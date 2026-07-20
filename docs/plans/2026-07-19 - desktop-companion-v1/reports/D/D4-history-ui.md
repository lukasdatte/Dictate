# D4 — Unified Companion History UI (§9.3 follow-up)

**Date:** 2026-07-20
**Agent:** D4 follow-up chunk (unified history surface)
**Commit:** `f33fa2a` `[D.D4] Unify the companion history into one filterable Phone/Desktop surface (desktop-companion-v1)`
**Test result:** `./gradlew :companion:test --rerun-tasks` green. New unified `HistoryViewModelTest`: 18/18.

## Starting point (differed from the chunk brief)

The brief assumed only the data layer existed. In fact repair wave W3-1 (`reports/D/repair-W3-1.md`)
had already built a §9.3 surface — but as a **screen-level Phone/Desktop toggle over two independent
view models** (`HistoryViewModel` + `DesktopHistoryViewModel`), with no unified list and no "All"
view. The chunk brief explicitly requires a **unified history with an All/Phone/Desktop
`host_origin` filter**, so this chunk superseded the toggle design rather than adding to it.

## What was built

- **`ui/history/HistoryViewModel.kt` (rewritten)** — the one history brain for both origins:
  - `HistoryItem` sealed interface (`Phone(ReceivedText)` / `Desktop(DesktopHistoryEntry)`) — the two
    row shapes stay distinct because subtitle, detail, and re-insert all branch on origin; `text` is
    the shared "what the row shows/copies/re-inserts".
  - `HistoryFilter { ALL, PHONE, DESKTOP }`; filter changes land on page 0 (like search).
  - **Merged paging:** each source stays SQL-paged; the "All" view fetches the top
    `(page+1)*pageSize` of each active source and merge-sorts by `(createdAt DESC, sessionId DESC)`
    — the same total order both queries use — then slices the page window. Correct because the
    merged top-K is contained in the union of the per-source top-Ks. The deliberate over-fetch on
    deep pages is documented in the class doc (50-row pages against local SQLite; one uniform load
    path beats per-filter offset special cases).
  - **Re-insert routing:** phone rows → `DispatchService.reinsert` (the one insert path, ADR-0018);
    desktop rows → fresh `desktopHistoryEntry` fetch, `inserter.insert(finalOutputText)` directly
    (NOT DispatchService, which only sees `PHONE_SYNC` + `dispatch_state` rows), `stampInserted` on
    non-FAILED outcome. `copy` re-fetches and writes the final output / text.
  - **Null `desktopSessions`** (forTest graph): the desktop source contributes nothing,
    `desktopAvailable = false` hides the filter chips, and the screen behaves exactly like the
    pre-Block-D phone-only history — decision documented in the class doc (one view model in every
    graph instead of a forked phone-only variant).
- **`ui/history/HistoryScreen.kt` (rewritten)** — single search field ("Search history"),
  `FilterChip` row All/Phone/This PC (only when `desktopAvailable`), one `LazyColumn` rendering
  per-type rows: phone rows as before (pending dot, `Phone · synced/typed…` subtitle), desktop rows
  with `This PC · inserted/not inserted here` subtitle and the expandable transcript-vs-final-output
  detail. Shared `RowActions`/`PendingDot`/`Pager`; filter-aware empty states.
- **Deleted:** `DesktopHistoryViewModel.kt` and `DesktopHistoryViewModelTest.kt` — fully superseded;
  keeping them would have been dead code.

## Tests (`ui/HistoryViewModelTest.kt`, 18 — superset of the two previous suites' 8 + 10)

Merged newest-first interleaving + page boundaries; rows carry their origin; PHONE/DESKTOP filters
hide the other origin and count correctly; filter change and search land on page 0; search filters
both sources; beyond-end clamp; desktop side excludes `PHONE_SYNC` and `REVIEW_REFINEMENT`; phone
re-insert routes through `DispatchService` (dispatched flag + outcome on the row); desktop re-insert
types the final output (not the transcript) and stamps `inserted_at`; FAILED does not stamp; gone
rows say so for either origin without touching the inserter; copy per origin (clipboard only);
`toggleExpand`; **null desktop source** (phone-only list, chips hidden, DESKTOP filter safe/empty);
`canInsert=false`.

## Decisions

1. **Supersede, don't extend, the W3-1 toggle** — the brief's "vereinheitlichte History" with an
   "Alle" view cannot be layered on two independent pagers; a merged list needs one brain. W3-1's
   report itself flagged the design as revisitable.
2. **Top-K over-fetch merge instead of a SQL `UNION`** — keeps the two read models (`ReceivedText`
   with dispatch state vs `DesktopHistoryEntry` with transcript) intact and the port boundary
   (`HistoryRepository` is a port; `DesktopSessionRepository` is concrete and nullable) unchanged.
   No schema or query changes were needed.
3. **`inserted_at` stamping on desktop re-insert kept** (W3-1 decision, per the scope research):
   mirrors the phone path's `recordDispatch`, keeps the "not inserted here" dot truthful.
4. **ADR-0035 alignment** — no contradiction: the ADR fixes the schema (`sessions` as the single
   archive, `host_origin` as the axis); the unified list is that axis surfaced as a filter.

## Files

- `companion/src/main/kotlin/net/devemperor/dictate/companion/ui/history/HistoryViewModel.kt`
- `companion/src/main/kotlin/net/devemperor/dictate/companion/ui/history/HistoryScreen.kt`
- `companion/src/main/kotlin/net/devemperor/dictate/companion/ui/history/DesktopHistoryViewModel.kt` (deleted)
- `companion/src/test/kotlin/net/devemperor/dictate/companion/ui/HistoryViewModelTest.kt`
- `companion/src/test/kotlin/net/devemperor/dictate/companion/ui/DesktopHistoryViewModelTest.kt` (deleted)
