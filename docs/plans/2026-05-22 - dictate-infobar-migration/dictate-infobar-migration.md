# Dictate Unified Info-Notice Migration — Complete the ADR-0006 State-Derived Big-Bang

archive_target: 2026-05-22 - dictate-infobar-migration

## 1. Context & Problem

Dictate communicates transient information to the user (errors, warnings,
app-prompts) through **scattered, mostly imperative channels**:

- **Legacy InfoBar** — `InfoBarController.showInfo(type)`, 9 imperative
  type strings, renders into `info_cl`.
- **New InfoBar** — `InfoBarSelector` (pure `(DictateUiState) ->
  List<InfoBarItem>`) + `InfoBarRenderer`, wired (wrongly) to
  `overlay_permission_infobar`.
- **Operational toasts** — `Toast.makeText` fired from 3–4 sites each
  for `service_not_ready`, `storage_full`, `audio_file_missing`,
  `resend_focus_lost`, `job_already_active`.

The two research files
([infobar-territory-map.md](research/infobar-territory-map.md),
[info-feedback-channels.md](research/info-feedback-channels.md)) expose
the real disease: **a single AI error fans to 4–5 surfaces through
independent wirings with no shared "error" fact in state**, and the
error *kind* is destroyed by the pipeline FSM (`PipelineFailed` →
`Idle`). The InfoBar and the red record-button derive the same event
from two different sources and can desync; the FGS notification
*contradicts* the InfoBar (dismisses on an error the InfoBar shows).

This plan unifies every **transient in-keyboard notice** — pipeline
errors *and* operational notices — into one typed state axis, finishes
the ADR-0006 InfoBar migration, and deletes both the legacy
`InfoBarController` and the dead `ToastSink` infrastructure.

The two research files are the SSoT for the *current-state facts*; this
plan is the SSoT for the *work*.

## 2. Acceptance Criteria

- **AC-1** — All transient in-keyboard notices render via
  `InfoBarSelector` → `InfoBarRenderer` → `info_cl`:
  - 5 pipeline errors — `invalid_api_key`, `quota_exceeded`,
    `model_not_found`, `bad_request`, `internet_error`;
  - 4 operational notices — `service_not_ready`, `storage_full`,
    `audio_file_missing`, `resend_focus_lost`;
  - 3 app-prompts — `update`, `rate`, `donate`.
- **AC-2** — Dropped, not migrated: `timeout` (dead code), `cancelled`
  (never surfaces), `job_already_active` (cosmetic echo of a reducer
  `Idle`-guard — deleted outright).
- **AC-3** — `InfoBarController.kt` deleted; the dead `ToastSink`
  interface + its binding deleted; no `showInfo(...)`,
  `infoBarController.dismiss()`, or operational `Toast.makeText` call
  site remains in `DictateInputMethodService`.
- **AC-4** — `info_cl` is the single info-bar container;
  `overlay_permission_infobar` (+ its 3 children) removed from the
  layout. `InfoBarRenderer` renders into `info_cl`.
- **AC-5** — The InfoBar Z-order bug is gone (`info_cl` is in the
  constraint chain → pushes content down; the `PromptVisibilityController`
  mutex from commit `7ee502c` hides `prompts_keyboard_cl` while a bar is
  up).
- **AC-6** — Every selector producer has a "confirm/dismiss action
  actually removes the item" test (ADR-0006 §Failure-Modes).
- **AC-7** — `quota_exceeded` keeps its dynamic behaviour: provider
  display-name interpolated; billing-URL confirm button only when
  `provider.billingUrl != null`.
- **AC-8** — Side-effects preserved: `update` dismiss writes
  `Pref.LastVersionCode`; `rate` writes `Pref.FlagHasRated`; `donate`
  writes both `Pref.FlagHasDonated` + `Pref.FlagHasRated`; confirm
  buttons still open Settings / Play-Store / PayPal / billing URL.
- **AC-9** — The 300 ms error vibration fires **consistently** for
  every error-class notice (an `Effect` on the notice-add edge) — the
  preflight `vibrate=false` vs. runtime `vibrate=true` inconsistency is
  gone.
- **AC-10** — Full unit-test suite green; debug APK builds;
  device-verified (force a network error → bar in `info_cl`, readable,
  tappable, prompts hidden, vibrates; dismiss → bar gone).
- **AC-11** — ADR-0006 `Proposed` → `Accepted` with a Decision-History
  entry recording D1–D9.

## 3. Design Decisions

### D1 — One typed list axis: `transientNotices`

A `PersistentList<TransientNotice>` on `DictateUiState`. `TransientNotice`
is a **sealed hierarchy** — each variant carries exactly its own data
(no optional-field-valid-only-for-some-variants smell). Consistent with
`pendingSessions` (already a `PersistentList`) and with the project's
sealed-class discipline (`RecordingState`, `PipelineUiState`, …).

```kotlin
sealed interface TransientNotice {
    val id: String         // stable, for dedup + dismiss targeting
    val createdAt: Long    // stamped by the dispatching action (reducers have no clock)
}
// pipeline errors
data class QuotaExceeded(val providerKey: String?, override val id, override val createdAt) : TransientNotice
data class InvalidApiKey(...)  : TransientNotice
data class ModelNotFound(...)  : TransientNotice
data class BadRequest(...)     : TransientNotice
data class NetworkError(...)   : TransientNotice
// operational notices (today's toasts)
data object/ data class ServiceNotReady(...)  : TransientNotice
data class StorageFull(...)      : TransientNotice
data class AudioFileMissing(...) : TransientNotice
data class ResendFocusLost(...)  : TransientNotice
```

The renderer shows the oldest item (`items.first()`); extra notices
queue — acceptable (errors rarely co-occur).

### D2 — Separate axis for app-prompts: `appPrompts`

`update/rate/donate` derive from **persistent prefs** + a DB aggregate,
not transient events — they do not belong in `transientNotices`.
`AppPromptsState(updateAvailable, ratePromptDue, donatePromptDue)` —
resolved booleans. The IME service computes them where it already does
the pref/DB reads (`onStartInputView`) and dispatches a `Refresh`
action; the selector reads three booleans → pure. `createdAt` for these
items = `BuildConfig.VERSION_BUILD_TIME` (stable).

### D3 — The "info subsystem" is a module: `InfoModule`

One new `DictateModule` — `InfoModule` — owns **both** new axes
(`transientNotices` + `appPrompts`) and carries their reducers. This is
the user-requested "info subsystem" in the architecturally-correct
form: a reducer-owning module, **not** an imperative manager class
(that is exactly the `InfoBarController` being deleted). The three
roles stay clean: `InfoModule` (state + reducers) → `InfoBarSelector`
(pure aggregation) → `InfoBarRenderer` (the collector that paints).

### D4 — Removal is reducer-driven; no timers

A `TransientNotice` leaves the list only via a reducer:
- **User-dismiss** — the dismiss button dispatches `DismissNotice(id)`;
  the reducer removes it (ADR-0006 "dismiss = natural-source mutation").
- **Event-clear** — a reducer arm removes notices on a natural
  follow-up event: starting a new pipeline clears stale pipeline-error
  notices; the service binding clears `ServiceNotReady`.

No auto-dismiss timers in v1 — every notice has a natural clear path
(user action or follow-up event). This is the explicit per-toast
auto-dismiss decision the research flagged.

### D5 — Drop `timeout`, `cancelled`, `job_already_active`

`timeout` — dead `when` branch, no caller. `cancelled` — never reaches
a surface (orchestrator returns early on cancellation). `job_already_active`
— a cosmetic toast echoing a reducer `Idle`-guard the FSM already
enforces; the real protection stays, the toast is deleted (not migrated).

### D6 — One container: `info_cl`

`info_cl` is in the layout constraint chain (`prompts_keyboard_cl` is
`Top_toBottomOf="@id/info_cl"`) → it pushes content down, no overlap.
Re-point `InfoBarRenderer` at `info_cl` / `info_tv` / `info_yes_btn` /
`info_no_btn`; delete the unchained `overlay_permission_infobar`. The
Z-order bug disappears structurally. The `PromptVisibilityController`
mutex (commit `7ee502c`) stays — once the selector is the sole writer
of `info_cl` it is correct and race-free.

### D7 — Vibration is an `Effect`, not state

Modelling "buzz now" as state would need edge-detection. Instead the
`InfoModule` reducer, when it **adds** an error-class `TransientNotice`,
emits a vibration `Effect`; the IME effect-handler fires the 300 ms
error pattern. This makes vibration fire for *every* error notice
(including preflight) — fixing the current inconsistency (AC-9).

### D8 — Delete the dead `ToastSink`

`ToastSink` is plumbed but has zero producers. The operational toasts
become `TransientNotice`s; `ToastSink` + its binding
(`PipelineServiceStubSubsystems.realToastSink`) + the `ModuleServices`
field are deleted. No genuinely-ephemeral feedback channel needs
resurrecting today.

### D9 — `Running.hasFailure` stays separate

A non-terminal *step* failure (`StepFailed` → `Running.hasFailure`, the
red record-button + step-row ✕, pipeline keeps running) is a
**different fact** from a terminal pipeline error (a `TransientNotice`).
They are not merged; `RecordButtonColorController` /
`PipelineStepRowRenderer` are untouched.

### Side-channels & out of scope

- **Confirm-action side-channels** (open Settings / Play-Store / PayPal
  / billing URL) fire in the IME service's `InfoBarRenderer` action
  sink alongside dispatch — the existing `RequestOverlayPermission`
  pattern. State-dependent params (the billing URL) are read **before**
  dispatch (the dismiss mutation clears the notice).
- **Out of scope** — the FGS notification (stays a separate OS surface;
  its contradiction with the InfoBar on errors is noted as a follow-up,
  not fixed here); `OVERLAY_BLOCK_HINT` (info-items while the overlay
  shows); the "What's new" dialog vs. InfoBar-`update` duplication.

## 4. Work Blocks

> Commit prefix per chunk: `[<Block>.<Chunk>] <title> (dictate-infobar-migration)`.

### Block A — State foundation & `InfoModule`

- **A.1** — `TransientNotice` sealed hierarchy (9 variants) + a small
  `errorKindOf` / classification helper. New file under `state/`.
- **A.2** — `AppPromptsState`. Add `DictateUiState.transientNotices:
  PersistentList<TransientNotice>` and `appPrompts: AppPromptsState`;
  update `initial()`.
- **A.3** — `Action`s: `InfoAction.AddNotice(notice)`,
  `DismissNotice(id)`, `ClearPipelineErrorNotices`,
  `ClearServiceNotReady`; `AppPromptsAction.{Refresh, DismissUpdate,
  ConfirmUpdate, DismissRate, ConfirmRate, DismissDonate, ConfirmDonate}`.
- **A.4** — `InfoModule` (owns both axes) + reducers: add/dismiss,
  event-clears (D4), app-prompt dismiss/confirm arms emitting
  `Effect.PersistPref` (AC-8), and the error-notice-add vibration
  `Effect` (D7). Register `InfoModule` in the module registry.
- Reducer + data-class tests (`InfoModuleTest`, `DictateUiStateTest`).

### Block B — Selector producers

- **B.1** — `transientNotices` producer in `InfoBarSelector.select()`:
  iterate the list → `InfoBarItem` per variant. Per-variant message
  (`@StringRes` + `textArgs` + `InfoBarStyle.ERROR`),
  `dismissAction = DismissNotice(id)`, `confirmAction` per variant
  (Settings / billing / none). `quota_exceeded`: provider display-name
  → `textArgs`, confirm only if `billingUrl != null` (AC-7).
- **B.2** — `appPrompts` producer (3 `if` blocks, `InfoBarStyle.INFO`,
  `createdAt = VERSION_BUILD_TIME`).
- **B.3** — `InfoBarSelectorTest` for all 12 items, including the
  dismiss/confirm-removes-item tests (AC-6) and the `quota_exceeded`
  provider/billing matrix.

### Block C — Wiring & legacy removal

- **C.1** — `onPipelineError`: replace `showInfo(...)` with a dispatch
  of `InfoAction.AddNotice(<mapped TransientNotice>)`
  (`String → TransientNotice` mapping; `timeout`/`cancelled` →
  unmapped; unknown → `NetworkError`). `PipelineFailed` dispatch stays.
- **C.2** — The 4 operational toasts: replace each `Toast.makeText` in
  `DictateInputMethodService` (`service_not_ready`, `storage_full`,
  `audio_file_missing`, `resend_focus_lost`) with an
  `InfoAction.AddNotice(...)` dispatch. Delete the `job_already_active`
  toast + `showJobBusyToast()` (D5).
- **C.3** — `onStartInputView` update/rate/donate checks: replace the 3
  `showInfo(...)` with one `AppPromptsAction.Refresh(...)` dispatch.
- **C.4** — Re-point `InfoBarRenderer` construction
  (`DictateInputMethodService.java:1592-1668`) to `info_cl` /
  `info_tv` / `info_yes_btn` / `info_no_btn`. Extend the action sink
  for the new confirm side-channels (D-§Side-channels).
- **C.5** — Delete `InfoBarController.kt` (+ field, construction, the 2
  private `showInfo` wrappers, the 5 `dismiss()` calls, the null
  guard). Delete `ToastSink` + its binding + the `ModuleServices`
  field (D8).

### Block D — Layout

- **D.1** — Delete the `overlay_permission_infobar` container + its 3
  children (`activity_dictate_keyboard_view.xml:290-346`). Verify the
  `info_cl → prompts_keyboard_cl → edit_buttons_keyboard_ll →
  main_buttons_cl` chain and that `info_cl`'s 3 children match what
  `InfoBarRenderer` expects.

### Block E — Verify & docs

- **E.1** — Full `./gradlew test` green; `assembleDebug`; install;
  device-verify per AC-10. Verify update/rate prompts still surface and
  a storage-full / service-not-ready notice renders in `info_cl`.
- **E.2** — ADR-0006 `Proposed` → `Accepted`; Decision-History entry
  for D1–D9. Cross-link this plan in ADR-0006 `## References`.

## 5. Risks & Notes

- **Big-bang risk** — Block C lands the new dispatch + the
  `InfoBarController`/`ToastSink` deletion together; partial states are
  not shippable. Mitigation: the selector is pure and unit-tested per
  type (Block B) **before** Block C deletes the legacy path; Block C is
  one reviewable cutover unit.
- **`createdAt`** — the pure reducer has no clock; `onPipelineError` and
  the toast call sites stamp `System.currentTimeMillis()` into the
  `AddNotice` action. App-prompts use `BuildConfig.VERSION_BUILD_TIME`.
- **Toast lifetime change** — a migrated toast lingers until dismissed
  / event-cleared instead of auto-vanishing after ~2 s. D4 gives each a
  natural clear path; verify on device that none feels "stuck".
- **Dead-code cleanup found by research** — `NotificationStatus.Pipeline.step`
  (plumbed, never rendered) and the orphaned `dictate_notif_ready_to_insert`
  strings are noted but **out of scope** here.

## 6. References

- ADR: `docs/decisions/0006-ui-info-bar-state-derived-items.md`
- Research: [research/infobar-territory-map.md](research/infobar-territory-map.md),
  [research/info-feedback-channels.md](research/info-feedback-channels.md)
- Related fix (already landed): commit `3c72d2a` — widget-send
  `canCommitToHost` axis correction (sibling bug from the same
  investigation; independent of this plan).
