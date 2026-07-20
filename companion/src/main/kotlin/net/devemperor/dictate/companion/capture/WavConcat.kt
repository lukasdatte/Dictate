package net.devemperor.dictate.companion.capture

import java.io.File
import java.io.RandomAccessFile

/**
 * Merges the rolling WAV segments of one take into a single upload file (desktop-host.md §4.3).
 *
 * Because every segment is the identical [CaptureFormat], a merge is pure byte work: keep one header,
 * concatenate the raw `data` chunks, and write one header whose `data` size is their sum. No decode,
 * no re-encode, no javax.sound. A single-segment take is returned as-is (zero-copy) — the common case
 * costs nothing.
 */
object WavConcat {

    /**
     * Concatenates [segments] (in order) into [output] and returns it. With exactly one segment the
     * segment file itself is returned untouched. [segments] must be non-empty.
     */
    fun merge(segments: List<File>, output: File): File {
        require(segments.isNotEmpty()) { "cannot merge zero segments" }
        if (segments.size == 1) return segments.single()

        val chunks = segments.map { it to WavHeader.dataChunk(it) }
        val totalData = chunks.sumOf { it.second.length }

        RandomAccessFile(output, "rw").use { out ->
            out.setLength(0)
            out.write(WavHeader.pcmHeader(dataBytes = totalData))
            val buffer = ByteArray(64 * 1024)
            for ((file, chunk) in chunks) {
                RandomAccessFile(file, "r").use { input ->
                    input.seek(chunk.offset)
                    var remaining = chunk.length
                    while (remaining > 0) {
                        val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                        val read = input.read(buffer, 0, toRead)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
        }
        return output
    }
}
