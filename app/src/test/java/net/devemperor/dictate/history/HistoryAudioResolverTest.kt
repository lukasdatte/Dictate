package net.devemperor.dictate.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [HistoryAudioResolver] (F-113 read side, ADR-0007).
 *
 * Pure JVM — the file-existence check is injected so resolution order is
 * exercised without touching the filesystem.
 */
class HistoryAudioResolverTest {

    private fun resolver(existing: Set<String>) =
        HistoryAudioResolver(fileExists = { it in existing })

    @Test
    fun `multi-segment column is preferred over legacy when its files exist`() {
        val r = resolver(setOf("/seg1", "/seg2", "/legacy"))
            .resolve(listOf("/seg1", "/seg2"), "/legacy")

        assertTrue(r.available)
        assertEquals(listOf("/seg1", "/seg2"), r.playablePaths)
        assertEquals("/seg1", r.primaryPath)
    }

    @Test
    fun `missing segment files are skipped`() {
        // seg2 does not exist on disk — only seg1 and seg3 are playable.
        val r = resolver(setOf("/seg1", "/seg3"))
            .resolve(listOf("/seg1", "/seg2", "/seg3"), null)

        assertEquals(listOf("/seg1", "/seg3"), r.playablePaths)
    }

    @Test
    fun `falls back to legacy column when no segment files exist`() {
        // Segment paths are listed but none exist (e.g. cache evicted); the
        // still-present legacy persistent copy carries the session.
        val r = resolver(setOf("/legacy"))
            .resolve(listOf("/seg1", "/seg2"), "/legacy")

        assertTrue(r.available)
        assertEquals(listOf("/legacy"), r.playablePaths)
        assertEquals("/legacy", r.primaryPath)
    }

    @Test
    fun `legacy fallback only applies when the legacy file exists`() {
        val r = resolver(existing = emptySet())
            .resolve(listOf("/seg1"), "/legacy")

        assertFalse(r.available)
        assertTrue(r.playablePaths.isEmpty())
        assertNull(r.primaryPath)
    }

    @Test
    fun `nothing resolvable yields unavailable`() {
        val r = resolver(existing = emptySet())
            .resolve(emptyList(), null)

        assertFalse(r.available)
        assertTrue(r.playablePaths.isEmpty())
        assertNull(r.primaryPath)
    }

    @Test
    fun `empty-string paths are ignored`() {
        // The multi-segment column round-trips empty as "" in some legacy rows.
        val r = resolver(setOf("/legacy"))
            .resolve(listOf("", ""), "/legacy")

        assertEquals(listOf("/legacy"), r.playablePaths)
    }
}
