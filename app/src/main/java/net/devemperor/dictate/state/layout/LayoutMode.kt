package net.devemperor.dictate.state.layout

/**
 * A complete render description for **one** keyboard / overlay state.
 *
 * A [LayoutMode] is a pure data structure — the set of buttons (`rows` of
 * [ButtonSlot]s), the target render backend, and optionally a MotionLayout
 * scene-state id. The [LayoutCatalog] holds the canonical instances; the
 * [KeyboardLayoutManager] picks one per render-tick via `computeLayoutMode(
 * state)`.
 *
 * **No behaviour.** Visibility / icon / action are encoded in resolvers on
 * the contained slots — the mode itself is a plain bag. This is what makes
 * the catalog testable without Android: any unit test can build a
 * [LayoutMode] in memory and assert against its resolvers.
 *
 * **`sceneStateId` is nullable on purpose.** The IME-keyboard backends use
 * MotionLayout (5 KEYBOARD modes ↔ 5 `ConstraintSet`s in Spec 2 §7); the
 * floating-overlay backend (Spec 3) has a flat static layout and skips the
 * scene-transition entirely. `null` means "no MotionLayout transition to
 * trigger" (Spec 2 §6 — `mode.sceneStateId?.let { motionLayout.transitionToState(it) }`).
 *
 * @property id stable identifier — Spec 2 §3.2 / Phase-C C-4 R.12 (`sceneStateId`
 *   moved from a `LayoutModeId.toSceneStateId()` extension to a per-instance
 *   field for OCP compliance).
 * @property backend which [RenderBackend] subtype consumes this mode — used
 *   by the manager to fan render-ticks out to the right backend(s) in the
 *   multi-backend list (R.10).
 * @property rows ordered slot rows. Render order = list order; for
 *   MotionLayout-backed modes the actual visual order comes from the
 *   ConstraintSet chain (not from this list).
 * @property sceneStateId MotionLayout scene-state-id (an Android
 *   `R.id.scene_xxx`) or `null` if this mode doesn't drive MotionLayout.
 *
 * @see net.devemperor.dictate.state.layout.LayoutCatalog
 * @see net.devemperor.dictate.state.layout.RenderBackend
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §3.2
 */
data class LayoutMode(
    val id: LayoutModeId,
    val backend: BackendType,
    val rows: List<RowDescriptor>,
    val sceneStateId: Int? = null,
) {
    /** Convenience: flat list of all slots across all rows. */
    val slots: List<ButtonSlot> get() = rows.flatMap { it.slots }
}

/**
 * One row of buttons in a [LayoutMode].
 *
 * Rows are a structural grouping for IME-keyboard layouts (action-row /
 * input-row); overlay layouts collapse into a single row. The legacy code
 * had two physical `ConstraintLayout` row-containers — those are gone in
 * the MotionLayout-flat hierarchy (L2, Spec 2 §1.1 bug #1/#2 eliminator).
 * The row concept here is purely a logical grouping kept so the layout-
 * mode definitions read row-by-row in source.
 *
 * @see net.devemperor.dictate.state.layout.LayoutMode
 */
data class RowDescriptor(
    val slots: List<ButtonSlot>,
)

/**
 * Which kind of render surface a [LayoutMode] is built for.
 *
 * The [KeyboardLayoutManager] uses this discriminator to decide which
 * [RenderBackend] in its active list should consume a given layout mode:
 *
 * - [IME_VIEW] — the KEYBOARD-modus backend (`ImeViewBackend`, Spec 2 §6 /
 *   C14) and the `ContentAreaController` (Spec 2 §4.1, R.10) both bind to
 *   IME-View resources.
 * - [OVERLAY_WINDOW] — the floating-overlay backend (Spec 3 §4 / B5)
 *   binding to a `WindowManager`-managed window.
 *
 * Adding a third surface (e.g. notification-panel overlay) adds a new
 * enum value plus a new backend; the manager's fan-out logic stays the
 * same.
 *
 * @see net.devemperor.dictate.state.layout.LayoutMode.backend
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §3.2
 */
enum class BackendType {
    /** IME-side MotionLayout. */
    IME_VIEW,

    /** Floating overlay window (Spec 3). */
    OVERLAY_WINDOW,
}
