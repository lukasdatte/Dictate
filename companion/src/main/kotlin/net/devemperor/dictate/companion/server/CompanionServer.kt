package net.devemperor.dictate.companion.server

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import net.devemperor.dictate.companion.CompanionContainer
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
 */
class CompanionServer(
    private val container: CompanionContainer,
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
) {

    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    val isRunning: Boolean get() = engine != null

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, host = host, port = port) { companionModule(container) }
            .also { it.start(wait = false) }
    }

    fun stop() {
        engine?.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
        engine = null
    }

    /**
     * The port the socket actually got — with `port = 0` that is the only way to know it, and the
     * pairing QR needs it.
     *
     * @throws IllegalStateException when the server is not running.
     */
    fun boundPort(): Int {
        val running = checkNotNull(engine) { "server is not running" }
        return runBlocking { running.engine.resolvedConnectors().first().port }
    }

    companion object {
        /** 0.0.0.0: the phone reaches the PC over the tailnet interface, not over loopback. */
        const val DEFAULT_HOST = "0.0.0.0"
        const val DEFAULT_PORT = 8756

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
