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
 * # Pending — the readers/writers are re-pointed in C2/C3
 * B2 moves the secret *data* into the store; it deliberately does **not** re-point the runtime
 * consumers. `AndroidAiConfig` (API-key reads), `WindowsTarget` (device-secret read), and the
 * settings/onboarding **write** paths still reference these prefs and are switched to the
 * SecretStore atomically in **C2** (`ProfileResolver` reads) and **C3** (UI writes) — a reads-only
 * re-point in B2 would silently break newly entered keys (the write path would still target prefs).
 * The end-state assertion is therefore encoded here and marked pending until those chunks land.
 *
 * `pending:` prefix per test-first-patterns. Tracking artefact: chunks C2/C3 of the
 * desktop-companion-v1 plan (spec secretstore.md §7.2, §2.6). Remove `@Ignore` when C3 re-points
 * the last writer.
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
