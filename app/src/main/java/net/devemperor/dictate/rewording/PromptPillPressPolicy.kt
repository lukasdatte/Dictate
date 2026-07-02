package net.devemperor.dictate.rewording

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
 * Behaviour matrix:
 *
 * | press | textOnlyDisabled | outcome          |
 * |-------|------------------|------------------|
 * | SHORT | false            | [ACTIVATE]       |
 * | SHORT | true             | [IGNORE]         |
 * | LONG  | false            | [EDIT]           |
 * | LONG  | true             | [APPLY_DISABLED] |
 *
 * The `textOnlyDisabled == false` column is exactly the pre-existing
 * behaviour (short = run/queue, long = edit); only the greyed column is
 * new.
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
}

/**
 * @see PromptPillAction for the outcome semantics.
 */
object PromptPillPressPolicy {

    @JvmStatic
    fun decide(press: PromptPillPress, textOnlyDisabled: Boolean): PromptPillAction =
        when (press) {
            PromptPillPress.SHORT ->
                if (textOnlyDisabled) PromptPillAction.IGNORE else PromptPillAction.ACTIVATE
            PromptPillPress.LONG ->
                if (textOnlyDisabled) PromptPillAction.APPLY_DISABLED else PromptPillAction.EDIT
        }
}
