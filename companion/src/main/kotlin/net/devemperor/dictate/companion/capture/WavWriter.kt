package net.devemperor.dictate.companion.capture

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Streams raw 16 kHz/mono/16-bit PCM into a canonical 44-byte-header WAV file (desktop-host.md §4.1).
 *
 * The RIFF/`WAVE` container carries two size fields — the overall `RIFF` chunk size and the `data`
 * sub-chunk size — that are only known once recording stops. So the writer emits a placeholder header
 * up front, appends PCM as it arrives, and back-patches the two little-endian sizes on [close] via a
 * seekable [RandomAccessFile]. That is the standard "write header last" RIFF pattern; it keeps the
 * file a valid, playable WAV the moment [close] returns without buffering the whole take in memory.
 *
 * Not thread-safe: one capture thread owns one writer (the rolling-segment loop, §4.3).
 */
class WavWriter(private val file: File) : Closeable {

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes: Long = 0
    private var closed = false

    init {
        raf.setLength(0)
        raf.write(placeholderHeader())
    }

    /** Appends [len] bytes of PCM from [buffer]. */
    fun write(buffer: ByteArray, len: Int) {
        check(!closed) { "WavWriter already closed for ${file.name}" }
        raf.write(buffer, 0, len)
        dataBytes += len
    }

    /** Bytes of PCM written so far — the rolling-segment loop rolls when this crosses its budget. */
    fun bytesWritten(): Long = dataBytes

    /** Back-patches the RIFF/`data` sizes and closes the file. Idempotent. */
    override fun close() {
        if (closed) return
        closed = true
        raf.seek(RIFF_SIZE_OFFSET)
        writeLeInt(HEADER_BYTES - CHUNK_ID_PLUS_SIZE + dataBytes)
        raf.seek(DATA_SIZE_OFFSET)
        writeLeInt(dataBytes)
        raf.close()
    }

    private fun writeLeInt(value: Long) {
        val v = value.toInt()
        raf.write(byteArrayOf(
            (v and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 24) and 0xFF).toByte(),
        ))
    }

    private fun placeholderHeader(): ByteArray = WavHeader.pcmHeader(dataBytes = 0)

    companion object {
        const val HEADER_BYTES = WavHeader.HEADER_BYTES

        /** Offset of the RIFF chunk size field (bytes 4..7). */
        private const val RIFF_SIZE_OFFSET = 4L

        /** Offset of the `data` sub-chunk size field (bytes 40..43). */
        private const val DATA_SIZE_OFFSET = 40L

        /** `RIFF` id (4) + its size field (4) are excluded from the RIFF chunk-size count. */
        private const val CHUNK_ID_PLUS_SIZE = 8L
    }
}
