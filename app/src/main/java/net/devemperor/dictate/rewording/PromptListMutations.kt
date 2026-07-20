package net.devemperor.dictate.rewording

import net.devemperor.dictate.database.entity.PromptEntity

/**
 * Pure position/copy arithmetic for the prompts-overview list, extracted from
 * the Activity so drag-and-drop persistence and duplication are unit-testable
 * without Android. The Activity applies the returned entities via [net.devemperor.dictate.database.dao.PromptDao].
 */
object PromptListMutations {

    /**
     * Copy of [source] for insertion at [pos]: fresh row (id 0 → autoGenerate),
     * localized copy suffix appended to the name, everything else verbatim —
     * including [PromptEntity.type], so text pills duplicate as text pills.
     */
    @JvmStatic
    fun copyOf(source: PromptEntity, copySuffix: String, pos: Int): PromptEntity =
        // C3: localCopy stamps a fresh uuid + content hash and drops peer provenance — the
        // duplicate is a new local prompt (spec §7.3).
        net.devemperor.dictate.config.PromptProvenance.localCopy(
            source.copy(
                id = 0,
                pos = pos,
                name = "${source.name.orEmpty()} $copySuffix",
            )
        )

    /**
     * Entities whose stored `pos` no longer matches their list index, rebuilt
     * with `pos = index`. Returns only the changed rows so the caller writes
     * the minimal set after a drag-and-drop or mid-list insertion.
     */
    @JvmStatic
    fun resequenced(list: List<PromptEntity>): List<PromptEntity> =
        list.mapIndexedNotNull { index, entity ->
            if (entity.pos == index) null else entity.copy(pos = index)
        }
}
