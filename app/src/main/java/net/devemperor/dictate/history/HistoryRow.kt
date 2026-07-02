package net.devemperor.dictate.history

import net.devemperor.dictate.database.entity.SessionEntity

/**
 * List-row model for the paged history list (F-054 /
 * history-pagination-and-scale §2.1).
 *
 * Wraps the persisted [session] together with the *presentation-time*
 * snapshot of the in-memory running-flag from
 * [net.devemperor.dictate.core.ActiveJobRegistry]. Folding the flag
 * into the item (instead of reading the registry live during
 * `onBindViewHolder`, as the pre-paging adapter did) makes it part of
 * the DiffUtil identity: a registry tick → `adapter.refresh()` →
 * re-mapped rows → only the rows whose running-state actually changed
 * are rebound. A live registry read would be invisible to DiffUtil and
 * leave badges stale after a diff-based update.
 */
data class HistoryRow(
    val session: SessionEntity,
    /** `true` while [net.devemperor.dictate.core.ActiveJobRegistry] reports an active job for this session. */
    val isRunning: Boolean,
)
