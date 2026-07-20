package net.devemperor.dictate.companion.ui.panel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.devemperor.dictate.companion.pipeline.PanelControl

/**
 * Window warmth + visibility of the dictation panel (desktop-host.md §6.2).
 *
 * The Compose `Window` behind this exists from process start and is only ever *shown/hidden*
 * (`visible=false`), never re-created per hotkey — that is what keeps the toggle under 100 ms (F5).
 */
interface PanelWindowControl {

    /** Panel visible, positioned (v1: fixed bottom-center, spec §6.2). */
    fun show()

    /** Panel invisible; the window and its composition stay warm. */
    fun hide()

    /** true when the `WS_EX_NOACTIVATE` spike is proven — the panel never takes focus (§6.3). */
    val focusFree: Boolean
}

/**
 * The production [PanelWindowControl]: a visibility [StateFlow] the warm `PanelWindow` composable
 * binds to. Also a [PanelControl], so the reducer's `ShowPanel`/`HidePanel` effects land here — one
 * visibility authority for hotkey, pipeline and tray.
 *
 * **Spike gate (§6.3, CAUTION block):** [focusFree] is `styleApplied && FOCUS_SPIKE_VERIFIED`.
 * `styleApplied` says Win32 accepted `WS_EX_NOACTIVATE` (set by `PanelWindow` after styling);
 * [FOCUS_SPIKE_VERIFIED] is the *manual* Windows verdict (TC-W1) and ships `false` until that
 * acceptance proves the panel really never steals focus. Until then the `FocusRestorationPolicy`
 * fallback stays active — belt and braces, never "wird schon". Flipping the constant to `true` (and
 * un-pending `FocusFreeWindowSpikeTest`) is the entire spike-success switch.
 */
class ComposePanelWindowControl : PanelWindowControl, PanelControl {

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    @Volatile
    private var styleApplied = false

    /** Reported by `PanelWindow` once `Win32WindowStyler.applyFocusFreeStyle` ran (false on Linux). */
    fun onFocusFreeStyle(applied: Boolean) {
        styleApplied = applied
    }

    override fun show() {
        _visible.value = true
    }

    override fun hide() {
        _visible.value = false
    }

    override val focusFree: Boolean get() = styleApplied && FOCUS_SPIKE_VERIFIED

    override fun setVisible(visible: Boolean) = if (visible) show() else hide()

    companion object {
        /**
         * The D2 focus-spike verdict — decided by the manual Windows acceptance (TC-W1), not by
         * code. See `FocusFreeWindowSpikeTest` (`pending: D2-focus-spike`).
         */
        const val FOCUS_SPIKE_VERIFIED = false
    }
}
