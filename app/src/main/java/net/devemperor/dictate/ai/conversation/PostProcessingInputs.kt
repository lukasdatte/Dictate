package net.devemperor.dictate.ai.conversation

/**
 * A single resolved instruction that goes into the consolidated first user
 * message. The platform layer (pipeline) resolves prompt-queue slots into
 * these before handing them to [ConversationTurnBuilder].
 *
 * [appliesToTranscript] mirrors the legacy `requiresSelection` semantics. In
 * the consolidated single call every instruction is listed for the model to
 * apply in order, so the flag is retained for provenance / future use (Paket 2)
 * rather than branching the build.
 */
data class TurnInstruction(
    val text: String,
    val appliesToTranscript: Boolean
)

/**
 * Everything [ConversationTurnBuilder] needs to build the consolidated first
 * user message of a post-processing conversation. The caller resolves all
 * platform state (enabled prefs, queue slots, language) up front, keeping the
 * builder Android-free.
 *
 * [forceTurn] is the Paket 2 extension point: the ambiguity modes
 * (Auto / Always Review) must be able to force a conversation turn on a bare
 * transcript even when there is no auto-formatting and no queued instruction.
 * Default `false` preserves the Paket 1 behaviour (a plain transcription runs
 * no turn).
 */
data class PostProcessingInputs(
    val transcript: String,
    val languageHint: String?,
    val autoFormatEnabled: Boolean,
    val instructions: List<TurnInstruction>,
    val includeAmbiguityTask: Boolean = true,
    val forceTurn: Boolean = false,
    /**
     * The foreground app's screen, serialised, or `null` when the user has not
     * opted in / the accessibility service is not connected. Opt-in feature —
     * `null` is the normal case and must stay cheap.
     *
     * Already a plain String by the time it gets here: the platform types
     * (`AccessibilityNodeInfo`) are resolved by
     * [net.devemperor.dictate.accessibility.AccessibilityContextReader] at the
     * send-tap, keeping this class and the builder Android-free per ADR-0012.
     *
     * **Untrusted.** This is another app's content, so
     * [ConversationTurnBuilder] must emit it through
     * `PromptBuilder.dataSection` (escaped), never `section`.
     */
    val uiContext: String? = null,
)
