package net.devemperor.dictate.core

import android.view.inputmethod.EditorInfo

/**
 * Heuristic for "is this the same editor field?".
 *
 * Used by the resend-button short-press path (Phase 5) to decide whether the
 * live [InputConnection][android.view.inputmethod.InputConnection] obtained
 * from `getCurrentInputConnection()` still belongs to the editor that was
 * focused when the click was registered. If yes, we prefer the live IC; if
 * no, we fall back to the captured IC that was kept around since the click.
 *
 * Identity is approximated via [EditorInfo.fieldId] + [EditorInfo.packageName]:
 *
 * - `fieldId` is documented by Android as "the identifier of the edit field",
 *   typically the View hash at creation time. After a View recreation
 *   (Activity restart, configuration change, ...) the id changes even though
 *   the user sees the "same" field. That is acceptable: a `false` here only
 *   demotes us from Stage 1 to Stage 2 in the resend strategy, which is the
 *   more conservative path anyway.
 * - On some devices/APIs `fieldId` is `0` or unset, which produces `false`
 *   here. Same fallback consequence — Stage 2 is still robust.
 *
 * This is therefore a best-case optimizer for Stage 1, never a correctness
 * gate. Worst case: we always fall through to Stage 2 (captured IC) or
 * Stage 3 (resume job).
 */
object EditorIdentity {
    fun isSame(a: EditorInfo?, b: EditorInfo?): Boolean =
        a != null && b != null &&
            a.fieldId == b.fieldId &&
            a.packageName == b.packageName
}
