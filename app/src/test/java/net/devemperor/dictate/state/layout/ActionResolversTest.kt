package net.devemperor.dictate.state.layout

import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.AudioFileFactory
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.state.ToastSink
import net.devemperor.dictate.testutil.NoopAudioFileFactory
import net.devemperor.dictate.testutil.NoopToastSink
import net.devemperor.dictate.testutil.fakeModuleServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Tests for the nullable-action resolvers in [ActionResolvers].
 *
 * The contract under test is R.3 (Spec 2 §3.2): a `null` resolver return
 * means "click is structurally meaningless in the current state" and the
 * IME click handler short-circuits silently — no
 * `DispatchOutcome.Rejected` log, no toast.
 *
 * # IOException side-channel
 *
 * `resolveRecordAction` is the one resolver that may call
 * `services.audioFileFactory.allocate()` (Pre-Dispatch Allocation, Spec 1
 * §4.11). When the factory throws `IOException`, the resolver MUST:
 *
 * 1. Show a toast via `services.toastSink.showError(...)`.
 * 2. Log the failure (asserted indirectly — no crash).
 * 3. Return `null` so the dispatch path stays clean.
 *
 * The handwritten [FailingAudioFileFactory] and [RecordingToastSink]
 * fixtures verify the full side-channel.
 */
class ActionResolversTest {

    private val state = DictateUiState.initial()

    // ─── resolveRecordAction ───────────────────────────────────────────

    @Test
    fun `resolveRecordAction returns null while recording is Preparing`() {
        val s = state.copy(
            recording = RecordingState.Preparing(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test"),
        )
        assertNull(resolveRecordAction(s, fakeModuleServices()))
    }

    @Test
    fun `resolveRecordAction emits StartRecording from Idle with allocated file`() {
        val recordingFile = File("/tmp/dictate-test-1.m4a")
        val factory = FixedAudioFileFactory(recordingFile)
        val services = fakeModuleServices(audioFileFactory = factory)
        val s = state.copy(recording = RecordingState.Idle)

        val action = resolveRecordAction(s, services) as? Action.RecordingAction.StartRecording
            ?: error("Expected StartRecording, got ${resolveRecordAction(s, services)}")

        assertEquals(recordingFile, action.audioFile)
        assertEquals(InsertionTarget.INPUT_CONNECTION, action.target)
        assertEquals(1, factory.allocateCallCount)
        // F-10 — the resolver mints a real (non-empty, UUID-shaped)
        // sessionId; no empty-string sentinel.
        assertTrue(action.sessionId.isNotEmpty())
        assertTrue(action.sessionId.matches(Regex("[0-9a-fA-F-]{36}")))
    }

    @Test
    fun `F-10 resolveRecordAction mints a fresh sessionId on each StartRecording`() {
        val services = fakeModuleServices(audioFileFactory = FixedAudioFileFactory(File("/tmp/r.m4a")))
        val s = state.copy(recording = RecordingState.Idle)
        val a = resolveRecordAction(s, services) as Action.RecordingAction.StartRecording
        val b = resolveRecordAction(s, services) as Action.RecordingAction.StartRecording
        // Distinct clicks get distinct ids (UUID per session).
        assertTrue(a.sessionId != b.sessionId)
    }

    @Test
    fun `resolveRecordAction emits StopRecordingAndSend from Active`() {
        val s = state.copy(
            recording = RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test"),
        )
        assertTrue(resolveRecordAction(s, fakeModuleServices()) is Action.RecordingAction.StopRecordingAndSend)
    }

    @Test
    fun `resolveRecordAction emits StopRecordingAndSend from Paused`() {
        val s = state.copy(
            recording = RecordingState.Paused(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test"),
        )
        assertTrue(resolveRecordAction(s, fakeModuleServices()) is Action.RecordingAction.StopRecordingAndSend)
    }

    @Test
    fun `resolveRecordAction returns null and shows toast on IOException`() {
        val toast = RecordingToastSink()
        val services = fakeModuleServices(
            audioFileFactory = FailingAudioFileFactory(IOException("disk full")),
            toastSink = toast,
        )
        val s = state.copy(recording = RecordingState.Idle)

        // R.3 contract: null is the silent-no-op path, never throws.
        val result = resolveRecordAction(s, services)
        assertNull(result)

        // Toast must surface the failure so the user knows the click didn't take.
        // B4-VAL F-4: switched from showError(literal) to show(@StringRes) so the
        // user-visible string goes through Android i18n.
        assertEquals(listOf(net.devemperor.dictate.R.string.dictate_storage_full), toast.resourceIds)
        assertTrue("Should not use error-channel String overload", toast.errorMessages.isEmpty())
    }

    // ─── resolveRecordLongPressAction (G2 / CR1 / A1) ──────────────────

    @Test
    fun `resolveRecordLongPressAction emits OnRecordLongPress from Active`() {
        val s = state.copy(
            recording = RecordingState.Active(
                useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test",
            ),
        )
        assertEquals(
            Action.RecordingAction.OnRecordLongPress,
            resolveRecordLongPressAction(s, fakeModuleServices()),
        )
    }

    @Test
    fun `resolveRecordLongPressAction emits OnRecordLongPress from Paused`() {
        val s = state.copy(
            recording = RecordingState.Paused(
                useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test",
            ),
        )
        assertEquals(
            Action.RecordingAction.OnRecordLongPress,
            resolveRecordLongPressAction(s, fakeModuleServices()),
        )
    }

    @Test
    fun `resolveRecordLongPressAction returns null from Idle (R-3 — Idle launch is IME-side, A1)`() {
        val s = state.copy(recording = RecordingState.Idle)
        // R.3: the Idle Settings+file-picker launch is an IME-side
        // affordance wired in CR4, NOT a reducer transition — the resolver
        // short-circuits so no pointless action reaches the orchestrator.
        assertNull(resolveRecordLongPressAction(s, fakeModuleServices()))
    }

    @Test
    fun `resolveRecordLongPressAction returns null while Preparing`() {
        val s = state.copy(
            recording = RecordingState.Preparing(
                useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test",
            ),
        )
        assertNull(resolveRecordLongPressAction(s, fakeModuleServices()))
    }

    // ─── resolveRecordActionPipeline ──────────────────────────────────

    @Test
    fun `resolveRecordActionPipeline returns ToggleAutoEnter while Running`() {
        val s = state.copy(
            pipeline = PipelineUiState.Running(
                sessionId = "s1",
                target = InsertionTarget.INPUT_CONNECTION,
            ),
        )
        assertEquals(
            Action.FeatureToggleAction.ToggleAutoEnter,
            resolveRecordActionPipeline(s, fakeModuleServices()),
        )
    }

    @Test
    fun `resolveRecordActionPipeline returns null when pipeline is Preparing`() {
        val s = state.copy(pipeline = PipelineUiState.Preparing("s1"))
        assertNull(resolveRecordActionPipeline(s, fakeModuleServices()))
    }

    @Test
    fun `resolveRecordActionPipeline returns null when pipeline is Idle`() {
        val s = state.copy(pipeline = PipelineUiState.Idle)
        assertNull(resolveRecordActionPipeline(s, fakeModuleServices()))
    }

    // ─── resolveTrashAction ───────────────────────────────────────────

    @Test
    fun `resolveTrashAction returns CancelReprocessStaging while staging`() {
        val s = state.copy(pipeline = PipelineUiState.ReprocessStaging("s42", "txt"))
        val result = resolveTrashAction(s, fakeModuleServices()) as? Action.PipelineAction.CancelReprocessStaging
            ?: error("Expected CancelReprocessStaging, got ${resolveTrashAction(s, fakeModuleServices())}")
        assertEquals("s42", result.sessionId)
    }

    @Test
    fun `resolveTrashAction returns null in pure-idle state`() {
        val s = state.copy(
            recording = RecordingState.Idle,
            pipeline = PipelineUiState.Idle,
        )
        assertNull(resolveTrashAction(s, fakeModuleServices()))
    }

    @Test
    fun `resolveTrashAction returns CancelRecording while recording`() {
        val s = state.copy(
            recording = RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test"),
        )
        assertEquals(
            Action.RecordingAction.CancelRecording,
            resolveTrashAction(s, fakeModuleServices()),
        )
    }

    // ─── resolvePauseAction ───────────────────────────────────────────

    @Test
    fun `resolvePauseAction returns PauseRecording while Active`() {
        val s = state.copy(
            recording = RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test"),
        )
        assertEquals(
            Action.RecordingAction.PauseRecording,
            resolvePauseAction(s, fakeModuleServices()),
        )
    }

    @Test
    fun `resolvePauseAction returns ResumeRecording while Paused`() {
        val s = state.copy(
            recording = RecordingState.Paused(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test"),
        )
        assertEquals(
            Action.RecordingAction.ResumeRecording,
            resolvePauseAction(s, fakeModuleServices()),
        )
    }

    @Test
    fun `resolvePauseAction returns null outside Active and Paused`() {
        assertNull(resolvePauseAction(state.copy(recording = RecordingState.Idle), fakeModuleServices()))
        assertNull(
            resolvePauseAction(
                state.copy(
                    recording = RecordingState.Preparing(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test"),
                ),
                fakeModuleServices(),
            ),
        )
    }

    // ─── Staging resolvers ────────────────────────────────────────────

    @Test
    fun `resolveSendStagingAction reads sessionId from current state`() {
        val s = state.copy(pipeline = PipelineUiState.ReprocessStaging("s99", "txt"))
        val result = resolveSendStagingAction(s, fakeModuleServices()) as? Action.PipelineAction.SendStaging
            ?: error("Expected SendStaging, got ${resolveSendStagingAction(s, fakeModuleServices())}")
        assertEquals("s99", result.sessionId)
    }

    @Test
    fun `resolveSendStagingAction returns null when pipeline is not staging`() {
        assertNull(resolveSendStagingAction(state.copy(pipeline = PipelineUiState.Idle), fakeModuleServices()))
    }

    @Test
    fun `resolveCancelStagingAction reads sessionId from current state`() {
        val s = state.copy(pipeline = PipelineUiState.ReprocessStaging("sX", "txt"))
        val result = resolveCancelStagingAction(s, fakeModuleServices()) as? Action.PipelineAction.CancelReprocessStaging
            ?: error("Expected CancelReprocessStaging, got ${resolveCancelStagingAction(s, fakeModuleServices())}")
        assertEquals("sX", result.sessionId)
    }

    @Test
    fun `resolveCancelStagingAction returns null when pipeline is not staging`() {
        assertNull(resolveCancelStagingAction(state.copy(pipeline = PipelineUiState.Idle), fakeModuleServices()))
    }

    // ─── Icon resolvers ───────────────────────────────────────────────

    @Test
    fun `resolveAudioFocusIcon returns volume_off when enabled (legacy semantics)`() {
        // enabled=true → AudioFocus IS held → other audio is muted →
        // icon depicts the effect on others (silenced) → volume_off.
        // Matches MainButtonsController.refreshAudioFocusIcon semantics.
        assertEquals(net.devemperor.dictate.R.drawable.ic_baseline_volume_off_24, resolveAudioFocusIcon(enabled = true))
    }

    @Test
    fun `resolveAudioFocusIcon returns volume_up when disabled (legacy semantics)`() {
        // enabled=false → AudioFocus NOT held → other audio plays normally
        // → icon depicts other-audio-audible → volume_up.
        assertEquals(net.devemperor.dictate.R.drawable.ic_baseline_volume_up_24, resolveAudioFocusIcon(enabled = false))
    }

    @Test
    fun `resolvePauseIcon returns mic icon while Paused`() {
        val s = state.copy(
            recording = RecordingState.Paused(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test"),
        )
        assertEquals(net.devemperor.dictate.R.drawable.ic_baseline_mic_24, resolvePauseIcon(s))
    }

    @Test
    fun `resolvePauseIcon returns pause icon otherwise`() {
        val active = state.copy(
            recording = RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test"),
        )
        val idle = state.copy(recording = RecordingState.Idle)
        assertEquals(net.devemperor.dictate.R.drawable.ic_baseline_pause_24, resolvePauseIcon(active))
        assertEquals(net.devemperor.dictate.R.drawable.ic_baseline_pause_24, resolvePauseIcon(idle))
    }

    // ─── Text resolvers ───────────────────────────────────────────────

    @Test
    fun `resolveRecordButtonText returns send text in Active`() {
        val strings = testLayoutStrings()
        val s = state.copy(
            recording = RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test"),
        )
        assertEquals(strings.send, resolveRecordButtonText(s, strings))
    }

    @Test
    fun `resolveRecordButtonText returns dictateButtonText in Idle`() {
        val strings = testLayoutStrings()
        val s = state.copy(recording = RecordingState.Idle)
        // F-15 — the resolver feeds the effective-language code into the
        // provider; the default initial state is "system".
        assertEquals(
            strings.dictateButtonText(s.language.effective),
            resolveRecordButtonText(s, strings),
        )
    }

    @Test
    fun `F-15 resolveRecordButtonText label differs across two effective languages`() {
        val strings = testLayoutStrings()
        val en = state.copy(
            recording = RecordingState.Idle,
            language = state.language.copy(effective = "en"),
        )
        val de = state.copy(
            recording = RecordingState.Idle,
            language = state.language.copy(effective = "de"),
        )
        val enLabel = resolveRecordButtonText(en, strings)
        val deLabel = resolveRecordButtonText(de, strings)
        assertEquals("Dictate (en)", enLabel)
        assertEquals("Dictate (de)", deLabel)
        // Core F-15 acceptance: the label is language-sensitive.
        assertTrue(enLabel != deLabel)
    }

    @Test
    fun `resolveRecordButtonText returns record string while Preparing`() {
        val strings = testLayoutStrings()
        val s = state.copy(
            recording = RecordingState.Preparing(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test"),
        )
        assertEquals(strings.record, resolveRecordButtonText(s, strings))
    }

    @Test
    fun `resolveRecordButtonTextPipeline returns sending while Preparing`() {
        val strings = testLayoutStrings()
        val s = state.copy(pipeline = PipelineUiState.Preparing("s1"))
        assertEquals(strings.sending, resolveRecordButtonTextPipeline(s, strings))
    }

    @Test
    fun `F-13 resolveRecordButtonTextPipeline renders real Running counters not placeholders`() {
        // Regression for the B4-resolver placeholder (`0, 0, …, 0L`):
        // the live label must reflect the actual Running progress fields.
        val strings = testLayoutStrings()
        val s = state.copy(
            pipeline = PipelineUiState.Running(
                sessionId = "s1",
                target = InsertionTarget.INPUT_CONNECTION,
                autoEnterActive = true,
                completedSteps = 2,
                totalSteps = 3,
                elapsedMs = 8_000L,
            ),
        )
        // testLayoutStrings().formatPipelineLabel: "$done/$total$mark  ${elapsedMs}ms"
        assertEquals("2/3 ↵  8000ms", resolveRecordButtonTextPipeline(s, strings))
    }

    @Test
    fun `F-13 resolveRecordButtonTextPipeline reflects autoEnter false in label`() {
        val strings = testLayoutStrings()
        val s = state.copy(
            pipeline = PipelineUiState.Running(
                sessionId = "s1",
                target = InsertionTarget.INPUT_CONNECTION,
                autoEnterActive = false,
                completedSteps = 0,
                totalSteps = 1,
                elapsedMs = 0L,
            ),
        )
        assertEquals("0/1  0ms", resolveRecordButtonTextPipeline(s, strings))
    }

    @Test
    fun `resolveRecordButtonTextStaging returns empty string outside staging`() {
        val strings = testLayoutStrings()
        val s = state.copy(pipeline = PipelineUiState.Idle)
        assertEquals("", resolveRecordButtonTextStaging(s, strings))
    }

    // ─── F-2 WIDGET_TOGGLE permission-aware resolver ─────────────────

    @Test
    fun `resolveWidgetToggleAction with permission returns ToggleViewModeWidget`() {
        val s = state.copy(overlay = state.overlay.copy(hasPermission = true))
        assertEquals(
            Action.ViewModeAction.ToggleViewModeWidget,
            resolveWidgetToggleAction(s, fakeModuleServices()),
        )
    }

    @Test
    fun `resolveWidgetToggleAction without permission returns ShowOverlayOnboarding`() {
        val s = state.copy(overlay = state.overlay.copy(hasPermission = false))
        assertEquals(
            Action.OverlayAction.ShowOverlayOnboarding,
            resolveWidgetToggleAction(s, fakeModuleServices()),
        )
    }
}

// ─── Hand-rolled fakes (K-1) ─────────────────────────────────────────

/** Always returns the same [file]. Tracks the number of `allocate` calls. */
private class FixedAudioFileFactory(private val file: File) : AudioFileFactory {
    var allocateCallCount: Int = 0
        private set

    override fun allocate(): File {
        allocateCallCount++
        return file
    }
}

/** Throws [thrown] on every `allocate` call. */
private class FailingAudioFileFactory(private val thrown: IOException) : AudioFileFactory {
    override fun allocate(): File = throw thrown
}

/** Captures every toast call so tests can assert on the side-channel. */
private class RecordingToastSink : ToastSink {
    val messages: MutableList<CharSequence> = mutableListOf()
    val errorMessages: MutableList<CharSequence> = mutableListOf()
    val resourceIds: MutableList<Int> = mutableListOf()

    override fun show(message: CharSequence) {
        messages.add(message)
    }

    override fun showError(message: CharSequence) {
        errorMessages.add(message)
    }

    override fun show(resId: Int) {
        resourceIds.add(resId)
    }
}

// Keeps `NoopAudioFileFactory` + `NoopToastSink` imported so future
// expansions of the test surface (e.g. a "default services" case) read
// from the shared no-op fixtures rather than reinventing them.
@Suppress("unused") private val _noopFactoryAnchor: AudioFileFactory = NoopAudioFileFactory
@Suppress("unused") private val _noopToastAnchor: ToastSink = NoopToastSink
