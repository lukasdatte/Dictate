package net.devemperor.dictate.state

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.reflect.KClass

/**
 * Tests for [DictateModuleRegistry.assertCompleteCoverage] (C7 addition).
 *
 * The check enforces Spec 1 §4.8 invariant 3 — every direct sealed
 * subclass of [Action] (except [Action.EffectFailure], routed via
 * `originModuleId`) is claimed by exactly one module. The production
 * `DictateModuleRegistry.Default` is post-C6 full, so the check should
 * pass on the production singleton. Custom test-registries with a
 * subset of modules deliberately do NOT call this method.
 */
class DictateModuleRegistryCoverageTest {

    @Test
    fun `production registry passes assertCompleteCoverage post-C6`() {
        // Should not throw. If it does, the test fails on the
        // uncaught IllegalStateException — clear signal that a new
        // Action sealed subclass was added without a registry entry.
        DictateModuleRegistry.Default.assertCompleteCoverage()
    }

    @Test
    fun `assertCompleteCoverage throws when a module is missing`() {
        // Build a registry that omits one production module (e.g.
        // RecordingModule). The check should fail because
        // Action.RecordingAction has no owner.
        val partial = DictateModuleRegistry(
            DictateModuleRegistry.Default.all.filter { it.id != ModuleId.Recording }
        )

        try {
            partial.assertCompleteCoverage()
            fail("Expected IllegalStateException for unclaimed Action.RecordingAction")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Error message should name the unclaimed subtype: ${e.message}",
                e.message?.contains("RecordingAction") == true,
            )
        }
    }

    @Test
    fun `assertCompleteCoverage ignores Action#EffectFailure as a special-case`() {
        // The check explicitly excludes Action.EffectFailure from the
        // coverage requirement (routed via originModuleId, not KClass).
        // The production registry has no module that claims EffectFailure
        // as its actionClass; if the check still passes, the exclusion
        // is in effect.
        @Suppress("UNCHECKED_CAST")
        val efKClass: KClass<out Action> = Action.EffectFailure::class as KClass<out Action>
        assertTrue(
            "EffectFailure must NOT be a module's actionClass",
            DictateModuleRegistry.Default.all.none { it.actionClass == efKClass },
        )
        // And the production check passes — exclusion is working.
        DictateModuleRegistry.Default.assertCompleteCoverage()
    }
}
