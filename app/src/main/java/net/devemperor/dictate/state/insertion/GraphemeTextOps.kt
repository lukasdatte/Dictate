package net.devemperor.dictate.state.insertion

import java.text.BreakIterator

/**
 * The single home of grapheme-cluster math for the insertion subsystem.
 *
 * Text editors count in UTF-16 code units, but *users* delete and see whole
 * grapheme clusters: an astral emoji is one surrogate pair, a skin-tone emoji
 * is a base + modifier, a family emoji is a ZWJ sequence, a flag is two
 * regional-indicator symbols, an accented letter can be base + combining mark.
 * Deleting or animating one UTF-16 unit at a time shreds all of these.
 *
 * This object owns the two operations the callers need:
 *
 * - [lastGraphemeUnitCount] — how many UTF-16 units the *last* cluster before
 *   the cursor spans, so a single backspace removes exactly one visible
 *   character (see [ControlOp.DeleteGrapheme] / F-018).
 * - [graphemeClusters] — split a string into whole clusters so the slow-output
 *   animator commits one *complete* cluster per tick instead of lone surrogates
 *   (see [SlowOutputAnimator] / F-020).
 *
 * Pure Kotlin so it is exercised in plain JVM unit tests. It uses
 * [java.text.BreakIterator] (ICU-backed on Android and on the JVM test runtime,
 * unlike `android.icu.text.BreakIterator`, which is stubbed to a no-op under
 * `unitTests.returnDefaultValues`). A defensive surrogate-pair fallback keeps a
 * split from ever landing inside a surrogate pair even on a degenerate
 * BreakIterator.
 *
 * @see ControlOp.DeleteGrapheme
 * @see SlowOutputAnimator
 * @see docs/research/2026-07-02 - feature-wiring-code-review.md (F-018, F-020)
 */
object GraphemeTextOps {

    /**
     * Number of trailing UTF-16 code units that form the last grapheme cluster
     * of [before] (the text immediately preceding the cursor). Always ≥ 1 when
     * [before] is non-empty, so a backspace never becomes a no-op; `0` only for
     * empty input.
     *
     * Used by [ControlOp.DeleteGrapheme] to size a `deleteSurroundingText`
     * call so a single backspace removes exactly one visible character rather
     * than half a surrogate pair or one code point of a ZWJ sequence.
     */
    fun lastGraphemeUnitCount(before: String): Int {
        if (before.isEmpty()) return 0

        val end = before.length
        val it = BreakIterator.getCharacterInstance()
        it.setText(before)
        var start = it.preceding(end)
        if (start == BreakIterator.DONE) {
            start = codePointStart(before, end)
        }
        // Never split a surrogate pair, even if a degenerate BreakIterator put
        // the boundary between the high and low surrogate.
        if (start in 1 until end && Character.isLowSurrogate(before[start]) &&
            Character.isHighSurrogate(before[start - 1])
        ) {
            start -= 1
        }
        return (end - start).coerceAtLeast(1)
    }

    /**
     * Split [text] into whole grapheme clusters, in order. Joining the result
     * reproduces [text] exactly. An empty string yields an empty list.
     *
     * The [SlowOutputAnimator] commits one element per tick, so every commit
     * carries a complete, renderable cluster — no lone surrogates flashed into
     * the host editor mid-animation.
     */
    fun graphemeClusters(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        val clusters = ArrayList<String>(text.length)
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            clusters += text.substring(start, end)
            start = end
            end = it.next()
        }
        return if (clusters.isEmpty()) fallbackByCodePoint(text) else clusters
    }

    /** Start index of the code point ending at [end] (surrogate-pair aware). */
    private fun codePointStart(s: String, end: Int): Int =
        try {
            s.offsetByCodePoints(end, -1)
        } catch (_: IndexOutOfBoundsException) {
            (end - 1).coerceAtLeast(0)
        }

    /** Code-point split used only if BreakIterator degenerates to nothing. */
    private fun fallbackByCodePoint(text: String): List<String> {
        val out = ArrayList<String>(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            out += text.substring(i, i + n)
            i += n
        }
        return out
    }
}
