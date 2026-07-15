package net.devemperor.dictate.rewording

import net.devemperor.dictate.database.entity.PromptType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Decision-logic coverage for [PromptPillPressPolicy] — the press-vs-state gate.
 *
 * Two axes now: the busy-state greying (unchanged for AI [PromptType.PROMPT]
 * pills) and the pill type. A [PromptType.TEXT] pill is a literal snippet that
 * inserts 1:1 in every state, so a short-press must ALWAYS activate it — the
 * greyed-out gate never applies to it (Chunk 2 bugfix).
 */
class PromptPillPressPolicyTest {

    @Test
    fun `short press on an enabled prompt pill activates it`() {
        assertEquals(
            PromptPillAction.ACTIVATE,
            PromptPillPressPolicy.decide(PromptPillPress.SHORT, textOnlyDisabled = false, pillType = PromptType.PROMPT),
        )
    }

    @Test
    fun `long press on an enabled prompt pill opens the editor`() {
        assertEquals(
            PromptPillAction.EDIT,
            PromptPillPressPolicy.decide(PromptPillPress.LONG, textOnlyDisabled = false, pillType = PromptType.PROMPT),
        )
    }

    @Test
    fun `short press on a greyed prompt pill stays inert`() {
        assertEquals(
            PromptPillAction.IGNORE,
            PromptPillPressPolicy.decide(PromptPillPress.SHORT, textOnlyDisabled = true, pillType = PromptType.PROMPT),
        )
    }

    @Test
    fun `long press on a greyed prompt pill applies it`() {
        assertEquals(
            PromptPillAction.APPLY_DISABLED,
            PromptPillPressPolicy.decide(PromptPillPress.LONG, textOnlyDisabled = true, pillType = PromptType.PROMPT),
        )
    }

    /**
     * Chunk 2 regression: the reported bug was that a text pill did nothing on a
     * short-press while recording/pipeline was busy (it was greyed like an AI
     * prompt and the short-press was swallowed). A TEXT pill must ALWAYS activate
     * on a short-press, even when the busy-state flag is set.
     */
    @Test
    fun `short press on a busy-state text pill still activates it`() {
        assertEquals(
            PromptPillAction.ACTIVATE,
            PromptPillPressPolicy.decide(PromptPillPress.SHORT, textOnlyDisabled = true, pillType = PromptType.TEXT),
        )
    }

    @Test
    fun `short press on an idle text pill activates it`() {
        assertEquals(
            PromptPillAction.ACTIVATE,
            PromptPillPressPolicy.decide(PromptPillPress.SHORT, textOnlyDisabled = false, pillType = PromptType.TEXT),
        )
    }

    @Test
    fun `long press on a text pill opens the editor`() {
        assertEquals(
            PromptPillAction.EDIT,
            PromptPillPressPolicy.decide(PromptPillPress.LONG, textOnlyDisabled = true, pillType = PromptType.TEXT),
        )
    }

    /**
     * A greyed AI prompt pill must never swallow a long-press: whatever the
     * press, it still has a reachable action (apply on long, inert on short).
     */
    @Test
    fun `disabled prompt pill only ever yields IGNORE or APPLY_DISABLED`() {
        PromptPillPress.entries.forEach { press ->
            val outcome = PromptPillPressPolicy.decide(press, textOnlyDisabled = true, pillType = PromptType.PROMPT)
            assertEquals(
                "disabled prompt pill for $press",
                true,
                outcome == PromptPillAction.IGNORE || outcome == PromptPillAction.APPLY_DISABLED,
            )
        }
    }
}
