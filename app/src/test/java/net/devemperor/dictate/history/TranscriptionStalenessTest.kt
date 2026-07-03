package net.devemperor.dictate.history

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the R6/D3 downstream-staleness predicate.
 *
 * The transcription card warns the user when the processing chain's first step
 * was based on a transcription text that differs from the now-current one — the
 * signal that a re-run / version switch made the downstream steps stale.
 */
class TranscriptionStalenessTest {

    @Test
    fun `no processing chain is never stale`() {
        assertFalse(TranscriptionStaleness.isStale("current text", null))
    }

    @Test
    fun `matching first-step input is not stale`() {
        assertFalse(TranscriptionStaleness.isStale("same text", "same text"))
    }

    @Test
    fun `differing first-step input is stale`() {
        assertTrue(TranscriptionStaleness.isStale("new transcription", "old transcription"))
    }

    @Test
    fun `null current transcription cannot establish a mismatch`() {
        assertFalse(TranscriptionStaleness.isStale(null, "old transcription"))
    }

    @Test
    fun `empty differing strings are still a mismatch`() {
        assertTrue(TranscriptionStaleness.isStale("", "old transcription"))
        assertFalse(TranscriptionStaleness.isStale("", ""))
    }
}
