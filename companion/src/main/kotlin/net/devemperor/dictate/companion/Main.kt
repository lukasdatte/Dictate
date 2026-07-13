package net.devemperor.dictate.companion

import net.devemperor.dictate.companion.server.CompanionServer

/**
 * Entry point of the desktop companion.
 *
 * Headless for now — it boots the HTTP server and waits. The Compose window, the tray icon and the
 * pairing dialog land with `wd-8`; the SQLDelight history with `wd-5`, which is why the container
 * still wires the in-memory repositories here.
 */
fun main() {
    val container = CompanionContainer.production()
    val server = CompanionServer(container)

    Runtime.getRuntime().addShutdownHook(Thread(server::stop))
    server.start()

    println("Dictate Companion — listening on ${CompanionServer.DEFAULT_HOST}:${server.boundPort()}")
    if (!container.inserter.available) {
        // The banner the UI will show (wd-8). On a non-Windows box the text still arrives and is
        // still stored — it just cannot be typed into the foreground window (ADR-0018).
        println("Text insertion is not available on this platform — received texts are stored, not typed.")
    }

    // Nothing else holds the main thread yet; the Compose `application { }` block will (wd-8).
    Thread.currentThread().join()
}
