package net.devemperor.dictate.history

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * Owns the Paging lifecycle for the in-keyboard history panel (ADR-0014).
 *
 * The IME is not a `LifecycleOwner`/`ViewModelStoreOwner`, so the Paging stream
 * cannot ride `viewModelScope` + `repeatOnLifecycle`. This controller centralises
 * the three cancel points (input-view destroy/rebuild, panel close) behind one
 * caller-owned scope so the IME stays thin and the wiring is unit-testable:
 *
 *  - [onViewCreated] — fresh scope for the current input view (destroys any old one).
 *  - [onPanelOpen]   — start the collector (idempotent — no double-collect).
 *  - [onPanelClosed] — stop the collector but keep the scope (cheap reopen).
 *  - [onViewDestroyed] — cancel scope + collector (no leak, no background query).
 *
 * @property mainDispatcher injected for tests; production is `Dispatchers.Main.immediate`.
 */
class KeyboardHistoryController @JvmOverloads constructor(
    private val pager: KeyboardHistoryPager,
    private val adapter: KeyboardHistoryAdapter,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private var scope: CoroutineScope? = null
    private var collectJob: Job? = null

    /** onCreateInputView: a fresh scope; any previous one is torn down first. */
    fun onViewCreated() {
        onViewDestroyed()
        scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    }

    /** Panel opened: start the collector if it is not already running. */
    fun onPanelOpen() {
        val s = scope ?: return
        if (collectJob?.isActive == true) return
        collectJob = s.launch {
            pager.flow(s).collectLatest { adapter.submitData(it) }
        }
    }

    /** Panel closed: stop the collector; keep the scope for a cheap reopen. */
    fun onPanelClosed() {
        collectJob?.cancel()
        collectJob = null
    }

    /** onDestroy / input-view rebuild: tear everything down. */
    fun onViewDestroyed() {
        collectJob = null
        scope?.cancel()
        scope = null
    }

    /** Test-only visibility into whether the collector is currently running. */
    internal fun isCollecting(): Boolean = collectJob?.isActive == true
}
