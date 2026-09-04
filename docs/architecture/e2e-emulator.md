# Local Android Emulator E2E Infrastructure

Headless Android emulator setup for driving the Dictate IME end-to-end on a
Linux VM (no display). Lets you install the app, activate the Dictate keyboard,
take screenshots, and run `connectedAndroidTest` — all from a shell.

## Prerequisites

- **KVM** available and usable (`/dev/kvm`, user in the `kvm` group).
  Verify: `$ANDROID_HOME/emulator/emulator -accel-check`.
- **Android SDK** at `$ANDROID_HOME` (default `~/android-sdk`) with
  `cmdline-tools/latest` (`sdkmanager`, `avdmanager`) and `platform-tools`
  (`adb`) present. The `emulator` package and a system image are installed
  on demand by `emulator-up.sh` — no manual step needed.
- Headless host: the emulator must run with `-no-window` and a software GPU
  (`-gpu swiftshader_indirect`); there is no hardware GL.

Default target: **API 35, `google_apis`, `x86_64`** (matches the app's
`targetSdk 35`). Override via `SYS_IMAGE`/`PLATFORM_PKG` (see `scripts/e2e/env.sh`)
to fall back to API 34 if 35 proves unstable.

## Usage

```bash
# 1. Boot the emulator (installs SDK packages + creates the AVD on first run).
scripts/e2e/emulator-up.sh

# 2. Install the debug APK and make Dictate the active keyboard.
scripts/e2e/install-and-enable-ime.sh          # uses an existing APK
scripts/e2e/install-and-enable-ime.sh --build  # builds assembleDebug first

# 3. (optional) Run the instrumented test suite against the emulator.
./gradlew connectedDebugAndroidTest

# 4. Shut the emulator down when done.
scripts/e2e/emulator-down.sh
```

All three scripts are idempotent and use `set -euo pipefail`. Configuration
(AVD name, port, SDK paths, IME id, artifact dir) lives in
`scripts/e2e/env.sh` and is fully overridable from the environment.

### Verifying the keyboard is really visible

`ime set` only binds the IME; the keyboard window appears only when an editor
is focused. To prove it end-to-end, focus a text field and check the
authoritative signal plus a screenshot:

```bash
adb=$ANDROID_HOME/platform-tools/adb
# Open a guaranteed text field (contact editor) and tap the first EditText.
$adb shell am start -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact
$adb shell input tap 535 935        # "First name" field on a Pixel-6 1080x2400 AVD
$adb shell dumpsys input_method | grep -E 'mInputShown|mCurMethodId'
#   -> mCurMethodId=net.devemperor.dictate.debug/...DictateInputMethodService
#   -> mInputShown=true
$adb exec-out screencap -p > tmp/e2e-setup/keyboard-visible.png
```

`mInputShown=true` + `mCurMethodId=<Dictate>` is the machine-checkable proof;
the screenshot is the human-visible confirmation.

## Key facts

- **IME id**: `net.devemperor.dictate.debug/net.devemperor.dictate.core.DictateInputMethodService`.
  The debug build applies `applicationIdSuffix ".debug"`, but the service
  **class** stays on the `net.devemperor.dictate` namespace — hence the
  package half carries `.debug` and the class half does not.
  `install-and-enable-ime.sh` discovers the id from the device
  (`ime list -a -s`) rather than trusting a hard-coded string.
- Cold boot on this VM is ~20–25 s with KVM.
- Serial is `emulator-5554` (port 5554).

## Gotchas

- **Dirty userdata hangs the boot forever.** If an emulator is hard-killed
  (e.g. its parent process/session dies), the guest `userdata` ext4 image is
  left dirty. The next `-no-snapshot` boot then hangs indefinitely at
  *"performing a full startup"* and `adb devices` shows the device stuck
  `offline`. Fix: boot once with `-wipe-data`. `emulator-up.sh` handles this
  automatically — it retries with `-wipe-data` once if the first boot does not
  reach `sys.boot_completed` within `BOOT_TIMEOUT`.
- **Decouple the emulator from your shell.** `emulator-up.sh` launches via
  `setsid nohup … </dev/null` so the emulator survives the death of the
  calling session. Do **not** start it as a plain background job of a
  short-lived process.
- **Never `pkill -f "qemu-system-x86_64"`.** The pattern also matches the
  command line of the very script issuing the `pkill` (the string appears in
  its own arguments), so it kills its own shell mid-run. Stop the emulator with
  `adb -s emulator-5554 emu kill` instead (this is what `emulator-down.sh` does).
- **`-gpu swiftshader_indirect` is mandatory headless.** Hardware/host GL is
  unavailable; other `-gpu` modes fail to start the renderer.

## Current instrumented-test status (2026-07-17)

`connectedDebugAndroidTest` **runs** against the emulator (build → test-APK
install → 39 tests executed → HTML report), so the infrastructure is proven.
All 39 tests currently **fail** for two pre-existing, app-side reasons that are
independent of the emulator setup:

1. **Migration tests (28)** — `FileNotFoundException: Cannot find the schema
   file in the assets folder … DictateDatabase/1.json`. The exported schemas
   exist under `app/schemas/` but the `androidTest` source set does not wire
   them in as assets. Fix (not applied here — app build config):
   `sourceSets { androidTest { assets.srcDirs += files("$projectDir/schemas") } }`.
2. **`KeyboardLayoutUiTest` (10)** — `IllegalArgumentException: The style on
   this component requires your app theme to be Theme.MaterialComponents (or a
   descendant)`. The test activity/theme is not a MaterialComponents descendant.

Both are test-configuration defects, not emulator issues.

## Tooling for keyboard control & latency measurement

Evaluated for (a) interactive control from Claude Code and (b) reproducible
latency measurement (time-to-keyboard-visible after focus; per-button response
time).

| Tool | Interactive control | Latency measurement |
|---|---|---|
| **mobile-mcp** (`@mobilenext/mobile-mcp`) | **Best** — MCP-native, drives adb, reads the accessibility tree (deterministic, no vision model) | None |
| **Maestro** | Good for reproducible interaction choreography (YAML flows) | Only coarse; deliberately flaky-tolerant → smooths away the ms you want to measure |
| **UIAutomator2 (openatx)** | Solid scriptable driver (Python) | Good as a driver with your own `perf_counter` wrappers; installs its own test-IME for text entry — keep it disabled so you measure Dictate |
| **adb raw** (`dumpsys input_method`, `gfxinfo framestats`, Perfetto/atrace) | Low-level, verbose | **Best** — the only path to ms/µs-accurate, reproducible numbers |

**Recommendation — (a) interactive debugging from Claude Code:** **mobile-mcp**.
MCP-native, headless-emulator-compatible over adb, exposes the Dictate UI to the
model as an element tree. Setup:
`claude mcp add mobile -- npx -y @mobilenext/mobile-mcp@latest`.

**Recommendation — (b) reproducible latency:** **app-side atrace markers in the
IME + Perfetto FrameTimeline**, with `dumpsys input_method | grep mInputShown`
+ `gfxinfo framestats` as a lightweight fallback. Because Dictate *is* the IME
and we own its code, instrument `onStartInputView`/`onCreateInputView`/first
draw with `Trace.beginSection(...)`, record a Perfetto trace
(`input`+`view`+`gfx`+`android.surfaceflinger.frametimeline`), and diff the
focus/input slice against the frame's *actual present time* via
`trace_processor` SQL (`actual_frame_timeline_slice`). This measures "rendered
**and presented**", which black-box tools (Maestro/mobile-mcp) cannot. Drive the
identical interaction N times (UIAutomator2 or `adb input`) and report
median/P90 rather than single samples.

Quick latency probe without a full trace:

```bash
adb shell dumpsys input_method | grep mInputShown              # visibility flag
adb shell dumpsys gfxinfo net.devemperor.dictate framestats    # ns present times
```
