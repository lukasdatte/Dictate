package net.devemperor.dictate.core

import net.devemperor.dictate.state.AudioFocusSubsystem

/**
 * Production [AudioFocusSubsystem] backed by the existing
 * [AudioFocusGate].
 *
 * **C8 — subsystem-adapter migration (Spec 1 §9.6):**
 * [AudioFocusGate] / [RealAudioFocusGate] are the unit-test-friendly
 * Android-AudioFocus seam (interface + Android-backed impl). This adapter
 * lifts a [AudioFocusGate] into the orchestrator's
 * [AudioFocusSubsystem] interface so [AudioModule.runEffect]'s
 * `RequestAudioFocus` / `ReleaseAudioFocus` map cleanly to the
 * underlying [android.media.AudioManager] calls.
 *
 * The double layering (`AudioFocusSubsystem` → `AudioFocusGate` → real
 * `AudioManager`) keeps the orchestrator-side contract pure-Kotlin
 * (no Android dependency in `state/`) while reusing the existing tested
 * gate. Unit tests for [AudioModule] inject a
 * [net.devemperor.dictate.state.AudioFocusSubsystem] fake directly;
 * tests for this adapter inject a
 * [net.devemperor.dictate.core.FakeAudioFocusGate].
 *
 * @see net.devemperor.dictate.state.AudioFocusSubsystem
 * @see net.devemperor.dictate.core.AudioFocusGate
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §9.6
 */
class AudioFocusSubsystemAdapter(
    private val gate: AudioFocusGate,
) : AudioFocusSubsystem {

    override fun request() {
        gate.request()
    }

    override fun release() {
        gate.abandon()
    }
}
