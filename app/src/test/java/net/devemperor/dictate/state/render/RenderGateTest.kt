package net.devemperor.dictate.state.render

import net.devemperor.dictate.BuildConfig
import net.devemperor.dictate.core.audit.VisibilityWriteAuditLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Unit tests for [RenderGate] — the CR3 dormant↔armed staged-safety-net
 * switch (render-path-cutover.md §6 RR-2 / §6.1; the visibility-axis
 * analogue of CR2's `SpecialTouchHandlerInstaller` dormant model).
 *
 * Pure JVM (no Robolectric — the gate is plain logic over an `Int` id +
 * the logger; K-4: no Android view needed).
 */
class RenderGateTest {

    private val viewId = 4242

    @Test
    fun `default is dormant - shouldWrite returns false (controller must NOT write)`() {
        val gate = RenderGate("ContentAreaController", auditLogger = null)
        assertFalse("CR3 default is dormant", gate.armed)
        assertFalse(gate.shouldWrite(viewId, android.view.View.GONE))
    }

    @Test
    fun `arm flips to armed - shouldWrite returns true (CR4 one-line flip)`() {
        val gate = RenderGate("ContentAreaController", auditLogger = null)
        gate.arm()
        assertTrue(gate.armed)
        assertTrue(gate.shouldWrite(viewId, android.view.View.VISIBLE))
    }

    @Test
    fun `dormant gate reports a SUPPRESSED (non-live) write to the ledger (RR-2 proof half)`() {
        assumeTrue("audit logger is DEBUG-guarded", BuildConfig.DEBUG)
        val logger = VisibilityWriteAuditLogger()
        val gate = RenderGate("ContentAreaController", logger)

        logger.beginRenderGeneration()
        // Legacy KSM is the live writer on the same axis in this gen.
        logger.logWrite(viewId, "KeyboardStateManager", android.view.View.GONE, live = true)

        val didWrite = gate.shouldWrite(viewId, android.view.View.VISIBLE)

        assertFalse("dormant gate must not authorise the view write", didWrite)
        assertEquals(
            "dormant report must not conflict with the KSM live write (RR-2)",
            0, logger.doubleWriteCount,
        )
        assertEquals("KeyboardStateManager", logger.soleLiveWriterOf(viewId))
        assertTrue(
            logger.dormantReportersOf(viewId).contains("ContentAreaController"),
        )
    }

    @Test
    fun `armed gate reports a LIVE write to the ledger`() {
        assumeTrue("audit logger is DEBUG-guarded", BuildConfig.DEBUG)
        val logger = VisibilityWriteAuditLogger()
        val gate = RenderGate("ContentAreaController", logger)
        gate.arm()

        logger.beginRenderGeneration()
        val didWrite = gate.shouldWrite(viewId, android.view.View.VISIBLE)

        assertTrue("armed gate authorises the view write", didWrite)
        assertEquals(
            "armed controller is now the sole live writer",
            "ContentAreaController", logger.soleLiveWriterOf(viewId),
        )
    }

    @Test
    fun `null logger - gate semantics still hold (no crash, proof simply unobserved)`() {
        val gate = RenderGate("OverlayResetHandler", auditLogger = null)
        assertFalse(gate.shouldWrite(viewId, android.view.View.GONE))
        gate.arm()
        assertTrue(gate.shouldWrite(viewId, android.view.View.GONE))
    }
}
