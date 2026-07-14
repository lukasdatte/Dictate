# ADR-0016: Wire Protocol — Typed DTOs, Konform Validation as Single Source of Truth, Protocol Versioning

**Status:** Accepted
**Subsystem:** protocol, architecture
**Scope:** Project-Wide
**Date:** 2026-07-14
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0012.** That ADR established the "single wire authority" pattern for the
> AI conversation layer (`StructuredResponseCodec`); this ADR applies the same one-door principle
> to the phone ↔ desktop-companion protocol. It also builds on ADR-0015, which owns the `shared/`
> JVM module these DTOs live in.

## Research

The binding requirement for the Windows-Dispatch package (plan §2.3) was a "Zod-equivalent for
Kotlin": *all app ↔ server communication fully typed; every payload validated against a defined
schema; one schema = single source of truth in `shared/`, used by both sides.* Four candidates
were evaluated (plan §2.3 table):

- **Konform `io.konform:konform-jvm:0.11.1`** (released 2025-03-31) — DSL-based
  (`Validation<T> { Request::text { minLength(1); maxLength(MAX) } }`), zero runtime dependencies,
  no reflection, no codegen/KSP. Multiplatform, so unproblematic on Android at jvmTarget 1.8 /
  minSdk 26. Its `ValidationResult` carries structured property paths that map 1:1 onto the wire
  error format. **Chosen.**
- **`dev.nesk.akkurate:0.11.0`** (2024-12-10) — more expressive but KSP-based (a second codegen
  plugin that must match `KSP 2.1.20-1.0.32` exactly, unverified), and its API is *explicitly*
  declared unstable ("breaking changes might happen on minor releases"). Rejected.
- **valiktor** (last commit 2021-12) — dead, JVM-only, never tested against Kotlin 2.x. Rejected.
- **JSON-Schema (e.g. networknt)** — a second schema language beside the Kotlin types → two
  truths instead of one, directly contradicting the SSoT requirement. Rejected.

The decision is grounded in built, green code under
`shared/src/main/kotlin/net/devemperor/dictate/shared/protocol/`:

- `ProtocolCodec.kt:50-90` — the single `object ProtocolCodec` with `decode()`/`encode()`; the
  `DecodeResult` sealed class (`ProtocolCodec.kt:18-22`) splits `Ok` / `Malformed` / `Invalid`.
- `Validations.kt:23-129` — one `Validation<T>` per DTO, co-located; the shared `supportedProtocol()`
  version rule at `Validations.kt:127-128`.
- `ProtocolVersion.kt:24-27` — `CURRENT = 1` and the strict `isSupported()` check.
- `ErrorEnvelope.kt:16-63` — the unified `ErrorEnvelope{code, message, details}` and `ErrorCode`
  enum; `Dtos.kt:22-152` — every `@Serializable data class` carrying `protocolVersion` first.
- Tests: `ProtocolCodecTest.kt:145-204` (Malformed vs Invalid-with-path), `ValidationsTest.kt`,
  and `ErrorEnvelopeRedactionTest.kt:23-76` (the `{value}` redaction guard).

## Context

Windows-Dispatch introduces a second process — the desktop companion — that speaks HTTP with the
Android app. Two independently deployed peers exchanging JSON is the classic setting for silent
protocol drift: a field renamed on one side, a value range enforced on one side only, an error
shape parsed by guessing status codes. The requirement was explicit that this must not happen: one
typed schema in `shared/`, used verbatim by both sides, with every payload validated against it.

`@Serializable` alone only enforces *types* and *required fields* — it cannot express value
ranges (text length, cursor monotonicity, enum whitelists, version acceptance). That value layer
is precisely the "Zod part" the requirement asks for, and it must live in the same place as the
types so the two cannot drift.

## Decision

Every payload that crosses the wire is a `@Serializable data class` in `shared/protocol/`
(`Dtos.kt`) **plus** a co-located `Validation<T>` (Konform 0.11.1) in the sibling `Validations.kt`.
kotlinx-serialization owns the **wire format** (types, required/optional, enum names — enforced by
the compiler); Konform owns the **value constraints** (lengths, ranges, formats, version). Together
they *are* the schema.

A single entry point — `ProtocolCodec` (`ProtocolCodec.kt:50`) — is the only door through which a
payload may enter or leave:

```kotlin
fun <T> decode(raw: String, serializer: KSerializer<T>, validation: Validation<T>): DecodeResult<T>
fun <T> encode(value: T, serializer: KSerializer<T>, validation: Validation<T>): String
```

`decode()` deserializes **and** validates; `encode()` validates **and** serializes. Client and
server both call exactly this — neither can skip validation, because there is no other door. This
mirrors the role `StructuredResponseCodec` plays in ADR-0012 ("the single wire authority").
Because encoding validates too, a payload is checked once by its sender and once by its receiver:
a bug on either side is caught on the side that owns it — that is where "Zod on both sides" is
actually paid for.

`decode()` returns a `DecodeResult<T>` sealed class (`ProtocolCodec.kt:18-22`) that keeps two
failure kinds apart, because they mean different things to the caller:

- **`Malformed(reason)`** — not even JSON, a missing required field, a wrong type, or an unknown
  enum value. The peer is not speaking our wire format; there is nothing to say back but "400,
  your wire format is broken" (`ProtocolCodecTest.kt:145-167`).
- **`Invalid(details)`** — well-formed JSON, but out of contract. It maps 1:1 onto
  `ErrorEnvelope(VALIDATION_FAILED, details)` and tells the peer *exactly* which property is wrong.
  Konform's property path (`.items[3].sessionId`) is normalised by dropping the leading dot
  (`ProtocolCodec.kt:88-89`), so a bad row in a 200-row sync page is pinpointed rather than sinking
  the page anonymously (`ProtocolCodecTest.kt:189-204`).

On the send side, an outgoing payload that violates its own contract throws
`ProtocolViolationException` carrying the same `details` (`ProtocolCodec.kt:32-34`) — a send-side
bug surfaces where it is born, not as a puzzling 400 on the far side.

**Protocol versioning.** `ProtocolVersion.CURRENT = 1` (`ProtocolVersion.kt:24`) is carried as the
first field of every request and response DTO (defaulted so a caller cannot forget it). The server
checks it **first** — before auth, before validation — so an outdated peer gets a comprehensible
`PROTOCOL_VERSION_UNSUPPORTED` (HTTP 400) instead of a puzzling "validation failed". V1 is strict:
`isSupported(v) = (v == CURRENT)` (`ProtocolVersion.kt:27`). The bump rule is asymmetric and
documented at `ProtocolVersion.kt:17-23`: adding an **optional** field with a default does NOT bump
the version (the codec decodes with `ignoreUnknownKeys = true`, so an older peer silently skips it);
removing a field, renaming one, or changing its meaning DOES. For the one bodyless request
(`GET /v1/sync/cursor`) the version travels in the `X-Dictate-Protocol` header (`Endpoints.kt:24`).

**Unified error shape.** One `ErrorEnvelope{protocolVersion, code, message, details}`
(`ErrorEnvelope.kt:50-63`) is the format for **every** non-2xx response of both sides. `ErrorCode`
is a closed enum (`ErrorEnvelope.kt:16-41`) covering the full status surface (version, validation,
auth, pairing-token lifecycle, insertion failure, internal). One shape, one classifier — that is
what lets the client's `DispatchError` be an exhaustive `sealed class` instead of a pile of
status-code guesses. The `errorEnvelope` validation is *deliberately empty* (`Validations.kt:117`):
an error envelope is the peer complaining, and we must always be able to read the complaint —
including "your protocol version is unsupported", which by definition arrives from a peer whose
version we do not accept.

### Scope of this Convention

**Applies to:** every payload exchanged over the phone ↔ companion HTTP protocol. Every such DTO
lives in `shared/protocol/Dtos.kt` (or `ErrorEnvelope.kt`), has a co-located `Validation<T>` in
`Validations.kt`, and is read/written *only* through `ProtocolCodec`. No direct
`Json.decodeFromString` / `encodeToString` call for a protocol payload anywhere in `:app` or
`:companion`.

**Exempt:** internal serialization unrelated to this wire protocol (Room, preferences,
`StructuredResponseCodec`'s AI-conversation payloads, which have their own single-codec authority
under ADR-0012). This ADR governs the Windows-Dispatch wire, not all serialization in the project.

## Alternatives Considered

1. **Akkurate for validation.** More expressive DSL, but KSP-based — a second codegen plugin that
   must be kept version-locked to the exact KSP release, in a `shared/` module that already avoids
   codegen on purpose (ADR-0015). Its API is self-declared unstable, unacceptable for a module two
   apps depend on. Rejected in favour of Konform's dependency-free, reflection-free, codegen-free
   DSL.

2. **valiktor.** Dead since 2021, JVM-only, Spring-oriented, never tested against Kotlin 2.x.
   Rejected outright.

3. **kotlinx.serialization alone with hand-written validation.** `@Serializable` enforces types
   and required fields but nothing about *values* (lengths, formats, enum whitelists, cursor
   monotonicity, version acceptance). Hand-rolling that per DTO is exactly the scattered,
   drift-prone validation the requirement rules out. Rejected as the *sole* solution — the
   combination (serialization owns the shape, Konform owns the values) is the answer.

4. **JSON-Schema (e.g. networknt).** A declarative schema alongside the Kotlin types. Rejected
   because it introduces a **second** schema language beside the Kotlin types: two sources of truth
   that must be kept in lockstep by hand — the precise failure mode the SSoT requirement exists to
   prevent. Keeping the schema *in* the Kotlin types keeps it singular.

5. **Two codecs, or free-hand `Json` calls per call site.** Letting each side deserialize where it
   is convenient. Rejected: it is impossible to prove that every path validates. The single
   `ProtocolCodec` door makes bypass structurally impossible, at the cost of routing every payload
   through one object.

## Consequences

**Positive:**
- **Validation is impossible to bypass.** There is exactly one door; both sides pass through it,
  and it validates on the way in and the way out. A malformed or out-of-contract payload cannot
  reach business logic on either side.
- **Wire-version safety by construction.** Every payload self-declares its version; the server
  rejects an unspeakable peer with a comprehensible error before it can misinterpret anything, and
  the additive-field rule lets the protocol grow without a bump.
- **One schema, verified by the compiler and the tests.** The types and their constraints live in
  one place in `shared/`, shared verbatim (ADR-0015); no second schema language to keep in sync.
- **Structured, greppable errors.** `Invalid` carries property paths (`items[3].sessionId`) that
  map 1:1 onto `ErrorEnvelope.details`, so a peer learns exactly what is wrong — including which
  row of a batch — instead of a status-code guess.
- **Send-side bugs surface at the source** via `ProtocolViolationException`, not as a remote 400.

**Negative:**
- **Two artefacts per payload.** Every DTO needs a hand-written `Validation<T>` next to it. That
  is one more file section to touch on every protocol change — the price for keeping the value
  layer explicit and co-located rather than reflected or generated.
- **Konform pins the library ceiling.** Like the rest of the `shared/` stack (ADR-0015), the choice
  ties us to the Konform line built for Kotlin ≤ 2.1.20; a Kotlin bump is a cross-cutting follow-up.
- **Every call site must pass the serializer and validation explicitly.** `ProtocolCodec.decode`
  takes `(raw, serializer, validation)` — three arguments a caller must match up correctly.

**Failure Modes:**
- **A DTO added *without* a co-located `Validation<T>` silently skips value validation.** Nothing
  in the type system forces the pairing: a new `@Serializable` class compiles and round-trips fine
  through `ProtocolCodec` with an empty (or absent) validation, and the value constraints are
  simply never checked. The guard is convention + the codec's signature (it *requires* a
  `Validation<T>` argument, so a caller cannot decode without *choosing* one) + the per-DTO
  round-trip and value-violation tests in `ProtocolCodecTest`/`ValidationsTest`. When adding a
  wire type: add its `Validation<T>` in `Validations.kt` and a round-trip + at-least-one-violation
  test in the same commit. A deliberately empty validation (like `errorEnvelope`,
  `Validations.kt:117`) must carry a comment saying *why* it is empty, so "empty" reads as a
  decision, not an omission.
- **`{value}` interpolation can leak payload into logs.** Konform interpolates `{value}` in a
  constraint hint with the validated value itself. On an Int version field that is harmless
  (`supportedProtocol()` uses it, `Validations.kt:128`); on a payload-bearing field
  (`text`, `deviceSecret`, `pairingToken`) it would copy the dictated text or a secret into the
  error message, and from there into *both* sides' logs (the envelope is logged on both sides).
  Never put `{value}` in a constraint on a payload field. `ErrorEnvelopeRedactionTest.kt:23-76`
  pins this — a message may name the *limit* ("at most 100000 characters") but never the *value*.
- **An unknown enum value decodes as `Malformed`, not `Invalid`.** kotlinx-serialization throws on
  an unrecognised enum constant, so it is caught in the deserialize `catch` and reported as a
  broken wire format, not a validation failure with a path (`ProtocolCodecTest.kt:161-167`). A peer
  sending a future enum constant therefore gets "400 malformed", which is intended (an unknown
  variant *is* an unspeakable wire) but is worth knowing when debugging.

## References

- **Related Plan:** Windows-Dispatch plan (`tmp/plan-windows-dispatch.md` §2.3, §3 ADR-0016 row —
  pending archival to `docs/plans/`). The plan motivated and this package implements the ADR
  (bidirectional).
- **Related ADRs:**
  - ADR-0015 — Companion Monorepo Topology; owns the `shared/` (JVM) module these DTOs and the
    codec live in.
  - ADR-0017 — Client/Server roles + transport; uses `ProtocolCodec` and `ErrorEnvelope` over HTTP
    and defines the HTTP-response-as-delivery-confirmation contract that `DispatchResponse` carries.
  - ADR-0020 — Lazy-Sync; its `SyncCursor` / `SessionUpsert` / `SyncRequest` DTOs follow this
    convention (typed DTO + co-located validation + codec).
  - ADR-0012 — Post-Processing Conversation; `StructuredResponseCodec` plays the analogous
    single-codec "one wire authority" role for the AI conversation layer.
- **Implementation:** `shared/src/main/kotlin/net/devemperor/dictate/shared/protocol/` —
  `Dtos.kt`, `Validations.kt`, `ProtocolCodec.kt`, `ProtocolVersion.kt`, `ErrorEnvelope.kt`,
  `Endpoints.kt`.
- **Test suite:** `shared/src/test/kotlin/net/devemperor/dictate/shared/protocol/` —
  `ProtocolCodecTest.kt`, `ValidationsTest.kt`, `ErrorEnvelopeRedactionTest.kt`.

## Decision History

### 2026-07-14 — Initial proposal

**Trigger:** The Windows-Dispatch package introduced a second process (the desktop companion)
speaking HTTP with the app, and the binding requirement demanded a fully typed protocol validated
against one schema shared by both sides — the "Zod for Kotlin" ask (plan §2.3).

**Before:** No cross-process wire protocol existed. The only comparable pattern was ADR-0012's
`StructuredResponseCodec` for the AI conversation layer; there was no typed, validated, versioned
protocol between the phone and any external peer.

**After:** Every wire payload is a `@Serializable data class` in `shared/protocol/` plus a
co-located `Validation<T>` (Konform 0.11.1). A single `ProtocolCodec.decode`/`encode` deserializes
and validates in both directions; client and server call only it, so validation cannot be bypassed.
`PROTOCOL_VERSION = 1` is carried in every DTO and checked first; a major mismatch yields
`400 PROTOCOL_VERSION_UNSUPPORTED`. All errors on both sides use one `ErrorEnvelope{code, message,
details}`.

**Reasoning:** Konform beat Akkurate (KSP codegen + self-declared unstable API) and valiktor
(dead); JSON-Schema was rejected because a second schema language beside the Kotlin types breaks
the single-source-of-truth requirement. kotlinx-serialization owns the shape and Konform owns the
values, both in one file next to the DTO, with a single codec as the only door — the same
"one wire authority" shape ADR-0012 proved for the conversation layer, now applied to the
phone ↔ companion protocol.
