package net.devemperor.dictate.core

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NotificationPermissionPolicy] (F-092).
 *
 * The policy encodes the *whether-to-ask* rule for the runtime
 * `POST_NOTIFICATIONS` prompt over the three inputs (API level ×
 * granted × rationale). These are pure-function tests — no Robolectric
 * / Context needed.
 */
class NotificationPermissionPolicyTest {

    private val tiramisu = Build.VERSION_CODES.TIRAMISU // 33
    private val preTiramisu = Build.VERSION_CODES.S_V2 // 32
    private val postTiramisu = Build.VERSION_CODES.UPSIDE_DOWN_CAKE // 34

    // --- isRuntimePermissionRelevant (API-level gate) ---

    @Test
    fun relevant_falseBelow33() {
        assertFalse(NotificationPermissionPolicy.isRuntimePermissionRelevant(preTiramisu))
        assertFalse(NotificationPermissionPolicy.isRuntimePermissionRelevant(Build.VERSION_CODES.O))
    }

    @Test
    fun relevant_trueFrom33() {
        assertTrue(NotificationPermissionPolicy.isRuntimePermissionRelevant(tiramisu))
        assertTrue(NotificationPermissionPolicy.isRuntimePermissionRelevant(postTiramisu))
    }

    // --- shouldRequestOnboarding (first-run: API 33+ & not granted) ---

    @Test
    fun onboarding_asksWhenApi33AndNotGranted() {
        assertTrue(NotificationPermissionPolicy.shouldRequestOnboarding(tiramisu, granted = false))
        assertTrue(NotificationPermissionPolicy.shouldRequestOnboarding(postTiramisu, granted = false))
    }

    @Test
    fun onboarding_doesNotAskWhenAlreadyGranted() {
        assertFalse(NotificationPermissionPolicy.shouldRequestOnboarding(tiramisu, granted = true))
    }

    @Test
    fun onboarding_doesNotAskBelow33() {
        // Pre-33 the permission is implicit — never ask, regardless of
        // the granted flag the caller passes.
        assertFalse(NotificationPermissionPolicy.shouldRequestOnboarding(preTiramisu, granted = false))
        assertFalse(NotificationPermissionPolicy.shouldRequestOnboarding(preTiramisu, granted = true))
    }

    // --- shouldRequestFromSettings (fallback: + shouldShowRationale) ---

    @Test
    fun settings_asksWhenDeniedOnceButDialogStillAllowed() {
        assertTrue(
            NotificationPermissionPolicy.shouldRequestFromSettings(
                apiLevel = tiramisu,
                granted = false,
                shouldShowRationale = true,
            ),
        )
    }

    @Test
    fun settings_doesNotAskWhenGranted() {
        assertFalse(
            NotificationPermissionPolicy.shouldRequestFromSettings(
                apiLevel = tiramisu,
                granted = true,
                shouldShowRationale = true,
            ),
        )
    }

    @Test
    fun settings_doesNotNagAfterPermanentDenial() {
        // Permanent denial: not granted AND system will no longer show a
        // dialog (shouldShowRationale=false). The fallback must stay
        // silent — no loop-nag.
        assertFalse(
            NotificationPermissionPolicy.shouldRequestFromSettings(
                apiLevel = tiramisu,
                granted = false,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun settings_doesNotAskOnFreshInstall() {
        // Fresh install (never asked): shouldShowRationale is false, so
        // the settings fallback stays quiet — onboarding owns the first
        // ask.
        assertFalse(
            NotificationPermissionPolicy.shouldRequestFromSettings(
                apiLevel = tiramisu,
                granted = false,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun settings_doesNotAskBelow33() {
        assertFalse(
            NotificationPermissionPolicy.shouldRequestFromSettings(
                apiLevel = preTiramisu,
                granted = false,
                shouldShowRationale = true,
            ),
        )
    }
}
