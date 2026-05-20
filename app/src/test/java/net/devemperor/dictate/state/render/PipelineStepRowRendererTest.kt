package net.devemperor.dictate.state.render

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.core.PipelineUiCallback
import net.devemperor.dictate.core.PipelineUiState
import net.devemperor.dictate.core.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CR-DEL (C10-C3) — unit tests for [PipelineStepRowRenderer], the
 * relocated `KeyboardUiController` View-side (G13 BLEIBT, A3 option-a,
 * Spec 1 §9.2). The behaviour is byte-equivalent to the deleted
 * `KeyboardUiController`; these tests are the relocated + extended
 * `KeyboardUiControllerTest` (the 6 deleted methods' coverage relocates
 * here, plus the new no-op `onPipelineUiStateChanged` seam and the
 * `PipelineUiStateReader` / callback / staging contract).
 *
 * Quality-Gate K-4 exception: the renderer mutates `MaterialButton` view
 * properties + inflates `item_pipeline_step_row`; Robolectric resolves
 * the resource ids from `app/src/main/res/`.
 *
 * Quality-Gate K-1: no Mockito — real renderer driven by handwritten
 * lambdas + a handwritten [PipelineUiCallback] fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PipelineStepRowRendererTest {

    private lateinit var themed: Context
    private lateinit var recordButton: MaterialButton
    private lateinit var stepsContainer: LinearLayout
    private lateinit var renderer: PipelineStepRowRenderer
    private var stateChangeSeamCalls = 0

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        themed = ContextThemeWrapper(app, R.style.Theme_Dictate)
        recordButton = MaterialButton(themed)
        stepsContainer = LinearLayout(themed)
        stateChangeSeamCalls = 0

        val views = PipelineStepRowRenderer.PipelineViews(
            pipelineStepsContainer = stepsContainer,
            pipelineScrollView = ScrollView(themed),
            recordButton = recordButton,
            infoCl = View(themed),
            layoutInflater = LayoutInflater.from(themed),
            mainHandler = Handler(Looper.getMainLooper()),
        )

        renderer = PipelineStepRowRenderer(
            views = views,
            onPipelineUiStateChanged = { stateChangeSeamCalls++ },
            dictateButtonTextProvider = { "Record" },
        )
    }

    // ── Phase 3 (cutover-vol2) — applyRecordButtonForRecording is no-op ──
    //
    // The legacy applyRecordButtonForRecording previously wrote record_btn
    // text + enabled + compound drawables for each RecordingState branch.
    // Phase 3 of dictate-render-cutover-completion-vol2 atomically flipped
    // that ownership: the Catalog/SlotRenderer now owns text + enabled and
    // the AutoEnterRenderer side-channel owns compound drawables. The
    // legacy method body is empty and only kept as a compile-time shim
    // until Phase 5.B deletes it.

    @Test
    fun `applyRecordButtonForRecording is no-op for every RecordingState branch`() {
        val initialText = recordButton.text?.toString().orEmpty()
        val initialEnabled = recordButton.isEnabled
        val initialDrawables = recordButton.compoundDrawablesRelative.toList()
        listOf(
            RecordingState.Idle,
            RecordingState.Preparing(useBluetooth = false),
            RecordingState.Active(useBluetooth = false),
            RecordingState.Active(useBluetooth = true),
            RecordingState.Paused,
        ).forEach { state ->
            renderer.applyRecordButtonForRecording(state)
            assertEquals(
                "Phase 3 no-op: applyRecordButtonForRecording must not mutate text — got '${recordButton.text}' for $state",
                initialText, recordButton.text?.toString().orEmpty(),
            )
            assertEquals(
                "Phase 3 no-op: applyRecordButtonForRecording must not mutate isEnabled for $state",
                initialEnabled, recordButton.isEnabled,
            )
            assertEquals(
                "Phase 3 no-op: applyRecordButtonForRecording must not mutate compound drawables for $state " +
                    "— AutoEnterRenderer is the single writer for that axis",
                initialDrawables, recordButton.compoundDrawablesRelative.toList(),
            )
        }
    }

    @Test
    fun `preparePipeline updates state but does NOT mutate record_btn directly`() {
        val initialText = recordButton.text?.toString().orEmpty()
        val initialEnabled = recordButton.isEnabled
        renderer.preparePipeline()
        // The renderer's internal `state` transitions to Preparing —
        // testable via callbacks — but the record_btn itself is now
        // owned by the Catalog/SlotRenderer, so no direct write here.
        assertEquals(initialText, recordButton.text?.toString().orEmpty())
        assertEquals(initialEnabled, recordButton.isEnabled)
        assertTrue(renderer.state is PipelineUiState.Preparing)
    }

    // ── Pipeline-UI machinery (relocated) ──

    @Test
    fun startPipeline_entersRunning_andSeam_fires_onStateChange() {
        assertTrue(renderer.state is PipelineUiState.Idle)
        renderer.startPipeline(2, PipelineStepRowRenderer.AutoEnterConfig(false))
        assertTrue(renderer.state is PipelineUiState.Running)
        assertTrue(
            "onPipelineUiStateChanged seam (replaces the deleted KSM.refresh) must fire on a real state change",
            stateChangeSeamCalls >= 1,
        )
        val cfg = renderer.getAutoEnterConfig()
        assertEquals(false, cfg?.autoEnterActive)
    }

    @Test
    fun addRunningStep_inflatesARow_andTracksCurrentStepName() {
        renderer.startPipeline(1, PipelineStepRowRenderer.AutoEnterConfig(true))
        val before = stepsContainer.childCount
        renderer.addRunningStep("Transkription")
        assertEquals(
            "addRunningStep must inflate exactly one item_pipeline_step_row",
            before + 1,
            stepsContainer.childCount,
        )
        val running = renderer.state as PipelineUiState.Running
        assertEquals("Transkription", running.currentStepName)
    }

    @Test
    fun completeStep_thenStopPipeline_returnsToIdle() {
        renderer.startPipeline(1, PipelineStepRowRenderer.AutoEnterConfig(false))
        renderer.addRunningStep("Step")
        renderer.completeStep("Step", 1234L)
        assertEquals(1, (renderer.state as PipelineUiState.Running).completedSteps)
        renderer.stopPipeline()
        assertTrue(renderer.state is PipelineUiState.Idle)
        assertNull(
            "stopPipeline must null the per-run AutoEnterConfig",
            renderer.getAutoEnterConfig(),
        )
    }

    @Test
    fun toggleAutoEnter_flipsRunningFlag_onlyWhileRunning() {
        renderer.toggleAutoEnter() // Idle — must no-op
        assertTrue(renderer.state is PipelineUiState.Idle)
        renderer.startPipeline(1, PipelineStepRowRenderer.AutoEnterConfig(false))
        renderer.toggleAutoEnter()
        assertTrue((renderer.state as PipelineUiState.Running).autoEnterActive)
        assertEquals(true, renderer.getAutoEnterConfig()?.autoEnterActive)
    }

    // ── ReprocessStaging carrier (View-side BLEIBT — Spec 1 §9.2) ──

    @Test
    fun enterReprocessStaging_carriesSelectedLanguage_asViewSideState() {
        renderer.enterReprocessStaging("sess-1", 42L, listOf(1, 2), "de")
        val st = renderer.state as PipelineUiState.ReprocessStaging
        assertEquals("sess-1", st.targetSessionId)
        assertEquals(42L, st.audioDurationSeconds)
        assertEquals(listOf(1, 2), st.editableQueue)
        assertEquals(
            "selectedLanguage is the relocated View-side BLEIBT staging state (NOT the F-6 language-read carrier)",
            "de",
            st.selectedLanguage,
        )
        assertTrue(renderer.isReprocessStaging())
        assertTrue(renderer.isBusy())
    }

    @Test
    fun updateReprocessQueue_andLanguage_onlyMutateWhileStaging() {
        // Not staging → both no-op.
        renderer.updateReprocessQueue(listOf(9))
        renderer.updateReprocessLanguage("xx")
        assertTrue(renderer.state is PipelineUiState.Idle)

        renderer.enterReprocessStaging("s", 1L, listOf(1), null)
        renderer.updateReprocessQueue(listOf(1, 2, 3))
        assertEquals(listOf(1, 2, 3), (renderer.state as PipelineUiState.ReprocessStaging).editableQueue)
        renderer.updateReprocessLanguage("fr")
        assertEquals("fr", (renderer.state as PipelineUiState.ReprocessStaging).selectedLanguage)

        renderer.cancelReprocessStaging()
        assertTrue(renderer.state is PipelineUiState.Idle)
    }

    // ── PipelineUiStateReader callback contract (multi-callback) ──

    @Test
    fun addCallback_isIdempotent_andForwardsStateChanges() {
        val cb = RecordingCallbackFake()
        renderer.addCallback(cb)
        renderer.addCallback(cb) // idempotent — addIfAbsent
        renderer.startPipeline(1, PipelineStepRowRenderer.AutoEnterConfig(false))
        assertEquals(
            "A single registered callback must receive exactly one state-change notification",
            1,
            cb.stateChanges,
        )
        renderer.removeCallback(cb)
        renderer.stopPipeline()
        assertEquals(
            "A removed callback must receive no further notifications",
            1,
            cb.stateChanges,
        )
    }

    @Test
    fun renderer_is_a_PipelineUiStateReader() {
        // Spec 1 §9.6 — the interface is adapted (not deleted) and now
        // points at the relocated owner. A consumer must be able to read
        // through the narrow surface without importing the concrete class.
        val reader: net.devemperor.dictate.core.PipelineUiStateReader = renderer
        assertSame(renderer.state, reader.state)
    }

    private class RecordingCallbackFake : PipelineUiCallback {
        var stateChanges = 0
            private set

        override fun onPipelineUiStateChanged(
            oldState: PipelineUiState,
            newState: PipelineUiState,
        ) {
            stateChanges++
        }
    }
}
