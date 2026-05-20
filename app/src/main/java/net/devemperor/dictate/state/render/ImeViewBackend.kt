package net.devemperor.dictate.state.render

import android.content.Context
import android.view.View
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.DictateUtils
import net.devemperor.dictate.keyboard.KeyPressAnimator
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
 * @property keyPressAnimator the shared key-press scale animator (Spec 2
 *   §6 ctor / §9.2 `initializeKeyPressAnimations` row, behaviour group
 *   G7). [wireStaticHandlers] calls
 *   [KeyPressAnimator.applyPressAnimation] per owned button **except**
 *   the three special-touch buttons (SPACE / BACKSPACE / ENTER) whose
 *   `OnTouchListener` is owned by the [staticHandlerInstaller] path
 *   (CR2) — wiring press-animation there would silently overwrite the
 *   CursorSwipe / Backspace-Swipe / Enter-Overlay handler (RR-1, the
 *   F-1/F-2 trap). The legacy `MainButtonsController` composes
 *   press-animation *into* those special handlers via
 *   [KeyPressAnimator.handlePressAnimationEvent]; CR2's installer does
 *   the same when it takes over. Defaults to a fresh no-op-friendly
 *   instance so JVM-only callers/tests need not supply it.
 * @property staticHandlerInstaller optional hook executed once per
 *   [attach] **after** standard click-listener wiring — used by the
 *   IME service to bolt on special-touch handlers (CursorSwipe /
 *   Backspace-Swipe / Enter-Overlay, Spec 2 §11.7) without making the
 *   backend depend on them directly. Default no-op so JVM unit-tests
 *   don't have to provide it.
 * @property onVibrate optional haptic feedback callback fired on every
 *   click. Defaults to no-op for tests.
 * @property imeSideAffordance optional IME-side hook fired
 *   `(LogicalButtonId, isLongPress)` *before* the catalog
 *   click/long-click dispatch (CR4 — render-path-cutover.md §7 A1).
 *   Some legacy `MainButtonsController.Callback` button behaviours have
 *   **no FSM/dispatch representation** — the catalog/modules model only
 *   part of what the legacy listener did, and the remainder is an
 *   IME-side side-effect with no `Action`/`ModuleServices` surface:
 *
 *    - **RECORD long-press**: legacy `onRecordLongClicked` did the Idle
 *      → launch Settings + file-picker, and the `autoSwitchKeyboard`
 *      one-shot before an Active/Paused stop. The catalog
 *      `resolveRecordLongPressAction` + [RecordingModule] own only the
 *      FSM-half (Active/Paused discard-stop).
 *    - **RESEND click**: legacy `onResendClicked` does the
 *      last-keyboard-session DB lookup → `ResendStatusDispatcher` →
 *      insert / resume. The catalog `ResendLastAudio` →
 *      [net.devemperor.dictate.state.modules.ResendModule] only **arms
 *      the cooldown** (no effect — the resend insertion has no new-path
 *      implementation; CR4-IMPL-3).
 *    - **RESEND long-press**: legacy `onResendLongClicked` enters
 *      ReprocessStaging with the last session. The catalog
 *      `ResendLastAudioLong` → [ResendModule] only arms the cooldown.
 *
 *   There is no Activity-launch / DB-lookup / ReprocessStaging-entry
 *   surface on `ModuleServices`
 *   ([net.devemperor.dictate.state.ModuleServices]) — render-path-cutover.md
 *   §7 A1 scopes these as the **CR4 IME-side activation**. The backend
 *   fires this callback (the IME wires the exact legacy
 *   `onRecordLongClicked` / `onResendClicked` / `onResendLongClicked`
 *   bodies — behaviour-identical) so the affordances survive the cutover
 *   with zero behaviour drift, *in addition to* the catalog dispatch
 *   (which still arms the cooldown / models the FSM-half). Default
 *   no-op for JVM/legacy callers.
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
    private val keyPressAnimator: KeyPressAnimator = KeyPressAnimator(),
    private val staticHandlerInstaller: ((Map<LogicalButtonId, View>) -> Unit)? = null,
    private val onVibrate: () -> Unit = {},
    private val imeSideAffordance: (LogicalButtonId, Boolean) -> Unit = { _, _ -> },
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
        //     step entirely. The firstRender flag only flips after an
        //     actual jump/transition fires — a render-tick that skips
        //     this step (null sceneStateId) leaves the flag set so the
        //     **next** tick with a non-null sceneStateId still snaps
        //     instead of animating (F-21 / Issue 2.1.18).
        mode.sceneStateId?.let { sceneId ->
            if (firstRender || !state.layout.animationsEnabled) {
                motionSurface.jumpToState(sceneId)
            } else {
                motionSurface.transitionToState(sceneId)
            }
            firstRender = false
        }

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
     * Wire click + long-click listeners (and key-press animation) exactly
     * once. Click/long-click handlers read [stateRef] / [modeRef] **at
     * (long-)click-time** so each captures a single lambda per button, not
     * one per render-tick (L8).
     *
     * # Long-press model (Spec 2 §6 / §13.2 — CR1, render-path-cutover.md G2)
     *
     * Long-press is now a first-class catalog axis via
     * [ButtonSlot.longClickResolver] — symmetric with the click
     * [ButtonSlot.actionResolver]. The B4-interim F-1/F-2 KDoc
     * (RESEND-only wiring; RECORD/BACKSPACE long-press left on the legacy
     * `MainButtonsController`) is **removed** — the model it deferred is
     * exactly this CR1 work (the `longClickResolver` slot field + the
     * `OnRecordLongPress` Action + the RecordingModule reducer arm now
     * exist).
     *
     * **RR-1 — CR4 widens the long-press attachment to every slot with
     * a non-null `longClickResolver`.** `attach()` runs *after* the
     * legacy `MainButtonsController.registerAllListeners()`, so any
     * `setOnLongClickListener` here is the View's *most-recent* (live)
     * listener. CR1 kept this **RESEND-only** (its `ResendLastAudioLong`
     * was already the live owner pre-CR1 — zero regression) and deferred
     * RECORD/other long-press to CR4 so the legacy `onRecordLongClicked`
     * survived (RR-1: never both wired at once). **CR4 removes the
     * legacy `registerAllListeners()` drive (when bound) in the same
     * chunk it widens this filter** — so the new owner takes over with
     * no overlap (render-path-cutover.md §6 RR-1 / §5).
     *
     * The widened listener fires the catalog `longClickResolver` for
     * every slot (RESEND → `ResendLastAudioLong`; RECORD →
     * `resolveRecordLongPressAction`, i.e. `OnRecordLongPress` for
     * Active/Paused, `null` for Idle/Preparing; others default `null`).
     * For **RECORD** it additionally fires [onRecordLongPressAffordance]
     * *before* the dispatch — the legacy `onRecordLongClicked` did two
     * IME-side affordances (Idle → Settings + file-picker launch; the
     * `autoSwitchKeyboard` one-shot) that have **no FSM/dispatch
     * representation** (no Activity-launch / IME-flag surface on
     * `ModuleServices`; render-path-cutover.md §7 A1 scopes them as the
     * CR4 IME-side activation). Without this hook the Idle Settings/
     * file-picker launch would be a silently-stranded feature (RR-2).
     * BACKSPACE has no `longClickResolver` (default `{ _, _ -> null }`)
     * so its widened listener vibrates-and-consumes only — the
     * accelerated-delete cascade is owned by the
     * [staticHandlerInstaller]'s `BackspaceSwipeHandler` (CR2/CR4,
     * §11.7), exactly as on the legacy path.
     *
     * # Key-press animation (Spec 2 §6 / §9.2 G7)
     *
     * [keyPressAnimator] is applied per owned button **except** the three
     * special-touch buttons (SPACE / BACKSPACE / ENTER): their
     * `OnTouchListener` belongs to the [staticHandlerInstaller] path (CR2)
     * — calling `applyPressAnimation` there would silently overwrite the
     * CursorSwipe / Backspace-Swipe / Enter-Overlay handler (RR-1). The
     * legacy controller composes press-animation *into* those special
     * handlers; CR2's installer does the same.
     *
     * Special touch handlers (CursorSwipe / Backspace-Swipe /
     * Enter-Overlay) come via the [staticHandlerInstaller] hook so the
     * backend doesn't depend on the IME-side handler classes directly.
     */
    private fun wireStaticHandlers() {
        buttonViews.forEach { (id, view) ->
            // F-1 (B5-VAL) — SPACE is **touch-only** (legacy-parity +
            // Spec 2 §13.2 Click-Listener-Audit: SPACE's sole new owner
            // is `buildSpaceTouchHandler()` (§11.7), it has NO
            // `setOnClickListener` row — legacy `MainButtonsController`
            // had none either). The §11.7 `CursorSwipeTouchHandler`
            // `onTap` is the single SPACE-commit path. Its
            // `consumeTouchEvents = false` is load-bearing for the G4
            // cursor-swipe MOVE-propagation invariant (ACTION_MOVE must
            // keep arriving so the cursor keeps moving), which means the
            // outer touch listener returns `false` → Android also fires
            // `performClick()`. Wiring a click here too → ONE tap = TWO
            // `commitText(" ")` (the double-space regression). The
            // catalog SPACE `actionResolver = SpaceKey` stays correct
            // for a backend that does NOT install the §11.7 touch
            // handler (e.g. overlay) — for the IME backend it is simply
            // not reached. Long-press / press-anim wiring stays uniform
            // below (SPACE already skips press-anim; its no-resolver
            // long-click is a harmless vibrate-and-consume).
            if (id != LogicalButtonId.SPACE) {
                view.setOnClickListener {
                    onVibrate()
                    // CR4 (render-path-cutover.md §7 A1 / CR4-IMPL-3) +
                    // post-cutover hotfix (ADR-0005 Decision-History,
                    // "catalog-click affordance hook symmetry"):
                    //
                    //  - **RESEND** click — the real work (last-session
                    //    DB lookup → insert / resume) has NO new-path
                    //    implementation: the catalog `ResendLastAudio`
                    //    → ResendModule only arms the cooldown. The
                    //    affordance carries the legacy `onResendClicked`
                    //    body.
                    //  - **RECORD** click (Active|Paused = "stop & send")
                    //    — the IME-runtime R-1 `JobRequest` snapshot +
                    //    pipeline-step-row prime must happen BEFORE the
                    //    catalog dispatches `StopRecordingAndSend`,
                    //    because `PipelineRunnerSubsystemAdapter`'s
                    //    `resolveFresh` runs async off the dispatch and
                    //    would otherwise hit an empty snapshot → loud
                    //    `UnsupportedOperationException` (R-1
                    //    silent-data-loss tripwire) → pipeline FSM hangs
                    //    in `Preparing` → endless "Sending…" with no
                    //    step-rows / no progress UI. The affordance is
                    //    self-gating on Active|Paused (no-op otherwise).
                    //
                    // The symmetry is locked structurally by
                    // CutoverArchitectureInvariantTest's
                    // affordance-hook-symmetry assertion. No-op for every
                    // other button id (default lambda fall-through).
                    //
                    // F-10 (B5-VAL) — the RESEND double-fire/cooldown
                    // safety is adequately mitigated (manual inCooldown
                    // re-check + enabledResolver disabling the view +
                    // PIPELINE-layout visibilityPredicate=false → GONE).
                    // The residual implicit invariant: this affordance's
                    // single-fire correctness relies on Android NOT
                    // delivering clicks to GONE/disabled views. Robust
                    // today; a future listener-wiring change that breaks
                    // that assumption would silently reopen the
                    // double-fire — keep the GONE/disabled suppression in
                    // mind before rewiring this path.
                    if (id == LogicalButtonId.RESEND || id == LogicalButtonId.RECORD) {
                        imeSideAffordance(id, false)
                    }
                    val s = stateRef ?: return@setOnClickListener
                    val slot = currentSlot(id) ?: return@setOnClickListener
                    // R.3 nullable-resolver-idiom: null = silent no-op,
                    // no Unrouted log-spam fires on the orchestrator.
                    slot.actionResolver(s, services)?.let { action ->
                        onAction?.invoke(action)
                    }
                }
            }

            // RR-1 — CR4 widens the long-press listener to EVERY slot
            // (CR1 was RESEND-only; the legacy `registerAllListeners()`
            // RECORD/BACKSPACE long-press wiring is removed in this same
            // chunk — never both wired at once, §6 RR-1 / §5). The
            // listener is catalog-driven (reads `slot.longClickResolver`):
            //  - RESEND → `ResendLastAudioLong` (catalog: arms cooldown)
            //    PLUS the IME-side affordance: the legacy
            //    `onResendLongClicked` ReprocessStaging-entry has NO
            //    new-path implementation (ResendModule only arms the
            //    cooldown — CR4-IMPL-3), so the affordance carries it.
            //  - RECORD → `resolveRecordLongPressAction` (OnRecordLongPress
            //    for Active/Paused, null Idle/Preparing) PLUS the IME-side
            //    affordance (Idle→Settings+picker / autoSwitch) which has
            //    no dispatch representation — see [imeSideAffordance]
            //    + the function KDoc (render-path-cutover.md §7 A1).
            //  - BACKSPACE / others → default `null` resolver: just
            //    vibrate-and-consume (the accel-delete cascade is the
            //    staticHandlerInstaller's BackspaceSwipeHandler, §11.7).
            view.setOnLongClickListener {
                onVibrate()
                if (id == LogicalButtonId.RECORD || id == LogicalButtonId.RESEND) {
                    // IME-side affordance — fired BEFORE the catalog
                    // dispatch so the IME observes the gesture even when
                    // the resolver returns null. The IME body re-checks
                    // the effective state itself (legacy
                    // `onRecordLongClicked` / `onResendLongClicked`
                    // parity).
                    imeSideAffordance(id, true)
                }
                val s = stateRef
                val slot = currentSlot(id)
                if (s != null && slot != null) {
                    slot.longClickResolver(s, services)?.let { action ->
                        onAction?.invoke(action)
                    }
                }
                // Consume the long-press so it doesn't fall through to
                // a click; R.3 nullable-resolver short-circuits the
                // dispatch when structurally meaningless.
                true
            }

            // G7 key-press scale animation — skip the special-touch
            // buttons (their OnTouchListener is the installer's, CR2;
            // wiring here would clobber CursorSwipe/Backspace/Enter — RR-1).
            if (id != LogicalButtonId.SPACE &&
                id != LogicalButtonId.BACKSPACE &&
                id != LogicalButtonId.ENTER
            ) {
                keyPressAnimator.applyPressAnimation(view)
            }
        }
    }

    /**
     * Re-apply accent-colour theming to the owned buttons (behaviour
     * group G6, Spec 2 §9.2 — *"Theme-Mutation ist eine separate Achse,
     * nicht state-getrieben. Der ImeViewBackend hat eine
     * `applyTheme(accentColor)`-Methode, die der Service nach jedem
     * Re-Inflate aufruft."*).
     *
     * Theme is a **separate, non-state-driven axis** — it is *not* derived
     * in [render] from [DictateUiState]; the IME service calls this
     * imperatively after a re-inflate / accent-colour change. The colour
     * tiers mirror the legacy `MainButtonsController.applyTheme`
     * (`:389-416`): RECORD = accent; BACKSPACE / ENTER = accent darkened
     * 0.35; the remaining owned buttons = accent darkened 0.18.
     * `WIDGET_TOGGLE` is intentionally not themed here (the legacy
     * `applyTheme` never themed it — it predates the button). The
     * non-owned edit-row buttons stay themed by the legacy controller
     * until CR4 (additive — legacy still drives the theme axis in CR1).
     *
     * Only [MaterialButton]-typed owned views are coloured (the legacy
     * `applyButtonColor` is `MaterialButton`-typed); a non-button view in
     * the map is skipped defensively.
     */
    fun applyTheme(accentColor: Int) {
        val accentMedium = DictateUtils.darkenColor(accentColor, 0.18f)
        val accentDark = DictateUtils.darkenColor(accentColor, 0.35f)
        buttonViews.forEach { (id, view) ->
            val button = view as? MaterialButton ?: return@forEach
            val tier = when (id) {
                LogicalButtonId.RECORD -> accentColor
                LogicalButtonId.BACKSPACE, LogicalButtonId.ENTER -> accentDark
                LogicalButtonId.WIDGET_TOGGLE -> return@forEach
                else -> accentMedium
            }
            button.setBackgroundColor(tier)
        }
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
