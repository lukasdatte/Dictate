package net.devemperor.dictate.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.state.PipelineUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM tests for [PipelineActivityTickerObserver].
 *
 * The observer subscribes to `state.pipeline`, runs a 1-second
 * Handler loop while the phase is [PipelineUiState.Running], and
 * dispatches `TickPipelineTimer` actions via the supplied callback.
 *
 * # Why a fake Handler?
 *
 * Production wires `Handler(Looper.getMainLooper())`. Robolectric
 * would let us schedule on the main looper but K-4 (no Android in
 * unit tests) pushes us to a tiny in-memory fake that records the
 * scheduled `Runnable`s and lets the test drive them manually.
 *
 * Coverage:
 *  - Enters Running → schedules a tick after TICK_INTERVAL_MS.
 *  - Tick fires the onTick callback and re-schedules itself.
 *  - Leaves Running (→ Idle / Preparing / Done) → cancels the loop.
 *  - Phase reentry (Running → Running data-only change, e.g.
 *    stepHistory append) does NOT re-trigger the loop start (the
 *    observer's `distinctUntilChanged` is on the phase CLASS).
 *  - `stop` cancels the loop.
 *  - Double `start` is idempotent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineActivityTickerObserverTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var stateFlow: MutableStateFlow<DictateUiState>
    private val ticks: MutableList<Long> = mutableListOf()
    private lateinit var fakeHandler: FakeHandler

    @Before
    fun setUp() {
        stateFlow = MutableStateFlow(DictateUiState.initial())
        ticks.clear()
        fakeHandler = FakeHandler()
    }

    private fun newObserver(): PipelineActivityTickerObserver =
        PipelineActivityTickerObserver(
            state = stateFlow,
            onTick = { ticks += System.nanoTime() },
            mainDispatcher = dispatcher,
            scheduler = fakeHandler,
        )

    private fun running(elapsedMs: Long = 0L): PipelineUiState.Running =
        PipelineUiState.Running(
            sessionId = "sid-R",
            target = InsertionTarget.INPUT_CONNECTION,
            elapsedMs = elapsedMs,
        )

    @Test
    fun `starting in Idle schedules no ticks`() = runTest(dispatcher) {
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()

        assertTrue("Idle phase MUST NOT schedule any tick", fakeHandler.scheduled.isEmpty())
        observer.stop()
    }

    @Test
    fun `Running phase schedules a tick after TICK_INTERVAL_MS`() = runTest(dispatcher) {
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()

        stateFlow.value = stateFlow.value.copy(pipeline = running())
        advanceUntilIdle()

        // One Runnable scheduled with delay 1000 ms.
        assertEquals(1, fakeHandler.scheduled.size)
        assertEquals(1000L, fakeHandler.scheduled.first().delayMs)
        // No tick fired yet (we haven't simulated the Handler firing).
        assertTrue(ticks.isEmpty())
        observer.stop()
    }

    @Test
    fun `tick fires onTick and re-schedules itself`() = runTest(dispatcher) {
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()

        stateFlow.value = stateFlow.value.copy(pipeline = running())
        advanceUntilIdle()
        assertEquals(1, fakeHandler.scheduled.size)

        // Simulate the Handler firing the first tick.
        fakeHandler.fireOldest()
        advanceUntilIdle()

        assertEquals(1, ticks.size)
        // The tick re-scheduled itself for another 1000 ms.
        assertEquals(1, fakeHandler.scheduled.size)
        assertEquals(1000L, fakeHandler.scheduled.first().delayMs)

        // Fire again — second tick.
        fakeHandler.fireOldest()
        advanceUntilIdle()
        assertEquals(2, ticks.size)
        assertEquals(1, fakeHandler.scheduled.size)

        observer.stop()
    }

    @Test
    fun `leaving Running cancels the loop`() = runTest(dispatcher) {
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()
        stateFlow.value = stateFlow.value.copy(pipeline = running())
        advanceUntilIdle()
        assertEquals(1, fakeHandler.scheduled.size)

        // Transition Running → Idle (e.g. PipelineDone).
        stateFlow.value = stateFlow.value.copy(pipeline = PipelineUiState.Idle)
        advanceUntilIdle()

        // The pending Runnable was removed.
        assertTrue(
            "Leaving Running MUST cancel the pending tick (removeCallbacks)",
            fakeHandler.scheduled.isEmpty(),
        )
        observer.stop()
    }

    @Test
    fun `Running to Running with new elapsedMs does NOT re-arm the loop`() = runTest(dispatcher) {
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()

        stateFlow.value = stateFlow.value.copy(pipeline = running(elapsedMs = 0L))
        advanceUntilIdle()
        assertEquals(1, fakeHandler.scheduled.size)

        // A reducer-restamp (Running → Running with different elapsedMs)
        // must NOT spawn another concurrent ticker — distinctUntilChanged
        // on the phase CLASS suppresses it.
        stateFlow.value = stateFlow.value.copy(pipeline = running(elapsedMs = 1000L))
        advanceUntilIdle()
        stateFlow.value = stateFlow.value.copy(pipeline = running(elapsedMs = 2000L))
        advanceUntilIdle()

        assertEquals(
            "Running → Running data-only changes MUST NOT spawn a second ticker.",
            1,
            fakeHandler.scheduled.size,
        )
        observer.stop()
    }

    @Test
    fun `stop cancels the pending tick`() = runTest(dispatcher) {
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()
        stateFlow.value = stateFlow.value.copy(pipeline = running())
        advanceUntilIdle()
        assertEquals(1, fakeHandler.scheduled.size)

        observer.stop()
        assertTrue("stop MUST cancel the pending Runnable", fakeHandler.scheduled.isEmpty())
    }

    @Test
    fun `tick fired after stop does NOT invoke onTick`() = runTest(dispatcher) {
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()
        stateFlow.value = stateFlow.value.copy(pipeline = running())
        advanceUntilIdle()
        val scheduled = fakeHandler.scheduled.first().runnable

        observer.stop()
        // Even if a stale Handler.postDelayed bypassed the
        // removeCallbacks (test simulates the race), the Runnable's
        // own `ticking` guard returns early.
        scheduled.run()

        assertTrue("No onTick after stop", ticks.isEmpty())
    }

    @Test
    fun `double start is idempotent`() = runTest(dispatcher) {
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()
        observer.start()
        advanceUntilIdle()

        stateFlow.value = stateFlow.value.copy(pipeline = running())
        advanceUntilIdle()

        assertEquals(
            "Double start MUST NOT spawn a second collector (would " +
                "schedule two concurrent tickers).",
            1,
            fakeHandler.scheduled.size,
        )
        observer.stop()
    }

    /**
     * In-memory [TickerScheduler] fake that records every scheduled
     * `Runnable` and lets the test drive the firing manually. Avoids
     * the K-4 forbidden Android-Handler instantiation.
     */
    private class FakeHandler : TickerScheduler {
        data class Scheduled(val runnable: Runnable, val delayMs: Long)

        val scheduled: MutableList<Scheduled> = mutableListOf()

        override fun postDelayed(r: Runnable, delayMs: Long) {
            scheduled += Scheduled(r, delayMs)
        }

        override fun removeCallbacks(r: Runnable) {
            scheduled.removeAll { it.runnable === r }
        }

        /**
         * Pop the oldest scheduled Runnable and invoke it (simulating
         * the Handler-loop firing it after its delay).
         */
        fun fireOldest() {
            val first = scheduled.removeAt(0)
            first.runnable.run()
        }
    }
}
