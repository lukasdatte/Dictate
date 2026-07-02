package net.devemperor.dictate.state.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Schema-level check for `res/xml/motion_scene_keyboard.xml`.
 *
 * **Scope:** JVM-pure XML-parser tests that verify the MotionScene
 * structure matches the contract Spec 2 §7 / ADR-0004 lays down. The
 * runtime semantics (transitionToState wiring, animation timing) are
 * out of scope here — they need an `ImeViewBackend` and live in C14's
 * test surface.
 *
 * **Why JVM-pure?** MotionLayout itself is Android-API-bound (would
 * require Robolectric or Espresso). XML well-formedness, ConstraintSet
 * inventory, and visibilityMode=ignore presence don't need that — a
 * raw DOM parse is enough to keep this test in the fast JVM suite
 * (K-1 / K-4 compliance per chunk acceptance).
 *
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §7 / §7.3
 * @see docs/decisions/0004-ui-layout-catalog-motionlayout.md §3
 */
class MotionSceneSchemaTest {

    private val sceneFile = File("src/main/res/xml/motion_scene_keyboard.xml")

    /**
     * The five ConstraintSet ids the scene MUST declare — one per
     * KEYBOARD-side [LayoutModeId]. The mapping is what
     * `ImeViewBackend.toSceneStateId()` (Spec 2 §6) relies on, so any
     * drift here breaks the C14 binding.
     */
    private val requiredConstraintSetIds = setOf(
        "two_row_state",
        "single_row_state",
        "two_row_send_mode_state",
        "single_row_send_mode_state",
        "reprocess_staging_state",
    )

    /**
     * The ten button view-ids the LayoutCatalog hands to
     * `ImeViewBackend.buttonViews` (Spec 2 §6, plus the ADR-0009
     * `secondary_record_btn`). Every one of them MUST carry
     * `motion:visibilityMode="ignore"` so the Catalog stays the sole
     * Visibility owner (Spec 2 §7.3 / R.11 — non-negotiable). Missing the
     * marker on `secondary_record_btn` would compound the reverse-transition
     * fade hazard (see the SEND_MODE→base reverse-transition test) into a
     * permanent stranded-visible bug rather than a transition-window one.
     */
    private val visibilityIgnoreButtonIds = setOf(
        "record_btn",
        "resend_btn",
        "secondary_record_btn",
        "backspace_btn",
        "audio_focus_btn",
        "widget_toggle_btn",
        "trash_btn",
        "space_btn",
        "pause_btn",
        "enter_btn",
    )

    @Test
    fun `motion scene file exists`() {
        assertTrue(
            "Expected MotionScene at ${sceneFile.absolutePath}",
            sceneFile.exists(),
        )
    }

    @Test
    fun `motion scene declares all five required ConstraintSets`() {
        val constraintSetIds = parseConstraintSetIds()
        val missing = requiredConstraintSetIds - constraintSetIds
        if (missing.isNotEmpty()) {
            fail("MotionScene is missing ConstraintSet ids: $missing (found: $constraintSetIds)")
        }
    }

    @Test
    fun `every required button carries visibilityMode=ignore in the base state`() {
        // The base `two_row_state` is the canonical position-owner; siblings
        // derive from it. Per Spec 2 §7.3, EVERY state-driven button gets
        // visibilityMode=ignore explicitly so derive-from cannot silently
        // drop it.
        val baseState = constraintSetById("two_row_state")
            ?: fail("Base ConstraintSet `two_row_state` missing — cannot run visibility check")
                as Nothing
        val ignoredIds = collectVisibilityIgnoreIds(baseState)
        val missing = visibilityIgnoreButtonIds - ignoredIds
        if (missing.isNotEmpty()) {
            fail(
                "Buttons missing motion:visibilityMode=\"ignore\" in two_row_state: $missing " +
                "(found ignore=true for: $ignoredIds)",
            )
        }
    }

    @Test
    fun `every Constraint block in every ConstraintSet carries visibilityMode=ignore`() {
        // B4-VAL F-8: derive-from siblings can silently drop the marker. Spec
        // 2 §7.3 / R.11 is non-negotiable — every `<Constraint>` block that
        // names one of the state-driven button ids in ANY of the 5
        // ConstraintSets must explicitly declare the PropertySet. A derived
        // ConstraintSet without an override still gets the parent's
        // PropertySet, so this test asserts on declared <Constraint> blocks
        // only (a missing Constraint is OK — it inherits — but a present
        // Constraint without the marker would override the inherited one).
        val sets = parseConstraintSetElements()
        require(sets.isNotEmpty()) { "No ConstraintSets parsed — XML smoke" }

        sets.forEach { set ->
            val setId = extractId(set) ?: error("Anonymous ConstraintSet — id missing")
            val constraints = set.getElementsByTagName("Constraint")
            for (i in 0 until constraints.length) {
                val c = constraints.item(i) as Element
                val cId = extractId(c) ?: continue
                if (cId !in visibilityIgnoreButtonIds) continue
                // Found a Constraint block for a state-driven button — it
                // MUST carry the PropertySet.
                val propertySets = c.getElementsByTagName("PropertySet")
                var hasIgnore = false
                for (j in 0 until propertySets.length) {
                    val p = propertySets.item(j) as Element
                    val mode = p.getAttributeNS(MOTION_NS, "visibilityMode").ifBlank {
                        p.getAttribute("motion:visibilityMode")
                    }
                    if (mode == "ignore") { hasIgnore = true; break }
                }
                if (!hasIgnore) {
                    fail(
                        "ConstraintSet `$setId` has a <Constraint android:id=\"@+id/$cId\"> " +
                            "block without <PropertySet motion:visibilityMode=\"ignore\"/> " +
                            "(Spec 2 §7.3 / R.11 — non-negotiable).",
                    )
                }
            }
        }
    }

    @Test
    fun `motion scene declares transitions between adjacent constraint sets`() {
        val transitions = parseTransitions()
        // Spec 2 §7.1 transitions table:
        // (two_row ↔ single_row 250ms),
        // (two_row ↔ two_row_send_mode 200ms),
        // (single_row ↔ single_row_send_mode 200ms),
        // (two_row ↔ reprocess_staging 200ms),
        // (single_row ↔ reprocess_staging 200ms).
        val expectedPairs = setOf(
            "two_row_state" to "single_row_state",
            "two_row_state" to "two_row_send_mode_state",
            "single_row_state" to "single_row_send_mode_state",
            "two_row_state" to "reprocess_staging_state",
            "single_row_state" to "reprocess_staging_state",
        )
        val actualPairs = transitions.map { it.start to it.end }.toSet()
        val missing = expectedPairs - actualPairs
        if (missing.isNotEmpty()) {
            fail("MotionScene missing transition pairs: $missing (found: $actualPairs)")
        }
    }

    @Test
    fun `SEND_MODE to base reverse transitions are declared (ADR-0009 recording-wins)`() {
        // Regression pin for the concurrent-recording double-record-icon bug.
        //
        // ADR-0009's `forKeyboard` recording-wins precedence makes a NEW
        // scene edge reachable: while a pipeline run processes (SEND_MODE),
        // a RECORD_SECONDARY tap starts a recording and the scene moves
        // SEND_MODE → base (two_row_send_mode_state → two_row_state, and the
        // single-row twin). Before this feature that edge was unreachable —
        // a recording could never coexist with a live pipeline.
        //
        // `MotionScene.setTransition(int,int)` matches a declared
        // `<Transition>` in ONE direction only (verified against the
        // constraintlayout-2.2.1 bytecode). An UNDECLARED edge falls back to
        // a synthesized `mDefaultTransition` (plain fade) that does NOT carry
        // the per-view `visibilityMode="ignore"` PropertySets — the exact
        // hazard the F-25 note in the scene warns about. `secondary_record_btn`
        // is the only button that flips VISIBLE→GONE across this edge, so the
        // fade leaves it stranded VISIBLE while the catalog already set it
        // GONE → the primary record surface AND the secondary mic render at
        // once (the on-device "two record icons" report).
        //
        // Only the forward edges (base → SEND_MODE) were declared; both
        // reverse edges must exist so the catalog stays the sole visibility
        // owner across the recording-starts-during-pipeline transition.
        val transitions = parseTransitions()
        val actualPairs = transitions.map { it.start to it.end }.toSet()
        val requiredReversePairs = setOf(
            "two_row_send_mode_state" to "two_row_state",
            "single_row_send_mode_state" to "single_row_state",
        )
        val missing = requiredReversePairs - actualPairs
        if (missing.isNotEmpty()) {
            fail(
                "MotionScene is missing the SEND_MODE→base reverse transitions " +
                    "$missing — the undeclared edge falls back to a fade that drops " +
                    "visibilityMode=ignore, stranding secondary_record_btn visible " +
                    "(ADR-0009 double-record-icon regression). Found: $actualPairs",
            )
        }
    }

    @Test
    fun `transitions declare positive durations`() {
        val transitions = parseTransitions()
        transitions.forEach { t ->
            assertTrue(
                "Transition ${t.start} → ${t.end} must declare a positive duration, got ${t.durationMs}",
                t.durationMs > 0,
            )
        }
    }

    @Test
    fun `single row state derives from two row state`() {
        // Spec 2 §7.1: `single_row_state` overrides the chain wholesale but
        // declares `motion:deriveConstraintsFrom="@+id/two_row_state"` so
        // any constraint we don't override inherits cleanly.
        val sets = parseConstraintSetElements()
        val singleRow = sets.firstOrNull { extractId(it) == "single_row_state" }
            ?: fail("single_row_state missing — cannot check derive-from") as Nothing
        val derive = singleRow.getAttributeNS(MOTION_NS, "deriveConstraintsFrom")
            .ifBlank { singleRow.getAttribute("motion:deriveConstraintsFrom") }
        assertTrue(
            "single_row_state must declare motion:deriveConstraintsFrom=@+id/two_row_state " +
                "(found: \"$derive\")",
            derive.endsWith("two_row_state"),
        )
    }

    @Test
    fun `send mode and staging states derive from their non-send siblings`() {
        // Spec 2 §7.1: send-mode + staging are position-identical to their
        // base state; they exist only as distinct transition end-points
        // (transitioning to the same constraintSet is a no-op).
        val sets = parseConstraintSetElements()
        val expectedDeriveFrom = mapOf(
            "two_row_send_mode_state" to "two_row_state",
            "single_row_send_mode_state" to "single_row_state",
            "reprocess_staging_state" to "two_row_state",
        )
        expectedDeriveFrom.forEach { (childId, expectedParent) ->
            val node = sets.firstOrNull { extractId(it) == childId }
                ?: fail("$childId missing — cannot check derive-from") as Nothing
            val derive = node.getAttributeNS(MOTION_NS, "deriveConstraintsFrom")
                .ifBlank { node.getAttribute("motion:deriveConstraintsFrom") }
            assertTrue(
                "$childId must derive from $expectedParent (found: \"$derive\")",
                derive.endsWith(expectedParent),
            )
        }
    }

    // ─── Parsing helpers ──────────────────────────────────────────────

    private data class TransitionInfo(val start: String, val end: String, val durationMs: Int)

    /**
     * MotionLayout's XML namespace. The `motion:` prefix on the
     * declaration maps to `http://schemas.android.com/apk/res-auto`,
     * the same namespace used by the regular `app:` prefix — Android
     * resource attributes resolve by namespace, not by prefix.
     */
    private val MOTION_NS = "http://schemas.android.com/apk/res-auto"
    private val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    private fun parseDocument() =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(sceneFile)

    private fun parseConstraintSetElements(): List<Element> {
        val doc = parseDocument()
        val list: NodeList = doc.getElementsByTagName("ConstraintSet")
        return (0 until list.length).map { list.item(it) as Element }
    }

    private fun parseConstraintSetIds(): Set<String> =
        parseConstraintSetElements().mapNotNull(::extractId).toSet()

    private fun constraintSetById(id: String): Element? =
        parseConstraintSetElements().firstOrNull { extractId(it) == id }

    private fun extractId(el: Element): String? {
        // android:id attribute, namespace-aware. Values look like
        // "@+id/two_row_state" or "@id/two_row_state".
        val raw = el.getAttributeNS(ANDROID_NS, "id").ifBlank {
            el.getAttribute("android:id")
        }
        if (raw.isBlank()) return null
        return raw.substringAfterLast('/')
    }

    /**
     * Collects the ids of every `<Constraint>` inside [constraintSet]
     * whose nested `<PropertySet motion:visibilityMode="ignore" />`
     * declares ignore. We DON'T look at descendant constraint-sets —
     * derive-from semantics are checked separately.
     */
    private fun collectVisibilityIgnoreIds(constraintSet: Element): Set<String> {
        val constraints = constraintSet.getElementsByTagName("Constraint")
        val out = mutableSetOf<String>()
        for (i in 0 until constraints.length) {
            val c = constraints.item(i) as Element
            val id = extractId(c) ?: continue
            val propertySets = c.getElementsByTagName("PropertySet")
            for (j in 0 until propertySets.length) {
                val p = propertySets.item(j) as Element
                val mode = p.getAttributeNS(MOTION_NS, "visibilityMode").ifBlank {
                    p.getAttribute("motion:visibilityMode")
                }
                if (mode == "ignore") {
                    out += id
                    break
                }
            }
        }
        return out
    }

    private fun parseTransitions(): List<TransitionInfo> {
        val doc = parseDocument()
        val list: NodeList = doc.getElementsByTagName("Transition")
        return (0 until list.length).mapNotNull { i ->
            val t = list.item(i) as Element
            val start = (t.getAttributeNS(MOTION_NS, "constraintSetStart")
                .ifBlank { t.getAttribute("motion:constraintSetStart") })
                .substringAfterLast('/')
            val end = (t.getAttributeNS(MOTION_NS, "constraintSetEnd")
                .ifBlank { t.getAttribute("motion:constraintSetEnd") })
                .substringAfterLast('/')
            val durationRaw = t.getAttributeNS(MOTION_NS, "duration")
                .ifBlank { t.getAttribute("motion:duration") }
            val duration = durationRaw.toIntOrNull() ?: return@mapNotNull null
            if (start.isBlank() || end.isBlank()) return@mapNotNull null
            TransitionInfo(start, end, duration)
        }
    }

    /**
     * Sanity assertion that the parser found a `<ConstraintSet>` at all
     * — guards against a silently empty XML file (would produce zero
     * findings and pass the missing-set check).
     */
    @Test
    fun `motion scene contains at least one ConstraintSet`() {
        val sets = parseConstraintSetElements()
        assertNotNull("Failed to parse MotionScene XML", sets)
        assertEquals(
            "MotionScene must declare exactly five ConstraintSets (one per KEYBOARD LayoutModeId)",
            5,
            sets.size,
        )
    }
}
