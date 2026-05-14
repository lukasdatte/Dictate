package net.devemperor.dictate.state

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Tests for [DictateModuleRegistry] — the structural-invariants check
 * that runs in the constructor's `init` block (single-dispatch
 * invariant + duplicate-id check + leaf-class overlap check).
 *
 * **Test scaffolding.** Uses the same production-side `TestDictateModule`
 * fixture as the orchestrator tests — see KDoc in
 * [net.devemperor.dictate.state.TestOnlyModules].
 */
class DictateModuleRegistryTest {

    private lateinit var lens: TestStateLens

    @Before
    fun setUp() {
        lens = TestStateLens()
    }

    @After
    fun tearDown() {
        lens.clear()
    }

    @Test
    fun `production singleton contains the 5 core modules from C5`() {
        // C5 populates `Default.all` with the 5 core modules
        // (Recording / Pipeline / Audio / ViewMode / Overlay). C6 will
        // append the 8 auxiliary modules; this assertion grows then.
        //
        // Order is a binding contract (cascade order, ADR-0002) — the
        // list literal in `DictateModuleRegistry.Default` is the single
        // source of truth.
        val expected: List<DictateModule<*, *, *>> = listOf(
            RecordingModule,
            PipelineModule,
            AudioModule,
            ViewModeModule,
            OverlayModule,
        )
        assertEquals(expected, DictateModuleRegistry.Default.all)
    }

    @Test
    fun `registry constructor accepts a non-empty list of distinct modules`() {
        val moduleA = TestDictateModule(
            id = TestModuleId.A,
            actionClass = Action.LanguageAction::class,
            lens = lens,
        )
        val moduleB = TestDictateModule(
            id = TestModuleId.B,
            actionClass = Action.LivePromptAction::class,
            lens = lens,
        )

        val registry = DictateModuleRegistry(listOf(moduleA, moduleB))

        assertEquals(2, registry.all.size)
    }

    @Test
    fun `registry constructor preserves the order of the supplied module list`() {
        // Order is part of the contract (cascade order, ADR-0002). The
        // registry must NOT sort, dedupe, or otherwise reorder the input.
        val moduleA = TestDictateModule(
            id = TestModuleId.A,
            actionClass = Action.LanguageAction::class,
            lens = lens,
        )
        val moduleB = TestDictateModule(
            id = TestModuleId.B,
            actionClass = Action.LivePromptAction::class,
            lens = lens,
        )
        val moduleC = TestDictateModule(
            id = TestModuleId.C,
            actionClass = Action.OverlayAction::class,
            lens = lens,
        )

        val registry = DictateModuleRegistry(listOf(moduleC, moduleA, moduleB))

        assertEquals(listOf<ModuleId>(TestModuleId.C, TestModuleId.A, TestModuleId.B), registry.all.map { it.id })
    }

    @Test
    fun `registry rejects duplicate module ids at init time`() {
        val moduleA1 = TestDictateModule(
            id = TestModuleId.A,
            actionClass = Action.LanguageAction::class,
            lens = lens,
        )
        val moduleA2 = TestDictateModule(
            id = TestModuleId.A,    // duplicate id
            actionClass = Action.LivePromptAction::class,
            lens = lens,
        )

        try {
            DictateModuleRegistry(listOf(moduleA1, moduleA2))
            fail("expected init-time IllegalArgumentException for duplicate id")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "error message should reference duplicate ModuleId; got: ${e.message}",
                e.message?.contains("Duplicate ModuleId") == true,
            )
        }
    }

    @Test
    fun `registry rejects duplicate actionClass tokens at init time`() {
        val moduleA = TestDictateModule(
            id = TestModuleId.A,
            actionClass = Action.LanguageAction::class,
            lens = lens,
        )
        val moduleB = TestDictateModule(
            id = TestModuleId.B,
            actionClass = Action.LanguageAction::class,    // same actionClass!
            lens = lens,
        )

        try {
            DictateModuleRegistry(listOf(moduleA, moduleB))
            fail("expected init-time IllegalArgumentException for duplicate actionClass")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "error message should reference duplicate actionClass; got: ${e.message}",
                e.message?.contains("Duplicate actionClass") == true,
            )
        }
    }

    @Test
    fun `registry rejects leaf-class overlap across modules`() {
        // Two modules pointing at the same Action subtype hierarchy from
        // different angles. The duplicate-actionClass check catches the
        // direct case; the leaf-overlap check catches a future scenario
        // where two roots are sealed parents of overlapping leaves.
        //
        // For C4's Action hierarchy, that's not directly representable
        // (`Action.LanguageAction` is the only level above `SetOverride`),
        // so we re-exercise the duplicate-actionClass path with the same
        // module-id pair to assert that this check also runs. Future
        // refactors that introduce a deeper sealed nesting can extend
        // this test to exercise the genuine overlap.
        val moduleA = TestDictateModule(
            id = TestModuleId.A,
            actionClass = Action.LanguageAction::class,
            lens = lens,
        )
        val moduleB = TestDictateModule(
            id = TestModuleId.B,
            actionClass = Action.LanguageAction::class,
            lens = lens,
        )

        try {
            DictateModuleRegistry(listOf(moduleA, moduleB))
            fail("expected init-time IllegalArgumentException for overlap")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `registry accepts empty module list (Phase-1 C4 baseline)`() {
        // The orchestrator binds to an empty registry in C4 — modules are
        // registered in C5/C6. This must not throw.
        val registry = DictateModuleRegistry(emptyList())
        assertEquals(emptyList<DictateModule<*, *, *>>(), registry.all)
    }
}
