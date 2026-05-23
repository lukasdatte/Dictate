# Audit Report: convention (Block 2, scope: full-block)

**Agent-ID:** B2-AUDIT-CONVENTION
**Date:** 2026-05-15
**Knowledge skills used:** knowledge-reference (loaded; not applicable — it documents TypeScript plugin-system / versioned-envelope patterns, no Kotlin/Android content). Primary grounding: project `CLAUDE.md` + the block-prompt Quality-Gate list (K-1, K-4, F-5, NOTIF_ID-SoT, ADR-0001/0002, prefs-via-DictatePrefs, Kotlin-new/Java-legacy, comment-noise).
**Files inspected:** 18
- `core/PipelineRunnerSubsystemAdapter.kt` (incl. `PipelineConfigResolver` / `DefaultPipelineConfigResolver` / `DelegatingPipelineConfigResolver`)
- `core/PipelineNotificationCoordinator.kt`
- `core/PipelineActionRouter.kt`
- `core/ImePipelineConfigResolver.kt`
- `core/BluetoothScoSubsystemAdapter.kt` (sibling baseline)
- `core/AudioFocusSubsystemAdapter.kt` (sibling baseline)
- `state/modules/AudioModule.kt`
- `state/modules/RecordingModule.kt`
- `state/Action.kt`
- `state/DictateUiState.kt`
- `state/ModuleServices.kt`
- `state/PipelineServiceStubSubsystems.kt`
- `core/DictateInputMethodService.java`
- `core/DictatePipelineService.kt` (NOTIF_ID references)
- `res/values/strings.xml` + `res/values-de/strings.xml` (locale parity)
- `test/.../state/AudioModuleTest.kt`, `test/.../state/RecordingModuleTest.kt`, `test/.../core/ImePipelineConfigResolverTest.kt` (K-1/K-4 spot-checks)

## Summary

- Critical: 0
- Important: 1
- Nice-to-have: 1

## Findings

### AUDIT-CONVENTION-B2-1

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/PipelineActionRouter.kt` (whole file — at least one embedded `\x00`)
- **Description:** `PipelineActionRouter.kt` is not a clean UTF-8 text file. It contains at least one **NUL byte** (`grep -aPc "\x00"` → 1). Consequences observed:
  - `git diff 17085ca..HEAD` reports it as `Bin 0 -> 7082 bytes` and `git diff --numstat` shows `-	-` (binary) — the file's diff is invisible to reviewers, the block-validate diff-scope tooling, and PR review.
  - `file(1)` classifies it as `data`, not `Kotlin/text`.
  - Plain `grep` skips it ("binary file matches"); only `grep -a` sees the content.

  The *source content itself is correct* (valid Kotlin, KDoc header with module purpose + `@param` + `@see` anchors incl. the `@see docs/plans/...§7.5` plan anchor — fully consistent with the sibling `core/*Adapter.kt` convention). The defect is purely file-hygiene: a stray NUL must not be committed. The other three new core classes (`PipelineRunnerSubsystemAdapter.kt`, `PipelineNotificationCoordinator.kt`, `ImePipelineConfigResolver.kt`) are clean text with no NUL/CRLF.
- **Why it matters:** A binary-flagged source file silently defeats `git diff`-based review and every diff-scoped automated gate in this workflow (block-validate diff-scope, AUDIT-TEST cross-chunk regression, PR review). A future editor using a NUL-stripping tool will also produce a spurious whole-file diff. This is a convention/file-layout violation (the project's source files are UTF-8 text) with real serviceability impact, independent of the (correct) code.
- **Suggested fix scope:** small (one-file, mechanical)
- **Suggested fix:** Strip the NUL byte(s) and rewrite the file as clean UTF-8 (e.g. `tr -d '\000'` into a temp file then replace, or re-save via the editor with the identical content). Verify post-fix: `file …PipelineActionRouter.kt` → text, `grep -aPc "\x00"` → 0, and `git diff` renders it as a normal text diff. No code change — content is byte-identical minus the NUL.
- **Status:** delegated-to-orchestrator (external-agent finding — repair-sub-phase, not inline; AGENT-CONTEXT D7 "issues found by external agents → never inline")
- **Routing:** repair-sub-phase, 🟢-class (mechanical, no research). Single-file, no API/behaviour change.

### AUDIT-CONVENTION-B2-2

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/PipelineServiceStubSubsystems.kt:14` (KDoc top-of-file)
- **Description:** Minor doc-naming drift, not a code issue. The refreshed top-of-file KDoc says *"after the C8 subsystem-adapter migration and the B1/B2 cutover chunks, this file retains **no production-route stubs**"* and then lists `notificationCoordinator` and `pipelineRunner` as **`@Deprecated` test-only**. That is internally consistent and matches the established `sessionRepo`/`audioFileFactory` deprecation-discipline convention (verified — the `@Deprecated(..., DeprecationLevel.WARNING)` annotation + "kept for test-only compile-compat" wording mirrors the sibling stubs exactly, so the *pattern* is correctly followed). The only nit: the prose phrase "retains no production-route stubs" reads as slightly contradictory next to two retained (deprecated) stub vals on first read; the established convention elsewhere in this file phrases it as "demoted to test-only" rather than "retains no … stubs". Pure wording polish for the next reader — no behavioural or structural impact.
- **Why it matters:** Marginal. Documentation-consistency only; the deprecation mechanism and the per-val KDoc are correct and convention-aligned.
- **Suggested fix scope:** small (one-line doc tweak) — or accept as-is.
- **Suggested fix:** Optionally reword the header sentence to "retains only **deprecated test-only** stubs (no production-route stub remains)" to match the per-val "demoted to test-only" phrasing. Non-blocking.
- **Status:** delegated-to-orchestrator (cosmetic; candidate for `postponed` per D15)
- **Routing:** postpone or fold into any 🟢 doc wave for this file. Not worth a dedicated repair.

## Convention checkpoints applied (all PASS unless noted above)

| Checkpoint | Verdict |
|---|---|
| New code in Kotlin; legacy `DictateInputMethodService` stays Java (not converted) | PASS — file is still `.java`, no `.kt` twin; new IME methods are Java with consistent camelCase verb names (`captureFreshConfigSnapshot`, `transcribeImportedAudioFileViaOrchestrator`, `isEffectiveRecordingIdle`, …) |
| Prefs only via `DictatePrefs` sealed class — no raw string keys | PASS — IME uses `DictatePrefsKt.get(sp, Pref.X.INSTANCE)` throughout the diff (`Pref.LastFileName`, `Pref.ResendButton`, `Pref.AutoEnter`, `Pref.AudioFocus`, `Pref.UseBluetoothMic`, `Pref.TranscriptionAudioFile`); zero `getSharedPreferences`/`.getBoolean("…")`/raw-key access in the 4 new core classes or modules. AudioModule reads `state.audioFocusEnabledPref` (PrefMirror-bound to `Pref.AudioFocus`) in the pure reducer — correct, no SharedPreferences in `state/` |
| 4 new `core` classes match the `BluetoothScoSubsystemAdapter`/`AudioFocusSubsystemAdapter` sibling pattern (KDoc header, `@param`, `@see` incl. plan anchor, provider-lambda seam over hard service ref) | PASS (content) — all four carry a purpose-first KDoc header, `@param` docs, `net.devemperor…` + `docs/plans/…` `@see` anchors; the `(Action)->Unit` / `()->T` provider-lambda seam mirrors the sibling adapters' constructor-injected interface style. (File-hygiene exception: B2-1, ActionRouter NUL byte — content is conformant, the byte is not) |
| ADR-0001 single-dispatch / pure-reducer (actions are pure data, no IME-view runtime state) | PASS — `ScoRouteResolved`/`RecordingStarted`/`RecordingEnded` carry only primitives; IME-runtime config is snapshotted out-of-band via `ImePipelineConfigResolver` (explicitly to keep it off the action payload, ADR-0001) |
| ADR-0002 cascade Mode-1/Mode-2 only (no Mode-3) | PASS — AudioModule `onCrossModuleStateChange` observes the RecordingState FSM and **cascades an Action** (Mode-2); the AudioModule reducer turns it into its **own SideEffect** (Mode-1). No direct cross-module side-effect emission (no Mode-3). The SCO-resolution arm likewise cascades `ScoRouteResolved` rather than reaching into RecordingModule |
| K-1 handwritten fakes only — no Mockito/MockK | PASS — `grep` for real `import org.mockito` / `import io.mockk` / `Mockito.` / `mockk(` across `test/`+`androidTest/`: zero. Every "Mockito" hit is a KDoc sentence *asserting its absence* ("…handwritten fake, no Mockito"). Module tests use handwritten fakes (`FakeModuleServices`, `FakeAudioFocusGate`, recording `PipelineRunner` spy) |
| K-4 Robolectric = justified opt-out only; no Robolectric in pure-reducer tests | PASS — `AudioModuleTest.kt` and `RecordingModuleTest.kt` (pure reducers) contain **no** `@RunWith(RobolectricTestRunner)`/`@Config`. The B2 core tests that do use Robolectric (`PipelineRunnerSubsystemAdapterTest`, `ImeRecordingDriveCutoverTest`, `DictatePipelineServiceRecordingDriveTest`, `DictateCutoverE2ETest`, `PipelineNotificationCoordinatorTest`) each carry an explicit `K-4`/"justified opt-out" KDoc tied to Service/IME/notification wiring — justified per the prompt. `ImePipelineConfigResolverTest` is plain JUnit (its KDoc only *names* Robolectric to state it is deliberately NOT used) |
| KDoc on new public APIs | PASS — all new public types/members documented: `ScoRouteResolved`, `RecordingStarted`, `RecordingEnded` (Action.kt), `NotificationStatus.Paused` (ModuleServices.kt), `Preparing.awaitingSco`/`Preparing.target` (DictateUiState.kt), `RecordingModule.Effect.UpdateNotification`/`DismissNotification`, all four new core classes + the `PipelineConfigResolver` interface |
| Comment-noise anti-pattern; W1 stale-dormant-comment class fix for `AudioModule.kt` | PASS — the stale Phase-B-S-4 KDoc ("AudioFocus is requested as part of `RecordingModule.Effect.AllocateMediaRecorder` — the subsystem adapter takes care of it") was **correctly replaced**: the new KDoc describes the real observer→cascade→effect path AND explicitly calls out the old claim as a "stale dormant-layer comment" that was provably false (RecordingHardwareAdapter only sets source + prepare()). No NEW stale comments introduced. Inline comments are substantive (explain re-entrancy under `Dispatchers.Main.immediate`, idempotency rationale, legacy-line parity) — they capture the non-derivable "why", not restate code |
| Notification string naming + values/ ↔ values-de/ parity (F-5) | PASS — all 14 new string names added in this block exist in **both** `values/` and `values-de/` (`dictate_action_pause/stop/send/resume/cancel/insert/discard`, `dictate_notif_recording_active/paused/processing/ready_to_insert/overlay_permission_required`, `dictate_service_not_ready`, `dictate_storage_full`). The 2 extra `values-de/` names (`overlay_record_cd`, `overlay_send`) already exist in `values/` (pre-existing, parity intact). Naming follows the established `dictate_action_*` / `dictate_notif_*` prefix convention |
| NOTIF_ID single-source-of-truth | PASS — exactly one `const val NOTIF_ID = 0xD1C7A7E` in `PipelineNotificationCoordinator.companion`; `DictatePipelineService` references `PipelineNotificationCoordinator.NOTIF_ID` for `startForeground`/`cancel`; the old `DictatePipelineService.companion.NOTIF_ID` was removed (commented as removed, Spec 1 §10). Coordinator `notify`/`cancel` and Service `startForeground` target the same id — no duplicate/orphan drift |

## Coverage

- **Files audited:** all 4 new production Kotlin core classes; both modified modules (AudioModule, RecordingModule); Action.kt, DictateUiState.kt, ModuleServices.kt, PipelineServiceStubSubsystems.kt; DictateInputMethodService.java (convention spot-check: stays Java, no raw prefs, no direct AI SDK); DictatePipelineService.kt (NOTIF_ID refs only); both strings.xml locale files; AudioModuleTest / RecordingModuleTest / ImePipelineConfigResolverTest (K-1/K-4 spot-checks); BluetoothScoSubsystemAdapter.kt + AudioFocusSubsystemAdapter.kt (sibling baseline).
- **Files skipped (with reason):** Other B2 test files beyond K-1/K-4 spot-checks (test-quality + coverage is the AUDIT-TEST topic, out of convention scope). PipelineActionRouter.kt content was read in full despite its binary flag (via the Read tool, which tolerates the NUL) — content audited, the NUL itself is finding B2-1.
- **Knowledge-skill checkpoints applied:** knowledge-reference loaded but N/A (TypeScript-only patterns). Grounding was project `CLAUDE.md` (Kotlin-new/Java-legacy, prefs-via-DictatePrefs, AI-via-orchestrator) + the prompt's explicit Quality-Gate list (K-1, K-4, F-5, NOTIF_ID-SoT, ADR-0001/0002, comment-noise, sibling-adapter pattern).

## Out-of-scope observations (for the consolidator)

- (logic) The `recordingStarted` engagement-edge detection in `AudioModule.onCrossModuleStateChange` (lines 195-202) has a thorough rationale comment about `Dispatchers.Main.immediate` re-entrancy collapsing `Idle→Preparing→Active` into an observed `Idle→Active`. This is a convention-clean comment, but the underlying edge-detection correctness (does `RecordingEnded` ever double-fire on `Active→Paused→Idle`? is `ScoRouteResolved` truly idempotent under a late duplicate SCO broadcast given the `awaitingSco` guard?) is a logic concern — flagging for AUDIT-LOGIC, not actioned here.
- (plan-and-api) `PipelineActionRouter.dispatch` maps `ACTION_INSERT`/`ACTION_DISMISS` to `ConfirmInsertion`/`DismissResult` requiring `EXTRA_SESSION_ID`, but `PipelineNotificationCoordinator.build` never adds an Insert/Discard action button (no `NotificationStatus` arm emits them). May be intentional (result-stage notification is a later block) — convention-clean, flagging for AUDIT-PLAN-AND-API to confirm it is not a half-wired API.
