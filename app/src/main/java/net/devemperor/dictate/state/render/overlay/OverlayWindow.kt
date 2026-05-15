package net.devemperor.dictate.state.render.overlay

import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Thin abstraction over the Android `WindowManager` surface that the
 * floating overlay needs.
 *
 * # Why an interface?
 *
 * `android.view.WindowManager` is a system service — instantiating one
 * from a plain JVM unit test requires Robolectric. The overlay-backend
 * code path is small (attach / update / detach) and the rest of the
 * backend has no other reason to touch `WindowManager`, so wrapping the
 * three methods behind a DIP-style interface keeps the backend testable
 * with a hand-rolled fake and the production implementation a
 * single-screen pass-through.
 *
 * # Lifecycle-idempotency contract (Spec 3 §4.1, Phase-B S-8)
 *
 * The wrapper is the **single SRP-home for `WindowManager` exception
 * handling**. Two failure modes are caught here so the backend never has
 * to know about a `WindowManager` exception type (DIP):
 *
 *  - [attach] catches `BadTokenException` — the user revoked the overlay
 *    permission between the backend's last permission check and our
 *    `addView` call. The wrapper sets `attached = false`; the backend
 *    sees `isAttached() == false` on the next render and routes through
 *    the fallback path.
 *  - [update] catches `IllegalArgumentException` — the system detached
 *    the view OS-side (e.g. runtime permission revoke); the wrapper sets
 *    `attached = false`. The next render-tick attempts a clean
 *    re-attach which then bails at the permission gate.
 *  - [detach] catches `IllegalArgumentException` — the view was already
 *    OS-detached when we removed it.
 *
 * All three methods are **idempotent**: re-calling without an
 * intervening state-change is a no-op.
 *
 * @see net.devemperor.dictate.state.render.overlay.OverlayBackend
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md §4.1
 */
interface OverlayWindow {
    /** `true` between a successful [attach] and a subsequent [detach]. */
    fun isAttached(): Boolean

    /**
     * Attach [view] to the WindowManager with the given [params].
     *
     * No-op when already attached. On `BadTokenException` (permission
     * revoked at runtime) the call returns silently and [isAttached]
     * stays `false` — the backend MUST read [isAttached] after
     * [attach] to decide whether to proceed.
     */
    fun attach(view: View, params: WindowManager.LayoutParams)

    /**
     * Update an attached [view]'s layout params (position, size).
     *
     * No-op when not attached. On `IllegalArgumentException` (view
     * already OS-detached) flips `isAttached() → false` so the next
     * `attach` round-trips through the permission gate cleanly.
     */
    fun update(view: View, params: WindowManager.LayoutParams)

    /**
     * Detach [view] from the WindowManager.
     *
     * Idempotent: no-op when not attached. `IllegalArgumentException`
     * (view never attached, or already removed) is swallowed.
     */
    fun detach(view: View)
}

/**
 * Production [OverlayWindow] bound to the real Android `WindowManager`.
 *
 * @see OverlayWindow
 */
class AndroidOverlayWindow(
    private val windowManager: WindowManager,
) : OverlayWindow {

    private var attached: Boolean = false

    override fun isAttached(): Boolean = attached

    override fun attach(view: View, params: WindowManager.LayoutParams) {
        if (attached) return
        try {
            windowManager.addView(view, params)
            attached = true
        } catch (e: WindowManager.BadTokenException) {
            // Permission was revoked between the backend's permission
            // check and our `addView` call. `attached` stays false; the
            // backend's next render-tick re-evaluates `state.overlay.
            // hasPermission` and routes through the fallback path.
            Log.w(TAG, "addView failed — overlay permission revoked at runtime?", e)
            attached = false
        }
    }

    override fun update(view: View, params: WindowManager.LayoutParams) {
        if (!attached) return
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: IllegalArgumentException) {
            // Race: the system detached our view (e.g. permission
            // revoke) before our wrapper flipped `attached`. Mark
            // detached so the next render re-runs the attach path; that
            // path will bail at the permission gate.
            Log.w(TAG, "updateViewLayout on detached view — was OS-detached?", e)
            attached = false
        }
    }

    override fun detach(view: View) {
        if (!attached) return
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            // View was not (or no longer) attached — e.g. after
            // permission-revoke. Idempotent.
            Log.w(TAG, "removeView on already-detached view", e)
        }
        attached = false
    }

    private companion object {
        const val TAG: String = "AndroidOverlayWindow"
    }
}
