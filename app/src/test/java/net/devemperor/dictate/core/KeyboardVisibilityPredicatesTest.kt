@file:Suppress("DEPRECATION") // tests legacy four-arg isResendVisible / resolveResendVisibility (B4-VAL F-32).

package net.devemperor.dictate.core

import android.view.View
import net.devemperor.dictate.testutil.Quadruple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests for [isResendVisible] + [resolveResendVisibility].
 *
 * Block-1a Quick-Win (Spec 1 §11.2.2 step 1): the helper is the single
 * source of truth for resend-button visibility. The full 25-case matrix
 * (Spec 2 §14.2) lands in Block 4 when the predicate moves into the
 * LayoutCatalog. This file covers the four-axis truth-table relevant for
 * today's call sites.
 *
 * Quality-Gate references:
 *  - K-1: handwritten fakes only — no Mockito. The predicate is pure, no
 *    fakes needed.
 *  - K-4: JVM unit runner, no Robolectric / no Android Context. The
 *    [android.view.View.VISIBLE] / [android.view.View.GONE] constants are
 *    resolved by the Android stub on the unit-test classpath (returns the
 *    documented int values from `android.jar` mock).
 */
class KeyboardVisibilityPredicatesTest {

    // A representative audio file path. The predicate only sees a Boolean
    // for "exists", so the actual path content does not matter.
    private val dummyAudioFile = File("/tmp/dictate-predicate-test.m4a")

    // ────────────────────────────────────────────────────────────
    // isResendVisible — happy path (all four axes hold)
    // ────────────────────────────────────────────────────────────

    @Test
    fun `isResendVisible true when all four axes hold`() {
        assertTrue(
            isResendVisible(
                lastAudioFileExists = true,
                resendEnabled = true,
                recordingState = RecordingState.Idle,
                pipelineState = PipelineUiState.Idle
            )
        )
    }

    // ────────────────────────────────────────────────────────────
    // isResendVisible — each axis flipped individually
    // ────────────────────────────────────────────────────────────

    @Test
    fun `isResendVisible false when audio file missing`() {
        assertFalse(
            isResendVisible(
                lastAudioFileExists = false,
                resendEnabled = true,
                recordingState = RecordingState.Idle,
                pipelineState = PipelineUiState.Idle
            )
        )
    }

    @Test
    fun `isResendVisible false when Pref ResendButton disabled`() {
        assertFalse(
            isResendVisible(
                lastAudioFileExists = true,
                resendEnabled = false,
                recordingState = RecordingState.Idle,
                pipelineState = PipelineUiState.Idle
            )
        )
    }

    @Test
    fun `isResendVisible false when recording is Preparing`() {
        assertFalse(
            isResendVisible(
                lastAudioFileExists = true,
                resendEnabled = true,
                recordingState = RecordingState.Preparing(useBluetooth = false),
                pipelineState = PipelineUiState.Idle
            )
        )
    }

    @Test
    fun `isResendVisible false when recording is Active`() {
        assertFalse(
            isResendVisible(
                lastAudioFileExists = true,
                resendEnabled = true,
                recordingState = RecordingState.Active(useBluetooth = false),
                pipelineState = PipelineUiState.Idle
            )
        )
    }

    @Test
    fun `isResendVisible false when recording is Paused`() {
        assertFalse(
            isResendVisible(
                lastAudioFileExists = true,
                resendEnabled = true,
                recordingState = RecordingState.Paused,
                pipelineState = PipelineUiState.Idle
            )
        )
    }

    @Test
    fun `isResendVisible false when pipeline is Preparing`() {
        assertFalse(
            isResendVisible(
                lastAudioFileExists = true,
                resendEnabled = true,
                recordingState = RecordingState.Idle,
                pipelineState = PipelineUiState.Preparing
            )
        )
    }

    @Test
    fun `isResendVisible false when pipeline is Running`() {
        assertFalse(
            isResendVisible(
                lastAudioFileExists = true,
                resendEnabled = true,
                recordingState = RecordingState.Idle,
                pipelineState = PipelineUiState.Running(
                    totalSteps = 1,
                    completedSteps = 0,
                    currentStepName = "Transkription",
                    autoEnterActive = false
                )
            )
        )
    }

    @Test
    fun `isResendVisible false when pipeline is ReprocessStaging`() {
        assertFalse(
            isResendVisible(
                lastAudioFileExists = true,
                resendEnabled = true,
                recordingState = RecordingState.Idle,
                pipelineState = PipelineUiState.ReprocessStaging(
                    targetSessionId = "test-session",
                    audioDurationSeconds = 12,
                    editableQueue = emptyList(),
                    selectedLanguage = null
                )
            )
        )
    }

    // ────────────────────────────────────────────────────────────
    // isResendVisible — Bluetooth variant doesn't matter for Active
    // (guards against an accidental Bluetooth-only check)
    // ────────────────────────────────────────────────────────────

    @Test
    fun `isResendVisible false for both Bluetooth and non-Bluetooth Active`() {
        // Both useBluetooth=false (above) and useBluetooth=true must yield
        // false — the predicate cares only about the sealed subclass, not
        // its data.
        assertFalse(
            isResendVisible(
                lastAudioFileExists = true,
                resendEnabled = true,
                recordingState = RecordingState.Active(useBluetooth = true),
                pipelineState = PipelineUiState.Idle
            )
        )
        assertFalse(
            isResendVisible(
                lastAudioFileExists = true,
                resendEnabled = true,
                recordingState = RecordingState.Preparing(useBluetooth = true),
                pipelineState = PipelineUiState.Idle
            )
        )
    }

    // ────────────────────────────────────────────────────────────
    // isResendVisible — multi-axis conjunction
    // (each false axis is enough on its own)
    // ────────────────────────────────────────────────────────────

    @Test
    fun `isResendVisible false when two axes fail simultaneously`() {
        // Audio missing AND recording Active: still false (any-false → false).
        assertFalse(
            isResendVisible(
                lastAudioFileExists = false,
                resendEnabled = true,
                recordingState = RecordingState.Active(useBluetooth = false),
                pipelineState = PipelineUiState.Idle
            )
        )
    }

    @Test
    fun `isResendVisible false when all four axes fail`() {
        assertFalse(
            isResendVisible(
                lastAudioFileExists = false,
                resendEnabled = false,
                recordingState = RecordingState.Active(useBluetooth = false),
                pipelineState = PipelineUiState.Preparing
            )
        )
    }

    // ────────────────────────────────────────────────────────────
    // resolveResendVisibility — translation to View constants
    // ────────────────────────────────────────────────────────────

    @Test
    fun `resolveResendVisibility VISIBLE when predicate true`() {
        val result = resolveResendVisibility(
            lastAudioFileExists = true,
            resendEnabled = true,
            recordingState = RecordingState.Idle,
            pipelineState = PipelineUiState.Idle
        )
        assertEquals(View.VISIBLE, result)
    }

    @Test
    fun `resolveResendVisibility GONE when predicate false (audio missing)`() {
        val result = resolveResendVisibility(
            lastAudioFileExists = false,
            resendEnabled = true,
            recordingState = RecordingState.Idle,
            pipelineState = PipelineUiState.Idle
        )
        assertEquals(View.GONE, result)
    }

    @Test
    fun `resolveResendVisibility GONE when recording is Active`() {
        val result = resolveResendVisibility(
            lastAudioFileExists = true,
            resendEnabled = true,
            recordingState = RecordingState.Active(useBluetooth = false),
            pipelineState = PipelineUiState.Idle
        )
        assertEquals(View.GONE, result)
    }

    @Test
    fun `resolveResendVisibility never returns VISIBLE for any non-Idle pipeline state`() {
        val nonIdlePipelineStates: List<PipelineUiState> = listOf(
            PipelineUiState.Preparing,
            PipelineUiState.Running(
                totalSteps = 2,
                completedSteps = 1,
                currentStepName = "Formatierung",
                autoEnterActive = false
            ),
            PipelineUiState.ReprocessStaging(
                targetSessionId = "s",
                audioDurationSeconds = 0,
                editableQueue = emptyList(),
                selectedLanguage = null
            ),
        )
        for (pipelineState in nonIdlePipelineStates) {
            val result = resolveResendVisibility(
                lastAudioFileExists = true,
                resendEnabled = true,
                recordingState = RecordingState.Idle,
                pipelineState = pipelineState
            )
            assertEquals(
                "expected GONE for pipelineState=$pipelineState",
                View.GONE, result
            )
        }
    }

    // ────────────────────────────────────────────────────────────
    // resolveResendVisibility — consistency with isResendVisible
    // (guard against accidental drift between the two)
    // ────────────────────────────────────────────────────────────

    @Test
    fun `resolveResendVisibility VISIBLE iff isResendVisible true (sample axes)`() {
        // Spot-check a handful of axis combinations to make sure the wrapper
        // does not invent its own answer — VISIBLE ↔ true, GONE ↔ false.
        val cases: List<Pair<Boolean, Quadruple<Boolean, Boolean, RecordingState, PipelineUiState>>> =
            listOf(
                true to Quadruple(true, true, RecordingState.Idle, PipelineUiState.Idle),
                false to Quadruple(false, true, RecordingState.Idle, PipelineUiState.Idle),
                false to Quadruple(true, false, RecordingState.Idle, PipelineUiState.Idle),
                false to Quadruple(
                    true, true,
                    RecordingState.Active(useBluetooth = false),
                    PipelineUiState.Idle
                ),
                false to Quadruple(
                    true, true,
                    RecordingState.Idle,
                    PipelineUiState.Running(
                        totalSteps = 1,
                        completedSteps = 0,
                        currentStepName = "",
                        autoEnterActive = false
                    )
                ),
            )
        for ((expectedVisible, axes) in cases) {
            val (lastAudio, resendEnabled, recState, pipeState) = axes
            val pred = isResendVisible(lastAudio, resendEnabled, recState, pipeState)
            val resolved = resolveResendVisibility(lastAudio, resendEnabled, recState, pipeState)
            assertEquals("predicate disagrees with expected for $axes", expectedVisible, pred)
            assertEquals(
                "resolveResendVisibility out of sync with isResendVisible for $axes",
                if (pred) View.VISIBLE else View.GONE, resolved
            )
        }
    }

}
