package net.devemperor.dictate.onboarding

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.core.DictatePipelineService
import net.devemperor.dictate.state.render.overlay.DefaultOverlayPermissionGate

/**
 * Standalone onboarding screen for the floating-overlay
 * (`SYSTEM_ALERT_WINDOW`) permission (Spec 3 §5.2 + §5.3).
 *
 * # Why a standalone Activity
 *
 * The primary onboarding surface is the in-IME info-bar (Spec 3
 * §5.3) which appears above the keyboard when the user taps the
 * disabled WIDGET-toggle. The IME-View is the most likely place for
 * the user to encounter the missing permission, so the in-keyboard
 * info-bar is the high-traffic path.
 *
 * This Activity covers the **secondary entry points**:
 *
 *  - Deep-link from a future Settings shortcut.
 *  - Hand-off from a notification action that needs the overlay
 *    permission before proceeding.
 *  - Manual launch via `am start` for testing.
 *
 * Activities cannot be opened from a Service without
 * `FLAG_ACTIVITY_NEW_TASK`; that's why Spec 3 §5.2 prefers the direct
 * Settings-intent launch from the IME-View. This Activity wraps the
 * same intent with an explainer + grant button — the grant button
 * triggers exactly the same intent as the in-IME info-bar.
 *
 * # Side-effects on the OverlayModule state axis
 *
 * The Activity is **not** wired to the orchestrator; mutating
 * `state.overlay.onboardingPending` is the in-IME flow's job. This
 * Activity is "fire and forget" — it just opens System Settings and
 * lets the [net.devemperor.dictate.state.render.overlay.OverlayPermissionObserver]
 * on the Pipeline-Service side catch the result on the next IME
 * lifecycle event. Pressing "Later" only finishes the Activity; the
 * permanently-denied bit lives in the in-IME flow (Spec 3 §5.4).
 *
 * # Acceptance flow
 *
 *  1. User opens this Activity.
 *  2. UI shows the explainer + Allow + Later buttons + a status
 *     line that mirrors the live permission state.
 *  3. Tapping Allow → `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`
 *     intent for this package.
 *  4. User toggles + comes back; `onResume` re-reads the status; if
 *     granted, the status line says so and the user can tap back.
 *  5. Tapping Later → `finish()` (no persisted dismissal — the
 *     in-IME flow owns the permanently-denied lifecycle).
 *
 * @see DefaultOverlayPermissionGate
 * @see net.devemperor.dictate.state.render.overlay.OverlayPermissionObserver
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md §5.2
 */
class OverlayPermissionOnboardingActivity : AppCompatActivity() {

    private lateinit var gate: DefaultOverlayPermissionGate
    private lateinit var statusTv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overlay_permission_onboarding)

        gate = DefaultOverlayPermissionGate(
            ctx = this,
            // Re-use the canonical app-wide SharedPreferences file
            // name. `DictatePipelineService.PREFS_NAME` is a `const
            // val` so the import is a build-time constant; no runtime
            // dependency on the Service being alive.
            prefs = getSharedPreferences(
                DictatePipelineService.PREFS_NAME,
                MODE_PRIVATE,
            ),
        )

        statusTv = findViewById(R.id.overlay_perm_onboarding_status_tv)

        findViewById<MaterialButton>(R.id.overlay_perm_onboarding_grant_btn)
            .setOnClickListener { launchSettings() }

        findViewById<MaterialButton>(R.id.overlay_perm_onboarding_dismiss_btn)
            .setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        // Refresh the status line so a user that just returned from
        // System Settings sees the granted state. The in-IME observer
        // updates `state.overlay.hasPermission` independently when the
        // IME view becomes visible again — the Activity's status text
        // is a UI-affordance for the Activity surface only.
        refreshStatus()
    }

    /**
     * Internal hook — package-visible for the Robolectric test so it
     * can assert the explicit-intent target without scraping the
     * `startActivity` stack manually if needed.
     */
    internal fun buildOverlayPermissionSettingsIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )

    private fun launchSettings() {
        // `FLAG_ACTIVITY_NEW_TASK` is mandatory when starting Settings
        // from any non-Activity context; here we're already in an
        // Activity, so the flag is redundant but harmless. Keeping it
        // matches the Service-side launch path one-for-one (Spec 3
        // §5.2) — drift between the two would be a future bug source.
        val intent = buildOverlayPermissionSettingsIntent().apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        // Mark that the rationale has been shown to the user so the
        // in-IME info-bar's "first-time" logic stays consistent
        // across surfaces (Spec 3 §5.4).
        gate.markOnboardingShown()
    }

    private fun refreshStatus() {
        statusTv.text = if (gate.hasOverlayPermission()) {
            getString(R.string.overlay_perm_onboarding_granted)
        } else {
            getString(R.string.overlay_perm_onboarding_pending)
        }
    }
}
