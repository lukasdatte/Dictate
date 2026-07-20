package net.devemperor.dictate.companion.domain

import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.domain.port.TextInserter
import net.devemperor.dictate.companion.domain.port.WindowHandle
import net.devemperor.dictate.companion.fakes.FakeForegroundWindows
import net.devemperor.dictate.companion.fakes.FakeTextInserter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both §6.3 focus paths, unit-tested with fakes (acceptance §2 criterion 8, D4.3): the
 * remember/restore fallback and the spike-success no-op — plus the restore-BEFORE-insert order that
 * is the entire point of the policy.
 */
class FocusRestorationPolicyTest {

    private val editor = WindowHandle(0xE01)
    private val windows = FakeForegroundWindows(foreground = editor)
    private val slept = mutableListOf<Long>()

    private fun policy(focusFree: Boolean = false) = FocusRestorationPolicy(
        windows = windows,
        focusFree = { focusFree },
        sleep = { slept += it },
    )

    // ── fallback path (spike undecided/failed — the shipping default) ─────────────────────────

    @Test
    fun fallback_remembersForegroundOnTrigger_andRestoresItBeforeInsert() {
        val policy = policy(focusFree = false)

        policy.onDictationTrigger()
        // The panel is now up and may have taken focus; the user's editor changed behind the scenes.
        windows.foreground = WindowHandle(0xBAD) // the panel itself

        policy.restoreBeforeInsert()

        assertEquals("restores the window remembered at trigger time, not the current one", listOf(editor), windows.focused)
        assertEquals("lets the activation settle before SendInput", listOf(FocusRestorationPolicy.DEFAULT_SETTLE_DELAY_MILLIS), slept)
    }

    @Test
    fun fallback_restoreRunsBeforeTheDelegateInsert() {
        val order = mutableListOf<String>()
        windows.onFocus = { order += "restore" }
        val delegate = object : TextInserter {
            override val available = true
            override fun insert(text: String): InsertionOutcome {
                order += "insert"
                return InsertionOutcome.TYPED_CTRL_V
            }
        }
        val policy = policy(focusFree = false)
        val wired = FocusRestoringTextInserter(delegate, policy)
        policy.onDictationTrigger()
        wired.insert("hello")

        assertEquals(listOf("restore", "insert"), order)
    }

    @Test
    fun fallback_aFailedRestoreStillInserts_withoutSettleDelay() {
        windows.focusSucceeds = false // editor window closed meanwhile / UIPI
        val delegate = FakeTextInserter()
        val policy = policy(focusFree = false)
        val inserter = FocusRestoringTextInserter(delegate, policy)

        policy.onDictationTrigger()
        inserter.insert("still delivered")

        assertEquals(listOf("still delivered"), delegate.inserted)
        assertTrue("no settle sleep for a restore the OS refused", slept.isEmpty())
    }

    @Test
    fun fallback_withNothingRemembered_insertsWithoutRestore() {
        val delegate = FakeTextInserter()
        val inserter = FocusRestoringTextInserter(delegate, policy(focusFree = false))

        inserter.insert("no trigger before insert")

        assertTrue(windows.focused.isEmpty())
        assertEquals(listOf("no trigger before insert"), delegate.inserted)
    }

    @Test
    fun fallback_reinsertRestoresAgain_sameRememberedWindow() {
        val policy = policy(focusFree = false)
        policy.onDictationTrigger()
        policy.restoreBeforeInsert()
        policy.restoreBeforeInsert() // e.g. a confirm after a review round

        assertEquals(listOf(editor, editor), windows.focused)
    }

    // ── spike-success path (focus-free panel, §6.3 step 4) ────────────────────────────────────

    @Test
    fun focusFree_neitherRemembersNorRestores() {
        val policy = policy(focusFree = true)

        policy.onDictationTrigger()
        policy.restoreBeforeInsert()

        assertTrue("a panel that never takes focus needs no restoration", windows.focused.isEmpty())
        assertTrue(slept.isEmpty())
    }

    @Test
    fun unavailablePlatform_isANoOp() {
        windows.available = false // Linux/macOS: clipboard-only insertion, nothing to restore
        val policy = policy(focusFree = false)

        policy.onDictationTrigger()
        policy.restoreBeforeInsert()

        assertTrue(windows.focused.isEmpty())
    }

    @Test
    fun decorator_reportsTheDelegatesAvailability() {
        val delegate = FakeTextInserter(available = false)
        val inserter = FocusRestoringTextInserter(delegate, policy())
        assertEquals(false, inserter.available)
    }
}
