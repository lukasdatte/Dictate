@file:JvmName("HostEditorMapper")
package net.devemperor.dictate.state

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Converts an Android [EditorInfo] (handed in by the IME-Service via
 * `onStartInputView`) into the framework-independent [HostEditorState]
 * the orchestrator stores. Living next to [HostEditorState] (rather
 * than inside the data-class) keeps the state itself free of Android
 * imports so JVM-only Reducer/Resolver tests don't pull in
 * `android.view.inputmethod`.
 *
 * **`null` input → "no editor":** `onStartInputView` may legitimately
 * receive a `null` `EditorInfo` (pre-bind, restarting=true with no
 * editor). The mapper returns a default [HostEditorState] with
 * [HostEditorState.hasEditorInfo] = `false`; the reducer routes that to
 * `Effect.SendPhysicalEnter` so the user can still type Enter.
 *
 * **Top-level (not Companion-extension)** for Java-friendliness — the
 * Java-side `DictateInputMethodService` calls this as
 * `HostEditorMapper.hostEditorStateFrom(info)`.
 *
 * @see HostEditorState
 * @see Action.KeyboardInputAction.HostEditorAttached
 */
fun hostEditorStateFrom(info: EditorInfo?): HostEditorState {
    if (info == null) return HostEditorState()
    return HostEditorState(
        imeActionId = info.imeOptions and EditorInfo.IME_MASK_ACTION,
        customActionId = info.actionId,
        customActionLabel = info.actionLabel,
        hasNoEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0,
        isMultiLine = (info.inputType and (
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE
        )) != 0,
        hasEditorInfo = true,
    )
}
