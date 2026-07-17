package net.devemperor.dictate.rewording

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * Drag-and-drop reordering for the prompts overview. The RecyclerView uses a
 * ConcatAdapter (info header + prompt cards), so all positions passed to the
 * listener are [RecyclerView.ViewHolder.getBindingAdapterPosition] values —
 * i.e. indices into the prompts adapter's own data list, not absolute ones.
 * The header is neither draggable nor a legal drop target.
 */
class PromptReorderCallback(private val listener: Listener) : ItemTouchHelper.Callback() {

    interface Listener {
        /** Swap the items at the given prompt-adapter positions; return true if handled. */
        fun onItemMoved(fromPosition: Int, toPosition: Int): Boolean

        /** Drag gesture ended — persist the current order. */
        fun onDragFinished()
    }

    override fun isLongPressDragEnabled(): Boolean = true

    override fun isItemViewSwipeEnabled(): Boolean = false

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int =
        if (viewHolder is PromptsOverviewAdapter.RecyclerViewHolder) {
            makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
        } else {
            0
        }

    override fun canDropOver(
        recyclerView: RecyclerView,
        current: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = target is PromptsOverviewAdapter.RecyclerViewHolder

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val from = viewHolder.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
        return listener.onItemMoved(from, to)
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // Swipe disabled.
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        listener.onDragFinished()
    }
}
