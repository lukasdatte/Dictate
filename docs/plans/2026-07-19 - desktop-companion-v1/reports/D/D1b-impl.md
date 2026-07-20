# D1b — Aufnahme + Desktop-Pipeline — Impl Report

**Chunk:** D1b (Block D) · **Timestamp:** 2026-07-20T00:40:00+02:00
**Spec:** desktop-host.md §4 (Capture), §5 (Pipeline) · **Acceptance:** §2 Kriterien 5–6

## What I did (summary)

Built the desktop dictation `capture/` and `pipeline/` layers plus their transitional AI wiring:

- **capture/** — fixed 16 kHz/mono/16-bit WAV format (`CaptureFormat`), a streaming
  back-patching RIFF writer (`WavWriter`/`WavHeader`), rolling-segment merge (`WavConcat`),
  peak amplitude extraction (`PcmAmplitude`) feeding the `:shared-ai`
  `AmplitudeProcessor` (moved there in A2 per D5.e — reused, not copied), mixer
  enumeration + persistence (`AudioDeviceCatalog`), the javax.sound capture service
  (`JavaSoundAudioCaptureService`, `AudioCaptureService` port), and a WAV-header
  `AudioDurationReader` (`WavAudioDurationReader`).
- **pipeline/** — pure `DesktopUiState` + `DictationPhase`, pure `DictationReducer`
  `(state,intent)→(state,effects)` with **no IO**, the IO effect handler
  `DictationEffects` (the three `:shared-ai` calls + persistence + insert), a serial
  session-deduped `JobQueue` (ADR-0009), the `DesktopDictationController` dispatch door,
  and the transitional `ActiveProfileSource`.
- **data/** — `DesktopSessionRepository` (session/transcription/CONVERSATION_TURN
  step/SYSTEM+USER messages, turn written in one transaction) + named write queries in
  `Companion.sq` (no schema change) + the `processing_steps`/`conversation_messages`
  ColumnAdapters D1a deferred to D1b (`CompanionDatabase.kt`).
- **ai/** — transitional `CompanionAiConfig`, no-op `CompanionProxyConfig`, `NoopUsageSink`.
- **Integration** — `CompanionContainer.production()` constructs and wires the
  `AIOrchestrator` + `DesktopDictationController` + `DesktopSessionRepository`.

**Tests:** `:companion:test` green (36 new: WavCodec 8, DictationReducer 16, JobQueue 4,
DesktopDictationPipeline 3, CompanionSettingsAudio 5). `:companion:build` +
`verifySqlDelightMigration` green. `:app` untouched.

## Acceptance mapping

- **Kriterium 5 (headless E2E)** — `DesktopDictationPipelineTest.autoInsertTake_…`: WAV
  fixture + fake runners → Hotkey → record → transcribe → post-process (auto-format ⇒ a
  real turn) → auto-insert; asserts the persisted session (COMPLETED, DESKTOP_DICTATION,
  `inserted_at`), transcription (v1/is_current), one `CONVERSATION_TURN` step, and the two
  `SYSTEM`+`USER` messages. Plus bare-transcript (no turn) and REVIEW-verdict variants.
- **Kriterium 6 (reducer transitions + enqueue)** — `DictationReducerTest`: Start/Pause/
  Resume/Stop/Discard, `RECORDING→TRANSCRIBING→POST_PROCESSING→(INSERTED|REVIEW|FAILED|
  CANCELLED)`, ADR-0009 second-trigger **enqueues** (not discards), dequeue-starts-next,
  session dedup, stale-callback rejection.

## §4.5 Provider upload-limit verification (PRÜFAUFTRAG)

Fixed format cost: `16000 · 2 bytes · 60 s = 1,920,000 B ≈ 1.92 MB/min` (≈1.83 MiB/min).
Verified against each configurable transcription provider (values as of knowledge cutoff
2026-01; re-confirm against live docs before GA — flagged in §15 Gap 2):

| Provider | File limit | Headroom at 1.92 MB/min |
|---|---|---|
| OpenAI (whisper-1 / gpt-4o-transcribe) | 25 MB | ~13 min |
| Groq (whisper-large-v3) | 25 MB (free) / 100 MB (dev) | ~13 min / ~52 min |
| ElevenLabs (Scribe) | ~1 GB / multi-hour | effectively unbounded here |
| Custom (OpenAI-compatible) | provider-defined | assume 25 MB (OpenAI parity) |

**Conclusion:** the fixed WAV format is comfortably within every provider's limit for
realistic dictations (seconds to a few minutes); the 25 MB floor gives ~13 min of audio.
No format change needed for v1. Opus/OGG remains the documented later option (§15 Gap 2)
only if a provider tightens limits or very long single takes become a requirement.

## Deviations

| Deviation | Plan location | What changed | Why | Impact on later chunks | Resolved? |
|---|---|---|---|---|---|
| Transitional `AiConfig` returns hardcoded OpenAI-compatible defaults + empty key, not literally "aus CompanionSettings" | §5.1 NOTE | `CompanionAiConfig` hardcodes provider/model defaults; `apiKey()=""` | Reading a key from the plaintext settings table violates the secrets policy; inventing `ai.provider`/`ai.model` settings keys now only to delete them in D3 is throwaway churn (D4: long-term-best). Pipeline is fully wired + testable; the config source swaps to the SecretStore-backed profile in D3. | D3 replaces `CompanionAiConfig` with the `ProfileResolver`→`AiConfig`; no pipeline change (§5.1). | Yes (documented) |
| `usage` table + `SqlDelightUsageSink` deferred; `NoopUsageSink` used instead | §5.4, §10 | No `usage` table/migration in D1b | Adding a table needs its own `.sqm` + `databases/N.db` snapshot (verifyMigrations); D3 already opens a migration (D5.b `3.sqm`) — folding `usage` there avoids a second D1b migration number + snapshot churn on a table nothing reads yet. D1b acceptance (crit. 5) covers no usage accounting. | See delegated issue D1b-1 (D3 owner). | Deferred (delegated) |
| Panel show/hide behind a minimal `PanelControl` port (`.None` default) | §6.2 (D2) | Reducer `ShowPanel`/`HidePanel` land on a fun-interface | The real focus-free `PanelWindowControl` is D2; a seam here lets D1b wire + test the effect without pre-building D2's window. | D2 supplies the windowed impl; the effect contract is stable. | Yes (transitional seam) |
| Terminal `FAILED` hides the panel (keeps `PipelineUi.Failed`) | §5.2 | Reducer `finish()` hides on any terminal | The failure-panel-hold UX (how long to show the error banner) is a D2 panel concern; D1b models the phase + remembers the error. | D2 may hold the panel to surface the banner. | Yes (D2 UI decision) |

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| D1b-1 | Important | Persistent `usage` table + `SqlDelightUsageSink` deferred to D3's migration (avoids a D1b migration-number collision with D5.b `3.sqm` + a snapshot for a table nothing reads yet). D3 owner: add `usage` DDL to its migration and swap `NoopUsageSink` → `SqlDelightUsageSink` in `CompanionContainer.production()`. `companion/.../ai/NoopUsageSink.kt` | delegated | none |

## Integration call-site

`CompanionContainer.production()`
(`companion/src/main/kotlin/net/devemperor/dictate/companion/CompanionContainer.kt`) now
builds `AIOrchestrator(CompanionAiConfig, NoopUsageSink, RunnerFactory(…, CompanionProxyConfig,
WavAudioDurationReader))`, `DesktopSessionRepository(database)`, and a
`DesktopDictationController(DictationEffects(JavaSoundAudioCaptureService, …, SerialJobQueue,
SystemClock, TransitionalProfileSource, PanelControl.None))`, passing `desktopDictation` +
`desktopSessions` into the container. `forTest()` leaves both `null` (the sync-server graph
has no desktop host; the pipeline's own tests construct it with fakes).

## Helper decisions

- **Reused** `:shared-ai` `AmplitudeProcessor` (D5.e move) — no companion copy (spec §4.4
  offered copy-vs-shared; shared wins per D5.e).
- **New test fakes** (co-located in `DesktopDictationPipelineTest.kt`): `FakeAudioCapture`,
  `FakeTranscriptionRunner`, `FakeCompletionRunner`, `FakeRunnerFactory` (subclasses the
  `open` production `RunnerFactory` K-1 seam). Reused existing `FakeTextInserter` +
  `MutableClock`. `InlineJobQueue` (main) drives the headless E2E deterministically.

## Not unit-tested (by design)

`JavaSoundAudioCaptureService` + `AudioDeviceCatalog` are platform adapters (javax.sound
`TargetDataLine`/mixer enumeration, like the Win32 impls) — covered by the manual Windows/
Linux acceptance (§10, spec §2 crit. 10), not JVM unit tests. Their pure sub-parts
(`WavWriter`/`WavConcat`/`WavHeader`/`PcmAmplitude`/`WavAudioDurationReader`) are unit-tested.

## Files modified

New (main):
- companion/.../capture/CaptureFormat.kt, PcmAmplitude.kt, WavHeader.kt, WavWriter.kt,
  WavConcat.kt, AudioCaptureService.kt, AudioDeviceCatalog.kt,
  JavaSoundAudioCaptureService.kt, WavAudioDurationReader.kt
- companion/.../pipeline/DesktopUiState.kt, DictationIntent.kt, Effect.kt,
  DictationReducer.kt, JobQueue.kt, ActiveProfileSource.kt, PanelControl.kt,
  DictationEffects.kt, DesktopDictationController.kt
- companion/.../ai/CompanionAiConfig.kt, CompanionProxyConfig.kt, NoopUsageSink.kt
- companion/.../data/DesktopSessionRepository.kt

Edited (main):
- companion/.../CompanionContainer.kt (wire desktop pipeline into production())
- companion/.../data/CompanionDatabase.kt (register processing_steps/conversation_messages adapters)
- companion/.../domain/CompanionSettings.kt (audio.inputDevice, audio.rollingSegmentSec)
- companion/.../platform/AppPaths.kt (recordingsDirectory())
- companion/src/main/sqldelight/.../Companion.sq (named write queries — no schema change)

New (test):
- companion/.../capture/WavCodecTest.kt
- companion/.../pipeline/DictationReducerTest.kt, JobQueueTest.kt, DesktopDictationPipelineTest.kt
- companion/.../domain/CompanionSettingsAudioTest.kt

Edited (test):
- companion/.../data/ChordMigrationSeedTest.kt (direct-DB construction now passes the two new adapters)

## Drift (files outside my capture/pipeline scope)

- `CompanionDatabase.kt` — my adapter additions are co-located with a **pre-existing
  uncommitted 1-line comment tweak from parallel Block-B secrets work** (the
  `PRAGMA foreign_keys` comment). Harmless (the comment already describes the D1b cascade
  reality); flagged so the commit-agent knows the file carries a foreign hunk.
- `ChordMigrationSeedTest.kt` (D1a file) — updated only because my new adapters made the
  generated `DictateCompanionDb` constructor require them; mechanical, no assertion change.
- `Companion.sq`, `CompanionSettings.kt`, `AppPaths.kt` — in-scope EDIT targets per spec §10
  (not drift), listed here for completeness.

Not touched by me (pre-existing parallel B-work, must NOT be swept into my commit):
`app/.../secrets/*`, `companion/.../secrets/FileAesGcmSecretStore.kt`,
`shared/.../config/CanonicalJsonTest.kt`.
