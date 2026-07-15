package net.devemperor.dictate.state.render

import android.content.Context
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.layout.LayoutCatalog
import net.devemperor.dictate.state.layout.testLayoutStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [EditBarToggleButtonRenderer], driven through both of its real
 * configurations (PC send-mode, screen context) so the shared class is pinned
 * by the two buttons that actually use it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditBarToggleButtonRendererTest {

    private lateinit var button: MaterialButton
    private val catalog = LayoutCatalog(testLayoutStrings())

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val ctx = ContextThemeWrapper(app, R.style.Theme_Dictate)
        button = MaterialButton(ctx)
    }

    private fun pcRenderer() = EditBarToggleButtonRenderer(
        button = button,
        iconRes = R.drawable.ic_baseline_computer_24,
        activeColorRes = R.color.dictate_pc_mode,
        descriptions = EditBarToggleButtonRenderer.Descriptions(
            on = R.string.dictate_pc_mode_state_on,
            off = R.string.dictate_pc_mode_state_off,
            unavailable = R.string.dictate_pc_mode_state_unpaired,
        ),
        selector = {
            EditBarToggleButtonRenderer.Status(
                active = it.features.windowsAutoSendActive,
                available = it.features.windowsPaired,
            )
        },
    )

    private fun a11yRenderer() = EditBarToggleButtonRenderer(
        button = button,
        iconRes = R.drawable.ic_baseline_screen_search_24,
        activeColorRes = R.color.dictate_green,
        descriptions = EditBarToggleButtonRenderer.Descriptions(
            on = R.string.dictate_a11y_toggle_state_on,
            off = R.string.dictate_a11y_toggle_state_off,
            unavailable = R.string.dictate_a11y_toggle_state_unavailable,
        ),
        selector = {
            EditBarToggleButtonRenderer.Status(
                active = it.features.screenContextEnabled,
                available = it.features.screenContextAvailable,
            )
        },
    )

    private fun stateWith(
        pcActive: Boolean = false,
        paired: Boolean = false,
        ctxEnabled: Boolean = false,
        ctxAvailable: Boolean = false,
    ): DictateUiState {
        val base = DictateUiState.initial()
        return base.copy(
            features = base.features.copy(
                windowsAutoSendActive = pcActive,
                windowsPaired = paired,
                screenContextEnabled = ctxEnabled,
                screenContextAvailable = ctxAvailable,
            ),
        )
    }

    private fun render(r: EditBarToggleButtonRenderer, s: DictateUiState) =
        r.render(s, catalog.KEYBOARD_TWO_ROW)

    @Test
    fun `backendType is null (consume every mode)`() {
        assertEquals(null, pcRenderer().backendType)
    }

    @Test
    fun `available button is fully opaque and has an icon`() {
        render(pcRenderer(), stateWith(pcActive = true, paired = true))
        assertEquals(1f, button.alpha, 0.001f)
        assertNotNull(button.foreground)
    }

    @Test
    fun `unavailable button is dimmed`() {
        render(pcRenderer(), stateWith(paired = false))
        assertTrue("unavailable must be visibly dimmed", button.alpha < 1f)
    }

    @Test
    fun `unavailable button is never disabled`() {
        // Load-bearing: a disabled View delivers no long-press, and the
        // long-press is the way OUT of the unavailable state (pair a PC / open
        // the a11y setup). Disabling would strand the users who need it.
        render(pcRenderer(), stateWith(paired = false))
        assertTrue(button.isEnabled)
        render(a11yRenderer(), stateWith(ctxAvailable = false))
        assertTrue(button.isEnabled)
    }

    @Test
    fun `each state gets its own content description`() {
        render(pcRenderer(), stateWith(paired = false))
        val unavailable = button.contentDescription
        render(pcRenderer(), stateWith(paired = true, pcActive = false))
        val off = button.contentDescription
        render(pcRenderer(), stateWith(paired = true, pcActive = true))
        val on = button.contentDescription
        assertEquals(3, setOf(unavailable, off, on).size)
    }

    @Test
    fun `the screen-context button reads its own axis`() {
        // The two configurations must not accidentally share a selector: PC
        // state must leave the a11y button alone.
        render(a11yRenderer(), stateWith(pcActive = true, paired = true))
        assertTrue("PC-mode must not light the screen-context button", button.alpha < 1f)

        render(a11yRenderer(), stateWith(ctxEnabled = true, ctxAvailable = true))
        assertEquals(1f, button.alpha, 0.001f)
    }

    @Test
    fun `render is idempotent`() {
        // The manager fans every state emit out to every backend, so render
        // runs far more often than the state changes.
        val r = pcRenderer()
        val s = stateWith(pcActive = true, paired = true)
        render(r, s)
        val alpha = button.alpha
        render(r, s)
        assertEquals(alpha, button.alpha, 0.001f)
    }
}
