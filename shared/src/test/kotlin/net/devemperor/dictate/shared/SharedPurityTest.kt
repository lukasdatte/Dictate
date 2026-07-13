package net.devemperor.dictate.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architecture-invariant test: `:shared` stays pure.
 *
 * The compiler already blocks `android.*` (no `android.jar` on this module's classpath), but it
 * cannot block the three imports that would quietly destroy the module's reason to exist:
 *
 * - **coroutines** — `:app` pins kotlinx-coroutines 1.7.3. A coroutine API in here would drag a
 *   version constraint across a module boundary that we explicitly refused to couple (ADR-0015).
 * - **Ktor** — the server belongs to the companion alone, and Ktor's server needs JDK 11+ while
 *   this module compiles at jvmTarget 1.8 for `:app`. An import here would be a build break
 *   waiting for the first person who touches `:app`.
 * - **androidx** — would make the module unusable from the desktop companion.
 *
 * Precedent: the app already pins an IME-side `JobExecutor.start` invariant the same way (ADR-0013).
 * The test reads the sources rather than the bytecode so its failure message can name the file
 * and the line the offending import sits on.
 */
class SharedPurityTest {

    private val forbiddenImports = mapOf(
        "android." to "Android APIs — :shared must stay consumable from the desktop companion",
        "androidx." to "AndroidX APIs — :shared must stay consumable from the desktop companion",
        "kotlinx.coroutines" to "coroutines — :app pins 1.7.3; :shared is blocking-by-design (ADR-0015)",
        "io.ktor" to "Ktor — the server lives in :companion only; :shared compiles at jvmTarget 1.8 (ADR-0015)",
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
    fun sharedModule_hasNoForbiddenImports() {
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
