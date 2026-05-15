# Research — Imported-Audio-File Orchestrator Route

**Date:** 2026-05-15
**Triggered by:** C7-IMPL-1 (Critical, `architecture-conflict`, `blocks-following-chunks`)
**Block:** B2 — Theme B (Recording-Drive Cutover)
**Agent-ID:** B2-C7-MID-RES-1 (mid-chunk-triage wave B2-C7-MID-W1, iter 1)

---

## Problem

C7 deleted the legacy fresh-recording + reprocess `JobExecutor.start`
branches. A reachability audit found a SECOND still-live legacy
`JobExecutor.INSTANCE.start` caller the C5/C6 grep tables overlooked:
the Settings **imported-audio-file transcription** feature.

- `onStartInputView()` (`DictateInputMethodService.java:1911`) checks
  `Pref.TranscriptionAudioFile`; when set it points `audioFile` at a
  user-picked pre-existing file in `cacheDir/audio/`, clears the pref,
  and calls `runTranscriptionViaOrchestrator()` (`:1923`).
- `runTranscriptionViaOrchestrator()` builds a
  `JobRequest.TranscriptionPipeline` (`:2507-2523`) and calls
  `JobExecutor.INSTANCE.start(this, request)` (`:2554`).
- This path has **no recording FSM** (the file already exists — no
  `StartRecording`/`StopRecordingAndSend`).

C7 (correctly, per its STOP directive) kept this call-site live rather
than delete reachable code. That leaves a residual legacy-pipeline
trigger consumer, violating AC-10 (single-architecture: only RESUME is
the documented legacy exception) and blocking Theme-C
(C10 PipelineOrchestrator disposition).

## Sources

1. **Code — the C3/C5 submit seam** (`PipelineRunnerSubsystemAdapter.kt`,
   `ImePipelineConfigResolver.kt`, `PipelineModule.kt`,
   `RecordingModule.kt`, `Action.kt`). The orchestrator already exposes
   a runner submit path. `PipelineModule.reduce` line 133-146:
   `Action.PipelineAction.TriggerPipeline(sessionId, audioFile)` from
   `PipelineUiState.Idle` → `Effect.SubmitPipeline(sessionId,
   audioFile)`; `runEffect` line 397:
   `Effect.SubmitPipeline → services.pipelineRunner.submit(sessionId,
   audioFile)` → `PipelineRunnerSubsystemAdapter.submit` →
   `configResolver.resolveFresh(sessionId, audioFile)` →
   `ImePipelineConfigResolver.resolveFresh` (the IME-faithful resolver,
   since the IME is bound and registered it at `onServiceConnected`).
   `RecordingModule.kt:157` confirms the recording path itself only
   emits `Action.PipelineAction.TriggerPipeline(sessionId, audioFile)`
   via `emitAction` — i.e. `TriggerPipeline` IS the canonical
   pipeline-entry action; the recording FSM is just one producer of it.

2. **Spec 1** (`docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/
   research/1-pipeline-service/1-pipeline-service.reviewed.md`). §3
   defines the pipeline-entry action as `TriggerPipeline` →
   `Effect.SubmitPipelineJob(sessionId, audioFile)` (§4.11.6.1 line
   1711-1732). Spec 1 specifies **no** dedicated "imported file" /
   "external submission" / "transcribe a pre-existing file" Action —
   the pipeline-entry contract is `(sessionId, audioFile)`-shaped and
   source-agnostic. There is therefore no verbatim spec path to follow;
   the minimal spec-faithful route is to reuse the existing
   `TriggerPipeline` entry-point (it already carries exactly
   `(sessionId, audioFile)` and requires no recording step).

3. **Epic plan** (`dictate-cutover-completion.md`) §2 AC-10
   (single-architecture: `DictateOrchestrator` sole state-router;
   `JobExecutor`/`PipelineOrchestrator` survive only behind
   `PipelineRunnerSubsystem`), §3.2 AFTER seam (IME dispatches; the
   runner adapter is the only `JobExecutor.start` site), AC-9 (no
   behaviour-coverage regression — the imported-file feature must keep
   working). The block-report `### Chunk C3-B1` R-1 fidelity table +
   `### Chunk C5-B3` snapshot mechanism (the established
   "snapshot config at trigger instant → dispatch → adapter resolves
   via snapshot" pattern, mirrored by `handleReprocessSend:3386-3394`).

## Findings

**Consensus:** The C3 `submit(sessionId, audioFile)` seam already
fully supports an imported file — that IS how a fresh recording reaches
the runner (`StopRecordingAndSend` → `EmitPipelineTrigger` →
`emitAction(TriggerPipeline)` → `SubmitPipeline` → `submit`). The
imported-file case is "submit this user-picked file to the pipeline
with no preceding recording step". The only missing piece is an
IME → orchestrator route that does NOT first drive the recording FSM.

**`TriggerPipeline` is that route, already.** It is a public
`Action.PipelineAction`, dispatchable via
`LocalBinder.dispatch(action: Action)` (`DictatePipelineService.kt:1085`).
The `PipelineUiState.Idle` reducer guard (line 133-146) is the
single-submit protection — a second trigger while a pipeline is
`Preparing`/`Running` reduces to `null` (no-op), structurally
equivalent to the legacy `JobExecutor.start` returning `false`
(busy). PipelineModule additionally emits the
`NotificationStatus.Pipeline` effect on the same arm, matching the
new-path behaviour for record-button submits.

**Config fidelity is already solved.** `ImePipelineConfigResolver`
(C5) is the IME-faithful resolver registered at `onServiceConnected`.
`resolveFresh(sessionId, audioFile)` consumes a snapshot keyed by
`sessionId`. The IME already has `captureFreshConfigSnapshot(sessionId)`
(`:2342-2385`) which computes the 8 IME-runtime fields **identically**
to the legacy `runTranscriptionViaOrchestrator()` construction (it was
extracted from it in C5 — verified field-by-field: `totalSteps`,
`audioFilePath`, `language`, `queuedPromptIds`, `targetAppPackage`,
`stylePrompt`, `livePrompt`, `autoSwitchKeyboard`, `showResendButton`,
plus the `pendingLivePromptChain` / `livePrompt=false` /
`autoSwitchKeyboard=false` post-snapshot reset that mirrors legacy
`:2525-2527`). So the imported-file path can reuse
`captureFreshConfigSnapshot` verbatim — guaranteeing R-1
field-faithfulness with **zero duplicated config logic**.

**Outlier / nuance — `audioFile` field.** Both the legacy
`runTranscriptionViaOrchestrator` and `captureFreshConfigSnapshot`
read the IME instance field `audioFile` (`:2511` /
`:2368 audioFile.getAbsolutePath()`). `onStartInputView:1919` already
sets `this.audioFile` to the imported file before calling. So the
existing snapshot helper picks up the imported file with no change.
(D-14 removes the `audioFile` field later in Theme C; until then it is
the faithful source, identical to the legacy path's source.)

**Methodology limitation:** the new-path busy-collision UX differs
slightly from legacy — legacy `runTranscriptionViaOrchestrator` showed
`showJobBusyToast()` when `JobExecutor.start` returned `false`. The
new `TriggerPipeline` route silently no-ops (Idle-guard) like every
other new-path submit. This is intentional and consistent with the
C3 adapter KDoc decision (the FSM edge is the single-submit guard; the
legacy busy-toast lived only on the now-deleted legacy call-site). To
preserve the imported-file user feedback faithfully, the IME pre-checks
`ActiveJobRegistry.isAnyActive()` before dispatching and shows the
busy toast then — exactly the pattern `handleReprocessSend:3382-3385`
already established for the new-path reprocess route. This keeps
behaviour parity (AC-9) without re-introducing a busy boolean on the
action.

## Implementation Hints

**Chosen route: (b) reuse the existing pipeline-trigger dispatch with a
pre-supplied audioFile and no recording step — via the existing
`Action.PipelineAction.TriggerPipeline`.**

Rationale (D4 long-term maintainability, fewest special-cases,
spec-alignment; ADR-0001 single-dispatch; ADR-0002 no Mode-3):

- **No new Action / reducer arm.** `TriggerPipeline` is the documented
  Spec 1 §3 pipeline-entry action and is *already* the exact thing the
  recording FSM emits. Adding a bespoke
  `SubmitImportedFile`/`RecordingAction` variant (option a) would be a
  redundant second entry-point for an identical
  `(sessionId, audioFile)` submit — more surface, more reducer arms,
  worse extensibility. Rejected.
- **Not the reprocess route (option c).** Reprocess is semantically
  distinct (`REPROCESS_STAGING` kind, `reuseSessionId` set, staging
  FSM). The imported file is a *fresh* `RECORDING`-kind transcription
  of a brand-new session — semantically it IS a fresh submit, just
  without the record step. Reusing the fresh `TriggerPipeline` +
  `ImePipelineConfigResolver.snapshotFresh` (RECORDING kind) is the
  correct taxonomic fit. Rejected (c).
- **Maximal DRY / R-1 safety.** Reusing `captureFreshConfigSnapshot`
  (already field-faithful to the legacy construction, extracted from
  the very method we are replacing) means the imported-file
  `JobRequest` is provably identical to what `:2507-2523` built —
  closing C7-IMPL-1 with no field-drift risk.
- **ADR-compliant.** Dispatch through `pipelineBinder` →
  `orchestrator.dispatch` (single-dispatch, ADR-0001).
  `TriggerPipeline` reduces to a Mode-1 same-axis `Effect.SubmitPipeline`
  (no Mode-3, ADR-0002). The runner adapter is the sole
  `JobExecutor.start` site (AC-10 §3.2 AFTER seam).

### Concrete change (production)

In `DictateInputMethodService.java`, replace the body of
`runTranscriptionViaOrchestrator()` with the orchestrator-routed
imported-file submit. Because the only remaining caller of
`runTranscriptionViaOrchestrator()` is `onStartInputView:1923` (the
imported-file path — the legacy `onRecordingCompleted` caller is dead
since C5 and the record-button path is C7-deleted), rename/repurpose
it to `transcribeImportedAudioFileViaOrchestrator()` and have it:

1. Guard: if `pipelineBinder == null || imePipelineConfigResolver ==
   null` → existing not-ready handling (toast / log, mirror
   `handleReprocessSend:3378-3381`); bail.
2. Busy pre-check: if `ActiveJobRegistry.INSTANCE.isAnyActive()` →
   `showJobBusyToast()`; bail (faithful to the legacy
   `started == false` branch + the established reprocess pattern).
3. Mint `String sessionId = java.util.UUID.randomUUID().toString();`
   (same as legacy `preAllocatedId` `:2506`).
4. `captureFreshConfigSnapshot(sessionId);` — reuse the existing C5
   helper verbatim. It reads the IME `audioFile` field (already set to
   the imported file by `onStartInputView:1919`), computes all 8
   IME-runtime fields identically to the deleted `:2507-2523`
   construction, snapshots them into `imePipelineConfigResolver`, and
   performs the `pendingLivePromptChain`/one-shot-flag reset (the
   legacy `:2525-2527` behaviour — keep it; live-prompt-chain on an
   imported file is byte-faithful to legacy).
5. `primePipelineUiForNewPath();` — reuse the existing C5 helper
   (the same "Sending…"/progress UI bookkeeping legacy
   `runTranscriptionViaOrchestrator()` did at `:2462-2485`).
6. `pipelineBinder.dispatch(new
   net.devemperor.dictate.state.Action.PipelineAction.TriggerPipeline(
   sessionId, audioFile));` — the audioFile is the imported `File`
   (IME field). This drives `Idle → Preparing`, emits
   `Effect.SubmitPipeline(sessionId, audioFile)` →
   `pipelineRunner.submit` → `ImePipelineConfigResolver.resolveFresh`
   (consumes the snapshot from step 4) → field-faithful `JobRequest` →
   `JobExecutor.start` **inside the C3 adapter** (the sole legacy
   start site, AC-10-compliant).

Then delete the now-truly-dead legacy `:2554`
`JobExecutor.INSTANCE.start(this, request)` + the `:2507-2523`
`JobRequest request` construction + the `preAllocatedId` local +
`pendingLivePromptChain`/`livePrompt`/`autoSwitchKeyboard` reset block
that was inline at `:2525-2527` (now done by
`captureFreshConfigSnapshot`) — they were consumed only by the deleted
`JobExecutor.start`. Verify `JobRequest` import is still used elsewhere
(it is — `startResumeJob:3229` RESUME) so do not remove the import.

After this, `grep -n "JobExecutor.INSTANCE.start"
DictateInputMethodService.java` returns exactly ONE site: `:3229`
(RESUME — the documented C6-IMPL-2 carve-out). AC-10 is fully GREEN
modulo the RESUME exception.

**Do NOT touch the RESUME path (`startResumeJob`,
`JobExecutor.INSTANCE.start` at `:3229`).**

### Tests (Step C — disjoint test files)

Add to `app/src/test/java/net/devemperor/dictate/core/`:

- Assert the imported-file submit produces a `JobRequest` field-faithful
  to the legacy `:2507-2523` construction: snapshot a `FreshConfig`
  via the same inputs and assert
  `ImePipelineConfigResolver.resolveFresh(sessionId, importedFile)`
  yields `kind=RECORDING`, `audioFilePath=importedFile.absolutePath`,
  `reuseSessionId=null`, and all 8 IME-runtime fields equal to the
  legacy expression results (config-parity assertion — extend / mirror
  the existing `ImePipelineConfigResolver` / `PipelineRunnerSubsystemAdapter`
  test if one exists; K-1 handwritten fakes for the resolver provider,
  no Robolectric needed — pure resolver logic).
- Assert routing: a `PipelineAction.TriggerPipeline(sessionId,
  audioFile)` dispatched into the orchestrator from `Idle` reaches
  `pipelineRunner.submit(sessionId, audioFile)` (handwritten fake
  `PipelineRunnerSubsystem` recording the call — K-1; reuse the
  existing PipelineModule/orchestrator test harness pattern). This
  proves the imported-file route is orchestrator-driven, not
  `JobExecutor.start`.
- Static guard: a test (or the block-report grep evidence) asserting
  `JobExecutor.INSTANCE.start` in `DictateInputMethodService.java`
  appears only at the RESUME site. (Grep evidence in the block-report
  is acceptable per the C7 precedent; a JUnit string-scan test is
  optional K-4 — only if a cheap deterministic form exists.)

K-4: Robolectric only if genuinely needed (it is not for the
resolver/module-level assertions — handwritten fakes suffice).

## References

- Block-report: `../reports/B2-theme-b-recording-drive.md` —
  `### Chunk C7-B3` (C7-IMPL-1), `### Chunk C3-B1` (submit seam +
  R-1 fidelity table), `### Chunk C5-B3` (snapshot mechanism +
  `captureFreshConfigSnapshot`), `### Mid-Chunk-Triage Wave B2-C7-MID-W1`.
- Plan: `../dictate-cutover-completion.md` §2 AC-1/AC-9/AC-10, §3.2.
- Spec 1: `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md` §3, §4.11.6.1, §9.6.
- ADR-0001 (single-dispatch), ADR-0002 (no Mode-3 cascade).
