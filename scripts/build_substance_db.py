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

import drug_community_source
import tripsit_source
import wikidata_source

ROOT = Path(__file__).resolve().parent.parent
RESOURCES = ROOT / "composeApp" / "src" / "desktopMain" / "resources"
DEFAULT_OUT = ROOT / "build" / "substance-db"
TRIPSIT_PATH = Path(__file__).resolve().parent / "cache" / "tripsit_all_drugs.json"
DC_PATH = Path(__file__).resolve().parent / "cache" / "drug_community_navigation.json"
DC_BANDS_PATH = Path(__file__).resolve().parent / "cache" / "drug_community_dose_bands.jsonl"
SMW_PATH = Path(__file__).resolve().parent / "cache" / "smw_only_seed.json"
WIKIDATA_PATH = Path(__file__).resolve().parent / "cache" / "wikidata_medicines.json"
TARGET_SUBSTANCES = 1675

SCHEMA_VERSION = 4

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
    old_id TEXT,
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
CREATE TABLE reference_effect(
    substance_id TEXT NOT NULL REFERENCES substance(id),
    name TEXT NOT NULL,
    category TEXT,
    description TEXT,
    PRIMARY KEY(substance_id, name)
);
CREATE TABLE reference_interaction(
    id TEXT PRIMARY KEY,
    substance_a TEXT NOT NULL,
    substance_b TEXT NOT NULL,
    risk TEXT NOT NULL,
    description TEXT,
    sources TEXT NOT NULL
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
        "wikidata",
        "CC0-1.0",
        "https://www.wikidata.org",
        "Wikidata medicine items",
    ),
    (
        "drug-community",
        "unknown",
        "https://drug.community",
        "Drug.Community names and class labels",
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
                "old_id": raw.get("oldId"),
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
        cat.rows["effect"].extend(dw_effects)
    if dw.get("summary"):
        entry["summary"] = dw["summary"].strip() or entry.get("summary")
    cat.rows["provenance"].append((sid, "summary", "dosewiki"))
    cat.rows["provenance"].append((sid, "dosage", "dosewiki"))
    cat.rows["provenance"].append((sid, "duration", "dosewiki"))
    cat.rows["provenance"].append((sid, "effects", "dosewiki"))


def clean_dc_name(raw: str) -> str:
    return raw.split(" (")[0].strip() or raw.strip()


def parse_interaction_entry(raw: str) -> tuple[str, str | None]:
    """Split "Name (reason text)" into target name and reason, as the Kotlin ingest does."""
    raw = raw.strip()
    open_at = raw.find(" (")
    if open_at < 0:
        return raw, None
    name = raw[:open_at].strip()
    reason = raw[open_at + 2:].strip()
    if reason.endswith(")"):
        reason = reason[:-1].strip()
    if not name:
        return raw, None
    return name, (reason or None)


def build(sources_dir: Path) -> tuple[sqlite3.Connection, dict]:
    dosewiki = load(sources_dir / "dosewiki_slim.json")
    seed = load(sources_dir / "psychonautwiki_seed.json")

    interactions: dict[str, tuple] = {}
    for row in seed.get("interactions") or []:
        interactions[row["id"]] = (
            row["id"],
            row["substanceAId"],
            row["substanceBId"],
            row["riskLevel"],
            row.get("description"),
            json.dumps(row.get("sources") or [], sort_keys=True),
        )

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
        # Effect entities come from DoseWiki only; the seed carries effect names, not rows.
        if entry["cid"]:
            catalog.rows["identifier"].append(
                (entry["id"], "pubchem_cid", str(entry["cid"]), "psychonautwiki")
            )
        if entry["pwiki_id"]:
            catalog.rows["identifier"].append(
                (entry["id"], "psychonautwiki", entry["pwiki_id"], "psychonautwiki")
            )

    pairs: list[tuple] = []
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
        pairs.append((dw, entry["id"]))

    # TripSit fills compounds the community sources miss and gaps where DoseWiki carries no ladder.
    tripsit_created = 0
    tripsit_filled = 0
    for item in tripsit_source.load(TRIPSIT_PATH):
        matched = catalog.match([item["name"]] + item["aliases"])
        if matched:
            has_dose = any(row[0] == matched for row in catalog.rows["dose"])
            if has_dose or not item["dose"]:
                continue
            for order, (route, tier, low, high, unit) in enumerate(item["dose"]):
                catalog.rows["dose"].append((matched, route, order, tier, low, high, unit, "tripsit"))
                catalog.rows["route"].append((matched, route))
            catalog.rows["provenance"].append((matched, "dosage", "tripsit"))
            tripsit_filled += 1
            continue
        entry = {
            "id": f"tripsit:{item['slug']}", "old_id": None, "name": item["name"],
            "summary": item["summary"], "cid": None, "pwiki_id": None,
            "aliases": item["aliases"], "classes": item["classes"], "routes": [],
            "addiction": None, "half_life": None, "tmax": None,
            "toxicity": [], "cross": [], "effects": [],
            "source_version": "tripsit", "payload": {},
        }
        catalog.register(entry)
        for alias in item["aliases"]:
            catalog.rows["alias"].append((entry["id"], alias, "tripsit"))
        for label in item["classes"]:
            catalog.rows["class"].append((entry["id"], label, "tripsit"))
        if item["summary"]:
            catalog.rows["provenance"].append((entry["id"], "summary", "tripsit"))
        seen_routes = set()
        for order, (route, tier, low, high, unit) in enumerate(item["dose"]):
            catalog.rows["dose"].append((entry["id"], route, order, tier, low, high, unit, "tripsit"))
            if route not in seen_routes:
                seen_routes.add(route)
                catalog.rows["route"].append((entry["id"], route))
            catalog.rows["provenance"].append((entry["id"], "dosage", "tripsit"))
        catalog.register_aliases(entry["id"])
        tripsit_created += 1

    # The cached SMW dump holds 64 PsychonautWiki pages the bundled seed left out.
    smw_added = 0
    if SMW_PATH.exists():
        for entry in seed_entries(load(SMW_PATH)):
            if catalog.match([entry["name"]] + entry["aliases"]):
                continue
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
            if entry["cid"]:
                catalog.rows["identifier"].append((entry["id"], "pubchem_cid", str(entry["cid"]), "psychonautwiki"))
            if entry["pwiki_id"]:
                catalog.rows["identifier"].append((entry["id"], "psychonautwiki", entry["pwiki_id"], "psychonautwiki"))
            catalog.rows["provenance"].append((entry["id"], "name", "psychonautwiki"))
            catalog.register_aliases(entry["id"])
            smw_added += 1

    # drug.community navigation adds named research chemicals without dose ladders.
    dc_created = 0
    for item in drug_community_source.load(DC_PATH):
        if catalog.match([item["name"]] + item["aliases"]):
            continue
        entry = {
            "id": f"dc:{item['slug']}", "old_id": None, "name": item["name"],
            "summary": None, "cid": None, "pwiki_id": None,
            "aliases": item["aliases"], "classes": item["classes"], "routes": [],
            "addiction": None, "half_life": None, "tmax": None,
            "toxicity": [], "cross": [], "effects": [],
            "source_version": "drug-community", "payload": {},
        }
        catalog.register(entry)
        for alias in item["aliases"]:
            catalog.rows["alias"].append((entry["id"], alias, "drug-community"))
        for label in item["classes"]:
            catalog.rows["class"].append((entry["id"], label, "drug-community"))
        catalog.rows["provenance"].append((entry["id"], "name", "drug-community"))
        catalog.rows["provenance"].append((entry["id"], "classes", "drug-community"))
        catalog.register_aliases(entry["id"])
        dc_created += 1

    # drug.community dose bands fill substances that still carry no ladder.
    dc_filled = 0
    for drug_name, bands in drug_community_source.load_dose_bands(DC_BANDS_PATH).items():
        target = catalog.match([clean_dc_name(drug_name), drug_name])
        if not target or any(row[0] == target for row in catalog.rows["dose"]):
            continue
        for route, order, tier, low, high, unit in bands:
            catalog.rows["dose"].append((target, route, order, tier, low, high, unit, "drug-community"))
            catalog.rows["route"].append((target, route))
        catalog.rows["provenance"].append((target, "dosage", "drug-community"))
        dc_filled += 1

    # Wikidata medicines fill the prescription breadth the community sources lack, up to the target count.
    wikidata_added = 0
    for item in wikidata_source.load(WIKIDATA_PATH):
        if len(catalog.substances) >= TARGET_SUBSTANCES:
            break
        if catalog.match([item["name"]]):
            continue
        entry = {
            "id": f"wd:{item['qid']}", "old_id": None, "name": item["name"],
            "summary": None, "cid": item["cid"], "pwiki_id": None,
            "aliases": [], "classes": ["medicine"], "routes": [],
            "addiction": None, "half_life": None, "tmax": None,
            "toxicity": [], "cross": [], "effects": [],
            "source_version": "wikidata", "payload": {},
        }
        catalog.register(entry)
        catalog.rows["class"].append((entry["id"], "medicine", "wikidata"))
        for scheme, value in (("atc", item["atc"]), ("cas", item["cas"]), ("drugbank", item["drugbank"])):
            if value:
                catalog.rows["identifier"].append((entry["id"], scheme, value, "wikidata"))
        catalog.rows["provenance"].append((entry["id"], "name", "wikidata"))
        catalog.rows["provenance"].append((entry["id"], "classes", "wikidata"))
        catalog.register_aliases(entry["id"])
        wikidata_added += 1

    # DoseWiki interactions resolve after every row exists, so created dw: targets are found.
    for dw, source_id in pairs:
        lists = dw.get("interactions") or {}
        for key, risk in (("dangerous", "DANGEROUS"), ("unsafe", "UNSAFE"), ("caution", "UNCERTAIN")):
            for raw in lists.get(key) or []:
                if not (raw or "").strip():
                    continue
                target_name, reason = parse_interaction_entry(raw)
                target = catalog.match([target_name])
                if not target or target == source_id:
                    continue
                a, b = sorted((source_id, target))
                interaction_id = f"interaction:{a}:{b}"
                interactions[interaction_id] = (
                    interaction_id, a, b, risk, reason,
                    json.dumps(["dosewiki", "tripsit"], sort_keys=True),
                )

    connection = sqlite3.connect(":memory:")
    connection.executescript(SCHEMA)
    connection.executemany("INSERT INTO source VALUES(?,?,?,?)", SOURCES)

    for entry in sorted(catalog.substances.values(), key=lambda item: item["id"]):
        payload = entry.get("payload") or {}
        connection.execute(
            "INSERT INTO substance VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                entry["id"], entry.get("old_id"), entry["name"], entry.get("summary"), entry.get("cid"),
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

    effect_rows = {}
    for (substance_id, name, category, description, _source) in catalog.rows["effect"]:
        effect_rows[(substance_id, name)] = (substance_id, name, category, description)
    connection.executemany(
        "INSERT OR REPLACE INTO reference_effect VALUES(?,?,?,?)",
        sorted(effect_rows.values(), key=str),
    )
    connection.executemany(
        "INSERT OR REPLACE INTO reference_interaction VALUES(?,?,?,?,?,?)",
        sorted(interactions.values(), key=str),
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
        "reference_effects": connection.execute(
            "SELECT COUNT(*) FROM reference_effect"
        ).fetchone()[0],
        "reference_interactions": connection.execute(
            "SELECT COUNT(*) FROM reference_interaction"
        ).fetchone()[0],
        "tripsit_created": tripsit_created,
        "tripsit_filled": tripsit_filled,
        "drug_community_created": dc_created,
        "psychonautwiki_added": smw_added,
        "wikidata_added": wikidata_added,
        "drug_community_filled": dc_filled,
    }
    return connection, counts


def source_digest(sources_dir: Path) -> str:
    """Fingerprint of everything that shapes the output: sources, schema and this builder."""
    digest = hashlib.sha256()
    for name in ("dosewiki_slim.json", "psychonautwiki_seed.json"):
        digest.update((sources_dir / name).read_bytes())
    digest.update(SCHEMA.encode("utf-8"))
    digest.update(Path(__file__).read_bytes())
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
    # SQLDelight opens a bundled file with the schema version it generated (1), so the
    # file must not advertise a newer version or Android treats it as a downgrade.
    connection.execute("PRAGMA user_version = 1")
    connection.commit()

    path = write_database(connection, args.out, content_version, counts)
    connection.close()

    check = sqlite3.connect(path)
    version = check.execute("PRAGMA user_version").fetchone()[0]
    check.close()
    if version != 1:
        print(f"user_version is {version}, expected 1 for the SQLDelight schema", file=sys.stderr)
        return 3

    print(json.dumps({"database": str(path), "content_version": content_version, **counts},
                     indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

