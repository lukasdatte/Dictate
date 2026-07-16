package net.devemperor.dictate.windows

import net.devemperor.dictate.preferences.WindowsTarget
import net.devemperor.dictate.shared.client.DispatchResult
import net.devemperor.dictate.shared.protocol.DispatchRequest
import net.devemperor.dictate.shared.protocol.SessionOriginWire
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.PipelineErrorKind
import java.util.concurrent.Executor

/**
 * THE shared Windows-dispatch primitive (ADR-0019). Called from BOTH terminal producers:
 *
 *   (a) the IME seam — `onPipelineCompleted`'s else-branch, and the history-panel row button;
 *   (b) the headless sink — `DictatePipelineService`'s ADR-0011 terminal sink, when no IME delegate
 *       is bound.
 *
 * Both call [dispatch]. Neither owns an HTTP client, an executor, or a result-handling branch of
 * its own — duplicating any of that is how the two paths would silently drift apart (forbidden by
 * ADR-0019). There is physically one instance, built in the service and passed to the IME.
 *
 * **OWNED BY THE SERVICE, not the IME.** The FGS outlives every IME view, so an in-flight dispatch
 * survives the keyboard being dismissed — the teardown cascade then only has to make the text
 * recoverable, not abort the send.
 *
 * **THREADING:** [dispatch] returns immediately. The blocking HTTP call runs on [executor] (a
 * dedicated executor — NOT the ADR-0009 JobExecutor: a dispatch is not a pipeline job and must not
 * occupy its serialized slot). Results come back through [emitAction], which is main-confined — the
 * same sink the headless terminal fallback already uses, so it works identically with and without a
 * bound IME.
 *
 * @property service the pure, blocking send logic.
 * @property targetProvider reads the paired target from the prefs LAZILY (null = not paired).
 * @property emitAction the orchestrator's main-confined action sink.
 * @property audit records the `insertion_method = WINDOWS_DISPATCH` audit row (one audit authority,
 *   three triggers).
 * @property executor the dedicated dispatch executor.
 */
class WindowsDispatchCoordinator(
    private val service: WindowsDispatchService,
    private val targetProvider: () -> WindowsTarget?,
    private val emitAction: (Action) -> Unit,
    private val audit: (sessionId: String, text: String, deviceId: String) -> Unit,
    private val executor: Executor,
) {

    /**
     * Fire-and-forget. Emits Started → (HTTP on [executor]) → Succeeded | Failed.
     *
     * @param acknowledgeOnSuccess true for a fresh terminal session and for an uninserted history
     *   row; false for a re-send of an already-acknowledged row (a pure re-send must not touch
     *   `inserted_at`).
     * @param surfacedAsPending true when a pending part for this session ALREADY exists (a re-sent
     *   still-pending history row). The reducer's Succeeded arm then needs no cross-axis read.
     * @param suppressPendingFallback true in the PC-only terminal mode (pc-dictation-activity): a
     *   failure must NOT surface a local pending part (there is no IME host). Threaded onto the
     *   in-flight axis so the reducer's Failed arm honours it. Defaults to `false` — the persistent
     *   auto-send producers keep the ADR-0011 pending fallback.
     */
    @JvmOverloads
    fun dispatch(
        sessionId: String,
        text: String,
        createdAt: Long,
        origin: SessionOriginWire,
        acknowledgeOnSuccess: Boolean,
        surfacedAsPending: Boolean = false,
        suppressPendingFallback: Boolean = false,
    ) {
        val target = targetProvider() ?: run {
            // Not paired — treat exactly like a rejected secret (both end in the pending-part
            // fallback and the "pair again" InfoBar).
            emitAction(Action.WindowsDispatchAction.Failed(sessionId, PipelineErrorKind.WINDOWS_UNAUTHORIZED))
            return
        }
        emitAction(
            Action.WindowsDispatchAction.Started(
                sessionId = sessionId,
                text = text,
                createdAt = createdAt,
                acknowledgeOnSuccess = acknowledgeOnSuccess,
                surfacedAsPending = surfacedAsPending,
                suppressPendingFallback = suppressPendingFallback,
            ),
        )
        executor.execute {
            val result = service.send(
                target,
                DispatchRequest(sessionId = sessionId, text = text, createdAt = createdAt, origin = origin),
            )
            when (result) {
                is DispatchResult.Success -> {
                    audit(sessionId, text, target.deviceId) // insertion_method = WINDOWS_DISPATCH
                    emitAction(Action.WindowsDispatchAction.Succeeded(sessionId, result.value.outcome))
                    executor.execute { service.sync(target) } // fire & forget (ADR-0020)
                }
                is DispatchResult.Failure ->
                    emitAction(
                        Action.WindowsDispatchAction.Failed(sessionId, DispatchOutcomeMapper.toErrorKind(result.error)),
                    )
            }
        }
    }
}
