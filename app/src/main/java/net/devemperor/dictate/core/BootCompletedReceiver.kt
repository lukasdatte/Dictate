package net.devemperor.dictate.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.state.PipelineRecovery

/**
 * Boot-time DB cleanup receiver (B1.4 — FGS Crash-Resilience).
 *
 * After a reboot — or any cold start where the user has not yet
 * opened the keyboard — there may be stale DB rows left over from a
 * pre-reboot crash:
 *
 *  - `RECORDING` rows from a recording that was in flight when the
 *    process died.
 *  - `TRANSCRIBING` rows whose audio file may or may not still be
 *    on disk.
 *  - Orphaned audio segments without a DB row.
 *
 * Without this receiver, those rows sit untouched until the user
 * next opens the IME and the `DictateOrchestrator` runs its own
 * `PipelineRecovery.recover()` pass. That works, but it means the
 * first keyboard-open after a crash is slower and the user might
 * see a stale "Aufnahme läuft" notification (if the FGS-restart
 * picked one up).
 *
 * This receiver runs the **DB-only** half of recovery
 * ([PipelineRecovery.recoverDbOnly]) as soon as the device boots,
 * so the next IME-bind starts on a clean state. It does **not**
 * start the IME service (Android does not start IMEs via
 * `BOOT_COMPLETED` anyway).
 *
 * **Idempotency.** If both this receiver and a subsequent IME-bind
 * call into recovery, the second pass is a no-op — the first run
 * already promoted RECORDING/TRANSCRIBING rows out of the
 * candidate set.
 *
 * **Threading.** [onReceive] hops to a coroutine on
 * [Dispatchers.IO] via [goAsync] so the 10-second receiver budget
 * is not blocked by Room I/O. [PendingResult.finish] is called in
 * every completion branch so the system knows the receiver is done.
 *
 * @see net.devemperor.dictate.state.PipelineRecovery.recoverDbOnly
 * @see docs/decisions/0008-ui-surface-axes-widget-state-and-ime-view.md
 *   (Plan §B1.4 — FGS Crash-Resilience)
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            // Defensive — the manifest filter is explicit, but a
            // broken external sender could still route here.
            return
        }
        val pending = goAsync()
        val app = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val db = DictateDatabase.getInstance(app)
                val recovery = PipelineRecovery(
                    sessionDao = db.sessionDao(),
                    // sessionRepo + emitAction left at their defaults —
                    // recoverDbOnly() does not touch them.
                )
                recovery.recoverDbOnly()
            } catch (t: Throwable) {
                Log.e(TAG, "Boot-time recovery failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootRecoveryReceiver"
    }
}
