package net.devemperor.dictate.history

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import net.devemperor.dictate.database.dao.SessionDao
import net.devemperor.dictate.database.entity.SessionEntity

/**
 * Builds the paged history stream for the in-keyboard history panel
 * (Paket 3 / ADR-0014).
 *
 * Mirrors `HistoryViewModel`'s pager but without the Android `ViewModel`: the
 * IME is not a `ViewModelStoreOwner`, so `cachedIn` takes a caller-owned
 * [CoroutineScope] (the IME's panel scope, cancelled with the input view) in
 * place of `viewModelScope`. No filter/search combine — the panel shows the
 * full history; search stays on the full-screen activity.
 *
 * Room's `InvalidationTracker` invalidates [SessionDao.pagedHistoryPanel] on any
 * `sessions` write, so a pipeline completing while the panel is open refreshes
 * the list (and surfaces the new pending part on top) for free.
 */
open class KeyboardHistoryPager(private val dao: SessionDao) {

    open fun flow(scope: CoroutineScope): Flow<PagingData<SessionEntity>> =
        Pager(
            config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false),
            pagingSourceFactory = { dao.pagedHistoryPanel() },
        ).flow.cachedIn(scope)

    companion object {
        /** Parity with `HistoryViewModel.PAGE_SIZE`. */
        const val PAGE_SIZE = 40
    }
}
