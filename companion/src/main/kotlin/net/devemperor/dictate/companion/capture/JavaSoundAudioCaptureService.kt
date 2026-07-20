package net.devemperor.dictate.companion.capture

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import net.devemperor.dictate.companion.domain.CompanionSettings
import net.devemperor.dictate.companion.platform.AppPaths
import net.devemperor.dictate.core.AmplitudeProcessor
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * The production [AudioCaptureService] on a javax.sound `TargetDataLine` (desktop-host.md §4).
 *
 * javax.sound has no `MediaRecorder.setNextOutputFile` equivalent (ADR-0007), so the read loop rolls
 * segments itself: it reads `line.read` in a background thread and, every
 * [CompanionSettings.rollingSegmentSeconds], finalizes the current [WavWriter] and opens the next
 * `{take}_{n}.wav` under [AppPaths.recordingsDirectory]. Each read block also feeds a peak amplitude
 * through the shared [AmplitudeProcessor] (moved to `:shared-ai` in Block A per D5.e) onto
 * [amplitudes] — the same log-normalized level the phone's recording UI renders.
 *
 * Cold-resume is intentionally out of scope for v1 (the desktop process is long-lived, no FGS
 * teardown) — the segment list is still persisted on the session so recovery is nachrüstbar (§4.3,
 * spec §15 Gap 3).
 */
class JavaSoundAudioCaptureService(
    private val settings: CompanionSettings,
    private val devices: AudioDeviceCatalog = AudioDeviceCatalog(settings),
    private val recordingsDir: File = AppPaths.recordingsDirectory().toFile(),
) : AudioCaptureService {

    private val _amplitudes = MutableSharedFlow<Float>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val amplitudes: Flow<Float> = _amplitudes.asSharedFlow()

    // `take` is written on the caller thread (start/stop) and its Take reads its own fields from the
    // capture thread — @Volatile publishes the reference safely across those threads.
    @Volatile private var take: Take? = null

    override fun start(device: AudioDeviceRef?) {
        check(take == null) { "capture already running" }
        recordingsDir.mkdirs()
        val takeId = UUID.randomUUID().toString()
        val format = CaptureFormat.audioFormat()
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val mixer = devices.resolveMixer(device ?: devices.selected())
        val line = (mixer?.getLine(info) ?: AudioSystem.getLine(info)) as TargetDataLine
        line.open(format)
        line.start()
        take = Take(takeId, line).also { it.startLoop() }
    }

    override fun pause() {
        take?.pause()
    }

    override fun resume() {
        take?.resume()
    }

    override fun stop(): CaptureResult {
        val current = checkNotNull(take) { "capture not running" }
        take = null
        return current.finish()
    }

    override fun discard() {
        take?.discard()
        take = null
    }

    /** All the mutable state of one in-flight recording, so [JavaSoundAudioCaptureService] stays a shell. */
    private inner class Take(private val takeId: String, private val line: TargetDataLine) {

        private val running = AtomicBoolean(true)
        private val paused = AtomicBoolean(false)
        private val processor = AmplitudeProcessor()
        private val segments = ArrayList<File>()

        @Volatile private var totalDataBytes = 0L
        @Volatile private var writer: WavWriter? = null
        private var segmentIndex = 0
        private var thread: Thread? = null

        fun startLoop() {
            openSegment()
            thread = Thread({ readLoop() }, "audio-capture-$takeId").apply {
                isDaemon = true
                start()
            }
        }

        private fun readLoop() {
            val buffer = ByteArray(CaptureFormat.READ_BUFFER_BYTES)
            val rollBytes = settings.rollingSegmentSeconds.toLong() * CaptureFormat.BYTES_PER_SECOND
            while (running.get()) {
                if (paused.get()) {
                    Thread.sleep(20)
                    continue
                }
                val read = line.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                writer?.write(buffer, read)
                totalDataBytes += read
                _amplitudes.tryEmit(processor.process(PcmAmplitude.peak(buffer, read)))
                if ((writer?.bytesWritten() ?: 0) >= rollBytes) roll()
            }
        }

        fun pause() {
            paused.set(true)
        }

        fun resume() {
            paused.set(false)
        }

        fun finish(): CaptureResult {
            stopLoop()
            line.stop()
            line.close()
            writer?.close()
            val merged = File(recordingsDir, "${takeId}.wav")
            WavConcat.merge(segments, merged)
            return CaptureResult(
                mergedWav = merged,
                segmentPaths = segments.toList(),
                durationSeconds = totalDataBytes / CaptureFormat.BYTES_PER_SECOND,
            )
        }

        fun discard() {
            stopLoop()
            line.stop()
            line.close()
            writer?.close()
            segments.forEach { it.delete() }
        }

        private fun stopLoop() {
            running.set(false)
            thread?.join(1_000)
        }

        private fun roll() {
            writer?.close()
            openSegment()
        }

        private fun openSegment() {
            segmentIndex += 1
            val segment = File(recordingsDir, "${takeId}_${segmentIndex}.wav")
            segments += segment
            writer = WavWriter(segment)
        }
    }
}
