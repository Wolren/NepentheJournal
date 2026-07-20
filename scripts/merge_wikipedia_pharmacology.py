#!/usr/bin/env python3
"""Merge Wikipedia pharmacology data into Nepenthe Journal seed.json.

Reads a JournalSnapshot JSON (seed.json), queries Wikipedia for each substance's
binding affinity tables, extracts pharmacology data, and merges into the
substance's wikipediaData field with deduplication against existing sources.

Usage:
    python scripts/merge_wikipedia_pharmacology.py \\
        --input scripts/seed.json \\
        --output scripts/seed.json \\
        [--verbose] [--dry-run] [--skip-existing] [--batch N]
"""

import argparse
import json
import os
import sys
import time
import re
import urllib.request
import urllib.parse
import urllib.error
from typing import Any

import httpx
import urllib.request
import urllib.error

# Ensure we can import from wikipedia-mcp tools
WIKIPEDIA_MCP_DIR = os.path.expanduser("~/wikipedia-mcp")
if os.path.isdir(WIKIPEDIA_MCP_DIR):
    sys.path.insert(0, WIKIPEDIA_MCP_DIR)

# Use the full extraction from wikipedia-mcp tools when available
try:
    from tools.pharmacology_table import extract_pharmacology as mcp_extract
    HAS_MCP_EXTRACT = True
except ImportError:
    mcp_extract = None  # type: ignore
    HAS_MCP_EXTRACT = False

WIKI_API = "https://en.wikipedia.org/w/api.php"
WD_API = "https://www.wikidata.org/w/api.php"
WD_SPARQL = "https://query.wikidata.org/sparql"
USER_AGENT = "WikipediaMCP/1.0 (Wolren; wolrenn@outlook.com)"
HTTP_TIMEOUT = 30.0
REQUEST_DELAY = 2.0  # seconds between API calls (429-safe)

_client = httpx.Client(
    headers={"User-Agent": USER_AGENT},
    timeout=HTTP_TIMEOUT,
    limits=httpx.Limits(max_keepalive_connections=5, max_connections=10),
)

# ---------------------------------------------------------------------------
# Wikipedia API helpers
# ---------------------------------------------------------------------------


def _urllib_api_call(params: dict[str, str]) -> dict | None:
    """Make a Wikipedia API call using urllib (more reliable on this system)."""
    try:
        url = WIKI_API + "?" + urllib.parse.urlencode(params)
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            if "error" in data:
                return None
            return data
    except urllib.error.HTTPError as e:
        if e.code == 429:
            # Rate limited — raise so caller can back off
            raise e
        return None
    except (urllib.error.URLError, json.JSONDecodeError, OSError, TimeoutError):
        return None


def wiki_api_call(params: dict[str, str], retries: int = 2) -> dict | None:
    """Make a Wikipedia API call with retry on transient failures.

    Uses urllib as primary transport (stable on this Windows/httpx
    environment), falls back to httpx on failure.
    Handles 429 rate limiting with exponential backoff.
    """
    params["format"] = "json"
    params["formatversion"] = "2"

    for attempt in range(retries + 1):
        # Primary: urllib (more reliable)
        try:
            result = _urllib_api_call(params)
            if result is not None:
                return result
        except urllib.error.HTTPError as e:
            if e.code == 429:
                retry_after = int(e.headers.get("Retry-After", "60"))
                wait = max(retry_after + 5, 30) * (attempt + 1)
                print(f"\n  Rate limited (429). Waiting {wait}s...", file=sys.stderr)
                time.sleep(wait)
                continue

        # Fallback: httpx
        if attempt < retries:
            try:
                resp = _client.get(WIKI_API, params=params)
                resp.raise_for_status()
                data = resp.json()
                if "error" not in data:
                    return data
            except httpx.HTTPStatusError as e:
                if e.response.status_code == 429:
                    retry_after = int(e.response.headers.get("Retry-After", "60"))
                    wait = max(retry_after + 5, 30) * (attempt + 1)
                    print(f"\n  Rate limited (429). Waiting {wait}s...", file=sys.stderr)
                    time.sleep(wait)
                    continue
            except Exception:
                pass

        if attempt < retries:
            time.sleep(2.0 * (attempt + 1))

    return None


def fetch_wikitext(title: str) -> str | None:
    """Fetch wikitext for a Wikipedia page by title."""
    data = wiki_api_call({
        "action": "parse",
        "page": title,
        "prop": "wikitext",
        "redirects": "1",
    })
    if data is None:
        return None
    return data.get("parse", {}).get("wikitext", "")


def resolve_title(name: str) -> str | None:
    """Check if a Wikipedia page exists for a given title, following redirects."""
    data = wiki_api_call({
        "action": "query",
        "titles": name,
        "redirects": "1",
    })
    if data is None:
        return None
    pages = data.get("query", {}).get("pages", [])
    if pages and pages[0].get("pageid"):
        return pages[0]["title"]
    return None


# ---------------------------------------------------------------------------
# Extraction (mirrors tools/pharmacology_table.py logic but standalone)
# ---------------------------------------------------------------------------


def clean_cell(text: str) -> str:
    """Strip wiki markup from a cell value."""
    text = re.sub(r'\[\[([^\]|]+)\|([^\]]+)\]\]', r'\2', text)
    text = re.sub(r'\[\[([^\]|]+)\]\]', r'\1', text)
    text = re.sub(r'<ref[^>]*/>', '', text)
    text = re.sub(r'<ref[^>]*>.*?</ref>', '', text)
    text = re.sub(r'<br\s*/?>', '\n', text)
    text = re.sub(r'</?(sub|sup|abbr|nowrap)\s*/?>', '', text, flags=re.I)
    text = re.sub(r'<(sub|sup|abbr|nowrap)[^>]*>', '', text, flags=re.I)
    text = re.sub(r'\{\{nowrap\|([^}]+)\}\}', r'\1', text, flags=re.I)
    text = re.sub(r'\{\{abbrlink\|([^|}]+)(?:\|[^}]*)?\}\}', r'\1', text, flags=re.I)
    text = re.sub(r'\{\{abbr\|([^|}]+)(?:\|[^}]*)?\}\}', r'\1', text, flags=re.I)
    text = re.sub(r'\{\{[a-zA-Z]+cite\|[^}]*\}\}', '', text)
    text = re.sub(r'\{\{nbsp\|\}\}', ' ', text)
    text = text.strip()
    text = re.sub(r'  +', ' ', text)
    return text.strip()


def clean_target(text: str) -> str:
    """Clean a target name to canonical form."""
    text = clean_cell(text)
    text = re.sub(r'\s*\((?:receptor|transporter|protein)\)\s*$', '', text)
    text = re.sub(r'\s+', ' ', text).strip()
    return text


def find_binding_tables(wikitext: str) -> list[dict[str, Any]]:
    """Find binding affinity tables in wikicode."""
    tables = []
    for match in re.finditer(r'(\{\|.*?\|\})', wikitext, re.DOTALL):
        raw = match.group(1)
        text = clean_cell(raw)
        indicators = ['Ki', 'Affinity', 'Target', 'Receptor', 'nM']
        if sum(1 for ind in indicators if ind in text) < 2:
            continue

        rows = _parse_table_rows(raw)
        if len(rows) < 2:
            continue

        caption = ""
        cap_m = re.search(r'\|\+\s*(.+?)(?:\n|\|-)', raw, re.DOTALL)
        if cap_m:
            caption = clean_cell(cap_m.group(1))

        fmt = _detect_format(rows)
        tables.append({"caption": caption, "rows": rows, "format": fmt})
    return tables


def _parse_table_rows(raw: str) -> list[list[str]]:
    """Parse wikitext table rows."""
    rows = []
    for section in re.split(r'\n\|-\n?', raw):
        section = section.strip()
        if not section or section.startswith('{|') or section.startswith('|}'):
            continue
        cells = _parse_cells(section)
        if cells:
            rows.append(cells)
    return rows


def _parse_cells(section: str) -> list[str]:
    """Parse individual cells from a row section."""
    cells = []
    current = ""
    for line in section.split('\n'):
        line = line.strip()
        if not line or line in ('|-', '|}', '{|') or line.startswith('|+'):
            continue
        if line.startswith('!!') or line.startswith('||'):
            if current:
                cells.append(current.strip())
            content = line[2:].strip()
            parts = re.split(r'(?<!\|)\|\|(?!\|)', content)
            if len(parts) <= 1:
                parts = re.split(r'!!', content)
            if len(parts) > 1:
                cells.extend(p.strip() for p in parts)
            else:
                current = content
        elif line.startswith('!') or line.startswith('|'):
            if current:
                cells.append(current.strip())
            current = line[1:].strip()
        else:
            current = (current + " " + line.strip()).strip()
    if current:
        cells.append(current.strip())
    return [c for c in cells if c]


def _detect_format(rows: list[list[str]]) -> str:
    """Detect table format from header."""
    header = ' '.join(rows[0]).lower()
    if 'target' in header and ('affinity' in header or 'ki' in header):
        return "target_affinity"
    if 'site' in header and 'value' in header:
        return "multi_column"
    if len(rows[0]) <= 2:
        return "target_affinity"
    return "multi_column"


def parse_target_affinity(rows: list[list[str]]) -> list[dict[str, Any]]:
    """Parse 2-column Target/Affinity table."""
    records = []
    for row in rows[1:]:
        if len(row) < 2:
            continue
        target = clean_target(row[0])
        if not target:
            continue
        affinity_clean = clean_cell(row[1])
        parsed = parse_values(affinity_clean)
        parsed["target"] = target
        records.append(parsed)
    return records


def parse_multi_column(rows: list[list[str]]) -> list[dict[str, Any]]:
    """Parse multi-column binding table."""
    headers = [clean_cell(h).lower() for h in rows[0]]
    col_map = {}
    for i, h in enumerate(headers):
        if h in ('site', 'target', 'receptor'):
            col_map['target'] = i
        elif 'value' in h or 'affinity' in h or h in ('ki', 'ic50'):
            col_map['value'] = i
        elif h in ('type',):
            col_map['type'] = i
        elif h in ('action', 'mode'):
            col_map['action'] = i
        elif h in ('species', 'organism'):
            col_map['species'] = i

    records = []
    for row in rows[1:]:
        rec = {"target": "", "value_raw": "", "value_nm": None,
               "affinity_type": "", "action": "", "species": ""}
        if 'target' in col_map and col_map['target'] < len(row):
            rec["target"] = clean_target(row[col_map['target']])

        if 'value' in col_map and col_map['value'] < len(row):
            raw_val = clean_cell(row[col_map['value']])
            rec["value_raw"] = raw_val

            unit = "nM"
            if 'uM' in raw_val or 'μM' in raw_val:
                unit = "uM"
            val_clean = re.sub(r'\s*(nM|uM|μM)', '', raw_val).strip()
            try:
                num_part = val_clean.replace(',', '').split()[0]
                nums = re.findall(r'[\d.]+', num_part)
                if nums:
                    numeric = float(nums[0])
                    if unit == "uM":
                        numeric *= 1000
                    rec["value_nm"] = numeric
            except (ValueError, IndexError):
                pass

        if 'type' in col_map and col_map['type'] < len(row):
            rec["affinity_type"] = clean_cell(row[col_map['type']])
        if 'action' in col_map and col_map['action'] < len(row):
            rec["action"] = clean_cell(row[col_map['action']])
        if 'species' in col_map and col_map['species'] < len(row):
            rec["species"] = clean_cell(row[col_map['species']])

        if rec["target"]:
            records.append(rec)
    return records


def parse_values(text: str) -> dict[str, Any]:
    """Parse Ki/EC50/IC50/Emax values from a cleaned affinity cell."""
    result: dict[str, Any] = {
        "ki_nm": None, "ki_nm_min": None, "ki_nm_max": None, "ki_nm_operator": None,
        "ec50_nm": None, "ic50_nm": None, "emax_pct": None, "species_note": None,
    }

    if text in ("ND", "No data", ""):
        return result

    lines = text.split('\n')
    ki_line = ec50_line = ic50_line = emax_line = None

    for line in lines:
        ll = line.strip()
        if re.search(r'(Ki)|(K\(i\))', ll, re.I) and 'Ki' not in ll.upper():
            # Check for actual Ki label
            pass

        if re.search(r'\(K_?\{?0,1\}?i\)', ll, re.I) or re.search(r'\bKi\b', ll):
            ki_line = ll
        elif re.search(r'EC_?50', ll, re.I):
            ec50_line = ll
        elif re.search(r'IC_?50', ll, re.I):
            ic50_line = ll
        elif '%' in ll and re.search(r'E_?\w*max', ll, re.I):
            emax_line = ll
        elif re.search(r'\(rat\)|\(mouse\)|\(human\)', ll, re.I):
            result["species_note"] = ll.strip()
            if not ki_line:
                ki_line = ll
        elif re.match(r'^[><]?\s*[\d.,]+(?:\s*[–-]\s*[\d.,]+)?(?:\s*\([^)]*\))?\s*(?:nM)?\s*$', ll):
            ki_line = ll

    def parse_single(line: str) -> dict:
        out = {"value": None, "min": None, "max": None, "operator": None}
        if not line:
            return out
        num_part = re.sub(r'\([^)]*\)', '', line)
        num_part = re.sub(r'[A-Za-z_<>{}/]+', '', num_part).strip()
        num_part = re.sub(r'\s+', '', num_part)

        if num_part.startswith('>'):
            out["operator"] = '>'
            num_part = num_part[1:]
        elif num_part.startswith('<'):
            out["operator"] = '<'
            num_part = num_part[1:]

        num_part = num_part.replace(',', '')
        rm = re.match(r'([\d.]+)\s*[-–]\s*([\d.]+)', num_part)
        if rm:
            out["min"] = rm.group(1)
            out["max"] = rm.group(2)
        else:
            sm = re.match(r'([\d.]+)', num_part)
            if sm:
                out["value"] = sm.group(1)
        return out

    if ki_line:
        p = parse_single(ki_line)
        result["ki_nm"] = p["value"]
        result["ki_nm_min"] = p["min"]
        result["ki_nm_max"] = p["max"]
        result["ki_nm_operator"] = p["operator"]
    if ec50_line:
        p = parse_single(ec50_line)
        result["ec50_nm"] = p["value"]
    if ic50_line:
        p = parse_single(ic50_line)
        result["ic50_nm"] = p["value"]
    if emax_line:
        m = re.search(r'([\d.,]+(?:[-–][\d.,]+)?)\s*%', emax_line)
        if m:
            result["emax_pct"] = m.group(1)

    return result


def _standalone_extract(wikitext: str) -> list[dict[str, Any]]:
    """Extract all pharmacology binding records from wikicode.

    Returns list of records with keys: target, ki_nm, ki_nm_min, ki_nm_max,
    ki_nm_operator, ec50_nm, ic50_nm, emax_pct, species_note.
    """
    all_records = []
    tables = find_binding_tables(wikitext)
    for t in tables:
        if t["format"] == "target_affinity":
            records = parse_target_affinity(t["rows"])
        else:
            records = parse_multi_column(t["rows"])
        all_records.extend(records)
    return all_records


# ---------------------------------------------------------------------------
# Conversion to Kotlin model format
# ---------------------------------------------------------------------------


def to_wikipedia_record(r: dict[str, Any]) -> dict[str, Any]:
    """Convert extraction record to WikipediaRecord JSON format (camelCase)."""
    def _to_float(val):
        if val is None:
            return None
        try:
            return float(val.replace(',', '')) if isinstance(val, str) else float(val)
        except (ValueError, AttributeError):
            return None

    rec = {
        "targetName": r.get("target"),
        "kiNM": _to_float(r.get("ki_nm")),
        "kiNMMin": _to_float(r.get("ki_nm_min")),
        "kiNMMax": _to_float(r.get("ki_nm_max")),
        "kiNMOperator": r.get("ki_nm_operator"),
        "ec50NM": _to_float(r.get("ec50_nm")),
        "ic50NM": _to_float(r.get("ic50_nm")),
        "emaxPercent": _to_float(r.get("emax_pct")),
        "species": r.get("species_note") or r.get("species"),
        "rawText": r.get("raw_text") or r.get("value_raw"),
    }
    # Strip None values for cleaner JSON (kotlinx handles null defaults)
    return {k: v for k, v in rec.items() if v is not None}


# ---------------------------------------------------------------------------
# Deduplication
# ---------------------------------------------------------------------------


def _target_key(name: str) -> str:
    """Normalize target name for comparison."""
    name = name.lower().strip()
    # Strip common suffixes
    name = re.sub(r'\s*\(?(receptor|transporter|protein|channel|enzyme)\)?\s*$', '', name)
    # Normalize whitespace
    name = re.sub(r'\s+', ' ', name)
    # Remove html sub/sup remnants
    name = name.replace('<sub>', '').replace('</sub>', '')
    name = name.replace('<sup>', '').replace('</sup>', '')
    return name.strip()


def _ki_overlaps(rec1: dict, rec2: dict) -> bool:
    """Check if two records have overlapping Ki values."""
    def _get_range(r):
        if r.get("kiNM") is not None:
            v = r["kiNM"]
            return (v, v)
        if r.get("kiNMMin") is not None and r.get("kiNMMax") is not None:
            return (r["kiNMMin"], r["kiNMMax"])
        return None
    r1 = _get_range(rec1)
    r2 = _get_range(rec2)
    if r1 is None or r2 is None:
        return False
    return r1[0] <= r2[1] and r2[0] <= r1[1]


def is_duplicate(wp_rec: dict, existing_records: list[dict]) -> bool:
    """Check if a Wikipedia record duplicates an existing pharmacology record.

    Compares against all existing source records (PDSP, BindingDB, ChEMBL, IUPHAR).
    Two records are duplicates if they target the same protein AND have overlapping
    Ki affinity ranges.
    """
    wp_target = _target_key(wp_rec.get("targetName", ""))
    if not wp_target:
        return False

    for ex_rec in existing_records:
        # Check target name match across different source formats
        ex_target = _target_key(
            ex_rec.get("targetName")
            or ex_rec.get("target")
            or ""
        )
        if not ex_target:
            continue

        if wp_target != ex_target:
            continue

        if _ki_overlaps(wp_rec, ex_rec):
            return True

    return False


def collect_existing_records(substance: dict) -> list[dict]:
    """Collect all existing pharmacology records from a substance dict."""
    records = []

    # PDSP records
    pdsp = substance.get("pdspData", {})
    for r in pdsp.get("records", []):
        records.append({
            "targetName": r.get("targetName", ""),
            "kiNM": r.get("kiNanoMolar"),
            "source": "pdsp",
        })

    # BindingDB records
    bdb = substance.get("bindingdbData", {})
    for r in bdb.get("records", []):
        records.append({
            "targetName": r.get("targetName", ""),
            "affinityNM": r.get("affinityNM"),
            "source": "bindingdb",
        })

    # ChEMBL records
    chembl = substance.get("chemblData", {})
    for r in chembl.get("bioactivities", []):
        records.append({
            "target": r.get("targetId", ""),
            "value": r.get("value"),
            "source": "chembl",
        })

    # IUPHAR records
    iuphar = substance.get("iupharData", {})
    for r in iuphar.get("interactions", []):
        records.append({
            "targetName": r.get("targetName", ""),
            "affinity": r.get("affinity"),
            "source": "iuphar",
        })

    # Already-existing Wikipedia records
    wp = substance.get("wikipediaData", {})
    for r in wp.get("records", []):
        records.append(r)

    return records


# ---------------------------------------------------------------------------
# Wikipedia title mapping
# ---------------------------------------------------------------------------

# Hard-coded overrides for substances whose Wikipedia title differs from
# the Nepenthe substance name.
TITLE_OVERRIDES: dict[str, str] = {
    "DXM": "Dextromethorphan",
    "LSA": "Lysergic acid amide",
    "GHB": "Gamma-Hydroxybutyric acid",
    "GBL": "Γ-Butyrolactone",
    "THC": "Tetrahydrocannabinol",
    "N-Bomb": "25I-NBOMe",
    "Kratom": "Mitragyna speciosa",
    "Cocaine": "Cocaine",
    "MDMA": "MDMA",
    "MDA": "MDA (drug)",
    "MDEA": "MDEA",
    "2C-B": "2C-B",
    "2C-I": "2C-I",
    "5-MeO-DMT": "5-MeO-DMT",
    "DMT": "N,N-Dimethyltryptamine",
    "Mescaline": "Mescaline",
    "PCP": "Phencyclidine",
    "DOB": "2,5-Dimethoxy-4-bromoamphetamine",
    "DOM": "2,5-Dimethoxy-4-methylamphetamine",
}


def wikipedia_title(name: str) -> str | None:
    """Determine the Wikipedia page title for a substance name."""
    name_stripped = name.strip()
    if name_stripped in TITLE_OVERRIDES:
        return TITLE_OVERRIDES[name_stripped]
    return name_stripped


def batch_resolve_titles(titles: list[str]) -> dict[str, str]:
    """Resolve multiple titles via Wikipedia's query+redirects API (batch of 50).

    Returns {input_title: resolved_title_or_None}.
    """
    result: dict[str, str] = {}
    for i in range(0, len(titles), 50):
        batch = titles[i:i + 50]
        try:
            url = WIKI_API + "?" + urllib.parse.urlencode({
                "action": "query",
                "titles": "|".join(batch),
                "redirects": "1",
                "format": "json",
                "formatversion": "2",
            })
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            pages = data.get("query", {}).get("pages", [])

            # Build redirect map
            redirect_map: dict[str, str] = {}
            for r in data.get("query", {}).get("redirects", []):
                redirect_map[r["from"]] = r["to"]

            # Map input → resolved title (or None if missing)
            for title in batch:
                # Follow redirect chain
                resolved = title
                seen = set()
                while resolved in redirect_map and resolved not in seen:
                    seen.add(resolved)
                    resolved = redirect_map[resolved]

                # Check if resolved page exists
                found = False
                for p in pages:
                    if p.get("title") == resolved and p.get("pageid"):
                        found = True
                        break

                result[title] = resolved if found else None
        except Exception:
            for t in batch:
                result[t] = None

        if i + 50 < len(titles):
            time.sleep(REQUEST_DELAY)

    return result


def batch_resolve_by_cid(cids: list[int], batch_size: int = 300) -> dict[int, str | None]:
    """Resolve PubChem CIDs to Wikipedia page titles via Wikidata SPARQL.

    Batched to avoid URL/query size limits. Returns {cid: wikipedia_title_or_None}.
    Rate-limit conscious — Wikidata SPARQL enforces ~10 req/min for user agents.
    """
    if not cids:
        return {}

    result: dict[int, str | None] = {}
    for i in range(0, len(cids), batch_size):
        batch = cids[i:i + batch_size]
        cid_values = " ".join(f'"{c}"' for c in batch)

        # Wikidata has strict rate limits — wait generously
        if i > 0:
            time.sleep(15.0)

        query = f"""
        SELECT ?cid ?page WHERE {{
          VALUES ?cid {{{cid_values}}}
          ?item wdt:P662 ?cid.
          ?sitelink schema:about ?item; schema:isPartOf <https://en.wikipedia.org/>.
          BIND(STRAFTER(STR(?sitelink), "https://en.wikipedia.org/wiki/") AS ?page)
        }}
        """
        for attempt in range(3):
            try:
                url = WD_SPARQL + "?" + urllib.parse.urlencode({"format": "json", "query": query})
                req = urllib.request.Request(url, headers={
                    "User-Agent": USER_AGENT + " (SPARQL resolver)",
                    "Accept": "application/sparql-results+json",
                })
                with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
                for item in data.get("results", {}).get("bindings", []):
                    cid = int(item["cid"]["value"])
                    title = item["page"]["value"]
                    title = urllib.parse.unquote(title.replace("_", " "))
                    result[cid] = title
                break  # Success — exit retry loop
            except urllib.error.HTTPError as e:
                if e.code == 429:
                    wait = int(e.headers.get("Retry-After", "30")) * (attempt + 1)
                    if args_verbose:
                        print(f"\n  Wikidata SPARQL rate-limited. Waiting {wait}s...", file=sys.stderr)
                    time.sleep(wait)
                else:
                    if args_verbose:
                        print(f"\n  Wikidata SPARQL HTTP {e.code}: {e.read().decode('utf-8')[:200]}", file=sys.stderr)
                    break
            except Exception as e:
                if args_verbose:
                    print(f"\n  Wikidata SPARQL error: {e}", file=sys.stderr)
                break
    return result


args_verbose = False


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Merge Wikipedia pharmacology data into Nepenthe seed.json"
    )
    parser.add_argument("--input", "-i", default="scripts/seed.json",
                        help="Input JournalSnapshot JSON path")
    parser.add_argument("--output", "-o", default="scripts/seed.json",
                        help="Output JournalSnapshot JSON path")
    parser.add_argument("--verbose", "-v", action="store_true",
                        help="Print progress")
    parser.add_argument("--dry-run", "-n", action="store_true",
                        help="Print what would be merged without writing")
    parser.add_argument("--skip-existing", action="store_true",
                        help="Skip substances that already have wikipediaData")
    parser.add_argument("--match", "-m", type=str, default="",
                        help="Only process substances whose name contains this substring (case-insensitive)")
    parser.add_argument("--batch", type=int, default=50,
                        help="Substances to process per checkpoint save (default: 50)")
    parser.add_argument("--delay", type=float, default=REQUEST_DELAY,
                        help=f"Delay between Wikipedia API calls (default: {REQUEST_DELAY})")
    parser.add_argument("--limit", type=int, default=0,
                        help="Max substances to process (0 = all)")
    args = parser.parse_args()

    # Load seed.json
    with open(args.input, encoding="utf-8") as f:
        snapshot = json.load(f)

    substances = snapshot.get("substances", [])
    if args.match:
        match_lower = args.match.lower()
        substances = [s for s in substances if match_lower in s.get("name", "").lower()]
        if args.verbose:
            print(f"Filtered to {len(substances)} substances matching '{args.match}'")

    if args.verbose:
        print(f"Loaded {len(substances)} substances from {args.input}")

    stats = {"fetched": 0, "merged": 0, "skipped": 0,
             "no_table": 0, "dup_skipped": 0, "page_not_found": 0}

    global args_verbose
    args_verbose = args.verbose

    # Step 1: build title map using CID-based (via Wikidata) + name-based fallback
    if args.verbose:
        print("[*] Resolving Wikipedia titles...")

    # 1a: CID-based resolution via Wikidata SPARQL
    cid_to_title: dict[int, str | None] = {}
    cids_to_resolve = []
    for sub in substances:
        cid = sub.get("cid")
        if cid is not None:
            cids_to_resolve.append(cid)

    if cids_to_resolve:
        if args.verbose:
            print(f"  Resolving {len(cids_to_resolve)} PubChem CIDs via Wikidata...")
        cid_to_title = batch_resolve_by_cid(cids_to_resolve)
        cid_found = sum(1 for v in cid_to_title.values() if v)
        if args.verbose:
            print(f"  {cid_found}/{len(cids_to_resolve)} CIDs resolved to Wikipedia pages")

    # 1b: For substances without resolved CID, try name-based
    title_map: dict[str, str | None] = {}
    unnamed_subs = 0
    for sub in substances:
        cid = sub.get("cid")
        if cid is not None and cid in cid_to_title and cid_to_title[cid]:
            # CID resolved — store under name key for lookup
            name = sub.get("name", "")
            if name:
                title_map[name] = cid_to_title[cid]
            continue

        name = sub.get("name", "")
        if not name:
            unnamed_subs += 1
            continue
        candidate = wikipedia_title(name)
        if candidate:
            # Resolve via Wikipedia query (batch)
            title_map[candidate] = None  # Will be batch-resolved below
        # If not found, title_map won't have it — treated as no page

    # Batch-resolve name-based candidates via Wikipedia API
    name_candidates = [k for k, v in title_map.items() if v is None]
    if name_candidates:
        if args.verbose:
            print(f"  Resolving {len(name_candidates)} name-based titles...")
        resolved_names = batch_resolve_titles(name_candidates)
        for k in name_candidates:
            title_map[k] = resolved_names.get(k)

    if args.verbose:
        cid_wp = sum(1 for sub in substances if sub.get("cid") is not None
                     and sub.get("cid") in cid_to_title and cid_to_title[sub["cid"]])
        name_wp = sum(1 for sub in substances if not (
            sub.get("cid") is not None and sub.get("cid") in cid_to_title and cid_to_title[sub["cid"]]
        ) and sub.get("name") and title_map.get(sub["name"]))
        print(f"  Total: {cid_wp} via CID, {name_wp} via name, {unnamed_subs} unnamed")

    processed = 0
    for i, sub in enumerate(substances):
        if args.limit and processed >= args.limit:
            break
        name = sub.get("name", "")
        if not name:
            continue

        # Skip if already have Wikipedia data and --skip-existing
        if args.skip_existing and sub.get("wikipediaData") is not None:
            stats["skipped"] += 1
            continue

        # Look up resolved title — prefer CID-based resolution
        cid = sub.get("cid")
        resolved_title = None
        if cid is not None and cid in cid_to_title and cid_to_title[cid]:
            resolved_title = cid_to_title[cid]
        else:
            candidate = wikipedia_title(name)
            resolved_title = title_map.get(candidate) if candidate else None

        if not resolved_title:
            stats["page_not_found"] += 1
            if args.verbose:
                print(f"  [{i+1}/{len(substances)}] {name}: no Wikipedia page")
            continue

        if args.verbose:
            print(f"  [{i+1}/{len(substances)}] {name} -> Wikipedia: {resolved_title}...", end="")

        # Fetch wikitext
        wikitext = fetch_wikitext(resolved_title)
        if wikitext is None:
            if args.verbose:
                print(" fetch failed")
            stats["page_not_found"] += 1
            continue

        if args.verbose:
            print("", end="")

        # Extract pharmacology data
        if HAS_MCP_EXTRACT:
            result = mcp_extract(wikitext, page_title=resolved_title)
            records = []
            for t in result.get("tables", []):
                records.extend(t.get("records", []))
        else:
            records = _standalone_extract(wikitext)
        if not records:
            if args.verbose:
                print(" no binding tables")
            stats["no_table"] += 1
            continue

        # Convert to WikipediaRecord format
        wp_records = [to_wikipedia_record(r) for r in records]
        wp_records = [r for r in wp_records if r.get("targetName")]

        if not wp_records:
            if args.verbose:
                print(" no parseable records")
            stats["no_table"] += 1
            continue

        # Deduplicate against existing pharmacology data
        existing = collect_existing_records(sub)
        new_records = []
        for rec in wp_records:
            if is_duplicate(rec, existing):
                stats["dup_skipped"] += 1
                continue
            # Skip records with no actual affinity data (ND/empty entries)
            if (rec.get("kiNM") is None and rec.get("kiNMMin") is None
                    and rec.get("ec50NM") is None and rec.get("ic50NM") is None):
                stats["dup_skipped"] += 1
                continue
            new_records.append(rec)

        if not new_records:
            if args.verbose:
                print(" all records duplicates")
            stats["dup_skipped"] += 0  # Already counted per-record
            stats["no_table"] += 1  # Flag as no net new data
            continue

        # Merge into substance
        if not args.dry_run:
            sub["wikipediaData"] = {"records": new_records}
            # Add "wikipedia" to sources
            sources = sub.get("sources", [])
            if "wikipedia" not in sources:
                sources.append("wikipedia")
                sub["sources"] = sources
            sub["updatedAt"] = int(time.time() * 1000)

        stats["fetched"] += 1
        stats["merged"] += len(new_records)
        processed += 1

        if args.verbose:
            print(f" {len(new_records)} records ({stats['dup_skipped']} dups skipped)")

        if args.batch and (i + 1) % args.batch == 0 and (i + 1) < len(substances):
            if not args.dry_run:
                # Write intermediate save
                with open(args.output, "w", encoding="utf-8") as f:
                    json.dump(snapshot, f, indent=2, ensure_ascii=False)
                if args.verbose:
                    print(f"\n[... checkpoint saved at {i+1}/{len(substances)} ...]\n")

        time.sleep(args.delay)

    # Final write
    if not args.dry_run:
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(snapshot, f, indent=2, ensure_ascii=False)
        print(f"\nWritten: {args.output}")
    else:
        print(f"\nDry-run: no changes written")

    print(f"\nSummary:")
    print(f"  Substances processed:  {processed}")
    print(f"  Pages fetched:        {stats['fetched']}")
    print(f"  Records merged:       {stats['merged']}")
    print(f"  Page not found:       {stats['page_not_found']}")
    print(f"  No binding tables:    {stats['no_table']}")
    print(f"  Duplicates skipped:   {stats['dup_skipped']}")
    print(f"  Skipped (existing):   {stats['skipped']}")


if __name__ == "__main__":
    main()
