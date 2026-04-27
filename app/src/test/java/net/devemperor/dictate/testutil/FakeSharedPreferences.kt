package net.devemperor.dictate.testutil

import android.content.SharedPreferences

/**
 * Minimal in-memory [SharedPreferences] fake for unit tests.
 *
 * Hoisted from `VersionedPluginRegistryTest` (Chunk 2) so multiple
 * test classes (storage, controllers, migrations) can reuse the
 * same lightweight Map-backed implementation. No Mockito; matches
 * the project's hand-written-Fake convention (Quality-Gate K-1).
 *
 * Implements only the surface area that production code actually
 * touches: getString, getStringSet, getInt/Long/Float/Boolean,
 * contains, getAll plus an [Editor] that mutates the same map on
 * `apply()`. Listener registration is a no-op — none of the consumers
 * in this codebase observe SharedPreferences changes synchronously.
 */
class FakeSharedPreferences : SharedPreferences {
    private val store = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = store.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        (store[key] as? String) ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        val raw = store[key] as? Set<String> ?: return defValues
        return raw.toMutableSet()
    }

    override fun getInt(key: String?, defValue: Int): Int = (store[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (store[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (store[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        (store[key] as? Boolean) ?: defValue

    override fun contains(key: String?): Boolean = store.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(store)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) { /* no-op */ }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) { /* no-op */ }

    private class FakeEditor(private val store: MutableMap<String, Any?>) :
        SharedPreferences.Editor {

        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putStringSet(
            key: String?, values: MutableSet<String>?
        ): SharedPreferences.Editor {
            if (key != null) pending[key] = values
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) removals.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) store.clear()
            removals.forEach { store.remove(it) }
            store.putAll(pending)
        }
    }
}
