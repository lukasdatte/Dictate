package net.devemperor.dictate.companion.pipeline

import net.devemperor.dictate.companion.ai.CompanionConfigWireMapping.toAmbiguityMode
import net.devemperor.dictate.companion.data.CompanionConfigRepository
import net.devemperor.dictate.preferences.AmbiguityMode

/**
 * Resolves the active Block-C profile into the [DictationProfile] one take runs under (desktop-host.md
 * §8.1 / §9.1, F20). The ambiguity policy is snapshotted from the profile at recording start — the
 * desktop never reads it live (parity with ADR-0013 K11 "one consistent mode snapshot per run").
 *
 * ## Scope
 * This source resolves [DictationProfile.ambiguityMode] from the active profile (via the shared
 * [net.devemperor.dictate.companion.ai.CompanionConfigWireMapping], D5.a). The provider/model/key
 * half of the resolution now lives in [net.devemperor.dictate.companion.ai.ProfileBackedAiConfig]
 * (wired in `CompanionContainer.production`), so a profiled take authenticates against its real
 * provider — the empty-key gap is closed.
 *
 * Still transitional here (§5.1 NOTE, §15 Gap 5 / research desktop-aiconfig-credential-resolution.md
 * part b): the profile's prompts → `instructions` / style prompt, and `autoFormatEnabled` / `language`
 * (which have no `ProfileEntity` field — they are prefs on Android). Until that lands a profiled take
 * honours its review mode and AI credentials but post-processes with the transitional defaults. When
 * no profile is active, the plain-transcription default applies ([TransitionalProfileSource]).
 */
class ConfigProfileSource(
    private val config: CompanionConfigRepository,
    private val activeProfileId: () -> String?,
) : ActiveProfileSource {

    override fun current(): DictationProfile {
        val profile = activeProfileId()?.let { config.profile(it) } ?: return DEFAULT
        // The ambiguity → domain map is the shared CompanionConfigWireMapping (D5.a), the same wire↔
        // domain seam ProfileBackedAiConfig uses — one place, parity-pinned, instead of an inline when.
        return DEFAULT.copy(ambiguityMode = profile.ambiguityMode.toAmbiguityMode())
    }

    private companion object {
        /** The transitional default: plain transcription, verbatim insert (no profile, or partial resolution). */
        val DEFAULT = DictationProfile(
            ambiguityMode = AmbiguityMode.ALWAYS_INSERT,
            language = null,
            autoFormatEnabled = false,
            instructions = emptyList(),
            stylePrompt = null,
        )
    }
}
