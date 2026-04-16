package net.devemperor.dictate.core

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * Java-friendly bridge to [ActiveJobRegistry]'s reactive [kotlinx.coroutines.flow.StateFlow].
 *
 * K3 minimal-fallback: Activities observe registry changes through this helper
 * instead of polling in `onResume`. The observer lifecycle is bound to the
 * host's [Lifecycle] via [repeatOnLifecycle], so the callback fires only while
 * the Activity is at least STARTED. Java callers receive the current snapshot
 * of `Map<sessionId, JobState>` on every change.
 *
 * Example (Java):
 * ```
 * ActiveJobRegistryObserver.observe(this, state -> { adapter.notifyDataSetChanged(); });
 * ```
 */
object ActiveJobRegistryObserver {

    /**
     * Registers [listener] to receive [ActiveJobRegistry] state snapshots on the
     * main thread for as long as [owner] is at least STARTED. The listener is
     * invoked once immediately with the current state.
     */
    @JvmStatic
    fun observe(
        owner: LifecycleOwner,
        listener: Listener
    ) {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ActiveJobRegistry.state.collect { snapshot ->
                    listener.onStateChanged(snapshot)
                }
            }
        }
    }

    /** Functional-interface-compatible listener so Java lambdas work. */
    fun interface Listener {
        fun onStateChanged(snapshot: Map<String, JobState>)
    }
}
