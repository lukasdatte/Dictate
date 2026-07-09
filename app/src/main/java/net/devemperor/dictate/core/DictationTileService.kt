package net.devemperor.dictate.core

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Quick-Settings tile "Dictation" (2026-07-09
 * external-dictation-entry-points) — one of the three external triggers
 * that funnel into [StartDictationActivity] (alongside the launcher
 * alias and the static app shortcut). The tile itself carries **no**
 * dictation logic; keeping it a dumb launcher preserves the
 * single-entry-point invariant and gives the trampoline's permission
 * gate one home.
 *
 * # API notes
 *
 *  - **API ≥ 34**: `startActivityAndCollapse(Intent)` throws
 *    `UnsupportedOperationException`; the `PendingIntent` overload is
 *    mandatory.
 *  - **API 26–33**: the `PendingIntent` overload does not exist; the
 *    (since-deprecated) `Intent` overload is the only path.
 *    `FLAG_ACTIVITY_NEW_TASK` is required because the launch happens
 *    from a Service context.
 *  - **Locked device**: recording + overlay on the lock screen is not a
 *    supported flow (the transcript needs an unlocked target app
 *    anyway), so [onClick] routes through [unlockAndRun] — the user
 *    authenticates first, then the normal start flow runs.
 *    `unlockAndRun` executes the runnable immediately when the device
 *    is not locked, so the `isLocked` guard is just dispatch hygiene.
 */
class DictationTileService : TileService() {

    override fun onClick() {
        if (isLocked) {
            unlockAndRun { launchTrampoline() }
        } else {
            launchTrampoline()
        }
    }

    private fun launchTrampoline() {
        val intent = Intent(this, StartDictationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    /* requestCode = */ 0,
                    intent,
                    // FLAG_IMMUTABLE is mandatory on API 31+; the intent
                    // payload never changes, so no UPDATE_CURRENT.
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
