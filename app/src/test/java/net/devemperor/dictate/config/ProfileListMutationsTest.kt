package net.devemperor.dictate.config

import net.devemperor.dictate.shared.config.ProfileEntity
import net.devemperor.dictate.shared.config.SourceRef
import net.devemperor.dictate.shared.config.SubscriptionMode
import net.devemperor.dictate.shared.config.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §10.3 profile-list arithmetic: duplicate creates a fresh LOCAL profile (payload kept, envelope
 * reset), the pref-backed display order merges stored rows with the order string, and moves are
 * bounds-safe. Mirrors `PromptListMutationsTest` for the profile list.
 */
class ProfileListMutationsTest {

    private fun profile(id: String, name: String = "P-$id") = ProfileEntity(
        id = id,
        name = name,
        contentHash = "hash-$id",
        updatedAt = 42L,
        visibility = Visibility.SHARED,
        sourceRef = SourceRef("peer-1", "orig-1", "hash-1"),
        subscriptionMode = SubscriptionMode.SUBSCRIBE,
        stylePromptCustomText = "custom",
    )

    // ── copyOf ──

    @Test
    fun copyOf_keepsPayloadAndAppendsSuffix() {
        val copy = ProfileListMutations.copyOf(profile("a"), "(copy)")
        assertEquals("P-a (copy)", copy.name)
        assertEquals("custom", copy.stylePromptCustomText)
    }

    @Test
    fun copyOf_assignsFreshIdAndResetsEnvelope() {
        val source = profile("a")
        val copy = ProfileListMutations.copyOf(source, "(copy)")
        assertNotEquals(source.id, copy.id)
        assertEquals("", copy.contentHash)
        assertEquals(0L, copy.updatedAt)
        assertEquals(Visibility.PRIVATE, copy.visibility)
        assertEquals(SubscriptionMode.LOCAL, copy.subscriptionMode)
        assertNull("a duplicate is a local profile, not a peer copy", copy.sourceRef)
    }

    @Test
    fun copyOf_twiceGivesDistinctIds() {
        val source = profile("a")
        assertNotEquals(
            ProfileListMutations.copyOf(source, "x").id,
            ProfileListMutations.copyOf(source, "x").id,
        )
    }

    // ── ordered ──

    @Test
    fun ordered_followsOrderString() {
        val profiles = listOf(profile("a"), profile("b"), profile("c"))
        val ordered = ProfileListMutations.ordered(profiles, "c,a,b")
        assertEquals(listOf("c", "a", "b"), ordered.map { it.id })
    }

    @Test
    fun ordered_appendsUnknownProfilesAndDropsStaleIds() {
        val profiles = listOf(profile("a"), profile("b"))
        // "gone" no longer exists; "b" is not in the order string yet.
        val ordered = ProfileListMutations.ordered(profiles, "gone,a")
        assertEquals(listOf("a", "b"), ordered.map { it.id })
    }

    @Test
    fun ordered_emptyOrderKeepsIncomingOrder() {
        val profiles = listOf(profile("b"), profile("a"))
        assertEquals(listOf("b", "a"), ProfileListMutations.ordered(profiles, "").map { it.id })
    }

    // ── moved ──

    @Test
    fun moved_swapsNeighbours() {
        assertEquals("b,a,c", ProfileListMutations.moved(listOf("a", "b", "c"), 0, 1))
        assertEquals("a,c,b", ProfileListMutations.moved(listOf("a", "b", "c"), 2, 1))
    }

    @Test
    fun moved_outOfRangeIsNoOp() {
        assertEquals("a,b", ProfileListMutations.moved(listOf("a", "b"), 0, -1))
        assertEquals("a,b", ProfileListMutations.moved(listOf("a", "b"), 1, 2))
    }

    @Test
    fun orderRoundTrip() {
        val ids = listOf("x", "y", "z")
        assertEquals(ids, ProfileListMutations.parseOrder(ProfileListMutations.serializeOrder(ids)))
        assertEquals(emptyList<String>(), ProfileListMutations.parseOrder(""))
    }
}
