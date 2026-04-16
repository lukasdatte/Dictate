package net.devemperor.dictate.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide registry of active jobs. One instance per process (Kotlin object).
 *
 * Exposes a reactive [StateFlow] that UI components can observe to render
 * running-job indicators without imperative state synchronisation.
 *
 * Thread safety: StateFlow is thread-safe; [register] returns false if the
 * sessionId is already active (lock against double-start).
 *
 * Only one active job at a time (by requirement). Calls to [register] while
 * another job is active will return false and the caller must reject.
 */
object ActiveJobRegistry {

    // NOTE (Finding SEC-3-4): The state is Map<String, JobState> even though
    // only 0 or 1 entries are possible (single-job constraint). This is intentional
    // for forward-compatibility — if the single-job constraint is relaxed in the
    // future, the API and all consumers are already Map-ready.
    // Alternative: StateFlow<JobState?> would be simpler but requires a breaking
    // API change when adding multi-job support.
    private val _state = MutableStateFlow<Map<String, JobState>>(emptyMap())

    /** Observable map of sessionId → JobState. */
    val state: StateFlow<Map<String, JobState>> = _state.asStateFlow()

    /**
     * Attempts to register a job. Returns false if another job is already active.
     */
    @Synchronized
    fun register(sessionId: String, initial: JobState.Running): Boolean {
        if (_state.value.isNotEmpty()) return false
        _state.update { it + (sessionId to initial) }
        return true
    }

    /** Updates progress of a running job. No-op if session is not registered. */
    @Synchronized
    fun update(sessionId: String, newState: JobState.Running) {
        _state.update { current ->
            if (current.containsKey(sessionId)) current + (sessionId to newState) else current
        }
    }

    /** Removes a job from the registry (call on completion, failure, or cancel). */
    @Synchronized
    fun unregister(sessionId: String) {
        _state.update { it - sessionId }
    }

    /** Quick check: is any job active? */
    fun isAnyActive(): Boolean = _state.value.isNotEmpty()

    /** Quick check: is this specific session active? */
    fun isActive(sessionId: String): Boolean = _state.value.containsKey(sessionId)

    /** Current state for a session (null if not active). */
    fun get(sessionId: String): JobState? = _state.value[sessionId]
}
