package net.devemperor.dictate.core

import android.content.Context
import android.content.Intent

/**
 * Builds the Intents the IME uses to open one of the app's own Activities.
 *
 * # Why this exists rather than four hand-rolled Intents
 *
 * Launching an Activity from a Service context needs
 * [Intent.FLAG_ACTIVITY_NEW_TASK] — that part is hard to get wrong, because
 * without it the launch throws. What *is* easy to get wrong is that NEW_TASK
 * **alone** does not mean "start this Activity in a new task". It means "find
 * a task for this Activity", and every Dictate Activity shares the package's
 * default `taskAffinity` with `launchMode=standard`. So if any Dictate task is
 * already in Recents, the system brings **that task forward in the state the
 * user left it** and never starts what was asked for.
 *
 * That is not hypothetical: long-pressing the history button opened the
 * settings screen for exactly this reason, because the user had opened
 * settings earlier and that task was still around. `openSettingsActivity` had
 * the identical bug and nobody noticed for months — its target *is* the
 * settings screen, so the broken behaviour and the correct one are
 * indistinguishable. The pairing launch had it too.
 *
 * Three sites, the same latent defect, one of them symptomatic: that is the
 * signature of a rule that should not have been a convention. Adding
 * [Intent.FLAG_ACTIVITY_CLEAR_TOP] here makes the requested Activity actually
 * run, and makes the next launch site correct by construction instead of by
 * whoever writes it remembering.
 *
 * `PipelineNotificationCoordinator` already used `NEW_TASK or CLEAR_TOP`,
 * which is why the notification path never showed the symptom — it is the
 * precedent this generalises.
 */
object ImeActivityLauncher {

    /**
     * An Intent that opens [target] from the IME's Service [context],
     * genuinely starting it rather than resurrecting a stale task.
     */
    fun intentFor(context: Context, target: Class<*>): Intent =
        Intent(context, target).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
}
