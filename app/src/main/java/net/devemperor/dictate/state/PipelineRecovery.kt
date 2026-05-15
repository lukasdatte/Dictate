package net.devemperor.dictate.state

import android.util.Log
import kotlinx.collections.immutable.toPersistentList

/**
 * DB-replay step run at service startup.
 *
 * **What it does (Phase 1, Spec 1 §4.6):** loads the list of pending
 * sessions from the repo subsystem and writes them into
 * [DictateUiState.pendingSessions] in a single atomic store update.
 *
 * **What B3 adds:** the full recovery algorithm in Spec 1 §6.3 +
 * §11.6.2 — promoting `RECORDING → FAILED` (lost during a crash mid-
 * recording), downgrading `TRANSCRIBING → RECORDED` (the audio file
 * is still on disk; we can re-run the pipeline), filtering "ghost"
 * sessions whose `audio_file_path` no longer resolves. That work
 * requires the full DAO surface from Spec 1 §6.1 (`getSessionsByStatuses`,
 * `markLegacyAudioSessionsFailed`, `clearAudioFilePathBulk`, …) which
 * lands with the M3→M4 migration in Block 3.
 *
 * **Lifecycle:** [recover] is called from [DictateOrchestrator]'s
 * `init { … }` block via `scope.launch { recovery.recover(store) }` —
 * **after** `prefMirror.attach(store)` ran synchronously. The order
 * is part of the orchestrator constructor contract (Spec 1 §4.3) so
 * recovery sees mirrored pref values, not `DictateUiState.initial()`
 * defaults.
 *
 * **Why a class with a single suspend function (not a top-level
 * `suspend fun`)?** Future B3 expansion will add private helpers
 * (status promotion, audio-file existence checks, etc.) and a class
 * keeps them naturally scoped. Tests inject a [PipelineSessionRepoSubsystem]
 * fake via the constructor.
 *
 * @property sessionRepo the source-of-pending-sessions subsystem
 *   (see [ModuleServices.sessionRepo]).
 *
 * @see net.devemperor.dictate.state.DictateOrchestrator
 * @see net.devemperor.dictate.state.PipelinePrefMirror
 * @see docs/decisions/0001-state-modular-orchestrator-pattern.md §"Required mechanics" item 8
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §4.6 §6.3 §11.6
 */
class PipelineRecovery(
    private val sessionRepo: PipelineSessionRepoSubsystem,
) {
    /**
     * Atomically replace [DictateUiState.pendingSessions] with the
     * sessions currently in the repo. Idempotent — re-running [recover]
     * overwrites with the same list.
     *
     * **Suspending** because [PipelineSessionRepoSubsystem.loadPending]
     * dispatches to `Dispatchers.IO` internally (B3 implementation).
     * The single `store.update` write happens on the resuming dispatcher
     * (typically Main.immediate per [ModuleServices.scope]) so
     * subscribers see the change on the main thread without an extra
     * coroutine hop.
     *
     * **F-6 (2026-05-15) — try/catch around the IO call.** The host
     * service launches this on a `SupervisorJob` with no
     * `CoroutineExceptionHandler`; without a guard, an `SQLiteException`
     * or `IOException` from `loadPending()` would be silently swallowed,
     * leaving `pendingSessions` empty with no diagnostic. The catch
     * logs at `Log.e` so the failure surfaces in logcat. The store
     * stays unchanged on failure — Phase 1 acceptable (the user sees
     * an empty pending-list, which is the same UX as "no pending
     * sessions"). B3 may extend the catch to emit a structured
     * failure action for UI signalling.
     */
    suspend fun recover(store: DictateUiStateStore) {
        try {
            val pending = sessionRepo.loadPending()
            store.update { it.copy(pendingSessions = pending.toPersistentList()) }
        } catch (t: Throwable) {
            Log.e(TAG, "Recovery failed — loadPending() threw", t)
        }
    }

    private companion object {
        private const val TAG = "PipelineRecovery"
    }
}
