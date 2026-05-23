# E2E-Runbook: dictate-cutover-completion

**Plan:** [→ dictate-cutover-completion.md](../dictate-cutover-completion.md)
**Status:** ready (Phase 1b output)
**Created:** 2026-05-15
**Mode-Distribution:** auto: 4, manual: 24 — Android IME E2E is fundamentally device-attached + human-driven; the 4 `auto` cases are grep/gradle compile-and-regression invariants (AC-1/5/6/7/9) that run headless.

> **This runbook has two execution profiles. Read "How to use this file" first.**
> - **C6-SUBSET** (the in-plan **D2-pre verification gate**, chunk `C6-D2pre`,
>   skill-block B2): runs mid-Epic, **after C5 lands the guarded
>   `USE_LEGACY_RECORDING_DRIVE` flip, before C7 deletes the legacy call-site**.
>   Proves the new live path is green so the destructive C7 + all of Theme C
>   (C8/C9/C10) are authorised. Subset = the keystone + Triangle T1–T7 +
>   two-keyboard survival + notification round-trip on the **new** path, with
>   the guard set to the **new** path.
> - **C12-FULL / Phase-4.5-FULL** (chunk `C12-D2`, skill-block B4, and the
>   skill's post-all-blocks Phase 4.5): the **full** TC set + cleanup-greps +
>   the AC-9 ≥946-test regression, run after Theme C deleted the legacy paths.
>
> The per-TC `Profiles:` field marks which profile(s) each TC belongs to.

## Scope

The parent plan `dictate-keyboard-layout-refactor` shipped the new
`DictateOrchestrator` + 14 modules + RenderBackends + Overlay + Triangle-FSM
**unit-green but parallel-dormant** — `ModuleServices.pipelineRunner` and
`.notificationCoordinator` were `Log.w` no-op stubs, so production recording +
the foreground notification were still driven by the legacy
`JobExecutor`/`PipelineOrchestrator` + `LanguageController` + `audioFile`-field
path. This Epic makes the new layer **live** (real `PipelineRunnerSubsystem`
adapter + real `PipelineNotificationCoordinator`, IME recording-trigger flipped
to `pipelineBinder.dispatch(RecordingAction.*)`) and **retires** the legacy
paths it renders dead.

This is a **staged destructive cutover on the product's core feature
(recording)**. The E2E layer's job is to prove the *new live path* delivers
every behaviour the parent plan's runbook proved on the *dormant path* — same
two-keyboard survival + Triangle-FSM trace, now on the cutover path — plus the
cutover-specific behaviours:

1. **Recording-drive cutover (AC-2/AC-3).** Recording started from the IME
   drives the **new** orchestrator: `state.recording` `Idle → Preparing →
   Active`; the pipeline runs via the real `PipelineRunnerSubsystem`
   (JobExecutor-backed); the full `JobRequest` config (language, prompt-queue,
   style/live prompt, autoSwitch, recordingsDir) survives the adapter
   translation (R-1).
2. **Real FGS notification (AC-2/AC-3).** The real `PipelineNotificationCoordinator`
   shows the persistent FGS notification with `[Pause][Stopp][Senden]` action
   buttons; transitions `Recording → Pipeline → Idle`; action-buttons dispatch
   back through `PipelineActionRouter`; `startForeground` does not crash the FGS
   (R-2).
3. **Guarded fallback correctness (R-4 / AC-10).** `USE_LEGACY_RECORDING_DRIVE`
   flips the IME recording-trigger between the legacy and new paths
   mutually-exclusively — no user action triggers both (no double-dispatch).
4. **Survival on the new path.** Recording survives Tastatur-Wechsel (the
   parent plan's raison d'être, ADR-0003) **on the new orchestrator path**, not
   the dormant one.
5. **Triangle-FSM re-trace on the live path.** T1–T7 + the keystone F-1/F-2/F-3
   IME-activation chain still deterministic after the recording-drive flip.
6. **Legacy-retire invariants (AC-1/5/6/7).** Post-Theme-C: stub subsystems
   dereferenced, `LanguageController.kt` deleted, `audioFile` field deleted,
   dead controllers deleted — all grep-verifiable; settings-language-change +
   resend + audio-migration still work.
7. **No regression (AC-9).** Full `./gradlew test` ≥946 green; `assembleDebug`
   green; keystone trace green.

## Relevant Knowledge

> **Knowledge-gap flag (carried from parent — escalated to orchestrator):**
> No `test-knowledge-android`, `test-knowledge-mobile`, or `test-knowledge-ime`
> skill exists under `~/.claude/skills/`. The C6/C12 implementer-agents and
> the Phase-4.5 agent hand-roll without skill-grounding. Every manual TC below
> carries **explicit self-contained adb steps** — matching the parent
> runbook's style (the parent runbook is the proven reuse base). Do not block
> on the missing skill; flag any out-of-runbook idiom need as a `knowledge-gap`
> issue.

Available skills relevant here:
- `test-orchestrator` — runs the project's own test-runner; used for the
  `auto`-tier `./gradlew test` / `assembleDebug` / `connectedAndroidTest`
  invocations (AC-9 regression, AC-8 Espresso).
- `knowledge-adr-format` / `knowledge-doc-format` — out of E2E scope (Phase 4.6),
  listed only because the cutover appends ADR Decision-History (0001/0003/0005).

Out-of-set knowledge needed (hand-rolled below, reused verbatim from parent
runbook):
- Android `adb logcat` filter idioms
- Android IME-enablement steps (Settings → System → Languages & Input →
  On-screen keyboard → Manage keyboards)
- `adb shell run-as` SQLite inspection idioms
- WindowManager `TYPE_APPLICATION_OVERLAY` UX-validation (Spec 3 §11.1/§11.2)

## Prerequisites

Inherited from the parent runbook's 17 prerequisites, **adjusted for the
Epic** (no DB-migration this Epic — Theme A/B/C is a code-cutover, no Room
schema change; so the parent's #14/#15 DB-migration-consent prerequisites are
**dropped/downgraded**).

| # | Kind | Target | Programmatic check | Blocking |
|---|------|--------|---------------------|----------|
| 1 | gradle-wrapper | `./gradlew` in worktree | `test -x ./gradlew` | yes |
| 2 | jvm | JDK 17+ for AGP 8.x | `java -version 2>&1 \| grep -E '"(17\|2[0-9])\.'` matches | yes |
| 3 | android-sdk | Android SDK with API 35 | `test -d "$ANDROID_HOME/platforms/android-35"` | yes |
| 4 | device | Android device OR emulator (API 26-35) via ADB | `adb devices` shows ≥1 device with state `device` | yes (manual TCs only) |
| 5 | adb-connection | **USB cable — NOT Wireless** (memory-flag `user_dev_setup.md`: ADB Wireless is unstable for this user) | `adb shell ip route` succeeds 5× in 60 s without disconnect | yes (manual TCs) |
| 6 | apk-installed | Dictate APK from this worktree's `./gradlew assembleDebug` at the profile's commit (C6: post-C5 HEAD; C12: post-C12 HEAD) | `adb shell pm list packages \| grep net.devemperor.dictate` | yes (manual TCs) |
| 7 | ime-enabled | Dictate IME enabled in Settings → System → Languages & Input → On-screen keyboard | `adb shell ime list -a \| grep net.devemperor.dictate` shows the IME line | yes (manual TCs) |
| 8 | ime-selected | Dictate IME is the currently selected keyboard | `adb shell settings get secure default_input_method` outputs `net.devemperor.dictate/...` | yes (manual TCs) |
| 9 | mic-permission | `RECORD_AUDIO` granted | `adb shell dumpsys package net.devemperor.dictate \| grep RECORD_AUDIO` shows granted | yes (manual TCs) |
| 10 | notif-permission | Android 13+ `POST_NOTIFICATIONS` granted (FGS notification with action-buttons — Epic-new, R-2) | `adb shell dumpsys package net.devemperor.dictate \| grep POST_NOTIFICATIONS` shows granted (API ≥ 33) | yes (manual TCs on API ≥ 33) |
| 11 | target-app | An app with a text input field (default: pre-installed Notes / Messages / Keep) | `adb shell pm list packages -d` shows ≥1 keep/notes/messages app | yes (manual TCs) |
| 12 | logcat-baseline | `adb logcat -c` clears the buffer at Setup start | n/a — preparatory | no |
| 13 | api-key | ≥1 AI-provider API key configured in Dictate Settings (Whisper for transcription) | manual: open Dictate Settings → API Keys → ≥1 provider key set | yes (manual TCs) |
| 14 | network | Device has WLAN (recording → transcription API call) | `adb shell ping -c 3 api.openai.com` succeeds (or chosen provider) | yes (manual TCs) |
| 15 | overlay-permission | `SYSTEM_ALERT_WINDOW` for `net.devemperor.dictate` — TC-specific (grant + deny paths) | `adb shell appops get net.devemperor.dictate SYSTEM_ALERT_WINDOW` reports `allow` / `deny` per TC | no — TC-specific |
| 16 | parent-baseline | Parent plan's ≥946-test baseline green at Epic-start | `./gradlew test` green at HEAD `65bb303` (recorded in state-file Pre-Flight #5) | yes (AC-9 baseline anchor) |
| 17 | guard-state | For C6-SUBSET: `USE_LEGACY_RECORDING_DRIVE` is set to the **new** path (C5 default after the flip lands). For C12-FULL: the boolean + legacy call-site are **deleted** (C7 done). | `grep -rn "USE_LEGACY_RECORDING_DRIVE" app/src/main/` — C6: ≥1 hit, default-new; C12: zero hits | yes |
| 18 | room-no-migration | **NO Room schema change this Epic** — the v3→v4 migration already shipped + verified in the parent plan; this Epic adds no `@Database(version=…)` bump | `grep -rn "version = 5\|version=5" app/src/main/java/.../database/` returns zero (still v4) | yes (safety invariant — confirms blast-radius is code-only) |
| 19 | personal-device-consent | If a personal device (not a dedicated test-device): user confirmed "OK to run on my real phone — FGS notification will appear, recording-trigger is the new path". **Blast-radius is LOWER than the parent run** (no DB migration). | manual: see User Questions Q2 | yes (manual TCs) |

> **The parent runbook's #14 (`personal-device-consent` re DB-migration) and
> #15 (Room v3→v4 backup consent) are intentionally NOT carried.** This Epic
> adds no schema change (prereq #18 enforces it). Consent is still gated for
> "run on real phone" generally (#19), but the irreversible-data-loss vector
> the parent run had does not exist here.

## User Questions (resolved before Phase 4.5 / before C6 gate runs)

"ohne Walkthrough" mode is active — the orchestrator **defaults** these; this
list is for transparency, not a block. The **Recommended default** column is
authoritative under "ohne Walkthrough". The **Parent-answered** column says
whether the parent plan already defaulted the same question.

| # | Question | Default options | Recommended default | Parent-answered? |
|---|----------|-----------------|---------------------|------------------|
| Q1 | Which device for the manual TCs — personal phone, dedicated test-device, or emulator? | (1) Personal phone (with consent) · (2) Dedicated test-device · (3) Android Emulator API 35 · (4) Decide at run time | **(1) Personal phone** — blast-radius is code-only (no DB migration, prereq #18); same default as parent | **Yes** — parent Q1 defaulted "personal phone, ohne Walkthrough". Lower risk here. |
| Q2 | Personal phone consent: OK that the recording-trigger is the **new** path + a new FGS notification appears? (No DB migration this Epic.) | (1) Yes — proceed · (2) Backup app-data first (`adb pull /data/data/net.devemperor.dictate/`) · (3) Use emulator | **(1) Yes** — no irreversible DB op; the guarded fallback (`USE_LEGACY_RECORDING_DRIVE`) is the in-app safety net during C6-SUBSET | **Partially** — parent Q2 was DB-migration-consent (HIGHER risk). This Epic's Q2 is recording-path consent only — strictly lower blast-radius. |
| Q3 | ADB connection: USB-cable or Wireless? | (1) USB-cable (recommended per `user_dev_setup.md`) · (2) Wireless (accept-known-unstable) | **(1) USB-cable** — wireless drops invalidate logcat-correlations | **Yes** — parent Q3 defaulted USB-cable. Unchanged. |
| Q4 | AI-provider for the transcription happy-path? | (1) OpenAI Whisper · (2) Groq Whisper · (3) Use what's already configured | **(3) Use what's already configured** — the cutover does not change provider wiring; reuse the existing key | **Yes** — parent Q4 defaulted "use configured". Unchanged. |
| Q5 | Target app for typing in TC-1..TC-6? | (1) Google Keep / Notes · (2) Signal / WhatsApp · (3) Browser address bar · (4) All three | **(1) Google Keep / Notes** — single deterministic target; parent default | **Yes** — parent Q5 defaulted Keep/Notes. Unchanged. |
| Q6 | Cleanup after E2E — tear down, clear app-data, or leave installed? | (1) Tear down · (2) Clear app-data only · (3) Leave installed | **(3) Leave installed** (personal-phone scenario — parent default); C12-FULL teardown only archives logcat | **Yes** — parent Q6 defaulted "leave installed (personal)". Unchanged. |
| Q7 | Overlay-Permission: test both grant + deny paths? | (1) Both grant + deny · (2) Grant only · (3) Deny only | **(1) Both** — overlay is unchanged by the cutover but the parent runbook's TC-17/18/19/20 are inherited; full coverage is cheap on the C12-FULL run only (skipped in C6-SUBSET) | **Yes** — parent Q7 defaulted "both". Unchanged. |
| Q8 | **Epic-NEW.** OQ-2: should `USE_LEGACY_RECORDING_DRIVE` ship one dogfood release (default-on-legacy) before C7 deletes it, or be removed immediately after C6 green in the same Epic run? | (1) Remove immediately after C6 green (D7 — no lingering dead switch; per AC-10) · (2) Ship one dogfood release default-on-legacy first | **(1) Remove immediately after C6 green** — Epic §7 OQ-2 stated default; D7 (no dead switch); the C6 gate *is* the proof | **No — Epic-new.** Plan §7 OQ-2 owner = user; default documented as (1). Not a blocker (surfaces at B3/C7, mid-Epic, with documented fallback). |

> **Forwarded to orchestrator in stdout.** Under "ohne Walkthrough" the
> orchestrator applies the Recommended-default column. Q8 is the only
> Epic-new question (parent had Q1–Q7); it is non-blocking (Epic §7: "OQ-2
> surfaces at B3 mid-Epic with documented fallback").

## Test Cases

> **Profiles legend.** `C6-SUBSET` = runs in the in-plan D2-pre gate (chunk
> C6-D2pre). `C12-FULL` = runs in chunk C12-D2 (final integration gate) and
> the skill's post-all-blocks Phase 4.5. A TC with both runs in both;
> C6-SUBSET TCs re-run identically in C12-FULL (regression).

### Auto-tier (headless — grep + gradle invariants)

### TC-A1: AC-1 stub-subsystem dereference invariant

- **Mode:** auto
- **Profiles:** C12-FULL  *(C6-SUBSET: skip — stubs are still demoted-not-deleted until B1/B2; AC-1 only holds post-cutover)*
- **Knowledge:** test-orchestrator (grep)
- **Scope:** AC-1 — the two no-op stubs are no longer wired into the
  composition root.
- **Steps:**
  1. `grep -rn "StubSubsystems.pipelineRunner\|StubSubsystems.notificationCoordinator" app/src/main/java/net/devemperor/dictate/core/`
  2. Assert: zero hits (the parent baseline had them at
     `DictatePipelineService.kt:419` + `:421`).
  3. `grep -n "PipelineRunnerSubsystemAdapter\|PipelineNotificationCoordinator(" app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt`
  4. Assert: ≥1 hit each (the real adapters are wired in onCreate Step 4).
- **Expected Result:** Zero stub references in `core/`; real adapters wired.

### TC-A2: AC-5 LanguageController deletion invariant (D-13)

- **Mode:** auto
- **Profiles:** C12-FULL
- **Knowledge:** test-orchestrator (grep)
- **Scope:** AC-5 — `LanguageController.kt` deleted, no caller remains.
- **Steps:**
  1. `test ! -f app/src/main/java/net/devemperor/dictate/core/LanguageController.kt` — assert file gone.
  2. `grep -rl "LanguageController" app/src/main/` — assert zero hits.
  3. `grep -rl "LanguageController" app/src/test/ app/src/androidTest/` — assert zero (test deleted too).
- **Expected Result:** No `LanguageController` symbol anywhere in source/tests.

### TC-A3: AC-6 audioFile-field deletion invariant (D-14)

- **Mode:** auto
- **Profiles:** C12-FULL
- **Knowledge:** test-orchestrator (grep)
- **Scope:** AC-6 — the IME `audioFile` field is deleted (baseline:
  `DictateInputMethodService.java:222`).
- **Steps:**
  1. `grep -n "private File audioFile" app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` — assert zero.
  2. `grep -n "this.audioFile\|\baudioFile\b" app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` — assert remaining hits are method-parameters (e.g. `onAudioPersisted(File audioFile, …)` at the historic `:2379`), not field reads.
- **Expected Result:** No field declaration; the ~9 historic reads are sourced from `state.recording` / the file-factory.

### TC-A4: AC-7 dead-controller deletion invariant + AC-9 regression

- **Mode:** auto
- **Profiles:** C12-FULL
- **Knowledge:** test-orchestrator (grep + gradle)
- **Scope:** AC-7 (dead controllers deleted, Spec 1 §9.6 cleanup-grep) +
  AC-9 (≥946-test regression + assembleDebug).
- **Steps:**
  1. For each of `MainButtonsController.kt`, `RecordingUiController.kt`, `KeyboardUiController.kt`, `KeyboardStateManager.kt`: `test ! -f app/src/main/java/net/devemperor/dictate/core/<name>` — assert gone.
  2. `grep -rl "MainButtonsController\|RecordingUiController\|KeyboardUiController\|KeyboardStateManager" app/src/main/` — assert zero.
  3. `test -f app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt` — assert **present** (Spec 1 §9.6: never deleted — survives as the `PipelineRunnerSubsystem` adaptee).
  4. `grep -rl "PipelineOrchestrator" app/src/main/` — assert the only non-self caller is the B1 adapter (`PipelineRunnerSubsystemAdapter.kt`).
  5. `./gradlew test` — assert green, test-count ≥ 946 (parent baseline; no net behaviour-coverage deletion).
  6. `./gradlew assembleDebug` — assert green.
- **Expected Result:** All 4 controllers gone, `PipelineOrchestrator` retained behind the adapter, full suite ≥946 green, debug APK builds.

### Survival group — the central goal, now on the LIVE path

### TC-1: Keyboard-switch survival on the NEW orchestrator path

- **Mode:** manual
- **Profiles:** C6-SUBSET, C12-FULL  *(keystone — the gate TC)*
- **Knowledge:** hand-rolled (adb + logcat)
- **Scope:** AC-2/AC-3 + ADR-0003. Recording survives Tastatur-Wechsel **driven
  by the new `DictateOrchestrator`**, not the dormant path. R-1/R-2 live check.
- **Steps:**
  1. Confirm prereq #17: `grep -n "USE_LEGACY_RECORDING_DRIVE" app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` — value resolves to the **new** path (C6-SUBSET) or boolean is absent (C12-FULL, post-C7).
  2. `adb logcat -c`. Open Notes, focus text field, Dictate IME selected.
  3. Tap record. Verify: waveform animation; **persistent FGS notification appears showing the `[Pause][Stopp][Senden]` action buttons** (the real `PipelineNotificationCoordinator`, not the legacy notification).
  4. `adb logcat -d | grep -E "(DictateOrchestrator|RecordingModule|PipelineRunnerSubsystem|PipelineNotificationCoordinator)"` — verify the **new** path drove it: `state.recording Idle→Preparing→Active`, NO `Log.w(.., "pipelineRunner.submit")` / `Log.w(.., "notificationCoordinator.show")` stub lines.
  5. Switch to Gboard via the system keyboard-switcher. Wait 30 s. Verify: FGS notification still says recording, action-buttons still present.
  6. Switch back to Dictate. Verify: keyboard shows recording still active (same session, record-button = "stop").
  7. Tap stop-and-send. Verify: notification transitions `Recording → Pipeline → Idle`; transcription text appears in Notes; notification dismissed after insertion (`stopSelf()`).
  8. `adb logcat -d | grep -E "(FATAL|AndroidRuntime|NPE|IllegalArgumentException.*startForeground)"` — assert none.
- **Expected Result:** Recording survives the 30-s round-trip on the new path; one continuous utterance transcribed; FGS never crashed; zero stub `Log.w` lines.

### TC-2: Stop-and-send drives the new pipeline with real sessionId (F-10 / AC-3)

- **Mode:** manual
- **Profiles:** C6-SUBSET, C12-FULL
- **Knowledge:** hand-rolled
- **Scope:** AC-3 + F-10 — `StopRecordingAndSend` carries a real sessionId
  (no empty-string sentinel); pipeline runs via the JobExecutor-backed
  `PipelineRunnerSubsystem`.
- **Steps:**
  1. `adb logcat -c`. Record "session id test" in Notes.
  2. Tap stop-and-send.
  3. `adb logcat -d | grep -E "StopRecordingAndSend|sessionId"` — verify the sessionId logged at `StartRecording` equals the one on `StopRecordingAndSend` (continuity; not `""`).
  4. Verify transcription "session id test" inserts.
- **Expected Result:** Same non-empty sessionId start→stop; pipeline completes via the new runner; text inserted.

### TC-3: Process-survival — running session visible after IME-View-restart

- **Mode:** manual
- **Profiles:** C12-FULL  *(inherited from parent TC-3)*
- **Knowledge:** hand-rolled
- **Scope:** `DictateUiStateStore` re-subscribed after IME-View dies + recreates,
  on the new path.
- **Steps:**
  1. Tap record. Recording starts (new path, notification visible).
  2. Force-close target-app (swipe from recents).
  3. Re-open target app, focus text field — Dictate IME loads.
  4. Verify: keyboard immediately shows recording-active (record-button=stop, waveform).
  5. Tap stop. Transcription completes.
- **Expected Result:** State restored from the store, not reset. Spec 1 §15.

### Cutover-specific group — Epic-new (the live flip + guard)

### TC-C1: Guarded-fallback mutual-exclusion — no double-dispatch (R-4 / AC-10)

- **Mode:** manual + logcat-scan
- **Profiles:** C6-SUBSET, C12-FULL  *(C12-FULL variant: the boolean is gone — assert single path structurally)*
- **Knowledge:** hand-rolled (logcat + grep)
- **Scope:** AC-10 / R-4 — while the guarded fallback exists (C6-SUBSET) no
  user action routes to *both* `JobExecutor.INSTANCE.start` and the new
  `dispatch`. Post-C7 (C12-FULL): the legacy call-site is structurally gone.
- **Steps:**
  1. **C6-SUBSET:** `grep -n "JobExecutor.INSTANCE.start\|pipelineBinder.dispatch(.*RecordingAction" app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` — enumerate every recording-trigger call-site. NOTE: the baseline has **3** `JobExecutor.INSTANCE.start` sites (`:2236` primary, `:2897`, `:3053` — the standalone-prompt + a third path); the AC-10 audit must cover all 3, not only `:2236`.
  2. Verify each `JobExecutor.start` site is mutually-exclusive with the new dispatch via the `USE_LEGACY_RECORDING_DRIVE` boolean (if/else, never both arms reachable in one action).
  3. `adb logcat -c`. Trigger record→stop-and-send once.
  4. `adb logcat -d | grep -E "(JobExecutor.*start|RecordingAction.StartRecording)"` — assert exactly ONE driver fired for the single user action (the new dispatch under C6-SUBSET default-new), NOT both.
  5. Repeat for the standalone-prompt path (long-press record → file pick) and the resend path — each must single-dispatch.
  6. **C12-FULL:** `grep -rn "JobExecutor.INSTANCE.start" app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` — assert the IME no longer calls it directly (only the B1 adapter does); `grep -rn "USE_LEGACY_RECORDING_DRIVE" app/src/main/` — zero.
- **Expected Result:** Exactly one recording driver per user action across all 3 trigger sites; post-C7 the legacy IME call-site is structurally deleted.

### TC-C2: Guarded-fallback rollback works (R-1 mitigation proof)

- **Mode:** manual
- **Profiles:** C6-SUBSET  *(only meaningful while the boolean exists)*
- **Knowledge:** hand-rolled
- **Scope:** §6.2 — the legacy path is the safety net; flipping
  `USE_LEGACY_RECORDING_DRIVE` to legacy restores the dormant-but-working state
  with no revert.
- **Steps:**
  1. Build a variant APK with `USE_LEGACY_RECORDING_DRIVE` forced to **legacy** (or toggle the debug-build hook if one exists; otherwise document this as a code-inspection check that the legacy arm is reachable + complete).
  2. Record→stop-and-send. Verify: recording + transcription still works via the legacy `JobExecutor.start` path (the safety net is intact).
  3. `adb logcat -d | grep -E "(JobExecutor|PipelineOrchestrator)"` — verify legacy path drove it; new orchestrator only handled state (non-pipeline), no double-drive.
- **Expected Result:** Legacy fallback is fully functional — rollback is one boolean away (proves R-1 mitigation before C7 deletes it).

### TC-C3: Full JobRequest config survives the adapter translation (R-1 / AC-2)

- **Mode:** manual
- **Profiles:** C6-SUBSET, C12-FULL
- **Knowledge:** hand-rolled (logcat + behaviour-observe)
- **Scope:** R-1 — the B1 adapter must reproduce the IME's intricate
  `JobRequest.TranscriptionPipeline` construction (language, prompt-queue,
  style-prompt, livePrompt, autoSwitch, recordingsDir, origin). A dropped
  field silently mis-transcribes.
- **Steps:**
  1. In Settings: set transcription language to a non-default (e.g. German), enable a style-prompt, queue a rewording prompt, enable auto-switch-back-keyboard.
  2. `adb logcat -c`. Record a German phrase via the new path.
  3. `adb logcat -d | grep -E "(JobRequest|TranscriptionPipeline|language|prompt|autoSwitch)"` — verify the JobRequest the adapter built carries: the German language, the style-prompt, the queued prompt, autoSwitch=true, the correct recordingsDir.
  4. Verify behaviourally: transcription is in German (not auto-translated), the queued reword applied, keyboard auto-switched back after completion.
- **Expected Result:** Every JobRequest field the legacy IME path threaded is present on the new path; no silent config loss. (Code-level field-by-field spy assert is the B1 Robolectric acceptance — this is the on-device behaviour confirmation.)

### TC-C4: FGS notification action-button round-trip (AC-2 / R-2 / B2)

- **Mode:** manual
- **Profiles:** C6-SUBSET, C12-FULL
- **Knowledge:** hand-rolled (Spec 1 §7.4/§7.5/§7.6)
- **Scope:** AC-2 — the real `PipelineNotificationCoordinator` posts the FGS
  notification with `[Pause][Stopp][Senden]`; the buttons dispatch back through
  `PipelineActionRouter`. NOTIF_ID single-source (Spec 1 §10 Phase-B-S-5).
- **Steps:**
  1. Tap record. Pull down the notification shade.
  2. Verify: persistent notification with exactly 3 action-buttons labelled per the OQ-3 strings (`[Pause][Stopp][Senden]` / de/en locale).
  3. Tap **Pause** from the notification. Verify: recording pauses (waveform freezes; state reflects pause); notification updates.
  4. Tap resume (Pause toggles or a resume affordance). Verify: recording continues.
  5. Tap **Senden** from the notification. Verify: stop-and-send fires via `PipelineActionRouter` → `orchestrator.dispatch`; notification transitions `Recording → Pipeline → Idle`; transcription inserts; notification dismissed.
  6. Repeat, using **Stopp** instead of Senden. Verify: recording cancelled, no transcription, notification dismissed.
  7. `adb logcat -d | grep -E "(PipelineActionRouter|NOTIF_ID|startForeground)"` — verify single NOTIF_ID, no `startForeground` IllegalArgumentException.
- **Expected Result:** All 3 action-buttons round-trip through the new coordinator; FGS never crashes; notification lifecycle `Recording→Pipeline→Idle` correct.

### TC-C5: OQ-3 notification strings present + localised

- **Mode:** auto (grep) + manual (locale visual)
- **Profiles:** C12-FULL
- **Knowledge:** test-orchestrator (grep)
- **Scope:** OQ-3 — B2/C4 must add `[Pause][Stopp][Senden]` notification-action
  strings (the baseline `values/strings.xml` has `dictate_history_pause` but no
  dedicated pipeline-notification-action strings — confirmed at Phase 1b).
- **Steps:**
  1. `grep -n "notification.*pause\|notification.*stop\|notification.*send\|pipeline.*action" app/src/main/res/values/strings.xml` (and de/es/pt locale files) — assert the 3 action strings exist in `values/` and every shipped locale (mirror F-5's locale-file discipline).
  2. Manual: switch device language to German, start recording, verify the notification buttons render German labels.
- **Expected Result:** Action strings exist + localised; no hard-coded English in the notification.

### Triangle-FSM group (T1–T7) — re-traced on the LIVE path

> Inherited verbatim-equivalent from parent runbook TC-4..TC-10. The Epic
> difference: these run **after the recording-drive flip**, so the FSM
> transitions that depend on a live pipeline (T3/T4/T7) now exercise the new
> `PipelineModule.runEffect` → real runner, not the dormant stub.

### TC-4: T1 KEYBOARD → WIDGET

- **Mode:** manual
- **Profiles:** C6-SUBSET, C12-FULL
- **Knowledge:** hand-rolled
- **Scope:** §3.1 T1 + Spec 3 §7.3 T1 (FSM unchanged by cutover — regression).
- **Steps:**
  1. KEYBOARD mode visible. Tap Widget-toggle.
  2. Verify: WIDGET overlay (5 buttons: Record, Send, Pause, Trash, Close); keyboard collapses per spec.
  3. Tap into target text field — InputConnection still works.
- **Expected Result:** Smooth transition, 5 WIDGET buttons functional, no flicker.

### TC-5: T2 WIDGET → KEYBOARD with SmallMode

- **Mode:** manual
- **Profiles:** C12-FULL
- **Scope:** §3.1 T2 + parent OPEN-1 (SmallMode after WIDGET-close).
- **Steps:**
  1. From WIDGET (TC-4). Tap Close.
  2. Verify: keyboard re-appears in SmallMode (single-row). Type a char — works.
- **Expected Result:** SmallMode is the post-WIDGET-close state.

### TC-6: T3 KEYBOARD → HOVER — IME-View hidden + pipeline active (LIVE)

- **Mode:** manual
- **Profiles:** C6-SUBSET, C12-FULL
- **Scope:** §3.1 T3 — now with the **real** pipeline active (new runner).
- **Steps:**
  1. Recording active on the new path (TC-1 steps 2-4).
  2. Tap outside the text field to dismiss the keyboard.
  3. Verify: HOVER overlay (4 buttons: Send DISABLED, Pause, Trash, Close).
  4. `adb logcat -d | grep -E "ViewMode|RecordingModule"` shows KEYBOARD→HOVER while the new pipeline is genuinely running.
- **Expected Result:** Auto-HOVER on IME-hide + real-pipeline-active.

### TC-7: T4 WIDGET → HOVER — pipeline active + was WIDGET

- **Mode:** manual
- **Profiles:** C12-FULL
- **Scope:** §3.1 T4 + WIDGET-persistence-bit (parent Spec 3 §7.3 T4).
- **Steps:**
  1. WIDGET-mode (TC-4) with recording active on the new path.
  2. Tap outside / momentarily close the focused app.
  3. Verify: WIDGET stays (HOVER inherits from WIDGET only on full IME-View-hide).
  4. Re-focus the app field → WIDGET returns (T6).
- **Expected Result:** WIDGET persists across temporary IME-hides; spec-compliant.

### TC-8: T5 HOVER → KEYBOARD — IME returns, was NOT WIDGET

- **Mode:** manual
- **Profiles:** C12-FULL
- **Scope:** §3.1 T5.
- **Steps:**
  1. HOVER per TC-6. Re-focus text field — IME re-loads.
  2. Verify: KEYBOARD mode resumes (not WIDGET).
- **Expected Result:** Was-not-WIDGET persists across HOVER.

### TC-9: T6 HOVER → WIDGET — IME returns + was WIDGET

- **Mode:** manual
- **Profiles:** C12-FULL
- **Scope:** §3.1 T6.
- **Steps:**
  1. HOVER from WIDGET (TC-7 step 1-2, full IME-hide). Re-focus text field.
  2. Verify: returns to WIDGET-mode (persistence-bit, Spec 3 §7.3 T6).
- **Expected Result:** WIDGET-persistence preserved through HOVER round-trip.

### TC-10: T7 HOVER → KEYBOARD via Pipeline-Done-Cascade (Geist-Widget-Bug)

- **Mode:** manual
- **Profiles:** C6-SUBSET, C12-FULL  *(Critical — the parent plan's keystone bug-elimination, re-proven on the LIVE pipeline)*
- **Scope:** §3.1 T7 + parent KG-RSB-2 + Spec 3 §7.3 T7. Regression-test for
  Geist-Widget-Bug — now the Pipeline-Done cascade is driven by the **real**
  pipeline completing via the new runner, not the stub.
- **Steps:**
  1. Tap Widget-toggle (KEYBOARD → WIDGET).
  2. Start recording from WIDGET (new path).
  3. Dismiss IME (WIDGET → HOVER via T4-then-T5 path).
  4. Wait for the **real** transcription to complete (the new `PipelineModule.runEffect` finishes; `ResetSuppressBit` cascade fires).
  5. Re-focus text-field.
  6. Verify: returns to **KEYBOARD** mode (not WIDGET) — the cascade dropped the WIDGET-persistence-bit.
  7. Verify: transcription text inserts.
- **Expected Result:** T7 structural fix holds on the live path: HOVER→KEYBOARD (not →WIDGET) after the real Pipeline-Done. Geist-Widget-Bug cannot reappear.

### TC-11: Keystone F-1/F-2/F-3 IME-activation chain on the live path

- **Mode:** manual + logcat-scan
- **Profiles:** C6-SUBSET, C12-FULL  *(keystone re-trace — Epic AC-9)*
- **Knowledge:** hand-rolled (`research/b5-ime-activation-wiring.md` §3, ADR-0005)
- **Scope:** AC-9 — the parent plan's keystone F-1/F-2/F-3 IME-activation
  chain still wired end-to-end **after** the recording-drive flip.
- **Steps:**
  1. `adb logcat -c`. Cold-start: force-stop `net.devemperor.dictate`, focus a text field to load the IME fresh.
  2. `adb logcat -d | grep -E "(DictatePipelineService|onCreate|pipelineBinder|DictateOrchestrator|ViewMode)"` — verify the activation chain fires in order: service onCreate → binder bound → orchestrator initialised → ViewMode KEYBOARD rendered (the F-1/F-2/F-3 trace per ADR-0005 Decision-History).
  3. Tap record (new path). Verify the activation chain integrates with the live recording-drive (no `pipelineBinder == null` race, no double-init).
- **Expected Result:** Keystone chain trace identical to the parent plan's green trace; the recording-drive flip did not regress IME-activation.

### Visibility-Predicate group — parent Bug-Class §2.3 (regression)

### TC-12: resend_btn visibility — predicate-driven, no race

- **Mode:** manual
- **Profiles:** C12-FULL
- **Scope:** parent §2.3 — `resend_btn` predicate-driven; verify the cutover
  (esp. C2 audioFile-field removal, R-5) did not break resend visibility.
- **Steps:**
  1. Default: resend_btn NOT visible. Send a transcription → resend_btn visible after success.
  2. Switch keyboard out + back → resend_btn visibility preserved.
  3. Open Widget-mode (T1) → resend_btn correct per Spec 2 §14.2.
  4. Trigger reword → resend_btn behaviour per Spec 2 §14.2.
- **Expected Result:** resend_btn tracks the state predicate, never stuck across mode transitions or across the audioFile-field removal.

### TC-13: Resend + audio-migration still work after audioFile-field removal (R-5)

- **Mode:** manual
- **Profiles:** C12-FULL  *(Epic-specific — guards C2/D-14 risk R-5)*
- **Knowledge:** hand-rolled
- **Scope:** R-5 — C2 deletes `audioFile`; several reads were non-recording
  (legacy-migration, resend, `Pref.LastFileName`). A wrong source silently
  breaks resend/migration.
- **Steps:**
  1. Record + send a transcription, then trigger **resend** (resend_btn). Verify: the previous audio re-sends + re-transcribes correctly (the resend path reads the right file source post-field-removal).
  2. Abort a recording mid-way (creates an aborted file). Trigger resend. Verify: the aborted file re-sends (the `Pref.LastFileName` semantics still hold).
  3. `adb shell run-as net.devemperor.dictate ls cache/` — verify the audio scratch files are produced + cleaned per the cleanup-cascade (no orphan growth).
- **Expected Result:** Resend (both success-path and aborted-path) + audio cleanup intact after the field removal; no silent breakage.

### TC-14: Settings language-change propagates after LanguageController removal (R-3)

- **Mode:** manual
- **Profiles:** C12-FULL  *(Epic-specific — guards C1/D-13 risk R-3)*
- **Knowledge:** hand-rolled
- **Scope:** R-3 + AC-5 — `DictateApplication` singleton no longer holds a
  `LanguageController`; the unbound path routes through `DictatePrefs`, the
  bound path through `LanguageModule`. A naive removal NPEs / uses stale
  `"system"` language.
- **Steps:**
  1. Open Dictate Settings (Application-singleton context, may run before the service binds). Change the transcription language (e.g. English → German).
  2. Without restarting the app: go to a target app, record a German phrase via the new path.
  3. Verify: the transcription uses German (the new effective-language propagated through `LanguageModule`/`DictatePrefs`, F-15).
  4. `adb logcat -d | grep -E "(LanguageModule|NPE|NullPointerException)"` — assert no NPE; the language change reached the next transcription.
  5. Cold-path check: force-stop, change language in Settings *before* focusing any text field (service unbound), then record — verify the unbound path still resolves the new language via `DictatePrefs` (not stale/`"system"`).
- **Expected Result:** Language change propagates to the next transcription on both the bound and unbound paths; no NPE; no stale-language regression.

### Persistence + Recovery group (regression — no schema change this Epic)

### TC-15: OOM-Recovery → User-Resume from DB (on the new path)

- **Mode:** manual
- **Profiles:** C12-FULL
- **Knowledge:** hand-rolled (Spec 1 §11.6 + §4.6 PipelineRecovery)
- **Scope:** parent §2.3 Stale-Running-Session elimination — still works after
  the recording-drive flip. **No DB-migration this Epic** (prereq #18) — this
  is a behaviour-regression check, not a migration test.
- **Steps:**
  1. Start recording on the new path. Wait ~5 s (DB-persist fires).
  2. `adb shell am force-stop net.devemperor.dictate`.
  3. Re-open Dictate-IME by focusing a text field.
  4. Verify: notification / IME shows "Aufnahme unterbrochen — Wiederherstellen?" (Spec 1 §11.6).
  5. Tap resume — the RUNNING session is presented; recording does NOT auto-restart.
  6. Tap stop/cancel → DB updates to CANCELLED.
- **Expected Result:** Stale-RUNNING-session recoverable on the new path, no zombie state. (No migration assertion — schema is still v4.)

### TC-16: Cleanup orphan FAILED audio files

- **Mode:** manual
- **Profiles:** C12-FULL
- **Scope:** Spec 1 §6.3.1 orphan-cleanup — regression (cleanup-cascade
  already landed in parent; verify the cutover did not break it).
- **Steps:**
  1. Cancel a session mid-recording (creates FAILED with audio-file).
  2. Wait 60+ s (cutoff per Spec 1 §4.11.10).
  3. Force-stop + relaunch the IME (triggers service-onCreate).
  4. `adb shell run-as net.devemperor.dictate ls cache/` — orphan audio gone; DB row marked cleaned.
- **Expected Result:** Orphan-cleanup runs at service-onCreate; no stale audio.

### Overlay-Permission group — inherited regression (overlay unchanged by cutover)

### TC-17: First-time Overlay-Permission ask flow

- **Mode:** manual
- **Profiles:** C12-FULL
- **Scope:** Spec 3 §5 — overlay onboarding unchanged by the cutover; regression.
- **Steps:**
  1. `adb shell pm clear net.devemperor.dictate` (reset permission state).
  2. Open Dictate IME, focus text field. Tap Widget-toggle (first time).
  3. Verify: Onboarding-UI explains the permission (Spec 3 §5.3). Tap Grant.
  4. System → Settings → "Display over other apps" → toggle ON. Return.
  5. Verify: WIDGET overlay now appears.
- **Expected Result:** First-time flow informative; grant triggers immediate WIDGET-render.

### TC-18: Permission denied → Notification fallback

- **Mode:** manual
- **Profiles:** C12-FULL
- **Scope:** Spec 3 §5.6 + §9 — Notification-Fallback when overlay denied.
- **Steps:**
  1. Fresh state. Open Dictate IME. Tap Widget-toggle.
  2. Deny `SYSTEM_ALERT_WINDOW` (or close Settings without granting).
  3. Verify: Notification appears with fallback controls (Pause / Trash / Send).
  4. Pull-down notification — verify pause/cancel work from it.
- **Expected Result:** Denial does not strand the user — fallback functional.

### TC-19: Overlay drag + position persistence

- **Mode:** manual
- **Profiles:** C12-FULL
- **Scope:** Spec 3 §4.6 + §11.5 — portrait/landscape normalised positions.
- **Steps:**
  1. Open WIDGET (permission granted). Drag to a new portrait position.
  2. Force-close + reopen IME, re-enter WIDGET. Verify: dragged position restored.
  3. Rotate to landscape. Drag to a different landscape position.
  4. Rotate back to portrait. Verify: portrait position restored (independent).
- **Expected Result:** Two independent position-stores; rotation round-trip preserves both.

### TC-20: Overlay survives orientation change while recording (new path)

- **Mode:** manual
- **Profiles:** C12-FULL
- **Scope:** WindowManager LayoutParams resilience — recording is now the new path.
- **Steps:**
  1. Start recording in WIDGET-mode (new path). Rotate device.
  2. Verify: WIDGET re-positions per landscape coords, recording continues (new runner).
  3. Rotate back. Verify: still recording, WIDGET back to portrait position.
- **Expected Result:** No flash, no recording-interrupt across rotation.

### MotionLayout + Cascade group — inherited regression

### TC-21: MotionLayout transitions — no visual flicker

- **Mode:** manual
- **Profiles:** C12-FULL
- **Scope:** Spec 2 §7 + §11.4 — render path is now solely RenderBackend
  (C3 deleted the legacy controllers); verify the sole path renders cleanly.
- **Steps:**
  1. Open IME — no >500 ms blank keyboard.
  2. Trigger Two-Row → Single-Row, Default → Send-Mode, Send-Mode → ReprocessStaging.
  3. Verify: smooth animated transitions, no constraint-set jump.
  4. `adb logcat -d | grep -E "(MotionLayout|onCreateInputView)"` — inflation < 50 ms (Spec 2 §11.4).
- **Expected Result:** All KEYBOARD-sub-mode transitions smooth; sole RenderBackend path renders correctly post-controller-deletion.

### TC-22: F-13 Running counters render real progress (A1 / AC-4)

- **Mode:** manual
- **Profiles:** C6-SUBSET, C12-FULL  *(Epic-specific — A1 F-13 + the notification/record-button progress consumer)*
- **Knowledge:** hand-rolled
- **Scope:** AC-4 — `PipelineUiState.Running.completedSteps/totalSteps/elapsedMs`
  (F-13) render real values, not the B4 placeholders, in the record-button text
  + the FGS notification progress.
- **Steps:**
  1. Record a phrase that triggers a multi-step pipeline (transcribe → reword).
  2. During processing, observe the record-button text and the FGS notification.
  3. Verify: a real progress indication ("step 2/3" / elapsed time), not a static placeholder.
- **Expected Result:** Live progress counters reflect actual pipeline steps + elapsed time (F-13 wired through to UI + notification).

### TC-23: SendStaging double-click guard (F-12 / AC-4)

- **Mode:** manual
- **Profiles:** C12-FULL
- **Scope:** AC-4 — `PipelineUiState.ReprocessStaging.isStarting` (F-12)
  prevents a double SendStaging dispatch on rapid double-tap.
- **Steps:**
  1. Reach ReprocessStaging (reword-staging state).
  2. Double-tap the Send/confirm affordance rapidly (within ~200 ms).
  3. `adb logcat -d | grep -E "SendStaging|isStarting"` — verify the second tap is a no-op (`isStarting` guard); only one pipeline submission.
- **Expected Result:** Exactly one SendStaging submission despite the double-tap; no duplicate pipeline.

### TC-24: Cross-Module-Cascade depth + Mode-3 prohibition (regression)

- **Mode:** manual + logcat-scan
- **Profiles:** C12-FULL
- **Scope:** Spec 1 §15.1 — cascades still within MAX_CASCADE_DEPTH (ADR-0002)
  after the recording-drive flip mixed new Effects into the pipeline path.
- **Steps:**
  1. Trigger a known Mode-1 cascade (recording-active → record-button-disabled elsewhere).
  2. `adb logcat -d | grep -E "CASCADE_DEPTH|cascade-depth-exceeded"` — depth ≤ 8 (ADR-0002), no exceeded exception, no Mode-3 violation warning.
- **Expected Result:** Cascades within depth limit; no Mode-3 violation introduced by the cutover Effects.

## Periodic Visits (run at Setup, after every TC-group, at Teardown)

| Visit | Command | Pass-criterion |
|-------|---------|----------------|
| logcat-error-scan | `adb logcat -d -t 5m \| grep -E "(FATAL\|AndroidRuntime\|ANR\|NPE\|net.devemperor)"` | No NPE, FATAL, ANR for `net.devemperor.dictate` |
| stub-leak-scan | `adb logcat -d \| grep -E "(pipelineRunner\.\|notificationCoordinator\.).*PipelineServiceStubSubsystems"` | **Zero** stub `Log.w` lines — proves the new path drove it, not the dormant stub (Epic-new, AC-1 runtime check) |
| fgs-crash-scan | `adb logcat -d \| grep -E "startForeground.*IllegalArgumentException\|RemoteServiceException"` | Zero — the new `PipelineNotificationCoordinator` never crashes the FGS (R-2) |
| double-dispatch-scan | `adb logcat -d \| grep -E "JobExecutor.*start" \| wc -l` vs `... "RecordingAction.StartRecording" \| wc -l` | Per single user action, exactly one driver fired (R-4 / AC-10) |
| memory-profile | `adb shell dumpsys meminfo net.devemperor.dictate \| head -50` | TotalPss < 80 MB sustained; no monotonic growth over a 10-min session |
| fgs-notification-presence | manual swipe to view notifications | Persistent FGS notification with `[Pause][Stopp][Senden]` visible while recording, dismissed when stopped |
| db-version-sanity | `adb shell run-as net.devemperor.dictate sqlite3 databases/dictate-database 'SELECT version FROM room_master_table'` | Version == **4** (unchanged — Epic adds no migration, prereq #18) |

## Teardown

| Step | Action | Notes |
|------|--------|-------|
| 1 | Stop any active recording | Tap stop in Dictate |
| 2 | Verify all sessions in DB terminal | Per Periodic-Visit |
| 3 | Per User-Question Q6 — default **(3) Leave installed** (personal-phone) | (1) `adb uninstall …` · (2) `adb shell pm clear …` · (3) leave |
| 4 | C12-FULL only: archive logcat | `adb logcat -d > reports/phase-4.5-logcat-$(date +%Y%m%d).log` |
| 5 | Save device-info | `adb shell getprop ro.build.version.release ro.product.manufacturer ro.product.model` |

## Acceptance

**C6-SUBSET pass-bar (authorises C7 + Theme C):**
- TC-1, TC-2, TC-3(skip in subset), TC-C1, TC-C2, TC-C3, TC-C4, TC-6, TC-10,
  TC-11, TC-22 all PASS on the new path with `USE_LEGACY_RECORDING_DRIVE`
  default-new.
- Periodic Visits `stub-leak-scan`, `fgs-crash-scan`, `double-dispatch-scan`
  all green.
- The guarded-fallback (TC-C2) proven functional (rollback is one boolean away).
- → On full pass: C6 (D2-pre gate) is **green** → C7 legacy-deletion + Theme C
  (C8/C9/C10) are **authorised**. On any fail: gate **blocks**; orchestrator
  runs the repair-sub-phase; C7/Theme-C stay gated.

**C12-FULL / Phase-4.5 pass-bar (final):**
- All auto-tier TC-A1..TC-A4 PASS (AC-1/5/6/7/9 grep + ≥946-test regression +
  assembleDebug).
- All manual TC-1..TC-24 PASS (user-confirmed).
- All Periodic-Visit criteria green.
- The parent plan's keystone F-1/F-2/F-3 trace (TC-11) green on the new path.
- No regression vs the ≥946-test baseline (TC-A4 step 5).

## Failure Routing

On TC failure: orchestrator runs the repair-sub-phase (research →
research-file → resume-fix → re-test), analogous to block-closeout.
- **C6-SUBSET failure:** the D2-pre gate does NOT pass — C7 + Theme C remain
  gated. The guarded fallback (`USE_LEGACY_RECORDING_DRIVE`) means the app is
  still shippable on legacy while the new path is repaired. Up to 3 outer
  iterations; then `AskUserQuestion` (1) keep gate closed + open follow-up
  issues, (2) revert the recording-drive wave-commits, (3) escalate.
- **C12-FULL failure:** Theme C already deleted legacy — a regression is
  **fixed forward** (the new path is proven by C6 at that point), not rolled
  back to legacy (§6.2: "Themes C are the point of no return — fixed forward").
  Up to 3 outer iterations; then `AskUserQuestion` escalation.

## Phase-4.5 Refresh (added by orchestrator before execution)

Before the post-all-blocks Phase 4.5 runs, the orchestrator gegen-checks this
runbook against the last block outputs (B1–B4 reports) and may add TCs for
"edge-of-the-blade" points that emerged during implementation (e.g. a new
failure mode surfaced in a B2 recording-drive repair-wave, or an AC-10
double-dispatch site found beyond the 3 known `JobExecutor.start` call-sites).
Placeholder section.

---

**Phase-4.5 Refresh — added by the `E2E` agent (2026-05-17), gegen-checked
against all 5 block reports (B1/B2/B3/B5/B6) + `reports/integration-check.md`.**

Six "edge-of-the-blade" points emerged during implementation that the
human device-tier runner must exercise on the device (the auto-tier
surrogate is named per-TC). They are appended as device-tier TCs (the
auto-surrogate already ran green in the in-plan gates / INTEGRATION-W1).

### TC-R1: BT-SCO already-connected does NOT hang recording (B2-VAL-W1 F-1, was Critical)

- **Mode:** manual
- **Profiles:** C12-FULL  *(regression — Critical fix, blocks-following-chunks at the time)*
- **Knowledge:** hand-rolled (Spec 1 §15.2/§15.3)
- **Scope:** B2-VAL-W1 F-1 — when the BT headset SCO link is **already
  connected** before recording starts, the old logic could wait forever
  for a `Connected` edge that never re-fires. Fix: prime
  `bluetoothSco = Waiting` on BT-mic start so `Waiting → Connected`
  becomes a real edge (stale-resolve-after-cancel still defeated, no
  Mode-3).
- **Steps:**
  1. Pair + connect a Bluetooth headset/earbuds. Confirm SCO/calls route
     to it (place + end a quick call, or play audio so the SCO link is
     warm/connected).
  2. In Dictate Settings enable "Use Bluetooth microphone".
  3. `adb logcat -c`. Open Notes, tap record **while the BT device is
     already connected**.
  4. Verify: recording **starts within ~2.5 s** (does NOT hang on
     "preparing"); the FGS notification appears; audio captures via the
     BT mic.
  5. `adb logcat -d | grep -E "(ScoRouteResolved|bluetoothSco|Waiting|Connected|AllocateMediaRecorder)"`
     — verify `Waiting → Connected` fired and `AllocateMediaRecorder`
     was deferred until SCO resolved (not an indefinite wait).
  6. Stop-and-send → transcription completes.
- **Expected Result:** No infinite "preparing" hang when the BT device
  is pre-connected; recording starts ≤2.5 s; SCO route resolves to
  `VOICE_COMMUNICATION` (or `MIC` fallback on timeout).
- **Auto-surrogate (GREEN):** `DictateCutoverE2ETest` C6-IMPL-1 audio-focus/
  BT-SCO parity cases + B2-C6-W1-REGATE code-trace (Connected/Failed/
  timeout/duplicate edges tested); `CutoverArchitectureInvariantTest`.

### TC-R2: SPACE key — exactly one space per tap, no double-commit (B5-VAL-W1 F-1, was Critical)

- **Mode:** manual
- **Profiles:** C12-FULL  *(regression — Critical user-visible render regression)*
- **Knowledge:** hand-rolled (Spec 2 §13.2 / §11.7 — SPACE is touch-only, no click row)
- **Scope:** B5-VAL-W1 F-1 — the render-cutover briefly wired SPACE into
  **both** the click loop and the touch handler → every SPACE tap
  committed **two** spaces. Fix: exclude SPACE from the click loop
  (one-tap-one-space; G4/§11.7 intact).
- **Steps:**
  1. Open Notes, Dictate IME selected, KEYBOARD mode.
  2. Type `a`, then tap the **space bar once**, then type `b`.
  3. Verify the inserted text is exactly `a b` (single space) — **not**
     `a  b` (double space).
  4. Repeat 5× rapidly + with a long-press on space (cursor-swipe must
     still work, no extra spaces committed).
- **Expected Result:** Exactly one space character per single SPACE tap;
  long-press SPACE still triggers cursor-swipe (no regression of
  §11.7); never a double-commit.
- **Auto-surrogate (GREEN):** B5 render unit-tests + `RenderPathCutoverGateTest`
  5/5 (G2-G16 sole-owner, no double-write); B5-VAL-W1 fixed + re-validated.

### TC-R3: Reword-staging language override seeded on entry + cleared on exit (B5-VAL-W1 F-2 / F-6 reopened-then-closed)

- **Mode:** manual
- **Profiles:** C12-FULL  *(regression — cross-session stale-language leak)*
- **Knowledge:** hand-rolled (F-6 single-carrier `LanguageState.override` lifecycle)
- **Scope:** B5-VAL-W1 F-2/F-6 — the cross-carrier collapse onto a single
  `LanguageState.override` initially wired only the *read* side; the
  *seed-on-staging-entry* and *clear-on-staging-exit* were missing →
  the reword-staging language chip showed the wrong language and the
  override leaked into the **next** session. Fix: `dispatchStagingOverride`
  seeds session-language on entry + `SetOverride(null)` on every exit
  (4 boundary wirings: 2 seed / 2 clear).
- **Steps:**
  1. Set transcription language to German. Record a German phrase,
     trigger a reword that enters ReprocessStaging.
  2. Verify: the staging language chip shows **German** (the session
     language, seeded on entry — not the default/stale value).
  3. Exit staging (send or cancel). Start a **new** recording in a
     fresh session.
  4. Verify: the new session uses the configured language with **no
     stale override** leaked from the previous staging session
     (language chip = configured language, not the previous override).
  5. `adb logcat -d | grep -E "(SetOverride|LanguageState|dispatchStagingOverride|selectedLanguage)"`
     — verify a seed on staging-entry and a `SetOverride(null)` on
     staging-exit; no leak across the session boundary.
- **Expected Result:** Staging language chip = session language on entry;
  override cleared on exit; no stale-override leak into the next
  session; single carrier (`LanguageState.override`) only.
- **Auto-surrogate (GREEN):** B5 language-override lifecycle unit-tests
  (research `f6-staging-language-override-lifecycle`); B5-VAL-W1 closed.

### TC-R4: F-6 staging-override does not corrupt the reprocess job config

- **Mode:** manual
- **Profiles:** C12-FULL
- **Knowledge:** hand-rolled
- **Scope:** B5-VAL-W1 F-2 corollary — the staging override is a
  **display/config-read** concern; the actual reprocess `JobRequest`
  must be unaffected (the reword still re-processes in the correct
  language regardless of the chip).
- **Steps:**
  1. From TC-R3 step 1 (German session, ReprocessStaging).
  2. Send the reword. Verify the reworded output is in German (the
     reprocess job used the correct language — the override fix did not
     corrupt the job config, only the display chip + clear lifecycle).
- **Expected Result:** Reword output language correct; the F-6 fix is
  display+lifecycle only, reprocess JobRequest fidelity intact.
- **Auto-surrogate (GREEN):** B5 reprocess-config unit-tests; covered
  by the F-6-closed validation in B5-VAL-W1.

### TC-R5: INT-1-pattern non-recurrence — no parallel-dormant layer (3× caught during Epic)

- **Mode:** auto (architecture-test) + manual (behaviour spot-check)
- **Profiles:** C12-FULL  *(the Epic's raison d'être — INT-1 recurred 3× during impl, each caught + spec-faithfully resolved)*
- **Knowledge:** test-orchestrator
- **Scope:** INT-1 / AC-10 — the INT-1 anti-pattern (built-but-not-driven
  production code) recurred **three times** during the Epic
  (C10-IMPL-2 render-cutover never done, CR4-IMPL-1 listener-bundle
  with no owner classes, CR4-IMPL-3 RESEND-action no new-path impl) and
  was caught + resolved each time, not re-deferred. The D4 regression-
  lock (`CutoverArchitectureInvariantTest`) now prevents silent
  recurrence.
- **Steps (auto):**
  1. Run `./gradlew test --tests "*CutoverArchitectureInvariantTest"` —
     assert 8/8 green (4 invariant + 4 stripper-soundness self-tests).
  2. The 4 invariants: exactly one `JobExecutor.INSTANCE.start` in the
     IME (RESUME carve-out only); zero `USE_LEGACY_RECORDING_DRIVE`
     functional code; zero functional refs to the 4 deleted
     controllers; stubs not wired + real adapters wired in
     `DictatePipelineService.onCreate`.
- **Steps (manual spot-check):**
  3. Record→stop-and-send on a real device; pull the notification —
     verify the FGS notification + transcription is driven by the new
     orchestrator (the `stub-leak-scan` Periodic Visit shows zero stub
     `Log.w` lines).
- **Expected Result:** `CutoverArchitectureInvariantTest` 8/8 green;
  zero stub `Log.w` at runtime; single recording driver. The
  parallel-dormant failure class cannot silently regress.
- **Auto-surrogate (GREEN):** **this TC's own auto-tier is the
  surrogate** — `CutoverArchitectureInvariantTest` (INT-3 D4 lock,
  non-vacuity self-tested + empirically RED-proven in INTEGRATION-W1).

### TC-R6: HistoryDetailActivity re-process is single-dispatch (INT-2 — out-of-scope, awareness)

- **Mode:** manual  *(awareness only — INT-2 is `out-of-scope-recorded`, NOT a blocker)*
- **Profiles:** C12-FULL
- **Knowledge:** hand-rolled
- **Scope:** INT-2 — `HistoryDetailActivity:492` has a pre-existing
  `JobExecutor.INSTANCE.start` (the "re-process a historical
  transcription" button). It is **outside the Epic scope** (not the IME
  recording surface), pre-existing + untouched by the Epic, and
  **single-dispatch** (a History Activity button, not a recording
  user-action) so it does **not** violate AC-10. This TC documents the
  awareness; a failure here is a Nice-to-have follow-up, not a Phase-4.5
  blocker.
- **Steps:**
  1. Open Dictate History, pick a past transcription, tap "re-process".
  2. `adb logcat -d | grep -E "(JobExecutor.*start|RecordingAction.StartRecording)"`
     — verify exactly ONE driver fired (the History `JobExecutor.start`),
     NOT also an orchestrator dispatch (no double-drive; this is a
     non-IME path so AC-10 is structurally not in scope).
  3. Verify the historical transcription re-processes correctly.
- **Expected Result:** History re-process works single-dispatch; no
  AC-10 violation (it is a separate, untouched, non-IME feature). Any
  defect → Nice-to-have Phase-5 follow-up (collapse HistoryDetailActivity
  onto the orchestrator), explicitly **non-blocking**.
- **Auto-surrogate (GREEN):** `CutoverArchitectureInvariantTest` asserts
  exactly-one `JobExecutor.start` *in the IME* (the History site is a
  different file, intentionally out of the IME invariant scope per
  INT-2/D3).

> **Pre-Flight env-note (Phase-4.5 agent, 2026-05-17):** The runbook's
> Prerequisite #3 / Pre-Flight E-3 names `android-35`; the project now
> sets `compileSdkVersion 36` (`app/build.gradle:9`) and the installed
> platform is `android-36` — the build prerequisite is **satisfied**
> against SDK 36 (the "-35" literal is parent-baseline-stale; not a
> failure). The device/adb/ime-enabled/mic-permission Pre-Flight items
> (#4-#15, E-1..E-5/E-9) are **`blocked: no-device-in-env`** — an
> environment constraint (no Android device/emulator in this CI-like
> env), NOT a test failure. The device-tier TCs are listed for the user
> to run on their phone per the Q1-Q7 defaults; each carries an
> auto-surrogate that has already run GREEN in the in-plan gates.

## How to use this file

This runbook serves **three readers**:

1. **The C6-D2pre implementer-agent (in-plan D2-pre verification gate, skill-block B2).**
   Runs the **C6-SUBSET** profile: prereqs #1-#19 with #17 = guard-default-new,
   #18 = no-schema-change; the `C6-SUBSET`-tagged TCs only; the 3
   cutover-critical Periodic Visits. Its PASS authorises C7 + Theme C. Its
   FAIL keeps them gated (the guarded fallback keeps the app shippable).
2. **The C12-D2 implementer-agent (final integration gate, skill-block B4).**
   Runs the **C12-FULL** profile: all TCs incl. the auto-tier grep/regression
   invariants (AC-1/5/6/7/9) + cleanup-greps + the full manual set + Periodic
   Visits + Teardown.
3. **The skill's post-all-blocks Phase-4.5 agent.** Runs the same **C12-FULL**
   profile as a holistic re-run, then writes `./reports/phase-4.5-e2e-report.md`
   (device-info, TC-pass/fail counts, logcat-archive-path, deviations).

**Knowledge-gap caveat:** no `test-knowledge-android` skill exists; every step
is self-contained hand-rolled adb. If a need for an idiom beyond this runbook
arises, the agent flags a `knowledge-gap` issue rather than blocking.

**Skip-recommendation:** NOT skip. This is a staged destructive cutover on the
product's core feature (recording) with major user-visible behaviour impact
(recording-drive flip, real FGS notification, legacy-path deletion). E2E is
load-bearing — the C6 gate is an in-plan blocking authorisation, not optional.
