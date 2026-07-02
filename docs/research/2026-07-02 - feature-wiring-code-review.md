# Whole-App Feature, Wiring & Code Review — Findings Catalog

---
date: 2026-07-02
author: Lukas + Claude (multi-agent review session)
type: Research
status: Research
context: Full-app review of Dictate for feature gaps, wiring gaps, and bugs — 96 findings (38 adversarially confirmed), seeding six follow-up specs.
related-plan: n/a (plan-free research)
related-adrs: ADR-0006 (info-bar consolidation), ADR-0007/ADR-0008 (recording stack)
---

This document is the consolidated output of a 57-agent review of the Dictate Android IME (12 subsystem reviewers, 5 seed root-cause agents, 1 consolidator, 39 adversarial verifiers). It catalogs **feature gaps** (half-built features), **wiring gaps** (components that exist but are not connected), **bugs** (behavioral defects with code evidence), and **inconsistencies** (divergent code paths for the same job). It is a findings catalog, not a fix plan — six large topics are broken out into sibling spec files (§5); everything else is a point fix that can be picked up directly from its catalog entry.

## Table of Contents

- [§1 Vision and Motivation](#1-vision-and-motivation)
- [§2 Findings + Conclusions](#2-findings--conclusions)
- [§3 Finding Catalog](#3-finding-catalog)
  - [§3.1 Critical](#31-critical-1-finding)
  - [§3.2 High](#32-high-12-findings)
  - [§3.3 Medium](#33-medium-35-findings)
  - [§3.4 Low](#34-low-48-findings)
- [§4 Refuted Findings](#4-refuted-findings)
- [§5 Follow-up Specs](#5-follow-up-specs)
- [§6 Information Gaps](#6-information-gaps)
- [§7 Change History](#7-change-history)
- [§8 References](#8-references)

## 1. Vision and Motivation

### 1.1 Why this review exists

Dictate has gone through a series of large migrations in quick succession (state-machine cutover, InsertionService unification, rolling-segment recording stack, history redesign, overlay-widget integration). Each migration cut over its primary path but the review hypothesis was that **secondary paths and cross-subsystem seams were left behind** — the classic post-migration failure mode. Several user-observed defects supported that hypothesis:

1. The delete button does not delete an active text selection — it keeps deleting single characters.
2. Deleting an emoji removes only single UTF-16 code units, not the whole emoji.
3. The history screen always shows a duration of ~16 seconds regardless of actual recording length.
4. The recently redesigned history needed a leftover-gap audit.
5. Feature request: the overlay widget should be transparent so content behind it stays visible.

### 1.2 What this review delivers

A verified, prioritized catalog of every gap found, precise enough that each entry can be implemented without re-research: every finding carries file:line evidence that a reviewer actually read, a suggested fix, and (for bugs/wiring gaps of medium+ severity) an adversarial verification verdict.

### 1.3 Methodology

- **Sweep:** 12 subsystem agents (core-ime, recording-audio, insertion-keyboard, state-machine, render-layout, history, widget, settings-prefs, ai-layer, rewording-usage-onboarding, database, manifest-resources) + 5 seed root-cause agents for the user-reported defects. 122 raw findings.
- **Consolidate:** one consolidator merged 25 cross-agent duplicates → 97 findings.
- **Verify:** every bug/wiring-gap/inconsistency of medium+ severity was handed to an independent adversarial agent instructed to *refute* it by reading the code ("default to refuted when ambiguous"). 39 verified → 38 confirmed, 1 refuted (§4). Feature gaps and low-severity findings passed through unverified (58) — they are marked as such in the catalog.

## 2. Findings + Conclusions

### 2.1 Numbers

| | Critical | High | Medium | Low | Σ |
|---|---:|---:|---:|---:|---:|
| Confirmed (adversarially verified) | 1 | 11 | 25 | 1 | **38** |
| Unverified pass-through | 0 | 1 | 10 | 47 | **58** |
| Refuted | | | | | 1 |

### 2.2 The one critical finding

> [!CAUTION]
> **F-000 — IME-side recording start still allocates the legacy `rec_*` initial file.** The Block-A4 initial-file cutover migrated only the catalog record path. `startRecording()` in `DictateInputMethodService` (QWERTZ record button, instant-prompt chip) still allocates via the legacy `AudioFileFactory` — the resulting `rec_*` file is invisible to the `sess_*`-prefixed multi-segment muxer. Consequence: long recordings on these surfaces lose their first chunk; short recordings upload a 0-byte pre-armed segment while the real audio is deleted. **This is silent, unrecoverable audio loss on a primary record surface** and is the same defect class the A4 cutover fixed for the main button. Fix is small (allocate via `AudioFileRepository.allocateFirst(sessionId)` like the resolver path); see F-000 in §3.1.

### 2.3 Theme clusters — what the findings say together

The 96 findings are not 96 independent accidents; they cluster into eight themes:

1. **Incomplete-cutover residue (the dominant theme).** The legacy allocator on the IME start path (F-000), the dead `RecordingStateController` read in `onFinishInputView` (F-004), the never-called `InfoBarController.onStateChanged` (F-039) beside a live legacy error channel (F-040), `ModuleServices.inputConnectionProvider` (F-033), dead action surfaces (F-032/034/037/038/041), and keep-screen-awake never engaging on the new path (F-008). Each migration left one or more unmigrated consumers. **Conclusion: cutovers in this codebase need a zero-grep exit criterion per legacy symbol, not per primary path.**
2. **Rolling-segment side effects.** The eager pre-arm fix (commit `5210df2`) guarantees a 0-byte trailing segment after every healthy recording; three consumers treat that file as meaningful: partial-recovery flags every recording as interrupted (F-012), auto-continuation always aborts reading codec params from it (F-014), and history duration sums only the first segment (F-047 — the user-reported "always 16 seconds"). **Conclusion: the repository needs a single "significant segments" read API that filters trailing 0-byte artifacts, instead of three consumers interpreting raw segment lists.**
3. **Text-buffer ops are not grapheme-aware and not selection-aware.** Backspace deletes one UTF-16 code unit and ignores selections (F-018, user-reported), slow-output commits lone surrogates (F-020), space-swipe cursor moves by code units and destroys selections (F-021), swipe-select miscomputes offsets in large editors (F-023). **Conclusion: `InsertionService` should own grapheme/selection semantics once (a `deleteGrapheme` ControlOp and selection-aware policy), instead of each caller choosing a primitive.**
4. **Reprocess/staging path diverges from the pipeline path.** The staged queue is never submitted (F-001), auto-apply queues are not prepared on the catalog path (F-003), the queue editor UI was never shipped (F-110), duration label hardcoded to 0:00 (F-042).
5. **History redesign left operational gaps.** Orphaned audio on delete (F-105), progress bar never hidden (F-049), persisted error details never displayed (F-053), Activity-scoped executors killed by rotation (F-055), regenerate bypassing `PromptService`/`AutoFormattingService` (F-108/F-109), playback on the legacy single-file column (F-113), no processing guard on delete (F-114).
6. **Bluetooth and notifications are quietly broken.** The service-side SCO receiver is never registered so Bluetooth-mic recording always times out to the built-in mic (F-013); `POST_NOTIFICATIONS` is never requested at runtime so the FGS notification is invisible on Android 13+ (F-092).
7. **Provider/AI-layer parameter drift.** Dead NETWORK_ERROR mapping (F-067), stale model-id filtering (F-069), half-built temperature/top-p/top-k plumbing (F-070/075), OpenRouter reasoning detection missing vendor prefixes (F-073).
8. **Widget theming/transparency.** The overlay card is opaque (F-118 — the user request), ignores the in-app theme pref (F-119), never reacts to config changes (F-120).

### 2.4 Recommended fix order

1. **F-000** (critical audio loss) — small fix, regression-tested, ship first.
2. The rolling-segment consumer trio **F-012 / F-014 / F-047** — one shared root cause, one repository-level fix; F-047 closes the user-visible "16 seconds" defect.
3. **F-018 + F-020 + F-021** — grapheme/selection semantics in `InsertionService`; closes both user-reported deletion defects.
4. **F-013** (Bluetooth SCO) and **F-092** (notification permission) — small wiring fixes with outsized UX impact.
5. **F-001/F-003** (reprocess queue correctness), then the history-redesign gap list (F-105 first — data loss on disk).
6. The six spec topics (§5) as planned work.


## 3. Finding Catalog

Grouped by severity, then category. Every entry names its verification status. IDs (`F-NNN`) are stable identifiers from the review run — safe to reference from commits and follow-up plans.

### 3.1 Critical (1 finding)

#### F-000 — QWERTZ/instant-prompt recording start still allocates legacy rec_* initial file — invisible to the multi-segment muxer (audio loss / empty transcription)

| | |
|---|---|
| **Category** | bug |
| **Severity** | critical |
| **Area** | core-ime / recording start paths |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:core-ime` |

**Files:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:3858`, `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:491`, `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt:113`, `app/src/main/java/net/devemperor/dictate/core/CacheDirAudioFileRepository.kt:103`, `app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt:919`

The Block-A4 'Initial-File-Cutover' migrated only the catalog record path (ActionResolvers.resolveRecordAction now uses audioFileRepository.allocateFirst(sessionId) → sess_{sid}_seg1.m4a). The IME's own startRecording() — reached from the QWERTZ record button (onRecordClicked at :5089 via the qwertzController lambda at :973), the instant-prompt chip (:2628), and any other IME-side start — still allocates via the legacy binder.getAudioFileFactory().allocate() (CacheDirAudioFileFactory, rec_{ts}_{uuid}.m4a naming). RecordingHardwareAdapter pre-arms rolling segments via allocateNext(sessionId) with sess_ naming from the very start (commit 5210df2). Consequences on these surfaces: (a) long recording that rolls: readForPipeline(sid)/segments(sid) only match the sess_ prefix, so the initial rec_* chunk never reaches the muxer — the exact 'only the latest audio chunk reached the AI' data loss the ActionResolvers comment documents as fixed; (b) short recording that never rolls: the eagerly pre-armed sess_{sid}_seg1 file (created 0-byte by MediaRecorder.setNextOutputFile(File)) is the ONLY sess_ segment on disk, so readForPipeline returns Complete(empty file) and persistMuxedForUpload overwrites recordings/{sid}.m4a with it — the pipeline transcribes an empty file while the real audio (rec_*) was deleted by persistNewSession's post-copy cache delete (PipelineOrchestrator:934).

<details><summary>Evidence</summary>

DictateInputMethodService.java:3858 audioFile = pipelineBinder.getAudioFileFactory().allocate(); DictatePipelineService.kt:491 audioFileFactoryImpl = CacheDirAudioFileFactory(...); ActionResolvers.kt:113-125 documents the rec_* naming as the root cause of an observed on-device data-loss bug and cuts over ONLY the resolver path; CacheDirAudioFileRepository.kt:103-112 segments() filters on the sess_ prefix; RecordingHardwareAdapter.kt:213-222 pre-arms allocateNext(sid) right after start(); PipelineOrchestrator.kt:919-921 isMultiSegmentInitial is false for rec_* names → persistFromCache branch + cache delete at :934; resolvePipelineAudio (:1164-1204) prefers repo segments over the legacy column.

</details>

**Suggested fix:** In DictateInputMethodService.startRecording(): mint the sessionId first, then allocate the initial file via pipelineBinder.getModuleServices().getAudioFileRepository().allocateFirst(sessionId) (same call the catalog resolver uses), and dispatch StartRecording with that file — deleting the legacy AudioFileFactory allocation. Add a regression test asserting the IME start path and the catalog start path produce the same sess_{sid}_seg1 naming.


### 3.2 High (12 findings)

#### F-001 — ReprocessStaging Send: user-edited prompt queue is never submitted — Effect.SubmitReprocess carries queue=emptyList and the IME's correct direct submit is dropped as a duplicate

| | |
|---|---|
| **Category** | bug |
| **Severity** | high |
| **Area** | core-ime / reprocess staging |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:core-ime` |

**Files:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:5488`, `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt:457`, `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:436`, `app/src/main/java/net/devemperor/dictate/core/JobExecutor.kt:130`, `app/src/main/java/net/devemperor/dictate/core/ImePipelineConfigResolver.kt:174`

ReprocessStaging Send loses the user's staged queue edits on both trigger paths because Effect.SubmitReprocess always carries queue=emptyList and audioFile=null (PipelineModule.kt:457-466), while the correctly-parameterised submit is suppressed. (1) IME-view catalog record button: the click fires imeSideAffordance(RECORD) first, but handleReprocessSend defers via dbExecutor+mainHandler.post; the click handler then synchronously dispatches SendStaging (LayoutCatalog.kt:436 → orchestrator.dispatch, effects run inline per DictateOrchestrator.kt:342-356). No reprocess snapshot exists yet, so ImePipelineConfigResolver falls back to DefaultPipelineConfigResolver (totalSteps=1, modelOverride=null, targetAppPackage=null) and the job registers in ActiveJobRegistry. handleReprocessSend's posted body then bails at the ActiveJobRegistry.isAnyActive() guard (DictateInputMethodService.java:5475-5477) with a spurious "job busy" toast — snapshotReprocess and the queue-carrying submit never run. (2) QWERTZ record button (onRecordClicked → handleReprocessSend directly): the posted body snapshots, then dispatch(SendStaging) at :5488 synchronously submits the empty-queue job, consuming the snapshot (ImePipelineConfigResolver.kt:180); the IME's explicit submitReprocess(sessionId, File, editableQueue, language) at :5490 hits ActiveJobRegistry.register → false (JobExecutor.kt:130-133) and PipelineRunnerSubsystemAdapter.submitReprocess (:98-106) discards the boolean, silently dropping the correct job. In both cases PipelineOrchestrator (:285-289 / :1055-1058) falls back to the live promptQueueManager.getQueuedIds() auto-apply queue instead of the staged reprocessEditableQueue (which lives only in IME mirror fields with no write-through to PromptQueueManager, DictateInputMethodService.java:2883-2896). Net effect: every reprocess send runs with the wrong prompt queue; the catalog path additionally loses modelOverride/targetAppPackage/totalSteps and shows a misleading busy toast.

<details><summary>Evidence</summary>

PipelineModule.kt:440-474 (SendStaging arm, Effect.SubmitReprocess with queue=emptyList and audioFile=null) + :561-568 runEffect → pipelineRunner.submitReprocess; DictateOrchestrator.kt:342-356 effects execute synchronously inside dispatch; DictateInputMethodService.java:5479-5494 handleReprocessSend snapshots then dispatches SendStaging then calls submitReprocess directly; JobExecutor.kt:130-133 register-fails → return false; PipelineRunnerSubsystemAdapter.kt:87-106 ignores the false return; LayoutCatalog.kt:418-437 wires the staging RECORD slot to resolveSendStagingAction only; PipelineOrchestrator.kt:285-289 empty-queue fallback to promptQueueManager.

</details>

**Suggested fix:** Make one path authoritative: either carry the editable queue/language/model in the state (ReprocessStaging payload) so Effect.SubmitReprocess is complete and delete the IME's direct submitReprocess call, or make the SendStaging reducer arm emit no SubmitReprocess and have the IME-side handleReprocessSend be the single submitter for both surfaces (routing the catalog staging-RECORD click through an imeSideAffordance branch like RECORD/RESEND). Add a regression test: staged queue with a removed prompt must produce a JobRequest whose queuedPromptIds equals the staged queue.

#### F-012 — Pre-armed rolling segment leaves a guaranteed 0-byte trailing segment file — false PartialRecovery on every recording, zero-copy path dead

| | |
|---|---|
| **Category** | bug |
| **Severity** | high |
| **Area** | recording-audio / rolling-segments |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:recording-audio` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/RecordingHardwareAdapter.kt:340`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/RecordingHardwareAdapter.kt:222`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/CacheDirAudioFileRepository.kt:103`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/CacheDirAudioFileRepository.kt:114`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt:1183`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/infobar/InfoBarSelector.kt:136`

Commit 5210df2's always-one-ahead pre-arm (RecordingHardwareAdapter.armNextSegment, called at start() and after every handover) makes the Android framework create the next segment file eagerly: MediaRecorder.setNextOutputFile(File) opens it with RandomAccessFile(file,"rw"), creating a 0-byte sess_{sid}_seg{N}.m4a at arm time. The last armed file is never rolled into before stop(), and neither stop() nor releaseRecorder() deletes it, so every production recording (repo + sessionId always wired) leaves a trailing 0-byte segment. CacheDirAudioFileRepository.segments() has no zero-length filter, so readForPipeline always sees >=2 segments: the single-segment zero-copy branch is dead, every recording is remuxed, and mergeSegments skips the unreadable 0-byte segment into ignoredIndices → PipelineAudioResult.PartialRecovery(estimatedLostSeconds=30.0). PipelineOrchestrator.resolvePipelineAudio then persists last_error_message="partial:30.0" on a fully complete recording, and InfoBarSelector renders an ERROR-style "partial recovery, ~30s audio lost" InfoBar for the session. The empty segment path is also synced into SessionEntity.audio_file_paths. Fix direction: delete (or length-filter) armed-but-unused 0-byte segments on stop, and/or filter zero-length files in segments()/mergeSegments.

<details><summary>Evidence</summary>

RecordingHardwareAdapter.kt:345-353 calls repo.allocateNext(sid) then mr.setNextOutputFile(next) (framework creates the file); armed after start() at line 222 for every session (repo + sessionId always non-null in production — DictatePipelineService.kt:382-389, RecordingModule.kt:942-948 always passes sessionId). stop() (lines 245-264) never deletes the armed-but-unused file. CacheDirAudioFileRepository.segments() (103-112) filters only by name pattern; readForPipeline (114-122) branches to mergeSegments when segs.size>1; mergeSegments (175-185) catches the per-segment failure and returns PartialRecovery with estimatedLostSeconds = ignoredIndices.size * 30.0 (line 201-206). PipelineOrchestrator.resolvePipelineAudio (1183-1203) persists "partial:${result.estimatedLostSeconds}" into last_error_message; InfoBarSelector (136-155) renders it as an ERROR InfoBar via PARTIAL_MARKER_REGEX.
[merged duplicate from sweep:core-ime: Eagerly pre-armed next rolling segment leaves a 0-byte sess_*_segN file — every non-rolled recording muxes through PartialRecovery and writes a false 'partial:30' error marker] RecordingHardwareAdapter.kt:213-222 (arm right after start) + :340-354 (allocateNext + setNextOutputFile, no cleanup of the unused armed file in stop()/releaseRecorder() :245-279); CacheDirAudioFileRepository.kt:103-112 segments() has no zero-length filter, :114-122 readForPipeline single-segment branch returns Complete without readability check, :124-207 mergeSegments counts the skipped empty segment into ignoredIndices → PartialRecovery(30s/segment); PipelineOrchestrator.kt:1183-1203 persists the 'partial:N' marker consumed by InfoBarSelector.

</details>

**Suggested fix:** Track the pre-armed File in RecordingHardwareAdapter (e.g. pendingNextSegmentFile), clear it on MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED, and delete it in stop()/releaseRecorder() when it was never rolled into (still 0 bytes). Defensively, also skip zero-length files in CacheDirAudioFileRepository.segments() (or at least in readForPipeline before the size==1 branch) so historic empty segments stop producing false PartialRecovery markers. Add a regression test: record → stop before cap → readForPipeline must return Complete with the single real segment.

#### F-014 — Auto-Continuation always aborts: codec-param read targets the last segment, which is now guaranteed unreadable — Record-tap on an interrupted recording is a silent no-op

| | |
|---|---|
| **Category** | bug |
| **Severity** | high |
| **Area** | recording-audio / crash-recovery continuation |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:recording-audio` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/RecordingContinuationLookup.kt:66`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/audio/AudioCodecReader.kt:42`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt:153`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/RecordingHardwareAdapter.kt:222`

RecordingContinuationLookup.lookup() reads codec params from existingSegments.last() and ABORTS continuation (returns null) when that read fails. After a real process death the last on-disk segment is virtually never readable: it is either the crash-truncated active segment (no moov atom) or — deterministically, since the 5210df2 pre-arm fix — the 0-byte pre-armed next segment, which AudioCodecReader.readCodecParams rejects immediately (file.length()==0L → null). So lookup() always returns null for genuine RECORDING_INTERRUPTED sessions, and the whole ADR-0008 Auto-Continuation feature ('Fortsetzen') can never fire. User-visible: the keyboard surfaces RecordingState.Interrupted ('as if briefly paused', frozen timer, Record = continue), but resolveRecordAction's Interrupted arm returns null when lookup() is null — the Record-tap does nothing, with no feedback; the user's only working affordance is the trash button. This also contradicts AudioCodecReader's own KDoc ('Callers fall back to CodecParams.DEFAULT_AAC_M4A') — the actual caller aborts instead of falling back.

<details><summary>Evidence</summary>

RecordingContinuationLookup.kt:66-76: lastSegment = existingSegments.last(); readCodecParams==null → log 'abort continuation' → return null. AudioCodecReader.kt:42-44 returns null for 0-byte files; truncated moov-less MP4s throw in setDataSource → null (65-67). RecordingHardwareAdapter.kt:213-222 + 340-371 guarantee a trailing 0-byte segment exists for every session (pre-armed via setNextOutputFile, never deleted). ActionResolvers.kt:150-159: Interrupted arm — lookup() null ⇒ resolver returns null ⇒ tap is a no-op (comment even documents 'the tap is a no-op' only for the segments-gone/stale case, not for this always-case). AudioCodecReader.kt KDoc lines 21-27 promises a DEFAULT_AAC_M4A fallback that no caller implements.

</details>

**Suggested fix:** In RecordingContinuationLookup, walk the segment list backwards to the last READABLE segment for codec params (skipping 0-byte/truncated tails), and only abort when no segment is readable; alternatively fall back to CodecParams.DEFAULT_AAC_M4A as AudioCodecReader's KDoc already promises (segments 1..N-1 were recorded with those defaults for fresh sessions anyway). Pair with the pre-armed-file cleanup from the rolling-segments finding so the 0-byte tail disappears. Regression test: session with [readable seg1, truncated seg2, empty seg3] must yield an EligibleContinuation with seg1's params.

#### F-018 — Main-keyboard backspace tap deletes one UTF-16 code unit and ignores active selection (splits emoji, inconsistent with all other backspace paths)

| | |
|---|---|
| **Category** | bug |
| **Severity** | high |
| **Area** | insertion-keyboard |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:insertion-keyboard` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/modules/KeyboardInputModule.kt:161`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:4837`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:103`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:5022`

The main keyboard's BACKSPACE button click is dispatched via the catalog (LayoutCatalog.kt:103/206/292/383/448 -> Action.KeyboardInputAction.Backspace -> KeyboardInputModule.runEffect SendBackspace -> ControlOp.Backspace), which executes ic.deleteSurroundingText(1, 0) (DictateInputMethodService.java:4838). deleteSurroundingText counts UTF-16 code units, so a single tap on an emoji (surrogate pair) deletes only the low surrogate and leaves a corrupt lone high surrogate in the host editor; multi-codepoint grapheme clusters (flags, ZWJ families, combining marks) are shredded one code unit at a time. It also does not delete an active selection: per Android semantics deleteSurroundingText deletes AROUND the selection, so tapping backspace with text selected deletes the character before the selection and leaves the selection standing. Every other backspace path is correct: QWERTZ tap (QwertzKeyboardController.handleBackspace -> deleteOneCharacter), long-press auto-repeat (onBackspaceLongClicked -> deleteOneCharacter), and the dead parity method onBackspaceClicked() all use deleteOneCharacter (DictateInputMethodService.java:5022-5054), which is selection-aware (DeleteSelection) and grapheme-aware (BreakIterator.getCharacterInstance + DeleteSurrounding(charsToDelete, 0)). So short-tap and long-press on the same physical button behave differently, and the short-tap is the broken one.

<details><summary>Evidence</summary>

Read LayoutCatalog.kt:99-104 (BACKSPACE slot actionResolver -> KeyboardInputAction.Backspace), KeyboardInputModule.kt:118-119 + 161-165 (Backspace action -> Effect.SendBackspace -> ControlOp.Backspace), DictateInputMethodService.java:4836-4841 (executeControlOp: Backspace -> deleteSurroundingText(1,0)), and DictateInputMethodService.java:5022-5054 (deleteOneCharacter: getSelectedText -> DeleteSelection; else BreakIterator preceding() -> DeleteSurrounding(n,0)). DictateInputMethodService.java:5335-5340 comment confirms the catalog action replaced the deleteOneCharacter click path ('BACKSPACE click is now the catalog Action.KeyboardInputAction.Backspace on the bound path; this body is kept for any non-catalog caller') — and onBackspaceClicked has zero callers (grep).
[merged duplicate from seed:delete-selection: Short-press delete button ignores active text selection (deletes chars before selection instead)] KeyboardInputModule.kt:161-164 (Effect.SendBackspace -> ControlOp.Backspace, no selection check); DictateInputMethodService.java:4837-4838 (ControlOp.Backspace -> ic.deleteSurroundingText(1,0), which by Android contract does not delete the selection); DictateInputMethodService.java:5026-5029 (legacy deleteOneCharacter checks getSelectedText and routes to DeleteSelection); DictateInputMethodService.java:5338-5340 (onBackspaceClicked wraps deleteOneCharacter but grep shows zero callers); ImeViewBackend.kt:460-465 (click dispatches catalog actionResolver); LayoutCatalog.kt:103,206,292,383,448 (BACKSPACE actionResolver = KeyboardInputAction.Backspace in every layout state). Test coverage only asserts the effect is emitted (KeyboardInputModuleTest.kt:43-46), not the selection semantics.
[merged duplicate from seed:delete-selection: Catalog short-press backspace splits surrogate pairs / grapheme clusters (emoji)] DictateInputMethodService.java:4837-4838 (Backspace -> deleteSurroundingText(1,0), one code unit); DictateInputMethodService.java:5038-5053 (deleteOneCharacter's BreakIterator + offsetByCodePoints cluster computation, routed as DeleteSurrounding); Insertion.kt:142-146 (Backspace documented as raw deleteSurroundingText(1,0) vs DeleteSurrounding documented as 'grapheme-aware backspace'); KeyboardInputModule.kt:161-164 (short-press effect emits the raw Backspace op).
[merged duplicate from seed:emoji-delete: Main-keyboard tap-backspace deletes one UTF-16 code unit, splitting emoji (seed root cause)] Read the full dispatch chain: LayoutCatalog.kt:103/206/292/383/448 (actionResolver = Action.KeyboardInputAction.Backspace on every layout variant); KeyboardInputModule.kt:118-119 + 161-164 (SendBackspace -> ControlOp.Backspace); Insertion.kt:142-143 (KDoc confirms 'deleteSurroundingText(1, 0)'); DictateInputMethodService.java:4837-4838 (executeControlOp does ic.deleteSurroundingText(1,0) with no grapheme scan). Contrast paths verified grapheme-aware: deleteOneCharacter() 5022-5054 (android.icu.text.BreakIterator import at line 14); QWERTZ tap backspace QwertzKeyboardController.kt:177-180 calls deleteOneCharacter; main-keyboard LONG-press repeat ImeViewBackend.kt:488-513 imeSideAffordance(BACKSPACE, true) -> onBackspaceLongClicked() 5345-5370 -> deleteOneCharacter per tick. onBackspaceClicked() grep: single hit = its own definition (no caller). So the same physical button deletes whole emoji when held but half an emoji when tapped.

</details>

**Suggested fix:** Make the single ControlOp.Backspace executor selection- and grapheme-aware: in executeControlOp, implement the Backspace arm with the deleteOneCharacter logic (selection -> commitText("",1); else BreakIterator grapheme lookback -> deleteSurroundingText(n,0)), and have deleteOneCharacter dispatch ControlOp.Backspace so there is exactly one implementation. Add a regression test with an emoji before the cursor and with an active selection.

#### F-019 — SlowOutputAnimator treats absolute per-index schedule offsets as relative inter-char delays: quadratic slowdown and auto-enter fires mid-animation

| | |
|---|---|
| **Category** | bug |
| **Severity** | high |
| **Area** | insertion-keyboard |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:insertion-keyboard` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/insertion/SlowOutputAnimator.kt:60`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:3614`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:4776`

The legacy loop (git 18a3ba8~1 commitSlowOutput) scheduled every tail character from t=0 with an ABSOLUTE offset: postDelayed(commit char i, i * perCharMs) — constant perCharMs spacing between characters. The refactored SlowOutputAnimator.scheduleFrom chains commits recursively: after char at index it posts the next one after delayForIndex.delayFor(index) ms, where the wired provider slowOutputDelayForIndex(index, speed) still returns index * perCharMs (the absolute formula). Used as a RELATIVE gap, the inter-char delay grows linearly with position, so total animation time is quadratic: a 200-char transcript at default speed 5 (20 ms/char) takes ~sum(i*20ms) ≈ 400 s instead of the legacy 4 s. Worse, scheduleAutoEnter (DictateInputMethodService.java:3637-3643) still computes the wait as lastCharDelay = slowOutputDelayForIndex(len-1, speed) — the correct ABSOLUTE end time under legacy semantics — so with auto-enter active the Enter key fires while most of the text is still animating, splitting the transcript across a newline or sending a half-typed chat message. Affects every pipeline/resend insert whenever the InstantOutput pref is disabled.

<details><summary>Evidence</summary>

SlowOutputAnimator.kt:60-71 (scheduleFrom posts next char after delayFor(index), i.e. relative chaining); DictateInputMethodService.java:3614-3616 (slowOutputDelayForIndex = index * base/(speed/5)) and 4774-4779 (same provider wired into the animator); 3611-3612 KDoc explicitly claims the shared helper keeps animator and scheduleAutoEnter in sync 'so the two cannot drift out of sync' — they have drifted in kind (relative vs absolute). Verified legacy semantics via `git show 18a3ba8~1` commitSlowOutput: `long delay = (long)(i * (20L / (speed / 5f))); mainHandler.postDelayed(...)` — all posts from t=0. SlowOutputAnimatorTest.kt only uses delayFor -> 0L and never asserts timing, so the regression is untested.

</details>

**Suggested fix:** Decide one timing model. Simplest: keep the animator's relative chaining but make the provider return the constant per-char delay (drop the `index *` factor), and change scheduleAutoEnter to add (len-1) * perCharDelay. Add a unit test asserting the delays passed to the scheduler are constant per char and that total time is linear in text length.

#### F-028 — Discard-stop and cancel paths never close the DB session row — discarded recordings resurrect as 'interrupted' after service restart

| | |
|---|---|
| **Category** | bug |
| **Severity** | high |
| **Area** | state-machine / RecordingModule + PipelineRecovery |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:state-machine` |

**Files:** `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt:677`, `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt:700`, `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt:727`, `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt:292`, `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt:206`, `app/src/main/java/net/devemperor/dictate/core/PipelineActionRouter.kt:66`, `app/src/main/java/net/devemperor/dictate/core/PipelineNotificationCoordinator.kt:184`

Effect.CreateRecordingSession inserts a status=RECORDING row at recording start (RecordingModule.kt:460/493, handler 1009-1017), and segment paths are synced into the row (MediaRecorderReady:591, SegmentRolled:754). The three non-send terminations never close that row: (a) StopRecording (Active 677-688, Paused 784-793 — dispatched by the FGS-notification [Stopp] button via PipelineNotificationCoordinator.kt:184/190 → PipelineActionRouter.kt:66) emits only hardware/UI teardown effects, keeps all audio files, leaves the row at RECORDING; (b) OnRecordLongPress (700-709, 799-807) identical; (c) CancelRecording (727-738, 822-832) deletes only state.audioFile — segment 1, since SegmentRolled keeps nextState = state — leaving rolled segments seg2..segN on disk and the row at RECORDING. Consequence for (a)/(b): PipelineRecovery.runStatusPromotion (292-307), which runs on every orchestrator construction (DictateOrchestrator.kt:178) and at device boot, promotes the row to RECORDING_INTERRUPTED (all paths exist); Phase 5 (206-228) surfaces it via SurfaceInterruptedRecording within the 24h freshness window, so the keyboard shows the explicitly-discarded recording as an unfinished one with frozen timer + continue/trash, and the next Idle record-tap (ActionResolvers.resolveRecordAction:89-103 ContinuationLookup) silently continues it — discarded audio is prepended to the next dictation. Consequence for (c): seg1 is missing, so the row is promoted to FAILED with the misleading error 'recording-interrupted-by-process-death' (appears in history as a crash), and orphan segments seg2..segN linger until that recovery pass. The correct atomic pattern already exists — Effect.DiscardAudioForSession (1055-1083: audioFileRepository.deleteAll + sessionRepo.markFailed('discarded_by_user')) — but is dispatched only from the Interrupted-state/Idle trash paths. Note: SessionManager.kt:101-103 is finalizeCancelled (not markCancelled) and does have callers, but only in PipelineOrchestrator's pipeline-stage cancel paths — none on the recording discard arms.

<details><summary>Evidence</summary>

Read RecordingModule.kt reduceInner: StopRecording arm (lines 677-688) effects = StopMediaRecorder/StopTimer/StopBorderGlow/StopAmplitudeStream/DismissNotification — no DB effect, no DeleteAudioFile; OnRecordLongPress (700-709) identical; CancelRecording (727-738) adds only DeleteAudioFile(state.audioFile) which is seg1 (SegmentRolled keeps nextState = state at 750-758, never updates audioFile). CreateRecordingSession row insert at 460/493 + PipelineSessionRepoAdapter.createRecordingSession:280 (status=RECORDING). PipelineRecovery.runStatusPromotion:292-307 promotes RECORDING→RECORDING_INTERRUPTED when paths.all{exists}; recover() Phase 5:206-228 surfaces it via SurfaceInterruptedRecording; SessionDao.findLatestUnfinishedRecording targets RECORDING_INTERRUPTED+RECORDED (SessionDao.kt:113-117). Notification [Stopp] wired: PipelineNotificationCoordinator.kt:184 addAction(ACTION_STOP) → PipelineActionRouter.kt:66 dispatch(StopRecording). grep 'markCancelled' → only the definition, no caller.
[merged duplicate from sweep:recording-audio: Discard paths (CancelRecording / StopRecording / OnRecordLongPress) never close the recovery chain: DB row stays status=RECORDING and rolled segments are not deleted] RecordingModule.kt Active-arm StopRecording (677-688: no DeleteAudioFile, no DB effect), OnRecordLongPress (700-709), CancelRecording (727-738: DeleteAudioFile(state.audioFile) only); Paused-arm equivalents at 784-793, 799-807, 822-832. Effect.DeleteAudioFile deletes a single File (handler at ~955). PipelineRecovery.runStatusPromotion (292-307): RECORDING rows with all paths present → RECORDING_INTERRUPTED (resurfaces via SurfaceInterruptedRecording); with any path missing → FAILED + error 'recording-interrupted-by-process-death'. grep shows finalizeCancelled callers only in PipelineOrchestrator.kt (340-361, 509-512) — nothing on the RecordingModule discard arms. Effect.DiscardAudioForSession (1055-1080) already implements the correct atomic delete-all+mark pattern, but only for the Interrupted trash button.

</details>

**Suggested fix:** Add a terminal DB effect to the three discard arms: StopRecording/OnRecordLongPress emit e.g. Effect.MarkSessionCancelled(state.sessionId) (repo call to SessionManager.markCancelled semantics via a new PipelineSessionRepoSubsystem.markCancelled, fail-soft) — and decide whether discard-stop should also delete the audio (or intentionally keep it and mark RECORDED for resend). CancelRecording should emit Effect.DiscardAudioForSession(state.sessionId)-style cleanup (audioFileRepository.deleteAll + markFailed/markCancelled with reason 'cancelled_by_user') instead of DeleteAudioFile(seg1 only). Add regression tests: discard-stop → recovery pass does NOT surface SurfaceInterruptedRecording.

#### F-029 — RESEND cooldown latches permanently after a long-press — button stays disabled until service restart

| | |
|---|---|
| **Category** | bug |
| **Severity** | high |
| **Area** | state-machine / ResendModule + IME wiring |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:state-machine` |

**Files:** `app/src/main/java/net/devemperor/dictate/state/modules/ResendModule.kt:86`, `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:5151`, `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:5289`, `app/src/main/java/net/devemperor/dictate/state/render/ImeViewBackend.kt:488`, `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:89`

ResendModule arms resendCooldown=true on BOTH ResendLastAudio (short press) and ResendLastAudioLong (long press). The clearing action ResendCooldownExpired is only scheduled inside onResendClicked (mainHandler.postDelayed 500ms, DictateInputMethodService.java:5151-5158). The long-press path (ImeViewBackend long-click listener dispatches the catalog longClickResolver ResendLastAudioLong AND fires onResendLongClicked) never schedules the clear — onResendLongClicked (5289ff) contains no ResendCooldownExpired dispatch. After one RESEND long-press, resendCooldown stays true for the rest of the service lifetime: the RESEND slot's enabledResolver { !state.resend.resendCooldown } (LayoutCatalog.kt:89, 219) disables the button (alpha 0.4), disabled views receive no clicks, so onResendClicked (the only clear scheduler) can never run again. The resend feature is dead until the FGS/store is recreated (keyboard switch).

<details><summary>Evidence</summary>

ResendModule.kt:86-92 (ResendLastAudioLong arms cooldown), :96-102 (only ResendCooldownExpired clears). grep 'ResendCooldownExpired' across app/src/main → sole dispatch at DictateInputMethodService.java:5155, inside onResendClicked. ImeViewBackend.kt:488-525 long-click listener: imeSideAffordance(id,true) → onResendLongClicked (no cooldown clear, read 5289-5329) + slot.longClickResolver dispatch (ResendLastAudioLong per LayoutCatalog.kt:97/224). Short-press affordance additionally gated on inCooldown (DictateInputMethodService.java:1507-1512), so even a delivered click would skip the scheduler.

</details>

**Suggested fix:** Schedule the same 500ms ResendCooldownExpired postDelayed in the long-press path (onResendLongClicked or the RESEND long-press affordance branch) — or better, move the cooldown timer into the module: ResendModule emits an Effect.ScheduleCooldownExpiry whose handler does services.scope.launch { delay(500); services.emitAction(ResendCooldownExpired) }, removing the UI-side scheduling dependency for both arms. Regression test: dispatch ResendLastAudioLong, advance time, assert resendCooldown returns to false.

#### F-047 — History duration shows only the first rolling segment (user-reported 'always 16 seconds') and is never healed

| | |
|---|---|
| **Category** | bug |
| **Severity** | high |
| **Area** | history / recording pipeline |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:history` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt:948`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt:1236`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/DurationHealingJob.kt:38`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/dao/SessionDao.kt:131`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryAdapter.java:85`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryDetailActivity.java:282`

Every keyboard recording now goes through the multi-segment path (post Block A4: initial file is cache/audio/sess_{sid}_seg1.m4a, rolling segments roll every ~16-30s via setMaxFileSize). In PipelineOrchestrator.persistNewSession the multi-segment branch sets audio_duration_seconds = repo.extractDurationSeconds(audioFile) where audioFile is ONLY the first segment (line 948). The comment at lines 944-947 claims 'DurationHealingJob re-syncs to sum-of-segments later' — that claim is false: DurationHealingJob only processes rows where audio_duration_seconds = 0 (SessionDao.findWithMissingDuration, WHERE audio_duration_seconds = 0) and even then extracts from the single audio_file_path, never summing segments. persistMuxedForUpload (line 1236-1254) later replaces audio_file_path with the full merged file but does NOT re-extract duration. Result: any recording longer than one rolling segment permanently shows the first segment's length (~16s on the user's device, where the AAC encoder evidently fills the 64kbps-derived byte budget in ~16s) in both the history list (HistoryAdapter line 85-87) and the detail header (HistoryDetailActivity line 282-283).

<details><summary>Evidence</summary>

PipelineOrchestrator.kt:942-949 multi-segment branch: 'audioDurationSec = repo.extractDurationSeconds(audioFile)' on the first segment, with the comment '(DurationHealingJob re-syncs to sum-of-segments later...)'. DurationHealingJob.kt:37-70 contains no sum-of-segments logic and its input query SessionDao.kt:131-138 filters 'audio_duration_seconds = 0', so a non-zero-but-wrong value is never touched. persistMuxedForUpload (PipelineOrchestrator.kt:1236-1254) calls updateAudioFilePath but never updateAudioDuration. Contrast: the recovery path DOES sum segments correctly — DictatePipelineService.kt:604-608 'audioFileRepository.segments(sessionId).sumOf { extractDurationSeconds(segment) }'. HistoryAdapter.java:85-87 and HistoryDetailActivity.java:282-283 render session.getAudioDurationSeconds() directly.
[merged duplicate from sweep:database: Multi-segment recordings persist wrong audio duration; claimed DurationHealingJob re-sync does not exist] PipelineOrchestrator.kt:942-950 (multi-segment branch: 'audioDurationSec = repo.extractDurationSeconds(audioFile)' on the first segment, with the false re-sync comment); DurationHealingJob.kt:43-70 (heal() reads only session.audioFilePath, single file); SessionDao.kt:130-138 (findWithMissingDuration filters audio_duration_seconds = 0); DictatePipelineService.kt:603-607 (the only sum-of-segments code, feeds the UI timer, not the DB); grep for updateAudioDuration callers shows only DurationHealingJob and the legacy single-file onAudioPersisted path (DictateInputMethodService.java:4659-4676).
[merged duplicate from seed:history-duration: History duration is stuck at ~16s: session duration is extracted from the first rolling segment only and never re-synced] Read PipelineOrchestrator.kt:889-995 (persistNewSession multi-segment branch, line 948 extracts seg1 only; lines 906-918 comment confirms all IME recordings use sess_{sid}_segN naming), RecordingHardwareAdapter.kt:144-170+290-297 (byte-budget cap 64000/8×15×1.15=138,000 B), DictatePrefs.kt:184-185 (interval default 15L), CodecParams.kt:45-48 (bitRate 64_000), SessionDao.kt:131-138 (healing query filters audio_duration_seconds = 0), DurationHealingJob.kt:43-69 (heals from single audioFilePath, no segment sum), PipelineOrchestrator.kt:1236-1254 (persistMuxedForUpload updates path only, not duration), HistoryAdapter.java:85-87 + HistoryDetailActivity.java:282-283 (UI reads session.getAudioDurationSeconds), RecordingRepository.kt:65-80 (ms/1000 floor).

</details>

**Suggested fix:** In persistNewSession's multi-segment branch, compute audioDurationSec as audioFileRepository.segments(sessionId).sumOf { repo.extractDurationSeconds(it) } (same pattern as DictatePipelineService.kt:605). Additionally, in persistMuxedForUpload re-extract the merged file's duration and call sessionDao.updateAudioDuration(sid, ...) so durations self-heal on upload/reprocess. Add a regression test that a two-segment session stores the summed duration.

#### F-105 — Deleting sessions (history delete / auto-cleanup) permanently orphans audio files in files/recordings/

| | |
|---|---|
| **Category** | bug |
| **Severity** | high |
| **Area** | history / storage lifecycle |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `seed:history-redesign` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryActivity.java:83`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryActivity.java:133`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/PipelineOrphanCleaner.kt:95`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/dao/SessionDao.kt:295`

Every successful dictation's canonical audio is persisted to files/recordings/{sid}.m4a (PipelineOrchestrator.persistMuxedForUpload, lines 1236-1254, called at 1182/1202 for both single-segment and muxed multi-segment uploads; audio_file_path updated to the persistent location). Three code paths then delete the session ROW without deleting that file: HistoryActivity long-press delete (sessionDao.deleteById, HistoryActivity.java:83), the Delete-all button (sessionDao.deleteAll, :133), and the 7-day auto-cleanup (SessionDao.deleteInsertedOlderThan, SessionDao.kt:295, run as step 1 of PipelineOrphanCleaner.cleanup). Once the row is gone the file is permanently unreachable: PipelineOrphanCleaner's file pass is DB-row-driven and covers only still-existing FAILED/CANCELLED rows; CacheDirAudioFileFactory.cleanupOrphans and CacheAudioCleanupJob sweep only cache/audio/; the user-visible "clear cache" preference recurses only getCacheDir() (PreferencesFragment.java:305). The only file deleter for files/recordings, RecordingRepository.deleteBySessionId, is invoked solely from HistoryDetailActivity's manual per-session audio-delete (:506). Result: unbounded filesDir growth (~one m4a per successful dictation) that the user cannot reclaim in-app or via Android's clear-cache; only clear-app-data/uninstall frees it. Note: cache-resident rolling segments are NOT leaked (CacheAudioCleanupJob reclaims them) — the leak is specifically the persisted canonical copy. CacheAudioCleanupJob.kt:12-14's claim that files/recordings "is owned by PipelineOrphanCleaner" is doc-drift: that ownership is implemented only for FAILED/CANCELLED rows that still exist, not for row deletion.

<details><summary>Evidence</summary>

HistoryActivity.java:83 `sessionDao.deleteById(session.getId())` and :133 `sessionDao.deleteAll()` — no RecordingRepository call. SessionDao.kt:295 `DELETE FROM sessions WHERE inserted_at IS NOT NULL AND inserted_at < :cutoff` — pure row delete, KDoc mentions CASCADE for child rows only. PipelineOrphanCleaner.kt:90-108 runs that query first, then the file pass (findOrphanedTerminalAudio, SessionDao.kt:310-318) which filters status IN ('FAILED','CANCELLED') — COMPLETED files never enter it. grep for listFiles over files/recordings: only RecordingRepository (writer) touches it; no sweeper exists. PreferencesFragment.java:305 `File cacheDir = requireContext().getCacheDir()` — recordings dir excluded from user-visible cleanup.
[merged duplicate from sweep:history: Deleting sessions from history deletes only DB rows — audio files in files/recordings/ are orphaned forever] HistoryActivity.java:83-85 and 133 delete rows only. RecordingRepository.kt:16 'No other code should write to files/recordings/' — its only delete is the user-driven deleteBySessionId, not called from HistoryActivity. PipelineOrphanCleaner.kt:131-137 reads paths from sessions rows; SessionDao.kt:310-318 query is row-scoped. CacheAudioCleanupJob.kt:12-14 'The persistent canonical audio in files/recordings/{sid}.m4a ... is untouched — that path is owned by PipelineOrphanCleaner', which never scans the directory itself.

</details>

**Suggested fix:** Delete the audio file before deleting the row: HistoryActivity should route deletes through RecordingRepository.deleteBySessionId (or a SessionManager.deleteSession that does file+row atomically); deleteInsertedOlderThan should be replaced by a select-then-delete pass that unlinks audio_file_path (and all audio_file_paths segments) first. Add a one-time (or per-boot) reconciliation sweep of files/recordings/ against SELECT audio_file_path FROM sessions to reclaim already-leaked files.

#### F-092 — POST_NOTIFICATIONS declared in manifest but never requested at runtime — FGS recording notification invisible on Android 13+

| | |
|---|---|
| **Category** | feature-gap |
| **Severity** | high |
| **Area** | manifest-resources |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:manifest-resources` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/AndroidManifest.xml:23`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/onboarding/OnboardingAdapter.java:70`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/settings/DictateSettingsActivity.java:187`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/PipelineNotificationCoordinator.kt:57`

The manifest declares android.permission.POST_NOTIFICATIONS with a comment (lines 16-19) claiming the 'runtime prompt lives in OnboardingActivity — Block 2 §11.5.1'. No such prompt exists anywhere: the only runtime-permission requests in the whole app are for RECORD_AUDIO (OnboardingAdapter.java:70, DictateSettingsActivity.java:187). Since the app targets SDK 35, on Android 13+ devices the POST_NOTIFICATIONS grant is never obtained, so the DictatePipelineService foreground notification — which carries the Pause/Stop/Send/Cancel action buttons (PipelineNotificationCoordinator.build) and the 'recording active/paused/processing' status, plus the OverlayPermissionRequired alert — is silently suppressed for every user who doesn't manually enable notifications in system settings. PipelineNotificationCoordinator.kt:57 itself documents that a missing grant makes notify() a silent no-op, i.e. the code knowingly degrades but nothing ever asks for the grant.

<details><summary>Evidence</summary>

grep for POST_NOTIFICATIONS across app/src/main/java returns only comments (RecordingModule.kt:984, PipelineNotificationCoordinator.kt:57,127) — zero requestPermissions call sites. grep for requestPermissions returns only RECORD_AUDIO at OnboardingAdapter.java:70 and DictateSettingsActivity.java:187. viewpager_permissions.xml has only microphone + keyboard-enable sections, no notifications section. Manifest comment at AndroidManifest.xml:16-19 claims the prompt lives in OnboardingActivity — contradicted by the code.
[merged duplicate from sweep:rewording-usage-onboarding: POST_NOTIFICATIONS runtime prompt promised in OnboardingActivity is never implemented] grep for POST_NOTIFICATIONS across app/src/main hits only the manifest <uses-permission> (AndroidManifest.xml:23), the manifest comment claiming the prompt lives in OnboardingActivity (AndroidManifest.xml:16-19), and comments in RecordingModule.kt:984 / PipelineNotificationCoordinator.kt:57,127. grep for requestPermissions hits only OnboardingAdapter.java:70 which requests RECORD_AUDIO exclusively. The onboarding permissions layout (viewpager_permissions.xml) has only microphone + keyboard rows, no notification row.

</details>

**Suggested fix:** Add a POST_NOTIFICATIONS request (API >= 33) to the onboarding permissions page (viewpager_permissions.xml + OnboardingAdapter position 1) mirroring the microphone row, and/or piggyback it onto the existing requestPermissions call in DictateSettingsActivity for upgrading users. Update or remove the stale manifest comment.

#### F-003 — Auto-apply prompt queue is never prepared on the catalog record path (main record button + overlay widget)

| | |
|---|---|
| **Category** | wiring-gap |
| **Severity** | high |
| **Area** | core-ime / prompt queue |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:core-ime` |

**Files:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:3809`, `app/src/main/java/net/devemperor/dictate/core/PromptQueueManager.kt:112`, `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt:89`

Confirmed as described. Additional detail: the stale comment at DictateInputMethodService.java:5063-5064 claims onRecordClicked() is invoked via the ImeViewBackend imeSideAffordance RECORD hook, but the affordance lambda (:1467-1550) only calls prepareCatalogStopRecordingIfActive() for RECORD/OVERLAY_RECORD clicks — onRecordClicked() (and thus startRecording() → prepareAutoApplyQueue()) is reachable only from the QWERTZ record button (:973) and the instant-prompt chip (:2628). Also note auto-apply IDs from a prior QWERTZ-started recording linger in the in-memory queue (never cleared post-pipeline), which can mask the gap in mixed-usage sessions but does not fix it.

<details><summary>Evidence</summary>

grep over app/src/main/java: prepareAutoApplyQueue has exactly one caller — DictateInputMethodService.java:3809 (inside the IME's startRecording, which the catalog path bypasses). ActionResolvers.kt:89-163 (resolveRecordAction) contains no auto-apply preparation and neither does RecordingModule's StartRecording arm (no other reference exists).

</details>

**Suggested fix:** Call prepareAutoApplyQueue at the single recording-start seam shared by both surfaces — e.g. in the imeSideAffordance RECORD/OVERLAY_RECORD click branch before the catalog dispatch when state is Idle, or as part of the StartRecording effect via a ModuleServices seam. Add a regression test asserting a catalog-started recording includes autoApply prompt IDs in the FreshConfig snapshot.

#### F-013 — Service-side BluetoothScoManager BroadcastReceiver is never registered — Bluetooth-mic recording always times out and silently records the phone mic

| | |
|---|---|
| **Category** | wiring-gap |
| **Severity** | high |
| **Area** | recording-audio / bluetooth-sco |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:recording-audio` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:402`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/BluetoothScoManager.kt:80`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/BluetoothScoManager.kt:121`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:2528`

The live (orchestrator) recording path's BluetoothScoManager is constructed in DictatePipelineService.onCreate (line 402) but its SCO BroadcastReceiver is never registered — no registerReceiver() call exists in DictatePipelineService.kt, and the BluetoothScoControl interface deliberately excludes receiver lifecycle ("service's lifecycle wiring", which the service omits). Only the IME-side legacy manager registers a receiver, whose callback is the @Deprecated, post-cutover-dead RecordingStateController that never forwards to the orchestrator. Consequently on the live path with Pref.UseBluetoothMic enabled, startSco() can only succeed via the rare isBluetoothScoOn early-return; otherwise the 2500 ms timeout always fires onScoFailed → OnBluetoothScoStateChanged(Failed) → ScoRouteResolved(useBluetooth=false) → deferred AllocateMediaRecorder with AudioSource.MIC. Every BT-mic recording therefore starts ~2.5 s late and silently records the phone microphone instead of the headset. Fix: call registerReceiver() on the service-side manager in onCreate and unregisterReceiver() in onDestroy.

<details><summary>Evidence</summary>

grep 'registerReceiver' across app/src/main/java: only BluetoothScoManager.kt itself and DictateInputMethodService.java:2528/3183 (IME-side legacy manager, callback = deprecated RecordingStateController per DictateInputMethodService.java:769-770). DictatePipelineService.kt:396-433 constructs the orchestrator-side manager + BluetoothScoSubsystemAdapter with no registerReceiver call; onDestroy likewise has no unregisterReceiver for it. BluetoothScoManager.startSco (121-141) only reports success via the broadcast-driven onScoConnected or the isBluetoothScoOn early return; AudioModule.kt:437-441 converts Connected/Failed into ScoRouteResolved(useBluetooth = phase==Connected).

</details>

**Suggested fix:** Call bluetoothScoManager.registerReceiver() right after construction in DictatePipelineService.onCreate and unregisterReceiver() in onDestroy (mirroring the legacy IME wiring). Consider migrating startBluetoothSco/stopBluetoothSco (deprecated since API 31) to AudioManager.setCommunicationDevice as a follow-up. Add an instrumented/robolectric test asserting that a SCO_AUDIO_STATE_CONNECTED broadcast flips isScoStarted on the service-owned manager.


### 3.3 Medium (35 findings)

#### F-004 — onFinishInputView state discrimination reads the dead legacy RecordingStateController — idle-cleanup branch runs during active new-path recordings and clears livePrompt

| | |
|---|---|
| **Category** | bug |
| **Severity** | medium |
| **Area** | core-ime / IME lifecycle |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:core-ime` |

**Files:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:2191`

onFinishInputView (DictateInputMethodService.java:2191) gates its three-way state discrimination on the legacy recordingStateController, which post-cutover is never started on the bound path and stays permanently Idle. During an active FGS recording, branch (A) (keep-alive, collapse content only) is therefore unreachable and the idle-cleanup branch (C) runs on keyboard-hide: it resets livePrompt and pendingLivePromptChain (plus pipelineOrchestrator.cancel(), SCO receiver unregister, infoBar dismiss). Since livePrompt is armed only at the instant-prompt chip tap and consumed at the send-tap (captureFreshConfigSnapshot → pendingLivePromptChain → onPipelineCompleted chain-vs-insert decision), hiding the keyboard mid-recording silently converts a live-prompt run into a verbatim transcript insert. The sibling consumer updatePromptButtonsEnabledState was already migrated off the dead controller (B-E fix, :4948); this call-site should use the existing isEffectiveRecordingActiveOrPaused()/Preparing predicates (:3712/:3731) instead.

<details><summary>Evidence</summary>

DictateInputMethodService.java:2191-2224 (branch chain reading recordingStateController.getState()); :3671-3710 isEffectiveRecordingIdle KDoc documents 'the legacy recordingStateController is never started' on the new path; :4916-4947 documents the identical B-E regression pattern for another consumer of the same dead state.

</details>

**Suggested fix:** Replace the branch predicates with the orchestrator-authoritative helpers already present in the file (isEffectiveRecordingActiveOrPaused()/isEffectiveRecordingInFlight() and getPipelinePhase() instanceof Preparing/Running) so branch (A)/(B) fire correctly on the bound path.

#### F-020 — Slow-output animation commits per UTF-16 code unit — surrogate pairs (emoji) are committed as two lone surrogates

| | |
|---|---|
| **Category** | bug |
| **Severity** | medium |
| **Area** | insertion-keyboard |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:insertion-keyboard` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/insertion/SlowOutputAnimator.kt:55`

SlowOutputAnimator (app/src/main/java/net/devemperor/dictate/state/insertion/SlowOutputAnimator.kt:55,63) commits one UTF-16 code unit per commitText call, so astral characters (emoji etc.) are committed as a lone high surrogate followed one animation tick later by the lone low surrogate. Guaranteed effect: invalid intermediate text (replacement glyph flash) in every host; permanent corruption in hosts that sanitize per commit (Chromium/WebView converts lone surrogates to U+FFFD independently, turning the emoji into two replacement chars). Exposure: gated by Pref.InstantOutput, which defaults to true — the bug only affects users who disabled instant output; when off, ALL inserts flow through the animator because InsertionPolicy.animate is never consumed (the TextCommitter at DictateInputMethodService.java:4769-4780 checks only the pref), including picked-emoji keystrokes. The claimed W1 escalation (tail drop via onTailFailure when a half-surrogate commit returns false, SlowOutputAnimator.kt:66-69) is speculative: commitText's false return conventionally signals a dead IC, not content rejection, so full-tail loss is a possible but not typical outcome. Fix pattern already exists in-repo: deleteOneCharacter (DictateInputMethodService.java:5039-5053) uses BreakIterator/offsetByCodePoints for the delete direction.

<details><summary>Evidence</summary>

SlowOutputAnimator.kt:55 (`ic.commitText(text[0].toString(), 1)`) and :63 (`ic.commitText(text[index].toString(), 1)`) iterate Char-wise; no codePoint/BreakIterator handling anywhere in state/insertion (grep). Contrast: deleteOneCharacter (DictateInputMethodService.java:5039-5052) already uses BreakIterator.getCharacterInstance for the delete direction, so the project knows the grapheme pattern. Legacy loop had the same charAt() flaw but fire-and-forgot each commit, so a rejected half-pair did not cancel the tail; the W1 abort (SlowOutputAnimator.kt:66-69) now does.
[merged duplicate from seed:emoji-delete: SlowOutputAnimator commits per UTF-16 char, splitting surrogate pairs across commitText calls] SlowOutputAnimator.kt run(): 'if (!ic.commitText(text[0].toString(), 1)) return false' and scheduleFrom(): 'ic.commitText(text[index].toString(), 1)' - both index by UTF-16 char. Wired as the TextCommitter for the InsertionService at DictateInputMethodService.java:4768-4780, gated only by Pref.InstantOutput; PIPELINE and RESEND policies have animate=true (Insertion.kt:76-85), so all transcription output flows through this when instant output is disabled.

</details>

**Suggested fix:** Iterate by grapheme cluster (BreakIterator.getCharacterInstance) or at minimum by code point (String.offsetByCodePoints), committing whole clusters per tick. Add a unit test animating a string containing an emoji and asserting each commitText payload is a valid, complete cluster.

#### F-021 — Space-swipe cursor move uses commitText("", offset): destroys an active selection and moves by code units (can land inside a surrogate pair)

| | |
|---|---|
| **Category** | bug |
| **Severity** | medium |
| **Area** | insertion-keyboard |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:insertion-keyboard` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:4855`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/render/SpecialTouchHandlerInstaller.kt:269`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/keyboard/QwertzKeyboardController.kt:72`

ControlOp.CursorMove is executed as ic.commitText("", offset) (DictateInputMethodService.java:4855-4856) with no selection check anywhere on the path (SpecialTouchHandlerInstaller.kt:269, QwertzKeyboardController.kt:64-72, InsertionService.control at InsertionService.kt:85-92, CursorSwipeTouchHandler). Per the InputConnection contract — which this codebase itself documents at Insertion.kt:160-164 (DeleteSelection = "commitText(\"\", 1) replaces the selected range with nothing") and BackspaceSwipeHandler.kt:29-31 — committing empty text while a selection is active deletes the selected text. So a single space-bar swipe step while the user has a host-app selection silently destroys it. Secondary issue: each step moves by one UTF-16 code unit (offsets 2 / -1) with no grapheme awareness (unlike the grapheme-aware DeleteSurrounding backspace); on custom editors the caret can land inside a surrogate pair, while stock editors' SpannableStringBuilder snaps span boundaries off surrogate pairs, making the more likely stock symptom a stuck/snapping cursor over emoji rather than guaranteed pair corruption. Behavior is legacy-parity by design (matches legacy lines 746/751), but the legacy call had the same defect.

<details><summary>Evidence</summary>

DictateInputMethodService.java:4855-4856 (CursorMove -> ic.commitText("", offset)); Android InputConnection.commitText contract: 'replace the currently composing text / selected text with the given text'. QwertzKeyboardController.kt:69-72 and SpecialTouchHandlerInstaller.kt:265-270 both emit CursorMove(2/-1) per swipe step with no selection check and no grapheme clamping. ControlOp.DeleteSelection (Insertion.kt:160-164) even documents that an empty commit over a selection deletes it — the same call CursorMove makes unconditionally.

</details>

**Suggested fix:** Implement CursorMove via selection APIs: read getExtractedText (selection + startOffset), compute the new absolute position clamped to a grapheme boundary (offsetByCodePoints/BreakIterator), then ic.setSelection(pos, pos). At minimum, guard: if getSelectedText is non-empty, collapse the selection to its edge instead of committing empty text.

#### F-023 — BackspaceSwipeHandler ignores ExtractedText.startOffset — swipe-select computes wrong absolute indices in editors that return partial extracts

| | |
|---|---|
| **Category** | bug |
| **Severity** | medium |
| **Area** | insertion-keyboard |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:insertion-keyboard` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/keyboard/BackspaceSwipeHandler.kt:86`

On swipe-select activation the handler reads et = ic.getExtractedText(...) and sets swipeBaseCursor = max(et.selectionStart, et.selectionEnd), then calls ic.setSelection(boundaries[steps], swipeBaseCursor). ExtractedText.selectionStart/End are documented as offsets WITHIN the extracted text (relative to et.startOffset), while InputConnection.setSelection takes absolute document offsets. The code never adds et.startOffset. For editors that return the full text (startOffset=0, the common case) this works, but hosts with large documents legitimately return a windowed extract with startOffset > 0 — there the swipe selects and then DELETES (via DeleteSelection on release, line 145) a range shifted toward the document start by startOffset code units: silent deletion of the wrong words.

<details><summary>Evidence</summary>

BackspaceSwipeHandler.kt:86-91 (et.selectionStart/selectionEnd used directly, subSequence(0, swipeBaseCursor) over et.text), :107 and :120 (ic.setSelection(boundaries[steps], swipeBaseCursor) with those relative values), :145 (release deletes the selection). No reference to et.startOffset anywhere in the file (grep). Android docs: ExtractedText.startOffset = 'the offset in the overall text at which the extracted text starts'.

</details>

**Suggested fix:** Add et.startOffset to swipeBaseCursor and to every boundary before calling setSelection (boundaries can stay relative for computeWordBoundaries; convert at the setSelection call sites). Add a unit test with a fake IC whose ExtractedText has startOffset > 0.

#### F-049 — Progress bar never hidden after a history reprocess completes

| | |
|---|---|
| **Category** | bug |
| **Severity** | medium |
| **Area** | history |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:history` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryDetailActivity.java:497`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryDetailActivity.java:208`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryDetailActivity.java:711`

startHistoryReprocess() (HistoryDetailActivity.java:497) sets UiState.LOADING (progress bar VISIBLE) after handing the job to JobExecutor. The job runs on JobExecutor's own executor thread; on completion (success or failure) JobExecutor only unregisters from ActiveJobRegistry (JobExecutor.kt:198). The Activity's only reaction is the ActiveJobRegistryObserver lambda (lines 208-210) and onResume (214-219), both of which call only loadSession() — which never calls setUiState or touches the progress bar. setUiState(IDLE/ERROR) exists only in the local-executor regenerate (553/560) and post-process (647/656) flows. Result: after any history reprocess (direct at line 164 or edited-queue at line 613), the indeterminate progress bar stays visible until the Activity is destroyed and recreated, even though the refreshed pipeline content renders and the reprocess buttons re-enable. The spinner also persists when the reprocess job fails.

<details><summary>Evidence</summary>

grep over HistoryDetailActivity.java: setUiState(LOADING) at 497 (startHistoryReprocess) has no matching IDLE transition; the registry observer at 208-210 only calls loadSession(); loadSession() (221-246) contains no progressBar/setUiState call.
[merged duplicate from seed:history-redesign: History-detail progress spinner never hidden after direct/edited reprocess] Grep of setUiState call sites in HistoryDetailActivity.java: line 497 (LOADING, reprocess), 519 (LOADING, regenerate), 552/560 (IDLE/ERROR, regenerate), 624/646/656 (post-process). The registry observer lambda at 208-210 and onResume at 214-219 call only loadSession(); loadSession() (221-246) contains no progressBar/setUiState code. setUiState switch at 711-721 collapses IDLE and ERROR into the same branch.

</details>

**Suggested fix:** In the ActiveJobRegistryObserver callback (or in loadSession), set UiState.IDLE when !ActiveJobRegistry.INSTANCE.isActive(sessionId). Alternatively drop the Activity-local LOADING state for JobExecutor jobs entirely and rely on the jobActive-derived button/badge state that buildRecordingPipeline already computes.

#### F-062 — Custom-provider host field stays hidden after viewing Anthropic in API settings

| | |
|---|---|
| **Category** | bug |
| **Severity** | medium |
| **Area** | settings-prefs |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:settings-prefs` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/settings/APISettingsActivity.java:393`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/settings/APISettingsActivity.java:385`, `/home/lukas/WebStorm/Dictate/app/src/main/res/layout/activity_api_settings.xml:235`

In APISettingsActivity.updateRewordingUI, the ANTHROPIC branch hides the custom-host sub-group with findViewById(R.id.rewording_custom_host_group).setVisibility(View.GONE) (line 393), but no other branch ever restores it to VISIBLE. The isCustom branch (lines 385-388) only toggles the outer wrapper. Consequence: once the user selects Anthropic as rewording provider and then switches to Custom within the same activity session, the 'Custom host' input field remains invisible — the user cannot enter the base URL for their custom endpoint. The field only reappears after leaving and reopening the activity (onCreate re-inflates with the XML default visibility).

<details><summary>Evidence</summary>

Read APISettingsActivity.java:374-405 — ANTHROPIC branch line 393 sets rewording_custom_host_group GONE; the isCustom branch (line 385) sets host/model text and hides the model group but never resets the host group's visibility. Verified via grep that line 393 is the ONLY setVisibility call on rewording_custom_host_group in the codebase, and activity_api_settings.xml:235 shows the group nested inside api_settings_rewording_custom_fields_wrapper (line 228), so the wrapper's VISIBLE does not undo the child's GONE.
[merged duplicate from sweep:ai-layer: API settings: switching provider Anthropic → Custom leaves the Custom host field permanently hidden] APISettingsActivity.java:374-405: the only write to rewording_custom_host_group is the GONE at line 393 inside the `provider == AIProvider.ANTHROPIC` branch; the isCustom branch (385-388) sets wrapper visibility and text but never resets the host group. Layout activity_api_settings.xml:235 declares rewording_custom_host_group inside api_settings_rewording_custom_fields_wrapper with default VISIBLE (only restored on activity recreation).

</details>

**Suggested fix:** In updateRewordingUI, explicitly set the host-group visibility in every branch: findViewById(R.id.rewording_custom_host_group).setVisibility(isCustom ? View.VISIBLE : View.GONE) before the provider-specific branches, keeping the ANTHROPIC branch's GONE as-is.

#### F-067 — NETWORK_ERROR mapping is dead code: SDKs wrap IOException in RuntimeExceptions the runners never catch

| | |
|---|---|
| **Category** | bug |
| **Severity** | medium |
| **Area** | ai-layer / error propagation |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:ai-layer` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/runner/OpenAICompatibleRunner.kt:75`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/runner/AnthropicCompletionRunner.kt:70`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/JobExecutor.kt:237`

Both wrapProviderCall implementations (OpenAICompatibleRunner.kt:75, AnthropicCompletionRunner.kt:70) catch java.io.IOException to map ErrorType.NETWORK_ERROR, but the OpenAI SDK 4.26.0 and Anthropic SDK 2.16.0 sync OkHttp clients catch IOException internally and rethrow it as OpenAIIoException / AnthropicIoException — RuntimeException subclasses that pass through the catch chain uncaught (bytecode-verified). Consequences: network failures escape the @throws AIProviderException contract of AIOrchestrator.transcribe/complete (provider tagging at AIOrchestrator.kt:72-74/112-114 never runs); JobExecutor.classifyError (JobExecutor.kt:237-254) persists last_error_type=UNKNOWN instead of NETWORK_ERROR, making the NETWORK_ERROR DB value unreachable for all OpenAI-compatible and Anthropic calls (only the raw-OkHttp ElevenLabs runner produces it); the UI shows 'internet_error' only via the generic else-branch in PipelineOrchestrator.handlePipelineError (PipelineOrchestrator.kt:1515-1519) with provider=null. Additionally, 403 PermissionDeniedException and 422 UnprocessableEntityException (siblings under OpenAIServiceException, not subclasses of any caught type) are also uncaught and degrade to UNKNOWN. Fix direction: catch the SDK base/IO exception types (com.openai.errors.OpenAIIoException / com.anthropic.errors.AnthropicIoException, plus the remaining OpenAIServiceException subtypes) in the runners' wrapProviderCall.

<details><summary>Evidence</summary>

OpenAICompatibleRunner.kt:59-78 and AnthropicCompletionRunner.kt:54-73 catch IOException as last resort. Bytecode inspection of openai-java-client-okhttp-4.26.0.jar (com.openai.client.okhttp.OkHttpClient) shows an exception-table entry catching java/io/IOException and constructing com/openai/errors/OpenAIIoException; identical pattern in anthropic-java-client-okhttp-2.16.0.jar (AnthropicIoException). OpenAIIoException extends OpenAIException extends RuntimeException (javap confirmed). JobExecutor.kt:237-255 classifyError: non-AIProviderException → ErrorType.UNKNOWN. PipelineOrchestrator.kt:1505-1516 else-branch emits 'internet_error'.

</details>

**Suggested fix:** In OpenAICompatibleRunner.wrapProviderCall additionally catch com.openai.errors.OpenAIIoException (map to NETWORK_ERROR, preserving cause so PipelineOrchestrator.isCancellation still sees InterruptedIOException), and in AnthropicCompletionRunner catch com.anthropic.errors.AnthropicIoException. Optionally also map PermissionDeniedException (403) and UnprocessableEntityException (422) to RATE_LIMITED/BAD_REQUEST. Add a regression test asserting a runner wraps an SDK IoException into AIProviderException(NETWORK_ERROR).

#### F-079 — Prompt pos bookkeeping breaks after delete: duplicate pos values cause unstable ordering

| | |
|---|---|
| **Category** | bug |
| **Severity** | medium |
| **Area** | rewording |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:rewording-usage-onboarding` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/rewording/PromptsOverviewAdapter.java:132`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/rewording/PromptEditActivity.java:176`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/rewording/PromptsOverviewActivity.java:266`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/dao/PromptDao.kt:13`

Deleting a prompt never re-normalizes the pos column, leaving holes. New prompts (PromptEditActivity.savePrompt:176) and imported appended prompts (PromptsOverviewActivity.appendPrompts:266) get pos = promptDao.count(), which collides with existing pos values after a deletion (e.g. {0,1,2} → delete → {1,2} → add → {1,2,2}). pos has no UNIQUE/CHECK constraint and PromptDao.getAll()/getAutoApplyIds() order by pos ASC with no tiebreaker, so duplicate-pos rows sort by unspecified scan order (rowid in practice). The move-up/move-down handlers (PromptsOverviewAdapter.java:94-130) write adapter-index-based pos values that collide with untouched rows once holes/duplicates exist; a reproducible sequence (reorder, delete, add, reorder) yields a DB state where the overview's live list, the reopened overview, and the keyboard prompt list (DictateInputMethodService.java:4897) show different orders than the user arranged. Auto-apply execution order is affected as well.

<details><summary>Evidence</summary>

PromptsOverviewAdapter.java:132-145 delete handler: promptDao.deleteById + data.remove + notifyItemRemoved only, no pos rewrite. PromptEditActivity.java:176: new PromptEntity(0, promptDao.count(), ...). PromptsOverviewActivity.java:266: int startPos = promptDao.count(). PromptDao.kt:13: SELECT * FROM prompts ORDER BY pos ASC. No CHECK/UNIQUE constraint on pos and no re-normalization pass exists anywhere (grepped for pos updates).

</details>

**Suggested fix:** After a delete, re-normalize pos for all remaining prompts (single transaction: rewrite pos = index in ORDER BY pos). Alternatively use pos = (SELECT MAX(pos)+1) for inserts and keep holes, but then the move handlers must swap actual pos values of the two neighbours instead of assuming pos == adapter index.

#### F-108 — Regenerate on AUTO_FORMAT steps runs a completion with null prompt (bypasses AutoFormattingService)

| | |
|---|---|
| **Category** | bug |
| **Severity** | medium |
| **Area** | history / regeneration |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `seed:history-redesign` |
| **Spec** | → [`2026-07-02 - history-reprocess-hardening.md`](<2026-07-02 - history-reprocess-hardening.md>) |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryDetailActivity.java:137`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryDetailActivity.java:523`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt:1316`

Confirmed as described. One additional detail strengthening the finding: the original auto-format path wraps the transcript in a PromptBuilder user prompt (language hint + <transcript> tags), while regenerate sends the raw inputText — so the regenerated call differs from the original in both system prompt AND user-prompt structure. The model receives only the bare transcript and will answer/continue it instead of reformatting. Mitigating factor for severity: the previous version is retained and re-selectable via the version chooser, so the damage is recoverable.

<details><summary>Evidence</summary>

HistoryDetailActivity.java:136-138 `onRegenerate: regenerateStep(step, chainIndex, step.getPromptUsed(), step.getPromptEntityId())`; :523 `orchestrator.complete(step.getInputText(), promptText)` where promptText is null for AUTO_FORMAT; PipelineOrchestrator.kt executeAutoFormat persists the step via appendProcessingStep(sid, StepType.AUTO_FORMAT, text, fr.text, model, provider, null, null, ...) — promptUsed is the 7th arg = null. AIOrchestrator.complete (AIOrchestrator.kt:84) forwards systemPrompt = null unchanged.

</details>

**Suggested fix:** For StepType.AUTO_FORMAT route regeneration through AutoFormattingService.formatIfEnabled (or hide the Regenerate/Other-prompt buttons on auto-format rows).

#### F-111 — Post-process flow creates the DB session before a prompt is chosen — cancelling leaves an empty orphan session; pending context lost on rotation

| | |
|---|---|
| **Category** | bug |
| **Severity** | medium |
| **Area** | history / post-processing |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `seed:history-redesign` |
| **Spec** | → [`2026-07-02 - history-reprocess-hardening.md`](<2026-07-02 - history-reprocess-hardening.md>) |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryDetailActivity.java:575`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryDetailActivity.java:81`

createPostProcessingSession() inserts a new POST_PROCESSING SessionEntity (sessionManager.createSession + updateInputText, lines 579-592) BEFORE showing the prompt chooser. If the user dismisses the bottom sheet without picking a prompt, the row stays: an empty POST_PROCESSING entry (status RECORDED, no steps, no final output) appears in the history list forever — and it is immune to the auto-cleanup because inserted_at never gets set. Additionally, all pending-chooser context lives in bare instance fields (pendingStep, pendingChainIndex, pendingPostProcessOutputText, pendingPostProcessNewSessionId, lines 81-84) that are not saved in onSaveInstanceState, while the BottomSheet itself DOES survive rotation (listener re-bound in onAttach). After a rotation with the sheet open, choosing a prompt silently does nothing for TAG_REGENERATE (pendingStep == null guard at line 602) and leaks the pre-created session for TAG_POST_PROCESS (pendingPostProcessNewSessionId == null). The reprocess-edit path shows the correct pattern — it encodes the sessionId into the fragment tag (TAG_REPROCESS_EDIT_PREFIX + sessionId) and survives recreation.

<details><summary>Evidence</summary>

HistoryDetailActivity.java:575-598 (createSession before PromptChooserBottomSheet.show), :600-621 (onPromptChosen null-guards silently no-op), :79-84 (comment admits 'survives only within same Activity instance'). No onSaveInstanceState override exists in the file. Contrast :178-180 tag-encoded reprocess path.
[merged duplicate from sweep:history: Post-process flow creates the session row before the prompt is chosen — cancelling the chooser leaves a permanent ghost 'Recorded' entry in history] HistoryDetailActivity.java:579-597: sessionManager.createSession(...RECORDED) + updateInputText happen before PromptChooserBottomSheet.show(); there is no onDismiss/onCancel cleanup anywhere in the class. onPromptChosen:602-605 guards on pendingStep != null / pendingPostProcessNewSessionId != null, both lost on recreation (comment at lines 79-81 acknowledges 'survives only within same Activity instance'). SessionDao.kt:45-51 includes RECORDED in findActiveSessionIds.

</details>

**Suggested fix:** Defer session creation until onPromptChosen (create + append step + finalize in one transaction), or delete the pre-created session on sheet dismiss/cancel. Encode the necessary context (step id, chain index, new-session payload) into the fragment tag/arguments like the reprocess-edit path so rotation survives.

#### F-113 — History playback and delete-audio operate on the legacy single audio_file_path — multi-segment recordings play/delete only segment 1

| | |
|---|---|
| **Category** | bug |
| **Severity** | medium |
| **Area** | history / multi-segment audio |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `seed:history-redesign` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryDetailActivity.java:332`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/RecordingRepository.kt:136`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/dao/SessionDao.kt:198`

History-detail playback and delete-audio operate only on the legacy single audio_file_path column, which for multi-segment recordings (rolling segments every ~30s, RecordingHardwareAdapter.DEFAULT_ROLLING_INTERVAL_MS) is frozen at segment 1 until persistMuxedForUpload repoints it during a pipeline upload (PipelineOrchestrator.kt:1236-1254). HistoryDetailActivity.resolveAudioAvailability (332-336), the AUDIO step path (306), and playAudio (432-447) never consult audioFilePaths, so affected sessions play only the first ~30s while the title shows the full duration. RecordingRepository.deleteBySessionId (136-148) deletes only that one file, then clearAudioFilePath wipes both columns, orphaning segments 2..N in cache/audio — reaped by the cache TTL for terminal statuses, but NEVER for RECORDED sessions (still in CacheAudioCleanupJob's alive-set, and PipelineRecovery's ghost-RECORDED promotion requires non-empty audioFilePaths, which the delete just cleared). Scope refinement vs. original claim: sessions that FAILED during the API call are NOT affected (persistMuxedForUpload already repointed the path before upload); affected are RECORDED sessions that never reached upload (incl. TRANSCRIBING→RECORDED crash downgrades), fresh RECORDING_INTERRUPTED continuation candidates (delete/play buttons shown without status gate), and cancel/fail-before-upload cases. The reprocess pipeline itself is correct (readForPipeline merges segments). Note: one merged duplicate's "~16s segment" figure is wrong — the rolling interval is ~30s.

<details><summary>Evidence</summary>

SessionDao.kt:182-199 KDoc: 'the legacy column is frozen at allocate-time ... only the new column tracks rolling-segment growth. Readers go through audioFilePaths directly.' HistoryDetailActivity.java:332-336 + :432-447 read getAudioFilePath() only; grep for audioFilePaths in history/: zero hits. RecordingRepository.kt:136-148 deletes one file then calls clearAudioFilePath which resets both columns (SessionDao.kt:164).
[merged duplicate from sweep:history: History audio playback plays only the first segment for multi-segment sessions that have not been uploaded yet] HistoryDetailActivity.java:306 '.audioFilePath(audioAvailable ? session.getAudioFilePath() : null)', :332-336 resolveAudioAvailability checks only session.getAudioFilePath(), :432-440 MediaPlayer.setDataSource(audioFilePath). SessionDao.kt:191-196 documents audio_file_path as frozen at allocate-time while audio_file_paths tracks rolling-segment growth. PipelineOrchestrator.kt:1248 shows audio_file_path is only repointed to the merged file during upload.
[merged duplicate from seed:history-duration: History audio playback plays only the first ~16s segment for multi-segment sessions that never completed a pipeline run] Read HistoryDetailActivity.java:280-312 (buildRecordingPipeline passes session.getAudioFilePath() to the AUDIO step) and 432-440 (playAudio uses that path directly), PipelineOrchestrator.kt:906-918 (audio_file_path = first segment on the multi-segment path), SessionDao.kt:182-199 (updateAudioFilePaths is single-column; legacy column frozen at allocate-time), PipelineOrchestrator.kt:1236-1254 (path only updated to merged file during upload persist).
[merged duplicate from sweep:history: 'Delete audio' in the detail view deletes only the single audio_file_path file — rolling segments stay on disk, indefinitely for RECORDED sessions] HistoryDetailActivity.java:505-507 calls recordingRepository.deleteBySessionId(targetSessionId). RecordingRepository.kt:136-148 deletes File(session.audioFilePath) only, then clearAudioFilePath. CacheAudioCleanupJob.kt:107-112: 'if (sessionId in alive) ... Files stay regardless of age', with alive-set from SessionDao.findActiveSessionIds (includes RECORDED, SessionDao.kt:45-51). grep confirms audioFileRepository.deleteAll is only invoked from state/modules/RecordingModule.kt:1058.

</details>

**Suggested fix:** Route history playback and availability through AudioFileRepository (merged file or sequential segment playback) using effective audio paths; make deleteBySessionId iterate all segment paths (audio_file_paths) plus the legacy path before clearing the columns.

#### F-088 — No behavioural migration tests for MIGRATION_4_5, 5_6 and 6_7 despite the doc-mandated test contract — including the 5→6 Double-Enum CHECK change

| | |
|---|---|
| **Category** | code-quality |
| **Severity** | medium |
| **Area** | database / migration tests |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:database` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/test/java/net/devemperor/dictate/database/migration/MigrationTo5MetadataTest.kt:11`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo6.kt:44`, `/home/lukas/WebStorm/Dictate/docs/DATABASE-PATTERNS.md:148`

DATABASE-PATTERNS.md:146-148 mandates: 'Every Double-Enum migration must have a corresponding test' (accept-valid + reject-invalid), and the Migration Conventions section adds a two-case test contract for cleanup-policy columns. The instrumented suite contains only MigrationTo4Test (covering 3→4 thoroughly) plus a setup smoke test. MIGRATION_5_6 is a full sessions table-recreate that widens the status CHECK to include RECORDING_INTERRUPTED (MigrationTo6.kt:44-49) — the exact migration class the mandate targets — and has zero behavioural coverage. MigrationTo5MetadataTest.kt:11-14 explicitly defers 'Behavioural verification ... belongs in the instrumented migration suite under app/src/androidTest/' — but no such test was ever added there for 4→5, 5→6, or 6→7 (backfill correctness of audio_file_paths untested both times). Table-recreate migrations are the highest-risk class (a column-order slip silently shifts data for every upgrading user), so the missing coverage is a real data-integrity exposure, not test hygiene.

<details><summary>Evidence</summary>

find app/src/androidTest → only KeyboardLayoutUiTest.kt, AndroidTestSetupSmokeTest.kt, MigrationTo4Test.kt; grep for MIGRATION_5_6/MIGRATION_6_7/MIGRATION_4_5 in test trees finds only metadata/enum-shape tests (MigrationTo5MetadataTest asserts start/end version numbers only; SessionStatusTest.kt:36 asserts enum arity against the M5_6 CHECK list but never runs the migration).

</details>

**Suggested fix:** Add instrumented MigrationTestHelper tests: migrate5To6 accepts RECORDING_INTERRUPTED and rejects an unknown status; migrate4To5 + migrate6To7 verify audio_file_paths backfill from audio_file_path (non-null copied verbatim, NULL → ''); plus a 1→7 chain test mirroring migrate1To4_chain_preservesData.

#### F-094 — Backup rules are unconfigured template stubs — all SharedPreferences (incl. every provider API key) and the full Room DB are cloud-backed-up

| | |
|---|---|
| **Category** | code-quality |
| **Severity** | medium |
| **Area** | manifest-resources |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:manifest-resources` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/res/xml/backup_rules.xml:9`, `/home/lukas/WebStorm/Dictate/app/src/main/res/xml/data_extraction_rules.xml:7`, `/home/lukas/WebStorm/Dictate/app/src/main/AndroidManifest.xml:47`

The manifest enables allowBackup=true and wires both backup files, but both are still the Android Studio sample stubs ('Sample backup rules file; uncomment and customize as necessary', 'TODO: Use <include> and <exclude>...'). backup_rules.xml (pre-API-31 path) explicitly includes domain="sharedpref" path="." and domain="database" path="."; data_extraction_rules.xml (API 31+ path) has an empty <cloud-backup> element, which means default behavior: back up everything. Consequence: all transcription/rewording API keys (Pref.TranscriptionApiKey*/RewordingApiKey* live in the default SharedPreferences, DictatePrefs.kt:49-70) plus the entire dictation history DB (sessions, transcriptions text) are uploaded to Google cloud backup. Restores can also resurrect stale internal-state prefs (e.g. Pref.TranscriptionAudioFile, CacheCleanupLastRunMs) and DB rows pointing at cache-audio paths that don't exist on the new device — PipelineRecovery mitigates the DB side, but the secret-material exposure is unaddressed and clearly never a deliberate decision (TODO comments remain).

<details><summary>Evidence</summary>

Read res/xml/backup_rules.xml (includes sharedpref+database, sample-file comment intact) and res/xml/data_extraction_rules.xml (empty <cloud-backup> with commented TODO). AndroidManifest.xml:47-49 wires android:allowBackup="true", android:dataExtractionRules, android:fullBackupContent. DictatePrefs.kt:49-76 shows all API keys stored in the same default SharedPreferences file that the rules include wholesale.

</details>

**Suggested fix:** Make a deliberate decision and encode it: either exclude the API-key prefs (they'd need to move to a dedicated SharedPreferences file, e.g. dictate_secrets.xml, so <exclude domain="sharedpref" path="dictate_secrets.xml"/> works in both rule files) or document that key backup is intended. Fill in data_extraction_rules.xml (cloud-backup + device-transfer) instead of leaving the sample stub, and drop the sample comments.

#### F-006 — Catalog record path has no RECORD_AUDIO permission check — legacy settings-redirect only exists on QWERTZ/instant-prompt surfaces

| | |
|---|---|
| **Category** | feature-gap |
| **Severity** | medium |
| **Area** | core-ime / recording start |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:core-ime` |

**Files:** `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt:89`, `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:5086`, `app/src/main/java/net/devemperor/dictate/core/RecordingHardwareAdapter.kt:200`

The legacy record click handler (onRecordClicked :5086, instant-prompt :2625) checks RECORD_AUDIO and opens the Settings activity when missing. The main keyboard record button and overlay widget go through resolveRecordAction, which dispatches StartRecording with no permission check anywhere in the chain (resolver → RecordingModule → RecordingHardwareAdapter). With the permission revoked (users revoke it, and Android auto-resets permissions of rarely-used apps), MediaRecorder.prepare()/start() fails → EffectFailure → FSM rollback, but the user just sees the tap do nothing (or a stuck Preparing) with no settings redirect. The imeSideAffordance RECORD click branch (:1513-1548) only handles the Active|Paused snapshot case, not the Idle permission case.

<details><summary>Evidence</summary>

ActionResolvers.kt:89-163 has no permission check; grep 'RECORD_AUDIO' hits only OnboardingAdapter, DictateSettingsActivity, and the two legacy IME click sites (:2625, :5086); RecordingHardwareAdapter.kt:200-211 maps start() failure to an EffectFailure action with no user-facing surface.

</details>

**Suggested fix:** Add a permission branch to the imeSideAffordance RECORD/OVERLAY_RECORD click (Idle + permission missing → openSettingsActivity, skip catalog dispatch), mirroring the legacy behaviour, or surface an InfoBar item from the EffectFailure('AllocateMediaRecorder'/'StartMediaRecorder') path.

#### F-036 — InterruptionAction has no producers — call/headset/screen interruption handling is entirely absent despite KDoc claiming listeners are wired

| | |
|---|---|
| **Category** | feature-gap |
| **Severity** | medium |
| **Area** | state-machine / InterruptionModule |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:state-machine` |
| **Spec** | → [`2026-07-02 - recording-interruption-handling.md`](<2026-07-02 - recording-interruption-handling.md>) |

**Files:** `app/src/main/java/net/devemperor/dictate/state/modules/InterruptionModule.kt:22`, `app/src/main/java/net/devemperor/dictate/state/Action.kt:1082`

InterruptionModule is a registered Phase-2 stub (reducer returns null for everything — acceptable), but its KDoc justifies the registration with 'the action sealed leaves are dispatched by the IME-side listeners today' and 'assertCompleteCoverage() would throw if no module claimed them'. The first claim is false: grep finds zero dispatch sites for PhoneCallStateChanged / HeadsetPlugChanged / ScreenStateChanged anywhere in app/src/main — no call-state receiver, no headset-plug receiver, no screen receiver exists. Functionally this means an incoming phone call during an active recording neither pauses nor cancels the recording (the planned Interruption × Recording cascade), the mic keeps capturing through the ringtone/call. The coverage-check argument still justifies registering the stub, but the module KDoc misrepresents the wiring state, and the user-facing interruption behaviour is a genuine gap.

<details><summary>Evidence</summary>

grep 'PhoneCallStateChanged|HeadsetPlugChanged|ScreenStateChanged' across app/src/main → hits only in Action.kt declarations, InterruptionModule KDoc, TestOnlyModules comment. InterruptionModule.kt:22-24 ('registration is required (the action sealed leaves are dispatched by the IME-side listeners today...')). No TelephonyCallback/PhoneStateListener/ACTION_HEADSET_PLUG registration anywhere in the app (grep confirmed no receiver).

</details>

**Suggested fix:** Short-term: correct the InterruptionModule KDoc (listeners are NOT wired; registration exists solely for assertCompleteCoverage + OCP slot). Long-term: implement Phase 2 — an FGS-side TelephonyCallback + headset receiver dispatching the actions, with the reducer cascading CancelRecording/PauseRecording per the documented Coupling-Matrix rows. The Phase-2 implementation needs its own spec (audio-focus interplay, permission READ_PHONE_STATE).

#### F-042 — ReprocessStaging record-button label always shows "Audio 0:00 · Send" — audio duration hardcoded to 0

| | |
|---|---|
| **Category** | feature-gap |
| **Severity** | medium |
| **Area** | render-layout / staging label |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:render-layout` |

**Files:** `app/src/main/java/net/devemperor/dictate/state/layout/TextResolvers.kt:245`, `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:977`, `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt:496`

resolveRecordButtonTextStaging always calls strings.formatStagingLabel(0) with the comment 'The duration field will come from the ReprocessStaging sub-state once Spec 1 §3 adds it; for now the resolver passes 0.' PipelineUiState.ReprocessStaging still carries only (sessionId, transcript) — the duration field was never added. The production formatter (DictatePipelineService.kt:977-985) formats the literal 0 as MM:SS, so every resend-long-press staging session shows 'Audio 0:00 · Send' regardless of the actual audio length. The user sees factually wrong information about the audio they are about to re-submit in a fully shipped UI state (KEYBOARD_REPROCESS_STAGING).

<details><summary>Evidence</summary>

TextResolvers.kt:245-252 (return strings.formatStagingLabel(0) with the ‘for now’ comment); DictateUiState.kt:496-499 ReprocessStaging(sessionId, transcript) — no duration field; DictatePipelineService.kt:982-984 renders %d:%02d from the passed seconds.

</details>

**Suggested fix:** Add audioDurationSeconds to PipelineUiState.ReprocessStaging (stamped by the reducer entering staging — the resend path already knows the audio file; RecordingRepository.extractDurationSeconds exists per PipelineRecovery.kt:124-126) and thread it through resolveRecordButtonTextStaging. Alternatively drop the fake duration from the label until the field exists ('Send' only), so the button stops asserting 0:00.

#### F-054 — No pagination or size limit: full-table history queries run synchronously on the main thread on every keystroke and registry tick

| | |
|---|---|
| **Category** | feature-gap |
| **Severity** | medium |
| **Area** | history |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:history` |
| **Spec** | → [`2026-07-02 - history-pagination-and-scale.md`](<2026-07-02 - history-pagination-and-scale.md>) |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryActivity.java:158`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/dao/SessionDao.kt:62`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/DictateDatabase.kt:121`

HistoryActivity.refreshData() calls sessionDao.getAll()/getByType()/search() — all unbounded full-table SELECTs (SessionDao.kt:62-69, no LIMIT/OFFSET) — synchronously on the main thread (DictateDatabase builds with allowMainThreadQueries(), DictateDatabase.kt:121), then notifyDataSetChanged() over the whole list. refreshData is triggered per keystroke in the SearchView (onQueryTextChange, line 120-124), on every ActiveJobRegistry snapshot change (line 148), and in onResume. Retention makes this worse than it looks: only COMPLETED+inserted rows are pruned after 7 days (deleteInsertedOlderThan); FAILED/CANCELLED rows are kept deliberately forever (PipelineOrphanCleaner doc, 'auto-deleting them would silently lose information') and COMPLETED rows that were never inserted (copy-only, post-processing) also never expire. The table therefore grows without bound and the history screen degrades into main-thread jank over months of use. The search LIKE also does not escape % and _ in user input, so wildcard characters silently change search semantics.

<details><summary>Evidence</summary>

HistoryActivity.java:158-181 refreshData with dao calls on the UI thread; SessionDao.kt:62-69 queries have no LIMIT; DictateDatabase.kt:121 allowMainThreadQueries(); SessionDao.kt:68 LIKE '%' || :query || '%' without ESCAPE clause; PipelineOrphanCleaner.kt:26-28 documents that FAILED/CANCELLED rows are never auto-deleted.

</details>

**Suggested fix:** Move the queries off the main thread (Room Paging3 PagingSource or at minimum LIMIT+load-more with a background executor), debounce the search-text callback, and add an ESCAPE clause with %/_ escaping. Consider a retention cap for never-inserted terminal rows (e.g. 60d FAILED-cleanup already sketched in PipelineOrphanCleaner's doc as follow-up).

#### F-063 — Entering an API key never triggers a model-list fetch — spinner stays empty until provider is reselected

| | |
|---|---|
| **Category** | feature-gap |
| **Severity** | medium |
| **Area** | settings-prefs |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:settings-prefs` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/settings/APISettingsActivity.java:183`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/settings/APISettingsActivity.java:263`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/settings/APISettingsActivity.java:408`

fetchTranscriptionModels (line 262-266) and fetchRewordingModels (line 407-411) early-return with an empty spinner adapter when the API key is empty. The API-key TextWatchers (lines 183-191 and 340-348) only persist the key — they never re-trigger updateTranscriptionUI/updateRewordingUI or the fetch. First-run flow: user opens API settings, provider spinner initialises (fetch runs with empty key → empty model list, no progress, no message), user pastes their API key — and the model spinner stays empty with zero feedback. The user must switch the provider away and back, or close and reopen the activity, to get models listed. This affects OpenAI, Groq and OpenRouter (all fetch-based providers); ElevenLabs is unaffected (hardcoded list) and Custom/Anthropic use free-text model fields.

<details><summary>Evidence</summary>

Read APISettingsActivity.java in full: the only callers of fetchTranscriptionModels/fetchRewordingModels are updateTranscriptionUI (line 232) and updateRewordingUI (line 398), which are invoked only from onCreate-time setup and the provider-spinner onItemSelected listeners. The key watchers at lines 183 and 340 contain only a DictatePrefsKt.put(...).apply() and no UI refresh.

</details>

**Suggested fix:** In the API-key TextWatcher, debounce (e.g. 500 ms after last edit) and re-invoke the fetch for the currently selected provider when the key transitions from empty to non-empty (or changes). Alternatively add an explicit refresh affordance next to the model spinner and a hint text ('enter API key to load models') in the empty state.

#### F-080 — Onboarding API-key step only supports OpenAI/Groq while the app supports six providers

| | |
|---|---|
| **Category** | feature-gap |
| **Severity** | medium |
| **Area** | onboarding |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:rewording-usage-onboarding` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/onboarding/OnboardingAdapter.java:136`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/AIProvider.kt:35`, `/home/lukas/WebStorm/Dictate/app/src/main/assets/dictate_api_key_info_en.html:3`

The final onboarding page routes the entered key by a single prefix check: keys starting with 'gsk_' go to Groq prefs, everything else is written to Pref.TranscriptionApiKeyOpenAI + Pref.RewordingApiKeyOpenAI. The app meanwhile supports ANTHROPIC ('sk-ant-...'), ELEVENLABS ('sk_...'), OPENROUTER ('sk-or-...') and CUSTOM providers (AIProvider.kt). A user who enters an ElevenLabs, Anthropic, or OpenRouter key — all plausible given the app's provider list and Play-store description — gets it silently stored as an OpenAI key, provider stays OPENAI, and the first transcription fails with an auth error with no hint at the cause. The bundled explainer (dictate_api_key_info_en.html) still says 'Dictate supports two speech recognition services'. There is also no skip path: the finish button requires a >=10-char key (OnboardingAdapter:121), forcing Custom-endpoint users (e.g. keyless local servers) to enter a dummy key.

<details><summary>Evidence</summary>

OnboardingAdapter.java:136-144: if (apiKey.startsWith("gsk_")) {...Groq prefs...} else {...OpenAI prefs...} — no other branch. AIProvider.kt enumerates OPENAI/GROQ/ANTHROPIC/ELEVENLABS/OPENROUTER/CUSTOM with capability flags. dictate_api_key_info_en.html line 3: 'supports two speech recognition services'. OnboardingAdapter.java:121: finishBtn.setEnabled(s.toString().trim().length() >= 10).

</details>

**Suggested fix:** Extend the prefix routing (sk-ant- → Anthropic rewording key, sk-or- → OpenRouter, sk_ → ElevenLabs transcription key) or add an explicit provider selector to the onboarding page; add a 'skip / configure later' affordance; update the four dictate_api_key_info_*.html assets to reflect the current provider set.

#### F-110 — Reprocess-with-edit queue editor (Plan 10.6, PromptChooserBottomSheetV2) not shipped — fallback rejects free-text after entry

| | |
|---|---|
| **Category** | feature-gap |
| **Severity** | medium |
| **Area** | history / reprocess |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `seed:history-redesign` |
| **Spec** | → [`2026-07-02 - reprocess-queue-editor.md`](<2026-07-02 - reprocess-queue-editor.md>) |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryDetailActivity.java:168`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryDetailActivity.java:606`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/PromptChooserBottomSheet.java:99`

The planned drag-to-reorder queue editor for 'Reprocess with edit' (Plan 10.6) was never built; the code comment at HistoryDetailActivity.java:169-177 documents the V1 chooser as a stop-gap that reduces the edited queue to exactly one prompt. Two user-visible gaps in the fallback: (1) the shared PromptChooserBottomSheet still renders its free-text field and send button (PromptChooserBottomSheet.java:82-117), but the TAG_REPROCESS_EDIT branch discards free-text choices because JobRequest.queuedPromptIds carries entity IDs only — the user types a prompt, submits, and only then gets a 'Please pick a saved prompt' toast (HistoryDetailActivity.java:606-619); (2) multi-prompt queues cannot be reproduced or edited — a session originally processed with N queued prompts can only be re-run unchanged (direct) or with exactly one prompt (edit).

<details><summary>Evidence</summary>

HistoryDetailActivity.java:169-181 (explicit follow-up comment 'PromptChooserBottomSheetV2 ... tracked as a follow-up'), :606-619 (K2 minimal-fallback comment + toast branch when promptEntityId == null), PromptChooserBottomSheet has no argument to hide the free-text row. strings.xml:371 dictate_history_reprocess_edit_needs_saved_prompt exists solely for this dead-end.
[merged duplicate from sweep:history: 'Reprocess with edit' ships a single-prompt fallback instead of the planned queue editor; free-text prompts are rejected] HistoryDetailActivity.java:168-181 in-code comment: 'Plan 10.6 calls for a full drag-to-reorder queue-editor (PromptChooserBottomSheetV2). That UI is tracked as a follow-up.' and :606-619 'Free-text prompts (promptEntityId == null) are skipped because JobRequest's queuedPromptIds carries entity IDs only'. PromptChooserBottomSheet.java:82/99-117 shows the free-text input is always presented.

</details>

**Suggested fix:** Ship the V2 queue editor (multi-select + reorder + free-text support by extending JobRequest to carry prompt texts, not only entity IDs). Short-term: pass a 'saved prompts only' flag to PromptChooserBottomSheet so the free-text field is hidden for the reprocess-edit tag instead of failing after submission.

#### F-118 — Floating widget card is fully opaque — no (configurable) background transparency

| | |
|---|---|
| **Category** | feature-gap |
| **Severity** | medium |
| **Area** | floating-overlay widget |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `seed:widget-transparency` |
| **Spec** | → [`2026-07-02 - overlay-widget-transparency.md`](<2026-07-02 - overlay-widget-transparency.md>) |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/res/drawable/overlay_background.xml:14`, `/home/lukas/WebStorm/Dictate/app/src/main/res/layout/overlay_5button_layout.xml:49`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayLayoutParamsFactory.kt:101`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayBackend.kt:426`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt:36`, `/home/lukas/WebStorm/Dictate/app/src/main/res/xml/fragment_preferences.xml:134`

IMPORTANT CORRECTION TO THE FEATURE-REQUEST FRAMING: Dictate has NO home-screen AppWidget. The manifest declares no appwidget-provider receiver and a repo-wide grep for AppWidget/RemoteViews returns zero hits. The 'widget' users see is the floating overlay card (SYSTEM_ALERT_WINDOW / TYPE_APPLICATION_OVERLAY) that hosts the record/pause/trash/close buttons over other apps. Consequently NONE of the RemoteViews constraints apply (no setInt-based setBackgroundColor workarounds, no API-31 system_app_widget_background_radius) — the card is an ordinary View tree with full View/Drawable APIs available.

HOW THE BACKGROUND IS DRAWN TODAY: (1) overlay_5button_layout.xml:42-51 — root DraggableOverlayLayout with android:background="@drawable/overlay_background", android:elevation="8dp", android:padding="6dp". (2) overlay_background.xml — a <shape> with a FULLY OPAQUE <solid android:color="?attr/colorSurface"/>, 16dp corner radius, 1dp ?attr/colorOutlineVariant stroke. (3) Theme attrs resolve via ContextThemeWrapper(ctx, R.style.Theme_Dictate) built in OverlayBackend.inflateAndAttach (OverlayBackend.kt:426); Theme.Dictate is Material3.Light with a values-night Material3.Dark variant, so light/dark surface colors already exist. (4) The window itself is ALREADY alpha-capable: OverlayLayoutParamsFactory.create() passes PixelFormat.TRANSLUCENT (line 101) precisely to honour the rounded-corner alpha mask, and FLAG_NOT_TOUCH_MODAL (line 80) already routes outside-touches to the app underneath. So the only opaque element is the drawable's solid fill — transparency is purely a drawable-mutation away, no window-level or manifest change needed.

WHAT IS MISSING: there is no opacity preference (grep for transparen/opacity/alpha in preferences/ returns nothing), no settings UI entry, and no render-path code that touches the card background after inflate.

<details><summary>Evidence</summary>

Read AndroidManifest.xml end-to-end — no appwidget receiver, no android.appwidget metadata. grep -rn 'AppWidget|appwidget|RemoteViews' over app/src/main returned zero hits. overlay_background.xml:14 '<solid android:color="?attr/colorSurface" />' (opaque). overlay_5button_layout.xml:49 'android:background="@drawable/overlay_background"'. OverlayLayoutParamsFactory.kt:96-112 builds LayoutParams with PixelFormat.TRANSLUCENT and doc comment (lines 36-39) confirming the format exists to honour the drawable's alpha mask. OverlayBackend.kt:417-454 inflateAndAttach: ContextThemeWrapper(ctx, R.style.Theme_Dictate) at :426, no background mutation anywhere in render() (:272-345). DictatePrefs.kt has no opacity/transparency Pref (grep). fragment_preferences.xml theme section (:134-152) has theme + accent_color entries only; one existing SeekBarPreference at :90 proves the widget type is already in use.
[merged duplicate from sweep:widget: Widget background is fully opaque — no transparency support despite window already being TRANSLUCENT-capable] Read overlay_background.xml:13-20 (<solid ?attr/colorSurface> — opaque), overlay_5button_layout.xml:42-51 (root background + elevation=8dp), OverlayLayoutParamsFactory.kt:96-101 (PixelFormat.TRANSLUCENT, factory comment at line 37-38 explicitly says TRANSLUCENT 'honours the rounded-corner alpha mask'), OverlayBackend.kt:417-428 (themed inflate). Grepped DictatePrefs.kt for transparency-related keys — none exist.

</details>

**Suggested fix:** Spec seed for 'configurable widget transparency':

1. PREF — add `object WidgetOpacity : Pref<Int>("net.devemperor.dictate.widget_opacity", 100)` to DictatePrefs.kt (percent, 100 = opaque). Add a `SeekBarPreference` (android:min="20", android:max="100", seekBarIncrement=5, showSeekBarValue) to the existing theme PreferenceCategory in fragment_preferences.xml (a SeekBarPreference precedent already exists at :90). Keep a 20% floor so the card never becomes invisible over matching content.

2. STATE — mirror the pref into `ThemingState` (new field `widgetOpacity: Int = 100`) via the existing PipelinePrefMirror + a new `Action.ThemingAction.SetWidgetOpacity` reducer arm; ThemingModule's KDoc states all its fields are Pref-mirrored via C7, so this follows the established pattern and the value arrives at OverlayBackend for free through `render(state, mode)`.

3. APPLY — in OverlayBackend, after inflate (and idempotently per render tick alongside the rendererBundle forwards, OverlayBackend.kt:335-345): resolve the surface color with `MaterialColors.getColor(overlayView, com.google.android.material.R.attr.colorSurface)`, then `(overlayView.background.mutate() as GradientDrawable).setColor(ColorUtils.setAlphaComponent(surface, opacityPercent * 255 / 100))`. Mutate only the fill; keep the 1dp colorOutlineVariant stroke fully opaque so the card boundary stays legible over busy content. Buttons (MaterialButton, Widget.Material3.Button styles from styles_overlay.xml) keep their own opaque backgrounds — text/icons stay readable.

4. WHAT NOT TO DO — do NOT use WindowManager.LayoutParams.alpha (fades the whole window including button labels/icons) and do NOT touch PixelFormat (TRANSLUCENT already set). RemoteViews/API-31 appwidget corner-radius constraints are inapplicable (no AppWidget exists).

5. NIGHT MODE — alpha composes onto whichever colorSurface the theme resolves (light or values-night dark), so dark mode works automatically; but see the separate finding on Pref.Theme being ignored by the overlay — fix that first or the 'dark' surface users expect may be light.

6. EDGE NOTES — (a) elevation shadow: View shadows scale with outline alpha, so at low opacity the 8dp shadow fades out proportionally; acceptable, optionally clamp elevation to 0 below ~50%. (b) The background mutation must re-run after every inflateAndAttach (teardown/attach cycles recreate the drawable). (c) JVM-testable: OverlayBackend already takes a fake OverlayWindow; assert the drawable alpha after render with a given ThemingState.

#### F-007 — Service-side audio-focus listener pauses recording on transient focus loss — contradicts the documented legacy-parity contract; the parity-correct IME listener is dead code

| | |
|---|---|
| **Category** | inconsistency |
| **Severity** | medium |
| **Area** | core-ime / audio focus |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:core-ime` |

**Files:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:1117`, `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:716`

Two OnAudioFocusChangeListener implementations exist for the same job. The IME-side one (DictateInputMethodService.java:716-760) documents and implements the 'legacy parity contract': dispatch granted=false only on hard AUDIOFOCUS_LOSS; ignore LOSS_TRANSIENT / LOSS_TRANSIENT_CAN_DUCK; treat all four GAIN variants as granted=true. The service-side gate (DictatePipelineService.buildAudioFocusGate :1117-1127) — which is the LIVE one on the new path (wired into AudioFocusSubsystemAdapter → ModuleServices.audioFocus, requested by the AudioModule when recording starts) — computes granted = (GAIN || GAIN_TRANSIENT) and dispatches false for everything else, including transient losses and the GAIN_TRANSIENT_MAY_DUCK/EXCLUSIVE variants. The AudioModule cascade pauses an Active recording on a granted true→false transition, so a mere notification ding (transient duck) can pause the user's dictation on the production path. Meanwhile the parity-correct IME listener is attached to a request only used by the never-started legacy RecordingStateController — dead code.

<details><summary>Evidence</summary>

DictatePipelineService.kt:1117-1127 (granted = GAIN || GAIN_TRANSIENT, everything else → false, dispatched unconditionally); DictateInputMethodService.java:727-760 (explicit legacy-parity contract: transient losses deliberately not dispatched); the IME-side request is only consumed by RealAudioFocusGate → RecordingStateController (:766-767), which the C5 KDoc (:3671+) declares never-started on the bound path.

</details>

**Suggested fix:** Port the parity logic (hard-LOSS-only → false; all GAIN variants → true; transient losses no-op) into buildAudioFocusGate's listener — ideally extract one shared classifier function used by both listeners — and delete the dead IME-side listener with the legacy gate once RecordingStateController is retired.

#### F-040 — Two parallel info-bar systems: legacy InfoBarController errors never migrated to InfoBarSelector, so force-expand and prompts-mutex do not apply to them

| | |
|---|---|
| **Category** | inconsistency |
| **Severity** | medium |
| **Area** | render-layout / infobar |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:render-layout` |
| **Spec** | → [`2026-07-02 - infobar-consolidation.md`](<2026-07-02 - infobar-consolidation.md>) |

**Files:** `app/src/main/java/net/devemperor/dictate/state/infobar/InfoBarSelector.kt:37`, `app/src/main/java/net/devemperor/dictate/core/InfoBarController.kt:26`, `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:655`, `app/src/main/java/net/devemperor/dictate/state/render/PromptVisibilityController.kt:129`, `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:1645`, `app/src/main/res/layout/activity_dictate_keyboard_view.xml:305`

Two parallel info-bar systems coexist: the state-derived InfoBarSelector/InfoBarRenderer (renders into overlay_permission_infobar) and the legacy InfoBarController (renders into info_cl). InfoBarSelector.kt:37-39 documents a "Pipeline-Errors (planned Block D.2)" producer meant to replace the nine InfoBarController.showInfo cases, but it was never built — the selector has only 4 producers, and DictateInputMethodService.java:4616 still routes all AI errors (internet_error, quota_exceeded, etc.) to the legacy controller. The UX machinery built for info bars keys exclusively on InfoBarSelector.select(state): LayoutCatalog.forKeyboard's InfoBar force-expand (LayoutCatalog.kt:655-656) and PromptVisibilityController's InfoBar-prompts mutex (PromptVisibilityController.kt:129-136). Legacy error bars therefore get neither the forced two-row expansion nor the prompts hide. Worse than stated: InfoBarController.onStateChanged (its own small-mode suppression) has zero callers since KeyboardStateManager was deleted (CR-DEL), so suppressDisplay is permanently false and legacy error bars render even in single-row/small mode without expansion. One softening nuance: since the 2026-05-22 Z-order fix, info_cl heads the vertical constraint chain (overlay_permission_infobar and prompts_keyboard_cl chained below), so a legacy bar pushes content down rather than being painted over — the gap manifests as cramped/inconsistent rendering, not literal covering. Additionally InfoBarRenderer's KDoc (InfoBarRenderer.kt:27-29, 71-75) documents info_cl/info_tv/info_yes_btn/info_no_btn while the actual wiring (DictateInputMethodService.java:1637-1650) binds overlay_permission_infobar/overlay_permission_message/overlay_perm_grant_btn/overlay_perm_dismiss_btn — doc-vs-wiring drift. Consolidation is tracked as pending task #149.

<details><summary>Evidence</summary>

InfoBarSelector.kt:36-39 (planned-producer comment, never implemented — the selector has only 4 producers: onboarding, pending-insert, partial-recovery, recovery-unfinished); DictateInputMethodService.java:4616 showInfo(errorInfoKey, providerName) still routes all AI errors to the legacy controller; layout XML comment at activity_dictate_keyboard_view.xml:293-302 explicitly notes 'info_cl stays the legacy-InfoBarController container; the two now stack cleanly if both ever show simultaneously'.

</details>

**Suggested fix:** Finish the ADR-0006 migration: add error/update/rate/donate producers to InfoBarSelector (driven by a small pipeline-error state axis + pref-mirror flags), delete InfoBarController, and merge the two containers into one (pending task #149). Short of that, at least fix InfoBarRenderer's KDoc/param naming to the overlay_permission_* views it actually renders into.

#### F-055 — Regenerate/post-process AI calls run on an Activity-scoped executor — silently killed by rotation or leaving the screen, inconsistent with the JobExecutor reprocess path

| | |
|---|---|
| **Category** | inconsistency |
| **Severity** | medium |
| **Area** | history |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:history` |
| **Spec** | → [`2026-07-02 - history-reprocess-hardening.md`](<2026-07-02 - history-reprocess-hardening.md>) |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryDetailActivity.java:91`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryDetailActivity.java:731`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryDetailActivity.java:492`

Two sibling long-running operations in HistoryDetailActivity use different execution models. 'Reprocess' goes through JobExecutor.INSTANCE.start() (line 492), which registers the session in ActiveJobRegistry (JobExecutor.kt:130) and survives the Activity. 'Regenerate'/'Other prompt'/'Post-process' run the blocking orchestrator.complete() on the Activity-instance-scoped regenerateExecutor (line 91), which onDestroy() terminates via shutdownNow() (line 731). The Activity has no android:configChanges entry, so rotation destroys it mid-call: queued work is dropped, the thread is interrupted (whether the in-flight HTTP call aborts depends on the SDK's socket interruptibility — the DB write may or may not land), and the LOADING state is always lost because the recreated instance renders IDLE with no way to reattach. Note: on rotation isFinishing() is false, so the error-Toast guard is skipped only on back-press/finish, not rotation — but on rotation the callbacks target the dead instance anyway. Separately, these local runs are invisible to ActiveJobRegistry: setUiState(LOADING) disables no buttons, reprocess buttons are gated only by ActiveJobRegistry.isActive(sessionId) (lines 285/293), and startHistoryReprocess checks only isAnyActive() (line 454) — so a reprocess (reuseSessionId = same session, line 487) can start while a regenerate is in flight, racing writes to the same processing-step chain. JobExecutor already defines JobRequest.StepRegenerate and JobRequest.PostProcess variants (JobExecutor.kt:160-169), so a registry-tracked path for these operations exists but is bypassed.

<details><summary>Evidence</summary>

HistoryDetailActivity.java:91 'regenerateExecutor = Executors.newSingleThreadExecutor()', :731 'regenerateExecutor.shutdownNow()' in onDestroy, :518-566 regenerateStep body never touches ActiveJobRegistry, :453-456 startHistoryReprocess checks only ActiveJobRegistry.INSTANCE.isAnyActive(). Contrast :492 JobExecutor.INSTANCE.start(this, request) for the reprocess flow.

</details>

**Suggested fix:** Route regenerate/post-process through the same JobExecutor/JobRequest mechanism (a CompletionJob kind) so they survive configuration changes, appear in ActiveJobRegistry, and are mutually exclusive with reprocess. Short of that, at minimum register the session id in ActiveJobRegistry for the duration and use an application-scoped executor.

#### F-069 — Rewording parameter UI is filtered by a stale/empty model id — shown parameters diverge from what is actually sent (worst for Custom provider)

| | |
|---|---|
| **Category** | inconsistency |
| **Severity** | medium |
| **Area** | ai-layer / parameter system |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:ai-layer` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/settings/APISettingsActivity.java:402`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/settings/APISettingsActivity.java:440`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/AIOrchestrator.kt:124`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/model/ParameterRegistry.kt:7`

APISettingsActivity.updateParameterUI(provider, currentRewordingModelId) filters ParameterDefs by currentRewordingModelId, which starts as "" (line 72) and is only ever updated inside the fetched-model spinner listener (line 440). It is never set from the saved model on activity open, never set for the CUSTOM free-text model (watcher at 358-369 saves the model but does not refresh parameters), and never set for Anthropic. At runtime AIOrchestrator.resolveParameters (line 124-125) filters by the REAL model name. Divergences: (a) on activity open with a saved reasoning model (e.g. gpt-5), the UI initially shows temperature/top_p and hides reasoning_effort until a fetch completes — permanently wrong if the API key is empty or the fetch fails; (b) for CUSTOM, the UI is filtered by whatever model id was last selected for a different provider (or ""), so a custom reasoning model gets a temperature slider whose value the orchestrator silently drops, and reasoning_effort is offered/hidden based on the wrong model.

<details><summary>Evidence</summary>

APISettingsActivity.java:72 `private String currentRewordingModelId = "";`; the only assignment is line 440 inside fetchRewordingModels' onItemSelected. updateRewordingUI:402 passes it for every provider incl. CUSTOM/ANTHROPIC. ParameterRegistry.kt:7-15 model filters (isNotReasoningModel/isReasoningModel) and AIOrchestrator.kt:124-137 filter with the actual model from RunnerFactory.getModelName — different input than the UI uses.

</details>

**Suggested fix:** Initialize currentRewordingModelId from the saved model (getSavedRewordingModel / Pref.RewordingCustomModel / Pref.RewordingAnthropicModel) in updateRewordingUI before calling updateParameterUI, and re-run updateParameterUI from the custom-model EditText watcher so the UI filter input matches what AIOrchestrator will use.

#### F-086 — Fresh installs get NO CHECK constraints — Double-Enum enforcement only exists on upgraded databases

| | |
|---|---|
| **Category** | inconsistency |
| **Severity** | medium |
| **Area** | database / schema creation |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:database` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/DictateDatabase.kt:126`, `/home/lukas/WebStorm/Dictate/app/schemas/net.devemperor.dictate.database.DictateDatabase/7.json`, `/home/lukas/WebStorm/Dictate/docs/DATABASE-PATTERNS.md:32`

The Double-Enum pattern's core promise ('You cannot change the Kotlin enum without also changing the SQL schema. The database will reject you.' — DATABASE-PATTERNS.md:32-36, 'enforced mechanically') only holds for devices that upgraded through the migrations. On a fresh install (or after clear-data), Room creates all tables from the entity-derived createSql — Room entities cannot declare CHECK constraints, and schema 7.json's sessions createSql confirms: 'status` TEXT NOT NULL' with no CHECK anywhere. The CHECK constraints on sessions.status/origin/last_error_type exist ONLY inside MIGRATION_2_3 / MIGRATION_3_4 / MIGRATION_5_6 table-recreates, which never run on a fresh install. The DictateDatabase Callback.onCreate (DictateDatabase.kt:126-154) only seeds default prompts and does not retrofit the constraints. Consequence: the install base is split into two schema populations — upgraders reject invalid enum writes loudly, fresh installs silently accept any string, which is exactly the 'silently rots databases' failure mode the pattern was adopted to prevent. Migration tests (MigrationTo4Test) validate the CHECK on the upgrade path, giving false confidence that it exists everywhere.

<details><summary>Evidence</summary>

app/schemas/.../7.json sessions createSql: '`status` TEXT NOT NULL, `origin` TEXT NOT NULL, ... `last_error_type` TEXT' — no CHECK tokens; Room generates createAllTables from this on version-7 fresh installs (migrations 1→7 are skipped). CHECKs only in MigrationTo3.kt:54-61, MigrationTo4Test-covered MigrationTo4.kt, MigrationTo6.kt:44-49. DictateDatabase.kt:126-154 onCreate callback inserts prompts only.

</details>

**Suggested fix:** In RoomDatabase.Callback.onCreate, after super.onCreate, execSQL the same table-recreate that MIGRATION_5_6 performs (or attach equivalent BEFORE INSERT/UPDATE triggers that RAISE(ABORT) on invalid enum strings) so fresh installs carry the same constraints as upgraded devices. Extract the CHECK lists into shared constants used by both the migration and the onCreate hook to prevent drift. Document the chosen approach in DATABASE-PATTERNS.md §Versioning & Schema Exports.

#### F-109 — History regenerate/post-process bypass PromptService — same prompt yields different results than the pipeline

| | |
|---|---|
| **Category** | inconsistency |
| **Severity** | medium |
| **Area** | history / prompt construction |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `seed:history-redesign` |
| **Spec** | → [`2026-07-02 - history-reprocess-hardening.md`](<2026-07-02 - history-reprocess-hardening.md>) |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryDetailActivity.java:523`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryDetailActivity.java:628`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt:705`

HistoryDetailActivity's regenerate and post-process paths call AIOrchestrator.complete() directly, bypassing PromptService. regenerateStep (HistoryDetailActivity.java:523) passes step.getInputText() as user prompt and the raw prompt instruction as SYSTEM prompt (AIOrchestrator.complete(prompt, systemPrompt) at AIOrchestrator.kt:84); runPostProcessing (:628) does the same with outputText. The pipeline instead builds XML-structured prompts (<instruction>/<text-to-process> via PromptBuilder) with the PromptContext.QUEUED system prompt (PipelineOrchestrator.kt:1381/1104 → buildQueuedPrompt) and persists pp.userPrompt as the step's inputText (PipelineOrchestrator.kt:1435). Consequences: (a) regenerating a pipeline-created QUEUED_PROMPT step feeds the already-XML-wrapped prompt back as user prompt while repeating the instruction as system prompt — the instruction is applied twice in a different shape; (b) the QUEUED context system prompt is skipped, so regenerated versions are produced under a different prompt contract than v1 and are not comparable in the versioning UI. Minor correction vs the original finding: the static-response short-circuit point is weak — isStaticResponse is only checked in runStandalonePrompt (PipelineOrchestrator.kt:737); the pipeline's queued-prompt paths do not short-circuit static prompts either, so history does not diverge from the queued pipeline on that sub-point. Note the JobExecutor-routed regenerate path (PipelineOrchestrator.kt:623-626) also double-wraps (passes target.inputText, the built prompt, as text-to-process), though it at least uses the correct QUEUED system prompt.

<details><summary>Evidence</summary>

PipelineOrchestrator.kt:705-706 `val pp = promptService.buildQueuedPrompt(promptText, inputText); aiOrchestrator.complete(pp.userPrompt, pp.systemPrompt)` vs HistoryDetailActivity.java:523/628 `orchestrator.complete(step.getInputText(), promptText)`. executeCompletion persists `pp.userPrompt` as the step's inputText (PipelineOrchestrator.kt:1434-1437), confirming the double-wrap on regenerate. PromptService.isStaticResponse handling exists only in PipelineOrchestrator.kt:737.

</details>

**Suggested fix:** Inject PromptService into HistoryDetailActivity (or better: move regenerate/post-process into SessionManager/JobExecutor) and build the completion via buildQueuedPrompt(rawPromptUsed, originalInput). Persist the raw input separately from the built prompt if regeneration is supposed to re-run the builder.

#### F-119 — Overlay widget card ignores the user's Theme preference — follows system uiMode only, while the keyboard honours Pref.Theme

| | |
|---|---|
| **Category** | inconsistency |
| **Severity** | medium |
| **Area** | floating-overlay widget / theming |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `seed:widget-transparency` |
| **Spec** | → [`2026-07-02 - overlay-widget-transparency.md`](<2026-07-02 - overlay-widget-transparency.md>) |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayBackend.kt:426`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:3305`, `/home/lukas/WebStorm/Dictate/app/src/main/res/values-night/themes.xml:3`

The keyboard surface computes its background from the user preference `Pref.Theme` ("light"/"dark"/"system"): DictateInputMethodService.java:3303-3308 picks dictate_keyboard_background_dark when theme=="dark" OR ("system" && uiMode night). The overlay widget card, however, resolves ?attr/colorSurface through ContextThemeWrapper(PipelineService, R.style.Theme_Dictate) (OverlayBackend.kt:426), which selects values/themes.xml (Material3.Light) vs values-night/themes.xml (Material3.Dark) purely by the SYSTEM uiMode configuration. Pref.Theme is never consulted on the overlay path. Result: a user who sets Theme=dark on a light-system device gets a dark keyboard and a LIGHT floating widget card (and inverse for Theme=light on a dark system) — two surfaces of the same feature disagree.

<details><summary>Evidence</summary>

DictateInputMethodService.java:3303-3308 reads DictatePrefsKt.get(sp, Pref.Theme.INSTANCE) and branches on "dark"/"system"+UI_MODE_NIGHT_YES for the keyboard background. OverlayBackend.kt:417-428 inflates the overlay with ContextThemeWrapper(ctx, R.style.Theme_Dictate) with no Pref.Theme / ThemingState.theme involvement (grep for 'theme' in OverlayBackend.kt shows only the wrapper comment). values-night/themes.xml defines Theme.Dictate parent=Theme.Material3.Dark — selected by configuration only. ThemingState.theme exists in DictateUiState.kt:807 and is available inside OverlayBackend.render(state,…) but is never read there.

</details>

**Suggested fix:** In OverlayBackend.inflateAndAttach, derive the night flag the same way the keyboard does: read state.theming.theme (already carried into render()); when it forces a mode differing from the service configuration, wrap ctx with createConfigurationContext(Configuration(ctx.resources.configuration).apply { uiMode = (uiMode and UI_MODE_NIGHT_MASK.inv()) or (if (dark) UI_MODE_NIGHT_YES else UI_MODE_NIGHT_NO) }) before the ContextThemeWrapper. Extract the 'effective night mode from Pref.Theme' decision (currently inlined at DictateInputMethodService.java:3305) into a small shared helper so both surfaces use one rule. Re-inflate the overlay when ThemingState.theme changes while attached.

#### F-005 — Resend button never reappears after process restart — state.resend.lastAudioExists has no cold-boot seeding

| | |
|---|---|
| **Category** | wiring-gap |
| **Severity** | medium |
| **Area** | core-ime / resend |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:core-ime` |

**Files:** `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt:758`, `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt:638`, `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:3241`

Confirmed as described. The RESEND slot's visibility predicate (LayoutPredicates.kt:56-60) requires state.resend.lastAudioExists, which defaults to false (DictateUiState.kt:758) and is only set true by the PipelineModule post-PipelineDone cascade (PipelineModule.kt:638) or the onShowResend callback (DictateInputMethodService.java:4639-4649) — both post-pipeline events. No boot-time component (PipelineRecovery, PipelinePrefMirror, DictatePipelineService.onCreate) seeds the axis from the persisted Pref.LastFileName/cache file. After any process restart the resend button therefore stays hidden despite the last session's audio existing on disk, until the user completes a new recording (whose PipelineDone re-seeds the axis). The disk-reading imperative fallback at :3253-3262 only runs unbound (pipelineBinder == null) and is overridden by the reactive render once bound; the comment at :3241-3252 holds only intra-process, not across restarts.

<details><summary>Evidence</summary>

DictateUiState.kt:758 lastAudioExists default false; grep 'MarkLastAudio' across app/src/main/java shows only PipelineModule.kt:638 (post-PipelineDone cascade) and DictateInputMethodService.java:4643 (onShowResend) as true-writers; LayoutPredicates.kt:49-57 gates RESEND visibility on the axis; DictateInputMethodService.java:3253 imperative fallback gated on pipelineBinder == null.

</details>

**Suggested fix:** Seed the axis at service boot: in DictatePipelineService.onCreate (or PipelineRecovery), check File(cacheDir, Pref.LastFileName).exists() (or the last KEYBOARD session's audio path) and dispatch ResendAction.MarkLastAudio(exists) before the first render. Fix the stale comment at DictateInputMethodService.java:3241.

#### F-008 — 'Keep screen awake while recording' no longer engages on the new recording path — updateKeepScreenAwake only driven by the dead legacy controller state

| | |
|---|---|
| **Category** | wiring-gap |
| **Severity** | medium |
| **Area** | core-ime / recording UX |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:core-ime` |

**Files:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:4177`, `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:2556`

On the production (orchestrator-bound) recording path the keep-screen-awake behavior never engages. updateKeepScreenAwake (DictateInputMethodService.java:4177, sets View.setKeepScreenOn + IME-window FLAG_KEEP_SCREEN_ON) is reachable only from restoreUiState:2556, gated on the legacy recordingStateController state being non-Idle — but that controller is never started post-C5/CR-DEL (isEffectiveRecordingIdle KDoc :3675-3681; recordingStateController.startRecording has zero call sites), so the state is permanently Idle. The original driver (recordingStateController callback onKeepScreenAwakeChanged → updateKeepScreenAwake) was removed in commit c54100d as already-dead code. No orchestrator-side observer or wake lock replaces it: repo-wide there is no PowerManager/newWakeLock, and OverlayLayoutParamsFactory.kt:30 explicitly omits FLAG_KEEP_SCREEN_ON citing a "PipelineService wake-lock" that does not exist in DictatePipelineService.kt. Result: the screen can dim and lock during an active dictation; the mic FGS keeps recording alive (no data loss), so this is a UX regression. Note: the behavior was unconditional legacy behavior, not a user-facing setting.

<details><summary>Evidence</summary>

grep 'updateKeepScreenAwake' across app/src/main/java: definition at :4177, self-recursion at :4180, and the single caller at :2556 inside restoreUiState's legacy-state guard. rewireCallbacks (:2508-2526) documents that the legacy callback block which used to drive it was removed because 'its callbacks never fired anyway'.

</details>

**Suggested fix:** Drive updateKeepScreenAwake from the orchestrator state — e.g. inside the existing RecordingActivityTickerObserver start/stop transitions or a small dedicated observer on state.recording Active|Paused (distinctUntilChanged), symmetric with the other post-cutover observers.

#### F-022 — InsertionPolicy.animate is never read — TextCommitter animates purely off the InstantOutput pref, so KEYSTROKE-policy inserts animate too

| | |
|---|---|
| **Category** | wiring-gap |
| **Severity** | medium |
| **Area** | insertion-keyboard |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:insertion-keyboard` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/insertion/Insertion.kt:56`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:4770`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:1705`

InsertionPolicy declares `animate` (Insertion.kt:56-57, "Char-by-char slow-output animation when InstantOutput pref is off") and the presets set it deliberately (PIPELINE/RESEND animate=true, KEYSTROKE animate=false — KDoc and EmojiController.kt:119 both say KEYSTROKE is "instant"). But the field is dead: InsertionService.insert() reads every other policy flag but never `animate`, and the TextCommitter interface (InsertionCollaborators.kt:38-40) takes only (ic, text), so the policy structurally cannot influence the commit mechanics. The concrete committer (DictateInputMethodService.java:4769-4780) branches solely on Pref.InstantOutput. Consequence for users who disabled InstantOutput (default is on): all KEYSTROKE inserts are slow-animated against the documented policy — emoji-picker commits (EmojiController.kt:122, every emoji is 2+ code units), the enter-overlay character, and most visibly the Pending-Insert accept (DictateInputMethodService.java:1705-1707), which commits the ENTIRE transcript with KEYSTROKE policy and therefore gets the slow animation (total duration linear in text length — not quadratic as originally claimed; delay per char is index*base as an absolute offset) plus tail-drop-on-stale-IC risk with no resume fallback (KEYSTROKE has resumeOnFailure=false; the dropped tail is only logged). Single-char keystrokes (space, one qwertz char) are effectively unaffected since the first char's delay is 0.

<details><summary>Evidence</summary>

Insertion.kt:55-92 (field + presets), InsertionService.kt:40-76 (insert() never touches policy.animate), InsertionCollaborators.kt:38-40 (TextCommitter interface takes only ic+text — the policy cannot reach it), DictateInputMethodService.java:4768-4780 (committer reads Pref.InstantOutput only). Grep for `.animate`/`policy.animate` across app/src/main and app/src/test: only the three preset assignments.

</details>

**Suggested fix:** Extend TextCommitter.commit(ic, text, animate: Boolean) (or pass the policy), have InsertionService forward request.policy.animate, and let the committer choose the instant path when animate=false regardless of the pref. Alternatively delete the field if legacy-parity animation-for-everything is actually wanted — but then fix the KEYSTROKE KDoc; the Pending-Insert case suggests the flag is the intended behavior.

#### F-039 — InfoBarController.onStateChanged has no caller since KeyboardStateManager deletion — small-mode/QWERTZ/emoji suppression is dead

| | |
|---|---|
| **Category** | wiring-gap |
| **Severity** | medium |
| **Area** | render-layout / infobar (legacy surface) |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:render-layout` |
| **Spec** | → [`2026-07-02 - infobar-consolidation.md`](<2026-07-02 - infobar-consolidation.md>) |

**Files:** `app/src/main/java/net/devemperor/dictate/core/InfoBarController.kt:41`, `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:1002`

InfoBarController.onStateChanged(contentArea, isSmallMode) has zero callers since KeyboardStateManager was deleted in commit cc5803e (B5-CR-DEL); pre-deletion, KeyboardStateManager.kt:179 was the sole caller. suppressDisplay is therefore permanently false and the documented contract (InfoBarController.kt:23-24: never show in small mode or QWERTZ/emoji content area) is dead. The legacy info bar (update/rate/donate at DictateInputMethodService.java:3363/3370/3372 and all pipeline error types via onPipelineError at 4616) can now surface while small mode or QWERTZ/emoji is active: info_cl is a root-level view outside the content-area containers, ContentAreaController/PromptVisibilityController do not manage it, and none of the dismiss() call sites fire on content-area or small-mode changes.

<details><summary>Evidence</summary>

InfoBarController.kt:37-46 defines suppressDisplay + onStateChanged; grep for 'onStateChanged' across app/src/main matches only KeyboardLayoutManager.onStateChanged (its own state fan-out), ActiveJobRegistryObserver, RecordingStateController and the InfoBarController definition itself — no call site passes (contentArea, isSmallMode). DictateInputMethodService.java:4993-4998 still calls infoBarController.showInfo(type) unconditionally; the CR-DEL comment at DictateInputMethodService.java:1046-1053 confirms KeyboardStateManager was deleted.
[merged duplicate from sweep:core-ime: InfoBarController.onStateChanged has zero callers since KeyboardStateManager deletion — the small-mode/QWERTZ/emoji suppression contract is dead] InfoBarController.kt:22-46 (contract + suppressDisplay + onStateChanged); grep 'onStateChanged(ContentArea|infoBarController.onStateChanged' across app/src/main/java returns only the definition.

</details>

**Suggested fix:** Either wire the suppression reactively (a small observer on state.layout.contentArea + state.layout.smallMode calling infoBarController.onStateChanged, same pattern as EditBarAudioFocusObserver at DictateInputMethodService.java:1726), or fold the legacy showInfo cases into InfoBarSelector producers (the planned Block D.2) so the state-driven mutex/force-expand machinery applies. Until then, at minimum call onStateChanged from the SetContentArea/SmallMode dispatch sites.

#### F-053 — Persisted session error details (last_error_type/last_error_message) are never displayed in history — FAILED sessions show only a generic badge

| | |
|---|---|
| **Category** | wiring-gap |
| **Severity** | medium |
| **Area** | history |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:history` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/entity/SessionEntity.kt:77`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryDetailActivity.java:248`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/DurationHealingJob.kt:53`

The pipeline diligently persists failure context — SessionManager.finalizeFailed writes last_error_type + last_error_message on every transcription/pipeline failure (SessionManager.kt:108-110, called from PipelineOrchestrator.kt:354/365/521/529), and DurationHealingJob writes 'Audio file not found during healing'. But no history UI ever reads these columns: grep over history/ finds zero references to lastError*. A FAILED recording session renders in the detail view as just the audio row (or nothing if the file is gone) with no explanation of what failed; the list shows only the generic 'Failed' badge. The DurationHealingJob comment (lines 53-55) even claims 'in the detail view, "Audio file missing"' is shown to the user — that rendering does not exist. Only step-level errors (ProcessingStepEntity.errorMessage) are rendered (HistoryDetailActivity.java:407-410); session-level errors, which cover the most common failure (transcription/API errors, missing audio), are invisible.

<details><summary>Evidence</summary>

grep -rn 'lastError|last_error' over app/src/main/java/net/devemperor/dictate/history returns no hits; SessionEntity.kt:77-78 defines the columns; writers confirmed at SessionManager.kt:108-110 and DurationHealingJob.kt:57-62. HistoryDetailActivity.buildPipeline/buildRecordingPipeline (248-330) never constructs an error step from the session row. DurationHealingJob.kt:53-55 documents a UI behaviour ('The user sees ... "Audio file missing"') that is not implemented.
[merged duplicate from seed:history-redesign: Persisted session failure reason (last_error_type/message) is never shown in the history UI] grep for lastErrorMessage readers: only state/infobar/InfoBarSelector.kt (partial-recovery seconds extraction) — zero hits in history/. HistoryDetailActivity.buildRecordingPipeline (280-330) reads status only for button gating; errorText is populated exclusively from ProcessingStepEntity.errorMessage (line 407-410). HistoryAdapter.applyStatusBadge FAILED branch (166-171) sets a fixed 'Failed' string.

</details>

**Suggested fix:** In buildRecordingPipeline (and the other builders), when session status is FAILED, append a pipeline step (or reuse errorText on the audio row) rendering sessionErrorTypeOrNull + lastErrorMessage via the existing dictate_history_error string. Also surface the 'partial:N' marker written by resolvePipelineAudio for partial recoveries.

#### F-093 — OverlayPermissionOnboardingActivity is declared and fully implemented but unreachable; the notification meant to hand off to it opens generic settings instead

| | |
|---|---|
| **Category** | wiring-gap |
| **Severity** | medium |
| **Area** | manifest-resources |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:manifest-resources` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/AndroidManifest.xml:117`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/PipelineNotificationCoordinator.kt:244`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/onboarding/OverlayPermissionOnboardingActivity.kt:26`

OverlayPermissionOnboardingActivity is declared in the manifest (AndroidManifest.xml:117-119, exported=false, with a comment claiming it covers "deep-link, notification action, manual launch for tests" entry points) and is fully implemented + Robolectric-tested, but has zero production launch sites — only tests reference it. The one notification that should hand off to an overlay-permission surface, NotificationStatus.OverlayPermissionRequired (produced live: OverlayModule.kt:401 adds RequestOverlayPermissionNotification to the runtime permission-loss cascade, reducer at :196-201 emits Effect.NotifyOverlayPermissionRequired, handled at :259-269), sets its content intent via overlaySettingsIntent() whose KDoc (PipelineNotificationCoordinator.kt:238-243) promises a deep-link to overlay settings with a fallback, but whose body is literally `= contentIntent()` (line 244) — landing the user in DictateSettingsActivity, which has no SYSTEM_ALERT_WINDOW re-grant surface (only an unrelated "overlay_characters" text preference). Tapping the alert notification therefore gives no direct path to re-grant. Minor caveat vs. the original wording: the user is not fully stranded app-wide — the in-IME info-bar explainer (InfoBarSelector.kt:56-76, Grant → RequestOverlayPermission → ACTION_MANAGE_OVERLAY_PERMISSION side-channel in DictateInputMethodService.java:1680-1691) still offers a re-grant path when the keyboard is open, but the notification (per OverlayModule's own KDoc "the only channel reachable when the user is in another app") does not fulfill its documented purpose.

<details><summary>Evidence</summary>

grep for OverlayPermissionOnboardingActivity across app/src/main hits only the manifest declaration, a strings.xml comment, and test files — zero production launch sites. PipelineNotificationCoordinator.kt:239-244: KDoc describes a settings deep-link with fallback, implementation is `private fun overlaySettingsIntent(): PendingIntent? = contentIntent()`. contentIntent() (lines 229-236) opens DictateSettingsActivity. Producer path verified: OverlayModule.kt:200 emits Effect.NotifyOverlayPermissionRequired, handled at OverlayModule.kt:259 → NotificationStatus.OverlayPermissionRequired → PipelineNotificationCoordinator.build sets setContentIntent(overlaySettingsIntent()).

</details>

**Suggested fix:** Point overlaySettingsIntent() at an explicit Intent(context, OverlayPermissionOnboardingActivity::class.java) PendingIntent (same-app, so exported=false is fine), which delivers the explainer + Allow flow the activity was built for. Alternatively delete the activity + its layout/strings if the standalone surface is abandoned, and fix the overlaySettingsIntent KDoc.

#### F-114 — History delete (single + all) not guarded against actively-processing sessions

| | |
|---|---|
| **Category** | wiring-gap |
| **Severity** | medium |
| **Area** | history / job concurrency |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `seed:history-redesign` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryActivity.java:78`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryDetailActivity.java:454`

HistoryActivity's long-press delete (lines 78-90, sessionDao.deleteById) and 'Delete all' (lines 128-139, sessionDao.deleteAll) run unconditionally, including for a session an active JobExecutor pipeline is writing to. Because transcriptions/processing_steps/completion_log/text_insertions declare Room FKs with ON DELETE CASCADE to sessions, subsequent pipeline inserts throw SQLiteConstraintException on the job thread (caught by JobExecutor's catch-all at JobExecutor.kt:187-194 → finalizeFailed, so no app crash, but the job dies mid-flight) and status/finalOutput UPDATEs silently no-op on the deleted row. This is inconsistent with the guard discipline in HistoryDetailActivity (isAnyActive gate at :454, jobActive gating of audio-row buttons at :285-301). Additionally, regenerate/other-prompt/post-process buttons are shown unconditionally (showRegenerate(true) at :423) and regenerateStep/runPostProcessing perform no ActiveJobRegistry check, so a user can regenerate a step concurrently with a running reprocess job writing the same version chain.

<details><summary>Evidence</summary>

HistoryActivity.java:78-90 and :128-139 have no ActiveJobRegistry reference (import exists at line 24 for the badge observer only). HistoryDetailActivity.java:454 shows the guard pattern used for reprocess; addProcessingSteps (:414-426) sets showRegenerate/showOtherPrompt true without consulting jobActive (computed at :285 but only used for audio-row buttons).

</details>

**Suggested fix:** Block (or confirm-with-cancel) deletion of sessions with ActiveJobRegistry.isActive(id); for 'Delete all' either exclude active sessions or cancel their jobs first. Extend the jobActive gate to the regenerate/other-prompt/post-process buttons.


### 3.4 Low (48 findings)

#### F-016 — BluetoothScoManager.startSco does not cancel a previous pending timeout — stale timeout can kill a newer SCO wait

| | |
|---|---|
| **Category** | bug |
| **Severity** | low |
| **Area** | recording-audio / bluetooth-sco |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:recording-audio` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/BluetoothScoManager.kt:121`

startSco() posts a new timeoutRunnable and overwrites the field reference without removing a previously posted runnable (cancelTimeout is only called from the CONNECTED broadcast branch and release()). If startSco is called twice without an intervening connect/release (e.g. reconnect() after pause, or two quick record attempts), the first runnable is still scheduled; when it fires, isWaitingForSco is true again (set by the second call), so it stops Bluetooth SCO and reports onScoFailed prematurely for the second attempt — the second wait is cut short by the first attempt's leftover timer. Additionally, the receiver ignores SCO_AUDIO_STATE_ERROR entirely (falls through the when), leaving a wait hanging until timeout.

<details><summary>Evidence</summary>

BluetoothScoManager.kt:121-141 — startSco sets isWaitingForSco=true, posts timeoutRunnable via handler.postDelayed without a preceding cancelTimeout(); timeoutRunnable guard checks only isWaitingForSco (131-137), which cannot distinguish attempt generations. cancelTimeout() called only at lines 96 (CONNECTED) and 148 (release). Receiver when-branch (91-104) handles CONNECTED/DISCONNECTED only.

</details>

**Suggested fix:** Call cancelTimeout() at the top of startSco() before posting the new runnable (and treat SCO_AUDIO_STATE_ERROR like DISCONNECTED/failed). This is worth fixing together with the receiver-registration wiring gap.

#### F-024 — BackspaceSwipeHandler fallback (extract failed) sets base cursor 0 — releasing the swipe jumps the caret to document start

| | |
|---|---|
| **Category** | bug |
| **Severity** | low |
| **Area** | insertion-keyboard |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:insertion-keyboard` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/keyboard/BackspaceSwipeHandler.kt:93`

When getExtractedText returns null (WebViews and custom editors commonly do not support extraction), the handler still enters swipe mode with swipeWordBoundaries=[0] and swipeBaseCursor=0 (lines 93-96). No selection can grow (maxSteps=0), but on ACTION_UP the swipeSelectedSteps==0 branch runs ic.setSelection(swipeBaseCursor, swipeBaseCursor) = setSelection(0,0) (line 148), teleporting the caret to the start of the field. The same reset also fires in the move branch (lines 123-125). A user who accidentally drags left on backspace in such a host loses their cursor position.

<details><summary>Evidence</summary>

BackspaceSwipeHandler.kt:85-96 (fallback sets boundaries=[0], base=0 whenever ic==null OR et?.text==null; ic can be non-null while extraction fails), :123-125 and :148 (setSelection(swipeBaseCursor, swipeBaseCursor) on step-0 / release).

</details>

**Suggested fix:** When extraction fails, either do not enter swipe-select mode at all (keep normal click/long-press handling), or mark the base cursor as unknown (-1) and skip every setSelection call while it is unknown.

#### F-025 — QWERTZ backspace long-press repeat deletes one extra character on finger lift (click fires after repeat because the long-press is never consumed)

| | |
|---|---|
| **Category** | bug |
| **Severity** | low |
| **Area** | insertion-keyboard |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:insertion-keyboard` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/keyboard/QwertzKeyboardView.kt:245`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/keyboard/QwertzKeyboardController.kt:97`

For repeatable functional keys (BACKSPACE) the view's OnTouchListener returns false (line 271: only COMMIT_TEXT consumes), letting the default View click machinery run. Long-press repeat is implemented by a custom postDelayed Runnable (300 ms, line 256-259) -> onKeyLongPress -> AcceleratingRepeatHandler.start(deleteOneCharacter). Because no OnLongClickListener is registered, View.performLongClick returns false, mHasPerformedLongPress stays false, and ACTION_UP therefore ALSO fires performClick -> onKeyAction(BACKSPACE) -> handleBackspace -> deleteOneCharacter. Net effect: after every long-press delete session on the QWERTZ backspace, releasing the finger deletes one additional character beyond what the repeat already removed (plus an extra vibrate). Compare BackspaceSwipeHandler/main keyboard, where release only cancels the cascade.

<details><summary>Evidence</summary>

QwertzKeyboardView.kt:238-272 (click listener for non-COMMIT_TEXT keys + touch listener returning false for them; custom repeat Runnable posted on ACTION_DOWN, removed on ACTION_UP, but no OnLongClickListener and no click suppression), QwertzKeyboardController.kt:97-113 (onKeyLongPress starts repeat, onKeyReleased stops it) and :177-180 (handleBackspace deletes one char on the click that still fires). Android View semantics: performClick on ACTION_UP is suppressed only when performLongClick returned true.

</details>

**Suggested fix:** Track repeat activation: when the posted long-press Runnable fires, set a flag on the key (e.g. v.setTag) and swallow the following click in the click listener (or register setOnLongClickListener { true } for repeatable keys and drive the repeat from there).

#### F-043 — RecordingAnimationController: with animations disabled, Interrupted recordings never show the frozen timer and Active is visually identical to Idle

| | |
|---|---|
| **Category** | bug |
| **Severity** | low |
| **Area** | render-layout / recording animation |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:render-layout` |

**Files:** `app/src/main/java/net/devemperor/dictate/state/render/RecordingAnimationController.kt:158`, `app/src/main/java/net/devemperor/dictate/state/render/RecordingAnimationController.kt:124`

Two reduced-motion (animationsEnabled() == false, i.e. Pref animations off) gaps: (1) In the Interrupted branch the whole start()+pause()+onTimerTick(curr.elapsedMs) sequence is inside `if (animationsEnabled())` — so a recovered recording renders with only the dimmed background and never shows the frozen '0:08' timer, defeating the explicit 2026-05-22 requirement that Interrupted renders 'exactly like Paused (frozen timer at elapsedMs)' (RecordingState.Interrupted KDoc, DictateUiState.kt:226-231). The timer text lives inside the AmplitudeVisualizerDrawable which only exists after animation.start(), but seeding the paused visualizer is a static render, not a motion effect — it should not be gated on the animations pref. (2) In the Active branch the reduced-motion else-arm applies accentColorProvider() with the comment 'still mark the button visually as active via the brighter peak colour' — but Idle applies exactly the same applyBackground(accentColorProvider()) (line 113), so there is no visual difference between idle and actively recording for reduced-motion users; only the label text changes.

<details><summary>Evidence</summary>

RecordingAnimationController.kt:105-168 onState(): Idle arm line 110-114 applies accent; Active arm lines 124-134 else-branch applies the same accent; Interrupted arm lines 144-165 gates start/pause/onTimerTick on animationsEnabled() and only stopBackgroundAnimator()+dimmed() run unconditionally.

</details>

**Suggested fix:** Move the Interrupted start()+pause()+onTimerTick(elapsedMs) seeding out of the animationsEnabled() gate (a paused visualizer with a static timer involves no motion), and give the reduced-motion Active state a distinct static treatment (e.g. the dimmed colour or a red-tinged tint) so 'recording' is distinguishable from 'idle'.

#### F-044 — PipelineStepRowRenderer per-row timer ignores StepRowItem.startedAtMs — restarts at 0:00 on re-attach/rotation mid-step

| | |
|---|---|
| **Category** | bug |
| **Severity** | low |
| **Area** | render-layout / pipeline step rows |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:render-layout` |

**Files:** `app/src/main/java/net/devemperor/dictate/state/render/PipelineStepRowRenderer.kt:264`, `app/src/main/java/net/devemperor/dictate/core/ElapsedTimer.kt:32`

PipelineStepRowRenderer.kt:105-108 documents the per-RUNNING-row live timer as 'driven by an ElapsedTimer anchored at the row's startedAtMs', and StepRowItem.startedAtMs exists precisely as the reducer-stamped wall-clock anchor (DictateUiState.kt:371-374). But startActiveTimerFor() calls ElapsedTimer.start(views.mainHandler) { ... }, and ElapsedTimer anchors at its own construction time (startTime = SystemClock.elapsedRealtime() at creation) — the row's startedAtMs is never consulted. Normally the timer starts right when the RUNNING row is appended so the error is negligible, but on an IME view recreate / rotation mid-pipeline the backend detach→reset()→re-attach path re-inflates the rows and restarts the timer, so a step that has been running for e.g. 30s shows 0.0s again and the displayed per-step duration under-reports until the reducer finalises the row with the correct durationMs.

<details><summary>Evidence</summary>

PipelineStepRowRenderer.kt:264-269 startActiveTimerFor uses ElapsedTimer.start with no anchor argument; ElapsedTimer.kt:16-22 startTime = SystemClock.elapsedRealtime() at construction; ImeViewBackend.detach (ImeViewBackend.kt:225-238) calls pipelineStepRowRenderer.reset() which wipes rows, and KeyboardLayoutManager.attachBackend:101-112 immediately re-renders the current state → diffStepHistory re-inflates and restarts the timer.

</details>

**Suggested fix:** Pass the row's startedAtMs into startActiveTimerFor and seed the tick text with (ReducerContext-now-derived) offset — e.g. add an optional initialElapsedMs to ElapsedTimer.start (onTick(initial + sinceStart)) or compute the offset from the Running.elapsedMs/startedAtMs fields already in state — so a re-attach resumes the display where the step actually is.

#### F-046 — OverlayResetHandler force-hides the enter-overlay character strip on every state emit, including mid-gesture in KEYBOARD mode

| | |
|---|---|
| **Category** | bug |
| **Severity** | low |
| **Area** | render-layout / overlay reset |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:render-layout` |

**Files:** `app/src/main/java/net/devemperor/dictate/state/render/OverlayResetHandler.kt:97`, `app/src/main/java/net/devemperor/dictate/state/render/SpecialTouchHandlerInstaller.kt:319`

OverlayResetHandler.render() writes overlay_characters_ll.visibility = GONE unconditionally on every render-tick (backendType = null → every state emit), with no check of viewMode/widget state. Its own KDoc scopes the defensive reset to 'the edge case where a ViewMode transition (KEYBOARD → WIDGET/HOVER) interrupts the touch sequence' — but the reset also fires while the user is mid-long-press on Enter in plain KEYBOARD mode with the character strip legitimately VISIBLE (EnterOverlayHandler shows it during the touch sequence). Any unrelated state emit during that press — pendingSessions DB subscriber update, Bluetooth-SCO phase change, audio-focus change, a pipeline StepStarted/StepCompleted while a pipeline runs in the background — hides the strip under the user's finger; the character-selection gesture then dead-ends until release. Emits during a press are plausible in the concurrent-pipeline flow the app explicitly supports (dictating result arriving while typing).

<details><summary>Evidence</summary>

OverlayResetHandler.kt:84 backendType = null (every tick) and :97-111 render() → writeVisibility(strip, View.GONE) with both state and mode ignored; EnterOverlayHandler sets the same overlay_characters_ll visible during the ENTER touch sequence (SpecialTouchHandlerInstaller.kt:311-325); DictateUiState KDoc documents pendingSessions as 'mutated frequently by the DB subscriber' (DictateUiState.kt:20-22), i.e. emits are not gesture-synchronised.

</details>

**Suggested fix:** Gate the reset on the condition it was written for: only write GONE when the overlay/widget surface is actually active (state.widget is Visible or !state.imeViewVisible), or expose an 'gesture in progress' check from EnterOverlayHandler and skip the reset while a touch sequence is live.

#### F-071 — Usage tracking can add -1 audio seconds when duration metadata extraction fails

| | |
|---|---|
| **Category** | bug |
| **Severity** | low |
| **Area** | ai-layer / usage tracking |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:ai-layer` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/AIOrchestrator.kt:64`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/runner/OpenAICompatibleRunner.kt:94`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/DictateUtils.java:163`

DictateUtils.getAudioDuration returns -1 when MediaMetadataRetriever fails (corrupt/odd container, missing metadata). Both transcription runners put that value into TranscriptionResult.audioDurationSeconds unchecked, and AIOrchestrator.transcribe passes it straight into usageDao.addUsage, whose upsert does 'audio_time = audio_time + :audioTime' (UsageDao.kt:25-33). A failed metadata read therefore decrements the accumulated per-model audio-time counter, silently corrupting usage/cost statistics.

<details><summary>Evidence</summary>

DictateUtils.java:157-169 returns -1 on null metadata or exception. OpenAICompatibleRunner.kt:94-99 and ElevenLabsTranscriptionRunner.kt:122-127 assign it directly. AIOrchestrator.kt:64-69 calls usageDao.addUsage(result.modelName, result.audioDurationSeconds, 0, 0, provider.name) with no clamping. UsageDao.kt:25-33 adds the raw value.

</details>

**Suggested fix:** Clamp in AIOrchestrator.transcribe: usageDao.addUsage(..., result.audioDurationSeconds.coerceAtLeast(0), ...) — or make getAudioDuration return 0 and reserve -1 handling at call sites that need the sentinel. Add a unit test feeding a -1 duration result.

#### F-078 — UsageAdapter never resets row visibility on rebind — recycled holders show wrong/empty rows

| | |
|---|---|
| **Category** | bug |
| **Severity** | low |
| **Area** | usage |
| **Status** | Confirmed (adversarially verified, confidence: high) |
| **Found by** | `sweep:rewording-usage-onboarding` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/usage/UsageAdapter.java:73`, `/home/lukas/WebStorm/Dictate/app/src/main/res/layout/item_usage.xml:27`

UsageAdapter.onBindViewHolder (UsageAdapter.java:73-83) only ever sets TableRow visibility to GONE and never resets any row to VISIBLE. All three data rows (item_usage_input_tokens, item_usage_output_tokens, item_usage_audio_time) default to VISIBLE in item_usage.xml (no android:visibility attributes). With a single view type and default RecyclerView recycling (UsageActivity.java:56-61, plain LinearLayoutManager, no DiffUtil/stable IDs), a recycled holder carries GONE state from its previous bind: a holder last bound to a token model (audio row GONE) rebound to a transcription model then hides input+output too, leaving only the model-name header with no data rows; the reverse case hides all token rows for a completion model. Cards are not fully blank — the model-name row is always set — but all metric rows vanish. Trigger requires actual recycling, i.e. enough distinct models to scroll: the usage table is aggregated per model_name via UPSERT (UsageDao.addUsage), so typical users have only a handful of rows and never scroll, which limits real-world impact. Fix: set the correct rows to VISIBLE in each branch (or use setVisibility(condition ? VISIBLE : GONE) for all three rows unconditionally).

<details><summary>Evidence</summary>

UsageAdapter.java:73-83 — if branch sets itemInputTokensTr/itemOutputTokensTr GONE, else branch sets itemAudioTimeTr GONE; no branch ever calls setVisibility(View.VISIBLE) on any row. item_usage.xml declares no android:visibility attributes (all default VISIBLE), verified via grep. RecyclerView in UsageActivity.java:56-61 uses default RecycledViewPool with a single view type.

</details>

**Suggested fix:** In onBindViewHolder set all three rows' visibility explicitly in both branches (audio branch: audio VISIBLE + tokens GONE; token branch: tokens VISIBLE + audio GONE).

#### F-081 — Clear-queue chip's empty-queue graying is immediately overwritten by the shared enable/alpha pass

| | |
|---|---|
| **Category** | bug |
| **Severity** | low |
| **Area** | rewording |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:rewording-usage-onboarding` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/rewording/PromptsKeyboardAdapter.java:238`

For the clear-queue control chip (id == -4) the bind code sets setEnabled(hasQueue) and alpha 0.35 when the queue is empty (lines 238-241, comment 'Gray out when queue is empty'). But the code that follows the if/else chain (lines 256-258) computes shouldDisable = disableNonSelectionPrompts && model.getId() >= 0 && !requiresSelection — always false for id -4 — and unconditionally calls setEnabled(!shouldDisable) and setAlpha(1f), re-enabling the chip and restoring full alpha. The intended empty-queue disabled/grayed rendering therefore never survives a bind; the chip always looks and behaves active even with nothing to clear.

<details><summary>Evidence</summary>

PromptsKeyboardAdapter.java:238-241 (boolean hasQueue = !queuedPromptOrder.isEmpty(); setEnabled(hasQueue); setAlpha(hasQueue ? 1f : 0.35f)) followed at 256-258 by boolean shouldDisable = disableNonSelectionPrompts && model.getId() >= 0 && ...; holder.promptBtn.setEnabled(!shouldDisable); holder.promptBtn.setAlpha(shouldDisable ? 0.5f : 1f); — executed for every non-chip holder including id -4.

</details>

**Suggested fix:** Skip the generic enabled/alpha pass for control chips that manage their own state (guard lines 256-258 with model.getId() != -4, or compute the -4 enabled state after the generic pass).

#### F-090 — usage table keyed by model_name only — same model id on two providers merges rows and misattributes the provider

| | |
|---|---|
| **Category** | bug |
| **Severity** | low |
| **Area** | database / usage tracking |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:database` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/entity/UsageEntity.kt:9`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/dao/UsageDao.kt:28`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/usage/UsageAdapter.java:66`

UsageEntity's primary key is model_name alone; model_provider is a plain column. UsageDao.addUsage uses 'ON CONFLICT(model_name) DO UPDATE' and deliberately does not update model_provider on conflict. If the user runs the same model id through two providers — realistic with the CUSTOM provider or Groq/OpenAI-compatible endpoints sharing ids like 'whisper-large-v3' — all usage accumulates in one row permanently labelled with whichever provider wrote first (UsageAdapter.java:64-72 renders that provider next to the model name). Per-provider usage statistics are silently wrong, and there is no way to split them afterwards.

<details><summary>Evidence</summary>

UsageEntity.kt:9-11 (@PrimaryKey model_name); UsageDao.kt:24-36 (addUsage upsert omits model_provider from the UPDATE SET); AIOrchestrator.kt:64-69 and 103-109 call addUsage with provider.name; UsageAdapter.java:66 resolves the display provider from the stored column.
[merged duplicate from sweep:rewording-usage-onboarding: usage table keyed on model_name only — provider never updated on merge, cross-provider usage conflated] UsageEntity.kt:9-11 (@PrimaryKey model_name), UsageDao.kt:25-33 (DO UPDATE SET audio_time/input_tokens/output_tokens — model_provider absent from the update list), UsageAdapter.java:66-77 (provider label from stored modelProvider; if inputTokens != 0 the audio row is hidden regardless of audioTime).

</details>

**Suggested fix:** Make (model_name, model_provider) the composite primary key (table-recreate migration merging is impossible retroactively — carry existing rows as-is) and change the upsert to ON CONFLICT(model_name, model_provider). Update getByModelName callers accordingly.

#### F-095 — Overlay record button keeps static 'Start recording' contentDescription across Send/Pipeline states; overlay_send/overlay_send_cd strings orphaned

| | |
|---|---|
| **Category** | bug |
| **Severity** | low |
| **Area** | manifest-resources |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:manifest-resources` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/res/layout/overlay_5button_layout.xml:82`, `/home/lukas/WebStorm/Dictate/app/src/main/res/values/strings.xml:462`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/render/SlotRenderer.kt:71`

After OVERLAY_RECORD and OVERLAY_SEND were merged into one state-driven button (overlay_5button_layout.xml header comment, lines 5-14), the button's android:contentDescription stays fixed at @string/overlay_record_cd ('Start recording') while its visible text is resolver-driven ('Send', 'Sending …', '2/3 ↵ 0:08'). SlotRenderer only writes icon/text/enabled (SlotRenderer.kt:71-83) and no renderer touches contentDescription (only EditBarController.kt:309 does, for a different button). Because contentDescription overrides text for accessibility services, TalkBack announces 'Start recording' on a button whose tap action is Send/Cancel in Active/Paused states. The strings overlay_send and overlay_send_cd (strings.xml, defined in all 4 locales) are referenced nowhere except a TextResolvers.kt:94 comment saying they are 'safe to drop' — dead resources from the pre-merge layout.

<details><summary>Evidence</summary>

overlay_5button_layout.xml:82 hard-codes contentDescription=@string/overlay_record_cd on overlay_record_btn; header comment lines 7-9 documents the record/send merge with state-driven text. grep for contentDescription in state/render + state/layout hits only EditBarController.kt:309. grep for overlay_send/overlay_send_cd outside strings.xml hits only the TextResolvers.kt:94 comment.

</details>

**Suggested fix:** Remove the static contentDescription from overlay_record_btn (letting TalkBack read the state-driven text), or add a contentDescription resolver to the slot system that mirrors the text resolver. Delete overlay_send/overlay_send_cd from all four strings.xml files per the TextResolvers note.

#### F-107 — PipelineStepAdapter: stale itemView click listener from recycled SOURCE_SESSION rows

| | |
|---|---|
| **Category** | bug |
| **Severity** | low |
| **Area** | history / PipelineStepAdapter |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `seed:history-redesign` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/PipelineStepAdapter.java:248`

onBindViewHolder sets holder.itemView.setOnClickListener(...) only when the bound step is Type.SOURCE_SESSION (lines 248-250). There is no else branch clearing the listener. When a ViewHolder that previously rendered a SOURCE_SESSION row is recycled for a TRANSCRIPTION/PROCESSING/FINAL_OUTPUT row, the whole card remains clickable and tapping it opens the old source-session's detail activity. Only manifests when the pipeline list is long enough to recycle (long chains, small screens), which makes it an intermittent misnavigation.

<details><summary>Evidence</summary>

PipelineStepAdapter.java:248-250 — `if (step.type == PipelineStep.Type.SOURCE_SESSION && step.sourceSessionId != null) { holder.itemView.setOnClickListener(...); }` with no else. All other per-row buttons in the same method (play/regenerate/etc., lines 196-245) do have symmetric hide/clear branches; itemView is the single exception.

</details>

**Suggested fix:** Add `else { holder.itemView.setOnClickListener(null); holder.itemView.setClickable(false); }`.

#### F-120 — Attached overlay widget never reacts to configuration changes (system dark-mode flip keeps stale colors)

| | |
|---|---|
| **Category** | bug |
| **Severity** | low |
| **Area** | floating-overlay widget / lifecycle |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `seed:widget-transparency` |
| **Spec** | → [`2026-07-02 - overlay-widget-transparency.md`](<2026-07-02 - overlay-widget-transparency.md>) |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:792`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayBackend.kt:318`

The overlay view is inflated once (OverlayBackend.render: `if (overlayView == null) inflateAndAttach()`) and stays attached until teardown — the widget is deliberately sticky and can stay on screen indefinitely (WidgetModule sticky-widget lifecycle). DictatePipelineService declares no onConfigurationChanged override, and no code path re-inflates the overlay on a uiMode change. If the system switches dark/light (auto night schedule is common) while the widget is visible, the card and its Material buttons keep the previous theme's colors until the user closes and reopens the widget. Same staleness applies to density/font-scale changes for the once-computed fixed window width (OverlayLayoutParamsFactory.kt:93 computes widthPx from displayMetrics.density at create() time).

<details><summary>Evidence</summary>

grep for 'onConfigurationChanged|Configuration' in DictatePipelineService.kt returns zero hits. OverlayBackend.kt:318 'if (overlayView == null) inflateAndAttach()' — inflate happens only when the view is null; teardownOverlay() is only triggered by permission loss (:283-285) or detach(). WidgetModule.kt:39-52 documents that the widget stays visible until explicit user close, so multi-hour attachment across a day/night boundary is a realistic scenario.

</details>

**Suggested fix:** Override onConfigurationChanged in DictatePipelineService (Service receives it without any manifest flag); when (newConfig.uiMode and UI_MODE_NIGHT_MASK) differs from the last-seen value and the overlay backend is attached, call a new OverlayBackend.reinflate() that runs teardownOverlay() and lets the next render tick re-attach with the fresh configuration (position is preserved via the OverlayPosition prefs, DictatePrefs.kt:123-126). Recompute layout params in the same hook to cover density changes.

#### F-011 — Dead code cluster in DictateInputMethodService: getDictateButtonText, onBackspaceClicked, dead IME-side audio-focus listener, stale onRecordClicked routing comment

| | |
|---|---|
| **Category** | code-quality |
| **Severity** | low |
| **Area** | core-ime |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:core-ime` |

**Files:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:5008`, `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:5338`, `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:710`, `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:5063`

Several remnants of the render cutover remain: (1) getDictateButtonText() (:5008-5020) has no callers — the catalog textResolver owns the record label; (2) onBackspaceClicked() (:5338) has no callers ('kept for any non-catalog caller' that doesn't exist); (3) the elaborate legacy-parity audio-focus listener built in initLongLivedObjects (:710-761) is attached to an AudioFocusRequest consumed only by RealAudioFocusGate → the never-started legacy RecordingStateController, so it never fires on the production path (see the related inconsistency finding — the parity logic it encodes is exactly what the live service-side listener is missing); (4) the comment at :5063-5065 claims onRecordClicked is 'invoked via the ImeViewBackend imeSideAffordance hook (RECORD click)' but the affordance lambda (:1466-1550) never calls onRecordClicked — only the QWERTZ record callback (:973) does; the misleading comment hides that ReprocessStaging-send and the permission check inside onRecordClicked are unreachable from the main record button.

<details><summary>Evidence</summary>

grep across app/src/main/java: getDictateButtonText and onBackspaceClicked have no call sites (only a comment reference at :3271); imeSideAffordance lambda body :1466-1550 contains no onRecordClicked call; audioFocusRequest is only passed into RealAudioFocusGate for recordingStateController (:766-767), never-started per :3671+ KDoc.
[merged duplicate from seed:delete-selection: Dead selection-aware click handler onBackspaceClicked kept 'for non-catalog callers' that do not exist] DictateInputMethodService.java:5335-5340 (definition + 'kept for any non-catalog caller' comment); grep for onBackspaceClicked across app/src/main matches only the definition; contrast with onBackspaceLongClicked which IS invoked (DictateInputMethodService.java:1491 via ImeViewBackend imeSideAffordance, ImeViewBackend.kt:488-513).

</details>

**Suggested fix:** Delete getDictateButtonText and onBackspaceClicked; retire the IME-side audio-focus listener together with the legacy gate (after porting its parity logic to the service-side gate); correct the routing comment at :5063 to name the QWERTZ callback as the only caller.

#### F-027 — BackspaceSwipeHandler KDoc documents the selection delete as 'CursorMove(1)' but the code (correctly) uses DeleteSelection

| | |
|---|---|
| **Category** | code-quality |
| **Severity** | low |
| **Area** | insertion-keyboard |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:insertion-keyboard` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/keyboard/BackspaceSwipeHandler.kt:28`

The class KDoc (@param insertionService, lines 28-31) states the swipe-release selection delete 'routes through it as a CursorMove(1) ControlOp', but the implementation at line 145 dispatches ControlOp.DeleteSelection. Since ControlOp.CursorMove and DeleteSelection have materially different semantics (and CursorMove has the selection-destroying quirk documented in the Insertion.kt types), the stale doc actively misleads the next maintainer of exactly the surface this review flags elsewhere. Additionally onBackspaceClicked (DictateInputMethodService.java:5338-5340) is dead — its comment says 'kept for any non-catalog caller' but grep finds zero callers.

<details><summary>Evidence</summary>

BackspaceSwipeHandler.kt:28-31 (KDoc: 'routes through it as a `CursorMove(1)` ControlOp') vs :145 (insertionService()?.control(ControlOp.DeleteSelection)). grep -rn onBackspaceClicked: only the definition at DictateInputMethodService.java:5338.

</details>

**Suggested fix:** Fix the KDoc to name ControlOp.DeleteSelection; delete onBackspaceClicked or route the catalog Backspace action through it (which would also resolve the high-severity backspace inconsistency).

#### F-045 — Dead LayoutStrings fields: pauseLabel / resumeLabel / overlaySend are built in production but no resolver reads them

| | |
|---|---|
| **Category** | code-quality |
| **Severity** | low |
| **Area** | render-layout / catalog strings |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:render-layout` |

**Files:** `app/src/main/java/net/devemperor/dictate/state/layout/TextResolvers.kt:111`, `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:1041`

LayoutStrings.pauseLabel and resumeLabel were added for the B3.4 'record-button morphs into Pause/Resume toggle' feature; that design was superseded on 2026-05-22 by the dedicated OVERLAY_PAUSE slot (comments in LayoutCatalog.kt:573-601 and TextResolvers.kt:225-230 both state the morph rule is gone). No resolver reads pauseLabel/resumeLabel any more — grep finds only the declaration (TextResolvers.kt:111-124) and the production construction (DictatePipelineService.kt:1041-1042, whose comment still describes the superseded morph behaviour). overlaySend is already @Deprecated with a removal note but the production call site never passed it, so it too is inert. Their KDocs actively describe behaviour the code no longer has, which will mislead the next reader of the overlay record-button text path.

<details><summary>Evidence</summary>

Grep for pauseLabel|resumeLabel|overlaySend across app/src/main matches only TextResolvers.kt (declaration) and DictatePipelineService.kt:1041-1042 (construction); resolveOverlayRecordButtonText (TextResolvers.kt:224-236) composes only send/sending/formatPipelineLabel/formatPreparingLabel/record/dictateButtonText.

</details>

**Suggested fix:** Delete pauseLabel, resumeLabel and overlaySend from LayoutStrings and the two production ctor args, and remove the stale B3.4 morph comment at DictatePipelineService.kt:1036-1040. (Also fix the neighbouring doc drift: FeatureToggles KDoc says 'Five product toggles / all five' but the class has four fields, DictateUiState.kt:791-800.)

#### F-059 — Suppress-bit state axis is dead: written and reset by three modules, read by nobody since the 2026-05-23 sticky-widget refactor

| | |
|---|---|
| **Category** | code-quality |
| **Severity** | low |
| **Area** | widget/state machine |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:widget` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayBackend.kt:285`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/modules/OverlayModule.kt:149`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/modules/WidgetModule.kt:316`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/Action.kt:627`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt:687`

OverlayState.suppressAutoOverlayUntilNextSession is still actively maintained by a whole cascade machine — W2 CloseWidget emits SuppressAutoOverlayUntilNextSession (WidgetModule.kt:316), ViewModeModule.kt:230 emits it too, OverlayModule reducer arms set/clear it (OverlayModule.kt:149-160), and W7/W8 observers in RecordingModule.kt:1170, WidgetModule.kt:284 and OverlayModule.kt:324 emit ResetSuppressBit — but a full-repo grep finds zero readers: the two former consumers (WidgetModule W3 auto-show gate and OverlayBackend render step 2) were both removed in the sticky-widget refactor. OverlayBackend.kt:306-308 itself documents: 'The suppress-bit is now an effectively dead axis; W2 still writes it, no one reads it. A later cleanup can remove the writes + the axis.' Additionally Action.WidgetAction.ResetSuppressBit (Action.kt:627) is declared, has a permanent null reducer arm (WidgetModule.kt:254), and is never dispatched anywhere — a fully dead action. The dead machinery misleads readers into thinking closing the widget suppresses a re-open, and every recording start/resume still pays cascade dispatches for a bit with no effect.

<details><summary>Evidence</summary>

grep -rn for suppressAutoOverlayUntilNextSession / SuppressAutoOverlayUntilNextSession / ResetSuppressBit over app/src/main: all hits are writers (OverlayModule.kt:149-160), emitters (WidgetModule.kt:284/316, RecordingModule.kt:1170, OverlayModule.kt:324, ViewModeModule.kt:230), the state field (DictateUiState.kt:687), and comments. No conditional reads the field. OverlayBackend.kt:285-308 contains the explicit 'dead axis' admission. Action.WidgetAction.ResetSuppressBit has no dispatch site outside its declaration.

</details>

**Suggested fix:** Execute the cleanup the code already promises: delete the OverlayState field, the two OverlayAction arms, the WidgetAction.ResetSuppressBit dead action, and the three observer emissions (W7/W8 + ViewModeModule/WidgetModule cascade emissions), plus the DispatchCloseWidgetCascade branch that emits SuppressAutoOverlayUntilNextSession (keeping only the PauseRecording branch). Update the WidgetModule W2/W7/W8 KDoc table accordingly.

#### F-060 — RipplePulseAnimation is dead code and both PulseLayout wrappers are inert — layout XML still claims they provide the recording pulse visuals

| | |
|---|---|
| **Category** | code-quality |
| **Severity** | low |
| **Area** | widget/animations |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:widget` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/widget/RipplePulseAnimation.kt:14`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/widget/PulseLayout.kt:105`, `/home/lukas/WebStorm/Dictate/app/src/main/res/layout/overlay_5button_layout.xml:56`, `/home/lukas/WebStorm/Dictate/app/src/main/res/layout/activity_dictate_keyboard_view.xml:91`

After the 2026-05-23 pulse-retirement (the RecordingAnimationController KDoc documents that the red PulseLayout ripple was replaced by a breathing button tint), RipplePulseAnimation — the only class that calls PulseLayout.startPulse/pausePulse/resumePulse/stopPulse — is never instantiated anywhere in production or tests. Consequently the two <net.devemperor.dictate.widget.PulseLayout> wrappers (overlay_5button_layout.xml:56-84 with app:pulseCount/pulseDuration/pulseStartAlpha/pulseMaxRadiusFactor/pulseStyle attributes, and activity_dictate_keyboard_view.xml:91-122) are inert FrameLayouts whose pulse configuration is dead data — no code ever starts a pulse on them. The overlay layout's header comment (lines 8-13) still tells the reader the button 'is wrapped in a PulseLayout so the BorderGlow + amplitude animation matches the keyboard record_btn' — but the actual BorderGlow renders as the button's foreground drawable and has nothing to do with PulseLayout. Also RecordingAnimation.resume() is never called in production (RecordingAnimationController's Paused→Active arm calls animation.start(), which rebuilds the AmplitudeVisualizerDrawable and clears the accumulated waveform on every resume), leaving resume() a dead interface member.

<details><summary>Evidence</summary>

grep for RipplePulseAnimation over app/src: only its own file. grep for startPulse/pausePulse/resumePulse/stopPulse: only PulseLayout.kt itself and RipplePulseAnimation.kt. RecordingAnimationController.kt:124-134 Active arm calls animation.start() (never .resume()); BorderGlowAnimation.start() recreates the visualizer drawable each call. Layout comments read at overlay_5button_layout.xml:8-13/53-55.

</details>

**Suggested fix:** Delete RipplePulseAnimation.kt; replace the two PulseLayout XML wrappers with plain FrameLayout (or unwrap the button entirely — check MotionScene constraint references to record_pulse_layout first) or, if PulseLayout is kept for a future setting, remove the dead pulse attributes and fix the misleading layout header comments. Optionally make Paused→Active call animation.resume() to preserve waveform history instead of rebuilding via start().

#### F-089 — Stale schema-lifecycle KDoc: SessionEntity/MigrationTo5 still reference the removed effectiveAudioFilePaths bridge and a 'MIGRATION_5_6 drops audio_file_path' plan that shipped as something else

| | |
|---|---|
| **Category** | code-quality |
| **Severity** | low |
| **Area** | database / entity documentation |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:database` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/entity/SessionEntity.kt:55`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo5.kt:14`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/dao/SessionDao.kt:161`

SessionEntity.audioFilePaths KDoc (SessionEntity.kt:53-57) states 'New code reads through [effectiveAudioFilePaths]; the legacy [audioFilePath] column will be removed in MIGRATION_5_6'. Both halves are now wrong: (a) the effectiveAudioFilePaths accessor no longer exists on the entity — Block A3 removed it (confirmed by PipelineSessionRepoAdapterTest.kt:55 'no more effectiveAudioFilePaths' and the updateAudioFilePaths KDoc in SessionDao.kt:196); the dangling [effectiveAudioFilePaths] link also appears in MigrationTo5.kt:14,23 and SessionDao.kt:161. (b) MIGRATION_5_6 shipped as the RECORDING_INTERRUPTED CHECK-widening recreate (MigrationTo6.kt), not the legacy-column drop; MigrationTo7.kt:698-706 documents the drop as an unscheduled future cleanup ('the M-series doesn't go beyond M7 for now'). A reader following the entity KDoc gets a false picture of the dual-column lifecycle.

<details><summary>Evidence</summary>

grep effectiveAudioFilePaths across app/src/main hits only KDoc/comments (SessionEntity.kt:55, MigrationTo5.kt:14+23, SessionDao.kt:161+196, MigrationTo7.kt:12, PipelineSessionRepoAdapter.kt:230) — no declaration exists; MigrationTo6.kt implements the 5→6 migration as the status-CHECK recreate.

</details>

**Suggested fix:** Rewrite the SessionEntity.audioFilePaths KDoc to the current state (canonical column; legacy audio_file_path frozen at allocate-time, still read by findOrphanedTerminalAudio/findAllAudioFilePaths/markLegacyAudioSessionsFailed; drop pending a future migration), and fix the four dangling @link references.

#### F-121 — Dead API<26 TYPE_PHONE fallback in OverlayLayoutParamsFactory (minSdk is 26)

| | |
|---|---|
| **Category** | code-quality |
| **Severity** | low |
| **Area** | floating-overlay widget |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `seed:widget-transparency` |
| **Spec** | → [`2026-07-02 - overlay-widget-transparency.md`](<2026-07-02 - overlay-widget-transparency.md>) |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayLayoutParamsFactory.kt:71`

OverlayLayoutParamsFactory.create() branches on Build.VERSION.SDK_INT >= O to choose TYPE_APPLICATION_OVERLAY vs the deprecated TYPE_PHONE (lines 71-76), and the class KDoc (lines 42-44) documents the API<26 fallback rationale. The app's minSdk is 26 (app/build.gradle:13), so SDK_INT >= O is always true and the TYPE_PHONE branch plus its @Suppress("DEPRECATION") is unreachable dead code that misleads readers into thinking pre-O devices are supported.

<details><summary>Evidence</summary>

app/build.gradle:13 'minSdk 26'. OverlayLayoutParamsFactory.kt:71-76 contains the SDK_INT branch with the deprecated TYPE_PHONE arm; KDoc lines 42-44 document the fallback as a live path.

</details>

**Suggested fix:** Delete the branch — use WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY unconditionally and drop the KDoc fallback paragraph (or keep a one-line note 'minSdk 26 ⇒ TYPE_APPLICATION_OVERLAY always available').

#### F-070 — Transcription temperature parameter is half-built: registry + options field + ElevenLabs support exist, but no UI and no orchestrator wiring

| | |
|---|---|
| **Category** | feature-gap |
| **Severity** | low |
| **Area** | ai-layer / parameter system |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:ai-layer` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/model/ParameterRegistry.kt:76`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/AIOrchestrator.kt:53`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/runner/TranscriptionOptions.kt:10`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/runner/OpenAICompatibleRunner.kt:83`

ParameterRegistry.getTranscriptionParameters (OPENAI_TRANSCRIPTION / ELEVENLABS_TRANSCRIPTION temperature defs) has zero callers in the codebase, despite its KDoc claiming 'This method is used by the Settings UI (Task 6.1) to dynamically generate parameter fields for transcription' — APISettingsActivity only calls getCompletionParameters. TranscriptionOptions.temperature is never populated anywhere: AIOrchestrator.transcribe builds TranscriptionOptions without it (lines 53-61), and even if it were set, OpenAICompatibleRunner.transcribe never forwards it to TranscriptionCreateParams (lines 83-89 only set file/model/responseFormat/language/prompt). Only ElevenLabsTranscriptionRunner (line 94-96) would forward it. Net effect: a fully-specced transcription-temperature feature is unreachable end-to-end, and the KDoc is stale.

<details><summary>Evidence</summary>

grep for getTranscriptionParameters returns only the declaration (ParameterRegistry.kt:76). grep for 'TranscriptionOptions(' shows the single construction site AIOrchestrator.kt:54, which passes no temperature. OpenAICompatibleRunner.kt:80-102 builds TranscriptionCreateParams without .temperature(). ElevenLabsTranscriptionRunner.kt:94-96 does forward options.temperature — dead branch.

</details>

**Suggested fix:** Either finish the feature (transcription parameter section in APISettingsActivity backed by new Pref keys, resolved in AIOrchestrator.transcribe, forwarded via paramsBuilder.temperature() in OpenAICompatibleRunner) or delete getTranscriptionParameters, TranscriptionOptions.temperature, and the ElevenLabs forwarding, and fix the stale KDoc.

#### F-072 — Whisper style prompt is silently dropped for the ElevenLabs transcription provider

| | |
|---|---|
| **Category** | feature-gap |
| **Severity** | low |
| **Area** | ai-layer / transcription |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:ai-layer` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/runner/ElevenLabsTranscriptionRunner.kt:85`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/prompt/PromptService.kt:22`

PromptService.resolveWhisperStylePrompt is resolved for every transcription (DictateInputMethodService.java:4114) and passed through AIOrchestrator into TranscriptionOptions.stylePrompt. OpenAICompatibleRunner forwards it as the Whisper 'prompt' parameter (OpenAICompatibleRunner.kt:89), but ElevenLabsTranscriptionRunner never reads options.stylePrompt (multipart body at lines 85-102 contains file/model_id/language_code/temperature/keyterms only). The ElevenLabs API has no equivalent prompt field, so this is an API limitation — but the Settings UI (SystemPromptsActivity style-prompt section) gives no hint that the predefined/custom punctuation style prompt has zero effect when ElevenLabs is the transcription provider. Users configuring a custom style prompt and Scribe will see it silently ignored.

<details><summary>Evidence</summary>

ElevenLabsTranscriptionRunner.kt:80-108: options.stylePrompt is never referenced; grep confirms stylePrompt only consumed in OpenAICompatibleRunner.kt:89. Style prompt UI in SystemPromptsActivity has no provider-conditional hint (keyterms section exists for ElevenLabs, style-prompt section is provider-agnostic).

</details>

**Suggested fix:** Show a note in SystemPromptsActivity when Pref.TranscriptionProvider == ELEVENLABS that the style prompt only applies to Whisper-compatible providers (keyterms are the ElevenLabs equivalent), or map the style-prompt terms into scribe_v2 keyterms as a best-effort fallback.

#### F-082 — Usage cost display removed but cost strings and provider strings remain orphaned

| | |
|---|---|
| **Category** | feature-gap |
| **Severity** | low |
| **Area** | usage |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:rewording-usage-onboarding` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/res/values/strings.xml:188`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/usage/UsageActivity.java:63`

strings.xml still ships dictate_usage_cost (%1$.2f $), dictate_usage_total_cost ('Estimated total cost: ...'), dictate_usage_model_cost_description, dictate_usage_buy_new_credits, and dictate_usage_model_provider_openai/groq/custom, but no layout or code references any of them — the usage screen (UsageActivity + item_usage.xml) only shows raw token counts and total audio time. The upstream app's per-model estimated-cost feature was dropped in this fork without removing its resources, and no replacement cost math exists (AIOrchestrator tracks tokens/seconds only). Either the cost feature is an intentional cut (then the strings are dead weight and translation burden) or it is a regression users of the upstream app will notice.

<details><summary>Evidence</summary>

grep for total_cost/usage_cost/model_cost/buy_new_credits/usage_model_provider across app/src/main returns hits only inside values*/strings.xml — zero layout or Java/Kotlin consumers. activity_usage.xml contains only usage_rv, usage_no_usage_tv, usage_reset_btn, usage_total_audio_time_tv. UsageActivity.java:63-66 renders only total audio time.

</details>

**Suggested fix:** Decide the feature's fate: either reintroduce a per-model price table (model → $/1M input, $/1M output, $/min audio) and render dictate_usage_cost + dictate_usage_total_cost rows, or delete the orphaned strings (all locales) to stop the drift.

#### F-102 — Resend cascade hard-codes MarkLastAudio(exists=true) on cancel path (documented F-21 deferral)

| | |
|---|---|
| **Category** | feature-gap |
| **Severity** | low |
| **Area** | state / pipeline-resend cascade |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `seed:emoji-delete` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt:43`

The Pipeline->Resend cascade emits MarkLastAudio with exists=true regardless of whether PipelineDone was success or cancel, because the cancel-path audio-file deletion ('Phase-2 cancel-cascade') was never implemented. The module KDoc (PipelineModule.kt:43-51) documents this as F-21 and prescribes the fix (emit MarkLastAudio(exists=false) on the cancel branch once deletion lands). Until then the Resend button can be armed against an audio file that a future cancel-cleanup would have removed. Currently self-consistent only because the deletion is also missing - the two halves of the Phase-2 feature must land together.

<details><summary>Evidence</summary>

PipelineModule.kt:43-51 KDoc: 'F-21 (2026-05-15): the cascade hard-codes exists = true regardless of whether PipelineDone was success vs cancel. The cancel-path audio-file deletion is not yet implemented (Phase-2 cancel-cascade)'. Confirmed no cancel-path deletion exists by the same comment; this is a tracked deferral surfacing as a latent feature gap.

</details>

**Suggested fix:** When implementing the Phase-2 cancel-cascade, land both halves atomically: cancel-path audio deletion in the pipeline module effect handler AND the exists=false branch in the Pipeline->Resend cascade, plus a cascade-order unit test (the file already references DictateOrchestratorCascadeOrderTest as the pattern). Until then, no code change needed - keep the tracking note.

#### F-115 — Audio playback controls half-built: no pause/stop, empty completion listener, unused pause string

| | |
|---|---|
| **Category** | feature-gap |
| **Severity** | low |
| **Area** | history / audio playback |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `seed:history-redesign` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryDetailActivity.java:432`, `/home/lukas/WebStorm/Dictate/app/src/main/res/values/strings.xml:342`

playAudio() starts a MediaPlayer with no way to pause or stop — tapping play again restarts from zero (previous player released, new one created), and the play button icon never changes state. The OnCompletionListener body is an empty placeholder with the comment 'Reset play button state if needed' (lines 441-443), i.e. the state-reset was planned but never written. strings.xml carries dictate_history_pause ('Pause', line 342) with zero references in code or layouts — the pause half of the feature was scoped (the strings.xml comment at line 437 even calls it 'the history-row label') but never wired. Long recordings can only be listened to end-to-end or restarted.

<details><summary>Evidence</summary>

HistoryDetailActivity.java:432-447; grep for R.string.dictate_history_pause / @string/dictate_history_pause across app/src/main: no hits outside strings.xml. Play button binding in PipelineStepAdapter.java:196-201 never swaps the icon.

</details>

**Suggested fix:** Track playback state in the adapter step (isPlaying), toggle play/pause on tap using the existing dictate_history_play/dictate_history_pause strings, and reset the icon in the completion listener. Release the player in onPause as well (currently only onDestroy).

#### F-017 — Rolling-interval constants drifted: partial-recovery loss estimate (30 s/segment) is 2x the actual 15 s segment interval; adapter KDoc claims wrong pref default

| | |
|---|---|
| **Category** | inconsistency |
| **Severity** | low |
| **Area** | recording-audio / rolling-segments |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:recording-audio` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/CacheDirAudioFileRepository.kt:301`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt:184`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/RecordingHardwareAdapter.kt:460`

Pref.RollingSegmentIntervalSec default was halved to 15 s (documented decision, DictatePrefs.kt:172-181), but two dependents still assume 30 s: (1) CacheDirAudioFileRepository.DEFAULT_LOST_SECONDS_PER_SEGMENT = 30.0 whose KDoc claims it 'matches the Rolling-Segment default interval' — the user-facing partial-recovery InfoBar therefore overstates lost audio by 2x per skipped segment ('partial:30' for an at-most-15 s loss); (2) RecordingHardwareAdapter.DEFAULT_ROLLING_INTERVAL_MS = 30_000 whose KDoc claims it 'matches Pref.RollingSegmentIntervalSec.default (30 s)' — production always passes the resolved pref so only doc/tests are affected, but the stated invariant is false.

<details><summary>Evidence</summary>

DictatePrefs.kt:184-185 default 15L ('halved from the historic ADR-0007 30 s estimate', 172-174). CacheDirAudioFileRepository.kt:292-301: DEFAULT_LOST_SECONDS_PER_SEGMENT = 30.0 with 'Matches the Rolling-Segment default interval (Pref.RollingSegmentIntervalSec, B1.3)'. RecordingHardwareAdapter.kt:460-467: DEFAULT_ROLLING_INTERVAL_MS 30_000L with 'matches Pref.RollingSegmentIntervalSec.default (30 s)'. Estimated seconds flow into last_error_message 'partial:N' (PipelineOrchestrator.kt:1192) and the InfoBar text arg (InfoBarSelector.kt:139-148).

</details>

**Suggested fix:** Derive the per-segment loss estimate from the actual configured interval (thread rollingIntervalMs / the pref value into the repository or into PipelineAudioResult) instead of a hard-coded 30.0; at minimum update both constants + KDocs to 15 s so the InfoBar estimate matches reality.

#### F-035 — State→SP write seam only half-adopted: OverlayModule and LayoutModule still write services.sharedPrefs directly instead of services.prefs.persist()

| | |
|---|---|
| **Category** | inconsistency |
| **Severity** | low |
| **Area** | state-machine / ModuleServices pref-persistence |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:state-machine` |

**Files:** `app/src/main/java/net/devemperor/dictate/state/modules/OverlayModule.kt:217`, `app/src/main/java/net/devemperor/dictate/state/modules/LayoutModule.kt:178`, `app/src/main/java/net/devemperor/dictate/state/ModuleServices.kt:152`

The PrefPersistenceService KDoc (ModuleServices.kt:146-181) declares services.prefs.persist() the single canonical State→SP write seam and explicitly names the direct sharedPrefs.edit() writes in LayoutModule/OverlayModule as the pre-cleanup anti-pattern that Chunk 3.0 retires. AudioModule (AudioModule.kt:318) and RecordingModule (RecordingModule.kt:992/1040/1046) were migrated; OverlayModule.runEffect (PersistOverlayPosition/MarkOnboardingShown/MarkOnboardingPermanentlyDismissed, lines 217-238) and LayoutModule.runEffect (PersistSmallMode/PersistSingleRowMode, lines 178-182) still use services.sharedPrefs.edit()...apply() directly. Two write paths for the same concern; the documented seam is not the actual seam for a third of the writers, and tests for those modules cannot use the recording-fake path the KDoc describes.

<details><summary>Evidence</summary>

ModuleServices.kt:152-160 ('Before Chunk 3.0 ... a) module effects writing directly through services.sharedPrefs.edit() (e.g. [LayoutModule], [OverlayModule])'). grep 'services.sharedPrefs' → OverlayModule.kt:217/229/235, LayoutModule.kt:179/182 (plus LanguageModule.kt:148, which legitimately delegates to LanguageResolver). grep 'services.prefs.persist' → RecordingModule (3x), AudioModule (1x) only.

</details>

**Suggested fix:** Migrate the five remaining OverlayModule/LayoutModule effect writes to services.prefs.persist(Pref.X, value) (PersistOverlayPosition may keep a batched variant by adding a multi-put persist overload, or emit four persists). Then tighten the ModuleServices KDoc so sharedPrefs is read-only for modules.

#### F-061 — Stale structural documentation in overlay layout and resolver tables (width, button count, enabled-table)

| | |
|---|---|
| **Category** | inconsistency |
| **Severity** | low |
| **Area** | widget/overlay rendering |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:widget` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/res/layout/overlay_5button_layout.xml:29`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt:432`

Three doc-vs-code drifts that actively mislead the next maintainer of the widget: (1) overlay_5button_layout.xml:29-31 claims 'Layout is wrap_content so the WindowManager positions a compact card; the visual size lands at ~280 dp wide' — the root is actually a fixed 156dp (line 46) and DefaultOverlayLayoutParamsFactory pins the authoritative window width to OVERLAY_WIDTH_DP=156 precisely because WRAP_CONTENT was broken (OverlayLayoutParamsFactory.kt:85-94 documents that history); (2) the file and LayoutMode are still named '5button'/'OVERLAY_5BUTTON' and the header says '5-button card' while only 4 buttons exist post Variante-2a merge (OverlayBackend.kt:429-436 maps exactly 4 LogicalButtonIds), and the Row-2 comment at lines 23-27 describes '[Trash][Spacer w=1][Pause][Spacer 6dp][Close]' while the actual XML uses two symmetric weight-1 spacers (lines 106-124); (3) resolveOverlayRecordEnabled's KDoc table (ActionResolvers.kt:432-438) keys the enabled decision on viewMode (HOVER=false, KEYBOARD=false) but the implementation (lines 460-474) never inspects viewMode — it discriminates on state.imeViewVisible and returns true for Idle even when the IME is hidden, matching the newer inline 2026-05-22 table (lines 448-455) that contradicts the KDoc directly above it.

<details><summary>Evidence</summary>

Read overlay_5button_layout.xml:23-31 vs 46/89-135; OverlayLayoutParamsFactory.kt:85-94 + companion OVERLAY_WIDTH_DP=156; ActionResolvers.kt:426-475 — KDoc table at 432-438 (viewMode-keyed) vs implementation 460-474 (imeViewVisible-keyed, no viewMode read).

</details>

**Suggested fix:** Fix the layout header (fixed 156dp width, 4 buttons, symmetric spacers), and replace the stale viewMode-keyed KDoc table in resolveOverlayRecordEnabled with the imeViewVisible-keyed one (or delete the duplicate — the inline table is the accurate SSoT). Renaming OVERLAY_5BUTTON to OVERLAY_4BUTTON is optional and churn-heavy; a one-line 'historical name, 4 buttons since Variante 2a' note suffices.

#### F-066 — LegacyAudioFileMigration stores its idempotence flag in the default prefs file, not the app's canonical 'net.devemperor.dictate' file

| | |
|---|---|
| **Category** | inconsistency |
| **Severity** | low |
| **Area** | settings-prefs |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:settings-prefs` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/migration/LegacyAudioFileMigration.kt:88`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt:112`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/DictateApplication.java:25`

LegacyAudioFileMigration.run reads and writes Pref.LegacyAudioPurgedV4 via PreferenceManager.getDefaultSharedPreferences(context) (file 'net.devemperor.dictate_preferences.xml'), while every other Pref in the sealed-class registry lives in getSharedPreferences("net.devemperor.dictate") — exactly the mismatch DictateApplication.java:21-25 warns about ('The default-PreferenceManager file would be a different XML and the migrations below would silently no-op there'). The migration itself stays idempotent because it uses the same (wrong) file for both read and write, but the pref is registered in DictatePrefs.kt like all canonical prefs, so any future consumer reading it through the canonical sp instance will always see the default false, and PrefsMigration-style cleanup passes on the canonical file will never see the key. It also means the flag key coexists in two possible files, which is a debugging trap.

<details><summary>Evidence</summary>

LegacyAudioFileMigration.kt:88 'val prefs = PreferenceManager.getDefaultSharedPreferences(context)' followed by prefs.get(Pref.LegacyAudioPurgedV4) at line 89 and prefs.edit().put(...) at line 107. Contrast: DictateApplication.java:25, PreferencesFragment.java:83 (setSharedPreferencesName), APISettingsActivity.java:123, SystemPromptsActivity.java:73, DictateSettingsActivity.java:83 all use the named 'net.devemperor.dictate' file. grep confirms LegacyAudioPurgedV4 has no other production readers/writers.

</details>

**Suggested fix:** Switch LegacyAudioFileMigration.run to context.getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE) and, for existing installs, read the old default-file flag once as a fallback gate before migrating the flag itself (or rely on the DAO-side WHERE-status idempotence layer, which the class KDoc documents as the secondary safety net). Update LegacyAudioFileMigrationTest accordingly.

#### F-073 — OpenRouter reasoning-model detection never matches vendor-prefixed model ids — wrong parameters offered and sent

| | |
|---|---|
| **Category** | inconsistency |
| **Severity** | low |
| **Area** | ai-layer / parameter system |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:ai-layer` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/model/ParameterRegistry.kt:62`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/model/ParameterRegistry.kt:7`

getCompletionParameters maps OPENROUTER to OPENAI_COMPLETION (line 62, commented 'Fallback, later API enrichment'). The reasoning filters use startsWith("o1"/"o3"/"o4"/"gpt-5") (lines 7-15), but OpenRouter model ids are vendor-prefixed ('openai/gpt-5', 'openai/o3-mini'), so isReasoningModel never matches: reasoning models via OpenRouter are always offered temperature/top_p (which some upstream o-series endpoints reject with 400), and reasoning_effort is never available. Same class of issue for CUSTOM deployments whose model names don't follow OpenAI naming. The registry comment acknowledges this as a fallback, but the resulting 400 BAD_REQUEST for OpenRouter o-series users is a real, user-visible edge.

<details><summary>Evidence</summary>

ParameterRegistry.kt:7-15 prefix checks; line 62 OPENROUTER -> OPENAI_COMPLETION; AIOrchestrator.kt:158 PARAMETER_PREFS includes TemperatureOpenRouter so a set temperature IS sent for any OpenRouter model. OpenAICompatibleRunner.kt:115 forwards temperature unconditionally when present.

</details>

**Suggested fix:** Make the reasoning-model predicate strip a 'vendor/' prefix before matching (model.substringAfterLast('/')) as a cheap first step; longer term enrich via OpenRouter's /models metadata (supported_parameters) as the comment already plans.

#### F-074 — reasoning_effort enum offers 'none'/'xhigh' to all reasoning models and lacks 'minimal' — invalid values cause 400 on o1/o3/o4

| | |
|---|---|
| **Category** | inconsistency |
| **Severity** | low |
| **Area** | ai-layer / parameter system |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:ai-layer` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/model/ParameterRegistry.kt:27`

The single reasoning_effort ParameterDef (enumValues none/low/medium/high/xhigh) applies to every model matching isReasoningModel (o1*, o3*, o4*, gpt-5*). 'none' and 'xhigh' are only accepted by newer gpt-5.1-family models; selecting them with o3/o4-mini yields a BAD_REQUEST at request time (surfaced only as the generic bad_request info bar). 'minimal' (valid for gpt-5) is missing entirely. Since the value is forwarded raw via putAdditionalBodyProperty (OpenAICompatibleRunner.kt:120), there is no client-side validation to compensate.

<details><summary>Evidence</summary>

ParameterRegistry.kt:27-29 single enum list with modelFilter=isReasoningModel; OpenAICompatibleRunner.kt:120 forwards unvalidated; APISettingsActivity.addEnumField (line 588+) offers all values for any matched model.

</details>

**Suggested fix:** Split the enum def by model family (modelFilter per value set), or at minimum add 'minimal' and gate 'none'/'xhigh' behind a gpt-5.1+ model filter.

#### F-084 — Select-all prompt chip performs raw InputConnection.setSelection, bypassing InsertionService unification

| | |
|---|---|
| **Category** | inconsistency |
| **Severity** | low |
| **Area** | rewording |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:rewording-usage-onboarding` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:3533`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/insertion/Insertion.kt:158`

The select-all control chip (prompts keyboard id -3) routes SELECT_ALL through insertionService().editAction(...), but its deselect branch writes directly to the InputConnection: clearMetaKeyStates(0) and setSelection(0,0)/setSelection(len,len) (DictateInputMethodService.handleSelectAllToggle, lines 3533-3538). The recent P1-P5 insertion unification ('unify all host-IC writes behind a single InsertionService', commit 18a3ba8) introduced ControlOp.CursorMove for cursor writes, yet this path (and BackspaceSwipeHandler.kt:107-148) still issues raw IC selection writes. Two code paths now do host-selection mutation differently; the raw path skips whatever logging/guarding (host-block guard, W4-race handling) the InsertionService applies, which is exactly the class of divergence the unification was meant to eliminate.

<details><summary>Evidence</summary>

DictateInputMethodService.java:3529-3538: editAction(EditAction.SELECT_ALL) for the select branch vs raw inputConnection.clearMetaKeyStates/setSelection for the deselect branch. Insertion.kt:158 defines ControlOp.CursorMove(offset) but grep shows no absolute-selection ControlOp and no InsertionService call in the deselect branch. BackspaceSwipeHandler.kt:107,120,124,148 contain further raw ic.setSelection calls.

</details>

**Suggested fix:** Add an absolute-selection ControlOp (e.g. SetSelection(start, end)) to the Insertion ControlOp sealed interface and route handleSelectAllToggle's deselect branch (and BackspaceSwipeHandler's swipe-selection) through InsertionService so all selection writes share the same policy/guard path.

#### F-087 — docs/DATABASE-PATTERNS.md falsely lists processing_steps.status as an applied Double-Enum column (no CHECK exists), plus wrong table name and missing column in the retrofit list

| | |
|---|---|
| **Category** | inconsistency |
| **Severity** | low |
| **Area** | database / documentation |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:database` |

**Files:** `/home/lukas/WebStorm/Dictate/docs/DATABASE-PATTERNS.md:222`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/migration/Migrations.kt:69`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo3.kt:179`

Three factual errors in the authoritative DB-conventions doc: (1) The 'Applied columns' table (DATABASE-PATTERNS.md:213-222) claims processing_steps.status follows the Double-Enum pattern via StepStatus — but no CHECK constraint on that column exists in any migration: Migrations.kt:69 ('status TEXT NOT NULL') and the MigrationTo3.kt:179 processing_steps rebuild both omit it. It belongs in the 'should be retrofitted' table instead. (2) The retrofit table (line 230) names the insertion table 'insertion_log'; the actual table is 'text_insertions' (TextInsertionEntity). (3) completion_log.type is a finite-set column ('TRANSCRIPTION' / 'AUTO_FORMAT' / StepType.name values written in SessionManager.logCompletion via PipelineOrchestrator.kt:1282,1326,1346,1445,1568) with no Kotlin enum covering 'TRANSCRIPTION' and no CHECK — it is absent from both doc tables. Relatedly, MIGRATION_5_6 recreated the entire sessions table (the doc's 'retrofit when next touched' trigger) without adding the pending sessions.type CHECK.

<details><summary>Evidence</summary>

DATABASE-PATTERNS.md:213-231 (applied + retrofit tables); Migrations.kt:69 and MigrationTo3.kt:179 (processing_steps status without CHECK); grep 'CHECK' across migration/ shows CHECKs only on sessions columns; SessionManager.kt:372-395 logCompletion writes free String type; PipelineOrchestrator call sites pass 'TRANSCRIPTION', 'AUTO_FORMAT', ctx.stepType.name.

</details>

**Suggested fix:** Move processing_steps.status to the retrofit table (or add the CHECK in the next migration), correct 'insertion_log' to 'text_insertions', and add completion_log.type to the retrofit list (introduce a CompletionLogType enum covering TRANSCRIPTION + step types so callers stop passing magic strings).

#### F-101 — Raw ic.getSelectedText(0) at six call sites bypasses the W-3 central try-catch the codebase itself mandates

| | |
|---|---|
| **Category** | inconsistency |
| **Severity** | low |
| **Area** | core IME service / InputConnection reads |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `seed:emoji-delete` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:4868`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:2655`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:3527`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:5026`

safeReadSelectedText (DictateInputMethodService.java:4864-4875) exists as 'Quality-Gate W-3 - central try-catch for InputConnection.getSelectedText' because, per its own KDoc, 'stale IC implementations are documented to throw on read attempts'. Yet six other call sites in the same file read getSelectedText(0) raw without the wrapper: 2655/2658 (rewording selection capture), 3224 (prompt-chip enable), 3527 (select-all toggle), 3548 + updateSelectAllPromptState, 4307, and 5026 (deleteOneCharacter). Most use a fresh getCurrentInputConnection() so the practical throw risk is lower than for captured ICs, but the 2655 site runs on a connection captured earlier in an executor flow, and an uncaught throw in any of them crashes the IME process. Two code paths do the same read with different robustness.

<details><summary>Evidence</summary>

Grepped all getSelectedText call sites; read each surrounding block (sed of lines 2650-2662, 3220-3228, 3524-3552, 4303-4311, 5022-5030). Only the InsertionAuditLog.captureReplaced adapter (4790-4792) routes through safeReadSelectedText; all others call ic.getSelectedText(0) directly with no try-catch in scope.
[merged duplicate from sweep:insertion-keyboard: deleteOneCharacter reads getSelectedText without the W-3 try-catch that the project added specifically for stale-IC reads] DictateInputMethodService.java:5026 (raw call) vs 4790-4792 and 5797 (both other getSelectedText sites route through safeReadSelectedText); KDoc at 4863-4867 documents the throw behavior as known.

</details>

**Suggested fix:** Route all getSelectedText reads through safeReadSelectedText (it already returns null for empty/thrown cases, which every caller treats identically to 'no selection'). For the two callers that need the CharSequence rather than a boolean, the String return is sufficient. Point change per site; no behavioural change on the happy path.

#### F-117 — Empty-state text 'No sessions yet' also shown for empty search/filter results

| | |
|---|---|
| **Category** | inconsistency |
| **Severity** | low |
| **Area** | history / HistoryActivity |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `seed:history-redesign` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/history/HistoryActivity.java:183`, `/home/lukas/WebStorm/Dictate/app/src/main/res/values/strings.xml:310`

updateEmptyState() shows the single history_no_sessions_tv ('No sessions yet.\nStart using Dictate to see\nyour history here.') whenever the displayed list is empty — including when the list is empty only because of the active search query or type-filter chip. A user with 50 recordings who searches for a term with no match is told they have no sessions and should 'start using Dictate'. The redesigned filter/search UI never got a matching 'no results' state. Additionally the 'Delete all' button is disabled based on the filtered view (line 185), so with a non-matching search you cannot delete-all even though sessions exist (arguably safe, but driven by the same conflation).

<details><summary>Evidence</summary>

HistoryActivity.java:183-186 — visibility keyed solely on data.isEmpty(); refreshData() (158-181) populates data from search/filter. Only one empty-state string exists (strings.xml:310).

</details>

**Suggested fix:** Differentiate: when currentSearch != null || currentFilter != null and the unfiltered table is non-empty, show a 'No matching sessions' string instead.

#### F-032 — ViewModeAction.CloseOverlay is never dispatched — its reducer arm, cancel-cascade effect, and resolver doc table are dead/stale

| | |
|---|---|
| **Category** | wiring-gap |
| **Severity** | low |
| **Area** | state-machine / ViewModeModule + ActionResolvers |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:state-machine` |

**Files:** `app/src/main/java/net/devemperor/dictate/state/modules/ViewModeModule.kt:187`, `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt:510`, `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt:484`

Since the 2026-05-23 sticky-widget refactor, resolveOverlayCloseAction routes every overlay X-click to WidgetAction.CloseWidget(WIDGET_BUTTON); grep shows no remaining dispatch site for ViewModeAction.CloseOverlay. The CloseOverlay reducer arm (ViewModeModule.kt:187-199) and Effect.DispatchCloseOverlayCascade (:223-258) — which carried the 'HOVER X-close cancels in-flight recording AND pipeline' semantics plus the SuppressAutoOverlay + CloseWidget(KEYBOARD_TOGGLE) fan-out — are unreachable. The resolver's own KDoc table (ActionResolvers.kt:481-486) still documents 'HOVER → Action.ViewModeAction.CloseOverlay', and OverlayModule.kt:372-389 still explains the cascade as if live. Behavior change is presumably intended (pipeline keeps running, result → pending-insert), but the dead arm + contradicting docs will mislead the next maintainer into thinking HOVER-close still cancels work.

<details><summary>Evidence</summary>

grep 'CloseOverlay' across app/src/main: hits only in Action.kt:534 (declaration), ViewModeModule/OverlayModule/ActionResolvers KDoc + reducer arm, DictatePipelineService.kt:900 comment — zero dispatch call. resolveOverlayCloseAction body (ActionResolvers.kt:492-515) returns CloseWidget(WIDGET_BUTTON) or null only; its own doc table at 481-486 contradicts the body.

</details>

**Suggested fix:** Remove CloseOverlay + its reducer arm + Effect.DispatchCloseOverlayCascade (and the SuppressAutoOverlayUntilNextSession/cancel emissions inside), or explicitly re-wire it if the HOVER cancel semantics are still wanted. Fix the resolver KDoc table and the OverlayModule §'HOVER → KEYBOARD' comment block either way.

#### F-033 — ModuleServices.inputConnectionProvider is a dead DI field after the InsertionService migration; KDoc still documents it as the write path

| | |
|---|---|
| **Category** | wiring-gap |
| **Severity** | low |
| **Area** | state-machine / ModuleServices |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:state-machine` |

**Files:** `app/src/main/java/net/devemperor/dictate/state/ModuleServices.kt:111`, `app/src/main/java/net/devemperor/dictate/state/ModuleServices.kt:61`, `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:562`

After the P4/P5 keystroke-path migration, every module host-IC write goes through services.insertionServiceProvider() (KeyboardInputModule.kt:160-191). No module (or any other code) ever invokes services.inputConnectionProvider — the field is populated at DictatePipelineService.kt:562 and read nowhere. The class KDoc (ModuleServices.kt:61-64) still instructs 'Effect handlers call inputConnectionProvider()?.commitText(…)', i.e. it documents the exact pattern the InsertionService unification forbids.

<details><summary>Evidence</summary>

grep 'inputConnectionProvider' across app/src: consumers with that name are separate constructor params of QwertzKeyboardController/BackspaceSwipeHandler/SpecialTouchHandlerInstaller (IME-side, not ModuleServices). The ModuleServices field appears only in the constructor (:111), its KDoc, the service wiring (DictatePipelineService.kt:562), and test fakes. KeyboardInputModule.runEffect uses insertionServiceProvider exclusively.

</details>

**Suggested fix:** Delete the inputConnectionProvider field from ModuleServices (and its wiring + fake default), or if a future module-level raw-IC need is anticipated, at minimum rewrite the KDoc to state that all writes must go through insertionServiceProvider and the raw provider is reserved/unused.

#### F-034 — Notification result-stage actions are dead at both ends: ACTION_INSERT/ACTION_DISMISS have no notification buttons and ConfirmInsertion/DismissResult reduce to null

| | |
|---|---|
| **Category** | wiring-gap |
| **Severity** | low |
| **Area** | state-machine / PipelineModule + PipelineActionRouter |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:state-machine` |

**Files:** `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt:484`, `app/src/main/java/net/devemperor/dictate/core/PipelineActionRouter.kt:77`

PipelineActionRouter decodes ACTION_INSERT → PipelineAction.ConfirmInsertion and ACTION_DISMISS → DismissResult ('[Einfügen]/[Verwerfen]' per its KDoc), but PipelineNotificationCoordinator never builds those actions into any notification (only PAUSE/STOP/SEND/RESUME/CANCEL at coordinator lines 183-193), and PipelineModule reduces both actions to null with the comment 'UI-only acks' (PipelineModule.kt:484-486). So a whole intended post-Done notification flow (insert/dismiss the produced transcript from the tray) exists only as a router mapping with no producer and a no-op consumer — while the equivalent user need is now served by the InfoBar pending-insert items. The router KDoc listing seven buttons ([Einfügen]/[Verwerfen]/[Fortsetzen]) is stale.

<details><summary>Evidence</summary>

PipelineActionRouter.kt:77-80 + constants :141-142; grep ACTION_INSERT/ACTION_DISMISS → no other usage; PipelineNotificationCoordinator.kt addAction lines 183-193 cover only PAUSE/STOP/SEND/RESUME/CANCEL; PipelineModule.kt:484-486 ConfirmInsertion/DismissResult → null.
[merged duplicate from sweep:core-ime: Notification INSERT/DISMISS actions are double-dead: no notification ever shows the buttons and the dispatched actions are rejected by every reducer] PipelineActionRouter.kt:77-80 (mapping) and :141-142 (constants); PipelineNotificationCoordinator.kt:176-197 build() covers only Idle/Recording/Paused/Pipeline/OverlayPermissionRequired; PipelineModule.kt:484-486 null arm; grep 'ConfirmInsertion|DismissResult' shows no other consumer.

</details>

**Suggested fix:** Either implement the result-stage notification (coordinator builds INSERT/DISMISS buttons; ConfirmInsertion routes to the same AcceptAndInsert/Dismiss semantics the InfoBar uses) or delete the two actions, the router branches, and the stale KDoc button list.

#### F-037 — Dead action surfaces: ThemingAction setters, LivePromptAction axis, and FeatureToggleAction.ToggleVibration have no producers

| | |
|---|---|
| **Category** | wiring-gap |
| **Severity** | low |
| **Area** | state-machine / ThemingModule + LivePromptModule + FeatureToggleModule |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:state-machine` |

**Files:** `app/src/main/java/net/devemperor/dictate/state/modules/ThemingModule.kt:42`, `app/src/main/java/net/devemperor/dictate/state/modules/LivePromptModule.kt:50`, `app/src/main/java/net/devemperor/dictate/state/modules/FeatureToggleModule.kt:85`, `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt:642`

Three action families exist with full reducer arms but zero dispatch sites: (1) ThemingAction.SetTheme/SetAccentColor/SetOverlayCharacters/SetOutputSpeed — theming state is populated exclusively by PipelinePrefMirror; no code dispatches the setters (their Action.kt KDoc even claims 'SP writes are performed by PipelinePrefMirror on state changes' — the mirror is SP→State only, so dispatching a setter would silently NOT persist). (2) LivePromptAction.EnableLivePrompt/DisableLivePrompt/ChainNext — nothing dispatches them and nothing ever sets livePrompt.pendingChain=true, so PipelineModule's ChainNext cascade (PipelineModule.kt:642-644, gated on enabled && pendingChain) is unreachable; the real live-prompt feature runs through the legacy IME-side boolean (ImePipelineConfigResolver.livePrompt / JobExecutor). (3) ToggleVibration reducer intentionally returns null (documented Phase-1 deviation) with no dispatch site either. Individually documented as phase artifacts, but collectively this is a set of misleading 'available' state-mutation APIs where dispatching either does nothing or does the wrong thing (unpersisted theming write).

<details><summary>Evidence</summary>

grep 'SetTheme|SetAccentColor|SetOverlayCharacters|SetOutputSpeed' outside Action.kt/ThemingModule → zero hits. grep 'EnableLivePrompt|DisableLivePrompt|ChainNext' outside Action.kt/modules → zero hits; LivePromptState.pendingChain only ever set false (LivePromptModule.kt:69/83); real feature uses core/ImePipelineConfigResolver.kt:102 + JobExecutor.kt:290 booleans. FeatureToggleModule.kt:85 ToggleVibration → null; no dispatch site (grep).

</details>

**Suggested fix:** Either wire real producers (in-IME theming pickers dispatching setters that ALSO emit persist effects; live-prompt toggle migrated from the legacy IME boolean into the state axis) or trim the dead leaves and note the SP-mirror as the sole update path in the modules' KDocs. For ThemingAction specifically, add persist effects before anyone wires a dispatcher — a setter that mutates state without SP write will be silently reverted on the next mirror sync of an unrelated key.

#### F-038 — PipelineSessionRepoSubsystem.pendingFlow contract is dead: production returns emptyFlow, no collector exists, interface KDoc claims a subscriber

| | |
|---|---|
| **Category** | wiring-gap |
| **Severity** | low |
| **Area** | state-machine / ModuleServices + PipelineSessionRepoAdapter |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:state-machine` |

**Files:** `app/src/main/java/net/devemperor/dictate/state/ModuleServices.kt:414`, `app/src/main/java/net/devemperor/dictate/state/PipelineSessionRepoAdapter.kt:210`, `app/src/main/java/net/devemperor/dictate/state/modules/PendingSessionsModule.kt:17`

The PipelineSessionRepoSubsystem interface KDoc states 'PendingSessionsModule subscribes to pendingFlow and emits PendingSessionsAction.Refresh whenever the DB changes' (ModuleServices.kt:412-415), and PendingSessionsModule's own KDoc calls the axis 'DB-subscriber-driven' via 'an observer on ModuleServices.sessionRepo.pendingFlow'. Neither is true: no code anywhere collects pendingFlow(), and the production adapter returns emptyFlow() (documented as Phase-1 conservative wiring at PipelineSessionRepoAdapter.kt:57-65). Actual update paths are PipelineRecovery boot hydration, PipelineModule Effect.AddPendingInsertSession, and RecordingModule's post-discard Refresh. The dead interface method + two contradicting KDocs invite a future consumer to rely on a flow that never emits.

<details><summary>Evidence</summary>

grep 'pendingFlow' across app/src/main: definitions (ModuleServices.kt:421, adapter :210 emptyFlow, stub :168 emptyFlow) and KDoc references only — no .pendingFlow() collector. PendingSessionsModule has no subscription code (read fully, 143 lines). Action.kt:437 also references a removed effect name 'RefreshPendingSessionsAsync' that no longer exists in PipelineModule (replaced by AddPendingInsertSession).

</details>

**Suggested fix:** Either drop pendingFlow() from the interface until a Room Flow implementation lands (removing the stale KDoc claims in ModuleServices + PendingSessionsModule), or implement it (Room Flow<List<SessionEntity>> + a services.scope collector dispatching Refresh) and delete the ad-hoc Refresh/AddOne side channels. Also fix the Action.kt:437 reference to the removed RefreshPendingSessionsAsync effect.

#### F-041 — Manual-paste affordance is a dead state axis: lastResultNeedsManualPaste / pendingPasteSessionIds are written but never rendered, and ClearManualPasteFlag is never dispatched

| | |
|---|---|
| **Category** | wiring-gap |
| **Severity** | low |
| **Area** | render-layout / infobar + resend state |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:render-layout` |

**Files:** `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt:757`, `app/src/main/java/net/devemperor/dictate/state/modules/ResendModule.kt:124`, `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt:184`, `app/src/main/java/net/devemperor/dictate/state/Action.kt:797`

PipelineRecovery Phase 4 dispatches Action.ResendAction.NotifyManualPasteNeeded per recovered COMPLETED-but-not-inserted session, and ResendModule maintains ResendState.lastResultNeedsManualPaste + pendingPasteSessionIds. The KDoc chain promises a UI: 'the IME header must hint tap to paste' (DictateUiState.kt:737-741) and 'when the IME re-binds, the header shows tap-to-paste' (PipelineRecovery.kt:180-182). No renderer, predicate, resolver, InfoBar producer, or IME code reads either field — grep finds only the reducer, the recovery dispatcher, and doc references. ClearManualPasteFlag has a reducer arm (ResendModule.kt:139-146) but zero dispatch sites, so the set only ever grows within a process lifetime. User impact is softened because the same sessions also surface via the InfoBar pending-insert producer (PipelineSessionRepoAdapter maps transcribedText = finalOutputText at line 341, so InfoBarSelector's COMPLETED filter picks them up), but the promised header hint does not exist and the axis is dead weight the docs present as live (DictateUiState.kt:747-749 itself admits 'consumer wiring tracked for B5/B6').

<details><summary>Evidence</summary>

Repo-wide grep for lastResultNeedsManualPaste/pendingPasteSessionIds/ClearManualPasteFlag/NotifyManualPasteNeeded matches only: ResendModule.kt (reducer arms 124-146), PipelineRecovery.kt:184 (dispatch), Action.kt:790-805 (definitions), plus KDoc/comment references in PipelineModule.kt, DictatePrefs.kt, SessionDao.kt, MigrationTo4.kt, DictatePipelineService.kt — no read of the state fields anywhere in state/render, state/layout, state/infobar, or DictateInputMethodService.java.
[merged duplicate from sweep:state-machine: Manual-paste hint (SF-4) is write-only: state flag set by recovery, no UI consumer, no clear dispatch, no clipboard copy] Dispatch: PipelineRecovery.kt:184. State write: ResendModule.kt:124-134 (NotifyManualPasteNeeded) and 139-148 (ClearManualPasteFlag — no dispatch site anywhere per grep). Consumers: grep '\.resend\b|getResend()' shows only cooldown/lastAudioExists/resendEnabled reads (LayoutCatalog.kt:89-90/219-220, LayoutPredicates.kt:57-58, DictateInputMethodService.java:1509). No clipboard write in PipelineRecovery.recover (read fully, lines 143-232).

</details>

**Suggested fix:** Either implement the consumer (simplest: fold it into InfoBarSelector as the per-session paste-hint producer and dispatch ClearManualPasteFlag from the AcceptAndInsert/Dismiss side-channel), or — since the pending-insert InfoBar already covers the UX — delete the two ResendState fields, both actions, and the recovery dispatch, updating the KDocs that promise a header hint.

#### F-058 — OverlayBackend.updateAccentColor is never called from production — widget recording visuals keep stale accent color after user changes accent

| | |
|---|---|
| **Category** | wiring-gap |
| **Severity** | low |
| **Area** | widget/overlay rendering |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:widget` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayBackend.kt:383`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:3354`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:763`

The overlay backend exposes updateAccentColor(color) (OverlayBackend.kt:383) as the 'symmetric to ImeViewBackend.updateAccentColor' hook, and it is covered by OverlayBackendTest.kt:616. But the only production accent-refresh site calls imeViewBackend.updateAccentColor(accentColor) (DictateInputMethodService.java:3354) — no code path ever invokes the overlay counterpart. The overlay's BorderGlowAnimation is constructed with the accent captured once at factory time (DictatePipelineService.kt:764 sharedPrefs.get(Pref.AccentColor)), so after the user changes the accent color in settings while the widget stays attached (sticky-widget keeps it attached across IME show/hide), the widget's amplitude-visualizer bar color and the brightness-modulated button background (BorderGlowAnimation.applyBackgroundLevel uses the stale baseColor) keep the OLD accent until the overlay is torn down and re-inflated. The breathing background tint partially self-heals because RecordingAnimationController reads accentColorProvider lazily, making the two color sources visibly diverge on the same button.

<details><summary>Evidence</summary>

grep for updateAccentColor across app/src: callers are DictateInputMethodService.java:3354 (imeViewBackend only) and OverlayBackendTest.kt:616 (test only). BorderGlowAnimation.kt:29-31 stores baseColor from constructor; DictatePipelineService.kt:763-772 passes the pref value once at factory-create time. RecordingAnimationController.kt:195-213 updateColor exists and handles the repaint but is only reachable via the never-called OverlayBackend.updateAccentColor forwarder.

</details>

**Suggested fix:** In the IME service's theme-apply block (DictateInputMethodService.java:~3354), also fetch the overlay backend via the existing LocalBinder path (same pattern as the onTimerTick/onAmplitude forwarding at lines 1826/1840) and call overlay.updateAccentColor(accentColor). Alternatively have DictatePipelineService observe Pref.AccentColor and forward.

#### F-064 — ignoreSpinnerChange guard is never armed — dead flag, initial spinner selection re-fires provider persistence and duplicate model fetch

| | |
|---|---|
| **Category** | wiring-gap |
| **Severity** | low |
| **Area** | settings-prefs |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:settings-prefs` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/settings/APISettingsActivity.java:68`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/settings/APISettingsActivity.java:173`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/settings/APISettingsActivity.java:330`

APISettingsActivity declares 'private boolean ignoreSpinnerChange = false' (line 68) and checks it at the top of both provider-spinner onItemSelected listeners (lines 173, 330), but no code path ever sets it to true. The guard was evidently intended to suppress the framework-initiated onItemSelected that Android fires after setSelection(providerIndex) during setup — without it, activity open causes a redundant write of the (unchanged) provider pref plus a second updateTranscriptionUI/updateRewordingUI pass, i.e. a duplicate network model-fetch on every open. The fetch-sequence counters mask stale-result races, but the double fetch itself still happens.

<details><summary>Evidence</summary>

grep -n ignoreSpinnerChange APISettingsActivity.java returns exactly three hits: the declaration (line 68, initialised false) and the two read sites (173, 330). No assignment to true exists anywhere in the codebase.

</details>

**Suggested fix:** Set ignoreSpinnerChange = true before the initial setSelection calls in setupTranscriptionSection/setupRewordingSection and reset it to false in a spinner post-runnable (or after first listener invocation), mirroring the working ignoreTextChange pattern. Or drop the flag and register the listeners only after initial selection settles.

#### F-065 — Pref.TranscriptionApiKeyOpenRouter is orphaned — no UI can set it and its only read is unreachable

| | |
|---|---|
| **Category** | wiring-gap |
| **Severity** | low |
| **Area** | settings-prefs |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:settings-prefs` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt:52`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/factory/RunnerFactory.kt:82`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/AIProvider.kt:46`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/settings/APISettingsActivity.java:704`

Pref.TranscriptionApiKeyOpenRouter (DictatePrefs.kt:52) has no write path and no reachable read path. AIProvider.OPENROUTER declares supportsTranscription = false ('OpenRouter has no /audio/transcriptions endpoint'), so AIProvider.withTranscription() excludes it from the transcription provider spinner, and APISettingsActivity.getTranscriptionApiKeyPref (line 704-712) has no OPENROUTER case (would silently fall through to the OpenAI key — a latent misrouting if OpenRouter transcription is ever enabled). The single read in RunnerFactory.getApiKey (line 82, AIProvider.OPENROUTER -> Pref.TranscriptionApiKeyOpenRouter) is unreachable: createTranscriptionRunner's require(provider.supportsTranscription) throws before getApiKey is ever called for OPENROUTER.

<details><summary>Evidence</summary>

grep across the codebase shows exactly two references to TranscriptionApiKeyOpenRouter outside DictatePrefs.kt: RunnerFactory.kt:82 (guarded unreachable by the require at RunnerFactory.kt:18-20 given AIProvider.kt supportsTranscription=false for OPENROUTER) and none in any settings UI. APISettingsActivity.getTranscriptionApiKeyPref default branch returns the OpenAI key pref.

</details>

**Suggested fix:** Either delete the pref and the RunnerFactory branch (with a code comment that OpenRouter lacks a transcription endpoint), or — if OpenRouter transcription support is planned — keep both and add the missing OPENROUTER case in APISettingsActivity.getTranscriptionApiKeyPref so the key routes correctly the day supportsTranscription flips to true.

#### F-075 — Anthropic top_p/top_k and OpenAI top_p/penalty parameters: registry defs and runner forwarding exist, but resolver and UI never supply them (dead mutual-exclusion machinery)

| | |
|---|---|
| **Category** | wiring-gap |
| **Severity** | low |
| **Area** | ai-layer / parameter system |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:ai-layer` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/AIOrchestrator.kt:133`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/runner/AnthropicCompletionRunner.kt:90`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/model/ParameterRegistry.kt:36`

ParameterRegistry declares top_p (+ mutuallyExclusiveWith temperature), top_k, frequency_penalty, presence_penalty; both runners forward them (OpenAICompatibleRunner.kt:117-119, AnthropicCompletionRunner.kt:96-97), and AnthropicCompletionRunner even implements runtime temperature-XOR-top_p arbitration (lines 90-96). But AIOrchestrator.resolveParameters returns null for every parameter except temperature/max-tokens/reasoning_effort (line 133 'else -> null'), no Pref keys exist for them, and APISettingsActivity.PARAM_PREFS skips them ('No Pref defined yet - skip', line 479) — so the mutual-exclusion arbitration in the Anthropic runner and the mutuallyExclusiveWith UI machinery (APISettingsActivity:637-671) can never trigger. This is acknowledged as 'extendable later' in two comments, so it is a deliberate extension point — flagged here because three layers (registry, runner arbitration, UI exclusion logic) are already built and only the middle resolver+Pref layer is missing, which makes the code read as functional when it is not.

<details><summary>Evidence</summary>

AIOrchestrator.kt:128-136 when-block covers only three names; PARAMETER_PREFS (lines 148-160) has no top_p/top_k/penalty fields; DictatePrefs.kt:89-97 defines only temperature/max_tokens/reasoning_effort params. AnthropicCompletionRunner.kt:90-97 contains arbitration for parameters that can never arrive.

</details>

**Suggested fix:** Either add the missing Pref<Float> keys + resolver arms + PARAM_PREFS entries to activate the already-built UI/runner support, or trim the registry defs to the three wired parameters and drop the dead arbitration until the feature is actually scheduled.

#### F-076 — InfoBarController has no 'cancelled' branch for AIProviderException.toInfoKey — falls through showing stale text and handlers

| | |
|---|---|
| **Category** | wiring-gap |
| **Severity** | low |
| **Area** | ai-layer / error propagation |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:ai-layer` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/AIProviderException.kt:37`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/InfoBarController.kt:57`

AIProviderException.toInfoKey maps ErrorType.CANCELLED to "cancelled", but InfoBarController.showInfo has no 'cancelled' case. Because showInfo sets infoCl VISIBLE and infoNoButton VISIBLE before the when-block (lines 57-59), an unmatched type displays the info bar with whatever text and yes-button handler the previous info left behind. Today this is unreachable — no runner constructs ErrorType.CANCELLED and PipelineOrchestrator.isCancellation short-circuits before onPipelineError — but the enum value, the toInfoKey mapping, and the pass-through at DictateInputMethodService.java:4616 form a loaded trap for the first runner that starts reporting CANCELLED (e.g. a future cooperative-cancel implementation).

<details><summary>Evidence</summary>

AIProviderException.kt:30-39 toInfoKey includes ErrorType.CANCELLED -> "cancelled". InfoBarController.kt:54-166 when-block cases: update, rate, donate, timeout, invalid_api_key, quota_exceeded, model_not_found, bad_request, internet_error — no cancelled, no else. Lines 57-59 mutate visibility before dispatch. grep shows no code path constructs AIProviderException with CANCELLED today.

</details>

**Suggested fix:** Move the visibility mutations inside the matched cases (or add an else that dismisses), and either add a no-op/'cancelled' case or remove CANCELLED from toInfoKey and document that cancellation must never reach showInfo.

#### F-091 — InsertionSource enum lives in database/entity but is never persisted — text_insertions audit rows cannot record what kind of text was inserted

| | |
|---|---|
| **Category** | wiring-gap |
| **Severity** | low |
| **Area** | database / insertion audit |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `sweep:database` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/entity/InsertionSource.kt:3`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/entity/TextInsertionEntity.kt:26`, `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/SessionManager.kt:404`

InsertionSource (TRANSCRIPTION / STATIC_PROMPT / REWORDING / QUEUED_PROMPT) is declared in the database entity package alongside the persisted Double-Enum classes, but no table has a column for it: TextInsertionEntity carries insertion_method, source_step_id and source_transcription_id but no source column, and SessionManager.logTextInsertion (SessionManager.kt:404-427) takes no source parameter. The enum is used purely as an in-memory routing type in the pipeline callback chain (PipelineOrchestrator.onPipelineCompleted → InsertionService). Consequence: the text_insertions audit table cannot distinguish a STATIC_PROMPT insert from a transcription insert (both may have NULL step/transcription ids), and the enum's location in database/entity misleadingly implies it is a persisted Double-Enum column.

<details><summary>Evidence</summary>

grep InsertionSource across app/src/main: consumers are state/insertion/Insertion.kt:111 (InsertionRequest.source), InsertionCollaborators.kt:64, PipelineOrchestrator.kt:739/800/1069, PipelineCallbackBridge.kt:91 — none write it to a DAO; TextInsertionEntity.kt:25-36 has no source column; MIGRATION_1_2 text_insertions DDL (Migrations.kt:104-118) likewise.

</details>

**Suggested fix:** Either move InsertionSource out of database/entity into state/insertion (it is a runtime routing type), or — if the audit trail should capture it — add a source column to text_insertions via migration (with CHECK, per the Double-Enum pattern) and thread InsertionRequest.source through InsertionCollaborators into logTextInsertion.

#### F-116 — Orphaned history strings from dropped design elements: based_on, open_parent, regenerating

| | |
|---|---|
| **Category** | wiring-gap |
| **Severity** | low |
| **Area** | history / resources |
| **Status** | Unverified (pass-through: feature-gap or low severity) |
| **Found by** | `seed:history-redesign` |

**Files:** `/home/lukas/WebStorm/Dictate/app/src/main/res/values/strings.xml:322`, `/home/lukas/WebStorm/Dictate/app/src/main/res/values/strings.xml:339`, `/home/lukas/WebStorm/Dictate/app/src/main/res/values/strings.xml:346`

Three history strings have no reference anywhere in code or layouts: dictate_history_based_on ('Based on %1$s', line 322 — presumably a planned parent-session subtitle in the list), dictate_history_open_parent ('Open parent session', line 339 — replaced by the SOURCE_SESSION pipeline row which uses dictate_history_source_session instead), and dictate_history_regenerating ('Regenerating…', line 346 — a loading label superseded by the bare ProgressBar). These are leftovers of dropped/changed history-design elements and will confuse translators and future maintainers about which UI they belong to.

<details><summary>Evidence</summary>

grep -rn 'R.string.dictate_history_based_on|@string/dictate_history_based_on' (and the other two names) across app/src/main returns hits only inside values/strings.xml. The live source-session row uses dictate_history_source_session (HistoryDetailActivity.java:355).

</details>

**Suggested fix:** Delete the three strings, or wire them where they were intended (dictate_history_regenerating as the LOADING-state label next to the progress bar would pair naturally with the stuck-spinner fix).


## 4. Refuted Findings

One finding survived initial review but was killed by adversarial verification — kept here so it is not re-reported by a future review:

#### F-030 — "Dismissing the Partial-Recovery info-bar item silently forfeits the pending-insert transcript of the same session" (REFUTED)

The claimed data-loss scenario cannot occur: `InfoBarRenderer` renders only `items.first()`, and for the same session the pending-insert item always sorts before the partial-recovery item (identical `createdAt`, stable sort, build order). The partial-recovery item can only surface when there is no transcript to forfeit (`transcribedText == null`). What *is* real from this finding: (a) the KDoc at `InfoBarSelector.kt:128-130` contradicts the code (doc drift), and (b) the partial-recovery warning is effectively shadowed and rarely/never shown — both folded into the info-bar spec (§5).

## 5. Follow-up Specs

Six findings/clusters are too large for point fixes and got their own spec files (same directory, promotable per the research→spec lifecycle):

| Spec file | Seeds | Topic |
|---|---|---|
| [`2026-07-02 - overlay-widget-transparency.md`](<2026-07-02 - overlay-widget-transparency.md>) | F-118, F-119, F-120, F-121 | Configurable widget transparency + theme unification (user feature request) |
| [`2026-07-02 - infobar-consolidation.md`](<2026-07-02 - infobar-consolidation.md>) | F-040, F-039 | Kill the dual info-bar system; finish the ADR-0006 migration |
| [`2026-07-02 - history-reprocess-hardening.md`](<2026-07-02 - history-reprocess-hardening.md>) | F-055, F-108, F-109, F-111 | Move history AI ops off Activity-scoped executors; route through PromptService |
| [`2026-07-02 - recording-interruption-handling.md`](<2026-07-02 - recording-interruption-handling.md>) | F-036 | Produce InterruptionAction: call/headset/screen interruption handling |
| [`2026-07-02 - history-pagination-and-scale.md`](<2026-07-02 - history-pagination-and-scale.md>) | F-054 | Paging + background queries + retention limits for the history table |
| [`2026-07-02 - reprocess-queue-editor.md`](<2026-07-02 - reprocess-queue-editor.md>) | F-110 | Ship the staged-queue editor (PromptChooserBottomSheetV2) |

## 6. Information Gaps

1. **Low-severity findings are unverified.** Owner: whoever picks a low finding up (verify inline before fixing). Fallback: the catalog marks their status explicitly.
2. **No on-device reproduction was run.** All findings are code-derived; the review environment cannot run an Android emulator (WSL2, no KVM). Owner: device verification per fix. Fallback: every finding names the concrete user-visible symptom to check.
3. **F-047 duration values were not empirically traced** (why exactly ~16 s rather than the 15 s segment interval is inferred, not measured — encoder header overhead vs. rounding). Owner: F-047 implementer. Fallback: root cause (first-segment-only sum) is code-proven either way.
4. **The consolidator may have missed semantic duplicates** across the 97 remaining findings; spot-checks found none, but the dedup was single-pass. Owner: fix-wave triage. Fallback: duplicate fixes converge on the same code.

## 7. Change History

### 2026-07-02 — Initial catalog

- **Trigger:** User request for a whole-app feature/code review targeting feature gaps and wiring gaps, seeded with five user-observed defects.
- **Reasoning:** Post-migration audit hypothesis (§1.1) confirmed — dominant theme is incomplete-cutover residue.
- **What changed:** Full document created from workflow run `wf_34da6f29-e39` (57 agents, 122 raw → 96 cataloged findings).

## 8. References

- Follow-up specs: see §5 table (same directory).
- `docs/decisions/` — ADR-0006 (info-bar), ADR-0007/0008 (recording stack) govern several affected subsystems.
- Recent commits referenced by findings: `5210df2` (rolling-segment pre-arm), `18a3ba8` (InsertionService unification), `5d8d4fc` (insertion review fixes).
- Workflow artifacts: run `wf_34da6f29-e39`, raw result in session task `wwygl0my2` (not committed).
- `docs/DATABASE-PATTERNS.md` — Double-Enum contract referenced by F-086/F-087/F-088.
