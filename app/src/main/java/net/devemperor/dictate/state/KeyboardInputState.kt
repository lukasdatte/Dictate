package net.devemperor.dictate.state

/**
 * Sub-state owned by [net.devemperor.dictate.state.KeyboardInputModule].
 *
 * Carries information that drives the keyboard's input-related buttons —
 * currently just the Enter-button's host-editor context, which the Catalog
 * resolvers need to decide both the **icon** (Return / Send / Search /
 * Check / NEXT) AND the **action** (`commitText("\n")` vs
 * `performEditorAction(SEND)` vs `sendKeyEvent(KEYCODE_ENTER)`).
 *
 * Before this axis existed, the icon was driven by the IME-Service-side
 * `updateEnterButtonIcon(EditorInfo)` and the action was hardcoded to
 * `commitText("\n", 1)` in the catalog resolver — a structural drift
 * between Optik and Verhalten. Splitting the icon and the action across
 * two ownership boundaries made every editor that requested an action
 * (Browser GO, Chat SEND, Maps SEARCH, Form NEXT, …) buggy. The unified
 * state-driven axis closes the drift.
 *
 * **Single-source-of-truth:** Catalog `iconResolver` and `actionResolver`
 * for the ENTER slot both read [hostEditor]. They cannot disagree.
 *
 * @see HostEditorState
 * @see net.devemperor.dictate.state.KeyboardInputModule
 */
data class KeyboardInputState(
    val hostEditor: HostEditorState = HostEditorState(),
)

/**
 * Raw `EditorInfo` snapshot used by the Enter-button decision pipeline.
 *
 * Populated by `Action.KeyboardInputAction.HostEditorAttached` from
 * `DictateInputMethodService.onStartInputView(EditorInfo)`. Cleared
 * (reset to defaults with [hasEditorInfo] = false) by
 * `Action.KeyboardInputAction.HostEditorDetached` on
 * `onFinishInputView`.
 *
 * **Rohwerte are the SoT, the Role is derived.** Storing
 * `EnterButtonRole` here would force the mapper to run inside the
 * state-write path; instead `EnterButtonRole.resolveEnterRole(this)`
 * lives as a free function so the resolver / icon-resolver can compute
 * it from the same Rohwerte at consumption time. The state then has one
 * truth (the EditorInfo snapshot), and derivations stay testable in
 * isolation.
 *
 * **Why no Android-`EditorInfo` import in the state file?** The state
 * is a pure data-bag — keeping it free of `android.view.inputmethod`
 * dependencies lets JVM-only tests instantiate it without a Robolectric
 * runtime. The Android conversion happens in
 * [HostEditorMapper.from] which lives next to the IME-Service.
 *
 * @property imeActionId raw `EditorInfo.imeOptions & IME_MASK_ACTION`.
 *   Value `0` = `IME_ACTION_UNSPECIFIED` (no app-requested action).
 * @property customActionId raw `EditorInfo.actionId`. Non-zero means the
 *   app installed a custom action via `actionLabel`/`actionId`; in that
 *   case the IME must call `performEditorAction(customActionId)` rather
 *   than the imeAction.
 * @property customActionLabel raw `EditorInfo.actionLabel` — when the
 *   app supplied a custom button label (e.g. "Antworten"). Currently
 *   informational (the Enter-button does not render text from this); a
 *   future iteration could surface it.
 * @property hasNoEnterAction `true` when `EditorInfo.imeOptions &
 *   IME_FLAG_NO_ENTER_ACTION != 0`. Apps set this to opt out of
 *   action-routing even when an imeAction is declared (typically
 *   multi-line fields). The flag dominates: the Enter-button sends
 *   newline.
 * @property isMultiLine `true` when `EditorInfo.inputType &
 *   (TYPE_TEXT_FLAG_MULTI_LINE | TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0`.
 *   Multi-line dominates the same way `hasNoEnterAction` does — a SEND
 *   action on a multi-line `<textarea>` would otherwise eat the user's
 *   line break.
 * @property hasEditorInfo `false` when the IME has no current editor
 *   target (pre-bind window or `onStartInputView` got `null`). Drives
 *   the physical `KEYCODE_ENTER` fallback so the user can still type
 *   Enter even before the orchestrator has been informed about any
 *   editor.
 */
data class HostEditorState(
    val imeActionId: Int = 0, // EditorInfo.IME_ACTION_UNSPECIFIED
    val customActionId: Int = 0,
    val customActionLabel: CharSequence? = null,
    val hasNoEnterAction: Boolean = false,
    val isMultiLine: Boolean = false,
    val hasEditorInfo: Boolean = false,
) {
    companion object
}
