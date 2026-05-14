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
<!-- FIX: Phase-B S-1 (2026-05-13) – §1 Scope-Aufzählung auf F-11 (DictateOrchestrator + DictateUiStateStore + Module) umgestellt. -->
<!-- FIX: Phase-C C-1 (2026-05-14) – Modul-Zähler aktualisiert auf 13 aktiv (12 ursprünglich + KeyboardInputModule aus Phase-B S-3). -->
- Den **`DictateOrchestrator` + `DictateUiStateStore` + 13 aktive Module** (F-11 Modular Orchestrator Pattern) als alleinige State-SSOT für ALLE UI-relevanten State-Achsen.
- Die **Bound-Service-Schnittstelle** (`LocalBinder`), über die der IME-Service mit dem Service kommuniziert.
- Die **Persistence-Schicht** (Room) mit minimaler Schema-Erweiterung und Checkpoint-Hooks.
- Den **Lifecycle**: Start, Stop, Notification-Updates, Recovery aus DB nach OOM-Death.
- Die **Migration** vorhandener Klassen (RecordingStateController, KeyboardUiController-State, JobExecutor-Verdrahtung).

Out-of-Scope (anderer Spec):
- View-Rendering, Layout-Wahl, Button-Sichtbarkeits-Resolver — siehe Spec 2 + Spec 3.
- Window-Lifecycle für Overlay — siehe Spec 3.

<!-- FIX: Phase-B S-4 (2026-05-13) – Naming-Konvention-Block: PipelineOrchestrator (alt, Audio-Pipeline) vs. DictateOrchestrator (neu, State-Action-Routing). -->
### §1.x Naming-Konvention für "Orchestrator" — Disambiguierung (Phase-B S-4)

Nach dem Refactor existieren **zwei Klassen mit "Orchestrator" im Namen**. Sie haben
verschiedene Verantwortlichkeiten und leben in verschiedenen Packages. Diese
Doppel-Existenz ist **bewusst akzeptiert** für Phase 1 (kein Refactor des Audio-
Pipeline-Pfades).

| Klasse | Package | Verantwortlichkeit | Status |
|---|---|---|---|
| `PipelineOrchestrator` | `net.devemperor.dictate.core` | **Audio-Pipeline-Runner** — orchestriert Speech-API-Calls + Reword-Pipeline + Auto-Formatting auf einem dedizierten Executor-Thread. 1383 Zeilen heute. | bleibt unverändert (siehe §8 Migrations-Tabelle); implementiert `PipelineRunner`-Interface (§4.9) |
| `DictateOrchestrator` | `net.devemperor.dictate.state` | **State-Action-Routing** — Composition Root + Action-Routing + Cross-Module-Cascade-Dispatch. Kennt nur das `DictateModule`-Interface, keine Pipeline-/Audio-Logik. | neu (Block 1b); siehe §4.3 |

**Lese-Konvention im Plan-Body:**

- "Orchestrator" (unqualifiziert) → **immer** `DictateOrchestrator` (neu, State-Routing).
- "PipelineOrchestrator" → **immer** die alte Klasse (mit oder ohne `core.`-Prefix).
- Code-Snippets nutzen den vollen `KClass.simpleName` zur Eindeutigkeit; Plan-Doku
  kann den unqualifizierten "Orchestrator"-Begriff verwenden, wenn der Kontext
  eindeutig ist (z.B. "der Orchestrator dispatcht Action X" → DictateOrchestrator).

**Phase-2-Backlog (Hauptplan §7.1):** Umbenennung des alten `PipelineOrchestrator`
auf z.B. `PipelineRunner` oder `PipelineExecutor`, oder Auflösung in den
`PipelineModule.runEffect`-Pfad (eliminiert den Naming-Konflikt strukturell).
Phase 1 akzeptiert die Doppel-Existenz, weil die Audio-Pipeline-Logik nicht
zur State-Refactor-Scope gehört (~10 Konsumenten-Sites, eigener Refactor).

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
| D7 | **`DictateOrchestrator.dispatch(Action)` als alleiniger Mutations-Eingang** für alle UI-State-Achsen (F-8 Single Dispatch + F-11 Modular Orchestrator) | Eliminiert die heutige resend_btn-Race + recordButton-Hybrid. Kein `_state.update`-Call außerhalb von Modul-`reduce` erlaubt (Audit-Pflicht §13.2). <!-- FIX: Phase-B S-1 (2026-05-13) – pre-F-11 PipelineStateManager → F-8/F-11 Single Dispatch --> |
| D8 | **DB-Schema-Migration M3→M4: table-recreate in Single-Transaktion** (`inserted_at`-Spalte **plus** `status`-CHECK-Erweiterung um RECORDING/TRANSCRIBING — siehe §6.1) | Rollback-sicher (Room-Migration ist atomar). Vorherige Iteration hatte "rein additiv" angenommen, das ist mit der Enum-Erweiterung nicht mehr möglich (SQLite kann CHECK nicht via `ALTER TABLE` ändern). Die `CREATE … _new` + `INSERT … SELECT`-Strategie folgt MIGRATION_2_3 als etabliertem Pattern. |

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

**Wichtig:** `DictateUiState` und alle Sub-State-Klassen sind **immutable**. Jede Änderung erzeugt eine neue Instanz, die per `_state.value = newState` emittiert wird. Konsumenten reagieren reaktiv via `state.collect { ... }`.

<!-- FIX: Issue 2.0.5 – PersistentList-Mutations-Idiom dokumentieren -->
**`PersistentList`-Mutations-Idiom (`pendingSessions` etc.):** `PersistentList` ist
structurally shared. Reducer MÜSSEN die nativen `add` / `remove` / `removeAt` /
`set`-Methoden von `PersistentList` verwenden — Round-Trip via `toMutableList()` +
`toPersistentList()` zerstört das Sharing.

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

### Achsen-Übersicht

<!-- FIX: Issue 2.0.1 – State-Achsen-Zähl-Korrektur (15 → 14) -->
<!-- FIX: Issue 1.1.5 / R.5 – contentArea wird in LayoutState konsolidiert (15 → 14 Sub-State-Felder, dann auf 13 reduziert) -->
13 State-Achsen (= Sub-State-Felder im `DictateUiState`), klassifiziert nach Verantwortung. Verschachtelte Sub-States (z.B. `BluetoothScoPublicState` als Detail von `audio`, `contentArea` als Detail von `layout`) zählen nicht als eigenständige Achsen. Plus 1 Top-Level-Boolean (`lastResultNeedsManualPaste`) als Pipeline-Service-Death-Flag.

| # | Sub-State-Feld | Eigentümer-Modul | Quellen |
|---|---|---|---|
| 1 | `recording` | RecordingModule (§15.1) | sealed class RecordingState (4 States: Idle/Preparing/Active/Paused), trägt `audioFile` + `useBluetooth` |
| 2 | `pipeline` | PipelineModule | sealed class PipelineUiState (4 States: Idle/Preparing/Running/ReprocessStaging), trägt `sessionId` (R.8) |
| 3 | `viewMode` | ViewModeModule | enum ViewMode (KEYBOARD/WIDGET/HOVER) |
| 4 | `layout` | LayoutModule | data LayoutState (contentArea + 3 Booleans, Pref-Mirror) — Issue 1.1.5/R.5 |
| 5 | `overlay` | OverlayModule | data OverlayState (4 Floats + 4 Booleans inkl. suppressAutoOverlay + hasPermission) |
| 6 | `audio` | AudioModule | data AudioState (Pref + System-Status + BluetoothSco) |
| 7 | `resend` | ResendModule | data ResendState (3 Booleans) |
| 8 | `livePrompt` | LivePromptModule | data LivePromptState (2 Booleans) |
| 9 | `language` | LanguageModule | data LanguageState (effective + override) |
| 10 | `features` | FeatureToggleModule | data FeatureToggles (5 Booleans, Pref-Mirror) |
| 11 | `theming` | ThemingModule | data ThemingState (4 Pref-gespiegelte Werte) |
| 12 | `pendingSessions` | PendingSessionsModule | PersistentList, DB-Subscriber-getrieben |
| 13 | `interruption` | InterruptionModule (Phase 2) | data InterruptionState — default null |
| (top) | `lastResultNeedsManualPaste` | PipelineModule | Boolean-Flag für IME-Service-Death-Recovery (Issue 2.1.9) |

ReprocessStaging ist KEIN eigenständiges Modul, sondern eine Sub-Variante der Pipeline-FSM (`PipelineUiState.ReprocessStaging`) — verwaltet vom PipelineModule.

### Vergleich zum heutigen State

| Heute | Künftig |
|-------|---------|
| `RecordingStateController.state` | `DictateUiState.recording` |
| `KeyboardUiController.state` | `DictateUiState.pipeline` |
<!-- FIX: R.5 – contentArea jetzt in LayoutState verschachtelt -->
| `KeyboardStateManager.contentArea` | `DictateUiState.layout.contentArea` |
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

<!-- FIX: Phase-C C-1 (2026-05-14) – Modul-Zähler aktualisiert (13 aktiv in Phase 1, KeyboardInputModule
     ergänzt durch Phase-B S-3 §15.6). Plus-1 Phase-2-Stub bleibt unerwähnt, weil das Interface
     hier den Phase-1-Vertrag beschreibt. -->
Das `DictateModule`-Interface ist der Plugin-Kontrakt. Jedes der 13 aktiven Module (siehe §15) implementiert dieses Interface und kapselt seine fachliche Domäne vollständig: eigener Sub-State, eigene Actions, eigener Reducer, eigene SideEffects, eigener EffectHandler, optionaler Cross-Module-Observer.

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
**Exhaustivity-Konvention für `reduce` / `runEffect` `when`-Blöcke:**

- Alle modulinternen `Effect`-Interfaces sind `sealed interface` (analog zur
  `Action`-Sealed-Class). Damit kann der Compiler Exhaustivity erzwingen.
- Alle `reduce` / `runEffect` `when`-Blöcke sind **expression-form**
  (`return when (action) { … }` bzw. `when (effect) { … }` als statement-form
  über alle sealed-Branches). Die expression-form lässt den Compiler die
  Exhaustivity erzwingen — fehlende Branches sind Compile-Errors.
- Ein `else`-Branch ist **nur bei explizit nicht-sealed Effects erlaubt** und
  verlangt einen Begründungs-Kommentar (z.B. "Effect is OEM-extensible").
- Reine Statement-`when` ohne `else` werden vermieden — sie verlieren die
  Exhaustivity-Garantie.

### §4.3 DictateOrchestrator (Composition Root)

Der Orchestrator löst den ehemaligen `PipelineStateManager` ab. Er kennt nur das `DictateModule`-Interface und routet Actions type-safe ans richtige Modul.

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

**Cascade-Tiefen-Counter (R.6):** Ein Loop wird in DEBUG via `error()` und in Release via Logger-Error
abgebrochen — IME crashed niemals. Cap 8 ist konservativ; reale Cascade-Tiefen liegen bei 1–3
(z.B. RecordingDone → ResendModule.onCrossModuleStateChange → Action.ResendAction.MarkAvailable).

<!-- FIX: Phase-B S-4 (2026-05-13) – Cascade-Order-Vertrag explizit verankert (vorher implizit via modules.flatMap). -->
**Cascade-Order-Vertrag (Phase-B S-4):** Die Reihenfolge der Cascade-Actions ist
<!-- FIX: Phase-C C-1 (2026-05-14) – stale Z. 1017-1033 → Section-Anchor (Line-Drift nach S-3/S-4-Apply). -->
deterministisch und folgt der Reihenfolge von `DictateModuleRegistry.all` (§4.8 `modules`-Liste).
Jeder rekursive `dispatchInternal(cascadeAction, depth+1)` (Step 6) macht einen **frischen**
`prevGlobal`/`nextGlobal`-Snapshot — Cascade-Actions sehen damit den State **inklusive**
vorheriger Cascade-Mutationen aus diesem Pass.

> **Konvention:** Cross-Module-Cascades sollen disjunkte State-Achsen mutieren —
> ein Modul soll NICHT in seine Cascade einplanen, dass ein anderer Cascade-Pass
> den State VOR ihm mutiert. Wenn Reihenfolge-Abhängigkeit nötig wird, ist das
> ein Mode-3-Use-Case (Atomic Cross-Axis-Update, Phase-2-Backlog, §14 Open-Q 4).
>
> **Code-Review-Pflicht:** Reorder der Modul-Liste in `DictateModuleRegistry.all`
> ist ein Plan-relevanter Refactor (Phase-B-Wiederholung erforderlich), kein
> Code-Cleanup. Die Reihenfolge wird via `DictateOrchestratorCascadeOrderTest.kt`
> (Block-1b-Acceptance) verifiziert.

<!-- FIX: Phase-B S-4 (2026-05-13) – ProGuard-Keep-Regel ist Pflicht für `KClass.sealedSubclasses`-Reflection. -->
<!-- FIX: Phase-C C-1 (2026-05-14) – stale Z. 587-589 → Method-Name (Line-Drift nach S-3/S-4-Apply). -->
> **⚠ ProGuard/R8-Keep-Regel ist Pflicht (Phase-B S-4):** `collectLeaves` (siehe `DictateOrchestrator`-Body)
> verwendet `KClass.sealedSubclasses` — Reflection auf die Action-Hierarchie.
> ProGuard-Default-Behavior in Release-Builds strippt diese Hierarchie weg, wenn
> die Klassen nicht explizit gehalten werden — `sealedSubclasses` returnt dann eine
> **leere Liste**, `moduleByLeafClass` ist **leer**, jeder Action-Dispatch wird
> `DispatchOutcome.Unrouted` → silent-drop **aller** Actions im Release-Build.
> Bug-Klasse identisch zu S-3 F-1/F-2, nur kataklysmischer (alle 14 sealed
> Action-Subtypen betroffen).
>
> **Konkreter ProGuard-Patch** (in `app/proguard-rules.pro` zu ergänzen, Block 1b):
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
> **Acceptance:** Block-1b ergänzt eine `OrchestratorReleaseSmokeTest.kt`
> (instrumented), die einen Release-Build verifiziert (`./gradlew assembleRelease`
> + `adb install`). Test dispatcht eine konkrete Action und assertet
> `DispatchOutcome.Applied` (nicht `Unrouted`).
>
> **Cross-Link:** Der Vollständigkeits-Check in `DictateModuleRegistry.init` (§4.8)
> verwendet ebenfalls `Action::class.sealedSubclasses` — beide Reflection-Sites
> profitieren von derselben Keep-Regel.

**Sealed-Leaves-Indexing (R.4):** Jede konkrete Action-Class ist genau einem Modul zugeordnet;
Verstoß ist Init-Time-Error (DI-Container-Pattern, analog Hilt/Dagger).

**Reentrancy-Vertrag (2.1.4 Option A):** `dispatch()` ist Main-Thread-confined, frozen-cascade.
Effekte oder Listener, die eine neue Action emittieren wollen, MÜSSEN `emitAction()` (async-via-scope)
verwenden — niemals `dispatch()` aus einem Effect-Body re-entrant. LocalBinder-Top-Level-Schutz:
ein gefangener `Throwable` wird in eine `Action.EffectFailure` umgewandelt (Issue 2.1.3 Option D).

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

<!-- FIX: Issue 1.0.4 – Java-Brücken-Notiz analog ActiveJobRegistryObserver -->
<!-- FIX: Phase-B S-3 (2026-05-13) – Java-Brücke vollständig spezifiziert (vorher nur "vorgesehen für Block 2"). -->
**Java-Brücke `DictateUiStateObserver`** — analog zu `core/ActiveJobRegistryObserver.kt`,
wird zur Block-2-Acceptance-Vorbedingung. Vorlage 1:1 portiert:

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

**Block-2-Acceptance** (in §10 ergänzt): Die Brücke ist angelegt; mindestens ein Java-
Konsument (`DictateInputMethodService.java`) verwendet sie statt direkter Callbacks.

### §4.5 PipelinePrefMirror (Pref-Sync, F-10 angepasst auf Sub-State-Struktur)

Erweitert um die 9 zusätzlichen UI-State-relevanten Prefs (RewordingEnabled, AutoFormattingEnabled, InstantOutput, Vibration, Theme, AccentColor, OverlayCharacters, OutputSpeed, UseBluetoothMic) und mappt auf die neue Sub-State-Struktur:

<!-- FIX: Phase-B S-4 (2026-05-13) – Phase-1/Phase-2-Hinweis: aktuelle Implementierung ist hardcoded, prefBindings()-API ist Phase-2. -->
> **Phase 1 vs. Phase 2 (Phase-B S-4):** Die untenstehende `initialMirror`- und
> `sync`-Implementation ist **Phase 1** — hardcodierte Mappings für 19 Prefs auf
<!-- FIX: Phase-C C-1 (2026-05-14) – stale Z. 462 → Section-Anchor (Line-Drift). -->
> die Sub-State-Achsen. Die `DictateModule.prefBindings()`-API (§4.2 `prefBindings()`-Hook) wird
> in Phase 1 **NICHT** konsumiert. Phase 2 (Hauptplan §7.1 Out-of-Scope) ersetzt
> die Hardcodes durch Iteration über `modules.flatMap { it.prefBindings() }` —
> dann werden Module ihre Prefs deklarativ deklarieren. Während Phase 1: **KEIN**
> Modul-Pref-Hook konsumieren, sonst Doppel-Spiegelung mit Race-Risk.

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

### §4.8 DictateModuleRegistry (zentrale Modul-Liste)

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
**Manuelle Modul-Liste vs. Reflection-basierte Action-Leaves (Phase-B S-4):**

Der Plan verwendet **zwei verschiedene** Reflection-Entscheidungen:

| Hierarchie | Mechanismus | R8/ProGuard-Risk | Mitigation |
|---|---|---|---|
| **Modul-Registry** (`DictateModuleRegistry.all`) | Manuelle Liste (oben) | Kein Risk — Klassen sind als `object`-Singletons in der Liste referenziert, R8 sieht die Refs | n/a |
| **Action-Leaves-Indexing** (`DictateOrchestrator.collectLeaves` + Vollständigkeits-Check) | Reflection via `KClass.sealedSubclasses` | **JA** — R8-Default strippt sealed-Hierarchie; `sealedSubclasses == emptyList()` → leere Map → silent-drop aller Actions | **ProGuard-Keep-Regel in `app/proguard-rules.pro` ist Pflicht** (siehe §4.3 ProGuard-Block) |

**Compile-Time-Garantie:** `DictateModule` ist `sealed interface`, also kennt der
Compiler alle Implementierer. Die manuelle Modul-Liste ist debug-freundlich +
R8-robust. Die Action-Leaves-Map kann NICHT manuell sein (15+ leaf Klassen, jede
neue Action wäre eine zweite Plan-Stelle zum Updaten — DRY-Verstoß) — Reflection
ist hier die saubere Lösung, mit ProGuard-Keep-Regel als unverhandelbare
Voraussetzung.

### §4.9 Dependency-Interfaces (F-2 / DIP, unverändert)

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

Konkretisierungen: `RoomPipelineSessionRepo` (im SessionsDao-Aufruf), `JobExecutor` (statisches object adaptiert das Interface).

### §4.10 Kontrakt

Alle Mutationen laufen NUR über `DictateOrchestrator.dispatch(action)`. Das Modul-System enthält die Logik (Reducer + EffectHandler), der Orchestrator routet, der Store hält Wahrheit. Direkt-Mutationen auf View-Properties (`view.visibility = ...`) sind verboten. Subscriber lesen `store.state` über `StateFlow.collect`.

<!-- FIX: Issue PENDING-1 / Block-4 Resolved – AudioFileFactory konkret spezifiziert -->
### §4.11 AudioFileFactory (Cache-File-Allocator, R.2)

> **Heimat-Sektion.** Spec 2 §10 (`resolveRecordAction`) und §15.2
> (RecordingModule) zeigen den Aufruf — die kanonische Definition lebt hier.
> Erstreckt sich über Block 3 (Composition-Wiring) + Block 4
> (RecordingHardwareSubsystem-Integration).

#### §4.11.1 Motivation

Der Reducer `RecordingModule.reduce(Idle, StartRecording)` ist pure und
darf KEINEN Hardware/IO-Read machen. Gleichzeitig braucht der nächste State
`Preparing(audioFile = …)` einen konkreten `File`-Handle. Lösung: **das
File wird vor dem Dispatch erzeugt** und als Action-Argument in den
Reducer geschoben — der reine Reducer sieht nur ein bereits existierendes
`File`-Objekt (kein Disk-Zugriff im Reducer-Body).

Heute (Pre-Refactor, `DictateInputMethodService.java:1612`) ist die
Allokation eine fixe Konstante:

```java
audioFile = new File(getCacheDir(), "audio.m4a");   // einziger Name, überschreibt sich
```

Das ist mit dem Multi-Job-Modell (R.8) und dem geplanten Recovery-Pfad
(§11.6) inkompatibel: zwei Jobs (z.B. eine laufende Transkription + eine
neue Aufnahme) würden auf denselben File-Path zeigen. Die Factory löst
das mit collision-freien Namen pro Session.

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

#### §4.11.3 Default-Implementation

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

#### §4.11.4 Pre-Dispatch-Usage

Der Caller (Spec 2 §10 — `resolveRecordAction`) baut die `StartRecording`-
Action mit dem allozierten File:

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

`allocate()` läuft **vor** `dispatch()`. Eine `IOException` aus dem
Mkdirs muss vom Resolver (View-Layer) gefangen und in eine
`ToastSink`-Message übersetzt werden — bevor `dispatch` aufgerufen wird.
Der Reducer sieht den Failure nie.

#### §4.11.5 Lifecycle-Pflichten

| Hook | Aufrufer | Aufruf | Zweck |
|---|---|---|---|
| `allocate()` | Pre-Dispatch-Resolver (Spec 2 §10) | pro `StartRecording` | frischer File-Path |
| `cleanupOrphans()` | `DictatePipelineService.onCreate` (§7.3) | einmal pro Service-Boot | Crash-Orphans entfernen |
| Datei löschen (legitim) | `Effect.DeleteAudioFile` Handler (§15.2) | bei `CancelRecording` | aktive Cancellation |
| Datei löschen (persistiert) | `RecordingRepository.deleteBySessionId` (unverändert) | History-Cleanup | DB-getriebene Löschung |

**`cleanupOrphans()` Aufruf-Stelle** (§7.3, neu hinzuzufügen):

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

Erforderlicher DAO-Zusatz (Block 3 Schema-Block):

```kotlin
@Query("SELECT audio_file_path FROM sessions WHERE audio_file_path IS NOT NULL")
fun findAllAudioFilePaths(): List<String?>
```

##### §4.11.5.1 Service-onCreate-Sequenz — kanonische Reihenfolge

Der Cleanup ist Teil der breiteren Service-onCreate-Sequenz (siehe §7.3
für den Voll-Skeleton). Damit der Implementierer nicht raten muss,
welcher Schritt vor welchem läuft, hier die geordnete Sequenz:

<!-- FIX: Phase-B S-1 (2026-05-13) – Sequence-Tabelle auf DictateOrchestrator + ModuleServicesFactory umgestellt (F-11 Modular-Orchestrator-Pattern; PipelineStateManager existiert nicht mehr) -->
<!-- FIX: Phase-B S-5 (2026-05-13) – Schritt 1.5 (ensureNotificationChannel) + Schritt 9 (startForeground) explizit in der Sequenz verankert. Ohne diese beiden Schritte wurde aus der "5-Sekunden-Timeout-Klausel" (§11.1.4) ein implizites Detail; jetzt ist die Sequenz vollständig (Channel → DI → FGS-Notification → async-Recovery + async-Cleanup). Plus: Schritt 10 (JobExecutor.initialize) explizit als letzter sync-Schritt — G7 §13.5.a verankert, dass der alte PipelineOrchestrator (nicht der neue DictateOrchestrator) übergeben wird (Naming-Konflikt-Falle, siehe §1.x). -->
| # | Schritt | Sync/Async | Anmerkung |
|---|---|---|---|
| 1 | `super.onCreate()` | sync | Service-Basis-Setup |
| 1.5 | `ensureNotificationChannel()` | sync | **MUSS vor Schritt 9** (startForeground). Channel-Erzeugung ist in-memory + getSystemService-Call, < 5 ms. API < 26 no-op. |
| 2 | DI-Wiring (Store, Repos, Runner, PrefMirror, Recovery) | sync | §7.3 Composition Root |
| 3 | `audioFileFactory = CacheDirAudioFileFactory(applicationContext)` | sync | Factory construct |
| 4 | `servicesFactory = ModuleServicesFactory { ModuleServices(audioFileFactory = audioFileFactory, …) }` | sync | DI-Container (siehe §4.7) |
| 5 | `orchestrator = DictateOrchestrator(scope, store, servicesFactory, prefMirror, recovery)` | sync | Composition Root (§4.3) — Konstruktor-`init` ruft `prefMirror.attach(store)` **VOR** `scope.launch { recovery.recover(store) }` (siehe §4.3) |
| 6 | `notifCoordinator = …`, `actionRouter = …` | sync | §7.4 / §7.5 |
| 6.5 | `LegacyAudioFileMigration.run(applicationContext)` | sync | One-shot idempotent (KG-AFF-2). Läuft VOR Schritt 7/8 |
| 7 | (`recovery.recover(store)` läuft bereits async via `Orchestrator.init`, Schritt 5) | async | §11.6.1 |
| 8 | `serviceScope.launch(Dispatchers.IO) { audioFileFactory.cleanupOrphans(referenced) }` | async | siehe oben |
| 9 | `startForeground(PipelineNotificationCoordinator.NOTIF_ID, notifCoordinator.buildInitial())` — gerufen aus `onStartCommand`, NICHT aus `onCreate` <!-- FIX: Phase-C C-2 (2026-05-14) – NOTIF_ID-Qualifier (SoT-Konsolidierung). --> | sync | **MUSS vor 5 s nach `startForegroundService`-Call** (§11.1.4). `buildInitial()` ist pure State→Notification-Render, in-memory, < 5 ms. Schritt 9 lebt im `onStartCommand`-Pfad, weil Android `onCreate` ohne `onStartCommand` nicht via `startForegroundService` durchläuft. |
| 10 | `JobExecutor.initialize(pipelineOrchestrator)` | sync | G7 §13.5.a. ⚠ Erwartet den **alten** `PipelineOrchestrator` (Audio-Pipeline-Runner, Spec 1 §1.x Naming-Konvention), NICHT den neuen `DictateOrchestrator`. Position nach Recovery-Async-Start ist OK (JobExecutor wird erst von User-Action / pendingSessions-Resume gerufen, also nach Recovery-Completion). |

**Reihenfolge-Invarianten:**

- Schritt 3 läuft **vor** Schritt 4, weil `services` die Factory hält.
- Schritt 4 läuft **vor** Schritt 5, weil der `DictateOrchestrator`-Konstruktor `servicesFactory` als Parameter erwartet.
<!-- FIX: Phase-C C-1 (2026-05-14) – stale Z. 567-570 → Section-Anchor (Line-Drift). -->
- **Schritt 5 garantiert `prefMirror.attach(store)` vor `recovery.recover(store)`** (codiert im `Orchestrator.init`-Block, §4.3 `DictateOrchestrator`-Konstruktor). Verstoß gegen diese Reihenfolge wäre subtil — Recovery sähe initial leere Pref-Mirror-Achsen (z.B. `state.overlay.position*` als Default-Werte statt persistierten Werten). Die Reihenfolge ist **Teil des Orchestrator-Konstruktor-Vertrags**, nicht extern an die Service-onCreate-Sequenz delegiert — Schritt 5 ist atomar.
- Schritt 7 (im Orchestrator-`init` gestartet) und Schritt 8 (im Service explizit gelauncht) laufen **parallel** im `serviceScope` — beide sind Reads, keiner blockt den anderen. Schritt 8 liest die DB direkt über `findAllAudioFilePaths()`; eine Synchronisation mit Schritt 7 wäre teurer als die doppelte Read und nicht nötig.
- **Initial-State-Race (NEU, Phase-B S-1 / F-11):** Subscribers, die VOR Schritt 5 (`Orchestrator(…)`) auf `store.state` attached werden, sehen den `DictateUiState.initial()`-Default — kein Pref-Mirror, keine pendingSessions. Der IME-Service `bindService`-Pfad (§7.2) garantiert, dass der `LocalBinder` erst NACH `onCreate` zurückgegeben wird; damit ist `store.state` zum Zeitpunkt des ersten IME-`collect`-Aufrufs **mindestens** mit Pref-Mirror-Werten gefüllt (Schritt 5 hat `prefMirror.attach` synchron durchgeführt). Recovery-Werte (`pendingSessions`) können nachträglich nachreichen — Subscriber müssen idempotent gegenüber späten `pendingSessions`-Updates sein.

**`onDestroy`-Interaktion:**

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

Wird der Cleanup-Job mitten in `listFiles()` oder einem `delete()`
abgebrochen, ist das **safe**: jeder Einzel-`delete()` ist atomic auf
der FS-Ebene, und nicht-gelöschte Orphans werden beim nächsten
Service-Boot erneut versucht (idempotent).

##### §4.11.5.2 Concurrency-Vertrag (Threading-Modell)

| Methode | Thread | Begründung |
|---|---|---|
| `allocate()` | **Main-Thread** (Resolver, View-Layer) | nur `mkdirs()` + UUID — O(1)-FS-Operation, akzeptabel auf Main |
| `cleanupOrphans()` | **Dispatchers.IO** (Service-Scope, einmalig in `onCreate`) | listFiles + delete-Loop — niemals auf Main |
| `companion`-Reads | egal | reine `const val`-Reads, lockfrei |

**Re-Entry-Sicherheit:** Es gibt **keinen geteilten Mutable State**
zwischen `allocate()` und `cleanupOrphans()`. Beide arbeiten gegen
denselben `audioCacheDir`-Lazy-Reference (immutable nach erstem Read),
aber:

- `allocate()` produziert nur `File`-Path-Objekte (kein Disk-Read auf
  existierende Files).
- `cleanupOrphans()` macht eine **snapshot-basierte** `listFiles()` —
  Dateien, die **nach** dem Snapshot erzeugt werden, sind außerhalb
  des Cleanups.

Race-Risiko ist auf das Window zwischen `listFiles()` und dem Per-Datei-
`delete()` beschränkt — abgedeckt unter Edge-Case #5 (§4.11.7).

##### §4.11.5.3 Service-Wiring — vollständiges Code-Diff (zukünftiger Pipeline-Service)

Anker für den Implementierer. Der zukünftige `DictatePipelineService`
(§7) wird in Kotlin geschrieben (`.kt`); das heutige
`DictateInputMethodService.java` bleibt der IME-Service, der den
Pipeline-Service per `bindService` anbindet (§7.2). Das Wiring der
Factory landet im **Pipeline-Service**, nicht im IME-Service:

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

**Lifecycle-Garantie:** Die Factory ist `lateinit var` und lebt von
`onCreate` bis `onDestroy` des Pipeline-Service. Sie hält selbst
**keinen State** (außer dem `audioCacheDir`-Lazy) und braucht kein
eigenes `dispose()`. `serviceScope.cancel()` cancelt den asynchronen
Cleanup-Job. `allocate()`-Calls aus dem View-Layer können nach
`onDestroy` nicht mehr eintreffen, weil das LocalBinder-Interface
dann unbound ist (siehe §5).

##### §4.11.5.4 Pre-Refactor-Pattern (Heute → Künftig)

Heute wird das `audioFile`-Feld direkt im `DictateInputMethodService`
gehalten (`DictateInputMethodService.java:208,1612`):

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

Nach dem Refactor lebt die `audioFile`-Allokation im Resolver
(`resolveRecordAction`, Spec 2 §10) und wird als Action-Argument
durch den Reducer geschoben. Das Service-Field `audioFile` in
`DictateInputMethodService.java` entfällt — der Pfad fließt durch
`StartRecording.audioFile → RecordingState.Preparing(audioFile) →
Effect.AllocateMediaRecorder(audioFile) → RecordingManager.start(file)`.

Konkrete Migrations-Schritte:

1. **Entfernen:** Field `private File audioFile;` in
   `DictateInputMethodService.java:208`.
2. **Entfernen:** Zeile `audioFile = new File(getCacheDir(), "audio.m4a");`
   (Z. 1612) — wandert in `CacheDirAudioFileFactory.allocate()`.
3. **Anpassen:** alle Lese-Sites (1407, 936, 1706) — neuer Owner ist
   `RecordingState.Preparing.audioFile` bzw. das
   `Effect.AllocateMediaRecorder`-Argument.
4. **Behalten:** `Pref.LastFileName.INSTANCE`-Pref-Eintrag bleibt
   (Display-Convenience, kein State-Driver); aktualisiert vom Resolver,
   **nicht** von der Factory (SRP — Factory bedient nur FS-Allokation).

#### §4.11.6 Recovery-Coupling (§11.6.2 Interaktion)

Die Factory wählt **`context.cacheDir`** (nicht `filesDir`). Damit gilt:

<!-- FIX: Phase-B S-7 (2026-05-13) – Recovery-Coupling-Tabelle um v4-Status RECORDING und
     TRANSCRIBING erweitert (S-2-DB-Schema-Migration bringt diese neuen Stati). Vorher nur RECORDED
     dokumentiert — Lücke gegen Spec-1-§11.6.2 + Acceptance R.16a/b/c. -->
| Szenario | Status (v4) | Verhalten | Recovery-Verhalten |
|---|---|---|---|
| App-Crash + Restart, Cache überlebt | RECORDED | File existiert, Session-Row hat Pfad | `recoverFromDb` lädt sie als RECORDED-Session normal in `pendingSessions` (§11.6.2 Z. 2183) |
| OS löscht `cacheDir` (Storage-Druck) | RECORDED | File weg, Session-Row hat Pfad | `recoverFromDb` filtert in Ghost-Sessions → `lastErrorType=UNKNOWN, lastErrorMessage="audio file vanished"`, status FAILED (§11.6.2 Z. 2192-2197) |
| User triggert "Cache leeren" in Preferences | RECORDED | File weg, Session-Row hat Pfad | identisch zum OS-Wipe-Pfad — Ghost-Session |
| Crash mid-Recording (Prozess-Kill) | RECORDING | DB-Row hat typischerweise `audio_file_path = NULL` (Path wird erst beim Stop geschrieben, siehe §6.3 RECORDING-Recovery-Block) | Recovery promoted → FAILED + `lastErrorMessage="recording-interrupted-by-process-death"`; partial-written File im `cacheDir/audio/` wird vom `cleanupOrphans`-Boot-Hook (NICHT vom Recovery-Pfad) eingesammelt. Konsistent mit S-2 F-2 + Acceptance R.16a |
| Crash mid-Transcription (Prozess-Kill), File überlebt | TRANSCRIBING | File existiert, Session-Row hat Pfad | Recovery downgraded → RECORDED + stale-error-clear; Session landet in `pendingSessions` (kein Auto-Resume, D4/OPEN-4). Acceptance R.16b |
| Crash mid-Transcription, File durch OS-Wipe weg | TRANSCRIBING | File weg, Session-Row hat Pfad | Recovery promoted → FAILED + `lastErrorMessage="audio file vanished before transcription"`. Acceptance R.16c |

**Akzeptiertes Trade-off:** `cacheDir` ist der korrekte Heimat-Ort für
**transiente** Recording-Dateien (Pre-Persistence). Sobald die Pipeline
durch ist, kopiert `RecordingRepository.persistFromCache` (heute schon
existierend, `RecordingRepository.kt:45`) in `filesDir/recordings/` —
**das** ist der persistente Heimat-Ort. Die Factory bedient nur die
Cache-Phase. Ein OS-Wipe zwischen `MediaRecorder.stop()` und
`persistFromCache` ist möglich, aber sehr unwahrscheinlich (Sekunden-
Fenster); §11.6.2 deckt es als Ghost-Session ab.

##### §4.11.6.1 `persistFromCache`-Trigger — wo lebt der Effect?

Die Persist-Operation existiert heute schon (`RecordingRepository.kt:45`,
"persistence bridge for Recordings"). Wichtige Frage für den neuen
Architektur-Aufbau: bleibt der Aufruf **innerhalb der Pipeline-Stage**
oder wird er zu einem expliziten `Effect.PersistAudioFile`?

**Entscheidung (im Plan getroffen):** Bleibt **innerhalb des
PipelineRunner-Jobs**. Konkret:

- `PipelineOrchestrator.persistNewSession()` (`PipelineOrchestrator.kt:837`)
  ruft `repo.persistFromCache(audioFile, sessionId)` in Z. 855. Das
  ist die PERSIST-Stage des Pipeline-Jobs (zwischen RECORDED-Session-
  Create und PROCESS-Stage).
- Die Pipeline läuft asynchron auf dem JobExecutor (Background-Thread,
  siehe `JobExecutor.kt`). Recording-Module-Reducer triggert das
  über `Effect.SubmitPipelineJob(sessionId, audioFile)` →
  `PipelineRunner.submit(sessionId, audioFile)` (Definition siehe
  Spec 1 §4.9 / §15.2).
- **Konsequenz:** Aus Sicht des `RecordingModule.reduce`/`runEffect`
  ist Persist eine Black-Box innerhalb der Pipeline. Das Modul
  dispatcht `Effect.SubmitPipelineJob` und "vergisst" das Cache-File
  bewusst — der nächste relevante Lifecycle-Punkt ist
  `Action.PipelineAction.PersistCompleted(sessionId)`, der von der
  Pipeline emittiert wird, sobald die Datei in `filesDir/recordings/`
  liegt.

**Wer löscht das Cache-File nach Persist?**

Heute: **niemand** explizit. Das Cache-File überlebt bis zum nächsten
`cleanupOrphans`-Lauf beim Service-Boot. Begründung: `persistFromCache`
nutzt `copyTo(overwrite = true)` (Z. 47), nicht `move`/`rename` — das
Original bleibt im Cache. Nach `PersistCompleted` zeigt die Session-Row
in der DB auf den **persisted** Pfad (`filesDir/recordings/{sid}.m4a`),
also ist der Cache-File-Eintrag nicht mehr in `referencedPaths` —
beim nächsten Boot räumt `cleanupOrphans` ihn weg.

Das ist akzeptiert (keine sofortige Cleanup-Coupling nötig), siehe
Edge-Case #7 unten.

<!-- KNOWLEDGE-GAP: KG-AFF-1 – Sofort-Delete des Cache-Files nach Persist [RESOLVED 2026-05-11] -->
> **✅ Knowledge-Gap (KG-AFF-1): Sofort-Delete des Cache-Files nach erfolgreicher Persist — RESOLVED 2026-05-11**
>
> - **Was wir wussten:** `persistFromCache` (`RecordingRepository.kt:45`) macht
>   `copyTo(overwrite = true)`, nicht `move` — das Cache-File überlebt. Heute
>   bleibt es bis zum nächsten `onCreate.cleanupOrphans`-Lauf liegen.
> - **Was wir nicht wussten:** Ob `Effect.PersistAudioFile` das Cache-File
>   sofort nach erfolgreichem Copy löscht (Inline-Cleanup) — oder ob der
>   Status-quo (Boot-Cleanup über `cleanupOrphans`) ausreicht.
> - **Auflösung:** **Sofort-Delete nach erfolgreichem Copy gewählt.**
>   Begründung: "Explicit cleanup wins" — eindeutige Eigentumsübergabe vom
>   Cache an Persist-Storage, weniger Storage-Druck zwischen Service-Boots,
>   und keine semantische Ambiguität (DB-Pfad zeigt auf filesDir, aber File
>   liegt noch im cacheDir — Implementer-Falle bei Lese-Recovery). Crash-
>   Fenster zwischen Copy und Delete ist akzeptiert: der zurückbleibende
>   Cache-File wird durch `cleanupOrphans` beim nächsten Boot eingefangen
>   (idempotent, da DB-Pfad bereits auf filesDir zeigt).
>
>   **Konkreter Code-Patch** in `PipelineOrchestrator.persistNewSession`
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
>   Reihenfolge ist **`copyTo` → DB-Row-Write → cache-`delete()`** (DB-Write
>   ist Ground-Truth; wenn Delete davor failt, hängt der DB-Pfad an einer
>   Datei, die nicht existiert). Bei `delete()`-Failure (z.B. FS-Race):
>   Log-Warn, **kein Throw** — der Pipeline-Job bleibt erfolgreich, weil die
>   persistierte Datei in `filesDir` korrekt vorliegt.
> - **Einarbeitung:** Code-Snippet oben + Patch-Anker in
>   `PipelineOrchestrator.kt:854-857`. §4.11.7 Edge-Case #7 (Cache-File
>   überlebt nach Persist) wird durch diese Resolution überschrieben — Edge-
>   Case #7 ist nach Block 4 **abgeschwächt**: gilt nur noch im Crash-Fenster
>   zwischen Copy und Delete (Sub-Millisekunden-Bereich), das durch
>   `cleanupOrphans` zuverlässig aufgeräumt wird.

##### §4.11.6.2 App-Update-Migration: alte `cacheDir/audio.m4a`-Pfade

**Problem:** Vor dem Refactor liegt das Cache-File unter dem
**festen Pfad** `cacheDir/audio.m4a` (`DictateInputMethodService.java:1612`)
— alle bisherigen Sessions, die in der DB ein `audio_file_path` haben,
referenzieren genau diesen einen Pfad. Mit dem Refactor wandert das
File-Schema auf `cacheDir/audio/rec_{ts}_{uuid8}.m4a`.

**Beobachtungen:**

- Android löscht `cacheDir` **nicht** bei App-Updates — die alte
  `cacheDir/audio.m4a` überlebt das Update.
- Mehrere alte Sessions in der DB können denselben `audio_file_path =
  ".../cacheDir/audio.m4a"` haben (das ist heute *immer* der Fall,
  weil der Pfad nicht UUID-suffixiert ist — jede neue Aufnahme
  überschreibt die alte).
- Das alte File liegt **nicht** im Sub-Verzeichnis `audio/`, sondern
  direkt im `cacheDir`. Damit fällt es **nicht** in den Scope der neuen
  `cleanupOrphans`-Pfad-Filter (`audioCacheDir = cacheDir/audio/`).

**Konsequenz für die neue Factory:**

| Aspekt | Verhalten | Begründung |
|---|---|---|
| Alte `cacheDir/audio.m4a` nach Update | **bleibt liegen**, `cleanupOrphans` sieht sie nicht | Sub-Verzeichnis-Scope schützt sie versehentlich |
| DB-Sessions mit `audio_file_path = ".../cacheDir/audio.m4a"` | Recovery-Pfad (§11.6.2) macht `File(path).exists()`-Check | Wenn die Datei noch existiert: als RECORDED-Session geladen. Wenn nicht: Ghost-Session, FAILED |
| Bereits **persistierte** alte Sessions (`filesDir/recordings/{sid}.m4a`) | unverändert | filesDir-Pfade sind nicht im Cache-Scope |

**Akzeptiertes Verhalten:**

1. Die alte `cacheDir/audio.m4a` bleibt nach App-Update als "stranded
   file" liegen. Wird beim nächsten User-"Cache leeren" via
   PreferencesFragment (Z. 285) gelöscht — denn diese Schleife
   iteriert über `cacheDir.listFiles()` ohne Sub-Filter und macht
   `file.delete()` direkt. (**Achtung:** siehe §4.11.6.3 — dieser
   Aufruf-Pfad löscht **nur Dateien**, nicht Sub-Verzeichnisse;
   `cacheDir/audio.m4a` ist eine Datei → wird gelöscht. Subdir
   `cacheDir/audio/` ist ein Verzeichnis → wird **nicht** gelöscht.)
2. Alte Sessions mit `audio_file_path = ".../audio.m4a"` werden vom
   Recovery-Pfad (§11.6.2) als Ghost-Session → FAILED markiert,
   sobald das File weg ist (User-Cache-Wipe oder OS-Storage-Druck).
   Das ist konsistent mit dem heutigen Verhalten — bereits *vor* dem
   Refactor waren diese Sessions nicht resubmittable (jede neue
   Aufnahme hat den File überschrieben).
3. **Keine aktive Migrations-Logik nötig** — Recovery-Pfad und
   `cleanupOrphans` sind beide robust gegen das Legacy-Schema.

<!-- KNOWLEDGE-GAP: KG-AFF-2 – Alte audio.m4a stranded nach Update [RESOLVED 2026-05-11] -->
> **✅ Knowledge-Gap (KG-AFF-2): Migration der alten `cacheDir/audio.m4a` beim App-Update — RESOLVED 2026-05-11**
>
> - **Was wir wussten:** Heute liegt das Cache-File unter `cacheDir/audio.m4a`
>   (`DictateInputMethodService.java:1612`). Nach App-Update bleibt es liegen,
>   aber außerhalb des neuen Sub-Dir-Scopes von `cleanupOrphans`. Alte
>   DB-Sessions referenzieren diesen Pfad. Alle DB-Sessions vor dem Refactor
>   teilen sich denselben einen `audio_file_path = ".../cacheDir/audio.m4a"`
>   (Pfad war nicht UUID-suffixiert — jede neue Aufnahme überschrieb die alte).
> - **Was wir nicht wussten:** Ob ein **One-Shot-Migration-Step** beim
>   ersten Service-Boot nach Update gewollt ist, der `cacheDir/audio.m4a`
>   explizit löscht UND die referenzierenden DB-Sessions als FAILED
>   markiert (sie waren ohnehin nicht resubmittable).
> - **Auflösung:** **Einmaliger Legacy-Cleanup beim ersten Service-Start
>   nach Update**, getrieben über ein Boot-Pref-Flag `legacy_audio_purged_v4`.
>   Begründung: ohne expliziten Cleanup bleibt eine ~5 MB-Stale-Datei
>   potentiell auf Monate im Cache, und die referenzierenden Sessions
>   liegen in der History als verwirrend-pseudo-aktive Einträge (Recovery-
>   Pfad markiert sie erst FAILED, wenn die Datei verschwindet — also
>   theoretisch nie). Explizit FAILED zu setzen mit klarer Fehler-Meldung
>   `"audio_file_path_legacy_purged"` ist die saubere Lösung.
>
>   **Konkrete Implementierung** — neue Klasse
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
>   **Idempotenz-Klausel auf DAO-Ebene (Phase-B S-7):** Das `markLegacyAudioSessionsFailed`-
>   Update setzt unkonditional `status = FAILED` + `last_error_message`. Ohne `WHERE`-Filter
>   würden bereits FAILED-Sessions ihre **originale** `last_error_message` verlieren (z.B.
>   "transcription_timeout", "openai_rate_limit") — die historische Fehler-Information
>   ginge verloren. Außerdem: falls eine `COMPLETED`-Session zufällig denselben legacy
>   path hat (extrem unwahrscheinlich, aber theoretisch denkbar bei einem User, der per
>   adb manuell DB-Rows manipuliert), würde sie auf FAILED **gedowngraded** + Daten gehen
>   verloren. **Fix:** Query um `AND status NOT IN ('FAILED', 'CANCELLED', 'COMPLETED')`
>   filtern. Nur Sessions in `RECORDING`/`RECORDED`/`TRANSCRIBING` (die nach Phase 1
>   v4-Schema die einzigen sinnvollen "incomplete pre-refactor"-Stati sind) werden
>   gemarkiert.
>
>   Erforderlicher DAO-Zusatz (Block 3 Schema-Block, neben `findAllAudioFilePaths`):
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
>   **Idempotenz beim zweiten Lauf (z.B. nach Pref-Wipe):** der Pref-Flag-Check oben ist
>   die Primär-Idempotenz. Falls der Flag verloren geht (App-Daten-Wipe, User-Downgrade-
>   Upgrade-Zyklus), läuft die Migration nochmal — die `WHERE status NOT IN (...)`-Klausel
>   stellt sicher, dass schon migrierte FAILED-Rows nicht erneut überschrieben werden.
>   Sessions, die zwischen den beiden Migrations-Läufen entstanden sind, können den
>   legacy-Path nicht referenzieren (`AudioFileFactory.allocate` schreibt UUID-Pfade
>   in `cacheDir/audio/`), also kein False-Positive.
>
>   **Aufruf-Site:** `DictatePipelineService.onCreate`, vor Schritt 7/8
>   (Recovery + Orphan-Cleanup), sync — die Migration ist O(1)
>   (Existence-Check + 1 DB-Update + 1 Pref-Write):
>
>   ```kotlin
>   // After Step 6 (notifCoordinator/actionRouter wiring), before Step 7:
>   LegacyAudioFileMigration.run(applicationContext)  // one-shot, idempotent
>   ```
>
>   <!-- FIX: Phase-B S-7 (2026-05-13) – sync-vs-async + FGS-5s-Frist explizit dokumentiert. -->
>   **Threading + FGS-5s-Frist (Phase-B S-7):** Der `run()`-Aufruf läuft **synchron** auf dem
>   Main-Thread (Service.onCreate ist Main-Thread). Drei Operationen:
>
>   1. `PreferenceManager.getDefaultSharedPreferences(context)`-Read — disk-blocking, aber
>      typischerweise <5 ms (SharedPreferences sind beim Boot bereits gelesen).
>   2. `File(cacheDir, "audio.m4a")`-Existence-Check + `delete()` — disk-blocking, <10 ms.
>   3. `dao.markLegacyAudioSessionsFailed(...)` — SQL-UPDATE auf indexed Spalte (`audio_file_path`),
>      typischerweise <20 ms bei <1k Sessions; bei >10k Sessions evtl. 100 ms.
>
>   Summe: typischerweise <50 ms, worst-case ~200 ms. Die 5-Sekunden-FGS-Frist (§11.1.4) wird
>   nicht angeknabbert. **Trotzdem:** falls Telemetrie zeigt, dass die DAO-Update auf bestimmten
>   Devices >500 ms dauert, kann der Migration-Aufruf in einen `serviceScope.launch(Dispatchers.IO)`-
>   Block gewrappt werden — Idempotenz (Pref-Flag + DAO-WHERE-Filter) macht den Lauf-zu-spät-Fall
>   safe (ein paralleler `LegacyAudioFileMigration`-Lauf im nächsten Service-Boot wäre no-op).
>   Phase 1: sync, Phase 2 evaluieren falls Telemetrie ein Problem zeigt.
> - **Einarbeitung:** Migrations-Klasse oben (§4.11.6.2) + neuer DAO-Query
>   (Block 3 Schema, Spec 1 §6.x) + Aufruf-Site in §4.11.5.1 Schritt 6.5
>   (zwischen 6 und 7). Akzeptiertes-Verhalten-Liste (3 Punkte oberhalb)
>   bleibt als historischer Pfad dokumentiert: Punkt 3 ("Keine aktive
>   Migrations-Logik nötig") gilt nun nicht mehr — siehe Resolution oben.

##### §4.11.6.3 PreferencesFragment "Cache leeren" — präzise Mechanik

Eines der Argumente in §4.11.8 lautete:

> "Cache leeren" in den Preferences räumt das Subdir automatisch mit ab
> (recursive `listFiles().delete()`).

**Korrektur:** Der heutige Code (`PreferencesFragment.java:272-289`)
iteriert **nicht** rekursiv:

```java
File[] cacheFiles = requireContext().getCacheDir().listFiles();        // Z. 272 — nur Top-Level-Entries
// ...
for (File file : cacheFiles) {
    file.delete();                                                      // Z. 285-287 — File.delete() auf Dir = no-op falls non-empty
}
```

**Java-Semantik:** `File.delete()` auf ein **nicht-leeres
Verzeichnis** gibt `false` zurück und tut nichts. Damit gilt:

- Alte `cacheDir/audio.m4a` (Datei, Top-Level): wird gelöscht.
- Neue `cacheDir/audio/rec_*.m4a` (Sub-Verzeichnis nicht leer): wird
  **NICHT** gelöscht via "Cache leeren".

**Implikation:** Die Aussage in §4.11.8 ("Cache leeren räumt das Subdir
mit ab") ist **falsch**. Wenn das gewollt ist, muss die "Cache leeren"-
Logik im `PreferencesFragment` zu **rekursivem Delete** erweitert
werden (ergänzender Punkt für den Implementierer).

<!-- KNOWLEDGE-GAP: KG-AFF-3 – PreferencesFragment rekursiv? [RESOLVED 2026-05-11] -->
> **✅ Knowledge-Gap (KG-AFF-3): Soll "Cache leeren" rekursiv durch Sub-Dirs gehen? — RESOLVED 2026-05-11**
>
> - **Was wir wussten:** Heutiger Code (`PreferencesFragment.java:272-289`)
>   iteriert nur Top-Level und ruft `File.delete()` — funktioniert
>   nicht auf nicht-leeren Verzeichnissen. Mit dem neuen `cacheDir/audio/`-
>   Subdir würde "Cache leeren" das Audio-Subdir nicht räumen.
> - **Was wir nicht wussten:** Ob der Implementer den Code anpassen soll,
>   sodass "Cache leeren" auch das neue `audio/`-Subdir mit aufräumt.
> - **Auflösung:** **Code-Erweiterung mit `deleteRecursively()`.** Pro:
>   konsistente UX (User erwartet alles weg); die heutige Cache-Anzeige
>   `cacheFiles.length` und `cacheFiles.length × File::length` würde
>   sonst dauerhaft das Audio-Subdir ignorieren (Top-Level-listFiles zählt
>   ein Verzeichnis als 1 Entry, aber ohne den enthaltenen Files).
>
>   **Konkreter Java-Diff** in `PreferencesFragment.java:272-289`:
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
>   Code-Cost: ~35 Zeilen Helper + Call-Site-Anpassung. Kotlin-Stdlib
>   `deleteRecursively()` wäre kürzer, aber die Datei ist Java —
>   handgeschriebene Walks bleiben in derselben Sprache (kein
>   Kotlin-Interop-Overhead, kein neuer Modul-Import).
>
>   <!-- FIX: Phase-B S-7 (2026-05-13) – Race-Schutz: "Cache leeren" während aktiver Recording. -->
>   **Race-Schutz: aktive Recording während "Cache leeren" (Phase-B S-7):**
>
>   Wenn der User in Preferences "Cache leeren" klickt, während eine Recording läuft (MediaRecorder
>   hat `cacheDir/audio/rec_*.m4a` offen für Schreiben), führt der unbedingte `entry.delete()`-Aufruf
>   auf der offenen Datei zu einem `unlink()` auf Linux/Android-FS — der File-Descriptor bleibt
>   offen, MediaRecorder schreibt weiter in den unlinkten Inode, aber **bei Recording-Stop ist die
>   Datei weg** (kein dirent-Eintrag mehr). Pipeline-Stage sieht eine fehlende Audio-Datei →
>   Ghost-Session, FAILED. User merkt "meine Aufnahme ist verloren" — schwer zu debuggen, weil der
>   Click-Path "Settings → Cache leeren" und das Recording typischerweise temporär entkoppelt sind.
>
>   **Mitigation:** Defensive Vorbedingung in PreferencesFragment's `OnPreferenceClickListener`:
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
>   Neue String-Resource: `dictate_cache_clear_blocked_recording = "Aufnahme läuft — Cache kann
>   nicht gelöscht werden. Stoppe zuerst die Aufnahme."`
>
>   **Akzeptierter Edge-Case:** wenn der User mitten im 1-2-Sekunden-Fenster zwischen Recording-
>   Stop und State-Update klickt, kann der Race-Schutz versagen — aber das ist akzeptiert, weil
>   der Recording-Stop-Pfad das Audio-File **vor** der Cache-Wipe-Click-Resolution bereits
>   abkopiert hat (`persistFromCache` läuft synchron im Stop-Effect, §4.11.6.1) — der Wipe
>   räumt also nur den schon-redundanten Cache-Eintrag.
> - **Einarbeitung:** Java-Patch oben (§4.11.6.3) ersetzt die alte
>   "Cache leeren"-Logik in Block 4. Die Aussage in §4.11.8 ("Cache leeren
>   räumt das Subdir mit ab") gilt nach diesem Patch **wieder**.

#### §4.11.7 Edge-Case-Tabelle

| # | Szenario | Verhalten | Begründung |
|---|---|---|---|
| 1 | `cacheDir.mkdirs()` failt (Storage voll) | `allocate()` wirft `IOException`; Resolver fängt → `ToastSink` zeigt "Speicher voll" | Reducer bleibt pure, View-Layer macht User-Feedback. Recording wird nicht gestartet. |
| 2 | `allocate()` zweimal hintereinander ohne Dispatch dazwischen (Doppel-Klick) | Beide Aufrufe erzeugen verschiedene Files (UUID-Suffix). Der erste File bleibt als Orphan im Cache liegen. | Orphan wird beim nächsten `onCreate` (cleanupOrphans) entfernt — kein dauerhaftes Leak. Doppel-Klick-Schutz selbst lebt in `dispatchInternal` (RecordingState wechselt Idle→Preparing → zweite StartRecording-Action ist `null` im Reducer-Arm). |
| 3 | OS löscht `cacheDir` zwischen `allocate()` und `MediaRecorder.start()` | `MediaRecorder.prepare()` failt mit IOException → Effect-Handler dispatcht `Action.RecordingAction.CancelRecording` (siehe §15.2 Reducer-Arm Preparing+CancelRecording) | Failure-Pfad via existierende Reducer-Logik, kein Spezialcode in der Factory. |
| 4 | Multi-Job: Job-N läuft (Transkription, hat eigenes audio_file_path) und User startet neue Aufnahme | `allocate()` erzeugt frisches File (UUID-Suffix) — kein Konflikt mit Job-N. | UUID-Suffix garantiert Eindeutigkeit pro Allokation. Nur 1 aktive Aufnahme zur Zeit (RecordingState ist Single-Instance) — kein Race zwischen 2 parallelen `allocate()`-Calls. |
| 5 | `cleanupOrphans` läuft parallel zu `allocate()` (sehr nah am Service-Start) | `cleanupOrphans` filtert über `referencedPaths`-Set; das frisch allozierte File ist (a) noch nicht in der DB, (b) hat aber einen UUID-Namen, der nicht in `referencedPaths` ist → könnte gelöscht werden | **Mitigation:** `cleanupOrphans` läuft in `onCreate` **asynchron in `serviceScope`** und ist typischerweise vor der ersten User-Interaktion fertig. Restliches Race-Risiko akzeptiert: im worst case erzeugt MediaRecorder die Datei beim `prepare()` neu (File-Path bleibt gleich, denn die UUID war bereits im `audioFile`-Argument). Pragma: kein Lock nötig. |
| 6 | App-Update (Versions-Wechsel) | Android löscht `cacheDir` NICHT bei App-Updates — überlebt. | Bestehende Sessions im DB (`audio_file_path` zeigt auf alten Pfad) bleiben gültig. Recovery-Path §11.6.2 deckt edge-cases (Pfad-Format-Wechsel) bereits ab via File-Existence-Check. Migrations-Detail siehe §4.11.6.2. |
| 7 | Cache-File überlebt nach erfolgreichem `persistFromCache` (s. §4.11.6.1) | Cache-Datei bleibt liegen, DB-Pfad zeigt jetzt auf `filesDir/recordings/{sid}.m4a` | Beim nächsten Service-Boot ist der Cache-File-Pfad NICHT in `referencedPaths` (DB zeigt auf filesDir) → `cleanupOrphans` löscht ihn. Akzeptiertes Delay (max. bis Service-Restart). KG-AFF-1 für Sofort-Delete-Variante. |
| 8 | `context.cacheDir == null` (sehr selten — nur in `Application.attachBaseContext`-Pfad theoretisch möglich) | `audioCacheDir`-Lazy wirft NullPointerException beim ersten `allocate()`/`cleanupOrphans()` | **Verhalten:** Im Pipeline-Service-onCreate ist `cacheDir` immer initialized (Service.onCreate läuft nach Application.onCreate). Da kein Aufruf vor Service.onCreate erfolgt, kann der Null-Fall hier nicht eintreten. Trotzdem defensiv: ein `requireNotNull(context.cacheDir)` im Konstruktor wäre sinnvoll, wenn ein Test-Path Null einspeist. Siehe KG-AFF-5. |
| 9 | `audio/`-Subdir ist ein Symlink (von außen platziert; theoretisch nur über `adb`/Root möglich, nicht über die App-Sandbox) | `mkdirs()` failt mit `IOException` (nicht durch das Symlink-Ziel hindurch) | `allocate()` propagiert IOException → Resolver-Toast. Nicht-realistisches Szenario auf nicht-rooted Devices (App-Sandbox erlaubt keinen Symlink-Inject in `cacheDir`). Kein Spezialhandling nötig. |
| 10 | DB-Read in Schritt 8 (`findAllAudioFilePaths`) failt (DB locked, Migration-Pending) | `try`-Block im Service-Wiring fängt → `Log.w` → Cleanup übersprungen | Service-Boot bleibt grün. Beim nächsten Boot wird der Cleanup erneut versucht. Idempotenz (Edge-Case #2 + #4): keine "verlorenen" Aufnahmen — Orphans leben bis erfolgreichem Cleanup. |
| 11 | App-Update mit alter `cacheDir/audio.m4a` außerhalb `audio/`-Subdir (siehe §4.11.6.2) | `cleanupOrphans` sieht sie **nicht** (Sub-Dir-Scope). Bleibt als "stranded file" bis User-"Cache leeren" oder OS-Wipe | Akzeptiert. Recovery-Pfad markiert die referenzierende Session als FAILED, sobald die Datei verschwindet. Migrations-Helper optional, siehe KG-AFF-2. |

#### §4.11.8 Design-Entscheidungen — kompakte Begründung

- **`cacheDir/audio/` (Subdir) statt `cacheDir/`:** isoliert Audio-Cleanup
  von Settings-/Export-Caches; das Audio-Subdir ist **selbst-aufräumend**
  via `cleanupOrphans` beim Service-Boot. Hinweis: die heutige
  "Cache leeren"-Schleife in `PreferencesFragment.java:272-289` ist
  **nicht rekursiv** und sieht das Subdir daher als nicht-leeren Ordner
  → `File.delete()` ist no-op auf Verzeichnissen. Siehe §4.11.6.3 +
  KG-AFF-3, falls die Preferences-UX auch das Subdir leeren soll.
- **`rec_{timestamp-ms}_{uuid8}.m4a` Naming:** Timestamp für Debug-
  Lesbarkeit (sortable in `ls -la`/Logs), UUID-Suffix für
  Collision-Safety (Multi-Job, R.8). Reine UUID wäre debug-feindlich;
  reiner Timestamp kollidiert in derselben ms.
- **`allocate()` legt File NICHT an:** MediaRecorder schreibt selbst —
  ein leeres `createNewFile()` wäre Orphan-Risiko bei `dispatch()`-
  Fehler. Nur `mkdirs()` für das Verzeichnis.
- **Interface + DI statt Kotlin-`object`:** Reducer-Tests und Resolver-
  Tests können `FakeAudioFileFactory` injizieren (z.B. mit
  `temporaryFolder.newFile()`). Singleton wäre nicht mockbar ohne
  `applicationContext`-Hack.
- **`cleanupOrphans` getrennt von `allocate`:** SRP. `allocate` läuft
  auf dem Main-Thread (Resolver), darf kein I/O-Listing machen;
  `cleanupOrphans` läuft in `Dispatchers.IO`, einmal pro Boot.
- **In `ModuleServices` injiziert:** dieselbe Quelle für (a) Pre-
  Dispatch-Resolver und (b) ggf. zukünftige EffectHandler-Aufrufe;
  vermeidet zweite DI-Drähte.
- **`cacheDir` statt `filesDir`:** transiente Phase (Cache); Persistenz
  übernimmt `RecordingRepository.persistFromCache` (unverändert).
  Konsequenz (OS-Wipe-Risiko) ist von §11.6.2 abgedeckt (Ghost-Sessions
  → FAILED + Error-Icon in History).

#### §4.11.9 Test-Strategie + Skelette

**Test-Stil im Projekt** (siehe
`app/src/test/java/.../core/RecordingStateControllerTest.kt`):
- **K-1:** handgeschriebene Fakes (keine Mockito).
- **K-4:** JVM-Unit-Tests, kein Robolectric, keine Android-Instrumentation.
- Android-Seams sind `open`/abstrahiert, damit Fakes substituieren können
  ohne `Context`/`Service`/Android-Framework.

##### Unit-Test 1: `CacheDirAudioFileFactoryTest.kt`

Reiner JVM-Test. Substituiert `Context.cacheDir` via Constructor-
Injection — die Default-Impl erlaubt das nicht direkt (heute nimmt sie
`Context` und liest `context.cacheDir`). Für die Testbarkeit
**Empfehlung:** Konstruktor erweitern auf:

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

Test-Skelett:

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

##### Unit-Test 2: `RecordingModuleAudioFileFactoryWiringTest.kt`

Verifiziert, dass der Reducer den `audioFile`-Argument-Pfad **unmodifiziert
durchschiebt** (Reducer ist pure, kein FS-Zugriff). Fake-Factory:

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

##### Integration-Test (Optional): `AudioFileFactoryE2ETest.kt`

End-to-end mit echtem `Context.cacheDir` über Robolectric oder
Instrumented-Test. **Empfehlung:** **nicht** vorrangig — der Unit-Test
1 deckt alle FS-Pfade ab; eine zusätzliche E2E würde nur den
`ApplicationProvider.getApplicationContext().cacheDir`-Path testen,
was bereits durch Android SDK garantiert ist.

Sollte aus Vorsicht ein Robolectric-Test gewünscht sein, lebt er unter
`app/src/test/java/.../core/AudioFileFactoryRobolectricTest.kt` mit
`@Config(sdk = [Build.VERSION_CODES.O])`-Annotation; aber ohne harten
Grund eher überspringen (zusätzliche Test-Runtime, keine zusätzliche
Coverage).

**Pflicht-Tests vor Block-4-Abschluss:**

| Test | Datei | Block | Rolle |
|---|---|---|---|
| `CacheDirAudioFileFactoryTest` | `app/src/test/.../core/` | Block 4 | Unit, FS-Verhalten |
| `RecordingModuleAudioFileFactoryWiringTest` | `app/src/test/.../core/` | Block 4 | Reducer-Vertrag |
| (optional) Robolectric-Test | `app/src/test/.../core/` | später | nur wenn Real-Context-Verhalten in Frage |

#### §4.11.10 Failure-Modi explizit

| # | Failure | Behandlung | Wer fängt? |
|---|---|---|---|
| F1 | `mkdirs()` failt (Storage voll, FS-Permission, ungewöhnliche FS-Layout) | `IOException` aus `allocate()` | Resolver in Spec 2 §10 → `services.toastSink.show(R.string.dictate_storage_full)` → `return null` → kein `dispatch()`. **Neue String-Resource nötig** in `res/values/strings.xml` (`dictate_storage_full`) + DE-Übersetzung. |
| F2 | `mkdirs()` failt **inside `cleanupOrphans`** (subdir nicht existierend, kein listFiles möglich) | `listFiles()` returnt `null` → `?: return` greift, kein delete-Loop | `cleanupOrphans()` selbst (defensiv). |
| F3 | `f.delete()` schlägt fehl (FS-Race, File ist von anderem Prozess offen) | `runCatching` fängt, `Log.w` schreibt Warnung, Schleife läuft weiter | innerhalb `cleanupOrphans()`. |
| F4 | DB-Read in `findAllAudioFilePaths()` failt (DB locked) | `try`-Block im Service-Wiring (siehe §4.11.5.3) fängt → `Log.w` → Cleanup übersprungen für diesen Boot | Service-onCreate-Wrapper. |
| F5 | Race: `allocate()` produziert Pfad, `cleanupOrphans` löscht ihn vor `MediaRecorder.prepare()` (Edge-Case #5) | `MediaRecorder.prepare()` failt → Effect-Handler dispatcht `CancelRecording` (existierender Pfad, §15.2) | Effect-Handler, nicht die Factory. |
| F6 | Symlink-Attacke auf `audio/` (nicht-Root-realistisch, App-Sandbox schützt) | `mkdirs()` failt (symlink durchgreift nicht) → IOException → wie F1 | Resolver-Toast. |
| F7 | UUID-Collision (Praktisch ausgeschlossen: 8-hex-UUID-Prefix hat 2^32 Space pro ms) | Falls doch — neuer File überschreibt alten Orphan, kein Datenverlust (alter Orphan war nicht persistiert) | n/a (Probabilistisch unmöglich.) |

**Wichtig F5 — Race-Window genauer:**

```
T0   Resolver ruft factory.allocate() → File-Path mit UUID-X erzeugt
T1   Resolver-Code dispatcht Action.StartRecording(audioFile=path)
T2   Reducer wechselt RecordingState.Idle → Preparing
T3   Effect.AllocateMediaRecorder läuft in Effects-Coroutine
T4   recordingManager.start(file=path) → MediaRecorder.prepare() → erzeugt File
```

Race-Window: T0 bis T4. `cleanupOrphans` läuft **einmal** im
`serviceScope` direkt nach Service-Boot. Wenn der User innerhalb von
~50 ms nach Service-Start eine Aufnahme startet (T0 vor Cleanup-Ende),
kann `cleanupOrphans` zwischen T0 und T4 keinen "Orphan" finden
(`allocate` erzeugt nichts → `listFiles` sieht den Pfad nicht). Wenn
`cleanupOrphans` **nach** T4 läuft (oder zwischen T4 und einem
späteren Pipeline-DB-Write), sieht es die Datei und sie ist nicht in
`referencedPaths` (DB hat noch keinen `audio_file_path` für die
laufende Session) → könnte gelöscht werden während die Aufnahme läuft.
**Aber:** `cleanupOrphans` läuft einmalig in `onCreate`, nicht
periodisch — sobald der eine Lauf fertig ist, existiert dieses Risiko
nicht mehr.

**Akzeptiertes Pragma:** Die Race-Wahrscheinlichkeit ist sehr niedrig
(Service-Boot + Sofort-Recording-Start innerhalb ~100 ms — User muss
extrem schnell sein). Im Fehlerfall: MediaRecorder schreibt weiter in
einen unlinkten File-Descriptor → Aufnahme geht verloren, Reducer
landet schließlich in `Preparing+CancelRecording` (über das
File-Existence-Check-Pfad bei `stop`-Submit). User merkt es ggf. an
einer fehlenden Aufnahme — selten genug, dass keine Lock-Logik nötig
ist (Edge-Case #5).

<!-- KNOWLEDGE-GAP: KG-AFF-4 – Race cleanupOrphans + concurrent allocate [RESOLVED 2026-05-11] -->
> **✅ Knowledge-Gap (KG-AFF-4): Reicht best-effort, oder lock/serialize gewünscht? — RESOLVED 2026-05-11**
>
> - **Was wir wussten:** Race-Window besteht zwischen T0 (`allocate()`) und
>   T4 (`MediaRecorder.prepare()` schreibt File). `cleanupOrphans` läuft
>   asynchron einmalig im selben Scope. Bei extrem schnellem User-Tap
>   nach Service-Boot (~50 ms) könnte `cleanupOrphans` den frisch allozierten
>   File löschen, bevor er in der DB als `audio_file_path` referenziert ist.
> - **Was wir nicht wussten:** Ob Status-quo (best-effort, kein Lock)
>   genügt, oder ob eine Lock-Mechanik nötig ist.
> - **Auflösung:** **Status-quo akzeptiert + Cutoff-Filter eingeführt.**
>   Begründung: Ein synchronisierender Lock (z.B. `CountDownLatch.await()`
>   im `allocate()`) wäre teuer und macht den Main-Thread-Resolver blockierbar
>   — unschön. Stattdessen filtern wir in `cleanupOrphans` Dateien aus, die
>   **jünger als 60 Sekunden** sind (Wall-Clock via `lastModified()`).
>   Damit ist das Race-Window geschlossen: jede frisch allozierte Datei
>   ist im Cutoff-Window, wird also nicht angefasst. Crash-Orphans aus
>   früheren Service-Lifecycles sind trivial älter als 60 Sekunden — sie
>   werden weiterhin gelöscht.
>
>   **Konkreter Code-Patch** in `CacheDirAudioFileFactory.cleanupOrphans`
>   (§4.11.3, Z. 1028-1038):
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
>   **Wie der Cutoff den Race deckt:** `allocate()` produziert nur einen
>   `File`-Path, ohne die Datei anzulegen. `MediaRecorder.prepare()` legt
>   sie an, mit OS-`lastModified() = now()`. Selbst bei extrem schnellem
>   User-Tap unmittelbar nach Service-Boot: die Datei wäre, falls sie
>   `cleanupOrphans` zu Gesicht bekäme, jünger als 60 s → wird übersprungen.
>   Trade-off: bei einem Crash-Orphan, der zufällig <60 s vor Service-Boot
>   entstand, bleibt er einen Boot-Zyklus länger liegen — akzeptiert,
>   weil der nächste Boot ihn dann mit Sicherheit fängt.
>
>   **Test-Skelett** (Ergänzung in `CacheDirAudioFileFactoryTest.kt`):
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
> - **Einarbeitung:** Code-Patch oben (§4.11.10) + Test-Skelett in §4.11.9
>   (zusätzlicher Test-Fall in `CacheDirAudioFileFactoryTest`). §4.11.3
>   Default-Impl wird in Block 4 mit dem Cutoff-Filter implementiert;
>   Edge-Case #5 (§4.11.7) und Failure F5 (§4.11.10) sind nach Block 4
>   durch den Cutoff faktisch unerreichbar (Race-Wahrscheinlichkeit → 0).

<!-- KNOWLEDGE-GAP: KG-AFF-5 – cacheDir-Null + Constructor-Defensive [RESOLVED 2026-05-11] -->
> **✅ Knowledge-Gap (KG-AFF-5): Defensive Null-Check für context.cacheDir? — RESOLVED 2026-05-11**
>
> - **Was wir wussten:** Android-Doc: `Context.getCacheDir()` kann theoretisch
>   `null` returnen wenn `Application.attachBaseContext` noch nicht durch ist
>   (extrem selten). Service.onCreate läuft strikt nach Application.onCreate,
>   damit ist `null` für unseren Aufruf-Pfad praktisch ausgeschlossen.
> - **Was wir nicht wussten:** Ob ein `requireNotNull` im Konstruktor sinnvoll
>   ist als Implementer-Sanity (Fail-fast vs. tote Defensive).
> - **Auflösung:** **Defensiver `requireNotNull` im `audioCacheDir`-Lazy-Init.**
>   Begründung: Klarheit > marginale Performance (1 Null-Check ist 1 Branch).
>   Fail-fast mit klarer Fehler-Meldung schlägt eine spät auftauchende
>   `NullPointerException` aus dem `File(null, AUDIO_SUBDIR)`-Konstruktor,
>   die für den Debugger zu spät kommt. In Tests, die einen bizarren
>   `cacheDirProvider = { null }` einspeisen (siehe §4.11.9
>   Constructor-Injection), greift der Check früh und liefert die korrekte
>   Diagnose.
>
>   **Konkreter Code-Patch** in `CacheDirAudioFileFactory` (§4.11.3 + §4.11.9
>   Constructor-Injection-Variante):
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
>   **Production-Construct** bleibt:
>   ```kotlin
>   CacheDirAudioFileFactory({ applicationContext.cacheDir }, System::currentTimeMillis)
>   ```
>
>   `requireNotNull` läuft beim ersten Read von `audioCacheDir` (lazy), nicht
>   bei Construct — damit ist auch Construction in einem bizarren
>   Test-Pre-State (z.B. ohne aufgesetzten Application-Context) safe; der
>   Check feuert erst, wenn die Factory tatsächlich benutzt wird.
> - **Einarbeitung:** Code-Patch oben — Default-Impl in §4.11.3 (Z. ~1006-1013)
>   und Test-Konstruktor-Skizze in §4.11.9 werden in Block 4 so umgesetzt.
>   Edge-Case #8 (§4.11.7) ist nach Block 4 mit konkretem Verhalten beschrieben:
>   `IllegalArgumentException` mit klarer Message statt diffuser NPE.

#### §4.11.11 Constants, Tunables, Logging

##### Constants (vereinheitlicht)

Die Default-Impl-Companion (§4.11.3) hält folgende `const val`-Konstanten:

```kotlin
companion object {
    private const val TAG = "AudioFileFactory"          // android.util.Log Tag
    private const val AUDIO_SUBDIR = "audio"            // Sub-Verzeichnis-Name unter cacheDir
    private const val PREFIX = "rec_"                   // File-Name-Prefix für cleanup-Filter
    private const val EXT = ".m4a"                      // MediaRecorder MPEG_4 + AAC Container
}
```

**Sichtbarkeit:** `private` ist Default. Wenn Tests darauf zugreifen
wollen (z.B. um eine `Orphan-Filename`-Heuristik zu verifizieren),
auf `internal` heben. Empfehlung: **Tests sollen NICHT auf die
Konstanten zugreifen** — sie sollten den **Vertrag** testen (Datei
matched/non-matched), nicht die spezifische String-Form. Damit bleibt
`private`.

**Keine Tunables nötig:** kein TTL für Files, keine Größenlimits,
keine Retention — alles durchsucht über DB-`referencedPaths`.

##### Logging-Vertrag

| Event | Tag | Level | Inhalt |
|---|---|---|---|
| Orphan-Cleanup gestartet | `"AudioFileFactory"` | DEBUG (optional) | `"cleanupOrphans: $N files in audio/"` — nur in Debug-Builds, vermeidet Produktions-Logspam |
| Einzelne delete()-Fail | `"AudioFileFactory"` | WARN | `"orphan cleanup failed: ${f.name}"` + Throwable. **Kein** absoluter Pfad geloggt (PII-konservativ, obwohl Cache-Files keine User-Daten enthalten). |
| Boot-Cleanup-Wrapper-Catch | `"DictatePipelineSvc"` | WARN | `"orphan cleanup failed at boot"` + Throwable. Quelle: DB-Read failt, oder DAO wirft. |
| `mkdirs` failt in `allocate` | n/a | n/a — wirft IOException, Caller loggt via ToastSink-Pfad | Reducer-Aware, nicht Factory-Aware. |

**Begründung:**

- Kein Pfad-Logging auf INFO-Level — Cache-Files sind nicht-secret,
  aber Pfad-Logging fördert keinen Debug-Wert (User kann mit absolutem
  Pfad nichts anfangen). Datei-Namen (relative Form) sind ausreichend.
- Log-Tag stabil + greppable (`AudioFileFactory`-Konvention im Projekt,
  konsistent mit `RecordingRepository`/`JobExecutor`/`PipelineOrchestrator`).
- Cleanup ist best-effort — ein WARN bei Einzel-delete-Fail darf das
  System nicht stoppen.
- Boot-Cleanup-Failure wird **nicht** als ERROR geloggt, weil der Boot
  trotzdem grün bleibt (Idempotenz beim nächsten Service-Start).

#### §4.11.12 Knowledge-Gaps — Übersicht

Diese Sektion sammelt alle KG-AFF-Marker für den Implementierer:

| ID | Titel | Block | Status / Auflösung |
|---|---|---|---|
| KG-AFF-1 | Sofort-Delete des Cache-Files nach Persist | Block 4 | ✅ RESOLVED 2026-05-11 — Sofort-Delete in `PipelineOrchestrator.persistNewSession` |
| KG-AFF-2 | Migration der alten `cacheDir/audio.m4a` | Block 4 | ✅ RESOLVED 2026-05-11 — `LegacyAudioFileMigration` + DAO-Query, Pref-Flag-idempotent |
| KG-AFF-3 | PreferencesFragment rekursiv | Block 4 | ✅ RESOLVED 2026-05-11 — `clearCacheRecursively`-Helper in Java |
| KG-AFF-4 | Race cleanupOrphans + concurrent allocate | Block 4 | ✅ RESOLVED 2026-05-11 — 60 s-Cutoff via `lastModified()`-Filter |
| KG-AFF-5 | cacheDir-Null + Constructor-Defensive | Block 4 | ✅ RESOLVED 2026-05-11 — `requireNotNull` im Lazy-Init |

Implementierer-Aktion: Alle KG-AFF-Marker sind aufgelöst — die jeweiligen
Code-Patches stehen direkt in den Markern (§4.11.6.1, §4.11.6.2, §4.11.6.3,
§4.11.10) als RESOLVED-Block. Block 4 implementiert sie 1:1.

---

## §5 Local-Binder API (F-8: Single Dispatch)

> **Architektur-Korrektur F-8 (2026-05-09):** Frühere Spec-Versionen hatten den
> LocalBinder mit ~25 typed Action-Methoden (`pauseRecording()`, `stopRecording()`,
> `confirmInsertion()`, …), parallel zu einer `Action`-Sealed-Class mit denselben
> Varianten — Doppel-Definition, DRY-Verletzung. Korrektur: LocalBinder schrumpft
> auf einen einzigen `dispatch(action: Action)`-Eingang. Alle UI-Events — auch
> View-Shown/Hidden — laufen über `dispatch(Action.…)`; **keine typed Forwarder-
> Methoden** (F-8-Geist).
>
> Vorteile:
> - **DRY**: Action-Liste lebt nur in der `Action`-sealed-class (Spec 2 §3.3)
> - **OCP**: neue Action = nur sealed-Klasse erweitern, kein Forwarder im Binder
> - **Compile-Sicherheit**: Kotlin-Compiler erzwingt im Reducer-`when`-Block die
>   Exhaustivität — keine Action wird vergessen

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

**IME-Service-Aufruf statt typed Forwarder:**

```kotlin
// Statt pipeline?.notifyImeViewShown() :
pipeline?.dispatch(Action.ViewModeAction.OnImeViewShown)
// Statt pipeline?.notifyImeViewHidden() :
pipeline?.dispatch(Action.ViewModeAction.OnImeViewHidden)
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

<!-- FIX: Issue PENDING-2 / Block-3 Resolved – SessionStatus-Erweiterung konkret spezifiziert -->

**M3→M4 leistet zwei Schema-Erweiterungen zusammen:**

1. **Neue Spalte `inserted_at`** — Marker für "Pipeline COMPLETED, aber Text noch nicht im Editor"
   (Restart-Button-Logik + Cleanup-Policy, siehe §6.3).
2. **Erweiterung der `status`-CHECK-Constraint** um `RECORDING` + `TRANSCRIBING`, damit
   Live-Pipeline-Stati (heute nur in `ActiveJobRegistry`, prozesslokal) den
   OOM-Death-Recovery-Pfad in §6.3 / §11.6 unterstützen.

> [!IMPORTANT]
> Punkt 2 erzwingt eine **table-recreate-Migration** (`CREATE TABLE … _new` + `INSERT … SELECT`
> + `DROP` + `RENAME`), weil SQLite CHECK-Constraints nicht via `ALTER TABLE` ändern kann.
> Damit ist M3→M4 **nicht mehr rein additiv** im Sinne von D8. Trade-off ist in §11.7 +
> Architektur-Entscheidung D8 (siehe §2) aktualisiert: die Migration läuft trotzdem
> in einer einzigen Room-Transaktion (atomar, abort-on-failure). Rollback bleibt sicher.

**Begründung der Enum-Erweiterung (Lifecycle-Inventar):**

| Status (neu) | Persistiert bei | Recovery-Verhalten | Sichtbar in HistoryActivity? |
|---|---|---|---|
| `RECORDING` | Recording-Start (Mic-Open, vor First-Frame) | Boot → `FAILED` + cleanup audio file (siehe §6.3) | Nur im OOM-Death-Window (vor `recoverFromDb`) — defensiver Fallback-Badge (siehe §8 + HistoryAdapter) |
| `RECORDED` | Recording-Stop (Audio-File geschlossen) | Boot → bleibt `RECORDED`, taucht als `pendingSessions` auf (Resume-Button) | ja (bestehend, Pending-Icon) |
| `TRANSCRIBING` | Pipeline-Start (Audio-Upload begonnen) | Boot → Downgrade auf `RECORDED` (siehe §6.3; D4 / OPEN-4: **kein Auto-Resume** — User klickt Restart) | wie RECORDING |
| `COMPLETED` | Pipeline-Done (Result-Text in DB) | Boot mit `inserted_at IS NULL` → `pendingSessions` (Insertion-Button) | ja (bestehend, kein Icon) |
| `FAILED` | API/Quota/Network-Error oder Recovery-Promote von RECORDING | terminal, User-Wahl Reprocess via HistoryDetail | ja (bestehend, Error-Icon) |
| `CANCELLED` | User-Explicit-Cancel | terminal | ja (bestehend, Cancel-Icon) |

**State-Machine (Forward-Path + Recovery-Path, ASCII):**

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

**Persistierung-Granularität:** RECORDING/TRANSCRIBING werden **bei State-Transition geschrieben**
(siehe §6.2 Checkpoint-Hooks). Innerhalb von TRANSCRIBING gibt es **keinen Sub-Status-Update pro
Pipeline-Step** — Step-Granularität lebt bereits in `processing_steps` + `transcriptions` (siehe
`MIGRATION_1_2` in MigrationTo3.kt-Schwesterfile). Die `status`-Spalte trägt nur den Phasen-Marker.

**Konkrete Migration-Implementation** (Datei: `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo4.kt`, NEU):

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
> **Warum kein `index_sessions_inserted_at`?** Die neue `inserted_at`-Spalte wird von zwei Queries gelesen: `findPendingInsertion()` (WHERE `inserted_at IS NULL`) und `deleteInsertedOlderThan(cutoff)` (WHERE `inserted_at < :cutoff`). Beide laufen nicht auf dem Hot-Path (Recovery beim Service-Boot bzw. Cleanup beim Service-Idle-Stop, je 1× pro Service-Lifecycle) und die `sessions`-Tabelle ist im erwarteten Use-Case klein (typisch <1k Rows, Power-User <10k). Ein zusätzlicher Index würde Insert/Update-Cost erhöhen ohne signifikanten Read-Gewinn bei dieser Größenordnung. Falls Telemetrie später zeigt, dass `findPendingInsertion()` zur Boot-Bremse wird, ist der Index als post-hoc-Migration (M4→M5) ergänzbar.
>
> SessionEntity.kt-Annotation bleibt entsprechend bei 5 Indices (`parent_session_id`, `type`, `created_at`, `origin`, `status`) — kein `Index("inserted_at")` ergänzen.

**Erweiterung von `SessionStatus.kt`** (`app/src/main/java/net/devemperor/dictate/database/entity/SessionStatus.kt`):

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

**State-Machine-Tabelle (Action → Next-Status):**

| Aktueller Status | Action | Neuer Status | DB-Update-Statement |
|---|---|---|---|
| `Idle` (no row) | Recording-Start | `RECORDING` | `INSERT sessions (... status='RECORDING' ...)` |
| `RECORDING` | Recording-Stop (normal) | `RECORDED` | `UPDATE … SET status='RECORDED', audio_file_path=? WHERE id=?` |
| `RECORDING` | User-Cancel | `CANCELLED` | `UPDATE … SET status='CANCELLED' WHERE id=?` |
| `RECORDED` | Pipeline-Start (submit) | `TRANSCRIBING` | `UPDATE … SET status='TRANSCRIBING' WHERE id=?` |
| `RECORDED` | User-Cancel | `CANCELLED` | `UPDATE … SET status='CANCELLED' WHERE id=?` |
| `TRANSCRIBING` | Pipeline-Done | `COMPLETED` | `UPDATE … SET status='COMPLETED', final_output_text=? WHERE id=?` |
| `TRANSCRIBING` | API-/Network-Error | `FAILED` | `UPDATE … SET status='FAILED', last_error_type=?, last_error_message=? WHERE id=?` |
| `TRANSCRIBING` | User-Cancel | `CANCELLED` | `UPDATE … SET status='CANCELLED' WHERE id=?` |
| `COMPLETED` | Text inserted in editor | `COMPLETED` (unverändert) | `UPDATE … SET inserted_at=now WHERE id=?` |
| `FAILED` / `CANCELLED` | (terminal) | — | — |

<!-- FIX: Phase-B S-1 (2026-05-13) – Status-Writes wandern aus dem monolithischen PipelineStateManager in Modul-EffectHandler (RecordingModule + PipelineModule, §15). -->
Status-Writes leben in den jeweiligen Modul-EffectHandlers (`RecordingModule.runEffect(Effect.PersistStatus)` für RECORDING/RECORDED, `PipelineModule.runEffect(Effect.PersistStatus)` für TRANSCRIBING/COMPLETED/inserted_at; siehe §6.2 + §15). `SessionManager` exposed dazu zwei neue Methoden neben dem bestehenden `finalizeCompleted/Cancelled/Failed`.

**Vorbild-Stil** (siehe `app/src/main/java/net/devemperor/dictate/core/SessionManager.kt:97-111` — die heute existierenden `finalizeCompleted/Cancelled/Failed` sind 1-3-Zeilen-Wrapper über `sessionDao.updateStatus(+updateError)`; kein Try/Catch, keine eigene Transaktion, kein Coroutine-Suspend — die Methoden sind synchron, weil heutige Caller via `dbExecutor` (siehe `DictateInputMethodService.java:248`) auf einen IO-Thread dispatchen):

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

> **Konsistenz mit `finalize*`:** Keine `suspend fun` (die existierenden `finalize*`
> sind ebenfalls plain functions — siehe `SessionManager.kt:97`); kein eigenes
> `db.runInTransaction { ... }` (Single-Update, atomar auf SQLite-Statement-Ebene
> — gleicher Pattern wie `finalizeCancelled` mit 2 DAO-Calls, der KEINE
> Transaktion nutzt, weil ein Zwischenfehler `last_error_*` lediglich stale lässt
> ohne State-Inkonsistenz). Begründung in `SessionManager.kt:102` (Kommentar "CA-1").

> **Hinweis: Neue Initial-Status-Übergaben.** `createSession(initialStatus = …)`
> existiert bereits (siehe `SessionManager.kt:54`) und akzeptiert jeden `SessionStatus`
> — keine API-Erweiterung nötig. Recording-Start ruft `createSession(initialStatus = RECORDING)`,
> Reprocess-aus-History (status=`RECORDED`) ruft `createSession(initialStatus = RECORDED)`.

**Recovery-Tabelle (Boot-Status → Recovery-Action):** Siehe §6.3 unten — die Tabelle ist dort
gekoppelt mit dem `recoverFromDb`-Code.

#### §6.1.1 ActiveJobRegistry-Strategie nach M4

<!-- FIX: Block-3 Detail-Vertiefung – ActiveJobRegistry-Rolle nach Persistierung präzisieren (siehe KG-SST-1 für offene Konsumentenliste) -->

`ActiveJobRegistry` (`app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistry.kt:20-65`) ist heute Single-Source-of-Truth für "läuft gerade etwas?". Mit M4 wandern RECORDING/TRANSCRIBING zusätzlich in die DB — das wirft die Frage auf: bleibt der Registry, oder fliegt er raus?

**Entscheidung: `ActiveJobRegistry` bleibt.** Begründungen:

1. **Performance.** Der Registry exponiert `StateFlow<Map<String, JobState>>` (heute beobachtet von `HistoryAdapter.applyStatusBadge` Z. 122 — Spinner-Overlay). Eine DB-Polling-Lösung pro Frame ist nicht realistisch.
2. **Job-State-Detail.** Der Registry hält `JobState.Running(currentStep, totalSteps, …)` — die DB hält nur den Phasen-Marker (`status`). Step-Granularität soll NICHT in die DB (würde §6.1 "Persistierung-Granularität"-Aussage brechen).
3. **Single-Job-Lock.** Der Registry serialisiert via `@Synchronized register` — eine zweite parallel-startende Pipeline würde `false` zurückbekommen. Diese Logik bleibt prozesslokal.

**Was ändert sich:**

| Aspekt | Heute (M3) | Nach M4 |
|---|---|---|
| Quelle für "läuft gerade etwas?" auf HistoryActivity | `ActiveJobRegistry.isActive(sessionId)` | unverändert (Registry bleibt SSoT für Runtime-Detail). |
| Quelle nach Process-Death | leer (Registry ist prozesslokal) | DB: `status IN (RECORDING, TRANSCRIBING)` löst den Recovery-Pfad aus (§6.3). |
| Konsistenz Cache ↔ DB | n/a (Registry und DB sind orthogonal) | Beide Schreibungen erfolgen im **selben Reducer-Hook** (siehe §6.2). **Reihenfolge: DB first, dann Cache** (umgedreht via KG-SST-5 RESOLVED). Falls DAO-Call fehlt: weder Cache noch DB sind aktualisiert — Pipeline läuft funktional weiter, Reducer-Tick versucht erneut. Falls Cache-Update fehlt (extrem unwahrscheinlich, da `ActiveJobRegistry.update` synchron auf In-Memory-Map): DB ist konsistent, Cache zeigt veralteten Step-Counter — UI-Annotation veraltet eine Tick lang, behebt sich beim nächsten Update. Process-Crash dazwischen: DB konsistent, Cache wird beim Process-Restart neu initialisiert (process-local). |

**Konsumenten von `ActiveJobRegistry` heute (vollständige Liste, grep-verifiziert 2026-05-11):**

| # | Datei : Zeile | API-Call | Was er macht | M4-Strategie (Cache vs. DB) |
|---|---|---|---|---|
| 1 | `core/JobExecutor.kt:96` | `ActiveJobRegistry.register(sessionId, initial)` | **Producer** — single-job-lock + Initial-`JobState.Running` schreiben. Returns `false` falls ein anderer Job läuft. | Bleibt Cache (single-job-lock-Pattern ist process-local; DB hätte keinen Mehrwert) |
| 2 | `core/JobExecutor.kt:164` | `ActiveJobRegistry.unregister(sessionId)` | **Producer** — finally-Block: cleanup auf Pipeline-Ende (egal ob completed/cancelled/failed) | Bleibt Cache |
| 3 | `history/HistoryAdapter.java:122` | `ActiveJobRegistry.INSTANCE.isActive(session.getId())` | **Consumer** — Spinner-Overlay in der History-Liste (Runtime-Overlay über persistiertem Status) | Bleibt Cache. Begründung: pro RecyclerView-Bind ein Lookup; ein DB-Read würde N+1 auf dem UI-Thread bedeuten. |
| 4 | `history/HistoryActivity.java:148` (+`:24,25` Imports) | `ActiveJobRegistryObserver.observe(this, snapshot -> refreshData())` | **Consumer** — lifecycle-scoped Reactive-Refresh der gesamten Liste, wenn Jobs starten/stoppen | Bleibt Cache. State-Read aus `DictateUiState` würde gehen, aber StateFlow-Observer + `refreshData()` ist exakt dieselbe Mechanik mit weniger Hops. |
| 5 | `history/HistoryDetailActivity.java:208` (+`:32,33` Imports) | `ActiveJobRegistryObserver.observe(this, snapshot -> { if (sessionId != null) loadSession(); })` | **Consumer** — reaktiver Detail-Refresh: Badge + Disable-Reprocess-Button updates | Bleibt Cache (gleicher Grund wie #4) |
| 6 | `history/HistoryDetailActivity.java:285` | `boolean jobActive = ActiveJobRegistry.INSTANCE.isActive(sessionId)` | **Consumer** — `canReprocess`-Vorberechnung im `buildRecordingPipeline()`-Pfad: solange Job aktiv, kein Reprocess-Button | Bleibt Cache (Read im UI-Build-Pfad) |
| 7 | `history/HistoryDetailActivity.java:454` | `ActiveJobRegistry.INSTANCE.isAnyActive()` | **Consumer** — `startHistoryReprocess()`: Reprocess-Klick wird verworfen, falls schon ein Job läuft (Toast "job already active") | Bleibt Cache (Klick-Handler — synchroner Read, kein DB-Hop vertretbar) |
| 8 | `core/DictateInputMethodService.java:2361` | `ActiveJobRegistry.INSTANCE.isAnyActive()` | **Consumer** — `startResumeJob()`: Resend-Klick verwirft, falls Job läuft | Bleibt Cache (gleicher Grund wie #7) |
| 9 | `core/DictateInputMethodService.java:2594` | `ActiveJobRegistry.INSTANCE.isActive(activeSessionId)` | **Consumer** — Cancel-Pfad: entscheidet, ob `JobExecutor.cancel()` (Registry-known) oder Legacy `pipelineOrchestrator.cancel()` aufgerufen wird | Bleibt Cache (Cancel-Routing-Logik, process-local) |
| 10 | `core/ActiveJobRegistryObserver.kt:23-37` | `ActiveJobRegistry.state.collect { snapshot -> … }` (Z. 37) | **Bridge** — Java-friendly Wrapper für Activity-lifecycle-scoped Observation der `StateFlow`. Nur Bridge-Code; eigentliche Konsumenten sind #4 und #5. | Bleibt — wird durch State-Read aus `DictateUiState` perspektivisch ersetzt, nicht aber in Block 3 (würde Activity-Refactor erzwingen). |
| 11 | `database/entity/SessionStatus.kt:6` (KDoc) | n/a — Doc-Anker | erklärt, warum `RECORDING/TRANSCRIBING` heute NICHT in der DB stehen | Wird mit M4 obsolet — KDoc anpassen (kein Logik-Touch) |
| 12 | `core/JobExecutor.kt:21,294` und `core/PipelineOrchestrator.kt:124,204,845` (KDoc/Inline-Kommentare) | n/a — Doku-Anker | erklären die Pre-Allocation-Beziehung Registry ↔ Orchestrator | unverändert |
| 13 | `preferences/versioned/VersionedPluginRegistry.kt:70` (KDoc) | n/a — Doc-Anker | nur Referenz auf das `resetRegistry()`-Pattern (Test-Helper-Convention) | unverändert |

**Summary:**

- **2 Producer-Sites** (`JobExecutor.kt:96, :164`) — bleiben unverändert. Producer-Sites werden in M4 NICHT um `SessionDao.updateStatus`-Calls **ergänzt** — der DB-Write für RECORDING/TRANSCRIBING wandert in den `DictateOrchestrator`-Reducer-Hook (§6.2), nicht in den `JobExecutor`. Begründung: `JobExecutor` kennt nur `JobRequest`/`JobState`, nicht `SessionStatus`; ihn an `SessionManager` zu koppeln wäre LISKOV-Bruch + Test-Aufwand.
- **7 Consumer-Sites** (#3-#9) — bleiben unverändert. Alle lesen den Registry als prozesslokalen Cache.
- **1 Bridge** (#10) — bleibt.
- **3 Doku-Anker** (#11-#13) — KDoc-Update nur für `SessionStatus.kt:6` (#11).

Kein vergessener Konsument in `rewording/`, `widget/`, `keyboard/`, `settings/`, `onboarding/` (grep auf das gesamte `app/src/main/java/` ergab nur diese Sites).

**Refactor-Entscheidung:** **Kein Refactor an `ActiveJobRegistry` selbst.** Die Doppel-Truth (Cache + DB) ist akzeptiert und durch §6.2 + KG-SST-5 (DB-first, dann Cache) drift-frei.

**Persistenz-Vertrag (Cache ↔ DB) — siehe KG-SST-5 (RESOLVED):**

> **DB first, dann Cache.** Im Checkpoint-Hook (§6.2) schreibt der jeweilige Modul-EffectHandler (`RecordingModule.runEffect(Effect.PersistStatus)` bzw. `PipelineModule.runEffect(...)`) zuerst die DB (`SessionDao.updateStatus(TRANSCRIBING)` / etc.), dann updated er den Registry (`ActiveJobRegistry.update(sessionId, newState)`). <!-- FIX: Phase-B S-1 (2026-05-13) – PipelineStateManager → Modul-EffectHandler --> Bei einem Crash zwischen DB-Write und Cache-Write: DB ist konsistent, Cache enthält ggf. einen veralteten Eintrag — der wird beim nächsten App-Start eh leer initialisiert (Registry ist process-local, kein langfristiger Drift möglich). Die obere Zeile in der "Was ändert sich"-Tabelle ("erst Registry, dann DAO-Call") ist durch KG-SST-5 (RESOLVED 2026-05-11) **umgekehrt** worden — siehe §6.2 Persistenz-Vertrag (R.17 erweitert) und KG-SST-5-Marker unten in §11.7.0.

**Hinweis für Block-3-Implementer:**

- `SessionStatus.kt:6`-KDoc anpassen (z.B. "RECORDING/TRANSCRIBING leben jetzt in DB + Registry; Registry bleibt als Performance-Cache + Single-Job-Lock").
- `JobExecutor.start()` und `JobExecutor.finally` **nicht** anfassen.
- DB-Status-Update für RECORDING/TRANSCRIBING in `DictateOrchestrator.Effect.Persist*` einbauen, nicht direkt in `JobExecutor`.

<!-- KNOWLEDGE-GAP: KG-SST-1 – Vollständige ActiveJobRegistry-Konsumentenliste [RESOLVED 2026-05-11] -->
> **✅ Knowledge-Gap (KG-SST-1): Vollständige `ActiveJobRegistry`-Konsumentenliste — RESOLVED 2026-05-11**
>
> - **Was wir wussten:** `HistoryAdapter.java:122` und das Single-Job-Lock-Pattern im IME-Service nutzen den Registry. Die `register/update/unregister`-Aufrufe stammen vermutlich aus `JobExecutor.kt` — nicht verifiziert per `grep`.
> - **Was wir nicht wussten:** Gibt es weitere Konsumenten in `core/`, `rewording/`, `widget/`?
> - **Auflösung:** `grep -rn "ActiveJobRegistry\|activeJobRegistry" app/src/main/java/` (29 Hits, davon 13 unique Sites — siehe Tabelle oben). Befund: **9 Logik-Sites** (2 Producer in `JobExecutor.kt`, 7 Consumer in `HistoryAdapter`/`HistoryActivity`/`HistoryDetailActivity`/`DictateInputMethodService`), **1 Bridge** (`ActiveJobRegistryObserver.kt`), **3 Doku-Anker** (`SessionStatus.kt:6`, `JobExecutor.kt:21,294`+`PipelineOrchestrator.kt:124,204,845`, `VersionedPluginRegistry.kt:70`). **Kein vergessener Konsument** in `rewording/`, `widget/`, `keyboard/`, `settings/`, `onboarding/`. Entscheidung: alle 9 Logik-Sites bleiben Cache-Reads — keine Migration auf `DictateUiState.pendingSessions[].status` in Block 3. Begründung: Use-Cases sind `isActive`/`isAnyActive` (lock-check + UI-overlay) — DB-Read gäbe keinen Mehrwert, würde aber N+1 auf RecyclerView-Bind kosten.
> - **Einarbeitung:** §6.1.1 Konsumenten-Tabelle (oben) ersetzt die kurze Stichpunktliste. Persistenz-Vertrag-Zeile in der "Was ändert sich"-Tabelle wurde umgedreht (DB-first via KG-SST-5).

#### §6.1.2 Schema-Version + addMigrations — verifizierter Zustand

<!-- FIX: Block-3 Detail-Vertiefung – konkrete Versionsnummer (heute v3) + Wiring-Anchor (DictateDatabase.kt:38,73) verifiziert -->

- **Heutige Version:** `DictateDatabase.kt:38` — `version = 3` (verifiziert per `Read`).
- **Heutige addMigrations:** `DictateDatabase.kt:73` — `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`.
- **M4-Diff:** `version = 4` + `.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)`.
- **Migration-Klasse:** `androidx.room.migration.Migration` (siehe `MigrationTo3.kt:3` für den Import-Anchor).
- **`exportSchema = true`** ist bereits aktiv (`DictateDatabase.kt:39`) — Schema-JSON `app/schemas/.../DictateDatabase/4.json` wird vom Room-Compiler beim Build erzeugt und kann im PR-Review verifiziert werden (Lesson-Learned aus MigrationTo3, siehe Kommentar Z. 38-42 dort).

#### §6.1.3 Konsumenten der `SessionStatus`-Enum — vollständiger Update-Audit

<!-- FIX: Block-3 Detail-Vertiefung – when-Konsumenten an 4 Stati gegen Erweiterung auf 6 Stati abgesichert (siehe KG-SST-4 für Lint-Regel-Followup) -->

Die heutige `SessionStatus`-Enum hat 4 Werte (`RECORDED, COMPLETED, FAILED, CANCELLED`). Mit M4 kommen `RECORDING` + `TRANSCRIBING` dazu. Jeder exhaustive `when`/`switch` auf `SessionStatus` muss um die neuen Branches erweitert werden — sonst Compile-Error (Kotlin: `'when' expression must be exhaustive`) oder stiller Default-Fall (Java: kein Compile-Fehler, aber lint/missing-case-Warning).

**Vollständige Konsumentenliste (grep-verifiziert):**

| File | Position | Sprache | Heute-Verhalten | M4-Update |
|---|---|---|---|---|
| `core/ResendStatusDispatcher.kt:57-71` | `when (status)` | Kotlin (exhaustive) | 4 Branches, kein Else | + `RECORDING` → `NoOp` (sollte UI-wise nie sichtbar werden — defensive), + `TRANSCRIBING` → `NoOp` (User soll Pipeline nicht doppelt starten — Single-Job-Lock greift sowieso, aber wir wollen Klick auf Resend-Button während laufender Pipeline ignorieren) |
| `history/HistoryAdapter.java:130-159` | `try { SessionStatus.valueOf(...) } catch (IllegalArgumentException) { RECORDED }` + `switch (status)` | Java (kein Exhaustiveness-Check); **existierender try/catch-Wrapper Z. 131–135 fängt unbekannte Strings als `RECORDED`-Fallback** (Downgrade-/Restore-Verträglichkeit) — der `switch` sieht damit immer einen gültigen Enum-Wert, und der `default:`-Branch ist heute **unerreichbar** | + `case RECORDING:` → Spinner + Label "Wird aufgenommen" (kommt nur im OOM-Death-Fenster vor — vor `recoverFromDb` läuft); + `case TRANSCRIBING:` → Spinner + Label "Wird transkribiert" (gleiches Fenster). Beide branches **können** strenggenommen nie sichtbar werden (Recovery promoted), aber defensiv pflegen. <!-- FIX: Phase-B S-2 (2026-05-13) – Wrapper-Doppel-Sicherung dokumentiert: try/catch fängt unbekannte Strings, default-case fängt "neue Enum-Werte ohne switch-Update". Beide Schichten bleiben — siehe Begründung unter dem Snippet. --> |
| `history/HistoryDetailActivity.java:287-299` | `try { SessionStatus.valueOf(...) } catch (IllegalArgumentException) { RECORDED }` + Whitelist `canReprocess = (status IN {RECORDED, FAILED, CANCELLED, COMPLETED})` | Java | 4 Status werden in einer **Whitelist** geprüft — neue Stati (RECORDING, TRANSCRIBING) landen automatisch im `canReprocess = false`-Pfad **ohne** Code-Änderung. Try/catch-Wrapper Z. 288–292 fängt zusätzlich unbekannte Status-Strings als `RECORDED`-Fallback. | **Keine Code-Änderung nötig** — die Whitelist-Logik ist bereits defensiv gegen neue Status-Werte. Hinzufügen von `status != RECORDING && status != TRANSCRIBING` wäre **redundant** und Code-Lärm. <!-- FIX: Phase-B S-2 (2026-05-13) – frühere Anweisung "explizit ausschließen" war redundant gegenüber der bestehenden Whitelist-Logik. --> |
| `history/HistoryDetailActivity.java:590` | Konstantenliste `SessionStatus.RECORDED` (Resume-Button) | Java | Hard-coded auf `RECORDED` | unverändert (Resume-Button nur für `RECORDED`-Sessions). |
| `ai/AIProviderException.kt` (`ErrorType.UNKNOWN`-Mapping) | n/a | n/a | n/a | unverändert — kein direkter `SessionStatus`-Touchpoint. |

**Konkreter Patch — `HistoryAdapter.java:136-159` (in Java, additiv):**

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

> **Lint-Backup (KG-SST-4 RESOLVED):** Zusätzlich aktiviert `app/build.gradle`
> die Android-Lint-Regel `EnumSwitch` als Error (heute nur Warning), damit ein
> vergessener `case` schon beim Build sichtbar wird — nicht erst zur Laufzeit.
> Siehe KG-SST-4-Marker unten in §11.7.0 für den Gradle-Snippet.

<!-- FIX: Phase-B S-2 (2026-05-13) – Doppel-Sicherung try/catch + default: explizit dokumentiert. -->
> **Doppel-Sicherung try/catch + `default:` — keine Redundanz, sondern zwei Failure-Modes:**
>
> Der bestehende `try { SessionStatus.valueOf(session.getStatus()) } catch (IllegalArgumentException e) { status = SessionStatus.RECORDED; }`-Wrapper (HistoryAdapter Z. 131–135, **bleibt erhalten**) und der neue `default:`-Branch im `switch` adressieren **disjunkte** Failure-Modes:
>
> | Failure-Mode | Auslöser | Gefangen durch |
> |---|---|---|
> | **DB-String unbekannt** (z.B. Downgrade: v4-App hat RECORDING/TRANSCRIBING geschrieben, dann auf v3-App zurück, die diese Enum-Werte nicht kennt) | `SessionStatus.valueOf("RECORDING")` wirft `IllegalArgumentException` | **try/catch-Wrapper** → fallback zu `RECORDED` (Pending-Badge, kein UI-Bruch) |
> | **Enum erweitert, switch nicht** (z.B. v5 fügt `PROCESSING_REWORDING` hinzu, jemand vergisst den `case`) | `SessionStatus.valueOf("PROCESSING_REWORDING")` returnt gültigen Enum-Wert, aber kein `case` matched | **`default:`-Branch** → `Log.wtf` + `GONE` (kein UI-Crash, Crashlytics-Signal) |
>
> Beide Schichten sind unabhängig: der try/catch fängt String→Enum-Boundary-Fehler, der default: fängt Enum→switch-Drift. Den try/catch zu entfernen würde die Downgrade-Verträglichkeit brechen (User-DB mit RECORDING-Zeile crasht beim ersten History-Scroll auf älterer App). Den default: wegzulassen würde silent-empty-Badges bei zukünftigen Enum-Erweiterungen erzeugen.

<!-- FIX: Phase-B S-3 (2026-05-13) – Naming-Kollisions-Warnung: zwei `ResendAction` koexistieren. -->
> **Naming-Kollision (bewusst akzeptiert, dokumentiert):** Es gibt zwei unabhängige
> Sealed-Klassen mit dem Namen `ResendAction` im Projekt:
> 1. `net.devemperor.dictate.core.ResendAction` (heutiger Code, `ResendStatusDispatcher.kt`):
>    interner Entscheidungstyp des Status-Dispatchers — Varianten `Insert(output, sessionId)`,
>    `Resume(sessionId)`, `NoOp`. **Bleibt erhalten** als Implementation-Detail des
>    Dispatchers; keine Refactor-Touchpoints.
> 2. `net.devemperor.dictate.state.Action.ResendAction` (neuer Code, Spec 2 §3.3):
>    Orchestrator-Action — Varianten `ResendLastAudio`, `ResendLastAudioLong`,
>    `ResendCooldownExpired`, `MarkLastAudio(exists)`. **Kein** `NoOp` (R.3 nullable
>    Resolver-Idiom).
>
> Die beiden Typen leben in **unterschiedlichen Packages** (`core` vs. `state`); der
> Kotlin-Compiler weist Cross-Use als Type-Mismatch ab. Bei der Block-3-Implementation
> ist der `ResendStatusDispatcher`-Patch unten **explizit** auf die `core.ResendAction`-
> Variante bezogen — kein Action-Forwarding nach `Action.ResendAction.*`. Eine künftige
> Refactor-Iteration könnte den Dispatcher in einen `ResendModule.runEffect`-Pfad
> integrieren (dann fiele `core.ResendAction` weg) — aktuell Out-of-Scope.

**Konkreter Patch — `ResendStatusDispatcher.kt:57-71`** (referenziert `core.ResendAction`, nicht `Action.ResendAction`):

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

**Neue String-Ressourcen** (`app/src/main/res/values/strings.xml`, ergänzen — defensive, falls Branch je sichtbar wird):

```xml
<string name="dictate_status_recording">Wird aufgenommen…</string>
<string name="dictate_status_transcribing">Wird transkribiert…</string>
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

### §6.2 Checkpoint-Hooks

<!-- FIX: Issue PENDING-2 / Block-3 Resolved – Tabelle gegen tatsächliche Enum-Werte ausgerichtet (kein TRANSCRIBED/INSERTED-Pseudostatus mehr) -->

Im `DictateOrchestrator` (Composition Root) wird jede State-Transition mit einem DB-Update versehen.
Die Status-Werte unten matchen die Enum-Definition in `SessionStatus.kt` exakt (siehe §6.1) — es
gibt **keinen** Pseudostatus `TRANSCRIBED` (das wäre `COMPLETED` mit `final_output_text != NULL`)
oder `INSERTED` (das wird via `inserted_at IS NOT NULL` markiert):

| State-Transition | DB-Schreibung | SessionManager-Methode |
|------------------|---------------|------------------------|
| Recording-Start | `INSERT sessions (... status='RECORDING' ...)` | `createSession(initialStatus = RECORDING)` |
| Recording-Stop | `UPDATE sessions SET status='RECORDED', audio_file_path=? WHERE id=?` | `transitionRecorded(id, path)` (neu) |
| Pipeline-Start | `UPDATE sessions SET status='TRANSCRIBING' WHERE id=?` | `transitionTranscribing(id)` (neu) |
| Pipeline-Done | `UPDATE sessions SET status='COMPLETED', final_output_text=? WHERE id=?` | `finalizeCompleted(id)` (+ `updateFinalOutputText`) |
| Insertion-Done | `UPDATE sessions SET inserted_at=now WHERE id=?` | `markInserted(id, ts)` (neu, §6.1) |
| Cancel | `UPDATE sessions SET status='CANCELLED' WHERE id=?` | `finalizeCancelled(id)` |
| Error | `UPDATE sessions SET status='FAILED', last_error_type=?, last_error_message=? WHERE id=?` | `finalizeFailed(id, type, msg)` |

<!-- FIX: Issue 2.1.21 / R.17 – Idempotenz-/Reihenfolge-/Failure-Vertrag explizit -->
<!-- FIX: Phase-C C-2 (2026-05-14) – Doppel-Reihenfolge-Klausel "State-First" vs. "DB → Cache"
     explizit disambiguiert: zwei verschiedene "Caches" auf zwei verschiedenen Layern. Beide
     Klauseln koexistieren konfliktfrei, aber der Lese-Anchor ohne Header war verwirrend. -->
**Persistenz-Vertrag (R.17):**

> **Zwei Reihenfolge-Klauseln, zwei Layer — kein Widerspruch:**
> - **State-First (Bulletpoint 2)** bezieht sich auf das Verhältnis `DictateUiState` (in-process
>   SSoT, immutable) vs. `SessionEntity` (DB). Reducer mutiert zuerst State, dann emittiert er
>   einen `Effect.Persist*`, der die DB schreibt.
> - **DB-first (Bulletpoint 5)** bezieht sich auf das Verhältnis `SessionEntity` (DB) vs.
>   `ActiveJobRegistry` (in-process Performance-Cache + Single-Job-Lock, separater Container von
>   `DictateUiState`). Innerhalb des `Effect.Persist*`-Handlers wird zuerst die DB geschrieben,
>   dann die `ActiveJobRegistry` aktualisiert.
>
> Die Gesamt-Reihenfolge ist also: **State → DB → ActiveJobRegistry**. Diese Klärung ist in §6.1.1
> Konsumenten-Tabelle und in der Pipeline-Start-Sequenz unten weiter ausgeführt.

- **Idempotenz:** Alle DB-Writes gehen über `@Insert(onConflict = REPLACE)` bzw. idempotente
  `UPDATE … WHERE id = ?`-Statements. Replay nach View-Recreate oder Cascade-Loop ist sicher
  (kein Doppel-Insert, kein Doppel-Status-Switch).
- **Reihenfolge State-First (State ↔ DB):** Reducer mutiert `DictateUiState` zuerst (Quelle der
  Wahrheit); der `Effect.Persist*` schreibt die DB asynchron. Ein DB-Failure macht den State
  NICHT inkonsistent — der State ist bereits persistiert via StateFlow, die DB ist Mirror.
- **Failure-Channel:** Wirft eine DB-Operation, fängt der Orchestrator (Issue 2.1.3 Option D)
  und re-dispatcht `Action.PipelineAction.PersistenceError(sessionId, reason)`. Der
  PipelineModule-Reducer markiert die Session in `pendingSessions` als `status=FAILED` und
  setzt eine Notification (Backoff-frei, kein Retry-Storm).
- **Cleanup-Cutoff:** `now − 7d − 1h` (Safety-Buffer für inflight-Operations); zentral in
  `Pref.SessionCleanupGracePeriodMs`.
- **Reihenfolge DB → Cache (DB ↔ ActiveJobRegistry, KG-SST-5, RESOLVED 2026-05-11):** Im
  Reducer-Hook für RECORDING/TRANSCRIBING gilt **innerhalb** des `Effect.Persist*`-Handlers:
  `SessionDao.updateStatus(...)` (DB) wird **vor** `ActiveJobRegistry.update(...)`
  (Performance-Cache) aufgerufen. Bei DAO-Failure wird der Registry-Call übersprungen
  (kein Drift, Pipeline-Reducer fängt es als `Action.PipelineAction.PersistenceError`,
  siehe Failure-Channel oben). Bei Process-Crash zwischen DB-Write und Cache-Write: DB
  ist konsistent, Registry wird beim App-Start eh leer initialisiert (`ActiveJobRegistry`
  ist process-local Kotlin `object`, kein langfristiger Drift möglich). Producer-Sites
  `JobExecutor.kt:96/:164` (`register/unregister`) bleiben unverändert — sie
  sind Lock-Producer (Single-Job-Lock), nicht Status-Producer.

<!-- FIX: Phase-B S-2 (2026-05-13) – Verzahnung JobExecutor.register vs. Effect.PersistStatus(TRANSCRIBING) — die DB-first-Regel gilt für DEN Reducer-Hook, nicht für JobExecutor.register. -->
> **Wichtige Verzahnung: `JobExecutor.register` vs. `Effect.PersistStatus(TRANSCRIBING)`** — die `DB-first`-Regel betrifft **ausschließlich** den State-Status-Write-Pfad (Reducer-Hook → `Effect.PersistStatus` → `SessionDao.updateStatus(TRANSCRIBING)` → `ActiveJobRegistry.update`). Sie betrifft **nicht** den parallelen `JobExecutor.start(...)`-Pfad, der `ActiveJobRegistry.register(...)` **vor** dem Pipeline-Run aufruft (Lock-Claim, Single-Job-Constraint). Konkrete Sequenz beim Pipeline-Start (User dispatched `Action.PipelineAction.Submit`):
>
> 1. `PipelineModule.reduce` → State von `Idle → Running(...)` (immutable copy, neuer Snapshot in `_state`).
> 2. `PipelineModule.runEffect(Effect.PersistStatus(sessionId, TRANSCRIBING))` (asynchron, im services.scope):
>    - **(a)** `sessionDao.updateStatus(sessionId, "TRANSCRIBING")` — DB-Write (Status-Producer)
>    - **(b)** `ActiveJobRegistry.update(sessionId, JobState.Running(...))` — Cache-Update (Status-Producer)
> 3. `PipelineModule.runEffect(Effect.StartPipeline(jobRequest))` → `jobExecutor.start(jobRequest)`:
>    - **(c)** `ActiveJobRegistry.register(sessionId, initial)` — Lock-Claim (Producer-Site `JobExecutor.kt:96`, **nicht** Status-Producer)
>    - **(d)** Pipeline-Run startet auf Executor-Thread.
>
> Reihenfolge: 1 → 2(a) → 2(b) → 3(c) → 3(d). Wenn 2(a) fehlschlägt, wird 2(b) übersprungen, 3 wird nicht gestartet → Pipeline läuft nicht, `PersistenceError`-Action wird dispatched (siehe Failure-Channel oben). Wenn 3(c) fehlschlägt (parallel-Job bereits aktiv): `JobExecutor.start` returnt `false`, Pipeline läuft nicht — der State ist aber bereits `Running` (Race-Condition durch State-First-Reihenfolge, R.17 — Reducer mutiert State zuerst). Mitigation: `JobExecutor.start`-Failure-Pfad dispatcht `Action.PipelineAction.RejectedJobAlreadyActive(sessionId)`, der den State zurück auf `Idle` rollt. Dieser Pfad ist in Block 4 (RecordingModule) zu implementieren — Block 3 bringt nur den DB-Write-Pfad (2a).
>
> Implementer-Anker: **NICHT** `JobExecutor.start` so umschreiben, dass es zuerst `SessionDao.updateStatus(TRANSCRIBING)` schreibt — das würde die DB-Write-Verantwortung aus dem Modul-Reducer-Hook in den JobExecutor mappen und den SRP der `JobExecutor`-Klasse brechen (Lock-Producer wird Status-Producer). Stattdessen: Status-Write **bleibt im Modul-Effect** (Schritt 2a oben).

### §6.3 Recovery-Read

Beim Service-`onCreate` (z.B. nach OOM-Death):

<!-- FIX: Issue 2.1.20 / R.16 – Recovery deckt RECORDING/TRANSCRIBING ab; Merge statt Override -->
<!-- FIX: Issue PENDING-2 / Block-3 Resolved – Recovery-Tabelle + Code an konkrete Enum-Werte gebunden -->

**Recovery-Tabelle (Boot-Status → Recovery-Action):**

| Boot-Status | Datei-Check | Recovery-Action | User-Auswirkung |
|---|---|---|---|
| `RECORDING` | Datei evtl. unvollständig/korrupt → nicht für Recovery vertrauen | Promote → `FAILED` mit `lastErrorType=UNKNOWN, lastErrorMessage="recording-interrupted-by-process-death"`; Audio-File opportunistic löschen, falls vorhanden | Session erscheint als FAILED in History (Error-Icon). User sieht "Aufnahme verloren"-Hinweis; kein Reprocess möglich. |
| `TRANSCRIBING` | Audio-File muss existieren (Recording war fertig, sonst wäre Status `RECORDING` geblieben) | Downgrade → `RECORDED`. **Kein Auto-Resume** (D4 / OPEN-4). Session wandert in `pendingSessions` und der User klickt Resend/Restart, um die Pipeline manuell neu zu starten | Session zeigt Pending-Icon in History; Resend-Button im Detail aktiviert. Whisper wird beim manuellen Klick erneut aufgerufen (akzeptierter Doppel-Cost; D4 begründet) |
| `TRANSCRIBING` | Audio-File fehlt (sehr selten — vermutlich Storage-Cleanup zwischen Recording-Stop und Pipeline-Start) | Promote → `FAILED` mit `lastErrorType=UNKNOWN, lastErrorMessage="audio file vanished before transcription"` | wie `RECORDING`-Recovery oben |
| `RECORDED` | Datei existiert | Lade als `pendingSessions` (existierender Pfad, §11.6.2) | Pending-Icon, Resend-Button aktiv |
| `RECORDED` | Datei fehlt (Ghost) | Promote → `FAILED` mit `lastErrorType=UNKNOWN, lastErrorMessage="audio file vanished"` (existierender Pfad, §11.6.2) | wie oben |
| `COMPLETED` mit `inserted_at IS NULL` | n/a (kein Audio nötig) | Lade als `pendingSessions` (existierender Pfad, §11.6.2 — Result-Text bereits in DB) | Pending-Insertion; User klickt Einfügen |
| `COMPLETED` mit `inserted_at IS NOT NULL` | n/a | Skip (Session ist abgeschlossen, kein Recovery-Handling) | — |
| `FAILED`, `CANCELLED` | n/a | Skip | — |

> **Reihenfolge File-Op vs. DB-Op (Begründung):** Bei RECORDING→FAILED promoten wir **erst** den DB-Status (terminal), **dann** löschen wir die Audio-Datei opportunistic. Hintergrund: wenn der File-Delete fehlschlägt (Permission-Race, OS-Storage-Wipe, File-Lock durch anderen Prozess), bleibt die Session trotzdem als FAILED in der DB — die Datei wird beim nächsten Service-Idle-Stop via Cleanup-Policy (`deleteInsertedOlderThan` greift hier nicht, weil FAILED kein `inserted_at` setzt; siehe `KG-SST-2` unten) ODER beim nächsten OOM-Death-Recovery erneut probiert (`clearAudioFilePath` ist idempotent). Die umgekehrte Reihenfolge (Delete vor Status-Promote) würde bei Crash-Mid-Recovery einen Geist hinterlassen: Datei weg, Status noch RECORDING → nächster Boot trifft `audioFilePath != null && !File.exists()` und behandelt es als "vanished". Funktional korrekt, aber redundant.

<!-- FIX: Phase-B S-2 (2026-05-13) – Lücke: RECORDING-Sessions haben in der DB-Row typischerweise `audio_file_path = NULL` (Path wird erst beim Recording-Stop via `transitionRecorded` geschrieben). Das partial-written File lebt physisch in `cacheDir/audio.m4a` (heute) bzw. `filesDir/recordings/{sessionId}.m4a` (nach Block 4 AudioFileFactory). Die `clearAudioFilePath`-Logik im RECORDING-Recovery-Pfad ist daher nur defensive — der File-Leak wird nicht von diesem Pfad behoben. -->
> **RECORDING-Recovery: das partial-written Audio-File auf Disk** — eine RECORDING-DB-Row hat in der Regel `audio_file_path = NULL`, weil `transitionRecorded(sessionId, audioFilePath)` (siehe §6.1 Vorbild-Snippet `SessionManager.transitionRecorded`) den Pfad erst beim **Recording-Stop** in die Row schreibt. Wenn der Prozess während RECORDING stirbt, lebt das partial-written File trotzdem physisch in `cacheDir/audio.m4a` (heute) bzw. `filesDir/recordings/{sessionId}.m4a` (nach Block 4 AudioFileFactory, siehe §4.11).
>
> Der File-Delete im RECORDING-Recovery-Pfad oben (`row.audioFilePath?.let { File(it) }?.takeIf { exists() }?.delete()`) ist daher **defensive für den Sonderfall**, dass der File-Path doch schon in der Row stand (z.B. nach Block 4: AudioFileFactory schreibt den Path beim Allocate, nicht erst beim Stop). Für den Phase-1-Code (Block 3 vor Block 4) wird das partial-written File durch zwei orthogonale Cleanup-Pfade entsorgt:
>
> 1. **`AudioFileFactory.cleanupOrphans(referenced)`** (siehe §7.3-Snippet Z. 3215–3225, läuft im Service-`onCreate` parallel zur Recovery): scant `filesDir/recordings/*` und löscht Files, die in **keiner** sessions-Row referenziert sind. Ein RECORDING-Session mit `audio_file_path = NULL` hinterlässt also einen Pfad, der nicht referenziert ist → wird gelöscht.
> 2. **`cacheDir`-OS-Cleanup** für das Legacy-Layout (`cacheDir/audio.m4a`): Android entsorgt cacheDir-Files opportunistic bei niedrigem Storage. Nach Block 4 ist `cacheDir` nicht mehr das Recording-Ziel.
>
> Akzeptiert: Block-3-Implementation läuft mit dem heutigen `cacheDir`-Layout — der Orphan-Cleanup-Pfad #1 wird in Block 4 mit der `AudioFileFactory` aktiv. Bis dahin reicht der OS-Cleanup.

> **Atomicity:** Die `updateStatus + updateError + clearAudioFilePath`-Sequenz pro Row läuft **nicht** in einer Transaktion (analog zu `SessionManager.finalizeFailed`, das ebenfalls 2 sequenzielle DAO-Calls ohne `runInTransaction` macht — siehe `SessionManager.kt:108-111`). Begründung: zwischen den 3 Statements ist ein Stale-State zwar denkbar (z.B. nur status=FAILED gesetzt, last_error_* noch leer), aber von keinem Konsument problematisch — HistoryAdapter zeigt FAILED unabhängig vom `last_error_*`-Feld an. Eine `runInTransaction`-Klammer wäre overkill und würde den IO-Thread länger blockieren.

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

`SessionDao` exposed dafür (zusätzlich zu den heute schon existierenden Methoden
`updateStatus`, `updateError`, `clearAudioFilePath` — siehe `SessionDao.kt:68-85`):

```kotlin
/**
 * Recovery-Bulk-Read: alle Sessions in den angegebenen Stati.
 * Double-Enum: Caller übergibt `SessionStatus.X.name`-Strings (Liste<String>),
 * weil Room keine Custom-TypeConverter-Listen für CHECK-Enums kennt.
 */
@Query("SELECT * FROM sessions WHERE status IN (:statuses)")
fun getSessionsByStatuses(statuses: List<String>): List<SessionEntity>
```

> **Hinweis zur Signatur:** Der Call-Site übergibt `listOf(SessionStatus.RECORDING.name, ...)`
> (siehe Code oben — wir mappen vor dem DAO-Call). Heutige `SessionDao`-Konvention nutzt
> ausschließlich `String`-Spalten (siehe `updateStatus(id, status: String)` in
> `SessionDao.kt:69-70`), kein TypeConverter für `SessionStatus`.

**Was nicht mehr nötig ist (gestrichen ggü. älterer Spec-Iteration):**

- ~~`markFailed(id, reason)`~~ — der existierende Pfad `updateStatus` + `updateError` deckt das
  ab; eine zusätzliche Single-Statement-Methode würde nur duplizieren (DRY-Verstoß).
- ~~`SessionStatus.TRANSCRIBED`~~ — gibt es nicht im finalen Enum (`COMPLETED` ist der
  Pipeline-Done-Status).
- ~~`last_error_reason`-Spalte~~ — heißt im Schema `last_error_message` (siehe
  `MigrationTo3.kt:63-69`); `last_error_type` ist ein Double-Enum gegen
  `AIProviderException.ErrorType`.

<!-- FIX: Issue 2.0.10 – Pref-Mirror-Bypass-Block aus recoverFromDb entfernt; Overlay-Position kommt aus PipelinePrefMirror.attach (§4.5), das in DictateOrchestrator.init VOR recovery.recover läuft. Damit gleichzeitig F-10-Mismatch-Pfad eliminiert (flache overlayPositionPortraitX vs. hierarchisch state.overlay.positionPortraitX). -->

> **Begründung (Issue 2.0.10):** Der frühere Pref-Mirror-Read im `recoverFromDb` war
> ein Drift-Artefakt aus einer früheren Iteration. `PipelinePrefMirror.attach(store)`
> (siehe §4.5) wird in `DictateOrchestrator.init` **vor** `recovery.recover(store)`
> aufgerufen — die Overlay-Position liegt zum Recovery-Start bereits kanonisch im
> Store. Doppel-Read + doppelter Write-Path entfallen. Gleichzeitig wird damit ein
> F-10-Inkonsistenz-Fall geschlossen (frühere Version nutzte die flachen Felder
> `overlayPositionPortraitX` etc., die F-10 in `state.overlay.positionPortraitX`
> umgestellt hat).

#### §6.3.1 Orphan-FAILED-Audio-Cleanup (KG-SST-2 RESOLVED)

<!-- FIX: KG-SST-2 [RESOLVED 2026-05-11] – Orphan-Audio-Cleanup-Routine konkretisiert -->

Nach M4-Block-4 (AudioFileFactory) wandert das Recording-Audio-File von `cacheDir/audio.m4a`
nach `filesDir/recordings/{sessionId}.m4a`. `filesDir` wird vom Android-OS NICHT
gecleant. FAILED/CANCELLED-Sessions mit `audio_file_path != null` lecken damit
Storage, bis sie via User-Delete (Detail-View) entfernt werden.

`deleteInsertedOlderThan(cutoff)` greift hier nicht (FAILED-Rows haben
`inserted_at IS NULL`). `DurationHealingJob.heal(...)` heilt nur DB-Inkonsistenzen
(File weg, DB-Row vorhanden), nicht den umgekehrten Fall.

**Lösung:** Eine neue DAO-Methode + ein neuer Service-Cleanup-Hook im
`DictatePipelineService.onTimeout()`/Idle-Stop-Slot:

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

**Trigger:** Direkt vor `stopSelf()` im Service-Idle-Stop-Pfad. Reihenfolge:

1. `deleteInsertedOlderThan(cutoff)` — bestehend (§6.2 R.17)
2. `cleanupOrphanedTerminalAudio()` — neu (KG-SST-2)
3. `stopSelf()`

**Cutoff:** Selber Wert wie `deleteInsertedOlderThan` — `now − 7d − 1h`
(`Pref.SessionCleanupGracePeriodMs`).

<!-- FIX: Phase-B S-7 (2026-05-13) – Concurrency-Vertrag explizit + Trigger-Slot präzise definiert. -->
**Concurrency-Vertrag (Phase-B S-7):**

- **Trigger-Slot:** Genau zwischen dem letzten `Effect`-`runEffect`-Tick und `stopSelf()`. Konkret
  im `stopSelfWhenTerminal(state)`-Callback (§7.3 Z. 3625) — der Service collected `state` und
  ruft den Callback bei jedem Update; wenn `state.isAllTerminal()`, läuft `cleanupOrphanedTerminalAudio()`
  in einer `serviceScope.launch(Dispatchers.IO)`-Coroutine und IM `await`-Block davor wird `stopSelf()`
  aufgerufen. **Wichtig:** `stopSelf()` blocks NICHT bis der Cleanup-Job durchläuft — Android wird
  den Service im Zweifel beenden, während die Cleanup-Coroutine noch IO macht. Das ist akzeptiert:
  `cleanupOrphanedTerminalAudio` ist best-effort, ein abgebrochener Lauf wird beim nächsten
  Service-Boot durch `cleanupOrphans`-Boot-Hook (anderer Pfad! §4.11.5.1 Schritt 8) erneut versucht
  — siehe Idempotenz-Vertrag von `findOrphanedTerminalAudio`.
- **Concurrent allocate während Cleanup:** Sehr unwahrscheinlich — `state.isAllTerminal()` ist
  Voraussetzung für den Trigger; das bedeutet `state.recording is Idle && state.pipeline is Idle`.
  Wenn der User *mid-cleanup* eine neue Aufnahme startet, läuft `audioFileFactory.allocate()` in
  einen anderen Pfad (`cacheDir/audio/`, nicht `filesDir/recordings/` — wo `cleanupOrphanedTerminalAudio`
  arbeitet). Kein direkter Konflikt. Aber: die Concurrent-`state.collect`-Cascade verhindert
  `stopSelf()` (Idle→Preparing-Transition macht `isAllTerminal() = false`), also würde der Cleanup-
  Job evtl. mitten in der DB-Read abbrechen, wenn der Service-Scope wegen einer neuen Recording
  reaktiv bleibt. **Verhalten:** `cleanupOrphanedTerminalAudio` läuft zu Ende (kein Cancel), aber
  `stopSelf()` wird nicht gerufen (`isAllTerminal()` ist false). Beim nächsten echten Idle-Stop
  versucht der Cleanup es erneut — idempotent.
- **Double-Delete-Race:** Wenn parallel zur `cleanupOrphanedTerminalAudio`-Iteration ein User
  via `HistoryDetailActivity` "Audio löschen" für eine FAILED-Session ruft (`RecordingRepository.
  deleteBySessionId`), könnte derselbe Pfad zweimal gelöscht werden. `File.delete()` ist auf
  bereits gelöschtem File ein no-op (`false`-Return); `clearAudioFilePathBulk(ids)` ist
  idempotent (`UPDATE … WHERE id IN (...)`). Akzeptiert: kein Lock nötig.

**Dispatcher-Disziplin (Layer-Trennung):**

- `findOrphanedTerminalAudio(cutoff)` läuft via Room auf einem internen Executor (kein Mainthread).
- `File.delete()` läuft im `withContext(Dispatchers.IO)`-Block (siehe Snippet oben).
- `clearAudioFilePathBulk(ids)` läuft via Room — `withContext(Dispatchers.IO)` ist redundant aber
  schadet nicht.

**Test-Case (Block 3):** `SessionDaoTest.findOrphanedTerminalAudio_filtersByStatusAndCutoff`
prüft, dass nur FAILED/CANCELLED-Rows mit `audio_file_path != NULL && created_at < cutoff`
zurückkommen. RECORDED + COMPLETED-Rows werden NICHT zurückgegeben (auch nicht ältere).
Integration-Test im Service-Layer: simulierte FAILED-Session mit File auf Disk → nach
Service-Idle-Stop ist die Datei weg und `audio_file_path IS NULL`.

<!-- FIX: Phase-B S-2 (2026-05-13) – DB-Row-Lifecycle für FAILED/CANCELLED explizit dokumentieren (Cleanup räumt nur Audio-Files, NICHT DB-Rows). -->
> **DB-Row-Lifecycle für FAILED/CANCELLED — bewusst kein Auto-Cleanup:**
>
> `cleanupOrphanedTerminalAudio` räumt **nur die Audio-Files** auf Disk weg und setzt `audio_file_path = NULL` in der DB-Row. Die DB-Row selbst **bleibt persistent** — FAILED/CANCELLED-Sessions sammeln sich also im History-View an, bis der User sie manuell via `HistoryDetailActivity → Delete` entfernt.
>
> Begründung:
> - **User-Wert:** Fehlerstatus in der History bleibt sichtbar → User kann später nachvollziehen ("warum hat Pipeline letzte Woche nicht funktioniert?"). Auto-Delete würde diese Information stillschweigend verschlucken.
> - **DB-Größe:** Pro FAILED-Row ~500 Byte (id + type + status + error-Felder). Bei 100 FAILED-Sessions/Jahr = 50 KB — irrelevant.
> - **`deleteInsertedOlderThan` (COMPLETED + inserted)** ist ein anderer Pfad: er räumt **erfolgreich abgeschlossene + eingefügte** Sessions nach 7d, weil deren Information nach Insertion redundant ist. Dieser Pfad gilt **nicht** für FAILED/CANCELLED, weil deren Information eben **nicht** redundant ist (Pipeline schlug fehl, kein Insertion-Event).
>
> Falls sich in der Praxis zeigt, dass FAILED-Sessions DB-Bloat erzeugen (z.B. >10k Rows bei häufigen Quota-Fehlern), kann ein zusätzlicher Cleanup-Pfad in einer späteren Phase ergänzt werden (`deleteFailedOlderThan(cutoff)` mit z.B. 30d-Frist). Phase 1: bewusst nicht implementieren.

### §6.4 SharedPreferences-Erweiterung — Overlay-Position (OPEN-3)

Die Overlay-Position wird **per Orientation getrennt** in SharedPreferences persistiert.
Werte sind **normalisierte 0..1-Koordinaten** relativ zur Bildschirmgröße — bei Orientation-
oder Display-Change ist der Pref-Wert unverändert nutzbar; das OverlayBackend
de-normalisiert vor dem Render zu absoluten Pixeln.

**Pref-Konstanten** (Datei: `app/src/main/java/net/devemperor/dictate/preferences/Pref.kt`, ergänzen):

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
**Schreib-Trigger:** `Action.OverlayAction.UpdatePosition(portrait, x, y)` — vom `OverlayBackend` nach Drag-End dispatcht (siehe Spec 3 §11.5). Reducer + Effect:

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
**Lese-Trigger:** `PipelinePrefMirror.attach(store)` (siehe §4.5) wird in
`DictateOrchestrator.init` **vor** `recovery.recover(store)` aufgerufen und initialisiert
die Overlay-Position-Felder im Store (`initialMirror()`); nachfolgende Pref-Änderungen
laufen über den `OnSharedPreferenceChangeListener` desselben PrefMirrors. Das stellt
sicher, dass nach OOM-Death oder Service-Restart die zuletzt gespeicherte Position
sofort im State liegt; das OverlayBackend liest die Werte vom State (nicht direkt aus
Pref) und respektiert damit die Single-Source-of-Truth-Regel "alles via DictateUiState".
`recoverFromDb()` selbst (§6.3) mutiert nur noch `pendingSessions` und ist nicht mehr
am Pref-Read beteiligt.

<!-- FIX: Phase-B S-1 (2026-05-13) – SOLID-Block auf F-11 umgestellt (PipelinePrefMirror + OverlayModule statt monolithischer PipelineStateManager). -->
**SOLID-Konformität:** Die Pref-Mirror-Logik (Read-Trigger) lebt im `PipelinePrefMirror` (Single-Responsibility, §4.5); die Pref-Write-Logik im `OverlayModule.runEffect(Effect.PersistOverlayPosition)` (§15, OverlayModule). `OverlayBackend` kennt nur die Action `Action.OverlayAction.UpdatePosition` und die State-Felder, nicht die Pref-Keys (Dependency-Inversion).

---

## §7 Lifecycle: Foreground Service

> **Architektur-Korrektur F-3 (Iteration 2026-05-08):** Frühere Spec-Versionen
> bauten Notification-Building, State-Subscribe und Action-PendingIntent-Routing
> direkt in `DictatePipelineService.onStartCommand`. Diese drei Concerns
> werden jetzt in zwei Helper-Klassen extrahiert (`PipelineNotificationCoordinator`,
> `PipelineActionRouter`), damit der Service einzig Process-Lifecycle-Owner
> ist und alles andere injiziert + testbar ist.

<!-- FIX: Phase-B S-1 (2026-05-13) – §7.1 Service-Struktur auf F-11-Naming umgestellt (PipelineStateManager → DictateOrchestrator + 12 Module + 4 Hilfsklassen). Pre-F-11-Diagramm zeigte den monolithischen Manager als einzigen Composition-Root-Eintrag. -->
### §7.1 Service-Struktur (F-3 / SRP, F-11 Modular Orchestrator)

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

**Verantwortlichkeiten:**

| Klasse | SRP | Side-Effects |
|---|---|---|
| `DictatePipelineService` | FGS-Lifecycle (`startForeground`/`stopSelf`), Bind-Connection | ja (FGS-Calls) |
| `DictateOrchestrator` | Action-Routing + Cross-Module-Cascade-Dispatch | nein (delegiert an Module via `runEffect`) |
| `PipelineNotificationCoordinator` | State → Notification-Render, throttled | ja (NotificationManager) |
| `PipelineActionRouter` | PendingIntent-Build + Intent-Decode → `orchestrator.dispatch(Action)` | nein (pure Mapping) |

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

### §7.3 onStartCommand (schlank)

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
    // Recording aktiv ist. Disambiguierung Pipeline- vs. Recording-Cancel ist Cross-Spec-
    // Klärung (siehe C-3 Action-Hierarchie); §10 + §13.5 referenzieren historisch
    // `Action.PipelineAction.CancelPipeline`, semantisch korrekter wäre eine Branch-Action,
    // die im jeweiligen Modul greift. Pre-Cancel-Dispatch unten als TODO-Marker verankert,
    // Implementer-Pflicht: vor Block-2-Acceptance-Test entscheiden + dispatchen.
    override fun onDestroy() {
        super.onDestroy()
        // 0. Pre-Cancel-Dispatch (Phase-C C-2 / §10 + §13.5 G6 Pfad A):
        //    bei aktivem Recording / aktiver Pipeline eine Cancel-Action dispatchen, damit
        //    der Reducer State→Idle setzt + Hardware-Release-Effect emittiert. Konkrete
        //    Action-Variante hängt vom State ab (siehe Implementer-Pflicht im Marker oben):
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

**SRP:** ausschließlich State → Notification-Mapping + Subscription-Management. Kein Action-Routing, kein Lifecycle-Wissen außer "wann ist der Service terminal".

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
**SRP:** pure Mapping zwischen Notification-Action-Strings und `Action`-Sealed-Class-Varianten. Keine UI-Logik, keine Notification-Build, kein Lifecycle. Tests können einen Mock-`DictateOrchestrator` injizieren und prüfen, dass jeder Action-String die richtige `Action`-Variante via `orchestrator.dispatch(...)` trifft.

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
<!-- FIX: Phase-B S-1 (2026-05-13) – Migrations-Tabelle auf F-11 (Modul-Ziele statt monolithischer PipelineStateManager) umgestellt. -->
| Recording-Logik | RecordingStateController | wandert in `RecordingModule` (§15.2) |
| Pipeline-Logik | KeyboardUiController + JobExecutor + PipelineOrchestrator | wandert teilweise (State-Teil → `PipelineModule`); JobExecutor + PipelineOrchestrator bleiben, aber im PipelineService gehalten |
| State-Coroutinen | keine | im PipelineService (serviceScope) |
| View-Rendering | direkter Code in Service | wandert in KeyboardLayoutManager (Spec 2) |
| Visibility-Mutations | hybrid (KSM + RecordingUiController + Service) | weg — alles über LayoutManager-Resolver (Spec 2) |
| Click-Listener-Verdrahtung | MainButtonsController | wandert in KeyboardLayoutManager (Spec 2) |

**Was im IME-Service bleibt:**
- View-Lifecycle (`onCreateInputView`, `onStartInputView`, `onFinishInputView`, `onDestroy`).
- IME-spezifische APIs (`getCurrentInputConnection()`, `requestHideSelf()`, `setInputView()`).
- Bind/Unbind zum PipelineService.
- Forwarding von User-Events an PipelineService.

<!-- FIX: Issue 2.1.11 / R.9 – View-Recreate-Vertrag (viewScope-Cancel + Migrations-Tabelle) -->
### §8.x View-Recreate-Vertrag

Beim Re-Inflate des IME-Views (Rotation, Theme-Wechsel, IME-Switch) müssen alle View-Subscriber
sauber abgemeldet und neu attached werden. Der Vertrag:

```
1. viewScope-Erzeugung in DictateInputMethodService.onCreateInputView() VOR Subscriber-Wiring.
2. viewScope.cancel() in DictateInputMethodService.onFinishInputView() (analog cleanupOldControllers).
3. WindowManager.removeView (Overlay) wird in OverlayBackend.detach() gerufen (kein StateFlow-Cancel,
   sondern expliziter Call — siehe Spec 3 §4.3 / §11.6).
4. KeyboardLayoutManager.detachBackend() wird in onFinishInputView() gerufen — räumt das aktive
   ImeViewBackend (firstRender-Flag-Reset, R.14) und das ContentAreaController-Backend (R.10) ab.
```

**Migrations-Tabelle** (heute → Refactor):

| Heutiger Call | Refactor-Replacement |
|---|---|
| `cleanupOldControllers()` | `viewScope.cancel()` (in `onFinishInputView`) |
| `rewireCallbacks()` | entfällt (StateFlow-Subscriber + neuer viewScope subscribt automatisch) |
| `restoreUiState()` | entfällt (StateFlow holds state, neuer viewScope subscribed → erste Emission auto-restored) |
| `keyboardLayoutManager.detachAllBackends()` (NEU, Issue 3.1.4 / Option C) | im `onDestroy` zusätzlich aufgerufen — eliminiert Window-Leak beim IME-Switch |

**Acceptance:** Robolectric-Test rotation while pipeline running — nach `onFinishInputView` +
`onCreateInputView` ist der Subscriber neu attached und der Pipeline-State ist korrekt
gerendert. Spec 1 §10 Block-2-Acceptance erweitert um diesen Test.

---

## §9 Migration vorhandener Klassen

<!-- FIX: Issue 1.0.6 – Hierarchische State-Pfade (F-10) durchpropagiert in §9 Migrations-Tabellen + §13.2 Audit (Mapping siehe Spec 1 §3) -->

<!-- FIX: Phase-B S-1 (2026-05-13) – Section-Titel auf RecordingModule umgestellt (F-11). -->
### §9.1 RecordingStateController → RecordingModule

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

<!-- FIX: Phase-B S-1 (2026-05-13) – Migrations-Tabelle auf Action-/Modul-Pattern umgestellt. RecordingStateController-Methoden werden zu Action.RecordingAction-Varianten, die im RecordingModule.reduce verarbeitet werden. -->
**Künftig:** Wandert komplett in `RecordingModule` (§15.2). Die `Callback`-Schnittstelle entfällt — Subscriber abonnieren via `orchestrator.state.collect { ... }` und reagieren auf `oldState.recording` vs. `newState.recording`-Diffs.

| Heute (Methode) | Künftig (Action + Modul) | Mutiert in `DictateUiState` |
|---|---|---|
| `startRecording(...)` (Z. 128) | `Action.RecordingAction.StartRecording(target, audioFile)` → `RecordingModule.reduce` | `recording: Idle → Preparing → Active` |
| `stopRecording()` (Z. 145) | `Action.RecordingAction.StopRecording` → `RecordingModule.reduce` | `recording: Active → Idle` + Pipeline-Auto-Start (`PipelineAction.Submit` via Cross-Module-Cascade) |
| `togglePause()` (Z. 164) | `Action.RecordingAction.PauseRecording` / `ResumeRecording` | `recording: Active ↔ Paused` |
| `setAudioFocusRuntime(b)` (Z. 201) | `Action.AudioAction.ToggleAudioFocusPref` → `AudioModule.reduce` <!-- FIX: Phase-B S-3 (2026-05-13) – Naming-Drift behoben: Spec 2 §3.3 SoT-Name ist `ToggleAudioFocusPref` mit `Pref`-Suffix (war hier `ToggleAudioFocus`). --> | `audio.audioFocusEnabledPref` |
| `cancelRecording()` (Z. 217) | `Action.RecordingAction.CancelRecording` → `RecordingModule.reduce` | `recording: → Idle` + `Effect.DeleteAudioFile` |

**Datentyp `RecordingState`** (`RecordingState.kt:10-18`) bleibt unverändert erhalten — er ist sealed-class, exhaustiv, gut. Wird zum Feld von `DictateUiState` (siehe §3).

<!-- FIX: Phase-B S-1 (2026-05-13) – Section-Titel auf PipelineModule umgestellt. -->
### §9.2 KeyboardUiController-State-Teil → PipelineModule

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
- View-Mutations innerhalb `updateDictateUiState`: ruft `refreshRecordButtonFromState()` (Z. 150, view-mutation: button text/icon/enabled — Z. 464-509) und `stateManager.refresh()` (Z. 153) — die View-Mutation wandert weg in den LayoutCatalog (Spec 2 §9.5), die State-Mutation in `PipelineModule.reduce`. <!-- FIX: Phase-B S-1 (2026-05-13) – PipelineStateManager → PipelineModule.reduce -->

**Künftig:**
- Sealed Class `PipelineUiState` (`PipelineUiState.kt:13-54`) bleibt — Datentyp ist gut.
<!-- FIX: Phase-B S-1 (2026-05-13) – Migrations-Ziel auf PipelineModule.reduce + Action-Dispatch (F-8). -->
- State-Field + Mutator wandern in `PipelineModule.reduce` als `state.copy(...)` (Reducer); `orchestrator.dispatch(action)` ist der einzige Mutations-Eingang (F-8 Single Dispatch).
- Public-API-Methoden auf `KeyboardUiController` werden ersetzt durch `Action.PipelineAction.*`-Varianten — Aufrufer dispatchen über `orchestrator.dispatch(Action.PipelineAction.X)`. Die heutigen Implementations werden inline in die Reducer-Arme migriert — kein "Wrapper".
- `refreshRecordButtonFromState()` (Z. 464-509) wandert in den `RECORD`-Slot-`textResolver` + `enabledResolver` im LayoutCatalog (Spec 2 §9.5).
- `stepRows`-Verwaltung (Z. 133-135 + `addRunningStep / completeStep / failStep`) bleibt im `KeyboardUiController` (View-side), wird aber durch `state.pipeline`-StateFlow-Subscriber getriggert statt durch direkte Methodenaufrufe vom Service.
- `AutoEnterConfig`-Field (Z. 67) wandert in `DictateUiState.pipeline` (das `PipelineUiState.Running`-Variant trägt `autoEnterActive` bereits — `AutoEnterConfig` ist eine redundante Schicht und entfällt).

<!-- FIX: Phase-B S-1 (2026-05-13) – Section-Titel auf LayoutModule + LayoutCatalog umgestellt. -->
### §9.3 KeyboardStateManager → LayoutModule + LayoutCatalog

**Heute:** `app/src/main/java/net/devemperor/dictate/core/KeyboardStateManager.kt`
- Eigene State-Felder: `contentArea: ContentArea` (Z. 100), `isSmallMode: Boolean` (Z. 102) — heute private Setter, mutiert via `setContentArea(area)` (Z. 135-138) und `setSmallMode(enabled)` (Z. 140-146).
- `applyVisibility()` (Z. 158-169) mit Sub-Funktionen `applyContentAreaVisibility()` (Z. 171-181), `applyRecordingControlsVisibility()` (Z. 183-192), `applyPromptsVisibility()` (Z. 194-224), `applyPromptsLayout()` (Z. 227-240). Mutiert direkt 8 View-Properties (siehe §13.1).
- `refresh()` (Z. 151-154) — externer Trigger, ruft `applyVisibility()`.
- Lambda-Konstruktor-Parameter: `isRecording`, `isPaused`, `isPipelineRunning`, `isRewordingEnabled`, `isPipelineProgressVisible`, `isReprocessStaging` (Z. 78-97). Diese Lambdas befragen heute `RecordingStateController` und `KeyboardUiController` direkt — werden durch reaktiven `state.collect`-Subscriber ersetzt.

**Künftig:**
- `contentArea` und `isSmallMode` wandern in `DictateUiState.layout` (siehe §3) — Mutation via `Action.LayoutAction.SetContentArea(area)` und `Action.LayoutAction.ToggleSmallMode` → `LayoutModule.reduce`. **Atomarität (KSM-Bug-Fix):** Das frühere `setSmallMode(true)` mutierte zuerst `isSmallMode = true` und DANN `contentArea = MAIN_BUTTONS` (zwei sequenzielle Schritte, siehe `KeyboardStateManager.kt:141-145`). Im Refactor läuft das in einem einzigen `state.copy(layout = layout.copy(smallMode = enabled, contentArea = MAIN_BUTTONS))` — atomar, kein Subscriber sieht den Zwischen-Zustand. <!-- FIX: Phase-B S-1 (2026-05-13) – PipelineStateManager → Action+LayoutModule; Atomarität-Klausel hinzugefügt -->
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
        state.resend.lastAudioExists
            && state.resend.resendEnabled                              // Pref.ResendButton
            && state.recording is RecordingState.Idle
            && state.pipeline is PipelineUiState.Idle
    },
    actionResolver = { /* short-press: re-run pipeline; long-press: enter staging */ }
)
```

**Datenfluss:**
- `resend.lastAudioExists` wird beim Service-`onCreate` vorbelegt (`File.exists()`-Check auf `Pref.LastFileName`) und nach jedem Recording-Done aktualisiert (`stopRecording`-Callback).
- `resend.resendEnabled` ist eine Spiegelung von `Pref.ResendButton` — wird beim `onCreate` gelesen und auf SP-Listener gehört.
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

### §9.6 Lösch-/Adapter-/Erhalt-Tabelle (heutige Klassen → künftiger Status)

<!-- FIX: Issue 2.0.9 – Lösch-Tabelle ergänzt; konsolidiert §9.1-§9.5-Migrations-Aussagen plus die in §9 nicht explizit migrierten Klassen (RecordingUiController, LanguageController, RecordingManager, BluetoothScoManager, JobExecutor) -->

| Heutige Klasse | Final gelöscht in Block | Übergangsweise als Adapter? |
|---|---|---|
| `RecordingStateController` | Block 1 (nach Migration §9.1) | nein, direkt gelöscht — Tests werden auf den neuen Reducer umgeschrieben |
| `KeyboardUiController` | Block 1 (state-Teil §9.2) | partial: state-Teil wandert sofort, View-Teil (`stepRows`-Verwaltung etc.) bleibt bis Spec 2 |
| `RecordingUiController` | Block 5 (LayoutCatalog) | bleibt bis dahin; Sub-Set seiner Methoden (Main-Button-Mutationen) wandern nach Spec 2 §9.4 — siehe Spec 2 §9.4-Tabelle |
| `KeyboardStateManager` | Block 5 (LayoutCatalog) | bleibt bis dahin; danach Aufspaltung (siehe Issue 2.1.13) — `contentArea` / `isSmallMode` wandern in `DictateUiState`, `applyVisibility`-Logik in den Catalog |
| `LanguageController` | Block 1 (LanguageModule) | wandert direkt in den Modul-Reducer; keine Adapter-Phase nötig |
| `RecordingManager` | nie gelöscht (Subsystem-Adaptee) | wird hinter `RecordingHardwareSubsystem`-Interface gewrapped (siehe §4.7) |
| `BluetoothScoManager` | nie gelöscht (Subsystem-Adaptee) | wird hinter `BluetoothScoSubsystem`-Interface gewrapped (siehe §4.7) |
| `JobExecutor` | nie gelöscht | implementiert das `PipelineRunner`-Interface (siehe §4.7) |

**End-of-Block-Cleanup-Check:** Pro Block prüft ein einfacher `grep` (oder eine
Architektur-Test-Assertion), dass die in dieser Tabelle als "Final gelöscht in
Block N" markierten Klassen nach Block N nicht mehr existieren. Adapter-Phasen
sind explizit erlaubt — sie tauchen in der dritten Spalte als `partial` auf.

---

## §10 Acceptance-Kriterien

Block 2 (DictatePipelineService) gilt als done, wenn:

- [ ] Recording starten, Tastatur zur Gboard wechseln, 30s warten, zurück zu Dictate → Recording läuft noch, Pulse-Animation läuft im IME-View, Pause-Button funktioniert.
- [ ] Pipeline starten, App-Switch, 30s warten, zurück → Pipeline-Status korrekt restauriert, Notification zeigt Status.
- [ ] Beim Recording: persistente Notification sichtbar, zeigt korrekte Action-Buttons.
<!-- FIX: Phase-B S-5 (2026-05-13) – Tastatur-Wechsel-Survival + Mic-Indikator-Sichtbarkeit explizit als Acceptance verankert. Heute schweigt der Plan, was der User in der "Zwischenzeit" (IME-Service tot, Service läuft weiter mit Recording) sieht. Diese Acceptance schließt die User-Visibility-Lücke. -->
- [ ] **Phase-B S-5 Mic-Indikator beim Tastatur-Wechsel:** während der IME-Service tot ist (User hat zu Gboard gewechselt) und der DictatePipelineService noch ein aktives Recording hält, ist im System-Tray ein Mikrofon-Indikator sichtbar (API ≥ 31 OS-feature, kostet uns nichts — `FOREGROUND_SERVICE_TYPE_MICROPHONE` triggert ihn automatisch). Die persistente Notification ist sichtbar und zeigt `[Pause] [Stopp] [Senden]`-Buttons; User kann via Notification-Action das Recording cancellen, ohne zur Dictate-Tastatur zurückzuwechseln. Verifiziert manuell (E2E-Test mit zwei Tastaturen) + via `DictatePipelineServiceForegroundSurvivalTest.kt` (verifiziert dass `pipelineBinder.unbind()` + `serviceScope.isActive == true` → Recording-State bleibt `Active`).
<!-- FIX: Phase-B S-5 (2026-05-13) – FGS-Killed-by-System (low memory) Recovery-Pfad acceptance. Plan dokumentiert START_NOT_STICKY ohne User-Visibility-Klausel; bei OOM-Kill verschwindet die Notification, Recording-Tonspur ist weg, User muss aktiv beim nächsten Bind das pendingSessions sehen. -->
- [ ] **Phase-B S-5 FGS-Killed-by-System (Low-Memory):** Service-Kill via `adb shell am kill` während aktives Recording simuliert OOM-Kill. `START_NOT_STICKY` bedeutet: Service wird NICHT automatisch neu gestartet. Beim nächsten IME-`onCreateInputView` läuft Service-onCreate frisch durch, `PipelineRecovery.recover` lädt die Session aus der DB (`RECORDING → FAILED` per R.16a, Block-3-Acceptance), `state.pendingSessions` enthält die unterbrochene Session mit `lastErrorType=UNKNOWN, lastErrorMessage="recording-interrupted-by-process-death"`. Der User sieht im Resend-Pfad / History den FAILED-Eintrag. Verifiziert via `DictatePipelineServiceKillRestartTest.kt` (Robolectric oder instrumented).
<!-- FIX: Phase-B S-5 (2026-05-13) – Channel-Erstellung-Reihenfolge expliziter Acceptance-Test. -->
- [ ] **Phase-B S-5 NotificationChannel-vor-startForeground:** ein Unit-Test mit fresh-App-Install (kein Channel existiert) verifiziert dass `DictatePipelineService.onCreate` den Channel erzeugt VOR `onStartCommand → startForeground(…)`. Test-Setup: `NotificationManager.deleteNotificationChannel("dictate_pipeline")` als Fixture, Service-Boot, assert dass `startForeground` keinen `IllegalArgumentException` wirft. Test-Datei: `DictatePipelineServiceChannelOrderTest.kt`.
<!-- FIX: Phase-B S-5 (2026-05-13) – FGS-5s-Frist als reproduzierbarer Test verankert. -->
- [ ] **Phase-B S-5 FGS-Boot < 5 s:** Robolectric- oder instrumented-Test misst Zeit zwischen `Context.startForegroundService(...)` und `startForeground(...)`-Call. Acceptance: **< 1 s p99** auf API-34-Test-Device. Test deckt §11.1.4 (5-s-Timeout-Mitigation) ab und schützt gegen zukünftige Regression (z.B. wenn jemand einen sync-DB-Read in `onCreate` einbaut, der die Frist verbraucht). Test-Datei: `DictatePipelineServiceFgsBootLatencyTest.kt`.
<!-- FIX: Phase-B S-5 (2026-05-13) – NOTIF_ID-SoT-Test. -->
- [ ] **Phase-B S-5 NOTIF_ID-Konsistenz:** Architektur-Test (Kotlin Reflection oder ESLint-Style-Lint) verifiziert dass NUR `PipelineNotificationCoordinator.NOTIF_ID` als Konstante existiert; kein `const val NOTIF_ID` im `DictatePipelineService.companion`. Schützt gegen erneutes Aufkommen der `1001` vs `0xD1C7A7E`-Drift (Phase-B S-5 F-2).
<!-- FIX: Phase-B S-5 (2026-05-13) – runBlocking-Timeout-Test. -->
- [ ] **Phase-B S-5 onDestroy-Timeout:** `DictatePipelineServiceShutdownTimeoutTest.kt` — Mock-Module mit `terminate(services)`-Implementation, die 5 s blockiert (`Thread.sleep(5000)`). Assert: `onDestroy` returnt nach < 2.5 s (2 s Timeout + Reserve). Verifiziert dass ein pathologisches Modul den Service-Destroy-Path nicht hängen lassen kann.
<!-- FIX: Phase-B S-5 (2026-05-13) – Multi-Bind-Acceptance verankert (§11.3.4). -->
- [ ] **Phase-B S-5 Multi-Bind:** `DictatePipelineServiceMultiBindTest.kt` mit zwei ServiceConnections in einem Test — Bind-A in Setup, Bind-B in Body. Assert: beide `onServiceConnected` empfangen denselben `IBinder`-Instanz (Singleton-Vertrag); Unbind-B alleine hält Service alive; Unbind-A + `state.isAllTerminal() == true` führt zu `onDestroy`.
<!-- FIX: Phase-B S-5 (2026-05-13) – Pre-Bind-Action-Pfad als Robustheits-Test verankert. -->
- [ ] **Phase-B S-5 Pre-Bind-Action-Toast:** `DictateInputMethodServiceBindRaceTest.kt` simuliert Click vor `onServiceConnected`. Assert: `pipelineBinder == null` → `Toast.makeText(..., R.string.dictate_service_not_ready, ...)` wird gerufen, kein Crash, kein silent-drop. (§11.3.2a). Plus: String-Resource `dictate_service_not_ready` ist in `values/strings.xml` + `values-de/strings.xml` angelegt.
<!-- FIX: Phase-B S-5 (2026-05-13) – POST_NOTIFICATIONS-Prompt-Test. -->
- [ ] **Phase-B S-5 POST_NOTIFICATIONS-Prompt:** auf API-33+-Test-Device öffnet `OnboardingActivity` den Permission-Prompt; auf Decline ist der Banner im IME-View bei aktivem Recording sichtbar; Klick auf Banner öffnet `Settings.ACTION_APP_NOTIFICATION_SETTINGS`. (§11.5.1). Verifiziert manuell + via `OnboardingPostNotifPromptTest.kt`.
- [ ] `stopSelf()` greift: nach Insertion verschwindet die Notification ohne weitere Aktion.
- [ ] Force-Stop der App: beim nächsten Tastatur-Open wird Restart-Button mit pending-Session gezeigt.
- [ ] Manueller Restart-Button-Klick: PipelineService startet neu, Pipeline läuft mit korrektem State.
- [ ] **MediaRecorder-release-Pfad (FIX Issue 3.0.11):** Service.onDestroy bei aktivem Recording ruft `orchestrator.dispatch(Action.PipelineAction.CancelPipeline)` → `recordingManager.release()` wird aufgerufen UND der MediaRecorder ist im released-State. Verifiziert via `MediaRecorder.release()`-Mock-Spy in Unit-Test (oder Robolectric); deckt §13.5 G6 Pfad A ab. (Spec 1 hat aktuell keine eigene Test-Strategie-Sektion; Test-Stub wird in Block-2-Implementation als `RecordingManagerReleaseTest.kt` angelegt.)
<!-- FIX: Phase-B S-3 (2026-05-13) – Java-Brücke + KeyboardInputModule-Acceptance ergänzt. -->
- [ ] **Phase-B S-3 Java-Brücke `DictateUiStateObserver`:** Datei `state/DictateUiStateObserver.kt` ist angelegt (analog zu `core/ActiveJobRegistryObserver.kt`); mindestens ein Java-Konsument (`DictateInputMethodService.java`) konsumiert den `DictateUiState` darüber statt über direkte Callbacks. Verifiziert via Robolectric-Test `DictateUiStateObserverTest.kt` (Lifecycle-Bind funktioniert; STOP cancellt, START repliziert State).
- [ ] **Phase-B S-3 KeyboardInputModule (§15.6):** Backspace-, Enter- und Space-Button-Klicks lösen die korrekten InputConnection-Operationen aus. Manuell verifiziert (Tastatur öffnen, Buttons drücken, Output prüfen) UND via Reducer-Unit-Test `KeyboardInputModuleTest.kt` (jede Action erzeugt den passenden Effect). Verifiziert zusätzlich, dass `orchestrator.dispatch(Action.KeyboardInputAction.Backspace)` **nicht** `DispatchOutcome.Unrouted` zurückgibt — d.h. das Modul ist in `DictateModuleRegistry.all` (§4.8).
<!-- FIX: Phase-C C-1 (2026-05-14) – stale Z. 617 → Section-Anchor (Line-Drift). -->
- [ ] **Phase-B S-3 EffectFailure-Origin-Routing (§4.3 `dispatchInternal` Step 1a + 2):** Ein Effect, der wirft (z.B. `RecordingModule.Effect.AllocateMediaRecorder` mit fehlender Permission), löst eine `Action.EffectFailure(originModuleId = ModuleId.Recording, …)` aus; das emittierende Modul (RecordingModule) hat einen expliziten `EffectFailure`-Reducer-Arm, der einen State-Rollback macht (z.B. `Preparing → Idle`). Verifiziert via `DictateOrchestratorTest.kt::effectFailure_routedBackToOriginModule()` mit einem Fake-Modul, das im `runEffect` wirft.

<!-- FIX: Phase-B S-1 (2026-05-13) – Block-1-Acceptance auf Block-1a/1b-Split (R.7) umgestellt. Vorher referenzierte den nicht-mehr-existierenden monolithischen PipelineStateManager. -->
Block 1a (Quick-Wins im heutigen Code) gilt als done, wenn:
- [ ] resend_btn-Visibility wird nur an EINER Stelle berechnet (zentraler `predResendVisible`-Helper, der die 6 verstreuten Mutations-Sites ersetzt — siehe §11.2.2 Block-1a Schritt 5).
- [ ] recordButton.text/isEnabled wird nur an EINER Stelle gesetzt (zentraler Resolver innerhalb KeyboardUiController, in Block 5 dann final in LayoutCatalog).
- [ ] Service.onSingleRowModeToggled triggert KSM.refresh() (Quick-Win-Fix).
- [ ] Service.onAudioFocusToggled triggert KSM.refresh() (Quick-Win-Fix).

<!-- FIX: Phase-C C-1 (2026-05-14) – Modul-Zähler 12 → 13 aktiv (KeyboardInputModule §15.6). -->
Block 1b (DictateUiState + DictateOrchestrator + 13 aktive Module) gilt als done, wenn:
- [ ] `DictateUiStateStore` ist alleinige State-SSOT — `RecordingStateController.state`, `KeyboardUiController.state`, `KeyboardStateManager.contentArea/isSmallMode` sind eliminiert. Verifiziert via `grep` (siehe §9.6 End-of-Block-Cleanup-Check).
- [ ] `DictateOrchestrator.dispatch(Action)` ist der einzige öffentliche Mutations-Eingang (F-8 Single Dispatch). Verifiziert via Architektur-Test, der `_state.update`-Calls außerhalb von Modul-`reduce` rejects.
- [ ] `PipelinePrefMirror.attach(store)` läuft **VOR** `recovery.recover(store)` (codiert im Orchestrator-`init`, §4.3). Verifiziert via `DictateOrchestratorInitOrderTest.kt` mit `FakePipelinePrefMirror` (records attach-Reihenfolge).
- [ ] **Atomarität `setSmallMode`:** der frühere sequenzielle `KSM.setSmallMode`-Pfad ist in einem einzigen `store.update`-Reducer-Aufruf konsolidiert (`it.copy(layout = it.layout.copy(smallMode = enabled, contentArea = MAIN_BUTTONS))`). Subscriber sehen nie einen Stale-Zwischen-Zustand. Verifiziert in `LayoutModuleAtomicityTest.kt`.
- [ ] **PersistentList-Idiom:** alle Reducer, die `pendingSessions` mutieren, verwenden `.add` / `.removeAll` / `.removeAt`. Verifiziert via Lint-Check `NoToMutableListOnPersistentList` (oder Code-Review-Checkliste in Block 1b).
- [ ] **Initial-State-Race-Fence (NEU Phase-B S-1):** ein Subscriber, der unmittelbar nach `bindService` auf `state.collect` attached, sieht **mindestens** die Pref-Mirror-Werte (nicht den `DictateUiState.initial()`-Default). Test: `DictateOrchestratorBootRaceTest.kt` mit `FakeSharedPreferences`, asserts dass die erste `state.value`-Emission Pref-Werte enthält.
<!-- FIX: Phase-B S-4 (2026-05-13) – 4 neue Acceptance-Klauseln: ProGuard-Robustheit, Vollständigkeits-Check, Cascade-Order, shutdown-Order. -->
<!-- FIX: Phase-C C-1 (2026-05-14) – stale Z. ~590 → Section-Anchor (Line-Drift). -->
- [ ] **Phase-B S-4 ProGuard-Robustheit:** ein Release-Build (`./gradlew assembleRelease`) installiert sich auf einem API-34-Test-Device; nach Install dispatcht ein instrumented Smoke-Test eine konkrete Action (z.B. `Action.RecordingAction.StartRecording`) und assertet `DispatchOutcome.Applied` (nicht `Unrouted`). Verifiziert, dass die ProGuard-Keep-Regel aus §4.3 (ProGuard-Hinweis-Block direkt unter `DictateOrchestrator`-Snippet) tatsächlich in `app/proguard-rules.pro` aufgenommen wurde. Test-Datei `OrchestratorReleaseSmokeTest.kt` (`app/src/androidTest/...`).
- [ ] **Phase-B S-4 Vollständigkeits-Check:** ein gezielter Unit-Test entfernt das `KeyboardInputModule` aus `DictateModuleRegistry.all` (test-only Copy der Liste) und erwartet einen Init-Time-Failure (`IllegalArgumentException` mit "Fehlende Modul-Routing für Action-Subtypen: [KeyboardInputAction]"). Verifiziert dass der Vollständigkeits-Check (§4.8 init) greift. Test-Datei `DictateModuleRegistryTest.kt`.
- [ ] **Phase-B S-4 Cascade-Order-Determinism:** ein Test mit zwei Mock-Modulen `FakeAModule` und `FakeBModule`, die beide auf denselben Idle→Active-Übergang reagieren und je eine eigene Cascade-Action emittieren. Reihenfolge in `modules`-Liste: A vor B. Test verifiziert dass die zweite Cascade-Action (von B) den State **inklusive** der ersten Cascade-Mutation (von A) sieht. Test-Datei `DictateOrchestratorCascadeOrderTest.kt`.
- [ ] **Phase-B S-4 shutdown-Order:** ein Test mit `FakeModule`, dessen `terminate(services)`-Implementation auf `services.scope.isActive == true` assertiert. Verifiziert dass `shutdown()` vor `serviceScope.cancel()` läuft. Plus: Spy-basierte Verifikation der Aufruf-Reihenfolge (`terminate` → `cancel`). Test-Datei `OrchestratorShutdownOrderTest.kt` (im Block-2-Acceptance, weil Service-Lifecycle-Test).
- [ ] Alle existierenden Use-Cases (UC1-UC7 + UC-extra-1 bis UC-extra-10 aus _pending-state-machine-visibility-owners.md §4) funktionieren weiterhin.
- [ ] **Resend-Cooldown-Visibility-Trennung (FIX Issue 3.0.9):** `predResendVisible` reflektiert NICHT `resendCooldown` — Cooldown betrifft NUR `enabledResolver` (disabled+alpha 0.4f), nicht `visibilityPredicate`. Verifiziert in Block-1-Unit-Test (Permutation `lastAudioExists=true` + `resendCooldown=true` → visibility=VISIBLE, enabled=false).
- [ ] **Cross-Module-Cascade-Verifikation (FIX Issue 3.0.10) — pro §15.1-Cascade-Eintrag ein Acceptance-Punkt:**
  - [ ] **PipelineModule.PipelineDone → ResendModule.MarkLastAudio:** nach Pipeline-Done ist `state.resend.lastAudioExists = true` ohne weiteres User-Input.
  - [ ] **PipelineModule.PipelineDone → LivePromptModule.ChainNext:** nach Pipeline-Done wird der nächste LivePrompt-Schritt (falls vorhanden) ausgelöst.
  - [ ] **AudioModule.AudioFocusLoss → RecordingModule.Pause:** bei `AudioFocusLoss` während Recording.Active wechselt `state.recording` zu `Paused`.
  - [ ] **OverlayModule auf Recording-Active + ImeViewHidden → ViewMode.HOVER:** korrekter ViewMode-Wechsel; HOVER wird automatisch eingenommen.
  - [ ] **OverlayModule auf PipelineDone (in HOVER) → ViewMode.KEYBOARD:** "Geist-Widget"-Bug strukturell ausgeschlossen (T7-Cascade, Cluster mit 3.1.2).
  - [ ] **PipelineModule auf Reprocess-Override → LanguageModule.Override:** Sprache wird gesetzt.
  - [ ] **OverlayModule auf ViewModeAction.CloseClicked (HOVER) → PipelineModule.Cancel + Audio-File-Cleanup:** Audio-File wird gelöscht, DB-Status `cancelled`, kein Notification-Eintrag verbleibt (Cluster mit 3.1.7).
  <!-- FIX: Issue PENDING-3 / Spec-3 Reducer Simplified – OverlayAction.ResetSuppressBit Acceptance -->
  - [ ] **RecordingModule auf Idle → Preparing → OverlayAction.ResetSuppressBit:** Wenn `state.overlay.suppressAutoOverlayUntilNextSession == true` (User schloss zuvor das HOVER-Overlay) und der User dispatcht `RecordingAction.StartRecording`, dann ist nach dem Dispatch `state.overlay.suppressAutoOverlayUntilNextSession == false` ohne weitere User-Aktion. Cancel in Preparing triggert KEINEN erneuten Reset (Boundary-Test deckt nur `Idle → Preparing` ab). Verifiziert in `RecordingModuleResetSuppressBitTest.kt` (Spec 3 §14.1).
  <!-- FIX: KG-RSB-2 Resolution (2026-05-11) – Regression-Test gegen Wiedereinführung des Self-Filters -->
  - [ ] **R.RSB-FIX-A Self-Cascade-Regression-Test:** `DictateOrchestratorTest.kt::recordingModule_idleToPreparing_emitsResetSuppressBit_viaSelfCascade()` — fungiert als Regression gegen die Wiedereinführung des Self-Filters `it.id != module.id` in §4.3 Step 5. Setup: `suppressAutoOverlayUntilNextSession = true`, dispatch `RecordingAction.StartRecording`, assert: nach `dispatchInternal`-Rückkehr ist `store.snapshot.overlay.suppressAutoOverlayUntilNextSession == false`. Wird der Filter versehentlich wieder eingebaut, schlägt dieser Test rot fehl, bevor der Production-Bug Production erreicht.
  - Verifiziert via Unit-Test pro Cascade-Pfad mit Mock-Modules (`FakeRecordingModule`, `FakeAudioModule`, etc.). (Spec 1 hat aktuell keine eigene Test-Strategie-Sektion; Test-Stub wird in Block-1-Implementation als `CrossModuleCascadeTest.kt` angelegt.)

Block 3 (DB-Persistence) gilt als done, wenn:
- [ ] M3→M4-Migration läuft fehlerfrei auf einer Test-DB.
- [ ] Alle Checkpoint-Hooks schreiben korrekte DB-Updates.
- [ ] `recoverFromDb()` lädt stuck Sessions korrekt.
- [ ] Cleanup-Policy (>7d alte INSERTED Sessions) läuft auf Service-Start.
<!-- FIX: Issue 2.1.20 / R.16 + 2.1.21 / R.17 + 2.1.11 / R.9 + PENDING-2 – Acceptance-Erweiterungen -->
<!-- FIX: Phase-B S-1 (2026-05-13) – Test-Datei-Naming auf PipelineRecoveryTest umgestellt; Recovery-Logik lebt in `PipelineRecovery` (§4.6), nicht im monolithischen PipelineStateManager. -->
- [ ] **R.16a Recovery aus RECORDING:** Process killed während RECORDING → Session ist post-Recovery `status=FAILED`, `lastErrorType=UNKNOWN`, `lastErrorMessage="recording-interrupted-by-process-death"`, Audio-File aufgeräumt (siehe §6.3). **Test:** `PipelineRecoveryTest.kt::recover_recordingPromoteToFailed_andCleansAudioFile()` — Asserts: `dao.updateStatus(id, "FAILED")` aufgerufen, `dao.updateError(id, "UNKNOWN", "recording-interrupted-by-process-death")` aufgerufen, `File(audioPath).exists() == false`, `dao.clearAudioFilePath(id)` aufgerufen.
- [ ] **R.16b Recovery aus TRANSCRIBING (Datei ok):** Process killed während TRANSCRIBING, Audio-File noch da → Session ist post-Recovery `status=RECORDED` (Downgrade) und erscheint in `pendingSessions`. **Kein** Auto-Resume (D4 / OPEN-4). **Test:** `PipelineRecoveryTest.kt::recover_transcribingDowngradeToRecorded_whenAudioPresent()` — Asserts: `dao.updateStatus(id, "RECORDED")` aufgerufen, `dao.updateError(id, null, null)` aufgerufen (Stale-Error-Clear, siehe §6.3), Session ist in `store.snapshot.pendingSessions`, KEIN `store.snapshot.pipeline = Running(id, …)` (kein Auto-Resume).
- [ ] **R.16c Recovery aus TRANSCRIBING (Datei weg):** Process killed während TRANSCRIBING, Audio-File ist verschwunden → `status=FAILED` mit `lastErrorMessage="audio file vanished before transcription"`. **Test:** `PipelineRecoveryTest.kt::recover_transcribingPromoteToFailed_whenAudioMissing()` — Asserts: `dao.updateStatus(id, "FAILED")` aufgerufen, `dao.updateError(id, "UNKNOWN", "audio file vanished before transcription")` aufgerufen, `dao.clearAudioFilePath(id)` aufgerufen, Session NICHT in `store.snapshot.pendingSessions`.
- [ ] **R.16 Race-Test:** parallel-Recording während Recovery führt nicht zu pendingSessions-Override (Merge-Operation).
- [ ] **PENDING-2 Migration-CHECK:** MigrationTo4Test verifiziert (a) `INSERT … status='RECORDING'` und `… status='TRANSCRIBING'` werden akzeptiert, (b) ungültiger Wert wirft `SQLiteConstraintException`.
- [ ] **R.17 Idempotenz:** Replay nach view-recreate führt nicht zu Doppel-Insertion (DB-Idempotenz-Test mit `@Insert(onConflict = REPLACE)`).
- [ ] **R.17 PersistenceError-Pfad:** DB-Crash mid-Save führt zu `pendingSessions`-failed-Marker, nicht zu State/DB-Inkonsistenz.
- [ ] **R.9 View-Recreate-Vertrag (Robolectric):** Rotation während aktiver Pipeline — nach `onFinishInputView` + `onCreateInputView` ist der Subscriber neu attached und der Pipeline-State ist korrekt gerendert.
<!-- FIX: Phase-B S-2 (2026-05-13) – S-2-spezifische Acceptance-Punkte ergänzt. -->
- [ ] **Phase-B S-2 androidTest-Smoke:** `AndroidTestSetupSmokeTest.smoke()` läuft grün via `./gradlew connectedDebugAndroidTest` VOR `MigrationTo4Test`-Implementation (verifiziert dass `androidTest/`-Verzeichnis + `room-testing`-Dependency korrekt verdrahtet sind, siehe §11.7.0a).
- [ ] **Phase-B S-2 Doppel-Sicherung HistoryAdapter:** Der bestehende `try { SessionStatus.valueOf(...) } catch (IllegalArgumentException e) { RECORDED }`-Wrapper in `HistoryAdapter.java:131-135` **bleibt erhalten** (Downgrade-Verträglichkeit) und der neue `default:`-Branch im `switch` (KG-SST-4) wird **zusätzlich** ergänzt. Unit-Test in HistoryAdapter-Robolectric-Coverage: bei `session.status = "UNBEKANNT"` zeigt der Adapter Pending-Badge (RECORDED-Fallback durch try/catch), bei einer fiktiven 7. Enum-Variante ohne `case` triggert der `default:`-Branch `Log.wtf` + `GONE`. Disjunkte Failure-Modes (§6.1.3 Doppel-Sicherung-Block).
- [ ] **Phase-B S-2 Cleanup-Reihenfolge im Service-Idle-Stop:** im `stopSelf`-Pfad läuft `dao.deleteInsertedOlderThan(cutoff)` VOR `cleanupOrphanedTerminalAudio()` VOR `stopSelf()`. Test: `DictatePipelineServiceCleanupOrderTest.kt` mit Mock-DAO + Mock-Filesystem verifiziert die Call-Reihenfolge.
- [ ] **Phase-B S-2 SessionStatus-KDoc-Update:** `database/entity/SessionStatus.kt:6` KDoc reflektiert die neue Doppel-Truth-Realität — die alte Aussage "Runtime state (TRANSCRIBING, PROCESSING) is NOT stored here — it lives in ActiveJobRegistry" ist nach M4 falsch und muss auf "RECORDING/TRANSCRIBING leben jetzt in DB + Registry; Registry bleibt Performance-Cache + Single-Job-Lock (siehe Plan §6.1.1 + KG-SST-5)" umformuliert sein. Verifiziert via Code-Review-Checkliste.

<!-- FIX: Phase-B S-7 (2026-05-13) – Block 4 (AudioFileFactory) Acceptance neu eingeführt. Vorher
     war Block 4 acceptance über §4.11 KG-AFF-Marker verstreut; jetzt explizite Bullet-Liste. -->
Block 4 (AudioFileFactory + Pre-Dispatch-Allocation + Legacy-Migration) gilt als done, wenn:
- [ ] **AudioFileFactory + Default-Impl angelegt:** `core/AudioFileFactory.kt` (Interface) +
  `core/CacheDirAudioFileFactory.kt` (Default-Impl) mit Lazy-Init via `cacheDirProvider: () -> File`
  (§4.11.3 + KG-AFF-5). `allocate()` erzeugt `cacheDir/audio/rec_{ts}_{uuid8}.m4a`-Pfade;
  `cleanupOrphans(referencedPaths)` filtert mit 60s-Cutoff (KG-AFF-4).
- [ ] **`CacheDirAudioFileFactoryTest` grün:** 8+ Unit-Tests (alle in §4.11.9), inkl. KG-AFF-4
  Cutoff-Test (`cleanupOrphans skips files younger than CUTOFF_GRACE_MS`) und KG-AFF-5
  Null-Check-Test (`cacheDirProvider = { null }` wirft `IllegalArgumentException`).
- [ ] **Pre-Dispatch-Allocation in Resolvers (R.2):** Spec 2 §8.5 `resolveRecordAction` und
  Spec 3 §3.1 `resolveOverlayRecordAction` rufen `services.audioFileFactory.allocate()` mit
  IOException-Toast-Fallback. `Action.RecordingAction.StartRecording` trägt `audioFile`-Argument.
  Verifiziert via `ResolverPreDispatchAllocateTest.kt` (handgeschriebener `FakeAudioFileFactory`).
- [ ] **`Effect.AllocateMediaRecorder` 3-arg:** Definition + Reducer-Use + EffectHandler-Use sind
  konsistent (S-4 F-3, bereits eingearbeitet); Block 4 verifiziert via `RecordingModuleTest` und
  `assembleDebug`-Smoke (keine Compile-Errors).
- [ ] **String-Resource `dictate_storage_full`** in `values/strings.xml` (EN) und
  `values-de/strings.xml` (DE) angelegt. Verifiziert via `grep -rn "dictate_storage_full" app/src/main/res/`.
- [ ] **Sofort-Delete nach Persist (KG-AFF-1):** `PipelineOrchestrator.persistNewSession`
  ruft `audioFile.delete()` nach erfolgreichem `persistFromCache` (siehe Code-Snippet §4.11.6.1).
  Verifiziert via `PipelineOrchestratorPersistTest.kt` (Mock-RecordingRepository + temp-File).
- [ ] **`LegacyAudioFileMigration` (KG-AFF-2):** Klasse angelegt, Pref-Flag-idempotent, im
  Service-`onCreate` Schritt 6.5 aufgerufen. DAO-Query hat
  `WHERE status NOT IN ('FAILED', 'CANCELLED', 'COMPLETED')`-Klausel (Phase-B S-7 F-3 Idempotenz).
  Verifiziert via `LegacyAudioFileMigrationTest.kt`: (a) erster Lauf markiert RECORDING/RECORDED/
  TRANSCRIBING-Sessions mit legacy-Path als FAILED + `audio_file_path_legacy_purged`-Reason; (b)
  bereits-FAILED Sessions mit anderer `last_error_message` werden NICHT überschrieben; (c)
  zweiter Lauf nach Pref-Flag-Set ist no-op; (d) Legacy-File wird gelöscht falls vorhanden.
- [ ] **Recursive Cache-Clear (KG-AFF-3):** `PreferencesFragment.clearCacheRecursively` +
  Cache-Size-/File-Count-Helper sind recursive. Race-Schutz gegen aktive Recording (Phase-B S-7
  F-10): Click ist disabled + Toast bei `state.recording !is Idle`. Verifiziert via
  `PreferencesFragmentRecursiveClearTest.kt` (Robolectric).
- [ ] **Boot-Cleanup-Hook:** `audioFileFactory.cleanupOrphans(referencedPaths)` läuft einmalig
  im `serviceScope.launch(Dispatchers.IO)` im `Service.onCreate` (§4.11.5.1 Schritt 8). DB-Read
  via `findAllAudioFilePaths()`; Fail-Catch loggt WARN, blockt nicht den Boot. Verifiziert via
  `DictatePipelineServiceBootOrphanCleanupTest.kt`.
- [ ] **Recovery-Coupling für v4-Stati (Phase-B S-7 F-5):** Recovery-Pfad behandelt
  RECORDED/RECORDING/TRANSCRIBING-Stati korrekt gegenüber File-Existenz (siehe §4.11.6
  Recovery-Coupling-Tabelle post-S-7). Erweiterung der existierenden R.16a/b/c-Tests
  (Block 3-Acceptance) — kein neuer Test, nur Verifikation dass die Tabelle vollständig ist.
- [ ] **`RecordingModule.reduceFailure` für AllocateMediaRecorder (Phase-B S-7 F-7):** State-
  Rollback `Preparing → Idle` + `Effect.ReleaseMediaRecorder` + `Effect.DeleteAudioFile`. Test:
  `RecordingModuleFailureTest::allocateFailure_rollsBackToIdle()` mit `FakeServices` und
  `AllocateMediaRecorder`-Effect-Wurf.
- [ ] **Service-Field-Entfernen:** `DictateInputMethodService.audioFile`-Field Z. 208 + Z. 1612
  Allokations-Zeile sind gelöscht (Verifikation per `grep -rn "audioFile" app/src/main/java/`).
  `recordingStateController.startRecording(audioFile, ...)`-Aufruf wandert in
  `RecordingModule.runEffect(Effect.AllocateMediaRecorder)`.

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

<!-- FIX: Phase-B S-5 (2026-05-13) – SYSTEM_ALERT_WINDOW-Cross-Link verankert. Plan hat sie
     in Spec 3 §5.7 als "Manifest-Eintrag" — Block 2 ist der Service-Manifest-Diff, also
     gehört der Cross-Link hier, damit der Implementer beide Permission-Sets in EINEM
     Manifest-Patch landet (keine getrennten Commits). -->
**SYSTEM_ALERT_WINDOW (Cross-Link auf Spec 3 §5.7):** Das Floating-Overlay-Feature (Block 6)
benötigt zusätzlich `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />`.
Diese Permission ist auf API < 23 install-time-granted, auf API ≥ 23 special-permission via
`Settings.canDrawOverlays()`. Der Block-2-Manifest-Diff kann die Permission BEREITS DEKLARIEREN
(no-op bis das Overlay-Feature in Block 6 verdrahtet ist) — das eliminiert einen zweiten
Manifest-Commit + macht Phase-1 / Phase-2-Trennung weniger spröde.

<!-- FIX: Phase-C C-2 (2026-05-14) – Caption "drei Permission-Gruppen" → "vier Permission-Einträge,
     drei Service-Permissions + eine Overlay-Permission". Vorher Off-by-One-Counter durch
     SYSTEM_ALERT_WINDOW-Ergänzung. -->
**Block-2-Manifest-Diff (final, vier Permission-Einträge — drei Service-Permissions + die vorab deklarierte Overlay-Permission):**

```xml
<!-- Block 2 (Service-Permissions, drei Einträge) -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<!-- Block 6 (Overlay-Permission), aber im Block-2-Manifest-Diff vorab deklariert: -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

#### §11.1.2 Notification.Builder — konkrete Implementation

**Channel-Setup** (im `DictatePipelineService.onCreate` — siehe §7.3 + §11.1.4 für die
verbindliche Aufruf-Reihenfolge: `ensureNotificationChannel()` läuft **vor**
`startForegroundCompat(…)`, sonst `startForeground` mit ungültigem Channel → ANR-Risk
auf API < 26 nicht relevant, aber auf API ≥ 26 wirft `NotificationManager` eine
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

<!-- FIX: Phase-B S-5 (2026-05-13) – §11.1.2 onStartCommand-Snippet auf F-11 (PipelineActionRouter + DictateOrchestrator) umgestellt. Pre-F-11-Snippet rief `stateManager.pauseRecording()` etc. — `PipelineStateManager` existiert nach 2026-05-10 nicht mehr. SoT für onStartCommand-Pfad ist §7.3 (PipelineActionRouter.dispatch). NOTIF_ID-Wert: siehe Konsolidierung weiter unten. -->
`onStartCommand` reagiert auf die Action-Strings — die Action-Mapping-Logik lebt im
`PipelineActionRouter` (§7.5), der Service ist hier nur Lifecycle-Owner:

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

> **NOTIF_ID-Konsolidierung (Phase-B S-5):** §7.4 (`PipelineNotificationCoordinator`)
> definiert `companion object { const val NOTIF_ID = 0xD1C7A7E }` — der Coordinator ist
> SoT. Die ältere Doppel-Definition in §11.1.2 (`private const val NOTIF_ID = 1001`)
> ist **gestrichen**, der Service referenziert `PipelineNotificationCoordinator.NOTIF_ID`.
> Zwei Definitionen unterschiedlicher Werte (`1001` vs `0xD1C7A7E`) hätten zur
> Race-Bug-Klasse "Service ruft `startForeground(1001, …)`, Coordinator ruft
> `nm.notify(0xD1C7A7E, …)` → zwei separate Notifications" geführt — eine bleibt
> als sticky-FGS-Notification, die andere wird als reguläre Notification überlagert.

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

<!-- FIX: Phase-C C-2 (2026-05-14) – stale `stateManager.state.value` → `orchestrator.state.value`
     (Pre-F-11-Drift; SoT für die State-Quelle ist §4.3 DictateOrchestrator.state). -->
**Mitigation:** `onStartCommand` des Service ruft `startForegroundCompat()` synchron als allererste Aktion nach dem Action-Router-Forward. Der Notification-Builder (`PipelineNotificationCoordinator.buildInitial()`) darf KEINE blocking-DB-Calls machen — er liest nur aus `orchestrator.state.value`, das im Memory liegt.

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
### §11.2 Block-1a / Block-1b / Block-2 / Block-3 — Implementierung

#### §11.2.1 Konkrete Code-Pointer pro Migrations-Schritt

Siehe §9.1 – §9.5 oben — alle Migrations-Sites haben jetzt `file:line`-Pointer und Tabellen.

#### §11.2.2 Migrations-Reihenfolge (Block-1a → 2 → 1b → 3, siehe Hauptplan §4)

**Block 1a — Quick-Wins im heutigen Code (kein Module-Pattern, kompilier-grün)**

Ziel: heutiges System auf eine Single-Owner-Visibility-Basis bringen, OHNE die Modul-Architektur einzuführen. Vorbedingung für Block 1b (Module-Architektur kann erst im PipelineService-Container leben, also nach Block 2).

1. **`predResendVisible`-Helper konsolidieren** (Spec 2 §13.5 Gap 5 + Hauptplan R.7) — neue Top-Level-Funktion, alle 6 verstreuten `resendButton.visibility = …`-Sites lesen sie. Quick-Win, kein State-Refactor.
2. **`recordButton.text/isEnabled`-Hybrid auflösen** — ein zentraler Resolver in `KeyboardUiController` (noch kein LayoutCatalog), der die 8 verstreuten Sites in §13.4.1 ersetzt.
3. **Quick-Win-Fixes** (Block-1a Acceptance): `onSingleRowModeToggled` → `KSM.refresh()`-Trigger, `onAudioFocusToggled` → ebenfalls. Heute fehlt `KSM.refresh()` nach `mainButtonsController.refreshAudioFocusIcon` — siehe `DictateInputMethodService.java:2664-2687`.

**Block 2 — DictatePipelineService einführen (Service-Klasse + Bound-Binder, KEIN DB-Schema-Change)**

<!-- FIX: Phase-B S-5 (2026-05-13) – Block-2-Sub-Schritte erweitert um (a) NotificationChannel-Setup als eigenen Schritt vor startForeground (b) Bind-Site-Korrektur onCreateInputView statt onCreate (c) Klärung dass Block-2 noch KEINEN DictateOrchestrator / Module hat (das ist Block 1b) — Block 2 verdrahtet das Skelett mit einem Stub-Composition-Root, der Block-1b dann ersetzt. -->
1. **Service-Klasse anlegen** (`core/DictatePipelineService.kt`) — Skelett, `LocalBinder`, `onCreate`/`onStartCommand`/`onBind`/`onDestroy` (siehe §7.3). In Block 2 ist der Composition-Root noch ein **Stub** — `DictateUiStateStore(DictateUiState.initial())` plus minimaler Forward an die existierenden Controller (PipelineState-Subscribe wird erst in Block 1b vollständig verdrahtet). `audioFileFactory` ist Block 4 — in Block 2 entweder dummy-Field (`lateinit`, wird in Block 4 gewired) oder die Referenz im `ModuleServicesFactory`-Wiring wird als `// TODO Block 4` kommentar-fenced.
2. **NotificationChannel-Setup als eigene private Methode** (`ensureNotificationChannel()`, §11.1.2) — wird in `onCreate` SYNCHRON als erstes nach `super.onCreate()` gerufen (vor jeglicher DI-Verdrahtung). Auf API < 26 no-op.
3. **Bound-Connection-Setup im IME** (siehe §11.3.1) — `bindService` in **`onCreateInputView`** (NICHT `onCreate` der IME), `unbindService` in `onDestroy`. Begründung: Latenz-Argument (50-200 ms first-bind in onCreateInputView-Inflate-Window absorbiert).
4. **Notification + startForeground** verdrahten (§11.1.2 + §7.4). `startForeground` läuft in `onStartCommand` (NICHT `onCreate`!) — Android-Lifecycle-Vertrag (startForegroundService → onCreate → onStartCommand → der erste `startForeground`-Call MUSS in onStartCommand oder onCreate stehen, vor 5-s-Timeout).
5. **Manifest erweitern** (§11.1.1) — Permissions FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, POST_NOTIFICATIONS plus Service-Eintrag mit `foregroundServiceType="microphone"`. Plus SYSTEM_ALERT_WINDOW (Spec 3 §5.7) — gehört thematisch zu Block 6, aber Manifest-Diff darf gemeinsam in Block 2 landen, weil deklarative Permissions kein Code-Path haben (no-op bis das Overlay-Feature in Block 6 wired ist).
6. **POST_NOTIFICATIONS Runtime-Permission-Prompt** in Onboarding ergänzen (§11.5.1) — `OnboardingActivity` ist heute schon vorhanden (Manifest Z. 53); `ActivityResultLauncher` mit Permission-Request für `Manifest.permission.POST_NOTIFICATIONS` (API ≥ 33), Begleit-Text "Dictate zeigt eine persistente Benachrichtigung mit Aufnahme-Steuerung." Plus Block-2-Acceptance "Onboarding zeigt Permission-Prompt auf API 33+".
7. **JobExecutor-Init** wandert vom IME-`onCreate` (Z. 389) in `Service.onCreate` (G7 in §13.5). ⚠ Wird mit dem **alten** `PipelineOrchestrator` (Audio-Pipeline-Runner) gerufen, NICHT mit dem neuen `DictateOrchestrator` — Naming-Konvention §1.x.

<!-- FIX: Phase-C C-1 (2026-05-14) – Modul-Zähler 12 → 13 aktiv (KeyboardInputModule §15.6 ist Phase-1-Pflicht). -->
**Block 1b — DictateUiState + DictateOrchestrator + 13 aktive Module (im PipelineService-Container)**

Ziel: alle State-Mutationen, die heute auf 3 Klassen verteilt sind (RecordingStateController / KeyboardUiController / KeyboardStateManager), werden in einer hierarchischen `DictateUiState`-Klasse + `DictateUiStateStore` konsolidiert. Mutationen laufen ausschließlich über `DictateOrchestrator.dispatch(Action)` → Modul-Reducer.

Reihenfolge der Sub-Schritte:

<!-- FIX: Phase-C C-1 (2026-05-14) – Sub-State-Felder-Zähler präzisiert. §3-Tabelle Z. 289 spricht
     von "13 State-Achsen (= Sub-State-Felder)". Davon sind 12 als eigene Sub-State-Klassen
     modelliert (RecordingState/PipelineUiState sealed, der Rest data class); das 13. Feld
     `pendingSessions` ist ein direktes PersistentList-Feld ohne Wrapper. Plus `lastResultNeedsManualPaste`. -->
1. **DictateUiState-Datentyp anlegen** (neue Datei `state/DictateUiState.kt`) — pure Daten-Klasse mit 12 Sub-State-Typen (`RecordingState` sealed, `PipelineUiState` sealed, `AudioState`/`LayoutState`/… data classes) + `pendingSessions: PersistentList<PendingSession>` als 13. Achse + 1 Top-Level-Bool (`lastResultNeedsManualPaste`). Siehe §3-Tabelle.
2. **DictateModule-Interface + DictateOrchestrator + ModuleServicesFactory anlegen** (§4.2 / §4.3 / §4.7). Skelett mit `Action`-Sealed-Class (leer), keine konkreten Module noch.
3. **RecordingModule implementieren** (§15.2) — `RecordingStateController.kt:128-321`-Logik wandert in `RecordingModule.reduce + runEffect`. Existierende `RecordingStateController.Callback`-Empfänger werden auf `state.collect`-Subscriber umgebaut. ⚠ Achtung: `RecordingManager` und `BluetoothScoManager` haben Callback-Backrefs auf den Controller — die müssen mitgezogen werden (Module-Effekt-Pfade dispatchen `Action.RecordingAction.MediaRecorderReady` etc. via `services.emitAction`).
4. **PipelineModule implementieren** — `KeyboardUiController.kt:147-353`-Logik wandert in `PipelineModule.reduce + runEffect`. Public-API-Methoden auf KeyboardUiController werden via `orchestrator.dispatch(Action.PipelineAction.X)` ersetzt.
5. **resend_btn-Predicate (final)** — der `predResendVisible`-Helper aus Block 1a wird in den `LayoutCatalog.RESEND`-Slot überführt (Spec 2 §3.2 + §13.1). Bis Block 5 läuft der Subscriber transitionsmäßig im IME-Service:
   ```kotlin
   // Transitional in Block 1b (wird in Block 5 durch LayoutCatalog ersetzt):
   orchestrator.state.collect { state ->
       val visible = state.resend.lastAudioExists && state.resend.resendEnabled
           && state.recording is RecordingState.Idle
           && state.pipeline is PipelineUiState.Idle
       resendButton.visibility = if (visible) View.VISIBLE else View.GONE
   }
   ```
6. **LayoutModule implementieren** — `KeyboardStateManager.contentArea/isSmallMode` wandern in `LayoutState` (Sub-State). `setSmallMode(enabled)` wird zu einem atomaren `reduce`-Aufruf, der `LayoutState.copy(smallMode = enabled, contentArea = MAIN_BUTTONS)` in einer Mutation atomar setzt — eliminiert das heutige sequenzielle 2-Step-Schreiben.
<!-- FIX: Phase-C C-1 (2026-05-14) – Pref-Zähler 15 → 19 (zählt §4.5 initialMirror-Block exakt:
     layout 3 + audio 3 + resend 1 + features 4 + theming 4 + overlay 4 = 19, konsistent mit
     Phase-B S-4 Hinweis "Phase 1 — hardcodierte Mappings für 19 Prefs"). -->
7. **PrefMirror-Wiring (§4.5):** `PipelinePrefMirror` mirrort die 19 UI-state-relevanten Prefs in die Sub-State-Klassen. Wird im `DictateOrchestrator.init` synchron attached.
8. **Recovery-Wiring (§4.6):** `PipelineRecovery` lädt `pendingSessions` aus DB in `store`. Wird im `DictateOrchestrator.init` async (`scope.launch`) gestartet — **NACH** `prefMirror.attach`.

**Block 3 — DB-Persistence (Schema-Migration M3→M4)**

<!-- FIX: Phase-B S-2 (2026-05-13) – Block-3-Sub-Schritte umstrukturiert: androidTest-Setup als eigener Schritt 0 (substantieller Test-Infrastruktur-Aufbau, wurde vorher als Implementation-Detail von Schritt 2 versteckt). -->

0. **androidTest-Infrastruktur anlegen (NEU, eigener Schritt — siehe §11.7.0a unten):**
   - Verzeichnis `app/src/androidTest/java/net/devemperor/dictate/database/migration/` neu anlegen (existiert heute nicht — verifiziert per `ls app/src/`).
   - `gradle/libs.versions.toml` erweitern um `room-testing = { module = "androidx.room:room-testing", version.ref = "room" }` und `androidx-test-runner = { group = "androidx.test", name = "runner", version = "1.5.2" }`, `androidx-test-rules = { group = "androidx.test", name = "rules", version = "1.5.0" }`.
   - `app/build.gradle:74-75` erweitern (in Z. 74-75, NACH `androidTestImplementation libs.espresso.core`):
     ```gradle
     androidTestImplementation libs.room.testing
     androidTestImplementation libs.androidx.test.runner
     androidTestImplementation libs.androidx.test.rules
     ```
   - Test-Runner `androidx.test.runner.AndroidJUnitRunner` ist bereits in `defaultConfig.testInstrumentationRunner` (Z. 18) konfiguriert — keine Änderung nötig.
   - Verifikation: leerer Smoke-Test (`Trivial androidTest with @Test fun smoke() = assertTrue(true)`) muss vor MigrationTo4.kt-Implementation grün laufen, um zu beweisen dass das Verzeichnis-Setup funktioniert.
1. **`SessionStatus.kt` erweitern** um `RECORDING` + `TRANSCRIBING` (siehe §6.1). KDoc auf `SessionStatus.kt:6` ("Runtime state … lives in ActiveJobRegistry") an die neue Doppel-Truth-Realität anpassen ("RECORDING/TRANSCRIBING leben jetzt DB + Registry; Registry bleibt Performance-Cache + Single-Job-Lock", siehe KG-SST-1 + KG-SST-5).
2. **MigrationTo4.kt anlegen** mit table-recreate-Strategie + CHECK-Erweiterung (siehe §6.1).
3. **Schema-Version + addMigrations** in `DictateDatabase.kt` (§6.1). Generiert beim ksp-Build automatisch `app/schemas/.../DictateDatabase/4.json` — diese Datei MUSS Teil des Commit sein (Code-Review-Anker für Schema-Diff).
4. **`SessionEntity.insertedAt`-Feld** ergänzen (§6.1). KEIN zusätzlicher `Index("inserted_at")` (siehe Begründung unter §6.1).
5. **`SessionDao`-Methoden:** `markInserted` / `findPendingInsertion` / `deleteInsertedOlderThan` / `getSessionsByStatuses` + `findAllAudioFilePaths` + `markLegacyAudioSessionsFailed` (§6.1 + §6.3 + §4.11) + `findOrphanedTerminalAudio` + `clearAudioFilePathBulk` (§6.3.1 KG-SST-2).
6. **`SessionManager`-Methoden:** `transitionRecording` + `transitionRecorded` + `transitionTranscribing` + `markInserted` neben den bestehenden `finalize*`-Methoden (§6.1).
7. **Checkpoint-Hooks pro Modul** (§6.2-Tabelle): RecordingModule + PipelineModule emittieren DAO-Calls als SideEffects (`Effect.PersistStatus(sessionId, status)` etc.). **Reihenfolge: DB-Schreib VOR `ActiveJobRegistry.update`** (KG-SST-5, siehe §6.2 Persistenz-Vertrag R.17).
8. **`PipelineRecovery.recover()`** liest pending sessions: RECORDING→FAILED+cleanup, TRANSCRIBING→RECORDED-Downgrade-oder-FAILED, lädt sie in `state.pendingSessions` (§6.3).
9. **Cleanup-Policy** beim Service-Idle-Stop: `dao.deleteInsertedOlderThan(now - 7d)` UND `cleanupOrphanedTerminalAudio()` (§6.3.1 KG-SST-2) einmal vor `stopSelf()`. Reihenfolge: `deleteInsertedOlderThan` → `cleanupOrphanedTerminalAudio` → `stopSelf`.
10. **`HistoryAdapter.java`-`switch` erweitern** um `case RECORDING:` + `case TRANSCRIBING:` + `default: Log.wtf + GONE` (§6.1.3). Der bestehende try/catch-Wrapper Z. 131-135 bleibt erhalten (Downgrade-Verträglichkeit, siehe Doppel-Sicherung-Erläuterung in §6.1.3).
11. **`ResendStatusDispatcher.kt`-`when` erweitern** um `RECORDING, TRANSCRIBING → ResendAction.NoOp` (§6.1.3). Kotlin-`when` ist exhaustive — Build-Fehler ohne diese Branches.
12. **`HistoryDetailActivity.java:287-299`** — KEINE Code-Änderung (existierende Whitelist `RECORDED || FAILED || CANCELLED || COMPLETED` schließt RECORDING/TRANSCRIBING automatisch aus, siehe §6.1.3 Konsumenten-Tabelle).
13. **Lint-Setup** (KG-SST-4): `app/build.gradle` um `lint { error += "EnumSwitch"; abortOnError true }`-Block ergänzen. Vorher `./gradlew lint` laufen lassen, Baseline-Findings reviewen.
14. **`strings.xml`** ergänzen: `dictate_status_recording`, `dictate_status_transcribing` (§6.1.3 Patch).

<!-- FIX: Phase-B S-7 (2026-05-13) – Block 4 (AudioFileFactory + Pre-Dispatch-Allocation) explizit als
     eigene Sub-Schritt-Sequenz dokumentiert. §4.11 hat die kanonische Spec; hier die
     Implementer-Reihenfolge der Sub-Schritte, damit Block 4 nicht implizit über die §4.11-Markers
     verstreut sein muss. -->
**Block 4 — AudioFileFactory + Pre-Dispatch-Allocation + Legacy-Migration (siehe §4.11)**

Ziel: Allokation der Audio-Cache-Files entkoppeln vom MediaRecorder-Setup (Pure-Reducer-Vertrag R.2,
Multi-Job-Kollisions-Freiheit R.8); Legacy-`cacheDir/audio.m4a`-Bereinigung; rekursive "Cache leeren"-
Logik in PreferencesFragment.

Reihenfolge der Sub-Schritte:

1. **`AudioFileFactory`-Interface + `CacheDirAudioFileFactory` anlegen** (`core/AudioFileFactory.kt`,
   `core/CacheDirAudioFileFactory.kt`) — Code-Snippets siehe §4.11.2 + §4.11.3. KG-AFF-4-Cutoff-Filter
   + KG-AFF-5-`requireNotNull` im Lazy-Init direkt mit-implementiert.
2. **`ModuleServices.audioFileFactory`-Field** im `ModuleServices` (§4.7) + im `ModuleServicesFactory`-
   Wiring in `DictatePipelineService.onCreate` (§4.11.5.3 Code-Diff). Phase-B S-7 Reminder:
   `services` wird zusätzlich an `ImeViewBackend`/`OverlayBackend` durchgereicht (Spec 2 §6 / Spec 3
   §4.2 post-S-7) — beide Backends brauchen `services.audioFileFactory` für Pre-Dispatch-Allocate.
3. **`resolveRecordAction`-Resolver erweitern** (Spec 2 §8.5) — 2-arg-Signatur `(state, services) ->
   Action?`, ruft `services.audioFileFactory.allocate()`; bei `IOException` → `services.toastSink.show
   (R.string.dictate_storage_full)` → `null`. Analog `resolveOverlayRecordAction` (Spec 3 §3.1
   post-S-7).
4. **`ButtonSlot.actionResolver`-Typ erweitern** (Spec 2 §3.2) auf `(DictateUiState, ModuleServices)
   -> Action?`. Alle existierenden `{ Action.X }` und `{ state -> Action.X }`-Lambdas in
   `LayoutCatalog`-Slots (Spec 2 §8.1-§8.4 + Spec 3 §3.1) mechanisch erweitern: `{ Action.X }` →
   `{ _, _ -> Action.X }`, `{ state -> Action.X }` → `{ state, _ -> Action.X }`. Compile-Fehler
   beim ersten `./gradlew assembleDebug` zeigt jede Stelle.
5. **`wireStaticHandlers` (Spec 2 §6) + `wireStaticOverlayHandlers` (Spec 3 §4.2)** rufen
   `slot.actionResolver(state, services)` statt der 1-arg-Variante (Phase-B S-7).
6. **`StartRecording`-Action 2-arg-Konstruktor sicherstellen** (Spec 2 §3.3): `data class
   StartRecording(target: InsertionTarget, audioFile: File)`. Beim Spec-2-Read prüfen, dass keine
   1-arg-Callsite zurückbleibt (Spec 3 §3.1 hatte einen — gefixt in Phase-B S-7).
7. **String-Resource `dictate_storage_full`** in `app/src/main/res/values/strings.xml` ergänzen
   (z.B. `"Cache full — recording cannot start."`) und in `values-de/strings.xml` mit Deutsch-
   Übersetzung (`"Cache voll — Aufnahme kann nicht starten."`). **Pflicht-Aufgabe — fehlt
   heute, blockiert sonst den Block-4-Build.**
8. **`Effect.AllocateMediaRecorder`-Signatur 3-arg** (Spec 1 §15.2): `data class AllocateMediaRecorder
   (target, useBluetooth, audioFile)` — siehe S-4 F-3 Fix (bereits eingearbeitet); Block 4 nur
   verifizieren.
9. **`PipelineOrchestrator.persistNewSession`-Patch** (`core/PipelineOrchestrator.kt:854-857`):
   Sofort-Delete des Cache-Files nach erfolgreichem `persistFromCache` (KG-AFF-1, Code-Snippet
   §4.11.6.1).
10. **`LegacyAudioFileMigration` anlegen** (`migration/LegacyAudioFileMigration.kt`) mit dem Code
    aus §4.11.6.2 (KG-AFF-2). Aufruf in `DictatePipelineService.onCreate` Schritt 6.5 (§4.11.5.1
    Sequence-Tabelle). Phase-B S-7 Reminder: DAO-Query hat jetzt
    `WHERE audio_file_path = :legacyPath AND status NOT IN ('FAILED', 'CANCELLED', 'COMPLETED')` —
    schützt bestehende Failure-Information vor Überschreibung.
11. **Boot-Cleanup im Service-`onCreate`** — `audioFileFactory.cleanupOrphans(referenced)` via
    `serviceScope.launch(Dispatchers.IO)` mit `findAllAudioFilePaths()`-DB-Read (§4.11.5.3 Schritt 5
    + §7.3 Z. 3603-3613).
12. **`PreferencesFragment.java:272-296`-Refactor** — `clearCacheRecursively`-Helper + Cache-Size-/
    File-Count-Helpers (Recursive-Versionen) (§4.11.6.3 KG-AFF-3). Hinweis-Block einfügen:
    "wenn `state.recording !is RecordingState.Idle` → 'Cache leeren' deaktiviert + Toast 'Recording
    aktiv'". (Phase-B S-7: Race-Schutz vor offenem MediaRecorder-FD, siehe Finding F-10.)
13. **DictateInputMethodService.java-Felder + Sites entfernen** — `audioFile`-Field Z. 208 sowie
    Allokations-Zeile Z. 1612 (§4.11.5.4 Migrations-Schritte 1-4).
14. **Tests verdrahten** — `CacheDirAudioFileFactoryTest`, `RecordingModuleAudioFileFactoryWiringTest`,
    optional `AudioFileFactoryRobolectricTest` (§4.11.9 Test-Strategie + Skelette).

<!-- FIX: Phase-B S-1 (2026-05-13) – Test-Klassen auf F-11-Module-Pattern umgestellt (RecordingModuleTest statt PipelineStateManagerTest, separate PipelineRecoveryTest). -->
#### §11.2.3 Test-Strategie

Existierende Tests (`app/src/test/java/net/devemperor/dictate/core/`):
- `RecordingStateControllerTest.kt` — pure Kotlin, nutzt `FakeAudioFocusGate.kt`. Block-1b-Auswirkung: Tests werden auf `RecordingModuleTest` umgebaut. State-Assertions ändern sich von `controller.state == X` zu `RecordingModule.reduce(stateBefore, action, ctx).nextState == X` (pure Function, kein Manager-Setup nötig).
- `JobExecutorTest.kt` — bleibt unangetastet (JobExecutor bleibt erhalten — siehe §8 + Plan-Hauptaussage).
- `ActiveJobRegistryTest.kt` — unverändert.

Neue Tests pro Block:

| Block | Neue Test-Klasse | Inhalt |
|---|---|---|
| 1b | `DictateOrchestratorTest.kt` | Action-Routing über `moduleByLeafClass`-Lookup, Cascade-Depth-Counter (R.6), Self-Cascade-Regression (`R.RSB-FIX-A`, siehe §10), Init-Order-Test (PrefMirror VOR Recovery), Boot-Race-Fence (Phase-B S-1). |
| 1b | `RecordingModuleTest.kt` | Pure Reducer-Tests pro State × Action — 4 States × ~7 Actions = ~28 Permutationen. Kein Hardware-Setup (Reducer ist pure). |
| 1b | `PipelineModuleTest.kt` | Pure Reducer-Tests für PipelineUiState-FSM (Idle/Preparing/Running/ReprocessStaging). |
| 1b | `LayoutModuleAtomicityTest.kt` | Verifiziert dass `setSmallMode(true)` in EINEM `store.update` `smallMode = true && contentArea = MAIN_BUTTONS` setzt — Subscriber sehen kein Zwischenstadium. |
| 1b | `DictateUiStateTest.kt` | `data class`-Equality, `copy()`-Verhalten, sealed-class-exhaustivität, `PersistentList.add/removeAll`-Idiom. |
| 1b | `PipelinePrefMirrorTest.kt` | `attach(store)`-Mirroring von 19 Prefs (§4.5 `initialMirror`-Block: 3 layout + 3 audio + 1 resend + 4 features + 4 theming + 4 overlay) in Sub-States, `OnSharedPreferenceChangeListener`-Trigger. <!-- FIX: Phase-C C-2 (2026-05-14) – 15 → 19 Prefs (Konsistenz mit §11.2.2 Schritt 7 post-Phase-C C-1). --> |
| 1b | `PipelineRecoveryTest.kt` | `recover(store)`-Logik gegen `FakeSessionRepo`. |
| 2 | `DictatePipelineServiceTest.kt` | Robolectric-Service-Test: `onCreate`-Lifecycle, `onStartCommand`-Action-Routing, FGS-Start innerhalb 5 s. |
| 2 | `LocalBinderTest.kt` | Bound-Service-Test: `onServiceConnected` triggert `state.collect`-Subscriber. |
| 3 | `MigrationTo4Test.kt` | Room-Migration-Test mit `MigrationTestHelper` — (a) `inserted_at`-Spalte existiert nach Migration, alte COMPLETED-Rows haben `inserted_at = created_at`; (b) CHECK-Constraint akzeptiert `RECORDING`/`TRANSCRIBING` als status-Wert; (c) ungültiger status wird abgelehnt (CHECK-Verstoß → `SQLiteConstraintException`); (d) **alle 4 alten Stati round-trippen verlustfrei** (`migrate3To4_preservesAllLegacyStatuses`); (e) **child-Rows aus `processing_steps`/`transcriptions` überleben den table-recreate** (`migrate3To4_preservesChildRows_processingStepsAndTranscriptions`); (f) **Indices werden nach Migration recreated** (`migrate3To4_preservesIndices`). Detail-Code siehe §11.4.2. |
| 3 | `SessionDaoTest.kt` (erweitert) | `findPendingInsertion`, `markInserted`, `deleteInsertedOlderThan`, `findAllAudioFilePaths`, `markLegacyAudioSessionsFailed` — alle neuen Queries. |
| 3 | `PipelineRecoveryTest.kt` (Block-3-Erweiterung) | 3 neue Recovery-Tests (R.16a/b/c — siehe §10 Acceptance): `recoverFromDb_recordingPromoteToFailed_andCleansAudioFile`, `recoverFromDb_transcribingDowngradeToRecorded_whenAudioPresent`, `recoverFromDb_transcribingPromoteToFailed_whenAudioMissing`. Nutzt `FakeSessionDao` (§11.7.3) + temp-Verzeichnis für Audio-File-Operationen. |

**Test-Doubles:**
- `FakeLocalBinder` — implementiert das gleiche Interface wie `DictatePipelineService.LocalBinder`, hält in-memory `MutableStateFlow<DictateUiState>` + `dispatch`-Recorder. Ermöglicht IME-Tests ohne Robolectric-Service.
- `FakePipelineRunner` — existiert bereits (`JobExecutorTest`-Pattern). Bleibt unverändert.
- `FakeAudioFocusGate.kt` (existiert) — bleibt; wird vom RecordingModule indirekt via `services.audioFocus` konsumiert.
- `FakeModuleServices` — `ModuleServices`-Konkretisierung mit allen Subsystem-Fakes; vom RecordingModule-/PipelineModule-Test verwendet.

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

<!-- FIX: Phase-B S-5 (2026-05-13) – Pre-Bind-Action-Queue als expliziter Pfad dokumentiert.
     §11.3.3 deckt nur den initialen Bind im Same-Process; aber: Notification-Action-Buttons
     können Actions per `startService(intent.setAction(...))` schicken, BEVOR der LocalBinder
     im IME verfügbar ist (z.B. User drückt "Pause" in Notification während Service noch im
     ersten onCreate steckt). Diese Pfad-Klasse braucht eine Klärung. -->
#### §11.3.2a Pre-Bind-Action-Pfad (Notification-Buttons während Boot)

Notification-Action-Buttons feuern Action-Intents an den **Service** (via
`PendingIntent.getService` in §7.5 PipelineActionRouter), NICHT an die IME. Das heißt:
diese Aktionen laufen über `onStartCommand → actionRouter.dispatch(intent)` und
brauchen weder den LocalBinder noch die `bindService`-Connection. Sie sind also
**immun** gegen die Bind-Lifecycle-Race.

Aber: Action-Intents die in `onStartCommand` ankommen, bevor `onCreate` Schritt 5
(`DictateOrchestrator(…)`) durch ist, dispatchen an einen `lateinit`-Orchestrator und
crashen mit `UninitializedPropertyAccessException`. Das ist auf Same-Process /
Main-Thread typischerweise unmöglich (Android queued `onStartCommand` strikt nach
`onCreate`-Completion), aber defensiv:

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

**IME-Bind-Pfad (LocalBinder-basiert):** Aktionen aus dem IME-View (Record-Button,
Pause-Button im Keyboard etc.) laufen über `binder.dispatch(action)`. Wenn der IME-View
in `onCreateInputView` einen Click verarbeitet, BEVOR `onServiceConnected` gefeuert hat,
ist `pipelineBinder == null` und der Click-Handler MUSS dies abfangen:

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

Pflicht-Aufgabe Block-2: neue String-Resource `dictate_service_not_ready` (DE: "Service
startet noch — bitte kurz warten.", EN: "Service is starting — please wait a moment.").

#### §11.3.3 Race: IME-onCreate vor Service-onCreate

Da `DictatePipelineService` im selben Process läuft (D1), gibt es keinen echten "Service noch nicht da"-Race. Reihenfolge:

1. IME-Service `onCreateInputView` → `startForegroundService(intent)` → returned sofort, scheduled service.
2. Android scheduled `DictatePipelineService.onCreate()` auf dem Main-Thread → läuft synchron.
3. IME-Service ruft direkt im Anschluss `bindService(intent, conn, BIND_AUTO_CREATE)` → sieht den frisch erzeugten Service, ruft `Service.onBind()` synchron, postet `ServiceConnection.onServiceConnected` auf dem Main-Looper.
4. IME-Service kehrt aus `onCreateInputView` zurück (Inflate fertig).
5. `onServiceConnected` läuft als nächste Main-Looper-Message.

**Edge-Case:** zwischen Schritt 4 und 5 kann der User KEINEN Button klicken (Touch-Events laufen auf demselben Main-Looper), also kein UI-Race möglich.

**Tatsächlicher Race:** wenn beim allerersten Start die `recoverFromDb()`-Coroutine noch läuft, sieht der erste `state.collect`-Sub die initiale `_state`-Emission ohne `pendingSessions`. Sobald `recoverFromDb` fertig ist, kommt eine zweite Emission mit den geladenen Sessions. Das ist OK — der Subscriber re-rendert.

<!-- FIX: Phase-B S-5 (2026-05-13) – Multi-Bind-Klärung. Plan dokumentiert nicht, ob mehrere
     Clients (IME + Settings-Activity + HistoryDetailActivity) parallel binden dürfen. Ohne
     Klärung wird der Block-2-Implementer entweder eine `BindRefCounter`-Optimierung einbauen
     (Premature-Optimization-Risiko) oder erlauben, dass ungebremst gebindet wird (jeder Bind
     erhöht den Service-RefCounter, hindert `stopSelf()` an effektivem Stop). -->
#### §11.3.4 Multi-Bind-Klärung (Phase-B S-5)

**Erlaubt:** Mehrere Clients dürfen parallel binden. Konkrete Use-Cases:

| Client | Bind-Site | Lifecycle | Zweck |
|---|---|---|---|
| `DictateInputMethodService` (IME) | `onCreateInputView` | View-Recreate-Cycle (Rotation, IME-Open/Close) | Action-Dispatch + State-Subscribe für Keyboard-Render |
| `DictateSettingsActivity` (optional, Phase 2) | `onStart` / `onStop` | Activity-Sichtbarkeit | "Aktive-Sessions-Anzeige" — kein heutiger Bedarf, aber zukünftig denkbar |
| `HistoryDetailActivity` (optional, Phase 2) | `onStart` / `onStop` | Activity-Sichtbarkeit | Live-Update "Pipeline läuft, Detail-Button greyed" |

**Konsequenzen für Block 2:** keine `BindRefCounter`-Klasse, keine Single-Bind-Restriktion.
`LocalBinder` ist ein **Singleton** pro Service-Lifetime; Android dispatcht denselben
IBinder an alle Konsumenten. Jeder Konsument legt seinen eigenen `ServiceConnection` an
und cleanupt in seinem `onDestroy`/`onStop`.

**`stopSelf()`-Interaktion:** `stopSelf()` cancelt das Service-LifecycleToken; Android hält
den Service trotzdem am Leben, solange mindestens eine `bindService`-Connection mit
`BIND_AUTO_CREATE` offen ist. Das heißt: in der Praxis bleibt der Service so lange am
Leben, wie der IME-View existiert (oder eine Activity mit Bind offen ist). Das ist
**gewünscht** — `state.isAllTerminal()` triggert nur `stopSelf`, das eigentliche Stoppen
hängt vom Bind-Counter ab. Wenn der User die IME schließt + keine Activity bindet, geht
der Service tot. Auto-Restart ist `START_NOT_STICKY` (kein Restart).

**Acceptance** (in §10 ergänzt): `DictatePipelineServiceMultiBindTest.kt` mit zwei
ServiceConnections — Bind-A in Test-Setup, Bind-B in Test-Body; Assert beide
`onServiceConnected` empfangen denselben `IBinder`-Instanz; nach Unbind-B bleibt
Service alive bis Unbind-A.

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
> **Zusätzlicher Recovery-Test (eigene Datei, nicht Teil des Migration-Tests):**
> `PipelineRecoveryTest.kt` (Block 1, siehe §11.2) deckt `recover()`-Logik ab —
> RECORDING-Boot → FAILED+cleanup, TRANSCRIBING-Boot mit Audio → RECORDED-Downgrade,
> TRANSCRIBING-Boot ohne Audio → FAILED. Diese Logik testet die Recovery-Tabelle aus §6.3
> end-to-end, ist aber JVM-only (Robolectric) — kein Android-Test-Setup nötig, weil
> SessionDao + DAO-Calls mocked werden. Test-Setup: `PipelineRecovery(FakeSessionRepo)`,
> Assertion auf `store.snapshot.pendingSessions` nach `recovery.recover(store)`.

#### §11.4.3 Edge-Cases bei Migration-Failure

`Room.databaseBuilder` heute (siehe `DictateDatabase.kt:67-103`) hat KEIN `fallbackToDestructiveMigration` — wenn die Migration fehlschlägt, crasht die App beim ersten DB-Zugriff mit `IllegalStateException`. Das ist die richtige Strategie für unser Setting (wir wollen User-Daten nicht verlieren).

**Falls die Migration in der Wildbahn fehlschlägt:** Crash + automatischer Bug-Report. User-Daten bleiben in der alten DB-Datei intakt; die App ist unbenutzbar bis ein Hotfix nachgereicht wird. Dies ist akzeptabel, weil die Migration `ALTER TABLE ADD COLUMN` ist — kann praktisch nicht fehlschlagen (außer bei Disk-Full oder Korruption).

### §11.5 Notification-UX

#### §11.5.1 POST_NOTIFICATIONS-Runtime-Permission (Android 13+)

<!-- FIX: Phase-B S-5 (2026-05-13) – Permission-Prompt-Flow präzisiert: IME-Service kann keinen
     ActivityResultLauncher halten (kein Activity-Context). Prompt-Site ist OnboardingActivity (für
     Fresh-Install) UND DictateSettingsActivity (für Update-Users, deren Onboarding bereits durch
     ist). UI-Hint im Keyboard zeigt subtilen Banner, wenn Permission fehlt — User-Friction-
     Indicator ohne Pflicht-Dialog. -->
**Lifecycle der Permission:**

| API | Default-State | Wirkung |
|---|---|---|
| < 33 (Tiramisu) | implizit granted (legacy permission) | Notification immer sichtbar |
| ≥ 33, niemals angefragt | DENIED | Notification unsichtbar, FGS läuft, kein User-Visibility-Signal |
| ≥ 33, User accepted | GRANTED | Notification sichtbar |
| ≥ 33, User declined | DENIED + don't-ask-again | wie "DENIED", Re-Prompt nur via App-Settings |

**Prompt-Site 1 — Onboarding (Fresh-Install):**

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

**Prompt-Site 2 — Settings-Activity (Update-User, der Onboarding nicht erneut durchläuft):**

Im `DictateSettingsActivity.onResume()` einen ein-maligen Check (per
`SharedPreferences`-Flag `post_notif_prompt_shown`):

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

**IME-User-Friction-Signal:** Wenn der User trotz Decline noch Dictate nutzt und ein
Recording startet, läuft FGS, aber Notification ist unsichtbar — User weiß nicht, dass
das Recording im Hintergrund weiterläuft. Mitigation: ein subtiler Hinweis-Banner im
Keyboard-View, der bei aktivem Recording UND `!checkSelfPermission(POST_NOTIFICATIONS)`
einblendet: "Hinweis: Aufnahme-Benachrichtigung deaktiviert — App-Einstellungen öffnen?".
Klick öffnet `Settings.ACTION_APP_NOTIFICATION_SETTINGS`-Intent. Implementierung: Block 6
(LayoutCatalog) als eigene `BannerSlot`-Predicate.

**Begründung:** IME-Services dürfen aus UX-Gründen keine `requestPermissions`-Dialoge
zeigen (das System würde den Dialog unter der Tastatur rendern und der IME hat keinen
Activity-Context für `ActivityResultLauncher`). Stattdessen prompten wir in zwei
Activities (`OnboardingActivity` für Fresh-Install, `DictateSettingsActivity` für
Update-Users); der IME zeigt nur einen passiv-informativen Banner.

**Block-2-Acceptance** (in §10 ergänzt): Onboarding zeigt POST_NOTIFICATIONS-Prompt auf
API ≥ 33, Decline führt zu sichtbarem Banner im IME-View bei aktivem Recording, Klick
auf Banner öffnet System-Notification-Settings.

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

#### §11.6.2 Audio-Files, die nicht mehr existieren

Bei `recoverFromDb` werden alle `final_output_text != NULL AND inserted_at IS NULL`-Sessions geladen (§6.1). Audio-Files SIND für Insertion nicht mehr nötig (das Result-Text ist bereits in der DB). Daher: kein File-Existence-Check nötig — `pendingSessions` enthält nur den fertigen Text + `sessionId`. Insertion erfolgt aus dem DB-Text.

**Edge-Case:** `recoverFromDb` lädt auch RECORDED-Sessions (Audio aufgenommen, aber keine Pipeline mehr gelaufen — Crash mitten im Recording-Stop). Dafür gilt:

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

Sessions mit `audio_file_path != null` aber Datei existiert nicht (Cache-Cleanup nach App-Update, OS-Storage-Druck-Wipe, oder User-"Cache leeren" — siehe §4.11.6) werden gefiltert + DB-cleanup als opportunistic side-effect:

```kotlin
val ghostSessions = db.sessionDao().getSessionsByStatuses(listOf("RECORDED"))
    .filter { it.audioFilePath != null && !File(it.audioFilePath).exists() }
ghostSessions.forEach {
    db.sessionDao().updateStatus(it.id, SessionStatus.FAILED.name)
    db.sessionDao().updateError(it.id, "UNKNOWN", "audio file vanished")
}
```

#### §11.6.3 User-Kommunikation "Session verloren"

Bei jeder Ghost-Session (siehe oben) wird `lastErrorType=UNKNOWN, lastErrorMessage="audio file vanished"` gesetzt. Die History-Activity (`HistoryActivity`/`HistoryAdapter.java:99-156`) zeigt FAILED-Sessions bereits mit Error-Icon an. Keine zusätzliche UI nötig. Akzeptiert per User-Wahl D4 (kein Auto-Resume).

### §11.7 Migrations-Reihenfolge & Risiko

<!-- FIX: Issue PENDING-2 / Block-3 Resolved – Risiko-Hinweis ergänzt zur table-recreate-Migration -->

#### §11.7.0 DB-Migrations-Risiko (M3 → M4)

Die Migration in §6.1 ist nicht mehr rein additiv (siehe D8-Update in §2). Konsequenzen:

| Risiko | Wahrscheinlichkeit | Mitigation |
|---|---|---|
| Datenverlust durch unterbrochene Migration | Sehr gering — Room führt jede Migration in einer SQLite-Transaktion aus; ein Fehler bricht atomar ab (Schema bleibt auf M3) | Migration ist deterministisch, keine externen Calls; Migration-Test deckt CHECK-Constraint + Backfill ab |
| Migration-Failure auf User-Geräten (z.B. SQLite-Korruption) | Gering | `Room.databaseBuilder` hat KEIN `fallbackToDestructiveMigration` (siehe `DictateDatabase.kt:67-103`) → App crasht statt User-Daten zu killen; Hotfix möglich |
| **FK-Cascade-Datenverlust durch `DROP TABLE sessions`** | Mittel-Hoch — `processing_steps` und `transcriptions` haben **FK auf `sessions.id` mit `ON DELETE CASCADE`** (verifiziert in `MigrationTo3.kt:138` und `:182`). Aber: **SQLite Cascade-Delete feuert NICHT bei `DROP TABLE`** (siehe https://sqlite.org/foreignkeys.html §4.2 — Cascade ist nur für row-level DELETE, nicht für Schema-Operations). MIGRATION_2_3 nutzt denselben Pattern produktiv ohne Datenverlust. | (a) Migration-Test **prüft explizit**, dass `processing_steps` und `transcriptions`-Rows nach Migration noch existieren (siehe §11.4.2 Test `migrate3To4_preservesChildRows`). (b) `PRAGMA foreign_keys = OFF` während Migration ist **nicht** nötig — Room deaktiviert FK-Enforcement während Migrationen sowieso (siehe https://developer.android.com/training/data-storage/room/migrating-db-versions). (c) Lesson aus MIGRATION_2_3: SQLite speichert FK-Referenzen textuell per Name; nach `DROP sessions` + `ALTER … RENAME sessions_new TO sessions` ist die FK wieder gültig — die child-Tabellen werden nicht angefasst. |
| Index-Verlust durch DROP | Gering | Migration recreated alle Indices explizit (siehe MigrationTo4.kt-Step 4); Migration-Test `migrate3To4_preservesIndices` prüft `PRAGMA index_list(sessions)`. |
| Schema-Validator-Mismatch (Room Compile-Time-Check) | Mittel — `audio_duration_seconds` muss ohne SQL-DEFAULT bleiben (siehe MigrationTo3-Kommentar Z. 38-42) | Migration-Snippet folgt der MIGRATION_2_3-Konvention; `exportSchema = true` (siehe DictateDatabase.kt:39) erzeugt Schema-JSON für Validierung im CI; `runMigrationsAndValidate(..., validateDroppedTables = true, ...)` im Test wirft bei Mismatch. |
| Multi-Step-Migration v1→v4 bei Restore aus altem Backup | Gering — App-Backup (Android Auto-Backup / ADB-Restore) enthält die Room-DB-Datei; bei Restore läuft Room durch `MIGRATION_1_2 → MIGRATION_2_3 → MIGRATION_3_4` sequenziell (Room-Standardverhalten, kein Sonderpfad nötig) | **Automatisierter Test (KG-SST-3 RESOLVED 2026-05-11):** `MigrationTo4Test.migrate1To4_chain_preservesData()` (siehe §11.4.2) — inserted eine v1-RECORDING-Row, validiert nach voller Chain dass die Row erhalten ist und Status korrekt inferiert wird. Zusätzlich Manual-Smoke-Test im §11.7.4-Runbook ("App-Installation aus Pre-v3-Backup wiederherstellen, `adb shell sqlite3`-Probe"). |
<!-- FIX: Phase-B S-2 (2026-05-13) – Downgrade-Strategie explizit dokumentieren. -->
| **DB-Downgrade v4 → v3 (Release-Update fehlgeschlagen, User installiert ältere App-Version aus Backup/APK)** | Sehr gering — Anwender installieren in der Regel nicht aktiv ältere Versionen | Heutiges `Room.databaseBuilder` hat KEIN `fallbackToDestructiveMigrationOnDowngrade` (verifiziert `DictateDatabase.kt:67-103`). Ergebnis: ältere App-Version crasht beim ersten DB-Zugriff mit `IllegalStateException` ("A migration from 4 to 3 was required but not found"). **User-Daten bleiben intakt** in der DB-Datei. Recovery-Pfad: User installiert v4-App erneut → Schema bleibt v4, alles funktioniert wieder. **Bewusste Entscheidung:** kein Downgrade-Pfad implementieren — Wiederinstallation der aktuellen Version ist der einfachere User-Pfad als Daten-Migration zurück auf v3. Pre-existing RECORDING/TRANSCRIBING-Rows aus der v4-Periode würden in der v3-App über den HistoryAdapter try/catch-Wrapper (§6.1.3) als RECORDED-Fallback erscheinen — kein UI-Crash. |

<!-- FIX: Phase-B S-2 (2026-05-13) – §11.7.0a neu: androidTest-Setup als eigene Sub-Sektion. -->
#### §11.7.0a androidTest-Setup (NEU für Block 3 — siehe Inventur Surprise-Finding #4)

Block 3 ist der erste Block in diesem Repo, der **Instrumented-Tests** einführt. Das Setup ist substanziell genug, um als eigener Sub-Schritt im Block-3-Plan zu stehen (siehe §11.2.2 Block 3 Schritt 0). Diese Sektion definiert die konkrete Setup-Reihenfolge + Verifikations-Smoke-Test.

**Heutiger Zustand (verifiziert 2026-05-13):**
- `app/src/androidTest/` existiert **nicht**.
- `app/build.gradle:18` deklariert `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` — bereits konfiguriert.
- `app/build.gradle:74-75` deklariert `androidTestImplementation libs.ext.junit` + `libs.espresso.core` — Runner + Espresso sind da, aber **keine `room-testing`-Dependency**, keine `androidx.test.runner` (separat von `ext.junit`), keine `androidx.test.rules`.
- `gradle/libs.versions.toml`: `room = "2.6.1"`, `espressoCore = "3.7.0"`, `junitVersion`-Eintrag vorhanden (für `ext-junit`). **`room-testing`, `androidx.test.runner`, `androidx.test.rules` fehlen** als Version-Catalog-Einträge.

**Setup-Reihenfolge (vor MigrationTo4.kt-Implementation):**

1. **Version-Catalog erweitern** — `gradle/libs.versions.toml`:
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
2. **build.gradle erweitern** — `app/build.gradle:74-75`, NACH `androidTestImplementation libs.espresso.core`:
   ```gradle
   androidTestImplementation libs.room.testing
   androidTestImplementation libs.androidx.test.runner
   androidTestImplementation libs.androidx.test.rules
   ```
3. **Verzeichnis anlegen** — `mkdir -p app/src/androidTest/java/net/devemperor/dictate/database/migration` (manuell oder via Android-Studio "New > Module > androidTest source set").
4. **Smoke-Test vor MigrationTo4 anlegen** — `app/src/androidTest/java/net/devemperor/dictate/database/migration/AndroidTestSetupSmokeTest.kt`:
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
   Verifikation: `./gradlew connectedDebugAndroidTest` muss grün sein (oder via Android-Studio gegen ein verbundenes Device/Emulator). Wenn dieser Smoke-Test scheitert, ist das Setup kaputt — keinen Code in MigrationTo4.kt schreiben, bevor er grün ist.
5. **Erst dann** MigrationTo4.kt + MigrationTo4Test.kt implementieren (siehe §6.1 + §11.4.2).

**CI-Integration:** Aktuell läuft `connectedDebugAndroidTest` nicht in CI (kein Emulator-Setup). Block 3 ergänzt das **nicht** — Instrumented-Tests laufen lokal vor Merge (Dev-Pflicht in PR-Checklist). Wenn später ein Emulator-CI-Setup kommt (separater Plan), reaktiviert sich der Migration-Test automatisch.

**Aufwand:** ~30 Min Setup (Catalog + build.gradle) + ~10 Min Smoke-Test + Local-Run gegen Emulator. **Plus** ~1-2 h für die 6 MigrationTo4Test-Tests (siehe §11.4.2). Gesamtaufwand Block 3 androidTest-Anteil: ~3 h.

<!-- KNOWLEDGE-GAP: KG-SST-2 – Cleanup-Policy für FAILED-Sessions mit ungenutztem Audio-File [RESOLVED 2026-05-11] -->
> **✅ Knowledge-Gap (KG-SST-2): Cleanup-Policy für FAILED-Sessions mit ungenutztem Audio-File — RESOLVED 2026-05-11**
>
> - **Was wir wussten:** `deleteInsertedOlderThan(cutoff)` löscht nur Rows mit `inserted_at IS NOT NULL` (§6.1 DAO). FAILED-Sessions haben `inserted_at IS NULL` → werden vom Cleanup-Pfad NICHT erfasst. Audio-Files, deren Delete während RECORDING→FAILED-Recovery scheitert, bleiben damit auf Disk liegen.
> - **Was wir nicht wussten:** Existiert heute schon ein zweiter Cleanup-Pfad für orphan audio files?
> - **Auflösung (Code-Recherche 2026-05-11):**
>
>     **Heutige Cleanup-Pfade (verifiziert per `grep -rn "deleteAudio\|orphan\|cleanup" app/src/main/java/`):**
>     1. `RecordingRepository.deleteBySessionId(sessionId)` (`RecordingRepository.kt:136-148`) — **User-triggered**: Detail-View "Audio löschen"-Button (`HistoryDetailActivity.onDeleteAudio` → `confirmDeleteAudio` → `RecordingRepository.deleteBySessionId`). Löscht File + setzt `audio_file_path = NULL` in DB.
>     2. `DurationHealingJob.heal(...)` (`DurationHealingJob.kt:33-72`) — App-Start-Hook: findet Sessions mit `audio_file_path != null && !File.exists()` (Datei vom OS-cache-cleanup oder manuell entfernt) und promoted sie → `status = FAILED, lastErrorType = UNKNOWN`. **Heilt nur DB-Inkonsistenzen, KEIN File-System-Cleanup.**
>     3. **Android-Cache-Auto-Cleanup** (`cacheDir`): die heutige Recording-Audio-Datei liegt in `getCacheDir()/audio.m4a` (`DictateInputMethodService.java:1407, 1612, 1693`) — Android cleant `cacheDir` opportunistic bei niedrigem Storage. **Heißt: FAILED-Sessions, deren `audio_file_path` auf `cacheDir/…` zeigt, werden indirekt aufgeräumt (DurationHealingJob promoted dann zu FAILED, File ist eh weg).**
>     4. **Keine Routine** für File-System-Orphans (Files auf Disk OHNE korrespondierende DB-Row).
>
>     **Befund:** Heute existiert KEINE Routine, die Audio-Files für `status = FAILED && audio_file_path != null && File.exists()`-Sessions räumt. Ein File-Delete im RECORDING→FAILED-Recovery-Pfad (§6.3, "Reihenfolge File-Op vs. DB-Op") kann fehlschlagen (Permission-Race) und das File leakt unbestimmt lange. Die Verlagerung von `cacheDir/audio.m4a` (heute) auf `filesDir/recordings/{sessionId}.m4a` (Block 4, AudioFileFactory) macht das schlimmer, weil `filesDir` NICHT vom OS-cleanup berührt wird.
>
>     **Auflösungs-Strategie: Defensive 2-Stufen-Cleanup im Service-Idle-Stop-Slot (analog zu `deleteInsertedOlderThan`).**
>
>     Block 3 ergänzt **eine neue DAO-Methode** + **eine neue Service-Cleanup-Routine**:
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
>     Pragmatischere Variante (da der DAO-Result nur Pfade liefert, nicht IDs): Statt `findOrphanedTerminalAudio` einen `findOrphanedTerminalSessions(cutoff)` zurückzugeben, der `List<Pair<String, String>>` (id + path) liefert. Implementer entscheidet — Funktionalität ist identisch.
>
>     **Trigger-Zeitpunkt:** Im selben Service-Idle-Stop-Slot wie `deleteInsertedOlderThan(cutoff)`. Cutoff: `now − 7d − 1h` (`Pref.SessionCleanupGracePeriodMs`, schon definiert in §6.2 R.17 Cleanup-Cutoff).
>
>     **Layer-Trennung:** File-IO im Service-Layer (Coroutine), DB im DAO — sauber getrennt analog `RecordingRepository.deleteBySessionId`. KEINE File-Ops im DAO selbst.
>
>     **Coverage-Lücke akzeptiert:** Files mit `audio_file_path` auf `cacheDir/` (Legacy) sind nicht in `findOrphanedTerminalAudio`-Scope, weil sie eh vom OS gecleant werden. Nach Block-4-Migration auf `filesDir/recordings/` wird `cleanupOrphanedAudio()` der einzige Cleanup-Pfad — und das ist OK.
>
> - **Einarbeitung:**
>     - §6.3 bekommt eine neue §6.3.1 "Orphan-FAILED-Audio-Cleanup" mit dem DAO-Query + Service-Hook-Snippet.
>     - §6.2 Persistenz-Vertrag (R.17) "Cleanup-Cutoff"-Bulletpoint erweitert um den neuen Slot.
>     - §11.6.5 / §13 Implementation-Plan (siehe Punkt 5 + 9 in §13) bekommt einen zusätzlichen Bulletpoint: "Block 3 ergänzt `findOrphanedTerminalAudio` + Service-Cleanup-Hook".

<!-- KNOWLEDGE-GAP: KG-SST-3 – v1→v4 Multi-Step-Migration nicht im automatisierten Test [RESOLVED 2026-05-11] -->
> **✅ Knowledge-Gap (KG-SST-3): v1→v4 Multi-Step-Migration nicht im automatisierten Test — RESOLVED 2026-05-11**
>
> - **Was wir wussten:** Room führt `addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)` sequenziell aus, wenn die User-DB auf v1 steht. `MigrationTo4Test` deckt aber nur v3→v4 ab.
> - **Was wir nicht wussten:** Gibt es User-Installationen mit v1-DB-File im Feld? Existieren bestehende Multi-Version-Migrations-Tests (z.B. `MigrationTo2Test`, `MigrationTo3Test`)?
> - **Auflösung (Code-Recherche 2026-05-11):**
>     - **Bestehende Migration-Tests:** Keine. `find /home/lukas/WebStorm/Dictate/app/src -iname "*Migration*Test*"` ergibt nur `InputLanguagesLegacyMigrationTest.kt` (Preferences-Domain, nicht Room). Es gibt heute KEINEN Room-Migration-Test im Repo. `MigrationTo4Test` (§11.4.2) ist der ERSTE Room-Migration-Test, der eingeführt wird.
>     - **`androidTest`-Verzeichnis:** Existiert heute NICHT. Block 3 muss `app/src/androidTest/java/...` neu anlegen + die `androidTestImplementation`-Dependencies (`androidx.room:room-testing`, `androidx.test:runner`, `androidx.test:rules`) in `app/build.gradle:74-75` ergänzen (heute nur `androidx.test:junit` + `espresso-core` deklariert, aber nicht `room-testing`).
>     - **v1-Schema verifiziert** (siehe `Migrations.kt:9-23`): `sessions` ohne `status`-Spalte, mit `id, type, created_at, target_app_package, language, audio_file_path, audio_duration_seconds, parent_session_id, final_output_text, input_text`. MIGRATION_2_3 inferiert `status` (siehe `MigrationTo3.kt:92-97`: RECORDING-Type ohne Transcription + audio_file_path → RECORDED, sonst COMPLETED).
>     - **Telemetrie-Frage entfällt:** Da Multi-Step-Migrations-Tests billig sind (~50 Zeilen, ~15 Min Implementation) und ein FAILED-Test eine Krisen-Klasse von Bugs verhindert (Restore-aus-Backup ist genau das Szenario, in dem ein User keinen Workaround hat), übernehmen wir die Default-Strategie ohne weitere User-Entscheidung.
>     - **Konkreter Test-Body** in §11.4.2 ergänzt: `migrate1To4_chain_preservesData()` — inserted eine RECORDING-Type-Session in eine v1-DB (id, audio_file_path gesetzt, audio_duration_seconds = 5), läuft `runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)`, und assertet (a) Row erhalten, (b) Status korrekt zu `RECORDED` inferiert, (c) Default-Origin `KEYBOARD`, (d) `inserted_at = NULL` (Backfill nur für COMPLETED).
>     - **Test-Runbook §11.7.4** bekommt zusätzlich einen Manual-Smoke-Test "App-Installation aus Pre-v3-Backup wiederherstellen, App starten, sessions-Tabelle via `adb shell sqlite3` prüfen" — als Belt-and-Suspenders neben dem automatisierten Test.
>
> - **Einarbeitung:**
>     - §11.4.2 — neuer `@Test fun migrate1To4_chain_preservesData()` (vollständiger Body).
>     - §11.7.0 Risiko-Tabelle — Zeile "Multi-Step-Migration v1→v4" aktualisiert: jetzt **automatisierter Test vorhanden** (`migrate1To4_chain_preservesData`), Manual-Smoke-Test im §11.7.4-Runbook als Backup.
>     - Block-3-Implementer-Note: `androidTest`-Verzeichnis + `room-testing`-Dependency müssen Teil von Block 3 sein (auch für die anderen `MigrationTo4Test`-Tests notwendig).

<!-- KNOWLEDGE-GAP: KG-SST-4 – HistoryAdapter switch ohne default (Java-Compile-Verhalten) [RESOLVED 2026-05-11] -->
> **✅ Knowledge-Gap (KG-SST-4): `HistoryAdapter.java`-`switch` ohne `default`-Branch — RESOLVED 2026-05-11**
>
> - **Was wir wussten:** `HistoryAdapter.java:136-159` ist ein `switch (status)` ohne `default`. Java ist nicht exhaustive — fehlende `case`s nach M4 (`RECORDING`/`TRANSCRIBING`) verursachen keinen Compile-Fehler. Sie würden zu einer leeren Anzeige (kein Badge) führen.
> - **Was wir nicht wussten:** Hat das Projekt eine Lint-Regel `EnumSwitch` aktiv?
> - **Auflösung (Code-Recherche 2026-05-11):**
>     - **Verifiziert:** `HistoryAdapter.java:136-159` hat KEIN `default:` — bestätigt per Read.
>     - **Lint-Setup-Befund:**
>         - **Keine `lint.xml`** im Projekt (geprüft: `find /home/lukas/WebStorm/Dictate -maxdepth 3 -name 'lint.xml'` → leer).
>         - **Keine `.editorconfig`** mit Lint-Rules (geprüft: gleicher Pfad).
>         - **Kein `lintOptions`-Block** in `app/build.gradle` (verifiziert: gelesen, nur `compileOptions`, `kotlinOptions`, `buildFeatures`, `packagingOptions`, `testOptions` vorhanden — keine `lint { … }` und kein `android.lint`).
>         - **Kein `lint { … }` Block** in `build.gradle` Root.
>         - **Keine ErrorProne/SpotBugs/Detekt-Setup** (keine Plugin-Deklarationen in `app/build.gradle` plugins-Block; nur `android.application`, `kotlin.android`, `ksp`).
>     - **Folge:** Android-Lint läuft mit **Defaults** — die Regel `EnumSwitch` (Android-Lint-Built-in, Issue-ID `EnumSwitchHandlesMissingCase`) ist **standardmäßig auf Severity `Warning`** und wird beim `./gradlew lint`-Run **nur als Report** erzeugt, ohne Build-Failure. Ohne `lintOptions { abortOnError true; warningsAsErrors true }`-Konfiguration sieht das CI keinen Failure beim Vergessen eines `case`s.
>     - **Auflösungs-Strategie: Defensive `default:`-Klausel + Lint-Severity-Schärfung (kombiniert, beide notwendig).**
>
>     **(a) Defensiver `default:`-Branch in `HistoryAdapter.java:136-159`** — als primärer Schutz:
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
>     **Warum `throw` statt `Log.e`?** Es ist ein Programmierfehler, kein User-Error — der Crash im Dev/Staging-Build outet die Lücke sofort. In Prod-Release-Builds catched die Activity das via `try/catch` (siehe `HistoryAdapter` ist im RecyclerView-Bind-Pfad, ein Throw hier crasht den RecyclerView-Loop) — pragmatischer wäre ein `Log.wtf(TAG, "...", e)` + `holder.statusIcon.setVisibility(GONE)`. Implementer-Entscheidung beim Patch-Write: das `throw` ist die strikteste Variante, ein `Log.wtf + GONE` der defensive Mittelweg. **Default: `Log.wtf + GONE`**, weil ein RecyclerView-Crash wegen einer Anzeige-Inkonsistenz für den User unangemessen ist.
>
>     **(b) Lint-Severity-Schärfung** — als Secondary-Defense, ergänzend zu (a):
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
>     **Cost-Check:** Aktivierung der Regel kann bestehende Java-`switch`-Stellen, die heute kein `default` haben, neu zum Build-Fail bringen. Daher: vor Aktivierung einmal `./gradlew lint` laufen lassen, alle existierenden EnumSwitch-Findings reviewen und entweder fixen oder via `// noinspection EnumSwitchHandlesMissingCase` annotieren. Findings sind typischerweise wenig (≤5 im gesamten App-Code, geschätzt nach Grep-Pattern `switch \(.*\.values?\(\)`).
>
> - **Einarbeitung:**
>     - §6.1.3 Patch — `default:`-Branch im `HistoryAdapter.java`-Snippet ergänzt (mit Log.wtf-Empfehlung als Default + Throw-Alternative dokumentiert).
>     - §13 Block 3 Implementation-Plan — neuer Bulletpoint "Lint-Setup: `lint { error += 'EnumSwitch'; abortOnError true }` in `app/build.gradle`, vorher Baseline-Cleanup."

<!-- KNOWLEDGE-GAP: KG-SST-5 – Atomarität DB-Persist vs ActiveJobRegistry-Update [RESOLVED 2026-05-11] -->
> **✅ Knowledge-Gap (KG-SST-5): Atomarität DB-Persist ↔ `ActiveJobRegistry`-Update — RESOLVED 2026-05-11**
>
> - **Was wir wussten:** Im Checkpoint-Hook (§6.2) ruft `PipelineStateManager` DB-DAO + `ActiveJobRegistry.register/update` auf. Wenn der DAO-Call fehlschlägt, divergieren Registry und DB.
> - **Was wir nicht wussten:** Soll der Hook bei DAO-Failure den Registry-Update zurückrollen (consistency-first), oder akzeptieren wir die Divergenz (availability-first)? Welche Reihenfolge (Cache→DB oder DB→Cache)?
> - **Auflösung — Availability-First, DB-first-Reihenfolge:**
>
>     **Heutige Reihenfolge (M3-Code, verifiziert 2026-05-11):** `JobExecutor.start()` (`JobExecutor.kt:96`) ruft `ActiveJobRegistry.register(sessionId, initial)` **zuerst**. `JobExecutor.finally` (Z. 164) ruft `ActiveJobRegistry.unregister(sessionId)` zuletzt. Dazwischen läuft die Pipeline, die `SessionManager.finalizeCompleted/Cancelled/Failed` aufruft (`SessionManager.kt:97-111` — reine DAO-Calls, KEIN `runInTransaction`, KEIN Registry-Touch). Die Registry wird also **vor** dem DB-Status-Update gesetzt und **nach** dem DB-Status-Update entfernt. Das ist heute korrekt für `JobExecutor.start/finally` (Registry = "läuft gerade, hands off"), aber im M4-Checkpoint-Hook für RECORDING/TRANSCRIBING ist eine andere Reihenfolge sinnvoll.
>
>     **M4-Reihenfolge: DB first, dann Cache.** Im `DictateOrchestrator.Effect.PersistStatus`-Pfad (§6.2 Checkpoint-Hook):
>     1. `SessionDao.updateStatus(sessionId, TRANSCRIBING)` — DB-Persist (atomar auf SQLite-Statement-Ebene)
>     2. `ActiveJobRegistry.update(sessionId, newState)` — Cache-Update
>
>     **Begründung:** Die DB ist die Crash-Safe-Quelle für OOM-Recovery. Wenn der Process zwischen Schritt 1 und 2 stirbt, ist die DB konsistent (Status = TRANSCRIBING), Registry ist beim nächsten Start eh leer (process-local). Wenn Schritt 1 fehlschlägt (DAO-Exception): Pipeline-Reducer fängt es als `Action.PipelineAction.PersistenceError` (§6.2, R.17), Registry wird NICHT geupdated, Drift ist null. Falls Schritt 2 fehlschlägt (kann eigentlich nicht, weil `ActiveJobRegistry.update` synchron auf einer In-Memory-Map ist): nur Step-Counter ist veraltet, behebt sich beim nächsten Reducer-Tick.
>
>     **Producer-Sites `JobExecutor.kt:96/:164` bleiben unverändert** — sie sind Lock-Producer (`register` = lock-claim, `unregister` = lock-release), nicht Status-Producer. DB-Status-Writes für RECORDING/TRANSCRIBING erfolgen separat im Reducer-Hook.
>
>     **Drift-Toleranz dokumentiert:** Process-local Cache wird bei jedem App-Start verworfen (`ActiveJobRegistry` ist Kotlin `object` ohne persistente State — bestätigt: `ActiveJobRegistry.kt:20-65`). Kein langfristiger Drift möglich. Persistenz-Vertrag wird in `SessionManager.kt`-KDoc (`finalizeCompleted` etc.) angezogen: "DB first, dann Cache. Drift-Toleranz: process-local Cache wird bei jedem App-Start verworfen, kein langfristiger Drift möglich."
>
> - **Einarbeitung:**
>     - §6.1.1 Tabelle "Was ändert sich" — letzte Zeile umgedreht: "Reihenfolge: DB first, dann Cache" (statt vorher "erst Registry, dann DAO-Call").
>     - §6.1.1 Konsumenten-Tabelle: Hinweis-Block "Persistenz-Vertrag (Cache ↔ DB)" mit der DB-first-Regel.
>     - §6.2 Persistenz-Vertrag (R.17) bekommt einen 5. Bulletpoint: "**Reihenfolge DB → Cache:** Im Reducer-Hook für RECORDING/TRANSCRIBING gilt DB-Update vor `ActiveJobRegistry.update`. Bei DAO-Failure wird der Registry-Call übersprungen (kein Drift); bei Process-Crash dazwischen ist DB konsistent, Registry wird beim App-Start eh leer initialisiert."

<!-- FIX: Phase-B S-1 (2026-05-13) – Test-Tabellen auf F-11-Module-Pattern umgestellt. -->
#### §11.7.1 Bestehende Tests, die brechen

| Test | Block | Bruchgrund | Mitigation |
|---|---|---|---|
| `RecordingStateControllerTest.kt` | 1b | Klasse wird gelöscht | Auf `RecordingModuleTest` umschreiben — Reducer-pure-Function-Asserts statt Controller-Field-Asserts. State-Assertions ändern sich von `controller.state == X` zu `RecordingModule.reduce(stateBefore, action, ctx).nextState == X`. |
| `MultiCallbackForwardingTest.kt` | 1b | Callback-Pattern verschwindet | Test wird auf `orchestrator.state.collect`-Subscriber umgebaut |
| `JobExecutorTest.kt` | (keiner) | unverändert | — |
| `ActiveJobRegistryTest.kt` | (keiner) | unverändert | — |
| `LanguageControllerTest.kt` | 1b | LanguageController wandert in LanguageModule | Auf `LanguageModuleTest` umschreiben |

#### §11.7.2 Neue Tests, die nötig sind

Siehe §11.2.3 — Tabelle pro Block.

#### §11.7.3 Test-Fakes

| Fake | Datei | Block | Zweck |
|---|---|---|---|
| `FakeLocalBinder` (LocalBinder-Stub) | `app/src/test/java/.../testutil/FakeLocalBinder.kt` (NEU) | 2 | IME-Tests ohne Robolectric-Service |
| `FakeJobExecutor` | (existiert via `PipelineRunner`-Interface in `JobExecutor.kt:332`) | (keiner) | bereits vorhanden |
| `FakeAudioFocusGate` | `app/src/test/java/.../core/FakeAudioFocusGate.kt` (existiert) | 1b | bereits vorhanden |
| `FakeModuleServices` | `app/src/test/java/.../testutil/FakeModuleServices.kt` (NEU) | 1b | DictateModule-Tests ohne reale Hardware-Adapter |
| `FakePipelinePrefMirror` | `app/src/test/java/.../testutil/FakePipelinePrefMirror.kt` (NEU) | 1b | DictateOrchestrator-Init-Order-Test (records attach-Reihenfolge) |
| `FakePipelineSessionRepo` | `app/src/test/java/.../testutil/FakePipelineSessionRepo.kt` (NEU) | 1b | PipelineRecovery-Tests ohne Room |
| `FakeSessionDao` | `app/src/test/java/.../testutil/FakeSessionDao.kt` (NEU) | 3 | DAO-Tests ohne Room |

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
| 7 | `KeyboardStateManager.kt:162` | `overlayCharactersLl` | **BLEIBT** — Touch-Handler-/View-Handler-internal (defensiver Reset des transient overlays). Kanonisch in Spec 2 §13.1 audited. <!-- FIX: Issue 3.0.8 – Cross-Spec-Konflikt mit Spec 2 §13.1 aufgelöst (Spec 2 ist kanonisch für IME-View-Visibility) --> |
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
| 19 | `EnterOverlayHandler.kt:56, :62` | `overlayCharactersLl` | **BLEIBT** — Touch-Handler-internal (kanonisch in Spec 2 §13.1 + §11.7 audited; Touch-Handler-interne State-Maschine, defensiver Reset). <!-- FIX: Issue 3.0.8 – Cross-Spec-Konflikt mit Spec 2 §13.1 aufgelöst --> |

**Verifikation:** 12 Mutations-Sites sind state-driven und wandern in einen LayoutCatalog-Slot mit Predicate (Zeilen 1-6, 8-14, 18 — partiell — in der Tabelle). Zwei Sites (Zeile 7 KSM:162 + Zeile 19 EnterOverlayHandler:56,62) sind **view-handler-internal** (Touch-Handler-interne State-Maschine, defensiver Reset) und bleiben — kanonisch in **Spec 2 §13.1** audited. Die 21 Sites, die view-lokal sind (Zeilen 15-17 + die 8 `KeyboardUiController`-Sites in Zeile 16), bleiben mit klarer Begründung erhalten. **Keine state-driven Visibility-Mutation ist im Refactor-Plan unaddressed.**

> **Cross-Spec-Note (FIX Issue 3.0.8):** Visibility-Mutationen mit IME-View-Scope (`overlayCharactersLl`) sind kanonisch in **Spec 2 §13.1** auditiert; die obenstehende Tabelle hier ist der **Cross-Spec-Index**. Falls beide Tabellen voneinander abweichen, ist Spec 2 §13.1 die maßgebliche Quelle (IME-View-Visibility lebt im Keyboard-Layout-Subsystem).

### §13.2 State-Mutation-Audit

#### §13.2.1 Direkte State-Field-Mutationen heute

| # | file:line | Code | Klasse | Wandert nach? |
|---|---|---|---|---|
<!-- FIX: Phase-B S-1 (2026-05-13) – Targets auf F-11 (RecordingModule + RecordingState.audioFile, R.2) umgestellt. -->
| 1 | `RecordingStateController.kt:106-107` | `var state: RecordingState = Idle; private set` | `RecordingStateController` | `DictateUiStateStore._state` → `DictateUiState.recording` (mutiert von `RecordingModule.reduce`) — JA |
| 2 | `RecordingStateController.kt:110` | `private var audioFile: File?` | `RecordingStateController` | wandert in `RecordingState.Preparing/Active/Paused.audioFile` (Sub-Feld der sealed-class-Varianten, R.2 — Pure-Reducer-Garantie) — JA |
| 3 | `RecordingStateController.kt:111` | `private var audioFocusEnabled: Boolean` | `RecordingStateController` | `DictateUiState.audio.audioFocusEnabledPref` — JA |
| 4 | `RecordingStateController.kt:355` | `state = newState` (in `setState`) | `RecordingStateController` | `_state.update { it.copy(recording = newState) }` — JA |
| 5 | `KeyboardUiController.kt:63-65` | `override var state: PipelineUiState = Idle; private set` | `KeyboardUiController` | `DictateUiState.pipeline` — JA |
| 6 | `KeyboardUiController.kt:67` | `private var config: AutoEnterConfig?` | `KeyboardUiController` | wird Member von `DictateUiState.pipeline` (`Running.autoEnterActive`) — JA |
| 7 | `KeyboardUiController.kt:118-119` | `pipelineTotalTimer`, `latestPipelineElapsedMs` | `KeyboardUiController` | bleibt View-lokal (Timer ist View-Display-Detail, kein State) — Akzeptiert |
| 8 | `KeyboardUiController.kt:133-136` | `stepRows`, `totalSteps`, `currentStep`, `activeTimer` | `KeyboardUiController` | bleibt View-lokal — Akzeptiert |
| 9 | `KeyboardUiController.kt:149` | `state = newState` (in `updateDictateUiState`) | `KeyboardUiController` | `_state.update { it.copy(pipeline = newState) }` — JA |
| 10 | `KeyboardStateManager.kt:100` | `var contentArea: ContentArea = MAIN_BUTTONS; private set` | `KeyboardStateManager` | `DictateUiState.contentArea` — JA |
| 11 | `KeyboardStateManager.kt:102` | `var isSmallMode: Boolean = false; private set` | `KeyboardStateManager` | `DictateUiState.layout.smallMode` — JA |
| 12 | `KeyboardStateManager.kt:136-137` | `contentArea = area` (in `setContentArea`) | `KeyboardStateManager` | `_state.update { it.copy(contentArea = area) }` — JA |
| 13 | `KeyboardStateManager.kt:141-145` | `isSmallMode = enabled; contentArea = MAIN_BUTTONS` (in `setSmallMode`) | `KeyboardStateManager` | atomare `_state.update { it.copy(layout = it.layout.copy(smallMode = enabled), contentArea = MAIN_BUTTONS) }` — JA, eliminiert das Coupled-Mutation-Problem (heute zwei sequenzielle Schreiben, künftig atomar) |
| 14 | `KeyboardStateManager.kt:113-117` | `private var layoutModeController: ...` | `KeyboardStateManager` | entfällt komplett (Klasse wird gelöscht in Spec 2) — JA |
| 15 | `KeyboardUiController.kt:138` | `private var savedRecordButtonTextColors` | `KeyboardUiController` | bleibt View-lokal — Akzeptiert |
<!-- FIX: Phase-B S-1 (2026-05-13) – Zeile 16 in 16a-16f aufgespalten (pro Service-Feld einen expliziten Migrations-Ziel). Frühere Sammelzeile war zu vage und ließ den restoreAutoEnter/restoreReprocessStaging-Felder offen, ob sie ersatzlos gestrichen oder umgezogen werden. -->
| 16a | `DictateInputMethodService.java:112` | `private boolean livePrompt` | Service | `DictateUiState.livePrompt.enabled` (Sub-State, §3) — JA |
| 16b | `DictateInputMethodService.java:113` | `private volatile boolean pendingLivePromptChain` | Service | `DictateUiState.livePrompt.pendingChain` (Sub-State, §3) — JA |
| 16c | `DictateInputMethodService.java:114` | `private boolean vibrationEnabled` | Service | `DictateUiState.audio.vibrationEnabled` (Pref-Mirror, §3 + §4.5) — JA |
| 16d | `DictateInputMethodService.java:121` | `private boolean autoSwitchKeyboard` | Service | Bleibt **lokales Service-Field** — repräsentiert einen Pre-IME-Switch-Toggle, der nur im IME-Service-Lifecycle relevant ist. KEIN UI-State, KEIN Cross-Konsument außer dem IME selbst. Akzeptiert als view-lokal. |
| 16e | `DictateInputMethodService.java:131` | `private Boolean restoreAutoEnter` | Service | **Ersatzlos gestrichen** — view-recreate-bridge entfällt. Der State `PipelineUiState.Running.autoEnterActive` lebt nach Block 1b im PipelineService-StateFlow, der View-Recreate strukturell überlebt (Spec 1 D1). Subscriber im neuen `viewScope` (Spec 1 §8.x) bekommt den Wert beim Re-Attach automatisch via erste `state.collect`-Emission. |
| 16f | `DictateInputMethodService.java:142` | `private PipelineUiState.ReprocessStaging restoreReprocessStaging` | Service | **Ersatzlos gestrichen** — view-recreate-bridge entfällt aus demselben Grund wie 16e. Der `PipelineUiState.ReprocessStaging(sessionId, transcript)`-State lebt im StateFlow; nach `onCreateInputView` wird er via `state.collect` neu gelesen. **Block-1-Akzeptanz:** `cleanupOldControllers()` darf das Feld nicht mehr capturen (siehe §8.x View-Recreate-Vertrag). |
| 17 | `ActiveJobRegistry.kt:28-31` | `MutableStateFlow<Map<String, JobState>>` | `ActiveJobRegistry` | bleibt unverändert (Job-Tracking ist orthogonal zu UI-State) — Akzeptiert |
| 18 | `JobExecutor.kt:36-54` | `activeToken`, `activeThread`, `orchestrator` | `JobExecutor` (object) | bleibt unverändert — Akzeptiert |

<!-- FIX: Phase-B S-1 (2026-05-13) – Verifikations-Block auf F-11 umgestellt (Modul-Reducer + DictateUiStateStore statt monolithischer PipelineStateManager); Sites-Liste erweitert für 16a–16f. -->
**Verifikation:** Alle 16 UI-state-relevanten Mutations-Sites (Zeilen 1, 3, 4, 5, 6, 9, 10, 11, 12, 13, 16a, 16b, 16c) wandern in `DictateUiStateStore` via Modul-`reduce`-Aufrufe (F-11). **Die heutigen 3 unabhängigen State-Halter** (RecordingStateController + KeyboardUiController + KeyboardStateManager) **werden eliminiert** zugunsten einer **single source of truth** (`DictateUiStateStore.state: StateFlow<DictateUiState>`).

Die 7 view-lokalen Felder (Zeilen 2, 7, 8, 14, 15, 17, 18) plus 3 Zeilen 16d/16e/16f (Service-Field-Migration in §13.2.1 oben) bleiben begründet view-lokal oder werden ersatzlos gestrichen: View-Display-Detail, Job-Tracking, Klasse wird komplett gelöscht, oder view-recreate-bridge entfällt.

#### §13.2.2 SP-Reads mit State-Charakter

Einige UI-State-Achsen werden heute on-demand aus `SharedPreferences` gelesen statt im State gehalten:

| Pref-Key | Heute gelesen in | Wandert in `DictateUiState`? |
|---|---|---|
| `Pref.SmallMode` | `DictateInputMethodService.java:1025, :1402, :2632, :2634` | NEIN — bleibt Pref, aber `DictateUiState.layout.smallMode` spiegelt den Wert. SP-Listener triggert State-Update. |
| `Pref.SingleRowMode` | `DictateInputMethodService.java:2652-2654`; `KeyboardLayoutModeController.kt:100, :151` | NEIN — bleibt Pref, `DictateUiState.layout.singleRowMode` spiegelt. |
| `Pref.AudioFocus` | `DictateInputMethodService.java:580, :664, :2671-2674`; `RecordingStateController.kt:194` | NEIN — bleibt Pref, `DictateUiState.audio.audioFocusEnabledPref` spiegelt. |
| `Pref.ResendButton` | `DictateInputMethodService.java:1344, :1694` | NEIN — bleibt Pref, `DictateUiState.resend.resendEnabled` spiegelt. |
| `Pref.LastFileName` | `DictateInputMethodService.java:1343, :1408, :1613, :1693` | NEIN — Cache-File-Tracking bleibt Pref-driven. `DictateUiState.resend.lastAudioExists` spiegelt File-Existence. |
| `Pref.Animations` | `DictateInputMethodService.java:611, :1399`; `KeyboardLayoutModeController.kt:123, :453` | NEIN — bleibt Pref. `DictateUiState.layout.animationsEnabled` ist redundant für UI-Resolver, aber konsistent gespiegelt. |
| `Pref.AutoEnter` | `DictateInputMethodService.java:1010, :1679, :1764-1766, :1891, :2532` | TEILWEISE — initial-Wert kommt aus Pref, runtime-toggle ist `PipelineUiState.Running.autoEnterActive` |
| `Pref.OverlayPositionPortraitX/Y` | NEU (OPEN-3) | JA — `DictateUiState.overlay.positionPortraitX/Y` spiegelt. Schreib-Trigger: `updateOverlayPosition(portrait=true, ...)` aus `OverlayBackend` (Spec 3 §11.5). |
| `Pref.OverlayPositionLandscapeX/Y` | NEU (OPEN-3) | JA — `DictateUiState.overlay.positionLandscapeX/Y` spiegelt. Schreib-Trigger: `updateOverlayPosition(portrait=false, ...)` aus `OverlayBackend` (Spec 3 §11.5). |

<!-- FIX: Phase-B S-1 (2026-05-13) – Spiegelung-Pattern-Aussage auf PipelinePrefMirror (§4.5) umgestellt. Pre-F-11-Text behauptete der Manager halte die Pref-Reads — falsch nach F-11: PrefMirror ist eigene Klasse, vom Orchestrator-`init` attached. -->
**Spiegelung-Pattern:** der `PipelinePrefMirror` (§4.5) liest beim `attach(store)` alle relevanten Prefs und ruft `store.update { initialMirror(it) }`. Ein `SharedPreferences.OnSharedPreferenceChangeListener` triggert weitere `store.update`-Calls (`sync(key)`), sodass Settings-Activity-Writes reaktiv im IME ankommen. **Reihenfolge-Invariant:** `prefMirror.attach(store)` läuft im `DictateOrchestrator.init` synchron VOR `scope.launch { recovery.recover(store) }` — siehe §4.3.

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
#### §13.2.3 Neue State-Mutation (OPEN-3)

| # | Action / Modul | Schreib-Effekt | Trigger-Ort |
|---|---|---|---|
| 1 | `Action.OverlayAction.UpdatePosition(portrait, x, y)` → `OverlayModule.reduce` | `state.copy(overlay = state.overlay.copy(position{Portrait\|Landscape}{X\|Y} = ...))` + `Effect.PersistOverlayPosition`-EffectHandler schreibt `Pref.OverlayPosition*` (atomar via `apply()`) | `OverlayBackend.OnTouchListener#onUp` (Drag-End, Spec 3 §11.5). Nur dispatcht wenn Move-Distance > Threshold (8dp). |

**Verifikation:** Die einzige neue State-Mutation in OPEN-3 läuft durch
`DictateOrchestrator.dispatch(Action.OverlayAction.UpdatePosition(...))` — kein
direkter Pref-Write aus `OverlayBackend`, kein direkter View-Mutation auf das
Overlay-Window vom Settings-Screen. Damit bleibt das State-SSOT-Invariant
unverletzt: ALLE Mutations gehen durch den Orchestrator + Modul-Reducer (F-8).

### §13.3 SOLID-Verifikation pro neue Klasse

> **Iteration 2026-05-09/10 (F-8, F-10, F-11):** Diese Sektion wurde
> grundlegend überarbeitet. Frühere Audits prüften den `PipelineStateManager`
> als Composition Root mit typed Action-Methoden. Mit dem Modular-Orchestrator-
> Pattern (F-11) ist die zentrale Klasse jetzt der `DictateOrchestrator`, der
<!-- FIX: Phase-C C-1 (2026-05-14) – Modul-Zähler aktualisiert auf 13 aktiv (12 ursprünglich + KeyboardInputModule §15.6). -->
> nur das `DictateModule`-Interface kennt; Action-Logik wandert in 13 aktive Module
> (plus 1 Phase-2-Stub, siehe §15). Audit ist entsprechend pro Schicht strukturiert.
>
> **Scope-Aufteilung (FIX Issue 3.0.6):** §13.3 audited die **Schicht-Klassen** —
> Service (Lifecycle), Orchestrator (Routing), Helpers (Notification, ActionRouter,
> Pref-Mirror, Recovery, Store) sowie das `DictateModule`-Interface selbst.
<!-- FIX: Phase-C C-1 (2026-05-14) – Modul-Zähler 13 → 13 aktive (siehe §15.1 Tabelle: 13 aktive + 1 Phase-2). -->
> **§15 ist die kanonische Audit-Stelle für die 13 aktiven Modul-Implementierungen (+ 1 Phase-2-Stub)** —
> dort lebt die fachliche SRP/OCP-Begründung pro Modul. §13.3.13 hier zeigt nur
> das Pattern am `RecordingModule`-Beispiel.

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

#### §13.3.4 ViewModeFsm — verschoben

> **Audit-Sektion verschoben:** siehe §15.1 (ViewModeModule) — Audit lebt an der kanonischen Modul-Stelle. <!-- FIX: Issue 3.0.6 – §13.3 audited Schicht-Klassen (Service / Orchestrator / Helper); §15 ist kanonische Audit-Stelle für Modul-Klassen -->
>
> Hintergrund (für Reader-Kontext): die ehemalige `ViewModeFsm`-Klasse aus dem F-1-Pass existiert nach F-11 nicht mehr eigenständig. Ihre Logik ist Teil des `ViewModeModule` (§15.1) — der Triangle-FSM-Reducer ist jetzt eine `reduce()`-Methode auf dem Modul, kein separates Pure-Function-object mehr. Die SOLID-Begründung gilt unverändert: Pure Function, keine Side-Effects, exhaustive `when`-Block, Truth-Table testbar.

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

#### §13.3.8 PipelineActionRouter (F-3, post-F-8 — Single Dispatch)

<!-- FIX: Issue 3.0.6 – Pre-F-8-Vokabular ("PipelineStateManager-Methode" / typed Action-Routing-Ziel) auf F-8/F-11 umgestellt -->

- **SRP** — pure Mapping `Intent.action → Action-Sealed-Class-Variante`. Dispatch erfolgt über den injizierten `DictateOrchestrator` (`orchestrator.dispatch(action)`). Keine UI-Logik, keine Notification-Build, keine typed Forwarder-Methoden mehr (entfallen mit F-8).
- **OCP** — Neue Action = neuer Branch im `dispatch(intent)`-when + neue Konstante in `companion`. Orchestrator + andere Klassen unberührt.
- **DIP** — Hängt am `DictateOrchestrator` (Single-Dispatch-API: `state` + `dispatch(action: Action)` + Lifecycle-Hooks). Tests injizieren `Mock<DictateOrchestrator>` und verifizieren, dass `dispatch` mit der korrekten `Action`-Variante (z.B. `Action.PipelineAction.CancelPipeline`) gerufen wird.

#### §13.3.9 LocalBinder — verschoben

> **Audit-Sektion verschoben:** siehe §13.3.2b für die kanonische F-8-Audit-Sektion (Single Dispatch). <!-- FIX: Issue 3.0.6 – §13.3.9-Stub explizit als „Verschoben"-Marker gekennzeichnet, statt als forward-Reference -->

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

<!-- FIX: Phase-C C-1 (2026-05-14) – Method-Zähler aktualisiert: nach Phase-B S-3 (reduceFailure)
     + Issue 2.1.2 (prefBindings) + Issue 2.1.12 (terminate) hat das Interface jetzt 7 Pflicht-
     Methoden (id, actionClass, read, write, initialState, reduce, runEffect) + 4 optionale Hooks
     mit Default-Body (reduceFailure, onCrossModuleStateChange, prefBindings, terminate). Pre-S-3
     waren es "5 Pflicht + 1 optional" — gilt nach den Phase-B-Erweiterungen nicht mehr. -->
- **SRP** — Definiert den Plugin-Kontrakt. Selbst keine Logik; nur ein Interface mit 7 Pflicht-Methoden + 4 optionalen Hooks (Default-Implementierungen).
- **OCP** — `sealed interface` mit `object`-Implementierungen pro Modul. Compile-Zeit-Hierarchie, exhaustive `when` möglich.
- **LSP** — Alle Module implementieren denselben Kontrakt mit eigenen Type-Parametern; polymorph austauschbar.
- **ISP** — Minimal: 7 Pflicht-Methoden (id, actionClass, read, write, initialState, reduce, runEffect) + 4 optionale Default-Hooks (reduceFailure, onCrossModuleStateChange, prefBindings, terminate). Keine Methode, die ein Modul nicht braucht.
<!-- FIX: Phase-C C-1 (2026-05-14) – 13 → 13 aktiv + 1 Phase-2 (KeyboardInputModule wurde in Phase-B S-3 als 13. aktives Modul ergänzt). -->
- **DIP** — Reines Interface. Konkretisierungen sind die 13 aktiven Module in §15 (plus 1 Phase-2-Stub `InterruptionModule`).

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
<!-- FIX: Phase-B S-1 (2026-05-13) – DRY-Tabelle auf F-11 umgestellt: AudioModule/AudioPrefMirror statt PipelineStateManager. -->
| **AudioFocus-on-Toggle-Reaktion** | `DictateInputMethodService.java:664-672` (SP-Listener) + `:2664-2687` (User-Toggle) | 1 Pfad: `Action.AudioAction.ToggleAudioFocusPref` → `AudioModule.reduce` (User-Click) **und** `PipelinePrefMirror.sync(Pref.AudioFocus.key)` → `store.update` (SP-Listener) — beide enden in derselben State-Achse `state.audio.audioFocusEnabledPref` <!-- FIX: Phase-B S-3 (2026-05-13) – Naming-Drift behoben (Spec 2 §3.3 SoT). --> |
| **`Pref.SmallMode`-Apply** | `DictateInputMethodService.java:1025, :1402, :2632-2634` | 1 Branch im `PipelinePrefMirror.sync(Pref.SmallMode.key)` (§4.5) |
| **`Pref.AudioFocus`-Apply** | `:580, :664, :2685` (3 Sites mit identischem `mainButtonsController.refreshAudioFocusIcon` Boilerplate) | 1 Subscriber: `state.collect { state -> mainButtonsController.refreshAudioFocusIcon(state.audio.audioFocusEnabledPref) }` |
| **`getLastAudioFileExists()` File-Check** | `DictateInputMethodService.java:611-613, :1343-1344, :1693-1694` | 1 Effect `ResendModule.Effect.RefreshLastAudioExists`, gerufen vom RecordingModule beim Übergang Active → Idle (Cross-Module-Cascade, siehe §15 + Coupling-Matrix §15.1.x). Result als `state.resend.lastAudioExists`. |

<!-- FIX: Issue 3.1.13 / R.21 – Cross-Spec-DRY-Tabelle (Symbol/Definition/Konsumenten) -->
#### §13.4.1b Cross-Spec-DRY-Tabelle (R.21)

| Symbol | Definition | Konsumenten |
|--------|------------|-------------|
| `predResendVisible` | Spec 2 §8.5 (zentraler Predicate-Helper) | Spec 2 `forKeyboard` resend_btn-Resolver, Spec 3 OVERLAY_RESEND-Slot (sofern vorhanden), Spec 1 §13.1 Audit |
| `state.isIdle` | `state/Predicates.kt` (R.21) — `recording is Idle && pipeline is Idle` | Spec 2 §8.5 visibility-Resolver, Spec 3 §3.1 OVERLAY_RECORD-Resolver |
| `state.predRecordingControlsVisible` | `state/Predicates.kt` (R.21) — `recording.isActiveOrPaused` | Spec 2 §8.5, Spec 1 §13.1 |
| `OverlayPositionMapper` | Spec 3 §4.7 | Spec 3 §4.3 `applyPosition`, Spec 1 §6.4 PrefMirror |
| `View.effectiveSize()` | Spec 3 §4.7 (Helper, R.19) | Spec 3 §4.3 `applyPosition`, Spec 3 `OverlayPositionMapper.normalizedToPixels` / `pixelsToNormalized` |

**Predicates.kt** (`app/src/main/java/net/devemperor/dictate/state/Predicates.kt`):

```kotlin
val DictateUiState.isIdle: Boolean
    get() = recording is RecordingState.Idle && pipeline is PipelineUiState.Idle

val DictateUiState.predRecordingControlsVisible: Boolean
    get() = recording.isActiveOrPaused
```

#### §13.4.2 Neue Duplikate, die im Plan nicht entstehen dürfen

| Risiko | Mitigation |
|---|---|
| `buildNotification` könnte Subtitle-/Action-Logik dupliziert mit `LayoutCatalog`-Resolvers haben | NICHT akzeptiert. Die `notifSubtitleFor(state)`-Funktion (§11.1.2) ist State-→-String-Mapping; sie nutzt KEINE View-Resolver, schreibt nur Notification-Strings. Klare Trennung Visual-IME-State (LayoutCatalog) vs. Notification-State. Wenn ein Refactor-Reviewer Code-Duplikation entdeckt: gemeinsamen Helper `state.toUserVisibleSummary()` extrahieren. |
| Service- und IME-Service haben beide ein `scope` (`CoroutineScope`) | Akzeptiert: zwei verschiedene Scopes mit verschiedenen Lifetimes. IME-`viewScope` wird beim View-Recreate gecancelt; Service-`serviceScope` lebt mit dem Service. Naming explizit: `viewScope` vs. `serviceScope` — kein versehentlicher Cross-Use. |
<!-- FIX: Phase-B S-1 (2026-05-13) – Code-Review-Checkliste auf PipelinePrefMirror umgestellt. -->
| Pref-Spiegelung in `DictateUiState` und Pref-Read in einzelnen Modul-Reducern gleichzeitig | NICHT akzeptiert. **Regel:** sobald ein Pref in `DictateUiState` gespiegelt ist, lesen Modul-Reducer NUR aus `ctx.global.X`, nie direkt aus `sp`. Code-Review-Checkliste: `sp.get(Pref.SmallMode)` darf nur im `PipelinePrefMirror.initialMirror`/`sync` vorkommen. |
<!-- FIX: Phase-B S-4 (2026-05-13) – Phase-1 vs. Phase-2: prefBindings()-Override nur Default emptyList(). -->
| Modul-Override von `prefBindings()` in Phase 1 | NICHT akzeptiert. **Regel:** In Phase 1 lassen alle Module die Default-`emptyList()`-Implementation; `PipelinePrefMirror` (§4.5) verwendet hardcodierte Liste, kein `modules.flatMap { prefBindings() }`. Code-Review-Checkliste: `override fun prefBindings()` mit non-empty Body ist Phase-2-Code (Hauptplan §7.1 Out-of-Scope) und wird im Block-1b-Audit blockiert. |

### §13.5 Identified Gaps + Mitigations

<!-- FIX: Issue 3.0.7 – §13.5 in drei Bereiche getrennt (Open / Cross-Spec-Pending / Resolved); Audit-Funktion wieder klar -->

#### §13.5.a Open Gaps (aktuell offen, im Block-1/2-Scope zu adressieren)

| # | Gap | Schweregrad | Mitigation |
|---|---|---|---|
| G1 | `KeyboardUiController.kt:241` mutiert `views.infoCl.visibility = GONE` direkt in `startPipeline` — das ist eine state-getriggerte Mutation, die heute über die Hilfsklasse `InfoBarController.dismiss()` laufen sollte, aber direkt geht. | Mittel | In Block 1: Mutation-Site auf `infoBarController.dismiss()` umstellen — danach hat InfoBarController die alleinige Verantwortung über infoCl. |
| G2 | `DictateInputMethodService.java:2630-2636` (`onSmallModeToggled`) schreibt direkt in `Pref.SmallMode` UND ruft `stateManager.setSmallMode(newSmallMode)` — zwei Schritte, die in seltenen Fällen out-of-sync sein können. | Niedrig | In Block 1 ist mit dem DictateUiState-Pref-Spiegel-Pattern (§13.2.2) der State automatisch konsistent — der explizite `setSmallMode`-Call wird redundant und entfällt. |
| G6 | Service-Death während aktivem Recording: `RecordingManager.stop()` wird nicht mehr gerufen → MediaRecorder bleibt im Native-Heap. | Mittel | Zwei Pfade explizit getrennt: **(A) Service.onDestroy normal (testbar)** — der Service ruft `orchestrator.dispatch(Action.PipelineAction.CancelPipeline)` → der `PipelineModule`-Reducer/EffectHandler emittiert `Effect.ReleaseRecording` → `recordingManager.release()`. Der `release()`-Pfad wird via Mock-Spy im Block-2-Unit-Test verifiziert (siehe §10 Block-2-Acceptance). **(B) Process-Kill (nicht testbar)** — Android-System-Cleanup räumt MediaRecorder und Native-Heap selbst ab. Akzeptiert. |
| G7 | `JobExecutor.initialize(orchestrator)` wird heute im IME-`onCreate` (Z. 389) gerufen — mit dem Service-Refactor muss das in den Service-onCreate. Wenn der IME-Service ohne den Pipeline-Service hochfährt (theoretisch nicht möglich, aber defensiv), ist `JobExecutor` un-initialisiert. Beachte: das `JobExecutor.initialize(orchestrator)` (Z. 56-58 verifiziert via Code-Read) erwartet den **alten `PipelineOrchestrator`** (Audio-Pipeline-Runner), NICHT den neuen `DictateOrchestrator` — siehe §1.x Naming-Konvention (Phase-B S-4). | Niedrig | `bindService` hält den Service-Lifecycle ans IME — es gibt keine Lifecycle-Sequenz, in der IME ohne Pipeline-Service läuft, sobald die Bind-Connection steht. Falls aus Robustheits-Gründen nötig: defensiv-`null`-Check in JobExecutor + lazy-init beim ersten Job-Start. |

#### §13.5.b Cross-Spec Patches Pending

(Aktuell keine offenen Cross-Spec-Patches in Spec 1. Spec-2-Eintragungen `WIDGET_TOGGLE` siehe Spec 2 §13.5.b; Spec-3-State-Achsen `state.overlay.*` sind via F-10 in §3 modelliert und damit RESOLVED — siehe §13.5.c G3-Eintrag dort.)

#### §13.5.c Resolved (Iter-History)

| # | Gap | Status | Auflösung |
|---|---|---|---|
| G3 | `DictateInputMethodService.java:914-958` (`recordingStateController.setCallback`) wird bei jedem `onCreateInputView` neu gesetzt → leak risk bei alter Callback. | RESOLVED via F-1 / F-11 | Block-1-Migration eliminiert dieses Pattern. Subscriber wird beim View-Recreate via `viewScope.cancel()` automatisch detached (StateFlow-Pattern). |
| G4 | `KeyboardUiController.callbacks: CopyOnWriteArrayList<PipelineUiCallback>` (Z. 82) — heute eigener Multi-Callback-Mechanismus. | RESOLVED via F-1 / F-11 | In Block 1 entfällt der Callback-List-Mechanismus zusammen mit `KeyboardUiController.state`. Subscriber gehen über `StateFlow.collect`. |
| G5 | Beim ersten Pipeline-Start werden 3 SP-Reads gleichzeitig benötigt (`Pref.LastFileName`, `Pref.ResendButton`, `Pref.AudioFocus`) — Race-Window klein aber existent. | RESOLVED via F-10 (Pref-Mirror) | Da die Prefs gespiegelt sind (§13.2.2), liest die Action-Methode aus `_state.value` (atomar). Race verschwindet strukturell. |

---

## §14 Open Questions

Bewusst offen gelassen, weil über bibliotheksspezifisches Wissen hinausgehend, das verifizierbar gegen Android-Docs fehlt:

1. **`MotionLayout`/`Transition`-Interaktion mit Foreground-Service-Notification-Updates** — wenn die Notification 60-mal pro Sekunde aktualisiert wird (z.B. Recording-Timer), throttled Android das? Empfehlung: Notification-Update nur bei semantischen State-Changes, nicht für Timer-Ticks (Subtitle bleibt "Aufnahme läuft" — keine Sekunden-Anzeige in der Notification).
2. **`startForeground` mit `FOREGROUND_SERVICE_TYPE_MICROPHONE` ohne aktive Recording** — Android 14 erlaubt das technisch beim FGS-Start (das System prüft Mikrofon-Permission, nicht aktive Nutzung). Verifikation auf einem Pixel-API-34-Device steht aus.
3. **Pre-Insertion-State-Survival nach Process-Death** — wenn der Process stirbt, NACHDEM `final_output_text` geschrieben aber BEVOR `inserted_at` gesetzt wurde, sieht der nächste `recoverFromDb` die Session als "pending insertion". Stimmt — aber: ist der zuletzt fokussierte InputConnection nach Process-Restart noch verfügbar? Vermutlich nein. **Konsequenz:** der User muss explizit auf "Einfügen" klicken und in einen neuen Input-Field tippen — das ist die D4-Wahl. Akzeptiert.
4. <!-- FIX: Issue 1.1.3 (User-Decision Option B) – Mode 3 als Open-Question für Phase 2 -->
   **Cross-Module-Effect-Modus 3 (Atomic Cross-Axis-Update) — Phase-2-Backlog.** §15.5 listet
   nur Modi 1+2 als verbindlich für Phase 1. Modus 3 wäre ein Spezial-Reducer im Orchestrator,
   der mehrere Sub-State-Achsen in einem `store.update` atomar mutiert. Bedarfs-Indikator: ein
   konkreter Use-Case, in dem Cascade (Modus 2) ein semantisches Race verursacht (z.B. eine
   Pipeline-Done-Aktion, die *gleichzeitig* `pipeline = Idle` und `resend.lastAudioExists = true`
   setzen muss, bevor irgendein Subscriber eine inkonsistente Zwischen-Sicht sieht). Stand
   2026-05-10 ist kein solcher Use-Case beobachtet — Mode 2 mit `predResendVisible`-Helper
   (R.7 Block 1a) löst die identifizierten Spec1-Logic-L-8-Fälle. Wenn Phase 2 einen echten
   Bedarf erkennt, wird Modus 3 als **explizites OCP-Bruch-Pattern** spezifiziert (zentrale
   Tabelle der Atomic-Reducer im Orchestrator).
5. <!-- FIX: Issue 3.1.4 (User-Decision Option C Hybrid) – STANDALONE_OVERLAY-Migration als Phase-2-Backlog -->
   **Overlay-Owner-Architektur-Migration (Phase 2).** Heute (Phase 1, Option C Sofort-Fix):
   IME-Service-onDestroy ruft `keyboardLayoutManager.detachAllBackends()` (eliminiert Window-Leak
   beim Tastatur-Wechsel). Phase 2 prüft, ob STANDALONE_OVERLAY-Use-Cases (Overlay sichtbar
   ohne aktives IME-Editor-Field) eine eigene `OverlayWindowService`-Migration verlangen — mit
   eigenem Lifecycle, FGS-Notification und IME-unabhängigem Trigger.
6. <!-- FIX: Issue 3.1.8 (User-Decision Option C) – STANDALONE_OVERLAY-Backlog -->
   **WIDGET-Modus mit deaktiviertem HOVER (T4-Constraint).** Phase 2 könnte einen
   `STANDALONE_OVERLAY`-Modus einführen, in dem WIDGET ohne HOVER-Pendant funktioniert —
   z.B. für Foldable-Outer-Display-Use-Cases. Heute akzeptierter Constraint: WIDGET-autark
   gilt nur im WIDGET-Modus selbst (Doku-Klärung — Issue 3.1.8 Option A).

---

## §15 Modul-Inventar (F-11)

<!-- FIX: Phase-C C-1 (2026-05-14) – Modul-Zähler aktualisiert auf 14 (13 aktiv + 1 Phase-2-Stub).
     Phase-B S-3 hat KeyboardInputModule (§15.6) als 13. aktives Modul nachgereicht.
     Off-by-One-Klarstellung gegen §3: KeyboardInputModule hat KEINE eigene State-Achse
     (Unit-State, §15.6) — daher 14 Module, aber nur 13 Sub-State-Felder im DictateUiState
     (§3-Tabelle zeigt 13 Achsen + Top-Level-Bool). Beide Zahlen sind korrekt für ihr Schema. -->
Die 14 Module (13 aktive + 1 Phase-2-Stub) sind in `app/src/main/java/net/devemperor/dictate/state/modules/` gruppiert. Pro Modul eine eigene Datei mit:
- **State-Sub-Klasse** (vom Modul verwaltet, im DictateUiState als Sub-Feld)
- **Module-Effect-Sub-Sealed-Interface** (Effect-Varianten dieses Moduls)
- **Reducer** (F1+F2 pure function)
- **EffectHandler** (Hardware-Calls)
- **Cross-Module-Observer** (optional, für Cascade-Trigger)

### §15.1 Modul-Übersicht

| # | Modul | Achse | F1+F2 nötig? | Cross-Module-Observer? |
|---|---|---|---|---|
| 1 | RecordingModule | recording (sealed RecordingState) | ✓ explizit | ja (Idle → Preparing → OverlayAction.ResetSuppressBit) <!-- FIX: Issue PENDING-3 / Spec-3 Reducer Simplified – OverlayAction.ResetSuppressBit --> |
| 2 | PipelineModule | pipeline (sealed PipelineUiState) | ✓ explizit | ja (PipelineDone → Resend, LivePrompt) |
| 3 | AudioModule | audio (AudioState) | mittel | ja (AudioFocus-Loss → Recording.Pause; Recording.Preparing → AudioFocus-Request) |
| 4 | ViewModeModule | viewMode (enum) | F4-Subset (ehemals ViewModeFsm) | ja (Recording-Active+View-hidden → HOVER) |
<!-- FIX: Issue 1.1.5 / R.5 – LayoutModule eigentümert nur noch `layout`-Achse (contentArea ist Sub-Field) -->
| 5 | LayoutModule | layout (LayoutState — contentArea + 3 Booleans) | trivial | nein |
<!-- FIX: Issue 3.1.1 / 3.1.2 (User-Decision Option A) – OverlayModule-Spec-Heimat: Spec 3 §4.x neu; ViewModeModule-Detail in Spec 3 §7.1 nur als Doku -->
| 6 | OverlayModule | overlay (OverlayState — Position + Permission + Suppress-Bit + Onboarding) | trivial-mittel | ja (HOVER-Permission-Loss → Mode-Cascade; siehe Spec 3 §4.x) |
| 7 | ResendModule | resend | mittel (Cooldown-Timer) | ja (Pipeline-Done → MarkLastAudio) |
| 8 | LivePromptModule | livePrompt | trivial | ja (Pipeline-Done → ChainNext) |
| 9 | LanguageModule | language | trivial | ja (Reprocess-Override → Language.Override) |
| 10 | FeatureToggleModule | features | trivial | nein |
| 11 | ThemingModule | theming | trivial | nein |
| 12 | PendingSessionsModule | pendingSessions | DB-Subscriber (kein Reducer) | nein |
<!-- FIX: Phase-B S-3 (2026-05-13) – KeyboardInputModule als #13 verankert.
     Vorher fehlte das Modul; KeyboardInputAction.Backspace/EnterKey/SpaceKey/CopyToClipboard
     hätte der Orchestrator als `Unrouted` abgewiesen — Button-Klicks wären tot gewesen.
     §15.6 enthält die kanonische Implementierung. -->
| 13 | KeyboardInputModule | n/a (Unit-State, reiner Effect-Producer) | trivial | nein |
| 14 | InterruptionModule (Phase 2) | interruption | mittel | ja (Anruf → Recording.Cancel) |

<!-- FIX: Issue 3.1.12 / R.20 – Cross-Module-Coupling-Matrix als Doku-Erweiterung -->
#### §15.1.x Cross-Module-Coupling-Matrix

Die Matrix macht den impliziten Cross-Module-Vertrag explizit. **Spalte = lesendes/observierendes
Modul, Zeile = schreibendes Modul.** Zellen-Notation:

- `R(state.x.y)` — das lesende Modul beobachtet diese Sub-State-Achse (Read-Coupling).
- `C(Action.X.Y)` — das lesende Modul emittiert diese Cascade-Action als Reaktion auf den State-Change.
- leer — kein Coupling.

<!-- KG-RSB-3 RESOLUTION 2026-05-11: Notations-Konvention für Self-Reads -->
**Notations-Konvention für Self-Reads (Diagonale, KG-RSB-3 RESOLVED 2026-05-11):**
Module lesen ihre eigene Achse implizit — Self-Reads (z.B. `RecordingModule`
liest `state.recording` in seinem `onCrossModuleStateChange`-Cascade-Hook)
werden **NICHT** als separater `R(state.x)`-Eintrag in der Diagonal-Zelle
aufgeführt. Die Diagonal-Zelle bleibt `—`. Cascade-Trigger, die rein auf
einem Self-Read basieren, listen nur die `C(Action.Y.Z)`-Konsequenz in
der Cross-Module-Zelle der jeweiligen Observer-Spalte. Beispiel:
`Recording × Overlay = C(OverlayAction.ResetSuppressBit)` — das `prev.recording
is Idle && next.recording is Preparing`-Predicate im RecordingModule wird
als Implementation-Detail des Owner-Moduls verstanden, nicht als Cross-
Module-Coupling. Verbose Alternative (`[self]R(...)`) wurde bewusst
verworfen — zusätzlicher Notations-Lärm ohne Informationsgewinn.

| Owner ↓ / Observer → | Recording | Pipeline | Audio | ViewMode | Overlay | Resend | LivePrompt | Language | Layout | FeatureToggle | Theming | PendingSessions | Interruption |
|----------------------|-----------|----------|-------|----------|---------|--------|------------|----------|--------|---------------|---------|-----------------|--------------|
| **Recording**        | —         | R(state.recording) C(PipelineAction.Submit) | R(state.recording) | R(state.recording) C(ViewModeAction.OnRecordingActive) | C(OverlayAction.ResetSuppressBit) <!-- FIX: Issue PENDING-3 / Spec-3 Reducer Simplified – OverlayAction.ResetSuppressBit (Recording-Read war pre-PENDING-3; Reset läuft jetzt rein als Cascade ohne dass Overlay den Recording-State lesen muss) --> | R(state.recording) C(ResendAction.MarkAvailable) | | | | | | R(state.recording) C(PendingSessionsAction.Insert) | R(state.recording) C(InterruptionAction.OnRecordingActive) |
| **Pipeline**         | R(state.pipeline) C(RecordingAction.StopRecording) | — | | R(state.pipeline) C(ViewModeAction.OnPipelineDone) | R(state.pipeline) C(OverlayAction.OnPipelineDone) | R(state.pipeline) | R(state.pipeline) C(LivePromptAction.ChainNext) | | | | | R(state.pipeline) | |
| **Audio**            | R(state.audio.audioFocusGranted) C(RecordingAction.PauseRecording) | | — | | | | | | | | | | |
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
**Matrix-Caption:** Die Matrix listet ausschließlich Module mit eigener State-Achse (13 Zeilen/Spalten
oben + Diagonale). **KeyboardInputModule (§15.6) erscheint bewusst NICHT** in der Matrix — Unit-
State, kein Observer-Hook, kein Inbound-Coupling (siehe §15.6 letzter Absatz). Die F-8-Single-
Dispatch-Garantie genügt; eine 14×14-Matrix mit einer leeren Zeile + leeren Spalte wäre Noise.

**SRP-Konsequenz (verlinkt §13.3.13):** Jedes Modul hat **nur** Lese-Coupling auf Achsen, die in
dieser Matrix dokumentiert sind. Ein neuer Lese-Hook ohne Matrix-Eintrag ist ein Code-Review-
Verstoß. Optional kann ein Compile-Zeit-Manifest `data class CrossReadSet(val reads: Set<KClass<*>>,
val cascades: Set<KClass<out Action>>)` pro Modul deklariert werden — die Doku-Tabelle bleibt
primärer Träger.

<!-- FIX: Issue PENDING-3 / Spec-3 Reducer Simplified – Recording × Overlay-Zelle: bewusst strikt-minimal -->
<!-- KNOWLEDGE-GAP: KG-RSB-3 – Recording × Overlay-Zelle: Read-Eintrag bewusst ausgespart? [RESOLVED 2026-05-11] -->
> **✅ Knowledge-Gap (KG-RSB-3): Recording × Overlay — strikt-minimal vs. R+C — RESOLVED 2026-05-11**
>
> - **Was wir wussten:** Die Matrix-Zelle `Recording → Overlay` zeigt nur
>   `C(OverlayAction.ResetSuppressBit)` — **keinen** `R(state.recording)`-
>   Eintrag, obwohl die Cascade-Bedingung `prev.recording is Idle && next.recording is Preparing`
>   (§15.2 onCrossModuleStateChange) ein klassischer Recording-State-Read ist.
>   Im Vergleich: die Zellen `Pipeline → Recording` und `Audio → Recording`
>   listen *beide* Sides — `R(state.recording) C(...)`. Die Recording → Overlay-
>   Zelle weicht von dieser Konvention ab.
> - **Was wir nicht wussten:** Ist die Auslassung des `R(state.recording)`-
>   Eintrags eine bewusste Notations-Konvention oder eine Inkonsistenz?
> - **Auflösung:** **Notations-Konvention explizit gemacht** — Self-Reads
>   (Modul liest seine eigene Achse) werden NICHT in die Matrix-Zeile
>   eingetragen, sind implizit durch die Diagonale `—` abgedeckt. Damit
>   bleibt `Recording × Overlay = C(OverlayAction.ResetSuppressBit)` strikt-
>   minimal und konsistent. Die verbose Alternative (`[self]R(state.recording)`-
>   Tag) wurde verworfen — sie hätte jede Cascade-Zeile ohne Informationsgewinn
>   um einen Self-Read-Marker erweitert.
> - **Einarbeitung:** Konvention oberhalb der Coupling-Matrix in §15.1.x
>   als Vorbemerkung dokumentiert ("Notations-Konvention für Self-Reads (Diagonale)").
>   Die Konvention gilt rückwirkend für alle bestehenden Matrix-Zeilen: jede
>   Diagonal-Zelle bleibt `—`, Cross-Module-Cascade-Trigger listen ausschließlich
>   die `C(Action.Y.Z)`-Konsequenz. Keine Code-Änderung nötig.

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
    override fun reduceFailure(
        state: RecordingState,
        failure: Action.EffectFailure,
        ctx: ReducerContext,
    ): TransitionResult<RecordingState, Effect>? = when {
        // Failure beim Allocate-Effect (Hardware-IO failed, externer Cache-Wipe, MIC-Permission
        // entzogen mid-prepare): State zurück auf Idle. Das angeforderte audioFile war noch nicht
        // im MediaRecorder geschrieben (`MediaRecorder.prepare()` wirft VOR dem ersten Frame) —
        // im worst case bleibt ein 0-Byte-File im cacheDir/audio/, das cleanupOrphans entsorgt.
        failure.effect == "AllocateMediaRecorder" && state is RecordingState.Preparing ->
            TransitionResult(
                nextState = RecordingState.Idle,
                sideEffects = listOf(
                    Effect.ReleaseMediaRecorder,    // idempotent, no-op falls allocate gar nicht durchkam
                    Effect.DeleteAudioFile(state.audioFile),    // best-effort, fängt 0-Byte-Files
                ),
            )
        // Failure beim Stop-Effect (MediaRecorder.stop wirft): State auf Idle, kein File-Delete
        // (Audio ist ggf. valid persistiert worden vor dem Stop-Throw).
        failure.effect == "StopMediaRecorder" && (state is RecordingState.Active || state is RecordingState.Paused) ->
            TransitionResult(
                nextState = RecordingState.Idle,
                sideEffects = listOf(Effect.ReleaseMediaRecorder, Effect.StopTimer, Effect.StopBorderGlow),
            )
        // Andere Failures: Default-Verhalten (Rejected). Künftige Effects können explizit ergänzt
        // werden, wenn ein neuer Failure-Pfad nötig ist.
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
**audioFile-Vertrag (R.2):** `audioFile` lebt im RecordingState (Pure-Function-Garantie); der
Hardware-Read entfällt. Der Allocator-Effect (`AllocateMediaRecorder`) bekommt das File-Objekt
von außen (Caller, z.B. PipelineRunner oder LocalBinder.startSession) — der Reducer ist 100 %
pure, State-Tests brauchen keinen `ModuleServicesFactory`-Stub mehr.

**Konsistenz der drei AllocateMediaRecorder-Sites (Phase-B S-4):**

1. **Definition** (Effect-sealed-interface oben): `AllocateMediaRecorder(target, useBluetooth, audioFile)` — 3 Felder.
2. **Reducer-Use** (Idle→Preparing-Branch): `Effect.AllocateMediaRecorder(action.target, ctx.global.audio.useBluetoothMic, action.audioFile)` — 3 Args.
3. **EffectHandler-Use** (`runEffect`-Body): `services.recordingHardware.allocate(effect.target, effect.useBluetooth, effect.audioFile)` — 3 Args.

Die drei Sites müssen synchron bleiben. Vor Phase-B S-4 hatte die Definition 2 Felder
+ Reducer-Use 3 Args + EffectHandler-Use 2 Args — eine drei-fache Inkonsistenz, die
beim ersten `./gradlew assembleDebug` als Compile-Error aufgefallen wäre.

<!-- FIX: Issue PENDING-3 / Spec-3 Reducer Simplified – Cascade-Reihenfolge bei StartRecording -->
**Cascade-Reihenfolge bei `StartRecording` (Spec 1 §4.3 `dispatchInternal`-Pipeline):**

Die Pipeline-Schritte aus §4.3 (Steps 1–6) verhalten sich für `StartRecording`
deterministisch wie folgt. Wichtig: `Effect.AllocateMediaRecorder` läuft **synchron
vor** dem Cross-Module-Cascade — der `runEffect`-Hardware-Call selbst ist
schnell (`MediaRecorder.allocate()` setzt nur ein Handle; das tatsächliche
`prepare()` läuft async und kommt später als `Action.RecordingAction.MediaRecorderReady`
zurück).

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

> **Verdict (KG-RSB-2, 2026-05-11):** Die ursprüngliche Filter-Klausel `it.id !=
> module.id` (§4.3 Step 5, Z. 624 vor Fix) hätte den hier beschriebenen
> `onCrossModuleStateChange`-Hook für RecordingModule bei der eigenen
> `StartRecording`-Action garantiert blockiert — der Hook wäre toter Code,
> die `ResetSuppressBit`-Cascade hätte niemals gefeuert, das Suppress-Bit
> wäre nach erstem User-Close-Klick permanent `true` geblieben und der
> HOVER-Auto-Reopen-Pfad wäre für den Rest der Session-Lifecycle blockiert.
> Production-Bug-Risiko bestätigt → Auflösung (A): Self-Filter aus §4.3
> Step 5 gestrichen (siehe FIX-Kommentar dort). MAX_CASCADE_DEPTH (R.6, Cap 8)
> deckt Endlos-Cascade-Loops als alleinige Sicherung weiterhin ab.

<!-- KNOWLEDGE-GAP: KG-RSB-1 – Service-Boot-Recovery: Suppress-Bit-Default [RESOLVED 2026-05-11] -->
> **✅ Knowledge-Gap (KG-RSB-1): Boot-Default des Suppress-Bits — RESOLVED 2026-05-11**
>
> - **Was wir wussten:** `OverlayState.suppressAutoOverlayUntilNextSession` hat
>   Default `false` (§3, Zeile 152); es ist NICHT in `PipelinePrefMirror.initialMirror`
>   gelistet (§4.5, Zeilen 703–735) und auch nicht im SP-Schema (§6.4 listet
>   nur die vier Position-Floats). Das Bit ist **transient** — lebt nur im
>   StateFlow im PipelineService, geht bei Service-Death verloren.
> - **Was wir nicht wussten:** Ob `OverlayState`-Persistenz bewusst auf
>   "nur Position + Permission, kein Suppress, kein Onboarding-Pending" beschränkt
>   ist — Spec 3 §11.9 dokumentierte die Entscheidung explizit nur für
>   `userPrefersWidget`. Soll das Suppress-Bit beim Service-Restart auf
>   `false` zurückgesetzt werden (status quo) oder explizit als bewusste
>   Boot-Semantik dokumentiert sein wie in §11.9?
> - **Auflösung:** **Status-quo bestätigt + bereits in Spec 3 §11.9
>   dokumentiert (PENDING-3-Spiegel-Eintrag).** Begründung: das Bit ist
>   transient, jeder Boot startet mit `false` (data-class-Default greift).
>   User-Wahl überlebt den App-Lifecycle bewusst NICHT — das ist UX-konsistent
>   mit `userPrefersWidget`: nach App-Restart darf das Overlay wieder
>   aufpoppen, weil die Session-Boundary durch den Restart eh überschritten
>   wurde. Der Suppress-Vertrag ("verhindere Auto-Reopen für *diese* Session")
>   ist nach Process-Tod gegenstandslos, weil keine Session aktiv ist.
>
>   **Code-Änderung: keine.** Spec 3 §11.9 enthält bereits den Spiegel-
>   Eintrag (Z. 1840–1856, "Persistenz-Bit
>   `state.overlay.suppressAutoOverlayUntilNextSession` (PENDING-3, KG-RSB-1)"),
>   in dem die Boot-Semantik explizit dokumentiert ist:
>
>   > Service-Restart (OOM-Recovery): Bit ist `false` per default. Begründung
>   > identisch zu `userPrefersWidget` — nach Process-Tod ist keine aktive
>   > Recording-Session mehr im Flug, der Suppress-Vertrag ist gegenstandslos.
>
> - **Einarbeitung:** Doku-Anchor in Spec 3 §11.9 (PENDING-3-Spiegel-Eintrag)
>   bleibt unverändert — Resolution erkennt an, dass die Doku schon vollständig
>   ist. Kein Code-Touch nötig in Block 6.

<!-- KNOWLEDGE-GAP: KG-RSB-2 – Self-Cascade durch §4.3 Step-5-Filter [RESOLVED 2026-05-11: Auflösung A — Self-Filter gestrichen] -->
> **⚠ Knowledge-Gap (KG-RSB-2): RecordingModule kann sich selbst nicht observieren**
> **[RESOLVED 2026-05-11: Production-Bug bestätigt → Auflösung (A) gewählt; siehe FIX-Kommentar in §4.3]**
>
> - **Was wir wissen:** §4.3 Step 5 (Cross-Module-Observation) filterte
>   vor dem 2026-05-11-Fix das emittierende Modul aus dem Cascade-Pass:
>   `modules.filter { it.id != module.id }` (Zeile 624 *vor* Fix).
>   Wenn RecordingModule die `StartRecording`-Action reduziert,
>   wäre *sein eigener* `onCrossModuleStateChange` in diesem Pass NICHT
>   gerufen worden — die hier definierte `Idle → Preparing → ResetSuppressBit`-Cascade
>   hätte nie gefeuert. **Bug-Verifikation 2026-05-11: durch Code-Lesung
>   des `dispatchInternal`-Snippets bestätigt — der Filter ist deterministisch,
>   keine andere Modul-`onCrossModuleStateChange`-Implementation observiert
>   `Idle → Preparing` (Spec 3 §4.8 OverlayModule liest bewusst kein
>   `state.recording`, war der Grund für die jetzige Architektur).**
> - **Was wir nicht wissen:** Drei mögliche Auflösungen, der Plan legt sich
>   nicht eindeutig fest:
>   - **(A)** Self-Filter ist als Sicherheits-Maßnahme gegen Endlos-Cascades
>     gedacht, und der `Idle → Preparing`-Übergang soll erkannt werden.
>     Lösung: Filter entfernen oder Self-Cascade explizit erlauben
>     (Cascade-Depth-Counter R.6 + DEBUG-Assert deckt Loops bereits ab).
>   - **(B)** Die ResetSuppressBit-Cascade lebt in einem **anderen** Modul-
>     `onCrossModuleStateChange`, z.B. OverlayModule selbst (würde aber
>     `state.recording` lesen → Coupling-Matrix-Read-Eintrag — war gerade
>     der Grund für die jetzige Lösung) oder einem dedizierten
>     LifecycleObserver-Modul.
>   - **(C)** RecordingModule emittiert die `ResetSuppressBit`-Action direkt
>     im Reducer als zweite Output-Action (statt im Observer). Würde §15.5
>     Mode 2 (Action-Cascade) konzeptuell brechen, weil der Reducer
>     Cross-Module-Actions emittiert.
> - **Klärbar durch:** Code-Recherche im `DictateOrchestrator.dispatchInternal`-
>   Implementierungs-Test: ein Unit-Test
>   `recordingModule_idleToPreparing_emitsResetSuppressBit` verifiziert das
>   gewünschte Verhalten gegen die echte Orchestrator-Implementation.
>   Wenn der Test rot ist → §4.3 Step 5 Filter ist falsch ausgelegt →
>   Auflösung (A).
> - **Auswirkung wenn ungeklärt:** Implementer baut die Cascade nach
>   bisherigem Plan-Wortlaut, sie feuert nie, Bit bleibt `true`, HOVER-Auto-
>   Reopen funktioniert nie wieder nach erstem User-Close. **Production-
>   Bug**.
> - **Empfohlene Default-Strategie:** Auflösung **(A)** — den Self-Filter in
>   §4.3 Step 5 streichen. Begründung:
>   - Cascade-Depth-Counter (R.6, Cap 8) deckt Endlos-Cascades bereits ab —
>     ein Self-Filter ist redundante Belt-and-Suspenders-Sicherheit.
>   - SRP: ein Modul soll auf seine *eigenen* State-Transitionen reagieren
>     dürfen (z.B. "ich bin gerade in Preparing eingetreten, also möchte ich
>     X cross-cascaden"); das ist semantisch dieselbe Operation, die der
>     Reducer auch hätte machen können, nur sauber im Observer separiert.
>   - Konsistenz mit der Coupling-Matrix: die Matrix-Diagonale (`Recording ×
>     Recording`) ist `—` (kein Self-Coupling im Sinne von Reads), aber
>     Self-Cascade-Trigger sind ein anderes Konzept — ein Modul löst eigene
>     Folge-Actions aus, ohne Cross-Modul-Reads.
>
>   Falls (A) abgelehnt wird → (B) mit OverlayModule + Recording-Read-
>   Matrix-Eintrag (verschiebt SRP, war der Grund für die jetzige Lösung).
>
> - **Resolution (2026-05-11):** Auflösung (A) **angewendet**. Der Filter
>   `modules.filter { it.id != module.id }` in §4.3 Step 5 (Z. 624 vor Fix)
>   ist gestrichen — siehe FIX-Kommentar dort. Der Test
>   `recordingModule_idleToPreparing_emitsResetSuppressBit` (vorgeschlagen
>   im "Klärbar durch"-Block oben) ist als **Pflicht-Test** für Block 4
>   im Acceptance-Block §10 unten zu ergänzen (siehe `R.RSB-FIX-A`-Klausel
>   falls noch nicht vorhanden) — als Regression-Test für den Filter-Fix.

<!-- FIX: Issue PENDING-3 / Spec-3 Reducer Simplified – Logging-Empfehlung -->
**Logging-Empfehlung (Telemetrie):** Der `DictateOrchestrator.dispatchInternal`
loggt heute (§4.3) nur Effect-Failures und Cascade-Loops. Für die
ResetSuppressBit-Cascade EMPFEHLEN wir ein DEBUG-Log direkt im
`onCrossModuleStateChange`-Block:

```kotlin
if (prev.recording is RecordingState.Idle && next.recording is RecordingState.Preparing) {
    if (BuildConfig.DEBUG) Log.d(
        "RecordingModule",
        "Session-Start cascade: ResetSuppressBit (prev.suppress=${prev.overlay.suppressAutoOverlayUntilNextSession})"
    )
    cascade.add(Action.OverlayAction.ResetSuppressBit)
}
```

Begründung: das Bit ist transient + invisible (kein direktes UI-Feedback);
ein DEBUG-Log am Trigger-Punkt erleichtert die Diagnose, wenn der HOVER-
Auto-Reopen-Pfad nicht greift. Release-Log nicht nötig — Bug ist sichtbar
am ausbleibenden Reopen. Pendant-Log im `OverlayModule.reduce(ResetSuppressBit)`
ist optional, aber die Action-Cascade ist über `DispatchOutcome` bereits
nachverfolgbar.

<!-- FIX: Issue 2.0.8 – Paused.Stop/Cancel TODO()-Stubs durch echte Reducer-Arme ersetzt -->

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
     * **AudioFocus-Request beim Recording-Start (Phase-B S-4):** Der vorher hier
     * gezeigte `if (Idle → Preparing) { ... }`-Block war Dead-Code (leerer Body).
     * AudioFocus-Request läuft als Effect direkt im RecordingModule beim
     * Preparing-Übergang (Effect.AllocateMediaRecorder kapselt das im
     * RecordingHardwareSubsystem.allocate-Pfad — kein Cross-Module-Cascade nötig).
     * Würde AudioModule den Request hier triggern, wäre AudioFocus-Lifecycle in
     * zwei Modulen verteilt — SRP-Verstoß.
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

<!-- FIX: Issue 1.1.3 (User-Decision Option B) – Mode 3 wird zu §14 Open-Question; §15.5 nur 2 Modi -->
**Zwei Modi für Cross-Module-Wirkung sind in Phase 1 verbindlich:**

| Modus | Mechanismus | Wann nutzen? |
|---|---|---|
| **1. Eigene SideEffect** | Reducer-Output enthält Hardware-Effect des eigenen Moduls | für Hardware/Pref, die zur eigenen State-Mutation gehören |
| **2. Action-Cascade** | `onCrossModuleStateChange` returns Liste von Actions, der Orchestrator dispatcht sie rekursiv (mit depth-counter, R.6) | für Cross-Module-Reaktionen (AudioFocus-Loss → Pause; PipelineDone → Resend.MarkAvailable) |

**Standard-Empfehlung:** Modus 2 (Action-Cascade). Mode 1+2 decken den heutigen Bedarf vollständig
ab — die in Sec1-Logic L-8 identifizierten Use-Cases (Auto-Enter-Chain, Resend-Pulse-Race) lassen
sich mit Cascade + `predResendVisible`-Konsolidierungs-Helper (siehe R.7 Block-1a) korrekt abbilden.

**Mode 3 (Atomic Cross-Axis-Update) ist explizit Out-of-Scope für Phase 1** — siehe §14 Open
Questions: erst bei konkretem Bedarf in Phase 2 nachrüsten (kein Halb-Pattern, keine spekulative
Architektur).

<!-- FIX: Phase-B S-9 (2026-05-13) – Anti-Beispiel-Block + Self-Read-Konvention Cross-Link.
     Hintergrund: Phase-A Surprise-Finding #3 fand in Spec 3 §7.3 T1+T2 eine versehentliche
     Mode-3-Mutation (ViewModeModule mutiert viewMode + layout.smallMode + overlay.userPrefersWidget
     in einem Reducer-Block); §6.1 hatte die korrekte Mode-2-Form. Doppel-Truth-Quelle.
     S-9 hat §7.3 auf §6.1-konsistente Form gebracht. Diese Anti-Beispiel-Tabelle macht die
     Disambiguation explizit, damit ein zukünftiger Maintainer die Modi nicht durcheinander wirft. -->
**Anti-Beispiel-Tabelle — wann KEINE Cascade (vs. Mode 1/2 vs. Mode-3-Backlog):**

| Pattern | Beispiel | Modus | Rationale |
|---|---|---|---|
| Modul mutiert **nur seine eigene Sub-State-Achse** + emittiert Effects auf eigene Hardware | RecordingModule.reduce setzt `recording = Preparing` + Effect `AllocateMediaRecorder` | **Mode 1** | SRP — Achse + Effects gehören zur eigenen Verantwortung |
| Modul mutiert seine Achse + andere Module sollen darauf reagieren (Folge-Mutation auf ANDERE Achse) | ViewModeModule setzt `viewMode = KEYBOARD`; LayoutModule reagiert via `onCrossModuleStateChange` → `LayoutAction.SetSmallMode(true)` | **Mode 2** | Cross-Module-Cascade — jedes Modul bleibt SRP-konform; Folge-Mutation wandert in das **Owner-Modul der Ziel-Achse** |
| Modul mutiert seine eigene Achse + EINE ANDERE Achse in einem Reducer-Schritt | `ViewModeModule.reduce` setzt `viewMode + layout.smallMode + overlay.userPrefersWidget` gleichzeitig (atomar) | **Mode 3 (Phase-2-Backlog, NICHT verwenden)** | SRP-Bruch — ViewModeModule schreibt in fremde Achsen; Test/Refactor-Schwierigkeit; Plan §15.5 + §14 Open-Q 4 |
| Modul liest seine eigene Achse (`prev.x` vs `next.x`) als Trigger für eine Cascade auf eine andere Achse | RecordingModule.onCrossModuleStateChange liest `prev.recording is Idle && next.recording is Preparing` → cascadiert `OverlayAction.ResetSuppressBit` | **Mode 2 (Self-Read)** | Self-Read ist KEIN Cross-Module-Coupling im Sinne der Matrix — wird NICHT in §15.1.x-Diagonale eingetragen (KG-RSB-3 Konvention), nur die Cross-Module-`C(...)`-Konsequenz |

**Code-Review-Pflicht:** wenn ein PR einen Reducer enthält, der GLEICHZEITIG zwei verschiedene
Sub-State-Achsen mutiert (`state.copy(x = …, y = …)` mit `x` und `y` in verschiedenen Owner-Modulen),
ist das ein Mode-3-Verstoß. Auflösung: das mutiert nur die EIGENE Achse; die fremde Achse wandert
in einen `onCrossModuleStateChange`-Hook des Owner-Moduls.

**Cross-Link zur Coupling-Matrix (§15.1.x):** Jede neue Mode-2-Cascade braucht einen `C(Action.X.Y)`-
Eintrag in der korrekten Zeile der Matrix. Self-Reads (Mode 2 mit eigener Achse als Trigger) folgen
der KG-RSB-3-Konvention — keine Eintragung in der Diagonale, nur die `C(...)`-Konsequenz in der
Cross-Module-Zelle.

<!-- FIX: Phase-B S-3 (2026-05-13) – KeyboardInputModule kanonisch spezifiziert (vorher fehlte das Modul). -->
### §15.6 KeyboardInputModule (Effect-only — `Unit`-State)

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

**`services.clipboard`-Erweiterung:** `ModuleServices` (§4.7) bekommt ein optionales
`val clipboard: android.content.ClipboardManager?` — gesetzt im Service-onCreate aus
`getSystemService(CLIPBOARD_SERVICE)`. Der Backspace/Enter/Space-Pfad braucht
`services.inputConnectionProvider`, das bereits existiert.

**Failure-Modus:** Wenn die `InputConnection` zur Effect-Ausführung null ist (Editor
hat fokus verloren), wird der Effect zur No-Op — bewusst kein `EffectFailure`, weil
"keine Input-Connection" der Standard-Zustand ist (z.B. wenn der User während Aufnahme
zur Home-Screen wechselt). Dieses Verhalten ist konsistent mit dem heutigen
`DictateInputMethodService`-Code, der ebenfalls `getCurrentInputConnection() == null`
als no-op behandelt.

**Kein Cross-Module-Observer:** KeyboardInput hat keine State-Achse, also gibt es nichts
zu beobachten. Andere Module beobachten KeyboardInput auch nicht (es gibt keinen
sinnvollen Trigger "Backspace wurde gedrückt"). Die Coupling-Matrix-Zeile/Spalte
KeyboardInput bleibt leer.

### §15.7 SOLID-Verifikation des Modul-Patterns

| Prinzip | Erfüllung |
|---|---|
| **SRP** | Jedes Modul hat genau eine fachliche Domäne. Reducer + EffectHandler sind kohärent. |
| **OCP** | Neues Modul = neue Datei + 4 kleine Erweiterungen, kein zentraler Code wird angefasst. Mode 1+2 erhalten OCP; Mode 3 wäre OCP-Bruch gegen den Orchestrator — daher Phase-2 (siehe §15.5 + §14 Open Questions). <!-- FIX: Issue 2.0.3 + 1.1.3 – Mode-3-OCP-Konsistenz-Hinweis --> |
| **LSP** | Alle Module sind `DictateModule<S, A, E>` und können polymorph behandelt werden |
<!-- FIX: Phase-C C-1 (2026-05-14) – Methodenzähler aktualisiert (7+4 nach Phase-B S-3 reduceFailure + Issue 2.1.2 prefBindings + Issue 2.1.12 terminate). -->
| **ISP** | `DictateModule`-Interface ist minimal (7 Pflicht-Methoden + 4 optionale Default-Hooks) |
| **DIP** | Orchestrator hängt am `DictateModule`-Interface, nicht an Konkretisierungen. EffectHandler hängt am Subsystem-Interface (über `services`) |
| **DRY** | Action-Liste lebt nur in der `Action`-sealed-class. Pref-Liste nur im PrefMirror. SideEffect pro Modul gekapselt. |
