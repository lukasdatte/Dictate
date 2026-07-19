package net.devemperor.dictate.shared.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.devemperor.dictate.shared.protocol.DecodeResult
import net.devemperor.dictate.shared.protocol.ProtocolViolationException
import net.devemperor.dictate.shared.protocol.ValidationDetail

/**
 * The v3 catalog file — a versioned envelope over a tagged union of config entities.
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015). The SAF file export (§10.5) and the Block E peer wire use the SAME
 * [CatalogCodec] — the "one codec implementation" of F23.
 */
@Serializable
data class CatalogFileV3(
    val version: Int = 3,
    val entities: List<CatalogEntry>,
)

/**
 * A tagged union over the `kind` discriminator ([CanonicalJson.CATALOG_DISCRIMINATOR]).
 *
 * A sealed interface, so [ConfigValidations.validateEntry] and every consumer stays exhaustive:
 * a new entity kind is a compile error until it is wired everywhere. The discriminator sits on
 * this wrapper (`{"kind":"provider","entity":{…}}`); the inner entity's own `kind`
 * (ProviderConfigEntity.kind) is nested under `entity` and never collides.
 */
@Serializable
sealed interface CatalogEntry {
    @Serializable @SerialName("provider") data class Provider(val entity: ProviderConfigEntity) : CatalogEntry
    @Serializable @SerialName("credential") data class Credential(val entity: ApiCredentialEntity) : CatalogEntry
    @Serializable @SerialName("model") data class Model(val entity: ModelRefEntity) : CatalogEntry
    @Serializable @SerialName("prompt") data class Prompt(val entity: PromptV3Entity) : CatalogEntry
    @Serializable @SerialName("profile") data class Profile(val entity: ProfileEntity) : CatalogEntry
}

/**
 * The ONE v3 door — the only way a config catalog enters or leaves as v3.
 *
 * Mirrors `ProtocolCodec` for the config layer (ADR-0016): encode validates on the way out and
 * throws [ProtocolViolationException] (a send-side bug surfaces where it is born, not as a puzzling
 * error on the far side); decode validates on the way in and returns [DecodeResult] so the caller
 * can tell a broken file ([DecodeResult.Malformed]) from a contract violation
 * ([DecodeResult.Invalid]) — reusing the protocol module's result type rather than a parallel one.
 *
 * The file body is emitted through [CanonicalJson] (§5.4) so a v3→v3 round-trip is byte-stable and
 * a receiver can independently recompute each entity's `contentHash` from its payload. Per-entity
 * `contentHash`/`updatedAt` are the caller's responsibility to set correctly before encoding
 * (`ConfigRepository`, §5.3); this codec transports them, it does not recompute them.
 *
 * > v1/v2 prompt files do NOT pass through here. They are legacy prompt exports (ADR-0024) routed
 * > to the existing Android `PromptImportExport` path by the import dispatcher (§10.4). This codec
 * > is v3-only, which keeps the pill classification untouched and the v3 format single-purpose.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §5.4, §12
 */
object CatalogCodec {

    /**
     * `ignoreUnknownKeys` — an additive field from a newer peer must not break an older reader.
     * `classDiscriminator` shares [CanonicalJson.CATALOG_DISCRIMINATOR] so decode reads the same
     * `"kind"` tag that [CanonicalJson] writes.
     */
    val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = CanonicalJson.CATALOG_DISCRIMINATOR
    }

    /** @throws ProtocolViolationException if any entity in [file] violates its own contract. */
    fun encode(file: CatalogFileV3): String {
        val details = collectViolations(file)
        if (details.isNotEmpty()) throw ProtocolViolationException(details)
        return CanonicalJson.canonicalString(file, CatalogFileV3.serializer())
    }

    fun decode(raw: String): DecodeResult<CatalogFileV3> {
        val file = try {
            json.decodeFromString(CatalogFileV3.serializer(), raw)
        } catch (e: SerializationException) {
            // Not JSON, a missing required field, a wrong type, or an unknown `kind` discriminator
            // — all mean the same to the caller: this file is not a well-formed v3 catalog.
            return DecodeResult.Malformed(e.message ?: e::class.java.simpleName)
        }

        val details = collectViolations(file)
        return if (details.isEmpty()) DecodeResult.Ok(file) else DecodeResult.Invalid(details)
    }

    /**
     * Konform renders an entity property path as `.label`; prefixing it with `entities[i]` pinpoints
     * the offending row in a multi-entity file (`entities[3].label`). An entity-level `constrain`
     * (e.g. the GATEWAY rule) has an empty path and surfaces as plain `entities[3]`.
     */
    private fun collectViolations(file: CatalogFileV3): List<ValidationDetail> =
        file.entities.flatMapIndexed { index, entry ->
            ConfigValidations.validateEntry(entry).map { error ->
                // A property error's dataPath is ".label"; an entity-level constrain (GATEWAY) has an
                // empty (or ".") root path — normalise so it surfaces as plain `entities[i]`.
                val suffix = error.dataPath.takeUnless { it.isEmpty() || it == "." } ?: ""
                ValidationDetail(path = "entities[$index]$suffix", message = error.message)
            }
        }
}
