package net.devemperor.dictate.state.render.overlay

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import net.devemperor.dictate.R
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.ModuleServices
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
 *  5. **Apply position** — write the position-axis (
 *     `state.overlay.position{Portrait,Landscape}{X,Y}`) into the
 *     `WindowManager.LayoutParams`. Today (C16) this is a no-op
 *     placeholder; the live drag-and-position mapping lands in C18.
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
 *   builder ([OverlayLayoutParamsFactory]) so flag combinations can be
 *   asserted in isolation. The drag-handler and position-mapper
 *   (Spec 3 §4.6 + §4.7) are deferred to C18 — their factories live
 *   in their own files there.
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
 *   Reserved for the C17 wire-up; the C16 render path reads
 *   `state.overlay.hasPermission` directly per Issue 3.1.3.
 * @property layoutParamsFactory builds [WindowManager.LayoutParams].
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

        // 5 — Position apply (placeholder for C18). Today this is a
        //     no-op — the WindowManager LayoutParams keep `x=0, y=0`
        //     and the overlay docks to the top-left corner. C18 wires
        //     the position mapper + drag handler that translate
        //     normalised [0..1] state into pixels.
        applyPositionPlaceholder()
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
        val inflater = ctx.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
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
     * C16 placeholder — actual position-mapping lands in C18.
     *
     * Reads the persisted position from `state.overlay` and (in C18)
     * will translate it into pixel `x`/`y` via the
     * `OverlayPositionMapper`. For now, the params hold the factory's
     * `0, 0` defaults (top-left dock). Documented as a clearly-named
     * placeholder so the C18 implementer has a single edit site.
     */
    @Suppress("UnusedPrivateMember")
    private fun applyPositionPlaceholder() {
        // No-op in C16. C18 will:
        //   1. Read state.overlay.position{Portrait,Landscape}{X,Y}.
        //   2. Map to pixels via OverlayPositionMapper.
        //   3. Mutate currentParams.x/y/gravity.
        //   4. overlayWindow.update(view, currentParams).
        // The drag handler also lives in C18; until it exists the
        // overlay docks to the top-left corner of the screen.
    }

    /**
     * Detach the overlay window + release every View reference.
     * Idempotent — calling on an already-torn-down backend is safe.
     */
    private fun teardownOverlay() {
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
    }

    private companion object {
        const val TAG: String = "OverlayBackend"
    }
}
