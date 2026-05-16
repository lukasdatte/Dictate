package net.devemperor.dictate.testutil

import net.devemperor.dictate.core.PipelineUiCallback
import net.devemperor.dictate.core.PipelineUiState
import net.devemperor.dictate.core.PipelineUiStateReader

/**
 * In-memory [PipelineUiStateReader] for unit tests of consumers that
 * need to drive ReprocessStaging state transitions and observe callback
 * registration without spinning up the real `PipelineStepRowRenderer`
 * (the CR-DEL-relocated pipeline-UI owner; depends on Android views).
 * The legacy effective-language controller consumer was removed in D-13
 * (Epic §4 Block C1); this fake still backs the `PipelineUiStateReader`
 * multi-callback contract tests.
 *
 * Quality-Gate K-1 — pure handwritten fake, no Mockito.
 */
class FakePipelineUiStateReader : PipelineUiStateReader {

    override var state: PipelineUiState = PipelineUiState.Idle

    /** Tracks the last code passed to [updateReprocessLanguage]. `null` if never called. */
    var lastUpdateLanguage: String? = null
        private set

    /** Number of times [updateReprocessLanguage] was invoked. */
    var updateLanguageCallCount: Int = 0
        private set

    private val callbacks = mutableListOf<PipelineUiCallback>()

    override fun updateReprocessLanguage(code: String) {
        lastUpdateLanguage = code
        updateLanguageCallCount++
        val current = state
        if (current is PipelineUiState.ReprocessStaging) {
            val newState = current.copy(selectedLanguage = code)
            val old = state
            state = newState
            // Snapshot to avoid ConcurrentModification if a callback adds/removes.
            callbacks.toList().forEach { it.onPipelineUiStateChanged(old, newState) }
        }
    }

    override fun addCallback(callback: PipelineUiCallback) {
        if (callback !in callbacks) callbacks.add(callback)
    }

    override fun removeCallback(callback: PipelineUiCallback) {
        callbacks.remove(callback)
    }

    /** True iff [callback] is currently registered. Test introspection helper. */
    fun isRegistered(callback: PipelineUiCallback): Boolean = callback in callbacks

    /**
     * Drive a state transition and dispatch the same `onPipelineUiStateChanged`
     * notification that the real controller would emit. Use this in tests
     * to simulate entering/leaving ReprocessStaging.
     */
    fun simulateStateChange(newState: PipelineUiState) {
        val old = state
        state = newState
        callbacks.toList().forEach { it.onPipelineUiStateChanged(old, newState) }
    }
}
