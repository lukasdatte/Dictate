# Audit Report: logic (Block 3, scope: full-block)

**Agent-ID:** B3-AUDIT-LOGIC
**Date:** 2026-05-16T00:00:00Z
**Knowledge skills used:** knowledge-typescript (loosely — discriminated-union / exhaustive-`when` reasoning transferred to Kotlin sealed classes; project is Kotlin/Java)
**Files inspected:** 14
- `app/src/main/java/net/devemperor/dictate/preferences/LanguageResolver.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/LanguageModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt` (override read site)
- `app/src/main/java/net/devemperor/dictate/state/Action.kt` (LanguageAction)
- `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt` (LanguageState + audioFileOrNull)
- `app/src/main/java/net/devemperor/dictate/state/layout/TextResolvers.kt` (F-15)
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` (resolveEffectiveLanguage / pushPermanentLanguageToOrchestrator / setLanguageFromPicker / onServiceConnected / inputLanguagesListener / stopRecording / captureFreshConfigSnapshot / onAudioPersisted / handleReprocessSend)
- `app/src/main/java/net/devemperor/dictate/DictateApplication.java`
- `app/src/main/java/net/devemperor/dictate/settings/PreferencesFragment.java`
- `app/src/main/java/net/devemperor/dictate/core/PipelineUiStateReader.kt`
- `app/src/main/java/net/devemperor/dictate/core/KeyboardUiController.kt`
- deleted `core/LanguageController.kt` (git show d39891a — behaviour comparison)
- `app/src/test/java/net/devemperor/dictate/state/LanguageModuleTest.kt`
- `app/src/test/java/net/devemperor/dictate/state/DictateUiStateTest.kt`

Scope note: Block B3 = **C8-C1** (LanguageController removal) + **C9-C2** (audioFile field removal). **C10-C3** controller-deletion is BLOCKED by design (C10-IMPL-2 architecture-conflict, moved to a new render-path-cutover block) — its non-deletion is NOT audited as a defect here; only its one shipped change (the additive OQ-1 KDoc on `PipelineOrchestrator.kt`, non-behavioural) is in diff scope and has no logic surface.

## Summary

- Critical: 0
- Important: 0
- Nice-to-have: 3

The two highest-risk logic questions both resolve **clean**:

- **R-3 boot-before-bind / `onServiceConnected` re-push race — CORRECT.**
  `LanguageResolver` is genuinely stateless (no retained field — verified
  line-by-line: `object` with only a `private const val TAG`, every public
  fn re-reads `SharedPreferences`). The unbound→bound reconciliation is
  sound: `onCreateInputView` calls `pushPermanentLanguageToOrchestrator()`
  with binder potentially null (dispatch skipped, `effective` stays
  `"system"`); `onServiceConnected` re-calls it once the binder exists; the
  reducer reduces a no-change `RefreshFromPref` to `null` (idempotent). The
  rebind / pref-change-while-unbound / RefreshFromPref-idempotence edges all
  hold (detailed trace below).

- **Override-vs-rebind clobber — CANNOT HAPPEN.** `RefreshFromPref` writes
  **only** `effective` (`state.copy(effective = …)`); `SetOverride` writes
  **only** `override` (`state.copy(override = …)`). They are orthogonal
  fields on `LanguageState`. A `RefreshFromPref` re-push on (re)bind or on
  `inputLanguagesListener` fire leaves a pending picker-`override`
  untouched. Structurally guaranteed by the reducer and explicitly
  regression-tested (`LanguageModuleTest:78` "RefreshFromPref does not
  clear an active override").

## Findings

### AUDIT-LOGIC-B3-1

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt:324` vs `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:3465,3518`
- **Description:** Dual reprocess-language source-of-truth during the
  C8→render-cutover window. The new orchestrator's `PipelineModule`
  `SendStaging → SubmitReprocess` effect reads
  `ctx.global.language.override` (the new `LanguageState.override`, written
  by the `SetOverride` dispatch in `setLanguageFromPicker`). The
  production IME reprocess-send (`handleReprocessSend` →
  `pipelineRunner.submitReprocess(…, staging.getSelectedLanguage())`) reads
  the **legacy** `PipelineUiState.ReprocessStaging.selectedLanguage`
  carrier instead. `resolveEffectiveLanguage()` (the IME-direct
  transcription path) also reads the legacy carrier. So today the legacy
  carrier is authoritative for every production reprocess transcription;
  `LanguageState.override` is written but only read by the new-render
  `SendStaging` path, which (per C10-IMPL-2) is **not IME-attached** in
  production. The two carriers cannot diverge into a wrong-language
  transcription *today* because the new path is not wired — but the moment
  the render-path-cutover block wires `resolveSendStagingAction` into the
  IME, whichever carrier is stale wins. This is the C8 report's documented
  "two carriers during the C8→C10 window" gap; it is correctly flagged
  there as transitional and explicitly handed to the (missing) render-path
  block. Recorded here for completeness and to make the divergence trap
  explicit for that future block.
- **Why it matters:** A latent wrong-language-transcription trap that
  activates exactly when the render-path-cutover block lands and forgets to
  collapse the read onto one carrier. It is dormant (not a B3 defect) only
  because the new staging-send resolver is not IME-attached.
- **Suggested fix scope:** large (belongs to the missing render-path-cutover
  block — collapse `resolveEffectiveLanguage()` + `handleReprocessSend` +
  `PipelineModule` onto a single override carrier, `LanguageState.override`).
- **Suggested fix:** needs research / belongs to the re-scoped block; do
  **not** fix in B3 (would pre-empt the blocked cutover). C8's
  "Overlooked points" already names this — keep that hand-off intact.

### AUDIT-LOGIC-B3-2

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:1639-1654` (`pushPermanentLanguageToOrchestrator`) vs `:907-913` (`inputLanguagesListener`)
- **Description:** `pushPermanentLanguageToOrchestrator()` resolves the
  permanent code via `LanguageResolver.effectiveLanguage(sp)` — it does
  **not** consult the ReprocessStaging override. That is correct for the
  *RefreshFromPref* dispatch (RefreshFromPref is the permanent axis only).
  But its sibling UI side-effects in the same method —
  `refreshLanguageChip()` and `mainButtonsController.updateRecordButtonText(
  getDictateButtonText())` — both call `resolveEffectiveLanguage()`, which
  *does* honour the staging override. So if `inputLanguagesListener` fires
  (a Settings-side input-languages write) **while the IME is in
  ReprocessStaging with an active override**, the chip/label correctly
  keep showing the override (because they re-resolve via
  `resolveEffectiveLanguage()`), while the dispatched
  `RefreshFromPref(permanentCode)` updates `state.language.effective`
  underneath. This is internally consistent (override beats permanent for
  display; `effective` is the permanent axis) and matches the deleted
  controller's `notifyIfChanged()`/`computeEffective()` split, so it is
  **not a bug** — but the interaction (a permanent-pref change mid-staging
  silently moves `effective` while the user sees the override) is subtle
  and undocumented at the listener site.
- **Why it matters:** Behaviourally correct but a future reader could
  mistake the listener-fires-during-staging path for a clobber. A one-line
  comment at `:907` noting "override (if staging) still wins for display
  via resolveEffectiveLanguage(); this only moves the permanent
  `effective` axis" would prevent a wrong "fix".
- **Suggested fix scope:** small (one comment).
- **Suggested fix:** documentation only — not a behavioural change.

### AUDIT-LOGIC-B3-3

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:380-397` (`onServiceConnected`)
- **Description:** The R-3 re-push in `onServiceConnected` is guarded by
  `dictateKeyboardView != null && imeViewBackend == null` for the
  *render-attach* but `pushPermanentLanguageToOrchestrator()` is called
  **unconditionally** afterwards. This is correct (the re-push must run
  regardless of view state — the binder is what was missing, not the
  view), and the dispatch is internally guarded by `pipelineBinder != null`
  (now true) and a `try/catch`. No defect. The only observation: if
  `onServiceConnected` fires but the view tree is not yet inflated
  (`promptsAdapter == null`, `mainButtonsController == null`), the re-push
  still dispatches `RefreshFromPref` (good — state is updated) but
  `refreshLanguageChip()` early-returns on `promptsAdapter == null` and the
  `mainButtonsController != null` guard skips the label refresh. The next
  `onCreateInputView` re-runs `pushPermanentLanguageToOrchestrator()` (line
  899) so the chip/label do get refreshed once the view exists. The
  state/UI two-phase reconciliation is sound; flagged only because the
  ordering relies on `onCreateInputView` always re-pushing — which it does
  (`:899`), and which is correctly documented at `:892-899`.
- **Why it matters:** Defensive completeness — confirms no missed UI
  refresh; no action needed beyond noting the dependency is satisfied.
- **Suggested fix scope:** small (none required — verification finding).
- **Suggested fix:** none — documented as a clean path for the consolidator.

## Detailed reasoning (the load-bearing traces)

### R-3 — boot-before-bind + onServiceConnected re-push race (verdict: CORRECT)

1. **`LanguageResolver` statelessness — verified, no retained field.**
   The whole `object LanguageResolver` body: one `private const val TAG`
   and pure functions. `effectiveLanguage`/`curatedLanguages`/`setLanguage`/
   `setCuratedLanguages`/`activeCodeOrNull` each call
   `VersionedPrefs.load(prefs, …)` / `prefs.get(...)` on every invocation.
   No `var`, no cache, no `lastEffective`. The legacy
   `LanguageController.lastEffective` cross-instance-staleness bug (Settings
   instance vs IME instance not invalidating each other) is **structurally
   eliminated** — every read re-reads the prefs file, the single SoT. The
   C8 report's R-3 claim is accurate.

2. **Boot race trace.** `onCreateInputView` (`:899`) calls
   `pushPermanentLanguageToOrchestrator()`. `bindService` is async; in the
   common race the binder is still null → the `pipelineBinder != null`
   guard (`:1642`) skips the `RefreshFromPref` dispatch →
   `state.language.effective` stays the `"system"` boot sentinel (the
   `DictateUiState`/`LanguageModule.initialState` default). UI still
   refreshes from the resolver directly (chip/label re-resolve via
   `resolveEffectiveLanguage()`), so the user sees the right language even
   pre-bind — only `state.language.effective` (the F-15 RenderBackend read)
   lags. `onServiceConnected` (`:397`) re-calls
   `pushPermanentLanguageToOrchestrator()`; binder now non-null → dispatch
   fires → reducer writes `effective`. Closed.

3. **Edge — service rebinds (onServiceConnected fires again).** The re-push
   is idempotent: `pushPermanentLanguageToOrchestrator()` resolves the
   *current* permanent code fresh from prefs and dispatches
   `RefreshFromPref(code)`; the reducer arm
   `if (action.effective != state.effective) … else null` reduces a
   no-change refresh to `null` (no store emission). Double rebind →
   double resolve → both no-ops if unchanged. Correct.

4. **Edge — language pref changes WHILE unbound (Settings) then IME
   binds.** Two sub-cases, both correct: (a) Settings writes
   `input_languages`/`pos` while IME unbound → no listener race because the
   resolver has no cache; when `onServiceConnected` later fires,
   `pushPermanentLanguageToOrchestrator()` resolves the **latest** prefs
   value (fresh read) and dispatches it. (b) If the IME process also has a
   live `inputLanguagesListener` registered (`:913`), a same-process write
   fires it → re-push; but the listener is registered in
   `onCreateInputView`, and the unbound-then-bind path is covered by the
   `onServiceConnected` re-push regardless. The fresh-read property makes
   "latest pref wins" hold in every interleaving.

5. **Edge — RefreshFromPref idempotence on no-change.** Reducer:
   `is RefreshFromPref -> if (action.effective != state.effective)
   TransitionResult(state.copy(effective=…)) else null`. Returns `null`
   (not a same-state `TransitionResult`) → no spurious store emission.
   Verified + tested (`LanguageModuleTest:69`).

6. **`computeEffective`/`resolveEffectiveLanguage` fidelity vs the deleted
   controller (R-1).** Legacy `LanguageController.computeEffective()`:
   `if state is ReprocessStaging && override not blank → override; else
   readPermanent()`, where `readPermanent()` = `langs.isEmpty() ? "en" :
   langs[pos.coerceIn(0, size-1)]`. New IME `resolveEffectiveLanguage()`
   (`:1611`): identical structure — staging override (trim-non-empty)
   first, else `LanguageResolver.effectiveLanguage(sp)`, and
   `LanguageResolver.effectiveLanguage` is byte-for-byte the legacy
   `readPermanent()` (`isEmpty → "en"`, same `coerceIn`). The override
   carrier is still the legacy `PipelineUiState.ReprocessStaging
   .selectedLanguage` (read via `uiController.getState()`), and
   `PipelineUiStateReader.kt`/`KeyboardUiController.kt` have **zero
   non-doc changes** in the B3 diff (verified) — the carrier is fully
   intact. `captureFreshConfigSnapshot` (`:2497`) and
   `handleReprocessSend` (`:3465`) both source language from this same
   resolution/legacy-carrier. R-1 transcription-config fidelity:
   **preserved** — no silent wrong-language path introduced by C8.

### Override-vs-rebind clobber (verdict: CANNOT HAPPEN)

`LanguageState(effective: String, override: String? = null)`. Reducer:
- `SetOverride(code)` → `state.copy(override = action.code)` (effective
  untouched) — verified `:76-82`, tested `:33-50`.
- `RefreshFromPref(effective)` → `state.copy(effective = action.effective)`
  (override untouched) — verified `:90-96`, tested `:78` ("RefreshFromPref
  does not clear an active override": `LanguageState(effective="en",
  override="fr")` + `RefreshFromPref("de")` → `effective="de",
  override="fr"`).

The picker-override path (`setLanguageFromPicker` during ReprocessStaging,
`:1800-1812`) dispatches `SetOverride(code)`; a subsequent
`RefreshFromPref` from `onServiceConnected` re-push or
`inputLanguagesListener` writes only `effective`. The override is not
clobbered. No interleaving of these two actions can corrupt the other's
field — they are disjoint `copy` targets. Verdict: structurally safe +
regression-locked.

### C8 latent F-15-bug-fix claim (verdict: LOGICALLY CORRECT + COMPLETE)

- Pre-C8 (`git show d39891a`): `Action.LanguageAction.RefreshFromPref` was
  `data object RefreshFromPref` (no payload); the reducer arm was
  `Action.LanguageAction.RefreshFromPref -> null` (pure no-op); the IME
  dispatched `RefreshFromPref.INSTANCE` (`:915`, no code). Therefore the
  **only** writers of `LanguageState.effective` pre-C8 were the two boot
  defaults (`DictateUiState` `language = LanguageState(effective="system")`
  and `LanguageModule.initialState() = LanguageState(effective="system")`).
  `effective` could never leave `"system"`. The F-15 resolver
  `resolveRecordButtonText` → `strings.dictateButtonText(
  state.language.effective)` therefore *always* rendered the `"system"`
  label. Claim accurate.
- Post-C8: `RefreshFromPref(effective)` carries the resolved code; reducer
  writes it idempotently. **Completeness check:** `grep` of every
  `effective =` / `copy(effective` writer in
  `app/src/main/java/net/devemperor/dictate/` returns exactly three sites —
  the two `"system"` boot defaults and the single reducer
  `state.copy(effective = action.effective)`. **No other code writes
  `"system"`** (or any value) into `effective` after boot. The fix is
  complete and has no second writer that could re-strand it.

### C9 R-5 — audioFile field removal (verdict: GREEN, confirmed)

- `RecordingState.audioFileOrNull` (`DictateUiState.kt:224`) is an
  exhaustive `when` over the sealed `RecordingState`:
  `Preparing/Active/Paused → audioFile`, `Idle → null`. All three non-idle
  variants carry a non-null `audioFile: File` (verified in the data-class
  decls + `DictateUiStateTest:120-176`). The only variant returning `null`
  is `Idle` — i.e. exactly "no recording in flight". No site that
  previously read a non-null field now silently gets `null` for an
  *in-flight* recording.
- The behaviour-bearing re-source (`stopRecording` → `:2434-2441`) is
  triple-guarded *before* the read: `:2393` `pipelineBinder == null`
  early-return; `:2422` `!isEffectiveRecordingActiveOrPaused()`
  early-return (reads binder state when bound — `:2247-2256`); `:2436`
  explicit `recordingAudioFile == null` defensive bail. The "recording
  active but service unbound" case is impossible at the read point: the
  read is `pipelineBinder.getState()…` which is only reached after the
  `:2393` non-null check. If the binder dropped between guards, the
  `:2436` null-bail catches it (recording preserved, nothing destructive
  ran — `captureFreshConfigSnapshot`/`primePipelineUiForNewPath`/
  `newPathRecordingSessionId=null` are all *after* the bail). Sound.
- **resend** (`handleReprocessSend:3473`) sources audio from
  `session.getAudioFilePath()` (Room DB) — never the field. **legacy
  migration / resend-visibility** source from `new File(getCacheDir(),
  Pref.LastFileName)` — never the field. Removing the field cannot break
  either (the field was structurally uninvolved). The C9 report's per-site
  table is accurate; `grep` confirms no `this.audioFile` / `private File
  audioFile` survives — only method-locals (`:2350 File audioFile;`).
- `onAudioPersisted(File audioFile, String sessionId)` (`:2817`) uses its
  **parameter** (`audioFile.getAbsolutePath()` `:2822`) exclusively; never
  referenced the field; the parameter is supplied by the caller at
  persist-time (the correct instant). No path expects the old field value
  at a different time than the param provides. Confirmed.

### Cross-cutting — DictateApplication singleton removal

`DictateApplication.java` diff: the process-global
`settingsScopeLanguageController` field, `getOrCreateLanguageController()`,
and the `NO_OP_PIPELINE_READER` are fully removed; replaced with a comment.
`grep -rn "getOrCreateLanguageController|settingsScopeLanguageController"
app/src/` → **zero** (no dangling consumer). The only consumer was
`PreferencesFragment` (migrated to `LanguageResolver.INSTANCE` static
calls, diff verified — reads/writes the same prefs keys, fresh per call,
no cache). Nothing else depended on the Application-singleton's lifecycle.
The removed no-op `PipelineUiStateReader` was inert by definition (always
`Idle`, callbacks dropped) — removing it removes no behaviour. Clean.

### Cross-cutting — KDoc-scrub did not remove behavioural content

Diff of all 9 KDoc-only-scrub files
(`PipelineUiStateReader.kt`, `KeyboardUiController.kt`,
`InputLanguagesPlugin.kt`, `InputLanguagesLegacyMigration.kt`,
`VersionedPrefs.kt`, `ImePipelineConfigResolver.kt`,
`DictatePipelineService.kt`, `PipelineRunnerSubsystemAdapter.kt`,
`TextResolvers.kt`): every changed line is a comment/KDoc token rename
(`LanguageController` → `LanguageResolver`) or a closed-deviation note.
**Zero non-doc lines changed** in `PipelineUiStateReader.kt` /
`KeyboardUiController.kt` (verified by diff filter). No load-bearing
`@see` / contract note was deleted — the `PipelineUiStateReader`
interface's `updateReprocessLanguage` contract (the legacy override
carrier `resolveEffectiveLanguage()` depends on) is intact in both code
and KDoc.

### C8/C9 documented deviations re-scrutinised for logic holes

- **C8 Dev-1** (`RefreshFromPref` `data object` → `data class(effective)`):
  logically necessary (see F-15 trace) and complete; the placeholder test
  call-sites were correctly updated to `RefreshFromPref("en")` with no
  semantic assertion affected. No hole.
- **C8 Dev-2** (`onServiceConnected` re-push): correct and idempotent
  (R-3 trace above). No hole.
- **C9 Dev-1** (`RecordingState.audioFileOrNull` accessor): exhaustive,
  identity-preserving (`assertSame`-tested), additive — no behaviour
  change to existing callers. No hole.

## Coverage

- Files audited: all 14 listed above (full B3 diff `d39891a..HEAD`
  filtered to C8-C1 + C9-C2 logic surface; C10-C3's lone additive KDoc
  has no logic surface and was confirmed non-behavioural).
- Files skipped (with reason): `dictate-cutover-completion.chunks.json`,
  `*.state.md`, `reports/B3-theme-c-legacy-retire.md`,
  `research/render-path-cutover.md` (planning/doc artefacts, no code
  logic); `MultiCallbackForwardingTest.kt`, `DictateOrchestratorTest.kt`,
  `ModuleServicesTest.kt`, `VersionedPluginRegistryTest.kt`,
  `LanguageResolverTest.kt`, `FakePipelineUiStateReader.kt` (test files —
  AUDIT-TEST topic owns test-quality/coverage; logic-spot-checked
  `LanguageModuleTest` + `DictateUiStateTest` for the two load-bearing
  regression locks, both present and correct).
- Knowledge-skill checkpoints applied: exhaustive sealed-`when` /
  discriminated-union completeness (knowledge-typescript, transferred to
  Kotlin) on `RecordingState.audioFileOrNull` and `LanguageModule.reduce`
  — both exhaustive; null-safety on the audioFile re-source path — guarded.

## Out-of-scope observations

- The C10-C3 architecture-conflict (C10-IMPL-2: render-path cutover block
  does not exist) is correctly flagged in the block-report and is the
  upstream cause of AUDIT-LOGIC-B3-1's dormancy. Not re-raised here as a
  B3 logic defect — it is a known escalated missing-scope issue, not a
  defect in the C8/C9 code that *was* shipped. The consolidator should
  treat B3-1 as a pointer-into-that-escalation, not a new finding.
- No CONVENTION / PLAN-AND-API / TEST findings folded in — those topics
  run in parallel.
