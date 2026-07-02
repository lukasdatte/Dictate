package net.devemperor.dictate.state

import java.io.File
import net.devemperor.dictate.BuildConfig
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import net.devemperor.dictate.testutil.FakeSharedPreferences
import net.devemperor.dictate.testutil.fakeModuleServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-reducer + effect + cross-module-cascade tests for [InfoHintModule]
 * (ADR-0006 completion, research `2026-07-02 - infobar-consolidation.md`).
 *
 * Coverage:
 * - PipelineErrorOccurred stamps `occurredAt` from ctx.now + replaces older errors
 * - Confirm/DismissPipelineError clear the hint; stale clicks reduce to null
 * - ShowEngagementHint sets the single slot (idempotent)
 * - Confirm/DismissEngagementHint clear + emit the legacy-parity persistence effects
 * - ClearTransientHints wipes everything in-RAM, no effects, null when already empty
 * - runEffect writes the matching `Pref.*` flags
 * - Cascade: recording start / pipeline start / IME-hide-while-idle → ClearTransientHints
 */
class InfoHintModuleTest {

    private val module = InfoHintModule
    private fun ctx(now: Long = 42_000L) =
        ReducerContext(global = DictateUiState.initial(), now = now)

    private val someError = PipelineErrorHint(
        kind = PipelineErrorKind.INTERNET_ERROR,
        providerKey = null,
        occurredAt = 1_000L,
    )

    // ─── Pipeline-error arms ────────────────────────────────────────────

    @Test
    fun `PipelineErrorOccurred stores hint with occurredAt from ctx now`() {
        val result = module.reduce(
            InfoHintState(),
            Action.InfoHintAction.PipelineErrorOccurred(
                PipelineErrorKind.QUOTA_EXCEEDED, providerKey = "OPENAI",
            ),
            ctx(now = 99_000L),
        )
        assertNotNull(result)
        assertEquals(
            PipelineErrorHint(PipelineErrorKind.QUOTA_EXCEEDED, "OPENAI", 99_000L),
            result!!.nextState.pipelineError,
        )
        assertTrue("pure state write — no effects", result.sideEffects.isEmpty())
    }

    @Test
    fun `PipelineErrorOccurred replaces an older error`() {
        val result = module.reduce(
            InfoHintState(pipelineError = someError),
            Action.InfoHintAction.PipelineErrorOccurred(
                PipelineErrorKind.INVALID_API_KEY, providerKey = null,
            ),
            ctx(now = 2_000L),
        )
        assertEquals(PipelineErrorKind.INVALID_API_KEY, result!!.nextState.pipelineError?.kind)
    }

    @Test
    fun `DismissPipelineError clears the hint without effects`() {
        val result = module.reduce(
            InfoHintState(pipelineError = someError),
            Action.InfoHintAction.DismissPipelineError,
            ctx(),
        )
        assertNull(result!!.nextState.pipelineError)
        assertTrue(result.sideEffects.isEmpty())
    }

    @Test
    fun `ConfirmPipelineError clears the hint (launch is the IME side-channel)`() {
        val result = module.reduce(
            InfoHintState(pipelineError = someError),
            Action.InfoHintAction.ConfirmPipelineError(
                PipelineErrorKind.INTERNET_ERROR, providerKey = null,
            ),
            ctx(),
        )
        assertNull(result!!.nextState.pipelineError)
        assertTrue(result.sideEffects.isEmpty())
    }

    @Test
    fun `stale pipeline-error dismiss reduces to null`() {
        assertNull(
            module.reduce(InfoHintState(), Action.InfoHintAction.DismissPipelineError, ctx()),
        )
    }

    // ─── Engagement-hint arms ───────────────────────────────────────────

    @Test
    fun `ShowEngagementHint sets the slot`() {
        val result = module.reduce(
            InfoHintState(),
            Action.InfoHintAction.ShowEngagementHint(EngagementHint.RATE),
            ctx(),
        )
        assertEquals(EngagementHint.RATE, result!!.nextState.engagementHint)
        assertTrue(result.sideEffects.isEmpty())
    }

    @Test
    fun `ShowEngagementHint is idempotent — same hint reduces to null`() {
        assertNull(
            module.reduce(
                InfoHintState(engagementHint = EngagementHint.UPDATE),
                Action.InfoHintAction.ShowEngagementHint(EngagementHint.UPDATE),
                ctx(),
            ),
        )
    }

    @Test
    fun `ConfirmEngagementHint UPDATE clears without persistence (legacy parity)`() {
        // Legacy behaviour: Update-"Yes" opened the settings but did NOT
        // write Pref.LastVersionCode — only "No" did. The hint therefore
        // re-fires on the next keyboard-open until explicitly declined.
        val result = module.reduce(
            InfoHintState(engagementHint = EngagementHint.UPDATE),
            Action.InfoHintAction.ConfirmEngagementHint(EngagementHint.UPDATE),
            ctx(),
        )
        assertNull(result!!.nextState.engagementHint)
        assertTrue("Update-confirm persists nothing", result.sideEffects.isEmpty())
    }

    @Test
    fun `DismissEngagementHint UPDATE clears + persists the seen version code`() {
        val result = module.reduce(
            InfoHintState(engagementHint = EngagementHint.UPDATE),
            Action.InfoHintAction.DismissEngagementHint(EngagementHint.UPDATE),
            ctx(),
        )
        assertNull(result!!.nextState.engagementHint)
        assertEquals(
            listOf<InfoHintModule.Effect>(InfoHintModule.Effect.PersistSeenVersionCode),
            result.sideEffects,
        )
    }

    @Test
    fun `RATE confirm and dismiss both persist the rated flag`() {
        for (action in listOf<Action.InfoHintAction>(
            Action.InfoHintAction.ConfirmEngagementHint(EngagementHint.RATE),
            Action.InfoHintAction.DismissEngagementHint(EngagementHint.RATE),
        )) {
            val result = module.reduce(
                InfoHintState(engagementHint = EngagementHint.RATE), action, ctx(),
            )
            assertNull(result!!.nextState.engagementHint)
            assertEquals(
                "$action must persist the rated flag",
                listOf<InfoHintModule.Effect>(InfoHintModule.Effect.PersistRatedFlag),
                result.sideEffects,
            )
        }
    }

    @Test
    fun `DONATE confirm and dismiss both persist the donated flags`() {
        for (action in listOf<Action.InfoHintAction>(
            Action.InfoHintAction.ConfirmEngagementHint(EngagementHint.DONATE),
            Action.InfoHintAction.DismissEngagementHint(EngagementHint.DONATE),
        )) {
            val result = module.reduce(
                InfoHintState(engagementHint = EngagementHint.DONATE), action, ctx(),
            )
            assertNull(result!!.nextState.engagementHint)
            assertEquals(
                listOf<InfoHintModule.Effect>(InfoHintModule.Effect.PersistDonatedFlags),
                result.sideEffects,
            )
        }
    }

    @Test
    fun `mismatched engagement dismiss reduces to null (stale click)`() {
        assertNull(
            module.reduce(
                InfoHintState(engagementHint = EngagementHint.RATE),
                Action.InfoHintAction.DismissEngagementHint(EngagementHint.DONATE),
                ctx(),
            ),
        )
    }

    // ─── Cancellation notice (R5, ADR-0009 / spec §3.6) ─────────────────

    @Test
    fun `PipelineCancelled stores the hint with occurredAt from ctx now`() {
        val result = module.reduce(
            InfoHintState(),
            Action.InfoHintAction.PipelineCancelled,
            ctx(now = 7_000L),
        )
        assertEquals(CancellationHint(occurredAt = 7_000L), result!!.nextState.cancellation)
        assertTrue(result.sideEffects.isEmpty())
    }

    @Test
    fun `PipelineCancelled replaces an older notice`() {
        val result = module.reduce(
            InfoHintState(cancellation = CancellationHint(occurredAt = 1_000L)),
            Action.InfoHintAction.PipelineCancelled,
            ctx(now = 9_000L),
        )
        assertEquals(CancellationHint(occurredAt = 9_000L), result!!.nextState.cancellation)
    }

    @Test
    fun `DismissCancellationHint clears the notice without effects`() {
        val result = module.reduce(
            InfoHintState(cancellation = CancellationHint(occurredAt = 1_000L)),
            Action.InfoHintAction.DismissCancellationHint,
            ctx(),
        )
        assertNull(result!!.nextState.cancellation)
        assertTrue(result.sideEffects.isEmpty())
    }

    @Test
    fun `stale cancellation dismiss reduces to null`() {
        assertNull(
            module.reduce(InfoHintState(), Action.InfoHintAction.DismissCancellationHint, ctx()),
        )
    }

    // ─── ClearTransientHints ────────────────────────────────────────────

    @Test
    fun `ClearTransientHints wipes both hints without effects`() {
        val result = module.reduce(
            InfoHintState(pipelineError = someError, engagementHint = EngagementHint.RATE),
            Action.InfoHintAction.ClearTransientHints,
            ctx(),
        )
        assertEquals(InfoHintState(), result!!.nextState)
        assertTrue("in-RAM clear only — no pref writes", result.sideEffects.isEmpty())
    }

    @Test
    fun `ClearTransientHints wipes the cancellation notice too`() {
        // R5: the notice is transient like every other hint — a new
        // recording / pipeline start or IME-hide-while-idle clears it via
        // the module's cross-clear cascade (which dispatches this action).
        val result = module.reduce(
            InfoHintState(cancellation = CancellationHint(occurredAt = 1_000L)),
            Action.InfoHintAction.ClearTransientHints,
            ctx(),
        )
        assertEquals(InfoHintState(), result!!.nextState)
    }

    @Test
    fun `ClearTransientHints on empty state reduces to null`() {
        assertNull(
            module.reduce(InfoHintState(), Action.InfoHintAction.ClearTransientHints, ctx()),
        )
    }

    // ─── Effects (pref persistence) ─────────────────────────────────────

    @Test
    fun `PersistSeenVersionCode writes the current version code`() {
        val prefs = FakeSharedPreferences()
        module.runEffect(
            InfoHintModule.Effect.PersistSeenVersionCode,
            fakeModuleServices(sharedPrefs = prefs),
        )
        assertEquals(BuildConfig.VERSION_CODE, prefs.get(Pref.LastVersionCode))
    }

    @Test
    fun `PersistRatedFlag writes only the rated flag`() {
        val prefs = FakeSharedPreferences()
        module.runEffect(
            InfoHintModule.Effect.PersistRatedFlag,
            fakeModuleServices(sharedPrefs = prefs),
        )
        assertTrue(prefs.get(Pref.FlagHasRated))
        assertFalse(prefs.get(Pref.FlagHasDonated))
    }

    @Test
    fun `PersistDonatedFlags writes donated AND rated (legacy parity)`() {
        val prefs = FakeSharedPreferences()
        module.runEffect(
            InfoHintModule.Effect.PersistDonatedFlags,
            fakeModuleServices(sharedPrefs = prefs),
        )
        assertTrue(prefs.get(Pref.FlagHasDonated))
        assertTrue(prefs.get(Pref.FlagHasRated))
    }

    // ─── Cross-module cascade (the legacy dismiss-site replacement) ─────

    private fun stateWithHints(): DictateUiState = DictateUiState.initial().copy(
        infoHints = InfoHintState(pipelineError = someError),
    )

    @Test
    fun `cascade clears hints when a recording starts`() {
        val prev = stateWithHints()
        val next = prev.copy(
            recording = RecordingState.Preparing(
                useBluetooth = false, audioFile = File("/tmp/a.m4a"), sessionId = "s1",
            ),
        )
        assertEquals(
            listOf<Action>(Action.InfoHintAction.ClearTransientHints),
            module.onCrossModuleStateChange(prev, next),
        )
    }

    @Test
    fun `cascade clears hints when a pipeline run starts`() {
        val prev = stateWithHints()
        val next = prev.copy(pipeline = PipelineUiState.Preparing(sessionId = "s1"))
        assertEquals(
            listOf<Action>(Action.InfoHintAction.ClearTransientHints),
            module.onCrossModuleStateChange(prev, next),
        )
    }

    @Test
    fun `cascade clears hints on IME-hide while everything is idle`() {
        // Mirrors the legacy onFinishInputView "State (C): Idle -> full
        // cleanup" dismiss branch.
        val prev = stateWithHints()  // imeViewVisible = true in initial()
        val next = prev.copy(imeViewVisible = false)
        assertEquals(
            listOf<Action>(Action.InfoHintAction.ClearTransientHints),
            module.onCrossModuleStateChange(prev, next),
        )
    }

    @Test
    fun `cascade does NOT clear on IME-hide while recording is live`() {
        // Legacy states (A)/(B) of the onFinishInputView branch kept the
        // bar alive when recording / pipeline continued in the background.
        val prev = stateWithHints().copy(
            recording = RecordingState.Active(
                useBluetooth = false, audioFile = File("/tmp/a.m4a"), sessionId = "s1",
            ),
        )
        val next = prev.copy(imeViewVisible = false)
        assertTrue(module.onCrossModuleStateChange(prev, next).isEmpty())
    }

    @Test
    fun `cascade is silent when no hints exist`() {
        val prev = DictateUiState.initial()
        val next = prev.copy(pipeline = PipelineUiState.Preparing(sessionId = "s1"))
        assertTrue(
            "no hints to clear — no cascade noise",
            module.onCrossModuleStateChange(prev, next).isEmpty(),
        )
    }

    @Test
    fun `cascade is silent when the error arrives AFTER the pipeline ended`() {
        // onPipelineError order: PipelineFailed (pipeline -> Idle) is
        // dispatched BEFORE PipelineErrorOccurred. The active -> Idle
        // transition must not clear the freshly-set error.
        val prev = stateWithHints().copy(
            pipeline = PipelineUiState.Running(sessionId = "s1", target = InsertionTarget.INPUT_CONNECTION),
        )
        val next = prev.copy(pipeline = PipelineUiState.Idle)
        assertTrue(module.onCrossModuleStateChange(prev, next).isEmpty())
    }

    // ─── Module plumbing ────────────────────────────────────────────────

    @Test
    fun `module id is InfoHint and lens round-trips`() {
        assertEquals(ModuleId.InfoHint, module.id)
        val global = DictateUiState.initial()
        val sub = InfoHintState(engagementHint = EngagementHint.DONATE)
        assertEquals(sub, module.read(module.write(global, sub)))
    }
}
