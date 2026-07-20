package net.devemperor.dictate.companion.fakes

import net.devemperor.dictate.companion.hotkey.GlobalHotkey
import net.devemperor.dictate.companion.hotkey.HotkeyCombo

/**
 * A hand-triggerable hotkey (spec §6.1's test fake; house style: hand-written, no mock library —
 * pattern `FakeInputCommandPerformer`). [trigger] plays a hotkey press; [registered] records what
 * the app asked the OS for.
 */
class FakeGlobalHotkey(
    override var available: Boolean = true,
    var acceptNext: Boolean = true,
) : GlobalHotkey {

    val registered = mutableListOf<HotkeyCombo>()
    private var onTrigger: (() -> Unit)? = null

    override fun register(combo: HotkeyCombo, onTrigger: () -> Unit): Boolean {
        registered += combo
        if (!acceptNext) return false
        this.onTrigger = onTrigger
        return true
    }

    override fun unregister() {
        onTrigger = null
    }

    /** Simulates the user pressing the hotkey. No-op when nothing is registered — like the OS. */
    fun trigger() {
        onTrigger?.invoke()
    }
}
