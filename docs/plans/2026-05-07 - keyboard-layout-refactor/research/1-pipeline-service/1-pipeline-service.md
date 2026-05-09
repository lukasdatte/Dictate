# Spec 1 — Pipeline-Service-Layer (Foreground Service + State-SSOT + Persistence)

**Status:** Skeleton — Architektur fixiert, Detail-Research durch Agent
**Hauptplan:** [→ keyboard-layout-refactor.md](../../keyboard-layout-refactor.md)
**Geschwister-Specs:**
- [Spec 2 — KEYBOARD-Layout (IME-View)](../2-keyboard-layout/2-keyboard-layout.md)
- [Spec 3 — Floating-Overlay (WIDGET + HOVER)](../3-floating-overlay/3-floating-overlay.md)

---

## §1 Kontext und Scope

Diese Spec beschreibt die **Service-Schicht** des Refactors. Sie umfasst:

- Einen neuen **Foreground Service** `DictatePipelineService`, der Pipeline-Logik und State hält und unabhängig vom IME-Service-Lifecycle lebt (überlebt Tastatur-Wechsel).
- Den **konsolidierten `PipelineStateManager`** als alleinige State-SSOT für ALLE UI-relevanten State-Achsen.
- Die **Bound-Service-Schnittstelle** (`LocalBinder`), über die der IME-Service mit dem Service kommuniziert.
- Die **Persistence-Schicht** (Room) mit minimaler Schema-Erweiterung und Checkpoint-Hooks.
- Den **Lifecycle**: Start, Stop, Notification-Updates, Recovery aus DB nach OOM-Death.
- Die **Migration** vorhandener Klassen (RecordingStateController, KeyboardUiController-State, JobExecutor-Verdrahtung).

Out-of-Scope (anderer Spec):
- View-Rendering, Layout-Wahl, Button-Sichtbarkeits-Resolver — siehe Spec 2 + Spec 3.
- Window-Lifecycle für Overlay — siehe Spec 3.

---

## §2 Architektur-Entscheidungen (fixiert)

| # | Entscheidung | Begründung |
|---|--------------|------------|
| D1 | **Eigener Foreground Service** im **App-Hauptprozess** (nicht in eigenem `:pipeline`-Prozess) | Kein IPC nötig, Local Binder reicht. Tastatur-Wechsel-Survival kommt durch Service-Lifecycle. |
| D2 | **`startForeground()`** mit persistenter Notification verpflichtend | Android-Pflicht für indefinite-running Services; gleichzeitig User-Status-UI. |
| D3 | **Local Binder mit `StateFlow` + Action-Methoden** als Kommunikationskanal IME ↔ PipelineService | Selber Prozess → keine Marshalling-Kosten. StateFlow ist Standard-Reaktiv-Pattern. |
| D4 | **KEIN WorkManager-Worker** (vom User verworfen) | Foreground-Service deckt 99% der Fälle ab. Bei OOM-Death (selten): User-controlled Resume aus DB. |
| D5 | **`return START_NOT_STICKY`** in `onStartCommand` | Bei Process-Death KEIN Auto-Restart. User entscheidet. |
| D6 | **`stopSelf()`** sobald **alle Sessions terminal** (COMPLETED/INSERTED/CANCELLED) | Notification verschwindet automatisch. Kein "Geist-Service". |
| D7 | **PipelineStateManager als alleinige Mutation-Quelle** für alle UI-State-Achsen | Eliminiert die heutige resend_btn-Race + recordButton-Hybrid. |
| D8 | **DB-Schema-Migration M3→M4 ist additiv** (`ALTER TABLE sessions ADD COLUMN inserted_at INTEGER`) | Rollback-sicher, kein Daten-Verlust-Risiko. |

---

## §3 Datenmodell: `DictateUiState`

> **Architektur-Korrektur F-10 (Iteration 2026-05-09):** Frühere Spec-Versionen
> hatten `DictateUiState` als flache 18-Felder-data-class entworfen. Mit dem
> erweiterten State-Inventar (Block 3.5) wären es ~30+ Felder geworden — eine
> "weiß-alles"-Daten-Klasse, derselbe SRP-Antipattern wie bei `PipelineStateManager`
> vor F-1. Korrektur: hierarchische **Sub-State-Klassen** pro semantischer Achse.
> Jede Sub-State-Klasse ist immutable, hat klare Zuständigkeit, und wird vom
> jeweiligen Modul (siehe §15) verwaltet.
>
> **Architektur-Korrektur F-9 (Iteration 2026-05-09):** Listen-Felder verwenden
> `kotlinx.collections.immutable.PersistentList` statt `List<T>`, um echte
> strukturelle Immutabilität zu erzwingen (Cast zu `MutableList` wird verhindert).
> Neue Library-Dependency: `kotlinx-collections-immutable` (~50 KB APK-Impact).
> KEINE MVI-Library wird adopted — der Stand-alone-Pfad mit StateFlow + sealed
> Action + modularen Reducern ist tragfähig (siehe Library-Vergleich im
> Hauptplan-Iteration-Log F-9).

Der `DictateUiStateStore` (§4.4) hält **eine** `MutableStateFlow<DictateUiState>`. Die Daten-Klasse fasst alle UI-relevanten Achsen über Sub-State-Klassen zusammen — pro fachlicher Domäne eine immutable data class:

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
    val contentArea: ContentArea,
    val layout: LayoutPrefs,
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

    // ─── Phase 2 (default null = nicht modelliert) ───
    val interruption: InterruptionState? = null,
) {
    companion object {
        fun initial(): DictateUiState = DictateUiState(
            recording = RecordingState.Idle,
            pipeline = PipelineUiState.Idle,
            viewMode = ViewMode.KEYBOARD,
            contentArea = ContentArea.MAIN_BUTTONS,
            layout = LayoutPrefs(),
            overlay = OverlayState(),
            audio = AudioState(),
            resend = ResendState(),
            livePrompt = LivePromptState(),
            language = LanguageState(effective = "system"),
            features = FeatureToggles(),
            theming = ThemingState(),
            pendingSessions = persistentListOf(),
        )
    }
}

// ─── Sub-State-Klassen ───

data class LayoutPrefs(
    val singleRowMode: Boolean = false,
    val smallMode: Boolean = false,
    val animationsEnabled: Boolean = true,
)

data class OverlayState(
    val positionPortraitX: Float = 1.0f,    // normalisiert 0..1
    val positionPortraitY: Float = 0.1f,
    val positionLandscapeX: Float = 1.0f,
    val positionLandscapeY: Float = 0.1f,
    val userPrefersWidget: Boolean = false,
    val onboardingPending: Boolean = false,
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
    val sessionId: Long,
    val status: SessionStatus,    // RECORDED, COMPLETED, FAILED, CANCELLED
    val transcribedText: String?,
    val createdAt: Long,
)

enum class ViewMode { KEYBOARD, WIDGET, HOVER }
```

**Wichtig:** `DictateUiState` und alle Sub-State-Klassen sind **immutable**. Jede Änderung erzeugt eine neue Instanz, die per `_state.value = newState` emittiert wird. Konsumenten reagieren reaktiv via `state.collect { ... }`.

### Achsen-Übersicht

15 State-Achsen, klassifiziert nach Verantwortung:

| # | Sub-State-Feld | Eigentümer-Modul | Quellen |
|---|---|---|---|
| 1 | `recording` | RecordingModule (§15.1) | sealed class RecordingState (4 States: Idle/Preparing/Active/Paused) |
| 2 | `pipeline` | PipelineModule | sealed class PipelineUiState (4 States: Idle/Preparing/Running/ReprocessStaging) |
| 3 | `viewMode` | ViewModeModule | enum ViewMode (KEYBOARD/WIDGET/HOVER) |
| 4 | `contentArea` | LayoutModule | enum ContentArea (MAIN_BUTTONS/QWERTZ/EMOJI_PICKER) |
| 5 | `layout` | LayoutModule | data LayoutPrefs (3 Booleans, Pref-Mirror) |
| 6 | `overlay` | OverlayModule | data OverlayState (4 Floats + 2 Booleans) |
| 7 | `audio` | AudioModule | data AudioState (Pref + System-Status + BluetoothSco) |
| 8 | `resend` | ResendModule | data ResendState (3 Booleans) |
| 9 | `livePrompt` | LivePromptModule | data LivePromptState (2 Booleans) |
| 10 | `language` | LanguageModule | data LanguageState (effective + override) |
| 11 | `features` | FeatureToggleModule | data FeatureToggles (5 Booleans, Pref-Mirror) |
| 12 | `theming` | ThemingModule | data ThemingState (4 Pref-gespiegelte Werte) |
| 13 | `pendingSessions` | PendingSessionsModule | PersistentList, DB-Subscriber-getrieben |
| 14 | `interruption` | InterruptionModule (Phase 2) | data InterruptionState — default null |

ReprocessStaging ist KEIN eigenständiges Modul, sondern eine Sub-Variante der Pipeline-FSM (`PipelineUiState.ReprocessStaging`) — verwaltet vom PipelineModule.

### Vergleich zum heutigen State

| Heute | Künftig |
|-------|---------|
| `RecordingStateController.state` | `DictateUiState.recording` |
| `KeyboardUiController.state` | `DictateUiState.pipeline` |
| `KeyboardStateManager.contentArea` | `DictateUiState.contentArea` |
| `KeyboardStateManager.isSmallMode` | `DictateUiState.layout.smallMode` |
| `Pref.SingleRowMode` (on demand) | `DictateUiState.layout.singleRowMode` (gespiegelt) |
| `Pref.ResendButton + getLastAudioFileExists()` (verteilt) | `DictateUiState.resend.{lastAudioExists, resendEnabled, resendCooldown}` |
| `RecordingStateController.audioFocusEnabled` | `DictateUiState.audio.audioFocusEnabledPref` |
| `BluetoothScoManager._isScoStarted` (verstreut) | `DictateUiState.audio.bluetoothSco.phase` |
| `LanguageController.lastEffective` | `DictateUiState.language.effective` |
| `livePrompt + pendingLivePromptChain` (Service-Felder) | `DictateUiState.livePrompt.{enabled, pendingChain}` |
| `Pref.RewordingEnabled / AutoFormattingEnabled / InstantOutput / Vibration / AutoEnter` (on demand) | `DictateUiState.features.*` (alle 5 gespiegelt) |
| `Pref.Theme / AccentColor / OverlayCharacters / OutputSpeed` (on demand) | `DictateUiState.theming.*` (alle 4 gespiegelt) |
| keine | `DictateUiState.viewMode` (neu für Triangle-FSM) |
| keine | `DictateUiState.pendingSessions` (neu für Restart-Button) |
| keine | `DictateUiState.overlay.position*` (neu, OPEN-3) |
| keine | `DictateUiState.audio.audioFocusGranted` (System-Status, getrennt von Pref) |

---

## §4 DictateOrchestrator + Modular Plugin-Pattern

> **Architektur-Korrekturen F-1 + F-2 (2026-05-08):** Frühere Spec-Versionen
> hatten einen monolithischen `PipelineStateManager` mit fünf Verantwortungen
> (State-Mutation + Pref-Sync + FSM + Recovery + JobExecutor-Init). Substruktur
> in vier Hilfsklassen + zwei Dependency-Interfaces (`PipelineSessionRepo`,
> `PipelineRunner`) wurde eingeführt.
>
> **Architektur-Korrektur F-8 (2026-05-09):** Sealed `Action`-Klasse + LocalBinder-API
> waren redundant (~25 Aktion-Varianten doppelt definiert). Korrektur: **Single
> Dispatch** über `dispatch(action: Action)` als einziger öffentlicher Eingang.
> LocalBinder schrumpft auf `state` + `dispatch` + Lifecycle-Hooks.
>
> **Architektur-Korrektur F-11 (2026-05-09):** Statt zentralisiertem Reducer +
> EffectRunner mit großem `when` über alle Achsen wird ein **Modular Orchestrator
> Pattern** eingeführt. Jedes Modul (Recording, Pipeline, Audio, …) kapselt
> seinen Sub-State + Actions + Reducer + SideEffects + EffectHandler in einer
> einzigen Datei. Der `DictateOrchestrator` routet Actions type-safe via
> `KClass<Action>`-Lookup ans richtige Modul, propagiert Cross-Module-Effekte
> via `onCrossModuleStateChange`. Inspiriert vom Excel-EKL Module-Augmentation-
> Pattern, in Kotlin abgebildet via `sealed interface DictateModule` +
> `object`-Singletons.
>
> Diese drei Korrekturen machen den ehemaligen `PipelineStateManager` zum
> deutlich schlankeren `DictateOrchestrator`. Die Hilfsklassen (Store,
> PrefMirror, Recovery) bleiben; ViewModeFsm wandert ins ViewModeModule.

### §4.1 Architektur-Übersicht (Modular Orchestrator Pattern)

```
DictateOrchestrator (Composition Root + zentrale Steuerung, kennt nur DictateModule-Interface)
   │
   │   ─── Hilfsklassen (Querschnitts-Concerns) ───
   ├── DictateUiStateStore        SSoT-Container: MutableStateFlow + atomare update()-Methode
   ├── PipelinePrefMirror         Pref-Sync: init-Read + OnSharedPreferenceChangeListener → Store
   ├── PipelineRecovery           suspend recover(): lädt Pending-Sessions aus Repo in Store
   ├── ModuleServices             Container für injizierte Hardware-Subsysteme
   │
   │   ─── 13 Module (jeweils 1 Datei mit State + Action + Reducer + SideEffect + EffectHandler) ───
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
   └── InterruptionModule         (Phase 2 — Anrufe, Headset-Plug, Screen-Off)
```

**Rationale:** Ohne diese Aufteilung würde:
1. `DictateUiState` zu einer 30+-Felder-Daten-Klasse anwachsen (SRP-Verletzung) — gelöst durch **Sub-State-Klassen** (F-10, §3)
2. Der Reducer + EffectRunner zu zwei großen `when`-Blöcken über alle Achsen anwachsen — gelöst durch **modulare Reducer + EffectHandler pro Modul** (F-11)
3. Cross-Achsen-Logik wäre über zentrale Stellen verstreut — gelöst durch `onCrossModuleStateChange`-Hook pro Modul

| Klasse | Verantwortung | Side-Effects | Testbarkeit |
|---|---|---|---|
| `DictateOrchestrator` | Composition Root + Action-Routing + Cross-Module-Cascade-Dispatch | nein (delegiert an Module) | Integration-Test mit Fake-Modulen |
| `DictateUiStateStore` | StateFlow-Owner | nein (pure data) | Trivial: in/out reducer |
| `DictateModule<S, A, E>` (Interface) | Plugin-Kontrakt | abstract | Pro Modul-Implementation eigener Test |
| `RecordingModule` (Beispiel) | Recording-Achse: State + Reducer + EffectHandler | ja (Hardware-Calls in runEffect) | Reducer pure → Unit-test ohne Hardware |
| `PipelinePrefMirror` | SP ↔ Store-Spiegelung | ja (SP-Listener) | Mit `FakeSharedPreferences` |
| `PipelineRecovery` | DB → Store Replay | ja (DB) | Mit `FakePipelineSessionRepo` |
| `ModuleServices` | DI-Container für Subsysteme | passiv | Mit Fake-Subsystemen |

### §4.2 DictateModule-Interface (F-11 / Plugin-Kontrakt)

Das `DictateModule`-Interface ist der Plugin-Kontrakt. Jedes der 13 Module (siehe §15) implementiert dieses Interface und kapselt seine fachliche Domäne vollständig: eigener Sub-State, eigene Actions, eigener Reducer, eigene SideEffects, eigener EffectHandler, optionaler Cross-Module-Observer.

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
}

/** TransitionResult — (nextState, sideEffects-Liste) als Reducer-Output. */
data class TransitionResult<S, E : SideEffect>(
    val nextState: S,
    val sideEffects: List<E>,
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
    data object Interruption : ModuleId
}

/** Kontext für Reducer — enthält Sub-States, die der Reducer für Bedingungen braucht. */
data class ReducerContext(
    val audio: AudioState,
    val recordingAudioFile: java.io.File?,
    val now: Long = System.currentTimeMillis(),
)
```

### §4.3 DictateOrchestrator (Composition Root)

Der Orchestrator löst den ehemaligen `PipelineStateManager` ab. Er kennt nur das `DictateModule`-Interface und routet Actions type-safe ans richtige Modul.

```kotlin
// File: app/src/main/java/net/devemperor/dictate/state/DictateOrchestrator.kt
package net.devemperor.dictate.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DictateOrchestrator(
    private val scope: CoroutineScope,
    private val store: DictateUiStateStore,
    private val servicesFactory: ModuleServicesFactory,
    private val prefMirror: PipelinePrefMirror,
    private val recovery: PipelineRecovery,
    private val modules: List<DictateModule<*, *, *>> = DictateModuleRegistry.all,
) {
    val state: StateFlow<DictateUiState> = store.state

    // Lookup-Map: action-class → modul. Beim Init einmalig gebaut.
    private val moduleByActionClass: Map<KClass<*>, DictateModule<*, *, *>> =
        modules.associateBy { it.actionClass }

    init {
        prefMirror.attach(store)
        scope.launch { recovery.recover(store) }
    }

    /** Single Dispatch — der einzige öffentliche Eingang für Mutationen (F-8). */
    fun dispatch(action: Action) {
        // 1. Modul für diese Action finden (type-safe via KClass-Lookup)
        val module = findModule(action) ?: run {
            android.util.Log.w(TAG, "Keine Modul-Zuordnung für $action")
            return
        }

        @Suppress("UNCHECKED_CAST")
        val typedModule = module as DictateModule<Any, Action, SideEffect>

        val prevGlobal = store.snapshot
        val subState = typedModule.read(prevGlobal)
        val ctx = buildContext(prevGlobal)

        // 2. Reducer-Aufruf (F1+F2 pure function)
        val result = typedModule.reduce(subState, action, ctx) ?: run {
            android.util.Log.w(TAG, "Action $action ungültig im aktuellen State")
            return
        }

        // 3. State-Update atomar
        store.update { typedModule.write(it, result.nextState) }

        // 4. SideEffects vom eigenen Modul ausführen
        val services = servicesFactory.get()
        result.sideEffects.forEach { effect -> typedModule.runEffect(effect, services) }

        // 5. Cross-Module-Observation: andere Module reagieren auf den State-Change
        val nextGlobal = store.snapshot
        val cascadeActions = modules
            .filter { it.id != module.id }
            .flatMap { it.onCrossModuleStateChange(prevGlobal, nextGlobal) }

        // 6. Cascade-Actions rekursiv dispatchen
        cascadeActions.forEach { dispatch(it) }
    }

    private fun findModule(action: Action): DictateModule<*, *, *>? {
        moduleByActionClass[action::class]?.let { return it }
        return modules.firstOrNull { module ->
            module.actionClass.java.isAssignableFrom(action::class.java)
        }
    }

    private fun buildContext(global: DictateUiState) = ReducerContext(
        audio = global.audio,
        recordingAudioFile = servicesFactory.get().recordingHardware.currentAudioFile(),
    )

    fun shutdown() = prefMirror.detach()

    companion object { private const val TAG = "DictateOrchestrator" }
}
```

**SRP-Verifikation:** Der Orchestrator macht ausschließlich Action-Routing + Cross-Module-Cascade. Er kennt **kein** Modul namentlich, kennt **keine** Recording-/Pipeline-/Audio-Logik — nur das `DictateModule`-Interface. Eine neue Achse hinzuzufügen erfordert KEINE Änderung am Orchestrator.

### §4.4 DictateUiStateStore (SSoT-Container)

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

**SRP:** reine Daten-Verwaltung. Keine Pref-Reads, keine Action-Methoden, keine Side-Effects.

### §4.5 PipelinePrefMirror (Pref-Sync, F-10 angepasst auf Sub-State-Struktur)

Erweitert um die 9 zusätzlichen UI-State-relevanten Prefs (RewordingEnabled, AutoFormattingEnabled, InstantOutput, Vibration, Theme, AccentColor, OverlayCharacters, OutputSpeed, UseBluetoothMic) und mappt auf die neue Sub-State-Struktur:

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

**SRP:** kapselt das Pref-Spiegelungs-Pattern als eigene Klasse. Erweitert um 9 zusätzliche UI-State-relevante Prefs (siehe Block 3.5 Klasse A).

### §4.6 PipelineRecovery (DB-Replay)

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

**SRP:** isoliert die DB-Recovery von State-Mutator und Modulen.

### §4.7 ModuleServices + ModuleServicesFactory

```kotlin
/**
 * DI-Container für Subsysteme, die EffectHandler brauchen. Wird vom Orchestrator
 * an `module.runEffect(effect, services)` übergeben.
 */
class ModuleServices(
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
    val sharedPrefs: android.content.SharedPreferences,
    val toastSink: ToastSink,
    val scope: kotlinx.coroutines.CoroutineScope,
    val emitAction: (Action) -> Unit,    // für Effect-Handler, die wieder Actions feuern
)

/** Lazy-Provider — Services werden beim Service-onCreate konstruiert, vom Orchestrator
 *  per Provider abgefragt. */
class ModuleServicesFactory(private val provider: () -> ModuleServices) {
    fun get(): ModuleServices = provider()
}
```

### §4.8 DictateModuleRegistry (zentrale Modul-Liste)

```kotlin
import net.devemperor.dictate.state.modules.*

object DictateModuleRegistry {
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
        // InterruptionModule (Phase 2 — auskommentiert bis aktiv)
    )

    /** Init-Sanity-Check: alle Module haben eindeutige IDs + actionClasses. */
    init {
        val ids = all.map { it.id }
        require(ids.toSet().size == ids.size) {
            "Doppelte ModuleId: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}"
        }
        val actionClasses = all.map { it.actionClass }
        require(actionClasses.toSet().size == actionClasses.size) {
            "Doppelte actionClass-Zuordnung in Registry"
        }
    }
}
```

**Compile-Time-Garantie:** `DictateModule` ist `sealed interface`, also kennt der Compiler alle Implementierer. Eine Alternative mit Reflection (`DictateModule::class.sealedSubclasses`) ist möglich (siehe Block 4.8 der Erläuterung); wir wählen die explizite Liste, weil sie debug-freundlicher und R8-/ProGuard-robust ist.

### §4.9 Dependency-Interfaces (F-2 / DIP, unverändert)

```kotlin
interface PipelineSessionRepo {
    suspend fun loadPending(): List<PendingSession>
    suspend fun markInserted(sessionId: Long, at: Long)
    suspend fun markFailed(sessionId: Long, reason: String)
    fun pendingFlow(): Flow<List<PendingSession>>
}

interface PipelineRunner {
    fun submit(audioFile: File)
    fun submitReprocess(audioFile: File, queue: List<Int>, language: String?)
    fun cancelActive()
    fun isRunning(): Boolean
}
```

Konkretisierungen: `RoomPipelineSessionRepo` (im SessionsDao-Aufruf), `JobExecutor` (statisches object adaptiert das Interface).

### §4.10 Kontrakt

Alle Mutationen laufen NUR über `DictateOrchestrator.dispatch(action)`. Das Modul-System enthält die Logik (Reducer + EffectHandler), der Orchestrator routet, der Store hält Wahrheit. Direkt-Mutationen auf View-Properties (`view.visibility = ...`) sind verboten. Subscriber lesen `store.state` über `StateFlow.collect`.

---

## §5 Local-Binder API (F-8: Single Dispatch)

> **Architektur-Korrektur F-8 (2026-05-09):** Frühere Spec-Versionen hatten den
> LocalBinder mit ~25 typed Action-Methoden (`pauseRecording()`, `stopRecording()`,
> `confirmInsertion()`, …), parallel zu einer `Action`-Sealed-Class mit denselben
> Varianten — Doppel-Definition, DRY-Verletzung. Korrektur: LocalBinder schrumpft
> auf einen einzigen `dispatch(action: Action)`-Eingang plus zwei Lifecycle-Hooks
> (View-Shown/Hidden), die KEINE User-Actions sind.
>
> Vorteile:
> - **DRY**: Action-Liste lebt nur in der `Action`-sealed-class (Spec 2 §3.3)
> - **OCP**: neue Action = nur sealed-Klasse erweitern, kein Forwarder im Binder
> - **Compile-Sicherheit**: Kotlin-Compiler erzwingt im Reducer-`when`-Block die
>   Exhaustivität — keine Action wird vergessen

```kotlin
class DictatePipelineService : Service() {
    private lateinit var orchestrator: DictateOrchestrator

    inner class LocalBinder : Binder() {
        /** Read-only State-Stream (collectable). */
        val state: StateFlow<DictateUiState> get() = orchestrator.state

        /** Single Dispatch — der einzige öffentliche Eingang für Mutationen. */
        fun dispatch(action: Action) = orchestrator.dispatch(action)

        /**
         * Lifecycle-Hooks: KEINE User-Actions, sondern System-Trigger vom IME-
         * Service-Lifecycle. Werden separat exposed, weil sie semantisch
         * Lifecycle-Events sind, keine User-Intentionen.
         *
         * Implementierung: feuern intern Action.ViewModeAction.OnImeViewShown/Hidden
         * — wird vom ViewModeModule reduziert.
         */
        fun notifyImeViewShown() = dispatch(Action.ViewModeAction.OnImeViewShown)
        fun notifyImeViewHidden() = dispatch(Action.ViewModeAction.OnImeViewHidden)
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder
}
```

**IME-Side:**

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

## §6 Persistence-Erweiterung

### §6.1 Schema-Migration M3→M4

> **Code-Pointer (Heute, M3):** `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo3.kt:43-75` — `MIGRATION_2_3` definiert die heutige `sessions`-Tabelle inkl. `status`/`origin`/`queued_prompt_ids`/`last_error_*`. Status-Enum-Quelle: `app/src/main/java/net/devemperor/dictate/database/entity/SessionStatus.kt:11-16` (heute: `RECORDED, COMPLETED, FAILED, CANCELLED`).

```sql
ALTER TABLE sessions ADD COLUMN inserted_at INTEGER;
```

Begründung: Session-Status `RECORDED → COMPLETED` wird heute über das `status`-Feld + Existenz von `final_output_text` impliziert. Eine explizite `inserted_at`-Spalte erlaubt:
- Restart-Button-Logik: "zeige nur Sessions wo `final_output_text IS NOT NULL AND inserted_at IS NULL`".
- Cleanup-Policy: "lösche Sessions wo `inserted_at IS NOT NULL AND inserted_at < now - 7d`".

**Konkrete Migration-Implementation** (Datei: `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo4.kt`, NEU):

```kotlin
package net.devemperor.dictate.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the `inserted_at` column to `sessions`.
 *
 * Additive migration — no table-recreate required. CHECK-Constraints
 * der bestehenden Spalten bleiben unangetastet (im Gegensatz zu
 * MIGRATION_2_3, wo CHECKs neu eingeführt wurden und deshalb
 * `CREATE TABLE … _new` + `INSERT … SELECT` nötig war).
 *
 * Backfill-Strategie: bestehende Zeilen mit `status = 'COMPLETED'` und
 * `final_output_text IS NOT NULL` bekommen `inserted_at = created_at`
 * (best-effort, weil der echte Insertion-Zeitpunkt nicht bekannt ist
 * — der Wert dient nur der Cleanup-Policy 7 Tage; die exakte
 * Genauigkeit ist irrelevant).
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN inserted_at INTEGER")
        db.execSQL(
            """
            UPDATE sessions
            SET inserted_at = created_at
            WHERE status = 'COMPLETED'
              AND final_output_text IS NOT NULL
            """.trimIndent()
        )
    }
}
```

**Wiring** (`DictateDatabase.kt:38` und `:73`):

```kotlin
// alt:
@Database(... version = 3, exportSchema = true)
.addMigrations(MIGRATION_1_2, MIGRATION_2_3)

// neu:
@Database(... version = 4, exportSchema = true)
.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
```

Außerdem in `SessionEntity.kt` (Zeile 51 ergänzt):

```kotlin
@ColumnInfo(name = "inserted_at") val insertedAt: Long? = null
```

Und im `SessionDao.kt` (vor Zeile 96 ergänzt):

```kotlin
/** Atomic INSERTED-Transition, gerufen von PipelineStateManager.confirmInsertion. */
@Query("UPDATE sessions SET inserted_at = :timestamp WHERE id = :id")
fun markInserted(id: String, timestamp: Long)

/** Pending-Sessions-Query für PipelineStateManager.recoverFromDb. */
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

### §6.2 Checkpoint-Hooks

Im `PipelineStateManager` wird jede State-Transition mit einem DB-Update versehen:

| State-Transition | DB-Schreibung |
|------------------|---------------|
| Recording-Start | `INSERT sessions (status=RECORDING, audio_path=NULL, started_at=now)` |
| Recording-Stop | `UPDATE sessions SET status=RECORDED, audio_path=..., ended_at=now WHERE id=...` |
| Pipeline-Start | `UPDATE sessions SET status=TRANSCRIBING WHERE id=...` |
| Pipeline-Done | `UPDATE sessions SET status=TRANSCRIBED, result_text=... WHERE id=...` |
| Insertion-Done | `UPDATE sessions SET status=INSERTED, inserted_at=now WHERE id=...` |
| Cancel | `UPDATE sessions SET status=CANCELLED WHERE id=...` |

### §6.3 Recovery-Read

Beim Service-`onCreate` (z.B. nach OOM-Death):

```kotlin
suspend fun recoverFromDb() {
    val stuck = db.sessionDao().getSessionsByStatuses(
        listOf(RECORDING, TRANSCRIBING, RECORDED, TRANSCRIBED)
    )
    val pending = stuck.map { it.toPendingSession() }
    // Pref-Mirror lesen (siehe §6.4): Overlay-Position pro Orientation
    val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
    _state.value = _state.value.copy(
        pendingSessions = pending,
        overlayPositionPortraitX = prefs.getFloat(Pref.OverlayPositionPortraitX, 1.0f),
        overlayPositionPortraitY = prefs.getFloat(Pref.OverlayPositionPortraitY, 0.1f),
        overlayPositionLandscapeX = prefs.getFloat(Pref.OverlayPositionLandscapeX, 1.0f),
        overlayPositionLandscapeY = prefs.getFloat(Pref.OverlayPositionLandscapeY, 0.1f),
    )
    // KEIN Auto-Resume — User muss Restart-Button klicken (User-Wahl)
}
```

### §6.4 SharedPreferences-Erweiterung — Overlay-Position (OPEN-3)

Die Overlay-Position wird **per Orientation getrennt** in SharedPreferences persistiert.
Werte sind **normalisierte 0..1-Koordinaten** relativ zur Bildschirmgröße — bei Orientation-
oder Display-Change ist der Pref-Wert unverändert nutzbar; das OverlayBackend
de-normalisiert vor dem Render zu absoluten Pixeln.

**Pref-Konstanten** (Datei: `app/src/main/java/net/devemperor/dictate/preferences/Pref.kt`, ergänzen):

```kotlin
object Pref {
    // ... bestehende Keys ...

    // Overlay-Position (OPEN-3): Float, 0..1, default Top-End mit ~80dp y-Offset
    const val OverlayPositionPortraitX = "overlay_position_portrait_x"   // default 1.0f
    const val OverlayPositionPortraitY = "overlay_position_portrait_y"   // default 0.1f
    const val OverlayPositionLandscapeX = "overlay_position_landscape_x" // default 1.0f
    const val OverlayPositionLandscapeY = "overlay_position_landscape_y" // default 0.1f
}
```

**Schreib-Trigger:** `PipelineStateManager.updateOverlayPosition(portrait, x, y)` — vom
`OverlayBackend` nach Drag-End emittiert (siehe Spec 3 §11.5). Implementierung:

```kotlin
fun updateOverlayPosition(portrait: Boolean, x: Float, y: Float) {
    val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
    val (xKey, yKey) = if (portrait) {
        Pref.OverlayPositionPortraitX to Pref.OverlayPositionPortraitY
    } else {
        Pref.OverlayPositionLandscapeX to Pref.OverlayPositionLandscapeY
    }
    prefs.edit()
        .putFloat(xKey, x.coerceIn(0f, 1f))
        .putFloat(yKey, y.coerceIn(0f, 1f))
        .apply()
    _state.value = if (portrait) {
        _state.value.copy(overlayPositionPortraitX = x, overlayPositionPortraitY = y)
    } else {
        _state.value.copy(overlayPositionLandscapeX = x, overlayPositionLandscapeY = y)
    }
}
```

**Lese-Trigger:** Pref-Mirror beim `recoverFromDb()` / Init — siehe §6.3 oben. Das stellt
sicher, dass nach OOM-Death oder Service-Restart die zuletzt gespeicherte Position
sofort im State liegt; das OverlayBackend liest die Werte vom State (nicht direkt aus
Pref) und respektiert damit die Single-Source-of-Truth-Regel "alles via DictateUiState".

**SOLID-Konformität:** Die Pref-Mirror-Logik bleibt im `PipelineStateManager` gekapselt
(Single-Responsibility); `OverlayBackend` kennt nur die Action `UpdateOverlayPosition`
und die State-Felder, nicht die Pref-Keys (Dependency-Inversion).

---

## §7 Lifecycle: Foreground Service

> **Architektur-Korrektur F-3 (Iteration 2026-05-08):** Frühere Spec-Versionen
> bauten Notification-Building, State-Subscribe und Action-PendingIntent-Routing
> direkt in `DictatePipelineService.onStartCommand`. Diese drei Concerns
> werden jetzt in zwei Helper-Klassen extrahiert (`PipelineNotificationCoordinator`,
> `PipelineActionRouter`), damit der Service einzig Process-Lifecycle-Owner
> ist und alles andere injiziert + testbar ist.

### §7.1 Service-Struktur (F-3 / SRP)

```
DictatePipelineService (Process-Lifecycle-Owner)
   ├── PipelineStateManager           // §4.3, Composition Root für State
   ├── PipelineNotificationCoordinator // baut Notifications, abonniert Store
   └── PipelineActionRouter            // PendingIntent → Manager.action
```

**Verantwortlichkeiten:**

| Klasse | SRP | Side-Effects |
|---|---|---|
| `DictatePipelineService` | FGS-Lifecycle (`startForeground`/`stopSelf`), Bind-Connection | ja (FGS-Calls) |
| `PipelineNotificationCoordinator` | State → Notification-Render, throttled | ja (NotificationManager) |
| `PipelineActionRouter` | PendingIntent-Build + Intent-Decode → Action-Dispatch | nein (pure Mapping) |

### §7.2 Start

```kotlin
// IME-Service onCreate (oder beim ersten Recording):
ContextCompat.startForegroundService(this, Intent(this, DictatePipelineService::class.java))
bindService(Intent(this, DictatePipelineService::class.java), connection, BIND_AUTO_CREATE)
```

### §7.3 onStartCommand (schlank)

```kotlin
class DictatePipelineService : Service() {

    private lateinit var stateManager: PipelineStateManager
    private lateinit var notifCoordinator: PipelineNotificationCoordinator
    private lateinit var actionRouter: PipelineActionRouter
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // Composition Root — alle Hilfsklassen werden hier konstruiert + verdrahtet.
        val store = DictateUiStateStore(initialDictateUiState())
        val sessionRepo: PipelineSessionRepo = RoomPipelineSessionRepo(database.sessionsDao())
        val runner: PipelineRunner = JobExecutor
        val prefMirror = PipelinePrefMirror(getSharedPreferences(...))
        val recovery = PipelineRecovery(sessionRepo)

        stateManager = PipelineStateManager(
            scope = serviceScope,
            sessionRepo = sessionRepo,
            runner = runner,
            store = store,
            fsm = ViewModeFsm,
            prefMirror = prefMirror,
            recovery = recovery,
            recordingHardware = RecordingHardware(audioManager, ...),
        )
        notifCoordinator = PipelineNotificationCoordinator(this, stateManager.state, serviceScope)
        actionRouter = PipelineActionRouter(stateManager)

        runner.initialize(orchestrator)   // G7: JobExecutor-init wandert hierher (vom IME-onCreate)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Action-Intents verarbeiten (von Notification-Buttons)
        intent?.let { actionRouter.dispatch(it) }
        // 2. Initiale FGS-Notification + reaktive Updates starten
        startForeground(NOTIF_ID, notifCoordinator.buildInitial())
        notifCoordinator.startReactiveUpdates(::stopSelfWhenTerminal)
        return START_NOT_STICKY
    }

    private fun stopSelfWhenTerminal(state: DictateUiState) {
        if (state.isAllTerminal()) stopSelf()
    }

    override fun onBind(intent: Intent): IBinder = LocalBinder()
    override fun onDestroy() {
        super.onDestroy()
        stateManager.shutdown()
        serviceScope.cancel()
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

**SRP:** ausschließlich State → Notification-Mapping + Subscription-Management. Kein Action-Routing, kein Lifecycle-Wissen außer "wann ist der Service terminal".

### §7.5 PipelineActionRouter

```kotlin
class PipelineActionRouter(
    private val stateManager: PipelineStateManager,
) {
    /** Vom `onStartCommand` gerufen, wenn ein Action-Intent ankommt. */
    fun dispatch(intent: Intent) {
        when (intent.action) {
            ACTION_PAUSE   -> stateManager.pauseRecording()
            ACTION_RESUME  -> stateManager.resumeRecording()
            ACTION_STOP    -> stateManager.stopRecording()
            ACTION_SEND    -> stateManager.stopRecording()        // semantisch identisch
            ACTION_CANCEL  -> stateManager.cancelPipeline()
            ACTION_INSERT  -> intent.getLongExtra(EXTRA_SESSION_ID, -1L)
                                  .takeIf { it >= 0 }
                                  ?.let { stateManager.confirmInsertion(it) }
            ACTION_DISMISS -> intent.getLongExtra(EXTRA_SESSION_ID, -1L)
                                  .takeIf { it >= 0 }
                                  ?.let { /* sessionRepo.markDismissed(it) — TBD §14 */ }
        }
    }

    /** PendingIntent-Builder, vom NotificationCoordinator genutzt. */
    fun pendingIntentFor(ctx: Context, action: String, sessionId: Long? = null): PendingIntent {
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

**SRP:** pure Mapping zwischen Notification-Action-Strings und `PipelineStateManager`-Methoden. Keine UI-Logik, keine Notification-Build, kein Lifecycle. Tests können einen Mock-`PipelineStateManager` injizieren und prüfen, dass jeder Action-String die richtige Methode trifft.

### §7.6 Notification-Inhalt

| State | Title | Subtitle | Actions |
|-------|-------|----------|---------|
| Idle (sollte nie sichtbar sein, weil stopSelf) | — | — | — |
| Recording-Active | "Dictate" | "Aufnahme läuft" (kein Sekunden-Timer, siehe §14 Open-1) | [Pause] [Stopp] [Senden] |
| Recording-Paused | "Dictate" | "Aufnahme pausiert" | [Resume] [Stopp] [Senden] |
| Pipeline-Running | "Dictate" | "Verarbeite (Schritt 2/4)" | [Abbrechen] |
| Pipeline-Done, ungelesen | "Dictate" | "Bereit zum Einfügen" | [Einfügen] [Verwerfen] |

### §7.7 Stop

`stopSelf()` wird vom Service gerufen, sobald `notifCoordinator` einen Terminal-State emittiert (`state.isAllTerminal()` — kein Recording, keine laufende Pipeline, keine pending-fertigen Sessions). Dann wird die Notification automatisch entfernt.

---

## §8 IME-Service-Integration

Der `DictateInputMethodService` wird durch das Refactor **deutlich schlanker**:

| Bestandteil | Heute (im IME-Service) | Künftig |
|-------------|------------------------|---------|
| Recording-Logik | RecordingStateController | wandert in PipelineStateManager |
| Pipeline-Logik | KeyboardUiController + JobExecutor + PipelineOrchestrator | wandert teilweise (State-Teil); JobExecutor + PipelineOrchestrator bleiben, aber im PipelineService |
| State-Coroutinen | keine | im PipelineService (serviceScope) |
| View-Rendering | direkter Code in Service | wandert in KeyboardLayoutManager (Spec 2) |
| Visibility-Mutations | hybrid (KSM + RecordingUiController + Service) | weg — alles über LayoutManager-Resolver (Spec 2) |
| Click-Listener-Verdrahtung | MainButtonsController | wandert in KeyboardLayoutManager (Spec 2) |

**Was im IME-Service bleibt:**
- View-Lifecycle (`onCreateInputView`, `onStartInputView`, `onFinishInputView`, `onDestroy`).
- IME-spezifische APIs (`getCurrentInputConnection()`, `requestHideSelf()`, `setInputView()`).
- Bind/Unbind zum PipelineService.
- Forwarding von User-Events an PipelineService.

---

## §9 Migration vorhandener Klassen

### §9.1 RecordingStateController → PipelineStateManager

**Heute:** `app/src/main/java/net/devemperor/dictate/core/RecordingStateController.kt`
- Klasse mit `var state: RecordingState` (Z. 106-107) — interne Mutation via `setState(newState)` (Z. 353-357).
- Public-API-Methoden, die State mutieren:
  - `startRecording(audioFile, useBluetooth, audioFocusEnabled)` → `:128-140`
  - `stopRecording()` → `:145-159`
  - `togglePause()` → `:164-180`
  - `setAudioFocusRuntime(enabled)` → `:201-212` (mutiert Field + AudioManager)
  - `cancelRecording()` → `:217-225`
  - Lifecycle-Hooks `onKeyboardHidden() / onKeyboardShown() / onDestroy()` → `:233-267`
  - Manager-Callbacks: `onRecordingStarted/Stopped/Paused/Resumed`, `onScoConnected/Disconnected/Failed` → `:271-321`
- Callback-Forwarding: `Callback.onStateChanged(old, new)` (Z. 90).
- Konstruktion + Wiring (Service-Side): `DictateInputMethodService.java:372-376` (`recordingStateController = new RecordingStateController(...)` + `setManagers`); `setCallback` in `:914-958`.

**Künftig:** Wandert komplett in `PipelineStateManager` als interne private Methoden. Die `Callback`-Schnittstelle entfällt — der Service abonniert via `state.collect { ... }` und reagiert auf `oldState.recording` vs. `newState.recording`-Diffs.

| Heute (Methode) | Künftig (PipelineStateManager) | Mutiert in `DictateUiState` |
|---|---|---|
| `startRecording(...)` (Z. 128) | `startRecording(target: InsertionTarget)` | `recording: Idle → Preparing/Active` |
| `stopRecording()` (Z. 145) | `stopRecording()` | `recording: Active → Idle` + Pipeline-Auto-Start |
| `togglePause()` (Z. 164) | `pauseRecording()` / `resumeRecording()` | `recording: Active ↔ Paused` |
| `setAudioFocusRuntime(b)` (Z. 201) | `toggleAudioFocus()` (private Pref-Write + Live-Hook) | `audioFocusEnabled` |
| `cancelRecording()` (Z. 217) | `cancelPipeline()` | `recording: → Idle` + Pipeline-Cleanup |

**Datentyp `RecordingState`** (`RecordingState.kt:10-18`) bleibt unverändert erhalten — er ist sealed-class, exhaustiv, gut. Wird zum Feld von `DictateUiState` (siehe §3).

### §9.2 KeyboardUiController-State-Teil → PipelineStateManager

**Heute:** `app/src/main/java/net/devemperor/dictate/core/KeyboardUiController.kt`
- State-Field `var state: PipelineUiState` (Z. 63).
- Mutator `updateDictateUiState(newState)` (Z. 147-155) — single-call-site interne Methode, von 5 Public-API-Methoden gerufen:
  - `preparePipeline()` → `:213-219`
  - `startPipeline(totalSteps, config, initialCompletedSteps)` → `:230-265`
  - `stopPipeline()` → `:271-285`
  - `toggleAutoEnter()` → `:292-299`
  - `enterReprocessStaging(...)` → `:307-321`
  - `cancelReprocessStaging()` → `:326-330`
  - `updateReprocessQueue(queue)` → `:335-340`
  - `updateReprocessLanguage(code)` → `:348-353`
  - Internal-Updater `addRunningStep / completeStep / failStep` → `:364-456` (alle rufen `updateRunningState { ... }` Z. 161-166).
- View-Mutations innerhalb `updateDictateUiState`: ruft `refreshRecordButtonFromState()` (Z. 150, view-mutation: button text/icon/enabled — Z. 464-509) und `stateManager.refresh()` (Z. 153) — die View-Mutation wandert weg in den LayoutCatalog (Spec 2 §9.5), die State-Mutation in PipelineStateManager.

**Künftig:**
- Sealed Class `PipelineUiState` (`PipelineUiState.kt:13-54`) bleibt — Datentyp ist gut.
- State-Field + Mutator wandern in `PipelineStateManager` als private `_state.update { it.copy(pipeline = newDictateUiState) }`.
- Public-API-Methoden auf `KeyboardUiController` werden ersetzt durch identisch benannte Methoden auf `PipelineStateManager` (siehe §4-API). Die heutigen Implementations werden inline migriert — kein "Wrapper".
- `refreshRecordButtonFromState()` (Z. 464-509) wandert in den `RECORD`-Slot-`textResolver` + `enabledResolver` im LayoutCatalog (Spec 2 §9.5).
- `stepRows`-Verwaltung (Z. 133-135 + `addRunningStep / completeStep / failStep`) bleibt im `KeyboardUiController` (View-side), wird aber durch `state.pipeline`-StateFlow-Subscriber getriggert statt durch direkte Methodenaufrufe vom Service.
- `AutoEnterConfig`-Field (Z. 67) wandert in `DictateUiState.pipeline` (das `PipelineUiState.Running`-Variant trägt `autoEnterActive` bereits — `AutoEnterConfig` ist eine redundante Schicht und entfällt).

### §9.3 KeyboardStateManager → PipelineStateManager + LayoutCatalog

**Heute:** `app/src/main/java/net/devemperor/dictate/core/KeyboardStateManager.kt`
- Eigene State-Felder: `contentArea: ContentArea` (Z. 100), `isSmallMode: Boolean` (Z. 102) — heute private Setter, mutiert via `setContentArea(area)` (Z. 135-138) und `setSmallMode(enabled)` (Z. 140-146).
- `applyVisibility()` (Z. 158-169) mit Sub-Funktionen `applyContentAreaVisibility()` (Z. 171-181), `applyRecordingControlsVisibility()` (Z. 183-192), `applyPromptsVisibility()` (Z. 194-224), `applyPromptsLayout()` (Z. 227-240). Mutiert direkt 8 View-Properties (siehe §13.1).
- `refresh()` (Z. 151-154) — externer Trigger, ruft `applyVisibility()`.
- Lambda-Konstruktor-Parameter: `isRecording`, `isPaused`, `isPipelineRunning`, `isRewordingEnabled`, `isPipelineProgressVisible`, `isReprocessStaging` (Z. 78-97). Diese Lambdas befragen heute `RecordingStateController` und `KeyboardUiController` direkt — werden durch reaktiven `state.collect`-Subscriber ersetzt.

**Künftig:**
- `contentArea` und `isSmallMode` wandern in `DictateUiState` (siehe §3) — Mutation via `setContentArea(area)` und `toggleSmallMode()` auf dem `PipelineStateManager`.
- Die Lambda-basierten Anfrage-Patterns entfallen: alle 6 Lambdas lesen heute Felder aus 2 verschiedenen Klassen; künftig sind alle Achsen Member von `DictateUiState`, der Subscriber bekommt sie atomar in einer Emission.
- `applyVisibility` + Sub-Funktionen wandern komplett in den `LayoutCatalog`-Resolver (Spec 2 §9.3). 4 von 8 Visibility-Mutationen werden Predicate-driven (siehe §13.1); 4 sind ContentArea-Achsen-Mutationen, die in `LayoutCatalog.forKeyboard(state)` einfließen.
- Die `setLayoutModeController()` / `clearLayoutModeController()`-Brücke (Z. 115-131) entfällt vollständig — `KeyboardLayoutModeController` wird in Spec 2 §9.1 durch MotionLayout ersetzt.

### §9.4 5 verstreute resend_btn-Mutationen

**Konkrete Mutations-Sites (heute):**

| Datei : Zeile | Code | Kontext |
|---|---|---|
| `RecordingUiController.kt:137` | `resendButton.visibility = if (getLastAudioFileExists()) View.VISIBLE else View.GONE` | `applyIdleState()` — bei Recording → Idle |
| `RecordingUiController.kt:158` | `resendButton.visibility = View.GONE` | `applyActiveState()` — bei Recording aktiv |
| `DictateInputMethodService.java:1345` | `resendButton.setVisibility(View.VISIBLE);` | `onStartInputView` — Idle-Wiedereinstieg |
| `DictateInputMethodService.java:1347` | `resendButton.setVisibility(View.GONE);` | `onStartInputView` — Idle-Wiedereinstieg, kein Audio |
| `DictateInputMethodService.java:1669` | `resendButton.setVisibility(View.GONE);` | `runTranscriptionViaOrchestrator` — Pipeline-Start |
| `DictateInputMethodService.java:1839` | `resendButton.setVisibility(View.VISIBLE);` | `onShowResend()` Callback — Pipeline-Done |

(Nominell "5 verstreute Stellen" laut Plan-Sprache — tatsächlich sind es 6 Mutations-Sites über 2 Dateien; die Plan-Aussage zählt RecordingUiController-Z.137+158 als "eine Stelle pro Komponente").

**Eliminiert in Block 1.** Stattdessen ein einziges Predicate im LayoutCatalog (Spec 2):

```kotlin
// Slot-Definition für RESEND-Slot in LayoutCatalog (siehe Spec 2 §3.2):
ButtonSlot(
    logicalId = LogicalButtonId.RESEND,
    visibilityPredicate = { state ->
        state.lastAudioExists
            && state.resendEnabled                              // Pref.ResendButton
            && state.recording is RecordingState.Idle
            && state.pipeline is PipelineUiState.Idle
    },
    actionResolver = { /* short-press: re-run pipeline; long-press: enter staging */ }
)
```

**Datenfluss:**
- `lastAudioExists` wird beim Service-`onCreate` vorbelegt (`File.exists()`-Check auf `Pref.LastFileName`) und nach jedem Recording-Done aktualisiert (`stopRecording`-Callback).
- `resendEnabled` ist eine Spiegelung von `Pref.ResendButton` — wird beim `onCreate` gelesen und auf SP-Listener gehört.
- `recording / pipeline`-Achsen sind ohnehin Member von `DictateUiState`.

### §9.5 recordButton.text/isEnabled-Hybrid

**Heute (Race-fragil):**
- `RecordingUiController.applyIdleState` (Z. 115-138) setzt `recordButton.text/isEnabled/icon` für Idle.
- `RecordingUiController.applyActiveState` (Z. 144-184) setzt für Active.
- `RecordingUiController.applyPreparingState` (Z. 140-142) setzt nur `isEnabled = false`.
- `KeyboardUiController.refreshRecordButtonFromState` (Z. 464-509) setzt für Preparing/Running/ReprocessStaging.
- **Race:** Wenn nach Recording-Stop direkt Pipeline-Start kommt, läuft der Idle-Branch von `RecordingUiController` einmal (`onStateChanged: Active → Idle`) — und unmittelbar danach `KeyboardUiController.refreshRecordButtonFromState` mit `Preparing`. Bei rotation/restoreUiState ist die Reihenfolge nicht deterministisch (siehe `restoreUiState` `:973-1026`).

**Künftig:** ein einziger `textResolver` + `enabledResolver` in der RECORD-Slot-Definition im LayoutCatalog (Spec 2 §9.5). Beispiel-Skelett:

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

Damit ist die Mutation-Ordnung fixiert: ein einziges `state.collect` triggert genau einen `slot.apply(view, state)`-Call, der `text` und `isEnabled` in derselben Frame-Phase setzt.

---

## §10 Acceptance-Kriterien

Block 2 (DictatePipelineService) gilt als done, wenn:

- [ ] Recording starten, Tastatur zur Gboard wechseln, 30s warten, zurück zu Dictate → Recording läuft noch, Pulse-Animation läuft im IME-View, Pause-Button funktioniert.
- [ ] Pipeline starten, App-Switch, 30s warten, zurück → Pipeline-Status korrekt restauriert, Notification zeigt Status.
- [ ] Beim Recording: persistente Notification sichtbar, zeigt korrekte Action-Buttons.
- [ ] `stopSelf()` greift: nach Insertion verschwindet die Notification ohne weitere Aktion.
- [ ] Force-Stop der App: beim nächsten Tastatur-Open wird Restart-Button mit pending-Session gezeigt.
- [ ] Manueller Restart-Button-Klick: PipelineService startet neu, Pipeline läuft mit korrektem State.

Block 1 (State-SSOT-Konsolidierung) gilt als done, wenn:
- [ ] resend_btn-Visibility wird nur an EINER Stelle berechnet (Predicate im PipelineStateManager).
- [ ] recordButton.text/isEnabled wird nur an EINER Stelle gesetzt (im Vorgriff auf Spec 2 zunächst noch in einem zentralen Resolver innerhalb KeyboardUiController, in Block 5 dann final in LayoutCatalog).
- [ ] Service.onSingleRowModeToggled triggert KSM.refresh() (Quick-Win-Fix).
- [ ] Service.onAudioFocusToggled triggert KSM.refresh() (Quick-Win-Fix).
- [ ] Alle existierenden Use-Cases (UC1-UC7 + UC-extra-1 bis UC-extra-10 aus _pending-state-machine-visibility-owners.md §4) funktionieren weiterhin.

Block 3 (DB-Persistence) gilt als done, wenn:
- [ ] M3→M4-Migration läuft fehlerfrei auf einer Test-DB.
- [ ] Alle Checkpoint-Hooks schreiben korrekte DB-Updates.
- [ ] `recoverFromDb()` lädt stuck Sessions korrekt.
- [ ] Cleanup-Policy (>7d alte INSERTED Sessions) läuft auf Service-Start.

---

## §11 Research-TODOs für Agent — Detail-Antworten

### §11.1 Foreground-Service-Implementierung

#### §11.1.1 AndroidManifest.xml — exakter Diff

**Heute (`app/src/main/AndroidManifest.xml`):**
- Permissions `RECORD_AUDIO, INTERNET, VIBRATE, BLUETOOTH, MODIFY_AUDIO_SETTINGS` (Z. 5-9).
- Nur ein `<service>`-Eintrag: der IME-Service `DictateInputMethodService` (Z. 29-40).

**Diff (additiv, NICHTS löschen):**

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

Begründung pro Zeile:
- `FOREGROUND_SERVICE` — Pflicht ab Android 9 (API 28) für jeden FGS.
- `FOREGROUND_SERVICE_MICROPHONE` — Pflicht ab Android 14 (API 34) wenn `foregroundServiceType="microphone"`. Da `targetSdk = 35` (`build.gradle:14`) müssen wir das deklarieren.
- `POST_NOTIFICATIONS` — Pflicht ab Android 13 (API 33) für jede User-sichtbare Notification. Muss zur Laufzeit angefragt werden (siehe §11.5.1).
- `android:foregroundServiceType="microphone"` — Ohne diesen Wert wirft Android 14+ `ForegroundServiceTypeNotAllowedException` beim Start. Wir nutzen "microphone", weil Recording während des Service läuft. Für reine Pipeline-Phasen (nach Stop) ist der Type strenggenommen falsch — dafür gibt es zwei Optionen:
  1. Während Recording: `microphone`-Type. Nach Recording-Stop: zu `dataSync` wechseln via `startForeground(notifId, notif, FOREGROUND_SERVICE_TYPE_DATA_SYNC)`. Erfordert `FOREGROUND_SERVICE_DATA_SYNC`-Permission zusätzlich.
  2. Während gesamter Service-Lifetime: `microphone` deklariert lassen — Android erlaubt das, solange der Mikrofon-Zugriff im Lifecycle wenigstens potenziell aktiv ist. Einfacher; akzeptiert für unsere Use-Cases.
  
  → **Entschieden: Option 2** (einfacher, Wartungsschuld minimal).
- `android:exported="false"` — Service ist nur für die App selbst (kein IPC).
- `android:description` — String-Ressource neu anlegen: `@string/dictate_pipeline_service_description = "Hintergrund-Service für Diktier-Pipeline"`.

#### §11.1.2 Notification.Builder — konkrete Implementation

**Channel-Setup** (im `DictatePipelineService.onCreate`):

```kotlin
companion object {
    private const val CHANNEL_ID = "dictate_pipeline"
    private const val NOTIF_ID = 1001  // beliebige stabile Konstante
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

**Notification-Builder pro State-Variante** (siehe §7.3-Tabelle):

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

`onStartCommand` reagiert auf die Action-Strings:

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action != null) {
        when (intent.action) {
            ACTION_PAUSE -> stateManager.pauseRecording()
            ACTION_RESUME -> stateManager.resumeRecording()
            ACTION_STOP -> stateManager.stopRecording()
            ACTION_SEND -> stateManager.stopRecordingAndSend()
            ACTION_CANCEL -> stateManager.cancelPipeline()
            ACTION_INSERT -> stateManager.confirmFirstPendingInsertion()
            ACTION_DISCARD -> stateManager.discardFirstPendingInsertion()
        }
    } else {
        // Erster Start ohne Action — initialer FGS-Start.
        startForegroundCompat(stateManager.state.value)
    }
    return START_NOT_STICKY
}

private fun startForegroundCompat(state: DictateUiState) {
    val notif = buildNotification(state)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {  // API 34
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    } else {
        startForeground(NOTIF_ID, notif)
    }
}
```

#### §11.1.3 startForegroundService vs. startService — Versions-Differential

| API | Verhalten |
|---|---|
| `< 26 (Oreo)` | `startService` reicht; FGS-Concept existiert nicht in der Schärfe. |
| `>= 26` | `startForegroundService` Pflicht — ohne ihn wirft Android `IllegalStateException` wenn `startForeground` aus Background gerufen wird. |
| `>= 31 (S)` | Zusätzliche Restriktionen: FGS-Start aus Background nur für definierte Fälle (Foreground-App, MediaProjection, etc.). IMEs sind privilegiert (durch `BIND_INPUT_METHOD`-Aufruf des Systems). |
| `>= 34` | `foregroundServiceType` muss deklariert + bei `startForeground(id, notif, type)` mitgegeben werden. |

**Wir starten den Service in `DictateInputMethodService.onCreateInputView` ODER beim ersten Recording-Click**, je nach Strategie (siehe §11.3.1). Der IME-Service ist Foreground-privilegiert (im Gegensatz zu Background-Apps), daher dürfen wir den FGS jederzeit starten.

#### §11.1.4 5-Sekunden-Timeout für `startForeground`

Wenn nach `Context.startForegroundService(intent)` nicht binnen 5 s `startForeground(id, notification)` gerufen wird, wirft das System `ForegroundServiceDidNotStartInTimeException` (API 26+) und beendet den Service mit ANR-ähnlichem Verhalten.

**Mitigation:** `onCreate` des Service ruft `startForegroundCompat()` synchron als allererste Aktion (vor jeglicher Coroutine-Initialisierung). Der Notification-Builder darf KEINE blocking-DB-Calls machen — er liest nur aus `stateManager.state.value`, das im Memory liegt.

```kotlin
override fun onCreate() {
    super.onCreate()
    ensureNotificationChannel()                  // synchron, in-memory
    stateManager = PipelineStateManager(scope, db, jobExecutor)  // sync Constructor
    startForegroundCompat(stateManager.state.value)  // synchron, < 50ms
    scope.launch {                                // ASYNC erst danach
        stateManager.recoverFromDb()              // DB-IO, darf länger dauern
        stateManager.state.collect { ... }
    }
}
```

### §11.2 PipelineStateManager-Implementierung

#### §11.2.1 Konkrete Code-Pointer pro Migrations-Schritt

Siehe §9.1 - §9.5 oben — alle Migrations-Sites haben jetzt `file:line`-Pointer und Tabellen.

#### §11.2.2 Migrations-Reihenfolge (Block-1 vor Block-2 vor Block-3)

Drei Blöcke aus dem Hauptplan, in der Reihenfolge ihrer Implementation:

**Block 1 — State-SSOT-Konsolidierung (kein Service, kein DB-Schema-Change)**

Ziel: alle State-Mutationen, die heute auf View-Properties direkt schreiben oder auf 2 verschiedenen Klassen verstreut sind, werden in einer einzigen `DictateUiState`-Klasse + `MutableStateFlow` konsolidiert. Der `PipelineStateManager` lebt zunächst ohne Service — er ist eine schlichte Klasse, vom IME-Service direkt instanziert.

Reihenfolge der Sub-Schritte:

1. **DictateUiState-Datentyp anlegen** (neue Datei `core/DictateUiState.kt`) — pure Datenklasse, kein Verhalten.
2. **PipelineStateManager-Skelett anlegen** (neue Datei `core/PipelineStateManager.kt`) — `MutableStateFlow<DictateUiState>`, leere Action-Methoden.
3. **RecordingStateController-Inhalt einkopieren** (Methoden aus `RecordingStateController.kt:128-321`) — Body wandert in PipelineStateManager-Methoden, mutiert `_state.update { it.copy(recording = newRecordingState) }`. Existierende `RecordingStateController.Callback`-Empfänger werden auf `state.collect`-Subscriber umgebaut. ⚠ Achtung: `RecordingManager` und `BluetoothScoManager` haben Callback-Backrefs auf den Controller — die müssen mitgezogen werden (PipelineStateManager wird zum neuen Callback-Empfänger).
4. **KeyboardUiController-State-Teil migrieren** (`KeyboardUiController.kt:147-353`) — `updateDictateUiState`-Body wandert in PipelineStateManager-Methoden. Public-API-Methoden (`preparePipeline`, `startPipeline`, ...) bekommen Wrapper-Forwarding `→ stateManager.preparePipeline()`. Service-Call-Sites bleiben unverändert in diesem Schritt — kein Big-Bang-Edit.
5. **resend_btn-Mutationen entfernen** (siehe §13.1 Tabelle): die 6 Mutations-Sites werden durch ein Predicate ersetzt. Da Spec 2 (LayoutCatalog) noch nicht da ist, wird in diesem Block 1 ein temporärer Stand-In im PipelineStateManager geschrieben:
   ```kotlin
   // Transitional in Block 1 (wird in Block 5 durch LayoutCatalog ersetzt):
   stateManager.state.collect { state ->
       val visible = state.lastAudioExists && state.resendEnabled
           && state.recording is RecordingState.Idle
           && state.pipeline is PipelineUiState.Idle
       resendButton.visibility = if (visible) View.VISIBLE else View.GONE
   }
   ```
6. **`KeyboardStateManager.contentArea` und `isSmallMode` migrieren** in den `DictateUiState`. `applyVisibility()` bleibt zunächst in `KeyboardStateManager` — wird in Spec 2 gelöscht. Die heutigen Lambda-Konstruktor-Parameter (Z. 78-97) werden auf `state.value`-Reads umgestellt; die `refresh()`-Trigger werden Subscriber-driven.
7. **Quick-Win-Fixes** (Plan-Acceptance Block-1): `onSingleRowModeToggled` → `stateManager.refresh()`-Trigger, `onAudioFocusToggled` → ebenfalls. Heute fehlt `stateManager.refresh()` nach `mainButtonsController.refreshAudioFocusIcon` — siehe `DictateInputMethodService.java:2664-2687`.

**Block 2 — DictatePipelineService einführen (Service-Klasse + Bound-Binder, KEIN DB-Schema-Change)**

1. **Service-Klasse anlegen** (`core/DictatePipelineService.kt`) — Skelett, `LocalBinder`, `onCreate`/`onStartCommand`/`onBind`/`onDestroy`.
2. **PipelineStateManager-Konstruktion verschieben** vom `DictateInputMethodService.initLongLivedObjects` (`:329-396`) in `DictatePipelineService.onCreate`. Der IME-Service hält nicht mehr selbst den StateManager — er hält nur noch eine `LocalBinder?`-Referenz.
3. **Bound-Connection-Setup im IME** (siehe §11.3.1) — `bindService` in `onCreate`, `unbindService` in `onDestroy`.
4. **Notification + startForeground** verdrahten (§11.1.2).
5. **Manifest erweitern** (§11.1.1).

**Block 3 — DB-Persistence (Schema-Migration M3→M4)**

1. **MigrationTo4.kt anlegen** (siehe §6.1).
2. **Schema-Version + addMigrations** in `DictateDatabase.kt` (§6.1).
3. **`SessionEntity.insertedAt`-Feld** ergänzen (§6.1).
4. **`SessionDao.markInserted/findPendingInsertion/deleteInsertedOlderThan`** ergänzen (§6.1).
5. **Checkpoint-Hooks im PipelineStateManager** (siehe §6.2-Tabelle): pro State-Transition ein DAO-Aufruf in einem `scope.launch(Dispatchers.IO)`.
6. **`recoverFromDb()`** im `PipelineStateManager.onCreate` — lädt pending sessions in `state.pendingSessions`.
7. **Cleanup-Policy** beim Service-Idle-Stop: `dao.deleteInsertedOlderThan(now - 7d)` einmal vor `stopSelf()`.

#### §11.2.3 Test-Strategie

Existierende Tests (`app/src/test/java/net/devemperor/dictate/core/`):
- `RecordingStateControllerTest.kt` — pure Kotlin, nutzt `FakeAudioFocusGate.kt`. Block-1-Auswirkung: Tests werden auf `PipelineStateManager` umgebaut. State-Assertions ändern sich von `controller.state == X` zu `manager.state.value.recording == X`.
- `JobExecutorTest.kt` — bleibt unangetastet (JobExecutor bleibt erhalten — siehe §8 + Plan-Hauptaussage).
- `ActiveJobRegistryTest.kt` — unverändert.

Neue Tests pro Block:

| Block | Neue Test-Klasse | Inhalt |
|---|---|---|
| 1 | `PipelineStateManagerTest.kt` | State-Transitions, audio-focus, single-row-toggle, resend-eligibility, pendingSessions-Updates |
| 1 | `DictateUiStateTest.kt` | `data class`-Equality, `copy()`-Verhalten, sealed-class-exhaustivität |
| 2 | `DictatePipelineServiceTest.kt` | Robolectric-Service-Test: `onCreate`-Lifecycle, `onStartCommand`-Action-Routing, FGS-Start innerhalb 5s. |
| 2 | `LocalBinderTest.kt` | Bound-Service-Test: `onServiceConnected` triggert `state.collect`-Subscriber. |
| 3 | `MigrationTo4Test.kt` | Room-Migration-Test mit `MigrationTestHelper` — `inserted_at`-Spalte existiert nach Migration, alte COMPLETED-Rows haben `inserted_at = created_at`. |
| 3 | `SessionDaoTest.kt` (erweitert) | `findPendingInsertion`, `markInserted`, `deleteInsertedOlderThan` — alle 3 neuen Queries. |

**Test-Doubles:**
- `FakePipelineService` — implementiert das gleiche Interface wie `DictatePipelineService.LocalBinder`, hält in-memory `MutableStateFlow<DictateUiState>`. Ermöglicht IME-Tests ohne Robolectric-Service.
- `FakePipelineRunner` — existiert bereits (`JobExecutorTest`-Pattern). Bleibt unverändert.
- `FakeAudioFocusGate.kt` (existiert) — bleibt; wird vom PipelineStateManager direkt konsumiert.

### §11.3 Bound-Service-Setup

#### §11.3.1 Verbindungs-Lifecycle

**Strategie: Service starten + binden in `onCreateInputView`** (NICHT bei erstem Recording-Click — siehe Begründung unten).

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

**Begründung:** Die Tastatur erscheint immer, bevor der User irgendeinen Button klickt. Wenn wir erst bei Recording-Click starten, würde der erste Click eine ~50-200ms-Latenz haben (Service-onCreate + Bind), spürbar lag. In `onCreateInputView` haben wir Zeitreserve.

**Alternative (verworfen):** Service-Start bei erstem Recording-Click. Verworfen wegen Latenz.

**Unbind-Lifecycle:**

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

**Service-Self-Stop:** wenn `state.isAllTerminal()` → `stopSelf()` (siehe §7.4). Der IME-Service-Bind hält den Service NICHT künstlich am Leben, weil nach `stopSelf` und `unbindService` der Service tatsächlich stirbt — der Bind-Counter ist die letzte Referenz nach `stopSelf`. Wenn der User die Tastatur nicht zugemacht hat, hält der Bind den Service zwar weiter — das ist OK, weil dann `state.isAllTerminal()` zwischenzeitlich false werden kann (neues Recording).

#### §11.3.2 ServiceConnection-Edge-Cases

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

#### §11.3.3 Race: IME-onCreate vor Service-onCreate

Da `DictatePipelineService` im selben Process läuft (D1), gibt es keinen echten "Service noch nicht da"-Race. Reihenfolge:

1. IME-Service `onCreateInputView` → `startForegroundService(intent)` → returned sofort, scheduled service.
2. Android scheduled `DictatePipelineService.onCreate()` auf dem Main-Thread → läuft synchron.
3. IME-Service ruft direkt im Anschluss `bindService(intent, conn, BIND_AUTO_CREATE)` → sieht den frisch erzeugten Service, ruft `Service.onBind()` synchron, postet `ServiceConnection.onServiceConnected` auf dem Main-Looper.
4. IME-Service kehrt aus `onCreateInputView` zurück (Inflate fertig).
5. `onServiceConnected` läuft als nächste Main-Looper-Message.

**Edge-Case:** zwischen Schritt 4 und 5 kann der User KEINEN Button klicken (Touch-Events laufen auf demselben Main-Looper), also kein UI-Race möglich.

**Tatsächlicher Race:** wenn beim allerersten Start die `recoverFromDb()`-Coroutine noch läuft, sieht der erste `state.collect`-Sub die initiale `_state`-Emission ohne `pendingSessions`. Sobald `recoverFromDb` fertig ist, kommt eine zweite Emission mit den geladenen Sessions. Das ist OK — der Subscriber re-rendert.

### §11.4 DB-Migration

#### §11.4.1 Migration-Skript für Room

Siehe §6.1 — vollständige `MigrationTo4.kt`-Datei spezifiziert.

#### §11.4.2 Tests gegen Migration-Test-Helper

**Neue Datei:** `app/src/androidTest/java/net/devemperor/dictate/database/migration/MigrationTo4Test.kt`

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

    companion object { private const val TEST_DB = "migration-test" }
}
```

#### §11.4.3 Edge-Cases bei Migration-Failure

`Room.databaseBuilder` heute (siehe `DictateDatabase.kt:67-103`) hat KEIN `fallbackToDestructiveMigration` — wenn die Migration fehlschlägt, crasht die App beim ersten DB-Zugriff mit `IllegalStateException`. Das ist die richtige Strategie für unser Setting (wir wollen User-Daten nicht verlieren).

**Falls die Migration in der Wildbahn fehlschlägt:** Crash + automatischer Bug-Report. User-Daten bleiben in der alten DB-Datei intakt; die App ist unbenutzbar bis ein Hotfix nachgereicht wird. Dies ist akzeptabel, weil die Migration `ALTER TABLE ADD COLUMN` ist — kann praktisch nicht fehlschlagen (außer bei Disk-Full oder Korruption).

### §11.5 Notification-UX

#### §11.5.1 POST_NOTIFICATIONS-Runtime-Permission (Android 13+)

```java
// In DictateInputMethodService.java oder Settings-Activity:
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
        // IME kann keine Permission-Dialoge zeigen — daher in der Settings-Activity nachfragen.
        // In der IME selber gilt: WENN Permission fehlt, läuft der Service weiterhin (FGS bleibt aktiv),
        // aber die Notification wird vom System unterdrückt — Service-Start funktioniert dennoch.
    }
}
```

**Begründung:** IME-Services dürfen aus UX-Gründen keine `requestPermissions`-Dialoge zeigen (das System würde den Dialog unter der Tastatur rendern). Stattdessen prompten wir in der Settings-Activity und Onboarding (`OnboardingActivity` heute existiert — `app/src/main/java/net/devemperor/dictate/onboarding/OnboardingActivity.java`-Klasse, im Manifest `:53`).

#### §11.5.2 MediaStyle vs. Default — Entscheidung

Wir nutzen **NotificationCompat-Default-Style** (kein MediaStyle). Begründung:
- MediaStyle ist auf Audio-Playback ausgelegt (Album-Cover, Track-Titel, Skip/Prev) — passt nicht für Pipeline-Status.
- Default-Style mit 3 Action-Buttons reicht für unsere States (Pause/Stop/Send bzw. Insert/Discard).
- Compact-Style zeigt max. 3 Actions → wir bleiben unter dem Limit.

#### §11.5.3 Klick-auf-Notification-Verhalten

**Ziel:** Der User klickt auf die Notification → die Tastatur-App öffnet sich auf der `DictateSettingsActivity` (Hauptactivity).

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

**Limitation:** Eine IME kann sich NICHT durch eine Notification-Click selbst sichtbar machen (kein Programmatic-Show-IME); wir können nur die App-Activity öffnen. Akzeptiert.

### §11.6 OOM-Death-Recovery

#### §11.6.1 recoverFromDb-Aufruf-Strategie

**Asynchron im scope** (NICHT synchron in `onCreate`), weil DB-IO blocken könnte und wir die 5-Sekunden-FGS-Frist nicht riskieren wollen (§11.1.4).

```kotlin
override fun onCreate() {
    super.onCreate()
    ensureNotificationChannel()
    stateManager = PipelineStateManager(scope, db, jobExecutor)
    startForegroundCompat(stateManager.state.value)   // sync, instant
    scope.launch(Dispatchers.IO) {
        stateManager.recoverFromDb()                    // async, kann 100-500ms dauern
    }
}
```

#### §11.6.2 Audio-Files, die nicht mehr existieren

Bei `recoverFromDb` werden alle `final_output_text != NULL AND inserted_at IS NULL`-Sessions geladen (§6.1). Audio-Files SIND für Insertion nicht mehr nötig (das Result-Text ist bereits in der DB). Daher: kein File-Existence-Check nötig — `pendingSessions` enthält nur den fertigen Text + `sessionId`. Insertion erfolgt aus dem DB-Text.

**Edge-Case:** `recoverFromDb` lädt auch RECORDED-Sessions (Audio aufgenommen, aber keine Pipeline mehr gelaufen — Crash mitten im Recording-Stop). Dafür gilt:

```kotlin
suspend fun recoverFromDb() = withContext(Dispatchers.IO) {
    val pending = db.sessionDao().findPendingInsertion()
        .map { it.toPendingSession() }
    val orphanedRecorded = db.sessionDao().getByStatus("RECORDED")
        .filter { it.audioFilePath != null && File(it.audioFilePath).exists() }
        .map { it.toPendingSession() }
    _state.update { it.copy(pendingSessions = pending + orphanedRecorded) }
}
```

Sessions mit `audio_file_path != null` aber Datei existiert nicht (Cache-Cleanup nach App-Update) werden gefiltert + DB-cleanup als opportunistic side-effect:

```kotlin
val ghostSessions = db.sessionDao().getByStatus("RECORDED")
    .filter { it.audioFilePath != null && !File(it.audioFilePath).exists() }
ghostSessions.forEach { 
    db.sessionDao().updateStatus(it.id, SessionStatus.FAILED.name)
    db.sessionDao().updateError(it.id, "UNKNOWN", "audio file vanished")
}
```

#### §11.6.3 User-Kommunikation "Session verloren"

Bei jeder Ghost-Session (siehe oben) wird `lastErrorType=UNKNOWN, lastErrorMessage="audio file vanished"` gesetzt. Die History-Activity (`HistoryActivity`/`HistoryAdapter.java:99-156`) zeigt FAILED-Sessions bereits mit Error-Icon an. Keine zusätzliche UI nötig. Akzeptiert per User-Wahl D4 (kein Auto-Resume).

### §11.7 Migrations-Reihenfolge & Risiko

#### §11.7.1 Bestehende Tests, die brechen

| Test | Block | Bruchgrund | Mitigation |
|---|---|---|---|
| `RecordingStateControllerTest.kt` | 1 | Klasse wird gelöscht | Auf `PipelineStateManagerTest` umschreiben, gleiche Assertions auf `manager.state.value.recording` |
| `MultiCallbackForwardingTest.kt` | 1 | Callback-Pattern verschwindet | Test wird auf `state.collect`-Subscriber umgebaut |
| `JobExecutorTest.kt` | (keiner) | unverändert | — |
| `ActiveJobRegistryTest.kt` | (keiner) | unverändert | — |
| `LanguageControllerTest.kt` | (keiner) | unverändert | — |

#### §11.7.2 Neue Tests, die nötig sind

Siehe §11.2.3 — Tabelle pro Block.

#### §11.7.3 Test-Fakes

| Fake | Datei | Block | Zweck |
|---|---|---|---|
| `FakePipelineService` (LocalBinder-Stub) | `app/src/test/java/.../testutil/FakePipelineService.kt` (NEU) | 2 | IME-Tests ohne Robolectric-Service |
| `FakeJobExecutor` | (existiert via `PipelineRunner`-Interface in `JobExecutor.kt:332`) | (keiner) | bereits vorhanden |
| `FakeAudioFocusGate` | `app/src/test/java/.../core/FakeAudioFocusGate.kt` (existiert) | 1 | bereits vorhanden |
| `FakeSessionDao` | `app/src/test/java/.../testutil/FakeSessionDao.kt` (NEU) | 3 | PipelineStateManager-Tests ohne Room |

---

## §12 Referenzen

### Phase-2-Recherchen (Eingangs-Material)

- [_pending-ime-lifecycle-view-recreation.md](../_pending-ime-lifecycle-view-recreation/_pending-ime-lifecycle-view-recreation.md) — bestätigt: KEINE Coroutinen im IME-Service heute, KEINE WorkManager-Dependency.
- [_pending-persistence-background-architecture.md](../_pending-persistence-background-architecture/_pending-persistence-background-architecture.md) — Room v3 Stand, sessions-Tabelle, RECORDED-Status, JobExecutor + ActiveJobRegistry.
- [_pending-state-machine-visibility-owners.md](../_pending-state-machine-visibility-owners/_pending-state-machine-visibility-owners.md) — vollständige Visibility-Mutation-Map, identifiziert die 5 problematischen resend_btn-Stellen.

### Code-Pointer (Heute)

- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`
- `app/src/main/java/net/devemperor/dictate/core/RecordingStateController.kt`
- `app/src/main/java/net/devemperor/dictate/core/RecordingState.kt`
- `app/src/main/java/net/devemperor/dictate/core/KeyboardUiController.kt`
- `app/src/main/java/net/devemperor/dictate/core/PipelineUiState.kt`
- `app/src/main/java/net/devemperor/dictate/core/KeyboardStateManager.kt`
- `app/src/main/java/net/devemperor/dictate/core/RecordingUiController.kt` — Site der heutigen `resendButton`-Mutationen (siehe §13.1).
- `app/src/main/java/net/devemperor/dictate/core/InfoBarController.kt` — view-lokaler infoCl-Owner (siehe §13.1 Eintrag 17).
- `app/src/main/java/net/devemperor/dictate/core/MainButtonsController.kt` — overlay-character-Mutationen (§13.1 Eintrag 18).
- `app/src/main/java/net/devemperor/dictate/core/JobExecutor.kt` (verbleibt, wird vom PipelineService-Code genutzt)
- `app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistry.kt` (verbleibt)
- `app/src/main/java/net/devemperor/dictate/keyboard/EnterOverlayHandler.kt:56,62` — overlay-Long-Press-Mutationen (§13.1 Eintrag 19).
- `app/src/main/AndroidManifest.xml:5-9, :29-40` — heutige Permissions + IME-Service-Eintrag.
- `app/build.gradle:8-19` — `compileSdkVersion 36`, `minSdk 26`, `targetSdk 35` (relevant für FGS-Type-Pflicht ab 34).
- `app/src/main/java/net/devemperor/dictate/database/DictateDatabase.kt:38, :73` — heutige `version = 3` und `addMigrations(...)`-Wiring.
- `app/src/main/java/net/devemperor/dictate/database/migration/Migrations.kt` — MIGRATION_1_2.
- `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo3.kt` — MIGRATION_2_3 (Vorbild-Format für M3→M4).
- `app/src/main/java/net/devemperor/dictate/database/dao/SessionDao.kt` — heutige Queries; M4 erweitert um `markInserted/findPendingInsertion/deleteInsertedOlderThan`.
- `app/src/main/java/net/devemperor/dictate/database/entity/SessionEntity.kt` — heutige Felder; M4 fügt `inserted_at` hinzu.

### Externe Referenzen

- Foreground Service Lifecycle: https://developer.android.com/develop/background-work/services/foreground-services
- Bound Services: https://developer.android.com/develop/background-work/services/bound-services
- Room Migrations: https://developer.android.com/training/data-storage/room/migrating-db-versions
- FGS-Types ab Android 14: https://developer.android.com/about/versions/14/changes/fgs-types-required
- POST_NOTIFICATIONS Runtime-Permission: https://developer.android.com/develop/ui/views/notifications/notification-permission

---

## §13 Vollständigkeits-Verifikation

> Diese Sektion ist die explizite Antwort auf die User-Forderung *"vollständige Zentralisierung von State und Funktionalität, mit konsequenter SOLID/DRY-Anwendung"*. Sie verifiziert, dass der Refactor alle heutigen verstreuten Mutations adressiert und keine neuen Duplikate einführt.

### §13.1 Visibility-Mutation-Audit

Quelle: `grep -rn "\.\(visibility\|setVisibility\)" app/src/main/java/net/devemperor/dictate/` mit Filter auf das `core/`-Package + `keyboard/EnterOverlayHandler.kt` (alle anderen Treffer in `usage/`, `settings/`, `history/`, `rewording/` sind Settings-/History-UIs außerhalb des IME-Refactors und bleiben unverändert).

| # | file:line | View | Status nach Refactor |
|---|---|---|---|
| 1 | `RecordingUiController.kt:137` | `resendButton` | **WANDERT IN PREDICATE** (LayoutCatalog `RESEND`-Slot, §9.4) |
| 2 | `RecordingUiController.kt:158` | `resendButton` | **WANDERT IN PREDICATE** (LayoutCatalog `RESEND`-Slot, §9.4) |
| 3 | `DictateInputMethodService.java:1345` | `resendButton` | **WANDERT IN PREDICATE** (LayoutCatalog `RESEND`-Slot, §9.4) |
| 4 | `DictateInputMethodService.java:1347` | `resendButton` | **WANDERT IN PREDICATE** (LayoutCatalog `RESEND`-Slot, §9.4) |
| 5 | `DictateInputMethodService.java:1669` | `resendButton` | **WANDERT IN PREDICATE** (LayoutCatalog `RESEND`-Slot, §9.4) |
| 6 | `DictateInputMethodService.java:1839` | `resendButton` | **WANDERT IN PREDICATE** (LayoutCatalog `RESEND`-Slot, §9.4) |
| 7 | `KeyboardStateManager.kt:162` | `overlayCharactersLl` | **WANDERT IN PREDICATE** (LayoutCatalog `OVERLAY_CHARS`-Slot, ungated) — `applyVisibility` löscht der Spec-2-Refactor; Default-GONE bleibt im LayoutCatalog. |
| 8 | `KeyboardStateManager.kt:172-180` | `mainButtonsClTyped`, `editButtonsLl`, `qwertzContainer`, `emojiPickerCl` (4 Sites) | **WANDERN** in `LayoutCatalog.forKeyboard(state)` als ContentArea-Achsen-Resolver (Spec 2 §8.5). |
| 9 | `KeyboardStateManager.kt:187` | `pauseButton` | **WANDERT IN PREDICATE** (LayoutCatalog `PAUSE`-Slot, Spec 2 §3.2). |
| 10 | `KeyboardStateManager.kt:191` | `trashButton` | **WANDERT IN PREDICATE** (LayoutCatalog `TRASH`-Slot, Spec 2 §3.2). |
| 11 | `KeyboardStateManager.kt:206` | `promptsCl` | **WANDERT IN PREDICATE** (LayoutCatalog `PROMPTS_CONTAINER`-Slot). |
| 12 | `KeyboardStateManager.kt:210` | `promptsRv` | **WANDERT IN PREDICATE** (LayoutCatalog `PROMPTS_LIST`-Slot). |
| 13 | `KeyboardStateManager.kt:212` | `pipelineProgressLl` | **WANDERT IN PREDICATE** (LayoutCatalog `PIPELINE_PROGRESS`-Slot). |
| 14 | `KeyboardStateManager.kt:218` | `promptRecordingControlsLl` | **WANDERT IN PREDICATE** (LayoutCatalog `PROMPT_RECORDING_CTL`-Slot). |
| 15 | `KeyboardUiController.kt:241` | `views.infoCl` | **BLEIBT** — view-lokale Kontrolle innerhalb `startPipeline`; aber semantisch verschiebt sich die Mutation von hier in den `InfoBarController` als Subscriber-driven Reaktion auf `state.pipeline → Running`. Begründet erhalten in Spec 2 §9.5. |
| 16 | `KeyboardUiController.kt:383, :384, :388, :421, :422, :426, :447, :448` (8 Sites) | `binding.iconTv`, `binding.pb`, `binding.durationTv` (innerhalb `addRunningStep / completeStep / failStep`) | **BLEIBT** — Pure View-Lokal-Logik innerhalb der `pipeline_step_row`-Dynamik. Diese Visibility-Mutationen sind View-internal (nicht state-relevant) und bilden keinen Cross-Component-Race. Begründet erhalten. |
| 17 | `InfoBarController.kt:49, :57, :58, :65, :78, :94, :111, :116, :131, :139, :145, :154, :163` (13 Sites) | `infoCl`, `infoYesButton`, `infoNoButton` | **BLEIBT** — `InfoBarController` ist ein gekapselter View-Owner (siehe `InfoBarController.kt:25-46`). Mutations sind view-lokal innerhalb seiner Verantwortung. Aufrufe von außen erfolgen nur via `dismiss()` und `showInfo(type)`-Public-API, nicht via direkter Visibility-Mutation. |
| 18 | `MainButtonsController.kt:251, :485, :487` | `views.overlayCharactersLl`, `charView` (overlay-character-Item) | **WANDERT TEILWEISE** — `:251` (overlay-Anzeigen bei Long-Press) wandert konzeptuell in den `OVERLAY_CHARS`-Slot. `:485, :487` (per-character-View innerhalb der overlay-Liste) bleiben view-lokal — Iteration über dynamisch erzeugte Char-Views, nicht state-relevant. |
| 19 | `EnterOverlayHandler.kt:56, :62` | `overlayCharactersLl` | **WANDERT IN PREDICATE** — die Long-Press-Overlay-Anzeige für Enter-Key gehört in den `OVERLAY_CHARS`-Slot, gleicher LayoutCatalog-Punkt wie `MainButtonsController.kt:251`. |

**Verifikation:** alle 14 Mutations-Sites, die State-driven sind (Zeilen 1-14, 18, 19 in der Tabelle), wandern in einen LayoutCatalog-Slot mit Predicate. Die 21 Sites, die view-lokal sind (Zeilen 15-17 + die 8 `KeyboardUiController`-Sites in Zeile 16), bleiben mit klarer Begründung erhalten. **Keine state-driven Visibility-Mutation ist im Refactor-Plan unaddressed.**

### §13.2 State-Mutation-Audit

#### §13.2.1 Direkte State-Field-Mutationen heute

| # | file:line | Code | Klasse | Wandert nach? |
|---|---|---|---|---|
| 1 | `RecordingStateController.kt:106-107` | `var state: RecordingState = Idle; private set` | `RecordingStateController` | `PipelineStateManager._state` (DictateUiState.recording) — JA |
| 2 | `RecordingStateController.kt:110` | `private var audioFile: File?` | `RecordingStateController` | bleibt eingekapselt im PipelineStateManager — JA |
| 3 | `RecordingStateController.kt:111` | `private var audioFocusEnabled: Boolean` | `RecordingStateController` | `DictateUiState.audioFocusEnabled` — JA |
| 4 | `RecordingStateController.kt:355` | `state = newState` (in `setState`) | `RecordingStateController` | `_state.update { it.copy(recording = newState) }` — JA |
| 5 | `KeyboardUiController.kt:63-65` | `override var state: PipelineUiState = Idle; private set` | `KeyboardUiController` | `DictateUiState.pipeline` — JA |
| 6 | `KeyboardUiController.kt:67` | `private var config: AutoEnterConfig?` | `KeyboardUiController` | wird Member von `DictateUiState.pipeline` (`Running.autoEnterActive`) — JA |
| 7 | `KeyboardUiController.kt:118-119` | `pipelineTotalTimer`, `latestPipelineElapsedMs` | `KeyboardUiController` | bleibt View-lokal (Timer ist View-Display-Detail, kein State) — Akzeptiert |
| 8 | `KeyboardUiController.kt:133-136` | `stepRows`, `totalSteps`, `currentStep`, `activeTimer` | `KeyboardUiController` | bleibt View-lokal — Akzeptiert |
| 9 | `KeyboardUiController.kt:149` | `state = newState` (in `updateDictateUiState`) | `KeyboardUiController` | `_state.update { it.copy(pipeline = newState) }` — JA |
| 10 | `KeyboardStateManager.kt:100` | `var contentArea: ContentArea = MAIN_BUTTONS; private set` | `KeyboardStateManager` | `DictateUiState.contentArea` — JA |
| 11 | `KeyboardStateManager.kt:102` | `var isSmallMode: Boolean = false; private set` | `KeyboardStateManager` | `DictateUiState.smallMode` — JA |
| 12 | `KeyboardStateManager.kt:136-137` | `contentArea = area` (in `setContentArea`) | `KeyboardStateManager` | `_state.update { it.copy(contentArea = area) }` — JA |
| 13 | `KeyboardStateManager.kt:141-145` | `isSmallMode = enabled; contentArea = MAIN_BUTTONS` (in `setSmallMode`) | `KeyboardStateManager` | atomare `_state.update` mit beiden Feldern in einer Emission — JA, eliminiert das Coupled-Mutation-Problem (heute zwei sequenzielle Schreiben, künftig atomar) |
| 14 | `KeyboardStateManager.kt:113-117` | `private var layoutModeController: ...` | `KeyboardStateManager` | entfällt komplett (Klasse wird gelöscht in Spec 2) — JA |
| 15 | `KeyboardUiController.kt:138` | `private var savedRecordButtonTextColors` | `KeyboardUiController` | bleibt View-lokal — Akzeptiert |
| 16 | `DictateInputMethodService.java:111-122` | div. Service-Felder (`livePrompt`, `pendingLivePromptChain`, `vibrationEnabled`, `autoSwitchKeyboard`, `restoreAutoEnter`, `restoreReprocessStaging`) | Service | `livePrompt` + `pendingLivePromptChain` + `autoSwitchKeyboard` wandern in `DictateUiState.pipeline.Running.livePrompt` etc. (oder bleiben Pipeline-Job-internal). `restoreAutoEnter` / `restoreReprocessStaging` — view-recreate-bridges entfallen, weil State-SSOT übersteht View-Recreate (Service-Lifecycle länger als IME-View). — JA |
| 17 | `ActiveJobRegistry.kt:28-31` | `MutableStateFlow<Map<String, JobState>>` | `ActiveJobRegistry` | bleibt unverändert (Job-Tracking ist orthogonal zu UI-State) — Akzeptiert |
| 18 | `JobExecutor.kt:36-54` | `activeToken`, `activeThread`, `orchestrator` | `JobExecutor` (object) | bleibt unverändert — Akzeptiert |

**Verifikation:** alle 11 UI-state-relevanten Mutations-Sites (Zeilen 1, 3, 4, 5, 6, 9, 10, 11, 12, 13, 16) wandern in `PipelineStateManager._state.update`. **Die heutigen 3 unabhängigen State-Halter** (RecordingStateController + KeyboardUiController + KeyboardStateManager) **werden zu einem einzigen** PipelineStateManager — single source of truth.

Die 7 view-lokalen Felder (Zeilen 2, 7, 8, 14, 15, 17, 18) bleiben begründet view-lokal: View-Display-Detail, Job-Tracking, oder Klasse wird komplett gelöscht.

#### §13.2.2 SP-Reads mit State-Charakter

Einige UI-State-Achsen werden heute on-demand aus `SharedPreferences` gelesen statt im State gehalten:

| Pref-Key | Heute gelesen in | Wandert in `DictateUiState`? |
|---|---|---|
| `Pref.SmallMode` | `DictateInputMethodService.java:1025, :1402, :2632, :2634` | NEIN — bleibt Pref, aber `DictateUiState.smallMode` spiegelt den Wert. SP-Listener triggert State-Update. |
| `Pref.SingleRowMode` | `DictateInputMethodService.java:2652-2654`; `KeyboardLayoutModeController.kt:100, :151` | NEIN — bleibt Pref, `DictateUiState.singleRowMode` spiegelt. |
| `Pref.AudioFocus` | `DictateInputMethodService.java:580, :664, :2671-2674`; `RecordingStateController.kt:194` | NEIN — bleibt Pref, `DictateUiState.audioFocusEnabled` spiegelt. |
| `Pref.ResendButton` | `DictateInputMethodService.java:1344, :1694` | NEIN — bleibt Pref, `DictateUiState.resendEnabled` spiegelt. |
| `Pref.LastFileName` | `DictateInputMethodService.java:1343, :1408, :1613, :1693` | NEIN — Cache-File-Tracking bleibt Pref-driven. `DictateUiState.lastAudioExists` spiegelt File-Existence. |
| `Pref.Animations` | `DictateInputMethodService.java:611, :1399`; `KeyboardLayoutModeController.kt:123, :453` | NEIN — bleibt Pref. `DictateUiState.animationsEnabled` ist redundant für UI-Resolver, aber konsistent gespiegelt. |
| `Pref.AutoEnter` | `DictateInputMethodService.java:1010, :1679, :1764-1766, :1891, :2532` | TEILWEISE — initial-Wert kommt aus Pref, runtime-toggle ist `PipelineUiState.Running.autoEnterActive` |
| `Pref.OverlayPositionPortraitX/Y` | NEU (OPEN-3) | JA — `DictateUiState.overlayPositionPortraitX/Y` spiegelt. Schreib-Trigger: `updateOverlayPosition(portrait=true, ...)` aus `OverlayBackend` (Spec 3 §11.5). |
| `Pref.OverlayPositionLandscapeX/Y` | NEU (OPEN-3) | JA — `DictateUiState.overlayPositionLandscapeX/Y` spiegelt. Schreib-Trigger: `updateOverlayPosition(portrait=false, ...)` aus `OverlayBackend` (Spec 3 §11.5). |

**Spiegelung-Pattern:** im `PipelineStateManager.onCreate` werden alle relevanten Prefs gelesen und in `_state.value` initialisiert. Ein `SharedPreferences.OnSharedPreferenceChangeListener` triggert `_state.update`-Calls, sodass Settings-Activity-Writes reaktiv im IME ankommen.

```kotlin
init {
    val initial = DictateUiState(
        recording = RecordingState.Idle,
        pipeline = PipelineUiState.Idle,
        singleRowMode = sp.get(Pref.SingleRowMode),
        smallMode = sp.get(Pref.SmallMode),
        audioFocusEnabled = sp.get(Pref.AudioFocus),
        resendEnabled = sp.get(Pref.ResendButton),
        animationsEnabled = sp.get(Pref.Animations),
        overlayPositionPortraitX = sp.getFloat(Pref.OverlayPositionPortraitX, 1.0f),
        overlayPositionPortraitY = sp.getFloat(Pref.OverlayPositionPortraitY, 0.1f),
        overlayPositionLandscapeX = sp.getFloat(Pref.OverlayPositionLandscapeX, 1.0f),
        overlayPositionLandscapeY = sp.getFloat(Pref.OverlayPositionLandscapeY, 0.1f),
        // ...
    )
    _state.value = initial
    sp.registerOnSharedPreferenceChangeListener(prefListener)
}

private val prefListener = OnSharedPreferenceChangeListener { _, key ->
    _state.update { current -> current.copy(
        singleRowMode = if (key == Pref.SingleRowMode.key) sp.get(Pref.SingleRowMode) else current.singleRowMode,
        smallMode = if (key == Pref.SmallMode.key) sp.get(Pref.SmallMode) else current.smallMode,
        // ...
    )}
}
```

#### §13.2.3 Neue State-Mutation (OPEN-3)

| # | Methode | Schreib-Effekt | Trigger-Ort |
|---|---|---|---|
| 1 | `PipelineStateManager.updateOverlayPosition(portrait, x, y)` | `_state.update { copy(overlayPosition{Portrait\|Landscape}{X\|Y} = ...) }` + Pref-Write (atomar via `apply()`) | `OverlayBackend.OnTouchListener#onUp` (Drag-End, Spec 3 §11.5). Nur emittiert wenn Move-Distance > Threshold (8dp). |

**Verifikation:** Die einzige neue State-Mutation in OPEN-3 läuft durch
`PipelineStateManager` — kein direkter Pref-Write aus `OverlayBackend`, kein
direkter View-Mutation auf das Overlay-Window vom Settings-Screen. Damit bleibt
das State-SSOT-Invariant unverletzt: ALLE Mutations gehen durch den Manager.

### §13.3 SOLID-Verifikation pro neue Klasse

> **Iteration 2026-05-09/10 (F-8, F-10, F-11):** Diese Sektion wurde
> grundlegend überarbeitet. Frühere Audits prüften den `PipelineStateManager`
> als Composition Root mit typed Action-Methoden. Mit dem Modular-Orchestrator-
> Pattern (F-11) ist die zentrale Klasse jetzt der `DictateOrchestrator`, der
> nur das `DictateModule`-Interface kennt; Action-Logik wandert in 13 Module
> (siehe §15). Audit ist entsprechend pro Schicht strukturiert.

#### §13.3.1 DictatePipelineService (verkleinert nach F-3, unverändert)

- **SRP** — Verantwortung: *Process-Lifecycle-Owner*. Konkret: FGS-Lifecycle (`startForeground` / `stopSelf`), Bind-Connection, JobExecutor-Init (G7). Notification-Build delegiert an `PipelineNotificationCoordinator`, Action-Routing an `PipelineActionRouter`, State-Mutation an `DictateOrchestrator`. Service hat exakt diese drei Helper-Felder + Lifecycle-Hooks.
- **OCP** — Neue Notification-Inhalte gehen in den Coordinator; neue Action-Strings gehen in den Router. Service-Klasse selbst bleibt invariant.
- **LSP** — kein Vererbungs-Hierarchien-Issue (erbt von `Service` direkt; `LocalBinder` ist innere Klasse).
- **ISP** — `LocalBinder` exponiert nur `state` + `dispatch(action)` + 2 Lifecycle-Hooks. Minimal, keine typed Forwarder mehr (F-8).
- **DIP** — Service konstruiert in `onCreate` Helper-Klassen über deren Konstruktoren; alle Helper hängen an Interfaces.

#### §13.3.2 DictateOrchestrator (F-11, ehemals PipelineStateManager)

- **SRP** — Verantwortung: *Action-Routing + Cross-Module-Cascade-Dispatch*. Kennt KEINE konkreten Module (nur das `DictateModule`-Interface), KEINE Hardware-Calls, KEINE State-Logik. Pures Routing + Composition.
- **OCP** — Neues Modul = neue Modul-Datei + Eintrag in `DictateModuleRegistry.all`. Orchestrator-Code unverändert. Höchster OCP-Score aller bisher geprüften Klassen.
- **LSP** — Module sind polymorph austauschbar. Tests können `FakeRecordingModule` injizieren.
- **ISP** — `state` (read) + `dispatch` (write) + `shutdown` — minimal.
- **DIP** — Konstruktor hängt an `DictateModule<*, *, *>`-Liste, `DictateUiStateStore`, `ModuleServicesFactory`, `PipelinePrefMirror`, `PipelineRecovery` — alles Interfaces oder thin Container. Keine Konkretisierungen.

#### §13.3.2b LocalBinder (F-8)

- **SRP** — bound-IPC-Schicht: nur `state` + `dispatch` + 2 Lifecycle-Forwarder. Kein Verhalten.
- **OCP** — Neue Action = neue sealed-class-Variante. LocalBinder unverändert.
- **DIP** — Hängt nur am `DictateOrchestrator`-Object (innere Klasse).

#### §13.3.3 DictateUiStateStore (F-1)

- **SRP** — Reine StateFlow-Verwaltung. Eine öffentliche Methode `update(reducer)` plus Read-Properties `state` und `snapshot`. Keine Action-Logik.
- **OCP** — Erweiterung erfolgt über neue Reducer-Funktionen, die in den Manager-Methoden gerufen werden — Store selbst bleibt invariant.
- **DIP** — Keine Dependencies; pures Wrapper über `MutableStateFlow`.

#### §13.3.4 ViewModeFsm (F-1, jetzt als ViewModeModule abgelöst — F-11)

> **Iteration 2026-05-09 (F-11):** Diese Klasse existiert nicht mehr eigenständig.
> Ihre Logik ist Teil des `ViewModeModule` (§15.1) — der Triangle-FSM-Reducer
> ist jetzt eine `reduce()`-Methode auf dem Modul, kein separates Pure-Function-
> object mehr. Die SOLID-Begründung gilt unverändert: Pure Function, keine
> Side-Effects, exhaustive `when`-Block, Truth-Table testbar.

#### §13.3.5 PipelinePrefMirror (F-1)

- **SRP** — kapselt das Pref-Spiegelungs-Pattern. Liest beim Attach + auf jede Pref-Änderung; mutiert ausschließlich den Store via `update { ... }`. Keine Action-Logik, kein FSM-Wissen.
- **OCP** — Neue Pref = neuer Branch im `sync(key)`-when + neue Spalte im `initialMirror`. Andere Klassen unberührt.
- **DIP** — Hängt an `SharedPreferences` (Android-API). Test-Doubles via `FakeSharedPreferences` möglich.

#### §13.3.6 PipelineRecovery (F-1)

- **SRP** — DB-Replay → Store. Eine `suspend fun recover(store)`, sonst nichts.
- **OCP** — Neue Recovery-Schritte (z.B. zusätzliche Tables) erweitern `recover()`. Andere Klassen unberührt.
- **DIP** — Hängt am `PipelineSessionRepo`-Interface (F-2). Testbar mit `FakePipelineSessionRepo`.

#### §13.3.7 PipelineNotificationCoordinator (F-3)

- **SRP** — State → Notification-Render + Subscription-Management mit Throttling. Keine Action-Logik, kein Lifecycle-Wissen außer Terminal-Detection.
- **OCP** — Neue Notification-Inhalte = neuer Branch in `build(state)`. Subscription-Mechanismus invariant.
- **DIP** — Hängt am `Service`-Context (für `NotificationManagerCompat`) und an `StateFlow<DictateUiState>`. Beides austauschbar in Tests via `Robolectric` oder `FakeNotificationManager`.

#### §13.3.8 PipelineActionRouter (F-3)

- **SRP** — pure Mapping `Intent.action → PipelineStateManager-Methode`. Keine UI-Logik, keine Notification-Build.
- **OCP** — Neue Action = neuer Branch im `dispatch(intent)`-when + neue Konstante in `companion`. Andere Klassen unberührt.
- **DIP** — Hängt am `PipelineStateManager`. Tests injizieren Mock-Manager und prüfen Methoden-Aufrufe.

#### §13.3.9 LocalBinder

> Siehe §13.3.2b für die aktualisierte F-8-Audit-Sektion (Single Dispatch).

#### §13.3.10 DictateUiState (Datenklasse, hierarchisch nach F-10)

- **SRP** — Reine immutable Daten. Top-Level-Container hält nur Sub-State-Klassen (`audio`, `layout`, `overlay`, `resend`, …) plus Hot-Path-FSMs (`recording`, `pipeline`, `viewMode`). Keine Logik außer triviale Helper-Properties.
- **OCP** — Neue State-Achse = neue Sub-State-Klasse + neues Feld in DictateUiState. Bestehende Subscriber kompilieren weiter (default-Wert oder neue Sub-State-Klasse mit Default-Werten). Sealed sub-classes (`RecordingState`, `PipelineUiState`, `ScoPhase`) erlauben Erweiterung über `when`-Exhaustivität.
- **LSP** — keine Vererbung außer durch sealed classes (geschlossene Hierarchien).
- **ISP** — Keine Interfaces; reines Daten-Modell.
- **DIP** — Keine Dependencies (pure Daten). Listen-Felder als `PersistentList<T>` für strukturelle Immutability (F-9).

#### §13.3.11 PipelineSessionRepo + PipelineRunner (F-2 / DIP, unverändert)

- **SRP** — Beide sind reine Interface-Definitionen (Daten-Repository bzw. Job-Runner). Konkretisierungen (`RoomPipelineSessionRepo`, `JobExecutor`) sind in eigenen Klassen.
- **OCP** — Erweiterung via Interface-Methoden; Konkretisierungen passen sich an.
- **LSP** — Test-Doubles (`FakePipelineSessionRepo`, `FakePipelineRunner`) substituieren Konkretisierungen 1:1.
- **ISP** — minimal: nur die Methoden, die der Orchestrator und Effect-Handler tatsächlich brauchen.
- **DIP** — *ist* die Abstraktion, an die der Orchestrator hängt. Vollständig erfüllt.

#### §13.3.12 DictateModule-Interface (F-11, Plugin-Kontrakt)

- **SRP** — Definiert den Plugin-Kontrakt. Selbst keine Logik; nur ein Interface mit fünf Pflicht-Methoden + einer optionalen Cross-Module-Hook-Methode.
- **OCP** — `sealed interface` mit `object`-Implementierungen pro Modul. Compile-Zeit-Hierarchie, exhaustive `when` möglich.
- **LSP** — Alle Module implementieren denselben Kontrakt mit eigenen Type-Parametern; polymorph austauschbar.
- **ISP** — Minimal: 5 Methoden + 1 optionale. Keine Methode, die ein Modul nicht braucht.
- **DIP** — Reines Interface. Konkretisierungen sind die 13 Module in §15.

#### §13.3.13 Modul-Implementierungen (F-11, am Beispiel RecordingModule)

- **SRP** — Pro Modul **eine** fachliche Domäne. Recording-Modul kennt: Recording-State, Recording-Actions, Recording-SideEffects, Recording-EffectHandler. Es kennt KEINE Pipeline-Logik, KEINE Audio-Logik. Cross-Module-Effekte gehen über `onCrossModuleStateChange` (Action-Cascade).
- **OCP** — Neue Recording-Action = neue Variante in `Action.RecordingAction` + neuer `when`-Branch in `reduce`. Andere Module unberührt. Compiler erzwingt Exhaustivität.
- **LSP** — Modul-Implementierungen sind durch Test-Fakes substituierbar (siehe Tests in §14).
- **ISP** — Modul exposiert nur das, was das `DictateModule`-Interface verlangt. Keine zusätzliche Public-API.
- **DIP** — `runEffect(effect, services)` hängt am `ModuleServices`-Container, der seinerseits an Subsystem-Interfaces hängt. Tests injizieren `FakeModuleServices`.

#### §13.3.14 DictateModuleRegistry (F-11)

- **SRP** — Zentrale Liste aller Module + Sanity-Check (eindeutige IDs, eindeutige actionClasses).
- **OCP** — Neues Modul = ein Eintrag in `all`. Init-Check fängt Doppel-Registrierungen.
- **DIP** — Reine Daten-Liste; keine Behavior.

#### §13.3.15 ModuleServices + ModuleServicesFactory (F-11)

- **SRP** — DI-Container für Subsystem-Hardware-Adapter. Factory liefert die Services lazy beim Service-onCreate.
- **OCP** — Neue Subsystem-Dependency = neues Feld in `ModuleServices`. Module, die es brauchen, lesen es; andere ignorieren es.
- **DIP** — Felder sind alle Interfaces oder Subsystem-Klassen mit eigenen Interfaces. Tests injizieren `FakeModuleServices` mit `FakeRecordingHardware` etc.

### §13.4 DRY-Verifikation

#### §13.4.1 Heute duplizierte Logik

| Duplikat | Heute (file:line × Anzahl) | Künftig (Ein-Stellen-Quelle) |
|---|---|---|
| **Resend-Visibility-Berechnung** | 6 Sites in 2 Dateien (siehe §13.1 Tabelle) | 1 Predicate im `LayoutCatalog.RESEND`-Slot |
| **`isRecordingOrPaused` / `isRecordingOrPaused() \|\| Preparing`-Checks** | `DictateInputMethodService.java:486, :757, :1066, :2228-2230, :2570-2571`, `KeyboardStateManager.kt:184, :196` | 1 Helper auf `DictateUiState`: `state.recording.isActiveOrPending` (extension property) |
| **`recordButton.text`-Set** | `RecordingUiController.kt:115, :144, :146` (Idle/Active) + `KeyboardUiController.kt:464-509` (Preparing/Running/Staging) | 1 Resolver `LayoutCatalog.RECORD.textResolver(state)` |
| **`recordButton.isEnabled`-Set** | `RecordingUiController.kt:117, :141, :145, :531`, `KeyboardUiController.kt:467, :472, :480, :495` | 1 Resolver `LayoutCatalog.RECORD.enabledResolver(state)` |
| **AudioFocus-on-Toggle-Reaktion** | `DictateInputMethodService.java:664-672` (SP-Listener) + `:2664-2687` (User-Toggle) | 1 Method `PipelineStateManager.toggleAudioFocus()` (sowohl SP-Listener als auch User-Click rufen die gleiche Methode) |
| **`Pref.SmallMode`-Apply** | `DictateInputMethodService.java:1025, :1402, :2632-2634` | 1 SP-Listener im `PipelineStateManager.init` (siehe §13.2.2-Snippet) |
| **`Pref.AudioFocus`-Apply** | `:580, :664, :2685` (3 Sites mit identischem `mainButtonsController.refreshAudioFocusIcon` Boilerplate) | 1 Subscriber: `state.collect { state -> mainButtonsController.refreshAudioFocusIcon(state.audioFocusEnabled) }` |
| **`getLastAudioFileExists()` File-Check** | `DictateInputMethodService.java:611-613, :1343-1344, :1693-1694` | 1 Method `PipelineStateManager.refreshLastAudioExists()`, gerufen bei `onCreateInputView` + nach Recording-Stop. Result als `state.lastAudioExists`. |

#### §13.4.2 Neue Duplikate, die im Plan nicht entstehen dürfen

| Risiko | Mitigation |
|---|---|
| `buildNotification` könnte Subtitle-/Action-Logik dupliziert mit `LayoutCatalog`-Resolvers haben | NICHT akzeptiert. Die `notifSubtitleFor(state)`-Funktion (§11.1.2) ist State-→-String-Mapping; sie nutzt KEINE View-Resolver, schreibt nur Notification-Strings. Klare Trennung Visual-IME-State (LayoutCatalog) vs. Notification-State. Wenn ein Refactor-Reviewer Code-Duplikation entdeckt: gemeinsamen Helper `state.toUserVisibleSummary()` extrahieren. |
| Service- und IME-Service haben beide ein `scope` (`CoroutineScope`) | Akzeptiert: zwei verschiedene Scopes mit verschiedenen Lifetimes. IME-`viewScope` wird beim View-Recreate gecancelt; Service-`serviceScope` lebt mit dem Service. Naming explizit: `viewScope` vs. `serviceScope` — kein versehentlicher Cross-Use. |
| Pref-Spiegelung in `DictateUiState` und Pref-Read in einzelnen Action-Methoden gleichzeitig | NICHT akzeptiert. **Regel:** sobald ein Pref in `DictateUiState` gespiegelt ist, lesen Action-Methoden NUR aus `_state.value.X`, nie direkt aus `sp`. Code-Review-Checkliste: `sp.get(Pref.SmallMode)` darf nur im `PipelineStateManager.init` und im `prefListener` vorkommen. |

### §13.5 Identified Gaps + Mitigations

| # | Gap | Schweregrad | Mitigation |
|---|---|---|---|
| G1 | `KeyboardUiController.kt:241` mutiert `views.infoCl.visibility = GONE` direkt in `startPipeline` — das ist eine state-getriggerte Mutation, die heute über die Hilfsklasse `InfoBarController.dismiss()` laufen sollte, aber direkt geht. | Mittel | In Block 1: Mutation-Site auf `infoBarController.dismiss()` umstellen — danach hat InfoBarController die alleinige Verantwortung über infoCl. |
| G2 | `DictateInputMethodService.java:2630-2636` (`onSmallModeToggled`) schreibt direkt in `Pref.SmallMode` UND ruft `stateManager.setSmallMode(newSmallMode)` — zwei Schritte, die in seltenen Fällen out-of-sync sein können (z.B. wenn ein anderer SP-Listener parallel das Pref liest, bevor der State-Update durchläuft). | Niedrig | In Block 1 ist mit dem DictateUiState-Pref-Spiegel-Pattern (§13.2.2) der State automatisch konsistent — der explizite `setSmallMode`-Call wird redundant und entfällt. |
| G3 | `DictateInputMethodService.java:914-958` (`recordingStateController.setCallback`) wird bei jedem `onCreateInputView` neu gesetzt → leak risk bei alter Callback. Das wird nur sicher, weil `RecordingStateController` einen einzelnen Callback-Slot hat (Z. 99-104). Im Multi-Subscriber-Pattern (`StateFlow.collect`) ist das auto-managed via `viewScope.cancel`. | Niedrig | Block-1-Migration eliminiert dieses Pattern. Subscriber wird beim View-Recreate via `viewScope.cancel()` automatisch detached. |
| G4 | `KeyboardUiController.callbacks: CopyOnWriteArrayList<PipelineUiCallback>` (Z. 82) — heute ein eigener Multi-Callback-Mechanismus. Im neuen Setup ist `StateFlow` der Multi-Subscriber-Mechanismus, der das ersetzt. | Niedrig | In Block 1 wird der Callback-List-Mechanismus zusammen mit `KeyboardUiController.state` entfernt — Subscriber gehen über `StateFlow.collect`. |
| G5 | Beim ersten Pipeline-Start werden 3 SP-Reads gleichzeitig benötigt (`Pref.LastFileName`, `Pref.ResendButton`, `Pref.AudioFocus`) — die Race-Window ist klein, aber existent. | Niedrig | Da die Prefs gespiegelt sind (§13.2.2), liest die Action-Methode aus `_state.value` (atomar). Race verschwindet. |
| G6 | Service-Death während aktivem Recording: `RecordingManager.stop()` wird nicht mehr gerufen → MediaRecorder bleibt im Native-Heap. | Mittel | `Service.onDestroy` ruft `stateManager.cancelPipeline()` → triggert `recordingManager.release()`. Bei Process-Kill greift Android's Cleanup. Akzeptiert. |
| G7 | `JobExecutor.initialize(orchestrator)` wird heute im IME-`onCreate` (Z. 389) gerufen — mit dem Service-Refactor muss das in den Service-onCreate. Wenn der IME-Service ohne den Pipeline-Service hochfährt (theoretisch nicht möglich, aber defensiv), ist `JobExecutor` un-initialisiert. | Niedrig | `bindService` hält den Service-Lifecycle ans IME — es gibt keine Lifecycle-Sequenz, in der IME ohne Pipeline-Service läuft, sobald die Bind-Connection steht. Falls aus Robustheits-Gründen nötig: defensiv-`null`-Check in JobExecutor + lazy-init beim ersten Job-Start. |

---

## §14 Open Questions

Bewusst offen gelassen, weil über bibliotheksspezifisches Wissen hinausgehend, das verifizierbar gegen Android-Docs fehlt:

1. **`MotionLayout`/`Transition`-Interaktion mit Foreground-Service-Notification-Updates** — wenn die Notification 60-mal pro Sekunde aktualisiert wird (z.B. Recording-Timer), throttled Android das? Empfehlung: Notification-Update nur bei semantischen State-Changes, nicht für Timer-Ticks (Subtitle bleibt "Aufnahme läuft" — keine Sekunden-Anzeige in der Notification).
2. **`startForeground` mit `FOREGROUND_SERVICE_TYPE_MICROPHONE` ohne aktive Recording** — Android 14 erlaubt das technisch beim FGS-Start (das System prüft Mikrofon-Permission, nicht aktive Nutzung). Verifikation auf einem Pixel-API-34-Device steht aus.
3. **Pre-Insertion-State-Survival nach Process-Death** — wenn der Process stirbt, NACHDEM `final_output_text` geschrieben aber BEVOR `inserted_at` gesetzt wurde, sieht der nächste `recoverFromDb` die Session als "pending insertion". Stimmt — aber: ist der zuletzt fokussierte InputConnection nach Process-Restart noch verfügbar? Vermutlich nein. **Konsequenz:** der User muss explizit auf "Einfügen" klicken und in einen neuen Input-Field tippen — das ist die D4-Wahl. Akzeptiert.

---

## §15 Modul-Inventar (F-11)

Die 13 Module (12 aktive + 1 Phase-2) sind in `app/src/main/java/net/devemperor/dictate/state/modules/` gruppiert. Pro Modul eine eigene Datei mit:
- **State-Sub-Klasse** (vom Modul verwaltet, im DictateUiState als Sub-Feld)
- **Module-Effect-Sub-Sealed-Interface** (Effect-Varianten dieses Moduls)
- **Reducer** (F1+F2 pure function)
- **EffectHandler** (Hardware-Calls)
- **Cross-Module-Observer** (optional, für Cascade-Trigger)

### §15.1 Modul-Übersicht

| # | Modul | Achse | F1+F2 nötig? | Cross-Module-Observer? |
|---|---|---|---|---|
| 1 | RecordingModule | recording (sealed RecordingState) | ✓ explizit | nein |
| 2 | PipelineModule | pipeline (sealed PipelineUiState) | ✓ explizit | ja (PipelineDone → Resend, LivePrompt) |
| 3 | AudioModule | audio (AudioState) | mittel | ja (AudioFocus-Loss → Recording.Pause; Recording.Preparing → AudioFocus-Request) |
| 4 | ViewModeModule | viewMode (enum) | F4-Subset (ehemals ViewModeFsm) | ja (Recording-Active+View-hidden → HOVER) |
| 5 | LayoutModule | contentArea, layout | trivial | nein |
| 6 | OverlayModule | overlay | trivial | nein |
| 7 | ResendModule | resend | mittel (Cooldown-Timer) | ja (Pipeline-Done → MarkLastAudio) |
| 8 | LivePromptModule | livePrompt | trivial | ja (Pipeline-Done → ChainNext) |
| 9 | LanguageModule | language | trivial | ja (Reprocess-Override → Language.Override) |
| 10 | FeatureToggleModule | features | trivial | nein |
| 11 | ThemingModule | theming | trivial | nein |
| 12 | PendingSessionsModule | pendingSessions | DB-Subscriber (kein Reducer) | nein |
| 13 | InterruptionModule (Phase 2) | interruption | mittel | ja (Anruf → Recording.Cancel) |

### §15.2 RecordingModule (Beispiel-Implementation, vollständig)

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
        data class AllocateMediaRecorder(val target: InsertionTarget, val useBluetooth: Boolean) : Effect
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

    override fun reduce(
        state: RecordingState,
        action: Action.RecordingAction,
        ctx: ReducerContext,
    ): TransitionResult<RecordingState, Effect>? = when (state) {
        is RecordingState.Idle -> when (action) {
            is Action.RecordingAction.StartRecording -> TransitionResult(
                nextState = RecordingState.Preparing(useBluetooth = ctx.audio.useBluetoothMic),
                sideEffects = listOf(
                    Effect.AllocateMediaRecorder(action.target, ctx.audio.useBluetoothMic),
                ),
            )
            else -> null    // F1: andere Actions in Idle nicht erlaubt
        }
        is RecordingState.Preparing -> when (action) {
            Action.RecordingAction.MediaRecorderReady -> TransitionResult(
                nextState = RecordingState.Active(useBluetooth = state.useBluetooth),
                sideEffects = listOf(Effect.StartTimer(0), Effect.StartAmplitudeStream, Effect.StartBorderGlow),
            )
            Action.RecordingAction.CancelRecording -> TransitionResult(
                nextState = RecordingState.Idle,
                sideEffects = listOf(Effect.ReleaseMediaRecorder),
            )
            else -> null
        }
        is RecordingState.Active -> when (action) {
            Action.RecordingAction.PauseRecording -> TransitionResult(
                nextState = RecordingState.Paused,
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
                sideEffects = listOfNotNull(
                    Effect.StopMediaRecorder, Effect.StopTimer,
                    Effect.StopBorderGlow, Effect.StopAmplitudeStream,
                    ctx.recordingAudioFile?.let { Effect.DeleteAudioFile(it) },
                ),
            )
            else -> null
        }
        is RecordingState.Paused -> when (action) {
            Action.RecordingAction.ResumeRecording -> TransitionResult(
                nextState = RecordingState.Active(useBluetooth = ctx.audio.useBluetoothMic),
                sideEffects = listOf(
                    Effect.ResumeMediaRecorder, Effect.ResumeTimer,
                    Effect.ResumeBorderGlow, Effect.StartAmplitudeStream,
                ),
            )
            Action.RecordingAction.StopRecording -> /* analog Active.Stop */ TODO()
            Action.RecordingAction.CancelRecording -> /* analog Active.Cancel */ TODO()
            else -> null
        }
    }

    override fun runEffect(effect: Effect, services: ModuleServices) = when (effect) {
        is Effect.AllocateMediaRecorder -> services.recordingHardware.allocate(effect.target, effect.useBluetooth)
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

    // Recording-Modul reagiert NICHT auf Änderungen in anderen Modulen.
    // Cross-Module-Effekte gegen Recording (z.B. Anruf → Cancel) emittieren ANDERE
    // Module ihre Trigger-Actions, die hierhin als RecordingAction.CancelRecording
    // ankommen.
}
```

### §15.3 AudioModule mit Cross-Module-Observer (Beispiel)

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

    /**
     * Cross-Module-Observer: AudioModule reagiert auf Recording-State-Änderungen.
     */
    override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> {
        val cascade = mutableListOf<Action>()

        // Recording → Preparing → AudioFocus + ggf. Bluetooth anfragen via Effect-Re-Dispatch
        // (eleganter als direkte Hardware-Calls hier — alles läuft durch Reducer)
        if (prev.recording is RecordingState.Idle && next.recording is RecordingState.Preparing) {
            // Effects werden im Recording-Modul ausgelöst, aber Audio-Effects passieren
            // durch direktes runEffect — alternativer Pfad: via emitAction(Action.X) eine
            // spezifische Audio-Action einleiten, die hier wieder reduziert wird.
        }

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

### §15.4 Hinzufügen eines neuen Moduls — Walkthrough

Beispiel: neues `InterruptionModule` (Phase 2). Sechs Schritte:

1. **Datei `modules/InterruptionModule.kt` anlegen** (analog §15.2)
2. **`ModuleId.Interruption`** in `DictateModule.kt` ergänzen
3. **`Action.InterruptionAction`** in `Action.kt` ergänzen
4. **`DictateUiState.interruption`** ist bereits Sub-State-Feld (default null)
5. **`DictateModuleRegistry.all`** um `InterruptionModule` erweitern
6. **`ModuleServices`** ggf. um neue Subsystem-Dependency erweitern (`telephonyListener: TelephonyListenerSubsystem`)

Das war's. Cross-Module-Wirkung (Anruf → Cancel-Recording) ist deklarativ in `onCrossModuleStateChange` — keine andere Datei wird angefasst.

### §15.5 Cross-Module-Effect-Modi

Drei Modi für Cross-Module-Wirkung:

| Modus | Mechanismus | Wann nutzen? |
|---|---|---|
| **1. Eigene SideEffect** | Reducer-Output enthält Hardware-Effect des eigenen Moduls | für Hardware/Pref, die zur eigenen State-Mutation gehören |
| **2. Action-Cascade** | `onCrossModuleStateChange` returns Liste von Actions, der Orchestrator dispatcht sie rekursiv | für Cross-Module-Reaktionen (AudioFocus-Loss → Pause) |
| **3. Atomic Cross-Axis-Update** | Im Orchestrator nach normalem Reduce: Spezial-Reducer mutiert mehrere Achsen in einem `store.update` | nur für wirklich atomare Updates wie "Pipeline-Done betrifft 4 Achsen" |

**Standard-Empfehlung:** Modus 2 (Action-Cascade). Modus 3 nur in begründeten Ausnahmen.

### §15.6 SOLID-Verifikation des Modul-Patterns

| Prinzip | Erfüllung |
|---|---|
| **SRP** | Jedes Modul hat genau eine fachliche Domäne. Reducer + EffectHandler sind kohärent. |
| **OCP** | Neues Modul = neue Datei + 4 kleine Erweiterungen, kein zentraler Code wird angefasst |
| **LSP** | Alle Module sind `DictateModule<S, A, E>` und können polymorph behandelt werden |
| **ISP** | `DictateModule`-Interface ist minimal (5 Pflicht-Methoden + 1 optional) |
| **DIP** | Orchestrator hängt am `DictateModule`-Interface, nicht an Konkretisierungen. EffectHandler hängt am Subsystem-Interface (über `services`) |
| **DRY** | Action-Liste lebt nur in der `Action`-sealed-class. Pref-Liste nur im PrefMirror. SideEffect pro Modul gekapselt. |
