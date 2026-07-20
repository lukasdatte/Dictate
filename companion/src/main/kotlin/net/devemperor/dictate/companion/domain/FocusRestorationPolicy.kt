package net.devemperor.dictate.companion.domain

import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.domain.port.ForegroundWindows
import net.devemperor.dictate.companion.domain.port.TextInserter
import net.devemperor.dictate.companion.domain.port.WindowHandle

/**
 * The focus fallback of desktop-host.md §6.3 (D4.3), as a pure, testable policy: when the dictation
 * trigger fires, remember which window is in the foreground; before the text insert, put that window
 * back in front (plus a short settle delay), so a panel that *did* take focus never receives the
 * Ctrl+V meant for the user's editor.
 *
 * Two states of the world, both unit-tested (acceptance §2 criterion 8):
 *
 * - **Spike succeeded** (`focusFree() == true` — the `WS_EX_NOACTIVATE` panel provably never takes
 *   focus, decided by the F1 Windows acceptance TC-W1): remember/restore degrade to no-ops.
 * - **Fallback** (spike undecided or failed — the default, and NOT an escalation per D4.3): both
 *   steps run. Restoring is idempotent and harmless even when the panel never stole focus, which is
 *   why the fallback is safe to leave on until the spike is *proven* (footgun "Fokus klauen": never
 *   "wird schon").
 *
 * The raw `GetForegroundWindow`/`SetForegroundWindow` calls sit behind the [ForegroundWindows] port;
 * the settle sleep is injected so tests do not wait.
 */
class FocusRestorationPolicy(
    private val windows: ForegroundWindows,
    /** Supplier, not value: the spike verdict is only known once the panel window exists. */
    private val focusFree: () -> Boolean,
    private val settleDelayMillis: Long = DEFAULT_SETTLE_DELAY_MILLIS,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {

    @Volatile
    private var saved: WindowHandle? = null

    /** Call when the dictation trigger fires — before the panel becomes visible. */
    fun onDictationTrigger() {
        if (!windows.available || focusFree()) return
        saved = windows.foregroundWindow()
    }

    /**
     * Call immediately before the text insert. Restores the remembered window and lets it settle.
     * A failed restore (window closed meanwhile, UIPI) still proceeds to the insert — Ctrl+V into
     * whatever is in front beats silently dropping the dictation.
     */
    fun restoreBeforeInsert() {
        if (!windows.available || focusFree()) return
        val target = saved ?: return
        if (windows.focusWindow(target)) sleep(settleDelayMillis)
    }

    companion object {
        /**
         * Long enough for Windows to complete the activation round-trip before `SendInput`, short
         * enough to stay imperceptible next to the AI round-trip that precedes every insert.
         */
        const val DEFAULT_SETTLE_DELAY_MILLIS = 150L
    }
}

/**
 * [TextInserter] decorator that runs [FocusRestorationPolicy.restoreBeforeInsert] before delegating
 * (spec §6.3/§8.5). Only the *dictation pipeline's* inserter is wrapped — the phone-dispatch path
 * has no panel in play and keeps the bare inserter.
 */
class FocusRestoringTextInserter(
    private val delegate: TextInserter,
    private val focus: FocusRestorationPolicy,
) : TextInserter {

    override val available: Boolean get() = delegate.available

    override fun insert(text: String): InsertionOutcome {
        focus.restoreBeforeInsert()
        return delegate.insert(text)
    }
}
