package net.devemperor.dictate.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ViewTreeSerializer].
 *
 * Plain JVM — the whole point of the [UiNodeSnapshot] DTO is that the
 * interesting rules can be tested without an emulator or a live
 * `AccessibilityNodeInfo`.
 */
class ViewTreeSerializerTest {

    private fun node(
        className: String? = "android.widget.TextView",
        text: String? = null,
        contentDescription: String? = null,
        viewId: String? = null,
        isEditable: Boolean = false,
        redacted: Boolean = false,
        children: List<UiNodeSnapshot> = emptyList(),
    ) = UiNodeSnapshot(
        className = className,
        text = text,
        contentDescription = contentDescription,
        viewId = viewId,
        isEditable = isEditable,
        redacted = redacted,
        children = children,
    )

    // ── Privacy ─────────────────────────────────────────────────────────

    @Test
    fun `a redacted node prints the placeholder, never its text`() {
        // Defence in depth: the reader already dropped the text, so `text` is
        // null here. This pins that a redacted node is still ANNOUNCED (the
        // model should know a password field exists) without revealing it.
        val out = ViewTreeSerializer.serialize(
            node(
                className = "android.widget.EditText",
                viewId = "com.bank:id/pin",
                redacted = true,
                isEditable = true,
            ),
        )!!
        assertTrue(out.contains(ViewTreeSerializer.REDACTED))
        assertTrue("the field should still be visible as a field", out.contains("#pin"))
    }

    @Test
    fun `a redacted node cannot leak text even if one is somehow present`() {
        // If a future reader bug let text through on a redacted node, this
        // class must not print it. Redaction is checked BEFORE text.
        val out = ViewTreeSerializer.serialize(
            node(text = "hunter2", contentDescription = "Password", redacted = true),
        )!!
        assertFalse("the secret must never reach the prompt", out.contains("hunter2"))
        assertFalse("a description can carry it too", out.contains("Password"))
        assertTrue(out.contains(ViewTreeSerializer.REDACTED))
    }

    // ── Pruning ─────────────────────────────────────────────────────────

    @Test
    fun `layout scaffolding is dropped but its children survive`() {
        val out = ViewTreeSerializer.serialize(
            node(
                className = "android.widget.FrameLayout",
                children = listOf(
                    node(
                        className = "android.widget.LinearLayout",
                        children = listOf(node(text = "Hello")),
                    ),
                ),
            ),
        )!!
        assertEquals("TextView \"Hello\"", out)
    }

    @Test
    fun `an empty tree yields null rather than an empty block`() {
        // An empty <ui-context> costs tokens and says nothing.
        assertNull(ViewTreeSerializer.serialize(node(className = "android.widget.FrameLayout")))
    }

    @Test
    fun `null root yields null`() {
        assertNull(ViewTreeSerializer.serialize(null))
    }

    @Test
    fun `an editable node survives even with no text`() {
        // An empty input field is exactly what the user is about to dictate
        // into — the most relevant node on screen.
        val out = ViewTreeSerializer.serialize(
            node(className = "android.widget.EditText", isEditable = true),
        )!!
        assertEquals("EditText (editable)", out)
    }

    // ── Shape ───────────────────────────────────────────────────────────

    @Test
    fun `nesting is expressed as indentation`() {
        val out = ViewTreeSerializer.serialize(
            node(
                className = "android.widget.LinearLayout",
                viewId = "com.example:id/root",
                children = listOf(node(text = "Child")),
            ),
        )!!
        assertEquals("LinearLayout #root\n  TextView \"Child\"", out)
    }

    @Test
    fun `class and id are shortened to their last segment`() {
        val out = ViewTreeSerializer.serialize(
            node(className = "android.widget.Button", viewId = "com.example:id/send", text = "Send"),
        )!!
        // The package prefixes are pure token cost — they are the same on
        // every line and carry nothing the model needs.
        assertEquals("Button #send \"Send\"", out)
    }

    // ── Budget ──────────────────────────────────────────────────────────

    @Test
    fun `a pathological tree is truncated`() {
        // ADR-0012 persists the built message verbatim, so an unbounded block
        // would bloat the DB row as well as the request.
        val many = (1..2000).map { node(text = "row $it padded out to be wide") }
        val out = ViewTreeSerializer.serialize(node(className = "x.Root", children = many))!!

        assertTrue("must respect the ceiling", out.length <= ViewTreeSerializer.MAX_CHARS + 64)
        assertTrue("truncation must be announced", out.endsWith(ViewTreeSerializer.TRUNCATION_MARKER))
    }

    @Test
    fun `truncation cuts on a line boundary`() {
        // Half a line reads as a truncated *value* and would misinform the
        // model about what is on screen.
        val many = (1..2000).map { node(text = "row $it") }
        val out = ViewTreeSerializer.serialize(node(className = "x.Root", children = many))!!
        val body = out.removeSuffix("\n" + ViewTreeSerializer.TRUNCATION_MARKER)
        for (line in body.lines()) {
            assertTrue("dangling partial line: $line", line.isEmpty() || line.trim().endsWith("\""))
        }
    }
}
