# E2E-Runbook: dictate-keyboard-layout-refactor

**Plan:** [→ dictate-keyboard-layout-refactor.reviewed.md](../dictate-keyboard-layout-refactor.reviewed.md)
**Status:** ready (Phase 1b output)
**Created:** 2026-05-14
**Mode-Distribution:** auto: 0, manual: 23 — Android IME E2E is fundamentally device-attached + human-driven (no headless test runner for IME flows; instrumented tests in B3/B4/B5 are covered as code-level test-tier, not E2E)

## Scope

End-to-end verification that the refactor (Foreground-Service `DictatePipelineService`, `DictateOrchestrator` + 13 DictateModule singletons, Room v3→v4 migration, MotionLayout-based KEYBOARD-backend, OverlayBackend for WIDGET+HOVER, Triangle-FSM KEYBOARD/WIDGET/HOVER with T1-T7 transitions) preserves and extends user-visible behaviour:

1. **Survival**: Recording survives Tastatur-Wechsel (switching to another keyboard and back) — this is the central goal (§2.1) and was the main Bug-Class.
2. **Visibility**: `resend_btn`, `record_btn.text/isEnabled`, Send-Mode-Sichtbarkeit predicate-driven from `LayoutCatalog` — no race conditions across 5 hybrid mutators (§2.3 Bug-Elimination-Ziele).
3. **Triangle-FSM**: T1-T7 transitions between KEYBOARD, WIDGET, HOVER are deterministic (§3.1); SmallMode after WIDGET-Schließen works; Geist-Widget-Bug (T7) cannot reappear.
4. **Persistence + Recovery**: DB-Migration M3→M4 (additive `inserted_at` column + `SessionStatus` enum 4→6 values + CHECK-Recreate) does not corrupt or lose user data; OOM-Recovery via DB-Replay shows running sessions, User-Resume works.
5. **Overlay-Permission Flow**: First-time permission ask, denial-fallback (Notification), drag-position persistence portrait/landscape.
6. **No regression in primary path**: Record → Transcribe → Insert into target app; Send-Mode; Rewording-prompts; multi-language UI.

## Relevant Knowledge

> **Knowledge-gap flag (escalated to orchestrator):** No `test-knowledge-android`, `test-knowledge-mobile`, or `test-knowledge-ime` skill exists under `~/.claude/skills/`. Phase 4.5 will hand-roll without skill-grounding. The runbook's manual steps therefore carry **explicit step-by-step instructions** rather than relying on a knowledge-skill to fill in idioms. Phase 4.6c may add a `knowledge-dictate` or `knowledge-android-ime` skill mid-implementation.

Available skills relevant here:
- `knowledge-adr-format` (verify Block 0 ADRs ship as deliverables — out-of-scope for Phase 4.5 but a "Phase 4.6-ADR-cross-check" prerequisite)
- `knowledge-doc-format` (verify state-architecture/ docs ship)
- `test-orchestrator` (runs project's own test-runner — useful for invoking `./gradlew connectedAndroidTest` on the migration test suite during the Phase-4.5 Setup step)

Out-of-set knowledge needed:
- Android `adb logcat` filter idioms (hand-rolled below)
- Android IME-enablement steps (hand-rolled below — Settings → System → Languages & Input → On-screen keyboard)
- WindowManager TYPE_APPLICATION_OVERLAY UX-validation (hand-rolled below — Spec 3 §11.1/§11.2)

## Prerequisites

| # | Kind | Target | Programmatic check | Blocking |
|---|------|--------|---------------------|----------|
| 1 | gradle-wrapper | `./gradlew` in worktree | `test -x ./gradlew` | yes |
| 2 | jvm | JDK 17+ for AGP 8.x | `java -version 2>&1 \| grep -E '\"(17\|2[0-9])\\.'` matches | yes |
| 3 | android-sdk | Android SDK with API 35 installed | `test -d $ANDROID_HOME/platforms/android-35` | yes |
| 4 | device | Android device OR emulator (API 26-35) reachable via ADB | `adb devices` shows ≥1 device with state `device` (not `unauthorized`/`offline`) | yes |
| 5 | adb-connection | **USB cable connection — NOT Wireless** (memory-flag: `user_dev_setup.md` — ADB Wireless is unstable for this user) | `adb shell ip route` succeeds 5× in 60 s without disconnect | yes |
| 6 | apk-installed | Dictate APK installed from this worktree's `./gradlew assembleDebug` output | `adb shell pm list packages \| grep net.devemperor.dictate` | yes |
| 7 | ime-enabled | Dictate IME is **enabled** in Android Settings → System → Languages & Input → On-screen keyboard → Manage keyboards | `adb shell ime list -a \| grep net.devemperor.dictate` shows the IME line | yes |
| 8 | ime-selected | Dictate IME is the **currently selected** keyboard | `adb shell settings get secure default_input_method` outputs `net.devemperor.dictate/...` | yes |
| 9 | overlay-permission | `SYSTEM_ALERT_WINDOW` permission for `net.devemperor.dictate` — TC requires both grant + deny paths | `adb shell appops get net.devemperor.dictate SYSTEM_ALERT_WINDOW` reports `allow` / `deny` per TC | no — TC-specific |
| 10 | mic-permission | `RECORD_AUDIO` permission granted | `adb shell dumpsys package net.devemperor.dictate \| grep RECORD_AUDIO` shows granted | yes |
| 11 | target-app | A target app with a text input field installed (default: pre-installed Notes / Messages / Keep) | `adb shell pm list packages -d` shows ≥1 keep/notes/messages app | yes |
| 12 | logcat-baseline | `adb logcat -c` clears the buffer at Setup start | n/a — preparatory step | no |
| 13 | api-key | At least one AI-provider API key configured in Dictate Settings (Whisper for transcription) | manual: open Dictate Settings → "API Keys" → ≥1 provider key set | yes |
| 14 | network | Device has WLAN (recording → transcription API call) | `adb shell ping -c 3 api.openai.com` succeeds (or chosen provider) | yes |
| 15 | personal-device-consent | If the device is a personal device (not a dedicated test-device), the user has confirmed: "I'm OK with the test running on my actual phone — DB will be migrated v3→v4, Foreground-Service-Notification will appear, Overlay-Permission will be asked." | manual: see "User Questions" below | yes |
| 16 | room-testing-dep | For chunk C9b1 androidTest infrastructure — `room-testing` Gradle dependency present after B3 completes | `grep room-testing app/build.gradle*` | yes (B3+ only) |
| 17 | block-1b-survival-baseline | A baseline "before-refactor" manual smoke-test was captured before B1 starts (1-pager screenshots: record, send, widget, keyboard-switch-survival fail) | manual: see TC-PRE | no (recommended) |

## User Questions (resolved before Phase 4.5 execution)

| # | Question | Default options | Why blocking |
|---|----------|-----------------|--------------|
| Q1 | Which device do we run Phase-4.5 E2E on — your personal phone, a dedicated test-device, or an emulator? | (1) Personal phone (with consent) · (2) Dedicated test-device · (3) Android Emulator (API 35) · (4) Decide at Phase-4.5 time | Determines blast-radius. Personal-phone needs explicit OK because the DB-Migration runs on real data. |
| Q2 | If personal phone: do you accept that the v3→v4 Room-Migration will run on your real Dictate session DB? (Recovery-path is rollback-safe per plan §6: M4 is additive + idempotent, but: are there in-flight RUNNING sessions you care about preserving?) | (1) Yes — proceed (rollback path documented) · (2) Backup the DB first (`adb pull /data/data/net.devemperor.dictate/databases/`) · (3) Use a fresh emulator instead | Real user-data sensitivity. The plan §6 declares M4 additive, but Phase-4.5 is "in production" testing. |
| Q3 | ADB connection: USB-cable (recommended per `user_dev_setup.md`) or Wireless? | (1) USB-cable (recommended) · (2) Wireless (accept-known-unstable) | Wireless drops mid-test invalidate logcat-correlations. |
| Q4 | Which AI-provider for the transcription happy-path? | (1) OpenAI Whisper (cheapest stable) · (2) Groq Whisper · (3) Use what's already configured | Affects test latency, API-quota cost. |
| Q5 | Target app for typing: which app should TC-1..TC-6 type into? | (1) Google Keep / Notes (default) · (2) Signal / WhatsApp (rich-text) · (3) Browser address bar (single-line) · (4) All three | Different IMEs of the same Dictate code path can show edge-cases (single-line, multi-line, rich-text). |
| Q6 | Cleanup after E2E: should the runbook tear down (uninstall, clear app-data, restore DB) — or leave Dictate installed for daily use? | (1) Tear down · (2) Leave installed but clear app-data · (3) Leave fully installed | Personal-phone scenario: user likely wants (3); test-device: (1). |
| Q7 | Overlay-Permission: should we test both grant and deny paths? Deny-path needs revoke + re-prompt. | (1) Both grant + deny (full coverage) · (2) Grant only (skip TC-overlay-deny) · (3) Deny only (test fallback) | Full coverage is recommended but extends manual test time ~10 min. |

> **Forwarded to orchestrator below in stdout — orchestrator gates Phase 4.5 on Q1+Q2+Q3 minimum.**

## Test Cases

### Pre-flight — TC-PRE: Baseline smoke before B1

- **Mode:** manual
- **Knowledge:** hand-rolled
- **Scope:** Capture "before-refactor" behaviour as evidence (optional but recommended)
- **Steps:**
  1. Install Dictate APK from **pre-B1 commit** (the commit immediately before B1's first commit).
  2. Open Notes app, switch keyboard to Dictate.
  3. Tap record, dictate "Hello world", verify text appears.
  4. With recording running: switch keyboard to Gboard. Note: today, recording terminates silently (the bug the plan fixes).
  5. Take screenshot or note "baseline confirms: keyboard-switch kills recording today".
- **Expected:** Documented baseline. No assertion — this is a reference point.

---

### Block 0 group — TC-B0-DOCS: Architecture-foundation docs exist

- **Mode:** manual (verification of file-presence)
- **Knowledge:** knowledge-adr-format, knowledge-doc-format
- **Scope:** Phase-4.5 is normally code-only, but Block 0 is doc-only — TC-B0-DOCS verifies the doc-set ships.
- **Steps:**
  1. `ls docs/decisions/` shows 5 ADRs per plan §8.1 (state-modular-orchestrator-pattern, state-cross-module-cascade, service-foreground-pipeline-architecture, ui-layout-catalog-motionlayout, ui-triangle-fsm-keyboard-widget-hover) + `README.md`.
  2. `ls docs/architecture/state-architecture/` shows 11 sub-files per plan §4.0.2 + `README.md`.
  3. Each ADR has 14 sections per plan §4.0.1.0.3 (status, context, decision, alternatives, consequences, references, decision-history, etc.).
  4. ADR cross-reference graph per plan §4.0.1.0.2 is intact (`grep -r "ADR-NNNN-" docs/decisions/` finds each peer-link).
- **Expected:** All docs present + structurally complete. No code-level assertion.

---

### Survival group — the central refactor goal

### TC-1: Keyboard-switch survival — recording continues through IME-restart

- **Mode:** manual
- **Knowledge:** hand-rolled (ADB + logcat)
- **Scope:** Core goal §2.1: Foreground-Service holds the process alive when IME-View dies. Bug-Class-Eliminierung §2.3.
- **Steps:**
  1. Setup: Dictate IME selected, target app open with text-field focused.
  2. Tap record button. Verify: recording starts (waveform animation visible, foreground notification appears).
  3. Switch to another keyboard (Gboard / SwiftKey) via system keyboard-switcher.
  4. Wait 10 seconds — verify foreground notification still says "Dictate is recording".
  5. Switch back to Dictate keyboard.
  6. Verify: keyboard UI shows recording is still active (the same waveform, not a new session); record-button shows "stop" not "start".
  7. Tap stop. Verify: transcription completes, text appears in target app.
  8. `adb logcat -d | grep -E "(DictatePipelineService|DictateOrchestrator|onCreateInputView|onDestroy)"` — verify no crash, no NPE, no "Service not bound" exception.
- **Expected:** Recording survives the round-trip. Transcription text is exactly one continuous utterance, not split.

### TC-2: Keyboard-switch + transcribe → insert into different target app

- **Mode:** manual
- **Knowledge:** hand-rolled
- **Scope:** Verify InputConnection rebinding to a *different* target after IME-View-recreation.
- **Steps:**
  1. Open Notes-A, tap record, say "Note one".
  2. Without stopping: switch keyboard to Gboard, then back to Dictate.
  3. Switch app: close Notes-A, open Notes-B, focus its text field.
  4. Tap stop on Dictate.
  5. Verify: transcription "Note one" inserts into Notes-B (the currently-focused field).
- **Expected:** Transcription routes to the active InputConnection, even if it's a different app than where recording started. Spec 1 §7 covers this.

### TC-3: Process-survival — running session visible after IME-View-restart

- **Mode:** manual
- **Knowledge:** hand-rolled
- **Scope:** Verify DictateUiStateStore is re-subscribed correctly after IME-View dies + recreates.
- **Steps:**
  1. Tap record. Note: recording starts.
  2. Force-close target-app (swipe-away from recents).
  3. Re-open target app. Focus text field. Dictate-IME loads.
  4. Verify: keyboard immediately shows recording-active state (record-button=stop, waveform visible).
  5. Tap stop. Transcription completes.
- **Expected:** State is restored, not reset. Spec 1 §15 ViewModule + StateStore restore covers this.

---

### Triangle-FSM group (T1-T7)

### TC-4: T1 KEYBOARD → WIDGET — User toggles Widget mode

- **Mode:** manual
- **Knowledge:** hand-rolled
- **Scope:** Plan §3.1 T1 + Spec 3 §7.3 T1.
- **Steps:**
  1. Default state: KEYBOARD mode visible.
  2. Tap Widget-toggle button.
  3. Verify: WIDGET overlay appears (5 buttons: Record, Send, Pause, Trash, Close per OPEN-2 resolution); main keyboard collapses to small-mode or fully detaches per spec.
  4. Tap into target-app text field. Verify: InputConnection still works (typing through WIDGET).
- **Expected:** Smooth visual transition; no IME-View flicker; 5 WIDGET-buttons functional.

### TC-5: T2 WIDGET → KEYBOARD with SmallMode (OPEN-1 resolution)

- **Mode:** manual
- **Scope:** Plan §3.1 T2 + OPEN-1 resolution (SmallMode after WIDGET-close).
- **Steps:**
  1. Start in WIDGET mode (per TC-4).
  2. Tap Close-button in WIDGET.
  3. Verify: keyboard re-appears in **SmallMode** (single-row, not two-row).
  4. Type a character. Verify: works as expected.
- **Expected:** SmallMode is the post-WIDGET-close state.

### TC-6: T3 KEYBOARD → HOVER — IME-View hidden + Pipeline active → auto-HOVER

- **Mode:** manual
- **Scope:** Plan §3.1 T3.
- **Steps:**
  1. Recording active (TC-1 step 1-2).
  2. Tap somewhere outside the text field to dismiss the keyboard.
  3. Verify: HOVER overlay appears (4 buttons: Send DISABLED, Pause, Trash, Close).
  4. `adb logcat -d | grep ViewMode` shows ViewMode transition KEYBOARD→HOVER.
- **Expected:** Auto-transition to HOVER on IME-View-hide + pipeline-active.

### TC-7: T4 WIDGET → HOVER — IME-View hidden + Pipeline active + was WIDGET

- **Mode:** manual
- **Scope:** Plan §3.1 T4 + WIDGET persistence (OPEN-1/OPEN-2 bit).
- **Steps:**
  1. Start in WIDGET-mode (TC-4) with recording active.
  2. Tap outside or close the focused app momentarily.
  3. Verify: WIDGET stays visible (no transition to HOVER — WIDGET-persistence-bit per spec 3 §7.3 T4 means HOVER inherits from WIDGET only on IME-View-hide).
  4. Re-focus the app field → WIDGET returns (per T6).
- **Expected:** Per spec 3 §7.3 — WIDGET persists across temporary IME-View-hides; ViewMode-transitions are spec-compliant.

### TC-8: T5 HOVER → KEYBOARD — IME-View returns, was NOT WIDGET

- **Mode:** manual
- **Scope:** Plan §3.1 T5.
- **Steps:**
  1. Reach HOVER state per TC-6.
  2. Re-focus text field. Dictate-IME-View re-loads.
  3. Verify: KEYBOARD mode resumes (not WIDGET).
- **Expected:** Was-not-WIDGET-before persists across HOVER.

### TC-9: T6 HOVER → WIDGET — IME-View returns + was WIDGET

- **Mode:** manual
- **Scope:** Plan §3.1 T6.
- **Steps:**
  1. Reach HOVER from WIDGET (per TC-7 step 1-2, with full IME-View-hide).
  2. Re-focus text field.
  3. Verify: returns to WIDGET-mode (persistence-bit per spec 3 §7.3 T6).
- **Expected:** WIDGET-persistence preserved through HOVER round-trip.

### TC-10: T7 HOVER → KEYBOARD via Pipeline-Done-Cascade (Geist-Widget-Bug-Strukturschutz)

- **Mode:** manual
- **Scope:** **Critical** — plan §3.1 T7 + KG-RSB-2 RESOLVED + spec 3 §7.3 T7. This is the regression-test for Geist-Widget-Bug.
- **Steps:**
  1. Tap Widget-toggle (KEYBOARD → WIDGET).
  2. Start recording from WIDGET.
  3. Dismiss IME (WIDGET → HOVER per T4-then-T5 path).
  4. Wait for transcription to complete (pipeline finishes, ResetSuppressBit cascade fires).
  5. Re-focus text-field.
  6. Verify: returns to **KEYBOARD** mode (not WIDGET). The reset-suppress-bit cascade dropped the WIDGET-persistence-bit.
  7. Verify: transcription text inserts correctly.
- **Expected:** T7 structural fix: HOVER → KEYBOARD (not HOVER → WIDGET) after Pipeline-Done. This is the central bug-elimination of plan §2.3 "Stale-Running-Session".

---

### Visibility-Predicate group — Bug-Class §2.3 elimination

### TC-11: resend_btn visibility — predicate-driven, no race

- **Mode:** manual
- **Knowledge:** hand-rolled (UI inspection)
- **Scope:** Plan §2.3 — eliminate `resend_btn`-race (5 mutators → 1 predicate). Spec 2 §14.2 VisibilityMatrixTest covers this at JVM level; TC-11 is the on-device E2E.
- **Steps:**
  1. Default state: resend_btn NOT visible.
  2. Send a transcription. Verify: resend_btn becomes visible after success.
  3. Switch keyboard out and back. Verify: resend_btn visibility is **preserved** (still visible).
  4. Open Widget-mode (T1). Verify: resend_btn correctly hidden/shown per spec 2 §14.2.
  5. Trigger reword. Verify: resend_btn behaviour per spec 2 §14.2.
- **Expected:** resend_btn visibility tracks the state predicate, never gets stuck visible/hidden across mode transitions.

### TC-12: Send-Mode + Single-Row — send-button not occluded

- **Mode:** manual
- **Scope:** Plan §2.3 "Send-Button im Send-Modus + Single-Row korrekt sichtbar".
- **Steps:**
  1. Trigger Send-Mode (after transcription).
  2. Switch to Single-Row keyboard layout (settings → single-row variant, or trigger SmallMode).
  3. Verify: send-button is fully visible, not clipped or covered.
- **Expected:** No clipping. The structural bug of two-row → single-row coverage is eliminated.

### TC-13: record_btn.text/isEnabled — no controller-overlap

- **Mode:** manual
- **Scope:** Plan §2.3 "record_btn.text/isEnabled-Hybrid (RecordingUiController + KeyboardUiController überschreiben sich heute)".
- **Steps:**
  1. Rapid-toggle record start/stop 5× in 5 seconds.
  2. Verify: record_btn.text stays consistent ("start" / "stop") at each tap; no flicker showing wrong label.
  3. `adb logcat -d | grep -E "(KeyboardUiController|record_btn)"` — verify single dispatch path.
- **Expected:** No race-condition on record_btn label/state.

---

### Persistence + Recovery group

### TC-14: DB-Migration M3→M4 on real user data

- **Mode:** manual + adb-script
- **Knowledge:** hand-rolled (per Spec 1 §6.1 + §11.4.2)
- **Scope:** **Critical** — plan §6: additive `inserted_at` column + SessionStatus enum 4→6 values + CHECK constraint recreate. User-data loss risk if migration mis-fires.
- **Steps:**
  1. Pre-step: backup DB. `adb shell run-as net.devemperor.dictate cat databases/dictate-database` → save locally (or `adb pull` if device is rooted).
  2. Have ≥1 RUNNING session, ≥1 COMPLETED session, ≥1 FAILED/CANCELLED session in the pre-refactor DB (use TC-PRE baseline).
  3. Install B3-completed APK over the pre-refactor APK.
  4. Open Dictate, trigger any state-read (e.g., open history view).
  5. `adb shell run-as net.devemperor.dictate sqlite3 databases/dictate-database 'SELECT version FROM room_master_table'` shows version=4.
  6. `... 'PRAGMA table_info(sessions)'` shows `inserted_at` column present.
  7. `... 'SELECT count(*), status FROM sessions GROUP BY status'` shows all 6 SessionStatus values are queryable, no records LOST.
  8. RUNNING sessions remain RUNNING (idempotent migration); FAILED/CANCELLED/COMPLETED counts unchanged.
- **Expected:** Migration is additive + idempotent. No data loss.

### TC-15: OOM-Recovery → User-Resume from DB

- **Mode:** manual
- **Knowledge:** hand-rolled (Spec 1 §11.6 + §4.6 PipelineRecovery)
- **Scope:** Plan §2.3 "Stale-Running-Session" bug elimination — manual resume from DB after OOM-Death.
- **Steps:**
  1. Start recording. Wait ~5 seconds to ensure DB-persist fired.
  2. Force-kill the Dictate process: `adb shell am force-stop net.devemperor.dictate`.
  3. Re-open Dictate-IME by focusing a text field.
  4. Verify: notification shows "Aufnahme unterbrochen — Wiederherstellen?" or equivalent (Spec 1 §11.6 + Spec 3 §9 Notification-Fallback).
  5. Tap "Resume" / re-open in IME.
  6. Verify: the RUNNING session is presented with a User-Resume button (per Spec 1 §7 OPEN-4 resolution).
  7. Tap resume — recording does NOT auto-restart, but session is acknowledged.
  8. Tap stop or cancel. DB updates to CANCELLED.
- **Expected:** Stale-RUNNING-session is recoverable, no zombie state.

### TC-16: Cleanup orphan FAILED audio files (KG-SST-2 RESOLVED)

- **Mode:** manual
- **Scope:** Spec 1 §6.3.1 — orphan-cleanup `cleanupOrphanedTerminalAudio()`.
- **Steps:**
  1. Force-create an orphan: cancel a session mid-recording (creates FAILED with audio-file).
  2. Wait 60+ seconds (cutoff threshold per Spec 1 §4.11.10).
  3. Trigger service-onCreate (force-stop + relaunch the IME).
  4. Verify (via `adb shell run-as`): the orphaned audio file in cache is deleted; DB row is updated to mark audio-file-cleaned-up.
- **Expected:** Orphan-cleanup runs at service-onCreate, no stale audio files.

---

### Overlay-Permission group (Block 6 / Spec 3)

### TC-17: First-time Overlay-Permission ask flow

- **Mode:** manual
- **Knowledge:** hand-rolled (Spec 3 §5)
- **Scope:** First-time Permission-Onboarding.
- **Steps:**
  1. Fresh install (or `pm clear net.devemperor.dictate` to reset permission state).
  2. Open Dictate IME, focus text field.
  3. Tap Widget-toggle for the first time.
  4. Verify: Onboarding-UI in IME-View explains why permission is needed (Spec 3 §5.3).
  5. Tap "Grant".
  6. System redirects to Settings → "Display over other apps". Toggle permission ON.
  7. Return to app. Verify: WIDGET overlay now appears.
- **Expected:** First-time flow is informative + non-frustrating. Permission grant triggers immediate WIDGET-render.

### TC-18: Permission denied → Notification fallback

- **Mode:** manual
- **Scope:** Spec 3 §5.6 + §9 + OPEN-5 resolution — Notification-Fallback when overlay denied.
- **Steps:**
  1. Fresh state. Open Dictate IME.
  2. Tap Widget-toggle.
  3. In Settings, deny SYSTEM_ALERT_WINDOW or close Settings without granting.
  4. Verify: Notification appears providing fallback controls (Pause / Trash / Send).
  5. Pull-down notification — verify pause/cancel work from notification.
- **Expected:** Denial does not strand the user — Notification-Fallback is functional.

### TC-19: Overlay drag + position persistence

- **Mode:** manual
- **Scope:** Spec 3 §4.6 + §11.5 + OPEN-3 — Drag in portrait + landscape, persist normalised 0..1 positions separately.
- **Steps:**
  1. Open WIDGET (permission granted).
  2. Drag WIDGET to a new portrait position.
  3. Force-close + reopen IME, re-enter WIDGET.
  4. Verify: WIDGET appears in the dragged position.
  5. Rotate device to landscape.
  6. Drag WIDGET to a different landscape position.
  7. Rotate back to portrait. Verify: portrait position restored (separate from landscape).
- **Expected:** Two independent position-stores; round-trip rotation preserves both.

### TC-20: Overlay survives orientation change while recording

- **Mode:** manual
- **Scope:** WindowManager LayoutParams resilience across orientation.
- **Steps:**
  1. Start recording in WIDGET-mode.
  2. Rotate device. Verify: WIDGET re-positions per landscape coords, recording continues.
  3. Rotate back. Verify: still recording, WIDGET back to portrait position.
- **Expected:** No flash, no recording-interrupt.

---

### MotionLayout + UI-rendering group (Block 5 / Spec 2)

### TC-21: MotionLayout transitions — no visual flicker

- **Mode:** manual
- **Knowledge:** hand-rolled (Spec 2 §7 + §11.4)
- **Scope:** Block 5 MotionScene; mitigates risk-row "MotionLayout-Inflation-Cost zu hoch" (plan §6).
- **Steps:**
  1. Open IME, observe initial layout-render. Verify: no >500ms blank-keyboard.
  2. Trigger Two-Row → Single-Row layout-switch (settings or SmallMode).
  3. Verify: smooth animated transition, no constraint-set jump.
  4. Repeat for: Default → Send-Mode, Send-Mode → ReprocessStaging.
  5. `adb logcat -d | grep -E "(MotionLayout|onCreateInputView)"` — verify inflation-time < 50 ms (per Spec 2 §11.4 acceptance).
- **Expected:** All 4 KEYBOARD-sub-mode-transitions smooth, no asymmetric re-parenting bugs (plan §2.3 first bullet).

### TC-22: PulseLayout animation in MotionLayout transition (Risk-Mitigation)

- **Mode:** manual
- **Knowledge:** hand-rolled (Spec 2 §11.3)
- **Scope:** Plan §6 risk-row "PulseLayout-Animation in MotionLayout-Transition könnte brechen".
- **Steps:**
  1. Trigger PulseLayout (visual pulse on record-button during active recording).
  2. While pulse is animating, trigger a layout transition (e.g., Two-Row → Single-Row).
  3. Verify: PulseLayout continues animating smoothly through the transition, does not freeze or skip.
- **Expected:** PulseLayout in motion-layout-transition does not break. If broken: fallback per plan §6 (Option 4 from motionlayout-architecture-options.md) — but Phase-4.5 is detection, fallback is a follow-up plan.

---

### Cross-Module-Cascade group (Spec 1 §15)

### TC-23: Mode 1 + Mode 2 cascades — observable behaviour intact

- **Mode:** manual + logcat-scan
- **Knowledge:** hand-rolled (Spec 1 §15.1)
- **Scope:** Verify cross-module cascade rules (Mode 1 read-on-write, Mode 2 atomic-cross-axis; Mode 3 forbidden per ADR-2 of Block 0).
- **Steps:**
  1. Trigger a known Mode-1-cascade-path (e.g., recording-active → record-button-disabled in other modules).
  2. Verify: state propagates within one dispatch.
  3. `adb logcat -d | grep "CASCADE_DEPTH"` — verify cascade depth ≤ MAX_CASCADE_DEPTH (8 per ADR-2).
  4. No `cascade-depth-exceeded` exceptions.
- **Expected:** Cascades run within depth limit, no infinite-loop, no Mode-3 violation in logcat (no warnings).

---

## Periodic Visits (run at Setup, after every TC-group, and at Teardown)

| Visit | Command | Pass-criterion |
|-------|---------|----------------|
| logcat-error-scan | `adb logcat -d -t 5m \| grep -E "(FATAL\|AndroidRuntime\|ANR\|NPE\|net.devemperor)"` | No NPE, no FATAL, no ANR for `net.devemperor.dictate` |
| memory-profile | `adb shell dumpsys meminfo net.devemperor.dictate \| head -50` | TotalPss < 80 MB sustained; no monotonic growth over a 10-min session |
| battery-impact | `adb shell dumpsys batterystats --charged net.devemperor.dictate \| head -40` | Foreground-service-uptime correlates with active-recording-duration (≤ 1.1×) — not 2× or higher |
| anr-detect | `adb shell ls /data/anr/` (rooted) OR `adb logcat -d \| grep ANR` (always) | No new ANR files / log lines since Setup |
| fgs-notification-presence | manual swipe to view notifications | Persistent FGS-notification visible while recording, dismissed when stopped |
| db-row-count-sanity | `adb shell run-as net.devemperor.dictate sqlite3 databases/dictate-database 'SELECT count(*), status FROM sessions GROUP BY status'` | Numbers monotonic per session-lifecycle, no orphan RUNNING |

## Teardown

| Step | Action | Notes |
|------|--------|-------|
| 1 | Stop any active recording | Tap stop in Dictate |
| 2 | Verify all sessions in DB are terminal (COMPLETED / FAILED / CANCELLED) | Per Periodic-Visit db-row-count-sanity |
| 3 | Per User-Question Q6 — execute selected cleanup option | (1) Uninstall: `adb uninstall net.devemperor.dictate` · (2) Clear data only: `adb shell pm clear net.devemperor.dictate` · (3) Leave installed |
| 4 | If pre-flight #16 baseline DB was saved: confirm whether to restore or discard | Personal-phone scenario: restore if user wants pre-test state |
| 5 | Save `adb logcat -d > reports/phase-4.5-logcat-$(date +%Y%m%d).log` | Long-term diagnostic anchor |
| 6 | Save device-info for the report | `adb shell getprop ro.build.version.release ro.product.manufacturer ro.product.model` |

## Acceptance

- All `mode: manual` test cases TC-1 through TC-23 (and TC-B0-DOCS) PASS — confirmed by user during run.
- Periodic-Visit pass-criteria all green across the run.
- No `mode: auto` cases (Android-IME E2E has none — the auto-tier is covered by JVM unit + instrumented tests in Block-validate phases).
- Phase-4.5 report at `./reports/phase-4.5-e2e-report.md` enumerates: device-info, TC-pass/fail counts, logcat-archive-path, any deviations.

## Failure Routing

On TC failure: orchestrator runs the repair-sub-phase (research → research-file → resume-fix → re-test). Phase-4.5 has up to 3 outer iterations. After 3 without convergence: `AskUserQuestion` with options (1) accept partial pass + open follow-up issues, (2) revert wave-commits + re-implement, (3) escalate to user-decision.

## Phase-4.5 Refresh (added by orchestrator before execution)

Before Phase-4.5 runs, the orchestrator gegen-checks this runbook against the last block outputs (B5 + B6 reports) and may add TCs for "edge-of-the-blade" points that emerged during implementation — e.g., new failure modes surfaced in B3-VAL-W2 or B5-VAL-W1. Placeholder section.

## How to use this file

This runbook is a **complete plan** for Phase 4.5. The Phase-4.5-agent reads it top-to-bottom:
1. Validates Prerequisites (rows 1-17) — gates the run on the user-questions Q1-Q3 first.
2. Executes Periodic Visits at Setup.
3. Runs TC-B0-DOCS through TC-23 in order (grouped — Survival, Triangle-FSM, Visibility, Persistence, Overlay, MotionLayout, Cascade).
4. Periodic Visits between groups.
5. Teardown per User-Question Q6.

Phase-4.5 produces `./reports/phase-4.5-e2e-report.md`.

**Knowledge-gap caveat:** because no `test-knowledge-android` skill exists, the Phase-4.5-agent runs entirely from this runbook's hand-rolled steps. Each step is self-contained — no skill-grounding-fall-back required. If during Phase-4.5 a need for hand-rolled idioms beyond this runbook arises (e.g., logcat-tag-pattern unique to a new module added in B5), the agent flags as a `knowledge-gap` issue rather than blocking.
