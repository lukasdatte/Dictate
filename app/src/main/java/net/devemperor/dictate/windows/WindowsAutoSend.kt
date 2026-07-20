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
        sp.get(Pref.WindowsAutoSendEnabled) && WindowsTarget.isPaired(sp)

    /**
     * Should a finished pipeline completion of this [source] be diverted to the paired PC
     * (instead of committed into the host field)?
     *
     * Auto-send is a *dictation* feature: a transcript (optionally reworded / queue-processed)
     * goes to the PC. A [InsertionSource.STATIC_PROMPT] completion is NOT dictation — it is the
     * literal text of a text pill (PromptType.TEXT) that the user wants inserted 1:1 into the
     * host field. Diverting it would make the text vanish locally (the IME divert branch also
     * skips the pending-part fallback). So STATIC_PROMPT is never diverted; every genuine
     * dictation output is (guards commit 27b91b3). (Text pills normally insert pipeline-free and
     * never reach this gate; the classifier stays source-aware for the defensive fallback path.)
     */
    fun shouldDivertToPc(source: InsertionSource, sp: SharedPreferences): Boolean =
        shouldAutoSend(sp) && source.isDictationOutput()

    /**
     * The [shouldDivertToPc] gate widened by the **PC-only terminal mode** (pc-dictation-activity).
     *
     * When [pcOnly] is `true` (the full-screen PC-dictation Activity owns the foreground), EVERY
     * pipeline terminal diverts to the PC **source-independently** — including
     * [InsertionSource.STATIC_PROMPT], which the persistent auto-send path deliberately excludes.
     * The Activity has no local IME host, so there is nowhere else the output could go: a text pill
     * inserted "locally" would simply vanish. The [pcOnly] flag is read from
     * `state.features.pcOnly`, not from [SharedPreferences], because it is a transient runtime mode
     * (see [net.devemperor.dictate.state.FeatureToggles.pcOnly]) — hence it is a separate parameter
     * rather than a fourth pref folded into [shouldAutoSend].
     *
     * When [pcOnly] is `false` this is exactly [shouldDivertToPc] — the persistent auto-send
     * behaviour is unchanged.
     */
    fun shouldDivertToPc(source: InsertionSource, sp: SharedPreferences, pcOnly: Boolean): Boolean =
        pcOnly || shouldDivertToPc(source, sp)

    /**
     * Which [InsertionSource]s are dictation outputs (auto-send-eligible)? Exhaustive `when`
     * (no `else`) so a new source must be classified deliberately at compile time.
     */
    private fun InsertionSource.isDictationOutput(): Boolean = when (this) {
        InsertionSource.STATIC_PROMPT -> false // literal text pill (PromptType.TEXT) → always inserted locally
        InsertionSource.TRANSCRIPTION,
        InsertionSource.REWORDING,
        InsertionSource.QUEUED_PROMPT,
        InsertionSource.PENDING_PART -> true
    }
}
