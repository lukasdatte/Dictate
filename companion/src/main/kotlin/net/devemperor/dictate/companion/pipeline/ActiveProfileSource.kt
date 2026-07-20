package net.devemperor.dictate.companion.pipeline

import net.devemperor.dictate.ai.conversation.TurnInstruction
import net.devemperor.dictate.preferences.AmbiguityMode

/**
 * The dictation settings one take runs under: how to post-process the transcript and how to treat an
 * ambiguous result (desktop-host.md §5.1, ADR-0013).
 *
 * In D1/D2 this is a **transitional** value assembled from [net.devemperor.dictate.companion.domain.CompanionSettings]
 * (see [TransitionalProfileSource]); D3 replaces the source with the resolved Block-C profile
 * (`provider`/`model`/`prompts`) without touching the pipeline (§5.1 NOTE).
 */
data class DictationProfile(
    val ambiguityMode: AmbiguityMode,
    val language: String?,
    val autoFormatEnabled: Boolean,
    val instructions: List<TurnInstruction>,
    /** Optional transcription style/vocabulary hint passed to `transcribe` (ADR-0007-adjacent). */
    val stylePrompt: String?,
)

/** Supplies the [DictationProfile] in force for the next take. */
fun interface ActiveProfileSource {
    fun current(): DictationProfile
}

/**
 * The D1/D2 transitional profile: a plain-transcription default (no auto-format, no instructions,
 * `ALWAYS_INSERT`) with no language pinned. Real provider/model/prompt/ambiguity configuration lands
 * in D3 (§5.1 NOTE) — until then a desktop dictation transcribes and inserts verbatim, which is the
 * honest capability before the profile UI exists.
 */
class TransitionalProfileSource : ActiveProfileSource {
    override fun current(): DictationProfile = DictationProfile(
        ambiguityMode = AmbiguityMode.ALWAYS_INSERT,
        language = null,
        autoFormatEnabled = false,
        instructions = emptyList(),
        stylePrompt = null,
    )
}
