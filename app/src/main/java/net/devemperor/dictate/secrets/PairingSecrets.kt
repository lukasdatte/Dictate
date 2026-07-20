package net.devemperor.dictate.secrets

import net.devemperor.dictate.ai.secrets.SecretRef

/**
 * The [SecretStore][net.devemperor.dictate.ai.secrets.SecretStore] address of the pairing device
 * secret (spec secretstore.md §7.2).
 *
 * Single source of truth so the three parties that touch the secret can never drift on its handle:
 * - the migration **write** ([SecretsMigration] parks the plaintext pref here),
 * - the send-target **read** ([net.devemperor.dictate.preferences.WindowsTarget.resolve]),
 * - the pairing **write/clear** ([net.devemperor.dictate.settings.WindowsPairingActivity]).
 *
 * Exact analogue of [net.devemperor.dictate.config.ConfigSecrets] for the `credential` namespace.
 * Unlike the legacy secret pref constant (allow-listed to `DictatePrefs`/`SecretsMigration` only,
 * spec §2.6), this handle is the address every non-allow-listed reader/writer names instead.
 */
object PairingSecrets {

    /** Namespace of the pairing device secret — its final SecretStore home (§7.2). */
    const val NAMESPACE = "pairing"

    /** `SecretRef("pairing", "windows_device_secret")` — the one pairing secret. */
    @JvmField
    val DEVICE_SECRET_REF = SecretRef(NAMESPACE, "windows_device_secret")
}
