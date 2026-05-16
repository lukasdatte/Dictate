package net.devemperor.dictate.state.render

import net.devemperor.dictate.core.audit.VisibilityWriteAuditLogger

/**
 * The staged-safety-net "dormant vs armed" switch for a visibility
 * RenderBackend (render-path-cutover.md §6 RR-2 + §6.1 staged safety
 * net; Spec 2 §11.8 5c).
 *
 * # Why a gate (RR-2 — the highest risk of Block B5)
 *
 * Theme C-R attaches [ContentAreaController] / [PromptVisibilityController]
 * / [OverlayResetHandler] in **CR3**, but the legacy
 * [net.devemperor.dictate.core.KeyboardStateManager] `applyVisibility()`
 * drive that writes the *same* visibility axes is removed only in
 * **CR4**. A visibility write — unlike a touch listener — is not an
 * "Android keeps the most-recent" overwrite; it is a *repeated write*
 * to the same field. If a controller writes the axis while KSM still
 * drives it, **both** mutate the container every render-tick: the
 * keyboard flickers or settles on the wrong container, with **no
 * error** (the F-1/F-2 silent-regression class at the visibility
 * layer).
 *
 * The mitigation mirrors CR1's RESEND-only long-press model and CR2's
 * `SpecialTouchHandlerInstaller.installDormant`: the new owner is
 * **attached** (wiring proven, view-recreate-safe, CR4 becomes a
 * one-line flip) but **dormant** — it does **not** write the live axis
 * until CR4 [arm]s it *in the same chunk* that removes the legacy KSM
 * drive. Never two live writers on one axis at once.
 *
 * # What dormant still does — the no-double-write proof
 *
 * A dormant controller still receives every `render(state, mode)` tick
 * (it stays a fully-attached [net.devemperor.dictate.state.layout.RenderBackend]).
 * Instead of mutating the view it reports the *intended* write to
 * [VisibilityWriteAuditLogger] tagged with its own owner name. Because
 * the controller never actually writes while dormant, the legacy KSM
 * stays the **sole live writer** per axis — and the audit ledger
 * proves it (`doubleWriteCount == 0`, Spec 2 §10). The dormant report
 * is the active half of the Strict-Mode-Logging acceptance: it makes
 * "exactly one live writer per axis" *observable*, not merely asserted.
 *
 * # CR4 flip
 *
 * CR4 calls [arm] on each controller in the **same** chunk it removes
 * the KSM `setContentArea`/`refresh` drive. From that tick on the
 * controller writes the axis and KSM no longer does — the sole writer
 * transitions KSM → controller with zero overlap, exactly the
 * `dormant-cr2 → attached-cr4` ledger transition CR2 established for
 * the touch axis.
 *
 * @see SpecialTouchHandlerInstaller — the same staged pattern for the
 *   touch axis (CR2).
 * @see VisibilityWriteAuditLogger
 * @see docs/plans/2026-05-15 - dictate-cutover-completion/research/render-path-cutover.md §6 RR-2 + §6.1
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §11.8 5c
 */
class RenderGate(
    /**
     * Stable owner tag for the audit ledger — the controller's simple
     * class name (matches the [VisibilityWriteAuditLogger.logWrite]
     * `caller` convention).
     */
    private val ownerTag: String,
    /**
     * The shared Strict-Mode audit logger. `null` is tolerated
     * (release builds / tests that don't exercise the audit) — the
     * gate's dormant/armed semantics still hold; only the
     * no-double-write *proof* is then unobserved.
     */
    private val auditLogger: VisibilityWriteAuditLogger?,
) {

    /**
     * `false` = dormant (CR3 default — do not write the live axis,
     * only report the intended write to the audit ledger).
     * `true` = armed (CR4 flips it — write the axis for real).
     */
    var armed: Boolean = false
        private set

    /**
     * CR4 entry point — arm the gate so the controller writes the live
     * axis from the next render-tick. MUST be called from the same
     * chunk that removes the legacy KSM drive for this axis (RR-2 —
     * never two live writers at once).
     */
    fun arm() {
        armed = true
    }

    /**
     * Decide whether the controller should perform a real visibility
     * write for [viewId] → [target], reporting the (intended or real)
     * write to the audit ledger first.
     *
     * Returns `true` iff the gate is [armed]; the controller does the
     * actual `view.visibility =` mutation only on `true`. On `false`
     * (dormant) the controller skips the mutation entirely — the
     * legacy KSM is the sole live writer and the audit ledger now
     * carries this controller's *intended* write under [ownerTag],
     * which is exactly what proves KSM is the only writer that
     * actually reached the view.
     *
     * @param viewId the `View.getId()` of the view about to be written
     *   (or that *would* be written, when dormant). Pass `0` only if
     *   the view truly has no id — the audit then keys on a synthetic
     *   slot and the proof is weaker, so prefer a real id.
     * @param target the visibility constant the controller intends.
     */
    fun shouldWrite(viewId: Int, target: Int): Boolean {
        // `live = armed`: a dormant gate reports a *suppressed* intended
        // write (observability only — never a double-write vs the
        // legacy KSM live write, RR-2). An armed gate (CR4) reports a
        // real live write, at which point KSM no longer writes this
        // axis so it is still the sole live writer.
        auditLogger?.logWrite(viewId, ownerTag, target, armed)
        return armed
    }
}
