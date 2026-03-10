package net.devemperor.dictate.preferences

import android.content.SharedPreferences

/**
 * One-time migration of SP provider keys from int (0/1/2) to String (enum name).
 * Without this migration, existing installations would break with ClassCastException
 * after the app update.
 */
object PrefsMigration {

    @JvmStatic
    fun migrateProviderPrefs(sp: SharedPreferences) {
        migrateKey(sp, Pref.TranscriptionProvider.key)
        migrateKey(sp, Pref.RewordingProvider.key)
    }

    private fun migrateKey(sp: SharedPreferences, key: String) {
        try {
            sp.getString(key, null)  // If this succeeds, already migrated (or new install)
        } catch (e: ClassCastException) {
            val oldInt = sp.getInt(key, 0)
            val newValue = when (oldInt) {
                0 -> "OPENAI"
                1 -> "GROQ"
                2 -> "CUSTOM"
                else -> "OPENAI"
            }
            sp.edit().putString(key, newValue).apply()
        }
    }
}
