package net.devemperor.dictate.core

/**
 * One slot of a prompt queue in transport ([JobRequest] / `PipelineConfig`)
 * — the single queue-slot type of the reprocess transport model
 * (research doc "reprocess-queue-editor" §2.1: `JobRequest` must carry
 * prompt *content*, not only entity IDs, and that decision is made once).
 *
 * Three valid shapes:
 *
 *  1. **ID-only** (`text == null`, `entityId != null`) — legacy keyboard
 *     transport (live prompt queue / historical queue). The pipeline
 *     resolves the prompt's *current* text from the DB at execution time;
 *     a since-deleted prompt is skipped (pre-slot semantics preserved).
 *  2. **Content-carrying saved prompt** (`text != null`, `entityId != null`)
 *     — a saved prompt confirmed in the reprocess queue editor. The carried
 *     text is what executes, so the slot survives the saved prompt being
 *     deleted (or edited) between confirm and execution.
 *  3. **Free-text** (`text != null`, `entityId == null`) — a prompt typed
 *     directly into the queue editor; applied to the current pipeline text.
 *
 * `(text == null, entityId == null)` is unconstructible ([init] guard).
 */
data class PromptQueueSlot(
    /**
     * Prompt content. `null` = ID-only slot — resolve the current text from
     * the DB at execution (skip when the prompt was deleted).
     */
    val text: String? = null,
    /** Saved-prompt entity id. `null` = free-text slot. */
    val entityId: Int? = null
) {
    init {
        require(text != null || entityId != null) {
            "PromptQueueSlot needs prompt text and/or a saved-prompt entity id"
        }
    }

    companion object {
        /** ID-only slot (shape 1) — legacy keyboard / historical-queue transport. */
        @JvmStatic
        fun ofSavedPrompt(entityId: Int) = PromptQueueSlot(text = null, entityId = entityId)

        /** Free-text slot (shape 3). */
        @JvmStatic
        fun ofFreeText(text: String) = PromptQueueSlot(text = text, entityId = null)

        /**
         * Content-carrying slot (shape 2 when [entityId] is non-null,
         * shape 3 otherwise) — what the queue editor emits per entry.
         */
        @JvmStatic
        fun ofContent(text: String, entityId: Int?) =
            PromptQueueSlot(text = text, entityId = entityId)

        /** Bulk conversion for the ID-based call sites (shape 1). */
        @JvmStatic
        fun fromIds(entityIds: List<Int>): List<PromptQueueSlot> =
            entityIds.map { ofSavedPrompt(it) }
    }
}
