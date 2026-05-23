package net.devemperor.dictate.state.render.overlay

/**
 * Test double for [OverlayPermissionGate] — every method backed by an
 * in-memory value so tests can:
 *
 *  - Toggle [hasPermission] to simulate the user granting / revoking
 *    the system permission.
 *  - Toggle [onboardingShown] / [permanentlyDenied] to drive
 *    [shouldShowOnboarding] across the §5.4 "first-time vs
 *    permanently-denied" matrix.
 *
 * Field names mirror the production gate's persisted state so reading
 * test code is a 1:1 mapping to the spec table.
 *
 * @see DefaultOverlayPermissionGate
 * @see net.devemperor.dictate.state.render.overlay.OverlayPermissionObserver
 */
class FakeOverlayPermissionGate(
    var hasPermission: Boolean = false,
    var permanentlyDenied: Boolean = false,
) : OverlayPermissionGate {

    /** Records each [markOnboardingShown] call. */
    var onboardingShownCalls: Int = 0
        private set

    /** Records each [markPermanentlyDenied] call. */
    var markPermanentlyDeniedCalls: Int = 0
        private set

    override fun hasOverlayPermission(): Boolean = hasPermission

    override fun shouldShowOnboarding(): Boolean =
        !hasPermission && !permanentlyDenied

    override fun markOnboardingShown() {
        onboardingShownCalls++
    }

    override fun markPermanentlyDenied() {
        markPermanentlyDeniedCalls++
        permanentlyDenied = true
    }
}
