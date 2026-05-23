package net.devemperor.dictate.preferences.versioned

/**
 * Versioned envelope for externally stored JSON data.
 *
 * Wraps any value with the schema version that was current when it was written.
 * Used as the on-disk shape for plugin-managed preferences (see [VersionedPlugin]).
 *
 * Ported from the excel_ekl `shared/versioned/` system (TypeScript) — Kotlin
 * portation uses `org.json` for serialization and integer-only versions
 * (no semver).
 */
data class Versioned<T>(val version: Int, val value: T)

/**
 * A migration function that transforms data from version N to version N+1.
 *
 * Receives the raw value (not the envelope) and returns the transformed value.
 * Migrations are sequenced through the version chain by [VersionedMigrator].
 *
 * The function is intentionally typed as `(Any?) -> Any?` because intermediate
 * shapes between versions cannot be statically typed across a chain. Each
 * migration is responsible for asserting/casting the shape it expects.
 */
typealias MigrationFn = (oldValue: Any?) -> Any?

/**
 * Strategy when a required migration step is missing or a migration fails.
 *
 * - [THROW]: fail hard. Use for critical data where silent recovery would
 *   mask a bug or hide data corruption from the user.
 * - [RESET_TO_DEFAULT]: fall back to the plugin's default value. Use for
 *   non-critical data (e.g. UI preferences) where "fresh start" is acceptable.
 */
enum class OnMissingMigration { THROW, RESET_TO_DEFAULT }

/**
 * Result of a migration operation.
 *
 * @property data the migrated envelope at the plugin's current version
 * @property migrated `true` if any migration step ran (incl. reset-to-default)
 * @property fromVersion the source version found in the input envelope
 * @property toVersion the version of the resulting [data]
 */
data class MigrationResult<T>(
    val data: Versioned<T>,
    val migrated: Boolean,
    val fromVersion: Int,
    val toVersion: Int
)
