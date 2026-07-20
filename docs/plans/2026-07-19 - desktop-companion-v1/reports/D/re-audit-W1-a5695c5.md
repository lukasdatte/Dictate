# Block D — Re-Audit of Repair Wave `a5695c5` (MODE = re-audit)

**Block:** D (D1a schema/sync · D1b capture/pipeline · D2 hotkey/panel/insert · D3 review/config/entities)
**Timestamp:** 2026-07-20T13:30:00+02:00
**Repair-wave commit under review:** `a5695c5` `[D] repair wave 1` (== current HEAD)
**Findings verified:** `plan-and-api-D-3` (yellow, Important), `logic-D-4` (green, Nice-to-have) — the two open findings from `validated-findings.md` (the 13:30 initial-consolidation re-run that superseded the 00:40 pass)
**Path note:** the assigned `REPORT_FILE` (`re-audit-W1.md`) was already occupied by the 00:40 re-audit of the earlier `b99b141` wave; written here as `re-audit-W1-a5695c5.md` to avoid clobbering that record.
**Verify base:** `:companion:test --rerun-tasks` → **382 tests, 3 documented skips, 0 failures, 0 errors** (fresh compile + execution). Changed suites: `DesktopHistoryViewModelTest` 10/10, `SqlDelightHistoryRepositoryTest` 7/7 (2 new), phone `HistoryViewModelTest` 8/8 untouched.

## Verdict: CONVERGED — both findings resolved, no new problems introduced

| ID | Class | Status after `a5695c5` | Evidence |
|---|---|---|---|
| plan-and-api-D-3 | yellow | **RESOLVED (built)** | §9.3 desktop-history UI now exists and consumes the read API |
| logic-D-4 | green | **RESOLVED (fixed)** | `recordDispatch` now stamps `sessions.inserted_at` transactionally |

Zero findings remain open. The re-audit returns an empty `findings` array (`eliminated_count = 2`).

## Per-finding verification

### plan-and-api-D-3 — §9.3 desktop-history UI unbuilt → RESOLVED

The finding: the desktop-history read layer (`DesktopSessionRepository.pageDesktopHistory` / `countDesktopHistory` / `desktopHistoryEntry` / `DesktopHistoryEntry`) was fully wired but had **zero UI consumers**, so persisted desktop dictations were invisible and non-re-insertable. Suggested fix: a desktop-history view-model over that read API + a `HistoryScreen` section with a Phone/Desktop filter, transcript-vs-`final_output_text` detail, and a `TextInserter` re-insert path.

The wave built exactly that:
- **`ui/history/DesktopHistoryViewModel.kt`** (new, 140 lines) — consumes `pageDesktopHistory`/`countDesktopHistory`/`desktopHistoryEntry` with the same count→clamp→page logic as `HistoryViewModel`. `reinsert()` types the **final output** straight through `container.inserter` (verified: **not** `DispatchService.reinsert`, which only sees `PHONE_SYNC` rows and would report a desktop row "gone") and stamps `inserted_at` via `stampInserted` on a non-FAILED outcome. `copy()` writes final output to the clipboard; `toggleExpand()` drives the transcript-vs-final detail.
- **`ui/history/HistoryScreen.kt`** — a screen-level `HistoryScope` (`PHONE`/`DESKTOP`) `FilterChip` toggle (shown only when `container.desktopSessions != null`), a weighted content `Box`, and new `DesktopHistoryContent`/`DesktopHistoryRow`/`DesktopEmptyState`. The desktop row shows the final output, an inserted/not-inserted subtitle + dot (mirroring the phone "synced" dot on `insertedAt == null`), and an expandable transcript labelled changed/unchanged by post-processing. Nullable `container.desktopSessions` is handled in both the toggle guard and a defensive `DesktopHistoryContent` branch (the headless `forTest` graph).
- **`ui/DesktopHistoryViewModelTest.kt`** (new, 10 tests) — newest-first paging + boundary, search lands on page 0 filtering by final output, `reinsert` types final output not transcript, stamps `inserted_at`, FAILED does not stamp, gone-row → `HistoryEvent.Gone`, `copy` writes final output only, `toggleExpand`, `canInsert=false`, and a regression that neither `PHONE_SYNC` nor `REVIEW_REFINEMENT` takes surface in the desktop list.

Grep confirms the read API now has consumers; the dangling cross-chunk contract is closed. All symbols the new code depends on exist at HEAD (`stampInserted`, `container.desktopSessions`/`inserter`/`clipboard`/`clock`, `TextInserter.available`, `HistoryEvent.{Reinserted,Copied,Gone}`).

**On the yellow scope-decision:** the finding was yellow because it carried an OPEN main-loop scope decision (build in Block-D repair now vs. a dedicated follow-up chunk) that likely warranted an `AskUserQuestion`. That decision is now **materially resolved toward "build now"**: the repair agent was dispatched this finding together with the completed, prescriptive research file `research/desktop-history-ui-scope.md`, and built the surface additively (phone path + its 8 tests untouched, guarded null-container branch). No open decision remains that would keep this finding on the board. (Documented in `repair-W3-1.md`.)

### logic-D-4 — `reinsert` never stamped `sessions.inserted_at` → RESOLVED

The finding: `recordDispatch` updated only `dispatch_state`, so re-inserting a text synced as *pending* (`upsertSyncSession` wrote `inserted_at=NULL`, `dispatched=0`) left `dispatched=1` while `inserted_at` stayed NULL, violating the documented cross-table mirror. Suggested fix: have `recordDispatch` also stamp `sessions.inserted_at` with a never-downgrade coalesce in the same transaction, plus a regression test.

The wave implemented exactly that:
- **`Companion.sq`** — new `markDispatchInserted: UPDATE sessions SET inserted_at = coalesce(inserted_at, :at) WHERE id = :sessionId` (coalesce = the never-downgrade rule `upsertSyncSession` already applies). No new `CREATE` → `verifySqlDelightMigration` stays green.
- **`SqlDelightHistoryRepository.recordDispatch`** — now runs `recordDispatch` + `markDispatchInserted` in one `database.transaction`, keeping the invariant in SQL where the class doc says it belongs.
- **`SqlDelightHistoryRepositoryTest`** — 2 new tests: `recordDispatch_stampsInsertedAt_soTheArchiveMirrorsTheDispatchFlag` (the regression; the wave report notes it was verified red on the unfixed code) and `recordDispatch_neverDowngradesAnInsertedAtAlreadyStamped` (guards the coalesce direction). Both green.

The latent cross-table disagreement is now impossible on either write path (`dispatch` already upheld it; `reinsert` now does too).

## New problems introduced by the wave? None

- **`Pager` refactor drift** — the phone `Pager(HistoryUiState, HistoryViewModel)` was parameterised over primitives so both scopes share it (DRY). All three sites verified: phone call `:143`, desktop call `:264`, definition `:361` — no stale caller of the old signature. Phone `HistoryViewModelTest` 8/8 unchanged and green.
- **Shared `HistoryEvent.describe()`** — exhaustive `when` over the sealed class (`Copied`/`Gone`/`Reinserted`), reused by both scopes; compiles and passes.
- **No container/repository API changes** — the wave only added a query, a repo transaction, one view-model, and screen composables; no existing signature that other chunks depend on was altered.
- Full `:companion:test --rerun-tasks` compiles the whole module (Compose composables included) and executes 382 tests with 0 failures — no cross-chunk regression.

## Eliminated / dropped

Both re-audited findings verified resolved by `a5695c5` and dropped (`eliminated_count = 2`). No residual finding and no newly-introduced finding. Block D is converged.
