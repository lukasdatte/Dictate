package net.devemperor.dictate;

import android.app.Application;

import net.devemperor.dictate.core.RecordingRepository;
import net.devemperor.dictate.database.DictateDatabase;
import net.devemperor.dictate.database.DurationHealingJob;
import net.devemperor.dictate.preferences.PrefsMigration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DictateApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        PrefsMigration.migrateProviderPrefs(getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE));
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
}
