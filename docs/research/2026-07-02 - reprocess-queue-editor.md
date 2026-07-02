# Reprocess Queue Editor (PromptChooserBottomSheetV2)

---
date: 2026-07-02
author: Lukas + Claude (multi-agent review session)
type: Research
status: Accepted
context: The planned drag-to-reorder queue editor for "Reprocess with edit" (Plan 10.6) was never shipped; the V1 fallback rejects free-text after entry and cannot reproduce multi-prompt queues. Finding F-110.
related-plan: n/a (seeded by 2026-07-02 - feature-wiring-code-review.md, F-110; original scope: Plan 10.6)
related-adrs: —
---

"Reprocess with edit" ships a documented stop-gap: the shared V1 prompt chooser reduced to exactly one saved prompt. The in-code comment (`HistoryDetailActivity.java:169-177`) explicitly tracks `PromptChooserBottomSheetV2` as a follow-up. This document records the gap, the two user-visible dead-ends of the fallback, and the design constraints for V2.

## 1. Vision and Motivation

### 1.1 The two user-visible dead-ends today

1. **Free-text rejected after entry:** the shared `PromptChooserBottomSheet` always renders its free-text field + send button (`PromptChooserBottomSheet.java:82-117`), but the `TAG_REPROCESS_EDIT` branch discards free-text choices — `JobRequest.queuedPromptIds` carries **entity IDs only**. The user types a prompt, submits, and only then gets a "Please pick a saved prompt" toast (`HistoryDetailActivity.java:606-619`; `strings.xml:371` exists solely for this dead-end).
2. **Multi-prompt queues cannot be edited:** a session originally processed with N queued prompts can only be re-run unchanged (direct reprocess) or with exactly one prompt (edit). No reorder, no add/remove.

### 1.2 Context that raises this topic's priority

The review found the queue plumbing this editor would sit on is itself defective: **F-001 (confirmed, high)** — the *keyboard-side* ReprocessStaging Send never submits the user's staged queue edits (`Effect.SubmitReprocess` carries `queue = emptyList()`, falling back to the live auto-apply queue); **F-003 (confirmed, high)** — the auto-apply queue is never prepared on the catalog record path. Any V2 design should land **after or together with** the F-001/F-003 correctness fixes, on a `JobRequest` model that actually transports queues end-to-end.

### 1.3 Discarded Alternatives

- **Keep the one-prompt fallback silently** — the free-text dead-end actively wastes user input (type → submit → toast); at minimum the interim fix below is warranted.

## 2. Findings + Conclusions — design constraints for V2

1. **`JobRequest` must carry prompt *content*, not only entity IDs** — free-text prompts and since-deleted saved prompts both need a `(text, optional entityId)` pair per queue slot. This is the same transport-model decision F-001's fix needs (the staged `reprocessEditableQueue` also lives outside entity IDs) — **make it once**.
2. **UI:** multi-select + drag-to-reorder + free-text row, per the original Plan 10.6 sketch (V2 bottom sheet). The V1 sheet stays for its other tags (regenerate "Other prompt", post-process).
3. **Interim fix (cheap, ship immediately):** pass a "saved prompts only" flag into `PromptChooserBottomSheet` so the free-text row is hidden for `TAG_REPROCESS_EDIT` instead of failing after submission.
4. **Lifecycle:** V2 must use the rotation-safe tag/argument-encoded context pattern (`TAG_REPROCESS_EDIT_PREFIX + sessionId`) — the review confirmed the sibling post-process flow gets this wrong (F-111; see the history-reprocess-hardening research doc).

## 3. Information Gaps

1. **Original Plan 10.6 text** — locate the archived plan section and reconcile its UI sketch with today's V1 sheet before designing V2. Owner: V2 implementer; fallback: constraints in §2 suffice for a fresh design.
2. **Whether queue *reordering* of a past session should re-run per-prompt or as one chained run** — owner: user decision; fallback: mirror pipeline semantics (sequential chain, each step persisted).
3. **F-110 is unverified** (feature-gap pass-through) — the in-code comments are explicit, so risk is low; re-read `:606-619` before building.

## 4. Change History

### 2026-07-02 — Initial scoping

- **Trigger:** Whole-app review, history-redesign seed agent (+ history sweep duplicate, merged).
- **What changed:** Document created from F-110; cross-linked to the F-001/F-003 queue-correctness findings.

### 2026-07-02 — Implemented (status → Accepted)

- **Trigger:** Implementation of §2 on branch `worktree-agent-a1081b6fb1222dc27` (`[queue-editor]` commits, on top of the merged wave-1 reprocess-hardening).
- **What changed:**
  - **§2.1 transport (made once):** `JobRequest.TranscriptionPipeline.queuedPromptIds: List<Int>` replaced by `queuedPromptSlots: List<PromptQueueSlot>` (same on `PipelineConfig`) — one slot type `(text, optional entityId)` with three shapes: ID-only (legacy keyboard semantics, current text resolved at execution, deleted → skip), content-carrying saved prompt (editor slot; survives deletion/edit of the saved prompt), free-text. Wave-1's `StepRegenerate` override pair was unified onto the same type (`promptOverride: PromptQueueSlot?`, text-bearing enforced). The pipeline resolves slots in ONE seam (`PipelineOrchestrator.resolveQueueSlot`) shared by the fresh-run and resume loops. The keyboard-side seams (`FreshConfig`, `PipelineConfigResolver`, `PipelineRunnerSubsystem.submitReprocess`) deliberately stay `List<Int>` — they are F-001/F-003 territory; conversion to slots happens at `JobRequest` construction (`PromptQueueSlot.fromIds`), so the F-001 fix can widen those seams to content slots without another transport change.
  - **§2.2 UI:** `ReprocessQueueEditorBottomSheet` (Kotlin, Material 3; named for its function rather than "PromptChooserBottomSheetV2") + plain unit-tested `ReprocessQueueEditorModel`: original queue pre-loaded from `session.queued_prompt_ids`, drag-to-reorder (`ItemTouchHelper`), per-row remove, tap-to-append saved prompts (filtered to not-yet-queued, per Plan 10.6), free-text row. Confirming an *empty* queue is valid (transcription-only re-run). V1 `PromptChooserBottomSheet` unchanged for regenerate "Other prompt" / post-process.
  - **§2.3 interim fix:** obsolete — superseded directly by V2; instead of hiding the V1 free-text row, the V1 reprocess-edit path (incl. the `dictate_history_reprocess_edit_needs_saved_prompt` toast dead-end, removed in all four locales) was deleted and regression-locked in `HistoryDetailJobRoutingInvariantTest`.
  - **§2.4 lifecycle:** session id in fragment arguments + `TAG_REPROCESS_EDIT_PREFIX + sessionId` fragment tag; edited queue in saved instance state (`ReprocessQueueEditorModel.Snapshot` primitive lists); listener re-bound in `onAttach`. No Activity fields back the flow.
  - **Gap 1 (Plan 10.6 text):** located (`docs/plans/dictate-reprocess-refactor.md` §10.6) and reconciled — UI shape (drag handle left, remove right, add-filtered-to-remaining, confirm button) adopted; its `List<Int>` callback superseded by the §2.1 slot transport; free-text support added on top (10.6 had none).
  - **Gap 2 (reorder semantics):** fallback taken — the edited queue executes exactly like the pipeline's queued prompts: one sequential chain, each step persisted (`executeQueuedPrompts` is the shared loop). Covered by `PipelineOrchestratorQueueExecutionTest` (chain order, per-step persistence, free-text via the exact `buildQueuedPrompt` construction, editor-content-wins, dead-ID-slot skip) and `PromptQueueTransportTest` (round-trip order/shape fidelity).
  - **Gap 3 (F-110 unverified):** verified during implementation — `:606-619` matched the described dead-end before removal.
- **Deviations:** none against §2. Note: a mid-run *resume* of an edited reprocess still re-derives the queue from the session row's entity IDs (free-text slots are not persisted on the session row — schema unchanged, documented at `PipelineOrchestrator.resumePipelineBlocking` / `persistNewSession`); pre-existing resume semantics, out of scope here.

### 2026-07-02 — Post-merge verification fixes (on `main`)

- **Trigger:** Adversarial cross-verification of the merged implementation (six-agent pass) found one important defect + follow-ups; fixed directly on `main` (`[queue-editor]` commits after merge `6d7f653`).
- **What changed:**
  - **Empty-vs-unset queue semantics (verification defect 1):** an *explicitly emptied* editor queue used to be indistinguishable from "no queue travelled with the request" — `resolveQueueSlotsAtStart`'s fallback then silently executed whatever lingered in the IME's **live** auto-apply queue (F-003 documents exactly that lingering state), contradicting the editor's "only the transcription will run" promise. Fixed via nullability on the transport: `queuedPromptSlots: List<PromptQueueSlot>?` on `JobRequest.TranscriptionPipeline` + `PipelineConfig` — `null` = UNSET (run-time live-queue fallback, legacy keyboard semantics), empty = EXPLICITLY NONE (zero prompts, no fallback). Chosen over an origin-gate because the intent belongs to the queue itself, not to session provenance, and the F-001 fix later just switches its seam from `PromptQueueSlot.fromIdsOrUnset` (new keyboard-seam helper: empty ids → `null`) to an explicit list. History/editor callers always pass explicit lists — this also closes the pre-existing hole where a *direct* reprocess of a session with an empty historical queue fell back to the live queue.
  - **Resolve-once (verification defect 2):** the queue is resolved once in `runTranscriptionPipelineBlocking` and passed into the PROCESS stage (`runTranscriptionPipelineBody(queuedSlotsAtStart)`), so `totalSteps`, the persisted session queue, the executed queue and the `InsertionSource` can no longer diverge when the live queue mutates mid-run.
  - **`JobRequest.PostProcess` slot unification (reprocess-hardening verifier defect 3):** the raw `(promptText, promptId)` pair replaced by `promptSlot: PromptQueueSlot` (text-bearing enforced by the same init-guard pattern as `StepRegenerate.promptOverride`) — the transport decision is now genuinely made once across all three request types. The V1 chooser's post-process branch gained the same empty-text guard as the regenerate branch.
  - **Resume-loop cancel parity (verification note 3 — evaluated, safe, fixed):** `executeStepsFrom` gained the N4 arm `executeQueuedPrompts` already had: a provider-level CANCELLED mid-step now rethrows as `CancellationException` → `resumePipelineBlocking` finalises CANCELLED and the loop stops. Upstream trace confirmed safety: the resume path's `CancellationException` handler + `JobExecutor`'s token-aware catch handle it identically to the fresh-run loop; pre-fix the cancel was swallowed as a step error, the loop marched on and the session ended COMPLETED.
  - **Tests (both red-proven on the unfixed code):** `PipelineOrchestratorQueueExecutionTest` — "explicitly empty edited queue runs zero prompts even when the live keyboard queue is non-empty" (red: 1 leaked completion) and "resume — provider-level cancel mid-chain finalises CANCELLED and stops the loop" (red: no throw, COMPLETED, 2 calls); plus green behaviour-preservation tests for the unset→live-queue fallback and `fromIdsOrUnset`, and the `PostProcess` ID-only-slot init-guard test in `PromptQueueTransportTest`.

## 5. References

- Parent catalog: [`2026-07-02 - feature-wiring-code-review.md`](<2026-07-02 - feature-wiring-code-review.md>) — F-110, F-001, F-003, F-111.
- Code: `history/HistoryDetailActivity.java:168-181,606-619`, `history/PromptChooserBottomSheet.java:82-117`, `res/values/strings.xml:371`.
- Sibling research: [`2026-07-02 - history-reprocess-hardening.md`](<2026-07-02 - history-reprocess-hardening.md>) (execution-model refactor on the same screen).
