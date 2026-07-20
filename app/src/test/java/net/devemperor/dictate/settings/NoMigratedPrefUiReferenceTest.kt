package net.devemperor.dictate.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AK8 grep test (spec §2.8): after the C3 UI rebuild, NO UI code (settings/, rewording/,
 * onboarding/) references a migrated pref constant — provider/model/key/host/parameter/prompt-mode/
 * ambiguity selections all live in the config entities now.
 *
 * `ElevenLabsKeytermsRaw` is deliberately NOT in this list: the raw keyterms text (incl. comments)
 * is device-local editor state; only the parsed JSON is the shareable model parameter (spec §4.5) —
 * documented C3 deviation.
 */
class NoMigratedPrefUiReferenceTest {

    private val migratedPrefNames = listOf(
        "TranscriptionProvider", "RewordingProvider",
        "TranscriptionApiKeyOpenAI", "TranscriptionApiKeyGroq", "TranscriptionApiKeyCustom",
        "TranscriptionApiKeyOpenRouter", "TranscriptionApiKeyElevenLabs",
        "RewordingApiKeyOpenAI", "RewordingApiKeyGroq", "RewordingApiKeyAnthropic",
        "RewordingApiKeyOpenRouter", "RewordingApiKeyCustom",
        "TranscriptionOpenAIModel", "TranscriptionGroqModel", "TranscriptionElevenLabsModel",
        "TranscriptionCustomModel",
        "RewordingOpenAIModel", "RewordingGroqModel", "RewordingAnthropicModel",
        "RewordingOpenRouterModel", "RewordingCustomModel",
        "TranscriptionCustomHost", "RewordingCustomHost",
        "TemperatureOpenAI", "TemperatureGroq", "TemperatureAnthropic", "TemperatureOpenRouter",
        "MaxTokensOpenAI", "MaxTokensGroq", "MaxTokensAnthropic", "MaxTokensOpenRouter",
        "ReasoningEffortOpenAI",
        "StylePromptSelection", "StylePromptCustomText",
        "SystemPromptSelection", "SystemPromptCustomText",
        "AmbiguityMode",
        "ElevenLabsKeytermsParsed",
    )

    /** The UI packages the C3 rebuild covers. Gradle runs tests with `app/` as working dir. */
    private val uiRoots = listOf(
        "src/main/java/net/devemperor/dictate/settings",
        "src/main/java/net/devemperor/dictate/rewording",
        "src/main/java/net/devemperor/dictate/onboarding",
    )

    private fun uiSources(): List<File> = uiRoots.flatMap { root ->
        val dir = File(root)
        assertTrue("missing source root: ${dir.absolutePath}", dir.isDirectory)
        dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
    }

    @Test
    fun uiCode_referencesNoMigratedPrefConstant() {
        val violations = mutableListOf<String>()
        uiSources().forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                migratedPrefNames.forEach { name ->
                    if (line.contains("Pref.$name")) {
                        violations += "${file.path}:${index + 1} references `Pref.$name`"
                    }
                }
            }
        }
        assertEquals(violations.joinToString("\n"), emptyList<String>(), violations)
    }

    @Test
    fun theScanner_reachesTheUiSources() {
        // Guard against a silently-empty scan (same idiom as NoLegacyKeyReadTest).
        val sources = uiSources()
        assertTrue("expected to scan UI sources, scanned ${sources.size}", sources.size > 10)
        assertTrue(
            "scanner must match the pattern syntax used in real code",
            sources.any { it.readText().contains("Pref.") },
        )
    }
}
