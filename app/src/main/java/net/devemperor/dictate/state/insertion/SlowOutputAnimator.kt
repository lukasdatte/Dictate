package net.devemperor.dictate.state.insertion

import android.view.inputmethod.InputConnection

/**
 * Schedules [action] to run after [delayMs]. Abstracted from `Handler` so the
 * animator is unit-testable: the production impl wraps `mainHandler`, the test
 * impl can run actions synchronously or drive them on demand.
 */
fun interface DelayedScheduler {
    fun postDelayed(delayMs: Long, action: () -> Unit)
}

/** Per-character delay (index 1 = first scheduled tail char). */
fun interface DelayProvider {
    fun delayFor(index: Int): Long
}

/** Sink for the remainder dropped when a tail commit hits a stale IC. */
fun interface TailFailureSink {
    fun onDropped(remaining: String)
}

/**
 * Char-by-char slow-output animation, hardened against the W1 failure mode.
 *
 * The first character is committed synchronously so a dead IC is detected
 * immediately (the [commit] return gates the whole insert). The remaining
 * characters are scheduled on the [scheduler]. Unlike the pre-refactor loop —
 * which fire-and-forgot every `commitText` and silently dropped the tail when
 * the editor lost focus mid-animation — this animator **checks each tail
 * commit and aborts the rest on the first failure**, reporting the dropped
 * remainder via [onTailFailure] so the caller can react (log, retry, surface).
 *
 * IC-capture semantics are unchanged: the IC is captured once at the start and
 * reused for every scheduled character (deliberate — the captured-IC resend
 * case explicitly targets the click-time editor).
 *
 * @param scheduler     delayed-task scheduler (wraps `mainHandler`).
 * @param delayForIndex per-character delay; index 1 = first scheduled tail char.
 * @param onTailFailure invoked with the not-yet-committed remainder when a tail
 *                      character is rejected by the (now stale) IC.
 */
class SlowOutputAnimator(
    private val scheduler: DelayedScheduler,
    private val delayForIndex: DelayProvider,
    private val onTailFailure: TailFailureSink = TailFailureSink {},
) {
    /**
     * @return `true` if the first grapheme cluster committed (insert is
     *   considered started); `false` if the IC rejected the very first write.
     */
    fun run(ic: InputConnection, text: String): Boolean {
        if (text.isEmpty()) return ic.commitText(text, 1)
        // F-020: commit one whole grapheme cluster per tick, never a lone
        // surrogate — an astral emoji is a surrogate pair, a family emoji a ZWJ
        // sequence; splitting them flashes replacement glyphs (or corrupts the
        // text permanently in hosts that sanitise per commit).
        val clusters = GraphemeTextOps.graphemeClusters(text)
        if (!ic.commitText(clusters[0], 1)) return false
        scheduleFrom(ic, clusters, 1)
        return true
    }

    private fun scheduleFrom(ic: InputConnection, clusters: List<String>, index: Int) {
        if (index >= clusters.size) return
        scheduler.postDelayed(delayForIndex.delayFor(index)) {
            if (ic.commitText(clusters[index], 1)) {
                scheduleFrom(ic, clusters, index + 1)
            } else {
                // W1 fix: stop feeding a stale IC, report the dropped tail
                // instead of silently losing it cluster by cluster.
                onTailFailure.onDropped(clusters.subList(index, clusters.size).joinToString(""))
            }
        }
    }
}
