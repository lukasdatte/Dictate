package net.devemperor.dictate.state.render.overlay

import android.view.View
import android.view.WindowManager

/**
 * Hand-rolled fake [OverlayWindow] used by JVM unit tests (K-1).
 *
 * Records every attach / update / detach call so tests can assert on
 * the lifecycle without going through real `WindowManager`. The
 * `attached` flag is the canonical state the [OverlayBackend]'s render
 * path reads.
 *
 * # Permission-revoke simulation
 *
 * Set [simulateBadTokenOnAttach] = `true` to mimic
 * [android.view.WindowManager.BadTokenException] — the next [attach]
 * call returns without flipping `attached`, mirroring the production
 * `AndroidOverlayWindow.attach` catch path.
 *
 * @see OverlayWindow
 * @see net.devemperor.dictate.state.render.overlay.OverlayBackend
 */
class FakeOverlayWindow : OverlayWindow {

    private var attached: Boolean = false

    /**
     * When `true`, the next [attach] call leaves the wrapper in the
     * detached state — used to test the BadToken/permission-revoke
     * branch in [OverlayBackend].
     */
    var simulateBadTokenOnAttach: Boolean = false

    /** Recorded call log: `("attach" | "update" | "detach", viewIdentity)` per call. */
    val events: MutableList<String> = mutableListOf()

    /** Last [WindowManager.LayoutParams] passed to [attach] or [update]. */
    var lastParams: WindowManager.LayoutParams? = null
        private set

    /**
     * The most recently attached View. Tests use this to walk the
     * inflated tree (`findViewById`) without needing access to the
     * backend's private fields. `null` until the first successful
     * [attach].
     */
    var lastAttachedView: View? = null
        private set

    override fun isAttached(): Boolean = attached

    override fun attach(view: View, params: WindowManager.LayoutParams) {
        events += "attach"
        if (simulateBadTokenOnAttach) {
            // Mirror production: leave attached=false, swallow.
            return
        }
        lastParams = params
        lastAttachedView = view
        attached = true
    }

    override fun update(view: View, params: WindowManager.LayoutParams) {
        events += "update"
        if (!attached) return
        lastParams = params
    }

    override fun detach(view: View) {
        events += "detach"
        attached = false
        // Keep lastAttachedView non-null so tests can still inspect
        // the previously-attached tree after a detach (cleared on next
        // attach).
    }
}
