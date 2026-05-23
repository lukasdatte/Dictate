# Spec 1 — Pipeline-Service-Layer (Foreground Service + State-SSOT + Persistence)

**Status:** Skeleton — Architecture fixed, detail research by agent
**Main plan:** [→ keyboard-layout-refactor.md](../../keyboard-layout-refactor.md)
**Sibling specs:**
- [Spec 2 — KEYBOARD layout (IME view)](../2-keyboard-layout/2-keyboard-layout.md)
- [Spec 3 — Floating overlay (WIDGET + HOVER)](../3-floating-overlay/3-floating-overlay.md)

---

## §1 Context and Scope

This spec describes the **service layer** of the refactor. It comprises:

- A new **Foreground Service** `DictatePipelineService` that holds pipeline logic and state and lives independently of the IME service lifecycle (survives keyboard switches).
<!-- FIX: Phase-B S-1 (2026-05-13) – §1 Scope-Aufzählung auf F-11 (DictateOrchestrator + DictateUiStateStore + Module) umgestellt. -->
<!-- FIX: Phase-C C-1 (2026-05-14) – Modul-Zähler aktualisiert auf 13 aktiv (12 ursprünglich + KeyboardInputModule aus Phase-B S-3). -->
- The **`DictateOrchestrator` + `DictateUiStateStore` + 13 active modules** (F-11 Modular Orchestrator Pattern) as the sole state SSOT for ALL UI-relevant state axes.
- The **bound-service interface** (`LocalBinder`) through which the IME service communicates with the service.
- The **persistence layer** (Room) with minimal schema extension and checkpoint hooks.
- The **lifecycle**: start, stop, notification updates, recovery from DB after OOM death.
- The **migration** of existing classes (RecordingStateController, KeyboardUiController state, JobExecutor wiring).

Out-of-scope (other spec):
- View rendering, layout selection, button-visibility resolver — see Spec 2 + Spec 3.
- Window lifecycle for overlay — see Spec 3.

<!-- FIX: Phase-B S-4 (2026-05-13) – Naming-Konvention-Block: PipelineOrchestrator (alt, Audio-Pipeline) vs. DictateOrchestrator (neu, State-Action-Routing). -->
### §1.x Naming convention for "Orchestrator" — Disambiguation (Phase-B S-4)

After the refactor there are **two classes with "Orchestrator" in their name**. They have
different responsibilities and live in different packages. This
double existence is **deliberately accepted** for Phase 1 (no refactor of the audio
pipeline path).

| Class | Package | Responsibility | Status |
|---|---|---|---|
| `PipelineOrchestrator` | `net.devemperor.dictate.core` | **Audio pipeline runner** — orchestrates Speech-API calls + reword pipeline + auto-formatting on a dedicated executor thread. 1383 lines today. | stays unchanged (see §8 migration table); implements the `PipelineRunner` interface (§4.9) |
| `DictateOrchestrator` | `net.devemperor.dictate.state` | **State-action routing** — Composition Root + action routing + cross-module cascade dispatch. Knows only the `DictateModule` interface, no pipeline/audio logic. | new (Block 1b); see §4.3 |

**Reading convention in the plan body:**

- "Orchestrator" (unqualified) → **always** `DictateOrchestrator` (new, state routing).
- "PipelineOrchestrator" → **always** the old class (with or without the `core.` prefix).
- Code snippets use the full `KClass.simpleName` for unambiguity; plan documentation
  may use the unqualified "Orchestrator" term when the context is
  unambiguous (e.g. "the orchestrator dispatches Action X" → DictateOrchestrator).

**Phase-2 backlog (main plan §7.1):** Renaming the old `PipelineOrchestrator`
to e.g. `PipelineRunner` or `PipelineExecutor`, or dissolving it into the
`PipelineModule.runEffect` path (structurally eliminates the naming conflict).
Phase 1 accepts the double existence because the audio pipeline logic does not
belong to the state-refactor scope (~10 consumer sites, separate refactor).

---

## §2 Architecture decisions (fixed)

| # | Decision | Rationale |
|---|--------------|------------|
| D1 | **Dedicated Foreground Service** in the **app main process** (not in a separate `:pipeline` process) | No IPC needed, Local Binder is sufficient. Keyboard-switch survival comes from the service lifecycle. |
| D2 | **`startForeground()`** with a persistent notification mandatory | Android requirement for indefinitely-running services; simultaneously a user status UI. |
| D3 | **Local Binder with `StateFlow` + action methods** as the communication channel IME ↔ PipelineService | Same process → no marshalling cost. StateFlow is the standard reactive pattern. |
| D4 | **NO WorkManager worker** (rejected by the user) | Foreground service covers 99% of cases. On OOM death (rare): user-controlled resume from DB. |
| D5 | **`return START_NOT_STICKY`** in `onStartCommand` | On process death NO auto-restart. The user decides. |
| D6 | **`stopSelf()`** as soon as **all sessions are terminal** (COMPLETED/INSERTED/CANCELLED) | Notification disappears automatically. No "ghost service". |
| D7 | **`DictateOrchestrator.dispatch(Action)` as the sole mutation entry** for all UI-state axes (F-8 Single Dispatch + F-11 Modular Orchestrator) | Eliminates today's resend_btn race + recordButton hybrid. No `_state.update` call outside a module `reduce` allowed (audit requirement §13.2). <!-- FIX: Phase-B S-1 (2026-05-13) – pre-F-11 PipelineStateManager → F-8/F-11 Single Dispatch --> |
| D8 | **DB schema migration M3→M4: table-recreate in a single transaction** (`inserted_at` column **plus** `status`-CHECK extension by RECORDING/TRANSCRIBING — see §6.1) | Rollback-safe (Room migration is atomic). The previous iteration had assumed "purely additive", which is no longer possible with the enum extension (SQLite cannot change CHECK via `ALTER TABLE`). The `CREATE … _new` + `INSERT … SELECT` strategy follows MIGRATION_2_3 as an established pattern. |

---

## §3 Data model: `DictateUiState`

> **Architecture correction F-10 (iteration 2026-05-09):** Earlier spec versions
> had designed `DictateUiState` as a flat 18-field data class. With the
> extended state inventory (Block 3.5) it would have grown to ~30+ fields — a
> "knows-everything" data class, the same SRP anti-pattern as with `PipelineStateManager`
> before F-1. Correction: hierarchical **sub-state classes** per semantic axis.
> Each sub-state class is immutable, has a clear responsibility, and is managed by the
> respective module (see §15).
>
> **Architecture correction F-9 (iteration 2026-05-09):** List fields use
> `kotlinx.collections.immutable.PersistentList` instead of `List<T>`, to enforce real
> structural immutability (a cast to `MutableList` is prevented).
> New library dependency: `kotlinx-collections-immutable` (~50 KB APK impact).
> NO MVI library is adopted — the stand-alone path with StateFlow + sealed
> Action + modular reducers is viable (see the library comparison in the
> main-plan iteration log F-9).

The `DictateUiStateStore` (§4.4) holds **one** `MutableStateFlow<DictateUiState>`. The data class aggregates all UI-relevant axes via sub-state classes — one immutable data class per functional domain:

```kotlin
// File: app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/**
 * Single Source of Truth für den UI-State der Dictate-IME.
 *
 * Immutable. Mutationen erzeugen neue Instanzen via `copy()`. Listen sind
 * `PersistentList` (kotlinx.collections.immutable) — kein versehentliches Mutate.
 *
 * Die Achsen sind in Sub-State-Klassen aufgeteilt, damit:
 * - jede Achse einen eigenen Reducer hat (SRP, im jeweiligen DictateModule, §15)
 * - Resolver pro Slot nur die Achse lesen, die sie brauchen (Performance + Lesbarkeit)
 * - neue Achsen ohne Berührung der Top-Level-Klasse ergänzt werden können (OCP)
 */
data class DictateUiState(

    // ─── Hot-Path-FSMs (sealed classes, eigene Reducer-Module) ───
    val recording: RecordingState,
    val pipeline: PipelineUiState,
    val viewMode: ViewMode,

    // ─── Layout / UI-Mode ───
    <!-- FIX: Issue 1.1.5 / R.5 – LayoutState-Container ersetzt Top-Level contentArea + LayoutPrefs -->
    val layout: LayoutState,
    val overlay: OverlayState,

    // ─── Subsysteme (Public-State-Snapshots, von DictateModule-Implementierungen verwaltet) ───
    val audio: AudioState,
    val resend: ResendState,
    val livePrompt: LivePromptState,
    val language: LanguageState,

    // ─── Pref-Mirror (von PipelinePrefMirror gefüllt, §4.6) ───
    val features: FeatureToggles,
    val theming: ThemingState,

    // ─── DB-Subscriber-getrieben ───
    val pendingSessions: PersistentList<PendingSession>,

    <!-- FIX: Issue 2.1.9 – lastResultNeedsManualPaste-Flag (User-Decision Option C: Clipboard + persistenter pending-Marker) -->
    /** Gesetzt, wenn nach IME-Service-Death eine Pipeline-Done-Notification dem User eine manuelle
     *  Paste-Aktion aus dem System-Clipboard signalisiert. UI-Hint im Keyboard-Header. */
    val lastResultNeedsManualPaste: Boolean = false,

    // ─── Phase 2 (default null = nicht modelliert) ───
    val interruption: InterruptionState? = null,
) {
    companion object {
        fun initial(): DictateUiState = DictateUiState(
            recording = RecordingState.Idle,
            pipeline = PipelineUiState.Idle,
            viewMode = ViewMode.KEYBOARD,
            // <!-- FIX: R.5 – LayoutState bündelt contentArea + Layout-Prefs -->
            layout = LayoutState(),
            overlay = OverlayState(),
            audio = AudioState(),
            resend = ResendState(),
            livePrompt = LivePromptState(),
            language = LanguageState(effective = "system"),
            features = FeatureToggles(),
            theming = ThemingState(),
            pendingSessions = persistentListOf(),
            lastResultNeedsManualPaste = false,    // <!-- FIX: 2.1.9 -->
        )
    }
}

// ─── Sub-State-Klassen ───

<!-- FIX: Issue 1.1.5 / R.5 – LayoutPrefs → LayoutState mit contentArea-Feld -->
data class LayoutState(
    val contentArea: ContentArea = ContentArea.MAIN_BUTTONS,
    val singleRowMode: Boolean = false,
    val smallMode: Boolean = false,
    val animationsEnabled: Boolean = true,
)

<!-- FIX: Issue 3.1.7 + 3.1.3 – Suppress-Bit + Permission-Achse als State-Felder -->
data class OverlayState(
    val positionPortraitX: Float = 1.0f,    // normalisiert 0..1
    val positionPortraitY: Float = 0.1f,
    val positionLandscapeX: Float = 1.0f,
    val positionLandscapeY: Float = 0.1f,
    val userPrefersWidget: Boolean = false,
    val onboardingPending: Boolean = false,
    /** Issue 3.1.7 — gesetzt nach `closeOverlay`-Cascade (User schloss WIDGET in HOVER),
     *  verhindert Auto-Reopen für die laufende Session; reset beim nächsten `StartRecording`. */
    val suppressAutoOverlayUntilNextSession: Boolean = false,
    /** Issue 3.1.3 — Permission-Status als State-Achse (statt jeder Render Check).
     *  Wird vom OverlayPermissionObserver synchron gehalten. */
    val hasPermission: Boolean = false,
)

data class AudioState(
    val audioFocusEnabledPref: Boolean = true,    // User-Toggle (Pref-gespiegelt)
    val audioFocusGranted: Boolean = false,        // System-Status (asynchron)
    val bluetoothSco: BluetoothScoPublicState = BluetoothScoPublicState(),
    val useBluetoothMic: Boolean = false,
    val vibrationEnabled: Boolean = true,
)

data class BluetoothScoPublicState(
    val phase: ScoPhase = ScoPhase.Disconnected,
    val failureReason: String? = null,
)
enum class ScoPhase { Disconnected, Waiting, Connected, Failed }

data class ResendState(
    val lastAudioExists: Boolean = false,
    val resendEnabled: Boolean = false,    // Pref-Mirror
    val resendCooldown: Boolean = false,   // 500ms-Window nach Resend-Klick
)

data class LivePromptState(
    val enabled: Boolean = false,
    val pendingChain: Boolean = false,
)

data class LanguageState(
    val effective: String,
    val override: String? = null,           // ReprocessStaging-Override
)

data class FeatureToggles(
    val rewordingEnabled: Boolean = true,
    val autoFormattingEnabled: Boolean = false,
    val instantOutputEnabled: Boolean = true,
    val autoEnterEnabled: Boolean = false,
)

data class ThemingState(
    val theme: String = "system",
    val accentColor: Int = -14700810,
    val overlayCharacters: String = "()-:!?,.",
    val outputSpeed: Int = 5,
)

data class InterruptionState(
    val callIncoming: Boolean = false,
    val headsetPlugged: Boolean = false,
    val screenAwake: Boolean = true,
)

data class PendingSession(
    <!-- FIX: Issue 2.1.19 / R.15 – sessionId String (UUID) durchgängig -->
    val sessionId: String,
    val status: SessionStatus,    // RECORDED oder COMPLETED — RECORDING/TRANSCRIBING werden vor Eintritt in pendingSessions vom recoverFromDb umgeschrieben (siehe §6.1 + §6.3); FAILED/CANCELLED landen NIE in pendingSessions (terminal)
    val transcribedText: String?,
    val createdAt: Long,
)

enum class ViewMode { KEYBOARD, WIDGET, HOVER }

<!-- FIX: Issue 1.1.7 / R.2 + Issue 2.1.5 – audioFile lebt im RecordingState (Pure-Function-Garantie) -->
<!-- FIX: Issue 2.1.8 (User-Decision Option C) – Paused trägt useBluetooth-Field zur Cross-Module-Invariant-Sicherung -->
sealed interface RecordingState {
    object Idle : RecordingState
    data class Preparing(val useBluetooth: Boolean, val audioFile: java.io.File) : RecordingState
    data class Active(val useBluetooth: Boolean, val audioFile: java.io.File) : RecordingState
    data class Paused(val useBluetooth: Boolean, val audioFile: java.io.File) : RecordingState
}

<!-- FIX: Issue 2.1.10 / R.8 – Multi-Job-Modell mit sessionId-Tracking -->
sealed interface PipelineUiState {
    object Idle : PipelineUiState
    data class Preparing(val sessionId: String) : PipelineUiState
    data class Running(val sessionId: String, val target: InsertionTarget, val autoEnterActive: Boolean = false) : PipelineUiState
    data class ReprocessStaging(val sessionId: String, val transcript: String) : PipelineUiState
}
```

**Important:** `DictateUiState` and all sub-state classes are **immutable**. Every change creates a new instance that is emitted via `_state.value = newState`. Consumers react reactively via `state.collect { ... }`.

<!-- FIX: Issue 2.0.5 – PersistentList-Mutations-Idiom dokumentieren -->
**`PersistentList` mutation idiom (`pendingSessions` etc.):** `PersistentList` is
structurally shared. Reducers MUST use the native `add` / `remove` / `removeAt` /
`set` methods of `PersistentList` — a round-trip via `toMutableList()` +
`toPersistentList()` destroys the sharing.

```kotlin
// ✓ structural-share preserved
pendingSessions = current.pendingSessions.add(newSession)
pendingSessions = current.pendingSessions.removeAll { it.sessionId == id }

// ✗ allocates fresh list — Performance-Regression
pendingSessions = (current.pendingSessions + newSession).toPersistentList()
pendingSessions = current.pendingSessions.toMutableList()
    .apply { add(newSession) }
    .toPersistentList()
```

### Axis overview

<!-- FIX: Issue 2.0.1 – State-Achsen-Zähl-Korrektur (15 → 14) -->
<!-- FIX: Issue 1.1.5 / R.5 – contentArea wird in LayoutState konsolidiert (15 → 14 Sub-State-Felder, dann auf 13 reduziert) -->
13 state axes (= sub-state fields in `DictateUiState`), classified by responsibility. Nested sub-states (e.g. `BluetoothScoPublicState` as a detail of `audio`, `contentArea` as a detail of `layout`) do not count as standalone axes. Plus 1 top-level boolean (`lastResultNeedsManualPaste`) as the pipeline-service-death flag.

| # | Sub-state field | Owner module | Sources |
|---|---|---|---|
| 1 | `recording` | RecordingModule (§15.1) | sealed class RecordingState (4 states: Idle/Preparing/Active/Paused), carries `audioFile` + `useBluetooth` |
| 2 | `pipeline` | PipelineModule | sealed class PipelineUiState (4 states: Idle/Preparing/Running/ReprocessStaging), carries `sessionId` (R.8) |
| 3 | `viewMode` | ViewModeModule | enum ViewMode (KEYBOARD/WIDGET/HOVER) |
| 4 | `layout` | LayoutModule | data LayoutState (contentArea + 3 booleans, pref-mirror) — Issue 1.1.5/R.5 |
| 5 | `overlay` | OverlayModule | data OverlayState (4 floats + 4 booleans incl. suppressAutoOverlay + hasPermission) |
| 6 | `audio` | AudioModule | data AudioState (pref + system status + BluetoothSco) |
| 7 | `resend` | ResendModule | data ResendState (3 booleans) |
| 8 | `livePrompt` | LivePromptModule | data LivePromptState (2 booleans) |
| 9 | `language` | LanguageModule | data LanguageState (effective + override) |
| 10 | `features` | FeatureToggleModule | data FeatureToggles (5 booleans, pref-mirror) |
| 11 | `theming` | ThemingModule | data ThemingState (4 pref-mirrored values) |
| 12 | `pendingSessions` | PendingSessionsModule | PersistentList, DB-subscriber-driven |
| 13 | `interruption` | InterruptionModule (Phase 2) | data InterruptionState — default null |
| (top) | `lastResultNeedsManualPaste` | PipelineModule | boolean flag for IME-service-death recovery (Issue 2.1.9) |

ReprocessStaging is NOT a standalone module, but a sub-variant of the pipeline FSM (`PipelineUiState.ReprocessStaging`) — managed by the PipelineModule.

### Comparison to the current state

| Today | Future |
|-------|---------|
| `RecordingStateController.state` | `DictateUiState.recording` |
| `KeyboardUiController.state` | `DictateUiState.pipeline` |
<!-- FIX: R.5 – contentArea jetzt in LayoutState verschachtelt -->
| `KeyboardStateManager.contentArea` | `DictateUiState.layout.contentArea` |
| `KeyboardStateManager.isSmallMode` | `DictateUiState.layout.smallMode` |
| `Pref.SingleRowMode` (on demand) | `DictateUiState.layout.singleRowMode` (mirrored) |
| `Pref.ResendButton + getLastAudioFileExists()` (distributed) | `DictateUiState.resend.{lastAudioExists, resendEnabled, resendCooldown}` |
| `RecordingStateController.audioFocusEnabled` | `DictateUiState.audio.audioFocusEnabledPref` |
| `BluetoothScoManager._isScoStarted` (scattered) | `DictateUiState.audio.bluetoothSco.phase` |
| `LanguageController.lastEffective` | `DictateUiState.language.effective` |
| `livePrompt + pendingLivePromptChain` (service fields) | `DictateUiState.livePrompt.{enabled, pendingChain}` |
| `Pref.RewordingEnabled / AutoFormattingEnabled / InstantOutput / Vibration / AutoEnter` (on demand) | `DictateUiState.features.*` (all 5 mirrored) |
| `Pref.Theme / AccentColor / OverlayCharacters / OutputSpeed` (on demand) | `DictateUiState.theming.*` (all 4 mirrored) |
| none | `DictateUiState.viewMode` (new for triangle FSM) |
| none | `DictateUiState.pendingSessions` (new for restart button) |
| none | `DictateUiState.overlay.position*` (new, OPEN-3) |
| none | `DictateUiState.audio.audioFocusGranted` (system status, separate from pref) |

---

## §4 DictateOrchestrator + Modular Plugin Pattern

> **Architecture corrections F-1 + F-2 (2026-05-08):** Earlier spec versions
> had a monolithic `PipelineStateManager` with five responsibilities
> (state mutation + pref sync + FSM + recovery + JobExecutor init). Substructure
> into four helper classes + two dependency interfaces (`PipelineSessionRepo`,
> `PipelineRunner`) was introduced.
>
> **Architecture correction F-8 (2026-05-09):** Sealed `Action` class + LocalBinder API
> were redundant (~25 action variants defined twice). Correction: **Single
> Dispatch** via `dispatch(action: Action)` as the sole public entry.
> LocalBinder shrinks to `state` + `dispatch` + lifecycle hooks.
>
> **Architecture correction F-11 (2026-05-09):** Instead of a centralized reducer +
> EffectRunner with a large `when` over all axes, a **Modular Orchestrator
> Pattern** is introduced. Each module (Recording, Pipeline, Audio, …) encapsulates
> its sub-state + actions + reducer + side effects + effect handler in a
> single file. The `DictateOrchestrator` routes actions type-safely via
> `KClass<Action>` lookup to the right module, propagates cross-module effects
> via `onCrossModuleStateChange`. Inspired by the Excel-EKL Module-Augmentation
> Pattern, mapped to Kotlin via `sealed interface DictateModule` +
> `object` singletons.
>
> These three corrections turn the former `PipelineStateManager` into the
> significantly leaner `DictateOrchestrator`. The helper classes (Store,
> PrefMirror, Recovery) remain; ViewModeFsm moves into the ViewModeModule.

### §4.1 Architecture overview (Modular Orchestrator Pattern)

```
DictateOrchestrator (Composition Root + zentrale Steuerung, kennt nur DictateModule-Interface)
   │
   │   ─── Hilfsklassen (Querschnitts-Concerns) ───
   ├── DictateUiStateStore        SSoT-Container: MutableStateFlow + atomare update()-Methode
   ├── PipelinePrefMirror         Pref-Sync: init-Read + OnSharedPreferenceChangeListener → Store
   ├── PipelineRecovery           suspend recover(): lädt Pending-Sessions aus Repo in Store
   ├── ModuleServices             Container für injizierte Hardware-Subsysteme
   │
   │   ─── 14 Module (jeweils 1 Datei mit State + Action + Reducer + SideEffect + EffectHandler) ───
   │   ─── (13 aktiv in Phase 1 + 1 Phase-2-Stub) ───
   ├── RecordingModule            (Recording-Lifecycle, MediaRecorder-Trigger)
   ├── PipelineModule             (Pipeline-Verarbeitung, Job-Submission, ReprocessStaging)
   ├── AudioModule                (AudioFocus + BluetoothSco + Vibration)
   ├── ViewModeModule             (Triangle-FSM KEYBOARD/WIDGET/HOVER, ehemals ViewModeFsm)
   ├── OverlayModule              (Position-Persistierung + Onboarding-Status)
   ├── ResendModule               (lastAudioExists + Cooldown-Timer)
   ├── LivePromptModule           (Chain-Buffer + Pipeline-Verkettung)
   ├── LanguageModule             (Effective + Override)
   ├── LayoutModule               (singleRowMode, smallMode, animationsEnabled, contentArea)
   ├── FeatureToggleModule        (rewording, autoFormatting, instantOutput, autoEnter, vibration)
   ├── ThemingModule              (theme, accentColor, overlayCharacters, outputSpeed)
   ├── PendingSessionsModule      (DB-Subscriber-Pattern für Pending-Sessions-Liste)
   <!-- FIX: Phase-C C-1 (2026-05-14) – KeyboardInputModule nachgetragen (Phase-B S-3 hat das Modul
        in §15.6 + DictateModuleRegistry §4.8 verankert, aber im §4.1-Tree gefehlt). -->
   ├── KeyboardInputModule        (IME-Direkteingaben Backspace/Enter/Space/CopyToClipboard — Unit-State, §15.6)
   └── InterruptionModule         (Phase 2 — Anrufe, Headset-Plug, Screen-Off)
```

**Rationale:** Without this split:
1. `DictateUiState` would grow into a 30+-field data class (SRP violation) — solved by **sub-state classes** (F-10, §3)
2. The reducer + EffectRunner would grow into two large `when` blocks over all axes — solved by **modular reducers + effect handlers per module** (F-11)
3. Cross-axis logic would be scattered across central locations — solved by the `onCrossModuleStateChange` hook per module

| Class | Responsibility | Side effects | Testability |
|---|---|---|---|
| `DictateOrchestrator` | Composition Root + action routing + cross-module cascade dispatch | no (delegates to modules) | integration test with fake modules |
| `DictateUiStateStore` | StateFlow owner | no (pure data) | trivial: in/out reducer |
| `DictateModule<S, A, E>` (interface) | plugin contract | abstract | own test per module implementation |
| `RecordingModule` (example) | recording axis: state + reducer + effect handler | yes (hardware calls in runEffect) | reducer pure → unit-test without hardware |
| `PipelinePrefMirror` | SP ↔ store mirroring | yes (SP listener) | with `FakeSharedPreferences` |
| `PipelineRecovery` | DB → store replay | yes (DB) | with `FakePipelineSessionRepo` |
| `ModuleServices` | DI container for subsystems | passive | with fake subsystems |

### §4.2 DictateModule interface (F-11 / plugin contract)

<!-- FIX: Phase-C C-1 (2026-05-14) – Modul-Zähler aktualisiert (13 aktiv in Phase 1, KeyboardInputModule
     ergänzt durch Phase-B S-3 §15.6). Plus-1 Phase-2-Stub bleibt unerwähnt, weil das Interface
     hier den Phase-1-Vertrag beschreibt. -->
The `DictateModule` interface is the plugin contract. Each of the 13 active modules (see §15) implements this interface and fully encapsulates its functional domain: own sub-state, own actions, own reducer, own side effects, own effect handler, optional cross-module observer.

```kotlin
// File: app/src/main/java/net/devemperor/dictate/state/DictateModule.kt
package net.devemperor.dictate.state

import kotlin.reflect.KClass

/**
 * Kontrakt für ein Dictate-Modul. Jedes Modul kapselt eine fachliche Domäne
 * (Recording, Pipeline, Audio, …) vollständig in einer Datei.
 *
 * Type-Parameter:
 * - [S] — Sub-State-Typ des Moduls (z.B. RecordingState)
 * - [A] — Action-Sealed-Class-Typ des Moduls (z.B. Action.RecordingAction)
 * - [E] — SideEffect-Sealed-Class-Typ des Moduls (z.B. RecordingModule.Effect)
 *
 * Compile-Time-Garantie: `sealed interface DictateModule` schließt die
 * Hierarchie zur Compile-Zeit. Der Compiler kennt alle Module und kann
 * exhaustivity-Checks erzwingen.
 */
sealed interface DictateModule<S, A : Action, E : SideEffect> {

    /** Stable Identifier für das Modul (Logging, Debugging, Telemetrie). */
    val id: ModuleId

    /** Die Action-Klasse, für die dieses Modul zuständig ist. Type-safe Action-Routing. */
    val actionClass: KClass<A>

    // ─── State-Lens (read/write des Sub-States im DictateUiState) ───
    fun read(global: DictateUiState): S
    fun write(global: DictateUiState, sub: S): DictateUiState
    fun initialState(): S

    // ─── Reducer (F1+F2 Pure Function) ───
    /**
     * (sub-state, action, kontext) → (next-sub-state, side-effects).
     * Pure function — keine Hardware-Calls, deterministisch.
     * Return null = Action war im aktuellen State nicht erlaubt (F1-Verstoß).
     */
    fun reduce(state: S, action: A, ctx: ReducerContext): TransitionResult<S, E>?

    <!-- FIX: Phase-B S-3 (2026-05-13) – EffectFailure-Reducer als eigener Hook (Spec 2 §3.3). -->
    /**
     * Failure-Reducer für `Action.EffectFailure`. Wird vom Orchestrator gerufen, wenn
     * ein `runEffect(...)` dieses Moduls geworfen hat (Spec 1 §4.3, EffectFailure-Pfad
     * `dispatchInternal` Step 1a + 2). <!-- FIX: Phase-C C-1 (2026-05-14) – stale Z. 617 → Section-Anchor (Line-Drift nach S-3/S-4-Apply). --> Default
     * gibt `null` zurück — `DispatchOutcome.Rejected("reducer-null")` ist semantisch
     * korrekt ("kein Failure-Pfad definiert"). Module mit Recovery-Bedarf
     * überschreiben den Hook und führen einen State-Rollback durch (z.B.
     * `Preparing → Idle` mit gesetztem `lastErrorMessage`).
     *
     * **Warum nicht als zusätzlicher Branch in `reduce(...)`?** Weil `reduce`'s
     * Action-Parameter den Modul-spezifischen Typ `A` (z.B. `Action.RecordingAction`)
     * hat — `Action.EffectFailure` ist ein direkter `Action`-Subtyp, KEINE
     * `RecordingAction`. Ein gemeinsamer Hook wäre type-unsicher. `reduceFailure`
     * trennt die beiden Pfade sauber (ISP-konform).
     */
    fun reduceFailure(
        state: S,
        failure: Action.EffectFailure,
        ctx: ReducerContext,
    ): TransitionResult<S, E>? = null

    // ─── EffectHandler (Hardware/IO-Ausführung) ───
    /**
     * Führt einen SideEffect aus. Hängt typischerweise an Subsystem-Klassen,
     * die über `services` injiziert werden.
     */
    fun runEffect(effect: E, services: ModuleServices)

    // ─── Cross-Module-Observer (optional) ───
    /**
     * Wird gerufen NACHDEM ein anderes Modul seinen State mutiert hat. Erlaubt
     * diesem Modul, daraufhin selbst Actions zu emittieren (Cross-Module-Kaskade).
     * Default: keine Reaktion.
     */
    fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> = emptyList()

    <!-- FIX: Issue 2.1.2 (User-Decision Option A) – deklarative Pref-Bindings pro Modul -->
    <!-- FIX: Phase-B S-4 (2026-05-13) – Phase-1/Phase-2-Hinweis: Hook ist in Phase 1 Dead-Code (PrefMirror nutzt hardcoded Liste). -->
    /**
     * Pref-Bindings: deklarative Auflistung der SharedPreferences-Keys, die in den
     * Sub-State des Moduls gespiegelt werden.
     *
     * **Phase 1 (heute):** Default `emptyList()` — Implementierungen lassen den Hook
     * leer. `PipelinePrefMirror` (§4.5) verwendet ihn in Phase 1 **NICHT** (hardcodierte
     * Pref-Liste in `initialMirror` + `sync`). Module dürfen `prefBindings()` in
     * Phase 1 **NICHT** befüllen — das wäre Dead-Code, der die Phase-2-Migration
     * komplizierter macht (Doppel-Pref-Reads, potenzielle Race-Bugs).
     *
     * **Phase 2 (Backlog, siehe Hauptplan §7.1):** `prefBindings()` wird zur einzigen
     * Pref-Spiegelungs-Quelle. PrefMirror iteriert dann über
     * `modules.flatMap { it.prefBindings() }` — die hardcodierte Liste verschwindet.
     */
    fun prefBindings(): List<PrefBinding<S, *>> = emptyList()

    <!-- FIX: Issue 2.1.12 (User-Decision Option A+B) – terminale Cleanup-Sequenz pro Modul -->
    /**
     * Terminate — Cleanup-Effect, vom Service-`onDestroy` mit `runBlocking`-Timeout
     * gerufen. Module emittieren hier ihre finalen SideEffects (Hardware-Release,
     * Subsystem-Stop). Default: leer.
     */
    fun terminate(services: ModuleServices) = Unit
}

/** TransitionResult — (nextState, sideEffects-Liste) als Reducer-Output. */
data class TransitionResult<S, E : SideEffect>(
    val nextState: S,
    val sideEffects: List<E>,
)

<!-- FIX: Issue 2.1.2 / Pref-Binding-Type für Modul-API -->
/**
 * Deklarative Pref-Mirror-Spezifikation. Jedes Modul gibt seine Pref-Bindings
 * über `DictateModule.prefBindings()` zurück; der `PipelinePrefMirror`
 * konsolidiert sie für Initial-Read + Listener.
 */
data class PrefBinding<S, T>(
    val prefKey: String,
    val read: (SharedPreferences) -> T,
    val write: (S, T) -> S,
)

/** ModuleId-Aufzählung — Compile-Time-bekannt, ein Eintrag pro Modul. */
sealed interface ModuleId {
    data object Recording : ModuleId
    data object Pipeline : ModuleId
    data object Audio : ModuleId
    data object ViewMode : ModuleId
    data object Overlay : ModuleId
    data object Resend : ModuleId
    data object LivePrompt : ModuleId
    data object Language : ModuleId
    data object Layout : ModuleId
    data object FeatureToggle : ModuleId
    data object Theming : ModuleId
    data object PendingSessions : ModuleId
    <!-- FIX: Phase-B S-3 (2026-05-13) – KeyboardInputModule braucht eigene ID (§15.6). -->
    data object KeyboardInput : ModuleId
    data object Interruption : ModuleId
}

<!-- FIX: Issue 2.1.1 (User-Decision Option A) – ReducerContext exposed `global: DictateUiState` -->
<!-- FIX: Issue 1.1.7 / R.2 – recordingAudioFile entfällt (audioFile lebt jetzt im RecordingState) -->
/**
 * Kontext für Reducer — enthält den **kompletten Global-State** (für Cross-Achsen-Reads
 * wie `state.audio.useBluetoothMic`) plus monoton-`now` für Timer-Vergleiche.
 *
 * Hardware-Reads gehören NICHT in den Context — der Reducer ist pure relativ zu (state,
 * action, ctx). Hardware-Side-Effects laufen ausschließlich in `runEffect()`.
 */
data class ReducerContext(
    val global: DictateUiState,
    val now: Long = System.currentTimeMillis(),
)
```

<!-- FIX: Issue 2.0.6 – Exhaustivity-Konvention für reduce/runEffect when-Blöcke -->
**Exhaustivity convention for `reduce` / `runEffect` `when` blocks:**

- All module-internal `Effect` interfaces are `sealed interface` (analogous to the
  `Action` sealed class). This lets the compiler enforce exhaustivity.
- All `reduce` / `runEffect` `when` blocks are **expression-form**
  (`return when (action) { … }` or `when (effect) { … }` as statement-form
  over all sealed branches). The expression-form lets the compiler enforce the
  exhaustivity — missing branches are compile errors.
- An `else` branch is **only allowed for explicitly non-sealed effects** and
  requires a rationale comment (e.g. "Effect is OEM-extensible").
- Pure statement `when` without `else` is avoided — it loses the
  exhaustivity guarantee.

### §4.3 DictateOrchestrator (Composition Root)

The orchestrator replaces the former `PipelineStateManager`. It knows only the `DictateModule` interface and routes actions type-safely to the right module.

```kotlin
// File: app/src/main/java/net/devemperor/dictate/state/DictateOrchestrator.kt
package net.devemperor.dictate.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

<!-- FIX: Issue 1.1.4 + 2.1.7 / R.3 – DispatchOutcome statt unstrukturierter NoOp/null -->
/** Typisiertes Dispatch-Ergebnis — eliminiert die drei ununterscheidbaren Outcomes (Sec1-Logic L-7). */
sealed interface DispatchOutcome {
    object Applied : DispatchOutcome
    data class Rejected(val action: Action, val reason: String) : DispatchOutcome
    data class Unrouted(val action: Action) : DispatchOutcome
}

class DictateOrchestrator(
    private val scope: CoroutineScope,
    private val store: DictateUiStateStore,
    private val servicesFactory: ModuleServicesFactory,
    private val prefMirror: PipelinePrefMirror,
    private val recovery: PipelineRecovery,
    private val modules: List<DictateModule<*, *, *>> = DictateModuleRegistry.all,
) {
    val state: StateFlow<DictateUiState> = store.state

    <!-- FIX: Issue 2.1.6 / R.4 – sealed-leaves-Indexing: jede konkrete Action-Class genau einem Modul -->
    /**
     * Sealed-Leaves-Indexing. Jede konkrete (nicht-sealed) Action-Class wird beim Init genau
     * einem Modul zugeordnet. Doppelte Zuordnung ist Init-Time-Failure (DI-Container-Pattern).
     * Lookup ist O(1); Linear-Fallback entfällt.
     */
    private val moduleByLeafClass: Map<KClass<out Action>, DictateModule<*, *, *>> = run {
        val map = mutableMapOf<KClass<out Action>, DictateModule<*, *, *>>()
        modules.forEach { module ->
            collectLeaves(module.actionClass).forEach { leaf ->
                require(map.put(leaf, module) == null) {
                    "Action $leaf is routed to multiple modules — ambiguity detected at init"
                }
            }
        }
        map
    }

    private fun collectLeaves(c: KClass<out Action>): List<KClass<out Action>> =
        if (c.sealedSubclasses.isEmpty()) listOf(c)
        else c.sealedSubclasses.flatMap { @Suppress("UNCHECKED_CAST") collectLeaves(it as KClass<out Action>) }

    init {
        prefMirror.attach(store)
        scope.launch { recovery.recover(store) }
    }

    <!-- FIX: Issue 2.1.4 (User-Decision Option A) – emitAction async-via-scope; dispatch Main-Thread-confined -->
    /** Asynchrones Re-Entry für Effects/Listeners — frozen Cascade-Snapshot, keine sync-Reentrancy. */
    fun emitAction(action: Action) {
        scope.launch { dispatch(action) }
    }

    /** Single Dispatch — der einzige öffentliche Eingang für Mutationen (F-8). Main-Thread-confined. */
    fun dispatch(action: Action): DispatchOutcome = dispatchInternal(action, depth = 0)

    <!-- FIX: Issue 1.1.7 / R.6 – Cascade Loop-Guard via depth-counter (Cap 8); DEBUG-error / Release-Log -->
    <!-- FIX: Phase-B S-3 (2026-05-13) – moduleById-Lookup für EffectFailure-Origin-Routing. -->
    /**
     * Sekundärer Lookup: ModuleId → Modul. Wird ausschließlich für `Action.EffectFailure`
     * benutzt, weil dieser Failure-Channel an die [Action.EffectFailure.originModuleId]
     * gebunden ist (nicht an die Action-Klasse — alle Module produzieren denselben
     * Action-Typ als Failure, aber jedes Modul reagiert auf seinen eigenen).
     */
    private val moduleById: Map<ModuleId, DictateModule<*, *, *>> =
        modules.associateBy { it.id }

    private fun dispatchInternal(action: Action, depth: Int): DispatchOutcome {
        if (depth >= MAX_CASCADE_DEPTH) {
            val msg = "Cascade loop detected at depth=$depth, action=$action"
            if (BuildConfig.DEBUG) error(msg)
            android.util.Log.e(TAG, msg)
            return DispatchOutcome.Rejected(action, "cascade-loop")
        }

        <!-- FIX: Phase-B S-3 (2026-05-13) – EffectFailure-Origin-Routing (Spec 2 §3.3). -->
        // 1a. EffectFailure-Special-Case: an das ORIGIN-Modul routen, nicht via KClass-Lookup.
        //     Module ohne EffectFailure-Reducer-Arm returnen null → Rejected("reducer-null"),
        //     semantisch korrekt ("kein Failure-Pfad definiert"). KEIN Unrouted, weil das
        //     Modul existiert — nur die Failure-Behandlung fehlt explizit.
        val module: DictateModule<*, *, *> = if (action is Action.EffectFailure) {
            moduleById[action.originModuleId]
                ?: return DispatchOutcome.Unrouted(action)
        } else {
            // 1b. Reguläres KClass-Lookup für alle anderen Actions (type-safe via sealed-leaves).
            moduleByLeafClass[action::class]
                ?: return DispatchOutcome.Unrouted(action)
        }

        @Suppress("UNCHECKED_CAST")
        val typedModule = module as DictateModule<Any, Action, SideEffect>

        val prevGlobal = store.snapshot
        val subState = typedModule.read(prevGlobal)
        val ctx = buildContext(prevGlobal)

        <!-- FIX: Phase-B S-3 (2026-05-13) – EffectFailure-Pfad ruft reduceFailure (DictateModule §4.2). -->
        // 2. Reducer-Aufruf (F1+F2 pure function); null = "Action im aktuellen State nicht relevant".
        //    EffectFailure-Sonderpfad: ruft reduceFailure(...) statt reduce(...) — Module-API
        //    trennt Normal- vs. Failure-Reducer (siehe §4.2 reduceFailure-KDoc).
        val result = if (action is Action.EffectFailure) {
            typedModule.reduceFailure(subState, action, ctx)
        } else {
            typedModule.reduce(subState, action, ctx)
        } ?: return DispatchOutcome.Rejected(action, "reducer-null")

        // 3. State-Update atomar
        store.update { typedModule.write(it, result.nextState) }

        // 4. SideEffects vom eigenen Modul ausführen — Issue 2.1.3 (Option D): try/catch + EffectFailure-Action
        val services = servicesFactory.get()
        result.sideEffects.forEach { effect ->
            try {
                typedModule.runEffect(effect, services)
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "Effect failure in ${typedModule.id}: $effect", t)
                <!-- FIX: Phase-B S-3 (2026-05-13) – EffectFailure trägt originModuleId für Origin-Routing (Spec 2 §3.3). -->
                // Re-dispatch typed failure — keep cascade depth, do not crash IME.
                // originModuleId zeigt dem Orchestrator zurück auf das emittierende Modul,
                // damit dessen Reducer den passenden Sub-State-Rollback vornehmen kann.
                dispatchInternal(
                    Action.EffectFailure(
                        originModuleId = typedModule.id,
                        effect = effect.toString(),
                        reason = t.message ?: t.javaClass.simpleName,
                    ),
                    depth + 1,
                )
            }
        }

        // 5. Cross-Module-Observation: andere Module reagieren auf den State-Change
        // Cascade-Snapshot eingefroren: alle Observer sehen denselben (prev, next), kein Race.
        //
        // ╔═══════════════════════════════════════════════════════════════════════════╗
        // ║ ⚠ DO NOT RE-ADD SELF-FILTER (KG-RSB-2, 2026-05-11)                        ║
        // ║                                                                           ║
        // ║ Es gab hier einen `modules.filter { it.id != module.id }`-Aufruf.         ║
        // ║ Er ist BEWUSST entfernt — Self-Cascade ist Pflicht (siehe §15.2           ║
        // ║ RecordingModule: Idle→Preparing → OverlayAction.ResetSuppressBit).        ║
        // ║                                                                           ║
        // ║ Wenn ein zukünftiger Maintainer ihn als "looks like infinite-loop guard"  ║
        // ║ wieder einfügt:                                                           ║
        // ║   → der Regression-Test                                                   ║
        // ║     `DictateOrchestratorTest.recordingModule_idleToPreparing_emits...`    ║
        // ║     (§10 R.RSB-FIX-A) schlägt rot fehl,                                   ║
        // ║   → das HOVER-Overlay reopent sich nach dem ersten User-Close nicht mehr  ║
        // ║     in derselben Session (Production-Bug-Klasse KG-RSB-2).                ║
        // ║                                                                           ║
        // ║ Endlos-Cascade-Schutz: MAX_CASCADE_DEPTH (R.6, Cap 8), siehe oben.        ║
        // ║ Der frühere Self-Filter war redundante Belt-and-Suspenders-Sicherheit.    ║
        // ╚═══════════════════════════════════════════════════════════════════════════╝
        val nextGlobal = store.snapshot
        val cascadeActions = modules
            .flatMap { it.onCrossModuleStateChange(prevGlobal, nextGlobal) }

        // 6. Cascade-Actions rekursiv dispatchen (depth-counter inkrementiert)
        cascadeActions.forEach { dispatchInternal(it, depth + 1) }

        return DispatchOutcome.Applied
    }

    <!-- FIX: Issue 1.1.7 / R.2 – buildContext mirror-only: kein Hardware-Read, audioFile kommt aus state -->
    private fun buildContext(global: DictateUiState) = ReducerContext(global = global)

    <!-- FIX: Phase-B S-1 (2026-05-13) – shutdown() ruft jetzt jedes Modul-`terminate()` (Issue 2.1.12 / D7). Frühere Implementation rief nur prefMirror.detach() — Module-Cleanup wäre ungemacht geblieben. -->
    <!-- FIX: Phase-B S-4 (2026-05-13) – Aufrufer-Vertrag: shutdown() MUSS vor serviceScope.cancel() laufen. -->
    /**
     * Service-Shutdown. Reihenfolge:
     *  1. PrefMirror detachen (kein neuer SP-Listener-Fire mehr).
     *  2. Pro Modul `terminate(services)` rufen — Module emittieren ihre finalen
     *     SideEffects (RecordingManager.release, BluetoothSco.stop, …) synchron.
     *  3. Service.onDestroy ruft anschließend `serviceScope.cancel()` und cancelt
     *     restliche in-flight Coroutines (siehe §7.3 onDestroy).
     *
     * Module-`terminate`-Calls dürfen blockieren (max. 1–2 s), weil sie unter
     * `runBlocking`-Timeout des Service.onDestroy laufen sollen — Android-FGS
     * gibt ~5 s, siehe §11.1.4. Effekte sind hier synchron-Hardware-Releases
     * (kein Coroutine-Suspend).
     *
     * **Aufrufer-Vertrag (Phase-B S-4):** Aufrufer (typischerweise `Service.onDestroy`)
     * MUSS `shutdown()` **vor** `serviceScope.cancel()` rufen. Andernfalls laufen
     * Module-`terminate(services)`-Calls auf einem gecancellten Scope — synchrone
     * Hardware-Releases funktionieren noch, aber alle async-Cleanup-Schritte
     * (Notification-cancel, DB-Flush, etc.) werden silent-no-op. Diese Reihenfolge
     * ist durch `OrchestratorShutdownOrderTest.kt` (Block-2-Acceptance, §10)
     * verifiziert — der Test assertet via Mock-Module-`terminate` auf
     * `services.scope.isActive == true` während des Aufrufs.
     */
    fun shutdown() {
        prefMirror.detach()
        val services = servicesFactory.get()
        modules.forEach { module ->
            try {
                @Suppress("UNCHECKED_CAST")
                (module as DictateModule<Any, Action, SideEffect>).terminate(services)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "module ${module.id} terminate failed", t)
                // Ein Modul-Failure darf andere Module nicht blockieren.
            }
        }
    }

    companion object {
        private const val TAG = "DictateOrchestrator"
        private const val MAX_CASCADE_DEPTH = 8
    }
}
```

**Cascade depth counter (R.6):** A loop is aborted in DEBUG via `error()` and in Release via a logger error
— the IME never crashes. Cap 8 is conservative; real cascade depths are 1–3
(e.g. RecordingDone → ResendModule.onCrossModuleStateChange → Action.ResendAction.MarkAvailable).

<!-- FIX: Phase-B S-4 (2026-05-13) – Cascade-Order-Vertrag explizit verankert (vorher implizit via modules.flatMap). -->
**Cascade-order contract (Phase-B S-4):** The order of the cascade actions is
<!-- FIX: Phase-C C-1 (2026-05-14) – stale Z. 1017-1033 → Section-Anchor (Line-Drift nach S-3/S-4-Apply). -->
deterministic and follows the order of `DictateModuleRegistry.all` (§4.8 `modules` list).
Each recursive `dispatchInternal(cascadeAction, depth+1)` (Step 6) takes a **fresh**
`prevGlobal`/`nextGlobal` snapshot — cascade actions thus see the state **including**
previous cascade mutations from this pass.

> **Convention:** Cross-module cascades should mutate disjoint state axes —
> a module must NOT plan into its cascade that another cascade pass
> mutates the state BEFORE it. If an order dependency becomes necessary, that is
> a Mode-3 use case (Atomic Cross-Axis Update, Phase-2 backlog, §14 Open-Q 4).
>
> **Code-review obligation:** Reordering the module list in `DictateModuleRegistry.all`
> is a plan-relevant refactor (Phase-B repetition required), not
> code cleanup. The order is verified via `DictateOrchestratorCascadeOrderTest.kt`
> (Block-1b acceptance).

<!-- FIX: Phase-B S-4 (2026-05-13) – ProGuard-Keep-Regel ist Pflicht für `KClass.sealedSubclasses`-Reflection. -->
<!-- FIX: Phase-C C-1 (2026-05-14) – stale Z. 587-589 → Method-Name (Line-Drift nach S-3/S-4-Apply). -->
> **⚠ ProGuard/R8 keep rule is mandatory (Phase-B S-4):** `collectLeaves` (see the `DictateOrchestrator` body)
> uses `KClass.sealedSubclasses` — reflection over the action hierarchy.
> ProGuard default behavior in release builds strips this hierarchy away if
> the classes are not explicitly kept — `sealedSubclasses` then returns an
> **empty list**, `moduleByLeafClass` is **empty**, every action dispatch becomes
> `DispatchOutcome.Unrouted` → silent-drop of **all** actions in the release build.
> Bug class identical to S-3 F-1/F-2, only more cataclysmic (all 14 sealed
> action subtypes affected).
>
> **Concrete ProGuard patch** (to be added in `app/proguard-rules.pro`, Block 1b):
>
> ```proguard
> # Phase-B S-4 — sealed-leaves-Indexing braucht intakte Action-Hierarchie.
> -keep,allowobfuscation,allowshrinking class net.devemperor.dictate.state.Action
> -keep,allowobfuscation,allowshrinking class * extends net.devemperor.dictate.state.Action { *; }
> -keepclassmembers class kotlin.reflect.** { *; }
> # Hinweis: `allowobfuscation` erlaubt Namens-Verkürzung, der Class-Reference-Pfad
> # über `KClass` bleibt intakt. Subclasses dürfen geshrunken werden, weil sie via
> # Modul-Registry referenziert sind — aber die Top-Level-Hierarchie muss bleiben.
> ```
>
> **Acceptance:** Block-1b adds an `OrchestratorReleaseSmokeTest.kt`
> (instrumented) that verifies a release build (`./gradlew assembleRelease`
> + `adb install`). The test dispatches a concrete action and asserts
> `DispatchOutcome.Applied` (not `Unrouted`).
>
> **Cross-link:** The completeness check in `DictateModuleRegistry.init` (§4.8)
> also uses `Action::class.sealedSubclasses` — both reflection sites
> benefit from the same keep rule.

**Sealed-leaves-indexing (R.4):** Each concrete action class is mapped to exactly one module;
a violation is an init-time error (DI-container pattern, analogous to Hilt/Dagger).

**Reentrancy contract (2.1.4 Option A):** `dispatch()` is main-thread-confined, frozen-cascade.
Effects or listeners that want to emit a new action MUST use `emitAction()` (async-via-scope)
— never `dispatch()` re-entrant from an effect body. LocalBinder top-level protection:
a caught `Throwable` is converted into an `Action.EffectFailure` (Issue 2.1.3 Option D).

**SRP verification:** The orchestrator does exclusively action routing + cross-module cascade. It knows **no** module by name, knows **no** recording/pipeline/audio logic — only the `DictateModule` interface. Adding a new axis requires NO change to the orchestrator.

### §4.4 DictateUiStateStore (SSoT container)

```kotlin
class DictateUiStateStore(initial: DictateUiState = DictateUiState.initial()) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<DictateUiState> = _state.asStateFlow()

    /** Atomare Mutation: liest aktuellen State, übergibt an Reducer, emittiert. */
    fun update(reducer: (DictateUiState) -> DictateUiState) {
        _state.update(reducer)
    }

    /** Snapshot — nur für Reducer-/FSM-Berechnungen, NIE für View-Updates. */
    val snapshot: DictateUiState get() = _state.value
}
```

**SRP:** pure data management. No pref reads, no action methods, no side effects.

<!-- FIX: Issue 1.0.4 – Java-Brücken-Notiz analog ActiveJobRegistryObserver -->
<!-- FIX: Phase-B S-3 (2026-05-13) – Java-Brücke vollständig spezifiziert (vorher nur "vorgesehen für Block 2"). -->
**Java bridge `DictateUiStateObserver`** — analogous to `core/ActiveJobRegistryObserver.kt`,
becomes a Block-2 acceptance precondition. Template ported 1:1:

```kotlin
// File: app/src/main/java/net/devemperor/dictate/state/DictateUiStateObserver.kt
package net.devemperor.dictate.state

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Java-friendly bridge to [DictateUiStateStore]'s reactive [StateFlow].
 *
 * Pattern-identisch zu [net.devemperor.dictate.core.ActiveJobRegistryObserver]
 * (Vorlage). Bindet den `state.collect`-Lifecycle an einen [LifecycleOwner] via
 * [repeatOnLifecycle] — der Callback feuert nur, solange der Owner mindestens
 * STARTED ist. Java-Konsumenten erhalten den aktuellen [DictateUiState]-Snapshot
 * synchron auf dem Main-Thread bei jeder State-Emission.
 *
 * **Threading-Vertrag:** Callback läuft auf dem Main-Thread (FGS-`serviceScope` ist
 * `Dispatchers.Main.immediate`). Java-Sites dürfen direkt View-Mutationen machen.
 *
 * **Lifecycle-Vertrag:** Der Job wird beim STOP des Owners gecancelled (über
 * `repeatOnLifecycle`); beim erneuten START wird ein neuer Job gestartet und
 * der aktuelle State-Wert wird sofort einmal an den Listener gegeben (StateFlow-
 * Replay-Semantik).
 *
 * Beispiel (Java):
 * ```
 * DictateUiStateObserver.observe(this, pipeline.getState(), state -> {
 *     // state ist der aktuelle DictateUiState-Snapshot
 *     updateMyJavaUi(state);
 * });
 * ```
 *
 * **Konsumenten (Block 2 + spätere):**
 * - `DictateInputMethodService.java` (Block 2): Subscribes für Pipeline/Recording-
 *   State, ersetzt heutige Callbacks (`recordingStateController.setCallback(...)` etc.).
 * - `HistoryAdapter.java` (Block 3): Subscribes für `pendingSessions`-Liste, ersetzt
 *   heutiges `ActiveJobRegistryObserver` (oder co-existiert temporär).
 * - `HistoryDetailActivity.java` (Block 3): Subscribes für `pipeline`-Achse, falls
 *   die Detail-View State-Updates braucht (heute polled sie in `onResume`).
 */
object DictateUiStateObserver {

    @JvmStatic
    fun observe(
        owner: LifecycleOwner,
        state: StateFlow<DictateUiState>,
        listener: Listener,
    ): Job = owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            state.collect { snapshot -> listener.onStateChanged(snapshot) }
        }
    }

    /** Functional-interface-compatible listener so Java lambdas work. */
    fun interface Listener {
        fun onStateChanged(snapshot: DictateUiState)
    }
}
```

**Block-2 acceptance** (added in §10): The bridge is set up; at least one Java
consumer (`DictateInputMethodService.java`) uses it instead of direct callbacks.

### §4.5 PipelinePrefMirror (Pref sync, F-10 adjusted to sub-state structure)

Extended by the 9 additional UI-state-relevant prefs (RewordingEnabled, AutoFormattingEnabled, InstantOutput, Vibration, Theme, AccentColor, OverlayCharacters, OutputSpeed, UseBluetoothMic) and maps onto the new sub-state structure:

<!-- FIX: Phase-B S-4 (2026-05-13) – Phase-1/Phase-2-Hinweis: aktuelle Implementierung ist hardcoded, prefBindings()-API ist Phase-2. -->
> **Phase 1 vs. Phase 2 (Phase-B S-4):** The `initialMirror` and
> `sync` implementation below is **Phase 1** — hardcoded mappings for 19 prefs onto
<!-- FIX: Phase-C C-1 (2026-05-14) – stale Z. 462 → Section-Anchor (Line-Drift). -->
> the sub-state axes. The `DictateModule.prefBindings()` API (§4.2 `prefBindings()` hook) is
> **NOT** consumed in Phase 1. Phase 2 (main plan §7.1 out-of-scope) replaces
> the hardcodes with iteration over `modules.flatMap { it.prefBindings() }` —
> then modules will declare their prefs declaratively. During Phase 1: **NO**
> module pref hook is consumed, otherwise double mirroring with race risk.

```kotlin
class PipelinePrefMirror(
    private val sp: SharedPreferences,
) {
    private var store: DictateUiStateStore? = null
    private val listener = OnSharedPreferenceChangeListener { _, key -> sync(key) }

    fun attach(store: DictateUiStateStore) {
        this.store = store
        store.update { initialMirror(it) }
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    fun detach() {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
        store = null
    }

    private fun initialMirror(current: DictateUiState): DictateUiState = current.copy(
        layout = current.layout.copy(
            singleRowMode = sp.get(Pref.SingleRowMode),
            smallMode = sp.get(Pref.SmallMode),
            animationsEnabled = sp.get(Pref.Animations),
        ),
        audio = current.audio.copy(
            audioFocusEnabledPref = sp.get(Pref.AudioFocus),
            useBluetoothMic = sp.get(Pref.UseBluetoothMic),
            vibrationEnabled = sp.get(Pref.Vibration),
        ),
        resend = current.resend.copy(
            resendEnabled = sp.get(Pref.ResendButton),
        ),
        features = current.features.copy(
            rewordingEnabled = sp.get(Pref.RewordingEnabled),
            autoFormattingEnabled = sp.get(Pref.AutoFormattingEnabled),
            instantOutputEnabled = sp.get(Pref.InstantOutput),
            autoEnterEnabled = sp.get(Pref.AutoEnter),
        ),
        theming = current.theming.copy(
            theme = sp.get(Pref.Theme),
            accentColor = sp.get(Pref.AccentColor),
            overlayCharacters = sp.get(Pref.OverlayCharacters),
            outputSpeed = sp.get(Pref.OutputSpeed),
        ),
        overlay = current.overlay.copy(
            positionPortraitX = sp.getFloat(Pref.OverlayPositionPortraitX.key, 1.0f),
            positionPortraitY = sp.getFloat(Pref.OverlayPositionPortraitY.key, 0.1f),
            positionLandscapeX = sp.getFloat(Pref.OverlayPositionLandscapeX.key, 1.0f),
            positionLandscapeY = sp.getFloat(Pref.OverlayPositionLandscapeY.key, 0.1f),
        ),
    )

    private fun sync(key: String?) {
        store?.update { current ->
            when (key) {
                Pref.SingleRowMode.key      -> current.copy(layout = current.layout.copy(singleRowMode = sp.get(Pref.SingleRowMode)))
                Pref.SmallMode.key          -> current.copy(layout = current.layout.copy(smallMode = sp.get(Pref.SmallMode)))
                Pref.Animations.key         -> current.copy(layout = current.layout.copy(animationsEnabled = sp.get(Pref.Animations)))
                Pref.AudioFocus.key         -> current.copy(audio = current.audio.copy(audioFocusEnabledPref = sp.get(Pref.AudioFocus)))
                Pref.UseBluetoothMic.key    -> current.copy(audio = current.audio.copy(useBluetoothMic = sp.get(Pref.UseBluetoothMic)))
                Pref.Vibration.key          -> current.copy(audio = current.audio.copy(vibrationEnabled = sp.get(Pref.Vibration)))
                Pref.ResendButton.key       -> current.copy(resend = current.resend.copy(resendEnabled = sp.get(Pref.ResendButton)))
                Pref.RewordingEnabled.key   -> current.copy(features = current.features.copy(rewordingEnabled = sp.get(Pref.RewordingEnabled)))
                Pref.AutoFormattingEnabled.key -> current.copy(features = current.features.copy(autoFormattingEnabled = sp.get(Pref.AutoFormattingEnabled)))
                Pref.InstantOutput.key      -> current.copy(features = current.features.copy(instantOutputEnabled = sp.get(Pref.InstantOutput)))
                Pref.AutoEnter.key          -> current.copy(features = current.features.copy(autoEnterEnabled = sp.get(Pref.AutoEnter)))
                Pref.Theme.key              -> current.copy(theming = current.theming.copy(theme = sp.get(Pref.Theme)))
                Pref.AccentColor.key        -> current.copy(theming = current.theming.copy(accentColor = sp.get(Pref.AccentColor)))
                Pref.OverlayCharacters.key  -> current.copy(theming = current.theming.copy(overlayCharacters = sp.get(Pref.OverlayCharacters)))
                Pref.OutputSpeed.key        -> current.copy(theming = current.theming.copy(outputSpeed = sp.get(Pref.OutputSpeed)))
                Pref.OverlayPositionPortraitX.key  -> current.copy(overlay = current.overlay.copy(positionPortraitX = sp.getFloat(key, 1.0f)))
                Pref.OverlayPositionPortraitY.key  -> current.copy(overlay = current.overlay.copy(positionPortraitY = sp.getFloat(key, 0.1f)))
                Pref.OverlayPositionLandscapeX.key -> current.copy(overlay = current.overlay.copy(positionLandscapeX = sp.getFloat(key, 1.0f)))
                Pref.OverlayPositionLandscapeY.key -> current.copy(overlay = current.overlay.copy(positionLandscapeY = sp.getFloat(key, 0.1f)))
                else                        -> current
            }
        }
    }
}
```

**SRP:** encapsulates the pref-mirroring pattern as its own class. Extended by 9 additional UI-state-relevant prefs (see Block 3.5 class A).

### §4.6 PipelineRecovery (DB replay)

```kotlin
class PipelineRecovery(
    private val sessionRepo: PipelineSessionRepo,
) {
    suspend fun recover(store: DictateUiStateStore) {
        val pending = sessionRepo.loadPending()
        store.update { it.copy(pendingSessions = pending.toPersistentList()) }
    }
}
```

**SRP:** isolates the DB recovery from the state mutator and modules.

### §4.7 ModuleServices + ModuleServicesFactory

```kotlin
/**
 * DI-Container für Subsysteme, die EffectHandler brauchen. Wird vom Orchestrator
 * an `module.runEffect(effect, services)` übergeben.
 */
class ModuleServices(
    <!-- FIX: Phase-B S-4 (2026-05-13) – RecordingHardwareSubsystem.allocate erwartet (target, useBluetooth, audioFile) — siehe §15.2 Effect.AllocateMediaRecorder. -->
    /**
     * Recording-Hardware-Adapter. Erwartete `allocate`-Signatur:
     * `fun allocate(target: InsertionTarget, useBluetooth: Boolean, audioFile: File)` —
     * der `audioFile`-Pfad lebt im State (R.2), Hardware-Subsystem erhält ihn als
     * Effect-Argument (siehe §15.2 `Effect.AllocateMediaRecorder` 3-Arg-Form +
     * EffectHandler-Use). Konkrete Konstruktion wandert in Block 2/4
     * (`ModuleServicesFactory` im Service-onCreate, §4.11.5.3).
     */
    val recordingHardware: RecordingHardwareSubsystem,
    val bluetoothSco: BluetoothScoSubsystem,
    val audioFocus: AudioFocusSubsystem,
    val recordingTimer: RecordingTimer,
    val amplitudeStream: AmplitudeStream,
    val borderGlow: BorderGlowAnimation,
    val pipelineRunner: PipelineRunner,
    val sessionRepo: PipelineSessionRepo,
    val notificationCoordinator: PipelineNotificationCoordinator,
    val inputConnectionProvider: () -> android.view.inputmethod.InputConnection?,
    <!-- FIX: Phase-B S-3 (2026-05-13) – clipboard für KeyboardInputModule (§15.6). -->
    /**
     * System-Clipboard für `Action.KeyboardInputAction.CopyToClipboard`. Im Service-
     * onCreate via `getSystemService(CLIPBOARD_SERVICE) as ClipboardManager` gesetzt.
     * Nullable, weil Android das `ClipboardManager` theoretisch verweigern kann (z.B.
     * Headless-Test-Umgebungen) — Effect-Handler behandelt null als no-op.
     */
    val clipboard: android.content.ClipboardManager?,
    val sharedPrefs: android.content.SharedPreferences,
    val toastSink: ToastSink,
    /**
     * Pre-Dispatch-Allocator für Audio-Cache-Files. Wird vom IME bzw. von
     * Pre-Dispatch-Resolvern (Spec 2 §10) gerufen, um das `audioFile`-Argument
     * von `Action.RecordingAction.StartRecording` zu produzieren — bevor der
     * Reducer läuft. Definition + Lifecycle + Failure-Modi siehe §4.11
     * (Sequence: §4.11.5.1; Service-Wiring: §4.11.5.3; Failure-Modi: §4.11.10).
     */
    val audioFileFactory: AudioFileFactory,
    /**
     * FGS-`serviceScope` (`SupervisorJob() + Dispatchers.Main.immediate`).
     * Wird in `Service.onDestroy` über `serviceScope.cancel()` beendet —
     * alle in-flight Effects sind danach gecancelt.
     * EffectHandlers MÜSSEN ihre Background-Coroutines in diesem Scope starten;
     * Effects, die das Service-Lifetime überdauern müssen, gehören in einen
     * separaten Worker (heute kein Use-Case).
     */
    val scope: kotlinx.coroutines.CoroutineScope,
    /**
     * Posts eine Action an den Orchestrator. Ausführung **immer asynchron**
     * über `scope.launch { dispatch(action) }` — re-entrant Aufrufe aus
     * `runEffect` heraus sind sicher, weil sie als nächste Main-Looper-Message
     * landen. Zählt als frische Cascade-Tiefe (siehe §4.3 Cascade-Tiefen-Counter).
     *
     * Hinweis (FIX 2.0.7): "async via scope" folgt der Standard-MVI-Konvention
     * (Compose-MVI / Redux-Toolkit). Falls Issue 2.1.4 (Orchestrator-
     * Reentrancy-Vertrag) auf Variante B (synchrone Queue) entscheidet, wird
     * dieser KDoc-Block dort übersteuert.
     */
    val emitAction: (Action) -> Unit,    // für Effect-Handler, die wieder Actions feuern
)
<!-- FIX: Issue 2.0.7 – KDoc-Vertrag für ModuleServices.scope / emitAction -->

/** Lazy-Provider — Services werden beim Service-onCreate konstruiert, vom Orchestrator
 *  per Provider abgefragt. */
class ModuleServicesFactory(private val provider: () -> ModuleServices) {
    fun get(): ModuleServices = provider()
}
```

### §4.8 DictateModuleRegistry (central module list)

```kotlin
import net.devemperor.dictate.state.modules.*

object DictateModuleRegistry {
    <!-- FIX: Phase-B S-4 (2026-05-13) – Reihenfolge ist deterministisch + Code-Review-relevant (Cascade-Order, §4.3). -->
    /**
     * **Reihenfolge: deterministisch + Code-Review-relevant.** Cross-Module-Cascades
     * (§4.3 Step 5–6) folgen dieser Reihenfolge: jede Cascade-Action wird in
     * dieser Reihenfolge aus `modules.flatMap { onCrossModuleStateChange(...) }`
     * extrahiert und rekursiv dispatcht. Jeder rekursive Dispatch sieht einen
     * **frischen** State-Snapshot — Cascade-Actions sehen damit den State
     * **inklusive** vorheriger Cascade-Mutationen aus diesem Pass.
     *
     * **Reorder ist Plan-relevant:** ein Refactor, der die Reihenfolge ändert
     * (z.B. alphabetisch sortieren beim Code-Cleanup), verändert observable
     * Cascade-Semantik. Phase-B-Wiederholung erforderlich; kein reines
     * Code-Cleanup. Cascade-Order-Vertrag siehe §4.3 (Hinweis-Block unter
     * dispatchInternal).
     */
    val all: List<DictateModule<*, *, *>> = listOf(
        RecordingModule,
        PipelineModule,
        AudioModule,
        ViewModeModule,
        OverlayModule,
        ResendModule,
        LivePromptModule,
        LanguageModule,
        LayoutModule,
        FeatureToggleModule,
        ThemingModule,
        PendingSessionsModule,
        <!-- FIX: Phase-B S-3 (2026-05-13) – KeyboardInputModule in Registry (§15.6). -->
        KeyboardInputModule,
        // InterruptionModule (Phase 2 — auskommentiert bis aktiv)
    )

    <!-- FIX: Phase-B S-4 (2026-05-13) – Init-Sanity-Check um Vollständigkeits-Check erweitert (S-3 Follow-Up F-7). -->
    /**
     * Init-Sanity-Check: drei strukturelle Invarianten.
     *
     *  1. **Eindeutige ModuleIds** — kein Doppel-Eintrag in `all`.
     *  2. **Eindeutige actionClasses (kein Doppel-Routing)** — DI-Container-Pattern.
     *  3. **Vollständige Routing (kein Fehlend-Routing, Phase-B S-4):** jede
     *     direkte sealed-Subclass von `Action::class` muss von genau einem Modul
     *     beansprucht sein (mit Excludelist für Special-Case-Subtypes wie
     *     `Action.EffectFailure`, das via `originModuleId` geroutet wird).
     *     Verstoß ist Init-Time-Failure — fängt die S-3-Bug-Klasse "Action
     *     ohne Modul-Owner → silent-drop" bereits beim App-Start.
     *
     * **ProGuard-Abhängigkeit:** Check #3 verwendet `Action::class.sealedSubclasses`
     * (Reflection). Die ProGuard-Keep-Regel aus §4.3 ist Pflicht — sonst
     * `sealedSubclasses == emptyList()` → false-positive "alle Subtypen fehlen"
     * beim ersten Release-Build.
     */
    init {
        val ids = all.map { it.id }
        require(ids.toSet().size == ids.size) {
            "Doppelte ModuleId: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}"
        }
        val actionClasses = all.map { it.actionClass }
        require(actionClasses.toSet().size == actionClasses.size) {
            "Doppelte actionClass-Zuordnung in Registry"
        }

        // Phase-B S-4: Vollständigkeits-Check via sealed-leaves-Traversal.
        // Excludelist: Action-Subtypes, die NICHT via actionClass-Lookup geroutet werden.
        val specialCaseSubtypes: Set<kotlin.reflect.KClass<out Action>> = setOf(
            Action.EffectFailure::class,    // via originModuleId-Routing (§4.3)
        )
        val claimedClasses: Set<kotlin.reflect.KClass<out Action>> = actionClasses.toSet()
        val allDirectSubtypes: Set<kotlin.reflect.KClass<out Action>> =
            @Suppress("UNCHECKED_CAST")
            (Action::class.sealedSubclasses as List<kotlin.reflect.KClass<out Action>>).toSet()
        val missing = allDirectSubtypes - claimedClasses - specialCaseSubtypes
        require(missing.isEmpty()) {
            "Fehlende Modul-Routing für Action-Subtypen: ${missing.map { it.simpleName }}. " +
                "Jede direkte sealed-Subclass von Action::class muss von einem Modul beansprucht " +
                "sein (außer Special-Cases wie EffectFailure)."
        }
    }
}
```

<!-- FIX: Phase-B S-4 (2026-05-13) – Klärung: zwei verschiedene Reflection-Entscheidungen (Modul-Liste manuell vs. Action-Leaves reflection). -->
**Manual module list vs. reflection-based action leaves (Phase-B S-4):**

The plan uses **two different** reflection decisions:

| Hierarchy | Mechanism | R8/ProGuard risk | Mitigation |
|---|---|---|---|
| **Module registry** (`DictateModuleRegistry.all`) | manual list (above) | No risk — classes are referenced as `object` singletons in the list, R8 sees the refs | n/a |
| **Action-leaves indexing** (`DictateOrchestrator.collectLeaves` + completeness check) | reflection via `KClass.sealedSubclasses` | **YES** — R8 default strips the sealed hierarchy; `sealedSubclasses == emptyList()` → empty map → silent-drop of all actions | **ProGuard keep rule in `app/proguard-rules.pro` is mandatory** (see §4.3 ProGuard block) |

**Compile-time guarantee:** `DictateModule` is a `sealed interface`, so the
compiler knows all implementers. The manual module list is debug-friendly +
R8-robust. The action-leaves map can NOT be manual (15+ leaf classes, every
new action would be a second plan location to update — DRY violation) — reflection
is the clean solution here, with the ProGuard keep rule as a non-negotiable
precondition.

### §4.9 Dependency interfaces (F-2 / DIP, unchanged)

<!-- FIX: Issue 2.1.19 / R.15 – sessionId durchgängig String (UUID) -->
<!-- FIX: Issue 2.1.10 / R.8 – PipelineRunner trägt sessionId; submit lehnt Idempotenz-Konflikt ab -->
```kotlin
interface PipelineSessionRepo {
    suspend fun loadPending(): List<PendingSession>
    suspend fun markInserted(sessionId: String, at: Long)
    /** Convenience-Wrapper über `SessionDao.updateStatus(FAILED) + updateError(UNKNOWN, reason)` — siehe §6.3 für die DAO-Direktcalls. */
    suspend fun markFailed(sessionId: String, reason: String)
    fun pendingFlow(): Flow<List<PendingSession>>
}

interface PipelineRunner {
    /** Submit schedules a new pipeline-job. Idempotent: gleiche `sessionId` → no-op. */
    fun submit(sessionId: String, audioFile: File)
    fun submitReprocess(sessionId: String, audioFile: File, queue: List<Int>, language: String?)
    fun cancel(sessionId: String)
    fun isRunning(sessionId: String): Boolean
    /** Anzahl aktiver Pipeline-Jobs (Multi-Job-Modell, R.8). */
    fun activeJobCount(): Int
}
```

Concretizations: `RoomPipelineSessionRepo` (in the SessionsDao call), `JobExecutor` (a static object adapts the interface).

### §4.10 Contract

All mutations run ONLY through `DictateOrchestrator.dispatch(action)`. The module system contains the logic (reducer + effect handler), the orchestrator routes, the store holds the truth. Direct mutations on view properties (`view.visibility = ...`) are forbidden. Subscribers read `store.state` via `StateFlow.collect`.

<!-- FIX: Issue PENDING-1 / Block-4 Resolved – AudioFileFactory konkret spezifiziert -->
### §4.11 AudioFileFactory (cache-file allocator, R.2)

> **Home section.** Spec 2 §10 (`resolveRecordAction`) and §15.2
> (RecordingModule) show the call — the canonical definition lives here.
> Spans Block 3 (composition wiring) + Block 4
> (RecordingHardwareSubsystem integration).

#### §4.11.1 Motivation

The reducer `RecordingModule.reduce(Idle, StartRecording)` is pure and
must NOT do any hardware/IO read. At the same time the next state
`Preparing(audioFile = …)` needs a concrete `File` handle. Solution: **the
file is created before the dispatch** and pushed into the reducer as an action
argument — the pure reducer sees only an already existing
`File` object (no disk access in the reducer body).

Today (pre-refactor, `DictateInputMethodService.java:1612`) the
allocation is a fixed constant:

```java
audioFile = new File(getCacheDir(), "audio.m4a");   // einziger Name, überschreibt sich
```

This is incompatible with the multi-job model (R.8) and the planned recovery path
(§11.6): two jobs (e.g. a running transcription + a
new recording) would point at the same file path. The factory solves
this with collision-free names per session.

#### §4.11.2 Interface

```kotlin
/**
 * Pre-Dispatch-Allocator für Audio-Cache-Files.
 *
 * Liefert ein frisches `File`-Handle (Pfad-Objekt, Datei wird vom
 * MediaRecorder beschrieben — nicht hier). Das File lebt anschließend
 * im RecordingState (R.2) und wandert per `Effect.AllocateMediaRecorder`
 * an die Hardware-Schicht.
 *
 * Lifecycle: pure factory, ohne State. Cleanup von Orphans erfolgt
 * separat über [cleanupOrphans] — getrennt, weil `allocate()` aus
 * dem Pre-Dispatch-Path (Main-Thread, Resolver) heraus gerufen wird
 * und KEINE listFiles()/delete()-Loops enthalten darf.
 */
interface AudioFileFactory {

    /**
     * Erzeugt einen frischen, kollisionsfreien Audio-File-Pfad im
     * Cache-Verzeichnis. **Touched die Datei NICHT** — der MediaRecorder
     * erzeugt sie beim `prepare()`/`start()`. Wenn das Verzeichnis nicht
     * existiert, wird `mkdirs()` aufgerufen.
     *
     * @return File mit absolutem Pfad und `.m4a`-Extension (MediaRecorder-
     *         Container ist MPEG_4 + AAC, siehe `RecordingManager.kt:61-62`).
     * @throws java.io.IOException wenn das Cache-Verzeichnis nicht angelegt
     *         werden kann (Storage voll, FS-Permission). Aufrufer (Resolver)
     *         müssen das fangen und in einen User-Toast übersetzen — der
     *         Reducer darf das `null` nicht beobachten.
     */
    fun allocate(): File

    /**
     * Best-Effort-Cleanup: löscht alle Dateien in `cacheDir/`, die dem
     * Factory-Namensschema entsprechen UND **nicht** in
     * `referencedPaths` referenziert werden.
     *
     * Wird vom Service einmal pro `onCreate` aufgerufen
     * (siehe §7.3 Schritt 5). Räumt Crash-Orphans auf, ohne aktive
     * Sessions zu beschädigen.
     *
     * @param referencedPaths absolute Pfade, die NICHT gelöscht werden
     *        dürfen. Quelle: `SessionDao.findAllAudioFilePaths()` — alle
     *        DB-Sessions mit `audio_file_path != null`.
     */
    fun cleanupOrphans(referencedPaths: Set<String>)
}
```

#### §4.11.3 Default implementation

```kotlin
/**
 * Cache-Dir-basierte Default-Implementation.
 *
 * Heimat: `context.cacheDir/audio/` — bewusst Unterverzeichnis statt
 * direkt im Cache-Root, damit `cleanupOrphans()` keine Settings-/
 * Export-/sonstige Cache-Files sieht und `PreferencesFragment.java:272`
 * (User-"Cache leeren") das ganze Unterverzeichnis weiterhin clearen
 * kann ohne Spezialwissen.
 */
class CacheDirAudioFileFactory(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : AudioFileFactory {

    private val audioCacheDir: File by lazy {
        File(context.cacheDir, AUDIO_SUBDIR).apply { mkdirs() }
    }

    override fun allocate(): File {
        if (!audioCacheDir.exists() && !audioCacheDir.mkdirs()) {
            throw IOException("Audio cache dir not creatable: $audioCacheDir")
        }
        // Name-Schema: rec_{timestamp-ms}_{uuid8}.m4a
        //  - Timestamp = Debug-Lesbarkeit (sortable; Log/`ls -la` zeigt Reihenfolge)
        //  - UUID-Suffix (8 hex) = Collision-Sicherheit innerhalb derselben
        //    Millisekunde (Multi-Job-Modell, R.8 — theoretisch parallel)
        //  - `.m4a` = MediaRecorder MPEG_4 + AAC (RecordingManager:61-62)
        val name = "rec_${clock()}_${UUID.randomUUID().toString().take(8)}.m4a"
        return File(audioCacheDir, name)
    }

    override fun cleanupOrphans(referencedPaths: Set<String>) {
        val files = audioCacheDir.listFiles { f ->
            f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(EXT)
        } ?: return
        files.forEach { f ->
            if (f.absolutePath !in referencedPaths) {
                runCatching { f.delete() }
                    .onFailure { Log.w(TAG, "orphan cleanup failed: ${f.name}", it) }
            }
        }
    }

    companion object {
        private const val TAG = "AudioFileFactory"
        private const val AUDIO_SUBDIR = "audio"
        private const val PREFIX = "rec_"
        private const val EXT = ".m4a"
    }
}
```

#### §4.11.4 Pre-Dispatch usage

The caller (Spec 2 §10 — `resolveRecordAction`) builds the `StartRecording`
action with the allocated file:

```kotlin
// Resolver-Helper, lebt im View-Binding-Layer (Spec 2 §10):
fun resolveRecordAction(
    state: DictateUiState,
    services: ModuleServices,    // injiziert vom Binding-Setup
): Action? = when (state.recording) {
    is RecordingState.Idle -> Action.RecordingAction.StartRecording(
        target = InsertionTarget.MainInputConnection,
        audioFile = services.audioFileFactory.allocate(),    // R.2
    )
    is RecordingState.Active    -> Action.RecordingAction.StopRecordingAndSend
    is RecordingState.Paused    -> Action.RecordingAction.StopRecordingAndSend
    is RecordingState.Preparing -> null
}
```

`allocate()` runs **before** `dispatch()`. An `IOException` from the
mkdirs must be caught by the resolver (view layer) and translated into a
`ToastSink` message — before `dispatch` is called.
The reducer never sees the failure.

#### §4.11.5 Lifecycle obligations

| Hook | Caller | Call | Purpose |
|---|---|---|---|
| `allocate()` | Pre-Dispatch resolver (Spec 2 §10) | per `StartRecording` | fresh file path |
| `cleanupOrphans()` | `DictatePipelineService.onCreate` (§7.3) | once per service boot | remove crash orphans |
| Delete file (legitimate) | `Effect.DeleteAudioFile` handler (§15.2) | on `CancelRecording` | active cancellation |
| Delete file (persisted) | `RecordingRepository.deleteBySessionId` (unchanged) | history cleanup | DB-driven deletion |

**`cleanupOrphans()` call site** (§7.3, to be newly added):

```kotlin
override fun onCreate() {
    super.onCreate()
    // ... existing wiring ...
    val audioFileFactory: AudioFileFactory = CacheDirAudioFileFactory(this)

    // Crash-Orphan-Cleanup, läuft asynchron damit FGS-5s-Frist (§11.1.4)
    // nicht verbraucht wird. Liest DB → Set<String> → löscht alles
    // andere im audio/-Cache-Subdir.
    serviceScope.launch(Dispatchers.IO) {
        val referenced = database.sessionDao()
            .findAllAudioFilePaths()           // neuer DAO-Query (s.u.)
            .filterNotNull()
            .toSet()
        audioFileFactory.cleanupOrphans(referenced)
    }
    // ...
}
```

Required DAO addition (Block 3 schema block):

```kotlin
@Query("SELECT audio_file_path FROM sessions WHERE audio_file_path IS NOT NULL")
fun findAllAudioFilePaths(): List<String?>
```

##### §4.11.5.1 Service-onCreate sequence — canonical order

The cleanup is part of the broader service-onCreate sequence (see §7.3
for the full skeleton). So that the implementer does not have to guess
which step runs before which, here is the ordered sequence:

<!-- FIX: Phase-B S-1 (2026-05-13) – Sequence-Tabelle auf DictateOrchestrator + ModuleServicesFactory umgestellt (F-11 Modular-Orchestrator-Pattern; PipelineStateManager existiert nicht mehr) -->
<!-- FIX: Phase-B S-5 (2026-05-13) – Schritt 1.5 (ensureNotificationChannel) + Schritt 9 (startForeground) explizit in der Sequenz verankert. Ohne diese beiden Schritte wurde aus der "5-Sekunden-Timeout-Klausel" (§11.1.4) ein implizites Detail; jetzt ist die Sequenz vollständig (Channel → DI → FGS-Notification → async-Recovery + async-Cleanup). Plus: Schritt 10 (JobExecutor.initialize) explizit als letzter sync-Schritt — G7 §13.5.a verankert, dass der alte PipelineOrchestrator (nicht der neue DictateOrchestrator) übergeben wird (Naming-Konflikt-Falle, siehe §1.x). -->
| # | Step | Sync/Async | Note |
|---|---|---|---|
| 1 | `super.onCreate()` | sync | service base setup |
| 1.5 | `ensureNotificationChannel()` | sync | **MUST run before step 9** (startForeground). Channel creation is in-memory + a getSystemService call, < 5 ms. API < 26 no-op. |
| 2 | DI wiring (Store, Repos, Runner, PrefMirror, Recovery) | sync | §7.3 Composition Root |
| 3 | `audioFileFactory = CacheDirAudioFileFactory(applicationContext)` | sync | factory construct |
| 4 | `servicesFactory = ModuleServicesFactory { ModuleServices(audioFileFactory = audioFileFactory, …) }` | sync | DI container (see §4.7) |
| 5 | `orchestrator = DictateOrchestrator(scope, store, servicesFactory, prefMirror, recovery)` | sync | Composition Root (§4.3) — constructor `init` calls `prefMirror.attach(store)` **BEFORE** `scope.launch { recovery.recover(store) }` (see §4.3) |
| 6 | `notifCoordinator = …`, `actionRouter = …` | sync | §7.4 / §7.5 |
| 6.5 | `LegacyAudioFileMigration.run(applicationContext)` | sync | one-shot idempotent (KG-AFF-2). Runs BEFORE step 7/8 |
| 7 | (`recovery.recover(store)` already runs async via `Orchestrator.init`, step 5) | async | §11.6.1 |
| 8 | `serviceScope.launch(Dispatchers.IO) { audioFileFactory.cleanupOrphans(referenced) }` | async | see above |
| 9 | `startForeground(PipelineNotificationCoordinator.NOTIF_ID, notifCoordinator.buildInitial())` — called from `onStartCommand`, NOT from `onCreate` <!-- FIX: Phase-C C-2 (2026-05-14) – NOTIF_ID-Qualifier (SoT-Konsolidierung). --> | sync | **MUST be before 5 s after the `startForegroundService` call** (§11.1.4). `buildInitial()` is a pure State→Notification render, in-memory, < 5 ms. Step 9 lives in the `onStartCommand` path, because Android does not run `onCreate` without `onStartCommand` via `startForegroundService`. |
| 10 | `JobExecutor.initialize(pipelineOrchestrator)` | sync | G7 §13.5.a. ⚠ Expects the **old** `PipelineOrchestrator` (audio pipeline runner, Spec 1 §1.x naming convention), NOT the new `DictateOrchestrator`. Position after the recovery-async-start is OK (JobExecutor is only called by user action / pendingSessions resume, i.e. after recovery completion). |

**Order invariants:**

- Step 3 runs **before** step 4, because `services` holds the factory.
- Step 4 runs **before** step 5, because the `DictateOrchestrator` constructor expects `servicesFactory` as a parameter.
<!-- FIX: Phase-C C-1 (2026-05-14) – stale Z. 567-570 → Section-Anchor (Line-Drift). -->
- **Step 5 guarantees `prefMirror.attach(store)` before `recovery.recover(store)`** (encoded in the `Orchestrator.init` block, §4.3 `DictateOrchestrator` constructor). Violating this order would be subtle — recovery would see initially empty pref-mirror axes (e.g. `state.overlay.position*` as default values instead of persisted values). The order is **part of the orchestrator-constructor contract**, not externally delegated to the service-onCreate sequence — step 5 is atomic.
- Step 7 (started in the orchestrator `init`) and step 8 (explicitly launched in the service) run **in parallel** in the `serviceScope` — both are reads, neither blocks the other. Step 8 reads the DB directly via `findAllAudioFilePaths()`; synchronizing with step 7 would be more expensive than the double read and is not necessary.
- **Initial-state race (NEW, Phase-B S-1 / F-11):** Subscribers attached to `store.state` BEFORE step 5 (`Orchestrator(…)`) see the `DictateUiState.initial()` default — no pref mirror, no pendingSessions. The IME-service `bindService` path (§7.2) guarantees that the `LocalBinder` is only returned AFTER `onCreate`; thus `store.state` is at the time of the first IME `collect` call **at least** filled with pref-mirror values (step 5 has performed `prefMirror.attach` synchronously). Recovery values (`pendingSessions`) may follow up later — subscribers must be idempotent against late `pendingSessions` updates.

**`onDestroy` interaction:**

<!-- FIX: Phase-C C-2 (2026-05-14) – stale `stateManager` (Pre-F-11-Drift) → `orchestrator`.
     §4.3 `shutdown()` ist die kanonische Cleanup-API; `stateManager` existiert seit
     Phase-B S-1 nicht mehr. Der Voll-Snippet für `onDestroy` (mit `runBlocking`-Timeout-
     Wrapper, §11.1.4-Grenze) lebt in §7.3 — hier nur die §4.11-Skelett-Variante, die
     zeigt, dass der Cleanup-Job vom `serviceScope.cancel()` automatisch abgebrochen wird. -->
```kotlin
override fun onDestroy() {
    super.onDestroy()
    orchestrator.shutdown()    // detacht PrefMirror + ruft jedes Modul-`terminate(services)` (§4.3)
    serviceScope.cancel()      // ← cancelt laufenden cleanupOrphans-Job
}
```

If the cleanup job is aborted in the middle of `listFiles()` or a `delete()`,
that is **safe**: every individual `delete()` is atomic at the
FS level, and non-deleted orphans are retried at the next
service boot (idempotent).

##### §4.11.5.2 Concurrency contract (threading model)

| Method | Thread | Rationale |
|---|---|---|
| `allocate()` | **Main thread** (resolver, view layer) | only `mkdirs()` + UUID — an O(1) FS operation, acceptable on main |
| `cleanupOrphans()` | **Dispatchers.IO** (service scope, once in `onCreate`) | listFiles + delete loop — never on main |
| `companion` reads | irrelevant | pure `const val` reads, lock-free |

**Re-entry safety:** There is **no shared mutable state**
between `allocate()` and `cleanupOrphans()`. Both work against
the same `audioCacheDir` lazy reference (immutable after the first read),
but:

- `allocate()` only produces `File` path objects (no disk read of
  existing files).
- `cleanupOrphans()` does a **snapshot-based** `listFiles()` —
  files created **after** the snapshot are outside
  the cleanup.

The race risk is limited to the window between `listFiles()` and the per-file
`delete()` — covered under edge case #5 (§4.11.7).

##### §4.11.5.3 Service wiring — full code diff (future Pipeline Service)

Anchor for the implementer. The future `DictatePipelineService`
(§7) is written in Kotlin (`.kt`); today's
`DictateInputMethodService.java` remains the IME service that connects the
Pipeline Service via `bindService` (§7.2). The wiring of the
factory lands in the **Pipeline Service**, not in the IME service:

```kotlin
// app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt
class DictatePipelineService : Service() {

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

<!-- FIX: Phase-B S-1 (2026-05-13) – Service-Wiring auf DictateOrchestrator + Modul-Pattern umgestellt (F-11). Pre-F-11-Code-Snippet zeigte PipelineStateManager als monolithischen State-Owner — existiert nicht mehr. -->
    // === NEW: Audio-File-Allocator (§4.11) ===
    private lateinit var audioFileFactory: AudioFileFactory
    // === END NEW ===

    private lateinit var orchestrator: DictateOrchestrator
    // ... (weitere Felder, siehe §7.3)

    override fun onCreate() {
        super.onCreate()

        // 1. Foundation + DI (siehe §7.3)
        val database = DictateDatabase.getInstance(this)
        val store = DictateUiStateStore(DictateUiState.initial())
        val sessionRepo: PipelineSessionRepo = RoomPipelineSessionRepo(database.sessionsDao())
        val runner: PipelineRunner = JobExecutor
        val prefMirror = PipelinePrefMirror(getSharedPreferences(...))
        val recovery = PipelineRecovery(sessionRepo)
        // ...

        // 2. NEW: AudioFileFactory (§4.11)
        audioFileFactory = CacheDirAudioFileFactory(applicationContext)

        // 3. ModuleServicesFactory mit injizierter AudioFileFactory (§4.7).
        //    Factory-Pattern statt direkter ModuleServices-Konstruktion, weil
        //    der DictateOrchestrator services lazy per `servicesFactory.get()`
        //    in `runEffect`-Aufrufen abruft.
        val servicesFactory = ModuleServicesFactory {
            ModuleServices(
                // ... andere Subsystem-Adapter
                audioFileFactory = audioFileFactory,
                scope = serviceScope,
                emitAction = { action -> orchestrator.emitAction(action) },
                // ...
            )
        }

        // 4. Composition Root: DictateOrchestrator (§4.3). Konstruktor-`init`
        //    sorgt für `prefMirror.attach(store)` VOR `recovery.recover(store)`
        //    (atomar im Orchestrator, nicht extern delegiert).
        orchestrator = DictateOrchestrator(
            scope = serviceScope,
            store = store,
            servicesFactory = servicesFactory,
            prefMirror = prefMirror,
            recovery = recovery,
            // modules = DictateModuleRegistry.all (Default)
        )

        // 5. NEW: Crash-Orphan-Cleanup async (Schritt 8 der Sequenz oben)
        serviceScope.launch(Dispatchers.IO) {
            try {
                val referenced = database.sessionDao()
                    .findAllAudioFilePaths()
                    .filterNotNull()
                    .toSet()
                audioFileFactory.cleanupOrphans(referenced)
            } catch (t: Throwable) {
                // Cleanup ist best-effort; ein DB-Fehler darf den Service-
                // Boot nicht killen. Log + weiter.
                Log.w("DictatePipelineSvc", "orphan cleanup failed at boot", t)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        orchestrator.shutdown()    // detacht PrefMirror, siehe §4.3 shutdown()
        serviceScope.cancel()      // cancelt auch den Cleanup-Job, falls noch laufend
    }
}
```

**Lifecycle guarantee:** The factory is a `lateinit var` and lives from
`onCreate` to `onDestroy` of the Pipeline Service. It holds itself
**no state** (except the `audioCacheDir` lazy) and needs no
own `dispose()`. `serviceScope.cancel()` cancels the asynchronous
cleanup job. `allocate()` calls from the view layer can no longer arrive after
`onDestroy`, because the LocalBinder interface
is then unbound (see §5).

##### §4.11.5.4 Pre-refactor pattern (today → future)

Today the `audioFile` field is held directly in the `DictateInputMethodService`
(`DictateInputMethodService.java:208,1612`):

```java
// Vor dem Refactor (DictateInputMethodService.java:1609-1619):
private void startRecording() {
    promptQueueManager.prepareAutoApplyQueue();
    audioFile = new File(getCacheDir(), "audio.m4a");                  // ← legacy fix path
    DictatePrefsKt.put(sp.edit(), Pref.LastFileName.INSTANCE, audioFile.getName()).apply();
    boolean useBt = DictatePrefsKt.get(sp, Pref.UseBluetoothMic.INSTANCE);
    recordingStateController.startRecording(
            audioFile, useBt, DictatePrefsKt.get(sp, Pref.AudioFocus.INSTANCE));
}
```

After the refactor the `audioFile` allocation lives in the resolver
(`resolveRecordAction`, Spec 2 §10) and is pushed as an action argument
through the reducer. The service field `audioFile` in
`DictateInputMethodService.java` is removed — the path flows through
`StartRecording.audioFile → RecordingState.Preparing(audioFile) →
Effect.AllocateMediaRecorder(audioFile) → RecordingManager.start(file)`.

Concrete migration steps:

1. **Remove:** field `private File audioFile;` in
   `DictateInputMethodService.java:208`.
2. **Remove:** line `audioFile = new File(getCacheDir(), "audio.m4a");`
   (l. 1612) — moves into `CacheDirAudioFileFactory.allocate()`.
3. **Adjust:** all read sites (1407, 936, 1706) — the new owner is
   `RecordingState.Preparing.audioFile` or the
   `Effect.AllocateMediaRecorder` argument.
4. **Keep:** the `Pref.LastFileName.INSTANCE` pref entry stays
   (display convenience, not a state driver); updated by the resolver,
   **not** by the factory (SRP — the factory only serves FS allocation).

#### §4.11.6 Recovery coupling (§11.6.2 interaction)

The factory chooses **`context.cacheDir`** (not `filesDir`). This means:

<!-- FIX: Phase-B S-7 (2026-05-13) – Recovery-Coupling-Tabelle um v4-Status RECORDING und
     TRANSCRIBING erweitert (S-2-DB-Schema-Migration bringt diese neuen Stati). Vorher nur RECORDED
     dokumentiert — Lücke gegen Spec-1-§11.6.2 + Acceptance R.16a/b/c. -->
| Scenario | Status (v4) | Behavior | Recovery behavior |
|---|---|---|---|
| App crash + restart, cache survives | RECORDED | file exists, session row has path | `recoverFromDb` loads it as a RECORDED session normally into `pendingSessions` (§11.6.2 l. 2183) |
| OS deletes `cacheDir` (storage pressure) | RECORDED | file gone, session row has path | `recoverFromDb` filters into ghost sessions → `lastErrorType=UNKNOWN, lastErrorMessage="audio file vanished"`, status FAILED (§11.6.2 l. 2192-2197) |
| User triggers "Clear cache" in Preferences | RECORDED | file gone, session row has path | identical to the OS-wipe path — ghost session |
| Crash mid-recording (process kill) | RECORDING | DB row typically has `audio_file_path = NULL` (path is only written on stop, see §6.3 RECORDING recovery block) | recovery promoted → FAILED + `lastErrorMessage="recording-interrupted-by-process-death"`; the partial-written file in `cacheDir/audio/` is picked up by the `cleanupOrphans` boot hook (NOT by the recovery path). Consistent with S-2 F-2 + Acceptance R.16a |
| Crash mid-transcription (process kill), file survives | TRANSCRIBING | file exists, session row has path | recovery downgraded → RECORDED + stale-error-clear; the session lands in `pendingSessions` (no auto-resume, D4/OPEN-4). Acceptance R.16b |
| Crash mid-transcription, file gone via OS wipe | TRANSCRIBING | file gone, session row has path | recovery promoted → FAILED + `lastErrorMessage="audio file vanished before transcription"`. Acceptance R.16c |

**Accepted trade-off:** `cacheDir` is the correct home location for
**transient** recording files (pre-persistence). Once the pipeline is
through, `RecordingRepository.persistFromCache` (already existing today,
`RecordingRepository.kt:45`) copies into `filesDir/recordings/` —
**that** is the persistent home location. The factory only serves the
cache phase. An OS wipe between `MediaRecorder.stop()` and
`persistFromCache` is possible, but very unlikely (a seconds-long
window); §11.6.2 covers it as a ghost session.

##### §4.11.6.1 `persistFromCache` trigger — where does the effect live?

The persist operation already exists today (`RecordingRepository.kt:45`,
"persistence bridge for Recordings"). An important question for the new
architecture build-up: does the call stay **inside the pipeline stage**
or does it become an explicit `Effect.PersistAudioFile`?

**Decision (made in the plan):** Stays **inside the
PipelineRunner job**. Concretely:

- `PipelineOrchestrator.persistNewSession()` (`PipelineOrchestrator.kt:837`)
  calls `repo.persistFromCache(audioFile, sessionId)` in l. 855. That
  is the PERSIST stage of the pipeline job (between RECORDED-session
  create and the PROCESS stage).
- The pipeline runs asynchronously on the JobExecutor (background thread,
  see `JobExecutor.kt`). The RecordingModule reducer triggers it
  via `Effect.SubmitPipelineJob(sessionId, audioFile)` →
  `PipelineRunner.submit(sessionId, audioFile)` (definition see
  Spec 1 §4.9 / §15.2).
- **Consequence:** From the perspective of `RecordingModule.reduce`/`runEffect`,
  persist is a black box inside the pipeline. The module
  dispatches `Effect.SubmitPipelineJob` and "forgets" the cache file
  deliberately — the next relevant lifecycle point is
  `Action.PipelineAction.PersistCompleted(sessionId)`, which is emitted by the
  pipeline as soon as the file is in `filesDir/recordings/`.

**Who deletes the cache file after persist?**

Today: **nobody** explicitly. The cache file survives until the next
`cleanupOrphans` run at service boot. Rationale: `persistFromCache`
uses `copyTo(overwrite = true)` (l. 47), not `move`/`rename` — the
original stays in the cache. After `PersistCompleted` the session row
in the DB points at the **persisted** path (`filesDir/recordings/{sid}.m4a`),
so the cache-file entry is no longer in `referencedPaths` —
at the next boot `cleanupOrphans` cleans it up.

This is accepted (no immediate cleanup coupling needed), see
edge case #7 below.

<!-- KNOWLEDGE-GAP: KG-AFF-1 – Sofort-Delete des Cache-Files nach Persist [RESOLVED 2026-05-11] -->
> **✅ Knowledge gap (KG-AFF-1): Immediate delete of the cache file after a successful persist — RESOLVED 2026-05-11**
>
> - **What we knew:** `persistFromCache` (`RecordingRepository.kt:45`) does
>   `copyTo(overwrite = true)`, not `move` — the cache file survives. Today
>   it stays around until the next `onCreate.cleanupOrphans` run.
> - **What we did not know:** Whether `Effect.PersistAudioFile` deletes the cache file
>   immediately after a successful copy (inline cleanup) — or whether the
>   status quo (boot cleanup via `cleanupOrphans`) is sufficient.
> - **Resolution:** **Immediate delete after a successful copy chosen.**
>   Rationale: "Explicit cleanup wins" — unambiguous ownership transfer from
>   the cache to persist storage, less storage pressure between service boots,
>   and no semantic ambiguity (DB path points at filesDir, but the file
>   still lies in cacheDir — an implementer trap on read recovery). The crash
>   window between copy and delete is accepted: the remaining
>   cache file is captured by `cleanupOrphans` at the next boot
>   (idempotent, since the DB path already points at filesDir).
>
>   **Concrete code patch** in `PipelineOrchestrator.persistNewSession`
>   (`PipelineOrchestrator.kt:837ff`), Block 4:
>
>   ```kotlin
>   // Replace lines ~854-857:
>   if (repo != null) {
>       val recording = repo.persistFromCache(audioFile, sessionId)
>       audioDurationSec = repo.extractDurationSeconds(recording.audioFile)
>       audioPathForRow = recording.audioFile.absolutePath
>
>       // KG-AFF-1: Best-effort cleanup of cache file after successful persist.
>       // Idempotent — if delete fails, cleanupOrphans catches it at next boot.
>       runCatching { audioFile.delete() }
>           .onFailure { Log.w(TAG, "cache delete after persist failed: ${audioFile.name}", it) }
>   }
>   ```
>
>   The order is **`copyTo` → DB row write → cache `delete()`** (the DB write
>   is ground truth; if delete fails before it, the DB path hangs on a
>   file that does not exist). On a `delete()` failure (e.g. FS race):
>   log-warn, **no throw** — the pipeline job stays successful, because the
>   persisted file in `filesDir` is correctly present.
> - **Incorporation:** Code snippet above + patch anchor in
>   `PipelineOrchestrator.kt:854-857`. §4.11.7 edge case #7 (cache file
>   survives after persist) is overridden by this resolution — edge
>   case #7 is **weakened** after Block 4: it only applies in the crash window
>   between copy and delete (sub-millisecond range), which is reliably cleaned up
>   by `cleanupOrphans`.

##### §4.11.6.2 App-update migration: old `cacheDir/audio.m4a` paths

**Problem:** Before the refactor the cache file lies under the
**fixed path** `cacheDir/audio.m4a` (`DictateInputMethodService.java:1612`)
— all previous sessions that have an `audio_file_path` in the DB
reference exactly this one path. With the refactor the
file schema moves to `cacheDir/audio/rec_{ts}_{uuid8}.m4a`.

**Observations:**

- Android does **not** delete `cacheDir` on app updates — the old
  `cacheDir/audio.m4a` survives the update.
- Multiple old sessions in the DB can have the same `audio_file_path =
  ".../cacheDir/audio.m4a"` (that is *always* the case today,
  because the path is not UUID-suffixed — every new recording
  overwrites the old one).
- The old file does **not** lie in the subdirectory `audio/`, but
  directly in `cacheDir`. Thus it does **not** fall into the scope of the new
  `cleanupOrphans` path filter (`audioCacheDir = cacheDir/audio/`).

**Consequence for the new factory:**

| Aspect | Behavior | Rationale |
|---|---|---|
| Old `cacheDir/audio.m4a` after update | **stays around**, `cleanupOrphans` does not see it | the subdirectory scope inadvertently protects it |
| DB sessions with `audio_file_path = ".../cacheDir/audio.m4a"` | the recovery path (§11.6.2) does a `File(path).exists()` check | If the file still exists: loaded as a RECORDED session. If not: ghost session, FAILED |
| Already **persisted** old sessions (`filesDir/recordings/{sid}.m4a`) | unchanged | filesDir paths are not in the cache scope |

**Accepted behavior:**

1. The old `cacheDir/audio.m4a` stays around after the app update as a "stranded
   file". It is deleted at the next user "Clear cache" via
   PreferencesFragment (l. 285) — because this loop
   iterates over `cacheDir.listFiles()` without a sub-filter and does
   `file.delete()` directly. (**Note:** see §4.11.6.3 — this
   call path deletes **only files**, not subdirectories;
   `cacheDir/audio.m4a` is a file → gets deleted. The subdir
   `cacheDir/audio/` is a directory → is **not** deleted.)
2. Old sessions with `audio_file_path = ".../audio.m4a"` are marked by the
   recovery path (§11.6.2) as a ghost session → FAILED,
   as soon as the file is gone (user cache wipe or OS storage pressure).
   This is consistent with today's behavior — already *before* the
   refactor these sessions were not resubmittable (every new
   recording overwrote the file).
3. **No active migration logic needed** — the recovery path and
   `cleanupOrphans` are both robust against the legacy schema.

<!-- KNOWLEDGE-GAP: KG-AFF-2 – Alte audio.m4a stranded nach Update [RESOLVED 2026-05-11] -->
> **✅ Knowledge gap (KG-AFF-2): Migration of the old `cacheDir/audio.m4a` on app update — RESOLVED 2026-05-11**
>
> - **What we knew:** Today the cache file lies under `cacheDir/audio.m4a`
>   (`DictateInputMethodService.java:1612`). After an app update it stays around,
>   but outside the new sub-dir scope of `cleanupOrphans`. Old
>   DB sessions reference this path. All DB sessions before the refactor
>   share the same one `audio_file_path = ".../cacheDir/audio.m4a"`
>   (the path was not UUID-suffixed — every new recording overwrote the old one).
> - **What we did not know:** Whether a **one-shot migration step** at the
>   first service boot after the update is wanted, that explicitly deletes
>   `cacheDir/audio.m4a` AND marks the referencing DB sessions as FAILED
>   (they were not resubmittable anyway).
> - **Resolution:** **One-time legacy cleanup at the first service start
>   after the update**, driven via a boot pref flag `legacy_audio_purged_v4`.
>   Rationale: without an explicit cleanup, a ~5 MB stale file potentially
>   stays in the cache for months, and the referencing sessions
>   lie in the history as confusing pseudo-active entries (the recovery
>   path only marks them FAILED when the file disappears — so
>   theoretically never). Explicitly setting FAILED with a clear error message
>   `"audio_file_path_legacy_purged"` is the clean solution.
>
>   **Concrete implementation** — new class
>   `app/src/main/java/net/devemperor/dictate/migration/LegacyAudioFileMigration.kt`:
>
>   ```kotlin
>   package net.devemperor.dictate.migration
>
>   import android.content.Context
>   import android.util.Log
>   import androidx.preference.PreferenceManager
>   import net.devemperor.dictate.database.DictateDatabase
>   import net.devemperor.dictate.database.entity.SessionStatus
>   import java.io.File
>
>   /**
>    * One-shot migration on first boot after the AudioFileFactory refactor
>    * (Block 4). Deletes the legacy `cacheDir/audio.m4a` and marks all DB
>    * sessions that still reference it as FAILED with a specific reason —
>    * those sessions were never re-submittable (the path was shared across
>    * all recordings; each new recording overwrote the previous file).
>    *
>    * Idempotent via the `legacy_audio_purged_v4` SharedPreferences flag.
>    */
>   object LegacyAudioFileMigration {
>       private const val TAG = "LegacyAudioMigration"
>       private const val FLAG_PREF = "legacy_audio_purged_v4"
>       private const val LEGACY_NAME = "audio.m4a"
>       private const val REASON = "audio_file_path_legacy_purged"
>
>       fun run(context: Context) {
>           val prefs = PreferenceManager.getDefaultSharedPreferences(context)
>           if (prefs.getBoolean(FLAG_PREF, false)) return
>
>           val legacy = File(context.cacheDir, LEGACY_NAME)
>           if (legacy.exists()) {
>               runCatching { legacy.delete() }
>                   .onFailure { Log.w(TAG, "delete of legacy $legacy failed", it) }
>           }
>
>           val dao = DictateDatabase.getInstance(context).sessionDao()
>           val legacyPath = legacy.absolutePath
>           runCatching {
>               dao.markLegacyAudioSessionsFailed(legacyPath, REASON, SessionStatus.FAILED)
>           }.onFailure { Log.w(TAG, "legacy-session FAILED-mark failed", it) }
>
>           prefs.edit().putBoolean(FLAG_PREF, true).apply()
>       }
>   }
>   ```
>
>   <!-- FIX: Phase-B S-7 (2026-05-13) – DAO-Query idempotent gegen Re-Marking + Daten-Erhalt. -->
>   **Idempotency clause at the DAO level (Phase-B S-7):** The `markLegacyAudioSessionsFailed`
>   update unconditionally sets `status = FAILED` + `last_error_message`. Without a `WHERE` filter
>   already-FAILED sessions would lose their **original** `last_error_message` (e.g.
>   "transcription_timeout", "openai_rate_limit") — the historical error information
>   would be lost. Furthermore: if a `COMPLETED` session happens to have the same legacy
>   path (extremely unlikely, but theoretically conceivable for a user who manually manipulates
>   DB rows via adb), it would be **downgraded** to FAILED + data would be
>   lost. **Fix:** filter the query with `AND status NOT IN ('FAILED', 'CANCELLED', 'COMPLETED')`.
>   Only sessions in `RECORDING`/`RECORDED`/`TRANSCRIBING` (which after Phase 1
>   v4 schema are the only meaningful "incomplete pre-refactor" stati) are
>   marked.
>
>   Required DAO addition (Block 3 schema block, next to `findAllAudioFilePaths`):
>
>   ```kotlin
>   @Query("""
>       UPDATE sessions
>       SET status = :failedStatus,
>           last_error_type = 'UNKNOWN',
>           last_error_message = :reason
>       WHERE audio_file_path = :legacyPath
>         AND status NOT IN ('FAILED', 'CANCELLED', 'COMPLETED')
>   """)
>   fun markLegacyAudioSessionsFailed(
>       legacyPath: String,
>       reason: String,
>       failedStatus: SessionStatus,
>   ): Int
>   ```
>
>   **Idempotency on the second run (e.g. after a pref wipe):** the pref-flag check above is
>   the primary idempotency. If the flag is lost (app-data wipe, user downgrade-
>   upgrade cycle), the migration runs again — the `WHERE status NOT IN (...)` clause
>   ensures that already-migrated FAILED rows are not overwritten again.
>   Sessions created between the two migration runs cannot reference the
>   legacy path (`AudioFileFactory.allocate` writes UUID paths
>   into `cacheDir/audio/`), so no false positive.
>
>   **Call site:** `DictatePipelineService.onCreate`, before step 7/8
>   (recovery + orphan cleanup), sync — the migration is O(1)
>   (existence check + 1 DB update + 1 pref write):
>
>   ```kotlin
>   // After Step 6 (notifCoordinator/actionRouter wiring), before Step 7:
>   LegacyAudioFileMigration.run(applicationContext)  // one-shot, idempotent
>   ```
>
>   <!-- FIX: Phase-B S-7 (2026-05-13) – sync-vs-async + FGS-5s-Frist explizit dokumentiert. -->
>   **Threading + FGS 5 s deadline (Phase-B S-7):** The `run()` call runs **synchronously** on the
>   main thread (Service.onCreate is the main thread). Three operations:
>
>   1. `PreferenceManager.getDefaultSharedPreferences(context)` read — disk-blocking, but
>      typically <5 ms (SharedPreferences are already read at boot).
>   2. `File(cacheDir, "audio.m4a")` existence check + `delete()` — disk-blocking, <10 ms.
>   3. `dao.markLegacyAudioSessionsFailed(...)` — SQL UPDATE on an indexed column (`audio_file_path`),
>      typically <20 ms with <1k sessions; with >10k sessions possibly 100 ms.
>
>   Total: typically <50 ms, worst-case ~200 ms. The 5-second FGS deadline (§11.1.4) is
>   not nibbled at. **Nonetheless:** if telemetry shows that the DAO update on certain
>   devices takes >500 ms, the migration call can be wrapped in a `serviceScope.launch(Dispatchers.IO)`
>   block — idempotency (pref flag + DAO WHERE filter) makes the run-too-late case
>   safe (a parallel `LegacyAudioFileMigration` run in the next service boot would be a no-op).
>   Phase 1: sync, Phase 2 evaluate if telemetry shows a problem.
> - **Incorporation:** Migration class above (§4.11.6.2) + new DAO query
>   (Block 3 schema, Spec 1 §6.x) + call site in §4.11.5.1 step 6.5
>   (between 6 and 7). The accepted-behavior list (3 points above)
>   stays documented as the historical path: point 3 ("No active
>   migration logic needed") no longer applies — see resolution above.

##### §4.11.6.3 PreferencesFragment "Clear cache" — precise mechanics

One of the arguments in §4.11.8 was:

> "Clear cache" in the preferences automatically clears the subdir too
> (recursive `listFiles().delete()`).

**Correction:** Today's code (`PreferencesFragment.java:272-289`)
does **not** iterate recursively:

```java
File[] cacheFiles = requireContext().getCacheDir().listFiles();        // Z. 272 — nur Top-Level-Entries
// ...
for (File file : cacheFiles) {
    file.delete();                                                      // Z. 285-287 — File.delete() auf Dir = no-op falls non-empty
}
```

**Java semantics:** `File.delete()` on a **non-empty
directory** returns `false` and does nothing. This means:

- Old `cacheDir/audio.m4a` (file, top-level): gets deleted.
- New `cacheDir/audio/rec_*.m4a` (subdirectory not empty): is
  **NOT** deleted via "Clear cache".

**Implication:** The statement in §4.11.8 ("Clear cache clears the subdir
too") is **false**. If that is wanted, the "Clear cache"
logic in the `PreferencesFragment` must be extended to **recursive delete**
(an additional point for the implementer).

<!-- KNOWLEDGE-GAP: KG-AFF-3 – PreferencesFragment rekursiv? [RESOLVED 2026-05-11] -->
> **✅ Knowledge gap (KG-AFF-3): Should "Clear cache" recurse through sub-dirs? — RESOLVED 2026-05-11**
>
> - **What we knew:** Today's code (`PreferencesFragment.java:272-289`)
>   iterates only top-level and calls `File.delete()` — does
>   not work on non-empty directories. With the new `cacheDir/audio/`
>   subdir, "Clear cache" would not clear the audio subdir.
> - **What we did not know:** Whether the implementer should adjust the code
>   so that "Clear cache" also cleans up the new `audio/` subdir.
> - **Resolution:** **Code extension with `deleteRecursively()`.** Pro:
>   consistent UX (the user expects everything gone); the current cache display
>   `cacheFiles.length` and `cacheFiles.length × File::length` would
>   otherwise permanently ignore the audio subdir (top-level listFiles counts
>   a directory as 1 entry, but without the contained files).
>
>   **Concrete Java diff** in `PreferencesFragment.java:272-289`:
>
>   ```java
>   // Replace block at 272-289:
>   Preference cachePreference = findPreference("net.devemperor.dictate.cache");
>   File cacheDir = requireContext().getCacheDir();
>   if (cachePreference != null) {
>       long cacheSize = computeCacheSizeRecursively(cacheDir);
>       int fileCount = countCacheFilesRecursively(cacheDir);
>       cachePreference.setTitle(getString(R.string.dictate_settings_cache,
>           fileCount, cacheSize / 1024f / 1024f));
>
>       cachePreference.setOnPreferenceClickListener(preference -> {
>           new MaterialAlertDialogBuilder(requireContext())
>               .setTitle(R.string.dictate_cache_clear_title)
>               .setMessage(R.string.dictate_cache_clear_message)
>               .setPositiveButton(R.string.dictate_yes, (dialog, which) -> {
>                   clearCacheRecursively(cacheDir);
>                   cachePreference.setTitle(getString(
>                       R.string.dictate_settings_cache, 0, 0f));
>                   Toast.makeText(requireContext(),
>                       R.string.dictate_cache_cleared, Toast.LENGTH_SHORT).show();
>               })
>               .setNegativeButton(R.string.dictate_no, null)
>               .show();
>           return true;
>       });
>   }
>
>   // === Helper methods at class level ===
>
>   /**
>    * KG-AFF-3: Recursively deletes all files under cacheDir (incl. audio/).
>    * Keeps the cacheDir itself (Android contract — cacheDir is managed by
>    * the framework).
>    */
>   private static void clearCacheRecursively(@NonNull File dir) {
>       File[] entries = dir.listFiles();
>       if (entries == null) return;
>       for (File entry : entries) {
>           if (entry.isDirectory()) {
>               clearCacheRecursively(entry);
>               entry.delete();  // empty dir → delete()
>           } else {
>               entry.delete();
>           }
>       }
>   }
>
>   private static long computeCacheSizeRecursively(@NonNull File dir) {
>       File[] entries = dir.listFiles();
>       if (entries == null) return 0L;
>       long sum = 0L;
>       for (File entry : entries) {
>           sum += entry.isDirectory()
>               ? computeCacheSizeRecursively(entry)
>               : entry.length();
>       }
>       return sum;
>   }
>
>   private static int countCacheFilesRecursively(@NonNull File dir) {
>       File[] entries = dir.listFiles();
>       if (entries == null) return 0;
>       int count = 0;
>       for (File entry : entries) {
>           count += entry.isDirectory()
>               ? countCacheFilesRecursively(entry)
>               : 1;
>       }
>       return count;
>   }
>   ```
>
>   Code cost: ~35 lines of helpers + a call-site adjustment. The Kotlin stdlib
>   `deleteRecursively()` would be shorter, but the file is Java —
>   hand-written walks stay in the same language (no
>   Kotlin-interop overhead, no new module import).
>
>   <!-- FIX: Phase-B S-7 (2026-05-13) – Race-Schutz: "Cache leeren" während aktiver Recording. -->
>   **Race protection: an active recording during "Clear cache" (Phase-B S-7):**
>
>   If the user clicks "Clear cache" in Preferences while a recording is running (MediaRecorder
>   has `cacheDir/audio/rec_*.m4a` open for writing), the unconditional `entry.delete()` call
>   on the open file leads to an `unlink()` on the Linux/Android FS — the file descriptor stays
>   open, MediaRecorder keeps writing into the unlinked inode, but **on recording stop the
>   file is gone** (no dirent entry anymore). The pipeline stage sees a missing audio file →
>   ghost session, FAILED. The user notices "my recording is lost" — hard to debug, because the
>   click path "Settings → Clear cache" and the recording are typically temporally decoupled.
>
>   **Mitigation:** Defensive precondition in PreferencesFragment's `OnPreferenceClickListener`:
>
>   ```java
>   cachePreference.setOnPreferenceClickListener(preference -> {
>       // Phase-B S-7: Race-Schutz gegen offene MediaRecorder-FDs.
>       // RecordingState liest via DictateUiStateObserver (Spec 1 §4.4 — Java-Brücke aus S-3 F-6).
>       boolean recordingActive = isRecordingActive();    // Helper, siehe unten
>       if (recordingActive) {
>           Toast.makeText(requireContext(),
>               R.string.dictate_cache_clear_blocked_recording, Toast.LENGTH_LONG).show();
>           return true;
>       }
>       // ... bestehender MaterialAlertDialogBuilder ...
>   });
>
>   private boolean isRecordingActive() {
>       // Snapshot-Read aus dem letzten DictateUiStateObserver-Event.
>       // Settings-Screen läuft typischerweise NICHT während Recording (User wechselt aus IME-
>       // Service in Settings-Activity), aber Edge-Case: Activity bleibt offen, User wechselt
>       // zurück, startet Recording, wechselt wieder zu Settings → recordingActive == true.
>       return lastObservedState != null && lastObservedState.recording.isActiveOrPausedOrPreparing();
>   }
>   ```
>
>   New string resource: `dictate_cache_clear_blocked_recording = "Recording in progress — the cache cannot
>   be cleared. Stop the recording first."`
>
>   **Accepted edge case:** if the user clicks in the middle of the 1-2-second window between recording
>   stop and the state update, the race protection can fail — but that is accepted, because
>   the recording-stop path has already copied the audio file away **before** the cache-wipe-click
>   resolution (`persistFromCache` runs synchronously in the stop effect, §4.11.6.1) — the wipe
>   thus only clears the already-redundant cache entry.
> - **Incorporation:** Java patch above (§4.11.6.3) replaces the old
>   "Clear cache" logic in Block 4. The statement in §4.11.8 ("Clear cache
>   clears the subdir too") applies **again** after this patch.

#### §4.11.7 Edge-case table

| # | Scenario | Behavior | Rationale |
|---|---|---|---|
| 1 | `cacheDir.mkdirs()` fails (storage full) | `allocate()` throws `IOException`; the resolver catches → `ToastSink` shows "Storage full" | The reducer stays pure, the view layer gives user feedback. The recording is not started. |
| 2 | `allocate()` twice in a row without a dispatch in between (double click) | Both calls create different files (UUID suffix). The first file stays as an orphan in the cache. | The orphan is removed at the next `onCreate` (cleanupOrphans) — no permanent leak. The double-click protection itself lives in `dispatchInternal` (RecordingState switches Idle→Preparing → the second StartRecording action is `null` in the reducer arm). |
| 3 | OS deletes `cacheDir` between `allocate()` and `MediaRecorder.start()` | `MediaRecorder.prepare()` fails with IOException → the effect handler dispatches `Action.RecordingAction.CancelRecording` (see §15.2 reducer arm Preparing+CancelRecording) | Failure path via existing reducer logic, no special code in the factory. |
| 4 | Multi-job: Job-N is running (transcription, has its own audio_file_path) and the user starts a new recording | `allocate()` creates a fresh file (UUID suffix) — no conflict with Job-N. | The UUID suffix guarantees uniqueness per allocation. Only 1 active recording at a time (RecordingState is single-instance) — no race between 2 parallel `allocate()` calls. |
| 5 | `cleanupOrphans` runs in parallel with `allocate()` (very close to service start) | `cleanupOrphans` filters via the `referencedPaths` set; the freshly allocated file is (a) not yet in the DB, (b) but has a UUID name that is not in `referencedPaths` → could be deleted | **Mitigation:** `cleanupOrphans` runs in `onCreate` **asynchronously in `serviceScope`** and is typically finished before the first user interaction. The remaining race risk is accepted: in the worst case MediaRecorder recreates the file at `prepare()` (the file path stays the same, because the UUID was already in the `audioFile` argument). Pragma: no lock needed. |
| 6 | App update (version change) | Android does NOT delete `cacheDir` on app updates — survives. | Existing sessions in the DB (`audio_file_path` points to the old path) stay valid. The recovery path §11.6.2 already covers edge cases (path-format change) via a file-existence check. Migration detail see §4.11.6.2. |
| 7 | Cache file survives after a successful `persistFromCache` (see §4.11.6.1) | The cache file stays around, the DB path now points to `filesDir/recordings/{sid}.m4a` | At the next service boot the cache-file path is NOT in `referencedPaths` (DB points to filesDir) → `cleanupOrphans` deletes it. Accepted delay (max. until service restart). KG-AFF-1 for the immediate-delete variant. |
| 8 | `context.cacheDir == null` (very rare — only theoretically possible in the `Application.attachBaseContext` path) | The `audioCacheDir` lazy throws NullPointerException at the first `allocate()`/`cleanupOrphans()` | **Behavior:** In the Pipeline-Service onCreate `cacheDir` is always initialized (Service.onCreate runs after Application.onCreate). Since no call happens before Service.onCreate, the null case cannot occur here. Still defensive: a `requireNotNull(context.cacheDir)` in the constructor would be sensible if a test path injects null. See KG-AFF-5. |
| 9 | The `audio/` subdir is a symlink (placed from outside; theoretically only possible via `adb`/root, not via the app sandbox) | `mkdirs()` fails with `IOException` (not through the symlink target) | `allocate()` propagates the IOException → resolver toast. A non-realistic scenario on non-rooted devices (the app sandbox does not allow a symlink inject in `cacheDir`). No special handling needed. |
| 10 | DB read in step 8 (`findAllAudioFilePaths`) fails (DB locked, migration pending) | The `try` block in the service wiring catches → `Log.w` → cleanup skipped | The service boot stays green. At the next boot the cleanup is retried. Idempotency (edge case #2 + #4): no "lost" recordings — orphans live until a successful cleanup. |
| 11 | App update with the old `cacheDir/audio.m4a` outside the `audio/` subdir (see §4.11.6.2) | `cleanupOrphans` does **not** see it (sub-dir scope). Stays as a "stranded file" until user "Clear cache" or an OS wipe | Accepted. The recovery path marks the referencing session as FAILED as soon as the file disappears. A migration helper is optional, see KG-AFF-2. |

#### §4.11.8 Design decisions — compact rationale

- **`cacheDir/audio/` (subdir) instead of `cacheDir/`:** isolates the audio cleanup
  from settings/export caches; the audio subdir is **self-cleaning**
  via `cleanupOrphans` at service boot. Note: today's
  "Clear cache" loop in `PreferencesFragment.java:272-289` is
  **not recursive** and therefore sees the subdir as a non-empty folder
  → `File.delete()` is a no-op on directories. See §4.11.6.3 +
  KG-AFF-3 if the Preferences UX should also clear the subdir.
- **`rec_{timestamp-ms}_{uuid8}.m4a` naming:** a timestamp for debug
  readability (sortable in `ls -la`/logs), a UUID suffix for
  collision safety (multi-job, R.8). A pure UUID would be debug-hostile;
  a pure timestamp collides in the same ms.
- **`allocate()` does NOT create the file:** MediaRecorder writes it itself —
  an empty `createNewFile()` would be an orphan risk on a `dispatch()`
  failure. Only `mkdirs()` for the directory.
- **Interface + DI instead of a Kotlin `object`:** reducer tests and resolver
  tests can inject a `FakeAudioFileFactory` (e.g. with
  `temporaryFolder.newFile()`). A singleton would not be mockable without
  an `applicationContext` hack.
- **`cleanupOrphans` separate from `allocate`:** SRP. `allocate` runs
  on the main thread (resolver), must not do I/O listing;
  `cleanupOrphans` runs in `Dispatchers.IO`, once per boot.
- **Injected into `ModuleServices`:** the same source for (a) the pre-
  dispatch resolver and (b) any future effect-handler calls;
  avoids second DI wires.
- **`cacheDir` instead of `filesDir`:** the transient phase (cache); persistence
  is handled by `RecordingRepository.persistFromCache` (unchanged).
  The consequence (OS-wipe risk) is covered by §11.6.2 (ghost sessions
  → FAILED + error icon in history).

#### §4.11.9 Test strategy + skeletons

**Test style in the project** (see
`app/src/test/java/.../core/RecordingStateControllerTest.kt`):
- **K-1:** hand-written fakes (no Mockito).
- **K-4:** JVM unit tests, no Robolectric, no Android instrumentation.
- Android seams are `open`/abstracted so that fakes can substitute
  without `Context`/`Service`/Android framework.

##### Unit test 1: `CacheDirAudioFileFactoryTest.kt`

A pure JVM test. Substitutes `Context.cacheDir` via constructor
injection — the default impl does not allow that directly (today it takes
`Context` and reads `context.cacheDir`). For testability
**recommendation:** extend the constructor to:

```kotlin
class CacheDirAudioFileFactory(
    private val cacheDirProvider: () -> File,           // statt Context
    private val clock: () -> Long = System::currentTimeMillis,
) : AudioFileFactory {
    // ... nutzt cacheDirProvider() statt context.cacheDir
}

// Production-Construct:
CacheDirAudioFileFactory({ context.cacheDir }, System::currentTimeMillis)
```

Test skeleton:

```kotlin
package net.devemperor.dictate.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM unit-tests for [CacheDirAudioFileFactory] (Spec 1 §4.11).
 *
 * K-1 / K-4 compliance: handwritten fakes only; pure JUnit (no Robolectric,
 * no MediaRecorder, no Android Context).
 */
class CacheDirAudioFileFactoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newFactory(clock: () -> Long = { 1_000L }): CacheDirAudioFileFactory =
        CacheDirAudioFileFactory(
            cacheDirProvider = { tempFolder.root },
            clock = clock,
        )

    // --- allocate() ---

    @Test fun `allocate creates audio subdir if missing`() {
        val factory = newFactory()
        val file = factory.allocate()
        assertTrue("audio/ subdir must exist", File(tempFolder.root, "audio").isDirectory)
        assertEquals(File(tempFolder.root, "audio"), file.parentFile)
    }

    @Test fun `allocate produces rec_TS_UUID m4a name pattern`() {
        val file = newFactory(clock = { 1_700_000_000_000L }).allocate()
        val name = file.name
        assertTrue("starts with rec_", name.startsWith("rec_"))
        assertTrue("ends with .m4a", name.endsWith(".m4a"))
        assertTrue("contains timestamp", name.contains("1700000000000"))
        // UUID-8 hex segment = exactly 8 hex chars between second underscore and .m4a
        val segments = name.removeSuffix(".m4a").split("_")
        assertEquals(3, segments.size)
        assertEquals(8, segments[2].length)
    }

    @Test fun `allocate never returns the same path twice within same ms`() {
        val factory = newFactory(clock = { 1_000L })   // clock is frozen
        val a = factory.allocate()
        val b = factory.allocate()
        assertNotEquals("UUID suffix must collide-free", a, b)
    }

    @Test fun `allocate does NOT create the file (only the dir)`() {
        val file = newFactory().allocate()
        assertFalse("MediaRecorder writes the file, not the factory", file.exists())
    }

    @Test(expected = java.io.IOException::class)
    fun `allocate throws IOException when mkdirs fails`() {
        // Create a regular file where the audio subdir should be → mkdirs() fails
        val collisionFile = File(tempFolder.root, "audio").apply { createNewFile() }
        check(collisionFile.isFile)
        newFactory().allocate()
    }

    // --- cleanupOrphans() ---

    @Test fun `cleanupOrphans deletes only files matching prefix+ext NOT in referenced`() {
        val factory = newFactory()
        val audioDir = File(tempFolder.root, "audio").apply { mkdirs() }

        val orphan      = File(audioDir, "rec_100_aaaaaaaa.m4a").apply { writeText("x") }
        val referenced  = File(audioDir, "rec_200_bbbbbbbb.m4a").apply { writeText("y") }
        val nonMatching = File(audioDir, "settings_backup.txt").apply { writeText("z") }

        factory.cleanupOrphans(referencedPaths = setOf(referenced.absolutePath))

        assertFalse("orphan deleted", orphan.exists())
        assertTrue ("referenced kept", referenced.exists())
        assertTrue ("non-matching file untouched", nonMatching.exists())
    }

    @Test fun `cleanupOrphans is no-op on empty referenced + empty dir`() {
        val factory = newFactory()
        File(tempFolder.root, "audio").mkdirs()
        factory.cleanupOrphans(emptySet())
        // No assertions — no exception is the test
    }

    @Test fun `cleanupOrphans on missing audio subdir is no-op`() {
        // audio/ does not exist (factory was never used for allocate)
        val factory = newFactory()
        factory.cleanupOrphans(emptySet())   // must not throw
    }
}
```

##### Unit test 2: `RecordingModuleAudioFileFactoryWiringTest.kt`

Verifies that the reducer **threads the `audioFile` argument path through
unmodified** (the reducer is pure, no FS access). Fake factory:

```kotlin
class FakeAudioFileFactory(private val fakeFile: File) : AudioFileFactory {
    override fun allocate(): File = fakeFile
    override fun cleanupOrphans(referencedPaths: Set<String>) = Unit
}

class RecordingModuleAudioFileFactoryWiringTest {
    @Test fun `StartRecording threads audioFile through to Preparing-state`() {
        val fakeFile = File("/tmp/test/rec_42_deadbeef.m4a")
        val initial  = DictateUiState.initial().copy(recording = RecordingState.Idle)
        val action   = Action.RecordingAction.StartRecording(
            target    = InsertionTarget.MainInputConnection,
            audioFile = fakeFile,
        )
        val (newState, _) = RecordingModule.reduce(initial, action)
        val preparing = newState.recording as RecordingState.Preparing
        assertEquals(fakeFile, preparing.audioFile)
    }
}
```

##### Integration test (optional): `AudioFileFactoryE2ETest.kt`

End-to-end with a real `Context.cacheDir` via Robolectric or an
instrumented test. **Recommendation:** **not** a priority — unit test
1 covers all FS paths; an additional E2E would only test the
`ApplicationProvider.getApplicationContext().cacheDir` path,
which is already guaranteed by the Android SDK.

If, out of caution, a Robolectric test is wanted, it lives under
`app/src/test/java/.../core/AudioFileFactoryRobolectricTest.kt` with the
`@Config(sdk = [Build.VERSION_CODES.O])` annotation; but without a hard
reason rather skip it (additional test runtime, no additional
coverage).

**Mandatory tests before Block-4 completion:**

| Test | File | Block | Role |
|---|---|---|---|
| `CacheDirAudioFileFactoryTest` | `app/src/test/.../core/` | Block 4 | unit, FS behavior |
| `RecordingModuleAudioFileFactoryWiringTest` | `app/src/test/.../core/` | Block 4 | reducer contract |
| (optional) Robolectric test | `app/src/test/.../core/` | later | only if real-context behavior is in question |

#### §4.11.10 Failure modes explicit

| # | Failure | Handling | Who catches? |
|---|---|---|---|
| F1 | `mkdirs()` fails (storage full, FS permission, unusual FS layout) | `IOException` from `allocate()` | Resolver in Spec 2 §10 → `services.toastSink.show(R.string.dictate_storage_full)` → `return null` → no `dispatch()`. **A new string resource is needed** in `res/values/strings.xml` (`dictate_storage_full`) + DE translation. |
| F2 | `mkdirs()` fails **inside `cleanupOrphans`** (subdir non-existent, no listFiles possible) | `listFiles()` returns `null` → `?: return` takes effect, no delete loop | `cleanupOrphans()` itself (defensive). |
| F3 | `f.delete()` fails (FS race, the file is open by another process) | `runCatching` catches, `Log.w` writes a warning, the loop continues | inside `cleanupOrphans()`. |
| F4 | DB read in `findAllAudioFilePaths()` fails (DB locked) | The `try` block in the service wiring (see §4.11.5.3) catches → `Log.w` → cleanup skipped for this boot | the service-onCreate wrapper. |
| F5 | Race: `allocate()` produces the path, `cleanupOrphans` deletes it before `MediaRecorder.prepare()` (edge case #5) | `MediaRecorder.prepare()` fails → the effect handler dispatches `CancelRecording` (existing path, §15.2) | the effect handler, not the factory. |
| F6 | Symlink attack on `audio/` (non-root-realistic, the app sandbox protects) | `mkdirs()` fails (the symlink does not take effect) → IOException → like F1 | resolver toast. |
| F7 | UUID collision (practically excluded: an 8-hex-UUID prefix has a 2^32 space per ms) | If it does happen — the new file overwrites the old orphan, no data loss (the old orphan was not persisted) | n/a (probabilistically impossible.) |

**Important F5 — race window more precisely:**

```
T0   Resolver ruft factory.allocate() → File-Path mit UUID-X erzeugt
T1   Resolver-Code dispatcht Action.StartRecording(audioFile=path)
T2   Reducer wechselt RecordingState.Idle → Preparing
T3   Effect.AllocateMediaRecorder läuft in Effects-Coroutine
T4   recordingManager.start(file=path) → MediaRecorder.prepare() → erzeugt File
```

Race window: T0 to T4. `cleanupOrphans` runs **once** in the
`serviceScope` directly after the service boot. If the user starts a recording
within ~50 ms of the service start (T0 before the cleanup end),
`cleanupOrphans` cannot find an "orphan" between T0 and T4
(`allocate` creates nothing → `listFiles` does not see the path). If
`cleanupOrphans` runs **after** T4 (or between T4 and a
later pipeline DB write), it sees the file and it is not in
`referencedPaths` (the DB does not yet have an `audio_file_path` for the
running session) → it could be deleted while the recording is running.
**But:** `cleanupOrphans` runs once in `onCreate`, not
periodically — as soon as the one run is finished, this risk
no longer exists.

**Accepted pragma:** The race probability is very low
(service boot + immediate recording start within ~100 ms — the user must
be extremely fast). In the failure case: MediaRecorder keeps writing into
an unlinked file descriptor → the recording is lost, the reducer
eventually lands in `Preparing+CancelRecording` (via the
file-existence-check path on the `stop` submit). The user may notice it from
a missing recording — rare enough that no lock logic is needed
(edge case #5).

<!-- KNOWLEDGE-GAP: KG-AFF-4 – Race cleanupOrphans + concurrent allocate [RESOLVED 2026-05-11] -->
> **✅ Knowledge gap (KG-AFF-4): Is best-effort sufficient, or is a lock/serialize wanted? — RESOLVED 2026-05-11**
>
> - **What we knew:** The race window exists between T0 (`allocate()`) and
>   T4 (`MediaRecorder.prepare()` writes the file). `cleanupOrphans` runs
>   asynchronously once in the same scope. With an extremely fast user tap
>   after the service boot (~50 ms), `cleanupOrphans` could delete the freshly allocated
>   file before it is referenced in the DB as an `audio_file_path`.
> - **What we did not know:** Whether the status quo (best-effort, no lock)
>   is sufficient, or whether a lock mechanism is needed.
> - **Resolution:** **Status quo accepted + cutoff filter introduced.**
>   Rationale: a synchronizing lock (e.g. `CountDownLatch.await()`
>   in `allocate()`) would be expensive and makes the main-thread resolver blockable
>   — unsightly. Instead, in `cleanupOrphans` we filter out files that are
>   **younger than 60 seconds** (wall clock via `lastModified()`).
>   This closes the race window: every freshly allocated file
>   is in the cutoff window, so it is not touched. Crash orphans from
>   earlier service lifecycles are trivially older than 60 seconds — they
>   are still deleted.
>
>   **Concrete code patch** in `CacheDirAudioFileFactory.cleanupOrphans`
>   (§4.11.3, l. 1028-1038):
>
>   ```kotlin
>   override fun cleanupOrphans(referencedPaths: Set<String>) {
>       // KG-AFF-4: Cutoff at 60s ago — never touch a freshly-allocated file
>       // that may not yet appear in referencedPaths because its DB-row write
>       // is still in-flight after the user tapped record mid-boot.
>       val cutoffMs = clock() - CUTOFF_GRACE_MS
>       val files = audioCacheDir.listFiles { f ->
>           f.isFile &&
>               f.name.startsWith(PREFIX) &&
>               f.name.endsWith(EXT) &&
>               f.lastModified() < cutoffMs   // ← guards the alloc→prepare race
>       } ?: return
>       files.forEach { f ->
>           if (f.absolutePath !in referencedPaths) {
>               runCatching { f.delete() }
>                   .onFailure { Log.w(TAG, "orphan cleanup failed: ${f.name}", it) }
>           }
>       }
>   }
>
>   companion object {
>       // ... existing constants ...
>       private const val CUTOFF_GRACE_MS = 60_000L   // 60 s — covers boot + first dispatch
>   }
>   ```
>
>   **How the cutoff covers the race:** `allocate()` only produces a
>   `File` path, without creating the file. `MediaRecorder.prepare()` creates
>   it, with the OS `lastModified() = now()`. Even with an extremely fast
>   user tap immediately after the service boot: the file would, if it
>   came to `cleanupOrphans`'s attention, be younger than 60 s → it is skipped.
>   Trade-off: with a crash orphan that happens to have been created <60 s before the service boot,
>   it stays around for one boot cycle longer — accepted,
>   because the next boot then certainly catches it.
>
>   **Test skeleton** (addition in `CacheDirAudioFileFactoryTest.kt`):
>
>   ```kotlin
>   @Test fun `cleanupOrphans skips files younger than CUTOFF_GRACE_MS`() {
>       val factory = newFactory(clock = { 1_000_000L })  // "now" = 1_000_000 ms
>       val audioDir = File(tempFolder.root, "audio").apply { mkdirs() }
>       val fresh    = File(audioDir, "rec_999_aaaaaaaa.m4a").apply {
>           writeText("x"); setLastModified(999_999L)       // ~1 ms ago
>       }
>       val ancient  = File(audioDir, "rec_100_bbbbbbbb.m4a").apply {
>           writeText("y"); setLastModified(100L)            // ~999 s ago
>       }
>       factory.cleanupOrphans(referencedPaths = emptySet())
>       assertTrue ("fresh kept (within cutoff)", fresh.exists())
>       assertFalse("ancient deleted (past cutoff)", ancient.exists())
>   }
>   ```
> - **Incorporation:** Code patch above (§4.11.10) + test skeleton in §4.11.9
>   (an additional test case in `CacheDirAudioFileFactoryTest`). §4.11.3
>   default impl is implemented in Block 4 with the cutoff filter;
>   edge case #5 (§4.11.7) and failure F5 (§4.11.10) are after Block 4
>   factually unreachable through the cutoff (race probability → 0).

<!-- KNOWLEDGE-GAP: KG-AFF-5 – cacheDir-Null + Constructor-Defensive [RESOLVED 2026-05-11] -->
> **✅ Knowledge gap (KG-AFF-5): Defensive null check for context.cacheDir? — RESOLVED 2026-05-11**
>
> - **What we knew:** Android doc: `Context.getCacheDir()` can theoretically
>   return `null` if `Application.attachBaseContext` has not yet run
>   (extremely rare). Service.onCreate runs strictly after Application.onCreate,
>   so `null` is practically excluded for our call path.
> - **What we did not know:** Whether a `requireNotNull` in the constructor is sensible
>   as an implementer sanity (fail-fast vs. dead defensive).
> - **Resolution:** **Defensive `requireNotNull` in the `audioCacheDir` lazy init.**
>   Rationale: clarity > marginal performance (1 null check is 1 branch).
>   Fail-fast with a clear error message beats a late-appearing
>   `NullPointerException` from the `File(null, AUDIO_SUBDIR)` constructor,
>   which comes too late for the debugger. In tests that feed in a bizarre
>   `cacheDirProvider = { null }` (see §4.11.9
>   constructor injection), the check takes effect early and delivers the correct
>   diagnosis.
>
>   **Concrete code patch** in `CacheDirAudioFileFactory` (§4.11.3 + §4.11.9
>   constructor-injection variant):
>
>   ```kotlin
>   class CacheDirAudioFileFactory(
>       private val cacheDirProvider: () -> File,                      // statt Context
>       private val clock: () -> Long = System::currentTimeMillis,
>   ) : AudioFileFactory {
>
>       private val audioCacheDir: File by lazy {
>           val cacheDir = requireNotNull(cacheDirProvider()) {
>               "cacheDir is null — Application.onCreate has not run yet"
>           }
>           File(cacheDir, AUDIO_SUBDIR).apply { mkdirs() }
>       }
>
>       // ... allocate / cleanupOrphans unchanged ...
>   }
>   ```
>
>   **Production construct** stays:
>   ```kotlin
>   CacheDirAudioFileFactory({ applicationContext.cacheDir }, System::currentTimeMillis)
>   ```
>
>   `requireNotNull` runs at the first read of `audioCacheDir` (lazy), not
>   at construction — so construction in a bizarre
>   test pre-state (e.g. without a set-up Application context) is also safe; the
>   check only fires when the factory is actually used.
> - **Incorporation:** Code patch above — the default impl in §4.11.3 (l. ~1006-1013)
>   and the test constructor sketch in §4.11.9 are implemented this way in Block 4.
>   Edge case #8 (§4.11.7) is after Block 4 described with concrete behavior:
>   `IllegalArgumentException` with a clear message instead of a diffuse NPE.

#### §4.11.11 Constants, tunables, logging

##### Constants (unified)

The default-impl companion (§4.11.3) holds the following `const val` constants:

```kotlin
companion object {
    private const val TAG = "AudioFileFactory"          // android.util.Log Tag
    private const val AUDIO_SUBDIR = "audio"            // Sub-Verzeichnis-Name unter cacheDir
    private const val PREFIX = "rec_"                   // File-Name-Prefix für cleanup-Filter
    private const val EXT = ".m4a"                      // MediaRecorder MPEG_4 + AAC Container
}
```

**Visibility:** `private` is the default. If tests want to access them
(e.g. to verify an `Orphan-Filename` heuristic),
raise to `internal`. Recommendation: **tests should NOT access the
constants** — they should test the **contract** (file
matched/non-matched), not the specific string form. So
`private` stays.

**No tunables needed:** no TTL for files, no size limits,
no retention — everything searched via DB `referencedPaths`.

##### Logging contract

| Event | Tag | Level | Content |
|---|---|---|---|
| Orphan cleanup started | `"AudioFileFactory"` | DEBUG (optional) | `"cleanupOrphans: $N files in audio/"` — only in debug builds, avoids production log spam |
| Single delete() fail | `"AudioFileFactory"` | WARN | `"orphan cleanup failed: ${f.name}"` + Throwable. **No** absolute path logged (PII-conservative, although cache files contain no user data). |
| Boot-cleanup wrapper catch | `"DictatePipelineSvc"` | WARN | `"orphan cleanup failed at boot"` + Throwable. Source: DB read fails, or the DAO throws. |
| `mkdirs` fails in `allocate` | n/a | n/a — throws IOException, the caller logs via the ToastSink path | reducer-aware, not factory-aware. |

**Rationale:**

- No path logging at INFO level — cache files are non-secret,
  but path logging provides no debug value (the user can do nothing with an absolute
  path). File names (relative form) are sufficient.
- The log tag is stable + greppable (`AudioFileFactory` convention in the project,
  consistent with `RecordingRepository`/`JobExecutor`/`PipelineOrchestrator`).
- Cleanup is best-effort — a WARN on a single delete fail must not stop the
  system.
- A boot-cleanup failure is **not** logged as ERROR, because the boot
  still stays green (idempotency at the next service start).

#### §4.11.12 Knowledge gaps — overview

This section collects all KG-AFF markers for the implementer:

| ID | Title | Block | Status / Resolution |
|---|---|---|---|
| KG-AFF-1 | Immediate delete of the cache file after persist | Block 4 | ✅ RESOLVED 2026-05-11 — immediate delete in `PipelineOrchestrator.persistNewSession` |
| KG-AFF-2 | Migration of the old `cacheDir/audio.m4a` | Block 4 | ✅ RESOLVED 2026-05-11 — `LegacyAudioFileMigration` + DAO query, pref-flag idempotent |
| KG-AFF-3 | PreferencesFragment recursive | Block 4 | ✅ RESOLVED 2026-05-11 — `clearCacheRecursively` helper in Java |
| KG-AFF-4 | Race cleanupOrphans + concurrent allocate | Block 4 | ✅ RESOLVED 2026-05-11 — 60 s cutoff via a `lastModified()` filter |
| KG-AFF-5 | cacheDir null + constructor-defensive | Block 4 | ✅ RESOLVED 2026-05-11 — `requireNotNull` in the lazy init |

Implementer action: All KG-AFF markers are resolved — the respective
code patches stand directly in the markers (§4.11.6.1, §4.11.6.2, §4.11.6.3,
§4.11.10) as a RESOLVED block. Block 4 implements them 1:1.

---

## §5 Local-Binder API (F-8: Single Dispatch)

> **Architecture correction F-8 (2026-05-09):** Earlier spec versions had the
> LocalBinder with ~25 typed action methods (`pauseRecording()`, `stopRecording()`,
> `confirmInsertion()`, …), in parallel with an `Action` sealed class with the same
> variants — double definition, a DRY violation. Correction: the LocalBinder shrinks
> to a single `dispatch(action: Action)` entry. All UI events — including
> view-shown/hidden — run through `dispatch(Action.…)`; **no typed forwarder
> methods** (F-8 spirit).
>
> Advantages:
> - **DRY**: the action list lives only in the `Action` sealed class (Spec 2 §3.3)
> - **OCP**: a new action = only extend the sealed class, no forwarder in the binder
> - **Compile safety**: the Kotlin compiler enforces exhaustivity in the
>   reducer `when` block — no action is forgotten

<!-- FIX: Issue 2.0.4 – notifyImeViewShown/Hidden-Wrapper entfernt (F-8: keine typed Forwarder) -->

```kotlin
class DictatePipelineService : Service() {
    private lateinit var orchestrator: DictateOrchestrator

    inner class LocalBinder : Binder() {
        /** Read-only State-Stream (collectable). */
        val state: StateFlow<DictateUiState> get() = orchestrator.state

        <!-- FIX: Phase-C C-1 (2026-05-14) – Return-Type DispatchOutcome dokumentiert (war implizit
             durch `= orchestrator.dispatch(action)` inferiert). IME-Konsumenten dürfen den Outcome
             ignorieren — Rejected/Unrouted sind in Phase 1 telemetry-only. -->
        /**
         * Single Dispatch — der einzige öffentliche Eingang für Mutationen.
         * Auch Lifecycle-Events (View-Shown/Hidden) laufen über diesen Pfad
         * via `Action.ViewModeAction.OnImeViewShown / OnImeViewHidden` —
         * keine typed Forwarder-Methoden (F-8).
         *
         * **Return:** `DispatchOutcome` (siehe §4.3). IME-Konsumenten dürfen
         * den Wert ignorieren (`fun dispatch(...) = orchestrator.dispatch(...)`
         * inferiert ihn nur weiter). `Rejected`/`Unrouted` sind Phase-1-Telemetry-
         * Signale und brechen die UI nicht — der Orchestrator loggt sie bereits.
         */
        fun dispatch(action: Action): DispatchOutcome = orchestrator.dispatch(action)
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder
}
```

**IME-service call instead of a typed forwarder:**

```kotlin
// Statt pipeline?.notifyImeViewShown() :
pipeline?.dispatch(Action.ViewModeAction.OnImeViewShown)
// Statt pipeline?.notifyImeViewHidden() :
pipeline?.dispatch(Action.ViewModeAction.OnImeViewHidden)
```

**IME side:**

```kotlin
class DictateInputMethodService : InputMethodService() {
    private var pipeline: DictatePipelineService.LocalBinder? = null
    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // KeyboardLayoutManager bekommt einen `onAction`-Callback im Konstruktor,
    // der direkt durch den LocalBinder.dispatch geforwardet wird.
    private val keyboardLayoutManager = KeyboardLayoutManager(
        scope = viewScope,
        onAction = ::dispatchAction,    // F-8: ein Eingang
    )

    private fun dispatchAction(action: Action) {
        pipeline?.dispatch(action)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            pipeline = binder as DictatePipelineService.LocalBinder
            viewScope.launch {
                pipeline!!.state.collect { state ->
                    keyboardLayoutManager.onStateChanged(state)
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            pipeline = null
        }
    }
}
```

---

## §6 Persistence extension

### §6.1 Schema migration M3→M4

> **Code pointer (today, M3):** `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo3.kt:43-75` — `MIGRATION_2_3` defines today's `sessions` table incl. `status`/`origin`/`queued_prompt_ids`/`last_error_*`. Status-enum source: `app/src/main/java/net/devemperor/dictate/database/entity/SessionStatus.kt:11-16` (today: `RECORDED, COMPLETED, FAILED, CANCELLED`).

<!-- FIX: Issue PENDING-2 / Block-3 Resolved – SessionStatus-Erweiterung konkret spezifiziert -->

**M3→M4 performs two schema extensions together:**

1. **New column `inserted_at`** — a marker for "pipeline COMPLETED, but text not yet in the editor"
   (restart-button logic + cleanup policy, see §6.3).
2. **Extension of the `status`-CHECK constraint** by `RECORDING` + `TRANSCRIBING`, so that
   live pipeline statuses (today only in `ActiveJobRegistry`, process-local) support the
   OOM-death recovery path in §6.3 / §11.6.

> [!IMPORTANT]
> Point 2 forces a **table-recreate migration** (`CREATE TABLE … _new` + `INSERT … SELECT`
> + `DROP` + `RENAME`), because SQLite cannot change CHECK constraints via `ALTER TABLE`.
> So M3→M4 is **no longer purely additive** in the sense of D8. The trade-off is updated in §11.7 +
> architecture decision D8 (see §2): the migration nevertheless runs
> in a single Room transaction (atomic, abort-on-failure). The rollback stays safe.

**Rationale for the enum extension (lifecycle inventory):**

| Status (new) | Persisted at | Recovery behavior | Visible in HistoryActivity? |
|---|---|---|---|
| `RECORDING` | Recording start (mic open, before first frame) | Boot → `FAILED` + cleanup of the audio file (see §6.3) | Only in the OOM-death window (before `recoverFromDb`) — a defensive fallback badge (see §8 + HistoryAdapter) |
| `RECORDED` | Recording stop (audio file closed) | Boot → stays `RECORDED`, appears as `pendingSessions` (resume button) | yes (existing, pending icon) |
| `TRANSCRIBING` | Pipeline start (audio upload started) | Boot → downgrade to `RECORDED` (see §6.3; D4 / OPEN-4: **no auto-resume** — the user clicks restart) | like RECORDING |
| `COMPLETED` | Pipeline done (result text in DB) | Boot with `inserted_at IS NULL` → `pendingSessions` (insertion button) | yes (existing, no icon) |
| `FAILED` | API/quota/network error or recovery-promote from RECORDING | terminal, the user chooses reprocess via HistoryDetail | yes (existing, error icon) |
| `CANCELLED` | User explicit cancel | terminal | yes (existing, cancel icon) |

**State machine (forward path + recovery path, ASCII):**

```
                  register/start            stop                       submit
       Idle ─────────────────────► RECORDING ──────────► RECORDED ─────────────────► TRANSCRIBING
                                       │                    ▲                              │
                                       │                    │ recoverFromDb                │
                                       │ cancel             │  (downgrade,                 │ done
                                       │                    │   file exists)               │
                                       │                    │                              ▼
                                       │  recoverFromDb     │                          COMPLETED ──inserted_at:=now──► (terminal "done")
                                       │  (file lost or     │                              │
                                       │   half-written)    │                              │ error
                                       ▼                                                   ▼
                                   FAILED ◄───────────────────────────────────────────── FAILED
                                       ▲
                                       │  User-Cancel from any non-terminal:
                                       │  { RECORDING, RECORDED, TRANSCRIBING } ──cancel──► CANCELLED
```

**Persistence granularity:** RECORDING/TRANSCRIBING are **written on a state transition**
(see §6.2 checkpoint hooks). Within TRANSCRIBING there is **no sub-status update per
pipeline step** — step granularity already lives in `processing_steps` + `transcriptions` (see
`MIGRATION_1_2` in the MigrationTo3.kt sibling file). The `status` column only carries the phase marker.

**Concrete migration implementation** (file: `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo4.kt`, NEW):

```kotlin
package net.devemperor.dictate.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * M3 → M4 leistet zwei Schema-Erweiterungen:
 *
 * 1. Neue Spalte `inserted_at` (INTEGER NULL) — Marker für "COMPLETED, aber
 *    Insertion noch nicht erfolgt"; Backfill für bestehende COMPLETED-Zeilen.
 * 2. Erweiterung der `status`-CHECK-Constraint um RECORDING + TRANSCRIBING
 *    (für OOM-Death-Recovery, siehe §6.3 / §11.6).
 *
 * Da SQLite CHECK-Constraints nicht via ALTER TABLE ändern kann, recreaten
 * wir die `sessions`-Tabelle (analog MIGRATION_2_3 in MigrationTo3.kt). Die
 * Migration ist trotzdem rollback-sicher: Room führt sie in einer Transaktion
 * aus und bricht atomar ab, falls eines der Statements fehlschlägt.
 *
 * Backfill-Strategie:
 * - `inserted_at = created_at` für bestehende `status = 'COMPLETED'`-Zeilen
 *   (best-effort; der exakte Insertion-Zeitpunkt ist nicht rekonstruierbar,
 *   genügt aber für die 7-Tage-Cleanup-Policy).
 * - Live-Stati RECORDING/TRANSCRIBING kommen in der Migration NIE in Bestands-
 *   daten vor (sie wurden erst von M4 eingeführt), daher kein Daten-Backfill
 *   nötig.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Neue Tabelle mit erweiterter CHECK-Constraint + inserted_at-Spalte.
        //    FOREIGN-KEY-Referenz auf den FINALEN Tabellennamen (`sessions`),
        //    nicht auf `sessions_new` — Lesson-Learned aus MIGRATION_2_3.
        db.execSQL(
            """
            CREATE TABLE sessions_new (
                id TEXT NOT NULL PRIMARY KEY,
                type TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                target_app_package TEXT,
                language TEXT,
                audio_file_path TEXT,
                audio_duration_seconds INTEGER NOT NULL,
                parent_session_id TEXT,
                status TEXT NOT NULL DEFAULT 'COMPLETED'
                    CHECK (status IN (
                        'RECORDING', 'RECORDED', 'TRANSCRIBING',
                        'COMPLETED', 'FAILED', 'CANCELLED'
                    )),
                origin TEXT NOT NULL DEFAULT 'KEYBOARD'
                    CHECK (origin IN ('KEYBOARD', 'HISTORY_REPROCESS', 'POST_PROCESSING')),
                queued_prompt_ids TEXT,
                last_error_type TEXT
                    CHECK (last_error_type IS NULL OR last_error_type IN (
                        'INVALID_API_KEY', 'RATE_LIMITED', 'MODEL_NOT_FOUND',
                        'BAD_REQUEST', 'SERVER_ERROR', 'NETWORK_ERROR',
                        'UNKNOWN'
                    )),
                last_error_message TEXT,
                final_output_text TEXT,
                input_text TEXT,
                inserted_at INTEGER,
                FOREIGN KEY (parent_session_id) REFERENCES sessions (id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        // 2. Bestehende Daten kopieren; inserted_at-Backfill für COMPLETED.
        db.execSQL(
            """
            INSERT INTO sessions_new (
                id, type, created_at, target_app_package, language,
                audio_file_path, audio_duration_seconds, parent_session_id,
                status, origin, queued_prompt_ids,
                last_error_type, last_error_message,
                final_output_text, input_text, inserted_at
            )
            SELECT
                id, type, created_at, target_app_package, language,
                audio_file_path, audio_duration_seconds, parent_session_id,
                status, origin, queued_prompt_ids,
                last_error_type, last_error_message,
                final_output_text, input_text,
                CASE
                    WHEN status = 'COMPLETED' AND final_output_text IS NOT NULL
                        THEN created_at
                    ELSE NULL
                END
            FROM sessions
            """.trimIndent()
        )

        // 3. Alt droppen, neu umbenennen.
        db.execSQL("DROP TABLE sessions")
        db.execSQL("ALTER TABLE sessions_new RENAME TO sessions")

        // 4. Indices wiederherstellen (identisch zu MIGRATION_2_3 — keine
        //    Index-Erweiterung für `inserted_at`, siehe Begründung unten).
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_parent_session_id ON sessions (parent_session_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_type ON sessions (type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_created_at ON sessions (created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_origin ON sessions (origin)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_status ON sessions (status)")
    }
}
```

<!-- FIX: Phase-B S-2 (2026-05-13) – inserted_at-Index-Begründung dokumentieren. -->
> **Why no `index_sessions_inserted_at`?** The new `inserted_at` column is read by two queries: `findPendingInsertion()` (WHERE `inserted_at IS NULL`) and `deleteInsertedOlderThan(cutoff)` (WHERE `inserted_at < :cutoff`). Both do not run on the hot path (recovery at service boot or cleanup at service idle-stop, each 1× per service lifecycle) and the `sessions` table is small in the expected use case (typically <1k rows, power-user <10k). An additional index would increase the insert/update cost without a significant read gain at this order of magnitude. If telemetry later shows that `findPendingInsertion()` becomes a boot brake, the index can be added as a post-hoc migration (M4→M5).
>
> The SessionEntity.kt annotation therefore stays at 5 indices (`parent_session_id`, `type`, `created_at`, `origin`, `status`) — do not add an `Index("inserted_at")`.

**Extension of `SessionStatus.kt`** (`app/src/main/java/net/devemperor/dictate/database/entity/SessionStatus.kt`):

```kotlin
package net.devemperor.dictate.database.entity

/**
 * Persisted lifecycle state of a [SessionEntity].
 *
 * Lifecycle-Order (forward path):
 *   RECORDING → RECORDED → TRANSCRIBING → COMPLETED
 * Terminal-Exits aus jedem Non-Terminal: FAILED, CANCELLED.
 *
 * Vor dem M4-Refactor wurden RECORDING/TRANSCRIBING NICHT persistiert
 * (Runtime-only in [ActiveJobRegistry]). Mit der Pipeline-Service-Refactor-
 * Architektur (siehe Plan §6 + §11.6) werden die Live-Stati zusätzlich in
 * der DB geschrieben, damit ein OOM-Death während aktiver Phase erkennbar
 * und für den User wieder auflösbar wird.
 *
 * Follows the Double-Enum pattern (see docs/DATABASE-PATTERNS.md):
 * die SQL-CHECK-Constraint matched diese Werte exakt
 * (siehe `MigrationTo4.kt`).
 */
enum class SessionStatus {
    /** Aufnahme läuft (Mic geöffnet, Audio-File geschrieben). Persistiert via §6.2 Recording-Start. Recovery → FAILED. */
    RECORDING,

    /** Audio-File geschlossen, Pipeline noch nicht (oder nicht erfolgreich) gelaufen. */
    RECORDED,

    /** Pipeline läuft (Whisper-Upload, Transcribe, Steps). Persistiert via §6.2 Pipeline-Start. Recovery → RECORDED. */
    TRANSCRIBING,

    /** Pipeline erfolgreich beendet, Result-Text in DB. Insertion-Marker via `inserted_at IS NOT NULL`. */
    COMPLETED,

    /** Pipeline endete mit Fehler (API/Quota/Network/RecordingLost). `lastErrorType` + `lastErrorMessage` gefüllt. */
    FAILED,

    /** User-Explicit-Cancel. `lastErrorType` ist NULL. */
    CANCELLED
}
```

**State-machine table (Action → Next status):**

| Current status | Action | New status | DB update statement |
|---|---|---|---|
| `Idle` (no row) | Recording start | `RECORDING` | `INSERT sessions (... status='RECORDING' ...)` |
| `RECORDING` | Recording stop (normal) | `RECORDED` | `UPDATE … SET status='RECORDED', audio_file_path=? WHERE id=?` |
| `RECORDING` | User cancel | `CANCELLED` | `UPDATE … SET status='CANCELLED' WHERE id=?` |
| `RECORDED` | Pipeline start (submit) | `TRANSCRIBING` | `UPDATE … SET status='TRANSCRIBING' WHERE id=?` |
| `RECORDED` | User cancel | `CANCELLED` | `UPDATE … SET status='CANCELLED' WHERE id=?` |
| `TRANSCRIBING` | Pipeline done | `COMPLETED` | `UPDATE … SET status='COMPLETED', final_output_text=? WHERE id=?` |
| `TRANSCRIBING` | API/network error | `FAILED` | `UPDATE … SET status='FAILED', last_error_type=?, last_error_message=? WHERE id=?` |
| `TRANSCRIBING` | User cancel | `CANCELLED` | `UPDATE … SET status='CANCELLED' WHERE id=?` |
| `COMPLETED` | Text inserted in editor | `COMPLETED` (unchanged) | `UPDATE … SET inserted_at=now WHERE id=?` |
| `FAILED` / `CANCELLED` | (terminal) | — | — |

<!-- FIX: Phase-B S-1 (2026-05-13) – Status-Writes wandern aus dem monolithischen PipelineStateManager in Modul-EffectHandler (RecordingModule + PipelineModule, §15). -->
Status writes live in the respective module effect handlers (`RecordingModule.runEffect(Effect.PersistStatus)` for RECORDING/RECORDED, `PipelineModule.runEffect(Effect.PersistStatus)` for TRANSCRIBING/COMPLETED/inserted_at; see §6.2 + §15). `SessionManager` exposes two new methods for this in addition to the existing `finalizeCompleted/Cancelled/Failed`.

**Reference style** (see `app/src/main/java/net/devemperor/dictate/core/SessionManager.kt:97-111` — the today-existing `finalizeCompleted/Cancelled/Failed` are 1-3-line wrappers over `sessionDao.updateStatus(+updateError)`; no try/catch, no own transaction, no coroutine suspend — the methods are synchronous, because today's callers dispatch onto an IO thread via `dbExecutor` (see `DictateInputMethodService.java:248`)):

```kotlin
// SessionManager.kt — Ergänzung NACH Z. 111 (vor `getHistoricalQueuedPromptIds`),
//                    Anchor: gleicher "Terminal status writes"-Abschnitt:

<!-- FIX: Phase-B S-1 (2026-05-13) – Doc-Comments referenzieren Modul-EffectHandler statt monolithischen PipelineStateManager. -->
/** Sets status = RECORDING. Called from `RecordingModule.runEffect(Effect.PersistStatus(RECORDING))` at Recording-Start checkpoint (§6.2). */
fun transitionRecording(sessionId: String) {
    sessionDao.updateStatus(sessionId, SessionStatus.RECORDING.name)
}

/** Sets status = RECORDED + persists the final audio file path. Called from `RecordingModule.runEffect(Effect.PersistRecorded)` at Recording-Stop (§6.2). */
fun transitionRecorded(sessionId: String, audioFilePath: String) {
    sessionDao.updateStatus(sessionId, SessionStatus.RECORDED.name)
    sessionDao.updateAudioFilePath(sessionId, audioFilePath)
}

/** Sets status = TRANSCRIBING. Called from `PipelineModule.runEffect(Effect.PersistStatus(TRANSCRIBING))` at Pipeline-Start (§6.2). */
fun transitionTranscribing(sessionId: String) {
    sessionDao.updateStatus(sessionId, SessionStatus.TRANSCRIBING.name)
}

/** Sets `inserted_at` to the given timestamp. Called from `PipelineModule.runEffect(Effect.MarkInserted)` at Insertion-Done (§6.2). */
fun markInserted(sessionId: String, timestamp: Long = System.currentTimeMillis()) {
    sessionDao.markInserted(sessionId, timestamp)
}
```

> **Consistency with `finalize*`:** No `suspend fun` (the existing `finalize*`
> are also plain functions — see `SessionManager.kt:97`); no own
> `db.runInTransaction { ... }` (single update, atomic at the SQLite-statement level
> — the same pattern as `finalizeCancelled` with 2 DAO calls, which does NOT use a
> transaction, because an intermediate error merely leaves `last_error_*` stale
> without state inconsistency). Rationale in `SessionManager.kt:102` (comment "CA-1").

> **Note: New initial-status transitions.** `createSession(initialStatus = …)`
> already exists (see `SessionManager.kt:54`) and accepts any `SessionStatus`
> — no API extension needed. Recording start calls `createSession(initialStatus = RECORDING)`,
> reprocess-from-history (status=`RECORDED`) calls `createSession(initialStatus = RECORDED)`.

**Recovery table (boot status → recovery action):** See §6.3 below — the table is
coupled there with the `recoverFromDb` code.

#### §6.1.1 ActiveJobRegistry strategy after M4

<!-- FIX: Block-3 Detail-Vertiefung – ActiveJobRegistry-Rolle nach Persistierung präzisieren (siehe KG-SST-1 für offene Konsumentenliste) -->

`ActiveJobRegistry` (`app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistry.kt:20-65`) is today the single source of truth for "is something running right now?". With M4, RECORDING/TRANSCRIBING additionally move into the DB — this raises the question: does the registry stay, or does it go?

**Decision: `ActiveJobRegistry` stays.** Rationale:

1. **Performance.** The registry exposes `StateFlow<Map<String, JobState>>` (today observed by `HistoryAdapter.applyStatusBadge` l. 122 — spinner overlay). A DB-polling solution per frame is not realistic.
2. **Job-state detail.** The registry holds `JobState.Running(currentStep, totalSteps, …)` — the DB only holds the phase marker (`status`). Step granularity should NOT go into the DB (it would break the §6.1 "persistence granularity" statement).
3. **Single-job lock.** The registry serializes via `@Synchronized register` — a second pipeline starting in parallel would get `false` back. This logic stays process-local.

**What changes:**

| Aspect | Today (M3) | After M4 |
|---|---|---|
| Source for "is something running right now?" in HistoryActivity | `ActiveJobRegistry.isActive(sessionId)` | unchanged (the registry stays the SSoT for runtime detail). |
| Source after process death | empty (the registry is process-local) | DB: `status IN (RECORDING, TRANSCRIBING)` triggers the recovery path (§6.3). |
| Consistency cache ↔ DB | n/a (the registry and DB are orthogonal) | Both writes happen in the **same reducer hook** (see §6.2). **Order: DB first, then cache** (reversed via KG-SST-5 RESOLVED). If the DAO call fails: neither the cache nor the DB is updated — the pipeline keeps running functionally, the reducer tick retries. If the cache update fails (extremely unlikely, since `ActiveJobRegistry.update` is synchronous on an in-memory map): the DB is consistent, the cache shows a stale step counter — the UI annotation is stale for one tick, fixes itself at the next update. A process crash in between: the DB is consistent, the cache is re-initialized at process restart (process-local). |

**Consumers of `ActiveJobRegistry` today (complete list, grep-verified 2026-05-11):**

| # | File : line | API call | What it does | M4 strategy (cache vs. DB) |
|---|---|---|---|---|
| 1 | `core/JobExecutor.kt:96` | `ActiveJobRegistry.register(sessionId, initial)` | **Producer** — single-job-lock + writes the initial `JobState.Running`. Returns `false` if another job is running. | Stays cache (the single-job-lock pattern is process-local; the DB would have no added value) |
| 2 | `core/JobExecutor.kt:164` | `ActiveJobRegistry.unregister(sessionId)` | **Producer** — finally block: cleanup on pipeline end (regardless of completed/cancelled/failed) | Stays cache |
| 3 | `history/HistoryAdapter.java:122` | `ActiveJobRegistry.INSTANCE.isActive(session.getId())` | **Consumer** — spinner overlay in the history list (runtime overlay over the persisted status) | Stays cache. Rationale: one lookup per RecyclerView bind; a DB read would mean N+1 on the UI thread. |
| 4 | `history/HistoryActivity.java:148` (+`:24,25` imports) | `ActiveJobRegistryObserver.observe(this, snapshot -> refreshData())` | **Consumer** — lifecycle-scoped reactive refresh of the whole list when jobs start/stop | Stays cache. A state read from `DictateUiState` would work, but a StateFlow observer + `refreshData()` is exactly the same mechanic with fewer hops. |
| 5 | `history/HistoryDetailActivity.java:208` (+`:32,33` imports) | `ActiveJobRegistryObserver.observe(this, snapshot -> { if (sessionId != null) loadSession(); })` | **Consumer** — reactive detail refresh: badge + disable-reprocess-button updates | Stays cache (same reason as #4) |
| 6 | `history/HistoryDetailActivity.java:285` | `boolean jobActive = ActiveJobRegistry.INSTANCE.isActive(sessionId)` | **Consumer** — `canReprocess` precomputation in the `buildRecordingPipeline()` path: as long as a job is active, no reprocess button | Stays cache (read in the UI-build path) |
| 7 | `history/HistoryDetailActivity.java:454` | `ActiveJobRegistry.INSTANCE.isAnyActive()` | **Consumer** — `startHistoryReprocess()`: the reprocess click is discarded if a job is already running (toast "job already active") | Stays cache (click handler — synchronous read, no DB hop justifiable) |
| 8 | `core/DictateInputMethodService.java:2361` | `ActiveJobRegistry.INSTANCE.isAnyActive()` | **Consumer** — `startResumeJob()`: the resend click discards if a job is running | Stays cache (same reason as #7) |
| 9 | `core/DictateInputMethodService.java:2594` | `ActiveJobRegistry.INSTANCE.isActive(activeSessionId)` | **Consumer** — cancel path: decides whether `JobExecutor.cancel()` (registry-known) or legacy `pipelineOrchestrator.cancel()` is called | Stays cache (cancel-routing logic, process-local) |
| 10 | `core/ActiveJobRegistryObserver.kt:23-37` | `ActiveJobRegistry.state.collect { snapshot -> … }` (l. 37) | **Bridge** — a Java-friendly wrapper for activity-lifecycle-scoped observation of the `StateFlow`. Bridge code only; the actual consumers are #4 and #5. | Stays — perspectively replaced by a state read from `DictateUiState`, but not in Block 3 (would force an activity refactor). |
| 11 | `database/entity/SessionStatus.kt:6` (KDoc) | n/a — doc anchor | explains why `RECORDING/TRANSCRIBING` are NOT in the DB today | Becomes obsolete with M4 — adjust the KDoc (no logic touch) |
| 12 | `core/JobExecutor.kt:21,294` and `core/PipelineOrchestrator.kt:124,204,845` (KDoc/inline comments) | n/a — doc anchor | explain the pre-allocation relationship registry ↔ orchestrator | unchanged |
| 13 | `preferences/versioned/VersionedPluginRegistry.kt:70` (KDoc) | n/a — doc anchor | only a reference to the `resetRegistry()` pattern (test-helper convention) | unchanged |

**Summary:**

- **2 producer sites** (`JobExecutor.kt:96, :164`) — stay unchanged. Producer sites are NOT **extended** in M4 with `SessionDao.updateStatus` calls — the DB write for RECORDING/TRANSCRIBING moves into the `DictateOrchestrator` reducer hook (§6.2), not into the `JobExecutor`. Rationale: `JobExecutor` only knows `JobRequest`/`JobState`, not `SessionStatus`; coupling it to `SessionManager` would be a LISKOV violation + test overhead.
- **7 consumer sites** (#3-#9) — stay unchanged. All read the registry as a process-local cache.
- **1 bridge** (#10) — stays.
- **3 doc anchors** (#11-#13) — KDoc update only for `SessionStatus.kt:6` (#11).

No forgotten consumer in `rewording/`, `widget/`, `keyboard/`, `settings/`, `onboarding/` (grep on the entire `app/src/main/java/` yielded only these sites).

**Refactor decision:** **No refactor on `ActiveJobRegistry` itself.** The double truth (cache + DB) is accepted and drift-free through §6.2 + KG-SST-5 (DB-first, then cache).

**Persistence contract (cache ↔ DB) — see KG-SST-5 (RESOLVED):**

> **DB first, then cache.** In the checkpoint hook (§6.2) the respective module effect handler (`RecordingModule.runEffect(Effect.PersistStatus)` or `PipelineModule.runEffect(...)`) writes the DB first (`SessionDao.updateStatus(TRANSCRIBING)` / etc.), then updates the registry (`ActiveJobRegistry.update(sessionId, newState)`). <!-- FIX: Phase-B S-1 (2026-05-13) – PipelineStateManager → Modul-EffectHandler --> On a crash between the DB write and the cache write: the DB is consistent, the cache may contain a stale entry — that is initialized empty anyway at the next app start (the registry is process-local, no long-term drift possible). The upper line in the "what changes" table ("first registry, then DAO call") has been **reversed** by KG-SST-5 (RESOLVED 2026-05-11) — see §6.2 persistence contract (R.17 extended) and the KG-SST-5 marker below in §11.7.0.

**Note for the Block-3 implementer:**

- Adjust the `SessionStatus.kt:6` KDoc (e.g. "RECORDING/TRANSCRIBING now live in DB + registry; the registry stays as a performance cache + single-job lock").
- Do **not** touch `JobExecutor.start()` and `JobExecutor.finally`.
- Build the DB-status update for RECORDING/TRANSCRIBING into `DictateOrchestrator.Effect.Persist*`, not directly into `JobExecutor`.

<!-- KNOWLEDGE-GAP: KG-SST-1 – Vollständige ActiveJobRegistry-Konsumentenliste [RESOLVED 2026-05-11] -->
> **✅ Knowledge gap (KG-SST-1): Complete `ActiveJobRegistry` consumer list — RESOLVED 2026-05-11**
>
> - **What we knew:** `HistoryAdapter.java:122` and the single-job-lock pattern in the IME service use the registry. The `register/update/unregister` calls presumably come from `JobExecutor.kt` — not verified via `grep`.
> - **What we did not know:** Are there further consumers in `core/`, `rewording/`, `widget/`?
> - **Resolution:** `grep -rn "ActiveJobRegistry\|activeJobRegistry" app/src/main/java/` (29 hits, of which 13 unique sites — see the table above). Finding: **9 logic sites** (2 producers in `JobExecutor.kt`, 7 consumers in `HistoryAdapter`/`HistoryActivity`/`HistoryDetailActivity`/`DictateInputMethodService`), **1 bridge** (`ActiveJobRegistryObserver.kt`), **3 doc anchors** (`SessionStatus.kt:6`, `JobExecutor.kt:21,294`+`PipelineOrchestrator.kt:124,204,845`, `VersionedPluginRegistry.kt:70`). **No forgotten consumer** in `rewording/`, `widget/`, `keyboard/`, `settings/`, `onboarding/`. Decision: all 9 logic sites stay cache reads — no migration to `DictateUiState.pendingSessions[].status` in Block 3. Rationale: the use cases are `isActive`/`isAnyActive` (lock check + UI overlay) — a DB read would give no added value, but would cost N+1 on RecyclerView bind.
> - **Incorporation:** §6.1.1 consumer table (above) replaces the short bullet list. The persistence-contract line in the "what changes" table was reversed (DB-first via KG-SST-5).

#### §6.1.2 Schema version + addMigrations — verified state

<!-- FIX: Block-3 Detail-Vertiefung – konkrete Versionsnummer (heute v3) + Wiring-Anchor (DictateDatabase.kt:38,73) verifiziert -->

- **Current version:** `DictateDatabase.kt:38` — `version = 3` (verified via `Read`).
- **Current addMigrations:** `DictateDatabase.kt:73` — `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`.
- **M4 diff:** `version = 4` + `.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)`.
- **Migration class:** `androidx.room.migration.Migration` (see `MigrationTo3.kt:3` for the import anchor).
- **`exportSchema = true`** is already active (`DictateDatabase.kt:39`) — the schema JSON `app/schemas/.../DictateDatabase/4.json` is generated by the Room compiler at build time and can be verified in the PR review (lesson learned from MigrationTo3, see comment l. 38-42 there).

#### §6.1.3 Consumers of the `SessionStatus` enum — full update audit

<!-- FIX: Block-3 Detail-Vertiefung – when-Konsumenten an 4 Stati gegen Erweiterung auf 6 Stati abgesichert (siehe KG-SST-4 für Lint-Regel-Followup) -->

Today's `SessionStatus` enum has 4 values (`RECORDED, COMPLETED, FAILED, CANCELLED`). With M4, `RECORDING` + `TRANSCRIBING` are added. Every exhaustive `when`/`switch` on `SessionStatus` must be extended by the new branches — otherwise a compile error (Kotlin: `'when' expression must be exhaustive`) or a silent default case (Java: no compile error, but a lint/missing-case warning).

**Complete consumer list (grep-verified):**

| File | Position | Language | Today's behavior | M4 update |
|---|---|---|---|---|
| `core/ResendStatusDispatcher.kt:57-71` | `when (status)` | Kotlin (exhaustive) | 4 branches, no else | + `RECORDING` → `NoOp` (should never become UI-visible — defensive), + `TRANSCRIBING` → `NoOp` (the user should not start the pipeline twice — the single-job lock takes effect anyway, but we want to ignore a click on the resend button during a running pipeline) |
| `history/HistoryAdapter.java:130-159` | `try { SessionStatus.valueOf(...) } catch (IllegalArgumentException) { RECORDED }` + `switch (status)` | Java (no exhaustiveness check); **the existing try/catch wrapper l. 131–135 catches unknown strings as a `RECORDED` fallback** (downgrade/restore compatibility) — the `switch` thus always sees a valid enum value, and the `default:` branch is today **unreachable** | + `case RECORDING:` → spinner + label "Recording" (only occurs in the OOM-death window — before `recoverFromDb` runs); + `case TRANSCRIBING:` → spinner + label "Transcribing" (same window). Both branches **can** strictly speaking never become visible (recovery promotes), but maintain defensively. <!-- FIX: Phase-B S-2 (2026-05-13) – Wrapper-Doppel-Sicherung dokumentiert: try/catch fängt unbekannte Strings, default-case fängt "neue Enum-Werte ohne switch-Update". Beide Schichten bleiben — siehe Begründung unter dem Snippet. --> |
| `history/HistoryDetailActivity.java:287-299` | `try { SessionStatus.valueOf(...) } catch (IllegalArgumentException) { RECORDED }` + whitelist `canReprocess = (status IN {RECORDED, FAILED, CANCELLED, COMPLETED})` | Java | 4 statuses are checked in a **whitelist** — new statuses (RECORDING, TRANSCRIBING) land automatically in the `canReprocess = false` path **without** a code change. The try/catch wrapper l. 288–292 additionally catches unknown status strings as a `RECORDED` fallback. | **No code change needed** — the whitelist logic is already defensive against new status values. Adding `status != RECORDING && status != TRANSCRIBING` would be **redundant** and code noise. <!-- FIX: Phase-B S-2 (2026-05-13) – frühere Anweisung "explizit ausschließen" war redundant gegenüber der bestehenden Whitelist-Logik. --> |
| `history/HistoryDetailActivity.java:590` | constant list `SessionStatus.RECORDED` (resume button) | Java | hard-coded to `RECORDED` | unchanged (the resume button is only for `RECORDED` sessions). |
| `ai/AIProviderException.kt` (`ErrorType.UNKNOWN` mapping) | n/a | n/a | n/a | unchanged — no direct `SessionStatus` touchpoint. |

**Concrete patch — `HistoryAdapter.java:136-159` (in Java, additive):**

```java
switch (status) {
    case COMPLETED:
        holder.statusIcon.setVisibility(View.GONE);
        holder.statusTv.setVisibility(View.GONE);
        break;
    // NEW (M4): defensive UI für den OOM-Death-Window-Fall.
    // Sollte unter normalem Lifecycle NIE sichtbar werden, weil
    // PipelineRecovery.recover() (§4.6 + §6.3) RECORDING→FAILED und
    // TRANSCRIBING→RECORDED downgraded, BEVOR HistoryActivity die Liste lädt.
    case RECORDING:
        holder.statusIcon.setVisibility(View.VISIBLE);
        holder.statusIcon.setImageResource(R.drawable.ic_baseline_sync_24);
        holder.statusTv.setVisibility(View.VISIBLE);
        holder.statusTv.setText(R.string.dictate_status_recording);  // NEU im strings.xml
        break;
    case TRANSCRIBING:
        holder.statusIcon.setVisibility(View.VISIBLE);
        holder.statusIcon.setImageResource(R.drawable.ic_baseline_sync_24);
        holder.statusTv.setVisibility(View.VISIBLE);
        holder.statusTv.setText(R.string.dictate_status_transcribing);  // NEU im strings.xml
        break;
    case RECORDED:
        // ... unverändert ...
    case FAILED:
        // ... unverändert ...
    case CANCELLED:
        // ... unverändert ...
    default:
        // KG-SST-4 (RESOLVED): defensiver Fallback. Java's switch ist nicht
        // exhaustive — wenn jemand später eine neue SessionStatus-Variante
        // hinzufügt ohne diesen switch zu erweitern, würde sonst stillschweigend
        // ein leeres Badge angezeigt. Log.wtf macht es im Crashlytics sichtbar,
        // GONE als Default-UI vermeidet einen RecyclerView-Crash.
        android.util.Log.wtf(
            "HistoryAdapter",
            "Unknown SessionStatus in applyStatusBadge: " + status
        );
        holder.statusIcon.setVisibility(View.GONE);
        holder.statusTv.setVisibility(View.GONE);
        break;
}
```

> **Lint backup (KG-SST-4 RESOLVED):** Additionally `app/build.gradle`
> activates the Android-Lint rule `EnumSwitch` as an error (today only a warning), so that a
> forgotten `case` becomes visible at build time — not only at runtime.
> See the KG-SST-4 marker below in §11.7.0 for the Gradle snippet.

<!-- FIX: Phase-B S-2 (2026-05-13) – Doppel-Sicherung try/catch + default: explizit dokumentiert. -->
> **Double protection try/catch + `default:` — no redundancy, but two failure modes:**
>
> The existing `try { SessionStatus.valueOf(session.getStatus()) } catch (IllegalArgumentException e) { status = SessionStatus.RECORDED; }` wrapper (HistoryAdapter l. 131–135, **kept**) and the new `default:` branch in the `switch` address **disjoint** failure modes:
>
> | Failure mode | Trigger | Caught by |
> |---|---|---|
> | **DB string unknown** (e.g. downgrade: a v4 app wrote RECORDING/TRANSCRIBING, then back to a v3 app that does not know these enum values) | `SessionStatus.valueOf("RECORDING")` throws `IllegalArgumentException` | **try/catch wrapper** → fallback to `RECORDED` (pending badge, no UI break) |
> | **Enum extended, switch not** (e.g. v5 adds `PROCESSING_REWORDING`, someone forgets the `case`) | `SessionStatus.valueOf("PROCESSING_REWORDING")` returns a valid enum value, but no `case` matches | **`default:` branch** → `Log.wtf` + `GONE` (no UI crash, Crashlytics signal) |
>
> Both layers are independent: the try/catch catches String→enum-boundary errors, the default: catches enum→switch drift. Removing the try/catch would break downgrade compatibility (a user DB with a RECORDING row crashes at the first history scroll on an older app). Omitting the default: would create silent-empty badges on future enum extensions.

<!-- FIX: Phase-B S-3 (2026-05-13) – Naming-Kollisions-Warnung: zwei `ResendAction` koexistieren. -->
> **Naming collision (deliberately accepted, documented):** There are two independent
> sealed classes with the name `ResendAction` in the project:
> 1. `net.devemperor.dictate.core.ResendAction` (today's code, `ResendStatusDispatcher.kt`):
>    the internal decision type of the status dispatcher — variants `Insert(output, sessionId)`,
>    `Resume(sessionId)`, `NoOp`. **Kept** as an implementation detail of the
>    dispatcher; no refactor touchpoints.
> 2. `net.devemperor.dictate.state.Action.ResendAction` (new code, Spec 2 §3.3):
>    orchestrator action — variants `ResendLastAudio`, `ResendLastAudioLong`,
>    `ResendCooldownExpired`, `MarkLastAudio(exists)`. **No** `NoOp` (R.3 nullable
>    resolver idiom).
>
> The two types live in **different packages** (`core` vs. `state`); the
> Kotlin compiler rejects cross-use as a type mismatch. In the Block-3 implementation
> the `ResendStatusDispatcher` patch below is **explicitly** referring to the `core.ResendAction`
> variant — no action forwarding to `Action.ResendAction.*`. A future
> refactor iteration could integrate the dispatcher into a `ResendModule.runEffect` path
> (then `core.ResendAction` would go away) — currently out-of-scope.

**Concrete patch — `ResendStatusDispatcher.kt:57-71`** (references `core.ResendAction`, not `Action.ResendAction`):

```kotlin
return when (status) {
    SessionStatus.COMPLETED ->
        if (!output.isNullOrEmpty()) ResendAction.Insert(output, sessionId)
        else ResendAction.NoOp
    SessionStatus.CANCELLED ->
        if (!output.isNullOrEmpty()) ResendAction.Insert(output, sessionId)
        else ResendAction.Resume(sessionId)
    SessionStatus.RECORDED ->
        ResendAction.Resume(sessionId)
    SessionStatus.FAILED ->
        ResendAction.NoOp
    // NEW (M4) — Pipeline läuft bereits; Resend-Click ist No-Op (Single-Job-Lock greift sowieso).
    SessionStatus.RECORDING,
    SessionStatus.TRANSCRIBING ->
        ResendAction.NoOp
}
```

**New string resources** (`app/src/main/res/values/strings.xml`, to add — defensive, in case the branch ever becomes visible):

```xml
<string name="dictate_status_recording">Wird aufgenommen…</string>
<string name="dictate_status_transcribing">Wird transkribiert…</string>
```

**Wiring** (`DictateDatabase.kt:38` and `:73`):

```kotlin
// alt:
@Database(... version = 3, exportSchema = true)
.addMigrations(MIGRATION_1_2, MIGRATION_2_3)

// neu:
@Database(... version = 4, exportSchema = true)
.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
```

Also in `SessionEntity.kt` (line 51 added):

```kotlin
@ColumnInfo(name = "inserted_at") val insertedAt: Long? = null
```

And in `SessionDao.kt` (added before line 96):

```kotlin
<!-- FIX: Phase-B S-1 (2026-05-13) – DAO-Doc-Comments auf F-11-Call-Sites umgestellt. -->
/** Atomic INSERTED-Transition, gerufen von `PipelineModule.runEffect(Effect.ConfirmInsertion)`. */
@Query("UPDATE sessions SET inserted_at = :timestamp WHERE id = :id")
fun markInserted(id: String, timestamp: Long)

/** Pending-Sessions-Query für `PipelineRecovery.recover()` (§4.6). */
@Query(
    """
    SELECT * FROM sessions
    WHERE status = 'COMPLETED'
      AND final_output_text IS NOT NULL
      AND inserted_at IS NULL
    ORDER BY created_at DESC
    """
)
fun findPendingInsertion(): List<SessionEntity>

/** Cleanup-Policy für stopSelf-Idle. */
@Query("DELETE FROM sessions WHERE inserted_at IS NOT NULL AND inserted_at < :cutoff")
fun deleteInsertedOlderThan(cutoff: Long): Int
```

### §6.2 Checkpoint hooks

<!-- FIX: Issue PENDING-2 / Block-3 Resolved – Tabelle gegen tatsächliche Enum-Werte ausgerichtet (kein TRANSCRIBED/INSERTED-Pseudostatus mehr) -->

In the `DictateOrchestrator` (Composition Root) every state transition is accompanied by a DB update.
The status values below match the enum definition in `SessionStatus.kt` exactly (see §6.1) — there
is **no** pseudo-status `TRANSCRIBED` (that would be `COMPLETED` with `final_output_text != NULL`)
or `INSERTED` (that is marked via `inserted_at IS NOT NULL`):

| State transition | DB write | SessionManager method |
|------------------|---------------|------------------------|
| Recording start | `INSERT sessions (... status='RECORDING' ...)` | `createSession(initialStatus = RECORDING)` |
| Recording stop | `UPDATE sessions SET status='RECORDED', audio_file_path=? WHERE id=?` | `transitionRecorded(id, path)` (new) |
| Pipeline start | `UPDATE sessions SET status='TRANSCRIBING' WHERE id=?` | `transitionTranscribing(id)` (new) |
| Pipeline done | `UPDATE sessions SET status='COMPLETED', final_output_text=? WHERE id=?` | `finalizeCompleted(id)` (+ `updateFinalOutputText`) |
| Insertion done | `UPDATE sessions SET inserted_at=now WHERE id=?` | `markInserted(id, ts)` (new, §6.1) |
| Cancel | `UPDATE sessions SET status='CANCELLED' WHERE id=?` | `finalizeCancelled(id)` |
| Error | `UPDATE sessions SET status='FAILED', last_error_type=?, last_error_message=? WHERE id=?` | `finalizeFailed(id, type, msg)` |

<!-- FIX: Issue 2.1.21 / R.17 – Idempotenz-/Reihenfolge-/Failure-Vertrag explizit -->
<!-- FIX: Phase-C C-2 (2026-05-14) – Doppel-Reihenfolge-Klausel "State-First" vs. "DB → Cache"
     explizit disambiguiert: zwei verschiedene "Caches" auf zwei verschiedenen Layern. Beide
     Klauseln koexistieren konfliktfrei, aber der Lese-Anchor ohne Header war verwirrend. -->
**Persistence contract (R.17):**

> **Two ordering clauses, two layers — no contradiction:**
> - **State-First (bullet point 2)** refers to the relationship `DictateUiState` (in-process
>   SSoT, immutable) vs. `SessionEntity` (DB). The reducer mutates the state first, then it emits
>   an `Effect.Persist*` that writes the DB.
> - **DB-first (bullet point 5)** refers to the relationship `SessionEntity` (DB) vs.
>   `ActiveJobRegistry` (in-process performance cache + single-job lock, a separate container from
>   `DictateUiState`). Within the `Effect.Persist*` handler the DB is written first,
>   then the `ActiveJobRegistry` is updated.
>
> The overall order is thus: **State → DB → ActiveJobRegistry**. This clarification is further elaborated in the §6.1.1
> consumer table and in the pipeline-start sequence below.

- **Idempotency:** All DB writes go through `@Insert(onConflict = REPLACE)` or idempotent
  `UPDATE … WHERE id = ?` statements. Replay after a view recreate or a cascade loop is safe
  (no double insert, no double status switch).
- **State-first order (state ↔ DB):** The reducer mutates `DictateUiState` first (the source of
  truth); the `Effect.Persist*` writes the DB asynchronously. A DB failure does NOT make the state
  inconsistent — the state is already persisted via StateFlow, the DB is a mirror.
- **Failure channel:** If a DB operation throws, the orchestrator catches it (Issue 2.1.3 Option D)
  and re-dispatches `Action.PipelineAction.PersistenceError(sessionId, reason)`. The
  PipelineModule reducer marks the session in `pendingSessions` as `status=FAILED` and
  sets a notification (backoff-free, no retry storm).
- **Cleanup cutoff:** `now − 7d − 1h` (safety buffer for inflight operations); central in
  `Pref.SessionCleanupGracePeriodMs`.
- **DB → cache order (DB ↔ ActiveJobRegistry, KG-SST-5, RESOLVED 2026-05-11):** In the
  reducer hook for RECORDING/TRANSCRIBING the following applies **within** the `Effect.Persist*` handler:
  `SessionDao.updateStatus(...)` (DB) is called **before** `ActiveJobRegistry.update(...)`
  (performance cache). On a DAO failure the registry call is skipped
  (no drift, the pipeline reducer catches it as `Action.PipelineAction.PersistenceError`,
  see the failure channel above). On a process crash between the DB write and the cache write: the DB
  is consistent, the registry is initialized empty at app start anyway (`ActiveJobRegistry`
  is a process-local Kotlin `object`, no long-term drift possible). Producer sites
  `JobExecutor.kt:96/:164` (`register/unregister`) stay unchanged — they
  are lock producers (single-job lock), not status producers.

<!-- FIX: Phase-B S-2 (2026-05-13) – Verzahnung JobExecutor.register vs. Effect.PersistStatus(TRANSCRIBING) — die DB-first-Regel gilt für DEN Reducer-Hook, nicht für JobExecutor.register. -->
> **Important interlocking: `JobExecutor.register` vs. `Effect.PersistStatus(TRANSCRIBING)`** — the `DB-first` rule concerns **exclusively** the state-status-write path (reducer hook → `Effect.PersistStatus` → `SessionDao.updateStatus(TRANSCRIBING)` → `ActiveJobRegistry.update`). It does **not** concern the parallel `JobExecutor.start(...)` path that calls `ActiveJobRegistry.register(...)` **before** the pipeline run (lock claim, single-job constraint). Concrete sequence at pipeline start (the user dispatches `Action.PipelineAction.Submit`):
>
> 1. `PipelineModule.reduce` → state from `Idle → Running(...)` (immutable copy, a new snapshot in `_state`).
> 2. `PipelineModule.runEffect(Effect.PersistStatus(sessionId, TRANSCRIBING))` (asynchronously, in the services.scope):
>    - **(a)** `sessionDao.updateStatus(sessionId, "TRANSCRIBING")` — DB write (status producer)
>    - **(b)** `ActiveJobRegistry.update(sessionId, JobState.Running(...))` — cache update (status producer)
> 3. `PipelineModule.runEffect(Effect.StartPipeline(jobRequest))` → `jobExecutor.start(jobRequest)`:
>    - **(c)** `ActiveJobRegistry.register(sessionId, initial)` — lock claim (producer site `JobExecutor.kt:96`, **not** a status producer)
>    - **(d)** the pipeline run starts on the executor thread.
>
> Order: 1 → 2(a) → 2(b) → 3(c) → 3(d). If 2(a) fails, 2(b) is skipped, 3 is not started → the pipeline does not run, the `PersistenceError` action is dispatched (see the failure channel above). If 3(c) fails (a parallel job already active): `JobExecutor.start` returns `false`, the pipeline does not run — but the state is already `Running` (a race condition through the state-first order, R.17 — the reducer mutates the state first). Mitigation: the `JobExecutor.start` failure path dispatches `Action.PipelineAction.RejectedJobAlreadyActive(sessionId)`, which rolls the state back to `Idle`. This path is to be implemented in Block 4 (RecordingModule) — Block 3 brings only the DB-write path (2a).
>
> Implementer anchor: do **NOT** rewrite `JobExecutor.start` so that it first writes `SessionDao.updateStatus(TRANSCRIBING)` — that would map the DB-write responsibility out of the module reducer hook into the JobExecutor and break the SRP of the `JobExecutor` class (a lock producer becomes a status producer). Instead: the status write **stays in the module effect** (step 2a above).

### §6.3 Recovery read

At the service `onCreate` (e.g. after an OOM death):

<!-- FIX: Issue 2.1.20 / R.16 – Recovery deckt RECORDING/TRANSCRIBING ab; Merge statt Override -->
<!-- FIX: Issue PENDING-2 / Block-3 Resolved – Recovery-Tabelle + Code an konkrete Enum-Werte gebunden -->

**Recovery table (boot status → recovery action):**

| Boot status | File check | Recovery action | User impact |
|---|---|---|---|
| `RECORDING` | the file may be incomplete/corrupt → do not trust it for recovery | promote → `FAILED` with `lastErrorType=UNKNOWN, lastErrorMessage="recording-interrupted-by-process-death"`; opportunistically delete the audio file if present | The session appears as FAILED in the history (error icon). The user sees a "recording lost" hint; no reprocess possible. |
| `TRANSCRIBING` | the audio file must exist (the recording was finished, otherwise the status would have stayed `RECORDING`) | downgrade → `RECORDED`. **No auto-resume** (D4 / OPEN-4). The session moves into `pendingSessions` and the user clicks resend/restart to manually restart the pipeline | The session shows a pending icon in the history; the resend button in the detail is enabled. Whisper is called again on the manual click (accepted double cost; D4 justifies it) |
| `TRANSCRIBING` | the audio file is missing (very rare — presumably a storage cleanup between recording stop and pipeline start) | promote → `FAILED` with `lastErrorType=UNKNOWN, lastErrorMessage="audio file vanished before transcription"` | like the `RECORDING` recovery above |
| `RECORDED` | the file exists | load as `pendingSessions` (existing path, §11.6.2) | pending icon, resend button active |
| `RECORDED` | the file is missing (ghost) | promote → `FAILED` with `lastErrorType=UNKNOWN, lastErrorMessage="audio file vanished"` (existing path, §11.6.2) | like above |
| `COMPLETED` with `inserted_at IS NULL` | n/a (no audio needed) | load as `pendingSessions` (existing path, §11.6.2 — the result text is already in the DB) | pending insertion; the user clicks insert |
| `COMPLETED` with `inserted_at IS NOT NULL` | n/a | skip (the session is completed, no recovery handling) | — |
| `FAILED`, `CANCELLED` | n/a | skip | — |

> **Order file-op vs. DB-op (rationale):** On RECORDING→FAILED we promote **first** the DB status (terminal), **then** we opportunistically delete the audio file. Background: if the file delete fails (permission race, OS storage wipe, file lock by another process), the session still stays as FAILED in the DB — the file is retried at the next service idle-stop via the cleanup policy (`deleteInsertedOlderThan` does not take effect here, because FAILED does not set an `inserted_at`; see `KG-SST-2` below) OR at the next OOM-death recovery (`clearAudioFilePath` is idempotent). The reverse order (delete before status promote) would leave a ghost on a crash-mid-recovery: the file gone, the status still RECORDING → the next boot hits `audioFilePath != null && !File.exists()` and treats it as "vanished". Functionally correct, but redundant.

<!-- FIX: Phase-B S-2 (2026-05-13) – Lücke: RECORDING-Sessions haben in der DB-Row typischerweise `audio_file_path = NULL` (Path wird erst beim Recording-Stop via `transitionRecorded` geschrieben). Das partial-written File lebt physisch in `cacheDir/audio.m4a` (heute) bzw. `filesDir/recordings/{sessionId}.m4a` (nach Block 4 AudioFileFactory). Die `clearAudioFilePath`-Logik im RECORDING-Recovery-Pfad ist daher nur defensive — der File-Leak wird nicht von diesem Pfad behoben. -->
> **RECORDING recovery: the partial-written audio file on disk** — a RECORDING DB row usually has `audio_file_path = NULL`, because `transitionRecorded(sessionId, audioFilePath)` (see §6.1 reference snippet `SessionManager.transitionRecorded`) only writes the path into the row at the **recording stop**. If the process dies during RECORDING, the partial-written file still lives physically in `cacheDir/audio.m4a` (today) or `filesDir/recordings/{sessionId}.m4a` (after Block 4 AudioFileFactory, see §4.11).
>
> The file delete in the RECORDING recovery path above (`row.audioFilePath?.let { File(it) }?.takeIf { exists() }?.delete()`) is therefore **defensive for the special case** that the file path was already in the row (e.g. after Block 4: AudioFileFactory writes the path on allocate, not only at stop). For the Phase-1 code (Block 3 before Block 4) the partial-written file is disposed of by two orthogonal cleanup paths:
>
> 1. **`AudioFileFactory.cleanupOrphans(referenced)`** (see §7.3 snippet l. 3215–3225, runs in the service `onCreate` in parallel with the recovery): scans `filesDir/recordings/*` and deletes files that are referenced in **no** sessions row. A RECORDING session with `audio_file_path = NULL` thus leaves a path that is not referenced → it gets deleted.
> 2. **`cacheDir` OS cleanup** for the legacy layout (`cacheDir/audio.m4a`): Android disposes of cacheDir files opportunistically at low storage. After Block 4 `cacheDir` is no longer the recording target.
>
> Accepted: the Block-3 implementation runs with today's `cacheDir` layout — the orphan-cleanup path #1 becomes active in Block 4 with the `AudioFileFactory`. Until then the OS cleanup is sufficient.

> **Atomicity:** The `updateStatus + updateError + clearAudioFilePath` sequence per row does **not** run in a transaction (analogous to `SessionManager.finalizeFailed`, which also does 2 sequential DAO calls without `runInTransaction` — see `SessionManager.kt:108-111`). Rationale: between the 3 statements a stale state is conceivable (e.g. only status=FAILED set, last_error_* still empty), but problematic for no consumer — HistoryAdapter shows FAILED regardless of the `last_error_*` field. A `runInTransaction` clamp would be overkill and would block the IO thread longer.

```kotlin
suspend fun recoverFromDb() = withContext(Dispatchers.IO) {
    // Lade alle Non-Terminal-Sessions inkl. ungeleerter COMPLETED-Insertions.
    val candidates = db.sessionDao().getSessionsByStatuses(
        listOf(
            SessionStatus.RECORDING.name,
            SessionStatus.TRANSCRIBING.name,
            SessionStatus.RECORDED.name,
            SessionStatus.COMPLETED.name,
        )
    )

    // 1. RECORDING — Recording-Phase überlebt OOM-Death nicht. Audio-File ist
    //    u.U. mid-stream abgeschnitten und nicht zuverlässig dekodierbar.
    //    → unmittelbar FAILED; Audio-File löschen, falls vorhanden.
    //    Reihenfolge: Status-Promote ZUERST (terminal), File-Delete opportunistic
    //    danach. Begründung: siehe Vor-Block.
    candidates.filter { it.statusEnum == SessionStatus.RECORDING }.forEach { row ->
        db.sessionDao().updateStatus(row.id, SessionStatus.FAILED.name)
        db.sessionDao().updateError(
            row.id,
            AIProviderException.ErrorType.UNKNOWN.name,
            "recording-interrupted-by-process-death"
        )
        // File-Delete: opportunistic — Failure (Permission, Race, File-Lock)
        // wird geloggt aber nicht propagiert. `clearAudioFilePath` läuft IMMER,
        // damit der nächste Recovery-Lauf die Session nicht erneut anfasst.
        row.audioFilePath
            ?.let { File(it) }
            ?.takeIf { it.exists() }
            ?.runCatching { delete() }
            ?.onFailure { Log.w(TAG, "RECORDING-recovery: failed to delete ${row.audioFilePath}", it) }
        db.sessionDao().clearAudioFilePath(row.id)
    }

    // 2. TRANSCRIBING — Audio existiert (Recording war fertig); Pipeline lief
    //    aber nicht zu Ende. Downgrade auf RECORDED, damit der User via
    //    Resend-Button manuell re-submitten kann. KEIN Auto-Resume (D4 / OPEN-4).
    //    Falls die Audio-Datei zwischenzeitlich verschwunden ist (Storage-Cleanup
    //    zwischen Stop und Pipeline-Start), behandeln wir es wie eine Ghost-
    //    Session (FAILED + Hinweis).
    //
    //    WICHTIG bei Downgrade: last_error_* CLEAREN. Eine TRANSCRIBING-Session
    //    kann durch frühere fehlgeschlagene Versuche `last_error_type/message`
    //    bereits gefüllt haben (z.B. Retry nach RATE_LIMITED). Wenn wir auf
    //    RECORDED downgraden, sollen UI-Konsumenten (HistoryDetailActivity)
    //    keinen stale-Error mehr zeigen. `updateError(id, null, null)` ist
    //    derselbe Pfad wie `finalizeCancelled` (siehe SessionManager.kt:104).
    candidates.filter { it.statusEnum == SessionStatus.TRANSCRIBING }.forEach { row ->
        val audioOk = row.audioFilePath?.let { File(it).exists() } == true
        if (audioOk) {
            db.sessionDao().updateStatus(row.id, SessionStatus.RECORDED.name)
            db.sessionDao().updateError(row.id, null, null)   // Stale-Errors aus früheren Versuchen löschen
        } else {
            db.sessionDao().updateStatus(row.id, SessionStatus.FAILED.name)
            db.sessionDao().updateError(
                row.id,
                AIProviderException.ErrorType.UNKNOWN.name,
                "audio file vanished before transcription"
            )
            db.sessionDao().clearAudioFilePath(row.id)
        }
    }

    // 3. Nachladen — nach den Promotes ist der gewünschte Pending-Set:
    //    a) RECORDED mit existierender Datei  → resumable
    //    b) COMPLETED mit final_output_text != NULL UND inserted_at IS NULL → pending insertion
    //
    // FIX: Phase-C C-2 (2026-05-14) – `getSessionsByStatuses(List<String>)` erwartet `.name`-
    // Strings (siehe DAO-Signatur weiter unten, §6.3 + §11.6.2). Frühere Variante übergab
    // `SessionStatus`-Enum-Werte direkt → Kotlin-Type-Mismatch (Compile-Error). Konsistent
    // zum Top-Block oben in derselben Funktion (Z. 3331-3336), der bereits `.name` nutzt.
    val recoveredCandidates =
        db.sessionDao().getSessionsByStatuses(
            listOf(SessionStatus.RECORDED.name, SessionStatus.COMPLETED.name)
        )

    val resumable = recoveredCandidates
        .filter { it.statusEnum == SessionStatus.RECORDED }
        .filter { it.audioFilePath?.let { p -> File(p).exists() } == true }

    val pendingInsertion = recoveredCandidates
        .filter { it.statusEnum == SessionStatus.COMPLETED && it.insertedAt == null && it.finalOutputText != null }

    val recovered = resumable + pendingInsertion

    // MERGE — kein Override. Verhindert Race mit parallelem Recording, das während
    // der Recovery startet (sessionId-tracked, R.8).
    store.update { current ->
        current.copy(pendingSessions = current.pendingSessions.addAll(recovered.map { it.toPendingSession() }))
    }
    // KEIN Auto-Resume — User muss Restart-Button klicken (User-Wahl, D4 / OPEN-4).
}
```

`SessionDao` exposes for this (in addition to the methods that already exist today
`updateStatus`, `updateError`, `clearAudioFilePath` — see `SessionDao.kt:68-85`):

```kotlin
/**
 * Recovery-Bulk-Read: alle Sessions in den angegebenen Stati.
 * Double-Enum: Caller übergibt `SessionStatus.X.name`-Strings (Liste<String>),
 * weil Room keine Custom-TypeConverter-Listen für CHECK-Enums kennt.
 */
@Query("SELECT * FROM sessions WHERE status IN (:statuses)")
fun getSessionsByStatuses(statuses: List<String>): List<SessionEntity>
```

> **Note on the signature:** The call site passes `listOf(SessionStatus.RECORDING.name, ...)`
> (see the code above — we map before the DAO call). Today's `SessionDao` convention uses
> exclusively `String` columns (see `updateStatus(id, status: String)` in
> `SessionDao.kt:69-70`), no TypeConverter for `SessionStatus`.

**What is no longer needed (removed vs. an older spec iteration):**

- ~~`markFailed(id, reason)`~~ — the existing path `updateStatus` + `updateError` covers that;
  an additional single-statement method would only duplicate (a DRY violation).
- ~~`SessionStatus.TRANSCRIBED`~~ — does not exist in the final enum (`COMPLETED` is the
  pipeline-done status).
- ~~`last_error_reason` column~~ — in the schema it is called `last_error_message` (see
  `MigrationTo3.kt:63-69`); `last_error_type` is a double-enum against
  `AIProviderException.ErrorType`.

<!-- FIX: Issue 2.0.10 – Pref-Mirror-Bypass-Block aus recoverFromDb entfernt; Overlay-Position kommt aus PipelinePrefMirror.attach (§4.5), das in DictateOrchestrator.init VOR recovery.recover läuft. Damit gleichzeitig F-10-Mismatch-Pfad eliminiert (flache overlayPositionPortraitX vs. hierarchisch state.overlay.positionPortraitX). -->

> **Rationale (Issue 2.0.10):** The earlier pref-mirror read in `recoverFromDb` was
> a drift artifact from an earlier iteration. `PipelinePrefMirror.attach(store)`
> (see §4.5) is called in `DictateOrchestrator.init` **before** `recovery.recover(store)`
> — the overlay position already lies canonically in the store at the recovery start.
> A double read + double write path is eliminated. At the same time this closes an
> F-10 inconsistency case (the earlier version used the flat fields
> `overlayPositionPortraitX` etc., which F-10 moved into `state.overlay.positionPortraitX`).

#### §6.3.1 Orphan-FAILED audio cleanup (KG-SST-2 RESOLVED)

<!-- FIX: KG-SST-2 [RESOLVED 2026-05-11] – Orphan-Audio-Cleanup-Routine konkretisiert -->

After M4-Block-4 (AudioFileFactory) the recording audio file moves from `cacheDir/audio.m4a`
to `filesDir/recordings/{sessionId}.m4a`. `filesDir` is NOT cleaned by the Android OS.
FAILED/CANCELLED sessions with `audio_file_path != null` thus leak
storage until they are removed via a user delete (detail view).

`deleteInsertedOlderThan(cutoff)` does not take effect here (FAILED rows have
`inserted_at IS NULL`). `DurationHealingJob.heal(...)` only heals DB inconsistencies
(file gone, DB row present), not the reverse case.

**Solution:** A new DAO method + a new service cleanup hook in the
`DictatePipelineService.onTimeout()`/idle-stop slot:

```kotlin
// In SessionDao.kt — NEU (vor Zeile 96):

/**
 * Findet FAILED/CANCELLED-Sessions, deren audio_file_path noch gesetzt ist
 * und die älter als cutoff sind. Reine SELECT-Query; das eigentliche
 * File.delete() macht der Service-Layer (Layer-Trennung).
 *
 * @return Liste von (sessionId, audioFilePath)-Tupeln.
 */
@Query(
    """
    SELECT id, audio_file_path FROM sessions
    WHERE status IN ('FAILED', 'CANCELLED')
      AND audio_file_path IS NOT NULL
      AND created_at < :cutoff
    """
)
fun findOrphanedTerminalAudio(cutoff: Long): List<OrphanedAudioRow>

/** Setzt audio_file_path = NULL nach erfolgreicher File-Op (idempotent). */
@Query("UPDATE sessions SET audio_file_path = NULL WHERE id IN (:ids)")
fun clearAudioFilePathBulk(ids: List<String>)

// Result-DTO (in derselben Datei):
data class OrphanedAudioRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "audio_file_path") val audioFilePath: String,
)
```

```kotlin
// In DictatePipelineService.kt — NEU im Service-Idle-Stop-Slot
// (gleicher Slot wie der bestehende deleteInsertedOlderThan-Call, §6.2 R.17):

/**
 * KG-SST-2: räumt Audio-Files für lange FAILED/CANCELLED-Sessions weg.
 * Best-effort — bei File.delete()-Failure (Permission-Race) bleibt der
 * Eintrag in DB und wird beim nächsten Idle-Stop erneut versucht.
 *
 * Layer-Trennung: DAO liefert Pfade, Service macht File-IO.
 */
suspend fun cleanupOrphanedTerminalAudio() = withContext(Dispatchers.IO) {
    val cutoff = System.currentTimeMillis() - Pref.SessionCleanupGracePeriodMs.get()
    val dao = DictateDatabase.getInstance(this@DictatePipelineService).sessionDao()
    val orphans = dao.findOrphanedTerminalAudio(cutoff)
    val cleared = mutableListOf<String>()
    for (row in orphans) {
        val file = File(row.audioFilePath)
        if (!file.exists() || file.delete()) {
            cleared += row.id
        }
    }
    if (cleared.isNotEmpty()) dao.clearAudioFilePathBulk(cleared)
}
```

**Trigger:** Directly before `stopSelf()` in the service-idle-stop path. Order:

1. `deleteInsertedOlderThan(cutoff)` — existing (§6.2 R.17)
2. `cleanupOrphanedTerminalAudio()` — new (KG-SST-2)
3. `stopSelf()`

**Cutoff:** The same value as `deleteInsertedOlderThan` — `now − 7d − 1h`
(`Pref.SessionCleanupGracePeriodMs`).

<!-- FIX: Phase-B S-7 (2026-05-13) – Concurrency-Vertrag explizit + Trigger-Slot präzise definiert. -->
**Concurrency contract (Phase-B S-7):**

- **Trigger slot:** Exactly between the last `Effect` `runEffect` tick and `stopSelf()`. Concretely
  in the `stopSelfWhenTerminal(state)` callback (§7.3 l. 3625) — the service collects `state` and
  calls the callback on every update; if `state.isAllTerminal()`, `cleanupOrphanedTerminalAudio()` runs
  in a `serviceScope.launch(Dispatchers.IO)` coroutine and IN the `await` block before it `stopSelf()` is
  called. **Important:** `stopSelf()` does NOT block until the cleanup job runs through — Android will
  end the service in case of doubt while the cleanup coroutine is still doing IO. That is accepted:
  `cleanupOrphanedTerminalAudio` is best-effort, an aborted run is retried at the next
  service boot through the `cleanupOrphans` boot hook (a different path! §4.11.5.1 step 8)
  — see the idempotency contract of `findOrphanedTerminalAudio`.
- **Concurrent allocate during cleanup:** Very unlikely — `state.isAllTerminal()` is
  a precondition for the trigger; that means `state.recording is Idle && state.pipeline is Idle`.
  If the user starts a new recording *mid-cleanup*, `audioFileFactory.allocate()` runs in
  a different path (`cacheDir/audio/`, not `filesDir/recordings/` — where `cleanupOrphanedTerminalAudio`
  works). No direct conflict. But: the concurrent `state.collect` cascade prevents
  `stopSelf()` (the Idle→Preparing transition makes `isAllTerminal() = false`), so the cleanup
  job might abort in the middle of the DB read if the service scope stays reactive due to a new recording.
  **Behavior:** `cleanupOrphanedTerminalAudio` runs to the end (no cancel), but
  `stopSelf()` is not called (`isAllTerminal()` is false). At the next real idle-stop
  the cleanup tries again — idempotent.
- **Double-delete race:** If, in parallel with the `cleanupOrphanedTerminalAudio` iteration, a user
  calls "Delete audio" for a FAILED session via `HistoryDetailActivity` (`RecordingRepository.
  deleteBySessionId`), the same path could be deleted twice. `File.delete()` on an
  already-deleted file is a no-op (`false` return); `clearAudioFilePathBulk(ids)` is
  idempotent (`UPDATE … WHERE id IN (...)`). Accepted: no lock needed.

**Dispatcher discipline (layer separation):**

- `findOrphanedTerminalAudio(cutoff)` runs via Room on an internal executor (no main thread).
- `File.delete()` runs in the `withContext(Dispatchers.IO)` block (see the snippet above).
- `clearAudioFilePathBulk(ids)` runs via Room — `withContext(Dispatchers.IO)` is redundant but
  does no harm.

**Test case (Block 3):** `SessionDaoTest.findOrphanedTerminalAudio_filtersByStatusAndCutoff`
checks that only FAILED/CANCELLED rows with `audio_file_path != NULL && created_at < cutoff`
come back. RECORDED + COMPLETED rows are NOT returned (not even older ones).
Integration test in the service layer: a simulated FAILED session with a file on disk → after
the service idle-stop the file is gone and `audio_file_path IS NULL`.

<!-- FIX: Phase-B S-2 (2026-05-13) – DB-Row-Lifecycle für FAILED/CANCELLED explizit dokumentieren (Cleanup räumt nur Audio-Files, NICHT DB-Rows). -->
> **DB-row lifecycle for FAILED/CANCELLED — deliberately no auto-cleanup:**
>
> `cleanupOrphanedTerminalAudio` cleans up **only the audio files** on disk and sets `audio_file_path = NULL` in the DB row. The DB row itself **stays persistent** — FAILED/CANCELLED sessions thus accumulate in the history view until the user removes them manually via `HistoryDetailActivity → Delete`.
>
> Rationale:
> - **User value:** the error status in the history stays visible → the user can later trace ("why did the pipeline not work last week?"). An auto-delete would silently swallow this information.
> - **DB size:** ~500 bytes per FAILED row (id + type + status + error fields). At 100 FAILED sessions/year = 50 KB — irrelevant.
> - **`deleteInsertedOlderThan` (COMPLETED + inserted)** is a different path: it cleans up **successfully completed + inserted** sessions after 7d, because their information is redundant after insertion. This path does **not** apply to FAILED/CANCELLED, because their information is precisely **not** redundant (the pipeline failed, no insertion event).
>
> If in practice it turns out that FAILED sessions create DB bloat (e.g. >10k rows with frequent quota errors), an additional cleanup path can be added in a later phase (`deleteFailedOlderThan(cutoff)` with e.g. a 30d deadline). Phase 1: deliberately do not implement.

### §6.4 SharedPreferences extension — overlay position (OPEN-3)

The overlay position is persisted **separately per orientation** in SharedPreferences.
The values are **normalized 0..1 coordinates** relative to the screen size — on an orientation
or display change the pref value is usable unchanged; the OverlayBackend
de-normalizes to absolute pixels before the render.

**Pref constants** (file: `app/src/main/java/net/devemperor/dictate/preferences/Pref.kt`, to add):

```kotlin
object Pref {
    // ... bestehende Keys ...

    <!-- FIX: Issue 3.1.6 (User-Decision Option A) – Aspect-Bucket-Persist statt nur Portrait/Landscape -->
    // Overlay-Position (OPEN-3): Float, 0..1, gebucket nach Aspect-Ratio + Orientation.
    // Aspect-Bucket-Schema: "phone" (≤1.5), "tablet" (1.5..2.0), "wide" (>2.0). Multi-Display-
    // Setups (Foldables, externe Displays) profitieren — eine Position auf dem Foldable-Inner
    // wird nicht auf dem Foldable-Outer überschrieben.
    fun overlayPositionXKey(aspectBucket: String, orientation: String) =
        "overlay_position_${aspectBucket}_${orientation}_x"   // default 1.0f
    fun overlayPositionYKey(aspectBucket: String, orientation: String) =
        "overlay_position_${aspectBucket}_${orientation}_y"   // default 0.1f
    // Backwards-Compat: alte flache Keys werden auf erstes Bucket-Setup gemigriert.
    const val OverlayPositionPortraitX = "overlay_position_portrait_x"   // legacy
    const val OverlayPositionPortraitY = "overlay_position_portrait_y"   // legacy
    const val OverlayPositionLandscapeX = "overlay_position_landscape_x" // legacy
    const val OverlayPositionLandscapeY = "overlay_position_landscape_y" // legacy
}
```

<!-- FIX: Phase-B S-1 (2026-05-13) – Schreib-Trigger auf Modul-Pattern umgestellt: OverlayAction.UpdatePosition → OverlayModule.reduce + Effect.PersistOverlayPosition. -->
**Write trigger:** `Action.OverlayAction.UpdatePosition(portrait, x, y)` — dispatched by the `OverlayBackend` after drag-end (see Spec 3 §11.5). Reducer + effect:

```kotlin
// Im OverlayModule.reduce (Spec 1 §15, OverlayModule):
is Action.OverlayAction.UpdatePosition -> TransitionResult(
    nextState = if (action.portrait) {
        state.copy(
            positionPortraitX = action.x.coerceIn(0f, 1f),
            positionPortraitY = action.y.coerceIn(0f, 1f),
        )
    } else {
        state.copy(
            positionLandscapeX = action.x.coerceIn(0f, 1f),
            positionLandscapeY = action.y.coerceIn(0f, 1f),
        )
    },
    sideEffects = listOf(Effect.PersistOverlayPosition(action.portrait, action.x, action.y)),
)

// Im OverlayModule.runEffect:
is Effect.PersistOverlayPosition -> {
    val (xKey, yKey) = if (effect.portrait) {
        Pref.OverlayPositionPortraitX.key to Pref.OverlayPositionPortraitY.key
    } else {
        Pref.OverlayPositionLandscapeX.key to Pref.OverlayPositionLandscapeY.key
    }
    services.sharedPrefs.edit()
        .putFloat(xKey, effect.x.coerceIn(0f, 1f))
        .putFloat(yKey, effect.y.coerceIn(0f, 1f))
        .apply()
}
```

<!-- FIX: Issue 2.0.10 (Folge-Korrektur) – Read-Trigger zeigt nicht mehr auf §6.3 (dort gelöscht), sondern auf §4.5 PipelinePrefMirror.attach. -->
**Read trigger:** `PipelinePrefMirror.attach(store)` (see §4.5) is called in
`DictateOrchestrator.init` **before** `recovery.recover(store)` and initializes
the overlay-position fields in the store (`initialMirror()`); subsequent pref changes
run through the `OnSharedPreferenceChangeListener` of the same PrefMirror. This ensures
that after an OOM death or a service restart the last saved position
is immediately in the state; the OverlayBackend reads the values from the state (not directly from
the pref) and thus respects the single-source-of-truth rule "everything via DictateUiState".
`recoverFromDb()` itself (§6.3) only mutates `pendingSessions` and is no longer
involved in the pref read.

<!-- FIX: Phase-B S-1 (2026-05-13) – SOLID-Block auf F-11 umgestellt (PipelinePrefMirror + OverlayModule statt monolithischer PipelineStateManager). -->
**SOLID conformity:** The pref-mirror logic (read trigger) lives in the `PipelinePrefMirror` (single-responsibility, §4.5); the pref-write logic in the `OverlayModule.runEffect(Effect.PersistOverlayPosition)` (§15, OverlayModule). `OverlayBackend` only knows the action `Action.OverlayAction.UpdatePosition` and the state fields, not the pref keys (dependency inversion).

---

## §7 Lifecycle: Foreground Service

> **Architecture correction F-3 (iteration 2026-05-08):** Earlier spec versions
> built notification-building, state-subscribe and action-PendingIntent routing
> directly into `DictatePipelineService.onStartCommand`. These three concerns
> are now extracted into two helper classes (`PipelineNotificationCoordinator`,
> `PipelineActionRouter`), so that the service is solely the process-lifecycle owner
> and everything else is injected + testable.

<!-- FIX: Phase-B S-1 (2026-05-13) – §7.1 Service-Struktur auf F-11-Naming umgestellt (PipelineStateManager → DictateOrchestrator + 12 Module + 4 Hilfsklassen). Pre-F-11-Diagramm zeigte den monolithischen Manager als einzigen Composition-Root-Eintrag. -->
### §7.1 Service structure (F-3 / SRP, F-11 Modular Orchestrator)

```
DictatePipelineService (Process-Lifecycle-Owner)
   ├── DictateOrchestrator               // §4.3, Composition Root + Action-Routing
   │     ├── DictateUiStateStore        (§4.4)
   │     ├── PipelinePrefMirror         (§4.5)
   │     ├── PipelineRecovery           (§4.6)
   │     ├── ModuleServicesFactory      (§4.7)
   │     └── DictateModuleRegistry.all  (§4.8) — 13 aktive Module (+ KeyboardInputModule §15.6)
   ├── PipelineNotificationCoordinator   // baut Notifications, abonniert Store
   └── PipelineActionRouter              // PendingIntent → Orchestrator.dispatch(Action)
```

**Responsibilities:**

| Class | SRP | Side effects |
|---|---|---|
| `DictatePipelineService` | FGS lifecycle (`startForeground`/`stopSelf`), bind connection | yes (FGS calls) |
| `DictateOrchestrator` | action routing + cross-module cascade dispatch | no (delegates to modules via `runEffect`) |
| `PipelineNotificationCoordinator` | state → notification render, throttled | yes (NotificationManager) |
| `PipelineActionRouter` | PendingIntent build + intent decode → `orchestrator.dispatch(Action)` | no (pure mapping) |

### §7.2 Start

<!-- FIX: Phase-B S-5 (2026-05-13) – Bind-Site auf onCreateInputView konsistent mit §11.3.1 gesetzt; vorher stand "IME-Service onCreate" — falscher Lifecycle-Hook (onCreate kann theoretisch vor erstem View-Inflate laufen; bind-Counter würde dann gegen IME-Service zählen, der nicht die Hands-on-Konsumenten-Klasse ist). §11.3.1 ist SoT für die Begründung (Latenz-Argument). -->
```kotlin
// IME-Service: Bind-Site ist `onCreateInputView` (siehe §11.3.1 für Begründung — Latenz-
// Argument: 50-200 ms first-bind, in onCreateInputView ist Zeitreserve da, weil der
// IME-View ohnehin inflate-blocking ist). NICHT in `onCreate` der IME — das ist zu früh
// (IME-onCreate kann VOR erstem View-Inflate laufen, manche OEM-IME-Settings rufen es).
@Override
public View onCreateInputView() {
    if (pipelineBinder == null) {
        Intent intent = new Intent(this, DictatePipelineService.class);
        ContextCompat.startForegroundService(this, intent);
        bindService(intent, pipelineConnection, BIND_AUTO_CREATE);
    }
    // ... View-Inflate ...
}
```

### §7.3 onStartCommand (lean)

<!-- FIX: Phase-B S-1 (2026-05-13) – §7.3 onCreate-Snippet auf F-11 umgestellt: DictateOrchestrator + ModuleServicesFactory + DictateUiState.initial(); ViewModeFsm-Parameter entfernt (lebt jetzt im ViewModeModule, §15.1). -->
```kotlin
class DictatePipelineService : Service() {

    private lateinit var orchestrator: DictateOrchestrator
    private lateinit var notifCoordinator: PipelineNotificationCoordinator
    private lateinit var actionRouter: PipelineActionRouter
    // FIX: Phase-B S-5 (2026-05-13) – audioFileFactory als lateinit-Field deklariert, weil
    // §11.2.2 Block 2 das Field bereits in der `ModuleServicesFactory`-Lambda referenziert
    // (Block 4 wired den realen `CacheDirAudioFileFactory`). In Block 2 ist es initial
    // `CacheDirAudioFileFactory(applicationContext)` (no-op-ready, alloc-only), die Pre-
    // Dispatch-Allocate-Logik landet erst in Block 4. Ohne diese Vorab-Deklaration scheitert
    // der Block-2-Composition-Root-Snippet aus §7.3.
    private lateinit var audioFileFactory: AudioFileFactory
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // FIX: Phase-B S-5 (2026-05-13) – NotificationChannel MUSS vor startForeground() (§11.1.4)
        // existieren. `onStartCommand` ruft `startForegroundCompat(notifCoordinator.buildInitial())`;
        // `buildInitial()` referenziert CHANNEL_ID. Auf API ≥ 26 wirft NotificationManager eine
        // `IllegalArgumentException`, wenn der Channel beim ersten `notify`/`startForeground`-Call
        // noch nicht erzeugt ist — selbst, wenn die Notification-Builder-Pipeline syntaktisch
        // valide ist. Synchroner In-Memory-Setup, < 5 ms, kein FGS-Frist-Risiko.
        ensureNotificationChannel()                              // synchron, < 5 ms

        // Composition Root — alle Hilfsklassen werden hier konstruiert + verdrahtet.
        val database = DictateDatabase.getInstance(this)
        val store = DictateUiStateStore(DictateUiState.initial())
        val sessionRepo: PipelineSessionRepo = RoomPipelineSessionRepo(database.sessionsDao())
        // FIX: Phase-B S-5 (2026-05-13) – PipelineOrchestrator (ALT, Audio-Pipeline, Spec 1 §1.x)
        // vs. DictateOrchestrator (NEU, State-Action-Routing) — JobExecutor erwartet den alten.
        // Hier konstruiert; per Schritt 10 der Sequenz (§4.11.5.1) an JobExecutor.initialize
        // übergeben. NICHT mit `orchestrator` (DictateOrchestrator) verwechseln.
        val pipelineOrchestrator = PipelineOrchestrator(
            aiOrchestrator = AIOrchestrator(sp, database.usageDao()),
            // ... (weitere Args identisch zu DictateInputMethodService.initLongLivedObjects Z. 379-385)
        )
        val runner: PipelineRunner = JobExecutor
        val prefMirror = PipelinePrefMirror(getSharedPreferences("dictate_prefs", MODE_PRIVATE))
        val recovery = PipelineRecovery(sessionRepo)
        // FIX: Phase-B S-5 (2026-05-13) – Zuweisung auf Member-`lateinit var audioFileFactory`
        // (siehe Field-Deklaration oben). Vermeidet shadowing — Block 4 muss das Field aus
        // anderen Service-Methoden (z.B. cleanupOrphanedTerminalAudio in stopSelfPath) lesen.
        audioFileFactory = CacheDirAudioFileFactory(applicationContext)

        // F-11: ModuleServicesFactory injiziert die Hardware-Adapter pro EffectHandler-Aufruf.
        // emitAction wird über eine Late-Reference auf orchestrator gehalten (Konstruktor-Zyklus),
        // weil der Orchestrator selbst die emitAction-Quelle ist.
        val servicesFactory = ModuleServicesFactory {
            ModuleServices(
                recordingHardware = RecordingHardware(audioManager, ...),
                bluetoothSco = BluetoothScoSubsystem(...),
                audioFocus = AudioFocusSubsystem(...),
                // ... weitere Subsystem-Adapter
                audioFileFactory = audioFileFactory,
                scope = serviceScope,
                emitAction = { action -> orchestrator.emitAction(action) },
            )
        }

        orchestrator = DictateOrchestrator(
            scope = serviceScope,
            store = store,
            servicesFactory = servicesFactory,
            prefMirror = prefMirror,
            recovery = recovery,
            // modules = DictateModuleRegistry.all (Default, §4.8)
        )

        // KG-AFF-2: One-shot Legacy-Audio-Cleanup (§4.11.6.2). Idempotent.
        LegacyAudioFileMigration.run(applicationContext)

        notifCoordinator = PipelineNotificationCoordinator(this, orchestrator.state, serviceScope)
        actionRouter = PipelineActionRouter(orchestrator)

        // FIX: Phase-B S-5 (2026-05-13) – JobExecutor.initialize erwartet den ALTEN PipelineOrchestrator
        // (Audio-Pipeline-Runner), NICHT den neuen DictateOrchestrator (State-Action-Router) — siehe
        // Spec 1 §1.x Naming-Konvention + §13.5.a G7-Block. Type-Mismatch hier ist Compile-Error.
        JobExecutor.initialize(pipelineOrchestrator)   // G7: wandert vom IME-onCreate hierher

        // Crash-Orphan-Cleanup async (parallel zur Recovery, beide read-only)
        serviceScope.launch(Dispatchers.IO) {
            try {
                val referenced = database.sessionsDao()
                    .findAllAudioFilePaths()
                    .filterNotNull()
                    .toSet()
                audioFileFactory.cleanupOrphans(referenced)
            } catch (t: Throwable) {
                Log.w("DictatePipelineSvc", "orphan cleanup failed at boot", t)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Action-Intents verarbeiten (von Notification-Buttons)
        intent?.let { actionRouter.dispatch(it) }
        // 2. Initiale FGS-Notification + reaktive Updates starten
        // FIX: Phase-C C-2 (2026-05-14) – `NOTIF_ID` qualifiziert via `PipelineNotificationCoordinator.NOTIF_ID`
        // (Coordinator-companion ist SoT, kein lokales `const val NOTIF_ID` im Service-Companion —
        // siehe §10 Acceptance "Phase-B S-5 NOTIF_ID-Konsistenz" + §11.1.2 NOTIF_ID-Konsolidierung).
        startForeground(PipelineNotificationCoordinator.NOTIF_ID, notifCoordinator.buildInitial())
        notifCoordinator.startReactiveUpdates(::stopSelfWhenTerminal)
        return START_NOT_STICKY
    }

    private fun stopSelfWhenTerminal(state: DictateUiState) {
        if (state.isAllTerminal()) stopSelf()
    }

    override fun onBind(intent: Intent): IBinder = LocalBinder(orchestrator)

    // FIX: Phase-B S-5 (2026-05-13) – onDestroy mit `runBlocking`-Timeout-Wrapper konkret
    // gezeigt (§4.3 KDoc behauptet "läuft unter runBlocking-Timeout des Service.onDestroy",
    // aber kein Snippet zeigte das tatsächlich). Service.onDestroy hat selbst ein OS-seitiges
    // Timeout-Fenster (auf API ≥ 8: 20 s, faktisch idR < 5 s vor SIGKILL); ein einzelnes Modul
    // mit fehlerhaftem `terminate`-Effect darf den Service nicht hängen lassen.
    //
    // FIX: Phase-C C-2 (2026-05-14) – MediaRecorder-Release-Pfad: §10 Acceptance (Block-2,
    // "MediaRecorder-release-Pfad") und §13.5 G6 verlangen, dass Service.onDestroy bei aktivem
    // Recording *zuerst* eine Cancel-Action dispatcht (damit der Reducer State→Idle setzt und
    // `Effect.ReleaseMediaRecorder` aus dem normalen FSM-Pfad emittiert wird), *bevor*
    // `orchestrator.shutdown()` das Cleanup über `module.terminate(services)` finalisiert.
    // Die untenstehende `shutdown()`-Variante allein ist NICHT ausreichend, weil
    // `DictateModule.terminate()` einen Default-Body `Unit` hat (§4.2) und RecordingModule
    // (§15.2) heute KEIN `terminate`-Override mit Hardware-Release definiert hat — der
    // MediaRecorder leakt also im Native-Heap, wenn der User die IME schließt während
    // Recording aktiv ist.
    //
    // FIX: Phase-C C-3 (2026-05-14) – Action-Naming-Disambiguation (C-2 F-3 Cross-Reference):
    // Recording-Hardware wird vom `RecordingModule` (§15.2) gehalten — die korrekte Action ist
    // `Action.RecordingAction.CancelRecording` (route via `moduleByLeafClass` an RecordingModule),
    // dessen Reducer-Arm `Active/Paused/Preparing+CancelRecording` synchron `Effect.ReleaseMediaRecorder`
    // emittiert. `Action.PipelineAction.CancelPipeline` (alte Plan-Variante, §10 + §13.5 vor C-3)
    // routet an PipelineModule, das den Recording-Hardware-Release-Effect nicht hält (PipelineModule.Effect
    // hat keinen `ReleaseMediaRecorder`-Eintrag). Pre-Cancel-Block unten ist mit der korrekten
    // State-Switch-Logik ausformuliert; §10 + §13.5 G6 sind auf C-3-Variante synchron gezogen.
    override fun onDestroy() {
        super.onDestroy()
        // 0. Pre-Cancel-Dispatch (Phase-C C-2 + C-3 / §10 + §13.5 G6 Pfad A):
        //    bei aktivem Recording bzw. aktiver Pipeline die jeweils Modul-eigene Cancel-Action
        //    dispatchen, damit der Reducer State→Idle setzt + den passenden Hardware/Job-Release-
        //    Effect aus dem normalen FSM-Pfad emittiert. Aktion hängt vom State ab — Recording
        //    hat Priorität, weil MediaRecorder den Native-Heap leakt; Pipeline ist sekundär,
        //    weil DB-Status + Job-Cancel idempotent sind:
        //
        //    val snap = orchestrator.state.value
        //    when {
        //        snap.recording !is RecordingState.Idle ->
        //            orchestrator.dispatch(Action.RecordingAction.CancelRecording)
        //        snap.pipeline !is PipelineUiState.Idle ->
        //            orchestrator.dispatch(Action.PipelineAction.CancelPipeline)
        //    }
        //
        // 1. Module-Cleanup mit Timeout: `shutdown()` ruft jedes Modul-`terminate(services)`.
        //    `services.scope` ist noch lebendig (Aufrufer-Vertrag §4.3), aber wir umschließen
        //    den Aufruf zusätzlich mit `runBlocking`-Timeout, damit ein blockierendes Modul
        //    den OS-Service-Destroy-Timer nicht verbraucht. 2 s Cap = konservativ <
        //    OS-seitigem 5-s-Limit + Reserve für Schritt 2/3.
        try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(2_000L) {
                    orchestrator.shutdown()
                }
            }
        } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
            android.util.Log.w(TAG, "orchestrator.shutdown() timeout — leaking module resources", t)
            // Fail-safe: weiter zu Schritt 2/3. Hardware-Releases sind primär synchron
            // (RecordingManager.release etc.), also typischerweise bereits durch — der
            // Timeout schützt nur gegen pathologische Cases.
        }
        // 2. Restliche in-flight Coroutines cancellen (§7.3 Schritt 5: cleanupOrphans, etc.).
        //    MUSS NACH `shutdown()` laufen (Aufrufer-Vertrag §4.3) — sonst laufen die
        //    Module-terminate-Calls auf einem gecancellten Scope und async-Cleanups sind
        //    silent-no-op.
        serviceScope.cancel()
        // 3. Notification entfernen — auch wenn `stopSelfWhenTerminal` schon gerufen wurde,
        //    schadet ein doppelter `nm.cancel(NOTIF_ID)` nicht (idempotent).
        NotificationManagerCompat.from(this).cancel(PipelineNotificationCoordinator.NOTIF_ID)
    }

    companion object {
        private const val TAG = "DictatePipelineSvc"
    }
}
```

### §7.4 PipelineNotificationCoordinator

```kotlin
class PipelineNotificationCoordinator(
    private val service: Service,
    private val state: StateFlow<DictateUiState>,
    private val scope: CoroutineScope,
) {
    private val nm = NotificationManagerCompat.from(service)

    /** Initiale Notification für `startForeground` — synchron, ohne Subscription. */
    fun buildInitial(): Notification = build(state.value)

    /**
     * Startet die State-Subscription. Throttled auf 1 Update/300ms, damit
     * Recording-Timer-Ticks nicht zu Notification-Spam führen (siehe §14 Open-1).
     * Bei Terminal-State wird `onTerminal(state)` aufgerufen — der Service
     * entscheidet dort über `stopSelf()`.
     */
    fun startReactiveUpdates(onTerminal: (DictateUiState) -> Unit) {
        scope.launch {
            state
                .distinctUntilChanged { old, new -> notificationEqual(old, new) }
                .collect { state ->
                    nm.notify(NOTIF_ID, build(state))
                    onTerminal(state)
                }
        }
    }

    /** State → Notification (siehe §7.6 für Inhalt-Mapping). Pure Function. */
    private fun build(state: DictateUiState): Notification { /* §7.6 */ TODO() }

    /**
     * Vergleicht nur die für die Notification relevanten Felder; eliminiert
     * Re-Renders bei UI-only State-Changes (z.B. `singleRowMode`-Toggle).
     */
    private fun notificationEqual(a: DictateUiState, b: DictateUiState): Boolean =
        a.recording == b.recording
            && a.pipeline.notifySemanticEquivalent(b.pipeline)   // ignoriert Sub-Sekunden-Timer
            && a.pendingSessions.map { it.sessionId } == b.pendingSessions.map { it.sessionId }

    companion object { const val NOTIF_ID = 0xD1C7A7E }
}
```

**SRP:** exclusively state → notification mapping + subscription management. No action routing, no lifecycle knowledge except "when is the service terminal".

### §7.5 PipelineActionRouter

<!-- FIX: Issue 2.1.19 / R.15 – sessionId String durchgängig (Java-IME-Compat: getStringExtra) -->
```kotlin
class PipelineActionRouter(
    private val orchestrator: DictateOrchestrator,
) {
    /** Vom `onStartCommand` gerufen, wenn ein Action-Intent ankommt. */
    fun dispatch(intent: Intent) {
        when (intent.action) {
            ACTION_PAUSE   -> orchestrator.dispatch(Action.RecordingAction.PauseRecording)
            ACTION_RESUME  -> orchestrator.dispatch(Action.RecordingAction.ResumeRecording)
            ACTION_STOP    -> orchestrator.dispatch(Action.RecordingAction.StopRecording)
            ACTION_SEND    -> orchestrator.dispatch(Action.RecordingAction.StopRecording)
            ACTION_CANCEL  -> orchestrator.dispatch(Action.PipelineAction.CancelPipeline)
            ACTION_INSERT  -> intent.getStringExtra(EXTRA_SESSION_ID)
                                  ?.let { orchestrator.dispatch(Action.PipelineAction.ConfirmInsertion(it)) }
            ACTION_DISMISS -> intent.getStringExtra(EXTRA_SESSION_ID)
                                  ?.let { orchestrator.dispatch(Action.PipelineAction.DismissResult(it)) }
        }
    }

    /** PendingIntent-Builder, vom NotificationCoordinator genutzt. */
    fun pendingIntentFor(ctx: Context, action: String, sessionId: String? = null): PendingIntent {
        val intent = Intent(ctx, DictatePipelineService::class.java).apply {
            this.action = action
            sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
        }
        return PendingIntent.getService(
            ctx, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_PAUSE = "net.devemperor.dictate.PAUSE"
        const val ACTION_RESUME = "net.devemperor.dictate.RESUME"
        const val ACTION_STOP = "net.devemperor.dictate.STOP"
        const val ACTION_SEND = "net.devemperor.dictate.SEND"
        const val ACTION_CANCEL = "net.devemperor.dictate.CANCEL"
        const val ACTION_INSERT = "net.devemperor.dictate.INSERT"
        const val ACTION_DISMISS = "net.devemperor.dictate.DISMISS"
        const val EXTRA_SESSION_ID = "session_id"
    }
}
```

<!-- FIX: Phase-B S-1 (2026-05-13) – SRP-Beschreibung auf DictateOrchestrator umgestellt. -->
**SRP:** pure mapping between notification-action strings and `Action` sealed-class variants. No UI logic, no notification build, no lifecycle. Tests can inject a mock `DictateOrchestrator` and check that each action string hits the right `Action` variant via `orchestrator.dispatch(...)`.

### §7.6 Notification content

| State | Title | Subtitle | Actions |
|-------|-------|----------|---------|
| Idle (should never be visible, because stopSelf) | — | — | — |
| Recording-Active | "Dictate" | "Recording" (no seconds timer, see §14 Open-1) | [Pause] [Stop] [Send] |
| Recording-Paused | "Dictate" | "Recording paused" | [Resume] [Stop] [Send] |
| Pipeline-Running | "Dictate" | "Processing (step 2/4)" | [Cancel] |
| Pipeline-Done, unread | "Dictate" | "Ready to insert" | [Insert] [Dismiss] |

### §7.7 Stop

`stopSelf()` is called by the service as soon as the `notifCoordinator` emits a terminal state (`state.isAllTerminal()` — no recording, no running pipeline, no pending-finished sessions). Then the notification is automatically removed.

---

## §8 IME-Service integration

The `DictateInputMethodService` becomes **significantly leaner** through the refactor:

| Component | Today (in the IME service) | Future |
|-------------|------------------------|---------|
<!-- FIX: Phase-B S-1 (2026-05-13) – Migrations-Tabelle auf F-11 (Modul-Ziele statt monolithischer PipelineStateManager) umgestellt. -->
| Recording logic | RecordingStateController | moves into `RecordingModule` (§15.2) |
| Pipeline logic | KeyboardUiController + JobExecutor + PipelineOrchestrator | moves partly (the state part → `PipelineModule`); JobExecutor + PipelineOrchestrator stay, but held in the PipelineService |
| State coroutines | none | in the PipelineService (serviceScope) |
| View rendering | direct code in the service | moves into KeyboardLayoutManager (Spec 2) |
| Visibility mutations | hybrid (KSM + RecordingUiController + service) | gone — all via the LayoutManager resolver (Spec 2) |
| Click-listener wiring | MainButtonsController | moves into KeyboardLayoutManager (Spec 2) |

**What stays in the IME service:**
- View lifecycle (`onCreateInputView`, `onStartInputView`, `onFinishInputView`, `onDestroy`).
- IME-specific APIs (`getCurrentInputConnection()`, `requestHideSelf()`, `setInputView()`).
- Bind/unbind to the PipelineService.
- Forwarding of user events to the PipelineService.

<!-- FIX: Issue 2.1.11 / R.9 – View-Recreate-Vertrag (viewScope-Cancel + Migrations-Tabelle) -->
### §8.x View-recreate contract

On a re-inflate of the IME view (rotation, theme change, IME switch) all view subscribers must
be cleanly unregistered and re-attached. The contract:

```
1. viewScope-Erzeugung in DictateInputMethodService.onCreateInputView() VOR Subscriber-Wiring.
2. viewScope.cancel() in DictateInputMethodService.onFinishInputView() (analog cleanupOldControllers).
3. WindowManager.removeView (Overlay) wird in OverlayBackend.detach() gerufen (kein StateFlow-Cancel,
   sondern expliziter Call — siehe Spec 3 §4.3 / §11.6).
4. KeyboardLayoutManager.detachBackend() wird in onFinishInputView() gerufen — räumt das aktive
   ImeViewBackend (firstRender-Flag-Reset, R.14) und das ContentAreaController-Backend (R.10) ab.
```

**Migration table** (today → refactor):

| Today's call | Refactor replacement |
|---|---|
| `cleanupOldControllers()` | `viewScope.cancel()` (in `onFinishInputView`) |
| `rewireCallbacks()` | removed (the StateFlow subscriber + the new viewScope subscribes automatically) |
| `restoreUiState()` | removed (StateFlow holds the state, the new viewScope subscribes → first emission auto-restored) |
| `keyboardLayoutManager.detachAllBackends()` (NEW, Issue 3.1.4 / Option C) | additionally called in `onDestroy` — eliminates the window leak on an IME switch |

**Acceptance:** Robolectric test rotation while the pipeline is running — after `onFinishInputView` +
`onCreateInputView` the subscriber is re-attached and the pipeline state is correctly
rendered. Spec 1 §10 Block-2 acceptance is extended by this test.

---

## §9 Migration of existing classes

<!-- FIX: Issue 1.0.6 – Hierarchische State-Pfade (F-10) durchpropagiert in §9 Migrations-Tabellen + §13.2 Audit (Mapping siehe Spec 1 §3) -->

<!-- FIX: Phase-B S-1 (2026-05-13) – Section-Titel auf RecordingModule umgestellt (F-11). -->
### §9.1 RecordingStateController → RecordingModule

**Today:** `app/src/main/java/net/devemperor/dictate/core/RecordingStateController.kt`
- A class with `var state: RecordingState` (l. 106-107) — internal mutation via `setState(newState)` (l. 353-357).
- Public-API methods that mutate state:
  - `startRecording(audioFile, useBluetooth, audioFocusEnabled)` → `:128-140`
  - `stopRecording()` → `:145-159`
  - `togglePause()` → `:164-180`
  - `setAudioFocusRuntime(enabled)` → `:201-212` (mutates a field + AudioManager)
  - `cancelRecording()` → `:217-225`
  - Lifecycle hooks `onKeyboardHidden() / onKeyboardShown() / onDestroy()` → `:233-267`
  - Manager callbacks: `onRecordingStarted/Stopped/Paused/Resumed`, `onScoConnected/Disconnected/Failed` → `:271-321`
- Callback forwarding: `Callback.onStateChanged(old, new)` (l. 90).
- Construction + wiring (service side): `DictateInputMethodService.java:372-376` (`recordingStateController = new RecordingStateController(...)` + `setManagers`); `setCallback` in `:914-958`.

<!-- FIX: Phase-B S-1 (2026-05-13) – Migrations-Tabelle auf Action-/Modul-Pattern umgestellt. RecordingStateController-Methoden werden zu Action.RecordingAction-Varianten, die im RecordingModule.reduce verarbeitet werden. -->
**Future:** Moves entirely into `RecordingModule` (§15.2). The `Callback` interface is removed — subscribers subscribe via `orchestrator.state.collect { ... }` and react to `oldState.recording` vs. `newState.recording` diffs.

| Today (method) | Future (action + module) | Mutates in `DictateUiState` |
|---|---|---|
| `startRecording(...)` (l. 128) | `Action.RecordingAction.StartRecording(target, audioFile)` → `RecordingModule.reduce` | `recording: Idle → Preparing → Active` |
| `stopRecording()` (l. 145) | `Action.RecordingAction.StopRecording` → `RecordingModule.reduce` | `recording: Active → Idle` + pipeline auto-start (`PipelineAction.Submit` via cross-module cascade) |
| `togglePause()` (l. 164) | `Action.RecordingAction.PauseRecording` / `ResumeRecording` | `recording: Active ↔ Paused` |
| `setAudioFocusRuntime(b)` (l. 201) | `Action.AudioAction.ToggleAudioFocusPref` → `AudioModule.reduce` <!-- FIX: Phase-B S-3 (2026-05-13) – Naming-Drift behoben: Spec 2 §3.3 SoT-Name ist `ToggleAudioFocusPref` mit `Pref`-Suffix (war hier `ToggleAudioFocus`). --> | `audio.audioFocusEnabledPref` |
| `cancelRecording()` (l. 217) | `Action.RecordingAction.CancelRecording` → `RecordingModule.reduce` | `recording: → Idle` + `Effect.DeleteAudioFile` |

**Data type `RecordingState`** (`RecordingState.kt:10-18`) stays unchanged — it is a sealed class, exhaustive, good. It becomes a field of `DictateUiState` (see §3).

<!-- FIX: Phase-B S-1 (2026-05-13) – Section-Titel auf PipelineModule umgestellt. -->
### §9.2 KeyboardUiController state part → PipelineModule

**Today:** `app/src/main/java/net/devemperor/dictate/core/KeyboardUiController.kt`
- State field `var state: PipelineUiState` (l. 63).
- Mutator `updateDictateUiState(newState)` (l. 147-155) — a single-call-site internal method, called by 5 public-API methods:
  - `preparePipeline()` → `:213-219`
  - `startPipeline(totalSteps, config, initialCompletedSteps)` → `:230-265`
  - `stopPipeline()` → `:271-285`
  - `toggleAutoEnter()` → `:292-299`
  - `enterReprocessStaging(...)` → `:307-321`
  - `cancelReprocessStaging()` → `:326-330`
  - `updateReprocessQueue(queue)` → `:335-340`
  - `updateReprocessLanguage(code)` → `:348-353`
  - Internal updaters `addRunningStep / completeStep / failStep` → `:364-456` (all call `updateRunningState { ... }` l. 161-166).
- View mutations within `updateDictateUiState`: calls `refreshRecordButtonFromState()` (l. 150, view mutation: button text/icon/enabled — l. 464-509) and `stateManager.refresh()` (l. 153) — the view mutation moves away into the LayoutCatalog (Spec 2 §9.5), the state mutation into `PipelineModule.reduce`. <!-- FIX: Phase-B S-1 (2026-05-13) – PipelineStateManager → PipelineModule.reduce -->

**Future:**
- The sealed class `PipelineUiState` (`PipelineUiState.kt:13-54`) stays — the data type is good.
<!-- FIX: Phase-B S-1 (2026-05-13) – Migrations-Ziel auf PipelineModule.reduce + Action-Dispatch (F-8). -->
- The state field + mutator move into `PipelineModule.reduce` as `state.copy(...)` (the reducer); `orchestrator.dispatch(action)` is the sole mutation entry (F-8 Single Dispatch).
- The public-API methods on `KeyboardUiController` are replaced by `Action.PipelineAction.*` variants — callers dispatch via `orchestrator.dispatch(Action.PipelineAction.X)`. Today's implementations are migrated inline into the reducer arms — no "wrapper".
- `refreshRecordButtonFromState()` (l. 464-509) moves into the `RECORD` slot `textResolver` + `enabledResolver` in the LayoutCatalog (Spec 2 §9.5).
- The `stepRows` management (l. 133-135 + `addRunningStep / completeStep / failStep`) stays in the `KeyboardUiController` (view side), but is triggered by the `state.pipeline` StateFlow subscriber instead of by direct method calls from the service.
- The `AutoEnterConfig` field (l. 67) moves into `DictateUiState.pipeline` (the `PipelineUiState.Running` variant already carries `autoEnterActive` — `AutoEnterConfig` is a redundant layer and is removed).

<!-- FIX: Phase-B S-1 (2026-05-13) – Section-Titel auf LayoutModule + LayoutCatalog umgestellt. -->
### §9.3 KeyboardStateManager → LayoutModule + LayoutCatalog

**Today:** `app/src/main/java/net/devemperor/dictate/core/KeyboardStateManager.kt`
- Own state fields: `contentArea: ContentArea` (l. 100), `isSmallMode: Boolean` (l. 102) — today private setters, mutated via `setContentArea(area)` (l. 135-138) and `setSmallMode(enabled)` (l. 140-146).
- `applyVisibility()` (l. 158-169) with sub-functions `applyContentAreaVisibility()` (l. 171-181), `applyRecordingControlsVisibility()` (l. 183-192), `applyPromptsVisibility()` (l. 194-224), `applyPromptsLayout()` (l. 227-240). Mutates 8 view properties directly (see §13.1).
- `refresh()` (l. 151-154) — external trigger, calls `applyVisibility()`.
- Lambda constructor parameters: `isRecording`, `isPaused`, `isPipelineRunning`, `isRewordingEnabled`, `isPipelineProgressVisible`, `isReprocessStaging` (l. 78-97). These lambdas query `RecordingStateController` and `KeyboardUiController` directly today — they are replaced by a reactive `state.collect` subscriber.

**Future:**
- `contentArea` and `isSmallMode` move into `DictateUiState.layout` (see §3) — mutation via `Action.LayoutAction.SetContentArea(area)` and `Action.LayoutAction.ToggleSmallMode` → `LayoutModule.reduce`. **Atomicity (KSM bug fix):** the earlier `setSmallMode(true)` first mutated `isSmallMode = true` and THEN `contentArea = MAIN_BUTTONS` (two sequential steps, see `KeyboardStateManager.kt:141-145`). In the refactor this runs in a single `state.copy(layout = layout.copy(smallMode = enabled, contentArea = MAIN_BUTTONS))` — atomic, no subscriber sees the intermediate state. <!-- FIX: Phase-B S-1 (2026-05-13) – PipelineStateManager → Action+LayoutModule; Atomarität-Klausel hinzugefügt -->
- The lambda-based query patterns are removed: all 6 lambdas read fields from 2 different classes today; in the future all axes are members of `DictateUiState`, the subscriber gets them atomically in one emission.
- `applyVisibility` + sub-functions move entirely into the `LayoutCatalog` resolver (Spec 2 §9.3). 4 of 8 visibility mutations become predicate-driven (see §13.1); 4 are ContentArea axis mutations that flow into `LayoutCatalog.forKeyboard(state)`.
- The `setLayoutModeController()` / `clearLayoutModeController()` bridge (l. 115-131) is removed entirely — `KeyboardLayoutModeController` is replaced by MotionLayout in Spec 2 §9.1.

### §9.4 5 scattered resend_btn mutations

**Concrete mutation sites (today):**

| File : line | Code | Context |
|---|---|---|
| `RecordingUiController.kt:137` | `resendButton.visibility = if (getLastAudioFileExists()) View.VISIBLE else View.GONE` | `applyIdleState()` — on recording → idle |
| `RecordingUiController.kt:158` | `resendButton.visibility = View.GONE` | `applyActiveState()` — when recording active |
| `DictateInputMethodService.java:1345` | `resendButton.setVisibility(View.VISIBLE);` | `onStartInputView` — idle re-entry |
| `DictateInputMethodService.java:1347` | `resendButton.setVisibility(View.GONE);` | `onStartInputView` — idle re-entry, no audio |
| `DictateInputMethodService.java:1669` | `resendButton.setVisibility(View.GONE);` | `runTranscriptionViaOrchestrator` — pipeline start |
| `DictateInputMethodService.java:1839` | `resendButton.setVisibility(View.VISIBLE);` | `onShowResend()` callback — pipeline done |

(Nominally "5 scattered places" per the plan language — actually there are 6 mutation sites across 2 files; the plan statement counts RecordingUiController l.137+158 as "one place per component").

**Eliminated in Block 1.** Instead a single predicate in the LayoutCatalog (Spec 2):

```kotlin
// Slot-Definition für RESEND-Slot in LayoutCatalog (siehe Spec 2 §3.2):
ButtonSlot(
    logicalId = LogicalButtonId.RESEND,
    visibilityPredicate = { state ->
        state.resend.lastAudioExists
            && state.resend.resendEnabled                              // Pref.ResendButton
            && state.recording is RecordingState.Idle
            && state.pipeline is PipelineUiState.Idle
    },
    actionResolver = { /* short-press: re-run pipeline; long-press: enter staging */ }
)
```

**Data flow:**
- `resend.lastAudioExists` is pre-set at the service `onCreate` (a `File.exists()` check on `Pref.LastFileName`) and updated after every recording-done (`stopRecording` callback).
- `resend.resendEnabled` is a mirror of `Pref.ResendButton` — read at `onCreate` and listened to via the SP listener.
- The `recording / pipeline` axes are members of `DictateUiState` anyway.

### §9.5 recordButton.text/isEnabled hybrid

**Today (race-fragile):**
- `RecordingUiController.applyIdleState` (l. 115-138) sets `recordButton.text/isEnabled/icon` for Idle.
- `RecordingUiController.applyActiveState` (l. 144-184) sets for Active.
- `RecordingUiController.applyPreparingState` (l. 140-142) sets only `isEnabled = false`.
- `KeyboardUiController.refreshRecordButtonFromState` (l. 464-509) sets for Preparing/Running/ReprocessStaging.
- **Race:** If a pipeline start comes directly after a recording stop, the idle branch of `RecordingUiController` runs once (`onStateChanged: Active → Idle`) — and immediately after that `KeyboardUiController.refreshRecordButtonFromState` with `Preparing`. On rotation/restoreUiState the order is not deterministic (see `restoreUiState` `:973-1026`).

**Future:** a single `textResolver` + `enabledResolver` in the RECORD slot definition in the LayoutCatalog (Spec 2 §9.5). Example skeleton:

```kotlin
ButtonSlot(
    logicalId = LogicalButtonId.RECORD,
    textResolver = { state ->
        when {
            state.pipeline is PipelineUiState.Preparing -> R.string.dictate_sending
            state.pipeline is PipelineUiState.Running -> /* counter+timer */
            state.pipeline is PipelineUiState.ReprocessStaging -> /* "Audio M:SS · Senden" */
            state.recording is RecordingState.Active -> R.string.dictate_send
            state.recording is RecordingState.Paused -> R.string.dictate_resume
            else -> /* getDictateButtonText() — language chip-derived */
        }
    },
    enabledResolver = { state ->
        state.pipeline !is PipelineUiState.Preparing
    }
)
```

This fixes the mutation order: a single `state.collect` triggers exactly one `slot.apply(view, state)` call that sets `text` and `isEnabled` in the same frame phase.

### §9.6 Delete/adapter/keep table (today's classes → future status)

<!-- FIX: Issue 2.0.9 – Lösch-Tabelle ergänzt; konsolidiert §9.1-§9.5-Migrations-Aussagen plus die in §9 nicht explizit migrierten Klassen (RecordingUiController, LanguageController, RecordingManager, BluetoothScoManager, JobExecutor) -->

| Today's class | Finally deleted in block | Temporarily as an adapter? |
|---|---|---|
| `RecordingStateController` | Block 1 (after migration §9.1) | no, deleted directly — tests are rewritten onto the new reducer |
| `KeyboardUiController` | Block 1 (state part §9.2) | partial: the state part moves immediately, the view part (`stepRows` management etc.) stays until Spec 2 |
| `RecordingUiController` | Block 5 (LayoutCatalog) | stays until then; a subset of its methods (main-button mutations) move into Spec 2 §9.4 — see the Spec 2 §9.4 table |
| `KeyboardStateManager` | Block 5 (LayoutCatalog) | stays until then; afterwards split up (see Issue 2.1.13) — `contentArea` / `isSmallMode` move into `DictateUiState`, the `applyVisibility` logic into the catalog |
| `LanguageController` | Block 1 (LanguageModule) | moves directly into the module reducer; no adapter phase needed |
| `RecordingManager` | never deleted (subsystem adaptee) | wrapped behind the `RecordingHardwareSubsystem` interface (see §4.7) |
| `BluetoothScoManager` | never deleted (subsystem adaptee) | wrapped behind the `BluetoothScoSubsystem` interface (see §4.7) |
| `JobExecutor` | never deleted | implements the `PipelineRunner` interface (see §4.7) |

**End-of-block cleanup check:** Per block a simple `grep` (or an
architecture-test assertion) checks that the classes marked as "finally deleted in
Block N" in this table no longer exist after Block N. Adapter phases
are explicitly allowed — they appear in the third column as `partial`.

---

## §10 Acceptance criteria

Block 2 (DictatePipelineService) counts as done when:

- [ ] Start a recording, switch the keyboard to Gboard, wait 30s, back to Dictate → the recording is still running, the pulse animation runs in the IME view, the pause button works.
- [ ] Start a pipeline, app-switch, wait 30s, back → the pipeline status is correctly restored, the notification shows the status.
- [ ] During the recording: a persistent notification is visible, showing the correct action buttons.
<!-- FIX: Phase-B S-5 (2026-05-13) – Tastatur-Wechsel-Survival + Mic-Indikator-Sichtbarkeit explizit als Acceptance verankert. Heute schweigt der Plan, was der User in der "Zwischenzeit" (IME-Service tot, Service läuft weiter mit Recording) sieht. Diese Acceptance schließt die User-Visibility-Lücke. -->
- [ ] **Phase-B S-5 mic indicator on keyboard switch:** while the IME service is dead (the user switched to Gboard) and the DictatePipelineService still holds an active recording, a microphone indicator is visible in the system tray (API ≥ 31 OS feature, costs us nothing — `FOREGROUND_SERVICE_TYPE_MICROPHONE` triggers it automatically). The persistent notification is visible and shows `[Pause] [Stop] [Send]` buttons; the user can cancel the recording via a notification action without switching back to the Dictate keyboard. Verified manually (E2E test with two keyboards) + via `DictatePipelineServiceForegroundSurvivalTest.kt` (verifies that `pipelineBinder.unbind()` + `serviceScope.isActive == true` → the recording state stays `Active`).
<!-- FIX: Phase-B S-5 (2026-05-13) – FGS-Killed-by-System (low memory) Recovery-Pfad acceptance. Plan dokumentiert START_NOT_STICKY ohne User-Visibility-Klausel; bei OOM-Kill verschwindet die Notification, Recording-Tonspur ist weg, User muss aktiv beim nächsten Bind das pendingSessions sehen. -->
- [ ] **Phase-B S-5 FGS-killed-by-system (low memory):** A service kill via `adb shell am kill` during an active recording simulates an OOM kill. `START_NOT_STICKY` means: the service is NOT automatically restarted. At the next IME `onCreateInputView`, the service-onCreate runs fresh, `PipelineRecovery.recover` loads the session from the DB (`RECORDING → FAILED` per R.16a, Block-3 acceptance), `state.pendingSessions` contains the interrupted session with `lastErrorType=UNKNOWN, lastErrorMessage="recording-interrupted-by-process-death"`. The user sees the FAILED entry in the resend path / history. Verified via `DictatePipelineServiceKillRestartTest.kt` (Robolectric or instrumented).
<!-- FIX: Phase-B S-5 (2026-05-13) – Channel-Erstellung-Reihenfolge expliziter Acceptance-Test. -->
- [ ] **Phase-B S-5 NotificationChannel-before-startForeground:** A unit test with a fresh app install (no channel exists) verifies that `DictatePipelineService.onCreate` creates the channel BEFORE `onStartCommand → startForeground(…)`. Test setup: `NotificationManager.deleteNotificationChannel("dictate_pipeline")` as a fixture, service boot, assert that `startForeground` throws no `IllegalArgumentException`. Test file: `DictatePipelineServiceChannelOrderTest.kt`.
<!-- FIX: Phase-B S-5 (2026-05-13) – FGS-5s-Frist als reproduzierbarer Test verankert. -->
- [ ] **Phase-B S-5 FGS boot < 5 s:** A Robolectric or instrumented test measures the time between the `Context.startForegroundService(...)` and the `startForeground(...)` call. Acceptance: **< 1 s p99** on an API-34 test device. The test covers §11.1.4 (5 s timeout mitigation) and protects against future regression (e.g. if someone adds a sync DB read in `onCreate` that consumes the deadline). Test file: `DictatePipelineServiceFgsBootLatencyTest.kt`.
<!-- FIX: Phase-B S-5 (2026-05-13) – NOTIF_ID-SoT-Test. -->
- [ ] **Phase-B S-5 NOTIF_ID consistency:** An architecture test (Kotlin reflection or ESLint-style lint) verifies that ONLY `PipelineNotificationCoordinator.NOTIF_ID` exists as a constant; no `const val NOTIF_ID` in the `DictatePipelineService.companion`. Protects against the recurrence of the `1001` vs `0xD1C7A7E` drift (Phase-B S-5 F-2).
<!-- FIX: Phase-B S-5 (2026-05-13) – runBlocking-Timeout-Test. -->
- [ ] **Phase-B S-5 onDestroy timeout:** `DictatePipelineServiceShutdownTimeoutTest.kt` — a mock module with a `terminate(services)` implementation that blocks for 5 s (`Thread.sleep(5000)`). Assert: `onDestroy` returns after < 2.5 s (2 s timeout + reserve). Verifies that a pathological module cannot hang the service-destroy path.
<!-- FIX: Phase-B S-5 (2026-05-13) – Multi-Bind-Acceptance verankert (§11.3.4). -->
- [ ] **Phase-B S-5 multi-bind:** `DictatePipelineServiceMultiBindTest.kt` with two ServiceConnections in one test — Bind-A in setup, Bind-B in body. Assert: both `onServiceConnected` receive the same `IBinder` instance (singleton contract); Unbind-B alone keeps the service alive; Unbind-A + `state.isAllTerminal() == true` leads to `onDestroy`.
<!-- FIX: Phase-B S-5 (2026-05-13) – Pre-Bind-Action-Pfad als Robustheits-Test verankert. -->
- [ ] **Phase-B S-5 pre-bind-action toast:** `DictateInputMethodServiceBindRaceTest.kt` simulates a click before `onServiceConnected`. Assert: `pipelineBinder == null` → `Toast.makeText(..., R.string.dictate_service_not_ready, ...)` is called, no crash, no silent drop. (§11.3.2a). Plus: the string resource `dictate_service_not_ready` is set up in `values/strings.xml` + `values-de/strings.xml`.
<!-- FIX: Phase-B S-5 (2026-05-13) – POST_NOTIFICATIONS-Prompt-Test. -->
- [ ] **Phase-B S-5 POST_NOTIFICATIONS prompt:** On an API-33+ test device, `OnboardingActivity` opens the permission prompt; on decline the banner in the IME view is visible during an active recording; clicking the banner opens `Settings.ACTION_APP_NOTIFICATION_SETTINGS`. (§11.5.1). Verified manually + via `OnboardingPostNotifPromptTest.kt`.
- [ ] `stopSelf()` takes effect: after insertion the notification disappears without further action.
- [ ] Force-stop of the app: at the next keyboard open the restart button with the pending session is shown.
- [ ] Manual restart-button click: the PipelineService starts again, the pipeline runs with the correct state.
<!-- FIX: Phase-C C-3 (2026-05-14) – C-2 F-3 cross-spec disambiguation: CancelPipeline → CancelRecording.
     Recording-Hardware (MediaRecorder + audio cache file) wird vom `RecordingModule` (§15.2) gehalten,
     NICHT vom PipelineModule. `Action.PipelineAction.CancelPipeline` routet via `moduleByLeafClass`
     an PipelineModule, dessen Reducer keinen `Effect.ReleaseMediaRecorder` emittieren kann (Effect
     lebt in RecordingModule.Effect, nicht in PipelineModule.Effect). Korrekte Action für den
     MediaRecorder-Release-Pfad ist `Action.RecordingAction.CancelRecording` — RecordingModule
     §15.2 Reducer-Arm `Preparing+CancelRecording` / `Active+CancelRecording` / `Paused+CancelRecording`
     emittiert `Effect.ReleaseMediaRecorder` + `Effect.DeleteAudioFile` synchron. Der §7.3-onDestroy-
     Pre-Cancel-Block (Phase-C C-2 F-3) wird in dieser Phase-C C-3-Iteration entsprechend disambiguiert. -->
- [ ] **MediaRecorder-release path (FIX Issue 3.0.11):** Service.onDestroy with an active recording (`state.recording !is RecordingState.Idle`) calls `orchestrator.dispatch(Action.RecordingAction.CancelRecording)` → `RecordingModule.reduce` sets `recording = Idle` and emits `Effect.ReleaseMediaRecorder` (+ `Effect.DeleteAudioFile`) → `runEffect` calls `services.recordingHardware.release()`. Verified via a `MediaRecorder.release()` mock spy in a unit test (or Robolectric); covers §13.5 G6 path A. (Spec 1 currently has no own test-strategy section; the test stub is created in the Block-2 implementation as `RecordingManagerReleaseTest.kt`.)
- [ ] **Pipeline-cancel path on onDestroy (complementary):** if NO recording is active, but a pipeline is active (`state.pipeline !is PipelineUiState.Idle`), Service.onDestroy calls `orchestrator.dispatch(Action.PipelineAction.CancelPipeline)` → `PipelineModule.reduce` sets `pipeline = Idle` and emits the pipeline-cleanup effects (DB status update, job cancel via `PipelineRunner.cancel(sessionId)`). Separates the two cancel domains along the module axes (Recording = hardware, Pipeline = DB+job).
<!-- FIX: Phase-B S-3 (2026-05-13) – Java-Brücke + KeyboardInputModule-Acceptance ergänzt. -->
- [ ] **Phase-B S-3 Java bridge `DictateUiStateObserver`:** The file `state/DictateUiStateObserver.kt` is set up (analogous to `core/ActiveJobRegistryObserver.kt`); at least one Java consumer (`DictateInputMethodService.java`) consumes the `DictateUiState` via it instead of via direct callbacks. Verified via the Robolectric test `DictateUiStateObserverTest.kt` (lifecycle bind works; STOP cancels, START replicates state).
- [ ] **Phase-B S-3 KeyboardInputModule (§15.6):** Backspace, enter and space button clicks trigger the correct InputConnection operations. Verified manually (open the keyboard, press the buttons, check the output) AND via the reducer unit test `KeyboardInputModuleTest.kt` (each action produces the matching effect). Additionally verifies that `orchestrator.dispatch(Action.KeyboardInputAction.Backspace)` does **not** return `DispatchOutcome.Unrouted` — i.e. the module is in `DictateModuleRegistry.all` (§4.8).
<!-- FIX: Phase-C C-1 (2026-05-14) – stale Z. 617 → Section-Anchor (Line-Drift). -->
- [ ] **Phase-B S-3 EffectFailure origin routing (§4.3 `dispatchInternal` Step 1a + 2):** An effect that throws (e.g. `RecordingModule.Effect.AllocateMediaRecorder` with a missing permission) triggers an `Action.EffectFailure(originModuleId = ModuleId.Recording, …)`; the emitting module (RecordingModule) has an explicit `EffectFailure` reducer arm that performs a state rollback (e.g. `Preparing → Idle`). Verified via `DictateOrchestratorTest.kt::effectFailure_routedBackToOriginModule()` with a fake module that throws in `runEffect`.

<!-- FIX: Phase-B S-1 (2026-05-13) – Block-1-Acceptance auf Block-1a/1b-Split (R.7) umgestellt. Vorher referenzierte den nicht-mehr-existierenden monolithischen PipelineStateManager. -->
Block 1a (quick wins in today's code) counts as done when:
- [ ] resend_btn visibility is computed only at ONE place (a central `predResendVisible` helper that replaces the 6 scattered mutation sites — see §11.2.2 Block-1a step 5).
- [ ] recordButton.text/isEnabled is set only at ONE place (a central resolver inside KeyboardUiController, then finally in the LayoutCatalog in Block 5).
- [ ] Service.onSingleRowModeToggled triggers KSM.refresh() (quick-win fix).
- [ ] Service.onAudioFocusToggled triggers KSM.refresh() (quick-win fix).

<!-- FIX: Phase-C C-1 (2026-05-14) – Modul-Zähler 12 → 13 aktiv (KeyboardInputModule §15.6). -->
Block 1b (DictateUiState + DictateOrchestrator + 13 active modules) counts as done when:
- [ ] `DictateUiStateStore` is the sole state SSOT — `RecordingStateController.state`, `KeyboardUiController.state`, `KeyboardStateManager.contentArea/isSmallMode` are eliminated. Verified via `grep` (see §9.6 end-of-block cleanup check).
- [ ] `DictateOrchestrator.dispatch(Action)` is the sole public mutation entry (F-8 Single Dispatch). Verified via an architecture test that rejects `_state.update` calls outside a module `reduce`.
- [ ] `PipelinePrefMirror.attach(store)` runs **BEFORE** `recovery.recover(store)` (encoded in the orchestrator `init`, §4.3). Verified via `DictateOrchestratorInitOrderTest.kt` with `FakePipelinePrefMirror` (records the attach order).
- [ ] **Atomicity `setSmallMode`:** the earlier sequential `KSM.setSmallMode` path is consolidated in a single `store.update` reducer call (`it.copy(layout = it.layout.copy(smallMode = enabled, contentArea = MAIN_BUTTONS))`). Subscribers never see a stale intermediate state. Verified in `LayoutModuleAtomicityTest.kt`.
- [ ] **PersistentList idiom:** all reducers that mutate `pendingSessions` use `.add` / `.removeAll` / `.removeAt`. Verified via the lint check `NoToMutableListOnPersistentList` (or a code-review checklist in Block 1b).
- [ ] **Initial-state-race fence (NEW Phase-B S-1):** a subscriber that attaches to `state.collect` immediately after `bindService` sees **at least** the pref-mirror values (not the `DictateUiState.initial()` default). Test: `DictateOrchestratorBootRaceTest.kt` with `FakeSharedPreferences`, asserts that the first `state.value` emission contains pref values.
<!-- FIX: Phase-B S-4 (2026-05-13) – 4 neue Acceptance-Klauseln: ProGuard-Robustheit, Vollständigkeits-Check, Cascade-Order, shutdown-Order. -->
<!-- FIX: Phase-C C-1 (2026-05-14) – stale Z. ~590 → Section-Anchor (Line-Drift). -->
- [ ] **Phase-B S-4 ProGuard robustness:** A release build (`./gradlew assembleRelease`) installs itself on an API-34 test device; after install an instrumented smoke test dispatches a concrete action (e.g. `Action.RecordingAction.StartRecording`) and asserts `DispatchOutcome.Applied` (not `Unrouted`). Verifies that the ProGuard keep rule from §4.3 (the ProGuard note block directly below the `DictateOrchestrator` snippet) was actually included in `app/proguard-rules.pro`. Test file `OrchestratorReleaseSmokeTest.kt` (`app/src/androidTest/...`).
- [ ] **Phase-B S-4 completeness check:** A targeted unit test removes the `KeyboardInputModule` from `DictateModuleRegistry.all` (a test-only copy of the list) and expects an init-time failure (`IllegalArgumentException` with "Fehlende Modul-Routing für Action-Subtypen: [KeyboardInputAction]"). Verifies that the completeness check (§4.8 init) takes effect. Test file `DictateModuleRegistryTest.kt`.
- [ ] **Phase-B S-4 cascade-order determinism:** A test with two mock modules `FakeAModule` and `FakeBModule` that both react to the same Idle→Active transition and each emit their own cascade action. Order in the `modules` list: A before B. The test verifies that the second cascade action (from B) sees the state **including** the first cascade mutation (from A). Test file `DictateOrchestratorCascadeOrderTest.kt`.
- [ ] **Phase-B S-4 shutdown order:** A test with `FakeModule` whose `terminate(services)` implementation asserts on `services.scope.isActive == true`. Verifies that `shutdown()` runs before `serviceScope.cancel()`. Plus: spy-based verification of the call order (`terminate` → `cancel`). Test file `OrchestratorShutdownOrderTest.kt` (in the Block-2 acceptance, because it is a service-lifecycle test).
- [ ] All existing use cases (UC1-UC7 + UC-extra-1 to UC-extra-10 from _pending-state-machine-visibility-owners.md §4) still work.
- [ ] **Resend-cooldown-visibility separation (FIX Issue 3.0.9):** `predResendVisible` does NOT reflect `resendCooldown` — the cooldown affects ONLY `enabledResolver` (disabled+alpha 0.4f), not `visibilityPredicate`. Verified in a Block-1 unit test (permutation `lastAudioExists=true` + `resendCooldown=true` → visibility=VISIBLE, enabled=false).
- [ ] **Cross-module-cascade verification (FIX Issue 3.0.10) — one acceptance point per §15.1 cascade entry:**
  - [ ] **PipelineModule.PipelineDone → ResendModule.MarkLastAudio:** after pipeline-done `state.resend.lastAudioExists = true` without further user input.
  - [ ] **PipelineModule.PipelineDone → LivePromptModule.ChainNext:** after pipeline-done the next LivePrompt step (if present) is triggered.
  - [ ] **AudioModule.AudioFocusLoss → RecordingModule.Pause:** on `AudioFocusLoss` during Recording.Active, `state.recording` switches to `Paused`.
  - [ ] **OverlayModule on Recording-Active + ImeViewHidden → ViewMode.HOVER:** correct ViewMode switch; HOVER is automatically taken.
  - [ ] **OverlayModule on PipelineDone (in HOVER) → ViewMode.KEYBOARD:** the "ghost widget" bug is structurally excluded (T7 cascade, cluster with 3.1.2).
  - [ ] **PipelineModule on reprocess-override → LanguageModule.Override:** the language is set.
  - [ ] **OverlayModule on ViewModeAction.CloseClicked (HOVER) → PipelineModule.Cancel + audio-file cleanup:** the audio file is deleted, the DB status `cancelled`, no notification entry remains (cluster with 3.1.7).
  <!-- FIX: Issue PENDING-3 / Spec-3 Reducer Simplified – OverlayAction.ResetSuppressBit Acceptance -->
  - [ ] **RecordingModule on Idle → Preparing → OverlayAction.ResetSuppressBit:** When `state.overlay.suppressAutoOverlayUntilNextSession == true` (the user previously closed the HOVER overlay) and the user dispatches `RecordingAction.StartRecording`, then after the dispatch `state.overlay.suppressAutoOverlayUntilNextSession == false` without further user action. Cancel in Preparing triggers NO renewed reset (the boundary test only covers `Idle → Preparing`). Verified in `RecordingModuleResetSuppressBitTest.kt` (Spec 3 §14.1).
  <!-- FIX: KG-RSB-2 Resolution (2026-05-11) – Regression-Test gegen Wiedereinführung des Self-Filters -->
  - [ ] **R.RSB-FIX-A self-cascade regression test:** `DictateOrchestratorTest.kt::recordingModule_idleToPreparing_emitsResetSuppressBit_viaSelfCascade()` — acts as a regression against the reintroduction of the self-filter `it.id != module.id` in §4.3 step 5. Setup: `suppressAutoOverlayUntilNextSession = true`, dispatch `RecordingAction.StartRecording`, assert: after the `dispatchInternal` return, `store.snapshot.overlay.suppressAutoOverlayUntilNextSession == false`. If the filter is accidentally re-added, this test fails red before the production bug reaches production.
  - Verified via a unit test per cascade path with mock modules (`FakeRecordingModule`, `FakeAudioModule`, etc.). (Spec 1 currently has no own test-strategy section; the test stub is created in the Block-1 implementation as `CrossModuleCascadeTest.kt`.)

Block 3 (DB persistence) counts as done when:
- [ ] The M3→M4 migration runs error-free on a test DB.
- [ ] All checkpoint hooks write correct DB updates.
- [ ] `recoverFromDb()` loads stuck sessions correctly.
- [ ] The cleanup policy (>7d old INSERTED sessions) runs at service start.
<!-- FIX: Issue 2.1.20 / R.16 + 2.1.21 / R.17 + 2.1.11 / R.9 + PENDING-2 – Acceptance-Erweiterungen -->
<!-- FIX: Phase-B S-1 (2026-05-13) – Test-Datei-Naming auf PipelineRecoveryTest umgestellt; Recovery-Logik lebt in `PipelineRecovery` (§4.6), nicht im monolithischen PipelineStateManager. -->
- [ ] **R.16a recovery from RECORDING:** Process killed during RECORDING → the session is post-recovery `status=FAILED`, `lastErrorType=UNKNOWN`, `lastErrorMessage="recording-interrupted-by-process-death"`, the audio file cleaned up (see §6.3). **Test:** `PipelineRecoveryTest.kt::recover_recordingPromoteToFailed_andCleansAudioFile()` — asserts: `dao.updateStatus(id, "FAILED")` called, `dao.updateError(id, "UNKNOWN", "recording-interrupted-by-process-death")` called, `File(audioPath).exists() == false`, `dao.clearAudioFilePath(id)` called.
- [ ] **R.16b recovery from TRANSCRIBING (file ok):** Process killed during TRANSCRIBING, audio file still present → the session is post-recovery `status=RECORDED` (downgrade) and appears in `pendingSessions`. **No** auto-resume (D4 / OPEN-4). **Test:** `PipelineRecoveryTest.kt::recover_transcribingDowngradeToRecorded_whenAudioPresent()` — asserts: `dao.updateStatus(id, "RECORDED")` called, `dao.updateError(id, null, null)` called (stale-error clear, see §6.3), the session is in `store.snapshot.pendingSessions`, NO `store.snapshot.pipeline = Running(id, …)` (no auto-resume).
- [ ] **R.16c recovery from TRANSCRIBING (file gone):** Process killed during TRANSCRIBING, the audio file is gone → `status=FAILED` with `lastErrorMessage="audio file vanished before transcription"`. **Test:** `PipelineRecoveryTest.kt::recover_transcribingPromoteToFailed_whenAudioMissing()` — asserts: `dao.updateStatus(id, "FAILED")` called, `dao.updateError(id, "UNKNOWN", "audio file vanished before transcription")` called, `dao.clearAudioFilePath(id)` called, the session NOT in `store.snapshot.pendingSessions`.
- [ ] **R.16 race test:** a parallel recording during recovery does not lead to a pendingSessions override (merge operation).
- [ ] **PENDING-2 migration CHECK:** MigrationTo4Test verifies (a) `INSERT … status='RECORDING'` and `… status='TRANSCRIBING'` are accepted, (b) an invalid value throws `SQLiteConstraintException`.
- [ ] **R.17 idempotency:** a replay after a view-recreate does not lead to a double insertion (DB idempotency test with `@Insert(onConflict = REPLACE)`).
- [ ] **R.17 PersistenceError path:** a DB crash mid-save leads to a `pendingSessions` failed marker, not to a state/DB inconsistency.
- [ ] **R.9 view-recreate contract (Robolectric):** rotation during an active pipeline — after `onFinishInputView` + `onCreateInputView` the subscriber is re-attached and the pipeline state is correctly rendered.
<!-- FIX: Phase-B S-2 (2026-05-13) – S-2-spezifische Acceptance-Punkte ergänzt. -->
- [ ] **Phase-B S-2 androidTest smoke:** `AndroidTestSetupSmokeTest.smoke()` runs green via `./gradlew connectedDebugAndroidTest` BEFORE the `MigrationTo4Test` implementation (verifies that the `androidTest/` directory + the `room-testing` dependency are correctly wired, see §11.7.0a).
- [ ] **Phase-B S-2 double protection HistoryAdapter:** The existing `try { SessionStatus.valueOf(...) } catch (IllegalArgumentException e) { RECORDED }` wrapper in `HistoryAdapter.java:131-135` **is kept** (downgrade compatibility) and the new `default:` branch in the `switch` (KG-SST-4) is **additionally** added. Unit test in HistoryAdapter Robolectric coverage: with `session.status = "UNBEKANNT"` the adapter shows the pending badge (RECORDED fallback via try/catch), with a fictitious 7th enum variant without a `case` the `default:` branch triggers `Log.wtf` + `GONE`. Disjoint failure modes (§6.1.3 double-protection block).
- [ ] **Phase-B S-2 cleanup order in the service idle-stop:** in the `stopSelf` path `dao.deleteInsertedOlderThan(cutoff)` runs BEFORE `cleanupOrphanedTerminalAudio()` BEFORE `stopSelf()`. Test: `DictatePipelineServiceCleanupOrderTest.kt` with a mock DAO + mock filesystem verifies the call order.
- [ ] **Phase-B S-2 SessionStatus KDoc update:** The `database/entity/SessionStatus.kt:6` KDoc reflects the new double-truth reality — the old statement "Runtime state (TRANSCRIBING, PROCESSING) is NOT stored here — it lives in ActiveJobRegistry" is false after M4 and must be reformulated to "RECORDING/TRANSCRIBING now live in DB + registry; the registry stays a performance cache + single-job lock (see plan §6.1.1 + KG-SST-5)". Verified via a code-review checklist.

<!-- FIX: Phase-B S-7 (2026-05-13) – Block 4 (AudioFileFactory) Acceptance neu eingeführt. Vorher
     war Block 4 acceptance über §4.11 KG-AFF-Marker verstreut; jetzt explizite Bullet-Liste. -->
Block 4 (AudioFileFactory + pre-dispatch allocation + legacy migration) counts as done when:
- [ ] **AudioFileFactory + default impl set up:** `core/AudioFileFactory.kt` (interface) +
  `core/CacheDirAudioFileFactory.kt` (default impl) with lazy init via `cacheDirProvider: () -> File`
  (§4.11.3 + KG-AFF-5). `allocate()` creates `cacheDir/audio/rec_{ts}_{uuid8}.m4a` paths;
  `cleanupOrphans(referencedPaths)` filters with a 60s cutoff (KG-AFF-4).
- [ ] **`CacheDirAudioFileFactoryTest` green:** 8+ unit tests (all in §4.11.9), incl. the KG-AFF-4
  cutoff test (`cleanupOrphans skips files younger than CUTOFF_GRACE_MS`) and the KG-AFF-5
  null-check test (`cacheDirProvider = { null }` throws `IllegalArgumentException`).
- [ ] **Pre-dispatch allocation in resolvers (R.2):** Spec 2 §8.5 `resolveRecordAction` and
  Spec 3 §3.1 `resolveOverlayRecordAction` call `services.audioFileFactory.allocate()` with
  an IOException-toast fallback. `Action.RecordingAction.StartRecording` carries the `audioFile` argument.
  Verified via `ResolverPreDispatchAllocateTest.kt` (a hand-written `FakeAudioFileFactory`).
- [ ] **`Effect.AllocateMediaRecorder` 3-arg:** definition + reducer use + effect-handler use are
  consistent (S-4 F-3, already incorporated); Block 4 verifies via `RecordingModuleTest` and
  an `assembleDebug` smoke (no compile errors).
- [ ] **String resource `dictate_storage_full`** set up in `values/strings.xml` (EN) and
  `values-de/strings.xml` (DE). Verified via `grep -rn "dictate_storage_full" app/src/main/res/`.
- [ ] **Immediate delete after persist (KG-AFF-1):** `PipelineOrchestrator.persistNewSession`
  calls `audioFile.delete()` after a successful `persistFromCache` (see the code snippet §4.11.6.1).
  Verified via `PipelineOrchestratorPersistTest.kt` (mock RecordingRepository + temp file).
- [ ] **`LegacyAudioFileMigration` (KG-AFF-2):** the class is set up, pref-flag idempotent, called in the
  service `onCreate` step 6.5. The DAO query has a
  `WHERE status NOT IN ('FAILED', 'CANCELLED', 'COMPLETED')` clause (Phase-B S-7 F-3 idempotency).
  Verified via `LegacyAudioFileMigrationTest.kt`: (a) the first run marks RECORDING/RECORDED/
  TRANSCRIBING sessions with the legacy path as FAILED + an `audio_file_path_legacy_purged` reason; (b)
  already-FAILED sessions with a different `last_error_message` are NOT overwritten; (c)
  the second run after the pref flag is set is a no-op; (d) the legacy file is deleted if present.
- [ ] **Recursive cache-clear (KG-AFF-3):** `PreferencesFragment.clearCacheRecursively` +
  cache-size/file-count helpers are recursive. Race protection against an active recording (Phase-B S-7
  F-10): the click is disabled + a toast on `state.recording !is Idle`. Verified via
  `PreferencesFragmentRecursiveClearTest.kt` (Robolectric).
- [ ] **Boot-cleanup hook:** `audioFileFactory.cleanupOrphans(referencedPaths)` runs once
  in the `serviceScope.launch(Dispatchers.IO)` in `Service.onCreate` (§4.11.5.1 step 8). DB read
  via `findAllAudioFilePaths()`; a fail-catch logs WARN, does not block the boot. Verified via
  `DictatePipelineServiceBootOrphanCleanupTest.kt`.
- [ ] **Recovery coupling for v4 statuses (Phase-B S-7 F-5):** the recovery path handles
  RECORDED/RECORDING/TRANSCRIBING statuses correctly with respect to file existence (see §4.11.6
  recovery-coupling table post-S-7). An extension of the existing R.16a/b/c tests
  (Block-3 acceptance) — no new test, only verification that the table is complete.
- [ ] **`RecordingModule.reduceFailure` for AllocateMediaRecorder (Phase-B S-7 F-7):** state
  rollback `Preparing → Idle` + `Effect.ReleaseMediaRecorder` + `Effect.DeleteAudioFile`. Test:
  `RecordingModuleFailureTest::allocateFailure_rollsBackToIdle()` with `FakeServices` and
  an `AllocateMediaRecorder` effect throw.
- [ ] **Service-field removal:** the `DictateInputMethodService.audioFile` field l. 208 + l. 1612
  allocation line are deleted (verification via `grep -rn "audioFile" app/src/main/java/`).
  The `recordingStateController.startRecording(audioFile, ...)` call moves into
  `RecordingModule.runEffect(Effect.AllocateMediaRecorder)`.

---

## §11 Research TODOs for the agent — detail answers

### §11.1 Foreground-service implementation

#### §11.1.1 AndroidManifest.xml — exact diff

**Today (`app/src/main/AndroidManifest.xml`):**
- Permissions `RECORD_AUDIO, INTERNET, VIBRATE, BLUETOOTH, MODIFY_AUDIO_SETTINGS` (l. 5-9).
- Only one `<service>` entry: the IME service `DictateInputMethodService` (l. 29-40).

**Diff (additive, do NOT delete anything):**

```xml
<!-- in <manifest>, NACH Z. 9 (vor <queries>): -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- in <application>, NACH Z. 40 (nach IME-<service>-Block): -->
<service
    android:name=".core.DictatePipelineService"
    android:exported="false"
    android:foregroundServiceType="microphone"
    android:description="@string/dictate_pipeline_service_description" />
```

Rationale per line:
- `FOREGROUND_SERVICE` — mandatory from Android 9 (API 28) for every FGS.
- `FOREGROUND_SERVICE_MICROPHONE` — mandatory from Android 14 (API 34) when `foregroundServiceType="microphone"`. Since `targetSdk = 35` (`build.gradle:14`) we must declare it.
- `POST_NOTIFICATIONS` — mandatory from Android 13 (API 33) for every user-visible notification. Must be requested at runtime (see §11.5.1).
- `android:foregroundServiceType="microphone"` — Without this value Android 14+ throws `ForegroundServiceTypeNotAllowedException` at start. We use "microphone" because recording runs during the service. For pure pipeline phases (after stop) the type is strictly speaking wrong — there are two options for that:
  1. During recording: `microphone` type. After recording stop: switch to `dataSync` via `startForeground(notifId, notif, FOREGROUND_SERVICE_TYPE_DATA_SYNC)`. Requires the `FOREGROUND_SERVICE_DATA_SYNC` permission additionally.
  2. For the entire service lifetime: leave `microphone` declared — Android allows that as long as microphone access is at least potentially active in the lifecycle. Simpler; accepted for our use cases.
  
  → **Decided: Option 2** (simpler, minimal maintenance debt).
- `android:exported="false"` — the service is only for the app itself (no IPC).
- `android:description` — a new string resource to create: `@string/dictate_pipeline_service_description = "Background service for the dictation pipeline"`.

<!-- FIX: Phase-B S-5 (2026-05-13) – SYSTEM_ALERT_WINDOW-Cross-Link verankert. Plan hat sie
     in Spec 3 §5.7 als "Manifest-Eintrag" — Block 2 ist der Service-Manifest-Diff, also
     gehört der Cross-Link hier, damit der Implementer beide Permission-Sets in EINEM
     Manifest-Patch landet (keine getrennten Commits). -->
**SYSTEM_ALERT_WINDOW (cross-link to Spec 3 §5.7):** The floating-overlay feature (Block 6)
additionally needs `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />`.
This permission is install-time-granted on API < 23, on API ≥ 23 a special permission via
`Settings.canDrawOverlays()`. The Block-2 manifest diff can ALREADY DECLARE the permission
(no-op until the overlay feature is wired in Block 6) — this eliminates a second
manifest commit + makes the Phase-1 / Phase-2 separation less brittle.

<!-- FIX: Phase-C C-2 (2026-05-14) – Caption "drei Permission-Gruppen" → "vier Permission-Einträge,
     drei Service-Permissions + eine Overlay-Permission". Vorher Off-by-One-Counter durch
     SYSTEM_ALERT_WINDOW-Ergänzung. -->
**Block-2 manifest diff (final, four permission entries — three service permissions + the pre-declared overlay permission):**

```xml
<!-- Block 2 (Service-Permissions, drei Einträge) -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<!-- Block 6 (Overlay-Permission), aber im Block-2-Manifest-Diff vorab deklariert: -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

#### §11.1.2 Notification.Builder — concrete implementation

**Channel setup** (in `DictatePipelineService.onCreate` — see §7.3 + §11.1.4 for the
binding call order: `ensureNotificationChannel()` runs **before**
`startForegroundCompat(…)`, otherwise `startForeground` with an invalid channel → an ANR risk
not relevant on API < 26, but on API ≥ 26 `NotificationManager` throws an
`IllegalArgumentException`):

<!-- FIX: Phase-B S-5 (2026-05-13) – NOTIF_ID-Doppel-Definition gestrichen. NOTIF_ID lebt
     im PipelineNotificationCoordinator-companion (§7.4), Service referenziert es per
     `PipelineNotificationCoordinator.NOTIF_ID`. Zweite Definition mit anderem Wert
     (1001 vs 0xD1C7A7E in §7.4) hätte zur sticky-FGS-Notification + überlagerter
     normaler Notification geführt. -->
```kotlin
companion object {
    private const val CHANNEL_ID = "dictate_pipeline"
    // NOTIF_ID lebt im PipelineNotificationCoordinator (§7.4) — Service referenziert
    // `PipelineNotificationCoordinator.NOTIF_ID` direkt.
}

private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return  // pre-O kein Channel nötig
    val mgr = getSystemService(NotificationManager::class.java)
    if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
    val channel = NotificationChannel(
        CHANNEL_ID,
        getString(R.string.dictate_pipeline_channel_name),  // "Diktier-Pipeline"
        NotificationManager.IMPORTANCE_LOW   // kein Sound, kein Heads-Up
    ).apply {
        description = getString(R.string.dictate_pipeline_channel_description)
        setShowBadge(false)
        setSound(null, null)
        enableVibration(false)
        enableLights(false)
        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
    }
    mgr.createNotificationChannel(channel)
}
```

**Notification builder per state variant** (see the §7.3 table):

```kotlin
private fun buildNotification(state: DictateUiState): Notification {
    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_baseline_mic_20)        // gleicher Icon wie record_btn
        .setContentTitle(getString(R.string.dictate_pipeline_notif_title))
        .setContentText(notifSubtitleFor(state))
        .setOngoing(true)                                    // nicht wegswipebar
        .setSilent(true)                                     // kein Sound bei Update
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(buildContentIntent())              // Click → Tastatur öffnen (siehe §11.5.3)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    // Pro State eine andere Action-Liste — max. 3 Actions wegen Compact-Style.
    when {
        state.recording is RecordingState.Active -> builder
            .addAction(actionPause())
            .addAction(actionStop())
            .addAction(actionSend())
        state.recording is RecordingState.Paused -> builder
            .addAction(actionResume())
            .addAction(actionStop())
            .addAction(actionSend())
        state.pipeline is PipelineUiState.Running -> builder
            .addAction(actionCancel())
        state.hasPendingInsertion() -> builder
            .addAction(actionInsert())
            .addAction(actionDiscard())
    }
    return builder.build()
}

private fun actionPause(): NotificationCompat.Action =
    NotificationCompat.Action.Builder(
        R.drawable.ic_baseline_pause_24,
        getString(R.string.dictate_action_pause),
        pendingIntent(ACTION_PAUSE)
    ).build()

// analog: actionResume, actionStop, actionSend, actionCancel, actionInsert, actionDiscard

private fun pendingIntent(action: String): PendingIntent {
    val intent = Intent(this, DictatePipelineService::class.java).setAction(action)
    return PendingIntent.getService(
        this, action.hashCode(), intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
```

<!-- FIX: Phase-B S-5 (2026-05-13) – §11.1.2 onStartCommand-Snippet auf F-11 (PipelineActionRouter + DictateOrchestrator) umgestellt. Pre-F-11-Snippet rief `stateManager.pauseRecording()` etc. — `PipelineStateManager` existiert nach 2026-05-10 nicht mehr. SoT für onStartCommand-Pfad ist §7.3 (PipelineActionRouter.dispatch). NOTIF_ID-Wert: siehe Konsolidierung weiter unten. -->
`onStartCommand` reacts to the action strings — the action-mapping logic lives in the
`PipelineActionRouter` (§7.5), the service here is only the lifecycle owner:

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // 1. Action-Intent vom Notification-Button (falls vorhanden) — pure Mapping auf Action.
    intent?.let { actionRouter.dispatch(it) }
    // 2. FGS-Notification: startForeground() läuft synchron BEVOR irgendeine Coroutine —
    //    siehe §11.1.4 (5-Sekunden-Timeout). buildInitial() ist pure State→Notification-
    //    Render, kein DB-Hop, < 5 ms.
    startForegroundCompat(notifCoordinator.buildInitial())
    // 3. Reaktive Updates der Notification (throttled, §7.4). stopSelf-Hook bei Terminal.
    notifCoordinator.startReactiveUpdates(::stopSelfWhenTerminal)
    return START_NOT_STICKY
}

/**
 * API-34+-Variante mit explizitem `FOREGROUND_SERVICE_TYPE_MICROPHONE` — Pflicht,
 * sobald `targetSdk >= 34` (`build.gradle:14`, verifiziert per Code-Read 2026-05-13).
 * Auf < 34 ist der Type-Parameter nicht verfügbar; das System ignoriert den
 * deklarierten Manifest-Type effektiv (Backward-Compat).
 */
// FIX: Phase-C C-2 (2026-05-14) – NOTIF_ID-Reference qualifiziert via Coordinator-companion
// (SoT-Konsolidierung, §11.1.2 NOTIF_ID-Konsolidierung-Block + §10 Acceptance).
private fun startForegroundCompat(notif: Notification) {
    val notifId = PipelineNotificationCoordinator.NOTIF_ID
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {  // API 34
        startForeground(notifId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    } else {
        startForeground(notifId, notif)
    }
}
```

> **NOTIF_ID consolidation (Phase-B S-5):** §7.4 (`PipelineNotificationCoordinator`)
> defines `companion object { const val NOTIF_ID = 0xD1C7A7E }` — the coordinator is
> the SoT. The older double definition in §11.1.2 (`private const val NOTIF_ID = 1001`)
> is **removed**, the service references `PipelineNotificationCoordinator.NOTIF_ID`.
> Two definitions of different values (`1001` vs `0xD1C7A7E`) would have led to the
> race-bug class "the service calls `startForeground(1001, …)`, the coordinator calls
> `nm.notify(0xD1C7A7E, …)` → two separate notifications" — one stays
> as the sticky FGS notification, the other is overlaid as a regular notification.

#### §11.1.3 startForegroundService vs. startService — version differential

| API | Behavior |
|---|---|
| `< 26 (Oreo)` | `startService` is sufficient; the FGS concept does not exist in this strictness. |
| `>= 26` | `startForegroundService` mandatory — without it Android throws `IllegalStateException` when `startForeground` is called from the background. |
| `>= 31 (S)` | Additional restrictions: an FGS start from the background only for defined cases (foreground app, MediaProjection, etc.). IMEs are privileged (through the system's `BIND_INPUT_METHOD` call). |
| `>= 34` | `foregroundServiceType` must be declared + passed along with `startForeground(id, notif, type)`. |

**We start the service in `DictateInputMethodService.onCreateInputView` OR at the first recording click**, depending on the strategy (see §11.3.1). The IME service is foreground-privileged (in contrast to background apps), so we may start the FGS at any time.

#### §11.1.4 5-second timeout for `startForeground`

If after `Context.startForegroundService(intent)` `startForeground(id, notification)` is not called within 5 s, the system throws `ForegroundServiceDidNotStartInTimeException` (API 26+) and ends the service with ANR-like behavior.

<!-- FIX: Phase-C C-2 (2026-05-14) – stale `stateManager.state.value` → `orchestrator.state.value`
     (Pre-F-11-Drift; SoT für die State-Quelle ist §4.3 DictateOrchestrator.state). -->
**Mitigation:** The service's `onStartCommand` calls `startForegroundCompat()` synchronously as the very first action after the action-router forward. The notification builder (`PipelineNotificationCoordinator.buildInitial()`) must NOT make blocking DB calls — it only reads from `orchestrator.state.value`, which is in memory.

<!-- FIX: Phase-B S-1 (2026-05-13) – §11.1.4 Snippet auf DictateOrchestrator umgestellt. Recovery wird vom Orchestrator-Konstruktor selbst async gestartet (§4.3 init); kein separater scope.launch nötig. -->
```kotlin
override fun onCreate() {
    super.onCreate()
    ensureNotificationChannel()                              // synchron, in-memory
    // Composition Root (siehe §7.3 für volles DI-Wiring).
    // Orchestrator-`init` ruft synchron prefMirror.attach(store) und startet
    // asynchron recovery.recover(store) — DB-IO blockt FGS-Start nicht.
    orchestrator = DictateOrchestrator(serviceScope, store, servicesFactory, prefMirror, recovery)
    startForegroundCompat(orchestrator.state.value)          // synchron, < 50ms
    // Reaktive Updates der Notification starten — NotificationCoordinator
    // throttled auf 1 Update/300 ms (§7.4).
    notifCoordinator.startReactiveUpdates(::stopSelfWhenTerminal)
}
```

<!-- FIX: Phase-B S-1 (2026-05-13) – §11.2 auf Block-1a/1b-Split (R.7) + F-11-Modular-Orchestrator umgestellt. Pre-F-11-Text rahmte alles als monolithischen PipelineStateManager — überholt seit 2026-05-10. -->
### §11.2 Block-1a / Block-1b / Block-2 / Block-3 — implementation

#### §11.2.1 Concrete code pointers per migration step

See §9.1 – §9.5 above — all migration sites now have `file:line` pointers and tables.

#### §11.2.2 Migration order (Block-1a → 2 → 1b → 3, see main plan §4)

**Block 1a — quick wins in today's code (no module pattern, compiles green)**

Goal: bring today's system onto a single-owner visibility basis, WITHOUT introducing the module architecture. A precondition for Block 1b (the module architecture can only live in the PipelineService container, so after Block 2).

1. **Consolidate the `predResendVisible` helper** (Spec 2 §13.5 Gap 5 + main plan R.7) — a new top-level function, all 6 scattered `resendButton.visibility = …` sites read it. A quick win, no state refactor.
2. **Resolve the `recordButton.text/isEnabled` hybrid** — a central resolver in `KeyboardUiController` (not yet a LayoutCatalog) that replaces the 8 scattered sites in §13.4.1.
3. **Quick-win fixes** (Block-1a acceptance): `onSingleRowModeToggled` → a `KSM.refresh()` trigger, `onAudioFocusToggled` → likewise. Today `KSM.refresh()` is missing after `mainButtonsController.refreshAudioFocusIcon` — see `DictateInputMethodService.java:2664-2687`.

**Block 2 — introduce DictatePipelineService (service class + bound binder, NO DB schema change)**

<!-- FIX: Phase-B S-5 (2026-05-13) – Block-2-Sub-Schritte erweitert um (a) NotificationChannel-Setup als eigenen Schritt vor startForeground (b) Bind-Site-Korrektur onCreateInputView statt onCreate (c) Klärung dass Block-2 noch KEINEN DictateOrchestrator / Module hat (das ist Block 1b) — Block 2 verdrahtet das Skelett mit einem Stub-Composition-Root, der Block-1b dann ersetzt. -->
1. **Create the service class** (`core/DictatePipelineService.kt`) — skeleton, `LocalBinder`, `onCreate`/`onStartCommand`/`onBind`/`onDestroy` (see §7.3). In Block 2 the Composition Root is still a **stub** — `DictateUiStateStore(DictateUiState.initial())` plus a minimal forward to the existing controllers (the PipelineState subscribe is only fully wired in Block 1b). `audioFileFactory` is Block 4 — in Block 2 either a dummy field (`lateinit`, wired in Block 4) or the reference in the `ModuleServicesFactory` wiring is comment-fenced as `// TODO Block 4`.
2. **NotificationChannel setup as its own private method** (`ensureNotificationChannel()`, §11.1.2) — called SYNCHRONOUSLY in `onCreate` first after `super.onCreate()` (before any DI wiring). On API < 26 a no-op.
3. **Bound-connection setup in the IME** (see §11.3.1) — `bindService` in **`onCreateInputView`** (NOT the IME's `onCreate`), `unbindService` in `onDestroy`. Rationale: the latency argument (50-200 ms first-bind absorbed in the onCreateInputView inflate window).
4. **Wire the notification + startForeground** (§11.1.2 + §7.4). `startForeground` runs in `onStartCommand` (NOT `onCreate`!) — the Android lifecycle contract (startForegroundService → onCreate → onStartCommand → the first `startForeground` call MUST be in onStartCommand or onCreate, before the 5 s timeout).
5. **Extend the manifest** (§11.1.1) — permissions FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, POST_NOTIFICATIONS plus the service entry with `foregroundServiceType="microphone"`. Plus SYSTEM_ALERT_WINDOW (Spec 3 §5.7) — thematically belongs to Block 6, but the manifest diff may land jointly in Block 2, because declarative permissions have no code path (no-op until the overlay feature is wired in Block 6).
6. **Add a POST_NOTIFICATIONS runtime-permission prompt** in onboarding (§11.5.1) — `OnboardingActivity` already exists today (manifest l. 53); an `ActivityResultLauncher` with a permission request for `Manifest.permission.POST_NOTIFICATIONS` (API ≥ 33), companion text "Dictate shows a persistent notification with recording controls." Plus the Block-2 acceptance "onboarding shows the permission prompt on API 33+".
7. **JobExecutor init** moves from the IME `onCreate` (l. 389) into `Service.onCreate` (G7 in §13.5). ⚠ Is called with the **old** `PipelineOrchestrator` (audio pipeline runner), NOT with the new `DictateOrchestrator` — naming convention §1.x.

<!-- FIX: Phase-C C-1 (2026-05-14) – Modul-Zähler 12 → 13 aktiv (KeyboardInputModule §15.6 ist Phase-1-Pflicht). -->
**Block 1b — DictateUiState + DictateOrchestrator + 13 active modules (in the PipelineService container)**

Goal: all state mutations that are distributed across 3 classes today (RecordingStateController / KeyboardUiController / KeyboardStateManager) are consolidated into one hierarchical `DictateUiState` class + `DictateUiStateStore`. Mutations run exclusively through `DictateOrchestrator.dispatch(Action)` → module reducer.

Order of the sub-steps:

<!-- FIX: Phase-C C-1 (2026-05-14) – Sub-State-Felder-Zähler präzisiert. §3-Tabelle Z. 289 spricht
     von "13 State-Achsen (= Sub-State-Felder)". Davon sind 12 als eigene Sub-State-Klassen
     modelliert (RecordingState/PipelineUiState sealed, der Rest data class); das 13. Feld
     `pendingSessions` ist ein direktes PersistentList-Feld ohne Wrapper. Plus `lastResultNeedsManualPaste`. -->
1. **Create the DictateUiState data type** (new file `state/DictateUiState.kt`) — a pure data class with 12 sub-state types (`RecordingState` sealed, `PipelineUiState` sealed, `AudioState`/`LayoutState`/… data classes) + `pendingSessions: PersistentList<PendingSession>` as the 13th axis + 1 top-level bool (`lastResultNeedsManualPaste`). See the §3 table.
2. **Create the DictateModule interface + DictateOrchestrator + ModuleServicesFactory** (§4.2 / §4.3 / §4.7). A skeleton with an `Action` sealed class (empty), no concrete modules yet.
3. **Implement RecordingModule** (§15.2) — the `RecordingStateController.kt:128-321` logic moves into `RecordingModule.reduce + runEffect`. Existing `RecordingStateController.Callback` receivers are rebuilt onto `state.collect` subscribers. ⚠ Note: `RecordingManager` and `BluetoothScoManager` have callback back-refs to the controller — these must be pulled along (module effect paths dispatch `Action.RecordingAction.MediaRecorderReady` etc. via `services.emitAction`).
4. **Implement PipelineModule** — the `KeyboardUiController.kt:147-353` logic moves into `PipelineModule.reduce + runEffect`. The public-API methods on KeyboardUiController are replaced via `orchestrator.dispatch(Action.PipelineAction.X)`.
5. **resend_btn predicate (final)** — the `predResendVisible` helper from Block 1a is moved into the `LayoutCatalog.RESEND` slot (Spec 2 §3.2 + §13.1). Until Block 5 the subscriber runs transitionally in the IME service:
   ```kotlin
   // Transitional in Block 1b (wird in Block 5 durch LayoutCatalog ersetzt):
   orchestrator.state.collect { state ->
       val visible = state.resend.lastAudioExists && state.resend.resendEnabled
           && state.recording is RecordingState.Idle
           && state.pipeline is PipelineUiState.Idle
       resendButton.visibility = if (visible) View.VISIBLE else View.GONE
   }
   ```
6. **Implement LayoutModule** — `KeyboardStateManager.contentArea/isSmallMode` move into `LayoutState` (sub-state). `setSmallMode(enabled)` becomes an atomic `reduce` call that sets `LayoutState.copy(smallMode = enabled, contentArea = MAIN_BUTTONS)` atomically in one mutation — eliminates today's sequential 2-step write.
<!-- FIX: Phase-C C-1 (2026-05-14) – Pref-Zähler 15 → 19 (zählt §4.5 initialMirror-Block exakt:
     layout 3 + audio 3 + resend 1 + features 4 + theming 4 + overlay 4 = 19, konsistent mit
     Phase-B S-4 Hinweis "Phase 1 — hardcodierte Mappings für 19 Prefs"). -->
7. **PrefMirror wiring (§4.5):** `PipelinePrefMirror` mirrors the 19 UI-state-relevant prefs into the sub-state classes. Attached synchronously in `DictateOrchestrator.init`.
8. **Recovery wiring (§4.6):** `PipelineRecovery` loads `pendingSessions` from the DB into the `store`. Started async (`scope.launch`) in `DictateOrchestrator.init` — **AFTER** `prefMirror.attach`.

**Block 3 — DB persistence (schema migration M3→M4)**

<!-- FIX: Phase-B S-2 (2026-05-13) – Block-3-Sub-Schritte umstrukturiert: androidTest-Setup als eigener Schritt 0 (substantieller Test-Infrastruktur-Aufbau, wurde vorher als Implementation-Detail von Schritt 2 versteckt). -->

0. **Set up the androidTest infrastructure (NEW, its own step — see §11.7.0a below):**
   - Create the directory `app/src/androidTest/java/net/devemperor/dictate/database/migration/` anew (does not exist today — verified via `ls app/src/`).
   - Extend `gradle/libs.versions.toml` with `room-testing = { module = "androidx.room:room-testing", version.ref = "room" }` and `androidx-test-runner = { group = "androidx.test", name = "runner", version = "1.5.2" }`, `androidx-test-rules = { group = "androidx.test", name = "rules", version = "1.5.0" }`.
   - Extend `app/build.gradle:74-75` (in l. 74-75, AFTER `androidTestImplementation libs.espresso.core`):
     ```gradle
     androidTestImplementation libs.room.testing
     androidTestImplementation libs.androidx.test.runner
     androidTestImplementation libs.androidx.test.rules
     ```
   - The test runner `androidx.test.runner.AndroidJUnitRunner` is already configured in `defaultConfig.testInstrumentationRunner` (l. 18) — no change needed.
   - Verification: an empty smoke test (`Trivial androidTest with @Test fun smoke() = assertTrue(true)`) must run green before the MigrationTo4.kt implementation, to prove that the directory setup works.
1. **Extend `SessionStatus.kt`** by `RECORDING` + `TRANSCRIBING` (see §6.1). Adjust the KDoc at `SessionStatus.kt:6` ("Runtime state … lives in ActiveJobRegistry") to the new double-truth reality ("RECORDING/TRANSCRIBING now live in DB + registry; the registry stays a performance cache + single-job lock", see KG-SST-1 + KG-SST-5).
2. **Create MigrationTo4.kt** with the table-recreate strategy + CHECK extension (see §6.1).
3. **Schema version + addMigrations** in `DictateDatabase.kt` (§6.1). The ksp build automatically generates `app/schemas/.../DictateDatabase/4.json` — this file MUST be part of the commit (a code-review anchor for the schema diff).
4. **Add the `SessionEntity.insertedAt` field** (§6.1). NO additional `Index("inserted_at")` (see the rationale under §6.1).
5. **`SessionDao` methods:** `markInserted` / `findPendingInsertion` / `deleteInsertedOlderThan` / `getSessionsByStatuses` + `findAllAudioFilePaths` + `markLegacyAudioSessionsFailed` (§6.1 + §6.3 + §4.11) + `findOrphanedTerminalAudio` + `clearAudioFilePathBulk` (§6.3.1 KG-SST-2).
6. **`SessionManager` methods:** `transitionRecording` + `transitionRecorded` + `transitionTranscribing` + `markInserted` in addition to the existing `finalize*` methods (§6.1).
7. **Checkpoint hooks per module** (§6.2 table): RecordingModule + PipelineModule emit DAO calls as side effects (`Effect.PersistStatus(sessionId, status)` etc.). **Order: DB write BEFORE `ActiveJobRegistry.update`** (KG-SST-5, see §6.2 persistence contract R.17).
8. **`PipelineRecovery.recover()`** reads pending sessions: RECORDING→FAILED+cleanup, TRANSCRIBING→RECORDED downgrade-or-FAILED, loads them into `state.pendingSessions` (§6.3).
9. **Cleanup policy** at the service idle-stop: `dao.deleteInsertedOlderThan(now - 7d)` AND `cleanupOrphanedTerminalAudio()` (§6.3.1 KG-SST-2) once before `stopSelf()`. Order: `deleteInsertedOlderThan` → `cleanupOrphanedTerminalAudio` → `stopSelf`.
10. **Extend the `HistoryAdapter.java` `switch`** by `case RECORDING:` + `case TRANSCRIBING:` + `default: Log.wtf + GONE` (§6.1.3). The existing try/catch wrapper l. 131-135 is kept (downgrade compatibility, see the double-protection explanation in §6.1.3).
11. **Extend the `ResendStatusDispatcher.kt` `when`** by `RECORDING, TRANSCRIBING → ResendAction.NoOp` (§6.1.3). Kotlin `when` is exhaustive — a build error without these branches.
12. **`HistoryDetailActivity.java:287-299`** — NO code change (the existing whitelist `RECORDED || FAILED || CANCELLED || COMPLETED` automatically excludes RECORDING/TRANSCRIBING, see the §6.1.3 consumer table).
13. **Lint setup** (KG-SST-4): extend `app/build.gradle` with a `lint { error += "EnumSwitch"; abortOnError true }` block. Run `./gradlew lint` first, review the baseline findings.
14. **Extend `strings.xml`**: `dictate_status_recording`, `dictate_status_transcribing` (§6.1.3 patch).

<!-- FIX: Phase-B S-7 (2026-05-13) – Block 4 (AudioFileFactory + Pre-Dispatch-Allocation) explizit als
     eigene Sub-Schritt-Sequenz dokumentiert. §4.11 hat die kanonische Spec; hier die
     Implementer-Reihenfolge der Sub-Schritte, damit Block 4 nicht implizit über die §4.11-Markers
     verstreut sein muss. -->
**Block 4 — AudioFileFactory + pre-dispatch allocation + legacy migration (see §4.11)**

Goal: decouple the allocation of the audio cache files from the MediaRecorder setup (pure-reducer contract R.2,
multi-job collision freedom R.8); legacy `cacheDir/audio.m4a` cleanup; recursive "Clear cache"
logic in PreferencesFragment.

Order of the sub-steps:

1. **Create the `AudioFileFactory` interface + `CacheDirAudioFileFactory`** (`core/AudioFileFactory.kt`,
   `core/CacheDirAudioFileFactory.kt`) — see code snippets §4.11.2 + §4.11.3. The KG-AFF-4 cutoff filter
   + KG-AFF-5 `requireNotNull` in the lazy init implemented directly along.
2. **`ModuleServices.audioFileFactory` field** in `ModuleServices` (§4.7) + in the `ModuleServicesFactory`
   wiring in `DictatePipelineService.onCreate` (§4.11.5.3 code diff). Phase-B S-7 reminder:
   `services` is additionally passed through to `ImeViewBackend`/`OverlayBackend` (Spec 2 §6 / Spec 3
   §4.2 post-S-7) — both backends need `services.audioFileFactory` for pre-dispatch-allocate.
3. **Extend the `resolveRecordAction` resolver** (Spec 2 §8.5) — a 2-arg signature `(state, services) ->
   Action?`, calls `services.audioFileFactory.allocate()`; on `IOException` → `services.toastSink.show
   (R.string.dictate_storage_full)` → `null`. Analogously `resolveOverlayRecordAction` (Spec 3 §3.1
   post-S-7).
4. **Extend the `ButtonSlot.actionResolver` type** (Spec 2 §3.2) to `(DictateUiState, ModuleServices)
   -> Action?`. Mechanically extend all existing `{ Action.X }` and `{ state -> Action.X }` lambdas in
   `LayoutCatalog` slots (Spec 2 §8.1-§8.4 + Spec 3 §3.1): `{ Action.X }` →
   `{ _, _ -> Action.X }`, `{ state -> Action.X }` → `{ state, _ -> Action.X }`. A compile error
   on the first `./gradlew assembleDebug` shows every site.
5. **`wireStaticHandlers` (Spec 2 §6) + `wireStaticOverlayHandlers` (Spec 3 §4.2)** call
   `slot.actionResolver(state, services)` instead of the 1-arg variant (Phase-B S-7).
6. **Ensure the `StartRecording` action 2-arg constructor** (Spec 2 §3.3): `data class
   StartRecording(target: InsertionTarget, audioFile: File)`. When reading Spec 2 check that no
   1-arg call site remains (Spec 3 §3.1 had one — fixed in Phase-B S-7).
7. **Add the string resource `dictate_storage_full`** in `app/src/main/res/values/strings.xml`
   (e.g. `"Cache full — recording cannot start."`) and in `values-de/strings.xml` with the German
   translation (`"Cache voll — Aufnahme kann nicht starten."`). **A mandatory task — missing
   today, otherwise blocks the Block-4 build.**
8. **`Effect.AllocateMediaRecorder` signature 3-arg** (Spec 1 §15.2): `data class AllocateMediaRecorder
   (target, useBluetooth, audioFile)` — see S-4 F-3 fix (already incorporated); Block 4 only
   verify.
9. **`PipelineOrchestrator.persistNewSession` patch** (`core/PipelineOrchestrator.kt:854-857`):
   immediate delete of the cache file after a successful `persistFromCache` (KG-AFF-1, code snippet
   §4.11.6.1).
10. **Create `LegacyAudioFileMigration`** (`migration/LegacyAudioFileMigration.kt`) with the code
    from §4.11.6.2 (KG-AFF-2). The call is in `DictatePipelineService.onCreate` step 6.5 (§4.11.5.1
    sequence table). Phase-B S-7 reminder: the DAO query now has
    `WHERE audio_file_path = :legacyPath AND status NOT IN ('FAILED', 'CANCELLED', 'COMPLETED')` —
    protects existing failure information from being overwritten.
11. **Boot cleanup in the service `onCreate`** — `audioFileFactory.cleanupOrphans(referenced)` via
    `serviceScope.launch(Dispatchers.IO)` with a `findAllAudioFilePaths()` DB read (§4.11.5.3 step 5
    + §7.3 l. 3603-3613).
12. **`PreferencesFragment.java:272-296` refactor** — a `clearCacheRecursively` helper + cache-size/
    file-count helpers (recursive versions) (§4.11.6.3 KG-AFF-3). Insert a note block:
    "if `state.recording !is RecordingState.Idle` → 'Clear cache' disabled + a toast 'Recording
    active'". (Phase-B S-7: race protection against an open MediaRecorder FD, see finding F-10.)
13. **Remove DictateInputMethodService.java fields + sites** — the `audioFile` field l. 208 as well as the
    allocation line l. 1612 (§4.11.5.4 migration steps 1-4).
14. **Wire tests** — `CacheDirAudioFileFactoryTest`, `RecordingModuleAudioFileFactoryWiringTest`,
    optional `AudioFileFactoryRobolectricTest` (§4.11.9 test strategy + skeletons).

<!-- FIX: Phase-B S-1 (2026-05-13) – Test-Klassen auf F-11-Module-Pattern umgestellt (RecordingModuleTest statt PipelineStateManagerTest, separate PipelineRecoveryTest). -->
#### §11.2.3 Test strategy

Existing tests (`app/src/test/java/net/devemperor/dictate/core/`):
- `RecordingStateControllerTest.kt` — pure Kotlin, uses `FakeAudioFocusGate.kt`. Block-1b impact: the tests are rebuilt onto `RecordingModuleTest`. The state assertions change from `controller.state == X` to `RecordingModule.reduce(stateBefore, action, ctx).nextState == X` (a pure function, no manager setup needed).
- `JobExecutorTest.kt` — stays untouched (JobExecutor is kept — see §8 + the plan's main statement).
- `ActiveJobRegistryTest.kt` — unchanged.

New tests per block:

| Block | New test class | Content |
|---|---|---|
| 1b | `DictateOrchestratorTest.kt` | Action routing via the `moduleByLeafClass` lookup, the cascade-depth counter (R.6), the self-cascade regression (`R.RSB-FIX-A`, see §10), the init-order test (PrefMirror BEFORE recovery), the boot-race fence (Phase-B S-1). |
| 1b | `RecordingModuleTest.kt` | Pure reducer tests per state × action — 4 states × ~7 actions = ~28 permutations. No hardware setup (the reducer is pure). |
| 1b | `PipelineModuleTest.kt` | Pure reducer tests for the PipelineUiState FSM (Idle/Preparing/Running/ReprocessStaging). |
| 1b | `LayoutModuleAtomicityTest.kt` | Verifies that `setSmallMode(true)` sets `smallMode = true && contentArea = MAIN_BUTTONS` in ONE `store.update` — subscribers see no intermediate state. |
| 1b | `DictateUiStateTest.kt` | `data class` equality, `copy()` behavior, sealed-class exhaustiveness, the `PersistentList.add/removeAll` idiom. |
| 1b | `PipelinePrefMirrorTest.kt` | `attach(store)` mirroring of 19 prefs (§4.5 `initialMirror` block: 3 layout + 3 audio + 1 resend + 4 features + 4 theming + 4 overlay) into sub-states, the `OnSharedPreferenceChangeListener` trigger. <!-- FIX: Phase-C C-2 (2026-05-14) – 15 → 19 Prefs (Konsistenz mit §11.2.2 Schritt 7 post-Phase-C C-1). --> |
| 1b | `PipelineRecoveryTest.kt` | `recover(store)` logic against `FakeSessionRepo`. |
| 2 | `DictatePipelineServiceTest.kt` | Robolectric service test: the `onCreate` lifecycle, `onStartCommand` action routing, FGS start within 5 s. |
| 2 | `LocalBinderTest.kt` | Bound-service test: `onServiceConnected` triggers the `state.collect` subscriber. |
| 3 | `MigrationTo4Test.kt` | A Room migration test with `MigrationTestHelper` — (a) the `inserted_at` column exists after the migration, old COMPLETED rows have `inserted_at = created_at`; (b) the CHECK constraint accepts `RECORDING`/`TRANSCRIBING` as a status value; (c) an invalid status is rejected (CHECK violation → `SQLiteConstraintException`); (d) **all 4 old statuses round-trip without loss** (`migrate3To4_preservesAllLegacyStatuses`); (e) **child rows from `processing_steps`/`transcriptions` survive the table-recreate** (`migrate3To4_preservesChildRows_processingStepsAndTranscriptions`); (f) **indices are recreated after the migration** (`migrate3To4_preservesIndices`). Detail code see §11.4.2. |
| 3 | `SessionDaoTest.kt` (extended) | `findPendingInsertion`, `markInserted`, `deleteInsertedOlderThan`, `findAllAudioFilePaths`, `markLegacyAudioSessionsFailed` — all new queries. |
| 3 | `PipelineRecoveryTest.kt` (Block-3 extension) | 3 new recovery tests (R.16a/b/c — see §10 acceptance): `recoverFromDb_recordingPromoteToFailed_andCleansAudioFile`, `recoverFromDb_transcribingDowngradeToRecorded_whenAudioPresent`, `recoverFromDb_transcribingPromoteToFailed_whenAudioMissing`. Uses `FakeSessionDao` (§11.7.3) + a temp directory for the audio-file operations. |

**Test doubles:**
- `FakeLocalBinder` — implements the same interface as `DictatePipelineService.LocalBinder`, holds an in-memory `MutableStateFlow<DictateUiState>` + a `dispatch` recorder. Enables IME tests without a Robolectric service.
- `FakePipelineRunner` — already exists (`JobExecutorTest` pattern). Stays unchanged.
- `FakeAudioFocusGate.kt` (exists) — stays; consumed indirectly by the RecordingModule via `services.audioFocus`.
- `FakeModuleServices` — a `ModuleServices` concretization with all subsystem fakes; used by the RecordingModule/PipelineModule test.

### §11.3 Bound-service setup

#### §11.3.1 Connection lifecycle

**Strategy: start + bind the service in `onCreateInputView`** (NOT at the first recording click — see the rationale below).

```java
// DictateInputMethodService.java — neu in onCreateInputView:
@Override
public View onCreateInputView() {
    // ... existing code ...
    if (pipelineBinder == null) {
        Intent intent = new Intent(this, DictatePipelineService.class);
        ContextCompat.startForegroundService(this, intent);   // ensure service runs
        bindService(intent, pipelineConnection, BIND_AUTO_CREATE);  // get LocalBinder
    }
    // ... existing code ...
}
```

**Rationale:** The keyboard always appears before the user clicks any button. If we only start at the recording click, the first click would have a ~50-200ms latency (service onCreate + bind), a noticeable lag. In `onCreateInputView` we have a time reserve.

**Alternative (rejected):** Service start at the first recording click. Rejected because of the latency.

**Unbind lifecycle:**

```java
@Override
public void onDestroy() {
    if (pipelineBinder != null) {
        unbindService(pipelineConnection);
        pipelineBinder = null;
    }
    super.onDestroy();
}
```

**Service self-stop:** when `state.isAllTerminal()` → `stopSelf()` (see §7.4). The IME-service bind does NOT artificially keep the service alive, because after `stopSelf` and `unbindService` the service actually dies — the bind counter is the last reference after `stopSelf`. If the user has not closed the keyboard, the bind keeps the service alive — that is OK, because then `state.isAllTerminal()` can become false in the meantime (a new recording).

#### §11.3.2 ServiceConnection edge cases

```java
private final ServiceConnection pipelineConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        pipelineBinder = (DictatePipelineService.LocalBinder) service;
        // Beginn StateFlow-Collection — replay der letzten Emission via .value
        viewScope.launch(() -> {
            pipelineBinder.getState().collect(state -> {
                keyboardLayoutManager.onStateChanged(state);
            });
        });
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        // Crash des Service-Process. Sollte nicht passieren, da gleicher Process —
        // aber defensiv: binder nullen, viewScope-Job cancellen.
        pipelineBinder = null;
    }

    @Override
    public void onBindingDied(ComponentName name) {
        // Permanent broken — neu binden.
        unbindService(this);
        bindService(new Intent(DictateInputMethodService.this, DictatePipelineService.class),
            this, BIND_AUTO_CREATE);
    }

    @Override
    public void onNullBinding(ComponentName name) {
        // Service hat null aus onBind zurückgegeben. Sollte nicht passieren —
        // unsere onBind gibt immer das LocalBinder-Singleton zurück.
        Log.e(TAG, "Unexpected null binding for DictatePipelineService");
    }
};
```

<!-- FIX: Phase-B S-5 (2026-05-13) – Pre-Bind-Action-Queue als expliziter Pfad dokumentiert.
     §11.3.3 deckt nur den initialen Bind im Same-Process; aber: Notification-Action-Buttons
     können Actions per `startService(intent.setAction(...))` schicken, BEVOR der LocalBinder
     im IME verfügbar ist (z.B. User drückt "Pause" in Notification während Service noch im
     ersten onCreate steckt). Diese Pfad-Klasse braucht eine Klärung. -->
#### §11.3.2a Pre-bind-action path (notification buttons during boot)

Notification action buttons fire action intents at the **service** (via
`PendingIntent.getService` in §7.5 PipelineActionRouter), NOT at the IME. That means:
these actions run through `onStartCommand → actionRouter.dispatch(intent)` and
need neither the LocalBinder nor the `bindService` connection. They are therefore
**immune** to the bind-lifecycle race.

But: action intents that arrive in `onStartCommand` before `onCreate` step 5
(`DictateOrchestrator(…)`) is through dispatch to a `lateinit` orchestrator and
crash with `UninitializedPropertyAccessException`. That is typically impossible on the same process /
main thread (Android queues `onStartCommand` strictly after
`onCreate` completion), but defensively:

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (!::orchestrator.isInitialized) {
        // Sollte nie passieren — defensiv. Wir ignorieren das Intent; der nächste
        // User-Klick funktioniert dann normal. Alternativ: queue + replay nach onCreate-
        // Done. Bewusst nicht implementiert: Same-Process-Lifecycle garantiert
        // onCreate-vor-onStartCommand; eine Queue wäre Dead-Code.
        android.util.Log.w(TAG, "onStartCommand before onCreate-complete — dropped: $intent")
        return START_NOT_STICKY
    }
    intent?.let { actionRouter.dispatch(it) }
    startForegroundCompat(notifCoordinator.buildInitial())
    notifCoordinator.startReactiveUpdates(::stopSelfWhenTerminal)
    return START_NOT_STICKY
}
```

**IME-bind path (LocalBinder-based):** Actions from the IME view (record button,
pause button in the keyboard, etc.) run through `binder.dispatch(action)`. If the IME view
processes a click in `onCreateInputView` BEFORE `onServiceConnected` has fired,
`pipelineBinder == null` and the click handler MUST catch this:

```java
// In dispatchAction-Helper im IME (single point of dispatch):
private void dispatchAction(Action action) {
    if (pipelineBinder == null) {
        // Bind noch nicht etabliert (sehr kurzes Window beim allerersten
        // onCreateInputView). User-Feedback statt silent-drop.
        Toast.makeText(this, R.string.dictate_service_not_ready, Toast.LENGTH_SHORT).show();
        return;
    }
    pipelineBinder.dispatch(action);
}
```

Mandatory task Block-2: a new string resource `dictate_service_not_ready` (DE: "Service
startet noch — bitte kurz warten.", EN: "Service is starting — please wait a moment.").

#### §11.3.3 Race: IME-onCreate before Service-onCreate

Since `DictatePipelineService` runs in the same process (D1), there is no real "service not there yet" race. Order:

1. The IME service `onCreateInputView` → `startForegroundService(intent)` → returns immediately, schedules the service.
2. Android schedules `DictatePipelineService.onCreate()` on the main thread → runs synchronously.
3. The IME service directly afterwards calls `bindService(intent, conn, BIND_AUTO_CREATE)` → sees the freshly created service, calls `Service.onBind()` synchronously, posts `ServiceConnection.onServiceConnected` on the main looper.
4. The IME service returns from `onCreateInputView` (inflate done).
5. `onServiceConnected` runs as the next main-looper message.

**Edge case:** between step 4 and 5 the user CANNOT click a button (touch events run on the same main looper), so no UI race is possible.

**Actual race:** if at the very first start the `recoverFromDb()` coroutine is still running, the first `state.collect` sub sees the initial `_state` emission without `pendingSessions`. As soon as `recoverFromDb` is finished, a second emission with the loaded sessions arrives. That is OK — the subscriber re-renders.

<!-- FIX: Phase-B S-5 (2026-05-13) – Multi-Bind-Klärung. Plan dokumentiert nicht, ob mehrere
     Clients (IME + Settings-Activity + HistoryDetailActivity) parallel binden dürfen. Ohne
     Klärung wird der Block-2-Implementer entweder eine `BindRefCounter`-Optimierung einbauen
     (Premature-Optimization-Risiko) oder erlauben, dass ungebremst gebindet wird (jeder Bind
     erhöht den Service-RefCounter, hindert `stopSelf()` an effektivem Stop). -->
#### §11.3.4 Multi-bind clarification (Phase-B S-5)

**Allowed:** Multiple clients may bind in parallel. Concrete use cases:

| Client | Bind site | Lifecycle | Purpose |
|---|---|---|---|
| `DictateInputMethodService` (IME) | `onCreateInputView` | view-recreate cycle (rotation, IME open/close) | action dispatch + state subscribe for the keyboard render |
| `DictateSettingsActivity` (optional, Phase 2) | `onStart` / `onStop` | activity visibility | "active sessions display" — no current need, but conceivable in the future |
| `HistoryDetailActivity` (optional, Phase 2) | `onStart` / `onStop` | activity visibility | live update "pipeline running, detail button greyed" |

**Consequences for Block 2:** no `BindRefCounter` class, no single-bind restriction.
`LocalBinder` is a **singleton** per service lifetime; Android dispatches the same
IBinder to all consumers. Each consumer creates its own `ServiceConnection`
and cleans up in its `onDestroy`/`onStop`.

**`stopSelf()` interaction:** `stopSelf()` cancels the service lifecycle token; Android keeps
the service alive nonetheless as long as at least one `bindService` connection with
`BIND_AUTO_CREATE` is open. That means: in practice the service stays alive as long
as the IME view exists (or an activity with a bind is open). That is
**desired** — `state.isAllTerminal()` only triggers `stopSelf`, the actual stopping
depends on the bind counter. If the user closes the IME + no activity binds,
the service dies. Auto-restart is `START_NOT_STICKY` (no restart).

**Acceptance** (added in §10): `DictatePipelineServiceMultiBindTest.kt` with two
ServiceConnections — Bind-A in test setup, Bind-B in test body; assert both
`onServiceConnected` receive the same `IBinder` instance; after Unbind-B the
service stays alive until Unbind-A.

### §11.4 DB migration

#### §11.4.1 Migration script for Room

See §6.1 — the complete `MigrationTo4.kt` file is specified.

#### §11.4.2 Tests against the migration test helper

**New file:** `app/src/androidTest/java/net/devemperor/dictate/database/migration/MigrationTo4Test.kt`

```kotlin
package net.devemperor.dictate.database.migration

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.devemperor.dictate.database.DictateDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTo4Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DictateDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate3To4_addsInsertedAtColumn_andBackfillsCompleted() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL("""
                INSERT INTO sessions (id, type, created_at, status, origin,
                    audio_duration_seconds, final_output_text)
                VALUES ('s1', 'RECORDING', 1000, 'COMPLETED', 'KEYBOARD', 10, 'Hello')
            """)
            db.execSQL("""
                INSERT INTO sessions (id, type, created_at, status, origin,
                    audio_duration_seconds, final_output_text)
                VALUES ('s2', 'RECORDING', 2000, 'RECORDED', 'KEYBOARD', 5, NULL)
            """)
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)
        db.query("SELECT id, inserted_at FROM sessions ORDER BY id").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("s1", c.getString(0))
            assertEquals(1000L, c.getLong(1))   // backfilled to created_at
            assertTrue(c.moveToNext())
            assertEquals("s2", c.getString(0))
            assertNull(if (c.isNull(1)) null else c.getLong(1))  // RECORDED → NULL
        }
    }

    @Test
    fun migrate3To4_checkConstraint_acceptsNewStatusValues() {
        // M3-Schema akzeptiert RECORDING/TRANSCRIBING noch nicht (CHECK-Verstoß).
        helper.createDatabase(TEST_DB, 3).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        // Nach M4 darf RECORDING als status eingefügt werden.
        db.execSQL("""
            INSERT INTO sessions (id, type, created_at, status, origin,
                audio_duration_seconds)
            VALUES ('r1', 'RECORDING', 3000, 'RECORDING', 'KEYBOARD', 0)
        """)
        db.execSQL("""
            INSERT INTO sessions (id, type, created_at, status, origin,
                audio_duration_seconds)
            VALUES ('t1', 'RECORDING', 4000, 'TRANSCRIBING', 'KEYBOARD', 5)
        """)
    }

    @Test(expected = android.database.sqlite.SQLiteConstraintException::class)
    fun migrate3To4_checkConstraint_rejectsUnknownStatus() {
        helper.createDatabase(TEST_DB, 3).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)
        db.execSQL("""
            INSERT INTO sessions (id, type, created_at, status, origin,
                audio_duration_seconds)
            VALUES ('x1', 'RECORDING', 5000, 'BOGUS_STATUS', 'KEYBOARD', 0)
        """)
    }

    // ───────────────────────────────────────────────────────────────────────
    // Zusatztests (KG-SST / §11.7.0-Risiko-Mitigationen)
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Round-Trip: alle 4 alten Stati werden ohne Wertverlust nach v4 übernommen.
     * Verifiziert, dass der CASE-Backfill in MIGRATION_3_4 keinen alten Status mutiert.
     */
    @Test
    fun migrate3To4_preservesAllLegacyStatuses() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            listOf("RECORDED", "COMPLETED", "FAILED", "CANCELLED").forEachIndexed { i, s ->
                db.execSQL("""
                    INSERT INTO sessions (id, type, created_at, status, origin,
                        audio_duration_seconds)
                    VALUES ('s$i', 'RECORDING', ${1000 + i}, '$s', 'KEYBOARD', 0)
                """)
            }
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)
        db.query("SELECT id, status FROM sessions ORDER BY id").use { c ->
            val seen = mutableMapOf<String, String>()
            while (c.moveToNext()) seen[c.getString(0)] = c.getString(1)
            assertEquals("RECORDED", seen["s0"])
            assertEquals("COMPLETED", seen["s1"])
            assertEquals("FAILED", seen["s2"])
            assertEquals("CANCELLED", seen["s3"])
        }
    }

    /**
     * FK-Cascade-Datenverlust-Mitigation (§11.7.0 Risiko 3):
     * Child-Tabellen `processing_steps` und `transcriptions` haben FK auf
     * `sessions.id` mit ON DELETE CASCADE. Verifiziert, dass DROP/RENAME
     * von `sessions` die child-Rows NICHT killt.
     */
    @Test
    fun migrate3To4_preservesChildRows_processingStepsAndTranscriptions() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL("""
                INSERT INTO sessions (id, type, created_at, status, origin,
                    audio_duration_seconds)
                VALUES ('parent', 'RECORDING', 1000, 'COMPLETED', 'KEYBOARD', 5)
            """)
            db.execSQL("""
                INSERT INTO transcriptions (id, session_id, version, is_current,
                    text, model_used, provider, prompt_tokens, completion_tokens,
                    duration_ms, created_at)
                VALUES ('t1', 'parent', 1, 1, 'hello', 'whisper-1', 'openai',
                    0, 0, 200, 1100)
            """)
            db.execSQL("""
                INSERT INTO processing_steps (id, session_id, step_type,
                    chain_index, version, is_current, input_text, model_used,
                    provider, prompt_tokens, completion_tokens, duration_ms,
                    status, created_at)
                VALUES ('ps1', 'parent', 'PROMPT', 0, 1, 1, 'hello', 'gpt-4',
                    'openai', 0, 0, 300, 'SUCCESS', 1200)
            """)
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        db.query("SELECT COUNT(*) FROM transcriptions WHERE session_id = 'parent'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM processing_steps WHERE session_id = 'parent'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
        }
        // FK-Integrität-Check: nach Migration neue child-Row einfügen muss funktionieren
        db.execSQL("""
            INSERT INTO transcriptions (id, session_id, version, is_current,
                text, model_used, provider, prompt_tokens, completion_tokens,
                duration_ms, created_at)
            VALUES ('t2', 'parent', 2, 1, 'world', 'whisper-1', 'openai',
                0, 0, 200, 1300)
        """)
    }

    /**
     * Index-Erhalt (§11.7.0 Risiko 4): alle Indices, die MigrationTo4 in Step 4
     * recreated, sind nach Migration tatsächlich da. PRAGMA index_list gibt
     * keine Reihenfolge-Garantie, deshalb in Set vergleichen.
     */
    @Test
    fun migrate3To4_preservesIndices() {
        helper.createDatabase(TEST_DB, 3).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)
        val indices = mutableSetOf<String>()
        db.query("PRAGMA index_list('sessions')").use { c ->
            while (c.moveToNext()) indices.add(c.getString(1))  // column 1 = name
        }
        // Expected indices: alle aus MIGRATION_2_3 + MIGRATION_3_4 Step 4
        // (PRIMARY KEY hat einen impliziten "sqlite_autoindex_sessions_1"
        // den wir hier ignorieren — wir prüfen NUR die expliziten.)
        listOf(
            "index_sessions_parent_session_id",
            "index_sessions_type",
            "index_sessions_created_at",
            "index_sessions_origin",
            "index_sessions_status"
        ).forEach { name ->
            assertTrue("Index $name fehlt nach M4", indices.contains(name))
        }
    }

    /**
     * KG-SST-3 (RESOLVED 2026-05-11): Multi-Step-Migration v1→v4-Chain.
     *
     * Verifiziert, dass eine v1-DB durch die volle Chain
     * MIGRATION_1_2 → MIGRATION_2_3 → MIGRATION_3_4 läuft, ohne Daten zu verlieren.
     *
     * Hintergrund: Room führt addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
     * bei einem App-Backup-Restore (Android Auto-Backup / ADB) automatisch
     * sequenziell aus, wenn die User-DB auf v1 steht. Da v1 die `sessions`-Tabelle
     * OHNE `status`-Spalte erzeugt, prüft dieser Test, dass MIGRATION_2_3
     * (die `status` einführt) und MIGRATION_3_4 (die `status` erweitert)
     * korrekt aufeinander aufbauen.
     *
     * v1-Schema (siehe Migrations.kt:9-23): sessions hat id, type, created_at,
     * target_app_package, language, audio_file_path, audio_duration_seconds,
     * parent_session_id, final_output_text, input_text.
     */
    @Test
    fun migrate1To4_chain_preservesData() {
        // Bekannter v1-Stand: eine Session mit Type RECORDING.
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL("""
                INSERT INTO sessions (
                    id, type, created_at, target_app_package, language,
                    audio_file_path, audio_duration_seconds, parent_session_id,
                    final_output_text, input_text
                )
                VALUES (
                    'sess-v1', 'RECORDING', 1000, 'com.example', 'en',
                    '/data/audio.m4a', 5, NULL,
                    NULL, NULL
                )
            """.trimIndent())
        }

        // Volle Chain durchlaufen.
        val db = helper.runMigrationsAndValidate(
            TEST_DB, 4, true,
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4
        )

        // Original-Row muss erhalten sein, inkl. der von MIGRATION_2_3 abgeleiteten
        // Status-Inferenz (RECORDING-Type ohne Transcription + audio_file_path
        // → status = 'RECORDED'; siehe MigrationTo3.kt:92-97).
        db.query(
            "SELECT id, type, status, origin, audio_file_path, inserted_at " +
            "FROM sessions WHERE id = 'sess-v1'"
        ).use { c ->
            assertEquals("v1-Row nach v1→v4 verschwunden", 1, c.count)
            c.moveToFirst()
            assertEquals("sess-v1", c.getString(0))
            assertEquals("RECORDING", c.getString(1))
            // Status aus MIGRATION_2_3 inferiert: RECORDED, da keine Transcription
            // und audio_file_path != null.
            assertEquals("RECORDED", c.getString(2))
            // Default-Origin von MIGRATION_2_3.
            assertEquals("KEYBOARD", c.getString(3))
            assertEquals("/data/audio.m4a", c.getString(4))
            // inserted_at: MIGRATION_3_4-Backfill setzt es NUR für COMPLETED-Rows.
            // Diese Row ist RECORDED → inserted_at bleibt NULL.
            assertNull("inserted_at darf für RECORDED nicht backfilled werden", c.getString(5))
        }
    }

    companion object { private const val TEST_DB = "migration-test" }
}
```

<!-- FIX: Phase-B S-1 (2026-05-13) – Test-Datei-Naming auf DictateOrchestrator-/PipelineRecovery-Pattern umgestellt. Recovery-Logik lebt nach F-11 nicht mehr im monolithischen PipelineStateManager, sondern in `PipelineRecovery` (§4.6) plus dem RECORDING/TRANSCRIBING-Branch im PendingSessionsModule/Recovery-Pfad (§6.3). -->
> [!NOTE]
> **Additional recovery test (its own file, not part of the migration test):**
> `PipelineRecoveryTest.kt` (Block 1, see §11.2) covers the `recover()` logic —
> RECORDING-boot → FAILED+cleanup, TRANSCRIBING-boot with audio → RECORDED downgrade,
> TRANSCRIBING-boot without audio → FAILED. This logic tests the recovery table from §6.3
> end-to-end, but is JVM-only (Robolectric) — no Android test setup needed, because
> SessionDao + DAO calls are mocked. Test setup: `PipelineRecovery(FakeSessionRepo)`,
> assertion on `store.snapshot.pendingSessions` after `recovery.recover(store)`.

#### §11.4.3 Edge cases on migration failure

`Room.databaseBuilder` today (see `DictateDatabase.kt:67-103`) has NO `fallbackToDestructiveMigration` — if the migration fails, the app crashes at the first DB access with `IllegalStateException`. That is the right strategy for our setting (we do not want to lose user data).

**If the migration fails in the wild:** a crash + automatic bug report. The user data stays intact in the old DB file; the app is unusable until a hotfix is delivered. This is acceptable, because the migration is `ALTER TABLE ADD COLUMN` — practically cannot fail (except on disk-full or corruption).

### §11.5 Notification UX

#### §11.5.1 POST_NOTIFICATIONS runtime permission (Android 13+)

<!-- FIX: Phase-B S-5 (2026-05-13) – Permission-Prompt-Flow präzisiert: IME-Service kann keinen
     ActivityResultLauncher halten (kein Activity-Context). Prompt-Site ist OnboardingActivity (für
     Fresh-Install) UND DictateSettingsActivity (für Update-Users, deren Onboarding bereits durch
     ist). UI-Hint im Keyboard zeigt subtilen Banner, wenn Permission fehlt — User-Friction-
     Indicator ohne Pflicht-Dialog. -->
**Lifecycle of the permission:**

| API | Default state | Effect |
|---|---|---|
| < 33 (Tiramisu) | implicitly granted (legacy permission) | the notification is always visible |
| ≥ 33, never requested | DENIED | the notification is invisible, the FGS runs, no user-visibility signal |
| ≥ 33, user accepted | GRANTED | the notification is visible |
| ≥ 33, user declined | DENIED + don't-ask-again | like "DENIED", re-prompt only via the app settings |

**Prompt site 1 — onboarding (fresh install):**

```kotlin
// In OnboardingActivity (Kotlin oder Java) — Sicheres ActivityResultLauncher-Pattern.
private val postNotifLauncher: ActivityResultLauncher<String> =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            // Optional: Hinweis-Card "Du kannst die Aufnahme-Benachrichtigung später in
            // den App-Einstellungen aktivieren." Kein Hard-Block — FGS läuft auch ohne.
        }
    }

private fun maybeRequestPostNotifications() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return
    postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
}
```

**Prompt site 2 — Settings activity (update user who does not run onboarding again):**

In `DictateSettingsActivity.onResume()` a one-time check (via a
`SharedPreferences` flag `post_notif_prompt_shown`):

```kotlin
override fun onResume() {
    super.onResume()
    val sp = getSharedPreferences("dictate_prefs", MODE_PRIVATE)
    if (Build.VERSION.SDK_INT >= 33
        && !sp.getBoolean("post_notif_prompt_shown", false)
        && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
        sp.edit().putBoolean("post_notif_prompt_shown", true).apply()
        postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
```

**IME user-friction signal:** If the user still uses Dictate despite a decline and starts a
recording, the FGS runs, but the notification is invisible — the user does not know that
the recording continues in the background. Mitigation: a subtle hint banner in the
keyboard view that fades in on an active recording AND `!checkSelfPermission(POST_NOTIFICATIONS)`:
"Note: recording notification disabled — open app settings?".
The click opens the `Settings.ACTION_APP_NOTIFICATION_SETTINGS` intent. Implementation: Block 6
(LayoutCatalog) as its own `BannerSlot` predicate.

**Rationale:** IME services may not show `requestPermissions` dialogs for UX reasons
(the system would render the dialog under the keyboard and the IME has no
activity context for `ActivityResultLauncher`). Instead we prompt in two
activities (`OnboardingActivity` for fresh install, `DictateSettingsActivity` for
update users); the IME shows only a passive-informative banner.

**Block-2 acceptance** (added in §10): onboarding shows the POST_NOTIFICATIONS prompt on
API ≥ 33, a decline leads to a visible banner in the IME view during an active recording, a click
on the banner opens the system notification settings.

#### §11.5.2 MediaStyle vs. Default — decision

We use the **NotificationCompat default style** (no MediaStyle). Rationale:
- MediaStyle is designed for audio playback (album cover, track title, skip/prev) — does not fit a pipeline status.
- The default style with 3 action buttons is sufficient for our states (Pause/Stop/Send or Insert/Discard).
- The compact style shows max. 3 actions → we stay under the limit.

#### §11.5.3 Click-on-notification behavior

**Goal:** The user clicks on the notification → the keyboard app opens on the `DictateSettingsActivity` (the main activity).

```kotlin
private fun buildContentIntent(): PendingIntent {
    val intent = Intent(this, DictateSettingsActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return PendingIntent.getActivity(
        this, 0, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
```

**Limitation:** An IME can NOT make itself visible through a notification click (no programmatic-show-IME); we can only open the app activity. Accepted.

### §11.6 OOM-death recovery

#### §11.6.1 recoverFromDb call strategy

**Asynchronously in the scope** (NOT synchronously in `onCreate`), because DB IO could block and we do not want to risk the 5-second FGS deadline (§11.1.4).

<!-- FIX: Phase-B S-1 (2026-05-13) – §11.6.1-Snippet auf DictateOrchestrator umgestellt. Recovery wird vom Orchestrator-init selbst async gestartet — kein separater scope.launch nötig. -->
```kotlin
override fun onCreate() {
    super.onCreate()
    ensureNotificationChannel()
    // Orchestrator-init startet `scope.launch { recovery.recover(store) }` selbst (§4.3) —
    // also wird FGS-Start nicht blockiert.
    orchestrator = DictateOrchestrator(serviceScope, store, servicesFactory, prefMirror, recovery)
    startForegroundCompat(orchestrator.state.value)   // sync, instant
    // Recovery läuft bereits async; keine explizite scope.launch hier nötig.
}
```

#### §11.6.2 Audio files that no longer exist

At `recoverFromDb` all `final_output_text != NULL AND inserted_at IS NULL` sessions are loaded (§6.1). Audio files ARE no longer needed for insertion (the result text is already in the DB). Therefore: no file-existence check needed — `pendingSessions` contains only the finished text + `sessionId`. Insertion is from the DB text.

**Edge case:** `recoverFromDb` also loads RECORDED sessions (audio recorded, but no pipeline ran any longer — crash in the middle of the recording stop). For this the following applies:

<!-- FIX: Phase-B S-7 (2026-05-13) – `getByStatus(String)` existiert im DAO nicht; korrekter Name ist
     `getSessionsByStatuses(List<String>)` (Plural, mit Liste). Drift gegen §6.3 Z. 3203/3268 +
     DAO-Definition Z. 3298 (S-2-Scope) — bereinigt. -->
<!-- FIX: Phase-C C-2 (2026-05-14) – Snippet ist **vereinfachte Pre-S-2-Variante** OHNE
     RECORDING/TRANSCRIBING-Branches. Die kanonische M4-Recovery-Logik (mit allen 6 v4-Stati,
     Promote/Downgrade-Pfade, Stale-Error-Clear, MERGE-Statt-Override) lebt in §6.3 — der
     unten gezeigte Mini-Snippet illustriert nur den RECORDED-Sub-Pfad als Beispiel und ist
     KEIN vollständiges `recoverFromDb`. Implementer-Anker: SoT ist §6.3. -->
```kotlin
// Auszug — vollständige Recovery-Logik siehe §6.3 (RECORDING/TRANSCRIBING/RECORDED/COMPLETED).
suspend fun recoverFromDb_recordedSubPath() = withContext(Dispatchers.IO) {
    val pending = db.sessionDao().findPendingInsertion()
        .map { it.toPendingSession() }
    val orphanedRecorded = db.sessionDao().getSessionsByStatuses(listOf("RECORDED"))
        .filter { it.audioFilePath != null && File(it.audioFilePath).exists() }
        .map { it.toPendingSession() }
    <!-- FIX: Phase-B S-4 (2026-05-13) – store.update statt _state.update (Insider-Syntax, §4.4 private MutableStateFlow). -->
    store.update { it.copy(pendingSessions = pending + orphanedRecorded) }
}
```

Sessions with `audio_file_path != null` but the file does not exist (cache cleanup after an app update, an OS storage-pressure wipe, or a user "Clear cache" — see §4.11.6) are filtered + DB cleanup as an opportunistic side effect:

```kotlin
val ghostSessions = db.sessionDao().getSessionsByStatuses(listOf("RECORDED"))
    .filter { it.audioFilePath != null && !File(it.audioFilePath).exists() }
ghostSessions.forEach {
    db.sessionDao().updateStatus(it.id, SessionStatus.FAILED.name)
    db.sessionDao().updateError(it.id, "UNKNOWN", "audio file vanished")
}
```

#### §11.6.3 User communication "session lost"

For every ghost session (see above) `lastErrorType=UNKNOWN, lastErrorMessage="audio file vanished"` is set. The history activity (`HistoryActivity`/`HistoryAdapter.java:99-156`) already shows FAILED sessions with an error icon. No additional UI needed. Accepted per user choice D4 (no auto-resume).

### §11.7 Migration order & risk

<!-- FIX: Issue PENDING-2 / Block-3 Resolved – Risiko-Hinweis ergänzt zur table-recreate-Migration -->

#### §11.7.0 DB migration risk (M3 → M4)

The migration in §6.1 is no longer purely additive (see the D8 update in §2). Consequences:

| Risk | Probability | Mitigation |
|---|---|---|
| Data loss through an interrupted migration | Very low — Room runs every migration in a SQLite transaction; an error aborts atomically (the schema stays at M3) | The migration is deterministic, no external calls; the migration test covers the CHECK constraint + backfill |
| Migration failure on user devices (e.g. SQLite corruption) | Low | `Room.databaseBuilder` has NO `fallbackToDestructiveMigration` (see `DictateDatabase.kt:67-103`) → the app crashes instead of killing user data; a hotfix is possible |
| **FK-cascade data loss through `DROP TABLE sessions`** | Medium-high — `processing_steps` and `transcriptions` have an **FK on `sessions.id` with `ON DELETE CASCADE`** (verified in `MigrationTo3.kt:138` and `:182`). But: **SQLite cascade-delete does NOT fire on `DROP TABLE`** (see https://sqlite.org/foreignkeys.html §4.2 — cascade is only for row-level DELETE, not for schema operations). MIGRATION_2_3 uses the same pattern productively without data loss. | (a) The migration test **explicitly checks** that `processing_steps` and `transcriptions` rows still exist after the migration (see §11.4.2 test `migrate3To4_preservesChildRows`). (b) `PRAGMA foreign_keys = OFF` during the migration is **not** needed — Room disables FK enforcement during migrations anyway (see https://developer.android.com/training/data-storage/room/migrating-db-versions). (c) Lesson from MIGRATION_2_3: SQLite stores FK references textually by name; after `DROP sessions` + `ALTER … RENAME sessions_new TO sessions` the FK is valid again — the child tables are not touched. |
| Index loss through DROP | Low | The migration recreates all indices explicitly (see MigrationTo4.kt step 4); the migration test `migrate3To4_preservesIndices` checks `PRAGMA index_list(sessions)`. |
| Schema-validator mismatch (Room compile-time check) | Medium — `audio_duration_seconds` must stay without a SQL DEFAULT (see the MigrationTo3 comment l. 38-42) | The migration snippet follows the MIGRATION_2_3 convention; `exportSchema = true` (see DictateDatabase.kt:39) produces the schema JSON for validation in CI; `runMigrationsAndValidate(..., validateDroppedTables = true, ...)` in the test throws on a mismatch. |
| Multi-step migration v1→v4 on a restore from an old backup | Low — an app backup (Android Auto-Backup / ADB restore) contains the Room DB file; on restore Room runs through `MIGRATION_1_2 → MIGRATION_2_3 → MIGRATION_3_4` sequentially (Room standard behavior, no special path needed) | **Automated test (KG-SST-3 RESOLVED 2026-05-11):** `MigrationTo4Test.migrate1To4_chain_preservesData()` (see §11.4.2) — inserts a v1 RECORDING row, validates after the full chain that the row is preserved and the status is correctly inferred. Additionally a manual smoke test in the §11.7.4 runbook ("restore an app installation from a pre-v3 backup, `adb shell sqlite3` probe"). |
<!-- FIX: Phase-B S-2 (2026-05-13) – Downgrade-Strategie explizit dokumentieren. -->
| **DB downgrade v4 → v3 (a release update failed, the user installs an older app version from a backup/APK)** | Very low — users do not usually actively install older versions | Today's `Room.databaseBuilder` has NO `fallbackToDestructiveMigrationOnDowngrade` (verified `DictateDatabase.kt:67-103`). Result: the older app version crashes at the first DB access with `IllegalStateException` ("A migration from 4 to 3 was required but not found"). **The user data stays intact** in the DB file. Recovery path: the user installs the v4 app again → the schema stays v4, everything works again. **Deliberate decision:** no downgrade path implemented — reinstalling the current version is the simpler user path than a data migration back to v3. Pre-existing RECORDING/TRANSCRIBING rows from the v4 period would appear in the v3 app via the HistoryAdapter try/catch wrapper (§6.1.3) as a RECORDED fallback — no UI crash. |

<!-- FIX: Phase-B S-2 (2026-05-13) – §11.7.0a neu: androidTest-Setup als eigene Sub-Sektion. -->
#### §11.7.0a androidTest setup (NEW for Block 3 — see inventory surprise finding #4)

Block 3 is the first block in this repo to introduce **instrumented tests**. The setup is substantial enough to stand as its own sub-step in the Block-3 plan (see §11.2.2 Block 3 step 0). This section defines the concrete setup order + verification smoke test.

**Current state (verified 2026-05-13):**
- `app/src/androidTest/` does **not** exist.
- `app/build.gradle:18` declares `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` — already configured.
- `app/build.gradle:74-75` declares `androidTestImplementation libs.ext.junit` + `libs.espresso.core` — the runner + Espresso are there, but **no `room-testing` dependency**, no `androidx.test.runner` (separate from `ext.junit`), no `androidx.test.rules`.
- `gradle/libs.versions.toml`: `room = "2.6.1"`, `espressoCore = "3.7.0"`, a `junitVersion` entry exists (for `ext-junit`). **`room-testing`, `androidx.test.runner`, `androidx.test.rules` are missing** as version-catalog entries.

**Setup order (before the MigrationTo4.kt implementation):**

1. **Extend the version catalog** — `gradle/libs.versions.toml`:
   ```toml
   [versions]
   # ... bestehend ...
   androidxTestRunner = "1.5.2"
   androidxTestRules = "1.5.0"

   [libraries]
   # ... bestehend ...
   # Room-Migration-Test-Helper (siehe MigrationTo4Test in §11.4.2)
   room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
   # Instrumented-Test-Runner + Test-Rules (für @get:Rule MigrationTestHelper)
   androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "androidxTestRunner" }
   androidx-test-rules = { group = "androidx.test", name = "rules", version.ref = "androidxTestRules" }
   ```
2. **Extend build.gradle** — `app/build.gradle:74-75`, AFTER `androidTestImplementation libs.espresso.core`:
   ```gradle
   androidTestImplementation libs.room.testing
   androidTestImplementation libs.androidx.test.runner
   androidTestImplementation libs.androidx.test.rules
   ```
3. **Create the directory** — `mkdir -p app/src/androidTest/java/net/devemperor/dictate/database/migration` (manually or via Android Studio "New > Module > androidTest source set").
4. **Create a smoke test before MigrationTo4** — `app/src/androidTest/java/net/devemperor/dictate/database/migration/AndroidTestSetupSmokeTest.kt`:
   ```kotlin
   package net.devemperor.dictate.database.migration

   import androidx.test.ext.junit.runners.AndroidJUnit4
   import org.junit.Assert.assertTrue
   import org.junit.Test
   import org.junit.runner.RunWith

   @RunWith(AndroidJUnit4::class)
   class AndroidTestSetupSmokeTest {
       @Test fun smoke() { assertTrue(true) }
   }
   ```
   Verification: `./gradlew connectedDebugAndroidTest` must be green (or via Android Studio against a connected device/emulator). If this smoke test fails, the setup is broken — do not write any code in MigrationTo4.kt before it is green.
5. **Only then** implement MigrationTo4.kt + MigrationTo4Test.kt (see §6.1 + §11.4.2).

**CI integration:** Currently `connectedDebugAndroidTest` does not run in CI (no emulator setup). Block 3 does **not** add that — instrumented tests run locally before merge (a dev obligation in the PR checklist). If an emulator-CI setup comes later (a separate plan), the migration test reactivates automatically.

**Effort:** ~30 min setup (catalog + build.gradle) + ~10 min smoke test + a local run against an emulator. **Plus** ~1-2 h for the 6 MigrationTo4Test tests (see §11.4.2). Total effort Block 3 androidTest share: ~3 h.

<!-- KNOWLEDGE-GAP: KG-SST-2 – Cleanup-Policy für FAILED-Sessions mit ungenutztem Audio-File [RESOLVED 2026-05-11] -->
> **✅ Knowledge gap (KG-SST-2): Cleanup policy for FAILED sessions with an unused audio file — RESOLVED 2026-05-11**
>
> - **What we knew:** `deleteInsertedOlderThan(cutoff)` only deletes rows with `inserted_at IS NOT NULL` (§6.1 DAO). FAILED sessions have `inserted_at IS NULL` → are NOT captured by the cleanup path. Audio files whose delete fails during a RECORDING→FAILED recovery thus stay on disk.
> - **What we did not know:** Does a second cleanup path for orphan audio files already exist today?
> - **Resolution (code research 2026-05-11):**
>
>     **Today's cleanup paths (verified via `grep -rn "deleteAudio\|orphan\|cleanup" app/src/main/java/`):**
>     1. `RecordingRepository.deleteBySessionId(sessionId)` (`RecordingRepository.kt:136-148`) — **user-triggered**: the detail-view "Delete audio" button (`HistoryDetailActivity.onDeleteAudio` → `confirmDeleteAudio` → `RecordingRepository.deleteBySessionId`). Deletes the file + sets `audio_file_path = NULL` in the DB.
>     2. `DurationHealingJob.heal(...)` (`DurationHealingJob.kt:33-72`) — an app-start hook: finds sessions with `audio_file_path != null && !File.exists()` (the file removed by the OS cache cleanup or manually) and promotes them → `status = FAILED, lastErrorType = UNKNOWN`. **Heals only DB inconsistencies, NO filesystem cleanup.**
>     3. **Android cache auto-cleanup** (`cacheDir`): today's recording audio file lies in `getCacheDir()/audio.m4a` (`DictateInputMethodService.java:1407, 1612, 1693`) — Android cleans `cacheDir` opportunistically at low storage. **Means: FAILED sessions whose `audio_file_path` points at `cacheDir/…` are indirectly cleaned up (DurationHealingJob then promotes to FAILED, the file is gone anyway).**
>     4. **No routine** for filesystem orphans (files on disk WITHOUT a corresponding DB row).
>
>     **Finding:** Today NO routine exists that cleans up audio files for `status = FAILED && audio_file_path != null && File.exists()` sessions. A file delete in the RECORDING→FAILED recovery path (§6.3, "order file-op vs. DB-op") can fail (permission race) and the file leaks indefinitely. Moving from `cacheDir/audio.m4a` (today) to `filesDir/recordings/{sessionId}.m4a` (Block 4, AudioFileFactory) makes it worse, because `filesDir` is NOT touched by the OS cleanup.
>
>     **Resolution strategy: a defensive 2-stage cleanup in the service idle-stop slot (analogous to `deleteInsertedOlderThan`).**
>
>     Block 3 adds **a new DAO method** + **a new service cleanup routine**:
>
>     ```kotlin
>     // In SessionDao.kt — neu (vor Zeile 96):
>     /**
>      * Cleanup-Helper für KG-SST-2: räumt Audio-Files für lange FAILED-/CANCELLED-Sessions
>      * weg, die auf Disk liegen aber nicht mehr referenziert werden müssen.
>      *
>      * Returns Liste der Dateipfade, die der Caller dann via File.delete() löschen muss
>      * (DAO darf kein File-IO machen — Trennung Layer).
>      */
>     @Query(
>         """
>         SELECT audio_file_path FROM sessions
>         WHERE status IN ('FAILED', 'CANCELLED')
>           AND audio_file_path IS NOT NULL
>           AND created_at < :cutoff
>         """
>     )
>     fun findOrphanedTerminalAudio(cutoff: Long): List<String>
>
>     /** Setzt `audio_file_path = NULL` nach erfolgreicher File-Op (idempotent, additiv). */
>     @Query("UPDATE sessions SET audio_file_path = NULL WHERE id IN (:ids)")
>     fun clearAudioFilePathBulk(ids: List<String>)
>     ```
>
>     ```kotlin
>     // In DictatePipelineService.onTimeout() / onIdleStop() — neu:
>     // (analog zur Stelle, an der heute deleteInsertedOlderThan() gerufen wird)
>     suspend fun cleanupOrphanedAudio() = withContext(Dispatchers.IO) {
>         val cutoff = System.currentTimeMillis() - Pref.SessionCleanupGracePeriodMs.get()
>         val dao = DictateDatabase.getInstance(this@DictatePipelineService).sessionDao()
>         val paths = dao.findOrphanedTerminalAudio(cutoff)
>         val cleared = mutableListOf<String>()
>         for (path in paths) {
>             val file = File(path)
>             // Best-effort: wenn File.delete() failed (Permission-Race),
>             // probieren wir es beim nächsten Idle-Stop erneut. KEIN Status-Touch.
>             if (!file.exists() || file.delete()) {
>                 // Pfad NICHT in DB zurückschreiben hier — wir machen es bulk unten,
>                 // weil jeder einzelne UPDATE eine IO-Operation ist.
>                 cleared += /* sessionId from path lookup */ ...
>             }
>         }
>         if (cleared.isNotEmpty()) dao.clearAudioFilePathBulk(cleared)
>     }
>     ```
>
>     A more pragmatic variant (since the DAO result only delivers paths, not IDs): instead of `findOrphanedTerminalAudio`, return a `findOrphanedTerminalSessions(cutoff)` that delivers `List<Pair<String, String>>` (id + path). The implementer decides — the functionality is identical.
>
>     **Trigger time:** In the same service-idle-stop slot as `deleteInsertedOlderThan(cutoff)`. Cutoff: `now − 7d − 1h` (`Pref.SessionCleanupGracePeriodMs`, already defined in §6.2 R.17 cleanup cutoff).
>
>     **Layer separation:** File IO in the service layer (coroutine), DB in the DAO — cleanly separated analogously to `RecordingRepository.deleteBySessionId`. NO file ops in the DAO itself.
>
>     **Coverage gap accepted:** Files with `audio_file_path` on `cacheDir/` (legacy) are not in the `findOrphanedTerminalAudio` scope, because they are cleaned by the OS anyway. After the Block-4 migration to `filesDir/recordings/`, `cleanupOrphanedAudio()` becomes the only cleanup path — and that is OK.
>
> - **Incorporation:**
>     - §6.3 gets a new §6.3.1 "Orphan-FAILED-Audio-Cleanup" with the DAO query + service-hook snippet.
>     - §6.2 persistence contract (R.17) "cleanup cutoff" bullet point extended by the new slot.
>     - §11.6.5 / §13 implementation plan (see point 5 + 9 in §13) gets an additional bullet point: "Block 3 adds `findOrphanedTerminalAudio` + a service cleanup hook".

<!-- KNOWLEDGE-GAP: KG-SST-3 – v1→v4 Multi-Step-Migration nicht im automatisierten Test [RESOLVED 2026-05-11] -->
> **✅ Knowledge gap (KG-SST-3): v1→v4 multi-step migration not in the automated test — RESOLVED 2026-05-11**
>
> - **What we knew:** Room runs `addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)` sequentially if the user DB is at v1. But `MigrationTo4Test` only covers v3→v4.
> - **What we did not know:** Are there user installations with a v1 DB file in the field? Do existing multi-version migration tests exist (e.g. `MigrationTo2Test`, `MigrationTo3Test`)?
> - **Resolution (code research 2026-05-11):**
>     - **Existing migration tests:** None. `find /home/lukas/WebStorm/Dictate/app/src -iname "*Migration*Test*"` yields only `InputLanguagesLegacyMigrationTest.kt` (Preferences domain, not Room). There is NO Room migration test in the repo today. `MigrationTo4Test` (§11.4.2) is the FIRST Room migration test introduced.
>     - **`androidTest` directory:** Does NOT exist today. Block 3 must create `app/src/androidTest/java/...` anew + add the `androidTestImplementation` dependencies (`androidx.room:room-testing`, `androidx.test:runner`, `androidx.test:rules`) in `app/build.gradle:74-75` (today only `androidx.test:junit` + `espresso-core` declared, but not `room-testing`).
>     - **v1 schema verified** (see `Migrations.kt:9-23`): `sessions` without a `status` column, with `id, type, created_at, target_app_package, language, audio_file_path, audio_duration_seconds, parent_session_id, final_output_text, input_text`. MIGRATION_2_3 infers `status` (see `MigrationTo3.kt:92-97`: RECORDING type without a transcription + audio_file_path → RECORDED, otherwise COMPLETED).
>     - **The telemetry question is moot:** Since multi-step migration tests are cheap (~50 lines, ~15 min implementation) and a FAILED test prevents a crisis class of bugs (a restore-from-backup is exactly the scenario in which a user has no workaround), we adopt the default strategy without a further user decision.
>     - **Concrete test body** added in §11.4.2: `migrate1To4_chain_preservesData()` — inserts a RECORDING-type session into a v1 DB (id, audio_file_path set, audio_duration_seconds = 5), runs `runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)`, and asserts (a) the row preserved, (b) the status correctly inferred to `RECORDED`, (c) the default origin `KEYBOARD`, (d) `inserted_at = NULL` (backfill only for COMPLETED).
>     - **Test runbook §11.7.4** additionally gets a manual smoke test "restore an app installation from a pre-v3 backup, start the app, check the sessions table via `adb shell sqlite3`" — as belt-and-suspenders next to the automated test.
>
> - **Incorporation:**
>     - §11.4.2 — a new `@Test fun migrate1To4_chain_preservesData()` (full body).
>     - §11.7.0 risk table — the line "multi-step migration v1→v4" updated: now **an automated test exists** (`migrate1To4_chain_preservesData`), a manual smoke test in the §11.7.4 runbook as a backup.
>     - Block-3 implementer note: the `androidTest` directory + the `room-testing` dependency must be part of Block 3 (also necessary for the other `MigrationTo4Test` tests).

<!-- KNOWLEDGE-GAP: KG-SST-4 – HistoryAdapter switch ohne default (Java-Compile-Verhalten) [RESOLVED 2026-05-11] -->
> **✅ Knowledge gap (KG-SST-4): `HistoryAdapter.java` `switch` without a `default` branch — RESOLVED 2026-05-11**
>
> - **What we knew:** `HistoryAdapter.java:136-159` is a `switch (status)` without a `default`. Java is not exhaustive — missing `case`s after M4 (`RECORDING`/`TRANSCRIBING`) cause no compile error. They would lead to an empty display (no badge).
> - **What we did not know:** Does the project have a lint rule `EnumSwitch` active?
> - **Resolution (code research 2026-05-11):**
>     - **Verified:** `HistoryAdapter.java:136-159` has NO `default:` — confirmed via Read.
>     - **Lint-setup finding:**
>         - **No `lint.xml`** in the project (checked: `find /home/lukas/WebStorm/Dictate -maxdepth 3 -name 'lint.xml'` → empty).
>         - **No `.editorconfig`** with lint rules (checked: the same path).
>         - **No `lintOptions` block** in `app/build.gradle` (verified: read, only `compileOptions`, `kotlinOptions`, `buildFeatures`, `packagingOptions`, `testOptions` present — no `lint { … }` and no `android.lint`).
>         - **No `lint { … }` block** in the root `build.gradle`.
>         - **No ErrorProne/SpotBugs/Detekt setup** (no plugin declarations in the `app/build.gradle` plugins block; only `android.application`, `kotlin.android`, `ksp`).
>     - **Consequence:** Android-Lint runs with **defaults** — the rule `EnumSwitch` (Android-Lint built-in, issue ID `EnumSwitchHandlesMissingCase`) is **by default at severity `Warning`** and is produced on the `./gradlew lint` run **only as a report**, without a build failure. Without a `lintOptions { abortOnError true; warningsAsErrors true }` configuration, CI sees no failure on forgetting a `case`.
>     - **Resolution strategy: a defensive `default:` clause + lint-severity sharpening (combined, both necessary).**
>
>     **(a) A defensive `default:` branch in `HistoryAdapter.java:136-159`** — as the primary protection:
>
>     ```java
>     switch (status) {
>         case COMPLETED:
>             // ... bestehend ...
>             break;
>         case RECORDING:
>             // ... NEU (M4, §6.1.3) ...
>             break;
>         case TRANSCRIBING:
>             // ... NEU (M4, §6.1.3) ...
>             break;
>         case RECORDED:
>             // ... bestehend ...
>             break;
>         case FAILED:
>             // ... bestehend ...
>             break;
>         case CANCELLED:
>             // ... bestehend ...
>             break;
>         default:
>             // KG-SST-4: defensiv. Compiler warnt nicht bei fehlenden enum-Werten;
>             // wenn doch mal eine neue SessionStatus-Variante dazukommt, ohne dass
>             // dieser switch erweitert wird, schlagen wir laut Alarm statt still
>             // ein leeres Badge zu zeigen.
>             throw new IllegalStateException("HistoryAdapter: unknown SessionStatus " + status);
>     }
>     ```
>
>     **Why `throw` instead of `Log.e`?** It is a programming error, not a user error — the crash in the dev/staging build outs the gap immediately. In prod release builds the activity catches this via `try/catch` (see `HistoryAdapter` is in the RecyclerView bind path, a throw here crashes the RecyclerView loop) — more pragmatic would be a `Log.wtf(TAG, "...", e)` + `holder.statusIcon.setVisibility(GONE)`. An implementer decision on the patch write: the `throw` is the strictest variant, a `Log.wtf + GONE` the defensive middle path. **Default: `Log.wtf + GONE`**, because a RecyclerView crash because of a display inconsistency is inappropriate for the user.
>
>     **(b) Lint-severity sharpening** — as a secondary defense, supplementing (a):
>
>     ```kotlin
>     // app/build.gradle — neu im android { ... }-Block:
>     lint {
>         // EnumSwitch / EnumSwitchHandlesMissingCase als Error, nicht nur Warning.
>         // Built-in Android-Lint-Regel, kein zusätzliches Plugin nötig.
>         error += "EnumSwitch"
>         // Build bricht bei Errors ab; Warnings dürfen weiterhin durchlaufen
>         // (keine globale "warningsAsErrors true"-Aktivierung, weil das die
>         // gesamte Build-Pipeline strenger machen würde als heute).
>         abortOnError true
>     }
>     ```
>
>     **Cost check:** Activating the rule can newly bring existing Java `switch` sites that have no `default` today to a build fail. Therefore: run `./gradlew lint` once before activation, review all existing EnumSwitch findings and either fix them or annotate via `// noinspection EnumSwitchHandlesMissingCase`. Findings are typically few (≤5 in the entire app code, estimated by the grep pattern `switch \(.*\.values?\(\)`).
>
> - **Incorporation:**
>     - §6.1.3 patch — the `default:` branch added in the `HistoryAdapter.java` snippet (with the Log.wtf recommendation as the default + the throw alternative documented).
>     - §13 Block 3 implementation plan — a new bullet point "Lint setup: `lint { error += 'EnumSwitch'; abortOnError true }` in `app/build.gradle`, baseline cleanup first."

<!-- KNOWLEDGE-GAP: KG-SST-5 – Atomarität DB-Persist vs ActiveJobRegistry-Update [RESOLVED 2026-05-11] -->
> **✅ Knowledge gap (KG-SST-5): Atomicity DB-persist ↔ `ActiveJobRegistry` update — RESOLVED 2026-05-11**
>
> - **What we knew:** In the checkpoint hook (§6.2) `PipelineStateManager` calls the DB DAO + `ActiveJobRegistry.register/update`. If the DAO call fails, the registry and DB diverge.
> - **What we did not know:** Should the hook roll back the registry update on a DAO failure (consistency-first), or do we accept the divergence (availability-first)? Which order (cache→DB or DB→cache)?
> - **Resolution — availability-first, DB-first order:**
>
>     **Today's order (M3 code, verified 2026-05-11):** `JobExecutor.start()` (`JobExecutor.kt:96`) calls `ActiveJobRegistry.register(sessionId, initial)` **first**. `JobExecutor.finally` (l. 164) calls `ActiveJobRegistry.unregister(sessionId)` last. In between the pipeline runs, which calls `SessionManager.finalizeCompleted/Cancelled/Failed` (`SessionManager.kt:97-111` — pure DAO calls, NO `runInTransaction`, NO registry touch). The registry is thus set **before** the DB status update and removed **after** the DB status update. That is correct today for `JobExecutor.start/finally` (the registry = "running now, hands off"), but in the M4 checkpoint hook for RECORDING/TRANSCRIBING a different order is sensible.
>
>     **M4 order: DB first, then cache.** In the `DictateOrchestrator.Effect.PersistStatus` path (§6.2 checkpoint hook):
>     1. `SessionDao.updateStatus(sessionId, TRANSCRIBING)` — DB persist (atomic at the SQLite-statement level)
>     2. `ActiveJobRegistry.update(sessionId, newState)` — cache update
>
>     **Rationale:** The DB is the crash-safe source for OOM recovery. If the process dies between step 1 and 2, the DB is consistent (status = TRANSCRIBING), the registry is empty at the next start anyway (process-local). If step 1 fails (DAO exception): the pipeline reducer catches it as `Action.PipelineAction.PersistenceError` (§6.2, R.17), the registry is NOT updated, drift is zero. If step 2 fails (cannot actually happen, because `ActiveJobRegistry.update` is synchronous on an in-memory map): only the step counter is stale, fixes itself at the next reducer tick.
>
>     **Producer sites `JobExecutor.kt:96/:164` stay unchanged** — they are lock producers (`register` = lock-claim, `unregister` = lock-release), not status producers. DB status writes for RECORDING/TRANSCRIBING happen separately in the reducer hook.
>
>     **Drift tolerance documented:** The process-local cache is discarded at every app start (`ActiveJobRegistry` is a Kotlin `object` without persistent state — confirmed: `ActiveJobRegistry.kt:20-65`). No long-term drift possible. The persistence contract is pulled into the `SessionManager.kt` KDoc (`finalizeCompleted` etc.): "DB first, then cache. Drift tolerance: the process-local cache is discarded at every app start, no long-term drift possible."
>
> - **Incorporation:**
>     - §6.1.1 table "what changes" — the last line reversed: "Order: DB first, then cache" (instead of the earlier "first registry, then DAO call").
>     - §6.1.1 consumer table: a note block "persistence contract (cache ↔ DB)" with the DB-first rule.
>     - §6.2 persistence contract (R.17) gets a 5th bullet point: "**DB → cache order:** In the reducer hook for RECORDING/TRANSCRIBING the DB update applies before `ActiveJobRegistry.update`. On a DAO failure the registry call is skipped (no drift); on a process crash in between the DB is consistent, the registry is initialized empty at app start anyway."

<!-- FIX: Phase-B S-1 (2026-05-13) – Test-Tabellen auf F-11-Module-Pattern umgestellt. -->
#### §11.7.1 Existing tests that break

| Test | Block | Reason for breaking | Mitigation |
|---|---|---|---|
| `RecordingStateControllerTest.kt` | 1b | the class is deleted | Rewrite onto `RecordingModuleTest` — reducer-pure-function asserts instead of controller-field asserts. The state assertions change from `controller.state == X` to `RecordingModule.reduce(stateBefore, action, ctx).nextState == X`. |
| `MultiCallbackForwardingTest.kt` | 1b | the callback pattern disappears | the test is rebuilt onto an `orchestrator.state.collect` subscriber |
| `JobExecutorTest.kt` | (none) | unchanged | — |
| `ActiveJobRegistryTest.kt` | (none) | unchanged | — |
| `LanguageControllerTest.kt` | 1b | LanguageController moves into LanguageModule | rewrite onto `LanguageModuleTest` |

#### §11.7.2 New tests that are necessary

See §11.2.3 — a table per block.

#### §11.7.3 Test fakes

| Fake | File | Block | Purpose |
|---|---|---|---|
| `FakeLocalBinder` (LocalBinder stub) | `app/src/test/java/.../testutil/FakeLocalBinder.kt` (NEW) | 2 | IME tests without a Robolectric service |
| `FakeJobExecutor` | (exists via the `PipelineRunner` interface in `JobExecutor.kt:332`) | (none) | already present |
| `FakeAudioFocusGate` | `app/src/test/java/.../core/FakeAudioFocusGate.kt` (exists) | 1b | already present |
| `FakeModuleServices` | `app/src/test/java/.../testutil/FakeModuleServices.kt` (NEW) | 1b | DictateModule tests without real hardware adapters |
| `FakePipelinePrefMirror` | `app/src/test/java/.../testutil/FakePipelinePrefMirror.kt` (NEW) | 1b | DictateOrchestrator init-order test (records the attach order) |
| `FakePipelineSessionRepo` | `app/src/test/java/.../testutil/FakePipelineSessionRepo.kt` (NEW) | 1b | PipelineRecovery tests without Room |
| `FakeSessionDao` | `app/src/test/java/.../testutil/FakeSessionDao.kt` (NEW) | 3 | DAO tests without Room |

---

## §12 References

### Phase-2 research (input material)

- [_pending-ime-lifecycle-view-recreation.md](../_pending-ime-lifecycle-view-recreation/_pending-ime-lifecycle-view-recreation.md) — confirms: NO coroutines in the IME service today, NO WorkManager dependency.
- [_pending-persistence-background-architecture.md](../_pending-persistence-background-architecture/_pending-persistence-background-architecture.md) — Room v3 state, the sessions table, RECORDED status, JobExecutor + ActiveJobRegistry.
- [_pending-state-machine-visibility-owners.md](../_pending-state-machine-visibility-owners/_pending-state-machine-visibility-owners.md) — the complete visibility-mutation map, identifies the 5 problematic resend_btn places.

### Code pointers (today)

- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`
- `app/src/main/java/net/devemperor/dictate/core/RecordingStateController.kt`
- `app/src/main/java/net/devemperor/dictate/core/RecordingState.kt`
- `app/src/main/java/net/devemperor/dictate/core/KeyboardUiController.kt`
- `app/src/main/java/net/devemperor/dictate/core/PipelineUiState.kt`
- `app/src/main/java/net/devemperor/dictate/core/KeyboardStateManager.kt`
- `app/src/main/java/net/devemperor/dictate/core/RecordingUiController.kt` — site of today's `resendButton` mutations (see §13.1).
- `app/src/main/java/net/devemperor/dictate/core/InfoBarController.kt` — the view-local infoCl owner (see §13.1 entry 17).
- `app/src/main/java/net/devemperor/dictate/core/MainButtonsController.kt` — overlay-character mutations (§13.1 entry 18).
- `app/src/main/java/net/devemperor/dictate/core/JobExecutor.kt` (remains, used by the PipelineService code)
- `app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistry.kt` (remains)
- `app/src/main/java/net/devemperor/dictate/keyboard/EnterOverlayHandler.kt:56,62` — overlay long-press mutations (§13.1 entry 19).
- `app/src/main/AndroidManifest.xml:5-9, :29-40` — today's permissions + the IME-service entry.
- `app/build.gradle:8-19` — `compileSdkVersion 36`, `minSdk 26`, `targetSdk 35` (relevant for the FGS-type requirement from 34).
- `app/src/main/java/net/devemperor/dictate/database/DictateDatabase.kt:38, :73` — today's `version = 3` and the `addMigrations(...)` wiring.
- `app/src/main/java/net/devemperor/dictate/database/migration/Migrations.kt` — MIGRATION_1_2.
- `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo3.kt` — MIGRATION_2_3 (the reference format for M3→M4).
- `app/src/main/java/net/devemperor/dictate/database/dao/SessionDao.kt` — today's queries; M4 extends by `markInserted/findPendingInsertion/deleteInsertedOlderThan`.
- `app/src/main/java/net/devemperor/dictate/database/entity/SessionEntity.kt` — today's fields; M4 adds `inserted_at`.

### External references

- Foreground Service Lifecycle: https://developer.android.com/develop/background-work/services/foreground-services
- Bound Services: https://developer.android.com/develop/background-work/services/bound-services
- Room Migrations: https://developer.android.com/training/data-storage/room/migrating-db-versions
- FGS types from Android 14: https://developer.android.com/about/versions/14/changes/fgs-types-required
- POST_NOTIFICATIONS runtime permission: https://developer.android.com/develop/ui/views/notifications/notification-permission

### ADRs (Block-0 artifacts, bidirectional)

- [ADR-0001 — state-modular-orchestrator-pattern](../../../../decisions/0001-state-modular-orchestrator-pattern.md) — binds §3 (DictateUiState), §4 (DictateOrchestrator + the module interface), §15 (module inventory).
- [ADR-0002 — state-cross-module-cascade](../../../../decisions/0002-state-cross-module-cascade.md) — binds §4.3 (`dispatchInternal` step 5 + self-cascade), §15.5 (cross-module effect modes), §15.1.x (coupling matrix + the KG-RSB-3 convention).
- [ADR-0003 — service-foreground-pipeline-architecture](../../../../decisions/0003-service-foreground-pipeline-architecture.md) — binds §7 (foreground-service lifecycle), §11.1 (FGS details), §11.3 (bound-service setup), §11.6 (OOM-death recovery).

### Architecture docs (Block-0 artifacts, teaching/explanatory)

- [`docs/architecture/state-architecture/`](../../../../architecture/state-architecture/README.md) — index + 11 sub-files (state-and-actions, modules, effects-and-failures, cross-module-cascade, rendering, wiring-ui, triangle-fsm, adding-a-button, adding-a-module, adding-a-sub-keyboard, forbidden-patterns).

---

## §13 Completeness verification

> This section is the explicit answer to the user requirement *"complete centralization of state and functionality, with consistent SOLID/DRY application"*. It verifies that the refactor addresses all of today's scattered mutations and introduces no new duplicates.

### §13.1 Visibility-mutation audit

Source: `grep -rn "\.\(visibility\|setVisibility\)" app/src/main/java/net/devemperor/dictate/` with a filter on the `core/` package + `keyboard/EnterOverlayHandler.kt` (all other hits in `usage/`, `settings/`, `history/`, `rewording/` are settings/history UIs outside the IME refactor and stay unchanged).

| # | file:line | View | Status after the refactor |
|---|---|---|---|
| 1 | `RecordingUiController.kt:137` | `resendButton` | **MOVES INTO A PREDICATE** (LayoutCatalog `RESEND` slot, §9.4) |
| 2 | `RecordingUiController.kt:158` | `resendButton` | **MOVES INTO A PREDICATE** (LayoutCatalog `RESEND` slot, §9.4) |
| 3 | `DictateInputMethodService.java:1345` | `resendButton` | **MOVES INTO A PREDICATE** (LayoutCatalog `RESEND` slot, §9.4) |
| 4 | `DictateInputMethodService.java:1347` | `resendButton` | **MOVES INTO A PREDICATE** (LayoutCatalog `RESEND` slot, §9.4) |
| 5 | `DictateInputMethodService.java:1669` | `resendButton` | **MOVES INTO A PREDICATE** (LayoutCatalog `RESEND` slot, §9.4) |
| 6 | `DictateInputMethodService.java:1839` | `resendButton` | **MOVES INTO A PREDICATE** (LayoutCatalog `RESEND` slot, §9.4) |
| 7 | `KeyboardStateManager.kt:162` | `overlayCharactersLl` | **STAYS** — touch-handler/view-handler-internal (defensive reset of the transient overlay). Canonically audited in Spec 2 §13.1. <!-- FIX: Issue 3.0.8 – Cross-Spec-Konflikt mit Spec 2 §13.1 aufgelöst (Spec 2 ist kanonisch für IME-View-Visibility) --> |
| 8 | `KeyboardStateManager.kt:172-180` | `mainButtonsClTyped`, `editButtonsLl`, `qwertzContainer`, `emojiPickerCl` (4 sites) | **MOVE** into `LayoutCatalog.forKeyboard(state)` as a ContentArea-axis resolver (Spec 2 §8.5). |
| 9 | `KeyboardStateManager.kt:187` | `pauseButton` | **MOVES INTO A PREDICATE** (LayoutCatalog `PAUSE` slot, Spec 2 §3.2). |
| 10 | `KeyboardStateManager.kt:191` | `trashButton` | **MOVES INTO A PREDICATE** (LayoutCatalog `TRASH` slot, Spec 2 §3.2). |
| 11 | `KeyboardStateManager.kt:206` | `promptsCl` | **MOVES INTO A PREDICATE** (LayoutCatalog `PROMPTS_CONTAINER` slot). |
| 12 | `KeyboardStateManager.kt:210` | `promptsRv` | **MOVES INTO A PREDICATE** (LayoutCatalog `PROMPTS_LIST` slot). |
| 13 | `KeyboardStateManager.kt:212` | `pipelineProgressLl` | **MOVES INTO A PREDICATE** (LayoutCatalog `PIPELINE_PROGRESS` slot). |
| 14 | `KeyboardStateManager.kt:218` | `promptRecordingControlsLl` | **MOVES INTO A PREDICATE** (LayoutCatalog `PROMPT_RECORDING_CTL` slot). |
| 15 | `KeyboardUiController.kt:241` | `views.infoCl` | **STAYS** — view-local control within `startPipeline`; but semantically the mutation moves from here into the `InfoBarController` as a subscriber-driven reaction to `state.pipeline → Running`. Justified to stay in Spec 2 §9.5. |
| 16 | `KeyboardUiController.kt:383, :384, :388, :421, :422, :426, :447, :448` (8 sites) | `binding.iconTv`, `binding.pb`, `binding.durationTv` (within `addRunningStep / completeStep / failStep`) | **STAYS** — pure view-local logic within the `pipeline_step_row` dynamics. These visibility mutations are view-internal (not state-relevant) and form no cross-component race. Justified to stay. |
| 17 | `InfoBarController.kt:49, :57, :58, :65, :78, :94, :111, :116, :131, :139, :145, :154, :163` (13 sites) | `infoCl`, `infoYesButton`, `infoNoButton` | **STAYS** — `InfoBarController` is an encapsulated view owner (see `InfoBarController.kt:25-46`). The mutations are view-local within its responsibility. Calls from outside happen only via the `dismiss()` and `showInfo(type)` public API, not via direct visibility mutation. |
| 18 | `MainButtonsController.kt:251, :485, :487` | `views.overlayCharactersLl`, `charView` (overlay-character item) | **MOVES PARTLY** — `:251` (showing the overlay on long-press) moves conceptually into the `OVERLAY_CHARS` slot. `:485, :487` (per-character view within the overlay list) stay view-local — iteration over dynamically created char views, not state-relevant. |
| 19 | `EnterOverlayHandler.kt:56, :62` | `overlayCharactersLl` | **STAYS** — touch-handler-internal (canonically audited in Spec 2 §13.1 + §11.7; a touch-handler-internal state machine, a defensive reset). <!-- FIX: Issue 3.0.8 – Cross-Spec-Konflikt mit Spec 2 §13.1 aufgelöst --> |

**Verification:** 12 mutation sites are state-driven and move into a LayoutCatalog slot with a predicate (rows 1-6, 8-14, 18 — partial — in the table). Two sites (row 7 KSM:162 + row 19 EnterOverlayHandler:56,62) are **view-handler-internal** (a touch-handler-internal state machine, a defensive reset) and stay — canonically audited in **Spec 2 §13.1**. The 21 sites that are view-local (rows 15-17 + the 8 `KeyboardUiController` sites in row 16) stay with a clear rationale. **No state-driven visibility mutation is unaddressed in the refactor plan.**

> **Cross-spec note (FIX Issue 3.0.8):** Visibility mutations with IME-view scope (`overlayCharactersLl`) are canonically audited in **Spec 2 §13.1**; the table above here is the **cross-spec index**. If both tables differ from each other, Spec 2 §13.1 is the authoritative source (IME-view visibility lives in the keyboard-layout subsystem).

### §13.2 State-mutation audit

#### §13.2.1 Direct state-field mutations today

| # | file:line | Code | Class | Moves to? |
|---|---|---|---|---|
<!-- FIX: Phase-B S-1 (2026-05-13) – Targets auf F-11 (RecordingModule + RecordingState.audioFile, R.2) umgestellt. -->
| 1 | `RecordingStateController.kt:106-107` | `var state: RecordingState = Idle; private set` | `RecordingStateController` | `DictateUiStateStore._state` → `DictateUiState.recording` (mutated by `RecordingModule.reduce`) — YES |
| 2 | `RecordingStateController.kt:110` | `private var audioFile: File?` | `RecordingStateController` | moves into `RecordingState.Preparing/Active/Paused.audioFile` (a sub-field of the sealed-class variants, R.2 — pure-reducer guarantee) — YES |
| 3 | `RecordingStateController.kt:111` | `private var audioFocusEnabled: Boolean` | `RecordingStateController` | `DictateUiState.audio.audioFocusEnabledPref` — YES |
| 4 | `RecordingStateController.kt:355` | `state = newState` (in `setState`) | `RecordingStateController` | `_state.update { it.copy(recording = newState) }` — YES |
| 5 | `KeyboardUiController.kt:63-65` | `override var state: PipelineUiState = Idle; private set` | `KeyboardUiController` | `DictateUiState.pipeline` — YES |
| 6 | `KeyboardUiController.kt:67` | `private var config: AutoEnterConfig?` | `KeyboardUiController` | becomes a member of `DictateUiState.pipeline` (`Running.autoEnterActive`) — YES |
| 7 | `KeyboardUiController.kt:118-119` | `pipelineTotalTimer`, `latestPipelineElapsedMs` | `KeyboardUiController` | stays view-local (the timer is a view-display detail, not state) — accepted |
| 8 | `KeyboardUiController.kt:133-136` | `stepRows`, `totalSteps`, `currentStep`, `activeTimer` | `KeyboardUiController` | stays view-local — accepted |
| 9 | `KeyboardUiController.kt:149` | `state = newState` (in `updateDictateUiState`) | `KeyboardUiController` | `_state.update { it.copy(pipeline = newState) }` — YES |
| 10 | `KeyboardStateManager.kt:100` | `var contentArea: ContentArea = MAIN_BUTTONS; private set` | `KeyboardStateManager` | `DictateUiState.contentArea` — YES |
| 11 | `KeyboardStateManager.kt:102` | `var isSmallMode: Boolean = false; private set` | `KeyboardStateManager` | `DictateUiState.layout.smallMode` — YES |
| 12 | `KeyboardStateManager.kt:136-137` | `contentArea = area` (in `setContentArea`) | `KeyboardStateManager` | `_state.update { it.copy(contentArea = area) }` — YES |
| 13 | `KeyboardStateManager.kt:141-145` | `isSmallMode = enabled; contentArea = MAIN_BUTTONS` (in `setSmallMode`) | `KeyboardStateManager` | atomic `_state.update { it.copy(layout = it.layout.copy(smallMode = enabled), contentArea = MAIN_BUTTONS) }` — YES, eliminates the coupled-mutation problem (today two sequential writes, future atomic) |
| 14 | `KeyboardStateManager.kt:113-117` | `private var layoutModeController: ...` | `KeyboardStateManager` | removed entirely (the class is deleted in Spec 2) — YES |
| 15 | `KeyboardUiController.kt:138` | `private var savedRecordButtonTextColors` | `KeyboardUiController` | stays view-local — accepted |
<!-- FIX: Phase-B S-1 (2026-05-13) – Zeile 16 in 16a-16f aufgespalten (pro Service-Feld einen expliziten Migrations-Ziel). Frühere Sammelzeile war zu vage und ließ den restoreAutoEnter/restoreReprocessStaging-Felder offen, ob sie ersatzlos gestrichen oder umgezogen werden. -->
| 16a | `DictateInputMethodService.java:112` | `private boolean livePrompt` | Service | `DictateUiState.livePrompt.enabled` (sub-state, §3) — YES |
| 16b | `DictateInputMethodService.java:113` | `private volatile boolean pendingLivePromptChain` | Service | `DictateUiState.livePrompt.pendingChain` (sub-state, §3) — YES |
| 16c | `DictateInputMethodService.java:114` | `private boolean vibrationEnabled` | Service | `DictateUiState.audio.vibrationEnabled` (pref-mirror, §3 + §4.5) — YES |
| 16d | `DictateInputMethodService.java:121` | `private boolean autoSwitchKeyboard` | Service | Stays a **local service field** — represents a pre-IME-switch toggle that is only relevant in the IME-service lifecycle. NOT UI state, NO cross-consumer other than the IME itself. Accepted as view-local. |
| 16e | `DictateInputMethodService.java:131` | `private Boolean restoreAutoEnter` | Service | **Removed without replacement** — the view-recreate bridge is removed. The state `PipelineUiState.Running.autoEnterActive` lives after Block 1b in the PipelineService StateFlow that structurally survives the view recreate (Spec 1 D1). A subscriber in the new `viewScope` (Spec 1 §8.x) gets the value automatically on re-attach via the first `state.collect` emission. |
| 16f | `DictateInputMethodService.java:142` | `private PipelineUiState.ReprocessStaging restoreReprocessStaging` | Service | **Removed without replacement** — the view-recreate bridge is removed for the same reason as 16e. The `PipelineUiState.ReprocessStaging(sessionId, transcript)` state lives in the StateFlow; after `onCreateInputView` it is re-read via `state.collect`. **Block-1 acceptance:** `cleanupOldControllers()` must no longer capture the field (see §8.x view-recreate contract). |
| 17 | `ActiveJobRegistry.kt:28-31` | `MutableStateFlow<Map<String, JobState>>` | `ActiveJobRegistry` | stays unchanged (job tracking is orthogonal to UI state) — accepted |
| 18 | `JobExecutor.kt:36-54` | `activeToken`, `activeThread`, `orchestrator` | `JobExecutor` (object) | stays unchanged — accepted |

<!-- FIX: Phase-B S-1 (2026-05-13) – Verifikations-Block auf F-11 umgestellt (Modul-Reducer + DictateUiStateStore statt monolithischer PipelineStateManager); Sites-Liste erweitert für 16a–16f. -->
**Verification:** All 16 UI-state-relevant mutation sites (rows 1, 3, 4, 5, 6, 9, 10, 11, 12, 13, 16a, 16b, 16c) move into `DictateUiStateStore` via module `reduce` calls (F-11). **Today's 3 independent state holders** (RecordingStateController + KeyboardUiController + KeyboardStateManager) **are eliminated** in favor of one **single source of truth** (`DictateUiStateStore.state: StateFlow<DictateUiState>`).

The 7 view-local fields (rows 2, 7, 8, 14, 15, 17, 18) plus 3 rows 16d/16e/16f (service-field migration in §13.2.1 above) stay justified view-local or are removed without replacement: a view-display detail, job tracking, the class is deleted entirely, or the view-recreate bridge is removed.

#### §13.2.2 SP reads with state character

Some UI-state axes are read on-demand from `SharedPreferences` today instead of being held in state:

| Pref key | Read today in | Moves into `DictateUiState`? |
|---|---|---|
| `Pref.SmallMode` | `DictateInputMethodService.java:1025, :1402, :2632, :2634` | NO — stays a pref, but `DictateUiState.layout.smallMode` mirrors the value. The SP listener triggers a state update. |
| `Pref.SingleRowMode` | `DictateInputMethodService.java:2652-2654`; `KeyboardLayoutModeController.kt:100, :151` | NO — stays a pref, `DictateUiState.layout.singleRowMode` mirrors. |
| `Pref.AudioFocus` | `DictateInputMethodService.java:580, :664, :2671-2674`; `RecordingStateController.kt:194` | NO — stays a pref, `DictateUiState.audio.audioFocusEnabledPref` mirrors. |
| `Pref.ResendButton` | `DictateInputMethodService.java:1344, :1694` | NO — stays a pref, `DictateUiState.resend.resendEnabled` mirrors. |
| `Pref.LastFileName` | `DictateInputMethodService.java:1343, :1408, :1613, :1693` | NO — cache-file tracking stays pref-driven. `DictateUiState.resend.lastAudioExists` mirrors file existence. |
| `Pref.Animations` | `DictateInputMethodService.java:611, :1399`; `KeyboardLayoutModeController.kt:123, :453` | NO — stays a pref. `DictateUiState.layout.animationsEnabled` is redundant for the UI resolver, but consistently mirrored. |
| `Pref.AutoEnter` | `DictateInputMethodService.java:1010, :1679, :1764-1766, :1891, :2532` | PARTLY — the initial value comes from the pref, the runtime toggle is `PipelineUiState.Running.autoEnterActive` |
| `Pref.OverlayPositionPortraitX/Y` | NEW (OPEN-3) | YES — `DictateUiState.overlay.positionPortraitX/Y` mirrors. Write trigger: `updateOverlayPosition(portrait=true, ...)` from `OverlayBackend` (Spec 3 §11.5). |
| `Pref.OverlayPositionLandscapeX/Y` | NEW (OPEN-3) | YES — `DictateUiState.overlay.positionLandscapeX/Y` mirrors. Write trigger: `updateOverlayPosition(portrait=false, ...)` from `OverlayBackend` (Spec 3 §11.5). |

<!-- FIX: Phase-B S-1 (2026-05-13) – Spiegelung-Pattern-Aussage auf PipelinePrefMirror (§4.5) umgestellt. Pre-F-11-Text behauptete der Manager halte die Pref-Reads — falsch nach F-11: PrefMirror ist eigene Klasse, vom Orchestrator-`init` attached. -->
**Mirroring pattern:** the `PipelinePrefMirror` (§4.5) reads all relevant prefs at `attach(store)` and calls `store.update { initialMirror(it) }`. A `SharedPreferences.OnSharedPreferenceChangeListener` triggers further `store.update` calls (`sync(key)`), so that settings-activity writes arrive reactively in the IME. **Order invariant:** `prefMirror.attach(store)` runs in `DictateOrchestrator.init` synchronously BEFORE `scope.launch { recovery.recover(store) }` — see §4.3.

```kotlin
// Hinweis: Snippet illustriert das Spiegelungs-Pattern. Die kanonische Implementierung
// lebt im PipelinePrefMirror (§4.5) und nutzt die hierarchische Sub-State-Struktur
// aus Spec 1 §3 (LayoutState / AudioState / ResendState / OverlayState).

init {
    val base = DictateUiState.initial()
    val initial = base.copy(
        // <!-- FIX: Issue 1.1.5 / R.5 – LayoutState statt LayoutPrefs (contentArea bleibt default initial) -->
        layout = LayoutState(
            contentArea       = base.layout.contentArea,
            singleRowMode     = sp.get(Pref.SingleRowMode),
            smallMode         = sp.get(Pref.SmallMode),
            animationsEnabled = sp.get(Pref.Animations),
        ),
        audio  = base.audio.copy(audioFocusEnabledPref = sp.get(Pref.AudioFocus)),
        resend = base.resend.copy(resendEnabled = sp.get(Pref.ResendButton)),
        overlay = base.overlay.copy(
            positionPortraitX  = sp.getFloat(Pref.OverlayPositionPortraitX, 1.0f),
            positionPortraitY  = sp.getFloat(Pref.OverlayPositionPortraitY, 0.1f),
            positionLandscapeX = sp.getFloat(Pref.OverlayPositionLandscapeX, 1.0f),
            positionLandscapeY = sp.getFloat(Pref.OverlayPositionLandscapeY, 0.1f),
        ),
    )
    _state.value = initial
    sp.registerOnSharedPreferenceChangeListener(prefListener)
}

private val prefListener = OnSharedPreferenceChangeListener { _, key ->
    _state.update { current -> when (key) {
        Pref.SingleRowMode.key -> current.copy(layout = current.layout.copy(singleRowMode = sp.get(Pref.SingleRowMode)))
        Pref.SmallMode.key     -> current.copy(layout = current.layout.copy(smallMode = sp.get(Pref.SmallMode)))
        // ... (vollständig in §4.5 PipelinePrefMirror)
        else -> current
    }}
}
```

<!-- FIX: Phase-B S-1 (2026-05-13) – OPEN-3-Mutation auf F-11 (OverlayAction.UpdatePosition + OverlayModule) umgestellt. -->
#### §13.2.3 New state mutation (OPEN-3)

| # | Action / module | Write effect | Trigger location |
|---|---|---|---|
| 1 | `Action.OverlayAction.UpdatePosition(portrait, x, y)` → `OverlayModule.reduce` | `state.copy(overlay = state.overlay.copy(position{Portrait\|Landscape}{X\|Y} = ...))` + the `Effect.PersistOverlayPosition` effect handler writes `Pref.OverlayPosition*` (atomic via `apply()`) | `OverlayBackend.OnTouchListener#onUp` (drag-end, Spec 3 §11.5). Only dispatched if the move distance > threshold (8dp). |

**Verification:** The only new state mutation in OPEN-3 runs through
`DictateOrchestrator.dispatch(Action.OverlayAction.UpdatePosition(...))` — no
direct pref write from `OverlayBackend`, no direct view mutation on the
overlay window from the settings screen. Thus the state-SSOT invariant
stays unviolated: ALL mutations go through the orchestrator + module reducer (F-8).

### §13.3 SOLID verification per new class

> **Iteration 2026-05-09/10 (F-8, F-10, F-11):** This section was
> fundamentally reworked. Earlier audits checked the `PipelineStateManager`
> as the Composition Root with typed action methods. With the Modular-Orchestrator
> Pattern (F-11) the central class is now the `DictateOrchestrator` that
<!-- FIX: Phase-C C-1 (2026-05-14) – Modul-Zähler aktualisiert auf 13 aktiv (12 ursprünglich + KeyboardInputModule §15.6). -->
> only knows the `DictateModule` interface; action logic moves into 13 active modules
> (plus 1 Phase-2 stub, see §15). The audit is structured per layer accordingly.
>
> **Scope split (FIX Issue 3.0.6):** §13.3 audits the **layer classes** —
> Service (lifecycle), Orchestrator (routing), helpers (Notification, ActionRouter,
> Pref-Mirror, Recovery, Store) plus the `DictateModule` interface itself.
<!-- FIX: Phase-C C-1 (2026-05-14) – Modul-Zähler 13 → 13 aktive (siehe §15.1 Tabelle: 13 aktive + 1 Phase-2). -->
> **§15 is the canonical audit location for the 13 active module implementations (+ 1 Phase-2 stub)** —
> the functional SRP/OCP rationale per module lives there. §13.3.13 here only shows
> the pattern via the `RecordingModule` example.

#### §13.3.1 DictatePipelineService (downsized after F-3, unchanged)

- **SRP** — Responsibility: *process-lifecycle owner*. Concretely: FGS lifecycle (`startForeground` / `stopSelf`), bind connection, JobExecutor init (G7). Notification build delegated to `PipelineNotificationCoordinator`, action routing to `PipelineActionRouter`, state mutation to `DictateOrchestrator`. The service has exactly these three helper fields + lifecycle hooks.
- **OCP** — New notification content goes into the coordinator; new action strings go into the router. The service class itself stays invariant.
- **LSP** — no inheritance-hierarchy issue (inherits from `Service` directly; `LocalBinder` is an inner class).
- **ISP** — `LocalBinder` exposes only `state` + `dispatch(action)` + 2 lifecycle hooks. Minimal, no typed forwarders anymore (F-8).
- **DIP** — the service constructs helper classes in `onCreate` via their constructors; all helpers hang on interfaces.

#### §13.3.2 DictateOrchestrator (F-11, formerly PipelineStateManager)

- **SRP** — Responsibility: *action routing + cross-module cascade dispatch*. Knows NO concrete modules (only the `DictateModule` interface), NO hardware calls, NO state logic. Pure routing + composition.
- **OCP** — A new module = a new module file + an entry in `DictateModuleRegistry.all`. The orchestrator code is unchanged. The highest OCP score of all classes checked so far.
- **LSP** — Modules are polymorphically interchangeable. Tests can inject `FakeRecordingModule`.
- **ISP** — `state` (read) + `dispatch` (write) + `shutdown` — minimal.
- **DIP** — The constructor hangs on a `DictateModule<*, *, *>` list, `DictateUiStateStore`, `ModuleServicesFactory`, `PipelinePrefMirror`, `PipelineRecovery` — all interfaces or thin containers. No concretizations.

#### §13.3.2b LocalBinder (F-8)

- **SRP** — bound-IPC layer: only `state` + `dispatch` + 2 lifecycle forwarders. No behavior.
- **OCP** — A new action = a new sealed-class variant. LocalBinder unchanged.
- **DIP** — Hangs only on the `DictateOrchestrator` object (an inner class).

#### §13.3.3 DictateUiStateStore (F-1)

- **SRP** — Pure StateFlow management. One public method `update(reducer)` plus the read properties `state` and `snapshot`. No action logic.
- **OCP** — Extension happens via new reducer functions called in the manager methods — the store itself stays invariant.
- **DIP** — No dependencies; a pure wrapper over `MutableStateFlow`.

#### §13.3.4 ViewModeFsm — moved

> **Audit section moved:** see §15.1 (ViewModeModule) — the audit lives at the canonical module location. <!-- FIX: Issue 3.0.6 – §13.3 audited Schicht-Klassen (Service / Orchestrator / Helper); §15 ist kanonische Audit-Stelle für Modul-Klassen -->
>
> Background (for reader context): the former `ViewModeFsm` class from the F-1 pass no longer exists standalone after F-11. Its logic is part of the `ViewModeModule` (§15.1) — the triangle-FSM reducer is now a `reduce()` method on the module, no longer a separate pure-function object. The SOLID rationale applies unchanged: pure function, no side effects, an exhaustive `when` block, the truth table testable.

#### §13.3.5 PipelinePrefMirror (F-1)

- **SRP** — encapsulates the pref-mirroring pattern. Reads at attach + on every pref change; mutates exclusively the store via `update { ... }`. No action logic, no FSM knowledge.
- **OCP** — A new pref = a new branch in the `sync(key)` when + a new column in `initialMirror`. Other classes untouched.
- **DIP** — Hangs on `SharedPreferences` (Android API). Test doubles via `FakeSharedPreferences` possible.

#### §13.3.6 PipelineRecovery (F-1)

- **SRP** — DB replay → store. One `suspend fun recover(store)`, nothing else.
- **OCP** — New recovery steps (e.g. additional tables) extend `recover()`. Other classes untouched.
- **DIP** — Hangs on the `PipelineSessionRepo` interface (F-2). Testable with `FakePipelineSessionRepo`.

#### §13.3.7 PipelineNotificationCoordinator (F-3)

- **SRP** — State → notification render + subscription management with throttling. No action logic, no lifecycle knowledge except terminal detection.
- **OCP** — New notification content = a new branch in `build(state)`. The subscription mechanism is invariant.
- **DIP** — Hangs on the `Service` context (for `NotificationManagerCompat`) and on `StateFlow<DictateUiState>`. Both interchangeable in tests via `Robolectric` or `FakeNotificationManager`.

#### §13.3.8 PipelineActionRouter (F-3, post-F-8 — Single Dispatch)

<!-- FIX: Issue 3.0.6 – Pre-F-8-Vokabular ("PipelineStateManager-Methode" / typed Action-Routing-Ziel) auf F-8/F-11 umgestellt -->

- **SRP** — pure mapping `Intent.action → Action sealed-class variant`. The dispatch happens via the injected `DictateOrchestrator` (`orchestrator.dispatch(action)`). No UI logic, no notification build, no typed forwarder methods anymore (removed with F-8).
- **OCP** — A new action = a new branch in the `dispatch(intent)` when + a new constant in `companion`. The orchestrator + other classes untouched.
- **DIP** — Hangs on the `DictateOrchestrator` (Single-Dispatch API: `state` + `dispatch(action: Action)` + lifecycle hooks). Tests inject a `Mock<DictateOrchestrator>` and verify that `dispatch` is called with the correct `Action` variant (e.g. `Action.PipelineAction.CancelPipeline`).

#### §13.3.9 LocalBinder — moved

> **Audit section moved:** see §13.3.2b for the canonical F-8 audit section (Single Dispatch). <!-- FIX: Issue 3.0.6 – §13.3.9-Stub explizit als „Verschoben"-Marker gekennzeichnet, statt als forward-Reference -->

#### §13.3.10 DictateUiState (data class, hierarchical after F-10)

- **SRP** — Pure immutable data. The top-level container holds only sub-state classes (`audio`, `layout`, `overlay`, `resend`, …) plus the hot-path FSMs (`recording`, `pipeline`, `viewMode`). No logic except trivial helper properties.
- **OCP** — A new state axis = a new sub-state class + a new field in DictateUiState. Existing subscribers keep compiling (a default value or a new sub-state class with default values). Sealed sub-classes (`RecordingState`, `PipelineUiState`, `ScoPhase`) allow extension via `when` exhaustiveness.
- **LSP** — no inheritance except via sealed classes (closed hierarchies).
- **ISP** — No interfaces; a pure data model.
- **DIP** — No dependencies (pure data). List fields as `PersistentList<T>` for structural immutability (F-9).

#### §13.3.11 PipelineSessionRepo + PipelineRunner (F-2 / DIP, unchanged)

- **SRP** — Both are pure interface definitions (a data repository or a job runner). The concretizations (`RoomPipelineSessionRepo`, `JobExecutor`) are in separate classes.
- **OCP** — Extension via interface methods; the concretizations adapt.
- **LSP** — Test doubles (`FakePipelineSessionRepo`, `FakePipelineRunner`) substitute the concretizations 1:1.
- **ISP** — minimal: only the methods the orchestrator and effect handler actually need.
- **DIP** — *is* the abstraction the orchestrator hangs on. Fully fulfilled.

#### §13.3.12 DictateModule interface (F-11, plugin contract)

<!-- FIX: Phase-C C-1 (2026-05-14) – Method-Zähler aktualisiert: nach Phase-B S-3 (reduceFailure)
     + Issue 2.1.2 (prefBindings) + Issue 2.1.12 (terminate) hat das Interface jetzt 7 Pflicht-
     Methoden (id, actionClass, read, write, initialState, reduce, runEffect) + 4 optionale Hooks
     mit Default-Body (reduceFailure, onCrossModuleStateChange, prefBindings, terminate). Pre-S-3
     waren es "5 Pflicht + 1 optional" — gilt nach den Phase-B-Erweiterungen nicht mehr. -->
- **SRP** — Defines the plugin contract. Itself no logic; only an interface with 7 mandatory methods + 4 optional hooks (default implementations).
- **OCP** — `sealed interface` with `object` implementations per module. A compile-time hierarchy, an exhaustive `when` possible.
- **LSP** — All modules implement the same contract with their own type parameters; polymorphically interchangeable.
- **ISP** — Minimal: 7 mandatory methods (id, actionClass, read, write, initialState, reduce, runEffect) + 4 optional default hooks (reduceFailure, onCrossModuleStateChange, prefBindings, terminate). No method that a module does not need.
<!-- FIX: Phase-C C-1 (2026-05-14) – 13 → 13 aktiv + 1 Phase-2 (KeyboardInputModule wurde in Phase-B S-3 als 13. aktives Modul ergänzt). -->
- **DIP** — A pure interface. The concretizations are the 13 active modules in §15 (plus 1 Phase-2 stub `InterruptionModule`).

#### §13.3.13 Module implementations (F-11, via the RecordingModule example)

- **SRP** — One functional domain **per module**. The recording module knows: recording state, recording actions, recording side effects, recording effect handler. It knows NO pipeline logic, NO audio logic. Cross-module effects go via `onCrossModuleStateChange` (an action cascade).
- **OCP** — A new recording action = a new variant in `Action.RecordingAction` + a new `when` branch in `reduce`. Other modules untouched. The compiler enforces exhaustiveness.
- **LSP** — Module implementations are substitutable via test fakes (see the tests in §14).
- **ISP** — The module exposes only what the `DictateModule` interface requires. No additional public API.
- **DIP** — `runEffect(effect, services)` hangs on the `ModuleServices` container that in turn hangs on subsystem interfaces. Tests inject `FakeModuleServices`.

#### §13.3.14 DictateModuleRegistry (F-11)

- **SRP** — The central list of all modules + a sanity check (unique IDs, unique actionClasses).
- **OCP** — A new module = one entry in `all`. The init check catches double registrations.
- **DIP** — A pure data list; no behavior.

#### §13.3.15 ModuleServices + ModuleServicesFactory (F-11)

- **SRP** — A DI container for subsystem hardware adapters. The factory delivers the services lazily at the service onCreate.
- **OCP** — A new subsystem dependency = a new field in `ModuleServices`. Modules that need it read it; others ignore it.
- **DIP** — The fields are all interfaces or subsystem classes with their own interfaces. Tests inject `FakeModuleServices` with `FakeRecordingHardware` etc.

### §13.4 DRY verification

#### §13.4.1 Logic duplicated today

| Duplicate | Today (file:line × count) | Future (single-source) |
|---|---|---|
| **Resend-visibility computation** | 6 sites in 2 files (see the §13.1 table) | 1 predicate in the `LayoutCatalog.RESEND` slot |
| **`isRecordingOrPaused` / `isRecordingOrPaused() \|\| Preparing` checks** | `DictateInputMethodService.java:486, :757, :1066, :2228-2230, :2570-2571`, `KeyboardStateManager.kt:184, :196` | 1 helper on `DictateUiState`: `state.recording.isActiveOrPending` (an extension property) |
| **`recordButton.text` set** | `RecordingUiController.kt:115, :144, :146` (Idle/Active) + `KeyboardUiController.kt:464-509` (Preparing/Running/Staging) | 1 resolver `LayoutCatalog.RECORD.textResolver(state)` |
| **`recordButton.isEnabled` set** | `RecordingUiController.kt:117, :141, :145, :531`, `KeyboardUiController.kt:467, :472, :480, :495` | 1 resolver `LayoutCatalog.RECORD.enabledResolver(state)` |
<!-- FIX: Phase-B S-1 (2026-05-13) – DRY-Tabelle auf F-11 umgestellt: AudioModule/AudioPrefMirror statt PipelineStateManager. -->
| **AudioFocus on-toggle reaction** | `DictateInputMethodService.java:664-672` (SP listener) + `:2664-2687` (user toggle) | 1 path: `Action.AudioAction.ToggleAudioFocusPref` → `AudioModule.reduce` (user click) **and** `PipelinePrefMirror.sync(Pref.AudioFocus.key)` → `store.update` (SP listener) — both end in the same state axis `state.audio.audioFocusEnabledPref` <!-- FIX: Phase-B S-3 (2026-05-13) – Naming-Drift behoben (Spec 2 §3.3 SoT). --> |
| **`Pref.SmallMode` apply** | `DictateInputMethodService.java:1025, :1402, :2632-2634` | 1 branch in `PipelinePrefMirror.sync(Pref.SmallMode.key)` (§4.5) |
| **`Pref.AudioFocus` apply** | `:580, :664, :2685` (3 sites with identical `mainButtonsController.refreshAudioFocusIcon` boilerplate) | 1 subscriber: `state.collect { state -> mainButtonsController.refreshAudioFocusIcon(state.audio.audioFocusEnabledPref) }` |
| **`getLastAudioFileExists()` file check** | `DictateInputMethodService.java:611-613, :1343-1344, :1693-1694` | 1 effect `ResendModule.Effect.RefreshLastAudioExists`, called by the RecordingModule on the transition Active → Idle (cross-module cascade, see §15 + the coupling matrix §15.1.x). Result as `state.resend.lastAudioExists`. |

<!-- FIX: Issue 3.1.13 / R.21 – Cross-Spec-DRY-Tabelle (Symbol/Definition/Konsumenten) -->
#### §13.4.1b Cross-spec DRY table (R.21)

| Symbol | Definition | Consumers |
|--------|------------|-------------|
| `predResendVisible` | Spec 2 §8.5 (a central predicate helper) | Spec 2 `forKeyboard` resend_btn resolver, Spec 3 OVERLAY_RESEND slot (where present), Spec 1 §13.1 audit |
| `state.isIdle` | `state/Predicates.kt` (R.21) — `recording is Idle && pipeline is Idle` | Spec 2 §8.5 visibility resolver, Spec 3 §3.1 OVERLAY_RECORD resolver |
| `state.predRecordingControlsVisible` | `state/Predicates.kt` (R.21) — `recording.isActiveOrPaused` | Spec 2 §8.5, Spec 1 §13.1 |
| `OverlayPositionMapper` | Spec 3 §4.7 | Spec 3 §4.3 `applyPosition`, Spec 1 §6.4 PrefMirror |
| `View.effectiveSize()` | Spec 3 §4.7 (helper, R.19) | Spec 3 §4.3 `applyPosition`, Spec 3 `OverlayPositionMapper.normalizedToPixels` / `pixelsToNormalized` |

**Predicates.kt** (`app/src/main/java/net/devemperor/dictate/state/Predicates.kt`):

```kotlin
val DictateUiState.isIdle: Boolean
    get() = recording is RecordingState.Idle && pipeline is PipelineUiState.Idle

val DictateUiState.predRecordingControlsVisible: Boolean
    get() = recording.isActiveOrPaused
```

#### §13.4.2 New duplicates that must not arise in the plan

| Risk | Mitigation |
|---|---|
| `buildNotification` could duplicate subtitle/action logic with `LayoutCatalog` resolvers | NOT accepted. The `notifSubtitleFor(state)` function (§11.1.2) is a state-→-string mapping; it uses NO view resolvers, only writes notification strings. A clear separation visual-IME-state (LayoutCatalog) vs. notification state. If a refactor reviewer discovers code duplication: extract a shared helper `state.toUserVisibleSummary()`. |
| Service and IME service both have a `scope` (`CoroutineScope`) | Accepted: two different scopes with different lifetimes. The IME `viewScope` is cancelled on the view recreate; the service `serviceScope` lives with the service. Naming explicit: `viewScope` vs. `serviceScope` — no accidental cross-use. |
<!-- FIX: Phase-B S-1 (2026-05-13) – Code-Review-Checkliste auf PipelinePrefMirror umgestellt. -->
| Pref mirroring in `DictateUiState` and a pref read in individual module reducers at the same time | NOT accepted. **Rule:** once a pref is mirrored in `DictateUiState`, module reducers read ONLY from `ctx.global.X`, never directly from `sp`. Code-review checklist: `sp.get(Pref.SmallMode)` may only occur in `PipelinePrefMirror.initialMirror`/`sync`. |
<!-- FIX: Phase-B S-4 (2026-05-13) – Phase-1 vs. Phase-2: prefBindings()-Override nur Default emptyList(). -->
| A module override of `prefBindings()` in Phase 1 | NOT accepted. **Rule:** In Phase 1 all modules leave the default `emptyList()` implementation; `PipelinePrefMirror` (§4.5) uses a hardcoded list, no `modules.flatMap { prefBindings() }`. Code-review checklist: an `override fun prefBindings()` with a non-empty body is Phase-2 code (main plan §7.1 out-of-scope) and is blocked in the Block-1b audit. |

### §13.5 Identified gaps + mitigations

<!-- FIX: Issue 3.0.7 – §13.5 in drei Bereiche getrennt (Open / Cross-Spec-Pending / Resolved); Audit-Funktion wieder klar -->

#### §13.5.a Open gaps (currently open, to be addressed in the Block-1/2 scope)

| # | Gap | Severity | Mitigation |
|---|---|---|---|
| G1 | `KeyboardUiController.kt:241` mutates `views.infoCl.visibility = GONE` directly in `startPipeline` — that is a state-triggered mutation that should run via the helper class `InfoBarController.dismiss()` today, but goes directly. | Medium | In Block 1: switch the mutation site to `infoBarController.dismiss()` — afterwards InfoBarController has sole responsibility over infoCl. |
| G2 | `DictateInputMethodService.java:2630-2636` (`onSmallModeToggled`) writes directly into `Pref.SmallMode` AND calls `stateManager.setSmallMode(newSmallMode)` — two steps that can be out-of-sync in rare cases. | Low | In Block 1, with the DictateUiState pref-mirror pattern (§13.2.2), the state is automatically consistent — the explicit `setSmallMode` call becomes redundant and is removed. |
| G6 | Service death during an active recording: `RecordingManager.stop()` is no longer called → the MediaRecorder stays in the native heap. | Medium | Two paths explicitly separated: **(A) Service.onDestroy normal (testable)** — the service calls <!-- FIX: Phase-C C-3 (2026-05-14) – CancelPipeline → CancelRecording + Effect.ReleaseRecording → Effect.ReleaseMediaRecorder. Vorherige Variante referenzierte einen `Effect.ReleaseRecording`, der NICHT existiert (RecordingModule.Effect-Liste in §15.2 kennt ausschließlich `ReleaseMediaRecorder`); plus die Action wurde fälschlich an PipelineModule geroutet, das die Recording-Hardware nicht hält. C-3-Disambiguation: Action gehört zur Recording-Achse, Effect ist `Effect.ReleaseMediaRecorder` (RecordingModule.Effect). --> `orchestrator.dispatch(Action.RecordingAction.CancelRecording)` → the `RecordingModule` reducer (§15.2 reducer arm `Active/Paused/Preparing+CancelRecording`) emits `Effect.ReleaseMediaRecorder` (+ optionally `Effect.DeleteAudioFile`) → `runEffect` calls `services.recordingHardware.release()`. The `release()` path is verified via a mock spy in the Block-2 unit test (see §10 Block-2 acceptance). **(B) Process kill (not testable)** — the Android system cleanup releases the MediaRecorder and the native heap itself. Accepted. |
| G7 | `JobExecutor.initialize(orchestrator)` is called today in the IME `onCreate` (l. 389) — with the service refactor that must move into the service onCreate. If the IME service boots up without the Pipeline Service (theoretically not possible, but defensive), `JobExecutor` is uninitialized. Note: the `JobExecutor.initialize(orchestrator)` (l. 56-58 verified via code read) expects the **old `PipelineOrchestrator`** (audio pipeline runner), NOT the new `DictateOrchestrator` — see §1.x naming convention (Phase-B S-4). | Low | `bindService` ties the service lifecycle to the IME — there is no lifecycle sequence in which the IME runs without the Pipeline Service once the bind connection is established. If necessary for robustness: a defensive `null` check in JobExecutor + lazy init at the first job start. |

#### §13.5.b Cross-spec patches pending

(Currently no open cross-spec patches in Spec 1. Spec-2 entries `WIDGET_TOGGLE` see Spec 2 §13.5.b; Spec-3 state axes `state.overlay.*` are modeled via F-10 in §3 and thus RESOLVED — see the §13.5.c G3 entry there.)

#### §13.5.c Resolved (iteration history)

| # | Gap | Status | Resolution |
|---|---|---|---|
| G3 | `DictateInputMethodService.java:914-958` (`recordingStateController.setCallback`) is set again on every `onCreateInputView` → a leak risk with an old callback. | RESOLVED via F-1 / F-11 | The Block-1 migration eliminates this pattern. The subscriber is automatically detached on the view recreate via `viewScope.cancel()` (StateFlow pattern). |
| G4 | `KeyboardUiController.callbacks: CopyOnWriteArrayList<PipelineUiCallback>` (l. 82) — its own multi-callback mechanism today. | RESOLVED via F-1 / F-11 | In Block 1 the callback-list mechanism is removed together with `KeyboardUiController.state`. Subscribers go via `StateFlow.collect`. |
| G5 | At the first pipeline start 3 SP reads are needed at the same time (`Pref.LastFileName`, `Pref.ResendButton`, `Pref.AudioFocus`) — a small but existing race window. | RESOLVED via F-10 (pref mirror) | Since the prefs are mirrored (§13.2.2), the action method reads from `_state.value` (atomic). The race disappears structurally. |

---

## §14 Open Questions

Deliberately left open, because it goes beyond library-specific knowledge that is verifiably missing against the Android docs:

1. **`MotionLayout`/`Transition` interaction with foreground-service notification updates** — if the notification is updated 60 times per second (e.g. a recording timer), does Android throttle that? Recommendation: a notification update only on semantic state changes, not for timer ticks (the subtitle stays "Recording" — no seconds display in the notification).
2. **`startForeground` with `FOREGROUND_SERVICE_TYPE_MICROPHONE` without an active recording** — Android 14 allows that technically at the FGS start (the system checks the microphone permission, not active use). Verification on a Pixel API-34 device is pending.
3. **Pre-insertion state survival after process death** — if the process dies AFTER `final_output_text` is written but BEFORE `inserted_at` is set, the next `recoverFromDb` sees the session as "pending insertion". Correct — but: is the last-focused InputConnection still available after a process restart? Presumably no. **Consequence:** the user must explicitly click "Insert" and type into a new input field — that is the D4 choice. Accepted.
4. <!-- FIX: Issue 1.1.3 (User-Decision Option B) – Mode 3 als Open-Question für Phase 2 -->
   **Cross-module-effect mode 3 (Atomic Cross-Axis Update) — Phase-2 backlog.** §15.5 lists
   only modes 1+2 as binding for Phase 1. Mode 3 would be a special reducer in the orchestrator
   that mutates several sub-state axes atomically in one `store.update`. The need indicator: a
   concrete use case in which the cascade (mode 2) causes a semantic race (e.g. a
   pipeline-done action that must set *simultaneously* `pipeline = Idle` and `resend.lastAudioExists = true`
   before any subscriber sees an inconsistent intermediate view). As of
   2026-05-10 no such use case is observed — mode 2 with the `predResendVisible` helper
   (R.7 Block 1a) solves the identified Spec1-Logic-L-8 cases. If Phase 2 recognizes a real
   need, mode 3 is specified as an **explicit OCP-break pattern** (a central
   table of the atomic reducers in the orchestrator).
5. <!-- FIX: Issue 3.1.4 (User-Decision Option C Hybrid) – STANDALONE_OVERLAY-Migration als Phase-2-Backlog -->
   **Overlay-owner-architecture migration (Phase 2).** Today (Phase 1, Option C immediate fix):
   the IME-service onDestroy calls `keyboardLayoutManager.detachAllBackends()` (eliminates a window leak
   on a keyboard switch). Phase 2 checks whether STANDALONE_OVERLAY use cases (overlay visible
   without an active IME editor field) require their own `OverlayWindowService` migration — with
   its own lifecycle, an FGS notification and an IME-independent trigger.
6. <!-- FIX: Issue 3.1.8 (User-Decision Option C) – STANDALONE_OVERLAY-Backlog -->
   **WIDGET mode with HOVER disabled (T4 constraint).** Phase 2 could introduce a
   `STANDALONE_OVERLAY` mode in which WIDGET works without a HOVER counterpart —
   e.g. for foldable-outer-display use cases. The accepted constraint today: WIDGET-autonomous
   applies only in the WIDGET mode itself (a doc clarification — Issue 3.1.8 Option A).

---

## §15 Module inventory (F-11)

<!-- FIX: Phase-C C-1 (2026-05-14) – Modul-Zähler aktualisiert auf 14 (13 aktiv + 1 Phase-2-Stub).
     Phase-B S-3 hat KeyboardInputModule (§15.6) als 13. aktives Modul nachgereicht.
     Off-by-One-Klarstellung gegen §3: KeyboardInputModule hat KEINE eigene State-Achse
     (Unit-State, §15.6) — daher 14 Module, aber nur 13 Sub-State-Felder im DictateUiState
     (§3-Tabelle zeigt 13 Achsen + Top-Level-Bool). Beide Zahlen sind korrekt für ihr Schema. -->
The 14 modules (13 active + 1 Phase-2 stub) are grouped in `app/src/main/java/net/devemperor/dictate/state/modules/`. One file per module with:
- **A state sub-class** (managed by the module, in DictateUiState as a sub-field)
- **A module-effect sub-sealed-interface** (the effect variants of this module)
- **A reducer** (F1+F2 pure function)
- **An effect handler** (hardware calls)
- **A cross-module observer** (optional, for cascade triggers)

### §15.1 Module overview

| # | Module | Axis | F1+F2 needed? | Cross-module observer? |
|---|---|---|---|---|
| 1 | RecordingModule | recording (sealed RecordingState) | ✓ explicit | yes (Idle → Preparing → OverlayAction.ResetSuppressBit) <!-- FIX: Issue PENDING-3 / Spec-3 Reducer Simplified – OverlayAction.ResetSuppressBit --> |
| 2 | PipelineModule | pipeline (sealed PipelineUiState) | ✓ explicit | yes (PipelineDone → Resend, LivePrompt) |
| 3 | AudioModule | audio (AudioState) | medium | yes (AudioFocus-Loss → Recording.Pause; Recording.Preparing → AudioFocus-Request) |
| 4 | ViewModeModule | viewMode (enum) | F4 subset (formerly ViewModeFsm) | yes (Recording-Active+View-hidden → HOVER) |
<!-- FIX: Issue 1.1.5 / R.5 – LayoutModule eigentümert nur noch `layout`-Achse (contentArea ist Sub-Field) -->
| 5 | LayoutModule | layout (LayoutState — contentArea + 3 booleans) | trivial | no |
<!-- FIX: Issue 3.1.1 / 3.1.2 (User-Decision Option A) – OverlayModule-Spec-Heimat: Spec 3 §4.x neu; ViewModeModule-Detail in Spec 3 §7.1 nur als Doku -->
| 6 | OverlayModule | overlay (OverlayState — position + permission + suppress-bit + onboarding) | trivial-medium | yes (HOVER-permission-loss → mode cascade; see Spec 3 §4.x) |
| 7 | ResendModule | resend | medium (cooldown timer) | yes (Pipeline-Done → MarkLastAudio) |
| 8 | LivePromptModule | livePrompt | trivial | yes (Pipeline-Done → ChainNext) |
| 9 | LanguageModule | language | trivial | yes (Reprocess-Override → Language.Override) |
| 10 | FeatureToggleModule | features | trivial | no |
| 11 | ThemingModule | theming | trivial | no |
| 12 | PendingSessionsModule | pendingSessions | DB subscriber (no reducer) | no |
<!-- FIX: Phase-B S-3 (2026-05-13) – KeyboardInputModule als #13 verankert.
     Vorher fehlte das Modul; KeyboardInputAction.Backspace/EnterKey/SpaceKey/CopyToClipboard
     hätte der Orchestrator als `Unrouted` abgewiesen — Button-Klicks wären tot gewesen.
     §15.6 enthält die kanonische Implementierung. -->
| 13 | KeyboardInputModule | n/a (Unit state, a pure effect producer) | trivial | no |
| 14 | InterruptionModule (Phase 2) | interruption | medium | yes (call → Recording.Cancel) |

<!-- FIX: Issue 3.1.12 / R.20 – Cross-Module-Coupling-Matrix als Doku-Erweiterung -->
#### §15.1.x Cross-module coupling matrix

The matrix makes the implicit cross-module contract explicit. **Column = the reading/observing
module, row = the writing module.** Cell notation:

- `R(state.x.y)` — the reading module observes this sub-state axis (read coupling).
- `C(Action.X.Y)` — the reading module emits this cascade action in reaction to the state change.
- empty — no coupling.

<!-- KG-RSB-3 RESOLUTION 2026-05-11: Notations-Konvention für Self-Reads -->
**Notation convention for self-reads (the diagonal, KG-RSB-3 RESOLVED 2026-05-11):**
Modules read their own axis implicitly — self-reads (e.g. `RecordingModule`
reads `state.recording` in its `onCrossModuleStateChange` cascade hook)
are **NOT** listed as a separate `R(state.x)` entry in the diagonal cell.
The diagonal cell stays `—`. Cascade triggers that are purely based on
a self-read list only the `C(Action.Y.Z)` consequence in
the cross-module cell of the respective observer column. Example:
`Recording × Overlay = C(OverlayAction.ResetSuppressBit)` — the `prev.recording
is Idle && next.recording is Preparing` predicate in the RecordingModule is
understood as an implementation detail of the owner module, not as cross-
module coupling. The verbose alternative (`[self]R(...)`) was deliberately
rejected — additional notation noise without information gain.

| Owner ↓ / Observer → | Recording | Pipeline | Audio | ViewMode | Overlay | Resend | LivePrompt | Language | Layout | FeatureToggle | Theming | PendingSessions | Interruption |
|----------------------|-----------|----------|-------|----------|---------|--------|------------|----------|--------|---------------|---------|-----------------|--------------|
| **Recording**        | —         | R(state.recording) C(PipelineAction.Submit) | R(state.recording) | R(state.recording) C(ViewModeAction.OnRecordingActive) | C(OverlayAction.ResetSuppressBit) <!-- FIX: Issue PENDING-3 / Spec-3 Reducer Simplified – OverlayAction.ResetSuppressBit (Recording-Read war pre-PENDING-3; Reset läuft jetzt rein als Cascade ohne dass Overlay den Recording-State lesen muss) --> | R(state.recording) C(ResendAction.MarkAvailable) | | | | | | R(state.recording) C(PendingSessionsAction.Insert) | R(state.recording) C(InterruptionAction.OnRecordingActive) |
| **Pipeline**         | R(state.pipeline) C(RecordingAction.StopRecording) | — | | R(state.pipeline) C(ViewModeAction.OnPipelineDone) | R(state.pipeline) C(OverlayAction.OnPipelineDone) | R(state.pipeline) | R(state.pipeline) C(LivePromptAction.ChainNext) | | | | | R(state.pipeline) | |
| **Audio**            | R(state.audio.audioFocusGranted) R(state.recording) R(state.audio.bluetoothSco) C(RecordingAction.PauseRecording) C(AudioAction.RecordingStarted) C(AudioAction.RecordingEnded) C(AudioAction.ReacquireAudioFocus) C(RecordingAction.ScoRouteResolved) | | — | | | | | | | | | | |
| **ViewMode**         | | | | — | R(state.viewMode) C(OverlayAction.OnViewModeChanged) | | | | R(state.viewMode) C(LayoutAction.OnViewModeChanged) | | | | |
| **Overlay**          | | | | R(state.overlay.userPrefersWidget / hasPermission) C(ViewModeAction.SetViewMode) | — | | | | | | | | |
| **Resend**           | | R(state.resend) C(PipelineAction.SubmitReprocess) | | | | — | | | | | | | |
| **LivePrompt**       | | R(state.livePrompt.pendingChain) C(PipelineAction.Submit) | | | | | — | R(state.livePrompt) C(LanguageAction.SetOverride) | | | | | |
| **Language**         | | R(state.language) | | | | | | — | | | | | |
| **Layout**           | | | | | | | | | — | | | | |
| **FeatureToggle**    | | R(state.features.autoEnterEnabled) | | | | | | | | — | | | |
| **Theming**          | | | | | | | | | | | — | | |
| **PendingSessions**  | | R(state.pendingSessions) | | | | | | | | | | — | |
| **Interruption**     | R(state.interruption.callIncoming) C(RecordingAction.CancelRecording) | | | | | | | | | | | | — |

<!-- FIX: Phase-C C-1 (2026-05-14) – KeyboardInputModule (§15.6) ist in der Matrix bewusst absent
     (Unit-State, keine eigene Achse, kein Observer auf anderen Modulen, kein anderes Modul
     beobachtet "Backspace-Klick"). Klarstellung als Caption, damit ein Reviewer nicht "fehlende
     Zeile/Spalte"-Findings produziert. -->
**Matrix caption:** The matrix lists exclusively modules with their own state axis (13 rows/columns
above + the diagonal). **KeyboardInputModule (§15.6) deliberately does NOT appear** in the matrix — Unit
state, no observer hook, no inbound coupling (see §15.6 last paragraph). The F-8 Single-
Dispatch guarantee is sufficient; a 14×14 matrix with one empty row + an empty column would be noise.

**SRP consequence (linked to §13.3.13):** Each module has **only** read coupling on axes documented in
this matrix. A new read hook without a matrix entry is a code-review
violation. Optionally a compile-time manifest `data class CrossReadSet(val reads: Set<KClass<*>>,
val cascades: Set<KClass<out Action>>)` per module can be declared — the doc table stays
the primary carrier.

<!-- FIX: Issue PENDING-3 / Spec-3 Reducer Simplified – Recording × Overlay-Zelle: bewusst strikt-minimal -->
<!-- KNOWLEDGE-GAP: KG-RSB-3 – Recording × Overlay-Zelle: Read-Eintrag bewusst ausgespart? [RESOLVED 2026-05-11] -->
> **✅ Knowledge gap (KG-RSB-3): Recording × Overlay — strictly minimal vs. R+C — RESOLVED 2026-05-11**
>
> - **What we knew:** The matrix cell `Recording → Overlay` shows only
>   `C(OverlayAction.ResetSuppressBit)` — **no** `R(state.recording)`
>   entry, although the cascade condition `prev.recording is Idle && next.recording is Preparing`
>   (§15.2 onCrossModuleStateChange) is a classic recording-state read.
>   By comparison: the cells `Pipeline → Recording` and `Audio → Recording`
>   list *both* sides — `R(state.recording) C(...)`. The Recording → Overlay
>   cell deviates from this convention.
> - **What we did not know:** Is the omission of the `R(state.recording)`
>   entry a deliberate notation convention or an inconsistency?
> - **Resolution:** **Notation convention made explicit** — self-reads
>   (a module reads its own axis) are NOT entered in the matrix row,
>   are implicitly covered by the diagonal `—`. This keeps
>   `Recording × Overlay = C(OverlayAction.ResetSuppressBit)` strictly
>   minimal and consistent. The verbose alternative (`[self]R(state.recording)`
>   tag) was rejected — it would have extended every cascade row without an information gain
>   by a self-read marker.
> - **Incorporation:** The convention documented above the coupling matrix in §15.1.x
>   as a preliminary note ("Notation convention for self-reads (the diagonal)").
>   The convention applies retroactively to all existing matrix rows: every
>   diagonal cell stays `—`, cross-module cascade triggers list exclusively
>   the `C(Action.Y.Z)` consequence. No code change needed.

### §15.2 RecordingModule (example implementation, complete)

```kotlin
// File: app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt
package net.devemperor.dictate.state.modules

import net.devemperor.dictate.state.*
import java.io.File
import kotlin.reflect.KClass

object RecordingModule : DictateModule<
    RecordingState,
    Action.RecordingAction,
    RecordingModule.Effect,
> {
    override val id = ModuleId.Recording
    override val actionClass: KClass<Action.RecordingAction> = Action.RecordingAction::class

    override fun read(global: DictateUiState) = global.recording
    override fun write(global: DictateUiState, sub: RecordingState) = global.copy(recording = sub)
    override fun initialState(): RecordingState = RecordingState.Idle

    sealed interface Effect : SideEffect {
        <!-- FIX: Phase-B S-4 (2026-05-13) – AllocateMediaRecorder trägt audioFile als 3. Arg (R.2-konform, konsistent mit Reducer-Use Z. ~5500 + EffectHandler-Use Z. ~5577). -->
        data class AllocateMediaRecorder(val target: InsertionTarget, val useBluetooth: Boolean, val audioFile: File) : Effect
        object ReleaseMediaRecorder : Effect
        object PauseMediaRecorder : Effect
        object ResumeMediaRecorder : Effect
        object StopMediaRecorder : Effect
        data class DeleteAudioFile(val file: File) : Effect
        data class StartTimer(val initialElapsedMs: Long) : Effect
        object PauseTimer : Effect
        object ResumeTimer : Effect
        object StopTimer : Effect
        object StartAmplitudeStream : Effect
        object StopAmplitudeStream : Effect
        object StartBorderGlow : Effect
        object PauseBorderGlow : Effect
        object ResumeBorderGlow : Effect
        object StopBorderGlow : Effect
    }

    <!-- FIX: Issue 1.1.7 / R.2 – audioFile lebt im State, kein ctx-Hardware-Read mehr. -->
    <!-- FIX: Issue 2.1.5 – Paused trägt audioFile (gleicher File-Handle wie Active). -->
    override fun reduce(
        state: RecordingState,
        action: Action.RecordingAction,
        ctx: ReducerContext,
    ): TransitionResult<RecordingState, Effect>? = when (state) {
        is RecordingState.Idle -> when (action) {
            is Action.RecordingAction.StartRecording -> TransitionResult(
                // audioFile wird async vom Effect AllocateMediaRecorder erzeugt und kommt
                // per Action.RecordingAction.MediaRecorderReady(audioFile) zurück. Bis dahin
                // ist Preparing platzhalterhaft via action.audioFile (oder File("/dev/null")
                // für State-Tests, die den Effekt nicht ausführen).
                nextState = RecordingState.Preparing(
                    useBluetooth = ctx.global.audio.useBluetoothMic,
                    audioFile = action.audioFile,
                ),
                sideEffects = listOf(
                    Effect.AllocateMediaRecorder(action.target, ctx.global.audio.useBluetoothMic, action.audioFile),
                ),
            )
            else -> null    // F1: andere Actions in Idle nicht erlaubt
        }
        is RecordingState.Preparing -> when (action) {
            is Action.RecordingAction.MediaRecorderReady -> TransitionResult(
                nextState = RecordingState.Active(useBluetooth = state.useBluetooth, audioFile = state.audioFile),
                sideEffects = listOf(Effect.StartTimer(0), Effect.StartAmplitudeStream, Effect.StartBorderGlow),
            )
            Action.RecordingAction.CancelRecording -> TransitionResult(
                nextState = RecordingState.Idle,
                sideEffects = listOf(Effect.ReleaseMediaRecorder, Effect.DeleteAudioFile(state.audioFile)),
            )
            else -> null
        }
        is RecordingState.Active -> when (action) {
            Action.RecordingAction.PauseRecording -> TransitionResult(
                nextState = RecordingState.Paused(useBluetooth = state.useBluetooth, audioFile = state.audioFile),
                sideEffects = listOf(
                    Effect.PauseMediaRecorder, Effect.PauseTimer,
                    Effect.PauseBorderGlow, Effect.StopAmplitudeStream,
                ),
            )
            Action.RecordingAction.StopRecording -> TransitionResult(
                nextState = RecordingState.Idle,
                sideEffects = listOf(
                    Effect.StopMediaRecorder, Effect.StopTimer,
                    Effect.StopBorderGlow, Effect.StopAmplitudeStream,
                ),
            )
            Action.RecordingAction.CancelRecording -> TransitionResult(
                nextState = RecordingState.Idle,
                sideEffects = listOf(
                    Effect.StopMediaRecorder, Effect.StopTimer,
                    Effect.StopBorderGlow, Effect.StopAmplitudeStream,
                    Effect.DeleteAudioFile(state.audioFile),    // immer non-null, kein ctx-Read
                ),
            )
            else -> null
        }
        is RecordingState.Paused -> when (action) {
            Action.RecordingAction.ResumeRecording -> TransitionResult(
                nextState = RecordingState.Active(useBluetooth = state.useBluetooth, audioFile = state.audioFile),
                sideEffects = listOf(
                    Effect.ResumeMediaRecorder, Effect.ResumeTimer,
                    Effect.ResumeBorderGlow, Effect.StartAmplitudeStream,
                ),
            )
            // FIX: Issue 2.0.8 – Paused.Stop/Cancel als echte Reducer-Arme statt TODO().
            Action.RecordingAction.StopRecording -> TransitionResult(
                nextState = RecordingState.Idle,
                sideEffects = listOf(
                    Effect.StopMediaRecorder, Effect.StopTimer, Effect.StopBorderGlow,
                ),
            )
            Action.RecordingAction.CancelRecording -> TransitionResult(
                nextState = RecordingState.Idle,
                sideEffects = listOf(
                    Effect.StopMediaRecorder, Effect.StopTimer, Effect.StopBorderGlow,
                    Effect.DeleteAudioFile(state.audioFile),    // R.2: state.audioFile statt ctx
                ),
            )
            else -> null
        }
    }

    <!-- FIX: Phase-C C-1 (2026-05-14) – Markdown-Block "audioFile-Vertrag" + "Konsistenz der drei
         AllocateMediaRecorder-Sites" aus der Mitte des Kotlin-Code-Blocks ausgelagert (siehe
         direkt unterhalb des schließenden ``` von §15.2). Phase-B S-4 hatte den Erklärungstext
         hier eingefügt, ohne den Code-Fence zu schließen — Markdown rendert die `**`/`1.`-Marker
         dann als Literal im Kotlin-Listing. Erklärungstext gehört zwischen Reducer und runEffect
         logisch, aber nicht *in* die Kotlin-Quelle. -->
    override fun runEffect(effect: Effect, services: ModuleServices) = when (effect) {
        <!-- FIX: Phase-B S-4 (2026-05-13) – allocate ruft mit 3 Args (target, useBluetooth, audioFile); R.2-konform. -->
        is Effect.AllocateMediaRecorder -> services.recordingHardware.allocate(effect.target, effect.useBluetooth, effect.audioFile)
        Effect.ReleaseMediaRecorder    -> services.recordingHardware.release()
        Effect.PauseMediaRecorder      -> services.recordingHardware.pause()
        Effect.ResumeMediaRecorder     -> services.recordingHardware.resume()
        Effect.StopMediaRecorder       -> services.recordingHardware.stop()
        is Effect.DeleteAudioFile      -> { effect.file.delete(); Unit }
        is Effect.StartTimer           -> services.recordingTimer.start(effect.initialElapsedMs)
        Effect.PauseTimer              -> services.recordingTimer.pause()
        Effect.ResumeTimer             -> services.recordingTimer.resume()
        Effect.StopTimer               -> services.recordingTimer.stop()
        Effect.StartAmplitudeStream    -> services.amplitudeStream.start()
        Effect.StopAmplitudeStream     -> services.amplitudeStream.stop()
        Effect.StartBorderGlow         -> services.borderGlow.start()
        Effect.PauseBorderGlow         -> services.borderGlow.pause()
        Effect.ResumeBorderGlow        -> services.borderGlow.resume()
        Effect.StopBorderGlow          -> services.borderGlow.stop()
    }

    <!-- FIX: Phase-B S-7 (2026-05-13) – reduceFailure für AllocateMediaRecorder-Failures.
         Hintergrund: S-3 F-1 hat `reduceFailure` als optionalen Hook eingeführt; S-3-Offene-Fragen
         für S-4 forderte einen expliziten Failure-Arm in RecordingModule (siehe S-3-Report).
         S-4 hat das nicht eingearbeitet. S-7 schließt die Lücke, weil der Pre-Dispatch-Resolver
         zwar `IOException` aus `allocate()` fängt, ABER: was passiert, wenn `MediaRecorder.prepare()`
         im Effect-Handler wirft (z.B. wegen externer Cache-Wipe zwischen `allocate()` und runEffect,
         oder MIC-Permission währenddessen entzogen)? Ohne reduceFailure-Arm würde `Action.EffectFailure
         (originModuleId = ModuleId.Recording, ...)` an `RecordingModule.reduceFailure` geroutet,
         der Default ist `null` → `Rejected("reducer-null")` → State hängt im `Preparing`-Zustand
         für immer. Mit dem unten implementierten Arm: State-Rollback `Preparing → Idle`, das
         angeforderte Audio-File wird als orphan vom nächsten `cleanupOrphans`-Lauf eingesammelt
         (kein expliziter Delete-Effect nötig, weil MediaRecorder.prepare die Datei nie erzeugt hat
         bzw. nur ein 0-Byte-File hinterlässt — beides vom cleanup-Pfad erfasst). -->
    <!-- FIX: Phase-C C-3 (2026-05-14) – Effect-Identifier-Matching für data-class-Effects.
         Bug-Klasse: Der Orchestrator füllt `Action.EffectFailure.effect` per `effect.toString()`
         (§4.3 Step 4). Kotlin `data class.toString()` enthält die Property-Werte
         (`"AllocateMediaRecorder(target=..., useBluetooth=..., audioFile=...)"`), während
         `object.toString()` den Simple-Name liefert (`"ReleaseMediaRecorder"`). Naiver String-
         Vergleich `failure.effect == "AllocateMediaRecorder"` (vor C-3) hätte für die data-class-
         Variante NIE gematched — der Preparing-Rollback-Arm wäre silent toter Code, jeder
         AllocateMediaRecorder-Failure würde via Default-`null` als `Rejected("reducer-null")`
         abgewiesen, Recording bliebe für immer in Preparing. Auflösung: Prefix-Match mit
         `startsWith("AllocateMediaRecorder(")` für data-class-Effects, exakter Match für
         `object`-Effects. Spec 2 §3.3 EffectFailure-KDoc dokumentiert die Konvention. -->
    override fun reduceFailure(
        state: RecordingState,
        failure: Action.EffectFailure,
        ctx: ReducerContext,
    ): TransitionResult<RecordingState, Effect>? = when {
        // Failure beim Allocate-Effect (Hardware-IO failed, externer Cache-Wipe, MIC-Permission
        // entzogen mid-prepare): State zurück auf Idle. Das angeforderte audioFile war noch nicht
        // im MediaRecorder geschrieben (`MediaRecorder.prepare()` wirft VOR dem ersten Frame) —
        // im worst case bleibt ein 0-Byte-File im cacheDir/audio/, das cleanupOrphans entsorgt.
        //
        // Effect-Identifier ist `data class.toString()` = "AllocateMediaRecorder(target=..., ...)"
        // — startsWith("AllocateMediaRecorder(") matched alle Args-Varianten dieses Effect-Typs.
        failure.effect.startsWith("AllocateMediaRecorder(") && state is RecordingState.Preparing ->
            TransitionResult(
                nextState = RecordingState.Idle,
                sideEffects = listOf(
                    Effect.ReleaseMediaRecorder,    // idempotent, no-op falls allocate gar nicht durchkam
                    Effect.DeleteAudioFile(state.audioFile),    // best-effort, fängt 0-Byte-Files
                ),
            )
        // Failure beim Stop-Effect (MediaRecorder.stop wirft): State auf Idle, kein File-Delete
        // (Audio ist ggf. valid persistiert worden vor dem Stop-Throw). `StopMediaRecorder` ist
        // ein `object` — `toString()` ist der Simple-Name, exakter Match korrekt.
        failure.effect == "StopMediaRecorder" && (state is RecordingState.Active || state is RecordingState.Paused) ->
            TransitionResult(
                nextState = RecordingState.Idle,
                sideEffects = listOf(Effect.ReleaseMediaRecorder, Effect.StopTimer, Effect.StopBorderGlow),
            )
        // Andere Failures: Default-Verhalten (Rejected). Künftige Effects können explizit ergänzt
        // werden, wenn ein neuer Failure-Pfad nötig ist. Bei zukünftigen data-class-Effects
        // konsistent `startsWith("EffectName(")` verwenden (Spec 2 §3.3 EffectFailure-Konvention).
        else -> null
    }

    <!-- FIX: Issue PENDING-3 / Spec-3 Reducer Simplified – OverlayAction.ResetSuppressBit -->
    /**
     * Cross-Module-Observer: RecordingModule emittiert eine einzige Cascade — den
     * Suppress-Bit-Reset am Session-Start-Boundary `Idle → Preparing`. Das Bit
     * `state.overlay.suppressAutoOverlayUntilNextSession` wird vom User-Klick auf
     * den Overlay-Close-Button gesetzt (Spec 3 §3.1 OVERLAY_CLOSE-Resolver +
     * OverlayModule.reduce(SuppressAutoOverlayUntilNextSession)) und soll für die
     * laufende Recording-Session sticky bleiben — beim Start der *nächsten* Session
     * (User dispatcht `StartRecording`) muss es zurück auf `false`.
     *
     * **Warum hier, nicht in OverlayModule?** Das Reset-Trigger ist eine
     * RecordingState-Transition; das passende Owner-Modul für „observe Recording,
     * cascade to Overlay" ist RecordingModule (Coupling-Matrix §15.1.x, Zeile
     * `Recording` → Spalte `Overlay`). Würde OverlayModule den Reset selbst
     * triggern, müsste es `state.recording` lesen — neuer Read-Eintrag in der
     * Coupling-Matrix, der SRP verschiebt (Overlay würde Recording-Lifecycle
     * tracken). Aktuell hat OverlayModule keinen Recording-Read.
     *
     * **Cancel-Verhalten:** Wenn der User in Preparing canceled (Preparing → Idle),
     * passiert KEIN Reset — der Boundary-Test (`prev.recording is Idle && next is
     * Preparing`) deckt ausschließlich den Start-Hin-Übergang ab. Eine im selben
     * Lifecycle wieder gestartete Session wird natürlich beim erneuten
     * `StartRecording` wieder ge-reset. Die Suppress-Wahl des Users hält also bis
     * zur *nächsten echten Aufnahme*, nicht nur bis zum nächsten Klick auf den
     * Record-Button.
     */
    override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> {
        val cascade = mutableListOf<Action>()
        if (prev.recording is RecordingState.Idle && next.recording is RecordingState.Preparing) {
            cascade.add(Action.OverlayAction.ResetSuppressBit)
        }
        return cascade
    }

    // Cross-Module-Effekte gegen Recording (z.B. Anruf → Cancel) emittieren ANDERE
    // Module ihre Trigger-Actions, die hierhin als RecordingAction.CancelRecording
    // ankommen — RecordingModule selbst hat oben nur den Session-Start-Cascade.
}
```

<!-- FIX: Phase-C C-1 (2026-05-14) – Erklärungstext "audioFile-Vertrag" + "AllocateMediaRecorder-Sites"
     hierher verschoben (war in §15.2 mitten im Kotlin-Code-Block, Markdown rendert dort als
     Literal). Inhalt unverändert; gehört semantisch zwischen Reducer-Block und runEffect, was
     hier nun in Prosa abgebildet ist. -->
**audioFile contract (R.2):** `audioFile` lives in the RecordingState (pure-function guarantee); the
hardware read is removed. The allocator effect (`AllocateMediaRecorder`) gets the File object
from outside (the caller, e.g. PipelineRunner or LocalBinder.startSession) — the reducer is 100%
pure, state tests no longer need a `ModuleServicesFactory` stub.

**Consistency of the three AllocateMediaRecorder sites (Phase-B S-4):**

1. **Definition** (the effect-sealed-interface above): `AllocateMediaRecorder(target, useBluetooth, audioFile)` — 3 fields.
2. **Reducer use** (Idle→Preparing branch): `Effect.AllocateMediaRecorder(action.target, ctx.global.audio.useBluetoothMic, action.audioFile)` — 3 args.
3. **EffectHandler use** (`runEffect` body): `services.recordingHardware.allocate(effect.target, effect.useBluetooth, effect.audioFile)` — 3 args.

The three sites must stay in sync. Before Phase-B S-4 the definition had 2 fields
+ reducer use 3 args + effect-handler use 2 args — a three-way inconsistency that would
have surfaced as a compile error on the first `./gradlew assembleDebug`.

<!-- FIX: Issue PENDING-3 / Spec-3 Reducer Simplified – Cascade-Reihenfolge bei StartRecording -->
**Cascade order on `StartRecording` (Spec 1 §4.3 `dispatchInternal` pipeline):**

The pipeline steps from §4.3 (steps 1–6) behave for `StartRecording`
deterministically as follows. Important: `Effect.AllocateMediaRecorder` runs **synchronously
before** the cross-module cascade — the `runEffect` hardware call itself is
fast (`MediaRecorder.allocate()` only sets a handle; the actual
`prepare()` runs async and comes back later as `Action.RecordingAction.MediaRecorderReady`).

```text
T0  User-Klick auf Record-Button
     │
T1   dispatch(Action.RecordingAction.StartRecording(target, audioFile))
     │   moduleByLeafClass-Lookup → RecordingModule
     │
T2   RecordingModule.reduce(Idle, StartRecording, ctx)
     │   → TransitionResult(
     │       nextState = RecordingState.Preparing(useBluetooth, audioFile),
     │       sideEffects = [AllocateMediaRecorder(target, useBluetooth, audioFile)])
     │
T3   store.update { write(it, Preparing) }
     │   → state.recording = Preparing; suppressBit unverändert (z.B. true)
     │
T4   runEffect(AllocateMediaRecorder)
     │   → services.recordingHardware.allocate(target, useBluetooth)
     │   (synchroner Handle-Set; async prepare läuft im Hintergrund)
     │
T5   nextGlobal = store.snapshot   (eingefrorener Cascade-Snapshot)
     │   modules.flatMap { onCrossModuleStateChange(prev, next) }
     │   → ALLE Module beobachten prev.recording=Idle → next.recording=Preparing,
     │     einschließlich RecordingModule selbst (siehe KG-RSB-2 unten — der
     │     frühere Self-Filter `it.id != module.id` ist 2026-05-11 entfernt worden).
     │   → RecordingModule.onCrossModuleStateChange feuert OverlayAction.ResetSuppressBit;
     │     andere Module (ViewModeModule, ResendModule, AudioModule, ...) beobachten
     │     den Übergang parallel.
     │
T6   cascadeActions.forEach { dispatchInternal(it, depth + 1) }
     │   → rekursiver Dispatch der Cascade-Actions (inkl. ResetSuppressBit).
     │   → MAX_CASCADE_DEPTH (R.6) schützt vor Endlos-Loops.
     │
T7   Async-später: services.recordingHardware ruft via Callback
     │   → emitAction(Action.RecordingAction.MediaRecorderReady(audioFile))
     │   → neuer Top-Level-Dispatch-Pass (depth=0): Preparing → Active.
     │     Kein Reset-Cascade mehr (Preparing → Active ist kein Idle→Preparing-Boundary).
```

> **Verdict (KG-RSB-2, 2026-05-11):** The original filter clause `it.id !=
> module.id` (§4.3 step 5, l. 624 before the fix) would have guaranteed-blocked the
> `onCrossModuleStateChange` hook described here for the RecordingModule on its own
> `StartRecording` action — the hook would be dead code,
> the `ResetSuppressBit` cascade would never have fired, the suppress bit
> would have stayed permanently `true` after the first user-close click and the
> HOVER auto-reopen path would be blocked for the rest of the session lifecycle.
> Production-bug risk confirmed → resolution (A): the self-filter removed from §4.3
> step 5 (see the FIX comment there). MAX_CASCADE_DEPTH (R.6, cap 8)
> still covers infinite cascade loops as the sole safeguard.

<!-- KNOWLEDGE-GAP: KG-RSB-1 – Service-Boot-Recovery: Suppress-Bit-Default [RESOLVED 2026-05-11] -->
> **✅ Knowledge gap (KG-RSB-1): Boot default of the suppress bit — RESOLVED 2026-05-11**
>
> - **What we knew:** `OverlayState.suppressAutoOverlayUntilNextSession` has
>   the default `false` (§3, line 152); it is NOT listed in `PipelinePrefMirror.initialMirror`
>   (§4.5, lines 703–735) and also not in the SP schema (§6.4 lists
>   only the four position floats). The bit is **transient** — lives only in
>   the StateFlow in the PipelineService, is lost on service death.
> - **What we did not know:** Whether `OverlayState` persistence is deliberately
>   limited to "only position + permission, no suppress, no onboarding-pending" —
>   Spec 3 §11.9 documented the decision explicitly only for
>   `userPrefersWidget`. Should the suppress bit be reset to `false` on the
>   service restart (status quo) or be explicitly documented as deliberate
>   boot semantics like in §11.9?
> - **Resolution:** **Status quo confirmed + already documented in Spec 3 §11.9
>   (PENDING-3 mirror entry).** Rationale: the bit is
>   transient, every boot starts with `false` (the data-class default takes effect).
>   The user choice deliberately does NOT survive the app lifecycle — that is UX-consistent
>   with `userPrefersWidget`: after an app restart the overlay may pop up
>   again, because the session boundary was exceeded by the restart anyway.
>   The suppress contract ("prevent auto-reopen for *this* session")
>   is moot after process death, because no session is active.
>
>   **Code change: none.** Spec 3 §11.9 already contains the mirror
>   entry (l. 1840–1856, "persistence bit
>   `state.overlay.suppressAutoOverlayUntilNextSession` (PENDING-3, KG-RSB-1)"),
>   in which the boot semantics are explicitly documented:
>
>   > Service-Restart (OOM-Recovery): Bit ist `false` per default. Begründung
>   > identisch zu `userPrefersWidget` — nach Process-Tod ist keine aktive
>   > Recording-Session mehr im Flug, der Suppress-Vertrag ist gegenstandslos.
>
> - **Incorporation:** The doc anchor in Spec 3 §11.9 (PENDING-3 mirror entry)
>   stays unchanged — the resolution acknowledges that the doc is already complete.
>   No code touch needed in Block 6.

<!-- KNOWLEDGE-GAP: KG-RSB-2 – Self-Cascade durch §4.3 Step-5-Filter [RESOLVED 2026-05-11: Auflösung A — Self-Filter gestrichen] -->
> **⚠ Knowledge gap (KG-RSB-2): RecordingModule cannot observe itself**
> **[RESOLVED 2026-05-11: production bug confirmed → resolution (A) chosen; see the FIX comment in §4.3]**
>
> - **What we know:** §4.3 step 5 (cross-module observation) filtered
>   the emitting module out of the cascade pass before the 2026-05-11 fix:
>   `modules.filter { it.id != module.id }` (line 624 *before* the fix).
>   If RecordingModule reduces the `StartRecording` action,
>   *its own* `onCrossModuleStateChange` in this pass would NOT have been
>   called — the `Idle → Preparing → ResetSuppressBit` cascade defined here
>   would never have fired. **Bug verification 2026-05-11: confirmed by code reading
>   the `dispatchInternal` snippet — the filter is deterministic,
>   no other module `onCrossModuleStateChange` implementation observes
>   `Idle → Preparing` (Spec 3 §4.8 OverlayModule deliberately reads no
>   `state.recording`, was the reason for the current architecture).**
> - **What we do not know:** Three possible resolutions, the plan does not
>   commit unambiguously:
>   - **(A)** The self-filter is intended as a safety measure against infinite cascades,
>     and the `Idle → Preparing` transition should be detected.
>     Solution: remove the filter or explicitly allow self-cascade
>     (the cascade-depth counter R.6 + a DEBUG assert already covers loops).
>   - **(B)** The ResetSuppressBit cascade lives in **another** module
>     `onCrossModuleStateChange`, e.g. OverlayModule itself (would but
>     read `state.recording` → a coupling-matrix read entry — was exactly
>     the reason for the current solution) or a dedicated
>     LifecycleObserver module.
>   - **(C)** RecordingModule emits the `ResetSuppressBit` action directly
>     in the reducer as a second output action (instead of in the observer). Would conceptually break §15.5
>     mode 2 (action cascade), because the reducer
>     emits cross-module actions.
> - **Resolvable by:** Code research in the `DictateOrchestrator.dispatchInternal`
>   implementation test: a unit test
>   `recordingModule_idleToPreparing_emitsResetSuppressBit` verifies the
>   desired behavior against the real orchestrator implementation.
>   If the test is red → §4.3 step 5 filter is misdesigned →
>   resolution (A).
> - **Impact if unresolved:** The implementer builds the cascade per the
>   previous plan wording, it never fires, the bit stays `true`, HOVER auto-
>   reopen never works again after the first user close. **A production
>   bug**.
> - **Recommended default strategy:** Resolution **(A)** — remove the self-filter in
>   §4.3 step 5. Rationale:
>   - The cascade-depth counter (R.6, cap 8) already covers infinite cascades —
>     a self-filter is redundant belt-and-suspenders safety.
>   - SRP: a module should be allowed to react to its *own* state transitions
>     (e.g. "I have just entered Preparing, so I want to
>     cross-cascade X"); that is semantically the same operation that the
>     reducer could have done too, only cleanly separated in the observer.
>   - Consistency with the coupling matrix: the matrix diagonal (`Recording ×
>     Recording`) is `—` (no self-coupling in the sense of reads), but
>     self-cascade triggers are a different concept — a module triggers its own
>     follow-up actions without cross-module reads.
>
>   If (A) is rejected → (B) with OverlayModule + a Recording-read
>   matrix entry (shifts SRP, was the reason for the current solution).
>
> - **Resolution (2026-05-11):** Resolution (A) **applied**. The filter
>   `modules.filter { it.id != module.id }` in §4.3 step 5 (l. 624 before the fix)
>   is removed — see the FIX comment there. The test
>   `recordingModule_idleToPreparing_emitsResetSuppressBit` (proposed
>   in the "resolvable by" block above) is to be added as a **mandatory test** for Block 4
>   in the acceptance block §10 below (see the `R.RSB-FIX-A` clause
>   if not yet present) — as a regression test for the filter fix.

<!-- FIX: Issue PENDING-3 / Spec-3 Reducer Simplified – Logging-Empfehlung -->
**Logging recommendation (telemetry):** The `DictateOrchestrator.dispatchInternal`
today (§4.3) only logs effect failures and cascade loops. For the
ResetSuppressBit cascade we RECOMMEND a DEBUG log directly in the
`onCrossModuleStateChange` block:

```kotlin
if (prev.recording is RecordingState.Idle && next.recording is RecordingState.Preparing) {
    if (BuildConfig.DEBUG) Log.d(
        "RecordingModule",
        "Session-Start cascade: ResetSuppressBit (prev.suppress=${prev.overlay.suppressAutoOverlayUntilNextSession})"
    )
    cascade.add(Action.OverlayAction.ResetSuppressBit)
}
```

Rationale: the bit is transient + invisible (no direct UI feedback);
a DEBUG log at the trigger point eases the diagnosis if the HOVER
auto-reopen path does not take effect. A release log is not needed — the bug is visible
in the failing reopen. A counterpart log in `OverlayModule.reduce(ResetSuppressBit)`
is optional, but the action cascade is already traceable via `DispatchOutcome`.

<!-- FIX: Issue 2.0.8 – Paused.Stop/Cancel TODO()-Stubs durch echte Reducer-Arme ersetzt -->

### §15.3 AudioModule with a cross-module observer (example)

```kotlin
// File: app/src/main/java/net/devemperor/dictate/state/modules/AudioModule.kt
package net.devemperor.dictate.state.modules

import net.devemperor.dictate.state.*
import kotlin.reflect.KClass

object AudioModule : DictateModule<AudioState, Action.AudioAction, AudioModule.Effect> {
    override val id = ModuleId.Audio
    override val actionClass: KClass<Action.AudioAction> = Action.AudioAction::class

    override fun read(g: DictateUiState) = g.audio
    override fun write(g: DictateUiState, s: AudioState) = g.copy(audio = s)
    override fun initialState() = AudioState()

    sealed interface Effect : SideEffect {
        object RequestAudioFocus : Effect
        object ReleaseAudioFocus : Effect
        object StartBluetoothSco : Effect
        object StopBluetoothSco : Effect
    }

    override fun reduce(state: AudioState, action: Action.AudioAction, ctx: ReducerContext) = when (action) {
        is Action.AudioAction.OnAudioFocusGrantChanged -> TransitionResult(
            nextState = state.copy(audioFocusGranted = action.granted),
            sideEffects = emptyList(),
        )
        is Action.AudioAction.OnBluetoothScoStateChanged -> TransitionResult(
            nextState = state.copy(bluetoothSco = BluetoothScoPublicState(action.phase, action.reason)),
            sideEffects = emptyList(),
        )
        // ... weitere Actions
    }

    override fun runEffect(effect: Effect, services: ModuleServices) = when (effect) {
        Effect.RequestAudioFocus -> services.audioFocus.request()
        Effect.ReleaseAudioFocus -> services.audioFocus.release()
        Effect.StartBluetoothSco -> services.bluetoothSco.start()
        Effect.StopBluetoothSco  -> services.bluetoothSco.stop()
    }

    <!-- FIX: Phase-B S-4 (2026-05-13) – Observer-Pure-Function-Vertrag explizit + Dead-Code-Block entfernt. -->
    /**
     * Cross-Module-Observer: AudioModule reagiert auf Recording-State-Änderungen.
     *
     * **Pure-Function-Vertrag (§15.5 Mode 2):** Dieser Hook darf **AUSSCHLIESSLICH** Actions
     * emittieren — KEINE Direct-Hardware-Calls (`services.X.Y()`) im Body. Hardware-
     * Side-Effects laufen ausschließlich in `runEffect()`. Cross-Module-Wirkungen,
     * die einen Hardware-Call brauchen, werden als Action emittiert (Cascade); der
     * Empfänger-Modul-Reducer setzt sie in seinen eigenen Effect um.
     *
     * **AudioFocus-Request beim Recording-Start (Phase-B S-4 → revidiert
     * B2-C6-W1 + B2-VAL-W1):** Die Phase-B-S-4-Annahme — "AudioFocus-Request
     * läuft als Effect direkt im RecordingModule beim Preparing-Übergang
     * (Effect.AllocateMediaRecorder kapselt das im
     * RecordingHardwareSubsystem.allocate-Pfad — kein Cross-Module-Cascade
     * nötig)" — war **faktisch falsch gegen den ausgelieferten Adapter**:
     * `RecordingHardwareAdapter.allocate` setzt nur die MediaRecorder-Source
     * + `prepare()`, fordert WEDER AudioFocus AN NOCH startet es SCO
     * (C6-IMPL-1 gate-RED-blocking). Die *Begründung* der S-4-Note
     * (AudioFocus-Lifecycle in einem Modul = SRP) ist korrekt und bleibt
     * die bindende Constraint — sie wird durch den **wiederhergestellten
     * §15.1-Zeile-3-Observer-Arm** erfüllt, NICHT durch einen
     * RecordingModule-Effect:
     *
     * AudioModule beobachtet die RecordingState-FSM via diesem Hook
     * (ADR-0002 Mode-2-Cascade → Mode-1 eigener Effect) und cascadiert
     * AudioModule-eigene Actions:
     *  - `Idle → Preparing` / `Paused → Active` → `AudioAction.RecordingStarted`
     *    (Reducer emittiert `RequestAudioFocus` gated auf `Pref.AudioFocus`,
     *    + `StartBluetoothSco` gated auf `useBluetoothMic`; auf dem BT-Pfad
     *    wird zusätzlich `bluetoothSco.phase` auf `Waiting` geprimt —
     *    B2-VAL-W1 F-1).
     *  - `* → Idle` / `Active → Paused` → `AudioAction.RecordingEnded`
     *    (`ReleaseAudioFocus` + `StopBluetoothSco`).
     *  - BT-mic SCO-Outcome (`OnBluetoothScoStateChanged`) →
     *    `RecordingAction.ScoRouteResolved` (RecordingModule feuert seinen
     *    deferred `AllocateMediaRecorder` mit der korrekten Source).
     *  - SCO-Wait-resolved-Edge (`Preparing.awaitingSco true → false`) →
     *    `AudioAction.ReacquireAudioFocus` (Focus-only Re-Request, legacy
     *    Timing-Parität: Focus direkt vor Capture — B2-VAL-W1 F-2).
     *
     * Damit bleibt der AudioFocus+SCO-Lifecycle vollständig in AudioModule
     * (dem `audio`-Achsen-Owner) — exakt die SRP-Aussage der S-4-Note,
     * nur korrekt angewandt. Kein Mode-3 (kein Cross-Achsen-Write).
     *
     * @see ../../../2026-05-15 - dictate-cutover-completion/research/recording-audiofocus-btsco-handshake.md
     */
    override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> {
        val cascade = mutableListOf<Action>()

        // AudioFocus-Loss während Recording → automatisch pausieren
        if (next.recording is RecordingState.Active &&
            prev.audio.audioFocusGranted &&
            !next.audio.audioFocusGranted
        ) {
            cascade.add(Action.RecordingAction.PauseRecording)
        }

        return cascade
    }
}
```

### §15.4 Adding a new module — walkthrough

Example: a new `InterruptionModule` (Phase 2). Six steps:

1. **Create the file `modules/InterruptionModule.kt`** (analogous to §15.2)
2. **Add `ModuleId.Interruption`** in `DictateModule.kt`
3. **Add `Action.InterruptionAction`** in `Action.kt`
4. **`DictateUiState.interruption`** is already a sub-state field (default null)
5. **Extend `DictateModuleRegistry.all`** by `InterruptionModule`
6. **`ModuleServices`** if necessary extend by a new subsystem dependency (`telephonyListener: TelephonyListenerSubsystem`)

That's it. The cross-module effect (call → cancel recording) is declarative in `onCrossModuleStateChange` — no other file is touched.

### §15.5 Cross-module-effect modes

<!-- FIX: Issue 1.1.3 (User-Decision Option B) – Mode 3 wird zu §14 Open-Question; §15.5 nur 2 Modi -->
**Two modes for cross-module effect are binding in Phase 1:**

| Mode | Mechanism | When to use? |
|---|---|---|
| **1. Own SideEffect** | The reducer output contains the hardware effect of the own module | for hardware/pref that belongs to the own state mutation |
| **2. Action cascade** | `onCrossModuleStateChange` returns a list of actions, the orchestrator dispatches them recursively (with a depth counter, R.6) | for cross-module reactions (AudioFocus-Loss → Pause; PipelineDone → Resend.MarkAvailable) |

**Default recommendation:** Mode 2 (action cascade). Modes 1+2 fully cover today's need
— the use cases identified in Sec1-Logic L-8 (auto-enter chain, resend-pulse race) can
be correctly mapped with the cascade + the `predResendVisible` consolidation helper (see R.7 Block-1a).

**Mode 3 (Atomic Cross-Axis Update) is explicitly out-of-scope for Phase 1** — see §14 Open
Questions: only retrofit when there is a concrete need in Phase 2 (no half-pattern, no speculative
architecture).

<!-- FIX: Phase-B S-9 (2026-05-13) – Anti-Beispiel-Block + Self-Read-Konvention Cross-Link.
     Hintergrund: Phase-A Surprise-Finding #3 fand in Spec 3 §7.3 T1+T2 eine versehentliche
     Mode-3-Mutation (ViewModeModule mutiert viewMode + layout.smallMode + overlay.userPrefersWidget
     in einem Reducer-Block); §6.1 hatte die korrekte Mode-2-Form. Doppel-Truth-Quelle.
     S-9 hat §7.3 auf §6.1-konsistente Form gebracht. Diese Anti-Beispiel-Tabelle macht die
     Disambiguation explizit, damit ein zukünftiger Maintainer die Modi nicht durcheinander wirft. -->
**Anti-example table — when NOT to cascade (vs. mode 1/2 vs. mode-3 backlog):**

| Pattern | Example | Mode | Rationale |
|---|---|---|---|
| A module mutates **only its own sub-state axis** + emits effects on its own hardware | RecordingModule.reduce sets `recording = Preparing` + the effect `AllocateMediaRecorder` | **Mode 1** | SRP — the axis + effects belong to its own responsibility |
| A module mutates its axis + other modules should react to it (a follow-up mutation on ANOTHER axis) | ViewModeModule sets `viewMode = KEYBOARD`; LayoutModule reacts via `onCrossModuleStateChange` → `LayoutAction.SetSmallMode(true)` | **Mode 2** | A cross-module cascade — each module stays SRP-conform; the follow-up mutation moves into the **owner module of the target axis** |
| A module mutates its own axis + ANOTHER axis in one reducer step | `ViewModeModule.reduce` sets `viewMode + layout.smallMode + overlay.userPrefersWidget` at the same time (atomically) | **Mode 3 (Phase-2 backlog, do NOT use)** | SRP break — ViewModeModule writes into foreign axes; test/refactor difficulty; plan §15.5 + §14 Open-Q 4 |
| A module reads its own axis (`prev.x` vs `next.x`) as a trigger for a cascade onto another axis | RecordingModule.onCrossModuleStateChange reads `prev.recording is Idle && next.recording is Preparing` → cascades `OverlayAction.ResetSuppressBit` | **Mode 2 (self-read)** | A self-read is NOT cross-module coupling in the sense of the matrix — it is NOT entered in the §15.1.x diagonal (KG-RSB-3 convention), only the cross-module `C(...)` consequence |

**Code-review obligation:** if a PR contains a reducer that mutates TWO different
sub-state axes AT THE SAME TIME (`state.copy(x = …, y = …)` with `x` and `y` in different owner modules),
that is a mode-3 violation. Resolution: it mutates only its OWN axis; the foreign axis moves
into an `onCrossModuleStateChange` hook of the owner module.

**Cross-link to the coupling matrix (§15.1.x):** Every new mode-2 cascade needs a `C(Action.X.Y)`
entry in the correct row of the matrix. Self-reads (mode 2 with its own axis as the trigger) follow
the KG-RSB-3 convention — no entry in the diagonal, only the `C(...)` consequence in the
cross-module cell.

<!-- FIX: Phase-B S-3 (2026-05-13) – KeyboardInputModule kanonisch spezifiziert (vorher fehlte das Modul). -->
### §15.6 KeyboardInputModule (effect-only — `Unit` state)

```kotlin
// File: app/src/main/java/net/devemperor/dictate/state/modules/KeyboardInputModule.kt
package net.devemperor.dictate.state.modules

import net.devemperor.dictate.state.*
import kotlin.reflect.KClass

/**
 * KeyboardInputModule — leitet IME-Direkteingaben (Backspace, Enter, Space, Clipboard-
 * Copy) als SideEffects an die `InputConnection` weiter. Eigentümert KEINEN Sub-State
 * (Unit), weil die Operationen außerhalb des `DictateUiState` wirken (System-Clipboard
 * + System-InputConnection-Buffer).
 *
 * **Warum trotzdem ein Modul?** Konsistenz mit dem F-8-Single-Dispatch-Vertrag: jede
 * Action MUSS über `DictateOrchestrator.dispatch(...)` laufen, sonst gibt es zwei
 * Dispatch-Pfade (LocalBinder.dispatch vs. direkter IME-Service-Call) und Resolver/Tests
 * können nicht mehr verlässlich annehmen, dass `dispatch` die einzige Mutation-Quelle ist.
 * Mit Unit-State + Effect-Pipeline läuft die Action regulär durch das Sealed-Leaves-
 * Indexing und der IME-Service braucht keine Action-Hooks außerhalb des Modul-Patterns.
 *
 * **Reducer:** trivial — jede Action wird in genau einen passenden SideEffect übersetzt.
 * `state` bleibt `Unit`; `TransitionResult` propagiert nur die `sideEffects`-Liste.
 */
object KeyboardInputModule : DictateModule<Unit, Action.KeyboardInputAction, KeyboardInputModule.Effect> {
    override val id = ModuleId.KeyboardInput
    override val actionClass: KClass<Action.KeyboardInputAction> = Action.KeyboardInputAction::class

    override fun read(global: DictateUiState) = Unit
    override fun write(global: DictateUiState, sub: Unit) = global
    override fun initialState() = Unit

    sealed interface Effect : SideEffect {
        object SendBackspace : Effect
        object SendEnter : Effect
        object SendSpace : Effect
        data class CopyToClipboard(val text: String) : Effect
    }

    override fun reduce(state: Unit, action: Action.KeyboardInputAction, ctx: ReducerContext) = when (action) {
        Action.KeyboardInputAction.Backspace ->
            TransitionResult(Unit, listOf(Effect.SendBackspace))
        Action.KeyboardInputAction.EnterKey ->
            TransitionResult(Unit, listOf(Effect.SendEnter))
        Action.KeyboardInputAction.SpaceKey ->
            TransitionResult(Unit, listOf(Effect.SendSpace))
        is Action.KeyboardInputAction.CopyToClipboard ->
            TransitionResult(Unit, listOf(Effect.CopyToClipboard(action.text)))
    }

    override fun runEffect(effect: Effect, services: ModuleServices) = when (effect) {
        Effect.SendBackspace -> {
            val ic = services.inputConnectionProvider() ?: return@runEffect
            ic.deleteSurroundingText(1, 0)
            Unit
        }
        Effect.SendEnter -> {
            val ic = services.inputConnectionProvider() ?: return@runEffect
            ic.commitText("\n", 1)
            Unit
        }
        Effect.SendSpace -> {
            val ic = services.inputConnectionProvider() ?: return@runEffect
            ic.commitText(" ", 1)
            Unit
        }
        is Effect.CopyToClipboard -> {
            services.clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("dictate", effect.text))
            Unit
        }
    }
}
```

**`services.clipboard` extension:** `ModuleServices` (§4.7) gets an optional
`val clipboard: android.content.ClipboardManager?` — set in the service onCreate from
`getSystemService(CLIPBOARD_SERVICE)`. The backspace/enter/space path needs
`services.inputConnectionProvider`, which already exists.

**Failure mode:** If the `InputConnection` is null at effect execution (the editor
has lost focus), the effect becomes a no-op — deliberately no `EffectFailure`, because
"no input connection" is the standard state (e.g. when the user switches to the home
screen during a recording). This behavior is consistent with today's
`DictateInputMethodService` code, which also treats `getCurrentInputConnection() == null`
as a no-op.

**No cross-module observer:** KeyboardInput has no state axis, so there is nothing
to observe. Other modules do not observe KeyboardInput either (there is no
sensible trigger "backspace was pressed"). The coupling-matrix row/column
KeyboardInput stays empty.

### §15.7 SOLID verification of the module pattern

| Principle | Fulfillment |
|---|---|
| **SRP** | Each module has exactly one functional domain. The reducer + effect handler are coherent. |
| **OCP** | A new module = a new file + 4 small extensions, no central code is touched. Modes 1+2 preserve OCP; mode 3 would be an OCP break against the orchestrator — therefore Phase-2 (see §15.5 + §14 Open Questions). <!-- FIX: Issue 2.0.3 + 1.1.3 – Mode-3-OCP-Konsistenz-Hinweis --> |
| **LSP** | All modules are `DictateModule<S, A, E>` and can be treated polymorphically |
<!-- FIX: Phase-C C-1 (2026-05-14) – Methodenzähler aktualisiert (7+4 nach Phase-B S-3 reduceFailure + Issue 2.1.2 prefBindings + Issue 2.1.12 terminate). -->
| **ISP** | The `DictateModule` interface is minimal (7 mandatory methods + 4 optional default hooks) |
| **DIP** | The orchestrator hangs on the `DictateModule` interface, not on concretizations. The effect handler hangs on the subsystem interface (via `services`) |
| **DRY** | The action list lives only in the `Action` sealed class. The pref list only in the PrefMirror. The SideEffect encapsulated per module. |
