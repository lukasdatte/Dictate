package net.devemperor.dictate.preferences

import net.devemperor.dictate.preferences.versioned.VersionedPluginRegistry
import net.devemperor.dictate.preferences.versioned.VersionedPrefs
import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [LanguageResolver] — the permanent-language SoT that
 * replaced the deleted legacy `core` language-controller's pref-only
 * surface (D-13 / Epic §4 Block C1).
 *
 * Quality-Gate K-1: handwritten [FakeSharedPreferences] only, no Mockito.
 *
 * These port the **permanent-path** cases from the retired
 * `LanguageControllerTest` (effective resolution, auto-curation,
 * pos-resync, curated-list replace). The transient ReprocessStaging
 * override is no longer this object's concern — it is the
 * `LanguageModule.SetOverride` axis (see `LanguageModuleTest`).
 */
class LanguageResolverTest {

    private lateinit var prefs: FakeSharedPreferences

    @Before
    fun setUp() {
        LanguageLabelResolver.initializeForTest(
            codes = arrayOf("detect", "en", "de", "fr", "es"),
            labels = arrayOf("Auto-Detect", "English", "Deutsch", "Français", "Español"),
        )
        // Plugins do not self-register — register explicitly. Idempotent.
        VersionedPluginRegistry.register(InputLanguagesPlugin)
        prefs = FakeSharedPreferences()
    }

    // ── fresh install / defaults ──

    @Test
    fun `fresh install effective language is the default head (detect)`() {
        // VersionedPrefs.load returns the plugin default ["detect", "en"];
        // sortByLabel: detect < en. pos defaults to 0 → "detect".
        assertEquals("detect", LanguageResolver.effectiveLanguage(prefs))
    }

    @Test
    fun `curatedLanguages on fresh install returns the sorted default`() {
        assertEquals(listOf("detect", "en"), LanguageResolver.curatedLanguages(prefs))
    }

    @Test
    fun `effectiveLanguage falls back to en on a corrupt empty envelope`() {
        // An empty curated list cannot arise through the plugin sanitize
        // (it collapses empty → default), so simulate a corrupt envelope
        // by anchoring pos out of range on the sorted default and asserting
        // the coerce keeps it in range rather than throwing.
        prefs.edit().put(Pref.InputLanguagePos, 99).apply()
        // ["detect", "en"], pos coerced to 1 → "en".
        assertEquals("en", LanguageResolver.effectiveLanguage(prefs))
    }

    // ── setLanguage: permanent path + auto-curation ──

    @Test
    fun `setLanguage writes permanently and re-anchors pos`() {
        LanguageResolver.setLanguage(prefs, "de")

        assertEquals("de", LanguageResolver.effectiveLanguage(prefs))
        val curated = LanguageResolver.curatedLanguages(prefs)
        assertEquals("de", curated[prefs.getInt(Pref.InputLanguagePos.key, -1)])
    }

    @Test
    fun `setLanguage with a code outside the curated list auto-curates it`() {
        // Default curated: detect, en. Set "fr" → grows to detect, en, fr.
        LanguageResolver.setLanguage(prefs, "fr")

        val curated = LanguageResolver.curatedLanguages(prefs)
        assertTrue("fr must have been auto-curated", "fr" in curated)
        assertEquals("fr", LanguageResolver.effectiveLanguage(prefs))
    }

    @Test
    fun `setLanguage with an already-present code keeps the curated shape`() {
        LanguageResolver.setLanguage(prefs, "en")

        assertEquals(listOf("detect", "en"), LanguageResolver.curatedLanguages(prefs))
        assertEquals("en", LanguageResolver.effectiveLanguage(prefs))
    }

    @Test
    fun `setLanguage with an unknown code is filtered by sanitize`() {
        // "zz" is not in the allowlist — sanitize drops it, the curated
        // list is unchanged and the active code stays resolvable.
        LanguageResolver.setLanguage(prefs, "zz")

        val curated = LanguageResolver.curatedLanguages(prefs)
        assertFalse("zz" in curated)
        assertEquals(listOf("detect", "en"), curated)
    }

    // ── setCuratedLanguages: replace + pos-resync (K-3) ──

    @Test
    fun `setCuratedLanguages dedupes, sorts and anchors pos on preferActive`() {
        LanguageResolver.setCuratedLanguages(
            prefs,
            codes = listOf("en", "de", "en", "fr", "fr"),
            preferActive = "en",
        )
        val curated = LanguageResolver.curatedLanguages(prefs)
        assertEquals(listOf("de", "en", "fr"), curated)
        val pos = prefs.getInt(Pref.InputLanguagePos.key, -1)
        assertEquals(1, pos)
        assertEquals("en", curated[pos])
    }

    @Test
    fun `setCuratedLanguages without preferActive defaults pos to 0 when active falls out`() {
        LanguageResolver.setLanguage(prefs, "fr") // active = fr

        // New list does NOT contain "fr" → pos falls back to 0.
        LanguageResolver.setCuratedLanguages(prefs, listOf("de", "en"))

        val curated = LanguageResolver.curatedLanguages(prefs)
        assertEquals(listOf("de", "en"), curated)
        assertEquals(0, prefs.getInt(Pref.InputLanguagePos.key, -1))
        assertEquals("de", LanguageResolver.effectiveLanguage(prefs))
    }

    @Test
    fun `setCuratedLanguages without preferActive keeps the still-present active code`() {
        LanguageResolver.setLanguage(prefs, "de") // active = de

        // "de" is still in the new list — the default pos-anchor
        // (activeCodeOrNull) keeps it active across the rewrite.
        LanguageResolver.setCuratedLanguages(prefs, listOf("de", "en", "fr"))

        val curated = LanguageResolver.curatedLanguages(prefs)
        assertEquals(listOf("de", "en", "fr"), curated)
        assertEquals("de", LanguageResolver.effectiveLanguage(prefs))
    }

    // ── cross-instance freshness (R-3): no cache → no staleness ──

    @Test
    fun `a write through the resolver is immediately visible to a fresh read`() {
        // The R-3 staleness the deleted controller's lastEffective cache
        // suffered from is structurally gone: every effectiveLanguage()
        // re-reads SharedPreferences, so a write from "another actor"
        // (here just a second call) is seen with no invalidation step.
        assertEquals("detect", LanguageResolver.effectiveLanguage(prefs))
        LanguageResolver.setLanguage(prefs, "de")
        assertEquals("de", LanguageResolver.effectiveLanguage(prefs))

        // Sanity: the persisted envelope itself carries the new code.
        assertTrue("de" in VersionedPrefs.load(prefs, InputLanguagesPlugin))
    }
}
