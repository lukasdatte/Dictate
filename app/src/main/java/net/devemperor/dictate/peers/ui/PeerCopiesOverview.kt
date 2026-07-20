package net.devemperor.dictate.peers.ui

import net.devemperor.dictate.config.entity.ApiCredentialRoomEntity
import net.devemperor.dictate.config.entity.ModelRefRoomEntity
import net.devemperor.dictate.config.entity.ProfileRoomEntity
import net.devemperor.dictate.config.entity.ProviderConfigRoomEntity
import net.devemperor.dictate.database.entity.PromptEntity
import net.devemperor.dictate.shared.config.SubscriptionMode
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire

/**
 * The read-only Android Peer Explorer's model (peer-katalog.md §8.3): every local copy whose
 * provenance names a peer, grouped by that peer — subscribed, one-shot, and forked alike.
 *
 * A pure builder over the Room rows so the grouping and state derivation are testable without
 * Robolectric (the phone-side sibling of the companion's `PeerExplorerViewModel`; the full §8.1
 * matrix needs the peers table + live index, which arrive with the delegated sync adapter — until
 * then the phone derives what its own rows prove: origin, mode, forked-ness).
 */
object PeerCopiesOverview {

    /** One copy taken from a peer, as the settings page lists it. */
    data class Copy(
        val kind: CatalogEntityKindWire,
        val label: String,
        val mode: SubscriptionMode,
    ) {
        val isFork: Boolean get() = mode == SubscriptionMode.LOCAL
    }

    /** All copies of one source peer. */
    data class PeerGroup(val peerId: String, val copies: List<Copy>)

    fun build(
        providers: List<ProviderConfigRoomEntity>,
        credentials: List<ApiCredentialRoomEntity>,
        models: List<ModelRefRoomEntity>,
        profiles: List<ProfileRoomEntity>,
        prompts: List<PromptEntity>,
    ): List<PeerGroup> {
        val copies = buildList {
            providers.forEach { entity ->
                entity.sourcePeerId?.let { add(it to Copy(CatalogEntityKindWire.PROVIDER_CONFIG, entity.label, entity.subscriptionModeEnum)) }
            }
            credentials.forEach { entity ->
                entity.sourcePeerId?.let { add(it to Copy(CatalogEntityKindWire.CREDENTIAL, entity.label, entity.subscriptionModeEnum)) }
            }
            models.forEach { entity ->
                entity.sourcePeerId?.let { add(it to Copy(CatalogEntityKindWire.MODEL_REF, entity.label ?: entity.modelId, entity.subscriptionModeEnum)) }
            }
            profiles.forEach { entity ->
                entity.sourcePeerId?.let { add(it to Copy(CatalogEntityKindWire.PROFILE, entity.name, entity.subscriptionModeEnum)) }
            }
            prompts.forEach { entity ->
                entity.sourcePeerId?.let { add(it to Copy(CatalogEntityKindWire.PROMPT, entity.name ?: "", mode(entity.subscriptionMode))) }
            }
        }
        return copies
            .groupBy({ it.first }, { it.second })
            .map { (peerId, peerCopies) -> PeerGroup(peerId, peerCopies.sortedWith(compareBy({ it.kind }, { it.label }))) }
            .sortedBy { it.peerId }
    }

    /** Room stores the mode as its string half of the Double-Enum; unknown text degrades to LOCAL. */
    private fun mode(raw: String): SubscriptionMode =
        runCatching { SubscriptionMode.valueOf(raw) }.getOrDefault(SubscriptionMode.LOCAL)
}
