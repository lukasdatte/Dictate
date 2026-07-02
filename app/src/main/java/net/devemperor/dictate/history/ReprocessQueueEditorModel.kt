package net.devemperor.dictate.history

import net.devemperor.dictate.core.PromptQueueSlot
import net.devemperor.dictate.database.entity.PromptEntity

/**
 * UI-free state model of the reprocess queue editor
 * ([ReprocessQueueEditorBottomSheet]) — ordered list of queue entries with
 * add / remove / reorder operations and the mapping to the
 * [PromptQueueSlot] transport.
 *
 * Deliberately a plain Kotlin class (no fragment/view state): the editor
 * logic is unit-testable and the fragment can snapshot/restore it across
 * recreation via [Snapshot] (primitive lists — Bundle-friendly without
 * Parcelable plumbing).
 */
class ReprocessQueueEditorModel private constructor(
    initialEntries: List<Entry>
) {

    /**
     * One queue row. [text] is the editor-confirmed prompt content
     * (executes even if the saved prompt is deleted later); [entityId]
     * links a saved prompt (null = free-text); [displayName] is the row
     * label (saved-prompt name, or a single-line preview for free-text).
     */
    data class Entry(
        val text: String,
        val entityId: Int?,
        val displayName: String
    )

    private val mutableEntries = initialEntries.toMutableList()

    val entries: List<Entry> get() = mutableEntries

    val size: Int get() = mutableEntries.size

    /**
     * Appends a saved prompt. Rejected when its content is blank or the
     * prompt is already queued (the add-list offers each saved prompt once
     * — original Plan 10.6 "filtered to prompts not yet in the queue").
     */
    fun addSavedPrompt(prompt: PromptEntity): Boolean {
        val text = prompt.prompt?.takeIf { it.isNotBlank() } ?: return false
        if (containsSavedPrompt(prompt.id)) return false
        mutableEntries += Entry(
            text = text,
            entityId = prompt.id,
            displayName = prompt.name?.takeIf { it.isNotBlank() } ?: previewOf(text)
        )
        return true
    }

    /** Appends a free-text prompt; blank input is rejected. */
    fun addFreeText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        mutableEntries += Entry(
            text = trimmed,
            entityId = null,
            displayName = previewOf(trimmed)
        )
        return true
    }

    /** Removes the entry at [index]; out-of-range indices are ignored. */
    fun removeAt(index: Int): Boolean {
        if (index !in mutableEntries.indices) return false
        mutableEntries.removeAt(index)
        return true
    }

    /**
     * Moves the entry at [from] to position [to] (drag-to-reorder).
     * Out-of-range indices are ignored.
     */
    fun move(from: Int, to: Int): Boolean {
        if (from !in mutableEntries.indices || to !in mutableEntries.indices) return false
        if (from == to) return true
        mutableEntries.add(to, mutableEntries.removeAt(from))
        return true
    }

    fun containsSavedPrompt(entityId: Int): Boolean =
        mutableEntries.any { it.entityId == entityId }

    /** The transport queue in editor order — every entry carries content. */
    fun toSlots(): List<PromptQueueSlot> =
        mutableEntries.map { PromptQueueSlot.ofContent(it.text, it.entityId) }

    /** Primitive-list snapshot for Bundle round-trips (rotation safety). */
    fun snapshot(): Snapshot = Snapshot(
        texts = mutableEntries.map { it.text },
        // NO_ENTITY_ID sentinel: Bundle-friendly IntArray has no nulls.
        entityIds = mutableEntries.map { it.entityId ?: NO_ENTITY_ID },
        displayNames = mutableEntries.map { it.displayName }
    )

    data class Snapshot(
        val texts: List<String>,
        val entityIds: List<Int>,
        val displayNames: List<String>
    )

    companion object {
        /**
         * Sentinel for "no saved-prompt entity" in [Snapshot.entityIds].
         * Room's `prompts.id` is a positive autoGenerate key, so MIN_VALUE
         * can never collide.
         */
        const val NO_ENTITY_ID: Int = Int.MIN_VALUE

        fun empty(): ReprocessQueueEditorModel = ReprocessQueueEditorModel(emptyList())

        /**
         * Preloads the session's original queue: each historical entity ID
         * is resolved through [resolvePrompt]; prompts deleted since the
         * original run are dropped (their content is unrecoverable — the
         * session row stores IDs only).
         */
        fun preload(
            historicalEntityIds: List<Int>,
            resolvePrompt: (Int) -> PromptEntity?
        ): ReprocessQueueEditorModel {
            val model = empty()
            historicalEntityIds.forEach { id ->
                resolvePrompt(id)?.let { model.addSavedPrompt(it) }
            }
            return model
        }

        /** Inverse of [snapshot] — restores the editor after recreation. */
        fun fromSnapshot(snapshot: Snapshot): ReprocessQueueEditorModel {
            require(
                snapshot.texts.size == snapshot.entityIds.size &&
                    snapshot.texts.size == snapshot.displayNames.size
            ) { "Snapshot lists diverged in length" }
            return ReprocessQueueEditorModel(
                snapshot.texts.indices.map { i ->
                    Entry(
                        text = snapshot.texts[i],
                        entityId = snapshot.entityIds[i].takeIf { it != NO_ENTITY_ID },
                        displayName = snapshot.displayNames[i]
                    )
                }
            )
        }

        private fun previewOf(text: String): String =
            text.lineSequence().first().let {
                if (it.length <= 40) it else it.take(40) + "…"
            }
    }
}
