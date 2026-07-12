package net.devemperor.dictate.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import net.devemperor.dictate.R
import net.devemperor.dictate.database.entity.SessionEntity

/**
 * Paged adapter for the in-keyboard history panel (ADR-0014).
 *
 * Renders one [SessionEntity] per row: a pending marker (for uninserted
 * completed rows), the date, a text preview, and an "Insert" action. The action
 * row is laid out to hold a second button ("Send to Windows") in a later package
 * — see [Callback].
 *
 * The Insert button is disabled for rows with no insertable text
 * ([SessionEntity.hasInsertableText]); `getFinalOutput` stays authoritative at
 * click time.
 */
class KeyboardHistoryAdapter(
    private val callback: Callback,
) : PagingDataAdapter<SessionEntity, KeyboardHistoryAdapter.ViewHolder>(DIFF_CALLBACK) {

    interface Callback {
        /** "Insert" tapped for [session]; [pending] mirrors [SessionEntity.isPendingInsertion]. */
        fun onInsert(session: SessionEntity, pending: Boolean)

        // RESERVED for the Windows-dispatch package (ADR-0014 follow-up), docking
        // at the GONE `item_kbd_history_send_btn` slot — do NOT implement in Paket 3:
        // fun onSendToWindows(session: SessionEntity)
    }

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_keyboard_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // Placeholders are disabled → getItem is only null during a refresh swap.
        val session = getItem(position) ?: return
        bindRow(holder, session)
    }

    /** Pure bind logic, extracted so it is testable without the Paging machinery. */
    internal fun bindRow(holder: ViewHolder, session: SessionEntity) {
        val pending = session.isPendingInsertion()

        holder.pendingDot.visibility = if (pending) View.VISIBLE else View.GONE
        holder.dateView.text = dateFormat.format(Date(session.createdAt))
        holder.previewView.text = session.finalOutputText ?: session.inputText ?: ""

        val hasText = session.hasInsertableText()
        holder.insertButton.isEnabled = hasText
        holder.insertButton.setOnClickListener(
            if (hasText) View.OnClickListener { callback.onInsert(session, pending) } else null
        )
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val pendingDot: TextView = itemView.findViewById(R.id.item_kbd_history_pending_dot)
        val dateView: TextView = itemView.findViewById(R.id.item_kbd_history_date_tv)
        val previewView: TextView = itemView.findViewById(R.id.item_kbd_history_preview_tv)
        val insertButton: MaterialButton = itemView.findViewById(R.id.item_kbd_history_insert_btn)
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SessionEntity>() {
            override fun areItemsTheSame(oldItem: SessionEntity, newItem: SessionEntity) =
                oldItem.id == newItem.id

            // SessionEntity is a data class; inserted_at / final_output_text
            // changes flip equality → DiffUtil re-binds (e.g. pending → inserted).
            override fun areContentsTheSame(oldItem: SessionEntity, newItem: SessionEntity) =
                oldItem == newItem
        }
    }
}
