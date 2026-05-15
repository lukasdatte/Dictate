package net.devemperor.dictate.state.render.overlay

import net.devemperor.dictate.state.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OverlayPermissionObserver] (Spec 3 §5.0).
 *
 * # Scope
 *
 * The observer is a small adapter; its job is exactly:
 *
 *  1. Read the live system-permission via the injected gate.
 *  2. Dispatch [Action.OverlayAction.OnOverlayPermissionChanged] with
 *     the boolean.
 *  3. Repeat on every `init` / `refresh` call.
 *
 * The reducer-side dedup logic (equality filter) lives in
 * [net.devemperor.dictate.state.OverlayModule] — those tests cover
 * the no-mutation cases. Here we verify the observer's side of the
 * contract: dispatches happen, the value is wired right, and both
 * lifecycle hooks behave identically.
 *
 * # Pure JVM
 *
 * No Robolectric — the observer holds only a function reference + an
 * interface; the [FakeOverlayPermissionGate] keeps the test
 * deterministic on the JVM.
 *
 * @see OverlayPermissionObserver
 */
class OverlayPermissionObserverTest {

    private val captured: MutableList<Action> = mutableListOf()
    private val gate: FakeOverlayPermissionGate = FakeOverlayPermissionGate()

    private fun newObserver(): OverlayPermissionObserver = OverlayPermissionObserver(
        gate = gate,
        dispatch = { captured += it },
    )

    @Test
    fun `init dispatches OnOverlayPermissionChanged(false) when gate is denied`() {
        gate.hasPermission = false
        newObserver().init()

        assertEquals(1, captured.size)
        val action = captured[0]
        assertTrue(action is Action.OverlayAction.OnOverlayPermissionChanged)
        assertFalse((action as Action.OverlayAction.OnOverlayPermissionChanged).granted)
    }

    @Test
    fun `init dispatches OnOverlayPermissionChanged(true) when gate is granted`() {
        gate.hasPermission = true
        newObserver().init()

        assertEquals(1, captured.size)
        assertTrue(
            (captured[0] as Action.OverlayAction.OnOverlayPermissionChanged).granted,
        )
    }

    @Test
    fun `refresh dispatches the current gate value`() {
        gate.hasPermission = false
        val observer = newObserver()
        observer.init()
        captured.clear()

        // User grants the permission via System Settings; IME comes
        // back and calls refresh.
        gate.hasPermission = true
        observer.refresh()

        assertEquals(1, captured.size)
        assertTrue(
            (captured[0] as Action.OverlayAction.OnOverlayPermissionChanged).granted,
        )
    }

    @Test
    fun `refresh dispatches unconditionally — reducer handles dedup (idempotency contract)`() {
        // Both calls dispatch the same boolean — the observer never
        // filters. The reducer-side equality filter lives in
        // OverlayModule.reduce (verified separately in OverlayModuleTest).
        gate.hasPermission = true
        val observer = newObserver()
        observer.refresh()
        observer.refresh()
        observer.refresh()

        assertEquals("three refresh() calls => three dispatches", 3, captured.size)
        captured.forEach { action ->
            assertTrue(action is Action.OverlayAction.OnOverlayPermissionChanged)
            assertTrue(
                (action as Action.OverlayAction.OnOverlayPermissionChanged).granted,
            )
        }
    }

    @Test
    fun `init then refresh with toggled gate emits both transitions`() {
        gate.hasPermission = false
        val observer = newObserver()
        observer.init()                         // false

        gate.hasPermission = true
        observer.refresh()                      // true

        gate.hasPermission = false
        observer.refresh()                      // false

        assertEquals(3, captured.size)
        val a = captured.map { (it as Action.OverlayAction.OnOverlayPermissionChanged).granted }
        assertEquals(listOf(false, true, false), a)
    }
}
