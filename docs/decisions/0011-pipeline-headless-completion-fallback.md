# ADR-0011: Service-Side Headless Pipeline-Completion Fallback

**Status:** Accepted
**Subsystem:** service, state
**Date:** 2026-07-12
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0009.** That ADR owns the serialized run-queue and
> the deferred-insertion (`committed=false`) semantics; this ADR adds the
> service-side terminal dispatch that feeds a `PipelineDone(committed=false)`
> into that machinery when no IME is bound.

## Research

- **`PipelineCallbackBridge.dispatch` drops all callbacks with no delegate**
  (`core/PipelineCallbackBridge.kt`, pre-change lines 63-69): logs WARN and
  returns. For the two *terminal* callbacks this stranded the in-memory
  `state.pipeline` FSM in `Running` forever while the DB row was already
  `COMPLETED`. Documented as an open gap in
  `docs/research/2026-07-09 - external-dictation-entry-points.md` §6.1 +
  decision-log D3 ("deferred — the IME completion handler is the most intricate
  code in the app; a partial duplicate risks drift").
- **Callback-before-finalize ordering (verified):**
  `PipelineOrchestrator` invokes `callback.onPipelineCompleted(text, source)`
  BEFORE `sessionManager.finalizeCompleted(sid)` (main path
  `PipelineOrchestrator.kt:329-332`; resume path `:517`; body `:1222`). At
  callback time the transcription + step rows are already persisted, so
  `SessionManager.getFinalOutput(sessionId)`
  (`core/SessionManager.kt:456-469`) already returns the correct text;
  the terminal `COMPLETED` status lands immediately after on the SAME
  pipeline thread, independent of any IME.
- **`getFinalOutput` is the only reliable text source.** Its fallback chain
  is current-step-chain output → transcription text → denormalized
  `finalOutputText`. The raw `final_output_text` column is historically
  empty for dictations (resend bug fixed in commit 9637fc3); the cold-boot
  resend seed already routes through `getFinalOutput` for exactly this
  reason (`DictatePipelineService.kt:734-747`).
- **`committed=false` path already exists** end-to-end: `PipelineDone`
  carries a `committed` flag (`state/Action.kt:446-450`); `false` makes the
  `PipelineModule` reducer emit `Effect.AddPendingInsertSession`
  (`state/modules/PipelineModule.kt:411-435`) → `PendingSessionsAction.AddOne`
  (dedup by sessionId, `state/modules/PendingSessionsModule.kt:84-99`) → a
  "Tap to paste" InfoBar pending part. It was built for the widget-host-block
  case (B3.5) and is reused verbatim here.

## Context

Dictate's pipeline runs in the foreground service (ADR-0003), independent of
the IME. External entry points (launcher alias, QS tile, app shortcut — see
the external-dictation spec) can start a dictation on a fresh process where
the keyboard is never opened, so no `PipelineCallbackBridge` delegate is ever
bound. The transcription still persists (Persistence-First → DB `COMPLETED`),
but the UI-completion callback is dropped, so `state.pipeline` never leaves
`Running`: the widget/notification show a stale "sending" state until the next
process start replays the row via `PipelineRecovery`. The external-entry-points
spec shipped this as an accepted Known Limitation (§6.1, D3) and flagged the
service-side fallback as the follow-up architecture decision. This ADR is that
follow-up.

## Decision

When a terminal pipeline callback (`onPipelineCompleted` / `onPipelineError`)
fires with **no IME delegate bound**, the service dispatches the terminal
state action itself, guarded so exactly one terminal dispatch happens per
session per process.

Three parts:

### 1. `PipelineTerminalDispatchGuard` — process-wide once-guard

`core/PipelineTerminalDispatchGuard.kt`: `fun tryConsume(sessionId: String):
Boolean` backed by `ConcurrentHashMap.newKeySet`. Returns `true` for the first
caller per `sessionId`, `false` for every subsequent caller, across all
threads (single atomic `add`). **Invariant:** exactly ONE terminal dispatch
(`PipelineDone`/`PipelineFailed`) per sessionId per process, across three
producers:

- (a) **bridge delegate-delivery** — IME bound, callback forwarded;
- (b) **bridge headless fallback** — this ADR;
- (c) **bind-reconciliation** — ADR-0011 Decision 2 (below), lands in the
  immediately-following change and shares this same guard instance.

**Delegate-delivery-consumes rule:** delivering to the delegate consumes the
guard just like the headless path. Once the IME has been handed a completion,
no later producer may fire for that session — the IME becomes the sole owner
of that terminal.

### 2. `PipelineCallbackBridge` late-bound headless sink

The bridge gains `setHeadlessTerminalSink(currentSessionIdProvider,
onCompleted, onFailed)`, wired by the service after the state orchestrator is
constructed (the sinks call `emitAction`, which does not exist at bridge
construction time). Terminal-callback behaviour, once wired:

- **`onPipelineCompleted`:** resolve `sid` via the provider.
  - `sid == null` → log + drop (no session to key the guard on; an unguarded
    delivery could double-commit if a reconciliation also runs). Guard
    untouched.
  - delegate present → `tryConsume(sid)`; on success deliver to the delegate,
    on failure log + **skip** delivery (someone already terminally dispatched
    this session — delivering would double-commit text).
  - delegate absent → `tryConsume(sid)`; on success invoke the headless
    completion sink, on failure log + drop.
- **`onPipelineError`:** symmetric — delegate present → `tryConsume` + deliver;
  absent → `tryConsume` + headless failure sink (`PipelineFailed(sid,
  errorInfoKey)`). Same guard: a session either completes or fails.
- All non-terminal callbacks (steps, finished, showResend, autoSwitch,
  audioPersisted) keep the existing drop-when-no-delegate behaviour.
- When the sink is **not yet wired** (early boot, unit tests), terminal
  callbacks fall back to the original legacy delegate-or-drop path verbatim —
  no behavioural change.

### 3. Service-side sink wiring

`DictatePipelineService.onCreate`, after orchestrator construction:

- `currentSessionIdProvider` reads the in-flight sessionId off
  `store.snapshot.pipeline` (Running / Preparing / ReprocessStaging carry it;
  Idle → `null`).
- `onCompleted` resolves text via `sessionManagerImpl.getFinalOutput(sid)`
  falling back to the callback-provided text, then
  `orchestrator.emitAction(PipelineDone(sid, text, committed = false))`.
- `onFailed` → `orchestrator.emitAction(PipelineFailed(sid, reason))`.

`emitAction` (not `dispatch`): the callback fires on the pipeline executor
thread and the orchestrator is main-confined (`Dispatchers.Main.immediate`),
so `emitAction` hops the action onto the main looper. `getFinalOutput` runs
its DB IO on the pipeline thread (which already performs the pipeline's DB
writes) — never on the main thread.

**`committed=false` semantics:** the headless fallback NEVER inserts text. It
routes the transcript through the existing deferred-insertion path so it
surfaces as a "Tap to paste" pending part. Text commit stays IME-exclusive —
only a bound IME with a live `InputConnection` can commit into an editor.

### 4. Bind-reconciliation as the covering safety net (Decision 2)

Part of this ADR's decision, landing in the immediately-following change: on
IME bind, any session that reached `COMPLETED` in the DB while its terminal
dispatch was lost is reconciled into the FSM through the SAME
`PipelineTerminalDispatchGuard`. It is the durable safety net for every
residual drop window the headless fallback cannot cover (see Failure Modes),
and reuses the guard so it can never double-fire against a delegate delivery
or a headless dispatch.

## Alternatives Considered

1. **Keep the Known Limitation (D3 status quo) — rely on `PipelineRecovery`
   replay only.** The transcript is safe in the DB and the next process start
   replays it. Rejected: within the current process the FSM stays `Running`
   forever, so the widget/notification show a stale "sending" state
   indefinitely, and queued runs (ADR-0009 `nextAfterTerminal`) never drain —
   a second queued dictation would be stranded behind the phantom-running one.

2. **Recreate the IME completion handler service-side (commit text headlessly).**
   Rejected (this was the core D3 fear): the IME handler is the most intricate
   code in the app (InputConnection selection handling, auto-enter,
   host-editor quirks). A service-side duplicate would drift, and the service
   has no `InputConnection` to commit into anyway. `committed=false` sidesteps
   the whole problem — the transcript waits as a pending part instead.

3. **Fire the terminal action unconditionally from the bridge (no guard).**
   Rejected: once the IME later binds and delivers, or the reconciliation
   runs, the same session would be dispatched twice — double-committing text
   or corrupting the FSM. The guard is what makes the three producers safe to
   coexist.

4. **A dedicated boolean/`AtomicBoolean` per session instead of a shared set.**
   Rejected: the three producers live in different objects (bridge, service
   wiring, reconciliation) with different lifetimes; a shared, process-wide,
   session-keyed guard is the only place all three can serialize against
   without threading a flag through every layer.

## Consequences

**Positive:**
- Fresh-boot external dictations complete in-process: the FSM leaves
  `Running`, the widget/notification clear, and the transcript surfaces as a
  "Tap to paste" pending part without waiting for the next process start.
- ADR-0009 queued runs drain correctly — the headless `PipelineDone` goes
  through the same `nextAfterTerminal` path, so a second queued dictation
  chain-starts instead of stranding.
- Text commit stays IME-exclusive (`committed=false`): no headless insertion,
  no duplication of the intricate IME completion handler.
- One guard makes all three terminal producers mutually exclusive per
  session, so delegate-delivery, headless fallback, and bind-reconciliation
  can coexist without double-dispatch.

**Negative:**
- One more indirection at the terminal callbacks: the bridge now branches on
  provider-wired / sid-resolvable / delegate-present / guard-consumed instead
  of a single `dispatch`. A reader tracing a completion must follow the guard
  to know which of the three producers actually fired.
- The service holds a small process-lifetime set of consumed sessionIds. At
  the real scale (a handful of dictations per process) this is negligible, but
  it is unbounded in principle — documented in the guard's KDoc with the LRU
  escape hatch.

**Failure Modes:**
- **Delegate delivered but the IME main-handler runnable is lost without
  process death.** The guard is consumed (delegate-delivery-consumes), so
  neither the headless fallback nor the reconciliation will re-fire for that
  session *in this process*. If the IME's posted completion runnable is then
  dropped (e.g. the IME view is torn down mid-post without the process dying),
  the transcript is stranded until the next process start, where
  `PipelineRecovery` replays the `COMPLETED` row. This is the accepted
  residual window — the in-process guard deliberately trades a rare
  same-process stranding for a hard no-double-commit guarantee.
- **`sid == null` with a delegate present drops a delegated terminal.** If the
  provider cannot resolve the in-flight session (e.g. the store snapshot is
  already `Idle`), the terminal callback is dropped even though a delegate is
  bound — an unguarded delivery is refused rather than risk a double-commit.
  In practice the pipeline is Running/Preparing when its own completion fires,
  so the snapshot always carries the sid; this arm is defensive.
- **`pendingFlow`/`Refresh` race (verified, benign).**
  `PendingSessionsAction.Refresh` replaces the whole pending list from a DB
  query that requires `COMPLETED` status. The headless `PipelineDone` emits
  `AddPendingInsertSession` → `AddOne` in the tiny window *before*
  `finalizeCompleted` writes the `COMPLETED` status. A `Refresh` landing in
  that window would re-query, not find the not-yet-COMPLETED row, and replace
  the list — transiently dropping the just-added part. This is currently
  unreachable in practice: `PipelineSessionRepoAdapter.pendingFlow()` returns
  `emptyFlow()` (no live Room-invalidation feed), so `Refresh` is emitted only
  by (a) service-start `PipelineRecovery` and (b) a specific `RecordingModule`
  `loadPending()` path — neither is triggered by `finalizeCompleted`, so no
  `Refresh` races the `AddOne`. **Caveat for a future maintainer:** because
  there is no live pending-flow, an in-process drop would NOT self-heal
  automatically — recovery only re-reads on the next process start or the
  `RecordingModule` refresh path. If `pendingFlow()` is ever wired to a real
  Room-invalidation Flow, re-audit this window (order the `COMPLETED` write
  before the `AddOne`, or have `loadPending` union the in-memory pending
  parts).

## References

- **Related Spec:** [External Dictation Entry Points](../research/2026-07-09 - external-dictation-entry-points.md)
  §6.1 (Known Limitation, now RESOLVED) + decision-log D3 (deferred → implemented)
- **Related ADRs:**
  - ADR-0003 — Service Foreground Pipeline Architecture (FGS host; pipeline is IME-independent)
  - ADR-0009 — Ordered Run-Queue with Serialized Execution (`committed=false`
    deferred insertion + `nextAfterTerminal` queue drain)
- Implementation:
  - `core/PipelineTerminalDispatchGuard.kt` — the once-guard
  - `core/PipelineCallbackBridge.kt` — headless terminal sink + guarded delivery
  - `core/DictatePipelineService.kt` (`onCreate`) — sink wiring
  - `state/modules/PipelineModule.kt:411-435` — `PipelineDone(committed=false)` reducer arm
  - `core/SessionManager.kt:456-469` — `getFinalOutput` fallback chain
- Test suites:
  - `app/src/test/java/net/devemperor/dictate/core/PipelineTerminalDispatchGuardTest.kt`
  - `app/src/test/java/net/devemperor/dictate/core/PipelineCallbackBridgeHeadlessTest.kt`

## Decision History

### 2026-07-12 — Initial proposal

**Trigger:** Follow-up to the external-dictation-entry-points spec (§6.1
Known Limitation, decision-log D3), which shipped the headless-completion gap
as an accepted limitation and named the service-side fallback as the pending
architecture decision.

**Before:** `PipelineCallbackBridge` dropped ALL callbacks when no IME
delegate was bound. For the two terminal callbacks this stranded
`state.pipeline` in `Running` for the rest of the process while the DB row was
already `COMPLETED`; queued runs never drained; the only recovery was the
next-process `PipelineRecovery` replay.

**After:** Terminal callbacks with no delegate dispatch a service-side
`PipelineDone(committed=false)` / `PipelineFailed` through the state
orchestrator, guarded by a process-wide `PipelineTerminalDispatchGuard` so
exactly one terminal dispatch fires per session across delegate-delivery,
headless fallback, and the upcoming bind-reconciliation. Text commit stays
IME-exclusive; the transcript surfaces as a "Tap to paste" pending part.

**Reasoning:** `committed=false` reuses the existing deferred-insertion path
and sidesteps the D3 drift fear (no duplicated IME completion handler, and the
service has no `InputConnection` anyway). The guard is what makes the three
terminal producers safe to coexist; without it a later IME bind or
reconciliation would double-dispatch. The persistence-order guarantee
(callback fires after the step/transcription rows are persisted, before
`finalizeCompleted` on the same thread) means `getFinalOutput` already returns
the correct text at callback time, so no terminal-status wait is needed.

### 2026-07-12 — Decision 2 landed (bind-reconciliation)

**Trigger:** Implementation of Decision §4 (the covering safety net named in
the initial proposal). The headless fallback closes only the "no delegate
bound when the terminal callback fires" window; the durable net for every
*residual* terminal-drop window (e.g. delegate delivered but the IME
main-handler runnable lost without process death) was still owed.

**Before:** Only two terminal producers existed (delegate-delivery + headless
fallback). A terminal callback that was delivered to the delegate but then lost
before it drove the FSM left `state.pipeline` stuck non-Idle for the rest of
the process; the guard was already consumed, so neither existing producer could
re-fire, and the only recovery was the next-process `PipelineRecovery` DB
replay.

**After:** New `state/PipelineBindReconciliation.kt`. On every IME bind
(`registerPipelineCallback(callback != null)` → `serviceScope.launch { reconcile() }`)
it reads the in-flight sessionId off `store.snapshot.pipeline`
(`Preparing`/`Running` only — `ReprocessStaging` is the expected resting state
of a completed session being re-edited and is skipped; `Idle` no-ops), loads
the session row on the IO context, and replays a terminal DB status into the
matching action through the SAME `PipelineTerminalDispatchGuard`:
`COMPLETED → PipelineDone(committed=false)` (text via `getFinalOutput`),
`FAILED → PipelineFailed(reason)`, `CANCELLED → CancelPipeline`. A non-terminal
row is a strict no-op — reconciliation never preempts a live run.

**Reasoning:** Reusing the shared guard makes bind-reconciliation the third
mutually-exclusive terminal producer, so it can never double-fire against
delegate-delivery or the headless sink. It heals the in-memory FSM per-bind and
is complementary to `PipelineRecovery` (once-per-process DB healing +
pendingSessions merge): both are idempotent — `PendingSessionsAction.AddOne`
dedups by sessionId and the guard dedups terminal dispatch — so they are safe in
any interleaving, including a reconcile that races a not-yet-finished recovery.
The `CancelPipeline` arm's `Effect.CancelPipelineJob` is a safe no-op on an
already-finished job (`JobExecutor.cancel` only flips a null-safe cancellation
token / thread interrupt), and its `Effect.NotifyCancellationHint` is acceptable
late-bind UX.
