package net.devemperor.dictate.state.render.overlay

/**
 * Permission + onboarding gate for the floating overlay (Spec 3 §5.1).
 *
 * # SRP — separated from render
 *
 * The gate bundles the system-permission check
 * (`Settings.canDrawOverlays`) with the persisted onboarding state
 * (was the rationale-prompt shown? did the user permanently deny?). The
 * `OverlayBackend` reads `state.overlay.hasPermission` directly from the
 * [net.devemperor.dictate.state.DictateUiState] axis — `OverlayPermissionObserver`
 * is the writer side that keeps the axis in sync — but the **decision
 * surface** (show onboarding now? open Settings now?) lives here.
 *
 * The interface lives in C16 as a contract; the production
 * `DefaultOverlayPermissionGate` (`Settings.canDrawOverlays` +
 * `SharedPreferences`-backed onboarding flags) is contributed in C17.
 *
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md §5.1
 */
interface OverlayPermissionGate {
    /** `true` when the OS-level `SYSTEM_ALERT_WINDOW` permission is granted. */
    fun hasOverlayPermission(): Boolean

    /**
     * `true` when the next WIDGET-toggle should bring up the
     * onboarding rationale (first-time + not permanently dismissed).
     */
    fun shouldShowOnboarding(): Boolean

    /** Record that the onboarding rationale was shown to the user. */
    fun markOnboardingShown()

    /**
     * Record that the user permanently declined the overlay onboarding.
     * Subsequent [shouldShowOnboarding] returns `false`.
     */
    fun markPermanentlyDenied()
}

/**
 * A safe-by-default [OverlayPermissionGate] used between C16
 * (interface introduction) and C17 (real implementation).
 *
 * Always reports "no permission, no onboarding to show" — the overlay
 * backend's render path then falls through to the no-overlay fallback
 * and nothing is attached. This keeps C16 wirable end-to-end (the
 * backend can be constructed) without forcing the C17 implementation to
 * land in the same chunk.
 *
 * **Replace in C17** with `DefaultOverlayPermissionGate` per Spec 3 §5.1.
 */
object NoOverlayPermissionGate : OverlayPermissionGate {
    override fun hasOverlayPermission(): Boolean = false
    override fun shouldShowOnboarding(): Boolean = false
    override fun markOnboardingShown() = Unit
    override fun markPermanentlyDenied() = Unit
}
