package net.devemperor.dictate.state.render

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import net.devemperor.dictate.R

/**
 * Owns the **overlay-characters** strip — both the one-time structural
 * inflate (`MainButtonsController.initializeOverlayCharacters()`,
 * `MainButtonsController.kt:299-313`) and the per-call content/theme
 * update (`MainButtonsController.updateOverlayCharacters(characters,
 * accentColor)`, `:451-463`).
 *
 * # Why this class exists (CR4-IMPL-1 resolution)
 *
 * Spec 2 **§13.1 row 13** marks the per-slot `overlayCharactersLl`
 * **"BLEIBT (Theme-internal, separate Animations-/Theme-Klasse)"** and
 * **§9.2** marks `updateOverlayCharacters` (`:481-493`) **"bleibt —
 * overlay-spezifisch"**. Neither §13 nor §9.2 names a concrete class —
 * only "separate Animations-/Theme-Klasse". `OverlayCharactersController`
 * is the **spec-faithful proposed name** (sibling-naming convention to
 * [ContentAreaController]; it owns both *init* (structural) and *update*
 * (theme/content) — a controller-grade responsibility, not a
 * single-shot handler). It is **distinct** from [OverlayResetHandler]
 * (G12, attached CR3): §13.1 explicitly has *two separate rows* — row
 * 11 (`overlayCharactersLl` defensive **reset** → `OverlayResetHandler`)
 * vs. row 13 (per-slot content/theme → this class). Different concern,
 * different owner.
 *
 * The `initializeOverlayCharacters` + `updateOverlayCharacters`
 * sub-axis is the third NO-owner sub-registration of
 * `MainButtonsController.registerAllListeners()` that blocked
 * `B5-CR4-IMPL` (CR4-IMPL-1) — see [EditBarController] KDoc for the
 * full narrative.
 *
 * # RR-2 — the staged-safety-net (build-but-dormant, [RenderGate])
 *
 * Unlike the click-listener owners ([EditBarController]/[EmojiController]
 * use the CR2 `installDormant`/`attachToViews` listener-overwrite
 * model), the overlay-chars axis is a **repeated write** (the same 8
 * char-view `visibility`/`text`/tint fields, plus a one-time inflate).
 * It therefore reuses the **CR3 [RenderGate]** dormant/`arm()` model
 * (the same one [ContentAreaController] uses):
 *
 *  - **dormant (CR-EXTRACT default):** [initialize] does **not** inflate
 *    the 8 char views, [update] does **not** write — both report the
 *    *intended* write to the audit ledger only. The legacy
 *    `MainButtonsController.initializeOverlayCharacters()` /
 *    `updateOverlayCharacters()` stay the **sole live owner**.
 *    (Inflating while the legacy also inflates would produce **16**
 *    child views — the structural analogue of a double-write, RR-2.)
 *  - **armed (CR4 `arm()`):** [initialize] inflates (idempotent — a
 *    `childCount` guard prevents a double-inflate) and [update] writes
 *    for real. CR4 calls [arm] *in the same chunk* it removes the
 *    legacy `registerAllListeners()` / `updateOverlayCharacters` drive
 *    — never two live owners at once (RR-2,
 *    render-path-cutover.md §11 + §6 RR-2).
 *
 * A `null` gate = legacy "always do it" (the pre-CR-EXTRACT contract;
 * keeps unit-test semantics simple — identical to
 * [ContentAreaController]).
 *
 * @property views the overlay-chars view-holder (the same
 *   `overlay_characters_ll` `LinearLayout` the legacy
 *   `MainButtonViews` carries).
 * @property gate the dormant/armed staged-safety-net switch (RR-2).
 *   `null` = always write (legacy contract / unit tests).
 *
 * @see ContentAreaController — the same [RenderGate] dormant model (CR3).
 * @see OverlayResetHandler — the *defensive-reset* belt (distinct
 *   concern, §13.1 row 11 vs. this row 13).
 * @see EditBarController — the sibling owner; full CR4-IMPL-1 narrative.
 * @see docs/plans/2026-05-15 - dictate-cutover-completion/research/render-path-cutover.md §11 + §6 RR-2
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §13.1 row 13 + §9.2
 */
class OverlayCharactersController(
    private val views: OverlayCharactersViews,
    private val gate: RenderGate? = null,
) {

    /**
     * One-time structural inflate — byte-equivalent to
     * `MainButtonsController.initializeOverlayCharacters()`
     * (`:299-313`): 8 `item_overlay_characters` TextViews with the
     * rounded-stroke `GradientDrawable` background.
     *
     * Dormant (gate not armed) → reports the intended structural write
     * to the ledger, does **not** inflate (the legacy controller stays
     * the sole live inflater — inflating here too would double the
     * child count, RR-2). Armed / `null` gate → inflate, guarded
     * idempotent on `childCount` so a re-arm or view-recreate cannot
     * stack a second set of 8.
     */
    fun initialize() {
        val ll = views.overlayCharactersStrip
        if (!shouldWrite(ll)) return
        if (ll.childCount >= OVERLAY_CHAR_COUNT) return  // idempotent guard
        val context = ll.context
        val density = context.resources.displayMetrics.density
        for (i in 0 until OVERLAY_CHAR_COUNT) {
            val charView = LayoutInflater.from(context)
                .inflate(R.layout.item_overlay_characters, ll, false) as TextView
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = (4 * density + 0.5f)
                setStroke((1 * density + 0.5f).toInt(), Color.BLACK)
            }
            charView.background = bg
            ll.addView(charView)
        }
    }

    /**
     * Per-call content/theme update — byte-equivalent to
     * `MainButtonsController.updateOverlayCharacters(characters,
     * accentColor)` (`:451-463`).
     *
     * Dormant → reports the intended write to the ledger, does **not**
     * mutate the char views (legacy stays the sole live writer, RR-2).
     * Armed / `null` gate → writes for real.
     */
    fun update(characters: String, accentColor: Int) {
        val ll = views.overlayCharactersStrip
        if (!shouldWrite(ll)) return
        for (i in 0 until ll.childCount) {
            val charView = ll.getChildAt(i) as TextView
            if (i >= characters.length) {
                charView.visibility = View.GONE
            } else {
                charView.visibility = View.VISIBLE
                charView.text = characters.substring(i, i + 1)
                val bg = charView.background as GradientDrawable
                bg.setColor(accentColor)
            }
        }
    }

    /**
     * Gate-routing identical to the CR3 controllers'
     * `writeVisibility` seam: `null` gate = always act (legacy
     * contract); a gate reports the intended write to the audit ledger
     * and returns `true` only when armed (CR4).
     */
    private fun shouldWrite(ll: LinearLayout): Boolean {
        val g = gate ?: return true
        return g.shouldWrite(ll.id, View.VISIBLE)
    }

    companion object {
        /**
         * The legacy `initializeOverlayCharacters` inflates exactly 8
         * slots (`MainButtonsController.kt:302` `for (i in 0 until 8)`)
         * — kept as a named constant so the idempotent guard and the
         * inflate loop can't drift apart.
         */
        const val OVERLAY_CHAR_COUNT = 8
    }
}

/**
 * View-holder for [OverlayCharactersController]. The single
 * `overlay_characters_ll` `LinearLayout` is the same instance the
 * legacy `MainButtonViews` carries (built by the IME from the inflated
 * tree).
 */
data class OverlayCharactersViews(
    val overlayCharactersStrip: LinearLayout,
)
