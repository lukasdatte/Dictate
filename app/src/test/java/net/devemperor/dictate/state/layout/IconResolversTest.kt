package net.devemperor.dictate.state.layout

import net.devemperor.dictate.R
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the record-button compound-drawable resolvers introduced in
 * Phase 1 of `2026-05-21 - dictate-render-cutover-completion-vol2`.
 *
 * **Scope:** prove that [resolveRecordLeftIcon] and [resolveRecordRightIcon]
 * produce byte-equivalent values to what
 * `PipelineStepRowRenderer.refreshRecordButtonFromState` /
 * `applyRecordButtonForRecording` write on the corresponding axes today
 * — *with the exception* of the two Phase-3-documented stateless deltas
 * (right-icon on `Paused`; right-icon on `Running` — `null` rather than
 * the dynamic auto-enter `BitmapDrawable`).
 *
 * **Not in scope:** Catalog-wiring. Phase 1 only ships the resolvers; the
 * `iconResolver` field on the four RECORD slots in [LayoutCatalog] is
 * still empty until Phase 3 — the atomic flip commit that also no-ops
 * the legacy 100 ms writer.
 *
 * @see net.devemperor.dictate.state.layout.resolveRecordLeftIcon
 * @see net.devemperor.dictate.state.layout.resolveRecordRightIcon
 * @see docs/plans/2026-05-21 - dictate-render-cutover-completion-vol2/dictate-render-cutover-completion-vol2.md §4 Phase 1
 */
class IconResolversTest {

    // ─── resolveRecordLeftIcon ─────────────────────────────────────────

    @Test
    fun `left icon on (pipeline=Idle, recording=Idle) is mic_20`() {
        val state = stateOf(pipeline = PipelineUiState.Idle, recording = RecordingState.Idle)
        assertEquals(R.drawable.ic_baseline_mic_20, resolveRecordLeftIcon(state))
    }

    @Test
    fun `left icon on (pipeline=Idle, recording=Active) is send_20 regardless of bluetooth`() {
        val noBt = stateOf(pipeline = PipelineUiState.Idle, recording = activeRecording(useBluetooth = false))
        val bt = stateOf(pipeline = PipelineUiState.Idle, recording = activeRecording(useBluetooth = true))
        assertEquals(R.drawable.ic_baseline_send_20, resolveRecordLeftIcon(noBt))
        assertEquals(R.drawable.ic_baseline_send_20, resolveRecordLeftIcon(bt))
    }

    @Test
    fun `left icon on (pipeline=Idle, recording=Paused) is send_20 (Active-residue parity)`() {
        // Legacy: Paused arm in `applyRecordButtonForRecording` makes no
        // mutation; the view holds the last Active write (always send_20).
        val state = stateOf(
            pipeline = PipelineUiState.Idle,
            recording = pausedRecording(useBluetooth = false),
        )
        assertEquals(R.drawable.ic_baseline_send_20, resolveRecordLeftIcon(state))
    }

    @Test
    fun `left icon on (pipeline=Idle, recording=Preparing) is null (Legacy no-op)`() {
        val state = stateOf(
            pipeline = PipelineUiState.Idle,
            recording = preparingRecording(useBluetooth = false),
        )
        assertNull(resolveRecordLeftIcon(state))
    }

    @Test
    fun `left icon on (pipeline=Preparing) is send_20`() {
        val state = stateOf(pipeline = PipelineUiState.Preparing("s1"))
        assertEquals(R.drawable.ic_baseline_send_20, resolveRecordLeftIcon(state))
    }

    @Test
    fun `left icon on (pipeline=Running) is null (Auto-Enter side-channel owns right slot)`() {
        val state = stateOf(
            pipeline = PipelineUiState.Running(sessionId = "s1", target = InsertionTarget.INPUT_CONNECTION),
        )
        assertNull(resolveRecordLeftIcon(state))
    }

    @Test
    fun `left icon on (pipeline=ReprocessStaging) is play_arrow_24`() {
        val state = stateOf(pipeline = PipelineUiState.ReprocessStaging("s1", "transcript"))
        assertEquals(R.drawable.ic_baseline_play_arrow_24, resolveRecordLeftIcon(state))
    }

    // ─── resolveRecordRightIcon ────────────────────────────────────────

    @Test
    fun `right icon on (pipeline=Idle, recording=Idle) is folder_open_20`() {
        val state = stateOf(pipeline = PipelineUiState.Idle, recording = RecordingState.Idle)
        assertEquals(R.drawable.ic_baseline_folder_open_20, resolveRecordRightIcon(state))
    }

    @Test
    fun `right icon on (pipeline=Idle, recording=Active, bluetooth=true) is bluetooth_20`() {
        // Bluetooth-Icon-Branch — Phase-1 plan requirement (§4 Phase 1.2).
        val state = stateOf(
            pipeline = PipelineUiState.Idle,
            recording = activeRecording(useBluetooth = true),
        )
        assertEquals(R.drawable.ic_baseline_bluetooth_20, resolveRecordRightIcon(state))
    }

    @Test
    fun `right icon on (pipeline=Idle, recording=Active, bluetooth=false) is null`() {
        val state = stateOf(
            pipeline = PipelineUiState.Idle,
            recording = activeRecording(useBluetooth = false),
        )
        assertNull(resolveRecordRightIcon(state))
    }

    @Test
    fun `right icon on (pipeline=Idle, recording=Paused, bluetooth=true) is bluetooth_20`() {
        // The orchestrator state's Paused carries useBluetooth, so the
        // stateless resolver reproduces the legacy "no-mutation"
        // residue without loss.
        val state = stateOf(
            pipeline = PipelineUiState.Idle,
            recording = pausedRecording(useBluetooth = true),
        )
        assertEquals(R.drawable.ic_baseline_bluetooth_20, resolveRecordRightIcon(state))
    }

    @Test
    fun `right icon on (pipeline=Idle, recording=Paused, bluetooth=false) is null`() {
        val state = stateOf(
            pipeline = PipelineUiState.Idle,
            recording = pausedRecording(useBluetooth = false),
        )
        assertNull(resolveRecordRightIcon(state))
    }

    @Test
    fun `right icon on (pipeline=Idle, recording=Preparing) is null`() {
        val state = stateOf(
            pipeline = PipelineUiState.Idle,
            recording = preparingRecording(useBluetooth = false),
        )
        assertNull(resolveRecordRightIcon(state))
    }

    @Test
    fun `right icon on (pipeline=Preparing) is null`() {
        val state = stateOf(pipeline = PipelineUiState.Preparing("s1"))
        assertNull(resolveRecordRightIcon(state))
    }

    @Test
    fun `right icon on (pipeline=Running) is null (Auto-Enter renderer side-channel, not a DrawableRes)`() {
        val state = stateOf(
            pipeline = PipelineUiState.Running(sessionId = "s1", target = InsertionTarget.INPUT_CONNECTION),
        )
        assertNull(resolveRecordRightIcon(state))
    }

    @Test
    fun `right icon on (pipeline=ReprocessStaging) is send_24`() {
        val state = stateOf(pipeline = PipelineUiState.ReprocessStaging("s1", "transcript"))
        assertEquals(R.drawable.ic_baseline_send_24, resolveRecordRightIcon(state))
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private fun stateOf(
        pipeline: PipelineUiState = PipelineUiState.Idle,
        recording: RecordingState = RecordingState.Idle,
    ): DictateUiState = DictateUiState.initial().copy(
        recording = recording,
        pipeline = pipeline,
    )

    private fun activeRecording(useBluetooth: Boolean) = RecordingState.Active(
        useBluetooth = useBluetooth, audioFile = stubAudioFile(), sessionId = "s-test",
    )

    private fun pausedRecording(useBluetooth: Boolean) = RecordingState.Paused(
        useBluetooth = useBluetooth, audioFile = stubAudioFile(), sessionId = "s-test",
    )

    private fun preparingRecording(useBluetooth: Boolean) = RecordingState.Preparing(
        useBluetooth = useBluetooth, audioFile = stubAudioFile(), sessionId = "s-test",
    )
}
