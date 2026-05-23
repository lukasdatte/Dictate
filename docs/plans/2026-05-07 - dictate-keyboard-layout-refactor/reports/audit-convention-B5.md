# Audit Report: convention (Block 5, scope: full-block)

**Agent-ID:** B5-AUDIT-CONVENTION
**Date:** 2026-05-15
**Knowledge skills used:** knowledge-reference, knowledge-doc-format (§Inline anchors)
**Files inspected:** 23 (10 production Kotlin + 1 onboarding Activity + 4 XML res + Pref sealed class + AndroidManifest + DictatePipelineService diff + 8 test files + 3 locale string files)

## Summary

- Critical: 0
- Important: 1
- Nice-to-have: 3

## Findings

### AUDIT-CONVENTION-B5-1

- **Severity:** Important
- **File:** `app/src/main/res/values/strings.xml:419-442` (11 new overlay strings); missing in `app/src/main/res/values-de/strings.xml`, `app/src/main/res/values-es/strings.xml`, `app/src/main/res/values-pt/strings.xml`
- **Description:** B5 added 11 new user-facing strings — 6 overlay-button content-descriptions (`overlay_record_cd`, `overlay_send`, `overlay_send_cd`, `overlay_pause_cd`, `overlay_trash_cd`, `overlay_close_cd`) and 6 onboarding strings (`overlay_perm_onboarding_title`, `overlay_perm_explainer`, `overlay_perm_later`, `overlay_perm_grant`, `overlay_perm_onboarding_granted`, `overlay_perm_onboarding_pending`) — **only to `values/strings.xml`**. The project ships three additional locales (`values-de`, `values-es`, `values-pt`) which carry full translations for every other user-facing string (e.g. `dictate_status_*`, `dictate_settings_overlay_characters_title`). None of the 11 new strings were added to any locale file. German/Spanish/Portuguese users will see the English content-descriptions (TalkBack) and the entire onboarding Activity in English.
- **Why it matters:** This is the **third recurrence of an established drift class** (B1-VAL-W1 F-6 missing translations; B3 `dictate_status_*` missing translations). Content-descriptions are accessibility surface (TalkBack reads them aloud); the onboarding Activity is a full user-facing screen. A recurring drift class that keeps re-appearing block after block indicates the localization step is not part of the implementer's string-addition reflex. Per the severity bar this is explicitly classed Important ("missing locale translations (recurring drift class B1-F6/B3)").
- **Suggested fix scope:** medium — add the 11 keys to `values-de/`, `values-es/`, `values-pt/strings.xml` with translations. `overlay_send` ("Send") and the `*_cd` strings are short; the onboarding strings need careful translation. Exclude any `translatable="false"` (none of the 11 are marked so).
- **Suggested fix:** Translate all 11 keys into the three locale files, matching the existing translation coverage pattern. Recommend the orchestrator also flag this drift class for a process note since it is now 3-for-3 across blocks.

### AUDIT-CONVENTION-B5-2

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayDragController.kt:11-146`
- **Description:** KDoc-attachment inconsistency. The 79-line detailed class KDoc (lines 11-89) describes `OverlayDragController`'s responsibilities (click-vs-drag differentiation, threshold, persistence cascade, mid-drag detach, all `@property` tags for `ctx`/`view`/`window`/`paramsHolder`/`positionMapper`/`onPositionPersist`). However it is immediately followed by a second KDoc block (lines 90-96) for `OverlayDragControllerFactory`, so per Kotlin doc-attachment rules the big 79-line block documents the **factory interface** (line 97), not the controller. The actual `OverlayDragController` class declaration (line 139) has **no KDoc at all** — it is a bare `class OverlayDragController(`. Every other B5 overlay file (`OverlayWindow.kt`, `OverlayPermissionGate.kt`, `OverlayPositionMapper.kt`, `OverlayLayoutParamsFactory.kt`) consistently puts the interface KDoc on the interface and the impl KDoc on the impl; this file is the lone same-operation-two-ways outlier and additionally has `@property` tags pointing at constructor parameters that don't exist on the type the KDoc is attached to.
- **Why it matters:** Inline-anchor convention (knowledge-doc-format §Inline anchors — "Module / Class header"): the class-responsibility doc should be attached to the class it describes. As written, an IDE quick-doc on `OverlayDragController` shows nothing; quick-doc on `OverlayDragControllerFactory` shows the controller's whole behaviour spec including `@property` tags for the controller's constructor. This is a structural ordering bug, not just a polish issue, but it is non-functional and isolated to one file → Nice-to-have.
- **Suggested fix scope:** small — move the 79-line KDoc block (lines 11-89) to immediately precede `class OverlayDragController(` (line 139), keeping the factory KDoc (90-96) on the factory interface. The file would then match the interface-then-impl KDoc placement used by the other four overlay files.
- **Suggested fix:** Relocate the detailed KDoc to the class it documents.

### AUDIT-CONVENTION-B5-3

- **Severity:** Nice-to-have
- **File:** `app/src/main/res/layout/activity_overlay_permission_onboarding.xml:71-72`
- **Description:** The `xmlns:tools` namespace declaration is placed on the **last child** `TextView` (`overlay_perm_onboarding_status_tv`) rather than on the root `ConstraintLayout` element. Every other layout XML in the project that uses `tools:` declares `xmlns:tools="http://schemas.android.com/tools"` on the root element alongside `xmlns:android` / `xmlns:app`. Declaring it mid-tree on a leaf works but is the only instance of this placement in the res tree and reads as accidental (the `tools:ignore="MissingPrefix"` was added reactively to silence a lint warning). Also note the root declares `xmlns:app` but the only `app:` usages are constraint attributes, which is fine; the inconsistency is purely the tools-namespace placement.
- **Why it matters:** File-layout convention consistency (CLAUDE.md / project XML convention). A future editor adding a second `tools:` attribute on a different element would have to re-declare the namespace or move it, which is exactly the drift this finding flags.
- **Suggested fix scope:** small — move `xmlns:tools="http://schemas.android.com/tools"` up to the root `<androidx.constraintlayout.widget.ConstraintLayout>` element next to `xmlns:android`/`xmlns:app`, leaving only `tools:ignore="MissingPrefix"` on the status TextView.
- **Suggested fix:** Hoist the tools namespace to the root element.

### AUDIT-CONVENTION-B5-4

- **Severity:** Nice-to-have
- **File:** `app/src/test/java/net/devemperor/dictate/state/render/overlay/OverlayLayoutParamsFactoryTest.kt`, `OverlayBackendTest.kt`, `DefaultOverlayPositionMapperTest.kt`, `OverlayDragControllerTest.kt`
- **Description:** Documentation-vs-reality note (not a K-4 violation). The audit brief listed `OverlayLayoutParamsFactoryTest`, `OverlayBackendTest`, `OverlayDragControllerTest`, `OverlayPositionMapperTest` as expected **pure JVM** and only four files as expected Robolectric. In reality all four "expected pure JVM" files run under `@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])`. **This is correct and K-4-compliant**: these types unavoidably touch real Android framework classes that cannot be constructed on a bare JVM — `WindowManager.LayoutParams` (LayoutParamsFactory), `View`+`MotionEvent`+`ViewConfiguration` (DragController), `DisplayMetrics`+`View` (PositionMapper), `LayoutInflater`+`View` (Backend). Robolectric is genuinely required, not a substitute for a hand-rolled fake. Only `OverlayPermissionObserverTest` is pure JVM (it takes a `(Action)->Unit` lambda + `FakeOverlayPermissionGate`, no framework types) — consistent with the DIP design noted in C17 deviation D-3. The finding is documentation-only: the block-report / chunks expectation of "pure JVM" for the four framework-coupled tests was inaccurate; the implementation made the correct call. No code change needed; flagged so the consolidator does not re-raise it as a K-4 violation.
- **Why it matters:** Prevents a false-positive K-4 finding downstream. The Robolectric usage is justified per the K-4 rule (Android framework genuinely needed).
- **Suggested fix scope:** small (documentation only — no code change). Optionally correct the block-report's test-classification wording.
- **Suggested fix:** No code fix. Note the classification correction in validated-findings so it is not re-litigated.

## Convention checkpoints — PASS (no findings)

- **New code in Kotlin** — all 10 new overlay types + onboarding Activity are Kotlin. PASS.
- **Pref sealed-class, no raw-string keys** — `Pref.OverlayPositionPortrait{X,Y}`, `Pref.OverlayPositionLandscape{X,Y}`, `Pref.OverlayOnboardingShown`, `Pref.OverlayOnboardingDismissed` all declared in `DictatePrefs.kt` (lines 123-128) as typed `Pref<T>` objects. `DefaultOverlayPermissionGate` reads/writes via `prefs.get(Pref.…)` / `prefs.edit().put(Pref.…)`. No raw `getString("overlay_…")` anywhere. PASS — no Critical.
- **K-1 (no Mockito/MockK)** — zero `mockito`/`mockk`/`Mockito`/`MockK` imports in any B5 test. Hand-rolled fakes only (`FakeOverlayWindow`, `FakeOverlayPermissionGate`, recording lambdas). PASS — no Critical.
- **Package layout** — all rendering/permission types under `state.render.overlay`; the user-facing Activity under `onboarding` (consistent with the existing `onboarding/` package convention). Consistent.
- **Overlay-prefix naming** — `OverlayWindow`/`AndroidOverlayWindow`, `OverlayPermissionGate`/`DefaultOverlayPermissionGate`/`NoOverlayPermissionGate`, `OverlayPositionMapper`/`DefaultOverlayPositionMapper`, `OverlayLayoutParamsFactory`/`DefaultOverlayLayoutParamsFactory`, `OverlayDragController`/`OverlayDragControllerFactory`/`DefaultOverlayDragControllerFactory`, `OverlayBackend`, `OverlayPermissionObserver`. Interface + `Default…`-impl pattern is consistent across the whole package. Consistent.
- **XML view-id ↔ LogicalButtonId** — `overlay_record_btn`/`overlay_send_btn`/`overlay_pause_btn`/`overlay_trash_btn`/`overlay_close_btn` map 1:1 to `LogicalButtonId.OVERLAY_RECORD/SEND/PAUSE/TRASH/CLOSE` in `OverlayBackend.inflateAndAttach()`. Consistent.
- **styles_overlay.xml / overlay_background.xml** — `OverlayButton.Primary` / `OverlayButton.Icon` follow the dotted-style Material 3 parent convention; the drawable uses `?attr/colorSurface` / `?attr/colorOutlineVariant` theme attrs consistent with the rest of the res tree. Consistent.
- **Error-handling pattern** — uniform `try { … } catch (t: Throwable) { Log.w(TAG, …, t) }` for defensive teardown (`OverlayBackend.teardownOverlay`, `DictatePipelineService` state-collect isolation); typed-exception catch per failure-mode in `AndroidOverlayWindow` (`BadTokenException` on attach, `IllegalArgumentException` on update/detach) with a `Log.w(TAG, …)` per branch. `private companion object { const val TAG }` used consistently. No `runCatching`-vs-`try/catch` mixed-style drift. Consistent.
- **WindowManager flags (Spec 3 §4.4)** — `OverlayLayoutParamsFactory` sets exactly `TYPE_APPLICATION_OVERLAY` (API≥26) / `TYPE_PHONE` (fallback), `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_IN_SCREEN | FLAG_HARDWARE_ACCELERATED`, `PixelFormat.TRANSLUCENT`, `Gravity.TOP or Gravity.START`, x/y=0, `windowAnimations=0` — matches the §4.4 truth-table exactly, with the table inlined as KDoc. Consistent.
- **Inline-anchor compliance** — every new public type carries a module/class KDoc header + `@see` to the Spec 3 section (§4.1/§4.2/§4.3-5/§4.6/§4.7/§5.0/§5.1/§5.2) and, for `OverlayBackend`, `@see` ADR-0004 §3 + ADR-0005. All anchor paths resolve on disk (Spec 3 file, ADR-0004, ADR-0005 all exist). Anchor style is uniform. PASS (one attachment defect tracked separately as B5-2).
- **Cross-doc SSoT** — `OverlayPositionMapper` is the single home for `[0..1]`↔pixel conversion + `effectiveWidth`/`effectiveHeight`; no duplicate conversion math anywhere else in `state/`. `LayoutCatalog.OVERLAY_5BUTTON` is the single layout-mode SoT (`LayoutModeId.OVERLAY_5BUTTON` is the enum case it derives from, not a duplicate definition; `KeyboardLayoutManager` / `OverlayBackend` only reference it). No duplicate SoT. PASS — no Important duplicate-SoT finding.
- **AndroidManifest** — `OverlayPermissionOnboardingActivity` declared `exported="false"`; `SYSTEM_ALERT_WINDOW` permission declared. Consistent with project manifest conventions.

## Coverage

- Files audited: `OverlayWindow.kt`, `OverlayLayoutParamsFactory.kt`, `OverlayPermissionGate.kt`, `DefaultOverlayPermissionGate.kt`, `OverlayPermissionObserver.kt`, `OverlayBackend.kt`, `OverlayDragController.kt`, `OverlayPositionMapper.kt`, `OverlayPermissionOnboardingActivity.kt`, `LayoutCatalog.kt` (diff), `ActionResolvers.kt` (diff), `TextResolvers.kt` (diff), `DictatePipelineService.kt` (overlay diff), `DictatePrefs.kt` (Overlay prefs), `AndroidManifest.xml`, `overlay_5button_layout.xml`, `styles_overlay.xml`, `overlay_background.xml`, `activity_overlay_permission_onboarding.xml`, `values/strings.xml` + `values-de/es/pt/strings.xml`, 8 B5 test files (runner + import scan).
- Files skipped (with reason): none in B5 scope.
- Knowledge-skill checkpoints applied: knowledge-doc-format §Inline anchors (module/class header, `@see` plan/ADR tag, SSoT anti-redundancy rule); knowledge-reference (interface + Default-impl pattern, single-SoT for shared conversion logic).

## Out-of-scope observations (for the consolidator)

- The audit-brief test-classification (expected pure-JVM vs Robolectric) does not match the implementation; this is documentation-vs-reality, not a K-4 defect — captured as B5-4 specifically so AUDIT-TEST / the consolidator does not re-raise it as a Critical K-4 violation.
- `DefaultOverlayLayoutParamsFactory` holds an `@Suppress("UNUSED_PARAMETER") private val ctx` — currently unused; documented in KDoc as a future density hook. Borderline dead-parameter; not flagged because it is explicitly justified in the property KDoc and the suppression is intentional. Logic-topic agent may have a view.
