#!/usr/bin/env python3
"""Fetch drug.community dose bands for every substance in the cached navigation.

Navigation carries no slug, so each substance is probed with a few slug
spellings until one answers. Results append to a JSONL cache, and a rerun
skips substances already recorded, so the fetch resumes after a stop.
"""

from __future__ import annotations

import json
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

CACHE = Path(__file__).resolve().parent / "cache"
NAVIGATION = CACHE / "drug_community_navigation.json"
OUTPUT = CACHE / "drug_community_dose_bands.jsonl"
BASE = "https://drug.community/api/catalog/v1/substances/"
UA = "NepentheJournalCatalog/0.1 (+https://github.com/Wolren/NepentheJournal)"
DELAY_S = 0.35


def slugify(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-") or "substance"


def candidates(raw_name: str) -> list[str]:
    cleaned = raw_name.split(" (")[0].strip()
    inside = re.findall(r"\(([^)]*)\)", raw_name)
    out = [slugify(cleaned), slugify(raw_name)]
    out.extend(slugify(part) for part in inside if part.strip())
    return list(dict.fromkeys(out))


def fetch(url: str) -> tuple[int, bytes]:
    request = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, b""
    except (urllib.error.URLError, TimeoutError, OSError):
        return 0, b""


def done_names(path: Path) -> set[str]:
    names: set[str] = set()
    if not path.exists():
        return names
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            names.add(json.loads(line)["drug"])
        except (ValueError, KeyError):
            continue
    return names


def main() -> int:
    navigation = json.loads(NAVIGATION.read_text(encoding="utf-8"))
    drugs = [entry.get("drug_name", "").strip() for entry in navigation.get("drugs") or []]
    drugs = [name for name in drugs if name]
    seen = done_names(OUTPUT)
    pending = [name for name in drugs if name not in seen]
    print(f"drugs={len(drugs)} done={len(seen)} pending={len(pending)}", flush=True)

    with OUTPUT.open("a", encoding="utf-8") as sink:
        for index, name in enumerate(pending, start=1):
            found = False
            for slug in candidates(name):
                status, body = fetch(BASE + slug + "/dose-bands")
                if status == 200 and body:
                    record = {"drug": name, "slug": slug, "payload": json.loads(body)}
                    sink.write(json.dumps(record, sort_keys=True) + "\n")
                    sink.flush()
                    found = True
                    break
                time.sleep(DELAY_S)
            if not found:
                sink.write(json.dumps({"drug": name, "slug": None, "payload": None}) + "\n")
                sink.flush()
            if index % 25 == 0:
                print(f"{index}/{len(pending)} {name}", flush=True)
            time.sleep(DELAY_S)
    print("done", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
