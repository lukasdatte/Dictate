package net.devemperor.dictate.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Decision-table tests for [decidePcDictationLaunch] (pc-dictation-activity).
 *
 * Mic outranks pairing (settings auto-requests the blocking permission first); pairing is required
 * because every output in the Activity goes to the paired PC.
 */
class PcDictationLaunchPolicyTest {

    @Test
    fun `all preconditions met opens the activity`() {
        assertEquals(
            PcDictationLaunchDecision.OpenPcDictation,
            decidePcDictationLaunch(hasMicPermission = true, isPaired = true),
        )
    }

    @Test
    fun `missing mic outranks a missing pairing`() {
        assertEquals(
            PcDictationLaunchDecision.OpenMicPermissionSettings,
            decidePcDictationLaunch(hasMicPermission = false, isPaired = false),
        )
        assertEquals(
            PcDictationLaunchDecision.OpenMicPermissionSettings,
            decidePcDictationLaunch(hasMicPermission = false, isPaired = true),
        )
    }

    @Test
    fun `mic granted but unpaired opens pairing`() {
        assertEquals(
            PcDictationLaunchDecision.OpenPairing,
            decidePcDictationLaunch(hasMicPermission = true, isPaired = false),
        )
    }
}
