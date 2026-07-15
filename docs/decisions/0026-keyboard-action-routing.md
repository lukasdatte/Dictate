# ADR-0026: Keyboard-Action Routing — an Exclusive Sink Router in front of the InsertionService Fassade

**Status:** Accepted
**Subsystem:** state, service
**Scope:** Project-Wide
**Date:** 2026-07-15
**Author:** Lukas + Claude (Fable 5)

> **Extends ADR-0019 (auto-send / one dispatch primitive / pending-part fallback).** ADR-0019 sends
> the *dictation text* to the PC. This ADR extends the reach to **every keyboard action** (cursor,
> backspace, enter, clipboard, text pills, emoji) — but with a different failure contract: keyboard
> actions are never buffered as pending parts.

## Plain-language summary

Before this change, PC-mode only diverted the finished dictation; every other key (cursor, backspace,
clipboard, …) still edited the invisible Android field the user was not looking at. This ADR puts a
**router** in front of the one InsertionService that already owns all local writes: in PC-mode it
sends the action to the PC **exclusively** (the Android field is left untouched), otherwise it
delegates to the local InsertionService byte-for-byte. A 500-ms send window batches bursts; a failed
batch is discarded with a single notice — never retried, never held.

## Research

Grounded in the shipped app-side implementation (block §C, `tmp/plan-keyboard-action-engine.md` (worktree `feature/keyboard-action-engine`)).
Load-bearing facts, verified in code:

- **The app was already centralised on one IC-write owner.** `InsertionService`
  (`app/.../state/insertion/InsertionService.kt`) is the sole caller of `commitText` /
  `deleteSurroundingText` / `performContextMenuAction`; the physical primitives live in injected
  collaborators. The abstraction did not need to be *created* — a router only had to be placed **in
  front** of the existing fassade.
- **The transport brings no batching (plan §4.3.1).** So the 500-ms window is engine-side: the first
  action of a burst goes immediately, and while a request is in flight the rest coalesce into one
  batch (HTTP/1.1 without pipelining allows only one request on the wire, which is also what gives the
  total order).
- **A few paths read the host field (`getExtractedText`, selection).** Those are meaningless on the
  PC; they are gated in PC-mode rather than abstracted away.

## Context

Roughly fifteen call-sites dispatch keyboard actions. Scattering a `if (pcMode) … else …` at each was
rejected (Open/Closed violation, no seam for a third target). The typed action models
(`ControlOp`, `EditAction`, `InsertionRequest`) were worth reusing rather than re-modelling.

## Decision

1. **One router, exclusive routing.** `KeyboardActionRouter : KeyboardActionSink` picks **exactly
   one** sink per submit — `PcInputSink` when `state.features.windowsAutoSendActive`, `LocalImeSink`
   otherwise. The mode is read *per submit*, so a toggle takes effect on the next keystroke.

2. **A thin hull, not a parallel hierarchy.** `KeyboardAction` wraps the existing
   `InsertionRequest` / `ControlOp` / `EditAction` (DRY). `LocalImeSink` delegates each to the same
   `InsertionService` method — so local mode is byte-identical to before, and the seam rewrite is a
   one-word diff at each call-site (via the `KeyboardActionDispatcher` facade whose method names
   mirror `InsertionService`).

3. **The dictation-text terminal keeps its own path (ADR-0019).** `onPipelineCompleted` retains its
   `WindowsAutoSend.shouldDivertToPc` weiche **with** the pending-part fallback. The router carries
   only the *live* keyboard actions, which have no pending semantics.

4. **500-ms send window, linger-only-when-busy (D5).** `PcInputCoordinator` runs on the **same**
   single-thread executor as the dictation dispatch (total order, incl. relative to `/v1/dispatch`).
   First action immediate; in-flight burst coalesced (same-direction moves fold into `count`,
   heterogeneous sequences stay an ordered batch) and flushed as one request when the response
   returns.

5. **Failure = discard + one notice + no retry (Entscheidung 4).** A failed batch and everything
   buffered behind it are dropped together with a single InfoBar notice (`WINDOWS_INPUT_FAILED`, or
   `WINDOWS_INPUT_COMPANION_OUTDATED` for a 404 — deliberately worded *not* to promise a pending
   part). A network fault opens a 3-s circuit breaker so a held cursor-swipe over a dead link
   produces one notice, not a flood. Nothing is ever re-sent (a retry would reorder against keys
   pressed in the meantime).

6. **IC-read-bound features are gated, not routed.** Selection prompt-pills, the select-all *toggle*
   and the backspace-swipe's field read have no PC meaning; in PC-mode they are disabled/degraded
   (see plan §6.2), and the router reports `Unsupported` for a control op with no PC mapping rather
   than swallowing it.

7. **The PC-mode frame (D4) is not a separate ADR.** A small `PcModeFrameRenderer` (side-channel
   pattern) sets a purple foreground frame on the keyboard root; it touches no theme/accent axis.

## Alternatives considered

- **Dual execution (Android *and* PC).** Rejected (User-Entscheidung 1): divergent cursor states are
  un-synchronisable and incomprehensible.
- **Per-call-site `if (pcMode)`.** Rejected: scattered conditions, no seam for a third target,
  Open/Closed violation.
- **A fixed 500-ms collect-before-send window.** Rejected (D5): it would add 500 ms latency to every
  single keystroke. Linger-only-when-busy gives batching for fast bursts and a failure horizon
  without taxing the common case.

## Consequences

### Positive
- One routing point; a third target (second PC, tablet) is one more sink, not another branch.
- Local mode provably unchanged (`LocalImeSink` delegates to the same service; existing suites green).
- JVM-testable routing, send window and coalescing (pure Kotlin, injected clock + executor).

### Negative
- The PC path is asynchronous, so a routed call returns `SubmitResult` (not `InsertionResult`); a
  call-site that inspects the outcome must handle `SubmitResult.Done`.
- Not every keyboard action is verifiable off-device — the manual checklist (plan §12) is the
  acceptance for the on-device behaviour.

### Failure modes
- **PC unreachable / timeout.** One `WINDOWS_INPUT_FAILED` notice, buffer discarded, 3-s circuit;
  no pending part, nothing survives to a reconnect (the deliberate contrast to the dictation path).
- **Grapheme semantics differ.** Android deletes grapheme-aware; on the PC `BACKSPACE` deletes as the
  target app does (an emoji may take two). Accepted, documented — the PC field cannot be read.
- **Word-selection variance per PC app (D1).** Accepted; the wire command exists, the outcome is the
  target app's convention.

## References

- Plan: `tmp/plan-keyboard-action-engine.md` (worktree `feature/keyboard-action-engine`) (§4, §6, §7, §9.2, D1/D3/D4/D5) — motivated and
  implemented here.
- Sibling: [ADR-0025](0025-input-command-protocol.md) *Input-Command Protocol* (the wire these actions travel on).
- Extends: ADR-0019 (and cooperates with ADR-0011/0013 through it).
- Code: `app/.../state/insertion/{KeyboardAction,KeyboardActionRouter,LocalImeSink,KeyboardActionDispatcher}.kt`,
  `app/.../windows/{PcInputSink,PcInputCoordinator,PcInputCommandMapper,PcInputOutcome}.kt`,
  `app/.../state/render/PcModeFrameRenderer.kt`, seam wiring in `core/DictateInputMethodService.java`
  + `core/DictatePipelineService.kt`.

## Decision History

- **2026-07-15 — Proposed (plan-scoped).** Authored alongside the keyboard-action-engine plan;
  core implemented in blocks §C.1/§C.2/§E (green: `KeyboardActionRouterTest`, `LocalImeSinkTest`,
  `PcInputCommandMapperTest`, `PcInputCoordinatorTest`, `PcModeFrameRendererTest`, plus the rewired
  `KeyboardInputModuleTest`/`EmojiControllerTest`/`SpecialTouchHandlerInstallerTest`). Remaining
  call-site tail (text-pill/resend inserts, backspace-swipe PC word selection §4.5, select-all
  stateless + selection gating §6.2, overlay-frame parity) tracked in the plan. Awaiting promotion.
- **2026-07-15 — Promoted + Accepted.** Assigned ADR-0026, moved to `docs/decisions/`; the call-site
  tail (§4.5, §6.2, resend/history, overlay parity) landed before the merge — see the plan.
