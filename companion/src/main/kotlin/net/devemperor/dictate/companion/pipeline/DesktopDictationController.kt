package net.devemperor.dictate.companion.pipeline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * The one dispatch door of the desktop host (desktop-host.md §5.1) — the small, testable analogue of
 * the Android `DictateInputMethodService` god-class.
 *
 * It holds the pure [DesktopUiState], routes every [DictationIntent] through the pure
 * [DictationReducer], and hands the resulting [Effect]s to [DictationEffects] for IO. Hotkey, panel
 * clicks and the pipeline's own async callbacks all enter through [dispatch]; the panel renderer only
 * reads [state]. Session ids are minted here (not in the reducer) so the reducer stays IO-free (§5.4).
 *
 * Threading: [dispatch] serializes state transitions under a lock but runs effects **outside** it, so
 * an inline effect that re-dispatches (the headless test path) does not deadlock, and a job-queue
 * callback arriving on the worker thread interleaves safely with a user intent on the UI thread.
 */
class DesktopDictationController(
    private val effects: DictationEffects,
) {

    private val _state = MutableStateFlow(DesktopUiState())
    val state: StateFlow<DesktopUiState> = _state.asStateFlow()

    private val lock = Any()

    /** Routes [intent] through the reducer, then executes its effects. */
    fun dispatch(intent: DictationIntent) {
        val pending = synchronized(lock) {
            val (next, effs) = DictationReducer.reduce(_state.value, intent)
            _state.value = next
            effs
        }
        pending.forEach { effects.run(it, ::dispatch) }
    }

    // ── convenience entry points (hotkey / panel buttons, D2) ────────────────────────────────

    /** Start a new take (or enqueue it if a pipeline is running). Returns the minted session id. */
    fun startHotkey(): String {
        val sessionId = UUID.randomUUID().toString()
        dispatch(DictationIntent.StartHotkey(sessionId))
        return sessionId
    }

    fun pauseRecording() = dispatch(DictationIntent.PauseRecording)
    fun resumeRecording() = dispatch(DictationIntent.ResumeRecording)
    fun stopRecording() = dispatch(DictationIntent.StopRecording)
    fun discard() = dispatch(DictationIntent.Discard)
}
