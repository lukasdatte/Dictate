package net.devemperor.dictate.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [SessionErrorFormatter.partialSegmentCount] — the F-053
 * `partial:N` humanization mapping (spec §3.3). Pure JVM.
 */
class SessionErrorFormatterTest {

    @Test
    fun `bare partial marker yields the segment count`() {
        assertEquals(3, SessionErrorFormatter.partialSegmentCount("partial:3"))
        assertEquals(0, SessionErrorFormatter.partialSegmentCount("partial:0"))
        assertEquals(12, SessionErrorFormatter.partialSegmentCount("partial:12"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(5, SessionErrorFormatter.partialSegmentCount("  partial:5  "))
    }

    @Test
    fun `null and empty are not partial markers`() {
        assertNull(SessionErrorFormatter.partialSegmentCount(null))
        assertNull(SessionErrorFormatter.partialSegmentCount(""))
    }

    @Test
    fun `a real error message is not a partial marker`() {
        assertNull(SessionErrorFormatter.partialSegmentCount("API request failed"))
        // A mixed message that merely contains the token is a real error and
        // must render verbatim, not as a partial-recovery note.
        assertNull(SessionErrorFormatter.partialSegmentCount("concat warning - partial:12 segments=3"))
        assertNull(SessionErrorFormatter.partialSegmentCount("partial:"))
        assertNull(SessionErrorFormatter.partialSegmentCount("partial:abc"))
    }
}
