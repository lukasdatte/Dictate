package net.devemperor.dictate.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for [PromptChipsBusyObserver].
 *
 * Pins the AC-E + AC-P-1 contract: the prompt-chips disable predicate
 * tracks the orchestrator-authoritative `state.recording` AND
 * `state.pipeline` axes (NEVER the legacy
 * `recordingStateController.getState()`), with `distinctUntilChanged`
 * suppressing per-tick churn (e.g. `elapsedMs` updates during Running).
 *
 * Coverage:
 *  - First emit carries the current "busy" value (Idle, Idle = false).
 *  - Recording transitions Idle → Active → Paused → Idle flip the bit.
 *  - Pipeline transitions Idle → Preparing → Running → Idle flip the bit.
 *  - `RecordingState.Preparing` is also "busy".
 *  - Per-tick `elapsedMs` increments in `Running` do NOT re-emit
 *    (distinctUntilChanged on the derived boolean).
 *  - `ReprocessStaging` is NOT busy (chips remain enabled).
 *  - `stop` cancels the collector.
 *  - Double `start` is idempotent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PromptChipsBusyObserverTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var stateFlow: MutableStateFlow<DictateUiState>
    private val captured: MutableList<Boolean> = mutableListOf()

    @Before
    fun setUp() {
        stateFlow = MutableStateFlow(DictateUiState.initial())
        captured.clear()
    }

    private fun audioFile(): File = File("/tmp/dictate-test-prompt-chips.m4a")

    private fun newObserver(): PromptChipsBusyObserver = PromptChipsBusyObserver(
        state = stateFlow,
        onChanged = { busy -> captured += busy },
        mainDispatcher = dispatcher,
    )

    @Test
    fun `first emit on Idle state reports busy=false`() = runTest(dispatcher) {
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()
        assertEquals(listOf(false), captured)
        observer.stop()
    }

    @Test
    fun `Active recording flips the bit to busy=true`() = runTest(dispatcher) {
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()
        captured.clear()

        stateFlow.value = stateFlow.value.copy(
            recording = RecordingState.Active(
                useBluetooth = false,
                audioFile = audioFile(),
                sessionId = "sid-A",
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(true), captured)
        observer.stop()
    }

    @Test
    fun `Paused recording keeps busy=true`() = runTest(dispatcher) {
        // Start busy via Active so the distinctUntilChanged latch is
        // already `true` and the Paused emit MUST stay true (one continuous
        // busy phase — the chip should not flicker enabled).
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()

        stateFlow.value = stateFlow.value.copy(
            recording = RecordingState.Active(
                useBluetooth = false,
                audioFile = audioFile(),
                sessionId = "sid-A",
            ),
        )
        advanceUntilIdle()
        captured.clear()

        stateFlow.value = stateFlow.value.copy(
            recording = RecordingState.Paused(
                useBluetooth = false,
                audioFile = audioFile(),
                sessionId = "sid-A",
            ),
        )
        advanceUntilIdle()

        // distinctUntilChanged collapses Active→Paused (both busy=true)
        // — no extra emit.
        assertEquals(emptyList<Boolean>(), captured)
        observer.stop()
    }

    @Test
    fun `Preparing recording is also busy`() = runTest(dispatcher) {
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()
        captured.clear()

        stateFlow.value = stateFlow.value.copy(
            recording = RecordingState.Preparing(
                useBluetooth = true,
                audioFile = audioFile(),
                sessionId = "sid-B",
                awaitingSco = true,
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(true), captured)
        observer.stop()
    }

    @Test
    fun `Pipeline Preparing flips the bit to busy=true`() = runTest(dispatcher) {
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()
        captured.clear()

        stateFlow.value = stateFlow.value.copy(
            pipeline = PipelineUiState.Preparing(sessionId = "sid-P"),
        )
        advanceUntilIdle()

        assertEquals(listOf(true), captured)
        observer.stop()
    }

    @Test
    fun `Pipeline Running flips the bit to busy=true`() = runTest(dispatcher) {
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()
        captured.clear()

        stateFlow.value = stateFlow.value.copy(
            pipeline = PipelineUiState.Running(
                sessionId = "sid-R",
                target = InsertionTarget.INPUT_CONNECTION,
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(true), captured)
        observer.stop()
    }

    @Test
    fun `Running elapsedMs increments do NOT re-emit`() = runTest(dispatcher) {
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()

        stateFlow.value = stateFlow.value.copy(
            pipeline = PipelineUiState.Running(
                sessionId = "sid-R",
                target = InsertionTarget.INPUT_CONNECTION,
                elapsedMs = 0L,
            ),
        )
        advanceUntilIdle()
        captured.clear()

        // Per-second-ticker would update elapsedMs across multiple ticks
        // — the derived `busy: Boolean` stays true → no new emit.
        for (ms in listOf(1000L, 2000L, 3000L, 4000L)) {
            val cur = stateFlow.value.pipeline as PipelineUiState.Running
            stateFlow.value = stateFlow.value.copy(
                pipeline = cur.copy(elapsedMs = ms),
            )
            advanceUntilIdle()
        }

        assertEquals(
            "distinctUntilChanged MUST suppress per-tick re-emits when the " +
                "derived busy-boolean does not change (AC-P-1 / B-D-3 " +
                "side-effect mitigation R-2).",
            emptyList<Boolean>(),
            captured,
        )
        observer.stop()
    }

    @Test
    fun `ReprocessStaging is NOT busy — chips stay enabled`() = runTest(dispatcher) {
        // Staging is an edit phase; the user explicitly stages
        // recordings to reorder the prompt queue. The chips must remain
        // tappable so the user can curate the queue.
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()
        captured.clear()

        stateFlow.value = stateFlow.value.copy(
            pipeline = PipelineUiState.ReprocessStaging(
                sessionId = "sid-S",
                transcript = "hello",
            ),
        )
        advanceUntilIdle()

        // Idle (busy=false) → Staging (also busy=false per the predicate)
        // → distinctUntilChanged collapses the emit.
        assertEquals(emptyList<Boolean>(), captured)
        observer.stop()
    }

    @Test
    fun `stop cancels the collector`() = runTest(dispatcher) {
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()
        captured.clear()
        observer.stop()

        stateFlow.value = stateFlow.value.copy(
            recording = RecordingState.Active(
                useBluetooth = false,
                audioFile = audioFile(),
                sessionId = "sid-A",
            ),
        )
        advanceUntilIdle()

        assertEquals(emptyList<Boolean>(), captured)
    }

    @Test
    fun `double start is idempotent`() = runTest(dispatcher) {
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()
        observer.start()
        advanceUntilIdle()
        captured.clear()

        stateFlow.value = stateFlow.value.copy(
            pipeline = PipelineUiState.Running(
                sessionId = "sid-R",
                target = InsertionTarget.INPUT_CONNECTION,
            ),
        )
        advanceUntilIdle()

        // One emit, not two — proves only one collector is live.
        assertEquals(listOf(true), captured)
        observer.stop()
    }
}
