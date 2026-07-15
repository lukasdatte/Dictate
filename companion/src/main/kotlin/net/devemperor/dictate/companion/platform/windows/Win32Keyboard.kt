package net.devemperor.dictate.companion.platform.windows

import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.platform.win32.WinUser.INPUT

/**
 * One raw `SendInput` primitive, plus a foreground probe — and **nothing else**.
 *
 * The seam exists so that every *policy* built on keyboard injection (the insertion's
 * save-clipboard-write-inject-restore, and the input engine's chord resolution) is ordinary Kotlin
 * that the Linux test suite exercises in full, while the part that genuinely cannot run here —
 * `user32.dll` — is reduced to two methods with no branching in them. What is left un-run on Linux
 * is exactly what the Windows checklist covers, and no more.
 *
 * `sendCtrlV` is kept as a named convenience (the insertion path is Ctrl+V and nothing else,
 * ADR-0018) but is now just `sendKeySequence([CTRL_V_SEQUENCE])` — one injection primitive, one set
 * of JNA gotchas, one place to fix them (DRY).
 */
interface Win32Keyboard {

    /** false when nothing has focus (the desktop itself is in front, or a screen lock). */
    fun hasForegroundWindow(): Boolean

    /**
     * Injects an ordered list of key-down/up events into whatever has focus.
     *
     * The caller brackets modifiers itself (downs in order, the key, then ups in reverse) — this
     * method just replays the list verbatim through one `SendInput` call.
     *
     * @return how many of the `events.size` events Windows accepted. Fewer means UIPI blocked part
     * of the injection — see [JnaWin32Keyboard] for why that is a degradation, not an error.
     */
    fun sendKeySequence(events: List<KeyEventSpec>): Int

    /**
     * Injects Ctrl+V into whatever has focus.
     *
     * @return how many of the [CTRL_V_EVENT_COUNT] events Windows accepted (see [sendKeySequence]).
     */
    fun sendCtrlV(): Int

    companion object {
        /** VK_CONTROL down, 'V' down, 'V' up, VK_CONTROL up. */
        const val CTRL_V_EVENT_COUNT = 4
    }
}

/**
 * One key event: a virtual-key code and whether this is the release (`keyUp`) or the press.
 *
 * Plain data on purpose — a chord is *built* into a `List<KeyEventSpec>` by pure, Linux-testable
 * code (the insertion's [JnaWin32Keyboard.CTRL_V_SEQUENCE], the input engine's chord resolution),
 * and only the final replay touches `user32.dll`.
 */
data class KeyEventSpec(val vk: Int, val keyUp: Boolean)

/**
 * `SendInput` against `user32.dll`, via JNA.
 *
 * **Compile-verified on Linux, behaviour-verified only on Windows** (checklist items 1–4). Three
 * things about this code are not obvious and all three have cost somebody a day:
 *
 * 1. **UIPI.** A process that is not elevated cannot inject input into an elevated window (an admin
 *    PowerShell, the Task Manager, a UAC prompt). Windows does not raise an error for this — it
 *    simply accepts *fewer* events than were passed and returns that count. A caller that ignores
 *    the return value reports a silent success for a Ctrl+V that never happened. Hence the count is
 *    returned rather than a boolean, and the caller degrades to `CLIPBOARD_ONLY`. Running the
 *    companion elevated would "fix" it and is a bad idea: it would hand every paired phone the
 *    ability to type into an administrator's session.
 *
 * 2. **The events must live in contiguous memory.** `SendInput` takes an *array*, so the INPUT
 *    structures are allocated with `INPUT().toArray(n)` — a plain Kotlin `Array(n) { INPUT() }`
 *    would hand Windows four unrelated pointers and the call would fail or, worse, read garbage.
 *
 * 3. **The `INPUT.input` union must have its active member selected** (`setType("ki")`) before its
 *    fields are written, or JNA writes into the wrong branch of the union and Windows sees a mouse
 *    event with nonsense in it.
 *
 * Why Ctrl+V at all, rather than `KEYEVENTF_UNICODE` (typing the text character by character)? That
 * alternative was considered and rejected: it takes seconds for a long dictation, it loses
 * characters in fields with auto-complete or an active IME, and it does not even leave the clipboard
 * alone. Clipboard + Ctrl+V is what every comparable tool does.
 */
object JnaWin32Keyboard : Win32Keyboard {

    private const val VK_CONTROL = 0x11
    private const val VK_V = 0x56

    /**
     * The Ctrl+V event list — the single source of truth for the insertion's key sequence.
     *
     * Extracted to a constant so the sequence is unit-testable on Linux (`SendInput` is not) and so
     * [sendCtrlV] is literally `sendKeySequence(CTRL_V_SEQUENCE)` — no second hand-rolled event loop.
     */
    val CTRL_V_SEQUENCE: List<KeyEventSpec> = listOf(
        KeyEventSpec(VK_CONTROL, keyUp = false),
        KeyEventSpec(VK_V, keyUp = false),
        KeyEventSpec(VK_V, keyUp = true),
        KeyEventSpec(VK_CONTROL, keyUp = true),
    )

    override fun hasForegroundWindow(): Boolean = User32.INSTANCE.GetForegroundWindow() != null

    override fun sendCtrlV(): Int = sendKeySequence(CTRL_V_SEQUENCE)

    override fun sendKeySequence(events: List<KeyEventSpec>): Int {
        if (events.isEmpty()) return 0

        val inputs = INPUT().toArray(events.size).map { it as INPUT }
        events.forEachIndexed { i, event -> keyEvent(inputs[i], event.vk, event.keyUp) }

        val accepted = User32.INSTANCE.SendInput(
            WinDef.DWORD(inputs.size.toLong()),
            inputs.toTypedArray(),
            inputs[0].size(),
        )
        return accepted.toInt()
    }

    private fun keyEvent(input: INPUT, virtualKey: Int, keyUp: Boolean) {
        input.type = WinDef.DWORD(INPUT.INPUT_KEYBOARD.toLong())
        input.input.setType("ki")
        input.input.ki.wVk = WinDef.WORD(virtualKey.toLong())
        input.input.ki.wScan = WinDef.WORD(0)
        input.input.ki.dwFlags = WinDef.DWORD(if (keyUp) WinUser.KEYBDINPUT.KEYEVENTF_KEYUP.toLong() else 0L)
        input.input.ki.time = WinDef.DWORD(0)
        input.input.ki.dwExtraInfo = BaseTSD.ULONG_PTR(0)
    }
}
