#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

ulimit -u 16384 2>/dev/null || true

# Whole-suite runner. Hand-inlined --tests filter lists are BANNED (audit C6):
# the old FAST/ALL arrays drifted from the real test tree, so six classes
# (InteractionDedupeTest, DoseWikiTripReportTest, DosewikiTaxonomyTest,
# DosewikiTaxonomyResourceTest, SubstanceTaxonomyTest, TargetNormalizerTest)
# never ran in CI, and the list still named ManualImportAdapterTest after
# wave-1 deleted that class, which fails the run with "No tests found for
# given includes". Running the bare desktopTest task cannot drift: a new test
# class runs from the moment it exists. This matches the CI test step in
# .github/workflows/ci.yml, which runs the same unfiltered task.
#
# NOTE: this script deliberately does NOT pkill java/gradle. Multiple agents
# may build this repo concurrently; killing foreign JVMs would wreck sibling
# runs ("Timeout waiting to lock" would follow).
#
# KMP desktopTest is not a plain Gradle Test task, so tasks.withType<Test>()
# hooks do not match it (see composeApp/build.gradle.kts); selection can only
# happen on the command line, which is exactly why CLI filters went away.

case "${1:-}" in
  --list)
    echo "Test source files discovered (no filters are used at run time):"
    find composeApp/src/commonTest composeApp/src/desktopTest \
         composeApp/src/androidUnitTest composeApp/src/iosTest \
         -name '*Test.kt' 2>/dev/null | sort
    ;;
  *)
    echo "Running the whole desktopTest suite (no --tests filters, audit C6)..."
    ./gradlew composeApp:desktopTest --no-daemon
    ;;
esac
