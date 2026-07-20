package net.devemperor.dictate.companion.ai

import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.secrets.SecretRef
import net.devemperor.dictate.ai.secrets.SecretStore
import net.devemperor.dictate.companion.data.CompanionConfigRepository
import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.shared.config.ModelFunction
import net.devemperor.dictate.shared.config.ModelRefEntity
import net.devemperor.dictate.shared.config.ProfileEntity
import net.devemperor.dictate.shared.config.ProviderConfigEntity
import net.devemperor.dictate.shared.config.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The desktop twin of `ProfileResolverCharacterizationTest` — [ProfileBackedAiConfig] resolves the
 * effective AI config from the active profile + its SecretStore credential (desktop-host.md §9), and
 * the §9.3 fallbacks never crash. This is the regression guard for the finding that the desktop
 * pipeline ran against a hard-coded empty key: a seeded profile now yields a real provider/model/key.
 */
class ProfileBackedAiConfigTest {

    private val database = CompanionDatabase.inMemory()
    private val config = CompanionConfigRepository(database, now = { 1L })
    private val secretStore = FakeSecretStore()
    private var activeProfileId: String? = "prof-1"

    private val resolver = ProfileBackedAiConfig(
        config = config,
        secretStore = secretStore,
        activeProfileId = { activeProfileId },
    )

    private fun seedFullProfile(credentialRef: String? = "cred-1") {
        config.save(
            ProviderConfigEntity(
                id = "p1", providerType = ProviderType.OPENAI, label = "OpenAI",
                baseUrl = null, credentialRef = credentialRef,
            )
        )
        config.save(
            ModelRefEntity(id = "m-trans", providerRef = "p1", modelId = "whisper-1", function = ModelFunction.TRANSCRIPTION)
        )
        config.save(
            ModelRefEntity(
                id = "m-comp", providerRef = "p1", modelId = "gpt-4o-mini", function = ModelFunction.COMPLETION,
                parameterDefaults = mapOf("temperature" to "0.7", "max_completion_tokens" to "1000"),
            )
        )
        config.save(
            ProfileEntity(
                id = "prof-1", name = "P",
                transcriptionModelRef = "m-trans", completionModelRef = "m-comp",
                parameterOverrides = mapOf("temperature" to "0.3"),
            )
        )
    }

    @Test
    fun resolvesProviderModelAndBaseUrlFromTheActiveProfile() {
        seedFullProfile()

        assertEquals(AIProvider.OPENAI, resolver.provider(AIFunction.TRANSCRIPTION))
        assertEquals("whisper-1", resolver.modelName(AIFunction.TRANSCRIPTION))
        assertEquals("gpt-4o-mini", resolver.modelName(AIFunction.COMPLETION))
        assertEquals(AIProvider.OPENAI.defaultBaseUrl, resolver.baseUrl(AIFunction.COMPLETION))
    }

    @Test
    fun apiKey_readsTheCredentialFromTheStoreAndStripsNonAscii() {
        seedFullProfile()
        secretStore.put(CredentialSecrets.credentialRef("cred-1"), "sk-abcé".toByteArray(Charsets.UTF_8))

        assertEquals("sk-abc", resolver.apiKey(AIFunction.COMPLETION))
    }

    @Test
    fun completionParameters_areModelDefaultsOverlaidByProfileOverrides() {
        seedFullProfile()

        val params = resolver.completionParameters(AIProvider.OPENAI, "gpt-4o-mini")
        assertEquals(0.3f, params["temperature"])           // profile override wins over the 0.7 default
        assertEquals(1000, params["max_completion_tokens"]) // model default, no override
    }

    @Test
    fun noActiveProfile_fallsBackToTheEmptyConfig() {
        seedFullProfile()
        activeProfileId = null

        assertEquals(AIProvider.OPENAI, resolver.provider(AIFunction.COMPLETION))
        assertEquals("", resolver.modelName(AIFunction.COMPLETION))
        assertEquals("", resolver.apiKey(AIFunction.COMPLETION))
        assertEquals(AIProvider.OPENAI.defaultBaseUrl, resolver.baseUrl(AIFunction.COMPLETION))
    }

    @Test
    fun absentCredential_resolvesToAnEmptyKeyNotACrash() {
        seedFullProfile(credentialRef = "cred-missing")   // referenced but never stored

        assertEquals("", resolver.apiKey(AIFunction.COMPLETION))
    }

    @Test
    fun noCredentialRefOnProvider_resolvesToAnEmptyKey() {
        seedFullProfile(credentialRef = null)

        assertEquals("", resolver.apiKey(AIFunction.COMPLETION))
    }

    /** A minimal in-memory [SecretStore] — no crypto, just a byte map. */
    private class FakeSecretStore : SecretStore {
        private val store = mutableMapOf<String, ByteArray>()
        override fun get(ref: SecretRef): ByteArray? = store[ref.handle]
        override fun put(ref: SecretRef, value: ByteArray) { store[ref.handle] = value }
        override fun delete(ref: SecretRef) { store.remove(ref.handle) }
        override val available: Boolean = true
        override val hardwareBacked: Boolean = false
    }
}
