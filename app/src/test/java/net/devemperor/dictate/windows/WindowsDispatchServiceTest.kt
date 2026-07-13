package net.devemperor.dictate.windows

import net.devemperor.dictate.preferences.WindowsTarget
import net.devemperor.dictate.shared.client.DispatchClient
import net.devemperor.dictate.shared.client.DispatchError
import net.devemperor.dictate.shared.client.DispatchResult
import net.devemperor.dictate.shared.protocol.DispatchRequest
import net.devemperor.dictate.shared.protocol.SessionOriginWire
import net.devemperor.dictate.shared.protocol.SessionUpsert
import net.devemperor.dictate.shared.sync.SyncClient
import net.devemperor.dictate.shared.protocol.SyncCursor
import net.devemperor.dictate.shared.sync.SyncSource
import net.devemperor.dictate.shared.transport.DispatchTransport
import net.devemperor.dictate.shared.transport.HttpResponseLite
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [WindowsDispatchService] — the pure, blocking, never-throwing send (ADR-0019).
 */
class WindowsDispatchServiceTest {

    private val target = WindowsTarget("http://vm-win:8756", "device-1", "s3cr3t", "Office PC")
    private val emptySource = object : SyncSource {
        override fun sessionsAfter(cursor: SyncCursor?, limit: Int): List<SessionUpsert> = emptyList()
    }
    private val request = DispatchRequest(sessionId = "s1", text = "hi", createdAt = 1_000L, origin = SessionOriginWire.KEYBOARD)

    private fun service(transport: DispatchTransport, logger: (String) -> Unit = {}) =
        WindowsDispatchService(
            clientFactory = { t -> DispatchClient(transport) { t.credentials() } },
            syncClientFactory = { t -> SyncClient(DispatchClient(transport) { t.credentials() }, emptySource) },
            logger = logger,
        )

    @Test
    fun `send returns Success on a delivered 200`() {
        val result = service(ProgrammableTransport.delivered("s1")).send(target, request)
        assertTrue(result is DispatchResult.Success)
    }

    @Test
    fun `send returns a Failure classified Unreachable on an IOException`() {
        val result = service(ProgrammableTransport.unreachable()).send(target, request)
        result as DispatchResult.Failure
        assertTrue(result.error is DispatchError.Unreachable)
    }

    @Test
    fun `syncAfterSend never throws and logs a non-UpToDate outcome`() {
        // A transport that fails the sync's cursor GET → SyncOutcome.Failed, which must be swallowed.
        val brokenSync = object : DispatchTransport {
            override fun post(path: String, body: String, headers: Map<String, String>): HttpResponseLite =
                throw IOException("unused")
            override fun get(path: String, headers: Map<String, String>): HttpResponseLite =
                throw IOException("cursor unreachable")
        }
        val logs = mutableListOf<String>()
        // Does not throw:
        service(brokenSync, logger = { logs += it }).syncAfterSend(target)
        assertTrue(logs.isNotEmpty())
    }
}
