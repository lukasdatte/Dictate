package net.devemperor.dictate.preferences

import android.content.SharedPreferences
import net.devemperor.dictate.shared.auth.Credentials

/**
 * The resolved Windows-dispatch target — everything a call to the companion needs, read from the
 * preferences in one place instead of four (ADR-0017/0019).
 *
 * The data class itself is pure (no Android): the `windows/` domain references it through an
 * injected `targetProvider: () -> WindowsTarget?` and never touches [SharedPreferences] directly
 * (purity rule V10). Only [from] is Android-near — it lives here in `preferences/`, not in the
 * domain.
 *
 * `null` from [from] means **not paired**: the [deviceSecret] is the pairing proof (ADR-0017), so
 * an empty secret is the single "is a PC coupled?" gate everything reads.
 */
data class WindowsTarget(
    val baseUrl: String,
    val deviceId: String,
    val deviceSecret: String,
    val serverName: String,
) {
    /** The bearer credential this target authenticates with (ADR-0017). */
    fun credentials(): Credentials = Credentials(deviceId = deviceId, deviceSecret = deviceSecret)

    companion object {
        /**
         * The paired target, or `null` when the phone is not paired.
         *
         * Pairing is proven by the device secret and reachable only through a base URL, so both
         * must be present; the id and name are always written alongside them by the pairing
         * handshake, but an empty either way is treated as "not paired" defensively.
         */
        @JvmStatic
        fun from(sp: SharedPreferences): WindowsTarget? {
            val baseUrl = sp.get(Pref.WindowsTargetUrl)
            val deviceId = sp.get(Pref.WindowsDeviceId)
            val deviceSecret = sp.get(Pref.WindowsDeviceSecret)
            if (baseUrl.isEmpty() || deviceId.isEmpty() || deviceSecret.isEmpty()) return null
            return WindowsTarget(
                baseUrl = baseUrl,
                deviceId = deviceId,
                deviceSecret = deviceSecret,
                serverName = sp.get(Pref.WindowsServerName),
            )
        }
    }
}
