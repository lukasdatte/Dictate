package net.devemperor.dictate.companion.pipeline

import net.devemperor.dictate.ai.prompt.PromptTemplates
import net.devemperor.dictate.companion.data.CompanionConfigRepository
import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.preferences.AmbiguityMode
import net.devemperor.dictate.shared.config.AmbiguityModeValue
import net.devemperor.dictate.shared.config.ProfileEntity
import net.devemperor.dictate.shared.config.ProfilePromptRef
import net.devemperor.dictate.shared.config.PromptSelectionMode
import net.devemperor.dictate.shared.config.PromptV3Entity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConfigProfileSource] resolves the take's full post-processing surface from the active profile +
 * device prefs (research desktop-aiconfig-credential-resolution.md part b F6-F11): auto-apply
 * instructions and style prompt from the profile, language + auto-format from the injected device
 * suppliers, and the plain [DictationProfile] default when no profile is active. This is the regression
 * guard against the finding that only `ambiguityMode` was resolved and the rest stayed transitional.
 */
class ConfigProfileSourceTest {

    private val database = CompanionDatabase.inMemory()
    private val config = CompanionConfigRepository(database, now = { 1L })

    private var activeProfileId: String? = "prof-1"
    private var language: String? = "de"
    private var autoFormat: Boolean = false

    private val source = ConfigProfileSource(
        config = config,
        activeProfileId = { activeProfileId },
        language = { language },
        autoFormatEnabled = { autoFormat },
    )

    private fun savePrompt(id: String, text: String, requiresSelection: Boolean = false) {
        config.save(PromptV3Entity(id = id, name = id, text = text, requiresSelection = requiresSelection))
    }

    private fun saveProfile(
        orderedPrompts: List<ProfilePromptRef> = emptyList(),
        stylePromptMode: PromptSelectionMode = PromptSelectionMode.NONE,
        stylePromptCustomText: String = "",
        ambiguityMode: AmbiguityModeValue = AmbiguityModeValue.ALWAYS_INSERT,
    ) {
        config.save(
            ProfileEntity(
                id = "prof-1",
                name = "P",
                orderedPrompts = orderedPrompts,
                stylePromptMode = stylePromptMode,
                stylePromptCustomText = stylePromptCustomText,
                ambiguityMode = ambiguityMode,
            )
        )
    }

    @Test
    fun noActiveProfile_returnsThePlainDefault() {
        activeProfileId = null

        val profile = source.current()

        assertEquals(AmbiguityMode.ALWAYS_INSERT, profile.ambiguityMode)
        assertNull(profile.language)
        assertTrue(!profile.autoFormatEnabled)
        assertEquals(emptyList<Any>(), profile.instructions)
        assertNull(profile.stylePrompt)
    }

    @Test
    fun activeProfileIdSetButRowMissing_returnsThePlainDefault() {
        activeProfileId = "gone" // never persisted

        val profile = source.current()

        assertNull(profile.language)
        assertEquals(emptyList<Any>(), profile.instructions)
    }

    @Test
    fun resolvesOnlyTheAutoApplyPrompts_inOrder_withRequiresSelectionAsProvenance() {
        savePrompt("p-auto", "tidy it", requiresSelection = true)
        savePrompt("p-manual", "never runs on desktop")
        saveProfile(
            orderedPrompts = listOf(
                ProfilePromptRef(promptRef = "p-auto", autoApply = true),
                ProfilePromptRef(promptRef = "p-manual", autoApply = false),
            )
        )

        val instructions = source.current().instructions

        assertEquals(1, instructions.size)
        assertEquals("tidy it", instructions.single().text)
        assertTrue("requiresSelection carried as appliesToTranscript", instructions.single().appliesToTranscript)
    }

    @Test
    fun aMissingPromptRow_isDroppedNotCrashed() {
        savePrompt("p-auto", "tidy it")
        saveProfile(
            orderedPrompts = listOf(
                ProfilePromptRef(promptRef = "p-auto", autoApply = true),
                ProfilePromptRef(promptRef = "p-deleted", autoApply = true), // referenced, never saved
            )
        )

        val instructions = source.current().instructions

        assertEquals(listOf("tidy it"), instructions.map { it.text })
    }

    @Test
    fun stylePromptPredefined_usesTheSharedLanguageAwarePunctuationPrompt() {
        saveProfile(stylePromptMode = PromptSelectionMode.PREDEFINED)

        language = "de"
        assertEquals(PromptTemplates.getPunctuationPromptForLanguage("de"), source.current().stylePrompt)

        language = null
        assertEquals(PromptTemplates.getPunctuationPromptForLanguage(null), source.current().stylePrompt)
    }

    @Test
    fun stylePromptCustom_usesTheCustomText_andNoneIsNull() {
        saveProfile(stylePromptMode = PromptSelectionMode.CUSTOM, stylePromptCustomText = "medical vocab")
        assertEquals("medical vocab", source.current().stylePrompt)

        saveProfile(stylePromptMode = PromptSelectionMode.NONE)
        assertNull(source.current().stylePrompt)
    }

    @Test
    fun languageAndAutoFormat_reflectTheInjectedDeviceSuppliers() {
        saveProfile()

        language = "en"
        autoFormat = true
        val profile = source.current()

        assertEquals("en", profile.language)
        assertTrue(profile.autoFormatEnabled)
    }

    @Test
    fun ambiguityMode_isResolvedFromTheProfile() {
        saveProfile(ambiguityMode = AmbiguityModeValue.ALWAYS_REVIEW)

        assertEquals(AmbiguityMode.ALWAYS_REVIEW, source.current().ambiguityMode)
    }
}
