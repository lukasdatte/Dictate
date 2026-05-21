package net.devemperor.dictate.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [CodecParams] — primarily lock the
 * [CodecParams.DEFAULT_AAC_M4A] values against the constants the
 * [net.devemperor.dictate.core.RecordingHardwareAdapter] used to
 * hard-code (44.1 kHz, mono, 64 kbps AAC-LC). A regression here
 * would change first-segment recording quality silently.
 */
class CodecParamsTest {

    @Test
    fun `DEFAULT_AAC_M4A matches the historic adapter constants`() {
        val d = CodecParams.DEFAULT_AAC_M4A
        assertEquals(44_100, d.sampleRate)
        assertEquals(1, d.channelCount)
        assertEquals(64_000, d.bitRate)
        assertEquals("audio/mp4a-latm", d.mimeType)
    }

    @Test
    fun `data-class equality and copy work as expected`() {
        val a = CodecParams(44_100, 1, 64_000, "audio/mp4a-latm")
        val b = CodecParams(44_100, 1, 64_000, "audio/mp4a-latm")
        assertEquals(a, b)
        val c = a.copy(sampleRate = 48_000)
        assertEquals(48_000, c.sampleRate)
        assertEquals(a.bitRate, c.bitRate)
    }
}
