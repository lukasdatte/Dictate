package net.devemperor.dictate.secrets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Convention test for spec secretstore.md §2.6: with the SecretStore migration complete, the 11
 * secret pref constants must be referenced **only** in their definition (`DictatePrefs.kt`) and in
 * the migration (`SecretsMigration.kt`) — no other main-source code may read or write them.
 *
 * # End state reached (no longer pending)
 * Both halves are re-pointed to the [SecretStore][net.devemperor.dictate.ai.secrets.SecretStore]:
 *   - the **ten API keys** read via `ProfileResolver`/`SecretStore` (C2/C3); the former pref-based
 *     reader `AndroidAiConfig` was retired to `src/test`.
 *   - the **device secret** reads via `WindowsTarget.resolve(sp, SecretStore)` and is written by
 *     `WindowsPairingActivity` through the store; "paired?" is now the non-secret
 *     `WindowsTarget.isPaired` predicate (url + deviceId), and `PipelinePrefMirror` no longer
 *     watches the secret key (spec §2.6/§7.2, research `androidaiconfig-secret-pref-retirement.md`).
 *
 * `SecretsMigration.kt` still *names* the secret prefs (it is their migration source) — it is on
 * the allow-list. If a future change reintroduces a plaintext read/write of any secret pref, this
 * test fails with the exact `file:line`.
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
