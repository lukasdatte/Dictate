package net.devemperor.dictate.companion.pipeline

import net.devemperor.dictate.companion.data.CompanionConfigRepository
import net.devemperor.dictate.preferences.AmbiguityMode
import net.devemperor.dictate.shared.config.AmbiguityModeValue

/**
 * Resolves the active Block-C profile into the [DictationProfile] one take runs under (desktop-host.md
 * §8.1 / §9.1, F20). The ambiguity policy is snapshotted from the profile at recording start — the
 * desktop never reads it live (parity with ADR-0013 K11 "one consistent mode snapshot per run").
 *
 * ## Scope (D3)
 * D3 wires the axis the review flow depends on — [DictationProfile.ambiguityMode] from the profile.
 * The remaining resolution (provider/model/key → `AiConfig`, and the profile's prompts → auto-format /
 * instructions / style prompt) stays on the transitional [net.devemperor.dictate.companion.ai.CompanionAiConfig]
 * path (§5.1 NOTE, §15 Gap 5): the desktop has no credential→config resolver yet (Block B/E). Until
 * then a profiled take honours its review mode but transcribes+post-processes with the transitional
 * defaults — the honest capability before desktop `AiConfig` resolution exists. When no profile is
 * active, the plain-transcription default applies ([TransitionalProfileSource]).
 */
class ConfigProfileSource(
    private val config: CompanionConfigRepository,
    private val activeProfileId: () -> String?,
) : ActiveProfileSource {

    override fun current(): DictationProfile {
        val profile = activeProfileId()?.let { config.profile(it) } ?: return DEFAULT
        return DEFAULT.copy(ambiguityMode = profile.ambiguityMode.toDomain())
    }

    private fun AmbiguityModeValue.toDomain(): AmbiguityMode = when (this) {
        AmbiguityModeValue.ALWAYS_INSERT -> AmbiguityMode.ALWAYS_INSERT
        AmbiguityModeValue.AUTO -> AmbiguityMode.AUTO
        AmbiguityModeValue.ALWAYS_REVIEW -> AmbiguityMode.ALWAYS_REVIEW
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
