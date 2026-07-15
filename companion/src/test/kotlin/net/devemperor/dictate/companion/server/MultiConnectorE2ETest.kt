package net.devemperor.dictate.companion.server

import net.devemperor.dictate.companion.CompanionContainer
import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.data.SqlDelightDeviceRepository
import net.devemperor.dictate.companion.data.SqlDelightHistoryRepository
import net.devemperor.dictate.companion.domain.net.AddressCatalog
import net.devemperor.dictate.companion.domain.net.BindSelection
import net.devemperor.dictate.companion.domain.port.NetworkAdapter
import net.devemperor.dictate.companion.fakes.FakeTextInserter
import net.devemperor.dictate.companion.fakes.MutableClock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress

/**
 * The multi-connector binding and the `boundPort()` footgun guard, over **real** CIO sockets on
 * loopback — the same "make them talk over a socket" stance as [CompanionE2ETest].
 *
 * Also the regression net for the bestand bug: a bind address that no longer exists used to make the
 * app throw on start (the free-text field was unvalidated and `start()` was uncaught). The domain now
 * resolves such a selection to loopback, so the server starts and stays correctable — asserted here
 * end-to-end, not just in the resolver unit test.
 */
class MultiConnectorE2ETest {

    private val database = CompanionDatabase.inMemory()
    private val container = CompanionContainer.forTest(
        inserter = FakeTextInserter(),
        clock = MutableClock(),
        devices = SqlDelightDeviceRepository(database),
        history = SqlDelightHistoryRepository(database),
    )
    private var server: CompanionServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun httpGet(host: String, port: Int): Int =
        OkHttpClient().newCall(Request.Builder().url("http://$host:$port/").build()).execute().use { it.code }

    @Test
    fun twoConnectors_bothServe() {
        assumeTrue("needs a usable 127.0.0.2 loopback alias", loopbackAliasWorks("127.0.0.2"))
        val port = freePort()
        server = CompanionServer(container, hosts = listOf("127.0.0.1", "127.0.0.2"), port = port).also { it.start() }

        // The pair endpoint is unauthenticated; any non-5xx proves the connector accepted the socket.
        assertTrue(httpGet("127.0.0.1", port) in 200..499)
        assertTrue(httpGet("127.0.0.2", port) in 200..499)
        assertEquals(2, server!!.resolvedEndpoints().size)
    }

    @Test
    fun singleConnectorOnEphemeralPort_boundPortIsHonest() {
        server = CompanionServer(container, hosts = listOf("127.0.0.1"), port = 0).also { it.start() }
        assertTrue(server!!.boundPort() > 0)
    }

    @Test
    fun boundPort_throwsWhenConnectorsDisagree() {
        // Two ephemeral connectors get two different ports; boundPort() must refuse to pick one
        // rather than silently return the first (the pre-fix behaviour).
        assumeTrue(loopbackAliasWorks("127.0.0.2"))
        server = CompanionServer(container, hosts = listOf("127.0.0.1", "127.0.0.2"), port = 0).also { it.start() }
        assertThrows(IllegalStateException::class.java) { server!!.boundPort() }
    }

    @Test
    fun aStoredAddressThatVanished_stillStarts_onLoopback() {
        // Regression: a selection pointing at an address no interface has. Catalogue holds only a LAN
        // address, so no unambiguous heal → loopback. The old code would have thrown on start.
        val catalog = AddressCatalog {
            listOf(NetworkAdapter("enp3s0", listOf("192.168.1.42"), isLoopback = false))
        }
        val resolved = catalog.resolve(BindSelection.Explicit(setOf("100.66.155.18")))
        assertEquals(listOf("127.0.0.1"), resolved.hosts)

        val port = freePort()
        server = CompanionServer(container, hosts = resolved.hosts, port = port).also { it.start() }
        assertTrue("the app must start, not throw, on a dead selection", server!!.isRunning)
        assertTrue(httpGet("127.0.0.1", port) in 200..499)
    }

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    private fun loopbackAliasWorks(address: String): Boolean = try {
        java.net.ServerSocket().use {
            it.bind(java.net.InetSocketAddress(InetAddress.getByName(address), 0))
            true
        }
    } catch (e: Exception) {
        false
    }
}
