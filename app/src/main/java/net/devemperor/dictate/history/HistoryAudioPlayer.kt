package net.devemperor.dictate.history

import android.media.MediaPlayer
import android.util.Log

/**
 * Sequential multi-segment playback for the history detail screen with a
 * play/pause toggle whose state resets on completion (F-113 playback + F-115).
 *
 * Two parts, split so the state logic is unit-testable without the Android
 * MediaPlayer (spec D5 — sequential segments, no muxing on the UI path;
 * D9 — extracted testable Kotlin):
 *  - [SegmentPlaylistPolicy] — the pure `Idle → Playing(i) → Paused(i)` state
 *    machine that decides which segment plays and when playback ends.
 *  - [HistoryAudioPlayer] — the thin [MediaPlayer] host that maps policy
 *    transitions onto `setDataSource/prepare/start/pause/release` and reports
 *    play/pause state via [onStateChanged] so the UI can swap the icon.
 *
 * @see docs/research/2026-07-02 - history-ui-overhaul.md §3.2
 */

/**
 * Pure state machine for sequential segment playback.
 *
 * States (see [State]): `Idle`, `Playing(index)`, `Paused(index)`. Auto-advance
 * moves to the next segment on completion; advancing past the last segment
 * returns to `Idle` (F-115 completion reset).
 *
 * The policy never touches a MediaPlayer — it only decides *which* segment
 * should be sounding and whether it is playing. The host reads the resulting
 * state and drives the actual playback.
 */
class SegmentPlaylistPolicy(private val segmentCount: Int) {

    sealed class State {
        object Idle : State()
        data class Playing(val index: Int) : State()
        data class Paused(val index: Int) : State()
    }

    var state: State = State.Idle
        private set

    val isPlaying: Boolean get() = state is State.Playing

    /** The segment index the host should currently have loaded, or null when idle. */
    val currentIndex: Int?
        get() = when (val s = state) {
            is State.Playing -> s.index
            is State.Paused -> s.index
            State.Idle -> null
        }

    /**
     * Play/pause toggle:
     *  - `Idle` → start segment 0 (`Playing(0)`), or stay `Idle` if there is
     *    nothing to play.
     *  - `Playing(i)` → `Paused(i)`.
     *  - `Paused(i)` → `Playing(i)`.
     */
    fun toggle() {
        state = when (val s = state) {
            State.Idle -> if (segmentCount > 0) State.Playing(0) else State.Idle
            is State.Playing -> State.Paused(s.index)
            is State.Paused -> State.Playing(s.index)
        }
    }

    /**
     * Called when the current segment finishes. Auto-advances to the next
     * segment (`Playing(i+1)`); after the last segment, resets to `Idle`.
     * A no-op when not currently playing.
     */
    fun advance() {
        val current = state
        if (current !is State.Playing) return
        val next = current.index + 1
        state = if (next < segmentCount) State.Playing(next) else State.Idle
    }

    /** Force back to the initial state (used on release / new resolution). */
    fun reset() {
        state = State.Idle
    }
}

/**
 * Thin [MediaPlayer] host mapping [SegmentPlaylistPolicy] transitions onto the
 * framework player. Owns exactly one MediaPlayer at a time; a segment change
 * releases the old instance and prepares a fresh one against the next path.
 *
 * Lifecycle contract: [release] must be called from the Activity's `onPause`
 * (and `onDestroy`) so playback never outlives the visible screen.
 *
 * @param segmentPaths the resolved, existing segment paths (from
 *   [HistoryAudioResolver]); playback plays them in order.
 * @param onStateChanged invoked on the calling (main) thread whenever the
 *   play/pause state flips, so the UI can swap the play/pause icon. Modelled
 *   as a SAM interface so the Java Activity can pass a clean lambda.
 */
class HistoryAudioPlayer(
    private val segmentPaths: List<String>,
    private val onStateChanged: StateListener,
) {

    /** Play/pause state callback (SAM — Java-friendly). */
    fun interface StateListener {
        fun onStateChanged(isPlaying: Boolean)
    }

    private val policy = SegmentPlaylistPolicy(segmentPaths.size)
    private var mediaPlayer: MediaPlayer? = null

    val isPlaying: Boolean get() = policy.isPlaying

    /**
     * Play/pause toggle driven from the play button. Applies the policy
     * transition and reconciles the MediaPlayer to match the new state,
     * then reports the resulting play/pause flag.
     */
    fun toggle() {
        policy.toggle()
        applyState()
    }

    private fun applyState() {
        when (val s = policy.state) {
            SegmentPlaylistPolicy.State.Idle -> stopAndRelease()
            is SegmentPlaylistPolicy.State.Playing -> playSegment(s.index)
            is SegmentPlaylistPolicy.State.Paused -> pauseCurrent()
        }
        onStateChanged.onStateChanged(policy.isPlaying)
    }

    private fun playSegment(index: Int) {
        val path = segmentPaths.getOrNull(index) ?: run {
            stopAndRelease()
            return
        }
        // Resuming the same, already-prepared segment: just start it.
        val existing = mediaPlayer
        if (existing != null && index == preparedIndex) {
            runCatching { existing.start() }
            return
        }
        // New segment: release the old instance and prepare a fresh one.
        releasePlayer()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(path)
            mp.prepare()
            mp.setOnCompletionListener {
                // Auto-advance on the main thread that owns the policy.
                policy.advance()
                applyState()
            }
            mp.start()
            mediaPlayer = mp
            preparedIndex = index
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play segment $index ($path)", e)
            releasePlayer()
            policy.reset()
        }
    }

    private fun pauseCurrent() {
        runCatching { mediaPlayer?.pause() }
    }

    private fun stopAndRelease() {
        releasePlayer()
    }

    private fun releasePlayer() {
        mediaPlayer?.let { mp ->
            runCatching {
                mp.setOnCompletionListener(null)
                mp.release()
            }
        }
        mediaPlayer = null
        preparedIndex = -1
    }

    /** Release the framework player and reset the policy. Idempotent. */
    fun release() {
        releasePlayer()
        policy.reset()
    }

    private var preparedIndex: Int = -1

    companion object {
        private const val TAG = "HistoryAudioPlayer"
    }
}
