package net.devemperor.dictate.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architecture-invariant test: `domain/` stays free of frameworks.
 *
 * The dependency direction is `server/` → `domain/` ← `data/`, `ui/`, `platform/`. The compiler
 * cannot enforce it — every one of those libraries is on this module's single classpath — so it is
 * enforced here, by reading the sources (which is also why the failure message can name the file
 * and the line).
 *
 * What it buys is not purity for its own sake: it is that the pairing rules, the dispatch order and
 * the sync invariants can be tested without a socket, a database file or a Windows box, and that a
 * change to any of those three never drags the other two along.
 *
 * Precedent: `SharedPurityTest` in `:shared` (ADR-0015).
 */
class CompanionLayeringTest {

    private val forbiddenInDomain = mapOf(
        "io.ktor" to "Ktor — HTTP is the server layer's business; domain rules must be testable without a socket",
        "app.cash.sqldelight" to "SQLDelight — persistence sits behind the repository ports",
        "com.sun.jna" to "JNA — Win32 sits behind TextInserter/AutostartManager, or the domain stops building on Linux",
        "androidx.compose" to "Compose — the UI depends on the domain, never the other way round",
        "org.jetbrains.compose" to "Compose — the UI depends on the domain, never the other way round",
        "java.awt" to "AWT — clipboard and tray belong to platform/, behind a port",
    )

    /** Gradle runs a test with the module directory as its working directory. */
    private val domainRoot = File("src/main/kotlin/net/devemperor/dictate/companion/domain")

    @Test
    fun domainLayer_hasNoFrameworkImports() {
        val sources = domainSources()
        assertTrue("expected to scan the domain sources, scanned ${sources.size}", sources.size > 5)

        val violations = mutableListOf<String>()
        sources.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("import ")) return@forEachIndexed
                val imported = trimmed.removePrefix("import ")
                forbiddenInDomain.forEach { (prefix, why) ->
                    if (imported.startsWith(prefix)) {
                        violations += "${file.path}:${index + 1} imports `$imported` — forbidden: $why"
                    }
                }
            }
        }

        assertEquals(violations.joinToString("\n"), emptyList<String>(), violations)
    }

    private fun domainSources(): List<File> {
        assertTrue("missing domain root: ${domainRoot.absolutePath}", domainRoot.isDirectory)
        return domainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
