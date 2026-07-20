package net.devemperor.dictate.companion.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the cross-platform credential namespace. The literal MUST equal `:app`'s
 * `ConfigSecrets.CREDENTIAL_NAMESPACE` (`"credential"`): a credential entered on the phone or delivered
 * from a peer (Block E) is stored under `SecretRef("credential", <id>)`, and the companion resolver
 * ([ProfileBackedAiConfig.apiKey]) must read it back under the same handle. A silent divergence here
 * would make every cross-platform key resolve to `""`.
 */
class CredentialSecretsTest {

    @Test
    fun namespace_matchesTheCrossPlatformCredentialNamespace() {
        assertEquals("credential", CredentialSecrets.CREDENTIAL_NAMESPACE)
    }

    @Test
    fun credentialRef_buildsANamespacedHandle() {
        val ref = CredentialSecrets.credentialRef("abc-123")
        assertEquals("credential", ref.namespace)
        assertEquals("abc-123", ref.id)
        assertEquals("credential/abc-123", ref.handle)
    }
}
