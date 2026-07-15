package net.devemperor.dictate.companion.server

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import net.devemperor.dictate.companion.CompanionContainer
import net.devemperor.dictate.companion.domain.CompanionSettings
import net.devemperor.dictate.companion.server.plugins.authenticated
import net.devemperor.dictate.companion.server.plugins.installCallLogging
import net.devemperor.dictate.companion.server.plugins.installSerialization
import net.devemperor.dictate.companion.server.plugins.installStatusPages
import net.devemperor.dictate.companion.server.routes.dispatchRoutes
import net.devemperor.dictate.companion.server.routes.healthRoutes
import net.devemperor.dictate.companion.server.routes.pairRoutes
import net.devemperor.dictate.companion.server.routes.syncRoutes

/**
 * The embedded HTTP server — the companion is the *only* server in the system (ADR-0017).
 *
 * **CIO, not Netty:** a local single-client server needs none of Netty's machinery, and every
 * megabyte of it would ride along in the jpackage bundle. CIO also binds an ephemeral port
 * (`port = 0`) cleanly, and that is what lets the E2E suite start a real server on a real socket in
 * every test instead of faking the transport.
 *
 * Start/stop are idempotent so the tray's "pause receiving" can toggle them without bookkeeping.
 *
 * **One connector per host** (ADR-0017 refinement): the [hosts] list becomes N CIO connectors on the
 * same port, so the companion can listen on the tailnet address alone, on several addresses at once,
 * or on `0.0.0.0`. The list is never empty — the domain resolves a dead selection to loopback first.
 */
class CompanionServer(
    private val container: CompanionContainer,
    private val hosts: List<String> = listOf(CompanionSettings.BIND_ALL),
    private val port: Int = CompanionSettings.DEFAULT_PORT,
) {

    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    val isRunning: Boolean get() = engine != null

    fun start() {
        if (engine != null) return
        engine = embeddedServer(
            CIO,
            environment = applicationEnvironment { },
            configure = { hosts.forEach { host -> connector { this.host = host; this.port = this@CompanionServer.port } } },
            module = { companionModule(container) },
        ).also { it.start(wait = false) }
    }

    fun stop() {
        engine?.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
        engine = null
    }

    /** Every bound `host:port` — the honest accessor when there is more than one connector. */
    fun resolvedEndpoints(): List<Pair<String, Int>> {
        val running = checkNotNull(engine) { "server is not running" }
        return runBlocking { running.engine.resolvedConnectors().map { it.host to it.port } }
    }

    /**
     * The port the socket actually got — with `port = 0` the only way to know it, and the pairing QR
     * needs it.
     *
     * Guards the multi-connector footgun: with `port = 0` **each** connector gets its own ephemeral
     * port, so "the" bound port is a lie unless they all agree (which they do for a fixed port, and
     * with a single connector). Call [resolvedEndpoints] when they might not.
     *
     * @throws IllegalStateException when the server is not running, or when connectors bound
     *   different ports.
     */
    fun boundPort(): Int {
        val ports = resolvedEndpoints().map { it.second }.distinct()
        check(ports.size == 1) { "connectors bound $ports; use resolvedEndpoints()" }
        return ports.first()
    }

    companion object {
        private const val GRACE_MILLIS = 1_000L
        private const val TIMEOUT_MILLIS = 2_000L
    }
}

/**
 * The whole HTTP surface in one function — also the unit a `testApplication` could mount, should a
 * route ever need a test that does not deserve a real socket.
 */
fun Application.companionModule(container: CompanionContainer) {
    installSerialization()
    installCallLogging()
    installStatusPages()

    routing {
        pairRoutes(container.pairingService)

        authenticated(container.authService) {
            dispatchRoutes(container.dispatchService)
            syncRoutes(container.syncService)
            healthRoutes(container.healthService)
        }
    }
}
