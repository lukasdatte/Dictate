package net.devemperor.dictate.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Opt-in accessibility service that lets Dictate read the foreground app's
 * view tree, so a dictation can be post-processed with the screen the user is
 * looking at as context.
 *
 * # It consumes no events
 *
 * [onAccessibilityEvent] is deliberately empty. The platform requires
 * `accessibilityEventTypes` to be declared, but this service is a **pull**:
 * the IME asks for the tree at the moment the user sends a dictation. Reacting
 * to events would mean processing a constant stream of other apps' UI changes —
 * strictly more data, more battery, and more privacy exposure, for a snapshot
 * we can take on demand.
 *
 * # The static instance
 *
 * A live reference set in [onServiceConnected] and cleared on teardown is the
 * only reliable answer to "can I read *right now*". `AccessibilityManager`'s
 * enabled-service list and `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
 * both report the user's *setting*, which can be true while the service is not
 * yet bound (right after enabling, or after the system restarts it) — and in
 * that window `getRootInActiveWindow()` returns null. Binding is the fact that
 * matters, so binding is what is tracked.
 *
 * The reference is to a Service (an Android-managed object), not an Activity,
 * and it is nulled on both unbind and destroy, so it does not leak a context
 * beyond the service's own lifetime.
 *
 * # Privacy
 *
 * The service is worthless unless the user enables it in system settings, and
 * Dictate never asks unless the feature is opted into
 * ([net.devemperor.dictate.preferences.Pref.AccessibilityContextEnabled]).
 * Windows marked `FLAG_SECURE` (banking apps and similar) are withheld by the
 * platform itself and are invisible here. Everything else is redacted by
 * [AccessibilityContextReader] before it can leave the device.
 *
 * @see AccessibilityContextReader
 */
class DictateAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty — see "It consumes no events" in the class KDoc.
    }

    override fun onInterrupt() {
        // Nothing to interrupt: no feedback is produced, no work is queued.
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        /**
         * The connected service, or `null` when it is not bound — i.e. when a
         * tree cannot be read no matter what the settings say.
         *
         * Written on the main thread by the framework callbacks; read from the
         * IME's main thread at send-tap. `@Volatile` documents the
         * cross-thread visibility rather than relying on that alignment
         * holding forever.
         */
        @Volatile
        @JvmStatic
        var instance: DictateAccessibilityService? = null
            private set

        /** Whether a view tree can be read right now. */
        @JvmStatic
        fun isConnected(): Boolean = instance != null
    }
}
