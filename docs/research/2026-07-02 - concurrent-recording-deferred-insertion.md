---
date: 2026-07-02
author: Lukas + Claude (planning session)
status: Accepted
context: Concurrent secondary recording during pipeline processing + deferred ordered insertion of finished results (R1–R5), built on the existing state-module / InsertionService / pending-sessions machinery.
related-plan: n/a (plan-free spec; implemented directly from this file)
related-adrs: ADR-0009, ADR-0001, ADR-0002, ADR-0003, ADR-0006, ADR-0008
---

# Concurrent Secondary Recording + Deferred Ordered Insertion

While an AI pipeline run is processing, the user can start a **new**
recording. Finished results insert immediately when a host field is
available; otherwise they become **ordered pending parts** that are
inserted at the next opportunity — in recording order, as separate
sequential inserts. Cancellations surface through the state-derived
info bar.

## Glossary

- **Run** — one pipeline execution for one session (`sessionId`):
  transcription + optional rewording steps.
- **Active run** — the run represented by `PipelineUiState.Preparing`
  / `Running` (exactly one at a time; execution stays serialized).
- **Queued run** — a session whose `TriggerPipeline` arrived while
  another run was active. Carried in the new
  `Preparing.queued` / `Running.queued` list; started automatically
  when the active run reaches a terminal transition ("chain-start").
- **Secondary recording** — a recording started while a pipeline run
  is processing (`Preparing`/`Running`). Only possible while
  `recording is Idle` (single `MediaRecorder`, user decision).
- **Pending part** — a COMPLETED session whose result could not be
  committed to a host field (`InsertionResult.DeferredToPending`) and
  now waits in `state.pendingSessions` for insertion.
- **Flush** — inserting all pending parts in recording order as
  separate sequential inserts.
- **Recording order** — ascending session `created_at` (session rows
  are created at `StartRecording`; a new recording can only start
  after the previous one was stopped, so `created_at` order ==
  stop order == trigger order == serialized completion order).

> **Run ≠ Pending part.** A *run* is in-flight processing (pipeline
> axis); a *pending part* is a finished result waiting for insertion
> (pendingSessions axis). A session moves run → pending part only via
> `PipelineDone(committed = false)`.

## 1. Vision and Motivation

### 1.1 Why this feature exists

Today the record button is repurposed as an auto-enter toggle during a
run (`resolveRecordActionPipeline`), so the user must wait for a run
to finish before dictating the next thought. Thoughts don't wait.
The feature lets the user pipeline their dictation: speak part B while
part A is still processing, and trust the system to insert the results
in the order they were spoken — or hold them safely when no input
field is available.

### 1.2 What problem this solves

1. **Dead time** — no way to record during processing (guard is the
   layout-mode selection: SEND_MODE consumes the record button,
   `ActionResolvers.kt:214-234`).
2. **Silent loss on double-trigger** — `TriggerPipeline` while the FSM
   is not `Idle` is silently dropped (`PipelineModule.kt:168-170`).
3. **Unordered, one-at-a-time pending inserts** — multiple deferred
   results surface one InfoBar item at a time, ordered by pipeline
   *completion* timestamp, not recording order
   (`InfoBarSelector.kt:294`; renderer shows `items.first()` only).
4. **Invisible cancellations** — `"cancelled"` is deliberately mapped
   to `null` in `PipelineErrorKind.fromInfoKey` (F-076), so a
   cancelled run vanishes without any user-facing confirmation.
5. **Audit fragility** — the pipeline insert audit resolves its
   session via `SessionTracker.getCurrentSessionId()`; late writes hit
   a cleared slot (the 9637fc3 bug family). Overlapping sessions make
   "current" ambiguous by construction.

### 1.3 Discarded Alternatives

- **True parallel pipeline execution** (multi-session `PipelineUiState`
  map + per-job `JobExecutor` tokens + per-run orchestrator contexts).
  Rejected: `PipelineOrchestrator` holds process-wide `@Volatile`
  per-run state (`PipelineOrchestrator.kt:204-212`), `JobExecutor` has
  a single token whose `cancel()` ignores its sessionId argument
  (`JobExecutor.kt:212-215`), and the FGS notification is single-slot.
  Parallel completion would also *break* R4's recording-order
  guarantee and require a reorder buffer at insertion time. The full
  reasoning lives in ADR-0009 §Alternatives.
- **Queueing inside `ActiveJobRegistry` / `JobExecutor`** (relax the
  registry lock, let the single-thread executor FIFO the jobs).
  Rejected: the queue would be invisible to `DictateUiState` — the UI
  could not render "1 waiting", cancel routing would target the wrong
  job (single token overwritten at submit), and state-derived
  rendering (ADR-0004/0006) forbids UI state living outside the store.
- **Auto-insert pending parts on keyboard-open** (no user consent).
  Rejected: contradicts the surprise-free-resume philosophy
  (ADR-0003 §Alternatives 2); text appearing in a freshly focused
  field without a tap is a data-corruption-grade surprise.

### 1.4 What this buys us

1. Recording order == completion order == insertion order, structurally
   (no reorder logic anywhere).
2. Every single-slot assumption in `JobExecutor`,
   `PipelineOrchestrator`, `SessionTracker`, and
   `PipelineNotificationCoordinator` **stays valid** — only one run
   executes at a time.
3. The queue is ordinary FSM payload: pure-reducer testable, rendered
   declaratively, recovered/dropped coherently with the run it
   belongs to.
4. Future true parallelism is an execution-policy swap (runner layer),
   not a state-model rewrite: the state already models N sessions in
   flight.

## 1a. Architecture Walkthrough

```
┌─────────────────────────────────────────────────────────────────────┐
│  LAYER 1 — UI slots (LayoutCatalog / ActionResolvers)               │
│  New:  LogicalButtonId.RECORD_SECONDARY in SEND_MODE rows           │
│  New:  forKeyboard precedence — recording-live wins over SEND_MODE  │
└─────────────────────────────────────────────────────────────────────┘
                 ↓ dispatches Action.RecordingAction.StartRecording
┌─────────────────────────────────────────────────────────────────────┐
│  LAYER 2 — RecordingModule (unchanged FSM)                          │
│  Idle → Preparing → Active → StopRecordingAndSend                   │
│  → Effect.EmitPipelineTrigger(sessionId, audioFile)                 │
└─────────────────────────────────────────────────────────────────────┘
                 ↓ emitAction(TriggerPipeline)
┌─────────────────────────────────────────────────────────────────────┐
│  LAYER 3 — PipelineModule (widened FSM)                             │
│  Idle: start run (as today)                                         │
│  Preparing/Running: append QueuedRun to state.queued  ← NEW         │
│  Terminal arms: queue empty → Idle; else chain-start next ← NEW     │
└─────────────────────────────────────────────────────────────────────┘
                 ↓ Effect.SubmitPipeline → pipelineRunner.submit
┌─────────────────────────────────────────────────────────────────────┐
│  LAYER 4 — PipelineRunnerSubsystemAdapter (submit-when-free gate)   │
│  Awaits ActiveJobRegistry empty (teardown race absorber) ← NEW      │
│  → JobExecutor (single-thread FIFO, unchanged)                      │
└─────────────────────────────────────────────────────────────────────┘
                 ↓ completion callback (IME): onPipelineCompleted
┌─────────────────────────────────────────────────────────────────────┐
│  LAYER 5 — Insertion (IME-side)                                     │
│  canCommitToHost:  flush older pending parts, then insert fresh     │
│  result (explicit sessionIdOverride)                       ← NEW    │
│  !canCommitToHost: PipelineDone(committed=false) →                  │
│  AddPendingInsertSession (createdAt = session created_at)  ← NEW    │
└─────────────────────────────────────────────────────────────────────┘
                 ↓ state.pendingSessions / state.infoHints
┌─────────────────────────────────────────────────────────────────────┐
│  LAYER 6 — InfoBar (state-derived, ADR-0006)                        │
│  Aggregate pending-parts item (confirm = flush, dismiss = all) ←NEW │
│  Cancellation notice item (dismiss-only)                      ←NEW  │
└─────────────────────────────────────────────────────────────────────┘
```

Read before implementing:

- [ ] `docs/architecture/state-architecture/adding-a-button.md` (full)
- [ ] `docs/architecture/state-architecture/forbidden-patterns.md`
- [ ] ADR-0009 (companion to this spec)
- [ ] `PipelineModule.kt` terminal arms + `InsertionService.kt` +
      `InfoBarSelector.kt` pending-insert producer

## 2. Acceptance Criteria

**R1 — secondary record button**

1. In `KEYBOARD_TWO_ROW_SEND_MODE` and `KEYBOARD_SINGLE_ROW_SEND_MODE`,
   a `RECORD_SECONDARY` slot renders a mic button in the Row-1 chain
   position right of the (hidden) `resend_btn` slot, visible iff
   `recording is Idle && (pipeline is Preparing || pipeline is Running)`.
2. Tapping it dispatches `StartRecording` with a fresh sessionId +
   pre-allocated audio file (same contract as
   `resolveRecordAction`'s Idle arm; shared helper, no copy-paste).
3. While the secondary recording is live, `LayoutCatalog.forKeyboard`
   returns the standard recording layouts (recording controls win over
   SEND_MODE); the running pipeline continues undisturbed (pipeline
   axis untouched by recording actions — verified by reducer tests).
4. The secondary button is never visible while `recording !is Idle`
   (single-MediaRecorder decision) and never in non-SEND modes.

**Queue (R1/R3/R4 backbone)**

5. `TriggerPipeline` while `Preparing`/`Running` appends a
   `QueuedRun(sessionId, audioFile, enqueuedAt)` to the FSM's `queued`
   list (dedup by sessionId) instead of being silently dropped —
   regression test proves the old silent-reject fails this test.
6. `PipelineDone` / `PipelineFailed` / `CancelPipeline` /
   `RejectedJobAlreadyActive` with a non-empty queue transition to
   `Preparing(next.sessionId, queued = rest)` and emit
   `SubmitPipeline(next)` + `UpdateNotification` — and do **not** emit
   `DismissNotification`. With an empty queue, today's behavior is
   byte-identical (regression-kept by existing tests).
7. `PipelineUiState.Idle` never coexists with a non-empty queue
   (structural: `Idle` carries no queue field).
8. `PipelineRunnerSubsystemAdapter.submit` no longer races the
   previous job's teardown: if `ActiveJobRegistry` is still occupied,
   submission is deferred until the registry empties (bounded await);
   on timeout the session fails loudly via the existing
   `PipelineFailed` path, never silently.

**R2 — continuation on app close**

9. Closing the IME view during `Preparing`/`Running` keeps the run
   alive (FGS) and auto-opens the widget (existing W3) — pinned by a
   JVM test on `WidgetModule` W3 with `pipeline is Running`.
10. A result finishing while `imeViewVisible == false` is deferred
    (never dropped, never committed to a wrong window) and offered via
    the pending-parts InfoBar item on the next keyboard open.

**R3 — immediate insertion while busy**

11. A run completing while `canCommitToHost` is inserted immediately
    with `InsertionPolicy.PIPELINE`, regardless of a live secondary
    recording or queued runs; the insert request carries an explicit
    `sessionIdOverride` (never relies on
    `SessionTracker.getCurrentSessionId()`).

**R4 — deferred ordered parts**

12. A result completing while `!canCommitToHost` becomes a pending
    part whose `createdAt` is the session's DB `created_at`
    (recording order), not the completion time.
13. The InfoBar shows **one aggregate** pending-parts item (count in
    the label, singular/plural strings). Confirm flushes **all** parts
    in recording order as separate sequential `InsertionService.insert`
    calls (one commit + one audit row per session, each with explicit
    `sessionIdOverride`); every part after the first in a batch is
    prefixed with a single space. Dismiss dismisses all parts
    (per-session `markInserted`, history rows untouched).
14. When a fresh run completes while older pending parts exist and
    `canCommitToHost`: parts are flushed first (recording order), then
    the fresh result is inserted (space-prefixed) — document order
    equals dictation order.
15. A failed part-insert stops the flush; the failed part and all
    later parts remain pending (nothing is consumed without a
    successful commit).

**R5 — cancel surfacing**

16. `CancelPipeline` on the active run produces a dismiss-only
    info-bar notice ("processing cancelled") via a new typed
    `InfoHintState.cancellation` field + producer — no reuse of the
    error path (F-076 stays intact: `fromInfoKey("cancelled")` remains
    `null`). The hint auto-clears on the next recording/pipeline start
    (existing cross-clear cascade, extended).
17. Cancelling the active run does not cancel queued runs — the next
    queued run chain-starts (criterion 6).

**Global**

18. `./gradlew :app:testDebugUnitTest` and `:app:assembleDebug` green;
    every behavior-fix lands with a red-provable regression test.

## 3. Architecture Specification

### 3.1 State: queued runs inside the pipeline FSM

`DictateUiState.kt`:

```kotlin
/**
 * One pipeline run waiting behind the active run. Enqueued by
 * PipelineModule's TriggerPipeline arm while the FSM is Preparing/
 * Running; chain-started by the terminal arms. @see ADR-0009.
 *
 * The per-session JobRequest config is NOT carried here — it already
 * lives in ImePipelineConfigResolver's sessionId-keyed snapshot map,
 * captured at the send-tap and consumed by resolveFresh at submit.
 */
data class QueuedRun(
    val sessionId: String,
    val audioFile: File,
    val enqueuedAt: Long,
)
```

`PipelineUiState.Preparing` and `PipelineUiState.Running` each gain:

```kotlin
val queued: PersistentList<QueuedRun> = persistentListOf(),
```

Defaulted → every existing construction site stays source-compatible.
`Idle` and `ReprocessStaging` carry **no** queue (invariant #7).

### 3.2 PipelineModule reducer semantics

- **`TriggerPipeline`:**
  - `Idle` → unchanged (Preparing + `SubmitPipeline` + notification).
  - `Preparing`/`Running` → same variant with
    `queued = queued.appendIfAbsent(QueuedRun(sessionId, audioFile, ctx.now))`
    (dedup by sessionId); **no** submit effect. No notification change.
  - `ReprocessStaging` → `null` (unchanged; staging is entered from
    Idle and the secondary button is not offered there).
- **Terminal arms** (`PipelineDone`, `PipelineFailed`,
  `CancelPipeline`, `RejectedJobAlreadyActive`, and any other arm that
  transitions to `Idle` today): route the "what next" decision through
  one private helper:

```kotlin
/** Terminal transition: drain the queue or return to Idle. */
private fun nextAfterTerminal(
    queued: PersistentList<QueuedRun>,
): Pair<PipelineUiState, List<Effect>> =
    if (queued.isEmpty()) {
        PipelineUiState.Idle to listOf(Effect.DismissNotification)
    } else {
        val next = queued.first()
        PipelineUiState.Preparing(
            sessionId = next.sessionId,
            queued = queued.removeAt(0),
        ) to listOf(
            Effect.SubmitPipeline(next.sessionId, next.audioFile),
            Effect.UpdateNotification(
                NotificationStatus.Pipeline(next.sessionId, step = "preparing"),
            ),
        )
    }
```

Per-arm effects that are about the *finished* session
(`MarkSessionInserted`, `MarkSessionFailed`, `AddPendingInsertSession`,
`CancelPipelineJob`) stay exactly as they are and are concatenated
with the helper's result. `DismissNotification` moves out of the arms
into the helper (only fired when the chain actually ends).

**Cross-module consequences (documented, accepted):** the
`pipeline != Idle → Idle` boundary observers (`OnPipelineDone` T7,
`ResendAction.MarkLastAudio`, LivePrompt `ChainNext`, InfoHint clears,
W6-family quiescence) fire once **at chain end**, not between chained
runs. That is the desired UX: the widget/progress surfaces stay up
through the whole chain; resend targets the last session of the chain.

### 3.3 Submit-when-free gate (runner adapter)

`PipelineRunnerSubsystemAdapter.submit(sessionId, audioFile)` (and the
reprocess submit) currently forwards straight into
`JobExecutor.start`. The chain-start submit races the finishing job's
teardown: the completion callback (which dispatches `PipelineDone`)
runs *inside* the worker's run, before the `finally` block unregisters
the job (`JobExecutor.kt:189-193`) — so `ActiveJobRegistry.register`
can still see the old entry for a few milliseconds.

Change: before starting, if `ActiveJobRegistry` is non-empty, launch
on the service scope, await the registry's reactive state becoming
empty (bounded, `SUBMIT_WHEN_FREE_TIMEOUT_MS = 15_000`), then start.
On timeout: emit the session's failure through the existing
`PipelineFailed` action path (reason string constant, e.g.
`"submit-gate timeout"`) so the standard error surfacing + terminal
handling (including queue drain) applies. `JobExecutor` and
`ActiveJobRegistry` themselves stay **unchanged** — the FSM guarantees
at most one job is submitted at a time; the gate only absorbs the
teardown window.

### 3.4 Layout: mode precedence + RECORD_SECONDARY

**`LayoutCatalog.forKeyboard`** — new precedence row (recording wins):

```kotlin
return when {
    isStaging -> KEYBOARD_REPROCESS_STAGING
    // NEW: a live recording needs its controls (timer / pause / trash /
    // stop&send) even while a pipeline run processes in the background.
    recordingLive && singleRow -> KEYBOARD_SINGLE_ROW
    recordingLive && !singleRow -> KEYBOARD_TWO_ROW
    isPipelineLive && singleRow -> KEYBOARD_SINGLE_ROW_SEND_MODE
    ...
}
```

with `recordingLive = state.recording !is RecordingState.Idle`.
(Today `recordingLive && isPipelineLive` is unreachable, so this row
changes nothing for existing flows — pinned by a test.)

**`LogicalButtonId.RECORD_SECONDARY`** — per
`adding-a-button.md` §3, seven steps:

1. Enum entry `RECORD_SECONDARY` in `LogicalButtonId.kt`.
2. `MaterialButton` `secondary_record_btn` in
   `activity_dictate_keyboard_view.xml` inside `main_buttons_cl`,
   `app:icon = @drawable/ic_baseline_mic_24`, `wrap_content`,
   `minWidth` per row convention, `motion:visibilityMode="ignore"`
   (mandatory, forbidden-pattern (k)).
3. MotionScene chain re-link in **every** ConstraintSet of
   `motion_scene_keyboard.xml` that lays out Row 1 (base
   `two_row_state`; verify the derived SEND_MODE/single-row sets for
   overrides): `resend_btn.end → secondary_record_btn.start`,
   `secondary_record_btn` between `resend_btn` and `backspace_btn`,
   `backspace_btn.start → secondary_record_btn.end`. Margins per the
   existing 8dp convention.
4. View-map entry in `DictateInputMethodService` (`findViewById` +
   `buttonViews.put(RECORD_SECONDARY, …)`) — missing entries are a
   render-time `error(...)`.
5. No new Action: the slot resolves to the existing
   `Action.RecordingAction.StartRecording`.
6. `ButtonSlot` added to **all five** keyboard modes (convention:
   RESEND is listed with `visibilityPredicate = { false }` in
   SEND_MODE — follow it so the view can never linger):
   - SEND_MODE modes: `visibilityPredicate = { it.recording is
     RecordingState.Idle }` (pipeline-live is implied by mode
     selection; the recording check is the single-MediaRecorder gate),
     `actionResolver = ::resolveSecondaryRecordAction`.
   - all other modes: `visibilityPredicate = { false }`.
7. Tests (see §6).

**`resolveSecondaryRecordAction`** (in `ActionResolvers.kt`): from
`recording is Idle` → exactly the Idle-arm behavior of
`resolveRecordAction` (mint UUID, `allocateFirst`, return
`StartRecording(target, audioFile, sessionId)`); otherwise `null`.
**Extract the shared Idle-arm body into one private helper** used by
both resolvers — no duplicated allocation logic.

**Overlay:** unchanged in this iteration (deliberate scope cut, §D6).

**IME-side affordances:** the send-tap path for the secondary
recording is the existing one (`stopRecording` new path →
`captureFreshConfigSnapshot(sessionId, …)` → dispatch
`StopRecordingAndSend`). `ImePipelineConfigResolver` is already
sessionId-keyed (`ConcurrentHashMap`), so queued runs consume their
own config snapshots — **no change needed**. Verify (test exists?
add one if not) that `primePipelineUiForNewPath` and other one-shot
IME bookkeeping tolerate `pipeline != Idle` at the send-tap.

### 3.5 Ordered pending parts (R4)

**Recording-order key.** `PipelineModule.runEffect` for
`AddPendingInsertSession` currently forwards `effect.createdAt`
(= `ctx.now` at PipelineDone). Change the effect handler to resolve
the session's `created_at` via `services.sessionRepo` (effects may do
IO) and dispatch `PendingSessionsAction.AddOne` with that value;
fall back to the effect's `createdAt` when the row is missing. (The
race that motivated carrying the payload on the effect was about the
COMPLETED *status* write; `created_at` is written at session creation
and is race-free.) `InfoBarSelector`'s existing
`sortedBy { it.createdAt }` then yields recording order, and the
DB-recovery path (`loadPending`, which already returns `created_at`)
is consistent by construction.

**Aggregate InfoBar item.** Replace the per-session pending-insert
items in `InfoBarSelector.select` with **one** item derived from the
ordered list of COMPLETED+text pending sessions:

- label: plurals resource ("Insert 1 pending part" / "Insert %d
  pending parts" — de: "1 wartenden Teil einfügen" / "%d wartende
  Teile einfügen"; exact wording implementer's choice, strings.xml +
  values-de).
- `createdAt` = oldest part's `createdAt` (keeps bar ordering stable).
- confirm → `PendingSessionsAction.AcceptAndInsertAll` (marker action;
  the IME side-channel intercepts it — see flusher below).
- dismiss → `PendingSessionsAction.DismissAll`.
- when `!state.canCommitToHost` (bar rendered on the overlay surface):
  emit the item **without** the confirm action (info-shaped: "parts
  waiting — open the keyboard to insert"), so the accept can never
  silently no-op into a missing InputConnection (today's known trap,
  `DictateInputMethodService.java:1665-1675`).

**`PendingSessionsModule`:**

- `AcceptAndInsertAll` — reducer returns state unchanged with no
  effects (the action is an IME side-channel trigger; per-part
  consumption still flows through the existing
  `AcceptAndInsert(sessionId)` arm so state+DB marking stays
  per-session and race-free).
- `DismissAll` — removes every COMPLETED pending-insert entry and
  emits one `PersistDismissal` per removed session (reuse the existing
  effect; RECORDED / RECORDING_INTERRUPTED entries are untouched —
  they belong to the resume-recording producers).

**`PendingPartsFlusher`** (new, `state/insertion/`, pure Kotlin,
JVM-tested): the ordered flush executor the IME calls.

```kotlin
/**
 * Inserts pending parts in recording order as separate sequential
 * commits. Stops at the first failed commit — nothing is consumed
 * without a successful insert. @see ADR-0009, spec §3.5.
 */
class PendingPartsFlusher(
    private val insertion: InsertionService,
    private val dispatch: (Action) -> Unit,
) {
    /** @return number of parts successfully inserted. */
    fun flush(parts: List<PendingPart>): Int
}

data class PendingPart(val sessionId: String, val text: String)
```

Behavior per part *i*: build text = (`i > 0` in this batch ? `" "` :
`""`) + `part.text`; call `insertion.insert` with the new
`InsertionPolicy.PENDING_PART` and
`sessionIdOverride = part.sessionId`; on
`InsertionResult.Committed` → `dispatch(AcceptAndInsert(sessionId))`
(consume + `markInserted`); on anything else → stop, return count.
Note the ordering fix vs. today's accept side-channel: **insert
first, consume after** (today dispatches first and can lose the text
to a dead IC).

**`InsertionPolicy.PENDING_PART`** (in `Insertion.kt`, next to
PIPELINE/RESEND/KEYSTROKE): `respectHostGuard = true`,
`audit = true`, `autoEnter = false`, `resumeOnFailure = false`,
`anchoredToCaptured = false`, animation off (instant paste — these
are catch-up parts, not live dictation output). Requires an
`InsertionSource` value for the audit row — reuse the existing enum's
closest value or add `PENDING_PART` (implementer checks the enum; a
new value is expected).

**IME wiring:**

- Side-channel: intercept `AcceptAndInsertAll` where the InfoBar
  confirm currently intercepts `AcceptAndInsert`
  (`DictateInputMethodService.java:1652-1683`): read the ordered
  COMPLETED parts from state, run `PendingPartsFlusher.flush`. The
  marker action itself is still dispatched (reducer no-ops) so the
  dispatch-outcome log stays truthful.
- `onPipelineCompleted` (fresh result, `:4540-4596`): when
  `canCommitToHost` and pending COMPLETED parts exist → flush them
  first; then insert the fresh result with `PIPELINE` policy,
  prefixed with a single space iff at least one part was flushed;
  `committed` for `PipelineDone` reflects the fresh result's own
  insert outcome (unchanged semantics otherwise).
- **Audit hardening:** the fresh-result insert request now always
  carries `sessionIdOverride = sid` (the completed run's id) so the
  audit never falls back to `SessionTracker.getCurrentSessionId()`
  (9637fc3 family; with queued runs "current" is ambiguous by
  construction).

### 3.6 Cancel surfacing (R5)

- `InfoHintState` gains
  `val cancellation: CancellationHint? = null` with
  `data class CancellationHint(val occurredAt: Long)`.
- New leaves in `Action.InfoHintAction`: `PipelineCancelled` (sets the
  field via `ctx.now`) and `DismissCancellationHint` (clears it).
- Producer: `PipelineModule`'s `CancelPipeline` arm additionally emits
  a new `Effect.NotifyCancellationHint`, whose `runEffect` does
  `services.emitAction(Action.InfoHintAction.PipelineCancelled)`
  (Mode-1 cross-module emit, same pattern as the existing
  notification effects). This covers every cancel origin (keyboard
  button, FGS notification action) because all route through
  `CancelPipeline`.
- `InfoBarSelector`: new dismiss-only producer — NOTICE-style item,
  text "Processing cancelled" (strings.xml + values-de), `createdAt =
  cancellation.occurredAt`, dismiss → `DismissCancellationHint`.
- Cross-clear: extend `InfoHintModule`'s existing
  `ClearTransientHints` cascade (new recording/pipeline start,
  IME-hide-while-idle) to also clear `cancellation`.
- **F-076 stays intact**: `PipelineErrorKind.fromInfoKey("cancelled")`
  remains `null`; the cancel notice is a deliberate, typed hint, not
  an error resurrection.

### 3.7 R2 gap closure — verification, not construction

Research confirmed the R2 pillars exist: FGS keeps the run alive on
IME-view hide (ADR-0003; `onFinishInputView` states A/B), W3 auto-opens
the widget when the IME hides while `recording.isActiveOrPaused ||
pipeline !is Idle`, and a deferred result is offered on the next
keyboard open via pendingSessions (DB `loadPending` on recovery + the
in-RAM `AddOne` path). What this spec adds for R2 is (a) the
not-insertable variant of the aggregate item (§3.5) so the widget
surface never offers a dead insert, and (b) **pinning tests** for the
walkthrough (W3 with `pipeline is Running`; deferred-not-dropped on
`imeViewVisible = false`). Known, accepted limitation: on FGS death
(keyboard switch), in-memory queued runs are lost as *runs* — their
sessions persist as RECORDED rows and resurface through the existing
resume-offer machinery (ADR-0009 §Failure Modes).

## 4. Directory Layout (delta)

```
app/src/main/java/net/devemperor/dictate/
├── state/
│   ├── DictateUiState.kt                        [EDIT]  QueuedRun; queued fields on Preparing/Running; InfoHintState.cancellation (+CancellationHint)
│   ├── Action.kt                                [EDIT]  InfoHintAction.PipelineCancelled / DismissCancellationHint; PendingSessionsAction.AcceptAndInsertAll / DismissAll
│   ├── modules/PipelineModule.kt                [EDIT]  enqueue arm, nextAfterTerminal helper, NotifyCancellationHint effect, AddPendingInsertSession created_at resolution
│   ├── modules/PendingSessionsModule.kt         [EDIT]  AcceptAndInsertAll (no-op arm), DismissAll
│   ├── modules/InfoHintModule.kt                [EDIT]  cancellation reduce arms + cross-clear
│   ├── infobar/InfoBarSelector.kt               [EDIT]  aggregate pending-parts item; cancellation producer
│   ├── insertion/Insertion.kt                   [EDIT]  InsertionPolicy.PENDING_PART (+ InsertionSource value if needed)
│   ├── insertion/PendingPartsFlusher.kt         [NEW]   ordered flush executor (§3.5)
│   └── layout/
│       ├── LogicalButtonId.kt                   [EDIT]  RECORD_SECONDARY
│       ├── LayoutCatalog.kt                     [EDIT]  forKeyboard precedence; RECORD_SECONDARY slots (all 5 keyboard modes)
│       └── ActionResolvers.kt                   [EDIT]  resolveSecondaryRecordAction + extracted shared start-recording helper
├── core/
│   ├── DictateInputMethodService.java           [EDIT]  view-map entry; AcceptAndInsertAll side-channel; flush-before-fresh-insert; sessionIdOverride on pipeline insert
│   └── PipelineRunnerSubsystemAdapter*          [EDIT]  submit-when-free gate (§3.3; exact file verified by implementer)
├── res/layout/activity_dictate_keyboard_view.xml [EDIT] secondary_record_btn
├── res/xml/motion_scene_keyboard.xml            [EDIT]  Row-1 chain re-link (all affected ConstraintSets)
└── res/values{,-de}/strings.xml                 [EDIT]  pending-parts plurals; cancellation notice
```

Tests: sibling `*Test.kt` files under `app/src/test/` per §6.

## 5. Migration Plan (compile-green steps)

1. **Pipeline queue core.** `QueuedRun` + `queued` fields (defaulted →
   compile-green immediately); `TriggerPipeline` enqueue arm;
   `nextAfterTerminal` threaded through all terminal arms; tests.
2. **Submit-when-free gate.** Adapter await + timeout→`PipelineFailed`;
   tests with fake registry flow. (Independent of step 1 at compile
   level; behaviorally required before step 3 ships.)
3. **Secondary record button + layout precedence.** `forKeyboard`
   precedence row; enum + XML + MotionScene + view-map + resolvers +
   slots; tests. (UI-only; queue from step 1 absorbs the new
   trigger.)
4. **Ordered pending parts.** created_at resolution; `AcceptAndInsertAll`
   / `DismissAll`; aggregate producer (+ not-insertable variant);
   `PENDING_PART` policy; `PendingPartsFlusher`; IME wiring
   (side-channel, flush-before-fresh, sessionIdOverride); strings;
   tests.
5. **Cancel hint.** `InfoHintState.cancellation` + actions + effect +
   producer + cross-clear + strings; tests.
6. **Docs + closure.** Module-inventory/table touch-ups
   (`DictateUiState` KDoc tables, `modules.md` if counts change),
   ADR-0009 finalization, spec status → Accepted with Change History.

Each step compiles and passes the full unit suite on its own; steps 1
and 2 must land before step 3 (a secondary recording's send would
otherwise be silently dropped — the exact bug class this feature
removes).

## 6. Testing Approach

- **Reducer tests (JVM)** — `PipelineModuleTest`: enqueue on
  Preparing/Running (incl. dedup + ReprocessStaging still rejects);
  chain-start on Done/Failed/Cancel/Rejected with queue (state +
  effects asserted, incl. *no* DismissNotification mid-chain); empty
  queue byte-identical to today. **Red-proof:** the enqueue test fails
  on the pre-change reducer (silent reject) — run before implementing.
  `PendingSessionsModuleTest`: DismissAll scoping (COMPLETED only) +
  per-session PersistDismissal. `InfoHintModuleTest`: cancellation
  set/dismiss/cross-clear. `RecordingModuleTest`: StartRecording from
  Idle with `pipeline = Running` in `ctx.global` still transitions
  (pin R1's "recording doesn't care about pipeline").
- **Selector tests** — `InfoBarSelectorTest`: aggregate item (count,
  ordering by createdAt == recording order, confirm/dismiss actions,
  not-insertable variant when `imeViewVisible = false`); cancellation
  producer; W3 pin test lives in `WidgetModuleTest`.
- **Layout tests** — `LayoutCatalogTest` / `VisibilityMatrixTest` /
  `ActionResolversTest`: forKeyboard precedence matrix (recording ×
  pipeline × singleRow); RECORD_SECONDARY visibility matrix; resolver
  returns StartRecording from Idle / null otherwise; shared-helper
  parity with `resolveRecordAction`'s Idle arm (same allocation
  contract).
- **Flusher tests** — `PendingPartsFlusherTest` (fake
  InsertionService collaborators): order, space-prefix policy,
  stop-on-failure (later parts unconsumed), per-part AcceptAndInsert
  dispatch with sessionIdOverride, audit policy flags.
- **Gate tests** — adapter submit-when-free: immediate submit on empty
  registry; deferred submit on occupied→empty; timeout →
  PipelineFailed emission.
- **No instrumented tests required**; on-device verification script is
  delivered with the final report (MotionScene chain, real IC
  behavior, FGS notification flow).

## 7. Decision Log

### D1 — Serialized queue, not parallel execution

**Decision:** concurrency is modeled as ordered queued runs inside the
pipeline FSM; execution stays strictly one-job-at-a-time.
**Rationale + alternatives:** ADR-0009 (canonical).

### D2 — Queue lives on Preparing/Running, not a new axis

**Trigger:** single-owner-per-axis vs. consumer churn.
**Decision:** `queued` is a defaulted field on the existing sealed
variants — `state.pipeline`'s type is unchanged for all ~consumers,
`Idle`-with-queue is unrepresentable, and the queue dies coherently
with the run chain. **Alternative** (new `pipelineQueue` axis or
composite lens state): rejected — changes the module's lens type and
every consumer signature for no semantic gain.

### D3 — Flush is consent-gated on keyboard-open, automatic mid-flow

**Decision:** pending parts auto-flush only as a prefix of an active
insertion flow (a fresh result committing); on keyboard-(re)open they
are offered via one aggregate InfoBar confirm.
**Rationale:** R4's "next opportunity/processing" satisfied without
violating the ADR-0003 surprise-free principle (§1.3 alternative 3).

### D4 — Separator is a single space, first part bare

**Decision:** within one flush batch, parts 2..n (and a fresh result
following a flush) are prefixed with `" "`; the first insert of a
batch is bare (consistent with today's single-part accept and with
live pipeline commits, which add no spacing either).
**Rationale:** dictated parts end with punctuation in practice; a
space is the neutral joiner. A future pref can widen this — the
policy is one constant in `PendingPartsFlusher`.

### D5 — Cancel cancels the active run only

**Decision:** queued runs survive a cancel and chain-start.
**Rationale:** the cancel affordances (keyboard button, notification)
visually target the *running* progress; queued parts are separate
user content. Cancelling a queued run = cancel it when it becomes
active (no dedicated queued-cancel UI in this iteration).

### D6 — No overlay secondary record button (scope cut)

**Decision:** the RECORD_SECONDARY slot ships on the keyboard
SEND_MODE layouts only; the floating widget keeps its merged
record/send button (auto-enter toggle during a run).
**Rationale:** R1 targets the keyboard row explicitly; the overlay is
156dp wide with a fixed 48dp-icon grid (width re-budget needed). The
slot machinery is surface-agnostic (`ButtonSlot` + resolvers), so the
overlay extension is additive later.

### D7 — Explicit sessionIdOverride on pipeline + part inserts

**Decision:** every insert produced by a pipeline result carries its
session id explicitly; `SessionTracker.getCurrentSessionId()` is no
longer on the audit path for these inserts.
**Rationale:** with overlapping sessions, "current" is ambiguous by
construction (9637fc3 family). Full SessionTracker retirement is out
of scope (ADR-0009 §Negative).

### D8 — Aggregate pending item shows a count, not a text preview

**Trigger:** implementation of §3.5 — the pre-R4 per-session item
carried a ~60-char text preview; an aggregate item for N parts has no
single text to preview.
**Decision:** the aggregate item's label is a plurals-backed count
("Insert N pending parts"); the preview helper was removed as dead
code. **Trade-off accepted:** the single-part case loses its preview;
a future refinement may re-add a preview for `count == 1` — additive,
not a revert. The overlay (not-insertable) variant keeps its dismiss
affordance (only confirm is dropped) so waiting parts remain
discardable from the widget surface.

### D9 — Queue survives the Preparing → Running hop (review fix)

**Trigger:** lead review of migration step 1 — `StartPipeline`
constructed `Running` fresh, silently dropping every second-in-line
run at the chain-start (and any mid-upload enqueue).
**Decision:** `StartPipeline` explicitly carries
`queued = state.queued` into `Running`; a red-proven regression test
pins it (`StartPipeline carries the queue from Preparing into Running`).

## 8. Information Gaps

1. **Exact adapter file/shape for the submit gate** — the class name
   `PipelineRunnerSubsystemAdapter` comes from docs; the implementer
   of step 2 locates the real submit seam (`services.pipelineRunner`)
   and applies §3.3 there. Fallback: gate directly where
   `JobExecutor.start`'s boolean return is handled.
2. **MotionScene derived-ConstraintSet overrides** — whether the
   SEND_MODE / single-row sets re-declare Row-1 constraints (research
   flagged "verify each"). Owner: step-3 implementer; fallback:
   re-link the chain in every set that mentions `resend_btn`.
3. **InsertionSource enum surface** — whether an existing value fits
   pending-part audits or a new `PENDING_PART` value is added (and
   whether the enum is DB-persisted → Double-Enum rule from
   `docs/DATABASE-PATTERNS.md` applies). Owner: step-4 implementer,
   who reads the enum + schema before deciding.
4. **InfoBar confirm interception mechanics** — the precise seam where
   the IME intercepts `AcceptAndInsert` today (`:1652-1683`) and how
   the marker action is threaded. Owner: step-4 implementer; the
   contract (insert-first-consume-after, per-part AcceptAndInsert) is
   fixed by §3.5 regardless of seam shape.
5. **Notification queue-count badge** — the FGS notification does not
   show "1 waiting" during a chain. Deliberately out of scope; future
   nicety once `NotificationStatus` grows a field.

## 9. References

- ADR-0009 — `docs/decisions/0009-pipeline-run-queue-serialized-concurrency.md` (companion decision record)
- ADR-0001/0002 (state modules + cascade), ADR-0003 (FGS), ADR-0004
  (LayoutCatalog), ADR-0006 (info bar), ADR-0007 (audio repository),
  ADR-0008 (surface axes) — `docs/decisions/`
- `docs/architecture/state-architecture/` — `adding-a-button.md`,
  `modules.md`, `forbidden-patterns.md`, `cross-module-cascade.md`
- `docs/research/2026-07-02 - infobar-consolidation.md` (InfoHint axis
  + F-076 cancelled-is-silent rationale)
- Key code: `state/modules/PipelineModule.kt`,
  `state/insertion/InsertionService.kt` + `Insertion.kt`,
  `state/infobar/InfoBarSelector.kt`,
  `state/layout/LayoutCatalog.kt` + `ActionResolvers.kt`,
  `core/ImePipelineConfigResolver.kt`, `core/JobExecutor.kt`,
  `core/ActiveJobRegistry.kt`,
  `core/DictateInputMethodService.java` (`onPipelineCompleted`,
  pending-insert side-channel, view map)

## Change History

### 2026-07-02 — Post-release fix: double record-icon on RECORD_SECONDARY

On-device, starting a secondary recording during a live pipeline run
(and, latently, every normal pipeline completion) left
`secondary_record_btn` stranded VISIBLE next to the primary record
controls — two record surfaces at once.

Root cause: `MotionScene.setTransition(int,int)` matches a declared
`<Transition>` in **one direction only** (verified against the
constraintlayout-2.2.1 bytecode). The scene declared only the forward
edges `base → SEND_MODE`. ADR-0009's recording-wins precedence makes
the **reverse** edge (`SEND_MODE → base`) reachable for the first time
(a `RECORD_SECONDARY` tap starts a recording, and `forKeyboard` then
returns the base recording layout). That undeclared reverse edge fell
back to MotionLayout's synthesized fade auto-transition, which does not
carry the per-view `visibilityMode="ignore"` PropertySets — the exact
hazard the scene's F-25 note warns about. `secondary_record_btn` is the
only button that flips VISIBLE→GONE across that edge, so the fade
stranded it visible after the slot renderer had already set it GONE.

Fix: declare the two reverse transitions explicitly
(`two_row_send_mode_state → two_row_state`,
`single_row_send_mode_state → single_row_state`, 200 ms) in
`motion_scene_keyboard.xml`, so the catalog stays the sole visibility
owner across the recording-starts-during-pipeline transition.
Red-proven by a new `MotionSceneSchemaTest` case asserting the reverse
edges are declared; `secondary_record_btn` was also added to the
`visibilityMode=ignore` schema invariant. No catalog/reducer logic
changed — `forKeyboard`'s precedence was already correct and tested.

### 2026-07-02 — Implemented; status → Accepted

Implemented in five commits on `main`, each with the full unit suite
green:

1. `c1bfceb` — run-queue core + submit-when-free gate (steps 1-2),
   incl. the review-found D9 fix (queue carried across
   `Preparing → Running`, red-proven).
2. `46383b1` — secondary record button + recording-wins layout
   precedence (step 3).
3. `28da773` — ordered pending parts: aggregate offer +
   recording-order flush (step 4); D8 recorded.
4. `169f55d` — cancel surfacing via typed info-bar notice (step 5).
5. closure commit (step 6) — docs, spec/ADR promotion, criterion-3
   pin in `RecordingModuleTest`.

Acceptance criteria 1-18 verified by the lead against the code; every
behavior change landed with a red-provable regression test (silent
TriggerPipeline drop; queue drop at the Running hop; completion-time
ordering; missing cancel surfacing; SEND_MODE-vs-recording layout
precedence). Information Gaps 1-4 were closed by the implementation:
the adapter is `PipelineRunnerSubsystemAdapter` (gap 1); the derived
MotionScene ConstraintSets inherit the base chain, so only the two
base sets were re-linked (gap 2); `InsertionSource.PENDING_PART` is
not DB-persisted — the Double-Enum migration rule does not apply
(gap 3); the side-channel seam is the InfoBar `onAction` closure
(gap 4). Gap 5 (notification queue-count badge) remains open by
design.

### 2026-07-02 — Initial spec

Written from three parallel research passes (pipeline/session
concurrency reality; insertion/pending/widget/infobar surfaces today;
layout slot system) with file:line evidence. Status starts at
"Spec — programmer-ready".
