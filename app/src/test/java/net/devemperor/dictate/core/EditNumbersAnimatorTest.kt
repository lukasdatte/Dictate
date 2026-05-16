package net.devemperor.dictate.core

import android.content.Context
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [EditNumbersAnimator] (G15 extraction, CR1 / Theme C-R).
 *
 * # Why Robolectric
 *
 * The helper mutates real Android `View` properties (`rotation`,
 * `translationX`) — the K-4 view-wiring exception, same approach as
 * `ImeViewBackendTest`.
 *
 * # Coverage focus (deterministic paths only)
 *
 * The tweened paths use async `ViewPropertyAnimator` (non-deterministic
 * under Robolectric without looper plumbing); the *behaviourally
 * load-bearing* contract is the **synchronous** branches:
 *
 *  1. `animateSmallModeToggle(animate=false)` sets `rotation` instantly
 *     to 180° (small-mode on) / 0° (off) — read from the `isSmallMode`
 *     supplier (decoupled from `KeyboardStateManager`).
 *  2. `animateSmallModeToggle` with `animationsEnabled=false` also sets
 *     instantly even when `animate=true` (the gate is AND-ed).
 *  3. `animateEditNumbersBounce` is a no-op when `animationsEnabled` is
 *     `false` (the layout change is the user-visible feedback) — verified
 *     by `translationX` staying at its pre-call value.
 *  4. Suppliers are read **live** on each call (not captured at
 *     construction) — toggling the small-mode supplier flips the target.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditNumbersAnimatorTest {

    private lateinit var ctx: Context
    private lateinit var button: Button

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        button = Button(ctx)
    }

    @Test
    fun `animateSmallModeToggle(false) sets rotation 180 when small-mode on`() {
        val animator = EditNumbersAnimator(
            editNumbersButton = button,
            animationsEnabled = { true },
            isSmallMode = { true },
        )
        animator.animateSmallModeToggle(animate = false)
        assertEquals(180f, button.rotation, 0.0001f)
    }

    @Test
    fun `animateSmallModeToggle(false) sets rotation 0 when small-mode off`() {
        val animator = EditNumbersAnimator(
            editNumbersButton = button,
            animationsEnabled = { true },
            isSmallMode = { false },
        )
        button.rotation = 180f
        animator.animateSmallModeToggle(animate = false)
        assertEquals(0f, button.rotation, 0.0001f)
    }

    @Test
    fun `animateSmallModeToggle sets instantly when animations disabled even if animate=true`() {
        val animator = EditNumbersAnimator(
            editNumbersButton = button,
            animationsEnabled = { false },
            isSmallMode = { true },
        )
        animator.animateSmallModeToggle(animate = true)
        // Gate is `animate && animationsEnabled()` → instant set path.
        assertEquals(180f, button.rotation, 0.0001f)
    }

    @Test
    fun `suppliers are read live — flipping small-mode flips the target`() {
        var small = false
        val animator = EditNumbersAnimator(
            editNumbersButton = button,
            animationsEnabled = { true },
            isSmallMode = { small },
        )
        animator.animateSmallModeToggle(animate = false)
        assertEquals(0f, button.rotation, 0.0001f)

        small = true
        animator.animateSmallModeToggle(animate = false)
        assertEquals(180f, button.rotation, 0.0001f)
    }

    @Test
    fun `animateEditNumbersBounce is a no-op when animations disabled`() {
        val animator = EditNumbersAnimator(
            editNumbersButton = button,
            animationsEnabled = { false },
            isSmallMode = { false },
        )
        button.translationX = 42f
        animator.animateEditNumbersBounce()
        // Disabled → early return, translationX untouched.
        assertEquals(42f, button.translationX, 0.0001f)
    }
}
