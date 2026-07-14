package net.devemperor.dictate.state.render

import android.view.View
import androidx.core.content.ContextCompat
import net.devemperor.dictate.R
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.layout.BackendType
import net.devemperor.dictate.state.layout.LayoutMode
import net.devemperor.dictate.state.layout.RenderBackend

/**
 * Drives the edit-bar's PC button (`edit_pc_btn`) from the `features` axis
 * (ADR-0019).
 *
 * Three signals, all derived, none stored:
 *
 *  - **Tint** — purple while [DictateUiState.features] reports PC-mode
 *    active, the bar's default black otherwise.
 *  - **Alpha** — dimmed while no PC is paired, echoing the settings switch
 *    that stays greyed out until pairing completes.
 *
 * **Dimmed, but deliberately NOT `isEnabled = false`.** A disabled View does
 * not deliver long-presses either, and the long-press is how an unpaired user
 * reaches the pairing screen — disabling would strand exactly the people who
 * need it. The tap is instead redirected to pairing by the IME while unpaired,
 * and the reducer rejects a stray toggle as a backstop.
 *
 * # Why a RenderBackend and not an observer
 *
 * The edit-bar's other state-dependent button (audio-focus) is driven
 * imperatively by `EditBarAudioFocusObserver`, a bespoke one-axis collector.
 * That shape is a retrofit — it exists because that button was migrated out
 * of a deleted controller after the render path had already been built. A
 * `RenderBackend` needs no second collector, no lifecycle of its own, and no
 * seed call: `KeyboardLayoutManager` already fans every state emit out to
 * every backend, and `attachBackend` renders once immediately. New
 * state-driven edit-bar buttons belong here rather than growing a second
 * observer per axis.
 *
 * `backendType = null` — like [ContentAreaController], the edit-bar lives
 * outside the layout-mode partition and must render under every mode.
 *
 * @property button `edit_pc_btn`; nullable for the "view not present"
 *   contract the sibling edit-bar renderers use.
 *
 * @see net.devemperor.dictate.state.FeatureToggles.windowsAutoSendActive
 * @see docs/decisions/0019-auto-send-terminal-pipeline-outcome.md
 */
class EditBarPcButtonRenderer(
    private val button: View?,
) : RenderBackend {

    override val backendType: BackendType? = null

    override fun attach(onAction: (Action) -> Unit) = Unit
    override fun detach() = Unit

    override fun render(state: DictateUiState, mode: LayoutMode) {
        val target = button ?: return
        val active = state.features.windowsAutoSendActive
        val paired = state.features.windowsPaired
        val context = target.context

        // mutate() so the tint does not leak into every other view sharing
        // this drawable's constant state (the icon is a shared resource).
        val icon = ContextCompat.getDrawable(context, R.drawable.ic_baseline_computer_24)?.mutate()
        icon?.setTint(
            ContextCompat.getColor(
                context,
                if (active) R.color.dictate_pc_mode else R.color.dictate_black,
            ),
        )
        target.foreground = icon

        target.alpha = if (paired) ALPHA_ENABLED else ALPHA_UNPAIRED
        target.contentDescription = context.getString(
            when {
                !paired -> R.string.dictate_pc_mode_state_unpaired
                active -> R.string.dictate_pc_mode_state_on
                else -> R.string.dictate_pc_mode_state_off
            },
        )
    }

    private companion object {
        const val ALPHA_ENABLED = 1f
        const val ALPHA_UNPAIRED = 0.4f
    }
}
