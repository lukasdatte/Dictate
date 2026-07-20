package net.devemperor.dictate.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.PromptEntity
import net.devemperor.dictate.database.entity.PromptType
import net.devemperor.dictate.shared.config.CatalogCodec
import net.devemperor.dictate.shared.config.CatalogEntry
import net.devemperor.dictate.shared.config.PromptV3Entity
import net.devemperor.dictate.shared.config.contentHash
import net.devemperor.dictate.shared.config.contentHashOfElement
import net.devemperor.dictate.shared.protocol.DecodeResult

/**
 * The SAF **import dispatcher** (spec §10.4): reads one file, detects the format, and routes it —
 *
 *  - **v3** (`{"version":3,…}`) → [CatalogCodec.decode] → per-entity `contentHash` recompute check
 *    (§5.3 — a file whose carried hash does not match its payload is corrupt or tampered) →
 *    [ConfigRepository] upserts; prompt entities land as Android `prompts` rows with `type=PROMPT`
 *    (§13 D3) matched by `uuid`.
 *  - **v1/v2** (legacy prompt files) → the caller routes the raw text through the EXISTING
 *    `PromptImportExport.parse` Android path (ADR-0024 untouched); [appendLegacyPrompts] then
 *    persists the parsed rows WITH the §8.5 uuid/content-hash backfill.
 *
 * Credentials inside a v3 file are metadata-only hulls (§13 D5) and are imported as such — the key
 * itself never travels in a file; the receiver sets it in the provider editor.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §10.4
 */
object CatalogImport {

    /** Detected file format. */
    enum class Format { V3, LEGACY_PROMPTS }

    sealed interface Result {
        /** v3 import applied. Counts are per entity kind for the summary toast. */
        data class V3Imported(
            val providers: Int,
            val credentials: Int,
            val models: Int,
            val prompts: Int,
            val profiles: Int,
        ) : Result

        /** File is not a well-formed v3 catalog (broken JSON, unknown kind, wrong types). */
        data class Malformed(val message: String) : Result

        /** Well-formed v3, but a contract violation or a §5.3 hash mismatch. */
        data class Invalid(val message: String) : Result
    }

    private val lenientJson = Json { ignoreUnknownKeys = true }

    /**
     * v3 iff the root is a JSON object with `"version": 3`. Anything else — v1 arrays, v2 prompt
     * objects (`"version": 2`), or non-JSON — is routed to the legacy prompt path, whose own parser
     * reports errors exactly as before.
     */
    fun detect(raw: String): Format {
        val version = runCatching {
            lenientJson.parseToJsonElement(raw).jsonObject["version"]?.jsonPrimitive?.intOrNull
        }.getOrNull()
        return if (version == 3) Format.V3 else Format.LEGACY_PROMPTS
    }

    /** Decodes + validates + applies a v3 catalog file. All DB writes run in one transaction. */
    fun importV3(raw: String, db: DictateDatabase, repo: ConfigRepository = ConfigRepository(db)): Result {
        val file = when (val decoded = CatalogCodec.decode(raw)) {
            is DecodeResult.Malformed -> return Result.Malformed(decoded.reason)
            is DecodeResult.Invalid ->
                return Result.Invalid(decoded.details.joinToString("; ") { "${it.path}: ${it.message}" })
            is DecodeResult.Ok -> decoded.value
        }

        // §5.3 integrity check — recompute each carried contentHash from the RAW file bytes, NOT the
        // decoded object. `decode` used `ignoreUnknownKeys`, so an additive field from a newer writer
        // is dropped from the typed entity; hashing that lossy projection would never reproduce the
        // hash the writer computed over the superset payload and would reject a valid cross-version
        // file. Hashing the re-parsed JsonElement (schema-less — it drops nothing) keeps the file
        // forward-compatible while a genuinely tampered payload value still mismatches. (finding
        // logic-C-1; Block E's peer recompute MUST reuse contentHashOfElement for the same reason.)
        val mismatches = lenientJson.parseToJsonElement(raw)
            .jsonObject["entities"]!!.jsonArray
            .mapNotNull { entry ->
                val payload = entry.jsonObject["entity"]!!.jsonObject
                val carried = payload["contentHash"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val id = payload["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (carried.isNotEmpty() && carried != contentHashOfElement(payload)) id else null
            }
        if (mismatches.isNotEmpty()) {
            return Result.Invalid("contentHash mismatch (corrupt or tampered file) for: ${mismatches.joinToString()}")
        }

        var providers = 0
        var credentials = 0
        var models = 0
        var prompts = 0
        var profiles = 0
        db.runInTransaction {
            file.entities.forEach { entry ->
                when (entry) {
                    is CatalogEntry.Provider -> { repo.upsertProviderConfig(entry.entity); providers++ }
                    is CatalogEntry.Credential -> { repo.upsertCredential(entry.entity); credentials++ }
                    is CatalogEntry.Model -> { repo.upsertModelRef(entry.entity); models++ }
                    is CatalogEntry.Prompt -> { upsertPromptRow(db, repo.clock, entry.entity); prompts++ }
                    is CatalogEntry.Profile -> { repo.upsertProfile(entry.entity); profiles++ }
                }
            }
        }
        return Result.V3Imported(providers, credentials, models, prompts, profiles)
    }

    /**
     * A v3 prompt entity → Android `prompts` row (`type=PROMPT`, §13 D3), matched by `uuid`: an
     * existing row is updated in place (keeping its pill `type` and `pos`), a new one is appended.
     */
    private fun upsertPromptRow(db: DictateDatabase, clock: () -> Long, dto: PromptV3Entity) {
        val dao = db.promptDao()
        val existing = dao.getAll().firstOrNull { it.uuid == dto.id }
        val row = (
            existing?.copy(
                name = dto.name,
                prompt = dto.text,
                requiresSelection = dto.requiresSelection,
                autoApply = dto.autoApply,
            ) ?: PromptEntity(
                id = 0,
                pos = dao.nextPos(),
                name = dto.name,
                prompt = dto.text,
                requiresSelection = dto.requiresSelection,
                autoApply = dto.autoApply,
                type = PromptType.PROMPT.name,
                uuid = dto.id,
            )
            ).copy(
                visibility = dto.visibility.name,
                subscriptionMode = dto.subscriptionMode.name,
                sourcePeerId = dto.sourceRef?.peerId,
                sourceOriginalId = dto.sourceRef?.originalId,
                sourceOriginalHash = dto.sourceRef?.originalContentHash,
                contentHash = contentHash(dto, PromptV3Entity.serializer()),
                updatedAt = clock(),
            )
        if (existing != null) dao.update(row) else dao.insert(row)
    }

    /**
     * Persists prompts parsed by the LEGACY v1/v2 path (`PromptImportExport.parse`) appended at the
     * end of the list, each with the §8.5 backfill (fresh uuid + content hash + local envelope) so a
     * legacy import is immediately shareable/profile-referencable like every migrated row.
     */
    @JvmStatic
    fun appendLegacyPrompts(db: DictateDatabase, parsed: List<PromptEntity>, clock: () -> Long = { System.currentTimeMillis() }) {
        val dao = db.promptDao()
        db.runInTransaction {
            var pos = dao.nextPos()
            parsed.forEach { entity ->
                val uuid = java.util.UUID.randomUUID().toString()
                val row = entity.copy(
                    id = 0,
                    pos = pos++,
                    uuid = uuid,
                    visibility = "PRIVATE",
                    subscriptionMode = "LOCAL",
                    sourcePeerId = null,
                    sourceOriginalId = null,
                    sourceOriginalHash = null,
                    updatedAt = clock(),
                )
                dao.insert(row.copy(contentHash = PromptHashing.contentHashOf(uuid, row)))
            }
        }
    }
}
