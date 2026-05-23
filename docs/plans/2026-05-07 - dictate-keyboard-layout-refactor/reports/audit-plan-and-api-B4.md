# Audit Report: plan-and-api (Block 4, scope: full-block)

**Agent-ID:** B4-AUDIT-PLAN-AND-API
**Date:** 2026-05-15T08:32:28+02:00
**Knowledge skills used:** none (Java/Kotlin-Android-Codebase; no `knowledge-typescript` / `knowledge-reference` patterns load-bearing here — but the **project ADR-0004** + **Spec 2 §3-§14** were the canonical references read end-to-end)
**Files inspected:** 20

Inspected production files:

- `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/LayoutMode.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/LayoutModeId.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/LogicalButtonId.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/ButtonSlot.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/KeyboardLayoutManager.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/RenderBackend.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/Predicates.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/TextResolvers.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/ImeViewBackend.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/RecordingAnimationController.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/ContentAreaController.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/PromptVisibilityController.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/OverlayResetHandler.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/MotionSurface.kt`
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt` (only the C15 wiring slice — Step 7 + onDestroy + LocalBinder)
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` (only `attachImeViewBackendIfReady` + cleanup paths)
- `app/src/main/java/net/devemperor/dictate/core/KeyboardStateManager.kt` (only the C15-diff region)
- `app/src/main/res/xml/motion_scene_keyboard.xml`
- `app/src/main/res/layout/activity_dictate_keyboard_view.xml` (only the `main_buttons_cl` region)

Inspected test files:

- `app/src/test/java/net/devemperor/dictate/state/layout/LayoutCatalogTest.kt`
- `app/src/test/java/net/devemperor/dictate/state/layout/VisibilityMatrixTest.kt`
- `app/src/test/java/net/devemperor/dictate/state/layout/KeyboardLayoutManagerTest.kt`
- `app/src/test/java/net/devemperor/dictate/state/layout/MotionSceneSchemaTest.kt`
- `app/src/test/java/net/devemperor/dictate/state/render/ImeViewBackendTest.kt`
- `app/src/androidTest/java/net/devemperor/dictate/ui/KeyboardLayoutUiTest.kt`

## Summary

- Critical: 0
- Important: 6
- Nice-to-have: 4

The block's plan-treue is **substantively complete** for C12 / C13 / C14 / C15:

- All 6 LayoutMode constants are present (5 KEYBOARD + 1 OVERLAY placeholder) with correct backend type and the required slot sets.
- WIDGET_TOGGLE is wired into all 5 KEYBOARD modes with the spec-prescribed predicates.
- MotionScene XML has 5 ConstraintSets + 5 Transitions and every state-driven button carries `visibilityMode="ignore"`.
- ImeViewBackend implements RenderBackend correctly with the L8 click-listener-once invariant, MotionSurface abstraction, and Silent-Skip-Guard `error(...)`.
- C15 wiring + destructive cleanup (`KeyboardLayoutModeController.kt` deleted, scaffold stubs removed) landed.
- D-13 (LanguageController) re-deferral is documented with a concrete suggested follow-up block ("B7"); 49 caller-graph references confirm the deferral scope.

All findings below are **Important** or **Nice-to-have** — none block B5 or the bug-symptom test contract.

## Findings

### AUDIT-PLAN-AND-API-B4-1

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt:72`
- **Description:** `resolveRecordAction` calls `services.toastSink.showError("Storage full — recording failed to start")` — a hardcoded English literal in production code. Spec 2 §8.5 KDoc and Spec 3 §3.1 `resolveOverlayRecordAction` both prescribe `services.toastSink.show(R.string.dictate_storage_full)`. The string resource already exists (`app/src/main/res/values/strings.xml:415`: `"Cache full — recording cannot start."`). The hardcoded form (a) bypasses i18n, (b) drifts from Spec 3 §3.1 verbatim wording ("Cache full" vs "Storage full"), and (c) is `showError` instead of `show` — the `ToastSink` API offers both.
- **Why it matters:** The IOException side-channel is one of the few user-visible error paths the resolver layer owns. Diverging both the wording and the i18n model from the spec creates a future drift between Spec 2's record-button-Idle path and Spec 3's overlay-record path (`resolveOverlayRecordAction`), even though they should share the **same** toast under Spec 2 §8.5 F-4 SSoT thinking.
- **Suggested fix scope:** small (one-line — switch to the `R.string.dictate_storage_full` resource ID; align `showError` vs `show` with Spec 2 §8.5 wording).
- **Suggested fix:** needs research on `ToastSink` API surface — pick `show(R.string.dictate_storage_full)` if that overload exists; otherwise `showError(ctx.getString(R.string.dictate_storage_full))`.

### AUDIT-PLAN-AND-API-B4-2

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt:82-83` (and Spec 2 §8.5 KDoc reference at `ActionResolvers.kt:55-61`)
- **Description:** `Action.RecordingAction.StopRecordingAndSend(sessionId = "")` uses an empty-string sentinel where the production cascade later overrides it. Spec 2 §8.5 anticipates this ("sessionId is generated by the recording-→-pipeline cross-module cascade — Spec 1 §15.2 F-2"); the C12 deviation table records it as "inline-fixed, C14/C15 will revisit when the click pipe is wired". C14/C15 did NOT revisit — the empty-string is still in HEAD, and the cascade is "expected to override". This is an API-contract leak: a sessionId that the receiver "overrides" instead of "uses" is a constructor-argument that should not exist (or should be `String? = null`). Risk: a future caller that sees `StopRecordingAndSend("")` and assumes the empty string is meaningful (e.g. logging, persistence) silently writes empty rows.
- **Why it matters:** R.3 says resolvers return `null` when the click is structurally meaningless; here the click is meaningful but the sessionId field is structurally absent. A nullable `sessionId: String?` plus a Spec 1 §15.2 reducer that **generates** the id if `null` is the spec-clean form.
- **Suggested fix scope:** medium (touches `Action.RecordingAction.StopRecordingAndSend` data-class — Spec 1 §3.3 owner — plus the `RecordingModule.reduce` arm + a follow-up on every other call-site).
- **Suggested fix:** flag as plan-deviation against Spec 1 §3.3 / Spec 2 §8.5; route via repair-sub-phase to either align Spec 2 §8.5 ("empty string is the documented sentinel") OR change the data-class to `sessionId: String? = null`. Pick the spec-side that wins; the implementation must match the chosen direction.

### AUDIT-PLAN-AND-API-B4-3

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:386-391` (REPROCESS_STAGING RECORD slot)
- **Description:** The C12 deviation table flags `enabledResolver` falling back to "any non-null staging" because `PipelineUiState.ReprocessStaging` doesn't yet carry `isStarting`. C14 was named as the follow-up; C15 also did NOT add the field. The KDoc at `LayoutCatalog.kt:387-389` still references the deferral. Spec 2 §8.4 explicitly states the `enabledResolver` reads `s != null && !s.isStarting`. The current implementation `state.pipeline is PipelineUiState.ReprocessStaging` is therefore a **functional drift**: during the staging-loading window (between SendStaging-action and the actual response), the button stays enabled and a re-click is racy.
- **Why it matters:** Spec 2 §8.4 was specifically iterated to prevent the SendStaging double-click race. The deferral is acknowledged in C12 + C14 deviations but the **production code now ships without `isStarting`** — a runtime regression vector. Tracked as C14 IMPL-3 (NTH) — severity should escalate per plan compliance.
- **Suggested fix scope:** medium (adds `isStarting: Boolean = false` to `PipelineUiState.ReprocessStaging` in Spec 1 §3; PipelineModule must transition `isStarting` true ↔ false around the SendStaging effect; resolver folds it in).
- **Suggested fix:** flag as plan-deviation against Spec 2 §8.4 → route via repair-sub-phase. Either implement the field (B5/B6 scope) or escalate the C14 IMPL-3 issue to Important so it doesn't slip under NTH triage.

### AUDIT-PLAN-AND-API-B4-4

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/TextResolvers.kt:103-104` + `LayoutCatalog.kt:238, 320` (RECORD textResolver for SEND_MODE)
- **Description:** Spec 2 §8.5 `resolveRecordButtonTextPipeline` reads `pipe.completedSteps`, `pipe.totalSteps`, `pipe.elapsedMs` from `PipelineUiState.Running`. The current implementation always passes `(0, 0, autoEnterActive, 0L)`. C12 deviation table flagged this as a follow-up to C14; C14 IMPL-2 carries it forward. C15 still doesn't address it. Result: in production today, the user sees `"0/0 ↵ 0:00"` (or whatever `formatPipelineLabel(0, 0, …)` renders) on the record-button during pipeline-running — the counter is dead. The legacy `KeyboardUiController.refreshRecordButtonFromState` is the one actually rendering the live counter, so the user-visible behaviour stays correct **because the legacy path wins on every refresh** (C15 IMPL-2 deviation notes this). But the new render path is broken-by-design and will surface the moment the legacy path is removed in a follow-up block.
- **Why it matters:** This is a Spec 2 §10 Block-5 acceptance violation ("Send-Button mit Counter sichtbar"). The bug is currently *masked* by parallel render paths (deviation acknowledged in C15) but ships as a latent regression — the moment the legacy path is removed, the counter dies. Per D7 long-term maintainability, the right resolution is to extend `PipelineUiState.Running` with the three fields (or read them from a side-channel) **before** B5/B6 removes the legacy path.
- **Suggested fix scope:** medium (Spec 1 §3 state-shape extension + Pipeline module reducer + LayoutStrings call-site).
- **Suggested fix:** delegate to orchestrator; route via repair-sub-phase for a Spec 1 §3 evolution OR a side-channel that the resolver can read. Avoid letting B5/B6 land with the broken-by-design path active.

### AUDIT-PLAN-AND-API-B4-5

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/RecordingAnimationController.kt:62, 102` (`lastRecordingClass: Class<out RecordingState>?`)
- **Description:** Implementation caches `Class<out RecordingState>?` instead of the spec-prescribed `RecordingState?` value-cache. The C14 deviation table justifies it ("Caches `Class<out RecordingState>?` directly. Same idempotency semantics, leaner allocation."). The behavioural change vs. spec is real: `prev::class == curr::class` returns **true** when `RecordingState.Active(useBluetooth=false, ...)` is replaced by `RecordingState.Active(useBluetooth=true, ...)` — same class. The spec's `prev::class == curr::class` snippet does the **same comparison** (both use `::class`), so they are semantically identical, but the field's type name lies about what's being compared. The KDoc rationale ("different `audioFile` would falsely flag state changed") is incorrect — `::class` already ignores constructor-args, so caching `RecordingState?` and comparing via `::class` would behave identically. The class-only cache is essentially a private optimisation that the spec doesn't anticipate.
- **Why it matters:** Drift in fact-vs-spec wording is a future-readability cost. A reader of the catalog who's been told "cache the state class" looks for `RecordingState?` (or compares with `equals`); the type chooses the comparator implicitly. The C14 deviation rationale also overstates the difference — it's stylistic, not semantic.
- **Suggested fix scope:** small (rewrite the C14 deviation rationale OR rename `lastRecordingClass` to `lastRecordingClassRef` + drop the misleading rationale).
- **Suggested fix:** clarify the deviation's wording, OR cache `RecordingState?` and compare with `prev?.let { p -> p::class == curr::class }` — matches Spec 2 §11.5 snippet verbatim.

### AUDIT-PLAN-AND-API-B4-6

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:57, 465-472` (LayoutCatalog is `class`, not `object`; `OVERLAY_5BUTTON` is a `val by lazy`)
- **Description:** Spec 3 §3.1 (post-Phase-C C-5) is unambiguous: `OVERLAY_5BUTTON` is declared as **`object OVERLAY_5BUTTON : LayoutMode(...)` nested inside `object LayoutCatalog`** — a Kotlin nested object inside an outer object. The C12 implementation flips both: `class LayoutCatalog(strings: LayoutStrings)` (constructor-injection for `LayoutStrings`) + `val OVERLAY_5BUTTON: LayoutMode by lazy { LayoutMode(...) }`. C12 deviation acknowledges the form difference and B5 is named as the body-supplier. The structural concern remains: the spec's idiom (nested `object`) is **impossible** when the outer is a `class` — instances can't host nested objects with that shape. B5 will have to land Spec 3 §3.1's body inside the C12 `by lazy` property, which **does** type-check, but the spec's `object OVERLAY_5BUTTON : LayoutMode(...)` keyword has to stay decorative in Spec 3 (or get re-written).
- **Why it matters:** Forward-compat for B5/Overlay is fine in practice — `LayoutMode` is a data-class and `LayoutCatalog.OVERLAY_5BUTTON` resolves regardless of nested-object vs lazy-val. But the **B5 implementer will hit Spec 3 §3.1's wording head-on** and need to translate "nested object" to "lazy property". Without an explicit cross-spec deviation entry, this is a paper-trail trap: a careless B5 will copy the spec verbatim and get a compile-error.
- **Suggested fix scope:** small (add a Spec 3 §3.1 fix-note OR a C12 deviation cross-reference saying "B5: this property is a `val by lazy`, NOT a nested `object` — assign `LayoutMode(...)` directly").
- **Suggested fix:** add cross-spec deviation note in the C15 deviations table flagging the Spec 3 §3.1 wording difference + a forward-pointer to B5.

### AUDIT-PLAN-AND-API-B4-7

- **Severity:** Important
- **File:** `app/src/main/res/xml/motion_scene_keyboard.xml:84, 146, 152-154, 273-284` (`record_btn` + `widget_toggle_btn` deviation from Spec 2 §7.1)
- **Description:** Two material additions to the scene XML are not in Spec 2 §7.1 — C13 deviation table partially documents them:
  1. A `<Constraint android:id="@+id/record_btn"><PropertySet motion:visibilityMode="ignore" /></Constraint>` block (lines 152-154) — Spec 2 §7.1 + §7.3 list only the 9 outer buttons (record_btn lives inside PulseLayout, not as a direct child). C13 deviation notes "PropertySet entries it cannot bind … kept harmless".
  2. `widget_toggle_btn` Constraints in both `two_row_state` (lines 76-84) and `single_row_state` (lines 277-284). C13 deviation acknowledges this. Spec 2 §3.1 says "Position TBD (Vorschlag: neben AUDIO_FOCUS im action_row)" — the chosen position (between `backspace_btn` and `audio_focus_btn` in Two-Row) is one valid placement, but the spec does NOT prescribe it.
  
  The two together mean the scene has **10 Constraints in Two-Row** (9 + record_btn) and **9 Constraints in Single-Row** (8 visible + widget_toggle parked at parent-end). Spec 2 §7.1 acceptance ("each of the 9 buttons in the 4 scene-XMLs has visibilityMode=ignore") still holds, just with one extra entry.
- **Why it matters:** The `record_btn` Constraint silently no-ops because record_btn is not a direct MotionLayout child (PulseLayout wraps it) — confirmed by MotionLayout doc. But: if a future PulseLayout refactor flattens the hierarchy, this Constraint suddenly becomes load-bearing and may collide with the PulseLayout's own positioning. Documenting it in the C13 deviation table (already done) is correct; a Spec 2 §7.1 spec-edit would make this canonical.
- **Suggested fix scope:** small (spec-edit OR follow-up note in Spec 2 §7.1 + §7.3).
- **Suggested fix:** Phase 4.6 doc-pass to extend Spec 2 §7.1 + §13.5 with `widget_toggle_btn` and `record_btn`-PropertySet — explicit deviation row.

### AUDIT-PLAN-AND-API-B4-8

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:497-528` (`buildLayoutStrings`)
- **Description:** `LayoutStrings.dictateButtonText` returns the static `R.string.dictate_record` (`getString(R.string.dictate_record)`). The legacy `getDictateButtonText()` in `DictateInputMethodService.java` is language-aware (reads `LanguageController.getEffectiveLanguage()` per Phase 2 user feature). C15 IMPL-2 acknowledges this is a "baseline; visible only when new path renders alone (currently superseded by legacy MainButtonsController.updateRecordButtonText)". Until D-13 closes, the new path's record-button label is wrong-by-design — but masked because both paths run.
- **Why it matters:** Same reason as PLAN-AND-API-B4-4 — the latent regression surfaces the moment the legacy path is removed. The fix is plumbed by Spec 1 §15.x `LanguageModule.effectiveLanguage` exposure to LayoutStrings.
- **Suggested fix scope:** medium (D-13 follow-up block scope).
- **Suggested fix:** explicit "B7 LanguageController + Settings-UI decoupling" block (already named in the C15 deviations table) — when that lands, wire `LanguageModule.effectiveLanguage` into `LayoutStrings.dictateButtonText`.

### AUDIT-PLAN-AND-API-B4-9

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/ContentAreaController.kt:84` / `PromptVisibilityController.kt:81` / `OverlayResetHandler.kt:69-70` (`@Suppress("UNUSED_VARIABLE") val _unused = mode`)
- **Description:** Three of the four "consume-every-mode" backends discard their `mode` parameter via the `@Suppress("UNUSED_VARIABLE")` + `_unused` shadowing idiom. The intent ("mode is orthogonal to this backend") is clear, but the idiom is the only way to silence the unused-warning under the current Kotlin Lint config and clutters the render path. A small Lint suppression or a more elegant unused-parameter idiom (e.g. `@Suppress("UNUSED_PARAMETER")` at the function level) would be DRYer.
- **Why it matters:** Three backends repeat the same boilerplate. If a future backend joins the consume-every-mode set, this pattern will spread further. The choice is documented in each class KDoc, but the warning-suppression dance reads as accidental rather than principled.
- **Suggested fix scope:** small (consolidate suppression at the function level OR introduce a parent abstract class `ContentLevelRenderBackend` that consumes-every-mode and pre-filters before render).
- **Suggested fix:** Phase 4.7 cleanup pass — pick a single idiom and apply across the three backends; document the choice in the RenderBackend KDoc.

### AUDIT-PLAN-AND-API-B4-10

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:63-471` (`val ... by lazy { ... }`)
- **Description:** All 6 mode-properties use `by lazy { ... }` instead of plain `val =` initialisation. The C12 deviation table doesn't explain the choice. Plausible motivation: avoid eager evaluation when only a subset is touched in tests. But: `LayoutCatalog`'s constructor takes `strings: LayoutStrings` (non-trivial dependency), and the lazy init **captures** `strings` — meaning a single catalog instance shared across multiple tests is correct, but the laziness adds a synchronization edge-case (thread-safe by default but with a tiny per-access cost). Spec 2 §8.6 prescribes `object LayoutCatalog { val KEYBOARD_TWO_ROW = LayoutMode(...) }` — eager init in a singleton.
- **Why it matters:** Performance-wise irrelevant. Architecturally, the lazy idiom hides the construction-cost question — `LayoutMode` with a row-of-9-slots is dirt-cheap to construct, so eager init is fine. The lazy form is over-engineered for a constructed-once data registry.
- **Suggested fix scope:** small (rename `by lazy { LayoutMode(...) }` to `= LayoutMode(...)`; verify VisibilityMatrixTest + LayoutCatalogTest still pass).
- **Suggested fix:** Phase 4.7 cleanup pass.

## Out-of-scope observations

These were noticed during the audit but belong to other audit topics:

- **CONVENTION:** The `_unused1` / `_unused2` naming inconsistency between OverlayResetHandler (numbered) and ContentAreaController / PromptVisibilityController (`_unused` without number) → AUDIT-CONVENTION.
- **CONVENTION:** Catalog mode property naming UPPER_SNAKE_CASE inside a `class` violates the Kotlin idiom (LayoutCatalog deviation table acknowledges this; the choice is anchored on cross-spec references). Worth a Kotlin-style ADR cross-check by AUDIT-CONVENTION.
- **LOGIC:** `ImeViewBackend.detach` resets `firstRender = true` but **does not clear click-listeners**. The KDoc says listeners stay wired but short-circuit because `onAction == null` + `stateRef == null`. If `attach` is called twice without an intervening `detach` (which `KeyboardLayoutManager.attachBackend` explicitly forbids via `check(!in activeBackends)`), the second `wireStaticHandlers` REPLACES every click-listener — that's fine, but worth a one-line LOGIC test that demonstrates the replacement semantic. → AUDIT-LOGIC.
- **LOGIC:** `KeyboardLayoutManager.detachBackend` is no-op for unknown backends, but `attachBackend` raises `IllegalStateException` for double-attach. Asymmetric defensive semantics — intentional (per KDoc) but worth a LOGIC double-check. → AUDIT-LOGIC.
- **TEST:** No test verifies `OVERLAY_5BUTTON.rows.isEmpty()` — the placeholder body. Adding one would tomorrow-proof B5 against accidentally replacing the property with a wrong empty-rows literal. → AUDIT-TEST.
- **TEST:** Espresso UI-Tests 1-10 are all `@Ignore("pending: ...")` with consistent reason strings — good. The audit-test agent should verify the `pending:` greppability per `~/.claude/snippets/test-first-patterns.md`.

## Coverage

- Files audited: see "Files inspected" list above (20 production + 6 test).
- Files skipped (with reason): all non-B4-diff files (`RecordingUiController.kt`, `MainButtonsController.kt`, etc.) — they remain in HEAD per C15 deviation table ("Kept as compile-bridges") but their content is not new in B4 and falls under B5/B6/B7 scope.
- Knowledge-skill checkpoints applied: ADR-0004 §3 (MotionLayout + LayoutCatalog + visibilityMode="ignore" mandatory) — verified against motion_scene_keyboard.xml. Spec 1 §3 (hierarchical state) — verified the resolvers read `state.resend.resendCooldown`, `state.layout.singleRowMode`, etc. instead of flat paths.

## Bug-symptom resolution check (plan §1.1)

Audited per the prompt's section 5:

| Symptom | Status | Evidence |
|---------|--------|----------|
| #1 (asymmetric re-parenting in Single-Row toggle) | **structurally eliminated** | MotionScene XML has flat 9-button hierarchy with `deriveConstraintsFrom`. No re-parent anywhere. `KeyboardLayoutModeController.kt` deleted. Verified UI-Test 1 + UI-Test 7 skeletons (`@Ignore`d, but anchored on these symptoms). |
| #2 (asymmetric re-parenting revert) | **structurally eliminated** | Same — L2 flat hierarchy. PulseLayout `record_pulse_layout` stays as the lone wrapper (L7). |
| #3a (Send-mode + Single-Row overlap) | **structurally eliminated** | KEYBOARD_TWO_ROW_SEND_MODE + KEYBOARD_SINGLE_ROW_SEND_MODE hardcode TRASH + PAUSE to `{ false }`. `applyRecordButtonForRecording` resolver chain (B1) + `resolveRecordButtonTextPipeline` (C12) replace the legacy hybrid. UI-Test 4 anchored on this symptom. |
| #3b (resend disappears mid-toggle / cooldown) | **structurally eliminated** | `isResendVisible` deliberately does NOT read `resendCooldown` (verified by `LayoutPredicatesTest.isResendVisible does NOT read resendCooldown (forbidden pattern j)`). Cooldown lives in `enabledResolver` + `alphaResolver`. UI-Test 8 + UI-Test 9 anchored on this symptom. |

The 25-case `VisibilityMatrixTest` (5 modes × 5 typical states) covers the bug-symptom truth tables. Per the prompt's "Symptom #3b — verify via VisibilityMatrixTest 25 cases" — yes, the suite has exactly 25 parameterised cases.

## Espresso UI-Tests 1-10 (Spec 2 §14.2)

All 10 skeleton tests exist at `app/src/androidTest/java/net/devemperor/dictate/ui/KeyboardLayoutUiTest.kt`. Each `@Ignore`d with the consistent reason `"pending: C15-wiring landed — body still skeleton; un-ignore + implement assertions"` (with two carrying additional deferral context: UI-3 mentions the `PipelineUiState.Running` counter shape, UI-6 mentions ActivityScenario rotation harness, UI-8 mentions per-frame IdlingResource, UI-10 mentions per-frame layout-check).

Each test KDoc anchors a bug-symptom or coverage-baseline — verified mapping per the prompt's section 6. **Skeleton bodies match the spec contract**: test names align with Spec 2 §14.2 wording, and the un-ignore step is "mechanical" per C15 deviations.

## D-13 LanguageController re-deferral

The C15 deviation table (`B4-keyboard-layout-catalog.md` IMPL-1) re-defers D-13 with explicit caller-graph scope:
- `DictateApplication.getOrCreateLanguageController()` (Settings-activity Application-singleton)
- `PreferencesFragment.java` (Settings UI)
- `InputLanguagesLegacyMigration.kt`
- `VersionedPrefs.kt` (migration-version comment)
- `KeyboardUiController.kt`
- ~19 IME-side call-sites in `DictateInputMethodService.java`

The follow-up block is named ("B7 LanguageController + Settings-UI decoupling"). Verified by `grep -rc "LanguageController" app/src/main/java/net/devemperor` → 49 references, confirming the caller-graph scope.

**Verdict:** D-13 re-deferral is **properly documented** with scope-pointer + follow-up name. No finding raised.

## Forward-compatibility for B5/Overlay

Per the prompt's section 4:

1. **LayoutCatalog.OVERLAY_5BUTTON nested-property pattern** — `val OVERLAY_5BUTTON: LayoutMode by lazy { ... }` exists; B5 just replaces the empty `rows = emptyList()` body. ⚠️ Spec 3 §3.1's `object OVERLAY_5BUTTON : LayoutMode(...)` wording will need translation — see AUDIT-PLAN-AND-API-B4-6.
2. **RenderBackend.backendType enum** — `BackendType.IME_VIEW` + `BackendType.OVERLAY_WINDOW` present + `null` for cross-cutting backends (ContentAreaController / PromptVisibilityController / OverlayResetHandler use `null`). B5's `OverlayBackend` can return `BackendType.OVERLAY_WINDOW` and be attached via `attachBackend()`. ✓
3. **LayoutCatalog filterable by backendType** — `LayoutCatalog.allModes()` is iterable; `LayoutMode.backend` is the discriminator. The `KeyboardLayoutManager.renderTo` (`KeyboardLayoutManager.kt:144-152`) filters by `backend.backendType == null || backend.backendType == mode.backend`. ✓

**Verdict:** B5 has a clean forward-compat surface. The one wording-friction-point (AUDIT-PLAN-AND-API-B4-6) is captured as Important.
