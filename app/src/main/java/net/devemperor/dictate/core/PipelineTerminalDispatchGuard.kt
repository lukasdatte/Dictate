package net.devemperor.dictate.core

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide once-guard enforcing **exactly one terminal dispatch
 * (`PipelineDone` / `PipelineFailed`) per sessionId per process**.
 *
 * A pipeline session ends in exactly one terminal state — it either
 * completes or fails, never both, and never twice. Three producers can
 * race to emit that terminal action into the state orchestrator, and all
 * three funnel through this single guard so the winner is unambiguous:
 *
 *  1. **Bridge delegate-delivery** — the IME-side callback delegate is
 *     bound and the pipeline completion is forwarded to it (the IME then
 *     commits text + dispatches `PipelineDone`).
 *  2. **Bridge headless fallback** — no IME delegate is bound (fresh boot
 *     → external trigger → keyboard never opened); the service dispatches
 *     `PipelineDone(committed=false)` itself so the FSM leaves Running.
 *  3. **Bind-reconciliation** (ADR-0011 Decision 2, implemented next) —
 *     on IME bind, any session that finished COMPLETED in the DB while its
 *     terminal dispatch was lost is reconciled into the FSM.
 *
 * **Invariant:** for any given `sessionId`, [tryConsume] returns `true`
 * for the first caller and `false` for every subsequent caller, across
 * all threads. The `true`-winner owns the terminal dispatch; the losers
 * MUST NOT dispatch (delivering a second terminal action would
 * double-commit text or corrupt the FSM). See ADR-0011.
 *
 * **Delegate-delivery-consumes rule:** delegate delivery consumes the
 * guard just like the headless path. Once the IME has been handed the
 * completion, no later producer (headless retry, reconciliation) may fire
 * for the same session — the IME is now the single owner of that terminal.
 *
 * **Threading:** backed by a lock-free [ConcurrentHashMap.newKeySet];
 * [tryConsume] is a single atomic `add`, safe to call from the pipeline
 * executor thread, the main thread, and IME-bind callbacks concurrently.
 *
 * **Memory:** sessions per process are few (a user dictates a handful of
 * times per keyboard session); the key-set grows by one short UUID string
 * per session and is discarded with the process. No eviction is needed at
 * this scale — if that ever changes, cap the set with an LRU wrapper.
 *
 * @see net.devemperor.dictate.core.PipelineCallbackBridge
 * @see docs/decisions/0011-pipeline-headless-completion-fallback.md
 */
class PipelineTerminalDispatchGuard {

    private val consumed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Atomically claim the terminal dispatch for [sessionId].
     *
     * @return `true` if this caller is the first (and thus the sole owner
     *   of the terminal dispatch for this session); `false` if the session
     *   was already claimed by an earlier caller — in which case the caller
     *   MUST NOT dispatch.
     */
    fun tryConsume(sessionId: String): Boolean = consumed.add(sessionId)
}
