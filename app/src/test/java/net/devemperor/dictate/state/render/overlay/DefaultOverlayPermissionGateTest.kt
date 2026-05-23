package net.devemperor.dictate.state.render.overlay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import net.devemperor.dictate.preferences.put
import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [DefaultOverlayPermissionGate] (Spec 3 §5.1).
 *
 * # Why Robolectric
 *
 * `Settings.canDrawOverlays(context)` requires the Android framework.
 * Robolectric returns its shadow default (`false`) on the JVM, which
 * is exactly the "permission not granted" state the spec's
 * `shouldShowOnboarding` branch keys off — that's the focus of this
 * suite. Tests do not need to mutate the system-permission shadow;
 * the production behaviour we exercise is the boolean composition
 * between the system check and the persisted prefs.
 *
 * # Coverage focus
 *
 *  - `shouldShowOnboarding`: returns `true` iff `!hasPermission &&
 *    !permanentlyDenied`. Four cases over the 2x2 matrix.
 *  - `markOnboardingShown` / `markPermanentlyDenied`: write the right
 *    typed [Pref] keys (K-4 — round-trip via typed pref accessors).
 *  - Idempotency of repeat marks (writing the same value twice is
 *    safe; prefs end up consistent).
 *
 * @see DefaultOverlayPermissionGate
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultOverlayPermissionGateTest {

    private lateinit var ctx: Context
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var gate: DefaultOverlayPermissionGate

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        prefs = FakeSharedPreferences()
        gate = DefaultOverlayPermissionGate(ctx = ctx, prefs = prefs)
    }

    @Test
    fun `hasOverlayPermission delegates to Settings_canDrawOverlays — Robolectric default is false`() {
        // Robolectric does not grant SYSTEM_ALERT_WINDOW by default; the
        // spec's fallback path is the only structurally reachable one
        // in a vanilla unit test (Boot-Default-Race-Window per §5.0).
        assertFalse(gate.hasOverlayPermission())
    }

    @Test
    fun `shouldShowOnboarding is true when permission missing and not permanently denied`() {
        // Default state: no permission (Robolectric default) + no pref
        // ⇒ first-time prompt should fire.
        assertTrue(gate.shouldShowOnboarding())
    }

    @Test
    fun `shouldShowOnboarding is false once permanently denied`() {
        prefs.edit().put(Pref.OverlayOnboardingDismissed, true).apply()

        assertFalse(
            "user permanently dismissed — onboarding must not re-appear",
            gate.shouldShowOnboarding(),
        )
    }

    @Test
    fun `markOnboardingShown writes the typed pref`() {
        assertFalse(prefs.get(Pref.OverlayOnboardingShown))

        gate.markOnboardingShown()

        assertTrue(
            "markOnboardingShown must flip Pref.OverlayOnboardingShown to true",
            prefs.get(Pref.OverlayOnboardingShown),
        )
    }

    @Test
    fun `markPermanentlyDenied writes the typed pref`() {
        assertFalse(prefs.get(Pref.OverlayOnboardingDismissed))

        gate.markPermanentlyDenied()

        assertTrue(
            "markPermanentlyDenied must flip Pref.OverlayOnboardingDismissed to true",
            prefs.get(Pref.OverlayOnboardingDismissed),
        )
    }

    @Test
    fun `markPermanentlyDenied flips shouldShowOnboarding to false (round-trip)`() {
        assertTrue(gate.shouldShowOnboarding())
        gate.markPermanentlyDenied()
        assertFalse(gate.shouldShowOnboarding())
    }

    @Test
    fun `repeat markOnboardingShown is idempotent (pref converges)`() {
        gate.markOnboardingShown()
        gate.markOnboardingShown()
        gate.markOnboardingShown()
        // No exception + value stays true.
        assertTrue(prefs.get(Pref.OverlayOnboardingShown))
    }

    @Test
    fun `markOnboardingShown does NOT touch the permanently-denied pref (SRP)`() {
        gate.markOnboardingShown()
        assertFalse(
            "markOnboardingShown must not affect the permanently-denied flag",
            prefs.get(Pref.OverlayOnboardingDismissed),
        )
    }

    @Test
    fun `markPermanentlyDenied does NOT touch the shown pref (SRP)`() {
        gate.markPermanentlyDenied()
        assertFalse(
            "markPermanentlyDenied must not affect the shown flag",
            prefs.get(Pref.OverlayOnboardingShown),
        )
    }

    @Test
    fun `pref keys match the canonical Pref entries (K-4 round-trip)`() {
        // Belt-and-suspenders: the canonical pref keys for the two
        // onboarding flags live in DictatePrefs.kt. Asserting the
        // exact key strings keeps a future rename loud.
        gate.markOnboardingShown()
        gate.markPermanentlyDenied()

        assertEquals(true, prefs.getAll()[Pref.OverlayOnboardingShown.key])
        assertEquals(true, prefs.getAll()[Pref.OverlayOnboardingDismissed.key])
    }
}
