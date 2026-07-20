package net.devemperor.dictate.companion

import net.devemperor.dictate.ai.AIOrchestrator
import net.devemperor.dictate.ai.factory.RunnerFactory
import net.devemperor.dictate.companion.ai.CompanionProxyConfig
import net.devemperor.dictate.companion.ai.ProfileBackedAiConfig
import net.devemperor.dictate.companion.ai.SqlDelightUsageSink
import net.devemperor.dictate.companion.capture.AudioCaptureService
import net.devemperor.dictate.companion.capture.JavaSoundAudioCaptureService
import net.devemperor.dictate.companion.capture.WavAudioDurationReader
import net.devemperor.dictate.companion.catalog.CatalogSubscriber
import net.devemperor.dictate.companion.catalog.CatalogSyncRunner
import net.devemperor.dictate.companion.catalog.CatalogSyncScheduler
import net.devemperor.dictate.companion.catalog.CatalogClientPeerIndexSource
import net.devemperor.dictate.companion.catalog.EngineCatalogSyncRunner
import net.devemperor.dictate.companion.catalog.PeerCatalogClientFactory
import net.devemperor.dictate.companion.catalog.PeerStoreCatalogSyncTargets
import net.devemperor.dictate.companion.catalog.TakeoverCatalogSubscriber
import net.devemperor.dictate.companion.data.SqlDelightCatalogSubscriberStore
import net.devemperor.dictate.companion.catalog.discovery.NoopPeerDiscovery
import net.devemperor.dictate.companion.catalog.discovery.PeerDiscovery
import net.devemperor.dictate.companion.catalog.discovery.TailscalePeerDiscovery
import net.devemperor.dictate.companion.catalog.PeerIndexSource
import net.devemperor.dictate.companion.data.CompanionConfigRepository
import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.data.DesktopSessionRepository
import net.devemperor.dictate.companion.data.memory.InMemoryChordMapping
import net.devemperor.dictate.companion.data.memory.InMemorySettings
import net.devemperor.dictate.companion.data.SqlDelightCatalogAuditLog
import net.devemperor.dictate.companion.data.SqlDelightCatalogRepository
import net.devemperor.dictate.companion.data.SqlDelightChordMappingRepository
import net.devemperor.dictate.companion.data.SqlDelightDeviceRepository
import net.devemperor.dictate.companion.data.SqlDelightHistoryRepository
import net.devemperor.dictate.companion.data.SqlDelightPeerExplorerStore
import net.devemperor.dictate.companion.data.SqlDelightSettingsRepository
import net.devemperor.dictate.companion.domain.AuthService
import net.devemperor.dictate.companion.domain.CatalogService
import net.devemperor.dictate.companion.domain.CompanionSettings
import net.devemperor.dictate.companion.domain.DispatchService
import net.devemperor.dictate.companion.domain.FocusRestorationPolicy
import net.devemperor.dictate.companion.domain.FocusRestoringTextInserter
import net.devemperor.dictate.companion.domain.HealthService
import net.devemperor.dictate.companion.domain.InputCommandService
import net.devemperor.dictate.companion.domain.net.AddressCatalog
import net.devemperor.dictate.companion.domain.PairingService
import net.devemperor.dictate.companion.domain.port.AutostartManager
import net.devemperor.dictate.companion.domain.port.CatalogAuditLog
import net.devemperor.dictate.companion.domain.port.ChordMappingRepository
import net.devemperor.dictate.companion.domain.port.ClipboardPort
import net.devemperor.dictate.companion.domain.port.ClockPort
import net.devemperor.dictate.companion.domain.port.DeviceRepository
import net.devemperor.dictate.companion.domain.port.HistoryRepository
import net.devemperor.dictate.companion.domain.port.InputCommandPerformer
import net.devemperor.dictate.companion.domain.port.NetworkInterfaces
import net.devemperor.dictate.companion.domain.port.PeerExplorerStore
import net.devemperor.dictate.companion.domain.port.SettingsRepository
import net.devemperor.dictate.companion.domain.port.TextInserter
import net.devemperor.dictate.companion.domain.SyncService
import net.devemperor.dictate.companion.hotkey.GlobalHotkey
import net.devemperor.dictate.companion.pipeline.ConfigProfileSource
import net.devemperor.dictate.companion.pipeline.DesktopDictationController
import net.devemperor.dictate.companion.pipeline.DictationEffects
import net.devemperor.dictate.companion.pipeline.SerialJobQueue
import net.devemperor.dictate.companion.platform.AppPaths
import net.devemperor.dictate.companion.platform.fallback.NoopAutostart
import net.devemperor.dictate.companion.platform.fallback.NoopClipboard
import net.devemperor.dictate.companion.platform.fallback.NoopGlobalHotkey
import net.devemperor.dictate.companion.platform.fallback.NoopInputCommandPerformer
import net.devemperor.dictate.companion.platform.JvmNetworkInterfaces
import net.devemperor.dictate.companion.platform.PlatformModule
import net.devemperor.dictate.companion.platform.SystemClock
import net.devemperor.dictate.companion.secrets.SecretStoreModule
import net.devemperor.dictate.companion.ui.panel.ComposePanelWindowControl
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
    /** Local config-entity store behind the management screens (§9.2); null in the headless test graph. */
    val configRepository: CompanionConfigRepository? = null,
    /** The pipeline's capture line — exposed for the panel's amplitude feed (§4.4/§7.4). */
    val desktopCapture: AudioCaptureService? = null,
    /** Visibility + spike state of the warm panel window (§6.2); null in the headless test graph. */
    val dictationPanel: ComposePanelWindowControl? = null,
    /** Remember-foreground/restore-before-insert policy (§6.3); shares the panel's spike verdict. */
    val dictationFocus: FocusRestorationPolicy? = null,
    /** System-wide dictation hotkey (§6.1); Noop off-Windows. */
    val globalHotkey: GlobalHotkey = NoopGlobalHotkey,
    /** `WS_EX_NOACTIVATE` styler the panel window applies on creation (§6.3). */
    val panelStyler: (java.awt.Window) -> Boolean = { false },
    /**
     * The peer-catalog offer service (Block E1, peer-katalog.md §4). Wired in [production]; null in the
     * minimal sync-test graph, which has no config store. When present, [CompanionServer] mounts the
     * `/v1/catalog` family and `HealthService` reports `supportsCatalog = true` — the two stay honest
     * together because both key off this one field.
     */
    val catalogService: CatalogService? = null,
    /**
     * The consumer-side Peer Explorer store (Block E3, peer-katalog.md §8) over `peers`/
     * `subscriptions` + entity provenance; null in the minimal sync-test graph (no config store).
     */
    val peerExplorer: PeerExplorerStore? = null,
    /** Tailnet candidate enumeration for "Add peer" (§9.2); Noop off-Tailscale and in tests (AC11). */
    val peerDiscovery: PeerDiscovery = NoopPeerDiscovery,
    /** The offer view's read side of the credential-delivery audit (§8.2); null without a catalog. */
    val catalogAuditLog: CatalogAuditLog? = null,
    /**
     * The three live-catalog seams of the Explorer (§8.1): fetch a peer's index, run one sync, take
     * over an offered entry. All need a per-peer CatalogClient built from the `peers` row + the
     * SecretStore — the credential-touching adapter that ships with the delegated E2 persistence
     * work (issue E2-1); until it lands they stay null and the UI degrades honestly (stored-state
     * matrix only, sync-now/subscribe disabled).
     */
    val peerIndexSource: PeerIndexSource? = null,
    val catalogSyncRunner: CatalogSyncRunner? = null,
    val catalogSubscriber: CatalogSubscriber? = null,
    /**
     * The background catalog-sync poll (Block E2, §6.5). Wired in [production]; null in the test graph.
     * [CompanionBootstrap] calls [CatalogSyncScheduler.start] once the server is up — a null here is the
     * honest "this graph does not poll peers" signal (the minimal sync-test graph has no config store).
     */
    val catalogSyncScheduler: CatalogSyncScheduler? = null,
) {

    /**
     * The one dictation trigger — hotkey, tray item and panel button all end here, so the §6.3
     * remember-the-foreground-window step can never be forgotten by one of the entry points.
     */
    fun startDictation() {
        dictationFocus?.onDictationTrigger()
        desktopDictation?.startHotkey()
    }

    val settings = CompanionSettings(settingsRepository)
    val addressCatalog = AddressCatalog(networkInterfaces)

    val pairingService = PairingService(devices, clock, serverName, random)
    val authService = AuthService(devices)
    val dispatchService = DispatchService(inserter, history, devices, clock)
    val inputCommandService = InputCommandService(inputPerformer)
    val syncService = SyncService(history, clock)
    val healthService = HealthService(serverName, appVersion, inserter, inputPerformer, supportsCatalog = catalogService != null)

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
            // AiConfig is resolved from the active Block-C profile + its SecretStore credential
            // (ProfileBackedAiConfig, §9): provider/model/key/baseUrl/params all come from what the
            // user configured, so a real transcription/completion authenticates. Usage now persists
            // via SqlDelightUsageSink (the `usage` table, §5.4) instead of being discarded.
            val settings = CompanionSettings(settingsRepository)
            val desktopSessions = DesktopSessionRepository(database)
            val configRepository = CompanionConfigRepository(database, now = SystemClock::nowMillis)
            val secretStore = SecretStoreModule.detect(AppPaths.dataDirectory())
            val aiConfig = ProfileBackedAiConfig(
                config = configRepository,
                secretStore = secretStore,
                activeProfileId = { settings.activeProfileId },
            )
            val aiOrchestrator = AIOrchestrator(
                config = aiConfig,
                usageSink = SqlDelightUsageSink(database),
                factory = RunnerFactory(aiConfig, CompanionProxyConfig, WavAudioDurationReader),
            )
            // The warm panel + both §6.3 focus paths (D2): the spike-styled window control and the
            // restoration fallback share one focus verdict; the pipeline's inserter is wrapped so
            // every dictation insert restores the remembered editor window first. The phone-dispatch
            // path below keeps the bare platform.inserter — no panel is in play there.
            val dictationPanel = ComposePanelWindowControl()
            val dictationFocus = FocusRestorationPolicy(
                windows = platform.foregroundWindows,
                focusFree = dictationPanel::focusFree,
            )
            // ── Peer-catalog offer side (Block E1, peer-katalog.md §4) ────────────────────────
            // The catalog view over the SHARED config entities + the credential-delivery audit. The
            // secretStore is the SAME instance the AI config resolves keys from, so a delivered
            // credential and a locally used one address the identical SecretStore namespace (B1).
            val catalogAuditLog = SqlDelightCatalogAuditLog(database)
            val catalogService = CatalogService(
                entities = SqlDelightCatalogRepository(configRepository),
                secretStore = secretStore,
                auditLog = catalogAuditLog,
                clock = SystemClock,
            )

            // ── Peer-catalog subscriber side (Block E2, peer-katalog.md §6) ───────────────────
            // The consumer half: the shared sync engine over the real peers/subscriptions rows +
            // config mirror + SecretStore, driven on a timer by the scheduler and surfaced to the
            // Explorer through the three live seams (index / sync-now / subscribe). One CatalogClient
            // factory (peer row + SecretStore-backed pairing secret) feeds all of them.
            val peerExplorer = SqlDelightPeerExplorerStore(database, configRepository)
            val subscriberStore = SqlDelightCatalogSubscriberStore(database, configRepository, secretStore)
            val catalogSyncEngine = net.devemperor.dictate.shared.sync.CatalogSyncEngine(
                store = subscriberStore,
                notifier = platform.notificationPort,
                clock = SystemClock::nowMillis,
            )
            val peerClients = PeerCatalogClientFactory(secretStore)
            val catalogSyncScheduler = CatalogSyncScheduler(
                targets = PeerStoreCatalogSyncTargets(peerExplorer, peerClients),
                engine = catalogSyncEngine,
                intervalMillis = { settings.catalogSyncIntervalMillis },
            )
            val peerIndexSource = CatalogClientPeerIndexSource(peerExplorer, peerClients)
            val catalogSyncRunner = EngineCatalogSyncRunner(peerExplorer, peerClients, catalogSyncEngine)
            val catalogSubscriber = TakeoverCatalogSubscriber(
                peers = peerExplorer,
                clients = peerClients,
                config = configRepository,
                secretStore = secretStore,
                database = database,
                clock = SystemClock::nowMillis,
            )

            val desktopCapture = JavaSoundAudioCaptureService(settings)
            val desktopDictation = DesktopDictationController(
                DictationEffects(
                    capture = desktopCapture,
                    ai = aiOrchestrator,
                    sessions = desktopSessions,
                    inserter = FocusRestoringTextInserter(platform.inserter, dictationFocus),
                    queue = SerialJobQueue(),
                    clock = SystemClock,
                    // F20 (§8.1): the take's post-processing surface is resolved from the active profile
                    // — ambiguity mode, auto-apply instructions and style prompt from the profile; language
                    // and auto-format from device settings (research desktop-aiconfig-credential-resolution.md
                    // part b F6-F11, see ConfigProfileSource). The provider/model/key half is
                    // ProfileBackedAiConfig above.
                    profiles = ConfigProfileSource(
                        config = configRepository,
                        activeProfileId = { settings.activeProfileId },
                        language = settings::language,
                        autoFormatEnabled = settings::autoFormatEnabled,
                    ),
                    panel = dictationPanel,
                    confirmBeforeInsert = settings::confirmBeforeInsert,
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
                configRepository = configRepository,
                desktopCapture = desktopCapture,
                dictationPanel = dictationPanel,
                dictationFocus = dictationFocus,
                globalHotkey = platform.globalHotkey,
                panelStyler = platform.panelStyler,
                catalogService = catalogService,
                peerExplorer = peerExplorer,
                peerDiscovery = TailscalePeerDiscovery(),
                catalogAuditLog = catalogAuditLog,
                peerIndexSource = peerIndexSource,
                catalogSyncRunner = catalogSyncRunner,
                catalogSubscriber = catalogSubscriber,
                catalogSyncScheduler = catalogSyncScheduler,
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
            /**
             * The peer-catalog offer service (Block E1). Null by default — the sync-server tests do not
             * exercise the catalog, and a null keeps the `/v1/catalog` routes unmounted and
             * `supportsCatalog = false`. The catalog E2E passes a real one built on an in-memory DB.
             */
            catalogService: CatalogService? = null,
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
            catalogService = catalogService,
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
