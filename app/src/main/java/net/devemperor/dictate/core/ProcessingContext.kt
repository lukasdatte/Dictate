package net.devemperor.dictate.core

import net.devemperor.dictate.database.entity.StepType

/**
 * Immutable context for a processing step. Passed as parameter instead of
 * pending* instance variables — thread-safe, no side effects.
 */
data class ProcessingContext(
    val stepType: StepType,
    val promptUsed: String?,
    val promptEntityId: Int?
)
