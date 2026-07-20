package net.devemperor.dictate.secrets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * Convention test for spec secretstore.md §2.6: once the SecretStore migration is complete, the
 * 11 secret pref constants must be referenced **only** in their definition (`DictatePrefs.kt`) and
 * in the migration (`SecretsMigration.kt`) — no other code may read or write them.
 *
 * # Pending — one slot (`WindowsDeviceSecret`) is not yet re-pointed
 * The **API-key** half is done: production reads credentials via `ProfileResolver`/`SecretStore`
 * (C2/C3) and the former pref-based reader `AndroidAiConfig` was retired to `src/test`, so the 10
 * API-key prefs are no longer referenced outside the allow-list.
 *
 * The **device-secret** half remains open. `SecretsMigration` (live via `DictateApplication`)
 * already moves `Pref.WindowsDeviceSecret` into the store, but three main-source consumers still
 * reference the pref and were missed by C2/C3:
 *   - `preferences/WindowsTarget.kt` — reads the pref to build the send target (now empty post-migration),
 *   - `settings/WindowsPairingActivity.java` — still *writes* the secret back into the plaintext pref,
 *   - `state/PipelinePrefMirror.kt` — watches the pref key to recompute `windowsPaired`.
 * These are a latent runtime bug (a paired user is treated as unpaired; the pairing secret is
 * re-persisted in plaintext), tracked separately as a Critical finding — re-pointing them needs a
 * `SecretStore` read/write seam plus a non-secret `paired?` predicate (spec secretstore.md §7.2).
 *
 * `pending:` prefix per test-first-patterns. Tracking artefact: the `WindowsDeviceSecret` re-point
 * (spec secretstore.md §7.2, §2.6; research `androidaiconfig-secret-pref-retirement.md` Part 2).
 * Remove `@Ignore` only after all three `WindowsDeviceSecret` consumers read/write via the store.
 */
class NoLegacyKeyReadTest {

    /** The 11 secret pref constant names (spec §3.1). */
    private val secretPrefNames = listOf(
        "TranscriptionApiKeyOpenAI",
        "TranscriptionApiKeyGroq",
        "TranscriptionApiKeyCustom",
        "TranscriptionApiKeyOpenRouter",
        "TranscriptionApiKeyElevenLabs",
        "RewordingApiKeyOpenAI",
        "RewordingApiKeyGroq",
        "RewordingApiKeyAnthropic",
        "RewordingApiKeyOpenRouter",
        "RewordingApiKeyCustom",
        "WindowsDeviceSecret",
    )

    /** Files allowed to reference the secret prefs in the end state: the definition + the migration. */
    private val allowedFileNames = setOf("DictatePrefs.kt", "SecretsMigration.kt")

    /** Gradle runs a test with the module directory (`app/`) as its working directory. */
    private fun mainSources(): List<File> {
        val root = File("src/main/java")
        assertTrue("missing source root: ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()
    }

    private fun offendingReferences(): List<String> {
        val violations = mutableListOf<String>()
        mainSources().forEach { file ->
            if (file.name in allowedFileNames) return@forEach
            file.readLines().forEachIndexed { index, line ->
                secretPrefNames.forEach { name ->
                    if (line.contains("Pref.$name")) {
                        violations += "${file.path}:${index + 1} references `Pref.$name`"
                    }
                }
            }
        }
        return violations
    }

    @Test
    @Ignore(
        "pending: readers/writers of the 11 secret prefs are re-pointed to the SecretStore in " +
            "C2 (AndroidAiConfig/ProfileResolver reads) + C3 (settings/onboarding writes); " +
            "spec secretstore.md §2.6",
    )
    fun secretPrefs_areReferencedOnlyInDefinitionAndMigration() {
        val violations = offendingReferences()
        assertEquals(violations.joinToString("\n"), emptyList<String>(), violations)
    }

    @Test
    fun theScanner_readsSourcesAndCanMatch() {
        // A convention test that silently scans nothing is worse than none — pin that the scanner
        // reaches the sources and that its match logic works, so the pending assertion above is
        // meaningful the moment `@Ignore` is removed.
        val sources = mainSources()
        assertTrue("expected to scan the app sources, scanned ${sources.size}", sources.size > 20)
        assertTrue(
            "the migration itself must reference the secret prefs (sanity of the scan target)",
            offendingReferencesIncludingAllowed().any { it.contains("SecretsMigration.kt") },
        )
    }

    /** Like [offendingReferences] but without the allow-list — used only by the scanner self-check. */
    private fun offendingReferencesIncludingAllowed(): List<String> {
        val hits = mutableListOf<String>()
        mainSources().forEach { file ->
            file.readLines().forEach { line ->
                secretPrefNames.forEach { name ->
                    if (line.contains("Pref.$name")) hits += "${file.name}: Pref.$name"
                }
            }
        }
        return hits
    }
}
