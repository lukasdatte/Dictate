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
            recording = RecordingState.Preparing(useBluetooth = false, audioFile = stubAudioFile()),
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
    }

    @Test
    fun `resolveRecordAction emits StopRecordingAndSend from Active`() {
        val s = state.copy(
            recording = RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile()),
        )
        assertTrue(resolveRecordAction(s, fakeModuleServices()) is Action.RecordingAction.StopRecordingAndSend)
    }

    @Test
    fun `resolveRecordAction emits StopRecordingAndSend from Paused`() {
        val s = state.copy(
            recording = RecordingState.Paused(useBluetooth = false, audioFile = stubAudioFile()),
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
        assertEquals(1, toast.errorMessages.size)
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
            recording = RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile()),
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
            recording = RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile()),
        )
        assertEquals(
            Action.RecordingAction.PauseRecording,
            resolvePauseAction(s, fakeModuleServices()),
        )
    }

    @Test
    fun `resolvePauseAction returns ResumeRecording while Paused`() {
        val s = state.copy(
            recording = RecordingState.Paused(useBluetooth = false, audioFile = stubAudioFile()),
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
                    recording = RecordingState.Preparing(useBluetooth = false, audioFile = stubAudioFile()),
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
    fun `resolveAudioFocusIcon returns volume_up when enabled`() {
        assertEquals(net.devemperor.dictate.R.drawable.ic_baseline_volume_up_24, resolveAudioFocusIcon(enabled = true))
    }

    @Test
    fun `resolveAudioFocusIcon returns volume_off when disabled`() {
        assertEquals(net.devemperor.dictate.R.drawable.ic_baseline_volume_off_24, resolveAudioFocusIcon(enabled = false))
    }

    @Test
    fun `resolvePauseIcon returns mic icon while Paused`() {
        val s = state.copy(
            recording = RecordingState.Paused(useBluetooth = false, audioFile = stubAudioFile()),
        )
        assertEquals(net.devemperor.dictate.R.drawable.ic_baseline_mic_24, resolvePauseIcon(s))
    }

    @Test
    fun `resolvePauseIcon returns pause icon otherwise`() {
        val active = state.copy(
            recording = RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile()),
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
            recording = RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile()),
        )
        assertEquals(strings.send, resolveRecordButtonText(s, strings))
    }

    @Test
    fun `resolveRecordButtonText returns dictateButtonText in Idle`() {
        val strings = testLayoutStrings()
        val s = state.copy(recording = RecordingState.Idle)
        assertEquals(strings.dictateButtonText(), resolveRecordButtonText(s, strings))
    }

    @Test
    fun `resolveRecordButtonText returns record string while Preparing`() {
        val strings = testLayoutStrings()
        val s = state.copy(
            recording = RecordingState.Preparing(useBluetooth = false, audioFile = stubAudioFile()),
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
    fun `resolveRecordButtonTextStaging returns empty string outside staging`() {
        val strings = testLayoutStrings()
        val s = state.copy(pipeline = PipelineUiState.Idle)
        assertEquals("", resolveRecordButtonTextStaging(s, strings))
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

    override fun show(message: CharSequence) {
        messages.add(message)
    }

    override fun showError(message: CharSequence) {
        errorMessages.add(message)
    }
}

// Keeps `NoopAudioFileFactory` + `NoopToastSink` imported so future
// expansions of the test surface (e.g. a "default services" case) read
// from the shared no-op fixtures rather than reinventing them.
@Suppress("unused") private val _noopFactoryAnchor: AudioFileFactory = NoopAudioFileFactory
@Suppress("unused") private val _noopToastAnchor: ToastSink = NoopToastSink
