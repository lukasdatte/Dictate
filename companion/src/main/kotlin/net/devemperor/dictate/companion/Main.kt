package net.devemperor.dictate.companion

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import net.devemperor.dictate.companion.server.CompanionServer
import net.devemperor.dictate.companion.ui.App
import net.devemperor.dictate.companion.ui.CompanionIcon

/**
 * The desktop companion: a tray application with an embedded server (ADR-0017).
 *
 * **Closing the window puts the app in the tray; it does not quit.** That is the only semantics that
 * fits an application with autostart — a receiver that stops receiving when its window is closed
 * would be a receiver the user cannot rely on. Quitting is done from the tray menu, deliberately.
 *
 * `--minimized` (the flag the autostart entry passes) starts straight into the tray.
 */
fun main(args: Array<String>) = application {
    val container = remember { CompanionContainer.production() }

    // Resolve the bind address once at start-up, alongside the "take effect on next start" contract.
    // First run has no stored selection → materialise the Tailscale default (or all-interfaces) and
    // persist it, so it is a default exactly once and a setting thereafter. An auto-heal (a moved
    // tailnet address) is persisted too, so the correction does not repeat every launch.
    val binding = remember {
        val catalog = container.addressCatalog
        val selection = container.settings.storedBindSelection
            ?: catalog.firstSetupSelection().also { container.settings.bindSelection = it }
        catalog.resolve(selection).also { resolved ->
            resolved.healedSelection?.let { container.settings.bindSelection = it }
        }
    }
    val server = remember { CompanionServer(container, binding.hosts, container.settings.port) }

    var windowVisible by remember { mutableStateOf(!args.contains(FLAG_MINIMIZED) && !container.settings.startMinimized) }
    var receiving by remember { mutableStateOf(true) }

    remember { server.start() }

    Tray(
        icon = CompanionIcon,
        tooltip = "Dictate Companion",
        onAction = { windowVisible = true },
        menu = {
            Item("Open") { windowVisible = true }
            CheckboxItem("Pause receiving", checked = !receiving) { paused ->
                // Stopping the socket is the honest way to pause: a phone then gets a connection
                // error and falls back to its pending part, rather than a 200 for a text nobody took.
                if (paused) server.stop() else server.start()
                receiving = !paused
            }
            Separator()
            Item("Quit") {
                server.stop()
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
        if (receiving) {
            // The QR carries the address DERIVED from the binding (ADR-0023): whatever the
            // server actually listens on, highest-priority first. Only when there is no reachable
            // candidate at all does it fall back to the human-facing server name.
            App(container) { "http://${binding.advertised ?: container.serverName}:${server.boundPort()}" }
        } else {
            MaterialTheme { Text("Receiving is paused. Resume it from the tray menu.") }
        }
    }
}

private const val FLAG_MINIMIZED = "--minimized"
