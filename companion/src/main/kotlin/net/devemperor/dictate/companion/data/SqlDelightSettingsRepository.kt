package net.devemperor.dictate.companion.data

import net.devemperor.dictate.companion.db.DictateCompanionDb
import net.devemperor.dictate.companion.domain.port.SettingsRepository

class SqlDelightSettingsRepository(database: DictateCompanionDb) : SettingsRepository {

    private val queries = database.companionQueries

    override fun get(key: String): String? = queries.settingByKey(key).executeAsOneOrNull()

    // `value_` — SQLDelight renames the parameter because `value` collides with the setter keyword.
    override fun put(key: String, value: String) {
        queries.putSetting(key = key, value_ = value)
    }
}
