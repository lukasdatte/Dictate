package net.devemperor.dictate.companion.platform.fallback

import net.devemperor.dictate.companion.domain.port.AutostartManager

/** Autostart on a platform that has no registry Run key. Reports the truth: not supported, never on. */
object NoopAutostart : AutostartManager {

    override val supported: Boolean = false

    override fun isEnabled(): Boolean = false

    override fun setEnabled(enabled: Boolean) = Unit
}
