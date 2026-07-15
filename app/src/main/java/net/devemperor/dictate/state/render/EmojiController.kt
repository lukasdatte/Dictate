package net.devemperor.dictate.state.render

import android.util.Log
import android.view.View
import androidx.emoji2.emojipicker.EmojiPickerView
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.DictateUtils
import net.devemperor.dictate.R
import net.devemperor.dictate.state.insertion.InsertionPolicy
import net.devemperor.dictate.state.insertion.InsertionRequest
import net.devemperor.dictate.state.insertion.KeyboardActionDispatcher

/**
 * Owns the **emoji-picker** click/picked listeners — the listeners
 * the legacy `MainButtonsController.registerEmojiListeners()`
 * (`MainButtonsController.kt:278-295`) used to wire before the cutover.
 *
 * # Wiring status (post-CR-DEL — sole live owner)
 *
 * **Sole live owner of the emoji-picker listener axis.** [attachToViews]
 * is the only writer of the emoji click/picked listeners now that
 * `MainButtonsController` is **deleted** (CR-DEL completed the D-13
 * migration). The "build-but-don't-attach / still LIVE until CR4"
 * framing below is **historical** — there is no `MainButtonsController`
 * and no parallel listener writer left; that staged mechanic is the
 * *how* this controller became the live owner, not a current state.
 * `EmojiControllerTest` covers the contract.
 *
 * # Why this class exists (CR4-IMPL-1 resolution)
 *
 * Spec 2 **§13.2** states the emoji listeners **"bleibt in
 * EmojiController"**. That class was never created — the listeners lived
 * inside `MainButtonsController` (the class CR-DEL deleted). This is the
 * sibling of [EditBarController]: the same A3 option-a extraction
 * (binding, CR3-recorded) applied to the emoji axis §3's 16-group
 * render map never enumerated. See [EditBarController] KDoc for the full
 * CR4-IMPL-1 narrative.
 *
 * This class **OWNS** the exact `registerEmojiListeners()` inventory,
 * ported **byte-equivalent**:
 *  - `editEmojiButton.setOnClickListener` → vibrate + emoji-toggle
 *  - `emojiPickerCloseButton.setOnClickListener` → vibrate + emoji-close
 *  - `emojiPickerView.setOnEmojiPickedListener` → vibrate +
 *    `inputConnectionProvider()?.commitText(emoji.emoji, 1)` (null-guarded)
 *
 * # RR-1 — build-but-don't-attach (identical to [EditBarController]) — historical
 *
 * The staged mechanic, recorded as history (`MainButtonsController` is
 * deleted — see "Wiring status" above). During CR-EXTRACT the legacy
 * `MainButtonsController.registerEmojiListeners()` was still LIVE until
 * CR4. [installDormant] only **builds + caches** the listeners + tags
 * the single-owner ledger marker; **CR4** called [attachToViews] *in
 * the same chunk* it removed the legacy wiring and **CR-DEL** then
 * deleted `MainButtonsController` — never both wired at once (RR-1,
 * render-path-cutover.md §11 + §6 RR-1).
 *
 * @property views the emoji view-holder (non-null — built by the IME
 *   service from the inflated tree).
 * @property callback the emoji action sink — a narrow ISP subset of the
 *   legacy `MainButtonsController.Callback` (parity contract).
 * @property keyboardActions the keyboard-action router facade (§4.2): the picked-emoji
 *   commit routes through it so it reaches the PC in PC-mode and the local InsertionService
 *   otherwise (nullable when the IME-View is detached → no-op).
 *
 * @see EditBarController — the sibling owner; full CR4-IMPL-1 narrative.
 * @see SpecialTouchHandlerInstaller — the CR2 staged-pattern precedent.
 * @see docs/plans/2026-05-15 - dictate-cutover-completion/research/render-path-cutover.md §11
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §13.2
 */
class EmojiController(
    private val views: EmojiViews,
    private val callback: Callback,
    private val keyboardActions: () -> KeyboardActionDispatcher?,
) {

    /**
     * The emoji action sink — a deliberately narrow interface (ISP)
     * carrying only the emoji callbacks, a strict subset of the legacy
     * `MainButtonsController.Callback` (parity contract).
     */
    interface Callback {
        fun onVibrate()
        fun onEmojiToggleClicked()
        fun onEmojiCloseClicked()
    }

    private val ownerLedger = mutableMapOf<Int, String>()

    /**
     * CR-EXTRACT entry point. **Builds + caches** the emoji listeners
     * and tags the single-owner ledger marker; does **NOT** attach them
     * to the live Views (RR-1).
     */
    fun installDormant() {
        if (cached != null) {
            Log.wtf(
                TAG,
                "RR-1: EmojiController built twice without a detach — " +
                    "double-build (no live overwrite in CR-EXTRACT, but a " +
                    "lifecycle bug). Owner ledger: $ownerLedger",
            )
        }
        cached = CachedListeners(
            editEmojiClick = View.OnClickListener {
                callback.onVibrate()
                callback.onEmojiToggleClicked()
            },
            emojiCloseClick = View.OnClickListener {
                callback.onVibrate()
                callback.onEmojiCloseClicked()
            },
            // emojiPickerView is an AndroidX widget with its own typed
            // setter (not setOnClickListener) — cache the lambda; attach
            // happens in CR4 [attachToViews] like the click listeners.
            emojiPicked = { emoji ->
                callback.onVibrate()
                if (emoji != null) {
                    // P4: the emoji commit funnels through the single
                    // InsertionService owner (KEYSTROKE policy = instant,
                    // no auto-enter/guard/audit). Null = no-op, as before.
                    keyboardActions()?.insert(
                        InsertionRequest(emoji, null, InsertionPolicy.KEYSTROKE, null, null))
                }
            },
        )
        for (view in allEmojiClickViews()) {
            guardSingleOwner(view)
        }
        // The picker-view itself is tagged too (its picked-listener is
        // the legacy live owner until CR4).
        guardSingleOwner(views.emojiPickerView)
    }

    /**
     * CR4 entry point — attaches the cached emoji listeners to the live
     * Views. **MUST** be called from the same chunk that removes the
     * legacy `MainButtonsController.registerEmojiListeners()` wiring
     * (never both wired at once — RR-1). Not called in CR-EXTRACT.
     */
    fun attachToViews() {
        val c = cached ?: return
        views.editEmojiButton.setOnClickListener(c.editEmojiClick)
        views.emojiPickerCloseButton.setOnClickListener(c.emojiCloseClick)
        val picked = c.emojiPicked
        views.emojiPickerView.setOnEmojiPickedListener { emoji ->
            picked(emoji?.emoji)
        }
        for (view in allEmojiClickViews() + views.emojiPickerView) {
            ownerLedger[view.id] = OWNER_ATTACHED_CR4
            view.setTag(OWNER_TAG_KEY, OWNER_ATTACHED_CR4)
        }
    }

    /**
     * Re-apply accent-colour theming to the emoji buttons (G6 — the
     * edit-row residual the new `ImeViewBackend.applyTheme` does NOT map;
     * sibling to [EditBarController.applyTheme]). Tiers mirror the deleted
     * `MainButtonsController.applyTheme` (`:421/:424`) **exactly**:
     * `editEmojiButton` = accent darkened 0.18; `emojiPickerCloseButton`
     * = the raw accent colour.
     */
    fun applyTheme(accentColor: Int) {
        views.editEmojiButton.setBackgroundColor(DictateUtils.darkenColor(accentColor, 0.18f))
        views.emojiPickerCloseButton.setBackgroundColor(accentColor)
    }

    /**
     * Single-owner proof surface — see [EditBarController.ownerOf].
     */
    fun ownerOf(viewId: Int): String? = ownerLedger[viewId]

    /** Test/CR4 accessor — the cached emoji-toggle click listener. */
    val cachedEditEmojiClick: View.OnClickListener? get() = cached?.editEmojiClick

    /**
     * Test/CR4 accessor — the cached picked-emoji handler (invokes
     * `onVibrate` + `commitText` exactly as the legacy
     * `setOnEmojiPickedListener` body does, null-guarded).
     *
     * F-8 (B5-VAL): exposed as a **property** (not an
     * `invokeEmojiPicked(...)` method) so the EditBar/Emoji sibling
     * pair share one test-accessor idiom — symmetric with
     * [cachedEditEmojiClick] and [EditBarController.cachedEditNumbersClick].
     * Tests call `cachedEmojiPicked?.invoke(e)`.
     */
    val cachedEmojiPicked: ((String?) -> Unit)? get() = cached?.emojiPicked

    private fun guardSingleOwner(view: View) {
        val existing = view.getTag(OWNER_TAG_KEY) as? String
        if (existing == OWNER_DORMANT) {
            Log.wtf(
                TAG,
                "RR-1: EmojiController guard saw view ${view.id} already " +
                    "tagged dormant (double-build). Owner ledger: $ownerLedger",
            )
        }
        view.setTag(OWNER_TAG_KEY, OWNER_DORMANT)
        ownerLedger[view.id] = OWNER_DORMANT
    }

    private fun allEmojiClickViews(): List<View> = listOf(
        views.editEmojiButton,
        views.emojiPickerCloseButton,
    )

    private var cached: CachedListeners? = null

    private class CachedListeners(
        val editEmojiClick: View.OnClickListener,
        val emojiCloseClick: View.OnClickListener,
        val emojiPicked: (String?) -> Unit,
    )

    companion object {
        private const val TAG = "DictateIME"

        /** Shared keyed-tag with [EditBarController] (one owner family). */
        private val OWNER_TAG_KEY = R.id.editbar_emoji_owner_tag

        /** Ledger value after CR-EXTRACT builds the listener (not attached). */
        const val OWNER_DORMANT = "dormant-cr-extract"

        /** Ledger value after CR4 attaches the listener to the live View. */
        const val OWNER_ATTACHED_CR4 = "attached-cr4"
    }
}

/**
 * Emoji view-holder for [EmojiController]. Same inflated-tree views as
 * the legacy `MainButtonViews` carries.
 */
data class EmojiViews(
    val editEmojiButton: MaterialButton,
    val emojiPickerCloseButton: MaterialButton,
    val emojiPickerView: EmojiPickerView,
)
