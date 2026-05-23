---
date: 2026-05-15
type: Spec
status: Spec — programmer-ready
topic: b5-ime-activation-wiring
finding-ids: F-1, F-2, F-3
triggered-by: B5-VAL-SANITY validated-findings-B5.md F-1 / F-2 / F-3
block: B5 (Floating-Overlay — final implementation block)
agent-id: B5-VAL-RES-1
related-adrs: ADR-0005
related-specs: Spec 1 §5 / §8.x / §11.3, Spec 3 §5.0–§5.7 / §7.3
---

# §0 — IME-Activation Wiring (the keystone gap)

**Topic:** `b5-ime-activation-wiring`
**Finding-IDs:** F-1 (Critical), F-2 (Critical), F-3 (Important)
**Date:** 2026-05-15
**Status:** Spec — programmer-ready
**Agent:** B5-VAL-RES-1 (repair-sub-phase research)

This is the **single combined research probe** for the three findings
that the B5 sanity-check identified as one IME-level activation
surface. The Triangle-FSM (ADR-0005), ViewModeModule reduce-arms
(B2-C5), OverlayModule onboarding/permission arms (B2-C5), the
attach/detach collapse rule (`syncOverlayBackendAttachment`, B5-C18),
`OverlayPermissionObserver` (B5-C17) and `OverlayPermissionOnboardingActivity`
(B5-C17) are all implemented and unit-green — but **inert in
production** because `DictateInputMethodService.java` was never wired
to drive them. The plan's central deliverable (Geist-Widget-Bug
elimination, the Triangle-FSM) cannot be claimed end-to-end until this
lands.

---

# §1 Findings — three gaps, one surface

| ID | Sev | Gap (one line) |
|----|-----|----------------|
| **F-1** | Critical | IME never dispatches `OnImeViewShown` / `OnImeViewHidden` → T3/T5/T6 dead; HOVER auto-overlay unreachable; T7 latent. |
| **F-2** | Critical | WIDGET-toggle-without-permission is a silent dead button — no info-bar, no `RequestOverlayPermission` dispatch, `OpenOverlayPermissionSettings` is a no-op, Activity never started. No path to grant the permission. |
| **F-3** | Important | IME never calls `overlayPermissionObserver.refresh()` → just-granted permission not picked up until cold-start; runtime-revoke causes unbounded inflate-retry. |

**Why they are one surface.** All three are missing IME→orchestrator
wiring in the *same file* (`DictateInputMethodService.java`), keyed off
the *same two IME-lifecycle hooks* (`onStartInputView` /
`onFinishInputView`), reaching the *same dispatch surface*
(`pipelineBinder.dispatch(Action)` + the `LocalBinder`
`overlayPermissionObserver` / `overlayPermissionGate` accessors that
already exist, [DictatePipelineService.kt:1085-1096]). One coherent
edit closes all three. Splitting them would create three partial
diffs touching the same two methods — a merge/coherence hazard.

**Evidence (production code, verified 2026-05-15):**

- `grep -n "ViewModeAction\|OnImeView\|dispatch(" DictateInputMethodService.java`
  → zero `ViewModeAction` / `OnImeView*` references; the only
  `pipelineBinder.dispatch(...)` call is
  `LanguageAction.RefreshFromPref` (line 826). The IME file is not in
  the B5 diff.
- `ViewModeModule.kt:90,100` — `OnImeViewHidden` / `OnImeViewShown`
  reduce-arms exist and are correct, but no producer.
- `LayoutCatalog.kt:110,217` — `WIDGET_TOGGLE` `actionResolver = { _, _ -> ToggleViewModeWidget }`
  unconditionally; no permission branch.
- `ViewModeModule.kt:116-117` — `ToggleViewModeWidget` returns `null`
  (silent no-op) when `!ctx.global.overlay.hasPermission`.
- `OverlayModule.kt:191-198` — `OpenOverlayPermissionSettings`
  runEffect body is `Unit` (documented Phase-1 placeholder).
- `grep -rn "shouldShowOnboarding\|OverlayPermissionOnboardingActivity\|RequestOverlayPermission"`
  over `app/src/main/java/` → only the *definitions* + the standalone
  Activity; **no production caller**.
- `OverlayPermissionObserver.refresh()` ([OverlayPermissionObserver.kt:84])
  has no production call site; only `init()` is wired
  ([DictatePipelineService.kt:601]).

---

# §2 Constraint inventory

### C-1 — IME-lifecycle callback semantics (canonical AOSP behaviour)

From the prior depth-research
[`_pending-ime-lifecycle-view-recreation.md`](_pending-ime-lifecycle-view-recreation/_pending-ime-lifecycle-view-recreation.md)
§A.2/§A.3 (AOSP-verified) and confirmed against the current code
(`onWindowShown`/`onWindowHidden`/`onConfigurationChanged` are **not**
overridden — the IME relies on framework defaults):

| Callback | Fires when | Suitability for the FSM trigger |
|----------|-----------|---------------------------------|
| `onStartInputView(EditorInfo, restarting)` | View built + about to show, for a target field. **Always after `onStartInput`.** Fires on rotation re-show (`restarting=true`) and on every editor focus. | **Correct "shown" hook** — Spec 1 §11 + ADR-0005 T5/T6 + architecture-doc §5 all name it. |
| `onFinishInputView(finishingInput)` | View being hidden (app-switch, back, editor-switch). | **Correct "hidden" hook** — Spec 1 §11 + ADR-0005 T3/T4 + architecture-doc §5 all name it. |
| `onWindowShown` / `onWindowHidden` | Window animation in/out. Can fire *without* a new editor; semantics differ from view-show. | **Rejected** — fires for animation, not "input view active for a field"; would mis-fire T3/T5 on transient window hides. Not the spec hook. |
| `onCreateInputView` | View tree (re-)inflated (first show + every config-change). | Used for `refresh()` *also* (Spec 3 §5.0 lists both), but **not** for `OnImeView*` — it does not fire on plain app-switch return (view stays in memory), so it would miss T5/T6. |

The spec is unambiguous and the FSM truth-table was *designed around*
these two hooks (ADR-0005 §Decision table T3/T4/T5/T6 explicitly
name `onFinishInputView` / `onStartInputView`; architecture-doc
`triangle-fsm.md` §5 shows the exact `override` bodies). **No new
design decision is needed for the hook choice** — it is dictated by
three independent canonical sources that agree.

### C-2 — R.2 reducer-purity

The IME dispatches *Actions only*; it must NOT compute view-mode,
read `Settings.canDrawOverlays`, or branch on `hasPermission` to
decide the FSM transition. `computeViewMode` lives in
`ViewModeModule.reduce` ([ViewModeModule.kt:178]); the IME's job is to
emit the boundary action and let the reducer + cascades react. (One
narrowly-scoped exception in §4: the **info-bar trigger** reads
`overlayPermissionGate.shouldShowOnboarding()` at *click time* in the
View-layer — that is a UI-resolver read, not a reducer read, and is
exactly the SRP split Spec 3 §5.1/§13.2 prescribes.)

### C-3 — LocalBinder is the dispatch surface (already complete)

`pipelineBinder.dispatch(Action): DispatchOutcome` is typed since
B2-C7 ([DictatePipelineService.kt:976]). IME-side binds in
`onCreateInputView` and unbinds in `onDestroy`
([DictateInputMethodService.java:558,1151-1159]). The binder also
exposes the two accessors the fix needs:

- `overlayPermissionObserver: OverlayPermissionObserver`
  ([line 1085-1086]) — for F-3 `refresh()`.
- `overlayPermissionGate: OverlayPermissionGate`
  ([line 1095-1096]) — for F-2 `shouldShowOnboarding()` at click time.

No binder change is required. All call sites guard on
`pipelineBinder != null` (the established pattern).

### C-4 — Permission-onboarding UX (Spec 3 §5.3/§5.4/§5.6)

- WIDGET-toggle without permission → in-IME **info-bar** above the
  keyboard (Spec 3 §5.3), NOT the standalone Activity (the Activity is
  the *secondary* surface — Spec 3 §5.2 + the Activity's own KDoc say
  the in-IME info-bar is the primary/high-traffic path).
- Info-bar visibility is driven by `state.overlay.onboardingPending`
  (Spec 3 §5.3); the reducer sets it; the View renders it.
- `onboardingPending` is cleared by the cascade
  `OverlayModule.onCrossModuleStateChange` on the
  `→ WIDGET` boundary (Spec 3 §5.4 snippet) **— this cascade arm is
  NOT yet implemented** (see §4, it is part of the F-2 fix).
- Grant-button → Settings deep-link
  (`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`) with
  `FLAG_ACTIVITY_NEW_TASK` (Spec 3 §5.2 — mandatory from a
  Service/IME context; the IME is not an Activity).
- HOVER without permission → **no** overlay, **no** info-bar (user is
  outside the keyboard); the FGS notification is the status indicator
  (Spec 3 §5.6, and F-4's `NotifyOverlayPermissionRequired` Effect —
  out of scope for this research, fixed in the same wave separately).

### C-5 — Spec 1 §8.x View-Recreate contract vs. current code (DEVIATION — important)

Spec 1 §8.x prescribes `viewScope.cancel()` + `KeyboardLayoutManager.detachBackend()`
in `onFinishInputView()`. **The current code does NOT do this** — it
detaches `imeViewBackend` only in `onDestroy()`
([DictateInputMethodService.java:1096-1104]) and cleans view
controllers in `cleanupOldControllers()` (called from
`onCreateInputView`, [line 566]). `onFinishInputView`
([line 1053-1088]) does its own 3-state legacy logic (recording-pause /
pipeline-continue / idle-cleanup) and does **not** touch the backend.

This is **load-bearing for F-1**: because `onFinishInputView` does
NOT detach the ImeViewBackend, adding `OnImeViewHidden` dispatch there
is safe — the orchestrator's `syncOverlayBackendAttachment` collector
([DictatePipelineService.kt:651]) attaches the **OverlayBackend** on
the resulting `viewMode != KEYBOARD` and the service-side state-collect
renders HOVER via the OverlayBackend (a *separate* backend, service-
owned, surviving view-recreation). The ImeViewBackend staying attached
during HOVER is harmless: `KeyboardLayoutManager` routes the render-
tick to the backend whose `backendType` matches the LayoutMode
(`OVERLAY_5BUTTON` → OverlayBackend; the ImeViewBackend simply does
not receive HOVER renders). Do **not** "fix" §8.x as part of this
repair — it is a documented divergence the codebase deliberately
took (the StateFlow-collect render model replaced the
`viewScope.cancel` model; §8.x is stale spec text). Recorded here so
the implementer does not chase it.

---

# §3 F-1 Solution — lifecycle hooks + T1–T7 trace-table

### Recommendation (no design freedom — dictated by ADR-0005 + Spec 1 §11 + architecture-doc §5)

```java
// DictateInputMethodService.java — onStartInputView (~line 1650),
// AFTER super.onStartInputView(info, restarting):
if (pipelineBinder != null) {
    pipelineBinder.dispatch(
        net.devemperor.dictate.state.Action.ViewModeAction.OnImeViewShown.INSTANCE);
}

// DictateInputMethodService.java — onFinishInputView (~line 1053),
// AFTER super.onFinishInputView(finishingInput) and the existing
// 3-state legacy block (place it at the very end of the method, see §6):
if (pipelineBinder != null) {
    pipelineBinder.dispatch(
        net.devemperor.dictate.state.Action.ViewModeAction.OnImeViewHidden.INSTANCE);
}
```

(Java accesses Kotlin `data object` singletons via `.INSTANCE` — same
idiom as the existing `LanguageAction.RefreshFromPref.INSTANCE` call
at line 826.)

**Rationale (1 line):** `onStartInputView`/`onFinishInputView` are the
input-view-active boundary; ADR-0005's Decision table, Spec 1 §11
(lines 2672-2676), and `triangle-fsm.md` §5 all independently name
exactly this pair — `onWindowShown/Hidden` is animation-scoped and
would mis-fire.

### T1–T7 trace-table (proving correctness through the chosen hooks)

`pipelineActive = pipeline !is Idle || recording.isActiveOrPaused`
(centralised in `ViewModeModule.isPipelineActive`,
[ViewModeModule.kt:198]). `userPrefersWidget` set by
`OverlayModule.onCrossModuleStateChange` on the KEYBOARD→WIDGET edge.

| T | From→To | Producer | Action dispatched | Reducer result | Verified |
|---|---------|----------|-------------------|----------------|----------|
| **T1** | KEYBOARD→WIDGET | `WIDGET_TOGGLE` click | `ToggleViewModeWidget` | `hasPermission` ⇒ WIDGET; else `null` (→ F-2 info-bar) | ✅ resolver exists; F-2 adds the no-perm arm |
| **T2** | WIDGET→KEYBOARD | `OVERLAY_CLOSE`/`WIDGET_TOGGLE` click | `ToggleViewModeWidget` | KEYBOARD; cascade SmallMode + `userPrefersWidget=false` | ✅ |
| **T3** | KEYBOARD→HOVER | **`onFinishInputView`** (F-1) | `OnImeViewHidden` | `computeViewMode(false,false,true)=HOVER` | ✅ once F-1 lands |
| **T4** | WIDGET→HOVER | **`onFinishInputView`** (F-1) | `OnImeViewHidden` | `computeViewMode(false,true,true)=HOVER`; `userPrefersWidget` stays | ✅ once F-1 lands |
| **T5** | HOVER→KEYBOARD | **`onStartInputView`** (F-1) | `OnImeViewShown` | `computeViewMode(true,false,*)=KEYBOARD` | ✅ once F-1 lands |
| **T6** | HOVER→WIDGET | **`onStartInputView`** (F-1) | `OnImeViewShown` | `computeViewMode(true,true,*)=WIDGET` (persist bit) | ✅ once F-1 lands |
| **T7** | HOVER→KEYBOARD | `PipelineModule` observer (Done) | `OnPipelineDone` | `computeViewMode(state!=HOVER,*,false)=KEYBOARD` | ✅ independent of F-1; **but only reachable once HOVER is reachable** (i.e. once T3/T4 fire) |

**Key correctness point:** T7 (the Geist-Widget guard, ADR-0005
§"Required mechanics" #6) is *already correct* in the reducer, but is
**latent** — HOVER is only ever entered via T3/T4, which require F-1.
So F-1 is the structural precondition that makes the plan's raison
d'être (T7) actually exercisable end-to-end.

### View-recreation handling (rotation / theme-switch)

Per `_pending-ime-lifecycle-view-recreation.md` §A.3: a config-change
fires `onConfigurationChanged → onInitializeInterface →
onCreateInputView → onStartInputView(restarting=true)`. There is **no
`onFinishInputView`** between the old and new view on a pure rotation
(the framework re-shows directly). Consequences for the FSM:

- `OnImeViewShown` fires again on the post-rotation
  `onStartInputView(restarting=true)`. This is **idempotent and
  correct**: `computeViewMode(true, userPrefersWidget, *)` recomputes
  to the same KEYBOARD/WIDGET value; `ViewModeModule.reduce` returns
  `null` when `next == state` (no spurious cascade — verified
  [ViewModeModule.kt:106]).
- The `restarting` boolean is **deliberately ignored** for the
  dispatch. Dispatching unconditionally is safe (idempotent reducer)
  and simpler than trying to suppress restart-shows; suppressing on
  `restarting=true` would *break* T6 (rotation while in HOVER must
  recompute to WIDGET/KEYBOARD when the view returns). Unconditional
  dispatch is the correct call. Document this with a one-line comment
  at the dispatch site.
- Editor-switch within the same app fires
  `onFinishInputView(finishingInput=false)` then
  `onStartInputView(restarting=true)`. The transient
  `OnImeViewHidden`→`OnImeViewShown` pair recomputes to the same mode
  (KEYBOARD→KEYBOARD) → both reduce to `null`. Harmless. (If
  `pipelineActive` is true during the brief hidden window the FSM
  would momentarily compute HOVER then back — but `onFinishInputView`
  and the subsequent `onStartInputView` are synchronous and the
  StateFlow is conflated/distinct; the intermediate HOVER is
  collapsed before any render. Acceptable and matches Spec 3 §11.6
  edge-case acceptance "sehr selten + nicht-blockierend".)

---

# §4 F-2 Solution — in-IME info-bar + trigger path + Settings launch

This is the only finding with genuine design freedom (Spec 3 §5.4
explicitly leaves the trigger-arm "TBD"). Decision below, with
rationale.

### §4.1 Trigger-arm decision (resolving Spec 3 §5.4 "Auslöser TBD")

Spec 3 §5.4 offers two options:
(a) extend `RequestOverlayPermission` to also set
`onboardingPending=true` + emit `OpenOverlayPermissionSettings`; or
(b) a dedicated `ShowOnboarding` reducer-arm.

**Decision: option (a-modified) — a *dedicated info-bar arm* separate
from the Settings-launch arm.** Concretely:

- **Keep `RequestOverlayPermission` as the Settings-launch action**
  (it already emits `Effect.OpenOverlayPermissionSettings`,
  [OverlayModule.kt:146-149]) — dispatched by the **info-bar's
  Grant button**, not by the WIDGET-toggle.
- **Add a new reducer-arm for the info-bar trigger.** The cleanest
  fit that respects R.2 + the existing action vocabulary is to add
  `Action.OverlayAction.ShowOverlayOnboarding` (data object) with an
  `OverlayModule.reduce` arm:
  `state.copy(onboardingPending = true)`, no effect. Reasoning:
  reusing `RequestOverlayPermission` for *both* "show the info-bar"
  and "launch Settings" conflates two UX steps (the info-bar must
  appear *before* the user decides to open Settings); a dedicated
  arm keeps each action single-purpose (SRP) and keeps the
  Spec-3-§5.4 reducer snippets coherent. `ShowOverlayOnboarding` is
  symmetric with the already-present `MarkOverlayOnboardingShown` /
  `DismissOverlayOnboarding` / `RequestOverlayPermission` family.
- **Auto-cleanup cascade (Spec 3 §5.4):** add to
  `OverlayModule.onCrossModuleStateChange` the arm:
  `if (prev.viewMode != WIDGET && next.viewMode == WIDGET && next.overlay.onboardingPending) listOf(MarkOverlayOnboardingShown)`
  — clears `onboardingPending` once the user successfully reaches
  WIDGET (so a stale info-bar does not linger). This arm is in the
  Spec 3 §5.4 snippet but not yet implemented.

> Implementer note: adding one `data object ShowOverlayOnboarding` to
> `Action.OverlayAction` + one reduce-arm + one cascade-arm is the
> minimal coherent change. If the orchestrator prefers strict reuse
> of the existing `RequestOverlayPermission` action (option a-pure),
> the fallback is: WIDGET-toggle-no-perm dispatches
> `RequestOverlayPermission`, and its reduce-arm additionally sets
> `onboardingPending=true` *and* keeps `Effect.OpenOverlayPermissionSettings`.
> That collapses the info-bar step (Settings opens immediately on the
> first toggle, no explainer bar). **Rejected as the primary** because
> Spec 3 §5.3 explicitly wants the explainer bar *first* (user
> consent before a context-switch to Settings). The dedicated-arm
> design is the spec-faithful one.

### §4.2 WIDGET-toggle-without-permission path

Today `LayoutCatalog.kt:110/217` resolves `WIDGET_TOGGLE` →
`ToggleViewModeWidget` unconditionally, and the reducer no-ops when
permission is missing ([ViewModeModule.kt:116]). The fix makes the
**actionResolver permission-aware** — this is the spec-prescribed
location (Spec 3 §8 / ADR-0005 §"Required mechanics" #3: "the
click-listener checks `state.overlay.hasPermission`; if missing,
`actionResolver` returns `RequestOverlayPermission` … instead of
`ToggleViewModeWidget`"). Concretely, replace the lambda with a named
resolver (analogous to `resolveOverlayCloseAction` in
`ActionResolvers.kt:239`):

```kotlin
// ActionResolvers.kt — new resolver
fun resolveWidgetToggleAction(
    state: DictateUiState,
    @Suppress("UNUSED_PARAMETER") services: ModuleServices,
): Action =
    if (state.overlay.hasPermission) Action.ViewModeAction.ToggleViewModeWidget
    else Action.OverlayAction.ShowOverlayOnboarding
```

…and wire it at the four `WIDGET_TOGGLE` slots in `LayoutCatalog.kt`
(lines ~110, ~217, ~263/362/410 — verify each `WIDGET_TOGGLE`
`actionResolver`). `state.overlay.hasPermission` is the **mirrored
axis** (kept fresh by the observer — F-3), so this stays R.2-pure
(the resolver reads state, not `Settings.canDrawOverlays`).

The reducer's existing `!hasPermission ⇒ null` guard
([ViewModeModule.kt:116]) stays as defence-in-depth (ADR-0005
§8 "even if a developer changes the actionResolver and breaks the
gate at the UI layer, the reducer still refuses").

### §4.3 In-IME info-bar location + binding

**Location decision:** the info-bar is a sibling element in the IME
root layout `activity_dictate_keyboard_view.xml` (root is a
`ConstraintLayout` id `dictate_keyboard_view`, [line 2-5]), placed
**above `main_buttons_cl`** per Spec 3 §5.3 ("oberhalb der
Tastatur-Buttons, Two-Row + Single-Row gleich"). Add a new
`res/layout/overlay_permission_infobar.xml` `<include>`-able block or
inline the LinearLayout from the Spec 3 §5.3 XML directly into the
root (inline is simpler — only one consumer). Constrain it
`app:layout_constraintTop_toTopOf="parent"` and re-constrain
`main_buttons_cl` top to the info-bar bottom; `visibility="gone"` by
default.

**Binding decision — NOT in `ImeViewBackend`.** The Spec 3 §5.3
sketch put `bindPermissionInfoBar` in `ImeViewBackend`, but the
**current `ImeViewBackend` only receives a `buttonViews: Map` — it has
no handle on the root view tree** ([ImeViewBackend.kt:85-93]; render
iterates `buttonViews`, never `findViewById` on a root). Threading the
root view + an `onAction` sink + the gate into `ImeViewBackend` purely
for the info-bar would widen its constructor and break its
button-map-only contract. **Sustainable choice:** bind the info-bar
**in the IME service itself** (`DictateInputMethodService.java`),
co-located with the existing `infoBarController` pattern (the IME
already owns an in-keyboard info-bar surface — `infoCl`/`infoTv`/
`infoYesButton`/`infoNoButton`, [line 595-598], driven by
`InfoBarController` [line ~656]). The IME already:

- subscribes to the pipeline `state` StateFlow (B1b state-collect),
- holds `dictateKeyboardView` (the inflated root),
- has `pipelineBinder.dispatch(...)` + the
  `pipelineBinder.overlayPermissionGate` accessor.

So the info-bar is wired the same way the existing info-bar is:
findViewById in `onCreateInputView`, visibility toggled from the
state-collect render pass on `state.overlay.onboardingPending`,
buttons dispatch `RequestOverlayPermission` (Grant) /
`DismissOverlayOnboarding` (Later) via `pipelineBinder.dispatch`.

> This is a documented **deviation from the Spec 3 §5.3 sketch**
> (binding moves from `ImeViewBackend` to the IME service). Rationale:
> the §5.3 sketch predates the B4 `ImeViewBackend` button-map-only
> contract; honouring the sketch would regress that contract's SRP.
> The IME service is the correct owner — it already owns the
> symmetric `InfoBarController` surface. Severity: Important,
> marker `plan-deviation-resolved` (mid-size, solution clear from
> plan knowledge + surrounding code).

### §4.4 `OpenOverlayPermissionSettings` runEffect — the Intent

`OverlayModule.runEffect` only has `ModuleServices`, which carries
**no Android `Context` and no activity-launcher**
([ModuleServices.kt:87-104] — `sharedPrefs`, `toastSink`,
`inputConnectionProvider` are the only Android-ish surfaces). So the
runEffect *cannot* `startActivity` directly without a new subsystem.

**Decision: launch the Settings intent from the IME-side info-bar
click handler, NOT from the runEffect.** This is exactly what Spec 3
§5.3's binding sketch does (`launchOverlayPermissionSettings(ctx)`
called in the Grant click handler) and what Spec 3 §5.2 prescribes
(launch "vom IME-Service aus"). The IME *is* a `Context`. The
`OverlayModule.Effect.OpenOverlayPermissionSettings` stays a no-op
placeholder **but its KDoc must be updated** to state that the launch
is owned by the IME-side info-bar handler (not "Phase-1 TODO"). The
`RequestOverlayPermission` reducer-arm still emits the Effect (for
future-proofing / a possible activity-launcher subsystem), but the
*effective* launch is the IME-side handler.

Concrete IME-side intent (Spec 3 §5.2, mandatory `FLAG_ACTIVITY_NEW_TASK`):

```java
// DictateInputMethodService — Grant-button click handler
Intent intent = new Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:" + getPackageName()));
intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);   // IME is not an Activity
startActivity(intent);
pipelineBinder.dispatch(Action.OverlayAction.RequestOverlayPermission.INSTANCE);
// (RequestOverlayPermission also marks onboarding-shown semantics; the
//  permission result is picked up by F-3's refresh() on return.)
```

This mirrors the **already-correct** intent in
`OverlayPermissionOnboardingActivity.buildOverlayPermissionSettingsIntent()`
([OverlayPermissionOnboardingActivity.kt:110-114]) one-for-one — Spec
3 §5.2 explicitly wants the two launch paths identical to avoid drift.
The standalone Activity remains the *secondary* surface (deep-link /
notification-action / `am start` testing) per its own KDoc; it is
**not** started from the WIDGET-toggle path (the in-IME info-bar is).

---

# §5 F-3 Solution — `observer.refresh()` trigger hook

```java
// DictateInputMethodService.java — onStartInputView (~line 1650),
// alongside the F-1 OnImeViewShown dispatch:
if (pipelineBinder != null) {
    pipelineBinder.getOverlayPermissionObserver().refresh();
}
```

(Kotlin property `overlayPermissionObserver` is accessed from Java as
`getOverlayPermissionObserver()`.)

**Hook choice:** `onStartInputView` — the moment the IME view returns
to the foreground, which is exactly when the user could be coming
back from the System-Settings deep link (Spec 3 §5.0:
"Vom IME-onStartInputView / onCreateInputView gerufen — User kommt aus
Settings zurück"). Placing it in `onStartInputView` (not
`onCreateInputView`) is the more reliable of the two spec-named hooks:
`onCreateInputView` does **not** fire on a plain app-switch return
(view stays in memory), whereas `onStartInputView` fires on **every**
view-show including the Settings→IME return where no config-change
occurred. Folding it next to the F-1 `OnImeViewShown` dispatch keeps
the activation wiring in one place.

**Why this closes both F-3 symptoms:**

1. *Grant-pickup latency* — on return from Settings,
   `onStartInputView` → `refresh()` → `OnOverlayPermissionChanged(true)`
   → `state.overlay.hasPermission=true`. The user's next WIDGET-toggle
   now resolves to `ToggleViewModeWidget` (F-2's permission-aware
   resolver reads the freshly-mirrored axis).
2. *Runtime-revoke busy-retry* — on a revoke while WIDGET/HOVER is
   active, the next `onStartInputView` (or the next time the IME view
   shows) → `refresh()` → `OnOverlayPermissionChanged(false)` →
   `OverlayModule.onCrossModuleStateChange` permission-loss arm
   ([OverlayModule.kt:254-259]) cascades `SetViewMode(KEYBOARD)` →
   `syncOverlayBackendAttachment` detaches the OverlayBackend → the
   `OverlayBackend.render()` inflate-retry loop
   ([OverlayBackend.kt:226-233]) stops because the backend is no
   longer attached/rendered. The observer flip is the single driver
   of the recovery cascade.

   *Residual:* a revoke that happens while the IME view is **already
   hidden + HOVER active** (the user is in another app) is not
   detected until the next `onStartInputView`. During that window the
   inflate-retry can still spin if a pipeline render-tick re-enters.
   The B5 sanity-check (F-3 "Suggested fix") explicitly accepts the
   IME `refresh()` as the **spec-mandated primary fix** and notes a
   backend-local belt-and-suspenders latch as an *optional* extra.
   **Recommendation: ship the IME `refresh()` only** (spec-faithful,
   minimal). If the orchestrator wants the residual closed, a
   one-shot guard in `OverlayBackend` (latch "inflate failed → do not
   re-attempt until next attach") is a separate, independently-shippable
   hardening — do **not** bundle it into this wave (it touches B5-C16
   backend internals and the sanity-check kept F-3 🟢 *because* the
   IME refresh alone is sufficient per spec).

---

# §6 Combined implementation hints (for the repair-implementer)

All edits are in **one coherent wave**. Files + exact sites:

### 6.1 `DictateInputMethodService.java`

- **`onStartInputView(EditorInfo, restarting)`** — after
  `super.onStartInputView(info, restarting);` (line 1651), add a
  guarded block:
  ```java
  if (pipelineBinder != null) {
      // F-3: pick up a permission toggled in System Settings while
      // the IME view was gone (Spec 3 §5.0). Refresh BEFORE the
      // ViewMode dispatch so the FSM sees the fresh hasPermission.
      pipelineBinder.getOverlayPermissionObserver().refresh();
      // F-1 T5/T6: unconditional (idempotent — reducer no-ops when
      // computeViewMode == current). `restarting` is intentionally
      // ignored: suppressing on restart would break T6 on rotation.
      pipelineBinder.dispatch(Action.ViewModeAction.OnImeViewShown.INSTANCE);
  }
  ```
  Place it near the top of the method (right after the
  `recordingStateController.onKeyboardShown()` call, before the
  theme/prompt work — order does not matter functionally since
  dispatch is async-on-main, but early keeps the activation wiring
  grouped). Add the `android.provider.Settings` /
  `net.devemperor.dictate.state.Action` imports.
- **`onFinishInputView(boolean finishingInput)`** — at the **very end
  of the method** (after the existing 3-state legacy block, line
  1088), add:
  ```java
  if (pipelineBinder != null) {
      // F-1 T3/T4: IME view hidden. The legacy 3-state block above
      // (recording-pause / pipeline-continue / idle-cleanup) is
      // unchanged; this only adds the FSM boundary dispatch. The
      // ImeViewBackend is NOT detached here (see §C-5) so HOVER
      // renders via the service-owned OverlayBackend.
      pipelineBinder.dispatch(Action.ViewModeAction.OnImeViewHidden.INSTANCE);
  }
  ```
  Note the three early `return;` paths in the legacy block (lines
  1064, 1070 — recording-active and pipeline-running). **The dispatch
  must fire on ALL paths**, including those returns. Either (a)
  refactor the early-returns to fall through to a single tail, or
  (b) add the dispatch before each `return;` and at the end. Option
  (a) is cleaner — restructure to compute the 3-state outcome then
  do the `OnImeViewHidden` dispatch unconditionally at the tail.
  This is critical: T3/T4 (KEYBOARD/WIDGET→HOVER) only matter when
  recording or pipeline is active — exactly the early-return paths.
  Missing the dispatch on the recording-active path would make HOVER
  unreachable in the *primary* HOVER use-case (recording continues
  after keyboard switch). **Verify the dispatch fires on the
  recording-active and pipeline-running branches.**
- **Info-bar wiring (F-2)** — in `onCreateInputView` (after the
  existing `infoBarController` construction, ~line 656): `findViewById`
  the new info-bar root + grant/dismiss buttons; set click listeners
  that call `startActivity(settingsIntent)` +
  `pipelineBinder.dispatch(RequestOverlayPermission.INSTANCE)` (Grant)
  and `pipelineBinder.dispatch(DismissOverlayOnboarding.INSTANCE)`
  (Later). In the state-collect render pass (the B1b
  `pipeline.state.collect` block — grep for where `state` is consumed
  for rendering), toggle info-bar `visibility` from
  `state.overlay.onboardingPending`. Mirror the existing
  `InfoBarController` show/hide idiom.

### 6.2 `app/src/main/res/layout/activity_dictate_keyboard_view.xml`

- Add the info-bar LinearLayout from Spec 3 §5.3 as a child of the
  root `ConstraintLayout` (id `dictate_keyboard_view`), constrained
  to `parent` top, `visibility="gone"`; re-point `main_buttons_cl`'s
  top constraint to the info-bar's bottom. IDs per Spec 3 §5.3:
  `overlay_permission_infobar`, `overlay_perm_grant_btn`,
  `overlay_perm_dismiss_btn`, message `overlay_permission_message`.
  (Strings `overlay_perm_explainer/later/grant` already exist in
  `values/strings.xml` per F-5 — locale files are F-5's job.)

### 6.3 `app/src/main/java/net/devemperor/dictate/state/Action.kt`

- Add `data object ShowOverlayOnboarding : OverlayAction()` to the
  `OverlayAction` sealed class (after `DismissOverlayOnboarding`,
  line ~332).

### 6.4 `app/src/main/java/net/devemperor/dictate/state/modules/OverlayModule.kt`

- Add reduce-arm:
  `Action.OverlayAction.ShowOverlayOnboarding -> TransitionResult(state.copy(onboardingPending = true), emptyList())`
  (place near `MarkOverlayOnboardingShown`, line ~105).
- Add to `onCrossModuleStateChange` the auto-cleanup arm (Spec 3
  §5.4): after the T1 KEYBOARD→WIDGET block (line ~229), add
  `if (prev.viewMode != ViewMode.WIDGET && next.viewMode == ViewMode.WIDGET && next.overlay.onboardingPending) cascade += Action.OverlayAction.MarkOverlayOnboardingShown`.
  (Note: the existing T1 block already keys on
  `prev==KEYBOARD && next==WIDGET`; the new arm uses
  `prev!=WIDGET` to also cover a hypothetical HOVER→WIDGET-with-pending
  — but onboardingPending can only be set in KEYBOARD; either
  predicate is correct. Use the Spec 3 §5.4 `prev != WIDGET` form for
  spec-faithfulness.)
- Update `Effect.OpenOverlayPermissionSettings` runEffect KDoc
  ([line 191-198]): replace the "Phase-1 no-op TODO" wording with
  "launch is owned by the IME-side info-bar Grant handler (Spec 3
  §5.2/§5.3); this Effect stays a structural placeholder for a
  future activity-launcher subsystem". Keep the body `Unit`.

### 6.5 `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt`

- Add `resolveWidgetToggleAction(state, services): Action` per §4.2
  (permission-aware: `hasPermission ? ToggleViewModeWidget :
  ShowOverlayOnboarding`). Mirror the `resolveOverlayCloseAction`
  KDoc-table style ([line 225-246]).

### 6.6 `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt`

- Replace all `WIDGET_TOGGLE` `actionResolver = { _, _ -> Action.ViewModeAction.ToggleViewModeWidget }`
  with `actionResolver = ::resolveWidgetToggleAction` at lines ~110,
  ~217, and verify the slots at ~263/~362/~410 (the
  `LogicalButtonId.WIDGET_TOGGLE` occurrences). Some of those may be
  layout-mode declarations without an actionResolver — only change
  the ones that resolve `ToggleViewModeWidget`.

### 6.7 Doc-trail

- `docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md` —
  append a Decision-History entry (§9 below): the IME-activation
  contract is now explicit (which lifecycle hook drives which
  transition). ADR-0005 §"Required mechanics" already *implies* it
  via the T-table; the entry records that B5 closed the
  production-wiring gap and pins the hook contract.

---

# §7 Edge-case handling

| Edge case | Behaviour after the fix | Why correct |
|-----------|-------------------------|-------------|
| **Rotation while KEYBOARD** | `onStartInputView(restarting=true)` → `OnImeViewShown` → `computeViewMode(true,false,*)=KEYBOARD` == current → reducer `null`. No churn. | Idempotent reducer; §3 view-recreation. |
| **Rotation while HOVER** (view was gone, pipeline active, user rotates the *other* app) | No IME callbacks fire (IME view is not shown). HOVER persists (service-owned OverlayBackend survives). When user returns to an IME field: `onStartInputView` → `OnImeViewShown` → recompute → KEYBOARD or WIDGET (T5/T6). | Pending-research §A.4: config-change without view-show fires nothing; HOVER is service-scoped. |
| **Grant-and-return** | User taps Grant → Settings → toggles ON → returns to the IME field. `onStartInputView` → `refresh()` (F-3) sets `hasPermission=true` → then `OnImeViewShown` (F-1). The overlay does **NOT** auto-appear — the user must tap WIDGET-toggle again (now resolves to `ToggleViewModeWidget`). | Spec 3 §5.5: "User kommt aus Settings zurück und klickt Widget-Toggle". No auto-WIDGET — `userPrefersWidget` is still false; T1 needs an explicit toggle. Matches ADR-0005 (WIDGET is user-triggered, never automatic). |
| **`suppressAutoOverlayUntilNextSession`** | User closes HOVER (`CloseOverlay`) → `OverlayModule` sets the suppress bit + cancels recording/pipeline. F-1's `OnImeViewHidden` on a *subsequent* keyboard-hide recomputes HOVER — **but** the suppress bit lives in `OverlayState`, not in `computeViewMode`. The bit gates the *OverlayBackend render*, not the FSM. After the cancel-cascade, `pipelineActive=false`, so `computeViewMode(false,*,false)=KEYBOARD` anyway — HOVER is not re-entered until a new recording session resets the bit (`Recording.Idle→Preparing` → `ResetSuppressBit`, ADR-0005 §"Required mechanics" #5). F-1 does not interact with the suppress bit. | The suppress bit and the FSM are orthogonal axes; F-1 only adds the FSM boundary dispatch. The cancel-cascade drives `pipelineActive` false, so the FSM naturally stays KEYBOARD. Verified against `OverlayModule.onCrossModuleStateChange` [line 239-251]. |
| **Multi-window / split-screen** | Each IME-view show/hide still fires `onStartInputView`/`onFinishInputView` per the Android contract (the IME serves the focused window). The FSM dispatch is per show/hide as normal. Split-screen does not introduce a new callback; `pipelineActive` and `userPrefersWidget` are process-global (single FGS). | No special handling needed — the IME-lifecycle contract is window-agnostic for the active editor; the FSM is process-scoped. |
| **Bind race** (`onStartInputView` before `onServiceConnected`) | `pipelineBinder == null` → all three dispatches guarded → no-op. The observer's cold-start `init()` ([DictatePipelineService.kt:601]) covers the initial `hasPermission`; the *next* `onStartInputView` after bind picks up F-1/F-3. | Established `pipelineBinder != null` guard pattern; matches the existing line-826 dispatch guard. |
| **`onFinishInputView` recording-active early-return** | The dispatch MUST still fire (see §6.1 critical note) — refactor the early-returns to a single tail. This is the *primary* HOVER trigger (recording continues after keyboard switch). | Without it, the headline HOVER use-case (Spec 3 §1.1 "Gboard for a password field, dictation keeps going") is unreachable. |

---

# §8 Test impact

### Existing tests — none break

- `ViewModeModuleTest.kt`, `OverlayModuleTest.kt` — pure-reducer
  tests; the new `ShowOverlayOnboarding` arm + cascade arm are
  *additions*, existing assertions unaffected. **Add** cases for the
  new arm + the WIDGET-auto-cleanup cascade.
- `DictatePipelineServiceOverlayTransitionTest.kt` — drives the FSM
  via `b.dispatch(...)` directly (not via IME callbacks), so F-1 does
  not change its assertions. It already covers T1/T2/T3/T5 *at the
  dispatch level*. **Add** an explicit T3/T4-via-`OnImeViewHidden`
  and T5/T6-via-`OnImeViewShown` case to assert the action→backend
  attach/detach (it currently dispatches `ToggleViewModeWidget`; add
  `OnImeViewHidden`/`OnImeViewShown` cases — the reducer arms exist,
  this just exercises them through the binder, mirroring the existing
  `binder()`/`idle()` harness).
- `OverlayPermissionObserverTest.kt` — pure JVM (lambda sink),
  unaffected; `refresh()` already tested.
- `DefaultOverlayPermissionGateTest.kt`,
  `OverlayPermissionOnboardingActivityTest.kt` — unaffected.

### New tests needed

1. **IME-lifecycle dispatch test (Robolectric)** —
   `DictateInputMethodServiceActivationTest.kt`. There is currently
   **no Robolectric test that boots `DictateInputMethodService`**
   (all IME logic is untested at the service level). Use
   `Robolectric.buildService(...)` is not applicable (IME is an
   `InputMethodService`, not a plain `Service`); use
   `Robolectric.buildService(DictateInputMethodService.class)` works
   for the `Service` superclass lifecycle, OR drive the public
   `onStartInputView`/`onFinishInputView` overrides directly on an
   instance with a stubbed binder. **Recommended pattern:** spawn the
   IME via Robolectric, bind a real `DictatePipelineService`
   `LocalBinder` (the existing `DictatePipelineServiceOverlayTransitionTest`
   shows the binder harness), call `onStartInputView` /
   `onFinishInputView`, assert the resulting `binder.state.value.viewMode`
   and `keyboardLayoutManager` overlay attach state. Cases:
   - `onFinishInputView` while `pipelineActive` (recording active) →
     `viewMode == HOVER`, OverlayBackend attached (T3).
   - `onFinishInputView` from WIDGET while pipeline active →
     `viewMode == HOVER` (T4).
   - `onStartInputView` from HOVER, `userPrefersWidget=false` →
     `KEYBOARD` (T5).
   - `onStartInputView` from HOVER, `userPrefersWidget=true` →
     `WIDGET` (T6).
   - `onStartInputView` with `restarting=true` and no mode change →
     no spurious cascade (idempotent; assert `viewMode` unchanged).
   - `onFinishInputView` on the **recording-active early-return
     path** still dispatches `OnImeViewHidden` (regression guard for
     the §6.1 critical refactor).
   - `pipelineBinder == null` (pre-bind) → no crash, no dispatch.
2. **F-3 refresh wiring (Robolectric)** — in the same test:
   `onStartInputView` calls `overlayPermissionObserver.refresh()`;
   assert via a Robolectric `Settings` shadow flip
   (`canDrawOverlays` false→true) that `state.overlay.hasPermission`
   becomes true after `onStartInputView` (the existing
   `OverlayPermissionObserverTest` shows the gate-fake pattern; here
   it is end-to-end through the binder).
3. **F-2 trigger path (reducer-level, pure JVM)** —
   `ViewModeModuleTest` / `OverlayModuleTest` /
   `ActionResolversTest`:
   - `resolveWidgetToggleAction`: `hasPermission=false` →
     `ShowOverlayOnboarding`; `true` → `ToggleViewModeWidget`.
   - `OverlayModule.reduce(ShowOverlayOnboarding)` →
     `onboardingPending=true`.
   - `onCrossModuleStateChange`: `prev.viewMode!=WIDGET &&
     next.viewMode==WIDGET && onboardingPending=true` → emits
     `MarkOverlayOnboardingShown`.
4. **Info-bar render binding (Robolectric, optional but recommended)** —
   assert the IME's info-bar view `visibility` toggles with
   `state.overlay.onboardingPending` and that the Grant button
   dispatches `RequestOverlayPermission` + launches the Settings
   intent (assert the shadow `startActivity` captured the
   `ACTION_MANAGE_OVERLAY_PERMISSION` intent with
   `FLAG_ACTIVITY_NEW_TASK` — mirrors
   `OverlayPermissionOnboardingActivityTest`'s intent assertion).

> Test-isolation note: the IME-boot Robolectric test will, like
> `DictatePipelineServiceOverlayTransitionTest`, share the
> `DictateDatabase` singleton + default SharedPreferences across the
> JVM fork — F-9 (the `LegacyAudioFileMigrationTest` flakiness)
> warns this amplifies cross-test pollution. The new IME test must
> `JobExecutor.resetForTest()` + DB/pref reset in `@After` (copy the
> `DictatePipelineServiceOverlayTransitionTest` `tearDown` pattern)
> so it does not become a 9th boot-amplifier.

---

# §9 Decision-History entry placeholder (ADR-0005 amendment)

To be appended to
`docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md`
§"Decision History" by the repair-implementer (append-only, since
ADR-0005 is `Accepted`):

> ### 2026-05-15 — IME-activation contract pinned (B5 repair-wave, F-1/F-2/F-3)
>
> **Trigger:** B5-VAL-SANITY validated-findings F-1 (IME never
> dispatches `OnImeViewShown/Hidden`), F-2 (permission-onboarding
> unreachable), F-3 (`observer.refresh()` not wired). The
> Triangle-FSM, OverlayModule, ViewModeModule arms, and the
> attach/detach collapse were implemented + unit-green but inert in
> production because `DictateInputMethodService.java` was never wired
> to drive them.
>
> **Before:** ADR-0005 §"Required mechanics" + the Decision T-table
> *named* `onFinishInputView`/`onStartInputView` as the T3/T4/T5/T6
> triggers, but no production code produced `OnImeViewShown/Hidden`;
> the WIDGET-toggle `actionResolver` was permission-blind; the
> onboarding info-bar + Settings-launch path was undelivered (C17→C18
> forward-drop); `observer.refresh()` had no IME call site.
>
> **After:** The IME-activation contract is now production-binding and
> explicit: `onStartInputView` dispatches `OnImeViewShown` + calls
> `overlayPermissionObserver.refresh()`; `onFinishInputView`
> dispatches `OnImeViewHidden` (on **all** paths including the
> recording-active/pipeline-running early-returns). The WIDGET-toggle
> `actionResolver` is permission-aware (`hasPermission ?
> ToggleViewModeWidget : ShowOverlayOnboarding`); a new
> `Action.OverlayAction.ShowOverlayOnboarding` arm sets
> `onboardingPending`; the in-IME info-bar (owned by the IME service,
> not `ImeViewBackend` — a documented deviation from the Spec 3 §5.3
> sketch, justified by the B4 `ImeViewBackend` button-map-only
> contract) launches `ACTION_MANAGE_OVERLAY_PERMISSION` with
> `FLAG_ACTIVITY_NEW_TASK`. `Effect.OpenOverlayPermissionSettings`
> remains a structural placeholder (launch owned by the IME-side
> handler per Spec 3 §5.2).
>
> **Reasoning:** The hook choice was not a free decision — ADR-0005's
> own T-table, Spec 1 §11 (lines 2672-2676), and the architecture-doc
> `triangle-fsm.md` §5 independently dictate
> `onStartInputView`/`onFinishInputView`. The trigger-arm
> (`ShowOverlayOnboarding`) resolves Spec 3 §5.4's explicitly-open
> "Auslöser TBD" toward a dedicated single-purpose action (SRP) over
> overloading `RequestOverlayPermission`. This entry pins the
> contract so a future reader knows the FSM's *production* trigger
> surface, not just the reducer arms.

---

# References

- Block-report: `../reports/validated-findings-B5.md` §F-1 / §F-2 / §F-3
- Plan: `../dictate-keyboard-layout-refactor.reviewed.md`
- ADR-0005: `../../../decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md`
- Architecture-doc: `../../../architecture/state-architecture/triangle-fsm.md` §5
- Spec 1 §5 / §8.x / §11.3: `1-pipeline-service/1-pipeline-service.reviewed.md` (lines 2640-2711, 4067-4099)
- Spec 3 §5.0–§5.7 / §7.3: `3-floating-overlay/3-floating-overlay.reviewed.md`
- Prior depth-research: `_pending-ime-lifecycle-view-recreation/_pending-ime-lifecycle-view-recreation.md` §A–§B
- Production: `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` (`onStartInputView` ~1650, `onFinishInputView` ~1053, binder dispatch line 826), `.../core/DictatePipelineService.kt` (LocalBinder 970-1096, `syncOverlayBackendAttachment` 651), `.../state/modules/ViewModeModule.kt`, `.../state/modules/OverlayModule.kt`, `.../state/layout/LayoutCatalog.kt`, `.../state/layout/ActionResolvers.kt`, `.../state/render/overlay/OverlayPermissionObserver.kt`, `.../onboarding/OverlayPermissionOnboardingActivity.kt`
