package net.devemperor.dictate.history

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R3 systemic-color-layer regression-lock (history-ui-overhaul, ADR-0010).
 *
 * The history icons rendered black-on-black / white-on-white because their
 * vector drawables carry baked tint literals and the consuming views declared
 * no usage-site tint (see ADR-0010 for the full root cause). ADR-0010's
 * enforcement clause makes the fix a *convention plus a mechanical backstop*,
 * not a batch of per-icon color patches: this pure-JVM source scan fails on
 * hex color literals, `@android:color/holo_*`, and tint-less icon views in the
 * history layouts, so a re-imported icon with a baked literal — or a new icon
 * view added without a style/tint — turns the suite red before it can ship a
 * black button.
 *
 * The file set is enumerated by directory + a history-file predicate (ADR-0010
 * failure-mode 3: "the test must enumerate by glob/directory, not by hardcoded
 * file names, wherever feasible") so a newly added history layout is
 * auto-covered. Precedent for the pure-JVM source-scan form (K-1: no
 * Robolectric, no mocking, each ban paired with a scanner self-test so it
 * cannot go false-GREEN): [HistoryDetailJobRoutingInvariantTest],
 * `net.devemperor.dictate.state.layout.MotionSceneSchemaTest`.
 */
class HistoryThemeInvariantTest {

    private val layoutDir = File("src/main/res/layout")

    /**
     * History layouts under scan. The explicit stems anchor the auto-discovery
     * (a history layout must contain one of these tokens); `activity_history`,
     * `item_history`, `item_pipeline`, and the reprocess/prompt dialogs match
     * by prefix so a future `item_history_*` / `item_pipeline_*` file is
     * covered automatically (ADR-0010 failure-mode 3).
     */
    private fun isHistoryLayout(name: String): Boolean {
        if (!name.endsWith(".xml")) return false
        val stem = name.removeSuffix(".xml")
        return stem.startsWith("activity_history") ||
            stem.startsWith("item_history") ||
            stem.startsWith("item_pipeline") ||
            stem.startsWith("dialog_prompt_chooser") ||
            stem.startsWith("item_prompt_chooser") ||
            stem.startsWith("dialog_reprocess_queue") ||
            stem.startsWith("item_reprocess_queue")
    }

    private fun historyLayoutFiles(): List<File> {
        assertTrue("layout dir moved? ${layoutDir.absolutePath}", layoutDir.isDirectory)
        val files = layoutDir.listFiles { f -> f.isFile && isHistoryLayout(f.name) }.orEmpty()
        // Non-vacuity: the seven+ named history layouts must actually resolve.
        assertTrue(
            "expected the history layout set to be discovered under $layoutDir, " +
                "found ${files.map { it.name }}",
            files.size >= 8
        )
        return files.sortedBy { it.name }
    }

    // ── (a) no hex color literal attribute values ────────────────────────

    @Test
    fun `no history layout carries a hex color literal`() {
        historyLayoutFiles().forEach { file ->
            val hits = hexColorAttributeValues(file.readText())
            assertTrue(
                "${file.name}: hex color literal(s) $hits — icon/text color must come " +
                    "from a theme attr at the usage site (ADR-0010). Use ?attr/... or a " +
                    "named color resource, never an inline #RRGGBB.",
                hits.isEmpty()
            )
        }
    }

    // ── (b) no @android:color/holo_* ─────────────────────────────────────

    @Test
    fun `no history layout references an android holo color`() {
        historyLayoutFiles().forEach { file ->
            val hits = Regex("""@android:color/holo_[A-Za-z_]+""")
                .findAll(file.readText()).map { it.value }.toList()
            assertTrue(
                "${file.name}: $hits — @android:color/holo_* is not DayNight-aware. " +
                    "Error text uses ?attr/colorError, warning text ?attr/dictateColorWarning " +
                    "(ADR-0010).",
                hits.isEmpty()
            )
        }
    }

    // ── (c) every ImageButton / ic_* ImageView declares a style or tint ──

    @Test
    fun `every image button and ic image view declares a usage-site tint`() {
        historyLayoutFiles().forEach { file ->
            untintedIconViews(file.readText()).forEach { tag ->
                assertTrue(
                    "${file.name}: an <${tag.element}> renders an icon without a usage-site " +
                        "tint. Add style=\"@style/Widget.Dictate.HistoryIconButton\" or an " +
                        "explicit ?attr/ tint via app:tint / android:tint (ADR-0010). Offending " +
                        "element:\n${tag.snippet}",
                    false
                )
            }
        }
    }

    // ── Non-vacuity self-tests: the scanners must catch the real thing ───

    @Test
    fun `hex scanner catches a literal and ignores non-color hashes`() {
        assertEquals(
            listOf("#000000"),
            hexColorAttributeValues("""<TextView android:textColor="#000000" />""")
        )
        assertEquals(
            listOf("#FFB74D", "#B26A00"),
            hexColorAttributeValues(
                """<x android:tint="#FFB74D" app:strokeColor="#B26A00" />"""
            )
        )
        // A theme-attr reference or resource reference is not a hex literal.
        assertTrue(
            hexColorAttributeValues(
                """<x android:tint="?attr/colorError" android:text="@string/x" />"""
            ).isEmpty()
        )
    }

    @Test
    fun `icon-view scanner flags a tint-less image button and clears a styled one`() {
        val bad = """<ImageButton android:src="@drawable/ic_baseline_delete_24" />"""
        assertEquals(1, untintedIconViews(bad).size)

        val styled = """<ImageButton style="@style/Widget.Dictate.HistoryIconButton"
            android:src="@drawable/ic_baseline_delete_24" />"""
        assertTrue(untintedIconViews(styled).isEmpty())

        val tinted = """<ImageButton app:tint="?attr/colorOnSurfaceVariant"
            android:src="@drawable/ic_baseline_delete_24" />"""
        assertTrue(untintedIconViews(tinted).isEmpty())

        // A plain (non-icon) ImageView without a tint is not flagged.
        val plainImage = """<ImageView android:src="@drawable/logo" />"""
        assertTrue(untintedIconViews(plainImage).isEmpty())

        // An ImageView whose ic_* src is only in tools: (bound at runtime) IS flagged.
        val toolsIcon = """<ImageView tools:src="@drawable/ic_baseline_pending_24" />"""
        assertEquals(1, untintedIconViews(toolsIcon).size)
    }

    // ── Scanners ─────────────────────────────────────────────────────────

    /** All inline `#RRGGBB(AA)` / `#RGB` values that appear as attribute values. */
    private fun hexColorAttributeValues(xml: String): List<String> =
        Regex("""="(#[0-9a-fA-F]{3,8})"""").findAll(xml).map { it.groupValues[1] }.toList()

    private data class IconViewHit(val element: String, val snippet: String)

    /**
     * Every `<ImageButton …>` element, plus every `<ImageView …>` whose
     * `android:src` or `tools:src` points at an `ic_*` drawable, that does NOT
     * declare a `style=` or a `?attr/`-based `app:tint` / `android:tint`.
     * `tools:src` counts because the list status icon sets its real `ic_*` src
     * at bind time and only reflects it via `tools:src` at design time.
     */
    private fun untintedIconViews(xml: String): List<IconViewHit> {
        val hits = mutableListOf<IconViewHit>()
        val tagRegex = Regex("""<(ImageButton|ImageView)\b([^>]*?)/?>""", RegexOption.DOT_MATCHES_ALL)
        for (m in tagRegex.findAll(xml)) {
            val element = m.groupValues[1]
            val attrs = m.groupValues[2]

            val isImageButton = element == "ImageButton"
            val srcIsIcon = Regex("""(?:android|tools):src="@drawable/ic_[A-Za-z0-9_]+"""")
                .containsMatchIn(attrs)
            if (!isImageButton && !srcIsIcon) continue

            val hasStyle = Regex("""\bstyle="@style/""").containsMatchIn(attrs)
            val hasAttrTint = Regex("""(?:app|android):tint="\?(?:attr|android:attr)/""")
                .containsMatchIn(attrs)
            if (hasStyle || hasAttrTint) continue

            hits += IconViewHit(element, m.value.trim())
        }
        return hits
    }

    private fun assertEquals(expected: Any?, actual: Any?) =
        org.junit.Assert.assertEquals(expected, actual)
}
