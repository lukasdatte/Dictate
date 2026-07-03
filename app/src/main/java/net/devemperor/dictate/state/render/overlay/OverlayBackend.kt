package net.devemperor.dictate.state.render.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
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
import net.devemperor.dictate.state.render.AutoEnterRenderer
import net.devemperor.dictate.state.render.RecordButtonColorController
import net.devemperor.dictate.state.render.RecordingAnimationController
import net.devemperor.dictate.state.render.applySlotToView
import net.devemperor.dictate.state.render.effectiveNight

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
    /**
     * Side-channel renderer factories (dictate-widget-integration §8.1
     * Chunks 1.3-1.4). The three side-channel-renderer classes
     * ([RecordingAnimationController], [AutoEnterRenderer],
     * [RecordButtonColorController]) are bound to concrete View
     * instances — which only exist after [inflateAndAttach] runs. The
     * factories let the construction site (e.g.
     * `DictatePipelineService.onCreate`) wire the production renderers
     * with no `lateinit`-style late binding, and let tests skip them
     * entirely by leaving the defaults `null`.
     *
     * Each factory may return `null` to opt out (e.g. JVM tests that
     * don't care about animations) — in that case the corresponding
     * forwarder method on this backend is a no-op. Production callers
     * pass real factories; the backend instantiates one renderer of
     * each kind per `inflateAndAttach` (and resets them in
     * [teardownOverlay]).
     *
     * **Why a factory and not a constructor-injected renderer?** The
     * three renderers hold strong refs to the inflated `record_btn` /
     * `overlay_pulse_layout` Views. Constructing them at backend-build
     * time would either (a) require View refs the service does not have
     * yet, or (b) force the backend to allocate them with `null` Views
     * and then reach in to set them — an indirection that breaks the
     * single-writer invariant. Factories invert the dependency: the
     * backend resolves the Views inside `inflateAndAttach`, hands them
     * to the factory, and stores the resulting renderer in its render
     * bundle.
     */
    private val recordingAnimationControllerFactory: RecordingAnimationControllerFactory? = null,
    private val autoEnterRendererFactory: AutoEnterRendererFactory? = null,
    private val recordButtonColorControllerFactory: RecordButtonColorControllerFactory? = null,
    /**
     * IME-side affordance hook fired *before* the catalog click-dispatch
     * for the `OVERLAY_RECORD` button (dictate-widget-integration §8.3
     * Chunk 3.1). Symmetric to `ImeViewBackend.imeSideAffordance` — the
     * production IME wires this to the same lambda the keyboard surface
     * uses, so the R-1 `JobRequest` snapshot
     * (`prepareCatalogStopRecordingIfActive`) lands in
     * `ImePipelineConfigResolver` BEFORE the orchestrator dispatches
     * `StopRecordingAndSend`. Without this hook, the pipeline async
     * `resolveFresh` would find an empty snapshot and the FSM would hang
     * in `Preparing` ("Sending …" with no progress) — the R-1
     * silent-data-loss class the keyboard surface guards against.
     *
     * The hook is self-gating in the IME implementation
     * (`prepareCatalogStopRecordingIfActive` returns early when state is
     * not Active|Paused), so it is safe to call unconditionally on every
     * OVERLAY_RECORD click. Default no-op for JVM tests / fallback
     * mode.
     */
    private val imeSideAffordance: (LogicalButtonId, Boolean) -> Unit = { _, _ -> },
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
     * The effective night mode the current [overlayView] was inflated
     * with (F-119) — `null` while detached. [render] compares this
     * against the freshly-resolved [effectiveNight] per tick and tears
     * the view down on divergence, so a `Pref.Theme` change (mirrored
     * into `state.theming.theme`) or a system uiMode flip re-inflates
     * with the correct `colorSurface` palette.
     */
    private var inflatedNightMode: Boolean? = null

    /**
     * The opacity percent last written into the card background's fill
     * (F-118) — `null` while detached or before the first
     * [applyBackgroundOpacity] pass. Makes the per-render-tick mutation
     * idempotent; nulled in [teardownOverlay] because a re-inflate
     * recreates the drawable (the fresh XML fill is the plain
     * `colorSurface` again, without the [OverlayCardFill] pre-blend).
     */
    private var lastAppliedOpacityPercent: Int? = null

    /**
     * The last normalised position (`(portrait?, normX, normY)`) the
     * backend pushed through [overlayWindow.update]. Used to dedup
     * `applyPosition` calls per render — comparing against
     * [WindowManager.LayoutParams.x] / `y` directly would force a
     * re-emit every time the drag controller had moved the params
     * mid-drag.
     */
    private var lastAppliedPosition: AppliedPosition? = null

    /**
     * Side-channel renderer bundle — the three view-bound renderers
     * that mirror the IME-View backend's render-axes onto the overlay
     * surface (dictate-widget-integration §8.1 Chunks 1.3-1.4).
     *
     * Built once per [inflateAndAttach] from the supplied factories; each
     * renderer holds strong refs to the inflated `overlay_record_btn`
     * (and the `overlay_pulse_layout` for the animation controller).
     * Reset in [teardownOverlay] BEFORE the View refs become invalid so
     * a pending animation does not touch a torn-down View tree.
     *
     * `null` outside an attached lifecycle, and any field of it stays
     * `null` if the corresponding factory was not supplied (e.g. JVM
     * unit tests).
     */
    private var rendererBundle: OverlayRendererBundle? = null

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

        // 2 — Suppress bit gate (removed 2026-05-23 sticky-widget refactor).
        //
        // The old gate tore the window down whenever `state.overlay.suppress-
        // AutoOverlayUntilNextSession == true`. That made sense pre-refactor
        // because the auto-open logic was the ONLY way the overlay could
        // come back — the suppress-bit stopped a just-closed widget from
        // immediately re-popping when the IME tore down. Post-refactor the
        // overlay attach/detach is driven by `syncOverlayBackendAttachment`
        // reading `state.viewMode != KEYBOARD || state.widget is Visible`
        // (DictatePipelineService.kt), so a closed widget gets a proper
        // detach() + teardown via the manager — not via an in-render gate.
        //
        // Critical: leaving the suppress-bit gate here while the widget axis
        // is sticky created a window-of-inconsistency. `widget = Visible`
        // would keep the backend attached, but a stale suppress-bit would
        // also fire teardownOverlay() on the next render-tick — nulling
        // stateRef/modeRef while leaving the click-listeners wired to the
        // (now-null) snapshot. Result: window sometimes visually intact,
        // every tap silently swallowed by the `stateRef ?: return` guard
        // in the click sink. Closing via X then did nothing.
        //
        // The suppress-bit is now an effectively dead axis; W2 still writes
        // it for backward-compatibility, no one reads it. A later cleanup
        // can remove the writes + the axis from `OverlayState`.

        // 2.5 — Theme unification (F-119). The attached view is bound
        //       to the effective night mode resolved at inflate time;
        //       when `state.theming.theme` (the Pref.Theme mirror) or
        //       the system uiMode flips the resolved mode, tear down so
        //       step 3 re-inflates against the correct palette. Runs
        //       BEFORE the stateRef/modeRef capture because
        //       teardownOverlay() nulls both.
        val nightWanted = effectiveNight(state.theming.theme, ctx.resources.configuration)
        if (overlayView != null && inflatedNightMode != nightWanted) {
            teardownOverlay()
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

        // 5 — Side-channel forwards (dictate-widget-integration §8.1
        //     Chunk 1.4) — symmetric to `ImeViewBackend.render` step 3-6.
        //     The three view-bound renderers are idempotent (no-op when
        //     the cached state class hasn't changed) so a render-tick
        //     that doesn't transition is cheap. Order mirrors
        //     ImeViewBackend.render to keep the two surfaces lock-step
        //     (B4-VAL F-14 idempotency contract).
        rendererBundle?.autoEnter?.onState(state)
        rendererBundle?.color?.onState(state)
        rendererBundle?.recording?.onState(state)

        // 5.5 — Card-background opacity (F-118). Idempotent per render
        //       tick (cached percent); re-runs after every
        //       inflateAndAttach because teardown recreates the
        //       drawable with the opaque XML fill.
        applyBackgroundOpacity(state.theming.widgetOpacity)

        // 6 — Position apply — de-normalises the persisted [0..1]
        //     coordinates from `state.overlay.position{Portrait,Landscape}{X,Y}`
        //     into pixels and writes them into the WindowManager params.
        //     Short-circuits during an active drag so the user's finger
        //     position wins over the (stale) normalised state axis.
        applyPosition(state.overlay)
    }

    // ─── Public side-channel forwarders (Spec 2 §11.5 pattern) ─────────

    /**
     * Side-channel amplitude tick (dictate-widget-integration §8.1
     * Chunk 1.3). Not part of [DictateUiState] — forwarded by the IME
     * service's `RecordingActivityTickerObserver` from the
     * `services.amplitudeStream` side-flow. No-op when no
     * [RecordingAnimationController] factory was supplied (JVM tests).
     *
     * Mirrors `ImeViewBackend.onAmplitude` exactly so the two surfaces
     * stay in lock-step — same controller class, different View
     * instance.
     */
    fun onAmplitude(level: Float) {
        rendererBundle?.recording?.onAmplitude(level)
    }

    /**
     * Side-channel timer tick (dictate-widget-integration §8.1 Chunk
     * 1.3). Same rationale as [onAmplitude]: not in [DictateUiState],
     * forwarded by the IME service. No-op when no
     * [RecordingAnimationController] factory was supplied.
     *
     * Mirrors `ImeViewBackend.onTimerTick` exactly.
     */
    fun onTimerTick(elapsedMs: Long) {
        rendererBundle?.recording?.onTimerTick(elapsedMs)
    }

    /**
     * Re-paint the recording animation with a new accent colour.
     * Symmetric to `ImeViewBackend.updateAccentColor`. No-op when no
     * [RecordingAnimationController] factory was supplied.
     */
    fun updateAccentColor(color: Int) {
        rendererBundle?.recording?.updateColor(color)
    }

    /**
     * Tear the attached overlay down and immediately re-render from the
     * last state/mode snapshot (F-120).
     *
     * Called by `DictatePipelineService.onConfigurationChanged` when
     * the uiMode night bits or the display density change while the
     * widget is attached — the once-inflated view tree would otherwise
     * keep stale colors (an auto night-schedule flip on a multi-hour
     * sticky widget) and stale pixel-derived layout params (the fixed
     * window width is computed from `displayMetrics.density` at
     * `create()` time). Re-rendering re-runs [inflateAndAttach], which
     * resolves both freshly; the position survives via the
     * `OverlayPosition` prefs mirrored into `state.overlay`.
     *
     * No-op while detached or before the first render — the next
     * regular render tick inflates against the fresh configuration
     * anyway.
     */
    fun reinflate() {
        if (overlayView == null) return
        val state = stateRef ?: return
        val mode = modeRef ?: return
        // teardownOverlay() nulls stateRef/modeRef — the locals above
        // carry the snapshot into the immediate re-render (waiting for
        // the next state emit would leave the window gone until then).
        teardownOverlay()
        render(state, mode)
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
        //
        // F-119 — honour Pref.Theme, not just the system uiMode: the
        // day/night variant of Theme.Dictate is selected by the
        // Configuration's night bits, so a uiMode-overriding
        // configuration context is interposed BEFORE the theme wrapper
        // whenever the user's theme pref diverges from the system.
        // `stateRef` is always populated here (render() assigns it
        // before calling inflateAndAttach); the "system" fallback only
        // covers a hypothetical future direct call.
        val nightWanted = effectiveNight(
            stateRef?.theming?.theme ?: "system",
            ctx.resources.configuration,
        )
        val themedCtx = ContextThemeWrapper(contextForNightMode(nightWanted), R.style.Theme_Dictate)
        val inflater = LayoutInflater.from(themedCtx)
        val view = inflater.inflate(R.layout.overlay_5button_layout, null)
        // Variante 2a (dictate-widget-integration §6.5): OVERLAY_SEND was
        // merged into OVERLAY_RECORD; only four buttons remain.
        val views = mapOf(
            LogicalButtonId.OVERLAY_RECORD to view.findViewById<View>(R.id.overlay_record_btn),
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
        inflatedNightMode = nightWanted

        wireStaticOverlayHandlers()
        wireDragController(view)
        buildRendererBundle(view, views)
    }

    /**
     * Mutate the card background's **fill** to the [OverlayCardFill]
     * pre-blend for [opacityPercent] (F-118 + 2026-07-03
     * opacity-consistency fix). The 1 dp `colorOutlineVariant` stroke
     * stays untouched so the card boundary remains legible.
     *
     * The painted colour is **fully opaque**: `colorSurface` pre-blended
     * over the effective keyboard background for the night mode this
     * view was inflated with ([inflatedNightMode]). A translucent fill
     * would composite against the window backdrop — the opaque keyboard
     * in WIDGET mode vs. arbitrary host content in HOVER mode — making
     * the same opacity value *look* different per mode (the user's
     * "opacity is not identical between modes" report). Pre-composition
     * makes the on-screen result backdrop-independent; the full
     * rationale + trade-off lives on [OverlayCardFill].
     *
     * The buttons keep their own **opaque** Material container tints
     * (`OverlayButton.Primary` = filled `colorPrimary`,
     * `OverlayButton.Icon` = filled-tonal `colorSecondaryContainer` —
     * `styles_overlay.xml`, 2026-07-03 widget-transparency fix). This
     * method touches the card drawable only; it must never reach into
     * the button backgrounds.
     *
     * `mutate()` detaches the drawable's constant state so the shared
     * `overlay_background.xml` resource (also used by other inflations)
     * is not affected. Clamping to the settings range happens inside
     * [OverlayCardFill.effectiveFill].
     *
     * Idempotent per render tick via [lastAppliedOpacityPercent].
     */
    private fun applyBackgroundOpacity(opacityPercent: Int) {
        val view = overlayView ?: return
        if (lastAppliedOpacityPercent == opacityPercent) return

        val background = view.background?.mutate() as? GradientDrawable
        if (background == null) {
            Log.w(TAG, "overlay background is not a GradientDrawable — opacity not applied")
            return
        }
        val surface = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorSurface,
        )
        // inflatedNightMode is always set here: applyBackgroundOpacity
        // only runs with overlayView != null, and both fields are
        // assigned together in inflateAndAttach.
        val base = ContextCompat.getColor(
            view.context,
            if (inflatedNightMode == true) R.color.dictate_keyboard_background_dark
            else R.color.dictate_keyboard_background_light,
        )
        background.setColor(OverlayCardFill.effectiveFill(surface, base, opacityPercent))
        lastAppliedOpacityPercent = opacityPercent
    }

    /**
     * Return [ctx] itself when the system configuration already
     * resolves to [night], otherwise a `createConfigurationContext`
     * wrap whose `uiMode` night bits force the requested mode (F-119).
     * The caller layers the `Theme.Dictate` [ContextThemeWrapper] on
     * top so the day/night resource qualifiers pick the right palette.
     */
    private fun contextForNightMode(night: Boolean): Context {
        val systemConfig = ctx.resources.configuration
        val systemNight =
            (systemConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        if (night == systemNight) return ctx

        val override = Configuration(systemConfig).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
        return ctx.createConfigurationContext(override)
    }

    /**
     * Instantiate the side-channel renderer bundle from the inflated
     * Views (dictate-widget-integration §8.1 Chunk 1.4).
     *
     * Each renderer is built only if its factory was supplied — JVM
     * tests that ignore animations leave the factory `null` and the
     * forwarder methods become no-ops. The bundle stays live until
     * [teardownOverlay] resets it, at which point the View refs are
     * about to become invalid.
     *
     * The `record_btn` view is downcast to [MaterialButton] because the
     * three renderer classes expect that concrete type — the layout XML
     * uses `<MaterialButton>` so the cast is structurally safe.
     */
    private fun buildRendererBundle(rootView: View, views: Map<LogicalButtonId, View>) {
        val recordBtn = views[LogicalButtonId.OVERLAY_RECORD] as? MaterialButton ?: run {
            Log.w(TAG, "OVERLAY_RECORD view is not a MaterialButton — skipping side-channel renderers.")
            return
        }
        rendererBundle = OverlayRendererBundle(
            recording = recordingAnimationControllerFactory?.create(recordBtn),
            autoEnter = autoEnterRendererFactory?.create(recordBtn),
            color = recordButtonColorControllerFactory?.create(recordBtn),
        )
    }

    /**
     * Build the drag controller and bind it to the overlay root.
     *
     * The root is a [DraggableOverlayLayout]; assigning its
     * [DraggableOverlayLayout.dragController] routes the view's
     * `onInterceptTouchEvent` / `onTouchEvent` into the controller, so
     * the whole window is draggable while the buttons stay clickable
     * (Spec 3 §4.6 / §11.5.2 — see [OverlayDragController]).
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
        val root = view as? DraggableOverlayLayout
        if (root != null) {
            root.dragController = controller
        } else {
            Log.w(TAG, "overlay root is not a DraggableOverlayLayout — window drag disabled")
        }
        dragController = controller
    }

    /**
     * Wire click listeners exactly once. Each lambda reads
     * [stateRef] + [modeRef] at click time so the listener captures
     * a single lambda per button (L8 — forbidden-pattern (l)).
     *
     * # `imeSideAffordance` hook for OVERLAY_RECORD (R-1 snapshot)
     *
     * For [LogicalButtonId.OVERLAY_RECORD] the affordance hook fires
     * **before** the catalog `actionResolver` is consulted, symmetric to
     * `ImeViewBackend.wireStaticHandlers` for the keyboard RECORD. The
     * production IME wires this to the same lambda used for
     * keyboard-RECORD: the lambda calls
     * `prepareCatalogStopRecordingIfActive()` which captures the R-1
     * `JobRequest` snapshot (`imePipelineConfigResolver.snapshotFresh`)
     * BEFORE the catalog dispatches `StopRecordingAndSend`. Without
     * this hook, the orchestrator's async `resolveFresh` finds an empty
     * snapshot, throws the loud `UnsupportedOperationException` R-1
     * tripwire, the EffectFailure arm catches it, and the pipeline FSM
     * hangs in `Preparing` ("Sending …") forever — the exact bug user
     * reported for the overlay SEND path before this hook landed.
     *
     * The hook is self-gating in the IME implementation (the helper
     * returns early when state is not Active|Paused), so it is safe to
     * call unconditionally — `null` `actionResolver` returns
     * (e.g. HOVER-gate, Idle-record-pre-allocate) just mean the
     * dispatch is a no-op while the snapshot remains harmless.
     */
    private fun wireStaticOverlayHandlers() {
        buttonViews.forEach { (id, view) ->
            view.setOnClickListener {
                val state = stateRef ?: return@setOnClickListener
                val slot = currentSlot(id) ?: return@setOnClickListener
                // R-1 affordance: fire BEFORE the catalog dispatch for
                // OVERLAY_RECORD so the JobRequest snapshot lands in
                // `imePipelineConfigResolver` before the orchestrator
                // submits the pipeline. Symmetric to ImeViewBackend's
                // RECORD click branch (`ImeViewBackend.wireStaticHandlers`
                // line ~457). Default no-op lambda makes this a free
                // call when no IME is attached (JVM tests / fallback).
                if (id == LogicalButtonId.OVERLAY_RECORD) {
                    imeSideAffordance(id, false)
                }
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
     * §4.6 Issue 3.1.5 / R.18). After the controller has flushed, it is
     * unbound from the [DraggableOverlayLayout] root, the window is
     * removed, and every cached reference is dropped.
     */
    private fun teardownOverlay() {
        // Side-channel renderer cleanup MUST run before the window
        // detach (dictate-widget-integration §10.1 R-2): pending
        // animations + drawable cache references would otherwise outlive
        // their View refs and leak. The renderer-classes' `reset()` is
        // idempotent; each clears its idempotency cache so a subsequent
        // re-attach re-applies unconditionally.
        try {
            rendererBundle?.recording?.reset()
            rendererBundle?.autoEnter?.reset()
            rendererBundle?.color?.reset()
        } catch (t: Throwable) {
            Log.w(TAG, "rendererBundle.reset threw", t)
        }
        rendererBundle = null

        try {
            dragController?.detach()
            (overlayView as? DraggableOverlayLayout)?.dragController = null
        } catch (t: Throwable) {
            // A mid-drag persist throw must not block the window cleanup.
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
        inflatedNightMode = null
        lastAppliedOpacityPercent = null
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

// ─── Side-channel renderer factory contracts (§8.1 Chunks 1.3-1.4) ──

/**
 * Builds a [RecordingAnimationController] bound to the inflated overlay
 * `record_btn` view.
 *
 * The factory is invoked once per [OverlayBackend.inflateAndAttach],
 * AFTER the views are inflated and attached to the WindowManager. The
 * resulting controller owns the BorderGlow visualiser and the breathing
 * background animator for the overlay surface; it is symmetric to the
 * IME-View backend's `recordingAnimationController` but bound to a
 * different View instance.
 *
 * Production wiring: the service builds the underlying
 * `RecordingAnimation` (e.g. `BorderGlowAnimation` configured with the
 * accent colour and density), then returns a
 * `RecordingAnimationController(animation, recordButton,
 * accentColorProvider, animationsEnabled)`.
 *
 * @see dictate-widget-integration §8.1 Chunk 1.4
 */
fun interface RecordingAnimationControllerFactory {
    fun create(recordButton: MaterialButton): RecordingAnimationController?
}

/**
 * Builds an [AutoEnterRenderer] bound to the inflated overlay
 * `record_btn`. Production wiring: `AutoEnterRenderer(recordButton)`.
 *
 * @see dictate-widget-integration §8.1 Chunk 1.4
 */
fun interface AutoEnterRendererFactory {
    fun create(recordButton: MaterialButton): AutoEnterRenderer?
}

/**
 * Builds a [RecordButtonColorController] bound to the inflated overlay
 * `record_btn`. Production wiring:
 * `RecordButtonColorController(recordButton, ...)`.
 *
 * @see dictate-widget-integration §8.1 Chunk 1.4
 */
fun interface RecordButtonColorControllerFactory {
    fun create(recordButton: MaterialButton): RecordButtonColorController?
}

/**
 * Holder for the three view-bound side-channel renderer instances built
 * inside [OverlayBackend.inflateAndAttach]. Each field is independently
 * nullable so callers that opt out of a single side-channel (e.g. tests)
 * still get a coherent bundle.
 *
 * **Lifecycle:** Created once per inflate, reset in
 * [OverlayBackend.teardownOverlay] BEFORE the View refs become invalid.
 * The bundle itself is data; the renderers it holds carry the mutable
 * idempotency caches.
 */
internal data class OverlayRendererBundle(
    val recording: RecordingAnimationController?,
    val autoEnter: AutoEnterRenderer?,
    val color: RecordButtonColorController?,
)
