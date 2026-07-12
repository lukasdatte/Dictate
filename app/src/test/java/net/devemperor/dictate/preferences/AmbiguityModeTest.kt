package net.devemperor.dictate.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbiguityModeTest {

    @Test
    fun `fromPersistKey round-trips every value`() {
        for (mode in AmbiguityMode.entries) {
            assertEquals(mode, AmbiguityMode.fromPersistKey(mode.persistKey))
        }
    }

    @Test
    fun `fromPersistKey defaults to ALWAYS_INSERT for null and unknown`() {
        assertEquals(AmbiguityMode.ALWAYS_INSERT, AmbiguityMode.fromPersistKey(null))
        assertEquals(AmbiguityMode.ALWAYS_INSERT, AmbiguityMode.fromPersistKey("nonsense"))
        assertEquals(AmbiguityMode.ALWAYS_INSERT, AmbiguityMode.fromPersistKey(""))
    }

    @Test
    fun `forcesTurn is false only for ALWAYS_INSERT`() {
        assertFalse(AmbiguityMode.ALWAYS_INSERT.forcesTurn)
        assertTrue(AmbiguityMode.AUTO.forcesTurn)
        assertTrue(AmbiguityMode.ALWAYS_REVIEW.forcesTurn)
    }
}
