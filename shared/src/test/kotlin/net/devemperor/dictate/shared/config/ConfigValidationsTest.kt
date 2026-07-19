package net.devemperor.dictate.shared.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Value-constraint tests for [ConfigValidations] (spec §12): each DTO rejects at least one
 * violation, and — the active one the spec calls out — a GATEWAY provider is rejected (F31,
 * reserved but not selectable in v1).
 */
class ConfigValidationsTest {

    private fun ok(errors: List<*>) = assertTrue(errors.toString(), errors.isEmpty())
    private fun bad(errors: List<*>) = assertFalse("expected a violation", errors.isEmpty())

    // -- ProviderConfig --

    @Test
    fun providerConfig_valid_passes() {
        ok(ConfigValidations.providerConfig(
            ProviderConfigEntity(id = "p", providerType = ProviderType.OPENAI, label = "OpenAI"),
        ).errors)
    }

    @Test
    fun providerConfig_emptyLabel_fails() {
        bad(ConfigValidations.providerConfig(
            ProviderConfigEntity(id = "p", providerType = ProviderType.OPENAI, label = ""),
        ).errors)
    }

    @Test
    fun providerConfig_gatewayKind_isRejected() {
        val errors = ConfigValidations.providerConfig(
            ProviderConfigEntity(id = "p", providerType = ProviderType.OPENAI, label = "L", kind = ProviderKind.GATEWAY),
        ).errors

        bad(errors)
        assertTrue(errors.toString(), errors.any { it.message.contains("GATEWAY") })
    }

    // -- ApiCredential --

    @Test
    fun apiCredential_valid16HexFingerprint_passes() {
        ok(ConfigValidations.apiCredential(
            ApiCredentialEntity(id = "c", providerType = ProviderType.OPENAI, label = "Key", keyFingerprint = "0123456789abcdef"),
        ).errors)
    }

    @Test
    fun apiCredential_emptyFingerprint_fails() {
        bad(ConfigValidations.apiCredential(
            ApiCredentialEntity(id = "c", providerType = ProviderType.OPENAI, label = "Key", keyFingerprint = ""),
        ).errors)
    }

    @Test
    fun apiCredential_nonHexOrWrongLengthFingerprint_fails() {
        bad(ConfigValidations.apiCredential(
            ApiCredentialEntity(id = "c", providerType = ProviderType.OPENAI, label = "Key", keyFingerprint = "XYZ"),
        ).errors)
        // Uppercase hex is not the §4.4 shape (lowercase, exactly 16).
        bad(ConfigValidations.apiCredential(
            ApiCredentialEntity(id = "c", providerType = ProviderType.OPENAI, label = "Key", keyFingerprint = "0123456789ABCDEF"),
        ).errors)
    }

    // -- ModelRef --

    @Test
    fun modelRef_valid_passes() {
        ok(ConfigValidations.modelRef(
            ModelRefEntity(id = "m", providerRef = "p", modelId = "gpt-4o-mini", function = ModelFunction.COMPLETION),
        ).errors)
    }

    @Test
    fun modelRef_emptyModelId_fails() {
        bad(ConfigValidations.modelRef(
            ModelRefEntity(id = "m", providerRef = "p", modelId = "", function = ModelFunction.COMPLETION),
        ).errors)
    }

    @Test
    fun modelRef_emptyProviderRef_fails() {
        bad(ConfigValidations.modelRef(
            ModelRefEntity(id = "m", providerRef = "", modelId = "gpt", function = ModelFunction.COMPLETION),
        ).errors)
    }

    // -- Prompt --

    @Test
    fun promptV3_valid_passes() {
        ok(ConfigValidations.promptV3(PromptV3Entity(id = "p", name = "Fix grammar", text = "Please fix.")).errors)
    }

    @Test
    fun promptV3_emptyName_fails() {
        bad(ConfigValidations.promptV3(PromptV3Entity(id = "p", name = "", text = "x")).errors)
    }

    @Test
    fun promptV3_emptyText_fails() {
        bad(ConfigValidations.promptV3(PromptV3Entity(id = "p", name = "n", text = "")).errors)
    }

    // -- Profile --

    @Test
    fun profile_valid_passes() {
        ok(ConfigValidations.profile(
            ProfileEntity(id = "pr", name = "Work", orderedPrompts = listOf(ProfilePromptRef("prompt-1"))),
        ).errors)
    }

    @Test
    fun profile_emptyName_fails() {
        bad(ConfigValidations.profile(ProfileEntity(id = "pr", name = "")).errors)
    }

    @Test
    fun profile_orderedPromptWithEmptyRef_failsWithIndexedPath() {
        val errors = ConfigValidations.profile(
            ProfileEntity(id = "pr", name = "Work", orderedPrompts = listOf(ProfilePromptRef(""))),
        ).errors

        bad(errors)
        assertTrue(errors.toString(), errors.any { it.dataPath.contains("orderedPrompts") })
    }

    // -- Dispatch through validateEntry --

    @Test
    fun validateEntry_dispatchesToTheRightValidation() {
        // A GATEWAY provider wrapped as a catalog entry must still be rejected.
        val gateway = CatalogEntry.Provider(
            ProviderConfigEntity(id = "p", providerType = ProviderType.OPENAI, label = "L", kind = ProviderKind.GATEWAY),
        )
        bad(ConfigValidations.validateEntry(gateway))

        val validPrompt = CatalogEntry.Prompt(PromptV3Entity(id = "p", name = "n", text = "t"))
        assertEquals(emptyList<Any>(), ConfigValidations.validateEntry(validPrompt))
    }
}
