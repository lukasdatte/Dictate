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

**Severity counts:**
- Critical: 0
- Important: 0
- Nice-to-have: 0
- Postponed: 0

**By status:**

| ID | Source agent | Severity | Status | Title | Source phase |
|----|--------------|----------|--------|-------|--------------|
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

**Agent-IDs:** Steps 1-5: `B5-C17-IMPL-FULL`

**Status:** ⏳ pending (depends on C16)

⏳

---

### Chunk C18-block6-mode-transitions-and-drag — Mode-transitions T1-T7 + Drag + OverlayModule

**Agent-IDs:** Steps 1-5: `B5-C18-IMPL-FULL`

**Status:** ⏳ pending (depends on C17)

⏳

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

⏳

---

## Block Deviation Summary

⏳

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
