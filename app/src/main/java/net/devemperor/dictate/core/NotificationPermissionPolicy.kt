package net.devemperor.dictate.core

import android.os.Build

/**
 * Pure decision logic for the runtime `POST_NOTIFICATIONS` prompt
 * (F-092). An IME service cannot request permissions itself, so the
 * grant is asked for from an Activity (onboarding first-run, settings
 * fallback). This object isolates the *whether-to-ask* rule so it is
 * unit-testable without a live `Context`/`Activity` — the Activities
 * only supply the three inputs and act on the verdict.
 *
 * ## Why the permission matters
 *
 * `DictatePipelineService` runs as a foreground service whose sticky
 * notification carries the recording/pipeline status plus the
 * Pause/Stop/Send/Cancel action buttons
 * ([PipelineNotificationCoordinator]). On API 33+ the reactive
 * `NotificationManagerCompat.notify` refresh is a silent no-op until
 * `POST_NOTIFICATIONS` is granted, so the user loses that surface
 * entirely. The FGS itself keeps running; only its user-visible
 * notification is suppressed.
 *
 * ## The three inputs (API level × granted × rationale)
 *
 *  - **API level** — the runtime permission only exists on API 33
 *    (`TIRAMISU`); below that the manifest declaration is enough and
 *    the notification shows unconditionally, so we never ask.
 *  - **granted** — if already granted there is nothing to ask for.
 *  - **shouldShowRationale** — Android's signal that the user denied
 *    once but the system will still show the dialog. We honour it in
 *    the *settings fallback* so a previously-denied user gets exactly
 *    one more contextual ask rather than a silent gap; we never
 *    loop-nag (a permanent denial — `!granted && !shouldShowRationale`
 *    after a first ask — is left alone).
 *
 * ## Two callers, two predicates
 *
 *  - [shouldRequestOnboarding] — the first-run ask. Rationale is
 *    irrelevant here (the user has never been asked), so the rule is
 *    simply *API 33+ and not yet granted*.
 *  - [shouldRequestFromSettings] — the fallback ask for upgrading /
 *    previously-denied users. Only fires when the system still permits
 *    a dialog (`shouldShowRationale == true`), which prevents the
 *    permanent-denial loop-nag.
 *
 * @see net.devemperor.dictate.core.PipelineNotificationCoordinator
 * @see docs/research/2026-07-02 - feature-wiring-code-review.md (F-092)
 */
object NotificationPermissionPolicy {

    /** The runtime permission only exists on API 33 (Tiramisu) and up. */
    fun isRuntimePermissionRelevant(apiLevel: Int): Boolean =
        apiLevel >= Build.VERSION_CODES.TIRAMISU

    /**
     * First-run onboarding ask: API 33+ and not yet granted. Rationale
     * is not consulted — the onboarding page is the user's first
     * exposure to the ask, and the page always offers the button so a
     * user who tapped past it can still grant later.
     */
    fun shouldRequestOnboarding(apiLevel: Int, granted: Boolean): Boolean =
        isRuntimePermissionRelevant(apiLevel) && !granted

    /**
     * Settings fallback ask (upgrading users, or users who denied
     * during onboarding). Fires only when the permission is relevant,
     * not granted, **and** the system still allows a dialog
     * (`shouldShowRationale`). Once the user permanently denies
     * (`shouldShowRationale` false after a real denial) this returns
     * false forever, so settings never loop-nags.
     */
    fun shouldRequestFromSettings(
        apiLevel: Int,
        granted: Boolean,
        shouldShowRationale: Boolean,
    ): Boolean =
        isRuntimePermissionRelevant(apiLevel) && !granted && shouldShowRationale
}
