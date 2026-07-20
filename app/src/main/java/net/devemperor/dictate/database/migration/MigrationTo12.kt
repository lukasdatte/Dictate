package net.devemperor.dictate.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * M11 → M12 introduces the shareable **config-entity model** (spec §7):
 *
 *  - five new tables — `provider_configs`, `api_credentials`, `model_refs`, `profiles`,
 *    `profile_prompts` — each finite-set column carrying its Double-Enum `CHECK`
 *    (docs/DATABASE-PATTERNS.md);
 *  - the existing `prompts` table is **recreated** (SQLite has no `ALTER TABLE … ADD CHECK`) to
 *    add the shareable `uuid` identity plus the envelope/provenance columns, preserving the
 *    ADR-0024 `type` pill column and its CHECK (§7.3). The `uuid`/`content_hash` backfill is NOT
 *    done here — it runs in the config-entity migration (§8.5), which also fills the new tables.
 *
 * Purely structural: no existing row is dropped, and the new columns get inert defaults
 * (`uuid=''`, `content_hash=''`, `updated_at=0`, `visibility='PRIVATE'`, `subscription_mode='LOCAL'`)
 * so the pill row keeps working until the config-entity migration backfills identities.
 *
 * Room's `validateMigration` ignores `CHECK` constraints and SQLite `DEFAULT` clauses, so the extra
 * CHECKs/DEFAULTs stay invisible to schema validation exactly as the existing `type`/`status`/
 * `origin` CHECKs already do (same pattern as [MIGRATION_10_11]).
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §7.2, §7.3
 * @see docs/DATABASE-PATTERNS.md
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS provider_configs (
                id TEXT NOT NULL PRIMARY KEY,
                provider_type TEXT NOT NULL
                    CHECK (provider_type IN ('OPENAI','GROQ','ANTHROPIC','ELEVENLABS','OPENROUTER','CUSTOM')),
                kind TEXT NOT NULL DEFAULT 'LOCAL' CHECK (kind IN ('LOCAL','GATEWAY')),
                label TEXT NOT NULL,
                base_url TEXT,
                credential_ref TEXT,
                visibility TEXT NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','SHARED')),
                subscription_mode TEXT NOT NULL DEFAULT 'LOCAL'
                    CHECK (subscription_mode IN ('LOCAL','SUBSCRIBE','ONE_SHOT')),
                source_peer_id TEXT,
                source_original_id TEXT,
                source_original_hash TEXT,
                content_hash TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS api_credentials (
                id TEXT NOT NULL PRIMARY KEY,
                provider_type TEXT NOT NULL
                    CHECK (provider_type IN ('OPENAI','GROQ','ANTHROPIC','ELEVENLABS','OPENROUTER','CUSTOM')),
                label TEXT NOT NULL,
                key_fingerprint TEXT NOT NULL,
                visibility TEXT NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','SHARED')),
                subscription_mode TEXT NOT NULL DEFAULT 'LOCAL'
                    CHECK (subscription_mode IN ('LOCAL','SUBSCRIBE','ONE_SHOT')),
                source_peer_id TEXT,
                source_original_id TEXT,
                source_original_hash TEXT,
                content_hash TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS model_refs (
                id TEXT NOT NULL PRIMARY KEY,
                provider_ref TEXT NOT NULL,
                model_id TEXT NOT NULL,
                function TEXT NOT NULL CHECK (function IN ('TRANSCRIPTION','COMPLETION')),
                label TEXT,
                parameter_defaults TEXT NOT NULL DEFAULT '{}',
                visibility TEXT NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','SHARED')),
                subscription_mode TEXT NOT NULL DEFAULT 'LOCAL'
                    CHECK (subscription_mode IN ('LOCAL','SUBSCRIBE','ONE_SHOT')),
                source_peer_id TEXT,
                source_original_id TEXT,
                source_original_hash TEXT,
                content_hash TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_model_refs_provider_ref ON model_refs(provider_ref)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS profiles (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                transcription_model_ref TEXT,
                completion_model_ref TEXT,
                style_prompt_mode TEXT NOT NULL DEFAULT 'PREDEFINED'
                    CHECK (style_prompt_mode IN ('NONE','PREDEFINED','CUSTOM')),
                style_prompt_custom_text TEXT NOT NULL DEFAULT '',
                system_prompt_mode TEXT NOT NULL DEFAULT 'PREDEFINED'
                    CHECK (system_prompt_mode IN ('NONE','PREDEFINED','CUSTOM')),
                system_prompt_custom_text TEXT NOT NULL DEFAULT '',
                ambiguity_mode TEXT NOT NULL DEFAULT 'ALWAYS_INSERT'
                    CHECK (ambiguity_mode IN ('ALWAYS_INSERT','AUTO','ALWAYS_REVIEW')),
                parameter_overrides TEXT NOT NULL DEFAULT '{}',
                visibility TEXT NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','SHARED')),
                subscription_mode TEXT NOT NULL DEFAULT 'LOCAL'
                    CHECK (subscription_mode IN ('LOCAL','SUBSCRIBE','ONE_SHOT')),
                source_peer_id TEXT,
                source_original_id TEXT,
                source_original_hash TEXT,
                content_hash TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS profile_prompts (
                profile_id TEXT NOT NULL,
                pos INTEGER NOT NULL,
                prompt_ref TEXT NOT NULL,
                auto_apply INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (profile_id, pos),
                FOREIGN KEY (profile_id) REFERENCES profiles(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_profile_prompts_prompt_ref ON profile_prompts(prompt_ref)")

        // ── prompts recreate (§7.3): add uuid + envelope columns, keep ADR-0024 `type` CHECK ──
        db.execSQL(
            """
            CREATE TABLE prompts_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                pos INTEGER NOT NULL,
                name TEXT,
                prompt TEXT,
                requires_selection INTEGER NOT NULL,
                auto_apply INTEGER NOT NULL,
                type TEXT NOT NULL DEFAULT 'PROMPT' CHECK (type IN ('PROMPT','TEXT')),
                uuid TEXT NOT NULL DEFAULT '',
                visibility TEXT NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','SHARED')),
                subscription_mode TEXT NOT NULL DEFAULT 'LOCAL'
                    CHECK (subscription_mode IN ('LOCAL','SUBSCRIBE','ONE_SHOT')),
                source_peer_id TEXT,
                source_original_id TEXT,
                source_original_hash TEXT,
                content_hash TEXT NOT NULL DEFAULT '',
                updated_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO prompts_new (id, pos, name, prompt, requires_selection, auto_apply, type)
            SELECT id, pos, name, prompt, requires_selection, auto_apply, type FROM prompts
            """.trimIndent()
        )
        db.execSQL("DROP TABLE prompts")
        db.execSQL("ALTER TABLE prompts_new RENAME TO prompts")
    }
}
