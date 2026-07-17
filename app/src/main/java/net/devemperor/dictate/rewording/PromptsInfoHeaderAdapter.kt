package net.devemperor.dictate.rewording

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import net.devemperor.dictate.R

/**
 * Single-item adapter rendering the explanatory header above the prompt cards.
 * Combined with [PromptsOverviewAdapter] via ConcatAdapter so the info text
 * scrolls with the list instead of occupying fixed space above it.
 */
class PromptsInfoHeaderAdapter : RecyclerView.Adapter<PromptsInfoHeaderAdapter.HeaderViewHolder>() {

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_prompts_overview_header, parent, false)
        return HeaderViewHolder(view)
    }

    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        // Static content — nothing to bind.
    }

    override fun getItemCount(): Int = 1
}
