package net.devemperor.dictate.ai.conversation

import net.devemperor.dictate.preferences.AmbiguityMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exhaustive matrix for the pure verdict rule (ADR-0013): 3 modes x
 * {needs true/false} x {message null/blank/text}.
 */
class ReviewDecisionTest {

    private fun decide(mode: AmbiguityMode, needs: Boolean, msg: String?) =
        ReviewDecision.decide(mode, needs, msg)

    @Test
    fun `ALWAYS_INSERT never reviews`() {
        for (needs in listOf(true, false)) {
            for (msg in listOf(null, "", "  ", "unclear")) {
                assertEquals(Verdict.INSERT, decide(AmbiguityMode.ALWAYS_INSERT, needs, msg))
            }
        }
    }

    @Test
    fun `ALWAYS_REVIEW always reviews`() {
        for (needs in listOf(true, false)) {
            for (msg in listOf(null, "", "  ", "unclear")) {
                assertEquals(Verdict.REVIEW, decide(AmbiguityMode.ALWAYS_REVIEW, needs, msg))
            }
        }
    }

    @Test
    fun `AUTO reviews only when flagged and message non-blank`() {
        assertEquals(Verdict.REVIEW, decide(AmbiguityMode.AUTO, true, "which name?"))
    }

    @Test
    fun `AUTO inserts when not flagged`() {
        assertEquals(Verdict.INSERT, decide(AmbiguityMode.AUTO, false, "which name?"))
    }

    @Test
    fun `AUTO inserts when flagged but message is null or blank (safety net)`() {
        assertEquals(Verdict.INSERT, decide(AmbiguityMode.AUTO, true, null))
        assertEquals(Verdict.INSERT, decide(AmbiguityMode.AUTO, true, ""))
        assertEquals(Verdict.INSERT, decide(AmbiguityMode.AUTO, true, "   "))
    }
}
