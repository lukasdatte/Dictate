package net.devemperor.dictate.accessibility

/**
 * An Android-free snapshot of one node of the foreground app's view tree.
 *
 * # Why a DTO instead of passing AccessibilityNodeInfo around
 *
 * Two reasons, both load-bearing:
 *
 *  1. **ADR-0012** requires the prompt to be built by an Android-free
 *     `ConversationTurnBuilder`. A live `AccessibilityNodeInfo` could never
 *     reach it, so the tree has to become plain data at the boundary anyway.
 *  2. `AccessibilityNodeInfo` is a live handle backed by IPC into the other
 *     app's process — reading it off the accessibility thread is a
 *     correctness problem, and it cannot be constructed in a JVM test. The
 *     redaction rules are the part most worth testing exhaustively, so they
 *     operate on this instead.
 *
 * [AccessibilityContextReader] converts; [ViewTreeSerializer] consumes.
 *
 * @property className the node's widget class, e.g. `android.widget.Button`.
 * @property text the node's visible text — **already redaction-checked**:
 *   `null` here means "nothing to show", never "we dropped it silently"; a
 *   dropped secret is represented by [redacted].
 * @property contentDescription the node's a11y description. Can itself carry
 *   PII, so it is subject to the same redaction as [text].
 * @property viewId the node's `resource-id` (needs `flagReportViewIds`), e.g.
 *   `com.example:id/username`. Cheap and unusually informative for a model —
 *   it is often the only place a field's *purpose* is named.
 * @property isEditable whether the node accepts input.
 * @property isPassword whether the node is a password field.
 * @property redacted `true` when this node's text was withheld because the
 *   field is sensitive. Kept as an explicit flag rather than just emitting a
 *   placeholder so the serializer can decide how to present it and tests can
 *   assert on the decision rather than on a string.
 * @property children in traversal order.
 */
data class UiNodeSnapshot(
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val isEditable: Boolean = false,
    val isPassword: Boolean = false,
    val redacted: Boolean = false,
    val children: List<UiNodeSnapshot> = emptyList(),
)
