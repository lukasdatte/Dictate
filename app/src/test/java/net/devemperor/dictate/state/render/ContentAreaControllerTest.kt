package net.devemperor.dictate.state.render

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.core.ContentArea
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
 * Unit tests for [ContentAreaController].
 *
 * # Why Robolectric
 *
 * `View.visibility` is an `Int` field on the Android view — assertable
 * directly, but the View itself needs an `android.content.Context`.
 * Lightweight Robolectric (sdk=34, no theme) is sufficient.
 *
 * # Coverage focus
 *
 * - The three `ContentArea` values each select exactly one container
 *   visible.
 * - `backendType == null` (consumes every mode, R.10).
 * - `attach` / `detach` lifecycle is benign — no exceptions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContentAreaControllerTest {

    private lateinit var mainButtons: View
    private lateinit var qwertz: View
    private lateinit var emoji: View
    private lateinit var controller: ContentAreaController
    private val catalog = LayoutCatalog(testLayoutStrings())

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        mainButtons = FrameLayout(ctx)
        qwertz = FrameLayout(ctx)
        emoji = FrameLayout(ctx)
        controller = ContentAreaController(
            ContentAreaViews(
                mainButtonsContainer = mainButtons,
                qwertzContainer = qwertz,
                emojiPickerContainer = emoji,
            )
        )
        controller.attach { /* */ }
    }

    @Test
    fun `backendType is null (consume every mode)`() {
        assertNull(controller.backendType)
    }

    @Test
    fun `MAIN_BUTTONS shows the main container only`() {
        val state = stateWithContentArea(ContentArea.MAIN_BUTTONS)
        controller.render(state, catalog.KEYBOARD_TWO_ROW)

        assertEquals(View.VISIBLE, mainButtons.visibility)
        assertEquals(View.GONE, qwertz.visibility)
        assertEquals(View.GONE, emoji.visibility)
    }

    @Test
    fun `QWERTZ shows the qwertz container only`() {
        val state = stateWithContentArea(ContentArea.QWERTZ)
        controller.render(state, catalog.KEYBOARD_TWO_ROW)

        assertEquals(View.GONE, mainButtons.visibility)
        assertEquals(View.VISIBLE, qwertz.visibility)
        assertEquals(View.GONE, emoji.visibility)
    }

    @Test
    fun `EMOJI_PICKER shows the emoji container only`() {
        val state = stateWithContentArea(ContentArea.EMOJI_PICKER)
        controller.render(state, catalog.KEYBOARD_TWO_ROW)

        assertEquals(View.GONE, mainButtons.visibility)
        assertEquals(View.GONE, qwertz.visibility)
        assertEquals(View.VISIBLE, emoji.visibility)
    }

    @Test
    fun `detach is a no-op against future renders (still applies visibility)`() {
        // The render method does NOT branch on attach-state — it's pure
        // property-setting. detach() only nullifies the click sink.
        // Verify that detach doesn't break subsequent renders.
        controller.detach()
        val state = stateWithContentArea(ContentArea.QWERTZ)
        controller.render(state, catalog.KEYBOARD_TWO_ROW)

        // B4-VAL F-34b: also assert the two non-active containers go GONE
        // — guards against a future refactor that splits visibility into
        // multiple methods and forgets one container.
        assertEquals(View.VISIBLE, qwertz.visibility)
        assertEquals(View.GONE, mainButtons.visibility)
        assertEquals(View.GONE, emoji.visibility)
    }

    // ─── CR3 RenderGate (RR-2 staged-safety-net) ──────────────────────

    @Test
    fun `CR3 dormant gate - render does NOT mutate any container`() {
        mainButtons.visibility = View.INVISIBLE
        qwertz.visibility = View.INVISIBLE
        emoji.visibility = View.INVISIBLE

        val gated = ContentAreaController(
            ContentAreaViews(mainButtons, qwertz, emoji),
            RenderGate("ContentAreaController", auditLogger = null),
        )
        gated.attach { }
        gated.render(stateWithContentArea(ContentArea.QWERTZ), catalog.KEYBOARD_TWO_ROW)

        // Dormant → legacy KSM stays the sole live writer; the
        // controller leaves every container exactly as it found it.
        assertEquals(View.INVISIBLE, mainButtons.visibility)
        assertEquals(View.INVISIBLE, qwertz.visibility)
        assertEquals(View.INVISIBLE, emoji.visibility)
    }

    @Test
    fun `CR4 armed gate - render drives the containers (the flip)`() {
        val gate = RenderGate("ContentAreaController", auditLogger = null)
        val gated = ContentAreaController(
            ContentAreaViews(mainButtons, qwertz, emoji), gate,
        )
        gated.attach { }
        gate.arm() // CR4 one-line flip
        gated.render(stateWithContentArea(ContentArea.QWERTZ), catalog.KEYBOARD_TWO_ROW)

        assertEquals(View.GONE, mainButtons.visibility)
        assertEquals(View.VISIBLE, qwertz.visibility)
        assertEquals(View.GONE, emoji.visibility)
    }

    @Test
    fun `null gate - legacy always-write contract unchanged`() {
        val state = stateWithContentArea(ContentArea.EMOJI_PICKER)
        controller.render(state, catalog.KEYBOARD_TWO_ROW)
        assertEquals(View.VISIBLE, emoji.visibility)
        assertEquals(View.GONE, mainButtons.visibility)
    }

    // ── CR-DEL — the 4th ContentArea axis: editButtonsLl (Spec 2 §13
    //    row 2 BLEIBT). The deleted KSM applyContentAreaVisibility owned
    //    it (visible iff MAIN_BUTTONS || QWERTZ); relocated here so no
    //    visibility axis is stranded by the deletion. ──

    @Test
    fun `editButtons axis - VISIBLE in MAIN_BUTTONS and QWERTZ, GONE in EMOJI`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val editButtons = FrameLayout(ctx)
        val c = ContentAreaController(
            ContentAreaViews(mainButtons, qwertz, emoji, editButtons),
        )
        c.attach { }

        c.render(stateWithContentArea(ContentArea.MAIN_BUTTONS), catalog.KEYBOARD_TWO_ROW)
        assertEquals(View.VISIBLE, editButtons.visibility)

        c.render(stateWithContentArea(ContentArea.QWERTZ), catalog.KEYBOARD_TWO_ROW)
        assertEquals(View.VISIBLE, editButtons.visibility)

        c.render(stateWithContentArea(ContentArea.EMOJI_PICKER), catalog.KEYBOARD_TWO_ROW)
        assertEquals(
            "editButtonsLl is GONE in EMOJI_PICKER (byte-identical to the deleted KSM rule)",
            View.GONE, editButtons.visibility,
        )
    }

    @Test
    fun `editButtons axis - null editButtonsContainer is a backward-compatible no-op`() {
        // The 3-arg holder (every pre-CR-DEL caller / test) must stay
        // byte-identical — the 4th axis defaults to null and is skipped.
        val state = stateWithContentArea(ContentArea.EMOJI_PICKER)
        // controller (3-arg holder, from setUp) — must not throw.
        controller.render(state, catalog.KEYBOARD_TWO_ROW)
        assertEquals(View.VISIBLE, emoji.visibility)
    }

    @Test
    fun `editButtons axis - dormant gate leaves editButtons untouched`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val editButtons = FrameLayout(ctx).apply { visibility = View.INVISIBLE }
        val gated = ContentAreaController(
            ContentAreaViews(mainButtons, qwertz, emoji, editButtons),
            RenderGate("ContentAreaController", auditLogger = null),
        )
        gated.attach { }
        gated.render(stateWithContentArea(ContentArea.MAIN_BUTTONS), catalog.KEYBOARD_TWO_ROW)
        assertEquals(
            "dormant gate must route the 4th axis through the gate too (RR-2)",
            View.INVISIBLE, editButtons.visibility,
        )
    }

    private fun stateWithContentArea(area: ContentArea): DictateUiState =
        DictateUiState.initial().copy(
            layout = DictateUiState.initial().layout.copy(contentArea = area),
        )
}
