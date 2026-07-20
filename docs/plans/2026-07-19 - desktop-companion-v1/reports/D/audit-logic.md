# Block D — Logic Audit (re-audit at HEAD)

**Topic:** logic · **Block:** D · **Date:** 2026-07-20T13:30:00+02:00
**Scope commit range:** c46cfe8..HEAD (file-scoped to BLOCK_FILES)
**Grounding:** knowledge-typescript (conceptual: null-safety, exhaustiveness, boundaries — module is Kotlin/JVM), knowledge-sql (NULL safety on the SQLDelight queries)

> This is a **re-audit**: two repair waves (`b99b141`, `3b9f980`) + a re-audit (`re-audit-W1.md`)
> have landed since the initial `audit-logic.md`. This pass (a) re-verifies the three prior logic
> findings are still fixed at HEAD, and (b) hunts for new logic defects, including any introduced by
> the repair waves.

## Coverage

Audited in depth (logic-bearing files at HEAD):

- `pipeline/` — `DictationReducer` (all phase transitions, confirm gate, re-dictate cancel/guard,
  queue enqueue+dedup, `finish`/`startRecording`), `DictationEffects` (the three shared-ai calls +
  persistence, all error paths, refinement + continuation), `JobQueue` (`SerialJobQueue` drain CAS
  race + dedup set lifecycle), `DesktopDictationController` (lock scope, effects-outside-lock
  reentrancy), `DesktopUiState`, `ConfigProfileSource` (profile→DictationProfile resolution, missing
  prompt-row drop, no-profile DEFAULT branch), `ActiveProfileSource`.
- `capture/` — `JavaSoundAudioCaptureService` (capture state machine, pause/resume line.stop ordering,
  finish/discard join+close ordering, roll threshold), `WavConcat` (single- vs multi-segment merge,
  zero-copy return), `WavHeader` (RIFF walk, truncation clamp, word-align pad), `WavWriter` (back-patch
  sizes, `bytesWritten` = data-only), `PcmAmplitude` (peak clamp), `RecordingBarDesign`
  (pushLevel/formatTimer/HSV math).
- `data/` — `DesktopSessionRepository` (turn/continuation transactions, `loadConversation` USER-by-turn
  pairing, chain-index/seq arithmetic, desktop-history reads), `CompanionConfigRepository` (upsert +
  hash recompute + ordered-prompt transaction), `SqlDelightHistoryRepository` (sync upsert idempotency,
  never-downgrade), `CompanionDatabase` (Double-Enum adapter wiring — all typed columns covered).
- `Companion.sq` — desktop-dictation writes, phone-sync history JOIN scoping, `upsertSyncSession` /
  `upsertDispatchState` never-downgrade, `addUsage` increment-upsert, `pageDesktopHistory` /
  `desktopHistoryEntry` (correlated transcript subquery, `instr(lower())` search, REVIEW_REFINEMENT
  exclusion), `usage` table.
- `ui/panel/` — `PanelViewModel` (timer accumulation across pause/stop/discard, glow reset).
- `Main.kt` — boot/hotkey/panel wiring.

Not audited (out of topic): SQLDelight migration snapshots (`2.sqm`/`3.sqm`/`*.db`) and parity tests
belong to `plan-and-api`/`test`; `verifySqlDelightMigration` was reported green by the re-audit against
the committed tree, and this pass changed nothing. `ui/profiles`, `ui/models`, `ui/prompts`,
`pipeline/review` directories in BLOCK_FILES **do not exist** (the deferred §9.3 UI surface —
`plan-and-api-D-3`, escalated to the main loop).

## Prior logic findings — re-verified at HEAD

| ID | Prior sev | State at HEAD |
|---|---|---|
| logic-D-1 | Critical | **Fixed, holds.** `Take.finish()` binds `val merged = WavConcat.merge(segments, File(recordingsDir, "${'$'}{takeId}.wav"))` (JavaSoundAudioCaptureService.kt:145) — consumes merge's return, so a single-segment short take gets the lone `{takeId}_1.wav` that exists. |
| logic-D-2 | Nice-to-have | **Fixed, holds.** `PcmAmplitude.peak` clamps `minOf(abs(sample), 32767)` (PcmAmplitude.kt:32). |
| logic-D-3 | Nice-to-have | **Fixed, holds.** `line.stop()` precedes `stopLoop()` in both `finish()` (`:137`) and `discard()` (`:154`); `pause()`/`resume()` also toggle the line (`:124`/`:128`). |

## Findings

### logic-D-4 (Nice-to-have) — `reinsert` sets `dispatch_state.dispatched` but never stamps `sessions.inserted_at`, so the two tables disagree on whether a synced text ever landed

**Files:** `companion/src/main/kotlin/net/devemperor/dictate/companion/domain/DispatchService.kt:70-75`
(`reinsert`), `companion/src/main/sqldelight/net/devemperor/dictate/companion/db/Companion.sq:394-397`
(`recordDispatch`), doc invariant at `SqlDelightHistoryRepository.kt:45-49` and `Companion.sq:360-364`.

`writeSyncRow` and `upsertSyncSession` document an explicit cross-table invariant: *"`inserted_at`
mirrors the dispatch flag so the archive agrees with the dispatch_state on whether the text ever
landed in a window."* The push-dispatch path (`DispatchService.dispatch`) upholds it — it calls
`upsert(..., dispatched = true)` first (stamping `sessions.inserted_at = receivedAt` via
`upsertSyncSession`) and only then `recordDispatch`.

**`DispatchService.reinsert` (the history "insert again" button) breaks it:** it does
`findById` → `inserter.insert` → `recordDispatch(sessionId, now, outcome)` with **no** `upsert`.
`recordDispatch` (`Companion.sq:394`) updates **only** `dispatch_state` (`dispatched = 1`,
`last_outcome`, `received_at`); it never touches `sessions.inserted_at`. So for a text that was synced
as *pending* (`SessionUpsert.dispatched = false` → `upsertSyncSession` wrote `inserted_at = NULL`,
`dispatch_state.dispatched = 0`), a subsequent re-insert leaves `dispatch_state.dispatched = 1` while
`sessions.inserted_at` stays **NULL** — the archive now claims the text never landed, contradicting the
dispatch row.

**Failure scenario:** phone lazily syncs an un-dispatched text (pending) → desktop user clicks
"insert again" in history → `reinsert` types it into the window and records the dispatch, but
`sessions.inserted_at` remains NULL. This is the ordinary "sync a pending text, then insert it on the
desktop" path, not an exotic one.

**Why Nice-to-have (currently latent):** no PHONE_SYNC read query consumes `sessions.inserted_at` —
`pageHistory` / `countHistory` / `receivedTextById` / `selectCursor` all read `ds.dispatched` from
`dispatch_state` (the read authority). The `desktopHistory*` queries that *do* select `inserted_at`
scope to `host_origin = 'DESKTOP_DICTATION'`, never PHONE_SYNC. So there is **no observable behaviour
impact today**. It is a genuine Room-parity gap on the `sessions` table — the very table D1a's ablation
exists to keep clean — that a future consumer (a unified desktop-style history over phone rows, a
sync-diff, an export) reading `sessions.inserted_at` would read wrong.

**Suggested fix:** have `recordDispatch` also stamp `sessions.inserted_at` (coalesced to never
downgrade), e.g. a companion `UPDATE sessions SET inserted_at = coalesce(inserted_at, :at) WHERE id =
:sessionId` alongside the `dispatch_state` update (both in one transaction), so both write paths
maintain the documented mirror. Add a regression test asserting `inserted_at` is non-NULL after a
`reinsert` of a pending synced row.

## Out-of-scope observations (for the consolidator)

- **plan-and-api / already-escalated:** `ConfigProfileSource` no longer resolves a *system* prompt
  (`systemPromptMode` deliberately unwired, F9) and the `ui/profiles`/`ui/models`/`ui/prompts` +
  `pipeline/review` dirs are absent — these are the §9.3 / profile-post-processing scope items already
  tracked as `plan-and-api-D-2` (part b) and `plan-and-api-D-3`, escalated to the main loop. Not
  re-derived as logic findings.
- **convention (known):** `submitContinuation` dedups the JobQueue by
  `"continuation-${'$'}{reviewSessionId}-${'$'}{clock.nowMillis()}"` (effectively never dedups) while
  the other two submit paths use a stable session id. Safe (reducer `refining`/`refinementRecording`
  guards prevent double dispatch), convention-only — as the initial audit already noted.
- **UX (known, not a code bug):** during a re-dictate the reducer keeps
  `DesktopUiState.recording = RecordingUi.Idle` (only `review.refinementRecording` flips), so
  `PanelViewModel`'s timer does not run for the S2 take; the ReviewPanel is expected to render its own
  recording affordance. Confirm at manual-E2E, already flagged in the initial consolidation.
- **Minor, not elevated:** `CaptureResult.durationSeconds = totalDataBytes / BYTES_PER_SECOND` is
  integer division, so a sub-second take persists `audio_duration_seconds = 0`. Display/metadata only,
  Room stores its own duration on the phone side — a rounding nit, not a defect.

## Coverage note

Files audited: every logic-bearing file listed under Coverage. Files skipped with reason: migration
snapshots + `*.db` (schema/`plan-and-api`), parity/`*Test` files (`test` topic), absent §9.3 UI
directories (not implemented — plan-and-api). No source changed by this pass; the tree remains the one
the re-audit reported green (`:companion:test` + `verifySqlDelightMigration`).
