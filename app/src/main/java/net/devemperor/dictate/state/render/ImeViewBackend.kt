package net.devemperor.dictate.state.render

import android.content.Context
import android.view.View
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.ModuleServices
import net.devemperor.dictate.state.layout.BackendType
import net.devemperor.dictate.state.layout.ButtonSlot
import net.devemperor.dictate.state.layout.LayoutMode
import net.devemperor.dictate.state.layout.LogicalButtonId
import net.devemperor.dictate.state.layout.RenderBackend

/**
 * RenderBackend implementation for the IME-View (KEYBOARD ViewMode).
 *
 * Consumes the [BackendType.IME_VIEW] subset of layout modes — i.e. all
 * five KEYBOARD_*_STATE modes in [LayoutCatalog]. The companion overlay
 * surface ([BackendType.OVERLAY_WINDOW]) is served by `OverlayBackend`
 * in Spec 3 / B5.
 *
 * # Render-loop contract (Spec 2 §6)
 *
 * Per emitted [DictateUiState]:
 *
 *  1. Drive the `MotionSurface` (i.e. the `MotionLayout`) to the
 *     `mode.sceneStateId` — first render or animations-off via
 *     [MotionSurface.jumpToState], otherwise [MotionSurface.transitionToState].
 *     R.14 `firstRender`-flag (Issue 2.1.18) prevents the 250 ms
 *     initial-state animation after each (re-)inflate.
 *  2. Walk every slot in `mode.rows` and call [applySlotToView] —
 *     visibility / icon / text / enabled / alpha all derived from the
 *     resolvers. Missing view-mapping → `error(...)` per the
 *     Silent-Skip-Guard (Issue 3.0.12).
 *  3. Fan into [RecordingAnimationController] for the BorderGlow + Pulse
 *     animation transitions (these are stateful and live outside the
 *     pure-resolver model — Spec 2 §11.5).
 *
 * # Click-listener single-wire (L8, forbidden pattern (l))
 *
 * [wireStaticHandlers] runs **exactly once** per [attach]. Each click
 * lambda reads [stateRef] and [modeRef] **at click-time** — never the
 * snapshot captured at wiring-time. That gives us:
 *
 *  - One lambda allocation per button per backend lifetime (no
 *    GC churn at 100 ms render-ticks).
 *  - Click always sees the latest state (no Pipeline-tick race).
 *  - `actionResolver` returning `null` is a silent no-op (R.3 nullable
 *    contract) — verified via `?.let { onAction?.invoke(it) }`.
 *
 * # ModuleServices dependency (Phase-B S-7)
 *
 * Pre-Dispatch-Allocation (R.2) requires the click-handler to call
 * `services.audioFileFactory.allocate()` before
 * `Action.RecordingAction.StartRecording`. Resolvers carry the
 * `(state, services) -> Action?` signature; the backend holds the
 * `services` reference and threads it into the resolver call.
 *
 * @property motionSurface the `MotionLayout` indirection — see
 *   [MotionSurface] for the abstraction's rationale.
 * @property buttonViews `LogicalButtonId` → concrete `View` map.
 *   A slot in the active [LayoutMode] whose id is missing from this
 *   map raises an `IllegalStateException` (Silent-Skip-Guard).
 * @property ctx Android context (for `ContextCompat.getDrawable` in
 *   the slot apply path).
 * @property services dependency-injection container (Pre-Dispatch
 *   allocator + toast sink + other subsystems).
 * @property recordingAnimationController the animator-bridge driving
 *   BorderGlow + PulseLayout from `state.recording` transitions.
 * @property staticHandlerInstaller optional hook executed once per
 *   [attach] **after** standard click-listener wiring — used by the
 *   IME service to bolt on special-touch handlers (CursorSwipe /
 *   Backspace-Swipe / Enter-Overlay, Spec 2 §11.7) without making the
 *   backend depend on them directly. Default no-op so JVM unit-tests
 *   don't have to provide it.
 * @property onVibrate optional haptic feedback callback fired on every
 *   click. Defaults to no-op for tests.
 *
 * @see net.devemperor.dictate.state.layout.RenderBackend
 * @see net.devemperor.dictate.state.render.RecordingAnimationController
 * @see net.devemperor.dictate.state.render.MotionSurface
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §6
 * @see docs/decisions/0004-ui-layout-catalog-motionlayout.md §3
 */
class ImeViewBackend(
    private val motionSurface: MotionSurface,
    private val buttonViews: Map<LogicalButtonId, View>,
    private val ctx: Context,
    private val services: ModuleServices,
    private val recordingAnimationController: RecordingAnimationController? = null,
    private val staticHandlerInstaller: ((Map<LogicalButtonId, View>) -> Unit)? = null,
    private val onVibrate: () -> Unit = {},
) : RenderBackend {

    override val backendType: BackendType = BackendType.IME_VIEW

    // ─── Backend lifecycle fields ─────────────────────────────────────

    /**
     * Aktueller state-snapshot — read by every click listener at click
     * time. `null` outside an attach/detach interval (defensive — a
     * click on a stale view short-circuits silently).
     */
    private var stateRef: DictateUiState? = null

    /**
     * Active [LayoutMode] — used by [currentSlot] to look up the slot
     * whose `actionResolver` should fire on click.
     */
    private var modeRef: LayoutMode? = null

    /**
     * The orchestrator-bound click sink. `null` before [attach] and
     * after [detach]; resolver-emitted actions short-circuit when
     * `null`.
     */
    private var onAction: ((Action) -> Unit)? = null

    /**
     * First-render flag (R.14 / Issue 2.1.18). MotionLayout always
     * boots into the first declared ConstraintSet — the user would see
     * a 250 ms animation to the actual mode on every (re-)inflate
     * without [MotionSurface.jumpToState]. Reset on [detach] so a
     * view-recreate re-arms the snap.
     */
    private var firstRender: Boolean = true

    // ─── RenderBackend implementation ────────────────────────────────

    override fun attach(onAction: (Action) -> Unit) {
        this.onAction = onAction
        wireStaticHandlers()
        staticHandlerInstaller?.invoke(buttonViews)
    }

    override fun detach() {
        onAction = null
        stateRef = null
        modeRef = null
        firstRender = true
        recordingAnimationController?.reset()
        // Click-listeners stay wired on the Views — they short-circuit
        // because `onAction == null` and `stateRef == null`. The Views
        // themselves are released by the IME service's onCreateInputView
        // teardown.
    }

    override fun render(state: DictateUiState, mode: LayoutMode) {
        require(mode.backend == BackendType.IME_VIEW) {
            "ImeViewBackend received a non-IME_VIEW mode: ${mode.id} (backend=${mode.backend})"
        }
        stateRef = state
        modeRef = mode

        // 1 — MotionLayout transition. Scene-id comes from the layout
        //     mode (R.12 / OCP). A null scene-id is legal (e.g. tests
        //     using a mode with no MotionScene binding) and skips this
        //     step entirely.
        mode.sceneStateId?.let { sceneId ->
            if (firstRender || !state.layout.animationsEnabled) {
                motionSurface.jumpToState(sceneId)
            } else {
                motionSurface.transitionToState(sceneId)
            }
        }
        firstRender = false

        // 2 — Per-slot apply via the shared helper (Spec 2 §5.1 / F-7).
        //     A slot in `mode.rows` whose `logicalId` is missing from
        //     `buttonViews` raises `error(...)` — Silent-Skip-Guard
        //     (Issue 3.0.12). Render-time crashes are preferable to
        //     UI-correctness drift from new button ids the layout XML
        //     hasn't caught up with.
        mode.rows.flatMap { it.slots }.forEach { slot ->
            val view = buttonViews[slot.logicalId]
                ?: error(
                    "No view registered for ${slot.logicalId} in " +
                        "ImeViewBackend.buttonViews (mode=${mode.id})"
                )
            applySlotToView(slot, view, state, ctx)
        }

        // 3 — Forward the recording-state transition into the
        //     animation controller (Spec 2 §11.5). The controller is
        //     idempotent — only the class transition triggers work.
        recordingAnimationController?.onState(state)
    }

    // ─── Public hooks (forwarded by the service) ──────────────────────

    /**
     * Side-channel amplitude tick. Not part of [DictateUiState] (Spec 2
     * §11.5) — the IME service forwards from the
     * `services.amplitudeStream` SCO/timer side-flow.
     */
    fun onAmplitude(level: Float) {
        recordingAnimationController?.onAmplitude(level)
    }

    /**
     * Side-channel timer tick. Same rationale as [onAmplitude].
     */
    fun onTimerTick(elapsedMs: Long) {
        recordingAnimationController?.onTimerTick(elapsedMs)
    }

    /**
     * Re-paint the recording animation with a new accent colour.
     * Forwarded by the IME service when [Pref.AccentColor] changes.
     */
    fun updateAccentColor(color: Int) {
        recordingAnimationController?.updateColor(color)
    }

    // ─── Internal ─────────────────────────────────────────────────────

    /**
     * Wire click listeners exactly once. Click handlers read
     * [stateRef] / [modeRef] **at click-time** so the listener captures
     * a single lambda per button, not one per render-tick (L8).
     *
     * Long-press wiring stays minimal here — only the two long-press
     * affordances declared in Spec 2 §6 (RECORD has a vibrate-only
     * marker, RESEND emits `ResendLastAudioLong`). Special touch
     * handlers (CursorSwipe / Backspace-Swipe / Enter-Overlay) come
     * via the `staticHandlerInstaller` hook so the backend doesn't
     * depend on the IME-side handler classes directly.
     */
    private fun wireStaticHandlers() {
        buttonViews.forEach { (id, view) ->
            view.setOnClickListener {
                onVibrate()
                val s = stateRef ?: return@setOnClickListener
                val slot = currentSlot(id) ?: return@setOnClickListener
                // R.3 nullable-resolver-idiom: null = silent no-op,
                // no Unrouted log-spam fires on the orchestrator.
                slot.actionResolver(s, services)?.let { action ->
                    onAction?.invoke(action)
                }
            }
        }

        // Long-press for RESEND emits the long-action (resend-with-
        // staging). RECORD's long-press is consumed for vibration but
        // doesn't currently emit an action — Spec 2 §6 preserves the
        // legacy behaviour so we keep the listener wired (returns
        // `true` to consume so the OnClick doesn't double-fire).
        buttonViews[LogicalButtonId.RECORD]?.setOnLongClickListener {
            onVibrate(); true
        }
        buttonViews[LogicalButtonId.RESEND]?.setOnLongClickListener {
            onVibrate()
            onAction?.invoke(Action.ResendAction.ResendLastAudioLong)
            true
        }
        buttonViews[LogicalButtonId.BACKSPACE]?.setOnLongClickListener { true }
    }

    /**
     * Look up the currently-active slot for [id] in the cached
     * [modeRef]. `null` when no mode is set yet (defensive — backend is
     * detached, or first render hasn't happened).
     */
    private fun currentSlot(id: LogicalButtonId): ButtonSlot? =
        modeRef?.rows
            ?.flatMap { it.slots }
            ?.firstOrNull { it.logicalId == id }
}
