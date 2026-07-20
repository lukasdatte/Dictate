package net.devemperor.dictate.peers

import net.devemperor.dictate.config.entity.ModelRefRoomEntity
import net.devemperor.dictate.config.entity.ProfileRoomEntity
import net.devemperor.dictate.config.entity.ProviderConfigRoomEntity
import net.devemperor.dictate.database.entity.PromptEntity
import net.devemperor.dictate.peers.ui.PeerCopiesOverview
import net.devemperor.dictate.shared.config.SubscriptionMode
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android Peer Explorer's model (peer-katalog.md §8.3): only provenance-carrying rows appear,
 * grouped per source peer, with the fork-ness derived exactly like the companion side (mode LOCAL +
 * source set → fork).
 */
class PeerCopiesOverviewTest {

    @Test
    fun build_groupsCopiesByPeer_andSkipsLocalEntities() {
        val groups = PeerCopiesOverview.build(
            providers = listOf(
                provider("prov-local", sourcePeerId = null),
                provider("prov-a", sourcePeerId = "peer-1"),
            ),
            credentials = emptyList(),
            models = listOf(model("model-b", sourcePeerId = "peer-2")),
            profiles = listOf(profile("profile-a", sourcePeerId = "peer-1")),
            prompts = listOf(prompt(1, sourcePeerId = "peer-1")),
        )

        assertEquals(listOf("peer-1", "peer-2"), groups.map { it.peerId })
        assertEquals(3, groups.first { it.peerId == "peer-1" }.copies.size)
        assertEquals(
            listOf(CatalogEntityKindWire.MODEL_REF),
            groups.first { it.peerId == "peer-2" }.copies.map { it.kind },
        )
    }

    @Test
    fun build_marksLocalModeWithProvenanceAsFork() {
        val groups = PeerCopiesOverview.build(
            providers = emptyList(),
            credentials = emptyList(),
            models = emptyList(),
            profiles = emptyList(),
            prompts = listOf(prompt(1, sourcePeerId = "peer-1", mode = "LOCAL")),
        )

        assertTrue(groups.single().copies.single().isFork)
    }

    @Test
    fun build_emptyWithoutAnyProvenance() {
        val groups = PeerCopiesOverview.build(
            providers = listOf(provider("p", sourcePeerId = null)),
            credentials = emptyList(),
            models = emptyList(),
            profiles = emptyList(),
            prompts = listOf(prompt(1, sourcePeerId = null)),
        )

        assertTrue(groups.isEmpty())
    }

    @Test
    fun build_unknownModeText_degradesToLocal() {
        val groups = PeerCopiesOverview.build(
            providers = emptyList(),
            credentials = emptyList(),
            models = emptyList(),
            profiles = emptyList(),
            prompts = listOf(prompt(1, sourcePeerId = "peer-1", mode = "SUBSCRIB")),
        )

        assertEquals(SubscriptionMode.LOCAL, groups.single().copies.single().mode)
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    private fun provider(id: String, sourcePeerId: String?) = ProviderConfigRoomEntity(
        id = id,
        providerType = "OPENAI",
        label = "Provider $id",
        sourcePeerId = sourcePeerId,
        subscriptionMode = if (sourcePeerId == null) "LOCAL" else "SUBSCRIBE",
        contentHash = "h",
        updatedAt = 0,
    )

    private fun model(id: String, sourcePeerId: String?) = ModelRefRoomEntity(
        id = id,
        providerRef = "prov-a",
        modelId = "gpt-4o-mini",
        function = "COMPLETION",
        sourcePeerId = sourcePeerId,
        subscriptionMode = "SUBSCRIBE",
        contentHash = "h",
        updatedAt = 0,
    )

    private fun profile(id: String, sourcePeerId: String?) = ProfileRoomEntity(
        id = id,
        name = "Profile $id",
        sourcePeerId = sourcePeerId,
        subscriptionMode = "ONE_SHOT",
        contentHash = "h",
        updatedAt = 0,
    )

    private fun prompt(id: Int, sourcePeerId: String?, mode: String = "SUBSCRIBE") = PromptEntity(
        id = id,
        pos = id,
        name = "Prompt $id",
        prompt = "text",
        requiresSelection = false,
        autoApply = false,
        sourcePeerId = sourcePeerId,
        subscriptionMode = mode,
    )
}
