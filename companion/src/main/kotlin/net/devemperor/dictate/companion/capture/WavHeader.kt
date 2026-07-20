package net.devemperor.dictate.companion.capture

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The canonical 44-byte PCM WAV header and the minimal RIFF reader the segment merger needs
 * (desktop-host.md §4.1/§4.3). Pure byte arithmetic — unit-testable, no javax.sound.
 *
 * Only the fixed [CaptureFormat] is ever produced here, so the header is a template with two variable
 * size fields rather than a general WAV encoder.
 */
object WavHeader {

    const val HEADER_BYTES = 44

    /** Builds a full 44-byte header for a `data` chunk of [dataBytes] PCM bytes. */
    fun pcmHeader(dataBytes: Long): ByteArray {
        val byteRate = CaptureFormat.BYTES_PER_SECOND
        val blockAlign = CaptureFormat.BYTES_PER_FRAME
        val riffSize = (HEADER_BYTES - 8 + dataBytes).toInt()

        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(riffSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)                                    // PCM fmt chunk size
            putShort(1)                                   // audioFormat = PCM
            putShort(CaptureFormat.CHANNELS.toShort())
            putInt(CaptureFormat.SAMPLE_RATE_HZ)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(CaptureFormat.BITS_PER_SAMPLE.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes.toInt())
        }.array()
    }

    /**
     * Locates the `data` sub-chunk of [file] and returns its byte offset (of the first PCM byte) and
     * declared length. Walks the chunk list rather than assuming offset 44, so a file carrying an
     * extra chunk (e.g. `LIST`/`fact`, which some encoders add) still merges correctly.
     *
     * @throws IllegalArgumentException if [file] is not a RIFF/`WAVE` file or has no `data` chunk.
     */
    fun dataChunk(file: File): DataChunk {
        val bytes = file.readBytes()
        require(bytes.size >= 12 &&
            bytes.readAscii(0, 4) == "RIFF" && bytes.readAscii(8, 4) == "WAVE") {
            "${file.name} is not a RIFF/WAVE file"
        }
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val chunkId = bytes.readAscii(pos, 4)
            val chunkSize = bytes.readLeInt(pos + 4)
            val body = pos + 8
            if (chunkId == "data") {
                // Clamp to the real file length: a truncated take (crash mid-write) may declare more
                // than it holds, and reading past the array must not throw during a merge.
                val available = (bytes.size - body).coerceAtLeast(0)
                return DataChunk(offset = body.toLong(), length = minOf(chunkSize.toLong(), available.toLong()))
            }
            // Chunks are word-aligned: an odd size carries a pad byte.
            pos = body + chunkSize + (chunkSize and 1)
        }
        throw IllegalArgumentException("${file.name} has no data chunk")
    }

    data class DataChunk(val offset: Long, val length: Long)

    private fun ByteArray.readAscii(offset: Int, len: Int): String =
        String(this, offset, len, Charsets.US_ASCII)

    private fun ByteArray.readLeInt(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
}
