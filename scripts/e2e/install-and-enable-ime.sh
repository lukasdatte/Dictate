#!/usr/bin/env bash
# Install the Dictate debug APK on the running emulator, then enable it as the
# active input method (IME) and make it the default keyboard.
#
# Idempotent: reinstalls over an existing install (-r), and enabling/setting an
# already-active IME is a no-op. Discovers the real IME id from the device
# instead of trusting a hard-coded string, so a package/class rename can't
# silently point the scripts at a non-existent component.
#
# Usage:  scripts/e2e/install-and-enable-ime.sh [--build]
#   --build   run `./gradlew assembleDebug` first (default: use existing APK)
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

if [ "${1:-}" = "--build" ]; then
  log "Building debug APK ..."
  ( cd "$REPO_ROOT" && ./gradlew assembleDebug )
fi

[ -f "$APK_PATH" ] || { log "ERROR: APK not found at $APK_PATH (run with --build)"; exit 1; }

# Ensure the device is booted.
"$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q '^1$' \
  || { log "ERROR: $SERIAL is not booted. Run emulator-up.sh first."; exit 1; }

log "Installing $APK_PATH ..."
"$ADB" -s "$SERIAL" install -r -g "$APK_PATH"

# Discover the IME id from the device (-a = list all, incl. not-yet-enabled).
# Fall back to the constructed id from env.sh if discovery finds nothing.
DISCOVERED="$("$ADB" -s "$SERIAL" shell ime list -a -s 2>/dev/null | tr -d '\r' | grep "^$APP_ID/" | head -n1 || true)"
IME="${DISCOVERED:-$IME_ID}"
log "IME id: $IME"

log "Enabling and setting IME as default ..."
"$ADB" -s "$SERIAL" shell ime enable "$IME"
"$ADB" -s "$SERIAL" shell ime set "$IME"

# Verify.
CURRENT="$("$ADB" -s "$SERIAL" shell settings get secure default_input_method | tr -d '\r')"
log "default_input_method = $CURRENT"
if [ "$CURRENT" = "$IME" ]; then
  log "SUCCESS: Dictate is the active IME on $SERIAL."
else
  log "WARNING: active IME ($CURRENT) does not match expected ($IME)."
  exit 1
fi
