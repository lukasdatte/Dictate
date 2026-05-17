# Phase 4.6 — Documentation Discovery

**Date:** 2026-05-17
**Plan:** docs/plans/2026-05-15 - dictate-cutover-completion/dictate-cutover-completion.md
**Diff range:** 65bb303..HEAD (922a19d) — 69 Epic commits
**Plan-touched files:** 49 production source files (.java/.kt, tests excluded), 98 incl. tests
**Agent-ID:** B0-DOCS-DISC (read-only)

> [!NOTE]
> **State-file Documentation-Plan block absent.** The orchestrator prompt
> referenced a `## Documentation Plan` block / `phase_4_6_activation: full`
> / `doc_plan_sketch` in `dictate-cutover-completion.state.md`. That block
> **does not exist** in the state file (483 lines, no `doc`/`4.6`/`phase_4_6`
> token; the file uses `## plan_lifecycle`-style lowercase sections only and
> ends after the Phase-4 YAML). This discovery proceeds from the Epic plan +
> reports + code directly and **recommends the orchestrator author the
> Documentation-Plan block from this report** before dispatching 4.6c
> workers. This is a process gap, not a doc gap — flagged in Summary.

## Existing documentation found

| Scope | Path | Lines | Last modified |
|-------|------|-------|---------------|
| adr | docs/decisions/0001-state-modular-orchestrator-pattern.md | ~395 | 2026-05-15 |
| adr | docs/decisions/0002-state-cross-module-cascade.md | ~? | 2026-05-14 |
| adr | docs/decisions/0003-service-foreground-pipeline-architecture.md | ~404 | 2026-05-15 |
| adr | docs/decisions/0004-ui-layout-catalog-motionlayout.md | ~? | 2026-05-14 |
| adr | docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md | ~432 | 2026-05-15 |
| adr-index | docs/decisions/README.md | ~120 | 2026-05-14 |
| architecture | docs/architecture/state-architecture/README.md | ~330 | 2026-05-15 |
| architecture | docs/architecture/state-architecture/state-and-actions.md | ~340 | 2026-05-14 |
| architecture | docs/architecture/state-architecture/modules.md | ~370 | 2026-05-14 |
| architecture | docs/architecture/state-architecture/adding-a-module.md | ~440 | 2026-05-15 |
| architecture | docs/architecture/state-architecture/cross-module-cascade.md | ~390 | 2026-05-14 |
| architecture | docs/architecture/state-architecture/effects-and-failures.md | ~360 | 2026-05-14 |
| architecture | docs/architecture/state-architecture/forbidden-patterns.md | ~510 | 2026-05-14 |
| architecture | docs/architecture/state-architecture/rendering.md | ~500 | 2026-05-14 |
| architecture | docs/architecture/state-architecture/triangle-fsm.md | ~470 | 2026-05-14 |
| architecture | docs/architecture/state-architecture/wiring-ui.md | ~340 | 2026-05-14 |
| architecture | docs/architecture/state-architecture/adding-a-button.md | ~300 | 2026-05-14 |
| architecture | docs/architecture/state-architecture/adding-a-sub-keyboard.md | ~300 | 2026-05-14 |
| db-pattern | docs/DATABASE-PATTERNS.md | ~360 | 2026-05-15 |
| claude.md | CLAUDE.md (root) | ~75 | (repo root) |
| spec (plan-co-located) | …/research/render-path-cutover.md | ~890 | 2026-05-17 |
| spec (plan-co-located) | …/research/recording-audiofocus-btsco-handshake.md | ~? | 2026-05-15 |
| spec (plan-co-located) | …/research/sendstaging-isstarting-guard-semantics.md | ~? | 2026-05-15 |
| research (plan-co-located) | …/research/imported-audiofile-orchestrator-route.md | ~? | 2026-05-15 |
| parent ADRs/specs | docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/… (Spec 1/2/3 — SoT) | huge | parent-plan |

No `docs/api/`, `docs/runbooks/`, `docs/research/` (plan-free), or nested
CLAUDE.md. No module-level `README.md` under `app/src/`. Documentation
infrastructure = ADRs + the `state-architecture/` doc-set + DATABASE-PATTERNS
+ root CLAUDE.md + the parent-plan specs (SoT).

## Plan-touched files → related docs

The Epic touches 49 production files. Grouped by subsystem; the cutover
classes carry their own substantive inline anchors already (see
Inline-anchor inventory — the B1/B2/B5 IMPL agents wrote
header+`@see`+gotcha per the convention). File-level doc impact is
concentrated in the `state-architecture/` doc-set + the ADR
Decision-Histories.

| Source file (subsystem) | Related docs | Action class |
|-------------------------|--------------|--------------|
| core/PipelineRunnerSubsystemAdapter.kt **(new)** | ADR-0001/0003 (DH), state-architecture/README.md (the "B3 absorbs PipelineOrchestrator" line), Spec 1 §9.6 | needs-update (ADR-DH + README stale line) |
| core/PipelineNotificationCoordinator.kt **(new)** | ADR-0003 (DH), Spec 1 §7.4/§7.6/§11.1.2 | needs-update (ADR-0003 DH) |
| core/PipelineActionRouter.kt **(new)** | ADR-0003 (DH), Spec 1 §7.5 | needs-update (ADR-0003 DH) |
| core/ImePipelineConfigResolver.kt **(new)** | ADR-0001 (DH, R-1 config-fidelity seam), Spec 1 §15 | needs-update (ADR-0001 DH) |
| core/DictatePipelineService.kt (onCreate Step 3/4) | ADR-0001/0003 (DH), state-architecture/README.md, modules.md §7.1 | needs-update |
| core/DictateInputMethodService.java (recording-trigger flip) | ADR-0001/0005 (DH), state-and-actions.md §1.1 | needs-update |
| state/render/RenderGate.kt **(new)** | ADR-0004/0005 (DH), render-path-cutover.md §6, rendering.md | needs-update (ADR-0005 DH + rendering.md) |
| core/audit/VisibilityWriteAuditLogger.kt **(new)** | render-path-cutover.md §10, rendering.md | needs-update (rendering.md) |
| state/render/EditBarController.kt **(new)** | rendering.md, wiring-ui.md, Spec 2 §13.2 | needs-update (rendering.md/wiring-ui.md) |
| state/render/EmojiController.kt **(new)** | rendering.md, wiring-ui.md, Spec 2 §13.2 | needs-update |
| state/render/OverlayCharactersController.kt **(new)** | rendering.md, Spec 2 §13.1 | needs-update |
| state/render/PipelineStepRowRenderer.kt **(new)** | rendering.md, Spec 2 §9.x | needs-update |
| state/render/QwertzRecordingController.kt **(new)** | rendering.md, Spec 2 §9.4 | needs-update |
| state/render/{ContentArea,PromptVisibility,OverlayReset,SpecialTouchHandlerInstaller}* | rendering.md, wiring-ui.md, ADR-0004 (DH for render-flip) | needs-update |
| core/EditNumbersAnimator.kt **(new)** | rendering.md, Spec 2 §9.2 | needs-update (minor) |
| preferences/LanguageResolver.kt **(new)** | ADR-0001 (DH, D-13 LanguageController→LanguageModule+Resolver split), modules.md (LanguageModule axis) | needs-update (ADR-0001 DH) |
| core/LanguageController.kt **(DELETED)** | state-and-actions.md §1.1 (lists it as a live "scattered writer"), README.md | needs-update (stale: now deleted) |
| core/MainButtonsController.kt **(DELETED)** | state-and-actions.md §1.1, rendering.md | needs-update (stale: now deleted) |
| core/RecordingUiController.kt **(DELETED)** | state-and-actions.md §1.1 | needs-update (stale: now deleted) |
| core/KeyboardUiController.kt **(DELETED)** | state-and-actions.md §1.1, rendering.md | needs-update (stale: now deleted) |
| core/KeyboardStateManager.kt **(DELETED)** | state-and-actions.md §1.1, rendering.md (visibility-drive) | needs-update (stale: now deleted) |
| core/PipelineOrchestrator.kt (annotated as adaptee) | README.md "Note on naming" + "B3 absorbs" line, ADR-0003 (DH), Spec 1 §9.6 | needs-update (README stale verb tense) |
| state/Action.kt, state/DictateUiState.kt (F-10/F-12/F-13/F-15) | ADR-0001 (DH — same-class as the 2026-05-15 F-1 manual-paste DH entry), state-and-actions.md | needs-update (ADR-0001 DH) |
| state/modules/{Recording,Pipeline,Audio,Language}Module.kt | modules.md §7.1, ADR-0001 (DH) | no-change (axis-count unchanged; inline anchors present) |
| state/PipelineServiceStubSubsystems.kt (stubs @Deprecated) | ADR-0001/0003 (DH) | needs-update (ADR-DH) |
| database/DurationHealingScheduler.kt **(new)** | ADR-0003 (cleanup-policy area), DATABASE-PATTERNS.md | no-change (orthogonal helper; no schema change) |
| preferences/{InputLanguagesLegacyMigration,InputLanguagesPlugin,LanguageResolver,versioned/VersionedPrefs}.kt | (no related architecture doc — preferences subsystem undocumented) | no-change |
| state/layout/{ActionResolvers,ButtonSlot,KeyboardLayoutManager,LayoutCatalog,TextResolvers}.kt | rendering.md, adding-a-button.md, ADR-0004 | no-change (resolver tweaks; inline anchors present) |
| core/{ActiveJobRegistry,JobExecutor,KeyboardVisibilityPredicates,PipelineUiStateReader}.kt | rendering.md / Spec 1 (JobExecutor=adaptee) | no-change |
| DictateApplication.java, settings/PreferencesFragment.java | (no related architecture doc) | no-change (LanguageController-removal call-site edits) |

**DATABASE-PATTERNS.md: NOT affected.** Independently confirmed: Room
`@Database(version=)` stays **v4** (integration-check.md axis E-7,
e2e-test.md Pre-Flight #18 — "no `@Database(version=5)`, code-only blast
radius"). The Epic added zero schema changes. No DATABASE-PATTERNS update.

## Spec inventory (Conversion candidates)

| Spec file | Lines | Has `## Specification`-equivalent | Convertibility | Suggested target |
|-----------|-------|-----------------------------------|----------------|------------------|
| research/render-path-cutover.md | ~890 | yes (`## 2 Acceptance Criteria`, `## 3 Per-Behaviour-Group→Owner Mapping (the SoT table)`, `## 9 Verification Gate`) — `status: Spec — programmer-ready` | **C — stays plan-co-located spec (do NOT convert)** | n/a |
| research/recording-audiofocus-btsco-handshake.md | ~? | research/spec hybrid (B2 R-1 handshake) | C — stays plan-co-located | n/a |
| research/sendstaging-isstarting-guard-semantics.md | ~? | research (F-12 option-b rationale) | C — stays plan-co-located | n/a |
| research/imported-audiofile-orchestrator-route.md | ~? | research (B2-C7-MID-W1) | C — stays plan-co-located | n/a |

**Spec-conversion recommendation: NO conversion.** `render-path-cutover.md`
is **derivative**, not a new architecture source: its own header states
*"SoT: Spec 2 §9.1–§9.6 … Spec 1 §9.6. This spec adopts those
dispositions; it does not redesign them."* Per D21 SSoT, the parent-plan
`state-architecture/` doc-set + ADR-0004/0005 + Spec 1/2 remain the
canonical architecture homes. Converting render-path-cutover.md to a
permanent `docs/architecture/state-architecture/` doc would create a
second SoT for the render path (the exact SSoT anti-pattern the ADR-0001
2026-05-14 cleanup DH entry fixed). The render-cutover *outcome* (legacy
controllers deleted, RenderBackend sole driver, the EditBar/Emoji/
OverlayChars/Qwertz/StepRow new owners) belongs in `rendering.md` +
**ADR-0004/0005 Decision-History**, not a copied spec. Recommendation: it
stays a plan-co-located spec; its decisions flow into `rendering.md`
updates + ADR-0004 DH (render-flip) + ADR-0005 DH (the IME
recording-trigger→dispatch flip already partially covered by the
2026-05-15 ADR-0005 DH entry).

## ADR gap-check (flag only — Phase 4.6 does NOT write ADRs)

Per lifecycle-adr.md: the Epic *implements/extends* Accepted ADRs. These
are **Decision-History append entries on existing Accepted ADRs**, not
new ADRs and not reversals (the Epic explicitly chose INT-1 option (a) =
*implement the parent ADRs' intended end-state*, not deviate from them).
All three ADRs already have post-Accepted DH entries (e.g. ADR-0001
2026-05-15 F-1, ADR-0003 2026-05-15 cleanup-policy, ADR-0005 2026-05-15
IME-activation) — the format is established and append-only.

### ADR-0001 (state modular-orchestrator / single-dispatch) — DH entry needed

- **Trigger sketch:** Epic `dictate-cutover-completion` — INT-1 resolution
  (parent-plan Phase-4 escalation). `reports/integration-check.md` Central
  Verdict §1/§2.
- **Before:** Two-orchestrator coexistence — `DictateOrchestrator` was a
  parallel-dormant state-router; `ModuleServices.pipelineRunner` /
  `.notificationCoordinator` were `Log.w` no-op stubs
  (`PipelineServiceStubSubsystems`); the IME drove production via legacy
  `JobExecutor.INSTANCE.start`. ADR-0001's single-dispatch was nominally
  in place but **not the production driver** for recording.
- **After:** Coexistence **collapsed**. `DictateOrchestrator` is the sole
  state-router for production recording — real `PipelineRunnerSubsystemAdapter`
  (JobExecutor-delegating, OQ-1 thin-delegation) +
  `PipelineNotificationCoordinator` wired in `DictatePipelineService.onCreate`
  Step 3/4; stubs `@Deprecated` test-only; IME dispatches
  `RecordingAction.StartRecording/StopRecordingAndSend` (single-dispatch
  per AC-10). New seams added under the pattern: `ImePipelineConfigResolver`
  (R-1 IME-config fidelity), `preferences/LanguageResolver` (D-13
  LanguageController→stateless-resolver+LanguageModule split). F-10/F-12/
  F-13/F-15 state-shape: sessionId on `RecordingState`, FSM
  `ReprocessStaging→Preparing` single-submit guard (no `isStarting` field —
  option-b), `Running.completedSteps/totalSteps/elapsedMs`, language-aware
  `dictateButtonText` — module axis-count **unchanged** (13 active + 1
  Phase-2 stub).
- **Reasoning:** D7 — a permanent parallel-dormant layer is two
  implementations of recording forever (the exact anti-pattern the
  modular-orchestrator pattern exists to kill). `JobExecutor`/
  `PipelineOrchestrator` survive only behind the `PipelineRunnerSubsystem`
  interface (Spec 1 §9.6 — never deleted, the adaptee). This DH entry
  records that ADR-0001's single-dispatch is now the production driver,
  not a dormant parallel layer.
- **Reference:** `reports/integration-check.md` (INT-1 RESOLVED Central
  Verdict); `research/render-path-cutover.md`;
  `research/sendstaging-isstarting-guard-semantics.md`.

### ADR-0003 (service foreground pipeline architecture) — DH entry needed

- **Trigger sketch:** Epic Theme-B — real FGS notification coordinator +
  action-router; BT-SCO/audio-focus Preparing-lifecycle (B2-VAL-W1 F-1/F-2,
  the Critical BT-SCO-hang).
- **Before:** `notificationCoordinator` was a `Log.w` no-op stub; the
  Spec 1 §7.4 persistent FGS notification + §7.5 action-button
  back-channel were unimplemented on the new path; the legacy notification
  path was the only one delivering Spec 1 §10 Block-2 acceptance.
- **After:** Real `PipelineNotificationCoordinator` (Spec 1 §7.4/§7.6/
  §11.1.2 — single-source `NOTIF_ID`, `buildInitial()`, `show`/`dismiss`,
  channel-reuse) + `PipelineActionRouter` (§7.5 — `[Pause][Stopp][Senden]`
  PendingIntent → `orchestrator.dispatch`, targets the FGS so it works
  while the IME-view is dead — ADR-0003's keyboard-switch-survival point).
  The BT-SCO/audio-focus handshake lifecycle moved to the
  Preparing-state-confined `AudioModule` (B2-VAL-W1 F-1/F-2; see
  `research/recording-audiofocus-btsco-handshake.md`).
- **Reasoning:** the FGS-container ADR's whole point (recording survives a
  keyboard switch) was only delivered by the legacy notification path; the
  new path's coordinator was inert. This entry records the real
  coordinator/router as the production notification surface.
- **Reference:** `reports/B2-theme-b-recording-drive.md`;
  `research/recording-audiofocus-btsco-handshake.md`; Spec 1 §7.4/§7.5/§7.6.

### ADR-0005 (Triangle-FSM keyboard/widget/hover) — DH entry needed

- **Trigger sketch:** Epic Theme-B/Theme-C-R — IME recording-trigger
  flipped to dispatch; the render-path cutover (4 controllers deleted,
  RenderBackend sole driver); the OnRecordLongPress 2-mode model.
- **Before:** The 2026-05-15 ADR-0005 DH entry pinned the IME-activation
  *view* contract (`OnImeViewShown/Hidden`), but recording was still
  driven by legacy `JobExecutor.start` and the legacy render controllers
  (`MainButtonsController`/`KeyboardUiController`/`RecordingUiController`/
  `KeyboardStateManager`) were the live render path attached **in
  parallel** to `ImeViewBackend`.
- **After:** IME recording-trigger dispatches `RecordingAction.*`; the
  4 legacy render controllers are **deleted** (Theme-C-R B5, gated on a
  GREEN CR-RGATE); RenderBackend (`ImeViewBackend` + the new
  EditBar/Emoji/OverlayChars/Qwertz/StepRow/ContentArea/PromptVisibility/
  OverlayReset owners) is the **sole render driver**
  (`doubleWriteCount==0`); the staged build-but-dormant→`RenderGate`-armed
  →delete pattern (CR1–CR3 attach, CR4 atomic per-axis flip, CR-DEL
  delete) is the safe-cutover mechanic. RR-2 visibility-double-write risk
  guarded by `VisibilityWriteAuditLogger`.
- **Reasoning:** Triangle-FSM's render side was nominally on RenderBackend
  but the legacy controllers were still live (third recurrence of the
  INT-1 parallel-dormant anti-pattern, at the render layer). This entry
  records RenderBackend as the sole render driver post-cutover.
- **Reference:** `research/render-path-cutover.md`;
  `reports/B5-theme-cr-render-cutover.md`; Spec 2 §9.x/§13.x.

### New-ADR recommendation: **NO new ADR** (fold into ADR-0001 Decision-History)

The render-path cutover + the 3× recurring INT-1 anti-pattern resolution
do **not** warrant their own new ADR. Rationale (per lifecycle-adr.md
"When to create an ADR"):

- **Not a new architectural pattern.** "Parallel-dormant-layer is
  forbidden / cutover-must-be-completed-not-deferred" is not a *new*
  decision — it is the **direct consequence** of ADR-0001's existing
  single-dispatch + single-owner-per-axis decision and D7's
  long-term-quality principle. ADR-0001's `## Supersede Triggers` already
  frames superseding as "a different state-mutation paradigm"; completing
  the cutover *fulfils* ADR-0001, it does not introduce a new paradigm.
- **Not a reversal.** The Epic implements the parent ADRs' intended
  end-state (INT-1 option (a)); nothing in ADR-0001/0003/0004/0005 is
  contradicted or superseded — so no `Supersedes ADR-NNNN` ADR.
- **The "completed-not-deferred" lesson is a process/quality observation**,
  best captured as the *Reasoning* in the ADR-0001 DH entry (D7 framing:
  "a permanent parallel-dormant layer is two implementations forever") +
  the Phase-5 closure narrative + the existing `forbidden-patterns.md`
  doc-set. A standalone cross-cutting ADR for "don't ship dormant layers"
  would restate D7 (a user-level engineering principle) and ADR-0001's
  single-dispatch — net SSoT-redundancy, not a new accepted decision.
- **Recommendation:** the cross-cutting "no parallel-dormant layer; the
  INT-1 anti-pattern recurred 3× and was each time resolved not
  re-deferred" narrative goes into the **ADR-0001 Decision-History entry's
  Reasoning** (it is the state-pattern's consequence) + Phase-5 closure.
  ADR-0003 and ADR-0005 get their own DH entries for the
  service-notification and render-driver halves respectively. Three DH
  appends, zero new ADRs.

> [!IMPORTANT]
> Phase 4.6 does **not** write these. lifecycle-adr.md: Accepted ADRs are
> append-only; DH entries are written in the Phase-5 closure / a dedicated
> ADR-DH worker with the `knowledge-adr-format` skill loaded. This section
> is the **flag + trigger/before/after/reasoning sketch** the orchestrator
> hands to that worker.

## Knowledge-skill gap-check (flag only — no auto-gen)

- **No project-language knowledge skill exists** (no `knowledge-kotlin`,
  no `knowledge-android`). The Epic's reusable patterns —
  thin-Subsystem-adapter (`*Adapter` delegating to a process-global
  singleton), the staged build-but-dormant→`RenderGate`-armed→delete
  cutover mechanic, the IME-side-affordance pattern (`imeSideAffordance`
  firing legacy callback bodies for actions with no FSM representation),
  the `VisibilityWriteAuditLogger` double-write strict-mode guard — are
  **Android/Kotlin-specific** and have **no knowledge-skill home**. This
  is a **carried gap** (the parent plan already noted the absent
  knowledge-kotlin/knowledge-android). **Flag only, no auto-gen** per the
  task spec. The patterns are well-documented inline (substantive headers
  + `@see`) + in the specs, so the knowledge-skill absence is a
  nice-to-have, not a blocker.
- The state-pattern itself (single-dispatch, modules, cascade) is
  thoroughly covered by the `state-architecture/` doc-set + ADRs — no gap
  there.

## Inline-anchor inventory

The B1/B2/B5 IMPL agents wrote **substantive** module/class headers with
`@see` ADR/spec anchors and gotcha comments per the convention. Spot-check
across the 14 named seams + the modified IME/Service:

| File | Header | `@see` count (stale) | Gotcha (undated) | Plan-driven decision w/o anchor |
|------|--------|----------------------|------------------|----------------------------------|
| core/PipelineRunnerSubsystemAdapter.kt | substantive (OQ-1 disposition documented) | 4 (0) | 0 | 0 |
| core/PipelineNotificationCoordinator.kt | substantive ("Why a command coordinator") | 4 (0) | 0 | 0 |
| core/PipelineActionRouter.kt | substantive (SRP, ADR-0003 rationale) | 3 (0) | 1 (0 — FGS-survival note, has ADR-0003 ref) | 0 |
| core/ImePipelineConfigResolver.kt | substantive (R-1 problem statement) | 3 (0) | 0 | 0 |
| state/render/RenderGate.kt | substantive (RR-2 highest-risk) | 4 (0) | 0 | 0 |
| core/audit/VisibilityWriteAuditLogger.kt | substantive (RR-2 double-write) | 3 (0) | 0 | 0 |
| state/render/EditBarController.kt | substantive (CR4-IMPL-1, Spec 2 §13.2) | 5 (0) | 0 | 0 |
| state/render/EmojiController.kt | substantive (CR4-IMPL-1, §13.2) | 4 (0) | 0 | 0 |
| state/render/OverlayCharactersController.kt | substantive (§13.1 row 13) | 5 (0) | 0 | 0 |
| state/render/PipelineStepRowRenderer.kt | substantive | 4 (0) | 2 (0) | 0 |
| state/render/QwertzRecordingController.kt | substantive (G9 BLEIBT §9.4) | 4 (0) | 0 | 0 |
| core/EditNumbersAnimator.kt | substantive (G15 §9.2) | 2 (0) | 0 | 0 |
| database/DurationHealingScheduler.kt | substantive (non-blocking-shutdown rationale) | 2 (0) | 0 | 0 |
| preferences/LanguageResolver.kt | substantive (D-13 split rationale) | 3 (0) | 0 | 0 |
| core/DictateInputMethodService.java (recording-trigger) | substantive method-Javadoc on the C5 cutover seams (new-vs-legacy path, DictateOrchestrator-owns) | 13 ADR/spec refs in file (0 stale) | several (dated/ref'd) | 0 |
| core/DictatePipelineService.kt (onCreate Step 3/4) | substantive (Step 3/4 comments, @see ADR-0001/0003 + Spec 1) | (file-level @see present) | wiring comments dated by chunk | 0 |
| state/modules/{Recording,Audio,Pipeline,Language}Module.kt | substantive (legacy-parity line-cites, @see ADR + research) | present (0 stale) | inline legacy-parity notes | 0 |

**Anchor verdict:** the inline-anchor coverage of the **new** cutover
classes is already high-quality (substantive headers + resolving
`@see`). No `@see` path observed pointing at a non-existent plan/ADR.
**No new inline anchors are required for the new classes** — the IMPL
agents satisfied the convention. The 4.6c inline-worker scope is
therefore **light**: a verification pass that the `@see` targets still
resolve post-archive (the Epic plan path will change on archive →
`2026-05-15 - dictate-cutover-completion`), and any `@see` pointing at
the **deleted** controllers in *surviving* files should be confirmed
historical-anchor (intentional) vs stale.

> [!NOTE]
> **SSoT-risk (low, flagged):** the new class headers cite Spec §-sections
> heavily (correct — Spec is SoT, headers point not duplicate). One watch
> item: `RenderGate.kt` / `VisibilityWriteAuditLogger.kt` headers narrate
> the RR-2 staged-cutover rationale at paragraph length — this is the
> *mechanic's own* rationale (legitimately inline, not duplicated from a
> doc), but the 4.6c worker should confirm it does not restate
> `rendering.md` content verbatim once `rendering.md` is updated with the
> render-flip outcome. No active duplication today (rendering.md does not
> yet describe the cutover).

## Inline-worker groups

| Group slug | Files | Reason for grouping |
|------------|-------|---------------------|
| cutover-service-seam | PipelineRunnerSubsystemAdapter.kt, PipelineNotificationCoordinator.kt, PipelineActionRouter.kt, ImePipelineConfigResolver.kt, DictatePipelineService.kt | Same subsystem (core/ service composition + recording-drive seam) — verify @see ADR-0001/0003/Spec-1 resolve post-archive |
| cutover-ime-trigger | DictateInputMethodService.java | IME recording-trigger flip — verify the 13 ADR/spec @see refs + the new-vs-legacy-path Javadoc resolve; confirm any deleted-controller mention is intentional historical-anchor |
| render-cutover-owners | state/render/{EditBarController,EmojiController,OverlayCharactersController,PipelineStepRowRenderer,QwertzRecordingController,RenderGate,ContentAreaController,PromptVisibilityController,OverlayResetHandler,SpecialTouchHandlerInstaller,ImeViewBackend}.kt, core/EditNumbersAnimator.kt, core/audit/VisibilityWriteAuditLogger.kt | Same subsystem (state/render — Theme-C-R new owners); verify Spec 2 §9.x/§13.x @see + SSoT-watch on RenderGate/VisibilityWriteAuditLogger vs rendering.md |
| state-shape | state/Action.kt, state/DictateUiState.kt, state/modules/{Recording,Pipeline,Audio,Language}Module.kt, state/PipelineServiceStubSubsystems.kt | Theme-A state-shape (F-10/F-12/F-13/F-15) + stub-demotion; light — anchors present, verify ADR-0001 @see |
| language-retire | preferences/LanguageResolver.kt, preferences/{InputLanguagesLegacyMigration,InputLanguagesPlugin}.kt, preferences/versioned/VersionedPrefs.kt, DictateApplication.java, settings/PreferencesFragment.java | D-13 LanguageController removal call-site cluster (preferences subsystem — no arch doc; light) |

5 inline-worker groups. All are **verification-weighted** (anchors
already written), not generation-weighted — except any rendering.md /
state-and-actions.md staleness the file-doc workers handle separately.

## File-doc update list (state-architecture stale sections)

| Doc | Stale section | Why stale | Action |
|-----|---------------|-----------|--------|
| state-architecture/state-and-actions.md | §1.1 "Why this state model exists" — *"scattered across `RecordingStateController`, `RecordingUiController`, `KeyboardUiController`, `KeyboardStateManager`, and the IME-Service"* | 4 of those 5 controllers are now **deleted** (D-13/Theme-C-R). Present-tense "scattered across" reads as current architecture; it is now historical (pre-refactor). | needs-update — reword to past-tense "Pre-refactor, before this Epic's cutover, … were the scattered writers; they are now deleted, the RenderBackend + modules are the sole owners" |
| state-architecture/README.md | "Note on naming — DictateOrchestrator vs PipelineOrchestrator" — *"The two co-exist during the Block 2 → Block 3 migration window; B3 absorbs the legacy `PipelineOrchestrator` into the new architecture as a `PipelineRunnerSubsystem` adapter"* | Future/in-progress tense ("co-exist during … window", "B3 absorbs") — the absorption is **done**: `PipelineRunnerSubsystemAdapter` exists, PipelineOrchestrator is the §9.6 adaptee reachable only via JobExecutor. | needs-update — past-tense: "the legacy `PipelineOrchestrator` is now reachable **only** as the `PipelineRunnerSubsystem` adaptee behind `PipelineRunnerSubsystemAdapter`/`JobExecutor` (Spec 1 §9.6); the two-orchestrator coexistence is collapsed" |
| state-architecture/README.md | "High-level architecture in 60 seconds" diagram + "13 Modules" list | Diagram does not show the now-real `pipelineRunner`/`notificationCoordinator` subsystem adapters (was the dormant-seam). Module list is accurate (13 — unchanged). | needs-update (minor) — add the real `PipelineRunnerSubsystemAdapter`/`PipelineNotificationCoordinator`/`PipelineActionRouter` to the service box; module list stays |
| state-architecture/rendering.md | (no explicit stale string found, but) the render-driver narrative predates Theme-C-R | rendering.md describes the RenderBackend pattern but does not record that the **legacy controllers are deleted and RenderBackend is the SOLE driver** (the cutover outcome), nor the new EditBar/Emoji/OverlayChars/Qwertz/StepRow owners, RenderGate, VisibilityWriteAuditLogger | needs-update — add a "Render-path cutover (post-Epic)" subsection: legacy controllers deleted, sole RenderBackend driver, the staged RenderGate mechanic, the new behaviour-group owners; point to ADR-0004/0005 DH |
| state-architecture/state-and-actions.md | §"// ─── Phase 2 stub ───" (line ~113) | Verify this refers to the `InterruptionModule` Phase-2 stub (legit, ADR-0001 §Supersede), NOT the now-removed pipelineRunner/notificationCoordinator stubs | confirm-only (likely no-change — it's the Phase-2 module stub, unrelated) |
| state-architecture/modules.md | §7.1 "Module-Inventar (13 active + 1 Phase-2 stub)" | Axis count **unchanged** by the Epic (integration-check axis 6 confirms) — F-10/F-13 are field additions within existing axes, no new module | no-change (verify only) |
| docs/decisions/README.md (ADR index) | (none) | DH appends do not change the ADR index | no-change |
| docs/DATABASE-PATTERNS.md | (none) | No schema change (Room v4 unchanged) | no-change |
| CLAUDE.md (root) | (none material) | Generic project overview; cutover is below its abstraction level | no-change |

## Summary

- **Files touched:** 49 production source files (98 incl. tests)
- **Docs needing update:** 4 file-docs — `state-and-actions.md §1.1`,
  `README.md` (naming-note + 60-sec diagram), `rendering.md`
  (render-cutover subsection) + 3 ADR Decision-History appends
- **ADR Decision-History flags (no ADRs written this phase):** 3 —
  **ADR-0001** (coexistence collapsed; single-dispatch is now the
  production driver; state-shape F-10/F-12/F-13/F-15; carries the
  cross-cutting "no parallel-dormant layer / 3× anti-pattern resolved"
  narrative in its Reasoning), **ADR-0003** (real
  PipelineNotificationCoordinator + PipelineActionRouter +
  BT-SCO/audio-focus Preparing-lifecycle), **ADR-0005** (IME
  recording-trigger→dispatch flip + render-path cutover, RenderBackend
  sole driver, 4 controllers deleted, RenderGate staged mechanic)
- **New-ADR recommendation: NO** — fold the render-cutover +
  parallel-dormant-forbidden narrative into the **ADR-0001
  Decision-History Reasoning** (it is the consequence of ADR-0001's
  single-dispatch + D7, not a new accepted decision; not a reversal →
  no supersede). 3 DH appends, 0 new ADRs.
- **Specs to convert:** 0 — `render-path-cutover.md` stays a
  plan-co-located **derivative** spec (its own header: "adopts Spec 1/2,
  does not redesign"); converting it would create a second render-path
  SoT (SSoT violation). Decisions flow into rendering.md + ADR DH.
- **Gaps (Scenario A — doc-worthy area, no doc):** 1 — the `preferences/`
  subsystem (LanguageResolver, InputLanguages*, VersionedPrefs) has no
  architecture doc; low priority, pre-existing, not Epic-introduced
- **Knowledge-skill flags:** 1 carried — no `knowledge-kotlin`/
  `knowledge-android` for the reusable adapter/staged-cutover/
  imeSideAffordance/double-write-guard patterns (flag only, no auto-gen;
  patterns well-documented inline + in specs)
- **Inline-worker groups:** 5 (cutover-service-seam, cutover-ime-trigger,
  render-cutover-owners, state-shape, language-retire) — all
  **verification-weighted** (the new classes already carry substantive
  header+`@see`+gotcha anchors from the IMPL agents; main work is
  post-archive @see-path resolution + deleted-controller-mention
  intentional-vs-stale confirmation)
- **Files with anchor gaps:** 0 for the new cutover classes (convention
  satisfied at implementation time)
- **SSoT-risk:** low, 1 watch item — `RenderGate.kt` /
  `VisibilityWriteAuditLogger.kt` paragraph-length RR-2 rationale is the
  mechanic's own (legit inline), but the 4.6c render-cutover-owners worker
  must confirm it does not duplicate `rendering.md` once rendering.md is
  updated with the cutover outcome
- **Active-Notification gap:** none — `PipelineNotificationCoordinator`
  is the real production FGS notification surface (integration-check
  Central Verdict §1, e2e-test TC-A1 PASS); the one residual
  (C5-IMPL-2, NTH) is the *cosmetic in-keyboard* amplitude/timer
  animation side-channel, **not** a notification gap (the FGS
  notification is the authoritative recording-active surface and is fully
  driven). Documented as a known NTH cosmetic gap with a tracking owner,
  not a doc-blocking gap.
- **Process gap (flagged):** the state-file has **no `## Documentation
  Plan` block / `phase_4_6_activation` field**. The orchestrator should
  author that block from this report before dispatching 4.6c workers.
