package net.devemperor.dictate.core

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import net.devemperor.dictate.R
import net.devemperor.dictate.preferences.WindowsTarget
import net.devemperor.dictate.settings.DictateSettingsActivity
import net.devemperor.dictate.settings.WindowsPairingActivity

/**
 * Invisible trampoline behind the external "PC dictation" entry points (pc-dictation-activity),
 * mirroring [StartDictationActivity]: precondition handling exists exactly once, then the flow
 * continues to the right screen.
 *
 * # Who launches this
 *
 *  - The exported `.core.PcDictation` **activity-alias** (MAIN+LAUNCHER, its own "PC Dictation"
 *    label + PC icon) — the third launcher entry next to "Dictate" and "Dictation".
 *  - The **static app shortcut** (`res/xml/shortcuts.xml`, long-press on the launcher icon).
 *
 * (The in-keyboard PC long-press does NOT come through here — the IME already knows whether a PC is
 * paired and branches itself in `onPcLongClicked`.)
 *
 * # Lifecycle contract
 *
 * Paired with `Theme.NoDisplay` (finish before `onResume`), `noHistory` + `excludeFromRecents` +
 * `taskAffinity=""` so a trigger neither draws nor pollutes recents nor pulls the main task forward.
 * Toasts are system windows, so the mic-permission toast works from a NoDisplay activity.
 *
 * Routing is the pure [decidePcDictationLaunch] gate — missing mic → toast + settings (which
 * auto-requests RECORD_AUDIO); no pairing → the pairing screen; otherwise the Activity.
 */
class StartPcDictationActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            route()
        } finally {
            finish()
        }
    }

    private fun route() {
        val sp = getSharedPreferences(DictatePipelineService.PREFS_NAME, MODE_PRIVATE)
        val decision = decidePcDictationLaunch(
            hasMicPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
            isPaired = WindowsTarget.from(sp) != null,
        )

        val target = when (decision) {
            PcDictationLaunchDecision.OpenPcDictation -> PcDictationActivity::class.java
            PcDictationLaunchDecision.OpenPairing -> WindowsPairingActivity::class.java
            PcDictationLaunchDecision.OpenMicPermissionSettings -> {
                Toast.makeText(
                    this, R.string.start_dictation_mic_permission_missing, Toast.LENGTH_LONG,
                ).show()
                DictateSettingsActivity::class.java
            }
        }
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
