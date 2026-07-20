# D1b — Aufnahme + Desktop-Pipeline — Self-Fix Report

**Chunk:** D1b (Block D) · **Timestamp:** 2026-07-20T00:40:00+02:00
**Role:** fresh-eyes self-fix over wave commit `c395491` (scope = D1b CHUNK_FILES only)
**Spec:** desktop-host.md §4 (Capture), §5 (Pipeline) · **Acceptance:** §2 Kriterien 5–6

## Verdict

The implementation is faithful to spec §4/§5 and the three `:shared-ai` contracts
(`ConversationTurnBuilder.hasWork`/`buildFirstUserMessage`, `ReviewDecision.decide`,
`AmbiguityMode.forcesTurn`). Reducer purity holds, the Room-parity write path is correct
(one-transaction turn, host_origin scoping), the headless E2E covers crit. 5 and the reducer
suite covers crit. 6. Two fixes applied inline; both defensible against the spec. Full
`:companion:test` + `verifySqlDelightMigration` green after the fixes.

## Fixes applied (inline, in scope)

### 1. Pause did not stop the capture line — spec §4.3 deviation (correctness)

`JavaSoundAudioCaptureService.Take.pause()`/`resume()` only toggled the `paused` flag and let
the read loop sleep. Spec §4.3 explicitly mandates `line.stop()` on pause. javax.sound has no
`MediaRecorder`-style pause: a line left *running* while the loop merely stops reading keeps
capturing into its internal buffer, which overruns and then **leaks the during-pause audio out
on the first read after resume** — the paused segment is not silent. Fixed by calling
`line.stop()` in `pause()` and `line.start()` in `resume()` (start-line-before-clearing-paused
so the loop never reads a stopped line). This was undocumented drift — the impl report's
deviation table did not mention pause semantics.

Not unit-testable (platform adapter, no mic in CI — covered by the manual Windows/Linux
acceptance, §10); the change is a direct transcription of the spec's stated pause contract.

### 2. Dead `terminalPhase` parameter + misleading comment in `DictationReducer.finish()` (clean-code)

`finish(state, terminalPhase, pre, terminalPipeline)` never read `terminalPhase` in its body —
the resting state is hard-coded to `phase = IDLE` in the queue-empty branch and `startRecording`
in the queued branch. The three call sites passed `CANCELLED` / `FAILED` / `INSERTED`, and the
KDoc claimed those values "are applied when the queue is empty", which is false (only
`terminalPipeline` is). A future reader would reasonably believe the resting phase depends on the
terminal reached. Removed the unused parameter from the signature and all three call sites, and
rewrote the KDoc to state the real invariant: the resting phase is always IDLE because the
terminal (COMPLETED/FAILED/CANCELLED) was already recorded on `sessions.status` (§5.2), and
`terminalPipeline` is the single carry-over that keeps a Failed banner on the hidden panel. The
existing `pipelineFailed_movesToFailedAndSurfacesTheErrorBanner` test (asserts `phase == IDLE`,
`pipeline == Failed`) confirms this was always the intended design — behavior unchanged.

## Reviewed, no change needed

- **Reducer purity** — confirmed no IO/clock/UUID in `DictationReducer.kt`; all side effects are
  named `Effect`s executed by `DictationEffects`. Session ids minted in the controller, carried
  on intents. Stale-callback rejection (`activeSessionId` guard) and phase guards present on
  every transition.
- **Queue dedup / FIFO** — `SerialJobQueue` `known`-set tracks a session from submit until its
  job returns (single-worker daemon; the poll-miss-then-clear race is handled by the re-drain
  check). `InlineJobQueue` correctly runs on the calling thread for the deterministic E2E.
- **Room-parity write** — `persistConversationTurn` writes step + SYSTEM + USER + `final_output_text`
  in one `database.transaction {}` (ADR-0013 §3). `transcriptions` needs no ColumnAdapter
  (its only typed column is a `kotlin.Boolean` built-in) — the `CompanionDatabase` comment is
  accurate. Named queries add no CREATE, so `verifyMigrations` stays green.
- **WAV byte machinery** — `WavHeader.dataChunk` walks chunks (handles a leading `LIST`/`fact`),
  clamps a declared-longer-than-file `data` size (crash-truncated take), word-aligns odd chunks;
  `WavWriter` back-patches both size fields idempotently; `WavConcat` is zero-copy on one segment.
  All exercised by `WavCodecTest`.
- **Transitional seams** — `CompanionAiConfig` (empty key, secrets-policy-honest), `NoopUsageSink`
  (usage table deferred to D3's migration — delegated issue D1b-1 in the impl report),
  `CompanionProxyConfig` (no-op), `PanelControl.None`, `TransitionalProfileSource`
  (ALWAYS_INSERT plain transcription) are all honest, documented pre-D2/D3 transitional wiring,
  not stubs-instead-of-impl.
- **§4.5 upload-limit verification** — the impl report's provider table is a reasonable
  knowledge-cutoff snapshot; the "re-confirm before GA" flag (§15 Gap 2) is the right disposition.

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| — | — | None newly delegated. The impl report's D1b-1 (usage table → D3 migration) stands as previously recorded. | — | none |

## Files modified

- `companion/src/main/kotlin/net/devemperor/dictate/companion/capture/JavaSoundAudioCaptureService.kt`
  — `pause()`/`resume()` now `line.stop()`/`line.start()` (spec §4.3).
- `companion/src/main/kotlin/net/devemperor/dictate/companion/pipeline/DictationReducer.kt`
  — removed dead `terminalPhase` parameter from `finish()` + 3 call sites; corrected KDoc.

## Drift (files outside CHUNK_FILES)

None. Both edits are inside the assigned CHUNK_FILES. The working tree also carries
`app/**` and `shared/**` modifications from parallel Block-B/Block-C agents — I did **not**
touch them, and the fix commit must stay file-scoped to the two companion files above.

## Final test result

`./gradlew :companion:test :companion:verifySqlDelightMigration` — BUILD SUCCESSFUL (green).
