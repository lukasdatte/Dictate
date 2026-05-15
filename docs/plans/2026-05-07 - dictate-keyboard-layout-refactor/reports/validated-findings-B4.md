# Validated Findings — Block 4

**Agent-ID:** B4-VAL-SANITY
**Date:** 2026-05-15
**Source audits:**

- `./reports/audit-plan-and-api-B4.md` — 10 findings (0 C / 6 Imp / 4 NTH)
- `./reports/audit-convention-B4.md` — 10 findings (0 C / 4 Imp / 6 NTH)
- `./reports/audit-logic-B4.md` — 15 findings (4 C / 6 Imp / 5 NTH)
- `./reports/audit-test-B4.md` — 8 findings (0 C / 2 Imp / 6 NTH)

Total raw findings: 43. After de-duplication: 33 distinct findings.

## Summary

- 🟢 valid + auto-fixable: **30** (Critical: 4, Important: 13, Nice: 13)
- 🟡 valid + research-needed: **3** (Critical: 0, Important: 3, Nice: 0)
- ❌ eliminated: **0**

**Repair-wave estimate:** **1 wave** — all 4 Criticals are 🟢 with mechanical fixes (option (c) interim chosen for F-2). Three Important plan-evolution items are 🟡 but they describe a downstream block (B5/B7) deliverable; no fix is required in this wave, only a planning artefact.

## Cross-cut patterns

1. **Dual-render-path latency cluster (4 findings, 2 of them Critical).** F-1 (BACKSPACE long-press), F-2 (RECORD long-press), F-3 (AUDIO_FOCUS icon inversion), and F-7 (button overlap in single_row_state) all describe user-visible behaviour that the C15 "two render paths run in parallel" deviation either masked or broke. The dual-render-path needs to be acknowledged in a single Phase 4 issue: any further block that flips authority between legacy and new path silently re-surfaces these.
2. **Plan-evolution debt — three pipeline-field placeholders (3 Important, 🟡 each).** F-12 (`ReprocessStaging.isStarting` field), F-13 (`Running.completedSteps/totalSteps/elapsedMs` fields), F-15 (`LayoutStrings` language-awareness) all need a Spec 1 §3 state-shape extension before B5/B6/B7 can remove the legacy bridge. Best handled as a single planned B5-pre block-edit.
3. **Dead controllers (1 bundle — was 2 findings).** ContentAreaController, PromptVisibilityController, OverlayResetHandler are defined + 20 tests, never wired. Mitigation: KDoc clarifying "wiring-state = pending" anchors + a `// TODO(D-13 follow-up)` marker on the legacy KSM methods. The actual wiring is B5/B6/B7 scope.
4. **Inconsistent unused-parameter idioms (1 finding, NTH).** Three controllers use `@Suppress("UNUSED_VARIABLE") val _unused = mode`; ActionResolvers uses `@Suppress("UNUSED_PARAMETER") services` statement-form. Standardise on annotation-on-parameter form.
5. **String.format Locale omission (1 finding, Important).** `DictatePipelineService.buildLayoutStrings` does not specify Locale at three call sites. Other new B4 code (RecordingAnimationController.kt) gets it right with `Locale.US`. Mechanical fix.
6. **MotionScene XML — overlap + missing edges + stale comments (3 findings).** F-7 (Critical overlap), F-25 (missing transition edges, NTH), F-26 (record_pulse_layout missing visibilityMode, NTH).
7. **Stale documentation pointers (1 finding, NTH).** `action_row` / `input_row` names referenced in 8+ comments though containers were deleted in C15.
8. **Espresso UI-Test bodies empty (1 Important).** All 10 `KeyboardLayoutUiTest` tests are `@Ignore`d with body = comment-only. Risk: un-ignore without body lands green. Mitigation: add `fail("pending: ...")`.

## Domain bundles for repair-routing

| Bundle | Files | Findings |
|--------|-------|----------|
| **ImeViewBackend long-press semantics** | `state/render/ImeViewBackend.kt` (lines ~248-257) | F-1, F-2, F-22 (vibrate consistency), F-21 (firstRender placement) |
| **IconResolvers semantics** | `state/layout/IconResolvers.kt` (resolveAudioFocusIcon) + IconResolversTest + SlotRendererTest | F-3 |
| **MotionScene XML layout** | `app/src/main/res/xml/motion_scene_keyboard.xml` (single_row_state + transitions) | F-7, F-25, F-26, F-29 |
| **LayoutCatalog + Predicates housekeeping** | `state/layout/LayoutCatalog.kt`, `Predicates.kt` (incl. file rename) | F-4 (storage-full literal — partially here), F-10 (sessionId sentinel), F-16 (lazy→eager + nested-object wording), F-18 (predicate redundancy), F-23 (FQ-type drift), F-24 (unreachable else), F-19 (Predicates.kt → LayoutPredicates.kt rename), F-30 (stale comments) |
| **Test gap repair** | `MotionSceneSchemaTest.kt`, `KeyboardLayoutUiTest.kt`, plus minor others | F-8 (MotionScene schema 4 remaining ConstraintSets), F-9 (UI-Test bodies = fail()), F-31..F-33 NTH coverage gaps |
| **Service-side polish** | `DictatePipelineService.kt`, `DictateInputMethodService.java` | F-5 (Locale), F-17 (instanceof check) |
| **KDoc wiring-state clarifications** | `ContentAreaController.kt`, `PromptVisibilityController.kt`, `OverlayResetHandler.kt`, `RenderBackend.kt`, `KeyboardStateManager.kt` | F-6 (dead-controller bundle), F-20 (unused-param idiom standardisation) |

---

## Findings

### F-1 (was AUDIT-LOGIC-B4-1) — CRITICAL

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Critical
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/ImeViewBackend.kt:256`
- **Description:** `BACKSPACE` long-press handler in `wireStaticHandlers` is wired as `setOnLongClickListener { true }` — bare consume. Because `wireStaticHandlers` runs **after** `MainButtonsController.registerAllListeners` (IME line 733 vs 949), the no-op listener **overwrites** the legacy `onBackspaceLongClicked` cascade, killing accelerated-delete (`deleteHandler.postDelayed` repeat-loop). User-visible regression: hold-backspace produces no behaviour.
- **Suggested fix:** Remove the BACKSPACE long-press wiring from `ImeViewBackend.wireStaticHandlers` entirely. BACKSPACE is not state-driven in the new path (the catalog assigns it `actionResolver = null`); the static-handler call should not touch its long-click listener. Concretely: delete the `backspaceBtn.setOnLongClickListener { ... }` line at ImeViewBackend.kt:256.
- **Domain bundle candidate:** ImeViewBackend long-press semantics

### F-2 (was AUDIT-LOGIC-B4-2) — CRITICAL

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Critical
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/ImeViewBackend.kt:248-250`
- **Description:** `RECORD` long-press handler wired as `{ onVibrate(); true }` (vibrate-only) **overwrites** the legacy `onRecordLongClicked()` cascade. The legacy two-way handler (a) opens DictateSettingsActivity with file-picker extra during `RecordingState.Idle`, and (b) sets `autoSwitchKeyboard = true` then `stopRecording()` during Active/Paused. Both user features silently vanish the moment ImeViewBackend attaches.
- **Suggested fix:** **Option (c) interim**: Remove the RECORD long-press wiring from `ImeViewBackend.wireStaticHandlers` so the legacy `MainButtonsController` handler survives. Concretely: delete the `recordBtn.setOnLongClickListener { ... }` block at ImeViewBackend.kt:248-250 — and leave the `onVibrate()` call there as a no-op? **No**: drop the whole block. The legacy handler already does the vibrate. Document this exception in the `ImeViewBackend` KDoc: "long-press semantics for RECORD remain in `MainButtonsController` until D-13 / B7 follow-up models them as Actions". Long-term: per F-2-followup (NTH, see below) introduce a `longClickResolver` pattern or `Action.RecordingAction.OnRecordLongClicked`.
- **Domain bundle candidate:** ImeViewBackend long-press semantics
- **Note:** Recommended Option (c) — straightforward, preserves all existing user behaviour, no Spec 1 change. Per D7 long-term, Option (b) (long-click resolver pattern parallel to actionResolver) is the cleaner endpoint; this is documented as F-2-followup but not implemented in this wave.

### F-3 (was AUDIT-LOGIC-B4-3) — CRITICAL

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Critical
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/IconResolvers.kt:52-54` (vs `core/MainButtonsController.kt:370-374`)
- **Description:** `resolveAudioFocusIcon` **inverts** legacy icon semantics:
  - New: `enabled=true → volume_up`, `enabled=false → volume_off`
  - Legacy: `enabled=true → volume_off`, `enabled=false → volume_up` (icon depicts what other audio does — focus enabled = silence others = volume_off)
  Currently dormant because legacy writes `view.foreground` while new writes `view.icon`; the moment the legacy path is retired the icon flips on every user device.
- **Suggested fix:** Invert the resolver to match legacy semantics:
  ```kotlin
  fun resolveAudioFocusIcon(enabled: Boolean): Int =
      if (enabled) R.drawable.ic_baseline_volume_off_24
      else R.drawable.ic_baseline_volume_up_24
  ```
  Then update `IconResolversTest` + `SlotRendererTest` expectations to the corrected pairing.
- **Domain bundle candidate:** IconResolvers semantics

### F-7 (was AUDIT-LOGIC-B4-4) — CRITICAL

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Critical
- **File:** `app/src/main/res/xml/motion_scene_keyboard.xml:276-285` (`single_row_state.widget_toggle_btn`) vs lines 260-271 (`audio_focus_btn`)
- **Description:** Both `widget_toggle_btn` and `audio_focus_btn` are pinned to `layout_constraintEnd_toEndOf="parent"` in the `single_row_state` ConstraintSet. With both visible (KEYBOARD_SINGLE_ROW catalog has WIDGET_TOGGLE.predicate via `isWidgetToggleVisible` = true when viewMode==KEYBOARD, AUDIO_FOCUS.predicate = `{ true }`), the two icons overlap stacked at the parent's right edge.
- **Suggested fix:** In the `single_row_state` ConstraintSet, chain `widget_toggle_btn` BEFORE `audio_focus_btn`:
  - `widget_toggle_btn.layout_constraintEnd_toStartOf="@+id/audio_focus_btn"` (replace the `parent` end-anchor)
  - `audio_focus_btn.layout_constraintStart_toEndOf="@+id/widget_toggle_btn"` (if not already)
  Verify spacing margins match the two_row_state pattern. This pairs naturally with F-18's structural cleanup: making `WIDGET_TOGGLE.visibilityPredicate` aware of `singleRowMode` would also work, but the layout-side fix is cleaner because the toggle is meant to be visible in single-row per Spec 2 §3.1.
- **Domain bundle candidate:** MotionScene XML layout

### F-4 (was AUDIT-PLAN-AND-API-B4-1) — Important

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt:72`
- **Description:** `resolveRecordAction` calls `services.toastSink.showError("Storage full — recording failed to start")` — hardcoded English literal. Spec 2 §8.5 + Spec 3 §3.1 both prescribe `services.toastSink.show(R.string.dictate_storage_full)`. String resource exists (`res/values/strings.xml:415` — "Cache full — recording cannot start."). Bypasses i18n + diverges from spec wording + uses `showError` instead of `show`.
- **Suggested fix:** Inspect `ToastSink` API surface first. If `show(@StringRes Int)` exists: switch to it. If only `show(String)` exists: use `show(ctx.getString(R.string.dictate_storage_full))`. Align method-name with Spec 2 §8.5 (`show`, not `showError`).
- **Domain bundle candidate:** LayoutCatalog + Predicates housekeeping

### F-5 (was AUDIT-CONVENTION-B4-4) — Important

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:513, 523, 525` (`buildLayoutStrings`)
- **Description:** Three `String.format(...)` call sites omit explicit `Locale`. With device locale set to e.g. Turkish or Arabic, locale-aware digit/separator characters appear in technical labels (`"%d:%02d"`, "%d/%d %d:%02d", etc.). Other new B4 code (`RecordingAnimationController.kt:118`) already uses `Locale.US`; the same author missed it here.
- **Suggested fix:** Add `import java.util.Locale` to `DictatePipelineService.kt` and convert each `String.format(...)` to `String.format(Locale.US, ...)`. These are technical formats; `Locale.US` is correct.
- **Domain bundle candidate:** Service-side polish

### F-6 (was CONVENTION-B4-3 + LOGIC-B4-5, merged) — Important

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:** `app/src/main/java/net/devemperor/dictate/state/render/ContentAreaController.kt`, `.../PromptVisibilityController.kt`, `.../OverlayResetHandler.kt`, `.../RenderBackend.kt`
- **Description:** All three null-`backendType` controllers are defined + 20 unit tests, never instantiated in production. `DictateInputMethodService.attachImeViewBackendIfReady` only attaches `ImeViewBackend`. The KDoc on `RenderBackend.kt` says "`ImeViewBackend` + `ContentAreaController` are both attached during normal IME runs" — that's untrue today. Risk: a future block author assumes the new path is authoritative and writes code on that assumption.
- **Suggested fix:** Add an `// IMPL-STATE (post-C15):` anchor in each of the three controller class KDocs noting "Not yet attached in production — `KeyboardStateManager.applyContentAreaVisibility()` / `applyPromptsVisibility()` / `applyVisibility()` continue to own this axis until the D-13 follow-up block migrates IME-side wiring. Tests exercise the contract so the controller can be wired in one step once the matching KSM method is removed." Update `RenderBackend.kt` KDoc to reflect "only `ImeViewBackend` attached today". Add a matching `// TODO(D-13 follow-up):` anchor on the three KSM methods (`applyContentAreaVisibility`, `applyPromptsVisibility`, the `overlayCharactersLl.visibility = View.GONE` line in `applyVisibility`). Pure documentation fix — no production-code or test changes needed.
- **Domain bundle candidate:** KDoc wiring-state clarifications

### F-8 (was AUDIT-TEST-B4-1) — Important

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/test/java/net/devemperor/dictate/state/layout/MotionSceneSchemaTest.kt:85-101`
- **Description:** Test "every required button carries visibilityMode=ignore in the base state" only iterates the `two_row_state` ConstraintSet. The other 4 (`single_row_state`, `two_row_send_mode_state`, `single_row_send_mode_state`, `reprocess_staging_state`) each contain per-Constraint `<PropertySet motion:visibilityMode="ignore"/>` declarations (20 total visibilityMode lines in the XML); the test never iterates them. Per Spec 2 §7.3 / R.11 "non-negotiable", the marker MUST be present in every ConstraintSet that owns a button's visibility. A derive-from sibling missing the marker would slip past the test.
- **Suggested fix:** Refactor the assertion to iterate **all 5** ConstraintSets, asserting each declared `<Constraint>` block carries `<PropertySet motion:visibilityMode="ignore"/>`. The test fixture already parses the XML; just extend the loop.
- **Domain bundle candidate:** Test gap repair

### F-9 (was AUDIT-TEST-B4-5) — Important

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/androidTest/java/net/devemperor/dictate/ui/KeyboardLayoutUiTest.kt:42-128` (10 tests)
- **Description:** All 10 Espresso UI tests are `@Ignore`d with `pending:` markers — names + bug-symptom anchors locked, but bodies are comment-only (step-by-step prose, no assertions). Risk: an accidental un-ignore (a future maintainer flips `@Ignore` without writing the body) leaves the test green and lets a real regression ship. Per `~/.claude/snippets/test-first-patterns.md` "Pending tests" convention, the body should at minimum call `fail("pending: <reason>")` so an un-ignored skeleton lands red.
- **Suggested fix:** Replace each test body's final line with `fail("pending: spec 2 §14.2 UI-{N} — body skeleton; un-ignore + implement assertions")`. Add a JUnit `import org.junit.Assert.fail`. The 10 tests still skip today (@Ignore wins); they will fail loudly if `@Ignore` is removed without writing the body — exactly the desired pending-test behaviour.
- **Domain bundle candidate:** Test gap repair

### F-10 (was AUDIT-PLAN-AND-API-B4-2) — Important

- **Classification:** 🟡 valid + research-needed
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt:82-83`
- **Description:** `Action.RecordingAction.StopRecordingAndSend(sessionId = "")` uses an empty-string sentinel — Spec 2 §8.5 anticipates the recording-→-pipeline cascade overrides it. C12/C14 deferred this; C15 still ships the empty-string. The data-class field that the receiver "overrides" instead of "uses" is an API-contract leak; risk that a future caller sees `StopRecordingAndSend("")` and treats the empty string as meaningful (e.g. logging).
- **Research topic:** `recording-action-sessionId-shape` — does Spec 1 §3.3 + Spec 2 §8.5 want `sessionId: String?` with the reducer generating the id when null, or stays-as-`String` with the documented empty-string sentinel? Trade-off: nullable cleaner at type-level but touches every call site + reducer; sentinel is pragmatic but breaks "structurally meaningful click → meaningless field". Decide which spec wins, then conform implementation.
- **Why research:** Touches `Action.RecordingAction.StopRecordingAndSend` data class (Spec 1 §3.3 owner) + `RecordingModule.reduce` arm + every other call-site. Not a local fix.
- **Domain bundle candidate:** LayoutCatalog + Predicates housekeeping (eventually)

### F-12 (was PLAN-AND-API-B4-3 + LOGIC-B4-6, merged) — Important

- **Classification:** 🟡 valid + research-needed
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:382-391` (KEYBOARD_REPROCESS_STAGING RECORD slot)
- **Description:** `enabledResolver = { state.pipeline is PipelineUiState.ReprocessStaging }` — structurally always-true when this mode is active. Spec 2 §8.4 prescribes `s != null && !s.isStarting`. The `isStarting: Boolean` field is **not yet on `PipelineUiState.ReprocessStaging`**. During the staging-loading window (between SendStaging click and pipeline-Running), RECORD stays enabled and a double-click race dispatches `SendStaging(sessionId)` twice → two `Preparing` cascade entries with the same sessionId.
- **Research topic:** `reprocess-staging-isStarting-field` — add `isStarting: Boolean = false` to `PipelineUiState.ReprocessStaging` (Spec 1 §3) + `PipelineModule.reduce` transitions `isStarting` true ↔ false around the SendStaging effect + resolver gates on it. Alternative: ResendModule-style cooldown bit. Choice depends on Spec 1 §15.2 ownership.
- **Why research:** Spec 1 §3 state-shape extension + PipelineModule reducer changes — cross-block (B5/B6 scope).
- **Domain bundle candidate:** B5/B6-pre planning block

### F-13 (was AUDIT-PLAN-AND-API-B4-4) — Important

- **Classification:** 🟡 valid + research-needed
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/TextResolvers.kt:103-104` + `LayoutCatalog.kt:238, 320`
- **Description:** `resolveRecordButtonTextPipeline` should read `pipe.completedSteps`, `pipe.totalSteps`, `pipe.elapsedMs` from `PipelineUiState.Running` per Spec 2 §8.5. Current implementation passes `(0, 0, autoEnterActive, 0L)` — placeholder. Today the legacy `KeyboardUiController.refreshRecordButtonFromState` wins on every refresh (so user sees the right counter), but the new path is broken-by-design — the counter dies the moment legacy is removed (Spec 2 §10 Block-5 AC violation).
- **Research topic:** `pipeline-running-counter-fields` — extend `PipelineUiState.Running` with `completedSteps: Int`, `totalSteps: Int`, `elapsedMs: Long`. Or read from side-channel. Best done before B5/B6 retires legacy.
- **Why research:** Spec 1 §3 state-shape + PipelineModule reducer + LayoutStrings call-site — cross-block.
- **Domain bundle candidate:** B5/B6-pre planning block

### F-14 (was AUDIT-PLAN-AND-API-B4-5) — Important

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/RecordingAnimationController.kt:62, 102`
- **Description:** Implementation caches `lastRecordingClass: Class<out RecordingState>?` instead of spec-prescribed `RecordingState?` value-cache. Behaviour is identical (both compare via `::class`); the C14 deviation rationale ("different `audioFile` would falsely flag state changed") **misstates the spec equivalence** — `::class` already ignores constructor-args. Stylistic drift + misleading rationale.
- **Suggested fix:** Either (a) clarify the C14 deviation rationale + rename to `lastRecordingClassRef` for honesty, or (b) cache `RecordingState?` and compare with `prev?.let { p -> p::class == curr::class }` per Spec 2 §11.5 verbatim. Prefer (b) (matches spec wording one-for-one, no semantic change).
- **Domain bundle candidate:** ImeViewBackend long-press semantics (file is in same package; this is a separate file but small)

### F-15 (was AUDIT-PLAN-AND-API-B4-8) — Important *(re-classified from NTH to Important to match the cluster's cross-cutting impact; see cross-cut pattern #2)*

- **Classification:** 🟡 valid + research-needed
- **Severity:** Important *(elevated from NTH — block-aware: this is the third placeholder in the same plan-evolution cluster as F-12 + F-13, and the un-resolution would break the language-aware record-button label the moment legacy retires)*
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:497-528` (`buildLayoutStrings`)
- **Description:** `LayoutStrings.dictateButtonText = getString(R.string.dictate_record)` is static. Legacy `getDictateButtonText()` (DictateInputMethodService.java) is language-aware (reads `LanguageController.getEffectiveLanguage()`). C15 IMPL-2 acknowledged this is "visible only when new path renders alone (currently superseded by legacy MainButtonsController.updateRecordButtonText)". Latent regression — surfaces the moment legacy retires.
- **Research topic:** `language-controller-decoupling-B7` — already named in the C15 deviation table as "B7 LanguageController + Settings-UI decoupling". Wire `LanguageModule.effectiveLanguage` (or equivalent state-flow) into `LayoutStrings.dictateButtonText` once B7 lands.
- **Why research:** D-13 + B7 scope — touches 49 LanguageController references across the codebase.
- **Domain bundle candidate:** B5/B6/B7-pre planning block

### F-16 (was AUDIT-PLAN-AND-API-B4-6 + AUDIT-PLAN-AND-API-B4-10, merged) — Important

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:57, 63-471, 465-472`
- **Description:** Two structural deltas vs spec:
  1. `LayoutCatalog` is declared as `class LayoutCatalog(strings: LayoutStrings)` (constructor-injection) — Spec 2 §8.6 says `object LayoutCatalog { val KEYBOARD_TWO_ROW = LayoutMode(...) }`. C12 deviation acknowledges the choice (constructor-injection enables test parameterisation), but Spec 3 §3.1's `object OVERLAY_5BUTTON : LayoutMode(...) nested inside LayoutCatalog` is now **impossible** when the outer is a `class` — instances can't host nested objects with that shape.
  2. All 6 mode-properties use `by lazy { LayoutMode(...) }` instead of plain `val =`. The lazy is over-engineered for a constructed-once registry; eager + cheap.
- **Suggested fix:** Add a Spec 3 §3.1 cross-spec deviation note + a forward-pointer in the C15 deviations table:
  > **B5 implementer note:** `OVERLAY_5BUTTON` is implemented as `val OVERLAY_5BUTTON: LayoutMode by lazy { LayoutMode(...) }` in this `class LayoutCatalog(strings: LayoutStrings)` — not as a nested `object OVERLAY_5BUTTON : LayoutMode(...)`. B5 supplies the body inside the `by lazy { ... }` block. Spec 3 §3.1's nested-object wording is decorative; the implementation is the lazy-property form.
  
  Plus convert the 6 `by lazy` → plain `val = LayoutMode(...)` where this is safe in tests (verify VisibilityMatrixTest + LayoutCatalogTest still pass). If a test depends on lazy-init for `OVERLAY_5BUTTON` placeholder, keep that one lazy; otherwise convert all 6.
- **Domain bundle candidate:** LayoutCatalog + Predicates housekeeping
- **Note:** The cross-spec wording-note is the priority; the lazy→eager conversion is the Phase 4.7 cleanup.

### F-17 (was AUDIT-LOGIC-B4-8) — Important

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:974-978`
- **Description:** Comment + null check claim to defend against "main_buttons_cl is not a MotionLayout", but Java `findViewById` returns the declared cast type — a type mismatch throws `ClassCastException`, not returns null. The defensive intent fails its own contract.
- **Suggested fix:** Use explicit `instanceof` check:
  ```java
  View v = dictateKeyboardView.findViewById(R.id.main_buttons_cl);
  if (!(v instanceof MotionLayout)) {
      Log.w("DictateIME", "main_buttons_cl is not a MotionLayout — ImeViewBackend not attached");
      return;
  }
  MotionLayout motionLayout = (MotionLayout) v;
  ```
- **Domain bundle candidate:** Service-side polish

### F-18 (was AUDIT-LOGIC-B4-10) — Important

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/Predicates.kt:101-102` (`isWidgetToggleVisible`)
- **Description:** `isWidgetToggleVisible(state) = state.viewMode == ViewMode.KEYBOARD` is structurally redundant — the manager routes WIDGET/HOVER viewModes to `OVERLAY_5BUTTON`, so the predicate is only evaluated when `viewMode == KEYBOARD` is already true. The dead truth-table branch may mislead future readers; specifically, in `KEYBOARD_SINGLE_ROW` it returns true 100% of the time, intersecting with F-7's overlap issue.
- **Suggested fix:** Either (a) make widget_toggle conditional on `viewMode == KEYBOARD && !state.layout.singleRowMode` (closes F-7 via a different path — but F-7's XML-side fix is preferable since the spec wants widget_toggle visible in single-row), or (b) simplify the predicate to `{ true }` in the KEYBOARD-mode slots since the gating happens at the mode-selection layer. Prefer (b); add a one-line KDoc comment "gating happens via `forKeyboard()` mode selection; this predicate is structurally always-true in the modes that use it".
- **Domain bundle candidate:** LayoutCatalog + Predicates housekeeping

### F-19 (was AUDIT-CONVENTION-B4-5) — Nice-to-have

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/Predicates.kt:1`
- **Description:** File name `Predicates.kt` mismatches its `@file:JvmName("LayoutPredicates")`. Sibling files (TextResolvers.kt → LayoutTextResolvers, IconResolvers.kt → LayoutIconResolvers, ActionResolvers.kt → LayoutActionResolvers) all match file-name ↔ JvmName minus the `Layout` prefix.
- **Suggested fix:** Rename `Predicates.kt` → `LayoutPredicates.kt`. JvmName stays. Git rename, no content changes.
- **Domain bundle candidate:** LayoutCatalog + Predicates housekeeping

### F-20 (was CONVENTION-B4-6 + PLAN-AND-API-B4-9, merged) — Nice-to-have

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **Files:** `state/layout/ActionResolvers.kt:96, 113, 133, 149, 159` + `state/render/ContentAreaController.kt:84` + `OverlayResetHandler.kt:69-70` + `PromptVisibilityController.kt:81`
- **Description:** Two parallel "unused parameter" idioms in new code:
  - `ActionResolvers.kt`: `@Suppress("UNUSED_PARAMETER") services` as a statement-level expression (5 sites)
  - 3 controllers: `@Suppress("UNUSED_VARIABLE") val _unused = mode` as a statement-level expression (4 sites)
  Both work; same-operation-two-ways drift.
- **Suggested fix:** Standardise on annotation-on-parameter form. Replace each `@Suppress("UNUSED_VARIABLE") val _unused = mode` line with the annotation moved to the parameter list (`fun render(state: DictateUiState, @Suppress("UNUSED_PARAMETER") mode: LayoutMode) { ... }`). Same for `ActionResolvers.kt`'s `services` parameter (move the annotation to the parameter list, remove the dummy statement-expression). Removes 9 statement-level dummies.
- **Domain bundle candidate:** KDoc wiring-state clarifications

### F-21 (was AUDIT-LOGIC-B4-7) — Important

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/ImeViewBackend.kt:159-166`
- **Description:** `firstRender = false` is set OUTSIDE the `mode.sceneStateId?.let { ... }` block. If a backend's first render-tick uses a mode with `sceneStateId = null`, the firstRender flag clears without an actual `jumpToState` call. The next IME_VIEW render then uses `transitionToState` (animated 250 ms) when it should have jumped. Today's IME_VIEW modes all have non-null sceneStateIds, so the bug is dormant — but the invariant is fragile.
- **Suggested fix:** Move `firstRender = false` INSIDE the `?.let { ... }` block so the flag only flips after an actual jump/transition fires. Alternatively, document the invariant ("IME_VIEW backend never sees a null sceneStateId") in the class KDoc.
- **Domain bundle candidate:** ImeViewBackend long-press semantics

### F-22 (was AUDIT-LOGIC-B4-13) — Nice-to-have

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/ImeViewBackend.kt:255, 257`
- **Description:** Vibrate-haptic inconsistency on long-press wiring:
  - RECORD: `{ onVibrate(); true }`
  - RESEND: `{ onVibrate(); onAction?.invoke(Action.ResendAction.ResendLastAudioLong); true }`
  - BACKSPACE: `{ true }` ← no vibrate
- **Suggested fix:** Once F-1 removes the BACKSPACE long-press wiring entirely and F-2 removes the RECORD wiring, only RESEND remains and the inconsistency vanishes. **No separate action needed** — F-22 collapses into the F-1+F-2 fixes.
- **Domain bundle candidate:** ImeViewBackend long-press semantics (bundled with F-1, F-2)

### F-23 (was AUDIT-CONVENTION-B4-7 + AUDIT-LOGIC-B4-14, merged) — Nice-to-have

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:163` + `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt:77`
- **Description:** Fully-qualified type drift:
  - `LayoutCatalog.kt:163` uses `state.recording !is net.devemperor.dictate.state.RecordingState.Preparing` — short form `RecordingState.Preparing` already imported on line 7.
  - `ActionResolvers.kt:77` uses `net.devemperor.dictate.state.InsertionTarget.INPUT_CONNECTION` — the file imports `RecordingState` but not `InsertionTarget`.
- **Suggested fix:** `LayoutCatalog.kt:163` → shorten to `state.recording !is RecordingState.Preparing`. `ActionResolvers.kt` → add `import net.devemperor.dictate.state.InsertionTarget`, shorten line 77 to `target = InsertionTarget.INPUT_CONNECTION`.
- **Domain bundle candidate:** LayoutCatalog + Predicates housekeeping

### F-24 (was AUDIT-LOGIC-B4-15) — Nice-to-have

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:498-507` (`forKeyboard` decision tree)
- **Description:** The `when {}` block's `else -> KEYBOARD_TWO_ROW` is technically unreachable — the four prior branches cover the `(isStaging × isPipelineLive × singleRowMode)` combinations exhaustively. A reader expects `else` to mean "unexpected state shape".
- **Suggested fix:** Replace `else -> KEYBOARD_TWO_ROW` with explicit `!isPipelineLive && !state.layout.singleRowMode -> KEYBOARD_TWO_ROW`, and either drop the `else` or add a defensive `else -> error("forKeyboard: impossible state shape: $state")`. Prefer the explicit case + defensive `error`.
- **Domain bundle candidate:** LayoutCatalog + Predicates housekeeping

### F-25 (was AUDIT-LOGIC-B4-11) — Nice-to-have

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/res/xml/motion_scene_keyboard.xml:328-352`
- **Description:** Missing transition edges for legitimate runtime state pairs:
  - `two_row_send_mode_state ↔ single_row_send_mode_state` (toggle single-row during pipeline)
  - `two_row_send_mode_state ↔ reprocess_staging_state` and `single_row_send_mode_state ↔ reprocess_staging_state` (Running → ReprocessStaging cascade)
  Falls back to MotionLayout auto-transition (default fade); auto-transitions don't pass `visibilityMode="ignore"` consistently — risk of double-write during the transition window.
- **Suggested fix:** Add four `<Transition>` elements with `motion:duration="200"` between the missing state pairs. Derive-from already gives them the same chain constraints.
- **Domain bundle candidate:** MotionScene XML layout

### F-26 (was AUDIT-LOGIC-B4-12) — Nice-to-have

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/res/xml/motion_scene_keyboard.xml:38-44` (`record_pulse_layout`)
- **Description:** The PulseLayout wrapper does not carry `<PropertySet motion:visibilityMode="ignore"/>` in any ConstraintSet. The C13 overlooked-points table calls it out: dormant today (nobody toggles its android:visibility), but a future visibility consumer would have to relearn the invariant.
- **Suggested fix:** Add `<Constraint android:id="@+id/record_pulse_layout"><PropertySet motion:visibilityMode="ignore"/></Constraint>` to `two_row_state` (derive-from gives the variants the same property-set automatically).
- **Domain bundle candidate:** MotionScene XML layout

### F-27 (was AUDIT-PLAN-AND-API-B4-7) — Important

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/res/xml/motion_scene_keyboard.xml:84, 146, 152-154, 273-284` + Spec 2 §7.1
- **Description:** Two material additions to the scene XML are not in Spec 2 §7.1:
  1. `<Constraint android:id="@+id/record_btn"><PropertySet motion:visibilityMode="ignore" /></Constraint>` (lines 152-154) — record_btn lives inside PulseLayout, not a direct MotionLayout child; the Constraint silently no-ops. C13 deviation notes "PropertySet entries it cannot bind … kept harmless".
  2. `widget_toggle_btn` Constraints in both `two_row_state` (lines 76-84) and `single_row_state` (lines 277-284). Spec 2 §3.1 says "Position TBD (Vorschlag: neben AUDIO_FOCUS im action_row)" — the chosen placement is valid but not prescribed.
- **Suggested fix:** Spec-edit OR deviation-table entry (preferred for the audit-wave). Add a Phase 4.6 doc-pass to extend Spec 2 §7.1 + §13.5 with `widget_toggle_btn` and `record_btn`-PropertySet — explicit deviation row. **For this repair-wave:** add a one-line `# B4-deviation:` comment in the XML head referring to the C13 deviation table; the actual spec extension is Phase 4.6 scope.
- **Domain bundle candidate:** MotionScene XML layout

### F-29 (was AUDIT-CONVENTION-B4-8) — Nice-to-have

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/res/layout/activity_dictate_keyboard_view.xml:58-64` + lines 91-98
- **Description:** Inline-anchor convention (knowledge-doc-format §"Inline anchors") prescribes stable cross-references in source. `motion_scene_keyboard.xml` does this; `activity_dictate_keyboard_view.xml` has a head-comment block but no per-element pointer at `record_btn` (line 58-64, `android:text="@string/dictate_record"`) noting that catalog overrides this label at render time. Same for `widget_toggle_btn` placeholder icon.
- **Suggested fix:** Add `<!-- Catalog override: @see ... ButtonSlot.textResolver in LayoutCatalog.KEYBOARD_*_RECORD slots -->` near `record_btn`; `<!-- Catalog override: @see ... ButtonSlot.iconResolver for LogicalButtonId.WIDGET_TOGGLE -->` near `widget_toggle_btn`.
- **Domain bundle candidate:** MotionScene XML layout

### F-30 (was AUDIT-CONVENTION-B4-9) — Nice-to-have

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:69, 108` + `app/src/main/res/xml/motion_scene_keyboard.xml:30, 32, 36, 71, 99` + 4 other comment sites
- **Description:** Inline comments reference deleted physical row containers `action_row` / `input_row` (deleted in C15). Comments are pedagogical but the identifiers no longer exist; grep for `action_row` lands on these comments + `MainButtonsController.kt:501` (KDoc) + `KeyboardStateManager.kt:30, 46` (KDoc) + `DictateInputMethodService.java:675`.
- **Suggested fix:** Rewrite each as "Row 1 (formerly action_row)" / "Row 2 (formerly input_row)" — or just "Row 1 / Row 2" with a single top-of-file note explaining the legacy names appear in commit history.
- **Domain bundle candidate:** KDoc wiring-state clarifications

### F-31 (was AUDIT-CONVENTION-B4-10) — Nice-to-have

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:465-472` (OVERLAY_5BUTTON placeholder) + `app/src/test/java/net/devemperor/dictate/state/layout/LayoutCatalogTest.kt`
- **Description:** B5 placeholder (`OVERLAY_5BUTTON` with `rows = emptyList()`) has no compile/test guard that B5 actually fills the body. The file uses `error(...)` guards elsewhere (e.g. ImeViewBackend.kt:176); the equivalent guard for "OVERLAY_5BUTTON body must be replaced before any OVERLAY_WINDOW backend is wired" is missing.
- **Suggested fix:** Add a unit-test in `LayoutCatalogTest.kt` asserting `OVERLAY_5BUTTON.rows.isEmpty()` with the comment "this assertion will fail when B5 supplies the body — that's the trigger to delete the test". Lightweight and gives B5 a structural reminder.
- **Domain bundle candidate:** Test gap repair

### F-32 (was CONVENTION-B4-1) — Important

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/KeyboardVisibilityPredicates.kt:79-88` + `app/src/main/java/net/devemperor/dictate/state/layout/Predicates.kt:56-60`
- **Description:** Two parallel SoT for `isResendVisible` predicate. Legacy file `core/KeyboardVisibilityPredicates.kt` exposes the 4-arg form (consumed by `DictateInputMethodService.java:1687, 2052` and `RecordingUiController.kt:210, 232`); new B4 file `state/layout/Predicates.kt` exposes the 1-arg form (consumed by catalog slots). Both named `isResendVisible`; only the package disambiguates. Same drift class as F-6 + the dual-render-path cluster.
- **Suggested fix:** Annotate the legacy `KeyboardVisibilityPredicates.isResendVisible` with `@Deprecated("Use net.devemperor.dictate.state.layout.isResendVisible(state) — legacy four-arg form scheduled for removal after D-13", level = DeprecationLevel.WARNING)` so any new caller gets compile feedback. Cross-reference the legacy file's KDoc to the new path with `@see net.devemperor.dictate.state.layout.isResendVisible`. The actual deletion is D-13 / B7 follow-up scope (per the four legacy call-sites being legacy-IME and RecordingUiController).
- **Domain bundle candidate:** KDoc wiring-state clarifications

### F-33 (was CONVENTION-B4-2) — Important *(adjacent to F-6 cluster but addresses KSM-side methods specifically)*

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/KeyboardStateManager.kt:170-199` (`applyPromptsVisibility`)
- **Description:** `PromptVisibilityController.render(...)` and `KeyboardStateManager.applyPromptsVisibility()` implement the same truth-table (smallMode / EMOJI_PICKER / active-or-pipeline / rewordingEnabled → promptsCl.visibility). Today both write to the same View slots; the second writer is intentional per C15 deviation ("KSM kept"), but the convention concern is on file. Same-operation-two-ways risk: divergent updates if one grows a branch and the other doesn't.
- **Suggested fix:** Add `// TODO(D-13 follow-up): remove KeyboardStateManager.applyPromptsVisibility once PromptVisibilityController attaches in production — see F-6` anchor on `applyPromptsVisibility()`. Same for `applyContentAreaVisibility` (→ ContentAreaController) and the `overlayCharactersLl.visibility = View.GONE` line in `applyVisibility` (→ OverlayResetHandler). This is the KSM-side mirror of F-6's controller-side KDoc anchors. Together F-6 + F-33 establish the wiring-state on both sides.
- **Domain bundle candidate:** KDoc wiring-state clarifications (paired with F-6)

### F-34 (additional findings, NTH) — Test-quality gaps from AUDIT-TEST

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **Files:** various test files (see below)
- **Description:** Five small AUDIT-TEST findings, none regression-bearing; bundled here for batched repair:
  - **F-34a (AUDIT-TEST-B4-2):** `KeyboardLayoutManagerTest` doesn't cover "two different backends with the same `backendType`" case. Add a test case.
  - **F-34b (AUDIT-TEST-B4-3):** `ContentAreaControllerTest.detach is a no-op against future renders` should also assert that the non-active containers (the other two ContentArea values) are set to GONE post-detach.
  - **F-34c (AUDIT-TEST-B4-4):** `VisibilityMatrixTest` cross-mode cases should add `assert(state.viewMode == ViewMode.KEYBOARD)` precondition in each state-builder helper (defensive — pins the cross-mode invariant).
  - **F-34d (AUDIT-TEST-B4-6):** `PromptVisibilityControllerTest` should cover `PipelineUiState.Failed` branch (currently the test never exercises Failed).
  - **F-34e (AUDIT-TEST-B4-7):** `ImeViewBackendTest.staticHandlerInstaller is invoked on attach` should also assert the installer is NOT invoked on `render` (guards L8 single-wire contract).
  - **F-34f (AUDIT-TEST-B4-8):** `DictatePipelineServiceLayoutWiringTest.state emissions reach attached backends via the manager` is brittle (depends on `ResendAction.MarkLastAudio` reduce semantics). Add an explicit assertion via `binder.state.value` for refactor-safety.
- **Suggested fix:** Apply each as a small test-code change. Each is independent; bundle in a single test-side commit.
- **Domain bundle candidate:** Test gap repair

---

## Notes for repair-wave routing

**🟢 (auto-fixable) findings: 30 — bundle by domain for the single repair wave:**

| Bundle | Findings | Approx scope |
|--------|----------|--------------|
| ImeViewBackend long-press semantics (F-1, F-2, F-21, F-22 collapses) | 4 | Remove 2 setOnLongClickListener blocks, move 1 firstRender line, add KDoc note |
| IconResolvers semantics (F-3) | 1 | Invert icon truth-table + update 2 tests |
| MotionScene XML layout (F-7, F-25, F-26, F-27, F-29) | 5 | Add constraint chain + 4 transitions + 1 PropertySet + 2 XML comments + 1 deviation-comment |
| LayoutCatalog + Predicates housekeeping (F-4, F-16, F-18, F-19, F-23, F-24, F-30) | 7 | File rename + 6 small in-file edits |
| Test gap repair (F-8, F-9, F-31, F-34a-f) | 9 | One major test refactor (F-8), one mechanical fail() injection (F-9), 7 small test additions |
| Service-side polish (F-5, F-17) | 2 | Locale.US import + 3 call sites; instanceof check |
| KDoc wiring-state clarifications (F-6, F-20, F-32, F-33) | 4 | KDoc edits + Deprecated annotation + parameter-annotation refactor |
| RecordingAnimationController rename (F-14) | 1 | Replace lastRecordingClass cache with RecordingState? cache |

**🟡 (research-needed) findings: 3 (F-10, F-12, F-13, F-15)**

These are **planning artefacts** for B5/B6/B7 — they describe Spec 1 §3 state-shape extensions that aren't local to B4. The right disposition:

- Do **not** spawn research-agents in this repair wave.
- Do create a **plan-edit note** at the end of `B4-keyboard-layout-catalog.md` listing F-10, F-12, F-13, F-15 as **B4 audit-deferred plan-evolution issues**, with the recommendation that the orchestrator schedules a "B5-pre: pipeline state-shape evolution" mini-block ahead of B5/B6.
- This keeps the repair wave focused (single 🟢 wave) without losing the issue.

If the orchestrator prefers to research them now: research topics are `recording-action-sessionId-shape` (F-10), `reprocess-staging-isStarting-field` (F-12), `pipeline-running-counter-fields` (F-13), `language-controller-decoupling-B7` (F-15). Each would spawn a `VAL-RES-{K}` agent. Recommendation is to defer until the B5-pre block is opened.

**❌ (eliminated): 0**

No findings were eliminated as false positives. All 43 raw findings either de-duplicated cleanly into the 33 distinct findings above, or were carried forward verbatim. The 4 Criticals are real, user-visible, and all have small mechanical fixes.

---

## Eliminated findings

| Source ID | Source audit | Reason for elimination |
|-----------|--------------|------------------------|
| (none) | (none) | All findings validated; de-duplication merged related findings into single F-N entries with combined severity. |

---

## Source-finding ↔ F-ID mapping

| Source finding | Mapped to |
|----------------|-----------|
| AUDIT-PLAN-AND-API-B4-1 | F-4 |
| AUDIT-PLAN-AND-API-B4-2 | F-10 |
| AUDIT-PLAN-AND-API-B4-3 | F-12 (merged with LOGIC-B4-6) |
| AUDIT-PLAN-AND-API-B4-4 | F-13 |
| AUDIT-PLAN-AND-API-B4-5 | F-14 |
| AUDIT-PLAN-AND-API-B4-6 | F-16 (merged with PLAN-AND-API-B4-10) |
| AUDIT-PLAN-AND-API-B4-7 | F-27 |
| AUDIT-PLAN-AND-API-B4-8 | F-15 *(re-classified Important)* |
| AUDIT-PLAN-AND-API-B4-9 | F-20 (merged with CONVENTION-B4-6) |
| AUDIT-PLAN-AND-API-B4-10 | F-16 (merged with PLAN-AND-API-B4-6) |
| AUDIT-CONVENTION-B4-1 | F-32 |
| AUDIT-CONVENTION-B4-2 | F-33 |
| AUDIT-CONVENTION-B4-3 | F-6 (merged with LOGIC-B4-5) |
| AUDIT-CONVENTION-B4-4 | F-5 |
| AUDIT-CONVENTION-B4-5 | F-19 |
| AUDIT-CONVENTION-B4-6 | F-20 (merged with PLAN-AND-API-B4-9) |
| AUDIT-CONVENTION-B4-7 | F-23 (merged with LOGIC-B4-14) |
| AUDIT-CONVENTION-B4-8 | F-29 |
| AUDIT-CONVENTION-B4-9 | F-30 |
| AUDIT-CONVENTION-B4-10 | F-31 |
| AUDIT-LOGIC-B4-1 | F-1 |
| AUDIT-LOGIC-B4-2 | F-2 |
| AUDIT-LOGIC-B4-3 | F-3 |
| AUDIT-LOGIC-B4-4 | F-7 |
| AUDIT-LOGIC-B4-5 | F-6 (merged with CONVENTION-B4-3) |
| AUDIT-LOGIC-B4-6 | F-12 (merged with PLAN-AND-API-B4-3) |
| AUDIT-LOGIC-B4-7 | F-21 |
| AUDIT-LOGIC-B4-8 | F-17 |
| AUDIT-LOGIC-B4-9 | (note) folded into F-22's domain bundle. **Re-listed below as F-28.** |
| AUDIT-LOGIC-B4-10 | F-18 |
| AUDIT-LOGIC-B4-11 | F-25 |
| AUDIT-LOGIC-B4-12 | F-26 |
| AUDIT-LOGIC-B4-13 | F-22 (collapses into F-1+F-2 fixes) |
| AUDIT-LOGIC-B4-14 | F-23 (merged with CONVENTION-B4-7) |
| AUDIT-LOGIC-B4-15 | F-24 |
| AUDIT-TEST-B4-1 | F-8 |
| AUDIT-TEST-B4-2 | F-34a |
| AUDIT-TEST-B4-3 | F-34b |
| AUDIT-TEST-B4-4 | F-34c |
| AUDIT-TEST-B4-5 | F-9 |
| AUDIT-TEST-B4-6 | F-34d |
| AUDIT-TEST-B4-7 | F-34e |
| AUDIT-TEST-B4-8 | F-34f |

### F-28 (was AUDIT-LOGIC-B4-9) — Important

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/SlotRenderer.kt:64-67`
- **Description:** `applySlotToView` writes `view.icon = ContextCompat.getDrawable(ctx, iconRes)` on EVERY render-tick — ~45 unnecessary Drawable allocations per state emit. Spec 2 §11.5 explicitly warns about per-tick allocations inflating StateFlow→render cost. (Index oversight in the mapping table above — F-28 is a real finding, not a bundling note.)
- **Suggested fix:** Cache the last-applied `iconRes: Int?` per logical button id (or per slot). Skip the `ContextCompat.getDrawable` + assignment when the resource id is unchanged. Same optimisation for text (cheap because String objects are interned, but worth a one-liner). The cache lives in `ImeViewBackend` (per-button-id map) or in a small `SlotRenderState` helper struct.
- **Domain bundle candidate:** ImeViewBackend long-press semantics (same package; small enough to bundle into the wave)

---

## Final summary

- **🟢 fixable in this wave: 30 findings** (4 Critical, 12 Important, 14 Nice-to-have)
- **🟡 deferred to B5-pre planning block: 3 findings (F-10, F-12, F-13, F-15)** *(4 listed, 3 distinct topics — F-12 + F-13 + F-15 are three pipeline state-shape extensions; F-10 is the sessionId-sentinel question; together they are 4 issues but 1 cluster of "Spec 1 §3 state evolution")*
- **❌ eliminated: 0**

**Repair-wave: 1 wave.** All Criticals are option-(c)-style interim fixes (don't touch legacy long-press cascade; just remove the new overrides). The 30 🟢 findings can land as one wave, bundled by the 7 domain groups above for diff-locality.

**Plan-edit recommendation:** Append a short "B4 audit-deferred plan-evolution" note to `B4-keyboard-layout-catalog.md` listing F-10, F-12, F-13, F-15 with the suggested follow-up scope (B5-pre block). The orchestrator decides whether to schedule that block before B5 or fold it into B5's first chunk.
