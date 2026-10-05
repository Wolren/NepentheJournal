#!/usr/bin/env python3
"""Audit the seed library for cross-substance joins.

Run:  python scripts/audit_seed.py [path/to/seed.json]   (default scripts/seed.json)

Checks (exit code 1 when any finding):
  1. alias join  - an alias that equals a DIFFERENT substance's canonical
     name, i.e. data from one row leaked into another. Intentional shared
     words are allowlisted below.
  2. class/formula join - substanceClass claims a nitrogen skeleton while
     the PubChem molecular formula contains no N, i.e. the name->CID
     resolution picked the wrong compound.

These are the two shapes of wrong join found in 2026-10 (1,3-Butanediol
carrying DMT's fields from PsychonautWiki's SMW properties; LSA and PCE
resolving to unrelated PubChem compounds).
"""
import json
import sys

# Rows where an alias legitimately equals another substance's name.
ALIAS_ALLOWLIST = {
    ("Cocaine", "Coca"),
    ("Banisteriopsis caapi", "Ayahuasca"),
    ("Salvinorin A", "Salvia divinorum"),
}

# substanceClass keywords that imply at least one nitrogen atom.
NITROGEN_CLASSES = (
    "tryptamine",
    "amphetamine",
    "phenethylamine",
    "arylcyclohexylamine",
    "lysergamide",
    "benzodiazepine",
)


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else "scripts/seed.json"
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    subs = data["substances"]
    names = {s["name"].strip().lower(): s["name"] for s in subs}
    findings = []

    for s in subs:
        for alias in s.get("aliases", []):
            other = names.get(alias.strip().lower())
            if other and other.lower() != s["name"].strip().lower():
                if (s["name"], other) not in ALIAS_ALLOWLIST:
                    findings.append(
                        f"alias join      : {s['name']!r} has alias {alias!r}"
                        f" == canonical name of {other!r}"
                    )
        formula = (
            (s.get("chemicalProperties") or {}).get("molecularFormula") or ""
        )
        cls = " ".join(s.get("substanceClass", [])).lower()
        if not formula:
            continue
        for key in NITROGEN_CLASSES:
            if key in cls and "n" not in formula.lower():
                findings.append(
                    f"class/formula   : {s['name']!r} classed {cls!r}"
                    f" but formula {formula} contains no N"
                )
                break

    if findings:
        print(f"FAIL {path}: {len(findings)} finding(s) in {len(subs)} substances")
        for f in findings:
            print("  " + f)
        return 1
    print(f"OK {path}: no cross-join findings in {len(subs)} substances")
    return 0


if __name__ == "__main__":
    sys.exit(main())
