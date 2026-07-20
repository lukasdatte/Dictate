package net.devemperor.dictate.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.adapter.PrefCompletionParameters
import net.devemperor.dictate.ai.prompt.PromptMode
import net.devemperor.dictate.ai.secrets.SecretStore
import net.devemperor.dictate.ai.secrets.SecretStoreException
import net.devemperor.dictate.config.ConfigWireMapping.toWire
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.preferences.AmbiguityMode
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import net.devemperor.dictate.preferences.put
import net.devemperor.dictate.secrets.AndroidKeystoreSecretStore
import net.devemperor.dictate.secrets.SecretsMigration
import net.devemperor.dictate.shared.config.ApiCredentialEntity
import net.devemperor.dictate.shared.config.ModelRefEntity
import net.devemperor.dictate.shared.config.ProfileEntity
import net.devemperor.dictate.shared.config.ProfilePromptRef
import net.devemperor.dictate.shared.config.ProviderConfigEntity
import net.devemperor.dictate.shared.config.ProviderKind
import java.security.MessageDigest
import java.util.UUID

/**
 * The one-time, idempotent **Prefs→entity migration** (spec §8): folds today's flat
 * SharedPreferences configuration (provider/model/key/parameter selections + prompts) into the
 * shareable config-entity model (C2) and creates the `Default` profile the resolver reads.
 *
 * # Order + dependencies (§8.1)
 * Runs at app start AFTER [SecretsMigration] (B2) — B2 has, by then, parked every API key under a
 * `legacy` [net.devemperor.dictate.ai.secrets.SecretRef] and removed the plaintext key prefs. This
 * migration **re-maps** those legacy refs onto Credential-entity ids (§7.2) via
 * [SecretsMigration.legacyKeyRef]; it never names a secret pref itself (spec §2.6 allow-list).
 *
 * # Crash-safety / idempotency (§8.4–8.6)
 *  - [PrefsBackup] snapshot FIRST, before any write — the documented rollback (§8.4).
 *  - all entity + prompt-backfill + profile writes happen in ONE DB transaction; the
 *    `SecretStore.put` re-maps are the only non-transactional step and are idempotent per ref (§8.5).
 *  - entity ids are **derived deterministically** from a namespace + slot (§8.6), so a retry after a
 *    partial run rewrites the same rows instead of duplicating; existing prompt uuids are kept.
 *  - the [Pref.ConfigEntityMigrationDone] flag is set LAST — an abort leaves it low and the next
 *    start retries.
 *
 * # Reader re-pointing is NOT here
 * This migration POPULATES the entities. The live AI read path (`AndroidAiFactory`) is flipped from
 * the pref-based `AndroidAiConfig` to the entity-based [net.devemperor.dictate.ai.adapter.ProfileResolver]
 * together with the settings **write** paths in C3, so reads and writes switch atomically (a
 * reads-only flip would strand keys entered before C3). The resolver is built + characterization-
 * tested here (§9.4) so C3's flip is a one-liner.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §8, §9
 * @see net.devemperor.dictate.secrets.SecretsMigration
 */
object ConfigEntityMigration {

    private const val TAG = "ConfigEntityMigration"

    /** Bumped when the migration logic changes in a way that must re-run. */
    const val CURRENT_MIGRATION_VERSION = 1

    private const val DEFAULT_PROFILE_NAME = "Default"

    /** Production entry point: builds the DB + Keystore store and migrates. Safe on every start. */
    @JvmStatic
    fun run(context: Context) {
        val sp = context.getSharedPreferences(SecretsMigration.MAIN_PREFS_NAME, Context.MODE_PRIVATE)
        run(context, sp, DictateDatabase.getInstance(context), AndroidKeystoreSecretStore.create(context))
    }

    /**
     * Testable core. [sp] is the app prefs, [db] the v12 database, [secretStore] the encrypted store
     * (already holding the B2 `legacy` refs in production).
     */
    @JvmStatic
    fun run(context: Context, sp: SharedPreferences, db: DictateDatabase, secretStore: SecretStore) {
        if (sp.get(Pref.ConfigEntityMigrationDone) >= CURRENT_MIGRATION_VERSION) return

        if (!secretStore.available) {
            // Mirrors B2: without a secure store the credential re-map cannot run; defer + retry.
            Log.w(TAG, "SecretStore unavailable — deferring config-entity migration to the next start")
            return
        }

        // §8.4 rollback snapshot FIRST (write-once, before any entity write).
        PrefsBackup.write(sp, context.filesDir)

        val repo = ConfigRepository(db)
        val defaultProfileId = UUID.nameUUIDFromBytes("profile:default".toByteArray()).toString()

        try {
            db.runInTransaction {
                val transcriptionModelRef = buildChain(AIFunction.TRANSCRIPTION, sp, repo, secretStore)
                val completionModelRef = buildChain(AIFunction.COMPLETION, sp, repo, secretStore)
                val orderedPrompts = backfillPromptsAndCollect(db, repo.clock)
                createDefaultProfile(sp, repo, defaultProfileId, transcriptionModelRef, completionModelRef, orderedPrompts)
            }
        } catch (e: SecretStoreException) {
            Log.w(TAG, "config-entity migration aborted (secret store); will retry on next start", e)
            return
        } catch (e: Exception) {
            Log.w(TAG, "config-entity migration aborted; will retry on next start", e)
            return
        }

        // §8.5: activate the profile + set the flag LAST, only after the transaction committed.
        sp.edit()
            .put(Pref.ActiveProfileId, defaultProfileId)
            .put(Pref.ConfigEntityMigrationDone, CURRENT_MIGRATION_VERSION)
            .apply()
    }

    // ── §8.2 provider/credential/model chain per function ─────────────────────────────────────

    /** Builds the ProviderConfig (+ Credential + re-mapped key) + ModelRef for [function]; returns the ModelRef id or null. */
    private fun buildChain(
        function: AIFunction,
        sp: SharedPreferences,
        repo: ConfigRepository,
        secretStore: SecretStore,
    ): String? {
        val provider = activeProvider(function, sp)
        val supported = when (function) {
            AIFunction.TRANSCRIPTION -> provider.supportsTranscription
            AIFunction.COMPLETION -> provider.supportsCompletion
        }
        if (!supported) return null // e.g. a stale selection the old UI could not have produced

        val discriminator = "${function.name}:${provider.name}"

        // Credential: only when a key exists (in the B2 legacy ref). Re-map legacy → credential.
        val credentialId: String? = SecretsMigration.legacyKeyRef(function, provider)?.let { legacyRef ->
            val keyBytes = runCatching { secretStore.get(legacyRef) }.getOrNull()
            if (keyBytes == null || keyBytes.isEmpty()) return@let null
            val id = UUID.nameUUIDFromBytes("credential:$discriminator".toByteArray()).toString()
            repo.upsertCredential(
                ApiCredentialEntity(
                    id = id,
                    providerType = provider.toWire(),
                    label = "${provider.displayName} Key",
                    keyFingerprint = fingerprint(keyBytes),
                ),
            )
            // Idempotent overwrite: park the key under its final credential ref.
            secretStore.put(ConfigSecrets.credentialRef(id), keyBytes)
            id
        }

        val providerConfigId = UUID.nameUUIDFromBytes("providerconfig:$discriminator".toByteArray()).toString()
        val baseUrl = if (provider == AIProvider.CUSTOM) sp.get(customHostPref(function)) else null
        repo.upsertProviderConfig(
            ProviderConfigEntity(
                id = providerConfigId,
                providerType = provider.toWire(),
                kind = ProviderKind.LOCAL,
                label = provider.displayName,
                baseUrl = baseUrl,
                credentialRef = credentialId,
            ),
        )

        val modelRefId = UUID.nameUUIDFromBytes("modelref:$discriminator".toByteArray()).toString()
        repo.upsertModelRef(
            ModelRefEntity(
                id = modelRefId,
                providerRef = providerConfigId,
                modelId = sp.get(modelPref(function, provider)),
                function = function.toWire(),
                parameterDefaults = parameterDefaults(function, provider, sp),
            ),
        )
        return modelRefId
    }

    private fun activeProvider(function: AIFunction, sp: SharedPreferences): AIProvider = when (function) {
        AIFunction.TRANSCRIPTION -> AIProvider.fromPersistKey(sp.get(Pref.TranscriptionProvider))
        AIFunction.COMPLETION -> AIProvider.fromPersistKey(sp.get(Pref.RewordingProvider))
    }

    private fun customHostPref(function: AIFunction): Pref<String> = when (function) {
        AIFunction.TRANSCRIPTION -> Pref.TranscriptionCustomHost
        AIFunction.COMPLETION -> Pref.RewordingCustomHost
    }

    private fun modelPref(function: AIFunction, provider: AIProvider): Pref<String> = when (function) {
        AIFunction.TRANSCRIPTION -> when (provider) {
            AIProvider.OPENAI -> Pref.TranscriptionOpenAIModel
            AIProvider.GROQ -> Pref.TranscriptionGroqModel
            AIProvider.ELEVENLABS -> Pref.TranscriptionElevenLabsModel
            AIProvider.CUSTOM -> Pref.TranscriptionCustomModel
            else -> Pref.TranscriptionOpenAIModel
        }
        AIFunction.COMPLETION -> when (provider) {
            AIProvider.OPENAI -> Pref.RewordingOpenAIModel
            AIProvider.GROQ -> Pref.RewordingGroqModel
            AIProvider.ANTHROPIC -> Pref.RewordingAnthropicModel
            AIProvider.OPENROUTER -> Pref.RewordingOpenRouterModel
            AIProvider.CUSTOM -> Pref.RewordingCustomModel
            else -> Pref.RewordingOpenAIModel
        }
    }

    /**
     * The ModelRef's `parameterDefaults` (§8.3). Completion params mirror
     * `PrefCompletionParameters.of` exactly (same key set, same sentinel filtering) so the
     * resolver reconstructs the byte-identical typed map; values are stored as the canonical string
     * form. Transcription adds ElevenLabs `keyterms` (§4.5).
     */
    private fun parameterDefaults(function: AIFunction, provider: AIProvider, sp: SharedPreferences): Map<String, String> {
        return when (function) {
            AIFunction.COMPLETION -> {
                val model = sp.get(modelPref(function, provider))
                PrefCompletionParameters.of(sp, provider, model)
                    .mapValues { canonicalParam(it.value) }
            }
            AIFunction.TRANSCRIPTION -> {
                if (provider != AIProvider.ELEVENLABS) return emptyMap()
                val raw = sp.get(Pref.ElevenLabsKeytermsParsed)
                val terms = net.devemperor.dictate.ai.ElevenLabsKeytermsParser.fromJson(raw)
                if (terms.isEmpty()) emptyMap() else mapOf("keyterms" to raw)
            }
        }
    }

    /** Canonical string form of a completion-parameter value for hash-stable storage (§8.3). */
    private fun canonicalParam(value: Any): String = when (value) {
        is Float -> ConfigEntityMapper.canonicalDecimal(value)
        is Int -> value.toString()
        is Long -> value.toString()
        is String -> value
        else -> value.toString()
    }

    /** Also used by [ConfigEntitySetup] (onboarding write path) so fingerprints never drift. */
    internal fun fingerprint(keyBytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(keyBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            .take(16)

    // ── §8.5 prompt backfill + Default profile ────────────────────────────────────────────────

    /**
     * Backfills every `prompts` row with a stable uuid (kept if already present — §8.6) + content
     * hash + envelope defaults, and returns the ordered [ProfilePromptRef] list for the profile.
     * TEXT pills keep their `type`; the shareable hash covers only name/text/flags (§8.5.1).
     */
    private fun backfillPromptsAndCollect(db: DictateDatabase, clock: () -> Long): List<ProfilePromptRef> {
        val dao = db.promptDao()
        val refs = mutableListOf<ProfilePromptRef>()
        for (row in dao.getAll()) {
            val uuid = row.uuid.ifEmpty { UUID.randomUUID().toString() }
            val hash = PromptHashing.contentHashOf(uuid, row)
            dao.update(
                row.copy(
                    uuid = uuid,
                    contentHash = hash,
                    visibility = "PRIVATE",
                    subscriptionMode = "LOCAL",
                    updatedAt = clock(),
                ),
            )
            refs += ProfilePromptRef(promptRef = uuid, autoApply = row.autoApply)
        }
        return refs
    }

    private fun createDefaultProfile(
        sp: SharedPreferences,
        repo: ConfigRepository,
        profileId: String,
        transcriptionModelRef: String?,
        completionModelRef: String?,
        orderedPrompts: List<ProfilePromptRef>,
    ) {
        repo.upsertProfile(
            ProfileEntity(
                id = profileId,
                name = DEFAULT_PROFILE_NAME,
                transcriptionModelRef = transcriptionModelRef,
                completionModelRef = completionModelRef,
                orderedPrompts = orderedPrompts,
                stylePromptMode = PromptMode.fromValue(sp.get(Pref.StylePromptSelection)).toWire(),
                stylePromptCustomText = sp.get(Pref.StylePromptCustomText),
                systemPromptMode = PromptMode.fromValue(sp.get(Pref.SystemPromptSelection)).toWire(),
                systemPromptCustomText = sp.get(Pref.SystemPromptCustomText),
                ambiguityMode = AmbiguityMode.fromPersistKey(sp.get(Pref.AmbiguityMode)).toWire(),
                parameterOverrides = emptyMap(),
            ),
        )
    }
}
