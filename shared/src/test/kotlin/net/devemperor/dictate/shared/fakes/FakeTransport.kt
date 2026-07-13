package net.devemperor.dictate.shared.fakes

import net.devemperor.dictate.shared.transport.DispatchTransport
import net.devemperor.dictate.shared.transport.HttpResponseLite
import java.io.IOException

/** One recorded call — lets a test assert what actually went on the wire. */
data class RecordedCall(
    val method: String,
    val path: String,
    val body: String?,
    val headers: Map<String, String>,
)

/**
 * A programmable [DispatchTransport]. Hand-written, per house style — no mock library.
 *
 * It fakes only the socket: the request it records has been through the real `ProtocolCodec` and
 * the real validation, so a test using it still exercises the actual contract.
 */
class FakeTransport(
    /** Answers per path, in the order they were queued. */
    private val responses: MutableMap<String, MutableList<Answer>> = mutableMapOf(),
) : DispatchTransport {

    sealed class Answer {
        data class Respond(val status: Int, val body: String) : Answer()
        data class Fail(val exception: IOException) : Answer()
    }

    val calls = mutableListOf<RecordedCall>()

    fun onPath(path: String, vararg answers: Answer) = apply {
        responses.getOrPut(path) { mutableListOf() }.addAll(answers)
    }

    fun respond(path: String, status: Int, body: String) = onPath(path, Answer.Respond(status, body))

    fun fail(path: String, exception: IOException) = onPath(path, Answer.Fail(exception))

    override fun post(path: String, body: String, headers: Map<String, String>): HttpResponseLite {
        calls += RecordedCall("POST", path, body, headers)
        return answer(path)
    }

    override fun get(path: String, headers: Map<String, String>): HttpResponseLite {
        calls += RecordedCall("GET", path, null, headers)
        return answer(path)
    }

    private fun answer(path: String): HttpResponseLite {
        val queued = responses[path]
            ?: throw AssertionError("FakeTransport has no answer queued for $path")
        // The last queued answer repeats — a paging test queues one page each, an idempotency test
        // queues one answer and calls twice.
        val answer = if (queued.size > 1) queued.removeAt(0) else queued.first()

        return when (answer) {
            is Answer.Respond -> HttpResponseLite(answer.status, answer.body)
            is Answer.Fail -> throw answer.exception
        }
    }
}
