package net.devemperor.dictate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [PipelineTerminalDispatchGuard] — the process-wide
 * once-guard that enforces "exactly one terminal dispatch per sessionId"
 * across the bridge's delegate-delivery, the headless fallback, and the
 * upcoming bind-reconciliation (ADR-0011).
 */
class PipelineTerminalDispatchGuardTest {

    @Test
    fun `first tryConsume for a session wins`() {
        val guard = PipelineTerminalDispatchGuard()
        assertTrue(guard.tryConsume("sess-A"))
    }

    @Test
    fun `second tryConsume for the same session loses`() {
        val guard = PipelineTerminalDispatchGuard()
        guard.tryConsume("sess-A")
        assertFalse(
            "A session already terminally dispatched must not be consumable twice",
            guard.tryConsume("sess-A"),
        )
    }

    @Test
    fun `different sessions are independent`() {
        val guard = PipelineTerminalDispatchGuard()
        assertTrue(guard.tryConsume("sess-A"))
        assertTrue(guard.tryConsume("sess-B"))
    }

    @Test
    fun `parallel tryConsume for the same session yields exactly one winner`() {
        val guard = PipelineTerminalDispatchGuard()
        val threads = 32
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val winners = AtomicInteger(0)
        try {
            repeat(threads) {
                pool.submit {
                    ready.countDown()
                    go.await()
                    if (guard.tryConsume("sess-race")) {
                        winners.incrementAndGet()
                    }
                }
            }
            ready.await(5, TimeUnit.SECONDS)
            go.countDown() // release all racers at once
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(
            "Exactly one racer may win the terminal dispatch for a session",
            1, winners.get(),
        )
    }
}
