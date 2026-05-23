package net.devemperor.dictate.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure-data tests for [DictateUiStateStore].
 *
 * No coroutine collection — `MutableStateFlow.value` is read directly
 * via [DictateUiStateStore.snapshot]. The store itself has no async
 * behaviour to exercise; full state-emission tests live in C4
 * (DictateOrchestrator).
 */
class DictateUiStateStoreTest {

    @Test
    fun `store boots with DictateUiState initial()`() {
        val store = DictateUiStateStore()

        assertEquals(DictateUiState.initial(), store.snapshot)
    }

    @Test
    fun `store can be booted with a custom initial state`() {
        val custom = DictateUiState.initial().copy(viewMode = ViewMode.WIDGET)
        val store = DictateUiStateStore(initial = custom)

        assertEquals(ViewMode.WIDGET, store.snapshot.viewMode)
    }

    @Test
    fun `update replaces the snapshot atomically`() {
        val store = DictateUiStateStore()
        val before = store.snapshot

        store.update { it.copy(viewMode = ViewMode.HOVER) }

        assertNotSame(before, store.snapshot)
        assertEquals(ViewMode.HOVER, store.snapshot.viewMode)
    }

    @Test
    fun `update reducer receives the current snapshot`() {
        val store = DictateUiStateStore()
        store.update { it.copy(viewMode = ViewMode.WIDGET) }
        // Second update sees the first's mutation as its input.
        store.update { current ->
            assertEquals(ViewMode.WIDGET, current.viewMode)
            current.copy(viewMode = ViewMode.HOVER)
        }
        assertEquals(ViewMode.HOVER, store.snapshot.viewMode)
    }

    @Test
    fun `state flow exposes the same snapshot value`() {
        val store = DictateUiStateStore()
        store.update { it.copy(viewMode = ViewMode.WIDGET) }

        assertSame(store.snapshot, store.state.value)
    }

    @Test
    fun `update with identity reducer leaves the snapshot equal`() {
        val store = DictateUiStateStore()
        val before = store.snapshot

        store.update { it }

        // MutableStateFlow conflates equal values — snapshot is the same instance.
        assertEquals(before, store.snapshot)
    }
}
