package net.devemperor.dictate.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.devemperor.dictate.state.DictateUiState

/**
 * Drives the IME's **language-chip** label refresh reactively from
 * `state.language.effective`
 * (indirection-cleanup 2026-05-21, Chunk 4.5b — OQ-1 Option A
 * follow-up).
 *
 * # Why this exists
 *
 * Before Chunk 4.5a the IME held a custom
 * `inputLanguagesListener` SP-listener that fired on every
 * `Pref.InputLanguages` / `Pref.InputLanguagePos` write and called
 * `pushPermanentLanguageToOrchestrator()` (which dispatched
 * `RefreshFromPref` and then `refreshLanguageChip()`). Chunk 4.5a
 * folded the SP→state half into the PipelinePrefMirror as a computed
 * mirror; this observer closes the loop on the IME's view side: when
 * `state.language.effective` changes (whether from the mirror or from
 * a `RefreshFromPref` dispatch), the chip label re-resolves.
 *
 * The observer follows the [InfoBarRenderer] / [EditBarAudioFocusObserver]
 * pattern — a [Listener] callback (not a controller reference) so the
 * collector stays JVM-testable.
 *
 * # Lifecycle
 *
 * [start] launches a collector on a private `Dispatchers.Main`
 * scope; [stop] cancels it. The IME starts the observer alongside
 * the sibling observers and stops it in `onDestroy` symmetrically.
 * Idempotent.
 *
 * @see net.devemperor.dictate.core.InfoBarRenderer — sibling
 *   Java-bridge pattern.
 * @see docs/plans/2026-05-21 - dictate-indirection-cleanup/dictate-indirection-cleanup.md §6.2 OQ-1
 */
class LanguageEffectiveObserver @JvmOverloads constructor(
    private val state: StateFlow<DictateUiState>,
    private val onChanged: Listener,
    private val mainDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main,
) {

    private var scope: CoroutineScope? = null

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + mainDispatcher)
        scope = s
        s.launch {
            state
                .map { it.language.effective }
                .distinctUntilChanged()
                .collect { effective -> onChanged.onEffectiveLanguageChanged(effective) }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
    }

    fun interface Listener {
        fun onEffectiveLanguageChanged(effective: String)
    }
}
