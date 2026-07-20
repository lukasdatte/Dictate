package net.devemperor.dictate.config

import net.devemperor.dictate.database.entity.PromptEntity
import net.devemperor.dictate.database.entity.PromptType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Regression guard for the C3-3 write-seam fix ([PromptProvenance]): every Android `prompts` write
 * must keep the v12 shareable-identity columns correct (spec §7.3/§8.5). The historical 7-arg
 * [PromptEntity] constructor silently reset `uuid`/`content_hash`; reverting any seam to it (the
 * exact C3-3 defect) must turn one of these assertions red.
 */
class PromptProvenanceTest {

    private fun row(
        uuid: String = "",
        contentHash: String = "",
        name: String? = "Greeting",
        prompt: String? = "Hello!",
        requiresSelection: Boolean = false,
        autoApply: Boolean = false,
        type: String = PromptType.PROMPT.name,
        visibility: String = "PRIVATE",
        subscriptionMode: String = "LOCAL",
        sourcePeerId: String? = null,
        sourceOriginalId: String? = null,
        sourceOriginalHash: String? = null,
        updatedAt: Long = 0,
    ) = PromptEntity(
        id = 1,
        pos = 0,
        name = name,
        prompt = prompt,
        requiresSelection = requiresSelection,
        autoApply = autoApply,
        type = type,
        uuid = uuid,
        visibility = visibility,
        subscriptionMode = subscriptionMode,
        sourcePeerId = sourcePeerId,
        sourceOriginalId = sourceOriginalId,
        sourceOriginalHash = sourceOriginalHash,
        contentHash = contentHash,
        updatedAt = updatedAt,
    )

    // ── stamped ──

    @Test
    fun `stamped mints a uuid when empty and computes the matching content hash`() {
        val stamped = PromptProvenance.stamped(row(uuid = ""), now = 12_345L)

        assertFalse("uuid must be minted", stamped.uuid.isEmpty())
        // Well-formed UUID (throws if not).
        UUID.fromString(stamped.uuid)
        assertEquals(12_345L, stamped.updatedAt)
        assertEquals(PromptHashing.contentHashOf(stamped.uuid, stamped), stamped.contentHash)
        assertFalse("content hash must be populated", stamped.contentHash.isEmpty())
    }

    @Test
    fun `stamped preserves an existing uuid`() {
        val existing = UUID.randomUUID().toString()

        val stamped = PromptProvenance.stamped(row(uuid = existing), now = 1L)

        assertEquals(existing, stamped.uuid)
    }

    @Test
    fun `stamped recomputes a stale content hash without touching the uuid`() {
        val existing = UUID.randomUUID().toString()

        val stamped = PromptProvenance.stamped(row(uuid = existing, contentHash = "stale-hash"), now = 1L)

        assertEquals(existing, stamped.uuid)
        assertNotEquals("stale-hash", stamped.contentHash)
        assertEquals(PromptHashing.contentHashOf(existing, stamped), stamped.contentHash)
    }

    // ── edited ──

    @Test
    fun `edited re-hashes while preserving uuid and peer provenance`() {
        val uuid = UUID.randomUUID().toString()
        val existing = PromptProvenance.stamped(
            row(uuid = uuid, sourcePeerId = "peer-A", sourceOriginalId = "orig-1"),
            now = 1L,
        )

        val edited = PromptProvenance.edited(
            existing,
            name = "New name",
            prompt = "New body",
            requiresSelection = true,
            autoApply = true,
            type = PromptType.TEXT.name,
        )

        // Content changed → new fields applied.
        assertEquals("New name", edited.name)
        assertEquals("New body", edited.prompt)
        assertTrue(edited.requiresSelection)
        assertTrue(edited.autoApply)
        assertEquals(PromptType.TEXT.name, edited.type)
        // Envelope carried over.
        assertEquals(uuid, edited.uuid)
        assertEquals("peer-A", edited.sourcePeerId)
        assertEquals("orig-1", edited.sourceOriginalId)
        // Hash re-derived from the new payload.
        assertNotEquals(existing.contentHash, edited.contentHash)
        assertEquals(PromptHashing.contentHashOf(uuid, edited), edited.contentHash)
    }

    // ── localCopy ──

    @Test
    fun `localCopy clears provenance and assigns a fresh uuid`() {
        val sourceUuid = UUID.randomUUID().toString()
        val source = row(
            uuid = sourceUuid,
            sourcePeerId = "peer-A",
            sourceOriginalId = "orig-1",
            sourceOriginalHash = "hash-1",
            visibility = "SHARED",
            subscriptionMode = "SUBSCRIBED",
        )

        val copy = PromptProvenance.localCopy(source)

        assertFalse(copy.uuid.isEmpty())
        assertNotEquals(sourceUuid, copy.uuid)
        UUID.fromString(copy.uuid)
        assertNull(copy.sourcePeerId)
        assertNull(copy.sourceOriginalId)
        assertNull(copy.sourceOriginalHash)
        assertEquals("PRIVATE", copy.visibility)
        assertEquals("LOCAL", copy.subscriptionMode)
        assertEquals(PromptHashing.contentHashOf(copy.uuid, copy), copy.contentHash)
    }
}
