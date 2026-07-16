package net.devemperor.dictate.core

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression tests for the Activities the IME launches from its Service
 * context.
 *
 * # The bug these pin
 *
 * The user reported that long-pressing the history button opened "the
 * overview" — the settings screen — instead of `HistoryActivity`, and that
 * the history often would not open at all.
 *
 * The listener wiring turned out to be correct. The defect is in the Intent:
 * `FLAG_ACTIVITY_NEW_TASK` **alone**, thrown from a Service context, asks the
 * system for "a task for this activity" — and since `HistoryActivity` and
 * `DictateSettingsActivity` share the package's default `taskAffinity` and
 * both are `launchMode=standard`, an existing Dictate task in Recents matches.
 * The system then brings that task forward **in whatever state the user left
 * it** rather than starting the requested Activity. If the user had ever
 * opened settings, long-pressing history resurrected the settings screen. Add
 * `FLAG_ACTIVITY_CLEAR_TOP` and the requested Activity actually gets to run.
 *
 * `openSettingsActivity` carried the identical defect but nobody could see it:
 * its target *is* the overview, so the wrong behaviour and the right one look
 * the same. The pairing launch had it too. All three are asserted here.
 *
 * The precedent for the fix is already in the codebase —
 * `PipelineNotificationCoordinator` uses `NEW_TASK or CLEAR_TOP`, which is why
 * the notification path never showed this symptom.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeActivityLaunchTest {

    private fun service(): DictateInputMethodService =
        Robolectric.buildService(DictateInputMethodService::class.java).get()

    private fun lastIntent(): Intent? =
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .nextStartedActivity

    private fun assertLaunches(intent: Intent?, target: Class<*>) {
        assertNotNull("no Activity was started at all", intent)
        assertEquals(
            "wrong Activity",
            target.name,
            intent!!.component?.className,
        )
        assertTrue(
            "NEW_TASK missing — a Service context cannot start an Activity without it",
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
        assertTrue(
            "CLEAR_TOP missing — NEW_TASK alone resurrects whatever Dictate task " +
                "is already in Recents instead of starting ${target.simpleName}",
            intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0,
        )
    }

    @Test
    fun `history long-press opens HistoryActivity, not whatever task is in Recents`() {
        service().onHistoryLongClicked()
        assertLaunches(lastIntent(), net.devemperor.dictate.history.HistoryActivity::class.java)
    }

    @Test
    fun `PC long-press opens the pairing screen while unpaired`() {
        // pc-dictation-activity: the PC-key long-press now branches on pairing — unpaired (the
        // default here, no WindowsTarget in prefs) keeps opening the pairing screen; the paired
        // branch opens PcDictationActivity (device-verified, needs a stored target).
        service().onPcLongClicked()
        assertLaunches(
            lastIntent(),
            net.devemperor.dictate.settings.WindowsPairingActivity::class.java,
        )
    }

    @Test
    fun `settings launch carries the same flags (the bug nobody could see)`() {
        // openSettingsActivity had the identical NEW_TASK-only defect, invisible
        // because its target IS the settings task: resurrecting it and starting
        // it look the same. Asserted so the fix cannot rot back.
        service().onRecordLongClicked()
        assertLaunches(
            lastIntent(),
            net.devemperor.dictate.settings.DictateSettingsActivity::class.java,
        )
    }

    @Test
    fun `history short-press falls back to the Activity before the binder arrives`() {
        // ADR-0014 splits the button: short press = in-keyboard panel, long
        // press = HistoryActivity. The panel needs the orchestrator, so in the
        // sub-second window between onCreateInputView and onServiceConnected
        // the short press degrades to the Activity rather than doing nothing.
        // `service()` has no binder, which is exactly that window.
        service().onHistoryClicked()
        assertLaunches(lastIntent(), net.devemperor.dictate.history.HistoryActivity::class.java)
    }
}
