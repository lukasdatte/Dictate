package net.devemperor.dictate.core

/** What is shown in the main content area? (mutually exclusive) */
enum class ContentArea {
    MAIN_BUTTONS,   // Normal buttons (Record, Space, Enter, etc.)
    QWERTZ,         // QWERTZ keyboard
    EMOJI_PICKER    // Emoji picker
}
