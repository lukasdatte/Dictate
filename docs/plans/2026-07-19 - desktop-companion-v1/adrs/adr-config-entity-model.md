# ADR-NNNN: Configuration Entity Model — ProviderConfig / ModelRef / Prompt / Profile in `:shared`, Canonical Serialization + contentHash as the v3 Format

**Status:** Proposed (plan-scoped — pending promotion)
**Scope:** Project-Wide
**Date:** 2026-07-20
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Plain-language summary.** Dictate's configuration today is a flat pile of
> `SharedPreferences` strings — provider keys, a chosen model, style/system
> prompts, ambiguity mode — with no shareable, versionable shape. This ADR
> introduces a small set of **entities** that model configuration as data:
> **ProviderConfig** (a provider + its endpoint), **ApiCredential** (a key,
> stored only in the SecretStore), **ModelRef** (a specific model with default
> parameters), **Prompt** (a rewording/system prompt), and **Profile** (the
> user-visible bundle that ties a transcription model, a completion model, an
> ordered list of prompts, and an ambiguity mode together). Each entity has a
> **canonical serialization** — one exact byte form — and a **contentHash** (a
> SHA-256 over that form) so two devices can tell whether they hold the same
> thing. That canonical form *is* the "v3" file/wire format used for export and
> for peer sharing. Android migrates onto this model immediately with v1.
> Jargon: an **entity** is a typed, serializable data record; **canonical**
> means "byte-for-byte reproducible regardless of platform"; **contentHash** is
> the fingerprint used for change-detection and fork-deduplication.

## Research

- **Entity-model spec** (`docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md`):
  §3 inventories the current `DictatePrefs` config surface and its migration
  categories; §4 defines the entities (`§4.3` ProviderConfig, `§4.4` ApiCredential,
  `§4.5` ModelRef, `§4.6` Prompt, `§4.7` Profile) with an Envelope/Payload split;
  §4.8 the mirror enums + parity requirement; §5 `CanonicalJson` (an RFC-8785 subset),
  `contentHash`, the recompute-on-write invariant, and the `CatalogFileV3` codec;
  §7 the Room v11→v12 tables with Double-Enum CHECKs; §8 the Prefs→entity migration.
- **Repo doctrine reused:** ADR-0016 (typed DTOs + Konform validation as SSoT,
  protocol versioning) and the `SessionEntityMapper` / `SessionOriginWire`↔`SessionOrigin`
  pattern for wire-vs-domain enums; `docs/DATABASE-PATTERNS.md` "Double-Enum pattern"
  and "Denormalized Cache Columns" (the model for `contentHash`/`updatedAt`).
- **Existing constraints touched:** ADR-0024 (typed prompt-pill column — the Prompt
  entity must carry the same type field, not the old `[bracket]` convention);
  ADR-0012 (post-processing conversation — model/prompt resolution now flows through
  a Profile instead of loose prefs).
- **Concept / decisions:** `.../research/fragenkatalog.md` §F17 (what belongs in a
  Profile), §F22 (Android migrates immediately), §F23 (file export stays; v3 = peer
  wire format, one codec), §F24 (the unit is named "Profile"), §F27 (root-hash +
  per-entity contentHash, canonical serialization = v3), §F31 (`GATEWAY` reserved);
  `.../research/konzept-skizze.md` "Entitätenmodell".
- **Plan Decision Log** (`.../desktop-companion-v1.md` §3): D3 (entities per platform,
  serialization in `:shared`), D5.a (mirror enums, parity via `:app`), D5.b (D3 owns
  the companion entity tables), D4.7 (C2 is a hard migration, no coexistence flag,
  Prefs-backup export as rollback).

## Context

Configuration in `:app` is unstructured `SharedPreferences`: the transcription/completion
model, provider keys, style and system prompts, and ambiguity mode are independent
string slots with no grouping and no way to share a coherent set. The desktop-companion
program needs three things this flat model cannot give:

1. **Shareability** — a user must be able to publish "my rewording setup" to a peer,
   which requires a self-contained, versionable, hashable unit.
2. **A single format** — file export (F23) and peer sync (Block E) must use the *same*
   serialization, or the two drift.
3. **Cross-platform identity** — Android (Room) and the companion (SQLDelight) must
   agree, byte-for-byte, on what "the same profile" is, so a hash comparison is
   meaningful.

There is no shared entity today, and Room and SQLDelight cannot share table definitions
(D3). The format must therefore live above both persistence layers.

## Decision

Model configuration as **five entities defined once in `:shared`**, with a **canonical
serialization** and **contentHash** that doubles as the **v3** file/wire format.

1. **Entities (spec §4), Envelope + Payload split.** Every entity carries an
   **Envelope** (`id`, `contentHash`, `updatedAt`, `visibility`, `sourceRef`,
   `subscriptionMode`) and a **Payload** (the shareable content). The five:
   - **ProviderConfig** — a provider (`ProviderType`) + endpoint config; `ProviderKind`
     is `LOCAL | GATEWAY`, with `GATEWAY` **reserved** (F31) for the future server path.
   - **ApiCredential** — referenced by ProviderConfig; the secret itself lives only in
     the `SecretStore` (never in a column, never in the payload).
   - **ModelRef** — a concrete model (`ModelFunction = TRANSCRIPTION | COMPLETION`) plus
     default parameters.
   - **Prompt** — a rewording/system prompt carrying its **typed** kind (ADR-0024, not
     the old `[bracket]` string convention).
   - **Profile** (F24) — the user-facing bundle (F17): a transcription ModelRef, a
     completion ModelRef, an **ordered** list of prompt refs with `autoApply`, style/
     system prompt selection, an `ambiguityMode`, and completion parameter overrides.
     `is_active` is **not** a Profile field (it is not shareable and would poison the
     hash) — the active profile is a global pointer `Pref.ActiveProfileId`.

2. **Canonical serialization = the v3 format (spec §5, F27/F23).** `CanonicalJson`
   in `:shared` produces one deterministic byte form (a subset of RFC 8785: envelope
   fields stripped, object keys sorted, arrays order-preserved, minimal escaping,
   integers only, `encodeDefaults=true`, `explicitNulls=false`, compact UTF-8).
   `contentHash = SHA-256(canonicalBytes)` as lowercase hex. Because envelope fields
   are stripped, two entities with identical payload but different `id`/`visibility`/`sourceRef`
   share a contentHash — the fork-dedup / drift-detection property Block E needs. The
   same `CatalogFileV3` codec serves **both** file export (F23) and peer transfer —
   one codec, not two.

3. **Recompute-on-write invariant (spec §5.3).** `contentHash`/`updatedAt` are
   **denormalized cache columns** (`docs/DATABASE-PATTERNS.md`): recomputed from the
   current payload at every write path (create/edit/import/migration), never trusted
   from a file or peer; on import the delivered hash is recomputed and compared
   (integrity check). The write choke point is a `ConfigRepository`, mirroring
   `SessionManager`.

4. **Entities in `:shared`, tables per platform (D3).** `:shared` owns the DTOs +
   `CanonicalJson` + `contentHash` as the single source of truth for the format;
   Room (Android) and SQLDelight (companion) keep their **own** tables and map via thin
   mappers (`SessionEntityMapper` precedent). `CanonicalJson` lives as the **single**
   instance in `:shared` precisely so `encodeDefaults`/`explicitNulls` can never differ
   between platforms and drift the hash.

5. **Mirror enums, parity by test (D5.a).** `:shared` defines the wire enums
   (`ProviderType`, `ProviderKind`, `ModelFunction`, `AmbiguityModeValue`,
   `PromptSelectionMode`) itself; the behaviour-bearing domain enums stay in
   `:shared-ai`/`:app`; mappers + parity tests live in `:app` (which sees both) and
   are a **mandatory gate**. This is the ADR-0016 wire-vs-domain doctrine — drift is
   test-prevented, not module-coupled.

6. **Android migrates immediately, hard cut (F22 / D4.7).** C2 is a one-way
   Prefs→Room-entities migration with **no coexistence flag**; the rollback path is a
   Prefs-backup export taken before the migration. Room goes v11→v12 with new tables
   whose finite-set columns use the Double-Enum pattern (Kotlin enum + SQL CHECK).

### Scope of this Convention

Project-Wide because the entity model and its canonical format are a repository-wide
contract shared by both platforms and the peer protocol.

- **Applies to:** the five entities, the Envelope/Payload split, `CanonicalJson` +
  `contentHash` as the sole v3 format, the recompute-on-write invariant, the
  mirror-enum + parity-test rule, and the Double-Enum requirement for the new columns.
- **Exempt:** the *active-profile pointer* and other genuinely non-shareable
  per-device settings stay ordinary prefs. Platform table shapes are each platform's
  own business as long as they round-trip the canonical form.

## Alternatives Considered

1. **Keep flat `SharedPreferences`; add ad-hoc export.** No new model. Rejected: an
   ad-hoc export has no stable identity or hash, cannot dedup forks, and would force a
   second serialization for peer sync — exactly the drift F23's "one codec" rule forbids.
2. **Define entities as Room `@Entity`s and share those.** Rejected (D3): Room and
   SQLDelight cannot share entity classes, and coupling the format to Android's ORM
   would make the companion's persistence a second-class citizen. The format must live
   above both DBs.
3. **Hash the stored JSON as-is (no canonicalisation).** Simpler. Rejected: key order,
   default materialisation, and escaping differ across platforms and serializer
   settings, so the same logical entity would hash differently on phone and desktop —
   the hash would be worthless for sync. A canonical form is mandatory (F27).
4. **Move the domain enums (`AIProvider`, …) into `:shared` to avoid mirrors.**
   Rejected (D5.a): the domain enums carry behaviour (capabilities, base URLs,
   `forcesTurn`) that does not belong in the wire module, and the move would introduce
   the `:shared-ai`↔`:shared` coupling Block A avoids. Mirror + parity test is the repo
   pattern.
5. **Migrate Android lazily / behind a flag (coexistence).** Rejected (D4.7): two live
   config models doubles the surface and invites split-brain bugs; a hard cut with a
   Prefs-backup rollback is cleaner and testable.

## Consequences

**Positive:**
- A Profile is a first-class, shareable, hashable unit — file export and peer sync use
  the identical v3 codec, so the two can never drift.
- Cross-platform identity is exact: a contentHash means the same thing on phone and
  desktop, which is the foundation Block E's sync stands on.
- The entity model is the single choke point for config resolution (ADR-0012 now flows
  through a Profile), replacing scattered prefs reads.
- The Double-Enum + canonical-form rules are machine-enforced (CHECK constraints,
  parity tests), so finite-set drift and hash drift both fail the build.

**Negative:**
- A substantial new model to build and a hard Android migration to ship in v1 —
  more up-front work than an ad-hoc export.
- Envelope/Payload + canonicalisation add conceptual overhead every contributor
  touching config must learn.
- The mirror-enum approach means each finite set is declared twice (wire + domain),
  kept honest only by the parity test — a contributor who adds an enum value on one
  side and skips the other gets a red test, not a compiler error.

**Failure Modes:**
- **`encodeDefaults`/`explicitNulls` drift between platforms silently breaks sync.**
  If a second `CanonicalJson` instance (or a differently-configured `Json`) sneaks
  into `:app` or `:companion`, hashes diverge and Block E mis-detects every entity as
  changed. The single-instance-in-`:shared` rule is the guard; a per-platform copy is
  the footgun.
- **Trusting an imported `contentHash` instead of recomputing** would let a corrupt or
  malicious file/peer poison local identity. The recompute-on-write + compare-on-import
  invariant must never be shortcut.
- **Putting a secret in a payload column** (e.g. inlining an API key into ProviderConfig)
  would leak it into the hash and into any export/share. Credentials live **only** in the
  SecretStore and are referenced, never embedded.
- **Adding `is_active` (or any per-device field) to a Payload** would change the hash
  per device and destroy fork-dedup. Non-shareable state stays in the Envelope or in
  prefs.
- **Hard migration with no rollback taken** — if the Prefs-backup export is skipped, a
  failed C2 migration has no recovery. The backup is a mandatory pre-step, not optional.

## References

- **Related Plan:** [desktop-companion-v1](docs/plans/2026-07-19 - desktop-companion-v1/desktop-companion-v1.md)
  — §3 (F17/F22/F23/F24/F27/F31, D3, D5.a/D5.b, D4.7), §5 Block C. Motivates and is
  implemented by this ADR.
- **Spec:** `docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md`
  (§4 entities, §5 canonical/contentHash/v3, §7 Room v12, §8 migration).
- **Concept:** `.../research/fragenkatalog.md` §F17/§F22/§F23/§F24/§F27/§F31;
  `.../research/konzept-skizze.md` "Entitätenmodell".
- **Conventions:** `docs/DATABASE-PATTERNS.md` (Double-Enum, Denormalized Cache Columns).
- **Related ADRs:**
  - ADR-0016 — the wire-DTO + Konform-validation + versioning doctrine and the
    wire-vs-domain enum pattern this model reuses.
  - ADR-0024 — the typed prompt-pill column the Prompt entity carries (not `[bracket]`).
  - ADR-0012 — model/prompt resolution now flows through a Profile; a Decision-History
    note is added there at promotion.
  - `adr-secret-store` — ApiCredential secrets live in the SecretStore, referenced only.
  - `adr-peer-catalog` — consumes the v3 format + contentHash as the sync unit.

## Decision History

### 2026-07-20 — Initial proposal (plan-scoped)

**Trigger:** The desktop-companion program needs shareable, versionable configuration
(F17/F23/F27) and cross-platform identity for peer sync; the entity-model spec resolved
the entity shapes, the canonical format, and the Android migration.

**Before:** Configuration was flat `SharedPreferences` strings with no grouping,
identity, hash, or shareable unit; Room and SQLDelight had no common config format.

**After:** Five `:shared` entities (ProviderConfig / ApiCredential / ModelRef / Prompt /
Profile) with an Envelope/Payload split, one `CanonicalJson` + `contentHash` that is the
v3 file **and** wire format, recompute-on-write, per-platform tables via thin mappers,
mirror enums with `:app` parity tests, and an immediate hard Android migration
(v11→v12) with a Prefs-backup rollback.

**Reasoning:** A canonical, hashable entity model is the only shape that makes file
export and peer sync share one codec and gives phone/desktop a byte-exact common
identity. Entities live in `:shared` (above both DBs, D3); mirror enums + parity tests
reuse the existing wire-vs-domain doctrine instead of coupling modules; a hard migration
with a backup is cleaner than a coexistence flag.
