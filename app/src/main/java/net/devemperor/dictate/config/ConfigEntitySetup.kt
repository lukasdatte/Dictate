package net.devemperor.dictate.config

import android.content.Context
import android.content.SharedPreferences
import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.secrets.SecretStore
import net.devemperor.dictate.config.ConfigWireMapping.toWire
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import net.devemperor.dictate.preferences.put
import net.devemperor.dictate.secrets.AndroidKeystoreSecretStore
import net.devemperor.dictate.shared.config.ApiCredentialEntity
import net.devemperor.dictate.shared.config.ModelRefEntity
import net.devemperor.dictate.shared.config.ProfileEntity
import net.devemperor.dictate.shared.config.ProviderConfigEntity
import net.devemperor.dictate.shared.config.ProviderKind
import java.util.UUID

/**
 * Entity write path for the ONBOARDING key step (C3): the first-run screen takes one API key
 * (OpenAI or Groq) and must produce the same rows the Prefs→entity migration would have built —
 * credential (key → SecretStore, F12), provider config, model refs, and a `Default` profile that
 * becomes active. Uses the SAME deterministic ids as [ConfigEntityMigration] (§8.6), so onboarding
 * and a later migration retry can never duplicate rows.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §8.6, §10.1
 */
object ConfigEntitySetup {

    /** Convenience entry for the Java onboarding adapter. */
    @JvmStatic
    fun applyOnboardingKey(context: Context, sp: SharedPreferences, apiKey: String) {
        val provider = if (apiKey.startsWith("gsk_")) AIProvider.GROQ else AIProvider.OPENAI
        applyProviderKey(
            sp,
            DictateDatabase.getInstance(context),
            AndroidKeystoreSecretStore.create(context),
            provider,
            apiKey,
        )
    }

    /**
     * Creates/updates credential + provider config + model refs for [provider] (both functions it
     * supports) with [apiKey], and points the `Default` profile (created if missing) at the new
     * model refs, activating it.
     */
    fun applyProviderKey(
        sp: SharedPreferences,
        db: DictateDatabase,
        secretStore: SecretStore,
        provider: AIProvider,
        apiKey: String,
    ) {
        val repo = ConfigRepository(db)
        val keyBytes = apiKey.toByteArray(Charsets.UTF_8)

        var transcriptionModelRef: String? = null
        var completionModelRef: String? = null
        for (function in AIFunction.values()) {
            val supported = when (function) {
                AIFunction.TRANSCRIPTION -> provider.supportsTranscription
                AIFunction.COMPLETION -> provider.supportsCompletion
            }
            if (!supported) continue
            val discriminator = "${function.name}:${provider.name}"

            val credentialId = UUID.nameUUIDFromBytes("credential:$discriminator".toByteArray()).toString()
            repo.upsertCredential(
                ApiCredentialEntity(
                    id = credentialId,
                    providerType = provider.toWire(),
                    label = "${provider.displayName} Key",
                    keyFingerprint = ConfigEntityMigration.fingerprint(keyBytes),
                ),
            )
            secretStore.put(ConfigSecrets.credentialRef(credentialId), keyBytes)

            val providerConfigId = UUID.nameUUIDFromBytes("providerconfig:$discriminator".toByteArray()).toString()
            repo.upsertProviderConfig(
                ProviderConfigEntity(
                    id = providerConfigId,
                    providerType = provider.toWire(),
                    kind = ProviderKind.LOCAL,
                    label = provider.displayName,
                    credentialRef = credentialId,
                ),
            )

            val modelRefId = UUID.nameUUIDFromBytes("modelref:$discriminator".toByteArray()).toString()
            repo.upsertModelRef(
                ModelRefEntity(
                    id = modelRefId,
                    providerRef = providerConfigId,
                    modelId = defaultModel(provider, function),
                    function = function.toWire(),
                ),
            )
            when (function) {
                AIFunction.TRANSCRIPTION -> transcriptionModelRef = modelRefId
                AIFunction.COMPLETION -> completionModelRef = modelRefId
            }
        }

        // Same deterministic Default-profile id as the migration (§8.6).
        val profileId = UUID.nameUUIDFromBytes("profile:default".toByteArray()).toString()
        val existingRow = db.profileDao().byId(profileId)
        val existing = existingRow?.let { ConfigEntityMapper.toDto(it, db.profileDao().promptsOf(profileId)) }
        repo.upsertProfile(
            (existing ?: ProfileEntity(id = profileId, name = "Default")).copy(
                transcriptionModelRef = transcriptionModelRef ?: existing?.transcriptionModelRef,
                completionModelRef = completionModelRef ?: existing?.completionModelRef,
            ),
        )
        if (sp.get(Pref.ActiveProfileId).isEmpty()) {
            sp.edit().put(Pref.ActiveProfileId, profileId).apply()
        }
    }

    /** The model preselected for a fresh onboarding setup (mirrors the old model-pref defaults). */
    private fun defaultModel(provider: AIProvider, function: AIFunction): String = when (function) {
        AIFunction.TRANSCRIPTION -> when (provider) {
            AIProvider.GROQ -> "whisper-large-v3-turbo"
            else -> "gpt-4o-mini-transcribe"
        }
        AIFunction.COMPLETION -> when (provider) {
            AIProvider.GROQ -> "llama-3.3-70b-versatile"
            else -> "gpt-4o-mini"
        }
    }
}
