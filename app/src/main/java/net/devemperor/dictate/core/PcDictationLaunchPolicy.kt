package net.devemperor.dictate.core

/**
 * Pure precondition gate for the PC-dictation Activity (pc-dictation-activity) — decides where an
 * entry trigger (launcher alias / app shortcut / keyboard PC long-press) routes, given the two
 * preconditions the Activity depends on.
 *
 * Kept as a top-level pure function (no Context) so the decision table is JVM-testable; the callers
 * (the [StartPcDictationActivity] trampoline and the IME's `onPcLongClicked`) read the real states
 * and execute the decision.
 *
 * # Decision table
 *
 * | RECORD_AUDIO | PC paired | decision                        |
 * |--------------|-----------|---------------------------------|
 * | granted      | yes       | [OpenPcDictation]               |
 * | missing      | any       | [OpenMicPermissionSettings]     |
 * | granted      | no        | [OpenPairing]                   |
 *
 * **Why mic outranks pairing:** without RECORD_AUDIO no dictation can happen at all, and
 * [net.devemperor.dictate.settings.DictateSettingsActivity] auto-requests the mic permission on
 * open — so the settings route resolves the blocking permission first; the next trigger then lands
 * on the pairing route.
 *
 * **Why pairing is required (not best-effort):** every output in the Activity goes to the paired PC.
 * Without a pairing there is no destination, so the trigger routes to the pairing screen instead of
 * opening an Activity whose every send would fail. (Overlay permission is deliberately NOT a
 * precondition here — unlike an external dictation start, the Activity is its own visible surface
 * and never relies on the floating overlay widget.)
 */
sealed interface PcDictationLaunchDecision {
    /** All preconditions met — open the PC-dictation Activity. */
    data object OpenPcDictation : PcDictationLaunchDecision

    /** RECORD_AUDIO missing — toast + open the settings activity (auto-requests mic). */
    data object OpenMicPermissionSettings : PcDictationLaunchDecision

    /** No PC paired — open the Windows pairing screen. */
    data object OpenPairing : PcDictationLaunchDecision
}

fun decidePcDictationLaunch(
    hasMicPermission: Boolean,
    isPaired: Boolean,
): PcDictationLaunchDecision = when {
    !hasMicPermission -> PcDictationLaunchDecision.OpenMicPermissionSettings
    !isPaired -> PcDictationLaunchDecision.OpenPairing
    else -> PcDictationLaunchDecision.OpenPcDictation
}
