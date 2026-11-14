#!/usr/bin/env python3
"""Read the cached Wikidata medicines query into catalogue rows.

The query returns one row per statement combination, so rows collapse by
item. Only items with a PubChem CID and an ATC code are kept; labels are
English and identifiers stay attached to the item.
"""

from __future__ import annotations

import json
from pathlib import Path


def _value(row: dict, key: str) -> str | None:
    cell = row.get(key)
    return cell["value"].strip() if cell and cell.get("value") else None


def load(path: Path) -> list[dict]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    by_item: dict[str, dict] = {}
    for row in payload.get("results", {}).get("bindings", []):
        url = _value(row, "item")
        label = _value(row, "label")
        if not url or not label:
            continue
        qid = url.rsplit("/", 1)[-1]
        if qid in by_item:
            continue
        by_item[qid] = {
            "qid": qid,
            "name": label,
            "cid": _value(row, "cid"),
            "atc": _value(row, "atc"),
            "cas": _value(row, "cas"),
            "drugbank": _value(row, "drugbank"),
        }
    return [by_item[qid] for qid in sorted(by_item)]
