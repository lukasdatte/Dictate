---
name: infobar-territory-map
description: Complete map of the legacy InfoBarController + the new InfoBarSelector/InfoBarRenderer system — the blueprint for finishing the ADR-0006 big-bang migration.
status: Research
---

# InfoBar Territory Map — Legacy `InfoBarController` vs. State-Derived `InfoBarSelector`

## 1. Purpose

Dictate has **two** info-bar systems running side by side:

- **Legacy** — `InfoBarController` (imperative `showInfo(type)`), renders into `info_cl`.
- **New** — `InfoBarSelector` (pure `(DictateUiState) -> List<InfoBarItem>`) +
  `InfoBarRenderer` (a `StateFlow` collector), renders into `overlay_permission_infobar`.

ADR-0006 decided the legacy system is fully migrated into the selector
and deleted ("big-bang migration"). The migration was only half done.
This document maps the territory so the migration can be finished.

## 2. Findings

### 2.1 The two systems render to *different* physical views

| System | Renderer | Container | In the layout constraint chain? |
|---|---|---|---|
| Legacy | `InfoBarController` | `info_cl` (XML :215) | **Yes** — `prompts_keyboard_cl` is `Top_toBottomOf="@id/info_cl"`; `info_cl` pushes content down. |
| New | `InfoBarRenderer` | `overlay_permission_infobar` (XML :291) | **No** — free-floating, `Top_toTopOf="parent"`, nothing chained below it → it *overlaps* and (low XML Z-order) is *painted over* by `edit_buttons_keyboard_ll` (:349) and `prompts_keyboard_cl` (:522). |

**This is the InfoBar Z-order bug.** `InfoBarRenderer`'s KDoc claims it
drives `info_cl`; the actual wiring (`DictateInputMethodService.java:1600-1601`)
passes `overlay_permission_infobar`. The migration must re-point
`InfoBarRenderer` at `info_cl` and delete `overlay_permission_infobar`.

### 2.2 `InfoBarController` — shape

`app/src/main/java/net/devemperor/dictate/core/InfoBarController.kt` (168 lines).

- **Constructor:** `infoCl`, `infoTv`, `infoYesButton`, `infoNoButton`,
  `openSettings: () -> Unit`, `startActivityAction: (Intent) -> Unit`,
  `sp: SharedPreferences`, `resources`, `themeProvider`.
- **`onStateChanged(contentArea, isSmallMode)`** — **dead code, zero callers.**
  Was meant to be called by the deleted `KeyboardStateManager`. Its gate
  flag `suppressDisplay` is therefore permanently `false`. Drop it.
- **`dismiss()`** — `infoCl.visibility = GONE`.
- **`showInfo(type)` / `showInfo(type, providerName)`** — the dispatch
  `when` over 9 type strings.

### 2.3 The 9 types — two origin classes

**App-lifecycle prompts (3)** — direct call sites:

| Type | String res | Confirm | Dismiss writes | Style | Trigger |
|---|---|---|---|---|---|
| `update` | `dictate_update_installed_msg` | Settings + dismiss | `Pref.LastVersionCode = VERSION_CODE` | INFO (blue) | `LastVersionCode < BuildConfig.VERSION_CODE` (`:3299`) |
| `rate` | `dictate_rate_app_msg` | Play-Store URL + flag + dismiss | `Pref.FlagHasRated = true` | INFO | `!FlagHasRated && totalAudioTime ∈ (180,600]` (`:3306`) |
| `donate` | `dictate_donate_msg` | PayPal URL + flags + dismiss | `Pref.FlagHasDonated = true` **and** `Pref.FlagHasRated = true` | INFO | `!FlagHasDonated && totalAudioTime > 600` (`:3308`) |

**Pipeline errors (6, one dead)** — all funnel through
`onPipelineError` → `showInfo(errorInfoKey, providerName)` (`:4547`):

| Type | String res | Confirm | Style | Note |
|---|---|---|---|---|
| `timeout` | `dictate_timeout_msg` | — (no button) | ERROR | **DEAD — no caller.** Drop. |
| `invalid_api_key` | `dictate_invalid_api_key_msg` | Settings + dismiss | ERROR | |
| `quota_exceeded` | `dictate_quota_exceeded_msg` | **conditional** — billing-URL button only if `provider.billingUrl != null` | ERROR | provider display-name interpolated as `%s` |
| `model_not_found` | `dictate_model_not_found_msg` | Settings + dismiss | ERROR | |
| `bad_request` | `dictate_bad_request_msg` | Settings + dismiss | ERROR | |
| `internet_error` | `dictate_internet_error_msg` | — (no button) | ERROR | |

No type auto-dismisses. `quota_exceeded` is the only one with dynamic
content (provider name → `textArgs`; `billingUrl` presence → whether
`confirmAction` is non-null).

### 2.4 Error-key origin

`onPipelineError(errorInfoKey, vibrate, providerName)`
(`DictateInputMethodService.java:4533-4552`): `errorInfoKey` IS the type
string — no service-side mapping table. The mapping lives in
`AIProviderException.toInfoKey()` (`ai/AIProviderException.kt:30-39`):
`INVALID_API_KEY→invalid_api_key`, `RATE_LIMITED→quota_exceeded`,
`MODEL_NOT_FOUND→model_not_found`, `BAD_REQUEST→bad_request`,
`SERVER_ERROR/NETWORK_ERROR/UNKNOWN→internet_error`,
`CANCELLED→cancelled` (never reaches the bar — orchestrator returns
early). Producers: `PipelineOrchestrator.kt:232,274,1468,1473,1491,1496`.

### 2.5 All call sites

- **Construction:** `DictateInputMethodService.java:963-968`; field `:321`.
- **`showInfo`:** `:3299` (update), `:3306` (rate), `:3308` (donate),
  `:4547` (pipeline errors). Private wrappers `:4989`, `:4993`.
- **`dismiss`:** `:2163`, `:4118`, `:4225`, `:5062`, `:5611` — all
  imperative "clear the bar on a UI transition". No state behind them.
- **Null guard:** `:4546` in `onPipelineError`.

### 2.6 State-model gaps

`DictateUiState` has **no** error/notification axis today.
`PipelineUiState` (`Idle|Preparing|Running|ReprocessStaging`) carries no
error key — on error the FSM goes straight to `Idle` and the key is lost
from state. `ResendState` carries manual-paste hints only.

To make the 9 types selector-driven, **new state must be added**:

- **Pipeline errors** — ADR-0006 §"Cross-Module Producer pattern"
  explicitly says *"PipelineModule will gain a `transientError` field"*.
  Not done. Payload needs: error kind, provider key (for
  `quota_exceeded`), `createdAt`. Plus `PipelineAction.ClearTransientError`.
- **App-lifecycle prompts** — ADR-0006's selector sketch reads a
  `state.prefs` axis that **does not exist**. `update/rate/donate` need
  either a new pref-mirror axis or a small resolved-boolean axis. Note
  `totalAudioTime` is a DB aggregate query (`usageDao.getTotalAudioTime()`,
  `:3302`) — it must reach state somehow for a pure selector to read it.

### 2.7 Lifecycle / behavioural differences to reconcile

- No legacy bar auto-dismisses.
- Legacy bars do **not** survive IME-View recreation (`InfoBarController`
  rebuilt fresh each `onCreateInputView`); update/rate/donate re-checked
  on every `onStartInputView`; pipeline errors are lost on re-inflate.
- The new `InfoBarRenderer` re-subscribes on `start()` → whatever is in
  state re-appears automatically. A RAM `transientError` therefore
  survives a view re-inflate (better) but not process death (transient
  by definition — intended).
- The 5 imperative `dismiss()` calls have no state equivalent. Drop them
  for app-prompts (selector re-derives); for transient errors, keep an
  explicit clear on new-pipeline-start so a stale error does not linger.

### 2.8 ADR-0006 constraints the migration must honour

`docs/decisions/0006-ui-info-bar-state-derived-items.md` (Status: Proposed):

- Items = pure function of state, recomputed every emit; no stored list.
- **Dismiss = natural-source mutation** — each item's `dismissAction`
  mutates its source so the selector stops producing it. No shared
  "dismissed" table.
- **Cross-Module Producer pattern** — producers are state reads, not
  classes.
- **Big-bang migration** of all 9 cases + `overlay_permission_infobar`
  in one coordinated change; `InfoBarController.kt` deleted; the two
  containers consolidate into one `info_cl`.
- **Container-level mutex** — `info_cl` VISIBLE ↔ `prompts_keyboard_cl`
  GONE (the `PromptVisibilityController` mutex, already added in commit
  `7ee502c`).
- Both `confirmAction` and `dismissAction` MUST be declared `Action`
  subtypes — lambdas are rejected.
- FAILED sessions are **not** info-items (they have a Retry button);
  only *transient* errors that never reached session-state surface here.
- Every new item-factory needs a "dismiss action actually removes the
  item" test (ADR-0006 §Failure-Modes).

### 2.9 Tests

`app/src/test/java/net/devemperor/dictate/state/infobar/InfoBarSelectorTest.kt`
is the pattern: plain JUnit4, no Mockito/Robolectric, `defaultState().copy(...)`
→ `select()` → assert on the `List<InfoBarItem>`. The 9 legacy cases are
explicitly noted as not yet tested. No `InfoBarRenderer` / `InfoBarController`
tests exist.

## 3. Conclusions

The migration is a contained big-bang with three structural moves:

1. **Add state** — a `transientError` axis (pipeline errors) and an
   app-prompt axis (`update/rate/donate`), each with reducer + actions.
2. **Add two selector producers** — pipeline-errors and app-prompts —
   covering the 8 live types (`timeout` dropped).
3. **Consolidate the surface** — re-point `InfoBarRenderer` at `info_cl`,
   delete `overlay_permission_infobar`, delete `InfoBarController` and
   its 4 `showInfo` + 5 `dismiss` call sites, rewire `onPipelineError`
   and the lifecycle checks to dispatch actions.

`OverlayPermissionInfobarRenderer.kt` / `OverlayOnboardingObserver.kt`
(ADR-0006 deletion targets) are already gone — only the
`InfoBarController` half remains.

## 4. Key file index

| Artifact | Path |
|---|---|
| Legacy controller (delete) | `core/InfoBarController.kt` |
| Error→key map | `ai/AIProviderException.kt:30-39` |
| `onPipelineError` | `core/DictateInputMethodService.java:4533-4552` |
| Error-key producers | `core/PipelineOrchestrator.kt:232,274,1468,1473,1491,1496` |
| `showInfo` sites | `DictateInputMethodService.java:3299,3306,3308,4547` |
| `dismiss` sites | `DictateInputMethodService.java:2163,4118,4225,5062,5611` |
| Controller construction | `DictateInputMethodService.java:963-968` |
| `InfoBarRenderer` wiring | `DictateInputMethodService.java:1592-1668` |
| Selector + types | `state/infobar/{InfoBarSelector,InfoBarItem,InfoBarMessage,InfoBarStyle,InfoBarRenderer}.kt` |
| State root | `state/DictateUiState.kt` |
| Actions | `state/Action.kt` |
| Layout | `res/layout/activity_dictate_keyboard_view.xml` — `info_cl`:215, `overlay_permission_infobar`:291 |
| Selector tests | `state/infobar/InfoBarSelectorTest.kt` |
| ADR | `docs/decisions/0006-ui-info-bar-state-derived-items.md` |
