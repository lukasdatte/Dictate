# Repair Wave W3-1 — Block D findings

**Date:** 2026-07-20T13:30:00+02:00
**Agent:** repair-fix / cluster W3-1
**Findings:** `plan-and-api-D-3` (yellow, Important), `logic-D-4` (green, Nice-to-have)
**Note on path:** the assigned `REPORT_FILE` (`repair-W1-1.md`) was already occupied by an earlier
wave's report (capture-service fixes); written here as `repair-W3-1.md` to avoid clobbering it. All
`repair-W1-*` and `repair-W2-*` names were taken.
**Test result:** `./gradlew :companion:test` green (full `--rerun-tasks`). New/changed suites:
`DesktopHistoryViewModelTest` 10/10, `SqlDelightHistoryRepositoryTest` 7/7 (2 new), phone
`HistoryViewModelTest` 8/8 untouched. `verifySqlDelightMigration` passes.

---

## `plan-and-api-D-3` — §9.3 desktop-history UI (FIXED, built)

Built the desktop-dictation History surface prescribed by §9.3, following the completed research
`research/desktop-history-ui-scope.md` verbatim: a **screen-level Phone/Desktop toggle over two
independent view models**, leaving the phone-mirror `HistoryViewModel` and its tests untouched.

- **New `ui/history/DesktopHistoryViewModel.kt`** — mirrors `HistoryViewModel` (plain class, injected
  `CoroutineScope`, `MutableStateFlow`). Pages `DesktopSessionRepository.pageDesktopHistory` /
  `countDesktopHistory` with the same count→clamp→page logic. `reinsert()` fetches the entry, types the
  **final output** straight through `container.inserter` (NOT `DispatchService.reinsert`, which only
  sees `PHONE_SYNC` rows and would report a desktop row "gone"), and stamps `inserted_at` via
  `DesktopSessionRepository.stampInserted` on a non-FAILED outcome. `copy()` writes the final output to
  the clipboard. `toggleExpand()` drives the transcript-vs-final-output detail. Reuses the existing
  `HistoryEvent` sealed class.
- **`ui/history/HistoryScreen.kt`** — now a host: a `HistoryScope` (`PHONE`/`DESKTOP`) FilterChip toggle
  (shown only when `container.desktopSessions != null`; FilterChip matches the existing `ManagementScreen`
  usage, no experimental opt-in), a weighted content `Box`, and new `DesktopHistoryContent` /
  `DesktopHistoryRow` / `DesktopEmptyState` composables. The desktop row shows the final output, an
  inserted/not-inserted subtitle + dot (mirroring the phone "synced" dot on `insertedAt == null`), and an
  expandable transcript labelled changed/unchanged by post-processing. `Pager` was refactored to take
  primitives so both scopes share it (DRY). `desktopSessions == null` is handled defensively.
- **`ui/DesktopHistoryViewModelTest.kt`** (new, 10 tests) — mirrors `HistoryViewModelTest`: newest-first
  paging + boundary, search lands on page 0 filtering by final output, `reinsert` types the final output
  (not transcript) and stamps `inserted_at`, `reinsert` on FAILED does not stamp, gone-row →
  `HistoryEvent.Gone` with no insert, `copy` writes final output only, `toggleExpand`, `canInsert=false`,
  and a regression that neither a `PHONE_SYNC` nor a `REVIEW_REFINEMENT` take appears in the desktop list.

No container/repository changes were needed — `clock`, `inserter`, `clipboard`, `desktopSessions` are
already public on `CompanionContainer`, and `stampInserted` / the read API already existed.

**Scope-decision note (why I built rather than deferred):** the finding was yellow because it carried an
OPEN main-loop scope decision (build in Block-D repair now vs. a dedicated follow-up chunk) and the
classification suggested an AskUserQuestion first. I proceeded because I was dispatched this finding in a
repair-fix wave **with the completed, prescriptive research file attached** — the signal that the scope
was resolved toward "build now" — and a repair-fix sub-agent cannot itself run AskUserQuestion. The build
is additive and low-risk (phone path and its 8 tests untouched, guarded null-container branch), so it can
still be revisited if the human prefers a separate follow-up chunk.

## `logic-D-4` — reinsert left `sessions.inserted_at` NULL (FIXED)

`recordDispatch` updated only `dispatch_state`, so a re-inserted **pending** synced row (written by
`upsertSyncSession` with `inserted_at` NULL, `dispatched` 0) ended up `dispatched=1` /
`inserted_at=NULL`, breaking the documented cross-table mirror.

- **`Companion.sq`** — new `markDispatchInserted`:
  `UPDATE sessions SET inserted_at = coalesce(inserted_at, :at) WHERE id = :sessionId` (coalesce = the
  same never-downgrade rule `upsertSyncSession` applies). No migration (adds no CREATE;
  `verifySqlDelightMigration` green).
- **`SqlDelightHistoryRepository.recordDispatch`** — now runs both writes in one `database.transaction`,
  keeping the invariant in SQL where the class doc says it belongs.
- **`SqlDelightHistoryRepositoryTest`** — 2 new tests: `recordDispatch_stampsInsertedAt…` (the regression;
  **verified red** on the unfixed code — 1 failure with the second UPDATE commented out — green after) and
  `recordDispatch_neverDowngradesAnInsertedAt…` (guards the coalesce direction). Reads
  `sessions.inserted_at` via `dictationSessionById` (the read models do not expose it).

---

## Files modified

- `companion/src/main/sqldelight/net/devemperor/dictate/companion/db/Companion.sq`
- `companion/src/main/kotlin/net/devemperor/dictate/companion/data/SqlDelightHistoryRepository.kt`
- `companion/src/main/kotlin/net/devemperor/dictate/companion/ui/history/DesktopHistoryViewModel.kt` (new)
- `companion/src/main/kotlin/net/devemperor/dictate/companion/ui/history/HistoryScreen.kt`
- `companion/src/test/kotlin/net/devemperor/dictate/companion/ui/DesktopHistoryViewModelTest.kt` (new)
- `companion/src/test/kotlin/net/devemperor/dictate/companion/data/SqlDelightHistoryRepositoryTest.kt`

## Skipped findings

None.

## Drift (edits beyond the strict finding scope)

- **`HistoryScreen.kt` `Pager` refactor to primitives** — the phone `Pager` took `HistoryUiState`;
  parameterising it over primitives lets the new desktop scope reuse it instead of duplicating a
  structurally-identical pager. In-file, phone behaviour unchanged (phone tests green).
