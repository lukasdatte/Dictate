# Desktop-History UI Scope (§9.3) — Repair Research

**Date:** 2026-07-20T13:30:00+02:00
**Triggered by:** Finding `plan-and-api-D-3 [Important]` — §9.3 desktop-history UI unbuilt: `DesktopSessionRepository` exposes a read API (`pageDesktopHistory`/`countDesktopHistory`/`desktopHistoryEntry` + `DesktopHistoryEntry`) with matching `Companion.sq` queries and a wired `container.desktopSessions`, but **zero UI consumers**. Desktop dictations persist yet are invisible and non-re-insertable.
**Agent-ID:** repair-research / desktop-history-ui-scope

## Sources

1. **Spec `desktop-host.md` §9** (Verwaltungs-/History-UI, D3): §9.1 panel entry + profile dropdown, §9.2 management screens, **§9.3 History-Screen-Ausbau** (the prescriptive text for this repair), §10 directory layout. Lines 972–1006, 1031, 1038.
2. **Code — read API (already built):** `companion/.../data/DesktopSessionRepository.kt:283–350` (`pageDesktopHistory`, `countDesktopHistory`, `desktopHistoryEntry`, `DesktopHistoryEntry`); `companion/.../sqldelight/.../Companion.sq:644–673` (the three queries, correlated-subquery transcript, `REVIEW_REFINEMENT`/in-flight exclusion).
3. **Code — existing phone-mirror History UI (the pattern to mirror):** `ui/history/HistoryScreen.kt`, `ui/history/HistoryViewModel.kt`, `ui/App.kt:36–92` (NavigationRail host), `ui/config/ManagementScreen.kt:44–63` (nullable-container guard pattern).
4. **Code — why phone re-insert can't be reused:** `domain/DispatchService.kt:70–75` (`reinsert` reads `history.findById` → phone `sessions JOIN dispatch_state`, `host_origin='PHONE_SYNC'`); `domain/FocusRestorationPolicy.kt:63–79` (`FocusRestoringTextInserter`, pipeline-only wrapping); `CompanionContainer.kt:66–116` (`inserter`, `clock`, `clipboard`, `desktopSessions` wiring).
5. **Code — test pattern:** `test/.../ui/HistoryViewModelTest.kt` (Dispatchers.Unconfined, real SQLDelight in-memory, FakeTextInserter); `test/.../data/DesktopHistoryTest.kt` (seed helper for completed desktop takes).
6. **ADR draft** `adrs/adr-companion-history-parity.md` — `sessions` is the single history archive; `host_origin` is the axis separating phone-mirror from desktop-dictation rows.

## Findings

### What exists vs. what §9.3 requires

`DesktopSessionRepository` (read side) and `Companion.sq` are complete and unit-tested (`DesktopHistoryTest`). What is missing is purely the **UI layer**: a ViewModel + Compose surface that consumes them. §9.3 prescribes exactly three capabilities:

1. **`host_origin` filter (Phone/Desktop)** — the History screen must let the user switch between the phone-mirror rows and the locally-dictated rows.
2. **Detail: transcript vs. final output** — `transcriptions.text` (raw) vs. `sessions.final_output_text` (post-processed), so the user sees what post-processing changed. `pageDesktopHistory` already returns `transcript_text` inline per row (correlated subquery), so no extra query is needed to render the detail.
3. **Re-insert via `TextInserter.insert(final_output_text)`** — NOT `DispatchService.reinsert`.

### Why phone `DispatchService.reinsert` cannot serve desktop rows (the load-bearing constraint)

`DispatchService.reinsert(sessionId)` (line 70) does `history.findById(sessionId)` → `SqlDelightHistoryRepository` → `historyEntry`/`pageHistory` which **INNER JOIN `dispatch_state` and scope to `host_origin='PHONE_SYNC'`** (`Companion.sq:354–358, 422–426`). A desktop-dictated session has `host_origin='DESKTOP_DICTATION'` and **no `dispatch_state` row**, so `findById` returns null and `reinsert` returns null (the "That text is no longer here" event). Desktop re-insert therefore must bypass `DispatchService` entirely and call `container.inserter.insert(entry.finalOutputText)` directly — precisely what §9.3 spells out.

### Which inserter — bare `container.inserter`, not the pipeline's `FocusRestoringTextInserter`

The `FocusRestoringTextInserter` wrapper (`FocusRestorationPolicy.kt:68`) is constructed **only inside the live pipeline** (`CompanionContainer.production():173`) and is not exposed on the container. It restores a window remembered at `onDictationTrigger()`. A History re-insert has no dictation trigger, so `saved` is null and `restoreBeforeInsert()` would be a no-op anyway. The phone dispatch/reinsert path likewise uses the bare `platform.inserter`. **Use `container.inserter` directly** — same single insert path, same Win32 gotchas fixed once (ADR-0018), no focus machinery in play.

### The two histories are genuinely different domains (drives the code shape)

| | Phone-mirror (existing) | Desktop-dictation (new) |
|---|---|---|
| Row model | `ReceivedText` (device binding, dispatch outcome) | `DesktopHistoryEntry` (transcript + final output, `insertedAt`) |
| Data source | `HistoryRepository` (`sessions JOIN dispatch_state`) | `DesktopSessionRepository` (`sessions`, no join) |
| Re-insert | `DispatchService.reinsert` → `recordDispatch` | `container.inserter.insert(finalOutputText)` |
| Status shown | synced / typed / clipboard-only / failed | transcript-vs-output diff; inserted / not-yet |

They share a screen (§9.3 "filter"), not a data shape. A single merged paged list would need a fragile `UNION` of two different projections and a row renderer full of `host_origin` conditionals. Keeping them as two ViewModels behind one screen-level toggle is the SRP-clean, SSoT-preserving choice and — critically — leaves the existing `HistoryViewModel` and its **five passing tests untouched** (no assertion changes, per the ADR-0-parity discipline).

## Implementation Hints

### Recommended shape: screen-level Phone/Desktop toggle over two ViewModels

**Do NOT** rewrite `HistoryViewModel` to carry both modes. Keep it exactly as-is for the Phone scope. Add a parallel `DesktopHistoryViewModel` and make `HistoryScreen` a thin host with a segmented toggle.

**1. New `ui/history/DesktopHistoryViewModel.kt`** — mirror `HistoryViewModel` structure (plain class + `MutableStateFlow`, injected `CoroutineScope`, `Dispatchers.Unconfined`-friendly). Copy the paging/clamp logic from `HistoryViewModel.load` verbatim (count → `coerceIn(0, lastPage)` → page) against `desktopSessions.countDesktopHistory` / `pageDesktopHistory`.

```kotlin
class DesktopHistoryViewModel(
    private val sessions: DesktopSessionRepository,
    private val inserter: TextInserter,
    private val clipboard: ClipboardPort,
    private val clock: ClockPort,
    private val scope: CoroutineScope,
    canInsert: Boolean,
    pageSize: Int = DesktopHistoryUiState.DEFAULT_PAGE_SIZE,
) {
    // state: rows: List<DesktopHistoryEntry>, query, page, pageSize, totalCount, canInsert,
    //        expandedId: String? (which row's transcript detail is open), event: HistoryEvent?

    fun reinsert(sessionId: String) = scope.launch {
        val entry = sessions.desktopHistoryEntry(sessionId)            // fresh fetch, may be gone
        if (entry == null) { setEvent(HistoryEvent.Gone); return@launch }
        val outcome = inserter.insert(entry.finalOutputText)          // <-- §9.3: bare inserter, final output
        if (outcome != InsertionOutcome.FAILED) {
            sessions.stampInserted(sessionId, clock.nowMillis())       // keep inserted_at truthful (see note)
        }
        load(state.value.query, state.value.page, HistoryEvent.Reinserted(outcome))
    }

    fun copy(sessionId: String) = scope.launch {
        val entry = sessions.desktopHistoryEntry(sessionId) ?: run { setEvent(HistoryEvent.Gone); return@launch }
        clipboard.writeText(entry.finalOutputText)                    // copy the final output
        setEvent(HistoryEvent.Copied)
    }
    // search / nextPage / previousPage / refresh / toggleExpand(id) / consumeEvent — as in HistoryViewModel
}
```

Reuse the existing `HistoryEvent` sealed class (`Reinserted`/`Copied`/`Gone`) — it is scope-agnostic. Only extend the `Reinserted.describe()` mapping if you want desktop-specific wording (not required).

**2. `ui/history/HistoryScreen.kt`** — become a host with a scope toggle:

```kotlin
enum class HistoryScope(val label: String) { PHONE("Phone"), DESKTOP("This PC") }

@Composable
fun HistoryScreen(container: CompanionContainer) {
    val desktopAvailable = container.desktopSessions != null      // null in the headless test graph
    var scope by remember { mutableStateOf(HistoryScope.PHONE) }
    Column {
        if (desktopAvailable) SegmentedButtons(scope) { scope = it }   // only offer the toggle when there IS a desktop side
        when (scope) {
            HistoryScope.PHONE   -> PhoneHistoryContent(container)     // == today's HistoryContent(viewModel)
            HistoryScope.DESKTOP -> DesktopHistoryContent(container)
        }
    }
}
```

Use Material3 `SingleChoiceSegmentedButtonRow` / `FilterChip` (the codebase already imports `FilterChip` in `ManagementScreen.kt`) for the toggle. Guard the desktop branch with the `container.desktopSessions != null` check — mirror `ManagementScreen.kt:47` ("Configuration is unavailable in this build."). In the real app `production()` always wires `desktopSessions`, so the toggle always shows; `forTest`/headless never renders `App()`, so the guard is purely defensive.

**3. `DesktopHistoryContent` + `DesktopHistoryRow` composables** (same file). Layout mirrors the phone `HistoryRow` (search field placeholder "Search dictations", pager, snackbar), with the row rendering the **detail**:
   - Final output prominent (`entry.finalOutputText`, 2-line ellipsis collapsed).
   - Expand affordance → reveals `entry.transcriptText` labelled "Transcript" when it is non-null **and differs** from the final output; when equal (or transcript null) show nothing / "(unchanged)". This is the "transcript vs. final output" detail — rendered from the already-loaded entry, no extra query.
   - Subtitle: `createdAt.asTime()` + inserted/not-inserted (`entry.insertedAt != null` → "inserted", else "not inserted here").
   - "Copy" + "Insert again" (enabled = `state.canInsert`), same as phone.
   Reuse the phone screen's `asTime()` / `TIME_FORMAT` / `SNACKBAR_MILLIS` helpers (extract to a small shared `HistoryFormatting.kt` or keep duplicated — one formatter is not worth an abstraction; prefer extraction if both files reference it).

**4. Wiring in `HistoryScreen`** — construct `DesktopHistoryViewModel` with `container.desktopSessions!!`, `container.inserter`, `container.clipboard`, `container.clock`, `rememberCoroutineScope()`, `canInsert = container.inserter.available`. All four collaborators already exist on the container; **no container change is required** (`clock`, `inserter`, `clipboard`, `desktopSessions` are all public fields).

### On stamping `inserted_at` on re-insert (judgment call — recommended)

§9.3 mandates only `TextInserter.insert(final_output_text)`. The spec does not explicitly ask to update `inserted_at`. **Recommendation: stamp it** via `desktopSessions.stampInserted(sessionId, now)` on a non-FAILED outcome, so a row's "inserted / not inserted here" status stays truthful after a manual re-insert (a discarded take the user re-inserts genuinely becomes inserted). This mirrors how the phone path calls `history.recordDispatch` after `reinsert`. It is a small, honest state update through the existing repo method (`DesktopSessionRepository.stampInserted:71`). If the fix agent judges the review/discard semantics should keep `inserted_at` as an auto-insert-only marker, dropping the stamp is acceptable and still satisfies §9.3 — document the choice either way. **Do stamp** unless a concrete review-semantics conflict surfaces.

### Tests — `test/.../ui/DesktopHistoryViewModelTest.kt`

Mirror `HistoryViewModelTest` (Dispatchers.Unconfined, `CompanionDatabase.inMemory()`, real `DesktopSessionRepository`, `FakeTextInserter`, `FakeClipboard`, `MutableClock`). Reuse `DesktopHistoryTest`'s `seedCompletedDesktopTake(id, createdAt, transcript, output)` helper (copy it in). Assertions:
- Newest-first paging + page-size boundary (parallels `rowsAreNewestFirst_andPagedByPageSize`).
- Substring search lands on page 0 and filters by final output (`countDesktopHistory`/`pageDesktopHistory` already tested at data layer — here assert the VM wires them).
- **`reinsert` calls `inserter.insert` with `finalOutputText`** (NOT the transcript), emits `Reinserted(outcome)`, and (if stamping) sets `insertedAt`. Assert against `FakeTextInserter.inserted`.
- `reinsert` of a gone/unknown id → `HistoryEvent.Gone`, `inserter.inserted` empty.
- `copy` writes `finalOutputText` to clipboard only, no inserter call.
- `canInsert = false` → button-disabled flag false.
- Regression-flavoured: a `PHONE_SYNC` row and a `REVIEW_REFINEMENT` take seeded alongside do **not** appear in the desktop list (defends the query scoping through the VM).

### Directory layout (§10) — no new package needed

Everything lands in the existing `ui/history/` package: `DesktopHistoryViewModel.kt` + the new composables in `HistoryScreen.kt` (or a sibling `DesktopHistoryScreen.kt`). §10 lists `ui/{profiles,models,prompts}/` for the §9.2 management screens (already built as `ui/config/`); §9.3 is an *Ausbau* of the existing history screen, so it stays in `ui/history/`.

## Recommendation

Build the §9.3 UI as a **screen-level Phone/Desktop toggle over two independent ViewModels**: leave `HistoryViewModel` (phone-mirror) untouched, add a `DesktopHistoryViewModel` that pages `DesktopSessionRepository.pageDesktopHistory/countDesktopHistory`, renders the transcript-vs-final-output detail from the already-loaded `DesktopHistoryEntry`, and re-inserts via **`container.inserter.insert(finalOutputText)` directly** (never `DispatchService.reinsert`, which only sees `PHONE_SYNC` rows), stamping `inserted_at` on success. `HistoryScreen` gains a segmented toggle (shown only when `container.desktopSessions != null`); no container wiring changes are needed — `clock`, `inserter`, `clipboard`, `desktopSessions` are all already public. Add `DesktopHistoryViewModelTest` mirroring `HistoryViewModelTest`.

## References

- Spec: `docs/plans/2026-07-19 - desktop-companion-v1/research/desktop-host.md` §9.3 (+ §9.1/§9.2, §10)
- ADR draft: `docs/plans/2026-07-19 - desktop-companion-v1/adrs/adr-companion-history-parity.md`
- Plan: `docs/plans/2026-07-19 - desktop-companion-v1/desktop-companion-v1.md` Block D / Chunk D3 (§5 line 574)
- Code (read API): `companion/src/main/kotlin/net/devemperor/dictate/companion/data/DesktopSessionRepository.kt:283–350`; `companion/src/main/sqldelight/net/devemperor/dictate/companion/db/Companion.sq:644–673`
- Code (pattern to mirror): `companion/src/main/kotlin/net/devemperor/dictate/companion/ui/history/{HistoryScreen,HistoryViewModel}.kt`, `ui/App.kt`, `ui/config/ManagementScreen.kt`
- Code (constraints): `companion/src/main/kotlin/net/devemperor/dictate/companion/domain/DispatchService.kt:70`, `domain/FocusRestorationPolicy.kt:68`, `CompanionContainer.kt:66–116`
- Tests: `companion/src/test/kotlin/net/devemperor/dictate/companion/ui/HistoryViewModelTest.kt`, `data/DesktopHistoryTest.kt`
