package net.devemperor.dictate.companion.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The pure WAV byte machinery (desktop-host.md §4.1/§4.3): header, writer, merge, duration, peak. */
class WavCodecTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun wavWriter_writesAValidRiffHeaderWithTheCorrectDataSize() {
        val file = temp.newFile("take.wav")
        val pcm = ByteArray(3200) { (it % 251).toByte() }
        WavWriter(file).use { it.write(pcm, pcm.size) }

        val bytes = file.readBytes()
        assertEquals("file = 44-byte header + data", WavHeader.HEADER_BYTES + pcm.size, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(pcm.size, leInt(bytes, 40))                 // data chunk size
        assertEquals(WavHeader.HEADER_BYTES - 8 + pcm.size, leInt(bytes, 4)) // RIFF chunk size
        assertEquals(CaptureFormat.SAMPLE_RATE_HZ, leInt(bytes, 24))
        assertArrayEquals(pcm, bytes.copyOfRange(44, bytes.size))
    }

    @Test
    fun wavWriter_multipleWritesAccumulate() {
        val file = temp.newFile("multi.wav")
        WavWriter(file).use {
            it.write(ByteArray(100), 100)
            it.write(ByteArray(60), 40) // honours len, not array size
            assertEquals(140, it.bytesWritten())
        }
        assertEquals(WavHeader.HEADER_BYTES + 140, file.length().toInt())
    }

    @Test
    fun dataChunk_locatesPcmEvenBehindAnExtraChunk() {
        val pcm = ByteArray(8) { it.toByte() }
        val file = temp.newFile("extra.wav")
        file.writeBytes(wavWithLeadingListChunk(pcm))

        val chunk = WavHeader.dataChunk(file)
        assertEquals(pcm.size.toLong(), chunk.length)
        assertArrayEquals(pcm, file.readBytes().copyOfRange(chunk.offset.toInt(), (chunk.offset + chunk.length).toInt()))
    }

    @Test
    fun merge_singleSegment_isZeroCopy() {
        val only = temp.newFile("only.wav")
        WavWriter(only).use { it.write(ByteArray(64), 64) }

        val merged = WavConcat.merge(listOf(only), temp.newFile("out.wav"))
        assertEquals("the lone segment is returned untouched", only, merged)
    }

    @Test
    fun merge_concatenatesDataAndSumsTheHeader() {
        val a = temp.newFile("a.wav")
        val b = temp.newFile("b.wav")
        WavWriter(a).use { it.write(ByteArray(100) { 1 }, 100) }
        WavWriter(b).use { it.write(ByteArray(60) { 2 }, 60) }

        val out = temp.newFile("merged.wav")
        WavConcat.merge(listOf(a, b), out)

        val chunk = WavHeader.dataChunk(out)
        assertEquals("merged data = 100 + 60", 160L, chunk.length)
        assertEquals("valid RIFF: 44 header + 160 data", WavHeader.HEADER_BYTES + 160, out.length().toInt())
        val data = out.readBytes().copyOfRange(chunk.offset.toInt(), (chunk.offset + chunk.length).toInt())
        assertTrue("first segment's bytes lead", data.take(100).all { it == 1.toByte() })
        assertTrue("second segment's bytes follow", data.drop(100).all { it == 2.toByte() })
    }

    @Test
    fun durationReader_readsWholeSecondsFromHeader() {
        val oneSecond = temp.newFile("1s.wav")
        WavWriter(oneSecond).use { it.write(ByteArray(CaptureFormat.BYTES_PER_SECOND), CaptureFormat.BYTES_PER_SECOND) }
        assertEquals(1L, WavAudioDurationReader.durationSeconds(oneSecond))
    }

    @Test
    fun durationReader_returnsMinusOneOnNonWav() {
        val junk = temp.newFile("junk.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(-1L, WavAudioDurationReader.durationSeconds(junk))
    }

    @Test
    fun peak_takesTheLargestAbsoluteSampleWithinLen() {
        // three LE int16 samples: 0, 1000, -2000 → peak 2000. A stale trailing sample beyond len is ignored.
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0); putShort(1000); putShort(-2000); putShort(30000)
        }.array()
        assertEquals(2000, PcmAmplitude.peak(buf, 6))
        assertEquals(30000, PcmAmplitude.peak(buf, 8))
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    /** A WAV whose `data` chunk sits after an unrelated `LIST` chunk, to exercise chunk-walking. */
    private fun wavWithLeadingListChunk(pcm: ByteArray): ByteArray {
        val listBody = "INFO".toByteArray(Charsets.US_ASCII)
        val out = ByteBuffer.allocate(12 + 8 + listBody.size + 8 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray(Charsets.US_ASCII)); out.putInt(0); out.put("WAVE".toByteArray(Charsets.US_ASCII))
        out.put("LIST".toByteArray(Charsets.US_ASCII)); out.putInt(listBody.size); out.put(listBody)
        out.put("data".toByteArray(Charsets.US_ASCII)); out.putInt(pcm.size); out.put(pcm)
        return out.array()
    }
}
