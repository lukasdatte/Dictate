# History Pagination and Scale

---
date: 2026-07-02
author: Lukas + Claude (multi-agent review session)
type: Research
status: Accepted
context: The history list runs unbounded full-table queries synchronously on the main thread, per keystroke, over a table that grows forever. Finding F-054.
related-plan: n/a (seeded by 2026-07-02 - feature-wiring-code-review.md, F-054)
related-adrs: —
---

The history screen works today because the table is young. It degrades structurally: unbounded queries × main-thread execution × keystroke-frequency triggers × unbounded retention. This document scopes the fix as one topic because the four factors multiply — fixing any single one leaves jank on the table.

## 1. Vision and Motivation

### 1.1 The four multiplying factors

1. **Unbounded queries:** `SessionDao.getAll()/getByType()/search()` have no `LIMIT`/`OFFSET` (`SessionDao.kt:62-69`).
2. **Main thread:** `DictateDatabase` builds with `allowMainThreadQueries()` (`DictateDatabase.kt:121`); `HistoryActivity.refreshData()` (`:158-181`) runs the DAO calls on the UI thread and `notifyDataSetChanged()` over the full list.
3. **High-frequency triggers:** `refreshData` fires per SearchView keystroke (`onQueryTextChange`, `:120-124`), on every `ActiveJobRegistry` snapshot change (`:148`), and in `onResume` — no debounce.
4. **Unbounded retention:** only COMPLETED+inserted rows are pruned (7 days, `deleteInsertedOlderThan`). FAILED/CANCELLED rows are kept **deliberately forever** (`PipelineOrphanCleaner.kt:26-28`: "auto-deleting them would silently lose information"), and never-inserted COMPLETED rows (copy-only, post-processing) also never expire.

**Bonus defect:** the search `LIKE '%' || :query || '%'` has no `ESCAPE` clause — user-typed `%`/`_` silently change search semantics (`SessionDao.kt:68`).

### 1.2 Discarded Alternatives

- **Only debouncing the search** — reduces trigger frequency but leaves an O(table) main-thread query on every trigger that still fires.
- **Only capping retention** — a hard cap on FAILED rows contradicts the documented non-destructive recovery philosophy; and even a capped table janks once large enough on low-end devices.

## 2. Findings + Conclusions — target design

1. **Paging:** Room `PagingSource` + Paging3 (`androidx.paging:paging-runtime`) with a `PagingDataAdapter` — the standard, sustainable answer; incremental loading, background query execution, and diffing (replacing `notifyDataSetChanged`) come built in. Minimum-viable alternative if Paging3 is deemed too heavy: `LIMIT ?, OFFSET ?` + background executor + load-more — but Paging3 is the recommended long-term option.
2. **Off the main thread:** the history queries stop relying on `allowMainThreadQueries()`. (Removing the flag app-wide is a separate, larger effort — out of scope here; the history screen just stops depending on it.)
3. **Debounce** the search callback (~300 ms) and coalesce registry-tick refreshes.
4. **Search escaping:** `LIKE ... ESCAPE '\'` with `%`/`_` escaped in the query argument.
5. **Retention:** add the follow-up already sketched in `PipelineOrphanCleaner`'s doc — a long-horizon cleanup for never-inserted terminal rows (e.g. FAILED/CANCELLED after 60 d), keeping the non-destructive philosophy for the recent window. Numbers are a user decision (gap 1).
6. **Index check:** verify the query patterns (type filter, timestamp sort, LIKE search) are covered by indices once LIMIT-based paging lands; add per `docs/DATABASE-PATTERNS.md` if missing.

## 3. Testing Approach

- **DAO tests:** paging window correctness (order, boundaries); ESCAPE behaviour (`%`/`_` literals found literally).
- **Retention test:** cleanup deletes only rows past the horizon and never touches rows with `inserted_at == null` inside the window.
- **Regression:** search debounce — rapid text changes issue ≤ 1 query per debounce window (executor-based fake clock).

## 4. Information Gaps

1. **Retention horizon + whether FAILED rows may ever auto-delete** — owner: user decision (touches the documented "don't silently lose information" philosophy); fallback: 60 d for CANCELLED, keep FAILED forever, revisit with real table-size data.
2. **Paging3 vs. minimal LIMIT/load-more** — owner: implementer after checking APK-size/dependency appetite; fallback: Paging3 (recommended above).
3. **F-054 is unverified** (feature-gap pass-through) — the code citations are concrete, but re-confirm the trigger frequency claims (`:120-124`, `:148`) before building.

## 5. Change History

### 2026-07-02 — Implemented (status → Accepted)

- **Trigger:** Implementation of the target design (§2).
- **What changed:**
  - **§2.1 Paging:** `SessionDao.pagedHistory(type, searchPattern): PagingSource<Int, SessionEntity>` (Room + `androidx.room:room-paging`, `androidx.paging:paging-runtime-ktx 3.3.6` via the version catalog) replaces the unbounded `getAll()`/`getByType()`/`search()` trio (removed — no remaining callers). `HistoryActivity` + `HistoryAdapter` were converted Java → Kotlin: `PagingDataAdapter` + DiffUtil instead of `notifyDataSetChanged()`, with a new `HistoryViewModel` owning the `Pager`/filter state (`cachedIn` across config changes).
  - **§2.2 Off the main thread:** all history queries run on Room's paging executor; deletes run on `Dispatchers.IO` in the view model. `allowMainThreadQueries()` stays app-wide (out of scope, per spec).
  - **§2.3 Debounce/coalesce:** search input debounced 300 ms (initial emission exempt); `ActiveJobRegistry` ticks coalesced via `sample(300 ms)` (not `debounce` — a busy pipeline's continuous ticks would starve a debounced refresh) into `refreshEvents` → `adapter.refresh()`. Running-badge state is folded into the diffed item (`HistoryRow.isRunning`) so refreshes rebind only changed rows.
  - **§2.4 Escaping:** `LIKE ... ESCAPE '\'` in the DAO + `LikeEscape.escape()` (escapes `\`, `%`, `_`) applied at the view-model boundary.
  - **§2.5 Retention (gap-1 fallback):** `SessionDao.deleteCancelledOlderThan` wired as path 3 of `PipelineOrphanCleaner` — CANCELLED rows older than 60 d are deleted; FAILED rows are kept forever. Horizon is constructor-injectable (`CANCELLED_RETENTION_MS_DEFAULT`).
  - **§2.6 Index check:** verified — `index_sessions_created_at` serves the `ORDER BY created_at DESC` walk; a composite `(type, created_at)` index was deliberately not added (the nullable-parameter query shape can't use it; substring `LIKE` is unindexable without FTS). No migration.
  - **§3 Tests:** `SessionDaoHistoryTest` (Robolectric, real in-memory Room — paging windows, ESCAPE incl. unescaped-over-match controls, retention SQL), `HistoryViewModelTest` (virtual clock — ≤ 1 query per debounce window, escaping, tick coalescing), `PipelineOrphanCleanerTest` extensions (horizon semantics, FAILED immunity, failure absorption).
- **Deviations:**
  - `RECORDING_INTERRUPTED` rows now show the RECORDED ("Not processed") badge; the Java adapter let them fall into the `default:` branch (`Log.wtf` + no badge). Forced by Kotlin's exhaustive `when`; semantically both are "unfinished recording, continuable".
  - §1.1 citation correction: `PipelineOrphanCleaner` lives in `state/`, not `core/`.
  - Gap-3 trigger-frequency claims re-confirmed before building (`HistoryActivity.java:120-124`, `:148`, `:158-181` as cited).

### 2026-07-02 — Initial scoping

- **Trigger:** Whole-app review, history sweep agent.
- **What changed:** Document created from F-054.

## 6. References

- Parent catalog: [`2026-07-02 - feature-wiring-code-review.md`](<2026-07-02 - feature-wiring-code-review.md>) — F-054.
- Code (pre-fix citations): `history/HistoryActivity.java:120-181` (since converted to `HistoryActivity.kt`), `database/dao/SessionDao.kt:62-69`, `database/DictateDatabase.kt:121`, `state/PipelineOrphanCleaner.kt:26-28`.
- `docs/DATABASE-PATTERNS.md` — index + migration conventions.
