package net.devemperor.dictate.companion.ui.peers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.devemperor.dictate.companion.data.CompanionConfigRepository
import net.devemperor.dictate.companion.domain.port.CatalogAccess
import net.devemperor.dictate.companion.domain.port.CatalogAuditLog
import net.devemperor.dictate.shared.config.Visibility
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire

/**
 * One of my entities as the offer view shows it: what it is, whether it is shared, and — read-only —
 * when a peer last picked it up ([lastAccess], from the `catalog_access_log`, §5.4).
 */
data class OfferRow(
    val id: String,
    val kind: CatalogEntityKindWire,
    val label: String,
    val visibility: Visibility,
    val lastAccess: CatalogAccess?,
)

data class OfferUiState(val rows: List<OfferRow> = emptyList())

/**
 * The offer view's brain ("Was biete ich an?", peer-katalog.md §8.2 / F34): visibility management
 * over my own entities plus the last pickup per shared entity from the access log.
 *
 * What the log shows is deliberately minimal — who (peer_device_id), what (entity id/kind), when —
 * no content, no frequency analytics (§8.2). Credential pickups are the reason the log exists (R8)
 * and always appear; here they surface through their provider config's row, since a companion has
 * no credential entity table (the secret lives in the SecretStore).
 *
 * Toggling visibility goes through [CompanionConfigRepository.save] like every other entity write —
 * and cannot change the `content_hash`, because `visibility` is an envelope field excluded from
 * canonical serialization. Un-sharing therefore never masquerades as a content update to subscribers;
 * they see the entity vanish from the index (SOURCE_REMOVED), which is the honest signal (§6.4).
 *
 * @see docs/decisions/0034-peer-catalog.md
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/peer-katalog.md §8.2
 */
class OfferViewModel(
    private val config: CompanionConfigRepository,
    private val auditLog: CatalogAuditLog,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(OfferUiState())
    val state: StateFlow<OfferUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = scope.launch { load() }

    fun setVisibility(row: OfferRow, visibility: Visibility) = scope.launch {
        when (row.kind) {
            CatalogEntityKindWire.PROVIDER_CONFIG ->
                config.providerConfig(row.id)?.let { config.save(it.copy(visibility = visibility)) }
            CatalogEntityKindWire.MODEL_REF ->
                config.modelRef(row.id)?.let { config.save(it.copy(visibility = visibility)) }
            CatalogEntityKindWire.PROMPT ->
                config.prompt(row.id)?.let { config.save(it.copy(visibility = visibility)) }
            CatalogEntityKindWire.PROFILE ->
                config.profile(row.id)?.let { config.save(it.copy(visibility = visibility)) }
            CatalogEntityKindWire.CREDENTIAL, CatalogEntityKindWire.UNKNOWN -> Unit
        }
        load()
    }

    private fun load() {
        // Newest-first log → the first row per entity id IS the last pickup.
        val lastAccessById = HashMap<String, CatalogAccess>()
        auditLog.all().forEach { access -> lastAccessById.putIfAbsent(access.entityId, access) }

        val rows = buildList {
            config.providerConfigs().forEach {
                add(OfferRow(it.id, CatalogEntityKindWire.PROVIDER_CONFIG, it.label, it.visibility, lastAccessById[it.id] ?: it.credentialRef?.let(lastAccessById::get)))
            }
            config.modelRefs().forEach {
                add(OfferRow(it.id, CatalogEntityKindWire.MODEL_REF, it.label ?: it.modelId, it.visibility, lastAccessById[it.id]))
            }
            config.prompts().forEach {
                add(OfferRow(it.id, CatalogEntityKindWire.PROMPT, it.name, it.visibility, lastAccessById[it.id]))
            }
            config.profiles().forEach {
                add(OfferRow(it.id, CatalogEntityKindWire.PROFILE, it.name, it.visibility, lastAccessById[it.id]))
            }
        }
        _state.value = OfferUiState(rows)
    }
}
