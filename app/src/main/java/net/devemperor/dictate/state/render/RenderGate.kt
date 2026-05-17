package net.devemperor.dictate.state.render

import net.devemperor.dictate.core.audit.VisibilityWriteAuditLogger

/**
 * The staged-safety-net "dormant vs armed" switch for a visibility
 * RenderBackend (render-path-cutover.md §6 RR-2 + §6.1 staged safety
 * net; Spec 2 §11.8 5c).
 *
 * # Why a gate (RR-2 — the highest risk of Block B5) — historical rationale
 *
 * This explains *why* the gate exists; the CR3→CR4→CR-DEL timeline it
 * describes is **history** — `KeyboardStateManager` is now **deleted**
 * (CR-DEL completed the D-13 migration) and the gated controllers are
 * the sole live owners of their axes. During the cutover Theme C-R
 * attached [ContentAreaController] / [PromptVisibilityController] /
 * [OverlayResetHandler] in **CR3**, while the legacy
 * [net.devemperor.dictate.core.KeyboardStateManager] `applyVisibility()`
 * drive that wrote the *same* visibility axes was removed only in
 * **CR4**. A visibility write — unlike a touch listener — is not an
 * "Android keeps the most-recent" overwrite; it is a *repeated write*
 * to the same field. Had a controller written the axis while KSM still
 * drove it, **both** would have mutated the container every render-tick:
 * the keyboard flickering or settling on the wrong container, with **no
 * error** (the F-1/F-2 silent-regression class at the visibility
 * layer).
 *
 * The mitigation mirrored CR1's RESEND-only long-press model and CR2's
 * `SpecialTouchHandlerInstaller.installDormant`: the new owner was
 * **attached** (wiring proven, view-recreate-safe, CR4 a one-line flip)
 * but **dormant** — it did **not** write the live axis until CR4
 * [arm]ed it *in the same chunk* that removed the legacy KSM drive;
 * CR-DEL then deleted KSM entirely. Never two live writers on one axis
 * at once. The dormant/armed switch survives as the live API (a `null`
 * gate / armed gate are still meaningful for unit tests and
 * release-build configurations — see [shouldWrite]).
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
 * # CR4 flip — historical
 *
 * This is the staged transition recorded as history (the cutover is
 * complete — see the historical rationale above). CR4 called [arm] on
 * each controller in the **same** chunk it removed the KSM
 * `setContentArea`/`refresh` drive. From that tick on the controller
 * wrote the axis and KSM no longer did — the sole writer transitioned
 * KSM → controller with zero overlap, exactly the
 * `dormant-cr2 → attached-cr4` ledger transition CR2 established for
 * the touch axis. CR-DEL then deleted `KeyboardStateManager`, leaving
 * each controller the permanent sole owner of its axis.
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
     * `false` = dormant (do not write the live axis, only report the
     * intended write to the audit ledger).
     * `true` = armed (write the axis for real).
     *
     * Historically the dormant→armed flip was the CR3→CR4 cutover
     * transition; post-CR-DEL the live owners are armed in production
     * and the dormant mode survives only as a unit-test / audit-proof
     * configuration.
     */
    var armed: Boolean = false
        private set

    /**
     * Arm the gate so the controller writes the live axis from the
     * next render-tick. (Historically the CR4 entry point — it had to
     * be called from the same chunk that removed the legacy KSM drive
     * for this axis, RR-2, never two live writers at once. KSM is now
     * deleted; production callers arm at construction/attach time.)
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
     * (dormant) the controller skips the mutation entirely and the
     * audit ledger carries only this controller's *intended* write
     * under [ownerTag]. Historically that proved the legacy KSM was the
     * sole writer that actually reached the view during the CR3 dormant
     * phase; post-CR-DEL it remains the audit-proof / unit-test
     * configuration (no legacy writer exists any more).
     *
     * @param viewId the `View.getId()` of the view about to be written
     *   (or that *would* be written, when dormant). Pass `0` only if
     *   the view truly has no id — the audit then keys on a synthetic
     *   slot and the proof is weaker, so prefer a real id.
     * @param target the visibility constant the controller intends.
     */
    fun shouldWrite(viewId: Int, target: Int): Boolean {
        // `live = armed`: a dormant gate reports a *suppressed* intended
        // write (observability only — historically never a double-write
        // vs the legacy KSM live write, RR-2). An armed gate reports a
        // real live write; post-CR-DEL the controller is the permanent
        // sole live writer of its axis (KSM is deleted).
        auditLogger?.logWrite(viewId, ownerTag, target, armed)
        return armed
    }
}
