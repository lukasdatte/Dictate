package net.devemperor.dictate.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.put
import net.devemperor.dictate.testutil.FakePipelineSessionRepo
import net.devemperor.dictate.testutil.FakeSharedPreferences
import net.devemperor.dictate.testutil.fakeModuleServices
import net.devemperor.dictate.testutil.testPipelineRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Integration test for **review-fix G3 (2026-05-21)** — verifies that
 * an external [android.content.SharedPreferences] write (the
 * Settings-Activity path) flows through [PipelinePrefMirror] → the
 * orchestrator's [DictateOrchestrator.runMirrorSync] cascade-engine →
 * the [AudioModule.onCrossModuleStateChange] arm that emits
 * [Action.AudioAction.ApplyAudioFocusRuntimeFromPref].
 *
 * **The bug this locks against.** Before G3, the mirror called
 * `store.update` directly, which bypassed
 * `DictateOrchestrator.dispatchInternal` Step 5 (cross-module
 * observation). An external Settings-Activity SP write would mirror
 * into `audio.audioFocusEnabledPref` but never reach the cascade arm
 * that dispatches `ApplyAudioFocusRuntimeFromPref` → the live
 * [net.devemperor.dictate.state.AudioFocusSubsystem] (`AudioManager`)
 * would stay stale during an Active recording. This is the exact R-5
 * regression described in indirection-cleanup plan §6.1 and the
 * scenario whose live-hook D-1 cascade was unreachable.
 *
 * **Why this test exists at the integration layer.** [AudioModuleTest]
 * already covers the observer arm in isolation
 * (`cross-module pref change during Active recording cascades
 * ApplyAudioFocusRuntimeFromPref`). The arm was always correct;
 * what was broken was the path that **invokes** it from an SP write.
 * This test asserts the wiring end-to-end: SP-write → effect
 * actually called on the [net.devemperor.dictate.state.AudioFocusSubsystem].
 *
 * @see net.devemperor.dictate.state.PipelinePrefMirror.sync
 * @see net.devemperor.dictate.state.DictateOrchestrator.runMirrorSync
 * @see net.devemperor.dictate.state.AudioModule.onCrossModuleStateChange
 */
class PipelinePrefMirrorCascadeTest {

    /** Counts AudioFocus `request()` / `release()` invocations. */
    private class CountingAudioFocus : AudioFocusSubsystem {
        var requests: Int = 0
        var releases: Int = 0
        override fun request() {
            requests++
        }
        override fun release() {
            releases++
        }
    }

    @Test
    fun `external SP toggle during Active recording triggers AudioFocus release via cascade`() {
        // 1. SP starts with AudioFocus=true (default).
        val sp = FakeSharedPreferences()
        // Seed the value explicitly so the listener has a "from true → false"
        // transition to mirror (the mirror only fires on actual SP-listener
        // events, which require a value change).
        sp.edit().put(Pref.AudioFocus, true).apply()

        val store = DictateUiStateStore(DictateUiState.initial())
        val audioFocus = CountingAudioFocus()
        val services = fakeModuleServices(sharedPrefs = sp, audioFocus = audioFocus)

        val orchestrator = DictateOrchestrator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            store = store,
            services = services,
            // AudioModule is required so its `onCrossModuleStateChange`
            // arm is in the cascade pass.
            registry = DictateModuleRegistry(listOf(AudioModule)),
            prefMirror = PipelinePrefMirror(sp),
            recovery = testPipelineRecovery(FakePipelineSessionRepo()),
        )

        // 2. Simulate the IME having an Active recording (no real
        //    StartRecording dispatch is needed; we put the state there
        //    directly — what we're testing is the SP→Cascade path,
        //    not the recording-start path). `audioFocusGranted = true`
        //    represents the live AudioManager already holding focus.
        store.update {
            it.copy(
                recording = RecordingState.Active(
                    useBluetooth = false,
                    audioFile = File("/tmp/test.m4a"),
                    sessionId = "sid-cascade-test",
                ),
                audio = it.audio.copy(audioFocusGranted = true),
            )
        }

        // Counter sanity — no focus calls yet from the test setup.
        assertEquals(0, audioFocus.releases)

        // 3. External Settings-Activity write: AudioFocus toggled OFF.
        //    Listener fires → Mirror.sync → orchestrator.runMirrorSync
        //    → cascade observer in AudioModule → dispatch
        //    ApplyAudioFocusRuntimeFromPref(enabled=false) → reducer
        //    emits ApplyAudioFocusRuntime(false) → runEffect →
        //    services.audioFocus.release().
        sp.edit().put(Pref.AudioFocus, false).apply()

        // 4. State mirrored.
        assertEquals(false, store.snapshot.audio.audioFocusEnabledPref)
        // 5. **The G3 assertion**: the live AudioManager was told to
        //    release focus.
        assertEquals(
            "External SP toggle during Active recording must trigger AudioFocus.release()",
            1,
            audioFocus.releases,
        )

        // Cleanup
        orchestrator.shutdown()
    }

    @Test
    fun `external SP toggle while Idle does NOT touch live AudioFocus`() {
        // Idempotency-gate parity: mirror still updates the state axis,
        // but the cascade arm only emits when recording is Active. This
        // locks the dual case of the cascade test above.
        val sp = FakeSharedPreferences()
        sp.edit().put(Pref.AudioFocus, true).apply()

        val store = DictateUiStateStore(DictateUiState.initial())
        val audioFocus = CountingAudioFocus()
        val services = fakeModuleServices(sharedPrefs = sp, audioFocus = audioFocus)

        DictateOrchestrator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            store = store,
            services = services,
            registry = DictateModuleRegistry(listOf(AudioModule)),
            prefMirror = PipelinePrefMirror(sp),
            recovery = testPipelineRecovery(FakePipelineSessionRepo()),
        )

        // No recording → Idle. Toggle the pref via Settings-Activity-like SP write.
        sp.edit().put(Pref.AudioFocus, false).apply()

        // State mirrored.
        assertEquals(false, store.snapshot.audio.audioFocusEnabledPref)
        // No live AudioManager call — the cascade arm gated on Active.
        assertEquals(0, audioFocus.requests)
        assertEquals(0, audioFocus.releases)
    }

    @Test
    fun `in-IME ToggleAudioFocusPref still drives AudioFocus release (no regression)`() {
        // Defensive against G3-induced regression on the in-IME path. The
        // click path produces an inline `ApplyAudioFocusRuntime` effect
        // (reducer arm `ToggleAudioFocusPref`). After the state mutation,
        // the post-reducer cross-module cascade *also* observes
        // `prev.audio.audioFocusEnabledPref != next.audio.audioFocusEnabledPref`
        // during Active recording and emits `ApplyAudioFocusRuntimeFromPref`
        // — but its reducer gates on `action.enabled != state.audioFocusGranted`.
        // In a real AudioManager handshake the `OnAudioFocusGrantChanged`
        // callback would have updated `audioFocusGranted` between the two
        // arms; in the test fake it hasn't, so we may see 1 or 2 release
        // calls — both correct per the AudioModule §"in-IME path is not
        // a duplicate caller" KDoc (`request()` / `release()` are
        // idempotent at the AudioManager layer).
        //
        // The G3-protected assertion is: at least one release MUST happen
        // (the legacy click path must still work end-to-end after the
        // mirror was switched to the orchestrator-backed dispatcher).
        val sp = FakeSharedPreferences()
        sp.edit().put(Pref.AudioFocus, true).apply()

        val store = DictateUiStateStore(DictateUiState.initial())
        val audioFocus = CountingAudioFocus()
        val services = fakeModuleServices(sharedPrefs = sp, audioFocus = audioFocus)

        val orchestrator = DictateOrchestrator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            store = store,
            services = services,
            registry = DictateModuleRegistry(listOf(AudioModule)),
            prefMirror = PipelinePrefMirror(sp),
            recovery = testPipelineRecovery(FakePipelineSessionRepo()),
        )

        store.update {
            it.copy(
                recording = RecordingState.Active(
                    useBluetooth = false,
                    audioFile = File("/tmp/test.m4a"),
                    sessionId = "sid-in-ime",
                ),
                audio = it.audio.copy(audioFocusGranted = true),
            )
        }

        val outcome = orchestrator.dispatch(Action.AudioAction.ToggleAudioFocusPref)
        assertTrue("expected Applied, got $outcome", outcome is DispatchOutcome.Applied)

        assertTrue(
            "In-IME click path must produce at least one release()" +
                " (got ${audioFocus.releases})",
            audioFocus.releases >= 1,
        )
        // Defensive against runaway cascade.
        assertTrue(
            "In-IME click path must not unbounded-cascade releases" +
                " (got ${audioFocus.releases})",
            audioFocus.releases <= 2,
        )

        orchestrator.shutdown()
    }
}
