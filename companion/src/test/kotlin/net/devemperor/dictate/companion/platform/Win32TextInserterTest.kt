package net.devemperor.dictate.companion.platform

import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.fakes.FakeClipboard
import net.devemperor.dictate.companion.platform.windows.JnaWin32Keyboard
import net.devemperor.dictate.companion.platform.windows.Win32Keyboard
import net.devemperor.dictate.companion.platform.windows.Win32TextInserter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The insertion policy, every branch of it — on Linux.
 *
 * This is what the `Win32Keyboard` seam buys. `SendInput` itself cannot run here, but the decisions
 * built on its *result* can, and they are where the bugs live: the UIPI degradation, the missing
 * foreground window, the failed clipboard write, and the restore that must not clobber what the user
 * copied in the meantime. What genuinely remains unverifiable on Linux is only "does `user32.dll`
 * actually type" — Windows-checklist items 1–4.
 */
class Win32TextInserterTest {

    private val clipboard = FakeClipboard(content = "what the user had copied")
    private val keyboard = FakeWin32Keyboard()
    private val restores = mutableListOf<Pair<Long, () -> Unit>>()

    private val inserter = Win32TextInserter(
        clipboard = clipboard,
        keyboard = keyboard,
        restoreDelayMillis = 800L,
        scheduler = { delay, task -> restores += delay to task },
    )

    @Test
    fun happyPath_typesTheTextAndRestoresTheClipboard() {
        val outcome = inserter.insert("dictated text")

        assertEquals(InsertionOutcome.TYPED_CTRL_V, outcome)
        assertEquals(listOf("dictated text"), clipboard.writes)
        assertEquals(1, keyboard.sent)

        // The restore is scheduled, not immediate — the target app has to read the clipboard first.
        assertEquals(1, restores.size)
        assertEquals(800L, restores.single().first)

        restores.single().second()
        assertEquals("what the user had copied", clipboard.content)
    }

    @Test
    fun theRestoreLeavesTheUsersOwnCopyAlone() {
        inserter.insert("dictated text")

        // Inside the 800 ms the user copied something themselves. Restoring now would destroy it.
        clipboard.content = "something the user copied just now"
        restores.single().second()

        assertEquals("something the user copied just now", clipboard.content)
    }

    @Test
    fun aNonTextClipboardIsNotRestoredOver() {
        clipboard.content = null // an image, a file list — nothing a String-shaped port can put back

        val outcome = inserter.insert("dictated text")

        assertEquals(InsertionOutcome.TYPED_CTRL_V, outcome)
        assertTrue("no restore may be scheduled for a clipboard we cannot reconstruct", restores.isEmpty())
        assertEquals("dictated text", clipboard.content)
    }

    @Test
    fun aClipboardThatRefusesTheWrite_isAFailure() {
        clipboard.writable = false

        val outcome = inserter.insert("dictated text")

        // Not even the clipboard took it: there is nowhere on this PC the user could reach the text,
        // so the phone must keep it (503 → pending part).
        assertEquals(InsertionOutcome.FAILED, outcome)
        assertEquals(0, keyboard.sent)
        assertTrue(restores.isEmpty())
    }

    @Test
    fun withoutAForegroundWindow_theTextStaysOnTheClipboard() {
        keyboard.foregroundWindow = false

        val outcome = inserter.insert("dictated text")

        assertEquals(InsertionOutcome.CLIPBOARD_ONLY, outcome)
        assertEquals("dictated text", clipboard.content)
        assertEquals(0, keyboard.sent)
        // Nothing was pasted, so nothing may be restored — the text has to stay reachable.
        assertTrue(restores.isEmpty())
    }

    @Test
    fun whenUipiBlocksTheInjection_itIsClipboardOnly_neverASilentSuccess() {
        // Windows accepted fewer events than we passed and raised nothing at all. That is an elevated
        // target window (admin PowerShell, Task Manager, a UAC prompt) rejecting input from a
        // non-elevated process. Reporting TYPED_CTRL_V here would be the silent lie this check exists
        // to prevent — the user would stare at an empty window believing the text arrived.
        keyboard.acceptedEvents = 2

        val outcome = inserter.insert("dictated text")

        assertEquals(InsertionOutcome.CLIPBOARD_ONLY, outcome)
        assertEquals("dictated text", clipboard.content)
        assertTrue(restores.isEmpty())
    }

    @Test
    fun theInserterReportsItselfAvailable() {
        assertEquals(true, inserter.available)
    }

    @Test
    fun platformDetectionMatchesTheHostOs() {
        val bindings = PlatformModule.detect()

        // The whole point of the port: on a non-Windows host the app runs, it just cannot type —
        // and it says so rather than pretending (ADR-0018). On Windows the same detection must wire
        // the real bindings. Only availability is asserted on Windows: calling insert() here would
        // really type into whatever window is focused on the build machine.
        if (System.getProperty("os.name").startsWith("Windows")) {
            assertEquals(true, bindings.inserter.available)
            assertEquals(true, bindings.autostart.supported)
        } else {
            assertEquals(false, bindings.inserter.available)
            assertEquals(InsertionOutcome.FAILED, bindings.inserter.insert("anything"))
            assertEquals(false, bindings.autostart.supported)
        }
        assertNull(System.getenv("DICTATE_FORCE_WINDOWS")) // guards against a stray override in CI
    }

    private class FakeWin32Keyboard : Win32Keyboard {

        var foregroundWindow = true
        var acceptedEvents = Win32Keyboard.CTRL_V_EVENT_COUNT
        var sent = 0

        override fun hasForegroundWindow(): Boolean = foregroundWindow

        override fun sendCtrlV(): Int = sendKeySequence(JnaWin32Keyboard.CTRL_V_SEQUENCE)

        override fun sendKeySequence(events: List<net.devemperor.dictate.companion.platform.windows.KeyEventSpec>): Int {
            sent++
            // Mirrors the real UIPI degradation: Windows never accepts *more* than it was handed.
            return minOf(acceptedEvents, events.size)
        }
    }
}
