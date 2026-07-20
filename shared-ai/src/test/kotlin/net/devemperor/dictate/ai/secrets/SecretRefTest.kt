package net.devemperor.dictate.ai.secrets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The port's only piece of behaviour: [SecretRef]'s handle derivation and its
 * validation invariants (the store derives a filesystem-safe handle from it,
 * so the charset guard must be total). Spec secretstore.md §4.2, §10.
 */
class SecretRefTest {

    @Test
    fun handle_isNamespaceSlashId() {
        assertEquals(
            "legacy/transcription_api_key_openai",
            SecretRef("legacy", "transcription_api_key_openai").handle,
        )
        assertEquals("credential/9f1c-uuid", SecretRef("credential", "9f1c-uuid").handle)
    }

    @Test
    fun namespace_allowsLetterDigitUnderscoreHyphen() {
        // No throw is the assertion — these are the four legal shapes the migration and entity
        // model produce (namespaces "legacy", "credential", "pairing", plus hyphen/digit forms).
        SecretRef("legacy", "x")
        SecretRef("pairing_v2", "x")
        SecretRef("cred-1", "x")
        SecretRef("ns0", "x")
    }

    @Test
    fun blankNamespace_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { SecretRef("", "id") }
        assertThrows(IllegalArgumentException::class.java) { SecretRef("   ", "id") }
    }

    @Test
    fun blankId_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { SecretRef("ns", "") }
        assertThrows(IllegalArgumentException::class.java) { SecretRef("ns", "  ") }
    }

    @Test
    fun namespaceWithFilesystemUnsafeChars_isRejected() {
        // A slash, dot or space in the namespace would break the handle-to-file mapping the
        // platform stores rely on — the guard keeps it total.
        assertThrows(IllegalArgumentException::class.java) { SecretRef("bad/ns", "id") }
        assertThrows(IllegalArgumentException::class.java) { SecretRef("bad.ns", "id") }
        assertThrows(IllegalArgumentException::class.java) { SecretRef("bad ns", "id") }
    }
}
