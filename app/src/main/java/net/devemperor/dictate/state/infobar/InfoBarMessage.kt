package net.devemperor.dictate.state.infobar

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * The display payload for a single [InfoBarItem] (ADR-0006 §"Hybrid —
 * sealed `InfoBarMessage` (Inhalt+Style) plus `confirmAction/dismissAction`").
 *
 * **Why a plain data class, not a sealed hierarchy?** ADR-0006's chosen
 * trade-off: items are added by [InfoBarSelector] from existing state,
 * and the set of producers grows over time as new state-axes are
 * introduced. A sealed hierarchy would force a central schema edit per
 * new case — the plain-data form lets each producer construct its own
 * `InfoBarMessage` instance locally without coupling to the renderer.
 *
 * Renderer reads only [textResId] / [quantity] + [textArgs] + [style];
 * everything else (titles, sub-titles, icons specific to the case) is
 * encoded in the string resources themselves (i18n-aware).
 *
 * @property textResId Android string resource id for the message body.
 *   Producers MUST use a `@StringRes` literal — or, when [quantity] is
 *   non-null, a `@PluralsRes` literal (the renderer then resolves it via
 *   `getQuantityString`). The renderer resolves the resource against the
 *   IME service's context at render time so the message text follows the
 *   user's locale.
 * @property textArgs format arguments for parameterised strings
 *   (e.g. `dictate_quota_exceeded_msg` carries the provider's display
 *   name). Pass an empty list for un-parameterised strings — the
 *   default avoids common-case verbosity at the call site.
 * @property quantity when non-null, [textResId] is treated as a
 *   `@PluralsRes` id and the renderer picks the plural form for this count
 *   via `getQuantityString(textResId, quantity, *textArgs)`. Pass the
 *   count both here and (if the string interpolates it) as a [textArgs]
 *   element — Android does not auto-substitute the quantity. `null` (the
 *   default) selects the plain-string `getString` path.
 * @property style visual tone — see [InfoBarStyle].
 *
 * @see InfoBarItem
 * @see InfoBarSelector
 * @see docs/decisions/0006-ui-info-bar-state-derived-items.md
 */
data class InfoBarMessage(
    @StringRes @PluralsRes val textResId: Int,
    val textArgs: List<Any> = emptyList(),
    val style: InfoBarStyle = InfoBarStyle.INFO,
    val quantity: Int? = null,
)
