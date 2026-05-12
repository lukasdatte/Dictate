# Spec 2 — KEYBOARD-Layout (IME-View): KeyboardLayoutManager + LayoutCatalog + MotionLayout

**Status:** Detail-Ausarbeitung — XML, LayoutCatalog, Migration, Verifikation befüllt.
**Hauptplan:** [→ keyboard-layout-refactor.md](../../keyboard-layout-refactor.md)
**Geschwister-Specs:**
- [Spec 1 — Pipeline-Service-Layer](../1-pipeline-service/1-pipeline-service.md)
- [Spec 3 — Floating-Overlay (WIDGET + HOVER)](../3-floating-overlay/3-floating-overlay.md)

---

## §1 Kontext und Scope

Diese Spec beschreibt die **UI-Layer-Architektur** für den KEYBOARD-Modus (IME-View). Sie umfasst:

<!-- FIX: Issue 1.0.3 – „FSM-Owner" → „FSM-Renderer" (Reduce-Logik liegt im ViewModeModule, Spec 1 §15.1) -->
- **`KeyboardLayoutManager`**: Triangle-FSM-Renderer (Subscriber). Subscribiert an `DictateUiState` (aus Spec 1; die FSM-Reduce-Logik liegt im `ViewModeModule`, Spec 1 §15.1), entscheidet über aktiven LayoutMode + RenderBackend.
- **`LayoutCatalog`**: zentrale Daten-Definition aller LayoutModes (KEYBOARD-Sub-Modi + Overlay-Layouts).
- **`LogicalButtonId` / `ButtonSlot` / `RowDescriptor` / `LayoutMode`**: Datentyp-Hierarchie, die das Layout deklarativ beschreibt.
- **`RenderBackend`-Interface**: abstrakte API, die jedes Backend (IME-View, Overlay-Window, später ggf. Notification) implementiert.
- **`ImeViewBackend`**: das Backend für KEYBOARD-Modus, basiert auf MotionLayout.
- **MotionScene-XML-Struktur**: deklarative Position-Definition für alle KEYBOARD-Sub-Modi.
- **Migration**: KeyboardLayoutModeController, MainButtonsController, KeyboardStateManager-Visibility-Logik werden in den Manager + Catalog überführt.

Out-of-Scope (anderer Spec):
- Pipeline-State-Mutation, Service-Lifecycle, Persistence — siehe Spec 1.
- WIDGET- und HOVER-Modi-Rendering, Permissions, Window-Management — siehe Spec 3.

---

## §2 Architektur-Entscheidungen (fixiert)

| # | Entscheidung | Begründung |
|---|--------------|------------|
| L1 | **MotionLayout** als Container im IME-View | Empfohlen durch Phase-2-Recherche. Eliminiert Re-Parenting-Bug-Klasse strukturell. Animationen deklarativ. |
| L2 | **Flache Button-Hierarchie** (alle Buttons direkte Children des MotionLayout) | Phase-2-Empfehlung. Vereinfacht ConstraintSets, vermeidet verschachtelte ConstraintLayouts. |
| L3 | **`VISIBILITY_MODE_IGNORE`** für state-getriebene Buttons (`resend_btn`, `pause_btn`, `trash_btn`, `audio_focus_btn`) | MotionScene managt Position, LayoutManager managt Visibility — kollisionsfrei (Phase-2-Empfehlung). |
| L4 | **Eine View-Instanz pro Backend** (Buttons im IME-View und im Overlay-Window sind getrennte Views) | Android-Hard-Constraint: ein View kann nur in EINEM Window leben. |
| L5 | **LogicalButtonId-Mapping** pro Backend | Manager kennt logische IDs, Backend übersetzt zu konkreten View-Instanzen. |
| L6 | **Subscription-Pattern**: KeyboardLayoutManager collected `pipelineService.state` | Reaktiv, automatischer Re-Render bei jeder State-Änderung. |
| L7 | **PulseLayout-Wrapper bleibt im record_pulse_layout** | record_pulse_layout ist direktes Child des MotionLayout-Roots, record_btn ist Child von record_pulse_layout. PulseLayout wird in MotionScene mit-positioniert. |
| L8 | **Click-Listener nur einmal pro Backend-Attach setzen, lesen Slot zur Click-Zeit** | Memory-Leak-frei (kein neues Lambda pro Tick), aber dynamische Action-Resolution durch State-Snapshot. Begründung in §11.6. |
| L9 | **Special-Touch-Handler werden im `attach()`-Callback einmal verdrahtet** und bleiben über alle Renders bestehen | CursorSwipeTouchHandler, BackspaceSwipeHandler, EnterOverlayHandler sind state-frei und brauchen kein Re-Wiring per Render. |

---

## §3 Datenmodell

### §3.1 LogicalButtonId

```kotlin
enum class LogicalButtonId {
    // KEYBOARD-Modus-Slots
    RECORD,             // record_btn (im PulseLayout-Wrapper)
    RESEND,             // resend_btn
    BACKSPACE,
    AUDIO_FOCUS,        // audio_focus_btn (im action_row, nur Single-Row sichtbar)
    WIDGET_TOGGLE,      // (Spec 3 GAP-4): Toggle-Btn KEYBOARD → WIDGET. Position TBD (Vorschlag: neben AUDIO_FOCUS im action_row)
    TRASH,
    SPACE,
    PAUSE,
    ENTER,

    // Overlay-Modus-Slots (für Spec 3)
    OVERLAY_RECORD,     // (OPEN-2): Record-Button im Overlay (5-Button-Layout, WIDGET autark)
    OVERLAY_SEND,
    OVERLAY_PAUSE,
    OVERLAY_TRASH,
    OVERLAY_CLOSE,
}
```

### §3.2 ButtonSlot, RowDescriptor, LayoutMode

```kotlin
sealed class WidthPolicy {
    object WrapContent : WidthPolicy()
    object FillRemaining : WidthPolicy()  // = ConstraintSet.MATCH_CONSTRAINT
    data class Fixed(val dp: Int) : WidthPolicy()
}

<!-- FIX: Issue 1.1.4 + 2.1.7 / R.3 – actionResolver returnt Action? = null statt Action.NoOp -->
data class ButtonSlot(
    val logicalId: LogicalButtonId,
    val widthPolicy: WidthPolicy,
    val visibilityPredicate: (DictateUiState) -> Boolean,
    val iconResolver: (DictateUiState) -> Int? = { null },
    val textResolver: (DictateUiState) -> CharSequence? = { null },
    val enabledResolver: (DictateUiState) -> Boolean = { true },
    val alphaResolver: (DictateUiState) -> Float = { 1f },
    /** null bedeutet: Click ist im aktuellen State unbedeutend (kein dispatch, kein Log-Spam). */
    val actionResolver: (DictateUiState) -> Action?,
)

data class RowDescriptor(
    val slots: List<ButtonSlot>,
)

enum class BackendType { IME_VIEW, OVERLAY_WINDOW }

<!-- FIX: Issue 2.1.16 / R.12 – sceneStateId direkt am LayoutMode (OCP) -->
data class LayoutMode(
    val id: LayoutModeId,
    val backend: BackendType,
    val rows: List<RowDescriptor>,
    /** Optional MotionLayout-Scene-State-ID. Null für Backends ohne MotionLayout (Overlay). */
    val sceneStateId: Int? = null,
)

enum class LayoutModeId {
    KEYBOARD_TWO_ROW,
    KEYBOARD_SINGLE_ROW,
    KEYBOARD_TWO_ROW_SEND_MODE,
    KEYBOARD_SINGLE_ROW_SEND_MODE,
    KEYBOARD_REPROCESS_STAGING,
    OVERLAY_5BUTTON,  // gemeinsam für WIDGET und HOVER (Spec 3) — 5-Button-Layout: Record/Send/Pause/Trash/Close (OPEN-2)
}
```

### §3.3 Action (für actionResolver) — hierarchische sealed-class-Struktur (F-8 + F-11)

> **Architektur-Korrektur F-8 + F-11 (2026-05-09):** Frühere Spec-Versionen
> hatten `Action` als flache sealed class mit ~25 Varianten. Mit dem Modular-
> Orchestrator-Pattern (Spec 1 §4 / §15) ist es nötig, Actions **pro Modul-
> Achse** zu gruppieren. Type-Parameter `actionClass: KClass<A>` auf jedem
> `DictateModule` braucht eine eindeutige Action-Klasse. Daher:
> hierarchische Sealed-Class-Struktur, eine innere sealed class pro Achse.

```kotlin
// File: app/src/main/java/net/devemperor/dictate/state/Action.kt
sealed class Action {

    <!-- FIX: Issue 1.1.4 + 2.1.7 / R.3 – NoOp entfernt; Resolver returnt jetzt Action? = null -->
    // (Action.NoOp entfernt — actionResolver hat Typ (DictateUiState) -> Action?)

    <!-- FIX: Issue 2.1.3 (User-Decision Option D) – EffectFailure als typisierter Failure-Channel -->
    /** Geworfen vom Orchestrator bei Effect-Exception; Module reagieren via onCrossModuleStateChange. */
    data class EffectFailure(val effect: String, val reason: String) : Action()

    // ─── Recording-Achse (RecordingModule) ───
    sealed class RecordingAction : Action() {
        <!-- FIX: Issue 1.1.7 / R.2 – StartRecording trägt audioFile (vom Caller alloziert) -->
        data class StartRecording(val target: InsertionTarget, val audioFile: java.io.File) : RecordingAction()
        <!-- FIX: Issue 1.1.7 / R.2 – MediaRecorderReady trägt das real allozierte audioFile -->
        data class MediaRecorderReady(val audioFile: java.io.File) : RecordingAction()
        object PauseRecording : RecordingAction()
        object ResumeRecording : RecordingAction()
        object StopRecording : RecordingAction()
        object CancelRecording : RecordingAction()
        object StopRecordingAndSend : RecordingAction()    // = "Senden"-Klick: Stop + Pipeline-Trigger
    }

    // ─── Pipeline-Achse (PipelineModule) ───
    sealed class PipelineAction : Action() {
        <!-- FIX: Issue 2.1.10 / R.8 – sessionId-getrackte Submission; R.15 – sessionId String -->
        data class TriggerPipeline(val sessionId: String, val audioFile: java.io.File) : PipelineAction()
        data class StartPipeline(val sessionId: String, val totalSteps: Int, val autoEnterActive: Boolean) : PipelineAction()
        data class StepStarted(val sessionId: String, val stepName: String) : PipelineAction()
        data class StepCompleted(val sessionId: String) : PipelineAction()
        data class StepFailed(val sessionId: String, val reason: String) : PipelineAction()
        data class PipelineDone(val sessionId: String, val finalText: String) : PipelineAction()
        data class PipelineFailed(val sessionId: String, val reason: String) : PipelineAction()
        data class CancelPipeline(val sessionId: String? = null) : PipelineAction()    // null = aktive Pipeline (UI-Slot)
        data class StartReprocessStaging(val sessionId: String) : PipelineAction()
        data class UpdateReprocessQueue(val sessionId: String, val newQueue: List<Int>) : PipelineAction()
        data class UpdateReprocessLanguage(val sessionId: String, val code: String?) : PipelineAction()
        data class SendStaging(val sessionId: String) : PipelineAction()
        data class CancelReprocessStaging(val sessionId: String) : PipelineAction()
        <!-- FIX: Issue 2.1.19 / R.15 – sessionId String -->
        data class ConfirmInsertion(val sessionId: String) : PipelineAction()
        <!-- FIX: Issue 2.1.7 / R.3 – DismissResult-Action (statt Inline-Repo-Call im Router) -->
        data class DismissResult(val sessionId: String) : PipelineAction()
        <!-- FIX: Issue 2.1.21 / R.17 – PersistenceError-Failure-Channel -->
        data class PersistenceError(val sessionId: String, val reason: String) : PipelineAction()
        <!-- FIX: Issue 2.1.9 (User-Decision Option C) – Manual-Paste-Notification -->
        data class NotifyResultNeedsManualPaste(val sessionId: String) : PipelineAction()
        <!-- FIX: Issue 2.1.9 – User hat manuell gepastet → Flag clearen -->
        object ClearManualPasteFlag : PipelineAction()
    }

    // ─── ViewMode-Achse (ViewModeModule) ───
    sealed class ViewModeAction : Action() {
        object ToggleViewModeWidget : ViewModeAction()
        object OnImeViewShown : ViewModeAction()
        object OnImeViewHidden : ViewModeAction()
        object CloseOverlay : ViewModeAction()
        <!-- FIX: Issue 3.1.3 – Permission-Loss-Cascade braucht expliziten SetViewMode -->
        data class SetViewMode(val mode: ViewMode) : ViewModeAction()
    }

    // ─── Layout-Achse (LayoutModule) ───
    sealed class LayoutAction : Action() {
        object ToggleSingleRowMode : LayoutAction()
        object ToggleSmallMode : LayoutAction()
        <!-- FIX: Issue 1.1.2 – SetSmallMode für Cross-Module-Cascade von ViewModeModule -->
        data class SetSmallMode(val enabled: Boolean) : LayoutAction()
        data class SetContentArea(val area: ContentArea) : LayoutAction()
    }

    // ─── Audio-Achse (AudioModule) ───
    sealed class AudioAction : Action() {
        object ToggleAudioFocusPref : AudioAction()
        data class OnAudioFocusGrantChanged(val granted: Boolean) : AudioAction()
        data class OnBluetoothScoStateChanged(val phase: ScoPhase, val reason: String? = null) : AudioAction()
    }

    // ─── Resend-Achse (ResendModule) ───
    sealed class ResendAction : Action() {
        object ResendLastAudio : ResendAction()
        object ResendLastAudioLong : ResendAction()    // long-press → ReprocessStaging
        object ResendCooldownExpired : ResendAction()
        data class MarkLastAudio(val exists: Boolean) : ResendAction()
    }

    // ─── LivePrompt-Achse (LivePromptModule) ───
    sealed class LivePromptAction : Action() {
        object EnableLivePrompt : LivePromptAction()
        object DisableLivePrompt : LivePromptAction()
        data class ChainNext(val text: String) : LivePromptAction()
    }

    // ─── Language-Achse (LanguageModule) ───
    sealed class LanguageAction : Action() {
        data class SetOverride(val code: String?) : LanguageAction()
        object RefreshFromPref : LanguageAction()
    }

    // ─── Overlay-Achse (OverlayModule) ───
    sealed class OverlayAction : Action() {
        data class UpdateOverlayPosition(    // OPEN-3: Drag-End → normalisierte 0..1-Koordinaten
            val portrait: Boolean,
            val x: Float,
            val y: Float,
        ) : OverlayAction()
        object MarkOverlayOnboardingShown : OverlayAction()    // Spec 3 GAP-2
        object DismissOverlayOnboarding : OverlayAction()       // Spec 3 GAP-2
        <!-- FIX: Issue 1.1.2 – SetUserPrefersWidget für OverlayModule.onCrossModuleStateChange-Cascade -->
        data class SetUserPrefersWidget(val prefers: Boolean) : OverlayAction()
        <!-- FIX: Issue 3.1.7 (User-Decision Option A) – Suppress-Bit Cascade -->
        /** Vom CloseOverlay-Cascade gesetzt; verhindert Auto-Reopen für die laufende Session. */
        object SuppressAutoOverlayUntilNextSession : OverlayAction()
        <!-- FIX: Issue PENDING-3 / Spec-3 Reducer Simplified – OverlayAction.ResetSuppressBit -->
        /**
         * Reset-Pendant zu [SuppressAutoOverlayUntilNextSession]. Vom Cross-Module-Observer
         * des **RecordingModule** beim Session-Start-Boundary `Idle → Preparing` emittiert
         * (Spec 1 §15.2 + §15.1.x Coupling-Matrix). Zentralisiert das Bit-Clearing in EINEM
         * Reducer-Arm (SRP) — frühere Plan-Versionen lösten den Reset implizit über
         * `SetUserPrefersWidget`-Cascade im OverlayModule.onCrossModuleStateChange aus,
         * was Doppel-Eigentum + leise Semantik-Drift erzeugte (siehe Spec 3 §4.8 Vorher).
         *
         * Idempotent: Reducer returnt `TransitionResult` auch wenn das Bit bereits `false`
         * ist (kein null → kein `DispatchOutcome.Rejected("reducer-null")`). Eine neue
         * Session ist niemals "rejected".
         *
         * **Bewusst `object` (Kotlin-Singleton), nicht `data class`:** Die Action trägt
         * keinen Payload — der Reset ist eine reine Trigger-Signalisierung. Singleton-
         * Identity (`===`-Vergleich) ist im Sealed-Leaves-Routing (Spec 1 §4.3) optimal,
         * weil `moduleByLeafClass[ResetSuppressBit::class]` direkt das Modul liefert und
         * Test-Assertions `assertEquals(Action.OverlayAction.ResetSuppressBit, ...)` ohne
         * `equals/hashCode`-Boilerplate funktionieren. Naming-Konsistenz mit anderen
         * Payload-losen OverlayActions (`MarkOverlayOnboardingShown`,
         * `DismissOverlayOnboarding`, `SuppressAutoOverlayUntilNextSession`,
         * `RequestOverlayPermission` — alle `object`).
         */
        object ResetSuppressBit : OverlayAction()
        <!-- FIX: Issue 3.1.3 (User-Decision Option A) – Permission-Achse -->
        /** Vom OverlayPermissionObserver — Permission-Status hat sich geändert. */
        data class OnOverlayPermissionChanged(val granted: Boolean) : OverlayAction()
        <!-- FIX: Issue 3.1.3 – Settings-Deep-Link Notification-Action -->
        object RequestOverlayPermission : OverlayAction()
    }

    // ─── Feature-Toggles (FeatureToggleModule) ───
    sealed class FeatureToggleAction : Action() {
        object ToggleRewording : FeatureToggleAction()
        object ToggleAutoFormatting : FeatureToggleAction()
        object ToggleInstantOutput : FeatureToggleAction()
        object ToggleAutoEnter : FeatureToggleAction()
        object ToggleVibration : FeatureToggleAction()
    }

    // ─── PendingSessions-Achse (PendingSessionsModule) ───
    sealed class PendingSessionsAction : Action() {
        data class Refresh(val sessions: List<PendingSession>) : PendingSessionsAction()
        <!-- FIX: Issue 2.1.19 / R.15 – sessionId String -->
        data class Dismiss(val sessionId: String) : PendingSessionsAction()
    }

    <!-- FIX: Issue 1.1.5 / R.5 – ContentArea-SetAction lebt unverändert in LayoutAction (LayoutState ist Container) -->
    // (LayoutAction.SetContentArea — siehe oben — schreibt jetzt in `state.layout.contentArea` über LayoutModule.)

    // ─── Tastatur-Eingaben (kein eigenes Modul — direkt im IME-Service ausgeführt) ───
    sealed class KeyboardInputAction : Action() {
        object Backspace : KeyboardInputAction()
        object EnterKey : KeyboardInputAction()
        object SpaceKey : KeyboardInputAction()
        data class CopyToClipboard(val text: String) : KeyboardInputAction()
    }

    // ─── Interruption-Achse (Phase 2 — InterruptionModule) ───
    sealed class InterruptionAction : Action() {
        data class PhoneCallStateChanged(val incoming: Boolean) : InterruptionAction()
        data class HeadsetPlugChanged(val plugged: Boolean) : InterruptionAction()
        data class ScreenStateChanged(val awake: Boolean) : InterruptionAction()
    }
}
```

**Wichtige Eigenschaften der Hierarchie:**

- **Type-safe Routing**: Jedes Modul beansprucht genau eine `actionClass: KClass<A>` (z.B. `Action.RecordingAction::class`). Der Orchestrator routet via `KClass`-Lookup type-safe.
- **Compile-Time-Exhaustivität**: Reducer-Methoden mit `when (action) {}` über die innere sealed class sind exhaustive — Compiler erzwingt Vollständigkeit.
- **Single Source of Truth für Action-Liste**: jede Action lebt nur EINMAL in der sealed-class. LocalBinder hat keine Forwarding-Methoden mehr (F-8).
- **OCP**: neue Action = neue Variante in der entsprechenden inneren sealed-class. Andere Module unberührt.

---

## §4 KeyboardLayoutManager — API

```kotlin
class KeyboardLayoutManager(
    private val scope: CoroutineScope,
    private val onAction: (Action) -> Unit,
) {
    private var activeBackend: RenderBackend? = null
    private var currentState: DictateUiState? = null

    fun attachBackend(backend: RenderBackend) {
        activeBackend?.detach()
        activeBackend = backend
        backend.attach(onAction)
        currentState?.let { state -> render(state, computeLayoutMode(state)) }
    }

    fun detachBackend() {
        activeBackend?.detach()
        activeBackend = null
    }

    fun onStateChanged(state: DictateUiState) {
        currentState = state
        val mode = computeLayoutMode(state)
        render(state, mode)
    }

    private fun computeLayoutMode(state: DictateUiState): LayoutMode = when (state.viewMode) {
        ViewMode.KEYBOARD -> LayoutCatalog.forKeyboard(state)
        ViewMode.WIDGET, ViewMode.HOVER -> LayoutCatalog.OVERLAY_5BUTTON
    }

    private fun render(state: DictateUiState, mode: LayoutMode) {
        activeBackend?.render(state, mode)
    }
}
```

**Zentrale Idee:** der Manager macht keine Visibility-Logik selbst — er delegiert an den `LayoutCatalog`, der die richtige `LayoutMode`-Instanz auswählt, und an das Backend, das die konkrete View-Mutation durchführt.

<!-- FIX: Issue 2.1.15 (User-Decision Option A+B) – KeyboardLayoutManager ↔ Spec-1-LayoutModule Beziehungs-Section -->
### §4.1 KeyboardLayoutManager ↔ LayoutModule (Spec 1 §15.1) — Beziehung

| Komponente | Wer | Verantwortung |
|------------|-----|---------------|
| `LayoutModule` (Spec 1 §15.1, Modul-Inventar #4) | **State-Owner** | Hält die `LayoutState`-Achse (`contentArea` + 3 Booleans). Schreibt durch `Action.LayoutAction.*` (z.B. `SetContentArea`, `ToggleSingleRowMode`). Reducer ist Pure Function, kein View-Wissen. |
| `KeyboardLayoutManager` (Spec 2 §4) | **View-Renderer** | Konsumiert `state.layout` (read-only) und mappt es per `computeLayoutMode(state)` auf eine konkrete `LayoutMode`-Instanz. Kein State-Owner, kein Reducer. |
| `RenderBackend` (Spec 2 §5) | **Property-Setter** | Wendet die `LayoutMode`-Eigenschaften auf konkrete Android-Views an. Drei Implementierungen: `ImeViewBackend` (Hauptpfad), `ContentAreaController` (zweites RenderBackend für Container-Visibility, R.10), `OverlayBackend` (Spec 3 §4). |

**Vertrag:** Mutationen an `state.layout` gehen **immer** durch `Action.LayoutAction.*` →
`LayoutModule.reduce` (in Spec 1 §15.1). Der `KeyboardLayoutManager` ruft niemals direkt
`store.update`. Damit bleibt die Single-Source-of-Truth-Regel intakt.

**ContentAreaController als zweites RenderBackend (R.10 + 2.1.15 Option B):** Container-
Visibility (`mainButtonsCl` / `qwertz_container` / `emojiPicker_container`) wird nicht in
ButtonSlot-Resolvern modelliert (das wäre conceptual missfit), sondern als eigene
`RenderBackend`-Implementierung, die parallel zu `ImeViewBackend` reagiert:

```kotlin
class ContentAreaController(views: KeyboardViews) : RenderBackend {
    override fun render(state: DictateUiState, mode: LayoutMode) {
        views.mainButtonsCl.visibility = if (state.layout.contentArea == ContentArea.MAIN_BUTTONS) View.VISIBLE else View.GONE
        views.qwertzContainer.visibility = if (state.layout.contentArea == ContentArea.QWERTZ) View.VISIBLE else View.GONE
        views.emojiPickerContainer.visibility = if (state.layout.contentArea == ContentArea.EMOJI_PICKER) View.VISIBLE else View.GONE
    }
}
```

Der `KeyboardLayoutManager` hält **eine Liste** aktiver Backends statt eines einzigen
`activeBackend`-Felds; bei Render-Tick werden alle aufgerufen.

---

## §5 RenderBackend-Interface

```kotlin
interface RenderBackend {
    fun attach(onAction: (Action) -> Unit)
    fun detach()
    fun render(state: DictateUiState, mode: LayoutMode)
}
```

Das Interface ist absichtlich minimal — Backend-spezifische Details bleiben innerhalb der Implementierung.

---

## §5.1 Geteilter Slot-Apply-Helper (F-7 / DRY)

> **Architektur-Korrektur F-7 (Iteration 2026-05-08):** `ImeViewBackend.applySlotProperties`
> und `OverlayBackend.applySlots` waren in früheren Spec-Versionen als
> separate Methoden mit identischer Sieben-Zeilen-Logik dupliziert.
> Beide werden jetzt zu einer Top-Level-Funktion `applySlotToView`
> konsolidiert, die beide Backends aufrufen — keine Drift-Quelle, klare
> SSOT für die Slot→View-Property-Mapping-Regel.

```kotlin
// Datei: app/src/main/java/net/devemperor/dictate/keyboard/render/SlotRenderer.kt
package net.devemperor.dictate.keyboard.render

/**
 * Setzt Slot-Properties auf einer View.
 *
 * Der einzige Code-Pfad, der `ButtonSlot`-Resolver in Android-View-Properties
 * übersetzt — von `ImeViewBackend` und `OverlayBackend` gleichermaßen genutzt.
 * Wenn eine neue Slot-Eigenschaft hinzukommt (z.B. `contentDescription`,
 * `tint`), wird sie hier ergänzt; beide Backends profitieren automatisch.
 *
 * Click-Listener werden NICHT hier verdrahtet — diese sind backend-spezifisch:
 * im IME einmalig in `wireStaticHandlers` (state-snapshot-driven), im Overlay
 * pro Render (kein Drag-Routing-Konflikt).
 *
 * @return true wenn der Slot vorhanden + sichtbar ist (für optionale Caller-Logik), false sonst
 */
fun applySlotToView(
    slot: ButtonSlot,
    view: View,
    state: DictateUiState,
    ctx: Context,
): Boolean {
    val visible = slot.visibilityPredicate(state)
    view.visibility = if (visible) View.VISIBLE else View.GONE
    view.isEnabled = slot.enabledResolver(state)
    view.alpha = slot.alphaResolver(state)
    if (view is MaterialButton) {
        slot.iconResolver(state)?.let { iconRes ->
            view.icon = ContextCompat.getDrawable(ctx, iconRes)
        }
        slot.textResolver(state)?.let { text ->
            view.text = text
        }
    }
    return visible
}
```

**SOLID-Verifikation:**
- **SRP** — pure Slot→View-Mapping. Kein Click-Routing, kein Backend-Wissen, kein State-Mutation.
- **OCP** — neue Slot-Property = neuer Setter-Block hier; alle Backends profitieren ohne eigene Anpassung.
- **DIP** — hängt nur von `ButtonSlot` (Daten-Schicht), `View`/`Context` (Android-API) und `MaterialButton` (Library-Klasse). Keine Backend-Konkretisierung.

**DRY-Beweis:** zwei Backends × sieben identische View-Property-Sets = 14 zu duplizierende Zeilen. Mit Helper: ein Aufruf pro Backend, eine Definition für beide. Wenn Spec 3 §4.2 später eine `contentDescription` für Accessibility braucht, fügt sie der Helper hinzu — Spec 2 erbt sie automatisch.

---

## §6 ImeViewBackend (KEYBOARD-Modus)

```kotlin
class ImeViewBackend(
    private val rootView: View,                          // MotionLayout-Root
    private val ctx: Context,
    private val inputConnectionProvider: () -> InputConnection?,
    private val keyPressAnimator: KeyPressAnimator,
    private val recordingAnimationController: RecordingAnimationController,
    private val accentColorProvider: () -> Int,
    private val onVibrate: () -> Unit,
) : RenderBackend {

    private val motionLayout = rootView as MotionLayout

    private val buttonViews: Map<LogicalButtonId, View> = mapOf(
        LogicalButtonId.RECORD        to rootView.findViewById(R.id.record_btn),
        LogicalButtonId.RESEND        to rootView.findViewById(R.id.resend_btn),
        LogicalButtonId.BACKSPACE     to rootView.findViewById(R.id.backspace_btn),
        LogicalButtonId.AUDIO_FOCUS   to rootView.findViewById(R.id.audio_focus_btn),
        LogicalButtonId.WIDGET_TOGGLE to rootView.findViewById(R.id.widget_toggle_btn),  // FIX: Issue 3.0.12 – Phase-1-1.0.2-Followup (LogicalButtonId.WIDGET_TOGGLE in §3.1 ergänzt, View-Mapping nachgepflegt — vermeidet Silent-Skip im Render-Loop)
        LogicalButtonId.TRASH         to rootView.findViewById(R.id.trash_btn),
        LogicalButtonId.SPACE         to rootView.findViewById(R.id.space_btn),
        LogicalButtonId.PAUSE         to rootView.findViewById(R.id.pause_btn),
        LogicalButtonId.ENTER         to rootView.findViewById(R.id.enter_btn),
    )

    /** Aktueller DictateUiState-Snapshot für die Click-Listener — single source. */
    private var stateRef: DictateUiState? = null
    private var modeRef: LayoutMode? = null

    private var onAction: ((Action) -> Unit)? = null

    <!-- FIX: Issue 2.1.18 / R.14 – firstRender-Flag verhindert 250ms-Initial-Animation -->
    /**
     * MotionLayout-Initial-State ist immer der erste ConstraintSet — beim Re-Inflate
     * (Rotation, Theme-Wechsel) muss der erste Render `jumpToState` rufen, sonst
     * sieht der User eine 250ms-Animation vom Initial-State zum eigentlichen Mode.
     */
    private var firstRender: Boolean = true

    override fun attach(onAction: (Action) -> Unit) {
        this.onAction = onAction
        wireStaticHandlers()  // einmalig — Touch-Handler, Vibrations-Wrapper etc.
    }

    override fun detach() {
        this.onAction = null
        // <!-- FIX: Issue 2.1.18 / R.14 – Reset firstRender für view-recreate-Semantik -->
        firstRender = true
        // Click-Listener werden NICHT abgemeldet — sie referenzieren `stateRef`,
        // das nach detach null wird; ein versehentlicher Klick auf einen
        // detached Backend ergibt dann ein No-Op (Resolver returnt null oder onAction == null).
    }

    override fun render(state: DictateUiState, mode: LayoutMode) {
        require(mode.backend == BackendType.IME_VIEW)
        stateRef = state
        modeRef = mode

        // 1. MotionLayout-Transition — Scene-ID kommt jetzt aus mode.sceneStateId (R.12 / OCP).
        //    `toSceneStateId()`-Extension entfällt.
        mode.sceneStateId?.let { sceneId ->
            if (firstRender || !state.layout.animationsEnabled) {
                motionLayout.jumpToState(sceneId)
            } else {
                motionLayout.transitionToState(sceneId)
            }
        }
        firstRender = false

        // 2. Pro Slot: Visibility/Icon/Text/Enabled/Alpha über den geteilten Helper
        //    `applySlotToView` (siehe §5.1 — F-7). Click-Listener sind einmal
        //    in wireStaticHandlers verdrahtet (siehe L8).
        //
        // FIX: Issue 3.0.12 – Silent-Skip-Schutz: ein fehlendes View-Mapping
        // (z.B. neu eingeführter `LogicalButtonId.WIDGET_TOGGLE` ohne
        // `R.id.widget_toggle_btn` im XML) muss zur Build-/Run-Time auffallen,
        // nicht zur Laufzeit verschwinden. `error(...)` statt `return@forEach`.
        mode.rows.flatMap { it.slots }.forEach { slot ->
            val view = buttonViews[slot.logicalId]
                ?: error("No view registered for ${slot.logicalId} in ImeViewBackend.buttonViews")
            applySlotToView(slot, view, state, ctx)
        }

        // 3. Recording-Animation reaktiv (BorderGlow + PulseLayout) — siehe §11.5
        recordingAnimationController.onState(state)
    }

    /**
     * Click-/Long-Click- + Touch-Handler werden einmal verdrahtet.
     * Lambdas referenzieren `stateRef`/`modeRef` (Felder), nicht render-Argumente
     * — so bleibt nur ein Lambda pro Button im Memory, statt eines pro Render-Tick
     * (siehe L8 / §11.6).
     */
<!-- FIX: Issue 1.1.4 + 2.1.7 / R.3 – Click-Listener nutzt nullable Resolver-Result statt Action.NoOp -->
    private fun wireStaticHandlers() {
        buttonViews.forEach { (id, view) ->
            view.setOnClickListener {
                onVibrate()
                val s = stateRef ?: return@setOnClickListener
                val slot = currentSlot(id) ?: return@setOnClickListener
                slot.actionResolver(s)?.let { onAction?.invoke(it) }
            }
            keyPressAnimator.applyPressAnimation(view)
        }

        // Special long-click handlers — ohne Action: einfach `true` zurückgeben (Long-Press konsumiert)
        buttonViews[LogicalButtonId.RECORD]?.setOnLongClickListener {
            onVibrate(); true   // konkrete Long-Action via Slot, sofern vorhanden
        }
        buttonViews[LogicalButtonId.RESEND]?.setOnLongClickListener {
            onVibrate(); onAction?.invoke(Action.ResendAction.ResendLastAudioLong); true
        }
        buttonViews[LogicalButtonId.BACKSPACE]?.setOnLongClickListener { true }

        // Special touch-handlers (state-frei, einmalig verdrahtet — siehe L9 / §11.7)
        buttonViews[LogicalButtonId.SPACE]?.setOnTouchListener(buildSpaceTouchHandler())
        buttonViews[LogicalButtonId.BACKSPACE]?.setOnTouchListener(buildBackspaceSwipeHandler())
        buttonViews[LogicalButtonId.ENTER]?.setOnTouchListener(buildEnterOverlayHandler())
    }

    private fun currentSlot(id: LogicalButtonId): ButtonSlot? =
        modeRef?.rows?.flatMap { it.slots }?.firstOrNull { it.logicalId == id }

    private fun buildSpaceTouchHandler(): View.OnTouchListener { /* siehe §11.7 */ TODO() }
    private fun buildBackspaceSwipeHandler(): View.OnTouchListener { /* siehe §11.7 */ TODO() }
    private fun buildEnterOverlayHandler(): View.OnTouchListener { /* siehe §11.7 */ TODO() }
}

private fun LayoutModeId.toSceneStateId(): Int = when (this) {
    LayoutModeId.KEYBOARD_TWO_ROW              -> R.id.two_row_state
    LayoutModeId.KEYBOARD_SINGLE_ROW           -> R.id.single_row_state
    LayoutModeId.KEYBOARD_TWO_ROW_SEND_MODE    -> R.id.two_row_send_mode_state
    LayoutModeId.KEYBOARD_SINGLE_ROW_SEND_MODE -> R.id.single_row_send_mode_state
    LayoutModeId.KEYBOARD_REPROCESS_STAGING    -> R.id.reprocess_staging_state
    LayoutModeId.OVERLAY_5BUTTON               -> error("Overlay mode is not handled by ImeViewBackend")
}
```

---

## §7 MotionScene-XML — vollständig

### §7.1 Datei `res/xml/motion_scene_keyboard.xml`

Vollständige Scene mit allen 5 KEYBOARD-States. `two_row_state` ist Basis-Definition; alle anderen erben via `motion:deriveConstraintsFrom` und überschreiben nur, was sich tatsächlich unterscheidet.

```xml
<?xml version="1.0" encoding="utf-8"?>
<MotionScene xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:motion="http://schemas.android.com/apk/res-auto">

    <!-- ════════════════════════════════════════════════════════════
         BASE STATE: Two-Row (default)
         action_row + input_row sind logisch erhalten, aber alle 9 Buttons
         sind direkte Children des MotionLayout-Roots. Die "Reihen" sind
         über Constraint-Chains realisiert, nicht über verschachtelte Container.
         ════════════════════════════════════════════════════════════ -->
    <ConstraintSet android:id="@+id/two_row_state">

        <!-- ── Reihe 1 (action_row): record_pulse — resend — backspace ── -->
        <Constraint
            android:id="@+id/record_pulse_layout"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginEnd="8dp"
            motion:layout_constraintTop_toTopOf="parent"
            motion:layout_constraintStart_toStartOf="parent"
            motion:layout_constraintEnd_toStartOf="@+id/resend_btn" />

        <Constraint
            android:id="@+id/resend_btn"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginEnd="8dp"
            motion:layout_constraintTop_toTopOf="@+id/record_pulse_layout"
            motion:layout_constraintBottom_toBottomOf="@+id/record_pulse_layout"
            motion:layout_constraintStart_toEndOf="@+id/record_pulse_layout"
            motion:layout_constraintEnd_toStartOf="@+id/backspace_btn">
            <PropertySet motion:visibilityMode="ignore" />
        </Constraint>

        <Constraint
            android:id="@+id/backspace_btn"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            motion:layout_constraintTop_toTopOf="@+id/record_pulse_layout"
            motion:layout_constraintBottom_toBottomOf="@+id/record_pulse_layout"
            motion:layout_constraintStart_toEndOf="@+id/resend_btn"
            motion:layout_constraintEnd_toEndOf="parent" />

        <!-- audio_focus_btn lebt in Two-Row neben backspace, ist aber GONE
             (Predicate `false` im Two-Row-LayoutMode). Position bleibt
             definiert für deriveConstraintsFrom-Erben. -->
        <Constraint
            android:id="@+id/audio_focus_btn"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            motion:layout_constraintTop_toTopOf="@+id/record_pulse_layout"
            motion:layout_constraintBottom_toBottomOf="@+id/record_pulse_layout"
            motion:layout_constraintEnd_toEndOf="parent">
            <PropertySet motion:visibilityMode="ignore" />
        </Constraint>

        <!-- ── Reihe 2 (input_row): trash — space — pause — enter ── -->
        <Constraint
            android:id="@+id/trash_btn"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            motion:layout_constraintTop_toBottomOf="@+id/record_pulse_layout"
            motion:layout_constraintStart_toStartOf="parent"
            motion:layout_constraintEnd_toStartOf="@+id/space_btn">
            <PropertySet motion:visibilityMode="ignore" />
        </Constraint>

        <Constraint
            android:id="@+id/space_btn"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:layout_marginEnd="8dp"
            motion:layout_constraintTop_toTopOf="@+id/trash_btn"
            motion:layout_constraintBottom_toBottomOf="@+id/trash_btn"
            motion:layout_constraintStart_toEndOf="@+id/trash_btn"
            motion:layout_constraintEnd_toStartOf="@+id/pause_btn"
            motion:layout_goneMarginStart="0dp" />

        <Constraint
            android:id="@+id/pause_btn"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginEnd="8dp"
            motion:layout_constraintTop_toTopOf="@+id/trash_btn"
            motion:layout_constraintBottom_toBottomOf="@+id/trash_btn"
            motion:layout_constraintStart_toEndOf="@+id/space_btn"
            motion:layout_constraintEnd_toStartOf="@+id/enter_btn">
            <PropertySet motion:visibilityMode="ignore" />
        </Constraint>

        <Constraint
            android:id="@+id/enter_btn"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            motion:layout_constraintTop_toTopOf="@+id/trash_btn"
            motion:layout_constraintBottom_toBottomOf="@+id/trash_btn"
            motion:layout_constraintStart_toEndOf="@+id/pause_btn"
            motion:layout_constraintEnd_toEndOf="parent" />

    </ConstraintSet>

    <!-- ════════════════════════════════════════════════════════════
         SINGLE_ROW STATE: alle 8 Buttons in einer Reihe (entspricht heutigem
         buildSingleRowConstraintSet, aber deklarativ statt programmatisch).
         Chain-Reihenfolge: trash — record_pulse — space — pause — backspace
         — enter — resend — audio_focus.
         ════════════════════════════════════════════════════════════ -->
    <ConstraintSet
        android:id="@+id/single_row_state"
        motion:deriveConstraintsFrom="@+id/two_row_state">

        <!-- trash_btn: jetzt erste Position links, oben/unten an parent -->
        <Constraint
            android:id="@+id/trash_btn"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="4dp"
            android:layout_marginEnd="4dp"
            motion:layout_constraintTop_toTopOf="parent"
            motion:layout_constraintBottom_toBottomOf="parent"
            motion:layout_constraintStart_toStartOf="parent"
            motion:layout_constraintEnd_toStartOf="@+id/record_pulse_layout">
            <PropertySet motion:visibilityMode="ignore" />
        </Constraint>

        <Constraint
            android:id="@+id/record_pulse_layout"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="4dp"
            android:layout_marginEnd="4dp"
            motion:layout_constraintTop_toTopOf="parent"
            motion:layout_constraintBottom_toBottomOf="parent"
            motion:layout_constraintStart_toEndOf="@+id/trash_btn"
            motion:layout_constraintEnd_toStartOf="@+id/space_btn" />

        <Constraint
            android:id="@+id/space_btn"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginStart="4dp"
            android:layout_marginEnd="4dp"
            motion:layout_constraintTop_toTopOf="parent"
            motion:layout_constraintBottom_toBottomOf="parent"
            motion:layout_constraintStart_toEndOf="@+id/record_pulse_layout"
            motion:layout_constraintEnd_toStartOf="@+id/pause_btn" />

        <Constraint
            android:id="@+id/pause_btn"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="4dp"
            android:layout_marginEnd="4dp"
            motion:layout_constraintTop_toTopOf="parent"
            motion:layout_constraintBottom_toBottomOf="parent"
            motion:layout_constraintStart_toEndOf="@+id/space_btn"
            motion:layout_constraintEnd_toStartOf="@+id/backspace_btn">
            <PropertySet motion:visibilityMode="ignore" />
        </Constraint>

        <Constraint
            android:id="@+id/backspace_btn"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="4dp"
            android:layout_marginEnd="4dp"
            motion:layout_constraintTop_toTopOf="parent"
            motion:layout_constraintBottom_toBottomOf="parent"
            motion:layout_constraintStart_toEndOf="@+id/pause_btn"
            motion:layout_constraintEnd_toStartOf="@+id/enter_btn" />

        <Constraint
            android:id="@+id/enter_btn"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="4dp"
            android:layout_marginEnd="4dp"
            motion:layout_constraintTop_toTopOf="parent"
            motion:layout_constraintBottom_toBottomOf="parent"
            motion:layout_constraintStart_toEndOf="@+id/backspace_btn"
            motion:layout_constraintEnd_toStartOf="@+id/resend_btn" />

        <Constraint
            android:id="@+id/resend_btn"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="4dp"
            android:layout_marginEnd="4dp"
            motion:layout_constraintTop_toTopOf="parent"
            motion:layout_constraintBottom_toBottomOf="parent"
            motion:layout_constraintStart_toEndOf="@+id/enter_btn"
            motion:layout_constraintEnd_toStartOf="@+id/audio_focus_btn">
            <PropertySet motion:visibilityMode="ignore" />
        </Constraint>

        <Constraint
            android:id="@+id/audio_focus_btn"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="4dp"
            android:layout_marginEnd="4dp"
            motion:layout_constraintTop_toTopOf="parent"
            motion:layout_constraintBottom_toBottomOf="parent"
            motion:layout_constraintStart_toEndOf="@+id/resend_btn"
            motion:layout_constraintEnd_toEndOf="parent">
            <PropertySet motion:visibilityMode="ignore" />
        </Constraint>

    </ConstraintSet>

    <!-- ════════════════════════════════════════════════════════════
         SEND-MODE STATES: identische Position wie Two-Row / Single-Row,
         aber record_btn wird in Send-Mode breiter (FillRemaining bleibt,
         aber resend_btn ist GONE → record_pulse erweitert sich automatisch
         dank Chain-Resolution). Keine Position-Overrides nötig — Catalog
         steuert Visibility + Text via Resolver.

         Diese States existieren nur, damit der MotionLayout-Animation-Pfad
         beim Übergang Idle → Pipeline einen sauberen Endpunkt hat
         (sonst würde "transition zu sich selbst" no-op sein).
         ════════════════════════════════════════════════════════════ -->
    <ConstraintSet
        android:id="@+id/two_row_send_mode_state"
        motion:deriveConstraintsFrom="@+id/two_row_state" />

    <ConstraintSet
        android:id="@+id/single_row_send_mode_state"
        motion:deriveConstraintsFrom="@+id/single_row_state" />

    <!-- ════════════════════════════════════════════════════════════
         REPROCESS_STAGING STATE: Layout-Position wie Two-Row,
         Visibility/Text via Catalog (record_btn = "Audio X:YY · Senden",
         pause_btn = visible+disabled+alpha 0.4, trash_btn = "Cancel-Staging").
         ════════════════════════════════════════════════════════════ -->
    <ConstraintSet
        android:id="@+id/reprocess_staging_state"
        motion:deriveConstraintsFrom="@+id/two_row_state" />

    <!-- ════════════════════════════════════════════════════════════
         TRANSITIONS — alle 250ms, AutoTransition-Default (fade + move).
         Wir definieren nur Transitions zwischen Geschwistern, die der User
         tatsächlich zwischen einander toggelt; alle anderen Übergänge
         laufen über den Default-Auto-Transition-Pfad von MotionLayout.
         ════════════════════════════════════════════════════════════ -->
    <Transition
        motion:constraintSetStart="@+id/two_row_state"
        motion:constraintSetEnd="@+id/single_row_state"
        motion:duration="250" />

    <Transition
        motion:constraintSetStart="@+id/two_row_state"
        motion:constraintSetEnd="@+id/two_row_send_mode_state"
        motion:duration="200" />

    <Transition
        motion:constraintSetStart="@+id/single_row_state"
        motion:constraintSetEnd="@+id/single_row_send_mode_state"
        motion:duration="200" />

    <Transition
        motion:constraintSetStart="@+id/two_row_state"
        motion:constraintSetEnd="@+id/reprocess_staging_state"
        motion:duration="200" />

    <Transition
        motion:constraintSetStart="@+id/single_row_state"
        motion:constraintSetEnd="@+id/reprocess_staging_state"
        motion:duration="200" />

</MotionScene>
```

### §7.2 Refactored `activity_dictate_keyboard_view.xml` — `main_buttons_cl`-Bereich

Vorher (heute, Z. 12-172): `LinearLayout` → `ConstraintLayout action_row` + `ConstraintLayout input_row` mit ihren Buttons als verschachtelte Children.

Nachher: ein einziges `MotionLayout` mit allen 9 Buttons als direkte Children. Position-Constraints liegen NICHT mehr im Layout-XML, sondern in der Scene (Inflation startet im `@+id/two_row_state`-ConstraintSet).

```xml
<!-- ════════════════════════════════════════════════════════════
     ERSETZT die Z. 12-172 des heutigen activity_dictate_keyboard_view.xml.
     Der Rest der Datei (info_cl, edit_buttons_keyboard_ll, prompts_keyboard_cl,
     emoji_picker_cl, qwertz_keyboard_container, overlay_characters_ll) bleibt
     unverändert.
     ════════════════════════════════════════════════════════════ -->
<androidx.constraintlayout.motion.widget.MotionLayout
    android:id="@+id/main_buttons_cl"
    android:layout_width="0dp"
    android:layout_height="wrap_content"
    android:paddingStart="72dp"
    android:paddingEnd="16dp"
    android:clipChildren="false"
    android:clipToPadding="false"
    app:layoutDescription="@xml/motion_scene_keyboard"
    app:layout_constraintTop_toBottomOf="@id/edit_buttons_keyboard_ll"
    app:layout_constraintStart_toStartOf="parent"
    app:layout_constraintEnd_toEndOf="parent">

    <!-- ── PulseLayout-Wrapper bleibt erhalten (L7 / §11.3) ── -->
    <net.devemperor.dictate.widget.PulseLayout
        android:id="@+id/record_pulse_layout"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:pulseCount="3"
        app:pulseDuration="2000"
        app:pulseStartAlpha="0.3"
        app:pulseMaxRadiusFactor="1.4"
        app:pulseStyle="fill">

        <com.google.android.material.button.MaterialButton
            android:id="@+id/record_btn"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/dictate_record"
            android:textSize="14sp"
            android:maxLines="1" />
    </net.devemperor.dictate.widget.PulseLayout>

    <com.google.android.material.button.MaterialButton
        android:id="@+id/resend_btn"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:foreground="@drawable/ic_outline_change_circle_24"
        android:foregroundGravity="center"
        android:minWidth="0dp"
        android:visibility="gone"
        tools:visibility="visible" />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/backspace_btn"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:foreground="@drawable/ic_baseline_keyboard_backspace_24"
        android:foregroundGravity="center"
        android:minWidth="56dp" />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/audio_focus_btn"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:foreground="@drawable/ic_baseline_volume_off_24"
        android:foregroundGravity="center"
        android:minWidth="56dp"
        android:visibility="gone" />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/trash_btn"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:foreground="@drawable/ic_baseline_delete_24"
        android:foregroundGravity="center"
        android:minWidth="0dp"
        android:visibility="gone"
        tools:visibility="visible" />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/space_btn"
        android:layout_width="0dp"
        android:layout_height="wrap_content" />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/pause_btn"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:foreground="@drawable/ic_baseline_pause_24"
        android:foregroundGravity="center"
        android:minWidth="0dp"
        android:visibility="gone"
        tools:visibility="visible" />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/enter_btn"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:foreground="@drawable/ic_baseline_subdirectory_arrow_left_24"
        android:foregroundGravity="center"
        android:minWidth="56dp" />

</androidx.constraintlayout.motion.widget.MotionLayout>
```

**Wichtig:** das Layout-XML enthält **keine** `app:layout_constraint…`-Attribute mehr auf den Buttons. Diese leben ausschließlich in der MotionScene. Der einzige Constraint, der im Layout-XML stehenbleibt, ist die Position des MotionLayout selbst innerhalb des Wurzel-`ConstraintLayout` (Z. 21-23 des heutigen XML).

### §7.3 VISIBILITY_MODE_IGNORE — Finalisierung

<!-- FIX: Issue 2.1.14 / R.11 (User-Decision Option A) – alle 9 Buttons bekommen visibilityMode="ignore" -->
**Catalog ist alleiniger Visibility-Owner.** Damit MotionLayout die Visibility-Mutationen des
LayoutCatalog nicht überstimmt, hat **jeder der 9 Buttons** `motion:visibilityMode="ignore"`
in der MotionScene und im Layout-XML — auch die Buttons, die heute "immer visible" sind. Die
Lint-Regel "wenn predicate-konstant, dann ohne Ignore" wird zugunsten eindeutiger Vertrags-
Semantik aufgegeben.

| Button | `visibilityMode="ignore"` |
|--------|---------------------------|
| record_btn          | **JA** |
| resend_btn          | **JA** |
| backspace_btn       | **JA** |
| audio_focus_btn     | **JA** |
| widget_toggle_btn   | **JA** |
| trash_btn           | **JA** |
| space_btn           | **JA** |
| pause_btn           | **JA** |
| enter_btn           | **JA** |

→ 9 Buttons × 1 XML-Attribut = 9 Edits in den Scene-XMLs (Two-Row, Single-Row, Send-Modes).
Catalog ist die einzige Visibility-Quelle; MotionScene macht ausschließlich Position.

---

## §8 LayoutCatalog: vollständige Definitionen

### §8.1 KEYBOARD_TWO_ROW

```kotlin
val KEYBOARD_TWO_ROW = LayoutMode(
    id = LayoutModeId.KEYBOARD_TWO_ROW,
    backend = BackendType.IME_VIEW,
    rows = listOf(
        // Row 1: action_row-Bereich
        RowDescriptor(slots = listOf(
            ButtonSlot(LogicalButtonId.RECORD, FillRemaining,
                visibilityPredicate = { true },
                textResolver = ::resolveRecordButtonText,
                enabledResolver = { state -> state.recording !is RecordingState.Preparing },
                actionResolver = ::resolveRecordAction),
            ButtonSlot(LogicalButtonId.RESEND, WrapContent,
                visibilityPredicate = ::predResendVisible,
                actionResolver = { Action.ResendAction.ResendLastAudio }),
            ButtonSlot(LogicalButtonId.BACKSPACE, WrapContent,
                visibilityPredicate = { true },
                actionResolver = { Action.KeyboardInputAction.Backspace }),
            ButtonSlot(LogicalButtonId.AUDIO_FOCUS, WrapContent,
                visibilityPredicate = { false },                       // nur Single-Row
                actionResolver = { Action.AudioAction.ToggleAudioFocusPref }),
        )),
        // Row 2: input_row-Bereich
        RowDescriptor(slots = listOf(
            ButtonSlot(LogicalButtonId.TRASH, WrapContent,
                visibilityPredicate = ::predTrashVisible,
                actionResolver = ::resolveTrashAction),
            ButtonSlot(LogicalButtonId.SPACE, FillRemaining,
                visibilityPredicate = { true },
                actionResolver = { Action.KeyboardInputAction.SpaceKey })  // FIX: Issue 3.0.5 – flache Action.SpaceKey → hierarchisch,
            ButtonSlot(LogicalButtonId.PAUSE, WrapContent,
                visibilityPredicate = ::predPauseVisible,
                enabledResolver = { it.recording.isActiveOrPaused },
                alphaResolver = { if (it.recording.isActiveOrPaused) 1f else 0.4f },
                iconResolver = ::resolvePauseIcon,
                actionResolver = ::resolvePauseAction),
            ButtonSlot(LogicalButtonId.ENTER, WrapContent,
                visibilityPredicate = { true },
                actionResolver = { Action.KeyboardInputAction.EnterKey }),
        )),
    ),
)
```

### §8.2 KEYBOARD_SINGLE_ROW

Eine Reihe mit allen 8 Buttons. Alle Predicates sind identisch zu Two-Row, **außer** `AUDIO_FOCUS`, das in Single-Row sichtbar ist.

```kotlin
val KEYBOARD_SINGLE_ROW = LayoutMode(
    id = LayoutModeId.KEYBOARD_SINGLE_ROW,
    backend = BackendType.IME_VIEW,
    rows = listOf(
        // Eine einzige Row — Reihenfolge entspricht der MotionScene-Chain
        RowDescriptor(slots = listOf(
            ButtonSlot(LogicalButtonId.TRASH, WrapContent,
                visibilityPredicate = ::predTrashVisible,
                actionResolver = ::resolveTrashAction),
            ButtonSlot(LogicalButtonId.RECORD, WrapContent,
                visibilityPredicate = { true },
                textResolver = ::resolveRecordButtonText,
                enabledResolver = { it.recording !is RecordingState.Preparing },
                actionResolver = ::resolveRecordAction),
            ButtonSlot(LogicalButtonId.SPACE, FillRemaining,
                visibilityPredicate = { true },
                actionResolver = { Action.KeyboardInputAction.SpaceKey })  // FIX: Issue 3.0.5 – flache Action.SpaceKey → hierarchisch,
            ButtonSlot(LogicalButtonId.PAUSE, WrapContent,
                visibilityPredicate = ::predPauseVisible,
                enabledResolver = { it.recording.isActiveOrPaused },
                alphaResolver = { if (it.recording.isActiveOrPaused) 1f else 0.4f },
                iconResolver = ::resolvePauseIcon,
                actionResolver = ::resolvePauseAction),
            ButtonSlot(LogicalButtonId.BACKSPACE, WrapContent,
                visibilityPredicate = { true },
                actionResolver = { Action.KeyboardInputAction.Backspace }),
            ButtonSlot(LogicalButtonId.ENTER, WrapContent,
                visibilityPredicate = { true },
                actionResolver = { Action.KeyboardInputAction.EnterKey }),
            ButtonSlot(LogicalButtonId.RESEND, WrapContent,
                visibilityPredicate = ::predResendVisible,
                actionResolver = { Action.ResendAction.ResendLastAudio }),
            ButtonSlot(LogicalButtonId.AUDIO_FOCUS, WrapContent,
                visibilityPredicate = { true },                        // ← einziger Unterschied zu Two-Row
                actionResolver = { Action.AudioAction.ToggleAudioFocusPref }),
        )),
    ),
)
```

### §8.3 KEYBOARD_TWO_ROW_SEND_MODE / KEYBOARD_SINGLE_ROW_SEND_MODE

Pipeline läuft (Preparing oder Running). `record_btn` = "Sende…" / "X/Y · 0:08", `pause_btn` GONE, `trash_btn` GONE, `resend_btn` GONE.

<!-- FIX: Issue 2.0.11 – Inline-Doku-Anker an hardcoded { false } für TRASH/PAUSE im SEND_MODE (load-bearing Bug-Fix) -->

> **Wichtige Architektur-Notiz (Bug-Fix-Verankerung):** Die `visibilityPredicate = { false }`
> für TRASH und PAUSE im SEND_MODE sind **hardcoded** und **dürfen nicht** durch
> `predTrashVisible(state)` / `predPauseVisible(state)` (zentrale Predicates aus §8.5)
> ersetzt werden. Begründung: bekannter User-Bug (Plan §1.1 #3 — "Send-Btn verdeckt
> im Send-Modus"). Der Catalog-Switch via `forKeyboard(state)` ist der eigentliche
> Bug-Eliminator — die zentrale Predicate `predTrashVisible(state)` liefert während
> des Active → Pipeline.Preparing-Tick-Übergangs noch `true` (`recording.isActive`),
> weil die Reducer-Reihenfolge nicht atomar ist. Ein DRY-Refactor "warum unterschiedlich
> zu Idle-Mode" würde den Bug reaktivieren. Siehe Test-Anker §14.2 UI-Test 4.

```kotlin
val KEYBOARD_TWO_ROW_SEND_MODE = LayoutMode(
    id = LayoutModeId.KEYBOARD_TWO_ROW_SEND_MODE,
    backend = BackendType.IME_VIEW,
    rows = listOf(
        RowDescriptor(slots = listOf(
            ButtonSlot(LogicalButtonId.RECORD, FillRemaining,
                visibilityPredicate = { true },
                textResolver = ::resolveRecordButtonTextPipeline,   // "Sende…" / "2/3 0:08"
                enabledResolver = { it.pipeline !is PipelineUiState.Preparing },
                actionResolver = ::resolveRecordActionPipeline),    // ToggleAutoEnter / NoOp
            ButtonSlot(LogicalButtonId.RESEND, WrapContent,
                visibilityPredicate = { false },
                actionResolver = { null }),    // R.3 / 1.1.4 – nullable Resolver
            ButtonSlot(LogicalButtonId.BACKSPACE, WrapContent,
                visibilityPredicate = { true },
                actionResolver = { Action.KeyboardInputAction.Backspace }),
            ButtonSlot(LogicalButtonId.AUDIO_FOCUS, WrapContent,
                visibilityPredicate = { false },
                actionResolver = { Action.AudioAction.ToggleAudioFocusPref }),
        )),
        RowDescriptor(slots = listOf(
            // Hardcoded { false } statt predTrashVisible — siehe Architektur-Notiz oben.
            // NICHT auf predTrashVisible umstellen ohne Plan-Iter (Bug §1.1 #3).
            ButtonSlot(LogicalButtonId.TRASH, WrapContent,
                visibilityPredicate = { false },                     // im Send-Mode NICHT sichtbar
                actionResolver = ::resolveTrashAction),
            ButtonSlot(LogicalButtonId.SPACE, FillRemaining,
                visibilityPredicate = { true },
                actionResolver = { Action.KeyboardInputAction.SpaceKey })  // FIX: Issue 3.0.5 – flache Action.SpaceKey → hierarchisch,
            // Hardcoded { false } statt predPauseVisible — siehe Architektur-Notiz oben.
            // Bug-Eliminator ist der Catalog-Switch, nicht die zentrale Predicate.
            ButtonSlot(LogicalButtonId.PAUSE, WrapContent,
                visibilityPredicate = { false },                     // GONE im Send-Mode
                actionResolver = { null }),    // R.3 / 1.1.4 – nullable Resolver
            ButtonSlot(LogicalButtonId.ENTER, WrapContent,
                visibilityPredicate = { true },
                actionResolver = { Action.KeyboardInputAction.EnterKey }),
        )),
    ),
)

val KEYBOARD_SINGLE_ROW_SEND_MODE = LayoutMode(
    id = LayoutModeId.KEYBOARD_SINGLE_ROW_SEND_MODE,
    backend = BackendType.IME_VIEW,
    rows = listOf(RowDescriptor(slots = listOf(
        // Hardcoded { false } für TRASH — siehe Architektur-Notiz oben (Bug §1.1 #3).
        ButtonSlot(LogicalButtonId.TRASH, WrapContent,
            visibilityPredicate = { false }, actionResolver = ::resolveTrashAction),
        ButtonSlot(LogicalButtonId.RECORD, WrapContent,
            visibilityPredicate = { true },
            textResolver = ::resolveRecordButtonTextPipeline,
            enabledResolver = { it.pipeline !is PipelineUiState.Preparing },
            actionResolver = ::resolveRecordActionPipeline),
        ButtonSlot(LogicalButtonId.SPACE, FillRemaining,
            visibilityPredicate = { true }, actionResolver = { Action.KeyboardInputAction.SpaceKey })  // FIX: Issue 3.0.5 – flache Action.SpaceKey → hierarchisch,
        // Hardcoded { false } für PAUSE — siehe Architektur-Notiz oben (Bug §1.1 #3).
        ButtonSlot(LogicalButtonId.PAUSE, WrapContent,
            visibilityPredicate = { false }, actionResolver = { null }),    // R.3 / 1.1.4
        ButtonSlot(LogicalButtonId.BACKSPACE, WrapContent,
            visibilityPredicate = { true }, actionResolver = { Action.KeyboardInputAction.Backspace }),
        ButtonSlot(LogicalButtonId.ENTER, WrapContent,
            visibilityPredicate = { true }, actionResolver = { Action.KeyboardInputAction.EnterKey }),
        ButtonSlot(LogicalButtonId.RESEND, WrapContent,
            visibilityPredicate = { false }, actionResolver = { null }),    // R.3 / 1.1.4
        ButtonSlot(LogicalButtonId.AUDIO_FOCUS, WrapContent,
            visibilityPredicate = { true }, actionResolver = { Action.AudioAction.ToggleAudioFocusPref }),
    ))),
)
```

### §8.4 KEYBOARD_REPROCESS_STAGING

Tri-State: pause_btn visible-disabled+alpha-0.4, trash_btn = "Cancel-Staging", record_btn = "Audio X:YY · Senden".

```kotlin
val KEYBOARD_REPROCESS_STAGING = LayoutMode(
    id = LayoutModeId.KEYBOARD_REPROCESS_STAGING,
    backend = BackendType.IME_VIEW,
    rows = listOf(
        RowDescriptor(slots = listOf(
            ButtonSlot(LogicalButtonId.RECORD, FillRemaining,
                visibilityPredicate = { true },
                textResolver = ::resolveRecordButtonTextStaging,    // "Audio 0:23 · Senden"
                enabledResolver = { state ->
                    val s = state.pipeline as? PipelineUiState.ReprocessStaging
                    s != null && !s.isStarting
                },
                actionResolver = { Action.PipelineAction.SendStaging }),  // FIX: Issue 3.0.5 – flache Action.SendStaging → hierarchisch (Mapping aus Phase-1 1.0.5)
            ButtonSlot(LogicalButtonId.RESEND, WrapContent,
                visibilityPredicate = { false },
                actionResolver = { null }),    // R.3 / 1.1.4 – nullable Resolver
            ButtonSlot(LogicalButtonId.BACKSPACE, WrapContent,
                visibilityPredicate = { true },
                actionResolver = { Action.KeyboardInputAction.Backspace }),
            ButtonSlot(LogicalButtonId.AUDIO_FOCUS, WrapContent,
                visibilityPredicate = { false },
                actionResolver = { Action.AudioAction.ToggleAudioFocusPref }),
        )),
        RowDescriptor(slots = listOf(
            ButtonSlot(LogicalButtonId.TRASH, WrapContent,
                visibilityPredicate = { true },
                actionResolver = { Action.PipelineAction.CancelReprocessStaging }),
            ButtonSlot(LogicalButtonId.SPACE, FillRemaining,
                visibilityPredicate = { true },
                actionResolver = { Action.KeyboardInputAction.SpaceKey })  // FIX: Issue 3.0.5 – flache Action.SpaceKey → hierarchisch,
            ButtonSlot(LogicalButtonId.PAUSE, WrapContent,
                visibilityPredicate = { true },
                enabledResolver = { false },
                alphaResolver = { 0.4f },
                actionResolver = { null }),    // R.3 / 1.1.4 – nullable Resolver
            ButtonSlot(LogicalButtonId.ENTER, WrapContent,
                visibilityPredicate = { true },
                actionResolver = { Action.KeyboardInputAction.EnterKey }),
        )),
    ),
)
```

### §8.5 Zentrale Predicate- und Resolver-Helfer

<!-- FIX: Issue 1.0.5 – Action-Hierarchie (F-8/F-11) durchpropagiert in §6/§8.5/§8.6/§9 (Mapping siehe Spec 2 §3.3) -->

Damit DRY: alle 5 LayoutModes nutzen die **gleichen** Predicate-Funktionen für identische Visibility-Logik.

```kotlin
// ── Predicates ─────────────────────────────────────────────────

/**
 * resend_btn — Single Source of Truth.
 * Ersetzt die heutigen 6 Mutatoren in 3 Klassen (RecordingUiController:137,158;
 * DictateInputMethodService:1345,1347,1669,1839).
 *
 * WICHTIG (FIX 2.0.12): `resendCooldown` ist NICHT Teil dieser Predicate.
 * Cooldown landet ausschließlich im `enabledResolver` des RESEND-Slots
 * (disabled+alpha 0.4f, siehe LayoutMode-Slot-Definitionen in §8.1/§8.2).
 *
 * Begründung: bekannter User-Bug (Plan §1.1 #3 — "Resend verschwindet beim
 * Toggle"). Der heutige Bug entstand durch transient visibility-Mutations bei
 * Re-Parent. Im neuen System gibt es kein Re-Parent (L2 flat hierarchy) und
 * keine Cooldown-im-Visibility-Pfad — damit ist der Bug strukturell
 * ausgeschlossen.
 *
 * NICHT in den Visibility-Pfad ziehen ohne Plan-Iter — siehe Test §14.2 UI-Test 4
 * und die Architektur-Notiz oben über das Cooldown-Verhalten (`enabledResolver`).
 */
fun predResendVisible(state: DictateUiState): Boolean =
    state.resend.lastAudioExists
        && state.resend.resendEnabled
        && state.recording is RecordingState.Idle
        && state.pipeline is PipelineUiState.Idle
// FIX: Issue 2.0.12 – Inline-Doku an predResendVisible (Cooldown-Trennung load-bearing)

/** trash_btn / pause_btn — sichtbar wenn aktiv aufgenommen wird oder Reprocess-Staging läuft. */
fun predTrashVisible(state: DictateUiState): Boolean =
    state.recording.isActiveOrPaused
        || state.pipeline is PipelineUiState.ReprocessStaging

fun predPauseVisible(state: DictateUiState): Boolean =
    state.recording.isActiveOrPaused
        || state.pipeline is PipelineUiState.ReprocessStaging

// ── Resolvers ──────────────────────────────────────────────────

/** record_btn-Text in Idle / Recording / Paused. */
fun resolveRecordButtonText(state: DictateUiState): CharSequence = when {
    state.recording is RecordingState.Active   -> ctx.getString(R.string.dictate_send)
    state.recording is RecordingState.Paused   -> ctx.getString(R.string.dictate_send)
    state.recording is RecordingState.Preparing -> ctx.getString(R.string.dictate_record)
    else                                       -> dictateButtonTextProvider()  // Sprach-Label
}

/** record_btn-Text während Pipeline (Preparing / Running). Ersetzt `KeyboardUiController.refreshRecordButtonFromState`. */
fun resolveRecordButtonTextPipeline(state: DictateUiState): CharSequence {
    val pipe = state.pipeline
    return when (pipe) {
        is PipelineUiState.Preparing -> ctx.getString(R.string.dictate_sending)
        is PipelineUiState.Running   -> {
            val counter = "${pipe.completedSteps}/${pipe.totalSteps}"
            val enter   = if (pipe.autoEnterActive) " \u21B5" else ""
            val timer   = formatElapsedCompact(pipe.elapsedMs)
            "$counter$enter  $timer"
        }
        else -> ctx.getString(R.string.dictate_record)
    }
}

/** record_btn-Text in ReprocessStaging — "Audio 0:23 · Senden". */
fun resolveRecordButtonTextStaging(state: DictateUiState): CharSequence {
    val s = state.pipeline as? PipelineUiState.ReprocessStaging ?: return ""
    return "Audio ${formatDuration(s.audioDurationSeconds)} · ${ctx.getString(R.string.dictate_send)}"
}

<!-- FIX: Issue 1.1.4 + 2.1.7 / R.3 – Resolver-Helfer returnen Action? = null statt Action.NoOp -->
<!-- FIX: Issue PENDING-1 / Block-4 Resolved – AudioFileFactory konkret spezifiziert, siehe Spec 1 §4.11. Resolver-Signatur trägt jetzt `services: ModuleServices` (DI-Quelle für `audioFileFactory`); `IOException` aus `allocate()` wird vom Resolver in einen ToastSink-Aufruf übersetzt, BEVOR dispatch erfolgt. -->
/**
 * record_btn-Click-Action gemäß RecordingState.
 *
 * **IOException-Handling (Spec 1 §4.11.10 / F1):**
 * `services.audioFileFactory.allocate()` (R.2) kann `java.io.IOException`
 * werfen, wenn `mkdirs()` auf `cacheDir/audio/` failt (Storage voll,
 * FS-Permission). Der Resolver fängt die Exception **lokal**, zeigt
 * einen Toast über `services.toastSink` und gibt `null` zurück; der
 * Caller (Click-Handler) sieht eine No-Op. Dadurch bleibt der
 * `dispatch()`-Pfad frei von I/O-Exceptions; der Reducer sieht den
 * Failure nie.
 */
fun resolveRecordAction(
    state: DictateUiState,
    services: ModuleServices,    // R.2: liefert audioFileFactory (Spec 1 §4.11)
): Action? = when (state.recording) {
    is RecordingState.Idle      -> {
        val file = try {
            services.audioFileFactory.allocate()    // R.2: Pre-Dispatch-Allocation (Spec 1 §4.11.4)
        } catch (e: java.io.IOException) {
            services.toastSink.show(ctx.getString(R.string.dictate_storage_full))
            android.util.Log.w("DictateResolver", "audioFileFactory.allocate failed", e)
            return null
        }
        Action.RecordingAction.StartRecording(
            target    = InsertionTarget.MainInputConnection,
            audioFile = file,
        )
    }
    is RecordingState.Active    -> Action.RecordingAction.StopRecordingAndSend
    is RecordingState.Paused    -> Action.RecordingAction.StopRecordingAndSend
    is RecordingState.Preparing -> null    // Click während Preparing ist No-Op
}

/** record_btn-Click-Action während Pipeline (Toggle-Auto-Enter im Running). */
fun resolveRecordActionPipeline(state: DictateUiState): Action? = when (state.pipeline) {
    is PipelineUiState.Running -> Action.FeatureToggleAction.ToggleAutoEnter   // Click toggelt AutoEnter
    else -> null
}

fun resolveTrashAction(state: DictateUiState): Action? = when {
    state.pipeline is PipelineUiState.ReprocessStaging -> Action.PipelineAction.CancelReprocessStaging(state.pipeline.sessionId)
    state.recording is RecordingState.Idle && state.pipeline is PipelineUiState.Idle -> null
    else -> Action.RecordingAction.CancelRecording
}

fun resolvePauseAction(state: DictateUiState): Action? = when (state.recording) {
    is RecordingState.Paused -> Action.RecordingAction.ResumeRecording
    is RecordingState.Active -> Action.RecordingAction.PauseRecording
    else -> null
}

fun resolvePauseIcon(state: DictateUiState): Int = when (state.recording) {
    is RecordingState.Paused -> R.drawable.ic_baseline_mic_24
    else                     -> R.drawable.ic_baseline_pause_24
}

/**
 * audio_focus_btn-Icon — gemeinsam für AUDIO_FOCUS-Slot UND EditBar (F-4 / DRY).
 *
 * Hintergrund: AudioFocus existiert in zwei UI-Stellen — als Main-Button-Area-Slot
 * (Single-Row-Variante) UND als `edit_audio_focus_btn` in der EditBar (orthogonale
 * Achse). Heute (`MainButtonsController.kt:368-387`) gibt es zwei separate
 * Code-Pfade, die `state.audio.audioFocusEnabledPref` in ein Icon mappen — eine Drift-Quelle.
 *
 * Dieser Helper ist die Single Source of Truth für "AudioFocus → Icon"; sowohl der
 * `AUDIO_FOCUS`-Slot (über `iconResolver`) als auch der `EditBarController`
 * (in `refreshAudioFocusIcon(state.audio.audioFocusEnabledPref)`) referenzieren ihn.
 */
fun resolveAudioFocusIcon(enabled: Boolean): Int =
    if (enabled) R.drawable.ic_baseline_volume_up_24
    else         R.drawable.ic_baseline_volume_off_24
```

### §8.6 LayoutCatalog.forKeyboard(state)

<!-- FIX: Issue 1.0.6 – Hierarchische State-Pfade (F-10) durchpropagiert in §6/§8.5/§8.6 (Mapping siehe Spec 1 §3) -->

```kotlin
object LayoutCatalog {
    fun forKeyboard(state: DictateUiState): LayoutMode {
        val isStaging      = state.pipeline is PipelineUiState.ReprocessStaging
        val isPipelineLive = state.pipeline is PipelineUiState.Preparing
                            || state.pipeline is PipelineUiState.Running
        return when {
            isStaging                                  -> KEYBOARD_REPROCESS_STAGING
            isPipelineLive && state.layout.singleRowMode      -> KEYBOARD_SINGLE_ROW_SEND_MODE
            isPipelineLive && !state.layout.singleRowMode     -> KEYBOARD_TWO_ROW_SEND_MODE
            !isPipelineLive && state.layout.singleRowMode     -> KEYBOARD_SINGLE_ROW
            else                                       -> KEYBOARD_TWO_ROW
        }
    }
}
```

### §8.7 Verifikation gegen Visibility-Matrix

Cross-Check der LayoutMode-Predicates gegen `main-button-area-inventory.md` §3 (Sichtbarkeits-Matrix):

| Button | Idle (heute) | TWO_ROW Predicate | Recording (heute) | TWO_ROW_SEND_MODE | Paused (heute) | TWO_ROW Predicate | ReprocessStaging (heute) | REPROCESS Predicate | Pipeline-Running (heute) | TWO_ROW_SEND_MODE | ✓ |
|---|---|---|---|---|---|---|---|---|---|---|---|
| record_pulse_layout | V | true | V | true | V | true | V | true | V | true | ✓ |
| resend_btn | V (iff lastAudio) | predResendVisible (Idle+lastAudio+ResendPref) | GONE | false | GONE | predResendVisible (false: not Idle) | GONE | false | GONE | false | ✓ |
| backspace_btn | V | true | V | true | V | true | V | true | V | true | ✓ |
| trash_btn | GONE | predTrashVisible (false) | V | false (Send-Mode!) | V | predTrashVisible (true) | V | true | GONE | false | **A1** |
| pause_btn | GONE | predPauseVisible (false) | V/enabled | false (Send-Mode!) | V/enabled | predPauseVisible (true) | V/disabled-0.4 | true (disabled+alpha) | GONE | false | **A2** |
| space_btn | V | true | V | true | V | true | V | true | V | true | ✓ |
| enter_btn | V | true | V | true | V | true | V | true | V | true | ✓ |
| audio_focus_btn (Single-Row) | V | true (SINGLE_ROW) | V | true (SINGLE_ROW_SEND) | V | true (SINGLE_ROW) | V | (n/a in Reprocess heute) | V | true | ✓ |

**Anmerkung A1 / A2:** Heutiges Verhalten in `KeyboardStateManager.kt:187-191` ist `isActive || isStaging` — d.h. trash/pause sind während Recording **und** Pipeline-Running sichtbar. **Recherche-Befund:** beim Übergang Recording → Pipeline-Running werden in heutigem Code pause/trash GONE, weil `RecordingState` zu `Idle` wechselt während `pipeline` zu `Preparing→Running` wechselt — und `predPauseVisible/predTrashVisible` gibt false zurück (nicht active, nicht paused, nicht staging).

→ A1/A2 entspricht damit exakt dem heutigen Verhalten. **Send-Mode hat kein trash/pause** — User-validierte Annahme aus dem Use-Case-Briefing.

### §8.8 Edge-Cases

| Edge-Case | Heute | Catalog-Predicate | Bemerkung |
|---|---|---|---|
| ReprocessStaging + SingleRow | Rendert Two-Row-Layout (kein Single-Row-Variant gepflegt) | `forKeyboard()` wählt `KEYBOARD_REPROCESS_STAGING` unabhängig vom singleRowMode | **Vereinfachung:** ReprocessStaging hat nur Two-Row-Variante. Begründung: Cancel-Staging-Workflow ist UI-fokussiert (Editable Queue + Language-Chip in Promptbar) — Single-Row macht hier keinen Sinn. Falls User-Test eine Single-Row-Variante fordert, wird ein zusätzlicher LayoutMode hinzugefügt. |
| ReprocessStaging + AudioFocus-Toggle | AudioFocus-Btn nur in SingleRow sichtbar; in ReprocessStaging-Two-Row also GONE | Predicate `false` in REPROCESS_STAGING | konsistent |
| Preparing-State + SingleRow-Toggle | Heute: KSM.refresh wird durch SingleRowToggle NICHT getriggert (Bug-Klasse "SSOT-Lücke" in §7 von _pending-state-machine-visibility-owners.md) | Im Refactor: `state.layout.singleRowMode` ist Teil des DictateUiState, jeder Toggle erzeugt eine neue State-Emission → Manager re-rendert komplett | Bug eliminiert |
| Send-Modus → Recording (Pause-Stop und neuer Start) | Heute: race-prone (RecordingUi vs KeyboardUi) | Catalog wählt deterministisch via `forKeyboard()` | konsistent |

---

## §9 Migration vorhandener Klassen — Code-Pointer pro Aussage

### §9.1 KeyboardLayoutModeController → entfällt vollständig

| Heute (Source) | Was passiert | Ziel im Refactor |
|---|---|---|
| `KeyboardLayoutModeController.kt:47-50` (`csTwoRowAction = ConstraintSet().clone(views.actionRow)`, `csTwoRowInput = clone(views.inputRow)`) | gelöscht | `motion_scene_keyboard.xml/two_row_state` (deklarativ) |
| `KeyboardLayoutModeController.kt:66-74` (`originalParents`-Map mit 7 Views) | gelöscht | nicht nötig — keine Re-Parents in MotionLayout (L2) |
| `KeyboardLayoutModeController.kt:82` (`csSingleRow = buildSingleRowConstraintSet()`) | gelöscht | `motion_scene_keyboard.xml/single_row_state` |
| `KeyboardLayoutModeController.kt:97-101` (`init { setSingleRowMode(sp.get(Pref.SingleRowMode), animate=false) }`) | gelöscht | erste Render des `KeyboardLayoutManager` macht `motionLayout.jumpToState(...)` (siehe ImeViewBackend.render §6) |
| `KeyboardLayoutModeController.kt:115-140` (`setSingleRowMode`) | gelöscht | Replaced by `motionLayout.transitionToState(targetSceneState)` (§6, Z. ~render) |
| `KeyboardLayoutModeController.kt:150-152` (`refresh()`) | gelöscht | nicht nötig — der Manager re-rendert reaktiv über StateFlow |
| `KeyboardLayoutModeController.kt:183-191` (`rehome(toSingleRow)`) | gelöscht | nicht nötig — keine Re-Parents (L2) |
| `KeyboardLayoutModeController.kt:202-272` (`buildSingleRowConstraintSet()`) | gelöscht | `motion_scene_keyboard.xml/single_row_state` |

**Nettogewinn:** 273 Zeilen Kotlin gelöscht.

Code-Snippet aus heutiger Implementierung (zur Verifikation, dass die Migration semantisch identisch ist):

```kotlin
// KeyboardLayoutModeController.kt:115-140 (heute)
fun setSingleRowMode(enabled: Boolean, animate: Boolean) {
    if (lastAppliedSingleRow == enabled) return
    if (animate && sp.get(Pref.Animations)) {
        TransitionManager.beginDelayedTransition(rootView())
    }
    rehome(enabled)
    if (enabled) {
        csSingleRow.applyTo(views.actionRow)
    } else {
        csTwoRowAction.applyTo(views.actionRow)
        csTwoRowInput.applyTo(views.inputRow)
    }
    views.inputRow.visibility = if (enabled) View.GONE else View.VISIBLE
    views.audioFocusButtonInRow.visibility = if (enabled) View.VISIBLE else View.GONE
    lastAppliedSingleRow = enabled
}
```

→ ersetzt durch (im neuen `ImeViewBackend.render`):

```kotlin
val targetSceneState = mode.id.toSceneStateId()
if (state.layout.animationsEnabled) motionLayout.transitionToState(targetSceneState)
else                         motionLayout.jumpToState(targetSceneState)
```

**`views.inputRow.visibility`-Toggle entfällt**, weil `inputRow` in der neuen Architektur gar nicht mehr existiert (flache Hierarchie, L2). **`views.audioFocusButtonInRow.visibility`-Toggle entfällt**, weil das Predicate in `KEYBOARD_SINGLE_ROW.AUDIO_FOCUS.visibilityPredicate = { true }` und `KEYBOARD_TWO_ROW.AUDIO_FOCUS.visibilityPredicate = { false }` lebt.

### §9.2 MainButtonsController → ImeViewBackend

| Heute | Ziel |
|---|---|
| `MainButtonsController.kt:76-79` (`recordClickListener` → `callback.onRecordClicked()`) | `ImeViewBackend.wireStaticHandlers` (RECORD-Slot → `actionResolver(state) = Action.RecordingAction.StartRecording(target = InsertionTarget.MainInputConnection) / StopRecordingAndSend / …`) |
| `MainButtonsController.kt:155-260` (`registerMainButtonListeners`, alle 9 Click-/Long-Click/Touch-Handler) | `ImeViewBackend.wireStaticHandlers` + `actionResolver` pro Slot. Tabelle in §13.2 |
| `MainButtonsController.kt:189-194` (`backspaceButton.setOnTouchListener(BackspaceSwipeHandler(...))`) | `ImeViewBackend.buildBackspaceSwipeHandler()` — gleiche Klasse, einmalig in `attach()` verdrahtet (§11.7) |
| `MainButtonsController.kt:203-232` (CursorSwipeTouchHandler für space_btn) | `ImeViewBackend.buildSpaceTouchHandler()` (§11.7) |
| `MainButtonsController.kt:254-259` (`enterButton.setOnTouchListener(EnterOverlayHandler(...))`) | `ImeViewBackend.buildEnterOverlayHandler()` (§11.7) |
| `MainButtonsController.kt:251` (`overlayCharactersLl.visibility = VISIBLE` in enter-long-press) | bleibt — overlay-spezifisch, nicht Teil der LayoutCatalog-Visibility-Matrix |
| `MainButtonsController.kt:303-319` (`initializeKeyPressAnimations`) | `ImeViewBackend.wireStaticHandlers` ruft `keyPressAnimator.applyPressAnimation(view)` für jeden Button |
| `MainButtonsController.kt:331-333` (`setResendEnabled` für 500ms-Cooldown) | bleibt erhalten als orthogonale Mutation auf `view.isEnabled` (siehe §11.6 Lifecycle-Note: dies wird durch State-Update getriggert, nicht direkt) |
| `MainButtonsController.kt:344-346` (`updateRecordButtonText`) | gelöscht — `textResolver` in RECORD-Slot übernimmt |
| `MainButtonsController.kt:368-387` (`refreshAudioFocusIcon`) | `iconResolver` in AUDIO_FOCUS-Slot |
| `MainButtonsController.kt:389-416` (`applyTheme` mit `applyButtonColor`) | bleibt erhalten — Theme-Mutation ist eine separate Achse, nicht state-getrieben. Der ImeViewBackend hat eine `applyTheme(accentColor)`-Methode, die der Service nach jedem Re-Inflate aufruft. |
| `MainButtonsController.kt:424-437` (`animateSmallModeToggle`) | bleibt erhalten — externe Animation auf `edit_numbers_btn`, kein Slot-Resolver. Wird als `EditNumbersAnimator`-Helper extrahiert. |
| `MainButtonsController.kt:452-477` (`animateEditNumbersBounce`) | identisch zu animateSmallModeToggle — verbleibt im EditNumbersAnimator |
| `MainButtonsController.kt:481-493` (`updateOverlayCharacters`) | bleibt — overlay-spezifisch |

**Nettogewinn:** ca. 200 Zeilen aus MainButtonsController wandern in ImeViewBackend (Click-Logik durch Catalog ersetzt → Reduktion um ~50 Zeilen). Theme + Animation-Helper bleiben.

### §9.3 KeyboardStateManager → drei Owner-Klassen (R.10)

<!-- FIX: Issue 2.1.13 / R.10 – KSM-Aufspaltung in ContentAreaController + PromptVisibilityController + OverlayResetHandler -->
**Heute** ist `KeyboardStateManager` ein god-class mit drei orthogonalen Verantwortungen
(Content-Area-Container, Prompt-Visibility, Overlay-Reset-Touchpunkte). Im Refactor wird die
Klasse in **drei kleine SRP-konforme Owner-Klassen** aufgespalten:

| Heutige Methode | Neue Owner-Klasse | Verantwortung |
|-----------------|-------------------|---------------|
| `applyVisibility()` (Z. 158-169) | (gelöscht) | Catalog-Predicates übernehmen — keine separate Owner-Klasse nötig |
| `applyContentAreaVisibility` (Z. 171-181) | **`ContentAreaController`** (neu, Spec 2 §9.3 / §13) | Owner: `mainButtonsCl` / `qwertz_container` / `emojiPicker_container`-Visibility (state.layout.contentArea-Achse). Optional als zweites RenderBackend implementiert (Issue 2.1.15 / Option B). |
| `applyRecordingControlsVisibility` (Z. 183-192) | (gelöscht) | Predicates in `predTrashVisible` / `predPauseVisible` (§8.5) |
| `applyPromptsVisibility` (Z. 194-224) | **`PromptVisibilityController`** (neu) | Owner: `prompts_container` + Sub-Views. Eigene Klasse, weil Prompts-Hierarchie ist orthogonal zur Main-Button-Area |
| `overlayCharactersLl.visibility = GONE` reset (Z. 162) | **`OverlayResetHandler`** (neu) | Touchpunkt für Overlay-Characters-Container-Reset; trivial, könnte auch im EnterOverlayHandler leben — eigener Helper, weil Reset-Logik wiederverwendet wird |

**Migrations-Strategie (R.13 / Issue 2.1.17):** in Block 5c werden die drei alten KSM-Methoden
durch **leere Bodies** ersetzt (no-op); die Owner-Klassen übernehmen parallel. Block 5d
löscht KSM komplett. **Strict-Mode-Logging** während 5c verifiziert, dass keine
Doppel-Mutation auf einer Visibility-Achse passiert.

**SOLID:** drei Klassen × eine Verantwortung jeweils. KSM ist nicht mehr das einzige
SRP-Antipattern in der KeyboardLayoutManager-Region.

**Nettogewinn:** Klassen-Größe sinkt von ~250 Zeilen auf 3×~50 Zeilen. Kein zentraler god-class mehr.

### §9.4 RecordingUiController → KeyboardUiController-Anteile + LayoutCatalog

| Heute | Ziel |
|---|---|
| `RecordingUiController.kt:115-138` (`applyIdleState`) — setzt `recordButton.text/isEnabled/CompoundDrawables`, ruft `recordingAnimation.cancel()`, mutiert `pauseButton.foreground`, **mutiert `resendButton.visibility`** | gelöscht — `textResolver` (RECORD-Slot, §8.5), `iconResolver` (PAUSE-Slot), `RecordingAnimationController` (§11.5), Predicate `predResendVisible` (§8.5) |
| `RecordingUiController.kt:140-142` (`applyPreparingState`) — `recordButton.isEnabled = false` | gelöscht — `enabledResolver` der RECORD-Slots |
| `RecordingUiController.kt:144-184` (`applyActiveState`) — Text "Senden", CompoundDrawables, **`resendButton.visibility = GONE`**, `recordingAnimation.start()`, prompt-Buttons | gelöscht / wandert: Text via Resolver; resend via Predicate; recordingAnimation via `RecordingAnimationController` |
| `RecordingUiController.kt:186-196` (`applyPausedState`) — pauseButton-Foreground, `recordingAnimation.pause()` | gelöscht — `iconResolver` PAUSE-Slot, `RecordingAnimationController` |
| `RecordingUiController.kt:51-60` (`onStateChanged` callback) | entfällt — neue Architektur: DictateUiState-Update emittiert vom Service, Manager re-rendert |
| `RecordingUiController.kt:62-64` (`onAmplitudeUpdate`) | bleibt — separate Klasse `RecordingAnimationController` (§11.5) erhält Amplituden via separaten Hook |
| `RecordingUiController.kt:67-82` (`onTimerTick`) | bleibt teilweise — Timer-Update auf `recordingAnimation` und auf `recordButton.text` (via Resolver, der `state.recording.elapsedMs` liest) |
| `RecordingUiController.kt:222-246` (`updateQwertzRecButton`) | bleibt — QWERTZ-Bereich ist orthogonal, eigener Slot oder eigener Controller |
| `RecordingUiController.kt:254-277` (`enterPipelineDisplay` / `updatePipelineTimer`) | bleibt — QWERTZ-spezifisch, bleibt im KeyboardUiController bzw. wird in den ContentArea-Controller verschoben |

**Nettogewinn:** ca. 80 der 280 Zeilen aus RecordingUiController gelöscht (alle Main-Button-Mutationen entfallen). Der QWERTZ-Bereich + Amplitude-Visualizer bleiben.

### §9.5 KeyboardUiController.refreshRecordButtonFromState → record_btn-Resolver

| Heute | Ziel |
|---|---|
| `KeyboardUiController.kt:464-509` (Idle/Preparing/Running/ReprocessStaging-Branches in `refreshRecordButtonFromState`) | Aufgeteilt in `resolveRecordButtonText` (Idle), `resolveRecordButtonTextPipeline` (Preparing/Running) und `resolveRecordButtonTextStaging` (ReprocessStaging) — siehe §8.5 |
| `KeyboardUiController.kt:147-155` (`updateDictateUiState`) | gelöscht — DictateUiState wird im DictateOrchestrator (Spec 1 §4.3) verwaltet, gespiegelt vom DictateUiStateStore (Spec 1 §4.4) und reactive emittiert <!-- FIX: Issue 2.0.2 – PipelineStateManager → DictateOrchestrator (Naming-Drift, F-11) --> |
| `KeyboardUiController.kt:241` (`infoCl.visibility = GONE` direkt) | wird zu `infoBarController.dismiss()` (siehe Sekundär-Schwäche in _pending-state-machine-visibility-owners.md §1) |

### §9.6 DictateInputMethodService.java — die 4 problematischen resend-Mutationen

| Heute | Ziel |
|---|---|
| `DictateInputMethodService.java:1345` (`resendButton.setVisibility(View.VISIBLE)` im onStartInputView Idle-Branch) | gelöscht — Predicate übernimmt |
| `DictateInputMethodService.java:1347` (`resendButton.setVisibility(View.GONE)` im onStartInputView Idle-Branch) | gelöscht — Predicate übernimmt |
| `DictateInputMethodService.java:1669` (`resendButton.setVisibility(View.GONE)` in `runTranscriptionViaOrchestrator`) | gelöscht — Predicate übernimmt (Pipeline-State wechselt zu Preparing → predResendVisible = false) |
| `DictateInputMethodService.java:1839` (`resendButton.setVisibility(View.VISIBLE)` in `onShowResend()`) | wird zu State-Update: `pipelineService.markLastAudioExists(true)` → State emittiert → Predicate evaluiert → resend wird sichtbar |

---

## §10 Acceptance-Kriterien

Block 4 (KeyboardLayoutManager + LayoutCatalog) gilt als done, wenn:
- [ ] Manager subscribt erfolgreich an `pipelineService.state`.
- [ ] Bei jeder State-Änderung wird die korrekte LayoutMode-Instanz aus dem Catalog gewählt.
- [ ] Alle Slots in allen LayoutModes haben Predicates, die mit dem heutigen Verhalten übereinstimmen (gegen Use-Case-Liste UC1-UC7 + UC-extra-1 bis UC-extra-10 verifiziert).

Block 5 (ImeViewBackend + MotionLayout) gilt als done, wenn:
- [ ] MotionLayout-XML inflated korrekt mit allen 9 Buttons als direkte Children.
- [ ] Two-Row ↔ Single-Row Übergang animiert weich (250ms), Pulse-Animation läuft ungestört durch.
- [ ] Send-Mode + Single-Row: Send-Button vollständig sichtbar, kein Verdecken (Bug-Eliminierung — deckt Hauptplan §1.1 Bug-Symptom #3a).
- [ ] Toggle Single-Row in allen Pipeline-States (Idle, Recording, Paused, Pipeline-Running, ReprocessStaging) korrekt.
- [ ] PulseLayout-Animation läuft auch während MotionLayout-Transition (Spike-Validierung §11.3).
- [ ] Inflation-Cost beim ersten `onCreateInputView` < 50ms (Spike-Messung §11.4).
- [ ] Re-Inflate (Rotation, Theme-Wechsel): erster Frame zeigt korrekten LayoutMode ohne Animation-Snap (`jumpToState` statt `transitionToState` beim ersten Render).
- [ ] **Resend-Btn bleibt während Toggle Two-Row ↔ Single-Row in Idle+lastAudio durchgängig sichtbar** (visibility=VISIBLE in jedem Frame). Verifiziert via Espresso `IdlingResource` oder Frame-Capture (siehe Test §14.2 UI-Test 8 — neu). Deckt Hauptplan §1.1 Bug-Symptom #3b. <!-- FIX: Issue 3.0.9 – Resend-Toggle-Bug-Acceptance ergänzt -->
- [ ] **Resend-Btn-Cooldown (500ms nach Click) lässt visibility=VISIBLE,** nur enabled=false + alpha=0.4. Siehe Test §14.2 UI-Test 9 — neu. <!-- FIX: Issue 3.0.9 -->
- [ ] **Active → Pipeline-Preparing-Übergang:** kein Frame zeigt trash/pause über record_btn. Siehe Test §14.2 UI-Test 10 — neu. <!-- FIX: Issue 3.0.9 -->
<!-- FIX: Issue 2.1.17 / R.13 + 2.1.18 / R.14 + 2.1.14 / R.11 – Acceptance-Erweiterungen -->
- [ ] **R.13 Strict-Mode-Logging während 5c:** `VisibilityWrite from $caller`-Log; Acceptance-Kriterium "keine zwei Subsysteme schreiben gleichzeitig auf einer Visibility-Achse" wird verifiziert.
- [ ] **R.14 firstRender-Flag bei Re-Inflate:** der erste Render nach `detach()` + `attach()` (Rotation) ruft `jumpToState` (kein 250ms-Slide-In). Robolectric-Test verifiziert die ConstraintSet-Animation = 0ms beim ersten Render-Tick.
- [ ] **R.11 visibilityMode="ignore" auf allen 9 Buttons:** XML-Lint-Check; jeder der 9 Button-Tags in den 4 MotionScene-XMLs hat das Attribut. Catalog ist alleiniger Visibility-Owner (verifiziert via Test "MotionLayout-Transition setzt nicht versehentlich visibility").

---

## §11 Research-TODOs für Agent — konkrete Antworten

### §11.1 MotionLayout-Migration — konkretes XML-Refactor-Diff

Diff zwischen `activity_dictate_keyboard_view.xml` (heute, Z. 12-172) und Refactor (§7.2):

```diff
-    <LinearLayout
+    <androidx.constraintlayout.motion.widget.MotionLayout
         android:id="@+id/main_buttons_cl"
         android:layout_width="0dp"
         android:layout_height="wrap_content"
-        android:orientation="vertical"
         android:paddingStart="72dp"
         android:paddingEnd="16dp"
         android:clipChildren="false"
         android:clipToPadding="false"
+        app:layoutDescription="@xml/motion_scene_keyboard"
         app:layout_constraintTop_toBottomOf="@id/edit_buttons_keyboard_ll"
         app:layout_constraintStart_toStartOf="parent"
         app:layout_constraintEnd_toEndOf="parent">

-        <androidx.constraintlayout.widget.ConstraintLayout
-            android:id="@+id/action_row" ...>
-
-            <net.devemperor.dictate.widget.PulseLayout
-                android:id="@+id/record_pulse_layout"
-                android:layout_width="0dp" ...
-                app:layout_constraintBottom_toBottomOf="parent"
-                app:layout_constraintEnd_toStartOf="@+id/resend_btn"
-                app:layout_constraintStart_toStartOf="parent"
-                app:layout_constraintTop_toTopOf="parent">
+        <net.devemperor.dictate.widget.PulseLayout
+            android:id="@+id/record_pulse_layout"
+            android:layout_width="0dp"
+            android:layout_height="wrap_content"
+            app:pulseCount="3" ...>

                 <com.google.android.material.button.MaterialButton
                     android:id="@+id/record_btn"
                     android:layout_width="match_parent" ... />

-            </net.devemperor.dictate.widget.PulseLayout>
+        </net.devemperor.dictate.widget.PulseLayout>

-            <com.google.android.material.button.MaterialButton
-                android:id="@+id/resend_btn"
-                ...
-                app:layout_constraintBottom_toBottomOf="parent"
-                app:layout_constraintEnd_toStartOf="@+id/backspace_btn"
-                app:layout_constraintStart_toEndOf="@+id/record_pulse_layout"
-                app:layout_constraintTop_toTopOf="parent" />
+        <com.google.android.material.button.MaterialButton
+            android:id="@+id/resend_btn"
+            android:layout_width="wrap_content"
+            android:layout_height="wrap_content"
+            android:foreground="@drawable/ic_outline_change_circle_24"
+            android:foregroundGravity="center"
+            android:minWidth="0dp"
+            android:visibility="gone" />

-        </androidx.constraintlayout.widget.ConstraintLayout>
-
-        <androidx.constraintlayout.widget.ConstraintLayout
-            android:id="@+id/input_row" ...>
-            <!-- 4 Buttons mit ihren Constraints -->
-        </androidx.constraintlayout.widget.ConstraintLayout>
-
-    </LinearLayout>
+        <!-- weitere 7 Buttons (siehe §7.2) -->
+    </androidx.constraintlayout.motion.widget.MotionLayout>
```

**Schlüsselbeobachtungen aus dem Diff:**
1. Beide ConstraintLayout-Container (`action_row`, `input_row`) entfallen vollständig.
2. Das LinearLayout `main_buttons_cl` wird zum MotionLayout (mit gleicher ID, damit andere Constraints im Wrapper-ConstraintLayout intakt bleiben).
3. Alle 9 Buttons werden direkte Children des MotionLayout — keine Verschachtelung mehr.
4. **Constraints sind nicht mehr im Layout-XML**, sondern in `motion_scene_keyboard.xml` (§7.1).
5. `tools:visibility`-Hints bleiben erhalten für IDE-Preview.

**Implikation für `KeyboardViews`** (data class in `KeyboardStateManager.kt:36-73`):
- `actionRow` / `inputRow`-Felder werden **gelöscht**.
- `mainButtonsClTyped` wird zu `MotionLayout` getypt.
- `audioFocusButtonInRow` wird zu `audioFocusButton` (nur eine Variante, da nicht mehr re-parented).

### §11.2 LayoutCatalog: Predicate-Verifikation

Siehe §8.7 (Tabelle). Alle 9 Buttons sind in allen 5 KEYBOARD-LayoutModes gegen die heutige Sichtbarkeits-Matrix verifiziert. Edge-Cases in §8.8.

**DRY-Beweis:** alle 5 LayoutModes nutzen die **gleichen** 3 Predicate-Funktionen (`predResendVisible`, `predTrashVisible`, `predPauseVisible`) und die gleichen 5 Resolver (`resolveRecordButtonText*`, `resolveTrashAction`, `resolvePauseAction`, `resolvePauseIcon`, `resolveRecordAction*`). Konkret: `predResendVisible` wird in jedem der 5 Modes als `visibilityPredicate` für RESEND verwendet, mit identischer Logik.

### §11.3 PulseLayout-Spike

**Spike-Ziel:** verifizieren, dass `PulseLayout.startPulse()`-Animation während `motionLayout.transitionToState()` weiterläuft, ohne gecanceled zu werden.

**Spike-Code-Skizze:**

```kotlin
// SpikeActivity.kt — minimaler Reproducer
class SpikeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.spike_motion_layout)  // enthält MotionLayout mit PulseLayout-Wrapper

        val motion: MotionLayout = findViewById(R.id.spike_motion)
        val pulse: PulseLayout    = findViewById(R.id.record_pulse_layout)

        pulse.startPulse()  // Animation läuft
        Handler(Looper.getMainLooper()).postDelayed({
            // Spike-Test 1: Transition während aktiver Pulse
            motion.transitionToState(R.id.single_row_state)
        }, 1000)
        Handler(Looper.getMainLooper()).postDelayed({
            // Spike-Test 2: Transition zurück
            motion.transitionToState(R.id.two_row_state)
        }, 3000)

        // Logging des Animator-States — gibt Aufschluss, ob ValueAnimator
        // bei View-Detach gecanceled wird (PulseLayout.onDetachedFromWindow ruft cancel(),
        // siehe PulseLayout.kt:136-140).
        motion.setTransitionListener(object : MotionLayout.TransitionListener {
            override fun onTransitionStarted(ml: MotionLayout?, start: Int, end: Int) {
                Log.d("Spike", "transition start: pulsing=${pulse.isPulsing}")
            }
            override fun onTransitionCompleted(ml: MotionLayout?, currentId: Int) {
                Log.d("Spike", "transition end: pulsing=${pulse.isPulsing}")
            }
            override fun onTransitionChange(ml: MotionLayout?, s: Int, e: Int, p: Float) {}
            override fun onTransitionTrigger(ml: MotionLayout?, t: Int, p: Boolean, prog: Float) {}
        })
    }
}
```

**Smoke-Test-Kriterien (passing):**
1. Während Transition (250ms): `pulse.isPulsing == true` durchgängig im TransitionListener.
2. Visuell: Pulse-Kreise rendern weiter, ohne sichtbares Reset (kein `invalidate()`-Pause).
3. Nach Transition: PulseLayout ist im neuen ConstraintSet positioniert (Single-Row vs. Two-Row), Animation läuft weiter mit gleichem `animator.animatedFraction`.

**Bekanntes Risiko (zu validieren durch Spike):** MotionLayout führt während Transition partielle View-Detach/Re-Attach durch. `PulseLayout.onDetachedFromWindow` (Z. 136-140) ruft `animator.cancel()` — das würde die Animation stoppen.

**Fallback bei Bruch:**
- **Option A (zu validieren durch Spike):** PulseLayout in einen FrameLayout-Container wrappen, der außerhalb des MotionLayout lebt und positionsmäßig per ConstraintHelper über den `record_pulse_layout`-Slot gelegt wird. Animation läuft stabil, da PulseLayout nicht detach-attach durchläuft. Aufwand: mittel.
- **Option B (Fallback):** programmatische ConstraintSets statt MotionScene (Option 4 aus motionlayout-architecture-options.md) — keine Re-Attach-Phase, aber kein deklaratives MotionLayout-Animation-System.

### §11.4 Inflation-Cost-Messung

**Profiling-Strategie:**

```kotlin
// In DictateInputMethodService.onCreateInputView, vor und nach Inflate:
override fun onCreateInputView(): View {
    val t0 = System.nanoTime()
    Trace.beginSection("DictateIME.inflate")
    val view = layoutInflater.inflate(R.layout.activity_dictate_keyboard_view, null)
    Trace.endSection()
    val t1 = System.nanoTime()
    Log.d("InflateProfile", "inflate took ${(t1 - t0) / 1_000_000} ms")

    Trace.beginSection("DictateIME.firstRender")
    keyboardLayoutManager.attachBackend(imeViewBackend)
    Trace.endSection()
    val t2 = System.nanoTime()
    Log.d("InflateProfile", "firstRender took ${(t2 - t1) / 1_000_000} ms")
    return view
}
```

**Mess-Geräte:** Pixel 6 (Mid-Range), Pixel 4a (Low-End — kritischer Datenpunkt), Samsung Galaxy A53 (typisches Mid-Range mit eigenem Skin).

**Akzeptanz-Schwelle:** `onCreateInputView` < 50ms auf Pixel 4a. Wenn überschritten:

**Optimierungs-Strategien (gestaffelt):**
1. **Async-Inflate:** `AsyncLayoutInflater` für Sub-Views, die im ersten Frame nicht sichtbar sind (Emoji-Picker, QWERTZ-Container).
2. **Pre-Inflate beim Service-onCreate:** Layout schon beim Service-Start (nicht beim Tastatur-Open) inflaten. Trade-off: Service-Memory steigt um ~2MB.
3. **Reduktion der MotionScene:** wenn deriveConstraintsFrom selbst Kosten produziert, Zwischen-States zusammenfassen.

**Spike-Vorschlag (zu validieren durch Spike):** Vergleichsmessung zwischen
- (a) heutigem `LinearLayout` + 2 `ConstraintLayout`-Setup (Baseline)
- (b) flachem MotionLayout + Scene-XML

Erwartung: (b) ist nicht signifikant teurer (MotionLayout ist ConstraintLayout + State-Manager; ConstraintLayout-Inflation dominiert).

### §11.5 BorderGlow-Animation-Migration: `RecordingAnimationController`

**Problem:** im neuen reaktiven Modell ruft der Manager `actionResolver(state)`, aber **keine direkten** `recordButton.foreground = ...`-Mutationen — die sind heute in `BorderGlowAnimation.start()/pause()/cancel()` (BorderGlowAnimation.kt:62-110).

**Lösung:** separate Klasse `RecordingAnimationController`, die `state.recording` observiert und auf den `record_btn`-View animiert.

```kotlin
class RecordingAnimationController(
    private val recordButton: MaterialButton,
    private val animation: RecordingAnimation,        // BorderGlowAnimation
    private val pulseLayout: PulseLayout,
    private val animationsEnabled: () -> Boolean,
) {
    private var lastRecordingState: RecordingState? = null

    /**
     * Reactive entry point. Called by ImeViewBackend.render after
     * applying slot properties. Idempotent — only mutates if state
     * actually changed.
     */
    fun onState(state: DictateUiState) {
        val prev = lastRecordingState
        val curr = state.recording
        if (prev::class == curr::class) return  // gleiche Sealed-Variante = no-op

        when (curr) {
            is RecordingState.Idle      -> { animation.cancel();  pulseLayout.stopPulse() }
            is RecordingState.Preparing -> { /* no animation */ }
            is RecordingState.Active    -> {
                if (animationsEnabled()) {
                    animation.start()
                    pulseLayout.startPulse()
                }
            }
            is RecordingState.Paused -> {
                if (animationsEnabled()) {
                    animation.pause()
                    pulseLayout.pausePulse()
                }
            }
        }
        lastRecordingState = curr
    }

    /** Forwarding für den per-Tick Amplitude-Hook (vom PipelineService). */
    fun onAmplitude(level: Float) = animation.onAmplitude(level)

    /** Forwarding für den per-Tick Timer-Hook. */
    fun onTimerTick(elapsedMs: Long) {
        val text = String.format(Locale.getDefault(), "%02d:%02d",
            (elapsedMs / 60000).toInt(), ((elapsedMs / 1000) % 60).toInt())
        animation.onTimerTick(text)
    }

    fun updateColor(accentColor: Int) = animation.updateColor(accentColor)
}
```

**Wichtig:** `RecordingAnimationController` ist **stateless** außer dem `lastRecordingState`-Cache (Performance-Guard). Er ist Composition-Member von `ImeViewBackend` (siehe §6).

<!-- FIX: Issue 2.0.2 – PipelineStateManager → AudioModule/RecordingModule + LocalBinder-Spec-1-§5-Querverweis -->
**Amplitude/Timer-Hooks:** kommen direkt vom AudioModule / RecordingModule (Spec 1 §15) — nicht über `DictateUiState`-Emission, weil das pro-Tick zu neuen State-Allocations führen würde. Stattdessen: eigener `StateFlow<AmplitudeTick>` oder simple Callback-Methode am LocalBinder (siehe Spec 1 §5 für die LocalBinder-API: `state` + `dispatch` + Lifecycle-Hooks).

### §11.6 Click-Listener-Lifecycle: Memory-Leak-Analyse

**Problem-Hypothese:** `ImeViewBackend.render()` setzt pro Slot `view.setOnClickListener { onAction?.invoke(slot.actionResolver(state)) }`. Bei jedem Render-Tick (Pipeline läuft, alle paar 100ms) wird ein neues Lambda erzeugt, das auf `state` und `slot` schließt. Das alte Lambda wird durch `setOnClickListener` ersetzt → GC-fähig.

**Risiko 1:** In schnellen Tick-Intervallen sammeln sich kurzzeitig Lambdas an (Allocation-Pressure). Bei einem 100ms-Tick und 9 Buttons sind das 90 Lambdas/Sekunde, die GC-pflichtig werden — moderate Allocation-Cost, aber auf Low-End-Geräten messbar.

**Risiko 2:** Wenn ein `state`-Snapshot in einem Lambda gefangen wird, das vom System verzögert ausgeführt wird (z.B. Touch-Event in der Frame-Queue), zeigt der Click die `actionResolver` von einem **veralteten** State an. Bei Pipeline-Tick-Race: Click auf record_btn könnte `Action.NoOp` (alter Pipeline.Running-State) statt `Action.RecordingAction.StartRecording(target = InsertionTarget.MainInputConnection)` (neuer Idle-State) emittieren.

**Empfehlung (L8):** Click-Listener nur einmal pro Backend-Attach setzen, lesen aktuellen State zur Click-Zeit aus einer Backend-Field (`stateRef`).

```kotlin
// Setup einmalig in attach()/wireStaticHandlers()
view.setOnClickListener {
    onVibrate()
    val s = stateRef ?: return@setOnClickListener     // aktueller State
    val slot = currentSlot(id) ?: return@setOnClickListener
    onAction?.invoke(slot.actionResolver(s))           // Action zur Click-Zeit
}
```

**Vorteile:**
- Eine Lambda-Allokation pro Button pro Lifecycle (statt pro Tick) → Allocation-frei während Recording.
- Click liest immer den **aktuellen** State, nicht den Render-Snapshot → keine Race.

**Nachteil:** Etwas indirektere Code-Lesbarkeit (`currentSlot(id)` wird zur Laufzeit gesucht). Mitigation: `currentSlot(id)` ist ein simple `flatMap.firstOrNull` über die ~9 Slots → O(9) ist vernachlässigbar.

**Memory-Leak-Frage:** Lambdas referenzieren `stateRef`/`modeRef` (Backend-Felder). Backend-Lifecycle ist gebunden an View-Lifecycle (`onCreateInputView` → `onDestroy`). Wenn der View entsorgt wird, geht der Backend mit — keine GC-Roots, kein Leak.

### §11.7 Special-Touch-Handler: CursorSwipe / Backspace-Swipe / Enter-Overlay

Drei Touch-Handler haben State-Maschinen, die nicht in Catalog-Slots passen (Slot ist auf `setOnClickListener` zentriert). Lösung: einmalig im `attach()`-Callback verdrahten, **nicht** pro Render.

#### CursorSwipeTouchHandler (space_btn)

```kotlin
private fun buildSpaceTouchHandler(): View.OnTouchListener {
    val space = buttonViews[LogicalButtonId.SPACE] as MaterialButton
    val swipeHandler = CursorSwipeTouchHandler(
        swipeThresholdPx = CursorSwipeTouchHandler.DEFAULT_SWIPE_THRESHOLD,
        onTap = {
            onVibrate()
            inputConnectionProvider()?.commitText(" ", 1)
        },
        onCursorMove = { dir ->
            onVibrate()
            inputConnectionProvider()?.commitText("", if (dir > 0) 2 else -1)
        },
        onSwipeStateChanged = { isSwiping ->
            if (isSwiping) {
                space.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_baseline_keyboard_double_arrow_left_24, 0,
                    R.drawable.ic_baseline_keyboard_double_arrow_right_24, 0)
            } else {
                space.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            }
        },
        consumeTouchEvents = false,
    )
    return View.OnTouchListener { v, event ->
        keyPressAnimator.handlePressAnimationEvent(v, event)
        if (inputConnectionProvider() == null) {
            space.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            return@OnTouchListener false
        }
        swipeHandler.onTouch(v, event)
    }
}
```

#### BackspaceSwipeHandler (backspace_btn)

```kotlin
private fun buildBackspaceSwipeHandler(): View.OnTouchListener =
    BackspaceSwipeHandler(
        inputConnectionProvider = inputConnectionProvider,
        vibrate = onVibrate,
        onDeleteCancelled = { /* siehe MainButtonsController:189-193 — wird vom Service gehandled.
                                 Kein Action-Emit nötig: Cancel ist eine view-lokale Animation,
                                 keine State-Mutation. R.3 — nullable-Resolver-Idiom. */ },
        keyPressAnimationHandler = { v, e -> keyPressAnimator.handlePressAnimationEvent(v, e) },
    )
```

#### EnterOverlayHandler (enter_btn)

```kotlin
private fun buildEnterOverlayHandler(): View.OnTouchListener =
    EnterOverlayHandler(
        overlayCharactersLl = rootView.findViewById(R.id.overlay_characters_ll),
        inputConnectionProvider = inputConnectionProvider,
        accentColorProvider = accentColorProvider,
        keyPressAnimationHandler = { v, e -> keyPressAnimator.handlePressAnimationEvent(v, e) },
    )
```

**Überlegung:** Touch-Handler werden im `attach()` einmalig verdrahtet (L9). Sie sind state-frei, brauchen kein Re-Wiring per Render. Wenn `onTap` einen `Action` emittieren soll, geht das über den Backend-`onAction`-Field — gleiches Pattern wie Click-Listener (§11.6).

**Special:** der heutige `EnterOverlayHandler` mutiert `overlayCharactersLl.visibility = GONE` direkt (Z. 56, 62). Das ist im neuen System überflüssig — die Visibility wird vom `KeyboardStateManager.applyVisibility` jeweils auf GONE gesetzt (Z. 162). Trotzdem: lokale Reset-Logik im Handler bleibt (defensive depth) — kein Bug.

### §11.8 Migration-Reihenfolge

<!-- FIX: Issue 2.1.17 / R.13 – KSM-Übergangs-State: leerer Body in 5c, KSM-Löschung in 5d, Strict-Mode-Logging -->
```
[Block 4] Manager + Catalog (parallel zu Block 5 möglich, aber reine Daten)
   │
   ├─► [Block 5a] MotionScene-XML schreiben + Layout-XML refactoren (XML-Welt isoliert)
   │
   ├─► [Block 5b] ImeViewBackend implementieren (kann Mock-DictateUiState verwenden)
   │     ├── wireStaticHandlers (Click-Listener-Setup)
   │     ├── Touch-Handler (CursorSwipe/Backspace/Enter)
   │     ├── ContentAreaController + PromptVisibilityController + OverlayResetHandler (R.10)
   │     └── RecordingAnimationController extrahieren
   │
   ├─► [Block 5c] Service-Wiring: KeyboardLayoutManager + ImeViewBackend instanziieren,
   │     `pipelineService.state.collect { manager.onStateChanged(it) }`.
   │     **KSM-Methoden** (`applyRecordingControlsVisibility`, `applyContentAreaVisibility`,
   │     `applyPromptsVisibility`) bekommen **leere Bodies** (no-op). KSM.refresh ruft sie
   │     weiter auf, ohne dass etwas passiert; Manager (R.10-Owner-Klassen) übernimmt die
   │     Visibility-Mutationen. **Strict-Mode-Logging** verifiziert, dass keine zwei Subsysteme
   │     gleichzeitig auf einer Visibility-Achse schreiben (Acceptance §10).
   │
   └─► [Block 5d] Cleanup: KeyboardLayoutModeController, MainButtonsController-Click-Logic,
         KSM komplett (inkl. der drei leeren Methoden + alle Aufrufer in MainButtonsController),
         RecordingUiController.applyXxxState löschen. **Nur gemacht, wenn 5c funktioniert!**
```

**Begründung der Reihenfolge:**
- 5a/5b parallelisierbar, da sie auf Mock-State testbar sind.
- **5c hat leere KSM-Bodies** (R.13 Option A+C): eliminiert die Doppelmutation strukturell;
  Strict-Mode-Log macht Acceptance verifizierbar.
- 5d ist destruktiv — strikt am Ende, KSM-Klasse verschwindet vollständig.

**Zeilen-Budget pro Block:**
- 5a (XML): ~400 Zeilen Scene + ~80 Zeilen Layout-Patch.
- 5b (Backend): ~250 Zeilen ImeViewBackend + ~80 Zeilen RecordingAnimationController.
- 5c (Wiring): ~30 Zeilen im Service.
- 5d (Cleanup): ~370 Zeilen löschen (KLMC 273 + KSM-Anteil 30 + RUC-Anteil 80 + Service resend-mutations 5).

**Netto-Zeilen-Bilanz:** ~+760 neu, ~−400 gelöscht → +360 Zeilen. Aber: dramatisch geringere Komplexität (deklarativ statt imperativ, Single-Owner, kein Re-Parenting).

---

## §12 Referenzen

### Phase-2-Recherchen (Eingangs-Material)

- [main-button-area-inventory.md](../main-button-area-inventory.md) — vollständige Capability-Inventur.
- [motionlayout-architecture-options.md](../motionlayout-architecture-options.md) — Begründung MotionLayout-Wahl.
- [_pending-layout-container-architecture.md](../_pending-layout-container-architecture/_pending-layout-container-architecture.md) — bestätigt MotionLayout, identifiziert Spike-Punkte.
- [_pending-state-machine-visibility-owners.md](../_pending-state-machine-visibility-owners/_pending-state-machine-visibility-owners.md) — vollständige Visibility-Mutation-Map.

### Code-Pointer (Heute)

- `app/src/main/res/layout/activity_dictate_keyboard_view.xml`
- `app/src/main/java/net/devemperor/dictate/core/KeyboardLayoutModeController.kt`
- `app/src/main/java/net/devemperor/dictate/core/MainButtonsController.kt`
- `app/src/main/java/net/devemperor/dictate/core/KeyboardStateManager.kt`
- `app/src/main/java/net/devemperor/dictate/core/RecordingUiController.kt`
- `app/src/main/java/net/devemperor/dictate/core/KeyboardUiController.kt` (Pipeline-State + refreshRecordButtonFromState)
- `app/src/main/java/net/devemperor/dictate/widget/PulseLayout.kt`
- `app/src/main/java/net/devemperor/dictate/widget/BorderGlowAnimation.kt`
- `app/src/main/java/net/devemperor/dictate/keyboard/EnterOverlayHandler.kt`
- `app/src/main/java/net/devemperor/dictate/keyboard/CursorSwipeTouchHandler.kt`
- `app/src/main/java/net/devemperor/dictate/keyboard/BackspaceSwipeHandler.kt`

### Externe Referenzen

- MotionLayout Doku: https://developer.android.com/develop/ui/views/animations/motionlayout
- MotionScene XML: https://developer.android.com/reference/androidx/constraintlayout/motion/widget/MotionScene
- VISIBILITY_MODE_IGNORE: https://stackoverflow.com/questions/57889399/motionlayout-ignore-visibility

---

## §13 Vollständigkeits-Verifikation

**User-Anforderung:** vollständige Zentralisierung von State und Funktionalität, mit konsequenter SOLID/DRY-Anwendung.

### §13.1 Visibility-Mutation-Audit

Alle heutigen `\.visibility =` und `setVisibility(...)`-Calls aus dem `core/`/`keyboard/`/`widget/`-Package, die UI-relevant für die Main-Button-Area sind. Quelle: [§1 Visibility-Mutation-Map in _pending-state-machine-visibility-owners.md](../_pending-state-machine-visibility-owners/_pending-state-machine-visibility-owners.md).

| # | View | file:line | Status nach Refactor |
|---|---|---|---|
| 1 | `mainButtonsClTyped` | KeyboardStateManager.kt:172 | **BLEIBT** — gehört zur orthogonalen ContentArea-Achse (Main-Button-Container vs. QWERTZ vs. Emoji), nicht Teil des LayoutCatalog. Wird in einen `ContentAreaController` extrahiert (Block 5d Cleanup). |
| 2 | `editButtonsLl` | KeyboardStateManager.kt:174 | **BLEIBT** (ContentArea-Achse) |
| 3 | `qwertzContainer` | KeyboardStateManager.kt:177 | **BLEIBT** (ContentArea-Achse) |
| 4 | `emojiPickerCl` | KeyboardStateManager.kt:179 | **BLEIBT** (ContentArea-Achse) |
| 5 | `pauseButton` | KeyboardStateManager.kt:187 | **WANDERT** in `predPauseVisible` (KEYBOARD_TWO_ROW + KEYBOARD_SINGLE_ROW + KEYBOARD_REPROCESS_STAGING, PAUSE-Slot) |
| 6 | `trashButton` | KeyboardStateManager.kt:191 | **WANDERT** in `predTrashVisible` (alle KEYBOARD-Modes, TRASH-Slot) |
| 7 | `promptsCl` | KeyboardStateManager.kt:206 | **BLEIBT** (Promptbar-Achse, eigenes Subsystem; nicht Teil von Spec 2) |
| 8 | `promptsRv` | KeyboardStateManager.kt:210 | **BLEIBT** (Promptbar) |
| 9 | `pipelineProgressLl` | KeyboardStateManager.kt:212 | **BLEIBT** (Promptbar) |
| 10 | `promptRecordingControlsLl` | KeyboardStateManager.kt:218 | **BLEIBT** (Promptbar) |
| 11 | `overlayCharactersLl` (Reset) | KeyboardStateManager.kt:162 | **BLEIBT** (defensive Reset des transient overlays) |
| 12 | `overlayCharactersLl` (Long-Press-Open) | MainButtonsController.kt:251 | **WANDERT** in den Long-Click-Handler von ENTER (in `wireStaticHandlers`) |
| 13 | `overlayCharactersLl` (per-Slot) | MainButtonsController.kt:485,487 | **BLEIBT** (Theme-internal, separate Animations-/Theme-Klasse) |
| 14 | `overlayCharactersLl` (Touch) | EnterOverlayHandler.kt:56,62 | **BLEIBT** (Touch-Handler-internal — siehe §11.7) |
| 15 | `infoCl.dismiss` | InfoBarController.kt:49 | **BLEIBT** (InfoBar-internal) |
| 16 | `infoCl.show` | InfoBarController.kt:57 | **BLEIBT** (InfoBar-internal) |
| 17 | `infoCl` direct mutation | KeyboardUiController.kt:241 | **BEHEBT** als Sekundär-Cleanup: ersetzen durch `infoBarController.dismiss()` |
| 18-20 | `infoYesButton/infoNoButton` | InfoBarController.kt:65-163 | **BLEIBT** (InfoBar-internal) |
| 20 | Pipeline-Step-Row binding | KeyboardUiController.kt:383-448 | **BLEIBT** (Pipeline-Step-internal, Promptbar) |
| 21 | `inputRow` | KeyboardLayoutModeController.kt:133 | **ENTFERNT** — `inputRow` existiert nicht mehr (L2, flache Hierarchie) |
| 22 | `audioFocusButtonInRow` | KeyboardLayoutModeController.kt:138 | **WANDERT** in AUDIO_FOCUS-Slot Predicate (`true` in SINGLE_ROW + SINGLE_ROW_SEND_MODE, `false` sonst) |
| 22b | `widget_toggle_btn` | NEU (Spec 3 OPEN-2 / Phase-1 1.0.2 / Spec 1 §15 OverlayModule) | **NEU** — LayoutCatalog `WIDGET_TOGGLE`-Slot, Predicate `{ state.viewMode == ViewMode.KEYBOARD }`. Sichtbar im IME-View, dispatcht Toggle zum WIDGET-Modus. <!-- FIX: Issue 3.0.12 – WIDGET_TOGGLE in §13.1 nachgepflegt (Phase-1-1.0.2-Followup) --> |
| **23** | **`resendButton` (Idle)** | **RecordingUiController.kt:137** | **ENTFERNT** — `predResendVisible` (RESEND-Slot) übernimmt |
| **24** | **`resendButton` (Active)** | **RecordingUiController.kt:158** | **ENTFERNT** — Predicate übernimmt |
| **25** | **`resendButton` (onStartInputView V)** | **DictateInputMethodService.java:1345** | **ENTFERNT** — Predicate übernimmt |
| **26** | **`resendButton` (onStartInputView G)** | **DictateInputMethodService.java:1347** | **ENTFERNT** — Predicate übernimmt |
| **27** | **`resendButton` (Pipeline-Start)** | **DictateInputMethodService.java:1669** | **ENTFERNT** — Predicate übernimmt (Pipeline=Preparing → predResendVisible=false) |
| **28** | **`resendButton` (onShowResend)** | **DictateInputMethodService.java:1839** | **ENTFERNT** — wird zu `pipelineService.markLastAudioExists(true)` State-Update |

**Verifikation:** alle 27 in `_pending-state-machine-visibility-owners.md` §1 gelisteten Mutationen sind explizit adressiert. Die 5+ problematischen `resend_btn`-Mutationen (#23-#28) sind alle ENTFERNT zugunsten **eines** Predicates `predResendVisible` (siehe §8.5).

### §13.2 Click-Listener-Audit

Alle heutigen Click-/Long-Click-/Touch-Handler-Setups in `MainButtonsController.kt` (siehe `MainButtonsController.kt:155-260`) und ihre Migration:

| Heute (file:line) | Slot/Handler im Refactor |
|---|---|
| `recordButton.setOnClickListener(recordClickListener)` (Z. 160) | `RECORD.actionResolver` → resolveRecordAction (StartRecording / StopRecordingAndSend) |
| `recordButton.setOnLongClickListener` (Z. 163) | wireStaticHandlers (RECORD long-click) — Long-Click konsumiert (`return true`), kein Action-Emit (R.3 — nullable-Resolver-Idiom; heutiges `onRecordLongClicked` ist Settings-Open via Activity-Intent, keine State-Mutation) |
| `resendButton.setOnClickListener` (Z. 170) | `RESEND.actionResolver` → Action.ResendAction.ResendLastAudio |
| `resendButton.setOnLongClickListener` (Z. 174) | wireStaticHandlers (RESEND long-click) → Action.ResendAction.ResendLastAudioLong |
| `backspaceButton.setOnClickListener` (Z. 181) | `BACKSPACE.actionResolver` → Action.KeyboardInputAction.Backspace |
| `backspaceButton.setOnLongClickListener` (Z. 185) | wireStaticHandlers (BACKSPACE long-click) → konkrete Long-Action (Auto-Delete) |
| `backspaceButton.setOnTouchListener(BackspaceSwipeHandler)` (Z. 189) | `buildBackspaceSwipeHandler()` (§11.7) — einmalig in attach() |
| `trashButton.setOnClickListener` (Z. 197) | `TRASH.actionResolver` → resolveTrashAction (CancelRecording / CancelReprocessStaging) |
| `spaceButton.setOnTouchListener` mit CursorSwipeTouchHandler (Z. 225) | `buildSpaceTouchHandler()` (§11.7) |
| `pauseButton.setOnClickListener` (Z. 235) | `PAUSE.actionResolver` → resolvePauseAction (PauseRecording / ResumeRecording) |
| `audioFocusButton.setOnClickListener(audioFocusClickListener)` (Z. 242) | `AUDIO_FOCUS.actionResolver` → Action.AudioAction.ToggleAudioFocusPref |
| `editAudioFocusButton.setOnClickListener(audioFocusClickListener)` (Z. 124) | **BLEIBT** — Edit-Bar-Audio-Focus-Btn ist **außerhalb** der Main-Button-Area (separate Achse, separate Klasse) |
| `enterButton.setOnClickListener` (Z. 245) | `ENTER.actionResolver` → Action.KeyboardInputAction.EnterKey |
| `enterButton.setOnLongClickListener` (Z. 249) | wireStaticHandlers (ENTER long-click) — öffnet `overlay_characters_ll` (View-Mutation, kein Action) |
| `enterButton.setOnTouchListener(EnterOverlayHandler)` (Z. 254) | `buildEnterOverlayHandler()` (§11.7) |
| **WIDGET_TOGGLE — NEU (Spec 3 OPEN-2)** | NEU — kein heutiger Migration-Source. `WIDGET_TOGGLE.actionResolver` → `Action.ViewModeAction.ToggleViewModeWidget` (siehe Spec 3 §7.3 T1 + §13.4). <!-- FIX: Issue 3.0.12 – WIDGET_TOGGLE in §13.2 nachgepflegt --> |
| `editNumbersButton.setOnClickListener` (Z. 102) | **BLEIBT** in EditBar-Controller — `Action.LayoutAction.ToggleSmallMode` wird emittiert <!-- FIX: Issue 3.0.5 – flache Action.ToggleSmallMode → hierarchisch --> |
| `editNumbersButton.setOnLongClickListener` (Z. 112) | **BLEIBT** in EditBar-Controller — `Action.LayoutAction.ToggleSingleRowMode` <!-- FIX: Issue 3.0.5 --> |
| `editSettingsButton/editHistoryButton/pipelineCancelBtn` (Z. 118-120) | **BLEIBT** in EditBar-Controller — separate Achse |
| `editKeyboardButton` (Z. 126/131) | **BLEIBT** in EditBar-Controller — `Action.LayoutAction.SetContentArea(QWERTZ)` (ehemals "ToggleQwertz", post-F-11 als ContentArea-Variante kanonisch in §3.3 modelliert) <!-- FIX: Issue 3.0.5 - Pre-F-11-„ToggleQwertz" auf SetContentArea-Variante umgestellt --> |
| Edit actions undo/redo/cut/copy/paste (Z. 138-150) | **BLEIBT** in EditBar-Controller |
| Emoji-Listener (Z. 264-280) | **BLEIBT** in EmojiController |

**Befund:** alle 9 Main-Button-Area-Click-Handler wandern in `actionResolver`-Slots. Edit-Bar (außerhalb der Main-Button-Area) bleibt in einem separaten `EditBarController`, der sich nicht ändert. Click-Listener werden einmalig in `wireStaticHandlers` verdrahtet (L8 / §11.6) — pro Render-Tick keine neue Lambda-Allocation.

### §13.3 SOLID-Verifikation pro neue Klasse

#### KeyboardLayoutManager

- **SRP (Single Responsibility):** Render-Orchestration. Subscribiert StateFlow, wählt LayoutMode aus dem Catalog, ruft `backend.render`. Keine Visibility-Berechnung selbst, keine View-Manipulation. ✓
- **OCP (Open/Closed):** neue LayoutModes werden via Catalog-Erweiterung hinzugefügt. Manager-Code unverändert. Voraussetzung: `forKeyboard` liefert den passenden LayoutMode für jeden State (offen für neue State-Achsen). ✓
- **LSP:** N/A (keine Vererbung).
- **ISP:** Manager kennt nur `RenderBackend`-Interface mit 3 Methoden. ✓
- **DIP (Dependency Inversion):** depend on `RenderBackend`-Interface, nicht auf `ImeViewBackend`/`OverlayBackend` direkt. Implementiert via Constructor-Injection eines `onAction`-Callbacks. ✓

#### LayoutCatalog

- **SRP:** Datenregistry — definiert die 5 KEYBOARD-LayoutModes + `forKeyboard(state)`-Selektion. **Kein Verhalten** außer der Selektion. ✓
- **DRY-Beweis (siehe §13.4):** alle Predicates und Resolvers leben **außerhalb** der LayoutMode-Definitionen, werden referenziert. **Keine** doppelte Logik in zwei LayoutModes. ✓
- **Typ-Bewertung:** sollte `LayoutCatalog` Verhalten haben? — **Nein.** Predicates und Resolvers gehören zu Slots (data members des `ButtonSlot`); Catalog ist ein `object` (Singleton-Daten-Container). Verifikation: keine Methoden auf `LayoutCatalog` außer `forKeyboard(state): LayoutMode` (das ist eine reine Daten-Selektion, keine View-Manipulation). ✓

#### ImeViewBackend

- **SRP:** rendert auf IME-View. Übersetzt LogicalButtonId zu Android-View via `findViewById`-Map, applied Slot-Properties, dispatcht Click-Events. Kein Pipeline-Wissen. ✓
  - Verifikation: ImeViewBackend kennt `DictateUiState` als opaken Datentyp (er ruft `slot.visibilityPredicate(state)` auf, ohne State-Internas zu lesen). ✓
- **OCP:** neue Slots werden via LayoutMode-Erweiterung aufgenommen, vorausgesetzt die `LogicalButtonId` ist im `buttonViews`-Map vorhanden. Wenn nein: 1-Zeilen-Erweiterung. ✓
- **DIP:** depend on `RenderBackend`-Interface. Receives `inputConnectionProvider`, `keyPressAnimator`, `recordingAnimationController` als Constructor-Parameter (nicht direkt instanziiert). ✓

#### ButtonSlot

- **Datenklasse:** alle Eigenschaften sind `val` (Immutable). Keine Methoden außer Resolver-Aufrufe (die sind Lambdas, nicht Verhalten der Klasse). ✓
- Verifikation: `ButtonSlot` hat **kein** Verhalten, das State mutiert. Resolver lesen nur. ✓

#### RecordingAnimationController

- **SRP:** koordiniert die zwei Animations-Subsysteme (BorderGlowAnimation + PulseLayout) gegen den `RecordingState`. Keine View-Mutation außer der zwei Animation-APIs. ✓
- **Cache-Optimierung (`lastRecordingState`):** Performance-Guard, kein State-Owner.

#### KeyboardLayoutModeController, MainButtonsController, RecordingUiController.applyXxxState

- **GELÖSCHT** (KeyboardLayoutModeController) bzw. **REDUZIERT** (MainButtonsController-Click-Logik, RecordingUiController-Apply-Methoden).

### §13.4 DRY-Verifikation

#### Two-Row und Single-Row: gemeinsame Predicate-Definition

| Slot | Two-Row Predicate | Single-Row Predicate | Identisch? |
|---|---|---|---|
| RECORD | `{ true }` | `{ true }` | ✓ |
| RESEND | `predResendVisible` | `predResendVisible` | ✓ — **gleiche Funktion** |
| BACKSPACE | `{ true }` | `{ true }` | ✓ |
| AUDIO_FOCUS | `{ false }` | `{ true }` | absichtlich verschieden (Definition des Modes) |
| TRASH | `predTrashVisible` | `predTrashVisible` | ✓ — **gleiche Funktion** |
| SPACE | `{ true }` | `{ true }` | ✓ |
| PAUSE | `predPauseVisible` | `predPauseVisible` | ✓ — **gleiche Funktion** |
| ENTER | `{ true }` | `{ true }` | ✓ |

**Beweis:** identische Visibility-Logik wird über drei top-level Funktionen (`predResendVisible`, `predTrashVisible`, `predPauseVisible`) **einmal** definiert und an **allen Stellen** referenziert. Diese drei Funktionen haben jeweils EINE Definition (in §8.5). Keine Duplikation.

#### Send-Mode-Varianten: Gemeinsamkeiten und Unterschiede

| Slot | TWO_ROW_SEND_MODE | SINGLE_ROW_SEND_MODE | Gemeinsam |
|---|---|---|---|
| RECORD textResolver | `resolveRecordButtonTextPipeline` | `resolveRecordButtonTextPipeline` | ✓ |
| RECORD enabledResolver | `{ it.pipeline !is Preparing }` | `{ it.pipeline !is Preparing }` | ✓ |
| RECORD actionResolver | `resolveRecordActionPipeline` | `resolveRecordActionPipeline` | ✓ |
| RESEND visibility | `{ false }` | `{ false }` | ✓ |
| TRASH visibility | `{ false }` | `{ false }` | ✓ |
| PAUSE visibility | `{ false }` | `{ false }` | ✓ |
| BACKSPACE/SPACE/ENTER | `{ true }` | `{ true }` | ✓ |
| AUDIO_FOCUS visibility | `{ false }` | `{ true }` | absichtlich verschieden (Definition Single-Row) |

**Was unterscheidet sich:** **nur** AUDIO_FOCUS-Visibility. Dies ist **die einzige Achse**, die TWO_ROW von SINGLE_ROW unterscheidet — Logik konsistent zwischen Standard- und Send-Mode-Varianten.

**Mögliche weitere DRY-Optimierung (zu validieren durch Spike):** statt 4 LayoutMode-Konstanten (TWO_ROW, SINGLE_ROW, TWO_ROW_SEND, SINGLE_ROW_SEND) ein einziger `LayoutMode` mit konditionalen Predicates, der `state.layout.singleRowMode` und `state.pipeline` direkt liest. **Trade-off:** weniger Konstanten, aber komplexere Predicates pro Slot. Empfehlung: **bleibt bei 4 Konstanten** — Lesbarkeit > Code-Compactness, und die DRY-Pflicht ist bereits durch geteilte Predicate/Resolver-Funktionen erfüllt.

#### Resolver-DRY in mehreren Slots

| Resolver | Verwendet in |
|---|---|
| `resolveRecordButtonText` | RECORD-Slot in TWO_ROW + SINGLE_ROW |
| `resolveRecordButtonTextPipeline` | RECORD-Slot in TWO_ROW_SEND + SINGLE_ROW_SEND |
| `resolveRecordButtonTextStaging` | RECORD-Slot in REPROCESS_STAGING |
| `resolveTrashAction` | TRASH-Slot in TWO_ROW + SINGLE_ROW (REPROCESS_STAGING hat eigenen `Action.PipelineAction.CancelReprocessStaging`-Direkt-Wert, weil semantisch festgelegt) |
| `resolvePauseAction` | PAUSE-Slot in TWO_ROW + SINGLE_ROW |
| `resolvePauseIcon` | PAUSE-Slot in TWO_ROW + SINGLE_ROW |

**Befund:** Resolver werden konsistent geteilt, wo die Logik identisch ist. Es gibt **keine** Slot-Definition, in der ein Resolver inline definiert ist und eine identische Logik in einem anderen Slot ebenfalls inline existiert.

#### Slot-Apply-Helper für beide Backends (F-7 / DRY)

| Was | Heute | Künftig (eine Quelle) |
|---|---|---|
| Slot → View-Properties (visibility/enabled/alpha/icon/text) | dupliziert in `ImeViewBackend.applySlotProperties` (§6) und `OverlayBackend.applySlots` (Spec 3 §4.2) — 2× sieben Zeilen identische Setter-Logik | Top-Level-Funktion `applySlotToView(slot, view, state, ctx)` in `keyboard/render/SlotRenderer.kt` (§5.1). Beide Backends rufen sie auf. |

**DRY-Beweis:** Wenn die Slot-Property-Mapping um `contentDescription`, `tint` oder eine andere Achse erweitert wird, gibt es **eine** Stelle, an der das Setter-Pattern lebt — nicht zwei. Dadurch ist eine Drift zwischen beiden Backends strukturell ausgeschlossen.

#### AudioFocus-Icon-Resolver (F-4 / DRY)

| Was | Heute | Künftig (eine Quelle) |
|---|---|---|
| `audioFocusEnabled` → Icon-Resource | dupliziert: einmal in `MainButtonsController.kt:368-387` für die Main-Button-Area, einmal für `edit_audio_focus_btn` in der EditBar | Top-Level-Funktion `resolveAudioFocusIcon(enabled)` in §8.5. Slot AUDIO_FOCUS verwendet sie über `iconResolver`; `EditBarController.refreshAudioFocusIcon` ruft sie ebenfalls. |

**DRY-Beweis:** Beide Konsumenten (Slot UND EditBar) lesen aus demselben StateFlow UND mappen über dieselbe Funktion. Wenn das Icon-Set wechselt (z.B. Material-Symbols statt Material-Icons), ist genau eine Stelle anzupassen.

### §13.5 Identified Gaps + Mitigations

<!-- FIX: Issue 3.0.7 – §13.5 in drei Bereiche gegliedert (Open / Cross-Spec-Pending / Resolved); Audit-Funktion wieder klar -->

Bei der Verifikation aufgedeckte Punkte, die im Plan ergänzt werden sollten:

#### §13.5.a Open Gaps

(Gap 3 — ContentArea-Achse — bleibt offen als Implementations-Hinweis. Gap 5 — Migration-Reihenfolge — bleibt offen als koordinations-Hinweis. Beide unten in §13.5.a-Abschnitten.)

#### §13.5.b Cross-Spec Patches Pending

(Phase-1-Apply hat alle bekannten Cross-Spec-Patches eingearbeitet. Aktuell offen: WIDGET_TOGGLE-Slot-Position im KEYBOARD_TWO_ROW/SINGLE_ROW LayoutMode — siehe Spec 3 §13.5 GAP-4 Mitigation; finale Slot-Position wird im Apply zu Spec 2 §3.1/§8.x ergänzt, sobald Spec-3-Layout-Position fixiert ist.)

#### §13.5.c Resolved (Iter-History)

(Gap 1 — Edit-Bar-Audio-Focus-Btn — RESOLVED via F-4. Gap 2 — Resend-Cooldown — RESOLVED via §3-Erweiterung + Phase-2-2.0.12-Inline-Doku in §8.5. Gap 4 — BorderGlow-Animation — RESOLVED via reaktive Animation-Bindung an `state.recording`.)

---

**Gap 1: Edit-Bar-Audio-Focus-Btn (`edit_audio_focus_btn`) ist nicht im LayoutCatalog** — *Status: RESOLVED (§13.5.c)*

- **Heute:** Edit-Bar hat eine eigene Audio-Focus-Variante (`edit_audio_focus_btn` in der `editButtonsLl`), die immer sichtbar ist. Die heutige `MainButtonsController.refreshAudioFocusIcon` (Z. 368-387) synchronisiert beide Buttons.
- **Refactor:** Edit-Bar ist **nicht** Teil der Main-Button-Area, daher nicht im LayoutCatalog. Die Edit-Bar-Variante des Audio-Focus-Btn bleibt in einem eigenen `EditBarController`.
- **RESOLVED via F-4 (Iteration 2026-05-08):** Der gemeinsame Helper `resolveAudioFocusIcon(enabled)` (§8.5) wird **sowohl** vom AUDIO_FOCUS-Slot **als auch** vom EditBarController genutzt. Beide hören auf denselben StateFlow UND mappen über dieselbe Funktion → echte SSOT, keine zwei Code-Pfade mehr. (Vorherige Mitigation "beide hören auf StateFlow" eliminierte zwar die Sync-Race, aber nicht die Code-Duplikation der Mapping-Funktion — F-4 schließt diese letzte Lücke.)

**Gap 2: `setResendEnabled(false)` Cooldown nach Resend-Klick (500ms)** — *Status: RESOLVED (§13.5.c) via §3-`resendCooldown`-Erweiterung + Phase-2-2.0.12-Inline-Doku in §8.5*

- **Heute:** `MainButtonsController.kt:331-333` deaktiviert den Resend-Btn temporär, um Doppelklicks zu verhindern. Trigger: `Service.onResendClicked` → 500ms-Cooldown.
- **Refactor:** das ist eine **transient State**-Achse (`resendCooldown: Boolean`). Lösung: DictateUiState bekommt einen Boolean `resendCooldown`, der vom Service nach Click auf `true` gesetzt und nach 500ms via `Handler.postDelayed` zurückgesetzt wird. `enabledResolver` für RESEND-Slot liest beide: `{ !state.resendCooldown }`.
- **Mitigation:** DictateUiState (Spec 1, §3) muss um `resendCooldown` erweitert werden. **Aktion:** Eintrag in §3 von Spec 1 oder im Hauptplan vermerken.

**Gap 3: ContentArea-Achse vs. LayoutCatalog** — *Status: Open (§13.5.a) — Implementations-Hinweis*

- **Heute:** ContentArea (MAIN_BUTTONS / QWERTZ / EMOJI_PICKER) ist **orthogonal** zum LayoutMode. Wenn ContentArea ≠ MAIN_BUTTONS, ist das gesamte `mainButtonsClTyped` GONE — alle Slots werden unsichtbar.
- **Refactor:** der LayoutCatalog deckt nur den Fall ContentArea = MAIN_BUTTONS ab. Der `ContentAreaController` (extrahiert aus `KeyboardStateManager.applyContentAreaVisibility`) setzt den **Container** auf GONE.
- **Mitigation:** keine Dopplung — der Catalog rendert Slot-Properties immer, aber wenn der Container GONE ist, ist das visuell egal (kein zusätzlicher Aufwand). **Mögliche Optimierung:** im `ImeViewBackend.render` ein Early-Return wenn `state.contentArea != ContentArea.MAIN_BUTTONS`. Trade-off: Performance-Win vs. Inkonsistenz, wenn ContentArea zurück zu MAIN_BUTTONS wechselt — der Backend müsste dann einen Re-Render forcieren. **Empfehlung:** kein Early-Return, immer rendern (idempotent). Performance ist OK, weil die Slot-Properties auf einer GONE-View kein Layout-Pass triggern.

**Gap 4: BorderGlow-Animation während Pipeline (Send-Mode)** — *Status: RESOLVED (§13.5.c)*

- **Heute:** BorderGlowAnimation läuft während Recording (Active/Paused). Beim Übergang zu Pipeline-Running wird sie via `RecordingUiController.applyIdleState` → `recordingAnimation.cancel()` gestoppt. Aber: der record_btn zeigt im Pipeline-Mode "Sende… 2/3 0:08", die Animation ist GONE — die Live-Amplitude existiert ja auch nicht mehr.
- **Refactor:** der `RecordingAnimationController` (§11.5) reagiert auf `state.recording`. Im Pipeline-Mode ist `state.recording = Idle` → Animation gecancelt. Konsistent mit heute. ✓
- **Keine Aktion nötig.**

**Gap 5: Migration-Reihenfolge versus Hauptplan-Block-Reihenfolge** — *Status: Open (§13.5.a) — Koordinations-Hinweis*

- Der Hauptplan listet 6 Blöcke (1: State-SSOT, 2: Foreground-Service, 3: DB, 4: Manager+Catalog, 5: ImeViewBackend, 6: OverlayBackend).
- Block 1 (State-SSOT-Konsolidierung in Spec 1) muss **vor** Block 4 (Manager) abgeschlossen sein, weil der Manager `DictateUiState` mit korrekter Ownership erwartet (kein hybrider resend_btn-Zustand mehr).
- **Mitigation:** Block 1 implementiert die `predResendVisible`-Konsolidierung **bereits** vor dem Refactor — das eliminiert die 6-Mutator-Race **innerhalb des heutigen Codes**, ohne MotionLayout. Block 5 entfernt dann die letzten Reste (KeyboardLayoutModeController + die jetzt zentralisierte `RecordingUiController.applyXxxState`). Diese Reihenfolge ist konsistent — explizit dokumentiert in Hauptplan §4 und Spec 1 §10 / Spec 2 §11.8.

---

## §14 Test-Strategie

### §14.1 Heutige Layout-Tests

Aktueller Test-Bestand (annahme-basiert; konkrete Test-Inventur durchzuführen in Block 4):

| Klasse | Heutige Tests | Bricht durch Refactor? |
|---|---|---|
| KeyboardLayoutModeController | unit-tests für `setSingleRowMode`, `rehome`, `buildSingleRowConstraintSet` | **JA** — Klasse wird gelöscht. Tests entfallen. |
| KeyboardStateManager | unit-tests für `applyVisibility`-Cascade, `setSmallMode`, `setContentArea` | **TEILWEISE** — Visibility-Cascade-Tests entfallen für recordingControls (resend/pause/trash). ContentArea-Tests bleiben. |
| RecordingUiController | unit-tests für `applyIdleState/Active/Paused` | **JA** — Methoden gelöscht. Tests werden zu LayoutCatalog-Predicate-Tests umgeschrieben. |
| MainButtonsController | unit-tests für Click-Handler-Routing (Mock-Callback) | **JA** — Click-Routing wandert in ImeViewBackend. Tests werden zu Backend-Tests. |
| LayoutCatalog | **NEU** — keine bestehenden Tests | — |
| ImeViewBackend | **NEU** — keine bestehenden Tests | — |
| KeyboardLayoutManager | **NEU** — keine bestehenden Tests | — |

### §14.2 Neue Tests (Unit + Integration)

**Unit-Tests (LayoutCatalog):**
- Pro LayoutMode (5 Stück) × pro Slot (8 Stück): Predicate-Verifikation gegen tabulierte DictateUiState-Permutationen.
- Beispiel-Test:
```kotlin
@Test fun `predResendVisible is true only in Idle with lastAudio and resendEnabled`() {
    val base = stateBuilder().build()
    assertFalse(predResendVisible(base.copy(recording = RecordingState.Active(false))))
    assertFalse(predResendVisible(base.copy(pipeline = PipelineUiState.Preparing)))
    assertFalse(predResendVisible(base.copy(lastAudioExists = false)))
    assertFalse(predResendVisible(base.copy(resendEnabled = false)))
    assertTrue(predResendVisible(base))
}
```

**Unit-Tests (KeyboardLayoutManager):**
- `forKeyboard(state)` → richtige LayoutMode pro State-Permutation (Idle / Recording / Pipeline / ReprocessStaging × Two-Row/Single-Row).
- `onStateChanged` triggert `backend.render(state, mode)` mit den korrekten Argumenten (Mock-Backend).

**Integration-Tests (Espresso):** <!-- FIX: Issue 3.0.9 – Reverse-Pointer pro Test auf Hauptplan §1.1 Bug-Symptom-Spalte; UI-Tests 8/9/10 ergänzt -->

| Test | Beschreibung | deckt Bug-Symptom |
|---|---|---|
| **UI-Test 1** | Toggle Single-Row im Idle. Verify alle 8 Buttons sichtbar, Layout entspricht Single-Row-State. | §1.1 #1 |
| **UI-Test 2** | Recording starten → Verify resend GONE, trash/pause VISIBLE. | — (Coverage-Baseline) |
| **UI-Test 3** | Recording-Stop → Pipeline → Verify record_btn-Text ist "Sende…" / "1/3 0:01" (Counter), trash/pause GONE. | — (Coverage-Baseline) |
| **UI-Test 4** | Send-Mode + Single-Row → Verify Send-Button vollständig sichtbar (kritischer Bug-Fix-Verifikator). | **§1.1 #3a** |
| **UI-Test 5** | ReprocessStaging → Verify pause_btn VISIBLE+disabled+alpha 0.4. | — (Coverage-Baseline) |
| **UI-Test 6** | Re-Inflate (Rotation) während Recording → Verify Animation läuft weiter, korrekter LayoutMode auf erstem Frame. | — (Coverage-Baseline) |
| **UI-Test 7** | Toggle Single-Row während Recording → Verify Pulse-Animation läuft durch, Send-Btn-Position wechselt korrekt. | **§1.1 #2** |
| **UI-Test 8** *(neu)* | Frame-Capture während Toggle Two-Row ↔ Single-Row in Idle+lastAudio: Resend-Btn bleibt visibility=VISIBLE in jedem Frame (Espresso `IdlingResource` + per-frame check). | **§1.1 #3b** |
| **UI-Test 9** *(neu)* | Resend-Cooldown (500ms): nach Click bleibt visibility=VISIBLE, enabled=false, alpha=0.4 — Visibility ist NICHT Cooldown-gekoppelt. | **§1.1 #3b** |
| **UI-Test 10** *(neu)* | Active → Pipeline-Preparing-Übergang: per-frame check — keiner der trash/pause-Buttons darf über record_btn gerendert werden. | **§1.1 #3a + §1.1 #3b** (Cross-Bug-Verifikation) |

**Visibility-Matrix als ausführbare Test-Suite:**

```kotlin
@RunWith(Parameterized::class)
class VisibilityMatrixTest(
    private val mode: LayoutMode,
    private val state: DictateUiState,
    private val expected: Map<LogicalButtonId, Boolean>,
) {
    @Test fun `predicates match expected matrix`() {
        mode.rows.flatMap { it.slots }.forEach { slot ->
            val actual = slot.visibilityPredicate(state)
            assertEquals(
                "Mismatch for ${slot.logicalId} in ${mode.id}",
                expected[slot.logicalId], actual,
            )
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} + state={1}")
        fun cases() = listOf(
            arrayOf(KEYBOARD_TWO_ROW, idleState(), expectedTwoRowIdle()),
            arrayOf(KEYBOARD_TWO_ROW, recordingActiveState(), expectedTwoRowRecording()),
            arrayOf(KEYBOARD_SINGLE_ROW, idleState(), expectedSingleRowIdle()),
            // ... alle 5 LayoutModes × 5 typische States = 25 Cases
        )
    }
}
```

**Pending-Tests (Test-First-Pattern):**
- Test für `predResendVisible` in PipelineUiState.Sending.Backgrounded — pending bis Spec 1 §7 (Background-Send) implementiert ist. Marker: `@Ignore("pending: Backgrounded-Sealed-Member kommt mit Block 7")`.

### §14.3 Spike-Validierungen

Pro Spike ein **klares Pass/Fail-Kriterium**, sodass das Spike-Resultat in die endgültige Architektur einfließt:

| Spike | Pass-Kriterium | Fail → Mitigation |
|---|---|---|
| §11.3 PulseLayout in MotionLayout | Pulse läuft durch alle 5 LayoutMode-Transitions ohne sichtbares Reset | Option A (PulseLayout in extra FrameLayout, via ConstraintHelper positioniert) |
| §11.4 Inflation-Cost | < 50ms `onCreateInputView` auf Pixel 4a | Async-Inflate / Pre-Inflate / Reduktion der Scene-States |

---
