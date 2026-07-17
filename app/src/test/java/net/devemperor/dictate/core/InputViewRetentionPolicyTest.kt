package net.devemperor.dictate.core

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * T4 (render-latency-wave2 §7.1) — the retention decision matrix for
 * [canReuseInputView].
 *
 * Robolectric only because [Configuration.diff] is real Android code; the
 * policy itself is a pure function. Reuse is allowed **only** for
 * pure-geometry deltas (rotation / window resize); every resource-affecting
 * delta — and every bit outside the geometry allowlist (fail-safe) —
 * rebuilds so the fresh resources are picked up.
 *
 * Note: a real portrait↔landscape rotation also sets the `@hide`
 * `CONFIG_WINDOW_CONFIGURATION` bit (0x20000000) — measured as
 * `diff=0x20000480` on the emulator (2026-07-18). That bit is whitelisted
 * in the policy (see [canReuseInputView]'s allowlist) but cannot be
 * injected through `Configuration`'s public API, so its whitelisting is
 * verified by the emulator rotation measurement rather than here; the cases
 * below cover the publicly-constructible geometry and resource bits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InputViewRetentionPolicyTest {

    private fun base(): Configuration = Configuration().apply {
        orientation = Configuration.ORIENTATION_PORTRAIT
        uiMode = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL
        setLocale(Locale.US)
        densityDpi = 420
        fontScale = 1.0f
        screenWidthDp = 400
        screenHeightDp = 800
        smallestScreenWidthDp = 400
        screenLayout = Configuration.SCREENLAYOUT_SIZE_NORMAL or
            Configuration.SCREENLAYOUT_LAYOUTDIR_LTR
        mcc = 310
    }

    private fun mutated(block: Configuration.() -> Unit): Configuration =
        Configuration(base()).apply(block)

    // ─── Reuse-allowed (pure geometry) ───────────────────────────────

    @Test
    fun `no delta reuses`() {
        assertTrue(canReuseInputView(base(), Configuration(base())))
    }

    @Test
    fun `orientation-only reuses`() {
        val rotated = mutated { orientation = Configuration.ORIENTATION_LANDSCAPE }
        assertTrue(canReuseInputView(base(), rotated))
    }

    @Test
    fun `orientation plus screen-size reuses`() {
        // A real rotation flips orientation AND swaps the w/h dp.
        val rotated = mutated {
            orientation = Configuration.ORIENTATION_LANDSCAPE
            screenWidthDp = 800
            screenHeightDp = 400
        }
        assertTrue(canReuseInputView(base(), rotated))
    }

    // ─── Rebuild (resource-affecting) ─────────────────────────────────

    @Test
    fun `night-mode flip rebuilds`() {
        val night = mutated {
            uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL
        }
        assertFalse(canReuseInputView(base(), night))
    }

    @Test
    fun `locale change rebuilds`() {
        val german = mutated { setLocale(Locale.GERMANY) }
        assertFalse(canReuseInputView(base(), german))
    }

    @Test
    fun `density change rebuilds`() {
        val denser = mutated { densityDpi = 560 }
        assertFalse(canReuseInputView(base(), denser))
    }

    @Test
    fun `font-scale change rebuilds`() {
        val bigger = mutated { fontScale = 1.3f }
        assertFalse(canReuseInputView(base(), bigger))
    }

    @Test
    fun `layout-direction change rebuilds`() {
        val rtl = mutated { setLayoutDirection(Locale("ar")) }
        assertFalse(canReuseInputView(base(), rtl))
    }

    @Test
    fun `a config bit outside the geometry allowlist rebuilds (fail-safe)`() {
        // mcc (CONFIG_MCC) is resource-affecting and not whitelisted — the
        // fail-safe path: any set delta bit outside the geometry mask
        // forces a rebuild, which is also how unknown/future bits behave.
        val differentMcc = mutated { mcc = 262 }
        assertFalse(canReuseInputView(base(), differentMcc))
    }
}
