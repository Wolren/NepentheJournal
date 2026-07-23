#!/usr/bin/env python3
"""
DoseWiki data pack: download + slim pipeline for Nepenthe Journal.

Downloads the CC0 SubstanceIndex.json from DoseWiki, extracts only the
fields Nepenthe needs (subjective effects, dosage, duration, interactions,
pharmacology, harm potential, legality, tolerance, citations), and writes
a slimmed JSON to be bundled as an app resource.

Usage:
    python3 scripts/dosewiki_slim.py

Output: composeApp/src/jvmMain/resources/dosewiki_slim.json
        composeApp/src/desktopMain/resources/dosewiki_slim.json

DoseWiki content: CC0 (public domain). See https://dose.wiki
"""

import json
import os
import urllib.request

SOURCE_URL = "https://dosewiki-admin.vercel.app/SubstanceIndex.json"
HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.normpath(os.path.join(HERE, ".."))

OUTPUT_PATHS = [
    os.path.join(PROJECT, "composeApp", "src", "jvmMain", "resources", "dosewiki_slim.json"),
    os.path.join(PROJECT, "composeApp", "src", "desktopMain", "resources", "dosewiki_slim.json"),
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
    return result


def download_source() -> list:
    """Download or read cached SubstanceIndex.json."""
    cache_path = os.path.join(HERE, "SubstanceIndex.json")

    if os.path.exists(cache_path):
        print(f"Using cached {cache_path}")
        with open(cache_path, encoding="utf-8") as f:
            return json.load(f)

    print(f"Downloading from {SOURCE_URL}...")
    req = urllib.request.Request(
        SOURCE_URL,
        headers={"User-Agent": "NepentheJournal/0.1 (data pipeline)"}
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = json.load(resp)

    # Cache the raw download
    with open(cache_path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print(f"Downloaded {len(data)} substances, cached to {cache_path}")
    return data


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
