package net.devemperor.dictate.companion.pipeline

/**
 * Shows/hides the warm dictation panel (desktop-host.md §6.2). A minimal port here in D1b so the
 * reducer's `ShowPanel`/`HidePanel` effects have somewhere to land; D2 supplies the real
 * `PanelWindowControl` (WS_EX_NOACTIVATE, focus-free window). Headless tests use [None].
 */
fun interface PanelControl {
    fun setVisible(visible: Boolean)

    companion object {
        /** No-op panel control for headless runs and the pre-D2 wiring. */
        val None = PanelControl { }
    }
}
