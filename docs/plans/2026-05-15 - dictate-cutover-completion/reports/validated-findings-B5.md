# Validated Findings — Block 5 (Theme-C-R render-path cutover)

**Agent-ID:** B5-VAL-SANITY
**Date:** 2026-05-17
**Source audits:**
- `./reports/audit-plan-and-api-B5.md` (Critical 0 / Important 1 / NTH 1)
- `./reports/audit-convention-B5.md` (Critical 0 / Important 1 / NTH 4)
- `./reports/audit-logic-B5.md` (Critical 2 / Important 3 / NTH 1)
- `./reports/audit-test-B5.md` (Important 1 / NTH 1; AC-RR-7/8 PASS, no non-R-7 regression)

Raw total: 2 Critical / 6 Important / 7 Nice-to-have (15 findings; pre-dedup).

## Summary

- 🟢 valid + auto-fixable: **9** (Critical 0, Important 4, Nice 5)
- 🟡 valid + research-needed: **2** (Critical 2, Important 0, Nice 0)
- ❌ eliminated / accepted-with-rationale: **4** (1 Important regression-class re-scoped, 1 Important deferral, 2 NTH accepted)

Per D3 (fix-every-polish incl. NTH) every NTH that is a genuine in-block
fix is 🟢-classified; only genuine intentional-deferred-with-rationale
or environmental items are ❌. The 2 Criticals + the R-7 3rd axis are
the load-bearing repair items.

## Cross-cut patterns

- **Stale doc-rot cluster (3 findings, same class):** CONVENTION-B5-1
  (ContentArea/OverlayReset/PromptVisibility "Not yet attached / KSM
  owns / D-13 follow-up" KDoc) + LOGIC-B5-4 (`:1442-1445` /
  `:1300-1303` "legacy KSM keeps driving" comment) + the
  LOGIC-B5-2 `:2133` false "cleared on staging exit" KDoc — all are
  the parallel-dormant doc-rot class **this block exists to
  eliminate** (a deleted class still cited as the live owner/fallback).
  The LOGIC-B5-2 KDoc fix is folded into the F-6 🟡 research-repair;
  the other two are independent 🟢 doc-fixes — **domain-bundle the two
  in DictateInputMethodService.java + the 3 render-package controller
  KDocs into one doc-correction repair-wave.**
- **G14 audio-focus stranded twin (2 findings, merge):**
  PLAN-AND-API-B5-1 (missing `EditBarController.refreshAudioFocusIcon`)
  + PLAN-AND-API-B5-2 (Issue-Index/RR-3-trace not tracking the gap) —
  one underlying defect + its doc-trail; merged to F-3.
- **Issue-Index fidelity contradiction (record explicitly):** the
  block-report Issue Index + RR-3 trace + CR-RGATE gate verdict assert
  "F-6 → closed CR-DEL, no regression" (`:1702`, `:1730`, `:1565`) and
  "CR4-IMPL-4 SPACE dual-commit = spec-mapped target, not a defect"
  (`:1285`). **LOGIC-B5-2 reopens F-6** (the collapse removed the read
  side without wiring the write/clear side) and **LOGIC-B5-1 promotes
  CR4-IMPL-4's SPACE sub-clause from "not a defect" to a Critical user
  regression** (the spec's "click for ALL + touch for SPACE" only works
  if the SPACE touch consumes the tap; `CursorSwipeTouchHandler`
  deliberately returns `false`, so the two paths double-commit). Both
  prior "closed/not-a-defect" verdicts are now **invalidated** —
  recorded as a validated contradiction for the orchestrator.
- **R-7 family closure:** AUDIT-TEST-B5-1 is the **3rd and final** R-7
  axis (after B2-VAL `ActiveJobRegistry`, B3-VAL `DurationHealing-
  Scheduler`). Precise one-file fix specified; actioned in-block per D3
  (NOT re-postponed).

## Findings

### F-1 (was AUDIT-LOGIC-B5-1) — SPACE tap double-commits a space

- **Classification:** 🟡 valid + research-needed
- **Severity:** Critical
- **Files:** `state/render/ImeViewBackend.kt:334-360` (`wireStaticHandlers` sets `OnClickListener` on **every** button incl. SPACE — verified: the SPACE skip at `:402-405` is `keyPressAnimator`-only, NOT the click loop); `state/render/SpecialTouchHandlerInstaller.kt:229-260` (`buildSpaceTouchHandler` returns `swipeHandler.onTouch(...)` = `consumeTouchEvents`); `keyboard/CursorSwipeTouchHandler.kt:67-77` (`ACTION_UP` no-swipe → `onTap()` then `return consumeTouchEvents` = `false`)
- **Description:** Verified by direct trace. One physical SPACE tap → `CursorSwipeTouchHandler.onTap()` commits `" "` (space #1) and the outer touch listener returns `false` → Android `View` dispatch does not consume → `performClick()` fires the SPACE `OnClickListener` → `SpaceKey` → `KeyboardInputModule` → `Effect.SendSpace` → `commitText(" ", 1)` (space #2). Legacy `MainButtonsController` (`git show c92ebd1:`) had **no** SPACE click listener (touch-only), so its `consumeTouchEvents=false` was harmless. The cutover (CR1 click-for-all + CR4 catalog `SpaceKey`) introduced the second path. BACKSPACE/ENTER are NOT affected (no commit-on-tap in their handlers — traced).
- **Why research:** Three candidate fixes — (a) exclude SPACE from the `wireStaticHandlers` click loop (legacy-faithful, lowest-risk), (b) make `buildSpaceTouchHandler` consume the tap on `ACTION_UP` while still returning `false` during a swipe, (c) route SPACE click → no-op. Each interacts with the §11.7 verbatim builder contract, the cursor-swipe MOVE-propagation invariant (`CursorSwipeTouchHandler` *must* return `false` so MOVE events propagate per its own KDoc — G4), and Spec 2 §6's reference `wireStaticHandlers` "click for ALL + touch for SPACE/BACKSPACE/ENTER" model. The audit recommends (a) but the choice must be validated spec-faithful so the cursor-swipe and the §11.7 contract do not silently break — a small but interaction-subtle state-machine decision.
- **Research topic:** `space-touch-vs-click-double-commit`
- **Repair scope:** small (one-file), but research-gated. Add a regression test asserting one SPACE tap = exactly one `commitText(" ",1)` (the defect survived because `ImeViewBackend` fakes do not model Android touch→`performClick` fallthrough).
- **Gate relevance:** YES — a shipped double-space keyboard is the exact silent-regression class B5's staged-safety-net exists to prevent. Gate-relevant for B5 close, **not** blocking later chunks/blocks.
- **Domain bundle candidate:** none (independent surface from F-2).

### F-2 (was AUDIT-LOGIC-B5-2 + the LOGIC-B5-2 `:2133` false-KDoc sub-item) — F-6 collapse INCOMPLETE; staging language lost + stale-override leak + false KDoc; F-6 NOT actually closed

- **Classification:** 🟡 valid + research-needed
- **Severity:** Critical
- **Files:** `core/DictateInputMethodService.java:2125-2200` (`resolveEffectiveLanguage` / `reprocessStagingOverrideOrNull`; the `:2132` KDoc falsely asserts override is "written by `SetOverride` from `setLanguageFromPicker` **and cleared on staging exit**"); `:4123-4148` (`onResendLongClicked` → `enterReprocessStaging(...lastSession.getLanguage())` — **no** `SetOverride` dispatch alongside); `:4200` (`cancelReprocessStaging` — no clear dispatch); `:2392` (`setLanguageFromPicker` — verified the **sole** `SetOverride` dispatch in all of `app/src/main/` via `grep -rn SetOverride app/src/main/`)
- **Description:** Verified by trace + grep. (1) **Lost staged language:** staging entry sets only the View-side `PipelineUiState.ReprocessStaging.selectedLanguage`; it does **not** dispatch `LanguageAction.SetOverride(sessionLanguage)`. The only `SetOverride` site is the explicit picker (`:2392`). A staging session entered without manual re-pick → `reprocessStagingOverrideOrNull()` reads a null/stale `LanguageState.override` → falls through to the **permanent** pref language → the language chip + transcription-config snapshot show the **wrong** language for that staging session. (2) **Stale-override leak across sessions:** no `SetOverride(null)` clear exists anywhere; the `reprocessStagingOrNull()` scope-guard prevents the stale value leaking *outside* staging but not *between* staging sessions (pick "de" in staging A → enter staging B for an "en" session without re-picking → chip shows stale "de"). (3) **False KDoc:** `:2132` claims the invariant "cleared on staging exit" which no code implements — masks the bug. Scope note: the reprocess *job* itself is unaffected (`handleReprocessSend():4223` reads `staging.getSelectedLanguage()` directly off the View-side carrier) — this is a **display/config-read fidelity** bug + a false-doc, NOT a wrong-transcription bug — but it is user-visible and **contradicts the block-report's "F-6 closed, no regression" Issue-Index claim** (`:1702`/`:1730`). **F-6 is therefore NOT actually closed; it must be re-opened** (it was prematurely marked closed in CR-DEL).
- **Why research:** The fix needs an entry-seed-vs-read-fallback architectural decision. Preferred (audit + lowest-risk + genuinely single-carrier): dispatch `SetOverride(sessionLanguage)` on staging entry (guarded `pipelineBinder != null`, non-blank — preserve F-3 blank-guard) + `SetOverride(null)` on every staging exit (`cancelReprocessStaging` + reprocess-send → Preparing/Idle transition) + correct/remove the `:2133` KDoc. The alternative (re-introduce a `selectedLanguage` read-fallback) partially re-introduces the dual-carrier F-6 was meant to collapse — must be ruled out as spec-faithful. Validate against Spec 1 §15.2 RecordingModule + Spec 2 §9.5 + the original F-6 intent + the legacy `KeyboardUiController.enterReprocessStaging` semantics (`git show c92ebd1:`) that session-language seeding is the intended initial override.
- **Research topic:** `f6-staging-language-override-lifecycle`
- **Repair scope:** medium (two IME call-sites + KDoc correction), research-gated. Add a regression test for the F-6 staging-language read. Re-open F-6 in the Issue Index (status corrected from "closed CR-DEL" → re-opened, fixed-via-this-wave).
- **Domain bundle candidate:** none (independent surface from F-1; different subsystem — language/staging lifecycle vs. SPACE touch/click interaction).

### F-3 (was AUDIT-PLAN-AND-API-B5-1 + AUDIT-PLAN-AND-API-B5-2, merged) — edit-bar audio-focus icon twin frozen + untracked

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:** `state/render/EditBarController.kt` (missing `refreshAudioFocusIcon`; verified `editAudioFocusButton` is referenced only at `:193` click-listener + `:235` theme-bg, **no** `foreground`/`contentDescription` write); shared SSOT `state/layout/IconResolvers.kt:59` (`resolveAudioFocusIcon` present, F-4-documented, **not called** by EditBarController); `activity_dictate_keyboard_view.xml:419` (frozen static default `ic_baseline_volume_off_24`); `core/DictateInputMethodService.java:4470-4478` (incorrect "owns every axis" comment)
- **Description:** The deleted `MainButtonsController.refreshAudioFocusIcon` drove **two** sites (main-button-area `audioFocusButton` + edit-bar `editAudioFocusButton`). Post-CR-DEL only the main-button-area twin is state-driven (catalog `iconResolver` → `resolveAudioFocusIconForSlot`). The edit-bar twin is frozen at the static `volume_off` default and TalkBack never announces the state — a user-visible parity regression on the always-visible edit bar. Spec 2 §13.2 F-4 explicitly prescribed `EditBarController.refreshAudioFocusIcon` sharing the `resolveAudioFocusIcon` SSOT — this F-4 contract was not implemented when EditBarController was extracted (CR-EXTRACT). Same "§13.2-assumed-an-owner-method-never-created" anti-pattern as CR4-IMPL-1, recurring at the edit-bar audio-focus *icon* axis. The Issue Index + RR-3 trace do not track this gap (B5-2).
- **Suggested fix:** Add `EditBarController.refreshAudioFocusIcon(enabled: Boolean)` setting `editAudioFocusButton.foreground` + `contentDescription` via the existing `resolveAudioFocusIcon(enabled)` SSOT (byte-equivalent to legacy `MainButtonsController.kt:368-385`); drive it from the IME on the same `state.audio.audioFocusEnabledPref` reactive path the AUDIO_FOCUS slot uses (F-4 shared-StateFlow model) or minimally from the audio-focus-toggle handler + initial render. Correct the `:4470-4478` comment. Add the Issue-Index row + a one-line RR-3-trace caveat noting `refreshAudioFocusIcon` had two driven sites and both now have owners.
- **Domain bundle candidate:** `EditBarController` / IME audio-focus wiring.

### F-4 (was AUDIT-CONVENTION-B5-1) — stale "Not yet attached / KSM owns / D-13 follow-up" class-KDoc rot

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:** `state/render/ContentAreaController.kt:14-21` (verified: still says *"Not yet attached in production… KeyboardStateManager… continues to own this axis until the D-13 follow-up block"* — `KeyboardStateManager` is **deleted**, this block just completed that migration); structurally identical stale framing in `state/render/OverlayResetHandler.kt` + `state/render/PromptVisibilityController.kt`
- **Description:** These three controllers are now the **sole live owners** of their visibility axes (KSM gone). The "not yet attached / KSM owns it" header is actively false and self-contradicts the same file's correct "# CR3 staged-safety-net" section. Exactly the doc-rot the convention audit + AC-RR-7 doc-anchor-intentionality rule guard against; a future maintainer reading it may re-introduce a parallel writer — the precise RR-2 failure class this block eliminates. The Self-Code-Fix log (`:1739`) fixed the IME-side dangling javadoc but not these render-package controller KDocs.
- **Suggested fix:** Replace the "Wiring status (IMPL-STATE post-C15, B4-VAL F-6)" block in all three with a current-state note: attached via `KeyboardLayoutManager.attachBackend` (CR3), armed CR4, sole live owner post-CR-DEL (KSM deleted). Keep the gate/null-contract paragraph.
- **Domain bundle candidate:** render-package controller KDoc doc-correction wave (with F-7).

### F-5 (was AUDIT-LOGIC-B5-4) — dead "legacy KSM keeps driving" fallback comment

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:** `core/DictateInputMethodService.java:1440-1447` (catch logs *"Visibility-controller attach/arm failed — legacy KSM keeps driving"*) + `:1300-1303` (`imeViewBackend` attach-failure path)
- **Description:** `KeyboardStateManager` is deleted (CR-DEL). On a real `attachBackend` throw the visibility axes end up with **no driver at all** (frozen/blank, silent) — not the graceful KSM fallback the comment promises. A stale comment promising a deleted safety net is worse than none — a maintainer debugging a frozen keyboard will look in the wrong place. Same doc-rot class as F-4. Low probability (attach failure is rare), but the recovery story is now false.
- **Suggested fix:** Correct the `:1442-1445` and `:1300-1303` comments to state the true post-CR-DEL failure mode (no fallback; the visibility/listener axes are dead until the next successful view-recreate attach). Mechanical comment correction; the orchestrator may additionally decide whether silent degradation here is acceptable (likely yes given rarity — document honestly, do not claim a deleted fallback).
- **Domain bundle candidate:** DictateInputMethodService.java comment-correction wave (with F-4's IME-side note + F-2's `:2133` KDoc if not folded into the F-2 research-repair).

### F-6 (was AUDIT-TEST-B5-1) — R-7 3rd axis: JobExecutor single-thread executor queue never drained by resetForTest()

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:** `core/JobExecutor.kt:33` (`executor = Executors.newSingleThreadExecutor()`, process-global), `:67-72` (`resetForTest()` clears orchestrator/activeToken/activeThread but **not** the executor work-queue)
- **Description:** The 3rd and final R-7 axis (after B2-VAL `ActiveJobRegistry`, B3-VAL `DurationHealingScheduler`). A prior same-fork test's still-finishing `Runnable` (its `finally` at `:161-165` still draining) keeps the FIFO single worker busy; the next test's submit queues behind it → `PipelineRunnerSubsystemAdapterTest:233` "blocking runner did not start" within its 2 s await. testRelease-only (release co-locates forks more aggressively — same R-7 timing-amplification family). `waitForRegistryEmpty()` waits on the registry, not the executor queue, so the registry can be empty while the executor thread is still finishing. Did not reproduce in the AUDIT-TEST 4-run sweep but has fired in CR-EXTRACT/CR-DEL agent runs — a real latent defect, not a phantom.
- **Suggested fix:** Add a sentinel-submit-and-await executor-quiescence drain to `JobExecutor.resetForTest()` (submit a no-op `Runnable`, `check(latch.await(5s))`) — mirrors the B2 `ActiveJobRegistry.resetForTest` / B3 `DurationHealingScheduler.resetForTest` seams. One production-file edit, no test edit (the `@After` already calls `JobExecutor.resetForTest()` first). Keep `executor` immutable (reject the recreate-executor alternative — a `val`→`var` production mutability footgun for a test-only concern). Verify by re-running `testReleaseUnitTest --rerun-tasks` ≥2× — adapter test stays 7/7.
- **Domain bundle candidate:** none (isolated production-file seam).

### F-7 (was AUDIT-CONVENTION-B5-3) — gate-routing seam implemented four different shapes

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have (actioned in-block per D3)
- **Files:** `OverlayResetHandler.kt:106-112` (inline gate block, no helper), `PromptVisibilityController.kt:150-156` + `ContentAreaController.kt:148-156` (`writeVisibility(view,target)` helper), `OverlayCharactersController.kt:148-151` (`shouldWrite(ll): Boolean` — a 3rd shape; name also conceptually collides with `RenderGate.shouldWrite`, different return semantics)
- **Description:** Three structurally different expressions of the one "route the write through the gate" convention. The documented same-shape `writeVisibility` duplication (no premature base class) is acceptable per engineering-principles; the *shape* divergence is the convention smell beyond that. The `OverlayCharactersController.shouldWrite` name clash with `RenderGate.shouldWrite` is the higher-value sub-fix.
- **Suggested fix:** Align the four owners on the `writeVisibility(view,target)` / `writeGated` performing-helper shape (the two CR3 controllers already do); at minimum rename `OverlayCharactersController.shouldWrite` to avoid the `RenderGate.shouldWrite` clash. Pure consistency, no behaviour change.
- **Domain bundle candidate:** render-package controller seam-shape alignment.

### F-8 (was AUDIT-CONVENTION-B5-4) — asymmetric test-accessor idiom between EditBar/Emoji siblings

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have (actioned in-block per D3)
- **Files:** `EditBarController.kt:248` (`val cachedEditNumbersClick` property only), `EmojiController.kt:153,160-162` (`val cachedEditEmojiClick` property **and** `fun invokeEmojiPicked(...)`)
- **Description:** Two accessor idioms for "let the test reach the cached listener" within the same sibling pair extracted in the same chunk. Both work; readability/symmetry nit between two classes the spec explicitly frames as siblings.
- **Suggested fix:** Standardise on the property-accessor idiom (drop `invokeEmojiPicked`; tests do `cachedEmojiPicked?.invoke(e)`) or add the symmetric accessor to `EditBarController` — make the two siblings symmetric (~5 lines).
- **Domain bundle candidate:** EditBar/Emoji controller pair.

### F-9 (was AUDIT-CONVENTION-B5-5) — two dead `@see <deleted-FQN>` Javadoc-link anchors

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have (actioned in-block per D3)
- **Files:** `EditNumbersAnimator.kt:43` (`@see net.devemperor.dictate.core.MainButtonsController`), `core/audit/VisibilityWriteAuditLogger.kt:65` (`@see net.devemperor.dictate.core.KeyboardStateManager`)
- **Description:** A `@see <FQN>` is a navigable cross-reference contract; the target types are deleted → KDoc/Dokka dead links. Distinct from the **prose** `(MainButtonsController.kt:NNN)` provenance pointers, which are the **intentional, accepted** historical trail (same accepted parent-C15 precedent — leave those untouched). Only the two structured `@see <deleted-FQN>` lines are the nit.
- **Suggested fix:** Convert the two `@see <deleted-FQN>` lines to plain prose provenance (the surrounding KDoc already carries the line-level history). Leave all `(File.kt:NNN)` prose pointers as-is.
- **Domain bundle candidate:** none (two isolated KDoc lines; can ride F-4/F-5 doc-wave).

### F-10 (was AUDIT-LOGIC-B5-6) — RESEND double-fire safety relies on an undocumented implicit invariant

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have (actioned in-block per D3)
- **Files:** `state/render/ImeViewBackend.kt:345-347`/`:375-385` (`wireStaticHandlers` RESEND affordance fires before consulting `slot.visibilityPredicate`/`enabledResolver`)
- **Description:** AUDIT-LOGIC verified the RESEND double-fire/cooldown-latch is **adequately mitigated** (three independent guards: manual `inCooldown` re-check, `enabledResolver` disabling the view, PIPELINE-layout `visibilityPredicate=false` → GONE). **No defect.** The only residual: correctness relies on Android not delivering clicks to GONE/disabled views — robust today but a brittle implicit invariant worth one clarifying comment so a future listener-wiring change does not silently reopen it.
- **Suggested fix:** Add a one-line comment in `wireStaticHandlers` noting the affordance's safety depends on GONE/disabled-view click suppression. No behaviour change.
- **Domain bundle candidate:** none.

## Eliminated / accepted-with-rationale findings

| Source ID | Source audit | Verdict | Rationale |
|-----------|--------------|---------|-----------|
| AUDIT-LOGIC-B5-3 | logic | ❌ accepted-with-rationale | **Unbound/boot-path bind-failure is NOT a cutover-introduced regression.** Verified: the current `bindService(...)`-returned-false handling (`DictateInputMethodService.java:698-701`) is **byte-identical** to the legacy `c92ebd1:` `:615-619` (same `Log.e` + `pipelineServiceBindAttempted=false` reset + "subsequent onCreateInputView can retry" comment). What CR-DEL removed is the *unbound listener fallback* (`MainButtonsController.registerAllListeners()` working with no service) — that is the **spec-authorized point-of-no-return** window the audit itself classifies as accepted/documented (block-report `:899`/§6.1). The genuine residual (bindService-false → no retry until next view-recreate) is **pre-existing, identical to legacy, out of cutover scope** → ❌ documented, not a B5 repair item. (Per D4: if a future hardening of the bind-failure path is desired it is a separate plan, not a CR-DEL regression fix.) |
| AUDIT-LOGIC-B5-5 | logic | ❌ accepted-with-rationale (tracking action required) | **Recording BorderGlow/timer side-channel undriven is a pre-existing documented C5-IMPL-2 deferral carried through, NOT a B5-introduced regression.** Recording itself works end-to-end (RecordingModule); only the cosmetic in-keyboard animation/timer is dead; the FGS notification is the authoritative recording-active surface. Consistently documented across CR4-IMPL/CR-RGATE/CR-DEL. **NOT a B5 repair item — BUT the orchestrator must ensure the C5-IMPL-2 amplitude/timer service-side bridge is promoted from "Overlooked points" prose into the Issue Index table as an open deferral so it is not silently dropped at block close / Phase-4 carry-forward** (currently at risk — it lives only in prose). |
| AUDIT-CONVENTION-B5-2 | convention | ❌ accepted | **Uniform `private const val TAG = "DictateIME"` in 4 classes — uniform, not a divergence.** Matches the legacy `MainButtonsController` idiom; the codebase has no shared logging utility/convention. Does not meet the "same operation done two different ways across chunks" bar (it is *consistent*). Out of B5 scope — defer to a hypothetical codebase-wide logging-tag decision. No action. |
| AUDIT-TEST-B5-2 | test | ❌ accepted (environmental) | **KSP incremental-cache race under `./gradlew test --rerun-tasks`** (`FileNotFoundException: app/build/kspCaches/debug/symbols`) — concurrent debug+release KSP racing the shared `kspCaches` dir. **Not a code defect:** `rm -rf app/build/kspCaches && ./gradlew clean assembleDebug` → SUCCESSFUL; split invocations (`testDebugUnitTest` then `testReleaseUnitTest`) fully green ×2 each. Build-infra hygiene note only (prefer split invocations + cache purge before `--rerun-tasks`). No production/test change. |

## Validated context (no-defect confirmations + the recorded contradiction)

- **AC-RR-7 PASS:** 4 controller `.kt` sources deleted; `KeyboardLayoutModeController` absent; 158 grep hits = 0 code refs (all doc-anchors / string-literal owner-tags); `assembleDebug` GREEN (loud-compile proof). `PipelineOrchestrator` survives.
- **AC-RR-8 PASS:** `testDebugUnitTest` independently re-run = **1153 / 0 / 0 / 0**; release **1153 / 0 / 0 / 16-skip** (16 = expected `assumeTrue(BuildConfig.DEBUG)` audit-logger skips, not coverage loss); ≥ Epic baseline ~1048 and ≥ AC-9 (≥946). No bound-path legacy-controller drive remains.
- **Extracted owners byte-equivalent confirmed** (RR-3 trace, 15/16 owners present + IME-wired + spec-faithful; G14 audio-focus *icon* twin = the stranded F-3 sub-axis).
- **`doubleWriteCount==0`** — no double-write defect (RR-2 core proven: `VisibilityWriteAuditLoggerTest` "two distinct LIVE writers = double-write" + Strict-Mode soak GREEN).
- **No non-R-7 regression** — 0 failures across both variants ×2 uncached different-order.
- **RESEND double-fire/cooldown-latch CLOSED** (F-10 is a doc-only residual, not a defect).
- **RECORDED CONTRADICTION (D9 SoT fidelity):** the block-report Issue Index + RR-3 trace + CR-RGATE gate verdict assert **"F-6 → closed CR-DEL, no regression"** (`:1565`/`:1702`/`:1730`) and **"CR4-IMPL-4 SPACE = spec-mapped target, not a defect"** (`:1285`). **F-2 invalidates the F-6-closed claim** (F-6 is re-opened) and **F-1 invalidates the CR4-IMPL-4 SPACE "not a defect" sub-clause** (it is a Critical user regression). The B3-VAL "F-6 closed, no regression" Issue-Index claim is hereby explicitly contradicted and recorded for the orchestrator.

## Repair routing (orchestrator input)

| Wave | Findings | Mode | Notes |
|------|----------|------|-------|
| 🟡-research-1 | **F-1** | research → resume-chain implementer | topic `space-touch-vs-click-double-commit`; add SPACE single-commit regression test; gate-relevant for B5 close |
| 🟡-research-2 | **F-2** | research → resume-chain implementer | topic `f6-staging-language-override-lifecycle`; folds the `:2133` false-KDoc fix; add F-6 staging-language regression test; **re-open F-6 in Issue Index** |
| 🟢-wave-A (audio-focus) | **F-3** | resume-chain consolidator | EditBarController.refreshAudioFocusIcon via shared SSOT + IME wiring + Issue-Index/RR-3-trace doc-trail |
| 🟢-wave-B (doc-rot) | **F-4, F-5, F-9** | resume-chain consolidator | render-package controller KDocs + IME `:1442-1445`/`:1300-1303` comments + 2 dead `@see` lines — one doc-correction wave |
| 🟢-wave-C (seam/accessor) | **F-7, F-8** | resume-chain consolidator | render-package seam-shape alignment + EditBar/Emoji accessor symmetry |
| 🟢-wave-D (R-7 / misc) | **F-6, F-10** | resume-chain consolidator | JobExecutor.resetForTest sentinel-drain (verify testRelease ×2) + the RESEND clarifying comment |

Wave grouping is a suggestion — the orchestrator decides bundling/iteration order (D5 soft-cap 3 per iteration). The 2 🟡 research waves + F-6 (R-7) are the load-bearing items; F-1/F-2 are gate-relevant for B5 close.

## References

- Block-report anchor: `./reports/B5-theme-cr-render-cutover.md#sanity-check-consolidator`
- Plan: `docs/plans/2026-05-15 - dictate-cutover-completion/dictate-cutover-completion.md`
- Source audits: `./reports/audit-{plan-and-api,convention,logic,test}-B5.md`
