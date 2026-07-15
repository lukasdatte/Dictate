package net.devemperor.dictate.onboarding

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.accessibility.A11yEnablementGate

/**
 * Explains the opt-in screen-context feature and walks the user to the system
 * switch that turns it on.
 *
 * # Why an Activity and not an onboarding page
 *
 * `OnboardingActivity`'s ViewPager identifies steps by **array position**,
 * hard-coded in three places (the layout-id array, an `if (position == N)`
 * chain in the adapter, and `notifyItemChanged(N)` calls). Inserting a step
 * silently breaks every branch after it, with no compiler help. More
 * importantly this step is optional and revisitable — the user may enable
 * screen context months later from the keyboard — which a linear first-run
 * flow cannot express. So it follows `OverlayPermissionOnboardingActivity`
 * instead: a standalone surface for a special permission, reachable from
 * wherever the user happens to be.
 *
 * # Polling, not a result callback
 *
 * Accessibility cannot be requested programmatically — it is a security
 * boundary with no API and no result to receive. The only way to learn the
 * outcome is to look again when the user comes back, which is what [onResume]
 * does. Same shape as the overlay-permission screen.
 */
class A11yContextOnboardingActivity : AppCompatActivity() {

    private lateinit var statusTv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_a11y_context_onboarding)

        statusTv = findViewById(R.id.a11y_onboarding_status_tv)

        findViewById<MaterialButton>(R.id.a11y_onboarding_open_settings_btn)
            .setOnClickListener { startActivity(A11yEnablementGate.settingsIntent()) }

        // Sideload escape hatch: on Android 13+ the accessibility switch is
        // greyed out until "Allow restricted settings" is granted here, and the
        // system offers no hint about that from the settings screen itself.
        findViewById<MaterialButton>(R.id.a11y_onboarding_open_app_info_btn)
            .setOnClickListener { startActivity(A11yEnablementGate.appInfoIntent(this)) }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        statusTv.text = getString(
            if (A11yEnablementGate.isServiceEnabled(this)) {
                R.string.dictate_a11y_state_enabled
            } else {
                R.string.dictate_a11y_state_disabled
            },
        )
    }
}
