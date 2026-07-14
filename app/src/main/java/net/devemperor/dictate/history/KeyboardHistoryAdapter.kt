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
 * completed rows), the date, a text preview, an "Insert" action, and — once a
 * PC is paired — a "Send to Windows" action (ADR-0019).
 *
 * The reserved second button in the action column is now live: it is VISIBLE
 * only while [windowsTargetPaired] is true (a companion is paired) and GONE
 * otherwise, so an unpaired install sees the exact pre-ADR-0019 layout. Both
 * actions are disabled for rows with no insertable text
 * ([SessionEntity.hasInsertableText]); `getFinalOutput` stays authoritative at
 * click time.
 */
class KeyboardHistoryAdapter(
    private val callback: Callback,
    /**
     * Whether a Windows companion is paired ([net.devemperor.dictate.preferences.WindowsTarget]
     * non-null). When false the per-row "Send to Windows" slot stays GONE — the one gate that
     * keeps the button out of an unpaired install's layout (ADR-0019 / ADR-0014 §6).
     */
    private val windowsTargetPaired: Boolean = false,
) : PagingDataAdapter<SessionEntity, KeyboardHistoryAdapter.ViewHolder>(DIFF_CALLBACK) {

    interface Callback {
        /** "Insert" tapped for [session]; [pending] mirrors [SessionEntity.isPendingInsertion]. */
        fun onInsert(session: SessionEntity, pending: Boolean)

        /**
         * The row body (not an action button) was tapped — open the full-text detail
         * view for [session] (Block B). The buttons keep their own listeners, so a tap
         * on Insert/Send does not open the detail.
         */
        fun onOpenDetail(session: SessionEntity)

        /**
         * ADR-0014's reserved hook, now live (ADR-0019): send [session]'s final output to the
         * paired PC. [pending] mirrors [SessionEntity.isPendingInsertion] — it drives both the
         * acknowledge and the pending-part cleanup in the dispatch coordinator.
         */
        fun onSendToWindows(session: SessionEntity, pending: Boolean)
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

        // Row-body short-press opens the full-text detail (Block B). The action buttons
        // below keep their own listeners, so tapping Insert/Send does not open the detail.
        holder.itemView.setOnClickListener { callback.onOpenDetail(session) }

        val hasText = session.hasInsertableText()
        holder.insertButton.isEnabled = hasText
        holder.insertButton.setOnClickListener(
            if (hasText) View.OnClickListener { callback.onInsert(session, pending) } else null
        )

        // The reserved second action (ADR-0019): only present once a PC is paired, and — like
        // Insert — disabled for text-less rows. GONE keeps the unpaired layout byte-identical.
        holder.sendButton.visibility = if (windowsTargetPaired) View.VISIBLE else View.GONE
        holder.sendButton.isEnabled = hasText
        holder.sendButton.setOnClickListener(
            if (hasText && windowsTargetPaired) {
                View.OnClickListener { callback.onSendToWindows(session, pending) }
            } else {
                null
            }
        )
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val pendingDot: TextView = itemView.findViewById(R.id.item_kbd_history_pending_dot)
        val dateView: TextView = itemView.findViewById(R.id.item_kbd_history_date_tv)
        val previewView: TextView = itemView.findViewById(R.id.item_kbd_history_preview_tv)
        val insertButton: MaterialButton = itemView.findViewById(R.id.item_kbd_history_insert_btn)
        val sendButton: MaterialButton = itemView.findViewById(R.id.item_kbd_history_send_btn)
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
