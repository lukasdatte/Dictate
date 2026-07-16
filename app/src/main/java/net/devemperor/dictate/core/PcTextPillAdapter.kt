package net.devemperor.dictate.core

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.database.entity.PromptEntity

/**
 * Lean RecyclerView adapter for the PC-dictation Activity's text-pill row (pc-dictation-activity F7).
 *
 * Deliberately NOT the IME's `PromptsKeyboardAdapter`: that adapter's callback carries the full
 * IME behaviour (reprocess-queue toggles, instant prompt, select-all, add-prompt, standalone AI
 * runs, selection handling) none of which applies to a TEXT-only PC row. A plain [ListAdapter] over
 * the pre-filtered TEXT pills is the more sustainable shape here; a tap types the pill to the PC.
 */
class PcTextPillAdapter(
    private val onPillTapped: (PromptEntity) -> Unit,
) : ListAdapter<PromptEntity, PcTextPillAdapter.PillViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PillViewHolder {
        val button = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pc_text_pill, parent, false) as MaterialButton
        return PillViewHolder(button)
    }

    override fun onBindViewHolder(holder: PillViewHolder, position: Int) {
        val pill = getItem(position)
        // A text pill's `name` is its label; fall back to a trimmed content preview when unnamed.
        holder.button.text = pill.name?.takeIf { it.isNotBlank() }
            ?: pill.prompt?.take(24)?.trim().orEmpty()
        holder.button.setOnClickListener { onPillTapped(pill) }
    }

    class PillViewHolder(val button: MaterialButton) : RecyclerView.ViewHolder(button)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PromptEntity>() {
            override fun areItemsTheSame(oldItem: PromptEntity, newItem: PromptEntity) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: PromptEntity, newItem: PromptEntity) =
                oldItem == newItem
        }
    }
}
