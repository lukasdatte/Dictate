package net.devemperor.dictate.preferences.versioned

import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry of [VersionedPlugin] instances.
 *
 * Plugins register themselves at app start (typically from
 * `DictateApplication.onCreate`) so [migrateAll] can eager-migrate all
 * persisted preferences on the first launch after an app upgrade. This keeps
 * the lazy-on-first-read path (`VersionedPrefs.load`) fast and avoids
 * surprise migrations during latency-sensitive code paths.
 *
 * Duplicate registration of the **same** plugin instance is idempotent;
 * registering a different instance under an existing name throws — that
 * indicates a wiring bug and silent acceptance would make later debugging
 * nearly impossible.
 *
 * Thread-safe via [ConcurrentHashMap]. [register] typically runs on the
 * app-startup main thread; reads ([findByName], [all], [migrateAll]) are
 * safe from any thread.
 */
object VersionedPluginRegistry {
    private const val TAG = "VersionedPluginRegistry"
    private val plugins = ConcurrentHashMap<String, VersionedPlugin<*>>()

    /**
     * Register [plugin]. Idempotent for the same instance; throws on a
     * collision with a *different* instance under the same name.
     */
    fun register(plugin: VersionedPlugin<*>) {
        val existing = plugins[plugin.name]
        if (existing != null && existing !== plugin) {
            throw IllegalStateException(
                "Duplicate plugin registration: '${plugin.name}'"
            )
        }
        plugins[plugin.name] = plugin
    }

    /** All registered plugins, in registration order. */
    fun all(): Collection<VersionedPlugin<*>> = plugins.values

    /** Look up a plugin by [name], or `null` if not registered. */
    fun findByName(name: String): VersionedPlugin<*>? = plugins[name]

    /**
     * Eagerly read every registered plugin's value, triggering migrations
     * and self-heal writes. Call once at app start.
     *
     * Quality-Gate W-9: a single broken plugin must not block the others.
     * Each plugin is wrapped in its own try/catch; failures are logged and
     * the loop continues to the next plugin.
     */
    fun migrateAll(prefs: SharedPreferences) {
        all().forEach { plugin ->
            try {
                VersionedPrefs.load(prefs, plugin)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to eager-migrate '${plugin.name}'", e)
            }
        }
    }

    /**
     * Test-seam: clears the registry so each test starts from a known state.
     * Pattern mirrors `ActiveJobRegistry.resetRegistry()`.
     */
    @VisibleForTesting
    internal fun reset() {
        plugins.clear()
    }
}
