package net.devemperor.dictate.ai.adapter

import android.content.SharedPreferences
import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.ElevenLabsKeytermsParser
import net.devemperor.dictate.ai.model.ParameterRegistry
import net.devemperor.dictate.ai.port.AiConfig
import net.devemperor.dictate.ai.secrets.SecretStore
import net.devemperor.dictate.config.ConfigEntityMapper
import net.devemperor.dictate.config.ConfigSecrets
import net.devemperor.dictate.config.ConfigWireMapping.toAIProvider
import net.devemperor.dictate.config.entity.ModelRefRoomEntity
import net.devemperor.dictate.config.entity.ProfileRoomEntity
import net.devemperor.dictate.config.entity.ProviderConfigRoomEntity
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get

/**
 * Entity-backed [AiConfig] — resolves the effective AI configuration from the **active profile** +
 * its credentials (spec §9), the entity-model successor to the pref-based [AndroidAiConfig].
 *
 * It reproduces `AndroidAiConfig`'s output **byte-for-byte** (proven by `ProfileResolverCharacterizationTest`,
 * §9.4): same provider/model/baseUrl, the same non-ASCII-stripped key (now read from the SecretStore,
 * not a plaintext pref), and the same typed completion-parameter map (reconstructed from the stored
 * canonical strings by iterating the very same `ParameterRegistry` defs).
 *
 * ## Fallback semantics (§9.3) — never crash, reproduce today's "not configured"
 *  - no active profile / profile row missing → empty config: provider OPENAI, model/key `""`,
 *    baseUrl the OPENAI default (the current "nothing set up" state; the "API key missing" UX fires);
 *  - profile with no ModelRef for a function → model/key `""` for that function only;
 *  - credential referenced but absent/undecryptable in the store → key `""` (a lost Keystore KEK
 *    surfaces as "key missing", not a crash).
 *
 * ## Not yet the live read path
 * `AndroidAiFactory` still builds `AndroidAiConfig`; the flip to this resolver happens together with
 * the settings write-path switch in C3, so reads and writes move atomically.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §9
 */
class ProfileResolver(
    private val sp: SharedPreferences,
    private val db: DictateDatabase,
    private val secretStore: SecretStore,
) : AiConfig {

    private data class Resolved(
        val providerConfig: ProviderConfigRoomEntity?,
        val modelRef: ModelRefRoomEntity?,
    )

    override fun provider(function: AIFunction): AIProvider =
        resolve(function).providerConfig?.providerTypeEnum?.toAIProvider() ?: AIProvider.OPENAI

    override fun modelName(function: AIFunction): String =
        resolve(function).modelRef?.modelId ?: ""

    override fun apiKey(function: AIFunction): String {
        val credentialRef = resolve(function).providerConfig?.credentialRef ?: return ""
        val bytes = runCatching { secretStore.get(ConfigSecrets.credentialRef(credentialRef)) }.getOrNull()
            ?: return ""
        // Same normalization the pref-based path applied (AiConfig contract): strip non-ASCII.
        return String(bytes, Charsets.UTF_8).replace(NON_ASCII, "")
    }

    override fun baseUrl(function: AIFunction): String {
        val providerConfig = resolve(function).providerConfig ?: return AIProvider.OPENAI.defaultBaseUrl
        return providerConfig.baseUrl ?: providerConfig.providerTypeEnum.toAIProvider().defaultBaseUrl
    }

    override fun completionParameters(provider: AIProvider, model: String): Map<String, Any> {
        val modelRef = resolve(AIFunction.COMPLETION).modelRef ?: return emptyMap()
        val profile = activeProfile()
        // ModelRef defaults ⊕ Profile overrides (profile wins) — §9.2 step 7.
        val combined = ConfigEntityMapper.decodeParams(modelRef.parameterDefaults) +
            (profile?.let { ConfigEntityMapper.decodeParams(it.parameterOverrides) } ?: emptyMap())

        // Iterate the SAME defs, in the SAME order, with the SAME name-branch + sentinel filters as
        // AndroidAiConfig.completionParameters — so the resulting typed map is byte-identical.
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
        val provider = resolved.providerConfig?.providerTypeEnum?.toAIProvider() ?: AIProvider.OPENAI
        if (provider != AIProvider.ELEVENLABS) return null
        val raw = ConfigEntityMapper.decodeParams(resolved.modelRef?.parameterDefaults ?: "{}")["keyterms"]
            ?: return null
        return ElevenLabsKeytermsParser.fromJson(raw).takeIf { it.isNotEmpty() }
    }

    private fun resolve(function: AIFunction): Resolved {
        val profile = activeProfile() ?: return Resolved(null, null)
        val modelRefId = when (function) {
            AIFunction.TRANSCRIPTION -> profile.transcriptionModelRef
            AIFunction.COMPLETION -> profile.completionModelRef
        } ?: return Resolved(null, null)
        val modelRef = db.modelRefDao().byId(modelRefId) ?: return Resolved(null, null)
        val providerConfig = db.providerConfigDao().byId(modelRef.providerRef)
        return Resolved(providerConfig, modelRef)
    }

    private fun activeProfile(): ProfileRoomEntity? {
        val id = sp.get(Pref.ActiveProfileId)
        if (id.isEmpty()) return null
        return db.profileDao().byId(id)
    }

    private companion object {
        val NON_ASCII = Regex("[^ -~]")
    }
}
