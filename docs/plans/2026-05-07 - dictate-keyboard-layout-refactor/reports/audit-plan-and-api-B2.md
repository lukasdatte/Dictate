# Audit Report: plan-and-api (Block 2, scope: full-block)

**Agent-ID:** B2-AUDIT-PLAN-AND-API
**Date:** 2026-05-15
**Knowledge skills used:** none (loaded none — topic is plan-conformity + stub markings + cross-chunk API contracts, no language-pattern grounding needed)
**Files inspected:** 18 production + 1 service + 22 tests + 4 plan/spec/chunks

Production files inspected:
- `app/src/main/java/net/devemperor/dictate/state/Action.kt`
- `app/src/main/java/net/devemperor/dictate/state/DictateModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/DictateModuleRegistry.kt`
- `app/src/main/java/net/devemperor/dictate/state/DictateOrchestrator.kt`
- `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt`
- `app/src/main/java/net/devemperor/dictate/state/DictateUiStateStore.kt`
- `app/src/main/java/net/devemperor/dictate/state/ModuleId.kt`
- `app/src/main/java/net/devemperor/dictate/state/ModuleServices.kt`
- `app/src/main/java/net/devemperor/dictate/state/PipelinePrefMirror.kt`
- `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt`
- `app/src/main/java/net/devemperor/dictate/state/PipelineServiceStubSubsystems.kt`
- `app/src/main/java/net/devemperor/dictate/state/TestOnlyModules.kt`
- `app/src/main/java/net/devemperor/dictate/state/TransitionResult.kt`
- `app/src/main/java/net/devemperor/dictate/state/SideEffect.kt`
- `app/src/main/java/net/devemperor/dictate/state/InsertionTarget.kt`
- All 14 modules in `app/src/main/java/net/devemperor/dictate/state/modules/`
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt`
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` (consumer side)

Spec sections read: Spec 1 §3, §4.2, §4.3, §4.5, §4.6, §4.7, §4.8, §7.3, §11.2.2 (Block 1b/2), §15.1 + §15.1.x (matrix), §15.2. Spec 3 §4.8, §7.1, §7.3. ADR cross-refs via KDocs (verified Spec → KDoc anchor links are correct).

## Summary

- Critical: 0
- Important: 3
- Nice-to-have: 6

The block delivers a substantial, well-structured composition root. Plan-treue is high across C3–C7. The stub-subsystem file is **exemplarily marked** (single `PipelineServiceStubSubsystems` object, per-method `Log.w` with "B3 fills this" marker, KDoc header explicitly names Block 3 / chunk C8 as replacement scope). The carry-over IMPL-1 re-deferral to C8 is documented with sound D5 reasoning in the Issue Index and the C7 service-class KDoc.

The findings below are mostly forward-compatibility gaps and plan-deviations whose `### Deviations` documentation is missing or thin. None block C8 wiring; B3 should track them in its plan-correctness pass.

## Findings

### AUDIT-PLAN-AND-API-B2-1 — `lastResultNeedsManualPaste` write-path missing

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt:292-311` (reducer arm) + `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt:80` (field declaration)
- **Description:** Spec 1 §3 + §15.x defines a top-level `lastResultNeedsManualPaste: Boolean` flag (declared on `DictateUiState`, attributed to PipelineModule in the §3 axes table). The actions `Action.PipelineAction.NotifyResultNeedsManualPaste(sessionId)` and `Action.PipelineAction.ClearManualPasteFlag` exist in the hierarchy, but the PipelineModule reducer arm explicitly returns `null` for both. The reducer's lens writes only `pipeline = sub` (`write(global, sub) = global.copy(pipeline = sub)`) — the top-level flag has no module that can mutate it. The reducer's own KDoc comment (lines 292-309) admits the gap: *"Simplest correct form: return null (no PipelineUiState mutation). The flag is read directly from the global state — its mutation is performed by C7 wiring via a separate (out-of-band) `_state.update` on PrefMirror init."* PrefMirror neither reads nor writes that flag. End result: the flag is **dead code** in Phase 1 — it stays `false` regardless of action.
- **Why it matters:** Spec 1 §11.2.2 Block-1b commits this flag to enable IME-service-death recovery ("tell the user to paste from clipboard"). B3 will dispatch `NotifyResultNeedsManualPaste` from the recovery path and expect it to flip the flag; with the current reducer, the dispatch resolves as `Rejected("reducer-null")` and the IME never gets the recovery signal. This is the kind of silent-drop the modular-orchestrator pattern is supposed to eliminate.
- **Suggested fix scope:** medium — either extend the PipelineModule lens to encompass the flag (then write it from the reducer arm), or move the flag onto `PipelineUiState` as a new arm/field, or introduce a dedicated tiny module. The C6 report's "Plan deviations" list does NOT document this gap.
- **Suggested fix:** Capture as B3-scope (forward) so the C8 subsystem migration can decide between the three options above. Document in C7 report's Deviations table to make the gap explicit.

### AUDIT-PLAN-AND-API-B2-2 — `Recording.StopRecordingAndSend` does NOT cascade to Pipeline

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt:344-353` (cross-module observer) + `app/src/main/java/net/devemperor/dictate/state/Action.kt:125` (`StopRecordingAndSend`)
- **Description:** Spec 1 §15.1.x Coupling-Matrix row `Recording × Pipeline = R(state.recording) C(PipelineAction.Submit)` predicts a cascade from a Recording-state transition into a pipeline-Submit action. `Action.RecordingAction.StopRecordingAndSend` is a distinct sealed leaf added to support the "Send" button (KDoc explicitly says "stop recording AND trigger the pipeline. The `PipelineAction.Submit` is emitted by `RecordingModule`'s cross-module cascade on `Active/Paused → Idle` transitions where the trigger was this action."). The implementation reduces `StopRecordingAndSend` to `Idle` with the standard stop-effects (same as `StopRecording`), and the `onCrossModuleStateChange` observer only emits `ResetSuppressBit` on `Idle → Preparing` — there is NO cascade arm that emits `Action.PipelineAction.TriggerPipeline` on the `Active/Paused → Idle` boundary. The C5 deviations table acknowledges *"PipelineModule does NOT cascade RecordingAction.StopRecording on its own state transitions"* but does NOT note that RecordingModule *also* doesn't cascade to Pipeline — both directions of the Recording↔Pipeline edge are unwired.
- **Why it matters:** Without the cascade, `StopRecordingAndSend`'s "Send" semantics are observationally identical to plain `StopRecording`. The IME-side click resolver must dispatch BOTH `StopRecordingAndSend` and a follow-up `TriggerPipeline` to make the flow work — but the resolver is B3 territory and the spec assumes the cascade exists. B3 / B4 (LayoutCatalog Send-slot) will hit this when wiring `resolveRecordAction`'s "Send" branch.
- **Suggested fix scope:** small — add a guard `if (prev.recording is Active|Paused && next.recording is Idle && trigger was StopRecordingAndSend)` block in `RecordingModule.onCrossModuleStateChange`. The challenge: the cascade observer signature `(prev, next) -> List<Action>` doesn't carry the originating action, so the observer cannot distinguish "Send" from "Stop". A second sub-state field (e.g. `RecordingState.Idle.lastTriggerWasSend: Boolean`) or a separate flag axis would be required — that's medium-scope and pushes the question into Spec / B3 design.
- **Suggested fix:** Defer to B3 with the design question documented (action-flavour preservation in state). The current behaviour is consistent with the C5 deviation table entry; making the gap **bilateral** (both directions missing) is the additional information for B3.

### AUDIT-PLAN-AND-API-B2-3 — InterruptionModule registered, contradicting Spec §4.8 comment-out

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/DictateModuleRegistry.kt:148-152`
- **Description:** Spec 1 §4.8 explicitly comments out the InterruptionModule entry in the registry source-snippet: `// InterruptionModule (Phase 2 — auskommentiert bis aktiv)` (Spec 1 line 1184). The implementation **does** register `InterruptionModule` — necessarily, because `Action.InterruptionAction` is a direct sealed subclass of `Action`, so `assertCompleteCoverage()` would throw `IllegalStateException` at service-onCreate time if no module claimed it. This is a real plan-impl divergence: the spec says "comment out" but the orchestrator's complete-coverage invariant says "register". The C6 report does not flag this divergence in its Deviations table (only the `Action.ThemingAction`-addition is documented). One of the two has to give: either Spec §4.8's comment-out instruction is stale (and should be updated to reflect that the assertCompleteCoverage rule forces registration), or the Action sealed hierarchy should not expose `InterruptionAction` as a Phase-1 direct subclass.
- **Why it matters:** The intent of the Phase-2 stub-module IS to be registered (the C6 stub explicit-rejects every action — that's the right Phase-1 behaviour). But silently fixing Spec-vs-impl drift accumulates over blocks. B3+ implementers reading Spec §4.8 will pattern-match on the comment-out form for new Phase-2 modules; they'll then trip over the same assertCompleteCoverage check.
- **Suggested fix scope:** small — document in C6 Deviations table and propose a Spec §4.8 textual fix (replace "auskommentiert bis aktiv" with "Phase-1 stub-registered to satisfy assertCompleteCoverage; reducer rejects all actions until B3 / Phase 2 promotes it").
- **Suggested fix:** Block-Validate / VAL-SANITY can route this as a low-risk doc-fix to the spec — the implementation choice is correct given the assertCompleteCoverage rule.

### AUDIT-PLAN-AND-API-B2-4 — `OverlayModule.Effect.NotifyOverlayPermissionRequired` missing (Spec 3 §4.8)

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/OverlayModule.kt:67-81` (Effect surface)
- **Description:** Spec 3 §4.8 lists `object NotifyOverlayPermissionRequired : Effect` in the OverlayModule's Effect surface (line 950 of the spec), with the runEffect-arm `services.notifications.showPermissionRequired()` (line 1010). The cross-module observer body's comment at the permission-loss arm reads "Notification-Action emittiert via Effect (siehe runEffect)" (spec line 1055). The implementation omits this Effect entirely — there is no `NotifyOverlayPermissionRequired` variant, no runEffect arm, and the cross-module observer at permission-loss emits only `SetViewMode(KEYBOARD)`. The implementation's cascade is functionally complete (the user sees the mode switch), but the user-visible notification path that the spec promises does not exist. Note: the `services.notifications` subsystem doesn't exist in `ModuleServices` either (the notification surface is `notificationCoordinator: PipelineNotificationCoordinatorSubsystem`, not a generic `notifications`), so adding the Effect would also force a subsystem addition.
- **Why it matters:** B5 (Overlay) will read Spec 3 §4.8 looking for the full surface; finding one Effect missing introduces friction. The omission is a Phase-1 simplification that should be documented as a deviation, not a silent gap.
- **Suggested fix scope:** small — either add the Effect (no-op in C7 stubs, real implementation in B5) and route via a new subsystem method, or document in C5 Deviations table that the permission-loss-cascade uses only `SetViewMode` in Phase 1.
- **Suggested fix:** Document as deviation in C5 report. The "either" branch with the toast/notification can wait for B5's `NotificationStatus.PermissionRequired` variant.

### AUDIT-PLAN-AND-API-B2-5 — Coupling-matrix arrows for 8 modules unimplemented (Spec §15.1.x)

- **Severity:** Nice-to-have
- **File:** All 8 modules in `app/src/main/java/net/devemperor/dictate/state/modules/` (Resend, LivePrompt, Language, Layout, FeatureToggle, Theming, PendingSessions, KeyboardInput)
- **Description:** Spec 1 §15.1.x Coupling-Matrix lists arrows from 8 of the 14 modules into other modules — for example:
  - `Resend → Pipeline = R(state.resend) C(PipelineAction.SubmitReprocess)`
  - `LivePrompt → Pipeline = R(state.livePrompt.pendingChain) C(PipelineAction.Submit)`
  - `LivePrompt → Language = R(state.livePrompt) C(LanguageAction.SetOverride)`
  - `ViewMode → Layout = R(state.viewMode) C(LayoutAction.OnViewModeChanged)`
  - `ViewMode → Overlay = R(state.viewMode) C(OverlayAction.OnViewModeChanged)`
  - `Language → Pipeline = R(state.language)`
  - `Pipeline → PendingSessions = R(state.pipeline)`
  - `FeatureToggle → Pipeline = R(state.features.autoEnterEnabled)`
  - `PendingSessions → Pipeline = R(state.pendingSessions)`
  - `Recording → PendingSessions = C(PendingSessionsAction.Insert)`
  - `Recording → ViewMode = C(ViewModeAction.OnRecordingActive)` — note: this action doesn't even exist in `Action.ViewModeAction`!

  But Spec §15.1 column "Cross-Module-Observer?" marks these modules as observer-free ("nein" or "ja" only for the 5 core modules — Recording / Pipeline / Audio / ViewMode / Overlay). The C5/C6 reports correctly implement the §15.1 column (5 modules have observers, 9 don't), and C6 notes: *"Cross-module observers absent from all 9 modules — Spec 1 §15.1 marks these explicitly as observer-free."* The §15.1 column and the §15.1.x matrix are **internally inconsistent within the spec**. The implementation follows §15.1; the matrix is forward-compat / Phase-2 / doc-only. Plus `Action.ViewModeAction.OnRecordingActive`, `Action.LayoutAction.OnViewModeChanged`, `Action.OverlayAction.OnViewModeChanged`, `Action.PendingSessionsAction.Insert` (matrix cells) **don't exist in the Action hierarchy** — adding them now would be premature without the implementing modules.
- **Why it matters:** Future spec-readers (and the plan-review skill's ADR-compliance agent) will keep flagging "missing cascade arrows" findings unless the matrix-vs-§15.1 inconsistency is resolved in the spec. B3 should reconcile.
- **Suggested fix scope:** medium — Spec §15.1.x matrix needs a caption explicitly noting "rows for non-core modules are forward-compat; Phase 1 implements only the 5 core-module rows". Or move the 8 inactive rows into a separate Phase-2 sub-section.
- **Suggested fix:** Spec-side doc-fix in the next plan-review pass. Implementation is correct.

### AUDIT-PLAN-AND-API-B2-6 — Carry-over IMPL-1 re-deferral is sound but Issue-Index status string is misleading

- **Severity:** Nice-to-have
- **File:** `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/reports/B2-modular-orchestrator.md` (Issue Index, top of file)
- **Description:** The Issue Index row for IMPL-1 has status `"delegated-to-orchestrator (re-deferred to Block 3 / C8)"` and a long reason ending in `"... is Block 3 (subsystem-adapter migration, chunk C8) scope."` — which is *accurate*. However, the C4 report (line 442) and the C7 report (line 1037-1042) both add their own "IMPL-1 status update" sub-sections that reach the same conclusion via slightly different paths (C4: "unblocked by this chunk but still C7 scope"; C7: "Block 3 / C8 absorbs them naturally"). The audit-trail across two chunks is split. A reviewer searching for "IMPL-1" gets three voices saying the same thing with slightly different framing.
- **Why it matters:** The audit-trail is correct — just verbose. A future PR reviewer will need to follow the chain (B1 IMPL-1 carry-over → C4 unblock-but-still-deferred → C7 re-deferred to C8 with D5 reasoning) to understand the final disposition. Consolidating into a single Issue-Index reason saves them five minutes; the redundancy is the only cost today.
- **Suggested fix scope:** small — fold the C4 + C7 status-update paragraphs into a single Issue-Index entry that lists both unblock + re-defer rationale.
- **Suggested fix:** VAL-SANITY consolidator can include this as a doc-fix in the block-validate pass.

### AUDIT-PLAN-AND-API-B2-7 — `ModuleServicesFactory` collapsed into direct `ModuleServices` (Spec §4.7 → C4 deviation)

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/ModuleServices.kt:87` (direct `ModuleServices` constructor) + `app/src/main/java/net/devemperor/dictate/state/DictateOrchestrator.kt:128` (constructor signature `services: ModuleServices`)
- **Description:** Spec 1 §4.3 / §4.7 / §7.3 prescribes a two-class pattern: `ModuleServices` (data) + `ModuleServicesFactory(provider: () -> ModuleServices)` (lazy provider, `factory.get()` returns the services). The orchestrator's spec-signature is `servicesFactory: ModuleServicesFactory`; `runEffect` is called with `servicesFactory.get()` (Spec 1 line 704). The implementation collapses this to a single `services: ModuleServices` constructor argument — no factory layer. This is sensible (the factory adds indirection without observable benefit in Phase 1 — the service constructs the services exactly once in `onCreate`), but it's a multi-touchpoint deviation from the spec that the C4 Deviations table does not explicitly call out. The C4 report's deviations list four items; "ModuleServicesFactory collapsed" is not one of them.
- **Why it matters:** B3 implementers reading Spec §7.3 will pattern-match on `ModuleServicesFactory { ... }`-construction. They'll then have to discover the simplification. A documented deviation saves them the discovery step.
- **Suggested fix scope:** small — add a fifth row to the C4 Deviations table: "ModuleServicesFactory collapsed into direct `ModuleServices` constructor argument. Justification: only one construction point per service lifetime, factory indirection unused. Impact: B3 wiring uses `ModuleServices(...)` directly in `Service.onCreate`."
- **Suggested fix:** Documentation-only.

### AUDIT-PLAN-AND-API-B2-8 — `Action.PipelineAction.Submit` vs `TriggerPipeline` (spec naming drift)

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/Action.kt:135` (`TriggerPipeline`)
- **Description:** Spec 1 §15.1.x matrix references `PipelineAction.Submit` (Recording → Pipeline cell, LivePrompt → Pipeline cell, etc.). Spec 1 §11.2.2 Block-1b Step 5 refers to *"orchestrator.dispatch(Action.PipelineAction.X)"* without naming. The implementation chose `TriggerPipeline` (semantically equivalent — "begin a pipeline run for the just-recorded audio"). Both names are reasonable; `TriggerPipeline` is arguably clearer (no name-clash with the eventual reprocess "submit" path). The C5 deviations table doesn't list this rename.
- **Why it matters:** Three spec sites use `Submit`; one (KDoc on `TriggerPipeline`) uses `TriggerPipeline`. A future grep for `PipelineAction.Submit` finds nothing — readers will assume the action is missing.
- **Suggested fix scope:** small — document the rename in C5 Deviations table, or update the spec matrix to `PipelineAction.TriggerPipeline`.
- **Suggested fix:** Spec-update preferred (matrix is doc-only and only referenced from a few spec sites).

### AUDIT-PLAN-AND-API-B2-9 — `realToastSink` lives in the stub file (mixed-concern file)

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/PipelineServiceStubSubsystems.kt:189-197`
- **Description:** The file `PipelineServiceStubSubsystems.kt` is supposed to contain **only no-op stubs** (per its top-level KDoc: "Production-side **no-op stub subsystems**"). It also contains `realToastSink(applicationContext: Context): ToastSink` — a production-quality implementation that uses the real Android `Toast` system. The file's name + KDoc suggest the file holds nothing but stubs; mixing a real production binding in violates the file's stated contract. The C7 report notes this explicitly: *"The `ToastSink` has a **production-quality variant** (`realToastSink(applicationContext)`) bound to the system Toast — user-visible errors surface today via the stub."* — so the choice is deliberate.
- **Why it matters:** Future B3 work that swaps the stubs out for real adapters has to remember to leave `realToastSink` alone, or move it to a different file. The naming convention "file with `Stub` in the name = all stubs" is a useful invariant that this file currently breaks.
- **Suggested fix scope:** small — move `realToastSink` and `stubSessionRepo` into a separate file (e.g. `PipelineServiceSubsystems.kt`) or rename `PipelineServiceStubSubsystems.kt` to drop the "Stub" qualifier. Or: rename the file to make the mixed nature explicit.
- **Suggested fix:** Cosmetic — defer to a doc-fix pass.

## Coverage

- Files audited:
  - All production files in `app/src/main/java/net/devemperor/dictate/state/` (16 files in parent package + 14 modules)
  - `DictatePipelineService.kt` (service composition root)
  - `DictateInputMethodService.java` (consumer side, IME — Block-2-bind path lines 316-368, 467-483)
  - Block-report `B2-modular-orchestrator.md`, full read
  - `dictate-keyboard-layout-refactor.reviewed.chunks.json` C3–C7 entries
  - Spec 1 sections: §3, §4.2, §4.3, §4.5, §4.6, §4.7, §4.8, §7.3, §11.2.2 Block-1b, §15.1 + §15.1.x, §15.2
  - Spec 3 sections: §4.8, §7.1, §7.3 (transition table)
- Files skipped (with reason):
  - **Test files** — out of scope for `plan-and-api` topic (AUDIT-TEST covers them).
  - **ADR 0001-0005** — KDoc cross-references in production code were spot-checked for path validity, not full ADR-content compliance (that's ADR-CONFORM agent's domain).
- Knowledge-skill checkpoints applied: none (topic catalog assigns `knowledge-typescript` + `knowledge-reference` to `plan-and-api`; this is a Kotlin/Android project — no TypeScript skill applies and `knowledge-reference` patterns are observed inline via plan-quality-gate which already ran on the spec).

## Forward-compatibility check — verdict

The four spot-checks from the audit brief:

1. **B3 subsystem-migration (C8) ↔ `PipelineServiceStubSubsystems` interfaces.** ✅ **Compatible.** The 14 subsystem interfaces (`RecordingHardwareSubsystem`, `BluetoothScoSubsystem`, …, `AudioFileFactory`, `ToastSink`, `NotificationStatus`) are declared in `ModuleServices.kt` with KDoc-pinned method signatures matching the modules' `runEffect` arms 1:1. B3's adapter swap is a constructor-argument swap in `DictatePipelineService.onCreate` — no module-side touch.

2. **B3 DB-persistence (C9+C10) ↔ `PipelineRecovery` hook.** ✅ **Compatible.** `PipelineRecovery.recover(store)` is one suspend method that reads `sessionRepo.loadPending()` and writes the result. B3 swaps `PipelineSessionRepoSubsystem` from the stub `emptyList` impl to the real DAO-backed adapter; `PipelineRecovery` itself doesn't change. The Spec 1 §6.3 status-promotion + ghost-cleanup logic is correctly deferred to B3 in the `PipelineRecovery` KDoc.

3. **B4 (LayoutCatalog) ↔ `LayoutModule` + `LayoutAction`.** ✅ **Compatible.** `LayoutAction` has 4 leaves (Toggle×2 + SetSmallMode + SetContentArea); `LayoutModule` enforces the atomic `setSmallMode` contract (Spec 2 §4.1) in two reducer arms with shared logic. B4's catalog can dispatch `LayoutAction.SetContentArea(QWERTZ|EMOJI_PICKER|MAIN_BUTTONS)` cleanly. One forward-compat note: the small+non-MAIN_BUTTONS guard returns `null` (Rejected) — B4 click-resolvers must not assume "every dispatch flips the area" because the rejection is silent at the action-level.

4. **B5 (Overlay) ↔ `OverlayModule` + `OverlayAction` + `OverlayPermissionGate`.** ⚠ **Mostly compatible.** `OverlayAction` has the full 8-leaf surface; `OverlayModule.runEffect` for `OpenOverlayPermissionSettings` is a no-op stub in C7 (logs only) — B5 has to swap it for `services.activityLauncher.openOverlayPermissionSettings()` AND add `ActivityLauncher` to `ModuleServices`. Per AUDIT-PLAN-AND-API-B2-4, the spec also lists `NotifyOverlayPermissionRequired : Effect` which is currently missing — B5 will need to add it back if the permission-loss-notification path is to land. The OverlayPermissionGate referenced in the audit brief lives entirely on the B5 side; C7 doesn't introduce a `permissionGate` subsystem yet.

## Out-of-scope observations

- **TEST topic:** `TestOnlyModules.kt` lives in `app/src/main/` (production source-set) with `@VisibleForTesting`. The C4 report justifies this thoroughly (sealed-interface cross-package implementation rule). Worth flagging to AUDIT-TEST in case there's a coverage / ProGuard rule that should be added.
- **CONVENTION topic:** PrefMirror's overlay raw-key constants and `OverlayModule.runEffect`'s `Effect.PersistOverlayPosition` write-path share the literal strings `"overlay_pos_portrait"` / `"overlay_pos_landscape"`. The OverlayModule effect builds them inline via string concatenation; PrefMirror has named constants. The two sites need to stay in lockstep — a refactor that changes one and not the other silently breaks the mirror. Worth a CONVENTION-pass note.
- **LOGIC topic:** `DictateOrchestrator.dispatchInternal` step 4 catches all `Throwable`s and re-dispatches as `EffectFailure`. The catch correctly excludes nothing (Spec 1 §4.3) — but the recursive `dispatchInternal(EffectFailure, depth+1)` at line 329 increments the depth on success. If the EffectFailure handler's reducer itself throws (`runEffect` arm of `reduceFailure`-emitted effect), the cascade can chain at most 8 deep before MAX_CASCADE_DEPTH kicks in. Behavior is correct; worth pinning a logic test for the "EffectFailure inside EffectFailure" pathological case.
