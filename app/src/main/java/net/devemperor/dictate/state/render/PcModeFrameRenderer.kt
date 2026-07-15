package net.devemperor.dictate.state.render

import net.devemperor.dictate.state.DictateUiState

/**
 * Single side-channel writer for the PC-mode frame (§7.1, D4).
 *
 * Paints a purple `dictate_pc_mode` frame **as the root container's `foreground`** whenever PC-mode
 * (`state.features.windowsAutoSendActive`) is on, and clears it otherwise. A foreground drawable
 * draws **over all children**, so the frame stays visible in every LayoutMode — including an open
 * Review / History / Emoji panel — which is exactly the panel-occlusion weakness the rejected
 * border variant had (§7.1). The accent colour and every button colour are untouched.
 *
 * The Android specifics (`root.foreground = drawable`, the contentDescription) live in the injected
 * [applyFrame] lambda, so this class is pure and JVM-testable like [RecordButtonColorController] and
 * [RecordingAnimationController] — the same idempotent `onState` / `reset` discipline.
 *
 * @property applyFrame `true` → set the frame + "PC mode active" description; `false` → clear both.
 */
class PcModeFrameRenderer(
    private val applyFrame: (active: Boolean) -> Unit,
) {

    private var lastActive: Boolean? = null

    /** Idempotent reactive entry point — called from the IME's render side-channel. */
    fun onState(state: DictateUiState) {
        val active = state.features.windowsAutoSendActive
        if (active == lastActive) return
        applyFrame(active)
        lastActive = active
    }

    /** Drop the idempotency cache so the next [onState] applies unconditionally (call on detach). */
    fun reset() {
        lastActive = null
    }
}
