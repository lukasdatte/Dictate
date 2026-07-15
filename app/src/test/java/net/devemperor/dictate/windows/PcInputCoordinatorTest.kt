package net.devemperor.dictate.windows

import net.devemperor.dictate.shared.protocol.InputCommandKindWire
import net.devemperor.dictate.shared.protocol.InputCommandWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

/**
 * The 500-ms send window (§4.3.2, D5) with an injected clock and a manually-pumped executor.
 *
 * The manual executor is the whole trick: it defers each flush until [drain], so a burst arriving
 * *during* an in-flight send (simulated by the send lambda re-submitting) is buffered and flushed as
 * one coalesced batch — proving "first goes immediately, the rest ride one follow-up request".
 */
class PcInputCoordinatorTest {

    /** Runs queued tasks only when [drain] is called — lets the test control the in-flight window. */
    private class QueueExecutor : Executor {
        private val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.add(command) }
        fun drain() { while (queue.isNotEmpty()) queue.poll().run() }
    }

    private val executor = QueueExecutor()
    private var now = 0L
    private val sends = mutableListOf<List<InputCommandWire>>()
    private val failures = mutableListOf<PcInputFailure>()

    private fun left() = listOf(InputCommandWire(kind = InputCommandKindWire.CURSOR_LEFT))

    private fun coordinator(send: (List<InputCommandWire>) -> PcSendResult) =
        PcInputCoordinator(send = { sends += it; send(it) }, emitFailure = { failures += it }, executor = executor, clock = { now })

    @Test
    fun aSingleAction_isSentImmediatelyAsOneRequest() {
        val c = coordinator { PcSendResult.Sent }

        c.submit(left())
        executor.drain()

        assertEquals(1, sends.size)
        assertEquals(left(), sends.single())
    }

    @Test
    fun aBurstArrivingDuringTheInFlight_flushesAsExactlyTwoRequests_coalesced() {
        lateinit var c: PcInputCoordinator
        var first = true
        c = coordinator {
            if (first) {
                first = false
                // Three more left-presses land while the first request is in flight.
                repeat(3) { c.submit(left()) }
            }
            PcSendResult.Sent
        }

        c.submit(left())
        executor.drain()

        assertEquals("first immediate + one coalesced follow-up", 2, sends.size)
        assertEquals(listOf(InputCommandWire(kind = InputCommandKindWire.CURSOR_LEFT, count = 1)), sends[0])
        assertEquals(listOf(InputCommandWire(kind = InputCommandKindWire.CURSOR_LEFT, count = 3)), sends[1])
    }

    @Test
    fun aFailure_discardsTheWindow_withExactlyOneNotice_andNoRetry() {
        lateinit var c: PcInputCoordinator
        var first = true
        c = coordinator {
            if (first) {
                first = false
                repeat(2) { c.submit(left()) } // buffered behind the failing request
            }
            PcSendResult.Failed(PcInputFailure.UNREACHABLE, opensCircuit = true)
        }

        c.submit(left())
        executor.drain()

        assertEquals("only the in-flight batch was ever sent — no retry of the discarded window", 1, sends.size)
        assertEquals(listOf(PcInputFailure.UNREACHABLE), failures)
    }

    @Test
    fun whileTheCircuitIsOpen_furtherActionsAreDroppedSilently() {
        val c = coordinator { PcSendResult.Failed(PcInputFailure.UNREACHABLE, opensCircuit = true) }

        c.submit(left())
        executor.drain() // fails, opens the 3 s circuit at now = 0

        now = 2_000 // still inside the cooldown
        c.submit(left())
        executor.drain()

        assertEquals("no second attempt inside the cooldown", 1, sends.size)
        assertEquals("no second notice — the flood is suppressed", 1, failures.size)
    }

    @Test
    fun afterTheCircuitCooldown_sendingResumes() {
        var outcome: PcSendResult = PcSendResult.Failed(PcInputFailure.UNREACHABLE, opensCircuit = true)
        val c = coordinator { outcome }

        c.submit(left())
        executor.drain() // fails at now = 0

        now = PcInputCoordinator.CIRCUIT_OPEN_MS + 1
        outcome = PcSendResult.Sent
        c.submit(left())
        executor.drain()

        assertEquals(2, sends.size)
        assertEquals(1, failures.size)
    }

    @Test
    fun aNotPerformedResult_doesNotOpenTheCircuit_soTheNextActionStillTries() {
        val c = coordinator { PcSendResult.Failed(PcInputFailure.NOT_PERFORMED, opensCircuit = false) }

        c.submit(left())
        executor.drain()
        c.submit(left())
        executor.drain()

        assertEquals("no cooldown → both attempts go out", 2, sends.size)
        assertEquals(2, failures.size)
    }
}
