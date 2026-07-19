# ADR-NNNN: Peer-Catalog Family — Pull-Only Configuration Sharing over the Existing Wire Stack, with Envelope Credential Delivery, Fork Semantics, and Discovery

**Status:** Proposed (plan-scoped — pending promotion)
**Scope:** Project-Wide
**Date:** 2026-07-20
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0016, ADR-0025, ADR-0017 and ADR-0020.** ADR-0016 owns the
> wire format (typed DTOs, Konform validation, additive versioning) and ADR-0025 the
> additive-endpoint convention — this family is built **on** them, not beside them.
> ADR-0017 owns roles/transport/pairing and ADR-0020 the phone-authoritative session
> sync — this ADR **extends** their authority model in a new, config-only direction,
> and is explicit that **dictations are excluded** (F16).

> **Plain-language summary.** This decision lets Dictate devices **share
> configuration** — profiles, prompts, models, and (optionally) the API keys behind
> them — with each other over the local Tailscale network. It works like subscribing
> to a feed: a "providing" peer publishes a **catalog** of what it offers; a
> "subscribing" peer **pulls** what it wants and keeps it in sync by comparing
> hashes. Sharing is **pull-only** (no peer can push into your device), a shared copy
> is **read-only** until you explicitly **fork** it to edit, secrets travel inside an
> encrypted **envelope** straight into the receiver's SecretStore, and a **headless
> peer** (a companion run with `--headless`) can act as an always-on hub. It reuses
> the exact same wire machinery the phone↔PC dispatch already uses. Jargon: a
> **peer** = another Dictate device addressable over Tailscale; **catalog** = the
> list of entities a peer offers; **rootHash** = one hash summarising the whole
> catalog so "did anything change?" is a single request; **fork** = detaching a
> subscribed copy so you can edit it, remembering where it came from.

## Research

- **Peer-catalog spec** (`docs/plans/2026-07-19 - desktop-companion-v1/research/peer-katalog.md`):
  §3 the wire family (`CatalogEntityKindWire`, `CatalogEntry`, `CatalogIndexResponse`
  with `rootHash`, `CatalogEntityResponse`, `CatalogCredentialResponse`; Konform
  validations that never put a `{value}` on `payload`/`secret`; the uniform
  `CATALOG_ENTITY_NOT_FOUND` 404 that hides which private entities exist; the pure-JVM
  `CatalogClient` reusing `DispatchTransport`/`Credentials`/`ProtocolCodec`); §4 the
  companion server routes + `CatalogService` + audited credential delivery; §5 the
  SQLDelight `peers`/`subscriptions`/`catalog_access_log` tables + entity-mirror +
  provenance columns; §6 the pull sync engine (rootHash short-circuit, per-entity
  fetch, verify-before-write with two hash checks); §7 tray + Android notification.
- **Reused wire doctrine:** ADR-0016 (typed DTOs + Konform-on-both-sides + additive,
  defaulted fields under `ignoreUnknownKeys` — the pattern behind `supportsCatalog`,
  the `UNKNOWN` enum landing zone, and additive DTOs) and ADR-0025 (additive endpoints
  over the shared stack); ADR-0017 (pairing model + bearer-secret auth reused for peer
  auth, F10; the client/server transport) and ADR-0023 (bind-address); ADR-0020 (the
  cursor-based idempotent sync + authoritative-instance pattern, here re-cast for
  config with a different authority direction).
- **Config identity foundation:** `adr-config-entity-model` — the canonical
  serialization + `contentHash` that make catalog diffing and fork-dedup possible; the
  `GATEWAY` reserved enum and v3 codec.
- **Concept / decisions:** `.../research/fragenkatalog.md` §F7/§F25 (providers =
  companions + optional headless hub-peer; Android is a pure consumer), §F8 (headless
  peer = `--headless` variant, not a separate module), §F9 (pull-only), §F10 (pairing
  reused for peer auth), §F12 (envelope encryption; offering peers may decrypt; TLS +
  SecretStore mandatory; no share-password), §F14 (fork + update hint: `sourceRef` +
  origin hash), §F16 (companion DB is the shared archive; peers are never dictate
  stores), §F26 (discovery: manual + QR **and** Tailscale-API enumeration, behind a
  port), §F27 (root-hash + per-entity contentHash), §F30 (peer = address + pairing
  credential; `peerId` public-key-ready), §F31 (`/v1/ai/*` namespace reserved),
  §F32 (one protocol — the catalog family on the existing wire stack), §F33 (offline
  peers tolerated, staleness shown); `.../research/konzept-skizze.md` §4.
- **Plan Decision Log** (`.../desktop-companion-v1.md` §3): D5.b (D3 lays the entity
  tables incl. provenance columns; E1 lays only `peers`/`subscriptions`/`catalog_access_log`;
  new E1→D3 edge; E1 = `4.sqm`).

## Context

The desktop-companion program's headline value is **sharing setups** — one user's
carefully-tuned rewording profile should reach a teammate's device without email
attachments. That needs a distribution mechanism over the local Tailnet. The
requirements (F7–F34) constrain it sharply: pull-only so no peer can inject into
another; the same wire stack as dispatch so there is one protocol to secure and version;
shared credentials delivered without a share-password but never zero-knowledge; a
read-only-then-fork edit model; and a headless always-on peer that is just the companion
in another mode, not a new codebase.

Crucially, this introduces a **new authority direction**. ADR-0020 made the *phone*
authoritative for session sync. Peer catalog sharing is authority over **configuration
entities** flowing the other way (a providing peer is authoritative for what it offers),
and it must be explicit that **dictation sessions are out of scope** (F16) — peers are
never a store for dictated text.

## Decision

Build a **peer-catalog family on the existing wire stack** (ADR-0016/0025), pull-only,
hash-diffed, with envelope credential delivery, fork semantics, discovery behind a port,
and a headless peer mode.

1. **Wire family, additive (spec §3; ADR-0016/0025).** New DTOs appended to the shared
   protocol: `CatalogEntry` (metadata only — id, `kind`, `contentHash`, `updatedAt`,
   label; never a payload, never a secret), `CatalogIndexResponse` (the whole offer +
   `rootHash`), `CatalogEntityResponse` (the canonical v3 payload of one non-credential
   entity), and `CatalogCredentialResponse` (the envelope-delivered secret). A separate
   `CatalogEntityKindWire` enum with an `UNKNOWN` landing zone (never dragged by a C1
   refactor, mirroring `SessionOriginWire`), a defaulted `supportsCatalog` health flag
   (older peers decode as "no support"), Konform validations that **never** put a
   `{value}` constraint on `payload`/`secret` (redaction), and a uniform
   `CATALOG_ENTITY_NOT_FOUND` 404 that does not leak which private entities exist. The
   `CatalogClient` is pure JVM, reused verbatim by both companion and Android consumers.

2. **Pull-only, hash-diffed (spec §6, F9/F27).** A subscriber pulls; no peer pushes. A
   single `GET /v1/catalog` returns the `rootHash` (SHA-256 over the sorted
   `id + contentHash` of all entries) — one request answers "did anything change at all?"
   before any per-entity fetch. Changed entities are fetched individually and
   **verified before write** with two hash checks: the delivered `contentHash` is
   recomputed from the canonical payload, and it must match the index entry
   (`adr-config-entity-model` recompute-on-import invariant).

3. **Read-only copies, explicit fork (F14/F29).** A subscribed entity is **read-only**;
   editing requires an explicit **fork** that detaches the copy and records provenance
   (`sourceRef` + origin hash), enabling an "upstream changed" update hint. Envelope
   `subscriptionMode` (`LOCAL` / subscribed) and `sourceRef` live on every entity
   (`adr-config-entity-model`).

4. **Envelope credential delivery (spec §3.2/§4.3, F12).** Secrets are **never** served
   on the entity route; `GET /v1/catalog/credential/{id}` returns the plaintext key over
   **TLS in transit**, reached only by an explicitly authorized call, and **every
   delivery writes an audit row** (`catalog_access_log`). The receiver puts the secret
   straight into its own **SecretStore** (`adr-secret-store`) — never a column. Offering
   peers **may** decrypt shared keys (not zero-knowledge, F12); TLS + SecretStore are
   mandatory; there is no separate share-password.

5. **Peer auth reuses pairing (F10/F30).** A peer is an address + a pairing credential;
   authentication reuses the ADR-0017 bearer-secret model. `peerId` is laid out
   public-key-ready (F30) for a future trust upgrade without a wire break.

6. **Discovery behind a port (F26).** Manual entry + QR **and** Tailscale-API enumeration
   sit behind a discovery port, so the enumeration source is swappable and the manual
   path always works. Offline/unreachable peers are tolerated silently with a staleness
   indicator (F33).

7. **Headless peer = `--headless` companion (F8/F25).** An always-on hub is the companion
   run with `--headless` — the same codebase, no new module — offering its catalog without
   a UI. Its file-fallback SecretStore (`adr-secret-store` §6.4) holds the shared keys.

8. **New authority direction, dictations excluded (F16, extends ADR-0017/0020).** Catalog
   authority flows from provider to subscriber for **configuration entities only**. The
   companion DB remains the shared **session** archive (phone sync + desktop sessions,
   origin-separated); **peers are never a dictation store** — dictated text does not travel
   the catalog family.

9. **`/v1/ai/*` namespace reserved (F31).** The protocol namespace for a future
   AI-gateway path is reserved now (alongside the `GATEWAY` `ProviderKind`), so the
   long-term server path needs no wire break — but nothing is built for it in v1.

10. **Schema ownership (D5.b).** D3 lays the entity mirror tables (incl. provenance
    columns, NULL until E2); E1 lays only `peers` / `subscriptions` / `catalog_access_log`
    (`4.sqm`). One migration owner per table surface; new E1→D3 dependency.

### Scope of this Convention

Project-Wide because it adds a repository-wide protocol family and an authority-direction
convention shared by both platforms.

- **Applies to:** the catalog wire DTOs and their additive-versioning rules; pull-only +
  hash-diff sync; the read-only-then-fork edit model; envelope credential delivery via the
  SecretStore with mandatory audit; peer auth via the pairing model; discovery behind a
  port; the reserved `/v1/ai/*` namespace; and the rule that **configuration** entities
  sync peer-to-peer while **dictations never do** (F16).
- **Exempt:** session sync stays ADR-0020 (phone-authoritative, dictation data) — it is a
  different axis and is not folded into the catalog family. Android is a **consumer only**
  (it may subscribe/fork but does not serve a catalog).

## Alternatives Considered

1. **Push-based distribution (a hub pushes updates to devices).** Rejected (F9): push lets
   a peer write into another device's config — a trust and correctness hazard — and needs
   per-device delivery state on the provider. Pull keeps every device in control of what it
   accepts and makes the provider stateless per subscriber.
2. **A separate protocol/stack for catalog sync.** Rejected (F32): a second stack means a
   second thing to secure, validate, and version, and it would drift from the dispatch
   protocol. The catalog family is additive DTOs + endpoints on the ADR-0016/0025 stack —
   one protocol to reason about.
3. **Zero-knowledge credential sharing (providers cannot read shared keys).** Rejected
   (F12): it requires per-recipient key wrapping and a distribution scheme far beyond v1;
   TLS-in-transit + SecretStore-at-rest with offering peers permitted to decrypt is the
   accepted trust model, with a mandatory audit row per delivery.
4. **A live reference model (subscribed entities update in place, always editable).**
   Rejected (F14/F29): silent in-place mutation of something the user may have tweaked is
   surprising and lossy. Read-only copies + explicit fork with a "upstream changed" hint
   keeps the user in control.
5. **A dedicated headless-hub module/service.** Rejected (F8): a `--headless` flag on the
   companion reuses the entire server, catalog, and SecretStore stack; a separate module
   would duplicate all of it.
6. **Let peers also archive dictations.** Rejected (F16): dictated text is sensitive and
   belongs only in the phone/companion session archive; the catalog family is config-only.

## Consequences

**Positive:**
- One protocol stack for dispatch and catalog — a single surface to secure, Konform-validate,
  and version; a schema change fails the build on both sides.
- Pull-only + rootHash makes sync cheap (one request to detect no-change) and keeps every
  device in control of what it accepts.
- Credentials reach a peer without a share-password, land straight in the SecretStore, and
  leave an audit trail on every delivery.
- The headless peer is "free" (a companion flag), giving an always-on hub with no new codebase.
- Reserving `/v1/ai/*` + `GATEWAY` keeps the long-term server path open without a v1 wire break.

**Negative:**
- A sizeable new protocol family, sync engine, and three new tables to build, validate, and
  maintain — the largest block of the program.
- The trust model is deliberately not zero-knowledge; a compromised providing peer can read
  the keys it offers. Accepted (F12) and made auditable, but a real limitation to document to
  users.
- A new authority *direction* (config peer-to-peer) coexists with the ADR-0020 phone-authoritative
  session sync — two authority models a reader must keep straight.

**Failure Modes:**
- **Trusting a delivered `contentHash` without recomputing** would let a corrupt or hostile
  peer poison a subscriber's config; the verify-before-write two-hash check is mandatory.
- **A `{value}` Konform constraint on `payload` or `secret`** would copy the key/payload into
  validation logs — the redaction rule forbids it; a naive validator addition is the footgun.
- **Serving a credential on the entity route, or persisting a received secret in a column**,
  leaks it; credentials have their own audited route and go only into the SecretStore.
- **A non-uniform 404 (distinguishing "unknown" from "not shared")** leaks which private
  entities exist — the single `CATALOG_ENTITY_NOT_FOUND` code is deliberate, parallel to the
  uniform 401.
- **Provenance columns treated as required before E2** — they are NULL until subscriptions
  exist (D5.b); a NOT NULL constraint or a non-null assumption breaks the D3 migration.
- **Dictation data leaking into a catalog entity** would violate F16; the entity kinds are
  config-only and a dictation must never be modelled as a catalog entry.
- **A subscriber editing a subscribed copy in place** (skipping fork) silently diverges from
  upstream and loses the update hint; edits must go through the explicit fork/detach path.

## References

- **Related Plan:** [desktop-companion-v1](docs/plans/2026-07-19 - desktop-companion-v1/desktop-companion-v1.md)
  — §3 (F7–F34, D5.b), §5 Block E. Motivates and is implemented by this ADR.
- **Spec:** `docs/plans/2026-07-19 - desktop-companion-v1/research/peer-katalog.md`
  (§3 wire family, §4 server, §5 schema, §6 sync engine, §7 notifications).
- **Concept:** `.../research/fragenkatalog.md` §F7–§F34; `.../research/konzept-skizze.md` §4.
- **Related ADRs:**
  - ADR-0016 — the wire-DTO + Konform + additive-versioning stack this family is built on.
  - ADR-0025 — the additive-endpoint convention over the shared stack.
  - ADR-0017 — pairing/bearer-secret auth reused for peers; a new authority direction extends
    its role model (Decision-History note added there at promotion).
  - ADR-0020 — the sync/authoritative-instance pattern re-cast for config; explicitly a
    different axis from (phone-authoritative) session sync, which stays as-is.
  - ADR-0023 — the companion bind-address the catalog server also uses.
  - `adr-config-entity-model` — the canonical form + contentHash + v3 codec + `GATEWAY` this
    family diffs and shares.
  - `adr-secret-store` — where delivered credentials land; the headless file-fallback backend.
  - `adr-companion-history-parity` — the session archive (F16) that peers are explicitly NOT.

## Decision History

### 2026-07-20 — Initial proposal (plan-scoped)

**Trigger:** The desktop-companion program's core value — sharing configuration between
devices (F7–F34) — required a distribution protocol; the peer-catalog spec resolved the wire
family, the pull/hash sync, credential delivery, discovery, and the headless peer.

**Before:** The wire stack (ADR-0016/0025) carried only phone↔PC dispatch and phone-authoritative
session sync (ADR-0020). There was no way to share profiles/prompts/models/credentials between
devices, and no headless hub.

**After:** A pull-only, hash-diffed peer-catalog family on the existing wire stack — metadata
index + `rootHash`, per-entity canonical payloads verified before write, audited envelope
credential delivery into the SecretStore, read-only-then-fork edit semantics with provenance,
peer auth via the pairing model, discovery behind a port (manual/QR + Tailscale enum), and a
`--headless` companion peer. A new config-only authority direction that extends ADR-0017/0020
while **excluding dictations** (F16), with `/v1/ai/*` + `GATEWAY` reserved for the future
server path. Schema split per D5.b (D3 entity tables, E1 = `4.sqm`).

**Reasoning:** Reusing one wire stack (F32) keeps a single protocol to secure and version;
pull-only (F9) keeps devices in control; hash-diffing (F27) makes sync cheap; envelope delivery
+ SecretStore + audit (F12) share keys safely without a share-password; fork-on-edit (F14)
keeps subscribers in control; a `--headless` flag (F8) gives a hub for free. Excluding
dictations (F16) and reserving the gateway namespace (F31) draw the scope lines the long-term
architecture needs.
