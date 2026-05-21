package net.devemperor.dictate.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.devemperor.dictate.state.AudioState
import net.devemperor.dictate.state.DictateUiState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM tests for [EditBarAudioFocusObserver].
 *
 * The observer is a thin StateFlow → boolean-callback bridge. We
 * inject a [StandardTestDispatcher] for `mainDispatcher` so the
 * collector runs deterministically; the callback captures every value
 * for assertions.
 *
 * Coverage:
 * - First emit on subscribe carries the current `audioFocusEnabledPref`.
 * - `distinctUntilChanged` suppresses duplicate values.
 * - Unrelated state mutations do not trigger the callback.
 * - `stop` cancels the collector (no further emits after stop).
 * - Double `start` is idempotent (one collector, not two).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditBarAudioFocusObserverTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var stateFlow: MutableStateFlow<DictateUiState>
    private val captured: MutableList<Boolean> = mutableListOf()

    @Before
    fun setUp() {
        stateFlow = MutableStateFlow(DictateUiState.initial())
        captured.clear()
    }

    private fun newObserver(): EditBarAudioFocusObserver = EditBarAudioFocusObserver(
        state = stateFlow,
        onChanged = { enabled -> captured += enabled },
        mainDispatcher = dispatcher,
    )

    @Test
    fun `start emits the current audioFocusEnabledPref on first subscribe`() = runTest(dispatcher) {
        // Default `DictateUiState.initial()` carries the AudioState default
        // (audioFocusEnabledPref = true per Pref.AudioFocus default).
        // First subscribe MUST fire so the imperative seed call can be
        // dropped from the IME's attach path (Chunk 3.3).
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()

        assertEquals(listOf(true), captured)
        observer.stop()
    }

    @Test
    fun `distinct emits flow through but duplicate values do not`() = runTest(dispatcher) {
        stateFlow.value = stateFlow.value.copy(
            audio = AudioState(audioFocusEnabledPref = false),
        )
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()

        // Toggle on
        stateFlow.value = stateFlow.value.copy(
            audio = stateFlow.value.audio.copy(audioFocusEnabledPref = true),
        )
        advanceUntilIdle()

        // Idempotent re-write — same value
        stateFlow.value = stateFlow.value.copy(
            audio = stateFlow.value.audio.copy(audioFocusEnabledPref = true),
        )
        advanceUntilIdle()

        // Toggle off
        stateFlow.value = stateFlow.value.copy(
            audio = stateFlow.value.audio.copy(audioFocusEnabledPref = false),
        )
        advanceUntilIdle()

        // distinctUntilChanged collapses the duplicate true → only three emits.
        assertEquals(listOf(false, true, false), captured)
        observer.stop()
    }

    @Test
    fun `unrelated state changes do not trigger the callback`() = runTest(dispatcher) {
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()
        captured.clear()

        // Mutate a different sub-axis — audioFocusGranted, not the pref.
        stateFlow.value = stateFlow.value.copy(
            audio = stateFlow.value.audio.copy(audioFocusGranted = true),
        )
        advanceUntilIdle()

        // `map { audioFocusEnabledPref }` + `distinctUntilChanged` →
        // the same boolean still → no new emit.
        assertEquals(emptyList<Boolean>(), captured)
        observer.stop()
    }

    @Test
    fun `stop cancels the collector — no further emits arrive`() = runTest(dispatcher) {
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()
        captured.clear()

        observer.stop()
        // Mutate after stop — collector is cancelled.
        stateFlow.value = stateFlow.value.copy(
            audio = stateFlow.value.audio.copy(audioFocusEnabledPref = false),
        )
        advanceUntilIdle()

        assertEquals(emptyList<Boolean>(), captured)
    }

    @Test
    fun `double start is idempotent — only one collector emits`() = runTest(dispatcher) {
        val observer = newObserver()
        observer.start()
        advanceUntilIdle()

        // Already running — second start should not spawn a second
        // collector (would double-fire on every change).
        observer.start()
        advanceUntilIdle()

        captured.clear()
        stateFlow.value = stateFlow.value.copy(
            audio = stateFlow.value.audio.copy(audioFocusEnabledPref = false),
        )
        advanceUntilIdle()

        assertEquals(listOf(false), captured)
        observer.stop()
    }
}
