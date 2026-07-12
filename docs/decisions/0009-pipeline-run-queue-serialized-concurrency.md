# ADR-0009: Pipeline — Ordered Run-Queue with Serialized Execution

**Status:** Accepted
**Subsystem:** state, service
**Date:** 2026-07-02
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0001, ADR-0002, ADR-0003.** ADR-0001 hosts the
> `PipelineModule` whose FSM this ADR widens; ADR-0002 carries the
> cascade semantics whose Idle-boundary observers move to "chain end";
> ADR-0003's FGS is what makes a queued run survive an IME-view hide
> at all.

## Research

Three parallel research passes (2026-07-02, feeding
`docs/research/2026-07-02 - concurrent-recording-deferred-insertion.md`)
established the load-bearing facts:

- **The system is single-session top to bottom.**
  `PipelineUiState` is one FSM slot; `TriggerPipeline` while
  `Preparing`/`Running` is silently dropped
  (`PipelineModule.kt:158-171`, `else -> null` with the comment
  "Already running — silently reject").
- **The execution layer is single-slot by design.**
  `ActiveJobRegistry.register` rejects when non-empty
  (`ActiveJobRegistry.kt:37-41`); `JobExecutor` has one
  `@Volatile activeToken`/`activeThread` and its `cancel(sessionId)`
  ignores the argument (`JobExecutor.kt:33-41, 212-215`);
  `PipelineOrchestrator` holds process-wide `@Volatile` per-run
  state — `cancelled`, `running`, step counters
  (`PipelineOrchestrator.kt:204-212`); the FGS notification is one
  slot (`PipelineNotificationCoordinator.kt:49-51`).
- **The DB/audit layer is already multi-session-safe.** Every
  `SessionManager` write takes `sessionId` as a parameter; the
  per-send pipeline config is stored sessionId-keyed
  (`ImePipelineConfigResolver.kt:118`, `ConcurrentHashMap`) and
  consumed per-submit.
- **The fragile identity path is `SessionTracker.current*`** — single
  `@Volatile` slots read by the insertion audit when no override is
  passed (`DictateInputMethodService.java:4814-4817`); the 9637fc3
  fix worked around one instance of a late write hitting a cleared
  slot.
- **The deferred-insert foundation exists.**
  `PipelineDone(committed=false)` → `Effect.AddPendingInsertSession` →
  `pendingSessions` → pending-insert InfoBar item
  (`PipelineModule.kt:338-363`, `PendingSessionsModule.kt:84-127`).

## Context

The feature "concurrent secondary recording + deferred ordered
insertion" needs a second session to *exist* while a run processes:
the user records part B during part A's processing; B's send must not
be silently dropped; finished results must reach the host field — or
the pending list — **in recording order**, with clean per-session
audit rows. The user constraint "secondary recording only during
processing, never during another recording" caps live recordings at
one (single `MediaRecorder`); the number of not-yet-finished runs is
unbounded in principle (record C while B is queued behind A).

The design question: does `PipelineModule` become a multi-session
state machine with parallel execution, or does a session-scoped
ordered model carry the concurrency while execution stays serialized?

## Decision

**Concurrency is modeled as an ordered queue of runs inside the
existing pipeline FSM; execution stays strictly serialized (one job at
a time).**

1. `PipelineUiState.Preparing` and `.Running` gain
   `queued: PersistentList<QueuedRun>` (defaulted empty), with
   `QueuedRun(sessionId, audioFile, enqueuedAt)`. `Idle` and
   `ReprocessStaging` carry no queue — "Idle with waiting work" is
   unrepresentable.
2. `TriggerPipeline` while `Preparing`/`Running` **enqueues**
   (dedup by sessionId) instead of silently rejecting. The Idle arm
   is unchanged.
3. Every terminal reducer arm (`PipelineDone`, `PipelineFailed`,
   `CancelPipeline`, `RejectedJobAlreadyActive`) routes through one
   helper: queue empty → `Idle` + `DismissNotification` (today's
   behavior, byte-identical); queue non-empty → chain-start
   `Preparing(next)` + `SubmitPipeline(next)` + notification update.
4. The runner adapter's submit becomes **submit-when-free**: if
   `ActiveJobRegistry` is still occupied (the finishing job's
   teardown window — completion callbacks run before the executor's
   `finally` unregisters), await the registry's reactive state
   becoming empty (bounded timeout), then start; on timeout fail the
   session loudly via the existing `PipelineFailed` path.
   `JobExecutor` / `ActiveJobRegistry` / `PipelineOrchestrator` are
   otherwise **unchanged**.
5. Inserts produced by pipeline results (fresh commits and pending-
   part flushes) carry an explicit `sessionIdOverride`; the
   `SessionTracker.getCurrentSessionId()` fallback is off the audit
   path for these inserts.

### Scope of this Convention

Applies to the pipeline lifecycle layer: `PipelineModule`, the runner
submit seam, and every consumer of "which run is active / what is
waiting". Out of scope: parallel *execution* (see Supersede Triggers),
the recording FSM (stays single-recording by product decision), and
history-reprocess jobs (which keep their existing
`isAnyActive()`-guarded mutual exclusion — a queued+running chain
keeps that guard `true`, so history reprocess remains blocked during a
chain, as today during a single run).

## Alternatives Considered

1. **True multi-session pipeline state + parallel execution.**
   `Map<sessionId, PipelineUiState>` axis, per-job tokens in
   `JobExecutor`, per-run context objects in `PipelineOrchestrator`,
   notification aggregation, SessionTracker retirement. Rejected:
   (a) it refactors four load-bearing singletons at once
   (`PipelineOrchestrator.kt:204-212`, `JobExecutor.kt:36-41`,
   `PipelineNotificationCoordinator.kt:49`, `SessionTracker.kt:32-36`)
   for a capability the feature does not need; (b) parallel completion
   order is nondeterministic, so R4's recording-order insertion would
   need a reorder buffer at the insertion boundary — re-introducing
   the very ordering machinery the queue gives structurally; (c) the
   transcription steps are network-bound and the overlap window is
   short — the latency win is marginal against the risk.
2. **Queue below the state layer** (relax `ActiveJobRegistry.register`
   and let the single-thread executor FIFO jobs). Rejected: the queue
   would be invisible to `DictateUiState` — declarative rendering
   (ADR-0004) could not show it, `JobExecutor.start` overwrites the
   single `activeToken` at submit so cancel would target the wrong
   job, and the `isAnyActive()` history-guards would change meaning
   implicitly.
3. **Keep silent-reject; block the secondary send until Idle** (UI
   disables the send while a run is active). Rejected: turns the
   feature into "you may record but not send" — the user's part B
   would sit as a paused recording occupying the single
   `MediaRecorder`, blocking part C and the exact dead-time the
   feature removes.
4. **A separate queue axis / composite lens state** for
   `PipelineModule` instead of widening `Preparing`/`Running`.
   Rejected: changes the module's lens type (`S`) and every
   `state.pipeline` consumer's assumptions for no semantic gain;
   the defaulted-field form is source-compatible and makes
   Idle-with-queue unrepresentable.

## Consequences

**Positive:**

- Recording order == trigger order == completion order == insertion
  order, structurally. No reorder logic exists anywhere.
- All single-slot invariants of the execution layer stay valid; the
  blast radius is one module + one adapter seam.
- The queue is pure FSM payload: JVM-tested reducers, declarative
  rendering, coherent lifecycle with the run chain.
- Future parallelism is an execution-policy change behind the same
  state model (the state already represents N in-flight sessions).

**Negative:**

- **Latency:** a queued run waits for the active run to finish even
  when the backend could process both. Accepted — the overlap window
  is seconds and ordering is a feature requirement.
- **Chain-end semantics:** cross-module observers keyed on the
  `pipeline != Idle → Idle` boundary (T7/quiescence, resend
  `MarkLastAudio`, LivePrompt `ChainNext`, InfoHint clears) fire once
  per chain, not per run. Desired for the surfaces (widget/progress
  stay up), but LivePrompt chaining after a queued batch refers to
  the last run — a semantic drift users of that niche feature may
  notice.
- **SessionTracker survives.** Full retirement of the `current*`
  slots is out of scope; only the pipeline-insert audit path drops
  its dependence on them. The remaining consumers (cancel routing,
  orchestrator-internal step tracking) are safe *because* execution
  stays serialized — a future parallel-execution ADR must retire the
  tracker first.
- No queued-run cancel UI and no queue-count badge on the FGS
  notification in this iteration.

**Failure Modes:**

- **Submit-gate timeout.** If the registry never empties (wedged
  worker), the queued session fails via `PipelineFailed` after the
  bounded await — loud, not silent; the queue drains through the same
  terminal helper. A wedged worker was already fatal before this ADR.
- **FGS death with a non-empty queue** (keyboard switch → service
  teardown, ADR-0003 mechanic 9). The in-memory queue is lost as
  *runs*; the sessions persist as RECORDED rows and resurface through
  the existing DB-replay resume offers. Users see "resume?" instead
  of automatic continuation — consistent with ADR-0003's
  surprise-free recovery philosophy.
- **IME death mid-chain.** `PipelineDone` is dispatched only by the
  IME (`DictateInputMethodService.java:4558, 4595`); if the IME
  service dies while the FGS lives, the FSM can hold `Running(A)`
  stale and the chain stalls until the next bind/recovery
  reconciles. Pre-existing gap (single runs stall identically);
  the queue widens its blast radius from one run to the chain.
  Tracked as an information gap; a service-side completion dispatch
  is the eventual fix.
- **Dedup masks re-triggers.** `TriggerPipeline` for a sessionId
  already queued is dropped by the dedup. Correct for double-taps;
  wrong if a legitimate re-trigger of the same session ever needs to
  queue behind itself (none exists today — resend/reprocess routes
  through different actions).

## References

- **Related Spec:** [`docs/research/2026-07-02 - concurrent-recording-deferred-insertion.md`](../research/2026-07-02%20-%20concurrent-recording-deferred-insertion.md) — the feature spec this ADR anchors (bidirectional).
- **Related ADRs:** ADR-0001 (module/orchestrator pattern — PipelineModule host), ADR-0002 (cascade semantics at the chain-end boundary), ADR-0003 (FGS lifecycle; recovery philosophy the failure modes lean on), ADR-0006 (info-bar surfacing of pending parts + cancellations), ADR-0008 (widget axes; W3 auto-open keyed on `pipeline !is Idle`), ADR-0012 (post-processing conversation — runs through this queue's `committed=false` + `nextAfterTerminal` machinery unchanged).
- **Implementation pointers:** `state/modules/PipelineModule.kt` (FSM), `core/JobExecutor.kt` + `core/ActiveJobRegistry.kt` (execution layer), `core/ImePipelineConfigResolver.kt` (sessionId-keyed config snapshots).

## Decision History

### 2026-07-02 — Accepted (implementation landed)

**Trigger:** the companion spec's implementation completed on `main`
(commits `c1bfceb`, `46383b1`, `28da773`, `169f55d` + closure), full
unit suite green at every step.

**Before:** Status: Proposed — the decision was a design commitment
pending implementation.

**After:** Status: Accepted (body now append-only per
knowledge-adr-format §"Lifecycle and editing rules"). One
implementation-time refinement worth recording: the queue must be
explicitly carried across the `Preparing → Running` transition
(`StartPipeline` constructs `Running` fresh); the lead review caught
the silent second-in-line drop and a red-proven regression test pins
it (spec §Decision Log D9).

**Reasoning:** all acceptance criteria of the companion spec verified
against the code; the serialized-queue model behaved as designed
(empty-queue paths byte-identical to the pre-ADR behavior, pinned by
the pre-existing test suite).

### 2026-07-02 — Initial proposal

**Trigger:** Feature request "concurrent secondary recording +
deferred ordered insertion" (R1-R5); research showed `TriggerPipeline`
silently dropping while busy and a fully single-slot execution layer.

**Before:** One pipeline run at a time, hard-gated at four layers
(FSM silent-reject, registry lock, single executor token, orchestrator
`@Volatile` fields). A second send during a run was silently lost.

**After:** Ordered `queued` list on `Preparing`/`Running`; enqueue
instead of reject; chain-start on terminal transitions; submit-when-
free gate at the adapter; explicit sessionIdOverride on pipeline-
result inserts. Execution remains one job at a time.

**Reasoning:** The feature needs ordered multi-session *state*, not
parallel *execution*. Serialization keeps every single-slot invariant
valid, makes recording-order insertion structural, and leaves true
parallelism as a future execution-policy swap behind an unchanged
state model.
