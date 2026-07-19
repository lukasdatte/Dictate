package net.devemperor.dictate.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architecture-invariant test: `:shared-ai` stays consumable from BOTH platforms.
 *
 * Sibling of `:shared`'s `SharedPurityTest`, with a deliberately different forbidden
 * list — this module IS allowed the AI SDKs (openai-java, anthropic-java) and okhttp
 * (that is its whole reason to exist separate from `:shared`), but it must never pull
 * in the couplings that would make it unusable from the desktop companion or break the
 * `:app` build:
 *
 * - **android / androidx** — would make the AI core unusable from `:companion`, which is
 *   the entire point of the extraction (ADR adr-shared-ai-module).
 * - **Ktor** — the server belongs to `:companion` alone, and Ktor's server needs JDK 11+
 *   while this module compiles at jvmTarget 1.8 for `:app` (ADR-0015 inline-bytecode
 *   constraint). An import here would be a build break waiting for the next `:app` compile.
 * - **coroutines** — `:app` pins kotlinx-coroutines 1.7.3; `:shared-ai` is blocking-by-design
 *   (SDKs on background executors, the house pattern). Pinned to mirror `:shared`'s doctrine.
 * - **org.json** — not on pure JVM (the companion has no `org.json`). The A3.4 migration to
 *   kotlinx-serialization is pinned here so the ElevenLabs parse can never regress to org.json.
 *
 * The test reads the sources rather than the bytecode so its failure message can name the
 * file and the line the offending import sits on.
 */
class SharedAiPurityTest {

    private val forbiddenImports = mapOf(
        "android." to "Android APIs — :shared-ai must stay consumable from the desktop companion",
        "androidx." to "AndroidX APIs — :shared-ai must stay consumable from the desktop companion",
        "io.ktor" to "Ktor — the server lives in :companion only; :shared-ai compiles at jvmTarget 1.8 (ADR-0015)",
        "kotlinx.coroutines" to "coroutines — :app pins 1.7.3; :shared-ai is blocking-by-design (ADR-0015)",
        "org.json" to "org.json — not on pure JVM; use kotlinx-serialization (spec A3.4)",
    )

    /** Gradle runs a test with the module directory as its working directory. */
    private fun sourceRoots(): List<File> =
        listOf(File("src/main/kotlin"), File("src/test/kotlin"))

    private fun kotlinSources(): List<File> =
        sourceRoots().flatMap { root ->
            assertTrue("missing source root: ${root.absolutePath}", root.isDirectory)
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }

    @Test
    fun sharedAiModule_hasNoForbiddenImports() {
        val violations = mutableListOf<String>()

        kotlinSources().forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val imported = line.trim().removePrefix("import ").takeIf { line.trim().startsWith("import ") } ?: return@forEachIndexed
                forbiddenImports.forEach { (prefix, why) ->
                    if (imported.startsWith(prefix)) {
                        violations += "${file.path}:${index + 1} imports `$imported` — forbidden: $why"
                    }
                }
            }
        }

        assertEquals(violations.joinToString("\n"), emptyList<String>(), violations)
    }

    @Test
    fun theTestItself_findsAViolationWhenThereIsOne() {
        // A purity test that silently scans nothing is worse than none at all — this pins that the
        // scanner actually reads files and actually matches.
        val sources = kotlinSources()
        assertTrue("expected to scan some sources, scanned ${sources.size}", sources.size > 5)

        val fabricated = "import android.content.Context"
        assertTrue(forbiddenImports.keys.any { fabricated.removePrefix("import ").startsWith(it) })
    }
}
