package net.devemperor.dictate.companion

import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.data.SqlDelightDeviceRepository
import net.devemperor.dictate.companion.data.SqlDelightHistoryRepository
import net.devemperor.dictate.companion.domain.net.BindSelection
import net.devemperor.dictate.companion.domain.port.NetworkAdapter
import net.devemperor.dictate.companion.domain.port.NetworkInterfaces
import net.devemperor.dictate.companion.fakes.FakeTextInserter
import net.devemperor.dictate.companion.fakes.MutableClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The start-up sequence that used to live inline in `main()` — now a unit under test.
 *
 * The two rules proven here (materialise the first-run selection exactly once; persist an auto-heal so
 * it does not repeat every launch) are load-bearing for the bind-address contract (ADR-0023) and were
 * untestable while they sat in a Compose `application {}` block. That is the second reason the logic
 * was extracted; the first is latency (it now runs off the UI thread). See [CompanionBootstrap].
 */
class CompanionBootstrapTest {

    private val database = CompanionDatabase.inMemory()
    private var startedServer: net.devemperor.dictate.companion.server.CompanionServer? = null

    @After
    fun tearDown() {
        startedServer?.stop()
    }

    private fun container(interfaces: NetworkInterfaces) = CompanionContainer.forTest(
        inserter = FakeTextInserter(),
        clock = MutableClock(),
        devices = SqlDelightDeviceRepository(database),
        history = SqlDelightHistoryRepository(database),
        networkInterfaces = interfaces,
    )

    @Test
    fun resolveBinding_firstRun_materialisesTailscaleDefaultAndPersistsItOnce() {
        val container = container(interfacesWith(tailscale = TAILNET_A))

        val binding = CompanionBootstrap.resolveBinding(container)

        assertEquals(listOf(TAILNET_A), binding.hosts)
        // Persisted as an explicit address, not a mode that re-resolves — so a later tailnet re-auth
        // is a visible heal, not a silent rebind.
        assertEquals(BindSelection.Explicit(setOf(TAILNET_A)), container.settings.storedBindSelection)
    }

    @Test
    fun resolveBinding_healedSelection_isPersisted() {
        val container = container(interfacesWith(tailscale = TAILNET_B))
        // The user was bound to a tailnet address that has since moved (re-auth). Only one tailnet
        // candidate exists, so the heal is unambiguous.
        container.settings.bindSelection = BindSelection.Explicit(setOf(TAILNET_A))

        val binding = CompanionBootstrap.resolveBinding(container)

        assertEquals(listOf(TAILNET_B), binding.hosts)
        assertEquals(BindSelection.Explicit(setOf(TAILNET_B)), container.settings.storedBindSelection)
    }

    @Test
    fun start_bringsUpAServerBoundToTheResolvedHosts() {
        // Loopback-only machine → no tailnet → first setup materialises AllInterfaces (0.0.0.0), an
        // address the VM can actually bind (a tailnet literal here would not exist on this host).
        val container = container(NetworkInterfaces {
            listOf(NetworkAdapter(name = "lo", addresses = listOf("127.0.0.1"), isLoopback = true))
        })

        val ready = CompanionBootstrap.start { container }
        startedServer = ready.server

        assertTrue(ready.server.isRunning)
        assertEquals(listOf(BIND_ALL), ready.binding.hosts)
        assertTrue(ready.server.resolvedEndpoints().all { it.first == BIND_ALL })
    }

    private fun interfacesWith(tailscale: String) = NetworkInterfaces {
        listOf(
            NetworkAdapter(name = "lo", addresses = listOf("127.0.0.1"), isLoopback = true),
            NetworkAdapter(name = "tailscale0", addresses = listOf(tailscale), isLoopback = false),
        )
    }

    private companion object {
        // Two CGNAT (100.64.0.0/10) addresses — the range Tailscale hands out; both classify as
        // TAILSCALE, and the heal test moves the binding from one to the other.
        const val TAILNET_A = "100.64.0.1"
        const val TAILNET_B = "100.100.100.100"
        const val BIND_ALL = "0.0.0.0"
    }
}
