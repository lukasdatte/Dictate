package net.devemperor.dictate.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import net.devemperor.dictate.companion.domain.CompanionSettings
import net.devemperor.dictate.companion.hotkey.HotkeyCombo
import net.devemperor.dictate.companion.platform.AppPaths
import net.devemperor.dictate.companion.platform.SingleInstanceGuard
import net.devemperor.dictate.companion.ui.App
import net.devemperor.dictate.companion.ui.CompanionIcon
import net.devemperor.dictate.companion.ui.panel.PanelViewModel
import net.devemperor.dictate.companion.ui.panel.PanelWindow
import net.devemperor.dictate.companion.ui.theme.CompanionTheme
import java.awt.EventQueue
import java.util.concurrent.CompletableFuture
import kotlin.system.exitProcess

/**
 * The desktop companion: a tray application with an embedded server (ADR-0017).
 *
 * **Single instance.** The companion is the only server in the system and binds a fixed port, so a
 * second copy can only fight over it. [main] takes a [SingleInstanceGuard] *before* anything else; a
 * second launch asks the running one to surface its window and exits without ever starting Compose.
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
 * painting. A failure or a timeout in that boot lands in the window as an error state, never an
 * endless spinner (a lesson from the double-launch hang). See [CompanionBootstrap] for why.
 */
fun main(args: Array<String>) {
    when (val acquisition = SingleInstanceGuard.acquire(AppPaths.dataDirectory())) {
        is SingleInstanceGuard.Acquisition.AlreadyRunning -> {
            // Another companion already owns the data dir and port 8756. A manual launch means the
            // user wants to see it — ask it to surface. An autostart re-fire (--minimized) and a
            // service re-fire (--headless) must not pop a window uninvited. Either way we do NOT
            // start a second app.
            if (!args.contains(FLAG_MINIMIZED) && !args.contains(FLAG_HEADLESS)) acquisition.requestShow()
            exitProcess(0)
        }

        is SingleInstanceGuard.Acquisition.Primary ->
            if (args.contains(FLAG_HEADLESS)) runHeadless(acquisition.guard) else runCompanion(args, acquisition.guard)
    }
}

/**
 * `--headless` (peer-katalog.md §9.3, F8): the full companion — server, persistence, catalog offer,
 * sync — without ever touching Compose/Skiko or AWT. The hub-peer / autostart-service mode: a
 * headless companion serves its catalog and receives dictations; it just has no window and no tray.
 *
 * It is deliberately the SAME boot path as the windowed app ([CompanionBootstrap.start] — already
 * Compose-free, that is its whole point): headless only skips `application {}`. Failures print to
 * stderr and exit non-zero — there is no window to show them in, and a service manager needs the
 * exit code, not a spinner.
 *
 * The desktop-dictation hotkey path stays dormant: registering a global hotkey without any UI to
 * confirm/review would insert text with no visible feedback anywhere, so headless is a *receiver*,
 * not a dictation host.
 */
private fun runHeadless(guard: SingleInstanceGuard) {
    Runtime.getRuntime().addShutdownHook(Thread { guard.release() })
    val ready = try {
        CompanionBootstrap.start()
    } catch (e: Throwable) {
        System.err.println("Dictate Companion could not start: ${describeBootFailure(e)}")
        exitProcess(1)
    }
    Runtime.getRuntime().addShutdownHook(Thread { ready.server.stop() })
    println(
        "Dictate Companion running headless on port ${ready.server.boundPort()}" +
            (ready.binding.advertised?.let { " ($it)" } ?: ""),
    )
    // Ktor's CIO engine runs on daemon threads; park the main thread until SIGTERM/SIGINT ends the
    // process (the shutdown hooks above then stop the server and free the single-instance lock).
    Thread.currentThread().join()
}

private fun runCompanion(args: Array<String>, guard: SingleInstanceGuard) {
    // A crash or a kill still frees the OS lock, but release the socket + port file tidily on a normal
    // exit too, so a stale port file never outlives the process.
    Runtime.getRuntime().addShutdownHook(Thread { guard.release() })

    // Start the blocking boot immediately, on a background thread, decoupled from the Compose render
    // loop: the receiver must come up even if window rendering is slow, and a phone pairing depends on
    // the socket being open, not on a frame being drawn.
    // Any failure is wrapped in a plain [CompanionBootFailure] so the UI side handles it as an ordinary
    // exception. A port-conflict start surfaces as a coroutine JobCancellationException (a
    // CancellationException) out of Ktor; unwrapped, that would be an awkward thing to catch inside a
    // coroutine — wrapping it here keeps the failure a normal value to inspect.
    val boot: CompletableFuture<ReadyCompanion> = CompletableFuture.supplyAsync {
        try {
            CompanionBootstrap.start()
        } catch (e: Throwable) {
            throw CompanionBootFailure(e)
        }
    }

    application {
        // Initial visibility is decided from the launch flag alone, on purpose: reading the (DB-backed)
        // startMinimized setting here would need the boot result and thus block the first frame on it.
        // --minimized starts hidden; a manual launch shows the window straight away on its loading
        // state. The rare "startMinimized set *and* launched by hand" case is corrected once boot
        // completes (below), at the cost of a brief flash.
        var windowVisible by remember { mutableStateOf(!args.contains(FLAG_MINIMIZED)) }
        var receiving by remember { mutableStateOf(true) }
        var bootState by remember { mutableStateOf<BootState>(BootState.Loading) }

        LaunchedEffect(Unit) {
            bootState = try {
                // A boot must *finish* — success or failure — so a wedged start (e.g. a port already
                // held, a locked database) becomes a visible error instead of a spinner that never ends.
                val booted = withTimeoutOrNull(BOOT_TIMEOUT_MILLIS) { boot.await() }
                if (booted == null) {
                    BootState.Failed("Start-up did not finish within ${BOOT_TIMEOUT_MILLIS / 1000} s. Is another copy already running?")
                } else {
                    if (booted.container.settings.startMinimized) windowVisible = false
                    BootState.Ready(booted)
                }
            } catch (e: Throwable) {
                BootState.Failed(describeBootFailure(e))
            }
        }

        // A second launch asks us to surface. The signal arrives on the guard's listener thread; hop
        // to the AWT event thread, where flipping Compose window state is safe and actually recomposes.
        DisposableEffect(Unit) {
            guard.onShowRequested { EventQueue.invokeLater { windowVisible = true } }
            onDispose { guard.onShowRequested { } }
        }

        // ── Desktop dictation (Block D2): global hotkey + warm panel ─────────────────────────
        // Registered once boot is Ready: the hotkey (or the tray item, the Linux path) funnels into
        // container.startDictation() — remember-foreground first (§6.3), then the pipeline. The
        // combo comes from `hotkey.combo`, self-healing to the default on garbage (§6.1).
        DisposableEffect(bootState) {
            val ready = (bootState as? BootState.Ready)?.companion?.container
            if (ready != null && ready.desktopDictation != null) {
                val combo = HotkeyCombo.parse(ready.settings.hotkeyCombo) ?: HotkeyCombo.DEFAULT
                ready.globalHotkey.register(combo) { ready.startDictation() }
            }
            onDispose { ready?.globalHotkey?.unregister() }
        }

        (bootState as? BootState.Ready)?.companion?.container?.let { container ->
            val controller = container.desktopDictation
            val panel = container.dictationPanel
            val capture = container.desktopCapture
            if (controller != null && panel != null && capture != null) {
                // The panel's brain lives as long as the application scope — the window itself stays
                // warm (visible=false) from here on, which is what keeps the toggle <100 ms (F5).
                val panelScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
                val viewModel = remember {
                    PanelViewModel(controller.state, capture.amplitudes, panelScope, container.clock).also { it.start() }
                }
                DisposableEffect(Unit) { onDispose { panelScope.cancel() } }
                PanelWindow(
                    controller = controller,
                    viewModel = viewModel,
                    control = panel,
                    applyFocusFreeStyle = container.panelStyler,
                )
            }
        }

        Tray(
            icon = CompanionIcon,
            tooltip = "Dictate Companion",
            onAction = { windowVisible = true },
            menu = {
                Item("Open") { windowVisible = true }
                // The Linux/macOS dictation trigger (no global hotkey there, spec §6.1/F6) — and a
                // mouse fallback on Windows.
                (bootState as? BootState.Ready)?.companion?.container?.let { container ->
                    if (container.desktopDictation != null) {
                        Item("Start dictation") { container.startDictation() }
                    }
                }
                // "Pause receiving" needs a server to stop; it only appears once boot has produced one.
                (bootState as? BootState.Ready)?.let { ready ->
                    CheckboxItem("Pause receiving", checked = !receiving) { paused ->
                        // Stopping the socket is the honest way to pause: a phone then gets a connection
                        // error and falls back to its pending part, not a 200 for a text nobody took.
                        if (paused) ready.companion.server.stop() else ready.companion.server.start()
                        receiving = !paused
                    }
                }
                Separator()
                Item("Quit") {
                    (bootState as? BootState.Ready)?.companion?.server?.stop()
                    guard.release()
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
            when (val state = bootState) {
                is BootState.Loading -> StartingUp()
                is BootState.Failed -> BootFailed(state.message)
                is BootState.Ready ->
                    if (!receiving) {
                        MaterialTheme { Text("Receiving is paused. Resume it from the tray menu.") }
                    } else {
                        // The QR carries the address DERIVED from the binding (ADR-0023): whatever the
                        // server actually listens on, highest-priority first. Only when there is no
                        // reachable candidate at all does it fall back to the human-facing server name.
                        App(state.companion.container) { "http://${state.companion.binding.advertised ?: state.companion.container.serverName}:${state.companion.server.boundPort()}" }
                    }
            }
        }
    }
}

/** Where start-up is between "kicked off" and "the window has something real to show". */
private sealed interface BootState {
    data object Loading : BootState
    data class Ready(val companion: ReadyCompanion) : BootState
    data class Failed(val message: String) : BootState
}

/** Wraps whatever [CompanionBootstrap.start] failed with, so the UI side handles a plain exception. */
class CompanionBootFailure(cause: Throwable) : Exception(cause)

/**
 * Turn a boot failure into a line a user can act on. The overwhelmingly common cause is the port
 * already being held — which arrives as a `BindException` deep in the chain, or (when Ktor cancels its
 * start job over it) as a `CancellationException`. Anything else falls back to the underlying message.
 */
internal fun describeBootFailure(error: Throwable): String {
    val chain = generateSequence<Throwable>(error) { it.cause }.toList()
    val portConflict = chain.any { it is java.net.BindException || it is java.util.concurrent.CancellationException }
    if (portConflict) {
        return "Could not open network port ${CompanionSettings.DEFAULT_PORT}. Another program may already be " +
            "using it — quit any other copy of Dictate Companion and try again."
    }
    return chain.firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) }
        ?: (chain.lastOrNull() ?: error).javaClass.simpleName
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

/**
 * The state the endless spinner used to hide. If the boot cannot finish — a port already held, a
 * database that will not open — the user is told so, instead of watching a spinner forever.
 */
@Composable
private fun BootFailed(message: String) {
    CompanionTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text("Dictate Companion could not start", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 520.dp),
                )
                Text(
                    text = "Quit any other running copy from its tray icon, then start Dictate Companion again.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 520.dp),
                )
            }
        }
    }
}

private const val FLAG_MINIMIZED = "--minimized"

/** Server without a window (§9.3) — the autostart/hub-peer mode. See [runHeadless]. */
private const val FLAG_HEADLESS = "--headless"

/** A boot that has not finished by here is wedged, not slow; surface it as an error. */
private const val BOOT_TIMEOUT_MILLIS = 20_000L
