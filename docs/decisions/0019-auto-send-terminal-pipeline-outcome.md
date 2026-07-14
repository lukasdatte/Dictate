# ADR-0019: Auto-Send as a Third Terminal Pipeline Outcome — Two Producers, One Dispatch Primitive, Pending-Part Fallback

**Status:** Accepted
**Subsystem:** state, service
**Scope:** Project-Wide
**Date:** 2026-07-14
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0011, ADR-0013.** ADR-0011 owns the headless completion sink, the
> terminal-dispatch guard, and the pending-part fallback; ADR-0013 owns the `PipelineDone`
> terminal contract and the review panel. This ADR adds a *third* terminal outcome —
> delivery to a paired PC — on top of both, reusing their mechanisms rather than duplicating
> them.

## Research

This decision is grounded in the shipped, green implementation of Block 3 of the
windows-dispatch work package (chunks C12–C14). The plan sketch for this ADR is
`tmp/plan-windows-dispatch.md:486` (the ADR-0019 row); the full derivation with the P1/P2/D1
sharpening passes is `tmp/plan-windows-dispatch-3-android.md` §3–§5. The load-bearing facts,
verified in code:

- **One primitive, three triggers.** `WindowsDispatchCoordinator.dispatch(...)`
  (`app/src/main/java/net/devemperor/dictate/windows/WindowsDispatchCoordinator.kt:56-96`) is the
  single method that starts a Windows dispatch. It is called from the IME seam
  (`core/DictateInputMethodService.java:4850`), the headless sink
  (`core/DictatePipelineService.kt:932`), and the history row button
  (`core/DictateInputMethodService.java:6398`). Neither call site owns an HTTP client, an executor,
  or a result branch — there is physically one instance, built in the service
  (`core/DictatePipelineService.kt:831-849`) and handed to the IME through the existing binder
  (`DictateInputMethodService.java:816`).

- **The FSM goes Idle; the in-flight state lives in a dedicated axis.** The `PipelineDone`
  follow-up selector checks `awaitingDispatch` *before* `committed`
  (`state/modules/PipelineModule.kt:423-431`) and emits neither `MarkSessionInserted` nor
  `AddPendingInsertSession`; `nextAfterTerminal` still runs (`PipelineModule.kt:400`), so the
  ADR-0009 run-queue drains. The in-flight session lives only in `WindowsDispatchState`
  (`state/modules/WindowsDispatchModule.kt:11-34`).

- **The acknowledge rule is state-dependent, driven by the axis's own flag.** The `Succeeded`
  arm reads `InFlightDispatch.surfacedAsPending` — not a cross-axis read of `pendingSessions` — and
  chooses `DismissPendingPart` vs `MarkAcknowledged` deterministically
  (`WindowsDispatchModule.kt:108-133`). The flag is set by the teardown cascade's `MarkSurfaced`
  (`WindowsDispatchModule.kt:205-223`) or by a re-sent still-pending history row's
  `Started(surfacedAsPending = true)`.

- **One audit authority.** `SessionManager.logTextInsertion` is the single writer of
  `text_insertions` (`core/SessionManager.kt:651-685`); the coordinator's audit callback calls it
  with `method = WINDOWS_DISPATCH` and `targetDeviceId` (`core/DictatePipelineService.kt:840-847`).

Regression tests pin every arm: `state/WindowsDispatchModuleTest.kt` (P1/P2 reducer cases),
`state/WindowsAutoSendBothProducersTest.kt` (the parameterized two-producer equivalence + the
bind-reconciliation no-op), `windows/WindowsAutoSendTest.kt` (the predicate),
`windows/WindowsDispatchCoordinatorTest.kt`, `state/WindowsDispatchPipelineDoneTest.kt`
(`awaitingDispatch` precedence), `state/PipelineBindReconciliationTest.kt`, and
`history/KeyboardHistoryAdapterBindTest.kt` (the now-visible send slot).

## Context

Dictate is an Android keyboard (IME). Its transcription/rewording pipeline ends by delivering the
final text somewhere. Until now there were exactly two terminal destinations: the text was
*committed* into the Android host field (the app the user is typing into), or — when the pipeline
finished with no keyboard bound (widget / Quick-Settings-tile dictation) — it was surfaced as a
**pending part**, a "Tap to paste" item the user could insert later (ADR-0011).

The windows-dispatch feature adds a third destination: a paired Windows PC on the user's Tailscale
network receives the text over HTTP and pastes it into whatever window has focus there. When the
user turns on the *auto-send* toggle, a completed dictation should go to the PC instead of the
Android field.

The hard part is that this touches the single most invariant-dense point in the codebase — the
terminal pipeline path — where three hotfix invariants and three ADR contracts already meet:

- **Exactly-once delivery** per session per process (`PipelineTerminalDispatchGuard`, ADR-0011).
- **`PipelineDone` must always fire** on the terminal path, or the keyboard's FSM (finite-state
  machine — the recording/pipeline state model) jams and the next dictation bounces off a stale
  state.
- **The run-queue must drain** (`nextAfterTerminal`, ADR-0009), or queued dictations stall.
- **Bind-reconciliation** (ADR-0011 Decision 2) must not, on a late IME bind, re-surface a session
  whose text is already on its way to the PC.

A naive implementation would add auto-send as a fourth terminal producer with its own HTTP client,
its own acknowledge path, and its own guard interaction. That is exactly what breaks the four
invariants above. This ADR records how auto-send slots in *behind* the existing machinery instead.

Two terms used throughout: a **guard** is the `PipelineTerminalDispatchGuard`, a per-session
one-shot token that ensures only one producer resolves a given session's terminal outcome. A
**cascade** is a cross-module reaction (ADR-0002) — when one axis of state changes, another axis
emits follow-up actions; here, the IME view disappearing triggers the surfacing of in-flight text
as a pending part.

## Decision

**Auto-send is a third terminal pipeline outcome, produced by two call sites that share one
dispatch primitive, with the existing pending-part path as the universal fallback.** No fourth
terminal producer, no second commit path, no second acknowledge channel.

### Scope of this Convention

This is a Project-Wide convention because it constrains the terminal pipeline contract that every
subsystem's completion path funnels through (`state`, `service`). It applies to **every** way a
dictation can reach the PC. What is exempt: the toggle-off path is byte-for-byte unchanged (a
completed dictation still commits to the host or surfaces as a pending part exactly as before), and
no non-auto-send feature may add a terminal producer without the same guard-behind discipline
described here.

### Two producers, one primitive

Auto-send has **two terminal producers**:

1. The **IME seam** — the else-branch of `onPipelineCompleted`
   (`DictateInputMethodService.java:4819-4852`), when a keyboard is bound.
2. The **ADR-0011 headless sink** — `DictatePipelineService`'s terminal completion sink
   (`DictatePipelineService.kt:893+`, dispatch at `:913-932`), when no IME delegate is bound
   (widget / QS-tile dictation). Conceptually this is the *strongest* auto-send case: "I dictate
   from the widget, the text should land on the PC."

Both check the **same predicate** — `WindowsAutoSend.shouldAutoSend(sp)`
(`windows/WindowsAutoSend.kt:21-22`), which requires *both* the toggle on *and* a paired target —
and both call the **same primitive**, `WindowsDispatchCoordinator.dispatch(...)`. The coordinator
lives in the **service**, which outlives every IME teardown, and owns threading (its own dedicated
single-thread executor, never the ADR-0009 `JobExecutor`), the blocking HTTP call, the audit write,
the ADR-0020 sync trigger, and the state feedback. **Duplicating the dispatch logic is forbidden**:
the two paths would silently drift apart. A parameterized test
(`WindowsAutoSendBothProducersTest.kt`) drives the same assertion set over both producers so a
missed update fails mechanically.

### Exactly-once, without a fourth producer

The `PipelineTerminalDispatchGuard` is consumed on **both** paths by the `PipelineCallbackBridge`
*before* anything Windows-specific runs. Auto-send hooks in *behind* the already-won guard; it adds
no new producer. The dispatch *result* comes back through a **non-terminal, guard-free**
`WindowsDispatchAction` family (`Started` / `Succeeded` / `Failed` / `MarkSurfaced` /
`DismissNotice` / `OpenPairing`) — mirroring why ADR-0013's `onReviewTurnCompleted` is non-terminal:
the terminal guard fires *once* per session, and the dispatch outcome arrives *after* that fire.

**Bind-reconciliation is doubly excluded.** (1) The guard is already consumed, so
`reconcile()`'s `guard.tryConsume(sid)` returns `false` → strict no-op. (2)
`PipelineDone(awaitingDispatch=true)` runs `nextAfterTerminal`, so the FSM is `Idle` by the time the
action reduces, and reconciliation acts only on `Preparing`/`Running`.

**Binding corollary:** `awaitingDispatch` must **not** hold the FSM non-Idle. A tempting
"improvement" — keeping the FSM in `Running` during the dispatch so the in-flight state is
"visible" — would reopen exactly the window in which the session is `COMPLETED` in the DB while the
dispatch is still running, and reconciliation could re-surface a pending part while the text is
already on its way to the PC. The in-flight state therefore lives **only** in the `windowsDispatch`
axis (`WindowsDispatchModule.kt:24-30`).

### The new `PipelineDone` arm

`PipelineDone` gains an `awaitingDispatch` flag, parallel to ADR-0013's `heldForReview`. The
follow-up selector (`PipelineModule.kt:423-431`) checks `awaitingDispatch` first and emits
**neither** `MarkSessionInserted` **nor** `AddPendingInsertSession`; the queue still drains
(ADR-0009). `heldForReview && awaitingDispatch` is impossible (review happens *before* sending) and
is pinned by a reducer test. Note that `committed = false` and `awaitingDispatch = true` coexist by
design: `committed` means "written into the Android host field", which auto-send never does.

### The state-dependent acknowledge rule

A success does **not** unconditionally acknowledge. The `Succeeded` arm
(`WindowsDispatchModule.kt:108-133`) reads `InFlightDispatch.surfacedAsPending` — a flag on its
**own** axis, never a cross-axis read of `pendingSessions`:

- **If a pending part already exists** (the teardown cascade surfaced it via `MarkSurfaced`, or a
  re-sent still-pending history row started with `surfacedAsPending = true`): acknowledge via
  `PendingSessionsAction.Dismiss` → `PersistDismissal` → `markInserted`, which removes the part *at
  the same time*.
- **Otherwise:** acknowledge via `Effect.MarkAcknowledged` → `markInserted`.

**Never `AcceptAndInsert`.** That action is intercepted by the IME side-channel
(`DictateInputMethodService.java`) and *additionally* commits the text into the Android field →
double delivery. Without the case split, a success-after-teardown leaves a "Tap to paste" ghost:
the cascade created a pending part, the HTTP-200 arrives, `markInserted` sets `inserted_at`, but the
in-memory pending part is never removed because `pendingFlow()` is `emptyFlow()` (ADR-0011) — there
is no DB-driven refresh of the `pendingSessions` axis. The user then pastes the text a second time
into the Android field. This is a real double-delivery bug, not cosmetic; the P1 sharpening pass
found and fixed it, and `WindowsDispatchModuleTest.kt` pins it.

Why the flag replaces a cross-axis read: reading `ctx.global.pendingSessions` is permitted by the
reducer contract but is **timing-dependent** — it assumes the cascade's `AddOne` was already reduced
when `Succeeded` arrives. With a second producer (the headless sink) firing off the pipeline
executor thread, that assumption is not worth defending. Keeping the fact in the axis makes the
resolution deterministic and identical on both paths.

### Failure and CLIPBOARD_ONLY

- **Failure or teardown** → the existing ADR-0011 pending part (`Effect.SurfacePendingPart` →
  `PendingSessionsAction.AddOne`), deduplicated by sessionId, so there is never a second part
  (`WindowsDispatchModule.kt:135-151`).
- **`CLIPBOARD_ONLY`** (the companion could put the text on the PC's clipboard but not type it —
  no focused window, UIPI block, or a Linux/macOS companion; ADR-0018) counts as **delivered**:
  acknowledge, **no** pending part, but raise a dismissible **INFO** InfoBar notice
  (`DispatchNotice.ClipboardOnly`, `WindowsDispatchModule.kt:124-129`). An **InfoBar** is Dictate's
  state-derived notification strip (ADR-0006). Offering the text as a pending part too would put the
  same text in two places → the very double delivery P1 removes; booking it as a silent full success
  would be a lie the user misreads as "the feature is broken".

Every successful dispatch writes an audit row: `insertion_method = WINDOWS_DISPATCH` +
`target_device_id` (schema v10 / ADR-0018 companion column).

### Three decisions made during implementation (Block 3b-1)

**(a) `OpenPairing` is a fourth guard-free action.** A `WINDOWS_UNAUTHORIZED` (HTTP 401 — the
device secret was rejected) surfaces an ERROR notice whose confirm action is
`WindowsDispatchAction.OpenPairing`. The reducer only clears the notice
(`WindowsDispatchModule.kt:158-159`); the IME side-channel launches `WindowsPairingActivity`
(`DictateInputMethodService.java:5666-5676`). It is non-terminal and touches no guard — the same
discipline as the rest of the `WindowsDispatchAction` family.

**(b) The audit authority is `SessionManager.logTextInsertion`, not `InsertionAudit.record`.**
`InsertionAudit.record` needs an `InputConnection`/`EditorInfo`, which is absent in the headless
case. `logTextInsertion` (`SessionManager.kt:660-685`) is the single `text_insertions` write
authority; the coordinator's audit callback routes through it
(`DictatePipelineService.kt:840-847`). One audit authority, three triggers (IME seam, headless
sink, history row). A Windows dispatch passes `targetDeviceId` and leaves `targetAppPackage` null;
every host insertion is the mirror.

**(c) The review-panel "Insert" routes to the PC with `acknowledgeOnSuccess = false`.** When the
review panel is open in auto-send mode, its Insert button sends to the PC instead of committing to
the host (`DictateInputMethodService.java:5085-5093`). But `ReviewPanelAction.Insert` *already*
acknowledges (closes the panel + `markInserted`), so the coordinator must **not** re-acknowledge —
otherwise `markInserted` runs twice. The button label reads "Send to PC" in auto-send mode
(`ReviewPanelRenderer.kt:68`, `strings.xml:598`). Clean rule: **review decides *whether* text is
committed; auto-send decides *where*** — two orthogonal axes, no cross-product of special cases.

## Alternatives Considered

1. **Add auto-send as a fourth terminal producer with its own guard interaction.** It would have
   meant a dedicated dispatch path that consumes (or races for) the `PipelineTerminalDispatchGuard`
   itself. Rejected: it breaks exactly-once (two producers competing for one session's guard) and
   forces bind-reconciliation to reason about a new terminal state. Hooking *behind* the
   already-consumed guard keeps the three existing producers pairwise-exclusive and adds nothing to
   reconcile.

2. **Keep the FSM in `Running` during the dispatch to "show" the in-flight state.** Natural, since
   an in-flight network call feels like ongoing work. Rejected: it reopens the reconciliation window
   (session `COMPLETED` in DB, FSM still `Running`) and stalls the ADR-0009 queue. A dedicated
   `windowsDispatch` axis carries the in-flight state without touching the FSM.

3. **Read `pendingSessions` cross-axis in the `Succeeded` arm** to decide dismiss-vs-acknowledge.
   Correct by the reducer contract, and how the first design pass did it. Rejected after the second
   producer landed: it assumes the cascade's `AddOne` was reduced before `Succeeded` arrives, which
   is not guaranteed when the headless sink fires off the pipeline thread. The `surfacedAsPending`
   flag on the axis makes it deterministic and identical on both paths.

4. **Acknowledge a success with `AcceptAndInsert` (uniform with the pending-part accept path).**
   Tempting because `Dismiss` and `AcceptAndInsert` are state-identical inside
   `PendingSessionsModule`. Rejected: `AcceptAndInsert` is intercepted by the IME side-channel and
   commits the text into the Android field — double delivery. `Dismiss` removes the part and
   acknowledges without any host commit.

5. **Overload `target_app_package` with a `windows:<deviceId>` prefix** instead of a new audit
   column. Rejected (recorded in ADR-0018's companion schema work): the column means "the Android
   package written into"; a prefix hack corrupts every future per-app usage query and is exactly the
   silent semantic drift `docs/DATABASE-PATTERNS.md` forbids. A nullable `target_device_id` column,
   riding the v10 recreate for free, is the clean choice.

6. **Block following sends behind a failed send** to preserve global dictation order. Rejected: it
   turns a broken PC into a total dictation standstill (see Consequences → Negative, F-5).

## Consequences

**Positive:**

- **One dispatch code path, three triggers.** IME seam, headless sink, and history row all funnel
  through `WindowsDispatchCoordinator.dispatch(...)`. Auditing, threading, sync, and error mapping
  cannot drift per-trigger because they exist once.
- **The toggle-off path is provably unchanged.** Byte-for-byte identical behaviour when auto-send
  is off, pinned by regression tests — the feature is additive at the most dangerous point in the
  codebase.
- **All four existing invariants hold** (exactly-once, `PipelineDone` always fires, queue drains,
  bind-reconciliation is a no-op) without new machinery — auto-send reuses the guard, the queue
  drain, and the pending-part fallback rather than reinventing them.
- **A dispatch survives IME teardown.** The coordinator lives in the foreground service, so the
  keyboard disappearing mid-send does not abort the send; the teardown cascade only has to make the
  text recoverable.
- **Serviceability.** "The text is gone — where did it go?" is answerable from one row:
  `SELECT insertion_method, target_device_id, timestamp FROM text_insertions WHERE session_id = ?`.

**Negative:**

- **F-5 (accepted): under mixed success/failure, the global dictation order (ADR-0009) splits into
  two streams.** A failed session A becomes a pending part in the Android field; a succeeding
  session B lands on the PC. Order holds *within* each stream; there is no longer a single global
  order across both. Accepted because a failed send is a *visible* exception state (InfoBar error +
  pending part), not a silent reorder — the user decides whether to "Tap to paste" into the phone or
  re-send from the history row. The alternative (blocking following sends behind a failure) would
  translate a broken PC into a total dictation standstill.
- **A dedicated axis to trace.** The in-flight state lives in `windowsDispatch`, separate from the
  pipeline FSM. A reader debugging "why is this session terminal but not acknowledged?" must know to
  look at the axis, not the FSM — the FSM is already `Idle`.
- **The `surfacedAsPending` flag duplicates a fact** that also lives (transiently) in the
  `pendingSessions` axis. The duplication is deliberate (determinism over normalization), but it is
  a fact maintained in two places and must be kept consistent by the cascade emitting both `AddOne`
  and `MarkSurfaced`.

**Failure Modes:**

- **Process death mid-dispatch surfaces the text once, possibly redundantly.** If the process dies
  while the HTTP call is in flight, the session is `COMPLETED` / `inserted_at NULL` /
  `final_output_text` written in-transaction (ADR-0013 §3), so cold-boot `findPendingInsertion`
  surfaces it as a pending part — the same durable net as every other headless completion. The sharp
  edge: a text that *reached* the PC but whose HTTP-200 was never processed appears once as a
  doubly-offered part. This is conservative and intended — one extra part beats a lost text — but an
  on-call reading a "text arrived on PC *and* is pending on phone" report should recognise it as this
  case, not a bug.
- **The acknowledge channel is `Dismiss`, never `AcceptAndInsert` — and the two are
  state-identical inside `PendingSessionsModule`.** A future editor "simplifying" the reducer to use
  `AcceptAndInsert` (or adding a second hand-written acknowledge branch at the history-row seam)
  silently reintroduces the double-delivery bug, because only the IME side-channel distinguishes
  them. The comment in `WindowsDispatchModule.kt:51-58` guards this; treat it as load-bearing.
- **The cascade never fires in the pure headless case** (no IME view was ever visible → no
  `true→false` edge). That is correct — the durable net there is cold-boot `findPendingInsertion`,
  not the cascade — but a reader expecting the cascade to run on every completion will misread the
  headless path.
- **`awaitingDispatch` must precede `committed` in the follow-up selector.** If a refactor reorders
  the `when` in `PipelineModule.kt:423-431` so `committed` is checked first, an auto-send completion
  (`committed = false`) would fall through to `AddPendingInsertSession` and create a spurious pending
  part alongside the dispatch. `WindowsDispatchPipelineDoneTest.kt` pins the precedence.

## References

- **Related Plan:** `tmp/plan-windows-dispatch.md` §3 (ADR sketch, line 486) and
  `tmp/plan-windows-dispatch-3-android.md` §3–§5 (domain, state axis, reducer, both producers,
  review×auto-send, per-row send) — the windows-dispatch plan (pending archival; the archive step
  will rewrite this to `docs/plans/YYYY-MM-DD - windows-dispatch/`).
- **Related ADRs:**
  - **ADR-0011** — the headless sink's third mode; the `PipelineTerminalDispatchGuard`; the
    pending-part fallback and the bind-reconciliation double-exclusion. This ADR adds the fourth
    pending-part producer (dispatch failure) behind the already-consumed guard.
  - **ADR-0013** — `PipelineDone`'s third terminal outcome beside `committed`/`heldForReview`;
    the review-panel Insert routes to the PC in auto-send mode (`acknowledgeOnSuccess = false`).
  - **ADR-0014** — the per-row "Send to Windows" button: the reserved GONE slot, now conditionally
    visible when a PC is paired.
  - **ADR-0009** — `nextAfterTerminal` queue drain; the source of the F-5 order-splitting trade-off.
  - **ADR-0002** — the teardown cross-module cascade this reuses to surface in-flight text.
  - **ADR-0006** — the state-derived InfoBar the `CLIPBOARD_ONLY` and error notices flow through.
  - **ADR-0017** — the dispatch client transport and the `WINDOWS_UNAUTHORIZED` → re-pair flow
    (`OpenPairing`).
  - **ADR-0018** — `CLIPBOARD_ONLY` / `TYPED_CTRL_V` / `FAILED` semantics and the
    `target_device_id` companion column.
  - **ADR-0020** — the lazy sync trigger that fires after a successful dispatch (in the
    coordinator's `Delivered` branch, all three triggers).
- **Implementation:**
  - `app/src/main/java/net/devemperor/dictate/windows/WindowsDispatchCoordinator.kt` — the shared
    primitive.
  - `.../windows/WindowsDispatchService.kt`, `.../windows/WindowsAutoSend.kt`,
    `.../windows/SessionEntityMapper.kt` — send logic, the predicate, the wire mapper.
  - `app/src/main/java/net/devemperor/dictate/state/modules/WindowsDispatchModule.kt` — the axis
    reducer (P1/P2, teardown cascade, `OpenPairing`).
  - `.../state/modules/PipelineModule.kt:423-431` — the `awaitingDispatch` arm.
  - `core/DictateInputMethodService.java:4819-4852, 5085-5093, 5666-5676, 6391-6400` — the IME seam,
    review Insert, `OpenPairing` side-channel, history row send.
  - `core/DictatePipelineService.kt:831-849, 893-932` — coordinator construction, audit wiring,
    the headless sink.
  - `core/SessionManager.kt:660-685` — the single `text_insertions` write authority.
- **Test suites:** `state/WindowsDispatchModuleTest.kt`, `state/WindowsAutoSendBothProducersTest.kt`,
  `windows/WindowsAutoSendTest.kt`, `windows/WindowsDispatchCoordinatorTest.kt`,
  `state/WindowsDispatchPipelineDoneTest.kt`, `state/PipelineBindReconciliationTest.kt`,
  `history/KeyboardHistoryAdapterBindTest.kt`.

## Decision History

### 2026-07-14 — Initial proposal

**Trigger:** Implementation of the windows-dispatch work package (Block 3, chunks C12–C14) reached
the terminal pipeline path, where auto-send-to-PC had to slot in beside the two existing terminal
outcomes (host commit / pending part) without breaking exactly-once, the always-fires `PipelineDone`
contract, the ADR-0009 queue drain, or bind-reconciliation. The design went through three sharpening
passes (P1 = the ghost-pending double-delivery bug, P2 = `CLIPBOARD_ONLY` semantics, D1 = the second
headless producer) plus three implementation-time refinements (`OpenPairing`, the
`logTextInsertion` audit authority, the review-Insert `acknowledgeOnSuccess = false` routing).

**Before:** The pipeline had two terminal outcomes — commit into the Android host field, or surface
as a pending part (ADR-0011). There was no notion of delivering a dictation off-device, no
`windowsDispatch` axis, no `awaitingDispatch` flag on `PipelineDone`, and `text_insertions` recorded
only host insertions.

**After:** Auto-send is a third terminal outcome. Two producers (the IME seam and the headless
sink) share one dispatch primitive owned by the service; `PipelineDone(awaitingDispatch=true)` drains
the queue and leaves the FSM `Idle` while a dedicated `windowsDispatch` axis carries the in-flight
session; success acknowledges state-dependently (`Dismiss` when a pending part exists, else
`MarkAcknowledged`, never `AcceptAndInsert`); failure or teardown falls back to the existing
pending-part path; `CLIPBOARD_ONLY` acknowledges with an INFO notice and no part; audit rows carry
`insertion_method = WINDOWS_DISPATCH` + `target_device_id` through the single `logTextInsertion`
authority. The whole feature is a no-op when the toggle is off.

**Reasoning:** Hooking auto-send *behind* the already-consumed terminal guard — reusing the queue
drain, the pending-part fallback, and the teardown cascade — keeps all four existing invariants
intact with no new terminal producer, no second commit path, and no second acknowledge channel. The
axis-local `surfacedAsPending` flag was chosen over a cross-axis read because the second producer
fires off the pipeline thread, making any "has the cascade reduced yet?" assumption a race. The
alternatives (a fourth producer, a `Running` FSM during dispatch, `AcceptAndInsert` acknowledge,
blocking sends behind a failure) each reintroduced one of the invariants this design preserves.

### 2026-07-14 — Documented the review×auto-send process-death recovery exception

**Trigger:** The windows-dispatch code-quality review (post-implementation gate) found that the
Failure Modes bullet above ("Process death mid-dispatch surfaces the text once, possibly
redundantly") reads as universal across all producers, but one path does not hold it: the
review-panel **"Insert" → PC** route. This entry documents the exception honestly; the ADR body is
unchanged (Accepted → append-only).

**Before:** The Failure Modes section claimed the process-death safety net ("one extra part beats a
lost text", `inserted_at` stays NULL until `Succeeded`) without naming the one path where it does
not apply.

**After:** The exception is recorded. The review-panel Insert path acknowledges **eagerly**:
`onReviewInsertClicked` (`DictateInputMethodService.java:5085`) dispatches `ReviewPanelAction.Insert`,
which runs `markInserted` (stamps `inserted_at`) **before** the async PC send, so the coordinator
runs with `acknowledgeOnSuccess = false` to avoid a double `markInserted` (§4.2(c) — "review decides
*whether*, auto-send decides *where*"). Consequence: on a send **failure**,
`WindowsDispatchModule.Failed(surfacedAsPending = false)` surfaces the text only as an **in-memory**
pending part; because `inserted_at` is already set, cold-boot `findPendingInsertion` (which requires
`inserted_at IS NULL`) does **not** recover it if the process dies in the window between the eager
acknowledge and the dispatch resolution. The three other producers (IME seam, headless sink, history
row) keep `inserted_at` NULL until `Succeeded`, so their process-death net holds — **only this path
is strictly weaker**. **No data loss:** the text persists as `final_output_text` in the DB and is
re-sendable from the history row's "Send to Windows". Sharp-edge trigger: the review panel is open in
auto-send mode **AND** the PC send fails **AND** the process dies in that narrow window.

**Reasoning:** The edge is narrow and lossless, so documenting it honestly now (option a) is the
right closeout. The durable fix (option b, deferred) is genuine state-machine surgery spanning
ADR-0013 (review panel) and this ADR — **defer the review acknowledge until the dispatch resolves**
(an Insert-without-ack variant) so this path gains the same `inserted_at IS NULL`-until-`Succeeded`
guarantee as the other three producers. It is worth its own careful change, not a review-closeout
patch. Tracked in `docs/architecture/windows-dispatch/README.md` §4 (Information Gaps).

### 2026-07-14 — Auto-send diversion is source-aware (STATIC_PROMPT stays local)

**Trigger:** A user-reported regression: while a recording is active, long-pressing a text-only
("pure text") pill in the top pill row used to insert the pill's literal text 1:1 into the host
field; it stopped working once auto-send + pairing were active. Git archaeology pinned it to the
SEAM-1 wiring (`27b91b3`, this ADR's realization).

**Before:** The IME terminal branch in `onPipelineCompleted`
(`DictateInputMethodService.java`) gated the divert-to-PC purely on `isWindowsAutoSendActive()`
(toggle on AND paired) — it did **not** inspect the completion's `InsertionSource`. A long-pressed
text-only pill produces a `STATIC_PROMPT` completion (a static `[...]` response, no AI call). With
auto-send active it was funneled through the same divert branch as a dictation transcript and sent
to the PC instead of committed locally; because that branch also skips the pending-part fallback,
the pill's text appeared **nowhere** on the phone.

**After:** The gate is `WindowsAutoSend.shouldDivertToPc(source, sp)` = `shouldAutoSend(sp)` AND
`source` is a **dictation output** (`TRANSCRIPTION` / `REWORDING` / `QUEUED_PROMPT` /
`PENDING_PART`). `STATIC_PROMPT` is never diverted — it falls through to the host commit and is
inserted 1:1, matching the user's expectation ("long-press just pastes the pill"). The decision
lives in the same single `WindowsAutoSend` gate both auto-send producers read, so the classification
cannot drift between them; an exhaustive `when` (no `else`) forces any new `InsertionSource` to be
classified deliberately. The review-panel Insert→PC route (`onReviewInsertClicked`) is unchanged: a
reviewed dictation output IS dictation, and review decides *whether* / auto-send decides *where*
(§4.2). Standalone-prompt `REWORDING` results are intentionally left divertible (no new signal
introduced — the reported regression is specifically about `STATIC_PROMPT`). Guarded by
`WindowsAutoSendTest` (STATIC_PROMPT stays local while auto-send active; dictation sources divert).

**Reasoning:** Auto-send is conceptually "dictation → PC", so sharpening the *one* routing gate on
`InsertionSource` fixes the regression at the source of truth rather than adding an ad-hoc check in
the IME. Excluding only `STATIC_PROMPT` (vs. also excluding standalone `REWORDING`) is the precise,
non-speculative fix: `source` alone cannot distinguish a standalone-prompt `REWORDING` from a
dictation+rewording `REWORDING`, and the reported break is unambiguously the `STATIC_PROMPT` pill —
so no extra origin signal is warranted yet.
