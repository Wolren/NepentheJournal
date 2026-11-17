#!/usr/bin/env bash
# Launches the desktop app and waits for the first-frame mark, so a bundle that
# builds but cannot open a window fails CI.
set -euo pipefail
cd "$(dirname "$0")/.."

LOG="$(mktemp "${TMPDIR:-/tmp}/nepenthe-launch.XXXXXX")"
MARKER="first frame"
TIMEOUT_SECONDS="${LAUNCH_TIMEOUT_SECONDS:-300}"

./gradlew :composeApp:run --no-daemon --console=plain >"$LOG" 2>&1 &
WRAPPER_PID=$!

cleanup() {
  # Gradle spawns the app JVM as a child; the wrapper alone would leave it running.
  pkill -f 'app.journal.MainKt' 2>/dev/null || true
  kill "$WRAPPER_PID" 2>/dev/null || true
}
trap cleanup EXIT

DEADLINE=$((SECONDS + TIMEOUT_SECONDS))
while (( SECONDS < DEADLINE )); do
  if grep -q "$MARKER" "$LOG"; then
    echo "Desktop launch OK: '$MARKER' reached"
    exit 0
  fi
  if ! kill -0 "$WRAPPER_PID" 2>/dev/null; then
    echo "::error::Desktop app exited before '$MARKER'"
    tail -n 120 "$LOG"
    exit 1
  fi
  sleep 2
done

echo "::error::Desktop app did not reach '$MARKER' within ${TIMEOUT_SECONDS}s"
tail -n 120 "$LOG"
exit 1
