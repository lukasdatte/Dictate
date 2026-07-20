package net.devemperor.dictate.secrets

import android.content.Context
import android.content.SharedPreferences
import android.system.Os
import android.util.Log
import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.secrets.SecretRef
import net.devemperor.dictate.ai.secrets.SecretStore
import net.devemperor.dictate.ai.secrets.SecretStoreException
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import net.devemperor.dictate.preferences.put
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * One-time, idempotent migration of the **11 plaintext secret prefs** out of the app's
 * `SharedPreferences` and into the encrypted [SecretStore] (Block B2 — spec secretstore.md §7,
 * ADR adr-secret-store). Resolves the ADR-0017 §F-3 plaintext-secret defer.
 *
 * # What moves where (§3.1 / §7.1 / §7.2)
 * - The **ten API keys** land under a stable **legacy** namespace,
 *   `SecretRef("legacy", "<pref-key-without-app-prefix>")`. B2 runs *before* the entity model
 *   (C2); C2 later re-maps these legacy refs onto Credential-entity IDs, so B2 stays independent
 *   of the entity model.
 * - The **pairing device secret** goes straight to its final home,
 *   `SecretRef("pairing", "windows_device_secret")`.
 *
 * # Order invariant (§7.3 / §11) — never lose a key
 * `backup (once) → for each slot: put BEFORE remove → set flag LAST`. Consequences:
 * - The rollback backup (§7.6) is written *before* any delete, so a crash between `put` and
 *   `remove` can never destroy a key that was not first captured.
 * - `put` precedes `remove` per slot — the plaintext is only dropped once the ciphertext is
 *   safely stored.
 * - The idempotence flag [Pref.SecretsMigratedV1] is set *only* after a fully successful run.
 *   An abort (store unavailable, a failing `put`) leaves the flag unset and the un-migrated
 *   plaintext in place, so the next app start retries — there is no half-migrated-but-flagged
 *   state.
 *
 * # Non-ASCII strip stays in the read adapter (§7.4)
 * The migration writes the key bytes **unchanged** (byte-exact round-trip). The historical
 * non-ASCII strip is the reader's concern (`AndroidAiConfig`), never the store's — the store is
 * value-neutral.
 *
 * # Reader re-pointing is NOT part of B2
 * B2 moves the *data*. The runtime readers/writers (`AndroidAiConfig` for API keys,
 * `WindowsTarget` for the device secret, and the settings/onboarding write paths) are re-pointed
 * to the SecretStore atomically in C2 (`ProfileResolver`) and C3 (UI). Until then the grep-freeness
 * invariant (spec §2.6) is encoded as a pending `NoLegacyKeyReadTest`.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/secretstore.md §7
 * @see net.devemperor.dictate.preferences.PrefsMigration.migrateSecrets
 */
object SecretsMigration {

    private const val TAG = "SecretsMigration"

    /** The app's canonical prefs file — the same name [net.devemperor.dictate.DictateApplication] uses. */
    internal const val MAIN_PREFS_NAME = "net.devemperor.dictate"

    /** Rollback export relative to `filesDir` (§7.6). Contains plaintext keys — delete after verifying. */
    internal const val BACKUP_RELATIVE_PATH = "backup/prefs-secrets-pre-migration.json"

    /** Namespace of the ten API-key legacy refs; C2 re-maps these to credential IDs (§7.2). */
    internal const val LEGACY_NAMESPACE = "legacy"

    /** Namespace of the pairing device secret — its final home (§7.2). */
    internal const val PAIRING_NAMESPACE = "pairing"

    private const val PREF_KEY_PREFIX = "net.devemperor.dictate."

    /** A plaintext pref slot paired with the [SecretRef] it migrates to. */
    internal data class Slot(val pref: Pref<String>, val ref: SecretRef)

    /**
     * The 11 secret slots (spec §3.1 / §7.1): the ten API keys under the `legacy` namespace, plus
     * the pairing device secret under the `pairing` namespace. The ref id is the pref key with the
     * app package prefix stripped (e.g. `transcription_api_key_openai`).
     */
    internal val SLOTS: List<Slot> = buildList {
        listOf(
            Pref.TranscriptionApiKeyOpenAI,
            Pref.TranscriptionApiKeyGroq,
            Pref.TranscriptionApiKeyCustom,
            Pref.TranscriptionApiKeyOpenRouter,
            Pref.TranscriptionApiKeyElevenLabs,
            Pref.RewordingApiKeyOpenAI,
            Pref.RewordingApiKeyGroq,
            Pref.RewordingApiKeyAnthropic,
            Pref.RewordingApiKeyOpenRouter,
            Pref.RewordingApiKeyCustom,
        ).forEach { add(Slot(it, SecretRef(LEGACY_NAMESPACE, it.key.removePrefix(PREF_KEY_PREFIX)))) }

        add(
            Slot(
                Pref.WindowsDeviceSecret,
                SecretRef(PAIRING_NAMESPACE, Pref.WindowsDeviceSecret.key.removePrefix(PREF_KEY_PREFIX)),
            ),
        )
    }

    /**
     * The legacy [SecretRef] under which B2 parked the API key for this (function, provider) slot
     * (or null for an unsupported combination). Exposed here — the one file allowed to name the
     * secret prefs (spec §2.6 allow-list) — so the C2 config-entity migration can re-map the legacy
     * refs onto Credential-entity ids (§7.2) WITHOUT itself referencing the secret pref constants.
     *
     * @see docs/plans/2026-07-19 - desktop-companion-v1/research/secretstore.md §7.2
     */
    @JvmStatic
    internal fun legacyKeyRef(function: AIFunction, provider: AIProvider): SecretRef? {
        val pref: Pref<String>? = when (function) {
            AIFunction.TRANSCRIPTION -> when (provider) {
                AIProvider.OPENAI -> Pref.TranscriptionApiKeyOpenAI
                AIProvider.GROQ -> Pref.TranscriptionApiKeyGroq
                AIProvider.ELEVENLABS -> Pref.TranscriptionApiKeyElevenLabs
                AIProvider.OPENROUTER -> Pref.TranscriptionApiKeyOpenRouter
                AIProvider.CUSTOM -> Pref.TranscriptionApiKeyCustom
                else -> null
            }
            AIFunction.COMPLETION -> when (provider) {
                AIProvider.OPENAI -> Pref.RewordingApiKeyOpenAI
                AIProvider.GROQ -> Pref.RewordingApiKeyGroq
                AIProvider.ANTHROPIC -> Pref.RewordingApiKeyAnthropic
                AIProvider.OPENROUTER -> Pref.RewordingApiKeyOpenRouter
                AIProvider.CUSTOM -> Pref.RewordingApiKeyCustom
                else -> null
            }
        }
        return pref?.let { p -> SLOTS.firstOrNull { it.pref == p }?.ref }
    }

    /**
     * Production entry point: builds the app prefs and the Keystore-backed store and migrates.
     * Idempotent and availability-guarded — safe to call unconditionally on every app start.
     */
    @JvmStatic
    fun run(context: Context) {
        val sp = context.getSharedPreferences(MAIN_PREFS_NAME, Context.MODE_PRIVATE)
        run(context, sp, AndroidKeystoreSecretStore.create(context))
    }

    /**
     * Testable core: migrates [sp] into [secretStore]. Split from [run] so unit tests can inject a
     * fake store / prefs without a real Android Keystore (spec §5.4).
     */
    internal fun run(context: Context, sp: SharedPreferences, secretStore: SecretStore) {
        if (sp.get(Pref.SecretsMigratedV1)) return // idempotent: second run is a no-op (§7.3)

        if (!secretStore.available) {
            // No secure store on this start (e.g. a transient Keystore failure). Do NOT touch the
            // plaintext or the flag — retry on the next start (§7.3).
            Log.w(TAG, "SecretStore unavailable — deferring secret migration to the next start")
            return
        }

        try {
            // §7.6 rollback export FIRST, before any delete. Written once (atomically) so a retry
            // after a partial run keeps the complete pre-migration snapshot rather than a truncated
            // one. Kept inside the try so a backup IO failure aborts cleanly (below) — the migration
            // must never delete plaintext that has no rollback source, nor crash app start.
            exportBackupOnce(context, sp)

            for (slot in SLOTS) {
                val plaintext = sp.getString(slot.pref.key, "").orEmpty()
                if (plaintext.isNotEmpty()) {
                    // put BEFORE remove — never drop a plaintext that is not yet safely stored (§7.3).
                    secretStore.put(slot.ref, plaintext.toByteArray(StandardCharsets.UTF_8))
                }
                // Remove even an empty slot so the absence test (§7.5) holds for all 11 keys.
                sp.edit().remove(slot.pref.key).apply()
            }
        } catch (e: SecretStoreException) {
            // Abort: leave the flag unset and the un-migrated plaintext in place; next start retries.
            Log.w(TAG, "secret migration aborted; will retry on next start", e)
            return
        } catch (e: IOException) {
            // The rollback backup could not be written. Abort before any delete so the plaintext keeps
            // its only rollback source; the next start retries with a fresh full backup (§7.6).
            Log.w(TAG, "secret migration aborted (rollback backup IO failed); will retry on next start", e)
            return
        }

        sp.edit().put(Pref.SecretsMigratedV1, true).apply() // flag LAST — only on full success (§7.3)
    }

    /**
     * Writes the non-empty plaintext secrets to the rollback file (§7.6) — but only if it does not
     * already exist, so a retry after a partial migration cannot overwrite the full first snapshot
     * with a truncated one. A fresh install with no secrets writes nothing.
     */
    private fun exportBackupOnce(context: Context, sp: SharedPreferences) {
        val backupFile = File(context.filesDir, BACKUP_RELATIVE_PATH)
        if (backupFile.exists()) return

        val json = JSONObject().put(
            "_comment",
            "Plaintext secret keys captured before the SecretStore migration " +
                "(spec secretstore.md §7.6). Rollback source only — delete manually after verifying.",
        )
        var any = false
        for (slot in SLOTS) {
            val plaintext = sp.getString(slot.pref.key, "").orEmpty()
            if (plaintext.isNotEmpty()) {
                json.put(slot.pref.key, plaintext)
                any = true
            }
        }
        if (!any) return // nothing to roll back

        backupFile.parentFile?.mkdirs()
        // Atomic write: stage into a temp file and rename into place. A crash or IO error mid-write
        // must never leave a *truncated* backup, because the write-once guard above would then treat
        // it as a complete snapshot — and once the plaintext is deleted the backup is the only
        // rollback source (§7.6). On any failure the temp is removed and the IOException propagates
        // so the caller aborts the migration before deleting anything.
        val tmp = File(backupFile.parentFile, "${backupFile.name}.tmp")
        try {
            tmp.writeText(json.toString(2), StandardCharsets.UTF_8)
            restrictToOwner(tmp)
            if (!tmp.renameTo(backupFile)) {
                throw IOException("could not finalize rollback backup at ${backupFile.absolutePath}")
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    /**
     * Best-effort `0600` on the rollback file. `filesDir` is already app-private (`0700`), so this
     * is defense-in-depth; it may be a no-op under Robolectric and must never fail the migration.
     */
    private fun restrictToOwner(file: File) {
        runCatching { Os.chmod(file.absolutePath, "600".toInt(radix = 8)) }
        runCatching {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
    }
}
