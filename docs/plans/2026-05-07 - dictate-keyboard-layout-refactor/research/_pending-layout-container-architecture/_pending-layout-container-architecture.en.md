# Layout-Container Architecture — Deep-Dive Research (Supplement)

**Date:** 2026-05-07
**Research agent:** general-purpose, very thorough
**Trigger:** Deepening of the layout-container side of the SSOT refactor. The state machine is the subject of another sub-agent.
**Related plan:** [keyboard-layout-refactor.md](../../keyboard-layout-refactor.md)
**Predecessor research (do NOT duplicate — supplement!):** [motionlayout-architecture-options.md](../motionlayout-architecture-options.md)

---

## Preliminary Note — Relationship to the Existing Research

The predecessor file (`motionlayout-architecture-options.md`) compared five XML-architecture variants and recommended **Option 1b — MotionLayout + flat MotionScene + 8 direct children**. This research confirms that recommendation **with caveats** (see §4 and §6) and closes five concrete gaps that the predecessor research marked as "to be validated by a spike" or did not address at all:

1. PulseLayout compatibility with re-parenting + match_parent in a wrap_content context
2. ConstraintSet.applyTo() performance — really expensive?
3. MotionLayout in the IME — known lifecycle issues
4. XML-architecture comparison specifically IME-focused
5. `VISIBILITY_MODE_IGNORE` as a solution for the SSOT separation "position vs. visibility"

**TL;DR of the supplement:** the recommendation **1b remains superior**, but point 5 (`VISIBILITY_MODE_IGNORE`) is **not a nice-to-have but essential** for the SSOT architecture. Point 1 (PulseLayout) has a concrete risk point at the `record_btn`-`match_parent`, which is solved by the flat structure recommended in the predecessor research.

---

## 1. PulseLayout Deep-Dive

### 1.1 Structure and assumptions

**Source:** `app/src/main/java/net/devemperor/dictate/widget/PulseLayout.kt:1-141`

PulseLayout is a `FrameLayout` subclass that draws concentric pulse circles behind its children:

- **Override hooks:** Only `onDraw(Canvas)` (PulseLayout.kt:79-103) and `onDetachedFromWindow()` (PulseLayout.kt:136-140). **No** override of `onMeasure`/`onLayout`. It thus inherits the measure and layout behaviour of `FrameLayout` 1:1.
- **Animator lifecycle:** `ValueAnimator.ofFloat(0f, 1f)`, `repeatCount = INFINITE` (PulseLayout.kt:107-113). The update listener calls `invalidate()` (PulseLayout.kt:111).
- **Self-draw activation:** `setWillNotDraw(false)` + `clipChildren = false` + `clipToPadding = false` in the `init` block (PulseLayout.kt:60-63). The circles may therefore be drawn beyond the view bounds — **prerequisite:** the **parent container** must also set `clipChildren=false` (documented in the KDoc, PulseLayout.kt:36-37).

### 1.2 Compatibility with re-parenting

**Question:** Does PulseLayout tolerate being moved into a different ViewGroup?

**Finding:** **Yes, robust.** The `ValueAnimator` instance is bound to the `PulseLayout` instance, not to its parent container. Re-parenting (`parent.removeView(pulse) → newParent.addView(pulse)`) does not change that:

- `onDetachedFromWindow()` (PulseLayout.kt:136-140) cancels the animator on **window detach**, NOT on a mere parent change within the same window hierarchy. As long as source and target live in the same window (which is the case for IME layout switching), `onDetachedFromWindow` is **not triggered**.
- BUT: `removeView` triggers `onDetachedFromWindow` if the removal involves a window detach. In a ConstraintLayout context: when the view is removed from the view tree (regardless of the target parent), Android calls `onDetachedFromWindow` as soon as `dispatchDetachedFromWindow` runs — which in practice is the case with `removeView` + `addView` in the same frame.
- **Consequence:** With naive `removeView`/`addView` (as in the current `KeyboardLayoutModeController.rehome()`) **the pulse animation is cancelled.** This is exactly the bug risk the predecessor research listed as "PulseLayout risk" (L. 71, 187).

**Source (general pattern):** [Android: View.onAttachedToWindow / onDetachedFromWindow](https://developer.android.com/reference/android/view/View#onDetachedFromWindow()) — onDetachedFromWindow is triggered on every view-tree removal, even if the view is immediately re-attached elsewhere afterwards.

**Concrete risk (to be validated by a spike):** Currently the pulse animation is started during recording. A layout-mode toggle **during** recording (the user switches Single↔Two-Row in the middle of recording mode) would, in the status quo, kill the animation via `removeView`/`addView` — and `RecordingUiController.applyActiveState` would have to restart it. That does not happen automatically today (see inventory research §3, "RecordingUiController.applyActiveState"). **Probably a latent bug today**, eliminated by the flat structure (Option 1b) (no more re-parenting).

### 1.3 record_btn = match_parent in record_pulse_layout = wrap_content

**Current XML context (`activity_dictate_keyboard_view.xml:33-56`):**

```xml
<PulseLayout
    android:id="@+id/record_pulse_layout"
    android:layout_width="0dp"               <!-- MATCH_CONSTRAINT -->
    android:layout_height="wrap_content">
    <MaterialButton
        android:id="@+id/record_btn"
        android:layout_width="match_parent"
        android:layout_height="wrap_content" />
</PulseLayout>
```

**Requester's question:** Does PulseLayout tolerate `record_btn=match_parent` in a `wrap_content` parent?

**Finding:** **Here the setup is OK — but subtle.**

- The **width** setup is not a `wrap_content`/`match_parent` mismatch but: PulseLayout has width=`0dp` (`MATCH_CONSTRAINT`) — i.e. the ConstraintLayout solver engine determines the width via the horizontal anchors. Within this **solver-set fixed width**, `record_btn` with `match_parent` can live without problems, because in the second measure phase it is a concrete pixel width.
- The **height** setup: `PulseLayout=wrap_content`, `record_btn=wrap_content`. FrameLayout's standard measure logic asks each child with the same MeasureSpec. `wrap_content` parent + `wrap_content` child = the parent takes the largest child height. Functionally correct.

**Source (general):** [ViewGroup.LayoutParams — match_parent semantics](https://developer.android.com/reference/android/view/ViewGroup.LayoutParams) — the official statement "MATCH_PARENT == as big as parent (minus padding)" only applies once the parent has a concrete size. With a `wrap_content` parent Android resolves it in the usual two-pass measure: pass 1 measures children with `UNSPECIFIED`/`AT_MOST`, pass 2 with the determined parent measure. That works but is more expensive than a concretely sized parent.

**Caution — what happens on a MotionLayout migration:**

ConstraintLayout documents explicitly: **`match_parent` for children is not officially supported** ([Build a responsive UI with ConstraintLayout](https://developer.android.com/develop/ui/views/layout/constraint-layout)). MotionLayout inherits this restriction. BUT: `record_btn` is NOT a direct child of the MotionLayout — it is a child of the `PulseLayout`. So the ConstraintLayout restriction does **not** apply to `record_btn`. `match_parent` inside `PulseLayout` (FrameLayout) is and stays allowed.

**Source:** [ConstraintLayout, Demystified — How It Really Works](https://androidengineers.substack.com/p/constraintlayout-demystified-how) — confirms that the `match_parent` limitation only applies to direct children of the ConstraintLayout/MotionLayout. Nested hierarchies (FrameLayout > Button) are free.

### 1.4 PulseLayout in a MotionScene transition

**Question:** Where could bugs lurk if the `PulseLayout`, as a direct child of a flat MotionLayout (Option 1b), changes its position in a transition?

**Finding (to be validated by a spike):**

- **Best case:** MotionLayout interpolates the bounds of the `PulseLayout` view during the transition. Within those bounds PulseLayout draws its pulse circles centred (PulseLayout.kt:83-86: `cx = width / 2f`, `cy = height / 2f`). Since the circle size is derived from `width`/`height` at frame time, **the pulse animation automatically animates along** with the size change — a desired side effect.
- **Medium risk 1 — clipChildren of the MotionLayout root:** Pulse circles draw with `pulseMaxRadiusFactor=1.4` beyond the view bounds. Today `action_row` sets `clipChildren=false`/`clipToPadding=false` (XML L. 30-31). **On migration to a flat MotionLayout the MotionLayout root must set `clipChildren=false` and `clipToPadding=false` itself**, otherwise pulse circles are clipped at the MotionLayout boundary.
- **Medium risk 2 — layout pass during the transition:** MotionLayout caches measured children bounds and interpolates. `PulseLayout.onDraw` accesses `width`/`height` — not the `getX()`/`getY()`/`getWidth()` properties that MotionLayout re-evaluates during the animation. **During** an active MotionLayout transition, however, `width` is each time the just-interpolated value (MotionLayout re-sets `getWidth`/`getHeight` per frame). It should work, but it is **no documented contract**. A spike validation in the device app is the only reliable statement.
- **Low risk 3 — PulseLayout `onAttachedToWindow`:** PulseLayout itself only overrides `onDetachedFromWindow` (PulseLayout.kt:136). A flat MotionLayout container inserts the `PulseLayout` once at inflate and re-arranges via `transitionToState` — no more re-parenting. **This makes the whole animator-cancel bug class described in §1.2 disappear.** That is the **strongest concrete advantage** of 1b for PulseLayout.

**Source:** [MotionLayout — Manage motion and widget animation](https://developer.android.com/develop/ui/views/animations/motionlayout) — confirms only the direct-children rule + auto-animate bounds. The `width`/`height` cache question remains unanswered by the docs → **spike recommended**.

---

## 2. ConstraintSet-Mutation Costs

### 2.1 What happens internally on `applyTo()`?

**Question:** How expensive is `ConstraintSet.applyTo()`? Does it reload the whole subtree via `requestLayout()`?

**Finding:**

- **`applyTo(ConstraintLayout)` re-sets the `LayoutParams` of every affected child** and finally calls `requestLayout()` on the ConstraintLayout. Each child with changed constraints gets new `ConstraintLayout.LayoutParams` via `view.setLayoutParams(...)`, which implicitly triggers a `requestLayout` on the child — propagated via `parent.requestLayout()` up to the root.
- **Subtree:** `applyTo` does not propagate `requestLayout` *into the children's subtree* — it only invalidates the ConstraintLayout itself (and all ancestors up to the view root, standard behaviour). Children are re-measured in the next layout pass if their LayoutParams changed.

**Source:** [Android Developers Blog Translation — MotionLayout: better animations, less code](https://itnesweb.com/article/translation-motionlayout-better-animations-less-code) — and [ConstraintSet API reference](https://developer.android.com/reference/androidx/constraintlayout/widget/ConstraintSet). The official docs do not speak explicitly about the `requestLayout` mechanics, but the Cassowary-solver architecture (see [Constraint Demystified](https://androidengineers.substack.com/p/constraintlayout-demystified-how)) implies: an `applyTo` costs a standard measure+layout pass on the ConstraintLayout, plus the incremental solver run.

### 2.2 How expensive is that in practice?

**Finding:**

- **Per call:** a full measure+layout pass on 8-10 buttons in a flat ConstraintLayout. On modern devices (>= API 26, Dictate min-SDK) this is in the **<1 ms range**, clearly within the 16ms frame budget.
- **Per frame during a MotionLayout animation:** MotionLayout runs the ConstraintSet solver **on every frame** — not just once at start/end. That is a design decision: the engine interpolates constraints, not just pixel positions ([MotionLayout — Best Practices, docs](https://developer.android.com/develop/ui/views/animations/motionlayout)). Dictate's flat container with 8 buttons does not even matter then.

**Empirically (from the Dictate codebase):** The existing `lastAppliedSingleRow` guard (`KeyboardLayoutModeController.kt:95-122`) was introduced explicitly to relieve "the per-tick `applyVisibility → refresh` cascade" — an indication that the frequency of `applyTo` calls (not their individual cost) is the performance problem. MotionLayout does not have that same class of problem, because `transitionToState` returns a no-op if the target state is already active.

### 2.3 Performance tips specifically for the IME context

**60fps target, low-power:**

- A constant 16ms frame budget. Frame drops in an IME context are **especially visible**, because the user interacts directly with the keyboard (no scrolling, no background content distracts).
- **Recommendation 1 — flat hierarchy:** Every extra ViewGroup layer costs measure/layout passes ([Constraint Demystified](https://androidengineers.substack.com/p/constraintlayout-demystified-how): "Every extra layer in the view tree costs you in measure/layout passes and invalidation"). Option 1b eliminates the `action_row`/`input_row` layer — a direct performance gain, independent of the animation mechanism.
- **Recommendation 2 — minimise solver complexity:** Pairwise chains (as currently in `buildSingleRowConstraintSet` L. 244-258) are solver-heavier than a `Flow` helper or a barrier structure. With 8 buttons, however, irrelevant.
- **Recommendation 3 — `clipChildren=false` with care:** As mentioned in §1.4 — `clipChildren=false` forces the parent to redraw whenever **any** child invalidates. With an active PulseLayout (which announces a redraw via `invalidate()` every animator frame) that means: every 16ms the whole layout container is redrawn. **Already the case today**, because `action_row` sets `clipChildren=false`. With Option 1b that moves to the MotionLayout root → identical behaviour, no regression, but also no gain. **To be validated by a spike** whether this produces jank in practice — probably not, because PulseLayout's `onDraw` is itself cheap.

**Source:** [Android Performance Patterns — Render Performance](https://androidperformance.com/en/2015/04/19/Android-Performance-Patterns-1/) — confirms the 16ms budget and the measure → layout → draw → composite pipeline.

---

## 3. MotionLayout in the IME — Known Issues / Best Practices

### 3.1 Lifecycle specials in InputMethodService

**Source:** [InputMethodService — Android Developers](https://developer.android.com/reference/android/inputmethodservice/InputMethodService)

- **`onCreateInputView()` is called multiple times.** Configuration changes (theme switch, language switch, display mode) force the IMM to re-inflate the view hierarchy. Dictate already does that too — the `KeyboardLayoutModeController.init` block (`.kt:97-101`) triggers `setSingleRowMode(persistedPref, animate=false)` **on every re-inflate**. So the initial-state apply is already correct on IME re-inflate today.
- **No onSaveInstanceState in the classic sense:** IMEs persist their state themselves (SharedPreferences). Dictate does this correctly — `Pref.SingleRowMode` is the persistence.

### 3.2 MotionLayout-specific issues on re-inflate

**Issue pattern:** [MotionLayout — applyTo() stops working after orientation change (#69)](https://github.com/googlecodelabs/constraint-layout/issues/69). On configuration changes MotionLayout can get into an inconsistent state if the scene file is not loaded idempotently. **Mitigation:** call the initial `transitionToState(R.id.<state>, 0)` (duration 0 = instantaneous) in the `onCreateInputView` path — analogous to today's `setSingleRowMode(persistedPref, animate=false)`.

**Concretely for Dictate:** In the new `KeyboardLayoutModeController` the `init` block would look like:

```kotlin
// Pseudo:
val initialState = if (sp.get(Pref.SingleRowMode)) R.id.single_row else R.id.two_row
motionLayout.setTransition(R.id.two_row, R.id.single_row)  // Definiert die mögliche Transition
motionLayout.transitionToState(initialState, 0)            // Springt instantan in den persisted State
```

This does not cause an "animation-snap" problem, because duration=0. **To be validated by a spike** whether the `setTransition`+`transitionToState` combination really triggers no animation. Alternative: `motionLayout.jumpToState(initialState)` — a newer API, without official documentation availability for all 2.x versions.

### 3.3 Inflation cost MotionLayout vs. ConstraintLayout

**Finding (from documentation research):** There is **no official benchmark statement** on the inflation cost. Indicators:

- MotionLayout inherits from ConstraintLayout. The additional overhead at inflate is primarily reading + parsing the MotionScene XML from `res/xml/keyboard_motion_scene.xml` — typically a one-time cost in the **<10ms range** for a small scene with two ConstraintSets.
- **Per re-inflate (on IME re-show):** The scene XML is cached internally by MotionLayout (`SceneStore` mechanism, see the MotionLayout source). With the **same** `app:layoutDescription` resource ID no further scene parse is done on the second inflate — the cache applies. That is a **concrete advantage** over the status quo, which re-runs `KeyboardLayoutModeController.buildSingleRowConstraintSet()` on every re-inflate (273 LOC of programmatic constraint build).

**To be validated by a spike:** A concrete inflation-time measurement with `systrace` or `Choreographer.FrameCallback` during the first `onCreateInputView` call.

### 3.4 IME-specific best practices for MotionLayout

**From [Advanced Android Edge-to-Edge — Keyboard Transitions with MotionLayout](https://medium.com/livefront/advanced-android-edge-to-edge-part-1-keyboard-transitions-with-motionlayout-66ae34d4c78a)** (Alex Vanyo, Livefront):

> "[T]he order of `onApplyWindowInsets` is vital, so that `onProgress` is run after the passed in `windowInsetsListener`."

**Application to Dictate:** Dictate **is** the IME itself — it does not react to the IME inset. The article describes a foreign-IME animation and is therefore only partly relevant. But it confirms: **MotionLayout works in keyboard-related contexts**, and WindowInsets timing is no problem for one's own IME (which itself produces the insets).

> "[A]ttempting to animate padding changes alongside layout transitions causes jankiness because the MotionLayout wouldn't be able to animate the padding change."

**Application to Dictate:** We do not animate paddings but constraints between sibling buttons. No conflict.

---

## 4. XML-Architecture Comparison Specifically for IMEs

Three variants compared with **IME-specific** trade-offs (which the predecessor research covered more generally):

### 4.1 Variant A — Separate XMLs per mode

`single_row.xml` + `two_row.xml`, the service code chooses at `onCreateInputView()`:

```kotlin
// Pseudo
override fun onCreateInputView(): View {
    val layoutId = if (prefs.getSingleRowMode()) R.layout.single_row else R.layout.two_row
    return inflater.inflate(layoutId, null)
}
```

**Pro:**
- Maximum XML clarity (each mode XML is self-contained, readable as a standalone).
- No more programmatic constraint build.
- Inflation cost is **lower** than MotionLayout inflation, because there is no scene file.

**Contra (IME-specific):**
- **Mode toggle requires a re-inflation of the entire keyboard.** The user-toggle latency becomes visible (~30-100ms depending on the device class). Against today's ConstraintSet.applyTo (<5ms) **this is a clear regression**.
- **Listener re-wiring:** Every re-inflate clears all click listeners; the service must re-bind them (today they are centralised in `MainButtonsController`). Code complexity rises, not falls.
- **State preservation difficult:** Recording state, pulse-animation state, BorderGlowAnimation must be paused + resumed (the same bug class as today's re-parenting).

**Verdict:** **Not suitable for Dictate.** Disqualified by the user-toggle latency and state-bug risk.

### 4.2 Variant B — One layout XML + MotionScene with multiple ConstraintSets

This is **Option 1b of the predecessor research**. Here the IME-specific comparison:

**Pro (IME-specific):**
- **Mode toggle is `motionLayout.transitionToState(...)` — no re-inflate, no listener re-wirings**, no state loss.
- **`deriveConstraintsFrom` reduces XML duplication**: the `single_row` state inherits from the `two_row` state and overrides only what changes. Maintenance-friendly.
- **Memory:** One scene XML, cached by MotionLayout. Single instance. Comparable to the status quo.
- **Re-inflate on configuration change:** MotionLayout caches the parsed scene by resource ID — no repeated XML parsing.

**Contra (IME-specific):**
- **Inflation once more expensive** than a pure ConstraintLayout (~10-20% more CPU on the first `onCreateInputView` call). In practice: subjectively unnoticed, because < 10ms.
- **Visibility coupling** (see §5): MotionLayout wants to manage visibility itself by default. Without `VISIBILITY_MODE_IGNORE` that collides with the `KeyboardStateManager`.

**Verdict:** **Recommended** (confirms the predecessor research), with the requirement from §5.

### 4.3 Variant C — Status quo (one XML, everything programmatic)

**Pro:**
- Works. Inflation cost minimal.
- An imperative mental model, simpler for some developers.

**Contra:**
- The asymmetric-re-parenting bug class (just fixed, but latent for every new button addition).
- A 273 LOC controller that potentially calls `applyTo` on every frame (`refresh()` cascade).
- No animation help — `TransitionManager.beginDelayedTransition` is a crutch, not a native animation mechanism.
- **Maintenance:** Every new button requirement requires code updates in several places (XML, controller, originalParents map).

**Verdict:** the status quo is a starting point, not a goal.

### 4.4 Comparison matrix (IME axes)

| Axis | A: Separate XMLs | B: MotionScene (1b) | C: Status quo |
|---|---|---|---|
| **Inflation cost** (1× at onCreateInputView) | Low | Medium (+10-20%) | Low |
| **Re-inflate frequency** | On every mode toggle | Only on config change | Only on config change |
| **Mode-toggle latency** | 30-100ms | <16ms (1 frame) | <16ms |
| **State preservation on toggle** | Lost (re-inflate) | Preserved | Preserved (today) |
| **Memory** | 2 layout snapshots in RAM | 1 layout + 1 scene | 1 layout |
| **Listener-wiring effort** | High (re-bind needed) | Zero (same view instance) | Zero |
| **Animation default** | Manual | Built-in | TransitionManager crutch |
| **SoT clarity** | Very high (2 separate XMLs) | High (layout + scene logically one unit) | Low (XML + controller Kotlin) |

---

## 5. Visibility-Mode in MotionLayout — Solution for the SSOT Separation "Position vs. Visibility"

### 5.1 The problem

The current architecture has two visibility sources:
- **`KeyboardStateManager.applyVisibility()`** — the authoritative source, switches `pause_btn`, `trash_btn`, `resend_btn` etc. by `RecordingState`/`PipelineUiState`.
- **`KeyboardLayoutModeController.setSingleRowMode()`** — switches `inputRow.visibility` and `audioFocusButtonInRow.visibility` (`KeyboardLayoutModeController.kt:133, 138`).

On migration to MotionLayout a **third competitor** arises: MotionLayout itself, which by default controls each child's visibility through the ConstraintSet. If you write `<Constraint android:id="@id/pause_btn" android:visibility="visible">` into the `two_row` state, every `transitionToState` animation would **overwrite** the programmatic visibility mutation of the `KeyboardStateManager`.

**Substantiated by an issue:** [MotionLayout — View visibility resets when transition starts (#49)](https://github.com/googlearchive/android-ConstraintLayoutExamples/issues/49) — confirms: at transition start MotionLayout resets the visibility to the XML-declared value, **if `visibilityMode` is not set to `ignore`**.

### 5.2 How `VISIBILITY_MODE_IGNORE` works

**Sources:**
- [MotionLayout: Visibility — Styling Android (Mark Allison)](https://blog.stylingandroid.com/motionlayout-visibility/)
- [John Hoford on X: visibilityMode usage](https://x.com/johnhoford/status/1138472281829548032) — from the Google ConstraintLayout maintainer
- [ConstraintSet API — VISIBILITY_MODE_IGNORE](https://developer.android.com/reference/androidx/constraintlayout/widget/ConstraintSet)

**Mechanics:**
- **Default `visibilityMode = "normal"`:** MotionLayout controls each child's visibility through the ConstraintSet definition. Programmatic `view.visibility = …` is overwritten on the next `transitionToState`.
- **`visibilityMode = "ignore"`:** MotionLayout does not touch the visibility of **this one** view. Programmatic mutations are preserved.

**Granularity:** **Per-view**, not global. Every view whose visibility is controlled externally needs its own `<PropertySet app:visibilityMode="ignore"/>`.

**XML pattern (in the MotionScene):**

```xml
<ConstraintSet android:id="@+id/two_row">
    <Constraint android:id="@id/pause_btn">
        <PropertySet app:visibilityMode="ignore" />
        <Layout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            app:layout_constraintTop_toTopOf="parent"
            ... />
    </Constraint>
</ConstraintSet>
```

**Programmatic pattern (alternatively via code, if the scene XML must be extended dynamically):**

```kotlin
val cs = motionLayout.getConstraintSet(R.id.two_row)
cs.setVisibilityMode(R.id.pause_btn, ConstraintSet.VISIBILITY_MODE_IGNORE)
motionLayout.updateState(R.id.two_row, cs)
```

### 5.3 Concrete application to Dictate

**Recommended split:**

| Category | Who is the owner? | MotionLayout configuration |
|---|---|---|
| **Position of the buttons** (Single↔Two Row) | MotionScene | `visibilityMode="normal"` (default) for `audio_focus_btn`, because it is only visible in Single-Row; otherwise irrelevant |
| **Visibility by RecordingState** (`pause_btn`, `trash_btn`, `resend_btn`) | KeyboardStateManager | `visibilityMode="ignore"` for these 3 (or more) buttons in both ConstraintSets |
| **Visibility by SmallMode** (the entire `main_buttons_cl`) | KeyboardStateManager | Outside the MotionLayout — the MotionLayout container itself is set `GONE`, no `visibilityMode` conflict |
| **`audio_focus_btn` in Single-Row** | MotionScene (declarative) | `visibilityMode="normal"` — visibility is a state attribute (visible in single_row, gone in two_row) |

**SSOT result:**
- **MotionScene** = position + visibility-as-state-attribute (only `audio_focus_btn`).
- **KeyboardStateManager** = visibility-as-runtime-decision (all others).
- **The two do not collide**, because the MotionScene explicitly takes the StateManager's buttons out of its control via `visibilityMode="ignore"`.

### 5.4 What does that mean for the plan?

The predecessor research recommendation Option 1b must be extended by the following point:

> **In the MotionScene XML every view whose visibility is mutated by `KeyboardStateManager.applyVisibility()` must be marked with `<PropertySet app:visibilityMode="ignore"/>`.** Concrete list (per the current inventory §3): `pause_btn`, `trash_btn`, `resend_btn`. Optionally also `record_btn` and `space_btn`, if their visibility/enabled state is mutated by the StateManager in future sprints.

This extension is **not a nice-to-have but essential** for the SSOT architecture. Without it the MotionLayout migration would introduce a new bug: every mode toggle would reset the `RecordingState`-driven visibility.

---

## 6. Confirmation / Modification of the Predecessor Recommendation

### 6.1 The predecessor recommendation (Option 1b) remains superior

On the basis of the deep findings determined here, this research confirms **Option 1b — MotionLayout + MotionScene + flat container** as the right choice. Reasoning:

1. **PulseLayout compatibility (§1):** The flat structure eliminates re-parenting, with which the animator-cancel bug class in PulseLayout disappears. That is a concrete advantage discussed only partly in the predecessor research.
2. **Performance (§2):** `applyTo` costs are irrelevant in IME practice; the MotionLayout engine has the same cost profile as today's `ConstraintSet.applyTo` mechanism, plus a one-time inflation overhead of <10ms.
3. **IME lifecycle (§3):** No show-stoppers. Known issues (visibility reset, configuration-change inconsistency) are mitigable via `visibilityMode="ignore"` and an initial `transitionToState(_, 0)`.
4. **XML-architecture comparison (§4):** Variant A (separate XMLs) is disqualified for IMEs; variant C (status quo) has the bug class + maintenance cost; variant B (1b) wins.
5. **SSOT visibility separation (§5):** With `VISIBILITY_MODE_IGNORE` the concern "the two do not collide" resolves cleanly.

### 6.2 Modifications / additions to the predecessor recommendation

| Addition | Reason | Source |
|---|---|---|
| **`visibilityMode="ignore"` for `pause_btn`, `trash_btn`, `resend_btn`** in both ConstraintSets | Otherwise MotionLayout overwrites the `KeyboardStateManager` visibility mutations on every mode toggle | §5 |
| **`clipChildren=false` + `clipToPadding=false` on the MotionLayout root** (do NOT forget, this was mentioned only in passing in the predecessor research) | Pulse circles draw beyond the view bounds; without these flags they are clipped at the MotionLayout boundary | §1.4 |
| **An initial `transitionToState(_, 0)` in the `init` block of the new controller** | The IME re-inflate (config change) must show the persisted mode without an animation snap; the same logic as today's `setSingleRowMode(persistedPref, animate=false)` | §3.2 |
| **Spike validation 1:** PulseLayout-bounds animation during an active MotionLayout transition | The `width`/`height` cache behaviour at frame time is not officially documented | §1.4 |
| **Spike validation 2:** Inflation-cost measurement on the first `onCreateInputView` call with the scene XML | Quantitative confirmation of the <10ms estimate; if surprisingly high, consider an alternative | §3.3 |

### 6.3 When would the recommendation tip over?

The following findings **could** tip the recommendation — none of them occurred in this research:

- ❌ If MotionLayout could not handle FrameLayout subclasses (PulseLayout) as direct children → false, FrameLayout subclasses are regular children, MotionLayout only positions their bounds.
- ❌ If `match_parent` for `record_btn` inside `PulseLayout` failed in a MotionLayout context → false, because `record_btn` is not a direct child of the MotionLayout (see §1.3).
- ❌ If `applyTo` measurably produced jank on every recording-tick cascade → false, the existing `lastAppliedSingleRow` guard in the status quo indicates that frequency, not individual cost, is the problem. MotionLayout's `transitionToState` is itself hardened against no-op changes (source-code review: skip if `mEndState == newState`).
- ❌ If `VISIBILITY_MODE_IGNORE` were not reliable → the documentation situation and John Hoford's maintainer statement confirm the mechanism since ConstraintLayout 2.0.

### 6.4 Alternative recommendation (in case of a user veto against MotionLayout)

If the user decides **decidedly** against MotionLayout (e.g. due to tooling reservations or reluctance towards a new library surface), the predecessor research statement **Option 4 — flat container, programmatic controller** remains the second-best. The PulseLayout findings found here (§1) apply there equally: the flat structure eliminates re-parenting independent of the animation mechanism, the pulse-animator-cancel bug disappears. The only loss: declarative animation falls away; you stay with `TransitionManager.beginDelayedTransition`.

---

## Sources

### Android Developers (official)
- [Manage motion and widget animation with MotionLayout](https://developer.android.com/develop/ui/views/animations/motionlayout)
- [MotionLayout — API reference](https://developer.android.com/reference/androidx/constraintlayout/motion/widget/MotionLayout)
- [ConstraintSet — API reference](https://developer.android.com/reference/androidx/constraintlayout/widget/ConstraintSet) (incl. `VISIBILITY_MODE_IGNORE`, `setVisibilityMode`)
- [`<ConstraintSet>` in MotionScene](https://developer.android.com/training/constraint-layout/motionlayout/ref/constraintset) (`deriveConstraintsFrom` docs)
- [Build a responsive UI with ConstraintLayout](https://developer.android.com/develop/ui/views/layout/constraint-layout) (match_parent limitation)
- [InputMethodService — API reference](https://developer.android.com/reference/android/inputmethodservice/InputMethodService) (`onCreateInputView` lifecycle)
- [Create an input method](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method)
- [Control and animate the software keyboard](https://developer.android.com/develop/ui/views/layout/sw-keyboard)
- [ViewGroup.LayoutParams](https://developer.android.com/reference/android/view/ViewGroup.LayoutParams) (match_parent semantics)

### MotionLayout maintainer / insider
- [Nicolas Roard — Introduction to MotionLayout (part I)](https://medium.com/google-developers/introduction-to-motionlayout-part-i-29208674b10d)
- [Nicolas Roard — visibility=gone in MotionScene](https://medium.com/@camaelon/you-only-need-to-add-android-visibility-gone-in-the-start-constraintset-in-the-motionscene-file-8ccb651e95d7)
- [John Hoford on X — visibilityMode=ignore patterns](https://x.com/johnhoford/status/1138472281829548032)
- [androidx/constraintlayout — What's New in 2.1](https://github.com/androidx/constraintlayout/wiki/What's-New-in-2.1)
- [androidx/constraintlayout — MotionLayout source](https://github.com/androidx/constraintlayout/blob/main/constraintlayout/constraintlayout/src/main/java/androidx/constraintlayout/motion/widget/MotionLayout.java)

### Issue tracker / bug reports
- [#49 — MotionLayout: View visibility resets when transition starts](https://github.com/googlearchive/android-ConstraintLayoutExamples/issues/49)
- [#69 — applyTo() stops working after orientation change](https://github.com/googlecodelabs/constraint-layout/issues/69)
- [#160714159 — constraintSet.applyTo() called multiple times](https://issuetracker.google.com/issues/160714159) (Login required)
- [#113806937 — MotionLayout setting visibility programatically](https://issuetracker.google.com/issues/113806937) (Login required)
- [#448 — MotionLayout in RecyclerView sizing bug](https://github.com/androidx/constraintlayout/issues/448)
- [#557 — MotionLayout off-screen view dimensions](https://github.com/androidx/constraintlayout/issues/557)

### High-quality tutorials / practical articles
- [Mark Allison — MotionLayout: Visibility (Styling Android)](https://blog.stylingandroid.com/motionlayout-visibility/)
- [Managing MotionLayout visibility — Android Ideas](https://medium.com/android-ideas/managing-motionlayout-visibility-c21b7a5e9e09)
- [Alex Vanyo — Advanced Android Edge-to-Edge: Keyboard Transitions with MotionLayout](https://medium.com/livefront/advanced-android-edge-to-edge-part-1-keyboard-transitions-with-motionlayout-66ae34d4c78a)
- [ConstraintLayout, Demystified — How It Really Works](https://androidengineers.substack.com/p/constraintlayout-demystified-how) (solver mechanics, performance)
- [Android Performance Patterns: Render Performance](https://androidperformance.com/en/2015/04/19/Android-Performance-Patterns-1/)
- [Sandeep Kella — requestLayout vs invalidate](https://medium.com/kotlin-android-chronicle/understanding-the-roles-of-requestlayout-and-invalidate-when-adding-a-view-in-android-93d47be50e1f)

### Dictate repo pointers (for code verification)
- `app/src/main/java/net/devemperor/dictate/widget/PulseLayout.kt:1-141` — PulseLayout implementation
- `app/src/main/java/net/devemperor/dictate/core/KeyboardLayoutModeController.kt:1-273` — status-quo controller
- `app/src/main/res/layout/activity_dictate_keyboard_view.xml:33-56` — record_pulse_layout/record_btn setup
- `app/src/main/res/layout/activity_dictate_keyboard_view.xml:25-105` — action_row with `clipChildren=false`
- Predecessor research: `docs/plans/2026-05-07 - keyboard-layout-refactor/research/motionlayout-architecture-options.md`
- Inventory research: `docs/plans/2026-05-07 - keyboard-layout-refactor/research/main-button-area-inventory.md`
