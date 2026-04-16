package net.devemperor.dictate.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ActiveJobRegistry].
 *
 * Verifies the single-job-at-a-time constraint, state transitions,
 * and convenience query methods.
 */
class ActiveJobRegistryTest {

    private fun running(sessionId: String) = JobState.Running(
        sessionId = sessionId,
        currentStepIndex = 0,
        totalSteps = 1,
        currentStepName = "",
        startedAt = 0L
    )

    @Before
    fun resetRegistry() {
        // Clean up any leftover state from earlier tests.
        ActiveJobRegistry.state.value.keys.forEach { ActiveJobRegistry.unregister(it) }
    }

    @After
    fun tearDown() {
        resetRegistry()
    }

    @Test
    fun `register returns true when registry is empty`() {
        assertTrue(ActiveJobRegistry.register("s1", running("s1")))
        assertTrue(ActiveJobRegistry.isActive("s1"))
        assertTrue(ActiveJobRegistry.isAnyActive())
    }

    @Test
    fun `register returns false while another job is active`() {
        assertTrue(ActiveJobRegistry.register("s1", running("s1")))
        assertFalse(ActiveJobRegistry.register("s2", running("s2")))
        assertTrue(ActiveJobRegistry.isActive("s1"))
        assertFalse(ActiveJobRegistry.isActive("s2"))
    }

    @Test
    fun `unregister clears the registry and allows new registrations`() {
        ActiveJobRegistry.register("s1", running("s1"))
        ActiveJobRegistry.unregister("s1")
        assertFalse(ActiveJobRegistry.isAnyActive())
        assertTrue(ActiveJobRegistry.register("s2", running("s2")))
    }

    @Test
    fun `update changes the state for an active session only`() {
        ActiveJobRegistry.register("s1", running("s1"))
        val newState = running("s1").copy(currentStepIndex = 2, currentStepName = "Step 2")
        ActiveJobRegistry.update("s1", newState)
        assertEquals(newState, ActiveJobRegistry.get("s1"))
    }

    @Test
    fun `update is no-op for an unknown session`() {
        ActiveJobRegistry.register("s1", running("s1"))
        val phantom = running("phantom")
        ActiveJobRegistry.update("phantom", phantom)
        assertNull(ActiveJobRegistry.get("phantom"))
        assertNotNull(ActiveJobRegistry.get("s1"))
    }

    @Test
    fun `get returns null for unknown session`() {
        assertNull(ActiveJobRegistry.get("nope"))
    }
}
