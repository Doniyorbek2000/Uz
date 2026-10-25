#!/usr/bin/env python3
"""Synthesises the key-click sound packs in shared/sounds/.

Every sound is generated from first principles (oscillators, filtered noise, envelopes), so the packs
carry no third-party licence. Output: 16-bit mono 44.1 kHz WAV files

    shared/sounds/<pack>/key1.wav … keyN.wav, delete.wav, space.wav, enter.wav
    shared/sounds/packs.json  — pack ids, number of key variants and how a variant is chosen

Usage: python3 tools/build_sounds.py
"""
from __future__ import annotations

import json
import logging
import math
import random
import struct
import wave
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "shared" / "sounds"
RATE = 44_100
PEAK = 0.89

log = logging.getLogger("build_sounds")

Signal = list[float]


# ---- Building blocks -----------------------------------------------------------------------------

def silence(seconds: float) -> Signal:
    return [0.0] * int(seconds * RATE)


def length(seconds: float) -> int:
    return max(1, int(seconds * RATE))


def sine(freq: float | Callable[[float], float], seconds: float, phase: float = 0.0) -> Signal:
    """Sine oscillator; freq may be a function of time for glides."""
    out = []
    for i in range(length(seconds)):
        t = i / RATE
        f = freq(t) if callable(freq) else freq
        phase += 2 * math.pi * f / RATE
        out.append(math.sin(phase))
    return out


def square(freq: float | Callable[[float], float], seconds: float, duty: float = 0.5) -> Signal:
    out = []
    position = 0.0
    for i in range(length(seconds)):
        t = i / RATE
        f = freq(t) if callable(freq) else freq
        position = (position + f / RATE) % 1.0
        out.append(1.0 if position < duty else -1.0)
    return out


def noise(seconds: float, rng: random.Random) -> Signal:
    return [rng.uniform(-1.0, 1.0) for _ in range(length(seconds))]


def decay(signal: Signal, time_constant: float, attack: float = 0.0008) -> Signal:
    """Exponential decay envelope with a short linear attack (avoids clicks at the start)."""
    attack_samples = max(1, int(attack * RATE))
    out = []
    for i, x in enumerate(signal):
        t = i / RATE
        gain = min(1.0, (i + 1) / attack_samples) * math.exp(-t / time_constant)
        out.append(x * gain)
    return out


def fade_out(signal: Signal, seconds: float = 0.004) -> Signal:
    n = min(len(signal), length(seconds))
    out = list(signal)
    for k in range(n):
        out[len(out) - n + k] *= 1.0 - (k + 1) / n
    return out


def lowpass(signal: Signal, cutoff: float) -> Signal:
    alpha = 1.0 - math.exp(-2 * math.pi * cutoff / RATE)
    out, y = [], 0.0
    for x in signal:
        y += alpha * (x - y)
        out.append(y)
    return out


def highpass(signal: Signal, cutoff: float) -> Signal:
    low = lowpass(signal, cutoff)
    return [x - l for x, l in zip(signal, low)]


def bandpass(signal: Signal, center: float, q: float) -> Signal:
    """RBJ biquad band-pass (constant 0 dB peak gain)."""
    w0 = 2 * math.pi * center / RATE
    alpha = math.sin(w0) / (2 * q)
    b0, b1, b2 = alpha, 0.0, -alpha
    a0, a1, a2 = 1 + alpha, -2 * math.cos(w0), 1 - alpha
    out = []
    x1 = x2 = y1 = y2 = 0.0
    for x in signal:
        y = (b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0
        x2, x1, y2, y1 = x1, x, y1, y
        out.append(y)
    return out


def mix(*parts: tuple[Signal, float], offsets: list[float] | None = None) -> Signal:
    """Sums (signal, gain) pairs, each optionally delayed by offsets[i] seconds."""
    offsets = offsets or [0.0] * len(parts)
    total = max(int(offset * RATE) + len(signal) for (signal, _), offset in zip(parts, offsets))
    out = [0.0] * total
    for (signal, gain), offset in zip(parts, offsets):
        start = int(offset * RATE)
        for i, x in enumerate(signal):
            out[start + i] += x * gain
    return out


def normalise(signal: Signal, level: float = 1.0) -> Signal:
    peak = max((abs(x) for x in signal), default=0.0) or 1.0
    return [x / peak * PEAK * level for x in signal]


def note(midi: float) -> float:
    return 440.0 * 2 ** ((midi - 69) / 12)


# ---- Packs -----------------------------------------------------------------------------------------

def mechanical(rng: random.Random) -> dict[str, Signal]:
    """A tactile mechanical switch: bright click, then the keycap bottoming out ("thock")."""
    def stroke(body_hz: float, body_tau: float, click_hz: float, rattle: float) -> Signal:
        click = decay(bandpass(noise(0.012, rng), click_hz, 1.4), 0.0018)
        body = decay(lowpass(noise(0.09, rng), 900), body_tau)
        tone = decay(sine(body_hz, 0.09), body_tau * 1.2)
        rattle_part = decay(bandpass(noise(0.05, rng), 2600, 3.0), 0.008)
        return mix((click, 0.9), (body, 0.75), (tone, 0.55), (rattle_part, rattle), offsets=[0, 0.004, 0.004, 0.018])

    sounds = {f"key{i + 1}": normalise(fade_out(stroke(body, 0.016, click, 0.12)), 0.92)
              for i, (body, click) in enumerate([(205, 4200), (190, 3900), (220, 4500)])}
    sounds["delete"] = normalise(fade_out(stroke(240, 0.013, 5000, 0.1)), 0.9)
    sounds["space"] = normalise(fade_out(stroke(128, 0.03, 3000, 0.35)), 1.0)
    sounds["enter"] = normalise(fade_out(stroke(150, 0.026, 3400, 0.3)), 1.0)
    return sounds


def typewriter(rng: random.Random) -> dict[str, Signal]:
    """A manual typewriter: metallic type-bar strike, ratchet for delete, bell for the carriage return."""
    def strike(pitch: float) -> Signal:
        impact = decay(highpass(noise(0.03, rng), 1500), 0.004)
        metal = mix(
            (decay(bandpass(noise(0.08, rng), 2100 * pitch, 18), 0.022), 1.0),
            (decay(bandpass(noise(0.08, rng), 3400 * pitch, 22), 0.016), 0.8),
        )
        thud = decay(lowpass(noise(0.06, rng), 500), 0.012)
        return normalise(fade_out(mix((impact, 0.8), (metal, 1.6), (thud, 0.7))), 0.9)

    sounds = {f"key{i + 1}": strike(p) for i, p in enumerate([1.0, 0.94, 1.07])}
    ratchet = [decay(bandpass(noise(0.02, rng), 3000, 5), 0.003) for _ in range(3)]
    sounds["delete"] = normalise(fade_out(mix(*[(r, 1.0) for r in ratchet], offsets=[0, 0.022, 0.044])), 0.8)
    sounds["space"] = normalise(fade_out(mix(
        (decay(lowpass(noise(0.08, rng), 700), 0.02), 1.0),
        (decay(bandpass(noise(0.05, rng), 1800, 6), 0.01), 0.4),
    )), 0.85)
    bell = mix(
        (decay(sine(note(96), 0.65), 0.2, attack=0.0004), 1.0),
        (decay(sine(note(96) * 2.76, 0.4), 0.07, attack=0.0004), 0.35),
        (decay(sine(note(96) * 5.4, 0.2), 0.03, attack=0.0004), 0.15),
    )
    sounds["enter"] = normalise(fade_out(mix((strike(0.9), 0.6), (bell, 0.9), offsets=[0, 0.03]), 0.05), 0.95)
    return sounds


def bubble(rng: random.Random) -> dict[str, Signal]:
    """Soap-bubble pops: a short sine whose pitch rises quickly, like air escaping under water."""
    def pop(start: float, end: float, seconds: float = 0.06, tau: float = 0.018) -> Signal:
        glide = lambda t: start + (end - start) * min(1.0, t / (seconds * 0.6))
        return normalise(fade_out(decay(sine(glide, seconds), tau, attack=0.002)), 0.7)

    sounds = {f"key{i + 1}": pop(s, s * 2.4) for i, s in enumerate([420, 470, 520, 580])}
    sounds["delete"] = pop(900, 380, tau=0.02)
    sounds["space"] = pop(260, 640, seconds=0.08, tau=0.026)
    sounds["enter"] = normalise(mix((pop(380, 900), 1.0), (pop(520, 1250), 0.9), offsets=[0, 0.06]), 0.9)
    return sounds


def drop(rng: random.Random) -> dict[str, Signal]:
    """Water drops: a resonant 'plip' — upward pitch glide with a damped ring."""
    def plip(base: float, ring: float = 0.035) -> Signal:
        glide = lambda t: base * (1 + 1.3 * (1 - math.exp(-t / 0.012)))
        body = decay(sine(glide, 0.12), ring, attack=0.0015)
        splash = decay(highpass(noise(0.01, rng), 3000), 0.0015)
        return normalise(fade_out(mix((body, 1.0), (splash, 0.15))), 0.85)

    sounds = {f"key{i + 1}": plip(b) for i, b in enumerate([700, 780, 860])}
    sounds["delete"] = plip(560, 0.03)
    sounds["space"] = plip(430, 0.05)
    sounds["enter"] = normalise(mix((plip(600), 0.9), (plip(900), 1.0), offsets=[0, 0.07]), 0.9)
    return sounds


def soft(rng: random.Random) -> dict[str, Signal]:
    """A quiet, muffled tap for typing in public places."""
    def tap(cutoff: float, body_hz: float, tau: float = 0.012) -> Signal:
        thump = decay(lowpass(lowpass(noise(0.05, rng), cutoff), cutoff), tau, attack=0.0015)
        tone = decay(sine(body_hz, 0.05), tau, attack=0.0015)
        return normalise(fade_out(mix((thump, 1.0), (tone, 0.5))), 0.6)

    sounds = {f"key{i + 1}": tap(c, b) for i, (c, b) in enumerate([(1300, 310), (1200, 290), (1400, 330)])}
    sounds["delete"] = tap(1500, 360, 0.01)
    sounds["space"] = tap(900, 220, 0.018)
    sounds["enter"] = tap(1000, 250, 0.02)
    return sounds


MARIMBA_NOTES = [72, 74, 76, 79, 81, 84, 86, 88]  # C major pentatonic, C5–E6


def marimba(rng: random.Random) -> dict[str, Signal]:
    """A wooden marimba: every letter key plays a note of the pentatonic scale, so typing makes music."""
    def bar(midi: float, tau: float = 0.12) -> Signal:
        f = note(midi)
        tone = mix(
            (decay(sine(f, 0.45), tau, attack=0.0015), 1.0),
            (decay(sine(f * 3.93, 0.2), tau * 0.18, attack=0.0008), 0.3),
            (decay(sine(f * 9.2, 0.08), tau * 0.06, attack=0.0005), 0.08),
        )
        mallet = decay(lowpass(noise(0.01, rng), 2500), 0.0012)
        return normalise(fade_out(mix((tone, 1.0), (mallet, 0.1)), 0.02), 0.85)

    sounds = {f"key{i + 1}": bar(m) for i, m in enumerate(MARIMBA_NOTES)}
    sounds["delete"] = bar(67, 0.07)
    sounds["space"] = bar(60, 0.1)
    sounds["enter"] = normalise(mix((bar(72), 1.0), (bar(76), 1.0), (bar(79), 1.0), (bar(84), 1.0),
                                    offsets=[0, 0.055, 0.11, 0.165]), 0.9)
    return sounds


def retro(rng: random.Random) -> dict[str, Signal]:
    """8-bit game console blips: square waves with pitch steps, a noise snare and a coin sound."""
    def blip(midi: float, step: float = 7, seconds: float = 0.05) -> Signal:
        freq = lambda t: note(midi + (step if t > seconds * 0.35 else 0))
        return normalise(fade_out(decay(lowpass(square(freq, seconds, 0.25), 6000), seconds * 0.6, attack=0.001)), 0.4)

    sounds = {f"key{i + 1}": blip(m) for i, m in enumerate([76, 79, 81, 84])}
    sounds["delete"] = blip(79, step=-12)
    sounds["space"] = normalise(fade_out(decay(lowpass(noise(0.06, rng), 5000), 0.015)), 0.75)
    coin = mix((lowpass(square(note(83), 0.07, 0.5), 6000), 1.0), (lowpass(square(note(88), 0.28, 0.5), 6000), 1.0),
               offsets=[0, 0.07])
    sounds["enter"] = normalise(fade_out(decay(coin, 0.15, attack=0.001)), 0.45)
    return sounds


@dataclass(frozen=True)
class Pack:
    id: str
    build: Callable[[random.Random], dict[str, Signal]]
    # "random": a random key variant per press; "by_key": the same key always plays the same variant.
    variation: str = "random"


PACKS = [
    Pack("mechanical", mechanical),
    Pack("typewriter", typewriter),
    Pack("bubble", bubble),
    Pack("drop", drop),
    Pack("soft", soft),
    Pack("marimba", marimba, "by_key"),
    Pack("retro", retro, "by_key"),
]


def write_wav(path: Path, signal: Signal) -> None:
    frames = b"".join(struct.pack("<h", max(-32767, min(32767, int(round(x * 32767))))) for x in signal)
    with wave.open(str(path), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes(frames)


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    manifest = []
    total = 0
    for pack in PACKS:
        rng = random.Random(f"uzkey-{pack.id}")  # deterministic output: rebuilding changes nothing
        sounds = pack.build(rng)
        directory = OUT_DIR / pack.id
        directory.mkdir(exist_ok=True)
        for old in directory.glob("*.wav"):
            old.unlink()
        for name, signal in sounds.items():
            write_wav(directory / f"{name}.wav", signal)
            total += len(signal) * 2
        keys = sum(1 for name in sounds if name.startswith("key"))
        missing = {"delete", "space", "enter"} - sounds.keys()
        if missing or keys == 0:
            raise SystemExit(f"pack {pack.id} lacks {sorted(missing) or 'key sounds'}")
        manifest.append({"id": pack.id, "keys": keys, "variation": pack.variation})
        log.info("%-11s %d key variants", pack.id, keys)
    (OUT_DIR / "packs.json").write_text(json.dumps({"packs": manifest}, indent=2) + "\n", encoding="utf-8")
    log.info("wrote %d packs, %.0f KB of audio", len(manifest), total / 1024)


if __name__ == "__main__":
    main()
