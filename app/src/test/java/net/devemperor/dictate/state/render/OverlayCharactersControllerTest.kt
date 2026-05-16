package net.devemperor.dictate.state.render

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.BuildConfig
import net.devemperor.dictate.R
import net.devemperor.dictate.core.audit.VisibilityWriteAuditLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [OverlayCharactersController] (B5 CR-EXTRACT —
 * CR4-IMPL-1 resolution; the overlay-chars third NO-owner sub-axis).
 *
 * Robolectric K-4 justified (the controller inflates real
 * `item_overlay_characters` TextViews + mutates real View state).
 *
 * # Coverage focus
 *
 *  1. **RR-2 dormant invariant (load-bearing).** With a dormant
 *     [RenderGate], `initialize()` does **not** inflate (the legacy
 *     `MainButtonsController` stays the sole live inflater — inflating
 *     here too would double the child count) and `update()` does not
 *     mutate. The ledger records the suppressed write
 *     (`doubleWriteCount == 0`).
 *  2. **CR4 flip.** After `arm()`, `initialize()` inflates exactly 8
 *     views and `update()` is byte-equivalent to the legacy
 *     `updateOverlayCharacters`.
 *  3. **`null` gate = legacy always-do-it contract.**
 *  4. **Idempotent inflate guard** — re-`initialize()` after arm does
 *     not stack a second set of 8.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayCharactersControllerTest {

    private lateinit var ctx: Context
    private lateinit var strip: LinearLayout

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        ctx = ContextThemeWrapper(app, R.style.Theme_Dictate)
        strip = LinearLayout(ctx).apply { id = R.id.overlay_characters_ll }
    }

    private fun controller(gate: RenderGate?) =
        OverlayCharactersController(OverlayCharactersViews(strip), gate)

    // ── 1. RR-2 dormant invariant ─────────────────────────────────────

    @Test
    fun dormant_gate_does_NOT_inflate_the_8_views() {
        val gate = RenderGate("OverlayCharactersController", auditLogger = null)
        val c = controller(gate)

        c.initialize()

        assertEquals(
            "dormant initialize() must NOT inflate (legacy stays the sole " +
                "live inflater — RR-2; inflating here too would double the " +
                "child count)",
            0, strip.childCount,
        )
    }

    @Test
    fun dormant_gate_does_NOT_mutate_on_update() {
        val gate = RenderGate("OverlayCharactersController", auditLogger = null)
        // Pre-populate as if the legacy controller had inflated.
        repeat(8) { strip.addView(TextView(ctx)) }
        val c = controller(gate)

        c.update("abc", 0xFF00FF00.toInt())

        // Dormant: no visibility/text written by us.
        for (i in 0 until strip.childCount) {
            val tv = strip.getChildAt(i) as TextView
            assertEquals("dormant must not set text", "", tv.text.toString())
        }
    }

    @Test
    fun dormant_report_does_not_conflict_with_legacy_live_writer_RR2() {
        assumeTrue("audit logger is DEBUG-guarded", BuildConfig.DEBUG)
        val logger = VisibilityWriteAuditLogger()
        val gate = RenderGate("OverlayCharactersController", logger)
        val c = controller(gate)

        logger.beginRenderGeneration()
        // The legacy MainButtonsController is the live writer this gen.
        logger.logWrite(strip.id, "MainButtonsController", View.VISIBLE, live = true)

        c.initialize()  // dormant → reports suppressed write only

        assertEquals(
            "dormant report must not be a double-write vs the legacy live writer",
            0, logger.doubleWriteCount,
        )
        assertEquals("MainButtonsController", logger.soleLiveWriterOf(strip.id))
        assertTrue(
            logger.dormantReportersOf(strip.id).contains("OverlayCharactersController"),
        )
    }

    // ── 2. CR4 flip (armed) ───────────────────────────────────────────

    @Test
    fun armed_gate_inflates_exactly_8_views() {
        val gate = RenderGate("OverlayCharactersController", auditLogger = null)
        gate.arm()
        val c = controller(gate)

        c.initialize()

        assertEquals(
            OverlayCharactersController.OVERLAY_CHAR_COUNT, strip.childCount,
        )
    }

    @Test
    fun armed_update_is_byte_equivalent_to_legacy_updateOverlayCharacters() {
        val gate = RenderGate("OverlayCharactersController", auditLogger = null)
        gate.arm()
        val c = controller(gate)
        c.initialize()

        c.update("xy", 0xFFAB12CD.toInt())

        // Slots 0..1 visible with the glyph; 2..7 GONE (legacy parity).
        val s0 = strip.getChildAt(0) as TextView
        val s1 = strip.getChildAt(1) as TextView
        val s2 = strip.getChildAt(2) as TextView
        assertEquals(View.VISIBLE, s0.visibility)
        assertEquals("x", s0.text.toString())
        assertEquals(View.VISIBLE, s1.visibility)
        assertEquals("y", s1.text.toString())
        assertEquals(View.GONE, s2.visibility)
    }

    // ── 3. null gate = legacy always-do-it ────────────────────────────

    @Test
    fun null_gate_inflates_and_updates_immediately_legacy_contract() {
        val c = controller(gate = null)

        c.initialize()
        c.update("z", 0xFF112233.toInt())

        assertEquals(
            OverlayCharactersController.OVERLAY_CHAR_COUNT, strip.childCount,
        )
        val s0 = strip.getChildAt(0) as TextView
        assertEquals(View.VISIBLE, s0.visibility)
        assertEquals("z", s0.text.toString())
    }

    // ── 4. Idempotent inflate guard ───────────────────────────────────

    @Test
    fun re_initialize_after_arm_does_not_stack_a_second_set_of_8() {
        val gate = RenderGate("OverlayCharactersController", auditLogger = null)
        gate.arm()
        val c = controller(gate)

        c.initialize()
        c.initialize()  // idempotent — childCount guard

        assertEquals(
            OverlayCharactersController.OVERLAY_CHAR_COUNT, strip.childCount,
        )
    }
}
