package net.devemperor.dictate.history

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.R
import net.devemperor.dictate.database.entity.ProcessingStepEntity
import net.devemperor.dictate.database.entity.TranscriptionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Robolectric bind tests for [PipelineStepAdapter] (spec §3.3 / Chunk C).
 *
 * # Why Robolectric
 * The adapter wires real `View.setOnClickListener` / `visibility` /
 * `maxLines` and the real clipboard, and DiffUtil identity via [ListAdapter].
 * A hand-rolled fake would have to re-implement all of that; Robolectric is the
 * K-4 justified exception (same rationale as [EditBarControllerTest]).
 *
 * # Coverage focus
 *  - **F-107 recycle regression:** a holder that rendered a SOURCE_SESSION row
 *    and is rebound as a TRANSCRIPTION row must NOT stay clickable.
 *  - **R1 per-step copy:** visible iff output text non-empty; puts the FULL
 *    (untruncated) text on the clipboard.
 *  - **R4 expand:** collapsed vs. expanded maxLines follow the expansion state.
 *  - **F-113 play/pause:** the audio-row play button swaps its icon per the
 *    `audioPlaying` flag.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PipelineStepAdapterBindTest {

    private lateinit var ctx: Context
    private lateinit var parent: FrameLayout
    private lateinit var expansion: StepExpansionState
    private lateinit var callback: RecordingCallback

    /** K-1 handwritten recorder — no mocking framework. */
    private class RecordingCallback : PipelineStepAdapter.StepActionCallback {
        val openedSources = mutableListOf<String>()
        val rerunSessions = mutableListOf<String>()
        val selectedTranscriptions = mutableListOf<TranscriptionEntity>()
        override fun onPlayAudio(audioFilePath: String) {}
        override fun onRegenerate(step: ProcessingStepEntity, chainIndex: Int) {}
        override fun onOtherPrompt(step: ProcessingStepEntity, chainIndex: Int) {}
        override fun onPostProcess(step: ProcessingStepEntity) {}
        override fun onVersionSelected(chainIndex: Int, selectedVersion: ProcessingStepEntity) {}
        override fun onRerunTranscription(sessionId: String) { rerunSessions += sessionId }
        override fun onTranscriptionVersionSelected(selectedVersion: TranscriptionEntity) {
            selectedTranscriptions += selectedVersion
        }
        override fun onOpenSourceSession(sessionId: String) { openedSources += sessionId }
        override fun onDirectReprocess(sessionId: String) {}
        override fun onReprocessWithEdit(sessionId: String) {}
        override fun onDeleteAudio(sessionId: String) {}
    }

    @Before
    fun setUp() {
        ctx = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(),
            R.style.Theme_Dictate
        )
        parent = FrameLayout(ctx)
        expansion = StepExpansionState()
        callback = RecordingCallback()
    }

    private fun newAdapter() = PipelineStepAdapter(expansion, callback)

    private fun bindAt(
        adapter: PipelineStepAdapter,
        holder: PipelineStepAdapter.ViewHolder,
        position: Int,
    ) {
        // ViewHolder position is read by bindViewHolder from the holder; set it
        // via the public bindViewHolder path which routes to onBindViewHolder.
        adapter.bindViewHolder(holder, position)
    }

    private fun newHolder(adapter: PipelineStepAdapter): PipelineStepAdapter.ViewHolder =
        adapter.onCreateViewHolder(parent, 0)

    /** Submit synchronously: await the differ commit, then drain the main looper. */
    private fun PipelineStepAdapter.submitBlocking(list: List<PipelineStepAdapter.PipelineStep>) {
        val latch = CountDownLatch(1)
        submitList(list) { latch.countDown() }
        // The differ runs on a background thread; give it a bounded wait, then
        // flush the main looper so the list-update callback lands.
        latch.await(5, TimeUnit.SECONDS)
        shadowOf(ctx.mainLooper).idle()
    }

    // ── F-107 recycle regression ─────────────────────────────────────────

    @Test
    fun `recycled SOURCE_SESSION holder rebound as TRANSCRIPTION is not clickable`() {
        val adapter = newAdapter()
        val holder = newHolder(adapter)

        adapter.submitBlocking(
            listOf(
                PipelineStepAdapter.PipelineStep(
                    type = PipelineStepAdapter.PipelineStep.Type.SOURCE_SESSION,
                    title = "Source",
                    sourceSessionId = "parent-123",
                )
            )
        )
        bindAt(adapter, holder, 0)
        assertTrue("source-session row must be clickable", holder.itemView.isClickable)

        // Recycle the SAME holder for a transcription row.
        adapter.submitBlocking(
            listOf(
                PipelineStepAdapter.PipelineStep(
                    type = PipelineStepAdapter.PipelineStep.Type.TRANSCRIPTION,
                    title = "Transcription v1",
                    outputText = "hello",
                )
            )
        )
        bindAt(adapter, holder, 0)

        assertFalse(
            "rebound non-source row must clear the stale click listener (F-107)",
            holder.itemView.isClickable
        )
        // And tapping it must not navigate.
        holder.itemView.performClick()
        assertTrue(callback.openedSources.isEmpty())
    }

    // ── R1 per-step copy ─────────────────────────────────────────────────

    @Test
    fun `copy button hidden when output empty, visible and copies full text otherwise`() {
        val adapter = newAdapter()

        // Empty output → hidden.
        val emptyHolder = newHolder(adapter)
        adapter.submitBlocking(
            listOf(
                PipelineStepAdapter.PipelineStep(
                    type = PipelineStepAdapter.PipelineStep.Type.AUDIO,
                    title = "Audio",
                )
            )
        )
        bindAt(adapter, emptyHolder, 0)
        assertEquals(View.GONE, emptyHolder.copyBtn.visibility)

        // Non-empty output → visible; copies the FULL untruncated text.
        val fullText = (1..40).joinToString("\n") { "line $it" }
        val holder = newHolder(adapter)
        adapter.submitBlocking(
            listOf(
                PipelineStepAdapter.PipelineStep(
                    type = PipelineStepAdapter.PipelineStep.Type.TRANSCRIPTION,
                    title = "Transcription",
                    outputText = fullText,
                )
            )
        )
        bindAt(adapter, holder, 0)
        assertEquals(View.VISIBLE, holder.copyBtn.visibility)

        holder.copyBtn.performClick()
        val clipboard =
            ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals(fullText, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    // ── R4 expand/collapse ───────────────────────────────────────────────

    @Test
    fun `collapsed vs expanded maxLines follows the expansion state`() {
        val adapter = newAdapter()
        val holder = newHolder(adapter)
        val step = PipelineStepAdapter.PipelineStep(
            type = PipelineStepAdapter.PipelineStep.Type.TRANSCRIPTION,
            title = "Transcription",
            outputText = "some long output",
        )

        adapter.submitBlocking(listOf(step))
        bindAt(adapter, holder, 0)
        assertEquals(
            PipelineStepAdapter.COLLAPSED_MAX_LINES,
            holder.outputTv.maxLines
        )

        // Expand this step's key, rebind → unlimited lines.
        expansion.toggle(step.stepKey)
        bindAt(adapter, holder, 0)
        assertEquals(Int.MAX_VALUE, holder.outputTv.maxLines)
    }

    // ── F-113 play/pause icon swap ───────────────────────────────────────

    @Test
    fun `audio play button reflects the audioPlaying flag`() {
        val adapter = newAdapter()

        val idleHolder = newHolder(adapter)
        adapter.submitBlocking(
            listOf(
                PipelineStepAdapter.PipelineStep(
                    type = PipelineStepAdapter.PipelineStep.Type.AUDIO,
                    title = "Audio",
                    audioFilePath = "/tmp/seg0.m4a",
                    audioPlaying = false,
                )
            )
        )
        bindAt(adapter, idleHolder, 0)
        assertEquals(View.VISIBLE, idleHolder.playBtn.visibility)
        assertEquals(
            ctx.getString(R.string.dictate_history_play),
            idleHolder.playBtn.contentDescription
        )

        val playingHolder = newHolder(adapter)
        adapter.submitBlocking(
            listOf(
                PipelineStepAdapter.PipelineStep(
                    type = PipelineStepAdapter.PipelineStep.Type.AUDIO,
                    title = "Audio",
                    audioFilePath = "/tmp/seg0.m4a",
                    audioPlaying = true,
                )
            )
        )
        bindAt(adapter, playingHolder, 0)
        assertEquals(
            ctx.getString(R.string.dictate_history_pause),
            playingHolder.playBtn.contentDescription
        )
    }

    // ── R6 transcription re-run button ───────────────────────────────────

    @Test
    fun `re-run button shows and dispatches only on a TRANSCRIPTION card with showRerun`() {
        val adapter = newAdapter()

        // TRANSCRIPTION + showRerun → visible; click dispatches the session id.
        val holder = newHolder(adapter)
        adapter.submitBlocking(
            listOf(
                PipelineStepAdapter.PipelineStep(
                    type = PipelineStepAdapter.PipelineStep.Type.TRANSCRIPTION,
                    title = "Transcription v1",
                    outputText = "hello",
                    sessionId = "s-1",
                    showRerun = true,
                )
            )
        )
        bindAt(adapter, holder, 0)
        assertEquals(View.VISIBLE, holder.rerunBtn.visibility)
        holder.rerunBtn.performClick()
        assertEquals(listOf("s-1"), callback.rerunSessions)

        // showRerun=false (e.g. no audio or a job is active) → hidden.
        val gatedHolder = newHolder(adapter)
        adapter.submitBlocking(
            listOf(
                PipelineStepAdapter.PipelineStep(
                    type = PipelineStepAdapter.PipelineStep.Type.TRANSCRIPTION,
                    title = "Transcription v1",
                    outputText = "hello",
                    sessionId = "s-1",
                    showRerun = false,
                )
            )
        )
        bindAt(adapter, gatedHolder, 0)
        assertEquals(View.GONE, gatedHolder.rerunBtn.visibility)
    }

    // ── R6 transcription version chips + D3 staleness ────────────────────

    @Test
    fun `transcription version chips render for multiple versions and select via callback`() {
        val adapter = newAdapter()
        val holder = newHolder(adapter)

        val v1 = transcription("t1", version = 1, isCurrent = false)
        val v2 = transcription("t2", version = 2, isCurrent = true)
        adapter.submitBlocking(
            listOf(
                PipelineStepAdapter.PipelineStep(
                    type = PipelineStepAdapter.PipelineStep.Type.TRANSCRIPTION,
                    title = "Transcription v2",
                    outputText = "hello",
                    sessionId = "s-1",
                    transcriptionVersions = listOf(v1, v2),
                )
            )
        )
        bindAt(adapter, holder, 0)

        assertEquals(View.VISIBLE, holder.versionChipGroup.visibility)
        assertEquals(2, holder.versionChipGroup.childCount)

        // Selecting the non-current v1 chip routes to the transcription
        // callback (not the processing-step one).
        val firstChip = holder.versionChipGroup.getChildAt(0)
                as com.google.android.material.chip.Chip
        firstChip.isChecked = true
        assertEquals(listOf(v1), callback.selectedTranscriptions)
    }

    @Test
    fun `single transcription version hides chips but staleness still warns`() {
        val adapter = newAdapter()
        val holder = newHolder(adapter)

        adapter.submitBlocking(
            listOf(
                PipelineStepAdapter.PipelineStep(
                    type = PipelineStepAdapter.PipelineStep.Type.TRANSCRIPTION,
                    title = "Transcription v1",
                    outputText = "hello",
                    sessionId = "s-1",
                    transcriptionVersions = listOf(transcription("t1", 1, true)),
                    transcriptionStale = true,
                )
            )
        )
        bindAt(adapter, holder, 0)

        assertEquals(View.GONE, holder.versionChipGroup.visibility)
        // D3: staleness warning is independent of chip count.
        assertEquals(View.VISIBLE, holder.versionWarningTv.visibility)
        assertEquals(
            ctx.getString(R.string.dictate_history_transcription_stale),
            holder.versionWarningTv.text.toString()
        )
    }

    private fun transcription(id: String, version: Int, isCurrent: Boolean) =
        TranscriptionEntity(
            id = id,
            sessionId = "s-1",
            version = version,
            isCurrent = isCurrent,
            text = "text-$version",
            modelUsed = "whisper-1",
            provider = "OPENAI",
            promptTokens = 0,
            completionTokens = 0,
            durationMs = 1000,
            createdAt = 1_700_000_000_000L,
        )
}
