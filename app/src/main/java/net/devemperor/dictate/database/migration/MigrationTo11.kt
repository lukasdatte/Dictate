package net.devemperor.dictate.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * M10 → M11 recreates `prompts` to add the Double-Enum `type` column
 * (`PromptType { PROMPT, TEXT }`) and classifies the existing rows.
 *
 * **Why the type column?** Pill kind was previously encoded as a fragile
 * runtime string convention (`prompt.startsWith("[") && endsWith("]")`), checked
 * in scattered places — a check every new code path could (and did) forget. The
 * explicit `type` column makes the kind first-class and lets the click/queue
 * paths branch on it structurally instead of re-parsing strings (see ADR —
 * prompt pill types).
 *
 * **Why a table-recreate?** SQLite has no `ALTER TABLE … ADD CHECK`; the
 * Double-Enum CHECK constraint (docs/DATABASE-PATTERNS.md) can only be added by
 * rebuilding the table (same pattern as MIGRATION_9_10).
 *
 * **Data classification** mirrors [net.devemperor.dictate.ai.prompt.PromptTypeClassifier]
 * (the shared trim+strip rule; `MigrationTo11Test` + `PromptTypeClassifierTest`
 * pin the equivalence):
 *  - a prompt whose *trimmed* text is fully bracketed → `type = 'TEXT'`, outer
 *    brackets stripped (whitespace trimmed along the way);
 *  - a fully-bracketed *name* is stripped too (plan F2), independent of the type,
 *    so migrated labels no longer show brackets;
 *  - everything else stays `type = 'PROMPT'`, unchanged.
 *
 * Edge case (plan F4): `"[a] und [b]"` trims to a bracketed string → TEXT with
 * inner content `a] und [b`, identical to the old runtime behaviour.
 *
 * Room's `validateMigration` ignores CHECK constraints, so the new CHECK is
 * invisible to schema validation — exactly how the existing `status`/`origin`/
 * `insertion_method` CHECKs already coexist with Room.
 *
 * @see net.devemperor.dictate.database.entity.PromptType
 * @see net.devemperor.dictate.ai.prompt.PromptTypeClassifier
 * @see docs/DATABASE-PATTERNS.md
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE prompts_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                pos INTEGER NOT NULL,
                name TEXT,
                prompt TEXT,
                requires_selection INTEGER NOT NULL,
                auto_apply INTEGER NOT NULL,
                type TEXT NOT NULL DEFAULT 'PROMPT'
                    CHECK (type IN ('PROMPT', 'TEXT'))
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO prompts_new (id, pos, name, prompt, requires_selection, auto_apply)
            SELECT id, pos, name, prompt, requires_selection, auto_apply FROM prompts
            """.trimIndent()
        )
        db.execSQL("DROP TABLE prompts")
        db.execSQL("ALTER TABLE prompts_new RENAME TO prompts")
        // Classify: a fully-bracketed (trimmed) prompt becomes TEXT and loses its
        // outer brackets. substr over trim(prompt) drops the first + last char of
        // the trimmed value, matching PromptTypeClassifier.classify exactly.
        db.execSQL(
            """
            UPDATE prompts SET
                type = 'TEXT',
                prompt = substr(trim(prompt), 2, length(trim(prompt)) - 2)
            WHERE prompt IS NOT NULL
              AND length(trim(prompt)) >= 2
              AND substr(trim(prompt), 1, 1) = '['
              AND substr(trim(prompt), -1) = ']'
            """.trimIndent()
        )
        // Name strip (plan F2): a fully-bracketed name loses its outer brackets
        // too, independent of the pill's type. Mirrors PromptTypeClassifier.stripName.
        db.execSQL(
            """
            UPDATE prompts SET
                name = substr(trim(name), 2, length(trim(name)) - 2)
            WHERE name IS NOT NULL
              AND length(trim(name)) >= 2
              AND substr(trim(name), 1, 1) = '['
              AND substr(trim(name), -1) = ']'
            """.trimIndent()
        )
    }
}
