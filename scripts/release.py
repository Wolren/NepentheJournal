#!/usr/bin/env python3
"""
Release packaging for Nepenthe Journal — data artifacts.

Builds a release tarball containing:
  - schemas/journal-snapshot-v5.json    (JSON Schema)
  - scripts/seed.json                   (latest substance database)
  - docs/journal-snapshot-spec.md        (field dictionary)
  - scripts/pharmacology_*.csv           (pharmacology matrix, 5 files)
  - scripts/dosewiki_slim.json           (CC0 public domain dose data)

Usage:
    python scripts/release.py [--version v1.0.0] [--output-dir ./dist]

Output:
    dist/nepenthe-data-v1.0.0.tar.gz     (the release artifact)
    dist/                                 (verzeichnis with individual copies)

The script is designed to be run from a GitHub Action or locally.
It does NOT push tags or create GitHub releases — those are manual/CI steps.
"""

import argparse
import os
import shutil
import subprocess
import sys
import tarfile
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, ".."))

RELEASE_ASSETS = [
    # (relative_path, label)
    ("schemas/journal-snapshot-v5.json", "JSON Schema"),
    ("scripts/seed.json", "Substance database (JournalSnapshot v3)"),
    ("docs/journal-snapshot-spec.md", "Field dictionary"),
    ("scripts/pharmacology_all.csv", "Pharmacology matrix (all sources)"),
    ("scripts/pharmacology_pdsp.csv", "PDSP Ki binding data"),
    ("scripts/pharmacology_iuphar.csv", "IUPHAR ligand-target interactions"),
    ("scripts/pharmacology_chembl.csv", "ChEMBL bioactivity measurements"),
    ("scripts/pharmacology_bindingdb.csv", "BindingDB affinity records"),
    ("composeApp/src/jvmMain/resources/dosewiki_slim.json", "DoseWiki slim (CC0)"),
    ("composeApp/src/desktopMain/resources/dosewiki_slim.json", "DoseWiki slim copy (CC0)"),
]


def check_git_clean():
    """Warn if there are uncommitted changes."""
    result = subprocess.run(
        ["git", "status", "--porcelain"],
        capture_output=True, text=True, cwd=ROOT
    )
    dirty = [line for line in result.stdout.splitlines() if line.strip()]
    if dirty:
        print("Warning: uncommitted changes:")
        for d in dirty:
            print(f"  {d}")
        print()


def get_git_commit() -> str:
    result = subprocess.run(
        ["git", "rev-parse", "--short", "HEAD"],
        capture_output=True, text=True, cwd=ROOT
    )
    return result.stdout.strip()


def ensure_assets_exist() -> list[str]:
    """Check all assets exist, return missing paths."""
    missing = []
    for rel_path, _label in RELEASE_ASSETS:
        full = os.path.join(ROOT, rel_path)
        if not os.path.exists(full):
            missing.append(rel_path)
    return missing


def build_tarball(version: str, output_dir: str) -> str:
    """Create a gzipped tarball of all release assets."""
    os.makedirs(output_dir, exist_ok=True)
    tarball_name = f"nepenthe-data-{version}.tar.gz"
    tarball_path = os.path.join(output_dir, tarball_name)

    with tarfile.open(tarball_path, "w:gz") as tar:
        for rel_path, _label in RELEASE_ASSETS:
            full = os.path.join(ROOT, rel_path)
            # Store in tar with prefix: nepenthe-data-{version}/{rel_path}
            arcname = f"nepenthe-data-{version}/{rel_path}"
            tar.add(full, arcname=arcname)

    return tarball_path


def copy_to_dist(version: str, output_dir: str):
    """Copy individual assets into a versioned subdirectory."""
    dist_dir = os.path.join(output_dir, f"nepenthe-data-{version}")
    os.makedirs(dist_dir, exist_ok=True)

    for rel_path, _label in RELEASE_ASSETS:
        full = os.path.join(ROOT, rel_path)
        dest = os.path.join(dist_dir, os.path.basename(rel_path))
        # Avoid overwriting when two source paths have the same basename
        if os.path.exists(dest) and os.path.abspath(full) != os.path.abspath(dest):
            # Disambiguate by using the parent dir name
            parent = os.path.basename(os.path.dirname(rel_path))
            dest = os.path.join(dist_dir, f"{parent}_{os.path.basename(rel_path)}")
        shutil.copy2(full, dest)

    return dist_dir


def main():
    parser = argparse.ArgumentParser(description="Build Nepenthe data release artifacts")
    parser.add_argument(
        "--version",
        default=None,
        help="Release version (e.g. v1.0.0). If omitted, generated from date+commit."
    )
    parser.add_argument(
        "--output-dir",
        default=os.path.join(ROOT, "dist"),
        help="Output directory for release artifacts"
    )
    parser.add_argument(
        "--skip-export",
        action="store_true",
        help="Skip regenerating pharmacology CSVs"
    )
    args = parser.parse_args()

    os.chdir(ROOT)
    check_git_clean()
    commit = get_git_commit()

    if args.version:
        version = args.version
    else:
        today = datetime.now(timezone.utc).strftime("%Y%m%d")
        version = f"v{today}-{commit}"

    print(f"Building release {version}")
    print(f"Commit: {commit}")
    print()

    # Regenerate pharmacology CSVs unless skipped
    if not args.skip_export:
        print("Regenerating pharmacology CSVs...")
        subprocess.run(
            [sys.executable, "scripts/pharmacology_export.py", "--output", "scripts/"],
            cwd=ROOT, check=True
        )
        print()

    # Check assets
    missing = ensure_assets_exist()
    if missing:
        print("ERROR: missing assets:")
        for m in missing:
            print(f"  {m}")
        sys.exit(1)

    # Build tarball
    tarball = build_tarball(version, args.output_dir)
    print(f"Tarball: {tarball}")
    tarball_size = os.path.getsize(tarball)
    print(f"  Size: {tarball_size:,} bytes ({tarball_size / 1024 / 1024:.1f} MB)")

    # Copy individual files
    dist_dir = copy_to_dist(version, args.output_dir)
    print(f"Dist dir: {dist_dir}")

    # Print asset manifest for CI
    print()
    print("=== Asset Manifest ===")
    for rel_path, label in RELEASE_ASSETS:
        full = os.path.join(ROOT, rel_path)
        size = os.path.getsize(full)
        print(f"  {rel_path}  ({size:,} bytes)  — {label}")
    print()
    print(f"  tarball: {tarball}")
    print()
    print("To create a GitHub release:")
    print(f"  gh release create {version} {tarball} \\")
    print(f"      {os.path.join(dist_dir, '*')} \\")
    print(f"      --title 'Data release {version}' \\")
    print("      --notes 'See docs/journal-snapshot-spec.md for format details.'")


if __name__ == "__main__":
    main()
