# Block 2: Theme B — Recording-Drive Cutover + D2-pre Gate

> **Logbook for Block 2.** Implementation/Audit-Agents document here.
> Orchestrator maintains the state-file status table — agents do not.

**Phase:** Theme B — recording-drive (the live cutover) + the D2-pre verification GATE
**Implementation-Chunks:** C3-B1, C4-B2, C5-B3, C6-D2pre, C7-B3
**Workflow:** Iter-10 5-step (combined-step pattern — SendMessage/resume unavailable; orchestrator splits 2 commits/chunk). **mid-chunk-triage ARMED for C3/C4/C5** (architecture-conflict / blocks-following-chunks markers likely on the recording-drive flip).
**Block-Start-Commit:** 17085ca
**Block-End-Commit:** ⏳

> **⚠ GATE (Epic §6.2, load-bearing):** C6-D2pre is a verification GATE.
> C7-B3 (legacy-call-site deletion) MUST NOT start until C6 signs off
> GREEN. All of Block B3 (Theme C, the next block) is also hard-gated on
> green C6. Until C6: the legacy `JobExecutor.start` path stays reachable
> behind `USE_LEGACY_RECORDING_DRIVE`. The new path must be **proven**,
> not assumed.

> **Cross-block forwarding notes in effect (from state-file Orchestrator
> Forwarding Notes — agents must honour):**
> - **FN-1:** AC-10 has **3** `JobExecutor.start` call-sites in
>   `DictateInputMethodService.java` (`:2236`, `:2897`, `:3053`), not the
>   2 the Epic §4-B3 names. C5 guards all three; C7 deletes all three;
>   C6 double-dispatch grep covers all three.
> - **FN-2:** notification-action strings `[Pause][Stopp][Senden]` likely
>   absent in `values/strings.xml` — C4 adds them (de/en, mirrors F-5).
> - **FN-3:** OQ-2 default — `USE_LEGACY_RECORDING_DRIVE` removed
>   immediately after C6 green (in C7), per D7.
> - **FN-4:** `StopRecordingAndSend` is now a **payload-less data
>   object**. C5 dispatches `StartRecording(target, audioFile,
>   preAllocatedId)` (IME `:2213` UUID) then payload-less
>   `StopRecordingAndSend()`. Supersedes Epic §4-B3 / §3 literal
>   `StopRecordingAndSend(realSessionId)` wording.
> - **F-7 (B1):** `StartRecording.sessionId` now has a
>   `require(isNotBlank())` fail-fast. C5 MUST mint a real non-blank UUID
>   (the IME's `preAllocatedId`) — passing `""` will crash.

---

## Issue Index (Orchestrator-Maintained)

**Severity counts:** Critical: 1 (C7-IMPL-1 — **FIXED by mid-chunk-triage wave B2-C7-MID-W1**) · Important: 2 (resolved by C5) · Nice-to-have: 2 (1 resolved, 1 still-deferred) · From C5: Important: 1 fixed (C5-IMPL-1 via C6-W1) + 1 delegated (C5-IMPL-2 documented Known-Gap), Nice-to-have: 1 postponed (C5-IMPL-3) · From C6 gate: Important: 1 (C6-IMPL-1) → FIXED (C6-W1); Nice-to-have: 1 (C6-IMPL-2) → **fixed by C7 (RESUME carve-out honoured)** · **From C7: Critical: 1 (C7-IMPL-1) → FIXED (B2-C7-MID-W1: imported-audio-file path now orchestrator-routed via `PipelineAction.TriggerPipeline`; AC-10 fully GREEN modulo the documented RESUME exception)**

> **🟢 C6-IMPL-1 CLOSED by repair-wave B2-C6-W1 — C6-D2pre may RE-GATE.**
> The gate-RED-blocking legacy-parity regression is repaired:
> audio-focus + Bluetooth-SCO are now emitted on the new recording path
> (`USE_LEGACY_RECORDING_DRIVE=false`) with legacy parity — AudioModule
> observes the RecordingState FSM (ADR-0002 Mode-2 cascade → Mode-1
> effect, restoring Spec 1 §15.1 row-3) and emits
> `RequestAudioFocus`/`ReleaseAudioFocus` (gated on `Pref.AudioFocus`
> default-true) + `StartBluetoothSco`/`StopBluetoothSco` (gated on
> `Pref.UseBluetoothMic`); BT-mic recordings defer `AllocateMediaRecorder`
> until the SCO handshake resolves (SCO-ready → `VOICE_COMMUNICATION`,
> SCO-fail/timeout → `MIC` fallback). Build green; full suite
> 1037/1038 (the only failure is the documented R-7 order-dependent
> `LegacyAudioFileMigrationTest` pollution flake — passes isolated, not
> a C6-W1 regression; no migration/DB files touched by this wave).
> New-path audio-focus proven end-to-end (`DictateCutoverE2ETest`
> shadow-AudioManager assertions) + pure-reducer/observer coverage.
> **A fresh C6-D2pre re-gate (re-trace audio-focus/BT-SCO on the new
> path + re-run the auto-tier) can now go GREEN, authorising C7 +
> Theme C.** See `### Gate-Repair Wave B2-C6-W1`.

| ID | Source agent | Severity | Status | Title | Source phase |
|----|--------------|----------|--------|-------|--------------|
| C3-IMPL-1 | B2-C3-B1-IMPL | Important | **fixed (C5)** | Fresh-recording config resolver: 8 IME-runtime fields not on orchestrator path. **C5 closed it** via `ImePipelineConfigResolver` (IME snapshots all 8 at the send-tap) + `DelegatingPipelineConfigResolver` + `LocalBinder.registerPipelineConfigResolver`. All 8 fields threaded 1:1 (see C5 fidelity table). | step-1-impl (C3-B1) → fixed C5 |
| C3-IMPL-2 | B2-C3-B1-IMPL | Nice-to-have | **fixed (C5)** | Reprocess modelOverride/targetAppPackage null + AutoFormatting +1. **C5 closed it**: `ImePipelineConfigResolver.snapshotReprocess` threads selectedModel/targetAppPackage/totalSteps; the new-path reprocess branch in `handleReprocessSend` routes via the C3 adapter with the snapshot. | step-1-impl (C3-B1) → fixed C5 |
| C4-IMPL-1 | B2-C4-B2-IMPL | Important | **fixed (C5)** | `NotificationStatus.Recording` had no emitter + no `Paused` variant. **C5 closed it**: added `NotificationStatus.Paused`, the coordinator `Paused` arm (`[Resume][Stopp][Senden]` + recording_paused subtitle), and `RecordingModule.Effect.UpdateNotification`/`DismissNotification` emitted across the FSM (Active→Recording, Paused→Paused, Resume→Recording, Stop/Cancel→Dismiss; StopRecordingAndSend deliberately NO dismiss for a seamless Recording→Pipeline hand-off). | step-1/2 (C4-B2) → fixed C5 |
| C4-IMPL-2 | B2-C4-B2-IMPL | Nice-to-have | postponed | `Pipeline` notif subtitle generic, no F-13 counters in `NotificationStatus.Pipeline`. Still-deferred (cosmetic; live counter already in record-button label; needs a `NotificationStatus.Pipeline` payload change across PipelineModule emit-sites — out of C5 recording-trigger scope). | step-1/2 (C4-B2) |
| C5-IMPL-1 | B2-C5-B3-IMPL | Important | **fixed (C6-W1)** | New-path **AudioFocus not requested** + **Bluetooth SCO route not established**. **Closed by repair-wave B2-C6-W1** (consolidated as C6-IMPL-1): AudioModule now emits `RequestAudioFocus`/`ReleaseAudioFocus`/`StartBluetoothSco`/`StopBluetoothSco` in reaction to RecordingState FSM transitions (ADR-0002 Mode-2 cascade → Mode-1 effect, restoring Spec 1 §15.1 row-3); BT-mic recordings defer `AllocateMediaRecorder` until the SCO handshake resolves so the recorder source matches the actual route. See `### Gate-Repair Wave B2-C6-W1`. | C5 step-1/3 → fixed C6-W1 |
| C5-IMPL-2 | B2-C5-B3-IMPL | Important | delegated-to-orchestrator | Legacy recording **UI/animation/keyboard-hide-pause** sites (~12 `recordingStateController.getState()` reads outside the record-button gate: `:730`/`:1215`/`:1855`/amplitude/timer/onKeyboardHidden) stay legacy-driven; on the new path the legacy controller is never started so they read Idle (no legacy recording animation, no legacy keyboard-hide auto-pause). The FGS notification (AC-2) is the authoritative new-path recording-active surface. The RenderBackend recording-UI migration is Theme-C/C3, out of C5's recording-trigger scope. Documented Known-Gap. | C5 step-2 |
| C5-IMPL-3 | B2-C5-B3-IMPL | Nice-to-have | postponed | RESUME (`startResumeJob`, JobExecutor.start #2) has no orchestrator equivalent (`PipelineRunnerSubsystem` has no `resume`); both boolean branches keep legacy `JobExecutor.start` (single-dispatch, orthogonal to the fresh-recording cutover). Adding a resume subsystem action is an architecture change beyond C5 (prompt forbids a fragile flip). C7/later owns retiring it. | C5 step-1 |
| C6-IMPL-1 | B2-C6-D2pre-IMPL | Important | **fixed (C6-W1)** | Consolidated gate-validated form of C5-IMPL-1. **Closed by repair-wave B2-C6-W1.** Audio-focus + BT-SCO are now emitted on the new path with legacy parity: `AudioModule.onCrossModuleStateChange` observes the RecordingState FSM (recording-engaged → `RecordingStarted` → `RequestAudioFocus` gated on `audioFocusEnabledPref` default-true + `StartBluetoothSco` gated on `useBluetoothMic`; disengaged → `RecordingEnded` → `ReleaseAudioFocus`+`StopBluetoothSco`). BT-mic recordings defer `AllocateMediaRecorder` until `ScoRouteResolved` (SCO-Connected→`VOICE_COMMUNICATION`, SCO-Failed/timeout→`MIC`, subsystem-owned 2500 ms timeout), eliminating the silent phone-mic substitution. Stale `AudioModule.kt` KDoc rewritten to describe the real path. ADR-0002 Mode-1/2 only; no Mode-3. New-path audio-focus proven E2E (`DictateCutoverE2ETest` shadow-AudioManager assertions) + pure-reducer/observer tests. **C7 + Theme C may now re-gate via a fresh C6-D2pre run.** | C6 gate → fixed C6-W1 |
| C6-IMPL-2 | B2-C6-D2pre-IMPL | Nice-to-have | **fixed (C7)** | C7 must carve out the RESUME `JobExecutor.start` site from its deletion scope — no orchestrator resume equivalent exists. **C7 honoured it:** `startResumeJob`'s byte-identical `if (USE_LEGACY_RECORDING_DRIVE) {…} else {…}` branches were collapsed to the single unconditional `JobExecutor.INSTANCE.start` they both contained — RESUME stays legacy, not fenced/deleted/rerouted (carve-out satisfied exactly). | C6 gate → fixed C7 |
| C7-IMPL-1 | B2-C7-B3-IMPL | **Critical** | **fixed** (B2-C7-MID-W1) | Imported-audio-file transcription (`onStartInputView` → `runTranscriptionViaOrchestrator()` → `JobExecutor.INSTANCE.start`, `:2554`) had **no orchestrator route**. **Fixed by mid-chunk-triage wave B2-C7-MID-W1:** the method was repurposed to `transcribeImportedAudioFileViaOrchestrator()` — it now snapshots the IME-runtime config via the shared C5 `captureFreshConfigSnapshot` helper (field-for-field identical to the deleted legacy `:2507-2523` construction — R-1/AC-9) and dispatches `Action.PipelineAction.TriggerPipeline(sessionId, audioFile)` (the documented Spec 1 §3 pipeline entry-point the recording FSM itself emits) → `PipelineModule` `Idle → Preparing` → `Effect.SubmitPipeline` → the C3 `PipelineRunnerSubsystemAdapter.submit` → `ImePipelineConfigResolver.resolveFresh` → `JobExecutor.start` **inside the C3 adapter** (the sole legacy start site). The legacy `:2554` `JobExecutor.start` + dead `JobRequest`/`preAllocatedId`/flag-reset removed. No new Action/reducer arm needed (the C3 submit seam already supported it). AFTER-grep: the ONLY surviving IME `JobExecutor.INSTANCE.start` is `:3222` (RESUME carve-out, C6-IMPL-2). **AC-10 fully GREEN modulo the documented RESUME exception**; Theme-C legacy-pipeline-trigger retire unblocked. Build + full suite green. See `### Mid-Chunk-Triage Wave B2-C7-MID-W1` + `../research/imported-audiofile-orchestrator-route.md`. | C7 step-1/3 → fixed B2-C7-MID-W1 |

---

## Conventions established this block

| Convention | Where established | Description |
|------------|-------------------|-------------|
| — | — | — |

---

## Mandatory Format Reminder for All Agents

Shared directives: `~/.claude/skills/implement-long-plan-v2/prompts/agent-prompts.md`.
Each agent documents: What was done · Plan deviations (table) · Issues
(table, severity + 5-status) · Overlooked points. 5-status: `open` /
`delegated-to-orchestrator` / `postponed` / `fixed` / `closed`.

---

## Implementation Logs

### Chunk C3-B1 — real PipelineRunnerSubsystemAdapter (JobExecutor-backed)

**Agent-IDs:** `B2-C3-B1-IMPL` (fresh, combined Steps 1-5).
**Status:** ✅ complete · **Risk:** HIGH (R-1 JobRequest field-by-field fidelity)
**Implementation-Commit (Commit 1):** ⏳ (orchestrator) · **Test-Commit (Commit 2):** ⏳ (orchestrator)

**What was done:** Replaced the no-op `PipelineServiceStubSubsystems.pipelineRunner`
stub with a real production `PipelineRunnerSubsystemAdapter` — a **thin
delegation to `JobExecutor.INSTANCE`** (OQ-1 thin-delegation; no
`PipelineOrchestrator` rewrite). `submit`/`submitReprocess` build a
`JobRequest.TranscriptionPipeline` via an injected `PipelineConfigResolver`
seam and call `JobExecutor.start`; `cancel`→`JobExecutor.cancel`;
`isRunning`/`activeJobCount`→`ActiveJobRegistry`. Wired into
`DictatePipelineService.onCreate` Step 4 (replaced the `:419`
stub line); `pipelineRunner` stub demoted to `@Deprecated` test-only
(mirrors `sessionRepo`/`audioFileFactory`).

**OQ-1 verdict:** thin delegation is **feasible — NO architecture-conflict**.
`JobExecutor.start(context, request)` + `cancel(sessionId)` and
`ActiveJobRegistry.isActive`/`.state.value.size` give the adapter a clean
submit/cancel/isRunning surface. Adopted the Epic §7 OQ-1 path; no escalation.

**R-1 verdict:** reprocess path is mapped **1:1** (table below, all asserted
field-by-field in tests). Fresh-recording path: ~8 IME-runtime-only fields are
**NOT on the orchestrator path until C5** — the `DefaultPipelineConfigResolver`
**throws** for them rather than silently defaulting (surfacing beats guessing;
the throw is caught by the orchestrator's `runEffect`→`EffectFailure` wrap, so
it fails loud, never silent-wrong). The legacy IME path is untouched and stays
authoritative for fresh recordings (Epic §6.2). Fresh-field delegation flagged
as `IMPL-1` (delegated to C5, the prescribed owner per Epic §4 B1/B3).

**R-1 JobRequest field-by-field fidelity table (mandatory evidence):**

*Fresh recording — IME `DictateInputMethodService.java:2214-2230`:*

| IME field (line) | Adapter source | Status |
|---|---|---|
| `preAllocatedId` (:2215) | submit `sessionId` (from `Effect.SubmitPipeline`, IME `:2213` UUID via C5) | ✅ on-path |
| `audioFilePath` (:2218) | submit `audioFile.absolutePath` | ✅ on-path |
| `kind=RECORDING` (:2217) | constant in resolver | ✅ trivial |
| `recordingsDir` (:2223) | `File(filesDir,"recordings")` | ✅ on-path |
| `origin=KEYBOARD` (:2226) | constant | ✅ trivial |
| `reuseSessionId=null` (:2224) | constant (fresh) | ✅ trivial |
| `totalSteps` (:2187-2189) | IME: 1 + autoFormatting + promptQueue | ❌ **C5** (AutoFormattingService + PromptQueueManager are IME-runtime) |
| `language` (:2198-2201) | IME: `LanguageController.getEffectiveLanguage()` | ❌ **C5** (LanguageController IME-runtime; D-13 removes it) |
| `stylePrompt` (:2202) | IME: `promptService.resolveWhisperStylePrompt(...)` | ❌ **C5** (depends on resolved language) |
| `queuedPromptIds` (:2221) | IME: `promptQueueManager.getQueuedIds()` | ❌ **C5** (PromptQueueManager IME-runtime) |
| `targetAppPackage` (:2222) | IME: `EditorInfo.packageName` | ❌ **C5** (EditorInfo is IME-view runtime) |
| `livePrompt` (:2227) | IME instance flag `livePrompt` | ❌ **C5** (IME instance state) |
| `autoSwitchKeyboard` (:2228) | IME instance flag `autoSwitchKeyboard` | ❌ **C5** (IME instance state) |
| `showResendButton` (:2229) | IME: prefs `LastFileName.exists() && ResendButton` | ❌ **C5** (resolvable but threaded with the above; not split to avoid partial-config) |

*Reprocess staging — IME `DictateInputMethodService.java:3038-3051` (fully on-path, asserted 1:1):*

| IME field (line) | Adapter source | Status |
|---|---|---|
| `targetSessionId` (:3039) | `submitReprocess` `sessionId` | ✅ 1:1 |
| `totalSteps` (:3034-3036) | `1 + queue.size` (AutoFormatting +1 = `IMPL-2` delegated) | ⚠ near-1:1 |
| `kind=REPROCESS_STAGING` (:3041) | constant | ✅ 1:1 |
| `audioFilePath` (:3042) | `audioFile?.absolutePath` (F-19 null = DB-lookup) | ✅ 1:1 |
| `language` (:3043) | `submitReprocess` `language` arg | ✅ 1:1 |
| `modelOverride` (:3044) | `null` (staging-FSM does not carry it on the new path) | ⚠ `IMPL-2` |
| `queuedPromptIds` (:3045) | `submitReprocess` `queue` arg | ✅ 1:1 |
| `targetAppPackage` (:3046) | `null` (IME-view runtime) | ⚠ `IMPL-2` |
| `recordingsDir` (:3047) | `File(filesDir,"recordings")` | ✅ 1:1 |
| `reuseSessionId` (:3048) | `sessionId` | ✅ 1:1 |
| `stylePrompt` (:3049) | `null` (matches IME — reprocess passes null) | ✅ 1:1 |
| `origin=KEYBOARD` (:3050) | constant | ✅ 1:1 |

**Files created/modified (production, Commit 1):**
- `app/src/main/java/net/devemperor/dictate/core/PipelineRunnerSubsystemAdapter.kt` (NEW — adapter + `PipelineConfigResolver` seam + `DefaultPipelineConfigResolver`)
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt` (field decl + onCreate Step 4 wiring, replaced `:419`)
- `app/src/main/java/net/devemperor/dictate/state/PipelineServiceStubSubsystems.kt` (demote `pipelineRunner` `@Deprecated` + header KDoc refresh)

**Files in chunk-scope:** all 3 above are named in the chunk spec/Epic §4 B1.
**Files outside chunk-scope (drift):** none.

**Plan deviations:**

| Deviation | Plan Location | What changed | Why | Impact | Inline-fixed? |
|-----------|---------------|--------------|-----|--------|----------------|
| Config-resolver seam instead of in-adapter `JobRequest` construction | Epic §4 B1 ("adapter's `submit` builds a `JobRequest.TranscriptionPipeline` mirroring IME `:2214-2236`") | Adapter delegates `JobRequest` construction to an injected `PipelineConfigResolver`; C3 ships `DefaultPipelineConfigResolver` (reprocess 1:1, fresh throws) | The new path's `Effect.SubmitPipeline` carries only `sessionId`+`audioFile`; the ~8 fresh fields are IME-runtime sources not on the orchestrator path until C5. Inventing defaults = the exact R-1 silent-data-loss the Epic forbids. Seam mirrors the established `emitAction:(Action)->Unit` provider pattern and gives C5 one typed insertion point. | C5 (IME-trigger flip) MUST inject an IME-faithful `PipelineConfigResolver` for fresh recordings. Until then fresh new-path submit fails loud (guarded; legacy IME path authoritative). C4/C7 unaffected (submit-direction + reprocess work today). | inline-fixed (mid-size, solution clear from Epic §4 B1/B3 + R-1 directive) → marker `plan-deviation-resolved` |

**Issues (Steps 1-3 — IMPL / PLAN-FIX / CODE-FIX):**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| IMPL-1 | Important | Fresh-recording `JobRequest` IME-runtime-only fields (`totalSteps`, `language`, `stylePrompt`, `queuedPromptIds`, `targetAppPackage`, `livePrompt`, `autoSwitchKeyboard`, `showResendButton`) are NOT on the orchestrator path; `DefaultPipelineConfigResolver.resolveFresh` throws to surface this. C5 must inject an IME-faithful resolver. `PipelineRunnerSubsystemAdapter.kt` resolveFresh + table above. | delegated-to-orchestrator | Prescribed owner is C5 (Epic §4 B1/B3 "this block is submit-direction only … C5 threads the IME trigger"). NOT an architecture-conflict — the path simply does not exist yet by design. Marker `plan-deviation-resolved`. |
| IMPL-2 | Nice-to-have | Reprocess `modelOverride`/`targetAppPackage` defaulted to `null` and AutoFormatting `+1` step omitted (IME `:3035`/`:3044`/`:3046`) — these are IME-runtime sources not on the staging-FSM path. Minor vs the fresh path (reprocess re-uses an existing session). `PipelineRunnerSubsystemAdapter.kt` resolveReprocess. | delegated-to-orchestrator | Same C5 ownership; lower severity (reprocess operates on an already-persisted session, model/targetApp less load-bearing than fresh language/prompts). |

**Test-Files created (Step 4-5 — Commit 2):**
- `app/src/test/java/net/devemperor/dictate/core/PipelineRunnerSubsystemAdapterTest.kt` (NEW, 7 tests)

**Test-Run-Result:** ✅ 7/7 pass. Full suite: **971 tests, 0 failures, 0 errors**
(≥946 baseline — AC-9 regression invariant holds, no cross-chunk regression).
`./gradlew assembleDebug` green.

Test → AC mapping:
| Test | Asserts |
|---|---|
| `resolveReprocess maps every JobRequest field 1-to-1...` | R-1 reprocess fidelity table (field-by-field) |
| `resolveReprocess passes a null audio path through...` | F-19 nullable contract |
| `resolveFresh throws rather than silently defaulting...` | R-1 fresh-guard (surfacing > silent) |
| `submitReprocess starts JobExecutor with the resolved JobRequest` | thin delegation → `JobExecutor.start` |
| `isRunning and activeJobCount reflect ActiveJobRegistry` | AC-1 registry reflection |
| `cancel delegates to JobExecutor cancel` | `cancel`→cooperative token |
| `binder TriggerPipeline reaches the real adapter and the R-1 guard surfaces...` | AC-1: production wiring reaches real adapter (NOT stub), no silent `JobExecutor.start` |

**Issues (Steps 4-5 — IMPL-TEST / IMPL-TEST-FIX):**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| — | — | none | — | — |

#### Code-Bugs Found While Writing/Reviewing Tests *(only if any)*

None. (The one Step-4 test failure was a **test-fake design issue**, not a
production bug — see below.)

| File:Line | Bug-Symptom | Root-Cause | Fix (vorher → nachher) | Recherche |
|-----------|-------------|-----------|------------------------|-----------|
| `PipelineRunnerSubsystemAdapterTest.kt` cancellableRunner (test-only) | `cancel delegates...` test flaked first run | `JobExecutor.cancel` ALSO `Thread.interrupt()`s the runner thread (last-resort fallback, `JobExecutor.kt:186`); the fake's `Thread.sleep` threw `InterruptedException` and exited the spin before re-checking `token.isCancelled` | wrapped the fake's `Thread.sleep` in `try/catch(InterruptedException)` so it keeps polling the cooperative token (the actual contract surface) | `JobExecutor.kt:170-187` `cancel()` doc — confirms token-flip + interrupt are both intentional; production `cancel` delegation was always correct |

**Mid-Chunk-Triage** *(only if Critical-blocker — ARMED for this chunk)*:

Not triggered. OQ-1 resolved in-chunk (thin delegation feasible, no
architecture-conflict). IMPL-1 is an Important `delegated-to-orchestrator`
with prescribed owner C5 — explicitly NOT a `architecture-conflict` /
`blocks-following-chunks` marker (C4-B2 notification + reprocess work today;
only the *fresh* new-path submit is C5-gated, which is C5's own scope).

| Triggering Issue | Step | Research Topic | Repair Agent-ID | Wave-Commit | Outcome |
|------------------|------|----------------|------------------|-------------|---------|
| — | — | — | — | — | — |

**Overlooked / Known Gaps:**
- Fresh-recording `PipelineConfigResolver` is the C5 insertion point (IMPL-1) —
  by design, not a gap to fix here.
- `LocalBinder` accessor for the adapter not added (no consumer yet; the
  field is held for C5 to reach without reconstruction).
- The binder integration test asserts the R-1 guard surfaces (registry stays
  empty) rather than asserting a successful fresh submit — a successful
  fresh-path submit is only possible post-C5 (when the IME-faithful resolver
  is injected); a green fresh-submit assertion belongs in C5's tests.
- Reprocess `modelOverride`/`targetAppPackage`/AutoFormatting-step (IMPL-2)
  delegated to C5 — low-severity, reprocess operates on a persisted session.

---

### Chunk C4-B2 — real PipelineNotificationCoordinator + PipelineActionRouter

**Agent-IDs:** `B2-C4-B2-IMPL` (fresh, combined Steps 1-5).
**Status:** ✅ complete · **Risk:** HIGH (R-2 FGS crash) — **R-2 verdict: GREEN**
**Implementation-Commit (Commit 1):** ⏳ (orchestrator) · **Test-Commit (Commit 2):** ⏳ (orchestrator)

#### Implementation (B2-C4-B2-IMPL)

**What was done:** Wrote the Spec 1 §7.5 `core/PipelineActionRouter.kt`
(notification-button → `Action` back-channel: `pendingIntentFor` builds
the `PendingIntent.getService` targeting `DictatePipelineService`;
`dispatch(intent)` decodes the action-string into the typed
`RecordingAction.*`/`PipelineAction.*`) and the Spec 1
§7.4/§7.6/§11.1.2 `core/PipelineNotificationCoordinator.kt`
(implements the `PipelineNotificationCoordinatorSubsystem` **command
interface** the orchestrator actually uses; `show(status)` →
`NotificationManagerCompat.notify(NOTIF_ID, …)` with the exact §11.1.2
`NotificationCompat.Builder` + §7.6 content/action map;
`buildInitial()` for `startForeground`; `show(Idle)`/`dismiss()` →
`cancel(NOTIF_ID)`). Wired both into
`DictatePipelineService.onCreate` Step 4 (replaced the
`PipelineServiceStubSubsystems.notificationCoordinator` stub at
~`:446`); `onStartCommand` now forwards the action-intent to the
router before `startForegroundCompat(coordinator.buildInitial())`;
`onDestroy` adds an idempotent `coordinator.dismiss()` (Spec 1 §7.3).
Demoted the stub `notificationCoordinator` to `@Deprecated` test-only
(mirrors C3-B1's `pipelineRunner`). Added the OQ-3/FN-2 notification
strings (en + de). `assembleDebug` green.

**R-2 verdict (FGS-crash risk — load-bearing): GREEN, no
architecture-conflict.**
- **Channel-order:** the coordinator **never** creates a
  `NotificationChannel`. `ensureNotificationChannel()` (onCreate Step
  1) stays the sole channel owner; the coordinator only references
  `DictatePipelineService.CHANNEL_ID`. Channel-before-`startForeground`
  ordering is therefore exactly as the Service already guaranteed
  (asserted: `channelIsCreatedBeforeStartForeground_andUsesSoTNotifId`).
- **FGS-5s-Frist:** `buildInitial()` is a pure in-memory state →
  notification render (no DB/IO); `onStartCommand` calls
  `startForegroundCompat(buildInitial())` synchronously. The
  action-router `dispatch` is wrapped in try/catch so a decode failure
  cannot abort the FGS start (asserted: `onStartCommand` start-foreground
  test + `RecordingDriveTest` boot).
- **NOTIF_ID single SoT:** moved the canonical `const val NOTIF_ID =
  0xD1C7A7E` from `DictatePipelineService.companion` to
  `PipelineNotificationCoordinator.NOTIF_ID`; Service references the
  qualified name for `startForeground`. **Grep evidence:** the only
  `const val NOTIF_ID` declaration in `app/src/main` is
  `PipelineNotificationCoordinator.kt:231`; no `1001` literal anywhere;
  `DictatePipelineService.companion` no longer declares one.
- **`notify` failure isolation:** `show()` swallows + logs any
  `notify`/`cancel` throw (missing `POST_NOTIFICATIONS` grant, OEM
  quirk) so it never propagates into the orchestrator's `runEffect`
  (which would surface as `EffectFailure` and could cascade-cancel an
  active recording).

**NOTIF_ID reconciliation note (AC-3 / Spec 1 §10):** Before C4 the
**only** `NOTIF_ID` in the codebase was
`DictatePipelineService.companion.NOTIF_ID = 0xD1C7A7E` — the legacy
`1001` the spec warns about **never existed here**. C4 moves the
constant to the coordinator (Spec 1 §7.4 SoT) and removes the Service
companion definition. **No duplicate-notification conflict with the
still-live legacy recording path:** grep confirms
`DictateInputMethodService.java`, `JobExecutor.kt`,
`PipelineOrchestrator.kt` post **zero** notifications (the IME only
calls `startForegroundService` to *start* the service; it never
`notify`s). The new coordinator is the sole notification owner even
though the legacy recording *trigger* is still live pre-C5/C7 — so
**no `architecture-conflict`, no mid-chunk-triage needed** (the
condition the prompt flagged as a possible escalation does not
materialise: there is no competing legacy notification path to
reconcile).

**OQ-3 / FN-2 strings result:** `[Pause][Stopp][Senden]` (+ Resume,
Cancel, Insert, Discard) action strings + 5 state subtitles were
**absent** (only the unrelated `dictate_history_pause` existed).
**Added** (additive, mirrors F-5 locale discipline):
`values/strings.xml` (en) + `values-de/strings.xml` (de) —
`dictate_action_{pause,resume,stop,send,cancel,insert,discard}` +
`dictate_notif_{recording_active,recording_paused,processing,ready_to_insert,overlay_permission_required}`.
`values-es`/`values-pt` fall back to the en default via Android
resource resolution (consistent with the existing
`dictate_pipeline_notif_*` keys, which are also en/de-only).

**Files created/modified (production, Commit 1):**
- `app/src/main/java/net/devemperor/dictate/core/PipelineNotificationCoordinator.kt` (NEW)
- `app/src/main/java/net/devemperor/dictate/core/PipelineActionRouter.kt` (NEW)
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt` (Step-4 wiring, onStartCommand router-forward, onDestroy dismiss, NOTIF_ID SoT move, removed dup `buildInitialNotification`, dropped 3 now-unused imports)
- `app/src/main/java/net/devemperor/dictate/state/PipelineServiceStubSubsystems.kt` (demote `notificationCoordinator` `@Deprecated` + header KDoc refresh)
- `app/src/main/res/values/strings.xml` (en notification strings)
- `app/src/main/res/values-de/strings.xml` (de notification strings)

**Files in chunk-scope:** all six are named in Epic §4 B2 "Files".
**Files outside chunk-scope (drift):** none.

#### Plan-Correctness Fix (B2-C4-B2-IMPL-PLAN-FIX)

Plan-requirement check:

| Requirement (Epic §4 B2 / Spec 1 §7.4-§7.6/§11.1.2/§10) | Status |
|---|---|
| `core/PipelineNotificationCoordinator.kt` impl `PipelineNotificationCoordinatorSubsystem` | ✓ |
| `core/PipelineActionRouter.kt` (§7.5 back-channel) | ✓ |
| `show(Recording)` → `[Pause][Stopp][Senden]` FGS notification | ✓ (rendered; emission C5 — Dev-2) |
| `Pipeline` status → progress notification | △ (Dev-3 — generic subtitle, no F-13 counter) |
| `dismiss()` / `show(Idle)` → notification removed | ✓ |
| Wire into `onCreate` Step 4, replace stub | ✓ |
| R-2: reuse existing channel, no 2nd channel, channel-before-startForeground | ✓ |
| NOTIF_ID single SoT in coordinator | ✓ |
| OQ-3/FN-2 strings (de+en) | ✓ |
| Demote stub `@Deprecated` test-only | ✓ |
| Action-button PendingIntent → router → dispatch | ✓ |

**Files modified in this step:** none (Step 1 was plan-faithful).
**Files in plan-prescribed scope:** the six above.
**Files outside plan-prescribed scope (drift):** none.

#### Self-Code Fix (B2-C4-B2-IMPL-CODE-FIX)

Knowledge skills consulted: `knowledge-reference` (plugin-system /
versioned-envelope — neither applies; this is a subsystem **adapter**
implementing an existing interface, not a registry/persisted-schema).
Grounded instead in the project-established `core/*Adapter`
provider-lambda convention (read `PipelineRunnerSubsystemAdapter` +
`RecordingHardwareAdapter`): the router's `dispatchAction: (Action) ->
Unit` mirrors the established `emitAction` pattern. Aspects: DRY ✓
(single `action()` helper, 4 call-sites vs §11.1.2's 4 repeated
builders), exhaustive `when` over sealed `NotificationStatus` ✓, no
`!!`/unchecked casts ✓, `*Impl` field naming consistent with the
Service ✓, comments explain WHY (R-2, SoT, command-vs-reactive) ✓.
One inline fix: corrected a misleading "unreachable" comment on the
`build()` Idle branch (it **is** reached via `buildInitial()`;
`show(Idle)` short-circuits before `build`). No delegated items.

**Files modified in this step:** `PipelineNotificationCoordinator.kt`
(comment-only). Drift: none.

**Plan deviations:**

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| Dev-1: command-interface coordinator, not the §7.4 reactive `StateFlow` subscriber | Spec 1 §7.4 (`startReactiveUpdates`/`buildInitial`) | Implemented `PipelineNotificationCoordinatorSubsystem.show()/dismiss()` (the interface the shipped modular orchestrator actually drives via `PipelineModule`/`OverlayModule` effects) + `buildInitial()` for `startForeground`. §7.4's reactive subscriber is superseded by the command interface that B1 + the module system already established. | The `PipelineNotificationCoordinatorSubsystem` interface (command-style) is the production SoT in current code; modules push `NotificationStatus` via `Effect.UpdateNotification`/`DismissNotification`. A reactive subscriber would be a parallel, conflicting notification driver. User-visible result (the §7.6 table) is identical. | None — the command interface is stable and already consumed by C3-landed modules. | inline-fixed (mid-size, solution clear from the existing `ModuleServices` interface contract) → marker `plan-deviation-resolved` |
| Dev-2: `NotificationStatus.Recording` rendered `[Pause][Stopp][Senden]` but **no module emits it yet** | Spec 1 §7.6 (Recording-Active/-Paused rows) | Coordinator fully renders `Recording` → `[Pause][Stopp][Senden]` per §7.6 Recording-Active. But `RecordingModule` emits **no** notification effect (only `PipelineModule`/`OverlayModule` do), and the sealed `NotificationStatus` has no `Paused` variant. The Recording-Active/-Paused **emission** + a `Paused` variant are gated on C5 (the IME-trigger flip routes recording through the orchestrator). | Mirrors C3-B1's IMPL-1: the path does not exist yet *by design*. The coordinator side of §7.6 Recording is complete + tested (`show_recording_…` asserts the 3 buttons); only the *emission* from the recording FSM is C5-scoped. NOT an architecture-conflict. | C5 owns: (a) wire `RecordingModule` to emit `NotificationStatus.Recording`, (b) add a `Paused` variant if §7.6 Recording-Paused `[Resume][Stopp][Senden]` fidelity is required. | flagged-for-validate (issue `IMPL-1`, Important, marker `plan-deviation-resolved`, owner C5) |
| Dev-3: `Pipeline` notification subtitle is a generic "Processing …", not the F-13 step counter (`Schritt 2/4`) | Spec 1 §7.6 (`"Verarbeite (Schritt 2/4)"`) | The command interface's `NotificationStatus.Pipeline(sessionId, step: String)` carries a step *name* string, not the F-13 `completedSteps/totalSteps` counters (those live on `PipelineUiState.Running`, not on `NotificationStatus`). The coordinator renders the static `dictate_notif_processing` subtitle. | `NotificationStatus.Pipeline` does not carry the F-13 counters; threading them would require either a state-flow subscription (Dev-1 conflict) or extending the `NotificationStatus.Pipeline` payload + every `PipelineModule` emit-site. Out of C4's adapter scope; the §7.6 progress-text is cosmetic (the live counter already renders in the record-button label per A1 F-13). | C5/Theme-C or a follow-up may extend `NotificationStatus.Pipeline` with the counters if the notification progress-text fidelity is required. | flagged-for-validate (issue `IMPL-2`, Nice-to-have) |

**Issues (Steps 1-3):**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| IMPL-1 | Important | `NotificationStatus.Recording` is rendered with the §7.6 `[Pause][Stopp][Senden]` buttons but **no module emits it** (RecordingModule has no notification effect) and there is no `Paused` variant. C5 must (a) emit `NotificationStatus.Recording`/-Paused from the recording FSM when it flips the IME trigger, (b) add a `Paused` variant for §7.6 Recording-Paused fidelity. `PipelineNotificationCoordinator.build()` Recording arm + `RecordingModule` (no notif effect). | delegated-to-orchestrator → **C5 owns** | Prescribed owner is C5 (the IME-trigger flip routes recording through the orchestrator — Epic §4 B3). NOT an `architecture-conflict`: the path simply does not exist yet by design (mirrors C3-B1 IMPL-1). Marker `plan-deviation-resolved`. |
| IMPL-2 | Nice-to-have | `Pipeline` notification subtitle is generic "Processing …" not the §7.6 `Schritt X/Y` counter; `NotificationStatus.Pipeline` does not carry the A1 F-13 counters. `PipelineNotificationCoordinator.subtitleFor`. | delegated-to-orchestrator | Cosmetic — the live F-13 counter already renders in the record-button label (A1). Extending it needs a `NotificationStatus.Pipeline` payload change across every `PipelineModule` emit-site; out of C4 adapter scope. |

#### Tests (B2-C4-B2-IMPL-TEST)

Test-files written:
- `app/src/test/java/net/devemperor/dictate/core/PipelineNotificationCoordinatorTest.kt` (NEW, 12 tests — coordinator + router unit, Robolectric K-4 justified opt-out)
- `app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceRecordingDriveTest.kt` (NEW, 4 tests — production-wiring integration, binder-harness + R-7 tearDown per `b5-ime-activation-wiring.md` §8)

Test → AC mapping:
| Test | Asserts |
|---|---|
| `show_recording_postsNotificationWithPauseStopSendActions` | AC-2: §7.6 Recording → 3 buttons `[Pause][Stopp][Senden]` |
| `show_pipeline_postsProgressNotificationWithCancelAction` | AC-2: §7.6 Pipeline → `[Abbrechen]` |
| `show_overlayPermissionRequired_postsNotificationWithoutActions` | OverlayModule §9 permission-revoke surface |
| `buildInitial_isNeutralReadyNotificationWithNoActions` | §7.4 buildInitial (FGS-start render) |
| `dismiss_removesThePostedNotification` / `show_idle_dismissesInsteadOfPosting` | AC-2: dismissed on Idle |
| `recordingActionButtons_pendingIntentsDispatchTheCorrectActions` | AC-2: PendingIntent → router → correct `RecordingAction.*` |
| `pipelineCancelButton_pendingIntentDispatchesCancelPipeline` | AC-2: `[Abbrechen]` → `PipelineAction.CancelPipeline` |
| `router_decodesResumeStopSendInsertDismiss` | §7.5 full action-string → Action mapping |
| `router_ignoresNullAndUnknownIntents` | router robustness (null/unknown/INSERT-no-session) |
| `router_pendingIntentForResultButtons_isDistinctPerSession` | result-button request-code collision defence |
| `notifId_isThePreservedCanonicalValue` | AC-3: NOTIF_ID == 0xD1C7A7E (no drift) |
| `moduleServices_notificationCoordinator_isTheRealCoordinator` | AC-2/AC-3: production wiring → real coordinator, NOT stub |
| `channelIsCreatedBeforeStartForeground_andUsesSoTNotifId` | R-2: channel-order + SoT NOTIF_ID via startForeground |
| `triggerPipeline_postsTheProcessingNotification_throughTheRealPath` | AC-2: orchestrator TriggerPipeline → real notification |
| `pipelinePersistenceError_dismissesTheNotification` | AC-2: DismissNotification effect → cancelled |

Helper-Decisions: reused `Robolectric.buildService` + binder-harness +
R-7 `DictateDatabase.resetForTest`/`JobExecutor.resetForTest` tearDown
from `DictatePipelineServiceOverlayTransitionTest` (no new helper
needed — the capturing `dispatchAction` lambda is the only fake, K-1
handwritten, no Mockito/MockK).

**Test-Run-Result:** ✅ 16/16 pass. Full suite: **987 tests, 0
failures, 0 errors** (≥971 C3-B1 baseline; +16 = exactly the new
tests — AC-9 regression invariant holds, no cross-chunk regression).
`./gradlew assembleDebug` green.

**Issues (Steps 4-5):** none.

#### Code-Bugs Found While Writing Tests

No **production** code-bugs. One **test-compile-compat fix forced by
the production NOTIF_ID SoT move** (not a logic bug — a mechanical
consequence of Spec 1 §10's mandated constant relocation):

| File:Line | Symptom | Root-Cause | Fix (before → after) | Research |
|-----------|---------|-----------|----------------------|----------|
| `DictatePipelineServiceTest.kt:193`, `DictatePipelineServicePreApi34Test.kt:73` | compile error: `DictatePipelineService.NOTIF_ID` unresolved | C4 moved the canonical `NOTIF_ID` to `PipelineNotificationCoordinator` (Spec 1 §10 NOTIF_ID-Konsistenz SoT) and removed the Service companion const | `DictatePipelineService.NOTIF_ID` → `PipelineNotificationCoordinator.NOTIF_ID` (same package, no import; same value `0xD1C7A7E`) | Spec 1 §10 "NOTIF_ID-Konsistenz" — the constant relocation is the explicit acceptance requirement; these two pre-existing tests assert `startForeground` uses the documented id and now read it from the SoT |

#### Test-Review (B2-C4-B2-IMPL-TEST-FIX)

Self-review: all AC-2/AC-3 surfaces covered; all 4 `build()` sealed
branches (Idle/Recording/Pipeline/OverlayPermissionRequired) exercised;
edge cases covered (null/unknown/malformed intents, INSERT-without-
session guard, per-session PendingIntent collision, buildInitial,
show(Idle)→dismiss). Test names describe behaviour; assertions are
specific (exact action titles + counts, not bare non-null). No weak
assertions, no snapshots. Coverage of the C4 production diff is
branch-complete by inspection (the two delegated deviations Dev-2/Dev-3
are documented gaps, not test holes — the rendered behaviour that
*does* exist is fully asserted). No additional tests needed; no
quality fixes; no further code-bugs. Final run: all green.

**Mid-Chunk-Triage** *(ARMED for this chunk)*: **Not triggered.** R-2
resolved in-chunk (channel-order/FGS-5s/NOTIF_ID all green; no second
channel; `notify` failures isolated). The legacy-path duplicate-
notification concern the prompt flagged as a possible
`architecture-conflict` **does not materialise**: grep proves the
legacy IME/JobExecutor/PipelineOrchestrator post no notifications, so
there is no competing path to reconcile and the new coordinator is the
sole owner even with the legacy recording trigger still live (C5/C7
own the flip/delete). No `architecture-conflict` / `blocks-following-
chunks` marker.

| Triggering Issue | Step | Research Topic | Repair Agent-ID | Wave-Commit | Outcome |
|------------------|------|----------------|------------------|-------------|---------|
| — | — | — | — | — | — |

**Overlooked / Known Gaps:**
- `NotificationStatus.Recording` emission from `RecordingModule` + a
  `Paused` variant (§7.6 Recording-Paused `[Resume][Stopp][Senden]`)
  are C5's scope (IMPL-1) — by design, not a gap to fix here.
- `Pipeline` notification step-counter text (IMPL-2) — cosmetic; the
  live F-13 counter already renders in the record-button label.
- POST_NOTIFICATIONS runtime-prompt (Spec 1 §11.5.1) is an
  `OnboardingActivity` concern, explicitly out of C4 scope (the IME
  edit is C5; onboarding is a separate block). The manifest already
  declares `POST_NOTIFICATIONS` (verified). `show()` is hardened to
  no-op-and-log if the grant is missing so a denied permission cannot
  crash the orchestrator.
- No `LocalBinder` accessor added for the coordinator/router (no
  consumer yet; fields held for any future C5 reach).

**Commit boundaries (orchestrator splits — lists disjoint):**
- **Commit 1 (production):** `core/PipelineNotificationCoordinator.kt`,
  `core/PipelineActionRouter.kt`, `core/DictatePipelineService.kt`,
  `state/PipelineServiceStubSubsystems.kt`, `res/values/strings.xml`,
  `res/values-de/strings.xml`
- **Commit 2 (tests):**
  `test/.../core/PipelineNotificationCoordinatorTest.kt`,
  `test/.../core/DictatePipelineServiceRecordingDriveTest.kt`,
  `test/.../core/DictatePipelineServiceTest.kt`,
  `test/.../core/DictatePipelineServicePreApi34Test.kt`

---

### Chunk C5-B3 — IME recording-trigger flip (guarded fallback)

**Agent-IDs:** `B2-C5-B3-IMPL` (fresh, combined Steps 1-5).
**Status:** ✅ complete · **Risk:** HIGHEST (R-1/R-4) — **R-1 verdict: GREEN (field-faithful); R-4 verdict: GREEN (no double-dispatch)**
**Implementation-Commit (Commit 1):** ⏳ (orchestrator) · **Test-Commit (Commit 2):** ⏳ (orchestrator)

#### Implementation (B2-C5-B3-IMPL)

**What was done:** Flipped `DictateInputMethodService.java`'s recording
trigger from the legacy `recordingStateController` + `JobExecutor.INSTANCE.start`
to `pipelineBinder.dispatch(RecordingAction.StartRecording/StopRecordingAndSend)`,
**behind a compile-time `USE_LEGACY_RECORDING_DRIVE` guard (default `false`
= new path active)**. On the new path the orchestrator's RecordingModule
drives the real `RecordingHardwareAdapter` MediaRecorder (Idle→Preparing→
Active), the C4 coordinator shows the §7.6 FGS notification, and
StopRecordingAndSend → `EmitPipelineTrigger` → `TriggerPipeline` →
`SubmitPipeline` → the C3 `PipelineRunnerSubsystemAdapter` → an
IME-faithful `JobRequest`. Closed all 4 delegated issues (C3-IMPL-1/-2,
C4-IMPL-1; C4-IMPL-2 stays postponed). Build + 1009 tests green.

**Boolean home + default + rationale (AC-10 / R-4 / OQ-2 / FN-3):**
`private static final boolean USE_LEGACY_RECORDING_DRIVE = false;` in
`DictateInputMethodService.java`. Compile-time `static final` (not a
`DictatePrefs` entry / `BuildConfig`): the cutover is literally "one
boolean away", the dead branch is reviewer-visible, no migration
overhead — matches Epic §6.2 wording. Default `false` so C6-D2pre can
verify the new path; legacy reachable by flipping to `true` (byte-for-
byte pre-C5 in every guarded branch). Removed in C7 after C6 green
(FN-3 / OQ-2 — not C5's job).

**C3-IMPL-1 fidelity (all 8 fresh fields threaded 1:1 — R-1 GREEN):**
The IME computes the 8 IME-runtime fields at the send-tap (the legacy
trigger instant, identical timing) in `captureFreshConfigSnapshot`,
stashes them in `ImePipelineConfigResolver` keyed by the
`preAllocatedId`, and the orchestrator's async `SubmitPipeline` →
`DelegatingPipelineConfigResolver.resolveFresh` rebuilds the
`JobRequest` field-for-field:

| Legacy IME field (pre-C5 `:2214-2230`) | C5 source | Status |
|---|---|---|
| `preAllocatedId` | `StartRecording.sessionId` minted in `startRecording()`, carried by the FSM (F-10) | ✅ 1:1 |
| `totalSteps` | `1 + autoFormatting + promptQueue.size` (same expr) | ✅ 1:1 |
| `audioFilePath` | `audioFile.getAbsolutePath()` (same allocated file) | ✅ 1:1 |
| `language` | `languageController.getEffectiveLanguage()` → null on "detect" (READ only; D-13/C8 removes the writer later) | ✅ 1:1 |
| `stylePrompt` | `promptService.resolveWhisperStylePrompt(effectiveLanguage)` | ✅ 1:1 |
| `queuedPromptIds` | `promptQueueManager.getQueuedIds()` | ✅ 1:1 |
| `targetAppPackage` | `getCurrentInputEditorInfo().packageName` | ✅ 1:1 |
| `livePrompt` | IME instance flag (captured before reset) | ✅ 1:1 |
| `autoSwitchKeyboard` | IME instance flag (captured before reset) | ✅ 1:1 |
| `showResendButton` | `LastFileName.exists() && Pref.ResendButton` | ✅ 1:1 |
| `modelOverride`/`reuseSessionId`/`recordingsDir`/`origin`/`kind` | constants/null exactly as legacy | ✅ 1:1 |

`pendingLivePromptChain = livePrompt; livePrompt=false; autoSwitchKeyboard=false`
post-snapshot mirrors the legacy `:2232-2234` one-shot-flag reset, so
the live-prompt chain (`onPipelineCompleted` callback — still fires, the
pipeline body is still `PipelineOrchestrator` per Spec §9.6) works
unchanged.

**C3-IMPL-2 (reprocess) closed:** `ImePipelineConfigResolver.snapshotReprocess`
threads `selectedModel`/`targetAppPackage`/`totalSteps` (incl. the
AutoFormatting +1); the new-path branch in `handleReprocessSend` routes
via `pipelineBinder.getModuleServices().getPipelineRunner().submitReprocess(...)`
(the C3 adapter, which calls JobExecutor.start internally — not an IME
site). Falls back to the C3 default when no snapshot (staging-FSM path
the IME does not flip in C5).

**C4-IMPL-1 (notification emission) closed:** added
`NotificationStatus.Paused`, the coordinator `Paused` arm
(`[Resume][Stopp][Senden]` + `dictate_notif_recording_paused`), and
`RecordingModule.Effect.UpdateNotification`/`DismissNotification`
emitted across the FSM. The Recording→Pipeline hand-off is seamless:
`StopRecordingAndSend` deliberately does **NOT** dismiss — the
`EmitPipelineTrigger → TriggerPipeline` cascade has PipelineModule
immediately re-`show()` a `Pipeline` status on the same NOTIF_ID (no
flicker, no FGS-less window — R-2-safe).

**AC-10 no-double-dispatch grep table (R-4 GREEN):**

| Site | File:line (post-C5) | `USE_LEGACY_RECORDING_DRIVE` true-branch | false-branch | Double-dispatch? |
|---|---|---|---|---|
| #1 fresh recording (`runTranscriptionViaOrchestrator`) | `DictateInputMethodService.java:2596` | `JobExecutor.INSTANCE.start(request)` (legacy, byte-identical) | suppressed + `uiController.stopPipeline()` + `Log.w` (method is unreachable on new path — only the legacy `onRecordingCompleted` callback reaches it; new path dispatches `StartRecording`/`StopRecordingAndSend` at `startRecording()`/`stopRecording()` instead) | **NO** — mutually exclusive on the boolean; the new-path trigger is `StopRecordingAndSend` (a different method), legacy is `JobExecutor.start` |
| #2 RESUME (`startResumeJob`) | `:3286` | `JobExecutor.INSTANCE.start(Resume)` | `JobExecutor.INSTANCE.start(Resume)` (no orchestrator resume action exists — single-dispatch, documented C5-IMPL-3) | **NO** — single-dispatch; orthogonal to the fresh-recording flip (recovery path, distinct user action) |
| #3 REPROCESS_STAGING (`handleReprocessSend`) | `:3463` | `JobExecutor.INSTANCE.start(REPROCESS_STAGING)` | `pipelineRunner.submitReprocess(...)` via the C3 adapter (closes C3-IMPL-2) | **NO** — mutually exclusive on the boolean; single-dispatch each branch |
| new-path fresh trigger | `:2334` `StartRecording`, `:2383` `StopRecordingAndSend` | (not reached — legacy `recordingStateController` path) | `pipelineBinder.dispatch(StartRecording/StopRecordingAndSend)` | **NO** — the false-branch dispatch and the true-branch `JobExecutor.start` are guarded by the same boolean; no user action reaches both |

**Audit conclusion:** every IME `JobExecutor.INSTANCE.start` is fenced
by `USE_LEGACY_RECORDING_DRIVE`; every new-path
`dispatch(StartRecording/StopRecordingAndSend)` is the false-branch of
the same boolean at the recording-lifecycle methods. No user action
triggers both a legacy `JobExecutor.start` and a new `StartRecording/
StopRecordingAndSend` dispatch for the same recording. RESUME is
single-dispatch legacy in both branches by design (no orchestrator
equivalent — C5-IMPL-3, not the fresh-recording R-1/R-4 path). **R-4
GREEN — no double-dispatch.**

**Record-button gating migrated to orchestrator state:** the
record-button start/stop decision (`onRecordClicked`,
`onRecordLongClicked`, the instant-prompt path, prompt-queue toggle,
`onSettingsClicked` cancel, `onPauseClicked`, `onTrashClicked`) reads
`isEffectiveRecording{Idle,ActiveOrPaused,InFlight}()` /
`cancelEffectiveRecording()` / `togglePauseEffectiveRecording()` —
these consult the orchestrator `state.recording` on the new path
(legacy `recordingStateController` is never started there) and the
legacy controller on the legacy path. Two distinct `RecordingState`
types (`core.*` legacy vs `state.*` orchestrator) → boolean predicates,
not a unified object.

**Files created/modified (production, Commit 1):**
- `app/src/main/java/net/devemperor/dictate/state/ModuleServices.kt` (NEW `NotificationStatus.Paused`)
- `app/src/main/java/net/devemperor/dictate/core/PipelineNotificationCoordinator.kt` (`Paused` arm in `build()` + `subtitleFor()`)
- `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt` (NEW `Effect.UpdateNotification`/`DismissNotification` + emissions across the FSM + `runEffect` arms)
- `app/src/main/java/net/devemperor/dictate/core/PipelineRunnerSubsystemAdapter.kt` (NEW `DelegatingPipelineConfigResolver`; `resolveReprocess` refactored to a shared `buildReprocess`)
- `app/src/main/java/net/devemperor/dictate/core/ImePipelineConfigResolver.kt` (NEW — R-1 IME-faithful resolver + fresh/reprocess snapshots)
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt` (DelegatingPipelineConfigResolver wiring, `delegatePipelineConfigResolver` field + `registerPipelineConfigResolver`)
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` (the guarded flip: `USE_LEGACY_RECORDING_DRIVE` const, resolver fields + register/unregister, `startRecording`/`stopRecording` flip, `captureFreshConfigSnapshot`/`primePipelineUiForNewPath`, `isEffectiveRecording*`/`cancelEffectiveRecording`/`togglePauseEffectiveRecording` helpers + their gating call-sites, the 3 guarded `JobExecutor.start` sites + the reprocess new-path route)

**Files in chunk-scope:** all named in Epic §4 B3 / the C5 prompt.
**Files outside chunk-scope (drift):** none.

**Plan deviations:**

| Deviation | Plan Location | What changed | Why | Impact | Resolved? |
|-----------|---------------|--------------|-----|--------|-----------|
| Flip the **record-button start/stop** (`startRecording`/`stopRecording`), not just the post-record `JobExecutor.start` trigger | Epic §4-B3 ("flip the recording trigger from `JobExecutor.start` (:2236) to `dispatch(StartRecording)`") | The `:2236` site fires from the legacy `onRecordingCompleted` callback AFTER the legacy recorder already produced audio; dispatching `StartRecording` there would re-allocate+restart a 2nd MediaRecorder over the recorded file (R-1 catastrophic). Spec 1 §15.2 (`StartRecording → AllocateMediaRecorder → RecordingManager.start(file)`) + AC-2 (`state.recording` Idle→Preparing→**Active**) confirm the orchestrator owns the *whole* recording, so the flip belongs at the button. The 3 `JobExecutor.start` sites are still guarded (FN-1) for the AC-10 audit + C7 deletion. | The orchestrator's `RecordingHardwareAdapter` becomes the sole recorder on the new path; legacy `recordingStateController` is the guarded fallback. C6 verifies; C7 deletes legacy. | inline-fixed (mid-size; solution clear from Spec §15.2 + AC-2 + the R-1 directive) → marker `plan-deviation-resolved` |
| RESUME (`startResumeJob`) kept legacy in **both** boolean branches | FN-1 ("C5 guards all three; … C6 grep covers all three") | `PipelineRunnerSubsystem` has `submit`/`submitReprocess`/`cancel` but no `resume`; adding one is an architecture change beyond C5 and the prompt explicitly forbids a fragile flip. RESUME is the recovery path, single-dispatch, orthogonal to the fresh-recording R-1/R-4 cutover. | C5-IMPL-3 (postponed, Nice-to-have). C7/later owns retiring it. AC-10 still holds (single-dispatch, not double). | flagged C5-IMPL-3 |

#### Plan-Correctness Fix (B2-C5-B3-IMPL-PLAN-FIX)

Plan-requirement check (C5 prompt AC-2/AC-3/AC-10 + FN-1..FN-4 + F-7):

| Requirement | Status |
|---|---|
| Guard ALL 3 `JobExecutor.start` sites behind one boolean (FN-1) | ✓ (fresh `:2596`, resume `:3286`, reprocess `:3463`; resume both-branches-legacy by documented necessity) |
| `StartRecording(target, audioFile, preAllocatedId)` then payload-less `StopRecordingAndSend()` (FN-4) | ✓ |
| Non-blank real `preAllocatedId` (F-7 `require(isNotBlank())`) | ✓ (UUID minted in `startRecording`, never `""`) |
| Boolean default `false` = new path active (for C6) | ✓ |
| No code path does BOTH legacy + new for one action (AC-10 / R-4) | ✓ (grep table above) |
| `pipelineBinder` null → handled (logged-skip, legacy authoritative) | ✓ (defensive bail in `stopRecording` new path + `effectiveRecording*` fall through to legacy) |
| C3-IMPL-1 — 8 fresh fields threaded 1:1 (R-1) | ✓ (fidelity table) |
| C3-IMPL-2 — reprocess modelOverride/targetApp/AutoFormatting | ✓ |
| C4-IMPL-1 — emit Recording/Paused + add `Paused` variant | ✓ |
| C4-IMPL-2 — Pipeline step counters | △ postponed (cosmetic, payload refactor out of scope) |
| `language` still READ from `LanguageController` (D-13/C8 later) | ✓ (READ only, not removed) |
| `DictateInputMethodService.java` stays Java; new helpers Kotlin | ✓ |
| `assembleDebug` + `test` green (≥987 baseline) | ✓ (1009 tests, 0 failures) |

**Files modified in this step:** none (Step 1 was plan-faithful; the
record-button-flip deviation is documented + marker-flagged).
**Files outside plan-prescribed scope (drift):** none.

#### Self-Code Fix (B2-C5-B3-IMPL-CODE-FIX)

Knowledge skills consulted: `knowledge-reference` (plugin-system /
versioned-envelope — N/A; this is subsystem-adapter + provider-lambda
seam work, not a registry/persisted schema). Grounded in the
project-established `core/*Adapter` provider-lambda + `LocalBinder.delegate*`
`@Volatile` register-pattern (read `PipelineRunnerSubsystemAdapter`,
`PipelineActionRouter`, `RecordingHardwareAdapter`, the existing
`registerInputConnectionProvider`). Aspects: DRY ✓ (`buildReprocess`
extracted so the C3 default + IME resolver don't duplicate the reprocess
JobRequest; `captureFreshConfigSnapshot`/`primePipelineUiForNewPath`
factor the new-path bookkeeping out of `stopRecording`); the new
`DelegatingPipelineConfigResolver`/`ImePipelineConfigResolver` mirror
the established lambda-seam + `@Volatile` delegate convention; boolean
predicates (not a fake unified `RecordingState`) given the two distinct
state types — type-safe; comments explain WHY (R-1, R-4, the two
RecordingState types, the seamless hand-off, the resume-no-equivalent
decision). No `!!`/unchecked casts. One inline call-out: the resume
both-branches-legacy is documented as C5-IMPL-3 rather than silently
duplicating. No additional delegated items beyond C5-IMPL-1/-2/-3.

**Files modified in this step:** none beyond Step 1 (the DRY
`buildReprocess` extraction + helper factoring were applied during
Step 1 as the code was written). Drift: none.

#### Tests (B2-C5-B3-IMPL-TEST) + Test-Review (B2-C5-B3-IMPL-TEST-FIX)

Test-files (Commit 2):
- `app/src/test/java/net/devemperor/dictate/core/ImePipelineConfigResolverTest.kt` (NEW, 12 pure-JVM tests — fresh fidelity, snapshot-consume, fallback-throw, discard, reprocess C3-IMPL-2, DelegatingResolver delegate/fallback/late-bind)
- `app/src/test/java/net/devemperor/dictate/core/ImeRecordingDriveCutoverTest.kt` (NEW, 5 Robolectric tests — StartRecording→Active+notification, Pause/Resume notif swap, Cancel→dismiss, StopRecordingAndSend→resolver with FSM sessionId + field-faithful JobRequest, seamless Recording→Pipeline hand-off)
- `app/src/test/java/net/devemperor/dictate/state/RecordingModuleTest.kt` (UPDATED 3 existing effect-count assertions for the intended notification effects + 5 NEW C5 notification-emission tests)
- `app/src/test/java/net/devemperor/dictate/core/PipelineNotificationCoordinatorTest.kt` (1 NEW — `Paused` arm `[Resume][Stopp][Senden]` + subtitle)

**Test-Run-Result:** ✅ **1009 tests, 0 failures, 0 errors** (987 C4
baseline + 22 net new; AC-9 regression invariant holds). `./gradlew
assembleDebug` green. Robolectric K-4 justified opt-out (Service/IME
binder wiring); K-1 handwritten fakes only (capturing coordinator +
instrumented resolver; no Mockito/MockK). Test self-review: all
AC-2/AC-3/AC-10 surfaces + every new FSM arm + the R-1
field-by-field + the DelegatingResolver late-bind covered; test
boundary for the deep 5-hop runner leg is the resolver call (the
resolver→JobExecutor leg is C3's tested territory + the C6-D2pre E2E
gate's job — asserting it through 5 async hops + a JobExecutor worker
thread is brittle, so the C5 test boundary is the R-1 surface).

#### Code-Bugs Found While Writing/Reviewing Tests

No **production** code-bugs. Test-only fixes (mechanical, documented):

| File:Line | Symptom | Root-Cause | Fix (before → after) | Research |
|---|---|---|---|---|
| `RecordingModuleTest.kt` 3 existing effect-count tests | `expected:<4> but was:<5>` | Intended C4-IMPL-1 production change adds 1 notification effect to MediaRecorderReady/Pause/Stop arms | updated the 3 assertions to `5` + assert the new `UpdateNotification`/`DismissNotification` (the new behaviour is correct per C4-IMPL-1) | C4-IMPL-1 directive + Spec 1 §7.6 |
| `ImeRecordingDriveCutoverTest.kt` | `createTempFile("c5"…)` `prefix too short` | JDK `createTempFile` requires prefix ≥3 chars | `"c5"` → `"c5rec"` | JDK `File.createTempFile` contract |
| `ImeRecordingDriveCutoverTest.kt` 2 tests | async chain not drained / `JobExecutor.INSTANCE` unresolved in Kotlin | single `idleMainLooper()` insufficient for the 5-hop `emitAction` chain; `JobExecutor` is a Kotlin `object` | added `pumpUntil { … }` looper-pump helper + waited for `RecordingState.Active` before send; `JobExecutor.INSTANCE.cancel` → `JobExecutor.cancel`; narrowed the runner-leg assertion to the resolver-call boundary (C3/C6 own the runner leg) | `JobExecutorTest.waitForRegistryEmpty` spin pattern; `object JobExecutor` |

#### Mid-Chunk-Triage *(ARMED for this chunk)*: **Not triggered.**

R-1 resolved in-chunk (the `ImePipelineConfigResolver` threads all 8
fields field-faithfully; the throw-fallback keeps the no-IME case
fail-loud). R-4 resolved (the guarded fallback is genuinely
mutually-exclusive — grep table). The record-button-flip deviation was
mid-size with a clear Spec-§15.2 + AC-2 solution (inline-fixed +
marker, not an architecture-conflict). The AudioFocus/BT-SCO gap
(C5-IMPL-1) is a **pre-existing dormant-layer** incompleteness surfaced
by the live path, NOT a C5-introduced architecture-conflict and NOT a
blocker for C6/C7 (the guarded fallback explicitly protects against it;
C6-D2pre is the verification gate where it gets caught before C7 deletes
legacy) — delegated `Important`, no `architecture-conflict` /
`blocks-following-chunks` marker, so no mid-chunk-triage per the
prompt's own criterion ("R-1 silent-data-loss" — here the JobRequest IS
field-faithful; the gap is audio-focus/BT quality-of-experience the
boolean fallback covers, not silent config loss).

| Triggering Issue | Step | Research Topic | Repair Agent-ID | Wave-Commit | Outcome |
|------------------|------|----------------|------------------|-------------|---------|
| — | — | — | — | — | — |

**Overlooked / Known Gaps:**
- **C5-IMPL-1** (AudioFocus + BT-SCO not wired on the new path) —
  pre-existing dormant-layer gap; guarded fallback covers it; C6/follow-up owns.
- **C5-IMPL-2** (legacy recording UI/animation/keyboard-hide-pause
  sites stay legacy-Idle on the new path) — RenderBackend recording-UI
  migration is Theme-C/C3; FGS notification is the authoritative
  new-path surface.
- **C5-IMPL-3** (RESUME has no orchestrator equivalent — both branches
  legacy) — single-dispatch, orthogonal to the fresh-recording cutover.
- **C4-IMPL-2** (Pipeline notif step-counter) — still postponed
  (cosmetic; needs a `NotificationStatus.Pipeline` payload refactor).
- The deep `SubmitPipeline → JobExecutor runner` E2E is intentionally
  C6-D2pre's gate (the C5 test boundary is the resolver call — the
  R-1 surface; the runner leg is C3-tested).
- `LegacyAudioFileMigrationTest` is a **pre-existing R-7 test-pollution
  flake** (Epic R-7; order-dependent, different method each run, passes
  in isolation + on full-suite re-run) — NOT caused by C5.

**Commit boundaries (orchestrator splits — lists disjoint):**
- **Commit 1 (production):**
  `state/ModuleServices.kt`, `state/modules/RecordingModule.kt`,
  `core/PipelineNotificationCoordinator.kt`,
  `core/PipelineRunnerSubsystemAdapter.kt`,
  `core/ImePipelineConfigResolver.kt`,
  `core/DictatePipelineService.kt`,
  `core/DictateInputMethodService.java`
- **Commit 2 (tests):**
  `test/.../core/ImePipelineConfigResolverTest.kt`,
  `test/.../core/ImeRecordingDriveCutoverTest.kt`,
  `test/.../state/RecordingModuleTest.kt`,
  `test/.../core/PipelineNotificationCoordinatorTest.kt`

---

### Chunk C6-D2pre — VERIFICATION GATE (authorises C7 + Theme C)

**Agent-IDs:** `B2-C6-D2pre-IMPL` · **Status:** ✅ complete · **Risk:** Gate

> **➡ REPAIR-RESOLUTION (2026-05-15):** the RED verdict below is the
> gate's *original* judgement, kept verbatim for audit-trail. The
> blocking finding **C6-IMPL-1 is now fixed by repair-wave B2-C6-W1**
> (see `### Gate-Repair Wave B2-C6-W1`). A fresh orchestrator C6-D2pre
> re-run (re-trace audio-focus/BT-SCO on the new path + re-run the
> auto-tier) is the authorising step for C7/Theme-C — the repair makes
> that re-gate able to go GREEN; it does not retroactively flip this
> historical verdict.

**GATE OUTPUT:** **🔴 GATE: RED** — C7 (legacy-call-site deletion) + ALL of
Theme C (C8/C9/C10) remain **GATED / NOT authorised**. The auto-tier is
fully green and the new path is structurally proven, but a real
**legacy-parity functional regression** (C5-IMPL-1: audio-focus +
Bluetooth-SCO) would ship the moment C7 deletes the guarded legacy
fallback. The guarded fallback exists precisely so this does not have to
be rushed (Epic §6.2: "proven, not assumed"). Repair via repair-sub-phase
BEFORE C7/Theme-C.

#### What was done

Ran the C6-SUBSET verification gate: full `./gradlew test` +
`assembleDebug`, the AC-10 grep audit (all 3 `JobExecutor.start` sites +
the new-path dispatch), a code-trace of the legacy-vs-new audio-focus /
Bluetooth-SCO behaviour (the C5-IMPL-1 gate question), and authored
`DictateCutoverE2ETest.kt` (7 Robolectric tests) aggregating the keystone
F-1/F-2/F-3 + Triangle-FSM T1-T7 **on the new live recording path**
(`USE_LEGACY_RECORDING_DRIVE=false`) + AC-2/AC-3 structurally. The C6-SUBSET
manual device-attached TCs are described below as manual-pending (no
device, "ohne Walkthrough") — the gate verdict rests on the auto-tier +
the per-criterion gap assessment, per the prompt.

#### Auto-tier results (the provable part — all GREEN)

| Check | Result |
|---|---|
| `./gradlew test` (full suite, debug) | **1016 tests, 0 failures, 0 errors, 0 skipped** (1009 C5 baseline + 7 new `DictateCutoverE2ETest`; ≥946 AC-9 invariant holds, no cross-chunk regression) |
| `./gradlew test` (release variant) | 1009 tests, 0 failures, 0 errors, 0 skipped |
| `./gradlew assembleDebug` | **BUILD SUCCESSFUL** (AC-9 build invariant) |
| R-7 `LegacyAudioFileMigrationTest` | 8 tests, **0 failures** in the full run — the known R-7 pollution flake did NOT manifest (order-dependent pollution, not a C-block regression; the new test's `DictatePipelineServiceOverlayTransitionTest`-mirrored DB/JobExecutor tearDown did not amplify it) |
| `DictateCutoverE2ETest.kt` (NEW, 7 tests) | **7/7 pass** — keystone boot→KEYBOARD, AC-2 (Active + §7.6 `[Pause][Stopp][Senden]` notification), T1/T2 widget round-trip, T3/T5 (HOVER driven by a **real** new-path recording Active, recording survives the keyboard switch — ADR-0003), T4 (WIDGET→HOVER keeps backend), AC-3/T7 (StopRecordingAndSend → notification Recording→Pipeline→Idle, real Pipeline-Done cascade HOVER→KEYBOARD = Geist-Widget guard), AC-2 cancel→dismiss |

**AC-10 no-double-dispatch grep audit (all 3 `JobExecutor.start` sites — FN-1):**

| Site | File:line | `USE_LEGACY_RECORDING_DRIVE` true-branch | false-branch (new path) | Double-dispatch? |
|---|---|---|---|---|
| `startRecording()` button-flip | `DictateInputMethodService.java:2313`/`:2318` (legacy) → `:2334` (new) | `recordingStateController.startRecording(...)` + `return` | `pipelineBinder.dispatch(RecordingAction.StartRecording(...))` | **NO** — `return` after legacy makes them strictly mutually exclusive |
| `stopRecording()` button-flip | `:2341`/`:2343` (legacy) → `:2382` (new) | `recordingStateController.stopRecording()` + `return` | `pipelineBinder.dispatch(StopRecordingAndSend.INSTANCE)` | **NO** — `return` after legacy; mutually exclusive |
| #1 fresh `runTranscriptionViaOrchestrator` | `:2596` | `JobExecutor.INSTANCE.start(request)` | else-branch **suppresses** + `Log.w` "no double-dispatch, AC-10" (only reached via legacy `onRecordingCompleted`; new path never starts the legacy recorder) | **NO** — explicit anti-double-dispatch else-arm |
| #2 RESUME `startResumeJob` | `:3286` (true) / `:3294` (false) | `JobExecutor.INSTANCE.start(Resume)` | `JobExecutor.INSTANCE.start(Resume)` (no orchestrator resume action exists — C5-IMPL-3) | **NO** — single-dispatch both branches; recovery path, orthogonal to fresh-recording cutover |
| #3 REPROCESS_STAGING `handleReprocessSend` | `:3463` (true) / `:3488` (false) | `JobExecutor.INSTANCE.start(REPROCESS_STAGING)` | `pipelineRunner.submitReprocess(...)` via the C3 adapter (calls JobExecutor.start *internally* — not an IME site) | **NO** — mutually exclusive on the boolean; single-dispatch each branch |

**AC-10 verdict: GREEN.** The C5 grep table is accurate and verified
independently. Every IME `JobExecutor.start` is fenced by
`USE_LEGACY_RECORDING_DRIVE` (default `false` = new path); every new-path
`dispatch(StartRecording/StopRecordingAndSend)` is the false-branch of the
same boolean. No user action reaches both a legacy `JobExecutor.start` and
a new dispatch for the same recording. The single-architecture invariant
holds for the gated step.

**Keystone + T1-T7-on-new-path trace result: GREEN.** The parent plan's
keystone F-1/F-2/F-3 chain still boots to `ViewMode.KEYBOARD` after the
recording-drive flip. The Triangle-FSM transitions are re-proven driven by
a **real new-path recording** (`state.recording` Active, not a synthetic
`PipelineAction.TriggerPipeline`): T3/T4 HOVER fire because
`pipelineActive = pipeline !is Idle || recording.isActiveOrPaused` is true
from a genuine cutover-path recording; T7 (Geist-Widget guard) fires from
a real `PipelineDone` cascade. The notification lifecycle
Recording→Pipeline→Idle is asserted on the new path. AC-2/AC-3 structurally
proven.

#### THE GATE ASSESSMENT (the load-bearing judgement)

**Criterion C5-IMPL-1 (Important) — audio-focus + Bluetooth-SCO on the new
path. VERDICT: 🔴 RED-BLOCKING.**

Code-trace evidence (not assumption):

- **Legacy path requests audio-focus.** `RecordingStateController.proceedStartRecording`
  (`:326`) calls `gate.request()` *before* `recordingManager.start()`, and
  `gate.abandon()` on stop/pause/cancel (`:150,:168,:221,:244,:331`).
  `Pref.AudioFocus` **defaults to `true`** (`DictatePrefs.kt:30`) → the
  legacy path requests audio-focus for **every recording, for 100% of
  users by default**.
- **Legacy path starts Bluetooth-SCO.** `RecordingStateController.startRecording`
  (`:134-136`) calls `bluetoothScoManager.startSco(2500)` and, on
  `onScoConnected` (`:300-303`), records with
  `MediaRecorder.AudioSource.VOICE_COMMUNICATION` (the BT-headset mic);
  `onScoFailed` falls back to `MIC`. `Pref.UseBluetoothMic` defaults to
  `false` (`DictatePrefs.kt:31`) → affects only users who explicitly
  enabled the BT mic, but for them the BT-headset mic genuinely routes.
- **New path requests NEITHER.** `RecordingHardwareAdapter.allocate`
  (`core/RecordingHardwareAdapter.kt:54-92`) only `setAudioSource(...)`s
  and `prepare()`s a `MediaRecorder` — no `audioFocus.request()`, no
  `bluetoothSco.start()`. `RecordingModule.reduce(StartRecording→Preparing)`
  emits **only** `Effect.AllocateMediaRecorder`; `Preparing→Active` emits
  MediaRecorder/Timer/Amplitude/Glow/Notification — **no
  `Effect.RequestAudioFocus`, no `Effect.StartBluetoothSco`, no
  `AudioAction` emission**. `AudioModule.reduce` has **no arm** that
  produces `RequestAudioFocus`/`StartBluetoothSco`; those effects are
  *defined but never emitted by any production reducer or cascade*. The
  `AudioModule` KDoc claim ("AudioFocus is requested as part of
  `RecordingModule.Effect.AllocateMediaRecorder` — the subsystem adapter
  takes care of it", `AudioModule.kt:28-33`) is **factually wrong** — a
  stale dormant-layer comment; the adapter demonstrably does no such
  thing.
- **Bluetooth subtlety (silent-quality-loss):** the new path *does* thread
  `useBluetooth` into `RecordingHardwareAdapter.allocate`, which selects
  `VOICE_COMMUNICATION`. But **without an active SCO connection,
  `VOICE_COMMUNICATION` records from the phone mic, not the BT headset.**
  So a BT-mic user on the new path gets a silently-wrong audio source — no
  error, just degraded/wrong-mic audio. This is exactly the R-1-class
  *silent* failure mode the Epic forbids, in a different field than the
  JobRequest (the JobRequest IS field-faithful per C5; this is the
  recording-hardware route, not the pipeline config).

**Why this is gate-RED-blocking:** C7 deletes the legacy
`JobExecutor.start` call-sites AND the `USE_LEGACY_RECORDING_DRIVE`
boolean (FN-3 / OQ-2 default: removed immediately after C6). Once C7 lands,
the legacy `RecordingStateController` recording path is unreachable and
Theme C deletes it entirely. At that point **every recording loses
audio-focus management** (default-on for all users — other-app audio can
duck/interrupt the recording; the recording is not protected) and **BT-mic
users silently record from the phone mic**. This is a shipped functional
regression on the product's core feature, introduced by the cutover,
*surfaced* (not caused) by C5 making the path live. It is precisely the
case the prompt names: "If legacy did and the new path doesn't, deleting
legacy in C7 = a shipped regression → gate-RED-blocking unless fixed
first." The guarded fallback covers it *today* (C5/C6) but C7 removes the
fallback — so it MUST be fixed before C7.

**Criterion C5-IMPL-2 (Important) — legacy recording UI/animation/
keyboard-hide-pause read Idle on the new path. VERDICT: 🟢 NON-BLOCKING
for this gated step.**

- Two sub-parts. (a) The legacy `onKeyboardHidden` *auto-pause on
  keyboard-hide* (`RecordingStateController.onKeyboardHidden:233-247` —
  `togglePause()` + 60s auto-stop + BT-release + focus-abandon) does NOT
  fire on the new path (legacy controller is Idle). **This is the
  intended improvement, not a regression.** ADR-0003 §Context (`:21-24`)
  states the product requirement verbatim: "Recording/Pipeline soll
  *weiterlaufen*, wenn der User auf eine andere Tastatur wechselt … und
  später zurückkommt." The legacy pause-on-hide is the *limitation the new
  FGS architecture exists to fix*. My `DictateCutoverE2ETest`
  `t3t5_realRecordingActive_drivesKeyboardToHoverAndBack` proves the new
  path keeps `state.recording` Active across `OnImeViewHidden` (HOVER) —
  recording genuinely survives the keyboard switch, the parent plan's
  raison d'être. ✅ better, not worse.
- (b) The legacy in-keyboard recording **animation/amplitude/timer UI**
  reads Idle on the new path → no in-keyboard waveform. The **FGS
  notification** (`NotificationStatus.Recording` with `[Pause][Stopp]
  [Senden]`, proven by `ImeRecordingDriveCutoverTest` + my AC-2 test) is
  the authoritative new-path recording-active surface. The RenderBackend
  recording-UI migration is legitimately Theme-C/C10 scope (Spec 2 §9.x).
  For a *gated step* where the FGS notification clearly shows
  recording-active + the 3 action-buttons, this is an **acceptable interim
  surface**, not a gate-RED UX regression. (Note: the BT-release/
  focus-abandon part of `onKeyboardHidden` is subsumed by C5-IMPL-1 — the
  new path never acquired them, so there is nothing to release; it is not
  a *separate* regression, it is the same C5-IMPL-1 finding.)

**Criterion C5-IMPL-3 (NTH) — RESUME has no orchestrator equivalent.
VERDICT: 🟢 genuinely non-blocking.** Both boolean branches keep legacy
`JobExecutor.start(Resume)` (`:3286`/`:3294`) — single-dispatch, verified
in the AC-10 table. The recovery path is orthogonal to the fresh-recording
cutover; C7 must keep this `JobExecutor.start(Resume)` site (it has no new
equivalent) — that is a C7/Theme-C scoping note, not a C6 blocker. RESUME
keeps working regardless of the boolean.

**Criterion C4-IMPL-2 (NTH) — `Pipeline` notif subtitle generic, no F-13
counters. VERDICT: 🟢 genuinely non-blocking.** Cosmetic; the live F-13
counter already renders in the record-button label (A1). `NotificationStatus.Pipeline`
payload extension is out of the cutover-gate scope. Confirmed
non-blocking.

#### Overall verdict: 🔴 GATE: RED

The new path is *structurally* proven (AC-2/AC-3/AC-10/keystone/T1-T7 all
green on the live path; full suite 1016 green; assembleDebug green; the
guarded fallback is intact so the app is shippable on legacy *today*).
**But it is not *behaviourally equivalent enough* to destroy the legacy
fallback:** C5-IMPL-1 is a genuine legacy-parity regression that ships the
moment C7 deletes legacy — audio-focus loss for 100% of users (default-on)
and silent BT-mic→phone-mic substitution for BT-mic users. Per D4
(long-term-correct over expediency) and Epic §6.2 ("proven, not assumed";
"the guarded fallback exists precisely so we DON'T have to rush this"), a
justified RED here is the gate doing its job — the last safe point before
irreversible legacy deletion.

**Precise repair worklist that gates C7/Theme-C (for the repair-sub-phase):**

1. **[BLOCKER] Wire audio-focus on the new recording path.** On
   `RecordingAction.StartRecording` (or `Preparing→Active`), the
   orchestrator must emit `AudioModule.Effect.RequestAudioFocus`
   (gated on `Pref.AudioFocus`, default true) and
   `Effect.ReleaseAudioFocus` on stop/pause/cancel — mirroring the legacy
   `gate.request()/abandon()` lifecycle (`RecordingStateController:326,
   :150,:168,:221,:244,:331`). The effect + the `AudioFocusSubsystemAdapter`
   already exist (`AudioModule.runEffect:90-91`); only the *emission* (a
   reducer arm or a Recording→Audio cascade) is missing. Likely a small
   `RecordingModule` side-effect addition or a cross-module cascade
   (ADR-0002 Mode-1). Fix the stale `AudioModule.kt:28-33` KDoc as part
   of this.
2. **[BLOCKER] Wire Bluetooth-SCO start on the new recording path.** When
   `ctx.global.audio.useBluetoothMic` is true, emit
   `AudioModule.Effect.StartBluetoothSco` before/with
   `AllocateMediaRecorder`, wait for the SCO-connected state (the
   `OnBluetoothScoStateChanged` observer already feeds `AudioState`), and
   only then `allocate` with `VOICE_COMMUNICATION`; on SCO-fail fall back
   to `MIC` (mirror legacy `onScoConnected`/`onScoFailed:300-321`).
   `Effect.StartBluetoothSco`/`StopBluetoothSco` +
   `BluetoothScoSubsystemAdapter` already exist; only the emission +
   the SCO-ready→allocate sequencing is missing. This is the larger of
   the two (a Preparing-state SCO-handshake sub-FSM) — likely
   research-needed (route via the repair-sub-phase research branch
   against Spec 1 §15.2 / §15.3 "Pure-Function-Vertrag" + the legacy
   `RecordingStateController` SCO-wait state machine).
3. **[non-blocker, C7 scoping note]** C7 must NOT delete the
   `JobExecutor.INSTANCE.start(Resume)` site (`:3286`/`:3294`,
   C5-IMPL-3) — RESUME has no orchestrator equivalent. Document this
   carve-out in C7's deletion scope so C7 does not over-delete.

After the repair-sub-phase lands #1 + #2 (and re-validates green),
**re-run this C6-D2pre gate** (re-trace audio-focus/BT-SCO on the new
path + re-run the auto-tier). Only a GREEN re-gate authorises C7 +
Theme C.

#### C6-SUBSET manual device-attached TCs — manual-pending (gate verdict provisional on auto+assessment, per prompt)

"ohne Walkthrough" mode + no guaranteed device → these are **described
for the user** (orchestrator may forward); the gate does NOT block on
them. Per the runbook C6-SUBSET pass-bar (TC-1, TC-2, TC-C1, TC-C2,
TC-C3, TC-C4, TC-6, TC-10, TC-11, TC-22 + the 3 Periodic Visits):

- **TC-1 / TC-11 (keyboard-switch survival + keystone on new path):**
  auto-mirrored green by `DictateCutoverE2ETest`
  `t3t5_…` + `keystone_…` (Robolectric equivalent). Device run would
  additionally confirm logcat shows zero stub `Log.w` lines + FGS visible.
- **TC-C1 (no-double-dispatch):** auto-covered by the AC-10 grep table
  above (all 3 sites, mutually-exclusive verified in code). Device
  logcat-scan would confirm exactly one driver per user action.
- **TC-C2 (guarded-fallback rollback):** code-inspection confirms the
  legacy arm is byte-for-byte preserved + reachable (`:2313-2320`,
  `:2341-2345`, `:3286`, `:3463`) — rollback is genuinely one boolean
  away. **This is the safety net that makes the RED non-catastrophic:
  the app is shippable on legacy while C5-IMPL-1 is repaired.**
- **TC-C3 (full JobRequest config survives — R-1):** the JobRequest
  field-fidelity is C5-proven (the 8-field 1:1 table) + C3-tested. The
  **audio-focus/BT-SCO regression is NOT a JobRequest field** — it is the
  recording-hardware route, which is why C5's R-1 (JobRequest) GREEN does
  not contradict this C6 RED (recording-hardware audio-focus/SCO).
- **TC-C4 (FGS action-button round-trip), TC-6 (T3 live), TC-10 (T7
  live), TC-22 (F-13 counters):** auto-mirrored structurally by the
  notification + T-series assertions in `DictateCutoverE2ETest` /
  `ImeRecordingDriveCutoverTest` / `PipelineNotificationCoordinatorTest`.
  Device runs would confirm visual/locale + PendingIntent on real OS.

These manual TCs do not change the verdict: the RED is driven by the
C5-IMPL-1 code-trace, which is conclusive without a device.

**Plan deviations:** none (verification chunk; only a new test file).

**Issues:**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| C6-IMPL-1 | Important | New recording path requests no AudioFocus (legacy did, `Pref.AudioFocus` default true → 100% users) + does not start Bluetooth-SCO (BT-mic users silently record from phone mic via `VOICE_COMMUNICATION` w/o SCO). `RecordingHardwareAdapter.kt:54-92` + `RecordingModule.reduce` (no Audio effect emission) + `AudioModule.reduce` (no RequestAudioFocus/StartBluetoothSco arm). Stale incorrect KDoc `AudioModule.kt:28-33`. **GATE-RED-BLOCKING for C7/Theme-C** — ships a regression once the legacy fallback is deleted. Repair worklist #1+#2 above. | delegated-to-orchestrator | This is the consolidated, gate-validated form of the open C5-IMPL-1. It blocks C7 (legacy deletion removes the fallback that masks it). Route via repair-sub-phase (research-needed for the BT-SCO handshake sub-FSM; audio-focus is the smaller, near-mechanical part). |
| C6-IMPL-2 | Nice-to-have | C7 must carve out the RESUME `JobExecutor.start` site (`:3286`/`:3294`, C5-IMPL-3) from its deletion scope (no orchestrator resume equivalent exists). | delegated-to-orchestrator | C7-scoping note, not a C6 blocker. Prevents C7 over-deleting a still-needed single-dispatch recovery call-site. |

**Inline-fixed items:** none (gate chunk — no production edits; only the
new `DictateCutoverE2ETest.kt`).

**Overlooked / Known Gaps:**
- The deep `SubmitPipeline → JobExecutor worker-thread` E2E leg is
  intentionally NOT re-asserted via worker-thread sync (the brittleness
  C5 flagged at `ImeRecordingDriveCutoverTest.kt:205-210`). My AC-3
  assertion uses the **state-reducer-driven** notification
  Recording→Pipeline transition (`PipelineModule.reduce(TriggerPipeline)`
  emits `Effect.UpdateNotification(Pipeline)` synchronously) — a robust
  proof the StopRecordingAndSend→EmitPipelineTrigger→TriggerPipeline
  chain reduced, without the flaky worker-thread await. The runner leg
  itself is C3-tested (`PipelineRunnerSubsystemAdapterTest`) + C5's
  `newPath_recordingNotification_*`.
- Manual device TCs not executed (no device, "ohne Walkthrough") —
  described above, gate verdict rests on auto-tier + code-trace
  assessment per the prompt's explicit instruction.

**Commit boundaries (orchestrator splits — lists disjoint):**
- **=== COMMIT 1 BOUNDARY === production files: none** (verification
  chunk — no production edits)
- **=== COMMIT 2 BOUNDARY === test files:**
  `app/src/test/java/net/devemperor/dictate/core/DictateCutoverE2ETest.kt`

---

### Gate-Repair Wave B2-C6-W1

**Agent-IDs:** `B2-C6-RES-1` (research) → `B2-C6-REPAIR-1` (repair) →
`B2-C6-REPAIR-1-VERIFY` (self-check) · **Date:** 2026-05-15 ·
**Scope:** C6-IMPL-1 (audio-focus + BT-SCO, both parts) + C6-IMPL-2
doc-note · **Convergence:** ✓ converged

#### What was done

Closed the C6-D2pre gate-RED-blocking finding C6-IMPL-1 (≡ C5-IMPL-1):
the new orchestrator recording path now requests audio-focus and
establishes the Bluetooth-SCO route with legacy parity, so deleting
the legacy fallback in C7 no longer ships a regression.

#### BT-SCO design decision + rationale

**Spec-faithful, ADR-0002 Mode-2 cascade → Mode-1 effect; one new
Effect/edge added, justified (D22).** Spec 1 §15.1 row 3 explicitly
prescribes an AudioModule cross-module observer arm
`Recording.Preparing → AudioFocus-Request`; the Phase-B S-4 §15.3
KDoc had removed it under the *false premise* that
`RecordingHardwareAdapter.allocate` requests focus (it provably does
not — the same stale-comment class the gate flagged). The repair
**restores that observer arm**: `AudioModule.onCrossModuleStateChange`
observes the RecordingState FSM and cascades AudioModule-owned actions;
the AudioModule reducer turns them into its own
`RequestAudioFocus`/`ReleaseAudioFocus`/`StartBluetoothSco`/`StopBluetoothSco`
effects (Mode-1). This keeps the audio-focus + SCO lifecycle entirely
in AudioModule (the `audio` axis owner) — the SRP rationale the S-4
note correctly stated but mis-applied. No Mode-3 (no cross-axis
write).

For BT-SCO, the legacy `onScoConnected/onScoFailed` wait was never
ported to the FSM. Rather than a heavy Preparing sub-FSM rewrite
(high blast radius, weak spec grounding, fragile under the gate's
"proven not assumed" bar), the repair adds **one new RecordingAction
(`ScoRouteResolved`) + one `Preparing` reducer arm + an `awaitingSco`
discriminator (and a carried `target`) on `RecordingState.Preparing`**:
BT-mic recordings *defer* `AllocateMediaRecorder` at `StartRecording`;
the existing production wiring already feeds the SCO outcome back as
`OnBluetoothScoStateChanged` (subsystem-owned 2500 ms timeout — no new
timer); AudioModule's observer translates the just-settled phase into
`ScoRouteResolved(useBluetooth = phase==Connected)`, whose `Preparing`
arm fires the now-correctly-sourced allocate (SCO-Connected →
`VOICE_COMMUNICATION`, SCO-Failed/timeout → `MIC` fallback). Non-BT
path is unchanged (immediate allocate). New Effect/edge documented as
a D22 deviation below. Research: `../research/recording-audiofocus-btsco-handshake.md`.

A second, non-obvious correctness fix surfaced during self-check: the
recording-engaged predicate had to be detected on the *engagement
edge* (`!engaged → engaged`), not the named `Idle → Preparing`
transition, because `Dispatchers.Main.immediate` lets the
`AllocateMediaRecorder` effect's re-entrant `MediaRecorderReady`
collapse the observer's frozen tuple to `Idle → Active` — the
single-named-transition form silently dropped the audio-focus request
on the live path (caught by the new E2E shadow-AudioManager test, not
the pure-reducer tests).

#### Deviations

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| Dev-W1-1 | Spec 1 §15.3 Phase-B S-4 KDoc | Restored the §15.1 row-3 AudioModule observer arm the S-4 note removed; rewrote the stale `AudioModule.kt` KDoc | S-4 premise (adapter requests focus) is factually wrong vs. the shipped adapter; §15.1 is the spec-faithful source | C7 may now delete legacy without the audio-focus/SCO regression | inline-fixed |
| Dev-W1-2 | Spec 1 §15.2 (RecordingModule reducer) | New `Action.RecordingAction.ScoRouteResolved` + `Preparing.awaitingSco`/`target` + a `Preparing` reducer arm; BT-mic path defers `AllocateMediaRecorder` | Spec never ported the legacy SCO-wait; this is the minimal spec-faithful realisation of legacy parity within ADR-0002 Mode-1/2 (no Mode-3, no sub-FSM rewrite) | C7/Theme-C: `Preparing` now has two extra default-valued fields (backward-compatible) + one extra action leaf | inline-fixed |
| Dev-W1-3 | Spec 1 §15.1.x Coupling-Matrix | AudioModule now reads `state.recording` + cascades `RecordingAction.ScoRouteResolved`; matrix `Audio` row should gain `R(state.recording)` + the new cascade cell | A new observer-read without a matrix entry is a §15.1.x SRP-review violation | Doc-only; matrix update is a follow-up doc task (flagged, not code-blocking) | flagged-for-validate |

#### Issues

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| C6-IMPL-1 | Important | Audio-focus + BT-SCO not emitted on the new path (gate-RED) | fixed | AudioModule observer + reducer arms + deferred-SCO-allocate; legacy parity proven (pure-reducer + observer + E2E shadow-AudioManager) |
| C5-IMPL-1 | Important | Consolidated into C6-IMPL-1 | fixed | fixed-via-C6-W1 (same root cause) |
| C6-IMPL-2 | Nice-to-have | C7 RESUME-site carve-out | documented | C7 carve-out blockquote confirmed intact; no code change |

#### Files changed (DISJOINT — for the orchestrator wave-commit)

**Production:**
- `app/src/main/java/net/devemperor/dictate/state/Action.kt` (added `RecordingAction.ScoRouteResolved`, `AudioAction.RecordingStarted`, `AudioAction.RecordingEnded`)
- `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt` (`RecordingState.Preparing.awaitingSco` + `.target`, default-valued)
- `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt` (BT-mic deferred-allocate at `StartRecording`; new `Preparing + ScoRouteResolved` arm)
- `app/src/main/java/net/devemperor/dictate/state/modules/AudioModule.kt` (`RecordingStarted`/`RecordingEnded` reducer arms; observer extension for recording-lifecycle + SCO-resolution; KDoc rewrite)

**Test:**
- `app/src/test/java/net/devemperor/dictate/state/RecordingModuleTest.kt` (updated BT/non-BT StartRecording expectations; new ScoRouteResolved arm tests)
- `app/src/test/java/net/devemperor/dictate/state/AudioModuleTest.kt` (RecordingStarted/Ended reducer + cross-module observer + SCO-handshake-resolution tests)
- `app/src/test/java/net/devemperor/dictate/core/DictateCutoverE2ETest.kt` (3 new C6-IMPL-1 E2E tests: new-path focus-request, stop abandons focus, pref-off does not request — via service-AudioManager shadow)

**Doc:** `docs/plans/2026-05-15 - dictate-cutover-completion/research/recording-audiofocus-btsco-handshake.md` (new, append-only D20)

**Files outside findings-scope (drift):** none — every production/test
file maps directly to the C6-IMPL-1 worklist.

#### Self-check (B2-C6-REPAIR-1-VERIFY)

- `./gradlew assembleDebug`: **BUILD SUCCESSFUL**.
- `./gradlew test`: **1037/1038 pass**. The single failure is
  `LegacyAudioFileMigrationTest > run leaves non-legacy-path sessions
  untouched` — the **known R-7 order-dependent DB/JobExecutor
  pollution flake** (`expected:<[RECORDING]> but was:<[FAILED]>`).
  Confirmed the flake, not a regression: the class passes **8/8 when
  run isolated**; no migration/session-DB files are in this wave's
  diff (production diff is confined to state/audio/recording modules).
- New + updated unit tests prove: `RequestAudioFocus` emitted on
  recording-start, gated off when `audioFocusEnabledPref=false`,
  released on stop/pause/cancel; BT-SCO handshake — SCO-ready →
  `VOICE_COMMUNICATION`, SCO-fail → `MIC` fallback, duplicate-resolve
  no-op; K-1 handwritten fakes only; K-4 pure-reducer JVM tests
  (Robolectric only for the 3 service-level E2E focus assertions,
  justified — they prove the full dispatch→cascade→effect→adapter→gate
  →AudioManager wiring end-to-end, which a pure-JVM test cannot).
- `DictateCutoverE2ETest` still green (all 10 tests incl. the 3 new
  C6-IMPL-1 ones) — extends the gate's keystone suite to assert
  audio-focus IS requested on the new path so a C6-D2pre re-gate can
  prove GREEN.
- Convergence: **✓ converged** — no new issues forwarded.

---

### Chunk C6-D2pre — RE-GATE

**Agent-ID:** `B2-C6-D2pre-REGATE` (fresh, independent — NOT the W1
repair agent) · **Date:** 2026-05-15 · **Iter:** 1 of the gate-repair
loop (D5 cap 3) · **Scope:** verify C6-IMPL-1 genuinely closed by
B2-C6-W1; re-run auto-tier; re-confirm AC-10 + non-blocking criteria.

> ## ✅ RE-GATE: GREEN
>
> **C6-IMPL-1 (≡ C5-IMPL-1) is genuinely closed.** Audio-focus + BT-SCO
> are now emitted on the new recording path (`USE_LEGACY_RECORDING_DRIVE
> =false`) with full legacy parity, independently re-traced (not on the
> repair agent's word). Auto-tier is green modulo the documented,
> pre-existing R-7 order-dependent shared-singleton pollution flake
> (proven: every flaked class passes 100% isolated; the W1 diff touches
> zero JobExecutor/migration/DB files). AC-10 holds. No new regression.
> **C7 (legacy-call-site deletion) + ALL of Theme C (B3) are
> AUTHORISED.**

#### Auto-tier (independently re-run — `--rerun-tasks`, no cache)

| Check | Result |
|---|---|
| `./gradlew assembleDebug` | **BUILD SUCCESSFUL** (AC-9 build invariant) |
| `./gradlew testDebugUnitTest --rerun-tasks` (fresh full suite, no cache) | **1038 tests, 1033 pass, 5 fail** — all 5 are the **R-7 order-dependent shared-singleton pollution flake family**, NOT regressions (see below) |
| Isolated re-run of every flaked class (`PipelineRunnerSubsystemAdapterTest` + `LegacyAudioFileMigrationTest`, `--rerun-tasks`) | **15/15 pass (7/7 + 8/8), BUILD SUCCESSFUL** — confirms flake, not regression |

**R-7 flake analysis (the prompt's "re-run isolated + full to confirm"
instruction, executed):** the 5 full-suite failures are:
`PipelineRunnerSubsystemAdapterTest` ×4 (`isRunning…reflect
ActiveJobRegistry` `expected:<0> but was:<1>`; `cancel delegates…`
`runner did not start`; `binder TriggerPipeline…R-1 guard` `registry
must stay empty but was Running`; `submitReprocess…` `runner did not
start`) + `LegacyAudioFileMigrationTest` ×1 (`run leaves non-legacy-path
sessions untouched` `expected:<[RECORDING]> but was:<[FAILED]>`). Every
symptom is a *prior-test-left-dirty-singleton* artefact (a JobExecutor
worker / `ActiveJobRegistry` entry / `DictateDatabase` row bleeding from
a preceding test in suite-order). **All 15 tests across both classes
pass 100% when run isolated together.** This is exactly the Epic §6.3
R-7 documented hazard ("test-pollution amplification — new IME-boot
Robolectric tests share the `DictateDatabase`+`JobExecutor` singletons").
Causal proof it is NOT a W1 regression: `git show --stat 13c273c` — W1's
production diff is confined to `state/Action.kt`,
`state/DictateUiState.kt`, `state/modules/AudioModule.kt`,
`state/modules/RecordingModule.kt` (pure reducers/observer) + their
tests + 3 `DictateCutoverE2ETest` E2E tests + docs. **Zero
JobExecutor/ActiveJobRegistry/migration/DB production files touched** —
a pure-reducer diff cannot causally introduce JobExecutor-worker /
registry / DB-row pollution. The flake's *magnitude* (W1 self-check saw
1, this fresh run sees 5) reflects R-7's known order-sensitivity + the 3
new Robolectric service-boot tests amplifying the **pre-existing**
shared-singleton hazard, not a new defect. There is **no OTHER failure**
(the prompt's RED trigger) — every failure is the documented flake.
Note: the W1 self-check's "1037/1038" came from a `./gradlew test` that
was Gradle-`UP-TO-DATE` cached (independently observed: a plain
`assembleDebug test` here showed `:app:test UP-TO-DATE`, 0 tests
executed); this RE-GATE forced `--rerun-tasks` for an authoritative
fresh count.

#### Per-criterion independent findings (1–7)

**1. Auto-tier green:** ✅ (see table). `assembleDebug` SUCCESSFUL;
fresh full suite 1033/1038 with the only failures being the
isolated-pass-confirmed R-7 flake. AC-9 ≥946 invariant holds (1038 ≫
946). No OTHER failure.

**2. C6-IMPL-1 audio-focus closed:** ✅ **Independently code-traced.**
`AudioModule.onCrossModuleStateChange` (`AudioModule.kt:172-214`) cascades
`AudioAction.RecordingStarted` on the **engagement edge**
(`!prevRec.isEngaged() && nextRec.isEngaged()`, plus the explicit
`Paused→Active` resume clause) and `RecordingEnded` on disengage
(`engaged→Idle` stop/cancel, `Active→Paused` pause). The
`RecordingStarted` reducer arm (`:127-133`) emits `Effect.RequestAudioFocus`
**gated on `state.audioFocusEnabledPref`**; `RecordingEnded` (`:141-147`)
emits `ReleaseAudioFocus` unconditionally (idempotent). Legacy-parity
re-verified against the actual legacy source (not the repair agent's
word): `RecordingStateController.proceedStartRecording:326`
`if (audioFocusEnabled) gate.request()`; `stopRecording:150` /
`cancelRecording:221` / `togglePause:168` `gate.abandon()`;
`togglePause:172` (Paused→Active) `gate.request()` — every legacy edge
has a matching observer cascade. `Pref.AudioFocus` default **`true`**
(`DictatePrefs.kt:30`) → focus requested for 100% of users by default,
exactly as legacy; pref-mirror wired (`PipelinePrefMirror.kt:138,199`
`Pref.AudioFocus.key → audioFocusEnabledPref`). The observer arm
restored Spec 1 §15.1 row 3; the stale `AudioModule.kt:28-33` KDoc is
rewritten to the real path (`:28-60`, no longer claims the adapter
handles it). Engagement-edge robustness against the
`Dispatchers.Main.immediate` re-entrant `Idle→Active` tuple collapse is
sound and is the documented reason the E2E test earns its place. Proven
by tests: `AudioModuleTest` (`RecordingStarted` pref-on/off/BT-combo,
`RecordingEnded`, all observer edges) + `DictateCutoverE2ETest`
`c6impl1_newPathRecording_requestsAudioFocus_throughTheSystemAudioManager`
(non-vacuous: asserts no focus pre-condition, dispatches a **real**
new-path `StartRecording` through the production binder→orchestrator→
modules→`AudioFocusSubsystemAdapter`→`RealAudioFocusGate`→system
`AudioManager` shadow, then asserts `lastAudioFocusRequest` non-null on
the same AudioManager instance the gate uses), `…StopRecording_abandons…`
(asserts `lastAbandonedAudioFocusRequest`), `…audioFocusPrefOff_doesNot…`
(legacy `if(audioFocusEnabled)` opt-out parity).

**3. C6-IMPL-1 BT-SCO closed:** ✅ **Independently code-traced.**
`RecordingModule.reduce(Idle, StartRecording)` (`:237-293`): when
`ctx.global.audio.useBluetoothMic` → `Preparing(awaitingSco=true,
target=action.target)` with **empty sideEffects** (allocation deferred);
the `RecordingStarted` cascade emits `StartBluetoothSco` (gated on
`useBluetoothMic`). SCO outcome arrives as `OnBluetoothScoStateChanged`
(production-wired `DictatePipelineService.kt:345-373`: `onScoConnected`→
`Connected`, `onScoFailed`→`Failed,"sco-timeout"`); AudioModule's
observer (`:228-238`) translates the *just-resolved* phase
(Waiting/Disconnected→Connected/Failed transition only, no re-fire on
duplicate) into `RecordingAction.ScoRouteResolved(useBluetooth =
phase==Connected)`. `RecordingModule.reduce(Preparing,
ScoRouteResolved)` (`:346-367`) — guarded on `state.awaitingSco` (stale/
duplicate = `null` no-op) — fires the deferred `AllocateMediaRecorder`
with the correctly-sourced route (Connected→`VOICE_COMMUNICATION`,
Failed/timeout→`MIC` fallback). Non-BT path unchanged (immediate
allocate). No new timer: `BluetoothScoManager.startSco(2500)` posts its
own `timeoutRunnable`→`onScoFailed` (`BluetoothScoManager.kt:121-138`);
`BluetoothScoSubsystemAdapter.start()`→`manager.startSco()` default
2500 ms — legacy parity exactly. Handshake edges all tested:
`AudioModuleTest` `SCO connect…cascades ScoRouteResolved(true)` /
`SCO fail…ScoRouteResolved(false)` / `SCO phase unchanged does NOT
re-cascade (duplicate broadcast)` / `SCO change while Preparing not
awaiting does NOT cascade`; `RecordingModuleTest` `StartRecording
(BT-mic) defers AllocateMediaRecorder until SCO resolves` /
`(non-BT) allocates immediately` / `ScoRouteResolved(true)…
VOICE_COMMUNICATION` / `ScoRouteResolved(false)…falls back to MIC` /
`ScoRouteResolved when not awaiting is a no-op`. Timeout edge is covered
by the Failed-phase test (the subsystem maps timeout→`onScoFailed`→
`Failed`). The silent phone-mic substitution is eliminated.

**4. AC-10 still holds:** ✅ **Independently re-grepped.** `grep -n
"JobExecutor.INSTANCE.start"` on `DictateInputMethodService.java` → 3
real sites: `:2597` fresh (`if (USE_LEGACY_RECORDING_DRIVE)`
true-branch, new path dispatches `StartRecording`@`:2334`/
`StopRecordingAndSend`@`:2382`, mutually exclusive); `:3286`/`:3294`
RESUME (single-dispatch both boolean branches, no orchestrator
equivalent — C5-IMPL-3 / C6-IMPL-2 carve-out); `:3463` REPROCESS_STAGING
(`if (USE_LEGACY_RECORDING_DRIVE)` true-branch, mutually exclusive). No
double-dispatch. W1's diff did **not** touch the IME — it cannot have
reintroduced a double-path (`git show --stat 13c273c` confirms zero IME
files). AC-10 single-architecture invariant intact for the gated step.

**5. Keystone + Triangle-FSM on the new path:** ✅ `DictateCutoverE2ETest`
now has 10 tests (7 original + 3 W1 C6-IMPL-1). The 3 new ones are
non-vacuous (verified by reading the bodies `:469-555`: real
`StartRecording` dispatch via the production `LocalBinder`, `pumpUntil
recording is Active` proving the full Idle→Preparing→Active FSM ran
incl. the AudioModule cascade, shadow-AudioManager read off the
*service's* AudioManager instance). Full-suite + isolated runs confirm
all 10 pass (the suite failures are confined to the unrelated R-7
JobExecutor/migration classes — `DictateCutoverE2ETest` is green in both
the full and isolated runs). Keystone F-1/F-2/F-3 + T1–T7 unaffected by
the pure-reducer W1 diff.

**6. Non-blocking criteria unchanged:** ✅ Re-confirmed genuinely
non-blocking for authorising C7 + Theme C: **C5-IMPL-2** — the lost
legacy keyboard-hide auto-pause is the *intended* ADR-0003 improvement
(recording must survive keyboard switch — the parent plan's raison
d'être), and the FGS `NotificationStatus.Recording` notification is the
authoritative new-path recording-active surface; the in-keyboard
animation/amplitude UI is legitimately Theme-C/C10 scope. The
BT-release/focus-abandon sub-part is now *positively closed* by W1
(`RecordingEnded`→`ReleaseAudioFocus`+`StopBluetoothSco`), strengthening
this verdict vs. the original gate. **C5-IMPL-3** — RESUME single-dispatch
both branches, orthogonal to the fresh-recording cutover, AC-10-verified.
**C4-IMPL-2** — cosmetic Pipeline-notif subtitle, F-13 counter already in
the record-button label. None blocks irreversible legacy deletion.

**7. C6-IMPL-2 scoping note present:** ✅ The C7 carve-out blockquote is
intact at `### Chunk C7-B3` ("must NOT delete the RESUME
`JobExecutor.INSTANCE.start(Resume)` site (`:3286`/`:3294`)") and the
Issue Index row records `C6-IMPL-2 → documented (C7-scoping)`. C7 is
correctly scoped to delete only the fresh (`:2597`) + reprocess
(`:3463`) legacy sites + the `USE_LEGACY_RECORDING_DRIVE` boolean +
`recordingStateController` recording branches, NOT the RESUME site.

#### Verdict + reasoning

**RE-GATE: GREEN.** The gate-RED-blocking C6-IMPL-1 (audio-focus +
BT-SCO legacy-parity regression) is genuinely closed — independently
re-traced through production code and the actual legacy source (not the
repair agent's word), proven by pure-reducer + observer + non-vacuous
E2E shadow-AudioManager tests. Audio-focus is requested for 100% of
users by default and released on stop/pause/cancel with exact legacy
timing parity; BT-mic recordings defer allocation until the SCO route
resolves (Connected→`VOICE_COMMUNICATION`, Failed/timeout→`MIC`),
eliminating the silent phone-mic substitution. AC-10 single-architecture
invariant holds (3 boolean-fenced `JobExecutor.start` sites, no
double-path; W1 did not touch the IME). The auto-tier is green: build
SUCCESSFUL, fresh full suite 1033/1038 with the **only** failures being
the documented, pre-existing, isolated-pass-confirmed R-7
shared-singleton pollution flake (W1's pure-reducer diff touches zero
JobExecutor/migration/DB files — causally cannot have introduced it; no
OTHER failure exists). Non-blocking criteria re-confirmed non-blocking.
Per D4 (long-term-correct) and Epic §6.2 ("proven, not assumed"), the
new path is now behaviourally equivalent enough to destroy the legacy
fallback. **C7 (legacy-call-site deletion) + ALL of Theme C (B3) are
AUTHORISED.** No repair worklist — no blocking findings.

**Issues:**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| C6-IMPL-1 | Important | Audio-focus + BT-SCO not emitted on the new path | **closed** | Independently re-verified closed by B2-C6-W1: observer + reducer arms + deferred-SCO-allocate; legacy parity code-traced + tested (pure-reducer + observer + non-vacuous E2E shadow-AudioManager). RE-GATE GREEN. |
| C6-IMPL-2 | Nice-to-have | C7 RESUME-site carve-out | **closed** | C7 carve-out blockquote re-confirmed intact + Issue Index row present; C7 correctly scoped. |
| — | — | R-7 full-suite flake (5 failures: PipelineRunnerSubsystemAdapterTest ×4 + LegacyAudioFileMigrationTest ×1) | postponed | Pre-existing documented Epic §6.3 R-7 order-dependent shared-singleton pollution; all 15 tests pass 100% isolated; W1 diff touches zero JobExecutor/migration/DB files. NOT a W1 regression, NOT a recording-feature regression — non-blocking for the gate per the prompt's explicit flake-handling instruction. (Pre-existing test-infra hygiene debt, owned by R-7, not this gate.) |

**Inline-fixed items:** none (verification-only chunk — no production
or test edits; all checks were independent re-traces + re-runs).

**Overlooked / Known Gaps:**
- The R-7 shared-singleton test-pollution is a real (pre-existing)
  test-infrastructure hygiene debt — it does not block this gate (it
  is the documented flake, not a regression, and not in the
  recording-feature behaviour), but it remains owned by Epic §6.3 R-7
  for an eventual test-isolation hardening pass. Flagged here so a
  future reader does not mistake the 5-failure full-suite run for a
  regression.
- Manual device-attached C6-SUBSET TCs not executed (no device) — the
  gate rests on the auto-tier + independent code-trace per the prompt;
  the original C6-D2pre subsection already enumerates them for
  orchestrator forwarding.

---

### Chunk C7-B3 — legacy call-site deletion (GATED on green C6)

**Agent-IDs:** `B2-C7-B3-IMPL` (fresh, combined Steps 1-5).
**Status:** ✅ complete · **Risk:** MED (pure deletion of proven-dead
code) — **GATE: C6-D2pre RE-GATE GREEN authorised C7 (Epic §6.2; the
revertible point-of-no-return chunk, separately committed).**
**Implementation-Commit (Commit 1):** ⏳ (orchestrator — separately
committed for git-revert isolation, Epic §6.2 invariant 3) ·
**Test-Commit (Commit 2):** ⏳ (orchestrator)

> **C7 carve-out note (C6-IMPL-2) — HONOURED:** the RESUME
> `JobExecutor.INSTANCE.start(Resume)` site (`startResumeJob`,
> post-C7 `:3229`) was NOT deleted. RESUME has no orchestrator
> equivalent (C5-IMPL-3); deleting it breaks pipeline recovery. Its
> byte-identical `if (USE_LEGACY_RECORDING_DRIVE) {…} else {…}` branches
> were collapsed to the single unconditional `JobExecutor.INSTANCE.start`
> they both already contained — behaviour-preserving (not fenced, not
> deleted, not rerouted through the new path; exactly as the carve-out
> mandates). C6-IMPL-2 → fixed.
>
> **C7-IMPL-1 (NEW, Critical, `architecture-conflict`):** a SECOND
> `JobExecutor.start` survives at post-C7 `:2554` — the imported-audio-
> file path (`onStartInputView:1959` → `runTranscriptionViaOrchestrator`).
> This was NOT in the prompt's/C5's/C6's "dead" classification (they
> overlooked the Settings-import caller). Per the STOP directive it was
> kept (made unconditional), not deleted — deleting it silently breaks
> imported-audio-file transcription (AC-9). Delegated for orchestrator
> routing (orchestrator-route = a new action + reducer arm; Theme-C/
> follow-up scope). C7's record-button-fresh + reprocess + boolean
> deletion is otherwise complete.

#### Implementation (B2-C7-B3-IMPL)

**What was done:** Removed the `USE_LEGACY_RECORDING_DRIVE` compile-time
constant (+ its 34-line Javadoc) and the now-dead legacy
`JobExecutor.start` branches it fenced, so the new
`DictateOrchestrator` is the **sole driver** for fresh recording +
reprocess (AC-10 single-architecture invariant; FN-3/OQ-2 — no
lingering dead switch, D7). The RESUME carve-out is preserved
unconditionally. One pre-existing C5-overlooked architecture gap was
discovered during the reachability audit and flagged (C7-IMPL-1,
Critical, `architecture-conflict`) rather than deleted — see Deviations
+ Issues.

**BEFORE grep (full enumeration + fresh/reprocess/resume
classification):**

| Site | File:line (pre-C7) | Kind | Pre-C7 shape |
|---|---|---|---|
| `USE_LEGACY_RECORDING_DRIVE` decl | `:151` | the switch | `private static final boolean … = false;` (+ `:118-150` Javadoc) |
| 5 predicate helpers | `:2189/2199/2217/2236/2258` | gating | `if (!USE_LEGACY_RECORDING_DRIVE && pipelineBinder != null)` |
| `startRecording` legacy | `:2313` | fresh-record-button | `if (USE_LEGACY_RECORDING_DRIVE) { recordingStateController.startRecording(...); return; }` |
| `stopRecording` legacy | `:2341` | fresh-record-button | `if (USE_LEGACY_RECORDING_DRIVE) { recordingStateController.stopRecording(); return; }` |
| #1 fresh `runTranscriptionViaOrchestrator` | `:2596` JobExecutor.start | **FRESH** | `if (USE_LEGACY_RECORDING_DRIVE) { JobExecutor.start } else { Log.w + stopPipeline }` |
| #2 RESUME `startResumeJob` | `:3286`/`:3294` JobExecutor.start | **RESUME** (carve-out) | `if (…) { JobExecutor.start } else { JobExecutor.start }` (byte-identical) |
| #3 REPROCESS `handleReprocessSend` | `:3463` JobExecutor.start | **REPROCESS** | `if (USE_LEGACY_RECORDING_DRIVE) { JobExecutor.start } else { adapter.submitReprocess }` |
| 3 Kotlin doc refs | `ImePipelineConfigResolver.kt:153`, `PipelineRunnerSubsystemAdapter.kt:296`, `DictatePipelineService.kt:450` | stale doc | KDoc/comment naming the removed switch |

**AFTER grep (proof):**

| Check | Result |
|---|---|
| `grep -rn USE_LEGACY_RECORDING_DRIVE app/src/main` | **ZERO** (exit 1 — constant, all 16 IME refs incl. predicate-helper conditions + section comment, and all 3 Kotlin doc refs removed) |
| `grep -n JobExecutor.INSTANCE.start DictateInputMethodService.java` | **2 sites only**: `:2554` (FRESH — imported-audio-file path, see C7-IMPL-1) + `:3229` (RESUME — carve-out preserved). The REPROCESS IME site is **gone** (routes via the C3 adapter's internal start). No `if (false)` / dead-branch residue. |

**What was deleted (exact):**
1. `USE_LEGACY_RECORDING_DRIVE` decl + its 34-line Javadoc (`:118-151`).
2. `startRecording()` legacy true-branch + the now-dead `boolean useBt`
   local (only the deleted legacy branch read it — verified by grep).
   New-path `dispatch(StartRecording)` is now unconditional.
3. `stopRecording()` legacy true-branch. New-path snapshot +
   `dispatch(StopRecordingAndSend)` is now unconditional.
4. REPROCESS `handleReprocessSend` legacy true-branch (`JobExecutor.start`)
   **+ the now-dead `JobRequest.TranscriptionPipeline request`
   construction** (only the deleted legacy branch consumed it — verified
   by method-scoped grep: the new-path branch builds its own request via
   the adapter). New-path `submitReprocess` route is unconditional.
5. The 5 predicate-helper boolean conjuncts: `if
   (!USE_LEGACY_RECORDING_DRIVE && pipelineBinder != null)` → `if
   (pipelineBinder != null)`. The legacy `recordingStateController`
   fallback in each helper is **kept** — it is genuinely reachable in
   the pre-bind window (`pipelineBinder == null`), NOT dead code; the
   prompt scopes deletion to the dead legacy *branches*, and per its
   STOP directive reachable code is not deleted. (RenderBackend/legacy-
   controller retire is Theme-C/C3 scope per C5-IMPL-2, not C7.)
6. The 6 now-stale `USE_LEGACY_RECORDING_DRIVE` mentions in comments /
   KDoc (IME section comment + the 3 Kotlin doc refs + the
   resolver-bind comment) rewritten to describe the new sole-driver
   reality (required for the AC-10 `grep app/src/main → ZERO` invariant).

**RESUME carve-out (NOT deleted, NOT fenced, NOT rerouted):** both
`if (USE_LEGACY_RECORDING_DRIVE)` and `else` branches in `startResumeJob`
were byte-identical `JobExecutor.INSTANCE.start(this, request)` calls.
Collapsing the dead `if/else` to the single unconditional call they
both contained is exactly behaviour-preserving and is the
carve-out-mandated treatment ("leave the resume path exactly as it
is"). RESUME stays legacy `JobExecutor.start`, unconditionally. The
comment was rewritten to record the carve-out (no `USE_LEGACY` ref).

**AC-10 single-architecture verdict: GREEN — sole driver confirmed.**
- **Fresh recording:** the orchestrator (`startRecording()` →
  `dispatch(StartRecording)`, `stopRecording()` →
  `dispatch(StopRecordingAndSend)`) is the **sole** driver. The legacy
  `recordingStateController.startRecording/stopRecording` record-button
  branches are deleted; no user record-button action starts a legacy
  pipeline.
- **Reprocess:** the orchestrator C3 adapter (`submitReprocess`) is the
  **sole** driver; the legacy IME `JobExecutor.start` reprocess site is
  deleted.
- **RESUME:** the **only** surviving IME `JobExecutor.start` driven by a
  recording-class user action — single-dispatch, no orchestrator
  equivalent (documented exception, C6-IMPL-2). Confirmed.
- **Imported-audio-file** (`onStartInputView` → `Pref.TranscriptionAudioFile`):
  a second surviving `JobExecutor.start` (`:2554`) — see C7-IMPL-1. It
  is single-dispatch (a one-shot Settings action cleared at
  `onStartInputView:1958`, not the record button); no double-dispatch
  with the orchestrator recording path. AC-10's "no code path starts
  BOTH a legacy and a new pipeline for the same user action" holds.

**RESUME-only legacy survivor?** At C7-close there were **2** surviving
IME `JobExecutor.start` sites: RESUME (carve-out, expected) **and** the
imported-audio-file path (`:2554`, C7-IMPL-1, a pre-existing
C5-overlooked gap, NOT a C7 regression). **→ Resolved by
mid-chunk-triage wave B2-C7-MID-W1:** the imported-file `:2554` site is
now orchestrator-routed (`PipelineAction.TriggerPipeline`) and deleted.
The IME now has exactly **1** surviving `JobExecutor.INSTANCE.start`:
RESUME (`:3222`, the documented carve-out). See `### Mid-Chunk-Triage
Wave B2-C7-MID-W1`.

**Files created/modified (production, Commit 1):**
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`
  (constant+Javadoc deleted; `startRecording`/`stopRecording` legacy
  branches + `useBt` deleted; fresh `runTranscriptionViaOrchestrator`
  legacy guard collapsed to unconditional `JobExecutor.start` for the
  still-live import path [C7-IMPL-1]; RESUME `if/else` collapsed to the
  byte-identical unconditional call; REPROCESS legacy branch + dead
  `request` deleted, `submitReprocess` unconditional; 5 predicate-helper
  boolean conjuncts removed; section comment rewritten)
- `app/src/main/java/net/devemperor/dictate/core/ImePipelineConfigResolver.kt`
  (stale `USE_LEGACY_RECORDING_DRIVE` throw-message clause removed)
- `app/src/main/java/net/devemperor/dictate/core/PipelineRunnerSubsystemAdapter.kt`
  (stale `USE_LEGACY_RECORDING_DRIVE` KDoc sentence removed)
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt`
  (stale `USE_LEGACY_RECORDING_DRIVE` comment sentence removed)

**Files in chunk-scope:** `DictateInputMethodService.java` (Epic §4
B3-final / the C7 prompt). The 3 Kotlin doc-only edits are
in-scope-by-necessity: the AC-10 acceptance grep is
`grep app/src/main → ZERO`, which fails on a stale doc string anywhere;
removing the now-false references is required to satisfy the stated
invariant (no behaviour change — comment/KDoc text only).
**Files outside chunk-scope (drift):** none (the Kotlin edits are
doc-only and required by the chunk's own acceptance grep).

**Plan deviations:**

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| Fresh-recording `JobExecutor.start` (`:2596`→`:2554`) **kept (made unconditional), NOT deleted** | C7 prompt "DELETE: the fresh-recording legacy branch … keep ONLY the new-path `pipelineBinder.dispatch(StartRecording...)` else-branch unconditionally" + C7 carve-out blockquote | The C7 prompt's premise — "the new path dispatches `StartRecording`/`StopRecordingAndSend` so `:2596` is dead" — and the C5/C6 grep tables' claim that `runTranscriptionViaOrchestrator` "is unreachable on new path — only the legacy `onRecordingCompleted` callback reaches it" are **incomplete**. `runTranscriptionViaOrchestrator()` has a **second, still-live caller**: `onStartInputView():1959` — the Settings *imported-audio-file transcription* feature (`Pref.TranscriptionAudioFile`). That path has no recording FSM (the file already exists) and the orchestrator exposes **no "transcribe a pre-existing file" entry-point**, so the legacy `JobExecutor.start` is its ONLY working route. The actual else-branch is also NOT a `dispatch(StartRecording)` (the prompt assumed it was) — it is a `Log.w` + `uiController.stopPipeline()` no-op. Following the literal instruction (keep only the no-op else-branch) would **silently break imported-audio-file transcription** (AC-9 "no net deletions of behaviour coverage" regression). Per the prompt's explicit STOP directive ("If a 'dead' branch turns out to still be reachable, STOP and flag — do not delete reachable code") + AGENT-CONTEXT D7 (reachable "dead" branch → don't delete, flag): the `JobExecutor.start` was kept and made **unconditional** (the dead `if/else` boolean + the now-redundant no-op else-arm — which only existed as the double-dispatch defence for the now-removed boolean window — were removed). | Theme C: routing imported-audio-file transcription through the orchestrator is a NEW `RecordingAction`/`PipelineAction` + reducer arm (an architecture change), beyond C7's pure-deletion scope. The legacy `recordingStateController` controllers must NOT be retired by a later C-block until this path has an orchestrator route (a residual live consumer of the legacy pipeline trigger remains). The single-architecture invariant (AC-10) is **structurally not yet fully met for the import-file action** — flagged C7-IMPL-1. | inline-fixed (mid-size: collapsed the dead boolean guard, kept the reachable call) → marker `plan-deviation-resolved`; **plus** Critical architecture issue C7-IMPL-1 (`architecture-conflict`) for orchestrator routing — the *deletion* could not proceed for this site, which is a plan-premise conflict, not just a deviation |
| REPROCESS dead `JobRequest request` construction also deleted | C7 prompt "Any now-dead helper/field that ONLY the deleted legacy branches used (verify each is truly unused before deleting — grep)" | The reprocess `JobRequest.TranscriptionPipeline request` (`:3370-3383` pre-C7) was consumed **only** by the deleted legacy `JobExecutor.start` (method-scoped grep confirmed: the new-path branch builds its own request inside `submitReprocess`). Left in place it is an unused local (dead code). | none — pure dead-code removal local to `handleReprocessSend` | inline-fixed (small + locally decidable) |
| RESUME `if/else` collapsed to one unconditional call | C7 carve-out ("Just leave the resume path exactly as it is. Do NOT fence it, do NOT delete it") | Both branches were byte-identical `JobExecutor.INSTANCE.start(this, request)`. With the boolean deleted the dead `if/else` has no meaning; collapsing to the single call they both contained is exactly behaviour-preserving and is the only way to "leave the resume path exactly as it is" once the boolean (which the carve-out itself says C7 removes) is gone. | none — RESUME behaviour byte-identical; AC-10 single-dispatch holds | inline-fixed (small: mechanical, behaviour-preserving) |

#### Plan-Correctness Fix (B2-C7-B3-IMPL-PLAN-FIX)

Plan-requirement check (C7 prompt DELETE list + AC-10 + carve-out + AFTER-grep):

| Requirement | Status |
|---|---|
| Delete `USE_LEGACY_RECORDING_DRIVE` constant decl | ✓ |
| `grep -rn USE_LEGACY_RECORDING_DRIVE app/src/main` → ZERO | ✓ (incl. 3 Kotlin doc refs — required by the acceptance grep) |
| Delete fresh-recording legacy branch; new path unconditional | △ — the record-button fresh path (`startRecording`/`stopRecording` legacy branches) **is** deleted (orchestrator sole driver). The `:2596` `JobExecutor.start` is **kept unconditional** because it is reachable via the imported-audio-file path — STOP-directive honoured; flagged C7-IMPL-1 (Critical, `architecture-conflict`). |
| Delete reprocess legacy branch; C3-adapter route unconditional | ✓ (+ dead `request` removed) |
| RESUME site untouched (carve-out) | ✓ (byte-identical branches collapsed; behaviour-preserving; not fenced/deleted/rerouted) |
| Now-dead helpers/fields removed (verified unused) | ✓ (`useBt`, reprocess `request`) |
| No `if (false)` / dead-branch residue | ✓ |
| AC-10: orchestrator sole driver for fresh-record + reprocess | ✓ (record-button + reprocess); RESUME + import-file are documented single-dispatch survivors |
| `./gradlew assembleDebug` green | ✓ |
| `./gradlew test` green (no real non-R-7 regression) | ✓ (see Tests subsection) |
| `DictateInputMethodService.java` stays Java | ✓ (edited in place, not converted) |

**Files modified in this step:** none (Step 1 deletion was scoped; the
fresh-site deviation is documented + flagged C7-IMPL-1, not silently
re-deleted).
**Files outside plan-prescribed scope (drift):** none.

#### Self-Code Fix (B2-C7-B3-IMPL-CODE-FIX)

Knowledge skills consulted: none load-bearing — this is a pure-deletion
chunk (no new patterns introduced; `knowledge-reference` plugin/envelope
patterns N/A to dead-code removal). Aspects: dead-code removal is the
entire chunk (DRY/dead-code ✓ — the dead `useBt`/`request` locals +
no-op else-arm removed, not left dangling); naming ✓ (no new
identifiers); comments ✓ — the rewritten comments explain the *new*
WHY (orchestrator sole driver; the RESUME carve-out; the C7-IMPL-1
import-path constraint) rather than restating code or leaving stale
references to the deleted switch; no `!!`/unchecked casts introduced
(none touched). The kept `pipelineBinder != null` predicate-helper
fallbacks are not a smell — they are the genuine pre-bind reachability
guard (documented inline). No additional delegated items beyond
C7-IMPL-1.

**Files modified in this step:** none beyond Step 1 (comment rewrites
were applied during Step 1 as the dead code was removed).
**Files outside chunk-scope (drift):** none.

> **Reconcile Issue Index:** **C6-IMPL-2 → fixed** (RESUME correctly
> preserved — the carve-out was honoured: `startResumeJob`'s
> `JobExecutor.start` survives unconditionally, not fenced/deleted/
> rerouted). New issue **C7-IMPL-1** (Critical, `architecture-conflict`)
> raised — see the Issue table below and the Issue Index.

**Issues:**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| C7-IMPL-1 | **Critical** | Imported-audio-file transcription (`onStartInputView:1959` → `runTranscriptionViaOrchestrator()` → `JobExecutor.INSTANCE.start`, `DictateInputMethodService.java:2554`) has **no orchestrator route**. The C7 prompt + the C5/C6 grep tables assumed `runTranscriptionViaOrchestrator` was dead-on-new-path ("only the legacy `onRecordingCompleted` callback reaches it") — they overlooked the `onStartInputView` Settings-import caller. The fresh `JobExecutor.start` was therefore kept (made unconditional) rather than deleted, so AC-10's single-architecture invariant is **structurally not yet met for the imported-audio-file user action** (a legacy `JobExecutor.start` pipeline still drives it; there is no `DictateOrchestrator` equivalent — the orchestrator exposes no "transcribe a pre-existing file" entry-point). Deleting it would silently break the feature (AC-9 behaviour-coverage regression). Routing it through the orchestrator needs a new `RecordingAction`/`PipelineAction` + reducer arm — an architecture change beyond C7's pure-deletion scope. **Also blocks the later dead-controller/legacy-pipeline retire (Theme C): a residual live consumer of the legacy `JobExecutor.start` trigger remains, so the legacy pipeline trigger cannot be fully retired until this path is migrated.** | delegated-to-orchestrator (`architecture-conflict`; `blocks-following-chunks` — Theme-C legacy-pipeline-trigger retire) | Pre-existing C5-overlooked gap surfaced by C7's reachability audit; an orchestrator-route is an architecture addition the orchestrator must scope/route (mid-chunk-triage or a Theme-C/follow-up block), not an inline IMPL fix. C7's pure-deletion mandate cannot proceed for this one site without breaking a live feature. |

**Overlooked / Known Gaps:**
- **C7-IMPL-1** (above) — the single, load-bearing finding. The
  imported-audio-file path is the reason 2 (not 1) IME
  `JobExecutor.start` sites survive; it must be routed through the
  orchestrator before the legacy pipeline trigger can be fully retired
  (Theme C / follow-up). The C7 deletion of the record-button fresh +
  reprocess legacy branches + the boolean is complete and correct;
  only this one site is held back, with rationale.
- The legacy `recordingStateController` itself (and its
  animation/amplitude/`onRecordingCompleted` UI sites) is NOT in C7's
  deletion scope (C5-IMPL-2 — Theme-C/C3 dead-controller retire). The
  predicate-helper legacy fallbacks are kept for the genuine pre-bind
  reachability window (not dead code).
- Manual device E2E (two-keyboard survival, FGS) not run (no device) —
  covered by the C6-D2pre RE-GATE GREEN that authorised C7 + the
  auto-tier below.

**Commit boundaries (orchestrator splits — lists disjoint;
**Commit 1 separately committed for git-revert isolation, Epic §6.2
invariant 3**):**
- **Commit 1 (production):**
  `core/DictateInputMethodService.java`,
  `core/ImePipelineConfigResolver.kt`,
  `core/PipelineRunnerSubsystemAdapter.kt`,
  `core/DictatePipelineService.kt`
- **Commit 2 (tests):** **none** — see Tests subsection (no test-contract
  update was needed; C5 wrote no boolean-toggle test cases, so there is
  nothing to convert/remove and no new test file). The deletion is
  validated by the existing, unchanged new-path coverage staying green.

#### Tests (B2-C7-B3-IMPL-TEST) + Test-Review (B2-C7-B3-IMPL-TEST-FIX)

**Test-contract update note (expected, documented — NOT a regression):**
The C7 prompt anticipated converting/removing "the C5
`ImeRecordingDriveCutoverTest` boolean-toggle cases". On inspection
**no such cases exist**: C5's test-review explicitly bounded its tests
to the new-path surface (StartRecording→Active+notification,
Pause/Resume, Cancel→dismiss, StopRecordingAndSend→resolver, seamless
hand-off) — `ImeRecordingDriveCutoverTest.kt` never sets
`USE_LEGACY_RECORDING_DRIVE=true` and never reflects/toggles it (grep:
zero `USE_LEGACY`/`legacy`/`toggle`/reflection in the file). It already
tests the default-false path, which C7 makes the **only** path — so the
existing assertions are exactly the "assert the unconditional new path"
coverage the prompt asks for, with **no edit required**. A repo-wide
`grep -rn USE_LEGACY_RECORDING_DRIVE app/src/test` returns only a single
**doc-comment** in `DictateCutoverE2ETest.kt:37` (descriptive prose, no
compile dependency) — left as-is (it correctly describes the new-path
intent; not a stale toggle reference). No test file changed → Commit 2
is empty for this pure-deletion chunk.

**Test-Run-Result:** `./gradlew testDebugUnitTest --rerun-tasks` (fresh
full suite, no cache) → **1038 tests, 1037 pass, 1 fail**. The single
failure is `LegacyAudioFileMigrationTest > run promotes recoverable
legacy-path sessions to FAILED` — the **documented R-7 order-dependent
shared-singleton pollution flake** named explicitly in the C7 prompt's
acceptance ("`LegacyAudioFileMigrationTest`×1 … is NOT a regression: it
fails only in uncached full-runs, passes 100% isolated"). **Confirmed
not a C7 regression by isolated re-run:** `./gradlew testDebugUnitTest
--rerun-tasks --tests "*.LegacyAudioFileMigrationTest"` → **BUILD
SUCCESSFUL** (100% pass isolated). C7's diff touches zero
migration/DB/JobExecutor production files (pure IME dead-branch deletion
+ Kotlin doc-comment edits) — it causally cannot introduce migration
pollution. **No OTHER failure** (the prompt's real-regression trigger).
AC-9 regression invariant holds: 1037 genuine passes ≫ ~946 baseline,
no net behaviour-coverage deletion (the import-file path's coverage is
preserved by keeping its `JobExecutor.start` — C7-IMPL-1).
`./gradlew assembleDebug` → BUILD SUCCESSFUL.

**Code-bugs found while writing/reviewing tests:** none (no test edits;
pure-deletion chunk — the existing new-path tests are the deletion's
validation and stayed green).

#### Mid-Chunk-Triage *(not armed for C7 by prompt; finding raised for orchestrator routing)*

C7-IMPL-1 is Critical with `architecture-conflict` + a
`blocks-following-chunks` characteristic (the Theme-C legacy-pipeline-
trigger retire cannot complete while a live consumer of
`JobExecutor.start` remains). Per AGENT-CONTEXT, the IMPL agent flags;
the orchestrator decides whether to trigger mid-chunk-triage. The C7
*deletion mandate itself is complete* for everything it can safely
delete (the boolean, the record-button fresh legacy branches, the
reprocess legacy branch + dead locals); only the single import-file
`JobExecutor.start` is held back, with full rationale + a clear
orchestrator-route recommendation (new `RecordingAction`/`PipelineAction`
+ reducer arm — a Theme-C/follow-up architecture addition, not an inline
IMPL fix).

> **→ RESOLVED by mid-chunk-triage wave B2-C7-MID-W1 (see below).**

### Mid-Chunk-Triage Wave B2-C7-MID-W1

**Agent-IDs:** `B2-C7-MID-RES-1` (research) → `B2-C7-MID-REPAIR-1`
(repair) → `-VERIFY` (self-check), one session. **Wave:**
B2-C7-MID-W1, iter 1 (iter-cap 2). **Triggering issue:** C7-IMPL-1
(Critical, `architecture-conflict`, `blocks-following-chunks`).
**Wave-Commit:** ⏳ (orchestrator).

> Research pointer: `../research/imported-audiofile-orchestrator-route.md`

**Design decision — route (b): reuse the existing pipeline-trigger
dispatch with a pre-supplied audioFile and no recording step, via the
existing `Action.PipelineAction.TriggerPipeline`.** No new Action /
reducer arm. `TriggerPipeline(sessionId, audioFile)` is the documented
Spec 1 §3 pipeline entry-point that the recording FSM itself emits
(`RecordingModule.Effect.EmitPipelineTrigger`); `PipelineModule`
reduces it `Idle → Preparing` + `Effect.SubmitPipeline` →
`PipelineRunnerSubsystemAdapter.submit` →
`ImePipelineConfigResolver.resolveFresh` → `JobExecutor.start` **inside
the C3 adapter** (the sole legacy start site). Spec-faithful: Spec 1
specifies **no** dedicated imported-file Action — the source-agnostic
`(sessionId, audioFile)` `TriggerPipeline` IS the canonical entry. A
bespoke `SubmitImportedFile` Action (option a) would be a redundant
second entry-point for an identical submit (rejected, D4); the
reprocess route (option c) is semantically distinct
(`REPROCESS_STAGING`/`reuseSessionId`/staging-FSM) — an imported file
is a *fresh* `RECORDING`-kind transcription (rejected c). ADR-0001
single-dispatch ✓; ADR-0002 Mode-1 same-axis effect, no Mode-3 ✓.

**R-1 fidelity (AC-9):** the repurposed method reuses the existing C5
`captureFreshConfigSnapshot` helper verbatim — it was *extracted from
the very `runTranscriptionViaOrchestrator` method being replaced* in
C5, so the `JobRequest` is provably field-for-field identical to the
deleted legacy `:2507-2523` construction (zero duplicated config logic,
no silent drift). Busy-toast user feedback preserved via an
`ActiveJobRegistry.isAnyActive()` pre-check (mirrors the established
new-path reprocess route in `handleReprocessSend`); the structural
single-submit guard is the `PipelineUiState.Idle` reducer edge.

**What changed:**
- `runTranscriptionViaOrchestrator()` → renamed/repurposed to
  `transcribeImportedAudioFileViaOrchestrator()`: not-ready guard +
  `ActiveJobRegistry.isAnyActive()` busy pre-check +
  `infoBarController.dismiss()`/`updatePromptButtonsEnabledState()`/
  `primePipelineUiForNewPath()` UI bookkeeping + `captureFreshConfigSnapshot(sessionId)`
  + `pipelineBinder.dispatch(PipelineAction.TriggerPipeline(sessionId,
  audioFile))`. The legacy `:2554` `JobExecutor.INSTANCE.start` + the
  dead `JobRequest request` construction + `preAllocatedId` local +
  the inline `pendingLivePromptChain`/`livePrompt`/`autoSwitchKeyboard`
  reset (now done inside the shared snapshot helper) are **deleted**.
- Both callers updated: `onStartInputView` (`Pref.TranscriptionAudioFile`,
  the live caller) + the dead legacy `onRecordingCompleted` callback
  (kept compiling, routed to the same orchestrator entry-point; the
  legacy-controller retire is Theme-C/C3 scope per C5-IMPL-2).
- 4 stale `runTranscriptionViaOrchestrator()` doc references rewritten
  to point at `captureFreshConfigSnapshot` / the new method (the
  legacy construction they described was C7-deleted).

**AFTER grep:** `grep -n JobExecutor.INSTANCE.start
DictateInputMethodService.java` → **exactly 1 site**: `:3222`
(`startResumeJob` — the RESUME carve-out, C6-IMPL-2). The imported-file
`:2554` site is gone. **AC-10 fully GREEN modulo the documented RESUME
exception.** Theme-C legacy-pipeline-trigger retire is unblocked (no
residual live consumer of the legacy `JobExecutor.start` trigger
besides the RESUME recovery path).

**Files modified (production — for wave-commit; DISJOINT from test list):**
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`
  (method repurpose + legacy `:2554` deletion + 2 caller updates + 4
  doc-ref rewrites)

**Files modified (test — for wave-commit; DISJOINT from production list):**
- `app/src/test/java/net/devemperor/dictate/core/ImeRecordingDriveCutoverTest.kt`
  (2 NEW tests: imported-file `TriggerPipeline` → IME-faithful resolver
  with no recording FSM; second-trigger-while-running no-op /
  single-submit guard)
- `app/src/test/java/net/devemperor/dictate/core/ImePipelineConfigResolverTest.kt`
  (1 NEW test: imported-file fresh snapshot rebuilds the legacy
  `:2507-2523` JobRequest field-for-field — R-1/AC-9 config parity)

**Files outside findings-scope (drift):** none. All edits are the
C7-IMPL-1 orchestrator-route + its tests + the doc-refs the route
rendered stale (no behaviour change in the doc edits).

**Test result:** `./gradlew assembleDebug` green; `./gradlew test`
green — 2082 tests across debug+release variants (~1041/variant =
~1038 baseline + 3 new), 0 failures, 0 errors. The known R-7
`LegacyAudioFileMigrationTest` / `PipelineRunnerSubsystemAdapterTest`
order-dependent flake did **not** surface (full suite green; no
migration/DB files touched by this wave) — not a regression.

**Plan deviations (D22):**

| Deviation | Plan Location | What changed | Why | Impact | Resolved? |
|-----------|---------------|--------------|-----|--------|-----------|
| Imported-file path routed via existing `PipelineAction.TriggerPipeline` (no new Action) | C7-IMPL-1 hypothesised "needs a new `RecordingAction`/`PipelineAction` + reducer arm" | Reused the existing documented `TriggerPipeline` entry-point — it already carries exactly `(sessionId, audioFile)` and is what the recording FSM itself emits; a new Action would be a redundant second entry-point for an identical submit | Smaller, more maintainable, fully spec-faithful change than the IMPL agent's worst-case estimate (the C3 submit seam already supported it). Theme-C dead-controller retire unblocked. | resolved (this wave) |
| Dead `onRecordingCompleted` legacy callback kept (re-routed, not deleted) | — | C7 deleted the legacy record-button branches so the callback is dead, but removing the whole callback structure is broader (Theme-C/C3 dead-controller retire, C5-IMPL-2) than this triage's scope | Minimal, scoped change; the callback compiles + is behaviourally equivalent if any future legacy caller reaches it | scoped-out (Theme-C owns) |

**Self-check (validate-fixes):** Completeness ✓ (C7-IMPL-1 fully
addressed). New problems ✓ none (build + full suite green). Type-safety
✓ (no `any`/unchecked casts; `assertSame` import added).
Imports/exports ✓ (`JobRequest` import retained — still used by RESUME
`:3197`; no orphaned imports). Consistency ✓ (mirrors the established
new-path reprocess pattern). Behaviour preserved ✓ (config
field-faithful via the shared extracted helper; busy-toast + UI
bookkeeping + live-prompt-chain preserved). Deterministic tests ✓ (the
new tests reuse the file's existing `pumpUntil` spin-wait discipline +
`tearDown` JobExecutor/DB reset; no timing/ordering dependency
introduced). RESUME path untouched ✓. **Convergence: ✓ converged** —
no new issues, no forwarded issues.

---

## Block-Validate (Phase 3.2)

**Status:** ⏳ · **Pre-Validate Commit:** ⏳ · **Validate-Pass Commit:** ⏳

| Topic | Agent-ID | Status | Output File | Findings |
|-------|----------|--------|-------------|----------|
| plan-and-api | `B2-AUDIT-PLAN-AND-API` | ⏳ | `./reports/audit-plan-and-api-B2.md` | — |
| convention | `B2-AUDIT-CONVENTION` | ⏳ | `./reports/audit-convention-B2.md` | — |
| logic | `B2-AUDIT-LOGIC` | ⏳ | `./reports/audit-logic-B2.md` | — |
| test | `B2-AUDIT-TEST` | ⏳ | `./reports/audit-test-B2.md` | — |

### Sanity-Check Consolidator

**Agent-ID:** `B2-VAL-SANITY` · **Output:** `./reports/validated-findings-B2.md`

### Mini-Triage + Repair-Wave(s)

(Per iteration, max 3 per D5 soft-cap.)

---

## Block Deviation Summary

| # | Plan Location | What changed | Why | Impact | Inline-fixed | Source-Agent | Source-Step |
|---|---------------|--------------|-----|--------|--------------|--------------|--------------|
| — | — | — | — | — | — | — | — |

---

## Block Closeout (Orchestrator)

- **All chunks complete (5-step, both commits):** ⏳
- **C6-D2pre gate GREEN (authorises C7 + Theme C):** ⏳
- **Block-Validate converged:** ⏳
- **AUDIT-TEST: coverage + no cross-chunk regressions:** ⏳
- **Build green at block-end:** ⏳
- **Issue index reconciled:** ⏳
- **Cross-block-API consumer info forwarded to B3:** ⏳

**Block completed at:** ⏳
**Block-End-Commit:** ⏳
