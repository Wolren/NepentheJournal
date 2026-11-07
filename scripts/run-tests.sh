set -euo pipefail
cd "$(dirname "$0")/.."

ulimit -u 16384 2>/dev/null || true

# Hand-inlined --tests filter lists are banned (audit C6): the old lists drifted from the real test tree.
# NOTE: this script deliberately does NOT pkill java/gradle. Multiple agents may build this repo concurrently; killing foreign JVMs would wreck sibling runs.

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
