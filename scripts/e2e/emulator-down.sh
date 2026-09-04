#!/usr/bin/env bash
# Shut the Dictate E2E emulator down cleanly.
#
# Idempotent: exits 0 whether or not an emulator was running. Uses the adb
# console `emu kill` path (no process-name pattern matching — matching on
# "qemu-system-x86_64" would also match this very script's command line and
# kill the wrong thing).
#
# Usage:  scripts/e2e/emulator-down.sh
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

if "$ADB" devices | grep -q "^${SERIAL}[[:space:]]"; then
  log "Stopping emulator $SERIAL ..."
  "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
  # Give it a moment to release the port.
  for _ in $(seq 1 15); do
    "$ADB" devices | grep -q "^${SERIAL}[[:space:]]" || break
    sleep 1
  done
  log "Emulator stopped."
else
  log "No emulator on $SERIAL — nothing to stop."
fi
