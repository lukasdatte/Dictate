# Phase 4 Integration Check — dictate-cutover-completion (the INT-1 follow-up Epic)

**Agent-ID:** INTEGRATION
**Date:** 2026-05-17
**Scope:** Cross-block integration audit of all 6 Epic blocks (B1 Theme-A,
B2 Theme-B, B3 Theme-C, B5 Theme-C-R, B6 Theme-D — 19 chunks incl. gates
+ mid-triage). HEAD `27746d8`.
**Central question:** Is the original parent-plan INT-1 condition
(`docs/plans/2026-05-07 - …/reports/integration-check.md`) now FALSE?
**Method:** code-verification (grep + read of every cutover seam), not
state-file assumption. Independent clean test re-run.

---

## ⭐ CENTRAL VERDICT — INT-1 RESOLVED

**The original INT-1 condition is now FALSE. The Epic achieved its
purpose: a single coherent architecture, no parallel-dormant layer
remains in the cutover surface.**

INT-1 (parent plan) had four constituent facts. Each is now code-verified
false:

### 1. The two dormant stub seams are gone (real adapters wired) — VERIFIED

`PipelineServiceStubSubsystems.pipelineRunner` and `.notificationCoordinator`
are both `@Deprecated(WARNING)` test-only `val`s (mirroring the
`sessionRepo`/`audioFileFactory` deprecation discipline). The only remaining
`StubSubsystems.notificationCoordinator` token anywhere in `app/src/main/`
is a **prose comment** in `DictatePipelineService.kt:475` ("Replaces the
`PipelineServiceStubSubsystems.notificationCoordinator` no-op") — i.e. the
documentation trail of the replacement, not a wiring reference.
`DictatePipelineService.onCreate` Step 3/4 (lines 463–508) constructs and
binds the **real** adapters:

```
pipelineRunnerSubsystemAdapterImpl = PipelineRunnerSubsystemAdapter(
    context, configResolver = DelegatingPipelineConfigResolver(
        fallback = DefaultPipelineConfigResolver{filesDir},
        imeResolverProvider = { binder.delegatePipelineConfigResolver }))
pipelineActionRouterImpl   = PipelineActionRouter{ orchestrator.dispatch(it) }
notificationCoordinatorImpl = PipelineNotificationCoordinator(this, pipelineActionRouterImpl)
…
ModuleServices(… pipelineRunner = pipelineRunnerSubsystemAdapterImpl,
                  notificationCoordinator = notificationCoordinatorImpl …)
```

`PipelineRunnerSubsystemAdapter` is a thin `JobExecutor.start`-delegating
`PipelineRunnerSubsystem` (Spec 1 §9.6 OQ-1 — `submit`/`submitReprocess`/
`cancel`/`isRunning`). `PipelineNotificationCoordinator` is the real Spec 1
§7.4/§7.6/§11.1.2 FGS coordinator (single-source `NOTIF_ID = 0xD1C7A7E`,
`buildInitial()`, `show`/`dismiss`, channel-reuse, `PipelineActionRouter`
back-channel). **AC-1 satisfied.**

### 2. The new DictateOrchestrator now DRIVES production recording — VERIFIED

`DictateInputMethodService.startRecording` (`:3145-3150`) mints a
`preAllocatedId` UUID (F-10 sessionId source) and dispatches
`pipelineBinder.dispatch(RecordingAction.StartRecording(INPUT_CONNECTION,
audioFile, preAllocatedId))`; `stopRecording` (`:3163-3224`) dispatches the
payload-less `StopRecordingAndSend.INSTANCE` (FN-4 contract). The legacy
`JobExecutor.INSTANCE.start` recording-trigger call-sites (the parent
plan's :2236/:2897/:3053, FN-1-corrected to 3 sites) are **deleted**.
`USE_LEGACY_RECORDING_DRIVE` is **grep-zero** in `app/src/main/` — the
guarded fallback was removed in C7 per OQ-2/FN-3 (no lingering dead
switch). `sessionId = ""` sentinel is grep-zero (F-10 closed).

### 3. The render path is solely the RenderBackend (4 controllers deleted) — VERIFIED

`MainButtonsController.kt`, `RecordingUiController.kt`,
`KeyboardUiController.kt`, `KeyboardStateManager.kt` — **all four `.kt`
files deleted**. Grep across `app/src/main/` shows **zero live code
references** to any of the four (every remaining mention is a KDoc/`@see`
historical anchor, an XML comment in `ids.xml`/the layout, or a
cosmetically-stale test-fixture owner-label string — C10-C3-IMPL-1, NTH,
not a compile dependency). This was the INT-1 anti-pattern's **third
recurrence** (render layer); Theme C-R (B5) ported the ~16 controller
behaviour groups to the RenderBackend owners (ImeViewBackend,
SpecialTouchHandlerInstaller, ContentAreaController,
PromptVisibilityController, OverlayResetHandler, EditBar/Emoji/OverlayChars
controllers, QwertzRecordingController, PipelineStepRowRenderer) and only
then deleted the controllers, gated on a GREEN CR-RGATE. **AC-7 / AC-RR-6
/ AC-RR-7 satisfied.**

### 4. Legacy language + audioFile single-source-of-truth collapse — VERIFIED

`LanguageController` — grep-zero in `app/src/main/` (D-13, AC-5; replaced
by stateless `preferences/LanguageResolver.kt` + `LanguageModule`).
`private File audioFile` in the IME — grep-zero (D-14, AC-6; sourced from
`RecordingState` per Spec 1 §15.2). Room `@Database(version=)` stays v4
(E-7 invariant — code-only blast radius, no schema migration).

### Residual parallel-dormant hunt — NONE in the cutover surface

I specifically hunted for built-but-not-driven production code (the exact
INT-1 failure class):

- **`PipelineOrchestrator`** is constructed in `DictatePipelineService`
  and invoked **only** via `JobExecutor` (the runner body) which the C3
  `PipelineRunnerSubsystemAdapter` delegates into. It is the
  Spec 1 §9.6-mandated adaptee ("never deleted — implements the
  PipelineRunner interface"), **not** a second live state-router. OQ-1
  boundary KDoc present (C10-C3, commit 185f3f6). This is the *intended*
  surviving structure, not a dormant layer.
- The intra-block "dormant" wording in the B5 report is the **staged
  safety-net mechanic** (CR1–CR3 attach owners build-but-dormant behind a
  `RenderGate`, CR4 flips them live per-axis atomically, CR-DEL deletes
  legacy — all within one block, GATE-proven). This is the correct
  staged-cutover pattern, not a permanent dormant layer; post-CR4/CR-DEL
  the new owners are the sole `live=true` writers (`doubleWriteCount==0`,
  CR-RGATE GREEN).
- The single narrowly-scoped residual (C5-IMPL-2, **NTH postponed**) is
  the *cosmetic in-keyboard* amplitude/timer/border-glow animation
  side-channel: recording itself works end-to-end and the FGS
  notification is the authoritative recording-active surface — only the
  in-keyboard animation is undriven. This is a documented cosmetic gap
  with a tracking owner, **not** a second implementation of recording. It
  does not reconstitute INT-1.

**Conclusion:** INT-1 RESOLVED. The new `DictateOrchestrator` is the sole
state-router; `JobExecutor`/`PipelineOrchestrator` survive only behind the
`PipelineRunnerSubsystem` interface; the RenderBackend is the sole render
driver; legacy language/audioFile/controllers are deleted. The Epic
collapsed the two-orchestrator coexistence into a single coherent
architecture. The INT-1 anti-pattern recurred **three times** during the
Epic (C10-IMPL-2 render-cutover, CR4-IMPL-1 listener-bundle,
CR4-IMPL-3 RESEND-action) and was caught + spec-faithfully resolved each
time rather than re-deferred — the Epic's own process did not reproduce
the failure it existed to cure.

---

## Per-axis findings (INT-{N})

| ID | Severity | File / Location | Description | Status | Suggested routing |
|----|----------|-----------------|-------------|--------|-------------------|
| INT-1 | **Resolved (informational)** | cross-Epic | The parent-plan INT-1 (Critical, escalate-to-user) is now code-verified FALSE — see Central Verdict. No action. | closed | None — record the resolution in Phase 5 closure + the ADR-0001/0003 Decision-History append (Doc-Plan already lists this). |
| INT-2 | Nice-to-have | `app/src/main/java/.../history/HistoryDetailActivity.java:492` | A production `JobExecutor.INSTANCE.start` call-site exists **outside** the IME recording surface (the History-detail screen's "re-process a historical transcription" button, `JobRequest.TranscriptionPipeline` / `HISTORY_REPROCESS`). It is **pre-existing** (present unchanged at Epic baseline `65bb303`, zero Epic commits touch the file) and **outside the Epic's declared scope** (Epic §3/§4 scope = the IME recording trigger + the 3 IME `JobExecutor.start` sites). It is single-dispatch (a History Activity button, not a recording user-action) so it does **not** violate AC-10 (no user action both legacy-starts and orchestrator-dispatches the same pipeline). Not an INT-1 reopener — it is a separate, untouched, non-IME feature. | **out-of-scope-recorded** | NOT fixed this Epic by design (D3 out-of-scope-pre-existing carve-out, AC-10 holds). Recorded as a Phase-5 known follow-up: a future "collapse HistoryDetailActivity onto the orchestrator pipeline" item, if the team wants 100% single-driver. Non-blocking. |
| INT-3 | Nice-to-have | (was: no automated guard) → **`app/src/test/java/net/devemperor/dictate/core/CutoverArchitectureInvariantTest.kt`** | The AC-10 "PipelineOrchestrator/recording-drive single-architecture" invariant held **by inspection** but had **no automated architecture-test guard**. **FIXED in INTEGRATION-W1:** added `CutoverArchitectureInvariantTest` — a pure-JVM source-scanning test (K-1 handwritten / K-4 no Android Context, no Robolectric) that strips comments+strings then asserts: (a) exactly ONE functional `JobExecutor.INSTANCE.start(` in `DictateInputMethodService.java` and it is the documented RESUME carve-out (enclosing method `startResumeJob`); (b) ZERO `USE_LEGACY_RECORDING_DRIVE` in `app/src/main` functional code; (c) ZERO functional refs to the 4 deleted controllers (`MainButtonsController`/`RecordingUiController`/`KeyboardUiController`/`KeyboardStateManager`) — doc-anchors/XML-comments stripped & allowed; (d) `PipelineServiceStubSubsystems.pipelineRunner`/`.notificationCoordinator` NOT wired in `DictatePipelineService.onCreate`, and the real `PipelineRunnerSubsystemAdapter`/`PipelineNotificationCoordinator` ARE. Non-vacuity: each invariant paired with a `commentStripperIsSound*` self-test, and empirically RED-proven (a temporarily-injected 2nd `JobExecutor.INSTANCE.start(` made the test fail; reverted clean). 8 tests, deterministic+fast. | **fixed** | Resolved — the D4 regression-lock is now in place; the parallel-dormant/double-dispatch failure class cannot silently regress. |
| INT-4 | Nice-to-have | Issue-ID namespace across `reports/B*.md` | `F-N` issue IDs are block-local; the same `F-1`/`F-6` numbers recur across B1/B2/B3/B5/B6 with the cross-block carries disambiguated by suffix ("(from B3)", "(B4 carry-over)"). Traceable but a future reader grepping `F-6` across `reports/` hits unrelated issues. Same hygiene observation the parent INT-4 made; carried convention, no code impact. **FIXED in INTEGRATION-W1:** added a one-paragraph block-local-`F-N`-namespace note to the state-file's `## Repair-Sub-Phase Log` header (clarifies a bare `F-N` reads as `B{X}-VAL F-N`, disambiguated by the Wave-ID column). | **fixed** | Resolved (doc-only — state-file header note). |

**No Critical or Important integration findings.** All Critical/Important
issues raised during the Epic were FIXED/closed within their block's
chunks or block-validate waves (verified against every block report's
Issue Index + the state-file Repair-Sub-Phase Log: B1-VAL-W1, B2-VAL-W1
incl. the Critical BT-SCO-hang, B2-C6-W1 gate-repair + independent
RE-GATE, B2-C7-MID-W1 imported-file, B3-VAL-W1, B5-CR4-MID-W1,
B5-VAL-W1 incl. 2 Critical SPACE-double-commit + F-6-reopened, B6-VAL-W1).
No issues were forwarded out of any block.

---

## Standard Phase-4 audit axes

**1. Imports + types across block boundaries — PASS.**
Java IME ↔ Kotlin Service ↔ orchestrator ↔ state-modules ↔ render-backends
all type-consistent. The IME dispatches fully-qualified
`net.devemperor.dictate.state.Action.RecordingAction.StartRecording(...)`
/ `StopRecordingAndSend.INSTANCE`; the FN-4 contract change
(`StopRecordingAndSend` payload-less data object, sessionId via
`StartRecording` → `RecordingState`) is consistently honoured at the
producer (IME `:3147`/`:3223`) and consumer (RecordingModule reducer). The
clean `--rerun-tasks` compile + 1172-test run proves no boundary breakage.

**2. DI/composition root fully registered — PASS.**
`DictatePipelineService.onCreate` Step 4 (`:491-508`) binds every
`ModuleServices` interface to a real adapter. `pipelineRunner` →
`PipelineRunnerSubsystemAdapter`, `notificationCoordinator` →
`PipelineNotificationCoordinator`, `sessionRepo` →
`PipelineSessionRepoAdapter`, `audioFileFactory` → `CacheDirAudioFileFactory`,
`recordingHardware`/`bluetoothSco`/`audioFocus`/… → real `*Adapter`
classes. **No production-route stub survives.** `PipelineOrchestrator`
reachable only via the C3 adapter + `JobExecutor` (Spec 1 §9.6) — verified
by caller-graph grep.

**3. API contracts match — PASS.**
`RecordingAction.StartRecording(target, audioFile, sessionId)` producer/
consumer agree; `StopRecordingAndSend` payload-less (FN-4) consistently
applied; `require(sessionId.isNotBlank())` fail-fast (B1 F-7) matched by
the IME minting a real UUID (`preAllocatedId`); `PipelineActionRouter`
notification-button → `orchestrator.dispatch` back-channel wired;
`PipelineRunnerSubsystem.submit/submitReprocess/cancel` reproduce the
legacy `JobRequest` field-for-field via the shared
`captureFreshConfigSnapshot`/`ImePipelineConfigResolver` (R-1 fidelity,
B2-validated).

**4. Convention drift across blocks — PASS.**
The build-but-dormant→atomic-flip pattern is applied consistently across
Theme-B (USE_LEGACY_RECORDING_DRIVE guard → C7 deletion) and Theme-C-R
(RenderGate dormant → CR4 arm → CR-DEL delete). `resetForTest()` test-seam
discipline applied uniformly (JobExecutor/ActiveJobRegistry/
DurationHealingScheduler) — the R-7 flake was closed on all 3 axes across
B2/B3/B5. Per-class `TAG`, `Log.w/e`, `DictatePrefs`-sealed access, the
module-header/`@see`/gotcha anchor convention all consistent (the
per-block AUDIT-CONVENTION agents already enforced local style; no
cross-block systemic drift found).

**5. Plan-vs-impl drift aggregate — PASS (drift is documented + the Epic doc is now coherent).**
This is the inverse of the parent INT-1 finding. The Epic was itself
amended mid-flight in a **disciplined, documented** way: AC-4/§4-A1
amended in B1-VAL-W1 (Option-b: no `isStarting` field — Spec 1 §3
canonical, research-backed); FN-1 (3 not 2 JobExecutor.start sites);
FN-4 (StopRecordingAndSend contract); the CR-EXTRACT chunk inserted into
chunks.json via mid-triage; Theme C-R authored as a new block when
C10-IMPL-2 proved the render-cutover was never done. Every deviation is
recorded in the state-file Iteration-Log / Repair-Sub-Phase Log /
Mid-Chunk-Triage Log with rationale. There is **no silent drift** and no
deferred-forward scope into non-existent blocks (the exact thing parent
INT-1 flagged). The Epic doc, state-file, and shipped code are coherent.

**6. Capability mismatch — PASS (per-pair).**
Theme-A state-shape (F-10 sessionId on `RecordingState`, F-13 `Running`
counters, F-15 language-aware `dictateButtonText`) is consumed by Theme-B
(IME StartRecording threading), Theme-C-R (RenderBackend reads
`state.language.effective`), and Theme-D tests with no
signature-produces-N / consumes-N+M mismatch. The B1→B2→B3→B5→B6
dependency chain's hand-offs (sessionId continuity, payload-less stop,
notification status mapping, RenderBackend sole-writer) all verified by
the GREEN CR-RGATE + C6/D2 gates + the 1172-test suite.

**7. Postponed-issue aggregate (D15) — UNDER THRESHOLD (no escalation).**

| Severity | Count | Items |
|----------|-------|-------|
| Critical | **0** | — (parent INT-1's 0-Critical-code held; the Epic's Critical issues all FIXED in-block) |
| Important | **0 open** | All Important issues FIXED/closed in-block (C5-IMPL-1 via C6-W1, C6-IMPL-1 via C6-W1, C7-IMPL-1 via MID-W1, F-6 reopened+closed B5-VAL-W1, all B*-VAL Important findings) |
| Nice-to-have | **4** | C5-IMPL-2 (cosmetic amplitude/timer side-channel, postponed), C10-C3-IMPL-1 (stale test-fixture label strings, not a defect), INT-2 (pre-existing out-of-scope HistoryDetailActivity), INT-3 (optional AC-10 architecture-test guard); INT-4 = doc-hygiene |

**Total open postponed (all severities): ~4–5, all Nice-to-have.**
Known-carried verified: C5-IMPL-2 (NTH — confirmed still NTH, did **not**
silently grow to Important; it narrowed when Theme-C-R absorbed its
render-path half), C10-C3-IMPL-1 (NTH), C4-IMPL-2 (resolved/verified, not
carried), C5-IMPL-3 (resolved by C7 RESUME carve-out — the documented
single legacy `JobExecutor.start` survivor in the IME, not a postponed
gap).

**D15 threshold check:** ≥1 Critical → NO (0). ≥5 Important → NO (0).
≥10 total → NO (~4–5). **VERDICT: UNDER THRESHOLD — NO ESCALATION.**
This is the decisive contrast with the parent plan's INT-1, which
escalated on 7 open Important. This Epic closed all of them and added no
new Important/Critical postponed.

**8. Regression invariant (AC-9) — PASS (independently verified).**
Clean `./gradlew testDebugUnitTest --rerun-tasks`: **1172 tests, 0
failures, 0 errors, 0 skipped** (+226 net vs the 946 parent baseline — no
behaviour-coverage deletion). `assembleDebug` green (proven by the
compile of the test build). Reproduces the B6 FINAL-LOCK figure exactly.
No R-7 flake (all 3 axes closed across B2/B3/B5).

---

## Postponed-aggregate count + threshold verdict

**Open Critical: 0. Open Important: 0. Total open postponed (all NTH):
~4–5.** D15: no Critical, no ≥5 Important, no ≥10 total →
**NO ESCALATION.** No finding requires escalate-to-user. The parent
INT-1 escalation is **resolved**, not re-raised.

---

## Repair Wave INTEGRATION-W1 (INTEGRATION-REPAIR-1 → -VERIFY)

**Date:** 2026-05-17
**Scope:** Phase-4 integration repair — INT-3 + INT-4 only (INT-2 left
out-of-scope-recorded per D3; explicitly NOT touched).
**Findings addressed:** 2 (🟢2 / 🟡0 / ❌0 — 0 Crit / 0 Imp / 2 NTH)

| Finding ID | Severity | File | Status | Fix description |
|------------|----------|------|--------|-----------------|
| INT-3 | NTH | `app/src/test/java/net/devemperor/dictate/core/CutoverArchitectureInvariantTest.kt` (new) | fixed | Pure-JVM source-scan AC-10 regression-lock (8 tests = 4 invariant + 4 stripper-soundness self-tests). Strips comments+strings, then asserts (a) exactly one functional `JobExecutor.INSTANCE.start(` in the IME inside `startResumeJob` (the RESUME carve-out); (b) zero `USE_LEGACY_RECORDING_DRIVE` in `app/src/main` functional code; (c) zero functional refs to the 4 deleted controllers (doc-anchors stripped/allowed); (d) stub `pipelineRunner`/`notificationCoordinator` not wired in `DictatePipelineService.onCreate` + real adapter/coordinator are. |
| INT-4 | NTH | `dictate-cutover-completion.state.md` (`## Repair-Sub-Phase Log` header) | fixed | One-paragraph note: `F-N` IDs are block-local; read a bare `F-N` as `B{X}-VAL F-N`, disambiguated by the Wave-ID column; cross-block carries spelled out inline. |

**Cross-fix conflicts:** none.
**Files modified:** `CutoverArchitectureInvariantTest.kt` (new, 1 file),
`dictate-cutover-completion.state.md` (INT-4 note + log row + Phase-4
YAML), `reports/integration-check.md` (this report).
**Files in findings-scope:** the new test file + the state-file (the
two finding targets).
**Files outside findings-scope (drift):** none. The diff is **DISJOINT
from `app/src/main`** — test + docs only, zero production-source change
(verified by `git status app/src/main` = clean).

**Non-vacuity argument (would it catch a reintroduced
parallel-dormant/double-dispatch?):** Yes — proven two ways.
(1) *Self-tests*: each of the four invariant assertions has a paired
`commentStripperIsSound*` test that feeds the comment-stripper a
synthetic snippet containing the banned construct once in code and
once in a comment/string, asserting code survives and doc is dropped —
so the comment-strip cannot mask a real regression.
(2) *Empirical mutation*: a second `JobExecutor.INSTANCE.start(`
recording-trigger was temporarily injected into
`DictateInputMethodService.java` as real code; the
`exactlyOneJobExecutorStartInIme_andItIsTheResumeCarveOut` test went
**RED** (counted 2, expected 1) — the exact double-dispatch failure
class the parent-plan INT-1 described. Mutation reverted clean
(`git status app/src/main` empty). The same RED-on-regression holds
for invariant (b) (re-added dead switch), (c) (re-wired deleted
controller), and (d) (reverted-to-stub `onCreate` wiring).

**Self-check (validate-fixes.resume.md):** `./gradlew assembleDebug`
green; `./gradlew test --rerun-tasks` (testDebugUnitTest +
testReleaseUnitTest) green at 1180/0/0 both variants (1172 baseline +
8 new INT-3 tests); CR-RGATE `RenderPathCutoverGateTest` 5/5 +
`DictateCutoverE2ETest` 10/10 stay green. Completeness: both in-scope
findings addressed, none silently skipped; INT-2 deliberately left
`out-of-scope-recorded` per the carve-out. No new issues forwarded.
**Repair-sub-phase: ✓ converged.**

---

## Disposition

5 findings documented (INT-1 = the resolved central verdict /
informational; 4 Nice-to-have). The original INT-1 — the reason this
Epic exists — is **code-verified RESOLVED**: the new architecture is
live in production, the legacy paths it rendered dormant are deleted,
no parallel-dormant layer remains in the cutover surface.
**INTEGRATION-W1 then closed the two actionable NTH follow-ups:**
INT-3 is now guarded by `CutoverArchitectureInvariantTest` (the D4
regression-lock, non-vacuity self-tested + empirically RED-proven), and
INT-4's per-block `F-N` namespace is documented in the state-file
Repair-Sub-Phase-Log header. **INT-2 remains out-of-scope-recorded by
design** (D3 pre-existing non-IME carve-out, AC-10 holds) — a
documented Phase-5 follow-up, non-blocking. No issue blocks archival.
Phase 4.5 / Phase 5 may proceed (this is the substantive answer the
parent INT-1 escalation demanded: the Epic did what it set out to do,
and the single-architecture invariant is now regression-locked).
