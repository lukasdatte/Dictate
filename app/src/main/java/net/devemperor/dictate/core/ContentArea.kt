package net.devemperor.dictate.core

/**
 * What is shown in the main content area? (mutually exclusive)
 *
 * # State-driven vs. derived values
 *
 * Three of the four values are **state-driven** — they appear in
 * `DictateUiState.layout.contentArea` and are mutated by reducers
 * (LayoutModule). The user picks them indirectly via emoji-toggle,
 * QWERTZ-toggle, or the small-mode side-effect.
 *
 * [HIDDEN_STRIP] is **derived, never stored.** The reducer never
 * writes it into state; instead `ContentAreaController` substitutes
 * it at render-time when the user-toggled widget overlay is visible —
 * see that controller's KDoc for the override. Adding HIDDEN_STRIP to
 * the enum keeps the visibility decision in one `when` block instead
 * of scattering an "is the IME hidden right now?" check next to every
 * container write.
 */
enum class ContentArea {
    /** Normal buttons (Record, Space, Enter, etc.) — the default. */
    MAIN_BUTTONS,

    /** QWERTZ keyboard. */
    QWERTZ,

    /** Emoji picker. */
    EMOJI_PICKER,

    /**
     * IME effectively hidden — only a thin strip is rendered in place
     * of the full keyboard. Used while the user has the floating
     * widget overlay open so the IME does not visually compete with
     * it. Render-derived from `state.widget`; never stored.
     */
    HIDDEN_STRIP,
}
