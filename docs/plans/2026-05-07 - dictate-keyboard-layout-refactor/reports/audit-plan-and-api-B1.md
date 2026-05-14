# Audit Report: plan-and-api (Block 1, scope: full-block)

**Agent-ID:** B1-AUDIT-PLAN-AND-API
**Date:** 2026-05-15
**Knowledge skills used:** knowledge-reference (code-API contracts), knowledge-adr-format (ADR-0003 alignment check)
**Files inspected:** 12

- `app/src/main/java/net/devemperor/dictate/core/KeyboardVisibilityPredicates.kt` (new)
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt` (new)
- `app/src/main/java/net/devemperor/dictate/core/RecordingUiController.kt` (modified)
- `app/src/main/java/net/devemperor/dictate/core/KeyboardUiController.kt` (modified)
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` (modified)
- `app/src/main/AndroidManifest.xml` (modified)
- `app/src/main/res/values/strings.xml` (modified)
- `app/src/main/res/values-de/strings.xml` (read — verified absence)
- `gradle/libs.versions.toml` (modified)
- `app/build.gradle` (modified)
- `app/src/test/java/net/devemperor/dictate/core/KeyboardVisibilityPredicatesTest.kt` (new)
- `app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceTest.kt` (new)

## Summary

- Critical: 0
- Important: 3
- Nice-to-have: 4

Block 1 implements the plan-Block-1a quick-wins (predicate helper + recordButton hybrid resolution + 2 KSM.refresh calls) and the plan-Block-2 Service-skeleton (FGS, NotificationChannel, LocalBinder, IME bind/unbind) substantively per Spec 1 §11.1, §11.2.2, §11.3, §11.5 and ADR-0003 §"Required mechanics" items 1-5. The deferred JobExecutor-init (Spec 1 §11.2.2 Block 2 sub-step 7, tracked as IMPL-1) is correctly routed to Block 1b via `delegated-to-orchestrator` with a sound rationale grounded in Spec 1 §7.3 Composition-Root. No stubs or TODO markers in production code beyond the documented Block-1b transition points. Three Important findings address (a) missing German translation for `dictate_service_not_ready` and 5 other new strings (explicit "Pflicht-Aufgabe Block-2" per Spec 1 §11.3.2a line 4968-4969), (b) silent deferral of Spec 1 §11.2.2 Block-2 sub-step 6 (POST_NOTIFICATIONS runtime prompt in OnboardingActivity), and (c) substantive LocalBinder surface divergence from ADR-0003 §"Required mechanics" item 3 (`service` accessor + `dispatch(action: Any)` instead of `state: StateFlow<DictateUiState>` + `dispatch(action: Action): DispatchOutcome`) that is not flagged in the Deviation table.

## Findings

### AUDIT-PLAN-AND-API-B1-1

- **Severity:** Important
- **File:** `app/src/main/res/values-de/strings.xml`, `app/src/main/res/values-es/strings.xml`, `app/src/main/res/values-pt/strings.xml`
- **Description:** Six new strings were added to `values/strings.xml` (`dictate_pipeline_service_description`, `dictate_pipeline_channel_name`, `dictate_pipeline_channel_description`, `dictate_pipeline_notif_title`, `dictate_pipeline_notif_idle`, `dictate_service_not_ready`) but none of them were added to any of the three localized `values-de/`, `values-es/`, `values-pt/` `strings.xml` files. Spec 1 §11.3.2a (line 4968-4969) explicitly calls this out for `dictate_service_not_ready`: "Pflicht-Aufgabe Block-2: neue String-Resource `dictate_service_not_ready` (DE: 'Service startet noch — bitte kurz warten.', EN: 'Service is starting — please wait a moment.')". The Block 1 implementation also added 5 other user-visible strings (channel name + description, notification title + idle subtitle, service description) which the FGS surfaces to the system tray and notification panel.
- **Why it matters:** German + Spanish + Portuguese users see the English fallback for the persistent FGS notification + system-tray entries — for the persistent notification displayed during recording/transcription this is highly user-visible. Specifically `dictate_pipeline_notif_idle` ("Ready") shows on the lock-screen + status bar. The deviation is not flagged in the C2 Deviations table; the block-report's `### Implementation` subsection lists the strings as "Resource additions" with no localization-deferral entry.
- **Suggested fix scope:** small (one-file-per-locale, mechanical translation)
- **Suggested fix:** Add localized entries to `values-de/strings.xml`, `values-es/strings.xml`, `values-pt/strings.xml` for all six new strings. The DE translations are explicitly provided in Spec 1 §11.3.2a line 4968. Other locales should be translated consistently.

### AUDIT-PLAN-AND-API-B1-2

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/onboarding/OnboardingActivity.java`, `app/src/main/java/net/devemperor/dictate/onboarding/OnboardingAdapter.java`
- **Description:** Spec 1 §11.2.2 Block-2 sub-step 6 explicitly assigns "POST_NOTIFICATIONS Runtime-Permission-Prompt in Onboarding ergänzen (§11.5.1)" to Block 2 as one of the 7 sub-steps. The block-report's `Overlooked points / known gaps` for C2 silently defers this: "Phase-B S-5 POST_NOTIFICATIONS-Prompt (Onboarding/Settings — separate UI surface, not a service-skeleton concern)". `grep -rn "POST_NOTIFICATIONS" app/src/main/java/net/devemperor/dictate/onboarding/` returns no hits — only the existing RECORD_AUDIO request in `OnboardingAdapter.java:70`. The deferral is not recorded as a `delegated-to-orchestrator` Issue in the Issue Index; it's only mentioned as an "Overlooked point".
- **Why it matters:** On API 33+ devices, the FGS notification will not be visible by default if the app installs fresh and the user never sees the prompt. The Phase-B S-5 acceptance ("POST_NOTIFICATIONS-Prompt") in Spec 1 §10 is verified manually + via `OnboardingPostNotifPromptTest.kt` — silent deferral makes Block 2 not actually satisfy plan-§10 Block-2 acceptance even though the block-report claims "all 7 sub-steps … covered except sub-step 7". The agent's rationale ("UI surface, not service-skeleton concern") is plausible but should be a flagged Issue (severity Important, `delegated-to-orchestrator`) with a routed target block, not a silent gap. D22 plan-deviation autonomy requires deviations of this scale to be flagged explicitly.
- **Suggested fix scope:** medium (add a `delegated-to-orchestrator` Issue entry IMPL-2 to the C2 block-report; route to the appropriate target block — most likely a Phase 4.5 runbook line item or a dedicated "Block-2-Onboarding-Completion" mini-chunk)
- **Suggested fix:** Either (a) implement the POST_NOTIFICATIONS runtime prompt in `OnboardingActivity` now (single file, ~30 LOC `ActivityResultLauncher` per Spec 1 §11.5.1), or (b) escalate the silent deferral to an explicit Issue with `delegated-to-orchestrator` status and a named target block + a documented rationale for why "onboarding flow modification" is out of scope for Block 2.

### AUDIT-PLAN-AND-API-B1-3

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:292-319`
- **Description:** ADR-0003 §"Required mechanics" item 3 specifies "Local Binder, same process. `onBind` returns a `LocalBinder(orchestrator)` exposing exactly two surfaces: `state: StateFlow<DictateUiState>` and `dispatch(action: Action): DispatchOutcome` (plus lifecycle hooks)." The Block-2 LocalBinder exposes (a) `service: DictatePipelineService` instead of `state: StateFlow<DictateUiState>`, and (b) `dispatch(action: Any)` returning `Unit` instead of `dispatch(action: Action): DispatchOutcome`. The class-KDoc + the dispatch-stub KDoc transparently document this as a temporary skeleton state and call out "Block 1b replaces the stub with a real `orchestrator.dispatch(action)` call **without** changing the binder contract", which is plausible — but this substantive deviation from the ADR contract is NOT in the C2 Deviations table (the table only lists D4 Robolectric introduction + D5 JobExecutor deferral).
- **Why it matters:** ADR-0003's "exactly two surfaces" is a binding contract per the ADR's `Status: Accepted`. Documenting the skeleton-state divergence only in source-code KDoc (and not in the block-report's Deviations table) means Block-1b's audit pass cannot reliably catch a regression where the `service` accessor leaks beyond Block 1b. Per D22, a deviation of this size ("plan/architecture issue, mid-size, solution clear from plan knowledge") should appear in `### Deviations` with the `plan-deviation-resolved` marker. The IMPL agent self-classified this as "Inline-fixed items" without an explicit deviation entry.
- **Suggested fix scope:** small (add a Deviation entry D6 to the C2 block-report capturing the skeleton-state ADR-0003 divergence + the Block-1b restoration path)
- **Suggested fix:** Append a deviation table row: "D6 | ADR-0003 §'Required mechanics' item 3 | `LocalBinder` exposes `service` + `dispatch(Any)` instead of `state: StateFlow` + `dispatch(Action): DispatchOutcome` | Skeleton — orchestrator doesn't exist until Block 1b; widening `Any` → `Action` and adding `state: StateFlow` is a non-breaking IME-side change once orchestrator is wired | Block 1b restores the canonical surface | flagged-for-validate (plan-deviation-resolved)". No code change required.

### AUDIT-PLAN-AND-API-B1-4

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/KeyboardVisibilityPredicates.kt:75-84`
- **Description:** The C1 IMPL agent's claim that `predResendVisible` mirrors "the future LayoutCatalog RESEND-slot predicate (Spec 2 §3.2) so Block 5 lifts the body verbatim" is not strictly accurate at the signature level. Spec 2 §3.2 defines `visibilityPredicate: (DictateUiState) -> Boolean` — a single-argument predicate over the global `DictateUiState`. The Block-1a helper has the shape `(Boolean, Boolean, RecordingState, PipelineUiState) -> Boolean` — four un-packed arguments. The truth-table is identical (same 4 axes ANDed in same order), but the call form is different. Block 5 will need to rewrite the call site (`state.resend.lastAudioExists && state.resend.resendEnabled && state.recording is RecordingState.Idle && state.pipeline is PipelineUiState.Idle`) — a one-line projection over `DictateUiState`, not a verbatim body lift.
- **Why it matters:** Wording-only — the truth-table preserves correctly. But the file-header KDoc and the class-level claim ("Block 5 (LayoutCatalog) lifts this predicate verbatim into the `RESEND` slot's `visibilityPredicate`") slightly overstates the migration cost reduction. A future reader skimming the KDoc could expect a literal copy-paste; in reality, Block 5 will pack the 4 sub-state reads into a single `DictateUiState` accessor.
- **Suggested fix scope:** small (KDoc tweak — clarify "Block 5 collapses the 4 args into a single-state-arg signature; the truth-table body is preserved")
- **Suggested fix:** Replace "Block 5 lifts the body verbatim" with "Block 5 collapses the 4-arg signature into the single-state-arg form `(DictateUiState) -> Boolean` per Spec 2 §3.2; the truth-table body — same 4 axes ANDed in same order — is preserved".

### AUDIT-PLAN-AND-API-B1-5

- **Severity:** Nice-to-have
- **File:** `docs/decisions/0003-service-foreground-pipeline-architecture.md:97`
- **Description:** ADR-0003 §"Required mechanics" item 1 has the manifest snippet `<service android:name=".pipeline.DictatePipelineService" …>` (package `.pipeline.`). The Block 1 implementation places the service at `.core.DictatePipelineService` (matches Spec 1 §11.5 line 1562 explicit code-pointer `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt`). The implementation aligns with Spec 1 (the canonical file-placement source); the ADR snippet is at variance with the spec. Strictly speaking this is an ADR drift, not an implementation drift.
- **Why it matters:** Future maintainers reading ADR-0003 might place a sibling service or sub-package under `.pipeline.` based on the ADR snippet, creating split-package drift. The ADR is `Status: Accepted` (append-only); the appropriate response is to add a Decision-History entry that corrects the path.
- **Suggested fix scope:** small (single-line correction in ADR-0003 + Decision-History entry)
- **Suggested fix:** Open a follow-up issue to amend ADR-0003 line 97 `.pipeline.DictatePipelineService` → `.core.DictatePipelineService` with a Decision-History entry "**Trigger:** B1 implementation discovered package-path drift. **Before:** `.pipeline.`. **After:** `.core.`. **Reasoning:** Spec 1 §11.5 line 1562 is the canonical file-placement source; the implementation followed Spec 1." Note: outside Block 1 audit scope to actually fix.

### AUDIT-PLAN-AND-API-B1-6

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:1967`
- **Description:** The `onShowResend` callback site (the 6th original resendButton mutation, line 1967) is intentionally left as explicit `resendButton.setVisibility(View.VISIBLE);` per Deviation D1 (the predicate would evaluate to false because pipeline state is still `Running` when this callback fires). The Deviation D1 entry is well-documented in the block-report and inline in source code. Block 1a's acceptance bullet (Spec 1 §10 line 4313) says "die 6 verstreuten Mutations-Sites" — 5 of 6 are migrated, 1 (the deviation) is documented. The pattern note in `KeyboardVisibilityPredicates.kt` KDoc (lines 24-28) accurately enumerates only **five** call sites that read the predicate ("Each site re-derived the answer from a different combination of inputs"), leaving the 6th (onShowResend, unconditional VISIBLE) implicit; but the KDoc concludes with "[predResendVisible] consolidates the rule into a pure function so all five 'compute the answer from current state' sites read the same expression. The unconditional VISIBLE in `onShowResend` is kept as an explicit call site for now …" which is fine. The C1 IMPL deviation justification (D1) is sound: Block 5 (LayoutCatalog) folds the explicit setter into a state-driven subscriber and re-orders pipeline-completion. Not a finding per se — just confirming the audit checked it.
- **Why it matters:** Tracking — confirms 5 of 6 sites are migrated and the 6th has a sound deferral with a named Block-5 fix.
- **Suggested fix scope:** none — confirmation only
- **Suggested fix:** None.

### AUDIT-PLAN-AND-API-B1-7

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:69`
- **Description:** `serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` matches Spec 1 §7.3 line 3764 verbatim. The KDoc references "ADR-0001 §'Required mechanics'" — but ADR-0001 (`0001-state-modular-orchestrator-pattern.md`) is the modular-orchestrator ADR, which talks about single-dispatch but does not enforce a specific dispatcher choice. The single-dispatch Main-thread-confinement is a side-effect of the orchestrator's `dispatchInternal` running on the constructor scope, not a direct ADR-0001 contract. The KDoc's claim "the orchestrator's single-dispatch path is Main-thread-confined per ADR-0001 §'Required mechanics'" is loosely accurate (ADR-0001 §"Required mechanics" item 1 forbids forwarder methods and item 2 names "Main-thread dispatch via `Dispatchers.Main.immediate`") but the precise pointer would land closer to Spec 1 §4.3 (the orchestrator's `dispatch` impl detail).
- **Why it matters:** Documentation precision — a future reader checking ADR-0001 against the implementation might not find an explicit "must use `Main.immediate`" clause. The pointer survives audit because ADR-0001 does mention Main-thread-immediate dispatch in item 2 (verified — see ADR-0001).
- **Suggested fix scope:** small (refine the `@see` pointer to point at Spec 1 §4.3 + ADR-0001 §"Required mechanics" item 2 jointly)
- **Suggested fix:** No code change required; if a documentation pass touches the file, broaden the `@see` pointer.

## Coverage

- Files audited: KeyboardVisibilityPredicates.kt, DictatePipelineService.kt, RecordingUiController.kt, KeyboardUiController.kt, DictateInputMethodService.java (resend + bind sites + KSM.refresh additions), AndroidManifest.xml (4 permissions + service entry), strings.xml (new entries + locale absence check), build files (Robolectric introduction), B1 block-report, Spec 1 §11.1 + §11.2.2 + §11.3 + §7.3 + §9.4 + §13.1, Spec 2 §3.2 (forward-compat predicate signature), ADR-0003 §"Required mechanics" items 1-9.
- Files skipped (with reason): the two test files were not deeply audited beyond test-count verification (covered separately by `B1-AUDIT-TEST`). Other modified `core/` files cited in the IMPL deviation table were spot-checked for resend-site migration; no IME-Service-level architectural concerns surfaced.
- Knowledge-skill checkpoints applied: `knowledge-reference` (verified API-consumer match between IME-side `pipelineConnection` + `pipelineBinder` cast and the C2 service's `LocalBinder`/`onBind` impl — both align on same-process `IBinder` casting); `knowledge-adr-format` (cross-checked ADR-0003 §"Required mechanics" items 1-9 against the C2 service implementation + manifest diff; identified the §3 `state`+`dispatch` surface drift documented in finding B1-3, and the manifest-path drift in B1-5).

## Out-of-scope observations

- **For AUDIT-CONVENTION:** The IME `onCreateInputView`'s bind-attempt block uses `ContextCompat.startForegroundService(this, pipelineIntent)` while the Spec 1 §11.3.1 snippet uses `ContextCompat.startForegroundService(this, intent)` — identical, but a future Block 5+ rebind path may want a shared helper to avoid divergence on retry. Not in current diff; flagged here for cross-block convention review.
- **For AUDIT-TEST:** The 10 Robolectric tests in `DictatePipelineServiceTest.kt` do not cover the IME-side bind/unbind lifecycle, which the C2 block-report explicitly acknowledges as an "intentional coverage gap" with E2E TC-15 as the runbook compensation. AUDIT-TEST should verify the E2E runbook line item exists.
- **For AUDIT-LOGIC:** `onBindingDied` in `DictateInputMethodService.java` re-binds via a new `bindService` call inside the connection's callback. Same-process should not trigger this, but the defensive re-bind path is untested. Edge case.
