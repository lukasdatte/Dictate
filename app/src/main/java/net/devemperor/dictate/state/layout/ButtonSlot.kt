package net.devemperor.dictate.state.layout

import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.ModuleServices

/**
 * A single button's full render contract — visibility / icon / text /
 * enabled-state / alpha / click-action — all expressed as **pure resolver
 * lambdas** against the global [DictateUiState]. Render backends iterate the
 * slots of the currently-active [LayoutMode] and evaluate each resolver per
 * render-tick.
 *
 * # Why resolvers, not direct fields?
 *
 * The legacy code mutated `view.visibility` etc. imperatively from many call
 * sites (Spec 2 §13.1 lists 27 such sites), which produced the resend-btn
 * race (Spec 2 §1.1 bug #3b). Resolvers re-derive every property **from the
 * single state snapshot** on every render — there is no scratch state to
 * drift, no "did I remember to update branch 4 of 5". A new derived
 * predicate (e.g. "show the resend btn only if last audio is < 30s old") is
 * a one-line resolver change.
 *
 * # The `actionResolver` nullable-return contract (R.3)
 *
 * The resolver returns `Action?`. `null` means "this click is structurally
 * meaningless in the current state" — the IME's click handler short-circuits
 * via `slot.actionResolver(state, services)?.let { onAction(it) }`, so the
 * orchestrator never sees a useless `DispatchOutcome.Rejected("reducer-
 * null")` and no `Unrouted` log-spam fires. This is the **first**
 * validation layer; the reducer's `null` return is the **second** layer for
 * the rare cases the resolver can't pre-detect (e.g. mid-cooldown).
 *
 * **Visibility vs `null`-action.** `visibilityPredicate` and `enabledResolver`
 * separately model "is the button on screen" and "is it tappable". A
 * `null`-action resolver is for the residual case where the button is
 * visible AND enabled but the click semantics aren't yet defined for the
 * current sub-state — extremely rare in practice.
 *
 * # Why `actionResolver` takes [ModuleServices]
 *
 * The record-button's idle-path needs to call
 * `services.audioFileFactory.allocate()` **before** dispatch (Pre-Dispatch
 * Allocation, Spec 1 §4.11 / R.2) so the reducer stays pure. The
 * 2-argument signature unifies that flow with all other resolvers — those
 * that don't need services just write `{ _, _ -> Action.X }`.
 *
 * Resolvers MUST treat `services` as **read-only for the allocator path
 * only**. Reading `services.recordingHardware.foo()` from a resolver is a
 * spec violation (Pure-Resolver garantie analog to Pure-Reducer): only
 * `services.audioFileFactory.allocate()` and the resolver's local toast +
 * log on its `IOException` are allowed pre-dispatch hardware/IO.
 *
 * @property logicalId stable enum id; the backend's
 *   `Map<LogicalButtonId, View>` knows where the View lives. A slot whose
 *   id is missing in the backend's map raises `error(...)` at render time
 *   (Spec 2 §6 silent-skip guard).
 * @property widthPolicy hint for the layout pass; the concrete MotionScene
 *   constraint already encodes the width per scene-state, but the policy
 *   stays here for SDK-free renderers (overlay-backend, tests).
 * @property visibilityPredicate `true` → `View.VISIBLE`, `false` → `View.GONE`.
 *   MUST NOT contain cooldown logic (forbidden pattern (j), Spec 2 §8.5).
 * @property iconResolver returns a drawable resource id or `null` (= leave
 *   the XML-default icon untouched).
 * @property textResolver returns a `CharSequence` (button label) or `null`.
 *   Only honoured for `MaterialButton`-typed views.
 * @property enabledResolver `false` makes the button non-clickable but
 *   still visible (cooldown / pipeline-Preparing-disable etc.).
 * @property alphaResolver float in [0..1]; conventional usage is `0.4f` for
 *   disabled and `1f` for enabled, but resolvers can shade arbitrarily.
 * @property actionResolver `(state, services) → Action?`. `null` is a
 *   silent no-op per R.3.
 * @property longClickResolver **long-press** counterpart of
 *   [actionResolver] (Spec 2 §6 / §13.2 long-click slot, behaviour
 *   groups G2 RECORD-long-press + the existing RESEND-long-press).
 *   Same `(state, services) → Action?` shape and same R.3 nullable
 *   contract — `null` means "this long-press is structurally
 *   meaningless in the current state" and the backend's long-press
 *   listener short-circuits without dispatching. Default `{ _, _ ->
 *   null }` so the (majority of) slots without a long-press behaviour
 *   need not spell it out. The backend still consumes the long-press
 *   (`OnLongClickListener` returns `true`) so a no-op resolver does
 *   not fall through to an unwanted click. Render-path-cutover.md §7
 *   A1: the RECORD 2-mode body is resolved in
 *   [net.devemperor.dictate.state.RecordingModule]'s reducer from
 *   `state.recording`, keeping this resolver a thin
 *   state→Action mapping symmetric with [actionResolver].
 *
 * @see net.devemperor.dictate.state.layout.LayoutMode
 * @see net.devemperor.dictate.state.layout.RenderBackend
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §3.2
 * @see docs/decisions/0004-ui-layout-catalog-motionlayout.md §3
 */
data class ButtonSlot(
    val logicalId: LogicalButtonId,
    val widthPolicy: WidthPolicy,
    val visibilityPredicate: (DictateUiState) -> Boolean,
    val iconResolver: (DictateUiState) -> Int? = { null },
    val textResolver: (DictateUiState) -> CharSequence? = { null },
    val enabledResolver: (DictateUiState) -> Boolean = { true },
    val alphaResolver: (DictateUiState) -> Float = { 1f },
    val actionResolver: (DictateUiState, ModuleServices) -> Action?,
    val longClickResolver: (DictateUiState, ModuleServices) -> Action? = { _, _ -> null },
)

/**
 * Width policy for a [ButtonSlot].
 *
 * The MotionScene XML carries the canonical layout width per scene-state;
 * the policy here is what non-MotionLayout consumers (overlay-backend,
 * unit-tests) use when the scene-state path doesn't apply.
 *
 * **Why a sealed hierarchy (not an enum)?** [Fixed] carries a `dp` payload
 * — an enum would force a magic-number lookup. The sealed form keeps the
 * payload typed and the other two variants free of boilerplate.
 *
 * @see net.devemperor.dictate.state.layout.ButtonSlot.widthPolicy
 */
sealed class WidthPolicy {
    /** `android:layout_width="wrap_content"`. */
    data object WrapContent : WidthPolicy()

    /** `android:layout_width="0dp"` with `match_constraint` chain weight. */
    data object FillRemaining : WidthPolicy()

    /** `android:layout_width="<dp>dp"` — explicit absolute width. */
    data class Fixed(val dp: Int) : WidthPolicy()
}
