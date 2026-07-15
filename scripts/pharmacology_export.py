#!/usr/bin/env python3
"""
Pharmacology matrix export for Nepenthe Journal.

Extracts all pharmacology binding data from the seed.json JournalSnapshot
into four standalone CSV files — one per source (PDSP, IUPHAR, ChEMBL,
BindingDB) — plus a merged all-sources CSV.

Each row is one measurement: a substance-target affinity pair with provenance.

Usage:
    python scripts/pharmacology_export.py [--input seed.json] [--output-dir .]

Output:
    pharmacology_pdsp.csv       (5,005 records)
    pharmacology_iuphar.csv     (406 records)
    pharmacology_chembl.csv     (3,127 records)
    pharmacology_bindingdb.csv  (1,893 records)
    pharmacology_all.csv        (10,431 records, merged)
"""

import argparse
import csv
import json
import os
import sys

# ---------------------------------------------------------------------------
# Column names (snake_case, consistent across sources)
# ---------------------------------------------------------------------------

BASE_COLUMNS = [
    # Substance identity
    "substance_id",      # cid:5761 or pwiki:salvia_divinorum
    "cid",               # PubChem CID (int) or empty
    "substance_name",    # Display name
    "substance_class",   # Psychoactive class (first one found or empty)
    # Source provenance
    "source",            # pdsp | iuphar | chembl | bindingdb
]

PDSP_COLUMNS = BASE_COLUMNS + [
    "target_name",
    "gene",
    "species",
    "ki_nm",
    "reference",
    "reference_source",
]

IUPHAR_COLUMNS = BASE_COLUMNS + [
    "target_id",
    "target_name",
    "target_species",
    "interaction_type",   # agonist, antagonist, etc.
    "action",
    "affinity_display",   # Formatted string e.g. "pKi 8.7"
    "affinity_parameter", # pKi, pIC50, pEC50
    "endogenous",
    "primary_target",
]

CHEMBL_COLUMNS = BASE_COLUMNS + [
    "activity_type",       # IC50, Ki, EC50, Kd
    "value_nm",           # Numeric value in nM (converted)
    "units",
    "relation",
    "target_id",          # ChEMBL target ID
    "assay_description",
]

BINDINGDB_COLUMNS = BASE_COLUMNS + [
    "target_name",
    "uniprot_id",
    "gene_symbol",
    "species",
    "affinity_type",       # Ki, IC50, Kd, EC50
    "affinity_nm",
    "pmid",
]

ALL_COLUMNS = [
    "substance_id",
    "cid",
    "substance_name",
    "substance_class",
    "source",
    "target_name",
    "gene",
    "species",
    "affinity_type",
    "affinity_nm_raw",
    "ki_nm",
    "reference",
    "pmid",
    "assay_description",
    "endogenous",
    "primary_target",
]

# ---------------------------------------------------------------------------
# Extraction helpers
# ---------------------------------------------------------------------------


def first_class(substance: dict) -> str:
    classes = substance.get("substanceClass", [])
    return str(classes[0]) if classes else ""


def extract_pdsp(substance: dict) -> list[dict]:
    """Extract PDSP Ki records."""
    rows = []
    pdsp = substance.get("pdspData")
    if not pdsp:
        return rows
    for rec in pdsp.get("records", []):
        rows.append(
            {
                "substance_id": substance["id"],
                "cid": substance.get("cid", ""),
                "substance_name": substance["name"],
                "substance_class": first_class(substance),
                "source": "pdsp",
                "target_name": rec.get("targetName", ""),
                "gene": rec.get("gene", ""),
                "species": rec.get("species", ""),
                "ki_nm": rec.get("kiNanoMolar"),
                "reference": rec.get("reference", ""),
                "reference_source": rec.get("source", ""),
            }
        )
    return rows


def extract_iuphar(substance: dict) -> list[dict]:
    """Extract IUPHAR ligand-target interactions."""
    rows = []
    iuphar = substance.get("iupharData")
    if not iuphar:
        return rows
    for rec in iuphar.get("interactions", []):
        rows.append(
            {
                "substance_id": substance["id"],
                "cid": substance.get("cid", ""),
                "substance_name": substance["name"],
                "substance_class": first_class(substance),
                "source": "iuphar",
                "target_id": rec.get("targetId", ""),
                "target_name": rec.get("targetName", ""),
                "target_species": rec.get("targetSpecies", ""),
                "interaction_type": rec.get("type", ""),
                "action": rec.get("action", ""),
                "affinity_display": rec.get("affinity", ""),
                "affinity_parameter": rec.get("affinityParameter", ""),
                "endogenous": rec.get("endogenous", ""),
                "primary_target": rec.get("primaryTarget", ""),
            }
        )
    return rows


def extract_chembl(substance: dict) -> list[dict]:
    """Extract ChEMBL bioactivity measurements."""
    rows = []
    chembl = substance.get("chemblData")
    if not chembl:
        return rows
    for rec in chembl.get("bioactivities", []):
        rows.append(
            {
                "substance_id": substance["id"],
                "cid": substance.get("cid", ""),
                "substance_name": substance["name"],
                "substance_class": first_class(substance),
                "source": "chembl",
                "activity_type": rec.get("type", ""),
                "value_nm": rec.get("value"),
                "units": rec.get("units", ""),
                "relation": rec.get("relation", ""),
                "target_id": rec.get("targetId", ""),
                "assay_description": rec.get("assay", ""),
            }
        )
    return rows


def extract_bindingdb(substance: dict) -> list[dict]:
    """Extract BindingDB binding affinity records."""
    rows = []
    bdb = substance.get("bindingdbData")
    if not bdb:
        return rows
    for rec in bdb.get("records", []):
        rows.append(
            {
                "substance_id": substance["id"],
                "cid": substance.get("cid", ""),
                "substance_name": substance["name"],
                "substance_class": first_class(substance),
                "source": "bindingdb",
                "target_name": rec.get("targetName", ""),
                "uniprot_id": rec.get("uniprotId", ""),
                "gene_symbol": rec.get("geneSymbol", ""),
                "species": rec.get("species", ""),
                "affinity_type": rec.get("affinityType", ""),
                "affinity_nm": rec.get("affinityNM"),
                "pmid": rec.get("pmid", ""),
            }
        )
    return rows


# ---------------------------------------------------------------------------
# Writers
# ---------------------------------------------------------------------------


def write_csv(path: str, columns: list[str], rows: list[dict]):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=columns, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)
    print(f"  {path}  ({len(rows)} rows)")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main():
    parser = argparse.ArgumentParser(description="Export pharmacology data matrix from JournalSnapshot")
    parser.add_argument("--input", default="scripts/seed.json", help="Path to JournalSnapshot JSON")
    parser.add_argument("--output-dir", 
        default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "."),
        help="Output directory for CSV files")
    args = parser.parse_args()

    with open(args.input) as f:
        data = json.load(f)

    substances = data["substances"]
    print(f"Loaded {len(substances)} substances from {args.input}")
    print()

    # Extract all rows
    pdsp_rows = []
    iuphar_rows = []
    chembl_rows = []
    bdb_rows = []

    for sub in substances:
        pdsp_rows.extend(extract_pdsp(sub))
        iuphar_rows.extend(extract_iuphar(sub))
        chembl_rows.extend(extract_chembl(sub))
        bdb_rows.extend(extract_bindingdb(sub))

    print("Writing pharmacology CSV files:")
    print("---")

    out = args.output_dir
    write_csv(os.path.join(out, "pharmacology_pdsp.csv"), PDSP_COLUMNS, pdsp_rows)
    write_csv(os.path.join(out, "pharmacology_iuphar.csv"), IUPHAR_COLUMNS, iuphar_rows)
    write_csv(os.path.join(out, "pharmacology_chembl.csv"), CHEMBL_COLUMNS, chembl_rows)
    write_csv(os.path.join(out, "pharmacology_bindingdb.csv"), BINDINGDB_COLUMNS, bdb_rows)

    # Merged all-sources CSV (normalised schema)
    all_rows = []
    for sub in substances:
        for rec in extract_pdsp(sub):
            all_rows.append(
                {
                    "substance_id": rec["substance_id"],
                    "cid": rec["cid"],
                    "substance_name": rec["substance_name"],
                    "substance_class": rec["substance_class"],
                    "source": "pdsp",
                    "target_name": rec["target_name"],
                    "gene": rec["gene"],
                    "species": rec["species"],
                    "affinity_type": "Ki",
                    "affinity_nm_raw": rec["ki_nm"],
                    "ki_nm": rec["ki_nm"],
                    "reference": rec["reference"],
                    "pmid": "",
                    "assay_description": "",
                    "endogenous": "",
                    "primary_target": "",
                }
            )
        for rec in extract_iuphar(sub):
            all_rows.append(
                {
                    "substance_id": rec["substance_id"],
                    "cid": rec["cid"],
                    "substance_name": rec["substance_name"],
                    "substance_class": rec["substance_class"],
                    "source": "iuphar",
                    "target_name": rec["target_name"],
                    "gene": "",
                    "species": rec["target_species"],
                    "affinity_type": rec["affinity_parameter"],
                    "affinity_nm_raw": "",
                    "ki_nm": "",
                    "reference": rec["affinity_display"],
                    "pmid": "",
                    "assay_description": "",
                    "endogenous": str(rec["endogenous"]) if rec["endogenous"] != "" else "",
                    "primary_target": str(rec["primary_target"]) if rec["primary_target"] != "" else "",
                }
            )
        for rec in extract_chembl(sub):
            all_rows.append(
                {
                    "substance_id": rec["substance_id"],
                    "cid": rec["cid"],
                    "substance_name": rec["substance_name"],
                    "substance_class": rec["substance_class"],
                    "source": "chembl",
                    "target_name": rec["target_id"],
                    "gene": "",
                    "species": "",
                    "affinity_type": rec["activity_type"],
                    "affinity_nm_raw": rec["value_nm"],
                    "ki_nm": rec["value_nm"] if rec["activity_type"] in ("Ki", "IC50") else "",
                    "reference": "",
                    "pmid": "",
                    "assay_description": rec["assay_description"],
                    "endogenous": "",
                    "primary_target": "",
                }
            )
        for rec in extract_bindingdb(sub):
            all_rows.append(
                {
                    "substance_id": rec["substance_id"],
                    "cid": rec["cid"],
                    "substance_name": rec["substance_name"],
                    "substance_class": rec["substance_class"],
                    "source": "bindingdb",
                    "target_name": rec["target_name"],
                    "gene": rec["gene_symbol"],
                    "species": rec["species"],
                    "affinity_type": rec["affinity_type"],
                    "affinity_nm_raw": rec["affinity_nm"],
                    "ki_nm": rec["affinity_nm"] if rec["affinity_type"] in ("Ki", "IC50") else "",
                    "reference": "",
                    "pmid": rec["pmid"],
                    "assay_description": "",
                    "endogenous": "",
                    "primary_target": "",
                }
            )

    print("---")
    write_csv(os.path.join(out, "pharmacology_all.csv"), ALL_COLUMNS, all_rows)
    print()

    # Summary
    print(f"Summary:")
    print(f"  PDSP:      {len(pdsp_rows):>6,} Ki binding records ({len(set(r['substance_id'] for r in pdsp_rows))} substances)")
    print(f"  IUPHAR:    {len(iuphar_rows):>6,} ligand-target interactions ({len(set(r['substance_id'] for r in iuphar_rows))} substances)")
    print(f"  ChEMBL:    {len(chembl_rows):>6,} bioactivity measurements ({len(set(r['substance_id'] for r in chembl_rows))} substances)")
    print(f"  BindingDB: {len(bdb_rows):>6,} affinity records ({len(set(r['substance_id'] for r in bdb_rows))} substances)")
    print(f"  Combined:  {len(all_rows):>6,} total pharmacology records")
    print()
    print(f"All CSVs written to: {os.path.abspath(args.output_dir)}")


if __name__ == "__main__":
    main()
