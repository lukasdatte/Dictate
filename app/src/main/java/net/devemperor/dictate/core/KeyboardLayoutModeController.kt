package net.devemperor.dictate.core

import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.transition.TransitionManager
import net.devemperor.dictate.R
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get

/**
 * Owns the two-row vs. single-row layout-mode switching of the main keyboard area.
 *
 * Lifecycle (Plan-Z. 207-212):
 *  - [init] captures the freshly inflated default constraints from `action_row`
 *    and `input_row`, builds the synthetic `csSingleRow`, and applies the
 *    persisted [Pref.SingleRowMode] without animation. This guarantees the
 *    very first frame shows the right layout — re-inflate (rotation,
 *    `onCreateInputView`) cannot cause an animation snap because every
 *    instance starts at this initial-apply gate.
 *  - [setSingleRowMode] is the user-toggle entry point — animated when
 *    [Pref.Animations] permits, instantaneous otherwise.
 *  - [refresh] re-applies the current persisted mode and is the seam used by
 *    [KeyboardStateManager.applyVisibility] so visibility recomputes (e.g.
 *    QWERTZ → MAIN_BUTTONS) restore the single-row layout without an explicit
 *    second call site.
 *
 * Single-Responsibility: [KeyboardStateManager] is the deterministic visibility
 * calculator (its KDoc explicitly forbids layout-mode work); the layout-mode
 * controller therefore lives in its own class. The bridge from one to the
 * other is a single setter-injected reference + a single call at the tail of
 * `applyVisibility()`.
 *
 * Quality-Gate W2 (Plan-Z. 173-179): three [ConstraintSet]s, not two — the
 * input-row buttons lose their default constraints when re-parented into
 * `action_row`, so the two-row restore needs a captured snapshot of the
 * original `input_row` chain to revert to.
 */
class KeyboardLayoutModeController(
    private val views: KeyboardViews,
    private val sp: SharedPreferences
) {

    /** Default constraints captured from the inflated `action_row`. */
    private val csTwoRowAction = ConstraintSet().apply { clone(views.actionRow) }

    /** Default constraints captured from the inflated `input_row`. */
    private val csTwoRowInput = ConstraintSet().apply { clone(views.inputRow) }

    /**
     * Snapshot of each movable's *original* parent ViewGroup, captured at
     * controller construction. Used by [rehome] so the two-row revert
     * restores every child to its declared row — not blindly to `input_row`.
     *
     * Bug-Fix 2026-05-07: the previous implementation used a single target
     * (`if (toSingleRow) action_row else input_row`) which was correct for
     * the forward toggle but stuffed `action_row`'s natives
     * (`record_pulse_layout`, `resend_btn`, `backspace_btn`) into `input_row`
     * on revert. The result was a visually-empty `action_row` and an
     * over-stuffed `input_row` with three children that had no constraints
     * in the applied `csTwoRowInput`. This map preserves each child's
     * original home so the revert is symmetric to the forward step.
     */
    private val originalParents: Map<View, ViewGroup> = listOf(
        views.recordPulseLayout,
        views.spaceButton,
        views.backspaceButton,
        views.enterButton,
        views.resendButton,
        views.trashButton,
        views.pauseButton
    ).associateWith { it.parent as ViewGroup }

    /**
     * Synthetic single-row chain: `[trash] [recordPulse] [space] [pause]
     * [backspace] [enter] [resend] [audio]`. Built programmatically against
     * `action_row`'s id space — only valid once the input-row buttons have
     * been re-parented into `action_row` (see [rehome]).
     */
    private val csSingleRow = buildSingleRowConstraintSet()

    /**
     * Last applied mode — guards [setSingleRowMode] so the per-tick
     * `applyVisibility → refresh` cascade does not re-apply identical
     * `ConstraintSet`s and re-trigger `requestLayout()` on every recording
     * tick. `null` means "never applied"; the `init` block sets it on the
     * first call.
     *
     * The guard only kicks in for non-animated calls. Animated calls
     * (user-toggle path) are rare enough that the small redundancy of
     * skipping a no-op transition is not worth special-casing.
     */
    private var lastAppliedSingleRow: Boolean? = null

    init {
        // Initial-Apply (Plan-Z. 209-211): re-inflate must show the persisted
        // layout on the first frame, no animation snap.
        setSingleRowMode(sp.get(Pref.SingleRowMode), animate = false)
    }

    /**
     * Switches the main button area between two-row (default) and single-row layout.
     *
     * @param enabled `true` activates the single-row layout; `false` restores two rows.
     * @param animate `true` runs a [TransitionManager] fade between the two states
     *                (subject to [Pref.Animations]); `false` applies immediately.
     *
     * Plan-Z. 195-204 — Quality-Gate W4 / W10: re-parenting between
     * `ViewGroup`s yields fade-out/fade-in (the default `AutoTransition`),
     * not a movement animation. We still gate the call on [Pref.Animations]
     * so users who disabled animations get an instantaneous switch.
     */
    fun setSingleRowMode(enabled: Boolean, animate: Boolean) {
        // Performance-Guard: skip identical re-applies on the per-tick
        // `applyVisibility → refresh` path. Animated calls (user-toggle) also
        // benefit — the toggle in the service computes `next = !current`, so
        // a no-op animated apply is by construction unreachable; if it ever
        // happened we would skip a transition that has nothing to show
        // anyway, which is the right behaviour.
        if (lastAppliedSingleRow == enabled) return
        if (animate && sp.get(Pref.Animations)) {
            TransitionManager.beginDelayedTransition(rootView())
        }
        rehome(enabled)
        if (enabled) {
            csSingleRow.applyTo(views.actionRow)
        } else {
            csTwoRowAction.applyTo(views.actionRow)
            csTwoRowInput.applyTo(views.inputRow)
        }
        views.inputRow.visibility = if (enabled) View.GONE else View.VISIBLE
        // Quality-Gate Lifecycle-Asymmetrie (Plan-Z. 261): the Single-Row
        // audio button is permanently parented in `action_row`; only its
        // visibility tracks the mode. The Edit-Bar copy is independent and
        // always visible.
        views.audioFocusButtonInRow.visibility = if (enabled) View.VISIBLE else View.GONE
        lastAppliedSingleRow = enabled
    }

    /**
     * Re-applies the current persisted layout-mode value without animation.
     *
     * Called by [KeyboardStateManager.applyVisibility] after every visibility
     * recomputation. While `main_buttons_cl` is GONE (SmallMode-Vorrang) the
     * re-apply is invisible to the user but keeps the row state coherent so
     * a subsequent `SmallMode = false` shows the correct layout immediately.
     */
    fun refresh() {
        setSingleRowMode(sp.get(Pref.SingleRowMode), animate = false)
    }

    /** Scene root for [TransitionManager.beginDelayedTransition]. */
    private fun rootView(): ViewGroup = views.mainButtonsClTyped

    /**
     * Re-parents the seven movables between their original rows and `action_row`.
     *
     * Forward (`toSingleRow = true`): every movable goes to `action_row` so
     * `csSingleRow`'s chain — which references all eight ids in `action_row`'s
     * id space — finds the children present.
     *
     * Reverse (`toSingleRow = false`): every movable goes back to *its own*
     * original parent, captured once at controller construction in
     * [originalParents]. Three of the seven (`record_pulse_layout`,
     * `backspace_btn`, `resend_btn`) live in `action_row` natively; the
     * other four (`space_btn`, `enter_btn`, `trash_btn`, `pause_btn`) live
     * in `input_row`. A single-target reverse path would corrupt half of
     * them — see the [originalParents] KDoc for the bug history.
     *
     * Movement is idempotent — a no-op when the child already lives under
     * the target parent. This matters because [refresh] may run multiple
     * times per frame (e.g. content-area switch + setSmallMode in the same
     * gesture) and we must not re-add views that are already in place.
     *
     * Quality-Gate (Plan-Z. 185): the pulse **wrapper** is moved, NOT the
     * bare `record_btn` — moving only the inner button breaks
     * [net.devemperor.dictate.widget.PulseLayout]'s animation by cutting its
     * container reference. `audio_focus_btn` stays put: it is genuinely in
     * `action_row` already (XML Z. 93) and only its visibility tracks the mode.
     */
    private fun rehome(toSingleRow: Boolean) {
        for ((view, originalParent) in originalParents) {
            val target: ViewGroup = if (toSingleRow) views.actionRow else originalParent
            val currentParent = view.parent as? ViewGroup ?: continue
            if (currentParent === target) continue
            currentParent.removeView(view)
            target.addView(view)
        }
    }

    /**
     * Builds the single-row chain against `action_row`'s id space.
     *
     * Sizing strategy: every button keeps its inflated `wrap_content` width
     * **except** [R.id.space_btn], which spans the remaining horizontal space
     * (`width = 0dp` / MATCH_CONSTRAINT). All siblings are vertically
     * centered against `action_row`. The chain is pairwise-constrained
     * (Start of N → End of N-1) — same pattern as the existing edit-bar.
     */
    private fun buildSingleRowConstraintSet(): ConstraintSet {
        val cs = ConstraintSet()
        // Seed from the captured two-row action set so any unrelated views
        // (audio_focus_btn already in action_row) keep their attributes.
        cs.clone(csTwoRowAction)

        val ids = intArrayOf(
            R.id.trash_btn,
            R.id.record_pulse_layout,
            R.id.space_btn,
            R.id.pause_btn,
            R.id.backspace_btn,
            R.id.enter_btn,
            R.id.resend_btn,
            R.id.audio_focus_btn
        )

        // Clear any pre-existing horizontal constraints on each id so the
        // freshly-defined chain does not collide with the cloned defaults.
        // The vertical edges (TOP/BOTTOM) do NOT need a clear — the
        // `connect(TOP/BOTTOM, PARENT_ID, ...)` calls below overwrite them
        // unconditionally; clearing first would be no-op noise.
        for (id in ids) {
            cs.clear(id, ConstraintSet.START)
            cs.clear(id, ConstraintSet.END)
            cs.clear(id, ConstraintSet.LEFT)
            cs.clear(id, ConstraintSet.RIGHT)
        }

        // 4dp → raw px for ConstraintSet.connect(margin) which expects pixels.
        // Earlier revisions used a literal `4`, which yields ~1.3dp on 3x
        // density screens — too tight visually. The Edit-Bar XML uses 4dp,
        // so we mirror that explicitly here.
        val marginPx = (4f * views.actionRow.resources.displayMetrics.density).toInt()

        // Vertical: every button centered top-to-top / bottom-to-bottom of action_row.
        for (id in ids) {
            cs.connect(id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            cs.connect(id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        }

        // Horizontal pairwise chain.
        for (i in ids.indices) {
            val id = ids[i]
            val prev = if (i == 0) ConstraintSet.PARENT_ID else ids[i - 1]
            val next = if (i == ids.lastIndex) ConstraintSet.PARENT_ID else ids[i + 1]
            cs.connect(
                id, ConstraintSet.START,
                prev, if (i == 0) ConstraintSet.START else ConstraintSet.END,
                marginPx
            )
            cs.connect(
                id, ConstraintSet.END,
                next, if (i == ids.lastIndex) ConstraintSet.END else ConstraintSet.START,
                marginPx
            )
        }

        // space_btn fills remaining horizontal space; everything else stays
        // at its intrinsic icon-button width.
        for (id in ids) {
            if (id == R.id.space_btn) {
                cs.constrainWidth(id, ConstraintSet.MATCH_CONSTRAINT)
            } else {
                cs.constrainWidth(id, ConstraintSet.WRAP_CONTENT)
            }
            cs.constrainHeight(id, ConstraintSet.WRAP_CONTENT)
        }

        return cs
    }
}
