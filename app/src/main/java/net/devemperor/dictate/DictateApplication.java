package net.devemperor.dictate;

import android.app.Application;
import android.content.SharedPreferences;

import net.devemperor.dictate.core.LanguageController;
import net.devemperor.dictate.core.PipelineUiStateReader;
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

    /**
     * Lazily-built process-wide {@link LanguageController}, cached for use
     * by the Settings UI in Phase 3 ({@code DictateApplication.getOrCreateLanguageController}).
     * The IME service builds its own per-view controller in
     * {@code onCreateInputView()} — this field is solely for hosts that do
     * not own a {@link PipelineUiStateReader} of their own (e.g. the Settings
     * Activity).
     */
    private LanguageController settingsScopeLanguageController;

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

    /**
     * Lazy accessor for a process-scope {@link LanguageController}, intended
     * for callers that have no {@link PipelineUiStateReader} of their own
     * (the Settings UI in Phase 3). The provided reader is a no-op stand-in
     * (always reports {@code Idle}; {@code addCallback}/{@code removeCallback}
     * are silently dropped) — suitable for write-only callers that never
     * need to observe pipeline-state transitions.
     *
     * <p><b>Not</b> suitable for callers that need the controller's
     * effective-language callback to fire on pipeline transitions: those
     * callers must build their own controller instance via
     * {@code new LanguageController(sp, reader)} with a real
     * {@link PipelineUiStateReader} (the IME's {@code KeyboardUiController})
     * and dispose it on view recreate.</p>
     */
    public synchronized LanguageController getOrCreateLanguageController() {
        if (settingsScopeLanguageController == null) {
            SharedPreferences sp = getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE);
            settingsScopeLanguageController = new LanguageController(sp, NO_OP_PIPELINE_READER);
        }
        return settingsScopeLanguageController;
    }

    /**
     * No-op {@link PipelineUiStateReader} for the settings-scope controller.
     * Always reports Idle state; calls to {@code addCallback}/{@code removeCallback}
     * are silently dropped. The settings UI does not observe pipeline-state
     * transitions, so no callback delivery is required.
     */
    private static final PipelineUiStateReader NO_OP_PIPELINE_READER = new PipelineUiStateReader() {
        @Override
        public net.devemperor.dictate.core.PipelineUiState getState() {
            return net.devemperor.dictate.core.PipelineUiState.Idle.INSTANCE;
        }

        @Override
        public void updateReprocessLanguage(String code) { /* no-op: not in staging */ }

        @Override
        public void addCallback(net.devemperor.dictate.core.PipelineUiCallback callback) { /* no-op */ }

        @Override
        public void removeCallback(net.devemperor.dictate.core.PipelineUiCallback callback) { /* no-op */ }
    };
}
