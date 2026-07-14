package net.devemperor.dictate.windows

import net.devemperor.dictate.preferences.WindowsTarget
import net.devemperor.dictate.shared.client.DispatchClient
import net.devemperor.dictate.shared.protocol.InsertionOutcomeWire
import net.devemperor.dictate.shared.protocol.SessionOriginWire
import net.devemperor.dictate.shared.protocol.SessionUpsert
import net.devemperor.dictate.shared.sync.SyncClient
import net.devemperor.dictate.shared.protocol.SyncCursor
import net.devemperor.dictate.shared.sync.SyncSource
import net.devemperor.dictate.shared.transport.DispatchTransport
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.PipelineErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Unit tests for [WindowsDispatchCoordinator] — the ONE dispatch primitive (ADR-0019).
 *
 * Drives the real [WindowsDispatchService] + [DispatchClient] over a fake transport, so the full
 * encode/validate/classify path runs; only the socket is fake.
 */
class WindowsDispatchCoordinatorTest {

    private val target = WindowsTarget("http://vm-win:8756", "device-1", "s3cr3t", "Office PC")
    private val emitted = mutableListOf<Action>()
    private val audits = mutableListOf<Triple<String, String, String>>()

    /** Synchronous executor so the fire-and-forget body runs inline. */
    private val inline = Executor { it.run() }

    /** Spy source: records each pull so a test can prove the post-dispatch sync actually fired (ADR-0020). */
    private val syncPulls = mutableListOf<SyncCursor?>()
    private val spySource = object : SyncSource {
        override fun sessionsAfter(cursor: SyncCursor?, limit: Int): List<SessionUpsert> {
            syncPulls += cursor
            return emptyList()
        }
    }

    private fun coordinator(
        transport: DispatchTransport,
        targetProvider: () -> WindowsTarget? = { target },
    ): WindowsDispatchCoordinator {
        val service = WindowsDispatchService(
            clientFactory = { t -> DispatchClient(transport) { t.credentials() } },
            syncClientFactory = { t -> SyncClient(DispatchClient(transport) { t.credentials() }, spySource) },
        )
        return WindowsDispatchCoordinator(
            service = service,
            targetProvider = targetProvider,
            emitAction = { emitted += it },
            audit = { sessionId, text, deviceId -> audits += Triple(sessionId, text, deviceId) },
            executor = inline,
        )
    }

    @Test
    fun `not paired emits Failed(UNAUTHORIZED) and no Started`() {
        coordinator(ProgrammableTransport.delivered("s1"), targetProvider = { null })
            .dispatch("s1", "hi", 1_000L, SessionOriginWire.KEYBOARD, acknowledgeOnSuccess = true)

        assertEquals(1, emitted.size)
        val failed = emitted.single() as Action.WindowsDispatchAction.Failed
        assertEquals("s1", failed.sessionId)
        assertEquals(PipelineErrorKind.WINDOWS_UNAUTHORIZED, failed.errorKind)
    }

    @Test
    fun `delivered emits Started then Succeeded, audits, and triggers sync`() {
        coordinator(ProgrammableTransport.delivered("s1", InsertionOutcomeWire.TYPED_CTRL_V))
            .dispatch("s1", "hello", 1_000L, SessionOriginWire.KEYBOARD, acknowledgeOnSuccess = true, surfacedAsPending = false)

        val started = emitted[0] as Action.WindowsDispatchAction.Started
        assertEquals("s1", started.sessionId)
        assertTrue(started.acknowledgeOnSuccess)
        val succeeded = emitted[1] as Action.WindowsDispatchAction.Succeeded
        assertEquals(InsertionOutcomeWire.TYPED_CTRL_V, succeeded.outcome)
        // Audit carries the target device id.
        assertEquals(Triple("s1", "hello", "device-1"), audits.single())
        // ADR-0020 — a delivered dispatch triggers the lazy cursor sync (review — L4-b: this line was
        // unasserted, so deleting the coordinator's `service.sync(target)` passed the whole suite).
        assertEquals("a delivered dispatch must trigger exactly one sync pull", 1, syncPulls.size)
    }

    @Test
    fun `clipboard-only is still a delivered success`() {
        coordinator(ProgrammableTransport.delivered("s1", InsertionOutcomeWire.CLIPBOARD_ONLY))
            .dispatch("s1", "hello", 1_000L, SessionOriginWire.KEYBOARD, acknowledgeOnSuccess = true)

        val succeeded = emitted[1] as Action.WindowsDispatchAction.Succeeded
        assertEquals(InsertionOutcomeWire.CLIPBOARD_ONLY, succeeded.outcome)
    }

    @Test
    fun `unreachable emits Started then Failed(UNREACHABLE) and no audit`() {
        coordinator(ProgrammableTransport.unreachable())
            .dispatch("s1", "hello", 1_000L, SessionOriginWire.KEYBOARD, acknowledgeOnSuccess = true)

        assertTrue(emitted[0] is Action.WindowsDispatchAction.Started)
        val failed = emitted[1] as Action.WindowsDispatchAction.Failed
        assertEquals(PipelineErrorKind.WINDOWS_UNREACHABLE, failed.errorKind)
        assertTrue(audits.isEmpty())
        // ADR-0020 — sync is triggered ONLY on the delivered branch, never on a failure.
        assertTrue("a failed dispatch must not trigger a sync", syncPulls.isEmpty())
    }

    @Test
    fun `Started carries the surfacedAsPending flag through`() {
        coordinator(ProgrammableTransport.delivered("s1"))
            .dispatch("s1", "hello", 1_000L, SessionOriginWire.HISTORY_REPROCESS, acknowledgeOnSuccess = false, surfacedAsPending = true)

        val started = emitted[0] as Action.WindowsDispatchAction.Started
        assertTrue(started.surfacedAsPending)
        assertEquals(false, started.acknowledgeOnSuccess)
    }
}
