# Phase 4.6 — Documentation Update Report

**Date:** 2026-05-17
**Plan:** dictate-cutover-completion (the INT-1 follow-up Epic)
**Final agent:** B0-DOCS-FINAL (combined 4.6c+d+e worker — single session)

## Executive summary

| Metric | Count |
|---|---|
| Plan-touched source files | 49 production (.kt/.java), 98 incl. tests |
| ADR Decision-History appends | 3 (ADR-0001, ADR-0003, ADR-0005) |
| Architecture file-docs updated | 3 (state-and-actions.md, README.md, rendering.md) |
| Specs converted to docs | 0 (by design — see Discovery; render-path-cutover.md stays a plan-co-located derivative spec) |
| Auto-fixed cross-doc links | 0 (all new links resolved on first write) |
| Doc-on-doc contradictions | 0 |
| Documentation gaps (Scenario A) | 1 (carried, pre-existing — `preferences/` subsystem) |
| Flagged non-auto-fixable items | 1 (stale present-tense inline headers in 4 production render files — doc-phase cannot edit source) |
| Knowledge-skill flags | 1 carried (no `knowledge-kotlin`/`knowledge-android`) |
| New ADRs written | 0 (correct — folds into ADR-0001/0003/0005 DH per Discovery + lifecycle-adr.md) |

## What was updated

### ADR Decision-History appends (3 — append-only, Accepted ADRs, no status change)

All three follow the knowledge-adr-format §"Decision History" **Trigger /
Before / After / Reasoning / Reference(s)** shape, are dated 2026-05-17,
prepended newest-first (the established convention in all three files —
`Initial proposal` is last), and add a bidirectional `## References`
Related-Plan link to the Epic (the Epic §8 already reciprocates — verified).

- **ADR-0001** (state modular-orchestrator / single-dispatch): *Two-orchestrator
  coexistence collapsed; single-dispatch is now the production recording
  driver.* Real `PipelineRunnerSubsystemAdapter` / `PipelineNotificationCoordinator`
  wired in `DictatePipelineService.onCreate` Step 3/4; stubs `@Deprecated`
  test-only; IME dispatches `RecordingAction.*`; F-10/F-12/F-13/F-15
  state-shape within existing axes (axis-count unchanged 13+1). The
  Reasoning paragraph carries the cross-cutting lesson: the INT-1
  parallel-dormant anti-pattern recurred **3×** (C10-IMPL-2 render-layer,
  CR4-IMPL-1 edit-bar/emoji, CR4-IMPL-3 RESEND-staging) and was each time
  resolved-not-deferred (D7, §6.2 staged safety-net, RR-3 per-class trace,
  `CutoverArchitectureInvariantTest` regression-lock) — this is the
  consequence of ADR-0001's single-dispatch + D7, which is why no new ADR
  is created.
- **ADR-0003** (service FGS pipeline): *Real notification coordinator +
  action-router; BT-SCO/audio-focus Preparing-lifecycle.* Real
  `PipelineNotificationCoordinator` (single-source `NOTIF_ID`) +
  `PipelineActionRouter` (FGS-targeted back-channel); the B2-VAL-W1 F-1
  already-connected-hang fix (prime-to-Waiting) + F-2 ReacquireAudioFocus,
  Preparing-state-confined in `AudioModule`.
- **ADR-0005** (Triangle-FSM): *IME recording-trigger flipped to dispatch;
  render-path cutover.* IME `JobExecutor.start`→`pipelineBinder.dispatch`
  (RESUME carve-out documented + regression-locked); 4 legacy controllers
  deleted, RenderBackend sole driver, the staged build-but-dormant →
  `RenderGate`-armed → atomic-flip → delete mechanic;
  `VisibilityWriteAuditLogger` RR-2 guard; `OnRecordLongPress` 2-mode.

### Architecture file-docs (3 — UDOC-conformant in-place updates, English, present-tense post-cutover)

| Doc file | What changed | Type |
|----------|--------------|------|
| `docs/architecture/state-architecture/state-and-actions.md` | §1.1: kept the pre-refactor scattered-writer narrative as historical motivation; added a `> [!NOTE]` post-cutover-reality block — 3 controllers + `LanguageController` deleted, IME no longer mutates state directly, RenderBackend sole render driver, modules sole state owners; explicitly notes `RecordingStateController` **survives** (verified against filesystem — it is in the §1.1 list but is NOT in the deleted set). Points at render-path-cutover.md §3 as the owner-map SoT (no duplication). | structure / new-note |
| `docs/architecture/state-architecture/README.md` | "Note on naming" rewritten future→past tense (coexistence collapsed; PipelineOrchestrator reachable only as the §9.6 adaptee). 60-sec diagram: added the real `PipelineRunnerSubsystemAdapter`/`PipelineNotificationCoordinator`/`PipelineActionRouter` to the service box (was the dormant seam) + noted stubs `@Deprecated`; render box updated (4 legacy controllers deleted, the new owners listed, "SOLE render driver"). Module list unchanged (13 — correct). | flow / structure |
| `docs/architecture/state-architecture/rendering.md` | New **§13 "Render-path cutover (post-Epic, 2026-05-17)"**: 4 controllers deleted, RenderBackend sole driver (`doubleWriteCount==0`), the 16 behaviour-group→owner map **referenced** (render-path-cutover.md §3/§11) not duplicated, the staged RenderGate / VisibilityWriteAuditLogger safety-net mechanic, decision-trail link to ADR-0004 + ADR-0005 DH. §14/15/16 (Information Gaps/Change History/References) renumbered; new Change-History entry added; §1.1 pre-refactor narrative intentionally preserved. | new-section |

## Specs converted

None. Per the Discovery report, `render-path-cutover.md` is a **derivative**
spec ("adopts Spec 1/2, does not redesign") — converting it to a permanent
`docs/architecture/` doc would create a second render-path SoT (the exact
SSoT anti-pattern the ADR-0001 2026-05-14 cleanup DH fixed). Its decisions
flowed into rendering.md §13 + the ADR-0004/0005 DH instead. 0 conversions
is the correct outcome.

## Auto-fixes applied

None. Every new `[text](path)` / `@see`-style cross-doc link was verified
to resolve on first write (render-path-cutover.md, the Epic plan, ADR-0004,
ADR-0005, integration-check.md, B5/B2 reports, the two research files, the
`#decision-history` auto-anchor). No moved/renamed targets within the
touched set, so no auto-fix was needed.

## Inline-anchor pass — zero-gap NOT fully confirmed (1 flagged)

The Discovery report claimed "ZERO anchor gaps" — that verdict was scoped
to `@see` **path resolution** and **missing headers** (both confirmed:
the 92 `@see docs/plans/...` anchors resolve; no missing module headers on
the new cutover classes). It did **not** assess **tense-staleness in
existing headers**. A 5-file spot-check (`OverlayResetHandler.kt`,
`ContentAreaController.kt`, `EditBarController.kt`, `EmojiController.kt`,
`OverlayCharactersController.kt`) found two header styles:

- **Correctly re-tensed (intentional historical anchors)** —
  `OverlayResetHandler.kt`, `ContentAreaController.kt` both carry an
  explicit "# Wiring status (post-CR-DEL — sole live owner)" section
  stating *"Now that `KeyboardStateManager` is **deleted** (CR-DEL
  completed)… the earlier framing is historical: there is no
  `KeyboardStateManager`"*. These are exemplary provenance anchors — the
  deleted-controller mentions are deliberate history, not stale refs.

- **⚠ Stale present-tense (NOT re-tensed after CR-DEL ran)** —
  `EditBarController.kt`, `EmojiController.kt`,
  `SpecialTouchHandlerInstaller.kt`, `RenderGate.kt` still assert
  present/future tense that is now false: *"the listeners
  `MainButtonsController.registerEditBarListeners()` wires **today**"*,
  *"the class CR-DEL deletes"* (future), *"is **still LIVE** until CR4"*,
  *"the legacy `MainButtonsController` therefore stays the **sole LIVE
  owner**"*. `MainButtonsController` is **deleted** (filesystem-verified)
  — these read as current-state claims that contradict the shipped code.
  `OverlayCharactersController.kt` is borderline (frames "sole live owner"
  as the dormant-phase contract description but lacks the explicit
  post-CR-DEL re-tensing its siblings have).

**Disposition: FIXED (Phase-4.6c inline re-tense follow-up, 2026-05-17).**
The mechanical re-tense was applied with source-edit rights by
`B0-DOCS-WORKER-INLINE-retense`. All 5 files (incl. the borderline
`OverlayCharactersController.kt`, which did carry the stale "stay the
sole live owner" / "build-but-dormant as present state" framing) now
mirror the `OverlayResetHandler`/`ContentAreaController` "Wiring status
(post-CR-DEL — sole live owner)" pattern: a one-paragraph wiring-status
header stating the cutover is complete + the class IS the sole live
owner, with the CR-EXTRACT/CR4/CR-DEL staging past-tensed as the
*historical mechanic* by which it became live (history kept as history,
not deleted). **KDoc/comment-only** — the `git diff` is 100% inside
comment regions (verified: zero code/import/annotation/signature lines
changed, all `@see` anchors byte-identical), and `./gradlew
assembleDebug` is green (exit 0, APK produced). Fixed files:
`state/render/EditBarController.kt`, `state/render/EmojiController.kt`,
`state/render/SpecialTouchHandlerInstaller.kt`,
`state/render/RenderGate.kt`,
`state/render/OverlayCharactersController.kt`.

## Deferred to Phase-5 — post-archive `@see`-path resolution

92 `@see docs/plans/...` anchors exist in source. They currently **all
resolve**:

- Parent-plan anchors (`2026-05-07 - dictate-keyboard-layout-refactor/…`)
  point at an already-archived plan dir — stable, resolve.
- Epic anchors (`2026-05-15 - dictate-cutover-completion/…`, e.g.
  `QwertzRecordingController.kt:44`, `RenderGate.kt:55`,
  `EmojiController.kt:51`, `DictateUiState.kt:301`) point at the
  **live, not-yet-archived** Epic plan dir — they resolve **now**.

The Epic's `archive_target` is `2026-05-15 - dictate-cutover-completion`
(**same folder name**), so the Epic-pointing `@see` paths are in fact
**stable across archive** — no rewrite needed. Per task scope these were
**not pre-broken / not rewritten**. **Phase-5 watch item (likely a
no-op):** after `PHASE5-ARCHIVE`, re-confirm the Epic `@see` paths still
resolve (they should, identical folder name); the new cross-doc links in
the 3 file-docs + 3 ADR DH use the same `2026-05-15 - …` path and are
likewise archive-stable.

## SSoT watch-item (note only — no action)

`RenderGate.kt` / `VisibilityWriteAuditLogger.kt` class headers narrate the
RR-2 staged-cutover rationale at paragraph length. This is the **mechanic's
own** rationale (legitimately inline) and does **not** duplicate
`rendering.md` §13: §13 is the architecture-level *outcome*, the headers
are the per-class *why*, and the 16-row behaviour-group map lives only in
`render-path-cutover.md` §3 (referenced from both, copied to neither). A
`> [!NOTE]` in rendering.md §13.2 records this boundary explicitly. No
active duplication today. Watch item: if a future rendering.md edit ever
inlines the per-class mechanic narrative, deduplicate toward the spec.

## Documentation gaps (Active Notification)

| File / area | Subsystem | Why doc-worthy |
|------|-----------|----------------|
| `preferences/{LanguageResolver,InputLanguages*,versioned/VersionedPrefs}.kt` | preferences | No architecture doc for the preferences subsystem. **Pre-existing, low priority, NOT Epic-introduced** (the Epic only added `LanguageResolver` as the D-13 `LanguageController` split — well-documented inline). |

**Recommendation:** close via a separate documentation plan only if the
preferences subsystem grows; not auto-created. Non-blocking.

## Knowledge-skill flags

1 **carried** (not Epic-introduced): no `knowledge-kotlin` /
`knowledge-android` skill exists for the Epic's reusable Android/Kotlin
patterns (thin-Subsystem-adapter delegating to a process-global singleton;
the staged build-but-dormant→`RenderGate`-armed→delete cutover mechanic;
the `imeSideAffordance` legacy-callback pattern; the
`VisibilityWriteAuditLogger` strict-mode double-write guard). The parent
plan already noted this. **Flag only, no auto-gen** — the patterns are
well-documented inline (substantive headers + `@see`) and in the specs;
the state-pattern itself is fully covered by the `state-architecture/`
doc-set + ADRs. Nice-to-have, not a blocker.

## ADR flags

**No new ADR.** Confirmed correct per Discovery + lifecycle-adr.md: the
Epic *implements/extends* Accepted ADRs (INT-1 option (a) = realise the
parent ADRs' intended end-state), it does not reverse or re-paradigm any.
The "no parallel-dormant layer / 3× recurrence resolved-not-deferred"
narrative is a **consequence** of ADR-0001's single-dispatch + D7 (a
user-level engineering principle) — a standalone ADR would be net
SSoT-redundancy. Captured as the ADR-0001 DH Reasoning + the Phase-5
closure narrative. 3 DH appends, 0 new ADRs, 0 supersedes.

## Cross-doc sanity verdict

**One coherent story — YES.** The 3 file-docs + 3 ADR DH + the Epic plan +
`integration-check.md` + `render-path-cutover.md` tell a single consistent
post-cutover architecture: two-orchestrator coexistence collapsed →
`DictateOrchestrator` sole production state-router; `PipelineOrchestrator`/
`JobExecutor` survive only as the Spec 1 §9.6 `PipelineRunnerSubsystem`
adaptee; `RenderBackend` sole render driver; 4 legacy controllers +
`LanguageController` deleted; stubs `@Deprecated` test-only; staged
RenderGate / VisibilityWriteAuditLogger safety-net; INT-1 3× recurrence
resolved-not-deferred. **No doc-on-doc contradiction.** The
`state-and-actions.md` §1.1 retains `RecordingStateController` in its
historical scattered-writer list — verified correct (that controller
**survives**; only the other 3 + `LanguageController` were deleted) — so
this is precise, not a contradiction. SSoT preserved (owner-map pointed-at,
never duplicated).

## ADR index (docs/decisions/README.md)

**No change needed.** Checked the index convention: the index table
columns are ID / Title / Subsystem-Scope / Status / Date — none change on
a Decision-History append (Status stays `Accepted`, Date is the ADR header
date not the DH date). The relationship graph is also unaffected (no new
ADR, no new cross-edge — the Epic ↔ ADR links live in each ADR's
`## References`, which the index does not mirror). Per its own convention
the index is untouched.

## Follow-ups for next plan / Phase 5

1. **Inline-anchor re-tense** — ✅ **DONE** (Phase-4.6c follow-up,
   2026-05-17). The 4 + 1 borderline render-owner headers were
   re-tensed to the `OverlayResetHandler`/`ContentAreaController`
   post-CR-DEL pattern. KDoc-only, assembleDebug green. See the
   `### Phase-4.6c inline re-tense follow-up` section below.
2. **Phase-5 `@see`-path re-confirm** — after `PHASE5-ARCHIVE`, sanity-check
   that Epic-pointing `@see` paths still resolve (expected no-op — identical
   archive folder name).
3. **Phase-5 closure narrative** — carry the "INT-1 anti-pattern recurred
   3×, resolved-not-deferred" lesson into the closure (already in ADR-0001
   DH Reasoning; the closure should cross-reference it).
4. (carried) preferences-subsystem architecture doc — only if the
   subsystem grows; separate doc plan.
5. (carried) `knowledge-kotlin`/`knowledge-android` skill — nice-to-have.

## Files changed (docs only)

- `docs/decisions/0001-state-modular-orchestrator-pattern.md` (DH append + References Related-Plan bidirectional link)
- `docs/decisions/0003-service-foreground-pipeline-architecture.md` (DH append + References Related-Plan bidirectional link)
- `docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md` (DH append + References Related-Plan bidirectional link)
- `docs/architecture/state-architecture/state-and-actions.md` (§1.1 post-cutover NOTE)
- `docs/architecture/state-architecture/README.md` (naming-note re-tense + 60-sec diagram)
- `docs/architecture/state-architecture/rendering.md` (new §13 + renumber + Change-History entry)
- `docs/plans/2026-05-15 - dictate-cutover-completion/reports/phase-4.6-report.md` (this report)

No production/test source modified **by the doc-phase itself**. No ADR
status changed. No new ADR files. No commit (orchestrator commits).

(The Phase-4.6c follow-up below modified 5 production render files —
KDoc/comment text only, see that section for the file list.)

---

### Phase-4.6c inline re-tense follow-up

**Date:** 2026-05-17
**Agent:** `B0-DOCS-WORKER-INLINE-retense` → `-VERIFY`

The single flagged inline item from the "Inline-anchor pass" section
(stale present-/future-tense headers in the render-owner files
post-CR-DEL) was resolved with source-edit rights. This is the
sanctioned inline-anchor scope (module/class header text only) — **zero
logic / import / annotation / signature change**.

**What changed (per-file before→after header gist):**

| File | Before (stale) | After (post-CR-DEL) |
|------|----------------|---------------------|
| `EditBarController.kt` | "listeners `MainButtonsController.registerEditBarListeners()` wires **today**"; "the class CR-DEL **deletes**"; "**still LIVE** in CR-EXTRACT"; "stays the **sole LIVE owner** … **CR4** calls [attachToViews]" | new "# Wiring status (post-CR-DEL — sole live owner)" section; legacy "used to wire before the cutover"; "the class CR-DEL **deleted**"; RR-1 section retitled "— historical", past-tensed (was LIVE / CR4 called / CR-DEL deleted) |
| `EmojiController.kt` | "listeners `…registerEmojiListeners()` wires **today**"; "the class CR-DEL **deletes**"; "still LIVE until CR4 … **CR4** calls [attachToViews]" | new "# Wiring status" section; legacy "used to wire"; "the class CR-DEL **deleted**"; RR-1 section "— historical", past-tensed + CR-DEL append |
| `SpecialTouchHandlerInstaller.kt` | "is **still LIVE** in CR2 — removed only by **CR4**"; wiring-block `(LIVE)`; "would **silently overwrite**"; "stays the **sole LIVE owner** … through CR2/CR3"; "**CR4 flips it**" | new "# Wiring status" section; RR-1 "— historical"; `(LIVE pre-CR4)`; "would have **silently overwritten**"; "**stayed** the sole LIVE owner"; "**CR4 flipped it** … then **CR-DEL** deleted" |
| `RenderGate.kt` | "Theme C-R **attaches** … in CR3, but legacy KSM … removed only in **CR4**"; "If a controller writes the axis while KSM still drives it"; "# CR4 flip / CR4 **calls** [arm]"; `armed` doc "CR3 default / CR4 flips it"; `shouldWrite` doc "legacy KSM **is** the sole live writer" | "# Why a gate … — historical rationale" (KSM **deleted**, gated controllers sole owners, staging past-tensed); "# CR4 flip — historical"; `armed`/`arm()`/`shouldWrite` docs re-tensed — dormant mode survives as unit-test/audit-proof config, controllers permanent sole writers. The live API semantics (dormant/armed/`null`-gate) kept intact + present-tense (legitimate per the §SSoT watch-item — this is the mechanic's own contract). |
| `OverlayCharactersController.kt` (borderline — **did** carry stale framing, fixed) | "the class CR-DEL **deletes**"; "legacy … **stay** the sole live owner"; "**dormant (CR-EXTRACT default)** / **armed (CR4 `arm()`)**" as present states | new "# Wiring status (post-CR-DEL — sole live owner)" section; legacy "used to … "; "the class CR-DEL **deleted**"; RR-2 section "— historical", dormant/armed phases past-tensed, "armed state is now the permanent production configuration" |

**Verification:**

- `git diff --stat`: 5 files, +175/-96, all in `state/render/`.
- **Comment/KDoc-ONLY confirmed:** every changed hunk (every `+`/`-`
  line) is a comment line (`*` / `/**` / `*/` / `//`) or blank — a
  filtered `git diff -U0` for any non-comment changed line returned
  **zero** output. No code, import, annotation, or signature changed.
- **`@see` anchors:** byte-identical (a `@see`-grep of the diff returned
  zero changed lines). Epic-plan-dir `@see` paths left as-is
  (archive-stable per the Deferred-to-Phase-5 section).
- `./gradlew assembleDebug`: **green** (exit 0, `app-debug.apk`
  produced) — expected for a comment-only diff, sanity-confirmed.
- The 5 files now tell the same post-CR-DEL story as the
  already-correct `OverlayResetHandler.kt` / `ContentAreaController.kt`
  (Wiring-status header first, staging mechanic kept as *history*).

**Files changed:** 5 production render `.kt` files (KDoc-only, listed
in the table above) + this report. No commit (orchestrator commits with
the Phase-4.6 wave).
