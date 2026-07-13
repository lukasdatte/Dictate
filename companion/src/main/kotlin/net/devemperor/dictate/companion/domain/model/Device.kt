package net.devemperor.dictate.companion.domain.model

/** One paired phone. */
data class Device(
    val deviceId: String,
    val name: String,
    /** SHA-256 of the device secret, lowercase hex. The secret itself is never stored (ADR-0017). */
    val secretHash: String,
    val pairedAt: Long,
    val lastSeenAt: Long? = null,
)
