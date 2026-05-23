package net.devemperor.dictate.core

import android.util.Log
import net.devemperor.dictate.state.AudioFileFactory
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Cache-dir-backed production [AudioFileFactory] (Spec 1 §4.11.3).
 *
 * **Why a sub-directory under `cacheDir`?** The factory writes to
 * `cacheDir/audio/` rather than the cache root. Two reasons:
 *
 *  1. [cleanupOrphans] scans **only** that sub-directory so it never
 *     touches unrelated cache files (settings exports, share-sheet
 *     temporaries, library debug-dumps).
 *  2. The user-visible "Cache leeren" preference (see
 *     `PreferencesFragment.clearCacheRecursively`) deletes the
 *     sub-directory recursively so the audio cache clears together
 *     with everything else — no special wiring needed (KG-AFF-3).
 *
 * **Naming scheme:** `rec_{wall-clock-ms}_{uuid8}.m4a`
 *
 *  - Wall-clock-ms makes the file ordering inspectable from `ls -la`.
 *  - 8-hex UUID suffix guarantees uniqueness even within the same
 *    millisecond (Multi-Job model R.8 — defensive, though only one
 *    recording is active at a time).
 *  - `.m4a` matches the MediaRecorder MPEG_4 + AAC container produced
 *    by [RecordingHardwareAdapter].
 *
 * **Thread model (Spec 1 §4.11.5.2):**
 *
 *  - [allocate] — Main thread (View-layer pre-dispatch resolver).
 *    O(1); only `mkdirs()` + UUID generation.
 *  - [cleanupOrphans] — `Dispatchers.IO` (service-onCreate, once).
 *    `listFiles` + per-file `delete`; bounded by referenced-paths set
 *    plus the 60 s freshness cut-off (KG-AFF-4) which closes the
 *    allocate → MediaRecorder.prepare race window.
 *
 * **Defensive null-check (KG-AFF-5):** [cacheDirProvider] resolves
 * lazily on first read of [audioCacheDir]. A `null` return triggers a
 * `IllegalArgumentException` with a clear diagnosis — preferred over
 * the diffuse NPE that `File(null, "audio")` would throw.
 *
 * @param cacheDirProvider supplier for the application cache directory.
 *   Inject `{ applicationContext.cacheDir }` in production; tests pass
 *   a temp-folder lambda.
 * @param clock returns the current wall-clock time in milliseconds. The
 *   default is `System::currentTimeMillis`; tests inject a deterministic
 *   clock so freshness assertions are stable.
 *
 * @see net.devemperor.dictate.state.AudioFileFactory
 * @see net.devemperor.dictate.core.DictatePipelineService
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §4.11
 */
class CacheDirAudioFileFactory(
    private val cacheDirProvider: () -> File?,
    private val clock: () -> Long = System::currentTimeMillis,
) : AudioFileFactory {

    /**
     * Lazy reference to `cacheDir/audio/`. Created on first read so the
     * factory can be constructed in tests without an Application
     * context. `requireNotNull` (KG-AFF-5) fails fast with a clear
     * message rather than letting `File(null, …)` blow up later.
     */
    private val audioCacheDir: File by lazy {
        val root = requireNotNull(cacheDirProvider()) {
            "cacheDir is null — Application.onCreate has not run yet"
        }
        File(root, AUDIO_SUBDIR).apply { mkdirs() }
    }

    override fun allocate(): File {
        // Treat "exists but not a directory" as an unrecoverable failure
        // — a regular file at the audio sub-dir location (extremely
        // unusual on a sandboxed app cache, but possible via adb push or
        // a custom test setup) blocks every future allocate() call.
        // Without this explicit guard the check below would short-circuit
        // on `exists() == true` and `File(dir, name)` would later return
        // a path whose parent is a file, with no useful diagnostic.
        if (audioCacheDir.exists() && !audioCacheDir.isDirectory) {
            throw IOException("Audio cache dir path is occupied by a non-directory: $audioCacheDir")
        }
        if (!audioCacheDir.exists() && !audioCacheDir.mkdirs()) {
            throw IOException("Audio cache dir not creatable: $audioCacheDir")
        }
        val name = "$PREFIX${clock()}_${UUID.randomUUID().toString().take(UUID_HEX_LEN)}$EXT"
        return File(audioCacheDir, name)
    }

    override fun cleanupOrphans(referencedPaths: Set<String>) {
        // KG-AFF-4 freshness cut-off: never touch a file modified less
        // than CUTOFF_GRACE_MS ago. This closes the allocate → prepare
        // race — a file that was just allocated but whose DB-row write
        // is still in-flight stays untouched.
        val cutoffMs = clock() - CUTOFF_GRACE_MS
        val files = audioCacheDir.listFiles { f ->
            f.isFile &&
                f.name.startsWith(PREFIX) &&
                f.name.endsWith(EXT) &&
                f.lastModified() < cutoffMs
        } ?: return
        files.forEach { f ->
            if (f.absolutePath !in referencedPaths) {
                runCatching { f.delete() }
                    .onFailure { Log.w(TAG, "orphan cleanup failed: ${f.name}", it) }
            }
        }
    }

    companion object {
        internal const val TAG = "AudioFileFactory"
        internal const val AUDIO_SUBDIR = "audio"
        internal const val PREFIX = "rec_"
        internal const val EXT = ".m4a"
        /** 60-second cut-off — covers boot + first dispatch (KG-AFF-4). */
        internal const val CUTOFF_GRACE_MS = 60_000L
        /** Hex length of the UUID suffix (`UUID.toString().take(N)`). */
        internal const val UUID_HEX_LEN = 8
    }
}
