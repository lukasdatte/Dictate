package net.devemperor.dictate.state.render

import android.util.Log
import android.view.View
import android.view.inputmethod.InputConnection
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.keyboard.BackspaceSwipeHandler
import net.devemperor.dictate.keyboard.CursorSwipeTouchHandler
import net.devemperor.dictate.keyboard.EnterOverlayHandler
import net.devemperor.dictate.keyboard.KeyPressAnimator
import net.devemperor.dictate.state.insertion.ControlOp
import net.devemperor.dictate.state.insertion.InsertionPolicy
import net.devemperor.dictate.state.insertion.InsertionRequest
import net.devemperor.dictate.state.insertion.InsertionService
import net.devemperor.dictate.state.layout.LogicalButtonId

/**
 * Builds the three special-touch [View.OnTouchListener]s that do **not**
 * fit the Catalog slot model (their state-machines are touch-driven, not
 * `setOnClickListener`-centric) and supplies them to [ImeViewBackend] via
 * the `staticHandlerInstaller` hook — the parent-B4 SRP/DIP improvement
 * over Spec 2 §6's "builders inside the backend" (kept, not reverted —
 * render-path-cutover.md §4 NOTE).
 *
 * The three handlers (Spec 2 §11.7, followed **verbatim**):
 *  - [buildSpaceTouchHandler] — `CursorSwipeTouchHandler` on SPACE
 *    (tap = commit space; horizontal swipe = move cursor; compound-drawable
 *    arrow swap while swiping). Behaviour group **G4**.
 *  - [buildBackspaceSwipeHandler] — `BackspaceSwipeHandler` on BACKSPACE
 *    (swipe-left-to-select-words; its `onDeleteCancelled` callback cancels
 *    the IME's accelerated-delete `deleteHandler` cascade — the half F-1
 *    dropped when it deleted a bare `{ true }` consumer-listener).
 *    Behaviour group **G3**.
 *  - [buildEnterOverlayHandler] — `EnterOverlayHandler` on ENTER
 *    (overlay-character selection). Behaviour group **G5**.
 *
 * # Wiring status (post-CR-DEL — sole live owner)
 *
 * **Sole live owner of the SPACE/BACKSPACE/ENTER special-touch axis.**
 * [attachToViews] is the only writer of these three
 * [View.OnTouchListener]s now that `MainButtonsController` is
 * **deleted** (CR-DEL completed the D-13 migration). The
 * "build-but-don't-attach / still LIVE in CR2 / CR4 flips it" framing
 * below is **historical** — there is no `MainButtonsController` and no
 * parallel touch writer left; that staged mechanic is the *how* this
 * installer became the live owner, not a current state.
 * `SpecialTouchHandlerInstallerTest` covers the contract.
 *
 * # RR-1 — the load-bearing single-owner model (build-but-don't-attach) — historical
 *
 * The staged mechanic, recorded as history (`MainButtonsController` is
 * deleted — see "Wiring status" above). During CR2 the legacy
 * `MainButtonsController` touch wiring
 * (`MainButtonsController.kt:203-208` BACKSPACE swipe,
 * `:217-246` SPACE cursor-swipe, `:268-273` ENTER overlay) was **still
 * LIVE** — it was removed only by **CR4**. The wiring order was
 * decisive:
 *
 * ```
 *   onCreateInputView:
 *     line 827  mainButtonsController.registerAllListeners()   ← legacy
 *                 → backspace/space/enter .setOnTouchListener(...)  (LIVE pre-CR4)
 *     line 1141 keyboardLayoutManager.attachBackend(imeViewBackend)
 *                 → ImeViewBackend.attach()
 *                   → staticHandlerInstaller.invoke(buttonViews)   ← us
 * ```
 *
 * Android keeps **only the most-recent** `setOnXListener`. The installer
 * runs *after* the legacy wiring, so a naive
 * `space.setOnTouchListener(buildSpaceTouchHandler())` would have
 * **silently overwritten** the live legacy `CursorSwipeTouchHandler` /
 * `BackspaceSwipeHandler` / `EnterOverlayHandler` — a half-broken
 * keyboard with **no error**. This was the exact F-1/F-2 trap
 * (render-path-cutover.md §6 **RR-1**, the highest-severity risk of
 * Block B5).
 *
 * **Mitigation — build-but-don't-attach.** [installDormant] (the lambda
 * the IME wires as `staticHandlerInstaller`) only **builds** the three
 * handlers and caches them ([spaceHandler] / [backspaceHandler] /
 * [enterHandler]); it does **not** call `setOnTouchListener` on the live
 * Views. Through CR2/CR3 the legacy `MainButtonsController` therefore
 * stayed the **sole LIVE owner** of SPACE/BACKSPACE/ENTER touch.
 *
 * **CR4 flipped it** via [attachToViews], called *in the same chunk* that
 * removed the legacy `registerAllListeners()` touch wiring, then
 * **CR-DEL** deleted `MainButtonsController` outright — never both
 * wired at once (RR-1 mitigation, identical to the CR1 long-press model
 * already accepted by the orchestrator: RESEND-only attach, RECORD
 * built-but-dormant). CR2 and CR4 separated cleanly — **no
 * architecture-conflict**.
 *
 * [installDormant] runs a single-owner-per-View **assertion/log**
 * (Strict-Mode-Logging style, Spec 2 §10): it tags each built View with
 * an ownership marker and logs `WTF` if a second build-pass ever sees a
 * View it already tagged (a double-wire would be a CR2 regression). The
 * tag is asserted (not just logged) under unit test via [ownerOf].
 *
 * @property inputConnectionProvider current `InputConnection` (nullable —
 *   the IME's `getCurrentInputConnection`). Threaded into all three
 *   §11.7 handlers exactly as the legacy `MainButtonsController` does.
 *   READ-only now (null-guards, compound-drawable reset); all host WRITES
 *   go through [insertionService] (P4 keystroke-path migration).
 * @property insertionService the single InsertionService owning all host-IC
 *   writes (nullable → no-op). Threaded into the SPACE handler (tap = space
 *   insert, swipe = cursor move) and forwarded to the BACKSPACE / ENTER
 *   handlers so their writes funnel through the same owner.
 * @property accentColorProvider live accent colour (for the ENTER
 *   overlay), `Pref.AccentColor`.
 * @property onVibrate haptic feedback sink (the IME `vibrate()`).
 * @property onBackspaceDeleteCancelled cancels the IME accelerated-delete
 *   `deleteHandler` cascade — wired to
 *   `DictateInputMethodService.onBackspaceDeleteCancelled()` so the G3
 *   cascade can be interrupted by a swipe (the behaviour F-1 lost).
 * @property keyPressAnimator the shared scale animator — composed *into*
 *   each special handler (`handlePressAnimationEvent`) exactly as the
 *   legacy controller does, so press-animation parity survives the
 *   cutover (and the backend can keep skipping these three Views in
 *   `applyPressAnimation`, RR-1 — see [ImeViewBackend.wireStaticHandlers]).
 *
 * @see ImeViewBackend
 * @see net.devemperor.dictate.keyboard.CursorSwipeTouchHandler
 * @see net.devemperor.dictate.keyboard.BackspaceSwipeHandler
 * @see net.devemperor.dictate.keyboard.EnterOverlayHandler
 * @see docs/plans/2026-05-15 - dictate-cutover-completion/research/render-path-cutover.md §6 RR-1 + §4 NOTE
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §11.7
 */
class SpecialTouchHandlerInstaller(
    private val inputConnectionProvider: () -> InputConnection?,
    private val insertionService: () -> InsertionService?,
    private val accentColorProvider: () -> Int,
    private val onVibrate: () -> Unit,
    private val onBackspaceDeleteCancelled: () -> Unit,
    private val keyPressAnimator: KeyPressAnimator,
) {

    /** The built CursorSwipe handler for SPACE (G4). `null` until [installDormant]. */
    var spaceHandler: View.OnTouchListener? = null
        private set

    /** The built BackspaceSwipe handler for BACKSPACE (G3). `null` until [installDormant]. */
    var backspaceHandler: View.OnTouchListener? = null
        private set

    /** The built EnterOverlay handler for ENTER (G5). `null` until [installDormant]. */
    var enterHandler: View.OnTouchListener? = null
        private set

    /**
     * The single-owner ledger: `LogicalButtonId` → the owner string that
     * tagged the View's [View.setTag] slot. Populated by [installDormant];
     * asserted by tests via [ownerOf]. A second build-pass that finds a
     * View already tagged by us logs `Log.wtf` (a CR2 double-wire would be
     * a real regression — RR-1).
     */
    private val ownerLedger = mutableMapOf<LogicalButtonId, String>()

    /**
     * CR2 entry point — the lambda the IME wires as the
     * `staticHandlerInstaller`. **Builds** the three §11.7 handlers and
     * caches them; does **NOT** attach them to the live Views (RR-1 —
     * the legacy `MainButtonsController` stays the sole live touch owner
     * until CR4 calls [attachToViews]).
     *
     * The single-owner guard runs here: each of SPACE/BACKSPACE/ENTER is
     * tagged with [OWNER_TAG] = [OWNER_DORMANT_CR2]. If a View already
     * carries our tag (a second install without a detach), that is a
     * double-build → `Log.wtf` (Strict-Mode-Logging, Spec 2 §10).
     */
    fun installDormant(buttonViews: Map<LogicalButtonId, View>) {
        val space = buttonViews[LogicalButtonId.SPACE] as? MaterialButton
        val backspace = buttonViews[LogicalButtonId.BACKSPACE]
        val enter = buttonViews[LogicalButtonId.ENTER]

        if (space != null) {
            guardSingleOwner(LogicalButtonId.SPACE, space)
            spaceHandler = buildSpaceTouchHandler(space)
        }
        if (backspace != null) {
            guardSingleOwner(LogicalButtonId.BACKSPACE, backspace)
            backspaceHandler = buildBackspaceSwipeHandler()
        }
        if (enter != null) {
            guardSingleOwner(LogicalButtonId.ENTER, enter)
            enterHandler = buildEnterOverlayHandler(enter)
        }
    }

    /**
     * CR4 entry point — attaches the cached §11.7 handlers to the live
     * SPACE/BACKSPACE/ENTER Views. **MUST** be called from the same chunk
     * that removes the legacy `MainButtonsController` touch wiring (never
     * both wired at once — RR-1). Not called in CR2/CR3.
     *
     * Idempotent-safe: re-tags the ownership ledger to
     * [OWNER_ATTACHED_CR4]. A caller that invokes this while the legacy
     * wiring is still live re-introduces the double-wire — the per-View
     * ledger entry transition (`dormant-cr2` → `attached-cr4`) is the
     * audit trail CR4's single-owner proof asserts against.
     */
    fun attachToViews(buttonViews: Map<LogicalButtonId, View>) {
        attachOne(buttonViews, LogicalButtonId.SPACE, spaceHandler)
        attachOne(buttonViews, LogicalButtonId.BACKSPACE, backspaceHandler)
        attachOne(buttonViews, LogicalButtonId.ENTER, enterHandler)
    }

    private fun attachOne(
        buttonViews: Map<LogicalButtonId, View>,
        id: LogicalButtonId,
        handler: View.OnTouchListener?,
    ) {
        val view = buttonViews[id] ?: return
        val h = handler ?: return
        view.setOnTouchListener(h)
        ownerLedger[id] = OWNER_ATTACHED_CR4
    }

    /**
     * The single-owner proof surface for tests / CR4's gate. Returns the
     * ledger entry for [id] (`dormant-cr2` after CR2, `attached-cr4`
     * after CR4), or `null` if we never touched that View — which, post
     * CR2, is the proof that the **legacy** `MainButtonsController`
     * remains the sole *live* `setOnTouchListener` owner.
     */
    fun ownerOf(id: LogicalButtonId): String? = ownerLedger[id]

    // ─── Single-owner guard (Strict-Mode-Logging, Spec 2 §10) ─────────

    private fun guardSingleOwner(id: LogicalButtonId, view: View) {
        val existing = view.getTag(OWNER_TAG_KEY) as? String
        if (existing == OWNER_DORMANT_CR2) {
            // A second dormant-install without an intervening detach. CR2
            // never attaches, so this can't overwrite a live listener —
            // but it signals a lifecycle bug (double attach()).
            Log.wtf(
                TAG,
                "RR-1: SPACE/BACKSPACE/ENTER touch builder ran twice for $id " +
                    "without a detach — double-build (no live overwrite in CR2, " +
                    "but a backend-lifecycle bug). Owner ledger: $ownerLedger",
            )
        }
        view.setTag(OWNER_TAG_KEY, OWNER_DORMANT_CR2)
        ownerLedger[id] = OWNER_DORMANT_CR2
    }

    // ─── §11.7 builders — followed VERBATIM (SoT) ─────────────────────

    /**
     * Spec 2 §11.7 `buildSpaceTouchHandler()` — verbatim. Wraps a
     * [CursorSwipeTouchHandler] and composes `keyPressAnimator` into the
     * outer `OnTouchListener` (legacy `MainButtonsController.kt:217-246`
     * parity), short-circuiting + clearing the compound drawables when no
     * `InputConnection` is available.
     */
    private fun buildSpaceTouchHandler(space: MaterialButton): View.OnTouchListener {
        val swipeHandler = CursorSwipeTouchHandler(
            swipeThresholdPx = CursorSwipeTouchHandler.DEFAULT_SWIPE_THRESHOLD,
            onTap = {
                onVibrate()
                // P4: space insert funnels through the InsertionService
                // (KEYSTROKE policy). Null = no-op, as the legacy null-IC.
                insertionService()?.insert(
                    InsertionRequest(" ", null, InsertionPolicy.KEYSTROKE, null, null))
            },
            onCursorMove = { dir ->
                onVibrate()
                // P4: cursor move funnels through the InsertionService as a
                // CursorMove ControlOp carrying the raw swipe direction; the
                // service owns the selection-safe, grapheme-clamped move (F-021).
                insertionService()?.control(ControlOp.CursorMove(dir))
            },
            onSwipeStateChanged = { isSwiping ->
                if (isSwiping) {
                    space.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        R.drawable.ic_baseline_keyboard_double_arrow_left_24, 0,
                        R.drawable.ic_baseline_keyboard_double_arrow_right_24, 0,
                    )
                } else {
                    space.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                }
            },
            consumeTouchEvents = false,
        )
        return View.OnTouchListener { v, event ->
            keyPressAnimator.handlePressAnimationEvent(v, event)
            if (inputConnectionProvider() == null) {
                space.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                return@OnTouchListener false
            }
            swipeHandler.onTouch(v, event)
        }
    }

    /**
     * Spec 2 §11.7 `buildBackspaceSwipeHandler()` — verbatim. The G3
     * accelerated-delete cascade itself lives in the IME
     * (`onBackspaceLongClicked` → `deleteHandler.postDelayed`, legacy
     * long-press path, attached by CR4). This handler's
     * `onDeleteCancelled` wires to [onBackspaceDeleteCancelled] so a
     * swipe-select **cancels** that cascade — the half F-1 dropped when
     * it removed a bare `{ true }` consumer-listener.
     */
    private fun buildBackspaceSwipeHandler(): View.OnTouchListener =
        BackspaceSwipeHandler(
            inputConnectionProvider = inputConnectionProvider,
            insertionService = insertionService,
            vibrate = onVibrate,
            onDeleteCancelled = onBackspaceDeleteCancelled,
            keyPressAnimationHandler = { v, e -> keyPressAnimator.handlePressAnimationEvent(v, e) },
        )

    /**
     * Spec 2 §11.7 `buildEnterOverlayHandler()` — verbatim. The
     * `overlayCharactersLl` is read off [enter]'s root view (the legacy
     * controller passes the same `R.id.overlay_characters_ll`). Defensive
     * local visibility reset stays handler-internal (§11.7 "Special" —
     * `OverlayResetHandler`, G12, is the additional belt, attached in
     * CR3; this is not a bug, it's defensive depth).
     */
    private fun buildEnterOverlayHandler(enter: View): View.OnTouchListener =
        EnterOverlayHandler(
            overlayCharactersLl = enter.rootView.findViewById(R.id.overlay_characters_ll),
            insertionService = insertionService,
            accentColorProvider = accentColorProvider,
            keyPressAnimationHandler = { v, e -> keyPressAnimator.handlePressAnimationEvent(v, e) },
        )

    companion object {
        private const val TAG = "DictateIME"

        /**
         * `View.setTag(key, ...)` slot for the single-owner marker.
         * Uses a real resource id ([R.id.special_touch_owner_tag]) so it
         * does not collide with framework / app keyed tags.
         */
        private val OWNER_TAG_KEY = R.id.special_touch_owner_tag

        /** Ledger value after CR2 builds the handler (not yet attached). */
        const val OWNER_DORMANT_CR2 = "dormant-cr2"

        /** Ledger value after CR4 attaches the handler to the live View. */
        const val OWNER_ATTACHED_CR4 = "attached-cr4"
    }
}
