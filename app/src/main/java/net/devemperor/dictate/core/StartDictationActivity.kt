package net.devemperor.dictate.core

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import net.devemperor.dictate.R
import net.devemperor.dictate.onboarding.OverlayPermissionOnboardingActivity
import net.devemperor.dictate.settings.DictateSettingsActivity
import net.devemperor.dictate.state.render.overlay.DefaultOverlayPermissionGate

/**
 * Invisible trampoline behind every external "start dictation" trigger
 * (2026-07-09 external-dictation-entry-points).
 *
 * # Who launches this
 *
 *  - The exported `.core.StartDictation` **activity-alias**
 *    (`MAIN`+`LAUNCHER`, own "Dictation" label + mic icon) — this is
 *    what Samsung's S-Pen Air Command, the Edge panel, the side-key
 *    double-press picker, Routines, and Tasker list as a second app
 *    entry next to "Dictate".
 *  - The **static app shortcut** (`res/xml/shortcuts.xml`, long-press
 *    on the launcher icon, pinnable).
 *  - The **Quick-Settings tile** ([DictationTileService]).
 *
 * All three funnel here so precondition handling exists exactly once;
 * from here the flow continues through the single canonical entry
 * `DictatePipelineService` / [PipelineActionRouter.ACTION_START_DICTATION]
 * → [net.devemperor.dictate.state.resolveExternalDictationStart].
 *
 * # Lifecycle contract
 *
 * The manifest pairs this with `Theme.NoDisplay`, which REQUIRES
 * `finish()` before `onResume` completes (enforced since API 23) — the
 * activity never draws. It also sets `noHistory` + `excludeFromRecents`
 * + `taskAffinity=""` so a trigger neither pollutes recents nor pulls
 * the main Dictate task to the foreground. Note: text toasts are system
 * windows, not activity UI — the mic-permission toast below works fine
 * from a NoDisplay activity.
 *
 * # FGS start legality (Android 14+)
 *
 * The `startForegroundService` call happens while this activity is the
 * foreground app, so the microphone-FGS background-start restriction
 * does not apply and while-in-use mic access is granted. Once the FGS
 * is up, `PipelineNotificationCoordinator.foregroundArmer` re-asserts
 * the mic-FGS association on every capture-phase status, keeping later
 * pause/resume cycles legal (see the 2026-07-09 widget-cancel-restart
 * fix).
 *
 * # Degradation (no silent failure)
 *
 * Routing is decided by the pure [decideStartDictationLaunch] gate —
 * see its decision table. Missing mic permission → toast + settings
 * (which auto-requests RECORD_AUDIO on open); missing overlay
 * permission → [OverlayPermissionOnboardingActivity] (the explainer
 * screen built for exactly these secondary entry points).
 */
class StartDictationActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            route()
        } finally {
            // NoDisplay contract — finish unconditionally, even if a
            // route target failed to resolve; the trigger must never
            // leave an invisible activity behind.
            finish()
        }
    }

    private fun route() {
        val decision = decideStartDictationLaunch(
            hasMicPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
            // Canonical live read of SYSTEM_ALERT_WINDOW — the same gate
            // the pipeline service + overlay backend use (Spec 3 §5.0).
            hasOverlayPermission = DefaultOverlayPermissionGate(
                ctx = this,
                prefs = getSharedPreferences(
                    DictatePipelineService.PREFS_NAME, MODE_PRIVATE,
                ),
            ).hasOverlayPermission(),
        )

        when (decision) {
            StartDictationLaunchDecision.StartPipelineService -> {
                val intent = Intent(this, DictatePipelineService::class.java)
                    .setAction(PipelineActionRouter.ACTION_START_DICTATION)
                ContextCompat.startForegroundService(this, intent)
            }

            StartDictationLaunchDecision.OpenMicPermissionSettings -> {
                Toast.makeText(
                    this,
                    R.string.start_dictation_mic_permission_missing,
                    Toast.LENGTH_LONG,
                ).show()
                startActivity(
                    Intent(this, DictateSettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }

            StartDictationLaunchDecision.OpenOverlayPermissionOnboarding -> {
                startActivity(
                    Intent(this, OverlayPermissionOnboardingActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
