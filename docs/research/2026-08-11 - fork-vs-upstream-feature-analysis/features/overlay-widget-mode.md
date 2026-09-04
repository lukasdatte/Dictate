---
date: 2026-08-11
author: Lukas + Claude (analysis session)
status: Research
context: Fork-vs-upstream analysis of the floating overlay widget (plus the recording visual feedback it renders) — what we built, what Dictate Keyboard 5.3 has, and whether the feature is still worth pursuing.
related-adrs: ADR-0005 (superseded), ADR-0008, ADR-0009, ADR-0010, ADR-0011
related-plan: n/a (plan-free research)
---

# Overlay / Floating Widget Mode — Fork vs. Upstream Analysis

The overlay widget is a draggable card that floats above the host app and carries the
dictation controls — either because the user toggled it on (`WIDGET`) or because a
recording/pipeline is still running after the keyboard went away (`HOVER`). **Verdict:
partially exists upstream, on a fundamentally different foundation.** Dictate Keyboard 5.3
ships a system-wide floating bubble that is strictly more reachable than ours (it works over
any app, with any keyboard, without a draw-over-apps permission), and its recording visuals
are richer than the pulse/amplitude work we did. What it does not have is the *in-keyboard
integration* our widget grew: a user-controllable opacity, a third row of editing keys, a
full collapse of the IME surface behind it, and a pipeline-driven origin that survives
process death.

## 1. Feature Overview

Two genuinely different needs converged on one surface. The first is a **floating keyboard**:
the user wants to dictate into an app whose text field sits behind the keyboard, or wants the
record button reachable while the keyboard is folded away — the `InputConnection` is still
alive, so the result commits normally. The second is **not losing control of a live run**:
Android tears the IME view down when the user switches apps, and before the widget existed a
recording simply continued in what the plans call zombie mode — mic hot, no send, no cancel,
no visible timer. The overlay auto-surfaces in that case so there is always an affordance
attached to a live pipeline.

Concretely: a five-button card (record/send, pause, trash, close, plus the auto-enter toggle
slot) that drags anywhere on screen and remembers separate portrait and landscape positions;
a secondary-record button that appears while a run is processing so the next thought can be
queued (ADR-0009); a **third row** (delete / space / enter) that only renders when there is a
host editor to commit into, with press-and-hold continuous delete on the keyboard's own
acceleration curve; and a **user-configurable card transparency** (20–100 %) so whatever sits
behind the card stays readable. When the user opens the widget deliberately, the IME surface
collapses to a 2 dp strip — the widget promises "the keyboard disappears", and since
`43784f72` that promise is a single named predicate (`DictateUiState.imeCollapsedToStrip`)
every renderer reads, rather than a term each surface remembers to copy.

Riding on the same surface is the recording visual feedback of cluster 7: a ripple pulse
behind the record button (`PulseLayout`, behind a `RecordingAnimation` strategy interface) and
a real amplitude visualizer (`AmplitudeProcessor` → `AmplitudeVisualizerDrawable`, plus a
`BorderGlow` variant), reused by the overlay through `state/render/RecordGlowFactory` and
`RecordingAnimationController`.

## 2. Our Implementation

**Shape.** `state/render/overlay/` is 11 files / 2,586 LOC, dominated by `OverlayBackend.kt`
(1,174) — the third `RenderBackend` implementation, reading `DictateUiState` and rendering
`LayoutCatalog.OVERLAY_5BUTTON` into a `TYPE_APPLICATION_OVERLAY` window. Around it:
`OverlayWindow`, `OverlayLayoutParamsFactory` (sets `PixelFormat.TRANSLUCENT` so the rounded
corners' alpha mask works — which is also why transparency turned out to be a drawable
mutation, not a window change), `OverlayDragController` + `DraggableOverlayLayout`,
`OverlayPositionMapper`, `OverlayCardFill`, `OverlayPermissionGate`/`Observer`,
`OverlayDeleteRepeatController`, `OverlayCharactersController`. State lives in
`WidgetModule.kt` (336) and `OverlayModule.kt` (505); the view tree is
`res/layout/overlay_5button_layout.xml` (231 lines). Prefs: `WidgetOpacity`,
`OverlayPositionPortraitX/Y`, `OverlayPositionLandscapeX/Y`, `OverlayOnboardingShown/Dismissed`,
`OverlayCharacters`, `Theme`. Twelve JVM/Robolectric test files cover the package.

**The modelling story is the interesting part.** ADR-0005 modelled the surface as a
Triangle-FSM (`ViewMode = KEYBOARD | WIDGET | HOVER`) with a `computeViewMode` truth table.
Within a week that collapsed: a bidirectional-render fix made both surfaces simultaneously
live, so a 1-of-3 enum was structurally a lie, and crash recovery needed to know *why* the
widget was up in order to decide where to return after the pipeline ends.
[ADR-0008](../../../decisions/0008-ui-surface-axes-widget-state-and-ime-view.md) supersedes it
with two orthogonal axes — `WidgetState = Hidden | Visible(origin: USER | PIPELINE)` plus
`imeViewVisible: Boolean` — and eight transitions W1–W8. That is what makes "sticky user
widget" a structural property rather than a truth-table row ordering, and what makes seamless
crash resume decidable.

**The July parity wave** (`docs/research/2026-07-11 - widget-mode-parity-and-third-row.md`)
turned the widget from a recording remote into a self-sufficient surface: recording-wins
precedence over pipeline-live (P1), secondary record (P2), close-handoff that keeps the
recording alive when the keyboard can take over (P3), the third row (P4), and repeat-delete
as a scheduler-injected pure policy class (P5). Its central design rule is one predicate —
`DictateUiState.canCommitToHost` — gating every host-commit affordance, because the previous
failure mode was a guard whose *name* and whose *body* had drifted apart. A follow-up decision
(2026-07-12) enabled sending from `HOVER` as well, since ADR-0009 deferred insertion plus
ADR-0011 headless completion mean a surface-less send is deferred, not lost.

**Coupling — the least portable feature in the fork.** It owns two axes of the state store
(cluster 8), the `PIPELINE` origin only exists because the foreground service outlives the IME
(cluster 9, ADR-0003), it renders through the LayoutCatalog (cluster 10), and continuation
resumes multi-segment audio (cluster 13, ADR-0007). Open debt on `main`: the legacy
`ViewModeModule`/`ViewMode`/`ViewModeAction` triple still coexists with `WidgetModule` (282
reader migrations deferred), and `docs/architecture/state-architecture/triangle-fsm.md` still
documents the superseded model.

## 3. Upstream 5.3 Status

**Verdict: partially exists — different architecture, wider reach, narrower knobs.**

Upstream's floating dictation button (#88, shipped in 4.2) is a `TYPE_ACCESSIBILITY_OVERLAY`
window hosted by the accessibility service (`dictate/overlay/DictateBubbleController.kt:72-78`,
params at `:648-665`). Because the a11y service hosts the window, **no `SYSTEM_ALERT_WINDOW`
permission is needed at all** — the manifest does not declare it. Background mic capture is
legalised by promoting the a11y service to `FOREGROUND_SERVICE_TYPE_MICROPHONE`
(`DictateAccessibilityService.kt:516-527`). Text lands via a three-tier ladder
(`:222-264`): a11y `InputConnection.commitText` on API 33+, then node `ACTION_SET_TEXT`, then
clipboard paste with the user's previous clip restored after 400 ms.

**What upstream does better:**

- **Reach.** The bubble floats over *any* app while *any* keyboard is active. Ours is a card
  belonging to our own IME; if the user is on Gboard, our widget does not exist.
- **No permission prompt.** Ours needs draw-over-apps and ships an onboarding gate
  (`OverlayPermissionGate`/`Observer`) for it; theirs needs the a11y toggle it already asks for.
- **Presentation.** Six designs (`RING, PILL, ORB, CLOUD, AURORA, LATTICE` —
  `DictateFloatingButtonDesign.kt:25-35`, the last two added in 5.3 via #253), three sizes,
  a full colour picker, edge snapping, **per-app remembered positions**, auto-dim, haptics,
  an undo button, optional clipboard copy, and a long-press menu offering the freeform
  "Live Prompt" voice command plus every saved prompt (`DictateBubbleController.kt:442-522`).
- **Recording visuals — better than our cluster 7.** This is not covered by the upstream
  report's area list, so it is worth stating plainly: upstream has a first-class
  `DictateRecordingAnimation { STATIC, PULSE, LEVEL }` preference (#238) driving both the
  Smartbar record dot (`ui/DictateSmartbarUi.kt:354-393`) and the classic legacy layout
  (`ui/LegacyDictateLayout.kt:593-610`), plus level-reactive bubble skins fed by a shared 20 Hz
  mic-level ticker (`DictateBubbleController.kt:978-1006`, `audio/AudioLevelSmoother.kt`,
  `ui/AudioReactiveCloudOrbView.kt`, `ui/DictateLatticeSphereView.kt`). Our `PulseLayout` +
  `AmplitudeVisualizerDrawable` + `BorderGlowDrawable` set has no advantage here — it is
  matched and then some.
- **A HOVER analogue exists.** The visibility rule is
  `show = enabled && (focused || active) && !hiddenByOwnKeyboard && !recogActive`
  (`DictateBubbleController.kt:194-240`), where `active` is "a dictation is in flight". So the
  bubble does auto-surface for a running dictation with no focused field — the same instinct as
  our `PIPELINE` origin. A `floatingButtonShowWithDictateKeyboard` pref governs coexistence
  with their own keyboard, which is the mirror image of our IME-collapse rule.

**What upstream lacks vs. ours:**

- **No opacity / transparency preference.** Verified in the worktree: the floating-button
  colour picker explicitly disables the alpha slider
  (`app/settings/dictate/DictateFloatingButtonScreen.kt:239`, `showAlphaSlider = false`), and
  the only fade is a hard-coded idle auto-dim (`.alpha(if (dim) 0.45f else 1f)`,
  `DictateBubbleController.kt:876`) that is on/off only. Our `Pref.WidgetOpacity` (20–100 %)
  has no counterpart.
- **No third row and no editing keys.** The bubble is a *button*, not a panel: tap toggles
  recording, long-press opens a prompt menu. Delete/space/enter without unfolding a keyboard is
  not a thing there.
- **No user-toggled collapse.** The PILL skin auto-expands while recording (`:1491-1500`) and
  auto-dim shrinks to a 50 %-scale dot, but there is no persistent collapsed-handle state and no
  collapse gesture.
- **No crash resume.** `DictateController` is a process-wide `object` with in-memory state
  (upstream report area 13): a process death loses the in-flight state machine entirely, and the
  carry-over splice state for a *continued* recording is in-memory only. Our
  `WidgetOrigin` + `RECORDING_INTERRUPTED` + `AudioFileRepository` chain exists precisely to
  make that resumable.

> [!NOTE]
> The `showAlphaSlider = false` flag appears at three call sites upstream
> (`OtherScreen.kt:80`, `DictateFloatingButtonScreen.kt:239`, `ThemeScreen.kt:119`), so it reads
> as a house convention for *colour pickers*, not as a decision against widget transparency.
> That matters for §5(c): it is a gap, not a rejected direction.

## 4. Assessment — is this feature still sensible?

**Does the user need it?** Yes, but it is worth separating the two halves. The `HOVER` half —
never losing control of a live recording when the keyboard goes away — is load-bearing for a
heavy dictation workflow and is a correctness property, not a nicety; it exists because zombie
recordings were an observed failure. The `WIDGET` half is more discretionary: it earns its keep
when the host field sits behind the keyboard, and the July third row earns its keep in the
"dictate → space → dictate → enter" micro-edit loop. Neither is speculative; both came from
concrete reports.

**Would a 5.3 user miss it?** Mostly no, and in one dimension they would be better off. The
bubble covers more ground (any app, any keyboard, no draw-over permission), looks better, and
has richer level visuals. A 5.3 user would miss exactly three things: the opacity slider, the
third-row editing keys, and resume-after-crash. Of those, only the opacity slider is a small,
self-contained want; the other two are architectural.

**What does keeping it cost?** This is the honest counterweight. The overlay is the single
least portable cluster in the fork: ~22 production files / ~4,400 LOC spanning two state-store
axes, the foreground service, the LayoutCatalog and the audio repository, plus ~1,350 further
insertions across the four July merges. It also carries live debt — the superseded
`ViewModeModule` triple still shipping alongside `WidgetModule`, and a state-architecture
teaching document that still describes the model ADR-0008 replaced. Every change to the surface
axes has to be reasoned about twice as long as it should be until that is cleaned up.

**Upstream signal.** No rejection anywhere. The bubble is under active development (#253 in
5.3 added two designs), which cuts both ways: it means the maintainer cares about this surface
(good for a small feature request), and it means the design direction is his and firmly held
(bad for anything that turns the bubble into a panel). The upstream report's contributor
analysis is the operative constraint: small, issue-anchored contributions land; architectural
ones do not.

## 5. Options going forward

**(a) Keep in fork as-is.** Zero migration cost and the feature is finished and tested. The
cost is that it anchors the fork to the state-store architecture — this is the cluster that
makes "just move to 5.x" expensive. If the fork continues, the one thing worth doing is
retiring the `ViewMode` triple so the ADR-0008 model is the only one in the tree.

**(b) Port to Dictate Keyboard 5.x as a private patch.** Not realistic as a port. The window
type, the host (a11y service vs. IME), the render path (Compose vs. MotionLayout + LayoutCatalog)
and the state model all differ; what would survive is the *idea* (origin-tracked visibility,
`canCommitToHost` as a single predicate), not the code. A far smaller private patch — an opacity
slider on their bubble — is a few dozen lines against their existing colour pref.

**(c) Propose upstream via issue.** One well-scoped candidate: **bubble opacity**. The issue
writes itself — "the bubble hides content behind it; the auto-dim is on/off and hard-coded at
0.45" — and the implementation is a percent pref + a slider in `DictateFloatingButtonScreen`
+ applying it in `rebuildSkin()`, with the existing `showAlphaSlider = false` staying untouched
(it is a colour-picker convention, not the mechanism). Everything else in this cluster (third
row, collapse, origin tracking) is a design-direction change and belongs in an issue only as a
question, if at all.

**(d) Retire in favour of upstream's bubble.** Only coherent as part of a whole-app move to
5.x. In isolation it would mean giving up `HOVER` and crash resume for nothing, since our IME
cannot host an a11y-overlay bubble without the a11y service we only ship for screen context.

*Leaning:* the widget stays wherever the fork stays, and the only piece with a plausible
upstream life of its own is the opacity preference.

## 6. Information Gaps

1. **How often the user actually opens the widget deliberately (USER origin) versus meeting it
   via `HOVER`.** The two halves have very different justifications and only one of them is a
   correctness property. There is no telemetry. **Owner:** Lukas. **Fallback:** assume both are
   used and keep both; the third row's existence implies the USER half sees real use.
2. **Whether the third row is used for real editing or only for `enter`.** P4 shipped
   delete/space/enter together; if only `enter` matters, the row could shrink and the
   repeat-delete controller retire. **Owner:** Lukas. **Fallback:** keep as-is; it is already
   built and tested.
3. **Whether upstream's a11y-hosted bubble would be acceptable to us at all**, given that it
   requires the accessibility service to be enabled for what we currently do with a
   draw-over-apps permission. **Owner:** Lukas (privacy preference). **Fallback:** treat the
   two mechanisms as non-substitutable.
4. **Position re-clamp on row-visibility changes** is a known open defect (widget-mode-parity
   §5.1): `OverlayBackend.applyPosition` caches on `(portrait, normX, normY)` without the view
   height, so a bottom-docked widget can sit slightly off-screen when the third row appears or
   disappears. **Owner:** follow-up fix. **Fallback:** the user drags the widget once.

## 7. References

**Source reports**
- [`../sources/fork-feature-inventory.md`](../sources/fork-feature-inventory.md) — clusters 11
  (widget/overlay) and 7 (recording visual feedback)
- [`../sources/upstream-rewrite-analysis.md`](../sources/upstream-rewrite-analysis.md) — area 5
  (overlay/floating widget), area 13 (session persistence)

**Our ADRs / plans / research**
- [`ADR-0005 — Triangle-FSM (superseded)`](../../../decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md)
- [`ADR-0008 — Surface axes: WidgetState + ImeView`](../../../decisions/0008-ui-surface-axes-widget-state-and-ime-view.md)
- [`ADR-0009 — Run queue / serialized concurrency`](../../../decisions/0009-pipeline-run-queue-serialized-concurrency.md),
  [`ADR-0011 — Headless completion fallback`](../../../decisions/0011-pipeline-headless-completion-fallback.md)
- [`ADR-0010 — Icon tint via theme attrs`](../../../decisions/0010-ui-icon-tint-theme-attrs.md)
- Plans: [`2026-05-21 - dictate-widget-integration`](../../../plans/2026-05-21%20-%20dictate-widget-integration/),
  [`2026-05-21 - dictate-widget-state-and-recovery`](../../../plans/2026-05-21%20-%20dictate-widget-state-and-recovery/)
- Research: [`2026-07-02 - overlay-widget-transparency.md`](../../2026-07-02%20-%20overlay-widget-transparency.md),
  [`2026-07-11 - widget-mode-parity-and-third-row.md`](../../2026-07-11%20-%20widget-mode-parity-and-third-row.md)
- Commits: `fac8af72` (opacity + theme unification), `905ae915` (third row), `f557fd09`
  (record precedence), `43784f72` (IME-surface collapse), `1deed1ae` / `007cd02e` (pulse +
  amplitude visualizer)

**Upstream evidence** (paths @ `upstream/main` `3e5ebe46`)
- `app/.../dictate/overlay/DictateBubbleController.kt:72-78` (window type), `:194-240`
  (visibility rule), `:442-522` (long-press menu), `:876` (auto-dim), `:978-1006` (level ticker)
- `app/.../dictate/overlay/DictateAccessibilityService.kt:222-264` (injection ladder), `:516-527`
  (mic FGS)
- `app/.../app/settings/dictate/DictateFloatingButtonScreen.kt:239` (`showAlphaSlider = false`)
- `app/.../dictate/DictateFloatingButtonDesign.kt:25-35`, `app/.../dictate/DictateRecordingAnimation.kt:25-29`
- `app/.../dictate/ui/DictateSmartbarUi.kt:354-393`, `app/.../dictate/ui/LegacyDictateLayout.kt:593-610`
- Issues: #88 (floating button), #238 (recording animation), #253 (Aurora/Lattice), #230
  (freeform voice command)
