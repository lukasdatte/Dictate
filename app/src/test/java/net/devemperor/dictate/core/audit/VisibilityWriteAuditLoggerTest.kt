package net.devemperor.dictate.core.audit

import net.devemperor.dictate.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [VisibilityWriteAuditLogger] — the CR3 Strict-Mode
 * no-double-write ledger (Spec 2 §10 / §11.8 5c, render-path-cutover.md
 * §6 RR-2).
 *
 * # Why pure JVM (no Robolectric)
 *
 * The logger keys on `Int` view-ids + `String` owner tags only — no
 * Android view, no `Context`. Plain JUnit is sufficient (K-4: the
 * heavier Robolectric runner is *not* used because nothing here needs
 * it).
 *
 * # The invariant under test (RR-2, the highest risk of Block B5)
 *
 * The cutover's safety hinges on "exactly **one LIVE writer** per
 * visibility axis per render generation". A *dormant* controller report
 * (CR3 — attached but gated off) must NOT be mistaken for a conflict
 * with the legacy KSM live write; only **two distinct LIVE writers**
 * in one generation is the silent-flicker regression Spec 2 §10
 * forbids.
 */
class VisibilityWriteAuditLoggerTest {

    private lateinit var logger: VisibilityWriteAuditLogger

    @Before
    fun setUp() {
        // The logger is BuildConfig.DEBUG-guarded by design (free in
        // release). testDebugUnitTest runs the debug variant so DEBUG
        // is true; assume it so the proof is actually exercised.
        assumeTrue("audit logger is DEBUG-guarded", BuildConfig.DEBUG)
        logger = VisibilityWriteAuditLogger()
    }

    private val viewA = 101
    private val viewB = 202

    @Test
    fun `single live writer per axis is not a double-write`() {
        logger.beginRenderGeneration()
        logger.logWrite(viewA, "KeyboardStateManager", android.view.View.GONE, live = true)
        // Same live caller again in the same generation = idempotent
        // re-render, NOT a double-write.
        logger.logWrite(viewA, "KeyboardStateManager", android.view.View.VISIBLE, live = true)

        assertEquals(0, logger.doubleWriteCount)
        assertEquals("KeyboardStateManager", logger.soleLiveWriterOf(viewA))
    }

    @Test
    fun `two distinct LIVE writers on one axis in one generation is a double-write`() {
        logger.beginRenderGeneration()
        logger.logWrite(viewA, "KeyboardStateManager", android.view.View.GONE, live = true)
        logger.logWrite(viewA, "ContentAreaController", android.view.View.VISIBLE, live = true)

        assertEquals(1, logger.doubleWriteCount)
    }

    @Test
    fun `dormant report does NOT count as a double-write against the live writer (RR-2 core)`() {
        logger.beginRenderGeneration()
        // Legacy KSM is the sole LIVE writer (CR3).
        logger.logWrite(viewA, "KeyboardStateManager", android.view.View.GONE, live = true)
        // The dormant ContentAreaController reports the SAME viewId in
        // the SAME generation — but suppressed (live = false). This is
        // exactly the CR3 steady state and must NOT be flagged.
        logger.logWrite(viewA, "ContentAreaController", android.view.View.VISIBLE, live = false)

        assertEquals(
            "dormant report must never trip the double-write detector (RR-2)",
            0, logger.doubleWriteCount,
        )
        assertEquals("KeyboardStateManager", logger.soleLiveWriterOf(viewA))
        assertTrue(
            "the dormant controller is observable as present-but-suppressed",
            logger.dormantReportersOf(viewA).contains("ContentAreaController"),
        )
    }

    @Test
    fun `CR4 flip - armed controller becomes the sole live writer, KSM no longer writes`() {
        // CR3 generation: KSM live, controller dormant.
        logger.beginRenderGeneration()
        logger.logWrite(viewA, "KeyboardStateManager", android.view.View.GONE, live = true)
        logger.logWrite(viewA, "ContentAreaController", android.view.View.VISIBLE, live = false)
        assertEquals("KeyboardStateManager", logger.soleLiveWriterOf(viewA))

        // CR4 generation: KSM drive removed (no KSM logWrite), gate armed
        // → the controller now reports live. Still exactly one live
        // writer per axis → zero double-writes across the flip.
        logger.beginRenderGeneration()
        logger.logWrite(viewA, "ContentAreaController", android.view.View.VISIBLE, live = true)

        assertEquals(0, logger.doubleWriteCount)
        assertEquals("ContentAreaController", logger.soleLiveWriterOf(viewA))
        assertTrue(logger.dormantReportersOf(viewA).isEmpty())
    }

    @Test
    fun `generation boundary resets the ledger - same two callers across generations is fine`() {
        logger.beginRenderGeneration()
        logger.logWrite(viewA, "KeyboardStateManager", android.view.View.GONE, live = true)

        // New generation (next state-emit fan-out). A different live
        // writer here is NOT a double-write — the previous generation's
        // owner does not carry over.
        logger.beginRenderGeneration()
        logger.logWrite(viewA, "PromptVisibilityController", android.view.View.VISIBLE, live = true)

        assertEquals(0, logger.doubleWriteCount)
        assertEquals("PromptVisibilityController", logger.soleLiveWriterOf(viewA))
    }

    @Test
    fun `distinct axes are tracked independently`() {
        logger.beginRenderGeneration()
        logger.logWrite(viewA, "KeyboardStateManager", android.view.View.GONE, live = true)
        logger.logWrite(viewB, "KeyboardStateManager", android.view.View.VISIBLE, live = true)
        // A different live writer on viewB only:
        logger.logWrite(viewB, "PromptVisibilityController", android.view.View.GONE, live = true)

        assertEquals("only viewB has the conflict", 1, logger.doubleWriteCount)
        assertEquals("KeyboardStateManager", logger.soleLiveWriterOf(viewA))
    }

    @Test
    fun `fresh logger has no live writer and zero double-writes`() {
        assertNull(logger.soleLiveWriterOf(viewA))
        assertEquals(0, logger.doubleWriteCount)
        assertTrue(logger.dormantReportersOf(viewA).isEmpty())
    }
}
