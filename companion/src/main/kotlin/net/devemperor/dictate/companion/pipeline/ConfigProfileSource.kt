package net.devemperor.dictate.companion.pipeline

import net.devemperor.dictate.ai.conversation.TurnInstruction
import net.devemperor.dictate.ai.prompt.PromptService
import net.devemperor.dictate.companion.ai.CompanionConfigWireMapping.toAmbiguityMode
import net.devemperor.dictate.companion.ai.ProfileBackedPromptConfig
import net.devemperor.dictate.companion.data.CompanionConfigRepository
import net.devemperor.dictate.preferences.AmbiguityMode
import net.devemperor.dictate.shared.config.ProfileEntity

/**
 * Resolves the active Block-C profile into the [DictationProfile] one take runs under (desktop-host.md
 * §8.1 / §9.1, F20). The profile is snapshotted at recording start — the desktop never reads it live
 * (parity with ADR-0013 K11 "one consistent mode snapshot per run").
 *
 * ## Scope — the full post-processing surface
 * The AI-credential half (provider/model/key/baseUrl/params) lives in the sibling
 * [net.devemperor.dictate.companion.ai.ProfileBackedAiConfig]. This source resolves the *post-processing*
 * half of a take, split by owner (research desktop-aiconfig-credential-resolution.md part b F6-F11):
 *
 *  - **Profile content** (resolved from the active [ProfileEntity]):
 *    - `ambiguityMode` — via the shared [net.devemperor.dictate.companion.ai.CompanionConfigWireMapping]
 *      (D5.a), the same wire↔domain seam `ProfileBackedAiConfig` uses.
 *    - `instructions` — the profile's **auto-apply** prompts, in order (F7). The desktop has no
 *      keyboard queue or manual-tap surface, so the auto-apply subset *is* the whole instruction set;
 *      non-auto-apply prompts have no trigger surface in v1 (a future desktop prompt-picker), a
 *      documented boundary, not a silent drop.
 *    - `stylePrompt` — via the shared [PromptService] over a [ProfileBackedPromptConfig] (F8), so the
 *      NONE/PREDEFINED/CUSTOM + language-aware fallback logic is the single `:shared-ai` source of
 *      truth rather than a re-implemented `when`.
 *  - **Device prefs** (resolved from [net.devemperor.dictate.companion.domain.CompanionSettings], NOT
 *    the profile — they are per-device ergonomics, not shareable content, so they are deliberately
 *    absent from `ProfileEntity`; adding them would pollute the config hash — F10):
 *    - `language`, `autoFormatEnabled`.
 *
 * The conversation post-processing turn's system prompt stays the fixed template persisted verbatim
 * (ADR-0012, F9) — `systemPromptMode` deliberately never touches it, so [DictationProfile] carries no
 * system-prompt field.
 *
 * When no profile is active (or the row is gone) the plain-transcription [DEFAULT] applies (verbatim
 * insert, no turn) — the honest "nothing configured" state; the device `language`/`autoFormat` apply
 * only once a profile is chosen (F11), keeping one coherent "no profile = plain" story.
 *
 * The empty-transcript skip Android applies to `requiresSelection` slots is intentionally omitted here:
 * `current()` runs *before* transcription (the profile is snapshotted at recording start, §8.1), so it
 * has no transcript to test. Auto-apply prompts are rarely `requiresSelection`, and a `requiresSelection`
 * prompt on an empty transcript is a degenerate case the model tolerates — a tiny, documented divergence
 * (research part b, hint 1 option a).
 */
class ConfigProfileSource(
    private val config: CompanionConfigRepository,
    private val activeProfileId: () -> String?,
    private val language: () -> String?,
    private val autoFormatEnabled: () -> Boolean,
) : ActiveProfileSource {

    override fun current(): DictationProfile {
        val profile = activeProfileId()?.let { config.profile(it) } ?: return DEFAULT
        val lang = language()
        return DictationProfile(
            ambiguityMode = profile.ambiguityMode.toAmbiguityMode(),
            language = lang,
            autoFormatEnabled = autoFormatEnabled(),
            instructions = resolveInstructions(profile),
            stylePrompt = PromptService.create(ProfileBackedPromptConfig(profile)).resolveWhisperStylePrompt(lang),
        )
    }

    /**
     * The profile's auto-apply prompts → [TurnInstruction]s, in order. A `promptRef` whose prompt row
     * is missing is dropped (the profile referencing a deleted prompt is not a crash). `appliesToTranscript`
     * carries the prompt's `requiresSelection` for provenance, mirroring Android's `resolveQueueSlot`.
     */
    private fun resolveInstructions(profile: ProfileEntity): List<TurnInstruction> =
        profile.orderedPrompts
            .filter { it.autoApply }
            .mapNotNull { ref ->
                val prompt = config.prompt(ref.promptRef) ?: return@mapNotNull null
                TurnInstruction(prompt.text, appliesToTranscript = prompt.requiresSelection)
            }

    private companion object {
        /** The transitional default: plain transcription, verbatim insert (no profile active). */
        val DEFAULT = DictationProfile(
            ambiguityMode = AmbiguityMode.ALWAYS_INSERT,
            language = null,
            autoFormatEnabled = false,
            instructions = emptyList(),
            stylePrompt = null,
        )
    }
}
