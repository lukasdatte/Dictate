# ADR-0013: Ambiguity Modes and the In-Keyboard Review Panel

**Status:** Accepted
**Subsystem:** state, ai, ui-rendering, service
**Scope:** Project-Wide
**Date:** 2026-07-12
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0012, ADR-0011 and ADR-0009.** ADR-0012 owns the
> persisted post-processing conversation and the `{message, output}` wire
> format; ADR-0011 owns the `getFinalOutput` text contract, the
> `committed=false` pending path and the once-per-session terminal guard;
> ADR-0009 owns the serialized run-queue. This ADR builds the ambiguity *modes*
> and the review *UI* on top, leaving all three contracts intact.

## Research

- **`findPendingInsertion` requires a persisted `final_output_text`** —
  `SessionDao.findPendingInsertion` (`database/dao/SessionDao.kt:318`) selects
  `status='COMPLETED' AND final_output_text IS NOT NULL AND inserted_at IS NULL`.
  Verified that the normal turn path did NOT set that column: `finalizeCompleted`
  (`core/SessionManager.kt:102`) only writes status, `appendConversationTurn`
  wrote `output_text` on the step but not the denormalized session column, and
  `Effect.AddPendingInsertSession` (`state/modules/PipelineModule.kt:793`) adds
  an in-memory pending part only. So a review-held session would NOT re-surface
  after process death without a fix — the crash-resilience was **not emergent**.
- **The terminal dispatch guard is once-per-session** (ADR-0011:
  `PipelineTerminalDispatchGuard`). A conversation legitimately produces many
  turns for one session, so a dictated follow-up turn cannot fire the guarded
  `onPipelineCompleted` a second time — the same reason regenerate never fires
  the terminal callback (`PipelineOrchestrator.regenerateStepBlocking` uses
  `onStepCompleted` + `finalizeCompleted`, not the terminal callback).
- **Dismissal already runs through `markInserted`** — `PendingSessionsModule`'s
  `Effect.PersistDismissal` (`state/modules/PendingSessionsModule.kt:172`) routes
  a dismiss through `sessionRepo.markInserted` as the "user acknowledged" channel.
  Reusing it for the review panel keeps one acknowledge path.
- **The layout system selects a mode from the whole state and reuses an empty
  derived ConstraintSet for grid-reusing modes** — `KEYBOARD_REPROCESS_STAGING`
  is `deriveConstraintsFrom two_row_state` with catalog-driven visibility
  (`res/xml/motion_scene_keyboard.xml:476`), selected first in
  `LayoutCatalog.forKeyboard`. The review panel follows this precedent.

## Context

Post-processing is now a persisted conversation with a structured
`{message, output}` answer (ADR-0012), but that foundation deliberately left the
*modes* out: today a turn's output is always inserted regardless of whether the
model was sure. The next step is to let the user choose how ambiguity is handled
and, when review is warranted, to clarify by voice — "chat like Claude Code, but
the user answers by speaking". This must not regress the ADR-0011 pending /
headless / guard contracts nor the ADR-0009 queue drain.

## Decision

Three user-selectable **ambiguity modes** drive an in-keyboard **review panel**
that the user refines by dictation.

### 1. Modes (`AmbiguityMode` pref)

A tri-state enum pref (`preferences/AmbiguityMode.kt`, `fromPersistKey` idiom,
default `ALWAYS_INSERT`), exposed as a ListPreference:

- `ALWAYS_INSERT` — the ADR-0012 behaviour; no extra call, no review.
- `AUTO` — a turn always runs (a bare transcription too, via
  `PostProcessingInputs.forceTurn`); a verdict decides insert-vs-review.
- `ALWAYS_REVIEW` — a turn always runs and the panel is always shown (when the
  IME is visible).

`forceTurn = mode.forcesTurn && !transcriptionOnly` is threaded IME →
`FreshConfig` → `JobRequest.TranscriptionPipeline` → `PipelineConfig`.

### 2. Verdict — an explicit wire field, not a heuristic

The structured answer gains a third field `needsClarification: Boolean`
(`StructuredResponseCodec` is the sole wire authority; all three provider paths
— OpenAI json_schema, Anthropic forced tool, lenient text fallback — carry it).
The pure rule `ReviewDecision.decide(mode, needsClarification, message)` returns
`INSERT` / `REVIEW`:

- `ALWAYS_INSERT` → always `INSERT`; `ALWAYS_REVIEW` → always `REVIEW`.
- `AUTO` → `REVIEW` iff `needsClarification && message` is non-blank (a blank/null
  message can never trigger a phantom review — the "null message = no ambiguity"
  safety net; a fallback provider that omits the field yields `false`).

The verdict is **transient**: no DB column, no schema v9. It is computed at
completion time from the fresh answer only; legacy/stored turns never compute
one. `encode()` stays two-field (`{message, output}`) — a replayed prior
assistant turn never carries the verdict back to the model.

### 3. `final_output_text` invariant (crash-resilience)

`SessionManager.appendConversationTurn` now persists `final_output_text`
uniformly in its transaction (parity with `regenerateConversationTurn`). This is
what makes a review-held turn — and, as a strict-improvement side effect, any
uninserted completed turn including the ADR-0011 headless/hover pending parts —
crash-recoverable via `findPendingInsertion` after process death.

### 4. `reviewPanel` state axis + `heldForReview` terminal

Review is its own `DictateModule` axis (`ReviewPanelModule`), NOT a dressed-up
pending part — SRP, and it never entangles with the pending-parts flush
ordering. When the IME decides to review, it dispatches
`PipelineDone(heldForReview=true)`: the FSM goes Idle and the queue drains
(ADR-0009) but NEITHER `MarkSessionInserted` NOR `AddPendingInsertSession` fires
— the session stays COMPLETED with `inserted_at` NULL and the axis owns the
surface. The terminal guard is untouched (delegate delivery consumes it as
before). Insert and Discard both route through the shared `sessionRepo.markInserted`
acknowledge channel (Insert additionally commits the text into the host
imperatively — a side-channel the reducer cannot reach). On IME-view teardown
while the panel is open, `ReviewPanelModule.onCrossModuleStateChange` converts
the held text to a pending part (via an injected clock port for determinism), so
nothing is lost.

### 5. Layout + rendering

A `KEYBOARD_REVIEW_PANEL` LayoutMode (empty `review_panel_state`
`deriveConstraintsFrom two_row_state`, selected first in `forKeyboard` when
`reviewPanel.open`) hides the grid; a dedicated `review_panel_cl` container
(InfoBar precedent — opaque, elevated, covers the grid) shows the output, the
model's message (hidden when blank → output-only), a "Refining…" hint, and its
own Insert / Re-dictate / Discard buttons. `ReviewPanelRenderer`
(`backendType=null`) drives visibility/text/enable; button clicks are wired
imperatively in the IME.

### 6. Dictated refinement loop

"Re-dictate" starts a transcription-only recording (session S2, origin
`REVIEW_REFINEMENT`, `transcriptionOnly=true` so it never runs its own turn). On
S2's completion the IME does NOT insert; it enqueues a `ConversationContinuation`
job (ADR-0009 run-queue) that appends a follow-up turn to the reviewed session
S1 (`continueConversationBlocking`: `buildFollowUpUserMessage` wraps the spoken
reply as `<user-reply>` — an instruction, not transcript data — replayed after
the full prior conversation). The result surfaces via the NEW **non-terminal**
`onReviewTurnCompleted` callback (guard-free, like regenerate); the IME re-runs
the verdict → update the panel or insert+close. During refining the Insert/
Re-dictate buttons are disabled; Discard doubles as cancel (`JobExecutor.cancel`
+ `CancelRefinement`).

### 7. Non-visible IME (hover / widget / headless)

The review panel opens only when the IME view is visible
(`canShowReviewPanel()`). In hover/widget/headless the verdict is ignored and
the text takes the existing `committed=false` pending path (ADR-0011) — review
is skipped, not lost, and the model's `message` stays visible in History (read
from `assistant_message`). "Offer review on next open" is deliberately out of
scope (see Alternatives).

### Scope of this Convention

Applies to the **session post-processing turn** (auto-format + queued prompts)
and its review/refinement operations in the foreground-service pipeline.

**Exempt (unchanged):** standalone rewording / live-prompt and history
child-session post-processing (`runPostProcessingBlocking`) stay single-shot
`complete()`; history direct/edit-reprocess stays a full rerun (= a fresh
conversation); regenerate of a `CONVERSATION_TURN` already replays the
conversation (ADR-0012) and is unchanged.

## Alternatives Considered

1. **Model the review as a pending part + overlay (reuse ADR-0011 machinery
   wholesale).** Tempting — the pending path already survives teardown. Rejected:
   a pending part participates in the R4 flush-older-parts ordering, so a later
   dictation completing while the under-review text is pending would flush it;
   and each refinement cycle would re-add/dedup the part. A dedicated axis is
   cleaner (SRP) and sidesteps the flush hazard. The teardown path still
   *converts* to a pending part, reusing the machinery exactly where it fits.

2. **Derive the verdict from the `message` text (prose heuristic).** No schema
   change. Rejected: brittle and untestable ("does the message contain a
   question mark?"). An explicit boolean decouples "is it ambiguous" from "what
   to show", is exhaustively unit-testable, and the fallback-degrades-to-false
   behaviour is well-defined.

3. **Persist the verdict so review can be offered on the next keyboard open.**
   Would let hover/headless dictations be reviewed later. Rejected for now: it
   needs a persisted `needs_review` column (schema v9) and would weaken the
   byte-identical ADR-0011 pending semantics. The explanation already survives
   in `assistant_message` / History; the interactive clarification outside a
   visible keyboard is a clean future extension, not a Paket-2 requirement.

4. **A separate `onPostProcessingVerdict` callback instead of extending
   `onPipelineCompleted`.** More local (no signature change). Rejected: it is
   stateful/racy (the IME would stash the verdict and correlate it with the
   later completion). Extending the terminal callback with a nullable
   `PostProcessingReview` is the honest shape; the bridge already forwards
   terminal callbacks explicitly and headless/reconciliation pass `null`.

## Consequences

**Positive:**
- Ambiguous results become a voice dialog without leaving the keyboard; the
  model's explanation is always available (panel or History).
- The dedicated axis keeps the pipeline FSM a pure run-lifecycle and avoids the
  pending-flush entanglement; the terminal guard and ADR-0009 drain are untouched.
- The uniform `final_output_text` persistence makes every uninserted completed
  turn crash-recoverable — closing a latent ADR-0011 gap as a side effect.
- One acknowledge channel (`markInserted`) for Insert, Discard and pending
  dismissal; one wire authority (`StructuredResponseCodec`) for the verdict.

**Negative:**
- One more terminal outcome (`heldForReview`) and one more non-terminal callback
  (`onReviewTurnCompleted`): a reader tracing a completion now has three IME
  branches plus a separate refinement path to follow.
- A second IME `JobExecutor.start` carve-out (`startReviewContinuationJob`)
  beyond the RESUME one — whitelisted in the architecture-invariant test.
- Each dictated refinement creates a second session (S2, the recording carrier),
  visible in History (tagged `REVIEW_REFINEMENT`; grouping is a future concern).

**Failure Modes:**
- **`needsClarification` under strict json_schema.** The three-field
  `additionalProperties:false` schema must be confirmed against a live OpenAI /
  Anthropic call during rollout — the unit tests fake the runner and cannot catch
  an API-level rejection (same live-check caveat as ADR-0012's forced tool). A
  provider that rejects it degrades via `allowsStructuredOutputTextFallback` to
  `TEXT_FALLBACK` → `needsClarification=false` → insert (no review), which is a
  safe degradation.
- **`AUTO` bills every dictation.** `forceTurn` makes even a bare transcription
  run a completion call in AUTO/ALWAYS_REVIEW — by design (a verdict must exist),
  not measured. `ALWAYS_INSERT` (the default) keeps the zero-extra-call behaviour.
- **IME `JobExecutor.cancel` → token path is device-only.** The orchestrator side
  of continuation-cancel is unit-tested; the IME click → `JobExecutor.cancel`
  one-liner and the live panel rendering (light/dark, refinement loop) are only
  verifiable on a device (manual checklist).
- **Contiguous-turn assumption (inherited from ADR-0012).** Continuation appends
  at `maxChainIndex+1` and the reconstruction maps `turns[i] ↔ chainIndex i`;
  holds for linear chains of successful turns.

## References

- **Related ADRs:**
  - ADR-0012 — Post-Processing Conversation (conversation foundation + `{message,
    output}` wire format this extends)
  - ADR-0011 — Headless Completion Fallback (`getFinalOutput`, `committed=false`
    pending path, terminal guard — all left intact; `final_output_text` gap
    closed here)
  - ADR-0009 — Ordered Run-Queue (the `ConversationContinuation` job drains
    through the same queue)
  - ADR-0001 / ADR-0002 — State module + cross-module cascade (the `reviewPanel`
    axis and its teardown cascade follow these)
  - ADR-0004 — LayoutCatalog + MotionLayout (the `KEYBOARD_REVIEW_PANEL` mode
    follows the reprocess-staging precedent)
  - ADR-0014 — In-Keyboard History Panel (extends this: follows the panel-axis +
    LayoutMode precedent, finally tags the `REVIEW_REFINEMENT` refinement carrier
    this ADR introduced, and yields to the review panel in `forKeyboard`)
- **Plan:** `tmp/plan-paket2-review-modi.md` (Paket 2 — implementation plan)
- Implementation:
  - `preferences/AmbiguityMode.kt` — the tri-state pref
  - `ai/conversation/{ReviewDecision,PostProcessingReview}.kt` + `StructuredResponseCodec` — verdict
  - `state/modules/ReviewPanelModule.kt` + `state/DictateUiState.kt` (`ReviewPanelState`) — the axis
  - `state/modules/PipelineModule.kt` — `PipelineDone(heldForReview)` arm
  - `state/layout/LayoutCatalog.kt` (`KEYBOARD_REVIEW_PANEL`, `forKeyboard`) + `state/render/ReviewPanelRenderer.kt`
  - `core/PipelineOrchestrator.kt` — `continueConversationBlocking`; `core/SessionManager.kt` — `appendConversationTurn` final_output_text
  - `core/DictateInputMethodService.java` — insert-vs-review branch, refinement loop, button handlers
- Test suites:
  - `ai/conversation/{ReviewDecisionTest,StructuredResponseCodecTest}` (JVM)
  - `state/ReviewPanelModuleTest`, `state/PipelineModuleTest` (heldForReview),
    `state/layout/LayoutCatalogTest`, `state/layout/MotionSceneSchemaTest`
  - `ui/ReviewPanelInflationTest`, `history/PipelineStepAdapterBindTest`
  - `core/SessionManagerConversationTest` (crash-recovery invariant),
    `core/PipelineOrchestratorQueueExecutionTest` (continuation + cancel)

## Decision History

### 2026-07-12 — Initial proposal

**Trigger:** The "ambiguity modes + review panel" work package (Paket 2) — the
follow-up to the ADR-0012 conversation foundation, which deliberately shipped the
foundation only and named the modes / review panel / dictated refinement as the
next decision.

**Before:** Post-processing always inserted a turn's `output`; there was no
verdict, no in-keyboard clarification surface, and no way for the user to refine
a result by voice. The `final_output_text` column was empty for dictations, so an
uninserted completed session was not crash-recoverable as a pending part.

**After:** A tri-state `AmbiguityMode` pref drives an in-keyboard review panel
(its own state axis + `KEYBOARD_REVIEW_PANEL` LayoutMode). The verdict is an
explicit transient `needsClarification` wire field parsed by
`ReviewDecision`; review holds the output via `PipelineDone(heldForReview)`
without inserting or creating a pending part; the user refines by dictation
(transcription-only S2 recording → `ConversationContinuation` job → non-terminal
`onReviewTurnCompleted`). `appendConversationTurn` now persists `final_output_text`
uniformly, making review-held (and all uninserted) turns crash-recoverable. The
ADR-0011 pending/headless/guard and ADR-0009 queue contracts are untouched;
non-visible IME falls back to the pending path.

**Reasoning:** An explicit boolean verdict (not a prose heuristic) is testable
and decouples "is it ambiguous" from "what to show". A dedicated axis with a
`heldForReview` terminal keeps the pipeline FSM clean and avoids the
pending-flush hazard a pending-part reuse would introduce, while the teardown
path still converts to a pending part so nothing is lost. Routing the follow-up
through a non-terminal callback respects the once-per-session terminal guard.
Persisting `final_output_text` at the SessionManager (the denormalized-cache
owner) is the smallest change that makes crash-resilience real, and it closes a
latent ADR-0011 gap for free.

### 2026-07-12 — Extended by ADR-0014 (reciprocal cross-reference)

**Trigger:** ADR-0014 (In-Keyboard History Panel) built on this ADR's panel-axis +
LayoutMode precedent and closed a gap it left.

**Before:** The `## References` block did not point forward to the ADR that reuses
the panel-axis pattern, and this ADR's `REVIEW_REFINEMENT` refinement carrier
(named in Decision §6) was never actually persisted — `resolveFresh` hard-coded
`SessionOrigin.KEYBOARD`, so the S2 carriers were indistinguishable in history.

**After:** Added an ADR-0014 cross-reference (bidirectional). No decision in this
ADR changed — ADR-0014 supplies the missing `SessionOrigin.REVIEW_REFINEMENT`
value + the `FreshConfig.origin` threading that makes the tagging this ADR assumed
real, and hides the carriers from its new history panel.

**Reasoning:** The bidirectional ADR-link rule, plus honest acknowledgement that
the carrier tagging described here was completed only in ADR-0014.

### 2026-07-13 — refinementRecording sub-axis locks the whole S2 window (Gate-1 K1/K12)

**Trigger:** K1 (Critical) — during the S2 refinement recording/transcription
window the review panel's Insert/Discard buttons were fully active, so a tap
committed the about-to-be-refined output twice or prematurely. K12 — the open
panel masked the recording layout, hiding that a recording was in flight.

**Before:** The panel modelled one busy flag, `refining`, set only when the
follow-up *turn* started running — the multi-second S2 recording before it was
unguarded.

**After:** A second sub-axis `ReviewPanelState.refinementRecording` covers the S2
recording window (set by `MarkRefinementRecording`, superseded by `MarkRefining`,
cleared by `CancelRefinement`). Insert/Discard are disabled by the renderer and
belt-and-braces IME guards for the whole refinement duration; Re-dictate remains
as the stop control; a recording hint is shown. The `reviewPanel` axis described
as atomic in §6 now carries this explicit sub-state.

**Reasoning:** The lock must span the entire refinement (record → transcribe →
turn), not just turn execution. Modelling it as state (not an ad-hoc handler
guard) makes it reducer-testable and lets the renderer reflect it.

### 2026-07-13 — Verdict computed from a single send-tap snapshot; ambiguity task gated on forceTurn (Gate-1 K9/K11)

**Trigger:** K11 — the IME re-read the ambiguity mode live for
`ReviewDecision.decide` while the orchestrator derived `forceTurn` from the
send-tap snapshot, so a settings toggle mid-run could make the two disagree
(e.g. a turn ran under AUTO but a fresh read said ALWAYS_INSERT → an ambiguous
output auto-inserted). K9 — the ambiguity task + `needsClarification` field were
always sent, even under ALWAYS_INSERT which ignores the verdict.

**Before:** §2 defined `ReviewDecision.decide(mode, …)` without fixing the source
of `mode`; the ambiguity task defaulted on regardless of mode.

**After:** `AmbiguityMode` is snapshotted onto `PostProcessingReview` at send tap
and the IME prefers it over a live pref read (live read only as the resume
fallback). `includeAmbiguityTask = forceTurn`, so ALWAYS_INSERT and history
reprocess (`forceTurn = false`) omit the task and the field. A follow-up
(continuation) turn still reads the mode live — a documented residual (report
follow-up), harmless because a toggle between send and refinement is rare.

**Reasoning:** One consistent mode snapshot per run, from `forceTurn` through the
verdict, removes the divergence class; skipping the task when the verdict is
ignored saves tokens without changing behaviour.

### 2026-07-13 — Non-terminal refinement completion: upsert, cancel-race guard, unbound recovery (Gate-1 K4, Gate-2 N4/N5)

**Trigger:** K4 — a refinement finishing while the IME view was gone committed
into a null InputConnection (lost); the teardown cascade had only surfaced the
*pre*-refinement output as a pending part. N4 — a discard-while-refining could
race a continuation already past its cancellation check. N5 — a continuation
whose delegate detached (service death) dropped its callback.

**Before:** `onReviewTurnCompleted` assumed a bound, visible IME and an
in-flight refinement; the pending axis had no way to replace a session's text.

**After:**
- `PendingSessionsAction.AddOrReplaceOne` upserts the refined output over the
  pre-refinement pending part for the same session when the IME view is gone (K4).
- `onReviewTurnCompleted` ignores a result whose panel is no longer refining
  THIS session (discarded / replaced) — it stays persisted for audit/recovery but
  is never committed or re-surfaced (N4).
- Dropping the callback when the delegate is unbound is documented as
  recovery-backed, not data loss: `final_output_text` is written in-transaction so
  cold-boot `findPendingInsertion` reconstructs it (N5).

**Reasoning:** The non-terminal path has three distinct "no live surface" cases
(view-hidden, discarded, unbound); each needs an explicit, lossless answer rather
than a best-effort commit into a surface that may not exist or may have moved on.

### 2026-07-13 — Panel surface ownership: review⇄history mutex + Show preserves the outgoing review (Gate-2 G2-3/G2-4)

**Trigger:** G2-3 — `canShowReviewPanel()` did not gate on the history panel being
closed and the two renderers set visibility independently, so both grids could
render at once with the history row exposing an Insert for the held session.
G2-4 — `ReviewPanelAction.Show` unconditionally replaced panel state, so a second
ambiguous completion (e.g. an overlay recording) overwrote a held review and the
first result vanished from the live UI.

**Before:** §4/§5 described the review and history panels as grid-covering but did
not state their mutual exclusion, and `Show` had no defence for an already-held
different session.

**After:** `HistoryPanelModule.onCrossModuleStateChange` closes the history panel
when the review panel opens (mutex enforced as a state invariant, not call-site
discipline). `ReviewPanelAction.Show` surfaces the outgoing session as a pending
part (reusing the `SurfacePendingPart` teardown channel) when it holds a
*different* session; a same-session re-Show emits no effect.

**Reasoning:** Only one panel may own the keyboard surface, and a held review is a
finished-but-uninserted result that must degrade to a pending part rather than
vanish. Both are expressed in the reducers/cascades so no IME call-site ordering
can violate them. The complementary info-bar suppression lives in ADR-0006.
