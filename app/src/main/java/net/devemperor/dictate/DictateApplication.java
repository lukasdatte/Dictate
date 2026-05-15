package net.devemperor.dictate;

import android.app.Application;
import android.content.SharedPreferences;

import net.devemperor.dictate.core.RecordingRepository;
import net.devemperor.dictate.database.DictateDatabase;
import net.devemperor.dictate.database.DurationHealingJob;
import net.devemperor.dictate.preferences.InputLanguagesLegacyMigration;
import net.devemperor.dictate.preferences.InputLanguagesPlugin;
import net.devemperor.dictate.preferences.LanguageLabelResolver;
import net.devemperor.dictate.preferences.PrefsMigration;
import net.devemperor.dictate.preferences.versioned.VersionedPluginRegistry;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DictateApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // Quality-Gate W-1 + K-4: this is the canonical accessor for the
        // app's SharedPreferences (named "net.devemperor.dictate"). The
        // default-PreferenceManager file would be a different XML and the
        // migrations below would silently no-op there.
        SharedPreferences sp = getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE);

        // 1. Pre-existing provider migration (TLS, Anthropic-Keys, etc.).
        PrefsMigration.migrateProviderPrefs(sp);

        // 2. Versioned-envelope subsystem (Chunk 2 + Chunk 3).
        //    Order matters:
        //      a) LanguageLabelResolver.initialize loads the resource arrays
        //         that InputLanguagesPlugin.sanitize and the legacy migration
        //         consult (allowlist + label sort).
        //      b) Explicitly register each VersionedPlugin with the registry.
        //         Plugins do NOT self-register; this keeps the wiring greppable
        //         and removes the dependency on Kotlin object class-load
        //         timing (Quality-Gate W-1 follow-up).
        //      c) The legacy StringSet -> versioned-envelope migration. Must
        //         run BEFORE migrateAll() because VersionedPrefs.load fails
        //         to read a StringSet under the same key.
        //      d) migrateAll() eager-loads all registered plugins so the
        //         self-heal path runs on app start, not on first lazy read
        //         from a latency-sensitive code path.
        LanguageLabelResolver.INSTANCE.initialize(this);
        VersionedPluginRegistry.INSTANCE.register(InputLanguagesPlugin.INSTANCE);
        InputLanguagesLegacyMigration.INSTANCE.migrateFromLegacyStringSet(sp);
        VersionedPluginRegistry.INSTANCE.migrateAll(sp);

        // 3. Locale (existing).
        DictateUtils.applyApplicationLocale(this);

        // One-time duration healing: runs AFTER getInstance() returns to avoid the
        // onOpen re-entry issue (Finding SA-2 / CA-2 / SEC-0-2). A single-threaded
        // executor keeps the DB off the main thread; the job is idempotent.
        //
        // The executor is shut down immediately after enqueuing the single task
        // (Finding W3 / Chunk-1 fix): executor threads are non-daemon by default,
        // so without shutdown() they would keep the JVM alive and leak across the
        // process lifetime. shutdown() lets the submitted task finish and then
        // releases the worker thread.
        final DictateDatabase db = DictateDatabase.getInstance(this);
        final RecordingRepository recordingRepository = new RecordingRepository(this);
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() ->
                DurationHealingJob.INSTANCE.heal(db.sessionDao(), recordingRepository)
        );
        executor.shutdown();
    }

    // D-13 (Epic §4 Block C1): the process-scope legacy language-controller
    // singleton (and its no-op `PipelineUiStateReader`) was removed. The
    // permanent language SoT is now `preferences.LanguageResolver`, which
    // reads/writes the same `SharedPreferences` keys directly — callers
    // without a bound orchestrator (the Settings UI's `PreferencesFragment`)
    // use it statically, so no Application-held instance is required.
    // Because the SoT is the prefs file (not an object tied to either the
    // Application or the IME-service lifetime), the boot-before-bind path
    // (R-3) is safe by construction: a pre-bind read returns the persisted
    // value, never a stale cache or an NPE.
}
