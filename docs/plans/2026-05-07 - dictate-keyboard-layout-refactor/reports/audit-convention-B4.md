# Audit Report: convention (Block 4, scope: full-block)

**Agent-ID:** B4-AUDIT-CONVENTION
**Date:** 2026-05-15
**Knowledge skills used:** knowledge-reference (general patterns); knowledge-doc-format (Inline-Anchor convention: header / `@see` / gotcha). Project CLAUDE.md consulted as primary baseline. Spec 2 (`research/2-keyboard-layout/2-keyboard-layout.reviewed.md`) and ADR-0004 cross-referenced for anchor-target validity.

**Files inspected:** 28

Production (new, B4):
- `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/ButtonSlot.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/IconResolvers.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/KeyboardLayoutManager.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/LayoutMode.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/LayoutModeId.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/LogicalButtonId.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/Predicates.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/RenderBackend.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/TextResolvers.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/ContentAreaController.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/ImeViewBackend.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/MotionSurface.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/OverlayResetHandler.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/PromptVisibilityController.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/RecordingAnimationController.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/SlotRenderer.kt`

Production (modified, B4):
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt`
- `app/src/main/java/net/devemperor/dictate/core/KeyboardStateManager.kt`

Production (deleted, B4):
- `app/src/main/java/net/devemperor/dictate/core/KeyboardLayoutModeController.kt` (273 LOC, gone)

Resources:
- `app/src/main/res/xml/motion_scene_keyboard.xml` (new)
- `app/src/main/res/layout/activity_dictate_keyboard_view.xml` (refactor)

Tests + Espresso skeleton:
- 11 new JVM/Robolectric files under `app/src/test/java/.../state/layout/` + `.../state/render/` + 1 service-side wiring test
- `app/src/androidTest/java/.../ui/KeyboardLayoutUiTest.kt` (new, all 10 `@Ignore`d)

Out-of-block files cross-checked for SoT-drift:
- `app/src/main/java/net/devemperor/dictate/core/KeyboardVisibilityPredicates.kt` (legacy predicate SoT)
- `app/src/main/java/net/devemperor/dictate/core/RecordingUiController.kt` (legacy visibility writer)

## Summary

- Critical: 0
- Important: 4
- Nice-to-have: 6

K-1 (no mocking framework) clean: `grep -rE 'mockk|Mockito' app/src/test/java/net/devemperor/dictate/state/layout/ app/src/test/java/net/devemperor/dictate/state/render/` returns only KDoc / comment lines re-affirming the K-1 baseline; no `mock<>()` / `mockk()` / `Mockito.when(...)` usage. K-4 (no Android Context in JVM tests unless justified): 7 Robolectric tests in B4 — every file declares an explicit `Why Robolectric` KDoc block (`SlotRendererTest`, `ContentAreaControllerTest`, `PromptVisibilityControllerTest`, `OverlayResetHandlerTest`, `ImeViewBackendTest`, plus `DictatePipelineServiceLayoutWiringTest` and `KeyboardLayoutUiTest` for connected-device tests). All catalog-layer files (`LayoutCatalog`, `Predicates`, `ActionResolvers`, `IconResolvers`, `TextResolvers`, `KeyboardLayoutManager`, `LayoutMode`, `ButtonSlot`, `LogicalButtonId`, `LayoutModeId`) keep their tests JVM-pure (`testLayoutStrings()` fixture + hand-rolled `TestRenderBackend` / `FakeMotionSurface` / `FakeRecordingAnimation`). DictatePrefs sealed-class rule observed — no raw-string Pref keys introduced; `state/layout` reads Pref only indirectly via `DictateUiState` snapshot. MotionLayout `visibilityMode="ignore"` is present on every state-driven button id in the `two_row_state` (base) ConstraintSet and propagates through derive-from for the other four states.

## Findings

### AUDIT-CONVENTION-B4-1

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/KeyboardVisibilityPredicates.kt:79-88` + `app/src/main/java/net/devemperor/dictate/state/layout/Predicates.kt:56-60`
- **Description:** Two parallel SoTs for the same predicate `isResendVisible`. The legacy file under `core/` exposes the 4-arg form (`Boolean, Boolean, RecordingState, PipelineUiState`) consumed by `DictateInputMethodService.java:1687, :2052` and `RecordingUiController.kt:210, :232`; the new B4 file under `state/layout/` exposes the 1-arg form `(DictateUiState) -> Boolean` consumed by the catalog slots. Both are named `isResendVisible` — only the package disambiguates. The new file's `@file:JvmName("LayoutPredicates")` softens the collision on the JVM side but does not fix it at the Kotlin source level: a maintainer touching one definition will not realise the other path still applies. This is exactly the same-operation-two-ways drift the SoT rule (per `knowledge-reference`) is meant to prevent — Spec 2 §13.5 / §9 §11.8 5d explicitly schedules removal of the legacy resend predicate (Gap 2 was supposed to land via Catalog migration).
- **Why it matters:** Drift class. A future fix to one branch (e.g. "also gate on `audio.foregroundService"`) silently leaves the other branch behind. The block-report C15 deviation table already documents that "two render paths run in parallel" for the buttons; the predicates are the second-level evidence of the same problem on the visibility-truth-table axis.
- **Suggested fix scope:** medium (one file `KeyboardVisibilityPredicates.kt` deletable once the four legacy call sites adopt the new path).
- **Suggested fix:** delete `KeyboardVisibilityPredicates.kt` after migrating its four call sites; or — if the migration is deferred to D-13's follow-up block per the C15 deviation table — add a `@Deprecated("Use net.devemperor.dictate.state.layout.isResendVisible(state) — legacy four-arg form scheduled for removal after D-13", level = ERROR)` annotation so any new caller gets a hard compile error and the SoT-drift is at least signposted to future readers. Cross-reference the legacy file's KDoc to the new path.

### AUDIT-CONVENTION-B4-2

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/PromptVisibilityController.kt:80-118` + `app/src/main/java/net/devemperor/dictate/core/KeyboardStateManager.kt:170-199`
- **Description:** `PromptVisibilityController.render(...)` and `KeyboardStateManager.applyPromptsVisibility()` implement the **same** truth-table (smallMode / EMOJI_PICKER / active-or-pipeline / rewordingEnabled → `promptsCl.visibility`) plus the same pipeline-progress vs `promptsRv` swap and the same QWERTZ recording-controls toggle. Same operation, two implementations, both writing to the same `View`s (`promptsCl`, `promptsRv`, `pipelineProgressLl`, `promptRecordingControlsLl`). Per the C15 deviation row "KSM kept" the cross-cutting concern was intentionally left behind, but the convention concern is that B4 added a second SoT for this axis without disabling the first — `KeyboardStateManager` is still wired in `DictateInputMethodService.java:684-695` and `applyVisibility()` still runs on every KSM mutation.
- **Why it matters:** Two writers on the same `View.visibility` field are the same bug class the entire B4 refactor was built to eliminate (Spec 2 §1.1 #3b race for `resendButton`). Today the two writers happen to agree because both read the same backing `DictateUiState` axes via different APIs — the moment one of them grows a new branch and the other does not, the surface that "wins" depends on render-tick order. The fact that `PromptVisibilityController` and `OverlayResetHandler` and `ContentAreaController` are also **never wired** (finding 3 below) means the parallel-path risk is currently zero — but the convention drift is on file and will reactivate the day the controllers come online.
- **Suggested fix scope:** large (Phase-2 follow-up — the same D-13-class work the C15 deviation table calls out).
- **Suggested fix:** flag with a `// TODO(D-13 follow-up): remove KSM.applyPromptsVisibility once PromptVisibilityController attaches in production` anchor in `KeyboardStateManager.applyPromptsVisibility` so the SoT-rule violation is signposted. Same for `applyContentAreaVisibility` → `ContentAreaController` and `applyVisibility`'s `overlayCharactersLl.visibility = View.GONE` → `OverlayResetHandler`. A Phase-2 cleanup block then strips the KSM methods once the new controllers are attached.

### AUDIT-CONVENTION-B4-3

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/ContentAreaController.kt`, `.../PromptVisibilityController.kt`, `.../OverlayResetHandler.kt`
- **Description:** All three `backendType = null` RenderBackends are **defined and tested** but never instantiated, never wired via `KeyboardLayoutManager.attachBackend(...)`. A `grep -rn "ContentAreaController(\|PromptVisibilityController(\|OverlayResetHandler(" app/src/main/` returns only the class definitions themselves and KDoc cross-references — no caller. The IME service only constructs and attaches `ImeViewBackend`. The block-report (C14 + C15 sections) acknowledges this as scope-bounded ("legacy KSM still drives ContentArea + Prompt + Overlay axes; D-13 follow-up does the destructive wiring"), so the issue is **plan-aware**, but from a convention angle it is dead code in production today.
- **Why it matters:** "Define a class, write its tests, but never attach it" is a non-trivial code asset to leave dormant. The risk is: someone reads `RenderBackend.kt` KDoc ("`ImeViewBackend` + `ContentAreaController` are both attached during normal IME runs"), believes both are live, and chases a phantom bug. The KDocs lie about the production wiring state. Either the prose is wrong, or the attach-call is missing. The C15 block-report deviation explains the policy, but the production-code-side KDoc does not — only the block-report knows.
- **Suggested fix scope:** small (KDoc edits — three files).
- **Suggested fix:** add an `// IMPL-STATE:` anchor in each of the three controller class KDocs noting the current wiring state, e.g.:
  > **Wiring status (post-C15):** Not yet attached in production. `KeyboardStateManager.applyContentAreaVisibility()` continues to own this axis until the D-13 follow-up block migrates the IME-side wiring. Tests exercise the contract so the controller can be wired in one step once KSM's matching method is dropped.

  Same anchor in `RenderBackend.kt`'s class KDoc — adjust "`ImeViewBackend` + `ContentAreaController` are both attached during normal IME runs" to reflect the current "only `ImeViewBackend` attached today" reality.

### AUDIT-CONVENTION-B4-4

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:512-527`
- **Description:** `buildLayoutStrings` uses `String.format("...", ...)` **without an explicit `Locale`** at lines 513, 523, 525. The repo's other formatter call sites consistently pass a Locale: `RecordingAnimationController.kt:118` uses `Locale.US`, `KeyboardUiController.kt:596` uses `Locale.getDefault()`, history activities use `Locale.getDefault()`, settings activities use `Locale.US`. With the system default locale set to e.g. Turkish (`tr_TR`) or Arabic (`ar_*`), an unspecified-locale `String.format("%d:%02d", ...)` substitutes locale-aware digit characters and could produce non-ASCII numerals in the formatted button label — which the LayoutCatalog text resolver passes straight to `MaterialButton.text`.
- **Why it matters:** Convention drift in a domain that historically bites Android apps. The B4 `RecordingAnimationController.kt:118` (also new this block) gets it right (`Locale.US`); the same author writing `buildLayoutStrings` two files later did not pick up the pattern. The cost of getting it wrong is a non-ASCII timer label on a small % of installs that's hard to reproduce from a developer machine in `en_US`.
- **Suggested fix scope:** small (one file, three call sites).
- **Suggested fix:** add `import java.util.Locale` and convert the three `String.format(...)` calls to `String.format(Locale.US, ...)` (these are technical labels — "Audio 0:23 · Send", "%d/%d %d:%02d" — that should stay ASCII per the `RecordingAnimationController` precedent). Or `Locale.getDefault()` if the spec wants per-locale rendering — but pick deliberately, not by omission. Match the convention already established in the new `RecordingAnimationController.kt` file.

### AUDIT-CONVENTION-B4-5

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/Predicates.kt:1` (file name + JvmName mismatch)
- **Description:** The file is named `Predicates.kt` but carries `@file:JvmName("LayoutPredicates")`. The four sibling resolver files all follow the `<Topic>Resolvers.kt` ↔ `Layout<Topic>Resolvers` pattern (file name matches JvmName minus the `Layout` prefix). For `Predicates.kt` the file name itself is missing the `Layout` prefix that the JvmName promises. Inconsistent with the sibling layout-package files. Compare:
  - `TextResolvers.kt` → `LayoutTextResolvers` ✓ (matches pattern)
  - `IconResolvers.kt` → `LayoutIconResolvers` ✓
  - `ActionResolvers.kt` → `LayoutActionResolvers` ✓
  - `Predicates.kt` → `LayoutPredicates` (file should arguably be `LayoutPredicates.kt`)
- **Why it matters:** Stylistic drift only — but the IDE's "open file by name" workflow (Ctrl+Shift+N "LayoutPredicates") fails to find the source file because the file name differs from the JvmName. Other layout-package files do not have this mismatch.
- **Suggested fix scope:** small (one rename).
- **Suggested fix:** rename `Predicates.kt` → `LayoutPredicates.kt`. JvmName stays as-is. Git handles the rename. No content changes.

### AUDIT-CONVENTION-B4-6

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt:96, :113, :133, :149, :159` + `app/src/main/java/net/devemperor/dictate/state/render/ContentAreaController.kt:84` + `OverlayResetHandler.kt:69-70` + `PromptVisibilityController.kt:81`
- **Description:** Two **different** in-body workaround idioms for "unused parameter" coexist in the new files:
  - `ActionResolvers.kt` uses `@Suppress("UNUSED_PARAMETER") services` as a statement-level expression: 5 call sites.
  - `ContentAreaController.kt` / `OverlayResetHandler.kt` / `PromptVisibilityController.kt` use `@Suppress("UNUSED_VARIABLE") val _unused = mode` as a statement-level expression: 4 call sites.
  Both work, but they signal **the same intent** ("this parameter is part of an interface contract I have to honour but I don't actually read it") in two different ways. The convention should pick one. The simpler approach for parameters is to put `@Suppress("UNUSED_PARAMETER")` on the **parameter itself** (annotation-on-parameter form) which compiles cleanly without the dummy-statement body, e.g. `fun render(@Suppress("UNUSED_PARAMETER") state: DictateUiState, @Suppress("UNUSED_PARAMETER") mode: LayoutMode) { ... }`.
- **Why it matters:** Same-operation-two-ways across the new package; future readers will copy whichever they see first and the drift compounds.
- **Suggested fix scope:** small.
- **Suggested fix:** standardise on annotation-on-parameter form. Replace the `@Suppress("UNUSED_VARIABLE") val _unused = mode` lines with the annotation moved to the parameter list; for `ActionResolvers.kt` the `services` parameter takes the same treatment. Removes 9 statement-level dummies from the new code.

### AUDIT-CONVENTION-B4-7

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt:77` + `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:163`
- **Description:** Mixed import style for `RecordingState`. `ActionResolvers.kt` does a fully-qualified `net.devemperor.dictate.state.InsertionTarget.INPUT_CONNECTION` reference at line 77 even though it imports `net.devemperor.dictate.state.RecordingState` at line 10 (so the symbol is in scope). Similarly `LayoutCatalog.kt:163` uses `state.recording !is net.devemperor.dictate.state.RecordingState.Preparing` (fully qualified) at one slot but the `KEYBOARD_TWO_ROW.RECORD` slot on line 76 uses the short-name `RecordingState.Preparing` — the difference is just whether the author manually added the import for that particular file branch. The block-report C12 §Inline-fixed acknowledged this for `LayoutCatalog.kt`. The `InsertionTarget` import is missing.
- **Why it matters:** Stylistic inconsistency. The `LayoutCatalog.kt:163` fully-qualified reference is grep-noisy (a future reader trying to locate all `Preparing` references gets one fully-qualified hit and ten short-name hits) and the `ActionResolvers.kt:77` reference signals "I forgot to add this import" — both readable as drift.
- **Suggested fix scope:** small.
- **Suggested fix:** `ActionResolvers.kt`: add `import net.devemperor.dictate.state.InsertionTarget` and shorten line 77 to `target = InsertionTarget.INPUT_CONNECTION`. `LayoutCatalog.kt`: shorten line 163 to `state.recording !is RecordingState.Preparing` (`RecordingState` is already imported on line 7).

### AUDIT-CONVENTION-B4-8

- **Severity:** Nice-to-have
- **File:** `app/src/main/res/xml/motion_scene_keyboard.xml:148-154` + `app/src/main/res/layout/activity_dictate_keyboard_view.xml:58-64`
- **Description:** The inline-anchor convention (knowledge-doc-format §"Inline anchors") prescribes a stable comment block near the file head listing the spec / ADR pointers. `motion_scene_keyboard.xml` does this well (lines 2-24). `activity_dictate_keyboard_view.xml` adds a header comment block at lines 12-31 — but the **inner** comment about `record_btn` carrying `visibilityMode="ignore"` because LayoutCatalog text/visibility resolution stays authoritative is on the **MotionScene** side (lines 148-151) and not cross-referenced from the matching `record_btn` definition in the layout XML (line 58-64). A reader of the layout XML who sees `android:text="@string/dictate_record"` would not realise the catalog overrides this label at render time. Same applies for `widget_toggle_btn`'s placeholder icon (line 91-98) — the comment names the placeholder but not the path to the catalog slot that sets the real icon resolver.
- **Why it matters:** Cross-file gotcha. Inline-anchors are supposed to bridge `code ↔ doc`; here they bridge MotionScene ↔ doc but not Layout-XML ↔ doc. A reader of `activity_dictate_keyboard_view.xml:58` does not know whether `android:text` matters or is overridden, without cross-reading the catalog.
- **Suggested fix scope:** small (XML comments).
- **Suggested fix:** add one `<!-- Catalog override: @see ... ButtonSlot.textResolver in LayoutCatalog.KEYBOARD_*_RECORD slots -->` comment near the `record_btn` definition and a matching `<!-- Catalog override: @see ... ButtonSlot.iconResolver for LogicalButtonId.WIDGET_TOGGLE -->` near `widget_toggle_btn`. Keeps the layout XML self-documenting.

### AUDIT-CONVENTION-B4-9

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:74` + `LayoutCatalog.kt:107` (XML row comments)
- **Description:** Inline comments inside `KEYBOARD_TWO_ROW` reference the now-deleted physical row containers: `// Row 1 — action_row: ...` (line 69) and `// Row 2 — input_row: ...` (line 108). These are the C13/C15 deletion targets — `action_row` / `input_row` are gone from the layout XML as of C15. Same in the motion_scene_keyboard.xml comment at lines 30, 32, 36, 71, 99. The comments are pedagogical (they preserve the legacy mental model) but they refer to identifiers that no longer exist. The C13/C15 cleanup-deletion convention (audit-prompt #8) asks "no leftover R.id references" — these are not R.id references but they are name-references to deleted containers.
- **Why it matters:** Stale documentation. A new reader running `grep action_row` lands on these comments + `MainButtonsController.kt:501` (KDoc) + `KeyboardStateManager.kt:30, :46` (KDoc) + `DictateInputMethodService.java:675` (comment) — none of which compile to a real reference, but all of which give the impression the identifier exists somewhere. The XML-comment side (Layout-XML line 145-148, "C15 — Removed `action_row` / `input_row` scaffold stubs") is the only place that explicitly notes the deletion.
- **Suggested fix scope:** small.
- **Suggested fix:** rewrite the eight stale references to say "Row 1 (formerly action_row)" / "Row 2 (formerly input_row)" — or just "Row 1 / Row 2" with a single top-of-file note explaining that the legacy names appear in commit history. The MotionScene-side comments already say "action_row chain" — the same approach.

### AUDIT-CONVENTION-B4-10

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:465-472` (the `OVERLAY_5BUTTON` placeholder)
- **Description:** The B5 placeholder (`OVERLAY_5BUTTON` with `rows = emptyList()`) is a deliberate cross-spec anchor per C-5. Convention concern: there is no compile-/test-time guard that the placeholder body actually gets replaced in B5. A future maintainer could forget to fill in the body, and the only signal would be Spec 3 review or a runtime `OverlayBackend.render(...)` no-op. The C12 block-report (Overlooked points section) flags it but the production-code-side has no `error("OVERLAY_5BUTTON body is a B5-placeholder...")` guard. Compare: `ImeViewBackend.render` does have an `error("No view registered for ${slot.logicalId}...")` silent-skip-guard (line 176) — the equivalent for "OverlayBackend received the empty placeholder" is absent.
- **Why it matters:** Convention from the same file: the implementation already uses `error(...)` guards for "this should never happen at runtime" cases; the OVERLAY_5BUTTON placeholder is a "this MUST be replaced by B5 before any OVERLAY_WINDOW backend is wired" case that's structurally identical. Without a guard, the only failure mode is a silently empty overlay.
- **Suggested fix scope:** small.
- **Suggested fix:** add a unit test in `LayoutCatalogTest.kt` asserting `OVERLAY_5BUTTON.rows.isEmpty()` is the current state, with a comment "this assertion will fail when B5 supplies the body — that's the trigger to delete the test". Or wrap the LayoutMode in a `lazy { ... }` block that throws on access if a sentinel flag is unset. The unit-test variant is the lighter-weight option.

## Coverage

- Files audited: 28 production + resources + tests (full B4 diff `git diff f8ba56a..HEAD` walked end-to-end).
- Files skipped (with reason):
  - `tests under .../state/layout/` and `.../state/render/` — read for K-1 / K-4 compliance (no Mockito; Robolectric only where the KDoc justifies it) but not audited for stylistic drift line-by-line. The test code follows the same conventions found in B0-B3 (RobolectricTestRunner + Config sdk=34; `assertEquals` / `assertTrue` from `org.junit.Assert.*`; backticked-name fun tests; hand-rolled fakes — no findings).
  - `KeyboardLayoutUiTest.kt` (androidTest, all `@Ignore`d) — skipped beyond the C15 `@Ignore` reason-string check (it does say "C15-wiring landed — body still skeleton" per the C15 block-report). Body implementation is post-B4.
  - All non-B4 files reached only for cross-SoT verification (e.g. `KeyboardVisibilityPredicates.kt`, `RecordingUiController.kt`) — not audited as B4 deltas.
- Knowledge-skill checkpoints applied:
  - **knowledge-doc-format §Inline anchors** — every new `.kt` carries a module header (`/** ... */` at file top), `@see` to Spec 2 (paths verified — `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md` exists), and `@see` to `docs/decisions/0004-ui-layout-catalog-motionlayout.md` (verified — file present). Anchor paths resolve. One drift: see finding B4-8 (XML inline-anchor coverage).
  - **knowledge-reference / project CLAUDE.md** — DictatePrefs sealed-class rule honoured (no raw-string `sp.getString(...)`); new code is Kotlin (Java touch is only the `DictateInputMethodService.java` C15 wiring, which the convention permits as the IME service is the legacy Java module); AI-Orchestrator + Room patterns out-of-scope for B4.
- MotionLayout convention sub-checks:
  - View-id naming consistent (`button_x` suffix convention) — all 9 buttons follow it (`record_btn`, `resend_btn`, `backspace_btn`, `audio_focus_btn`, `widget_toggle_btn`, `trash_btn`, `space_btn`, `pause_btn`, `enter_btn`).
  - `visibilityMode="ignore"` placement consistent — present on all 9 state-driven buttons in `two_row_state` (base) and `single_row_state` (override). Derive-from for `*_send_mode_state` + `reprocess_staging_state` inherits the property-set; no manual restate needed. `record_btn` (nested inside PulseLayout, not a direct MotionLayout child) has its own `<Constraint android:id="@+id/record_btn"><PropertySet motion:visibilityMode="ignore"/></Constraint>` block at line 152.
  - ConstraintSet IDs follow `<state>_state` naming — five IDs: `two_row_state`, `single_row_state`, `two_row_send_mode_state`, `single_row_send_mode_state`, `reprocess_staging_state`. Consistent.
  - Transition durations — 250 ms (chain reshape) / 200 ms (subtle) per Spec 2 §7.1. Five `Transition` elements; reverse direction inherits from MotionLayout default. Consistent.
  - XML attribute ordering — `android:id`, `android:layout_*`, `motion:layout_*`, `<PropertySet>` block — consistent across all Constraint elements.

## Out-of-scope observations (for VAL-SANITY consolidator)

- (logic) `KeyboardLayoutManager.computeLayoutMode(state)` returns `catalog.OVERLAY_5BUTTON` for `ViewMode.WIDGET` and `ViewMode.HOVER` (line 132). The OVERLAY_5BUTTON is currently a placeholder with `rows = emptyList()` — if Spec 3 / B5 doesn't ship a body and a state emits `viewMode = WIDGET` today, the manager returns the placeholder and `ContentAreaController` (not attached anyway) would render against zero slots. Belongs to `audit-logic`.
- (test) `DictatePipelineService.buildLayoutStrings` is not directly unit-tested as a standalone factory; coverage comes via `DictatePipelineServiceLayoutWiringTest`. A focused test would catch the `Locale`-omission B4-4 as a property of the produced strings under a non-US locale. Belongs to `audit-test`.
- (plan-and-api) The C12 placeholder for `PipelineUiState.Running.completedSteps / totalSteps / elapsedMs` (resolver passes `0, 0, autoEnter, 0L`) — the resolver compiles today because the fields are not on the state class. Once they land, the resolver call site needs an update. Block-report flags this as IMPL-2 carry-over; the audit-plan-and-api topic owns the verification.
