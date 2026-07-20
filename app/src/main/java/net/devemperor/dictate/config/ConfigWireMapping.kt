package net.devemperor.dictate.config

import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.preferences.AmbiguityMode
import net.devemperor.dictate.ai.prompt.PromptMode
import net.devemperor.dictate.shared.config.AmbiguityModeValue
import net.devemperor.dictate.shared.config.ModelFunction
import net.devemperor.dictate.shared.config.PromptSelectionMode
import net.devemperor.dictate.shared.config.ProviderType

/**
 * The value-equality bridge between the behaviour-carrying **domain** enums in `:shared-ai`
 * (`AIProvider`, `AmbiguityMode`, `PromptMode`, `AIFunction`) and the platform-free **wire** enums in
 * `:shared` (`ProviderType`, `AmbiguityModeValue`, `PromptSelectionMode`, `ModelFunction`). `:shared`
 * sits below `:shared-ai` and cannot reference the domain originals, so it defines its own mirror
 * enums (spec §4.8, §13 D6).
 *
 * `:app` is the one module that sees BOTH, so the conversion — and the parity test that pins
 * name/value equality so they can never drift (`ConfigWireEnumParityTest`) — lives here.
 *
 * Conversions are by enum `name()` (both sides share identical names by construction), except
 * [PromptMode]↔[PromptSelectionMode] which the parity test also confirms name-aligned.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §4.8, §9.2
 */
object ConfigWireMapping {

    fun AIProvider.toWire(): ProviderType =
        runCatching { ProviderType.valueOf(name) }.getOrDefault(ProviderType.OPENAI)

    fun ProviderType.toAIProvider(): AIProvider =
        runCatching { AIProvider.valueOf(name) }.getOrDefault(AIProvider.OPENAI)

    fun AmbiguityMode.toWire(): AmbiguityModeValue =
        runCatching { AmbiguityModeValue.valueOf(persistKey) }.getOrDefault(AmbiguityModeValue.ALWAYS_INSERT)

    fun AmbiguityModeValue.toAmbiguityMode(): AmbiguityMode =
        AmbiguityMode.fromPersistKey(name)

    fun PromptMode.toWire(): PromptSelectionMode =
        runCatching { PromptSelectionMode.valueOf(name) }.getOrDefault(PromptSelectionMode.NONE)

    fun PromptSelectionMode.toPromptMode(): PromptMode =
        runCatching { PromptMode.valueOf(name) }.getOrDefault(PromptMode.NONE)

    fun AIFunction.toWire(): ModelFunction =
        runCatching { ModelFunction.valueOf(name) }.getOrDefault(ModelFunction.COMPLETION)

    fun ModelFunction.toAIFunction(): AIFunction =
        runCatching { AIFunction.valueOf(name) }.getOrDefault(AIFunction.COMPLETION)
}
