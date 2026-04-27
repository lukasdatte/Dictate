package net.devemperor.dictate.preferences.versioned

/**
 * Walks a [Versioned] envelope from its source version forward to the
 * plugin's [VersionedPlugin.currentVersion], applying each registered
 * [MigrationFn] in order.
 *
 * Behaviour matrix (Quality-Gate W-8):
 * - **`fromVersion == currentVersion`** — no-op, decode and return.
 * - **`fromVersion < currentVersion`** — sequentially apply
 *   `migrations[fromVersion]`, `migrations[fromVersion + 1]`, ... until
 *   reaching `currentVersion`. A missing step or thrown migration routes
 *   through [VersionedPlugin.onMissingMigration].
 * - **`fromVersion > currentVersion`** — data was written by a newer app
 *   version. Routed through [VersionedPlugin.onMissingMigration]. The
 *   `THROW` branch surfaces a hard error; the `RESET_TO_DEFAULT` branch
 *   returns the plugin's default. The latter is the right default for
 *   non-critical user data (e.g. language lists) where an app downgrade
 *   should not crash subsequent reads.
 */
object VersionedMigrator {

    fun <T> migrate(plugin: VersionedPlugin<T>, envelope: Versioned<Any?>): MigrationResult<T> {
        val fromVersion = envelope.version
        val targetVersion = plugin.currentVersion

        // Future-version handling (W-8): respect plugin strategy instead of
        // hard-throwing. App-downgrade after a future version was written
        // would otherwise crash every subsequent read of this preference.
        if (fromVersion > targetVersion) {
            return when (plugin.onMissingMigration) {
                OnMissingMigration.THROW -> throw IllegalStateException(
                    "${plugin.name}: data version $fromVersion > current $targetVersion. Update the app."
                )
                OnMissingMigration.RESET_TO_DEFAULT ->
                    resetToDefault(plugin, fromVersion)
            }
        }

        // Already at current version — decode and return.
        if (fromVersion == targetVersion) {
            return MigrationResult(
                data = Versioned(fromVersion, plugin.codec.decode(envelope.value)),
                migrated = false,
                fromVersion = fromVersion,
                toVersion = fromVersion
            )
        }

        // Sequential migration: v(from) -> v(from+1) -> ... -> v(target).
        var currentRaw: Any? = envelope.value
        var currentVersion = fromVersion

        while (currentVersion < targetVersion) {
            val migration = plugin.migrations[currentVersion]
                ?: return applyMissingMigrationStrategy(plugin, fromVersion, targetVersion)

            try {
                currentRaw = migration(currentRaw)
                currentVersion++
            } catch (e: Throwable) {
                return when (plugin.onMissingMigration) {
                    OnMissingMigration.THROW -> throw IllegalStateException(
                        "Migration failed for '${plugin.name}' v$currentVersion -> v${currentVersion + 1}",
                        e
                    )
                    OnMissingMigration.RESET_TO_DEFAULT ->
                        resetToDefault(plugin, fromVersion)
                }
            }
        }

        return MigrationResult(
            data = Versioned(currentVersion, plugin.codec.decode(currentRaw)),
            migrated = currentVersion > fromVersion,
            fromVersion = fromVersion,
            toVersion = currentVersion
        )
    }

    private fun <T> applyMissingMigrationStrategy(
        plugin: VersionedPlugin<T>,
        from: Int,
        to: Int
    ): MigrationResult<T> = when (plugin.onMissingMigration) {
        OnMissingMigration.THROW -> throw IllegalStateException(
            "Missing migration for '${plugin.name}' from v$from to v$to"
        )
        OnMissingMigration.RESET_TO_DEFAULT -> resetToDefault(plugin, from)
    }

    private fun <T> resetToDefault(
        plugin: VersionedPlugin<T>,
        from: Int
    ): MigrationResult<T> = MigrationResult(
        data = Versioned(plugin.currentVersion, plugin.defaultValue),
        migrated = true,
        fromVersion = from,
        toVersion = plugin.currentVersion
    )
}
