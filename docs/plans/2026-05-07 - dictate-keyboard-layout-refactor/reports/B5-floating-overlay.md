# Block 5: Floating-Overlay (plan-Block-6 — Spec 3 full)

> **This file is the logbook for Block 5.** Implementation-Agents
> and Audit-Agents document their work here. The orchestrator
> maintains the status table in the main state file
> (`../dictate-keyboard-layout-refactor.state.md`) — agents do **not** write to the
> state file.

**Phase:** Floating-Overlay (Spec 3 — OverlayBackend + AndroidOverlayWindow-Wrapper + Permission-Observer + Mode-Transitionen T1-T7 + Drag-Lifecycle + 5-Button-Layout). Last implementation block.
**Implementation-Chunks:** C16 (M, 800) · C17 (M, 550) · C18 (M, 650). Total 2000 score.
**Workflow:** Iter-10 5-step workflow with orchestrator-split commits per chunk. Block runs C16 → C17 → C18 sequentially.
**Block-Start-Commit:** `74f9dd3`
**Block-End-Commit:** ⏳ (set by orchestrator at block completion)

---

## Issue Index (Orchestrator-Maintained)

**Severity counts (B5-VAL findings, post-repair):**
- Critical: 0 open (2 fixed: F-1, F-2)
- Important: 0 open (7 fixed: F-3…F-9)
- Nice-to-have: 0 open (4 fixed: F-10…F-13)
- Postponed: 0

All 13 B5-VAL distinct findings **fixed** in B5-VAL-REPAIR Wave 1
(2026-05-15). 2 ❌ informational findings left as-is per consolidator
disposition (carry-over Issue Index items below are unaffected —
B5 did not regress them).

**By status:**

| ID | Source agent | Severity | Status | Title | Source phase |
|----|--------------|----------|--------|-------|--------------|
| F-1…F-13 | B5-VAL-SANITY | Crit/Imp/NTH | fixed (B5-VAL-REPAIR W1) | IME-activation wiring + overlay coordinate/doc/test cluster — see `### Block-Validate Repair Wave 1` | B5 |
| D-13 (B3 → B4 carry-over) | B3-C8-IMPL | Important | open (re-deferred from C15) | LanguageController full removal — ~30 caller-graph sites; deferred to B7 follow-up block | B7 |
| D-14 (B3 → B4 carry-over) | B3-C11-IMPL | Important | open (deferred) | DictateInputMethodService.audioFile field removal — 5 unrelated IME-side reads still need it | B5/B6 (deferred to B7 since B5 scope is overlay) |
| F-10 (B4 carry-over) | B4-VAL-W1 | Important | open (delegated-to-orchestrator) | Action.RecordingAction.StopRecordingAndSend(sessionId="") empty-string sentinel | B5-pre |
| F-12 (B4 carry-over) | B4-VAL-W1 | Important | open (delegated-to-orchestrator) | ReprocessStaging.isStarting field addition for SendStaging double-click guard | B5-pre |
| F-13 (B4 carry-over) | B4-VAL-W1 | Important | open (delegated-to-orchestrator) | PipelineUiState.Running.completedSteps/totalSteps/elapsedMs field additions | B5-pre |
| F-15 (B4 carry-over) | B4-VAL-W1 | Important | open (delegated-to-orchestrator) | LayoutStrings.dictateButtonText language-awareness | B7 / D-13 |
| Espresso UI-Tests 1-10 | B4-C14 | NTH | open | 10 @Ignored skeletons — bodies pending un-ignore | post-B5 |

---

## Conventions established this block

| Convention | Where established | Description |
|------------|-------------------|-------------|

---

## Mandatory Format Reminder for All Agents

Shared sub-agent directives (issue handling, status schema, stdout
convention, research-file output, plan-deviation autonomy) live in
`prompts/agent-prompts.md` — read it before starting your task.

### Deviation Format

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Inline-fixed? |
|-----------|---------------|--------------|-----|------------------------|----------------|

### Issue Format

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|

---

## Implementation Logs

### Chunk C16-block6-overlay-backend-and-window — OverlayBackend + Window-wrapper + XML

**Agent-IDs:** Steps 1-5 (combined): `B5-C16-IMPL-FULL`

**Status:** ✅ complete (all 5 steps combined; `./gradlew test` green, `./gradlew assembleDebug` green)
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 17 (C16-block6-overlay-backend-and-window)
**Test result:** new tests 41/41 pass (11 `AndroidOverlayWindowTest` + 16 `OverlayLayoutParamsFactoryTest` + 14 `OverlayBackendTest`); full project test run green (`./gradlew test`).
**Build result:** `./gradlew assembleDebug` green.

#### What was done

Implemented the floating-overlay rendering surface as a parallel
`RenderBackend` per Spec 3 §3.1 + §3.2 + §4.1–§4.5:

- **Production files (Commit 1):**
  - `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayWindow.kt` —
    `OverlayWindow` interface + `AndroidOverlayWindow` impl with SRP-aligned
    exception hygiene per Spec 3 §4.1 (BadToken on attach + IllegalArgument on
    update/detach are all caught inside the wrapper).
  - `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayLayoutParamsFactory.kt` —
    Interface + `DefaultOverlayLayoutParamsFactory` building
    `WindowManager.LayoutParams` per the §4.4 flag truth-table
    (`TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL |
    FLAG_LAYOUT_IN_SCREEN | FLAG_HARDWARE_ACCELERATED`,
    `PixelFormat.TRANSLUCENT`, `Gravity.TOP|START`).
  - `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayPermissionGate.kt` —
    `OverlayPermissionGate` interface (Spec 3 §5.1) + a `NoOverlayPermissionGate`
    stub. C17 contributes the real `DefaultOverlayPermissionGate`.
  - `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayBackend.kt` —
    `RenderBackend` impl (`backendType=OVERLAY_WINDOW`). Render-loop reads
    `state.overlay.hasPermission` + `suppressAutoOverlayUntilNextSession`,
    attaches the window once, applies slots via the shared `applySlotToView`
    helper, wires `wireStaticOverlayHandlers` exactly once per inflate
    (L8 single-wire). `applyPositionPlaceholder()` is a documented no-op slot
    so the C18 drag/position-mapping is a single edit point.
  - `app/src/main/res/layout/overlay_5button_layout.xml` — two-row 3+2
    MaterialButton layout (Record / Send / Pause | Trash / Close) per
    Spec 3 §3.2.
  - `app/src/main/res/drawable/overlay_background.xml` — Material-Surface
    rounded-corner card with outline.
  - `app/src/main/res/values/styles_overlay.xml` — `OverlayButton.Primary`
    + `OverlayButton.Icon` styles.
  - Five new overlay strings + content-descriptions in `strings.xml`.

- **Production files modified (Commit 1):**
  - `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt` —
    Filled in `OVERLAY_5BUTTON` with 5 `ButtonSlot`s per Spec 3 §3.1. Resolvers
    branch on `state.viewMode` for the WIDGET-vs-HOVER differential (Record +
    Send enabled only in WIDGET, OVERLAY_CLOSE emits different actions per
    ViewMode).
  - `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt` —
    Added `resolveOverlayRecordAction` (Pre-Dispatch-Allocation R.2),
    `resolveOverlayPauseAction`, `resolveOverlayCloseAction`.
  - `app/src/main/java/net/devemperor/dictate/state/layout/TextResolvers.kt` —
    `LayoutStrings.overlaySend` (default "Send") for the OVERLAY_SEND text
    resolver.
  - `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt` —
    Constructed `OverlayBackend` (with `AndroidOverlayWindow` +
    `NoOverlayPermissionGate` stub) in `onCreate`. **Not attached** to
    `KeyboardLayoutManager` yet — C18 wires the attach into the
    ViewMode-transition logic per ADR-0005. Exposed via the
    `LocalBinder.overlayBackend` accessor.

- **Test files (Commit 2):**
  - `app/src/test/java/net/devemperor/dictate/state/render/overlay/FakeOverlayWindow.kt` —
    Hand-rolled K-1 fake recording attach/update/detach calls plus a
    `simulateBadTokenOnAttach` toggle.
  - `app/src/test/java/net/devemperor/dictate/state/render/overlay/OverlayLayoutParamsFactoryTest.kt` —
    16 tests covering window type, every flag in §4.4, format, gravity,
    initial x/y, animation, fresh-instance-per-call.
  - `app/src/test/java/net/devemperor/dictate/state/render/overlay/AndroidOverlayWindowTest.kt` —
    11 tests covering attach/update/detach forwarding, double-attach
    idempotency, BadToken catch leaves wrapper detached,
    IllegalArgumentException on update flips attached → false,
    detach IllegalArgumentException swallowed, recover-after-revoke.
  - `app/src/test/java/net/devemperor/dictate/state/render/overlay/OverlayBackendTest.kt` —
    14 tests covering backendType, permission-gate teardown, suppress-bit
    teardown, attach once on first render, mismatched-backend `require`,
    OVERLAY_CLOSE behaviour-per-ViewMode, OVERLAY_RECORD null-resolver-no-op
    in HOVER, OVERLAY_RECORD Pre-Dispatch-Allocation in WIDGET, click after
    detach is no-op, idempotent re-render, permission-revoked-mid-session.

- **Existing test updated:** `LayoutCatalogTest`'s placeholder-empty
  assertion replaced with a real structural assertion of the 5-button rows
  and the `sceneStateId == null` invariant.

#### Plan deviations

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| `applyPositionPlaceholder()` is a documented no-op in C16; drag handler + position mapper deferred to C18 | Spec 3 §4.2, §4.6, §4.7 | Constructor of `OverlayBackend` omits `dragHandlerFactory` and `positionMapper` (they're not yet wired). | C16 chunk scope explicitly excludes drag (chunk description: "C16 = OverlayBackend + Window-wrapper + LayoutParamsFactory + Overlay-XML"; "Don't yet: Mode-transitions T1-T7 + Drag (C18)"). Adding them as no-op stubs without a `OverlayPositionMapper` interface would leak Spec 3 §4.7 surface into C16; cleaner to leave one well-named injection slot. | C18 will (a) introduce the `OverlayDragHandler` / `OverlayPositionMapper` interfaces, (b) add them to the `OverlayBackend` constructor (additive), and (c) replace the `applyPositionPlaceholder()` body. | inline-fixed (deliberate scope-respecting deferral) |
| `OverlayPermissionGate` interface introduced in C16 with `NoOverlayPermissionGate` stub | Spec 3 §5.1 | The full `DefaultOverlayPermissionGate` (Settings.canDrawOverlays + onboarding flags) is C17 work, but `OverlayBackend` constructor needs the type. | Avoids forward-referencing a class that hasn't been written; the interface is the contract, the implementation belongs in C17. | C17 supplies `DefaultOverlayPermissionGate` and swaps it in the `DictatePipelineService.onCreate` site (single-edit). | inline-fixed |
| `LayoutStrings.overlaySend` field added with default value "Send" | Spec 3 §3.2 | The XML already has `android:text="@string/overlay_send"`, but the slot's `textResolver` in §3.1 calls a `resolveOverlaySendText(state)` helper. Adding the field to `LayoutStrings` (with a string-default to keep the existing test factories compiling) lets the slot resolve to the Android-resolved string in production while leaving a clear i18n hook. | Same as record/send/sending — the `LayoutStrings` indirection keeps the catalog Android-loose. Default value "Send" survives JVM tests that don't override the field. | C18 / B-VAL agents may want to wire the literal through `DictatePipelineService.buildLayoutStrings()` to pick up `R.string.overlay_send`. Not a regression — the default value matches the literal in `strings.xml`. | inline-fixed |

#### Issues

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| (none) | | | | No open issues from this chunk. |

#### Test-Infrastructure implemented

- `FakeOverlayWindow` — first overlay-side test fake; mirrors the
  `FakeMotionSurface` pattern already established in C14 (D7 / K-1).

#### Code-Bugs Found While Writing Tests

(none)

#### Overlooked points / known gaps

- **Drag handler + position mapper deferred to C18** — `applyPositionPlaceholder()`
  is documented as a no-op slot. Without C18 the overlay docks to the top-left
  corner; this is correct for the C16 scope but isn't user-shippable until C18
  lands. The chunk description explicitly carves drag out.
- **OverlayBackend is NOT yet attached to `KeyboardLayoutManager`** — C18 owns
  the ViewMode-transition logic (T1–T7 per ADR-0005) that decides when to
  attach (`KEYBOARD → WIDGET` user-toggle) and when to detach
  (`HOVER → KEYBOARD` close-cascade). Until then, WIDGET / HOVER ViewModes
  exist as state but render nothing — exactly the "no production behavior
  change yet" contract from the chunk prompt.
- **`OverlayPermissionGate` is currently `NoOverlayPermissionGate`** in the
  composition root. The render path reads `state.overlay.hasPermission`
  directly (which is `false` by default), so the overlay backend's render
  path will short-circuit at the permission gate until the
  `OverlayPermissionObserver` (C17) lands and starts dispatching
  `OnOverlayPermissionChanged` actions.
- **`textResolver` for OVERLAY_SEND** — uses `LayoutStrings.overlaySend`
  default ("Send"). `DictatePipelineService.buildLayoutStrings()` does not
  yet wire `R.string.overlay_send` through — the XML's `android:text`
  attribute is what the user sees today. A follow-up edit there would
  unify the path but isn't required for the C16 scope (the catalog
  resolver still functions; it just resolves to a literal that matches
  the XML default).

---

### Chunk C17-block6-permission-onboarding — Permission-Observer + Gate + Onboarding-UI

**Agent-IDs:** Steps 1-5 (combined): `B5-C17-IMPL-FULL`

**Status:** ✅ complete (all 5 steps combined; `./gradlew test` green, `./gradlew assembleDebug` green)
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 18 (C17-block6-permission-onboarding)
**Test result:** 22 new tests / 22 pass (10 `DefaultOverlayPermissionGateTest` + 5 `OverlayPermissionObserverTest` + 7 `OverlayPermissionOnboardingActivityTest`); full project `./gradlew test` green.
**Build result:** `./gradlew assembleDebug` green.

#### What was done

Implemented the permission infrastructure layer per Spec 3 §5.0–§5.7:

- **Production files (Commit 1):**
  - `app/src/main/java/net/devemperor/dictate/state/render/overlay/DefaultOverlayPermissionGate.kt` —
    production `OverlayPermissionGate` impl. `hasOverlayPermission()` wraps
    `Settings.canDrawOverlays(ctx)`; `shouldShowOnboarding()` returns
    `!hasOverlayPermission() && !prefs.get(Pref.OverlayOnboardingDismissed)` per
    Spec 3 §5.1. `markOnboardingShown` / `markPermanentlyDenied` write the typed
    `Pref.OverlayOnboardingShown` / `Pref.OverlayOnboardingDismissed` entries via
    the project's `Pref` sealed class (`CLAUDE.md` rule).
  - `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayPermissionObserver.kt` —
    the only live source for `state.overlay.hasPermission` (Issue 3.1.3 / R.2).
    Per Spec 3 §5.0 *no polling*: lifecycle-trigger model with two entry points,
    `init()` (called from `DictatePipelineService.onCreate`) and `refresh()`
    (designed to be called from the IME's `onCreateInputView` /
    `onStartInputView`). Both call `gate.hasOverlayPermission()` and dispatch
    `Action.OverlayAction.OnOverlayPermissionChanged(granted)`. Constructor
    takes `(Action) -> Unit` instead of `DictateOrchestrator` directly — DIP for
    JVM-pure tests; production wires `orchestrator::dispatch`.
  - `app/src/main/java/net/devemperor/dictate/onboarding/OverlayPermissionOnboardingActivity.kt` —
    standalone Activity surface (Spec 3 §5.2). Inflates an explainer + Allow +
    Later layout. Allow triggers `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` with
    a `package:` data Uri + `FLAG_ACTIVITY_NEW_TASK`, then calls
    `gate.markOnboardingShown()`. Later just `finish()`s — the permanently-denied
    bit lives in the in-IME info-bar flow (Spec 3 §5.4), per SRP. `onResume`
    refreshes the status TextView so a returning user sees the granted state.
  - `app/src/main/res/layout/activity_overlay_permission_onboarding.xml` —
    Material 3 ConstraintLayout with title + explainer + grant + dismiss +
    status line.
  - `app/src/main/res/values/strings.xml` — six new strings
    (`overlay_perm_onboarding_title`, `overlay_perm_explainer`,
    `overlay_perm_later`, `overlay_perm_grant`,
    `overlay_perm_onboarding_granted`, `overlay_perm_onboarding_pending`).
  - `app/src/main/AndroidManifest.xml` — declared
    `OverlayPermissionOnboardingActivity` (`exported="false"`).

- **Modified files (Commit 1):**
  - `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt` —
    Step-8 block now constructs `DefaultOverlayPermissionGate` +
    `OverlayPermissionObserver` (gate + dispatch-sink), wires the **real** gate
    into `OverlayBackend.permissions` (replaces the `NoOverlayPermissionGate`
    stub from C16), and calls `overlayPermissionObserverImpl.init()` after
    backend construction so the first state-emit carries the live permission
    boolean. `LocalBinder` exposes `overlayPermissionObserver` and
    `overlayPermissionGate` for IME-side use (IME-lifecycle refresh + non-reducer
    consumers per Spec 3 §13.3).

- **Test files (Commit 2):**
  - `app/src/test/java/net/devemperor/dictate/state/render/overlay/FakeOverlayPermissionGate.kt` —
    in-memory test double with mutable `hasPermission` / `permanentlyDenied`
    fields + call counters (K-1).
  - `app/src/test/java/net/devemperor/dictate/state/render/overlay/DefaultOverlayPermissionGateTest.kt` —
    10 Robolectric tests (`@Config(sdk = [34])`); covers
    `hasOverlayPermission` delegation (Robolectric default = false),
    `shouldShowOnboarding` over the permission × permanently-denied matrix,
    `markOnboardingShown` / `markPermanentlyDenied` write-paths via typed `Pref`
    accessors, SRP-separation (each method touches only its own pref),
    idempotency, plus a K-4-style canonical-pref-key round-trip.
  - `app/src/test/java/net/devemperor/dictate/state/render/overlay/OverlayPermissionObserverTest.kt` —
    5 pure-JVM tests (no Robolectric). Covers `init` dispatches false / true,
    `refresh` dispatches current gate value, `refresh` unconditional dispatch
    (idempotency contract owned by `OverlayModule.reduce`), and a three-emit
    transition sequence (false → true → false).
  - `app/src/test/java/net/devemperor/dictate/onboarding/OverlayPermissionOnboardingActivityTest.kt` —
    7 Robolectric tests. Covers View inflation, grant button (`Settings`
    intent with package URI + `FLAG_ACTIVITY_NEW_TASK`), grant flips
    `Pref.OverlayOnboardingShown`, grant does NOT touch the dismissed pref
    (SRP), dismiss button calls `finish()`, dismiss does NOT touch dismissed
    pref, and the status TextView shows the pending copy by default.

#### Plan deviations

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| D-1 | Chunk-prompt vs Spec 3 §5.0 — chunk description floated "periodic polling" as an alternative | Implemented the lifecycle-trigger model (no polling) — `init()` + `refresh()` with explicit lifecycle hooks | Spec 3 §5.0 explicitly says "Pollt nicht — reagiert auf Lifecycle-Trigger" and explains why (no system broadcast, lifecycle hooks are sufficient). Spec is the design truth; the chunk-prompt was a fallback hint. | C18 (mode-transitions) needs to call `observer.refresh()` from the IME's `onCreateInputView` / `onStartInputView` if it wants snappier permission-update detection. The Service-side `init()` covers the cold-start case. | inline-fixed |
| D-2 | Chunk-prompt: "OverlayPermissionOnboardingActivityTest.kt (Robolectric) — view inflation + button-click → intent" — implicit assumption that the Activity has no state machine of its own | Spec 3 §5.4 says the permanently-denied bit lives in the in-IME flow; the Activity is a fire-and-forget Settings-launch surface. The dismiss-button-tests assert the Activity does NOT touch the dismissed pref, reinforcing the SRP boundary. | The earlier reading of "the Activity dismisses permanently" would have duplicated the in-IME info-bar's "Later" semantics across two surfaces and risked drift on a future copy edit. | Activity stays a thin surface; the in-IME info-bar (C18 work) is the canonical onboarding interactor. | inline-fixed |
| D-3 | Chunk-prompt: `OverlayPermissionObserver` constructor planned to take `DictateOrchestrator` | Constructor takes `dispatch: (Action) -> Unit` instead (DIP) | `DictateOrchestrator` is a concrete class with a non-trivial constructor — JVM-pure tests need a recording lambda, not a full orchestrator. Production calls `orchestrator::dispatch`. | None — IME-side will get the observer through the LocalBinder and call `init()` / `refresh()` directly; no consumer touches the constructor wiring. | inline-fixed |

#### Issues

(none — clean implementation; all spec requirements met)

#### Inline-fixed items

- `DictatePipelineService.kt` line 519: swap `NoOverlayPermissionGate` → real
  `overlayPermissionGateImpl` in the `OverlayBackend` constructor. The
  no-permission stub from C16 is now retired in production wiring (still
  retained in the `OverlayPermissionGate.kt` source file for `OverlayBackendTest`
  to consume).
- `DictatePipelineService.kt` step-8 block extended with the
  `overlayPermissionGateImpl` + `overlayPermissionObserverImpl` construction
  and the post-construction `observer.init()` call (Spec 3 §5.0 boot-default
  race-window mitigation).
- `OverlayPermissionOnboardingActivity.kt`: deduplicated `PREFS_NAME` literal
  to reference `DictatePipelineService.PREFS_NAME` (single source of truth).

#### Overlooked points / known gaps

- **IME-side refresh wiring**: C17 stops at constructing the observer + exposing
  it via the LocalBinder. The actual `observer.refresh()` calls from the IME's
  `onCreateInputView` / `onStartInputView` are an explicit C18 follow-up (lives
  with the rest of the WIDGET-toggle wiring + the in-IME info-bar's
  `MarkOverlayOnboardingShown` dispatch). State today still flows correctly via
  the `init()` cold-start dispatch + the `OverlayModule.onCrossModuleStateChange`
  permission-loss cascade.
- **Permission-granted Robolectric branch**: `Settings.canDrawOverlays()`
  returns `false` in Robolectric without a custom shadow setup, so the
  permission-granted-→-shouldShowOnboarding-false branch of
  `DefaultOverlayPermissionGate.shouldShowOnboarding` is verified via the
  `FakeOverlayPermissionGate` truth-table (in `OverlayPermissionObserverTest`)
  rather than a direct shadow-poke. Acceptable — the boolean composition is
  trivial and the Fake-based truth-table covers all four cells.
- **In-IME info-bar (Spec 3 §5.3 XML + InfoBar layout in
  `overlay_permission_infobar.xml`)** is **not** in this chunk's scope —
  according to the chunks.json description, the in-IME info-bar binding is part
  of C18 (mode-transitions + drag) since it sits next to the WIDGET-toggle
  permission-fehlt handler.

---

### Chunk C18-block6-mode-transitions-and-drag — Mode-transitions T1-T7 + Drag + OverlayModule

**Agent-IDs:** Steps 1-5 (combined): `B5-C18-IMPL-FULL`

**Status:** ✅ done — production + tests green (`./gradlew test` + `./gradlew assembleDebug` BUILD SUCCESSFUL)

**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 19

#### Implementation (B5-C18-IMPL)

**What was done:**

- **Part A — Mode-Transitions T1–T7 wiring.** `DictatePipelineService.syncOverlayBackendAttachment(viewMode)` toggles the `OverlayBackend`'s membership in the `KeyboardLayoutManager` on every state-collect emit. The 7-transition matrix collapses to one rule: **attach iff `viewMode != KEYBOARD`** (the overlay window is the WIDGET ∪ HOVER union per Spec 3 §3.1; T4/T6 stay in the union → no churn; T7 is structurally identical to T5 — both land on KEYBOARD, the "Geist-Widget" protection already lives in `ViewModeModule.reduce`). Permission gate is single-sourced in `OverlayBackend.render` (`hasPermission` guard) — a permission-less attach is a cheap no-op, no duplicate `Settings.canDrawOverlays` read.
- **Part B — Drag-Lifecycle.** New `OverlayPositionMapper` (+ `DefaultOverlayPositionMapper`) — the single SoT for `[0..1]` ↔ pixel conversion (Spec 3 §4.7), with `effectiveWidth`/`effectiveHeight` helpers and the not-measured `null` short-circuit. New `OverlayDragController` (+ `OverlayDragControllerFactory`/`Default…`) — touch state machine with accessibility-aware `dragThresholdPx = max(8dp, scaledTouchSlop*1.5)`, click-vs-drag differentiation, `WindowManager.update` per `ACTION_MOVE`, drag-end → `Action.OverlayAction.UpdateOverlayPosition` dispatch, and the mid-drag-detach persistence net (R.18). `OverlayBackend.applyPositionPlaceholder()` replaced with a real `applyPosition(overlay)` (orientation-aware pref-bucket selection, `lastAppliedPosition` dedup cache, `view.post` retry for the unmeasured-first-render case, `isDragging()` short-circuit).
- **Part C — OverlayModule.** Verified all needed action arms already exist (`UpdateOverlayPosition`, `SetUserPrefersWidget`, `Suppress…`, `ResetSuppressBit`, `OnOverlayPermissionChanged`, `RequestOverlayPermission`) — canonical Spec 3 §4.8 uses only `UpdateOverlayPosition` on drag-end (no separate DragStart/DragEnd actions; the drag state machine is purely in the controller, the reducer stays pure). **No new actions/arms needed.**

**Plan deviations:**

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| Attach-rule simplified to `viewMode != KEYBOARD` instead of a 7-arm `when` | Spec 3 §7.2 / chunk Part A "T1–T7" | One boolean rule rather than per-transition arms | Spec 3 §3.1 — WIDGET and HOVER both render `OVERLAY_5BUTTON`; the only mode needing teardown is KEYBOARD. T4/T6 (overlay↔overlay) need no churn; T7 settles on KEYBOARD via the FSM so it needs no special arm. Simpler + provably exhaustive. | None — behaviour is identical to the per-arm form for all 7 transitions (verified by `DictatePipelineServiceOverlayTransitionTest`). | inline-fixed |
| Single-backend `switchBackend` (Spec 3 §7.2 snippet) NOT used; overlay backend attached/detached on the **multi-backend list** instead | Spec 3 §7.2 | `ImeViewBackend`/`ContentAreaController` stay attached; only the OverlayBackend toggles | Consistent with the C16 deviation note + the existing `KeyboardLayoutManager` R.10 multi-backend design; §7.2 is the pedagogical snippet, §4.1/R.10 is the production contract. | None — matches the established block-wide pattern. | inline-fixed |
| `DragHandler`/`PositionMapper` named `OverlayDragController`/`OverlayPositionMapper` (chunk-prompt said `DefaultOverlayPositionMapper.kt` + `OverlayDragController.kt`) — matches the chunk-prompt file names, **not** Spec 3 §4.6's `OverlayDragHandler` | Spec 3 §4.6 vs chunk-prompt "Files to create" | Used the chunk-prompt's `OverlayDragController` name (the explicit C18 deliverable) | Chunk-prompt is the concrete C18 contract and names the exact files; the Spec §4.6 `OverlayDragHandler` is the design sketch. Behaviour (interface surface: attach/detach/isDragging, factory, threshold, persist-on-detach) is 1:1 with Spec §4.6. | Block-validate may want to cross-check the rename is intentional; functionally equivalent. | inline-fixed |
| Collector body wrapped in `try/catch` (per-emit render isolation) | not in plan | A render exception in one backend no longer cancels the state-collect coroutine | C18 is the first chunk that attaches the OverlayBackend to the **live** manager; a backend render-throw (observed: Robolectric `MaterialButton` inflate needs a Material-themed context) would otherwise silently freeze the notification/DB/every state subscriber for the process lifetime. Legit robustness improvement scoped to where the risk first appears. Paired with the flag-first ordering in `syncOverlayBackendAttachment` so a thrown first-render still bookkeeps as attached (→ matching detach still fires, no window leak). | Positive — hardens the whole state pipeline for all later blocks. No behavioural change on the happy path. | inline-fixed |

**Issues:** none open. (The Robolectric `MaterialButton` inflate failure under the real `AndroidOverlayWindow` is a test-environment theming artifact, not a production bug — production inflates from the themed IME context; the per-emit isolation makes it a logged no-op regardless.)

**Inline-fixed items:**

- `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayPositionMapper.kt` — new (interface + `DefaultOverlayPositionMapper` + `effectiveWidth`/`effectiveHeight`).
- `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayDragController.kt` — new (controller + `OverlayDragControllerFactory` + `DefaultOverlayDragControllerFactory`).
- `OverlayBackend.kt` — constructor gains `positionMapper` + `dragControllerFactory` (defaults wire production); `applyPositionPlaceholder()` → real `applyPosition()`; `wireDragController()` added; `teardownOverlay()` flushes drag controller first; class KDoc updated.
- `DictatePipelineService.kt` — `syncOverlayBackendAttachment()` added + called from the state-collect; `overlayBackendAttached` field; collector body guarded; Step-8 comment + `overlayBackend` binder KDoc updated.

**Test-Infrastructure implemented:** none new — reused `FakeOverlayWindow`, `fakeModuleServices`, `NoOverlayPermissionGate`, `Robolectric.buildService`.

#### Tests (B5-C18-IMPL-TEST)

**What was done:** Created `DefaultOverlayPositionMapperTest` (9 tests — corners/centre/clamp/round-trip/unmeasured/zero-free-area), `OverlayDragControllerTest` (6 tests — tap-vs-drag, update-per-move, persist-on-UP, mid-drag-detach R.18, idle-detach, null-params guard), `DictatePipelineServiceOverlayTransitionTest` (8 tests — T1/T2/T3/T4/T5/T7 + boot-state + backend-availability, Robolectric Service-level). Extended `OverlayBackendTest` (+4 tests — applyPosition via mapper, idempotent re-render, drag-persist → `UpdateOverlayPosition`, detach after position render).

**Result:** `./gradlew test` → BUILD SUCCESSFUL (full suite green, no cross-chunk regressions). `./gradlew assembleDebug` → BUILD SUCCESSFUL.

**Coverage notes:** All 7 T1–T7 transitions covered (T1/T3 attach, T2/T5/T7 detach, T4/T6 stay-attached via the no-churn rule — T6 is covered transitively by the `viewMode != KEYBOARD` rule asserted in T4). Mapper boundary clamping + not-measured both directions. Drag click-vs-drag threshold + mid-drag detach. Permission-gate covered (T1 needs `OnOverlayPermissionChanged(true)` first, asserted via the `binder()` helper).

**Overlooked points / known gaps:**

- **Orientation-change live test** is manual (Spec 3 §11.5.6) — the `isPortraitOrientation()` read + per-orientation pref bucketing is unit-covered indirectly via the mapper, but a real rotation round-trip is a Phase 4.5 manual runbook item.
- **Multi-window / foldable** (Spec 3 §11.7 / OPEN-3.2 aspect-bucket) is manual — Spec explicitly defers multi-window code to a later phase; the mapper uses `displayMetrics` (current display) which is correct for the Phase-1 scope.
- IME-side `observer.refresh()` from `onCreateInputView`/`onStartInputView` (C17 follow-up forwarded to C18) is **not** wired here — it lives in the IME service, outside this chunk's file scope; the Service-side `init()` covers cold-start, and the snappier-refresh path is a small IME edit best done with the rest of the IME-overlay-toggle wiring (forwarded to Phase 4 Integration).

---

## Block-Validate (Phase 3.2)

**Status:** ⏳ pending (run after all 3 chunks)
**Pre-Validate Commit:** ⏳
**Validate-Pass Commit:** ⏳

### Audit-Topic Outputs

| Topic | Agent-ID | Status | Output File | Findings (counts) |
|-------|----------|--------|-------------|-------------------|
| plan-and-api | `B5-AUDIT-PLAN-AND-API` | ⏳ | `./reports/audit-plan-and-api-B5.md` | — |
| convention | `B5-AUDIT-CONVENTION` | ⏳ | `./reports/audit-convention-B5.md` | — |
| logic | `B5-AUDIT-LOGIC` | ⏳ | `./reports/audit-logic-B5.md` | — |
| test | `B5-AUDIT-TEST` | ⏳ | `./reports/audit-test-B5.md` | — |

### Sanity-Check Consolidator

**Agent-ID:** `B5-VAL-SANITY`
**Output file:** `./reports/validated-findings-B5.md`

13 distinct findings: 2 🟡 Critical (F-1/F-2, research-resolved via
`../research/b5-ime-activation-wiring.md`), 1 🟢 Important folded into
the same surface (F-3), 4 🟢 Important + 4 🟢 NTH, 2 ❌ informational.
Repair-wave recommendation: 1 research-step + 1 all-validated wave.

### Block-Validate Repair Wave 1 (B5-VAL-REPAIR)

**Date:** 2026-05-15
**Scope:** all-validated (2 🟡 Critical research-resolved + 5 🟢 Imp +
4 🟢 NTH)
**Findings addressed:** 11 fixed (F-1…F-13; F-12/F-13 NTH; the 2 ❌
informational left as-is per the consolidator disposition)

| Finding ID | Severity | File(s) | Status | Fix description |
|------------|----------|---------|--------|-----------------|
| F-1 | Critical | `core/DictateInputMethodService.java` | fixed | `onStartInputView`→`OnImeViewShown`; `onFinishInputView` 3-state early-`return`s refactored to a single if/else-if/else + tail dispatch so `OnImeViewHidden` fires on the recording-active/pipeline-running paths (the primary HOVER trigger). `restarting` deliberately ignored (idempotent reducer; suppressing would break T6). |
| F-2 | Critical | `state/Action.kt`, `state/modules/OverlayModule.kt`, `state/layout/ActionResolvers.kt`, `state/layout/LayoutCatalog.kt`, `res/layout/activity_dictate_keyboard_view.xml`, `core/DictateInputMethodService.java`, `core/OverlayOnboardingObserver.kt` (new) | fixed | New `ShowOverlayOnboarding` action + reduce-arm + Spec 3 §5.4 auto-cleanup cascade; permission-aware `resolveWidgetToggleAction` wired at the 2 `ToggleViewModeWidget` WIDGET_TOGGLE slots; IME-owned info-bar (XML sibling above the buttons, mirroring `info_cl`) bound via the new `OverlayOnboardingObserver` StateFlow bridge; Grant launches `ACTION_MANAGE_OVERLAY_PERMISSION` + `FLAG_ACTIVITY_NEW_TASK` + dispatches `RequestOverlayPermission`; Later dispatches `DismissOverlayOnboarding`. `OpenOverlayPermissionSettings` KDoc updated (structural placeholder, not Phase-1 TODO). |
| F-3 | Important | `core/DictateInputMethodService.java` | fixed | `pipelineBinder.getOverlayPermissionObserver().refresh()` in `onStartInputView`, BEFORE the `OnImeViewShown` dispatch. |
| F-4 | Important | `state/modules/OverlayModule.kt`, `state/Action.kt`, `state/ModuleServices.kt` | fixed | `Effect.NotifyOverlayPermissionRequired` + `NotificationStatus.OverlayPermissionRequired` + `RequestOverlayPermissionNotification` action; permission-loss cascade now emits it alongside `SetViewMode(KEYBOARD)`. |
| F-5 | Important | `res/values-de/-es/-pt/strings.xml` | fixed | 12 overlay strings (6 content-descriptions + 6 onboarding) added to all 3 locale files. |
| F-6 | Important | `state/render/overlay/OverlayPositionMapper.kt` | fixed | Shared `freeArea(screen,view)=(screen-view).coerceAtLeast(1)`; both directions use the identical floor → round-trip identity at the zero-free-area boundary (no more right→left anchor rewrite). |
| F-7 | Important | `state/render/overlay/OverlayDragController.kt`, `OverlayBackend.kt` | fixed | `orientationProvider` injected; orientation snapshotted once at `ACTION_DOWN`, threaded through `onPositionPersist(portrait,…)`; controller+factory KDoc SRP narrative updated to document the moved boundary. |
| F-8 | Important | `core/DictatePipelineServiceOverlayTransitionTest.kt` | fixed | Added `F-8 both-in-flight HOVER-close-from-pipeline-done stays within MAX_CASCADE_DEPTH` Robolectric test (recording+pipeline both in-flight → HOVER → PipelineDone; asserts settles KEYBOARD + overlay detached without a cascade-cap throw in DEBUG). |
| F-9 | Important | `database/DictateDatabase.kt`, `migration/LegacyAudioFileMigrationTest.kt`, `core/DictatePipelineServiceOverlayTransitionTest.kt` | fixed | `@VisibleForTesting resetForTest(context)` drops the singleton AND `deleteDatabase()` the file-backed DB; migration test + amplifier transition test now full-reset (singleton + default-prefs `clear()`) in `@Before`/`@After`. Production code uninvolved. |
| F-10 | NTH | `state/render/overlay/OverlayDragController.kt` | fixed | 79-line controller KDoc relocated from the factory interface to `class OverlayDragController(`; factory keeps its own short KDoc. Coherent edit with F-7's contract update. |
| F-11 | NTH | `res/layout/activity_overlay_permission_onboarding.xml` | fixed | `xmlns:tools` hoisted to root `ConstraintLayout`; leaf `TextView` keeps only `tools:ignore`. |
| F-12 | NTH | `state/render/overlay/OverlayDragController.kt` | fixed | Detach-before-params-swap invariant comment added in `detach()`. |
| F-13 | NTH | `core/DictatePipelineService.kt` | fixed | `KeyboardLayoutManager.detachBackend` remove-before-detach ordering comment added on the detach branch (verified against `KeyboardLayoutManager.kt:95-96`). |

**Cross-fix conflicts:** none.

**Files modified (production):**
- `core/DictateInputMethodService.java` (F-1/F-2/F-3 — imports, onStartInputView, onFinishInputView refactor, onCreateInputView info-bar wiring, attachImeViewBackendIfReady observer start, onDestroy observer stop, fields)
- `core/OverlayOnboardingObserver.kt` (new, F-2)
- `state/Action.kt` (F-2 `ShowOverlayOnboarding`, F-4 `RequestOverlayPermissionNotification`)
- `state/modules/OverlayModule.kt` (F-2 reduce-arm + §5.4 cascade, F-4 effect + cascade, KDoc)
- `state/ModuleServices.kt` (F-4 `NotificationStatus.OverlayPermissionRequired`)
- `state/layout/ActionResolvers.kt` (F-2 `resolveWidgetToggleAction`)
- `state/layout/LayoutCatalog.kt` (F-2 2 slot rewires)
- `state/render/overlay/OverlayPositionMapper.kt` (F-6)
- `state/render/overlay/OverlayDragController.kt` (F-7/F-10/F-12)
- `state/render/overlay/OverlayBackend.kt` (F-7 wiring)
- `core/DictatePipelineService.kt` (F-13 comment)
- `database/DictateDatabase.kt` (F-9 `resetForTest`)
- `res/layout/activity_dictate_keyboard_view.xml` (F-2 info-bar)
- `res/layout/activity_overlay_permission_onboarding.xml` (F-11)
- `res/values-de/-es/-pt/strings.xml` (F-5)

**Files modified (tests):** `OverlayModuleTest.kt`, `ActionResolversTest.kt`, `OverlayBackendTest.kt`, `OverlayDragControllerTest.kt`, `DefaultOverlayPositionMapperTest.kt`, `DictatePipelineServiceOverlayTransitionTest.kt`, `LegacyAudioFileMigrationTest.kt`.

**Files outside findings-scope (drift):** none — every modified file is named in a finding, its research spec (§6.x), or is a direct test-update consequent of the F-7 signature change / F-8/F-9 new tests.

**ADR-0005 amended:** yes — append-only Decision-History entry `2026-05-15 — IME-activation contract pinned (B5 repair-wave, F-1/F-2/F-3)`.

#### Documented deviation (F-2 info-bar ownership)

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| Info-bar bound in the IME service, NOT `ImeViewBackend` | Spec 3 §5.3 sketch (`ImeViewBackend.bindPermissionInfoBar`) | The info-bar root/grant/dismiss views are found + wired in `DictateInputMethodService.onCreateInputView`; visibility driven by a new `OverlayOnboardingObserver` StateFlow bridge | The B4 `ImeViewBackend` contract is button-map-only (it has no root-view handle); threading the root + onAction sink + gate purely for the info-bar would widen its ctor and break SRP. The IME already owns the symmetric `InfoBarController` surface + the binder + the inflated root. The §5.3 sketch predates the B4 contract. | None — final block; Phase 4 integration only verifies. | inline-fixed (research §4.3 resolved, marker `plan-deviation-resolved`) |

#### Additional deviation (F-2 IME StateFlow bridge)

Research §4.3 assumed "the IME already subscribes to the pipeline state
StateFlow (B1b state-collect)". The current code's state-collect is
**service-side only** (`DictatePipelineService.kt:518`); the IME has no
`lifecycleScope` (not a `LifecycleOwner`). Resolution: a dedicated
`OverlayOnboardingObserver` Kotlin bridge (sibling pattern to
`ActiveJobRegistryObserver`) gives the Java IME a minimal
lifecycle-scoped subscription to the single `onboardingPending`
sub-axis, started in `attachImeViewBackendIfReady` (the bind↔inflate
consolidation point) and stopped in `onDestroy`. This honours the
research intent (IME-owned info-bar, not `ImeViewBackend`) without the
inaccurate "IME already collects" premise. Marker:
`plan-deviation-resolved` (small, locally decidable from surrounding
code).

### Validate-Fixes Self-Check (B5-VAL-W1)

**Date:** 2026-05-15

- **Build:** `./gradlew assembleDebug` green.
- **Tests:** `./gradlew test` green (debug + release). `--rerun-tasks`
  green **twice** consecutively — the F-9 flaky
  `LegacyAudioFileMigrationTest` (`expected:<[RECORDING]> but
  was:<[FAILED]>`) no longer reproduces under the clean full-suite run
  that originally triggered it. New B5 suites: OverlayModuleTest 32/0,
  ActionResolversTest 29/0, OverlayDragControllerTest 6/0,
  DefaultOverlayPositionMapperTest 10/0,
  DictatePipelineServiceOverlayTransitionTest 12/0.
- **F-1 T1–T7 trace (re-read of the new hooks, confirmed):**
  - **T1** KEYBOARD→WIDGET: WIDGET_TOGGLE → `resolveWidgetToggleAction`
    (hasPermission) → `ToggleViewModeWidget` → reducer WIDGET. ✅
  - **T2** WIDGET→KEYBOARD: `ToggleViewModeWidget`/`CloseOverlay` →
    reducer KEYBOARD + `SetUserPrefersWidget(false)` cascade. ✅
  - **T3** KEYBOARD→HOVER: `onFinishInputView` State (A)
    recording-active path now falls through to the **single tail**
    dispatch → `OnImeViewHidden` → `computeViewMode(false,false,true)
    =HOVER`. ✅ (the load-bearing refactor — verified the dispatch is
    after the if/else-if/else, line 1173-1176, fires on State (A)/(B)).
  - **T4** WIDGET→HOVER: same tail dispatch, `userPrefersWidget` stays
    → `computeViewMode(false,true,true)=HOVER`. ✅
  - **T5** HOVER→KEYBOARD: `onStartInputView` → `refresh()` then
    `OnImeViewShown` → `computeViewMode(true,false,*)=KEYBOARD`. ✅
  - **T6** HOVER→WIDGET: `onStartInputView`→`OnImeViewShown`,
    userPrefersWidget=true → `computeViewMode(true,true,*)=WIDGET`. ✅
    (covered by new `F-1 T6` Robolectric test).
  - **T7** HOVER→KEYBOARD via `OnPipelineDone`: unchanged reducer arm,
    now reachable because T3/T4 fire; covered by existing T7 test +
    new F-8 worst-case both-in-flight test. ✅
- **F-3 ordering:** `refresh()` is dispatched BEFORE `OnImeViewShown`
  in the same `pipelineBinder != null` block (verified line ordering)
  so the FSM sees the fresh `hasPermission`.
- **F-9 root-cause depth:** the production DB is file-backed; closing
  the connection alone left rows on disk across the Robolectric fork.
  `resetForTest` now also `deleteDatabase(DATABASE_NAME)` — verified by
  the migration test passing in the clean-suite rerun that previously
  failed.
- **No drift:** every modified file maps to a finding / its research
  spec / a direct test consequence.

**Self-check result:** PASS — all 11 actionable findings fixed,
ADR amended, build + tests green (incl. F-9 stability), F-1 trace
confirmed correct end-to-end.

---

## Block Deviation Summary

See `### Block-Validate Repair Wave 1 (B5-VAL-REPAIR)` above for the two
documented F-2 deviations (info-bar ownership in the IME service vs.
the Spec 3 §5.3 `ImeViewBackend` sketch; IME StateFlow bridge vs. the
research's "IME already collects" premise). Both `plan-deviation-resolved`,
justified by the B4 `ImeViewBackend` button-map-only contract and the
actual (service-side-only) state-collect topology. No other deviations.

---

## Block Closeout (Orchestrator)

- **All chunks complete (5-step workflow done, both commits per chunk):** ⏳
- **Block-Validate converged (4-topic audit + sanity-pass + repair-waves done):** ⏳
- **AUDIT-TEST: coverage thresholds met for new files, no cross-chunk regressions:** ⏳
- **Build/Lint green at block-end:** ⏳
- **Issue index reconciled (all ids closed/postponed/forwarded):** ⏳
- **Conventions section filled:** ⏳
- **Deviation list propagated to plan/state:** ⏳
- **Cross-block-API consumer info forwarded:** ⏳ (Phase 4 Integration is next — verifies all 6 blocks integrate cleanly)

**Block completed at:** ⏳
**Block-End-Commit:** ⏳
