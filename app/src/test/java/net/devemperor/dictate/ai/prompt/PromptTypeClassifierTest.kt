package net.devemperor.dictate.ai.prompt

import net.devemperor.dictate.database.entity.PromptType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM coverage for [PromptTypeClassifier] — the single trim+strip rule the
 * JSON-import fallback and the SQL migration [MIGRATION_10_11] both mirror.
 *
 * The cases here are deliberately the same shapes `MigrationTo11Test` asserts on
 * the SQL side; the two suites together pin the classifier ↔ migration
 * equivalence (plan §4.2).
 */
class PromptTypeClassifierTest {

    @Test
    fun `fully bracketed prompt becomes TEXT with brackets stripped`() {
        assertEquals(PromptType.TEXT to "Beste Grüße", PromptTypeClassifier.classify("[Beste Grüße]"))
    }

    @Test
    fun `surrounding whitespace is trimmed before stripping`() {
        assertEquals(PromptType.TEXT to "x", PromptTypeClassifier.classify("  [x]  "))
    }

    @Test
    fun `empty brackets classify as TEXT with empty content`() {
        assertEquals(PromptType.TEXT to "", PromptTypeClassifier.classify("[]"))
    }

    @Test
    fun `plain instruction stays a PROMPT unchanged`() {
        assertEquals(PromptType.PROMPT to "Make it formal", PromptTypeClassifier.classify("Make it formal"))
    }

    @Test
    fun `partially bracketed text is not TEXT`() {
        assertEquals(PromptType.PROMPT to "prefix [x]", PromptTypeClassifier.classify("prefix [x]"))
    }

    @Test
    fun `edge case - bracketed with inner brackets keeps the simple rule (F4)`() {
        // "[a] und [b]" trims to a bracketed string → TEXT, inner = "a] und [b".
        assertEquals(PromptType.TEXT to "a] und [b", PromptTypeClassifier.classify("[a] und [b]"))
    }

    @Test
    fun `null prompt stays PROMPT with null payload`() {
        assertEquals(PromptType.PROMPT to null, PromptTypeClassifier.classify(null))
    }

    @Test
    fun `single bracket char is not enough to be TEXT`() {
        assertEquals(PromptType.PROMPT to "[", PromptTypeClassifier.classify("["))
    }

    @Test
    fun `stripName removes brackets from a fully bracketed name`() {
        assertEquals("Dictate is great", PromptTypeClassifier.stripName("[Dictate is great]"))
    }

    @Test
    fun `stripName leaves a plain name unchanged`() {
        assertEquals("Formalize", PromptTypeClassifier.stripName("Formalize"))
    }
}
