package net.devemperor.dictate.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Tests for [ModuleId] sealed-interface enumeration.
 *
 * Verifies:
 * - All 14 module identifiers exist (13 Phase-1 + 1 Phase-2 stub).
 * - Each id is a singleton (`data object` identity).
 * - Identity comparison (`===`) discriminates distinct ids.
 *
 * Why this matters: [Action.EffectFailure.originModuleId] uses these to
 * route failures back to their owning module. A duplicate or missing id
 * would silently misroute or drop EffectFailures.
 */
class ModuleIdTest {

    @Test
    fun `all module ids exist and extend ModuleId sealed interface`() {
        val ids: List<ModuleId> = listOf(
            ModuleId.Recording,
            ModuleId.Pipeline,
            ModuleId.Audio,
            ModuleId.ViewMode,
            ModuleId.Overlay,
            ModuleId.Resend,
            ModuleId.LivePrompt,
            ModuleId.Language,
            ModuleId.Layout,
            ModuleId.FeatureToggle,
            ModuleId.Theming,
            ModuleId.PendingSessions,
            ModuleId.KeyboardInput,
            ModuleId.Interruption,
        )
        // 13 Phase-1 modules + 1 Phase-2 stub (Interruption).
        assertEquals("Spec 1 §15.1 ⇒ 14 module ids", 14, ids.size)
        // All entries must be distinct singletons — `id is ModuleId` would be
        // statically-true (list is typed), so we check distinctness via toSet().
        assertEquals("All ModuleId entries must be distinct singletons", 14, ids.toSet().size)
    }

    @Test
    fun `module ids are singletons (identity comparison succeeds)`() {
        assertSame(ModuleId.Recording, ModuleId.Recording)
        assertSame(ModuleId.Pipeline, ModuleId.Pipeline)
        assertSame(ModuleId.Audio, ModuleId.Audio)
        assertSame(ModuleId.ViewMode, ModuleId.ViewMode)
        assertSame(ModuleId.Overlay, ModuleId.Overlay)
        assertSame(ModuleId.Resend, ModuleId.Resend)
        assertSame(ModuleId.LivePrompt, ModuleId.LivePrompt)
        assertSame(ModuleId.Language, ModuleId.Language)
        assertSame(ModuleId.Layout, ModuleId.Layout)
        assertSame(ModuleId.FeatureToggle, ModuleId.FeatureToggle)
        assertSame(ModuleId.Theming, ModuleId.Theming)
        assertSame(ModuleId.PendingSessions, ModuleId.PendingSessions)
        assertSame(ModuleId.KeyboardInput, ModuleId.KeyboardInput)
        assertSame(ModuleId.Interruption, ModuleId.Interruption)
    }

    @Test
    fun `distinct module ids are NOT equal`() {
        // Cast to Any? to disambiguate the overloaded JUnit assertNotEquals(Object, Object)
        // from the primitive long/double overloads — without the cast Kotlin picks the
        // long/double overload and fails with a type mismatch.
        assertNotEquals(ModuleId.Recording as Any?, ModuleId.Pipeline as Any?)
        assertNotEquals(ModuleId.Audio as Any?, ModuleId.ViewMode as Any?)
        assertNotEquals(ModuleId.Overlay as Any?, ModuleId.Resend as Any?)
        assertNotEquals(ModuleId.LivePrompt as Any?, ModuleId.Language as Any?)
        assertNotEquals(ModuleId.KeyboardInput as Any?, ModuleId.Interruption as Any?)
    }

    @Test
    fun `module ids work as map keys (hashCode + equals contracts)`() {
        val map: Map<ModuleId, String> = mapOf(
            ModuleId.Recording to "rec",
            ModuleId.Pipeline to "pipe",
            ModuleId.Audio to "audio",
        )
        assertEquals("rec", map[ModuleId.Recording])
        assertEquals("pipe", map[ModuleId.Pipeline])
        assertEquals("audio", map[ModuleId.Audio])
        assertEquals(null, map[ModuleId.Overlay])
    }
}
