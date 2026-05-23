package net.devemperor.dictate.state.render

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.layout.ButtonSlot
import net.devemperor.dictate.state.layout.LogicalButtonId
import net.devemperor.dictate.state.layout.WidthPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the shared [applySlotToView] helper.
 *
 * The helper is the single code-path from `ButtonSlot` to View
 * property writes — both [ImeViewBackend] and `OverlayBackend` consume
 * it, so a regression here surfaces in both surfaces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SlotRendererTest {

    private lateinit var ctx: Context
    private lateinit var button: MaterialButton
    private lateinit var plainView: View

    @Before
    fun setUp() {
        val app: Context = ApplicationProvider.getApplicationContext()
        ctx = ContextThemeWrapper(app, R.style.Theme_Dictate)
        button = MaterialButton(ctx)
        plainView = FrameLayout(ctx)
    }

    @Test
    fun `visibility predicate true sets VISIBLE`() {
        val slot = makeSlot(visibilityPredicate = { true })
        val visible = applySlotToView(slot, button, DictateUiState.initial(), ctx)
        assertEquals(View.VISIBLE, button.visibility)
        assertTrue(visible)
    }

    @Test
    fun `visibility predicate false sets GONE`() {
        val slot = makeSlot(visibilityPredicate = { false })
        val visible = applySlotToView(slot, button, DictateUiState.initial(), ctx)
        assertEquals(View.GONE, button.visibility)
        assertFalse(visible)
    }

    @Test
    fun `enabled resolver sets isEnabled`() {
        val slot = makeSlot(visibilityPredicate = { true }, enabledResolver = { false })
        applySlotToView(slot, button, DictateUiState.initial(), ctx)
        assertFalse(button.isEnabled)
    }

    @Test
    fun `alpha resolver writes alpha`() {
        val slot = makeSlot(visibilityPredicate = { true }, alphaResolver = { 0.4f })
        applySlotToView(slot, button, DictateUiState.initial(), ctx)
        assertEquals(0.4f, button.alpha, 0.001f)
    }

    @Test
    fun `text resolver writes text on MaterialButton`() {
        val slot = makeSlot(visibilityPredicate = { true }, textResolver = { "Hello" })
        applySlotToView(slot, button, DictateUiState.initial(), ctx)
        assertEquals("Hello", button.text.toString())
    }

    @Test
    fun `text resolver returning null leaves the text intact`() {
        button.text = "Original"
        val slot = makeSlot(visibilityPredicate = { true }, textResolver = { null })
        applySlotToView(slot, button, DictateUiState.initial(), ctx)
        assertEquals("Original", button.text.toString())
    }

    @Test
    fun `non-MaterialButton view skips icon and text resolvers`() {
        val slot = makeSlot(
            visibilityPredicate = { true },
            textResolver = { "X" },
            iconResolver = { R.drawable.ic_baseline_mic_24 },
        )
        // Should not throw — applySlotToView only touches icon/text on
        // MaterialButton instances.
        applySlotToView(slot, plainView, DictateUiState.initial(), ctx)
        assertEquals(View.VISIBLE, plainView.visibility)
    }

    private fun makeSlot(
        visibilityPredicate: (DictateUiState) -> Boolean,
        enabledResolver: (DictateUiState) -> Boolean = { true },
        alphaResolver: (DictateUiState) -> Float = { 1f },
        iconResolver: (DictateUiState) -> Int? = { null },
        textResolver: (DictateUiState) -> CharSequence? = { null },
    ): ButtonSlot = ButtonSlot(
        logicalId = LogicalButtonId.RECORD,
        widthPolicy = WidthPolicy.WrapContent,
        visibilityPredicate = visibilityPredicate,
        enabledResolver = enabledResolver,
        alphaResolver = alphaResolver,
        iconResolver = iconResolver,
        textResolver = textResolver,
        actionResolver = { _, _ -> Action.KeyboardInputAction.Backspace },
    )
}
