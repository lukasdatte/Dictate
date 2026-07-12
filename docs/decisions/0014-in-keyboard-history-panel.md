# ADR-0014: In-Keyboard History Panel — a Paged List Surface in the IME

**Status:** Accepted
**Subsystem:** state, ui-rendering, service
**Scope:** Project-Wide
**Date:** 2026-07-12
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0013, ADR-0011 and ADR-0004.** ADR-0013 owns the review
> panel (the axis + LayoutMode precedent this follows) and the dictated
> review-refinement carrier recording; ADR-0011 owns the `getFinalOutput` text
> contract, the `committed=false` pending path and the shared `markInserted`
> acknowledge channel; ADR-0004 owns the LayoutCatalog + MotionLayout surface
> selection. This ADR adds a second in-keyboard panel — a paged history list —
> on top, leaving all three contracts intact.

## Research

- **Paging3 is used only in the full-screen history, tied to a `ViewModel`.**
  `HistoryViewModel` (`history/HistoryViewModel.kt:137-148`) builds the `Pager`
  and `cachedIn(viewModelScope)`; `HistoryActivity` collects it under
  `lifecycleScope` + `repeatOnLifecycle(STARTED)` (`history/HistoryActivity.kt:126-162`).
  The IME (`DictateInputMethodService`) is neither a `LifecycleOwner` nor a
  `ViewModelStoreOwner`, so that machinery cannot be reused verbatim.
- **"Pending" is a row predicate, not a separate source.**
  `SessionDao.findPendingInsertion` (`database/dao/SessionDao.kt:318-328`) selects
  `status='COMPLETED' AND final_output_text IS NOT NULL AND inserted_at IS NULL`.
  The same computed boolean can drive a pending-first `ORDER BY`, so the panel
  needs no in-memory merge with the `pendingSessions` axis (that axis only holds
  fresh/recovered parts — the DB query is the full superset).
- **The review panel overlays only the button grid.** `review_panel_cl`
  (`res/layout/activity_dictate_keyboard_view.xml:373-384`) is constrained
  top+bottom to `main_buttons_cl`, so it is exactly grid-height. The MotionScene
  (`res/xml/motion_scene_keyboard.xml`) governs only `main_buttons_cl` (itself a
  MotionLayout); sibling containers live in the root `ConstraintLayout`
  (`wrap_content`). There was no precedent for a surface *taller* than the grid.
- **The `REVIEW_REFINEMENT` origin was named in Paket 2 but never persisted.**
  `SessionOrigin` held only `{KEYBOARD, HISTORY_REPROCESS, POST_PROCESSING}`
  (CHECK-constrained), and `ImePipelineConfigResolver.resolveFresh` hard-coded
  `SessionOrigin.KEYBOARD` for every fresh recording (`:177`), so the S2
  refinement carrier (ADR-0013) was indistinguishable from a normal recording.
- **`sessions.type` carried the last documented Double-Enum debt.**
  `docs/DATABASE-PATTERNS.md` §"retrofit when next touched" lists `sessions.type`
  as a plain String without a CHECK; git shows `SessionType` was introduced in one
  commit (`6608bfa`) with three values, never changed, and only ever written as
  `SessionType.name` — the same safety argument the v8 `step_type` retrofit used.

## Context

Post-processing is now a persisted conversation with a review panel (ADR-0012 /
ADR-0013). The next surface is a *fast, in-context* history: the edit-bar already
has a history button that opens the full-screen `HistoryActivity`, but reaching a
recent result from inside a text field means leaving the keyboard. Paket 3 adds an
in-keyboard panel that lists the full history and re-inserts a result in one tap,
while the heavyweight screen (search / audio / detail) stays one long-press away.
This must not regress the ADR-0011 pending/`getFinalOutput`/acknowledge contracts,
the ADR-0013 review precedence, or the ADR-0004 surface selection, and it must
finally make the ADR-0013 refinement carriers distinguishable.

## Decision

A short press on the edit-bar history button opens an in-keyboard **history
panel**: its own state axis, a taller-than-grid LayoutMode, and an IME-owned
Paging list.

### 1. Button split — short vs long press

The edit-bar history button (`EditBarController`) gains a long-press
(`onHistoryLongClicked`) that opens the full-screen `HistoryActivity`; the short
press (`onHistoryClicked`) now toggles the in-keyboard panel. The fast in-context
list lives on the primary tap; the heavyweight screen on the deliberate gesture.

### 2. `historyPanel` state axis

Review-style, but minimal: a `HistoryPanelState(open: Boolean)` axis owned by
`HistoryPanelModule` (an `object` — teardown loses no data, so no clock/port).
Only `open` lives in state (so `LayoutCatalog.forKeyboard` can swap the grid for
the panel); the paged list is an IME-owned stream, NOT part of the immutable
snapshot. `HistoryPanelAction` = `Open` / `Close` / `AcknowledgeInsert(sessionId)`.
An auto-close cross-module cascade fires on IME-view teardown OR when a recording
starts (an external recording trigger — widget / QS tile / shortcut — must not
strand the panel over a live capture).

### 3. Paging3 in the IME via a caller-owned scope

The panel reuses the same Room-paged history — a dedicated `pagedHistoryPanel()`
query (pending-first order, `REVIEW_REFINEMENT` carriers excluded) wrapped by
`KeyboardHistoryPager`, whose `cachedIn` takes a caller scope instead of
`viewModelScope`. `KeyboardHistoryController` owns that scope and centralises the
three cancel points (input-view destroy/rebuild, panel close): a fresh scope per
input view, a collector started on open (idempotent) and stopped on close,
everything cancelled on destroy. Room's `InvalidationTracker` invalidates the
`PagingSource` on any `sessions` write, so a pipeline completing while the panel
is open refreshes the list — and surfaces the new pending part on top — for free.

### 4. Pending-first order + insert semantics

`pagedHistoryPanel()` sorts uninserted completed rows first (the
`findPendingInsertion` predicate minus the freshness floor), then newest-first.
The pure `SessionEntity.isPendingInsertion()` mirrors that ORDER-BY key (pinned by
a parity test — the SQL cannot call the Kotlin). Per-row "Insert" commits
`SessionManager.getFinalOutput(sid)` into the host imperatively (a side-channel the
reducer cannot reach), then acknowledges a *pending* row through the shared
`markInserted` channel: `PendingSessionsAction.AcceptAndInsert` when the row is in
the `pendingSessions` axis (removes the part + acknowledges), else
`HistoryPanelAction.AcknowledgeInsert` (acknowledge only — for an older uninserted
row not in the axis). An already-inserted row is a pure re-commit. The insert
button is disabled for text-less rows (`hasInsertableText`).

### 5. Layout — a distinctly taller surface

A `KEYBOARD_HISTORY_PANEL` LayoutMode (empty `history_panel_state`
`deriveConstraintsFrom two_row_state`, selected in `forKeyboard` below the review
panel — a held review always wins — and above staging) hides the grid; a dedicated
`history_panel_cl` container, anchored below the edit bar with its own list height
of ~50% of the display (clamped to `[280dp, 400dp]`), covers the grid via
elevation and makes the root `wrap_content` grow, so the IME window becomes taller
when the panel opens. `HistoryPanelRenderer` (`backendType=null`) toggles the
container and fires `onOpenChanged` on transitions so the IME starts/stops the
collector once per open/close.

### 6. Extensible action row

Each row's action bar is laid out to hold a second button. A later package adds
"Send to Windows"; the reserved `item_kbd_history_send_btn` slot is GONE and the
`KeyboardHistoryAdapter.Callback` documents the future `onSendToWindows` hook. No
premature abstraction — just the documented dock.

### 7. `REVIEW_REFINEMENT` origin (schema v9)

A new `SessionOrigin.REVIEW_REFINEMENT` value (schema v9, sessions table recreate)
closes the Paket-2 gap: the S2 refinement recording now persists it (threaded
explicitly via `FreshConfig.origin`, not derived from `transcriptionOnly` — origin
and "transcription-only" are distinct axes that only coincide today). The panel
excludes these carriers by `WHERE`; the full-screen history still shows them with a
discreet "Refinement" tag. The same recreate discharges the documented
`sessions.type` Double-Enum CHECK debt (git-verified safe on the same bar as the v8
`step_type` retrofit).

### Scope of this Convention

Applies to the **in-keyboard history panel** and its data/insert/lifecycle
operations. The Paging-in-the-IME pattern (a caller-owned scope + a lifecycle
controller in place of `viewModelScope`/`repeatOnLifecycle`) is the reusable model
for any future in-keyboard list (e.g. the Windows-target picker).

**Exempt (unchanged):** the full-screen `HistoryActivity` keeps its `ViewModel` +
`lifecycleScope` Paging; the review panel (ADR-0013) and pending parts (ADR-0011)
keep their axes; history reprocess/regenerate (ADR-0012) is untouched.

## Alternatives Considered

1. **A simpler non-paged `LIMIT` query + `ListAdapter` snapshot.** No scope
   ceremony. Rejected: it loses Room's live invalidation (a completing pipeline
   would not refresh the open panel without a manual re-query), caps the list
   artificially, and forks a second load path from the activity. Reusing Paging is
   the sustainable choice for a full, live-updating list.

2. **Merge the in-memory `pendingSessions` axis as a header + the paged history
   below.** Pending parts already live in state. Rejected: the axis holds only
   fresh/recovered parts (freshness floor), so it would need a dedup against the
   full paged list plus separate live-feed handling. One pending-first query is a
   single source of truth for order with no dedup.

3. **Put the paged list in the state snapshot.** Symmetric with other axes.
   Rejected: `PagingData` is mutable and lifecycle-bound — a poor fit for the
   immutable, structurally-shared `DictateUiState`. Only the `open` flag needs to
   be in state (for `forKeyboard`); the list belongs to the IME like the
   `InputConnection`.

4. **Tag the refinement carriers via `parent_session_id` instead of a new origin.**
   No migration. Rejected: the project's Double-Enum discipline makes a queryable
   origin the sustainable marker (the panel filters by `WHERE origin != …`), and
   the recreate needed to widen the CHECK also discharges the `sessions.type` debt
   for free — so the "heavier" option is the cleaner one here.

5. **Derive the S2 origin from `transcriptionOnly` in `resolveFresh`.** One fewer
   field. Rejected: origin ("where did this come from") and `transcriptionOnly`
   ("does this run a turn") are distinct axes that only coincide today; an explicit
   `FreshConfig.origin` prevents a silent coupling if `transcriptionOnly` ever gets
   a second use.

## Consequences

**Positive:**
- A recent result is re-insertable without leaving the keyboard; pending results
  sort to the top and update live as pipelines complete (Room invalidation).
- One Paging path shared with the activity (one query family, one diff behaviour);
  the caller-owned-scope + controller pattern is a reusable blueprint for future
  in-keyboard lists.
- `getFinalOutput` (one text source) and `markInserted` (one acknowledge channel)
  are reused, so recovery never re-surfaces an inserted row; the ADR-0011/0013
  contracts are untouched.
- The Paket-2 `REVIEW_REFINEMENT` gap is closed and the last `sessions` Double-Enum
  debt (`type`) is discharged in the same migration.

**Negative:**
- A second in-keyboard panel axis + LayoutMode + renderer: a reader tracing surface
  selection now follows two panel arms in `forKeyboard` above the grid modes.
- The IME owns a manually-managed coroutine scope (not a `LifecycleOwner`), so its
  lifecycle correctness rests on the controller's three cancel points rather than
  the framework — a small, tested surface, but bespoke.
- One more schema version (v9) and one more sessions-table recreate.

**Failure Modes:**
- **Reorder jump on insert.** Inserting a pending row sets `inserted_at`, so it
  leaves the pending group and the list refreshes — the row visibly moves. Correct
  (it is no longer pending) but a perceptible jump; accepted, not smoothed.
- **Scope leak if a cancel point is missed.** If `onViewDestroyed` is not called on
  an input-view rebuild, the old scope's Room observer would linger. Mitigated by
  routing every teardown through `detachDormantVisibilityControllers` (the same
  path the other renderers use) and a unit test on the controller state machine.
- **Panel height vs. IME window.** The height is a screen fraction clamped to
  `[280dp, 400dp]`; the container's own bottom drives the root `wrap_content`.
  Device-specific window-resize behaviour (very short screens, split-screen) is
  only verifiable on a device — on the manual checklist.
- **Pending predicate ↔ SQL drift.** `isPendingInsertion()` and the
  `pagedHistoryPanel` ORDER-BY key must stay identical; the SQL cannot call the
  Kotlin, so a parity test is the only guard — if it is deleted, silent drift is
  possible.

## References

- **Related ADRs:**
  - ADR-0013 — Ambiguity Modes and the In-Keyboard Review Panel (the axis +
    LayoutMode precedent; the `REVIEW_REFINEMENT` carrier this ADR finally tags;
    review outranks history in `forKeyboard`)
  - ADR-0012 — Post-Processing Conversation (`getFinalOutput` returns the merged
    turn's output the panel inserts; history reprocess/regenerate unchanged)
  - ADR-0011 — Headless Completion Fallback (`getFinalOutput` text contract, the
    `committed=false` pending path, the shared `markInserted` acknowledge channel —
    all reused)
  - ADR-0009 — Ordered Run-Queue (the pending parts this panel re-inserts originate
    from the queue's `committed=false` deferrals)
  - ADR-0004 — LayoutCatalog + MotionLayout (the `KEYBOARD_HISTORY_PANEL` mode
    follows the review/staging precedent)
  - ADR-0001 / ADR-0002 — State module + cross-module cascade (the `historyPanel`
    axis and its auto-close cascade follow these)
- **Plan:** `tmp/plan-paket3-history-panel.md` (Paket 3 — implementation plan)
- **Database pattern:** `docs/DATABASE-PATTERNS.md` §"Double-Enum Pattern"
  (`sessions.origin` widened, `sessions.type` retrofitted)
- Implementation:
  - `database/dao/SessionDao.kt` (`pagedHistoryPanel`) + `history/KeyboardHistoryPager.kt`
    + `history/SessionRowPredicates.kt` (`isPendingInsertion` / `hasInsertableText`)
  - `database/entity/SessionOrigin.kt` (`REVIEW_REFINEMENT`) + `database/migration/MigrationTo9.kt`
    (`app/schemas/9.json`) + `core/ImePipelineConfigResolver.kt` (`FreshConfig.origin`)
  - `state/DictateUiState.kt` (`HistoryPanelState`) + `state/modules/HistoryPanelModule.kt`
  - `state/layout/LayoutCatalog.kt` (`KEYBOARD_HISTORY_PANEL`, `forKeyboard`) +
    `state/render/HistoryPanelRenderer.kt` + `history/KeyboardHistoryAdapter.kt`
    + `history/KeyboardHistoryController.kt`
  - `state/render/EditBarController.kt` (long-press) + `core/DictateInputMethodService.java`
    (toggle, insert handler, panel lifecycle) + `history/HistoryAdapter.kt` (refinement tag)
- Test suites:
  - `database/dao/SessionDaoHistoryPanelTest`, `history/SessionRowPredicatesTest` (order + parity)
  - `state/HistoryPanelModuleTest` (reducer + cascade), `state/layout/LayoutCatalogTest`
    (`forKeyboard` precedence), `state/layout/MotionSceneSchemaTest`
  - `ui/KeyboardHistoryPanelInflationTest`, `history/KeyboardHistoryAdapterBindTest`,
    `history/KeyboardHistoryControllerTest`
  - `database/migration/MigrationTo9Test` (device-only) + `MigrationTo9MetadataTest`

## Follow-ups (deliberately out of Paket 3)

- **"Send to Windows"** — the second per-row action; docks at the reserved GONE
  slot + the documented `Callback.onSendToWindows` hook. A later package.
- **"Continue dictating" from history** — the `continueConversationBlocking` engine
  (ADR-0013) is ready; a per-row affordance would seed a transcription-only
  recording targeting the chosen session and surface via `onReviewTurnCompleted`.
  Deferred (Eligibility-gate to `CONVERSATION_TURN` sessions; docks at the same
  action row).
- **"Other prompt" for a `CONVERSATION_TURN` regenerate** — a `HistoryDetailActivity`
  concern (not the panel); deferred as a small separate item.

## Decision History

### 2026-07-12 — Initial proposal

**Trigger:** The "in-keyboard history panel" work package (Paket 3) — the follow-up
to the ADR-0012 conversation foundation and the ADR-0013 review panel, which named
the history panel and Windows dispatch as later packages.

**Before:** The edit-bar history button opened only the full-screen `HistoryActivity`.
Paging lived exclusively in that activity's `ViewModel`. Uninserted completed
sessions surfaced only as transient pending parts. The ADR-0013 `REVIEW_REFINEMENT`
refinement carriers persisted as ordinary `KEYBOARD` recordings, indistinguishable
in history. `sessions.type` still carried its Double-Enum CHECK debt.

**After:** A short press toggles an in-keyboard history panel (its own
`historyPanel` axis + a taller-than-grid `KEYBOARD_HISTORY_PANEL` LayoutMode); a
long press keeps the full-screen activity. The panel reuses Room Paging via an
IME-owned scope + a lifecycle controller, lists the full history pending-first, and
re-inserts a result via `getFinalOutput` + the shared `markInserted` acknowledge
channel. A new `SessionOrigin.REVIEW_REFINEMENT` (schema v9) tags the S2 carriers
(hidden from the panel, tagged in the activity) and the same recreate discharges
the `sessions.type` CHECK debt. The ADR-0011/0012/0013 and ADR-0009/0004 contracts
are untouched.

**Reasoning:** Reusing Paging (over a non-paged snapshot) buys live updates for
free via Room invalidation and one shared load path; a caller-owned scope + a
tested controller is the smallest correct substitute for the `LifecycleOwner` the
IME is not. A dedicated `open`-only axis keeps the list (mutable, lifecycle-bound)
out of the immutable snapshot while still letting `forKeyboard` swap the surface. A
queryable `REVIEW_REFINEMENT` origin is the sustainable marker under the project's
Double-Enum discipline, and the CHECK-widening recreate discharges the last
`sessions` Double-Enum debt at no extra cost.
