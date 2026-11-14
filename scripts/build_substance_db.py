#!/usr/bin/env python3
"""Build the bundled substance reference database.

Reads the DoseWiki slim export and the PsychonautWiki seed, applies the merge
the app performed at startup, and writes a deterministic SQLite file plus a
manifest. Output is byte-stable: no wall-clock values, every row sorted.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESOURCES = ROOT / "composeApp" / "src" / "desktopMain" / "resources"
DEFAULT_OUT = ROOT / "build" / "substance-db"

SCHEMA_VERSION = 2

SCHEMA = """
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE source(
    name TEXT PRIMARY KEY,
    licence TEXT NOT NULL,
    url TEXT NOT NULL,
    attribution TEXT
);
CREATE TABLE substance(
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    summary TEXT,
    cid INTEGER,
    pwiki_id TEXT,
    addiction_potential TEXT,
    half_life_minutes REAL,
    tmax_minutes REAL,
    source_version TEXT NOT NULL,
    device_origin TEXT NOT NULL,
    payload_json TEXT
);
CREATE TABLE substance_alias(
    substance_id TEXT NOT NULL REFERENCES substance(id),
    alias TEXT NOT NULL,
    source TEXT NOT NULL,
    PRIMARY KEY(substance_id, alias)
);
CREATE TABLE substance_class(
    substance_id TEXT NOT NULL REFERENCES substance(id),
    class TEXT NOT NULL,
    source TEXT NOT NULL,
    PRIMARY KEY(substance_id, class)
);
CREATE TABLE substance_route(
    substance_id TEXT NOT NULL REFERENCES substance(id),
    route TEXT NOT NULL,
    PRIMARY KEY(substance_id, route)
);
CREATE TABLE substance_toxicity(
    substance_id TEXT NOT NULL REFERENCES substance(id),
    text TEXT NOT NULL,
    primary key(substance_id, text)
);
CREATE TABLE substance_cross_tolerance(
    substance_id TEXT NOT NULL REFERENCES substance(id),
    name TEXT NOT NULL,
    PRIMARY KEY(substance_id, name)
);
CREATE TABLE dose_range(
    substance_id TEXT NOT NULL REFERENCES substance(id),
    route TEXT NOT NULL,
    route_order INTEGER NOT NULL,
    tier TEXT NOT NULL,
    min REAL,
    max REAL,
    unit TEXT,
    source TEXT NOT NULL,
    PRIMARY KEY(substance_id, route, tier)
);
CREATE TABLE duration_stage(
    substance_id TEXT NOT NULL REFERENCES substance(id),
    route TEXT NOT NULL,
    route_order INTEGER NOT NULL,
    stage TEXT NOT NULL,
    min REAL,
    max REAL,
    unit TEXT,
    source TEXT NOT NULL,
    PRIMARY KEY(substance_id, route, stage)
);
CREATE TABLE substance_effect(
    substance_id TEXT NOT NULL REFERENCES substance(id),
    name TEXT NOT NULL,
    domain TEXT,
    description TEXT,
    source TEXT NOT NULL,
    PRIMARY KEY(substance_id, name)
);
CREATE TABLE substance_identifier(
    substance_id TEXT NOT NULL REFERENCES substance(id),
    scheme TEXT NOT NULL,
    value TEXT NOT NULL,
    source TEXT NOT NULL,
    PRIMARY KEY(substance_id, scheme, value)
);
CREATE TABLE field_source(
    substance_id TEXT NOT NULL REFERENCES substance(id),
    field TEXT NOT NULL,
    source TEXT NOT NULL,
    PRIMARY KEY(substance_id, field, source)
);
CREATE VIRTUAL TABLE substance_fts USING fts5(
    id UNINDEXED,
    name,
    aliases,
    tokenize = 'unicode61 remove_diacritics 2'
);
CREATE INDEX dose_range_route ON dose_range(route);
CREATE INDEX substance_name ON substance(name);
"""

SOURCES = [
    ("dosewiki", "CC0-1.0", "https://dose.wiki/docs/license", "DoseWiki"),
    (
        "psychonautwiki",
        "CC-BY-SA-4.0",
        "https://psychonautwiki.org/wiki/PsychonautWiki:Copyrights",
        "PsychonautWiki",
    ),
    (
        "tripsit",
        "CC-BY-NC-SA-4.0",
        "https://tripsit.me",
        "TripSit interaction risk data, non-commercial attribution",
    ),
]

TIERS = ("threshold", "light", "moderate", "strong", "heavy")
STAGES = ("onset", "come_up", "peak", "offset", "after_effects", "total_duration")


def load(path: Path):
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def slugify(text: str) -> str:
    out = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
    return out or "substance"


def dw_id(entry: dict) -> str:
    slug = (entry.get("slug") or "").strip()
    return "dw:" + (slug or slugify(entry["title"]))


def primary_route(routes: list[dict], key: str) -> dict | None:
    if not routes:
        return None
    for route in routes:
        if (route.get("route") or "").lower() == "oral":
            return route
    for route in routes:
        if route.get(key):
            return route
    return routes[0]


RANGE_RE = re.compile(
    r"(\d+(?:\.\d+)?)\s*(?:-|to|\u2013)?\s*(\d+(?:\.\d+)?)?\s*"
    r"(min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days)",
    re.IGNORECASE,
)
ANIMAL_RE = re.compile(
    r"\b(rats?|mice|mouse|dogs?|rabbits?|primates?|non-human|in vitro|in vivo)\b",
    re.IGNORECASE,
)


def parse_minutes(text: str | None) -> float | None:
    if not text:
        return None
    match = RANGE_RE.search(text)
    if not match:
        return None
    low = float(match.group(1))
    high = float(match.group(2)) if match.group(2) else low
    factor = {
        "min": 1.0, "mins": 1.0, "minute": 1.0, "minutes": 1.0,
        "h": 60.0, "hr": 60.0, "hrs": 60.0, "hour": 60.0, "hours": 60.0,
        "d": 1440.0, "day": 1440.0, "days": 1440.0,
    }.get(match.group(3).lower())
    if factor is None:
        return None
    value = (low + high) / 2.0 * factor
    return value if value > 0 else None


def half_life_minutes(text: str | None) -> float | None:
    if not text or ANIMAL_RE.search(text):
        return None
    return parse_minutes(text)


def stage_text(stage: dict) -> str | None:
    low = stage.get("min")
    if low is None:
        return None
    high = stage.get("max", low)
    return f"{low}-{high} {stage.get('unit') or ''}"


def tmax_minutes(stages: dict | None) -> float | None:
    if not stages:
        return None
    peak = stages.get("peak")
    if peak:
        value = parse_minutes(stage_text(peak))
        if value is not None:
            return value
    onset = stages.get("onset")
    if not onset:
        return None
    base = parse_minutes(stage_text(onset))
    if base is None:
        return None
    come_up = parse_minutes(stage_text(stages["come_up"])) if stages.get("come_up") else None
    return base + (come_up or 0.0)


def band_words(tier: str) -> str:
    return {"moderate": "common"}.get(tier, tier)


class Catalog:
    def __init__(self) -> None:
        self.substances: dict[str, dict] = {}
        self.by_name: dict[str, str] = {}
        self.by_alias: dict[str, str] = {}
        self.rows: dict[str, list[tuple]] = {
            "alias": [], "class": [], "route": [], "dose": [], "duration": [],
            "effect": [], "toxicity": [], "cross": [], "identifier": [],
            "provenance": [],
        }

    def register(self, entry: dict) -> None:
        self.substances[entry["id"]] = entry
        self.by_name[entry["name"].strip().lower()] = entry["id"]
        for alias in entry.get("aliases", []):
            self.by_alias.setdefault(alias.strip().lower(), entry["id"])

    def register_aliases(self, substance_id: str) -> None:
        for row_id, alias, _source in self.rows["alias"]:
            if row_id == substance_id:
                self.by_alias.setdefault(alias.strip().lower(), substance_id)

    def match(self, names: list[str]) -> str | None:
        for name in names:
            key = (name or "").strip().lower()
            if not key:
                continue
            hit = self.by_name.get(key) or self.by_alias.get(key)
            if hit:
                return hit
        return None


def seed_entries(seed: dict) -> list[dict]:
    out = []
    for raw in seed.get("substances", []):
        out.append(
            {
                "id": raw["id"],
                "name": raw["name"],
                "summary": raw.get("summary"),
                "cid": raw.get("cid"),
                "pwiki_id": raw.get("pwikiId"),
                "aliases": list(raw.get("aliases") or []),
                "classes": list(raw.get("substanceClass") or []),
                "routes": list(raw.get("routesOfAdministration") or []),
                "addiction": raw.get("addictionPotential"),
                "half_life": raw.get("halfLifeMinutes"),
                "tmax": raw.get("tmaxMinutes"),
                "toxicity": list(raw.get("toxicity") or []),
                "cross": list(raw.get("crossTolerances") or []),
                "effects": list(raw.get("effects") or []),
                "source_version": raw.get("sourceVersion") or "seed",
                "payload": {
                    key: raw[key]
                    for key in (
                        "chemicalProperties", "chemblData", "iupharData",
                        "pdspData", "bindingdbData", "wikipediaData",
                    )
                    if raw.get(key)
                },
            }
        )
    return out


def apply_dosewiki(cat: Catalog, entry: dict, dw: dict) -> None:
    sid = entry["id"]
    dosage = (dw.get("dosage") or {}).get("routes") or []
    for order, route in enumerate(dosage):
        name = (route.get("route") or "").strip()
        if not name:
            continue
        cat.rows["route"].append((sid, name))
        ranges = route.get("dose_ranges") or {}
        for tier in TIERS:
            band = ranges.get(tier)
            if not band or band.get("min") is None:
                continue
            cat.rows["dose"].append(
                (sid, name, order, band_words(tier), band.get("min"), band.get("max"),
                 band.get("unit"), "dosewiki")
            )
    duration = (dw.get("duration") or {}).get("routes") or []
    for order, route in enumerate(duration):
        name = (route.get("route") or "").strip()
        if not name:
            continue
        stages = route.get("stages") or {}
        for stage in STAGES:
            value = stages.get(stage)
            if not value or value.get("min") is None:
                continue
            cat.rows["duration"].append(
                (sid, name, order, stage, value.get("min"), value.get("max"),
                 value.get("unit"), "dosewiki")
            )
    # Kinetics come from the primary route only, oral first, matching the Kotlin ingest.
    primary = next((r for r in duration if (r.get("route") or "").lower() == "oral"), None)
    if primary is None:
        primary = next((r for r in duration if r.get("stages")), None)
    if primary is not None:
        parsed_half_life = half_life_minutes(primary.get("half_life"))
        if parsed_half_life:
            entry["half_life"] = parsed_half_life
        parsed_tmax = tmax_minutes(primary.get("stages"))
        if parsed_tmax:
            entry["tmax"] = parsed_tmax

    identification = dw.get("identification") or {}
    for alias in identification.get("alternative_names") or []:
        value = (alias or "").strip()
        if value and value.lower() != entry["name"].strip().lower():
            cat.rows["alias"].append((sid, value, "dosewiki"))
    classification = dw.get("classification") or {}
    dw_classes = [
        (label or "").strip()
        for label in (classification.get("chemical_class") or [])
        + (classification.get("psychoactive_class") or [])
        if (label or "").strip()
    ]
    # DoseWiki replaces the seed's classes and routes whenever it carries any, as the Kotlin ingest does.
    if dw_classes:
        cat.rows["class"] = [row for row in cat.rows["class"] if row[0] != sid]
        for label in dw_classes:
            cat.rows["class"].append((sid, label, "dosewiki"))
    dw_routes = [
        (route.get("route") or "").strip()
        for route in dosage
        if (route.get("route") or "").strip()
    ]
    if dw_routes:
        cat.rows["route"] = [row for row in cat.rows["route"] if row[0] != sid]
        for label in dw_routes:
            cat.rows["route"].append((sid, label))
    harm = dw.get("harm_potential") or {}
    addiction = harm.get("addiction") or {}
    if not entry.get("addiction"):
        entry["addiction"] = (addiction.get("description") or "").strip() or (
            addiction.get("level") or ""
        ).strip() or None
    for organ in ((harm.get("toxicity") or {}).get("organ_toxicity") or []):
        system = (organ.get("system") or "").strip()
        findings = (organ.get("findings") or "").strip()
        text = f"{system}: {findings}" if system and findings else (findings or system)
        if text:
            cat.rows["toxicity"].append((sid, text))
    for key in ("psychosis", "seizure"):
        text = ((harm.get(key) or {}).get("description") or "").strip()
        if text:
            cat.rows["toxicity"].append((sid, text))
    for cross in (dw.get("tolerance") or {}).get("cross_tolerance") or []:
        value = (cross or "").strip()
        if value:
            cat.rows["cross"].append((sid, value))

    subjective = dw.get("subjective_effects") or {}
    dw_effects = []
    for domain in ("cognitive", "physical"):
        for group in (subjective.get(domain) or {}).values():
            for effect in group.get("effects") or []:
                name = (effect.get("name") or "").strip()
                if name:
                    dw_effects.append(
                        (sid, name, domain, (effect.get("description") or "").strip() or None,
                         "dosewiki")
                    )
    sensory = subjective.get("sensory") or {}
    for category in sensory.values():
        for group in (category.get("subcategories") or {}).values():
            for effect in group.get("effects") or []:
                name = (effect.get("name") or "").strip()
                if name:
                    dw_effects.append(
                        (sid, name, "sensory", (effect.get("description") or "").strip() or None,
                         "dosewiki")
                    )
    if dw_effects:
        cat.rows["effect"] = [row for row in cat.rows["effect"] if row[0] != sid]
        cat.rows["effect"].extend(dw_effects)
    if dw.get("summary"):
        entry["summary"] = dw["summary"].strip() or entry.get("summary")
    cat.rows["provenance"].append((sid, "summary", "dosewiki"))
    cat.rows["provenance"].append((sid, "dosage", "dosewiki"))
    cat.rows["provenance"].append((sid, "duration", "dosewiki"))
    cat.rows["provenance"].append((sid, "effects", "dosewiki"))


def build(sources_dir: Path) -> tuple[sqlite3.Connection, dict]:
    dosewiki = load(sources_dir / "dosewiki_slim.json")
    seed = load(sources_dir / "psychonautwiki_seed.json")

    catalog = Catalog()
    for entry in seed_entries(seed):
        catalog.register(entry)
        for alias in entry["aliases"]:
            catalog.rows["alias"].append((entry["id"], alias.strip(), "psychonautwiki"))
        for label in entry["classes"]:
            catalog.rows["class"].append((entry["id"], label.strip(), "psychonautwiki"))
        for route in entry["routes"]:
            catalog.rows["route"].append((entry["id"], route.strip()))
        for item in entry["toxicity"]:
            catalog.rows["toxicity"].append((entry["id"], item.strip()))
        for item in entry["cross"]:
            catalog.rows["cross"].append((entry["id"], item.strip()))
        for effect in entry["effects"]:
            catalog.rows["effect"].append((entry["id"], effect.strip(), None, None, "psychonautwiki"))
        if entry["cid"]:
            catalog.rows["identifier"].append(
                (entry["id"], "pubchem_cid", str(entry["cid"]), "psychonautwiki")
            )
        if entry["pwiki_id"]:
            catalog.rows["identifier"].append(
                (entry["id"], "psychonautwiki", entry["pwiki_id"], "psychonautwiki")
            )

    for dw in dosewiki:
        name = (dw.get("title") or "").strip()
        if not name:
            continue
        alt = ((dw.get("identification") or {}).get("alternative_names") or [])
        matched = catalog.match([name] + list(alt))
        if matched:
            entry = catalog.substances[matched]
        else:
            entry = {
                "id": dw_id(dw), "name": name, "summary": None, "cid": None,
                "pwiki_id": None, "aliases": [], "classes": [], "routes": [],
                "addiction": None, "half_life": None, "tmax": None,
                "toxicity": [], "cross": [], "effects": [],
                "source_version": "dosewiki", "payload": {},
            }
            catalog.register(entry)
        apply_dosewiki(catalog, entry, dw)
        catalog.register_aliases(entry["id"])

    connection = sqlite3.connect(":memory:")
    connection.executescript(SCHEMA)
    connection.executemany("INSERT INTO source VALUES(?,?,?,?)", SOURCES)

    for entry in sorted(catalog.substances.values(), key=lambda item: item["id"]):
        payload = entry.get("payload") or {}
        connection.execute(
            "INSERT INTO substance VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            (
                entry["id"], entry["name"], entry.get("summary"), entry.get("cid"),
                entry.get("pwiki_id"), entry.get("addiction"), entry.get("half_life"),
                entry.get("tmax"), entry.get("source_version") or "mixed",
                "system", json.dumps(payload, sort_keys=True) if payload else None,
            ),
        )

    def insert(table: str, query: str) -> None:
        rows = sorted(set(catalog.rows[table]), key=lambda row: tuple(str(v) for v in row))
        connection.executemany(query, rows)

    insert("alias", "INSERT OR IGNORE INTO substance_alias VALUES(?,?,?)")
    insert("class", "INSERT OR IGNORE INTO substance_class VALUES(?,?,?)")
    insert("route", "INSERT OR IGNORE INTO substance_route VALUES(?,?)")
    insert("toxicity", "INSERT OR IGNORE INTO substance_toxicity VALUES(?,?)")
    insert("cross", "INSERT OR IGNORE INTO substance_cross_tolerance VALUES(?,?)")
    insert(
        "dose",
        "INSERT OR IGNORE INTO dose_range VALUES(?,?,?,?,?,?,?,?)",
    )
    insert(
        "duration",
        "INSERT OR IGNORE INTO duration_stage VALUES(?,?,?,?,?,?,?,?)",
    )
    insert(
        "effect",
        "INSERT OR IGNORE INTO substance_effect VALUES(?,?,?,?,?)",
    )
    insert(
        "identifier",
        "INSERT OR IGNORE INTO substance_identifier VALUES(?,?,?,?)",
    )
    insert(
        "provenance",
        "INSERT OR IGNORE INTO field_source VALUES(?,?,?)",
    )

    for entry in sorted(catalog.substances.values(), key=lambda item: item["id"]):
        aliases = " ".join(
            sorted({alias for (sid, alias, _src) in catalog.rows["alias"] if sid == entry["id"]})
        )
        connection.execute(
            "INSERT INTO substance_fts(id, name, aliases) VALUES(?,?,?)",
            (entry["id"], entry["name"], aliases),
        )

    counts = {
        "substances": connection.execute("SELECT COUNT(*) FROM substance").fetchone()[0],
        "aliases": connection.execute("SELECT COUNT(*) FROM substance_alias").fetchone()[0],
        "classes": connection.execute("SELECT COUNT(*) FROM substance_class").fetchone()[0],
        "routes": connection.execute("SELECT COUNT(*) FROM substance_route").fetchone()[0],
        "dose_ranges": connection.execute("SELECT COUNT(*) FROM dose_range").fetchone()[0],
        "duration_stages": connection.execute(
            "SELECT COUNT(*) FROM duration_stage"
        ).fetchone()[0],
        "effects": connection.execute("SELECT COUNT(*) FROM substance_effect").fetchone()[0],
        "identifiers": connection.execute(
            "SELECT COUNT(*) FROM substance_identifier"
        ).fetchone()[0],
    }
    return connection, counts


def source_digest(sources_dir: Path) -> str:
    digest = hashlib.sha256()
    for name in ("dosewiki_slim.json", "psychonautwiki_seed.json"):
        digest.update((sources_dir / name).read_bytes())
    return digest.hexdigest()


def write_database(connection: sqlite3.Connection, out_dir: Path, content_version: str,
                   counts: dict) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / "substances.sqlite"
    if path.exists():
        path.unlink()
    disk = sqlite3.connect(path)
    with disk:
        connection.backup(disk)
    disk.close()

    manifest = {
        "schema_version": SCHEMA_VERSION,
        "content_version": content_version,
        "sqlite_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "sqlite_size_bytes": path.stat().st_size,
        **counts,
    }
    (out_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", type=Path, default=RESOURCES)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args()

    if not (args.sources / "dosewiki_slim.json").exists():
        print(f"missing dosewiki_slim.json under {args.sources}", file=sys.stderr)
        return 2

    connection, counts = build(args.sources)
    content_version = source_digest(args.sources)[:16]
    connection.execute("INSERT INTO meta VALUES('content_version', ?)", (content_version,))
    connection.execute("INSERT INTO meta VALUES('schema_version', ?)", (str(SCHEMA_VERSION),))
    connection.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")
    connection.commit()

    path = write_database(connection, args.out, content_version, counts)
    connection.close()

    print(json.dumps({"database": str(path), "content_version": content_version, **counts},
                     indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

