package net.devemperor.dictate.companion.domain.model

import net.devemperor.dictate.shared.protocol.InputCommandKindWire
import net.devemperor.dictate.shared.protocol.InputCommandWire
import net.devemperor.dictate.shared.protocol.InputOutcomeWire

/**
 * One keyboard action to perform on this PC, in domain terms (not wire terms).
 *
 * Two shapes because they take two entirely different paths in the performer: [Type] goes through
 * the existing `TextInserter` (clipboard + Ctrl+V, ADR-0018), every [Key] is resolved to a
 * [KeyChord] and injected via `SendInput`.
 */
sealed interface InputCommand {

    /** Insert a literal text (a text pill, an emoji, a dictation fragment — D3). Never a chord. */
    data class Type(val text: String) : InputCommand

    /** A configurable key action, repeated [count] times (coalesced cursor/backspace bursts, §4.3.2). */
    data class Key(val command: KeyCommand, val count: Int) : InputCommand
}

/**
 * What became of a batch of input commands. Only [SENT] is a success (mirrors `delivered`,
 * ADR-0017): a keyboard action has no clipboard fallback surface, so anything short of "injected"
 * must reach the phone as a failure it can show the user (Entscheidung 4).
 */
enum class InputOutcome {
    /** Every command was injected into the foreground window. */
    SENT,

    /** There was no foreground window to receive the input — nothing was sent. */
    NO_FOREGROUND_WINDOW,

    /** UIPI (an elevated target) rejected the injection in part or whole — treated as not sent. */
    REJECTED,
}

/** Maps a validated wire command to its domain shape. `text` presence is guaranteed by [net.devemperor.dictate.shared.protocol.Validations]. */
fun InputCommandWire.toDomain(): InputCommand = when (kind) {
    InputCommandKindWire.TYPE_TEXT ->
        InputCommand.Type(requireNotNull(text) { "TYPE_TEXT without text passed validation — impossible" })
    else -> InputCommand.Key(kind.toKeyCommand(), count)
}

/** The wire kind → domain [KeyCommand] mapping. Exhaustive by hand (no `valueOf`) so a new kind fails to compile here. */
fun InputCommandKindWire.toKeyCommand(): KeyCommand = when (this) {
    InputCommandKindWire.TYPE_TEXT -> error("TYPE_TEXT is not a KeyCommand — route it as InputCommand.Type")
    InputCommandKindWire.BACKSPACE -> KeyCommand.BACKSPACE
    InputCommandKindWire.ENTER -> KeyCommand.ENTER
    InputCommandKindWire.SPACE -> KeyCommand.SPACE
    InputCommandKindWire.CURSOR_LEFT -> KeyCommand.CURSOR_LEFT
    InputCommandKindWire.CURSOR_RIGHT -> KeyCommand.CURSOR_RIGHT
    InputCommandKindWire.CURSOR_WORD_SELECT_BACK -> KeyCommand.CURSOR_WORD_SELECT_BACK
    InputCommandKindWire.CURSOR_WORD_SELECT_FORWARD -> KeyCommand.CURSOR_WORD_SELECT_FORWARD
    InputCommandKindWire.SELECT_ALL -> KeyCommand.SELECT_ALL
    InputCommandKindWire.CUT -> KeyCommand.CUT
    InputCommandKindWire.COPY -> KeyCommand.COPY
    InputCommandKindWire.PASTE -> KeyCommand.PASTE
    InputCommandKindWire.UNDO -> KeyCommand.UNDO
    InputCommandKindWire.REDO -> KeyCommand.REDO
}

fun InputOutcome.toWire(): InputOutcomeWire = when (this) {
    InputOutcome.SENT -> InputOutcomeWire.SENT
    InputOutcome.NO_FOREGROUND_WINDOW -> InputOutcomeWire.NO_FOREGROUND_WINDOW
    InputOutcome.REJECTED -> InputOutcomeWire.REJECTED
}
