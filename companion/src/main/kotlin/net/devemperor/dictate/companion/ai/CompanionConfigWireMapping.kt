package net.devemperor.dictate.companion.ai

import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.preferences.AmbiguityMode
import net.devemperor.dictate.shared.config.AmbiguityModeValue
import net.devemperor.dictate.shared.config.ProviderType

/**
 * The companion's value-equality bridge between the platform-free **wire** enums in `:shared`
 * (`ProviderType`, `AmbiguityModeValue`) and the behaviour-carrying **domain** enums in `:shared-ai`
 * (`AIProvider`, `AmbiguityMode`). The desktop companion is — like `:app` — a module that sees BOTH,
 * so per D5.a it gets its own small mapper rather than reaching for the app's `ConfigWireMapping`
 * (which lives in `:app` and is invisible here).
 *
 * Conversions are by enum `name()` (both sides share identical names by construction). Parity is
 * pinned by `CompanionConfigWireEnumParityTest`, the mirror of the app's `ConfigWireEnumParityTest`:
 * if a value is added on one side only, the name-set assertion fails and the drift cannot silently
 * break a resolved profile.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §4.8, §9.2
 */
object CompanionConfigWireMapping {

    fun ProviderType.toAIProvider(): AIProvider =
        runCatching { AIProvider.valueOf(name) }.getOrDefault(AIProvider.OPENAI)

    fun AmbiguityModeValue.toAmbiguityMode(): AmbiguityMode =
        AmbiguityMode.fromPersistKey(name)
}
