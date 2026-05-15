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

**Status:** ⏳ pending (depends on C12)

⏳

---

### Chunk C14-block5-ime-view-backend — ImeViewBackend + Controllers + Animation

**Agent-IDs:** Steps 1-5: `B4-C14-IMPL-FULL`

**Status:** ⏳ pending (depends on C13)

⏳

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
