package net.devemperor.dictate.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [decideStartDictationLaunch] — the pure precondition gate
 * `StartDictationActivity` evaluates before firing the
 * `ACTION_START_DICTATION` service intent.
 *
 * Ordering rule under test: the microphone permission outranks the
 * overlay permission. Without RECORD_AUDIO no dictation is possible at
 * all, and `DictateSettingsActivity` auto-requests it on open — so the
 * mic route also happens to be the best first stop when both
 * permissions are missing.
 */
class StartDictationLaunchPolicyTest {

    @Test
    fun `all permissions granted starts the pipeline service`() {
        assertEquals(
            StartDictationLaunchDecision.StartPipelineService,
            decideStartDictationLaunch(hasMicPermission = true, hasOverlayPermission = true),
        )
    }

    @Test
    fun `missing mic permission routes to settings`() {
        assertEquals(
            StartDictationLaunchDecision.OpenMicPermissionSettings,
            decideStartDictationLaunch(hasMicPermission = false, hasOverlayPermission = true),
        )
    }

    @Test
    fun `missing overlay permission routes to overlay onboarding`() {
        assertEquals(
            StartDictationLaunchDecision.OpenOverlayPermissionOnboarding,
            decideStartDictationLaunch(hasMicPermission = true, hasOverlayPermission = false),
        )
    }

    @Test
    fun `both permissions missing routes to settings first (mic outranks overlay)`() {
        assertEquals(
            StartDictationLaunchDecision.OpenMicPermissionSettings,
            decideStartDictationLaunch(hasMicPermission = false, hasOverlayPermission = false),
        )
    }
}
