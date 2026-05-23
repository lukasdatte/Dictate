package net.devemperor.dictate.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure type-level tests for [TransitionResult] and [ReducerContext].
 *
 * No reducer logic — just data-class behaviour and the
 * empty-effects default.
 */
class TransitionResultTest {

    // A minimal Effect type local to this test, so we don't depend on
    // any concrete module's Effect sealed (which doesn't exist yet —
    // those are Chunk C5/C6 territory).
    private sealed interface TestEffect : SideEffect {
        object Foo : TestEffect
        data class Bar(val n: Int) : TestEffect
    }

    @Test
    fun `TransitionResult equality compares nextState and sideEffects`() {
        val a = TransitionResult<String, TestEffect>("hello", listOf(TestEffect.Foo))
        val b = TransitionResult<String, TestEffect>("hello", listOf(TestEffect.Foo))

        assertEquals(a, b)
    }

    @Test
    fun `TransitionResult with different nextState is not equal`() {
        val a = TransitionResult<String, TestEffect>("a", emptyList())
        val b = TransitionResult<String, TestEffect>("b", emptyList())

        assertNotEquals(a, b)
    }

    @Test
    fun `TransitionResult with different sideEffects is not equal`() {
        val a = TransitionResult<String, TestEffect>("a", listOf(TestEffect.Foo))
        val b = TransitionResult<String, TestEffect>("a", listOf(TestEffect.Bar(7)))

        assertNotEquals(a, b)
    }

    @Test
    fun `TransitionResult sideEffects defaults to empty list`() {
        val r = TransitionResult<String, TestEffect>("x")

        assertTrue(r.sideEffects.isEmpty())
    }

    @Test
    fun `TransitionResult can carry multiple effects in order`() {
        val r = TransitionResult<String, TestEffect>(
            nextState = "x",
            sideEffects = listOf(TestEffect.Foo, TestEffect.Bar(1), TestEffect.Bar(2)),
        )

        assertEquals(3, r.sideEffects.size)
        assertSame(TestEffect.Foo, r.sideEffects[0])
        assertEquals(TestEffect.Bar(1), r.sideEffects[1])
        assertEquals(TestEffect.Bar(2), r.sideEffects[2])
    }

    // ────────────────────────────────────────────────────────────────
    // ReducerContext
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `ReducerContext exposes global state and a default now`() {
        val global = DictateUiState.initial()
        val ctx = ReducerContext(global = global)

        assertSame(global, ctx.global)
        assertTrue("now should be a positive timestamp", ctx.now > 0L)
    }

    @Test
    fun `ReducerContext now can be injected for deterministic tests`() {
        val global = DictateUiState.initial()
        val ctx = ReducerContext(global = global, now = 1234L)

        assertEquals(1234L, ctx.now)
    }

    @Test
    fun `ReducerContext equality compares global and now`() {
        val global = DictateUiState.initial()
        val a = ReducerContext(global, now = 100L)
        val b = ReducerContext(global, now = 100L)
        val c = ReducerContext(global, now = 200L)

        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    // ────────────────────────────────────────────────────────────────
    // null reducer-return semantics — not a TransitionResult thing
    // but documented behavior worth pinning down at the type level.
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `TransitionResult is nullable in reduce signatures — null encodes rejection`() {
        // The TransitionResult type itself is non-null; the reducer
        // signature is `fun reduce(...): TransitionResult<S, E>?` —
        // returning null is the "action not relevant" signal.
        val accepted: TransitionResult<String, TestEffect>? =
            TransitionResult("x", emptyList())
        val rejected: TransitionResult<String, TestEffect>? = null

        assertEquals("x", accepted!!.nextState)
        assertNull(rejected)
    }
}
