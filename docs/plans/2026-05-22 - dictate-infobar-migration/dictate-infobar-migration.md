# Dictate InfoBar Migration — Complete the ADR-0006 State-Derived Big-Bang

archive_target: 2026-05-22 - dictate-infobar-migration

## 1. Context & Problem

Dictate runs **two** info-bar systems side by side:

- **Legacy** — `InfoBarController` (imperative `showInfo(type)`), renders
  into the `info_cl` container.
- **New** — `InfoBarSelector` (pure `(DictateUiState) -> List<InfoBarItem>`)
  + `InfoBarRenderer` (a `StateFlow` collector), renders into the
  `overlay_permission_infobar` container.

ADR-0006 decided the legacy system would be fully migrated into the
selector and deleted ("big-bang migration"). It was only half done:
the new `InfoBarRenderer` exists, but (a) it was wired to the wrong
container — `overlay_permission_infobar`, which is **not** in the
layout's constraint chain, so it overlaps and is painted over by later
views (the **InfoBar Z-order bug**); and (b) the 9 legacy
`InfoBarController` types were never migrated, so `InfoBarController`
still lives.

Full territory map: [research/infobar-territory-map.md](research/infobar-territory-map.md).
This plan is the single source of truth for the *work*; the research
file is the SSoT for the *current-state facts*.

## 2. Acceptance Criteria

- **AC-1** — All 8 live info-bar types render via
  `InfoBarSelector` → `InfoBarRenderer`: `update`, `rate`, `donate`,
  `invalid_api_key`, `quota_exceeded`, `model_not_found`, `bad_request`,
  `internet_error`. (`timeout` dropped — dead code, no caller.)
- **AC-2** — `InfoBarController.kt` is deleted; no `showInfo(...)` /
  `infoBarController.dismiss()` call sites remain.
- **AC-3** — `info_cl` is the single info-bar container; `InfoBarRenderer`
  renders into it; `overlay_permission_infobar` (+ its 3 children) is
  removed from the layout.
- **AC-4** — The InfoBar Z-order bug is gone: an active info-bar is fully
  visible and tappable (`info_cl` is in the constraint chain → pushes
  content down; the `PromptVisibilityController` mutex from commit
  `7ee502c` hides `prompts_keyboard_cl` while the bar is up).
- **AC-5** — Each new selector producer (pipeline-error, app-prompt) has
  a "confirm/dismiss action actually removes the item" test
  (ADR-0006 §Failure-Modes).
- **AC-6** — `quota_exceeded` keeps its dynamic behaviour: provider
  display-name interpolated into the message; the billing-URL confirm
  button appears only when `provider.billingUrl != null`.
- **AC-7** — Confirm/dismiss side-effects are preserved: `update` dismiss
  writes `Pref.LastVersionCode`; `rate` writes `Pref.FlagHasRated`;
  `donate` writes both `Pref.FlagHasDonated` and `Pref.FlagHasRated`;
  confirm buttons still open Settings / Play-Store / PayPal / billing URL.
- **AC-8** — Full unit-test suite green; debug APK builds; device-verified
  (trigger a network error → bar shows in `info_cl`, readable, tappable).
- **AC-9** — ADR-0006 moved `Proposed` → `Accepted` with a Decision-History
  entry recording the final state-modelling choices (D1–D6 below).

## 3. Design Decisions

### D1 — Pipeline transient error: a top-level `pipelineError` axis

A pipeline error is a one-shot event; the pipeline FSM goes straight to
`Idle`, so the error key cannot live *inside* `PipelineUiState`
(`Idle` would have to carry it). Model it as a **top-level nullable
axis** `DictateUiState.pipelineError: PipelineError?`, owned by
`PipelineModule` (a module may own more than one axis; the
single-owner-per-axis invariant is per *axis*).

```
enum class PipelineErrorKind { INVALID_API_KEY, QUOTA_EXCEEDED,
                               MODEL_NOT_FOUND, BAD_REQUEST, INTERNET_ERROR }

data class PipelineError(
    val kind: PipelineErrorKind,
    val providerKey: String?,   // AIProvider persist key, for QUOTA_EXCEEDED
    val createdAt: Long,        // stamped by the dispatcher (pure reducer has no clock)
)
```

ADR-0006's sketch said `state.pipeline.transientError`; this plan
deviates to a sibling top-level field because the sealed `PipelineUiState`
cannot carry a shared field cleanly. Recorded in AC-9.

### D2 — App-lifecycle prompts: a resolved-boolean `appPrompts` axis

`update/rate/donate` depend on SharedPreferences flags **and** a DB
aggregate (`usageDao.getTotalAudioTime()`). Mirroring a live DB
aggregate into state continuously is heavy and pointless — the checks
only run at `onStartInputView`. So model the **resolved** booleans:

```
data class AppPromptsState(
    val updateAvailable: Boolean = false,
    val ratePromptDue: Boolean = false,
    val donatePromptDue: Boolean = false,
)
```

The IME service computes the three booleans where it already does the
pref/DB reads (`onStartInputView`) and dispatches
`AppPromptsAction.Refresh(...)`. The selector reads three booleans —
pure. Owned by a new tiny `AppPromptsModule`.

ADR-0006's sketch read a non-existent `state.prefs` axis; this plan
resolves that gap with `AppPromptsState`. Recorded in AC-9.

### D3 — Drop `timeout` and `cancelled`

`timeout` has a `when` branch + string resource but **no caller** —
delete the branch; keep the string resource (cheap, harmless) or remove
it (implementer's call). `cancelled` is produced by
`AIProviderException.toInfoKey()` but never reaches the bar — the
error-key→kind mapping simply has no `CANCELLED` case.

### D4 — One container: `info_cl`

`info_cl` is already in the layout constraint chain
(`prompts_keyboard_cl` is `Top_toBottomOf="@id/info_cl"`), so it pushes
content down correctly. Re-point `InfoBarRenderer` at `info_cl` /
`info_tv` / `info_yes_btn` / `info_no_btn`; delete the
`overlay_permission_infobar` container and its 3 children. The Z-order
bug disappears structurally — there is no longer an unchained,
low-Z-order info-bar container.

### D5 — Confirm-action side-channels stay in the IME action-sink

`InfoBarRenderer` dispatches one `Action` per click. State mutations go
through the reducer; **side-channels** (launch Settings / a browser URL)
fire alongside dispatch in the IME service's `InfoBarRenderer` action
sink — exactly the existing pattern for `RequestOverlayPermission`
(`DictateInputMethodService.java:1606-1668`). Side-channel parameters
that depend on state (e.g. `quota_exceeded`'s `billingUrl`, derived from
`providerKey`) are read **before** dispatch, because the dismiss
mutation clears `pipelineError`.

### D6 — Keep the `PromptVisibilityController` mutex (commit `7ee502c`)

ADR-0006 §"Container-level mutex" specifies `info_cl` VISIBLE ↔
`prompts_keyboard_cl` GONE. Commit `7ee502c` already added it
(`infoBarActive -> false` in `PromptVisibilityController.render()`).
Once `InfoBarController` is gone and `InfoBarRenderer` is the *sole*
writer of `info_cl` driven by the selector, the mutex's
`InfoBarSelector.select(state).isNotEmpty()` check is correct and
race-free. No change needed — just verified.

## 4. Work Blocks

> Commit prefix per chunk: `[<Block>.<Chunk>] <title> (dictate-infobar-migration)`.

### Block A — State foundation

- **A.1** — `PipelineErrorKind` enum + `PipelineError` data class
  (new file under `state/`). Add `DictateUiState.pipelineError:
  PipelineError? = null`; update `initial()`. Reducer/data-class tests.
- **A.2** — `Action.PipelineAction.ReportTransientError(kind,
  providerKey, createdAt)` + `ClearTransientError`. `PipelineModule`
  reducer arms: set on `ReportTransientError`, clear on
  `ClearTransientError`, **and** clear on pipeline-start
  (`TriggerPipeline`/`StartPipeline` — replaces the imperative
  `dismiss()` at `:4118`/`:4225`). `PipelineModuleTest` arms.
- **A.3** — `AppPromptsState` + `DictateUiState.appPrompts`;
  `Action.AppPromptsAction` (`Refresh` + `DismissUpdate`/`ConfirmUpdate`
  /`DismissRate`/`ConfirmRate`/`DismissDonate`/`ConfirmDonate`); new
  `AppPromptsModule` + registration in the module registry. Dismiss/
  confirm reducer arms flip the `*Due` boolean and emit
  `Effect.PersistPref` for the flag writes (AC-7). `AppPromptsModuleTest`.

### Block B — Selector producers

- **B.1** — Pipeline-error producer in `InfoBarSelector.select()`:
  `state.pipelineError?.let { add(InfoBarItem(...)) }`. Maps each
  `PipelineErrorKind` → string resource + `InfoBarStyle.ERROR`.
  `quota_exceeded`: derive provider display-name via
  `AIProvider.fromPersistKey(providerKey)?.displayName ?: "API"` into
  `textArgs`; `confirmAction` non-null only when `billingUrl != null`
  (AC-6). `dismissAction = ClearTransientError`; confirm actions per D5.
- **B.2** — App-prompts producer: three `if (state.appPrompts.xxxDue)`
  blocks → `InfoBarItem` with `InfoBarStyle.INFO`, `createdAt` from
  `BuildConfig.VERSION_BUILD_TIME` (stable, per ADR-0006 §Failure-Modes).
- **B.3** — Selector tests in `InfoBarSelectorTest.kt` for all 8 types,
  including the dismiss/confirm-removes-item tests (AC-5) and the
  `quota_exceeded` provider/billing matrix.

### Block C — Wiring & legacy removal

- **C.1** — `onPipelineError(errorInfoKey, vibrate, providerName)`:
  replace `showInfo(errorInfoKey, providerName)` with a dispatch of
  `ReportTransientError(kind, providerKey, createdAt)`. Add a
  `String → PipelineErrorKind?` mapping (drop `timeout`/`cancelled`;
  unknown → `INTERNET_ERROR` fallback, matching today's else-less
  behaviour). `PipelineFailed` dispatch stays.
- **C.2** — `onStartInputView` update/rate/donate checks (`:3298-3312`):
  replace the three `showInfo(...)` calls with one
  `AppPromptsAction.Refresh(updateAvailable, ratePromptDue,
  donatePromptDue)` dispatch (computed from the same pref reads + the
  `dbExecutor` `totalAudioTime` query).
- **C.3** — Re-point `InfoBarRenderer` construction
  (`DictateInputMethodService.java:1592-1668`) from
  `overlay_permission_infobar`/`overlay_permission_*` to `info_cl` /
  `info_tv` / `info_yes_btn` / `info_no_btn`. Extend the action-sink to
  handle the new confirm side-channels (Settings, Play-Store, PayPal,
  billing URL) — D5.
- **C.4** — Delete `InfoBarController.kt`; remove the field (`:321`),
  the construction (`:963-968`), the two private wrappers (`:4989`,
  `:4993`), and the five imperative `dismiss()` calls (`:2163`,
  `:4118`, `:4225`, `:5062`, `:5611` — superseded by reducer-driven
  clearing). Remove the now-dead `infoBarController == null` guard
  (`:4546`).

### Block D — Layout

- **D.1** — Delete the `overlay_permission_infobar` container + its 3
  children (`activity_dictate_keyboard_view.xml:290-346`). Verify the
  `info_cl` → `prompts_keyboard_cl` → `edit_buttons_keyboard_ll` →
  `main_buttons_cl` chain is intact and `info_cl`'s children
  (`info_tv`/`info_yes_btn`/`info_no_btn`) match what `InfoBarRenderer`
  expects (ids, button styling).

### Block E — Verify & docs

- **E.1** — Full `./gradlew test` green; `assembleDebug`; install;
  device-verify: force a network error (airplane mode mid-transcription)
  → error bar in `info_cl`, readable + tappable, prompts hidden; tap
  dismiss → bar gone. Verify update/rate prompts still surface.
- **E.2** — ADR-0006 `Proposed` → `Accepted`; Decision-History entry for
  D1 (top-level `pipelineError` vs. `state.pipeline.transientError`),
  D2 (`AppPromptsState` resolving the missing `state.prefs` axis), D3
  (`timeout` dropped). Cross-link this plan in ADR-0006 `## References`.

## 5. Risks & Notes

- **Big-bang risk** — `InfoBarController` deletion + container swap land
  together; partial states are not shippable. Mitigation: the selector
  is pure and unit-tested per type (B.3) before C.4 deletes the legacy
  path; Block C is one reviewable unit.
- **`createdAt` for `pipelineError`** — the pure reducer cannot read a
  clock; `onPipelineError` stamps `System.currentTimeMillis()` into the
  action. App-prompts use `BuildConfig.VERSION_BUILD_TIME`.
- **Out of scope** — ADR-0006's `OVERLAY_BLOCK_HINT` LayoutMode (overlay
  surface transform when items exist) is a separate concern; not touched.

## 6. References

- ADR: `docs/decisions/0006-ui-info-bar-state-derived-items.md`
- Research: [research/infobar-territory-map.md](research/infobar-territory-map.md)
- Related fix (already landed): commit `3c72d2a` — widget-send
  `canCommitToHost` axis correction (the sibling bug from the same
  investigation; independent of this plan).
