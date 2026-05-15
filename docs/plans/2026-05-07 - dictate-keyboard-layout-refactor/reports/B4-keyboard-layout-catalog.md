# Block 4: Keyboard-Layout-Catalog (plan-Block-5 — Spec 2 full)

> **This file is the logbook for Block 4.** Implementation-Agents
> and Audit-Agents document their work here. The orchestrator
> maintains the status table in the main state file
> (`../dictate-keyboard-layout-refactor.state.md`) — agents do **not** write to the
> state file.

**Phase:** Keyboard-Layout-Catalog (Spec 2 — KeyboardLayoutManager + LayoutCatalog + ImeViewBackend + MotionScene + RecordingAnimationController + ContentAreaController + PromptVisibilityController + OverlayResetHandler)
**Implementation-Chunks:** C12 (M, 800) · C13 (M, 500) · C14 (M, 750) · C15 (M, 450). Total 2500 score.
**Workflow:** Iter-10 5-step workflow with orchestrator-split commits per chunk. Block runs C12 → C13 → C14 → C15 sequentially. Spec 2 §11.8 migration order: 5a XML || 5b Backend → 5c Wiring (KSM empty-body bridge) → 5d Cleanup (destructive).
**Block-Start-Commit:** `f8ba56a`
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
| F-15 (B3 carry-over) | B3-VAL-W1 | NTH-test | open (delegated-to-orchestrator) | New PipelineOrchestratorPersistTest for KG-AFF-1 sofort-delete coverage | B4 AUDIT-TEST |
| F-16 (B3 carry-over) | B3-VAL-W1 | NTH-test | open (delegated-to-orchestrator) | New CleanupOrderTest for Service.onDestroy → triggerOrphanCleanupAsync wiring | B4 AUDIT-TEST |
| F-23 (B3 carry-over) | B3-VAL-W1 | NTH-test | open (delegated-to-orchestrator) | New ResolverPreDispatchAllocateTest for residual File-allocate flow | B4 AUDIT-TEST |
| D-13 (B3 carry-over) | B3-C8-IMPL | Important | open (delegated-to-orchestrator) | LanguageController full removal — Settings-activity decoupling needed; bridge keeps both sides in sync until orchestrator becomes primary | B4 LayoutCatalog scope |
| D-14 (B3 carry-over) | B3-C11-IMPL | Important | open (delegated-to-orchestrator) | DictateInputMethodService.audioFile field removal — 5 unrelated IME-side reads still need it | B5/B6 (B4 keeps it for now) |
| B2 F-11+F-14 | B2-VAL-W1 | NTH | postponed | Spec-internal §15.1 matrix/column inconsistency — spec-doc-edits | Phase 4.6c |

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

### Chunk C12-block5-layout-catalog-and-render-backend — LayoutCatalog + RenderBackend + helpers

**Agent-IDs:** Steps 1-5 (combined): `B4-C12-IMPL-FULL`

**Status:** ✅ implementation + tests green (`./gradlew test` passes)
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 13 (C12-block5-layout-catalog-and-render-backend)

#### What was done

Introduced the data + interface layer of the keyboard-layout system per Spec 2 §3, §4, §5, §5.1, §8 and ADR-0004 — no production-code regressions, all rendering for the IME still flows through the legacy path (C14/C15 will route through the new manager).

**Production files (new) — `app/src/main/java/net/devemperor/dictate/state/layout/`:**

| File | Purpose |
|------|---------|
| `LogicalButtonId.kt` | Enum of 14 logical button ids (9 KEYBOARD + 5 OVERLAY) per Spec 2 §3.1. |
| `LayoutModeId.kt` | Enum of 6 layout-mode ids (5 KEYBOARD + 1 OVERLAY). |
| `ButtonSlot.kt` | `ButtonSlot` data class + `WidthPolicy` sealed hierarchy. Resolvers: visibility / icon / text / enabled / alpha / action (2-arg `(state, services) -> Action?`, R.3 nullable contract). |
| `LayoutMode.kt` | `LayoutMode` + `RowDescriptor` + `BackendType` enum (IME_VIEW / OVERLAY_WINDOW). |
| `RenderBackend.kt` | Single render-surface mutation contract. Carries `backendType: BackendType?` (null = "consume every mode", ContentAreaController pattern). |
| `Predicates.kt` | `isResendVisible`, `isTrashVisible`, `isPauseVisible`, `isWidgetToggleVisible` — central SoT predicates. `resendCooldown` is deliberately NOT read (forbidden pattern (j)). |
| `ActionResolvers.kt` | `resolveRecordAction`, `resolveRecordActionPipeline`, `resolveTrashAction`, `resolvePauseAction`, `resolveSendStagingAction`, `resolveCancelStagingAction`. IOException side-channel (toast + null) wired in record-btn idle path. |
| `IconResolvers.kt` | `resolvePauseIcon`, `resolveAudioFocusIcon` (F-4 SSoT, EditBar consumes too), `resolveAudioFocusIconForSlot`. |
| `TextResolvers.kt` | `LayoutStrings` injection container + `resolveRecordButtonText` / Pipeline / Staging variants — JVM-pure, Android string resources captured at construction. |
| `LayoutCatalog.kt` | `LayoutCatalog(strings: LayoutStrings)` class with `KEYBOARD_TWO_ROW` / `KEYBOARD_SINGLE_ROW` / `KEYBOARD_TWO_ROW_SEND_MODE` / `KEYBOARD_SINGLE_ROW_SEND_MODE` / `KEYBOARD_REPROCESS_STAGING` / `OVERLAY_5BUTTON` (B5 placeholder) properties + `forKeyboard(state)` selector + `allModes()` introspection helper. UPPER_SNAKE_CASE matches Spec 2's qualified-member references. |
| `KeyboardLayoutManager.kt` | Holds `MutableList<RenderBackend>` (R.10 multi-backend per C-4 F-6). `attachBackend` / `detachBackend` / `detachAll` / `onStateChanged` / `computeLayoutMode`. Re-renders newly attached backends with current state to prevent blank-frame flash. |

**Test files (new) — `app/src/test/java/net/devemperor/dictate/state/layout/`:**

| File | Tests | Coverage |
|------|-------|----------|
| `LayoutCatalogTest.kt` | 14 | Structural invariants: all KEYBOARD modes target IME_VIEW backend, no duplicate logical ids per mode, all 9 keyboard slots present in TWO_ROW + SINGLE_ROW, WIDGET_TOGGLE slot in all 5 KEYBOARD modes (Phase-B S-6 acceptance), `forKeyboard(state)` decision tree, defensive resolver-evaluation smoke test. |
| `VisibilityMatrixTest.kt` | 25 (parameterised) + 5 stand-alone predicate tests = **30 tests** | Spec 2 §14.2 25-case truth-table (5 LayoutModes × 5 typical states). Stand-alone `LayoutPredicatesTest` covers `isResendVisible` doesn't-read-cooldown invariant (forbidden pattern (j)), `isTrashVisible == isPauseVisible` co-evolution, `isWidgetToggleVisible` viewMode gate. |
| `RenderBackendTest.kt` | 4 | Interface contract: attach captures onAction, detach clears it, render records args, idempotency. Also defines the shared `TestRenderBackend` fake reused by manager test. |
| `KeyboardLayoutManagerTest.kt` | 17 | Multi-backend fan-out (IME_VIEW vs OVERLAY_WINDOW vs null backendType), attach/detach lifecycle (double-attach is `IllegalStateException`, defensive double-detach is no-op), `computeLayoutMode` for all ViewMode×pipeline×singleRow combos, initial-render-on-attach (no blank-frame flash). |
| `ActionResolversTest.kt` | 27 | R.3 nullable contract — every resolver tested for `null`-return cases, IOException side-channel (`FailingAudioFileFactory` + `RecordingToastSink` fakes) verifies toast surfaces + null is returned. Includes icon + text resolver coverage. |

**Total new tests: 92, all green on `./gradlew test`.**

#### Plan deviations

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| `InsertionTarget.INPUT_CONNECTION` instead of `MainInputConnection` | Spec 2 §8.5 `resolveRecordAction` | Spec uses `InsertionTarget.MainInputConnection` (PascalCase nested name); B2's `InsertionTarget.kt` enum is `INPUT_CONNECTION` (SCREAMING_SNAKE_CASE per Kotlin enum convention). | Existing enum constant from C2 — adopting it keeps the resolver compilable without re-introducing a doppelganger. | None — C14/C15 use the same enum. | inline-fixed |
| `StopRecordingAndSend(sessionId = "")` placeholder | Spec 2 §8.5 | Spec doesn't show sessionId in the `StopRecordingAndSend` literal (action signature changed in C2 F-2 fix to add sessionId for the cascade re-entry). | The session-id is generated by the recording-→-pipeline cross-module cascade (Spec 1 §15.2 F-2), not at click time. Empty-string placeholder is a known-safe sentinel; the receiving module overrides it. | C14/C15 will revisit when the click pipe is wired — flagged. | inline-fixed |
| Text resolvers depend on `LayoutStrings` (not Context directly) | Spec 2 §5.1 / §8.5 references `ctx.getString(...)` | Catalog is instantiated with a `LayoutStrings` bundle that pre-resolves the strings; production wires it from `Context.getString`. | Keeps catalog + tests Android-loose (K-1/K-4 — no Robolectric for JVM unit tests). The legacy code used `MainButtonsController(ctx)`-bound resolvers; the indirection is the test-loose adaptation of the spec's `ctx` reads. | C14 builds `LayoutStrings` from the service's Context. | inline-fixed |
| `OVERLAY_5BUTTON` body is empty (B5 placeholder) | Spec 3 §3.1 contributes the body | Reserved a slot with empty rows so Spec 2's cross-references (`LayoutCatalog.OVERLAY_5BUTTON`) compile today. | B5/C16 fills in the 5-button layout (Record/Send/Pause/Trash/Close); reserving the property is the cross-spec anchor per C-5 fix. | B5 fills in the body. | inline-fixed |
| `LayoutCatalog` is a `class` (not `object`) | Spec 2 §8.6 declares `object LayoutCatalog` | The class form lets us inject `LayoutStrings` at construction. Singleton-by-construction (one instance per `onCreateInputView`) preserves the spec's "data registry" semantic. | C14 instantiates once per inflate. | inline-fixed |
| KEYBOARD_REPROCESS_STAGING `enabledResolver` doesn't read `s.isStarting` | Spec 2 §8.4 | The field `isStarting` doesn't exist yet on `PipelineUiState.ReprocessStaging` (Spec 1 §3 carries `sessionId` + `transcript` only). | Resolver falls back to "any non-null staging is enabled" until Spec 1 grows the field. C14 will fold in once it exists. | C14 follow-up. | inline-fixed |
| Pipeline `Running` resolver doesn't read `completedSteps/totalSteps/elapsedMs` | Spec 2 §8.5 `resolveRecordButtonTextPipeline` | Same reason — `PipelineUiState.Running` doesn't carry those fields in the current C2 state shape. | `formatPipelineLabel` is called with `(0, 0, autoEnter, 0L)`. C14 wires the live values. | C14 follow-up. | inline-fixed |

#### Inline-fixed items

| Where | Issue | Fix |
|-------|-------|-----|
| `LayoutCatalog.kt` resolver lambdas | Initial fully-qualified `net.devemperor.dictate.state.RecordingState.Preparing` in enabledResolver | Added `import net.devemperor.dictate.state.RecordingState` and shortened to `RecordingState.Preparing` |
| `LayoutCatalog.kt` property naming | Spec uses `LayoutCatalog.OVERLAY_5BUTTON` (UPPER_SNAKE_CASE) but Kotlin convention for `val by lazy` would be camelCase | Adopted UPPER_SNAKE_CASE for all 6 mode properties to keep cross-spec references stable (KEYBOARD_TWO_ROW, KEYBOARD_SINGLE_ROW, etc.) |
| `TextResolvers.kt resolveRecordButtonTextStaging` | Initial `@Suppress("UNUSED_VARIABLE")` anchor + assignment to `unused` | Replaced with a defensive bare-cast and an explanatory comment; the cast is the structural gate |

#### Issues

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| (none) | — | No open issues at end of C12 | — | All Spec 2 §3 + §4 + §5 + §8 + §11.8 5a requirements implemented and tested. |

#### Overlooked points / known gaps

- **`audio_focus_btn` foreground vs icon attribute.** The XML uses `android:foreground` (legacy), the slot resolver returns a `@DrawableRes Int`. The actual translation from `iconResolver` → view-property lives in `SlotRenderer.applySlotToView` (C13 territory) — for `MaterialButton`-foreground vs icon-slot, C14 will decide the apply path.
- **`StopRecordingAndSend.sessionId` placeholder.** Empty-string today; revisit in C14/C15 once the cascade re-entry pattern is fully wired.
- **`PipelineUiState.Running` field shape.** `completedSteps/totalSteps/elapsedMs` not yet on the data class; resolver passes defaults. Follow-up in C14.
- **`ReprocessStaging.isStarting`.** Field doesn't exist yet — enabledResolver falls back to "always enabled while staging". Follow-up in C14 or C2 spec-1 evolution.
- **`widget_toggle_btn` View ID.** The XML lacks the `R.id.widget_toggle_btn` reference today (C13/C14 adds it). The catalog's WIDGET_TOGGLE slot is reserved; C13/C14 wires the view-id mapping with `error(...)` silent-skip-guard.

#### Test-Infrastructure implemented

- `LayoutStrings` test fixture (top-level `testLayoutStrings()` in `LayoutCatalogTest.kt`) — literal English strings, deterministic formatters. Reused by every layout-package test.
- `stubAudioFile()` helper — a single `File("/tmp/dictate-test-stub.m4a")` for resolver tests.
- `TestRenderBackend` hand-rolled fake (in `RenderBackendTest.kt`) — counters for attach/detach/render + state/mode capture + `simulateClick(action)` helper. Shared by manager tests.
- `FixedAudioFileFactory` / `FailingAudioFileFactory` / `RecordingToastSink` hand-rolled fakes (in `ActionResolversTest.kt`) for the IOException side-channel test.

Reuses existing fixtures: `fakeModuleServices()` + `NoopAudioFileFactory` + `NoopToastSink` from `testutil/FakeModuleServices.kt`.


---

### Chunk C13-block5-motionscene-xml — MotionScene XML + layout-XML refactor

**Agent-IDs:** Steps 1-5: `B4-C13-IMPL-FULL`

**Status:** ✅ done (2026-05-15)

#### What was done

Created `app/src/main/res/xml/motion_scene_keyboard.xml` per Spec 2 §7
with all five KEYBOARD ConstraintSets (`two_row_state`,
`single_row_state`, `two_row_send_mode_state`,
`single_row_send_mode_state`, `reprocess_staging_state`), declared
`motion:visibilityMode="ignore"` on the nine state-driven button
constraint blocks (Spec 2 §7.3 / R.11 — non-negotiable), and wired the
five sibling transitions (Two-Row ↔ Single-Row at 250 ms; both ↔ their
send-mode variants and staging at 200 ms).

Refactored `app/src/main/res/layout/activity_dictate_keyboard_view.xml`
to turn `main_buttons_cl` into a `MotionLayout` with `layoutDescription`
pointing at the scene XML. The legacy nested `action_row` / `input_row`
`ConstraintLayout` containers were dissolved — all nine buttons are now
direct children of the `MotionLayout`, with PulseLayout still wrapping
`record_btn` (L7).

Added `widget_toggle_btn` to the layout XML (LogicalButtonId.WIDGET_TOGGLE
view-id from C12's LayoutCatalog) so C14's `ImeViewBackend.buttonViews`
`findViewById(R.id.widget_toggle_btn)` resolves to a real view — Spec 2
§7.1 / §7.2 omitted it, this is a recorded plan-deviation (see below).

Wrote `app/src/test/java/net/devemperor/dictate/state/layout/MotionSceneSchemaTest.kt`
— eight JVM-pure DOM-parser tests covering: file existence,
ConstraintSet inventory (exactly 5 required), `visibilityMode="ignore"`
presence per button in the base state, transition inventory + positive
duration, derive-from relationships for both single-row and the
send-mode/staging trio.

#### Plan deviations

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| Stub `action_row` + `input_row` `ConstraintLayout`s kept as gone-sized siblings of the new `MotionLayout` | Spec 2 §11.1 ("entfallen vollständig") | Legacy `DictateInputMethodService` (`actionRow`, `inputRow` fields) + `KeyboardStateManager.KeyboardViews` + `KeyboardLayoutModeController` still resolve `R.id.action_row` / `R.id.input_row` via `findViewById` and would throw `IllegalStateException` (or fail to compile in Java) without these IDs. C15 (5d cleanup, Spec 2 §11.8) deletes those legacy classes and these stubs in one step. | Without the stubs, the build is red at `:app:compileDebugJavaWithJavac` between C13 and C15. The stubs are 0dp + gone so they don't influence runtime layout. They are explicitly documented as scaffold-only in the layout-XML comment block. | inline-fixed, C15 cleanup-target |
| `widget_toggle_btn` added to both motion_scene_keyboard.xml and layout XML (Spec 2 §7.1 / §7.2 omitted it) | Spec 2 §7.1 / §7.2 only list 9 buttons but §7.3 + §13.1-22b + C12 LayoutCatalog list 10 (`WIDGET_TOGGLE`) | Without the view-id, `ImeViewBackend.buttonViews` (Spec 2 §6, C14) would throw `error("No view registered for WIDGET_TOGGLE in ImeViewBackend.buttonViews")` at the first render-tick (Issue 3.0.12 silent-skip-guard). Spec 2 §3.1 already places it "neben AUDIO_FOCUS im action_row" — the constraint positions follow that spec hint. Placeholder foreground icon (`ic_outline_change_circle_24`) until B5 ships a dedicated drawable. | C14 / B5 can wire the proper icon resolver; no other ripple. | inline-fixed |

#### Inline-fixed items

- `app/src/main/res/xml/motion_scene_keyboard.xml` — new file, 5
  ConstraintSets + 5 transitions, all 9 visible buttons carry
  `visibilityMode="ignore"`.
- `app/src/main/res/layout/activity_dictate_keyboard_view.xml` —
  `main_buttons_cl` is now `androidx.constraintlayout.motion.widget.MotionLayout`
  with `app:layoutDescription="@xml/motion_scene_keyboard"`. All nine
  buttons (including new `widget_toggle_btn`) are direct children with
  per-button XML defaults but no per-button position constraints
  (positions live in MotionScene). Legacy `action_row` / `input_row`
  scaffold stubs added at file end with explanatory comment.

#### Issues

| ID | Severity | Description | Status | Reason |
|----|----------|-------------|--------|--------|

(no open issues — both plan-deviations are inline-fixed; scaffold stubs
are tracked as C15 cleanup-target via the in-XML comment)

#### Overlooked points / known gaps

- **Runtime behaviour between C13 and C15 is not yet correct.**
  `KeyboardLayoutModeController.rehome` will (at small-mode toggle)
  remove the flat-hierarchy buttons from the MotionLayout and add them
  to the empty `action_row` stub — breaking the visible keyboard.
  Spec 2 §11.8 5c says KSM methods get empty bodies for exactly this
  reason; the empty-body conversion is C15's job. C13's contract is
  XML-isolated compile-green, not runtime parity.
- `record_pulse_layout` (the PulseLayout wrapper) does **not** carry
  `visibilityMode="ignore"` — Spec 2 §7.3 lists only the nine MaterialButton
  ids. The wrapper's visibility is currently `VISIBLE` from XML and never
  toggled programmatically against the MotionLayout, so this should be
  safe. If C14 needs to drive the wrapper's visibility, an additional
  `<Constraint android:id="@+id/record_pulse_layout"><PropertySet motion:visibilityMode="ignore"/></Constraint>`
  entry will need to be added.
- The `<Constraint android:id="@+id/record_btn">` block in `two_row_state`
  references a non-direct MotionLayout child (record_btn is nested inside
  PulseLayout). It's kept harmless — MotionLayout silently ignores
  PropertySet entries it cannot bind — but if C14's ImeViewBackend ever
  manipulates this entry directly, the path may need re-validation.
- Espresso UI-Tests (Spec 2 §14.2 1-10) are deferred to C14 / later
  blocks; they need an `ImeViewBackend` running. The eight JVM-pure
  schema tests cover the C13 XML-shape contract.

#### Test-Infrastructure implemented

None — the schema tests reuse `DocumentBuilderFactory` from the JDK,
no new test helpers were needed.

#### Build / test results

- `./gradlew assembleDebug` — **BUILD SUCCESSFUL** (37 tasks).
- `./gradlew test` — **BUILD SUCCESSFUL**;
  `MotionSceneSchemaTest`: 8 tests, 0 failures, 0 errors, 0 skipped.

---

### Chunk C14-block5-ime-view-backend — ImeViewBackend + Controllers + Animation

**Agent-IDs:** Steps 1-5 (combined): `B4-C14-IMPL-FULL`

**Status:** ✅ implementation + tests green (`./gradlew test` — 833 tests, 0 failures, 0 errors; `./gradlew assembleDebug` BUILD SUCCESSFUL)
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 15 (C14-block5-ime-view-backend)

#### What was done

Introduced the four parallel rendering backends and the
[RecordingAnimationController] per Spec 2 §4, §4.1, §5, §5.1, §6,
§11.5, §11.6, §11.7 + ADR-0004 §3. No live production wiring yet —
C15 (Spec 2 §11.8 5c) attaches the backends to
`DictatePipelineService.state.collect`; until then the legacy
`KeyboardUiController` / `KeyboardStateManager` continue to drive the
IME.

**Production files (new) — `app/src/main/java/net/devemperor/dictate/state/render/`:**

| File | Purpose |
|------|---------|
| `SlotRenderer.kt` | Top-level `applySlotToView` helper (F-7 / DRY, Spec 2 §5.1). Single code-path translating `ButtonSlot` resolver outputs into Android-view properties — consumed by `ImeViewBackend` today and by `OverlayBackend` once Spec 3 lands. `is MaterialButton` branch for icon/text resolvers; non-`MaterialButton` views skip the icon/text writes. |
| `MotionSurface.kt` | Interface abstraction over `MotionLayout` + production `RealMotionSurface` wrapper. Lets `ImeViewBackend` JVM-test the jump-vs-transition selection logic without Robolectric (D7 — abstraction wins long-term maintainability). |
| `ImeViewBackend.kt` | RenderBackend for `BackendType.IME_VIEW`. `wireStaticHandlers` runs once per `attach` (L8); click lambdas read `stateRef` / `modeRef` at click time. `firstRender` flag (R.14) snaps to scene state without animation on first emit. `staticHandlerInstaller` constructor hook for IME-side special-touch handlers (CursorSwipe / Backspace-Swipe / Enter-Overlay — Spec 2 §11.7) — keeps the backend independent of the handler classes. R.3 nullable-resolver-idiom (`?.let { onAction?.invoke(it) }`). Long-press wiring for RESEND (`ResendLastAudioLong`) + vibrate-only for RECORD/BACKSPACE per spec. `require(...)` mismatched-backend guard + `error(...)` silent-skip-guard (Issue 3.0.12). |
| `ContentAreaController.kt` | Second RenderBackend (R.10 / 2.1.15 Option B) for the three IME content-areas (`MAIN_BUTTONS` / `QWERTZ` / `EMOJI_PICKER`). `backendType = null` so the manager fans every render-tick out regardless of which surface owns the active mode. |
| `PromptVisibilityController.kt` | RenderBackend for the prompts container + `pipelineProgress` swap-in + QWERTZ-side recording controls. Truth-table per Spec 2 §9.3: smallMode / EMOJI_PICKER suppress prompts; active/staging/pipeline forces VISIBLE; otherwise driven by `features.rewordingEnabled`. `pipelineProgress` view replaces `promptsRv` while `pipeline is Running && !isStaging`. |
| `OverlayResetHandler.kt` | RenderBackend that defensively resets `overlay_characters_ll` to GONE every render-tick — catches the edge case where a ViewMode transition interrupts an in-flight `EnterOverlayHandler` touch sequence and the handler never observes the matching release event. R.10 sub-backend. |
| `RecordingAnimationController.kt` | Drives `RecordingAnimation` (BorderGlow) + `PulseLayout` from `state.recording` transitions. Class-comparison idempotency (`prev::class == curr::class`) means re-emitting the same state class is a no-op. `Preparing` is a deliberate no-op (recorder warm-up window). `onAmplitude` / `onTimerTick` side-channels forward to the animator without going through `DictateUiState` (per-tick state allocations would inflate StateFlow). `reset()` re-arms first-apply on detach. |

**Production files (modified):**

| File | Change |
|------|--------|
| `LayoutCatalog.kt` | Added `sceneStateId = R.id.<state>` to all 5 KEYBOARD layout modes (`KEYBOARD_TWO_ROW`, `KEYBOARD_SINGLE_ROW`, both `*_SEND_MODE` variants, `KEYBOARD_REPROCESS_STAGING`). The scene-id was deferred to C14 in C12; without it the `ImeViewBackend` cannot drive `MotionLayout.transitionToState(...)`. Added `import net.devemperor.dictate.R`. `OVERLAY_5BUTTON` keeps `sceneStateId = null` (overlay surface doesn't drive MotionLayout). |

**Test files (new) — `app/src/test/java/net/devemperor/dictate/state/render/`:**

| File | Tests | Coverage |
|------|-------|----------|
| `RecordingAnimationControllerTest.kt` | 11 | Class-transition idempotency (Idle/Active/Paused/Preparing); `animationsEnabled=false` suppresses start (still allows cancel); `onAmplitude` / `onTimerTick` (MM:SS formatting) / `updateColor` forwarding; `reset()` re-arms first-apply. Hand-rolled `FakeRecordingAnimation` (K-1). |
| `ContentAreaControllerTest.kt` | 5 | All three `ContentArea` values map to exactly one visible container; `backendType == null`; detach is a no-op against future renders. Robolectric (K-4 — `View.visibility` mutation requires real Android view classes). |
| `PromptVisibilityControllerTest.kt` | 11 | Full truth-table coverage (smallMode / EMOJI_PICKER / active / preparing / running / staging / rewordingEnabled); pipelineProgress swap-in vs ReprocessStaging recycler; QWERTZ recording controls; nullable-view tolerance. Robolectric. |
| `OverlayResetHandlerTest.kt` | 4 | `backendType == null`; reset forces GONE; null-view no-op; idempotency. Robolectric. |
| `ImeViewBackendTest.kt` | 14 | First-render `jumpToState` (R.14); subsequent `transitionToState`; `animationsEnabled=false` forces jump every tick; visibility writes; click → `onAction` via resolver; cross-render single-listener (L8); R.3 null-action silent no-op; detach clears state; detach resets firstRender; mismatched-backend `require`; silent-skip-guard `error`; RecordingAnimationController forwarding; `staticHandlerInstaller` invocation; `onVibrate` per click. Robolectric (`Theme_Dictate` + `MaterialButton`). Hand-rolled `FakeMotionSurface`. |
| `SlotRendererTest.kt` | 7 | Visibility predicate / enabled / alpha writes; text resolver applied on `MaterialButton` only; null text resolver leaves text intact; non-MaterialButton skips icon/text. Robolectric. |

**Total new tests: 52** (RecordingAnimationController 11 + ContentArea 5 + PromptVisibility 11 + OverlayReset 4 + ImeViewBackend 14 + SlotRenderer 7), all green on `./gradlew test`.

**Espresso UI-Tests (Spec 2 §14.2 #1-10) — `app/src/androidTest/java/net/devemperor/dictate/ui/`:**

- `KeyboardLayoutUiTest.kt` — 10 skeleton tests, all `@Ignore`d with `pending:` markers. Test names + bug-symptom anchors locked in for the C15 implementer to un-ignore once `attachBackend(imeViewBackend)` is wired in `DictateInputMethodService.onCreateInputView`. Coverage:
  - UI-1 (§1.1 #1) — toggle Single-Row in Idle, all 8 buttons visible
  - UI-2 — active recording hides resend, shows trash/pause
  - UI-3 — pipeline counter text on record_btn
  - UI-4 (§1.1 #3a — bug-fix verifier) — SEND_MODE + Single-Row keeps record_btn unobstructed
  - UI-5 — ReprocessStaging: pause visible+disabled+alpha 0.4
  - UI-6 — rotation during recording, animation continues
  - UI-7 (§1.1 #2) — toggle Single-Row during recording, Pulse keeps running
  - UI-8 (§1.1 #3b — frame-capture) — resend stays VISIBLE through Two-Row ↔ Single-Row toggle
  - UI-9 (§1.1 #3b) — resend cooldown VISIBLE+enabled=false+alpha 0.4
  - UI-10 (§1.1 #3a + #3b — cross-bug) — no overlap during Active → Pipeline-Preparing transition

#### Plan deviations

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| `RecordingAnimationController` constructor does NOT take `recordButton: MaterialButton` | Spec 2 §11.5 | Spec snippet holds the `recordButton` as a constructor field but only uses it indirectly via `RecordingAnimation.prepare(...)` — the helper never needs the button directly. The controller now takes only `RecordingAnimation` + nullable `PulseLayout` + `animationsEnabled` lambda. | Cleaner SRP — the IME service calls `animation.prepare(recordButton)` once during setup before constructing the controller. The controller's concern is state-to-animation forwarding, not view ownership. | C15 must call `animation.prepare(recordButton)` before passing the controller into `ImeViewBackend`. Otherwise no ripple. | inline-fixed |
| `PulseLayout` parameter is nullable in `RecordingAnimationController` | Spec 2 §11.5 | Spec uses non-nullable `PulseLayout`. The nullable form lets JVM unit tests skip Robolectric for the controller (PulseLayout is a `FrameLayout` subclass — instantiating it requires a real Context). | Test-loose adaptation; production always passes non-null. | None — production passes the real wrapper. | inline-fixed |
| `ImeViewBackend` constructor uses `MotionSurface` interface, not `MotionLayout` directly | Spec 2 §6 | Spec snippet types `motionLayout` as the concrete `MotionLayout` view; introducing the `MotionSurface` interface (with `RealMotionSurface` production impl) keeps the backend JVM-unit-testable via a hand-rolled fake. | Long-term maintainability gain (D7) — abstraction is one extra interface file, but lets us assert on jump-vs-transition selection without Robolectric ceremony. | C15 wraps the `MotionLayout` in `RealMotionSurface` before constructing the backend. One-line change in the service-wiring. | inline-fixed |
| `ImeViewBackend` `staticHandlerInstaller` parameter | Spec 2 §6 / §11.7 | Spec embeds `buildSpaceTouchHandler()` / `buildBackspaceSwipeHandler()` / `buildEnterOverlayHandler()` directly inside the backend. Those handlers depend on IME-side classes (`CursorSwipeTouchHandler`, `BackspaceSwipeHandler`, `EnterOverlayHandler` — all in `core/keyboard/`) plus the `inputConnectionProvider` / `accentColorProvider` injections — keeping them inside the backend would force the backend to depend on the IME-side core. The installer hook lets C15 wire them up from outside without inverting the dependency. | SRP + DIP gain — the backend's slot-driven rendering surface stays independent of the special-touch handler classes. | C15 supplies the installer lambda; legacy handlers stay where they are. | inline-fixed |
| `LayoutCatalog.OVERLAY_5BUTTON.sceneStateId = null` | (no explicit spec) | The overlay surface (Spec 3) does NOT drive MotionLayout — it uses a flat `LinearLayout` inside a `WindowManager` window. `null` matches the `LayoutMode.sceneStateId` KDoc rationale ("no MotionLayout transition to trigger"). | None — overlay backend doesn't reach the IME-side MotionSurface. | inline-fixed |
| `PromptVisibilityController` accepts nullable views | Spec 2 §11.5 mentions container references | Some layout configurations (mini-keyboard variants in Phase 2) may omit the prompt area entirely. Nullable members + skip-write keeps the controller robust. | C15 always passes the real views from `onCreateInputView`. | None. | inline-fixed |
| `LayoutCatalog` `sceneStateId` populated for all 5 KEYBOARD modes | C12 follow-up acknowledged in B4 block-report  | C12 deferred the scene-state-ids to C14 (didn't know `R.id.*` at C12-time). C14 now sets them per Spec 2 §6 — `mode.sceneStateId?.let { motionLayout.transitionToState(it) }`. | None — extends the data already in place. | inline-fixed |

#### Inline-fixed items

| Where | Issue | Fix |
|-------|-------|-----|
| `LayoutCatalog.kt` (modified) | C12 deferred `sceneStateId` on the 5 KEYBOARD layout modes; ImeViewBackend.render needs them to drive MotionLayout transitions. | Added `sceneStateId = R.id.two_row_state` / `single_row_state` / `two_row_send_mode_state` / `single_row_send_mode_state` / `reprocess_staging_state` on the matching `LayoutMode` literals. `OVERLAY_5BUTTON` keeps `sceneStateId = null`. |
| `ImeViewBackend.kt` | Spec 2 §6 prescribes a single MotionLayout-typed field, but the backend's hot path is exactly two methods (`jumpToState` / `transitionToState`). Direct `MotionLayout` typing would force Robolectric on every backend unit test. | Introduced `MotionSurface` interface + `RealMotionSurface` production wrapper. JVM unit tests pass a `FakeMotionSurface` and assert on the recorded transitions. |
| `RecordingAnimationController.kt` | Spec 2 §11.5 carries a `lastRecordingState: RecordingState?` cache but compares via `prev::class == curr::class`. Caching the whole state when only the sealed branch matters is wasteful (especially for `Active(useBluetooth, audioFile)` — different `audioFile` would falsely flag "state changed"). | Caches `Class<out RecordingState>?` directly. Same idempotency semantics, leaner allocation. |

#### Issues

| ID | Severity | Description | Status | Reason |
|----|----------|-------------|--------|--------|
| IMPL-1 | NTH | UI-Tests 1-10 are `@Ignore`d skeletons — they cannot run until C15 wires the backend into `DictateInputMethodService.onCreateInputView`. | postponed | Acceptable — Spec 2 §14.2 explicitly schedules these for the cross-cutting phase (Block 5/4 / C15). Skeletons document the bug-symptom anchors so the un-ignore step is mechanical. |
| IMPL-2 (C12 carry-over) | NTH | `resolveRecordButtonTextPipeline` still ignores `completedSteps`, `totalSteps`, `elapsedMs` — the `PipelineUiState.Running` data class doesn't carry those fields. | open | Out of C14 scope (modifying `PipelineUiState` would re-open B2 state-shape). C15 or B5/B6 follow-up after the pipeline-state extension lands. |
| IMPL-3 (C12 carry-over) | NTH | `KEYBOARD_REPROCESS_STAGING.enabledResolver` still falls back to "any non-null staging" instead of reading `s.isStarting`. | open | Same reason as IMPL-2 — the field isn't on `PipelineUiState.ReprocessStaging` yet. Follow-up. |

#### Overlooked points / known gaps

- **Production wiring not done.** No `DictateInputMethodService.onCreateInputView` change in C14 (per chunk scope — Spec 2 §11.8 says wiring lives in 5c / C15). Legacy `KeyboardUiController` + `KeyboardStateManager` still drive the IME. Verified: `./gradlew assembleDebug` BUILD SUCCESSFUL.
- **PulseLayout-Spike (§11.3) and Inflation-Cost-Spike (§11.4) not run.** Both need a connected device or Robolectric `LayoutInflater` with a real `MotionLayout` — out-of-scope for the implementer-agent that can't run instrumented tests. Block-validate / E2E (Phase 4.5) covers these.
- **Espresso UI-Tests 1-10 not executable by the agent.** Per state-file B4 Test-Strategy + Spec 2 §14.2 — these require a connected device or emulator (`connectedAndroidTest`). The skeletons compile (`./gradlew compileDebugAndroidTestKotlin` passes) and document the contract. C15 will un-`@Ignore` and run them against a real device.
- **`MotionLayoutSurface` could replace `MotionSurface` once `OverlayBackend` lands** — Spec 3's overlay surface doesn't use MotionLayout at all, so the abstraction stays local to `ImeViewBackend`. No premature generalisation.
- **Long-press for RECORD currently consumes the event without dispatching an action.** Spec 2 §6 keeps this behaviour ("RECORD has a vibrate-only marker") — the legacy IME doesn't emit a record-long-press action either. Phase 2 may add one.

#### Test-Infrastructure implemented

None — all new test files reuse:
- `testLayoutStrings()` from `LayoutCatalogTest.kt` (internal helper)
- `fakeModuleServices()` from `testutil/FakeModuleServices.kt`
- Robolectric (already on the build classpath)

The only new test fixture is **`FakeRecordingAnimation`** (inside `RecordingAnimationControllerTest.kt`, internal) — a hand-rolled K-1 fake recording every `RecordingAnimation` method invocation. Re-used by `ImeViewBackendTest` to assert the controller forwarding.

#### Build / test results

- `./gradlew assembleDebug` — **BUILD SUCCESSFUL** (5s; 37 tasks).
- `./gradlew test` — **BUILD SUCCESSFUL** (1m 18s; 833 tests, 0 failures, 0 errors). Render-package alone: **52 new tests, all green** (0.5s + 16s Robolectric).
- `./gradlew compileDebugAndroidTestKotlin` — **BUILD SUCCESSFUL** (4s) — Espresso skeletons compile.

---

### Chunk C15-block5-service-wiring-and-cleanup — Service wiring + Cleanup (destructive)

**Agent-IDs:** Steps 1-5: `B4-C15-IMPL-FULL`

**Status:** ⏳ pending (depends on C14)

⏳

---

## Block-Validate (Phase 3.2)

**Status:** ⏳ pending (run after all 4 chunks)
**Pre-Validate Commit:** ⏳
**Validate-Pass Commit:** ⏳

### Audit-Topic Outputs

| Topic | Agent-ID | Status | Output File | Findings (counts) |
|-------|----------|--------|-------------|-------------------|
| plan-and-api | `B4-AUDIT-PLAN-AND-API` | ⏳ | `./reports/audit-plan-and-api-B4.md` | — |
| convention | `B4-AUDIT-CONVENTION` | ⏳ | `./reports/audit-convention-B4.md` | — |
| logic | `B4-AUDIT-LOGIC` | ⏳ | `./reports/audit-logic-B4.md` | — |
| test | `B4-AUDIT-TEST` | ⏳ | `./reports/audit-test-B4.md` | — |

### Sanity-Check Consolidator

**Agent-ID:** `B4-VAL-SANITY`
**Output file:** `./reports/validated-findings-B4.md`

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
- **Cross-block-API consumer info forwarded to Block 5:** ⏳ (B5 Overlay consumes LayoutCatalog.OVERLAY_5BUTTON + RenderBackend + KeyboardLayoutManager from B4)

**Block completed at:** ⏳
**Block-End-Commit:** ⏳
