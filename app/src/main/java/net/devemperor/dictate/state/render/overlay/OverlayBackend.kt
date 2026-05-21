package net.devemperor.dictate.state.render.overlay

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import net.devemperor.dictate.R
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.ModuleServices
import net.devemperor.dictate.state.OverlayState
import net.devemperor.dictate.state.layout.BackendType
import net.devemperor.dictate.state.layout.ButtonSlot
import net.devemperor.dictate.state.layout.LayoutMode
import net.devemperor.dictate.state.layout.LogicalButtonId
import net.devemperor.dictate.state.layout.RenderBackend
import net.devemperor.dictate.state.render.applySlotToView

/**
 * RenderBackend implementation for the floating-overlay window
 * (Spec 3 §4.2).
 *
 * Consumes the [BackendType.OVERLAY_WINDOW] subset of layout modes —
 * that is, `LayoutCatalog.OVERLAY_5BUTTON` — and projects it into the
 * 5-button card defined in `res/layout/overlay_5button_layout.xml`.
 *
 * # Render-loop contract (Spec 3 §4.2)
 *
 * Per emitted [DictateUiState]:
 *
 *  1. **Permission gate** — if `state.overlay.hasPermission == false`,
 *     tear down the overlay (Issue 3.1.3 / Spec 3 §5.4 fallback).
 *  2. **Suppress bit** — if
 *     `state.overlay.suppressAutoOverlayUntilNextSession == true`,
 *     tear down. The user explicitly closed the HOVER overlay; we
 *     mustn't auto-reopen for the rest of this recording session
 *     (Issue 3.1.7).
 *  3. **Attach** — inflate + `WindowManager.addView` on first render.
 *  4. **Apply slots** — walk every slot in `mode.rows` and call
 *     [applySlotToView] (visibility / icon / text / enabled / alpha).
 *  5. **Apply position** — de-normalise the position-axis (
 *     `state.overlay.position{Portrait,Landscape}{X,Y}`) via the
 *     [OverlayPositionMapper] and write the pixel result into the
 *     `WindowManager.LayoutParams` (C18). Short-circuits during an
 *     active user drag so the finger position wins (Spec 3 §4.6).
 *
 * # Drag-lifecycle (C18, Spec 3 §4.6 + §11.5)
 *
 * The root view carries an [OverlayDragController] (wired once per
 * inflate). Touches below the drag threshold propagate to the button
 * children (clicks fire); above it the controller takes over,
 * `WindowManager.update`s the params per `ACTION_MOVE`, and on drag-end
 * dispatches `Action.OverlayAction.UpdateOverlayPosition` (normalised
 * `[0..1]`, single-dispatch F-8). A mid-drag [detach] persists the
 * final position before releasing the listener (R.18).
 *
 * # Click-listener single-wire (L8, Spec 3 §4.2)
 *
 * `wireStaticOverlayHandlers` runs exactly once per inflate; every
 * click lambda reads [stateRef] and [modeRef] **at click time** so
 * there's a single lambda allocation per button per backend lifetime
 * (mirrors the IME-View backend's `wireStaticHandlers` — same
 * forbidden-pattern (l) avoidance).
 *
 * # SOLID dependencies
 *
 * - **DIP**: `WindowManager` is wrapped in [OverlayWindow] for JVM
 *   testability. The factory pattern is used for the layout-params
 *   builder ([OverlayLayoutParamsFactory]), the position-mapper
 *   ([OverlayPositionMapper]) and the drag controller
 *   ([OverlayDragControllerFactory]) so each can be asserted in
 *   isolation or faked in tests (Spec 3 §4.6 + §4.7).
 *
 * - **SRP**: Backend handles render-loop orchestration only. Window
 *   lifecycle idempotency lives in [OverlayWindow]; layout params live
 *   in [OverlayLayoutParamsFactory].
 *
 * # ModuleServices dependency (Phase-B S-7)
 *
 * Pre-Dispatch-Allocation (R.2) requires the click-handler to call
 * `services.audioFileFactory.allocate()` before
 * `Action.RecordingAction.StartRecording`. Resolvers carry the
 * `(state, services) -> Action?` signature; the backend holds the
 * `services` reference and threads it into the resolver call —
 * consistent with `ImeViewBackend`.
 *
 * @property ctx Android Context (used by [LayoutInflater] +
 *   [applySlotToView]).
 * @property services dependency-injection container (Pre-Dispatch
 *   allocator + toast sink + other subsystems).
 * @property overlayWindow `WindowManager` indirection — production
 *   wires [AndroidOverlayWindow]; tests wire a fake.
 * @property permissions permission + onboarding gate (Spec 3 §5.1).
 *   The render path reads the mirrored `state.overlay.hasPermission`
 *   axis directly (Issue 3.1.3); the gate is held for non-reducer
 *   surfaces (kept as a constructor dependency for the C17 wiring).
 * @property layoutParamsFactory builds [WindowManager.LayoutParams].
 * @property positionMapper de-/normalises `[0..1]` ↔ pixel position;
 *   the single SoT for the conversion (Spec 3 §4.7).
 * @property dragControllerFactory builds the per-inflate
 *   [OverlayDragController] (Spec 3 §4.6) — fakeable for K-1 tests.
 *
 * @see net.devemperor.dictate.state.layout.RenderBackend
 * @see net.devemperor.dictate.state.render.overlay.OverlayWindow
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md §4.2
 * @see docs/decisions/0004-ui-layout-catalog-motionlayout.md §3
 * @see docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md
 */
class OverlayBackend(
    private val ctx: Context,
    private val services: ModuleServices,
    private val overlayWindow: OverlayWindow,
    @Suppress("unused") private val permissions: OverlayPermissionGate,
    private val layoutParamsFactory: OverlayLayoutParamsFactory =
        DefaultOverlayLayoutParamsFactory(ctx),
    private val positionMapper: OverlayPositionMapper =
        DefaultOverlayPositionMapper(ctx),
    /**
     * Factory for the drag controller — receives the inflated root
     * view, the [OverlayWindow] wrapper, a holder for the current
     * [WindowManager.LayoutParams], the [OverlayPositionMapper], and a
     * persist sink. Tests inject a fake to assert on drag events;
     * production wires [DefaultOverlayDragController].
     *
     * The factory's `paramsHolder` lambda must return the backend's
     * current [WindowManager.LayoutParams] reference (not a copy) —
     * the controller mutates `.x` / `.y` in place per `ACTION_MOVE` so
     * the next render's `applyPosition` reads the post-drag pixels
     * before normalising.
     */
    private val dragControllerFactory: OverlayDragControllerFactory =
        DefaultOverlayDragControllerFactory(ctx),
) : RenderBackend {

    override val backendType: BackendType = BackendType.OVERLAY_WINDOW

    // ─── Backend lifecycle fields ─────────────────────────────────────

    /** The inflated overlay root View — `null` while detached. */
    private var overlayView: View? = null

    /** The last [WindowManager.LayoutParams] applied to the View. */
    private var currentParams: WindowManager.LayoutParams? = null

    /** Click sink — `null` outside an attach/detach interval. */
    private var onAction: ((Action) -> Unit)? = null

    /**
     * `LogicalButtonId` → concrete `View` map for the 5 buttons in
     * `overlay_5button_layout.xml`. Re-built every inflate; empty
     * outside an attached lifecycle.
     */
    private var buttonViews: Map<LogicalButtonId, View> = emptyMap()

    /**
     * State snapshot read by click listeners at click-time. Single
     * source of truth so the lambda lives for the whole backend
     * lifetime — L8 forbidden-pattern (l) avoidance.
     */
    private var stateRef: DictateUiState? = null

    /** Active [LayoutMode] — looked up by [currentSlot]. */
    private var modeRef: LayoutMode? = null

    /**
     * Active drag controller — `null` outside an attached lifecycle.
     * Created in [inflateAndAttach] once the root view exists.
     */
    private var dragController: OverlayDragController? = null

    /**
     * The last normalised position (`(portrait?, normX, normY)`) the
     * backend pushed through [overlayWindow.update]. Used to dedup
     * `applyPosition` calls per render — comparing against
     * [WindowManager.LayoutParams.x] / `y` directly would force a
     * re-emit every time the drag controller had moved the params
     * mid-drag.
     */
    private var lastAppliedPosition: AppliedPosition? = null

    // ─── RenderBackend implementation ────────────────────────────────

    override fun attach(onAction: (Action) -> Unit) {
        this.onAction = onAction
        // No inflate here — render() drives that idempotently. attach()
        // can run before we know whether the permission is granted, so
        // attaching the window is deferred to the first successful
        // render.
    }

    override fun detach() {
        onAction = null
        teardownOverlay()
    }

    override fun render(state: DictateUiState, mode: LayoutMode) {
        require(mode.backend == BackendType.OVERLAY_WINDOW) {
            "OverlayBackend received a non-OVERLAY_WINDOW mode: ${mode.id} (backend=${mode.backend})"
        }

        // 1 — Permission gate (Issue 3.1.3): the OverlayPermissionObserver
        //     mirrors the system permission into the state axis; reducers
        //     never poll the system directly.
        if (!state.overlay.hasPermission) {
            teardownOverlay()
            return
        }

        // 2 — Suppress bit (Issue 3.1.7): the user explicitly closed the
        //     HOVER overlay during this session — don't auto-reopen.
        if (state.overlay.suppressAutoOverlayUntilNextSession) {
            teardownOverlay()
            return
        }

        stateRef = state
        modeRef = mode

        // 3 — First-render attach. inflateAndAttach() flips its own
        //     idempotency bit if it succeeds; the BadToken catch in
        //     AndroidOverlayWindow leaves the View detached on
        //     runtime-permission revocation and re-renders bail at
        //     step 1 above.
        if (overlayView == null) inflateAndAttach()
        if (!overlayWindow.isAttached()) {
            // BadToken was caught by the wrapper. Nothing to do — the
            // next render-tick will see hasPermission=false (once the
            // OverlayPermissionObserver refreshes) and the cleanup is
            // implicit because overlayView is already null.
            return
        }

        // 4 — Slot apply.
        applySlots(state, mode)

        // 5 — Position apply — de-normalises the persisted [0..1]
        //     coordinates from `state.overlay.position{Portrait,Landscape}{X,Y}`
        //     into pixels and writes them into the WindowManager params.
        //     Short-circuits during an active drag so the user's finger
        //     position wins over the (stale) normalised state axis.
        applyPosition(state.overlay)
    }

    // ─── Internal — render helpers ───────────────────────────────────

    /**
     * Apply each slot in [mode] to its corresponding button View.
     *
     * A slot referencing a [LogicalButtonId] that isn't in
     * [buttonViews] raises `error(...)` — same Silent-Skip-Guard as
     * `ImeViewBackend` (Issue 3.0.12).
     */
    private fun applySlots(state: DictateUiState, mode: LayoutMode) {
        mode.rows.flatMap { it.slots }.forEach { slot ->
            val view = buttonViews[slot.logicalId]
                ?: error(
                    "No view registered for ${slot.logicalId} in " +
                        "OverlayBackend.buttonViews (mode=${mode.id})"
                )
            applySlotToView(slot, view, state, ctx)
        }
    }

    /**
     * Inflate the overlay layout, find each button View, wire the
     * static click handlers, and attach to the WindowManager.
     *
     * The wrapper's [OverlayWindow.attach] is idempotent against
     * `BadTokenException` — if the permission was revoked between our
     * state check and the system's `addView`, [OverlayWindow.isAttached]
     * stays `false` and the backend bails before any further state is
     * captured.
     */
    private fun inflateAndAttach() {
        // overlay_5button_layout uses MaterialButton, which requires a
        // Material3 theme. The Service Context inherits the system default
        // (`Theme.DeviceDefault.*`), so inflating against it throws
        // `IllegalArgumentException("The style on this component requires
        // your app theme to be Theme.MaterialComponents (or a descendant)")`.
        // Wrap in a ContextThemeWrapper to match the IME view's theming —
        // same R.style.Theme_Dictate the IME service uses for its own
        // ContextThemeWrapper sites (DictateInputMethodService:539/766/2540).
        val themedCtx = ContextThemeWrapper(ctx, R.style.Theme_Dictate)
        val inflater = LayoutInflater.from(themedCtx)
        val view = inflater.inflate(R.layout.overlay_5button_layout, null)
        val views = mapOf(
            LogicalButtonId.OVERLAY_RECORD to view.findViewById<View>(R.id.overlay_record_btn),
            LogicalButtonId.OVERLAY_SEND to view.findViewById<View>(R.id.overlay_send_btn),
            LogicalButtonId.OVERLAY_PAUSE to view.findViewById<View>(R.id.overlay_pause_btn),
            LogicalButtonId.OVERLAY_TRASH to view.findViewById<View>(R.id.overlay_trash_btn),
            LogicalButtonId.OVERLAY_CLOSE to view.findViewById<View>(R.id.overlay_close_btn),
        )

        val params = layoutParamsFactory.create()
        overlayWindow.attach(view, params)
        if (!overlayWindow.isAttached()) {
            // Permission revoked at runtime — wrapper caught BadToken.
            // Leave state empty and let the next render() drop us into
            // the teardown path via the hasPermission == false branch.
            return
        }

        overlayView = view
        currentParams = params
        buttonViews = views

        wireStaticOverlayHandlers()
        wireDragController(view)
    }

    /**
     * Attach the drag controller to the overlay root. Per Spec 3 §4.6
     * the listener lives on the **root view** so the entire window is
     * draggable; button-clicks still propagate by the controller's
     * threshold-based touch-routing (Spec 3 §11.5.2).
     */
    private fun wireDragController(view: View) {
        val controller = dragControllerFactory.create(
            view = view,
            window = overlayWindow,
            paramsHolder = { currentParams },
            positionMapper = positionMapper,
            // F-7: the backend stays the single orientation SoT; the
            // controller captures this snapshot ONCE at ACTION_DOWN and
            // hands it back through onPositionPersist so the bucket and
            // the geometry come from the same configuration.
            orientationProvider = { isPortraitOrientation() },
            onPositionPersist = { portrait, normX, normY ->
                onAction?.invoke(
                    Action.OverlayAction.UpdateOverlayPosition(
                        portrait = portrait,
                        x = normX,
                        y = normY,
                    ),
                )
                // Update the cache so the next render's `applyPosition`
                // recognises the new persisted value as already-applied
                // and skips a redundant `window.update` (Spec 3 §11.5.5
                // idempotency note).
                lastAppliedPosition = AppliedPosition(portrait, normX, normY)
            },
        )
        controller.attach()
        dragController = controller
    }

    /**
     * Wire click listeners exactly once. Each lambda reads
     * [stateRef] + [modeRef] at click time so the listener captures
     * a single lambda per button (L8 — forbidden-pattern (l)).
     */
    private fun wireStaticOverlayHandlers() {
        buttonViews.forEach { (id, view) ->
            view.setOnClickListener {
                val state = stateRef ?: return@setOnClickListener
                val slot = currentSlot(id) ?: return@setOnClickListener
                // R.3 nullable-resolver-idiom — null = silent no-op.
                slot.actionResolver(state, services)?.let { action ->
                    onAction?.invoke(action)
                }
            }
        }
    }

    /**
     * Look up the currently-active slot for [id] in [modeRef]. `null`
     * outside an attached lifecycle.
     */
    private fun currentSlot(id: LogicalButtonId): ButtonSlot? =
        modeRef?.rows
            ?.flatMap { it.slots }
            ?.firstOrNull { it.logicalId == id }

    /**
     * Resolve the persisted normalised position from [overlay] for the
     * current orientation, de-normalise via [positionMapper], and push
     * the result into [overlayWindow.update]. Cached so re-renders for
     * the same `(orientation, normX, normY)` triple short-circuit
     * (Spec 3 §11.5.5).
     *
     * Three short-circuits:
     *
     *  1. **Active drag** — when the user's finger owns the position,
     *     state-driven updates would yank the overlay back (Spec 3
     *     §4.6 Issue 3.1.5).
     *  2. **No params / no view** — defensive; should never happen
     *     after `inflateAndAttach` because both fields land in the same
     *     block, but the type system can't prove it.
     *  3. **View not measured** — the mapper returns `null` while
     *     `width == 0`. `view.post { … }` schedules a retry so the
     *     position lands after the first layout pass (R.19 / R.20 /
     *     Spec 3 §11.5.5).
     */
    private fun applyPosition(overlay: OverlayState) {
        if (dragController?.isDragging() == true) return

        val view = overlayView ?: return
        val params = currentParams ?: return

        val portrait = isPortraitOrientation()
        val (normX, normY) = if (portrait) {
            overlay.positionPortraitX to overlay.positionPortraitY
        } else {
            overlay.positionLandscapeX to overlay.positionLandscapeY
        }

        val cached = lastAppliedPosition
        if (cached != null &&
            cached.portrait == portrait &&
            cached.normX == normX &&
            cached.normY == normY
        ) {
            return
        }

        val pixels = positionMapper.normalizedToPixels(normX, normY, view)
        if (pixels == null) {
            // First render — view hasn't been measured yet. Retry once
            // the first layout pass lands so the position is applied
            // before the user notices the top-left dock (Spec 3 §4.7).
            view.post { retryApplyPositionAfterLayout(overlay) }
            return
        }

        val (px, py) = pixels
        if (params.x == px && params.y == py) {
            // No-op — params already match; just refresh the cache so
            // a subsequent state change with the same numerics doesn't
            // re-walk the mapper.
            lastAppliedPosition = AppliedPosition(portrait, normX, normY)
            return
        }

        params.x = px
        params.y = py
        overlayWindow.update(view, params)
        lastAppliedPosition = AppliedPosition(portrait, normX, normY)
    }

    /**
     * `view.post` callback used by [applyPosition] when the first
     * render runs before the view has been measured. Reads the latest
     * snapshot from [stateRef] (in case the state moved on between the
     * `post` and its execution) and re-routes through [applyPosition].
     */
    private fun retryApplyPositionAfterLayout(initialOverlay: OverlayState) {
        val state = stateRef
        val overlay = state?.overlay ?: initialOverlay
        if (overlayView != null && currentParams != null) {
            applyPosition(overlay)
        }
    }

    /**
     * Read the current device orientation from the bound [Context].
     * Centralised so the drag controller and render path agree on
     * which pref bucket to read/write (Spec 3 §11.5.6).
     */
    private fun isPortraitOrientation(): Boolean =
        ctx.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    /**
     * Detach the overlay window + release every View reference.
     * Idempotent — calling on an already-torn-down backend is safe.
     *
     * The drag controller's [OverlayDragController.detach] runs
     * **before** the window's `detach` so a mid-drag tear-down can
     * still persist the final pixel position via [onAction] (Spec 3
     * §4.6 Issue 3.1.5 / R.18). After the controller has flushed, the
     * view's touch listener is cleared, the window is removed, and
     * every cached reference is dropped.
     */
    private fun teardownOverlay() {
        try {
            dragController?.detach()
        } catch (t: Throwable) {
            // Touch-listener detach is reflective inside the View; an
            // unexpected throw here must not block the window cleanup.
            Log.w(TAG, "dragController.detach threw", t)
        }
        dragController = null

        val view = overlayView
        if (view != null) {
            try {
                overlayWindow.detach(view)
            } catch (t: Throwable) {
                // OverlayWindow.detach already swallows
                // IllegalArgumentException; any other throwable here
                // is unexpected but mustn't crash the IME — log and
                // proceed.
                Log.w(TAG, "overlayWindow.detach threw", t)
            }
        }
        overlayView = null
        currentParams = null
        buttonViews = emptyMap()
        stateRef = null
        modeRef = null
        lastAppliedPosition = null
    }

    /**
     * Cache key for the last `applyPosition` call. `portrait` is part
     * of the tuple because rotating the device changes which pref
     * bucket feeds the position — the same `(normX, normY)` pair on
     * a different orientation is a different render outcome.
     */
    private data class AppliedPosition(
        val portrait: Boolean,
        val normX: Float,
        val normY: Float,
    )

    private companion object {
        const val TAG: String = "OverlayBackend"
    }
}
