package net.devemperor.dictate.state.layout

import net.devemperor.dictate.R
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.HostEditorState
import net.devemperor.dictate.state.ModuleServices
import net.devemperor.dictate.state.canCommitToHost

/**
 * Semantic role the Enter-button plays in the currently-focused host
 * editor — derived purely from [HostEditorState] (Rohwerte → Role).
 *
 * Stored nowhere — produced on demand by [resolveEnterRole]. Keeping
 * the role out of the state respects single-source-of-truth: the
 * `EditorInfo` snapshot is the truth, every derivation
 * (Icon / Action / Effect-variant) reads it. A stored role would have
 * to be re-stamped on every editor switch and could silently drift if a
 * code path forgot to update it.
 *
 * **Edge-case priority** (`resolveEnterRole`):
 *  1. `hasNoEnterAction` → [NEWLINE] (app's explicit opt-out)
 *  2. `isMultiLine` → [NEWLINE] (line breaks must reach the editor)
 *  3. `customActionId != 0` → [CUSTOM] (app installed a custom action)
 *  4. `imeActionId` → matching role
 *  5. Fallback → [NEWLINE]
 *
 * @see actionIdForEnter
 * @see resolveEnterRole
 * @see resolveEnterIcon
 * @see resolveEnterAction
 */
enum class EnterButtonRole {
    /** Plain newline — `commitText("\n", 1)`. */
    NEWLINE,

    /** `IME_ACTION_GO` (browser address bar). */
    GO,

    /** `IME_ACTION_SEARCH` (search field). */
    SEARCH,

    /** `IME_ACTION_SEND` (chat / mail compose). */
    SEND,

    /** `IME_ACTION_NEXT` (multi-field form, focus next). */
    NEXT,

    /** `IME_ACTION_PREVIOUS` (focus previous). */
    PREVIOUS,

    /** `IME_ACTION_DONE` (close the IME). */
    DONE,

    /**
     * App-defined custom action — driven by `EditorInfo.actionId`
     * (non-zero) together with `actionLabel`. The handler calls
     * `performEditorAction(customActionId)`.
     */
    CUSTOM,
}

// ─── IME-Action-Konstanten (mirroring android.view.inputmethod.EditorInfo) ───
// Kept as locals so the resolver stays a pure-Kotlin JVM-testable function
// without an android.view.inputmethod.EditorInfo import. Values stable since
// API 1; see EditorInfo source.
internal const val IME_ACTION_UNSPECIFIED = 0
internal const val IME_ACTION_NONE = 1
internal const val IME_ACTION_GO = 2
internal const val IME_ACTION_SEARCH = 3
internal const val IME_ACTION_SEND = 4
internal const val IME_ACTION_NEXT = 5
internal const val IME_ACTION_DONE = 6
internal const val IME_ACTION_PREVIOUS = 7

/**
 * Derives the [EnterButtonRole] from a [HostEditorState] per the
 * priority order documented on [EnterButtonRole]. Pure function, no
 * Android dependency, fully unit-testable from JVM tests.
 *
 * **Note on `hasEditorInfo`:** This function does **not** branch on
 * `hasEditorInfo`. The pre-bind window (no `EditorInfo` ever attached)
 * is handled one layer up in `KeyboardInputModule.reduceEnterKey` —
 * there it emits `Effect.SendPhysicalEnter` directly. Once an editor
 * is bound, this function picks the right role for every subsequent
 * Enter-tap.
 */
fun resolveEnterRole(host: HostEditorState): EnterButtonRole {
    if (host.hasNoEnterAction) return EnterButtonRole.NEWLINE
    if (host.isMultiLine) return EnterButtonRole.NEWLINE
    if (host.customActionId != 0) return EnterButtonRole.CUSTOM
    return when (host.imeActionId) {
        IME_ACTION_GO -> EnterButtonRole.GO
        IME_ACTION_SEARCH -> EnterButtonRole.SEARCH
        IME_ACTION_SEND -> EnterButtonRole.SEND
        IME_ACTION_NEXT -> EnterButtonRole.NEXT
        IME_ACTION_PREVIOUS -> EnterButtonRole.PREVIOUS
        IME_ACTION_DONE -> EnterButtonRole.DONE
        // UNSPECIFIED / NONE / unknown future values
        else -> EnterButtonRole.NEWLINE
    }
}

/**
 * The integer action-id the handler must hand to
 * `InputConnection.performEditorAction(...)` for the given role.
 *
 * For [EnterButtonRole.CUSTOM] this is the app's `EditorInfo.actionId`
 * (carried in `host.customActionId`); for the standard IME-action
 * roles it is the matching `EditorInfo.IME_ACTION_*` constant. Returns
 * `0` for [EnterButtonRole.NEWLINE] — the handler doesn't read the
 * actionId in that branch, but `0` is a safe sentinel.
 */
fun actionIdForEnter(role: EnterButtonRole, host: HostEditorState): Int = when (role) {
    EnterButtonRole.NEWLINE -> 0
    EnterButtonRole.GO -> IME_ACTION_GO
    EnterButtonRole.SEARCH -> IME_ACTION_SEARCH
    EnterButtonRole.SEND -> IME_ACTION_SEND
    EnterButtonRole.NEXT -> IME_ACTION_NEXT
    EnterButtonRole.PREVIOUS -> IME_ACTION_PREVIOUS
    EnterButtonRole.DONE -> IME_ACTION_DONE
    EnterButtonRole.CUSTOM -> host.customActionId
}

/**
 * Picks the Enter-button drawable for the current host editor. Mirrors
 * the legacy `updateEnterButtonIcon` mapping so the visual remains
 * unchanged: Return-arrow for NEWLINE, Send for GO/SEARCH/SEND/NEXT/
 * PREVIOUS/CUSTOM, Check for DONE.
 *
 * **Why not a dedicated Search-icon?** The Legacy used `ic_baseline_send_20`
 * for SEARCH too; introducing a separate Search-drawable would be a
 * visual change orthogonal to the action-correctness fix this plan
 * delivers. A follow-up can add `ic_baseline_search_24` and update the
 * SEARCH arm in isolation.
 */
fun resolveEnterIcon(state: DictateUiState): Int {
    val host = state.keyboardInput.hostEditor
    if (!host.hasEditorInfo) return R.drawable.ic_baseline_subdirectory_arrow_left_24
    return when (resolveEnterRole(host)) {
        EnterButtonRole.NEWLINE -> R.drawable.ic_baseline_subdirectory_arrow_left_24
        EnterButtonRole.DONE -> R.drawable.ic_baseline_check_24
        EnterButtonRole.GO,
        EnterButtonRole.SEARCH,
        EnterButtonRole.SEND,
        EnterButtonRole.NEXT,
        EnterButtonRole.PREVIOUS,
        EnterButtonRole.CUSTOM -> R.drawable.ic_baseline_send_20
    }
}

/**
 * Picks the Action the Enter-button dispatches on click. Returns
 * `null` (Silent-No-op per Catalog R.3) when `state.canCommitToHost`
 * is `false` — the IME-View is not on screen so a commit/Enter would
 * land in the wrong target.
 *
 * **pc-dictation-activity exception (F1):** in PC-only mode there is no
 * local host at all, but the key is NOT dead — the reducer's `EnterKey`
 * effect (`PerformEnter` / `SendPhysicalEnter`) both map to
 * `ControlOp.Enter`/`PhysicalEnter`, which `PcInputCommandMapper` routes
 * to the PC's ENTER. So `pcOnly` opens the gate: ENTER reaches the paired
 * PC instead of being suppressed. (Without this the Activity's ENTER was
 * a dead key while ADR-0027 promised "routes to PC".)
 *
 * The Action itself is always [Action.KeyboardInputAction.EnterKey];
 * the role-derivation happens inside `KeyboardInputModule`'s reducer
 * so the Catalog and the Module agree on a single decision point
 * (state.keyboardInput.hostEditor read once on the Module side).
 */
fun resolveEnterAction(
    state: DictateUiState,
    @Suppress("UNUSED_PARAMETER") services: ModuleServices,
): Action? {
    if (!state.canCommitToHost && !state.features.pcOnly) return null
    return Action.KeyboardInputAction.EnterKey
}
