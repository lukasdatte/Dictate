package net.devemperor.dictate.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * M3 → M4 makes three coupled schema changes to the `sessions` table:
 *
 * 1. **Adds the `inserted_at` column** (INTEGER, nullable) — the
 *    "COMPLETED but not yet surfaced to the user" marker that drives
 *    the restart-button logic and the 7-day cleanup policy
 *    (Spec 1 §6.1 + §6.2 R.17). Pre-existing rows are backfilled with
 *    `inserted_at = NULL` — NULL means "the cleanup-marker does not
 *    apply to this row" so the user's pre-M4 history is immune to
 *    `deleteInsertedOlderThan` (see Spec 1 §6.5 + B3-VAL-W1 F-2).
 *
 * 2. **Extends the `status` CHECK constraint** to include `RECORDING`
 *    and `TRANSCRIBING`. Before M4 those live states existed only in
 *    [net.devemperor.dictate.core.ActiveJobRegistry] (process-local);
 *    persisting them enables the OOM-death-recovery path
 *    (Spec 1 §6.3 + §11.6).
 *
 * 3. **Changes the self-referential FK on `parent_session_id`** from
 *    `ON DELETE CASCADE` to `ON DELETE SET NULL`. Row-level DELETE on
 *    `sessions` (e.g. `deleteInsertedOlderThan`) used to take the
 *    POST_PROCESSING children of the deleted parent with it — a fresh
 *    translation could be silently wiped when its source-session aged
 *    out. After M4 the children survive with `parent_session_id = NULL`;
 *    they show as root-level history items (the history UI is a flat
 *    list anyway — no UX regression). See Spec 1 §6.5 + B3-VAL-W1 F-1
 *    + research/b3-cleanup-cascade-and-backfill-policy.md.
 *
 * SQLite cannot ALTER an existing CHECK constraint, so this migration
 * uses the standard table-recreate pattern (create `sessions_new`,
 * copy rows, drop the original, rename). The whole sequence runs in
 * a single Room transaction — a failure half-way through aborts
 * atomically and the schema stays on M3.
 *
 * **Data-preservation contract (B3-VAL-W1, F-1 + F-2):** Both the
 * FK-change (SET NULL) and the NULL-backfill converge on the same
 * NULL-as-unknown semantics — "this row is alive but the cleanup-marker
 * doesn't apply". Pre-existing user history is conserved across the
 * upgrade; future row-level DELETEs preserve children unconditionally.
 *
 * **Why no `index_sessions_inserted_at`?** The two queries that read
 * the column (`findPendingInsertion` and `deleteInsertedOlderThan`)
 * are off the hot path (each runs at most once per service lifecycle:
 * recovery on boot, cleanup on idle-stop). The expected `sessions`
 * row count stays small (<1k for typical users, <10k for power
 * users), so the index would inflate insert/update cost without a
 * measurable read win. See Spec 1 §6.1 for the trade-off and the
 * post-hoc-index migration plan (M4→M5) if telemetry ever shows
 * otherwise.
 *
 * **Why no index on `inserted_at` recreated in step 4?** Step 4
 * mirrors MIGRATION_2_3 exactly — same five indices (`parent_session_id`,
 * `type`, `created_at`, `origin`, `status`). Adding a sixth would
 * contradict the rationale above.
 *
 * **FK-Cascade safety.** `transcriptions` and `processing_steps` both
 * declare `FOREIGN KEY (session_id) REFERENCES sessions(id) ON
 * DELETE CASCADE`. SQLite's cascade fires on row-level DELETE, **not**
 * on `DROP TABLE` (see https://sqlite.org/foreignkeys.html §4.2),
 * and Room disables FK enforcement during migrations anyway. The
 * companion test `migrate3To4_preservesChildRows_processingStepsAndTranscriptions`
 * asserts this empirically.
 *
 * **Lessons inherited from MIGRATION_2_3** (MigrationTo3.kt:38-42):
 * - `audio_duration_seconds` declares NO SQL `DEFAULT` — the entity
 *   uses a Kotlin default (`= 0`), so Room's expected schema is
 *   `defaultValue='undefined'`. A SQL `DEFAULT 0` would mismatch.
 * - The `FOREIGN KEY` reference targets the FINAL table name
 *   (`sessions`), not the transient `sessions_new`. SQLite stores
 *   the FK text verbatim across `ALTER TABLE RENAME`.
 *
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §6.1 §6.5
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/b3-cleanup-cascade-and-backfill-policy.md
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Create the new table with the extended CHECK constraint
        //    + the new `inserted_at` column.
        db.execSQL(
            """
            CREATE TABLE sessions_new (
                id TEXT NOT NULL PRIMARY KEY,
                type TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                target_app_package TEXT,
                language TEXT,
                audio_file_path TEXT,
                audio_duration_seconds INTEGER NOT NULL,
                parent_session_id TEXT,
                status TEXT NOT NULL DEFAULT 'COMPLETED'
                    CHECK (status IN (
                        'RECORDING', 'RECORDED', 'TRANSCRIBING',
                        'COMPLETED', 'FAILED', 'CANCELLED'
                    )),
                origin TEXT NOT NULL DEFAULT 'KEYBOARD'
                    CHECK (origin IN ('KEYBOARD', 'HISTORY_REPROCESS', 'POST_PROCESSING')),
                queued_prompt_ids TEXT,
                -- Values MUST match net.devemperor.dictate.ai.AIProviderException.ErrorType
                -- (reused as the Double-Enum for this column — see docs/DATABASE-PATTERNS.md).
                -- Note: ErrorType.CANCELLED is intentionally EXCLUDED here — cancellation is
                -- expressed via sessions.status = CANCELLED with last_error_type = NULL.
                last_error_type TEXT
                    CHECK (last_error_type IS NULL OR last_error_type IN (
                        'INVALID_API_KEY', 'RATE_LIMITED', 'MODEL_NOT_FOUND',
                        'BAD_REQUEST', 'SERVER_ERROR', 'NETWORK_ERROR',
                        'UNKNOWN'
                    )),
                last_error_message TEXT,
                final_output_text TEXT,
                input_text TEXT,
                inserted_at INTEGER,
                FOREIGN KEY (parent_session_id) REFERENCES sessions (id) ON DELETE SET NULL
            )
            """.trimIndent()
        )

        // 2. Copy existing rows. `inserted_at` is backfilled with
        //    NULL for ALL pre-existing rows (Spec 1 §6.5 + B3-VAL-W1
        //    F-2). NULL means "the cleanup-marker doesn't apply to
        //    this row" — `deleteInsertedOlderThan` filters
        //    `inserted_at IS NOT NULL`, so pre-M4 history is immune.
        //    The user's "history is forever" pre-M4 expectation is
        //    preserved across the upgrade. Backfilling with `created_at`
        //    (the pre-fix value) would have made every COMPLETED row
        //    older than 7 days immediately eligible for deletion at the
        //    first idle-stop after upgrade — silent loss of months of
        //    history.
        //
        //    Pre-existing COMPLETED rows still surface as candidates in
        //    `findPendingInsertion`, gated by `Pref.PendingInsertionFreshnessMs`
        //    (default 24h on `created_at`) so the first post-upgrade boot
        //    doesn't flood `NotifyManualPasteNeeded`.
        db.execSQL(
            """
            INSERT INTO sessions_new (
                id, type, created_at, target_app_package, language,
                audio_file_path, audio_duration_seconds, parent_session_id,
                status, origin, queued_prompt_ids,
                last_error_type, last_error_message,
                final_output_text, input_text, inserted_at
            )
            SELECT
                id, type, created_at, target_app_package, language,
                audio_file_path, audio_duration_seconds, parent_session_id,
                status, origin, queued_prompt_ids,
                last_error_type, last_error_message,
                final_output_text, input_text,
                NULL
            FROM sessions
            """.trimIndent()
        )

        // 3. Drop the old table and rename the replacement. FK
        //    references in `transcriptions`/`processing_steps` are
        //    stored textually by name; after the rename they point at
        //    the new table without further work.
        db.execSQL("DROP TABLE sessions")
        db.execSQL("ALTER TABLE sessions_new RENAME TO sessions")

        // 4. Recreate the five indices (identical set to MIGRATION_2_3;
        //    no new index on `inserted_at` — see KDoc above).
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_parent_session_id ON sessions (parent_session_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_type ON sessions (type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_created_at ON sessions (created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_origin ON sessions (origin)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_status ON sessions (status)")
    }
}
