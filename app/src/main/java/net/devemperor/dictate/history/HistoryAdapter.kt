package net.devemperor.dictate.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import net.devemperor.dictate.R
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.database.entity.SessionType

/**
 * Paged history-list adapter (F-054 / history-pagination-and-scale
 * §2.1) — Kotlin [PagingDataAdapter] replacing the former Java
 * `RecyclerView.Adapter` + `notifyDataSetChanged()` combination.
 * DiffUtil computes minimal updates on a background thread; pages load
 * incrementally as the user scrolls.
 *
 * The running-badge state is part of [HistoryRow] (sampled at page
 * load), NOT read live from `ActiveJobRegistry` during bind — see
 * [HistoryRow] for the diffing rationale.
 *
 * KG-SST-4 note (Spec 1 §6.1.3): the Java predecessor needed an
 * EnumSwitch lint promotion + `Log.wtf` default branch because Java
 * `switch` is not exhaustive. The Kotlin `when` in [applyStatusBadge]
 * is compiler-enforced exhaustive — a new [SessionStatus] variant now
 * fails compilation instead of silently rendering an empty badge. The
 * orthogonal "DB string unknown to this build" failure mode is still
 * handled by [SessionEntity.statusEnum]'s RECORDED fallback.
 */
class HistoryAdapter(
    private val callback: Callback,
) : PagingDataAdapter<HistoryRow, HistoryAdapter.ViewHolder>(DIFF_CALLBACK) {

    interface Callback {
        fun onItemClicked(session: SessionEntity)
        fun onItemLongClicked(session: SessionEntity)
    }

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history_session, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // Placeholders are disabled (PagingConfig.enablePlaceholders =
        // false), so getItem only returns null in the brief window while
        // a refresh swaps the list — skipping the bind is safe there.
        val row = getItem(position) ?: return
        val session = row.session

        // Parse type once for icon + subtitle (unknown DB value → null).
        val type = runCatching { SessionType.valueOf(session.type) }.getOrNull()

        holder.typeIconTv.text = when (type) {
            SessionType.REWORDING -> "✏️"       // pencil
            SessionType.POST_PROCESSING -> "🔄" // arrows
            SessionType.RECORDING -> "🎤"       // mic
            null -> "❓"                              // question mark
        }

        holder.dateTv.text = dateFormat.format(Date(session.createdAt))

        val context = holder.itemView.context
        val subtitle = when (type) {
            SessionType.RECORDING -> {
                val dur = session.audioDurationSeconds
                context.getString(R.string.dictate_history_duration, dur / 60, dur % 60)
            }
            SessionType.REWORDING -> context.getString(R.string.dictate_history_rewording)
            SessionType.POST_PROCESSING ->
                context.getString(R.string.dictate_history_filter_post_processing)
            null -> ""
        }
        holder.subtitleTv.text = subtitle
        holder.subtitleTv.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE

        val preview = session.finalOutputText?.takeIf { it.isNotEmpty() } ?: session.inputText
        holder.previewTv.text = preview.orEmpty()
        holder.previewTv.visibility = if (preview.isNullOrEmpty()) View.GONE else View.VISIBLE

        applyStatusBadge(holder, row)

        holder.itemView.setOnClickListener { callback.onItemClicked(session) }
        holder.itemView.setOnLongClickListener {
            callback.onItemLongClicked(session)
            true
        }
    }

    private fun applyStatusBadge(holder: ViewHolder, row: HistoryRow) {
        // Runtime overlay takes precedence over persisted status (Phase 10.1).
        if (row.isRunning) {
            holder.showBadge(R.drawable.ic_baseline_sync_24, R.string.dictate_status_running)
            return
        }

        when (row.session.statusEnum) {
            SessionStatus.COMPLETED -> holder.hideBadge()
            // M4 (Spec 1 §6.1.3): RECORDING/TRANSCRIBING are defensive
            // UI for the OOM-death window — PipelineRecovery promotes
            // them BEFORE this list loads; the badge only surfaces if
            // recovery has not run yet.
            SessionStatus.RECORDING ->
                holder.showBadge(R.drawable.ic_baseline_sync_24, R.string.dictate_status_recording)
            SessionStatus.TRANSCRIBING ->
                holder.showBadge(R.drawable.ic_baseline_sync_24, R.string.dictate_status_transcribing)
            // RECORDING_INTERRUPTED is "an unfinished recording the user
            // can continue" — the same user-facing situation as RECORDED
            // (see SessionDao.findLatestUnfinishedRecording), so it
            // shares the pending badge. (The Java predecessor let it
            // fall through to Log.wtf + no badge — a gap, not a design.)
            SessionStatus.RECORDED, SessionStatus.RECORDING_INTERRUPTED ->
                holder.showBadge(R.drawable.ic_baseline_pending_24, R.string.dictate_status_recorded)
            SessionStatus.FAILED ->
                holder.showBadge(R.drawable.ic_baseline_error_outline_24, R.string.dictate_status_failed)
            SessionStatus.CANCELLED ->
                holder.showBadge(R.drawable.ic_baseline_cancel_24, R.string.dictate_status_cancelled)
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val typeIconTv: TextView = itemView.findViewById(R.id.item_history_type_icon_tv)
        val dateTv: TextView = itemView.findViewById(R.id.item_history_date_tv)
        val subtitleTv: TextView = itemView.findViewById(R.id.item_history_subtitle_tv)
        val previewTv: TextView = itemView.findViewById(R.id.item_history_preview_tv)
        val statusIcon: ImageView = itemView.findViewById(R.id.item_history_status_icon)
        val statusTv: TextView = itemView.findViewById(R.id.item_history_status_tv)

        fun showBadge(iconRes: Int, textRes: Int) {
            statusIcon.visibility = View.VISIBLE
            statusIcon.setImageResource(iconRes)
            statusTv.visibility = View.VISIBLE
            statusTv.setText(textRes)
        }

        fun hideBadge() {
            statusIcon.visibility = View.GONE
            statusTv.visibility = View.GONE
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<HistoryRow>() {
            override fun areItemsTheSame(oldItem: HistoryRow, newItem: HistoryRow): Boolean =
                oldItem.session.id == newItem.session.id

            // SessionEntity + HistoryRow are data classes — structural
            // equality covers every rendered field incl. the running flag.
            override fun areContentsTheSame(oldItem: HistoryRow, newItem: HistoryRow): Boolean =
                oldItem == newItem
        }
    }
}
