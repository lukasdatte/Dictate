package net.devemperor.dictate.core

import android.content.SharedPreferences
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import net.devemperor.dictate.preferences.put

/**
 * Manages the prompt queue for rewording operations.
 *
 * Responsibilities:
 * - Queued prompt management (add, remove, clear)
 * - Auto-apply logic (prompts with autoApply=true)
 * - Prompt ordering (position-based)
 * - Persistence across keyboard restarts (SharedPreferences)
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
     * Persists the change to SharedPreferences.
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
        persistQueue()
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
     * Clears all queued prompts and persists the empty state.
     */
    fun clear() {
        synchronized(queuedPromptIds) {
            queuedPromptIds.clear()
        }
        persistQueue()
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
     * Restores the queue from SharedPreferences.
     * Filters out prompt IDs that no longer exist in the database.
     * @param validPromptIds set of prompt IDs that currently exist
     */
    fun restoreQueue(validPromptIds: Set<Int>) {
        val saved = sp.getString(Pref.QueuedPromptIds.key, null)
        if (saved.isNullOrEmpty()) return

        val restoredIds = saved.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in validPromptIds }

        synchronized(queuedPromptIds) {
            queuedPromptIds.clear()
            queuedPromptIds.addAll(restoredIds)
        }

        // Re-persist to clean up deleted prompt IDs
        if (restoredIds.size != saved.split(",").size) {
            persistQueue()
        }
        notifyChanged()
    }

    /**
     * Prepares the auto-apply queue before recording starts.
     * Adds all prompts with autoApply=true at the front,
     * preserving any manually queued prompts after them.
     * Does NOT persist — auto-apply additions are transient.
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

    private fun persistQueue() {
        // Only persist manually-queued IDs — auto-apply IDs are transient
        val autoApplyIds = autoApplyIdsProvider.getAutoApplyIds().toSet()
        synchronized(queuedPromptIds) {
            val manualIds = queuedPromptIds.filter { it !in autoApplyIds }
            val idsString = manualIds.joinToString(",")
            sp.edit().putString(Pref.QueuedPromptIds.key, idsString).apply()
        }
    }

    private fun notifyChanged() {
        val snapshot = getQueuedIds()
        callback.onQueueChanged(snapshot)
    }
}
