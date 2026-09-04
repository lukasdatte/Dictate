# Shared environment for the Dictate local E2E emulator scripts.
# Source this from the other scripts: `source "$(dirname "$0")/env.sh"`.
#
# Everything is overridable from the environment so CI or a second AVD can
# reuse the same scripts without edits.

# --- SDK location -----------------------------------------------------------
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
# avdmanager/emulator resolve AVDs from here; pin it so the scripts are
# independent of the caller's shell profile.
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"

# --- Tool paths -------------------------------------------------------------
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"

# --- AVD / device -----------------------------------------------------------
AVD_NAME="${AVD_NAME:-dictate-e2e}"
EMU_PORT="${EMU_PORT:-5554}"
SERIAL="emulator-${EMU_PORT}"
# API 35 (google_apis, x86_64) is the default target; matches the app's
# targetSdk 35. Override SYS_IMAGE to fall back to API 34 if 35 is unstable.
SYS_IMAGE="${SYS_IMAGE:-system-images;android-35;google_apis;x86_64}"
PLATFORM_PKG="${PLATFORM_PKG:-platforms;android-35}"
DEVICE_PROFILE="${DEVICE_PROFILE:-pixel_6}"

# --- Repo / app -------------------------------------------------------------
# Repo root = two levels up from this file (scripts/e2e/env.sh).
REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
APK_PATH="${APK_PATH:-$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk}"
# Debug build applies applicationIdSuffix ".debug".
APP_ID="${APP_ID:-net.devemperor.dictate.debug}"
# IME component class stays on the namespace package (no .debug suffix).
IME_SERVICE_CLASS="${IME_SERVICE_CLASS:-net.devemperor.dictate.core.DictateInputMethodService}"
IME_ID="${IME_ID:-$APP_ID/$IME_SERVICE_CLASS}"

# --- Artifacts --------------------------------------------------------------
E2E_TMP="${E2E_TMP:-$REPO_ROOT/tmp/e2e-setup}"
EMU_LOG="${EMU_LOG:-$E2E_TMP/emulator.log}"
mkdir -p "$E2E_TMP"

# Boot timeout (seconds) for the guest to reach sys.boot_completed=1.
BOOT_TIMEOUT="${BOOT_TIMEOUT:-240}"

log() { printf '[e2e] %s\n' "$*" >&2; }
