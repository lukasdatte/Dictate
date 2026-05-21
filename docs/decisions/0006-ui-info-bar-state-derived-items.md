# ADR-0006: UI — Info-Bar State-Derived Items with Cross-Module Producers

**Status:** Proposed
**Subsystem:** ui-architecture, state-management
**Date:** 2026-05-21
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0001 (Modular Orchestrator) and ADR-0005 (Triangle-FSM).**
> ADR-0001 owns the modular state pattern that this ADR plugs a new
> module into. ADR-0005 owns the Keyboard/Widget/Hover ViewMode-FSM;
> this ADR defines how the Overlay surface transforms when info items
> exist (a "Pending-Items present" mode that suppresses the normal
> 5-button widget). This ADR also depends on ADR-0007 (Audio-File
> Repository) for the Pending-Recording dismiss semantics that delete
> the underlying segment files.

## Research

Three independent investigations produced this decision:

1. **Imperative status-quo audit (this session, 2026-05-21).**
   `core/InfoBarController.kt:54-167` carries nine hardcoded info-types
   (`update`, `rate`, `donate`, `timeout`, `invalid_api_key`,
   `quota_exceeded`, `model_not_found`, `bad_request`, `internet_error`)
   plus a parallel `overlay_permission_infobar` surface in
   `res/layout/activity_dictate_keyboard_view.xml:290-346` with its own
   renderer. Both paths mutate views directly via
   `showInfo()` / `dismiss()` from
   `DictateInputMethodService.java:4701,4773,5273,...` — no shared queue,
   no priority, no reactive subscription. Dismiss-callers proliferate
   (Z. 2068, 3894, 4001, 4773, 5273).

2. **Pending-state surface gap (this session).** The state layer already
   carries the data needed to drive an info-bar: `pendingSessions` in
   `PendingSessionsModule.kt:50` (RECORDED + COMPLETED-with-null-`inserted_at`
   rows) and `ResendModule.kt:13`'s `lastResultNeedsManualPaste` flag set
   by `PipelineRecovery.kt:155`. Neither field has a renderer. The
   user-reported symptom "wartender Text geht verloren wenn Tastatur weg
   ist" is a UI-only gap, not a persistence gap.

3. **User-architecture design session (this conversation, 2026-05-21).**
   The user articulated three explicit constraints: (a) items must
   surface as a function of state ("im Rahmen des App-Prozesses
   generiert, aber state-abgeleitet"), (b) dismiss must persist via the
   natural source of each item ("Pro Item-Typ natürliche Quelle
   modifizieren — kein einheitliches dismissed-Feld"), and (c) when
   pending items exist while the IME is hidden, the Overlay surface
   must lock down with a full-screen translucent hint rather than show
   the regular widget. These constraints rule out a stored-list +
   AddItem/DismissItem reducer pattern.

## Context

The Dictate IME has accreted multiple info-surfacing paths over its
lifetime:

- **`InfoBarController`** (`info_cl` view): imperative, 9 hardcoded
  cases, view-mutation per case
- **`overlay_permission_infobar`**: separate view + separate renderer
  (`OverlayPermissionInfobarRenderer`) for the SYSTEM_ALERT_WINDOW
  onboarding flow
- **`pendingSessions`**: backend-only state, never rendered
- **`lastResultNeedsManualPaste`**: backend-only state, never rendered

The user-reported symptoms ("Aufnahme verschwindet bei Rotate", "wartender
Text geht verloren", "Pipeline-Anzeige schmiert ab") root-cause to three
separate phenomena, only one of which is architecturally new:

1. Legacy `RecordingStateController.onKeyboardHidden()` pause-with-timeout
   call at `DictateInputMethodService.java:2050-2053` — dead code from a
   superseded controller, but still wired. **Audit + remove**, not a
   new architecture.
2. IME-view re-inflate after rotation does not re-hydrate
   recording/pipeline state into the freshly inflated views fast enough.
   **Lifecycle-bug**, addressed by hardening the re-bind path. Not
   architecture.
3. Pending sessions and manual-paste-needed have no UI surface. **This
   is the new architecture** — Info-Bar with priority queue derived
   from state.

Rather than build a fourth ad-hoc info surface, this ADR consolidates
all four (and future ones — `api-key-missing`, `recovery-acknowledged`,
etc.) into one state-derived selector pattern.

## Decision

### Items are a pure function of state

`InfoBarItem` is not a stored entity. The `InfoBarSelector` computes
a sorted `List<InfoBarItem>` from `DictateUiState` on every state emit:

```kotlin
object InfoBarSelector {
    fun select(state: DictateUiState): PersistentList<InfoBarItem> = buildList {
        // Pending-Insert items (one per COMPLETED + inserted_at == null session)
        state.pendingSessions
            .filter { it.status == SessionStatus.COMPLETED && it.insertedAt == null }
            .forEach { add(InfoBarItem.pendingInsert(it)) }

        // Pending-Recording items (one per RECORDED session)
        state.pendingSessions
            .filter { it.status == SessionStatus.RECORDED }
            .forEach { add(InfoBarItem.pendingRecording(it)) }

        // SP-driven items (Update / Rate / Donate / API-Key / Recovery)
        if (state.prefs.lastSeenVersionCode < BuildConfig.VERSION_CODE)
            add(InfoBarItem.update(state.prefs))
        if (!state.prefs.hasRated && eligibleForRatePrompt(state))
            add(InfoBarItem.rate(state.prefs))
        if (!state.prefs.hasApiKeyConfigured)
            add(InfoBarItem.apiKeyMissing(state.prefs))
        if (state.recovery.lastRecoveryUnacknowledged)
            add(InfoBarItem.recoveryAcknowledge(state.recovery))

        // Pipeline-error items (network / quota / model / etc.) — only
        // surface for non-FAILED-Session causes (transient errors that
        // never reached the session state). FAILED sessions have their
        // own Retry button (see Alternatives §4 "FAILED-Sessions").
        state.pipeline.transientError?.let { add(InfoBarItem.error(it)) }

        // Overlay-Permission-Onboarding
        if (state.overlay.onboardingPending) add(InfoBarItem.overlayPermission(state.overlay))
    }
        .sortedBy { it.createdAt }
        .toPersistentList()
}
```

Each item-factory packages a `messageKey: InfoBarMessage` (sealed class
for i18n + style), a `confirmAction: Action?`, a `dismissAction: Action`,
and a `createdAt: Long` derived from the source (session `createdAt`,
recovery timestamp, app-version-build-time for SP-only items).

### Dismiss = natural-source mutation

There is no shared `dismissed_items` table. Each item's `dismissAction`
mutates the source that caused the item to surface:

| Item type | dismissAction state mutation |
|---|---|
| Pending-Insert | `SessionDao.updateInsertedAt(id, now)` → item drops from `pendingSessions` filter |
| Pending-Recording | `SessionDao.updateStatus(id, CANCELLED)` + `AudioFileRepository.deleteAll(id)` |
| Update / Rate / Donate / API-Key | `Pref.LastSeenVersionCode` / `Pref.HasRated` / `Pref.HasApiKeyConfigured` written via `Effect.PersistPref` |
| Recovery-Acknowledge | `Pref.LastRecoveryAcknowledgedAt` written |
| Overlay-Permission | `Action.OverlayAction.DismissOverlayOnboarding` → `Pref.OverlayOnboardingDismissed` (already exists) |
| Pipeline-Transient-Error | `PipelineAction.ClearTransientError` (in-RAM, transient by definition) |

The selector re-runs on the next state emit, the item is gone from its
filter, and the renderer drops it. No double-bookkeeping.

### Items render via a state-driven renderer

`InfoBarRenderer` is a `SlotRenderer`-style component attached to the
`KeyboardLayoutManager` that:

1. Subscribes to `DictateUiStateStore.state`
2. Calls `InfoBarSelector.select(state)` on every emit
3. Mutex-toggles two parent containers:
   - `prompts_keyboard_cl` VISIBLE when items is empty
   - `info_cl` VISIBLE when items.nonEmpty, populated from `items[0]`
     (top of sorted list)
4. Wires `confirmAction` and `dismissAction` to the rendered buttons.
   Clicks dispatch the actions through the standard
   `onAction: (Action) -> Unit` sink.

When the user dispatches the confirmAction of item N, the state mutates,
the selector re-runs, item N drops out, item N+1 becomes the new top —
the renderer transparently follows. No manual queue management.

### Overlay surface transforms when items are pending

Per ADR-0005's Triangle-FSM the Overlay backend renders
`catalog.OVERLAY_5BUTTON` when `state.viewMode in {WIDGET, HOVER}`. This
ADR adds a second predicate to `KeyboardLayoutManager.modeForBackend`:

```kotlin
BackendType.OVERLAY_WINDOW -> when {
    InfoBarSelector.select(state).isNotEmpty() -> catalog.OVERLAY_BLOCK_HINT
    else -> catalog.OVERLAY_5BUTTON
}
```

`OVERLAY_BLOCK_HINT` is a new LayoutMode (catalog entry) that renders a
**translucent full-screen overlay** with a centered card listing the top
item, two buttons (Confirm + Dismiss per the item's action contract),
and a static message instructing the user to open the keyboard for full
control. The full-screen click-catcher is inert (no on-click handler) —
the user must explicitly tap one of the buttons, or open the IME in
another app. This satisfies the user's "User must react"-requirement
without forcing an Activity launch (which is brittle from a FGS).

### Cross-Module Producer pattern

The producers that generate items are not new modules — they are the
**existing** modules whose state the selector already reads. The
producer mechanism is implicit:

- `PendingSessionsModule` already owns the session-list; the selector
  reads it
- `PrefMirror` already mirrors SP into state; the selector reads
  `state.prefs`
- `OverlayModule` already owns `onboardingPending`; the selector reads
  it
- `PipelineModule` will gain a `transientError` field (new — separate
  from FAILED-status which routes to Retry-button)
- A new lightweight `RecoveryAcknowledgementState` axis tracks whether
  the user has acknowledged the most recent process-death-induced
  recovery — its `dismissAction` writes `Pref.LastRecoveryAcknowledgedAt`

No "InfoBar producer" classes exist. The state is the producer.

### Layout: Container-level mutex

The current `activity_dictate_keyboard_view.xml` already has both
candidates wired into the constraint chain:

- `info_cl` at Z. 215, currently `visibility="gone"`, constrained to
  parent-top
- `prompts_keyboard_cl` at Z. 500, constrained to
  `layout_constraintTop_toBottomOf="@id/info_cl"`

ConstraintLayout's "gone" handling (zero size + neighbors fall through
gone-margins) means the mutex works with **only a visibility flip** —
no constraint changes, no MotionLayout state. The renderer toggles
`info_cl.visibility` between VISIBLE and GONE; `prompts_keyboard_cl`'s
top-constraint resolves automatically.

The `prompt_recording_controls_ll` (Pause/Trash during recording) lives
**inside** `prompts_keyboard_cl` and is therefore mutex-swept along with
the chips. The user accepted this constraint explicitly: "Man hat ja
trotzdem weiterhin eigentlich immer irgendwo anders die Möglichkeit,
zumindest die Aufnahmepipeline zu pausieren" — the long-press on the
record-button (existing affordance) and the Overlay-Block-Hint's
"open IME" hint together cover the user's pause path.

### Big-Bang migration of the 9 legacy cases + `overlay_permission_infobar`

All 9 `InfoBarController` cases plus the `overlay_permission_infobar`
renderer are migrated to the producer pattern in a single coordinated
change:

1. Each case-specific SP-flag becomes the `dismissAction`'s natural
   source
2. The `showInfo("type")` call sites in `DictateInputMethodService` are
   replaced by state-mutations that the selector will observe
3. `InfoBarController.kt` and `OverlayPermissionInfobarRenderer.kt` are
   deleted
4. `info_cl` + `overlay_permission_infobar` views in the layout are
   consolidated to one `info_cl` (the existing structure is reused;
   `overlay_permission_infobar` is removed)

This is acceptable risk because the selector is pure (testable in
isolation per case) and the surfaces are visually one-or-zero (no
in-flight info during the swap window).

## Alternatives Considered

1. **Imperative `InfoBarController` (status quo).** Keep the existing
   imperative pattern, add new `showInfo("pending_insert")` and
   `showInfo("pending_recording")` cases. Rejected: the imperative
   pattern is not state-driven (a re-bind after process-death cannot
   re-derive the right info-bar from the current state), not testable
   (each case is a view-mutation tangle), and the parallel
   `overlay_permission_infobar` shows the pattern doesn't scale (the
   tenth case wanted its own surface).

2. **State-stored item list with `AddItem` / `DismissItem` reducer
   arms.** A traditional Redux-style list, items added by producers,
   removed by reducer when dismissed. Rejected: duplicates the source
   of truth (a Pending-Insert session is already in `pendingSessions`,
   storing the item separately means two facts to keep in sync), and
   dismiss requires both removing the item *and* mutating the source
   (otherwise the item re-surfaces on the next producer-emit). The
   user explicitly chose against this: "Pro Item-Typ natürliche Quelle
   modifizieren — kein einheitliches dismissed-Feld."

3. **Lambda-callback items (`onConfirm: () -> Unit`, `onDismiss: () -> Unit`).**
   Producers construct items with inline lambdas. Rejected: lambdas
   capture Context / View references, leak; not serializable for
   state-restore; not testable without Mockito (which the project
   already avoids per K-1).

4. **Sealed-class hierarchy per info-type (`InfoBarItem.PendingInsert /
   .Update / .Error / ...`).** Each type is its own subclass with
   typed fields. Rejected: scales poorly across the 10+ existing types
   plus future ones; every new type is a schema change in the central
   sealed class; the renderer's `when`-statement grows linearly. The
   hybrid chosen here (`InfoBarMessage` sealed for i18n + style,
   actions as plain `Action`) keeps the renderer generic and only the
   message-side typed.

5. **FAILED sessions as info-items.** Rejected: the Sessions-History
   already shows FAILED rows with a dedicated Retry button. Duplicating
   that affordance in the info-bar would conflict with the user's
   mental model ("FAILED hat ja schon einen eigenen Button").
   Pipeline *transient* errors (network drop mid-call, retryable
   provider hiccups) that never reach session-state are a separate
   class and *do* surface as info-items.

6. **Notification-only for pending items (no overlay-block).**
   Rejected: the user explicitly required "User muss reagieren" when
   pending items exist. A notification is dismissable in the
   notification shade without resolving the underlying state; the
   block-overlay forces the user to acknowledge.

## Consequences

### Positive

- **Single source of truth.** Each info-item's existence is a pure
  function of its underlying state. Producers cannot get out of sync
  with consumers because there is no second store.
- **Reactive across process-death.** `PipelineRecovery` rebuilds
  `pendingSessions` from the DB, the selector re-runs, items reappear
  automatically without explicit producer re-emit.
- **Testable.** `InfoBarSelector.select` is a pure function over
  `DictateUiState` — unit tests construct fake states, assert the
  expected items, no Android dependencies.
- **Extensible.** Adding a new info-type means (a) extend the relevant
  state axis to carry the trigger condition, (b) add a branch to the
  selector. No reducer changes, no renderer changes, no action plumbing.
- **Migration-clean.** The 9 legacy cases + `overlay_permission_infobar`
  collapse into one consistent surface; two delete-able classes
  (`InfoBarController.kt`, `OverlayPermissionInfobarRenderer.kt`) and
  one delete-able layout block disappear.

### Negative

- **Mental shift required.** Engineers used to "imperative show/hide"
  must internalize that info-bars surface because state holds a
  trigger, not because a method was called. Adding a new info-type
  means thinking "where does this trigger live in state?" not "where
  do I call `showInfo`?"
- **Selector must remain pure.** Side-effects in the selector
  (e.g. "while we're at it, also save a Pref...") would re-introduce
  the dual-source problem. The selector contract is read-only over
  state.

### Failure Modes

- **Forgotten dismissAction source.** If a producer adds an info-type
  whose dismissAction does not actually change the state that the
  selector filters on, dismissing the item leaves it on screen
  (the user-visible bug is "Dismiss-Button macht nichts"). Mitigation:
  the project's plan-quality-gate runs an audit; every new item-factory
  must specify the source-field-name in its KDoc, and a test
  `confirmAndDismissActuallyRemoveTheItem` is required per case.
- **createdAt drift.** Items derived from SharedPreferences (`update`,
  `rate`, `apiKeyMissing`) have no natural `createdAt` — they're
  app-state, not events. The selector uses
  `BuildConfig.VERSION_BUILD_TIME` as a stable proxy so Update-Hints
  don't oscillate in sort order across emits. Items with a true event
  origin (Pending-Insert from `session.createdAt`, Recovery from
  `recoveredAt`) get their authentic timestamp.
- **Block-Overlay race with permission revoke.** If the user revokes
  SYSTEM_ALERT_WINDOW while pending items exist, the OverlayBackend
  cannot show the block-hint. ADR-0005's existing `permission-loss`
  cascade re-routes to KEYBOARD ViewMode + emits a notification —
  this ADR adds a fallback: the FGS notification text mentions
  "X wartende Hinweise" when items exist at permission-loss time, so
  the user knows opening the IME will show them.

## References

- **Related Plan:** none (per user-decision: inline architecture
  discussion, no Plan-File; implementation tracked via commits)
- **Cooperates with ADR-0007:** Multi-File Audio Repository — the
  Pending-Recording item's dismiss-action calls
  `AudioFileRepository.deleteAll(sessionId)`; the source-of-truth for
  segment files lives there.
- Architecture docs to be added:
  `docs/architecture/info-bar-and-pending-affordances.md` (post-impl)
- Implementation: commits with tag `[infobar-architecture]` in
  branch `feature/dictate-keyboard-layout-refactor` from 2026-05-21
  onward.
- Layout: `app/src/main/res/layout/activity_dictate_keyboard_view.xml`
  (`info_cl` Z. 215, `prompts_keyboard_cl` Z. 499)
- Legacy code being replaced:
  - `app/src/main/java/net/devemperor/dictate/core/InfoBarController.kt`
    (whole class, 168 lines)
  - `app/src/main/java/net/devemperor/dictate/core/OverlayPermissionInfobarRenderer.kt`
    (deletion)
  - `app/src/main/res/layout/activity_dictate_keyboard_view.xml:290-346`
    (`overlay_permission_infobar` block)
  - 11 `showInfo(...)` / `dismiss()` call-sites in
    `DictateInputMethodService.java`
- Related ADRs:
  - ADR-0001 (Modular Orchestrator) — InfoBar is a new state-derived
    surface on top of the modular store
  - ADR-0002 (Cross-Module Cascade) — producers leverage existing
    cross-module observation; no new cascade primitive
  - ADR-0005 (Triangle-FSM) — Overlay surface gains the
    `OVERLAY_BLOCK_HINT` mode that suppresses `OVERLAY_5BUTTON` when
    items exist
  - ADR-0007 (Audio File Repository) — Pending-Recording dismiss
    semantics

## Decision History

### 2026-05-21 — Initial proposal

**Trigger:** Architecture discussion driven by user-reported symptoms
("Aufnahme verschwindet bei Rotate", "wartender Text geht verloren") +
the gap that `pendingSessions` is backend-only state with no renderer.

**Before:** Two parallel imperative info surfaces
(`InfoBarController` + `overlay_permission_infobar`), nine hardcoded
case types, no queue, dismiss-state per-case-ad-hoc. Pending sessions
and manual-paste hints never reach the UI.

**After:** One `InfoBarSelector` pure function over `DictateUiState`
produces a sorted item list on every emit. Items render through a
single `InfoBarRenderer` reactive on the store. Dismiss mutates each
item's natural source field. Overlay surface gains an
`OVERLAY_BLOCK_HINT` mode that suppresses the regular 5-button widget
when items exist and forces the user to open the IME (or dismiss per
item via the overlay's dismiss buttons).

**Reasoning:** The state-derived approach was the only design that
satisfied all four user constraints simultaneously
— (a) parallel items sorted by creation, (b) items resurface until
explicitly resolved, (c) dismiss persists via natural source, and
(d) reactive across process-death without producer re-emit. The
imperative status quo could not be extended to satisfy (b) and (d)
without re-architecting anyway; doing it once cleanly costs less than
two iterative migrations.
