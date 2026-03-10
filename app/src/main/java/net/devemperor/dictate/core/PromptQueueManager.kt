package net.devemperor.dictate.core

import android.content.SharedPreferences

/**
 * Manages the prompt queue for rewording operations.
 *
 * Responsibilities:
 * - Queued prompt management (add, remove, clear)
 * - Auto-apply logic (prompts with autoApply=true)
 * - Prompt ordering (position-based)
 *
 * Does NOT handle UI or API calls - communicates via PromptQueueCallback.
 */
class PromptQueueManager(
    private val autoApplyIdsProvider: AutoApplyIdsProvider,
    private val sp: SharedPreferences,
    private val callback: PromptQueueCallback
) {
    fun interface AutoApplyIdsProvider {
        fun getAutoApplyIds(): List<Int>
    }

    interface PromptQueueCallback {
        fun onQueueChanged(queuedIds: List<Int>)
    }

    private val queuedPromptIds = mutableListOf<Int>()

    /**
     * Toggle a prompt in/out of the queue.
     */
    fun togglePrompt(promptId: Int) {
        if (promptId < 0) return

        synchronized(queuedPromptIds) {
            if (queuedPromptIds.contains(promptId)) {
                queuedPromptIds.remove(promptId)
            } else {
                queuedPromptIds.add(promptId)
            }
        }
        notifyChanged()
    }

    /**
     * Returns a snapshot of currently queued prompt IDs.
     */
    fun getQueuedIds(): List<Int> {
        synchronized(queuedPromptIds) {
            return ArrayList(queuedPromptIds)
        }
    }

    /**
     * Clears all queued prompts.
     */
    fun clear() {
        synchronized(queuedPromptIds) {
            queuedPromptIds.clear()
        }
        notifyChanged()
    }

    /**
     * Returns true if there are queued prompts.
     */
    fun hasQueuedPrompts(): Boolean {
        synchronized(queuedPromptIds) {
            return queuedPromptIds.isNotEmpty()
        }
    }

    /**
     * Prepares the auto-apply queue before recording starts.
     * Adds all prompts with autoApply=true at the front,
     * preserving any manually queued prompts after them.
     */
    fun prepareAutoApplyQueue() {
        val rewordingEnabled = sp.getBoolean("net.devemperor.dictate.rewording_enabled", true)
        if (!rewordingEnabled) return

        val autoApplyIds = autoApplyIdsProvider.getAutoApplyIds()
        synchronized(queuedPromptIds) {
            val manualQueue = queuedPromptIds.filter { it !in autoApplyIds }
            queuedPromptIds.clear()
            queuedPromptIds.addAll(autoApplyIds)
            queuedPromptIds.addAll(manualQueue)
        }
        notifyChanged()
    }

    private fun notifyChanged() {
        val snapshot = getQueuedIds()
        callback.onQueueChanged(snapshot)
    }
}
