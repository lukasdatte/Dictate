package net.devemperor.dictate.companion.ai

import net.devemperor.dictate.ai.secrets.SecretRef

/**
 * The SecretStore addressing convention for config-entity credentials on the companion — the desktop
 * mirror of the app's `ConfigSecrets`.
 *
 * The [CREDENTIAL_NAMESPACE] value MUST equal `:app`'s `ConfigSecrets.CREDENTIAL_NAMESPACE`
 * (`"credential"`): a credential delivered from a phone peer (Block E) or entered on either platform
 * has to resolve to the same [SecretRef.handle] on both sides, or the resolved key would be looked up
 * under a namespace the writer never used. `CredentialSecretsTest` pins the literal so the two copies
 * cannot drift; if this constant ever needs to move, promote it into `:shared-ai` rather than forking.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/desktop-host.md §5.1, §9
 */
object CredentialSecrets {

    /** Final home of an API key: `SecretRef("credential", <credentialRef>)`. */
    const val CREDENTIAL_NAMESPACE = "credential"

    fun credentialRef(credentialId: String): SecretRef =
        SecretRef(CREDENTIAL_NAMESPACE, credentialId)
}
