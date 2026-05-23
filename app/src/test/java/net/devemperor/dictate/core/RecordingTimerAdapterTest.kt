package net.devemperor.dictate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit tests for [RecordingTimerAdapter] — the orchestrator-side
 * recording-duration counter.
 *
 * **Quality-Gate K-4 exception.** The adapter holds an Android
 * [android.os.Handler] bound to the main [android.os.Looper]; the
 * looper requires Robolectric. Justification: tick scheduling
 * correctness is the contract under test. An alternative would be to
 * extract the scheduler behind an interface (like
 * [PauseTimeoutScheduler] does for [RecordingStateController]); that
 * refactor is a B5 concern and would inflate the adapter's surface for
 * a benefit that Robolectric already covers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingTimerAdapterTest {

    @Test
    fun `initial state is not running with zero elapsed`() {
        val adapter = RecordingTimerAdapter()
        assertFalse(adapter.isRunning)
        assertEquals(0L, adapter.elapsedMs)
    }

    @Test
    fun `start sets running true and elapsed zero`() {
        val adapter = RecordingTimerAdapter()
        adapter.start()
        assertTrue(adapter.isRunning)
        assertEquals(0L, adapter.elapsedMs)
    }

    @Test
    fun `start is idempotent — repeated start does not reset elapsed`() {
        val adapter = RecordingTimerAdapter()
        adapter.start()
        // Advance the looper to accumulate elapsed time.
        shadowOf(android.os.Looper.getMainLooper()).idleFor(
            300, java.util.concurrent.TimeUnit.MILLISECONDS,
        )
        val elapsedAfterFirstStart = adapter.elapsedMs
        assertTrue(
            "Looper.idleFor must run the scheduled tick at least once",
            elapsedAfterFirstStart >= 100L,
        )

        adapter.start()  // Second start while running — must be a no-op.

        // elapsedMs unchanged immediately after the second start.
        assertEquals(elapsedAfterFirstStart, adapter.elapsedMs)
    }

    @Test
    fun `pause stops the tick`() {
        val adapter = RecordingTimerAdapter()
        adapter.start()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(
            250, java.util.concurrent.TimeUnit.MILLISECONDS,
        )
        val before = adapter.elapsedMs

        adapter.pause()
        assertFalse(adapter.isRunning)

        // Run the looper longer — elapsed must NOT advance further.
        shadowOf(android.os.Looper.getMainLooper()).idleFor(
            500, java.util.concurrent.TimeUnit.MILLISECONDS,
        )
        assertEquals(
            "Paused timer must freeze elapsedMs",
            before, adapter.elapsedMs,
        )
    }

    @Test
    fun `resume restarts the tick from current elapsed`() {
        val adapter = RecordingTimerAdapter()
        adapter.start()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(
            200, java.util.concurrent.TimeUnit.MILLISECONDS,
        )
        val pausedAt = adapter.elapsedMs
        adapter.pause()

        adapter.resume()
        assertTrue(adapter.isRunning)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(
            150, java.util.concurrent.TimeUnit.MILLISECONDS,
        )
        assertTrue(
            "Resumed timer must continue from where it left off",
            adapter.elapsedMs > pausedAt,
        )
    }

    @Test
    fun `reset zeroes elapsed and stops`() {
        val adapter = RecordingTimerAdapter()
        adapter.start()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(
            300, java.util.concurrent.TimeUnit.MILLISECONDS,
        )

        adapter.reset()

        assertFalse(adapter.isRunning)
        assertEquals(0L, adapter.elapsedMs)
    }
}
