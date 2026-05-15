package net.devemperor.dictate.onboarding

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.core.DictatePipelineService
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Unit tests for [OverlayPermissionOnboardingActivity] (Spec 3 §5.2).
 *
 * # Coverage focus
 *
 *  - View inflation — title / explainer / grant / dismiss / status
 *    Views resolve.
 *  - Grant button → starts `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`
 *    intent targeting this package; flips
 *    `Pref.OverlayOnboardingShown` to true so the in-IME flow stays
 *    in sync.
 *  - Dismiss button → finishes the activity; does **not** touch the
 *    permanently-denied pref (in-IME info-bar owns that lifecycle).
 *  - Status text reflects the current permission state on `onResume`.
 *
 * The Activity is intentionally thin — most of the heavy lifting lives
 * in `OverlayModule` + the in-IME info-bar flow (C18). This test
 * verifies the Activity surface contract: explainer + grant + dismiss
 * + status, no more.
 *
 * @see OverlayPermissionOnboardingActivity
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayPermissionOnboardingActivityTest {

    @Test
    fun `inflates the explainer + grant + dismiss + status Views`() {
        val controller = Robolectric.buildActivity(OverlayPermissionOnboardingActivity::class.java)
            .create()
            .start()
            .resume()
        val activity = controller.get()

        assertNotNull(activity.findViewById<TextView>(R.id.overlay_perm_onboarding_title_tv))
        assertNotNull(activity.findViewById<TextView>(R.id.overlay_perm_onboarding_explainer_tv))
        assertNotNull(activity.findViewById<MaterialButton>(R.id.overlay_perm_onboarding_grant_btn))
        assertNotNull(activity.findViewById<MaterialButton>(R.id.overlay_perm_onboarding_dismiss_btn))
        assertNotNull(activity.findViewById<TextView>(R.id.overlay_perm_onboarding_status_tv))
    }

    @Test
    fun `grant button launches Settings ACTION_MANAGE_OVERLAY_PERMISSION intent`() {
        val controller = Robolectric.buildActivity(OverlayPermissionOnboardingActivity::class.java)
            .create()
            .start()
            .resume()
        val activity = controller.get()

        activity.findViewById<MaterialButton>(R.id.overlay_perm_onboarding_grant_btn)
            .performClick()

        val started = Shadows.shadowOf(activity).nextStartedActivity
        assertNotNull("grant click must startActivity(Settings intent)", started)
        assertEquals(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            started.action,
        )
        // The intent's data must target this package so the Settings
        // screen lands on the right app entry directly.
        assertNotNull("intent must carry package: data Uri", started.data)
        assertTrue(
            "Uri must reference this app's package",
            started.dataString!!.contains(activity.packageName),
        )
        // FLAG_ACTIVITY_NEW_TASK is set defensively in case the launch
        // ever happens from a non-Activity context (Spec 3 §5.2).
        assertTrue(
            "FLAG_ACTIVITY_NEW_TASK must be set",
            started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }

    @Test
    fun `grant button flips Pref_OverlayOnboardingShown to true`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val prefs = ctx.getSharedPreferences(
            DictatePipelineService.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        prefs.edit().clear().apply()

        val controller = Robolectric.buildActivity(OverlayPermissionOnboardingActivity::class.java)
            .create()
            .start()
            .resume()
        controller.get().findViewById<MaterialButton>(R.id.overlay_perm_onboarding_grant_btn)
            .performClick()

        assertTrue(
            "grant click must mark the onboarding rationale as shown so the in-IME flow stays consistent",
            prefs.get(Pref.OverlayOnboardingShown),
        )
    }

    @Test
    fun `grant button does NOT touch the permanently-denied pref (SRP)`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val prefs = ctx.getSharedPreferences(
            DictatePipelineService.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        prefs.edit().clear().apply()

        val controller = Robolectric.buildActivity(OverlayPermissionOnboardingActivity::class.java)
            .create()
            .start()
            .resume()
        controller.get().findViewById<MaterialButton>(R.id.overlay_perm_onboarding_grant_btn)
            .performClick()

        assertEquals(
            "grant click must not affect the permanently-denied pref",
            false,
            prefs.get(Pref.OverlayOnboardingDismissed),
        )
    }

    @Test
    fun `dismiss button finishes the activity`() {
        val controller = Robolectric.buildActivity(OverlayPermissionOnboardingActivity::class.java)
            .create()
            .start()
            .resume()
        val activity = controller.get()

        activity.findViewById<MaterialButton>(R.id.overlay_perm_onboarding_dismiss_btn)
            .performClick()

        assertTrue(
            "Dismiss button must call finish() — Activity dismissal does not persist 'never show again'",
            activity.isFinishing,
        )
    }

    @Test
    fun `dismiss button does NOT mark the permanently-denied pref (in-IME flow owns it)`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val prefs = ctx.getSharedPreferences(
            DictatePipelineService.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        prefs.edit().clear().apply()

        val controller = Robolectric.buildActivity(OverlayPermissionOnboardingActivity::class.java)
            .create()
            .start()
            .resume()
        controller.get().findViewById<MaterialButton>(R.id.overlay_perm_onboarding_dismiss_btn)
            .performClick()

        assertEquals(
            "Activity dismissal must not write Pref.OverlayOnboardingDismissed " +
                "— that flag is the in-IME info-bar's 'Later' button's responsibility.",
            false,
            prefs.get(Pref.OverlayOnboardingDismissed),
        )
    }

    @Test
    fun `status text reflects the permission state on resume`() {
        val controller = Robolectric.buildActivity(OverlayPermissionOnboardingActivity::class.java)
            .create()
            .start()
            .resume()
        val activity = controller.get()
        val statusTv = activity.findViewById<TextView>(R.id.overlay_perm_onboarding_status_tv)

        // Robolectric default: no overlay permission ⇒ "pending" copy.
        assertEquals(
            activity.getString(R.string.overlay_perm_onboarding_pending),
            statusTv.text.toString(),
        )
    }
}
