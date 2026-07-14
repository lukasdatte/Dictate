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
     * TODO(C.2): source-aware — for now this is the verbatim pre-fix behaviour (source
     * ignored) so the [WindowsAutoSendTest] STATIC_PROMPT regression is RED first.
     */
    fun shouldDivertToPc(source: InsertionSource, sp: SharedPreferences): Boolean =
        shouldAutoSend(sp)
}
