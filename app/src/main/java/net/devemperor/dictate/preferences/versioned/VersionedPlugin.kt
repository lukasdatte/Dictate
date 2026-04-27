package net.devemperor.dictate.preferences.versioned

/**
 * Base class for a plugin that owns a single versioned preference key.
 *
 * Each plugin defines:
 * - the [name] (also the SharedPreferences key)
 * - the [currentVersion] expected by the running app
 * - the [defaultValue] used for fresh installs and reset-to-default recovery
 * - the [codec] that bridges between [T] and `org.json` shapes
 * - the per-source-version [migrations] that walk the version chain forward
 * - an optional [sanitize] hook for post-decode validation/coercion
 *
 * Plugins are loaded/saved via [VersionedPrefs] and discovered through
 * [VersionedPluginRegistry].
 *
 * ### Registration is explicit
 *
 * **Plugins are NOT automatically registered.** Callers must explicitly call
 * `VersionedPluginRegistry.register(...)` for each plugin, usually in
 * `Application.onCreate()`. We deliberately avoid `init { register(this) }`
 * blocks inside plugin objects because Kotlin object class-load is triggered
 * by the first reference, which couples app-correctness to a non-obvious
 * load timing (and made earlier code rely on a "touch the .name property to
 * force class-load" workaround). Explicit registration keeps the wiring
 * greppable and the failure modes loud.
 *
 * The [onMissingMigration] strategy controls behaviour when a migration step
 * is missing, when a migration throws, or when the on-disk data is from a
 * future (newer) app version. Use [OnMissingMigration.THROW] for critical
 * data and [OnMissingMigration.RESET_TO_DEFAULT] for soft state like UI
 * preferences.
 */
abstract class VersionedPlugin<T>(
    val name: String,
    val currentVersion: Int,
    val defaultValue: T,
    val codec: JsonCodec<T>,
    val onMissingMigration: OnMissingMigration = OnMissingMigration.RESET_TO_DEFAULT
) {
    /**
     * Migrations from each source version `N` to `N+1`.
     *
     * Empty for v1-only plugins. The keys are the **source** versions; the
     * function value transforms the raw decoded shape.
     */
    abstract val migrations: Map<Int, MigrationFn>

    /**
     * Optional post-migration hook for validation, coercion, and allowlist
     * filtering. Default implementation is identity. Called by the
     * serializer/loader after a successful decode + migration, and again
     * before every save (to enforce the same invariants on writes).
     */
    open fun sanitize(value: T): T = value
}
