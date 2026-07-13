package net.devemperor.dictate.shared.transport

import java.io.IOException

/**
 * The HTTP port — deliberately dumb: strings in, strings out.
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015).
 *
 * Everything typed happens one layer up, in `DispatchClient`, through `ProtocolCodec`. That is
 * what makes the fake trivial *and* keeps the fake honest: a test that swaps this port still runs
 * the real encoding and the real validation, so only the socket is fake, not the contract.
 *
 * **Blocking by design.** The Android caller runs on the existing single-threaded JobExecutor —
 * the same place `ElevenLabsTranscriptionRunner` does its blocking OkHttp call — and cancellation
 * works by interrupting that thread. No coroutines: `:shared` must stay compatible with `:app`'s
 * pinned kotlinx-coroutines 1.7.3 and jvmTarget 1.8.
 */
interface DispatchTransport {

    /** @throws IOException on any connectivity, timeout or truncation failure. */
    @Throws(IOException::class)
    fun post(path: String, body: String, headers: Map<String, String>): HttpResponseLite

    /** @throws IOException on any connectivity, timeout or truncation failure. */
    @Throws(IOException::class)
    fun get(path: String, headers: Map<String, String>): HttpResponseLite
}

/**
 * A response reduced to what the protocol actually needs.
 *
 * The body is already **fully read** when this exists. That is not an implementation detail: a
 * connection that dies halfway through the body must raise an [IOException] rather than hand up a
 * truncated string, or a half-received response could be mistaken for a delivery confirmation.
 */
data class HttpResponseLite(val status: Int, val body: String)
