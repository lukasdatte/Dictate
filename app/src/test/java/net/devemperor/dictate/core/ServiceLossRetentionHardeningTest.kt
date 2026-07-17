package net.devemperor.dictate.core

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.state.layout.LogicalButtonId
import net.devemperor.dictate.state.render.ImeViewBackend
import net.devemperor.dictate.state.render.MotionSurface
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.lang.reflect.Field

/**
 * T5 (render-latency-wave2 §7.1) — Problem-B service-loss hardening (§B3),
 * the precondition for the §B2 retention fast-path.
 *
 * With view retention the view-rebuild no longer implicitly heals a service
 * restart, so `onServiceDisconnected` / `onBindingDied` must explicitly
 * clear the `imeViewBackendAttached` marker (and stop the service-bound
 * renderers), and a following re-attach must set it back. This drives the
 * **real** IME `ServiceConnection` callbacks + the real
 * `attachImeViewBackendToService` on the real fields via reflection (the
 * full `onCreateInputView` inflation is not drivable under Robolectric —
 * the same reason `PipelineServiceConnectionContractTest` exists).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceLossRetentionHardeningTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val ctx = ContextThemeWrapper(app, R.style.Theme_Dictate)

    private val pipelineController = Robolectric.buildService(DictatePipelineService::class.java)

    @After
    fun tearDown() {
        try {
            pipelineController.destroy()
        } catch (ignored: Throwable) {
        }
        JobExecutor.resetForTest()
        ActiveJobRegistry.resetForTest()
        net.devemperor.dictate.database.DurationHealingScheduler.resetForTest()
        net.devemperor.dictate.database.DictateDatabase.resetForTest(app)
    }

    private fun field(name: String): Field =
        DictateInputMethodService::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun attached(ime: DictateInputMethodService): Boolean =
        field("imeViewBackendAttached").getBoolean(ime)

    private fun connection(ime: DictateInputMethodService): ServiceConnection =
        field("pipelineConnection").get(ime) as ServiceConnection

    private fun bootBinder(): DictatePipelineService.LocalBinder {
        pipelineController.create()
        val b = pipelineController.get().onBind(Intent()) as DictatePipelineService.LocalBinder
        ShadowLooper.idleMainLooper()
        return b
    }

    /** A real, render-capable backend (full button map + no-op MotionSurface). */
    private fun workingBackend(binder: DictatePipelineService.LocalBinder): ImeViewBackend {
        val buttons: Map<LogicalButtonId, View> = LogicalButtonId.entries
            .filter { id ->
                id != LogicalButtonId.OVERLAY_RECORD &&
                    id != LogicalButtonId.OVERLAY_PAUSE &&
                    id != LogicalButtonId.OVERLAY_TRASH &&
                    id != LogicalButtonId.OVERLAY_CLOSE
            }
            .associateWith { MaterialButton(ctx) as View }
        val motion = object : MotionSurface {
            override fun jumpToState(stateId: Int) {}
            override fun transitionToState(stateId: Int) {}
        }
        return ImeViewBackend(
            motionSurface = motion,
            buttonViews = buttons,
            ctx = ctx,
            services = { binder.moduleServices },
        )
    }

    @Test
    fun `onServiceDisconnected clears the attached marker`() {
        val ime = Robolectric.buildService(DictateInputMethodService::class.java).create().get()
        field("imeViewBackendAttached").setBoolean(ime, true) // pretend attached

        connection(ime).onServiceDisconnected(
            ComponentName(app, DictatePipelineService::class.java),
        )

        assertFalse(
            "onServiceDisconnected must clear imeViewBackendAttached (B3)",
            attached(ime),
        )
    }

    @Test
    fun `onBindingDied clears the attached marker`() {
        val ime = Robolectric.buildService(DictateInputMethodService::class.java).create().get()
        field("imeViewBackendAttached").setBoolean(ime, true)

        connection(ime).onBindingDied(
            ComponentName(app, DictatePipelineService::class.java),
        )

        assertFalse(
            "onBindingDied must clear imeViewBackendAttached (B3)",
            attached(ime),
        )
    }

    @Test
    fun `after service loss a re-attach sets the marker back to true`() {
        val binder = bootBinder()
        val ime = Robolectric.buildService(DictateInputMethodService::class.java).create().get()

        // Simulate a completed attach, then lose the service.
        field("imeViewBackendAttached").setBoolean(ime, true)
        connection(ime).onServiceDisconnected(
            ComponentName(app, DictatePipelineService::class.java),
        )
        assertFalse("pre-condition: marker cleared on service loss", attached(ime))

        // Re-attach: inject the state the real attach half consumes and drive
        // the real attachImeViewBackendToService against the fresh binder.
        field("pipelineBinder").set(ime, binder)
        field("dictateKeyboardView").set(ime, ConstraintLayout(ctx))
        field("imeViewBackend").set(ime, workingBackend(binder))
        val noopAffordance: (LogicalButtonId, Boolean) -> Unit = { _, _ -> }
        field("imeSideAffordanceFn").set(ime, noopAffordance)
        val attach = DictateInputMethodService::class.java
            .getDeclaredMethod("attachImeViewBackendToService", android.content.Context::class.java)
            .apply { isAccessible = true }
        try {
            attach.invoke(ime, ctx)
        } catch (ignored: Throwable) {
            // The marker is set the instant attachBackend accepts the backend
            // (before the downstream observer/InfoBar block, which needs the
            // fully inflated tree this bare-view harness doesn't provide). A
            // throw from that later block does not undo the marker.
        }
        ShadowLooper.idleMainLooper()

        assertTrue(
            "a re-attach after service loss must set imeViewBackendAttached back to true (B3)",
            attached(ime),
        )
    }
}
