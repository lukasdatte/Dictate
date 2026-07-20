package net.devemperor.dictate.companion

import net.devemperor.dictate.ai.AIOrchestrator
import net.devemperor.dictate.ai.factory.RunnerFactory
import net.devemperor.dictate.companion.ai.CompanionAiConfig
import net.devemperor.dictate.companion.ai.CompanionProxyConfig
import net.devemperor.dictate.companion.ai.NoopUsageSink
import net.devemperor.dictate.companion.capture.JavaSoundAudioCaptureService
import net.devemperor.dictate.companion.capture.WavAudioDurationReader
import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.data.DesktopSessionRepository
import net.devemperor.dictate.companion.data.memory.InMemoryChordMapping
import net.devemperor.dictate.companion.data.memory.InMemorySettings
import net.devemperor.dictate.companion.data.SqlDelightChordMappingRepository
import net.devemperor.dictate.companion.data.SqlDelightDeviceRepository
import net.devemperor.dictate.companion.data.SqlDelightHistoryRepository
import net.devemperor.dictate.companion.data.SqlDelightSettingsRepository
import net.devemperor.dictate.companion.pipeline.DesktopDictationController
import net.devemperor.dictate.companion.pipeline.DictationEffects
import net.devemperor.dictate.companion.pipeline.PanelControl
import net.devemperor.dictate.companion.pipeline.SerialJobQueue
import net.devemperor.dictate.companion.pipeline.TransitionalProfileSource
import net.devemperor.dictate.companion.domain.AuthService
import net.devemperor.dictate.companion.domain.CompanionSettings
import net.devemperor.dictate.companion.domain.DispatchService
import net.devemperor.dictate.companion.domain.HealthService
import net.devemperor.dictate.companion.domain.InputCommandService
import net.devemperor.dictate.companion.domain.PairingService
import net.devemperor.dictate.companion.domain.SyncService
import net.devemperor.dictate.companion.domain.net.AddressCatalog
import net.devemperor.dictate.companion.domain.port.AutostartManager
import net.devemperor.dictate.companion.domain.port.ChordMappingRepository
import net.devemperor.dictate.companion.domain.port.ClipboardPort
import net.devemperor.dictate.companion.domain.port.ClockPort
import net.devemperor.dictate.companion.domain.port.DeviceRepository
import net.devemperor.dictate.companion.domain.port.HistoryRepository
import net.devemperor.dictate.companion.domain.port.InputCommandPerformer
import net.devemperor.dictate.companion.domain.port.NetworkInterfaces
import net.devemperor.dictate.companion.domain.port.SettingsRepository
import net.devemperor.dictate.companion.domain.port.TextInserter
import net.devemperor.dictate.companion.platform.AppPaths
import net.devemperor.dictate.companion.platform.JvmNetworkInterfaces
import net.devemperor.dictate.companion.platform.PlatformModule
import net.devemperor.dictate.companion.platform.SystemClock
import net.devemperor.dictate.companion.platform.fallback.NoopAutostart
import net.devemperor.dictate.companion.platform.fallback.NoopClipboard
import net.devemperor.dictate.companion.platform.fallback.NoopInputCommandPerformer
import java.net.InetAddress
import java.security.SecureRandom

/**
 * Manual dependency injection — constructor wiring, no framework.
 *
 * A desktop app with this few collaborators does not need a container that scans the classpath; it
 * needs one file a newcomer can read top to bottom to see what talks to what. [forTest] is the same
 * graph with the ports swapped, which is why an E2E test can drive the *real* server and the *real*
 * services against a fake inserter and a controllable clock.
 */
class CompanionContainer(
    val devices: DeviceRepository,
    val history: HistoryRepository,
    settingsRepository: SettingsRepository,
    val inserter: TextInserter,
    val inputPerformer: InputCommandPerformer,
    val chordMapping: ChordMappingRepository,
    val clipboard: ClipboardPort,
    val autostart: AutostartManager,
    val clock: ClockPort,
    val serverName: String,
    val appVersion: String,
    networkInterfaces: NetworkInterfaces,
    random: SecureRandom = SecureRandom(),
    /**
     * The desktop-dictation entry point (Block D). Wired only in [production] — the sync-server test
     * graph ([forTest]) leaves it `null` because the pipeline's own tests construct it directly with
     * fakes (headless E2E, spec §12). Null-here is the honest signal that a graph without a real
     * capture line / AI orchestrator has no desktop host.
     */
    val desktopDictation: DesktopDictationController? = null,
    val desktopSessions: DesktopSessionRepository? = null,
) {

    val settings = CompanionSettings(settingsRepository)
    val addressCatalog = AddressCatalog(networkInterfaces)

    val pairingService = PairingService(devices, clock, serverName, random)
    val authService = AuthService(devices)
    val dispatchService = DispatchService(inserter, history, devices, clock)
    val inputCommandService = InputCommandService(inputPerformer)
    val syncService = SyncService(history, clock)
    val healthService = HealthService(serverName, appVersion, inserter, inputPerformer)

    companion object {

        const val APP_VERSION = "1.0.0"

        /**
         * The production graph, on the real SQLite file under [AppPaths], with the ports
         * [PlatformModule] picked for this operating system.
         *
         * On Linux/macOS that means a no-op inserter and no autostart — the app runs, serves and
         * stores its history, it just cannot type, and it says so (`canInsert = false`, ADR-0018).
         */
        fun production(
            platform: PlatformModule.Bindings = PlatformModule.detect(),
        ): CompanionContainer {
            val database = CompanionDatabase.open(AppPaths.databaseFile())
            val chordMapping = SqlDelightChordMappingRepository(database)
            val settingsRepository = SqlDelightSettingsRepository(database)

            // ── Desktop-dictation pipeline (Block D) ──────────────────────────────────────────
            // Runs against a TRANSITIONAL AiConfig (§5.1 NOTE): OpenAI-compatible defaults, no key
            // until D3 wires the SecretStore-backed profile. Usage accounting is a no-op sink until
            // D3's `usage` table (D5.b migration); see NoopUsageSink. The panel is headless (D2 adds
            // the real focus-free window).
            val settings = CompanionSettings(settingsRepository)
            val desktopSessions = DesktopSessionRepository(database)
            val aiConfig = CompanionAiConfig()
            val aiOrchestrator = AIOrchestrator(
                config = aiConfig,
                usageSink = NoopUsageSink,
                factory = RunnerFactory(aiConfig, CompanionProxyConfig, WavAudioDurationReader),
            )
            val desktopDictation = DesktopDictationController(
                DictationEffects(
                    capture = JavaSoundAudioCaptureService(settings),
                    ai = aiOrchestrator,
                    sessions = desktopSessions,
                    inserter = platform.inserter,
                    queue = SerialJobQueue(),
                    clock = SystemClock,
                    profiles = TransitionalProfileSource(),
                    panel = PanelControl.None,
                )
            )

            return CompanionContainer(
                devices = SqlDelightDeviceRepository(database),
                history = SqlDelightHistoryRepository(database),
                settingsRepository = settingsRepository,
                inserter = platform.inserter,
                inputPerformer = platform.inputPerformer(chordMapping),
                chordMapping = chordMapping,
                clipboard = platform.clipboard,
                autostart = platform.autostart,
                clock = SystemClock,
                serverName = defaultServerName(),
                appVersion = APP_VERSION,
                networkInterfaces = JvmNetworkInterfaces,
                desktopDictation = desktopDictation,
                desktopSessions = desktopSessions,
            )
        }

        /**
         * The test graph. The repositories the caller passes in are the **real** SQLDelight ones on
         * an in-memory SQLite (`CompanionDatabase.inMemory()`) — because the invariants worth testing
         * (idempotency, the never-downgrade-a-dispatch rule, the cursor order) live in the SQL, and a
         * hand-written fake repository would only ever test the fake.
         */
        fun forTest(
            inserter: TextInserter,
            clock: ClockPort,
            devices: DeviceRepository,
            history: HistoryRepository,
            settingsRepository: SettingsRepository = InMemorySettings(),
            chordMapping: ChordMappingRepository = InMemoryChordMapping(),
            inputPerformer: InputCommandPerformer = NoopInputCommandPerformer,
            clipboard: ClipboardPort = NoopClipboard,
            autostart: AutostartManager = NoopAutostart,
            serverName: String = "test-pc",
            networkInterfaces: NetworkInterfaces = NetworkInterfaces { emptyList() },
            random: SecureRandom = SecureRandom(),
        ): CompanionContainer = CompanionContainer(
            devices = devices,
            history = history,
            settingsRepository = settingsRepository,
            inserter = inserter,
            inputPerformer = inputPerformer,
            chordMapping = chordMapping,
            clipboard = clipboard,
            autostart = autostart,
            clock = clock,
            serverName = serverName,
            appVersion = APP_VERSION,
            networkInterfaces = networkInterfaces,
            random = random,
        )

        /** The name the phone shows as "paired with …". The hostname is what the user recognises. */
        private fun defaultServerName(): String = try {
            InetAddress.getLocalHost().hostName.ifBlank { FALLBACK_SERVER_NAME }
        } catch (e: Exception) {
            // An unresolvable hostname is common on a laptop with no DNS suffix — not worth a crash.
            FALLBACK_SERVER_NAME
        }

        private const val FALLBACK_SERVER_NAME = "Dictate Companion"
    }
}
