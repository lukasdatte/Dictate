package net.devemperor.dictate.secrets

import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.ai.secrets.SecretRef
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.WindowsTarget
import net.devemperor.dictate.preferences.get
import net.devemperor.dictate.testutil.FakeSecretStore
import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Migration behaviour of [SecretsMigration] on the JVM (spec secretstore.md §7, criteria §2.4–2.6).
 *
 * # Why Robolectric
 * The migration touches `context.filesDir` (backup export) and `org.json`; both need the Robolectric
 * runtime. The happy-path tests run the migration through a **real** [AndroidKeystoreSecretStore]
 * (with the [InMemoryKekProvider] crypto seam, §5.4) so the byte-exact round-trip is proven end to
 * end; the failure/unavailable tests use an in-test [FakeSecretStore] to force the abort paths.
 */
@RunWith(RobolectricTestRunner::class)
class SecretsMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** The 11 pref keys the migration owns, with a distinct plaintext value each. */
    private val fixture: Map<Pref<String>, String> = mapOf(
        Pref.TranscriptionApiKeyOpenAI to "sk-openai-transcribe",
        Pref.TranscriptionApiKeyGroq to "gsk-groq-transcribe",
        Pref.TranscriptionApiKeyCustom to "custom-transcribe",
        Pref.TranscriptionApiKeyOpenRouter to "or-transcribe",
        Pref.TranscriptionApiKeyElevenLabs to "el-transcribe",
        Pref.RewordingApiKeyOpenAI to "sk-openai-reword",
        Pref.RewordingApiKeyGroq to "gsk-groq-reword",
        Pref.RewordingApiKeyAnthropic to "sk-ant-reword",
        Pref.RewordingApiKeyOpenRouter to "or-reword",
        Pref.RewordingApiKeyCustom to "custom-reword",
        Pref.WindowsDeviceSecret to "device-secret-256bit",
    )

    private fun realStore(prefs: FakeSharedPreferences = FakeSharedPreferences()) =
        AndroidKeystoreSecretStore(prefs, InMemoryKekProvider(), hardwareBacked = false)

    private fun seedAllSlots(sp: FakeSharedPreferences) {
        val editor = sp.edit()
        fixture.forEach { (pref, value) -> editor.putString(pref.key, value) }
        editor.apply()
    }

    private fun backupFile() = File(context.filesDir, SecretsMigration.BACKUP_RELATIVE_PATH)

    private fun clearBackup() {
        backupFile().delete()
    }

    private fun refOf(pref: Pref<String>): SecretRef =
        SecretsMigration.SLOTS.first { it.pref == pref }.ref

    // ── §2.4 Migration lossless + plaintext gone ──────────────────────────────────────────────

    @Test
    fun migratesAll11Slots_losslessly_andRemovesEveryPlaintextKey() {
        clearBackup()
        val sp = FakeSharedPreferences()
        seedAllSlots(sp)
        val store = realStore()

        SecretsMigration.run(context, sp, store)

        fixture.forEach { (pref, value) ->
            assertArrayEquals(
                "slot ${pref.key} must round-trip byte-identically through the store",
                value.toByteArray(StandardCharsets.UTF_8),
                store.get(refOf(pref)),
            )
            assertFalse("plaintext key ${pref.key} must be gone from prefs", sp.contains(pref.key))
        }
        assertTrue("flag set after a full run", sp.get(Pref.SecretsMigratedV1))
    }

    @Test
    fun apiKeysLandUnderLegacyNamespace_deviceSecretUnderPairing() {
        // Pins the §7.2 namespace split so C2's legacy→credential re-map has the address it expects.
        assertEquals("legacy", refOf(Pref.TranscriptionApiKeyOpenAI).namespace)
        assertEquals("transcription_api_key_openai", refOf(Pref.TranscriptionApiKeyOpenAI).id)
        assertEquals("pairing", refOf(Pref.WindowsDeviceSecret).namespace)
        assertEquals("windows_device_secret", refOf(Pref.WindowsDeviceSecret).id)
    }

    @Test
    fun nonAsciiKey_roundTripsAsRawBytes_stripStaysInReader() {
        // The store is value-neutral (§7.4): the migration writes the exact bytes; any non-ASCII
        // strip is the read adapter's concern, not the store's.
        clearBackup()
        val sp = FakeSharedPreferences()
        val raw = "sk-café✓-ß-key"
        sp.edit().putString(Pref.RewordingApiKeyOpenAI.key, raw).apply()
        val store = realStore()

        SecretsMigration.run(context, sp, store)

        assertArrayEquals(
            raw.toByteArray(StandardCharsets.UTF_8),
            store.get(refOf(Pref.RewordingApiKeyOpenAI)),
        )
    }

    // ── §2.5 Idempotent + fresh install ───────────────────────────────────────────────────────

    @Test
    fun secondRun_isNoOp() {
        clearBackup()
        val sp = FakeSharedPreferences()
        seedAllSlots(sp)
        val store = realStore()
        SecretsMigration.run(context, sp, store)

        // A stray plaintext written after the flag is set must NOT be migrated by a second run.
        sp.edit().putString(Pref.RewordingApiKeyGroq.key, "written-after-migration").apply()
        SecretsMigration.run(context, sp, store)

        assertTrue("stray post-migration plaintext is left untouched (no-op)", sp.contains(Pref.RewordingApiKeyGroq.key))
        assertEquals("written-after-migration", sp.getString(Pref.RewordingApiKeyGroq.key, null))
    }

    @Test
    fun freshInstall_noKeys_setsFlag_noStoreWrites_noBackupFile() {
        clearBackup()
        val sp = FakeSharedPreferences()
        val store = realStore()

        SecretsMigration.run(context, sp, store)

        assertTrue("flag set even with nothing to migrate", sp.get(Pref.SecretsMigratedV1))
        SecretsMigration.SLOTS.forEach { assertNull("no store write on a fresh install", store.get(it.ref)) }
        assertFalse("no backup file when there is nothing to roll back", backupFile().exists())
    }

    // ── §7.3 Abort semantics — flag stays unset, plaintext survives ───────────────────────────

    @Test
    fun storeUnavailable_leavesFlagUnset_prefsIntact_noBackup() {
        clearBackup()
        val sp = FakeSharedPreferences()
        seedAllSlots(sp)
        val unavailable = FakeSecretStore(available = false)

        SecretsMigration.run(context, sp, unavailable)

        assertFalse("flag stays unset when the store is unavailable", sp.get(Pref.SecretsMigratedV1))
        fixture.forEach { (pref, value) ->
            assertEquals("plaintext $${pref.key} untouched", value, sp.getString(pref.key, null))
        }
        assertFalse("no backup written when migration cannot start", backupFile().exists())
    }

    @Test
    fun putFailureMidway_abortsWithFlagUnset_backupHoldsAllPlaintext() {
        clearBackup()
        val sp = FakeSharedPreferences()
        seedAllSlots(sp)
        // Fail on the Anthropic reword slot (a later slot, so earlier ones are already moved).
        val failing = FakeSecretStore(failOn = refOf(Pref.RewordingApiKeyAnthropic))

        SecretsMigration.run(context, sp, failing)

        assertFalse("flag stays unset on an aborted run", sp.get(Pref.SecretsMigratedV1))
        // The failing slot's plaintext must survive for the retry.
        assertEquals("device-secret", "device-secret-256bit", sp.getString(Pref.WindowsDeviceSecret.key, null))
        assertEquals("sk-ant-reword", sp.getString(Pref.RewordingApiKeyAnthropic.key, null))
        // Rollback backup captured every original plaintext before the first delete (§7.6).
        val backup = org.json.JSONObject(backupFile().readText())
        fixture.forEach { (pref, value) -> assertEquals(value, backup.getString(pref.key)) }
    }

    @Test
    fun backupFile_isNotOverwrittenOnRetry_afterPartialFailure() {
        clearBackup()
        val sp = FakeSharedPreferences()
        seedAllSlots(sp)
        // One store instance across both runs (as in prod: the retry reuses the same backing store,
        // so slots stored before the failure are still there). It fails on the first pass, then heals.
        val store = FakeSecretStore(failOn = refOf(Pref.RewordingApiKeyAnthropic))
        SecretsMigration.run(context, sp, store)
        val firstBackup = backupFile().readText()

        store.failOn = null // transient failure cleared
        SecretsMigration.run(context, sp, store)

        assertTrue("retry completes the migration", sp.get(Pref.SecretsMigratedV1))
        assertEquals("backup snapshot from the first run is preserved", firstBackup, backupFile().readText())
        // Every key ends up in the store across the two passes (early slots on run 1, the rest on run 2).
        fixture.forEach { (pref, value) ->
            assertArrayEquals(value.toByteArray(StandardCharsets.UTF_8), store.get(refOf(pref)))
        }
        // And no plaintext survives.
        fixture.keys.forEach { assertFalse("plaintext ${it.key} gone", sp.contains(it.key)) }
    }

    @Test
    fun backupWriteFailure_abortsCleanly_noPlaintextDeleted_noCrash() {
        // Regression (B2 self-fix): a failure writing the rollback backup must abort the migration
        // BEFORE any plaintext is deleted — it must never crash app start, and never leave a
        // truncated backup that the write-once guard would later mistake for a complete snapshot
        // (§7.6 — once the plaintext is gone the backup is the only rollback source). Force the
        // failure by planting a plain *file* where the backup's parent directory must go, so the
        // staged write cannot open its file.
        clearBackup()
        val sp = FakeSharedPreferences()
        seedAllSlots(sp)
        val store = realStore()

        val backupParent = requireNotNull(backupFile().parentFile)
        backupParent.deleteRecursively()
        backupParent.parentFile?.mkdirs()
        backupParent.writeText("not a directory") // occupy the parent path with a plain file

        try {
            // Must NOT throw — a backup IO failure is a clean abort, not an app-start crash.
            SecretsMigration.run(context, sp, store)

            assertFalse("flag stays unset when the backup could not be written", sp.get(Pref.SecretsMigratedV1))
            fixture.forEach { (pref, value) ->
                assertEquals("plaintext ${pref.key} must survive an aborted backup", value, sp.getString(pref.key, null))
            }
            SecretsMigration.SLOTS.forEach {
                assertNull("no store write when the migration aborts before the slot loop", store.get(it.ref))
            }
        } finally {
            backupParent.delete()
        }
    }

    // ── §7.2 Pairing device-secret re-point — the paired user survives the migration ──────────

    @Test
    fun pairingSecretRef_matchesTheMigrationSlot() {
        // The reader (WindowsTarget.resolve) and the writer (WindowsPairingActivity) address the
        // secret via PairingSecrets.DEVICE_SECRET_REF; the migration destination must be the same
        // handle or a migrated user's secret would be unreadable (§7.2 SSoT).
        assertEquals(
            PairingSecrets.DEVICE_SECRET_REF,
            SecretsMigration.SLOTS.first { it.pref == Pref.WindowsDeviceSecret }.ref,
        )
    }

    @Test
    fun pairedUserSurvivesMigration_isPairedStaysTrue_resolveReadsMigratedSecret() {
        // Regression (C-TEST-2): before the re-point, SecretsMigration deleted the plaintext secret
        // pref while WindowsTarget still read it — silently un-pairing an existing user. Now
        // "paired?" is the non-secret url+deviceId predicate and the send target reads the secret
        // from the store, so a paired user survives the migration untouched.
        clearBackup()
        val sp = FakeSharedPreferences()
        sp.edit()
            .putString(Pref.WindowsTargetUrl.key, "http://vm-win:8756")
            .putString(Pref.WindowsDeviceId.key, "device-1")
            .putString(Pref.WindowsServerName.key, "Office PC")
            .putString(Pref.WindowsDeviceSecret.key, "device-secret-256bit")
            .apply()
        val store = realStore()

        SecretsMigration.run(context, sp, store)

        assertFalse("the plaintext secret pref is gone after migration", sp.contains(Pref.WindowsDeviceSecret.key))
        assertTrue("a paired user is still paired (url+deviceId survive)", WindowsTarget.isPaired(sp))
        val target = WindowsTarget.resolve(sp, store)
        assertNotNull("resolve reads the migrated secret from the store", target)
        assertEquals("device-secret-256bit", target!!.deviceSecret)
        assertEquals("http://vm-win:8756", target.baseUrl)
        assertEquals("Office PC", target.serverName)
    }
}
