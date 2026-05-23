# Audit Report: plan-and-api (Block 5, scope: full-block)

**Agent-ID:** B5-AUDIT-PLAN-AND-API
**Date:** 2026-05-15
**Knowledge skills used:** knowledge-reference (plugin-system / versioned-envelope — concept-level only; not load-bearing for Kotlin Android overlay code)
**Files inspected:** 18
- `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayBackend.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayWindow.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayLayoutParamsFactory.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayPermissionGate.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/overlay/DefaultOverlayPermissionGate.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayPermissionObserver.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayPositionMapper.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayDragController.kt`
- `app/src/main/java/net/devemperor/dictate/onboarding/OverlayPermissionOnboardingActivity.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/TextResolvers.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/OverlayModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/ViewModeModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt`
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt`
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`
- `app/src/main/java/net/devemperor/dictate/state/PipelinePrefMirror.kt`

## Summary

- Critical: 2
- Important: 4
- Nice-to-have: 2

## Findings

### AUDIT-PLAN-AND-API-B5-1

- **Severity:** Critical
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` (onStartInputView:1650, onFinishInputView:1053 — dispatch absent)
- **Description:** The IME service never dispatches `Action.ViewModeAction.OnImeViewShown` / `OnImeViewHidden`. `grep` confirms zero `ViewModeAction` / `OnImeView*` references in `DictateInputMethodService.java`, and the IME file is not in the B5 diff (`git diff 74f9dd3..HEAD` does not touch it). Spec 3 §7.3 T3/T5/T6 and plan §4 (line 414–415) + Spec 1 §11 (lines 2670–2676) all place these dispatches in the IME's `onStartInputView`/`onFinishInputView` as the **trigger** for the Triangle-FSM. The `ViewModeModule.reduce` arms `OnImeViewShown`/`OnImeViewHidden` (ViewModeModule.kt:90,100) exist but are **dead code** — nothing produces these actions in production. Consequence: T3 (KEYBOARD→HOVER), T5 (HOVER→KEYBOARD), T6 (HOVER→WIDGET) never fire; the HOVER auto-overlay (Spec 3 §10 acceptance "Bei View-hidden + Recording-aktiv: HOVER-Overlay erscheint automatisch") is structurally unreachable.
- **Why it matters:** The HOVER half of the Triangle-FSM — the core behaviour Block 5 exists to deliver, and the regression class the whole plan targets (Geist-Widget-Bug T7 also depends on a HOVER state being reachable) — has no production entry point. Unit tests (audit-test-B2) cover the reducer arms but not the IME trigger, so this passes CI while being non-functional end-to-end.
- **Scope note:** The IME-side dispatch is *Spec 1 / B1-B2 (C2 service-skeleton + IME-binding)* scope per the plan, **not** a B5 chunk deliverable. B5 cannot fix it without going out of chunk scope. But B5 is the last implementation block and its central acceptance depends on this; the gap must be surfaced for Phase 4 Integration. Flagging as a cross-block integration gap.
- **Suggested fix scope:** medium — add `pipeline?.dispatch(Action.ViewModeAction.OnImeViewShown)` in `onStartInputView` and `OnImeViewHidden` in `onFinishInputView` (per Spec 1 §11 lines 2672–2676). Belongs to a Phase-4 integration repair or a B1/B2 follow-up, not a B5 chunk.

### AUDIT-PLAN-AND-API-B5-2

- **Severity:** Critical
- **File:** `app/src/main/java/net/devemperor/dictate/onboarding/OverlayPermissionOnboardingActivity.kt` (exists, never started); `DefaultOverlayPermissionGate.shouldShowOnboarding()` never called from production
- **Description:** Spec 3 §10 acceptance #1: "Permission-Onboarding läuft beim ersten Widget-Toggle-Versuch." The onboarding surface (`OverlayPermissionOnboardingActivity`) is implemented and Manifest-declared, and `DefaultOverlayPermissionGate.shouldShowOnboarding()` is implemented, but **nothing in production starts the Activity or calls `shouldShowOnboarding()`**. `grep` for `OverlayPermissionOnboardingActivity` / `shouldShowOnboarding` / `RequestOverlayPermission` dispatch shows no production trigger from the WIDGET-toggle path. The in-IME info-bar (Spec 3 §5.3, `overlay_permission_infobar.xml`) does not exist (only `activity_overlay_permission_onboarding.xml` is present). C17's report explicitly forwarded the in-IME info-bar binding to C18 ("the in-IME info-bar binding is part of C18"); C18's report does not mention implementing it. Additionally, `OverlayModule.Effect.OpenOverlayPermissionSettings` runEffect is a documented **no-op placeholder** (OverlayModule.kt:191–198: "Phase-1: … we emit no-op"), and `RequestOverlayPermission` therefore reaches a dead Effect.
- **Why it matters:** With permission absent (the default first-run state), `ToggleViewModeWidget` is filtered to `null` in `ViewModeModule.reduce` (`!ctx.global.overlay.hasPermission`) and **nothing tells the user why the widget didn't appear or how to grant permission**. The onboarding flow — the entire user path into the WIDGET feature — is unreachable. This fails Spec 3 §10 acceptance #1 and #10 ("Permission verweigert: … HOVER-Auto-Trigger fällt auf Notification-Only zurück").
- **Suggested fix scope:** medium — wire `shouldShowOnboarding()` into the WIDGET-toggle path (resolver/Effect or IME-side info-bar) and make `OpenOverlayPermissionSettings` launch the Settings intent (or start `OverlayPermissionOnboardingActivity`). Part is C17/C18 chunk scope that was forwarded and apparently dropped; part (no-op Effect) is C5/B2 OverlayModule scope.

### AUDIT-PLAN-AND-API-B5-3

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/OverlayModule.kt:69-83` (Effect sealed interface), `:253-259` (permission-loss cascade), `:191-198` (OpenOverlayPermissionSettings no-op)
- **Description:** Spec 3 §4.8 declares the canonical `OverlayModule.Effect` set including `NotifyOverlayPermissionRequired` ("Issue 3.1.3 Notification-Action"). The implemented `Effect` sealed interface is missing it entirely (`grep NotifyOverlayPermissionRequired / showPermissionRequired` → no production hit). The permission-loss cascade (`onCrossModuleStateChange`, lines 253–259) emits only `SetViewMode(KEYBOARD)`; Spec 3 §4.8's cascade comment explicitly states "Notification-Action emittiert via Effect (siehe runEffect)" and §9 Notification-Fallback is the documented permission-free UX. So a runtime permission revoke silently drops the overlay with no notification telling the user, violating Spec 3 §9 + §10 acceptance #10.
- **Why it matters:** Plan-mandated Effect from the canonical OverlayModule spec is absent; the permission-free fallback (a deliberate O7 architecture decision) is not delivered.
- **Scope note:** OverlayModule is C5/B2 canonical-code home but Spec 3 §4.8 is its authoritative spec; the gap surfaces in the B5 overlay-feature scope. The §4.8 EffectFailure-convention block (Iter-log C-5 finding 6) was honoured (no `reduceFailure` override is intentional and documented) — that part is correct.
- **Suggested fix scope:** small — add the `NotifyOverlayPermissionRequired` Effect + `services.notifications.showPermissionRequired()` runEffect arm + emit it in the permission-loss cascade. Delegated (logic/architecture, not inline).

### AUDIT-PLAN-AND-API-B5-4

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayPermissionObserver.kt`; `DictatePipelineService.onCreate` (no IME-side `refresh()` wiring); `DictateInputMethodService.java` (no `observer.refresh()` call)
- **Description:** Spec 3 §5.0 specifies `OverlayPermissionObserver.refresh()` called from the IME's `onCreateInputView`/`onStartInputView` so a user returning from the system Settings page is detected promptly. Only the Service-side `init()` cold-start dispatch is wired (`DictatePipelineService.kt:601`). C17's and C18's reports both explicitly forwarded the IME-side `refresh()` wiring ("forwarded to Phase 4 Integration"). Net effect: after the user grants overlay permission in Settings and returns, `state.overlay.hasPermission` is **not refreshed until the next process cold-start** — the just-granted permission does not take effect in the live session. This compounds finding B5-2 (even if onboarding launched, the grant wouldn't be picked up).
- **Why it matters:** Plan rule partially implemented (Service-side `init()` only); the user-facing "grant then immediately use" loop is broken. Documented as a known forward to Phase 4 in the chunk reports, so this is a tracked deferral rather than silent drift — flagging to ensure Phase 4 Integration actually closes it.
- **Suggested fix scope:** small — `pipeline?.overlayPermissionObserver?.refresh()` (binder accessor exists) in IME `onStartInputView`. Forward-compat issue for Phase 4.

### AUDIT-PLAN-AND-API-B5-5

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:512` (OVERLAY_SEND textResolver), `:518` (`StopRecordingAndSend(sessionId = "")`)
- **Description:** Two C16 catalog deviations from Spec 3 §3.1 verbatim form, both documented in the C16 block-report `### Deviations`, both functionally equivalent — verified, not a defect, but flagged for the deviation-aggregate and the F-10 carry-over linkage: (a) OVERLAY_SEND uses `textResolver = { strings.overlaySend }` instead of Spec 3's `resolveOverlaySendText(state)` — the `LayoutStrings.overlaySend` default ("Send") matches `R.string.overlay_send`; acceptable indirection consistent with the catalog's Android-loose pattern. (b) OVERLAY_SEND `actionResolver` emits `Action.RecordingAction.StopRecordingAndSend(sessionId = "")` — the empty-string sentinel. This is the **F-10 B4 carry-over** ("StopRecordingAndSend(sessionId="") empty-string sentinel", still `open / delegated-to-orchestrator` in the Issue Index). The keyboard-surface SEND uses the same convention (Spec 1 §15.2 cross-module cascade fills the real sessionId), so it is internally consistent — but F-10 remains an unresolved cross-cutting decision that B5 propagates rather than resolves.
- **Why it matters:** Confirms F-10 is still deferred (not addressed by B5) and is now load-bearing in the overlay path too — Phase 4 must resolve the sentinel convention or accept it project-wide. The textResolver deviation is benign.
- **Suggested fix scope:** small (documentation / Phase-4 decision) — no code change required for correctness; F-10 needs an orchestrator-level resolution.

### AUDIT-PLAN-AND-API-B5-6

- **Severity:** Important
- **File:** Issue Index carry-overs F-12, F-13, F-15 (B4-pre state-shape extensions); D-13, D-14 (B7); Espresso UI-Tests 1–10
- **Description:** Verified the carry-over disposition: D-13 (LanguageController removal) and D-14 (`DictateInputMethodService.audioFile` field) are **not** touched in the B5 diff (`git diff 74f9dd3..HEAD` shows no LanguageController / audioFile-field edits) — correctly re-deferred to B7. F-12 (`ReprocessStaging.isStarting`), F-13 (`PipelineUiState.Running.completedSteps/totalSteps/elapsedMs`), F-15 (`LayoutStrings.dictateButtonText` language-awareness) are **not addressed** by B5 and remain `open / delegated-to-orchestrator` — B5 did not regress them but also did not close them (F-15 is visibly still a baseline stub: `buildLayoutStrings().dictateButtonText = { getString(R.string.dictate_record) }`, DictatePipelineService.kt:699, with a documented D-13 forward). Espresso UI-Tests 1–10 remain `@Ignored` skeletons (not in B5 scope; status unchanged). No accidental carry-over contamination found — this is the expected state, flagged to confirm the Issue Index is accurate for the Phase-4 reconciliation.
- **Why it matters:** Confirms no carry-over was accidentally touched (good) and that the deferred set is intact for Phase 4 — the plan's "all 19 chunks implemented" claim is true for chunk *scope*, but several B4-pre state-shape extensions (F-12/F-13) and language-awareness (F-15/D-13) remain genuinely open and must not be lost in Phase 4 closeout.
- **Suggested fix scope:** small (tracking only) — ensure Phase 4 Integration / Issue-index reconciliation explicitly carries F-12/F-13/F-15/D-13/D-14 forward to B7.

### AUDIT-PLAN-AND-API-B5-7

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayPermissionGate.kt:54` (NoOverlayPermissionGate)
- **Description:** The C16 `NoOverlayPermissionGate` stub is correctly retired from production — `grep` confirms it is referenced **only** in `OverlayBackendTest.kt` (test fixture). Production wiring constructs `DefaultOverlayPermissionGate` and threads it into `OverlayBackend` (DictatePipelineService.kt:565,584). This is the expected C17 hand-off (the stub is intentionally retained in the source file for the JVM-pure backend test). No remaining production stubs in the overlay render/window/drag/mapper path (`grep TODO|FIXME|STUB|notImplemented` over the overlay package → no production hits). The only no-op-in-production is `OverlayModule.Effect.OpenOverlayPermissionSettings` (covered by B5-2).
- **Why it matters:** Confirms the C16→C17 stub-replacement deviation resolved cleanly; no finding beyond the OpenOverlayPermissionSettings no-op already raised in B5-2.
- **Suggested fix scope:** none — informational.

### AUDIT-PLAN-AND-API-B5-8

- **Severity:** Nice-to-have
- **File:** `DictatePipelineService.kt:651-673` (`syncOverlayBackendAttachment`), C18 deviation table
- **Description:** The 7-arm T1–T7 attach matrix is collapsed to a single rule `attach iff viewMode != KEYBOARD`. Verified provably exhaustive against Spec 3 §3.1 (WIDGET ∪ HOVER both render `OVERLAY_5BUTTON`) and §7.2: T1/T3 → attach, T2/T5/T7 → detach, T4/T6 → no-op (stay attached). T7 (Geist-Widget regression) is structurally identical to T5 — both settle `viewMode = KEYBOARD` via the `PipelineModule.OnPipelineDone` → `ViewModeModule.reduce` cascade (verified: PipelineModule emits `OnPipelineDone`, ViewModeModule.kt:158 reduces it with derived `imeViewVisible = state != HOVER`). The flag-first ordering (set `overlayBackendAttached=true` before `attachBackend`) correctly prevents a window leak on first-render throw. This is a well-reasoned, documented C18 deviation; the only residual risk is the per-emit `try/catch` swallowing render exceptions silently (logged at WARN) — acceptable robustness trade-off, but worth a Phase-4 note that a persistently-throwing overlay render would be invisible except in logcat.
- **Why it matters:** Confirms the central FSM attach/detach contract is correct **given** the FSM is actually driven — but note its correctness is moot in production until B5-1 (missing IME `OnImeView*` dispatch) is fixed: HOVER is never entered, so only T1/T2 (user WIDGET toggle, which is itself gated behind the unreachable onboarding per B5-2) are exercisable.
- **Suggested fix scope:** none for the rule itself — informational; the dependency on B5-1/B5-2 is the operative concern.

## Coverage

- Files audited: all 18 listed above (full C16+C17+C18 production surface + the cross-block FSM trigger sites in `DictateInputMethodService.java` and the C5/B2 `ViewModeModule`/`PipelineModule`/`OverlayModule` consumed by B5).
- Files skipped (with reason): test files (out of plan-and-api topic scope — AUDIT-TEST owns them); res/ XML and styles (structurally matched against Spec 3 §3.2 ID-contract via grep; `overlay_5button_layout.xml` button IDs verified present); B5 unit tests (compile-green confirmed via `:app:compileDebugKotlin`).
- Knowledge-skill checkpoints applied: knowledge-reference plugin-system concept (DictateModule registry mirrors the closed-set plugin pattern — no contract violation found); type-contract review of `RenderBackend` / `OverlayPermissionGate` / `OverlayDragControllerFactory` interfaces (consumer signatures consistent across C16-C18 and the service composition root).

## Out-of-scope observations

- (logic) `OverlayModule.onCrossModuleStateChange` HOVER→KEYBOARD cancel-cascade was changed from a Spec-3 `when {}` priority-chain to an additive `if/if` (both Recording AND Pipeline cancelled when both in-flight) — documented as F-7 (2026-05-15) in OverlayModule.kt:209–220. Plausible improvement but a behavioural deviation from Spec 3 §6.2's "Recording priorisiert (sonst Pipeline)" — AUDIT-LOGIC should confirm the both-in-flight ordering is safe under the orchestrator's serial depth+1 re-snapshot.
- (convention) `isPortraitOrientation()` is defined independently in `OverlayBackend` (`!= ORIENTATION_LANDSCAPE`) and the drag-persist path reads orientation via the same helper — consistent within the backend, but the mapper (`DefaultOverlayPositionMapper`) uses `displayMetrics` directly; AUDIT-CONVENTION may want to confirm there's no portrait/landscape divergence between the two SoTs (Spec 3 §11.5.6 OPEN-3.2 aspect-bucket is explicitly manual/Phase-4, so likely acceptable).

## Bug-elimination final check (plan §2.3) — note

The plan §1.1 bug-classes the overlay block targets (Geist-Widget-Bug via T7; permission-less crash via state-axis mirroring; window-leak via idempotent attach/detach) are **structurally** present in the code (T7 cascade exists, BadToken caught in the wrapper, flag-first attach bookkeeping). However, the Geist-Widget-Bug elimination is only *latent* — it cannot be exercised in production because the HOVER state is unreachable without the IME `OnImeView*` dispatch (B5-1). The structural fix is correct; its activation is blocked by the cross-block integration gap. Phase 4 must close B5-1/B5-2/B5-4 before the §2.3 bug-elimination goals can be claimed end-to-end.
