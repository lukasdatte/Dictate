package net.devemperor.dictate.companion.server

import net.devemperor.dictate.companion.CompanionContainer
import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.data.SqlDelightDeviceRepository
import net.devemperor.dictate.companion.data.SqlDelightHistoryRepository
import net.devemperor.dictate.companion.domain.model.InputCommand
import net.devemperor.dictate.companion.domain.model.InputOutcome
import net.devemperor.dictate.companion.domain.model.KeyCommand
import net.devemperor.dictate.companion.fakes.FakeInputCommandPerformer
import net.devemperor.dictate.companion.fakes.FakeTextInserter
import net.devemperor.dictate.companion.fakes.MutableClock
import net.devemperor.dictate.shared.auth.Credentials
import net.devemperor.dictate.shared.client.DispatchClient
import net.devemperor.dictate.shared.client.DispatchError
import net.devemperor.dictate.shared.client.DispatchResult
import net.devemperor.dictate.shared.protocol.InputCommandKindWire
import net.devemperor.dictate.shared.protocol.InputCommandWire
import net.devemperor.dictate.shared.protocol.InputOutcomeWire
import net.devemperor.dictate.shared.transport.OkHttpDispatchTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `POST /v1/input` over a **real** CIO server and the **real** shared [DispatchClient] on 127.0.0.1
 * (Muster [CompanionE2ETest]).
 *
 * The only fake that *has* to be one is the input performer — this VM is Linux and `SendInput`
 * cannot run — and it doubles as the assertion surface: what the phone sent is what the boundary got.
 */
class InputCommandE2ETest {

    private val performer = FakeInputCommandPerformer()
    private val inserter = FakeTextInserter()
    private val clock = MutableClock()
    private val database = CompanionDatabase.inMemory()
    private val devices = SqlDelightDeviceRepository(database)
    private val history = SqlDelightHistoryRepository(database)

    private lateinit var container: CompanionContainer
    private lateinit var server: CompanionServer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        container = CompanionContainer.forTest(
            inserter = inserter,
            clock = clock,
            devices = devices,
            history = history,
            inputPerformer = performer,
        )
        server = CompanionServer(container, hosts = listOf("127.0.0.1"), port = 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.boundPort()}"
    }

    @After
    fun tearDown() = server.stop()

    @Test
    fun input_deliversTheCommandsInOrder_andReportsExecuted() {
        val credentials = pairedCredentials()

        val commands = listOf(
            InputCommandWire(kind = InputCommandKindWire.TYPE_TEXT, text = "hi"),
            InputCommandWire(kind = InputCommandKindWire.CURSOR_LEFT, count = 3),
            InputCommandWire(kind = InputCommandKindWire.CURSOR_WORD_SELECT_BACK, count = 1),
            InputCommandWire(kind = InputCommandKindWire.REDO),
        )
        val response = client(credentials).input(commands).success()

        assertTrue(response.executed)
        assertEquals(InputOutcomeWire.SENT, response.outcome)

        // Byte-for-byte and in order over the wire → domain boundary.
        assertEquals(
            listOf(
                InputCommand.Type("hi"),
                InputCommand.Key(KeyCommand.CURSOR_LEFT, 3),
                InputCommand.Key(KeyCommand.CURSOR_WORD_SELECT_BACK, 1),
                InputCommand.Key(KeyCommand.REDO, 1),
            ),
            performer.received.single(),
        )
    }

    @Test
    fun input_withoutAForegroundWindow_is200ButNotExecuted() {
        val credentials = pairedCredentials()
        performer.nextOutcome = InputOutcome.NO_FOREGROUND_WINDOW

        val response = client(credentials).input(listOf(InputCommandWire(kind = InputCommandKindWire.BACKSPACE))).success()

        // Not an HTTP error: the reason rides in the body, mirroring dispatch's `delivered`.
        assertFalse(response.executed)
        assertEquals(InputOutcomeWire.NO_FOREGROUND_WINDOW, response.outcome)
    }

    @Test
    fun input_recordsNoHistory_ephemeralByDesign() {
        val credentials = pairedCredentials()

        client(credentials).input(listOf(InputCommandWire(kind = InputCommandKindWire.TYPE_TEXT, text = "pill text"))).success()

        // D3: keyboard actions never touch the history — flooding it with keystrokes would drown it.
        assertEquals(0, history.count(null))
    }

    @Test
    fun input_withoutPairing_isUnauthorized() {
        val error = client(Credentials(DEVICE_ID, "a-secret-that-was-never-issued-000"))
            .input(listOf(InputCommandWire(kind = InputCommandKindWire.BACKSPACE)))
            .failure()

        assertEquals(DispatchError.Unauthorized, error)
        assertTrue(performer.received.isEmpty())
    }

    // ── Plumbing ──────────────────────────────────────────────────────────────────────────

    private fun client(credentials: Credentials? = null) =
        DispatchClient(OkHttpDispatchTransport(baseUrl), credentials = { credentials })

    private fun pairedCredentials(): Credentials {
        val token = container.pairingService.issue().token
        val response = client().pair(token, DEVICE_ID, DEVICE_NAME).success()
        return Credentials(deviceId = response.deviceId, deviceSecret = response.deviceSecret)
    }

    private fun <T> DispatchResult<T>.success(): T = when (this) {
        is DispatchResult.Success -> value
        is DispatchResult.Failure -> throw AssertionError("expected success, got $error")
    }

    private fun <T> DispatchResult<T>.failure(): DispatchError = when (this) {
        is DispatchResult.Success -> throw AssertionError("expected a failure, got $value")
        is DispatchResult.Failure -> error
    }

    private companion object {
        const val DEVICE_ID = "test-device-0001"
        const val DEVICE_NAME = "Pixel 8"
    }
}
