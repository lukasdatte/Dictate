package net.devemperor.dictate.shared.config

import io.konform.validation.Validation
import io.konform.validation.ValidationError
import io.konform.validation.constraints.maxLength
import io.konform.validation.constraints.minLength
import io.konform.validation.constraints.pattern
import io.konform.validation.onEach

/**
 * The value constraints of every config entity — one `Validation<T>` per DTO, co-located here.
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015). kotlinx-serialization owns the *shape* (types, required fields, enum
 * names); Konform owns the *values* (lengths, formats). Both are applied by [CatalogCodec] on the
 * way out AND on the way in, so a payload is validated by its sender and its receiver alike, and
 * neither can skip it — [CatalogCodec] is the only door (ADR-0016). This mirrors the wire-side
 * `Validations` object next door in `protocol/`.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §5.4, §12
 */
object ConfigValidations {

    private const val MAX_LABEL = 200
    private const val MAX_MODEL_ID = 200
    private const val MAX_NAME = 200

    /** `sha256(key)`-hex, first 16 chars (§4.4) — 16 lowercase hex digits, exactly. */
    private val FINGERPRINT_PATTERN = Regex("^[0-9a-f]{16}$")

    val providerConfig = Validation<ProviderConfigEntity> {
        ProviderConfigEntity::label {
            minLength(1)
            maxLength(MAX_LABEL)
        }
        // GATEWAY is reserved (F31): the enum value exists for wire-forward-stability, but a v1
        // user must not create one. This is the active rejection test the spec calls for (§12).
        constrain("provider kind GATEWAY is reserved (F31) and not selectable in v1") {
            it.kind != ProviderKind.GATEWAY
        }
    }

    val apiCredential = Validation<ApiCredentialEntity> {
        ApiCredentialEntity::label {
            minLength(1)
            maxLength(MAX_LABEL)
        }
        // The fingerprint is the ONLY key-derived field (F12); it must be the exact §4.4 shape so a
        // downstream cannot mistake a raw key or a truncated hash for a fingerprint.
        ApiCredentialEntity::keyFingerprint { pattern(FINGERPRINT_PATTERN) }
    }

    val modelRef = Validation<ModelRefEntity> {
        ModelRefEntity::providerRef { minLength(1) }
        ModelRefEntity::modelId {
            minLength(1)
            maxLength(MAX_MODEL_ID)
        }
    }

    val promptV3 = Validation<PromptV3Entity> {
        PromptV3Entity::name {
            minLength(1)
            maxLength(MAX_NAME)
        }
        PromptV3Entity::text { minLength(1) }
    }

    val profile = Validation<ProfileEntity> {
        ProfileEntity::name {
            minLength(1)
            maxLength(MAX_NAME)
        }
        ProfileEntity::orderedPrompts {
            onEach {
                ProfilePromptRef::promptRef { minLength(1) }
            }
        }
    }

    /**
     * Validate one catalog entry against the matching per-DTO rule; the empty list means valid.
     * The exhaustive `when` keeps every [CatalogEntry] variant wired to its validation — a new
     * variant is a compile error here until it gets one.
     */
    fun validateEntry(entry: CatalogEntry): List<ValidationError> = when (entry) {
        is CatalogEntry.Provider -> providerConfig(entry.entity).errors
        is CatalogEntry.Credential -> apiCredential(entry.entity).errors
        is CatalogEntry.Model -> modelRef(entry.entity).errors
        is CatalogEntry.Prompt -> promptV3(entry.entity).errors
        is CatalogEntry.Profile -> profile(entry.entity).errors
    }
}
