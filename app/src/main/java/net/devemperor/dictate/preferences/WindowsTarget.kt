package net.devemperor.dictate.preferences

import android.content.SharedPreferences
import net.devemperor.dictate.ai.secrets.SecretStore
import net.devemperor.dictate.ai.secrets.SecretStoreException
import net.devemperor.dictate.secrets.PairingSecrets
import net.devemperor.dictate.shared.auth.Credentials
import java.nio.charset.StandardCharsets

/**
 * The resolved Windows-dispatch target — everything a call to the companion needs, read from the
 * preferences and the [SecretStore] in one place instead of four (ADR-0017/0019).
 *
 * The data class itself is pure (no Android): the `windows/` domain references it through an
 * injected `targetProvider: () -> WindowsTarget?` and never touches [SharedPreferences] directly
 * (purity rule V10). Only [isPaired]/[resolve] are Android-near — they live here in `preferences/`,
 * not in the domain.
 *
 * ## "Paired?" is a non-secret predicate; the secret lives in the store
 * Since the SecretStore migration (spec secretstore.md §7.2), the device secret is **not** a pref —
 * it is stored encrypted under [PairingSecrets.DEVICE_SECRET_REF]. So "is a PC paired?" can no
 * longer read the secret. It is instead [isPaired]: a predicate over the non-secret
 * [Pref.WindowsTargetUrl] + [Pref.WindowsDeviceId], which the pairing handshake writes together
 * with the secret and unpair clears together with it. In every steady state `url-present ⟺
 * secret-present`; the one window where they diverge is right after the migration deletes the
 * plaintext secret pref — and there the user **is** paired, which [isPaired] correctly reports
 * (the old "secret present in a pref" gate wrongly flipped them to unpaired).
 *
 * [resolve] is the only path that needs the secret value (the actual send target); it reads it
 * from the [SecretStore] and returns `null` when not paired or the secret is absent/undecryptable —
 * the same "pair again" outcome as a rejected secret (ADR-0017).
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
         * Whether a PC is paired — a **non-secret** predicate over [Pref.WindowsTargetUrl] +
         * [Pref.WindowsDeviceId] (both written by the pairing handshake, [Pref.WindowsTargetUrl]
         * cleared by unpair). This is the single "is a PC coupled?" gate everything reads; it never
         * touches the SecretStore, so UI/state/routing code stays decoupled from the crypto store.
         */
        @JvmStatic
        fun isPaired(sp: SharedPreferences): Boolean =
            sp.get(Pref.WindowsTargetUrl).isNotEmpty() && sp.get(Pref.WindowsDeviceId).isNotEmpty()

        /**
         * The full send target, or `null` when the phone is not paired ([isPaired] is false) or the
         * pairing secret is absent/undecryptable in [secretStore].
         *
         * Reads the device secret from [PairingSecrets.DEVICE_SECRET_REF]. A
         * [SecretStoreException] (store gone, rotated/foreign KEK) is treated as "not resolvable" →
         * `null`, the same "pair again" outcome as a rejected secret (ADR-0017), never a crash.
         */
        @JvmStatic
        fun resolve(sp: SharedPreferences, secretStore: SecretStore): WindowsTarget? {
            if (!isPaired(sp)) return null
            val secretBytes = try {
                secretStore.get(PairingSecrets.DEVICE_SECRET_REF)
            } catch (e: SecretStoreException) {
                null
            } ?: return null
            val deviceSecret = String(secretBytes, StandardCharsets.UTF_8)
            if (deviceSecret.isEmpty()) return null
            return WindowsTarget(
                baseUrl = sp.get(Pref.WindowsTargetUrl),
                deviceId = sp.get(Pref.WindowsDeviceId),
                deviceSecret = deviceSecret,
                serverName = sp.get(Pref.WindowsServerName),
            )
        }
    }
}
