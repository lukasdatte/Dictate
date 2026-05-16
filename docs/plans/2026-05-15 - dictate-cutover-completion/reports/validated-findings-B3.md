# Validated Findings — Block 3

**Agent-ID:** B3-VAL-SANITY
**Date:** 2026-05-16
**Block scope:** B3 = C8-C1 (LanguageController removal, D-13) + C9-C2 (audioFile field removal, D-14). C10-C3 (dead-controller retire) is **moved to a new B5 / render-path-cutover block** per the Critical C10-IMPL-2 architecture-conflict escalation; only its additive OQ-1 KDoc landed in B3's diff and carries no logic/test/API surface.
**Source audits:**
- `./reports/audit-plan-and-api-B3.md` — 0 Crit / 0 Imp / 2 NTH (+ 7 PASS/SOUND/GREEN AC-Risk verdicts)
- `./reports/audit-convention-B3.md` — 0 Crit / 0 Imp / 2 NTH (+ 10 verified-clean convention checkpoints)
- `./reports/audit-logic-B3.md` — 0 Crit / 0 Imp / 3 NTH (+ R-3/override-clobber/F-15/R-5 load-bearing traces all clean)
- `./reports/audit-test-B3.md` — 1 Imp (C8-IMPL-1 root-caused) + 1 Imp (doc-gap AUDIT-TEST-B3-1) + 1 NTH (R-3 coverage gap); 3/3 uncached runs fail only on the C8-IMPL-1 pollution flake, 1047/1048 green every run

## Summary

- 🟢 valid + auto-fixable: **5** (Critical: 0, Important: 2 [F-1, F-2], Nice-to-have: 3 [F-3, F-4, F-5])
- 🟡 valid + research-needed: **0**
- ❌ eliminated / deferred-with-tracking: **3** (F-6 dual-carrier → tracked-for-B5; convention-2 broad-catch → false-positive-as-finding; R-3 IME-binder → known inherent gap)
- Validated-no-residual (AC/Risk verdicts confirmed, no action): **7 buckets**

Per D3 (fix-every-polish-point incl. NTH in *this* block): every real finding incl. Nice-to-have is classified 🟢 for repair in B3. Only the genuine intentional deferrals — the dual-carrier ReprocessStaging cleanup that legitimately belongs to B5/render-path-cutover, and the inherent untestable-IME R-3 coverage gap — are ❌/postponed-with-tracking.

## Cross-cut patterns

- **`DictateInputMethodService.java` `resolveEffectiveLanguage()` / `setLanguageFromPicker()` cluster** — AUDIT-CONVENTION-B3-1 (duplicated ReprocessStaging-override-extraction with a blank-guard *drift*) + AUDIT-LOGIC-B3-2 (the same listener/staging interaction wants a clarifying comment) + AUDIT-PLAN-AND-API-B3-1 / AUDIT-LOGIC-B3-1 (the *dual-carrier* SoT, which is the underlying systemic theme). The duplication+blank-guard-drift is a local code-quality fix-now (F-3); the dual-carrier *collapse* is render-cutover territory (F-6, deferred to B5).
- **Documentation-accuracy systemic** — AUDIT-TEST-B3-1 (the C8/C9 "debug always green / 1048/0/0" claims are falsified) + AUDIT-PLAN-AND-API-B3-2 (§9.6-vs-LanguageResolver reconciliation) + AUDIT-LOGIC-B3-2/B3-3 (clarifying comments). A small doc-trail-accuracy bundle; all 🟢, all in B3, all needed for Phase 4.6/4.7 doc fidelity.
- **Dual-carrier ReprocessStaging override** — flagged from *three* angles (PLAN-AND-API-B3-1, LOGIC-B3-1, and the convention-1 footnote). Consensus across all three auditors: it is **intentional transitional duplication**, behaviour-safe today (single reader = legacy carrier, the new staging-send resolver is not IME-attached per C10-IMPL-2), and its *collapse* is genuinely the missing B5/render-path-cutover block's job. → tracked, deferred-with-rationale (F-6), NOT a B3 defect.

## Findings

### F-1 (was AUDIT-TEST C8-IMPL-1 — root-cause diagnosis)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:** `app/src/main/java/net/devemperor/dictate/DictateApplication.java:64-70` (polluter spawn-site), new `app/src/main/java/net/devemperor/dictate/database/DurationHealingScheduler.kt` (to create), `app/src/test/java/net/devemperor/dictate/migration/LegacyAudioFileMigrationTest.kt` (@Before/@After wiring), `DictatePipelineService*` boot-test teardowns (belt-and-suspenders)
- **Description:** `DictateApplication.onCreate()` spawns `DurationHealingJob.heal()` on a `Executors.newSingleThreadExecutor()` with a **non-blocking** `executor.shutdown()` and **no cancel/await seam**. Every Robolectric test in the JVM fork instantiates `DictateApplication` → spawns a heal thread. `DurationHealingJob.heal()` runs `SELECT * FROM sessions WHERE audio_file_path IS NOT NULL AND audio_duration_seconds = 0` (no status/legacy filter) on the shared process-wide `DictateDatabase` singleton; for any matched row whose file is absent it writes `status=FAILED` + `last_error_message="Audio file not found during healing"`. An in-flight heal thread races `LegacyAudioFileMigrationTest`'s inserted rows, overwriting their `status`/`last_error_message` and failing the assertion at a **method-varying** line (the textbook non-deterministic shared-state-pollution signature). The string `"Audio file not found during healing"` exists in **exactly one place** (`DurationHealingJob.kt:61`) — smoking-gun proof the polluter is `DurationHealingJob`, not `LegacyAudioFileMigration` (whose `REASON` is `"audio_file_path_legacy_purged"`). The existing `DictateDatabase.resetForTest()` only closes+deletes the DB file; it has **no seam** to cancel/await the heal executor — the heal thread can run *after* `resetForTest()` rebuilds the DB and pollute the *next* test.
- **Why valid (not eliminated):** Independently confirmed against the block report. AUDIT-TEST reproduced 3/3 uncached runs (debug→kt:244, release-1→kt:220, release-2→kt:244 — method-varying, the pollution fingerprint). Zero code-path overlap with the C8/C9 diff (language-only / audioFile-field; B3's new tests are pure-JVM `FakeSharedPreferences`, cannot be the polluter). This is a distinct shared-state axis from B2-VAL-W1's `ActiveJobRegistry.resetForTest()` (that is the job-lock axis; this is the `DurationHealingJob`/DB-singleton async axis). It is the block's **forwarded Postponed test-debt being actioned now per D3** — NOT re-postponed.
- **Suggested fix (fully specified by AUDIT-TEST, mechanical, test-infra only — no production behaviour change):**
  1. Extract the healing executor into a production-owned holder, `database/DurationHealingScheduler.kt`, owning the `ExecutorService` and exposing a `schedule(dao, repo)` (the current `onCreate` async-single-shot semantics, unchanged in production) plus:
     ```kotlin
     @JvmStatic @VisibleForTesting
     internal fun resetForTest() {
         executor?.shutdownNow()
         executor?.awaitTermination(5, TimeUnit.SECONDS)   // drain in-flight heal
         executor = null
     }
     ```
     `DictateApplication.onCreate()` calls `DurationHealingScheduler.schedule(db.sessionDao(), recordingRepository)` instead of inlining the executor. Behaviour identical in production (still async, still single-shot, still shut down after enqueue). Mirrors the established `DictateDatabase.resetForTest` / `JobExecutor.resetForTest` / `ActiveJobRegistry.resetForTest` production-seam convention.
  2. Call the new `DurationHealingScheduler.resetForTest()` from `LegacyAudioFileMigrationTest` `@Before` AND `@After` — **before** `DictateDatabase.resetForTest(context)` (drain the heal thread before the DB is rebuilt). This is the single mandatory site to fix the observed failure.
  3. Belt-and-suspenders: add the same teardown call to the `DictatePipelineService*` boot-test teardowns (they each run `DictateApplication.onCreate` → spawn a heal thread; the same "complete the discipline at the source" pattern B2-VAL-W1 F-6 applied to `ActiveJobRegistry`).
  After the seam lands, re-run `testDebugUnitTest --rerun-tasks` AND `testReleaseUnitTest --rerun-tasks` ≥2× to confirm determinism.
- **Domain bundle candidate:** test-infra (DurationHealing/DB-singleton async axis). Bundle with F-2 (the doc-correction is the natural co-commit once the flake is closed).
- **Routing:** repair-sub-phase, this block (B3-VAL repair wave).

### F-2 (was AUDIT-TEST-B3-1 — documentation gap)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:** `./reports/B3-theme-c-legacy-retire.md` — C8-C1 `#### Issues` row IMPL-1 (block-report line ~212); C9-C2 `#### Resend + migration still work` (line ~360-361) and `#### Build + test (AC-9)` (line ~368-370)
- **Description:** The C8-C1 and C9-C2 block-report claims that the flake is **release-only** and that `testDebugUnitTest` (uncached) is **all green (1048/0/0)** are **false**. AUDIT-TEST's authoritative uncached `testDebugUnitTest --rerun-tasks` **fails** (`LegacyAudioFileMigrationTest.kt:244`, 1048/1). The C8-IMPL-1 flake manifests in **both** the debug and release suites non-deterministically; the "debug always green / release-only" framing is inaccurate.
- **Why valid:** Directly verifiable in the block report — the false claims are quoted in the report at the lines above; AUDIT-TEST's 3-run table (debug→1 failure) contradicts them. This does **not** change the root cause or the not-a-regression verdict, but the doc-trail must be accurate for Phase 4.6/4.7 and PR-review (they read these sub-sections).
- **Suggested fix (doc-only):** Correct the three sub-sections to state the C8-IMPL-1 flake manifests in **BOTH** `testDebugUnitTest` and `testReleaseUnitTest` non-deterministically (method-varying), 1047/1048 green every run, single failure always the forwarded C8-IMPL-1 pollution flake. No code revert; no verdict change. Co-commit with F-1's fix (once the seam lands, the corrected text also reflects the resolution).
- **Domain bundle candidate:** doc-trail (bundle with F-1 — the doc correction lands in the same repair wave that closes the flake).
- **Routing:** repair-sub-phase, this block.

### F-3 (was AUDIT-CONVENTION-B3-1)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:1628-1639` (`resolveEffectiveLanguage`) and `:1797-1808` (`setLanguageFromPicker`)
- **Description:** The ReprocessStaging-override extraction (`uiController != null && uiController.getState() instanceof PipelineUiState.ReprocessStaging` then cast + `getSelectedLanguage()`) is duplicated **byte-identically** across `resolveEffectiveLanguage()` and `setLanguageFromPicker()`. Worse, the two copies have already **drifted** on the blank-guard: `resolveEffectiveLanguage()` trims+empty-checks the override (`override != null && !override.trim().isEmpty()`), `setLanguageFromPicker()` writes whatever it gets with no guard. This is the exact "same operation, two ways, with a latent correctness drift" category.
- **Why valid (and why fix-now rather than defer-to-B5):** The convention auditor noted a reviewer *may* legitimately defer the *extraction* to the (re-scoped) B5/render-path-cutover where the override collapses onto `LanguageState.override`. However, the **blank-guard drift is a latent correctness smell** (`setLanguageFromPicker()` could persist a blank override the read-path would then treat as "no override"), and it lives entirely inside C8-C1's own new helpers (B3-introduced code). Per D3 the polish point is fixed in this block; per D4 (long-term sustainability) normalising the guard now removes a divergence trap that would otherwise quietly survive into B5. Fix-now, scoped to B3's own new code.
- **Suggested fix (small, one-file, mechanical):** Extract `private String reprocessStagingOverrideOrNull()` returning the **trimmed non-blank** override or `null` (the safer of the two existing behaviours — normalises the drift), and have both `resolveEffectiveLanguage()` and `setLanguageFromPicker()` branch on it. This removes the duplication AND the blank-guard drift in one move. (The *dual-carrier collapse* onto `LanguageState.override` is a separate, larger concern — that is F-6, deferred to B5; F-3 only de-duplicates + normalises the existing legacy-carrier read, which is B3-local and behaviour-preserving.)
- **Domain bundle candidate:** `DictateInputMethodService.java` resolveEffectiveLanguage/setLanguageFromPicker cluster (bundle with F-4).
- **Routing:** repair-sub-phase, this block.

### F-4 (was AUDIT-LOGIC-B3-2)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:907-913` (`inputLanguagesListener`) — relates to `pushPermanentLanguageToOrchestrator()` `:1639-1654`
- **Description:** When `inputLanguagesListener` fires (a Settings-side input-languages write) **while the IME is in ReprocessStaging with an active override**, the chip/label correctly keep showing the override (they re-resolve via `resolveEffectiveLanguage()`, which honours the staging override), while the dispatched `RefreshFromPref(permanentCode)` moves `state.language.effective` (the permanent axis) underneath. This is **behaviourally correct** (override beats permanent for display; `effective` is the permanent axis; matches the deleted controller's `notifyIfChanged()`/`computeEffective()` split — confirmed in the LOGIC trace) — but the interaction is subtle and undocumented at the listener site, where a future reader could mistake the silent `effective` move for a clobber and "fix" it wrongly.
- **Why valid:** LOGIC audit confirmed it is not a bug; the only gap is the missing inline rationale at a non-obvious correctness invariant — exactly the "document the why the reader cannot derive" baseline. Per D3 the NTH doc point is fixed in B3.
- **Suggested fix (one comment):** Add a one-line comment at `:907` (the `inputLanguagesListener` site) noting: "override (if in ReprocessStaging) still wins for display via `resolveEffectiveLanguage()`; this listener only moves the permanent `effective` axis — the two are orthogonal `LanguageState` fields (see `LanguageModuleTest` 'RefreshFromPref does not clear an active override')."
- **Domain bundle candidate:** `DictateInputMethodService.java` language cluster (bundle with F-3).
- **Routing:** repair-sub-phase, this block.

### F-5 (was AUDIT-PLAN-AND-API-B3-2)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** documentation reconciliation — C8-C1 deviation table in `./reports/B3-theme-c-legacy-retire.md` (the `#### Files modified — drift classification` already partially notes this) vs Spec 1 §9.6
- **Description:** Spec 1 §9.6 prescribes `LanguageController` → "wandert direkt in den Modul-Reducer; keine Adapter-Phase nötig". The implementation instead extracted the pref-only resolution/write algorithm into a separate stateless `preferences/LanguageResolver` object, keeping `LanguageModule.reduce` strictly I/O-free. This is the **correct** reading of the spec as a whole (Spec 1 §4.11 Pre-Dispatch-Resolution + ADR-0002 mandate a pure reducer; putting `SharedPreferences` I/O literally inside the reducer would violate the pure-reducer invariant). It is a literal divergence from the §9.6 one-liner that should be reconciled in one line so a future reader does not mistake the `preferences/LanguageResolver` placement for unplanned drift.
- **Why valid:** The chosen design is the *more* spec-faithful one; this is a traceability nicety, not a code defect. Per D3 the NTH doc point is fixed in B3 (the doc-trail must let a §9.6-vs-code reconciliation be done without archaeology — the documentation baseline).
- **Suggested fix (one-line doc note):** Add a one-line note to the C8-C1 deviation/drift-classification section of the block-report clarifying that "wandert direkt in den Modul-Reducer" (Spec 1 §9.6) is satisfied by the pref-only algorithm living in `preferences/LanguageResolver` (invoked Pre-Dispatch per Spec 1 §4.11) rather than literally inside `LanguageModule.reduce`, because the reducer must stay I/O-free (ADR-0002). (Spec file edit is out of B3's scope; the block-report note is the SSoT-correct location.)
- **Domain bundle candidate:** doc-trail (bundle with F-2).
- **Routing:** repair-sub-phase, this block.

### F-6 (was AUDIT-PLAN-AND-API-B3-1 + AUDIT-LOGIC-B3-1 + AUDIT-CONVENTION-B3-1 footnote, merged — dual-carrier ReprocessStaging override)

- **Classification:** ❌ deferred-with-rationale (NOT a B3 defect — genuine intentional transitional duplication; collapse is B5/render-path-cutover scope) → **tracked for B5**
- **Severity:** Nice-to-have (informational; behaviour-safe today)
- **Files:** `DictateInputMethodService.java:1611-1621` (`resolveEffectiveLanguage` reads legacy `PipelineUiState.ReprocessStaging.selectedLanguage`) + `state/modules/PipelineModule.kt:324` (new `SendStaging→SubmitReprocess` effect reads `ctx.global.language.override`) + `handleReprocessSend` `:3465,:3518`
- **Description:** The ReprocessStaging language override has **two parallel carriers** during the C8→render-cutover window: the legacy `PipelineUiState.ReprocessStaging.selectedLanguage` (read by `resolveEffectiveLanguage()` / `handleReprocessSend` — authoritative for *every production reprocess transcription today*) and the new `LanguageState.override` (written by `SetOverride` in `setLanguageFromPicker`, read only by the new `PipelineModule SendStaging` path — which per C10-IMPL-2 is **not IME-attached** in production). The carriers cannot diverge into a wrong-language transcription *today* because the new path is not wired; but the moment the render-path-cutover block wires `resolveSendStagingAction` into the IME, whichever carrier is stale wins — a latent wrong-language-transcription trap.
- **Why deferred (not 🟢-fix-now):** Consensus across **three** auditors (PLAN-AND-API, LOGIC, and the CONVENTION-1 footnote) is that this is **intentional, documented transitional duplication** (the C8-C1 block-report "Overlooked points" already names it and hands the collapse to the render-cutover block). The *collapse* — unifying `resolveEffectiveLanguage()` + `handleReprocessSend` + `PipelineModule` onto a single carrier (`LanguageState.override`) and removing the legacy `KeyboardUiController`-backed `ReprocessStaging.selectedLanguage` read — is **large**, depends on the legacy `KeyboardUiController`/`PipelineUiStateReader` retirement, and is exactly the work the new B5/render-path-cutover block owns (C10-IMPL-2 escalation). Fixing it in B3 would pre-empt the blocked cutover (D7 anti-pattern). This is a **genuine intentional-deferred** item with active tracking, NOT a D3 polish point being skipped. (Note: F-3 *does* de-duplicate + normalise the *legacy-carrier read* within B3's own helpers — that is B3-local and behaviour-preserving; F-6 is the *cross-carrier collapse*, a strictly larger and B5-scoped concern. The two do not overlap.)
- **Tracking obligation (mandatory — orchestrator/consolidator):** The new B5/render-path-cutover block scope **must explicitly inherit** the "collapse the ReprocessStaging override read onto `LanguageState.override`; remove the legacy `PipelineUiState.ReprocessStaging.selectedLanguage` carrier read" item that the C8-C1 "Overlooked points" hands off, so the transitional dual-carrier does not become permanent when `KeyboardUiController` is retired. This is folded into the same C10-IMPL-2 render-path-cutover scope (alongside the controller retirements).
- **Routing:** NOT B3 repair. Tracked-for-B5 (record in block-report Issue Index as a B5 carry-over under the C10-IMPL-2 umbrella).

## Eliminated / deferred-with-tracking findings

| Source ID | Source audit | Verdict | Reason |
|-----------|--------------|---------|--------|
| AUDIT-PLAN-AND-API-B3-1 + AUDIT-LOGIC-B3-1 (dual-carrier) | plan-and-api + logic (+ convention-1 footnote) | ❌ deferred-with-tracking → **F-6, tracked for B5** | Genuine intentional transitional duplication; behaviour-safe today (single reader = legacy carrier; new staging-send path not IME-attached per C10-IMPL-2). The *collapse* is large and is the missing B5/render-path-cutover block's job — fixing it in B3 would pre-empt the blocked cutover (D7 anti-pattern). Tracking obligation recorded (B5 scope must inherit the collapse). |
| AUDIT-CONVENTION-B3-2 | convention | ❌ false-positive-as-finding (no action) | The auditor explicitly recorded this as an **observation, not a defect**: the new orchestrator-dispatch helpers' `catch (Throwable t)` + `Log.w("DictateIME", …)` is **byte-consistent with the pre-existing in-file deleted-bridge convention** (the old `languageController bridge dispatch failed` catch used the same shape). The implementers correctly followed the established in-file convention rather than introducing a third error-handling style. No divergence; the auditor flagged it solely so the consolidator would not re-raise the broad catch as new drift. Confirmed: no action. Any narrowing would be a separate file-wide cleanup, out of B3 scope. |
| AUDIT-TEST R-3 `onServiceConnected` coverage gap (AUDIT-LOGIC-B3-3 + AUDIT-TEST Coverage row) | test + logic | ❌ deferred-with-tracking → **known-gap, inherent (not B3-introduced)** | `DictateInputMethodService.onServiceConnected` re-calling `pushPermanentLanguageToOrchestrator()` (Java IME service binder-lifecycle, `:397`) has no automated test — the boot-before-bind race fix is verified by inspection/KDoc only. This is **inherent to the Java IME service's untestable binder lifecycle**, consistent with the project's existing pattern of not Robolectric-testing the IME binder lifecycle (the reducer end *is* covered by the real-orchestrator `LanguageModuleTest` AC-5 propagation test). It is **NOT B3-introduced** (the IME service untestability predates this block) and there is no project-sanctioned mechanic to close it without introducing IME-binder Robolectric tests the project deliberately avoids. Recorded as a known intentional gap, not a B3 defect. AUDIT-LOGIC-B3-3 is a pure verification finding (the two-phase state/UI reconciliation is sound; `onCreateInputView` `:899` always re-pushes — dependency satisfied), no action. |

## Validated-no-residual (AC / Risk verdicts independently confirmed across audits — no action)

| Item | Verdict | Confirmed by |
|------|---------|--------------|
| **AC-5** (LanguageController removal) | **PASS** | `grep -rl "LanguageController" app/src/main` literal-zero; all consumers migrated (DictateApplication singleton + no-op reader removed; PreferencesFragment → LanguageResolver.INSTANCE; IME → resolveEffectiveLanguage/pushPermanentLanguageToOrchestrator/setLanguageFromPicker). Verified by PLAN-AND-API + CONVENTION (no dangling `{@link}`/`[...]`; KDoc-scrub replaced refs, did not blank). |
| **AC-6** (audioFile field removal) | **PASS** | `grep -n "private File audioFile"` zero; all 9 field use-sites provably re-sourced; canonical `RecordingState.audioFileOrNull` accessor. Verified by PLAN-AND-API + LOGIC (per-site trace, triple-guarded behaviour-bearing read). |
| **R-3** (boot-before-bind / onServiceConnected re-push) | **SOUND / CORRECT** | LOGIC line-by-line: `LanguageResolver` genuinely stateless (no retained field; every read re-reads prefs → no cross-instance staleness → the legacy `lastEffective` bug structurally eliminated); unbound→bound reconciliation idempotent (no-change `RefreshFromPref` → reducer `null`); all rebind / pref-change-while-unbound / idempotence edges hold. |
| **Override-vs-rebind clobber** | **CANNOT HAPPEN** | LOGIC: `RefreshFromPref` writes only `effective`, `SetOverride` writes only `override` — orthogonal `LanguageState` fields, disjoint `copy` targets; regression-locked (`LanguageModuleTest:78`). |
| **F-15 side-effect** (RenderBackend `state.language.effective` was always `"system"` pre-C8) | **REAL & CORRECT + COMPLETE** | PLAN-AND-API + LOGIC: pre-C8 the `RefreshFromPref` reducer was a no-op + `PipelinePrefMirror` does not mirror language → `effective` could never leave the `"system"` boot sentinel; C8's payload-bearing dispatch makes it live. `grep` of every `effective =`/`copy(effective` writer → exactly 3 sites (2 boot defaults + the single reducer arm); no second writer can re-strand it. |
| **R-5** (audioFile non-recording reads) | **GREEN — confirmed** | PLAN-AND-API spot-checked 4/9 sites + LOGIC full trace + AUDIT-TEST accessor coverage (5 `assertSame` identity tests, exhaustive sealed `when`). Resend reads Room DB `session.getAudioFilePath()`; legacy-migration reads `Pref.LastFileName` — the field was structurally uninvolved in both. C9 per-site table accurate. |
| **C10-C3 disposition** (only OQ-1 KDoc landed; 4 controller deletions NOT done) | **CORRECT — by design** | PLAN-AND-API: commit `185f3f6` is additive KDoc on `PipelineOrchestrator.kt` only (zero behaviour change); the 4 deletions correctly NOT performed (moved to new B5 per the Critical C10-IMPL-2 architecture-conflict escalation). AUDIT-TEST: `185f3f6` has zero `*Test` files; commit boundaries disjoint. AUDIT-TEST cross-chunk: `LanguageControllerTest` deletions all ported/obsolete-mechanism (coverage preserved, not deleted); the 6 edited sibling tests are mechanical-substitution + the one deliberate `LanguageModuleTest` strengthening — no behavioural assertion silently dropped. |

## Stdout sign-off counts

- 🟢 valid + auto-fixable: 5 (Important: F-1 C8-IMPL-1 fix, F-2 doc-correction; Nice-to-have: F-3 dedup+blank-guard-normalise, F-4 listener comment, F-5 §9.6 reconciliation note)
- 🟡 valid + research-needed: 0
- ❌ eliminated / deferred-with-tracking: 3 (F-6 dual-carrier → tracked for B5; convention-2 broad-catch false-positive → no action; R-3 IME-binder coverage gap → known inherent gap)
