# ADR-0003: Service — Foreground Pipeline Architecture

**Status:** Accepted
**Subsystem:** service
**Scope:** Project-Wide
**Date:** 2026-05-14
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0001.** ADR-0001 defines the `DictateOrchestrator`
> and module registry as the in-process state-mutation pattern. This ADR
> defines the **container** that hosts the orchestrator: a Foreground
> Service distinct from the IME-Service, so the orchestrator outlives
> keyboard switches.

## Research

The Foreground-Service container is the response to a concrete product
requirement and a concrete IME-Service-lifecycle constraint:

- Plan §1.2 (user-requirement iteration): "Tastatur-Wechsel-Survival —
  Recording/Pipeline soll weiterlaufen, wenn der User auf eine andere
  Tastatur wechselt (z.B. Gboard für ein Passwort-Feld) und später
  zurückkommt." The IME-Service does not survive keyboard-switch —
  it gets `onDestroy()`-ed by the OS. State held inside the IME is gone.
- Phase-2 research `_pending-ime-lifecycle-view-recreation.md`
  (referenced from plan §1.3) confirmed: the IME-Service has a
  View-Recreate lifecycle and **does not use coroutines today**
  (a clean slate for the state container). No WorkManager dependency
  exists either.
- Phase-2 research `_pending-persistence-background-architecture.md`
  (plan §1.3) confirmed: Room v3 with a `sessions` table already
  exists, with a `RECORDED` status that fits the
  recovery-after-process-death flow. **Only one new column**
  (`inserted_at`) is needed for the persistence-side adapter
  — no new table.
- Spec 1 §7 + §11.1 — the Foreground-Service lifecycle, FGS-Type
  requirements for Android 14+ (type=microphone), the 5-second
  `startForeground` deadline, and the bound-service setup.
- Android docs (referenced from Spec 1 §12 External References):
  - Foreground Service Lifecycle: https://developer.android.com/develop/background-work/services/foreground-services
  - FGS Types ab Android 14: https://developer.android.com/about/versions/14/changes/fgs-types-required
  - Bound Services: https://developer.android.com/develop/background-work/services/bound-services

## Context

The product requirement (Tastatur-Wechsel-Survival) plus the IME
Service's lifecycle (it dies on keyboard-switch) plus the existing
`RecordingManager` + audio-pipeline machinery (live in the IME-Service
today, plan §3.2) leave one structural answer: extract the persistent
state-and-orchestration layer into a separate process-resident
service. Three sub-questions then needed answering:

1. **Foreground-Service or background-Service?** Background services
   get killed within seconds. Foreground services need a notification
   but stay alive — and the notification doubles as a user-facing
   status indicator.
2. **WorkManager or no?** WorkManager would let us schedule the
   pipeline restart on OOM-death. But Spec 1 §6 + Phase-2 research
   `_pending-persistence-background-architecture.md` showed: the
   audio pipeline is interactive (user is waiting). User-controlled
   resume from DB is the better UX than auto-resume (which would
   surprise users).
3. **One process or two?** Android allows binding services across
   processes via AIDL or Messenger. Both add IPC marshalling cost
   for state snapshots that are emitted at 60 Hz when recording is
   active. Same-process Local Binder is free.

## Decision

Pipeline state lives in a dedicated `DictatePipelineService`
(Foreground Service, `type=microphone`) in the **same app process**
as the IME-Service. Communication uses **LocalBinder + `StateFlow`
(no IPC)**. **No WorkManager** is used. On OOM-Death, recovery is
**via DB-replay + manual User-Resume**. A persistent notification is
mandatory (FGS contract) and doubles as a status UI.

### Scope of this Convention

Project-wide for the **pipeline lifecycle layer** — `DictatePipelineService`,
the `LocalBinder` it exposes, and the IME-Service's bind/unbind
discipline. Out of scope:

- The legacy `core.PipelineOrchestrator` (audio-pipeline runner)
  which remains an in-process collaborator referenced via
  `JobExecutor` (plan §7.1 Out-of-Scope acknowledges the naming
  conflict).
- Future `STANDALONE_OVERLAY-Service` (Spec 1 §14 Open-Q 5+6
  + plan §7.1 Phase-2 backlog) — that would be a second service,
  independent of `DictatePipelineService`.
- Settings/Preferences and onboarding activities — they remain in
  their existing UI layer.

### Required mechanics (binding contract for Block 2…6)

1. **FGS type=microphone.** `AndroidManifest.xml` declares
   `<service android:name=".pipeline.DictatePipelineService"
   android:foregroundServiceType="microphone" android:exported="false" />`.
   `FOREGROUND_SERVICE_MICROPHONE` permission is declared. Targets
   Android 14+ (compile/target sdk 35).
2. **`startForeground` within 5 s.** `onStartCommand` calls
   `startForeground(NOTIF_ID, notifCoordinator.buildInitial())` as
   the very first non-no-op step. The notification channel must
   exist before the call (created in `onCreate`).
3. **Local Binder, same process.** `onBind` returns a
   `LocalBinder(orchestrator)` exposing exactly two surfaces:
   `state: StateFlow<DictateUiState>` and
   `dispatch(action: Action): DispatchOutcome` (plus lifecycle
   hooks). No forwarder methods (ADR-0001 §"Required mechanics"
   item 1 forbids them).
4. **Bind from `onCreateInputView`, not `onCreate` of the IME.**
   `onCreate` of the IME can run before the first view inflation
   (some OEM-IME settings flows trigger it). Binding then would
   start FGS too early. `onCreateInputView` is the first hook where
   "user actually wants the keyboard" is true.
5. **No WorkManager dependency.** No `androidx.work:work-runtime`
   in `app/build.gradle`. The audio-pipeline does not schedule
   background work via WorkManager.
6. **Recovery via DB-replay.** On service `onCreate`, the
   `PipelineRecovery` (Spec 1 §4.6) reads pending sessions from
   Room and populates the store. On OOM-death, the service is not
   auto-restarted — the next user interaction (open keyboard)
   triggers IME `onCreateInputView` → bind → service `onCreate` →
   replay.
7. **Persistent notification with action buttons.** The notification
   is built by `PipelineNotificationCoordinator` (Spec 1 §7.4) and
   carries action buttons (Send / Trash / Pause / Resume / Cancel)
   that dispatch via `PipelineActionRouter`. It is the only
   user-facing surface during keyboard-switch.
8. **Service `onDestroy` order: `orchestrator.shutdown()` BEFORE
   `serviceScope.cancel()`.** Module `terminate(services)` calls
   require `services.scope.isActive == true`. Inverting the order
   silently no-ops async cleanup. Wrap `shutdown()` in
   `runBlocking { withTimeout(2_000L) { … } }` to cap OS-killed
   pathological cases (Spec 1 §7.3 onDestroy).
9. **Pre-Cancel-Dispatch on `onDestroy`.** If recording or pipeline
   is active at destroy time, dispatch
   `Action.RecordingAction.CancelRecording` (or `…PipelineAction.CancelPipeline`)
   first so the FSM's normal cleanup effects run (e.g.
   `Effect.ReleaseMediaRecorder`). Default `module.terminate()` is
   a no-op (Spec 1 §4.2), so leaking the MediaRecorder is the
   default if we skip this step.

### Service-layer diagram

```
╔══════════════════════════════════════════════════════════════════════╗
║                  APP MAIN PROCESS (always the same)                  ║
║                                                                      ║
║  ┌────────────────────────────────────────────────────────────────┐ ║
║  │           DictatePipelineService (Foreground, type=microphone) │ ║
║  │   — survives keyboard switches; persistent notification         │ ║
║  │                                                                 │ ║
║  │   DictateOrchestrator (Composition Root, single dispatch)       │ ║
║  │     dispatch(action: Action) → module-registry routing          │ ║
║  │                                                                 │ ║
║  │   Co-aggregates (helpers, F-11):                                │ ║
║  │     DictateUiStateStore  (StateFlow owner, _state holder)       │ ║
║  │     PipelinePrefMirror   (SP ↔ store mirror)                    │ ║
║  │     PipelineRecovery     (DB replay)                            │ ║
║  │     ModuleServicesFactory (DI container)                        │ ║
║  │                                                                 │ ║
║  │   PipelineNotificationCoordinator + PipelineActionRouter        │ ║
║  │                                                                 │ ║
║  │   RoomDatabase (sessions + 1 new column: inserted_at)           │ ║
║  └────────────────────────────────────────────────────────────────┘ ║
║                          ▲                                          ║
║                          │ LocalBinder (no IPC, same process)        ║
║                          │  state: StateFlow<DictateUiState>          ║
║                          │  dispatch(action: Action): DispatchOutcome ║
║                          ▼                                          ║
║  ┌───────────────────────────────────────────────────────────────┐  ║
║  │            DictateInputMethodService (IME)                     │  ║
║  │  — comes and goes per keyboard selection                       │  ║
║  │                                                                │  ║
║  │   KeyboardLayoutManager (Triangle-FSM, render orchestrator)    │  ║
║  │     state.collect { render(state) }                            │  ║
║  │                                                                │  ║
║  │   ImeViewBackend (KEYBOARD mode)                               │  ║
║  │   OverlayBackend (WIDGET + HOVER, both use it)                 │  ║
║  └────────────────────────────────────────────────────────────────┘  ║
╚══════════════════════════════════════════════════════════════════════╝
```

## Alternatives Considered

1. **Keep pipeline state in the IME-Service.** The status quo.
   Rejected because the IME-Service does not survive keyboard
   switches (plan §1.2 product requirement). The
   "Tastatur-Wechsel-Survival" use case fails entirely.
2. **WorkManager for pipeline-after-keyboard-switch.** Rejected
   because the audio-pipeline is interactive — the user is
   waiting for transcription to appear. Auto-resume after
   process death would surprise users (audio they already
   moved past suddenly being transcribed). Manual resume from
   DB is the correct UX (plan §7 OPEN-4 resolution).
3. **AIDL-based service-IPC, possibly cross-process.** Rejected
   because the IME-Service and pipeline-service have no
   security boundary (they're the same app, same trust domain).
   IPC marshalling at 60 Hz during recording is gratuitous
   cost. Local Binder + same-process delivers the same isolation
   benefit (separate lifecycle) without the cost.
4. **STANDALONE_OVERLAY-Service** that hosts the overlay
   window independently of the IME. Considered for the
   HOVER-without-IME edge case. Rejected for Phase 1 (plan
   §7.1 Out-of-Scope) — the IME-Service-onDestroy-cleanup
   handles overlay teardown well enough. A second FGS
   notification would be invasive.
5. **`type=dataSync` or no-type FGS.** Rejected because Android
   14+ enforces a typed FGS that matches the actual work. The
   service captures microphone audio (via `RecordingHardware`
   in the audio-pipeline), so `type=microphone` is the
   correct semantic.

## Consequences

**Positive:**

- Pipeline state survives keyboard-switch by construction.
  The user can switch to Gboard, fill in a password, switch
  back — the transcript that was in flight is still being
  produced.
- LocalBinder + `StateFlow` keeps the IME-side render layer
  identical to the in-process state container — no
  serialization, no marshalling, no schema versioning.
- The Foreground-Service notification is **both** an Android FGS requirement
  AND a status indicator. Two requirements satisfied with one
  surface.
- DB-replay-based recovery is testable in isolation (Room
  migration tests in Block 3) and gives the user agency on
  resume.
- The service container hosts the modular orchestrator from
  ADR-0001 — the two ADRs compose: ADR-0001 says "modular
  state mutation", ADR-0003 says "where that state lives".

**Negative:**

- An always-on notification is a UX cost during recording
  (plan §6 Risiko-Tabelle). Mitigation: the notification
  carries useful action buttons (Send / Trash / Pause) so
  it's a feature, not just a permission tax.
- Two services to reason about (IME-Service + pipeline-service).
  The lifecycle interaction is non-trivial — Spec 1 §11.3
  walks through bind/unbind timing and Spec 1 §8.x covers
  the View-Recreate contract. Documentation cost is real;
  worth it for the survival guarantee.
- OOM-death recovery requires user interaction. A user who
  closes the app entirely and comes back will see a "resume?"
  affordance, not auto-restored state. This is a deliberate
  UX trade-off: surprise-free resume.

**Failure Modes:**

- **`startForeground` not called within 5 s.** Android kills the
  service with `RemoteServiceException`. Mitigation: `onStartCommand`
  calls `startForeground` as step 2 (after action-intent routing,
  before reactive updates). The notification channel must already
  exist (Spec 1 §11.1.4 ensures it via `ensureNotificationChannel`
  in `onCreate`).
- **Permission `FOREGROUND_SERVICE_MICROPHONE` missing.** The
  service start throws `SecurityException`. Mitigation: declared
  in `AndroidManifest.xml`; release-build smoke test
  (`PipelineServiceReleaseSmokeTest`, Block 2) starts and stops
  the service.
- **OS-killed service without restart.** If the user switches away
  from the app and back, the IME's `onCreateInputView` triggers
  a fresh `bindService` + `startForegroundService`. State is
  re-initialised from `PipelineRecovery`. If recovery fails
  (corrupt DB row), the user sees the initial state and the
  pending session is lost (logged at error severity). This is
  acceptable failure semantics — better than a zombie
  half-state.
- **`onDestroy` order wrong** (`serviceScope.cancel()` before
  `shutdown()`). Async cleanup steps silently no-op; only
  synchronous hardware releases run. Mitigation: the order is
  spelled out in the `shutdown()` KDoc (Spec 1 §4.3
  "Aufrufer-Vertrag Phase-B S-4") and verified by
  `OrchestratorShutdownOrderTest.kt`.
- **Pre-Cancel-Dispatch skipped.** If `Service.onDestroy` runs
  with recording active and we don't dispatch
  `Action.RecordingAction.CancelRecording` first, the
  MediaRecorder leaks (default module `terminate()` is a
  no-op). Mitigation: the explicit pre-cancel block in
  `onDestroy` (Spec 1 §7.3 Phase-C C-2) + a Block-2-acceptance
  manual test "force-stop the app during recording, observe
  no native-heap leak".

## References

- **Related Plans:**
  - [dictate-keyboard-layout-refactor](../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md) §3.2, §7 OPEN-4, §4.0.1.0 — the plan that motivated this ADR.
  - [dictate-cutover-completion](../plans/2026-05-15%20-%20dictate-cutover-completion/dictate-cutover-completion.md) — the Epic that wired the real `PipelineNotificationCoordinator` + `PipelineActionRouter` and the BT-SCO/audio-focus Preparing-lifecycle (see Decision History 2026-05-17). §8 of that plan references this ADR (bidirectional).
- **Related Spec:** [Spec 1 — Pipeline-Service](../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md) §1, §7 (FGS lifecycle), §7.2, §7.3, §7.4 (NotificationCoordinator), §7.5 (ActionRouter), §7.6, §11.1 (FGS details), §11.1.2, §11.3 (Bound-Service setup), §11.6 (OOM-Death recovery)
- **Related ADRs:**
  - **ADR-0001 — state-modular-orchestrator-pattern.** This ADR hosts ADR-0001's `DictateOrchestrator` + module registry inside a Foreground Service. The two ADRs compose: ADR-0001 says how state mutates; this ADR says where the mutation lives so that it survives keyboard switches.
  - **[ADR-0002 — Cross-Module Cascade](0002-state-cross-module-cascade.md)** — cascade-protocol the orchestrator running inside this FGS enforces.
  - **[ADR-0004 — UI LayoutCatalog + MotionLayout](0004-ui-layout-catalog-motionlayout.md)** — UI layer the service-hosted orchestrator publishes state to.
  - **ADR-0005 — ui-triangle-fsm-keyboard-widget-hover.** The HOVER mode is structurally enabled by this ADR: HOVER means "IME-View is gone but pipeline is still running" — only possible because the pipeline-service outlives the IME-Service. The "Geist-Widget" failure mode (HOVER hanging after pipeline-done) is structurally guarded in ADR-0005 §T7 cascade.
- **Architecture docs:**
  - [state-architecture/effects-and-failures.md](../architecture/state-architecture/effects-and-failures.md)
  - [state-architecture/modules.md](../architecture/state-architecture/modules.md)
  - [state-architecture/README.md](../architecture/state-architecture/README.md)
- **Skill:** `~/.claude/skills/knowledge-adr-format/SKILL.md`

## Supersede Triggers (Forward-Looking Notes)

This ADR is likely to be superseded in two scenarios:

- **WorkManager adoption.** If a use case arrives where
  background pipeline work needs to survive process death and
  re-run autonomously (e.g. "transcribe captured audio when the
  device is on Wi-Fi"), WorkManager becomes attractive. The
  supersede would add a `androidx.work` dependency, keep the
  Foreground Service for live pipeline runs, and route
  background runs to a Worker. Specifically: this ADR would be
  superseded by ADR-NNNN-pipeline-workmanager-hybrid.
- **STANDALONE_OVERLAY-Service.** If the overlay needs to live
  independently of the IME (e.g. foldable outer-display
  use case), a second FGS would be introduced. This ADR
  stays valid for the pipeline-service; a new ADR adds the
  second service as a peer. The plan §7.1 backlog entry is the
  trigger.

A full supersede would mean a different lifecycle model — e.g.
moving the state container out of an Android Service entirely
(e.g. a JobService scheduler with persistent state via Room).
Not anticipated for Phase 2.

## Decision History

### 2026-05-17 — Real notification coordinator + action-router; BT-SCO/audio-focus Preparing-lifecycle (Epic dictate-cutover-completion)

**Trigger:** Epic `dictate-cutover-completion` Theme-B (the INT-1 cutover follow-up). The new-path notification surface was the dormant half of the parallel-dormant layer; B2-VAL-W1 additionally surfaced a Critical BT-SCO already-connected hang (F-1) + an audio-focus reacquire gap (F-2). Code-verified in `reports/integration-check.md` Central Verdict §1, `reports/B2-theme-b-recording-drive.md`.

**Before:** `ModuleServices.notificationCoordinator` was a `Log.w` no-op stub. The Spec 1 §7.4 persistent FGS notification and §7.5 action-button back-channel were unimplemented on the new path; the **legacy** notification path was the only surface delivering Spec 1 §10 Block-2 acceptance — so the FGS-container ADR's whole point (recording survives a keyboard switch with a usable surface) was, in production, served only by the legacy path while the new coordinator was inert.

**After:** Real `PipelineNotificationCoordinator` (Spec 1 §7.4/§7.6/§11.1.2 — single-source-of-truth `NOTIF_ID = 0xD1C7A7E`, `buildInitial()`, `show`/`dismiss`, channel-reuse) plus `PipelineActionRouter` (Spec 1 §7.5 — `[Pause]/[Stopp]/[Senden]` PendingIntents → `orchestrator.dispatch`, targeting the FGS so the buttons work while the IME-view is dead — the keyboard-switch-survival point of this ADR). Both are constructed and bound in `DictatePipelineService.onCreate` Step 4; the `notificationCoordinator` stub is demoted to `@Deprecated(WARNING)` test-only. The BT-SCO / audio-focus handshake is now confined to the `Preparing` state in `AudioModule`: the F-1 already-connected hang is fixed by priming to `Waiting` (so an already-connected SCO route does not deadlock the Preparing→Active transition) and F-2 adds a `ReacquireAudioFocus` step; see `research/recording-audiofocus-btsco-handshake.md` for the B2 R-1 handshake derivation.

**Reasoning:** The FGS-container ADR exists so recording survives a keyboard switch *with a user-facing surface*; that guarantee was only delivered by the legacy notification path while the new coordinator was a no-op stub — the ADR-0003 contract was nominally met but production-inert (the INT-1 parallel-dormant anti-pattern, service half). This entry records the real coordinator/router as the production notification + back-channel surface and the BT-SCO/audio-focus lifecycle as Preparing-state-confined. This implements the ADR's §"Required mechanics" items 2/7 on the new path; it is an append, not a supersede — the lifecycle model is unchanged (no WorkManager, LocalBinder same-process, DB-replay recovery all stand).

**References:**
- `docs/plans/2026-05-15 - dictate-cutover-completion/dictate-cutover-completion.md` (Epic — §8 references this ADR, bidirectional)
- `docs/plans/2026-05-15 - dictate-cutover-completion/reports/B2-theme-b-recording-drive.md` (B2-VAL-W1 F-1/F-2)
- `docs/plans/2026-05-15 - dictate-cutover-completion/research/recording-audiofocus-btsco-handshake.md`
- `docs/plans/2026-05-15 - dictate-cutover-completion/reports/integration-check.md` Central Verdict §1
- Spec 1 §7.4 / §7.5 / §7.6 / §11.1.2

### 2026-05-15 — Cleanup-policy + FK-cascade semantics (B3-VAL-REPAIR)

**Trigger:** B3 block-audit findings F-1 (parent-cascade wipes
children) + F-2 (M3→M4 backfill makes pre-existing history
immediately deletable). Both are Critical silent-data-loss bugs
discovered during the B3 sanity-pass on the worktree's M4 migration
implementation (pre-merge, no users shipped).

**Before:**
- `SessionEntity.parent_session_id` declared `ForeignKey.CASCADE`,
  allowing `deleteInsertedOlderThan` (idle-stop slot) to wipe
  POST_PROCESSING children of an aged-out parent. Concrete scenario:
  user records text on day 1, applies a translate-prompt on day 8 —
  the day-1 parent ages out, cascade wipes the day-8 child created
  five minutes ago.
- `MigrationTo4.kt` backfilled `inserted_at = created_at` for
  pre-existing COMPLETED rows, exposing months of pre-M4 history to
  the 7-day cleanup at the first idle-stop after upgrade (~500
  sessions silently wiped for a multi-month user on first boot
  after installing v4).

**After:**
- FK is `ForeignKey.SET_NULL`. Children become root-level history
  items when their parent is deleted by any row-level DELETE
  pathway (cleanup, user-driven, future paths). The history UI
  shows sessions as a flat list — no UX regression for the lost
  parent-child link.
- Migration backfill is `inserted_at = NULL` for all pre-existing
  rows. NULL-semantics = "the cleanup-marker does not apply to
  this row"; immune to `deleteInsertedOlderThan` (which already
  filters `inserted_at IS NOT NULL`).
- New `Pref.PendingInsertionFreshnessMs` (default 24h) gates
  `findPendingInsertion` so legacy pre-M4 COMPLETED rows don't flood
  the manual-paste notification surface on the first post-upgrade
  boot.
- M4 is amended in place (no MIGRATION_4_5) because the worktree
  has not shipped to any user device.

**Reasoning:** Both fixes converge on NULL-as-unknown semantics for
"this row is alive but the cleanup-marker does not apply". The
data-preservation guarantee outweighs the lost parent-child link
(history UI shows sessions as a flat list anyway). Alternative
options (FK RESTRICT, NOT EXISTS sub-query, sentinel value,
is_legacy column, migration-timestamp pref) were evaluated in
`docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/b3-cleanup-cascade-and-backfill-policy.md`
§§3-4 and rejected on data-preservation, complexity, or
Kotlin-idiomaticity grounds.

**References:**
- `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/b3-cleanup-cascade-and-backfill-policy.md` (full research + alternatives)
- `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/reports/validated-findings-B3.md` F-1 + F-2
- Spec 1 §6.1 + §6.2 R.17 + §6.3 + §6.5 (canonical post-fix)

### 2026-05-14 — Accepted

**Trigger:** Block-0 audit-consolidation pass (B0-VAL-SANITY) — plan §4.0 binding-pre-code-contract closeout.

**Before:** Status: Proposed (per §4.0.1.0.3 lifecycle clause "Proposed during Block 0").

**After:** Status: Accepted (body now append-only per knowledge-adr-format §"Lifecycle and editing rules").

**Reasoning:** Block-0 acceptance criteria from plan §4.0.3 met; B0-AUDIT-PLAN-AND-API + B0-AUDIT-CONVENTION pass; ADR binds downstream Blocks 1b…6 per plan §4.0.4 "Bindender-Vertrag-Charakter".

### 2026-05-14 — Block-0 doc-set audit cleanup (B0-VAL-REPAIR)

### 2026-05-14 — F-1 / F-5 / F-11 post-review pass (ADR-0003 reviewed.md)

**Trigger:** Validated findings F-5 (German-language leakage), F-11 (Phase-2 Superseding placement), F-1 (inter-ADR cross-reference completion).

**Before:** Body prose contained `Android-FGS-Pflicht` (F-5 German leakage). The "Phase-2 Superseding Expectations" block lived inside `## Decision History` (F-11). References → Related ADRs listed only ADR-0001 + ADR-0005 (F-1: ADR-0002 + ADR-0004 missing).

**After:** `Android-FGS-Pflicht` → `Android FGS requirement` (body prose anglicised; load-bearing German Spec citations remain). Phase-2-Superseding moved to new top-level section `## Supersede Triggers (Forward-Looking Notes)` between `## References` and `## Decision History`. Related-ADRs list completed with ADR-0002 + ADR-0004 (bidirectional graph).

**Reasoning:** Language convention (`~/.claude/snippets/docs/language-conventions.md`) requires English-only body prose in docs declared English. Forward-looking content belongs outside the post-Accepted append-only `Decision History` body. Plan §4.0.1.0.2 demands universal inter-ADR cross-references, not the direct-edge subset.

### 2026-05-14 — Initial proposal

**Trigger:** Plan §3.2 + §4.0.1.0 mandate a service-foreground
architecture as a binding pre-code contract. The product requirement
(plan §1.2 "Tastatur-Wechsel-Survival") + the IME-Service-lifecycle
analysis (Phase-2 research `_pending-ime-lifecycle-view-recreation.md`)
force a separate container.

**Before:** Pipeline state lived inside the IME-Service and died on
keyboard-switch. No persistence-based recovery existed; OOM-death
left a zombie half-state in DB.

**After:** `DictatePipelineService` (FGS type=microphone) hosts the
orchestrator + 13 modules + Room database. LocalBinder for in-process
communication. DB-replay-based recovery on `onCreate`; manual
User-Resume on OOM-death. No WorkManager.

**Reasoning:** The user requirement is non-negotiable
(keyboard-switch is a frequent flow — Gboard for password fields
specifically). LocalBinder + same-process keeps marshalling cost at
zero and matches the "two services, same trust domain" model
naturally. WorkManager would have meant auto-resume which surprised
users (plan §7 OPEN-4). The Foreground-Service notification is
a tax we accept because it doubles as the only user-facing surface
during keyboard-switch (where the IME-View is gone).
