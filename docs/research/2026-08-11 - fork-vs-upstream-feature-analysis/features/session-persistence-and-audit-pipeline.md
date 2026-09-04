---
date: 2026-08-11
author: Lukas + Claude (analysis session)
status: Research
context: Fork-vs-upstream analysis of session persistence, the persist-first pipeline with its ordered run queue, and the multi-file audio repository — what we built, what Dictate Keyboard 5.3 has, and whether the feature is still worth pursuing.
related-adrs: ADR-0007, ADR-0009, ADR-0011 (ADR-0003 as host, ADR-0012 and ADR-0014 as downstream consumers)
---

# Session Persistence & the Audit Pipeline — Fork vs. Upstream Analysis

Every dictation in the fork is a persisted, versioned chain of Room rows written **before** the network
call: a session, its transcription, each processing step, the completion log, and a record of what text
was inserted where and how. On top of that sit two capabilities the persistence makes possible — an
ordered run queue that lets a second recording start while the first is still processing (ADR-0009),
and a multi-file audio repository that hides N on-disk segments behind a one-audio-per-session API so a
recording survives process death (ADR-0007). Upstream 5.3 persists a meaningful subset: a Room history
table with audio retention, an interrupted-recording WAV plus three preferences, and the last dictation
text. **Verdict: partially exists, with the two halves diverging sharply.** Upstream's *recovery* story
is better-tested and simpler than ours for the case it covers; upstream's *audit* story does not exist
at all, and concurrent recording is not a gap there but an explicitly enforced prohibition.

## 1. Feature Overview

Upstream 3.2 — the fork's base — discarded everything. Audio was overwritten by the next recording,
transcripts and AI outputs lived only in RAM. If the pipeline failed after the transcription succeeded,
the transcript was gone; if the process died with finished text not yet committed, the text was gone;
if the user wanted yesterday's dictation back, there was nothing to get.

Three problems drove the work, in order:

**Nothing survived.** The first answer (cluster 4) was to persist the whole chain with **insert-only
versioning** — regenerating a step never destroys the previous version, it writes a new row at the same
`chain_index` with a bumped `version` and flips `is_current`. That gave the fork its History UI (list +
detail with a pipeline view, audio playback, clipboard/share, a version switcher, regenerate with a
prompt chooser), parent/child sessions for reprocess and reword, a keyboard history button, and a live
in-keyboard progress bar with a per-step elapsed timer and a cancel button.

**Persistence after the fact is not persistence.** The second answer (cluster 15) inverted the order:
rows are written *before* the network call, not after it, so process death at any point leaves a
recoverable row rather than a hole. `findPendingInsertion` re-surfaces output that was produced but
never inserted the next time the keyboard opens. History entries re-process as real child sessions
(`origin = HISTORY_REPROCESS` / `POST_PROCESSING`) through the *same* `JobExecutor` as a keyboard run —
one cancel path, one busy state, one notification slot. ADR-0009 then made a second recording legal
while the first is still processing; ADR-0011 added a service-side headless completion fallback so a run
that finishes while no IME surface exists still lands somewhere recoverable ("Tap to paste").

**`MediaRecorder` cannot append.** The third answer (cluster 13). Once `release()` runs or the process
dies, restarting against the same path overwrites at offset 0 — so a crash mid-recording silently
destroyed everything recorded so far. ADR-0007 makes `AudioFileRepository` the sole owner of the on-disk
naming convention (`{prefix}{sessionId}{infix}{N}{ext}`); callers only ever receive `File` handles.
`readForPipeline()` is `suspend`: a single-segment session returns zero-copy, a multi-segment session is
concatenated through `MediaExtractor.readSampleData` → `MediaMuxer.writeSampleData`, which is safe
precisely because all segments share codec parameters. Pipeline, state and recovery keep believing in
one file. This is what makes the widget's "Continue" affordance work, and "Discard" is a single
`deleteAll(sessionId)` sweeping every segment.

## 2. Our Implementation

**Schema.** Room, today at **v11 with 8 entities**. The audit core is five tables — `sessions`,
`transcriptions`, `processing_steps`, `completion_log`, `text_insertions` — governed by the project's
**Double-Enum pattern** (`docs/DATABASE-PATTERNS.md`): every finite-set column is both a Kotlin `enum
class` and a SQL `CHECK` constraint, which forces each new value through a migration instead of letting
a typo corrupt data silently. Hence `SessionType`, `SessionStatus`, `SessionOrigin`, `StepType`,
`StepStatus`, `InsertionMethod`, `InsertionSource`, `ResponseFormatKind`, `MessageRole`, `PromptType`.

`processing_steps` is where the versioning lives: a unique index on `(session_id, chain_index, version)`
plus `is_current`, `previous_step_id`, `previous_transcription_id` and `source_session_id`. A regenerate
adds a row; nothing is ever updated in place. That single design choice is what History's version chips,
the staleness warning when downstream steps came from an older transcription, and byte-faithful replay
of an earlier conversation turn (ADR-0012) all rest on.

**Pipeline.** `core/PipelineOrchestrator.kt` (2,101 LOC), `JobExecutor.kt` (550), `ActiveJobRegistry`,
`JobState`, `CancellationToken`, `SessionManager.kt` (812), `SessionTracker` + `ProcessingContext`,
`ImePipelineConfigResolver`, `RegenerationPromptFactory`, `ResendableSessionPolicy`, plus
`PipelineModule` and `PendingSessionsModule` on the state side. The run queue (ADR-0009) is deliberately
*ordered, not parallel*: `PipelineUiState.Preparing`/`Running` carry `queued: PersistentList<QueuedRun>`
(so "Idle with waiting work" is unrepresentable), `TriggerPipeline` enqueues with dedup by session id
instead of rejecting, and every terminal reducer arm routes through one helper that either goes Idle or
chain-starts the next run. Execution stays strictly single-slot; the submit seam waits for
`ActiveJobRegistry` to drain with a bounded timeout, and fails loudly on timeout rather than silently.

**Recovery.** `state/PipelineRecovery.kt` (445 LOC) is a DB replay at service startup with a seven-step
algorithm, and it is worth spelling out because it is the concrete difference from upstream:

```
RECORDING     → FAILED    (process died mid-recording; partial audio untrusted, file deleted,
                           reason "recording-interrupted-by-process-death")
TRANSCRIBING  → RECORDED  (audio file exists — only the pipeline died; stale last_error_* cleared;
                           no auto-resume by design, the user clicks Resend)
TRANSCRIBING  → FAILED    (audio file missing — storage cleanup race)
RECORDED      → FAILED    (audio file gone — cache wipe / "clear cache")
then: hydrate pendingSessions by MERGE (never override — a recording started during recovery keeps
      its in-memory entry), and for every COMPLETED row with finalOutputText != NULL AND
      inserted_at IS NULL, dispatch NotifyManualPasteNeeded so the IME shows "tap to paste".
```

A `BootCompletedReceiver` runs `recoverDbOnly()` before the keyboard is opened for the first time.

**Audio.** `audio/` (6 files / 695 LOC) — `AudioFileRepository` (interface), `AudioCodecReader`,
`CodecParams`, `PipelineAudioResult`, `CacheAudioCleanupJob`/`Scheduler` — implemented by
`core/CacheDirAudioFileRepository.kt` (311) and fed by `RecordingHardwareAdapter.kt` (477,
`allocateFirst`/`allocateNext`). Three migrations carry it: **v4→v5** introduces
`sessions.audio_file_paths` (pipe-delimited, backfilled, with `effectiveAudioFilePaths` as the bridge),
**v5→v6** widens the status CHECK with `RECORDING_INTERRUPTED` (a full table recreate, since SQLite
cannot drop a CHECK), **v6→v7** is a one-shot backfill closing the "paths empty but a legacy file is on
disk" gap. Related: **v3→v4** added `sessions.inserted_at`, extended the status CHECK with
`RECORDING`/`TRANSCRIBING` specifically so an OOM death is recoverable, and flipped the
`parent_session_id` self-FK from `ON DELETE CASCADE` to `SET NULL` — a data-loss fix.

**Coupling.** The schema, `SessionManager`/`SessionTracker` and `AudioFileRepository` are reusable in
isolation — the inventory calls `AudioFileRepository` + `CacheDirAudioFileRepository` (the MediaMuxer
concat) the most portable artifact of the whole May wave. The *wiring* is not: plan Phase 2 explicitly
threaded a `ProcessingContext` through every pipeline call site inside the God-Class, and
`PipelineModule`'s FSM, `ModuleServices.sessionRepo`, the effect/action cascade and
`DictatePipelineService` are all fork inventions. Everything in clusters 16–21 sits on this schema.

**Size.** XL. Cluster 4's branch is well north of +10,000 lines across three merges; cluster 15's three
waves are ~+11,600 / −2,900; cluster 13 adds ~1,600 LOC for the audio layer plus three migrations.

## 3. Upstream 5.3 Status

**Verdict: partially exists — good recovery, no audit, concurrency forbidden by design.**

### What upstream persists

`DictateController` is a process-wide `object` with a `SupervisorJob` scope, so its state survives an
IME **service** restart within the same process; nothing about the live state machine is serialized, so
**process death loses all in-flight state.** Deliberately persisted (upstream report area 13):

| What | Where |
|---|---|
| Interrupted-recording audio | `filesDir/dictate_interrupted.wav` (`DictateController.kt:2215-2216`) |
| Its metadata (pending flag, seconds, was-live) | 3 JetPrefs (`AppPrefs.kt:576-589`) |
| Last dictation text (for re-insert) | pref `dictate__last_dictation` — commented "stored to a pref so it survives the IME process being killed" (`:2357-2359`) |
| Provider keyring | one JSON JetPref (`ProviderAccount.kt:110-147`) — **plaintext**, no Keystore |
| History | Room v3 + `filesDir/dictate_history/<id>.wav` |

Explicitly **not** persisted, verified absent by search: any in-flight transcription or rewording
request (no foreground service, WorkManager or JobScheduler for transcription — `startForegroundService`
appears only for model downloads and the overlay mic); the pending prompt queue; and `carryOverAudio`
(`:446-449`), which is in-memory only, so **process death during a *continued* recording loses both
halves**. Recovery is strictly "here is the finished WAV — send, continue, or discard?", never "resume
where the state machine was".

### Where upstream is better than us

**Interrupted-recording recovery is present, solid and well-triggered** (`#147`/`#111`). Three triggers
funnel into `stashRecordingOnHide` (`DictateController.kt:2226`): `onWindowHidden`, `onDestroy`, and —
the part worth stealing conceptually — **a screen-off broadcast registered for the duration of every
recording** (`:874-895`), which their own code describes as "the dependable catch-all". The handler
stops the recorder, patches the WAV header so the file is valid, and moves it to `filesDir` "so it
survives the cache wipe". *Continue* splices the new segment onto the old via `AudioConcat.concat`
(`:2274`) — functionally our `readForPipeline()` concatenation, arrived at independently.

**History audio retention is more thought-through than ours.** `prune()`
(`dictate/data/history/DictateHistory.kt:306-342`) enforces three independent caps: max entries, max age
in days, and an audio **byte budget that drops the audio while keeping the text**. Pinned entries are
exempt from all three. Our retention is a 60-day sweep of `CANCELLED` rows plus a cache-audio cleanup
job — no byte budget, no pinning, no graceful audio-only degradation.

**Long-form segmentation is their one ordered-concurrency site, and it is done correctly** (`#170`):
segment indices are assigned under `segmentMutex` at cut time (`:1811-1819`), results are buffered in
`segmentResults` and drained strictly in order by `segmentCommitIndex` (`:1910-1929`), and a failed cut
*consumes its index* "so the ordered drain never stalls". That is exactly the invariant our pending-parts
flusher maintains — reached by a different route, for one feature instead of as the pipeline's general
shape.

### Where upstream lacks what we have

- **No per-step history.** A 3-prompt auto-apply chain stores only first-in and last-out;
  `originalForHistory` is set only when the chain actually changed the text
  (`DictateController.kt:1467`), and the settings dialog renders exactly two labelled sections with a
  copy button each (`DictateHistoryScreen.kt:509-522`, `#240`). There is no counterpart to
  `processing_steps`, no `chain_index`, no versioning, and therefore no way to see *which* prompt did
  what.
- **No re-run with a different prompt.** `retranscribeHistoryEntry` (`:2540-2559`) replays the whole
  chain against the stored WAV; you cannot run a *different* prompt against a stored transcript. Our
  prompt-chooser reprocess flow has no equivalent.
- **No multi-segment audio storage.** Long-form segments live in `segmentAudioFiles: HashMap<Int, File>`
  and are merged into one `dictate_seg_merged.wav` before the single history write (`:1942-1965`). No
  segment table, no segment column — the N-file reality is transient, not persisted.
- **No pagination.** No `androidx.paging` anywhere, no `LIMIT`/`OFFSET`; the DAO is
  `SELECT * FROM dictate_history ORDER BY pinned DESC, createdAt DESC` returning a `Flow<List<…>>`, and
  both UIs consume the full table. Their own comment at `ui/DictateHistoryLayout.kt:156-158` records that
  eager composition of "several hundred entries" blocked the UI thread for over a second.
- **A crash or OOM kill during recording loses the audio.** The WAV header is patched only in `stop()`;
  there is no periodic flush and no orphan scan (area 10c). This is the exact failure ADR-0007 and the
  `RECORDING → FAILED` recovery arm were built for — and the one place where our design is
  unambiguously stronger.

### Concurrency: not a gap, a prohibition

> [!IMPORTANT]
> `canStartRecording()` (`DictateController.kt:598-604`) returns **false** during `Recording`,
> `Transcribing` *and* `Rewording`, and a mic tap during transcription **cancels** it rather than
> starting a new capture (`:613-628`). With a single `recorder` and a single `transcribeJob` on a
> process-wide `object`, the architecture is structurally one dictation at a time.

Our ADR-0009 run queue is therefore not an unimplemented upstream feature — it is a direction upstream
has closed off in code. Proposing it would mean proposing a change to the controller's core state
machine, which is the category the upstream report identifies as least likely to be accepted.

One further note on direction: `completion_log` and the `prompt_tokens`/`completion_tokens` columns on
`processing_steps` sit adjacent to usage/cost tracking, which upstream **deliberately deleted** in
`852e7f2d` ("Pricing tables go stale quickly and cost transparency is out of scope") and replaced with
token-free, money-free local counters (`DictateStats`, `#142`). Any upstream-facing framing of our audit
chain has to keep well clear of that.

## 4. Assessment — is this feature still sensible?

**For the fork's actual user, yes — this is among the highest-value clusters, and probably the one with
the best value-to-coupling ratio after the AI abstraction.** For a heavy dictation user, the failure it
prevents is total: a long dictation lost to a process kill is minutes of speech that cannot be
reconstructed. Persist-first plus `findPendingInsertion` means the worst case degrades to "your text is
waiting in the keyboard, tap to paste" instead of "it's gone". The multi-file repository turns the
single most brutal `MediaRecorder` behaviour — overwrite at offset 0 — into a non-event. And the audit
chain is what makes the PC-dictation workflow legible: when text goes to a Windows machine over the
tailnet, `text_insertions` with `InsertionMethod.WINDOWS_DISPATCH` and `target_device_id` is the only
record that it happened at all.

**Would a user on 5.3 miss it?** Partly, and the split is informative. They would *not* miss basic
crash recovery — upstream's interrupted-WAV flow covers the common case (screen off, keyboard hidden,
service destroyed) and covers it well, arguably with a better trigger set than ours thanks to the
screen-off broadcast. They *would* miss: seeing which prompt in a chain produced which text;
re-processing an old dictation with a different prompt; recording again while the previous one is still
running; and audio surviving a crash *during* recording. Whether those matter depends entirely on
whether the user chains prompts and dictates back-to-back. For a light user, upstream's model is
sufficient and simpler.

**What keeping it costs.** This is the cluster with the heaviest ongoing tax. Eleven Room schema
versions with exported schemas and instrumented migration tests; the Double-Enum rule means every new
status value is a table recreate (SQLite cannot drop a CHECK); `PipelineOrchestrator` is 2,101 lines and
`SessionManager` 812. The migration history itself shows how easy this is to get wrong: v6→v7 exists
purely to backfill a gap left by v4→v5, and `F-000` — the IME start path allocating its first audio file
*outside* `AudioFileRepository`, an audio-loss bug — was found by the fork's own audit, not by a test.
The invariant "the repository is the sole owner of the naming convention" is exactly the kind that a
single missed call site silently breaks.

**Argued the other way:** the run queue in particular is the piece whose cost/benefit is genuinely
contestable. ADR-0009 is a substantial addition to the pipeline FSM — queue state on two arms, a
chain-start helper on every terminal arm, a submit-when-free seam with a bounded timeout — and upstream
serves the same users by simply saying no. If Lukas rarely starts a second recording before the first
finishes, that machinery is carrying its own weight and nothing else. The countervailing point is that
the queue is also what made headless completion (ADR-0011) and the ordered pending-parts flush coherent
rather than special-cased, and those *do* fire on the PC-dictation path.

**Upstream signal.** No explicit rejection of persistence — quite the opposite, they keep extending
history (`#140` in 5.1, `#240` in a later release). But concurrency is closed in code, per-step audit is
absent with a frozen-schema habit elsewhere in the codebase (the prompts table is *explicitly declared
frozen* for legacy compatibility, `PromptsDatabaseHelper.kt:23-30`), and cost/token tracking is a
rejected direction. The receptive surface is narrow and specific: the mid-recording-crash audio-loss gap,
and pagination.

## 5. Options going forward

**(a) Keep in the fork as-is.** The default and the coherent one. The schema is stable at v11, the
recovery algorithm is documented and tested, and clusters 16–21 plus the Windows dispatch path all
depend on it. Cost is the migration tax and the `PipelineOrchestrator` size; benefit is that the fork's
most valuable user-facing guarantee (nothing is ever lost) keeps holding.

**(b) Port to Dictate Keyboard 5.x as a private patch.** Selectively plausible, unusually so for a
fork-tied cluster. `AudioFileRepository` + the MediaMuxer concat is self-contained and would slot in
next to their `AudioConcat`, and the `processing_steps` table with `chain_index`/`version`/`is_current`
is ordinary Room that could be added alongside `DictateHistoryEntry` — their history DB is already Room
with an exported schema and a hand-written migration. What does *not* port is the persist-first ordering
and recovery, both of which presume a foreground service and a state container upstream does not have.
A patch would therefore give you the audit trail without the crash-safety, which is the less valuable
half.

**(c) Propose upstream via issue.** Two well-scoped candidates, both bug-shaped rather than
architecture-shaped, which is the shape that lands there:
  1. *"Recording is lost if the app is killed mid-recording."* Their own gap (area 10c). A good issue
     names the mechanism — the WAV header is patched only in `stop()` — and proposes the minimal fix:
     patch the header on a timer, or scan for orphaned WAVs at startup. This is close to what the
     unlanded companion branch already does on the desktop side ("always-valid recordings": session row
     written when the mic opens, WAV header patched on a tick, boot recovery).
  2. *"History does not paginate."* They have already hit it and written the comment; an issue proposing
     `androidx.paging` on the existing DAO is small, evidenced by their own code, and the settings screen
     is the natural first surface.
  A third — per-step history — is a bigger ask and runs into the frozen-schema habit; worth *gauging by
  issue* before writing anything.

**(d) Retire / let upstream's equivalent replace it.** Not viable while the fork exists. Cluster 15 is
load-bearing for the run queue, headless completion, the review panel, the history panel and the Windows
dispatch audit. Retiring persistence means retiring the fork, which is an overview-level decision.

*Leaning:* (a) for the fork, plus (c.1) and (c.2) as two genuinely useful, low-cost upstream issues that
report *their* bugs rather than pitching *our* architecture.

## 6. Information Gaps

1. **How often does Lukas actually start a second recording while one is processing?** The entire
   ADR-0009 run queue rests on this, and no usage data exists. *Owner: Lukas.* *Fallback:* the queue is
   already built and stable, so the answer changes nothing today; it only matters if the FSM ever needs
   simplification.
2. **How often has crash-during-recording actually fired in real use?** The `RECORDING → FAILED`
   recovery arm and the multi-file repository are insurance against a failure whose real-world frequency
   is unmeasured. *Owner: Lukas.* *Fallback:* treat OOM-kill-during-recording as rare-but-total and keep
   the protection; the cost is already sunk.
3. **Would DevEmperor accept a WAV-header-flush patch, or does he consider the interrupted-recording
   flow sufficient?** Unknown. *Owner: whoever files the issue.* *Fallback:* file the issue describing
   the loss scenario and let him choose the mechanism, rather than opening with a PR.
4. **Does our history retention need upstream's byte-budget model?** Our sweep only removes `CANCELLED`
   rows older than 60 days; total audio footprint on the device is unmeasured. *Owner: Lukas.*
   *Fallback:* measure `cacheDir` audio size before deciding — if it is small, the gap is theoretical.
5. **Is `AudioFileRepository` still the sole allocator after the July waves?** `F-000` proved one call
   site had escaped it once. *Owner: Lukas.* *Fallback:* a targeted grep for direct `File(...)`
   construction under the recording paths, ideally promoted into a source-scan invariant test like the
   one ADR-0010 uses for icon tints.
6. **ADR-0007's header still reads `Proposed` although it shipped.** Cosmetic but misleading to a future
   reader. *Owner: Lukas.* *Fallback:* flip to `Accepted` with a Decision-History entry naming the
   implementing commits at the next docs pass.

## 7. References

**Source reports**
- [`../sources/fork-feature-inventory.md`](../sources/fork-feature-inventory.md) — clusters 4, 13, 15 (and 28 for `F-000`/`F-012`/`F-014`/`F-047`)
- [`../sources/upstream-rewrite-analysis.md`](../sources/upstream-rewrite-analysis.md) — areas 3, 10c, 10d, 13, 14

**ADRs**
- [`../../../decisions/0007-audio-multi-file-repository.md`](../../../decisions/0007-audio-multi-file-repository.md) — the `MediaRecorder` append audit; header still says *Proposed*
- [`../../../decisions/0009-pipeline-run-queue-serialized-concurrency.md`](../../../decisions/0009-pipeline-run-queue-serialized-concurrency.md) — ordered queue, strictly serialized execution
- [`../../../decisions/0011-pipeline-headless-completion-fallback.md`](../../../decisions/0011-pipeline-headless-completion-fallback.md)
- [`../../../decisions/0003-service-foreground-pipeline-architecture.md`](../../../decisions/0003-service-foreground-pipeline-architecture.md) — the host that makes persist-first recoverable
- [`../../../decisions/0012-pipeline-post-processing-conversation.md`](../../../decisions/0012-pipeline-post-processing-conversation.md), [`0014`](../../../decisions/0014-in-keyboard-history-panel.md) — downstream consumers of the audit chain

**Plans & research**
- [`../../../plans/session-persistierung.md`](../../../plans/session-persistierung.md) (~1,150 lines, 5 phases) + `.state.md`
- [`../../../plans/dictate-reprocess-refactor.md`](../../../plans/dictate-reprocess-refactor.md) (2,765 lines) + `.chunks.json` + `.state.md`
- [`../../../plans/2026-05-21 - dictate-widget-state-and-recovery/`](../../../plans/2026-05-21%20-%20dictate-widget-state-and-recovery/) (Block B1 built the repository unwired), [`2026-05-22 - dictate-recording-stack-completion/`](../../../plans/2026-05-22%20-%20dictate-recording-stack-completion/) (Block A is the cutover)
- [`../../2026-07-02 - concurrent-recording-deferred-insertion.md`](../../2026-07-02%20-%20concurrent-recording-deferred-insertion.md) (855 lines), [`- history-reprocess-hardening.md`](../../2026-07-02%20-%20history-reprocess-hardening.md), [`- feature-wiring-code-review.md`](../../2026-07-02%20-%20feature-wiring-code-review.md) (2,314 lines)
- [`../../../DATABASE-PATTERNS.md`](../../../DATABASE-PATTERNS.md) — the Double-Enum rule and the migration workflow

**Fork commits**
`6608bfa2` (46 files, +4,359 / −39 — the audit schema), `79852ef0` (52 files, +7,486 / −2,236 — persist-first), the run-queue chain `7f484286`/`c1bfceb6`/`46383b16`/`28da773c`/`169f55d9`/`4a0c164f`, `d066a610` (routing-correctness hardening), `bc9390b7` (`F-000`, the audio-loss bug), `59ce0193` (`significantSegments`)

**Upstream evidence** (paths @ `upstream/main` `3e5ebe46`, tag `v5.3.0`)
- `dictate/DictateController.kt:598-604` (`canStartRecording` — concurrency forbidden), `:613-628` (mic tap cancels), `:446-449` (`carryOverAudio` in-memory only), `:2215-2216` + `:2226` + `:874-895` (interrupted-recording stash and the screen-off catch-all), `:2274` (`AudioConcat.concat`), `:1811-1819` + `:1910-1929` (`segmentMutex` ordered drain), `:1942-1965` (segments merged before a single history write), `:1467` (`originalForHistory`), `:2540-2559` (`retranscribeHistoryEntry`), `:2357-2359` (last-dictation pref)
- `dictate/data/history/DictateHistory.kt:49-90` (columns), `:95-96` (unpaginated DAO), `:143` (Room v3), `:306-342` (`prune()` with three caps)
- `ui/DictateHistoryLayout.kt:156-158` — UI-thread block on eager composition
- `app/settings/dictate/DictateHistoryScreen.kt:509-522` — the two-level per-step copy (`#240`)
- `data/prompts/PromptsDatabaseHelper.kt:23-30,44-47` — schema explicitly frozen
- Commit `852e7f2d` — usage/cost tracking deleted; issues `#111`, `#140`, `#147`, `#170`, `#240`
