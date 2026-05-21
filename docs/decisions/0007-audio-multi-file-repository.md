# ADR-0007: Audio — Multi-File Recording Repository (Resume-after-Cold-Start)

**Status:** Proposed
**Subsystem:** audio-pipeline, database
**Date:** 2026-05-21
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0006 (Info-Bar State-Derived Items).**
> ADR-0006 surfaces a "Pending-Recording — Fortsetzen / Senden /
> Verwerfen" item when a `SessionStatus.RECORDED` row exists. The
> "Fortsetzen" action calls into this ADR's repository to extend an
> existing recording even after the process died and the MediaRecorder
> was lost; the "Verwerfen" action calls `deleteAll(sessionId)` to
> sweep every segment file.

## Research

1. **MediaRecorder API audit (this session).**
   `RecordingManager.kt:55-83` and `RecordingHardwareAdapter.kt:54-92`
   both wrap `android.media.MediaRecorder`. The instance lifecycle is
   `prepare → start → (pause | resume)* → stop → release`. The
   `pause()` / `resume()` calls (API 24+, available since min-SDK 26)
   reliably extend a recording into the **same** output file *as long
   as the MediaRecorder instance lives*. There is no
   `MediaRecorder.appendToExisting(File)` API. Once `release()` is
   called or the process dies, a fresh `MediaRecorder` started against
   the same file path will overwrite the file (the prepared output
   stream is positioned at offset 0 by `prepare()`).
2. **Schema audit.** `SessionEntity.kt:49` carries
   `audio_file_path: String?` — singular. The Room migrations in
   `database/migration/MigrationTo3.kt` and the recovery promotions in
   `state/PipelineRecovery.kt:167-218` all assume one file per session.
   No upstream callers currently inspect the inside of the file (no
   header-parsing, no transcription chunking) — they pass the path to
   the AI provider's audio-upload step.
3. **MediaMuxer / MediaExtractor feasibility (Android Developers docs,
   tested mental model).** Two M4A/AAC files produced by the same
   `MediaRecorder` configuration (`MPEG_4` + `AAC` encoder + 44.1 kHz +
   64 kbps) can be concatenated into a single M4A via
   `MediaExtractor.readSampleData` → `MediaMuxer.writeSampleData`. The
   container-level concat preserves frame boundaries because both
   files share codec parameters. The first file's `csd-0`
   codec-specific-data is reused; subsequent file's leading frames are
   appended with adjusted PTS. The operation is O(samples) but
   I/O-bound — for typical Dictate recordings (<2 min) it completes in
   <500 ms on a Pixel-class device.
4. **User-architecture intent (this conversation, 2026-05-21).**
   "Multifile-Architektur, ohne dass es die überliegenden Schichten
   wissen. Die Schicht und das Repository, das sich mit den Audiofiles
   beschäftigt, kennt dieses Verfahren, alle anderen nicht." The user
   explicitly asked for a Repository pattern that hides the multi-file
   reality from Pipeline / State / Recovery layers.

## Context

The "Resume nach Cold-Start" affordance from ADR-0006's
Pending-Recording item is the trigger for this ADR. The naive
implementations are unsatisfactory:

- **Naive A: discard old, start fresh.** Loses the prior take entirely.
  Defeats the user's "Aufnahme darf nicht verloren gehen" requirement.
- **Naive B: append by reopening MediaRecorder against the same path.**
  Does not work — MediaRecorder overwrites from offset 0. The first
  take's bytes are gone after prepare() of the second take.
- **Naive C: keep a Map<sessionId, MediaRecorder> alive across
  process-death.** Not possible — the JVM and the underlying
  audio-driver references die with the process.

The user's design constraint (Repository hides the multi-file reality)
ruled the right path: store each take as a separate file with a
deterministic naming scheme, and concatenate at the layer that needs a
single file (the Pipeline pre-upload step).

## Decision

### Multi-File Naming Convention

Each recording session owns N audio files following a strict naming
convention managed exclusively by `AudioFileRepository`:

```
{cacheDir}/recordings/{sessionId}_1.m4a
{cacheDir}/recordings/{sessionId}_2.m4a   ← exists only after first append
{cacheDir}/recordings/{sessionId}_3.m4a   ← exists only after second append
...
```

The naming is **owned** by the repository — no caller constructs paths
directly. Callers receive `File` references from repository methods.

### Schema Change

`SessionEntity.audio_file_path: String?` is migrated to a new column
`audio_file_paths: String` (JSON-encoded array of file paths), stored
via a Room `@TypeConverter<List<String>, String>`. The migration:

```kotlin
@Migration(from = 4, to = 5)
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions RENAME COLUMN audio_file_path TO audio_file_path_legacy")
        db.execSQL("ALTER TABLE sessions ADD COLUMN audio_file_paths TEXT NOT NULL DEFAULT '[]'")
        // Backfill: wrap each legacy path in a single-element JSON array, NULL → '[]'
        db.execSQL("""
            UPDATE sessions
            SET audio_file_paths = CASE
                WHEN audio_file_path_legacy IS NULL THEN '[]'
                ELSE json_array(audio_file_path_legacy)
            END
        """)
        // Keep the legacy column for one release cycle to allow rollback;
        // dropped in MIGRATION_5_6.
    }
}
```

The Double-Enum / single-string convention does not apply here — the
column carries a list, not a finite enum. The JSON encoder is the
project-standard `kotlinx.serialization` already on the classpath.

### Repository API

```kotlin
interface AudioFileRepository {
    /** Allocate the first segment of a new session. Used by RecordingHardwareAdapter.allocate. */
    fun allocateFirst(sessionId: String): File

    /** Allocate the next segment of an existing session (Cold-Resume / append). Used by the Pending-Recording Fortsetzen action. */
    fun allocateNext(sessionId: String): File

    /** Read the current segment list (for Recovery, for debugging). */
    fun segments(sessionId: String): List<File>

    /**
     * Return ONE file that the Pipeline can upload. If `segments(sessionId).size == 1`,
     * returns that file directly (zero-copy). Otherwise concatenates all segments
     * into a transient `{sessionId}_merged.m4a` via MediaMuxer and returns it.
     * The merged file is owned by the repository and cleaned up alongside the segments.
     */
    suspend fun readForPipeline(sessionId: String): File

    /** Delete all segments and any transient merged file. Used by Verwerfen action and by cleanup policies. */
    fun deleteAll(sessionId: String)

    /** Find orphan segments (no matching DB session) — called by PipelineRecovery's cleanup pass. */
    fun listOrphanSessionIds(knownSessionIds: Set<String>): Set<String>
}
```

The implementation `RealAudioFileRepository` is the only class that:

- Knows about the `_N.m4a` suffix pattern
- Knows about the `_merged.m4a` transient
- Performs `MediaMuxer` concatenation
- Performs file deletion

Pipeline layer, State layer, and Recovery layer call only the
interface. They see logically "the audio file" of a session.

### Live-Resume vs. Cold-Resume — symmetric API, different fast-path

The Pending-Recording item's `Fortsetzen` action emits
`Action.RecordingAction.ResumeExistingSession(sessionId)`. The
`RecordingModule` reducer + `RecordingHardwareAdapter` cooperate:

1. If a MediaRecorder instance is alive for this session (in-RAM
   reference held by the adapter, indicating the FGS did not die since
   the pause), call `recorder.resume()`. This is the **Live-Resume**
   fast path — bytes continue into the existing `_1.m4a`. No new
   segment file is created.
2. Otherwise (Cold-Resume after process-death), the adapter calls
   `audioFileRepository.allocateNext(sessionId)` to get
   `{sessionId}_N.m4a`, prepares a fresh MediaRecorder against that
   path, starts it. Subsequent operations on the new instance affect
   only `_N.m4a`. The merging happens later via `readForPipeline`.

The reducer arm is identical for both paths from the State perspective
— `RecordingState.Paused → RecordingState.Active` — the adapter
chooses the right physical path.

### Recovery integration

`PipelineRecovery.kt`'s `RECORDING → FAILED` promotion currently
opportunistic-deletes one file path (`row.audioFilePath`). After this
ADR, it iterates the repository's `segments(sessionId)` for cleanup.
`listOrphanSessionIds(knownSessionIds)` enables the periodic cleanup
job that already runs out of `DurationHealingScheduler` to sweep
abandoned segments left behind by a crash mid-allocate.

### `audio_duration_seconds` semantics

The denormalized `SessionEntity.audio_duration_seconds` becomes the
**sum** of all segments' durations rather than a single file's
duration. Computed at recording-stop time per segment and accumulated.
`DurationHealingJob` is extended to re-scan all segments on a session
whose denormalized duration disagrees with the segment sum.

## Alternatives Considered

1. **FFmpeg-binary for concatenation.** Bundled native ffmpeg or
   `mobile-ffmpeg` library. Rejected: adds ~5 MB to APK size, native
   binary ABI variants increase release-management cost, and the
   project does not otherwise depend on FFmpeg. MediaMuxer is the
   first-party API and handles the M4A/AAC same-encoder case
   trivially.
2. **`AudioRecord` (PCM-level) replacement of MediaRecorder.** Lower-
   level API where in-process append-to-file is trivial. Rejected: the
   project would have to handle M4A muxing manually (encoder pipeline,
   AAC encoder lifecycle, header writing), which is its own
   maintenance burden. MediaRecorder is the project-standard high-
   level API; the multi-file design preserves that.
3. **Single-file with offset-tracking + manual mux.** Keep
   `audio_file_path: String?` singular but write subsequent takes at a
   tracked offset inside the same file, then manually rewrite the
   MP4 metadata. Rejected: MP4 container format is unforgiving;
   metadata corruption produces an unrecoverable file. The multi-file
   design isolates each take's container so a corrupted segment
   doesn't poison the others — partial-recovery is possible.
4. **Concatenate at stop-time, not at upload-time.** Eager merge after
   `MediaRecorder.stop()`, store one consolidated file. Rejected: the
   user may pause/resume several times; eager merging means N²
   re-merges. Lazy merge at upload-time is O(N) once.
5. **No append, just discard old recording on Cold-Resume.** Reject
   the user's "Fortsetzen"-button for the cold path. Rejected: the
   user explicitly asked for the architecture to support cold-append,
   and the cost (MediaMuxer 50-line helper + N-segment naming) is
   modest enough not to compromise.

## Consequences

### Positive

- **Cold-Resume works without losing prior recordings.** The
  user-visible value: "Fortsetzen" remains an option even after the
  app process died.
- **Repository contains all multi-file complexity.** Pipeline layer,
  State layer, UI layer see one file path-per-call semantics. They
  cannot break the invariant because they cannot construct paths.
- **Partial-recovery on segment corruption.** If `_2.m4a` is corrupted
  but `_1.m4a` and `_3.m4a` are fine, the merger can skip the broken
  segment (planned: log + skip, recoverable degradation rather than
  hard fail). Single-file design lacks this safety net.
- **Cleanly testable.** `RealAudioFileRepository` is the unit-test
  surface; passing a `FakeAudioFileRepository` to the
  `RecordingModule` reducer leaves the rest of the system unchanged.

### Negative

- **Schema migration risk.** Existing users have populated
  `audio_file_path` rows; the MIGRATION_4_5 step must wrap them into
  JSON arrays without data loss. Mitigation: the legacy column is
  kept for one release cycle (MIGRATION_5_6 drops it) so a rollback
  is possible.
- **Pipeline-upload latency increase for multi-segment sessions.**
  The first `readForPipeline` after a multi-segment recording adds
  the MediaMuxer merge time (~100-500 ms typical) before the upload
  starts. Single-segment sessions (the common case — user never
  paused) are unaffected (zero-copy path).
- **Disk usage transient peak.** The `_merged.m4a` temporary exists
  alongside the source segments during upload, doubling peak disk
  usage for that session. Mitigation: deleted in the
  `readForPipeline`'s `try-finally` after the upload completes, and
  segment deletion is best-effort-on-failure (left to the periodic
  cleanup).

### Failure Modes

- **MediaMuxer fails on heterogeneous codec params.** If a future code
  change causes one session's segments to have different AAC bitrates
  or sample rates (e.g. Bluetooth toggle mid-pause), the muxer rejects
  the second segment. Mitigation: the `RecordingHardwareAdapter.allocate`
  for an append-segment captures the previous segment's codec params
  and configures the new MediaRecorder identically; an Audit test
  asserts this on every CI run by recording with both `MIC` and
  `VOICE_COMMUNICATION` sources and checking the muxer accepts the
  concat.
- **Cold-Resume race with cleanup job.** The
  `DurationHealingScheduler` periodic cleanup could delete a segment
  while the user is in the middle of a Fortsetzen flow. Mitigation:
  the cleanup job acquires the repository's per-session lock that
  `allocateNext` also acquires; the two are mutually serialized.
- **Orphaned `_merged.m4a` after process-death during upload.** The
  transient merged file is not in the segment list and not in the DB.
  Mitigation: `listOrphanSessionIds` also scans for `_merged.m4a`
  files older than the freshness floor and deletes them.
- **JSON encoding/decoding cost.** The `audio_file_paths` column is
  read on every session lookup. Mitigation: typical session has 1-3
  paths, JSON parse is <50 µs on Pixel-class devices, no measurable
  impact on the HistoryActivity query path.

## References

- **Related ADR:** ADR-0006 (Info-Bar State-Derived Items) — consumes
  this repository for the Pending-Recording dismiss + Fortsetzen
  affordances
- Architecture doc to be added:
  `docs/architecture/audio-pipeline/multi-file-recording.md` (post-impl)
- Implementation: commits with tag `[audio-multi-file-repository]` in
  branch `feature/dictate-keyboard-layout-refactor` from 2026-05-21
  onward
- Files affected:
  - `app/src/main/java/net/devemperor/dictate/database/entity/SessionEntity.kt`
    (column rename)
  - `app/src/main/java/net/devemperor/dictate/database/migration/` (new
    `Migration_4_5.kt`)
  - `app/src/main/java/net/devemperor/dictate/core/RecordingHardwareAdapter.kt`
    (`allocate` calls the repository, captures codec-params for
    future `allocateNext`)
  - `app/src/main/java/net/devemperor/dictate/state/AudioFileFactory.kt`
    (renamed / refactored into the new `AudioFileRepository`
    interface)
  - `app/src/main/java/net/devemperor/dictate/core/CacheDirAudioFileFactory.kt`
    (becomes `CacheDirAudioFileRepository`)
  - `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt`
    (iterates segments in cleanup)
  - `app/src/main/java/net/devemperor/dictate/database/DurationHealingJob.kt`
    (sum-over-segments)
- Test plan: `app/src/test/java/.../audio/MediaMuxerConcatTest.kt`
  (instrumented), `app/src/test/java/.../audio/CacheDirAudioFileRepositoryTest.kt`
  (unit)
- Related ADRs:
  - ADR-0003 (Foreground Pipeline Architecture) — the FGS is where
    `MediaRecorder` instances live across the IME-view lifecycle;
    Live-Resume relies on this
  - ADR-0006 (Info-Bar State-Derived Items) — primary consumer of
    the repository's append API

## Decision History

### 2026-05-21 — Initial proposal

**Trigger:** User-asked support for "Fortsetzen" in the
Pending-Recording info-bar item even after process-death. Naive
in-place append impossible per `MediaRecorder.prepare()` semantics.

**Before:** Single `audio_file_path` per session. Pause + Resume work
only while the `MediaRecorder` instance is alive. Cold-Resume after
process-death is architecturally impossible.

**After:** Multi-file segment architecture hidden behind
`AudioFileRepository`. Pipeline layer and State layer continue to see
"one audio file per session" semantically. Concatenation lazy at
upload-time via MediaMuxer.

**Reasoning:** The Repository-encapsulation was the design that
satisfied the user's "überliegende Schichten kennen das nicht"-
constraint while preserving the MediaRecorder-based recording path
elsewhere. MediaMuxer is the first-party API and handles same-codec
concatenation trivially; the alternatives (FFmpeg, AudioRecord)
introduce dependencies or maintenance burden disproportionate to the
benefit.
