#!/usr/bin/env python3
"""Parse the cached TripSit drug list into catalogue rows.

The API returns one object keyed by slug. Each entry carries free text like
"Oral Light: 20-40mg Common: 40-80mg | Insufflated Light: 10-20mg".
This module turns that text into route-scoped dose rows and leaves identity
resolution to the caller.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

ROUTES = (
    "oral", "insufflated", "intranasal", "rectal", "smoked", "intravenous",
    "intramuscular", "sublingual", "inhaled", "transdermal", "subcutaneous", "buccal",
)
TIERS = ("threshold", "light", "common", "strong", "heavy")

ROUTE_PATTERN = re.compile(r"\b(" + "|".join(ROUTES) + r")\b", re.IGNORECASE)
TIER_PATTERN = re.compile(
    r"\b(" + "|".join(TIERS) + r")\s*:\s*([0-9][0-9.,/\-]*\s*[A-Za-z\u00b5/%'s]*)",
    re.IGNORECASE,
)
VALUE_PATTERN = re.compile(
    r"([0-9]+(?:\.[0-9]+)?)\s*(mg|ug|\u00b5g|mcg|g|ml)?"
    r"(?:\s*-\s*([0-9]+(?:\.[0-9]+)?)\s*(mg|ug|\u00b5g|mcg|g|ml)?)?",
    re.IGNORECASE,
)

ROUTE_ALIASES = {"intranasal": "insufflated"}
UNIT_ALIASES = {"ug": "\u00b5g", "mcg": "\u00b5g"}


def parse_dose(text: str) -> list[tuple[str, str, float, float | None, str]]:
    """Return (route, tier, min, max, unit) rows for one dose string."""
    rows: list[tuple[str, str, float, float | None, str]] = []
    for segment in re.split(r"\s*[|]\s*", text):
        segment = re.split(r"\bNOTE\b", segment, maxsplit=1, flags=re.IGNORECASE)[0]
        if not segment.strip():
            continue
        current_route = "oral"
        events: list[tuple[int, str, str]] = []
        for match in ROUTE_PATTERN.finditer(segment):
            events.append((match.start(), "route", match.group(1).lower()))
        for match in TIER_PATTERN.finditer(segment):
            events.append((match.start(), "tier", match.group(0)))
            events.append((match.end(), "value", match.group(2)))
        events.sort()
        pending: tuple[str, str] | None = None
        for _position, kind, payload in events:
            if kind == "route":
                current_route = ROUTE_ALIASES.get(payload, payload)
            elif kind == "tier":
                tier_match = re.match(r"([a-z]+)", payload.strip(), re.IGNORECASE)
                if tier_match:
                    pending = (current_route, tier_match.group(1).lower())
            elif kind == "value" and pending is not None:
                parsed = parse_value(payload)
                if parsed is not None:
                    rows.append((pending[0], pending[1], parsed[0], parsed[1], parsed[2]))
                pending = None
    return rows


def parse_value(raw: str) -> tuple[float, float | None, str] | None:
    """'20-40mg' -> (20, 40, mg); '130mg+' -> (130, None, mg); '750ug' -> (750, 750, µg)."""
    cleaned = raw.strip().rstrip("'s.").rstrip("+").strip()
    match = VALUE_PATTERN.search(cleaned)
    if not match:
        return None
    low = float(match.group(1))
    high = float(match.group(3)) if match.group(3) else low
    unit = (match.group(4) or match.group(2) or "mg").lower()
    unit = UNIT_ALIASES.get(unit, unit)
    return low, high, unit


def load(path: Path) -> list[dict]:
    """One dict per TripSit entry, with parsed dose rows and cleaned names."""
    raw = json.loads(path.read_text(encoding="utf-8"))
    data = raw["data"]
    entries = data[0] if isinstance(data, list) else data
    out: list[dict] = []
    for slug, entry in sorted(entries.items()):
        properties = entry.get("properties") or {}
        name = (entry.get("pretty_name") or entry.get("name") or slug).strip()
        dose_text = properties.get("dose") or ""
        out.append(
            {
                "slug": slug,
                "name": name,
                "aliases": [a.strip() for a in (properties.get("aliases") or entry.get("aliases") or []) if a and a.strip()],
                "classes": [c.strip() for c in (properties.get("categories") or entry.get("categories") or []) if c and c.strip()],
                "summary": (properties.get("summary") or "").strip() or None,
                "dose": parse_dose(dose_text) if dose_text else [],
            }
        )
    return out
