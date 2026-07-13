package net.devemperor.dictate.companion.domain.port

/**
 * The handful of values the user can change — port, bind address, clipboard-restore delay.
 *
 * A key/value table rather than a typed row: these settings are read once at start-up and written by
 * hand, and a schema migration for every new checkbox would be a tax with no payer. The *typing*
 * happens one layer up, in [net.devemperor.dictate.companion.domain.CompanionSettings], which is
 * where a bad value gets a default instead of a crash.
 */
interface SettingsRepository {

    fun get(key: String): String?

    fun put(key: String, value: String)
}
