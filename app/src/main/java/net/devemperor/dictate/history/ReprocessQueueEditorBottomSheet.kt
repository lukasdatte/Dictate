package net.devemperor.dictate.history

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import net.devemperor.dictate.R
import net.devemperor.dictate.core.PromptQueueSlot
import net.devemperor.dictate.core.SessionManager
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.PromptEntity

/**
 * "Reprocess with edit" queue editor (the Plan 10.6
 * `PromptChooserBottomSheetV2` follow-up, F-110) — a full queue editor for
 * re-running a history session: the session's original prompt queue is
 * pre-loaded and can be reordered (drag handle), pruned (remove button) and
 * extended with saved prompts (tap-to-append, each offered once) or
 * free-text prompts.
 *
 * Confirmation hands the queue to the host activity as content-carrying
 * [PromptQueueSlot]s, so free-text prompts and since-deleted saved prompts
 * survive the trip (research doc "reprocess-queue-editor" §2.1).
 *
 * Lifecycle (spec §2.4): the target session id lives in the fragment
 * arguments and the edited queue in the saved instance state — both survive
 * Activity recreation. The result listener is re-bound in [onAttach]
 * (the host activity implements [OnReprocessQueueConfirmedListener]),
 * mirroring [PromptChooserBottomSheet]'s rotation-safe callback pattern.
 * The V1 sheet stays in place for its other tags (regenerate "Other
 * prompt", post-process).
 */
class ReprocessQueueEditorBottomSheet : BottomSheetDialogFragment() {

    /** Implemented by the host activity — survives configuration changes. */
    interface OnReprocessQueueConfirmedListener {
        fun onReprocessQueueConfirmed(sessionId: String, queue: List<PromptQueueSlot>)
    }

    private var listener: OnReprocessQueueConfirmedListener? = null
    private lateinit var model: ReprocessQueueEditorModel

    private lateinit var queueAdapter: QueueAdapter
    private lateinit var savedPromptsAdapter: PromptChooserAdapter
    private lateinit var emptyHint: TextView
    private lateinit var savedLabel: TextView
    private lateinit var savedRv: RecyclerView

    /** All saved prompts with usable content — source for the add-list. */
    private lateinit var allPrompts: List<PromptEntity>
    private val addablePrompts = mutableListOf<PromptEntity>()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? OnReprocessQueueConfirmedListener
            ?: throw IllegalStateException(
                "${context.javaClass.simpleName} must implement " +
                    "ReprocessQueueEditorBottomSheet.OnReprocessQueueConfirmedListener"
            )
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_reprocess_queue_editor, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sessionId = requireNotNull(requireArguments().getString(ARG_SESSION_ID)) {
            "ReprocessQueueEditorBottomSheet requires a session id — use newInstance()"
        }

        val db = DictateDatabase.getInstance(requireContext())
        allPrompts = db.promptDao().getAll().filter { !it.prompt.isNullOrBlank() }

        model = restoreModel(savedInstanceState)
            ?: ReprocessQueueEditorModel.preload(
                SessionManager(db).getHistoricalQueuedPromptIds(sessionId),
                db.promptDao()::getById
            )

        emptyHint = view.findViewById(R.id.reprocess_queue_empty_tv)
        savedLabel = view.findViewById(R.id.reprocess_queue_saved_label_tv)

        // Queue list — drag-to-reorder via ItemTouchHelper, remove per row.
        val queueRv = view.findViewById<RecyclerView>(R.id.reprocess_queue_rv)
        queueRv.layoutManager = LinearLayoutManager(requireContext())
        queueAdapter = QueueAdapter()
        queueRv.adapter = queueAdapter
        touchHelper.attachToRecyclerView(queueRv)

        // Saved prompts — tap to append; each prompt offered once.
        savedRv = view.findViewById(R.id.reprocess_queue_saved_rv)
        savedRv.layoutManager = LinearLayoutManager(requireContext())
        savedPromptsAdapter = PromptChooserAdapter(addablePrompts) { prompt ->
            if (model.addSavedPrompt(prompt)) {
                queueAdapter.notifyItemInserted(model.size - 1)
                refreshDerivedViews()
            }
        }
        savedRv.adapter = savedPromptsAdapter

        // Free-text prompt entry.
        val freetextEt = view.findViewById<TextInputEditText>(R.id.reprocess_queue_freetext_et)
        view.findViewById<MaterialButton>(R.id.reprocess_queue_add_freetext_btn)
            .setOnClickListener {
                val text = freetextEt.text?.toString().orEmpty()
                if (model.addFreeText(text)) {
                    freetextEt.setText("")
                    queueAdapter.notifyItemInserted(model.size - 1)
                    refreshDerivedViews()
                }
            }

        // Confirm — an empty queue is valid: it re-runs the transcription only.
        view.findViewById<MaterialButton>(R.id.reprocess_queue_run_btn).setOnClickListener {
            listener?.onReprocessQueueConfirmed(sessionId, model.toSlots())
            dismiss()
        }

        refreshDerivedViews()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The view (and thus the model) may not exist yet when the state is
        // saved right after process-restore; nothing to persist then.
        if (!::model.isInitialized) return
        val snapshot = model.snapshot()
        outState.putStringArrayList(STATE_TEXTS, ArrayList(snapshot.texts))
        outState.putIntArray(STATE_ENTITY_IDS, snapshot.entityIds.toIntArray())
        outState.putStringArrayList(STATE_NAMES, ArrayList(snapshot.displayNames))
    }

    private fun restoreModel(savedInstanceState: Bundle?): ReprocessQueueEditorModel? {
        val texts = savedInstanceState?.getStringArrayList(STATE_TEXTS) ?: return null
        val entityIds = savedInstanceState.getIntArray(STATE_ENTITY_IDS) ?: return null
        val names = savedInstanceState.getStringArrayList(STATE_NAMES) ?: return null
        return ReprocessQueueEditorModel.fromSnapshot(
            ReprocessQueueEditorModel.Snapshot(texts, entityIds.toList(), names)
        )
    }

    /** Empty-queue hint + the not-yet-queued filter of the saved-prompt list. */
    @SuppressLint("NotifyDataSetChanged")
    private fun refreshDerivedViews() {
        emptyHint.visibility = if (model.size == 0) View.VISIBLE else View.GONE

        addablePrompts.clear()
        addablePrompts += allPrompts.filter { !model.containsSavedPrompt(it.id) }
        savedPromptsAdapter.notifyDataSetChanged()
        val hasAddable = addablePrompts.isNotEmpty()
        savedLabel.visibility = if (hasAddable) View.VISIBLE else View.GONE
        savedRv.visibility = if (hasAddable) View.VISIBLE else View.GONE
    }

    // ── Queue list plumbing ───────────────────────────────────────────────

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, /* swipeDirs */ 0
    ) {
        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            if (!model.move(from, to)) return false
            queueAdapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        // Reordering starts from the drag handle only (isLongPressDragEnabled
        // stays default-true as a fallback for accessibility).
    })

    private inner class QueueAdapter : RecyclerView.Adapter<QueueViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder =
            QueueViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_reprocess_queue_entry, parent, false)
            )

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
            val entry = model.entries[position]
            holder.nameTv.text = entry.displayName
            holder.previewTv.text = entry.text

            holder.removeBtn.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION && model.removeAt(index)) {
                    notifyItemRemoved(index)
                    refreshDerivedViews()
                }
            }
            holder.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchHelper.startDrag(holder)
                }
                false
            }
        }

        override fun getItemCount(): Int = model.size
    }

    private class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val dragHandle: ImageView = itemView.findViewById(R.id.reprocess_queue_entry_drag_handle)
        val nameTv: TextView = itemView.findViewById(R.id.reprocess_queue_entry_name_tv)
        val previewTv: TextView = itemView.findViewById(R.id.reprocess_queue_entry_preview_tv)
        val removeBtn: ImageButton = itemView.findViewById(R.id.reprocess_queue_entry_remove_btn)
    }

    companion object {
        private const val ARG_SESSION_ID = "reprocess_queue_session_id"
        private const val STATE_TEXTS = "reprocess_queue_texts"
        private const val STATE_ENTITY_IDS = "reprocess_queue_entity_ids"
        private const val STATE_NAMES = "reprocess_queue_names"

        /** @param sessionId the history session whose queue is being edited. */
        @JvmStatic
        fun newInstance(sessionId: String): ReprocessQueueEditorBottomSheet =
            ReprocessQueueEditorBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_SESSION_ID, sessionId) }
            }
    }
}
