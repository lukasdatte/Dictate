package net.devemperor.dictate.config

import net.devemperor.dictate.ai.secrets.SecretRef

/**
 * The SecretStore addressing convention for config-entity credentials (spec §7.2). The one place
 * that names the `"credential"` namespace, so the write side (config-entity migration + future
 * settings writes) and the read side (`ProfileResolver`) can never drift on the handle format.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §7.2
 */
object ConfigSecrets {

    /** Final home of an API key: `SecretRef("credential", <ApiCredentialEntity.id>)`. */
    const val CREDENTIAL_NAMESPACE = "credential"

    fun credentialRef(credentialId: String): SecretRef =
        SecretRef(CREDENTIAL_NAMESPACE, credentialId)
}
