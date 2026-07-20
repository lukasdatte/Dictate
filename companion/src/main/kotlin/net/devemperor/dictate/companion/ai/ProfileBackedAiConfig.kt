package net.devemperor.dictate.companion.ai

import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.ElevenLabsKeytermsParser
import net.devemperor.dictate.ai.model.ParameterRegistry
import net.devemperor.dictate.ai.port.AiConfig
import net.devemperor.dictate.ai.secrets.SecretStore
import net.devemperor.dictate.companion.ai.CompanionConfigWireMapping.toAIProvider
import net.devemperor.dictate.companion.data.CompanionConfigRepository
import net.devemperor.dictate.shared.config.ModelRefEntity
import net.devemperor.dictate.shared.config.ProviderConfigEntity

/**
 * Entity-backed [AiConfig] — resolves the effective AI configuration from the **active profile** + its
 * credential in the [SecretStore] (desktop-host.md §5.1/§9). The desktop twin of the Android
 * `ProfileResolver`, reading SQLDelight config rows instead of Room rows and reproducing the same
 * resolution and the same §9.3 fallbacks.
 *
 * It replaces the transitional [CompanionAiConfig] (whose `apiKey()` was hard-wired to `""`): the
 * provider, model, base URL, completion parameters and — the correctness-critical part — the API key
 * now all come from the profile the user selected, so a real desktop transcription/completion can
 * actually authenticate. When nothing is configured the fallbacks preserve today's "not configured"
 * UX rather than crashing (see below).
 *
 * ## Fallback semantics (§9.3) — never crash
 *  - no active profile / profile row missing → empty config: provider OPENAI, model/key `""`,
 *    baseUrl the OPENAI default (the "nothing set up" state; the "API key missing" UX fires);
 *  - profile with no ModelRef for a function → model/key `""` for that function only;
 *  - credential referenced but absent/undecryptable in the store → key `""` (a lost KEK surfaces as
 *    "key missing", not a crash — the store's `runCatching` swallows a `DecryptionFailed`).
 *
 * ## Credential population (out of this class's scope)
 * The resolver only *reads* the store. A key gets *into* it via Block E peer-credential delivery or a
 * future credential-entry field (desktop-host.md §15 Gap 5, research desktop-aiconfig-credential-
 * resolution.md F5); until one lands, the empty-store fallback above keeps a profiled fake-runner take
 * (headless E2E) green and a real provider call honestly reports the missing key.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §9
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/desktop-aiconfig-credential-resolution.md
 */
class ProfileBackedAiConfig(
    private val config: CompanionConfigRepository,
    private val secretStore: SecretStore,
    private val activeProfileId: () -> String?,
) : AiConfig {

    private data class Resolved(
        val providerConfig: ProviderConfigEntity?,
        val modelRef: ModelRefEntity?,
    )

    override fun provider(function: AIFunction): AIProvider =
        resolve(function).providerConfig?.providerType?.toAIProvider() ?: AIProvider.OPENAI

    override fun modelName(function: AIFunction): String =
        resolve(function).modelRef?.modelId ?: ""

    override fun apiKey(function: AIFunction): String {
        val credentialRef = resolve(function).providerConfig?.credentialRef ?: return ""
        val bytes = runCatching { secretStore.get(CredentialSecrets.credentialRef(credentialRef)) }.getOrNull()
            ?: return ""
        // Same normalization the pref-based path applied (AiConfig contract): strip non-ASCII.
        return String(bytes, Charsets.UTF_8).replace(NON_ASCII, "")
    }

    override fun baseUrl(function: AIFunction): String {
        val providerConfig = resolve(function).providerConfig ?: return AIProvider.OPENAI.defaultBaseUrl
        return providerConfig.baseUrl ?: providerConfig.providerType.toAIProvider().defaultBaseUrl
    }

    override fun completionParameters(provider: AIProvider, model: String): Map<String, Any> {
        val modelRef = resolve(AIFunction.COMPLETION).modelRef ?: return emptyMap()
        val profile = activeProfileId()?.let { config.profile(it) }
        // ModelRef defaults ⊕ Profile overrides (profile wins) — §9.2 step 7. Both are already decoded
        // Map<String,String> off the repository, so no ConfigEntityMapper.decodeParams on this side.
        val combined = modelRef.parameterDefaults + (profile?.parameterOverrides ?: emptyMap())

        // Iterate the SAME defs, in the SAME order, with the SAME name-branch + sentinel filters as the
        // Android ProfileResolver.completionParameters — so the resulting typed map is byte-identical.
        val defs = ParameterRegistry.getCompletionParameters(provider, model)
        val params = mutableMapOf<String, Any>()
        for (def in defs) {
            val raw = combined[def.name] ?: continue
            val value: Any? = when (def.name) {
                "temperature" -> raw.toFloatOrNull()?.takeIf { it >= 0f }
                "max_completion_tokens", "max_tokens" -> raw.toIntOrNull()?.takeIf { it > 0 }
                "reasoning_effort" -> raw.takeIf { it.isNotEmpty() }
                else -> null
            }
            if (value != null) params[def.name] = value
        }
        return params
    }

    override fun elevenLabsKeyterms(): List<String>? {
        val resolved = resolve(AIFunction.TRANSCRIPTION)
        val provider = resolved.providerConfig?.providerType?.toAIProvider() ?: AIProvider.OPENAI
        if (provider != AIProvider.ELEVENLABS) return null
        val raw = resolved.modelRef?.parameterDefaults?.get("keyterms") ?: return null
        return ElevenLabsKeytermsParser.fromJson(raw).takeIf { it.isNotEmpty() }
    }

    private fun resolve(function: AIFunction): Resolved {
        val profileId = activeProfileId() ?: return Resolved(null, null)
        val profile = config.profile(profileId) ?: return Resolved(null, null)
        val modelRefId = when (function) {
            AIFunction.TRANSCRIPTION -> profile.transcriptionModelRef
            AIFunction.COMPLETION -> profile.completionModelRef
        } ?: return Resolved(null, null)
        val modelRef = config.modelRef(modelRefId) ?: return Resolved(null, null)
        val providerConfig = config.providerConfig(modelRef.providerRef)
        return Resolved(providerConfig, modelRef)
    }

    private companion object {
        val NON_ASCII = Regex("[^ -~]")
    }
}
