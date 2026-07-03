package net.devemperor.dictate.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.devemperor.dictate.testutil.fakeModuleServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-reducer tests for [ResendModule].
 *
 * Coverage:
 * - ResendLastAudio / ResendLastAudioLong arms cooldown when off AND emits
 *   the ScheduleCooldownExpiry effect (F-029)
 * - Second click while cooldown=true is silent no-op (returns null)
 * - ResendCooldownExpired clears cooldown
 * - The ScheduleCooldownExpiry effect emits ResendCooldownExpired after
 *   COOLDOWN_MS (F-029 latch-fix — long-press → cooldown → expires →
 *   button enabled again)
 * - MarkLastAudio toggles lastAudioExists idempotently
 * - Lens round-trip + id + initial state
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResendModuleTest {

    private val module = ResendModule
    private fun ctx() = ReducerContext(global = DictateUiState.initial())

    @Test
    fun `ResendLastAudio with cooldown off arms cooldown and schedules expiry`() {
        val state = ResendState(resendCooldown = false)
        val result = module.reduce(state, Action.ResendAction.ResendLastAudio, ctx())
        assertEquals(true, result!!.nextState.resendCooldown)
        // F-029 — the arm now schedules its own clear so the button
        // re-enables after the window (was `emptyList()` pre-fix).
        assertEquals(listOf(ResendModule.Effect.ScheduleCooldownExpiry), result.sideEffects)
    }

    @Test
    fun `ResendLastAudio with cooldown on returns null (silent no-op)`() {
        val state = ResendState(resendCooldown = true)
        assertNull(module.reduce(state, Action.ResendAction.ResendLastAudio, ctx()))
    }

    @Test
    fun `ResendLastAudioLong with cooldown off arms cooldown and schedules expiry`() {
        val state = ResendState(resendCooldown = false)
        val result = module.reduce(state, Action.ResendAction.ResendLastAudioLong, ctx())
        assertEquals(true, result!!.nextState.resendCooldown)
        // F-029 — the LONG-press path previously armed the cooldown but
        // emitted no effect, so nothing ever cleared it: the RESEND button
        // latched disabled until service restart. The scheduled expiry is
        // the fix.
        assertEquals(listOf(ResendModule.Effect.ScheduleCooldownExpiry), result.sideEffects)
    }

    @Test
    fun `ResendLastAudioLong with cooldown on is silent no-op`() {
        val state = ResendState(resendCooldown = true)
        assertNull(module.reduce(state, Action.ResendAction.ResendLastAudioLong, ctx()))
    }

    @Test
    fun `ResendCooldownExpired clears cooldown`() {
        val state = ResendState(resendCooldown = true)
        val result = module.reduce(state, Action.ResendAction.ResendCooldownExpired, ctx())
        assertEquals(false, result!!.nextState.resendCooldown)
    }

    @Test
    fun `ResendCooldownExpired while not on cooldown is no-op`() {
        val state = ResendState(resendCooldown = false)
        assertNull(module.reduce(state, Action.ResendAction.ResendCooldownExpired, ctx()))
    }

    // ────────────────────────────────────────────────────────────────
    // F-029 — module-owned cooldown timer (latch-fix regression).
    //
    // Before the fix, the cooldown *clear* was scheduled UI-side (a
    // `Handler.postDelayed` in DictateInputMethodService.onResendClicked)
    // and only on the short-press path. A RESEND long-press armed the
    // cooldown via ResendLastAudioLong but nothing ever cleared it, so
    // the `enabledResolver { !resendCooldown }` disabled the button
    // permanently. The timer is now module-owned: both arms emit
    // Effect.ScheduleCooldownExpiry, whose handler emits
    // ResendCooldownExpired after COOLDOWN_MS. These tests are red on the
    // pre-fix module (no ScheduleCooldownExpiry effect exists) and green
    // on the fixed module.
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `ScheduleCooldownExpiry effect emits ResendCooldownExpired after COOLDOWN_MS`() = runTest {
        val emitted = mutableListOf<Action>()
        val services = fakeModuleServices(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            emitAction = { emitted += it },
        )

        module.runEffect(ResendModule.Effect.ScheduleCooldownExpiry, services)

        // Still inside the window — nothing emitted yet.
        advanceTimeBy(ResendModule.COOLDOWN_MS - 1)
        assertTrue("no expiry before COOLDOWN_MS elapses", emitted.isEmpty())

        // Window elapsed — the clear action fires exactly once.
        advanceUntilIdle()
        assertEquals(listOf(Action.ResendAction.ResendCooldownExpired), emitted)
    }

    @Test
    fun `long-press cooldown expires and re-enables the button (F-029 latch-fix)`() = runTest {
        val emitted = mutableListOf<Action>()
        val services = fakeModuleServices(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            emitAction = { emitted += it },
        )

        // 1. Long-press arms the cooldown → resendCooldown = true, button
        //    disabled by the enabledResolver.
        val armed = module.reduce(
            ResendState(resendCooldown = false),
            Action.ResendAction.ResendLastAudioLong,
            ctx(),
        )!!
        assertEquals(true, armed.nextState.resendCooldown)

        // 2. Run the scheduled effect(s) the arm emitted.
        armed.sideEffects.forEach { module.runEffect(it, services) }

        // 3. Advance past the window — the module emits the clear.
        advanceUntilIdle()
        assertEquals(listOf(Action.ResendAction.ResendCooldownExpired), emitted)

        // 4. Apply the clear through the reducer → cooldown back to false,
        //    button enabled again. Pre-fix this action was never dispatched
        //    on the long-press path, so the button stayed latched.
        val cleared = module.reduce(
            armed.nextState,
            Action.ResendAction.ResendCooldownExpired,
            ctx(),
        )!!
        assertEquals(false, cleared.nextState.resendCooldown)
    }

    @Test
    fun `MarkLastAudio true updates lastAudioExists`() {
        val state = ResendState(lastAudioExists = false)
        val result = module.reduce(state, Action.ResendAction.MarkLastAudio(exists = true), ctx())
        assertEquals(true, result!!.nextState.lastAudioExists)
    }

    @Test
    fun `MarkLastAudio with same value returns null (idempotent)`() {
        val state = ResendState(lastAudioExists = true)
        assertNull(module.reduce(state, Action.ResendAction.MarkLastAudio(exists = true), ctx()))
    }

    @Test
    fun `module id is Resend`() {
        assertEquals(ModuleId.Resend, module.id)
    }

    @Test
    fun `lens round-trip preserves resend axis`() {
        val state = DictateUiState.initial().copy(
            resend = ResendState(lastAudioExists = true, resendEnabled = true, resendCooldown = false),
        )
        val sub = module.read(state)
        assertEquals(ResendState(lastAudioExists = true, resendEnabled = true, resendCooldown = false), sub)
        val back = module.write(state, ResendState())
        assertEquals(ResendState(), back.resend)
    }

    @Test
    fun `initial state is default ResendState`() {
        assertEquals(ResendState(), module.initialState())
    }

    // ────────────────────────────────────────────────────────────────
    // F-1 — NotifyManualPasteNeeded + ClearManualPasteFlag
    //
    // The flag lives on ResendState (relocated from a top-level
    // DictateUiState field per `research/manual-paste-field-architecture.md`).
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `NotifyManualPasteNeeded flips lastResultNeedsManualPaste from false to true`() {
        val state = ResendState()
        val result = module.reduce(
            state,
            Action.ResendAction.NotifyManualPasteNeeded(sessionId = "sid-1"),
            ctx(),
        )
        // B3-VAL-W1 F-14: the data shape carries both — alias Boolean
        // mirrors set-non-empty.
        assertEquals(true, result!!.nextState.lastResultNeedsManualPaste)
        assertEquals(setOf("sid-1"), result.nextState.pendingPasteSessionIds)
        assertTrue(result.sideEffects.isEmpty())
    }

    @Test
    fun `NotifyManualPasteNeeded is idempotent when sessionId already pending`() {
        val state = ResendState(
            lastResultNeedsManualPaste = true,
            pendingPasteSessionIds = setOf("sid-1"),
        )
        assertNull(
            module.reduce(
                state,
                Action.ResendAction.NotifyManualPasteNeeded(sessionId = "sid-1"),
                ctx(),
            ),
        )
    }

    @Test
    fun `NotifyManualPasteNeeded adds distinct sessionIds to the set`() {
        // B3-VAL-W1 F-14: pre-fix N-1 of N sessions were silently
        // dropped because the Boolean swallowed re-dispatch. With the
        // per-session set the second dispatch is no longer idempotent
        // — it records the additional sessionId.
        val state = ResendState(
            lastResultNeedsManualPaste = true,
            pendingPasteSessionIds = setOf("sid-1"),
        )
        val result = module.reduce(
            state,
            Action.ResendAction.NotifyManualPasteNeeded(sessionId = "sid-2"),
            ctx(),
        )
        assertEquals(setOf("sid-1", "sid-2"), result!!.nextState.pendingPasteSessionIds)
        assertEquals(true, result.nextState.lastResultNeedsManualPaste)
    }

    @Test
    fun `ClearManualPasteFlag flips lastResultNeedsManualPaste from true to false`() {
        val state = ResendState(
            lastResultNeedsManualPaste = true,
            pendingPasteSessionIds = setOf("sid-1"),
        )
        val result = module.reduce(
            state,
            Action.ResendAction.ClearManualPasteFlag,
            ctx(),
        )
        assertEquals(false, result!!.nextState.lastResultNeedsManualPaste)
        // Clear-all semantic preserved (per F-14 contract).
        assertTrue(result.nextState.pendingPasteSessionIds.isEmpty())
        assertTrue(result.sideEffects.isEmpty())
    }

    @Test
    fun `ClearManualPasteFlag is idempotent when already clear`() {
        val state = ResendState()
        assertNull(
            module.reduce(
                state,
                Action.ResendAction.ClearManualPasteFlag,
                ctx(),
            ),
        )
    }
}
