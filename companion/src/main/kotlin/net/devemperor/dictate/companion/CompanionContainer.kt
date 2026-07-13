package net.devemperor.dictate.companion

import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.data.SqlDelightDeviceRepository
import net.devemperor.dictate.companion.data.SqlDelightHistoryRepository
import net.devemperor.dictate.companion.domain.AuthService
import net.devemperor.dictate.companion.domain.DispatchService
import net.devemperor.dictate.companion.domain.HealthService
import net.devemperor.dictate.companion.domain.PairingService
import net.devemperor.dictate.companion.domain.port.AutostartManager
import net.devemperor.dictate.companion.domain.port.ClockPort
import net.devemperor.dictate.companion.domain.port.DeviceRepository
import net.devemperor.dictate.companion.domain.port.HistoryRepository
import net.devemperor.dictate.companion.domain.port.TextInserter
import net.devemperor.dictate.companion.platform.AppPaths
import net.devemperor.dictate.companion.platform.SystemClock
import net.devemperor.dictate.companion.platform.fallback.NoopAutostart
import net.devemperor.dictate.companion.platform.fallback.NoopTextInserter
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
    val inserter: TextInserter,
    val autostart: AutostartManager,
    val clock: ClockPort,
    val serverName: String,
    val appVersion: String,
    random: SecureRandom = SecureRandom(),
) {

    val pairingService = PairingService(devices, clock, serverName, random)
    val authService = AuthService(devices)
    val dispatchService = DispatchService(inserter, history, devices, clock)
    val healthService = HealthService(serverName, appVersion, inserter)

    companion object {

        const val APP_VERSION = "1.0.0"

        /**
         * The production graph, on the real SQLite file under [AppPaths].
         *
         * On Linux/macOS the inserter is [NoopTextInserter] and the autostart a no-op — the app
         * runs, serves and stores its history, it just cannot type (ADR-0018). The Windows
         * implementations arrive with `wd-7`/`wd-9` behind `PlatformModule.detect()`.
         */
        fun production(
            inserter: TextInserter = NoopTextInserter,
            autostart: AutostartManager = NoopAutostart,
        ): CompanionContainer {
            val database = CompanionDatabase.open(AppPaths.databaseFile())
            return CompanionContainer(
                devices = SqlDelightDeviceRepository(database),
                history = SqlDelightHistoryRepository(database),
                inserter = inserter,
                autostart = autostart,
                clock = SystemClock,
                serverName = defaultServerName(),
                appVersion = APP_VERSION,
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
            autostart: AutostartManager = NoopAutostart,
            serverName: String = "test-pc",
            random: SecureRandom = SecureRandom(),
        ): CompanionContainer = CompanionContainer(
            devices = devices,
            history = history,
            inserter = inserter,
            autostart = autostart,
            clock = clock,
            serverName = serverName,
            appVersion = APP_VERSION,
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
