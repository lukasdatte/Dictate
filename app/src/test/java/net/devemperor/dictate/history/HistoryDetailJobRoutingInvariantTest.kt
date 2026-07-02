package net.devemperor.dictate.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F-055 / F-111 structural regression-lock (history-reprocess-hardening).
 *
 * `HistoryDetailActivity` used to run AI completions on an Activity-scoped
 * executor (killed by rotation, invisible to `ActiveJobRegistry`) and used to
 * create the POST_PROCESSING session row BEFORE the prompt chooser was even
 * answered (dismissing the sheet leaked a permanent ghost "Recorded" row).
 * After the hardening, the Activity is a thin dispatcher: DB session rows and
 * AI calls exist only in the job layer (`JobExecutor` →
 * `PipelineOrchestrator`).
 *
 * Like [net.devemperor.dictate.core.CutoverArchitectureInvariantTest], this
 * is a pure-JVM source-scan (K-1: no Robolectric, no mocking): comments and
 * string literals are stripped so historical doc-anchors stay free, then the
 * remaining functional code is asserted token-free. Each ban is paired with a
 * stripper self-test so the lock cannot silently go false-GREEN.
 */
class HistoryDetailJobRoutingInvariantTest {

    private val activitySource = File(
        "src/main/java/net/devemperor/dictate/history/HistoryDetailActivity.java"
    )

    private fun functionalCode(): String {
        assertTrue("source file moved? ${activitySource.absolutePath}", activitySource.isFile)
        return stripCommentsAndStrings(activitySource.readText())
    }

    // ── F-111: no session row creation in the Activity ───────────────────

    @Test
    fun `activity never creates a session row - the job body owns the POST_PROCESSING lifecycle`() {
        val code = functionalCode()
        assertEquals(
            "HistoryDetailActivity must not call createSession — the " +
                "POST_PROCESSING row is created inside " +
                "PipelineOrchestrator.runPostProcessingBlocking (F-111): a " +
                "dismissed chooser must leave no orphan row.",
            0,
            Regex("""createSession\s*\(""").findAll(code).count()
        )
    }

    // ── F-055: no Activity-local AI execution ────────────────────────────

    @Test
    fun `activity holds no AIOrchestrator and no regenerate executor`() {
        val code = functionalCode()
        assertEquals(
            "HistoryDetailActivity must not reference AIOrchestrator — AI " +
                "calls go through JobExecutor (F-055).",
            0,
            Regex("""\bAIOrchestrator\b""").findAll(code).count()
        )
        assertEquals(
            "regenerateExecutor was deleted (F-055) — do not reintroduce " +
                "Activity-scoped AI executors.",
            0,
            Regex("""\bregenerateExecutor\b""").findAll(code).count()
        )
    }

    // ── F-110: the V1 free-text dead-end is gone ─────────────────────────

    @Test
    fun `reprocess-with-edit no longer routes through the V1 chooser dead-end`() {
        val code = functionalCode()
        assertEquals(
            "The 'Please pick a saved prompt' toast dead-end was removed " +
                "(F-110) — reprocess-with-edit goes through " +
                "ReprocessQueueEditorBottomSheet, whose slot queue carries " +
                "free-text content. Do not reintroduce the string reference.",
            0,
            Regex("""dictate_history_reprocess_edit_needs_saved_prompt""")
                .findAll(code).count()
        )
        assertEquals(
            "Reprocess-with-edit must open the queue editor, not the V1 " +
                "single-prompt chooser (F-110).",
            1,
            Regex("""ReprocessQueueEditorBottomSheet\s*\n?\s*\.newInstance""")
                .findAll(code).count()
        )
    }

    @Test
    fun `the dead-end string resource is deleted in every locale`() {
        val resDir = File("src/main/res")
        val stringsFiles = resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values") }
            ?.mapNotNull { dir -> File(dir, "strings.xml").takeIf { it.isFile } }
            .orEmpty()
        assertTrue("expected locale strings.xml files under $resDir", stringsFiles.size >= 4)
        stringsFiles.forEach { file ->
            assertFalse(
                "${file.path}: dictate_history_reprocess_edit_needs_saved_prompt existed " +
                    "solely for the V1 free-text dead-end (F-110) and must stay deleted.",
                file.readText().contains("dictate_history_reprocess_edit_needs_saved_prompt")
            )
        }
    }

    // ── Non-vacuity: the stripper keeps code hits and drops doc hits ─────

    @Test
    fun `stripper keeps functional tokens and drops comment or string occurrences`() {
        val snippet = """
            // sessionManager.createSession(x) — doc anchor, must NOT count
            /* AIOrchestrator in a block comment, must NOT count */
            String s = "createSession(inside a string)";
            sessionManager.createSession(real);
        """.trimIndent()
        val stripped = stripCommentsAndStrings(snippet)
        assertEquals(1, Regex("""createSession\s*\(""").findAll(stripped).count())
        assertEquals(0, Regex("""\bAIOrchestrator\b""").findAll(stripped).count())
    }

    /**
     * Comment + string-literal stripper (same algorithm as
     * `CutoverArchitectureInvariantTest`, kept local so the two locks stay
     * independently movable).
     */
    private fun stripCommentsAndStrings(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            val next = if (i + 1 < n) source[i + 1] else '\u0000'
            when {
                c == '/' && next == '/' -> {
                    i += 2
                    while (i < n && source[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    i += 2
                    while (i < n && !(source[i] == '*' && i + 1 < n && source[i + 1] == '/')) i++
                    i += 2
                }
                c == '"' -> {
                    if (next == '"' && i + 2 < n && source[i + 2] == '"') {
                        i += 3
                        while (i + 2 < n && !(source[i] == '"' && source[i + 1] == '"' && source[i + 2] == '"')) i++
                        i += 3
                    } else {
                        i++
                        while (i < n && source[i] != '"') {
                            if (source[i] == '\\' && i + 1 < n) i++
                            i++
                        }
                        i++
                    }
                    out.append(' ')
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
