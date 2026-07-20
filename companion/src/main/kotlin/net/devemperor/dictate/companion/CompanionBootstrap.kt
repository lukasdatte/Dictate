package net.devemperor.dictate.companion

import net.devemperor.dictate.companion.domain.net.ResolvedBinding
import net.devemperor.dictate.companion.server.CompanionServer

/**
 * The graph and the running server, once start-up has finished — what [CompanionBootstrap.start]
 * hands the UI so the window can finally show history and a pairing QR.
 */
class ReadyCompanion(
    val container: CompanionContainer,
    val binding: ResolvedBinding,
    val server: CompanionServer,
)

/**
 * Everything that must happen before the window shows anything useful: open the database, resolve the
 * bind address (materialising the first-run default and persisting an auto-heal), and start the
 * server. Extracted out of `main()`'s Compose `application {}` block for two reasons that pull the
 * same way:
 *
 * - **Latency — why it is a plain function, not a `remember {}`.** On a cold Windows start this work is
 *   measured at ~0.4–0.5 s of SQLite native-library extraction plus Ktor CIO class-loading. While it
 *   ran inside the composition it delayed the tray icon *and* the window frame by exactly that much.
 *   `main()` now runs it on a background dispatcher and paints a loading state until it returns, so
 *   the frame appears as soon as Compose/Skiko is up rather than after the database and the socket.
 *   (The remaining, dominant cold-start cost is the JVM + Skiko itself; see the diagnosis report —
 *   that one needs CDS, not a thread.)
 * - **Testability — why the binding logic moved here.** The two bind-address rules below (materialise
 *   the first-run selection exactly once; persist an auto-heal so it does not repeat every launch) are
 *   load-bearing for ADR-0023 and could not be reached by a test while they sat inline in a Compose
 *   entry point. `CompanionBootstrapTest` now drives them against a `forTest` container.
 */
object CompanionBootstrap {

    /**
     * Open the graph, resolve the binding, start the server.
     *
     * @param containerFactory the graph to boot; the production graph by default. A test injects a
     *   `forTest` container so it can assert the settings side effects without a real database file.
     */
    fun start(containerFactory: () -> CompanionContainer = { CompanionContainer.production() }): ReadyCompanion {
        val container = containerFactory()
        val binding = resolveBinding(container)
        val server = CompanionServer(container, binding.hosts, container.settings.port)
        server.start()
        // Start the background catalog-sync poll (Block E2, §6.5, ADR-0020 app-start trigger). Idempotent
        // and best-effort — it fires one immediate run and then polls; a null scheduler (the headless
        // sync-test graph) simply does not poll. It never throws into boot.
        container.catalogSyncScheduler?.start()
        return ReadyCompanion(container, binding, server)
    }

    /**
     * Resolve the bind address once at start-up, honouring the "take effect on next start" contract.
     * First run has no stored selection → materialise the Tailscale default (or all-interfaces) and
     * persist it, so it is a default exactly once and a setting thereafter. An auto-heal (a moved
     * tailnet address) is persisted too, so the correction does not repeat every launch (ADR-0023).
     */
    fun resolveBinding(container: CompanionContainer): ResolvedBinding {
        val catalog = container.addressCatalog
        val selection = container.settings.storedBindSelection
            ?: catalog.firstSetupSelection().also { container.settings.bindSelection = it }
        return catalog.resolve(selection).also { resolved ->
            resolved.healedSelection?.let { container.settings.bindSelection = it }
        }
    }
}
