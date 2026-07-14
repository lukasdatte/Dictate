package net.devemperor.dictate.state.render

import android.util.Log
import android.view.View
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.DictateUtils
import net.devemperor.dictate.R
import net.devemperor.dictate.state.layout.resolveAudioFocusIcon

/**
 * Owns the **edit-bar** click/long-click listeners — the listeners
 * the legacy `MainButtonsController.registerEditBarListeners()`
 * (`MainButtonsController.kt:115-165`) used to wire before the cutover.
 *
 * # Wiring status (post-CR-DEL — sole live owner)
 *
 * **Sole live owner of the edit-bar listener axis.** [attachToViews]
 * is the only writer of the edit-bar click/long-click listeners now
 * that `MainButtonsController` is **deleted** (CR-DEL completed the
 * D-13 migration). The earlier "build-but-don't-attach / legacy stays
 * the sole LIVE owner through CR-EXTRACT / CR4 flips it" framing below
 * is **historical**: there is no `MainButtonsController` and no
 * parallel listener writer left — that staged mechanic is the *how*
 * this controller became the live owner, not a current state.
 * `EditBarControllerTest` covers the contract.
 *
 * # Why this class exists (CR4-IMPL-1 resolution)
 *
 * Spec 2 **§13.2** (the Click-Listener-Audit SoT) states the edit-bar
 * **"bleibt in einem separaten `EditBarController`, der sich nicht
 * ändert"** and the emoji listeners **"bleibt in EmojiController"**.
 * Neither class was ever created by the parent plan — the listeners
 * lived *inside* `MainButtonsController` (the class CR-DEL deleted). When
 * `B5-CR4-IMPL` went to remove `mainButtonsController.registerAllListeners()`
 * (AC-RR-6) it found three sub-registrations with **no new-path owner**
 * (edit-bar / emoji / overlay-chars init) — the exact INT-1 / F-1 / F-2
 * parallel-dormant deferral anti-pattern at the edit-bar layer. This is
 * the **A3 option-a** (extract the BLEIBT parts so the kill-list class
 * fully deletes and AC-RR-7 stays a clean zero-grep) the orchestrator
 * recorded as binding in CR3, recurring for the edit-bar/emoji axes
 * §3's 16-group render map never enumerated.
 *
 * This class **OWNS** the exact `registerEditBarListeners()` listener
 * inventory, ported **byte-equivalent** (same callbacks, same vibrate,
 * same `return true` long-press consumption, same shared
 * `audioFocusClickListener` semantics).
 *
 * # RR-1 — the load-bearing single-owner model (build-but-don't-attach) — historical
 *
 * This is the **staged mechanic** by which this controller became the
 * sole live owner; it is recorded as history, not a current state
 * (`MainButtonsController` is deleted — see "Wiring status" above).
 * During CR-EXTRACT the legacy
 * `MainButtonsController.registerEditBarListeners()` was **still LIVE**
 * — it was removed only by **CR4**. Android keeps **only the
 * most-recent** `setOnClickListener`. The installer runs *after* the
 * legacy wiring (the IME attach point was past `registerAllListeners()`),
 * so a naive `setOnClickListener` would have **silently overwritten** the
 * live legacy edit-bar listeners — a half-broken edit row with **no
 * error** (the exact F-1/F-2 trap, render-path-cutover.md §6 RR-1).
 *
 * **Mitigation — build-but-don't-attach.** [installDormant] only
 * **builds + caches** the listener lambdas and tags the single-owner
 * ledger marker; it does **not** call `setOnClickListener` on the live
 * Views. Through CR-EXTRACT the legacy `MainButtonsController` therefore
 * stayed the **sole LIVE owner** of every edit-bar listener. **CR4**
 * called [attachToViews] *in the same chunk* it removed the legacy
 * `registerAllListeners()`, then **CR-DEL** deleted
 * `MainButtonsController` outright — never both wired at once (identical
 * to the CR2 [SpecialTouchHandlerInstaller] touch model and the CR1
 * RESEND-only long-press model already accepted by the orchestrator).
 *
 * @property views the edit-bar view-holder (non-null — built by the IME
 *   service from the inflated tree).
 * @property callback the edit-bar action sink — the same
 *   `DictateInputMethodService` instance the legacy
 *   `MainButtonsController.Callback` already targets, so behaviour is
 *   byte-identical across the cutover.
 *
 * @see SpecialTouchHandlerInstaller — the same staged pattern for the
 *   touch axis (CR2).
 * @see EmojiController — the sibling owner for the emoji listeners.
 * @see OverlayCharactersController — the overlay-chars owner.
 * @see docs/plans/2026-05-15 - dictate-cutover-completion/research/render-path-cutover.md §11 + §6 RR-1
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §13.2
 */
class EditBarController(
    private val views: EditBarViews,
    private val callback: Callback,
) {

    /**
     * The edit-bar action sink. A deliberately **narrow** interface (ISP)
     * carrying only the edit-bar callbacks — a strict subset of the
     * legacy `MainButtonsController.Callback` so the IME service can
     * implement both with the same method bodies (parity contract).
     */
    interface Callback {
        fun onVibrate()
        fun onSmallModeToggled()
        fun onSingleRowModeToggled()
        fun onSettingsClicked()
        fun onHistoryClicked()

        /**
         * History button **long-press** (Paket 3). Opens the full-screen
         * [net.devemperor.dictate.history.HistoryActivity] (search / audio /
         * detail). The short press ([onHistoryClicked]) opens the in-keyboard
         * history panel instead — the two affordances are split so the fast,
         * in-context list lives on the primary tap and the heavyweight screen
         * on the deliberate long-press.
         */
        fun onHistoryLongClicked()
        fun onPipelineCancelClicked()
        fun onAudioFocusToggled()
        fun onKeyboardToggleClicked()
        fun onKeyboardLongClicked()
        fun onEditAction(actionId: Int)

        /**
         * Edit-bar widget-toggle (2026-05-22). Was previously a main-row
         * slot driven by the catalog's WIDGET_TOGGLE; relocated into
         * the edit-bar so it sits next to settings + small-mode. The
         * IME dispatches the same Action it used to dispatch from the
         * slot's actionResolver (resolveWidgetToggleAction) — permission-
         * aware: emits ShowOverlayOnboarding when overlay permission is
         * missing, ToggleViewModeWidget otherwise.
         */
        fun onWidgetToggleClicked()

        /**
         * PC button **short-press** (ADR-0019) — flips PC send-mode, so the
         * next dictation goes to the paired PC instead of the host field.
         * Mirrors the settings switch; the button is disabled while unpaired.
         */
        fun onPcModeToggled()

        /**
         * PC button **long-press** — opens the pairing screen. Split the same
         * way the history button splits: the everyday toggle sits on the tap,
         * the occasional heavyweight screen on the deliberate long-press.
         * Stays live while the button is disabled, so a user with no PC can
         * still reach pairing from here.
         */
        fun onPcLongClicked()
    }

    /**
     * Shared click listener for the edit-bar audio-focus button —
     * byte-equivalent to the legacy
     * `MainButtonsController.audioFocusClickListener` (the single-row
     * variant is owned by the Main-Button-Area path; this owns only the
     * edit-bar `edit_audio_focus_btn`, §13.2 "BLEIBT — außerhalb der
     * Main-Button-Area").
     */
    private val audioFocusClickListener = View.OnClickListener {
        callback.onVibrate()
        callback.onAudioFocusToggled()
    }

    /**
     * The single-owner ledger: view → the owner string that tagged the
     * View's keyed-tag slot. Populated by [installDormant]; asserted by
     * tests via [ownerOf]. A second build-pass that finds a View already
     * carrying our tag logs `Log.wtf` (a CR-EXTRACT double-wire would be
     * a real regression — RR-1).
     */
    private val ownerLedger = mutableMapOf<Int, String>()

    /**
     * CR-EXTRACT entry point. **Builds + caches** the edit-bar listeners
     * and tags the single-owner ledger marker; does **NOT** attach them
     * to the live Views (RR-1 — the legacy `MainButtonsController` stays
     * the sole live owner until CR4 calls [attachToViews]).
     *
     * The cached lambdas are stored once so [attachToViews] (CR4) wires
     * the *same* instances — no re-allocation, and the single-owner
     * proof can `assertSame`.
     */
    fun installDormant() {
        if (cached != null) {
            // A second dormant-install without an intervening detach.
            // CR-EXTRACT never attaches, so this can't overwrite a live
            // listener — but it signals a lifecycle bug (double attach).
            Log.wtf(
                TAG,
                "RR-1: EditBarController built twice without a detach — " +
                    "double-build (no live overwrite in CR-EXTRACT, but a " +
                    "lifecycle bug). Owner ledger: $ownerLedger",
            )
        }
        cached = CachedListeners(
            editNumbersClick = View.OnClickListener {
                callback.onVibrate()
                callback.onSmallModeToggled()
            },
            // Long-press toggles SingleRowMode; returning `true`
            // suppresses the click that would otherwise follow
            // (legacy `MainButtonsController.kt:126-130` parity).
            editNumbersLong = View.OnLongClickListener {
                callback.onVibrate()
                callback.onSingleRowModeToggled()
                true
            },
            editSettingsClick = View.OnClickListener { callback.onSettingsClicked() },
            editHistoryClick = View.OnClickListener { callback.onHistoryClicked() },
            // Long-press opens the full-screen HistoryActivity; vibrate +
            // return `true` to consume (parity with editNumbersLong/editKeyboardLong).
            editHistoryLong = View.OnLongClickListener {
                callback.onVibrate()
                callback.onHistoryLongClicked()
                true
            },
            pipelineCancelClick = View.OnClickListener { callback.onPipelineCancelClicked() },
            editAudioFocusClick = audioFocusClickListener,
            editKeyboardClick = View.OnClickListener {
                callback.onVibrate()
                callback.onKeyboardToggleClicked()
            },
            editKeyboardLong = View.OnLongClickListener {
                callback.onVibrate()
                callback.onKeyboardLongClicked()
                true
            },
            editWidgetToggleClick = View.OnClickListener {
                callback.onVibrate()
                callback.onWidgetToggleClicked()
            },
            editPcClick = View.OnClickListener {
                callback.onVibrate()
                callback.onPcModeToggled()
            },
            editPcLong = View.OnLongClickListener {
                callback.onVibrate()
                callback.onPcLongClicked()
                true
            },
            // undo/redo/cut/copy/paste — per-button click forwarding the
            // android.R.id.* action id (legacy `:151-164` parity).
            editActionClicks = editActionViews().map { (view, actionId) ->
                view to View.OnClickListener {
                    callback.onVibrate()
                    callback.onEditAction(actionId)
                }
            },
        )
        // Single-owner ledger: tag every edit-bar View dormant.
        for (view in allEditBarViews()) {
            guardSingleOwner(view)
        }
    }

    /**
     * CR4 entry point — attaches the cached edit-bar listeners to the
     * live Views. **MUST** be called from the same chunk that removes
     * the legacy `MainButtonsController.registerEditBarListeners()`
     * wiring (never both wired at once — RR-1). Not called in
     * CR-EXTRACT.
     */
    fun attachToViews() {
        val c = cached ?: return
        views.editNumbersButton.setOnClickListener(c.editNumbersClick)
        views.editNumbersButton.setOnLongClickListener(c.editNumbersLong)
        views.editSettingsButton.setOnClickListener(c.editSettingsClick)
        views.editHistoryButton.setOnClickListener(c.editHistoryClick)
        views.editHistoryButton.setOnLongClickListener(c.editHistoryLong)
        views.pipelineCancelButton.setOnClickListener(c.pipelineCancelClick)
        views.editAudioFocusButton.setOnClickListener(c.editAudioFocusClick)
        views.editKeyboardButton.setOnClickListener(c.editKeyboardClick)
        views.editKeyboardButton.setOnLongClickListener(c.editKeyboardLong)
        views.editWidgetToggleButton?.setOnClickListener(c.editWidgetToggleClick)
        views.editPcButton?.setOnClickListener(c.editPcClick)
        views.editPcButton?.setOnLongClickListener(c.editPcLong)
        for ((view, listener) in c.editActionClicks) {
            view.setOnClickListener(listener)
        }
        for (view in allEditBarViews()) {
            ownerLedger[view.id] = OWNER_ATTACHED_CR4
            view.setTag(OWNER_TAG_KEY, OWNER_ATTACHED_CR4)
        }
    }

    /**
     * Re-apply accent-colour theming to the edit-bar buttons (G6 — Spec 2
     * §9.2 *"Theme-Mutation ist eine separate Achse, nicht state-getrieben"*;
     * the edit-row residual the new `ImeViewBackend.applyTheme` does NOT
     * map — it themes only the 8 logical buttons).
     *
     * Owned here (not in a separate theme class) because [EditBarController]
     * already holds the exact edit-bar [MaterialButton]s — the §9.2
     * "separate Theme-Klasse" intent is satisfied by the owner that also
     * owns the listeners (sibling-faithful, no extra class). Tiers mirror
     * the deleted `MainButtonsController.applyTheme` (`:407-429`) **exactly**:
     * `editKeyboard` = accent darkened 0.35; the other 9 themed edit-bar
     * buttons = accent darkened 0.18. `pipelineCancelButton` is
     * intentionally **not** themed — the legacy `applyTheme` never themed
     * it (byte-identical parity). Imperative (the IME calls it after
     * re-inflate / accent change) — never both this and the legacy call
     * (the legacy `mainButtonsController.applyTheme` is removed by CR-DEL).
     */
    fun applyTheme(accentColor: Int) {
        val accentMedium = DictateUtils.darkenColor(accentColor, 0.18f)
        val accentDark = DictateUtils.darkenColor(accentColor, 0.35f)
        views.editSettingsButton.setBackgroundColor(accentMedium)
        views.editKeyboardButton.setBackgroundColor(accentDark)
        views.editUndoButton.setBackgroundColor(accentMedium)
        views.editRedoButton.setBackgroundColor(accentMedium)
        views.editCutButton.setBackgroundColor(accentMedium)
        views.editCopyButton.setBackgroundColor(accentMedium)
        views.editPasteButton.setBackgroundColor(accentMedium)
        views.editNumbersButton.setBackgroundColor(accentMedium)
        views.editHistoryButton.setBackgroundColor(accentMedium)
        views.editAudioFocusButton.setBackgroundColor(accentMedium)
        // Edit-bar widget-toggle (2026-05-22 relocation) — themed with
        // the same accentMedium tier as the other edit-bar buttons.
        views.editWidgetToggleButton?.setBackgroundColor(accentMedium)
        // The PC button's BACKGROUND is themed like its siblings; its
        // FOREGROUND (the purple "PC-mode is on" tint) is owned by
        // EditBarPcButtonRenderer. Two different axes on two different
        // properties, so there is no writer race here.
        views.editPcButton?.setBackgroundColor(accentMedium)
    }

    /**
     * F-3 (B5-VAL) — re-paint the **edit-bar** audio-focus button's
     * icon + contentDescription to track the audio-focus pref.
     *
     * The deleted legacy `MainButtonsController.refreshAudioFocusIcon`
     * (Spec 2 §13.2 F-4) drove **two** sites: the main-button-area
     * `audioFocusButton` AND the edit-bar `editAudioFocusButton`.
     * Post-CR-DEL only the main-button-area twin is state-driven (the
     * catalog AUDIO_FOCUS slot `iconResolver` →
     * [resolveAudioFocusIconForSlot]). This edit-bar twin had **no**
     * `foreground`/`contentDescription` writer and stayed frozen at the
     * static `volume_off` default (TalkBack never announced the state)
     * — the F-3 parity regression on the always-visible edit bar.
     *
     * Shares the [resolveAudioFocusIcon] SSoT (Spec 2 §13.5.c / Gap 1)
     * with the AUDIO_FOCUS slot so the two twins can never drift. The
     * `foreground` + `contentDescription` writes are byte-equivalent to
     * the legacy edit-bar tier (`MainButtonsController.kt:368-385` — the
     * edit-bar half only; the legacy `audioFocusButton` constantState
     * clone is now the state-reactive slot's job, not duplicated here).
     * Imperative (the IME calls it on the same trigger points the
     * legacy method ran: user toggle, external SP change, initial
     * render) — not state-driven, mirroring the §9.2 separate-axis
     * model the edit bar already follows for theming.
     */
    fun refreshAudioFocusIcon(enabled: Boolean) {
        val context = views.editAudioFocusButton.context
        val drawable = androidx.core.content.ContextCompat.getDrawable(
            context, resolveAudioFocusIcon(enabled),
        )
        val description = context.getString(
            if (enabled) R.string.dictate_audio_focus_state_on
            else R.string.dictate_audio_focus_state_off,
        )
        views.editAudioFocusButton.foreground = drawable
        views.editAudioFocusButton.contentDescription = description
    }

    /**
     * The single-owner proof surface for tests / CR4's gate. Returns the
     * ledger entry for [viewId] (`dormant-cr-extract` after CR-EXTRACT,
     * `attached-cr4` after CR4), or `null` if we never touched that View
     * — which, post CR-EXTRACT, is the proof that the **legacy**
     * `MainButtonsController` remains the sole *live* listener owner.
     */
    fun ownerOf(viewId: Int): String? = ownerLedger[viewId]

    /** Test/CR4 accessor — the cached edit-numbers click listener. */
    val cachedEditNumbersClick: View.OnClickListener? get() = cached?.editNumbersClick

    private fun guardSingleOwner(view: View) {
        val existing = view.getTag(OWNER_TAG_KEY) as? String
        if (existing == OWNER_DORMANT) {
            Log.wtf(
                TAG,
                "RR-1: EditBarController guard saw view ${view.id} already " +
                    "tagged dormant (double-build, no live overwrite in " +
                    "CR-EXTRACT). Owner ledger: $ownerLedger",
            )
        }
        view.setTag(OWNER_TAG_KEY, OWNER_DORMANT)
        ownerLedger[view.id] = OWNER_DORMANT
    }

    private fun editActionViews(): List<Pair<MaterialButton, Int>> = listOf(
        views.editUndoButton to android.R.id.undo,
        views.editRedoButton to android.R.id.redo,
        views.editCutButton to android.R.id.cut,
        views.editCopyButton to android.R.id.copy,
        views.editPasteButton to android.R.id.paste,
    )

    private fun allEditBarViews(): List<View> = listOfNotNull(
        views.editNumbersButton,
        views.editSettingsButton,
        views.editHistoryButton,
        views.pipelineCancelButton,
        views.editAudioFocusButton,
        views.editKeyboardButton,
        views.editUndoButton,
        views.editRedoButton,
        views.editCutButton,
        views.editCopyButton,
        views.editPasteButton,
        views.editWidgetToggleButton,
        views.editPcButton,
    )

    private var cached: CachedListeners? = null

    private class CachedListeners(
        val editNumbersClick: View.OnClickListener,
        val editNumbersLong: View.OnLongClickListener,
        val editSettingsClick: View.OnClickListener,
        val editHistoryClick: View.OnClickListener,
        val editHistoryLong: View.OnLongClickListener,
        val pipelineCancelClick: View.OnClickListener,
        val editAudioFocusClick: View.OnClickListener,
        val editKeyboardClick: View.OnClickListener,
        val editKeyboardLong: View.OnLongClickListener,
        val editWidgetToggleClick: View.OnClickListener,
        val editPcClick: View.OnClickListener,
        val editPcLong: View.OnLongClickListener,
        val editActionClicks: List<Pair<MaterialButton, View.OnClickListener>>,
    )

    companion object {
        private const val TAG = "DictateIME"

        /**
         * `View.setTag(key, ...)` slot for the single-owner marker.
         * Shared with [EmojiController] (one keyed-tag id for the
         * edit-bar/emoji owner family — they never tag the same View).
         */
        private val OWNER_TAG_KEY = R.id.editbar_emoji_owner_tag

        /** Ledger value after CR-EXTRACT builds the listener (not attached). */
        const val OWNER_DORMANT = "dormant-cr-extract"

        /** Ledger value after CR4 attaches the listener to the live View. */
        const val OWNER_ATTACHED_CR4 = "attached-cr4"
    }
}

/**
 * Edit-bar view-holder for [EditBarController].
 *
 * A typed data class makes the controller's dependencies explicit and
 * lets unit tests pass simple fake views without `findViewById`. The
 * concrete views are the same `MaterialButton` instances the legacy
 * `MainButtonViews` carries (the IME service builds both holders from
 * the same inflated tree).
 */
data class EditBarViews(
    val editNumbersButton: MaterialButton,
    val editSettingsButton: MaterialButton,
    val editHistoryButton: MaterialButton,
    val pipelineCancelButton: MaterialButton,
    val editAudioFocusButton: MaterialButton,
    val editKeyboardButton: MaterialButton,
    val editUndoButton: MaterialButton,
    val editRedoButton: MaterialButton,
    val editCutButton: MaterialButton,
    val editCopyButton: MaterialButton,
    val editPasteButton: MaterialButton,
    /**
     * Edit-bar widget-toggle (2026-05-22). Nullable so existing tests
     * built before the relocation keep compiling without listing the
     * new field. Production code passes the concrete view from the
     * inflated tree.
     */
    val editWidgetToggleButton: MaterialButton? = null,
    /**
     * Edit-bar PC send-mode toggle (ADR-0019). Nullable for the same reason as
     * [editWidgetToggleButton]: tests built before it existed keep compiling
     * without listing it.
     */
    val editPcButton: MaterialButton? = null,
)
