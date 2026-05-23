package net.devemperor.dictate.core.audit

import android.util.Log
import net.devemperor.dictate.BuildConfig

/**
 * Strict-Mode-Logging audit for keyboard-UI visibility writes
 * (Spec 2 §10 acceptance / §11.8 5c — "Migration-Reihenfolge").
 *
 * # Why this class exists (RR-2 — the highest risk of Block B5)
 *
 * Theme C-R migrates three visibility axes (content-area,
 * prompt-area, overlay-reset) from [net.devemperor.dictate.core.KeyboardStateManager]
 * to three new RenderBackends ([net.devemperor.dictate.state.render.ContentAreaController],
 * [net.devemperor.dictate.state.render.PromptVisibilityController],
 * [net.devemperor.dictate.state.render.OverlayResetHandler]). During the
 * staged cutover **two** subsystems can structurally reach the same
 * `View.visibility` field:
 *
 *  - the **legacy** KSM `applyVisibility()` drive (live until CR4), and
 *  - a **new** dormant controller, once it is armed.
 *
 * If both write the same axis in the same render-tick the keyboard
 * does not crash — it *flickers* or settles on the wrong container,
 * with **no error** (the exact F-1/F-2 silent-regression class at the
 * visibility layer, render-path-cutover.md §6 RR-2). Spec 2 §10
 * therefore makes "**no two subsystems write the same visibility axis
 * concurrently**" a hard acceptance criterion and §11.8 5c mandates a
 * concrete Strict-Mode logger that proves it during the transition
 * window.
 *
 * # The single-owner ledger
 *
 * Every visibility writer on a tracked axis calls [logWrite] **before**
 * it mutates the view. The logger records the first caller per
 * `viewId` per render-generation; a *second distinct* caller on the
 * same `viewId` within the same generation is a **double-write** and is
 * reported via `Log.wtf` (the Strict-Mode signal) plus surfaced through
 * [doubleWriteCount] for unit-test assertion.
 *
 * Callers cross a render-generation boundary by calling
 * [beginRenderGeneration] once per state-emit (the
 * [net.devemperor.dictate.state.layout.KeyboardLayoutManager] fan-out
 * boundary). Within a generation, the *same* caller writing the same
 * axis again (idempotent re-render) is **not** a double-write — only a
 * *different* caller is.
 *
 * # Lifecycle (Spec 2 §11.8 5c → 5d)
 *
 * - **CR3 (= Block 5c):** this logger is instantiated and the legacy
 *   KSM path + the (dormant) controllers both report through it. Because
 *   the controllers are dormant (they do not write the live axis), the
 *   ledger proves KSM is the *sole* live writer per axis → acceptance
 *   §10 satisfied with zero double-writes.
 * - **CR4:** the KSM drive is removed and the controllers are armed in
 *   the **same** chunk. The sole writer flips KSM → controller; still
 *   zero double-writes (the logger keeps proving it across the flip).
 * - **CR-DEL (= Block 5d):** KSM is deleted and this logger is removed
 *   ersatzlos (it is debug-only scaffolding, never shipped logic).
 *
 * Guarded by [BuildConfig.DEBUG]: in release builds [logWrite] is a
 * cheap early-return (no allocation, no `Log` call) so the audit is
 * free in production.
 *
 * Provenance: this logger originally proved the legacy
 * `KeyboardStateManager` (deleted in CR-DEL) was the sole live
 * visibility writer during the staged cutover.
 * @see net.devemperor.dictate.state.render.ContentAreaController
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §10 + §11.8
 * @see docs/plans/2026-05-15 - dictate-cutover-completion/research/render-path-cutover.md §6 RR-2
 */
class VisibilityWriteAuditLogger {

    /**
     * `viewId` → the **live** caller that first wrote it in the current
     * render generation. A different **live** caller on the same id
     * within the same generation is the double-write the §10
     * acceptance forbids.
     *
     * Dormant reports (a controller that is attached but gated off in
     * CR3 — see [RenderGate]) are intentionally **not** entered here:
     * a suppressed write cannot reach the view, so it cannot conflict
     * with the legacy KSM live write. They are recorded separately in
     * [dormantReporters] purely as the *observability* half of the
     * proof ("this owner exists and WOULD write, but is suppressed —
     * the live owner is unique").
     */
    private val ledger = mutableMapOf<Int, String>()

    /**
     * `viewId` → the set of owners that reported a *dormant* (gated-off)
     * intended write this generation. Test-observable proof that the
     * new controllers are present-but-suppressed while the legacy KSM
     * remains the sole live writer (RR-2 / Spec 2 §10).
     */
    private val dormantReporters = mutableMapOf<Int, MutableSet<String>>()

    /**
     * Count of detected double-writes since construction. Test-asserted
     * (the §10 acceptance is "0 after a soak over all layout modes").
     * Production code must not branch on this.
     */
    var doubleWriteCount: Int = 0
        private set

    /**
     * Open a new render generation — call **once** per state-emit
     * fan-out (the manager re-render boundary), before any backend
     * writes. Resets the per-generation ledger so an idempotent
     * re-render of the same axis by the *same* owner is not mistaken
     * for a double-write.
     */
    fun beginRenderGeneration() {
        if (!BuildConfig.DEBUG) return
        ledger.clear()
        dormantReporters.clear()
    }

    /**
     * Owners that reported a *dormant* (suppressed) intended write for
     * [viewId] this generation. Empty when none. Test-only — the
     * RR-2 proof asserts the new controllers show up here (present but
     * suppressed) while [soleLiveWriterOf] stays the legacy KSM.
     */
    fun dormantReportersOf(viewId: Int): Set<String> =
        dormantReporters[viewId]?.toSet() ?: emptySet()

    /**
     * The single live writer recorded for [viewId] this generation, or
     * `null` if nothing live wrote it yet. Test-only — the RR-2 proof
     * asserts this is `"KeyboardStateManager"` through CR3 and flips to
     * the controller name in CR4.
     */
    fun soleLiveWriterOf(viewId: Int): String? = ledger[viewId]

    /**
     * Record (and audit) a visibility write to [viewId] by [caller]
     * targeting [target] (`View.VISIBLE`/`GONE`/`INVISIBLE`).
     *
     * Call this **immediately before** the actual `view.visibility =`
     * mutation. The first caller per `viewId` per generation is the
     * legitimate owner; a *different* caller on the same id in the same
     * generation is the double-write — reported via `Log.wtf` and
     * counted in [doubleWriteCount].
     *
     * @param viewId the `View.getId()` of the view being mutated.
     * @param caller a stable owner tag — by convention the writing
     *   class's simple name (Spec 2 §11.8 5c uses the caller's class;
     *   we pass it explicitly rather than walking the stack trace,
     *   which is fragile under R8/Robolectric).
     * @param target the visibility constant being written (for the log
     *   line only — the audit keys on `viewId` + `caller`).
     * @param live `true` iff the write actually reaches the view (the
     *   legacy KSM, or an *armed* controller post-CR4). `false` for a
     *   *dormant* controller (CR3 — attached but gated off): such a
     *   report is recorded separately and never counts as a
     *   double-write, because a suppressed write cannot conflict with
     *   the one live writer (RR-2).
     */
    fun logWrite(viewId: Int, caller: String, target: Int, live: Boolean) {
        if (!BuildConfig.DEBUG) return

        if (!live) {
            // Dormant report — observability only. Proves the new owner
            // exists and WOULD write, but is suppressed. Not a conflict.
            dormantReporters.getOrPut(viewId) { mutableSetOf() }.add(caller)
            return
        }

        val existing = ledger[viewId]
        if (existing == null) {
            ledger[viewId] = caller
            return
        }
        if (existing != caller) {
            doubleWriteCount++
            Log.wtf(
                TAG,
                "RR-2 double-write: viewId=$viewId written LIVE by BOTH " +
                    "'$existing' and '$caller' in the same render generation " +
                    "(target=$target). Spec 2 §10 forbids two subsystems on " +
                    "one visibility axis. This is the silent-flicker / wrong-" +
                    "container F-1/F-2-class regression at the visibility layer.",
            )
        }
        // Same live caller again in the same generation = idempotent
        // re-render, NOT a double-write — no-op.
    }

    companion object {
        private const val TAG = "DictateIME"
    }
}
