# Architecture Research: Single-Row vs Two-Row Layout-Switching in the Dictate Keyboard

**Date:** 2026-05-07
**Research agent:** general-purpose, very thorough
**Trigger:** Toggle bug in the current `KeyboardLayoutModeController` — the re-parenting logic is fragile; the send button is hidden by other buttons in send mode; the resend button disappears on toggle.
**Related plan:** [keyboard-layout-refactor.md](../keyboard-layout-refactor.md)

---

## Objective of the Research

Evaluate the user idea "declare Single-Row and Two-Row in one XML, compute the diff on switch and move the buttons" against what standard Android patterns deliver. Research-only, no implementation.

**Current code:**
- `app/src/main/res/layout/activity_dictate_keyboard_view.xml` (vertical LinearLayout with two nested ConstraintLayouts: `action_row` + `input_row`)
- `app/src/main/java/net/devemperor/dictate/core/KeyboardLayoutModeController.kt` (273 LOC, re-parenting + 3 ConstraintSets, one of which is built programmatically in `buildSingleRowConstraintSet()`)

---

## 1. MotionLayout + MotionScene

### Definition

`MotionLayout` (a subclass of `ConstraintLayout` from `androidx.constraintlayout.motion.widget`) is a ViewGroup that animates between two or more `<ConstraintSet>`s. The constraint definitions live in a separate XML file, the **MotionScene** (`res/xml/`). By convention the layout only references the scene; the scene is the SoT for all constraint sets.

### Code sketch

```xml
<!-- res/layout/activity_dictate_keyboard_view.xml -->
<androidx.constraintlayout.motion.widget.MotionLayout
    android:id="@+id/main_buttons_ml"
    app:layoutDescription="@xml/keyboard_motion_scene"
    ...>
    <!-- 8 Buttons als direkte Children, ohne Constraints -->
    <com.google.android.material.button.MaterialButton android:id="@+id/record_btn" .../>
    <com.google.android.material.button.MaterialButton android:id="@+id/space_btn" .../>
    ...
</androidx.constraintlayout.motion.widget.MotionLayout>
```

```xml
<!-- res/xml/keyboard_motion_scene.xml -->
<MotionScene xmlns:android="..." xmlns:motion="...">
    <Transition motion:constraintSetStart="@id/two_row"
                motion:constraintSetEnd="@id/single_row"
                motion:duration="200" />
    <ConstraintSet android:id="@+id/two_row">
        <Constraint android:id="@id/record_btn" .../>
        <!-- alle 8 Buttons in 2-Reihen-Position -->
    </ConstraintSet>
    <ConstraintSet android:id="@+id/single_row" motion:deriveConstraintsFrom="@id/two_row">
        <!-- nur die Differenzen -->
    </ConstraintSet>
</MotionScene>
```

In Kotlin: `motionLayout.transitionToState(R.id.single_row)` / `transitionToState(R.id.two_row)`.

### Does it meet the user requirements?

- **Single Source of Truth:** Conceptually yes (the scene XML holds both states), but **it violates the letter of the wish** — the scene is an *additional* XML in `res/xml/`. That is, however, an **established idiom**: every Android developer recognises the layout↔scene coupling immediately and looks for the partner file.
- **Documentation-friendly:** Very good. Both constraint sets sit declaratively side by side as XML, with `deriveConstraintsFrom` as explicit inheritance.
- **Default render Two-Row:** Met — `motion:constraintSetStart` is the initial state.
- **Comparison-based switching:** **This is exactly the built-in core mechanism.** The engine computes the diff between the start and end ConstraintSet and animates it. Exactly the operation the user describes by hand — as a standard idiom, maintenance-free.

### Effort for Dictate / Risks

- **Re-parenting remains the fundamental problem.** [Manage motion and widget animation with MotionLayout](https://developer.android.com/develop/ui/views/animations/motionlayout) and Stack-Overflow verifications confirm: **MotionLayout works only with *direct children*.** A `LinearLayout > {action_row, input_row}` would be allowed as a child of MotionLayout — but the 8 buttons would still sit in two separate ViewGroups. It follows that **migrating to MotionLayout forces the XML into a flat structure** (all 8 buttons as direct siblings inside the MotionLayout), no more nested `action_row`/`input_row`.
- **Click listeners:** A state change triggers no view recycling — listeners are preserved. Known issue: MotionLayout can freely "catch" touch events if `<OnClick>` or `<OnSwipe>` are present in the scene. As long as we omit those in the scene and only call `transitionToState()` from Kotlin, the existing listeners are undisturbed.
- **Performance:** On a state change MotionLayout does a `requestLayout()` — comparable to the current `ConstraintSet.applyTo()`. In steady state: identical to `ConstraintLayout`.
- **PulseLayout risk:** `record_btn` currently lives *inside* `PulseLayout` (a custom ViewGroup). If `PulseLayout` stays as a wrapper inside the flat MotionLayout, a `PulseLayout` child is not a "direct child" of the MotionLayout but a child of the PulseLayout. That is OK — MotionLayout positions the `PulseLayout`, not the `record_btn` inside it.
- **Estimated LOC:** ~120 LOC new (scene XML), `KeyboardLayoutModeController` shrinks from 273 to ~40-60 LOC (only pref reading + `transitionToState()` routing + an initial-apply guard).
- **Risks:** *One* central risk — tooling for the programmatic override of the `audio_focus_btn` visibility. Currently visibility is set directly in the controller (`views.audioFocusButtonInRow.visibility = …`). MotionLayout's ConstraintSets can encode `android:visibility`, which would be the clean way. Migration means: visibility partly moves into the scene XML.

---

## 2. ConstraintSet in the Layout XML, Same File

### Definition

Idea: the root `ConstraintLayout` contains buttons plus additional `<Constraints>` or `<ConstraintSet>` sibling tags, loaded via `ConstraintSet.clone(context, R.id.set_id)`.

### Code sketch

```xml
<!-- Erhofft: -->
<ConstraintLayout>
    <Button android:id="@+id/btn_a" .../>
    <Button android:id="@+id/btn_b" .../>
    <ConstraintSet android:id="@+id/cs_two_row"> ... </ConstraintSet>
    <ConstraintSet android:id="@+id/cs_single_row"> ... </ConstraintSet>
</ConstraintLayout>
```

### Does it meet the user requirements?

**No — the idea does not work in this form.** [androidx/constraintlayout 2.1 release notes](https://github.com/androidx/constraintlayout/wiki/What's-New-in-2.1) confirm: **`<ConstraintSet>` is exclusively a child element of `<MotionScene>` (`res/xml/`)**. Inside a layout XML (`res/layout/`) as a child of `<ConstraintLayout>` it is not a recognised element — the LayoutInflater would try to inflate a view from it and fail.

What *partly* works: there is a helper class `androidx.constraintlayout.widget.Constraints` that serves as an XML container for pure constraint definitions. But it is *not* used to declare multiple sets in *one* layout file — it is a one-per-file variant.

### Effort

**Dead end.** This variant does not exist as a supported idiom.

---

## 3. Layout-File Referencing Instead of a Programmatic ConstraintSet

### Definition

`ConstraintSet().apply { clone(context, R.layout.alt_layout) }` loads the constraint definitions from *a separate layout XML*. The alt-layout XML contains the *same children with the same IDs* but different constraints. On `applyTo(layout)` the constraints are applied to the existing children of the original layout — **the alt-layout is never inflated**, it only serves as a constraint template.

### Code sketch

```xml
<!-- res/layout/keyboard_buttons_two_row.xml -- defaults, im Activity inflated -->
<ConstraintLayout android:id="@+id/buttons_cl">
    <Button android:id="@+id/record_btn" app:layout_constraintTop_toTopOf="parent" .../>
    ...
</ConstraintLayout>

<!-- res/layout/keyboard_buttons_single_row.xml -- nur als ConstraintSet-Schablone -->
<ConstraintLayout>
    <Button android:id="@+id/record_btn" app:layout_constraintStart_toEndOf="@id/trash_btn" .../>
    ...
</ConstraintLayout>
```

```kotlin
val csSingleRow = ConstraintSet().apply { clone(context, R.layout.keyboard_buttons_single_row) }
csSingleRow.applyTo(buttonsCl)
```

### Does it meet the user requirements?

- **Single Source of Truth:** **Violation** — the user wants *one* XML, this would be two.
- **Documentation-friendly:** Good on its own — both XMLs are fully readable.
- **Default render Two-Row:** Met — the default XML is inflated.
- **Comparison-based switching:** Met — `applyTo()` implicitly computes the difference.
- **Re-parenting:** Does *not* help against re-parenting. The alt-XML can only set constraints for buttons that actually exist in the default layout and live in the *same* ConstraintLayout parent. For buttons that are in a *different* parent in the default XML (`input_row` vs `action_row`), `applyTo()` would silently ignore them.

### Effort

- ~50 LOC new layout XML, ~20 LOC less Kotlin (no more `buildSingleRowConstraintSet()`).
- **But:** the very first user wish ("only one XML") is already violated. The pattern therefore loses weight.

---

## 4. One Container, No Re-Parenting (Flat Structure)

### Definition

The two nested `ConstraintLayout`s (`action_row`, `input_row`) are **eliminated**. All 8 buttons are direct children of a single `ConstraintLayout` (or `MotionLayout`). The "two rows" arise purely from constraints — no more ViewGroup boundaries. Switching = ConstraintSet apply *without* re-parenting.

### Code sketch

```xml
<!-- 8 Buttons, alle direkte Geschwister -->
<ConstraintLayout android:id="@+id/buttons_cl"
                  android:layout_height="wrap_content">
    <Button android:id="@+id/record_btn"
            app:layout_constraintTop_toTopOf="parent"
            app:layout_constraintStart_toStartOf="parent" .../>
    <Button android:id="@+id/space_btn"
            app:layout_constraintTop_toBottomOf="@id/record_btn"  <!-- Two-Row default: 2. Zeile -->
            app:layout_constraintStart_toStartOf="parent" .../>
    <!-- ... -->
</ConstraintLayout>
```

The Single-Row CS positions *all* 8 buttons with `app:layout_constraintTop_toTopOf="parent"` and one continuous Start-toEndOf chain → a single row.

### Does it meet the user requirements?

- **Single SoT:** When combined with MotionScene or a programmatic CS build: fully.
- **Documentation-friendly:** Very good — no more "movables vs natives" asymmetry; every button is a free atom.
- **Default render Two-Row:** Met — the default XML constraints are the Two-Row position.
- **Comparison-based switching:** Met — and elegant, because **no more re-parenting**. `ConstraintSet.applyTo()` suffices.
- **Height effect:** The container height (`wrap_content`) follows the tallest occupied row — Two-Row → 2× button height + margin, Single-Row → 1× button height. That is *desired* behaviour (the UI changes visibly).

### Effort

- **Structural layout refactor** — `action_row` and `input_row` are root containers for the `record_pulse_layout` wrapping and the `marginBottom` margin. Both must be redistributed.
- ~60 LOC XML rebuild, **`KeyboardLayoutModeController` shrinks drastically** (no `originalParents`, no `rehome()`, no 3-CS logic). Estimated: 273 LOC → 60-80 LOC.
- **Risks:**
  - **The "asymmetric re-parenting" bug class disappears entirely.** That is the root of the bug just fixed.
  - PulseLayout must remain as a wrapper; only its constraint position moves, not the inner `record_btn`. Works.
  - `input_row` had `layout_marginBottom="16dp"` — that must be re-set as a margin on the bottom anchor of the ButtonsCl container (or per ConstraintSet per state).

### Assessment

**The strongest structural gain of all options.** It not only fixes the current problem but *makes an entire bug class impossible*.

---

## 5. Other Approaches

### `Flow` helper (ConstraintLayout 2.0+)

[Flow API reference](https://developer.android.com/reference/androidx/constraintlayout/helper/widget/Flow): a virtual helper that chains referenced views horizontally/vertically, with `wrapMode` (`none` / `chain` / `aligned`). **Can change the row count dynamically.**

```xml
<androidx.constraintlayout.helper.widget.Flow
    android:id="@+id/buttons_flow"
    app:constraint_referenced_ids="trash_btn,record_btn,space_btn,pause_btn,backspace_btn,enter_btn,resend_btn,audio_focus_btn"
    app:flow_wrapMode="chain"
    app:flow_maxElementsWrap="8"  <!-- single-row: 8, two-row: 4 -->
    .../>
```

**Meets user requirements?** Single SoT fully — *one* XML, only the `flow_maxElementsWrap` attribute is switched programmatically between 4 and 8. **But:** Flow helpers distribute children evenly — fine-grained per-button control (e.g. "space_btn stretches" + custom margins) is limited. For Dictate's use case (buttons with different widths / visibility) probably **too little control**.

### `Group` helper

Visibility sync only. **Does not help here** — no layout restructuring.

### `Barrier` helper

A virtual guideline for variable width. Could help with details (e.g. "all pause buttons end on a common line"), but **not the main path** for Two-Row→Single-Row.

### ViewStub-based lazy inflation

Unsuitable — a ViewStub can be inflated only **once** per instance. Switching multiple times would require manual view-replacement logic, which structurally reproduces exactly the re-parenting problem.

### Custom ViewGroup with its own `onMeasure`/`onLayout` logic

Maximum control, **maximum effort**. Over-engineered for 8 buttons in 2 modes. Disqualified.

### `<merge>` + alternative layout files (`layout-port`, `layout-land`)

The resource-qualifier system only switches on a configuration change (orientation, language). A user toggle is *not* a configuration trigger → **disqualified**.

---

## 6. Assessment of the User Idea "Both Layouts in One XML"

### The idea, sharply assessed

> "Both layout variants in one XML, on switch compute the difference and move the buttons."

**This idea is exactly the description of MotionLayout — as a concept.** The algorithm the user imagines ("compare, move") is exactly what `ConstraintSet.applyTo()` and MotionLayout's transition engine do internally.

It collides, however, with the **literal** wish ("everything in *one* file"): an Android layout XML in `res/layout/` allows **no** second ConstraintSet definition as a child element of the root layout. That is a hard technical limit of the layout inflater (see point 2).

### Solution space

| User wish | Feasible? | How |
|---|---|---|
| "Exactly one XML" literally | **No** | The inflater does not understand `<ConstraintSet>` siblings in the layout XML |
| "One logically cohesive unit made of two XMLs" | **Yes** | Layout + MotionScene (point 1) |
| "Two layout XMLs, one as default + one as CS template" | **Yes** | `ConstraintSet.clone(context, R.layout.alt)` (point 3) |
| "One flat XML without row containers, both states in code" | **Yes** | Point 4, possibly combined with MotionScene |
| "One flat XML + Flow helper with dynamic `maxElementsWrap`" | **Yes**, but little detail control | Point 5 / Flow |

### Idiom match

**MotionLayout/MotionScene is the official idiom for "declarative constraint states + automatic diff switching"** — exactly what the user describes in words. The fact that there is a scene XML as a partner file is a deliberate architectural decision by Google, not a shortcoming.

---

## A. Comparison Table

| Option | Single XML? | Re-parenting needed? | "Asymmetric" bug class possible? | Animation-capable? | Migration | Recommendation |
|---|---|---|---|---|---|---|
| **0. Status quo** (3 ConstraintSets, re-parenting) | Yes (1 layout) | **Yes** | **Yes** (just fixed!) | Manual via TransitionManager | — | ★ |
| **1. MotionLayout + MotionScene (nested rows)** | Partly (layout + scene) | **Yes** (rows as nested → MotionLayout does not reach in) | Yes | Yes, native | M | ★★ |
| **1b. MotionLayout + MotionScene (flat, 8 direct children)** | Partly (layout + scene, but idiomatically one unit) | **No** | **No** | Yes, native, declarative | L | **★★★★★** |
| **2. Multiple `<ConstraintSet>` in one layout XML** | n/a | n/a | n/a | n/a | — | (not supported) |
| **3. `ConstraintSet.clone(context, R.layout.alt)`** | No (2 layouts) | **Yes** (same bug remains) | Yes | Manual via TransitionManager | S | ★★ |
| **4. Flat container, no re-parenting (programmatic as today)** | Yes (1 layout) | **No** | **No** | Manual via TransitionManager | M | ★★★★ |
| **5a. Flow helper (`flow_maxElementsWrap`)** | Yes (1 layout) | **No** | **No** | Limited | S | ★★ (too little control) |
| **5b. Custom ViewGroup** | Yes | **No** | n/a | Self-written | XL | ★ |

---

## B. Recommendation

**Option 1b — MotionLayout + MotionScene with a flat button structure.** This variant combines the two strongest levers of both worlds: the structural elimination of the `action_row`/`input_row` containers (point 4) makes the "asymmetric re-parenting" bug class mathematically impossible, and MotionScene gives the user the declarative, documentation-friendly SoT defined in exactly one logical place that they are looking for (point 1). The imperative `KeyboardLayoutModeController` construct (273 LOC with an `originalParents` map, `rehome()`, programmatic `buildSingleRowConstraintSet()`) reduces to ~30-40 LOC of pure `transitionToState()` routing plus an initial-apply guard. Animation behaviour becomes correct as a by-product — no more TransitionManager trick needed. The only formal violation of "everything in one XML" is the scene XML as a partner file, which is the recognised Android idiom and immediately recognisable as a logical unit to any Android developer on first look at the layout (the `app:layoutDescription` attribute shows the coupling explicitly). Trade-off: the migration is L (large), because both the XML structure and the controller have to be rebuilt — but precisely this rebuild is the sustainable gain.

If the user strictly wants to avoid MotionLayout (e.g. due to API-level concerns or tooling reservations), **Option 4** (flat container, imperative controller as today, but without re-parenting) would be the second-best path — the same bug-class elimination, less documentation comfort, but a minimal idiom jump away from the status quo.

---

## C. Risks / What the User Must Decide

1. **The "one XML" doctrine: literal or conceptual?** If literal: only Option 4 or 5a satisfies it. If conceptual ("one logical unit, clearly linked"): Option 1b is superior.

2. **PulseLayout in the flat MotionLayout:** On-device verification needed — `record_btn` as a child of `PulseLayout` as a child of MotionLayout. Theoretically unproblematic (MotionLayout only sees the `PulseLayout` as a direct child), but the pulse animation could interact with MotionLayout's layout pass. **A concrete risk, not researchable without a device test.**

3. **Visibility moving into the scene XML:** MotionLayout's idiomatic way is to encode visibility per `<Constraint android:visibility="…">` per state. The current code sets visibility imperatively. On migration to MotionLayout it must be decided: does visibility stay imperative (in the controller) or declarative (in the scene)? Both work; the idiomatic way is declarative.

4. **The `audio_focus_btn` edit-bar variant** is already in a separate container (`edit_buttons_keyboard_ll`) and is *not* touched by the layout-mode logic. The migration only affects the `main_buttons_cl` buttons. No risk here.

5. **Touch capture by MotionLayout:** A known issue — if `<OnClick>` or `<OnSwipe>` are defined in the scene, MotionLayout can "catch" touches. **Mitigation:** omit such tags in the scene, set all click listeners from Kotlin as today, trigger transitions only via `transitionToState()`.

6. **API level / library version:** ConstraintLayout 2.0+ brings Flow + most MotionLayout features. The current `compileSdk` and `constraintlayout` dependency must be checked — the research result: all discussed features have been stable since 2.0, which should be the case for Dictate.

---

## Sources

- [Manage motion and widget animation with MotionLayout — Android Developers](https://developer.android.com/develop/ui/views/animations/motionlayout)
- [MotionScene reference — Android Developers](https://developer.android.com/reference/androidx/constraintlayout/motion/widget/MotionScene)
- [`<ConstraintSet>` in MotionScene — Android Developers](https://developer.android.com/training/constraint-layout/motionlayout/ref/constraintset)
- [ConstraintSet API reference — Android Developers](https://developer.android.com/reference/androidx/constraintlayout/widget/ConstraintSet)
- [Build a responsive UI with ConstraintLayout — Android Developers](https://developer.android.com/develop/ui/views/layout/constraint-layout)
- [What's New in ConstraintLayout 2.1 — androidx/constraintlayout Wiki](https://github.com/androidx/constraintlayout/wiki/What's-New-in-2.1)
- [Flow helper API reference — Android Developers](https://developer.android.com/reference/androidx/constraintlayout/helper/widget/Flow)
- [Reuse layouts with `<include>` and `<merge>` — Android Developers](https://developer.android.com/develop/ui/views/layout/improving-layouts/reusing-layouts)
- [ViewStub API reference — Android Developers](https://developer.android.com/reference/android/view/ViewStub)
- [Introduction to MotionLayout (part I) — Nicolas Roard, Google Developers](https://medium.com/google-developers/introduction-to-motionlayout-part-i-29208674b10d)
- [Introducing Constraint Layout 2.0 — Sean McQuillan, Android Developers](https://medium.com/androiddevelopers/introducing-constraint-layout-2-0-9daa3e99995b)
