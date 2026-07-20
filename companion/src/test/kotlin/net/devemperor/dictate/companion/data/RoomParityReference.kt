package net.devemperor.dictate.companion.data

/**
 * The manual cross-module SSoT for the Room-parity enum vocabularies — guarded by a test.
 *
 * `:companion` cannot import the Room enums: they live in `:app`, which is Android, off this module's
 * classpath. So the Room-side truth is transcribed here **by hand**, once, each set carrying a
 * `SSoT:` pointer at its Room origin. `CompanionSchemaParityTest` asserts the companion enums equal
 * these sets — so a drift on either side (someone edits a companion enum, or forgets to pull a Room
 * change through) turns red with a diff that points at the Room file to reconcile against.
 *
 * This is deliberately a stopgap: the real fix is defining the enums once in `:shared` and mapping
 * both Room and SQLDelight onto them, an `:app` Room refactor out of Block D's scope (desktop-host.md
 * §15 Gap 1, adr-companion-history-parity). Until then, the tested reference list is the maintainable
 * middle ground.
 *
 * The values are the persisted `.name` strings, taken from the Room migration CHECK constraints (the
 * Room schema JSON carries no CHECKs — desktop-host.md §3.2).
 */
object RoomParityReference {

    // SSoT: app/.../database/entity/SessionType.kt + MigrationTo9.kt:46
    val SESSION_TYPE = setOf("RECORDING", "REWORDING", "POST_PROCESSING")

    // SSoT: app/.../database/entity/SessionStatus.kt:27 + MigrationTo9.kt:55
    val SESSION_STATUS = setOf(
        "RECORDING", "RECORDING_INTERRUPTED", "RECORDED",
        "TRANSCRIBING", "COMPLETED", "FAILED", "CANCELLED",
    )

    // SSoT: app/.../database/entity/SessionOrigin.kt:22 + MigrationTo9.kt:60
    val SESSION_ORIGIN = setOf("KEYBOARD", "HISTORY_REPROCESS", "POST_PROCESSING", "REVIEW_REFINEMENT")

    // SSoT: shared-ai/.../ai/AIProviderException.kt:18 (ErrorType) + app MigrationTo9.kt:66
    val AI_ERROR_TYPE = setOf(
        "INVALID_API_KEY", "RATE_LIMITED", "MODEL_NOT_FOUND", "BAD_REQUEST",
        "SERVER_ERROR", "NETWORK_ERROR", "CANCELLED", "UNKNOWN",
    )

    // SSoT: app/.../database/entity/StepType.kt:3 + MigrationTo8.kt:47
    val STEP_TYPE = setOf("AUTO_FORMAT", "REWORDING", "QUEUED_PROMPT", "CONVERSATION_TURN")

    // SSoT: app/.../database/entity/StepStatus.kt:3 (Room stores this as bare TEXT, no CHECK; the
    // companion adds one — desktop-host.md §14 D3. The vocabulary is still Room's.)
    val STEP_STATUS = setOf("SUCCESS", "ERROR")

    // SSoT: app/.../database/entity/ResponseFormatKind.kt:13 + MigrationTo8.kt:68
    val RESPONSE_FORMAT = setOf("JSON_SCHEMA", "TOOL_USE", "TEXT_FALLBACK")

    // SSoT: app/.../database/entity/MessageRole.kt:19 + MigrationTo8.kt:108
    val MESSAGE_ROLE = setOf("SYSTEM", "USER", "ASSISTANT")
}
