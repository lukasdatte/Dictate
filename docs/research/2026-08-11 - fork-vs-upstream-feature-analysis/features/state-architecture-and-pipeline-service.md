---
date: 2026-08-11
author: Lukas + Claude (analysis session)
status: Research
context: Fork-vs-upstream analysis of the fork's state architecture (modular store, foreground pipeline service, render backends, info-bar, single write owner) — what we built, what Dictate Keyboard 5.3 has, and whether the architecture is still worth pursuing.
related-adrs: ADR-0001, ADR-0002, ADR-0003, ADR-0004, ADR-0005 (superseded), ADR-0006, ADR-0008, ADR-0010, ADR-0022, ADR-0026, ADR-0027
---

# State Architecture & Pipeline Service — Fork vs. Upstream Analysis

This document compares *architectures*, not a user feature. Over the May–July 2026 waves the fork
replaced upstream 3.2's single God-Class IME service with five interlocking concerns: a Redux-style
modular state store (`DictateOrchestrator`), a foreground service that hosts it so the pipeline
outlives the IME, a declarative `LayoutCatalog` rendered by pluggable backends, a state-derived
info-bar, and a single write owner for every text mutation. Dictate Keyboard 5.3's counterpart is
`DictateController.kt`, a ~3,200-line process-wide `object` with one recorder, one transcribe job and
no foreground service for transcription. **Verdict: absent upstream, and unportable in both
directions.** Upstream did not converge on this shape; it went the other way, and it has walked into
several of the specific walls this architecture was built to prevent. The value of our architecture
is entirely internal — it is what makes the fork's other 20 clusters buildable at all — while its
*lessons* (single write owner, one dispatch entry point, persist-first) travel as concepts only.

## 1. Feature Overview

The problem in upstream 3.2 was concrete and measured, not aesthetic. `DictateInputMethodService.java`
was 2,258 lines owning recording, the AI pipeline, layout, visibility and the info-bar at once. As the
fork added session persistence (cluster 4) the class grew until three defensive extractions
(`KeyboardUiController`, `KeyboardStateManager`, `RecordingStateController`, `RecordingUiController`)
were carved out just to keep it compiling — and that produced the actual failure mode: **five parallel
writers on the same logical state.** ADR-0001's research section counts **27 separate visibility
mutations on the single `resend_btn` attribute** and traces five production bugs to that fan-out. No
reader could answer "who last hid this button and why" without reading all five writers.

Four structural answers followed, each with its own ADR, and a fifth landed later as a corollary:

- **One mutation entry point.** `DictateOrchestrator.dispatch(Action)` is the only way state changes.
  Each axis of state is a `DictateModule<S, A, E>` addressed by a lens; reducers are pure and return a
  `TransitionResult`; side effects run outside the reducer with failures routed back by
  `originModuleId`. ADR-0002 governs how modules influence each other and *forbids* the tempting third
  mode (a reducer writing a foreign axis).
- **A process-resident host.** Android destroys the IME service whenever the user switches keyboards —
  a routine event (Gboard for a password field, a different layout for a foreign language). Anything
  living inside the IME dies mid-recording. ADR-0003 moves the store into a bound foreground service
  with `foregroundServiceType="microphone"`, whose persistent notification doubles as the user-facing
  recording indicator.
- **Declarative layout.** The pre-refactor mode switcher physically re-parented buttons between rows,
  with an `originalParents` map as the band-aid. ADR-0004 makes re-parenting structurally impossible:
  a `LayoutCatalog` declares which logical button occupies which slot per mode, and MotionLayout
  transitions between ConstraintSets. Backends only ever *read* state.
- **Notices as a pure function of state.** Nine hardcoded imperative `showInfo()` cases, a second
  permission bar with its own renderer, and scattered toasts meant one AI error fanned out to 4–5
  surfaces through independent wirings, with no shared "error" fact anywhere in state. ADR-0006 makes
  info items a selector `(DictateUiState) -> List<InfoBarItem>`.
- **One write owner.** Text reached the `InputConnection` from many places using naive
  `deleteSurroundingText(1, 0)`, which splits emoji and combining sequences and ignores an active
  selection. The Enter key was the clearest symptom of the drift: its icon switched per
  `EditorInfo.imeOptions` while its click hardcoded `commitText("\n", 1)`, so a search field showed a
  search icon and inserted a newline. Everything now funnels through `InsertionService`.

The user-visible payoff is indirect but real: recording survives a keyboard switch, the pipeline
survives IME teardown, a crash no longer loses in-flight work, rotation no longer resets the surface,
and delete is grapheme-correct. None of it is a feature you can point at in a changelog.

## 2. Our Implementation

`state/` is **103 Kotlin files / 25,508 LOC** — for scale, that is *larger than upstream's entire
Dictate-specific layer* (23.3k LOC on top of FlorisBoard). Core: `DictateOrchestrator.kt` (471),
`DictateModule.kt` (216), `DictateModuleRegistry.kt` (267, with `assertCompleteCoverage()`),
`DictateUiState.kt` (1,168 — **20 axes**), `Action.kt` (1,546), `ModuleServices.kt` (670, the DI seam).
`state/modules/` holds 19 files / 6,037 LOC, 18 registered: Recording (1,225), Pipeline (891), Audio
(521), Overlay (505), Widget (336), Resend, LivePrompt, Language, Layout, FeatureToggle, Theming,
PendingSessions, KeyboardInput, InfoHint, Interruption, ReviewPanel, HistoryPanel, WindowsDispatch —
plus a legacy `ViewModeModule` that is still open debt.

The host is `core/DictatePipelineService.kt` (2,375 LOC) with `PipelineNotificationCoordinator` (344),
`PipelineActionRouter`, `PipelineCallbackBridge`, `PipelineRunnerSubsystemAdapter` (404),
`ActiveJobRegistry` and a `BootCompletedReceiver` that runs `recoverDbOnly()` before the keyboard is
first opened. WorkManager was evaluated and explicitly rejected in ADR-0003. The binder is
`exported=false`, in-process only.

Rendering splits in two: `state/layout/` (12 files / 3,226 LOC, `LayoutCatalog.kt` alone 914) declares
8 layout modes over 18 `LogicalButtonId`s with predicate/text/icon/action resolvers, and `state/render/`
(22 files / 4,382 LOC, `ImeViewBackend.kt` 649) executes them against `res/xml/motion_scene_keyboard.xml`
(604 lines). ADR-0010 is the sibling convention — icon colour comes from theme attributes at the usage
site, enforced by a JVM source-scan invariant test. ADR-0022 later rebuilt the edit bar because the
original ConstraintLayout chain of `0dp` buttons divided the viewport evenly and unconditionally; going
from 12 to 14 buttons pushed icons below a usable touch target on a 320 dp phone.

The info-bar is `state/infobar/` (5 files / 884 LOC, `InfoBarSelector.kt` 540) plus `InfoHintState`
(188) and `InfoHintModule` (241), with **eleven producer blocks** — panel ownership, widget ownership,
overlay-permission onboarding, pending-parts aggregate, partial-recovery warning, recovery-surfaced
unfinished recording, interruption-paused recording, pipeline errors, windows-dispatch notice,
cancellation notice, engagement hints. Dismissal persists through each item's *natural source* rather
than a uniform `dismissed` flag.

The write owner is `state/insertion/`: `InsertionService.kt` (207), `Insertion.kt` (`ControlOp`,
`EditAction`, `InsertionRequest`), `GraphemeTextOps.kt`, `LocalImeSink.kt`, `PendingPartsFlusher.kt`,
`SlowOutputAnimator.kt`. A `HostEditorState` axis on `KeyboardInputModule` made the Enter icon and the
Enter action read from one source, deleting the parallel legacy paths outright.

> [!NOTE]
> `DictateInputMethodService.java` was never deleted — it was re-homed. It went 2,148 lines pre-merge
> → 5,766 at the merge → **7,418 today**, but it is now a host/wiring shell that dispatches actions
> instead of owning state. The line count is misleading; the ownership is what changed.

**How coupled.** Total. Clusters 9–22 and the Android half of 24–26 are clients, hosts or modules of
the store. ADR-0027's PC-Dictation Activity exists *only* because `KeyboardLayoutManager` fans
`DictateUiState` to a list of backends — it is the third render host and cost ~1,500 lines instead of a
rewrite. ADR-0026's keyboard-action router sits in front of `InsertionService` and replaced ~15
scattered `if (pcMode)` branches; without the single-write-owner refactor it has nowhere to sit.

Governance is documented rather than tribal: `docs/architecture/state-architecture/` is 12 teaching
files including `forbidden-patterns.md` (14 hard-forbidden patterns a–n), and the layout-refactor plan
shipped 946 tests while running as a **parallel-dormant second architecture** before the cutover flipped
it live.

## 3. Upstream 5.3 Status

**Verdict: absent — upstream took the opposite road, deliberately and successfully for its own goals.**

Dictate Keyboard 5.3 is FlorisBoard with a dictation layer grafted in. The orchestrator is
`dictate/DictateController.kt`, ~3,200 lines, a **process-wide Kotlin `object`** with a `SupervisorJob`
scope (`:110`, `:228`), a single `recorder` and a single `transcribeJob`. State lives in
`MutableStateFlow`s consumed by Compose; there is no dispatch funnel, no module boundary, no reducer
purity, and no equivalent of a `LayoutCatalog` — layout is Compose composition over FlorisBoard's own
keyboard stack (77 character layouts, `qwertz.json` among them).

What that buys them, honestly: **it is a vastly smaller architecture for a vastly larger feature set.**
23.3k LOC of Dictate-specific code delivers realtime streaming from five providers, on-device
transcription via sherpa-onnx, a Wear OS keyboard, a system-wide `RecognitionService`, a floating
bubble, long-form segmentation and 16 provider presets. Our 25.5k LOC of `state/` delivers correctness
properties. Compose also removes an entire class of problem we solved structurally: there is no view
tree to re-parent, so ADR-0004's whole problem statement evaporates, and `visibilityMode="ignore"` has
no meaning in a world where the UI *is* a function of state by construction. On that specific axis
upstream got the same guarantee for free that we paid ~7,600 LOC for.

Where the monolith-object shape has cost them, with evidence:

- **Eager composition blocked the UI thread.** `ui/DictateHistoryLayout.kt:156-158` carries their own
  comment that composing "several hundred entries" blocked the UI thread for over a second. They
  patched around it (`LazyColumn` windowing) rather than paginating; there is no `androidx.paging`
  anywhere and the DAO is a bare `SELECT *` returning a `Flow<List<…>>`.
- **No foreground service for transcription.** `startForegroundService` appears only for model
  downloads and the *overlay* mic path — the accessibility service is promoted to
  `FOREGROUND_SERVICE_TYPE_MICROPHONE` for the floating bubble
  (`DictateAccessibilityService.kt:516-527`), but the IME dictation path has none. Consequently the
  IME-hosted flow does **not** continue across service teardown: `onDestroy` is one of three triggers
  that funnel into `stashRecordingOnHide` (`DictateController.kt:2226`), which stops the recorder,
  patches the WAV header, moves the file to `filesDir/dictate_interrupted.wav` "so it survives the
  cache wipe", and offers **send / continue / discard** on the next keyboard open. The process-wide
  `object` does survive an IME *service* restart within the same process, so state is not lost — but
  the recording is deliberately ended, not carried, and nothing keeps the process itself alive.
- **A crash during recording loses the audio.** The WAV header is only patched in `stop()`; there is
  no periodic flush and no orphan scan (upstream report area 10c).
- **No single write owner.** Text injection from the bubble is a three-tier ladder inside the
  accessibility service (`DictateAccessibilityService.kt:222-264`): a11y `InputConnection.commitText`
  on API 33+, then node `ACTION_SET_TEXT`, then clipboard paste with the previous clip restored after
  400 ms. That is a *sink* ladder, not a router — the keyboard path, the bubble path, the watch path
  and the `RecognitionService` path each reach their own sink.
- **No IME cold-start work at all.** `onCreateInputView()` is the stock FlorisBoard implementation,
  untouched (`FlorisImeService.kt:312-318`). Their latency work is real, instrumented
  (`LATENCY_LOG_TAG = "DictateLatency"`, `BatchLatencyTrace`) and thorough — but aimed entirely at the
  dictation pipeline, not the first frame.

What upstream's architecture does *better* than ours, stated plainly: it is comprehensible in one
sitting, a new feature is a new function on an existing object rather than a new module + lens + action
family + reducer + tests, and DevEmperor ships features at a rate our architecture would not permit for
a single maintainer. Our shape earns its keep only because the fork's feature set (three render hosts,
crash recovery, an ordered run queue, a PC routing engine) genuinely needs a state contract; upstream's
feature set, broad as it is, is mostly *one dictation at a time through one path*, which a monolith
handles fine.

## 4. Assessment — is this architecture still sensible?

**In the fork: yes, unambiguously, and the question is nearly meaningless.** This is not a feature that
can be kept or dropped. It is the substrate. Removing it means removing clusters 9–22 and 24–27; there
is no fork left afterwards. Every incremental thing Lukas still wants to build — a pending-info screen,
multi-PC targets, the unlanded desktop dictation host — attaches to it. The architecture also
demonstrably paid for itself twice: the third render host (PC-Dictation Activity) cost ~1,500 lines
instead of a rewrite, and the keyboard-action router replaced ~15 scattered `if (pcMode)` branches with
one exclusive sink.

**As something to carry elsewhere: no.** This is the honest half. `state/` cannot be ported to Dictate
Keyboard 5.x — not because it is bad, but because 5.x is Compose over FlorisBoard, where a
view-tree-oriented `RenderBackend`/MotionScene layer has no host and a second state container would sit
beside `DictateController` doing nothing. Nor can it be proposed upstream: the upstream report's read
of DevEmperor's PR history is that "small, well-scoped, issue-anchored PRs have a good chance; large
architectural contributions almost certainly do not", in a codebase that is 397/453 single-author with
a strong personal design voice. A 25k-LOC state rewrite is the exact shape of contribution that gets
declined, and *rightly so* from his position.

**The cost of keeping it** is the real argument to weigh. It is a legacy-Java-hosted architecture: the
IME service is still 7,418 lines of Java, `ViewModeModule`/`ViewMode`/`ViewModeAction` still coexist
with `WidgetModule` with 282 reader migrations deferred, `state-architecture/triangle-fsm.md` still
documents the model ADR-0008 superseded, and ADR-0007 still says *Proposed* despite being implemented.
The 243-file JVM test suite is what makes this maintainable at all, and it is also a standing tax — the
Robolectric inflation suite needs `maxHeapSize = "2g"` and `forkEvery = 80` to avoid OOM. Meanwhile
every upstream release widens the feature gap in directions the fork structurally cannot follow
(on-device STT, realtime streaming, Wear OS, glide typing), because those live below the dictation
layer in FlorisBoard's stack.

**Upstream signal.** There is none for or against — this was never proposed, and the areas where
upstream has explicitly rejected our direction (usage/cost tracking, screen-context capture) are
different clusters. What *is* signal: upstream independently hit two of the walls this architecture was
built for (UI-thread-blocking eager composition; recording lost to a mid-recording crash) and solved
them tactically rather than structurally. That is evidence the problems are real, not evidence the
solution is wanted.

**Where it is genuinely contested:** whether the *degree* of rigour was proportionate. Eleven info-bar
producer blocks and a 540-line selector to replace nine `showInfo()` cases is defensible as
correctness-by-construction and criticisable as gold-plating; the same argument applies to a 604-line
MotionScene. The counter-evidence is cluster 28: the fork's own audit waves found real bugs (`F-005`
RESEND not seeded on cold boot, `F-029` a cooldown timer living outside its module, `F-092`
`POST_NOTIFICATIONS` declared but never requested) precisely *because* the architecture makes "which
module owns this?" an answerable question. A monolith hides those; it does not lack them.

## 5. Options going forward

**(a) Keep in the fork as-is.** The only option that preserves the fork. Cost is ongoing maintenance
of a Java-hosted architecture that no longer tracks upstream, plus the named open debt (`ViewMode`
removal, `triangle-fsm.md`, ADR-0007's status header). Benefit: everything else the fork does keeps
working, and new work stays cheap.

**(b) Port to Dictate Keyboard 5.x as a private patch.** Not realistic for the store itself.
`DictateController` would have to be split behind a dispatch funnel — a rewrite of upstream's
orchestrator that every upstream release would then conflict with. The sub-pieces that *could* travel
are small and shallow: `GraphemeTextOps` (pure), `InputViewRetentionPolicy` (a pure `Configuration.diff`
predicate, though Compose changes the calculus), and the *idea* of one write owner in front of
upstream's four injection paths.

**(c) Propose upstream via issue.** Only in narrow, symptom-shaped slices, never as architecture. Two
plausible ones: an issue about the mid-recording-crash audio loss (their own gap, area 10c) proposing a
periodic WAV-header flush or orphan scan; and an issue about IME-path recording not surviving a
keyboard switch, proposing a microphone-typed FGS for the IME flow the way they already do for the
bubble. Both are one-behaviour issues with a clear user story, which is the shape that lands there. A
PR titled "introduce a modular state store" is not.

**(d) Retire / let upstream replace it.** Only coherent as a decision to abandon the fork entirely and
move to 5.x, accepting the loss of the PC companion, the audit DB, the run queue and the review panel.
That is a fork-level decision, not an architecture-level one, and belongs in the overview doc.

*Leaning:* (a) for as long as the fork lives, with (c) as two small, honest bug-shaped issues that cost
almost nothing to file and carry the architecture's lessons without carrying its code.

## 6. Information Gaps

1. **How often does the keyboard-switch survival actually fire in real use?** The FGS exists to survive
   IME destruction mid-recording, but no telemetry exists (the fork has no analytics by design). *Owner:
   Lukas.* *Fallback:* treat it as insurance whose value is bounded by how often a password field or a
   second keyboard interrupts a dictation — plausibly rare, but the failure it prevents is total data
   loss.
2. **Would a microphone-typed FGS even be accepted for upstream's IME path?** Play Store policy for
   `FOREGROUND_SERVICE_MICROPHONE` is stricter than for a sideloaded fork, and Dictate Keyboard is
   distributed on Play. *Owner: whoever files the issue.* *Fallback:* frame the issue as the *problem*
   (recording lost on keyboard switch) and let DevEmperor choose the mechanism.
3. **Is the remaining `ViewModeModule` debt costing anything measurable?** 282 deferred reader
   migrations is a number from the plan, not from a current grep. *Owner: Lukas.* *Fallback:* re-run the
   grep before the next state-touching wave; if it is inert, downgrade it from debt to vestige.
4. **Does the 12-file `state-architecture/` doc set still match the code after the July waves?**
   `triangle-fsm.md` is known-stale (ADR-0008 superseded ADR-0005). Others are unverified. *Owner:
   Lukas.* *Fallback:* a doc-drift pass gated on the next module addition, since `adding-a-module.md` is
   the file that would mislead a future reader fastest.
5. **Compose vs. view-tree — is any of ADR-0004's pattern transferable at all?** Unverified whether a
   declarative slot catalogue adds anything on top of Compose's own model. *Owner: unassigned.*
   *Fallback:* assume no, and treat the catalogue as a view-system-only artifact.

## 7. References

**Source reports**
- [`../sources/fork-feature-inventory.md`](../sources/fork-feature-inventory.md) — clusters 8, 9, 10, 12, 22
- [`../sources/upstream-rewrite-analysis.md`](../sources/upstream-rewrite-analysis.md) — §A.2, §A.3, areas 1, 3, 5, 10, 11, 13, Part C

**ADRs**
- [`../../../decisions/0001-state-modular-orchestrator-pattern.md`](../../../decisions/0001-state-modular-orchestrator-pattern.md) — the 27-mutation research finding
- [`../../../decisions/0002-state-cross-module-cascade.md`](../../../decisions/0002-state-cross-module-cascade.md)
- [`../../../decisions/0003-service-foreground-pipeline-architecture.md`](../../../decisions/0003-service-foreground-pipeline-architecture.md)
- [`../../../decisions/0004-ui-layout-catalog-motionlayout.md`](../../../decisions/0004-ui-layout-catalog-motionlayout.md)
- [`../../../decisions/0006-ui-info-bar-state-derived-items.md`](../../../decisions/0006-ui-info-bar-state-derived-items.md)
- [`../../../decisions/0008-ui-surface-axes-widget-state-and-ime-view.md`](../../../decisions/0008-ui-surface-axes-widget-state-and-ime-view.md) — supersedes [`0005`](../../../decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md)
- [`../../../decisions/0010-ui-icon-tint-theme-attrs.md`](../../../decisions/0010-ui-icon-tint-theme-attrs.md), [`0022`](../../../decisions/0022-editbar-overflow-peek.md)
- [`../../../decisions/0026-keyboard-action-routing.md`](../../../decisions/0026-keyboard-action-routing.md), [`0027`](../../../decisions/0027-pc-dictation-activity.md) — the two clusters that prove the abstraction paid off

**Plans**
- [`../../../plans/2026-05-07 - dictate-keyboard-layout-refactor/`](../../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/) — 6 blocks / 19 chunks, parallel-dormant build, 946 tests
- [`../../../plans/2026-05-15 - dictate-cutover-completion/`](../../../plans/2026-05-15%20-%20dictate-cutover-completion/) — the flip, four legacy controllers deleted
- [`../../../plans/2026-05-21 - dictate-indirection-cleanup/`](../../../plans/2026-05-21%20-%20dictate-indirection-cleanup/), [`2026-05-22 - dictate-infobar-migration/`](../../../plans/2026-05-22%20-%20dictate-infobar-migration/), [`2026-05-23 - dictate-enter-button-host-action/`](../../../plans/2026-05-23%20-%20dictate-enter-button-host-action/)

**Architecture docs**
- [`../../../architecture/state-architecture/`](../../../architecture/state-architecture/) — 12 files, incl. `forbidden-patterns.md` (patterns a–n)

**Fork commits**
`efd39d25` (the 277-commit architecture merge), `be060522` (info-bar consolidation, 28 files / +1,604 / −426), `d2a78357` (single write owner, F-018/F-020/F-021/F-023)

**Upstream evidence** (paths @ `upstream/main` `3e5ebe46`, tag `v5.3.0`)
- `dictate/DictateController.kt:110,228` (process-wide `object` + `SupervisorJob`), `:598-604` (`canStartRecording`), `:2226` (`stashRecordingOnHide`)
- `ui/DictateHistoryLayout.kt:156-158` — their own UI-thread-blocking comment
- `dictate/overlay/DictateAccessibilityService.kt:222-264` (three-tier injection ladder), `:516-527` (FGS microphone for the bubble only)
- `app/.../FlorisImeService.kt:312-318` — stock `onCreateInputView()`, untouched
- `dictate/DictateLegacyLayout.kt:26-33` — the inverse of our keyboard work (`OFF | LOCKED | SWIPE`)
