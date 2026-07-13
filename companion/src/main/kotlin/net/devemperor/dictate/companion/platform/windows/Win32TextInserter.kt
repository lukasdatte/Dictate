package net.devemperor.dictate.companion.platform.windows

import net.devemperor.dictate.companion.domain.CompanionSettings
import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.domain.port.ClipboardPort
import net.devemperor.dictate.companion.domain.port.TextInserter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Windows text insertion: put the text on the clipboard, then press Ctrl+V for the user (ADR-0018).
 *
 * The **policy** lives here and is plain Kotlin — which is why the whole of it, every branch, is
 * exercised by the Linux test suite. Only the two `user32.dll` calls sit behind [Win32Keyboard], and
 * only those are on the Windows checklist.
 *
 * ```
 * insert(text):
 *   1. previous = clipboard.readText()          (null = the clipboard held something that is not text)
 *   2. clipboard.writeText(text)                failed → FAILED       (503; the phone keeps the text)
 *   3. no foreground window?                    → CLIPBOARD_ONLY      (200; the text is reachable)
 *   4. sendCtrlV() accepted < 4 events?         → CLIPBOARD_ONLY      (UIPI blocked us — see below)
 *   5. schedule the clipboard restore           → TYPED_CTRL_V
 * ```
 *
 * Three deliberate imperfections, none of them fixable, all of them cheaper than the alternative:
 *
 * - **The focus race.** Between reading the foreground window and injecting, the focus can move.
 *   Nothing can prevent it: `SendInput` delivers to whatever has focus *at delivery time*, not to a
 *   window handle. Accepted.
 * - **The clipboard restore is time-based**, and therefore never quite right. If the user copies
 *   something themselves inside the restore delay, a naive restore would overwrite it — so the
 *   restore happens **only if the clipboard still holds our text**. If it does not, the user has
 *   moved on and we leave them alone.
 * - **A non-text previous clipboard is not restored** (see [ClipboardPort.readText]). Putting an
 *   image back is not something a `String`-shaped port can do, and clobbering it with an empty
 *   string would be worse than leaving the dictated text there.
 */
class Win32TextInserter(
    private val clipboard: ClipboardPort,
    private val keyboard: Win32Keyboard = JnaWin32Keyboard,
    private val restoreDelayMillis: Long = CompanionSettings.DEFAULT_RESTORE_DELAY_MILLIS,
    /** Injected so the restore is deterministic in a test instead of a 800 ms sleep. */
    private val scheduler: (Long, () -> Unit) -> Unit = ::scheduleOnDaemonThread,
) : TextInserter {

    override val available: Boolean = true

    override fun insert(text: String): InsertionOutcome {
        val previous = clipboard.readText()

        if (!clipboard.writeText(text)) return InsertionOutcome.FAILED

        if (!keyboard.hasForegroundWindow()) return InsertionOutcome.CLIPBOARD_ONLY

        val accepted = keyboard.sendCtrlV()
        if (accepted < Win32Keyboard.CTRL_V_EVENT_COUNT) {
            // Windows accepted fewer events than we passed and raised nothing. That is UIPI: the
            // target window is elevated and we are not. The text is on the clipboard and one manual
            // Ctrl+V away — a partial success, not a failure, and the user is told which.
            return InsertionOutcome.CLIPBOARD_ONLY
        }

        if (previous != null) {
            scheduler(restoreDelayMillis) {
                if (clipboard.readText() == text) clipboard.writeText(previous)
            }
        }

        return InsertionOutcome.TYPED_CTRL_V
    }

    companion object {

        private val restoreExecutor: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                // Daemon: a pending clipboard restore must never keep the JVM alive after the user
                // quit the app from the tray.
                Thread(runnable, "clipboard-restore").apply { isDaemon = true }
            }
        }

        private fun scheduleOnDaemonThread(delayMillis: Long, task: () -> Unit) {
            restoreExecutor.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
        }
    }
}
