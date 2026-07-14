package net.devemperor.dictate.accessibility

import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Converts the foreground app's live view tree into an Android-free
 * [UiNodeSnapshot] tree, **redacting sensitive fields on the way out**.
 *
 * This is the only class that touches [AccessibilityNodeInfo]. Everything
 * downstream — the serializer, the prompt builder — sees plain data, which is
 * what ADR-0012 requires (the turn builder is Android-free) and what makes the
 * redaction rules testable.
 *
 * # Redaction is done here, not later, and not by the platform
 *
 * The platform strips password text from accessibility *events*, but a node
 * fetched by tree traversal can still hand back the actual characters — that
 * asymmetry is a documented attack surface, not a quirk, and its behaviour
 * varies across versions and OEMs. So: if a node looks like a secret, its text
 * is dropped **at the point of reading**. Nothing downstream can leak what was
 * never copied out.
 *
 * "Looks like a secret" is deliberately generous — `isPassword`, every
 * password input-type variation, plus the personal-data variations (email,
 * postal address, phone). A missed redaction ships someone's password to an
 * API; an over-redaction costs the model one line of context. The trade is not
 * close.
 *
 * Windows marked `FLAG_SECURE` never appear here at all: the platform withholds
 * them from accessibility retrieval entirely, so banking apps and the like are
 * invisible without any cooperation from this code.
 *
 * # Threading and cost
 *
 * Every level of traversal is a synchronous IPC into the other app's process,
 * so this is not free and must not run on a UI thread. [MAX_DEPTH] and
 * [MAX_NODES] bound it: a web view can otherwise present thousands of nodes,
 * and the caller is a user waiting for a dictation to send.
 *
 * # Why the app's window and not "the active window"
 *
 * The IME window is `TYPE_INPUT_METHOD` and never takes input focus, so
 * `getRootInActiveWindow()` already returns the app underneath rather than
 * Dictate's own keyboard. The [AccessibilityWindowInfo] fallback exists for
 * the cases where that is not true (split screen, an overlay holding focus):
 * pick the focused `TYPE_APPLICATION` window explicitly rather than trust the
 * default.
 */
object AccessibilityContextReader {

    /** Deepest traversal level. Beyond this, trees are scaffolding anyway. */
    const val MAX_DEPTH = 12

    /** Ceiling on visited nodes — the guard against a web view's DOM. */
    const val MAX_NODES = 600

    /**
     * Read the foreground app's tree, or `null` when there is nothing to read:
     * the service is not connected, the window is secure, or the tree is empty.
     *
     * Must NOT be called from the main thread (see "Threading and cost").
     */
    fun read(service: DictateAccessibilityService?): UiNodeSnapshot? {
        val root = rootNode(service ?: return null) ?: return null
        return snapshot(root, depth = 0, budget = intArrayOf(MAX_NODES))
    }

    /**
     * The foreground application window's root: the focused `TYPE_APPLICATION`
     * window if one is reachable, else whatever the platform calls active.
     */
    private fun rootNode(service: DictateAccessibilityService): AccessibilityNodeInfo? {
        val appWindowRoot = runCatching {
            service.windows
                ?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
                ?.root
        }.getOrNull()
        // `windows` needs flagRetrieveInteractiveWindows and can legitimately
        // be empty; the active window is the normal path, not the fallback.
        return appWindowRoot ?: runCatching { service.rootInActiveWindow }.getOrNull()
    }

    /**
     * @param budget single-element node budget, shared across the recursion —
     *   a plain Int would be copied per frame and bound nothing.
     */
    private fun snapshot(
        node: AccessibilityNodeInfo,
        depth: Int,
        budget: IntArray,
    ): UiNodeSnapshot? {
        if (depth > MAX_DEPTH || budget[0] <= 0) return null
        budget[0]--

        val sensitive = isSensitive(node)
        val children = ArrayList<UiNodeSnapshot>(node.childCount)
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            snapshot(child, depth + 1, budget)?.let(children::add)
        }

        return UiNodeSnapshot(
            className = node.className?.toString(),
            // The redaction: a sensitive node's text is never copied out of the
            // node, so no later stage can print it by mistake.
            text = if (sensitive) null else node.text?.toString(),
            contentDescription = if (sensitive) null else node.contentDescription?.toString(),
            viewId = runCatching { node.viewIdResourceName }.getOrNull(),
            isEditable = node.isEditable,
            isPassword = node.isPassword,
            redacted = sensitive,
            children = children,
        )
    }

    /**
     * Whether this node's content must be withheld.
     *
     * Errs towards withholding: a false negative sends a password to a model
     * provider, a false positive costs one line of screen context.
     */
    private fun isSensitive(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        val inputType = runCatching { node.inputType }.getOrDefault(0)
        if (inputType == 0) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val klass = inputType and InputType.TYPE_MASK_CLASS
        return when {
            klass == InputType.TYPE_CLASS_TEXT && variation in SENSITIVE_TEXT_VARIATIONS -> true
            klass == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD -> true
            // A phone field is a phone number: personal data with no variation
            // flag of its own to test.
            klass == InputType.TYPE_CLASS_PHONE -> true
            else -> false
        }
    }

    private val SENSITIVE_TEXT_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
    )
}
