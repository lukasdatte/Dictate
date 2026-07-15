package net.devemperor.dictate.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.future.await
import net.devemperor.dictate.companion.ui.App
import net.devemperor.dictate.companion.ui.CompanionIcon
import net.devemperor.dictate.companion.ui.theme.CompanionTheme
import java.util.concurrent.CompletableFuture

/**
 * The desktop companion: a tray application with an embedded server (ADR-0017).
 *
 * **Closing the window puts the app in the tray; it does not quit.** That is the only semantics that
 * fits an application with autostart — a receiver that stops receiving when its window is closed
 * would be a receiver the user cannot rely on. Quitting is done from the tray menu, deliberately.
 *
 * `--minimized` (the flag the autostart entry passes) starts straight into the tray.
 *
 * **Start-up runs off the composition — and off the render loop.** Opening the database, resolving the
 * bind address and starting the server are ~0.4–0.5 s of blocking work on a cold Windows start (SQLite
 * native extraction + Ktor class-loading, measured). It is kicked off on its own thread *before*
 * `application {}`, so the server comes up independently of when — or whether — Compose/Skiko finishes
 * painting, and its cost never sits on the path to the first frame. The window shows a loading state
 * and swaps to the real content when the boot future completes. See [CompanionBootstrap] for why.
 */
fun main(args: Array<String>) {
    // Start the blocking boot immediately, on a background thread, decoupled from the Compose render
    // loop: the receiver must come up even if window rendering is slow, and a phone pairing depends on
    // the socket being open, not on a frame being drawn.
    val boot: CompletableFuture<ReadyCompanion> = CompletableFuture.supplyAsync { CompanionBootstrap.start() }
    application {
        // Initial visibility is decided from the launch flag alone, on purpose: reading the (DB-backed)
        // startMinimized setting here would need the boot result and thus block the first frame on it.
        // --minimized starts hidden; a manual launch shows the window straight away on its loading
        // state. The rare "startMinimized set *and* launched by hand" case is corrected once boot
        // completes (below), at the cost of a brief flash.
        var windowVisible by remember { mutableStateOf(!args.contains(FLAG_MINIMIZED)) }
        var receiving by remember { mutableStateOf(true) }
        var ready by remember { mutableStateOf<ReadyCompanion?>(null) }

        LaunchedEffect(Unit) {
            val booted = boot.await()
            if (booted.container.settings.startMinimized) windowVisible = false
            ready = booted
        }

        Tray(
            icon = CompanionIcon,
            tooltip = "Dictate Companion",
            onAction = { windowVisible = true },
            menu = {
                Item("Open") { windowVisible = true }
                // "Pause receiving" needs a server to stop; it only appears once boot has produced one.
                ready?.let { booted ->
                    CheckboxItem("Pause receiving", checked = !receiving) { paused ->
                        // Stopping the socket is the honest way to pause: a phone then gets a connection
                        // error and falls back to its pending part, not a 200 for a text nobody took.
                        if (paused) booted.server.stop() else booted.server.start()
                        receiving = !paused
                    }
                }
                Separator()
                Item("Quit") {
                    ready?.server?.stop()
                    exitApplication()
                }
            },
        )

        Window(
            visible = windowVisible,
            onCloseRequest = { windowVisible = false },
            title = "Dictate Companion",
            icon = CompanionIcon,
            state = rememberWindowState(width = 980.dp, height = 680.dp),
        ) {
            val booted = ready
            when {
                booted == null -> StartingUp()
                !receiving -> MaterialTheme { Text("Receiving is paused. Resume it from the tray menu.") }
                else ->
                    // The QR carries the address DERIVED from the binding (ADR-0023): whatever the
                    // server actually listens on, highest-priority first. Only when there is no reachable
                    // candidate at all does it fall back to the human-facing server name.
                    App(booted.container) { "http://${booted.binding.advertised ?: booted.container.serverName}:${booted.server.boundPort()}" }
            }
        }
    }
}

/** The loading state shown while [CompanionBootstrap] opens the database and starts the server. */
@Composable
private fun StartingUp() {
    CompanionTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text("Starting Dictate Companion…", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private const val FLAG_MINIMIZED = "--minimized"
