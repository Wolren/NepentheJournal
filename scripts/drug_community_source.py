#!/usr/bin/env python3
"""Read the cached drug.community navigation into catalogue rows.

Navigation carries names, aliases and class labels for 441 substances but no
dose ladders; dose bands need a per-substance API call and are added later.
"""

from __future__ import annotations

import json
import re
from pathlib import Path


def slugify(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-") or "substance"


def clean_name(raw: str) -> str:
    """'2C-F (2,5-dimethoxy-4-fluorophenethylamine)' -> '2C-F'."""
    return raw.split(" (")[0].strip() or raw.strip()


def load(path: Path) -> list[dict]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    out: list[dict] = []
    for entry in payload.get("drugs") or []:
        raw_name = (entry.get("drug_name") or "").strip()
        if not raw_name:
            continue
        name = clean_name(raw_name)
        aliases = [a.strip() for a in entry.get("alternative_names") or [] if a and a.strip()]
        if raw_name != name:
            aliases.append(raw_name)
        classes = [c.strip() for c in entry.get("categories") or [] if c and c.strip()]
        for key in ("psychoactive_class", "chemical_class"):
            value = (entry.get(key) or "").strip()
            if value:
                classes.append(value)
        out.append(
            {
                "slug": slugify(name),
                "name": name,
                "aliases": sorted(set(aliases)),
                "classes": sorted(set(classes)),
            }
        )
    return out
