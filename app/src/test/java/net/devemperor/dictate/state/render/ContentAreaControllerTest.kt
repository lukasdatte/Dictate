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

    private fun stateWithContentArea(area: ContentArea): DictateUiState =
        DictateUiState.initial().copy(
            layout = DictateUiState.initial().layout.copy(contentArea = area),
        )
}
