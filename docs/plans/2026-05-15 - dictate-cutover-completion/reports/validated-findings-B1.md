# Validated Findings — Block 1 (Theme A — State-Shape)

**Agent-ID:** B1-VAL-SANITY
**Date:** 2026-05-15
**Source audits:**
- `./reports/audit-plan-and-api-B1.md` — 0 Crit / 2 Imp / 1 NTH
- `./reports/audit-convention-B1.md` — 0 Crit / 0 Imp / 2 NTH
- `./reports/audit-logic-B1.md` — 0 Crit / 2 Imp / 2 NTH
- `./reports/audit-test-B1.md` — 0 findings (964/964 green, K-1/K-4 ok, ~100% new-branch coverage, doc-trail complete)

Raw total: 0 Critical / 4 Important / 5 Nice-to-have (9 raw findings, 2 known overlap pairs).

## Summary

- 🟢 valid + auto-fixable: **5** (Critical: 0, Important: 1, Nice: 4)
- 🟡 valid + research-needed: **1** (Critical: 0, Important: 1, Nice: 0)
- ❌ eliminated: **1** (1 NTH — intentional-deferred-with-rationale)

After dedup: 7 unique findings (9 raw − 2 merged).

## Cross-cut patterns

- **Documentation-drift cluster (4 of 7 findings are doc/comment-only):**
  F-2 (`DictateUiState.kt` `totalSteps` KDoc), F-3
  (`DictatePipelineService.kt:730-732` stale `formatPipelineLabel`
  comment), plus the doc-leg of F-1 (`DictateUiState.kt` `isStarting`
  KDoc + `LayoutCatalog.kt:390-393` stale comment). Systemic theme:
  C1-A1/C2-A2 shipped behaviour but left **paired KDoc/comments
  describing the pre-implementation contract** at multiple sites. The
  same fact ("F-13 fields are now live", "StepStarted does not refresh
  totalSteps", "isStarting is not the live guard") is documented
  correctly in one file and contradictorily in a twin. Domain-bundle
  candidate: a single doc-coherence repair pass touching
  `DictateUiState.kt` + `DictatePipelineService.kt` + `LayoutCatalog.kt`.
- **`isStarting` mechanism appears in 3 findings' file-sets**
  (`PipelineModule.kt` guard branch, `DictateUiState.kt` field+KDoc,
  `LayoutCatalog.kt` comment+enabledResolver). Independently found by
  AUDIT-PLAN-AND-API **and** AUDIT-LOGIC → merged into F-1. This is the
  block's load-bearing decision and the only 🟡.
- **F-10-sentinel forward-risk (F-7):** a B1-correct guard-rail gap that
  becomes a real regression vector when B3 flips the recording trigger.
  Must be carried as a B3 forwarding concern regardless of how it is
  repaired in B1.
- **No Critical, no test/coverage findings.** AUDIT-TEST is fully clean
  (forced-rerun 964/964, doc-trail intact, no hidden regressions in the
  12 C2-A2 sibling-test edits). The 3 delegated `plan-deviation-resolved`
  issues (IMPL-PLAN-FIX-1 C1-A1, IMPL-PLAN-FIX-1 C2-A2, IMPL-PLAN-FIX-2
  C2-A2) were independently cross-checked by both PLAN-AND-API and LOGIC:
  the **sessionId-payload-widening** (C2-A2 Dev-1) and
  **`StopRecordingAndSend` → `data object`** (C2-A2 Dev-2) deviations are
  **confirmed-justified, no residual finding** — they are recorded here
  only as resolved cross-checks (see "Delegated-deviation cross-check"),
  not as open findings. The **SendStaging `→ Preparing`** deviation
  (C1-A1 Dev-2) is confirmed-justified *for the FSM edge* but spawns the
  residual F-1 inert-mechanism finding.

## Findings

### F-1 (was AUDIT-PLAN-AND-API-B1-1 + AUDIT-LOGIC-B1-1, merged)

- **Classification:** 🟡 valid + research-needed
- **Severity:** Important
- **Files:**
  - `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt:289-320` (`SendStaging` arm, `else if (state.isStarting) null` guard branch)
  - `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt:238-251` (`ReprocessStaging.isStarting` field + KDoc)
  - `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:390-393` (stale comment + `enabledResolver` that does not read `isStarting`)
- **Description:** F-12's `ReprocessStaging.isStarting` field, the
  `else if (state.isStarting) null` guard branch, and the
  `LayoutCatalog` Send-button comment collectively describe a
  double-click-guard mechanism that is **inert in production**.
  Verified by grep (`grep -rn "isStarting = true\|copy(isStarting" app/src/main`
  → **zero hits**; the only `isStarting = true` is the test fixture
  `PipelineModuleTest.kt:196`). The first `SendStaging` tap transitions
  `ReprocessStaging → Preparing` (C1-A1 Dev-2, justified — a literal
  `copy(isStarting=true)` would strand the reprocess job because
  `StartPipeline` only fires from `Preparing`); the *actual*
  double-submit protection is the FSM leaving `ReprocessStaging` (second
  tap falls to `else -> null`). So the field, the guard branch, and the
  catalog comment are a field/branch/comment trio that protect nothing.
  The `ReprocessStaging.isStarting` KDoc ("Set to `true` by the first
  `SendStaging` action") is **factually wrong** — the first `SendStaging`
  transitions to `Preparing`, it never writes `isStarting`. The
  `LayoutCatalog.kt:390-393` comment claims the field is "not yet on
  `ReprocessStaging`" — false, C1-A1 added it — and the
  `enabledResolver` does not read it, so the spec-intended "disable Send
  while starting" UX (legacy `core/PipelineUiState.kt:52`) is unwired.
- **Why it matters / why research-needed:** This is a genuine design
  tension between **Epic-intent** and the **runner handshake**, not a
  mechanical fix. Epic §2 AC-4 (`dictate-cutover-completion.md:193`)
  explicitly names the guard `!isStarting`; Epic §4 Block A1
  (`:314-315`) prescribes `if (state.isStarting) null else
  copy(isStarting=true)`. The implementation correctly avoided the
  literal pseudo-code (it breaks `StartPipeline`-from-`Preparing`) but
  the chosen resolution leaves a shipped field that does nothing — a
  future-reader trap and a serviceability hazard (a maintainer may
  "fix" the real protection, the `→ Preparing` edge, believing
  `isStarting` is the live guard). D4 (long-term highest quality): a
  do-nothing state field on a seam that **B2 (FGS notification) and B3
  (recording-drive cutover) will build on** must have unambiguous guard
  semantics *before B2*. IMPL-PLAN-FIX-1 (C1-A1) **explicitly asked
  Block-Validate to confirm this call** (block-report Issue Index +
  C1-A1 Step-2 issue). D5 ("when in doubt research more") + the
  Epic-intent-vs-handshake reconciliation → strong 🟡.
- **Research topic:** `sendstaging-isstarting-guard-semantics`
- **What research must decide (no "it depends"):** Choose and fully
  specify **one** of:
  - **(a) Wire `isStarting` so it is the real guard** — make
    `SendStaging` first-tap set `isStarting=true` *and* still drive the
    runner handshake (e.g. emit `SubmitReprocess` + transition to
    `Preparing` while the optimistic-UI/resolver or the `Preparing`
    successor carries a starting flag; or keep `ReprocessStaging` with
    `isStarting=true` *and* additionally emit the effect that gets the
    job to `Preparing`). Must re-verify the `StartPipeline`-from-
    `Preparing` contract + the existing `SendStaging transitions
    ReprocessStaging to Preparing` test (`PipelineModuleTest.kt:171`)
    are not broken. Update `LayoutCatalog.kt:393` `enabledResolver` to
    `... is ReprocessStaging && !it.isStarting` (delivers the spec
    disabled-button UX). This honours the Epic F-12 intent.
  - **(b) Remove the inert field+guard+comment** — drop
    `ReprocessStaging.isStarting`, remove the `else if (state.isStarting)
    null` branch, rewrite the `LayoutCatalog.kt:390-393` comment to
    state the FSM `ReprocessStaging → Preparing` edge **is** the
    canonical double-submit guard, and **adjust the Epic AC-4
    expectation** (`dictate-cutover-completion.md:193` `(!isStarting)`)
    + the F-12 acceptance test so the canonical guard is the FSM edge,
    not the field. Lower code-risk; loses the spec disabled-button UX
    (must be explicitly deferred with a tracked note to a later
    Theme-C/D UI block).
  Research must also state the B2/B3 forward-impact of the chosen
  option (B2 notification + B3 recording-drive build on this seam).
- **Domain bundle candidate:** the `isStarting` mechanism
  (`PipelineModule.kt` + `DictateUiState.kt` + `LayoutCatalog.kt`) — the
  three sites must be repaired coherently in one wave whichever option
  research picks.

### F-2 (was AUDIT-PLAN-AND-API-B1-2 + AUDIT-LOGIC-B1-2, merged)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt:208-212` (`@property totalSteps` KDoc on `Running`)
- **Description:** The `totalSteps` KDoc says it is "set from
  `StartPipeline.totalSteps` on `Preparing → Running` **and refreshed by
  `StepStarted`** (defensive — keeps the label sane if the runner
  re-reports a different total mid-run)". The `StepStarted` reducer arm
  (`PipelineModule.kt:174-192`) does **not** refresh `totalSteps` — it
  only restamps `elapsedMs` via `state.copy(elapsedMs = elapsedSince(...))`
  (verified). `Action.PipelineAction.StepStarted(sessionId, stepName)`
  carries no `totalSteps` payload, so the arm is structurally incapable
  of refreshing it; C1-A1 Dev-1 documents this. The KDoc directly
  contradicts the shipped reducer and the documented deviation.
- **Why it matters:** Doc-vs-code contradiction on a state-shape
  contract that B2 (notification) and B4 (record-button label) consume.
  Pure documentation fix, no behaviour change — auto-fixable.
- **Suggested fix:** Edit `DictateUiState.kt:208-212`: remove "and
  refreshed by `StepStarted` (defensive — keeps the label sane if the
  runner re-reports a different total mid-run)"; replace with: "set once
  from `StartPipeline.totalSteps` on `Preparing → Running`; never
  re-stamped — `StepStarted` carries no total in its payload (see
  PipelineModule Dev-1). `0` means 'unknown' and the label formatter
  renders it as such."
- **Domain bundle candidate:** documentation-drift cluster (with the
  doc-leg of F-1 + F-3) — same file as F-1's KDoc fix.

### F-3 (was AUDIT-CONVENTION-B1-1)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:730-732` (`formatPipelineLabel` lambda comment)
- **Description:** The `formatPipelineLabel` lambda still carries "Live
  values come from `PipelineUiState.Running`. The resolver currently
  passes 0s pending pipeline-state extension (Spec 2 C12/C14
  follow-up)." This is now false: C1-A1 (F-13) made
  `resolveRecordButtonTextPipeline` pass the real
  `completedSteps/totalSteps/elapsedMs`. C1-A1 correctly updated the
  twin KDoc in `TextResolvers.kt` but missed this consumer comment.
  Verified at `:730-732` against live code (the lambda receives real
  `completedSteps, totalSteps, autoEnterActive, elapsedMs` params).
- **Why it matters:** A comment that actively misdescribes shipped
  behaviour (says the label is a `0`-placeholder when it is live) is
  worse than no comment; same-fact contradiction across
  `TextResolvers.kt` vs here. In-block scope (C2-A2 modified this file).
- **Suggested fix:** Replace `:730-732` with a statement matching the
  `TextResolvers.kt` F-13 KDoc, e.g. "Live
  `completedSteps/totalSteps/elapsedMs` come from `PipelineUiState.Running`
  via `resolveRecordButtonTextPipeline` (F-13, Epic §4 Block A1). This
  lambda only formats them as `N/M ↵ M:SS`."
- **Domain bundle candidate:** documentation-drift cluster (with F-2 +
  doc-leg of F-1).

### F-4 (was AUDIT-CONVENTION-B1-2)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt:60`
- **Description:** `newSessionId()` uses a fully-qualified inline
  `java.util.UUID.randomUUID().toString()` rather than `import
  java.util.UUID` + short name. Every other UUID-mint site imports the
  type (`PipelineOrchestrator.kt:22` import → `:709/:847` short name);
  `ActionResolvers.kt` imports every other type it uses — the inline FQN
  is the lone exception, and the helper's own KDoc says it mirrors the
  `PipelineOrchestrator` call-shape (verified at `:55-60`).
- **Why it matters:** Cross-site import/naming inconsistency — exactly
  the convention drift this audit guards; trivial mechanical fix.
- **Suggested fix:** Add `import java.util.UUID` (alphabetical in the
  import block) and change `:60` to `private fun newSessionId(): String
  = UUID.randomUUID().toString()`.
- **Domain bundle candidate:** none (standalone, `ActionResolvers.kt`).

### F-5 (was AUDIT-PLAN-AND-API-B1-3)

- **Classification:** ❌ false-positive (intentional-deferred-with-rationale)
- **Severity:** Nice-to-have (eliminated)
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:713-719` (`dictateButtonText` lambda)
- **Rationale for elimination:** Not a defect. The F-15 testable
  contract (Epic §2 AC-4 / §4-A2: label "differs across two
  `LanguageState.effective` values") is **met** — `"Record (en)"` ≠
  `"Record"`/`"Record (de)"`, asserted by `F-15 resolveRecordButtonText
  label differs across two effective languages`. The raw-language-code
  vs localised-display-name observation is a **deliberate Phase-1
  baseline self-documented by the IMPL agent** (C2-A2 block-report
  "Overlooked / Known Gaps": *"the exact production label string is a
  UI-polish concern a later Theme-C/D block can refine"*) and the
  AUDIT-PLAN-AND-API agent itself states "No action required for B1".
  Per the D3 carve-out, genuine intentional-deferred-with-rationale
  (tracked by the IMPL known-gap note + owned by a later Theme-C/D
  block) becomes ❌/postponed rather than an in-block repair. The
  code-suffix form also matches the Epic §4-A2 example and the
  `TextResolvers.kt` KDoc (`"Dictate (en)"`), so there is no
  plan-treue/API-contract violation to repair. **Forwarded as a
  documented known-gap (already in the C2-A2 block-report), not an open
  finding.**

### F-6 (was AUDIT-LOGIC-B1-3)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt:194-207` (`StepCompleted` arm)
- **Description:** `StepCompleted` does `completedSteps =
  state.completedSteps + 1` with no upper bound vs `totalSteps`. If the
  runner mis-reports / double-emits / `totalSteps == 0`, the live label
  can render `"4/3"` or `"1/0"`. The sibling `elapsedMs` already has a
  defensive floor (`elapsedSince().coerceAtLeast(0L)`); `completedSteps`
  has no symmetric guard. Low impact (cosmetic label + FGS
  notification); runner is the step-count authority.
- **Why it matters:** Small consistency gap in the F-13 counter family.
  Per D3 (fix every polish point, no Final-PR deferral) this is
  classified for in-block repair, but the auditor's recommended
  resolution is the minimal, correct one: **document the assumption**
  rather than add reducer logic (keeps the pure reducer minimal; the
  runner-authoritative contract is the design intent).
- **Suggested fix:** Add a one-line comment to the `StepCompleted` arm
  (`PipelineModule.kt:194-197`) stating "the runner is authoritative on
  step count; `completedSteps` is not clamped here — if the runner
  mis-reports, the label may briefly show an overrun (`N/M` with
  `N>M`). The display formatter, not the reducer, owns any cosmetic
  clamp." Do **not** add a reducer clamp (over-engineering per the
  auditor). Optional follow-on (out of B1, note only): a
  `completedSteps.coerceAtMost(totalSteps)` clamp could live in
  `formatPipelineLabel` in a later display-polish block — record as a
  forward-note, do not implement in B1.
- **Domain bundle candidate:** none (standalone reducer-comment).

### F-7 (was AUDIT-LOGIC-B1-4)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **Files:**
  - `app/src/main/java/net/devemperor/dictate/state/Action.kt:117-121` (`StartRecording.sessionId: String`)
  - `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt:184-199` (`Idle + StartRecording` arm)
- **Description:** `StartRecording.sessionId` is an unconstrained
  `String`. B1 callers are correct (`newSessionId()` →
  `UUID.randomUUID()`, grep confirms zero `sessionId = ""`), but the
  reducer carries `action.sessionId` into `Preparing` verbatim with no
  `require(...isNotBlank())`. When **B3** flips the recording trigger to
  route the IME's `preAllocatedId` in, a regression there (blank id)
  would silently re-introduce the exact F-10 empty-string sentinel this
  block removes, propagating through the whole FSM into
  `EmitPipelineTrigger` with no fail-fast.
- **Why it matters:** Latent F-10-invariant guard-rail gap. Not a
  current bug, but a real **B3 forward-risk**: F-10's "FSM is the single
  source, never empty" currently relies on every future caller's
  discipline rather than an enforcement point. Cheap fail-fast.
- **Suggested fix:** Add `require(action.sessionId.isNotBlank()) {
  "F-10: StartRecording.sessionId must be non-blank" }` at the top of
  the `Idle + StartRecording` arm in `RecordingModule.kt` (fail-fast,
  matches the FSM-is-single-source invariant). Add a regression test
  (`F-10 StartRecording with a blank sessionId throws` — pending/red on
  unfixed code, green after) per the test-first regression-test
  convention. **Also carry as a B3 forwarding concern** (alongside
  IMPL-PLAN-FIX-2's existing B3 contract note): B3's recording-trigger
  cutover must supply a non-blank `preAllocatedId` and is the contract
  owner for the enforcement point.
- **Domain bundle candidate:** none (standalone) — but the B3
  forwarding note must be added to the block-report's cross-block-API
  forward section (next to IMPL-PLAN-FIX-2).

## Delegated-deviation cross-check (resolved, no open finding)

These were independently re-verified by both AUDIT-PLAN-AND-API and
AUDIT-LOGIC and are **confirmed-justified — recorded for trace
completeness, not open findings:**

| Delegated issue | Verdict | Note |
|---|---|---|
| IMPL-PLAN-FIX-1 (C1-A1) — SendStaging keeps `→ Preparing` edge | Confirmed-justified **for the FSM edge**; residual inert-mechanism → F-1 (🟡) | The deviation call (keep `→ Preparing`) is correct (literal pseudo-code strands the reprocess job). The residual issue is the inert `isStarting` field/branch/comment, escalated as F-1. |
| IMPL-PLAN-FIX-1 (C2-A2) — `sessionId` on `RecordingState.Preparing/Active/Paused` | Confirmed-justified, **no residual** | Spec 1 §3/§15.2 predate F-10; Epic §4-A2 explicitly authorises ("adding `sessionId` here is the clean source"). FSM graph payload-only widened; every transition propagates `sessionId` verbatim; verified line-by-line by both audits. Spec-faithful. |
| IMPL-PLAN-FIX-2 (C2-A2) — `StopRecordingAndSend` → `data object` | Confirmed-justified, **no residual** | A2 (authoritative seam owner) refines the B3 forward-sketch. All call-sites payload-less (grep-verified). Cross-block: B3 must dispatch `StartRecording(...,preAllocatedId)` + payload-less `StopRecordingAndSend` — already captured in FN-4 / IMPL-PLAN-FIX-2; orchestrator must forward to B3. |

## Eliminated findings

| Source ID | Source audit | Reason for elimination |
|-----------|--------------|------------------------|
| AUDIT-PLAN-AND-API-B1-3 | block-audit (plan-and-api topic) | Not a defect → F-5. F-15 testable contract met (`"Record (en)"` ≠ `"Record"`, asserted by test); the raw-code-vs-display-name observation is a deliberate, self-documented Phase-1 baseline owned by a later Theme-C/D block (IMPL "Overlooked / Known Gaps"), and the auditor itself states "No action required for B1". Genuine intentional-deferred-with-rationale per the D3 carve-out → forwarded as a tracked known-gap, not an in-block repair. |

## Routing recommendation

- **🟡 research-needed (1):** F-1 → research topic
  `sendstaging-isstarting-guard-semantics`, then repair via the
  research-agent resume-chain. **This must be resolved before B2**
  (B2 FGS-notification + B3 recording-drive build on this seam; the
  guard semantics must be unambiguous). Domain-bundle the 3 `isStarting`
  sites into one repair wave.
- **🟢 auto-fixable (5):** F-2, F-3, F-4, F-6, F-7 → consolidator
  resume-chain (`implement-fixes.md`). Recommended single repair wave;
  bundle F-2 + F-3 (+ the doc-leg of F-1 if option-(b) is later chosen)
  as the documentation-drift sub-bundle. F-7 additionally requires a
  regression test + a B3 forward-note in the block-report.
- **❌ eliminated (1):** F-5 — no repair; forwarded as a documented
  known-gap (already in C2-A2 block-report).
- **No Critical; no mid-chunk-triage needed** (F-1 is Important and does
  not block the *remaining* chunks of B1 — both B1 chunks are already
  implemented — but it **does** gate B2; flag for orchestrator to
  resolve before opening B2).

## Merge-map (raw audit-ID → validated finding)

| Raw audit ID | Severity (raw) | → Validated | Verdict |
|---|---|---|---|
| AUDIT-PLAN-AND-API-B1-1 | Important | **F-1** (merged) | 🟡 |
| AUDIT-LOGIC-B1-1 | Important | **F-1** (merged) | 🟡 |
| AUDIT-PLAN-AND-API-B1-2 | Important | **F-2** (merged) | 🟢 |
| AUDIT-LOGIC-B1-2 | Important | **F-2** (merged) | 🟢 |
| AUDIT-CONVENTION-B1-1 | Nice-to-have | **F-3** | 🟢 |
| AUDIT-CONVENTION-B1-2 | Nice-to-have | **F-4** | 🟢 |
| AUDIT-PLAN-AND-API-B1-3 | Nice-to-have | **F-5** | ❌ |
| AUDIT-LOGIC-B1-3 | Nice-to-have | **F-6** | 🟢 |
| AUDIT-LOGIC-B1-4 | Nice-to-have | **F-7** | 🟢 |
| AUDIT-TEST (all) | — | (none) | clean — no findings |
