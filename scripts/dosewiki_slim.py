#!/usr/bin/env python3
"""
DoseWiki data pack: download + slim pipeline for Nepenthe Journal.

Downloads the SubstanceIndex from DoseWiki open data
(https://dose.wiki/open-data/SubstanceIndex.json merged with the
raw GitHub mirror by slug, live dump winning), extracts only the fields Nepenthe needs (subjective
effects, dosage, duration, interactions, pharmacology, harm potential,
legality, tolerance, citations), and writes
a slimmed JSON to be bundled as an app resource.

Usage:
    python3 scripts/dosewiki_slim.py

Output (all four copies are written with identical bytes):
    composeApp/src/desktopMain/resources/dosewiki_slim.json
    composeApp/src/jvmMain/resources/dosewiki_slim.json
    composeApp/src/iosMain/resources/dosewiki_slim.json
    composeApp/src/androidMain/assets/dosewiki_slim.json

The raw DoseWiki SubstanceIndex.json download is cached at
scripts/cache/SubstanceIndex.json (gitignored regenerable). Test
fixtures under commonTest/desktopTest resources are small hand-written
fixtures and are NOT overwritten by this pipeline.

DoseWiki substance prose is CC0 1.0; the interactions field keeps
TripSit's non-commercial attribution terms (sources recorded as
["dosewiki", "tripsit"]). See https://dose.wiki/docs/license
"""

import json
import os
import re
import urllib.request

SOURCE_URL = "https://dose.wiki/open-data/SubstanceIndex.json"
# Fallbacks if the live open-data route is unreachable (order matters).
SOURCE_FALLBACKS = [
    "https://raw.githubusercontent.com/josikinzz/dosewiki/main/public/SubstanceIndex.json",
]
HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.normpath(os.path.join(HERE, ".."))

OUTPUT_PATHS = [
    os.path.join(PROJECT, "composeApp", "src", "desktopMain", "resources", "dosewiki_slim.json"),
    os.path.join(PROJECT, "composeApp", "src", "jvmMain", "resources", "dosewiki_slim.json"),
    os.path.join(PROJECT, "composeApp", "src", "iosMain", "resources", "dosewiki_slim.json"),
    os.path.join(PROJECT, "composeApp", "src", "androidMain", "assets", "dosewiki_slim.json"),
]

# Fields from the raw SubstanceIndex.json that we keep
KEEP_KEYS = {
    "title", "slug", "id", "summary",
    "identification",        # aliases, IUPAC, CAS, SMILES
    "classification",        # chemical_class, psychoactive_class
    "dosage",                # routes with dose_ranges
    "duration",              # routes with stages
    "subjective_effects",    # primary: per-substance effect descriptions
    "interactions",          # dangerous/unsafe/caution with reasons
    "pharmacology",          # pharmacodynamics, pharmacokinetics, metabolites
    "harm_potential",        # addiction, psychosis, seizure, toxicity
    "tolerance",             # cross_tolerance lists
    "legality",              # per-country status
    "citations",             # source references
    "reagent_testing",       # reagent test color data
}


def slim_substance(raw: dict) -> dict:
    """Extract only the fields Nepenthe needs from a raw substance entry."""
    result = {}
    for key in KEEP_KEYS:
        if key in raw:
            val = raw[key]
            # Skip empty fields to keep the slim file small
            if val is None or val == "" or val == [] or val == {}:
                continue
            result[key] = val
    normalize_subjective_effects(result)
    strip_citation_markup(result)
    return result


# Upstream prose carries inline citation placeholders ([citation-needed],
# [cite:doi-...], [cite:<legal-instrument>...], thousands of occurrences
# across summary, pharmacology, legality, harm potential). They leak into
# displayed text, so strip them at the source; the citations list field
# keeps the real references.
CITATION_RE = re.compile(r"\[(citation-needed|cite:[^\]]*)\]", re.IGNORECASE)


def strip_citation_markup(node):
    """Recursively remove citation placeholders from all strings in place."""
    if isinstance(node, dict):
        for key, val in node.items():
            if isinstance(val, str):
                cleaned = CITATION_RE.sub("", val)
                if cleaned != val:
                    node[key] = re.sub(r" {2,}", " ", cleaned).strip()
            else:
                strip_citation_markup(val)
    elif isinstance(node, list):
        for idx, val in enumerate(node):
            if isinstance(val, str):
                cleaned = CITATION_RE.sub("", val)
                if cleaned != val:
                    node[idx] = re.sub(r" {2,}", " ", cleaned).strip()
            else:
                strip_citation_markup(val)


# subjective_effects slots the Kotlin model declares as objects.
# Upstream sometimes emits them as (empty) lists; those carry no
# information and would fail strict deserialization, so drop them.
EFFECT_OBJECT_SLOTS = ("cognitive", "physical", "sensory")
SENSORY_SUB_SLOTS = (
    "auditory", "gustatory", "tactile", "visual", "olfactory",
    "multisensory", "multisensory_category",
)


def normalize_subjective_effects(result: dict) -> None:
    se = result.get("subjective_effects")
    if not isinstance(se, dict):
        return
    for slot in EFFECT_OBJECT_SLOTS:
        if slot in se and not isinstance(se[slot], dict):
            del se[slot]
    sensory = se.get("sensory")
    if isinstance(sensory, dict):
        for sub in SENSORY_SUB_SLOTS:
            if sub in sensory and not isinstance(sensory[sub], dict):
                del sensory[sub]
    if se == {}:
        del result["subjective_effects"]


def fetch_url(url: str) -> list:
    """Fetch one URL, transparently handling gzip and envelope shapes."""
    import gzip
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": "NepentheJournal/0.1 (data pipeline)",
            "Accept-Encoding": "gzip, identity",
            "Accept": "application/json",
        },
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        raw_bytes = resp.read()
        if resp.headers.get("Content-Encoding") == "gzip" or raw_bytes[:2] == b"\x1f\x8b":
            raw_bytes = gzip.decompress(raw_bytes)
        data = json.loads(raw_bytes.decode("utf-8"))
    # Open-data routes wrap records in an envelope. Two shapes exist:
    # live API routes use {"data": [...]}; the /open-data dumps use
    # {"dataset": ..., "items": [...], "license": ...}.
    if isinstance(data, dict) and isinstance(data.get("data"), list):
        return data["data"]
    if isinstance(data, dict) and isinstance(data.get("items"), list):
        return data["items"]
    if isinstance(data, list):
        return data
    raise ValueError(f"Unexpected SubstanceIndex shape from {url}")


def download_source() -> list:
    """Download or read cached SubstanceIndex.json (cache lives in scripts/cache/).

    Merges every reachable mirror by slug: the live open-data dump
    (reviewed substances, freshest) wins per slug, and entries found
    only in the raw GitHub mirror (low-priority stubs) are appended so
    coverage stays maximal.
    """
    cache_path = os.path.join(HERE, "cache", "SubstanceIndex.json")

    if os.path.exists(cache_path):
        print(f"Using cached {cache_path}")
        with open(cache_path, encoding="utf-8") as f:
            return json.load(f)

    urls = [SOURCE_URL] + SOURCE_FALLBACKS
    fetched: list[list] = []
    for url in urls:
        try:
            print(f"Downloading from {url}...")
            fetched.append(fetch_url(url))
        except Exception as e:  # noqa: BLE001 - try next mirror
            print(f"  failed ({e}), trying next source...")
    if not fetched:
        raise RuntimeError("All SubstanceIndex sources failed")
    data = merge_sources(fetched)
    # Cache the merged download
    os.makedirs(os.path.dirname(cache_path), exist_ok=True)
    with open(cache_path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print(f"Merged {len(data)} substances from {len(fetched)} source(s), cached to {cache_path}")
    return data


def merge_sources(sources: list[list]) -> list:
    """Merge substance lists by slug (title fallback); earlier sources win."""
    merged: dict[str, dict] = {}
    order: list[str] = []

    def key_of(entry: dict) -> str:
        slug = (entry.get("slug") or "").strip().lower()
        if slug:
            return "slug:" + slug
        return "title:" + (entry.get("title") or "").strip().lower()

    for source in sources:
        for entry in source:
            if not isinstance(entry, dict) or not entry.get("title"):
                continue
            key = key_of(entry)
            if key not in merged:
                merged[key] = entry
                order.append(key)
    return [merged[k] for k in order]


def main():
    print("=== DoseWiki Slim Pipeline ===")
    raw = download_source()

    slimmed = [slim_substance(s) for s in raw if s.get("title")]
    print(f"Slimmed {len(slimmed)} substances from {len(raw)} raw entries")

    for path in OUTPUT_PATHS:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(slimmed, f, separators=(",", ":"), ensure_ascii=False)
        size_kb = os.path.getsize(path) / 1024
        print(f"  -> {path} ({size_kb:.0f} KB)")

    # Count how many substances have subjective effects (our primary data source)
    with_effects = sum(1 for s in slimmed if "subjective_effects" in s)
    with_dosage = sum(1 for s in slimmed if "dosage" in s)
    with_duration = sum(1 for s in slimmed if "duration" in s)
    with_interactions = sum(1 for s in slimmed if "interactions" in s)

    print("\nStats:")
    print(f"  With subjective effects: {with_effects}")
    print(f"  With dosage data:       {with_dosage}")
    print(f"  With duration data:      {with_duration}")
    print(f"  With interaction data:   {with_interactions}")
    print("Done.")


if __name__ == "__main__":
    main()
