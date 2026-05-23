# Validated Findings — Block 2 (Theme B — Recording-Drive Cutover + D2-pre Gate)

**Agent-ID:** B2-VAL-SANITY
**Date:** 2026-05-15
**Source audits:**
- `reports/audit-plan-and-api-B2.md` — 0 Crit / 1 Imp / 3 NTH (+ AC verdicts + out-of-scope notes)
- `reports/audit-convention-B2.md` — 0 Crit / 1 Imp / 1 NTH (+ checkpoint table all PASS bar B2-1)
- `reports/audit-logic-B2.md` — 1 Crit / 2 Imp / 3 NTH (+ deviation re-scrutiny + out-of-scope)
- `reports/audit-test-B2.md` — 0 Crit / 2 Imp (F-TEST-B2-1 + its amplifier sub-finding) / 0 NTH

Raw total: 1 Critical · 6 Important · 7 Nice-to-have.

## Summary

- 🟢 valid + auto-fixable: **6** (Critical: 0, Important: 3, Nice: 3)
- 🟡 valid + research-needed: **2** (Critical: 1, Important: 1) — clustered into **1** research topic
- ❌ eliminated / no-residual: **5** (1 confirmed-postponed-stands, 1 superseded-spec-sketch, 3 deviation re-scrutiny → validated-no-residual)

Net unique findings after dedup: **8** actionable + **5** eliminated/no-residual.
Per D3 every actionable finding incl. NTH is classified for repair in THIS
block; only genuinely intentional-deferred-with-rationale items are ❌/postponed.

## Cross-cut patterns

1. **Preparing-window lifecycle cluster (the architectural heart of B2).**
   F-1 (Critical BT-SCO already-connected hang), F-2 (Important focus-lost-
   during-Preparing not re-acquired), and F-3 (Important Send-while-Preparing
   destructive pre-dispatch) all stem from the **same** structural root: the
   BT-mic `Preparing(awaitingSco=true)` window is a new, potentially long-lived
   state that the C6-W1 repair introduced, and three independent edge behaviours
   around it are under-specified (edge-vs-level SCO resolution, focus re-acquire
   timing, IME pre-dispatch irreversibility). F-1+F-2 are **consolidated into
   ONE 🟡 research topic** (`recording-preparing-sco-focus-lifecycle`) — they
   share the same Preparing-window redesign surface and the same §15.2/§15.3
   spec constraint; fixing them separately risks two incompatible Preparing
   redesigns. F-3 is kept **separate 🟢** (its fix is a local sendable-state
   guard, decidable without the Preparing redesign — see F-3 rationale) but is
   noted as *Preparing-cluster-adjacent*: it must be repaired in a wave that
   sees the F-1/F-2 research outcome (ordering note for the orchestrator).

2. **PipelineActionRouter.kt grep-blind spot (systemic audit-evasion).** F-4
   (NUL byte) is mechanically 🟢, but it has a **routing rider**: the binary
   flag silently excluded `PipelineActionRouter.kt` from the grep-based
   plan-and-api/logic audits (only the Read-tool-tolerant convention audit and
   the test-coverage audit actually inspected its content). After the NUL strip
   the repair MUST trigger a logic/plan-conformance re-audit of that file (the
   `ACTION_SEND → StopRecordingAndSend` vs §7.5-sketch `→ StopRecording` note +
   the half-wired `ACTION_INSERT`/`ACTION_DISMISS` observation both land here).

3. **Spec-vs-code doc drift on the §15.1.x Coupling-Matrix (settled-decision
   re-litigation risk).** F-5 — the C6-W1 repair is spec-faithful to §15.1
   row 3, but the spec file's own §15.1.x matrix `Audio` row + §15.3 KDoc still
   carry the disproven S-4 premise. Doc-only 🟢; tracked already as the block-
   report's own `Dev-W1-3` (`flagged-for-validate`) — this promotes it to an
   actionable doc-reconciliation item so it is not lost at block close.

4. **R-7 test-pollution root cause refined (not what the state-file said).**
   F-6 — the precise defect is `ActiveJobRegistry` (process-wide `object`,
   hard single-job lock, **no reset method**) is never drained, AND even the
   reference `DictatePipelineServiceOverlayTransitionTest` never drains it. The
   new B2 boot-tests faithfully copied an incomplete reference discipline. The
   fix is well-specified + mechanical (add `ActiveJobRegistry.resetForTest()`
   production seam mirroring the 2 existing seams + call it in 4 B2 tearDowns +
   the reference test). 🟢 — and per D3 this IS actioned in this block (it is
   the Postponed R-7 item being closed now, not re-postponed).

---

## Findings

### F-1 (was AUDIT-LOGIC-B2-1) — consolidated with F-2 into one research topic

- **Classification:** 🟡 valid + research-needed
- **Severity:** Critical
- **Marker:** `architecture-conflict`-adjacent / **blocks-following-work**
  (Theme-C builds on a sound recording path; a hard hang on the BT opt-in path
  ships only after C7 deleted the legacy fallback — no safety net)
- **Files:** `state/modules/AudioModule.kt:228-238` (just-resolved edge guard)
  + `:99-110` (OnBluetoothScoStateChanged no-op guard); root mechanism
  `core/BluetoothScoManager.kt:121-126` (`startSco` already-connected
  early-return)
- **Description:** BT-mic recording hangs forever in
  `Preparing(awaitingSco=true)` when SCO is already connected at recording
  start. `BluetoothScoManager.startSco()` (verified `:122-126`) takes the
  `audioManager.isBluetoothScoOn` early-return: fires `onScoConnected()`
  synchronously and `return true` **without** arming the 2500 ms timeout
  (`postDelayed` at `:138` is never reached). The resulting
  `OnBluetoothScoStateChanged(Connected)` hits `AudioModule.reduce`'s
  `if (newSco != state.bluetoothSco)` guard (verified `:104`), but the phase
  is **already stale-`Connected`** from the prior session (`StopBluetoothSco`
  → `release()` does not synchronously reset `audio.bluetoothSco.phase`; the
  `Disconnected` broadcast is async and has not arrived) ⇒ reducer returns
  `null` (Rejected) ⇒ no state write ⇒ the observer's `justResolved` edge
  check `prevPhase != nextPhase` (verified `AudioModule.kt:231`) is
  `Connected==Connected` ⇒ `false` ⇒ `ScoRouteResolved` is never cascaded ⇒
  `AllocateMediaRecorder` never fires ⇒ recording is silently dead (no audio,
  no `Preparing→Active`, no §7.6 notification, no error, no timeout-recovery
  because the early-return skipped it). Realistic trigger: two back-to-back
  BT-mic dictations (very common), or another app/the system already holds SCO.
- **Verification:** Confirmed against actual code. `startSco` early-return at
  `BluetoothScoManager.kt:122-126` provably skips the `postDelayed` timeout
  (lines 128-138 are only reached on the not-connected branch). The edge-
  trigger `prevPhase != nextPhase` at `AudioModule.kt:231` is genuinely
  defeated by a stale-`Connected` prior phase. This is a real level-vs-edge
  mismatch the C6-W1 repair left.
- **Why research (not auto-fix):** The three candidate fixes the auditor lists
  (a: distinct settle signal regardless of prior phase; b: level-trigger the
  deferred allocate on `next.recording is Preparing && awaitingSco` +
  `phase ∈ {Connected,Failed}` with an `awaitingSco`-keyed re-fire guard; c:
  synchronously reset `audio.bluetoothSco.phase` on
  `StartBluetoothSco`/`RecordingStarted`) are a **state-machine design
  choice** that must be (1) spec-faithful to Spec 1 §15.2/§15.3 and the
  ADR-0002 Mode-1/2-only cascade discipline, and (2) must NOT reintroduce the
  stale-resolve-after-cancel bug the current *edge* trigger correctly defeats
  (a duplicate late SCO broadcast must not re-cascade `ScoRouteResolved` after
  a cancel). Option (b) is the auditor's recommendation (also closes the
  "SCO connects exactly at the 2500 ms timeout" race) but the re-fire-guard /
  cancel-safety interaction needs verification before implementation. This is
  not locally decidable from the plan + surrounding code alone (D5: when
  unclear, research more).
- **Research topic:** `recording-preparing-sco-focus-lifecycle` (shared with
  F-2 — see cross-cut pattern 1)
- **Routing:** repair-sub-phase 🟡-branch (research-agent → resume-chain
  implementer). **Blocks-following marker set** — orchestrator should treat
  this as the gating item for block close (it is the architectural core of B2).

### F-2 (was AUDIT-LOGIC-B2-2) — consolidated with F-1

- **Classification:** 🟡 valid + research-needed
- **Severity:** Important
- **Files:** `state/modules/AudioModule.kt:165-170` (focus-loss→pause guard,
  verified — guarded on `next.recording.isActiveOrPaused`, `Preparing`
  excluded) + `:195-211` (engagement-edge `recordingStarted`); interaction
  with `state/modules/RecordingModule.kt:237-294`
- **Description:** Audio-focus is dropped for a BT-mic recording when focus is
  lost during the SCO wait. `Preparing` is deliberately NOT `isActiveOrPaused`
  (`DictateUiState.kt:204-205`), so a focus-loss during the
  `Preparing(awaitingSco)` handshake does not pause and does not cascade
  `RecordingEnded`; `RequestAudioFocus` was emitted once on the
  `Idle→Preparing` engagement edge and is never re-requested; the later
  `Preparing→Active` is engaged→engaged so `recordingStarted` does not re-fire
  (verified: `recordingStarted` predicate at `AudioModule.kt:197-199` requires
  `!prevRec.isEngaged()` or `Paused→Active`). Net: recording goes Active having
  lost focus mid-Preparing, other apps duck/play over it. Legacy did not have
  this window (requested focus right before `MediaRecorder.start()`, after the
  SCO wait).
- **Verification:** Confirmed. The `recordingStarted` engagement-edge and the
  focus-loss `isActiveOrPaused` guard both exclude the Preparing window exactly
  as described.
- **Why research + why consolidated with F-1:** The fix (re-request focus on
  the `Preparing(awaitingSco)→Active` edge, OR move the BT-path
  `RequestAudioFocus` to fire alongside the deferred `AllocateMediaRecorder`
  matching legacy timing) is the **same Preparing-window redesign surface** as
  F-1's deferred-allocate trigger redesign, and is constrained by the same
  §15.3 SRP rule (focus stays AudioModule-owned). Fixing F-1 and F-2 with two
  independent Preparing redesigns risks incompatible state-machine shapes. One
  research topic, one coherent redesign.
- **Research topic:** `recording-preparing-sco-focus-lifecycle` (shared — see
  F-1 + cross-cut pattern 1)
- **Routing:** repair-sub-phase 🟡-branch, **same research-agent / same
  resume-chain as F-1** (one topic, one fix-wave covering both).

### F-3 (was AUDIT-LOGIC-B2-3)

- **Classification:** 🟢 valid + auto-fixable *(Preparing-cluster-adjacent —
  see ordering note)*
- **Severity:** Important
- **Files:** `core/DictateInputMethodService.java:2301-2336` (`stopRecording()`
  — verified: guards only `sessionId==null || pipelineBinder==null ||
  imePipelineConfigResolver==null`, NO FSM-state check before the destructive
  `captureFreshConfigSnapshot` `:2325` + `primePipelineUiForNewPath` `:2331` +
  `newPathRecordingSessionId=null` `:2333`); reducer side
  `RecordingModule.kt:415-431` (Active+StopRecordingAndSend) / `:479-492`
  (Paused arm — no `Preparing` arm)
- **Description:** `StopRecordingAndSend` from a non-bearing recording state
  (e.g. still `Preparing` — BT-SCO wait unresolved, or slow
  `MediaRecorder.prepare()`) silently drops the user's recording with no
  feedback. The reducer correctly rejects (no `Preparing` arm → Rejected), but
  the IME has already irreversibly: consumed/reset the one-shot flags inside
  `captureFreshConfigSnapshot` (`livePrompt=false`, `autoSwitchKeyboard=false`,
  `pendingLivePromptChain` armed), shown "Sending…" UI via
  `primePipelineUiForNewPath()`, cleared `newPathRecordingSessionId=null`, and
  left a `freshSnapshots` entry cleared only on next process boot. Result:
  orphaned recording + permanently-stuck "Sending…" keyboard + destructively-
  consumed live-prompt/auto-switch state. F-7 `require(isNotBlank)` does not
  help (the id is non-blank; the problem is the recording *state*).
- **Verification:** Confirmed against `DictateInputMethodService.java:2301-2336`.
  `isEffectiveRecordingActiveOrPaused()` already exists (`:2172`) but is **not**
  called in `stopRecording()`. The destructive trio runs before any FSM-state
  check.
- **Why 🟢 (not 🟡) — judged independently:** The minimal correct fix is a
  **local guard**: call the already-existing `isEffectiveRecordingActiveOrPaused()`
  (or capture the dispatch `DispatchOutcome` and roll back) *before* the
  destructive snapshot/prime. This is decidable from the surrounding code alone
  — the helper exists, the guard placement is unambiguous (immediately after
  the existing null-guard, before `captureFreshConfigSnapshot`), and it does
  not require the Preparing-window redesign of F-1/F-2 (it is a defensive
  early-bail, not a state-machine reshape). The auditor's alternative
  ("add a `Preparing+StopRecordingAndSend` reducer arm that defers the send")
  IS research-scope, but it is the *larger* alternative, not the minimal fix —
  per D3/D22 the small locally-decidable guard is the correct repair here.
- **Suggested fix:** In `stopRecording()`, after the existing `sessionId==null
  || pipelineBinder==null || imePipelineConfigResolver==null` bail and before
  `captureFreshConfigSnapshot(sessionId)`, add a guard: if NOT
  `isEffectiveRecordingActiveOrPaused()` → log + return (mirrors the existing
  defensive-bail pattern; nothing destructive has run yet at that point).
- **Domain bundle candidate:** `DictateInputMethodService.stopRecording` —
  none other in this file.
- **Ordering note for orchestrator:** F-1/F-2 widen the Preparing window
  (BT-SCO can stay Preparing for up to 2500 ms), which makes the
  Send-while-Preparing race materially more likely. F-3's guard is independent
  and correct regardless of the F-1/F-2 outcome, but the repair wave should
  apply F-3 in a wave that has *seen* the F-1/F-2 research conclusion (so the
  guard's interaction with any new Preparing sub-state is consistent). Not a
  dependency — a sequencing preference.

### F-4 (was AUDIT-CONVENTION-B2-1)

- **Classification:** 🟢 valid + auto-fixable *(with mandatory re-audit rider)*
- **Severity:** Important
- **File:** `core/PipelineActionRouter.kt` (whole file — embedded NUL byte)
- **Description:** `PipelineActionRouter.kt` contains a NUL byte. Verified:
  `grep -aPc "\x00"` → `1`; `file(1)` → `data` (not text); `git diff
  17085ca..HEAD --numstat` → `-	-` (binary). The source content itself is
  valid Kotlin (sibling-adapter-convention-clean per the convention audit).
  The defect is purely file-hygiene — a binary-flagged source file silently
  defeats `git diff`-based review and every diff-scoped automated gate
  (block-validate diff-scope, AUDIT-TEST cross-chunk-regression, PR review).
- **Verification:** Confirmed by direct `grep -aPc`, `file`, and
  `git diff --numstat` — all three reproduce the audit's observations exactly.
- **Suggested fix:** Strip the NUL byte(s), rewrite as clean UTF-8 with
  byte-identical content (`tr -d '\000'` into a temp file then replace, or
  re-save via editor). Post-fix verify: `file` → text, `grep -aPc "\x00"` → 0,
  `git diff` renders a normal text diff.
- **Routing rider (MANDATORY — cross-cut pattern 2):** Because the binary flag
  excluded this file from the grep-based plan-and-api + logic audits, after the
  NUL strip the repair MUST re-audit `PipelineActionRouter.kt` for
  logic/plan-conformance. Two specific items to reconcile in that re-audit:
  (a) `PipelineActionRouter.dispatch` maps `ACTION_SEND → StopRecordingAndSend`
  while the §7.5 spec sketch (`1-pipeline-service.reviewed.md:3999`) maps
  `ACTION_SEND → StopRecording` — implementation is the *correct* FN-4 update
  (spec sketch predates FN-4), record as **intentional supersession**, not a
  bug; (b) `ACTION_INSERT`/`ACTION_DISMISS` decode to
  `ConfirmInsertion`/`DismissResult` but no `NotificationStatus` arm currently
  emits Insert/Discard buttons — confirm this is intentional half-wiring for a
  later result-stage block (not a B2 defect). Both are expected to resolve as
  no-residual once the file is grep-visible, but the re-audit must be
  *explicit* (the file was effectively unreviewed by 2 of 4 audit topics).
- **Domain bundle candidate:** `core/PipelineActionRouter.kt` (single file).

### F-5 (was AUDIT-PLAN-AND-API-B2-1; = block-report self-flagged Dev-W1-3)

- **Classification:** 🟢 valid + auto-fixable (doc-only)
- **Severity:** Important
- **Files:** `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md`
  §15.1.x Coupling-Matrix `Audio` owner row (~line 6252) + §15.3 AudioModule
  example KDoc (~lines 6811-6817)
- **Description:** The B2-C6-W1 repair correctly implemented the AudioModule
  observer arm against §15.1 row 3 (verified spec-faithful by the plan-and-api
  audit's C6-W1 verdict), but the spec file's own §15.1.x matrix `Audio` row
  was never updated (still reads only `Recording = R(state.audio.audioFocusGranted)
  C(RecordingAction.PauseRecording)`; the shipped
  `AudioModule.onCrossModuleStateChange` now also reads `state.recording` +
  `state.audio.bluetoothSco` and cascades `RecordingStarted`/`RecordingEnded`/
  `ScoRouteResolved` — §15.1.x explicitly states a new read-hook without a
  matrix entry is a code-review violation), and the §15.3 example KDoc still
  asserts the disproven S-4 premise. Left unreconciled, the next plan/review
  cycle re-litigates a settled, correct decision.
- **Verification:** Cross-referenced against the block-report's own `Dev-W1-3`
  entry (`reports/B2-theme-b-recording-drive.md:1127`, status
  `flagged-for-validate`, "matrix update is a follow-up doc task"). The
  shipped `AudioModule.kt` reads `state.recording` + `state.audio.bluetoothSco`
  and cascades all three actions — confirmed against `AudioModule.kt:177-238`.
- **Suggested fix:** (a) §15.1.x matrix `Audio` owner `× Recording` cell: add
  `R(state.recording) R(state.audio.bluetoothSco) C(AudioAction.RecordingStarted)
  C(AudioAction.RecordingEnded) C(RecordingAction.ScoRouteResolved)`. (b)
  Replace the §15.3 lines ~6811-6817 S-4 "Dead-Code / kein Cross-Module-Cascade
  nötig" paragraph with the restored-arm reality, cross-ref §15.1 row 3 +
  `research/recording-audiofocus-btsco-handshake.md`. No code change.
- **Promotes:** block-report `Dev-W1-3` from `flagged-for-validate` →
  actionable doc-reconciliation (must not be lost at block close).
- **Domain bundle candidate:** spec file `1-pipeline-service.reviewed.md`
  (single doc, two edits — fold both into one doc-fix in the same wave).

### F-6 (was AUDIT-TEST F-TEST-B2-1 + F-TEST-B2-2, merged)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:** production seam to add: `core/ActiveJobRegistry.kt` (verified:
  `object` at `:20`, hard single-job lock `if (_state.value.isNotEmpty())
  return false` at `:38`, **no** `resetForTest`); tearDown call-sites:
  `PipelineRunnerSubsystemAdapterTest.kt:77-87`,
  `DictatePipelineServiceRecordingDriveTest.kt:54-66`,
  `ImeRecordingDriveCutoverTest.kt:76-86`,
  `DictateCutoverE2ETest.kt:105-115`, + the reference
  `DictatePipelineServiceOverlayTransitionTest` tearDown
- **Description:** R-7 order-dependent test-pollution. Precise root cause
  (refines the state-file's inaccurate "new tests lack tearDown discipline"):
  `JobExecutor.resetForTest()` clears only orchestrator/token/thread —
  **not** `ActiveJobRegistry`; `ActiveJobRegistry` is a process-wide `object`
  with a hard single-job lock and **no reset method**; the new B2 boot-tests
  faithfully copied the reference `DictatePipelineServiceOverlayTransitionTest`
  discipline, but **that reference test itself never drains
  `ActiveJobRegistry`** (inherited-incomplete-discipline). When a job's async
  `unregister` (on the single-thread executor `finally`) has not completed
  before tearDown, the next test in the same Robolectric fork hits
  `register()` → `_state` non-empty → `return false` → job silently never
  starts → assertion fails. Latent at block-end HEAD (2 uncached full runs
  green: 1041/1041 both variants) but the structural gap is real.
- **Verification:** Confirmed against `ActiveJobRegistry.kt` (`object` :20,
  single-job lock :38, no `resetForTest`) and the existing-seam pattern: only
  `DictateDatabase.kt` + `JobExecutor.kt` carry `fun resetForTest` — the
  proposed `ActiveJobRegistry.resetForTest()` mirrors them exactly (K-1:
  production-owned reset seam is the established convention; no Mockito).
- **Suggested fix (preferred, well-specified, mechanical):** Add to
  `ActiveJobRegistry`:
  ```kotlin
  /** Testing seam — clears the process-wide registry between tests. */
  @JvmStatic
  internal fun resetForTest() { _state.value = emptyMap() }
  ```
  Then call `ActiveJobRegistry.resetForTest()` in each of the four B2 boot-test
  classes' `@After` (after `JobExecutor.resetForTest()`) **and** in the
  reference `DictatePipelineServiceOverlayTransitionTest` tearDown (closes the
  inherited-defect root cause). Mirrors the 2 existing `*.resetForTest()`
  seams.
- **Why 🟢 + actioned this block (D3):** The fix is well-specified and
  mechanical (the auditor gave the exact code + exact call-sites). Per the
  prompt + state-file Postponed-Issues + D3 this IS the Postponed R-7 item
  being actioned now — do **not** re-postpone. F-TEST-B2-2 (the
  zero-`waitForRegistryEmpty` amplifier classes) is the same defect's
  concrete amplifier path and is **merged here** (same fix closes it).
- **Domain bundle candidate:** test-infra hygiene — bundle the 5 tearDown
  edits + the 1 production seam into one repair-wave (single concern).

### F-7 (was AUDIT-LOGIC-B2-4)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `state/modules/RecordingModule.kt:358-362`
- **Description:** Defensive `?:` fallback `state.target ?:
  InsertionTarget.INPUT_CONNECTION` on the deferred-allocate arm. The KDoc
  asserts `target` is non-null on the `awaitingSco` path so the `?:` cannot
  fire under the FSM contract, but `Preparing.target` is typed
  `InsertionTarget? = null` — the invariant is convention-only. The file's own
  F-7 precedent (fail-fast on the analogous `sessionId` invariant) argues for
  `requireNotNull` to make the invariant load-bearing.
- **Why 🟢 + actioned (D3):** Small, locally decidable, consistent with the
  file's established F-7 fail-fast philosophy. Per D3 all polish points incl.
  NTH are fixed in this block unless genuinely intentional-deferred-with-
  rationale — this is not deferred, it is a latent-correctness-landmine the
  auditor recommends closing.
- **Suggested fix:** Replace the `?:` with
  `requireNotNull(state.target) { "ScoRouteResolved on awaitingSco Preparing
  requires non-null target" }`.
- **Domain bundle candidate:** `RecordingModule.kt` — bundle with no other
  (logic-class, distinct from F-3's IME-side fix).

### F-8 (was AUDIT-PLAN-AND-API-B2-3)

- **Classification:** 🟢 valid + auto-fixable (KDoc-only)
- **Severity:** Nice-to-have
- **File:** `state/Action.kt:189` (`PipelineAction.TriggerPipeline` KDoc) vs
  imported-file caller `DictateInputMethodService.java:2556-2558`
- **Description:** `TriggerPipeline`'s KDoc still reads "Initiate a pipeline
  run for the **just-recorded** audio." It is now also the imported-file
  (no-recording-FSM) entry-point via MID-W1. The action is genuinely
  source-agnostic `(sessionId, audioFile)` but the KDoc does not document the
  second valid caller — a reader tracing the imported-file path won't find it
  referenced from the action that carries it.
- **Why 🟢 + actioned (D3):** Documentation-completeness, one-sentence KDoc
  append, no behaviour. Per D3 small doc polish is fixed in-block.
- **Suggested fix:** Append to `TriggerPipeline` KDoc: source-agnostic
  pipeline entry; emitted by `RecordingModule.Effect.EmitPipelineTrigger`
  (post-record) **and** dispatched directly by the imported-audio-file path
  (no recording FSM) — cross-ref `transcribeImportedAudioFileViaOrchestrator`
  + `research/imported-audiofile-orchestrator-route.md`.
- **Domain bundle candidate:** `state/Action.kt` (single KDoc).

### F-9 (was AUDIT-PLAN-AND-API-B2-2)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `core/DictateInputMethodService.java:3371-3374`
  (`handleReprocessSend` new-path not-bound branch)
- **Description:** When the new-path is selected but the service is not bound,
  `handleReprocessSend` calls `showJobBusyToast()` ("a job is already active")
  when the actual condition is "service not yet bound".
  `transcribeImportedAudioFileViaOrchestrator` handles the identical not-bound
  precondition correctly (`R.string.dictate_service_not_ready`). Cross-chunk
  inconsistency (C5 reprocess vs MID-W1 import) + semantically-wrong message.
  Not an AC/behaviour regression (both bail without crash/double-dispatch).
- **Why 🟢 + actioned (D3):** One-line message swap, locally decidable, the
  correct string already exists and is already used by the sibling path. Per
  D3 small UX-correctness polish is fixed in-block.
- **Suggested fix:** In the `pipelineBinder == null` arm of
  `handleReprocessSend`, use `R.string.dictate_service_not_ready` (matching the
  import path); keep `showJobBusyToast()` only for the genuine
  `ActiveJobRegistry.isAnyActive()` branch.
- **Domain bundle candidate:** `DictateInputMethodService.java` — repair-wave
  may co-locate with F-3 (same file) but they are independent concerns; bundle
  only for git-index efficiency, not logically.

---

## Eliminated / no-residual findings

| Source ID | Source audit | Verdict | Rationale |
|-----------|--------------|---------|-----------|
| AUDIT-PLAN-AND-API-B2-4 | plan-and-api | ❌ confirmed-postponement-stands | C4-IMPL-2 / Dev-3: `NotificationStatus.Pipeline` static subtitle vs §7.6 step-counter. Genuinely intentional-deferred-with-rationale: the F-13 counter already renders in the record-button label; threading it into the notification needs a cross-cutting `NotificationStatus.Pipeline` payload change across every PipelineModule emit-site (large, Theme-C/follow-up scope). Already tracked `postponed` in the Issue Index (C4-IMPL-2). The auditor itself recommends "None for B2 — confirm the existing postponement stands". Per D3 this qualifies as genuine intentional-deferred → stays postponed, NOT re-opened. |
| AUDIT-CONVENTION-B2-2 | convention | ❌ cosmetic, fold-if-touched | `PipelineServiceStubSubsystems.kt:14` header wording "retains no production-route stubs" reads slightly contradictory next to the (correct) `@Deprecated` test-only stubs. The deprecation mechanism + per-val KDoc are convention-correct (verified by the convention audit's own checkpoint table). Pure prose polish, no structural/behavioural impact, auditor explicitly offers "or accept as-is". Classified ❌-no-dedicated-repair: not worth a repair-wave; if the F-? doc wave touches this file's header anyway it may be folded in, but it does not gate. (Borderline 🟢-NTH; the dominant signal is the auditor's own "non-blocking / accept as-is" + no reader is *misled*, unlike F-5 — so it does not meet the D3 "polish point worth a fix" bar on its own.) |
| AUDIT-LOGIC-B2-5 | logic | ❌ no-bug, documented-asymmetry | Audio-focus request gated on `audioFocusEnabledPref` but `ReleaseAudioFocus` emitted unconditionally. Verified against `AudioModule.kt:127-147` — `RealAudioFocusGate.abandon()` on a never-made request is a harmless Android no-op; double-release is idempotent. The KDoc at `AudioModule.kt:138-140` already documents the idempotence rationale. Auditor verdict: "symmetric in effect, asymmetric in code; **None required**; unconditional release is the *safer* choice if the pref flips mid-session". Gating `ReleaseAudioFocus` would be a behaviour *regression* risk (mid-session pref flip). ❌ false-positive-as-actionable (the asymmetry is the correct design + already documented). |
| AUDIT-LOGIC-B2-6 | logic | ❌ no-residual-for-B2 (PipelineModule-other-block) | `StopRecordingAndSend` deliberately emits no `DismissNotification` (seamless hand-off); a stuck recording notification only if the pipeline-start *fails after a successful recording* (resolver throw / PipelineModule reject). The RecordingModule side (no dismiss) is **correct given the pipeline always picks up** (verified: the seamless hand-off is the intended §7.6 design, asserted by the RE-GATE E2E). The failure-arm dismiss is a **PipelineModule concern outside B2's primary diff** (the auditor itself routes it as "partly a PipelineModule (Block-other) concern — note for the consolidator to route"). Not a B2 recording-drive defect; route as a **forward-note to the block owning PipelineModule failure-arm** (Theme-C / integration phase), not a B2 repair. Recorded here as no-residual-for-B2 with a forward-route note. |
| Deviation re-scrutiny: C5 record-button-flip, C6-W1 audio-focus/BT-SCO repair, C7 RESUME carve-out, C7-MID-W1 imported-file | plan-and-api + logic + convention | ✅ validated-no-residual-finding | All four AC verdicts **AC-1 / AC-2 / AC-3 / AC-10 GREEN** (plan-and-api, independently corroborated). C5 flip = spec-faithful (Spec 1 §15.2 + AC-2; flipping at the legacy callback would double-allocate). C6-W1 = §15.1-row-3-faithful (the §15.3 S-4 removal was on a provably-false premise; restored arm is correct — the ONLY residual is the **doc** drift = F-5, not a code finding). C7 RESUME carve-out = byte-identical collapse, single-dispatch, orthogonal (verified `:3222` is the sole surviving IME `JobExecutor.start`). MID-W1 imported-file = reuses the documented `TriggerPipeline` entry, busy-check correctly ordered *before* the destructive `captureFreshConfigSnapshot` (verified — `isAnyActive()` precedes the snapshot), 8-field R-1 fidelity reused 1:1. No unjustified drift in any of the four. (The only deviation-adjacent residuals are F-3 — the *separate* Send-while-Preparing guard gap the flip exposed — and F-5 — the doc reconciliation; both carried as their own findings above.) |

---

## Routing summary for the orchestrator

| Wave | Findings | Branch | Notes |
|------|----------|--------|-------|
| 🟡 research wave | F-1 (Crit, blocks-following) + F-2 (Imp) | research-agent `recording-preparing-sco-focus-lifecycle` → resume-chain implementer | ONE topic, ONE coherent Preparing-window redesign. Gating item for block close. |
| 🟢 repair wave (logic/IME) | F-3 (Imp), F-7 (NTH), F-9 (NTH) | VAL-REPAIR resume-chain | F-3 ordering: apply in a wave that has seen the F-1/F-2 research outcome (sequencing preference, not a hard dep). |
| 🟢 repair wave (file-hygiene + re-audit) | F-4 (Imp) | VAL-REPAIR resume-chain | MANDATORY post-strip logic/plan re-audit of `PipelineActionRouter.kt`. |
| 🟢 repair wave (doc) | F-5 (Imp), F-8 (NTH) | VAL-REPAIR resume-chain | F-5 = spec file 2 edits (promotes Dev-W1-3); F-8 = Action.kt KDoc. |
| 🟢 repair wave (test-infra) | F-6 (Imp, merges F-TEST-B2-1+2) | VAL-REPAIR resume-chain | Closes the Postponed R-7 item (D3 — not re-postponed). |
| forward-note | AUDIT-LOGIC-B2-6 | route to PipelineModule-owning block | Failed-hand-off notification-dismiss is PipelineModule failure-arm scope, out of B2 primary diff. |

The 🟢 findings (F-3, F-4, F-5, F-6, F-7, F-8, F-9) are auto-fixable by the
VAL-REPAIR resume-chain. The 🟡 cluster (F-1+F-2) needs the
`recording-preparing-sco-focus-lifecycle` research-agent first. F-1 carries the
**blocks-following** marker — the orchestrator should treat the 🟡 wave as the
gating dependency for B2 block close (Theme-C builds on a sound recording path).

## References

- Block-report anchor: `reports/B2-theme-b-recording-drive.md#block-validate-phase-32`
- Plan: `docs/plans/2026-05-15 - dictate-cutover-completion/dictate-cutover-completion.md`
- Source audits: `reports/audit-plan-and-api-B2.md`, `reports/audit-convention-B2.md`, `reports/audit-logic-B2.md`, `reports/audit-test-B2.md`
