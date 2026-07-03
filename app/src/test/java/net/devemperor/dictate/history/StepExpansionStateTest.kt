package net.devemperor.dictate.history

import android.os.Bundle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [StepExpansionState] (R4/R5 expand/collapse owner, spec §3.3).
 *
 * The toggle / isExpanded logic is pure; the snapshot round-trip uses a real
 * [Bundle], so the suite runs under Robolectric (K-4 justified: exercising the
 * actual `putStringArrayList` / `getStringArrayList` contract rather than a
 * hand-rolled fake).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StepExpansionStateTest {

    @Test
    fun `starts collapsed`() {
        val state = StepExpansionState()
        assertFalse(state.isExpanded("transcription"))
    }

    @Test
    fun `toggle flips expanded and returns the new flag`() {
        val state = StepExpansionState()
        assertTrue(state.toggle("transcription"))
        assertTrue(state.isExpanded("transcription"))
        assertFalse(state.toggle("transcription"))
        assertFalse(state.isExpanded("transcription"))
    }

    @Test
    fun `toggle keys are independent`() {
        val state = StepExpansionState()
        state.toggle("processing:1")
        assertTrue(state.isExpanded("processing:1"))
        assertFalse(state.isExpanded("processing:2"))
    }

    @Test
    fun `snapshot-restore round-trip preserves the expanded set`() {
        val original = StepExpansionState()
        original.toggle("transcription")
        original.toggle("processing:2")

        val bundle = Bundle()
        original.saveTo(bundle)

        val restored = StepExpansionState()
        restored.restoreFrom(bundle)

        assertTrue(restored.isExpanded("transcription"))
        assertTrue(restored.isExpanded("processing:2"))
        assertFalse(restored.isExpanded("final"))
    }

    @Test
    fun `restore from a null bundle is a no-op`() {
        val state = StepExpansionState()
        state.toggle("transcription")
        state.restoreFrom(null)
        // Pre-existing state is untouched when there is nothing to restore.
        assertTrue(state.isExpanded("transcription"))
    }

    @Test
    fun `restore replaces any prior expanded set`() {
        val bundle = Bundle()
        StepExpansionState().apply { toggle("audio") }.saveTo(bundle)

        val state = StepExpansionState()
        state.toggle("transcription")
        state.restoreFrom(bundle)

        // The restored set wins; the pre-restore key is dropped.
        assertTrue(state.isExpanded("audio"))
        assertFalse(state.isExpanded("transcription"))
    }
}
