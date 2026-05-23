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
 * Drives the **edit-bar** audio-focus-twin (`editAudioFocusButton`) icon
 * + contentDescription reactively from `state.audio.audioFocusEnabledPref`
 * (indirection-cleanup 2026-05-21, Chunk 3.3 — A-3 Part 3).
 *
 * # Why this exists
 *
 * The main-button-area audio-focus button (the "twin") is rendered by
 * the catalog `AUDIO_FOCUS` slot's `iconResolver`
 * (`resolveAudioFocusIconForSlot`) — state-reactive. The edit-bar
 * audio-focus button, however, lived outside that pipeline: the legacy
 * code re-painted it imperatively from three places (the click handler
 * `onAudioFocusToggled`, the SP-listener `audioFocusListener`, and the
 * initial render in `attachDormantEditBarEmojiOwners`). Chunk 3.4 +
 * Chunk 3.5 retire the first two; the initial render is taken over by
 * this observer's first emit (the `StateFlow` carries the current value
 * on subscribe).
 *
 * The observer is a thin Java-bridge in the [InfoBarRenderer]
 * mould: it watches a single sub-axis with `distinctUntilChanged` and
 * forwards the boolean to a [Listener] callback (the IME passes a
 * lambda that calls `EditBarController.refreshAudioFocusIcon`). Using a
 * callback (not a controller reference) keeps the observer
 * JVM-testable — it does not require the Android-View-backed
 * controller in unit tests.
 *
 * # Lifecycle
 *
 * [start] launches a collector on a private `Dispatchers.Main` scope;
 * [stop] cancels it. The IME starts the observer in
 * `onCreateInputView`/`onServiceConnected` after the
 * [EditBarController] is attached, and stops it in
 * `onDestroyInputView`/`onDestroy`. Idempotent — a second [start] while
 * already running is a no-op; [stop] on an already-stopped observer is
 * safe.
 *
 * @see net.devemperor.dictate.core.InfoBarRenderer — sibling
 *   Java-bridge pattern.
 * @see docs/plans/2026-05-21 - dictate-indirection-cleanup/dictate-indirection-cleanup.md §4 Block 3 Chunk 3.3
 */
class EditBarAudioFocusObserver @JvmOverloads constructor(
    private val state: StateFlow<DictateUiState>,
    private val onChanged: Listener,
    private val mainDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main,
) {

    private var scope: CoroutineScope? = null

    /**
     * Begin observing. Idempotent — a second call while already running
     * keeps the existing collector.
     */
    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + mainDispatcher)
        scope = s
        s.launch {
            state
                .map { it.audio.audioFocusEnabledPref }
                .distinctUntilChanged()
                .collect { enabled -> onChanged.onAudioFocusEnabledChanged(enabled) }
        }
    }

    /** Stop observing. Idempotent. */
    fun stop() {
        scope?.cancel()
        scope = null
    }

    /** Functional-interface-compatible listener so Java lambdas work. */
    fun interface Listener {
        fun onAudioFocusEnabledChanged(enabled: Boolean)
    }
}
