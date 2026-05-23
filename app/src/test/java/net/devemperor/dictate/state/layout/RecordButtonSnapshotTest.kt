package net.devemperor.dictate.state.layout

import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.state.LayoutState
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Snapshot tests for the `record_btn` Catalog output across the
 * `RecordingState × PipelineUiState` cross-product — Phase 1 deliverable
 * of `2026-05-21 - dictate-render-cutover-completion-vol2`.
 *
 * # What this test proves
 *
 * For every state permutation that the UI can land in, this test
 * documents exactly what [LayoutCatalog.forKeyboard]'s selected
 * `RECORD` slot returns on the four axes Phase 1 covers:
 * `textResolver`, `iconResolver`, `enabledResolver`, `alphaResolver`.
 *
 * **Color axis (setTextColor) is explicitly out of scope.** It is
 * driven by the upcoming `RecordButtonColorController` side-channel
 * introduced in Phase 5.A (state-migration for `Running.hasFailure`).
 *
 * # Phase-3-acceptance deltas (current Catalog gap vs. legacy writer)
 *
 * Every assertion below marked `// Δ Phase 3:` documents a current
 * **gap** between Catalog output and what the legacy
 * `PipelineStepRowRenderer.refreshRecordButtonFromState` /
 * `applyRecordButtonForRecording` write today. Phase 3 closes those
 * gaps atomically: the legacy 100 ms tick stops and the Catalog
 * becomes the single writer. Until then both writers run in parallel
 * — the legacy one wins because it re-fires every 100 ms.
 *
 * # Why a snapshot-style test (not behaviour assertions)
 *
 * The point is **byte-equivalent documentation**, not a behavioural
 * spec. The test guards against accidental drift in either direction:
 * a Catalog change should be intentional (and the test updated), and
 * a legacy change should likewise be reflected (the Δ-comments
 * indicate the legacy values to preserve).
 *
 * @see net.devemperor.dictate.state.layout.LayoutCatalog
 * @see net.devemperor.dictate.state.render.PipelineStepRowRenderer.refreshRecordButtonFromState
 * @see docs/plans/2026-05-21 - dictate-render-cutover-completion-vol2/dictate-render-cutover-completion-vol2.md §4 Phase 1
 */
class RecordButtonSnapshotTest {

    private val strings = testLayoutStrings()
    private val catalog = LayoutCatalog(strings)

    // ── Permutation 1-4: pipeline = Idle ───────────────────────────────

    @Test
    fun `snapshot — pipeline=Idle, recording=Idle (two-row)`() {
        val s = stateOf(pipeline = PipelineUiState.Idle, recording = RecordingState.Idle)
        val slot = catalog.recordSlot(s)
        assertEquals("Dictate (system)", slot.textResolver(s)?.toString())
        // Δ Phase 3: legacy writes left=mic_20, right=folder_open_20.
        assertNull(slot.iconResolver(s))
        assertEquals(true, slot.enabledResolver(s))
        assertEquals(1f, slot.alphaResolver(s), 0.001f)
    }

    @Test
    fun `snapshot — pipeline=Idle, recording=Preparing (two-row)`() {
        val s = stateOf(
            pipeline = PipelineUiState.Idle,
            recording = RecordingState.Preparing(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "s-test"),
        )
        val slot = catalog.recordSlot(s)
        // Δ Phase 3: legacy makes no text mutation on recording=Preparing
        // — the view holds whatever the previous (Idle) write produced.
        // Catalog overrides to "Record" which is a single-frame visual
        // delta the resolveRecordButtonText branch flips through. Phase 3
        // can either accept the flip or short-circuit (no-op on
        // Idle→Preparing). Doc: open Phase-3 decision.
        assertEquals("Record", slot.textResolver(s)?.toString())
        // Δ Phase 3: legacy writes left=null, right=null (refresh first
        // wipes via `(0,0,0,0)`, then applyRecordButtonForRecording
        // Preparing-arm makes no icon mutation).
        assertNull(slot.iconResolver(s))
        assertEquals(false, slot.enabledResolver(s))
        assertEquals(1f, slot.alphaResolver(s), 0.001f)
    }

    @Test
    fun `snapshot — pipeline=Idle, recording=Active(bluetooth=false) (two-row)`() {
        val s = stateOf(
            pipeline = PipelineUiState.Idle,
            recording = activeRec(useBluetooth = false),
        )
        val slot = catalog.recordSlot(s)
        assertEquals("Send (en)", slot.textResolver(s)?.toString())
        // Δ Phase 3: legacy writes left=send_20, right=null.
        assertNull(slot.iconResolver(s))
        assertEquals(true, slot.enabledResolver(s))
        assertEquals(1f, slot.alphaResolver(s), 0.001f)
    }

    @Test
    fun `snapshot — pipeline=Idle, recording=Paused (two-row)`() {
        val s = stateOf(
            pipeline = PipelineUiState.Idle,
            recording = pausedRec(useBluetooth = false),
        )
        val slot = catalog.recordSlot(s)
        assertEquals("Send (en)", slot.textResolver(s)?.toString())
        // Δ Phase 3: legacy holds whatever Active wrote (send_20 left,
        // bluetooth_20 or null right depending on the Active variant);
        // resolveRecordRightIcon Paused branch in the orchestrator state
        // reproduces this faithfully because Paused carries useBluetooth.
        assertNull(slot.iconResolver(s))
        assertEquals(true, slot.enabledResolver(s))
        assertEquals(1f, slot.alphaResolver(s), 0.001f)
    }

    // ── Permutation 5-8: pipeline = Preparing ──────────────────────────

    @Test
    fun `snapshot — pipeline=Preparing, recording=Idle (send-mode two-row)`() {
        val s = stateOf(pipeline = PipelineUiState.Preparing(sessionId = "s1"))
        val slot = catalog.recordSlot(s)
        assertEquals("Sending …", slot.textResolver(s)?.toString())
        // Δ Phase 3: legacy writes left=send_20, right=null.
        assertNull(slot.iconResolver(s))
        // #AE-OPTIK2: SEND_MODE record slot intentionally has no
        // enabledResolver — defaults to `{ true }` so the double-tap
        // auto-enter toggle stays reachable during the upload window.
        assertEquals(true, slot.enabledResolver(s))
        assertEquals(1f, slot.alphaResolver(s), 0.001f)
    }

    @Test
    fun `snapshot — pipeline=Preparing(autoEnterActive), recording=Idle (send-mode two-row)`() {
        val s = stateOf(pipeline = PipelineUiState.Preparing(sessionId = "s1", autoEnterActive = true))
        val slot = catalog.recordSlot(s)
        // #AE-DEEP2: the ↵-suffix lights up during the upload window so
        // the second-tap gets immediate visual confirmation.
        assertEquals("Sending … ↵", slot.textResolver(s)?.toString())
        assertNull(slot.iconResolver(s))
        assertEquals(true, slot.enabledResolver(s))
        assertEquals(1f, slot.alphaResolver(s), 0.001f)
    }

    @Test
    fun `snapshot — pipeline=Running, recording=Idle (send-mode two-row)`() {
        val s = stateOf(
            pipeline = PipelineUiState.Running(
                sessionId = "s1",
                target = InsertionTarget.INPUT_CONNECTION,
                completedSteps = 1,
                totalSteps = 3,
                elapsedMs = 1234,
            ),
        )
        val slot = catalog.recordSlot(s)
        // formatPipelineLabel test-stub format: "${done}/${total}${mark}  ${elapsedMs}ms"
        assertEquals("1/3  1234ms", slot.textResolver(s)?.toString())
        // Δ Phase 3: legacy writes the dynamic auto-enter ↵ icon (a
        // BitmapDrawable with PorterDuff knockout, not a DrawableRes Int)
        // on the right slot via the AutoEnterRenderer side-channel.
        assertNull(slot.iconResolver(s))
        assertEquals(true, slot.enabledResolver(s))
        assertEquals(1f, slot.alphaResolver(s), 0.001f)
    }

    @Test
    fun `snapshot — pipeline=Running(autoEnterActive), recording=Idle (send-mode two-row)`() {
        val s = stateOf(
            pipeline = PipelineUiState.Running(
                sessionId = "s1",
                target = InsertionTarget.INPUT_CONNECTION,
                autoEnterActive = true,
                completedSteps = 2,
                totalSteps = 3,
                elapsedMs = 5678,
            ),
        )
        val slot = catalog.recordSlot(s)
        assertEquals("2/3 ↵  5678ms", slot.textResolver(s)?.toString())
        assertNull(slot.iconResolver(s))
        assertEquals(true, slot.enabledResolver(s))
        assertEquals(1f, slot.alphaResolver(s), 0.001f)
    }

    // ── Permutation 9-12: pipeline = ReprocessStaging ──────────────────

    @Test
    fun `snapshot — pipeline=ReprocessStaging, recording=Idle (staging mode)`() {
        val s = stateOf(pipeline = PipelineUiState.ReprocessStaging("s1", "transcript"))
        val slot = catalog.recordSlot(s)
        // formatStagingLabel test-stub: "Audio 0:${secs}" — secs=0 from
        // resolveRecordButtonTextStaging until Spec 1 §3 wires the
        // duration field through ReprocessStaging.
        assertEquals("Audio 0:00 · Send", slot.textResolver(s)?.toString())
        // Δ Phase 3: legacy writes left=play_arrow_24, right=send_24.
        assertNull(slot.iconResolver(s))
        assertEquals(true, slot.enabledResolver(s))
        assertEquals(1f, slot.alphaResolver(s), 0.001f)
    }

    // ── Single-row layout sanity (forKeyboard branch coverage) ─────────

    @Test
    fun `snapshot — pipeline=Idle, recording=Active, singleRow=true (single-row)`() {
        val s = stateOf(
            pipeline = PipelineUiState.Idle,
            recording = activeRec(useBluetooth = false),
            singleRow = true,
        )
        val slot = catalog.recordSlot(s)
        // resolveRecordButtonText is shared between TWO_ROW and SINGLE_ROW
        // so the text matches the two-row Active assertion above.
        assertEquals("Send (en)", slot.textResolver(s)?.toString())
        assertNull(slot.iconResolver(s))
        assertEquals(true, slot.enabledResolver(s))
        assertEquals(1f, slot.alphaResolver(s), 0.001f)
    }

    @Test
    fun `snapshot — pipeline=Running, recording=Idle, singleRow=true (send-mode single-row)`() {
        val s = stateOf(
            pipeline = PipelineUiState.Running(
                sessionId = "s1",
                target = InsertionTarget.INPUT_CONNECTION,
                completedSteps = 0,
                totalSteps = 1,
                elapsedMs = 0,
            ),
            singleRow = true,
        )
        val slot = catalog.recordSlot(s)
        // resolveRecordButtonTextPipeline shared between TWO_ROW and
        // SINGLE_ROW SEND_MODE — same output.
        assertEquals("0/1  0ms", slot.textResolver(s)?.toString())
        assertNull(slot.iconResolver(s))
        assertEquals(true, slot.enabledResolver(s))
        assertEquals(1f, slot.alphaResolver(s), 0.001f)
    }

    // ── Bluetooth axis (relevant for Phase 3 wiring) ───────────────────

    @Test
    fun `snapshot — pipeline=Idle, recording=Active(bluetooth=true) (two-row)`() {
        val s = stateOf(
            pipeline = PipelineUiState.Idle,
            recording = activeRec(useBluetooth = true),
        )
        val slot = catalog.recordSlot(s)
        assertEquals("Send (en)", slot.textResolver(s)?.toString())
        // Δ Phase 3: legacy writes left=send_20, right=bluetooth_20.
        // The Catalog still has no iconResolver here; the new
        // `resolveRecordRightIcon` (Phase 1 helper) covers this branch
        // — see IconResolversTest.
        assertNull(slot.iconResolver(s))
        assertEquals(true, slot.enabledResolver(s))
        assertEquals(1f, slot.alphaResolver(s), 0.001f)
    }

    // ── ReprocessStaging enabled gate ──────────────────────────────────

    @Test
    fun `snapshot — REPROCESS_STAGING record slot is disabled outside ReprocessStaging`() {
        // The REPROCESS_STAGING mode is only entered via forKeyboard when
        // pipeline is in ReprocessStaging. But the slot's
        // enabledResolver guards defensively against a stale render —
        // verify it disables itself if pipeline drifts away mid-render.
        val mode = catalog.KEYBOARD_REPROCESS_STAGING
        val slot = mode.slots.first { it.logicalId == LogicalButtonId.RECORD }
        val state = stateOf(pipeline = PipelineUiState.Idle)
        assertEquals(false, slot.enabledResolver(state))
    }

    // ── helpers ────────────────────────────────────────────────────────

    private fun LayoutCatalog.recordSlot(state: DictateUiState): ButtonSlot {
        val mode = forKeyboard(state)
        return mode.slots.first { it.logicalId == LogicalButtonId.RECORD }
    }

    private fun stateOf(
        pipeline: PipelineUiState = PipelineUiState.Idle,
        recording: RecordingState = RecordingState.Idle,
        singleRow: Boolean = false,
    ): DictateUiState = DictateUiState.initial().copy(
        recording = recording,
        pipeline = pipeline,
        layout = LayoutState(singleRowMode = singleRow),
    )

    private fun activeRec(useBluetooth: Boolean) = RecordingState.Active(
        useBluetooth = useBluetooth, audioFile = stubAudioFile(), sessionId = "s-test",
    )

    private fun pausedRec(useBluetooth: Boolean) = RecordingState.Paused(
        useBluetooth = useBluetooth, audioFile = stubAudioFile(), sessionId = "s-test",
    )
}
