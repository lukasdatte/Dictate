package net.devemperor.dictate.companion.ui.panel

import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * The target assertion of the D2 focus spike (desktop-host.md §6.3, spec CAUTION block): with
 * `WS_EX_NOACTIVATE` applied, the visible panel never takes focus, so `focusFree` may report true
 * and the `FocusRestorationPolicy` fallback may stand down.
 *
 * **Pending** (test-first-patterns: pending tests document a gap someone still has to close): the
 * verdict needs the manual Windows acceptance — TC-W1 in `reports/e2e-runbook.md`, run in Block F —
 * because whether the style actually prevents Compose/AWT activation is only observable on a real
 * Windows desktop. Until then `ComposePanelWindowControl.FOCUS_SPIKE_VERIFIED` stays `false` and
 * this test stays ignored; it must NOT be faked green (D4.3 — spike failure is no escalation, the
 * fallback is the equally-valid path).
 *
 * Un-pending procedure (the spike-success switch, in one commit):
 *  1. TC-W1 passes on Windows (focus stays with Notepad while the panel shows and is clicked).
 *  2. Flip `ComposePanelWindowControl.FOCUS_SPIKE_VERIFIED` to `true`.
 *  3. Remove the `@Ignore` below — the assertion then holds by construction and guards the flag.
 * If TC-W1 *fails*, delete this test and the `FOCUS_SPIKE_VERIFIED` gate instead, documenting the
 * fallback as the permanent path in `adr-desktop-panel-ui`.
 */
class FocusFreeWindowSpikeTest {

    @Test
    @Ignore("pending: D2-focus-spike — awaiting the manual Windows verdict (TC-W1, e2e-runbook.md; desktop-host.md §6.3)")
    fun focusFreePanel_reportsFocusFree_onceTheSpikeIsVerified() {
        val control = ComposePanelWindowControl()
        control.onFocusFreeStyle(applied = true) // what PanelWindow reports after a successful styling

        assertTrue(
            "WS_EX_NOACTIVATE verified (TC-W1) — the panel is focus-free and the restoration fallback stands down",
            control.focusFree,
        )
    }
}
