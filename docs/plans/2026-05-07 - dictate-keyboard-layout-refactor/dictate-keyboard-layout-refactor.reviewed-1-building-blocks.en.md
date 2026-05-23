## 4. Building Blocks (Implementation Order)

> Translation of source §4 (lines 229–1123). See
> `dictate-keyboard-layout-refactor.reviewed-0-overview.en.md` for the
> split map and §1–§3.

| # | Block | Spec | Brief description | Complexity |
|---|-------|------|---------------------|-------------|
| **0** | **Architecture Doc + ADR** | §4.0 (this document) | Create ADR `docs/decisions/NNNN-modular-orchestrator-architecture.md` + architecture doc `docs/architecture/state-architecture/`. Anchors the modular-orchestrator pattern (composition root + 13 modules + single-dispatch + pure-reducer invariant + cross-module cascade) project-wide. Mandatory reading source for all following blocks. **Binding contract, not description.** | small |
| **1a** | **Quick-wins in today's code** | [Spec 1: Pipeline-Service](research/1-pipeline-service/1-pipeline-service.md) §11.2.2 + Spec 2 §13.5 Gap 5 | Consolidate the `predResendVisible` helper; switch all 6 resend_btn mutations to the helper; resolve the `recordButton.text/isEnabled` hybrid — **in today's code, without module architecture, compile-green** | small-medium |
| 2 | **DictatePipelineService skeleton** | [Spec 1: Pipeline-Service](research/1-pipeline-service/1-pipeline-service.md) | Service skeleton, FGS, ServiceScope, LocalBinder, persistent notification | medium |
| **1b** | **DictateUiState + DictateOrchestrator + 13 modules** | [Spec 1: Pipeline-Service](research/1-pipeline-service/1-pipeline-service.md) §3 + §4 + §15 | Hierarchical DictateUiState, DictateOrchestrator, all 13 modules (Recording, Pipeline, Audio, ViewMode, Overlay, Resend, LivePrompt, Language, Layout, FeatureToggle, Theming, PendingSessions, Interruption-Phase-2), Action sealed hierarchy — **in the PipelineService container** | large |
| 3 | **Subsystem-adapter migration** | [Spec 1](research/1-pipeline-service/1-pipeline-service.md) | LanguageController, BluetoothScoManager, AudioFocus → module migration | medium |
| 4 | **RecordingHardwareSubsystem** | [Spec 1](research/1-pipeline-service/1-pipeline-service.md) | RecordingManager → RecordingHardwareSubsystem adapter, audioFile in state (R.2) | medium |
| 5 | **LayoutCatalog + ImeViewBackend** | [Spec 2: Keyboard-Layout](research/2-keyboard-layout/2-keyboard-layout.md) | KeyboardLayoutManager, LayoutCatalog, MotionScene, VISIBILITY_MODE_IGNORE, RecordingAnimationController, ContentAreaController + PromptVisibilityController + OverlayResetHandler (R.10) | large |
| 6 | **OverlayBackend (WIDGET + HOVER)** | [Spec 3: Floating-Overlay](research/3-floating-overlay/3-floating-overlay.md) | Overlay XML, WindowManager integration, permission observer, close-button differential, mode transitions, drag lifecycle (R.18, R.19) | medium-large |

**Order:** **0** → **1a** → 2 → **1b** → 3 → 4 → 5 → 6. Block 0 is the written anchoring of the architecture as a binding contract — ADR + architecture doc are created BEFORE the code migration, so that all following blocks have a binding reading anchor (and Block-1b implementers do not decide from the gut what "pure-reducer" or "cross-module-cascade" means). Block 1a (quick-wins in today's code) stays after Block 0, because 1a does not yet need the architecture — but the ADR context helps in case an architecture question comes up in 1a. Block 1b (DictateUiState + module build-up) comes **after** Block 2, because it needs the PipelineService container.

**Risk (new):** Block 1a quick-wins ↔ Block 1b module build-up without a split would create a race condition for visibility mutations during the migration. The split eliminates that structurally.

### 4.0 Block 0 — Architecture Doc + ADR (mandatory reading anchor)

Block 0 produces two artifacts that must exist BEFORE all code blocks:

#### 4.0.1 ADRs (5 of them) in `docs/decisions/`

**Purpose:** Binding architecture contract, **split into five standalone ADRs**, so that
later Phase-2 extensions can change individual decisions via superseding without
calling the overall architecture into question (e.g. allow Mode 3, a fourth ViewMode,
WorkManager adoption).

##### 4.0.1.0 ADR Overview

| # | ADR slug | Subsystem | Decision core statement (goes into §2 "Decision") |
|---|---|---|---|
| 1 | `state-modular-orchestrator-pattern` | `state` | "We adopt the `DictateOrchestrator + 13 DictateModule` pattern with single-dispatch, pure-reducer invariant, and lens-based sub-state encapsulation. Mutation runs exclusively via `dispatch(action: Action)` (main-thread-confined). Each sub-state axis has exactly one module owner; modules communicate only via global state + action pipe, never directly." |
| 2 | `state-cross-module-cascade` | `state` | "Cross-module effects run via **Mode 1** (own SideEffect from the reducer) or **Mode 2** (action cascade via `onCrossModuleStateChange(prev, next): List<Action>`). **Mode 3** (atomic cross-axis update in the reducer) is forbidden. Self-cascade is allowed (KG-RSB-2 fix). `MAX_CASCADE_DEPTH = 8` is the only infinite-loop protection. `EffectFailure` is routed via `originModuleId` to the emitting module, not via KClass lookup." |
| 3 | `service-foreground-pipeline-architecture` | `service` | "Pipeline state lives in a dedicated `DictatePipelineService` (foreground service, type=microphone) in the same app process as the IME service. Communication via LocalBinder + StateFlow (no IPC). **No WorkManager** — on OOM-death, recovery happens via DB-replay + manual user resume. Persistent notification is mandatory (FGS) and serves simultaneously as status UI." |
| 4 | `ui-layout-catalog-motionlayout` | `ui-rendering` | "UI rendering is declarative: `LayoutCatalog` (predicates + resolvers per `ButtonSlot`) + MotionScene XML instead of programmatic re-parenting. `RenderBackend` interface with a multi-backend pattern (`ImeViewBackend` + `ContentAreaController` + `OverlayBackend` in parallel). Click listeners wired once at `attach()`, read backend fields `stateRef`/`modeRef`. `motion:visibilityMode=\"ignore\"` is mandatory on all state-driven buttons." |
| 5 | `ui-triangle-fsm-keyboard-widget-hover` | `ui-mode` | "ViewMode is a deterministically computed function `computeViewMode(imeViewVisible, userPrefersWidget, pipelineActive) → ViewMode` with three values KEYBOARD/WIDGET/HOVER and seven transitions T1–T7. WIDGET is user-triggered (permission-gated), HOVER is automatic on `!imeViewVisible && pipelineActive`. `userPrefersWidget` is transient (in-memory). T7 (HOVER→KEYBOARD on pipeline-done) is structural protection against the 'ghost-widget' bug." |

**Subsystem header per ADR:** ADR-1+2 = `Subsystem: state`. ADR-3 = `Subsystem: service`.
ADR-4 = `Subsystem: ui-rendering`. ADR-5 = `Subsystem: ui-mode`. All five carry a
`Scope: Project-Wide` hint in the header at the same time, because they also bind other subsystems.

##### 4.0.1.0.1 Mapping: plan-body sub-section → ADR

The hard-defined rules from §4.0.1.1–§4.0.1.7 + §4.0.5 + §4.0.6 distribute
across the five ADRs as follows — when writing the ADRs, the named sections are
adopted 1:1 (or linked via cross-reference).

| Plan-body section | ADR-1 | ADR-2 | ADR-3 | ADR-4 | ADR-5 |
|---|:---:|:---:|:---:|:---:|:---:|
| §4.0.1.1 Reducer rules | **§3** | — | — | — | — |
| §4.0.1.2 Action rules | **§4** | (§4) | — | — | — |
| §4.0.1.3 Effect rules | **§5** | (§5) | — | — | — |
| §4.0.1.4 Cross-module rules | (Ref) | **§3** | — | — | — |
| §4.0.1.5 Forbidden patterns | (a,b,c,e,h,i,m,n) | (f,g) | — | (d,j,k,l) | — |
| §4.0.1.6 Who holds state (diagram) | **§6** | — | — | — | — |
| §4.0.1.7 Who holds UI wiring | — | — | — | **§3** | — |
| §4.0.5 Module isolation (lens, diagram, channels, registry) | **§7** | (Ref) | — | — | — |
| §4.0.6.1 Walkthrough Button | **§Cons. Pos.** | — | — | (§Cons. Pos.) | — |
| §4.0.6.2 Walkthrough sub-keyboard | — | — | — | **§Cons. Pos.** | — |
| §4.0.6.3 Walkthrough Module | **§Cons. Pos.** | (§Cons. Pos.) | — | — | — |
| Main plan §3.1 Triangle-FSM diagram | — | — | — | — | **§3** |
| Spec 3 §7.1 computeViewMode truth table | — | — | — | — | **§4** |
| Spec 3 §7.3 T1–T7 transitions | — | — | — | — | **§5** |
| Spec 1 §7 foreground-service lifecycle | — | — | **§3** | — | — |
| Spec 1 §11.1 FGS details (5s timeout, permission, bind) | — | — | **§4** | — | — |
| Spec 2 §3–§8 LayoutCatalog + RenderBackend | — | — | — | **§4** | — |
| Spec 2 §7 MotionScene XML | — | — | — | **§5** | — |

Symbol convention: **bold** = main home of the rule (1:1 adoption). `(Ref)` =
cross-reference via `@see ADR-X §Y`. `(§N)` = sub-section mention, not 1:1.

##### 4.0.1.0.2 Cross-reference graph between the ADRs

```
ADR-1 (Modular-Orchestrator)
  ├── ist Voraussetzung für ─► ADR-2 (Cross-Module-Cascade)
  ├── lebt im Container von ─► ADR-3 (Foreground-Service)
  └── wird konsumiert von   ─► ADR-4 (LayoutCatalog)

ADR-2 (Cross-Module-Cascade)
  ├── erweitert             ─► ADR-1
  └── relevant für T7-Pfad  ─► ADR-5

ADR-3 (Foreground-Service)
  ├── hostet                ─► ADR-1 (Composition Root)
  └── motiviert             ─► ADR-5 (HOVER-Modus möglich, weil FGS überlebt)

ADR-4 (LayoutCatalog + MotionLayout)
  ├── konsumiert            ─► ADR-1 (Sub-State über RenderBackend)
  └── implementiert         ─► ADR-5 (Triangle-FSM via computeLayoutMode)

ADR-5 (Triangle-FSM)
  ├── implementiert in      ─► ADR-1 (ViewModeModule)
  ├── benötigt              ─► ADR-3 (FGS für HOVER-Persistenz)
  └── wird gerendert von    ─► ADR-4 (RenderBackend-Switching)
```

Each ADR's §References carries the other four ADRs as a cross-reference (bidirectional).

##### 4.0.1.0.3 ADR standard structure (binding per ADR, 14 sections)

All five ADRs follow the same section structure (per the `knowledge-adr-format` skill):

| § | Section | Content |
|---|---|---|
| 1 | **Context** | Symptom/trigger from this plan + relation to §1.1 bug classes / §2.1 architecture goals |
| 2 | **Decision (Status: Proposed → Accepted)** | The decision core statement from §4.0.1.0 (1:1) |
| 3..7 | **Hard-defined rules** | per ADR specific (see mapping table §4.0.1.0.1) |
| 8 | **Consequences (positive)** | concrete from §2.1+2.3 + walkthrough references |
| 9 | **Consequences (negative + failure modes)** | known risks from plan §6 + Phase-A inventory Top-3 |
| 10 | **Subsystem header** | `Subsystem: <state/service/ui-rendering/ui-mode>` + optional `Scope: Project-Wide` hint |
| 11 | **References** | plan file · Specs 1+2+3 § · Phase-A inventory · Phase-B/C reports · the other 4 ADRs · `~/.claude/skills/knowledge-adr-format` |
| 12 | **Decision History** | initial entry `2026-MM-DD — Created (Proposed)` |

**Skill + template:** When writing each ADR, the `knowledge-adr-format` skill
and `~/.claude/templates/adr.md` **MUST** be loaded (mandatory load from
`~/.claude/snippets/docs/docs.md`).

**Lifecycle per ADR:** Status `Proposed` during Block 0; after plan-approval `Accepted`.
Body thereafter append-only — reversal only via a new ADR that supersedes this one.

**Phase-2 expectations** (potential superseding candidates):

- ADR-2 could be superseded if a use-case makes Mode 3 (atomic cross-axis) mandatory
  (plan §7.1 out-of-scope).
- ADR-3 could be superseded if a STANDALONE_OVERLAY service comes (plan §7.1
  out-of-scope) or WorkManager becomes necessary.
- ADR-5 could be superseded if a fourth ViewMode (e.g. PIP mode) or a
  different close behavior becomes a Phase-2 wish.
- ADR-1 + ADR-4 are the most stable — superseding here would mean a larger architecture
  pivot.

##### 4.0.1.1 What a Reducer Does (ADR §3)

**Positive definition (what it MAY and SHALL do):**

- Pure function signature: `(state: S, action: A, ctx: ReducerContext) → TransitionResult<S, E>?`
- Deterministic — same inputs yield same outputs
- Reads cross-module state ONLY via `ctx.global: DictateUiState` (read-only view)
- Mutates EXCLUSIVELY its own sub-state `S` (single-owner-per-sub-state invariant)
- Emits side-effects as a list in `TransitionResult.sideEffects` (plan, not execution)
- Return `null` = "action insignificant in this state" — the orchestrator returns
  `DispatchOutcome.Rejected("reducer-null")`, NOT a bug
- `when` block is expression-form over sealed Actions (compiler enforces exhaustivity)
- Available read context: `ctx.global` (complete UiState snapshot) + `ctx.now`
  (monotonic, for timer comparisons)

**Negative definition (what it MUST NOT do):**

- Hardware/IO/FS reads (no `audioFile.exists()`, no `MediaRecorder.prepare()`)
- Threading (coroutines, threads, locks)
- Write other sub-states (no `state.copy(audio = ...)` from
  `RecordingModule.reduce`)
- Direct store access (`_state.value = …`)
- Synchronously call `dispatch(...)`
- System-time reads (except `ctx.now`)
- `else` branch in `when` over sealed Action (except for documented OEM-extensible Effect)
- Logging with side-effect (`Log.d` is OK; `Toast.makeText` from a reducer is forbidden)

##### 4.0.1.2 What Actions Do (ADR §4)

**What they ARE:**

- Pure data containers (`data class` / `data object`)
- Sealed-class hierarchy with one inner sealed per module (`Action.RecordingAction`,
  `Action.OverlayAction`, …)
- Carry all inputs the reducer needs (e.g. `audioFile: File` as
  pre-dispatch allocation from the resolver)
- The only entry into state mutation: `orchestrator.dispatch(action)`
- Plus one top-level `Action.EffectFailure(originModuleId, effect, reason)` as a
  failure channel with `originModuleId` routing (NOT KClass routing)

**What they are NOT:**

- Carriers of logic (no methods except data properties)
- Carriers of hardware references (no `MediaRecorder` instance inside)
- Directly executable — actions are planned, not "called"
- Parallel definition to LocalBinder forwarder methods (F-8: double API forbidden)

**How they are triggered (five sources):**

1. **UI click:** `slot.actionResolver(state, services) → Action?` → `onAction(action)` →
   `binder.dispatch(action)`. A nullable resolver return is a silent no-op
   (`?.let { onAction(it) }`), no log spam.
2. **Android lifecycle:** e.g. `onFinishInputView` →
   `service.dispatch(Action.ViewModeAction.OnImeViewHidden)`.
3. **Cross-module cascade:** another module returns an action from
   `onCrossModuleStateChange(prev, next)`, which the orchestrator dispatches recursively
   (depth+1).
4. **Effect completion:** `services.emitAction(action)` from the `runEffect` coroutine
   (always asynchronous, main-thread re-post via `Main.dispatcher.launch`).
5. **Effect failure (automatic):** orchestrator emits
   `Action.EffectFailure(originModuleId, effect.toString(), reason)` when an
   effect throws. Routed back to the origin module via the `reduceFailure` hook.

##### 4.0.1.3 What Side-Effects Are (ADR §5)

**What they ARE:**

- Per module its own `sealed interface Effect : SideEffect`
  (e.g. `RecordingModule.Effect.AllocateMediaRecorder(target, useBluetooth, audioFile)`)
- Asynchronous hardware/IO actions
- Emitted by the reducer as a **plan** in `TransitionResult.sideEffects`, not executed
- Executed by the orchestrator after state-emit in `services.scope.launch { module.runEffect(effect, services) }`
- The hardware interface is exclusively `runEffect` — no other code path may call
  `services.recordingHardware.*` etc.

**What they MUST NOT do:**

- Synchronously call `dispatch(...)` (would violate the frozen-cascade snapshot)
- Write state directly (`_state.value = …`)
- Be executed more than once for the same reducer output (idempotent or reducer-filter)
- Be started outside the `services.scope` (would leak on
  `serviceScope.cancel()`)

**How they react to throws:**

```kotlin
services.scope.launch {
    runCatching { module.runEffect(effect, services) }
        .onFailure { e ->
            services.emitAction(Action.EffectFailure(
                originModuleId = module.id,
                effect = effect.toString(),
                reason = e.message ?: "unknown"
            ))
        }
}
```

- `EffectFailure` is routed **back** to the origin module (NOT KClass lookup) →
  `reduceFailure(state, failure, ctx)` hook
- Default hook returns `null` = "no failure path defined"
  (`DispatchOutcome.Rejected("reducer-null")` is semantically correct)
- `effect.toString()` for `object` effects yields the simple name; for `data class` effects
  it contains the args representation — modules with `data class` effects MUST
  use `failure.effect.startsWith("AllocateMediaRecorder(")` (see Spec 1 §15.2)

##### 4.0.1.4 What Cross-Module Does (ADR §6)

**Allowed:**

- **Mode 1 (own SideEffect):** module A reacts to its own state transition with
  an effect that triggers hardware in another subsystem. No state mutation
  in a foreign axis.
- **Mode 2 (action cascade):** module B listens in
  `onCrossModuleStateChange(prev, next): List<Action>` for a state transition
  in the global state and returns actions that the orchestrator dispatches recursively
  (depth+1).
- **Self-cascade:** module B may cross-cascade its own state transition
  (the self-filter `it.id != module.id` in `dispatchInternal` step 5 is **removed**,
  KG-RSB-2 fix 2026-05-11).
- **Frozen snapshot:** `prevGlobal` + `nextGlobal` are snapshotted before the cascade
  — observers see a consistent before/after comparison, even if the recursive
  dispatch sequence mutates the state further.

**Forbidden:**

- **Mode 3 (atomic cross-axis update):** a reducer mutates multiple sub-state axes
  simultaneously. Phase-2 backlog without an explicit use-case.
- Synchronous re-dispatch from `onCrossModuleStateChange` (always via the recursive
  `dispatchInternal` loop, not through code duplication).
- Cross-mutation on a foreign axis via `state.copy(otherAxis = …)` — belongs in an
  action of the owner module.

**Infinite-loop protection:** `MAX_CASCADE_DEPTH = 8` — DEBUG `error()`, release log `error`.
The only protection after the KG-RSB-2 fix.

##### 4.0.1.5 Hard-Forbidden Patterns (ADR §7)

| # | Forbidden pattern | Rationale |
|---|---|---|
| (a) | Direct `_state.value = …` outside the store | breaks single-dispatch ownership (F-8) |
| (b) | Hardware/IO in the reducer | breaks the pure-reducer invariant (F1+F2), tests become non-deterministic |
| (c) | `else` branch in the `reduce` `when` over sealed Actions | loses the exhaustivity guarantee, a new action variant is silently swallowed |
| (d) | Re-parenting on layout switch (ConstraintSet rewriting) | reactivates bug §1.1 #1+#2 (asymmetric re-parenting). MotionLayout is binding |
| (e) | `toMutableList()` round-trip on `PersistentList` | destroys structural sharing, performance regression |
| (f) | Self-filter (`it.id != module.id`) in `dispatchInternal` step 5 | KG-RSB-2 reactivation, HOVER auto-reopen broken |
| (g) | Cross-axis mutation in the reducer (Mode 3) | Phase-2 backlog, no use-case |
| (h) | Synchronous re-dispatch from the EffectHandler | breaks the frozen-cascade snapshot; only via `services.emitAction(...)` async |
| (i) | Action-forwarder methods in the LocalBinder in parallel to the action hierarchy | F-8: double definition forbidden |
| (j) | `pred*Visible` predicate contains cooldown logic | reactivates bug §1.1 #3b; cooldown belongs in the `enabledResolver` |
| (k) | View-visibility-driven buttons without `motion:visibilityMode="ignore"` in the MotionScene XML | MotionScene + LayoutCatalog collide, visible jump |
| (l) | Re-wiring click listeners per render tick | memory leak (one lambda per button per tick) |
| (m) | `actionResolver` return `Action.NoOp` (instead of `null`) | `Action.NoOp` is removed (R.3), the click filter is `?.let` sort-out |
| (n) | Direct module-to-module call (`recordingModule.foo()` from OverlayModule) | breaks module decoupling; inter-module communication ONLY via global state + action pipe |

##### 4.0.1.6 Who Holds State (ADR §8)

```
┌──────────────────────────────────────────────────────────────┐
│  DictateUiStateStore                                          │
│                                                              │
│    private val _state = MutableStateFlow(initial)            │
│    val state: StateFlow<DictateUiState> = _state.asStateFlow()│
│                                                              │
│    fun update(reducer: (DictateUiState) -> DictateUiState)   │
└──────────────────────────────────────────────────────────────┘
       ▲                                            │
       │ EINZIGE Mutations-                         │ Read-only via collect{}
       │ Schnittstelle                              │
       ▼                                            ▼
┌──────────────────────────┐         ┌─────────────────────────────┐
│  DictateOrchestrator     │         │  Subscriber                 │
│  .dispatch(action)       │         │  - KeyboardLayoutManager    │
│                          │         │  - PipelineNotificationCoord│
│  Main-Thread-confined    │         │  - PrefMirror               │
└──────────────────────────┘         │  - DB-Subscriber            │
                                     └─────────────────────────────┘
```

**Three main rules:**

1. **State is read-only externally** — only via `state.collect { ... }`.
2. **Mutation only via `dispatch(action: Action)`** — the hand mutation
   `_state.value = ...` is private in the store. A violation is a code-review violation, not a
   compile check.
3. **`dispatch()` is main-thread-confined**
   (`require(Looper.myLooper() == Looper.getMainLooper())`).

##### 4.0.1.7 Who Holds UI Wiring (ADR §10)

- The `RenderBackend` interface is the only UI-mutator interface.
- One backend per render surface: `ImeViewBackend` (KEYBOARD), `OverlayBackend`
  (WIDGET + HOVER), `ContentAreaController` (container visibility, second backend
  in parallel to ImeView, R.10).
- Click listeners are wired ONCE at `attach()` in `wireStaticHandlers()`.
- Lambdas reference `stateRef`/`modeRef` fields, NOT the `render` arguments —
  so that exactly one lambda per button lives for the entire backend lifetime (L8).
- The resolver result is `Action?`. `null` is a silent no-op (no log spam).
- Do NOT check visibility/enabled in the click listener — `visibilityPredicate` +
  `enabledResolver` manage that in the render loop.

#### 4.0.2 Architecture Doc: `docs/architecture/state-architecture/`

**Purpose:** Detailed, textbook-style explanation with ASCII diagrams, code snippets,
walkthroughs for new modules/buttons/backends. Complementary to the ADR — the ADR is
**contract (concise + binding)**, the architecture doc is **teaching material (long + explanatory)**.

**Minimum content (binding topics):**

| File | Content |
|---|---|
| `state-architecture/README.md` | Architecture overview: Triangle-FSM (3-mode, 7 transitions, computeViewMode truth table) · service-layer diagram (FGS + IME, LocalBinder) · Modular Orchestrator (composition root + module inventory) · reading order of the sub-files · pointer to the ADR as the binding contract |
| `state-architecture/state-and-actions.md` | `DictateUiState` (immutable, 13 sub-state axes + 1 top-level flag) · PersistentList idiom (add/remove, NO `toMutableList()` round-trip) · sub-state class list with fields + owner module · action hierarchy (sealed hierarchy with inner sealeds per module) · `dispatch` loop in 5 steps (cascade-limit → routing → reducer → state-write → effects async → cross-module cascade) · `DispatchOutcome.{Applied, Rejected, Unrouted}` trichotomy · ReducerContext (global + now) |
| `state-architecture/modules.md` | `DictateModule<S, A, E>` interface complete (`id`, `actionClass`, `read`/`write`/`initialState`, `reduce`, `reduceFailure`, `runEffect`, `onCrossModuleStateChange`, `prefBindings`, `terminate`) · `ModuleId` enumeration (14 IDs) · lens-pattern mechanics · `DictateModuleRegistry.all` + init-sanity-check (double-routing detection) · `ModuleServices` DI container · `PrefBinding<S, T>` (Phase 2) · module-inventory overview with sub-state type + responsibility |
| `state-architecture/effects-and-failures.md` | `SideEffect` sealed per module (example: `RecordingModule.Effect.{AllocateMediaRecorder, ReleaseMediaRecorder, StartTimer, …}`) · `runEffect(effect, services)` in the `services.scope` · `services.emitAction` (main-thread re-post) · `EffectFailure` routing via `originModuleId` (NOT KClass lookup) · `effect.toString()` convention for `object` vs `data class` · `reduceFailure(state, failure, ctx)` hook · default return `null` = "no failure path" · failure-recovery patterns (state-rollback, error-marker, ToastSink) |
| `state-architecture/cross-module-cascade.md` | Mode 1 (own SideEffect) vs Mode 2 (action cascade) vs Mode 3 (forbidden) · `onCrossModuleStateChange(prev, next): List<Action>` convention · frozen-snapshot (`prevGlobal` + `nextGlobal` are captured before the cascade) · self-cascade permission (KG-RSB-2 fix with before/after code diff) · `MAX_CASCADE_DEPTH=8` (DEBUG-error, release-log-error) · coupling-matrix notation `R(state.x.y) C(Action.Y.Z)` with self-read convention (KG-RSB-3) · example cascade sequence (ResetSuppressBit) |
| `state-architecture/rendering.md` | `RenderBackend` interface (`attach`, `detach`, `render`) · multi-backend pattern (ImeViewBackend + ContentAreaController + OverlayBackend in parallel, R.10) · `LayoutCatalog` concept (predicates + resolvers per `ButtonSlot`) · `LayoutMode` with `sceneStateId` (R.12) · `LogicalButtonId` enum list · MotionScene + `motion:visibilityMode="ignore"` bindingness (R.11) · `firstRender` flag (R.14) · shared `SlotRenderer.applySlotToView` helper (F-7) · `computeLayoutMode(state)` truth table |
| `state-architecture/wiring-ui.md` | Click-listener wiring complete: `wireStaticHandlers()` once at `attach`, NEVER per render tick (L8) · `stateRef` + `modeRef` fields as single source for lambda reads · nullable-resolver idiom (`slot.actionResolver(s, services)?.let { onAction(it) }`) · special touch handlers (CursorSwipe, Backspace-Swipe, Enter-Overlay — state-free) · long-click per button · memory-leak structural protection with code comparison (per-tick wiring vs once-wiring) · data-flow diagram Click→Reducer→State-Emit→Render |
| `state-architecture/triangle-fsm.md` | Three ViewModes (KEYBOARD/WIDGET/HOVER) · `computeViewMode(imeViewVisible, userPrefersWidget, pipelineActive)` truth table · 7 transitions T1–T7 with conditions + cascade sequence · `userPrefersWidget` persistence (transient, in-memory) · ghost-widget bug structural protection (T7) · permission gate before T1 |
| `state-architecture/adding-a-module.md` | §4.0.6.3 (Walkthrough Module) — complete step plan: define sub-state · create action subclass · implement module singleton (all 9 hooks) · `ModuleId` entry · `DictateModuleRegistry.all` entry · system subscription if applicable · coupling-matrix update · tests · set inline anchors |
| `state-architecture/adding-a-button.md` | §4.0.6.1 (Walkthrough Button) — complete step plan: `LogicalButtonId` entry · XML view ID · backend `buttonViews` mapping · new action variant if applicable · reducer extension in the owner module if applicable · `ButtonSlot` in the LayoutCatalog · tests |
| `state-architecture/adding-a-sub-keyboard.md` | §4.0.6.2 (Walkthrough sub-keyboard) — two variants: (A) new ContentArea (container visibility) · (B) new render surface (own backend with `BackendType` extension + LayoutMode + WindowManager if applicable) |
| `state-architecture/forbidden-patterns.md` | Negative examples with code snippets: all 14 points from ADR §7 (§4.0.1.5) each with (a) forbidden code snippet (b) why it breaks (c) correct alternative |

**Skill:** `knowledge-doc-format` + `~/.claude/templates/universal.md` (mandatory load from
`~/.claude/snippets/docs/docs.md`). Doc genre: **Architecture (Subsystem)** — §2 heading
is "Properties this Architecture Guarantees".

**Language:** English (convention from `~/.claude/snippets/docs/language-conventions.md` —
the architecture doc is product documentation).

**Inline-anchor convention:** Code that implements the ADR rules carries
`@see docs/decisions/NNNN-modular-orchestrator-architecture.md §X` as a comment
(see `knowledge-doc-format` skill §"Inline anchors"). At least at the following places:

- `DictateModule` interface file → ADR §3+4+5
- `DictateOrchestrator.dispatchInternal` → ADR §6+§7(f)
- `DictateUiStateStore` → ADR §8
- `RenderBackend` interface → ADR §10
- `wireStaticHandlers` function → ADR §10 + §7(l)

#### 4.0.3 Block-0 Acceptance

- [ ] **Five ADRs** exist under `docs/decisions/`, each with status `Accepted`,
      with all 12 ADR sections from §4.0.1.0.3, correct subsystem header (state /
      service / ui-rendering / ui-mode), decision-history initial entry:
  - [ ] `NNNN-state-modular-orchestrator-pattern.md`
  - [ ] `NNNN-state-cross-module-cascade.md`
  - [ ] `NNNN-service-foreground-pipeline-architecture.md`
  - [ ] `NNNN-ui-layout-catalog-motionlayout.md`
  - [ ] `NNNN-ui-triangle-fsm-keyboard-widget-hover.md`
- [ ] Inter-ADR cross-references per §4.0.1.0.2 (all five ADRs reference one another
      in §References, bidirectional).
- [ ] `docs/decisions/README.md` (ADR index) exists with all five entries.
- [ ] `docs/architecture/state-architecture/README.md` + all 12 sub-files (§4.0.2 table)
      exist, all with a "Properties this Architecture Guarantees" section (UDOC skeleton).
- [ ] Bidirectional plan-↔-ADR reference: main plan §8.1 "References" links all five
      ADRs, each ADR §References links the plan.
- [ ] Bidirectional spec-↔-architecture-doc reference: Spec 1/2/3 §12 "References" link
      the relevant ADRs + architecture-doc files (Spec 1 → ADR-1+2+3 · Spec 2 → ADR-1+4 ·
      Spec 3 → ADR-3+4+5).
- [ ] Sanity check: a reader not involved in the plan review can read the ADRs + architecture
      doc and reproduce the modular-orchestrator pattern + Triangle-FSM + FGS architecture
      (without looking into the specs).

#### 4.0.4 Binding-Contract Character

After Block 0 completes:

- Blocks 1a..6 may **not** break the rules hard-defined in ADR §3..§7.
- When implementing, the ADR + architecture doc are cited (inline anchor via `@see docs/decisions/NNNN-modular-orchestrator-architecture.md §3` etc., per the `knowledge-doc-format` skill §"Inline anchors").
- An implementer who wants to do an architecture rule differently must re-open Block 0 (editing the ADR body is only possible while `Proposed`; after `Accepted`, reversal is only via a new superseding ADR).

#### 4.0.5 Module Isolation — how multiple modules run side by side

(This section is the SoT for the architecture doc `state-architecture/modules.md` +
`cross-module-cascade.md`. For the code implementation, see Spec 1 §4.2 + §15.)

##### 4.0.5.1 Lens Pattern (read/write)

Each module knows **its own sub-state type `S`** (e.g. `RecordingState`,
`OverlayState`). From the global `DictateUiState`, the sub-state is sliced out via two
lens methods:

```kotlin
interface DictateModule<S, A : Action, E : SideEffect> {
    fun read(global: DictateUiState): S                  // ⊂ "raus"-Lens
    fun write(global: DictateUiState, sub: S): DictateUiState  // ⊂ "rein"-Lens
    fun initialState(): S
    // ...
}

// RecordingModule:
object RecordingModule : DictateModule<RecordingState, Action.RecordingAction, RecordingModule.Effect> {
    override fun read(global: DictateUiState) = global.recording
    override fun write(global: DictateUiState, sub: RecordingState) = global.copy(recording = sub)
    override fun initialState() = RecordingState.Idle
    // ...
}
```

**Consequence:** the reducer works on `S` (e.g. `RecordingState`) and knows the
top-level `DictateUiState` **ONLY** through `ReducerContext.global` for **reads**.
It can change its own `S`, but **never** write `global.audio` or `global.layout`.

##### 4.0.5.2 Three modules side by side (ASCII diagram)

```
                    ┌──────────────────────────────────────────────┐
                    │            DictateUiState (global)           │
                    │                                              │
                    │   recording   pipeline   layout   overlay    │
                    │      ▲           ▲          ▲        ▲       │
                    │      │           │          │        │       │
                    └──────┼───────────┼──────────┼────────┼───────┘
                           │           │          │        │
              read/write   │           │          │        │
              eigene Achse │           │          │        │
                           │           │          │        │
                   ┌───────┴────┐ ┌────┴────┐ ┌───┴────┐ ┌─┴──────┐
                   │ Recording  │ │Pipeline │ │ Layout │ │Overlay │
                   │  Module    │ │ Module  │ │ Module │ │ Module │
                   │            │ │         │ │        │ │        │
                   │ Sub-State: │ │   ...   │ │   ...  │ │   ...  │
                   │ RecordingState                                │
                   │ Actions:                                      │
                   │ RecordingAction.{Start,Stop,Pause,...}        │
                   │ Effects:                                      │
                   │ Effect.{AllocateMediaRecorder,StartTimer,...} │
                   │                                               │
                   │ ─── Read ──► ctx.global.audio.useBluetoothMic │
                   │              ctx.global.layout.singleRowMode  │
                   │              (Read-only, in eigener           │
                   │               Reducer-Logik konsumiert)       │
                   │                                               │
                   │ ─── Write ─► nur state.copy(... eigenes ...)  │
                   │              NIEMALS state.copy(audio=...)    │
                   │                                               │
                   │ ─── Cascade ► returnt List<Action> aus        │
                   │               onCrossModuleStateChange,       │
                   │               die der Orchestrator dispatcht  │
                   └───────────────────────────────────────────────┘
```

##### 4.0.5.3 Three communication channels between modules

| Channel | Mechanism | When |
|---|---|---|
| **Read** (polling on reduce) | `ctx.global.<otherAxis>` in the reducer | Synchronous, every time on the own dispatch |
| **Cascade** (Mode 2) | `onCrossModuleStateChange(prev, next): List<Action>` | After every successful state mutation the orchestrator iterates ALL modules |
| **Effect-emit** (Mode 1 → foreign action) | `services.emitAction(Action.OtherModule.Foo)` from `runEffect` | Asynchronous, fresh cascade snapshot |

**Important (forbidden pattern (n)):** module A does **not** know module B directly — no
`recordingModule.overlayModule.foo()`. All inter-module communication runs through the
**global state** + the **action pipe**. Modules are thereby **decoupled
from one another** and individually testable.

##### 4.0.5.4 Inner sub-state vs. global state — encapsulation

| Property | Inner sub-state `S` | Global state `DictateUiState` |
|---|---|---|
| Who writes? | ONLY the owner reducer | ONLY the store via `module.write(global, newSub)` |
| Who reads? | Owner module: via `read(global)` · Other modules: via `ctx.global.<axis>` | Subscribers via `state.collect{}` · other modules via `ctx.global` |
| Type | Module-specific (`RecordingState`, `OverlayState`, ...) | Top-level `data class DictateUiState` |
| Immutability | Sealed/`data class`, `copy()`-based | `data class` with all sub-states as properties |
| Identity | Has none outside the module — other modules see only the snapshot via `ctx.global.recording` | Globally unique, monotonically emitted via StateFlow |

**Encapsulation happens structurally:**

1. **Compile-time type safety:** the reducer signs on `S`, not on `DictateUiState`.
   An attempt to write `state.audio = ...` directly is a compile error.
2. **Lens contract:** `write(global, newSub)` is called by the orchestrator, NOT by the
   reducer. The reducer returns only the new `S`.
3. **`ctx.global` is `val`** — the reducer has read-only access to the global state,
   no mutator.
4. **`ModuleServices` container** is read-only externally; hardware is addressed via
   interfaces (`RecordingHardware`, `AudioFileFactory` etc.), not
   via instance fields of other modules.

##### 4.0.5.5 Wiring: `DictateModuleRegistry.all`

There is **one** central list of all modules:

```kotlin
object DictateModuleRegistry {
    val all: List<DictateModule<*, *, *>> = listOf(
        RecordingModule, PipelineModule, AudioModule, ViewModeModule, OverlayModule,
        ResendModule, LivePromptModule, LanguageModule, LayoutModule,
        FeatureToggleModule, ThemingModule, PendingSessionsModule, KeyboardInputModule,
        // InterruptionModule (Phase 2)
    )

    init {
        // Sanity-Check: keine doppelte Action-Klasse, keine doppelte ModuleId
        val byActionClass = all.groupBy { it.actionClass }
        require(byActionClass.values.all { it.size == 1 }) {
            "Doppel-Routing: ${byActionClass.filter { it.value.size > 1 }}"
        }
    }
}
```

This list is read once by the `DictateOrchestrator` at the service `onCreate`.
From it the orchestrator builds:

```kotlin
private val moduleByLeafClass: Map<KClass<*>, DictateModule<*, *, *>> =
    DictateModuleRegistry.all.flatMap { module ->
        module.actionClass.sealedSubclasses.map { it to module }
    }.toMap()
```

The `KClass` lookup is init-time constant. Double-routing of an action to two modules
is an **init-time error**, not a runtime bug. A ProGuard-keep rule for `sealedSubclasses`
is mandatory (otherwise R8 strips the reflection).

#### 4.0.6 Walkthroughs — how new components are added

(This section is the SoT for the architecture-doc files `adding-a-button.md`,
`adding-a-sub-keyboard.md`, `adding-a-module.md`.)

##### 4.0.6.1 New Button (example: `INSERT_COMMA`)

Goal: a new button in KEYBOARD_TWO_ROW mode that inserts a comma into the InputConnection.

**Step 1 — new `LogicalButtonId` entry (Spec 2 §3.1):**

```kotlin
enum class LogicalButtonId {
    RECORD, RESEND, BACKSPACE, AUDIO_FOCUS, WIDGET_TOGGLE, TRASH, SPACE, PAUSE, ENTER,
    OVERLAY_RECORD, OVERLAY_SEND, OVERLAY_PAUSE, OVERLAY_TRASH, OVERLAY_CLOSE,
    INSERT_COMMA,   // ← NEU
}
```

**Step 2 — XML view ID:**

```xml
<!-- res/layout/activity_dictate_keyboard_view.xml -->
<ImageButton android:id="@+id/insert_comma_btn"
             android:visibilityMode="ignore"
             ... />
```

**Step 3 — button-view mapping in the backend (Spec 2 §6 `ImeViewBackend.buttonViews`):**

```kotlin
private val buttonViews: Map<LogicalButtonId, View> = mapOf(
    // ... bestehende
    LogicalButtonId.INSERT_COMMA to rootView.findViewById(R.id.insert_comma_btn),
)
```

**Step 4 — action variant (`KeyboardInputAction` is already there):**

```kotlin
sealed class KeyboardInputAction : Action() {
    data object Backspace : KeyboardInputAction()
    data object Enter : KeyboardInputAction()
    data object Space : KeyboardInputAction()
    data object InsertComma : KeyboardInputAction()      // ← NEU
}
```

**Step 5 — reducer extension in `KeyboardInputModule` (§15.6, Unit state):**

```kotlin
override fun reduce(state: Unit, action: Action.KeyboardInputAction, ctx: ReducerContext)
    : TransitionResult<Unit, Effect>? = when (action) {
    Action.KeyboardInputAction.Backspace   -> TransitionResult(Unit, listOf(Effect.SendBackspace))
    Action.KeyboardInputAction.Enter       -> TransitionResult(Unit, listOf(Effect.SendEnter))
    Action.KeyboardInputAction.Space       -> TransitionResult(Unit, listOf(Effect.SendSpace))
    Action.KeyboardInputAction.InsertComma -> TransitionResult(Unit, listOf(Effect.SendText(",")))
}

sealed interface Effect : SideEffect {
    object SendBackspace : Effect
    object SendEnter : Effect
    object SendSpace : Effect
    data class SendText(val text: String) : Effect   // ← NEU, generisch
}

override fun runEffect(effect: Effect, services: ModuleServices) = when (effect) {
    Effect.SendBackspace  -> services.inputConnection().sendKeyEvent(KEYCODE_DEL)
    Effect.SendEnter      -> services.inputConnection().sendKeyEvent(KEYCODE_ENTER)
    Effect.SendSpace      -> services.inputConnection().commitText(" ", 1)
    is Effect.SendText    -> services.inputConnection().commitText(effect.text, 1).let { Unit }
}
```

**Step 6 — `ButtonSlot` in the LayoutCatalog (Spec 2 §8.1):**

```kotlin
val KEYBOARD_TWO_ROW = LayoutMode(
    id = LayoutModeId.KEYBOARD_TWO_ROW,
    backend = BackendType.IME_VIEW,
    sceneStateId = R.id.scene_two_row,
    rows = listOf(
        RowDescriptor(slots = listOf(
            // ... bestehende Slots
            ButtonSlot(
                logicalId = LogicalButtonId.INSERT_COMMA,
                widthPolicy = WidthPolicy.WrapContent,
                visibilityPredicate = { it.viewMode == ViewMode.KEYBOARD },
                iconResolver = { R.drawable.ic_comma },
                enabledResolver = { true },
                actionResolver = { _, _ -> Action.KeyboardInputAction.InsertComma },
            ),
        ))
    ),
)
```

**Step 7 — tests (JVM, reducer pure):**

```kotlin
@Test
fun insertCommaAction_emitsSendTextEffect() {
    val result = KeyboardInputModule.reduce(
        state = Unit,
        action = Action.KeyboardInputAction.InsertComma,
        ctx = ReducerContext(DictateUiState.initial())
    )
    assertNotNull(result)
    assertEquals(listOf(KeyboardInputModule.Effect.SendText(",")), result!!.sideEffects)
}
```

**What NOT to do:**
- No MotionScene XML change (button in the same layout mode, only a new slot)
- No cross-module observer (the button has no cross-axis effect)
- No DB migration
- No new module file

**Complexity:** ~20 LoC spread across 4-5 files.

##### 4.0.6.2 New Sub-Keyboard — two variants

###### Variant A — new ContentArea (e.g. numeric pad next to QWERTZ/Emoji)

If the "sub-keyboard" is a **different view in the same render backend**:

**Step 1 — extend the `ContentArea` enum:**

```kotlin
enum class ContentArea { MAIN_BUTTONS, QWERTZ, EMOJI_PICKER, NUMERIC_PAD }   // ← NEU
```

**Step 2 — add XML container + IDs:**

```xml
<FrameLayout android:id="@+id/numeric_pad_container" ... >
    <!-- Numerik-Buttons -->
</FrameLayout>
```

**Step 3 — extend `ContentAreaController` (R.10, Spec 2 §4.1):**

```kotlin
class ContentAreaController(views: KeyboardViews) : RenderBackend {
    override fun render(state: DictateUiState, mode: LayoutMode) {
        views.mainButtonsCl.visibility       = if (state.layout.contentArea == ContentArea.MAIN_BUTTONS) VISIBLE else GONE
        views.qwertzContainer.visibility     = if (state.layout.contentArea == ContentArea.QWERTZ) VISIBLE else GONE
        views.emojiPickerContainer.visibility = if (state.layout.contentArea == ContentArea.EMOJI_PICKER) VISIBLE else GONE
        views.numericPadContainer.visibility = if (state.layout.contentArea == ContentArea.NUMERIC_PAD) VISIBLE else GONE  // ← NEU
    }
}
```

**Step 4 — trigger-button slot:**

```kotlin
ButtonSlot(
    logicalId = LogicalButtonId.NUMERIC_TOGGLE,
    actionResolver = { _, _ -> Action.LayoutAction.SetContentArea(ContentArea.NUMERIC_PAD) },
    // ...
)
```

**Complexity:** medium.

###### Variant B — new render surface (e.g. own window via WindowManager)

If the "sub-keyboard" is a **separate window** (like WIDGET/HOVER today via
`OverlayBackend`):

**Step 1 — new `RenderBackend` (Spec 2 §5):**

```kotlin
class NotificationPanelBackend(
    private val ctx: Context,
    private val services: ModuleServices,
    private val window: OverlayWindow,
    // ...
) : RenderBackend {
    override fun attach(onAction: (Action) -> Unit) { /* wireStaticHandlers etc. */ }
    override fun detach() { /* */ }
    override fun render(state: DictateUiState, mode: LayoutMode) { /* applySlotToView pro Button */ }
}
```

**Step 2 — new `BackendType` enum value:**

```kotlin
enum class BackendType { IME_VIEW, OVERLAY_WINDOW, NOTIFICATION_PANEL }
```

**Step 3 — new `LayoutMode` in the LayoutCatalog + a new ViewMode value in the
Triangle-FSM if applicable.**

**Step 4 — backend switching in the `KeyboardLayoutManager`** (multi-backend list,
R.10, allows parallel backends).

**Complexity:** large. New WindowManager window configuration, possibly own
permission logic, new touch-routing strategy.

##### 4.0.6.3 New Module (example: `BatterySaverModule`)

Goal: a module that observes the battery-saver status and pauses the pipeline
when battery-saver is active.

**Step 1 — define sub-state:**

```kotlin
// In DictateUiState.kt
data class BatterySaverState(val isActive: Boolean = false)

data class DictateUiState(
    // ... bestehende
    val batterySaver: BatterySaverState = BatterySaverState(),
)
```

**Step 2 — define actions:**

```kotlin
sealed class BatterySaverAction : Action() {
    data class SetActive(val active: Boolean) : BatterySaverAction()
}
```

**Step 3 — implement the module:**

```kotlin
// app/src/main/java/net/devemperor/dictate/state/modules/BatterySaverModule.kt
object BatterySaverModule : DictateModule<BatterySaverState, Action.BatterySaverAction, BatterySaverModule.Effect> {
    override val id = ModuleId.BatterySaver
    override val actionClass = Action.BatterySaverAction::class

    override fun read(global: DictateUiState) = global.batterySaver
    override fun write(global: DictateUiState, sub: BatterySaverState) = global.copy(batterySaver = sub)
    override fun initialState() = BatterySaverState()

    sealed interface Effect : SideEffect

    override fun reduce(state: BatterySaverState, action: Action.BatterySaverAction, ctx: ReducerContext)
        : TransitionResult<BatterySaverState, Effect>? = when (action) {
        is Action.BatterySaverAction.SetActive ->
            if (action.active != state.isActive)
                TransitionResult(state.copy(isActive = action.active), emptyList())
            else null
    }

    override fun runEffect(effect: Effect, services: ModuleServices) = Unit

    // Cross-Module-Cascade: bei Battery-Saver-On → Recording pausieren
    override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> =
        if (!prev.batterySaver.isActive && next.batterySaver.isActive
            && next.recording is RecordingState.Active)
            listOf(Action.RecordingAction.PauseRecording)
        else emptyList()
}
```

**Step 4 — `ModuleId` entry:**

```kotlin
sealed interface ModuleId {
    // ... bestehende
    data object BatterySaver : ModuleId
}
```

**Step 5 — registry entry:**

```kotlin
object DictateModuleRegistry {
    val all = listOf(
        // ... bestehende
        BatterySaverModule,
    )
}
```

**Step 6 — system subscription** for the battery-saver status: an external observer
(in `DictatePipelineService.onCreate`) listens for `BatteryManager.ACTION_POWER_SAVE_MODE_CHANGED`
and calls `binder.dispatch(Action.BatterySaverAction.SetActive(isOn))`.

**Step 7 — coupling-matrix update (Spec 1 §15.1.x):**

```
BatterySaver × Recording = C(RecordingAction.PauseRecording)
```

**Step 8 — tests:**

```kotlin
@Test
fun batterySaver_activeWhileRecording_cascadesToPause() {
    val cascadeActions = BatterySaverModule.onCrossModuleStateChange(
        prev = DictateUiState.initial().copy(
            batterySaver = BatterySaverState(isActive = false),
            recording = RecordingState.Active(useBluetooth = false, audioFile = File("/tmp/x.m4a")),
        ),
        next = DictateUiState.initial().copy(
            batterySaver = BatterySaverState(isActive = true),
            recording = RecordingState.Active(useBluetooth = false, audioFile = File("/tmp/x.m4a")),
        ),
    )
    assertEquals(listOf(Action.RecordingAction.PauseRecording), cascadeActions)
}
```

**What NOT to do:**
- No UI change (the module affects behavior, not rendering)
- No new spec file
- No DB migration

**Complexity:** medium. 1 new file (~80 LoC), 4 edits in existing files,
1 test file.

##### 4.0.6.4 General Walkthrough Pattern

```
1. WO LEBT DAS NEUE FEATURE?
   ├── neuer Button im bestehenden Modus    → §4.0.6.1 (~20 LoC)
   ├── neue Render-Surface / Sub-Tastatur   → §4.0.6.2 (~mittel-groß)
   └── neue Verhaltens-Achse                → §4.0.6.3 (~80 LoC + Tests)

2. WAS BERÜHRT ES?
   State?         → Sub-State erweitern oder neuen anlegen
   Actions?       → neue Action-Variante in passender innerer sealed
   Effects?       → neue Effect-Variante in passender Effect-Sealed
   UI?            → ButtonSlot + ggf. View-ID
   Cross-Module?  → onCrossModuleStateChange-Hook im neuen Modul (oder Owner-Modul)

3. WAS MUSS GETESTET WERDEN?
   Reducer-Test       (JVM, pure, kein Android)
   Cascade-Test       (Cross-Module-Observer auf Vor-/Nach-Snapshot)
   Resolver-Test      (Click → Action über ButtonSlot)
   UI-Smoke-Test      (Espresso, optional)

4. WO WERDEN INLINE-ANKER GESETZT?
   Modul-Datei: @see docs/decisions/NNNN-modular-orchestrator-architecture.md
   ButtonSlot:  @see docs/architecture/state-architecture/adding-a-button.md
   Coupling-Matrix-Update: in Spec 1 §15.1.x
```

---
