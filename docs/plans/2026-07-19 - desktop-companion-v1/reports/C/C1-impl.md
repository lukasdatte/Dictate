# Chunk C1 — Konfigurations-Entitäten + kanonischer v3-Codec (`:shared`)

**Agent:** C1 IMPL+TEST · **Timestamp:** 2026-07-20T00:40:00+02:00
**Spec:** `research/entitaetenmodell-android.md` §4.1–4.8, §5.1–5.4 · **Plan:** §5 C1, §3 D5.a

## What I did

Implemented the pure `:shared` config layer: the five `@Serializable` entity DTOs plus the
envelope value types (§4.1–4.7), the seven wire enums (§4.8), the deterministic `CanonicalJson`
byte-form + `ENVELOPE_FIELDS` exclusion (§5.1/§4.2), the `contentHash` SHA-256 (§5.2), and the
single v3 door `CatalogCodec` over the `kind`-discriminated `CatalogEntry` union (§5.4), each DTO
carrying a co-located `ConfigValidations.Validation<T>` with an active `GATEWAY` rejection (F31).
Reused the protocol module's `DecodeResult` / `ProtocolViolationException` / `ValidationDetail`
(spec §5.4 references them) rather than defining parallel types. 43 config unit tests + the
existing `SharedPurityTest` are green.

## Files modified (all NEW, all in `:shared/config` scope)

Main:
- `shared/src/main/kotlin/net/devemperor/dictate/shared/config/ConfigEnums.kt`
- `shared/src/main/kotlin/net/devemperor/dictate/shared/config/Entities.kt`
- `shared/src/main/kotlin/net/devemperor/dictate/shared/config/CanonicalJson.kt`
- `shared/src/main/kotlin/net/devemperor/dictate/shared/config/ContentHash.kt`
- `shared/src/main/kotlin/net/devemperor/dictate/shared/config/ConfigValidations.kt`
- `shared/src/main/kotlin/net/devemperor/dictate/shared/config/CatalogCodec.kt`

Tests:
- `shared/src/test/kotlin/net/devemperor/dictate/shared/config/CanonicalJsonTest.kt` (7)
- `shared/src/test/kotlin/net/devemperor/dictate/shared/config/ContentHashTest.kt` (8)
- `shared/src/test/kotlin/net/devemperor/dictate/shared/config/ConfigValidationsTest.kt` (16)
- `shared/src/test/kotlin/net/devemperor/dictate/shared/config/CatalogCodecTest.kt` (12)

## Test run

`./gradlew :shared:test` → BUILD SUCCESSFUL. Config suites: CanonicalJson 7, ContentHash 8,
ConfigValidations 16, CatalogCodec 12 (43 total), 0 failures/skips. `SharedPurityTest` 2/2 green —
no `android`/`androidx`/`kotlinx.coroutines`/`io.ktor` import in the new files (AK1). Scoped to
`:shared:test` deliberately: the worktree has in-flight staged changes from parallel Block A
(`AIProvider.kt` already `git mv`-ed to `:shared-ai`); `:shared` builds independently of those, so
a full `./gradlew build` was intentionally NOT run (it would fold in another agent's mid-flight
state, not my chunk).

## Acceptance mapping (spec §2)

- **AK1** (Codec + Purity): 5 DTOs + co-located validations exist; `CatalogCodec` is the only v3
  door; `SharedPurityTest` green. ✓
- **AK2** (Kanonik-Stabilität): byte-snapshots per type; key sort incl. nested map (`CanonicalJsonTest`);
  same-payload⇒same-hash, per-field-change⇒new-hash, `orderedPrompts` reorder⇒new-hash, envelope
  change⇒same-hash, map-key-order⇒same-hash (`ContentHashTest`). ✓
- **AK3** (v3-Round-Trip): full 5-kind catalog round-trips byte-stable; Malformed (non-JSON /
  unknown `kind` / missing field / unknown enum) vs. Invalid (empty label, GATEWAY, indexed path)
  correctly split (`CatalogCodecTest`). ✓  v1/v2 prompt files intentionally do NOT pass through
  `CatalogCodec` (spec §5.4 IMPORTANT / §13 D3) — that legacy path stays Android-side (C3, §10.4).

## Deviations

| Deviation | Plan location | What changed | Why | Impact on later chunks | Resolved? |
|---|---|---|---|---|---|
| SHA-256 hex uses `(it.toInt() and 0xFF)` mask | spec §5.2 sample `"%02x".format(it)` | added unsigned mask | the spec sample sign-extends bytes ≥ 0x80 to `ffffff80` (a signed-`Byte` bug); a SHA-256 digest routinely has such bytes | none — produces the correct 64-char lowercase hex the spec text intends | Yes (guarded by `ContentHashTest.hash_is64LowercaseHex_andMatchesAnIndependentRenderer`) |
| `keyFingerprint` validated as exact `^[0-9a-f]{16}$` | spec §4.4 / §12 | tightened beyond "empty rejected" to the documented shape | §4.4 prescribes "sha256-hex, first 16 chars"; validating the exact shape is the sustainable contract and blocks a raw/truncated key masquerading as a fingerprint | C2 migration must emit exactly 16 lowercase hex chars (already the §4.4 contract) | Yes (documented; `plan-deviation-resolved`) |
| `promptV3.text` requires `minLength(1)` | spec §12 lists label/modelId/fingerprint violations, not prompt text | added non-empty text constraint | a shareable rewording prompt with empty text is meaningless | none | Yes |

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| C1-1 | Important | Enum-parity tests + `AIProvider↔ProviderType` etc. mappers (spec §4.8 / §13 D6 / Plan §3 D5.a) are NOT authored in C1. They must live in `:app` (the only module that sees both `:shared` wire enums AND the `:shared-ai`/`:app` domain originals — D5.a forbids a `:shared-ai`→`:shared` edge). C1 is pure-`:shared` (INTEGRATION_TARGETS=none) and runs parallel to the in-flight Block A `:app`/`:shared-ai` refactor; adding `:app` files now would collide with Block A and couple across blocks. §6 directory layout + §12 test list place them outside C1. Route to the `:app` config chunk (C2) after Block A settles the enum homes. | delegated | blocks-following |

The `:shared` wire-enum values were verified by hand against the current domain originals so the
future parity test will pass: `ProviderType` == `AIProvider` (OPENAI/GROQ/ANTHROPIC/ELEVENLABS/
OPENROUTER/CUSTOM), `AmbiguityModeValue` == `AmbiguityMode.persistKey` (ALWAYS_INSERT/AUTO/
ALWAYS_REVIEW), `ModelFunction` == `AIFunction` (TRANSCRIPTION/COMPLETION), `PromptSelectionMode`
correspondends to `PromptMode` 0/1/2 (NONE/PREDEFINED/CUSTOM).

## Inline fixes applied

Hardened the `contentHash` hex rendering (see Deviations) — the only non-additive fix; everything
else is greenfield.

## Files outside assigned scope (drift)

none.

## Self-check

Walked all five Phase-B checklist items. Findings fixed: (1) `CatalogCodec` path composition
normalised so an entity-level `constrain` (GATEWAY) surfaces as `entities[i]` rather than
`entities[i].` regardless of Konform's root `dataPath` representation; (2) removed a throwaway
custom `assertFalse` helper in favour of the JUnit import; (3) replaced raw control chars in the
canonical-escaping test source with a Kotlin char escape + literal `` assertion. All plan
requirements of §4–§5 implemented as specified except the delegated `:app`-side parity tests (C1-1).
