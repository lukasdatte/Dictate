package net.devemperor.dictate.state.infobar

/**
 * Visual style classification for an [InfoBarItem] (ADR-0006 §"Items
 * render via a state-driven renderer").
 *
 * The style controls **only the visual presentation** (text color,
 * icon set). It is intentionally not a priority axis — sort order is
 * driven by `InfoBarItem.createdAt` (per the user's design constraint
 * "sortiert nach Entstehungszeitpunkt").
 *
 * @see InfoBarItem
 * @see InfoBarMessage
 */
enum class InfoBarStyle {
    /**
     * Standard informational tone — blue text on background. Used for
     * update / rate / donate / pending-insert hints and similar
     * non-urgent surfaces.
     */
    INFO,

    /**
     * Error tone — red text on background. Used for transient pipeline
     * errors (network / quota / model-not-found), invalid-api-key
     * notice, and similar conditions the user should be aware of
     * before continuing.
     */
    ERROR,

    /**
     * Action-required tone — emphasised but not red. Used for
     * affordances where the user is expected to make an explicit
     * choice (pending-recording resume / send / discard, manual paste).
     */
    ACTION,
}
