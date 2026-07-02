package net.devemperor.dictate.rewording

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Decision-logic coverage for [PromptPillPressPolicy] — the press-vs-state
 * gate that lets a long-press apply a greyed-out text-only prompt pill while
 * keeping every other press mapping unchanged.
 */
class PromptPillPressPolicyTest {

    @Test
    fun `short press on an enabled pill activates it`() {
        assertEquals(
            PromptPillAction.ACTIVATE,
            PromptPillPressPolicy.decide(PromptPillPress.SHORT, textOnlyDisabled = false),
        )
    }

    @Test
    fun `long press on an enabled pill opens the editor`() {
        assertEquals(
            PromptPillAction.EDIT,
            PromptPillPressPolicy.decide(PromptPillPress.LONG, textOnlyDisabled = false),
        )
    }

    @Test
    fun `short press on a greyed text-only pill stays inert`() {
        assertEquals(
            PromptPillAction.IGNORE,
            PromptPillPressPolicy.decide(PromptPillPress.SHORT, textOnlyDisabled = true),
        )
    }

    @Test
    fun `long press on a greyed text-only pill applies it`() {
        assertEquals(
            PromptPillAction.APPLY_DISABLED,
            PromptPillPressPolicy.decide(PromptPillPress.LONG, textOnlyDisabled = true),
        )
    }

    /**
     * The greyed state must never swallow a long-press: whatever the press,
     * a disabled text-only pill still has a reachable action (apply on long,
     * inert on short) — never the enabled EDIT/ACTIVATE mappings.
     */
    @Test
    fun `disabled state only ever yields IGNORE or APPLY_DISABLED`() {
        PromptPillPress.entries.forEach { press ->
            val outcome = PromptPillPressPolicy.decide(press, textOnlyDisabled = true)
            assertEquals(
                "disabled pill for $press",
                true,
                outcome == PromptPillAction.IGNORE || outcome == PromptPillAction.APPLY_DISABLED,
            )
        }
    }
}
