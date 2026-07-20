# ADR-0020: Lazy-Sync — Cursor-Based, Idempotent Delta Upserts with the Phone as the Authoritative Instance

**Status:** Accepted
**Subsystem:** protocol, data
**Date:** 2026-07-14
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0016 and ADR-0017.** ADR-0016 owns the `SessionUpsert`/`SyncCursor`
> DTOs and the validating `ProtocolCodec`; ADR-0017 owns the `/v1/sync` endpoints and their
> device-secret auth. This ADR adds the *replication algorithm* — the cursor watermark, the
> paging loop, and the phone-authoritative model — on top of those two.

## Research

The decision was reached during the windows-dispatch plan's design phase and is grounded in the
code that was built for it (Blocks 1–3, green):

- **The cursor is a lexicographic pair, not a timestamp.** `shared/.../sync/Cursor.kt:18-24`
  implements `object Cursor : Comparator<SyncCursor>` over `(lastCreatedAt, lastSessionId)`. The
  header (`Cursor.kt:11-17`) records the load-bearing reason: the order has to be **total**, and
  `createdAt` alone is not — two sessions can be born in the same millisecond — so the unique
  session id breaks the tie. Paging over a merely partial order silently skips or repeats rows at
  a page boundary.
- **The server holds the cursor; every run begins by asking for it.** `SyncClient.sync()`
  (`shared/.../sync/SyncClient.kt:74-77`) fetches `client.cursor()` first, then pages. The header
  (`SyncClient.kt:46-48`) spells out why: if the phone remembered its own high-water mark, a
  companion that came back with a wiped database would be invisible. One round trip per run makes
  the sync self-healing.
- **The paging loop and its stall guard.** `SyncClient.kt:80-106` pulls a page via
  `source.sessionsAfter(cursor, batchSize)`, posts it, advances to the server's returned watermark,
  and stops when a page is shorter than the limit. Two guards live here: a server whose cursor does
  not advance yields `SyncOutcome.Stalled` (`:93-97`) rather than looping for ever, and a per-run
  `maxBatches` cap (`:62`, default 20 × 200 = 4000 rows) keeps a freshly paired PC from holding the
  executor for minutes.
- **The full-history scope with its privacy consequence is written into the port contract.**
  `shared/.../sync/SyncSource.kt:26-30` documents that `sessionsAfter` returns **every** completed
  session with text, *including ones never dispatched to the PC* — "so every dictation ends up as a
  plaintext copy on the PC, not only the ones the user actively sent." The SQL that realises the
  filter is the plan's §2.3 query (`WHERE status = 'COMPLETED' AND final_output_text IS NOT NULL
  AND origin != 'REVIEW_REFINEMENT'`), matching `SessionDao.pagedHistoryPanel()` (ADR-0014 §4).
- **Two triggers, both fire-and-forget.** After every successful dispatch inside the coordinator
  (`app/.../windows/WindowsDispatchCoordinator.kt:88` — `executor.execute { service.sync(target) }
  // fire & forget`) and at service start (`app/.../core/DictatePipelineService.kt:852-871`, the
  block commented "App-start sync trigger (ADR-0020)").

Test coverage that pins the algorithm: `shared/.../sync/SyncClientTest.kt` (paging, stall, cap,
partial-failure outcomes), `companion/.../server/SyncE2ETest.kt` (round-trip against the real Ktor
server), `app/.../windows/AndroidSyncSourceTest.kt` (the same-millisecond cursor boundary — two
sessions with identical `created_at` each delivered exactly once).

## Context

The desktop companion needs a copy of the phone's dictation history so it can show past results
locally. Dispatch (ADR-0019) delivers the *live* text of a single send; it does not populate an
archive, and it says nothing about sessions dictated while the PC was offline or about the pre-pair
backlog. Something has to replicate the Room history to the PC.

Three constraints shaped the design:

1. **Sync must never endanger a dispatch.** The whole point of Windows-Dispatch is getting a
   freshly dictated text onto the PC; a replication mechanism that could fail *the send* by being on
   its critical path would be a regression. Sync therefore has to be strictly best-effort.
2. **The transport is one-directional and stateless per ADR-0017** — the companion is the only
   server and the HTTP response is the only confirmation channel. There is no back-channel over
   which the PC could push its state to the phone between runs.
3. **A companion can lose its database** (reinstall, disk wipe, new machine) and must recover
   without the user re-pairing or manually re-pushing history.

## Decision

**The phone lazily pushes cursor-based, idempotent delta upserts to the PC; the phone is the single
authoritative instance and the PC is a derived archive.**

Concretely:

- **Watermark.** On `GET /v1/sync/cursor` (`Endpoints.SYNC_CURSOR`) the PC reports how far it knows
  the history as `Cursor(lastCreatedAt, lastSessionId)` — a **lexicographic pair**, because
  `created_at` alone is not unique. `null` means "the PC has nothing".
- **Delta selection.** The phone selects
  `WHERE (created_at, id) > cursor ORDER BY created_at, id LIMIT 200` and posts pages of
  `SessionUpsert`s to `POST /v1/sync` (`Endpoints.SYNC`, `MAX_SYNC_BATCH = 200`) until a page is
  shorter than the limit (the last page) or the per-run batch cap is reached.
- **Idempotency.** Upserts key on `session_id` (the server-side primary key). A repeated or aborted
  sync is harmless — re-sending a page the server already has changes nothing.
- **Phone authority.** The server **never** sends changes back. Its cursor is a pure receive
  receipt, and on conflict the phone's row simply overwrites the PC's. The `SyncSource` port is
  one-way by construction (`SyncSource.kt:12-14`).
- **Triggers.** After **every** successful dispatch (in `WindowsDispatchCoordinator` — so all three
  dispatch producers of ADR-0019 inherit it) and at app start
  (`DictatePipelineService.onCreate`). Both are fire-and-forget on a background executor / IO
  dispatcher.
- **"Lazy" rationale.** Sync is never on the dispatch critical path. It may fail without losing a
  text — the dispatched text is already on the PC via `/v1/dispatch`; sync only fills the *archive*.
  This is why `SyncClient.sync()` returns a `SyncOutcome` instead of throwing.

### Scope of the sync — deliberate, and its privacy consequence

The sync mirrors the **entire** Room history to the PC, **including sessions that were never
dispatched** (`dispatched = 0`). This is intentional: the design goal is "the PC holds a derived
copy of the Room history", so a user browsing the companion sees their whole dictation history, not
only the fraction they actively pushed.

This carries an explicit **privacy implication that must be a decision, not a surprise: every
dictation result the user has ever produced lands as a plaintext copy on the PC** — the transcript
of a dictation into a private note is replicated exactly like one the user deliberately sent to
Windows. The transport is encrypted (Tailscale/WireGuard, ADR-0017) and reaches only paired
devices, but at rest on the PC the archive is plaintext. A user who pairs a companion is opting the
whole history into that copy.

**Excluded from the sync** (matching the `pagedHistoryPanel` filter, ADR-0014 §4):

- `REVIEW_REFINEMENT` carriers — internal helper sessions of the post-processing conversation, not
  dictation results.
- Sessions that are not `COMPLETED` or that have no `final_output_text` — an empty or failed run has
  nothing to archive.

**Deletions do not propagate in V1.** Deleting a session on the phone leaves its copy on the PC
untouched — the PC is an **archive, not a mirror**. A later `deleted_at` field would be an additive
change (a new nullable column and a filter clause, no `PROTOCOL_VERSION` bump per ADR-0016) and is
deferred to a follow-up.

## Alternatives Considered

1. **Phone remembers its own high-water mark.** The phone stores "last synced cursor" locally and
   never asks the PC. Rejected: a companion that lost its database would be invisible to the phone —
   the phone would believe everything was already synced and never re-push. Asking the PC "how far
   do you know me?" at the start of every run costs one round trip and makes recovery automatic
   (`SyncClient.kt:46-48`).

2. **Single-column `created_at` cursor.** Simpler watermark, one field. Rejected: `created_at` is
   not unique — two sessions in the same millisecond straddle a page boundary and one is silently
   skipped or the page repeats for ever. The `(created_at, id)` pair is the minimal *total* order
   (`Cursor.kt:11-17`).

3. **Bidirectional sync / conflict resolution.** Let the PC edit or delete and reconcile both ways.
   Rejected as large and unnecessary: the phone is the source of truth for dictation, the PC is a
   read-only archive. A merge protocol would add a back-channel (which ADR-0017 deliberately does
   not have) and conflict semantics for a case that does not exist — nothing on the PC authors
   history.

4. **Sync only dispatched sessions.** Push a session to the archive only when it was actually sent
   to Windows (`dispatched = 1`). Rejected on product grounds: the companion would then show a
   partial, confusing history. The privacy cost of full-history sync was instead made explicit (see
   Scope) so it is an accepted decision rather than a hidden default.

5. **Eager / transactional sync on the dispatch path.** Replicate synchronously as part of each
   dispatch so the archive is always current. Rejected: it would put replication on the critical
   path — a slow or broken archive write could fail or delay a send whose text already reached the
   PC. Lazy, best-effort sync keeps dispatch and archiving independent.

## Consequences

**Positive:**
- Sync is decoupled from dispatch — a replication failure can never devalue a delivered text
  (`SyncClient` returns an outcome, never throws).
- Self-healing: because the PC owns the cursor, a wiped or replaced companion re-receives the whole
  history automatically on the next trigger, with no user action.
- Restart-safe by construction: idempotent `session_id` upserts mean a page sent twice is a no-op,
  so an interrupted run needs no repair — it just resumes.
- The two triggers cover both the steady state (after each dispatch) and the backlog / offline gap
  (at app start), without a scheduler or background job.
- The excluded-set is the *same* filter as the in-keyboard history panel (ADR-0014), so what the PC
  archives and what the phone shows as history stay definitionally aligned.

**Negative:**
- **Full-history plaintext copy on the PC.** Every dictation ever made — including never-sent,
  private ones — is replicated as plaintext at rest on the paired PC. Accepted deliberately and
  spelled out so pairing is an informed choice; the transport is encrypted but the archive is not.
- **No deletion propagation in V1.** A session deleted on the phone lingers on the PC. The user's
  mental model ("I deleted it") diverges from the archive until the follow-up `deleted_at` ships.
- **Per-run page cap can leave the PC temporarily behind.** A very large backlog (> `maxBatches` ×
  200) is not caught up in a single run; it converges over subsequent triggers. Chosen so a fresh
  pair against a 10 000-session history cannot hold the executor for minutes.

**Failure Modes:**
- **Abort mid-batch.** If the network drops between pages, the cursor rests on the last
  *acknowledged* page (`SyncOutcome.Partial`, `SyncClient.kt:84-86`). This is safe: the next trigger
  fetches that cursor and resumes exactly there — no gap, no duplicate archived row.
- **Edit-after-sync silently overwrites the PC.** A session edited on the phone after it was synced
  will overwrite the PC's copy on the next run (same `session_id`, phone authoritative). Intended,
  but an implementer must not expect the PC copy to be immutable — the phone always wins.
- **Server acknowledges but its cursor does not advance.** A broken or half-implemented companion
  could accept a page yet return an unchanged watermark; paging on would re-send the same page for
  ever. `SyncClient` detects this and stops with `SyncOutcome.Stalled` rather than masquerading as a
  hit page cap (`SyncClient.kt:89-97`). An implementer adding a new server must actually advance the
  cursor or every sync will halt after one page.
- **Missing `(created_at, id)` index.** On a large history the paging query without a composite
  index degrades to a full table scan per page. `created_at` indexed with `id` as the PK is
  sufficient; a new server or a schema change must preserve that ordering support (plan §2.3
  index-check note).

## References

- **Related Plan:** windows-dispatch plan (pending archival) — `tmp/plan-windows-dispatch.md`
  §"ADR-0020" (line 487) and §"Voll-History-Sync — Privacy-Implikation" (line 939);
  `tmp/plan-windows-dispatch-3-android.md` §2.3 (sync query + `AndroidSyncSource`). The plan's
  `## References` reciprocates this link.
- **Related ADRs:**
  - ADR-0016 — Wire Protocol: owns the `SessionUpsert`/`SyncCursor` DTOs and the validating
    `ProtocolCodec` this ADR's payloads flow through; the `deleted_at` follow-up is additive under
    its versioning rule.
  - ADR-0017 — Client/Server roles & transport: owns the `/v1/sync` and `/v1/sync/cursor` endpoints,
    their device-secret auth, and the one-directional, response-only transport this ADR relies on.
  - ADR-0019 — Auto-Send dispatch primitive: the after-dispatch sync trigger lives in
    `WindowsDispatchCoordinator`, so all three dispatch producers inherit it; the app-start trigger
    is the second, independent one.
  - ADR-0014 — In-keyboard history panel: the `REVIEW_REFINEMENT` carrier exclusion and the
    `COMPLETED`/text filter are shared verbatim with `SessionDao.pagedHistoryPanel()` (§4).
  - ADR-0035 — Companion History Parity: this cursor-based, phone-authoritative sync keeps
    working unchanged against the companion's full Room-parity SQLDelight schema, and the
    `received_texts` retirement preserves it (five sync/dispatch tests green on unchanged
    assertions); see the 2026-07-20 Decision-History entry.
- **Implementation:** `shared/src/main/kotlin/net/devemperor/dictate/shared/sync/` (`SyncClient.kt`,
  `Cursor.kt`, `SyncSource.kt`); `app/src/main/java/net/devemperor/dictate/windows/AndroidSyncSource.kt`;
  `app/.../core/DictatePipelineService.kt:852-871` (app-start trigger);
  `app/.../windows/WindowsDispatchCoordinator.kt:88` (after-dispatch trigger).
- **Test suites:** `shared/src/test/kotlin/net/devemperor/dictate/shared/sync/SyncClientTest.kt`,
  `companion/src/test/kotlin/net/devemperor/dictate/companion/server/SyncE2ETest.kt`,
  `app/src/test/java/net/devemperor/dictate/windows/AndroidSyncSourceTest.kt`.

## Decision History

### 2026-07-14 — Initial proposal

**Trigger:** The windows-dispatch plan needed a way to give the desktop companion a copy of the
phone's dictation history — dispatch (ADR-0019) delivers a single live text but populates no
archive, and the transport (ADR-0017) has no back-channel. The design phase had to settle how
history replicates, how a companion recovers from a lost database, and whether *all* history or only
dispatched sessions crosses to the PC.

**Before:** No replication mechanism existed. The PC received only the text of an individual
dispatch via `/v1/dispatch`; there was no notion of a synced archive, no cursor, and no stated
position on the privacy scope of a full-history copy.

**After:** Lazy, cursor-based, idempotent delta upserts with the phone as the single authoritative
instance. The PC owns a `(lastCreatedAt, lastSessionId)` watermark; the phone pages
`WHERE (created_at, id) > cursor LIMIT 200` and pushes `SessionUpsert`s keyed on `session_id` until
level. Two fire-and-forget triggers (after each dispatch, at app start). Scope is the **entire**
Room history including never-dispatched sessions — with the plaintext-copy privacy implication
written out explicitly — excluding `REVIEW_REFINEMENT` carriers and non-`COMPLETED`/textless
sessions. Deletions do not propagate in V1 (PC = archive, not mirror); a `deleted_at` field is a
deferred additive follow-up.

**Reasoning:** Keeping sync off the dispatch critical path (lazy, best-effort) protects the primary
feature — a delivered text must never be endangered by a replication failure. Making the PC own the
cursor makes recovery from a wiped companion automatic, which a phone-held high-water mark could not.
The `(created_at, id)` pair is the minimal total order that survives same-millisecond page
boundaries. Full-history scope serves the product goal (a complete archive on the PC), and its
privacy cost was made an explicit, accepted decision rather than a silent default. Idempotent
`session_id` upserts make interrupted runs self-resuming without any repair protocol.

### 2026-07-20 — Sync serves the full-parity companion schema (ADR-0035)

**Trigger:** The desktop-companion-v1 plan (Block D, chunk D1a) brought the companion
SQLDelight schema to full Room parity and retired `received_texts`.

**Before:** The companion persisted a thin schema; this ADR's cursor-based,
phone-authoritative sync wrote into it.

**After:** ADR-0035 gives the companion a full Room-parity session schema (with parity
tests) and retires `received_texts` by backfill; this ADR's sync keeps working unchanged
against the parity schema — the five existing sync/dispatch tests stay green without
assertion changes.

**Reasoning:** Full parity lets phone↔companion sync round-trip the complete record; the
sync contract of this ADR is preserved. See ADR-0035.
