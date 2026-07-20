package net.devemperor.dictate.config

import net.devemperor.dictate.shared.config.SourceRef

/**
 * Rebuilds a [SourceRef] envelope value from its three flat persistence columns, or `null` when the
 * row carries no provenance (a locally-authored entity). All three columns are written together and
 * cleared together, so the null-guard is all-or-nothing: any single null means "no source".
 *
 * Shared between [ConfigEntityMapper] (over the `:shared`-DTO Room rows) and [CatalogExport] (over
 * the legacy `PromptEntity`, whose columns the mapper's typed rows cannot reach) so the guard has a
 * single definition (spec §7.1, §10.5).
 */
internal fun sourceRefOrNull(peerId: String?, originalId: String?, originalHash: String?): SourceRef? =
    if (peerId != null && originalId != null && originalHash != null) {
        SourceRef(peerId, originalId, originalHash)
    } else {
        null
    }
