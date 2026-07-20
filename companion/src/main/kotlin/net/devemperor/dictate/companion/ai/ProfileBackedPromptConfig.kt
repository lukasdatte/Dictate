package net.devemperor.dictate.companion.ai

import net.devemperor.dictate.ai.port.PromptConfig
import net.devemperor.dictate.ai.prompt.PromptMode
import net.devemperor.dictate.companion.ai.CompanionConfigWireMapping.toPromptMode
import net.devemperor.dictate.shared.config.ProfileEntity

/**
 * Entity-backed [PromptConfig] over a resolved Block-C [ProfileEntity] — the desktop twin of the
 * Android `ProfilePromptConfig`. It exposes the profile's style/system prompt selection so the shared
 * [net.devemperor.dictate.ai.prompt.PromptService] resolves the Whisper style prompt exactly the way
 * `:app` does (one behaviour for both platforms), instead of `ConfigProfileSource` open-coding a second
 * NONE/PREDEFINED/CUSTOM `when` (research desktop-aiconfig-credential-resolution.md part b F8).
 *
 * Only the style-prompt half is consumed on desktop v1: the conversation post-processing turn uses a
 * fixed system prompt persisted verbatim (ADR-0012, F9), so `systemPromptMode`/`systemPromptCustomText`
 * feed only the standalone REWORDING/LIVE/QUEUED contexts the desktop has no surface for yet. They are
 * mapped here regardless so a future standalone-rewording path can reuse `SystemPromptResolver`
 * unchanged.
 *
 * The mode conversion goes through the shared [CompanionConfigWireMapping.toPromptMode] (D5.a), the same
 * wire↔domain seam the rest of the resolution uses; its parity is pinned by
 * `CompanionConfigWireEnumParityTest`.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/desktop-aiconfig-credential-resolution.md
 */
class ProfileBackedPromptConfig(private val profile: ProfileEntity) : PromptConfig {

    override fun stylePromptMode(): PromptMode = profile.stylePromptMode.toPromptMode()

    override fun stylePromptCustomText(): String = profile.stylePromptCustomText

    override fun systemPromptMode(): PromptMode = profile.systemPromptMode.toPromptMode()

    override fun systemPromptCustomText(): String = profile.systemPromptCustomText
}
