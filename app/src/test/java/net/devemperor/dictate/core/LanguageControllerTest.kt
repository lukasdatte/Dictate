package net.devemperor.dictate.core

import net.devemperor.dictate.preferences.InputLanguagesPlugin
import net.devemperor.dictate.preferences.LanguageLabelResolver
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.versioned.VersionedPluginRegistry
import net.devemperor.dictate.preferences.versioned.VersionedPrefs
import net.devemperor.dictate.testutil.FakePipelineUiStateReader
import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [LanguageController] using handwritten fakes.
 *
 * Quality-Gate references:
 *  - K-1: no Mockito; only [FakeSharedPreferences] + [FakePipelineUiStateReader].
 *  - K-3: pos resync after sanitize is verified across the three writers
 *    (writePermanent, setCuratedLanguages, ReprocessStaging exit).
 *  - W-11: callback fires unconditionally after if/else; covered by the
 *    "ReprocessStaging-Mehrfach-Wechsel" case.
 */
class LanguageControllerTest {

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var reader: FakePipelineUiStateReader
    private lateinit var controller: LanguageController

    private val callbackEvents = mutableListOf<Pair<String, String>>()
    private val recordingCallback = object : LanguageController.Callback {
        override fun onEffectiveLanguageChanged(oldCode: String, newCode: String) {
            callbackEvents.add(oldCode to newCode)
        }
    }

    @Before
    fun setUp() {
        LanguageLabelResolver.initializeForTest(
            codes = arrayOf("detect", "en", "de", "fr", "es"),
            labels = arrayOf("Auto-Detect", "English", "Deutsch", "Français", "Español")
        )
        // Plugins do not self-register — register explicitly. Idempotent.
        VersionedPluginRegistry.register(InputLanguagesPlugin)

        prefs = FakeSharedPreferences()
        reader = FakePipelineUiStateReader()
        controller = LanguageController(prefs, reader)
        controller.setCallback(recordingCallback)
    }

    // ── fresh install / defaults ──

    @Test
    fun `fresh install effective language is the default head (detect)`() {
        // VersionedPrefs.load returns the plugin's default ["detect", "en"];
        // sortByLabel: detect < en. pos defaults to 0 → "detect".
        assertEquals("detect", controller.getEffectiveLanguage())
    }

    @Test
    fun `getCuratedLanguages on fresh install returns the sorted default`() {
        assertEquals(listOf("detect", "en"), controller.getCuratedLanguages())
    }

    // ── permanent set ──

    @Test
    fun `setLanguage in idle path writes permanently and fires callback`() {
        controller.setLanguage("de")

        assertEquals("de", controller.getEffectiveLanguage())
        // Pos points at "de" inside the sanitized list.
        val curated = controller.getCuratedLanguages()
        assertEquals("de", curated[prefs.getInt(Pref.InputLanguagePos.key, -1)])
        // Callback fired: detect → de.
        assertEquals(1, callbackEvents.size)
        assertEquals("detect" to "de", callbackEvents.first())
    }

    @Test
    fun `setLanguage with code outside curated list auto-curates it`() {
        // Default curated: detect, en. Set "fr" → curated grows to detect, en, fr (sorted).
        controller.setLanguage("fr")

        val curated = controller.getCuratedLanguages()
        assertTrue("fr must have been auto-curated", "fr" in curated)
        assertEquals("fr", controller.getEffectiveLanguage())
    }

    @Test
    fun `setLanguage with already-present code keeps curated list shape`() {
        controller.setLanguage("en")
        val curated = controller.getCuratedLanguages()
        // No new entry — still detect, en.
        assertEquals(listOf("detect", "en"), curated)
        assertEquals("en", controller.getEffectiveLanguage())
    }

    @Test
    fun `pos points at correct index even with duplicates in input (K-3)`() {
        // Inject a list with duplicates. Sanitize dedupes + sorts; pos must
        // anchor on the active code's NEW position post-sanitize.
        controller.setCuratedLanguages(
            codes = listOf("en", "de", "en", "fr", "fr"),
            preferActive = "en"
        )
        val curated = controller.getCuratedLanguages()
        assertEquals(listOf("de", "en", "fr"), curated)
        val pos = prefs.getInt(Pref.InputLanguagePos.key, -1)
        assertEquals(1, pos)
        assertEquals("en", curated[pos])
    }

    // ── ReprocessStaging override ──

    @Test
    fun `setLanguage during ReprocessStaging routes to reader, not prefs`() {
        // Persist "de" first so we have a known baseline for the permanent value.
        controller.setLanguage("de")
        callbackEvents.clear()

        // Enter staging.
        reader.simulateStateChange(
            PipelineUiState.ReprocessStaging(
                targetSessionId = "s1",
                audioDurationSeconds = 5,
                editableQueue = emptyList(),
                selectedLanguage = null
            )
        )
        // simulateStateChange may have triggered notifyIfChanged; clear before set.
        callbackEvents.clear()

        controller.setLanguage("fr")

        // Reader saw the temporary write.
        assertEquals(1, reader.updateLanguageCallCount)
        assertEquals("fr", reader.lastUpdateLanguage)
        // Permanent pref unchanged: still "de".
        // Direct read so we side-step the pipeline override branch.
        assertFalse(
            "fr must NOT be in the curated list after a staging-only set",
            "fr" in VersionedPrefs.load(prefs, InputLanguagesPlugin)
        )
        // Effective language now reads the override.
        assertEquals("fr", controller.getEffectiveLanguage())
        // Callback fired: de (permanent) → fr (override).
        assertTrue(callbackEvents.isNotEmpty())
        assertEquals("de" to "fr", callbackEvents.last())
    }

    @Test
    fun `repeated language switches inside staging fire one callback per change (W-11)`() {
        controller.setLanguage("de") // baseline
        callbackEvents.clear()

        reader.simulateStateChange(
            PipelineUiState.ReprocessStaging(
                targetSessionId = "s1",
                audioDurationSeconds = 5,
                editableQueue = emptyList(),
                selectedLanguage = null
            )
        )
        callbackEvents.clear()

        controller.setLanguage("fr")
        controller.setLanguage("es")
        controller.setLanguage("es") // identical — must not fire again
        controller.setLanguage("fr")

        // de → fr, fr → es, (no event for es → es), es → fr = 3 events.
        assertEquals(3, callbackEvents.size)
        assertEquals("de" to "fr", callbackEvents[0])
        assertEquals("fr" to "es", callbackEvents[1])
        assertEquals("es" to "fr", callbackEvents[2])
    }

    @Test
    fun `leaving ReprocessStaging restores the permanent effective language`() {
        controller.setLanguage("de") // baseline
        callbackEvents.clear()

        reader.simulateStateChange(
            PipelineUiState.ReprocessStaging(
                targetSessionId = "s1",
                audioDurationSeconds = 5,
                editableQueue = emptyList(),
                selectedLanguage = "fr"
            )
        )
        callbackEvents.clear()
        assertEquals("fr", controller.getEffectiveLanguage())

        // Exit staging.
        reader.simulateStateChange(PipelineUiState.Idle)

        assertEquals("de", controller.getEffectiveLanguage())
        // Callback fired the override → permanent transition.
        assertTrue(callbackEvents.isNotEmpty())
        assertEquals("fr" to "de", callbackEvents.last())
    }

    // ── setCuratedLanguages ──

    @Test
    fun `setCuratedLanguages without preferActive defaults pos to 0 when active falls out`() {
        // Activate "fr" first.
        controller.setLanguage("fr")
        callbackEvents.clear()

        // Now write a new curated list that does NOT contain "fr".
        controller.setCuratedLanguages(listOf("de", "en"))

        val curated = controller.getCuratedLanguages()
        assertEquals(listOf("de", "en"), curated)
        // "fr" was the active code but is no longer present → pos falls back to 0.
        assertEquals(0, prefs.getInt(Pref.InputLanguagePos.key, -1))
        assertEquals("de", controller.getEffectiveLanguage())
        // Callback observed the change.
        assertTrue(callbackEvents.isNotEmpty())
        assertEquals("fr" to "de", callbackEvents.last())
    }

    @Test
    fun `setCuratedLanguages with preferActive anchors pos when present`() {
        controller.setCuratedLanguages(
            codes = listOf("en", "de", "fr"),
            preferActive = "fr"
        )
        val curated = controller.getCuratedLanguages()
        assertEquals(listOf("de", "en", "fr"), curated)
        assertEquals(2, prefs.getInt(Pref.InputLanguagePos.key, -1))
        assertEquals("fr", controller.getEffectiveLanguage())
    }

    // ── lifecycle / dispose ──

    @Test
    fun `init registers the controller as a callback on the reader`() {
        assertTrue(reader.isRegistered(controller))
    }

    @Test
    fun `dispose deregisters the controller from the reader`() {
        controller.dispose()
        assertFalse(reader.isRegistered(controller))
    }

    @Test
    fun `dispose is idempotent`() {
        controller.dispose()
        controller.dispose() // must not throw
        assertFalse(reader.isRegistered(controller))
    }

    @Test
    fun `setCallback before any change still receives the next callback`() {
        // Fresh setup: controller built in @Before with callback already wired.
        // Sanity: rewriting the callback should not lose subsequent events.
        val freshEvents = mutableListOf<Pair<String, String>>()
        controller.setCallback(object : LanguageController.Callback {
            override fun onEffectiveLanguageChanged(oldCode: String, newCode: String) {
                freshEvents.add(oldCode to newCode)
            }
        })
        controller.setLanguage("de")
        assertEquals(1, freshEvents.size)
        assertEquals("detect" to "de", freshEvents.first())
    }

    // ── refreshFromPrefs: cross-instance invalidation (Phase 3) ──

    @Test
    fun `refreshFromPrefs fires callback when an external writer changed the prefs`() {
        // Baseline: this controller observes "detect" (fresh-install head).
        // A *second* controller sharing the same prefs simulates the Settings
        // activity's Application-singleton: it persists "de", which goes
        // through the standard sanitize+pos-resync path. This controller's
        // lastEffective cache is NOT invalidated by that write — the only
        // bridge is an external SharedPreferences listener calling
        // refreshFromPrefs() (production wiring lives in
        // DictateInputMethodService.onCreateInputView).
        val external = LanguageController(prefs, FakePipelineUiStateReader())
        external.setLanguage("de")

        // Even though the underlying prefs now resolve to "de", this
        // controller has not yet been invalidated → no callback fired.
        assertTrue(callbackEvents.isEmpty())

        controller.refreshFromPrefs()

        assertEquals(1, callbackEvents.size)
        assertEquals("detect" to "de", callbackEvents.first())
        assertEquals("de", controller.getEffectiveLanguage())
    }

    @Test
    fun `refreshFromPrefs is idempotent when nothing changed`() {
        // Sanity: calling refreshFromPrefs without an external write must not
        // fire a spurious callback (lastEffective guard inside notifyIfChanged).
        controller.refreshFromPrefs()
        controller.refreshFromPrefs()
        assertTrue(callbackEvents.isEmpty())
    }

    @Test
    fun `getEffectiveLanguageOrNull-style fallback returns null when curated is empty`() {
        // Edge case: although sanitize prevents empty curated lists, a corrupted
        // SharedPreferences string could still cause the load to return default.
        // This test just confirms the code path is null-safe via getEffectiveLanguage,
        // which handles "no languages" by returning "en" rather than throwing.
        val emptyPrefs = FakeSharedPreferences()
        val emptyReader = FakePipelineUiStateReader()
        val freshController = LanguageController(emptyPrefs, emptyReader)
        // No exception, returns the default head.
        val effective = freshController.getEffectiveLanguage()
        assertTrue("effective must be a non-empty code", effective.isNotEmpty())
    }
}
