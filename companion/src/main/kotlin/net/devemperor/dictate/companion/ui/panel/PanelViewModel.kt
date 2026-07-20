package net.devemperor.dictate.companion.ui.panel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.devemperor.dictate.companion.domain.port.ClockPort
import net.devemperor.dictate.companion.pipeline.DesktopUiState
import net.devemperor.dictate.companion.pipeline.RecordingUi

/**
 * Everything the warm panel renders (desktop-host.md §7.4): the pipeline's [DesktopUiState] plus the
 * presentation-only recording extras the reducer deliberately does not track — the 30-slot amplitude
 * ring buffer, the live `MM:SS` timer, and the glow level.
 */
data class PanelUi(
    val dictation: DesktopUiState = DesktopUiState(),
    /** [RecordingBarDesign.BAR_COUNT] levels, oldest left, newest right (§7.2). */
    val levels: List<Float> = RecordingBarDesign.emptyLevels(),
    val timerText: String = ZERO_TIMER,
    /** Latest normalized level, drives the glow tint (§7.3). */
    val glowLevel: Float = 0f,
) {
    companion object {
        const val ZERO_TIMER = "00:00"
    }
}

/**
 * The panel's brain — a plain class with a [StateFlow], no Compose (house pattern:
 * `HistoryViewModel`). The recording *time* lives here and not in the reducer: the reducer is
 * clock-free by design (§5.4), so the ViewModel derives elapsed time from [ClockPort] on a 10 Hz
 * tick — the same cadence the amplitude feed and the Android original use (§7.2).
 *
 * The [scope] is injected; [start] wires the flows and the ticker. Tests skip [start] and drive
 * [onDictationState]/[onAmplitude]/[onTick] directly against a `MutableClock` — buffer shift,
 * pause/resume timer freezing and the transitions are all synchronous state math.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/desktop-host.md §7
 */
class PanelViewModel(
    private val dictation: StateFlow<DesktopUiState>,
    private val amplitudes: Flow<Float>,
    private val scope: CoroutineScope,
    private val clock: ClockPort,
) {

    private val _state = MutableStateFlow(PanelUi())
    val state: StateFlow<PanelUi> = _state.asStateFlow()

    /** Wall-clock start of the current *un-paused* stretch, or `null` while paused / not recording. */
    private var activeSince: Long? = null

    /** Recorded milliseconds accumulated across completed stretches (pause keeps, discard resets). */
    private var accumulatedMillis = 0L

    /** Binds the pipeline state, the amplitude feed and the 10 Hz timer tick onto [scope]. */
    fun start() {
        scope.launch { dictation.collect { onDictationState(it) } }
        scope.launch { amplitudes.collect { onAmplitude(it) } }
        scope.launch {
            while (isActive) {
                delay(RecordingBarDesign.UPDATE_PERIOD_MILLIS)
                onTick()
            }
        }
    }

    internal fun onDictationState(next: DesktopUiState) {
        val previous = _state.value.dictation.recording
        val current = next.recording

        when {
            previous !is RecordingUi.Active && current is RecordingUi.Active -> {
                // A fresh take: cold buffer, zero timer (the Android widget clears on start too).
                accumulatedMillis = 0
                activeSince = if (current.paused) null else clock.nowMillis()
                _state.value = PanelUi(dictation = next)
                return
            }

            previous is RecordingUi.Active && current is RecordingUi.Active &&
                previous.paused != current.paused -> {
                if (current.paused) {
                    accumulatedMillis += activeSince?.let { clock.nowMillis() - it } ?: 0
                    activeSince = null
                } else {
                    activeSince = clock.nowMillis()
                }
            }

            current !is RecordingUi.Active -> {
                // Mic stopped (pipeline running or take over): freeze the timer, drop the glow. The
                // last waveform stays visible behind the transcode phases — matching Android, where
                // the bars only clear when the next take starts.
                accumulatedMillis += activeSince?.let { clock.nowMillis() - it } ?: 0
                activeSince = null
            }
        }
        _state.value = _state.value.copy(dictation = next, glowLevel = if (current is RecordingUi.Active) _state.value.glowLevel else 0f)
    }

    internal fun onAmplitude(level: Float) {
        _state.value = _state.value.copy(
            levels = RecordingBarDesign.pushLevel(_state.value.levels, level),
            glowLevel = level.coerceIn(0f, 1f),
        )
    }

    internal fun onTick() {
        val since = activeSince ?: return
        val elapsed = accumulatedMillis + (clock.nowMillis() - since)
        val text = RecordingBarDesign.formatTimer(elapsed)
        if (text != _state.value.timerText) _state.value = _state.value.copy(timerText = text)
    }
}
