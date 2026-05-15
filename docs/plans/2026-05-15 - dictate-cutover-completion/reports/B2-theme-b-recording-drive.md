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

**Severity counts:** Critical: 0 · Important: 2 (resolved by C5) · Nice-to-have: 2 (1 resolved, 1 still-deferred) · Postponed: 0 · New from C5: Important: 2 (delegated), Nice-to-have: 1 (postponed)

| ID | Source agent | Severity | Status | Title | Source phase |
|----|--------------|----------|--------|-------|--------------|
| C3-IMPL-1 | B2-C3-B1-IMPL | Important | **fixed (C5)** | Fresh-recording config resolver: 8 IME-runtime fields not on orchestrator path. **C5 closed it** via `ImePipelineConfigResolver` (IME snapshots all 8 at the send-tap) + `DelegatingPipelineConfigResolver` + `LocalBinder.registerPipelineConfigResolver`. All 8 fields threaded 1:1 (see C5 fidelity table). | step-1-impl (C3-B1) → fixed C5 |
| C3-IMPL-2 | B2-C3-B1-IMPL | Nice-to-have | **fixed (C5)** | Reprocess modelOverride/targetAppPackage null + AutoFormatting +1. **C5 closed it**: `ImePipelineConfigResolver.snapshotReprocess` threads selectedModel/targetAppPackage/totalSteps; the new-path reprocess branch in `handleReprocessSend` routes via the C3 adapter with the snapshot. | step-1-impl (C3-B1) → fixed C5 |
| C4-IMPL-1 | B2-C4-B2-IMPL | Important | **fixed (C5)** | `NotificationStatus.Recording` had no emitter + no `Paused` variant. **C5 closed it**: added `NotificationStatus.Paused`, the coordinator `Paused` arm (`[Resume][Stopp][Senden]` + recording_paused subtitle), and `RecordingModule.Effect.UpdateNotification`/`DismissNotification` emitted across the FSM (Active→Recording, Paused→Paused, Resume→Recording, Stop/Cancel→Dismiss; StopRecordingAndSend deliberately NO dismiss for a seamless Recording→Pipeline hand-off). | step-1/2 (C4-B2) → fixed C5 |
| C4-IMPL-2 | B2-C4-B2-IMPL | Nice-to-have | postponed | `Pipeline` notif subtitle generic, no F-13 counters in `NotificationStatus.Pipeline`. Still-deferred (cosmetic; live counter already in record-button label; needs a `NotificationStatus.Pipeline` payload change across PipelineModule emit-sites — out of C5 recording-trigger scope). | step-1/2 (C4-B2) |
| C5-IMPL-1 | B2-C5-B3-IMPL | Important | delegated-to-orchestrator | New-path **AudioFocus not requested** + **Bluetooth SCO route not established**. Pre-existing dormant-layer gap (AudioModule `RequestAudioFocus` never emitted on recording start; `RecordingHardwareAdapter` sets the MediaRecorder source but does not start the BT SCO connection). Surfaced (not caused) by C5 making the path live. Guarded fallback (`USE_LEGACY_RECORDING_DRIVE=true`) restores full legacy BT/audio-focus. Owner: a follow-up audio-subsystem-wiring block / C6-D2pre will surface it. NOT a fresh-recording R-1 silent-config-loss (the JobRequest is field-faithful) and NOT an architecture-conflict blocking C6/C7. | C5 step-1/3 |
| C5-IMPL-2 | B2-C5-B3-IMPL | Important | delegated-to-orchestrator | Legacy recording **UI/animation/keyboard-hide-pause** sites (~12 `recordingStateController.getState()` reads outside the record-button gate: `:730`/`:1215`/`:1855`/amplitude/timer/onKeyboardHidden) stay legacy-driven; on the new path the legacy controller is never started so they read Idle (no legacy recording animation, no legacy keyboard-hide auto-pause). The FGS notification (AC-2) is the authoritative new-path recording-active surface. The RenderBackend recording-UI migration is Theme-C/C3, out of C5's recording-trigger scope. Documented Known-Gap. | C5 step-2 |
| C5-IMPL-3 | B2-C5-B3-IMPL | Nice-to-have | postponed | RESUME (`startResumeJob`, JobExecutor.start #2) has no orchestrator equivalent (`PipelineRunnerSubsystem` has no `resume`); both boolean branches keep legacy `JobExecutor.start` (single-dispatch, orthogonal to the fresh-recording cutover). Adding a resume subsystem action is an architecture change beyond C5 (prompt forbids a fragile flip). C7/later owns retiring it. | C5 step-1 |

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

**Agent-IDs:** `B2-C6-D2pre-IMPL` · **Status:** ⏳ pending · **Risk:** Gate
**GATE OUTPUT:** green → orchestrator authorises C7 + Block B3; red → mid-chunk-triage, NO deletion.
(subsections filled when chunk runs)

---

### Chunk C7-B3 — legacy call-site deletion (GATED on green C6)

**Agent-IDs:** `B2-C7-B3-IMPL` · **Status:** ⏳ blocked-on-C6 · **Risk:** Med (pure delete of proven-dead code)
(subsections filled when chunk runs — separately committed for git-revert isolation, Epic §6.2)

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
