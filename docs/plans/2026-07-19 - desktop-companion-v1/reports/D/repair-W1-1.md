# Repair Wave D-W1-1 — JavaSoundAudioCaptureService finish() fixes

**Date:** 2026-07-20T00:40:00+02:00
**Agent-ID:** repair-fix D-W1-1
**Cluster:** logic-D-1 (Critical), logic-D-3 (Nice-to-have) — both in the javax.sound capture adapter.

## logic-D-1 (Critical) — mergedWav pointed at a never-written file

`Take.finish()` discarded `WavConcat.merge()`'s return value and bound `CaptureResult.mergedWav`
to `File(recordingsDir, "{takeId}.wav")`. `WavConcat.merge` is zero-copy for a single segment: it
returns `segments.single()` (`{takeId}_1.wav`) and never writes the `{takeId}.wav` output. With the
default `rollingSegmentSeconds = 30`, every dictation shorter than 30 s (the common case) is
single-segment, so `mergedWav` referenced a non-existent file → `ai.transcribe` throws
`FileNotFoundException` → `PipelineFailed(UNKNOWN)`. Only takes that rolled ≥ 2 segments succeeded.

**Fix:** `finish()` now binds `mergedWav` to the value merge returns —
`val merged = WavConcat.merge(segments, File(recordingsDir, "${takeId}.wav"))`. This is the lone
segment for a single-segment take (matching `CaptureResult.mergedWav`'s own doc) and the written
concat file for a multi-segment take. A `why` comment records the zero-copy gotcha.
File: `companion/.../capture/JavaSoundAudioCaptureService.kt` (finish()).

## logic-D-3 (Nice-to-have) — line closed under a still-live capture thread

`finish()`/`discard()` called `stopLoop()` (`running=false`; `thread.join(1_000)`) BEFORE
`line.stop()`. A read loop wedged in `line.read` on a stalled driver outlives the 1 s join; the
method then closed the line and writer while the capture thread was still alive, and its next
`writer.write(...)` threw `IllegalStateException`.

**Fix:** `line.stop()` is now called BEFORE `stopLoop()` in both `finish()` and `discard()`.
Stopping the line unblocks the pending `line.read`, so the loop returns and the join resolves
promptly before close(). `line.stop()` is idempotent, so calling it here after a prior `pause()`
(which also stops the line) is harmless. A `why` comment records the ordering rationale.
File: `companion/.../capture/JavaSoundAudioCaptureService.kt` (finish(), discard()).

## Test added

`WavCodecTest.merge_returnValueAlwaysExists_soFinishUploadsARealFile` — regression guard for
logic-D-1 at the exact contract `finish()` now relies on: `WavConcat.merge(...)`'s return must be a
real, existing file for both the single-segment (zero-copy) and multi-segment paths. Guards against
re-introducing a `mergedWav` that points at an unwritten path.

Note on scope: a `finish()`-level unit test would need a fake/injectable `TargetDataLine` seam on
`JavaSoundAudioCaptureService` (the adapter has none today, and the finding's own note records the
platform adapter is not unit-tested). Adding that seam is a larger refactor than a Nice-to-have +
Critical repair warrants; the merge-contract regression test is the tightest in-scope guard for the
Critical bug's downstream effect.

## Tests

`./gradlew :companion:test` — BUILD SUCCESSFUL, all green (new test included).

## Files modified

- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/companion/src/main/kotlin/net/devemperor/dictate/companion/capture/JavaSoundAudioCaptureService.kt`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/companion/src/test/kotlin/net/devemperor/dictate/companion/capture/WavCodecTest.kt`

## Drift

none — both edits are within the cluster's files.

## Skipped

none — both findings fixed.
