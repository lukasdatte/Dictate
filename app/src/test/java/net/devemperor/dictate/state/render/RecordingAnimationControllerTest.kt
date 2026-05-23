package net.devemperor.dictate.state.render

import android.view.View
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.widget.RecordingAnimation
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Unit tests for [RecordingAnimationController].
 *
 * The animation strategy is mocked via [FakeRecordingAnimation] (K-1 —
 * hand-rolled, no Mockito). The [pulseLayout] parameter is nullable, so
 * tests pass `null` to avoid pulling in `PulseLayout` (an Android
 * `FrameLayout` subclass that requires Robolectric).
 *
 * # Coverage focus
 *
 * 1. **Class-transition idempotency** — only the
 *    `RecordingState`-sealed-class branch change triggers work.
 * 2. **Lifecycle parity with the legacy `RecordingUiController`** —
 *    Idle / Active / Paused mappings to start / cancel / pause.
 * 3. **`animationsEnabled = false` shortcut** — verified for the
 *    Active, Paused, and Interrupted branches.
 * 4. **`reset()` re-arms first-apply after detach** — guards the
 *    view-recreate semantics on rotation.
 * 5. **Cold-Interrupted rendering** — a recovery-surfaced
 *    [RecordingState.Interrupted] must `start()`-then-`pause()` the
 *    animation (it is reached without a prior Active interval) and
 *    seed the frozen timer from `elapsedMs`.
 */
class RecordingAnimationControllerTest {

    @Test
    fun `idle then active starts the animation when enabled`() {
        val anim = FakeRecordingAnimation()
        val controller = RecordingAnimationController(anim, pulseLayout = null, animationsEnabled = { true })

        controller.onState(stateWithRecording(RecordingState.Idle))
        controller.onState(stateWithRecording(activeRecording()))

        assertEquals(listOf("cancel", "start"), anim.events)
    }

    @Test
    fun `active then paused calls pause`() {
        val anim = FakeRecordingAnimation()
        val controller = RecordingAnimationController(anim, pulseLayout = null, animationsEnabled = { true })

        controller.onState(stateWithRecording(activeRecording()))
        controller.onState(stateWithRecording(pausedRecording()))

        assertEquals(listOf("start", "pause"), anim.events)
    }

    @Test
    fun `paused then idle calls cancel`() {
        val anim = FakeRecordingAnimation()
        val controller = RecordingAnimationController(anim, pulseLayout = null, animationsEnabled = { true })

        controller.onState(stateWithRecording(pausedRecording()))
        controller.onState(stateWithRecording(RecordingState.Idle))

        assertEquals(listOf("pause", "cancel"), anim.events)
    }

    @Test
    fun `cold interrupted starts then freezes the animation and seeds the timer`() {
        // Regression (2026-05-22): a recovery-surfaced Interrupted recording
        // lands in a FRESH controller — no prior Active interval, so the
        // animation was never start()-ed. The pre-fix code grouped
        // Interrupted with Paused and emitted a bare pause(); on a cold
        // BorderGlowAnimation that builds no visualizer and drops the timer
        // text (`!isActive` guard) → the keyboard showed nothing.
        val anim = FakeRecordingAnimation()
        val controller = RecordingAnimationController(anim, pulseLayout = null, animationsEnabled = { true })

        controller.onState(stateWithRecording(interruptedRecording(elapsedMs = 8_000L)))

        // start() builds the visualizer (the timer host), pause() freezes it.
        assertEquals(listOf("start", "pause"), anim.events)
        // The "0:08" must be seeded straight from Interrupted.elapsedMs.
        assertEquals(listOf("00:08"), anim.timerTexts)
    }

    @Test
    fun `interrupted then active continuation restarts the animation`() {
        val anim = FakeRecordingAnimation()
        val controller = RecordingAnimationController(anim, pulseLayout = null, animationsEnabled = { true })

        // A Record-tap on the surfaced Interrupted recording continues it.
        controller.onState(stateWithRecording(interruptedRecording(elapsedMs = 8_000L)))
        controller.onState(stateWithRecording(activeRecording()))

        assertEquals(listOf("start", "pause", "start"), anim.events)
    }

    @Test
    fun `interrupted then idle discard cancels the animation`() {
        val anim = FakeRecordingAnimation()
        val controller = RecordingAnimationController(anim, pulseLayout = null, animationsEnabled = { true })

        controller.onState(stateWithRecording(interruptedRecording(elapsedMs = 5_000L)))
        controller.onState(stateWithRecording(RecordingState.Idle))

        assertEquals(listOf("start", "pause", "cancel"), anim.events)
    }

    @Test
    fun `animationsEnabled=false suppresses interrupted rendering`() {
        val anim = FakeRecordingAnimation()
        val controller = RecordingAnimationController(anim, pulseLayout = null, animationsEnabled = { false })

        controller.onState(stateWithRecording(interruptedRecording(elapsedMs = 8_000L)))

        // Same pref-gate as the Active branch — no visualizer, no timer.
        assertEquals(emptyList<String>(), anim.events)
        assertEquals(emptyList<String>(), anim.timerTexts)
    }

    @Test
    fun `re-emitting same recording class is idempotent`() {
        val anim = FakeRecordingAnimation()
        val controller = RecordingAnimationController(anim, pulseLayout = null, animationsEnabled = { true })

        controller.onState(stateWithRecording(activeRecording()))
        controller.onState(stateWithRecording(activeRecording()))  // same class
        controller.onState(stateWithRecording(activeRecording()))

        assertEquals(listOf("start"), anim.events)
    }

    @Test
    fun `preparing does not invoke the animator`() {
        val anim = FakeRecordingAnimation()
        val controller = RecordingAnimationController(anim, pulseLayout = null, animationsEnabled = { true })

        controller.onState(stateWithRecording(preparingRecording()))

        // No start / pause / cancel — Preparing is a deliberate no-op
        // (Spec 2 §11.5 — the recorder warm-up window).
        assertEquals(emptyList<String>(), anim.events)
    }

    @Test
    fun `animationsEnabled=false suppresses active start`() {
        val anim = FakeRecordingAnimation()
        val controller = RecordingAnimationController(anim, pulseLayout = null, animationsEnabled = { false })

        controller.onState(stateWithRecording(activeRecording()))

        // Skipping start is the user-pref behaviour; cancel/pause still
        // run on transitions so the visualizer doesn't get stuck on.
        assertEquals(emptyList<String>(), anim.events)
    }

    @Test
    fun `animationsEnabled=false still allows idle cancel`() {
        val anim = FakeRecordingAnimation()
        val controller = RecordingAnimationController(anim, pulseLayout = null, animationsEnabled = { false })

        // Even with animations off, the transition Active → Idle must
        // cancel (defensive — animations off may have flipped mid-
        // recording, leaving the visualizer running).
        controller.onState(stateWithRecording(activeRecording()))  // no-op
        controller.onState(stateWithRecording(RecordingState.Idle))

        assertEquals(listOf("cancel"), anim.events)
    }

    @Test
    fun `onAmplitude forwards to the animator`() {
        val anim = FakeRecordingAnimation()
        val controller = RecordingAnimationController(anim, pulseLayout = null, animationsEnabled = { true })

        controller.onAmplitude(0.5f)
        controller.onAmplitude(0.75f)

        assertEquals(listOf(0.5f, 0.75f), anim.amplitudes)
    }

    @Test
    fun `onTimerTick formats MM colon SS`() {
        val anim = FakeRecordingAnimation()
        val controller = RecordingAnimationController(anim, pulseLayout = null, animationsEnabled = { true })

        controller.onTimerTick(0L)
        controller.onTimerTick(8_000L)
        controller.onTimerTick(65_000L)

        assertEquals(listOf("00:00", "00:08", "01:05"), anim.timerTexts)
    }

    @Test
    fun `updateColor forwards to the animator`() {
        val anim = FakeRecordingAnimation()
        val controller = RecordingAnimationController(anim, pulseLayout = null, animationsEnabled = { true })

        controller.updateColor(0xFFAA0011.toInt())

        assertEquals(listOf(0xFFAA0011.toInt()), anim.colorUpdates)
    }

    @Test
    fun `reset re-arms first-apply after detach`() {
        val anim = FakeRecordingAnimation()
        val controller = RecordingAnimationController(anim, pulseLayout = null, animationsEnabled = { true })

        controller.onState(stateWithRecording(activeRecording()))  // start
        controller.reset()
        // After reset, the controller forgets the cached class — the
        // next Active state must re-fire start.
        controller.onState(stateWithRecording(activeRecording()))

        assertEquals(listOf("start", "start"), anim.events)
    }
}

// ─── Fixtures ──────────────────────────────────────────────────────────

private fun stateWithRecording(recording: RecordingState): DictateUiState =
    DictateUiState.initial().copy(recording = recording)

private fun activeRecording(): RecordingState.Active =
    RecordingState.Active(useBluetooth = false, audioFile = File("/tmp/test.m4a"), sessionId = "sid-test")

private fun pausedRecording(): RecordingState.Paused =
    RecordingState.Paused(useBluetooth = false, audioFile = File("/tmp/test.m4a"), sessionId = "sid-test")

private fun preparingRecording(): RecordingState.Preparing =
    RecordingState.Preparing(
        useBluetooth = false,
        audioFile = File("/tmp/test.m4a"), sessionId = "sid-test",
    )

private fun interruptedRecording(elapsedMs: Long): RecordingState.Interrupted =
    RecordingState.Interrupted(sessionId = "sid-test", elapsedMs = elapsedMs)

// ─── Hand-rolled fake animator (K-1) ───────────────────────────────────

/**
 * Records every method invocation against [RecordingAnimation] so tests
 * can assert on the lifecycle event sequence.
 */
internal class FakeRecordingAnimation : RecordingAnimation {
    val events: MutableList<String> = mutableListOf()
    val amplitudes: MutableList<Float> = mutableListOf()
    val timerTexts: MutableList<String> = mutableListOf()
    val colorUpdates: MutableList<Int> = mutableListOf()

    override fun prepare(target: View) {
        events += "prepare"
    }

    override fun start() {
        events += "start"
    }

    override fun pause() {
        events += "pause"
    }

    override fun resume() {
        events += "resume"
    }

    override fun cancel() {
        events += "cancel"
    }

    override fun onAmplitude(level: Float) {
        amplitudes += level
    }

    override fun onTimerTick(timerText: String) {
        timerTexts += timerText
    }

    override fun updateColor(color: Int) {
        colorUpdates += color
    }
}
