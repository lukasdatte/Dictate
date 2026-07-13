package net.devemperor.dictate.companion.server

import net.devemperor.dictate.shared.auth.Credentials
import net.devemperor.dictate.shared.client.DispatchClient
import net.devemperor.dictate.shared.client.DispatchError
import net.devemperor.dictate.shared.client.DispatchResult
import net.devemperor.dictate.shared.protocol.DispatchRequest
import net.devemperor.dictate.shared.protocol.SessionOriginWire
import net.devemperor.dictate.shared.transport.OkHttpDispatchTransport
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The nastiest failure of all: a **200 whose body never finishes**.
 *
 * A `Content-Length` that promises more than the connection delivers is what a killed process, a
 * dropped VPN or a half-open NAT mapping actually looks like on the wire. The danger is not that it
 * errors — it is that a naive client parses the truncated prefix, finds `"delivered":true` in it and
 * acknowledges a delivery that never happened; the phone would then drop the text.
 *
 * A real Ktor server cannot be made to do this on demand, so the peer here is a bare
 * [ServerSocket]. What is under test is the *client's* rule (`:shared`), and the rule is absolute:
 * anything short of a fully-read body is [DispatchError.Unreachable] — never a delivery.
 */
class TruncatedResponseE2ETest {

    private lateinit var socket: ServerSocket
    private lateinit var acceptor: Thread

    @Before
    fun setUp() {
        socket = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        acceptor = thread(isDaemon = true, name = "truncating-peer") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    return@thread
                }
                client.use {
                    // Announce a body of 128 bytes, send a plausible prefix of one, then hang up.
                    val prefix = """{"protocolVersion":1,"sessionId":"session-1","delivered":true"""
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: 128\r\nConnection: close\r\n\r\n"
                    it.getOutputStream().apply {
                        write((head + prefix).toByteArray())
                        flush()
                    }
                }
            }
        }
    }

    @After
    fun tearDown() {
        socket.close()
    }

    @Test
    fun aTruncatedBodyIsNeverADelivery() {
        val client = DispatchClient(
            OkHttpDispatchTransport("http://127.0.0.1:${socket.localPort}"),
            credentials = { Credentials("test-device-0001", "a-secret-long-enough-to-validate") },
        )

        val result = client.dispatch(
            DispatchRequest(
                sessionId = "session-1",
                text = "hello",
                createdAt = 42L,
                origin = SessionOriginWire.KEYBOARD,
            ),
        )

        val error = (result as DispatchResult.Failure).error
        assertTrue("$error", error is DispatchError.Unreachable)
    }
}
