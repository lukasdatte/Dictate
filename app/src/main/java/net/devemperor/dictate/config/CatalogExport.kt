package net.devemperor.dictate.config

import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.PromptEntity
import net.devemperor.dictate.database.entity.PromptType
import net.devemperor.dictate.shared.config.CatalogEntry
import net.devemperor.dictate.shared.config.CatalogFileV3
import net.devemperor.dictate.shared.config.PromptV3Entity
import net.devemperor.dictate.shared.config.SourceRef
import net.devemperor.dictate.shared.config.SubscriptionMode
import net.devemperor.dictate.shared.config.Visibility

/**
 * Builds the v3 catalog for the SAF file export (spec §10.5). Selection is either the whole local
 * catalog or one profile plus its referenced closure (model refs → provider configs, prompts).
 *
 * ## Never credentials (§13 D5, F12)
 * A file export shares prompts/profiles/models/providers — NEVER `ApiCredentialEntity` rows. An
 * exported `ProviderConfigEntity` keeps its `credentialRef` as a dangling reference; the receiver
 * sets their own key. Key delivery exists only as the authorised Block-E peer call.
 *
 * ## Prompts
 * Only PROMPT-type pills with a backfilled `uuid` are shareable; TEXT pills are Android-local
 * literals and never leave the device as v3 entities (ADR-0024, §13 D3).
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §10.5
 */
object CatalogExport {

    /** The whole shareable local catalog: providers, models, prompts, profiles — no credentials. */
    fun fullCatalog(db: DictateDatabase): CatalogFileV3 {
        val entries = mutableListOf<CatalogEntry>()
        db.providerConfigDao().getAll().forEach { entries += CatalogEntry.Provider(ConfigEntityMapper.toDto(it)) }
        db.modelRefDao().getAll().forEach { entries += CatalogEntry.Model(ConfigEntityMapper.toDto(it)) }
        db.promptDao().getAll().forEach { row -> toPromptDto(row)?.let { entries += CatalogEntry.Prompt(it) } }
        db.profileDao().getAll().forEach { row ->
            entries += CatalogEntry.Profile(ConfigEntityMapper.toDto(row, db.profileDao().promptsOf(row.id)))
        }
        return CatalogFileV3(entities = entries)
    }

    /**
     * One profile plus everything it references: its model refs, their provider configs, and the
     * prompts of its ordered-prompt list. Returns null if [profileId] does not exist.
     */
    fun profileCatalog(db: DictateDatabase, profileId: String): CatalogFileV3? {
        val profileRow = db.profileDao().byId(profileId) ?: return null
        val profile = ConfigEntityMapper.toDto(profileRow, db.profileDao().promptsOf(profileId))

        val entries = mutableListOf<CatalogEntry>()
        val modelRefs = listOfNotNull(profile.transcriptionModelRef, profile.completionModelRef)
            .mapNotNull { db.modelRefDao().byId(it) }
        modelRefs
            .mapNotNull { db.providerConfigDao().byId(it.providerRef) }
            .distinctBy { it.id }
            .forEach { entries += CatalogEntry.Provider(ConfigEntityMapper.toDto(it)) }
        modelRefs.forEach { entries += CatalogEntry.Model(ConfigEntityMapper.toDto(it)) }

        val promptUuids = profile.orderedPrompts.map { it.promptRef }.toSet()
        db.promptDao().getAll()
            .filter { it.uuid in promptUuids }
            .forEach { row -> toPromptDto(row)?.let { entries += CatalogEntry.Prompt(it) } }

        entries += CatalogEntry.Profile(profile)
        return CatalogFileV3(entities = entries)
    }

    /** Shareable DTO projection of an Android prompt row, or null for TEXT pills / unbackfilled rows. */
    fun toPromptDto(row: PromptEntity): PromptV3Entity? {
        if (row.typeEnum == PromptType.TEXT || row.uuid.isEmpty()) return null
        val sourceRef =
            if (row.sourcePeerId != null && row.sourceOriginalId != null && row.sourceOriginalHash != null) {
                SourceRef(row.sourcePeerId, row.sourceOriginalId, row.sourceOriginalHash)
            } else {
                null
            }
        return PromptV3Entity(
            id = row.uuid,
            contentHash = row.contentHash,
            updatedAt = row.updatedAt,
            visibility = runCatching { Visibility.valueOf(row.visibility) }.getOrDefault(Visibility.PRIVATE),
            sourceRef = sourceRef,
            subscriptionMode = runCatching { SubscriptionMode.valueOf(row.subscriptionMode) }
                .getOrDefault(SubscriptionMode.LOCAL),
            name = row.name.orEmpty(),
            text = row.prompt.orEmpty(),
            requiresSelection = row.requiresSelection,
            autoApply = row.autoApply,
        )
    }
}
