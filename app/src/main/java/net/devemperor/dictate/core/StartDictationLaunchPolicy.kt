package net.devemperor.dictate.core

/**
 * Pure precondition gate for [StartDictationActivity] — decides where an
 * external dictation trigger (launcher alias / app shortcut / QS tile)
 * routes, given the two runtime permissions dictation depends on.
 *
 * Kept as a top-level pure function (no Context) so the decision table
 * is JVM-testable; the Activity is a thin shell that reads the real
 * permission states and executes the decision.
 *
 * # Decision table
 *
 * | RECORD_AUDIO | SYSTEM_ALERT_WINDOW | decision                        |
 * |--------------|---------------------|---------------------------------|
 * | granted      | granted             | [StartPipelineService]          |
 * | missing      | any                 | [OpenMicPermissionSettings]     |
 * | granted      | missing             | [OpenOverlayPermissionOnboarding] |
 *
 * **Why mic outranks overlay:** without RECORD_AUDIO no dictation can
 * happen at all, and [net.devemperor.dictate.settings.DictateSettingsActivity]
 * auto-requests the mic permission on open — so when both are missing
 * the settings route resolves the blocking permission first; the user's
 * next trigger then lands on the overlay-onboarding route.
 *
 * **Why overlay permission is required (not best-effort):** without
 * SYSTEM_ALERT_WINDOW the overlay widget cannot render, so a started
 * recording would run with the FGS notification as its only visible
 * control surface — an easy way to leave the mic hot without noticing.
 * The trigger therefore refuses to start and routes to
 * [net.devemperor.dictate.onboarding.OverlayPermissionOnboardingActivity]
 * (the dedicated secondary-entry-point explainer, Spec 3 §5.2) instead
 * of failing silently.
 */
sealed interface StartDictationLaunchDecision {
    /** All preconditions met — fire `ACTION_START_DICTATION` at the FGS. */
    data object StartPipelineService : StartDictationLaunchDecision

    /** RECORD_AUDIO missing — toast + open the settings activity (auto-requests mic). */
    data object OpenMicPermissionSettings : StartDictationLaunchDecision

    /** SYSTEM_ALERT_WINDOW missing — open the overlay-permission explainer. */
    data object OpenOverlayPermissionOnboarding : StartDictationLaunchDecision
}

fun decideStartDictationLaunch(
    hasMicPermission: Boolean,
    hasOverlayPermission: Boolean,
): StartDictationLaunchDecision = when {
    !hasMicPermission -> StartDictationLaunchDecision.OpenMicPermissionSettings
    !hasOverlayPermission -> StartDictationLaunchDecision.OpenOverlayPermissionOnboarding
    else -> StartDictationLaunchDecision.StartPipelineService
}
