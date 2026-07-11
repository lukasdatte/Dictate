package net.devemperor.dictate.state.render.overlay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewConfiguration
import net.devemperor.dictate.core.BackspaceDeleteSpeedCurve

/**
 * Abstraction over `Handler.postDelayed` / `removeCallbacks` so the
 * repeat state machine in [OverlayDeleteRepeatController] is JVM-testable
 * without a `Looper` (P5, widget-mode-parity-and-third-row spec §Repeat
 * delete).
 *
 * A JVM unit test injects a fake that records the posted `(delay, action)`
 * pairs and fires them on demand under a controllable clock; production
 * wires [HandlerRepeatScheduler].
 */
interface RepeatScheduler {
    /** Schedule [action] to run after [delayMs]. */
    fun postDelayed(delayMs: Long, action: () -> Unit)

    /** Drop every not-yet-fired scheduled action. */
    fun cancelAll()
}

/**
 * Production [RepeatScheduler] backed by a **dedicated** [Handler].
 *
 * The handler must be private to one controller: [cancelAll] calls
 * `removeCallbacksAndMessages(null)`, which clears every message whose
 * target is *this* handler instance. A fresh `Handler(Looper)` per
 * controller keeps that blast radius to the controller's own repeat
 * ticks, so cancelling one overlay's delete-repeat never disturbs other
 * work on the same looper.
 */
class HandlerRepeatScheduler(
    private val handler: Handler,
) : RepeatScheduler {
    override fun postDelayed(delayMs: Long, action: () -> Unit) {
        handler.postDelayed({ action() }, delayMs)
    }

    override fun cancelAll() {
        handler.removeCallbacksAndMessages(null)
    }
}

/**
 * Repeat-policy state machine for the overlay-widget Delete button's
 * press-and-hold continuous delete (P5).
 *
 * # Why a dedicated policy class (SRP + testability)
 *
 * The IME keyboard already has a continuous-backspace behaviour
 * (`DictateInputMethodService.onBackspaceLongClicked`), but that loop is
 * bound to a concrete keyboard `View` and its own `Handler`/`isDeleting`
 * fields — not reusable from the overlay backend. What *is* reusable, and
 * is reused here, is the pure-Kotlin acceleration curve
 * [BackspaceDeleteSpeedCurve] (50→25→10→5 ms), so the keyboard and the
 * widget share **one** acceleration definition (DRY).
 *
 * This class owns only the *timing + phase* policy; the View/`Handler`
 * wiring stays thin in [OverlayBackend] (a touch listener on the delete
 * button that forwards [onPress] / [onRelease] / [cancel]). Keeping the
 * policy free of `MotionEvent` / `View` lets a JVM test pin the exact
 * tick cadence and every stop path without Robolectric.
 *
 * # Phases
 *
 * ```
 *  IDLE ──onPress()──▶ PENDING ──(long-press timeout)──▶ REPEATING
 *   ▲                    │                                   │
 *   │        onRelease()/cancel()                  onRelease()/cancel()
 *   └────────────────────┴───────────────────────────────────┘
 * ```
 *
 * - **PENDING** — the finger is down but the long-press threshold has not
 *   elapsed. A release here is a *short tap*: [onRelease] returns `false`
 *   so the backend lets the normal click fire a single delete.
 * - **REPEATING** — the long-press fired; each tick deletes one character
 *   and re-schedules itself at the [BackspaceDeleteSpeedCurve] delay for
 *   the elapsed hold time. A release here returns `true` so the backend
 *   suppresses the trailing click (the deletes already happened).
 *
 * # Drag / teardown coexistence (spec §Repeat delete)
 *
 * The controller has no notion of dragging — the backend maps a drag
 * steal (the `DraggableOverlayLayout` intercepts a move past its
 * threshold, delivering `ACTION_CANCEL` to the button) and an overlay
 * teardown both onto [cancel], which returns to IDLE and drops the
 * scheduled tick. A still press never becomes a drag (drag needs motion),
 * and a moved press cancels the repeat before it can — so the two
 * gestures are mutually exclusive by construction.
 *
 * Every scheduled callback re-checks the phase before acting, so a stale
 * callback that slips past [RepeatScheduler.cancelAll] (defence in depth)
 * is a no-op.
 *
 * @property scheduler post/cancel indirection (fake in tests).
 * @property clock monotonic millis source (`SystemClock.uptimeMillis` in
 *   production) — read at repeat start and each tick to drive the curve.
 * @property longPressTimeoutMs delay before a held press promotes to a
 *   repeat (production: `ViewConfiguration.getLongPressTimeout()`).
 * @property onDelete dispatch one Backspace — the backend routes this
 *   through the same slot-action → `onAction` path a tap uses.
 * @property onAdvance optional hook fired when the cascade steps to a
 *   faster delay (haptic tick parity with the keyboard); default no-op.
 *
 * @see net.devemperor.dictate.core.BackspaceDeleteSpeedCurve
 * @see OverlayBackend
 * @see docs/research/2026-07-11 - widget-mode-parity-and-third-row.md
 */
class OverlayDeleteRepeatController(
    private val scheduler: RepeatScheduler,
    private val clock: () -> Long,
    private val longPressTimeoutMs: Long,
    private val onDelete: () -> Unit,
    private val onAdvance: () -> Unit = {},
) {

    private enum class Phase { IDLE, PENDING, REPEATING }

    private var phase: Phase = Phase.IDLE
    private var repeatStartMs: Long = 0L
    private var currentDelayMs: Int = BackspaceDeleteSpeedCurve.INITIAL_DELAY_MS

    /** `true` while the accelerating delete loop is active. */
    fun isRepeating(): Boolean = phase == Phase.REPEATING

    /**
     * Pointer down on the delete button: arm the long-press timer. A
     * second [onPress] without an intervening stop is ignored so a
     * spurious re-entry cannot start two loops.
     */
    fun onPress() {
        if (phase != Phase.IDLE) return
        phase = Phase.PENDING
        scheduler.postDelayed(longPressTimeoutMs) { beginRepeat() }
    }

    /**
     * Pointer up. Returns `true` iff a repeat *was* running — the backend
     * uses that to decide whether to suppress the trailing single-tap
     * click (repeating ⇒ suppress; short tap ⇒ let the click delete once).
     * Always returns to IDLE and drops any scheduled callback.
     */
    fun onRelease(): Boolean {
        val wasRepeating = phase == Phase.REPEATING
        stop()
        return wasRepeating
    }

    /**
     * Abort with no tap semantics — used for a drag steal
     * (`ACTION_CANCEL`) and for overlay teardown. Idempotent.
     */
    fun cancel() = stop()

    private fun stop() {
        phase = Phase.IDLE
        scheduler.cancelAll()
    }

    private fun beginRepeat() {
        if (phase != Phase.PENDING) return
        phase = Phase.REPEATING
        repeatStartMs = clock()
        currentDelayMs = BackspaceDeleteSpeedCurve.INITIAL_DELAY_MS
        tick()
    }

    private fun tick() {
        if (phase != Phase.REPEATING) return
        onDelete()
        val elapsed = clock() - repeatStartMs
        val step = BackspaceDeleteSpeedCurve.nextDelay(elapsed, currentDelayMs)
        if (step.advanced) {
            currentDelayMs = step.nextDelayMs
            onAdvance()
        }
        scheduler.postDelayed(currentDelayMs.toLong()) { tick() }
    }

    companion object {
        /**
         * Build a controller wired to the main looper with the platform
         * long-press timeout and `SystemClock.uptimeMillis` — the
         * production construction site used by [OverlayBackend].
         */
        fun forMainLooper(
            onDelete: () -> Unit,
            onAdvance: () -> Unit = {},
        ): OverlayDeleteRepeatController = OverlayDeleteRepeatController(
            scheduler = HandlerRepeatScheduler(Handler(Looper.getMainLooper())),
            clock = { SystemClock.uptimeMillis() },
            longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong(),
            onDelete = onDelete,
            onAdvance = onAdvance,
        )
    }
}
