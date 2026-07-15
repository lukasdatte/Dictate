package net.devemperor.dictate.rewording

import net.devemperor.dictate.database.entity.PromptType

/**
 * Press-type gate for the keyboard prompt pills (the chip row above the
 * keyboard). Kept as a pure function so the "which press type does what
 * on which pill state" decision is unit-testable independently of the
 * Android view layer.
 *
 * # What a "text-only pill" is
 *
 * A *text-only* pill is a saved prompt (`PromptEntity.id >= 0`) whose
 * prompt does **not** require a text selection
 * (`PromptEntity.requiresSelection == false`). Those prompts run
 * standalone against the editor / pipeline rather than transforming a
 * selection. While a recording or pipeline is busy they are greyed out
 * (see [PromptsKeyboardAdapter] `onBindViewHolder`, driven by
 * `disableNonSelectionPrompts`).
 *
 * # Why the gate lives here and not in `isEnabled`
 *
 * The greyed state used to be `View.setEnabled(false)`. An Android view
 * with `isEnabled == false` receives **no** `MotionEvent`s, so a
 * long-press could never fire on it. To honour the user request that a
 * long-press on a greyed text-only pill still applies it, the adapter
 * keeps the pill *enabled* (so it still gets touches), renders the
 * disabled look via alpha only, and routes the short/long press through
 * this policy instead.
 *
 * Behaviour matrix (AI `PROMPT` pills — the pre-existing behaviour):
 *
 * | press | textOnlyDisabled | outcome          |
 * |-------|------------------|------------------|
 * | SHORT | false            | [ACTIVATE]       |
 * | SHORT | true             | [IGNORE]         |
 * | LONG  | false            | [EDIT]           |
 * | LONG  | true             | [APPLY_DISABLED] |
 *
 * `TEXT` pills (literal snippets, `PromptType.TEXT`) ignore `textOnlyDisabled`
 * entirely: SHORT → [ACTIVATE] (insert 1:1 in every state), LONG → [EDIT].
 * They are never greyed out, so the busy-state column does not apply.
 */
enum class PromptPillPress { SHORT, LONG }

enum class PromptPillAction {
    /** Normal short-press: run/queue the prompt (`AdapterCallback.onItemClicked`). */
    ACTIVATE,

    /** Normal long-press: open the prompt editor (`AdapterCallback.onItemLongClicked`). */
    EDIT,

    /**
     * Long-press on a greyed text-only pill: apply it anyway, exactly as
     * an idle short-press would (`AdapterCallback.onTextOnlyItemApplyRequested`).
     */
    APPLY_DISABLED,

    /** Short-press on a greyed text-only pill: inert (preserve the greyed semantics). */
    IGNORE,

    /**
     * Any press on a selection-requiring pill while PC-mode is active (§6.2). The PC selection cannot
     * be read in v1, so the pill is greyed and a press shows a hint instead of running the prompt
     * (`AdapterCallback.onSelectionUnavailableInPcMode`). Takes precedence over the busy-state matrix.
     */
    SELECTION_UNAVAILABLE_HINT,
}

/**
 * @see PromptPillAction for the outcome semantics.
 */
object PromptPillPressPolicy {

    /**
     * @param selectionUnavailable true iff PC-mode is active AND this pill requires a selection
     *   (§6.2). When true it dominates every other column: the pill is gated and any press shows a
     *   hint. Selection-free pills and recording pills are unaffected (`false`).
     */
    @JvmStatic
    fun decide(
        press: PromptPillPress,
        textOnlyDisabled: Boolean,
        pillType: PromptType,
        selectionUnavailable: Boolean = false,
    ): PromptPillAction {
        // The PC-mode selection gate wins over the pillType/busy matrix — a selection prompt simply
        // has no selection to work on while the field lives on the PC.
        if (selectionUnavailable) return PromptPillAction.SELECTION_UNAVAILABLE_HINT

        return when (pillType) {
            // Text pills insert their literal content in every state — the
            // busy-state greying never applies. Short = insert, long = edit.
            PromptType.TEXT -> when (press) {
                PromptPillPress.SHORT -> PromptPillAction.ACTIVATE
                PromptPillPress.LONG -> PromptPillAction.EDIT
            }
            // AI prompt pills keep the pre-existing press-vs-greyed matrix.
            PromptType.PROMPT -> when (press) {
                PromptPillPress.SHORT ->
                    if (textOnlyDisabled) PromptPillAction.IGNORE else PromptPillAction.ACTIVATE
                PromptPillPress.LONG ->
                    if (textOnlyDisabled) PromptPillAction.APPLY_DISABLED else PromptPillAction.EDIT
            }
        }
    }
}
