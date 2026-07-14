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
 * Tests for [EditBarPcButtonRenderer] (ADR-0019).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditBarPcButtonRendererTest {

    private lateinit var button: MaterialButton
    private lateinit var renderer: EditBarPcButtonRenderer
    private val catalog = LayoutCatalog(testLayoutStrings())

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val ctx = ContextThemeWrapper(app, R.style.Theme_Dictate)
        button = MaterialButton(ctx)
        renderer = EditBarPcButtonRenderer(button)
    }

    private fun render(active: Boolean, paired: Boolean) {
        val base = DictateUiState.initial()
        renderer.render(
            base.copy(
                features = base.features.copy(
                    windowsAutoSendActive = active,
                    windowsPaired = paired,
                ),
            ),
            catalog.KEYBOARD_TWO_ROW,
        )
    }

    @Test
    fun `backendType is null (consume every mode)`() {
        // The edit-bar renders under every layout mode.
        assertEquals(null, renderer.backendType)
    }

    @Test
    fun `paired and active is fully opaque`() {
        render(active = true, paired = true)
        assertEquals(1f, button.alpha, 0.001f)
        assertNotNull(button.foreground)
    }

    @Test
    fun `unpaired dims the button`() {
        render(active = false, paired = false)
        assertTrue("unpaired must be visibly dimmed", button.alpha < 1f)
    }

    @Test
    fun `unpaired never disables the button`() {
        // Load-bearing: a disabled View delivers no long-press, and the
        // long-press is how an unpaired user reaches the pairing screen.
        // Dimming communicates the same thing without stranding them.
        render(active = false, paired = false)
        assertTrue("the dimmed button must stay clickable", button.isEnabled)
    }

    @Test
    fun `content description distinguishes all three states`() {
        render(active = false, paired = false)
        val unpaired = button.contentDescription
        render(active = false, paired = true)
        val off = button.contentDescription
        render(active = true, paired = true)
        val on = button.contentDescription

        assertEquals(3, setOf(unpaired, off, on).size)
    }

    @Test
    fun `render is idempotent`() {
        // The manager fans every state emit out to every backend, so render is
        // called far more often than the state actually changes.
        render(active = true, paired = true)
        val alpha = button.alpha
        render(active = true, paired = true)
        assertEquals(alpha, button.alpha, 0.001f)
    }
}
