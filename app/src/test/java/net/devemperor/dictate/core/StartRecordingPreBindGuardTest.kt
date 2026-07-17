package net.devemperor.dictate.core

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression test for the pre-bind NPE in
 * [DictateInputMethodService.startRecording] (render-latency-wave2 §2.3).
 *
 * # The bug this pins
 *
 * `startRecording()` used to call `promptQueueManager.prepareAutoApplyQueue()`
 * **before** the B1 binder-guard. `promptQueueManager` is only assigned in
 * `bindAiInfrastructureFromService` (post-bind), so any record tap that
 * reached `startRecording()` in the cold-start window between
 * `onCreateInputView` and `onServiceConnected` threw an NPE **before** the
 * pending-tap buffer could set `pendingRecordOnBind`. The tap was lost (and,
 * on the QWERTZ record button, crashed).
 *
 * The fix moves the `if (pipelineBinder == null) { pendingRecordOnBind = true;
 * return; }` guard to the very top of the method. This test drives the public
 * `onRecordClicked()` entry point (the same path the Problem-A bootstrap
 * record-listener uses) on a freshly-built service with **no binder** — the
 * exact cold-start window — and asserts the tap is buffered instead of
 * throwing.
 *
 * On the unfixed code this test is RED (NPE surfaces out of `onRecordClicked`).
 *
 * @see DictateInputMethodService#startRecording()
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartRecordingPreBindGuardTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun service(): DictateInputMethodService =
        Robolectric.buildService(DictateInputMethodService::class.java).get()

    private fun pendingRecordOnBind(svc: DictateInputMethodService): Boolean {
        val f = DictateInputMethodService::class.java.getDeclaredField("pendingRecordOnBind")
        f.isAccessible = true
        return f.getBoolean(svc)
    }

    @Test
    fun `record tap before the binder arrives buffers instead of NPEing`() {
        // Cold-start window: onCreateInputView ran, onServiceConnected has not,
        // so pipelineBinder == null and promptQueueManager == null.
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val svc = service()

        // Public entry point the bootstrap record-listener routes through
        // (getPipelinePhase() Idle-fallback → isEffectiveRecordingIdle() →
        // startRecording()). Must not throw.
        svc.onRecordClicked()

        assertTrue(
            "pre-bind record tap must be buffered as pendingRecordOnBind (B1), " +
                "not dropped by an NPE before the guard",
            pendingRecordOnBind(svc),
        )
    }
}
