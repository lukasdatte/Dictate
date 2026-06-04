package net.devemperor.dictate.state.insertion

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import net.devemperor.dictate.database.entity.InsertionSource
import net.devemperor.dictate.state.layout.EnterButtonRole

/**
 * Data types for the single insertion subsystem.
 *
 * The [InsertionService] is the sole owner of every write to the host
 * {@link InputConnection}. These types describe *what* to write and *how*;
 * the service and its injected collaborators decide the mechanics.
 *
 * @see net.devemperor.dictate.state.insertion.InsertionService
 * @see docs/architecture (single-insertion-service) — Pipeline path is the
 *   reference behaviour the other paths were unified onto.
 */

/** Which InputConnection channel actually carried the write. */
enum class Target {
    /** The currently focused (live) editor. */
    LIVE,

    /**
     * The InputConnection captured at click time (resend path). Android does
     * not synchronously invalidate IC objects on focus change, so a captured
     * handle often still accepts writes for the original field.
     */
    CAPTURED,
}

/**
 * An InputConnection + its paired EditorInfo. Used both as the live target
 * (from [IcProvider.live]) and as the captured target carried by a resend
 * request.
 */
data class HostTarget(
    val ic: InputConnection,
    val editor: EditorInfo?,
)

/**
 * Per-caller behaviour switches. The three named presets reproduce the
 * historical behaviour of the paths they replace:
 *
 * - [PIPELINE]  — transcription commit: animated, auto-enter, host-guard,
 *                 audited, resume-on-failure. The reference behaviour.
 * - [RESEND]    — short-press resend: like PIPELINE but no auto-enter
 *                 (recovery insert, not a new transcription).
 * - [KEYSTROKE] — space / emoji / qwertz char: instant, no auto-enter,
 *                 no host-guard (keystrokes only happen while the keyboard
 *                 is visible), no audit, no resume.
 */
data class InsertionPolicy(
    /** Char-by-char slow-output animation when InstantOutput pref is off. */
    val animate: Boolean,
    /** Append an auto-enter tick after a successful commit. */
    val autoEnter: Boolean,
    /** On total IC failure, surface focus-lost + start a resume job. */
    val resumeOnFailure: Boolean,
    /** Honour the widget host-block guard (defer to Pending-Insert). */
    val respectHostGuard: Boolean,
    /** Write the DB audit row (logTextInsertion + finalOutputText). */
    val audit: Boolean,
    /**
     * When true (resend), the live IC is used only if it is the *same* editor
     * as the captured anchor; a missing/mismatched anchor skips straight to
     * the captured IC (and then the resume fallback). When false (pipeline /
     * keystroke), the live IC is always the primary target.
     */
    val anchoredToCaptured: Boolean,
) {
    companion object {
        @JvmField
        val PIPELINE = InsertionPolicy(
            animate = true, autoEnter = true, resumeOnFailure = false,
            respectHostGuard = true, audit = true, anchoredToCaptured = false,
        )

        @JvmField
        val RESEND = InsertionPolicy(
            animate = true, autoEnter = false, resumeOnFailure = true,
            respectHostGuard = true, audit = true, anchoredToCaptured = true,
        )

        @JvmField
        val KEYSTROKE = InsertionPolicy(
            animate = false, autoEnter = false, resumeOnFailure = false,
            respectHostGuard = false, audit = false, anchoredToCaptured = false,
        )
    }
}

/**
 * A request to insert text into the host editor.
 *
 * @property text              the text to commit (may be empty).
 * @property source            DB audit classifier; `null` disables auditing
 *                             regardless of [InsertionPolicy.audit].
 * @property policy            behaviour switches (see [InsertionPolicy]).
 * @property captured          IC+editor captured at click time (resend). When
 *                             present the service tries the live IC only if it
 *                             is the *same* editor, then falls back to this
 *                             captured handle.
 * @property sessionIdOverride binds the audit row / resume job to this session
 *                             id instead of the tracker's current one.
 */
data class InsertionRequest(
    val text: String,
    val source: InsertionSource?,
    val policy: InsertionPolicy,
    val captured: HostTarget? = null,
    val sessionIdOverride: String? = null,
)

/** Outcome of an [InsertionService.insert] call. */
sealed interface InsertionResult {
    /** Text committed via the given channel. */
    data class Committed(val via: Target) : InsertionResult

    /**
     * Host-guard blocked the commit (widget visible). The caller is expected
     * to surface the Pending-Insert info-bar (the transcript text is already
     * persisted in the DB by the pipeline). No text was written here.
     */
    data object DeferredToPending : InsertionResult

    /** Both IC channels failed; focus-lost was surfaced and a resume started. */
    data object ResumedAfterFailure : InsertionResult

    /** No usable IC and no recovery configured — nothing happened. */
    data object Failed : InsertionResult
}

/**
 * A control write that is not a plain text insert. Heterogeneous host calls
 * (backspace, enter, cursor) funnelled through the same owner so no caller
 * touches the InputConnection directly.
 */
sealed interface ControlOp {
    /** Delete one char before the cursor (`deleteSurroundingText(1, 0)`). */
    data object Backspace : ControlOp

    /** Delete [before]/[after] units around the cursor (grapheme-aware backspace). */
    data class DeleteSurrounding(val before: Int, val after: Int) : ControlOp

    /**
     * Enter key resolved against the host editor: NEWLINE commits `"\n"`,
     * every other role triggers the editor action [actionId].
     */
    data class Enter(val role: EnterButtonRole, val actionId: Int) : ControlOp

    /** Physical KEYCODE_ENTER (pre-bind / no-editor-info path, WebViews). */
    data object PhysicalEnter : ControlOp

    /** Move the cursor by committing empty text at position [offset] (QWERTZ swipe). */
    data class CursorMove(val offset: Int) : ControlOp

    /**
     * Delete the current selection (`commitText("", 1)` replaces the selected
     * range with nothing). A no-op when there is no selection.
     */
    data object DeleteSelection : ControlOp
}

/**
 * Edit-bar context-menu action. [androidId] is the AOSP context-menu id passed
 * to `performContextMenuAction`. COPY/PASTE/CUT additionally have a manual
 * clipboard fallback for hosts that ignore the soft API; UNDO/REDO are
 * soft-API-only (the fallback no-ops for them) but still routed through the
 * service for single-ownership.
 */
enum class EditAction(val androidId: Int) {
    COPY(android.R.id.copy),
    PASTE(android.R.id.paste),
    CUT(android.R.id.cut),
    UNDO(android.R.id.undo),
    REDO(android.R.id.redo),
    SELECT_ALL(android.R.id.selectAll),
    ;

    companion object {
        /** Map an `android.R.id.*` context-menu id to an [EditAction], or null. */
        @JvmStatic
        fun fromAndroidId(id: Int): EditAction? = entries.firstOrNull { it.androidId == id }
    }
}
