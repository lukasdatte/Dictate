package net.devemperor.dictate.companion.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The serial run-queue: FIFO, one-at-a-time, session-deduped (desktop-host.md §5.6, ADR-0009). */
class JobQueueTest {

    @Test
    fun inlineQueue_runsImmediatelyInOrder() {
        val order = mutableListOf<String>()
        val queue = InlineJobQueue()
        queue.submit("a") { order += "a" }
        queue.submit("b") { order += "b" }
        assertEquals(listOf("a", "b"), order)
    }

    @Test
    fun serialQueue_runsJobsFifoOneAtATime() {
        val queue = SerialJobQueue()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(3)
        val active = java.util.concurrent.atomic.AtomicInteger(0)
        val maxConcurrent = java.util.concurrent.atomic.AtomicInteger(0)

        listOf("a", "b", "c").forEach { id ->
            queue.submit(id) {
                val now = active.incrementAndGet()
                maxConcurrent.updateAndGet { maxOf(it, now) }
                Thread.sleep(20)
                order += id
                active.decrementAndGet()
                done.countDown()
            }
        }

        assertTrue("jobs finished", done.await(5, TimeUnit.SECONDS))
        assertEquals("submission order preserved", listOf("a", "b", "c"), order.toList())
        assertEquals("never more than one job at a time", 1, maxConcurrent.get())
        queue.shutdown()
    }

    @Test
    fun serialQueue_dedupsAWhileItIsStillRunning() {
        val queue = SerialJobQueue()
        val runs = java.util.concurrent.atomic.AtomicInteger(0)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        queue.submit("a") {
            runs.incrementAndGet()
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        // "a" is mid-flight — a re-submit of the same session must be dropped, not queued.
        queue.submit("a") { runs.incrementAndGet() }
        release.countDown()

        // Give the worker a moment to (not) run a second "a".
        Thread.sleep(100)
        assertEquals("the duplicate submission was ignored", 1, runs.get())
        queue.shutdown()
    }

    @Test
    fun serialQueue_acceptsTheSameSessionAgainAfterItFinished() {
        val queue = SerialJobQueue()
        val runs = java.util.concurrent.atomic.AtomicInteger(0)

        val first = CountDownLatch(1)
        queue.submit("a") { runs.incrementAndGet(); first.countDown() }
        assertTrue(first.await(5, TimeUnit.SECONDS))

        val second = CountDownLatch(1)
        queue.submit("a") { runs.incrementAndGet(); second.countDown() }
        assertTrue("dedup clears once the job returns", second.await(5, TimeUnit.SECONDS))
        assertEquals(2, runs.get())
        queue.shutdown()
    }
}
