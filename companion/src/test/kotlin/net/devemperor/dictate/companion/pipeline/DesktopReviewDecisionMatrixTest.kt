package net.devemperor.dictate.companion.pipeline

import net.devemperor.dictate.ai.conversation.ReviewDecision
import net.devemperor.dictate.ai.conversation.Verdict
import net.devemperor.dictate.preferences.AmbiguityMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The desktop review verdict matrix (desktop-host.md §8.2, acceptance §2 criterion 7).
 *
 * The desktop pipeline calls `ReviewDecision.decide` **verbatim** — it never rebuilds the rule
 * (`DictationEffects.postProcess` / `submitContinuation`, §8.2 footgun). This parametrised suite pins
 * the five-row matrix that governs both the first verdict and every re-dictate continuation, so a
 * change to the shared rule that would silently flip the desktop's insert-vs-review behaviour turns
 * this red.
 */
@RunWith(Parameterized::class)
class DesktopReviewDecisionMatrixTest(
    private val label: String,
    private val mode: AmbiguityMode,
    private val needsClarification: Boolean,
    private val message: String?,
    private val expected: Verdict,
) {

    @Test
    fun verdictMatches() {
        assertEquals(label, expected, ReviewDecision.decide(mode, needsClarification, message))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any?>> = listOf(
            // ALWAYS_INSERT — never review, regardless of verdict/message.
            row("ALWAYS_INSERT / clarify+msg → INSERT", AmbiguityMode.ALWAYS_INSERT, true, "which Anna?", Verdict.INSERT),
            row("ALWAYS_INSERT / no clarify → INSERT", AmbiguityMode.ALWAYS_INSERT, false, null, Verdict.INSERT),
            // AUTO — review only when the model flagged clarification AND produced a non-blank message.
            row("AUTO / no clarify → INSERT", AmbiguityMode.AUTO, false, "ignored", Verdict.INSERT),
            row("AUTO / clarify + null message → INSERT", AmbiguityMode.AUTO, true, null, Verdict.INSERT),
            row("AUTO / clarify + blank message → INSERT", AmbiguityMode.AUTO, true, "   ", Verdict.INSERT),
            row("AUTO / clarify + non-blank message → REVIEW", AmbiguityMode.AUTO, true, "which Anna?", Verdict.REVIEW),
            // ALWAYS_REVIEW — always review (panel renders output-only when the message is blank).
            row("ALWAYS_REVIEW / no clarify → REVIEW", AmbiguityMode.ALWAYS_REVIEW, false, null, Verdict.REVIEW),
            row("ALWAYS_REVIEW / clarify+msg → REVIEW", AmbiguityMode.ALWAYS_REVIEW, true, "hm", Verdict.REVIEW),
        )

        private fun row(label: String, mode: AmbiguityMode, needsClarification: Boolean, message: String?, expected: Verdict): Array<Any?> =
            arrayOf(label, mode, needsClarification, message, expected)
    }
}
