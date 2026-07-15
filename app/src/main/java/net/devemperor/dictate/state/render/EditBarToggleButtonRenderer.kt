package net.devemperor.dictate.state.render

import android.view.View
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import net.devemperor.dictate.R
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.layout.BackendType
import net.devemperor.dictate.state.layout.LayoutMode
import net.devemperor.dictate.state.layout.RenderBackend

/**
 * Drives an edit-bar **mode toggle** — a button whose icon lights up while its
 * mode is on, and dims while the mode cannot be turned on at all.
 *
 * Two buttons share this shape today: PC send-mode (on when auto-send is
 * active, unavailable with no PC paired) and screen context (on when the
 * opt-in is set, unavailable while the accessibility service is off). They are
 * the same widget with different nouns, so they are one class — a second copy
 * would drift the moment one of them grew a state the other did not.
 *
 * # Three signals
 *
 *  - **Tint** — [activeColorRes] while on, the bar's default black otherwise.
 *  - **Alpha** — dimmed while unavailable, so "not set up" is visible.
 *  - **contentDescription** — a distinct string per state, because none of the
 *    above survives TalkBack.
 *
 * # Dimmed, deliberately NOT `isEnabled = false`
 *
 * A disabled View delivers no long-press — and the long-press is precisely the
 * way out of the unavailable state (pair a PC / open the a11y setup screen).
 * Disabling would strand exactly the users who need it. The reducers reject
 * the toggle as the real guard; this class only communicates.
 *
 * # Why a RenderBackend and not an observer
 *
 * `EditBarAudioFocusObserver` — the edit-bar's other state-driven button —
 * runs a bespoke one-axis collector. That is a retrofit from when the button
 * was migrated out of a deleted controller after the render path already
 * existed. `KeyboardLayoutManager` already fans every state emit out to every
 * backend and renders once on attach, so this needs no collector, no
 * lifecycle, and no seed call.
 *
 * `backendType = null` — like [ContentAreaController], the edit-bar sits
 * outside the layout-mode partition and renders under every mode.
 *
 * @property button nullable for the "view not present" contract the sibling
 *   edit-bar renderers use.
 * @property selector reads the two booleans off the state. A lambda rather
 *   than a subclass: the difference between these buttons is data, not
 *   behaviour.
 */
class EditBarToggleButtonRenderer(
    private val button: View?,
    @DrawableRes private val iconRes: Int,
    @ColorRes private val activeColorRes: Int,
    private val descriptions: Descriptions,
    private val selector: (DictateUiState) -> Status,
) : RenderBackend {

    /**
     * @property active the mode is on.
     * @property available the mode *can* be turned on — a PC is paired / the
     *   accessibility service is enabled.
     */
    data class Status(val active: Boolean, val available: Boolean)

    /** The three contentDescriptions this button can have. */
    data class Descriptions(
        @StringRes val on: Int,
        @StringRes val off: Int,
        @StringRes val unavailable: Int,
    )

    override val backendType: BackendType? = null

    override fun attach(onAction: (Action) -> Unit) = Unit
    override fun detach() = Unit

    override fun render(state: DictateUiState, mode: LayoutMode) {
        val target = button ?: return
        val status = selector(state)
        val context = target.context

        // mutate() so the tint does not leak into every other view sharing this
        // drawable's constant state — icons are shared resources.
        val icon = ContextCompat.getDrawable(context, iconRes)?.mutate()
        icon?.setTint(
            ContextCompat.getColor(
                context,
                if (status.active) activeColorRes else R.color.dictate_black,
            ),
        )
        target.foreground = icon

        target.alpha = if (status.available) ALPHA_AVAILABLE else ALPHA_UNAVAILABLE
        target.contentDescription = context.getString(
            when {
                !status.available -> descriptions.unavailable
                status.active -> descriptions.on
                else -> descriptions.off
            },
        )
    }

    private companion object {
        const val ALPHA_AVAILABLE = 1f
        const val ALPHA_UNAVAILABLE = 0.4f
    }
}
