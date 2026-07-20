package net.devemperor.dictate.ai.runner

import net.devemperor.dictate.ai.testutil.FakeAudioDurationReader
import net.devemperor.dictate.ai.testutil.FakeProxyConfig
import okhttp3.MultipartBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Wire-format regression tests for [ElevenLabsTranscriptionRunner].
 *
 * Guards the ElevenLabs Scribe request body against the provider contract:
 * `keyterms` is a `List[str]` and MUST be serialized as repeated form-data
 * parts, mirroring the official ElevenLabs SDK. A prior version concatenated
 * the list into a single JSON-array string field, which the API treated as
 * one keyterm exceeding the per-term 50-char limit and rejected with HTTP 422
 * — the "API did not accept the request" bug reported for Scribe models.
 */
class ElevenLabsTranscriptionRunnerTest {

    private fun runner() =
        ElevenLabsTranscriptionRunner(
            apiKey = "test-key",
            proxy = FakeProxyConfig(),
            audioDuration = FakeAudioDurationReader()
        )

    private fun tempAudio(): File =
        File.createTempFile("dictate-eleven", ".m4a").apply {
            writeBytes(byteArrayOf(0, 1, 2))
            deleteOnExit()
        }

    private fun MultipartBody.readUtf8(): String {
        val buffer = Buffer()
        writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun keytermsPartCount(wire: String): Int =
        Regex("""name="keyterms"""").findAll(wire).count()

    @Test
    fun `keyterms are sent as one form field per term, not a JSON array string`() {
        val options = TranscriptionOptions(
            audioFile = tempAudio(),
            model = "scribe_v2",
            keyterms = listOf("Alpha", "Beta", "Gamma")
        )

        val wire = runner().buildMultipartBody(options).readUtf8()

        assertEquals("one keyterms part per term", 3, keytermsPartCount(wire))
        assertTrue(wire.contains("Alpha"))
        assertTrue(wire.contains("Beta"))
        assertTrue(wire.contains("Gamma"))
        assertFalse(
            "keyterms must not be encoded as a JSON array string",
            wire.contains("""["Alpha","Beta","Gamma"]""")
        )
    }

    @Test
    fun `keyterms are omitted for scribe_v1`() {
        val options = TranscriptionOptions(
            audioFile = tempAudio(),
            model = "scribe_v1",
            keyterms = listOf("Alpha", "Beta")
        )

        val wire = runner().buildMultipartBody(options).readUtf8()

        assertEquals("scribe_v1 does not support keyterms", 0, keytermsPartCount(wire))
    }

    @Test
    fun `model_id is always present`() {
        val wire = runner().buildMultipartBody(
            TranscriptionOptions(audioFile = tempAudio(), model = "scribe_v2")
        ).readUtf8()

        assertTrue(wire.contains("name=\"model_id\""))
        assertTrue(wire.contains("scribe_v2"))
    }

    @Test
    fun `no proxy configured leaves the client unproxied and skips the authenticator`() {
        val proxyConfig = FakeProxyConfig(proxy = null)
        val runner = ElevenLabsTranscriptionRunner(
            apiKey = "test-key",
            proxy = proxyConfig,
            audioDuration = FakeAudioDurationReader()
        )

        val client = runner.buildClient()

        assertNull("no proxy must be applied to the okhttp client", client.proxy)
        assertEquals(
            "installAuthenticator must not be called when no proxy is configured",
            0,
            proxyConfig.installAuthenticatorCalls
        )
    }

    @Test
    fun `resolved proxy is applied to the client and installs the authenticator`() {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("proxy.example", 8080))
        val proxyConfig = FakeProxyConfig(proxy = proxy)
        val runner = ElevenLabsTranscriptionRunner(
            apiKey = "test-key",
            proxy = proxyConfig,
            audioDuration = FakeAudioDurationReader()
        )

        val client = runner.buildClient()

        assertSame("resolved proxy must be applied to the okhttp client", proxy, client.proxy)
        assertEquals(
            "installAuthenticator must be called exactly once when a proxy is configured",
            1,
            proxyConfig.installAuthenticatorCalls
        )
    }
}
