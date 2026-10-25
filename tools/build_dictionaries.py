#!/usr/bin/env python3
"""Generate the bundled word lists in shared/dictionaries/.

Output format: a ``#sorted-keys`` header, then one entry per line, ``word<TAB>frequency[<TAB>key]``
where frequency is 1-255 and key is the normalised lookup key (written only when it differs from the
word); sorted by key in UTF-16 order with one entry per key, so the apps load it without normalising
or sorting.

* en, ru  — top words from the ``wordfreq`` package (data: CC BY-SA 4.0).
* uz_latn, uz_latn_2027, uz_cyrl — tools/uz_seed.txt merged with corpus frequencies from
  tools/uz_corpus_freq.tsv (produced by tools/build_uz_corpus.py), when that file exists.

Usage:
    pip install wordfreq
    python3 tools/build_dictionaries.py
"""
from __future__ import annotations

import logging
import math
import re
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SEED_PATH = ROOT / "tools" / "uz_seed.txt"
CORPUS_PATH = ROOT / "tools" / "uz_corpus_freq.tsv"
UZ_SIZE = 50_000
SEED_FLOOR_OFFSET = 50
OUT_DIR = ROOT / "shared" / "dictionaries"

MODIFIER_TURNED_COMMA = "ʻ"  # ʻ — marks oʻ / gʻ
MODIFIER_APOSTROPHE = "ʼ"  # ʼ — tutuq belgisi
WORDFREQ_SIZE = 25_000

log = logging.getLogger("build_dictionaries")


@dataclass(frozen=True)
class Entry:
    word: str
    freq: int


def parse_seed(path: Path) -> list[Entry]:
    entries: list[Entry] = []
    seen: set[str] = set()
    tier = 100
    index_in_tier = 0
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("@"):
            tier = int(line[1:].strip())
            index_in_tier = 0
            continue
        for token in line.split():
            token = token.replace(MODIFIER_TURNED_COMMA, "'").replace("‘", "'").replace("’", "'")
            if "-" in token:
                continue
            key = token.lower()
            if key in seen:
                continue
            seen.add(key)
            freq = max(1, tier - index_in_tier // 25)
            entries.append(Entry(token, freq))
            index_in_tier += 1
    return entries


def parse_corpus(path: Path) -> list[Entry]:
    """Corpus words come in the current Latin script with ʻ/ʼ; convert them to the seed notation."""
    if not path.exists():
        return []
    rows: list[tuple[str, int]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        word, _, count = line.partition("\t")
        if word and count.strip().isdigit():
            rows.append((word, int(count)))
    total = sum(count for _, count in rows) or 1
    entries = []
    for word, count in rows[:UZ_SIZE]:
        zipf = math.log10(count / total * 1e9)
        freq = max(1, min(255, round(zipf * 30) - 30))
        seed_word = word.replace(MODIFIER_TURNED_COMMA, "'").replace(MODIFIER_APOSTROPHE, "`")
        entries.append(Entry(seed_word, freq))
    return entries


def merge_uzbek(seed: list[Entry], corpus: list[Entry]) -> list[Entry]:
    if not corpus:
        return seed
    merged: dict[str, Entry] = {e.word.lower(): e for e in corpus}
    for entry in seed:
        key = entry.word.lower()
        floor = max(1, entry.freq - SEED_FLOOR_OFFSET)
        existing = merged.get(key)
        if existing is None or existing.freq < floor:
            # Keep the seed spelling: it carries the intended capitalisation.
            merged[key] = Entry(entry.word, floor)
        elif existing.word != entry.word and entry.word[0].isupper():
            merged[key] = Entry(entry.word, existing.freq)
    return sorted(merged.values(), key=lambda e: -e.freq)[:UZ_SIZE]


def to_latin_current(word: str) -> str:
    word = re.sub(r"([oOgG])'", lambda m: m.group(1) + MODIFIER_TURNED_COMMA, word)
    return word.replace("`", MODIFIER_APOSTROPHE).replace("'", MODIFIER_APOSTROPHE)


def to_latin_2027(word: str) -> str:
    replacements = [
        ("O'", "Ö"), ("o'", "ö"), ("G'", "Ğ"), ("g'", "ğ"),
        ("Sh", "Ş"), ("SH", "Ş"), ("sh", "ş"), ("Ch", "Ç"), ("CH", "Ç"), ("ch", "ç"),
    ]
    for old, new in replacements:
        word = word.replace(old, new)
    return word.replace("`", MODIFIER_APOSTROPHE).replace("'", MODIFIER_APOSTROPHE)


CYRILLIC_OVERRIDES = {
    "yanvar": "январь", "fevral": "февраль", "iyun": "июнь", "iyul": "июль",
    "sentyabr": "сентябрь", "oktyabr": "октябрь", "noyabr": "ноябрь", "dekabr": "декабрь",
    "kompyuter": "компьютер", "aprel": "апрель", "rossiya": "россия", "moskva": "москва",
    "amerika": "америка", "metro": "метро", "video": "видео", "internet": "интернет",
    "server": "сервер", "veb": "веб", "login": "логин", "parol": "пароль",
}

CYRILLIC_SINGLE = {
    "a": "а", "b": "б", "d": "д", "e": "е", "f": "ф", "g": "г", "h": "ҳ", "i": "и", "j": "ж",
    "k": "к", "l": "л", "m": "м", "n": "н", "o": "о", "p": "п", "q": "қ", "r": "р", "s": "с",
    "t": "т", "u": "у", "v": "в", "x": "х", "y": "й", "z": "з", "c": "с", "w": "в", "`": "ъ",
}
VOWELS = set("aeiouAEIOU")
# -nsiya words whose Cyrillic spelling really has с.
NSIYA_WITH_S = ("pensiya", "ekspansiya", "dispansiya")


def to_cyrillic(word: str) -> str:
    lower = word.lower()
    if lower in CYRILLIC_OVERRIDES:
        converted = CYRILLIC_OVERRIDES[lower]
        return converted[0].upper() + converted[1:] if word[0].isupper() else converted
    out: list[str] = []
    i = 0
    while i < len(lower):
        rest = lower[i:]
        prev = lower[i - 1] if i > 0 else ""
        # Russian loanwords write ц as "ts" after a vowel (revolyutsiya → революция). After a consonant it is
        # spelled "s", which cannot be told apart from a real с (konferensiya vs pensiya), so that stays с.
        if rest.startswith(("tsiya", "tsion")):
            out.append("ц"); i += 2
        elif rest.startswith("siya") and lower[max(0, i - 2):i] in ("en", "an") and not any(
            lower.startswith(stem) for stem in NSIYA_WITH_S
        ):
            # -ensiya/-ansiya loans are almost always -нция (konferensiya, stansiya, tendensiya).
            out.append("ц"); i += 1
        elif rest.startswith("o'"):
            out.append("ў"); i += 2
        elif rest.startswith("g'"):
            out.append("ғ"); i += 2
        elif rest.startswith("sh"):
            out.append("ш"); i += 2
        elif rest.startswith("ch"):
            out.append("ч"); i += 2
        elif rest.startswith("yo") and not rest.startswith("yo'"):
            out.append("ё"); i += 2
        elif rest.startswith("yu"):
            out.append("ю"); i += 2
        elif rest.startswith("ya"):
            out.append("я"); i += 2
        elif rest.startswith("ye"):
            out.append("е"); i += 2
        elif rest[0] == "e" and (i == 0 or prev in VOWELS):
            out.append("э"); i += 1
        elif rest[0] == "'":
            out.append("ъ"); i += 1
        else:
            out.append(CYRILLIC_SINGLE.get(rest[0], rest[0])); i += 1
    converted = "".join(out)
    return converted[0].upper() + converted[1:] if word[0].isupper() else converted


def from_wordfreq(lang: str, pattern: re.Pattern[str]) -> list[Entry]:
    from wordfreq import top_n_list, zipf_frequency

    entries: list[Entry] = []
    for word in top_n_list(lang, WORDFREQ_SIZE * 2):
        if not pattern.fullmatch(word):
            continue
        if len(word) == 1 and word not in {"a", "i", "в", "и", "к", "о", "с", "у", "я", "а"}:
            continue
        freq = max(1, min(255, round(zipf_frequency(word, lang) * 30) - 30))
        entries.append(Entry(word, freq))
        if len(entries) >= WORDFREQ_SIZE:
            break
    return entries


APOSTROPHE_LIKE = set("'\u2018\u2019`" + MODIFIER_TURNED_COMMA + MODIFIER_APOSTROPHE)


def normalize_key(word: str) -> str:
    """Mirror of TextRules.normalizeKey (Kotlin/Swift): lower-case, apostrophes folded to ', ё → е."""
    return "".join("'" if c in APOSTROPHE_LIKE else "е" if c == "ё" else c for c in word.lower())


def latin_2027_key(word: str) -> str:
    """Mirror of KeyNormalizer.LATIN_2027: the normalised key with oʻ gʻ sh ch spelled ö ğ ş ç."""
    key = normalize_key(word)
    out: list[str] = []
    i = 0
    while i < len(key):
        c = key[i]
        nxt = key[i + 1] if i + 1 < len(key) else ""
        if c in "og" and nxt == "'":
            out.append("ö" if c == "o" else "ğ")
            i += 2
        elif c in "sc" and nxt == "h":
            out.append("ş" if c == "s" else "ç")
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


# First line of every generated file: entries are sorted by key, and a missing key column means key == word.
SORTED_KEYS_HEADER = "#sorted-keys"


def utf16_order(key: str) -> bytes:
    """Sort key matching String comparison in Kotlin and the UTF-16 key comparison in Swift."""
    return key.encode("utf-16-be")


def write(name: str, entries: list[Entry]) -> None:
    """Writes `word<TAB>frequency[<TAB>key]` sorted by key, one entry per key.

    The key column (the normalised lookup key) is present only when it differs from the word. Shipping
    keys pre-sorted lets the apps load a dictionary without normalising and sorting 50 000 words on the
    phone; tests on both platforms check the keys against the runtime normaliser.
    """
    to_key = latin_2027_key if name == "uz_latn_2027" else normalize_key
    best: dict[str, tuple[Entry, str]] = {}
    for entry in entries:
        key = to_key(entry.word)
        existing = best.get(key)
        if existing is None or existing[0].freq < entry.freq:
            best[key] = (entry, key)
    ordered = sorted(best.values(), key=lambda item: utf16_order(item[1]))
    lines = []
    for entry, key in ordered:
        lines.append(f"{entry.word}\t{entry.freq}\n" if key == entry.word else f"{entry.word}\t{entry.freq}\t{key}\n")
    path = OUT_DIR / f"{name}.txt"
    path.write_text(SORTED_KEYS_HEADER + "\n" + "".join(lines), encoding="utf-8")
    log.info("wrote %s (%d words)", path.relative_to(ROOT), len(ordered))


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    seed = merge_uzbek(parse_seed(SEED_PATH), parse_corpus(CORPUS_PATH))
    write("uz_latn", [Entry(to_latin_current(e.word), e.freq) for e in seed])
    write("uz_latn_2027", [Entry(to_latin_2027(e.word), e.freq) for e in seed])
    write("uz_cyrl", [Entry(to_cyrillic(e.word), e.freq) for e in seed])
    write("en", from_wordfreq("en", re.compile(r"[a-z]+(?:'[a-z]+)?")))
    write("ru", from_wordfreq("ru", re.compile(r"[а-яё]+")))


if __name__ == "__main__":
    main()
