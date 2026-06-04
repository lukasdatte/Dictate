package net.devemperor.dictate.state.insertion

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import net.devemperor.dictate.database.entity.InsertionSource

/**
 * Collaborators the [InsertionService] depends on. Each isolates one
 * Android- or DB-bound concern behind a tiny interface so the service stays
 * pure Kotlin and JVM-unit-testable with hand-rolled fakes (no Robolectric).
 *
 * The concrete implementations live next to the IME service (they close over
 * `getCurrentInputConnection()`, SharedPreferences, the orchestrator binder,
 * `SessionManager`, `mainHandler`, etc.).
 */

/** Supplies the currently focused editor's IC + EditorInfo (null if none). */
fun interface IcProvider {
    fun live(): HostTarget?
}

/**
 * The widget host-block guard (B3.5 / ADR-0008 "Send-during-widget"). Returns
 * `false` when the floating widget is visible and committing would leak text
 * into the wrong host window.
 */
fun interface HostCommitGuard {
    fun canCommitToHost(): Boolean
}

/**
 * Writes [text] onto [ic]. The implementation chooses instant vs. slow-output
 * animation (InstantOutput pref) and, for the animated path, propagates a
 * failed first character as `false`. Returning `false` signals the service
 * that this IC is unusable and the next target (or the resume fallback)
 * should be tried.
 */
fun interface TextCommitter {
    fun commit(ic: InputConnection, text: String): Boolean
}

/** Executes a non-text [ControlOp] on [ic]; `false` = IC unusable. */
fun interface ControlExecutor {
    fun execute(ic: InputConnection, op: ControlOp): Boolean
}

/** Auto-enter side-effect (reads per-run override, schedules the Enter tick). */
interface AutoEnterScheduler {
    fun isActive(): Boolean
    fun schedule(text: String)
}

/**
 * DB audit of a text insertion. [captureReplaced] reads the selected text
 * *before* the commit (so the undo/audit buffer has it); [record] writes the
 * insertion + denormalised final-output row *after* a successful commit.
 */
interface InsertionAuditLog {
    fun captureReplaced(ic: InputConnection): String?
    fun record(
        text: String,
        replaced: String?,
        editor: EditorInfo?,
        source: InsertionSource,
        sessionIdOverride: String?,
    )
}

/** Last-resort recovery when both IC channels fail (resend path). */
interface RecoveryHandler {
    fun notifyFocusLost()
    fun resume(sessionId: String)
}

/**
 * The copy/paste/cut gateway. [performHostAction] tries the host's soft
 * `performContextMenuAction`; [fallback] is the manual clipboard
 * implementation for hosts (WebViews, custom editors) that ignore it.
 */
interface ClipboardGateway {
    fun performHostAction(ic: InputConnection, action: EditAction): Boolean
    fun fallback(ic: InputConnection, action: EditAction)
}
