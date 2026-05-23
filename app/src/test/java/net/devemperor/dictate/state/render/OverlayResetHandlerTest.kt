package net.devemperor.dictate.state.render

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.layout.LayoutCatalog
import net.devemperor.dictate.state.layout.testLayoutStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [OverlayResetHandler].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayResetHandlerTest {

    private lateinit var overlayStrip: View
    private lateinit var handler: OverlayResetHandler
    private val catalog = LayoutCatalog(testLayoutStrings())

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        overlayStrip = FrameLayout(ctx).apply { visibility = View.VISIBLE }
        handler = OverlayResetHandler(OverlayResetViews(overlayCharactersStrip = overlayStrip))
        handler.attach { /* */ }
    }

    @Test
    fun `backendType is null (consume every mode)`() {
        assertNull(handler.backendType)
    }

    @Test
    fun `render forces overlay strip to GONE`() {
        overlayStrip.visibility = View.VISIBLE
        handler.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)
        assertEquals(View.GONE, overlayStrip.visibility)
    }

    @Test
    fun `null overlay strip is a no-op (no crash)`() {
        val noopHandler = OverlayResetHandler(OverlayResetViews(overlayCharactersStrip = null))
        noopHandler.attach { /* */ }
        noopHandler.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)
        // No assertion needed — just no exception.
    }

    @Test
    fun `render is idempotent`() {
        handler.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)
        handler.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)
        assertEquals(View.GONE, overlayStrip.visibility)
    }

    // ─── CR3 RenderGate (RR-2 staged-safety-net) ──────────────────────

    @Test
    fun `CR3 dormant gate - render does NOT reset the strip (KSM still owns it)`() {
        overlayStrip.visibility = View.VISIBLE
        val gated = OverlayResetHandler(
            OverlayResetViews(overlayStrip),
            RenderGate("OverlayResetHandler", auditLogger = null),
        )
        gated.attach { }
        gated.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)

        // Dormant → the legacy KSM applyVisibility() reset (line ~142)
        // stays the sole live writer; the handler leaves it untouched.
        assertEquals(View.VISIBLE, overlayStrip.visibility)
    }

    @Test
    fun `CR4 armed gate - render forces the strip GONE (the flip)`() {
        overlayStrip.visibility = View.VISIBLE
        val gate = RenderGate("OverlayResetHandler", auditLogger = null)
        val gated = OverlayResetHandler(OverlayResetViews(overlayStrip), gate)
        gated.attach { }
        gate.arm()
        gated.render(DictateUiState.initial(), catalog.KEYBOARD_TWO_ROW)

        assertEquals(View.GONE, overlayStrip.visibility)
    }
}
