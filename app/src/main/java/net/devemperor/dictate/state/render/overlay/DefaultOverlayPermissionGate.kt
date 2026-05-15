package net.devemperor.dictate.state.render.overlay

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import net.devemperor.dictate.preferences.put

/**
 * Production [OverlayPermissionGate] — wraps
 * [Settings.canDrawOverlays] for the system-permission check and a
 * pair of typed [Pref] flags for the onboarding state machine
 * (Spec 3 §5.1).
 *
 * # Responsibilities
 *
 *  - **Permission check** ([hasOverlayPermission]) — single live read
 *    of [Settings.canDrawOverlays]. Reducers DO NOT call this; they
 *    consume the mirrored `state.overlay.hasPermission` axis kept
 *    fresh by [OverlayPermissionObserver]. This gate is the live
 *    source for non-reducer consumers (Onboarding-Trigger, Activity
 *    flows).
 *  - **Onboarding state** ([shouldShowOnboarding] +
 *    [markOnboardingShown] + [markPermanentlyDenied]) — persisted via
 *    [Pref.OverlayOnboardingShown] + [Pref.OverlayOnboardingDismissed]
 *    so the in-IME info-bar is shown at the right moments (first
 *    encounter + permission still missing + user has not permanently
 *    declined).
 *
 * # Pref bindings (per `CLAUDE.md` convention — always go through
 *   the typed [Pref] sealed class, never raw string keys)
 *
 *  - [Pref.OverlayOnboardingShown] — boolean, `true` once the
 *    onboarding rationale has been surfaced at least once. Set by
 *    [markOnboardingShown]; today not read by this gate (it would
 *    only matter if we changed the "first time" semantics to require
 *    `!shown`, but the spec requires the bar to keep appearing while
 *    the permission is missing and not permanently dismissed —
 *    §13.3 Permissions-Logik).
 *  - [Pref.OverlayOnboardingDismissed] — boolean, `true` once the
 *    user explicitly tapped "Later" / dismissed permanently. Read by
 *    [shouldShowOnboarding]; set by [markPermanentlyDenied].
 *
 * # SRP separation from render (Spec 3 §13.2)
 *
 * The gate decides "should the onboarding bar be visible right now?"
 * — the render backend reads `state.overlay.onboardingPending` (set
 * by the reducer in reaction to the user clicking the disabled
 * WIDGET-toggle). The gate is the persistence + system-permission
 * adapter; it has no opinion on the View-tree.
 *
 * @property ctx Android Context — used only for
 *   [Settings.canDrawOverlays]; the gate doesn't hold UI references.
 * @property prefs the app's [SharedPreferences] — must be the same
 *   instance that [net.devemperor.dictate.state.modules.OverlayModule]
 *   writes to via `Effect.MarkOnboardingShown` /
 *   `Effect.MarkOnboardingPermanentlyDismissed` (the gate reads what
 *   the module persists).
 *
 * @see OverlayPermissionGate
 * @see OverlayPermissionObserver
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md §5.1
 */
class DefaultOverlayPermissionGate(
    private val ctx: Context,
    private val prefs: SharedPreferences,
) : OverlayPermissionGate {

    override fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(ctx)

    override fun shouldShowOnboarding(): Boolean =
        !hasOverlayPermission() && !prefs.get(Pref.OverlayOnboardingDismissed)

    override fun markOnboardingShown() {
        prefs.edit()
            .put(Pref.OverlayOnboardingShown, true)
            .apply()
    }

    override fun markPermanentlyDenied() {
        prefs.edit()
            .put(Pref.OverlayOnboardingDismissed, true)
            .apply()
    }
}
