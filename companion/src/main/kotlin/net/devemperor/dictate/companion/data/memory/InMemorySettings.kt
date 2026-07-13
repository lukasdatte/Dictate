package net.devemperor.dictate.companion.data.memory

import net.devemperor.dictate.companion.domain.port.SettingsRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * Settings with no file behind them.
 *
 * Used by the E2E suite — whose server has no business writing into the developer's real settings —
 * and by anything that just wants the defaults.
 */
class InMemorySettings : SettingsRepository {

    private val values = ConcurrentHashMap<String, String>()

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String) {
        values[key] = value
    }
}
