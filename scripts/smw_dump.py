#!/usr/bin/env python3
"""
One-shot SMW dump: pulls ALL substance data from PsychonautWiki's Semantic
MediaWiki API and writes it as a JournalSnapshot JSON file.

Usage:
    python scripts/smw_dump.py [--output path/to/dump.json] [--verbose]

The output matches the JournalSnapshot format that JournalJson.load() can
read, so the app can be seeded by copying this file into the data directory.

Run this periodically (monthly) to refresh the seed data. The app also
falls through to the live SMW API as a backup.
"""

import argparse
import json
import sys
import time
import urllib.parse
from datetime import datetime, timezone
from typing import Any

import httpx

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

SMW_API = "https://psychonautwiki.org/w/api.php"
USER_AGENT = "NepentheJournal/1.0 (+https://github.com/Wolren/psychonaut-journal) dump script"
REQUEST_DELAY = 0.15  # seconds between smwbrowse calls (polite)
HTTP_TIMEOUT = 30.0

# Properties we skip (internal SMW metadata)
SKIP_PROPS = {"_INST", "_ASK", "_MDAT", "_SKEY", "_TYPE", "_ERRC", "_REDI"}

# Known route prefixes from the SMW schema
ROUTE_NAMES = [
    "Oral", "Sublingual", "Insufflated", "Inhalation", "Smoked",
    "Intravenous", "Intramuscular", "Subcutaneous", "Rectal",
    "Transdermal", "Buccal", "Topical", "Ophthalmic",
]

# Timing phases (in order)
TIME_PHASES = ["onset", "comeup", "peak", "offset", "afterglow", "total"]

# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------

_client = httpx.Client(headers={"User-Agent": USER_AGENT}, timeout=HTTP_TIMEOUT)


def smw_get(params: dict[str, str]) -> dict[str, Any]:
    url = f"{SMW_API}?{urllib.parse.urlencode(params)}"
    resp = _client.get(url)
    resp.raise_for_status()
    return resp.json()


def get_all_substance_names() -> list[str]:
    """Fetch all page titles in Category:Substance via SMW ask."""
    data = smw_get({
        "action": "ask",
        "query": "[[Category:Substance]]|limit=500",
        "format": "json",
    })
    results: dict = data["query"]["results"]
    return list(results.keys())


def get_substance_properties(subject: str) -> dict[str, list[str]]:
    """
    Fetch all SMW properties for a single substance page via smwbrowse.

    Returns {property_name: [string_values...]} where values are cleaned
    of SMW internal suffixes (#0##, #14##, etc.).
    """
    params = json.dumps({"subject": subject, "ns": 0}, separators=(",", ":"))
    data = smw_get({
        "action": "smwbrowse",
        "browse": "subject",
        "params": params,
        "format": "json",
    })
    props: dict[str, list[str]] = {}
    for entry in data.get("query", {}).get("data", []):
        name = entry["property"]
        if name in SKIP_PROPS:
            continue
        values = []
        for item in entry["dataitem"]:
            val = item["item"]
            dtype = item["type"]
            # Strip SMW page/namespace suffix
            if dtype == 9:  # page link
                val = val.split("#")[0]
            values.append(val)
        if values:
            props[name] = values
    return props


def get_summaries(substance_names: list[str]) -> dict[str, str]:
    """
    Batch-fetch page extracts (summaries) for all substance names.

    Returns {page_title: summary_text}.
    """
    summaries: dict[str, str] = {}

    # Query in batches of 50 (MediaWiki API max titles per query is 50)
    batch_size = 50
    for i in range(0, len(substance_names), batch_size):
        batch = substance_names[i:i + batch_size]
        try:
            data = smw_get({
                "action": "query",
                "prop": "extracts",
                "exintro": "1",
                "explaintext": "1",
                "titles": "|".join(batch),
                "format": "json",
            })
            for _pid, page in data.get("query", {}).get("pages", {}).items():
                title = page.get("title", "")
                extract = page.get("extract", "")
                if title and extract:
                    summaries[title] = extract.strip()
        except Exception:
            pass  # non-critical; summaries are optional

    return summaries


# ---------------------------------------------------------------------------
# SMW -> Substance model mapping
# ---------------------------------------------------------------------------

def _extract_route_props(
    props: dict[str, list[str]], prefix: str,
) -> tuple[dict[str, str], dict[str, str]]:
    """
    Extract dose + duration properties for a given route prefix (e.g. "Oral").

    Returns (dose_map, duration_map) with string-formatted values matching
    the existing `dosageBands` and `durationProfile` format from the model.
    """
    doses: dict[str, str] = {}
    durations: dict[str, str] = {}

    units = _first(props, f"{prefix}_dose_units", "")

    threshold = _first(props, f"{prefix}_threshold_dose")
    if threshold:
        doses["threshold"] = f"{threshold} {units}".strip()

    heavy = _first(props, f"{prefix}_heavy_dose")
    if heavy:
        doses["heavy"] = f"{heavy} {units}".strip()

    light_min = _first(props, f"{prefix}_min_light_dose")
    light_max = _first(props, f"{prefix}_max_light_dose")
    if light_min or light_max:
        doses["light"] = _format_range(light_min, light_max, units)

    common_min = _first(props, f"{prefix}_min_common_dose")
    common_max = _first(props, f"{prefix}_max_common_dose")
    if common_min or common_max:
        doses["common"] = _format_range(common_min, common_max, units)

    strong_min = _first(props, f"{prefix}_min_strong_dose")
    strong_max = _first(props, f"{prefix}_max_strong_dose")
    if strong_min or strong_max:
        doses["strong"] = _format_range(strong_min, strong_max, units)

    # Duration phases
    for phase in TIME_PHASES:
        phase_units = _first(props, f"{prefix}_{phase}_time_units")
        min_val = _first(props, f"{prefix}_min_{phase}_time")
        max_val = _first(props, f"{prefix}_max_{phase}_time")
        if min_val or max_val:
            durations[phase] = _format_range(min_val, max_val, phase_units)

    return doses, durations


def _first(props: dict[str, list[str]], key: str, default: Any | None = None) -> Any | None:
    vals = props.get(key)
    if vals and len(vals) > 0:
        return vals[0]
    return default


def clean_wiki_markup(text: str) -> str:
    """Strip MediaWiki [[Target|Display]] and [[Target]] markup."""
    import re
    # [[Target|Display]] -> Display
    text = re.sub(r'\[\[([^|\]]+)\|([^\]]+)\]\]', r'\2', text)
    # [[Target]] -> Target
    text = re.sub(r'\[\[([^\]]+)\]\]', r'\1', text)
    return text


def _format_range(min_val: str | None, max_val: str | None, units: str) -> str:
    parts = []
    if min_val:
        parts.append(str(min_val))
    if max_val:
        parts.append(str(max_val))
    result = "\u2013".join(parts) if len(parts) == 2 else (parts[0] if parts else "")
    if units:
        result = f"{result} {units}"
    return result


def smw_to_substance(
    subject: str, props: dict[str, list[str]], summary: str | None, now_ms: int,
) -> dict[str, Any]:
    """Convert SMW property dict to a Substance dict matching Kotlin model."""
    name = subject
    sub_id = f"pwiki:{name.lower().replace(' ', '_')}"

    # Aliases from Common_name (skip the primary name)
    common_names = props.get("Common_name", [])
    aliases = [c for c in common_names if c.lower() != name.lower()]

    # Chemical + psychoactive classes
    substance_class: list[str] = []
    chem = props.get("Chemical_class", [])
    if chem:
        substance_class.append(", ".join(chem))
    psycho = props.get("Psychoactive_class", [])
    if psycho:
        substance_class.append(", ".join(psycho))

    # Routes of administration (detected from SMW properties)
    routes: list[str] = []
    for route in ROUTE_NAMES:
        if f"{route}_dose_units" in props or f"{route}_threshold_dose" in props:
            routes.append(route)

    # First route's dose/duration for the "primary" band
    primary_doses: dict[str, str] = {}
    primary_durations: dict[str, str] = {}
    if routes:
        primary_doses, primary_durations = _extract_route_props(props, routes[0])

    # Effects from both Effect and Effects properties
    effects: list[str] = []
    for ef in props.get("Effect", []):
        if isinstance(ef, str):
            effects.append(ef.replace("_", " ").title())
    for ef in props.get("Effects", []):
        if isinstance(ef, str):
            effects.append(ef.replace("_", " ").title())

    # Cross-tolerances
    cross_tols: list[str] = []
    ct = _first(props, "Cross-tolerance")
    if ct:
        cross_tols.append(clean_wiki_markup(ct))
    tft = _first(props, "Time_to_full_tolerance")
    if tft:
        cross_tols.append(f"Full tolerance: {clean_wiki_markup(tft)}")
    tzt = _first(props, "Time_to_zero_tolerance")
    if tzt:
        cross_tols.append(f"Zero tolerance: {clean_wiki_markup(tzt)}")
    tht = _first(props, "Time_to_half_tolerance")
    if tht:
        cross_tols.append(f"Half tolerance: {clean_wiki_markup(tht)}")

    # Toxicity (may be multiple)
    toxicity = [str(t) for t in props.get("Toxicity", [])]

    return {
        "id": sub_id,
        "docType": "substance",
        "createdAt": now_ms,
        "updatedAt": now_ms,
        "deviceOrigin": "system",
        "pwikiId": name,
        "name": name,
        "aliases": aliases,
        "summary": summary,
        "substanceClass": substance_class,
        "routesOfAdministration": routes,
        "dosageBands": primary_doses,
        "durationProfile": primary_durations,
        "addictionPotential": _first(props, "Addiction_potential"),
        "toxicity": toxicity,
        "crossTolerances": cross_tols,
        "effects": effects,
        "cachedAt": now_ms,
        "sourceVersion": "pwiki-smw-v1",
    }


def smw_to_interactions(
    subject: str, props: dict[str, list[str]], now_ms: int,
) -> list[dict[str, Any]]:
    """Extract interactions from SMW DangerousInteraction/UnsafeInteraction/UncertainInteraction."""
    sub_id = f"pwiki:{subject.lower().replace(' ', '_')}"
    interactions: list[dict[str, Any]] = []

    risk_map = {
        "DangerousInteraction": "DANGEROUS",
        "UnsafeInteraction": "UNSAFE",
        "UncertainInteraction": "UNCERTAIN",
    }

    for smw_prop, risk_level in risk_map.items():
        targets = props.get(smw_prop, [])
        for target in targets:
            target_id = f"pwiki:{target.lower().replace(' ', '_')}"
            sorted_ids = sorted([sub_id, target_id])
            int_id = f"interaction:{sorted_ids[0]}:{sorted_ids[1]}"
            interactions.append({
                "id": int_id,
                "docType": "interaction",
                "createdAt": now_ms,
                "updatedAt": now_ms,
                "deviceOrigin": "system",
                "substanceAId": sorted_ids[0],
                "substanceBId": sorted_ids[1],
                "riskLevel": risk_level,
                "sources": ["psychonautwiki"],
            })

    return interactions


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> None:
    parser = argparse.ArgumentParser(description="Dump PsychonautWiki SMW data to JSON")
    parser.add_argument(
        "--output", "-o",
        default="psychonautwiki_dump.json",
        help="Output JSON file path (default: psychonautwiki_dump.json)",
    )
    parser.add_argument(
        "--verbose", "-v",
        action="store_true",
        help="Print progress to stderr",
    )
    parser.add_argument(
        "--delay",
        type=float,
        default=REQUEST_DELAY,
        help=f"Delay between API calls in seconds (default: {REQUEST_DELAY})",
    )
    args = parser.parse_args()

    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)

    # Step 1: get all substance names
    if args.verbose:
        print("[*] Fetching substance list from SMW...", file=sys.stderr)

    names = get_all_substance_names()
    # Filter out Experience: pages (not real substances)
    names = [n for n in names if not n.startswith("Experience:")]
    if args.verbose:
        print(f"[+] Found {len(names)} substances (excluded Experience: pages)", file=sys.stderr)

    # Step 2: batch-fetch summaries via MediaWiki extracts API
    if args.verbose:
        print("[*] Batch-fetching summaries...", file=sys.stderr)
    summaries = get_summaries(names)
    if args.verbose:
        print(f"[+] Got {len(summaries)} summaries", file=sys.stderr)

    # Step 3: fetch properties for each substance
    substances: list[dict[str, Any]] = []
    all_interactions: dict[str, dict[str, Any]] = {}

    for i, name in enumerate(names, 1):
        if args.verbose:
            print(f"  [{i}/{len(names)}] {name}...", file=sys.stderr, end="")

        try:
            props = get_substance_properties(name)
        except Exception as e:
            if args.verbose:
                print(f" FAILED: {e}", file=sys.stderr)
            continue

        sub = smw_to_substance(name, props, summaries.get(name), now_ms)
        substances.append(sub)

        ints = smw_to_interactions(name, props, now_ms)
        for interaction in ints:
            all_interactions[interaction["id"]] = interaction

        if args.verbose:
            print(f" {len(props)} props, {len(ints)} interactions", file=sys.stderr)

        if i < len(names):
            time.sleep(args.delay)

    # Step 4: build JournalSnapshot
    snapshot = {
        "version": 3,
        "savedAt": now_ms,
        "sessions": [],
        "substances": substances,
        "doses": [],
        "notes": [],
        "timelineEvents": [],
        "interactions": list(all_interactions.values()),
        "customUnits": [],
        "useShulginRating": False,
    }

    # Step 5: write output
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(snapshot, f, indent=2, ensure_ascii=False)

    if args.verbose:
        print(file=sys.stderr)
        print(f"[+] Wrote {len(substances)} substances + {len(all_interactions)} interactions", file=sys.stderr)
        print(f"[+] Output: {args.output}", file=sys.stderr)
        fs = len(json.dumps(snapshot, indent=2, ensure_ascii=False))
        print(f"[+] File size: {fs / 1024:.1f} KB ({fs / 1024 / 1024:.2f} MB)", file=sys.stderr)


if __name__ == "__main__":
    main()
