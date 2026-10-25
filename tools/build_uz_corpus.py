#!/usr/bin/env python3
"""Count Uzbek (Latin) word frequencies from public corpora.

Sources, in order of weight:
  1. Uzbek news text-classification corpus (Kuriyozov et al., 2023, Zenodo 7677431, ~120M words).
  2. Uzbek Wikipedia article dump (weighted down: many bot-generated geography stubs).

Writes tools/uz_corpus_freq.tsv: ``word<TAB>count`` sorted by count, apostrophes normalised
(oʻ gʻ with U+02BB, tutuq with U+02BC). Runs in CI (see .github/workflows/uz-dictionary.yml)
because the corpora are large.

Usage: python3 tools/build_uz_corpus.py [--work-dir DIR] [--top N]
"""
from __future__ import annotations

import argparse
import bz2
import collections
import csv
import html
import io
import json
import logging
import re
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_PATH = ROOT / "tools" / "uz_corpus_freq.tsv"
ZENODO_RECORD = "https://zenodo.org/api/records/7677431"
WIKI_DUMP = "https://dumps.wikimedia.org/uzwiki/latest/uzwiki-latest-pages-articles.xml.bz2"
WIKI_WEIGHT = 0.35

TURNED_COMMA = "ʻ"
TUTUQ = "ʼ"
APOSTROPHES = "'`‘’ʻʼʹ"
TOKEN_RE = re.compile(rf"[a-zA-Z{APOSTROPHES}]+")
VALID_RE = re.compile(rf"[a-z]+(?:[{TURNED_COMMA}{TUTUQ}][a-z]+)*[{TURNED_COMMA}]?")

log = logging.getLogger("build_uz_corpus")


def normalise(token: str) -> str | None:
    token = token.strip(APOSTROPHES)
    if not token or len(token) > 30:
        return None
    out: list[str] = []
    for i, ch in enumerate(token):
        if ch in APOSTROPHES:
            prev = token[i - 1].lower() if i > 0 else ""
            out.append(TURNED_COMMA if prev in ("o", "g") else TUTUQ)
        else:
            out.append(ch)
    word = "".join(out)
    if not VALID_RE.fullmatch(word.lower()):
        return None
    # Reject tokens that are clearly not Uzbek Latin (no vowels, or letter runs Uzbek never uses).
    lower = word.lower()
    if not re.search(r"[aeiou]", lower) or re.search(r"(.)\1\1", lower) or "w" in lower:
        return None
    return word


class Counter:
    def __init__(self) -> None:
        self.counts: collections.Counter[str] = collections.Counter()
        self.surface: dict[str, collections.Counter[str]] = collections.defaultdict(collections.Counter)

    def add_text(self, text: str, weight: float = 1.0) -> int:
        added = 0
        for raw in TOKEN_RE.findall(text):
            word = normalise(raw)
            if word is None:
                continue
            key = word.lower()
            self.counts[key] += weight
            if word[0].isupper() and word[1:].islower():
                self.surface[key]["cap"] += 1
            elif word.islower():
                self.surface[key]["low"] += 1
            added += 1
        return added

    def display(self, key: str) -> str:
        forms = self.surface.get(key)
        # Keep words capitalised only when they are (almost) always written that way: names, places.
        if forms and forms["cap"] > 9 * max(1, forms["low"]):
            return key[0].upper() + key[1:]
        return key


def download(url: str, target: Path) -> Path:
    if target.exists() and target.stat().st_size > 0:
        return target
    log.info("downloading %s", url)
    request = urllib.request.Request(url, headers={"User-Agent": "UzKey-dictionary-builder/1.0"})
    with urllib.request.urlopen(request, timeout=600) as response, target.open("wb") as out:
        while chunk := response.read(1 << 20):
            out.write(chunk)
    log.info("saved %s (%.1f MB)", target.name, target.stat().st_size / 1e6)
    return target


def iter_texts_from_file(path: Path):
    name = path.name.lower()
    if name.endswith(".zip"):
        with zipfile.ZipFile(path) as archive:
            for member in archive.namelist():
                if member.endswith("/"):
                    continue
                with archive.open(member) as handle:
                    yield from iter_texts_from_stream(member.lower(), handle)
    else:
        with path.open("rb") as handle:
            yield from iter_texts_from_stream(name, handle)


def iter_texts_from_stream(name: str, handle):
    text_handle = io.TextIOWrapper(handle, encoding="utf-8", errors="ignore")
    if name.endswith(".csv"):
        csv.field_size_limit(sys.maxsize)
        for row in csv.reader(text_handle):
            yield " ".join(row)
    elif name.endswith(".json") or name.endswith(".jsonl"):
        for line in text_handle:
            line = line.strip()
            if not line:
                continue
            try:
                item = json.loads(line)
            except json.JSONDecodeError:
                yield line
                continue
            yield " ".join(str(v) for v in item.values()) if isinstance(item, dict) else str(item)
    else:
        for line in text_handle:
            yield line


def add_news_corpus(counter: Counter, work: Path) -> None:
    with urllib.request.urlopen(ZENODO_RECORD, timeout=60) as response:
        record = json.load(response)
    log.info("zenodo record: %s (license: %s)", record["metadata"]["title"], record["metadata"].get("license"))
    for item in record["files"]:
        log.info("  file %s %.1f MB", item["key"], item["size"] / 1e6)
    for item in record["files"]:
        key = item["key"]
        if not key.lower().endswith((".zip", ".csv", ".txt", ".json", ".jsonl")):
            continue
        path = download(item["links"]["self"], work / key)
        words = sum(counter.add_text(text) for text in iter_texts_from_file(path))
        log.info("  %s: %d words", key, words)


WIKI_COMMENT = re.compile(r"<!--.*?-->", re.S)
WIKI_REF = re.compile(r"<ref[^>/]*/>|<ref[^>]*>.*?</ref>", re.S | re.I)
WIKI_TAG_BLOCK = re.compile(r"<(math|code|pre|syntaxhighlight|gallery|timeline|score)[^>]*>.*?</\1>", re.S | re.I)
WIKI_TAG = re.compile(r"<[^>]+>")
WIKI_TEMPLATE = re.compile(r"\{\{[^{}]*\}\}")
WIKI_TABLE = re.compile(r"\{\|[^{}]*?\|\}", re.S)
WIKI_LINK = re.compile(r"\[\[([^\[\]|]*)(?:\|([^\[\]]*))?\]\]")
WIKI_EXTERNAL = re.compile(r"\[(?:https?:)?//[^\s\]]+\s*([^\]]*)\]")
WIKI_URL = re.compile(r"https?://\S+")
WIKI_EMPHASIS = re.compile(r"''+")
WIKI_NS = re.compile(r"<ns>(\d+)</ns>")
WIKI_TEXT = re.compile(r"<text[^>]*>(.*?)</text>", re.S)


def _wiki_link(match: re.Match[str]) -> str:
    target, label = match.group(1), match.group(2)
    # [[Turkum:...]], [[Fayl:...]], [[en:...]]: namespaces and interwiki links are not prose.
    if ":" in target:
        return " "
    return label if label is not None else target


def clean_wikitext(text: str) -> str:
    """Reduces MediaWiki markup to plain prose: drops templates, tables, references, files and categories."""
    text = html.unescape(text)
    text = WIKI_COMMENT.sub(" ", text)
    text = WIKI_REF.sub(" ", text)
    text = WIKI_TAG_BLOCK.sub(" ", text)
    for _ in range(8):  # templates and tables nest; each pass peels the innermost layer
        reduced = WIKI_TABLE.sub(" ", WIKI_TEMPLATE.sub(" ", text))
        if reduced == text:
            break
        text = reduced
    for _ in range(3):  # file captions contain links: [[Fayl:x.jpg|... [[y]] ...]]
        reduced = WIKI_LINK.sub(_wiki_link, text)
        if reduced == text:
            break
        text = reduced
    text = WIKI_EXTERNAL.sub(lambda m: m.group(1), text)
    text = WIKI_URL.sub(" ", text)
    text = WIKI_TAG.sub(" ", text)
    text = WIKI_EMPHASIS.sub("", text)
    lines = []
    for line in text.splitlines():
        stripped = line.strip()
        # Residue of multi-line templates and tables, headings, navigation lists and magic words.
        if not stripped or stripped[0] in "|!{}=*#:;" or stripped.startswith("__"):
            continue
        lines.append(stripped)
    return "\n".join(lines)


def iter_wiki_articles(path: Path):
    """Yields the wikitext of main-namespace, non-redirect pages of a MediaWiki XML dump."""
    buffer: list[str] = []
    in_page = False
    with bz2.open(path, "rt", encoding="utf-8", errors="ignore") as dump:
        for line in dump:
            if "<page>" in line:
                in_page = True
                buffer = []
            if in_page:
                buffer.append(line)
            if in_page and "</page>" in line:
                in_page = False
                page = "".join(buffer)
                ns = WIKI_NS.search(page)
                if ns is None or ns.group(1) != "0" or "<redirect" in page:
                    continue
                body = WIKI_TEXT.search(page)
                if body:
                    yield body.group(1)


def add_wikipedia(counter: Counter, work: Path) -> None:
    path = download(WIKI_DUMP, work / "uzwiki.xml.bz2")
    words = 0
    pages = 0
    for wikitext in iter_wiki_articles(path):
        words += counter.add_text(clean_wikitext(wikitext), WIKI_WEIGHT)
        pages += 1
    log.info("wikipedia: %d articles, %d words", pages, words)


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    parser = argparse.ArgumentParser()
    parser.add_argument("--work-dir", type=Path, default=ROOT / "build" / "corpus")
    parser.add_argument("--top", type=int, default=80_000)
    parser.add_argument("--min-count", type=float, default=5)
    args = parser.parse_args()
    args.work_dir.mkdir(parents=True, exist_ok=True)

    counter = Counter()
    for name, source in (("news", add_news_corpus), ("wikipedia", add_wikipedia)):
        try:
            source(counter, args.work_dir)
        except Exception:  # one unavailable source must not discard the other
            log.exception("source %s failed", name)
    if not counter.counts:
        raise SystemExit("no corpus text could be read")

    rows = [(counter.display(key), count) for key, count in counter.counts.most_common(args.top) if count >= args.min_count]
    OUT_PATH.write_text("".join(f"{word}\t{int(round(count))}\n" for word, count in rows), encoding="utf-8")
    log.info("wrote %s: %d words, total tokens %.0f", OUT_PATH.relative_to(ROOT), len(rows), sum(counter.counts.values()))


if __name__ == "__main__":
    main()
