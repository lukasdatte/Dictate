@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package net.devemperor.dictate.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.put
import net.devemperor.dictate.testutil.FakePipelineSessionRepo
import net.devemperor.dictate.testutil.FakeSharedPreferences
import net.devemperor.dictate.testutil.fakeModuleServices
import net.devemperor.dictate.testutil.testPipelineRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the [DictateOrchestrator]'s `init { … }` block wiring (C7):
 *
 *  1. **`prefMirror.attach(store)` runs synchronously** before the
 *     constructor returns — verified by reading `orchestrator.state.value`
 *     immediately after construction.
 *  2. **`scope.launch { recovery.recover(store) }`** is started by the
 *     init block — verified by reading state after the test scope
 *     advances.
 *  3. **Order is PrefMirror → Recovery** — recovery does not run on a
 *     state where the pref-mirror has not applied yet.
 *
 * These align with Spec 1 §11.2.2 Block-1b Sub-Schritte 7-8 + Phase-B
 * S-1 "init-order" acceptance.
 *
 * @see net.devemperor.dictate.state.DictateOrchestrator
 */
class DictateOrchestratorInitOrderTest {

    /** Recovery that records the snapshot of state it saw when it ran. */
    private class RecordingRecoveryRepo(private val pending: List<PendingSession>) : PipelineSessionRepoSubsystem {
        @Volatile
        var stateSeenAtRecovery: DictateUiState? = null
        override suspend fun loadPending(): List<PendingSession> = pending
        override suspend fun markInserted(sessionId: String, at: Long) = Unit
        override suspend fun markFailed(sessionId: String, reason: String) = Unit
        override fun pendingFlow(): Flow<List<PendingSession>> = emptyFlow()
    }

    @Test
    fun `prefMirror attach runs synchronously inside the orchestrator constructor`() {
        // Pref written before orchestrator construction.
        val sp = FakeSharedPreferences()
        sp.edit().put(Pref.SingleRowMode, true).apply()

        val store = DictateUiStateStore(DictateUiState.initial())
        val prefMirror = PipelinePrefMirror(sp)
        // F-22 — shared fake (testutil/FakePipelineSessionRepo).
        val recovery = testPipelineRecovery(FakePipelineSessionRepo())

        // Construct the orchestrator. Pref-mirror attaches in init {}.
        DictateOrchestrator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            store = store,
            services = fakeModuleServices(),
            registry = DictateModuleRegistry(emptyList()),
            prefMirror = prefMirror,
            recovery = recovery,
        )

        // Immediately after construction — synchronous.
        assertTrue(
            "PrefMirror must attach synchronously inside orchestrator init",
            store.snapshot.layout.singleRowMode,
        )
    }

    @Test
    fun `recovery sees the pref-mirrored state, not the boot defaults`() = runTest {
        // Spec 1 §11.2.2 Block-1b sub-step 7-8 + Phase-B S-1 init-order
        // acceptance: PrefMirror.attach MUST run before recovery.recover.
        val sp = FakeSharedPreferences()
        sp.edit().put(Pref.ResendButton, true).apply()

        val store = DictateUiStateStore(DictateUiState.initial())
        val recovery = testPipelineRecovery(
            object : PipelineSessionRepoSubsystem {
                // recovery.recover() calls loadPending() and writes to store —
                // capture the snapshot it saw via state-read inside the
                // store-update closure.
                override suspend fun loadPending(): List<PendingSession> {
                    // If pref-mirror had NOT run yet, resendEnabled would be false.
                    // If it ran, resendEnabled is true. recovery.recover runs AFTER
                    // attach per the orchestrator's init contract.
                    assertTrue(
                        "Recovery must see the post-PrefMirror state",
                        store.snapshot.resend.resendEnabled,
                    )
                    return emptyList()
                }
                override suspend fun markInserted(sessionId: String, at: Long) = Unit
                override suspend fun markFailed(sessionId: String, reason: String) = Unit
                override fun pendingFlow(): Flow<List<PendingSession>> = emptyFlow()
            }
        )

        DictateOrchestrator(
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            store = store,
            services = fakeModuleServices(),
            registry = DictateModuleRegistry(emptyList()),
            prefMirror = PipelinePrefMirror(sp),
            recovery = recovery,
        )

        // Advance the unconfined scheduler so the recovery launch runs.
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `recovery writes pendingSessions into the store via scope launch`() = runTest {
        val sp = FakeSharedPreferences()
        val store = DictateUiStateStore(DictateUiState.initial())

        val pending = listOf(
            PendingSession(
                sessionId = "abc",
                status = net.devemperor.dictate.database.entity.SessionStatus.RECORDED,
                transcribedText = null,
                createdAt = 0L,
            ),
        )

        DictateOrchestrator(
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            store = store,
            services = fakeModuleServices(),
            registry = DictateModuleRegistry(emptyList()),
            prefMirror = PipelinePrefMirror(sp),
            // F-22 — shared fake (testutil/FakePipelineSessionRepo).
            recovery = testPipelineRecovery(FakePipelineSessionRepo(pending = pending)),
        )

        testScheduler.advanceUntilIdle()

        assertEquals(1, store.snapshot.pendingSessions.size)
        assertEquals("abc", store.snapshot.pendingSessions.first().sessionId)
    }

    @Test
    fun `shutdown calls prefMirror detach so post-shutdown SP changes do not mutate the store`() {
        val sp = FakeSharedPreferences()
        val store = DictateUiStateStore(DictateUiState.initial())
        val prefMirror = PipelinePrefMirror(sp)
        val orchestrator = DictateOrchestrator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            store = store,
            services = fakeModuleServices(),
            registry = DictateModuleRegistry(emptyList()),
            prefMirror = prefMirror,
            // F-22 — shared fake (testutil/FakePipelineSessionRepo).
            recovery = testPipelineRecovery(FakePipelineSessionRepo()),
        )

        // Sanity: SP write before shutdown DOES reach the store via the listener.
        sp.edit().put(Pref.Vibration, false).apply()
        assertEquals(false, store.snapshot.audio.vibrationEnabled)

        // Shutdown detaches the listener.
        orchestrator.shutdown()

        // Subsequent SP write must NOT mutate the store.
        sp.edit().put(Pref.Vibration, true).apply()
        assertEquals(false, store.snapshot.audio.vibrationEnabled)
    }

    @Test
    fun `orchestrator constructor accepts null prefMirror and null recovery for legacy tests`() {
        // Pre-C7 tests instantiate DictateOrchestrator without these
        // arguments. The C7 signature defaults both to null — verify
        // that path still works.
        val store = DictateUiStateStore(DictateUiState.initial())
        val orchestrator = DictateOrchestrator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            store = store,
            services = fakeModuleServices(),
            registry = DictateModuleRegistry(emptyList()),
            // prefMirror / recovery omitted -> default null
        )

        // Construction does not throw, and shutdown is safe to call.
        orchestrator.shutdown()
    }
}
