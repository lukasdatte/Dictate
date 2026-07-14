package net.devemperor.dictate.windows

import android.content.SharedPreferences
import net.devemperor.dictate.database.entity.InsertionSource
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.WindowsTarget
import net.devemperor.dictate.preferences.get

/**
 * THE single gate "should this session go to the PC?" (ADR-0019).
 *
 * Read by BOTH terminal producers (the IME seam and the headless sink) and the settings — one
 * predicate, never a condition copied four times. Auto-send needs BOTH the toggle on AND a pairing;
 * an active toggle without a target would tip every dictation into the pending-part path.
 *
 * The only place in `windows/` that reads [SharedPreferences] — testable in pure JVM through the
 * project's `FakeSharedPreferences`.
 */
object WindowsAutoSend {

    /** True iff the auto-send toggle is on AND a PC is paired. */
    fun shouldAutoSend(sp: SharedPreferences): Boolean =
        sp.get(Pref.WindowsAutoSendEnabled) && WindowsTarget.from(sp) != null

    /**
     * Should a finished pipeline completion of this [source] be diverted to the paired PC
     * (instead of committed into the host field)?
     *
     * Auto-send is a *dictation* feature: a transcript (optionally reworded / queue-processed)
     * goes to the PC. A [InsertionSource.STATIC_PROMPT] completion is NOT dictation — it is the
     * literal text of a long-pressed text-only pill that the user wants inserted 1:1 into the
     * host field. Diverting it would make the text vanish locally (the IME divert branch also
     * skips the pending-part fallback). So STATIC_PROMPT is never diverted; every genuine
     * dictation output is (guards commit 27b91b3).
     */
    fun shouldDivertToPc(source: InsertionSource, sp: SharedPreferences): Boolean =
        shouldAutoSend(sp) && source.isDictationOutput()

    /**
     * Which [InsertionSource]s are dictation outputs (auto-send-eligible)? Exhaustive `when`
     * (no `else`) so a new source must be classified deliberately at compile time.
     */
    private fun InsertionSource.isDictationOutput(): Boolean = when (this) {
        InsertionSource.STATIC_PROMPT -> false // pure text-only pill → always inserted locally
        InsertionSource.TRANSCRIPTION,
        InsertionSource.REWORDING,
        InsertionSource.QUEUED_PROMPT,
        InsertionSource.PENDING_PART -> true
    }
}
