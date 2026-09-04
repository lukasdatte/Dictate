#!/usr/bin/env bash
# Bring up the Dictate E2E emulator, installing missing SDK packages on demand.
#
# Idempotent: safe to run repeatedly. If the emulator is already booted it
# returns immediately. The emulator is launched DECOUPLED (setsid + nohup +
# stdin from /dev/null) so it survives the death of the calling shell/session
# — a hard-killed Claude Code session must NOT take the emulator with it.
#
# Usage:  scripts/e2e/emulator-up.sh
# Env overrides: see env.sh (AVD_NAME, EMU_PORT, SYS_IMAGE, ...).
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

# --- 0. Already up? ---------------------------------------------------------
if "$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q '^1$'; then
  log "Emulator $SERIAL already booted."
  exit 0
fi

# --- 1. Install-on-demand: emulator, system image, platform -----------------
need_pkg() {
  # Returns 0 (true) if the package path is absent on disk.
  local path="$1"
  [ ! -e "$ANDROID_HOME/$path" ]
}
MISSING=()
need_pkg "emulator/emulator"                                   && MISSING+=("emulator")
need_pkg "system-images/android-35/google_apis/x86_64/system.img" && MISSING+=("$SYS_IMAGE")
need_pkg "platforms/android-35/android.jar"                    && MISSING+=("$PLATFORM_PKG")
if [ "${#MISSING[@]}" -gt 0 ]; then
  log "Installing missing SDK packages: ${MISSING[*]}"
  yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
  "$SDKMANAGER" --install "${MISSING[@]}"
fi

# --- 2. Create the AVD if it does not exist ---------------------------------
if ! "$AVDMANAGER" list avd 2>/dev/null | grep -q "Name: $AVD_NAME\b"; then
  log "Creating AVD $AVD_NAME ($DEVICE_PROFILE, $SYS_IMAGE)"
  echo "no" | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "$SYS_IMAGE" -d "$DEVICE_PROFILE" --force
fi

# --- 3. Launch + wait, with an automatic wipe-data retry --------------------
# swiftshader_indirect: software GL, required on a headless host with no GPU.
# -no-snapshot: always a clean cold boot (deterministic for E2E).
# setsid detaches into a new session; </dev/null + nohup + redirect make it
# fully independent of the parent — it keeps running after we exit.
#
# GOTCHA: a previous emulator that was hard-killed (e.g. its parent session
# died) leaves the guest userdata ext4 image dirty. A plain -no-snapshot boot
# then hangs forever at "performing a full startup" and the device stays
# `offline`. Booting once with -wipe-data recreates userdata clean and fixes
# it. So: try a normal boot, and on timeout retry once with -wipe-data.
launch_emulator() {
  local extra="$1"   # e.g. "-wipe-data" or ""
  "$ADB" start-server >/dev/null 2>&1 || true
  # shellcheck disable=SC2086
  setsid nohup "$EMULATOR" -avd "$AVD_NAME" \
    -no-window -gpu swiftshader_indirect -no-audio -no-snapshot -no-boot-anim \
    -accel on -port "$EMU_PORT" -no-metrics $extra \
    </dev/null >"$EMU_LOG" 2>&1 &
  log "Emulator launched${extra:+ ($extra)} (log: $EMU_LOG)"
}

wait_for_boot() {
  timeout 120 "$ADB" -s "$SERIAL" wait-for-device || return 1
  local start; start=$(date +%s)
  while :; do
    local bc; bc="$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    [ "$bc" = "1" ] && { echo "$(( $(date +%s) - start ))"; return 0; }
    [ "$(( $(date +%s) - start ))" -ge "$BOOT_TIMEOUT" ] && return 1
    sleep 2
  done
}

log "Launching emulator $AVD_NAME on port $EMU_PORT (headless, decoupled) ..."
launch_emulator ""
if ! ELAPSED="$(wait_for_boot)"; then
  log "Boot did not complete in ${BOOT_TIMEOUT}s — likely dirty userdata. Retrying with -wipe-data ..."
  "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
  sleep 5
  launch_emulator "-wipe-data"
  if ! ELAPSED="$(wait_for_boot)"; then
    log "ERROR: boot failed even after -wipe-data. See $EMU_LOG."
    exit 1
  fi
fi
# Dismiss the initial lock screen so UI tests can reach an activity.
"$ADB" -s "$SERIAL" shell input keyevent 82 >/dev/null 2>&1 || true
log "Boot completed in ${ELAPSED}s. Device ready: $SERIAL"
