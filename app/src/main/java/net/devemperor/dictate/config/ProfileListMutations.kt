package net.devemperor.dictate.config

import net.devemperor.dictate.shared.config.ProfileEntity
import net.devemperor.dictate.shared.config.SubscriptionMode
import net.devemperor.dictate.shared.config.Visibility
import java.util.UUID

/**
 * Pure copy/order arithmetic for the profile list in the settings UI (spec §10.3), modelled after
 * [net.devemperor.dictate.rewording.PromptListMutations] so both "Overview" screens share the same
 * duplicate/reorder semantics without an Activity dependency.
 *
 * ## Why order lives in a pref, not a column
 * `profiles` (schema v12, C2) has no `pos` column, and the display order is device-local UI state,
 * not shareable content — a `pos` payload field would pollute the `contentHash` (same reasoning as
 * `is_active`, spec §13 D4). The order is therefore a comma-joined id list in
 * [net.devemperor.dictate.preferences.Pref.ProfileOrder]; [ordered] merges it with the stored rows
 * (unknown ids dropped, missing ids appended alphabetically), [moved] returns the updated pref value.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §10.3
 */
object ProfileListMutations {

    /**
     * Copy of [source] as a NEW local profile: fresh uuid, localized copy suffix on the name, payload
     * verbatim — but a fresh envelope (no provenance, private, local): duplicating a peer-sourced
     * profile creates a locally owned copy, exactly like duplicating a prompt pill. `contentHash`/
     * `updatedAt` are cleared; `ConfigRepository.upsertProfile` recomputes both on write (§5.3).
     */
    fun copyOf(source: ProfileEntity, copySuffix: String): ProfileEntity =
        source.copy(
            id = UUID.randomUUID().toString(),
            name = "${source.name} $copySuffix",
            contentHash = "",
            updatedAt = 0,
            visibility = Visibility.PRIVATE,
            sourceRef = null,
            subscriptionMode = SubscriptionMode.LOCAL,
        )

    /** Parses the `Pref.ProfileOrder` value (comma-joined ids; empty → empty list). */
    fun parseOrder(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Serialises an id list back into the `Pref.ProfileOrder` value. */
    fun serializeOrder(ids: List<String>): String = ids.joinToString(",")

    /**
     * [profiles] in display order: pref order first (ids without a row are skipped), then any rows
     * the pref does not know yet (freshly created/imported), keeping their incoming order.
     */
    fun ordered(profiles: List<ProfileEntity>, orderRaw: String): List<ProfileEntity> {
        val byId = profiles.associateBy { it.id }
        val known = parseOrder(orderRaw).mapNotNull { byId[it] }
        val knownIds = known.map { it.id }.toSet()
        return known + profiles.filter { it.id !in knownIds }
    }

    /**
     * New pref value after moving the profile at [from] to [to] within the CURRENT display order
     * [displayedIds]. Out-of-range indices return the unchanged serialised order.
     */
    fun moved(displayedIds: List<String>, from: Int, to: Int): String {
        if (from !in displayedIds.indices || to !in displayedIds.indices || from == to) {
            return serializeOrder(displayedIds)
        }
        val mutable = displayedIds.toMutableList()
        mutable.add(to, mutable.removeAt(from))
        return serializeOrder(mutable)
    }
}
