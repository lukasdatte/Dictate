package net.devemperor.dictate.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import net.devemperor.dictate.R
import net.devemperor.dictate.database.entity.ProcessingStepEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter for the history-detail pipeline-step list — the flagship of the
 * history UI overhaul (spec §3.3).
 *
 * # Responsibility
 * Renders one [PipelineStep] per row (audio / transcription / processing /
 * input / final-output / source-session / session-error) and wires each row's
 * action buttons (play-pause, copy, regenerate, other-prompt, post-process,
 * reprocess trio) plus version chips and tap-to-expand back to the Activity via
 * [StepActionCallback]. All AI/DB work stays in the Activity + job layer; this
 * adapter is pure presentation.
 *
 * # Invariants
 * - **Stable identity ([PipelineStep.StepKey]).** [DiffUtil] keys rows on
 *   `stepKey`, so the Activity replaces `notifyDataSetChanged()` on every
 *   registry tick with [submitList]: only changed rows rebind, and UI state
 *   keyed by `stepKey` (expansion) survives the wholesale rebuild.
 * - **Expansion outside the list.** Collapsed vs. expanded is owned by
 *   [StepExpansionState] (keyed by `stepKey`), never by the throwaway
 *   [PipelineStep] objects — so it survives reloads and rotation (R4/R5, D2).
 * - **Symmetric binding (F-107).** Every listener / visibility set in
 *   [onBindViewHolder] has an else branch; in particular the item-view click
 *   listener is cleared and `isClickable=false` for every non-SOURCE_SESSION
 *   row, so a recycled source-session holder can never misnavigate.
 *
 * @see StepExpansionState
 * @see docs/research/2026-07-02 - history-ui-overhaul.md §3.3
 */
class PipelineStepAdapter(
    private val expansionState: StepExpansionState,
    private val callback: StepActionCallback,
) : ListAdapter<PipelineStepAdapter.PipelineStep, PipelineStepAdapter.ViewHolder>(DIFF) {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    /**
     * A displayable pipeline step. Immutable data class (the Builder is gone —
     * Java call sites construct it directly via the constructor with named
     * defaults mirrored by overloads generated for the JVM).
     *
     * [stepKey] is the stable identity used for DiffUtil and expansion state
     * (spec §3.3): `"$type:$chainIndex"` for chain steps and fixed literals for
     * the singleton rows.
     */
    data class PipelineStep @JvmOverloads constructor(
        val type: Type,
        val icon: String = "",
        val title: String = "",
        val outputText: String? = null,
        val errorText: String? = null,
        val metaText: String? = null,
        val audioFilePath: String? = null,
        // F-113/F-115: reflects HistoryAudioPlayer state so the play button
        // swaps play <-> pause. Set on the AUDIO step; folded into the DiffUtil
        // contents so a state flip rebinds the audio row.
        val audioPlaying: Boolean = false,
        val stepEntity: ProcessingStepEntity? = null,
        val versions: List<ProcessingStepEntity>? = null,
        val chainIndex: Int = 0,
        val showRegenerate: Boolean = false,
        val showOtherPrompt: Boolean = false,
        val showPostProcess: Boolean = false,
        val sessionId: String? = null,
        val showDirectReprocess: Boolean = false,
        val showReprocessWithEdit: Boolean = false,
        val showDeleteAudio: Boolean = false,
        val sourceSessionId: String? = null,
        // F-053: the session-error surface renders errorText with colorError and
        // suppresses all action affordances (display-only card).
        val isSessionError: Boolean = false,
    ) {
        enum class Type { AUDIO, TRANSCRIPTION, PROCESSING, INPUT, FINAL_OUTPUT, SOURCE_SESSION, SESSION_ERROR }

        /** Stable identity across full list rebuilds (spec §3.3). */
        val stepKey: String
            get() = when (type) {
                Type.AUDIO -> "audio"
                Type.TRANSCRIPTION -> "transcription"
                Type.FINAL_OUTPUT -> "final"
                Type.SESSION_ERROR -> "session-error"
                Type.SOURCE_SESSION -> "source:${sourceSessionId ?: ""}"
                Type.INPUT -> "input"
                Type.PROCESSING -> "processing:$chainIndex"
            }

        /**
         * Fluent builder retained purely for the Java call sites in
         * [HistoryDetailActivity] — Kotlin cannot use named arguments from Java,
         * and the twenty-odd optional fields make a positional constructor call
         * unreadable there. Kotlin callers construct the data class directly.
         */
        class Builder(private val type: Type) {
            private var icon: String = ""
            private var title: String = ""
            private var outputText: String? = null
            private var errorText: String? = null
            private var metaText: String? = null
            private var audioFilePath: String? = null
            private var audioPlaying: Boolean = false
            private var stepEntity: ProcessingStepEntity? = null
            private var versions: List<ProcessingStepEntity>? = null
            private var chainIndex: Int = 0
            private var showRegenerate: Boolean = false
            private var showOtherPrompt: Boolean = false
            private var showPostProcess: Boolean = false
            private var sessionId: String? = null
            private var showDirectReprocess: Boolean = false
            private var showReprocessWithEdit: Boolean = false
            private var showDeleteAudio: Boolean = false
            private var sourceSessionId: String? = null
            private var isSessionError: Boolean = false

            fun icon(v: String) = apply { icon = v }
            fun title(v: String) = apply { title = v }
            fun outputText(v: String?) = apply { outputText = v }
            fun errorText(v: String?) = apply { errorText = v }
            fun metaText(v: String?) = apply { metaText = v }
            fun audioFilePath(v: String?) = apply { audioFilePath = v }
            fun audioPlaying(v: Boolean) = apply { audioPlaying = v }
            fun stepEntity(v: ProcessingStepEntity?) = apply { stepEntity = v }
            fun versions(v: List<ProcessingStepEntity>?) = apply { versions = v }
            fun chainIndex(v: Int) = apply { chainIndex = v }
            fun showRegenerate(v: Boolean) = apply { showRegenerate = v }
            fun showOtherPrompt(v: Boolean) = apply { showOtherPrompt = v }
            fun showPostProcess(v: Boolean) = apply { showPostProcess = v }
            fun sessionId(v: String?) = apply { sessionId = v }
            fun showDirectReprocess(v: Boolean) = apply { showDirectReprocess = v }
            fun showReprocessWithEdit(v: Boolean) = apply { showReprocessWithEdit = v }
            fun showDeleteAudio(v: Boolean) = apply { showDeleteAudio = v }
            fun sourceSessionId(v: String?) = apply { sourceSessionId = v }
            fun isSessionError(v: Boolean) = apply { isSessionError = v }

            fun build(): PipelineStep = PipelineStep(
                type, icon, title, outputText, errorText, metaText, audioFilePath,
                audioPlaying, stepEntity, versions, chainIndex, showRegenerate,
                showOtherPrompt, showPostProcess, sessionId, showDirectReprocess,
                showReprocessWithEdit, showDeleteAudio, sourceSessionId, isSessionError,
            )
        }
    }

    interface StepActionCallback {
        fun onPlayAudio(audioFilePath: String)
        fun onRegenerate(step: ProcessingStepEntity, chainIndex: Int)
        fun onOtherPrompt(step: ProcessingStepEntity, chainIndex: Int)
        fun onPostProcess(step: ProcessingStepEntity)
        fun onVersionSelected(chainIndex: Int, selectedVersion: ProcessingStepEntity)
        fun onOpenSourceSession(sessionId: String)
        fun onDirectReprocess(sessionId: String)
        fun onReprocessWithEdit(sessionId: String)
        fun onDeleteAudio(sessionId: String)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pipeline_step, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val step = getItem(position)
        val context = holder.itemView.context

        // Connector visibility — hide for the first item.
        holder.connector.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE

        holder.iconTv.text = step.icon
        holder.titleTv.text = step.title

        bindOutput(holder, step)
        bindError(holder, step)
        bindMeta(holder, step)
        bindPlay(holder, step, context)
        bindReprocessActions(holder, step)
        bindCopy(holder, step, context)
        bindStepButtons(holder, step)
        bindSourceSession(holder, step)
        bindVersionChips(holder, step, context)
    }

    // ── Output + expand affordance (R1/R4/R5) ────────────────────────────

    private fun bindOutput(holder: ViewHolder, step: PipelineStep) {
        val text = step.outputText
        if (text.isNullOrEmpty()) {
            holder.outputTv.visibility = View.GONE
            holder.expandTv.visibility = View.GONE
            holder.card.setOnClickListener(null)
            holder.card.isClickable = false
            holder.outputTv.setOnClickListener(null)
            return
        }

        holder.outputTv.visibility = View.VISIBLE
        holder.outputTv.text = text

        val expanded = expansionState.isExpanded(step.stepKey)
        applyExpansion(holder, expanded)

        // Toggle from either the card or the text itself (spec §3.3).
        val toggle = View.OnClickListener {
            val nowExpanded = expansionState.toggle(step.stepKey)
            applyExpansion(holder, nowExpanded)
            refreshAffordance(holder, nowExpanded)
        }
        holder.card.setOnClickListener(toggle)
        holder.card.isClickable = true
        holder.outputTv.setOnClickListener(toggle)

        // Affordance visible ONLY when the collapsed text is actually
        // ellipsized (post-layout check). When expanded it always shows the
        // "collapse" caption.
        refreshAffordance(holder, expanded)
    }

    private fun applyExpansion(holder: ViewHolder, expanded: Boolean) {
        holder.outputTv.maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_MAX_LINES
    }

    /**
     * Shows the expand/collapse caption only when it is meaningful: when
     * expanded it always offers "collapse"; when collapsed it appears only if
     * the text is truly truncated (post-layout ellipsis check on the last
     * visible line — the layout is not final at bind time, so this is deferred
     * to a one-shot pre-draw pass).
     */
    private fun refreshAffordance(holder: ViewHolder, expanded: Boolean) {
        val ctx = holder.itemView.context
        if (expanded) {
            holder.expandTv.visibility = View.VISIBLE
            holder.expandTv.text = ctx.getString(R.string.dictate_history_collapse)
            return
        }
        holder.expandTv.visibility = View.GONE
        val tv = holder.outputTv
        tv.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                tv.viewTreeObserver.removeOnPreDrawListener(this)
                val layout = tv.layout
                val truncated = layout != null &&
                    layout.lineCount > 0 &&
                    layout.getEllipsisCount(layout.lineCount - 1) > 0
                holder.expandTv.visibility = if (truncated) View.VISIBLE else View.GONE
                holder.expandTv.text = ctx.getString(R.string.dictate_history_expand)
                return true
            }
        })
    }

    // ── Error / meta ─────────────────────────────────────────────────────

    private fun bindError(holder: ViewHolder, step: PipelineStep) {
        val error = step.errorText
        if (!error.isNullOrEmpty()) {
            holder.errorTv.visibility = View.VISIBLE
            holder.errorTv.text = error
        } else {
            holder.errorTv.visibility = View.GONE
        }
    }

    private fun bindMeta(holder: ViewHolder, step: PipelineStep) {
        val meta = step.metaText
        if (!meta.isNullOrEmpty()) {
            holder.metaTv.visibility = View.VISIBLE
            holder.metaTv.text = meta
        } else {
            holder.metaTv.visibility = View.GONE
        }
    }

    // ── Play/pause (F-113/F-115) ─────────────────────────────────────────

    private fun bindPlay(holder: ViewHolder, step: PipelineStep, context: Context) {
        if (step.type == PipelineStep.Type.AUDIO && step.audioFilePath != null) {
            holder.playBtn.visibility = View.VISIBLE
            holder.playBtn.setImageResource(
                if (step.audioPlaying) R.drawable.ic_baseline_pause_24
                else android.R.drawable.ic_media_play
            )
            holder.playBtn.contentDescription = context.getString(
                if (step.audioPlaying) R.string.dictate_history_pause
                else R.string.dictate_history_play
            )
            val path = step.audioFilePath
            holder.playBtn.setOnClickListener { callback.onPlayAudio(path) }
        } else {
            holder.playBtn.visibility = View.GONE
            holder.playBtn.setOnClickListener(null)
        }
    }

    // ── Per-step copy (R1) ───────────────────────────────────────────────

    private fun bindCopy(holder: ViewHolder, step: PipelineStep, context: Context) {
        // Copy is a plain clipboard convenience on any card with output text
        // (D10: NO usage/insertion logging — that stays exclusive to the
        // session-level final-output copy). The session-error card carries no
        // output text, so it is naturally excluded.
        val text = step.outputText
        if (!text.isNullOrEmpty() && step.type != PipelineStep.Type.SESSION_ERROR) {
            holder.copyBtn.visibility = View.VISIBLE
            holder.copyBtn.setOnClickListener { copyToClipboard(context, text) }
        } else {
            holder.copyBtn.visibility = View.GONE
            holder.copyBtn.setOnClickListener(null)
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Dictate", text))
        Toast.makeText(context, R.string.dictate_copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    // ── Audio-row reprocess actions ──────────────────────────────────────

    private fun bindReprocessActions(holder: ViewHolder, step: PipelineStep) {
        val sid = step.sessionId
        if (step.showDirectReprocess && sid != null) {
            holder.directReprocessBtn.visibility = View.VISIBLE
            holder.directReprocessBtn.setOnClickListener { callback.onDirectReprocess(sid) }
        } else {
            holder.directReprocessBtn.visibility = View.GONE
            holder.directReprocessBtn.setOnClickListener(null)
        }
        if (step.showReprocessWithEdit && sid != null) {
            holder.reprocessEditBtn.visibility = View.VISIBLE
            holder.reprocessEditBtn.setOnClickListener { callback.onReprocessWithEdit(sid) }
        } else {
            holder.reprocessEditBtn.visibility = View.GONE
            holder.reprocessEditBtn.setOnClickListener(null)
        }
        if (step.showDeleteAudio && sid != null) {
            holder.deleteAudioBtn.visibility = View.VISIBLE
            holder.deleteAudioBtn.setOnClickListener { callback.onDeleteAudio(sid) }
        } else {
            holder.deleteAudioBtn.visibility = View.GONE
            holder.deleteAudioBtn.setOnClickListener(null)
        }
    }

    // ── Processing-step buttons ──────────────────────────────────────────

    private fun bindStepButtons(holder: ViewHolder, step: PipelineStep) {
        val entity = step.stepEntity
        if (step.showRegenerate && entity != null) {
            holder.regenerateBtn.visibility = View.VISIBLE
            holder.regenerateBtn.setOnClickListener { callback.onRegenerate(entity, step.chainIndex) }
        } else {
            holder.regenerateBtn.visibility = View.GONE
            holder.regenerateBtn.setOnClickListener(null)
        }
        if (step.showOtherPrompt && entity != null) {
            holder.otherPromptBtn.visibility = View.VISIBLE
            holder.otherPromptBtn.setOnClickListener { callback.onOtherPrompt(entity, step.chainIndex) }
        } else {
            holder.otherPromptBtn.visibility = View.GONE
            holder.otherPromptBtn.setOnClickListener(null)
        }
        if (step.showPostProcess && entity != null) {
            holder.postProcessBtn.visibility = View.VISIBLE
            holder.postProcessBtn.setOnClickListener { callback.onPostProcess(entity) }
        } else {
            holder.postProcessBtn.visibility = View.GONE
            holder.postProcessBtn.setOnClickListener(null)
        }
    }

    // ── Source-session navigation (F-107 symmetric branch) ───────────────

    private fun bindSourceSession(holder: ViewHolder, step: PipelineStep) {
        if (step.type == PipelineStep.Type.SOURCE_SESSION && step.sourceSessionId != null) {
            val target = step.sourceSessionId
            holder.itemView.setOnClickListener { callback.onOpenSourceSession(target) }
            holder.itemView.isClickable = true
        } else {
            // F-107: clear the recycled listener so a reused SOURCE_SESSION
            // holder cannot misnavigate as a normal step.
            holder.itemView.setOnClickListener(null)
            holder.itemView.isClickable = false
        }
    }

    // ── Version chips ────────────────────────────────────────────────────

    private fun bindVersionChips(holder: ViewHolder, step: PipelineStep, context: Context) {
        // Clear before rebuilding to avoid onClick/checkable conflicts.
        holder.versionChipGroup.setOnCheckedStateChangeListener(null)
        val versions = step.versions
        if (versions != null && versions.size > 1) {
            holder.versionChipGroup.visibility = View.VISIBLE
            holder.versionChipGroup.removeAllViews()

            for (version in versions) {
                val chip = Chip(holder.versionChipGroup.context)
                chip.id = View.generateViewId()
                val chipText = context.getString(R.string.dictate_history_version, version.version)
                val timeStr = timeFormat.format(Date(version.createdAt))
                chip.text = "$chipText ($timeStr)"
                chip.isCheckable = true
                chip.isChecked = version.isCurrent
                chip.tag = version
                holder.versionChipGroup.addView(chip)
            }

            holder.versionChipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
                if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
                val selected = group.findViewById<Chip>(checkedIds[0])
                val selectedVersion = selected?.tag as? ProcessingStepEntity
                if (selectedVersion != null && !selectedVersion.isCurrent) {
                    callback.onVersionSelected(step.chainIndex, selectedVersion)
                }
            }

            val currentMatchesLatest = versions.last().isCurrent
            holder.versionWarningTv.visibility = if (currentMatchesLatest) View.GONE else View.VISIBLE
        } else {
            holder.versionChipGroup.visibility = View.GONE
            holder.versionWarningTv.visibility = View.GONE
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: View = itemView.findViewById(R.id.item_pipeline_card)
        val connector: View = itemView.findViewById(R.id.item_pipeline_connector)
        val iconTv: TextView = itemView.findViewById(R.id.item_pipeline_icon_tv)
        val titleTv: TextView = itemView.findViewById(R.id.item_pipeline_title_tv)
        val playBtn: ImageButton = itemView.findViewById(R.id.item_pipeline_play_btn)
        val copyBtn: ImageButton = itemView.findViewById(R.id.item_pipeline_copy_btn)
        val regenerateBtn: ImageButton = itemView.findViewById(R.id.item_pipeline_regenerate_btn)
        val otherPromptBtn: ImageButton = itemView.findViewById(R.id.item_pipeline_other_prompt_btn)
        val postProcessBtn: ImageButton = itemView.findViewById(R.id.item_pipeline_post_process_btn)
        val directReprocessBtn: ImageButton = itemView.findViewById(R.id.item_pipeline_direct_reprocess_btn)
        val reprocessEditBtn: ImageButton = itemView.findViewById(R.id.item_pipeline_reprocess_edit_btn)
        val deleteAudioBtn: ImageButton = itemView.findViewById(R.id.item_pipeline_delete_audio_btn)
        val outputTv: TextView = itemView.findViewById(R.id.item_pipeline_output_tv)
        val expandTv: TextView = itemView.findViewById(R.id.item_pipeline_expand_tv)
        val errorTv: TextView = itemView.findViewById(R.id.item_pipeline_error_tv)
        val metaTv: TextView = itemView.findViewById(R.id.item_pipeline_meta_tv)
        val versionChipGroup: ChipGroup = itemView.findViewById(R.id.item_pipeline_version_chip_group)
        val versionWarningTv: TextView = itemView.findViewById(R.id.item_pipeline_version_warning_tv)
    }

    companion object {
        /** Collapsed preview line count (R4). */
        const val COLLAPSED_MAX_LINES = 5

        private val DIFF = object : DiffUtil.ItemCallback<PipelineStep>() {
            override fun areItemsTheSame(oldItem: PipelineStep, newItem: PipelineStep): Boolean =
                oldItem.stepKey == newItem.stepKey

            override fun areContentsTheSame(oldItem: PipelineStep, newItem: PipelineStep): Boolean =
                oldItem == newItem
        }
    }
}
