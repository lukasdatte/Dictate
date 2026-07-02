package net.devemperor.dictate.state.render

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Truth-table tests for [effectiveNight] — the single shared
 * "does `Pref.Theme` force night?" rule (F-119).
 *
 * # Why Robolectric
 *
 * [Configuration] is a framework class; constructing one and writing
 * its `uiMode` field needs the real implementation on the classpath
 * (same K-4 exception as the other render tests).
 *
 * Covers the full theme × uiMode matrix from the spec's acceptance
 * criterion 7: theme ∈ {light, dark, system} × uiMode ∈ {day, night},
 * plus the defensive unknown-theme case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EffectiveNightModeTest {

    private fun config(night: Boolean): Configuration = Configuration().apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
    }

    @Test
    fun `theme=dark forces night on a day system`() {
        assertTrue(effectiveNight("dark", config(night = false)))
    }

    @Test
    fun `theme=dark stays night on a night system`() {
        assertTrue(effectiveNight("dark", config(night = true)))
    }

    @Test
    fun `theme=light forces day on a day system`() {
        assertFalse(effectiveNight("light", config(night = false)))
    }

    @Test
    fun `theme=light forces day on a night system`() {
        assertFalse(effectiveNight("light", config(night = true)))
    }

    @Test
    fun `theme=system follows a day system`() {
        assertFalse(effectiveNight("system", config(night = false)))
    }

    @Test
    fun `theme=system follows a night system`() {
        assertTrue(effectiveNight("system", config(night = true)))
    }

    @Test
    fun `unknown theme value defaults to day (defensive)`() {
        assertFalse(effectiveNight("blorb", config(night = true)))
        assertFalse(effectiveNight("", config(night = false)))
    }
}
