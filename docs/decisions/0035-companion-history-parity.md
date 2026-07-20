# ADR-0035: Companion History Parity — Full Session-Schema Parity in SQLDelight, `received_texts` Retirement, and the Companion DB as the Shared Session Archive

**Status:** Accepted
**Subsystem:** companion, data
**Date:** 2026-07-20
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0014 and ADR-0020.** ADR-0014 defined the in-keyboard
> history panel and its filter semantics on Android; ADR-0020 made the phone the
> authoritative instance for cursor-based session sync. This ADR **extends** both:
> the filter-definition equivalence now holds for desktop sessions too, and the
> companion's SQLDelight schema reaches full parity with Room so it can be the
> shared archive the sync writes into.

> **Plain-language summary.** The desktop companion needs to store dictation
> **sessions** — the same rich records the phone keeps (transcripts, post-processing
> turns, final text, status). Today the companion has only a thin `received_texts`
> table for dispatched snippets. This ADR brings the companion's session schema to
> **full parity** with the phone's Room schema (enforced by tests so the two can't
> drift), **retires** the old `received_texts` table by backfilling its data into
> the real `sessions` table and then dropping it, and establishes the companion DB
> as the **one shared archive**: phone-synced sessions and desktop-recorded sessions
> live together, separated by an `origin` marker. Jargon: **parity test** = a test
> asserting the two platforms' enum vocabularies and schema shapes match exactly;
> **backfill** = copy existing rows into the new shape during a migration, then drop
> the old table; **dispatch_state** = a 1:1 companion table holding the sync/dispatch
> bookkeeping the old `received_texts` used to carry.

## Research

- **Desktop-host spec** (`docs/plans/2026-07-19 - desktop-companion-v1/research/desktop-host.md`):
  §3.1 the current SQLDelight state vs the parity goal; §3.2 the enum vocabularies (SSoT
  for the CHECK constraints); §3.3 the `Companion.sq` table translation; §3.4 the
  `received_texts` retirement migration (backfill → `sessions` + `dispatch_state`, then
  DROP); §3.5 the SyncService/repo blast-radius; §3.6 the programmatic parity-test design;
  §14 D1/D2 (the D1a/D1b split rationale).
- **Extended ADRs:** ADR-0014 (in-keyboard history panel — the filter definitions whose
  equivalence now covers desktop sessions) and ADR-0020 (lazy cursor-based sync,
  phone-authoritative — the sync this schema must keep serving without assertion changes).
- **Regression anchor (D5.c):** five existing tests must stay green **without assertion
  changes** across the retirement — `SyncE2ETest`, `CompanionE2ETest`,
  `MultiConnectorE2ETest`, `TruncatedResponseE2ETest`, `SqlDelightHistoryRepositoryTest`
  (desktop-host §3.5) — the behaviour-neutrality proof.
- **Concept / decisions:** `.../research/fragenkatalog.md` §F15 (full Room-schema parity in
  SQLDelight, with parity tests), §F16 (companion DB = shared archive: phone sync + desktop
  sessions, origin-separated; peers never a dictation store); `.../research/bestandsaufnahme.md`
  §7 (history/DB inventory).
- **Plan Decision Log** (`.../desktop-companion-v1.md` §3): D5.c (D1a = SQLDelight parity +
  `received_texts` retirement + sync rebuild, as its own chunk **before** D1b capture/pipeline),
  D5.g (`received_texts` retirement confirmed as the plan decision, replacing §10 Gap 5),
  and the migration-number assignment D1a = `2.sqm`.

## Context

The companion persists almost nothing about a dictation: a thin `received_texts` table for
text dispatched from the phone. The desktop dictation host (ADR-0031) is
about to **record and post-process its own sessions**, which need the full session record
(transcriptions, conversation turns, `final_output_text`, status). And F16 wants **one** shared
archive: phone-synced sessions and desktop sessions together, not two schemas.

Two forces make this the highest-risk work in Block D:

1. **Parity must be exact.** Android (Room) and the companion (SQLDelight) cannot share tables
   (D3), so the companion schema is hand-translated — and any drift in an enum vocabulary or a
   column silently corrupts sync or the shared history view.
2. **`received_texts` is load-bearing today.** Existing sync and dispatch tests depend on it.
   Retiring it while keeping five behavioural tests green **without changing their assertions**
   is the proof that nothing regressed.

## Decision

Bring the companion's session schema to **full Room parity**, retire `received_texts` by
backfill, and make the companion DB the shared session archive — as a **dedicated chunk (D1a,
`2.sqm`) sequenced before capture/pipeline (D1b)**.

1. **Full session-schema parity (spec §3.1–3.3, F15).** `Companion.sq` gains the full session
   record (sessions + transcriptions + conversation turns/messages + final text + status),
   translating the Room schema table-for-table. Every finite-set column uses the **Double-Enum
   pattern** (Kotlin enum + SQL CHECK), with the enum vocabularies (§3.2) as the SSoT for both
   the CHECK constraints and the parity assertions.

2. **Parity tests are mandatory (spec §3.6, F15).** Programmatic tests assert the SQLDelight
   enum vocabularies and schema shape match Room's exactly. This is the same drift-prevention
   discipline ADR-0016 uses for wire-vs-domain enums, applied to the two persistence layers —
   a mismatch fails the build, it is not caught by convention.

3. **`received_texts` retirement by backfill (spec §3.4, D5.g).** The sync/dispatch bookkeeping
   `received_texts` carried moves into a 1:1 companion table `dispatch_state`; the SQLDelight
   migration **backfills** `received_texts` → `sessions` (+ `dispatch_state`), then **DROPs**
   `received_texts`. This is a retirement, not coexistence — one archive, not two. The
   behaviour-neutrality proof is the five existing tests (SyncE2ETest, CompanionE2ETest,
   MultiConnectorE2ETest, TruncatedResponseE2ETest, SqlDelightHistoryRepositoryTest) staying
   green **without assertion changes**.

4. **Companion DB = the shared archive (F16).** Phone-synced sessions and desktop-recorded
   sessions live in the **same** `sessions` table, separated by an `origin` marker — one
   history the user browses across both sources. **Peers are never a dictation store**
   (ADR-0034 F16); the archive is companion-local.

5. **Sync stays ADR-0020, filters stay ADR-0014 (extended).** The cursor-based, idempotent,
   phone-authoritative session sync (ADR-0020) keeps working against the parity schema; the
   history filter definitions (ADR-0014) now apply equivalently to desktop-origin sessions.
   Both ADRs get a Decision-History note at promotion.

6. **Own chunk, sequenced first (D5.c).** Because the retirement is the block's highest
   regression risk, D1a is a **separate chunk** (its own focused audit) landing **before** the
   D1b capture/pipeline work, and it owns migration `2.sqm`.

## Alternatives Considered

1. **A slimmer desktop-only session schema (no Room parity).** Less to translate. Rejected
   (F15/F16): without parity, phone→companion sync cannot round-trip the full record and the
   shared history view is lopsided; the two schemas would drift with every Room change. Full
   parity + parity tests is the sustainable choice.
2. **Keep `received_texts` alongside a new `sessions` table (coexistence).** No risky
   migration. Rejected (D5.g): two overlapping stores mean two code paths, split-brain history,
   and ambiguous ownership of dispatched text. A backfill-then-drop retirement leaves one
   archive.
3. **Do the retirement inside the capture/pipeline chunk (D1b).** Fewer chunks. Rejected
   (D5.c): bundling the highest-regression-risk migration with new feature code hides it from a
   focused audit and couples two independent risks. A dedicated D1a chunk isolates and audits it.
4. **Prove behaviour-neutrality by rewriting the affected tests.** Rejected: a test whose
   assertions were changed to pass proves nothing about neutrality. Keeping the five tests'
   assertions **unchanged** is the actual proof the retirement is behaviour-preserving.
5. **Store desktop sessions in a separate DB/table from synced ones.** Rejected (F16): the user
   wants one history across sources; an `origin` marker in one table is simpler than two stores
   and one union view.

## Consequences

**Positive:**
- The companion can store and browse full dictation sessions — desktop-recorded and phone-synced
  — in one origin-separated archive.
- Parity tests make Room↔SQLDelight drift a build failure, not a latent sync corruption.
- Retiring `received_texts` leaves a single, unambiguous store for dispatched and recorded text.
- Isolating the migration in its own chunk gives it a focused audit and decouples its risk from
  the capture/pipeline build.

**Negative:**
- Full parity is real translation work and an ongoing obligation — every future Room schema
  change must be mirrored in SQLDelight and re-checked by the parity test.
- A destructive backfill-then-drop migration is inherently higher-risk than an additive one;
  it must be exactly reversible-in-effect and proven by the five tests.
- Two persistence layers now encode the same schema, doubling the surface a schema change touches.

**Failure Modes:**
- **An enum vocabulary drift between Room and SQLDelight** silently corrupts sync or the shared
  view — the parity test is the only guard; disabling or weakening it is the footgun.
- **A backfill that loses or mis-maps a `received_texts` row** before the DROP is unrecoverable
  (the source table is gone); the migration must backfill completely and be validated before the
  drop, with the five tests green on unchanged assertions.
- **Changing a test's assertions to make the retirement "pass"** destroys the behaviour-neutrality
  proof; the five anchor tests must pass as-is.
- **A missing `origin` separation** would merge phone and desktop sessions indistinguishably,
  breaking filters (ADR-0014) and sync attribution (ADR-0020); `origin` is mandatory on every
  session row.
- **Treating the companion archive as reachable by peers** would violate F16; the archive is
  companion-local and never served over the catalog family.

## References

- **Related Plan:** [desktop-companion-v1](../plans/2026-07-19%20-%20desktop-companion-v1/desktop-companion-v1.md)
  — §3 (F15/F16, D5.c, D5.g, `2.sqm` assignment), §5 Block D (D1a). Motivates and is implemented
  by this ADR.
- **Spec:** `docs/plans/2026-07-19 - desktop-companion-v1/research/desktop-host.md`
  (§3.1–3.6 parity + retirement + parity tests, §14 D1/D2).
- **Concept:** `.../research/fragenkatalog.md` §F15/§F16; `.../research/bestandsaufnahme.md` §7.
- **Conventions:** `docs/DATABASE-PATTERNS.md` (Double-Enum pattern, SQLDelight parity section).
- **Related ADRs:**
  - ADR-0014 — the in-keyboard history filter semantics now applying to desktop-origin
    sessions (Decision-History note added there at promotion).
  - ADR-0020 — the cursor-based phone-authoritative session sync this parity schema keeps
    serving; note added there at promotion.
  - ADR-0031 — the D1b capture/pipeline host that records into this archive,
    sequenced after D1a.
  - ADR-0034 — the peer family that shares configuration but **never** dictations (F16).

## Decision History

### 2026-07-20 — Initial proposal (plan-scoped)

**Trigger:** The desktop dictation host needs to persist full sessions (F15) and the program
wants one shared archive across phone and desktop (F16); the desktop-host spec resolved the
parity schema, the `received_texts` retirement, and the parity-test design.

**Before:** The companion persisted only a thin `received_texts` table; there was no full session
record, no Room-parity schema, no parity test, and dispatched text lived apart from any
session archive.

**After:** Full Room-parity session schema in SQLDelight with mandatory parity tests; a
backfill-then-drop retirement of `received_texts` into `sessions` + a 1:1 `dispatch_state`
table; the companion DB as the origin-separated shared archive; ADR-0020 sync and ADR-0014
filters extended to desktop sessions — delivered as a dedicated chunk (D1a, `2.sqm`) sequenced
before capture/pipeline, with five existing tests green on unchanged assertions as the
behaviour-neutrality proof.

**Reasoning:** Full parity + parity tests is the only shape that lets phone↔companion sync
round-trip the full record and keeps the two schemas from drifting; a backfill-then-drop
retirement leaves one unambiguous archive; isolating the highest-risk migration in its own
chunk gives it a focused audit; and proving neutrality with unchanged test assertions is the
only honest proof.

### 2026-07-20 — Promoted and accepted

**Trigger:** Chunk F1 (Block F) of the desktop-companion-v1 plan — blocks A–E are
implemented; the plan-scoped draft is promoted to a numbered, accepted ADR before
plan archival (§2 criterion 9).

**Before:** Plan-scoped draft `adrs/adr-companion-history-parity.md` with an `NNNN` placeholder and
`Proposed (plan-scoped — pending promotion)` status; sibling ADRs referenced by slug.

**After:** `docs/decisions/0035-companion-history-parity.md`, Status **Accepted**, indexed in
`docs/decisions/README.md`; sibling cross-references resolved to their assigned ADR
numbers. The reciprocal history-parity notes were added to ADR-0014 and ADR-0020.

**Reasoning:** The decision is active in the codebase across the implemented blocks;
promotion makes it a binding, navigable ADR with bidirectional cross-links.
