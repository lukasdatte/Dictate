# D1a — Self-Fix (fresh eyes, diff-based)

**Chunk:** D1a · **Agent:** SELF-FIX · **Timestamp:** 2026-07-20T00:40:00+02:00
**Wave commit reviewed:** `de6a60ddcb116ed02f3e7a1c45b23cc1b21f38bb`

## Verdict

One inline fix applied (stale comment introduced by this chunk). Otherwise the implementation is
correct, faithful to spec §3.2–§3.6, DRY/well-documented, and fully covered by green tests. Parity
references verified at their source; behaviour-neutrality proof holds.

## What I reviewed (three lenses)

**Plan correctness — all requirements present.**
- §3.2 Enum vocabularies: all eight Double-Enum columns modelled `TEXT AS <enum> + CHECK` with the
  exact Room `.name` sets. `last_error_type` correctly typed
  `AS net.devemperor.dictate.ai.AIProviderException.ErrorType` (imported from `:shared-ai`, NOT
  redefined companion-side — matches the §3.2 IMPORTANT note).
- **Parity references verified at the source (the load-bearing §3.6 claim).** Checked every
  `RoomParityReference` set against the real enum definitions: `SessionType/Status/Origin`,
  `StepType/Status` in `app/.../database/entity/*.kt`; `ResponseFormatKind`, `MessageRole` in
  `shared-ai/.../database/entity/*.kt` (moved there by Block A); `AIProviderException.ErrorType`
  (8 values) in `shared-ai/.../ai/AIProviderException.kt`. All eight sets match exactly.
- §3.3 Table translation: four Room-parity tables 1:1 (columns, affinities, nullability, FKs,
  indices), plus the companion-only `sessions.host_origin` axis and its index.
- §3.4 Ablation: `2.sqm` (v2→v3) creates the tables in FK order, backfills
  `received_texts → sessions + dispatch_state` (UNKNOWN→KEYBOARD per ADR-0016, `inserted_at` mirrors
  dispatch, PHONE_SYNC shape), then `DROP`s the old table + index. `verifySqlDelightMigration` green.
- §3.5 Sync/Repo rewire: diffed the new `upsertSyncSession` + `upsertDispatchState` split against the
  old single `upsertReceivedText` — the "never downgrade dispatched" invariant (`MAX(...)`) and the
  `last_outcome`-excluded-from-DO-UPDATE rule are preserved; the two-statement write runs inside the
  repo's `transactionWithResult`, so no partial state is observable. Cursor/page/count JOIN
  `dispatch_state` and scope `host_origin='PHONE_SYNC'` with the ADR-0020 ordering intact.
- §3.6 Parity test design: both families present (CHECK accept + reject per column; companion-enum ↔
  `RoomParityReference` cross-module guard with SSoT pointers).

**Behaviour-neutrality.** The five protected tests (`SyncE2ETest`, `CompanionE2ETest`,
`MultiConnectorE2ETest`, `TruncatedResponseE2ETest`, `SqlDelightHistoryRepositoryTest`) have a 0-line
diff in the D1a commit (byte-unchanged) and are green. `SyncService.kt`/`DispatchService.kt`
untouched — the `HistoryRepository` port signature held.

**Deviations (from impl report) — all defensible (D4).** Session-enum package `domain/session/`;
`usage` deferred to D1b (no DDL specified, no D1a acceptance criterion touches it, UsageSink port is
D1b); schema-only-table adapters deferred to D1b (SQLDelight omits an adapter until a query
references the table); the mandated `dispatch_state` JOIN on `selectCursor`. All correct.

**Code quality — clean.** One shared `toReceivedText` mapper via SQLDelight mapper overloads (no
per-query duplication); `writeSyncRow` helper dedups the two-table write; naming matches surrounding
companion code; comments explain WHY (invariants, ADR refs). `build.gradle` dedup left exactly one
`implementation project(':shared-ai')` (line 48).

**Test quality — comprehensive.** Accept + reject per Double-Enum column; Room-parity equality per
enum (goes red on artificial drift → acceptance criterion satisfied); the migration test covers
losslessness with same-ms cursor tie-break, UNKNOWN fold, and the dispatch mirror.

## Inline fix applied

| File | Change | Why |
|---|---|---|
| `CompanionDatabase.kt:33-35` | The `PRAGMA foreign_keys = ON` comment named the ablated `received_texts` table as the cascade the pragma protects. Repointed to the tables that actually carry an ON DELETE CASCADE now: `dispatch_state` (on device-unpair) and the session children (on session-delete). | D1a itself drops `received_texts` (2.sqm). The comment describes the pragma's *present-tense* purpose, so it now names a table that no longer exists — actively misleading a future reader about what the pragma guards. Comment-only, identical bytecode. |

## Non-blocking observations (no change made — spec-design consequences, behaviour-neutral)

1. **Orphaned PHONE_SYNC sessions after device revoke.** Old model: revoking a device
   `CASCADE`-deleted its `received_texts` rows. New model: the cascade hits `dispatch_state` only;
   the `sessions` row lingers (host_origin=PHONE_SYNC, no dispatch_state). It is invisible to both
   the cursor and history (both INNER-JOIN `dispatch_state`), so reads stay behaviour-identical; only
   storage is not reclaimed. Inherent consequence of the §3.4 split (sessions carries no `device_id`
   by Room-parity design) — not a defect, breaks no following chunk.
2. **`recordDispatch` does not touch `sessions.inserted_at`.** A mirror-only row (dispatched=0) later
   dispatched via `recordDispatch` would keep `inserted_at=NULL` while `dispatch_state.dispatched`
   flips to 1. Behaviour-neutral in D1a: `inserted_at` is not on the phone-sync read path
   (`ReceivedText` has no such field), and the real /v1/dispatch route sets `inserted_at` on the
   initial `dispatched=true` upsert, so the divergence is unreachable in practice.
3. `origin=UNKNOWN` no longer round-trips (folded to KEYBOARD on write) — spec-designed (§3.4,
   ADR-0016 landing default); protected tests confirm no observable regression.
4. `V1_RECEIVED_TEXTS`/`V2_RECEIVED_TEXTS` fixture strings near-duplicated across two migration
   tests — 2 uses, under the 3-use rule; abstracting now would be premature.

None warrant a code change or a delegated issue.

## Issues

| ID | Severity | Description | Status | Marker |
|---|---|---|---|---|
| — | — | none | — | — |

## Drift (files outside CHUNK_FILES)

**none** — the only file modified is `CompanionDatabase.kt`, which is in CHUNK_FILES. The other
working-tree changes (`app/.../secrets/*`, `shared/.../CanonicalJsonTest`,
`companion/.../secrets/FileAesGcmSecretStore.kt`) belong to parallel agents (B1/secrets); untouched.

## Files modified

- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/companion/src/main/kotlin/net/devemperor/dictate/companion/data/CompanionDatabase.kt`

## Test result

- `./gradlew :companion:test --rerun-tasks` → **207 tests, 0 failures, 0 errors, 2 skipped** (the 2
  skips are pre-existing platform-gated `DpapiSecretStoreTest` cases, unrelated to D1a).
- `./gradlew :companion:verifySqlDelightMigration` → BUILD SUCCESSFUL.
- Post-fix `:companion:test` re-run green (comment-only change recompiles to identical bytecode).
