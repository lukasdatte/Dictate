package net.devemperor.dictate.state

/**
 * Info-hint sub-state — the trigger axis behind the transient info-bar
 * items that used to live in the imperative legacy info-bar controller
 * (deleted 2026-07-02, ADR-0006 migration completion / research
 * `2026-07-02 - infobar-consolidation.md`).
 *
 * Two hint families share the axis because both are **in-RAM,
 * trigger-shaped** state (unlike `pendingSessions`, whose source of
 * truth is the DB):
 *
 *  - [pipelineError] — the most recent transient AI-pipeline error
 *    (network / quota / model / api-key / bad-request). Set by the IME
 *    service's `onPipelineError` callback via
 *    [Action.InfoHintAction.PipelineErrorOccurred]; the
 *    [net.devemperor.dictate.state.infobar.InfoBarSelector] derives an
 *    ERROR-style bar from it.
 *  - [engagementHint] — at most one of Update / Rate / Donate. The
 *    triggers are evaluated by the IME service on `onStartInputView`
 *    (pref + usage-DB reads that a pure reducer cannot perform) and
 *    dispatched via [Action.InfoHintAction.ShowEngagementHint]. The
 *    single-slot shape mirrors the legacy `if/else` trigger chain —
 *    the three hints were always mutually exclusive.
 *
 * **Lifecycle (no-resurrection guarantee, ADR-0006 §"Dismiss =
 * natural-source mutation"):** dismissing a hint clears its field here
 * (and, for engagement hints, persists the matching `Pref.*` flag so
 * the service-side trigger stops re-firing). `InfoHintModule`'s
 * cross-module observer additionally clears all hints when a new
 * recording / pipeline run starts and when the IME view hides while
 * idle — the same moments the legacy `infoBarController.dismiss()`
 * call sites covered.
 *
 * Owned by `InfoHintModule`.
 *
 * @see Action.InfoHintAction
 * @see net.devemperor.dictate.state.infobar.InfoBarSelector
 * @see docs/decisions/0006-ui-info-bar-state-derived-items.md
 */
data class InfoHintState(
    val pipelineError: PipelineErrorHint? = null,
    val engagementHint: EngagementHint? = null,
    /**
     * Non-null while a "processing cancelled" notice is live (R5,
     * ADR-0009 / spec §3.6). Set when the ACTIVE pipeline run is
     * cancelled ([Action.InfoHintAction.PipelineCancelled], emitted by
     * `PipelineModule`'s real `CancelPipeline` arm); cleared by the
     * notice's dismiss button or the module's transient cross-clear.
     * Queued runs survive the cancel (ADR-0009 D5), so the notice can
     * legitimately coexist with a live pipeline (the chain-started
     * next run).
     */
    val cancellation: CancellationHint? = null,
)

/**
 * One "processing cancelled" occurrence (R5). Deliberately a typed
 * notice and not a [PipelineErrorKind]: [PipelineErrorKind.fromInfoKey]
 * keeps mapping `"cancelled"` to `null` (F-076) — cancellation is not
 * an error, it just must no longer vanish without user-visible trace.
 *
 * @property occurredAt wall-clock ms from `ReducerContext.now`
 *   (reducers never read the clock directly). Doubles as the info-bar
 *   item's `createdAt` sort key.
 */
data class CancellationHint(
    val occurredAt: Long,
)

/**
 * One transient pipeline error, kept until the user dismisses it or a
 * new recording / pipeline run supersedes it.
 *
 * @property kind typed error classifier — see [PipelineErrorKind].
 * @property providerKey `AIProvider.name` persist-key of the provider
 *   that raised the error, or `null` when unknown (e.g. generic
 *   network failures). Used by the quota-exceeded bar to resolve the
 *   provider display-name + billing URL.
 * @property occurredAt wall-clock ms the error was reduced into state
 *   (from `ReducerContext.now` — reducers never read the clock
 *   directly). Doubles as the info-bar item's `createdAt` sort key.
 */
data class PipelineErrorHint(
    val kind: PipelineErrorKind,
    val providerKey: String?,
    val occurredAt: Long,
)

/**
 * Typed classifier for user-facing transient pipeline errors.
 *
 * Replaces the legacy string-keyed `showInfo(errorInfoKey)` routing:
 * the string boundary (`AIProviderException.toInfoKey()` →
 * `PipelineCallback.onPipelineError`) is parsed exactly once via
 * [fromInfoKey]; everything downstream is exhaustively typed.
 *
 * **Deliberately absent values:**
 *
 *  - `CANCELLED` — user-initiated cancellation is not an error the
 *    info-bar should surface. [fromInfoKey] maps `"cancelled"` to
 *    `null` explicitly (F-076: the legacy `showInfo` had no
 *    `cancelled` branch and would have rendered stale text + stale
 *    click handlers if a runner ever reported it).
 *  - `TIMEOUT` — the legacy `"timeout"` case had no producer anywhere
 *    in the codebase (verified 2026-07-02); it is not carried over.
 */
enum class PipelineErrorKind {
    /** 401 — API key missing/invalid. Confirm opens the settings. */
    INVALID_API_KEY,

    /** 429 — rate/quota limit. Confirm opens the provider's billing page. */
    QUOTA_EXCEEDED,

    /** 404 — configured model no longer exists. Confirm opens the settings. */
    MODEL_NOT_FOUND,

    /** 400 — invalid request parameters. Confirm opens the settings. */
    BAD_REQUEST,

    /** Connectivity / 5xx / unclassified failures. Dismiss-only. */
    INTERNET_ERROR,

    /** ADR-0019 — the paired PC is unreachable / timed out / 5xx. Dismiss-only; the text is a pending part. */
    WINDOWS_UNREACHABLE,

    /** ADR-0019 — 401, the pairing is invalid. Confirm opens the pairing screen (wired at the seam, Block 3b). */
    WINDOWS_UNAUTHORIZED;

    companion object {
        /**
         * Parse the `errorInfoKey` string arriving through
         * `PipelineCallback.onPipelineError` into a typed kind.
         *
         * Returns `null` for keys that must NOT surface an error bar:
         *
         *  - `"cancelled"` — user-initiated, silent by design (F-076).
         *  - any unknown key — fail-closed. The legacy `when` without
         *    an `else` silently showed the *previous* bar's text and
         *    handlers for unmatched keys; returning `null` here turns
         *    that trap into a no-op.
         */
        fun fromInfoKey(key: String): PipelineErrorKind? = when (key) {
            "invalid_api_key" -> INVALID_API_KEY
            "quota_exceeded" -> QUOTA_EXCEEDED
            "model_not_found" -> MODEL_NOT_FOUND
            "bad_request" -> BAD_REQUEST
            "internet_error" -> INTERNET_ERROR
            // F-076 — cancellation deliberately produces no bar.
            "cancelled" -> null
            else -> null
        }
    }
}

/**
 * The three mutually-exclusive engagement nags migrated from the
 * legacy info bar. Trigger conditions (evaluated IME-side, see
 * [InfoHintState] KDoc):
 *
 *  - [UPDATE] — `Pref.LastVersionCode < BuildConfig.VERSION_CODE`.
 *    Confirm opens the settings (changelog); dismiss persists the
 *    current version code.
 *  - [RATE] — total transcribed audio in (180 s, 600 s] and
 *    `Pref.FlagHasRated` unset. Confirm opens the Play-Store page;
 *    both buttons persist `Pref.FlagHasRated`.
 *  - [DONATE] — total transcribed audio > 600 s and
 *    `Pref.FlagHasDonated` unset. Confirm opens the PayPal page; both
 *    buttons persist `Pref.FlagHasDonated` + `Pref.FlagHasRated`.
 */
enum class EngagementHint {
    UPDATE,
    RATE,
    DONATE,
}
