package net.devemperor.dictate.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import net.devemperor.dictate.state.PendingSession
import net.devemperor.dictate.state.PipelineSessionRepoSubsystem

/**
 * Shared test fake for [PipelineSessionRepoSubsystem] (F-22 — extracted
 * from per-test inline fakes that duplicated each other).
 *
 * **Defaults:** `loadPending()` returns the configured [pending] list
 * (default empty). `markInserted` / `markFailed` are no-ops. The
 * Flow surface is `emptyFlow()` — extend per-test by passing a
 * custom flow or by subclassing.
 *
 * **Why a single fake (not a Kotlin mock framework):** the codebase
 * uses hand-written K-1 fakes (Spec 1 quality-gate K-1 — "no Mockito
 * / mockk for state-side tests"). The constructor parameter shape
 * gives per-test override without the indirection of a mock-config
 * DSL.
 *
 * **Subclassing:** for tests that need to inject behaviour into
 * `markInserted`/`markFailed` (e.g. to record call order or to
 * throw), construct via the secondary anonymous-object form rather
 * than this class — see `DictateOrchestratorInitOrderTest` for an
 * inline-override example.
 */
class FakePipelineSessionRepo(
    private val pending: List<PendingSession> = emptyList(),
) : PipelineSessionRepoSubsystem {

    override suspend fun loadPending(): List<PendingSession> = pending

    override suspend fun markInserted(sessionId: String, at: Long) = Unit

    override suspend fun markFailed(sessionId: String, reason: String) = Unit

    override fun pendingFlow(): Flow<List<PendingSession>> = emptyFlow()

    /** Block A1 — fake returns 0 (no segments tracked). Override per-test if needed. */
    override suspend fun syncAudioFilePaths(sessionId: String): Int = 0

    /**
     * Recovery-chain (2026-05-22) — records every `createRecordingSession`
     * call as `(sessionId, audioFilePath)` so effect-handler tests can
     * assert the row-create was requested.
     */
    val createdRecordingSessions: MutableList<Pair<String, String>> = mutableListOf()

    /** Records every `transitionToRecording` call's `sessionId`. */
    val transitionedToRecording: MutableList<String> = mutableListOf()

    override suspend fun createRecordingSession(sessionId: String, audioFilePath: String) {
        createdRecordingSessions += sessionId to audioFilePath
    }

    override suspend fun transitionToRecording(sessionId: String) {
        transitionedToRecording += sessionId
    }
}
