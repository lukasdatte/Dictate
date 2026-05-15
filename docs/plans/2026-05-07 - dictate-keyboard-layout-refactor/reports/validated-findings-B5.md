# Validated Findings — Block 5 (Floating-Overlay, final implementation block)

**Agent-ID:** B5-VAL-SANITY
**Date:** 2026-05-15
**Source audits:**
- `reports/audit-plan-and-api-B5.md` — 2 Crit / 4 Imp / 2 NTH
- `reports/audit-convention-B5.md` — 0 Crit / 1 Imp / 3 NTH
- `reports/audit-logic-B5.md` — 1 Crit / 4 Imp / 3 NTH
- `reports/audit-test-B5.md` — 0 Crit / 1 Imp / 1 NTH

## Summary

- 🟢 valid + auto-fixable: 9 (Critical: 0, Important: 5, Nice: 4)
- 🟡 valid + research-needed: 2 (Critical: 2, Important: 0, Nice: 0)
- ❌ eliminated: 4 (2 informational-no-fix, 1 false-positive, 1 merged-away)

Net distinct findings after de-dup: **13** (2 Critical 🟡, 5 Important 🟢, 4 Nice 🟢, 2 carry/FP ❌).

## Cross-cut patterns

1. **IME-side activation wiring is the single dominant gap.** The two Criticals (B5-CRIT-A dispatch of `OnImeViewShown/Hidden`, B5-CRIT-B permission-onboarding-trigger) plus Important F-3 (`observer.refresh()`) and Important F-4 (busy-retry loop, which is a *symptom* of the missing refresh) all converge on one surface: the IME (`DictateInputMethodService.java`) was never wired to drive the state machine. The Triangle-FSM, OverlayModule, ViewModeModule reducer arms, the attach/detach collapse rule, and the onboarding Activity are all implemented and tested — but **inert in production** because nothing in the IME produces the actions or refreshes permission state. This is the most consequential pattern of the whole run: the plan's central feature is structurally complete and unit-green but functionally unreachable end-to-end. → **single combined research topic** `b5-ime-activation-wiring`.

2. **Locale-translation drift, 3rd recurrence.** CONVENTION-B5-1 (11 new strings only in `values/`) is the same drift class as B1-VAL-W1 F-6 and B3 `dictate_status_*`. 3-for-3 across blocks → flag for a process note in addition to fixing.

3. **Inline-anchor / documentation-consistency cluster in the overlay backend pair.** CONVENTION-B5-2 (mis-attached `OverlayDragController` KDoc), LOGIC-B5-7 (drag-params-stability invariant implicit), LOGIC-B5-8 (detach flag-ordering relies on un-cited cross-class guarantee) all live in `OverlayDragController.kt` / `OverlayBackend.kt` / `DictatePipelineService.kt#syncOverlayBackendAttachment` and are documentation-only. Domain-bundle candidate for one repair pass.

4. **`OverlayPositionMapper` / orientation correctness cluster.** LOGIC-B5-3 (asymmetric clamp denominator) and LOGIC-B5-4 (orientation split-read race) both concern the drag-persist coordinate path. F-6 is a pure mechanical fix; F-7 touches the controller↔backend SRP boundary (kept 🟢 but with a documented constraint — see finding).

## Repair-Wave Status (B5-VAL-REPAIR, 2026-05-15)

All 13 findings **fixed** in one wave (2 🟡 Critical research-resolved +
5 🟢 Important + 4 🟢 NTH + 2 ❌ informational left as-is). ADR-0005
Decision-History amended. `./gradlew test` (debug + release) green;
`--rerun-tasks` green twice (F-9 flaky-stability confirmed);
`./gradlew assembleDebug` green. Full detail + T1–T7 trace in the
block-report `### Block-Validate Repair Wave 1 (B5-VAL-REPAIR)` and
`### Validate-Fixes Self-Check (B5-VAL-W1)`.

| ID | Sev | Status | Resolution (one line) |
|----|-----|--------|-----------------------|
| F-1 | Critical | **fixed** | `onStartInputView`→`OnImeViewShown`; `onFinishInputView` early-returns refactored to a single tail → `OnImeViewHidden` fires on the recording-active/pipeline-running paths too. |
| F-2 | Critical | **fixed** | `Action.OverlayAction.ShowOverlayOnboarding` + `resolveWidgetToggleAction` (permission-aware) + Spec 3 §5.4 auto-cleanup cascade + IME-owned info-bar (`OverlayOnboardingObserver` bridge) + Settings deep-link from the Grant handler. |
| F-3 | Important | **fixed** | `overlayPermissionObserver.refresh()` in `onStartInputView`, BEFORE the `OnImeViewShown` dispatch. |
| F-4 | Important | **fixed** | `Effect.NotifyOverlayPermissionRequired` + `NotificationStatus.OverlayPermissionRequired` + `RequestOverlayPermissionNotification` action, emitted by the permission-loss cascade. |
| F-5 | Important | **fixed** | 12 overlay strings added to `values-de/-es/-pt`. |
| F-6 | Important | **fixed** | Shared `freeArea()` helper, identical `coerceAtLeast(1)` floor both directions → round-trip identity at the zero-free-area boundary. |
| F-7 | Important | **fixed** | Orientation snapshotted once at `ACTION_DOWN` via `orientationProvider`, threaded through `onPositionPersist(portrait, …)`; controller/factory KDoc SRP-narrative updated. |
| F-8 | Important | **fixed** | `F-8 both-in-flight HOVER-close-from-pipeline-done` Robolectric verification test (asserts no MAX_CASCADE_DEPTH trip + settles KEYBOARD). |
| F-9 | Important | **fixed** | `DictateDatabase.resetForTest(context)` (drops singleton + deletes file-backed DB) + `@Before`/`@After` full-reset in the migration test and the amplifier transition test. |
| F-10 | NTH | **fixed** | 79-line controller KDoc relocated from the factory interface to `class OverlayDragController(` (carrying the F-7 contract update). |
| F-11 | NTH | **fixed** | `xmlns:tools` hoisted to the root `ConstraintLayout` in `activity_overlay_permission_onboarding.xml`. |
| F-12 | NTH | **fixed** | Detach-before-params-swap invariant comment added in `OverlayDragController.detach()`. |
| F-13 | NTH | **fixed** | `KeyboardLayoutManager.detachBackend` remove-before-detach ordering comment added on the `syncOverlayBackendAttachment` detach branch. |

## Findings

### F-1 — IME never dispatches `OnImeViewShown` / `OnImeViewHidden` (Triangle-FSM has no production trigger)

- **Classification:** 🟡 valid + research-needed
- **Severity:** Critical
- **Was:** AUDIT-PLAN-AND-API-B5-1 (standalone; corroborated by AUDIT-PLAN-AND-API-B5-8 dependency note and AUDIT-LOGIC coverage note "T7 correct *given* the FSM is driven")
- **Files:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` (`onStartInputView` ~1650, `onFinishInputView` ~1053 — dispatch absent); consumes `app/src/main/java/net/devemperor/dictate/state/modules/ViewModeModule.kt:90,100` (dead reducer arms)
- **Description:** Zero `ViewModeAction` / `OnImeView*` references in `DictateInputMethodService.java`; the IME file is not in the B5 diff (`git diff 74f9dd3..HEAD` does not touch it). `ViewModeModule.reduce` arms for `OnImeViewShown`/`OnImeViewHidden` exist but are dead code — nothing in production produces these actions. Consequence: T3 (KEYBOARD→HOVER), T5 (HOVER→KEYBOARD), T6 (HOVER→WIDGET) never fire; the HOVER auto-overlay (Spec 3 §10 acceptance "Bei View-hidden + Recording-aktiv: HOVER-Overlay erscheint automatisch") is structurally unreachable; the Geist-Widget-Bug fix (T7) is correct but latent because HOVER is never entered. Scope is nominally Spec 1 / B1-B2 (IME→orchestrator dispatch wiring) but B5 is the final implementation block and its central acceptance depends on it — surfaced here for the repair-sub-phase / Phase 4.
- **Why research:** The exact IME lifecycle hook is a design decision, not a mechanical edit. Candidates: `onStartInputView` vs `onWindowShown` vs `onCreateInputView` for the *shown* dispatch; the symmetric *hidden* hook (`onFinishInputView` vs `onWindowHidden`). The choice must be justified against IME-lifecycle semantics (restart/reconfigure cycles, `onStartInputView(restarting=true)`, window-vs-input-view distinction), Spec 1 §11 (lines 2670–2676), Spec 3 §7, and ADR-0005, and validated against the Triangle-FSM truth-table (T3/T5/T6 must fire exactly once per real view show/hide, not on spurious restarts).
- **Research topic:** `b5-ime-activation-wiring` (combined — see F-2 and F-3)
- **Domain bundle candidate:** `DictateInputMethodService.java` IME-activation surface (with F-2, F-3)

### F-2 — Permission-onboarding-trigger path unreachable (in-IME info-bar + onboarding launch + no-op Effect)

- **Classification:** 🟡 valid + research-needed
- **Severity:** Critical
- **Was:** AUDIT-PLAN-AND-API-B5-2 ∪ AUDIT-LOGIC-B5-1 (**merged** — same root cause, two audit angles)
- **Files:**
  - `app/src/main/java/net/devemperor/dictate/onboarding/OverlayPermissionOnboardingActivity.kt` (implemented, Manifest-declared, never started from production)
  - `app/src/main/java/net/devemperor/dictate/state/render/overlay/DefaultOverlayPermissionGate.kt` (`shouldShowOnboarding()` implemented, never called from production)
  - `app/src/main/java/net/devemperor/dictate/state/modules/OverlayModule.kt:191-198` (`OpenOverlayPermissionSettings` runEffect is a documented no-op placeholder)
  - `app/src/main/java/net/devemperor/dictate/state/modules/ViewModeModule.kt:116-117` (`ToggleViewModeWidget` → `null` when `!ctx.global.overlay.hasPermission`)
  - `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:110` + `ActionResolvers.kt` (no overlay-permission resolver on disabled `WIDGET_TOGGLE`); in-IME info-bar `overlay_permission_infobar.xml` / `ImeViewBackend.bindPermissionInfoBar` (Spec 3 §5.3) does not exist
- **Description:** When a permission-less user (the default first-run state) taps the keyboard `WIDGET_TOGGLE`, the resolver emits `ToggleViewModeWidget`; the reducer returns `null` (silent no-op); nothing dispatches `RequestOverlayPermission`, nothing sets `state.overlay.onboardingPending = true`, there is no info-bar render binding, and `OpenOverlayPermissionSettings` reaches a dead no-op Effect. Net effect: a 100%-silent dead button with zero feedback and **no path to grant the permission** — the entire user entry into the floating-overlay feature is unreachable. C17's chunks.json scope ("Onboarding-UI im IME-View, Activity-Result-Handling", §5.3/§5.4) was deferred C17→C18, and C18 forwarded it to "Phase 4 Integration" — it fell through every chunk and is undelivered at block end. Fails Spec 3 §10 acceptance #1 ("Permission-Onboarding läuft beim ersten Widget-Toggle-Versuch") and #10 (notification-only fallback).
- **Why research:** Spec 3 §5.4 leaves the trigger-arm design explicitly open ("Auslöser TBD: entweder ein neuer `Action.OverlayAction.RequestOverlayPermission`-Arm der `onboardingPending = true` setzt, oder ein expliziter `ShowOnboarding`-Reducer-Arm"). The fix needs: (a) the trigger-arm decision (resolved per §5.4), (b) an overlay-permission resolver on the disabled `WIDGET_TOGGLE` dispatching it when `!state.overlay.hasPermission`, (c) `ImeViewBackend.bindPermissionInfoBar` render binding + `overlay_permission_infobar.xml` (§5.3), (d) `OpenOverlayPermissionSettings` runEffect actually launching the Settings intent / `OverlayPermissionOnboardingActivity`. Cross-references C17 + C18 + ImeViewBackend (B4) + OverlayModule (C5/B2). Where in the IME the info-bar lives and how the tap-without-permission path reaches it must be researched.
- **Research topic:** `b5-ime-activation-wiring` (combined with F-1, F-3 — same IME-activation surface)
- **Domain bundle candidate:** `DictateInputMethodService.java` IME-activation surface (with F-1, F-3)

### F-3 — IME-side `OverlayPermissionObserver.refresh()` not wired (granted permission not picked up until cold-start) + unbounded inflate-retry symptom

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Was:** AUDIT-PLAN-AND-API-B5-4 ∪ AUDIT-LOGIC-B5-2 (**merged** — the "unbounded inflate+addView retry on runtime permission-revoke" is the production symptom of the same missing IME-side `observer.refresh()`)
- **Files:** `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayPermissionObserver.kt`; `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` (no `observer.refresh()` call); `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayBackend.kt:226-233` (retry symptom)
- **Description:** Spec 3 §5.0 specifies `OverlayPermissionObserver.refresh()` called from the IME's `onCreateInputView`/`onStartInputView` so a user returning from the system Settings page is detected promptly. Only the Service-side `init()` cold-start dispatch is wired (`DictatePipelineService.kt:601`). C17 + C18 both explicitly forwarded the IME-side `refresh()` wiring. Two consequences: (1) after the user grants overlay permission and returns, `state.overlay.hasPermission` is not refreshed until the next process cold-start — the just-granted permission does not take effect in the live session (compounds F-2: even if onboarding launched, the grant wouldn't be picked up); (2) on a runtime permission-*revoke* while WIDGET/HOVER is active, `OverlayBackend.render()` re-enters `inflateAndAttach()` every pipeline emit (potentially many/sec during transcription), busy-retrying inflate+addView with no upper bound and WARN-spamming, because nothing flips `hasPermission` to drive the recovery cascade.
- **Suggested fix:** Add `pipeline?.overlayPermissionObserver?.refresh()` (binder accessor exists) in the IME's `onStartInputView` (place alongside the F-1/F-2 IME dispatch wiring — same lifecycle hook, same file). The IME-side refresh closes both the grant-pickup loop and the revoke busy-retry loop (the observer flip drives `OverlayModule.onCrossModuleStateChange`'s permission-loss cascade). This is mechanical given the F-1 research resolves which IME lifecycle hook is used; no separate research needed — fold the one-line `refresh()` call into the same repair as F-1/F-2. (If the orchestrator prefers a backend-local belt-and-suspenders latch, AUDIT-LOGIC-B5-2 notes that as an alternative, but the IME `refresh()` is the spec-mandated primary fix and is sufficient.)
- **Domain bundle candidate:** `DictateInputMethodService.java` IME-activation surface (with F-1, F-2) — fix in the same wave

### F-4 — `NotifyOverlayPermissionRequired` Effect missing (no permission-free notification fallback per Spec 3 §9)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Was:** AUDIT-PLAN-AND-API-B5-3
- **Files:** `app/src/main/java/net/devemperor/dictate/state/modules/OverlayModule.kt:69-83` (Effect sealed interface — Effect absent), `:253-259` (permission-loss cascade emits only `SetViewMode(KEYBOARD)`), `:191-198` (`OpenOverlayPermissionSettings` no-op)
- **Description:** Spec 3 §4.8 declares the canonical `OverlayModule.Effect` set including `NotifyOverlayPermissionRequired` ("Issue 3.1.3 Notification-Action"). The implemented `Effect` sealed interface is missing it entirely. The permission-loss cascade emits only `SetViewMode(KEYBOARD)`; Spec 3 §4.8 explicitly states "Notification-Action emittiert via Effect (siehe runEffect)" and §9 Notification-Fallback is the documented permission-free UX (deliberate O7 architecture decision). A runtime permission revoke silently drops the overlay with no notification — violates Spec 3 §9 + §10 acceptance #10. (The §4.8 EffectFailure-convention block — no `reduceFailure` override — was correctly honoured and is intentional; not part of this finding.)
- **Suggested fix:** Add the `NotifyOverlayPermissionRequired` Effect to the sealed interface, add a `services.notifications.showPermissionRequired()` runEffect arm, and emit it in the permission-loss cascade alongside `SetViewMode(KEYBOARD)`. Small, self-contained within `OverlayModule.kt` + the notification service surface; clear from Spec 3 §4.8/§9 with no open design question.
- **Domain bundle candidate:** `OverlayModule.kt` (independent of the IME-activation bundle)

### F-5 — Locale translations missing for 11 new overlay strings (3rd recurrence of drift class B1-F6 / B3)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Was:** AUDIT-CONVENTION-B5-1
- **Files:** `app/src/main/res/values/strings.xml:419-442` (11 new strings); missing in `app/src/main/res/values-de/strings.xml`, `app/src/main/res/values-es/strings.xml`, `app/src/main/res/values-pt/strings.xml`
- **Description:** B5 added 11 user-facing strings — 6 overlay-button content-descriptions (`overlay_record_cd`, `overlay_send`, `overlay_send_cd`, `overlay_pause_cd`, `overlay_trash_cd`, `overlay_close_cd`) and 6 onboarding strings (`overlay_perm_onboarding_title`, `overlay_perm_explainer`, `overlay_perm_later`, `overlay_perm_grant`, `overlay_perm_onboarding_granted`, `overlay_perm_onboarding_pending`) — only to `values/strings.xml`. The project ships `values-de`, `values-es`, `values-pt` with full coverage of every other user-facing string. Content-descriptions are TalkBack accessibility surface; the onboarding strings are a full user-facing screen. Third recurrence of an established drift class (B1-VAL-W1 F-6, B3 `dictate_status_*`).
- **Suggested fix:** Add all 11 keys to `values-de/`, `values-es/`, `values-pt/strings.xml` with translations matching the existing coverage pattern. None of the 11 are `translatable="false"`. **Process note for the orchestrator:** flag this drift class as 3-for-3 across blocks — the localization step is not part of the implementer's string-addition reflex; recommend a standing checklist item.
- **Domain bundle candidate:** the three `values-*/strings.xml` locale files

### F-6 — `OverlayPositionMapper` asymmetric clamp denominator breaks round-trip identity at zero-free-area boundary

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Was:** AUDIT-LOGIC-B5-3
- **Files:** `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayPositionMapper.kt:92-95` (`normalizedToPixels`, `coerceAtLeast(0)`) vs `:112-116` (`pixelsToNormalized`, `coerceAtLeast(1)`)
- **Description:** `normalizedToPixels` uses `maxX = (screenW - viewW).coerceAtLeast(0)` so a screen-filling view maps every `normX` to `px = 0`; `pixelsToNormalized` uses `coerceAtLeast(1)`. The inverses disagree at the degenerate denominator: `normalizedToPixels(1.0)` → `0` but `pixelsToNormalized(0)` → `0.0`. A drag ending with the view exactly screen-sized persists `0.0` even though `OverlayState.positionPortraitX` defaults to `1.0f` (right-edge anchor) — silently rewrites the persisted anchor from right-edge to left-edge, moving the overlay across the screen on the next orientation/session. The mapper KDoc claims it is the single SoT that "keeps the two call-sites in sync"; the asymmetric floor violates that.
- **Suggested fix:** Introduce a shared `private fun freeArea(screen: Int, view: Int): Int = (screen - view)` and apply one identical zero-guard policy in both directions (recommend both `coerceAtLeast(1)`, or a symmetric zero-case guard so the round-trip is identity at the boundary). Purely mechanical; the audit's suggestion is unambiguous.
- **Domain bundle candidate:** `OverlayPositionMapper.kt` (with F-7 — same coordinate-persist path)

### F-7 — Orientation split-read race between drag-end `pixelsToNormalized` and backend `isPortraitOrientation()` bucket selection

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Was:** AUDIT-LOGIC-B5-4
- **Files:** `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayBackend.kt:316-330` (`onPositionPersist`) + `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayDragController.kt:247-250` (`persistCurrentPosition`)
- **Description:** The drag controller computes the normalised position via `positionMapper.pixelsToNormalized(...)` (using pre-rotation `displaySize()` metrics) and hands `(normX, normY)` to `onPositionPersist`. The backend lambda then *independently* reads `isPortraitOrientation()` to choose the pref bucket. These are two separate reads of `ctx.resources.configuration`. A config change landing between them computes the normalised value against the old geometry but persists it into the new orientation's bucket → corrupted position in the wrong bucket. The R.18 mid-drag-detach path (`detach()` → `persistCurrentPosition` from `teardownOverlay()`, which can be invoked by a config-change-triggered transition) has the same split-read shape.
- **Suggested fix:** Capture the orientation **once** at gesture start (`ACTION_DOWN`) inside `OverlayDragController` and thread it through `onPositionPersist` together with the coords, so the bucket and the geometry that produced the normalised value come from the same configuration snapshot. **Constraint to honour (don't re-research, but respect it):** the controller KDoc currently asserts "the controller does not know which orientation it's in (orientation discrimination lives in the backend)" — the C18 author drew that SRP boundary deliberately. The fix changes that boundary; the implementer must update the controller + factory KDoc to document the new contract (orientation snapshot captured at ACTION_DOWN and passed through the persist callback) so the SRP rationale stays coherent. The change itself is mechanical (add an orientation field captured on `ACTION_DOWN`, widen the `onPositionPersist` signature); kept 🟢 because the audit's fix direction is concrete and unambiguous — only the doc/SRP-narrative update needs care, not a research probe.
- **Domain bundle candidate:** `OverlayPositionMapper.kt` / `OverlayDragController.kt` / `OverlayBackend.kt` coordinate-persist path (with F-6)

### F-8 — Unverified `MAX_CASCADE_DEPTH=8` budget for both-in-flight HOVER-close-from-pipeline-done cascade

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Was:** AUDIT-LOGIC-B5-5
- **Files:** `app/src/main/java/net/devemperor/dictate/state/modules/OverlayModule.kt:239-251` (HOVER→KEYBOARD cancel-cascade, F-7-internal fix) + `app/src/main/java/net/devemperor/dictate/core/DictateOrchestrator.kt:374-386` (depth machinery)
- **Description:** The HOVER→KEYBOARD cancel-cascade emits an additive list (`SuppressAutoOverlayUntilNextSession`, conditional `CancelRecording`, conditional `CancelPipeline`) dispatched serially at `depth+1` with re-snapshotting. `CancelRecording` triggers `RecordingModule.onCrossModuleStateChange` and the recording→pipeline send-cascade (further actions at depth+2/+3…). When this fires from an already-deep `OnPipelineDone`/`CloseOverlay` cascade with both recording and pipeline in flight, the worst-case stacks. No test exercises the both-in-flight HOVER-close-while-nested-in-pipeline-done-cascade worst case to prove it stays < 8. At depth 8 the orchestrator `error()`s in DEBUG and silently `Rejected`s in release, dropping a `CancelPipeline`/`CancelRecording` — the user closes HOVER, the pipeline keeps running, and an opted-out transcript is produced (the exact bug F-7-internal was meant to fix).
- **Suggested fix:** Add a worst-case cascade-depth assertion test for the both-in-flight HOVER-close-from-pipeline-done path (assert observed max depth stays comfortably < `MAX_CASCADE_DEPTH`). This is a **test-addition** that *verifies* the budget; if the test reveals the budget is actually exceeded, that escalates to a code finding for a follow-up wave (emit a single coalesced cancel action). Kept 🟢: writing the depth-assertion test is mechanical and the cascade machinery is already in place; the audit flagged it as "needs research" only because it spans B2's cascade machinery, but the *action* (write the verification test) is unambiguous and the research is the test itself.
- **Domain bundle candidate:** cascade-depth test (independent)

### F-9 — `LegacyAudioFileMigrationTest` flaky via shared `DictateDatabase` singleton pollution, amplified by B5's 8× Service-boot transition test

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Was:** AUDIT-TEST-B5-1
- **Files:** `app/src/test/java/net/devemperor/dictate/migration/LegacyAudioFileMigrationTest.kt:233` (failure surface); `app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceOverlayTransitionTest.kt` (amplifier)
- **Description:** `LegacyAudioFileMigrationTest.run leaves non-legacy-path sessions untouched` failed once on a clean full-suite run (`expected:<[RECORDING]> but was:<[FAILED]>`), passed on cached + repeat-clean + targeted-subset runs. Root cause: the test shares the `DictateDatabase` singleton + default SharedPreferences across the Robolectric JVM fork (its own `@Before` KDoc documents the fragility). B5's new `DictatePipelineServiceOverlayTransitionTest` boots the full `DictatePipelineService` 8×; each `onCreate` calls `LegacyAudioFileMigration.run(applicationContext)` against the shared singleton and dispatches `TriggerPipeline` (creates session rows). Non-deterministic Robolectric fork co-location occasionally leaves the `Pref.LegacyAudioPurgedV4` flag / session rows in a state the `deleteAll()`+pref-clear `@Before` does not fully neutralise. **Production code is uninvolved (B5 touched 0 migration/DB files).**
- **Suggested fix:** Test-isolation hardening, **not** a code revert: either (a) reset the `DictateDatabase` singleton + clear default SharedPreferences in `@Before`/`@After` of the affected tests (or a shared base class) so the migration test's pre-state is deterministic regardless of fork co-location, or (b) harden `forkEvery` / Robolectric isolation for the Service-boot transition test so it does not co-locate with DB-singleton tests. Prefer (a) — per-test DB+pref reset — as the more sustainable fix (it removes the latent fragility the test's own KDoc already warns about, rather than depending on fork-scheduling). Mechanical; no production change.
- **Domain bundle candidate:** test-isolation (independent)

### F-10 — `OverlayDragController` 79-line KDoc mis-attached to `OverlayDragControllerFactory` (class itself has no KDoc)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **Was:** AUDIT-CONVENTION-B5-2
- **Files:** `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayDragController.kt:11-89` (KDoc block), `:90-96` (factory KDoc), `:139` (bare `class OverlayDragController(`)
- **Description:** The 79-line class-responsibility KDoc (lines 11-89, with `@property` tags for `ctx`/`view`/`window`/`paramsHolder`/`positionMapper`/`onPositionPersist`) is followed immediately by a second KDoc block (90-96) for `OverlayDragControllerFactory`, so per Kotlin doc-attachment the big block documents the *factory interface* (line 97), not the controller. `class OverlayDragController(` (line 139) has no KDoc. IDE quick-doc on the controller shows nothing; quick-doc on the factory shows the controller's whole spec including `@property` tags for parameters the factory doesn't have. The other four overlay files consistently attach interface KDoc to the interface and impl KDoc to the impl — this is the lone outlier.
- **Suggested fix:** Move the 79-line block (lines 11-89) to immediately precede `class OverlayDragController(` (line 139), keeping the factory KDoc (90-96) on the factory interface. Per knowledge-doc-format §Inline anchors (class header attached to the class it describes). **Coordinate with F-7** — F-7 also edits this file's controller/factory KDoc to document the new orientation-snapshot contract; do F-10 (relocate) and F-7 (content update) in one coherent edit so the relocated KDoc already carries the corrected SRP narrative.
- **Domain bundle candidate:** `OverlayDragController.kt` (bundle with F-7)

### F-11 — `xmlns:tools` declared on leaf TextView instead of root element in onboarding layout

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **Was:** AUDIT-CONVENTION-B5-3
- **Files:** `app/src/main/res/layout/activity_overlay_permission_onboarding.xml:71-72`
- **Description:** `xmlns:tools="http://schemas.android.com/tools"` is declared on the last child `TextView` (`overlay_perm_onboarding_status_tv`) rather than the root `ConstraintLayout`. Every other project layout that uses `tools:` declares the namespace on the root alongside `xmlns:android`/`xmlns:app`. Works but is the only mid-tree leaf placement in the res tree (added reactively to silence a `tools:ignore="MissingPrefix"` lint warning).
- **Suggested fix:** Hoist `xmlns:tools` to the root `<androidx.constraintlayout.widget.ConstraintLayout>` element next to `xmlns:android`/`xmlns:app`, leaving only `tools:ignore="MissingPrefix"` on the status TextView. Trivial mechanical edit.
- **Domain bundle candidate:** `activity_overlay_permission_onboarding.xml` (independent)

### F-12 — `OverlayDragController` drag-params-stability invariant (detach-before-params-swap) is implicit/undocumented

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **Was:** AUDIT-LOGIC-B5-7
- **Files:** `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayDragController.kt:193`
- **Description:** No guard against `paramsHolder()` returning a different params instance between `ACTION_DOWN` (captures `initialParamsX/Y`) and subsequent `ACTION_MOVE`. The race (re-inflate creating a fresh params object mid-gesture) is currently closed by ordering — `teardownOverlay()` calls `dragController?.detach()` (removing the listener) *before* the params swap — but this dependency is implicit and undocumented at the controller site. A future refactor of teardown ordering could silently reopen it. (The `.toInt()` truncation and ACTION_MOVE-only threshold sub-points in the audit are negligible and not actioned.)
- **Suggested fix:** Add a one-line invariant comment in `OverlayDragController` documenting the "detach-before-params-swap" dependency on the backend's `teardownOverlay()` ordering. Comment-only. Bundle with F-10/F-7 (same file).
- **Domain bundle candidate:** `OverlayDragController.kt` (bundle with F-7, F-10)

### F-13 — `syncOverlayBackendAttachment` detach flag-ordering relies on un-cited `KeyboardLayoutManager.detachBackend` remove-before-detach guarantee

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **Was:** AUDIT-LOGIC-B5-8
- **Files:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt` (`syncOverlayBackendAttachment`, ~line 600-630 of HEAD)
- **Description:** The attach-path flag-first ordering is exhaustively commented; the symmetric detach path clears `overlayBackendAttached = false` *before* `keyboardLayoutManagerImpl.detachBackend(backend)`. The invariant still holds — but only because `KeyboardLayoutManager.detachBackend` removes the backend from `activeBackends` *before* calling `backend.detach()` (so a throwing `detach()` still leaves a consistent state and re-attach's `check(backend !in activeBackends)` passes). This cross-class ordering guarantee is uncited at the `syncOverlayBackendAttachment` detach branch — an asymmetry (attach exhaustively commented, detach relies on an implicit cross-class detail) that is a maintenance trap.
- **Suggested fix:** Add a comment on the detach branch noting the dependency on `KeyboardLayoutManager.detachBackend`'s remove-before-detach ordering. Comment-only.
- **Domain bundle candidate:** `DictatePipelineService.kt` (can fold into the F-1/F-2/F-3 IME-wiring wave's touch of this file, or stand alone — comment-only)

## Eliminated findings

| Source ID | Source audit | Disposition | Reason |
|-----------|--------------|-------------|--------|
| AUDIT-PLAN-AND-API-B5-5 | plan-and-api | ❌ informational — no fix | F-10 (B4 carry-over: `StopRecordingAndSend(sessionId="")` empty-string sentinel) is now load-bearing in the overlay path too, but B5 *propagates* it consistently (keyboard SEND uses the same convention; Spec 1 §15.2 cross-module cascade fills the real sessionId). The OVERLAY_SEND textResolver indirection is benign and documented. No B5 code change is correct here — the sentinel convention is a project-wide decision owned by the carry-over Issue Index, to be resolved at Phase 4, not in this block. Recorded so Phase 4 reconciliation does not lose it. |
| AUDIT-PLAN-AND-API-B5-6 | plan-and-api | ❌ informational — no fix (carry-over verification) | Verification finding: D-13 (LanguageController removal) + D-14 (`audioFile` field) correctly untouched and re-deferred to B7; F-12/F-13/F-15 not addressed by B5 but **not regressed** — no accidental carry-over contamination. This is the *expected* state and confirms the Issue Index is accurate for Phase 4. No fix; recorded so Phase 4 explicitly carries F-12/F-13/F-15/D-13/D-14 forward to B7. |
| AUDIT-PLAN-AND-API-B5-7 / -B5-8 | plan-and-api | ❌ informational — no fix (merged-away / confirmatory) | B5-7 confirms the `NoOverlayPermissionGate` stub is correctly retired from production (only the `OpenOverlayPermissionSettings` no-op remains — already captured in F-2). B5-8 confirms the T1–T7 attach-matrix collapse is provably exhaustive and correct *given the FSM is driven* — its operative concern (FSM not driven) is F-1. Both fold into existing findings; no independent fix. |
| AUDIT-CONVENTION-B5-4 | convention | ❌ false-positive (do not re-raise) | The four "expected pure JVM" tests (`OverlayLayoutParamsFactoryTest`, `OverlayBackendTest`, `DefaultOverlayPositionMapperTest`, `OverlayDragControllerTest`) running under Robolectric is **K-4-compliant** — these types unavoidably touch real Android framework classes (`WindowManager.LayoutParams`, `View`+`MotionEvent`+`ViewConfiguration`, `DisplayMetrics`+`View`, `LayoutInflater`+`View`) that cannot be constructed on a bare JVM. Robolectric is genuinely required (the documented K-4 opt-out for framework-coupled types), not a substitute for a hand-rolled fake. Only `OverlayPermissionObserverTest` is correctly pure-JVM (DIP via `(Action)->Unit` lambda). The audit-brief's "pure JVM" classification was inaccurate; the implementation made the correct call. Consolidator confirms K-4 satisfied — **do not re-raise**. (Optional: correct the block-report's test-classification wording — not actioned as a finding.) |

## Repair-wave recommendation

**1 research-step + 1 repair-wave.**

- **Research step:** combined topic `b5-ime-activation-wiring` covering F-1 (`OnImeViewShown/Hidden` dispatch + correct IME lifecycle hook), F-2 (permission-onboarding-trigger: §5.4 trigger-arm decision + info-bar location + `OpenOverlayPermissionSettings` launch), and the F-3 `observer.refresh()` placement (folds into the same hook). These three are the same IME-level activation surface in the same file (`DictateInputMethodService.java` + the OverlayModule/ImeViewBackend it must reach) — one research probe, not three.
- **Repair wave (all-validated scope):** the resume-chain research-agent applies F-1 + F-2 + F-3 (IME-activation bundle), then the consolidator-as-implementer applies the 🟢 mechanical/doc set: F-4 (`NotifyOverlayPermissionRequired` Effect), F-5 (3 locale files), F-6 + F-7 + F-10 + F-12 (`OverlayPositionMapper`/`OverlayDragController` coordinate+doc bundle — F-7 and F-10 must be one coherent edit), F-8 (cascade-depth test), F-9 (test-isolation), F-11 (xmlns:tools hoist), F-13 (detach-ordering comment). Domain bundles: IME-activation (`DictateInputMethodService.java`), `OverlayModule.kt`, locale files, `OverlayPositionMapper.kt`/`OverlayDragController.kt`/`OverlayBackend.kt` coordinate-persist path, test-isolation, layout XML.

Note for the orchestrator: F-1/F-2 are nominally cross-block (Spec 1 / B1-B2 IME-dispatch scope) but B5 is the **final implementation block** and its central acceptance (the Triangle-FSM / Geist-Widget-Bug elimination, the plan's raison d'être) is non-functional end-to-end without them. The Phase-4 §2.3 bug-elimination claim cannot be made until F-1/F-2/F-3 land. Recommend they are fixed in this repair-wave rather than deferred to Phase 4, since deferring leaves the plan's core deliverable inert.
