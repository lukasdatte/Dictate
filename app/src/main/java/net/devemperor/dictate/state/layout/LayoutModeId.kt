package net.devemperor.dictate.state.layout

/**
 * Stable identifier per [LayoutMode]. Each id maps 1:1 to a [LayoutMode]
 * constant on [LayoutCatalog]; the manager's `computeLayoutMode(state)`
 * picks one of them per render-tick.
 *
 * **Why an enum (not a sealed class)?** Same reasoning as
 * [LogicalButtonId] — payload-free leaves, exhaustive `when (id)`, free
 * `entries` iterator. Adding a new layout mode (e.g. `NUMERIC_PAD`) is a
 * one-line enum edit + a matching `LayoutMode` constant in `LayoutCatalog`.
 *
 * **5 KEYBOARD ids + 1 OVERLAY id.** The five KEYBOARD ids exactly mirror
 * the MotionScene `ConstraintSet` states defined in Spec 2 §7
 * (`two_row_state` ↔ [KEYBOARD_TWO_ROW] etc.); the runtime mapping from
 * `LayoutMode.sceneStateId` to the resource id lives on the layout-mode
 * instance, not on the enum, so the enum stays Android-free for JVM unit
 * tests (K-4 compliance).
 *
 * @see net.devemperor.dictate.state.layout.LayoutMode
 * @see net.devemperor.dictate.state.layout.LayoutCatalog
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §3.2
 */
enum class LayoutModeId {
    /** Standard keyboard, two rows (default). Spec 2 §8.1. */
    KEYBOARD_TWO_ROW,

    /** Standard keyboard collapsed into a single row. Spec 2 §8.2. */
    KEYBOARD_SINGLE_ROW,

    /** Two-row layout during an active pipeline (record-btn shows progress). Spec 2 §8.3. */
    KEYBOARD_TWO_ROW_SEND_MODE,

    /** Single-row layout during an active pipeline. Spec 2 §8.3. */
    KEYBOARD_SINGLE_ROW_SEND_MODE,

    /** Tri-state layout while the user edits the reprocess queue. Spec 2 §8.4. */
    KEYBOARD_REPROCESS_STAGING,

    /**
     * 5-button overlay layout shared by WIDGET and HOVER ViewModes.
     * Concrete `LayoutMode` body is contributed by Spec 3 §3.1 in B5/C16;
     * the id is registered here so cross-spec references stay stable.
     */
    OVERLAY_5BUTTON,
}
