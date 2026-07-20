package net.devemperor.dictate.companion

import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.data.SqlDelightDeviceRepository
import net.devemperor.dictate.companion.data.SqlDelightHistoryRepository
import net.devemperor.dictate.companion.domain.port.NetworkInterfaces
import net.devemperor.dictate.companion.fakes.FakeTextInserter
import net.devemperor.dictate.companion.fakes.MutableClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI

/**
 * AC12 (peer-katalog.md §2, §9.3): `CompanionBootstrap.start()` alone yields a serving
 * [ReadyCompanion] — server + persistence, no Compose window, no AWT. The `--headless` branch in
 * `Main.kt` is exactly this call plus a parked main thread, so proving the boot proves the mode.
 *
 * "No AWT/Skiko" is enforced structurally, not by an assertion: this JVM runs `java.awt.headless=
 * true` (test default) and the boot path below never touches a window class — if someone wires one
 * in, this test dies with a HeadlessException/ClassNotFound instead of quietly regressing.
 */
class HeadlessBootTest {

    private val database = CompanionDatabase.inMemory()
    private var ready: ReadyCompanion? = null

    @After
    fun tearDown() {
        ready?.server?.stop()
    }

    @Test
    fun bootstrapAlone_bringsUpAServingCompanion_withoutCompose() {
        val container = CompanionContainer.forTest(
            inserter = FakeTextInserter(),
            clock = MutableClock(),
            devices = SqlDelightDeviceRepository(database),
            history = SqlDelightHistoryRepository(database),
            networkInterfaces = NetworkInterfaces { emptyList() },
        )
        container.settings.port = 0 // ephemeral — the test must not fight over the real 8756

        val started = CompanionBootstrap.start { container }.also { ready = it }

        assertTrue("server must have bound a real port", started.server.boundPort() > 0)

        // The socket answers — the headless mode's whole contract: a receiver without a window.
        val url = URI("http://127.0.0.1:${started.server.boundPort()}/v1/health").toURL()
        val connection = url.openConnection() as HttpURLConnection
        try {
            // /v1/health is authenticated; 401 (not a connection error) proves serving + auth intact.
            assertEquals(401, connection.responseCode)
        } finally {
            connection.disconnect()
        }
    }
}
