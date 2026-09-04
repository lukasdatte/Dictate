---
date: 2026-08-11
author: Lukas + Claude (analysis session)
status: Research
context: Fork-vs-upstream analysis of the history system (in-keyboard panel + full-screen history) — what we built, what Dictate Keyboard 5.3 has, and whether the feature is still worth pursuing.
related-plan: n/a (plan-free research)
related-adrs: ADR-0014 (in-keyboard history panel), ADR-0010 (icon-tint theme attrs), ADR-0011 (headless completion / pending insertion), ADR-0009 (run queue)
---

# History System — Fork vs. Upstream Analysis

Everything the user dictated is stored and can be read back, re-inserted, played,
re-transcribed or re-processed — through two surfaces: a Paging3-backed panel *inside* the
keyboard on the short press of the history button, and a full-screen
`HistoryActivity`/`HistoryDetailActivity` pair on the long press. **Verdict: partially exists
upstream.** Dictate Keyboard 5.3 shipped a genuinely good history in 5.1 (`36851710`, `#140`)
with pin, search, backup/restore and a user-configurable audio-retention budget we do not have
— but it has no pagination at all (and a source comment conceding the UI stall that our Paging3
work solves), no per-step audit, no multi-segment audio and no "re-run a *different* prompt
against this transcript".

## 1. Feature Overview

Upstream 3.2 — our fork's base — persisted **nothing**. Audio was overwritten by the next
recording, the transcript and every AI output lived only in RAM, and a keyboard teardown
mid-pipeline destroyed the result. There was no history screen and no concept of a past
dictation. Everything in this document therefore starts from zero on our side.

The user value is two-layered, and the split matters. Re-inserting a result you produced 30
seconds ago is a **fast path**: you are inside a text field, you do not want to leave it, one
tap must commit the text. Reading the full text of an entry, hearing the audio back, comparing
what the transcription said against what the prompt chain made of it, or re-running an old
recording through today's better model is a **deliberate act** that earns a full screen.
ADR-0014 assigns those to the short press and the long press of the same edit-bar button.

The panel is pending-first: a run that completed while the keyboard was hidden — because the
pipeline outlives the IME (ADR-0003/ADR-0011) — appears at the top and one tap commits it. That
ordering is not a nicety; it is the visible half of the fork's headless-completion contract. It
is drag-resizable with a persisted height, auto-refreshes through Room's `InvalidationTracker`
while open, hides review-refinement carrier sessions, and a short press on a row opens an
in-panel detail surface (rows were previously truncated with no way to read the rest).

The full-screen side gives search (debounced, wildcard-escaped), filter chips, per-session
deletion, a step-by-step pipeline view with tap-to-expand and per-step copy, inline error
display, multi-segment audio playback as a playlist, version chips across regenerated steps,
and a **transcription re-run** that re-transcribes stored audio with the current model — with a
staleness warning when downstream steps came from an older transcription.

## 2. Our Implementation

Both surfaces sit on the persisted pipeline schema from clusters 4 and 15 (`sessions` →
`transcriptions` → `processing_steps` → `text_insertions`, insert-only versioning). That schema
is the expensive part; the history UIs are comparatively thin readers on top of it.

**In-keyboard panel (cluster 18, ADR-0014).** `history/KeyboardHistoryPager.kt` wraps a
dedicated `SessionDao.pagedHistoryPanel()` query (`PAGE_SIZE = 40`, pending-first `ORDER BY`,
`REVIEW_REFINEMENT` carriers excluded) with a `cachedIn` that takes a **caller-owned
`CoroutineScope`** — an IME is neither a `LifecycleOwner` nor a `ViewModelStoreOwner`, so the
`viewModelScope` machinery of the full-screen screen cannot be reused verbatim.
`KeyboardHistoryController.kt` owns that scope and centralises the three cancel points
(input-view destroy, input-view rebuild, panel close). State-side it is a minimal
`HistoryPanelState(open: Boolean)` axis on `HistoryPanelModule` with an auto-close cascade on
IME teardown, on recording start, and when the review panel opens; rendering is
`HistoryPanelRenderer` against `LayoutModeId.KEYBOARD_HISTORY_PANEL` — the first surface
*taller* than the button grid. `SessionRowPredicates.kt` is parity-tested against the SQL
`ORDER BY` so the Kotlin and SQL notions of "pending" cannot drift.
`keyboard/VerticalDragResizeHandler.kt` is a reusable resize primitive; the height lives in
`Pref.HistoryPanelHeightDp`. Migration **v8→v9** widens the `origin` CHECK by
`REVIEW_REFINEMENT` and retrofits the `sessions.type` Double-Enum CHECK.

**Full-screen history (cluster 19).** `HistoryActivity.kt` (converted from Java) +
`HistoryViewModel.kt` build a `Pager(pageSize = 40)`, apply filter chips immediately, debounce
search, and escape `LIKE` wildcards through `database/dao/LikeEscape.kt`; deletes run on an IO
context and self-refresh via Room invalidation. `HistoryDetailActivity.java` (926 LOC) drives
`PipelineStepAdapter.kt` (596 LOC, rewritten from a 332-line Java adapter) with
`StepExpansionState.kt` preserving expansion across rebinding, `SessionErrorFormatter.kt`,
`TranscriptionStaleness.kt`, and `HistoryAudioPlayer`/`HistoryAudioResolver` playing
multi-segment recordings as a playlist. ADR-0010 (icon colour from theme attributes at the
usage site, enforced by a JVM source-scan test) was born in this wave because history icons
were the surface where light/dark tinting broke first.

**Retention, ours.** `PipelineOrphanCleaner` deletes `CANCELLED` sessions older than 60 days
(`CANCELLED_RETENTION_MS_DEFAULT`) and sweeps orphaned terminal audio; `CacheAudioCleanupJob`
removes stale segment and merged files from `cache/audio/` while protecting non-terminal
sessions. **There is no cap on successful sessions and no audio byte budget** — a heavy user's
audio grows unbounded.

**Size.** Cluster 18 ≈ +3,878 / −48; cluster 19 ≈ +4,975 / −877. Combined roughly 9,000 added
lines plus their tests.

**Coupling.** The panel is fork-tied through its state axis and the LayoutCatalog. The
full-screen pair is **the most portable cluster in the history area** — ordinary Activities
plus a ViewModel that touch the state store only through `ActiveJobRegistry`/`JobState` refresh
ticks and dispatch re-runs through `JobExecutor`. Both have a hard data dependency on cluster
15's schema.

## 3. Upstream 5.3 Status

**Verdict: partially exists.** Upstream's history is one Room entity, not a pipeline audit
trail.

**Present, and in two respects better than ours:**

- `@Database(entities = [DictateHistoryEntry::class], version = 3)`
  (`dictate/data/history/DictateHistory.kt:143`) with an exported schema and a hand-written
  `MIGRATION_2_3`. Per-entry columns (`:49-90`): `text`, `originalText`, `createdAt`,
  `providerId`, `providerName`, `model`, `language`, `durationSecs`, `audioPath`, `audioBytes`,
  `source` (`keyboard`/`overlay`/`realtime`/`import`), `reworded`, `pinned`, `failed`.
- **Audio retention with three user-configurable caps**, enforced in `prune()` (`:306-342`)
  against prefs `historyMaxEntries`, `historyMaxAgeDays`, `historyAudioBudgetMb`
  (`AppPrefs.kt:601-620`): drop entries past the count cap or the age cutoff entirely, then
  walk the survivors newest-first and drop the **audio only** of the oldest ones once the byte
  budget is exceeded — the text survives. Pinned entries are exempt from all three and a
  `totalAudioBytes()` readout feeds a disk-usage line in settings. **We have no equivalent**,
  and this is the clearest thing to take *from* upstream.
- Pin, backup/restore, failed-entry logging, a sensitive-field gate, share and **audio export
  to Downloads** from the settings `DetailDialog`
  (`app/settings/dictate/DictateHistoryScreen.kt:445-591`) with `MediaPlayer` playback and a
  progress ring.
- **Re-transcribe: yes.** `DictateController.retranscribeHistoryEntry` (`:2540-2559`) re-runs
  the stored WAV through the whole chain and updates the entry in place; exposed from the
  in-keyboard panel (`ui/DictateHistoryLayout.kt:180-183`).
- Two-level output history: `originalForHistory` is set only when the prompt chain actually
  changed the text (`DictateController.kt:1467`), and the settings dialog renders raw and final
  as two labelled sections each with a copy button (`7047202e`, `#240`). Long-pressing a panel
  row inserts the raw transcript instead of the rewritten one.

**Absent vs. ours:**

- **No pagination whatsoever.** No `androidx.paging` anywhere, no `LIMIT`/`OFFSET`. The DAO is
  `SELECT * FROM dictate_history ORDER BY pinned DESC, createdAt DESC` returning a
  `Flow<List<…>>` (`DictateHistory.kt:95-96`) and both UIs consume the whole table, relying on
  `LazyColumn` windowing. The source says so plainly (`ui/DictateHistoryLayout.kt:156-158`,
  verified verbatim):

  > "Lazy on purpose: a full history is up to several hundred entries, and composing them all eagerly blocked the UI thread for well over a second — which is what froze the loading spinner (and everything else) while the panel opened."

  That is exactly the wall our Paging3 work removes; they hit it, diagnosed it correctly, and
  patched around the symptom. The `Flow<List<…>>` still materialises every row, every audio
  path and every text blob on each emission.
- **No per-prompt-step history.** A three-prompt auto-apply chain stores first-in and last-out
  only. There is no counterpart to `processing_steps`, `PipelineStepAdapter`,
  `StepExpansionState` or the version chips — and none is possible, because upstream's
  post-processing chain does not persist its intermediate steps at all (see
  [`conversation-postprocessing-and-review.md`](conversation-postprocessing-and-review.md)).
- **No multi-segment audio storage.** Long-form segments live in `segmentAudioFiles:
  HashMap<Int, File>` and are merged by `AudioConcat.concat` into one `dictate_seg_merged.wav`
  before the single history write (`DictateController.kt:1942-1965`). No segment table, no
  segment column, no playlist playback.
- **No prompt choice on re-run.** Re-transcribe replays the whole stored chain; you cannot run
  a *different* prompt against a stored transcript. Our `PromptChooserBottomSheet` + reprocess
  flow (see [`prompt-management-and-pills.md`](prompt-management-and-pills.md)) has no
  equivalent.
- No search and no detail view **in the keyboard panel** — search is settings-only and is an
  in-memory `contains` filter over the fully loaded list.
- No pending-first concept, because there is nothing to be pending about: upstream forbids
  concurrent recording (`canStartRecording()`, `DictateController.kt:598-604`) and has no
  foreground pipeline that outlives the IME.

## 4. Assessment — is this feature still sensible?

**Does the user need it?** Yes, and it is one of the few clusters where that is not in
question. A heavy dictation user re-inserts, re-reads and occasionally re-runs; the fork's own
usage pattern (long PC-dictation sessions, results arriving while the keyboard is hidden) makes
the pending-first panel load-bearing rather than decorative. The part whose value is *genuinely
uncertain* is the depth: per-step expansion, version chips and transcription staleness are
debugging affordances. They were indispensable while building the pipeline. Whether they are
still opened monthly is unknown (gap 1).

**Would a user on 5.3 miss ours?** Partly. They would get pin, retention, backup and search on
day one, and lose the step audit, the version history and the prompt-chooser re-run. For a user
who mostly re-inserts recent text, upstream's history is adequate. For our fork's own workflow
— where a session can be re-processed with a *different* prompt chain as a normal operation —
upstream's model does not reach.

**What does keeping it cost?** Less than most clusters. The two full-screen Activities are
ordinary Android code with a ViewModel and a Paging source; they do not participate in the
state store's cascade rules and are the cheapest large surface in the fork to maintain. The
panel is more expensive: it owns a state axis, a LayoutMode taller than the grid, a
caller-owned coroutine scope with three cancel points, and a Kotlin/SQL parity contract between
`SessionRowPredicates` and `pagedHistoryPanel()`. The real cost is not the UI at all — it is
the audit schema underneath (clusters 4/15), which everything else in the fork also depends on,
so history does not carry that bill alone.

**Upstream signal.** Positive and specific. History is an actively iterated area (`#140`
shipped it, `#240` extended per-entry copying, retention got its own prefs), and DevEmperor's
pattern is to implement well-scoped, issue-anchored requests himself and fast. A "history panel
takes >1s to open on large histories" issue would land on a problem he has already written a
comment about — the single most likely-to-be-welcomed item anywhere in this analysis. The
counterweight: converting a `Flow<List<…>>` consumed by two Compose surfaces into a
`PagingSource` is a real change to both UIs, not a drive-by, and their pin-first ordering plus
in-memory search filter both assume the full list is in hand. An issue is cheap; the PR is
medium-sized.

**The reverse direction deserves equal weight.** Their `prune()` is 35 lines of straightforward
code solving a problem we have not solved at all. Our audio retention protects against
*orphans*, not against *volume*. A user dictating daily for a year accumulates audio without
bound, and the only thing standing between that and a full device is manual deletion. Porting
the three-cap model (entry count, age, audio byte budget, pinned exempt) into
`PipelineOrphanCleaner` is small, self-contained work with a clear user benefit — arguably the
highest value-per-line item this comparison surfaced.

## 5. Options going forward

**(a) Keep in fork as-is.** Cheapest by far; the feature is complete, tested and already paid
for. Cost is ongoing drift: every schema change to `processing_steps` touches
`PipelineStepAdapter`, and the panel's parity contract has to be re-checked whenever the
pending predicate moves. No new capability appears from doing nothing.

**(b) Port to Dictate Keyboard 5.x as a private patch.** The full-screen pair would transplant
with modest rewiring *if* the audit schema came along — but it does not exist upstream, so this
is really "port clusters 4/15 and then history", which is the largest possible version of the
work. Not proportionate for a history screen alone.

**(c) Propose upstream via an issue.** Two sharply different candidates. The strong one:
*"History panel blocks the UI thread on large histories — paginate the DAO"*, referencing their
own comment at `DictateHistoryLayout.kt:156-158`, proposing `PagingSource` + `LazyPagingItems`
for both surfaces and naming the two things that must be redesigned along the way (pinned-first
ordering across pages, and the settings search which currently filters an in-memory list).
Scoped, evidenced, anchored to code they wrote — the shape that gets merged. The weak one:
per-step history, which is not a UI feature at all but a request to persist intermediate
pipeline steps; it presupposes their post-processing chain becoming step-aware and would be a
large architectural ask on a codebase that is effectively single-author.

**(d) Retire / let upstream replace it.** Only coherent as part of a wholesale move to 5.3, and
it would mean giving up the reprocess-with-chosen-prompt flow and the pending-first
re-surfacing that our concurrent-pipeline design produces. Nothing about history *in isolation*
argues for retirement.

**(e) Reverse-port their retention model into our fork.** Take `prune()`'s three caps and the
pinned-exemption into `PipelineOrphanCleaner`, plus a disk-usage readout. Small, independent of
every other decision here, and it closes a gap that will otherwise surface as a full-storage
complaint.

*Leaning:* keep and maintain (a), do (e) regardless of what happens to the rest, and treat the
pagination issue (c) as the single most promising upstream contribution in the whole fork.

## 6. Information Gaps

1. **How often are the deep detail affordances actually used** — per-step expansion, version
   chips, transcription staleness? *Owner:* Lukas. *Fallback:* instrument
   `HistoryDetailActivity` for a fortnight, or accept that they are build-time debugging tools
   whose value is already realised and whose maintenance is near-zero.
2. **Current history volume and audio footprint on the daily-driver device.** *Owner:* Lukas.
   *Fallback:* `SELECT COUNT(*) FROM sessions` and `du -sh files/recordings` on the device via
   ADB; without this, option (e)'s urgency is a guess.
3. **Would DevEmperor accept a Paging3 dependency** in the app module, and does the
   pinned-first + in-memory-search design have a constraint we cannot see from outside?
   *Owner:* an upstream issue. *Fallback:* propose an offset-paged DAO with no new dependency
   as the fallback variant in the same issue.
4. **Does the reprocess-with-a-different-prompt flow get used often enough to be a blocker** in
   a hypothetical move to 5.3? *Owner:* Lukas. *Fallback:* treat it as blocking, since it is
   the one history capability with no upstream path at all.

## 7. References

- [`../sources/fork-feature-inventory.md`](../sources/fork-feature-inventory.md) — clusters 18
  (in-keyboard history panel) and 19 (full-screen history)
- [`../sources/upstream-rewrite-analysis.md`](../sources/upstream-rewrite-analysis.md) — area 3
  (history system), area 10(d) (concurrency), area 13 (session persistence)
- [`../../../decisions/0014-in-keyboard-history-panel.md`](../../../decisions/0014-in-keyboard-history-panel.md),
  [`0010-ui-icon-tint-theme-attrs.md`](../../../decisions/0010-ui-icon-tint-theme-attrs.md),
  [`0011-pipeline-headless-completion-fallback.md`](../../../decisions/0011-pipeline-headless-completion-fallback.md)
- [`../../2026-07-02 -
  history-pagination-and-scale.md`](../../2026-07-02%20-%20history-pagination-and-scale.md),
  [`../../2026-07-02 - history-ui-overhaul.md`](../../2026-07-02%20-%20history-ui-overhaul.md),
  [`../../2026-07-02 -
  history-reprocess-hardening.md`](../../2026-07-02%20-%20history-reprocess-hardening.md)
- Plans (untracked working copies): `tmp/plan-paket3-history-panel.md`,
  `tmp/plan-history-drag-and-pill-fix.md`
- Our commits: `b30dfd7d`…`f90fdba6`, merge `f83ddcd9`, `ac029902`, `b864beeb`, `fede2c5a`
  (panel); `567a5a2b`, `7f66f66f` (full-screen)
- Upstream @ `upstream/main` (`3e5ebe46`):
  `dictate/data/history/DictateHistory.kt:49-90,95-96,143,306-342`;
  `dictate/ui/DictateHistoryLayout.kt:156-158,180-183`;
  `app/settings/dictate/DictateHistoryScreen.kt:445-591,509-522`;
  `dictate/DictateController.kt:598-604,1467,1942-1965,2540-2559`; `app/AppPrefs.kt:601-620`;
  commits `36851710` (`#140`), `7047202e` (`#240`), issue `#170` (long-form segmentation)
