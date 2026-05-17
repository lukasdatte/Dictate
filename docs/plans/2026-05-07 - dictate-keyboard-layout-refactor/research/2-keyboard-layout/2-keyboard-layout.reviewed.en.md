# Spec 2 — KEYBOARD Layout (IME View): KeyboardLayoutManager + LayoutCatalog + MotionLayout

**Status:** Detailed elaboration — XML, LayoutCatalog, Migration, Verification populated.
**Main plan:** [→ keyboard-layout-refactor.md](../../keyboard-layout-refactor.md)
**Sibling specs:**
- [Spec 1 — Pipeline Service Layer](../1-pipeline-service/1-pipeline-service.md)
- [Spec 3 — Floating Overlay (WIDGET + HOVER)](../3-floating-overlay/3-floating-overlay.md)

---

## §1 Context and Scope

This spec describes the **UI layer architecture** for the KEYBOARD mode (IME view). It covers:

<!-- FIX: Issue 1.0.3 – „FSM-Owner" → „FSM-Renderer" (Reduce-Logik liegt im ViewModeModule, Spec 1 §15.1) -->
- **`KeyboardLayoutManager`**: Triangle FSM renderer (subscriber). Subscribes to `DictateUiState` (from Spec 1; the FSM reduce logic lives in the `ViewModeModule`, Spec 1 §15.1), decides on the active LayoutMode + RenderBackend.
- **`LayoutCatalog`**: central data definition of all LayoutModes (KEYBOARD sub-modes + overlay layouts).
- **`LogicalButtonId` / `ButtonSlot` / `RowDescriptor` / `LayoutMode`**: data-type hierarchy that describes the layout declaratively.
- **`RenderBackend` interface**: abstract API that each backend (IME view, overlay window, possibly notification later) implements.
- **`ImeViewBackend`**: the backend for KEYBOARD mode, based on MotionLayout.
- **MotionScene XML structure**: declarative position definition for all KEYBOARD sub-modes.
- **Migration**: KeyboardLayoutModeController, MainButtonsController, KeyboardStateManager visibility logic are migrated into the manager + catalog.

Out of scope (other spec):
- Pipeline state mutation, service lifecycle, persistence — see Spec 1.
- WIDGET and HOVER mode rendering, permissions, window management — see Spec 3.

---

## §2 Architecture Decisions (fixed)

| # | Decision | Rationale |
|---|--------------|------------|
| L1 | **MotionLayout** as container in the IME view | Recommended by the Phase-2 research. Structurally eliminates the re-parenting bug class. Animations declarative. |
| L2 | **Flat button hierarchy** (all buttons are direct children of the MotionLayout) | Phase-2 recommendation. Simplifies ConstraintSets, avoids nested ConstraintLayouts. |
| L3 | **`VISIBILITY_MODE_IGNORE`** for state-driven buttons (`resend_btn`, `pause_btn`, `trash_btn`, `audio_focus_btn`) | MotionScene manages position, LayoutManager manages visibility — collision-free (Phase-2 recommendation). |
| L4 | **One view instance per backend** (buttons in the IME view and in the overlay window are separate views) | Android hard constraint: a view can only live in ONE window. |
| L5 | **LogicalButtonId mapping** per backend | Manager knows logical IDs, backend translates them to concrete view instances. |
<!-- FIX: Phase-C C-4 (2026-05-14) – `pipelineService.state` → `pipeline.state` (Naming-Drift F-11 / G2 fortgesetzt).
     Spec 1 §5 LocalBinder API (post-F-8) ist über das Feld `pipeline: LocalBinder?` im IME-Service exponiert
     (Spec 1 §5 IME-Side-Snippet: `pipeline!!.state.collect`). Spec 2 §11.8 nutzt zwar noch
     `pipelineService.state.collect` (Pre-F-11), aber das wird in derselben Iteration mit-homogenisiert. -->
| L6 | **Subscription pattern**: KeyboardLayoutManager collects `pipeline.state` (LocalBinder, Spec 1 §5) | Reactive, automatic re-render on every state change. |
| L7 | **PulseLayout wrapper stays in record_pulse_layout** | record_pulse_layout is a direct child of the MotionLayout root, record_btn is a child of record_pulse_layout. PulseLayout is co-positioned in the MotionScene. |
| L8 | **Set click listeners only once per backend attach, read the slot at click time** | Memory-leak-free (no new lambda per tick), but dynamic action resolution via a state snapshot. Rationale in §11.6. |
| L9 | **Special touch handlers are wired once in the `attach()` callback** and persist across all renders | CursorSwipeTouchHandler, BackspaceSwipeHandler, EnterOverlayHandler are state-free and do not need re-wiring per render. |

---

## §3 Data Model

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
<!-- FIX: Phase-B S-7 (2026-05-13) – actionResolver auf 2-arg `(state, services) -> Action?` erweitert.
     Hintergrund: `resolveRecordAction` (§8.5) braucht `services.audioFileFactory.allocate()` als Pre-
     Dispatch-Allocation (R.2, Spec 1 §4.11). Mit dem alten 1-arg-Signatur war die Referenz
     `actionResolver = ::resolveRecordAction` ein Compile-Error (Typ-Mismatch). Alle anderen Resolver
     ignorieren das 2. Argument einfach — `{ Action.X }` ist nach Erweiterung `{ _, _ -> Action.X }`
     bzw. `{ state, _ -> ... }` (Kotlin trailing-lambda-Konvention). Services lebt im Backend (siehe
     §3.4 ImeViewBackend-Konstruktor + Spec 3 §4.2 OverlayBackend-Konstruktor); der Click-Listener
     in `wireStaticHandlers` ruft `slot.actionResolver(state, services)?.let { onAction?.invoke(it) }`. -->
data class ButtonSlot(
    val logicalId: LogicalButtonId,
    val widthPolicy: WidthPolicy,
    val visibilityPredicate: (DictateUiState) -> Boolean,
    val iconResolver: (DictateUiState) -> Int? = { null },
    val textResolver: (DictateUiState) -> CharSequence? = { null },
    val enabledResolver: (DictateUiState) -> Boolean = { true },
    val alphaResolver: (DictateUiState) -> Float = { 1f },
    <!-- FIX: Phase-C C-3 (2026-05-14) – `null`-Semantik explizit als strukturelle Verhinderung
         von `DispatchOutcome.Unrouted` dokumentiert (C-1 F-6 Offene Frage für C-3). Implementer-
         Anchor: ein `null`-Return aus dem Resolver wird vom Click-Handler (§6 wireStaticHandlers)
         per `?.let { onAction?.invoke(it) }` aussortiert — die Action erreicht den Orchestrator
         erst gar nicht, weshalb es KEIN `DispatchOutcome.Rejected` oder `DispatchOutcome.Unrouted`
         und keinen Telemetry-Log gibt. Das ist der bewusst gewählte "stille No-Op"-Pfad für
         strukturell ungültige Clicks (Cooldown, Wrong-State). -->
    /**
     * Mappt (State, Services) → Action. `services` liefert nur den Pre-Dispatch-Allokator
     * für AudioFileFactory (R.2, Spec 1 §4.11) — Resolver dürfen KEINE anderen `services`-
     * Felder lesen (Pure-Function-Garantie: keine Hardware/IO-Reads außer File-Allocate).
     *
     * **`null`-Semantik (Phase-C C-3):** Ein `null`-Return bedeutet "Click ist im aktuellen
     * State unbedeutend" — der Click-Handler (§6 `wireStaticHandlers`,
     * `slot.actionResolver(s, services)?.let { onAction?.invoke(it) }`) verschluckt das
     * `null` per `?.let`, die Action erreicht den Orchestrator NIE. Damit gibt es kein
     * `DispatchOutcome.Rejected("reducer-null")` und kein `DispatchOutcome.Unrouted`-Log-Spam
     * für strukturell-ungültige Clicks (Cooldown, falscher Recording-Pfad, etc.). Resolver
     * sind die **erste** Validierungs-Schicht (Spec 1 §4.3 Reducer ist die zweite). Konvention:
     * Visibility/Enabled werden via `visibilityPredicate` / `enabledResolver` getrennt
     * modelliert — der Resolver nutzt `null` nur, wenn die Action selbst State-abhängig
     * inexistent ist (z.B. PAUSE-Click in Idle: kein `Action.RecordingAction.X` ist sinnvoll).
     */
    val actionResolver: (DictateUiState, ModuleServices) -> Action?,
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

### §3.3 Action (for actionResolver) — hierarchical sealed-class structure (F-8 + F-11)

> **Architecture correction F-8 + F-11 (2026-05-09):** Earlier spec versions
> had `Action` as a flat sealed class with ~25 variants. With the modular
> orchestrator pattern (Spec 1 §4 / §15) it becomes necessary to group actions
> **per module axis**. The type parameter `actionClass: KClass<A>` on every
> `DictateModule` needs a unique action class. Hence:
> a hierarchical sealed-class structure, one inner sealed class per axis.

```kotlin
// File: app/src/main/java/net/devemperor/dictate/state/Action.kt
sealed class Action {

    <!-- FIX: Issue 1.1.4 + 2.1.7 / R.3 – NoOp entfernt; Resolver returnt jetzt Action? = null -->
    // (Action.NoOp entfernt — actionResolver hat Typ (DictateUiState) -> Action?)

    <!-- FIX: Issue 2.1.3 (User-Decision Option D) – EffectFailure als typisierter Failure-Channel -->
    <!-- FIX: Phase-B S-3 (2026-05-13) – EffectFailure-Routing präzisiert (siehe Spec 1 §4.3 Effect-Failure-Pfad).
         Vorher war EffectFailure eine Top-Level-Action ohne moduleByLeafClass-Routing — der dispatchInternal-
         Loop hätte sie als DispatchOutcome.Unrouted abgewiesen, Module hätten den State-Snapshot davor
         nie gesehen (kein prev/next-Cascade ohne erfolgreichen Reducer-Lauf). Failure-Channel war effektiv tot.
         Konvention jetzt: Routing über die ORIGIN-Modul-ID (Modul, dessen Effect failed hat). Damit erbt
         EffectFailure das Routing des emittierenden Moduls; das Modul reagiert in einem dedizierten
         `reduceFailure(state, failure, ctx)`-Hook (DictateModule-Interface, Spec 1 §4.2) und kann den
         State-Rollback / Failure-Marker setzen. Andere Module beobachten den State-Change anschließend
         regulär via onCrossModuleStateChange. -->
    <!-- FIX: Phase-C C-3 (2026-05-14) – Drift-Korrektur: Routing erfolgt jetzt über den separaten
         `reduceFailure`-Hook (Phase-B S-3-Erweiterung des DictateModule-Interfaces), NICHT über einen
         `is Action.EffectFailure -> …`-Arm im regulären `reduce`. Grund: `reduce`'s Action-Parameter
         hat den Modul-spezifischen Typ `A` (z.B. `Action.RecordingAction`); `Action.EffectFailure`
         ist ein direkter Action-Subtyp, KEINE `RecordingAction` — ein Reducer-Arm wäre type-unsicher
         (siehe Spec 1 §4.2 reduceFailure-KDoc). KDoc hier auf den separaten Hook umgestellt. -->
    /**
     * Failure-Channel — vom Orchestrator emittiert, wenn ein Modul-`runEffect`-Aufruf wirft.
     *
     * **Routing-Konvention (Spec 1 §4.3, EffectFailure-Pfad `dispatchInternal` Step 1a + 2):**
     * <!-- FIX: Phase-C C-3 (2026-05-14) – stale Z. 617 → Section-Anchor (F-5-Pattern aus C-1). -->
     * EffectFailure trägt die [originModuleId] des emittierenden Moduls; der Orchestrator routet sie
     * **zurück an genau dieses Modul** (NICHT über `moduleByLeafClass`-KClass-Lookup, sondern über
     * die ID). Begründung: nur das Owner-Modul des Effects weiß, welcher Sub-State-Rollback /
     * Failure-Marker korrekt ist. Module ohne überschriebenen `reduceFailure`-Hook erben das
     * Interface-Default `null` (Spec 1 §4.2) — `DispatchOutcome.Rejected("reducer-null")` ist
     * semantisch korrekt ("Modul hat keine Failure-Behandlung für diesen Effect"), kein Bug.
     *
     * **Reducer-Arm liegt im Hook `reduceFailure(state, failure, ctx)`, nicht im regulären
     * `reduce(state, action, ctx)`:** Phase-B S-3 hat das Modul-Interface um den separaten
     * Failure-Hook erweitert (Spec 1 §4.2). Ein `is Action.EffectFailure ->`-Arm im regulären
     * `reduce` wäre type-unsicher, weil `reduce`'s Action-Parameter den Modul-spezifischen Typ `A`
     * (z.B. `Action.RecordingAction`) hat — `Action.EffectFailure` ist ein direkter Action-Subtyp,
     * KEINE `RecordingAction`. `reduceFailure` trennt die beiden Pfade ISP-konform.
     *
     * **Effect-Identifikator als String (Phase-C C-3 Hinweis):** `effect` wird vom Orchestrator
     * über `effect.toString()` (Spec 1 §4.3 Step 4) befüllt. Für `object`-Effects (z.B.
     * `Effect.ReleaseMediaRecorder`) ist `toString()` der Simple-Name — Module können direkt mit
     * `failure.effect == "ReleaseMediaRecorder"` matchen. Für `data class`-Effects (z.B.
     * `Effect.AllocateMediaRecorder(target, useBluetooth, audioFile)`) enthält `toString()` die
     * Args-Repräsentation — naiver String-Vergleich `failure.effect == "AllocateMediaRecorder"`
     * matcht NICHT. Module mit data-class-Effects MÜSSEN `failure.effect.startsWith("AllocateMediaRecorder(")`
     * verwenden oder einen typisierten Effect-Discriminator etablieren (siehe Spec 1 §15.2
     * RecordingModule.reduceFailure-FIX-Kommentar).
     *
     * Cross-Module-Beobachtung läuft danach wie bei jeder anderen erfolgreich-reduzierten
     * Action über `onCrossModuleStateChange` — z.B. kann PipelineModule auf
     * `RecordingModule`-EffectFailure mit einem `PipelineAction.PipelineFailed` reagieren.
     */
    data class EffectFailure(
        val originModuleId: ModuleId,
        val effect: String,
        val reason: String,
    ) : Action()

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

    <!-- FIX: Phase-B S-3 (2026-05-13) – KeyboardInputAction-Kommentar präzisiert.
         Vorher: "kein eigenes Modul — direkt im IME-Service ausgeführt" — das war
         architektonisch inkonsistent: die Actions werden von Slot-Resolvern
         (Spec 2 §3.2 + §6) an `orchestrator.dispatch(...)` geschickt, aber kein
         Modul beanspruchte `actionClass = Action.KeyboardInputAction::class`.
         Folge: `moduleByLeafClass`-Lookup gäbe `null` → `DispatchOutcome.Unrouted`
         → Backspace/Enter/Space-Buttons wären im neuen System TOT (keine
         InputConnection-Operation wird ausgelöst). Korrektur: `KeyboardInputModule`
         als eigenes Modul mit `Unit`-State + SideEffects, das die InputConnection-
         Operation in `runEffect` macht (Spec 1 §15.6). Damit bleibt die F-8-
         Single-Dispatch-Garantie intakt und der reguläre Sealed-Leaves-Indexing-
         Pfad bleibt OCP-konform. -->
    // ─── Tastatur-Eingaben (KeyboardInputModule — Spec 1 §15.6) ───
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

**Important properties of the hierarchy:**

- **Type-safe routing**: Each module claims exactly one `actionClass: KClass<A>` (e.g. `Action.RecordingAction::class`). The orchestrator routes type-safely via a `KClass` lookup.
- **Compile-time exhaustiveness**: Reducer methods with `when (action) {}` over the inner sealed class are exhaustive — the compiler enforces completeness.
- **Single source of truth for the action list**: every action lives only ONCE in the sealed class. LocalBinder no longer has any forwarding methods (F-8).
- **OCP**: a new action = a new variant in the corresponding inner sealed class. Other modules untouched.

---

## §4 KeyboardLayoutManager — API

<!-- FIX: Phase-C C-4 (2026-05-14) – Code-Snippet zeigt nur Single-Backend-Skelett (`activeBackend: RenderBackend?`),
     aber §4.1 ContentAreaController-Block (R.10 / Issue 2.1.15 Option B) verlangt explizit "eine Liste aktiver
     Backends statt eines einzigen activeBackend-Felds". Doppel-Truth-Quelle in derselben Section.
     Auflösung: §4 ist das Skelett (Pädagogik — Single-Backend-Pfad), §4.1 ist der Production-Vertrag
     (Multi-Backend für `ImeViewBackend` + `ContentAreaController` parallel). Block-5b-Implementer-Anker:
     §4.1 ist SoT, das §4-Snippet ist die "klassische Single-Backend-Variante" als Lese-Anker.
     Cross-Reference-Header ergänzt, damit Implementer nicht beide als gleichwertig liest. -->
> **Implementer anchor:** The following snippet shows the **single-backend skeleton** to explain the
> manager API. **The production variant** (with `ContentAreaController` as a second `RenderBackend`,
> R.10 / Issue 2.1.15 Option B) lives in §4.1 and holds **a list** of active backends. The SoT for
> the Block-5b implementation is §4.1.

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

    // FIX: Phase-C C-4 (2026-05-14) – Cross-Spec-Referenz-Drift entdeckt: `LayoutCatalog.OVERLAY_5BUTTON`
    // wird in Spec 2 (hier + §8.6 implizit) und Spec 3 (§11/§14 mehrfach) als qualifizierter Member
    // referenziert, aber Spec 3 §3.1 deklariert `OVERLAY_5BUTTON` als **top-level `object OVERLAY_5BUTTON
    // : LayoutMode(...)`** außerhalb von `LayoutCatalog`. Compile-Error in der jetzigen Form.
    // Auflösung: Spec 3 §3.1 verschiebt die Deklaration in `LayoutCatalog`-Object (Block-6-Implementer-
    // Aufgabe, Spec 3-internal — C-5 Floating-Overlay-Audit erbt diese Cross-Spec-Korrektur-Pflicht).
    // SoT der `LayoutCatalog`-Struktur: Spec 2 §8.6 — `LayoutCatalog` ist ein `object`, `OVERLAY_5BUTTON`
    // wird dort als Property ergänzt (analog `forKeyboard(state)`).
    private fun computeLayoutMode(state: DictateUiState): LayoutMode = when (state.viewMode) {
        ViewMode.KEYBOARD -> LayoutCatalog.forKeyboard(state)
        ViewMode.WIDGET, ViewMode.HOVER -> LayoutCatalog.OVERLAY_5BUTTON
    }

    private fun render(state: DictateUiState, mode: LayoutMode) {
        activeBackend?.render(state, mode)
    }
}
```

**Central idea:** the manager does no visibility logic itself — it delegates to the `LayoutCatalog`, which picks the right `LayoutMode` instance, and to the backend, which performs the concrete view mutation.

<!-- FIX: Issue 2.1.15 (User-Decision Option A+B) – KeyboardLayoutManager ↔ Spec-1-LayoutModule Beziehungs-Section -->
### §4.1 KeyboardLayoutManager ↔ LayoutModule (Spec 1 §15.1) — Relationship

| Component | Who | Responsibility |
|------------|-----|---------------|
| `LayoutModule` (Spec 1 §15.1, module inventory #4) | **State owner** | Holds the `LayoutState` axis (`contentArea` + 3 booleans). Writes via `Action.LayoutAction.*` (e.g. `SetContentArea`, `ToggleSingleRowMode`). The reducer is a pure function, no view knowledge. |
| `KeyboardLayoutManager` (Spec 2 §4) | **View renderer** | Consumes `state.layout` (read-only) and maps it via `computeLayoutMode(state)` onto a concrete `LayoutMode` instance. No state owner, no reducer. |
| `RenderBackend` (Spec 2 §5) | **Property setter** | Applies the `LayoutMode` properties to concrete Android views. Three implementations: `ImeViewBackend` (main path), `ContentAreaController` (second RenderBackend for container visibility, R.10), `OverlayBackend` (Spec 3 §4). |

**Contract:** Mutations to `state.layout` **always** go through `Action.LayoutAction.*` →
`LayoutModule.reduce` (in Spec 1 §15.1). The `KeyboardLayoutManager` never calls
`store.update` directly. This keeps the single-source-of-truth rule intact.

<!-- FIX: Phase-C C-4 (2026-05-14) – Atomar-Vertrag-Cross-Link ergänzt (Offene Frage aus C-1 für C-4
     gemäß `phase-c1-state-module-coherence.md` Sektion "Für C-3 (Layout/View-Rendering)"). -->
> **Atomicity contract `LayoutAction.ToggleSmallMode` (Spec 1 §11.2.2 step 6 + Block-1b acceptance):**
> The former `KeyboardStateManager.setSmallMode(true)` mutated sequentially first `isSmallMode = true`
> and THEN `contentArea = MAIN_BUTTONS` (two sequential steps, KSM.kt:141-145). The LayoutModule
> reducer (Spec 1 §15.1, §11.2.2 step 6) consolidates both mutations into **one** `state.copy`
> call on the **one** `LayoutState` axis: `state.copy(layout = layout.copy(smallMode = enabled,
> contentArea = MAIN_BUTTONS))`. This is NOT a Mode-3 violation (Spec 1 §15.5) — both fields live in
> the same sub-state class `LayoutState`, which the LayoutModule owns alone. Verified via
> `LayoutModuleAtomicityTest.kt` (Spec 1 §11 Block-1b acceptance).

**ContentAreaController as a second RenderBackend (R.10 + 2.1.15 Option B):** Container
visibility (`mainButtonsCl` / `qwertz_container` / `emojiPicker_container`) is not modelled in
ButtonSlot resolvers (that would be a conceptual misfit), but as its own
`RenderBackend` implementation that reacts in parallel to `ImeViewBackend`:

```kotlin
class ContentAreaController(views: KeyboardViews) : RenderBackend {
    override fun render(state: DictateUiState, mode: LayoutMode) {
        views.mainButtonsCl.visibility = if (state.layout.contentArea == ContentArea.MAIN_BUTTONS) View.VISIBLE else View.GONE
        views.qwertzContainer.visibility = if (state.layout.contentArea == ContentArea.QWERTZ) View.VISIBLE else View.GONE
        views.emojiPickerContainer.visibility = if (state.layout.contentArea == ContentArea.EMOJI_PICKER) View.VISIBLE else View.GONE
    }
}
```

The `KeyboardLayoutManager` holds **a list** of active backends instead of a single
`activeBackend` field; on a render tick all of them are called.

---

## §5 RenderBackend Interface

```kotlin
interface RenderBackend {
    fun attach(onAction: (Action) -> Unit)
    fun detach()
    fun render(state: DictateUiState, mode: LayoutMode)
}
```

The interface is deliberately minimal — backend-specific details stay within the implementation.

---

## §5.1 Shared Slot-Apply Helper (F-7 / DRY)

> **Architecture correction F-7 (iteration 2026-05-08):** `ImeViewBackend.applySlotProperties`
> and `OverlayBackend.applySlots` were, in earlier spec versions, duplicated as
> separate methods with identical seven-line logic.
> Both are now consolidated into a top-level function `applySlotToView`,
> which both backends call — no drift source, a clear
> SSOT for the slot→view-property mapping rule.

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

**SOLID verification:**
- **SRP** — pure slot→view mapping. No click routing, no backend knowledge, no state mutation.
- **OCP** — a new slot property = a new setter block here; all backends benefit without any adjustment of their own.
- **DIP** — depends only on `ButtonSlot` (data layer), `View`/`Context` (Android API) and `MaterialButton` (library class). No backend concretization.

**DRY proof:** two backends × seven identical view-property sets = 14 lines to duplicate. With the helper: one call per backend, one definition for both. If Spec 3 §4.2 later needs a `contentDescription` for accessibility, it adds it to the helper — Spec 2 inherits it automatically.

---

## §6 ImeViewBackend (KEYBOARD mode)

<!-- FIX: Phase-B S-7 (2026-05-13) – Backend-Konstruktor um `services: ModuleServices` erweitert.
     Hintergrund: Resolver-Signatur `(state, services) -> Action?` (§3.2) braucht eine `services`-Quelle
     im Backend, um `services.audioFileFactory.allocate()` Pre-Dispatch aufzurufen (R.2, Spec 1 §4.11).
     `services` lebt im DictatePipelineService (§7.3 Spec 1 Composition Root) und wird durch
     `KeyboardLayoutManager.attach(backend)` an den Backend gereicht; das Backend ruft `services` nur
     in `wireStaticHandlers` (Click-Listener), nicht im Render-Loop — Pure-Function-Garantie bleibt
     erhalten. Verifiziert in `ImeViewBackendActionResolverTest.kt` (Click → resolveRecordAction → audioFile in Action). -->
```kotlin
class ImeViewBackend(
    private val rootView: View,                          // MotionLayout-Root
    private val ctx: Context,
    private val services: ModuleServices,                // Phase-B S-7: für Pre-Dispatch-Allocation (audioFileFactory)
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
<!-- FIX: Phase-B S-7 (2026-05-13) – Click-Listener ruft 2-arg-Resolver (state, services); services-Reference
     kommt vom Backend-Konstruktor (`ImeViewBackend(scope, services, onAction, …)`). -->
<!-- FIX: Phase-C C-3 (2026-05-14) – `?.let { onAction?.invoke(it) }` ist die Resolver-`null`-Aussortierung
     (siehe §3.2 ButtonSlot.actionResolver-KDoc). Verhindert strukturell `DispatchOutcome.Unrouted`-
     Log-Spam für unsinnige Clicks (Cooldown, Wrong-State) — die Action erreicht den Orchestrator nie.
     Strukturelle Verhinderung, KEIN Telemetry-Pfad. -->
    private fun wireStaticHandlers() {
        buttonViews.forEach { (id, view) ->
            view.setOnClickListener {
                onVibrate()
                val s = stateRef ?: return@setOnClickListener
                val slot = currentSlot(id) ?: return@setOnClickListener
                slot.actionResolver(s, services)?.let { onAction?.invoke(it) }
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

## §7 MotionScene XML — complete

### §7.1 File `res/xml/motion_scene_keyboard.xml`

Complete scene with all 5 KEYBOARD states. `two_row_state` is the base definition; all others inherit via `motion:deriveConstraintsFrom` and override only what actually differs.

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

### §7.2 Refactored `activity_dictate_keyboard_view.xml` — `main_buttons_cl` area

Before (today, lines 12-172): `LinearLayout` → `ConstraintLayout action_row` + `ConstraintLayout input_row` with their buttons as nested children.

After: a single `MotionLayout` with all 9 buttons as direct children. Position constraints are NO LONGER in the layout XML, but in the scene (inflation starts in the `@+id/two_row_state` ConstraintSet).

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

**Important:** the layout XML no longer contains **any** `app:layout_constraint…` attributes on the buttons. These live exclusively in the MotionScene. The only constraint that remains in the layout XML is the position of the MotionLayout itself within the root `ConstraintLayout` (lines 21-23 of today's XML).

### §7.3 VISIBILITY_MODE_IGNORE — Finalization

<!-- FIX: Issue 2.1.14 / R.11 (User-Decision Option A) – alle 9 Buttons bekommen visibilityMode="ignore" -->
**The catalog is the sole visibility owner.** So that MotionLayout does not override the
LayoutCatalog's visibility mutations, **each of the 9 buttons** has `motion:visibilityMode="ignore"`
in the MotionScene and in the layout XML — even the buttons that are "always visible" today. The
lint rule "if predicate-constant, then without ignore" is dropped in favour of unambiguous contract
semantics.

| Button | `visibilityMode="ignore"` |
|--------|---------------------------|
| record_btn          | **YES** |
| resend_btn          | **YES** |
| backspace_btn       | **YES** |
| audio_focus_btn     | **YES** |
| widget_toggle_btn   | **YES** |
| trash_btn           | **YES** |
| space_btn           | **YES** |
| pause_btn           | **YES** |
| enter_btn           | **YES** |

→ 9 buttons × 1 XML attribute = 9 edits in the scene XMLs (Two-Row, Single-Row, Send-Modes).
The catalog is the only visibility source; the MotionScene does position only.

---

## §8 LayoutCatalog: complete definitions

### §8.1 KEYBOARD_TWO_ROW

```kotlin
val KEYBOARD_TWO_ROW = LayoutMode(
    id = LayoutModeId.KEYBOARD_TWO_ROW,
    backend = BackendType.IME_VIEW,
    rows = listOf(
        // Row 1: action_row-Bereich
        RowDescriptor(slots = listOf(
            // FIX: Phase-B S-6 (2026-05-13) – actionResolver-Lambdas auf 2-arg (state, services) -> Action?
            // umgestellt, Folgepfad von S-7 F-1. Konvention: `{ Action.X }` (0-arg) → `{ _, _ -> Action.X }`,
            // `{ state -> ... }` (1-arg) → `{ state, _ -> ... }`. Methodenreferenzen wie ::resolveRecordAction
            // bleiben unverändert (Resolver-Signaturen sind selbst 2-arg).
            ButtonSlot(LogicalButtonId.RECORD, FillRemaining,
                visibilityPredicate = { true },
                textResolver = ::resolveRecordButtonText,
                enabledResolver = { state -> state.recording !is RecordingState.Preparing },
                actionResolver = ::resolveRecordAction),
            // FIX: Issue 3.0.9 / Spec 2 §13.5 Gap 2 – Resend-Cooldown landet im enabledResolver, NICHT im
            // visibilityPredicate. Predicate (predResendVisible) bleibt cooldown-frei (load-bearing für Bug §1.1 #3b).
            ButtonSlot(LogicalButtonId.RESEND, WrapContent,
                visibilityPredicate = ::predResendVisible,
                enabledResolver = { !it.resend.resendCooldown },
                alphaResolver = { if (it.resend.resendCooldown) 0.4f else 1f },
                actionResolver = { _, _ -> Action.ResendAction.ResendLastAudio }),
            ButtonSlot(LogicalButtonId.BACKSPACE, WrapContent,
                visibilityPredicate = { true },
                actionResolver = { _, _ -> Action.KeyboardInputAction.Backspace }),
            ButtonSlot(LogicalButtonId.AUDIO_FOCUS, WrapContent,
                visibilityPredicate = { false },                       // nur Single-Row
                actionResolver = { _, _ -> Action.AudioAction.ToggleAudioFocusPref }),
            // FIX: Phase-B S-6 (2026-05-13) – WIDGET_TOGGLE-Slot in alle KEYBOARD-LayoutModes nachgepflegt
            // (Spec 3 OPEN-2 / Phase-1-1.0.2). Ohne Slot würde der widget_toggle_btn-View (im buttonViews-Map
            // registriert, §6) nie aktualisiert; Default-XML-Visibility `gone` bliebe sticky → User-Bug
            // "Toggle zu WIDGET geht nicht". Position: am Ende der action_row (rechts neben AUDIO_FOCUS-Slot,
            // der in TWO_ROW GONE ist). Finale Slot-Position bleibt im OPEN-2-Apply (Spec 2 §13.5.b) eventuell
            // verfeinert; Slot ist hier explizit verankert, damit der Render-Loop ihn nicht silent skipt.
            ButtonSlot(LogicalButtonId.WIDGET_TOGGLE, WrapContent,
                visibilityPredicate = { it.viewMode == ViewMode.KEYBOARD },
                actionResolver = { _, _ -> Action.ViewModeAction.ToggleViewModeWidget }),
        )),
        // Row 2: input_row-Bereich
        RowDescriptor(slots = listOf(
            ButtonSlot(LogicalButtonId.TRASH, WrapContent,
                visibilityPredicate = ::predTrashVisible,
                actionResolver = ::resolveTrashAction),
            ButtonSlot(LogicalButtonId.SPACE, FillRemaining,
                visibilityPredicate = { true },
                actionResolver = { _, _ -> Action.KeyboardInputAction.SpaceKey }),  // FIX: Issue 3.0.5 – flache Action.SpaceKey → hierarchisch
            ButtonSlot(LogicalButtonId.PAUSE, WrapContent,
                visibilityPredicate = ::predPauseVisible,
                enabledResolver = { it.recording.isActiveOrPaused },
                alphaResolver = { if (it.recording.isActiveOrPaused) 1f else 0.4f },
                iconResolver = ::resolvePauseIcon,
                actionResolver = ::resolvePauseAction),
            ButtonSlot(LogicalButtonId.ENTER, WrapContent,
                visibilityPredicate = { true },
                actionResolver = { _, _ -> Action.KeyboardInputAction.EnterKey }),
        )),
    ),
)
```

### §8.2 KEYBOARD_SINGLE_ROW

A single row with all 8 buttons. All predicates are identical to Two-Row, **except** `AUDIO_FOCUS`, which is visible in Single-Row.

```kotlin
val KEYBOARD_SINGLE_ROW = LayoutMode(
    id = LayoutModeId.KEYBOARD_SINGLE_ROW,
    backend = BackendType.IME_VIEW,
    rows = listOf(
        // Eine einzige Row — Reihenfolge entspricht der MotionScene-Chain.
        // FIX: Phase-B S-6 (2026-05-13) – actionResolver-Lambdas auf 2-arg (state, services) -> Action?
        // umgestellt, Folgepfad von S-7 F-1.
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
                actionResolver = { _, _ -> Action.KeyboardInputAction.SpaceKey }),  // FIX: Issue 3.0.5 – flache Action.SpaceKey → hierarchisch
            ButtonSlot(LogicalButtonId.PAUSE, WrapContent,
                visibilityPredicate = ::predPauseVisible,
                enabledResolver = { it.recording.isActiveOrPaused },
                alphaResolver = { if (it.recording.isActiveOrPaused) 1f else 0.4f },
                iconResolver = ::resolvePauseIcon,
                actionResolver = ::resolvePauseAction),
            ButtonSlot(LogicalButtonId.BACKSPACE, WrapContent,
                visibilityPredicate = { true },
                actionResolver = { _, _ -> Action.KeyboardInputAction.Backspace }),
            ButtonSlot(LogicalButtonId.ENTER, WrapContent,
                visibilityPredicate = { true },
                actionResolver = { _, _ -> Action.KeyboardInputAction.EnterKey }),
            // FIX: Issue 3.0.9 / Spec 2 §13.5 Gap 2 – Resend-Cooldown im enabledResolver, NICHT visibilityPredicate.
            ButtonSlot(LogicalButtonId.RESEND, WrapContent,
                visibilityPredicate = ::predResendVisible,
                enabledResolver = { !it.resend.resendCooldown },
                alphaResolver = { if (it.resend.resendCooldown) 0.4f else 1f },
                actionResolver = { _, _ -> Action.ResendAction.ResendLastAudio }),
            ButtonSlot(LogicalButtonId.AUDIO_FOCUS, WrapContent,
                visibilityPredicate = { true },                        // ← einziger Unterschied zu Two-Row
                iconResolver = { resolveAudioFocusIcon(it.audio.audioFocusEnabledPref) },  // FIX: F-4 – SSoT-Helper
                actionResolver = { _, _ -> Action.AudioAction.ToggleAudioFocusPref }),
            // FIX: Phase-B S-6 (2026-05-13) – WIDGET_TOGGLE-Slot auch in SINGLE_ROW (siehe TWO_ROW §8.1).
            // Position am Ende der Chain (rechts neben AUDIO_FOCUS).
            ButtonSlot(LogicalButtonId.WIDGET_TOGGLE, WrapContent,
                visibilityPredicate = { it.viewMode == ViewMode.KEYBOARD },
                actionResolver = { _, _ -> Action.ViewModeAction.ToggleViewModeWidget }),
        )),
    ),
)
```

### §8.3 KEYBOARD_TWO_ROW_SEND_MODE / KEYBOARD_SINGLE_ROW_SEND_MODE

Pipeline is running (Preparing or Running). `record_btn` = "Sende…" / "X/Y · 0:08", `pause_btn` GONE, `trash_btn` GONE, `resend_btn` GONE.

<!-- FIX: Issue 2.0.11 – Inline-Doku-Anker an hardcoded { false } für TRASH/PAUSE im SEND_MODE (load-bearing Bug-Fix) -->

> **Important architecture note (bug-fix anchoring):** The `visibilityPredicate = { false }`
> for TRASH and PAUSE in SEND_MODE are **hardcoded** and **must not** be
> replaced by `predTrashVisible(state)` / `predPauseVisible(state)` (the central predicates from §8.5).
> Rationale: a known user bug (plan §1.1 #3 — "Send button hidden
> in send mode"). The catalog switch via `forKeyboard(state)` is the actual
> bug eliminator — the central predicate `predTrashVisible(state)` still returns
> `true` during the Active → Pipeline.Preparing tick transition (`recording.isActive`),
> because the reducer ordering is not atomic. A DRY refactor "why different
> from Idle mode" would reactivate the bug. See test anchor §14.2 UI test 4.

```kotlin
val KEYBOARD_TWO_ROW_SEND_MODE = LayoutMode(
    id = LayoutModeId.KEYBOARD_TWO_ROW_SEND_MODE,
    backend = BackendType.IME_VIEW,
    rows = listOf(
        // FIX: Phase-B S-6 (2026-05-13) – actionResolver-Lambdas auf 2-arg umgestellt (Folgepfad S-7 F-1).
        RowDescriptor(slots = listOf(
            ButtonSlot(LogicalButtonId.RECORD, FillRemaining,
                visibilityPredicate = { true },
                textResolver = ::resolveRecordButtonTextPipeline,   // "Sende…" / "2/3 0:08"
                enabledResolver = { it.pipeline !is PipelineUiState.Preparing },
                actionResolver = ::resolveRecordActionPipeline),    // ToggleAutoEnter / null
            ButtonSlot(LogicalButtonId.RESEND, WrapContent,
                visibilityPredicate = { false },
                actionResolver = { _, _ -> null }),    // R.3 / 1.1.4 – nullable Resolver
            ButtonSlot(LogicalButtonId.BACKSPACE, WrapContent,
                visibilityPredicate = { true },
                actionResolver = { _, _ -> Action.KeyboardInputAction.Backspace }),
            ButtonSlot(LogicalButtonId.AUDIO_FOCUS, WrapContent,
                visibilityPredicate = { false },
                actionResolver = { _, _ -> Action.AudioAction.ToggleAudioFocusPref }),
            // FIX: Phase-B S-6 (2026-05-13) – WIDGET_TOGGLE in SEND_MODE wie in §8.1, damit
            // der View nicht durch Render-Loop-Silent-Skip auf Default-GONE stehen bleibt.
            // visibilityPredicate `false` während aktiver Pipeline: Sender soll Pipeline nicht
            // versehentlich durch Mode-Toggle abreißen — User-Decision: WIDGET-Toggle deaktiviert
            // während Send-Mode (R.13 SSoT — Catalog steuert, nicht ad-hoc-Code).
            ButtonSlot(LogicalButtonId.WIDGET_TOGGLE, WrapContent,
                visibilityPredicate = { false },
                actionResolver = { _, _ -> null }),
        )),
        RowDescriptor(slots = listOf(
            // Hardcoded { false } statt predTrashVisible — siehe Architektur-Notiz oben.
            // NICHT auf predTrashVisible umstellen ohne Plan-Iter (Bug §1.1 #3).
            ButtonSlot(LogicalButtonId.TRASH, WrapContent,
                visibilityPredicate = { false },                     // im Send-Mode NICHT sichtbar
                actionResolver = ::resolveTrashAction),
            ButtonSlot(LogicalButtonId.SPACE, FillRemaining,
                visibilityPredicate = { true },
                actionResolver = { _, _ -> Action.KeyboardInputAction.SpaceKey }),  // FIX: Issue 3.0.5 – flache Action.SpaceKey → hierarchisch
            // Hardcoded { false } statt predPauseVisible — siehe Architektur-Notiz oben.
            // Bug-Eliminator ist der Catalog-Switch, nicht die zentrale Predicate.
            ButtonSlot(LogicalButtonId.PAUSE, WrapContent,
                visibilityPredicate = { false },                     // GONE im Send-Mode
                actionResolver = { _, _ -> null }),    // R.3 / 1.1.4 – nullable Resolver
            ButtonSlot(LogicalButtonId.ENTER, WrapContent,
                visibilityPredicate = { true },
                actionResolver = { _, _ -> Action.KeyboardInputAction.EnterKey }),
        )),
    ),
)

val KEYBOARD_SINGLE_ROW_SEND_MODE = LayoutMode(
    id = LayoutModeId.KEYBOARD_SINGLE_ROW_SEND_MODE,
    backend = BackendType.IME_VIEW,
    // FIX: Phase-B S-6 (2026-05-13) – actionResolver-Lambdas auf 2-arg (Folgepfad S-7 F-1).
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
            visibilityPredicate = { true }, actionResolver = { _, _ -> Action.KeyboardInputAction.SpaceKey }),  // FIX: Issue 3.0.5 – flache Action.SpaceKey → hierarchisch
        // Hardcoded { false } für PAUSE — siehe Architektur-Notiz oben (Bug §1.1 #3).
        ButtonSlot(LogicalButtonId.PAUSE, WrapContent,
            visibilityPredicate = { false }, actionResolver = { _, _ -> null }),    // R.3 / 1.1.4
        ButtonSlot(LogicalButtonId.BACKSPACE, WrapContent,
            visibilityPredicate = { true }, actionResolver = { _, _ -> Action.KeyboardInputAction.Backspace }),
        ButtonSlot(LogicalButtonId.ENTER, WrapContent,
            visibilityPredicate = { true }, actionResolver = { _, _ -> Action.KeyboardInputAction.EnterKey }),
        ButtonSlot(LogicalButtonId.RESEND, WrapContent,
            visibilityPredicate = { false }, actionResolver = { _, _ -> null }),    // R.3 / 1.1.4
        ButtonSlot(LogicalButtonId.AUDIO_FOCUS, WrapContent,
            visibilityPredicate = { true },
            iconResolver = { resolveAudioFocusIcon(it.audio.audioFocusEnabledPref) },  // FIX: F-4
            actionResolver = { _, _ -> Action.AudioAction.ToggleAudioFocusPref }),
        // FIX: Phase-B S-6 (2026-05-13) – WIDGET_TOGGLE-Slot (siehe TWO_ROW_SEND_MODE).
        ButtonSlot(LogicalButtonId.WIDGET_TOGGLE, WrapContent,
            visibilityPredicate = { false }, actionResolver = { _, _ -> null }),
    ))),
)
```

### §8.4 KEYBOARD_REPROCESS_STAGING

Tri-state: pause_btn visible-disabled+alpha-0.4, trash_btn = "Cancel-Staging", record_btn = "Audio X:YY · Senden".

```kotlin
val KEYBOARD_REPROCESS_STAGING = LayoutMode(
    id = LayoutModeId.KEYBOARD_REPROCESS_STAGING,
    backend = BackendType.IME_VIEW,
    // FIX: Phase-B S-6 (2026-05-13) – actionResolver-Lambdas auf 2-arg (Folgepfad S-7 F-1).
    rows = listOf(
        RowDescriptor(slots = listOf(
            // FIX: Phase-B S-6 (2026-05-13) – SendStaging-Action ist `data class SendStaging(val sessionId: String)`
            // (Spec 2 §3.3 PipelineAction.SendStaging), NICHT object — sessionId muss aus dem aktuellen
            // ReprocessStaging-State gelesen werden. Vorher als `{ Action.PipelineAction.SendStaging }`
            // (Singleton-Use) Compile-Error.
            // FIX: Phase-C C-3 (2026-05-14) – stale Z. 205 → Action-Name-Anchor (F-5-Pattern aus C-1).
            ButtonSlot(LogicalButtonId.RECORD, FillRemaining,
                visibilityPredicate = { true },
                textResolver = ::resolveRecordButtonTextStaging,    // "Audio 0:23 · Senden"
                enabledResolver = { state ->
                    val s = state.pipeline as? PipelineUiState.ReprocessStaging
                    s != null && !s.isStarting
                },
                actionResolver = { state, _ ->
                    (state.pipeline as? PipelineUiState.ReprocessStaging)
                        ?.let { Action.PipelineAction.SendStaging(it.sessionId) }
                }),
            ButtonSlot(LogicalButtonId.RESEND, WrapContent,
                visibilityPredicate = { false },
                actionResolver = { _, _ -> null }),    // R.3 / 1.1.4 – nullable Resolver
            ButtonSlot(LogicalButtonId.BACKSPACE, WrapContent,
                visibilityPredicate = { true },
                actionResolver = { _, _ -> Action.KeyboardInputAction.Backspace }),
            ButtonSlot(LogicalButtonId.AUDIO_FOCUS, WrapContent,
                visibilityPredicate = { false },
                actionResolver = { _, _ -> Action.AudioAction.ToggleAudioFocusPref }),
            // FIX: Phase-B S-6 (2026-05-13) – WIDGET_TOGGLE-Slot in REPROCESS_STAGING. Während Staging
            // bewusst GONE (Workflow-Fokus: Queue + Language-Chip), kein WIDGET-Mode-Wechsel mid-Staging.
            ButtonSlot(LogicalButtonId.WIDGET_TOGGLE, WrapContent,
                visibilityPredicate = { false },
                actionResolver = { _, _ -> null }),
        )),
        RowDescriptor(slots = listOf(
            // FIX: Phase-B S-6 (2026-05-13) – CancelReprocessStaging-Action ist `data class
            // CancelReprocessStaging(val sessionId: String)` (Spec 2 §3.3 PipelineAction.CancelReprocessStaging)
            // — sessionId aus ReprocessStaging-State.
            // FIX: Phase-C C-3 (2026-05-14) – stale Z. 206 → Action-Name-Anchor (F-5-Pattern aus C-1).
            ButtonSlot(LogicalButtonId.TRASH, WrapContent,
                visibilityPredicate = { true },
                actionResolver = { state, _ ->
                    (state.pipeline as? PipelineUiState.ReprocessStaging)
                        ?.let { Action.PipelineAction.CancelReprocessStaging(it.sessionId) }
                }),
            ButtonSlot(LogicalButtonId.SPACE, FillRemaining,
                visibilityPredicate = { true },
                actionResolver = { _, _ -> Action.KeyboardInputAction.SpaceKey }),  // FIX: Issue 3.0.5 – flache Action.SpaceKey → hierarchisch
            ButtonSlot(LogicalButtonId.PAUSE, WrapContent,
                visibilityPredicate = { true },
                enabledResolver = { false },
                alphaResolver = { 0.4f },
                actionResolver = { _, _ -> null }),    // R.3 / 1.1.4 – nullable Resolver
            ButtonSlot(LogicalButtonId.ENTER, WrapContent,
                visibilityPredicate = { true },
                actionResolver = { _, _ -> Action.KeyboardInputAction.EnterKey }),
        )),
    ),
)
```

### §8.5 Central Predicate and Resolver Helpers

<!-- FIX: Issue 1.0.5 – Action-Hierarchie (F-8/F-11) durchpropagiert in §6/§8.5/§8.6/§9 (Mapping siehe Spec 2 §3.3) -->

For DRY: all 5 LayoutModes use the **same** predicate functions for identical visibility logic.

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

// FIX: Phase-B S-6 (2026-05-13) – ButtonSlot.actionResolver-Typ (§3.2) ist post-S-7-F-1 jetzt
// `(DictateUiState, ModuleServices) -> Action?`. Damit Methodenreferenzen wie `::resolveRecordActionPipeline`
// im LayoutCatalog (§8.1–§8.4) typ-konsistent funktionieren, MÜSSEN diese Resolver ebenfalls die 2-arg-
// Signatur tragen (Kotlin-Methodenreferenzen werden über die Funktions-Signatur typisiert; eine 1-arg-
// Funktion bekommt KFunction1, was als `(state, services) -> Action?` zugewiesen Compile-Error gäbe).
// Konvention: Resolver, die `services` nicht brauchen, ignorieren das Argument (`_`-Underscore). Nur
// `resolveRecordAction` (Idle-Allokations-Pfad) liest tatsächlich `services.audioFileFactory`.
/** record_btn-Click-Action während Pipeline (Toggle-Auto-Enter im Running). */
fun resolveRecordActionPipeline(state: DictateUiState, services: ModuleServices): Action? = when (state.pipeline) {
    is PipelineUiState.Running -> Action.FeatureToggleAction.ToggleAutoEnter   // Click toggelt AutoEnter
    else -> null
}

fun resolveTrashAction(state: DictateUiState, services: ModuleServices): Action? = when {
    state.pipeline is PipelineUiState.ReprocessStaging -> Action.PipelineAction.CancelReprocessStaging(state.pipeline.sessionId)
    state.recording is RecordingState.Idle && state.pipeline is PipelineUiState.Idle -> null
    else -> Action.RecordingAction.CancelRecording
}

fun resolvePauseAction(state: DictateUiState, services: ModuleServices): Action? = when (state.recording) {
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
<!-- FIX: Phase-C C-4 (2026-05-14) – `OVERLAY_5BUTTON` als Catalog-Property dokumentiert (SSoT-Verankerung).
     Cross-Spec-Konsistenz: Spec 2 §4 + Spec 3 §11/§14 referenzieren `LayoutCatalog.OVERLAY_5BUTTON`
     als qualifizierten Member; Spec 3 §3.1 deklariert die `LayoutMode`-Instanz selbst (Inhalts-SoT
     für Slots + Rows). Die Cross-Spec-Ergänzung ist in Spec 3 §3.1 fällig: das `object OVERLAY_5BUTTON
     : LayoutMode(...)` wird in den `LayoutCatalog`-Body eingebettet (analog `forKeyboard(state)` hier).
     Bis Spec 3 das gefixt hat, ist der `LayoutCatalog.OVERLAY_5BUTTON`-Ref ein Compile-Error.
     C-5-Cross-Reference (Floating-Overlay-Audit). -->

```kotlin
object LayoutCatalog {
    /** Spec 3 §3.1 — OVERLAY_5BUTTON LayoutMode-Definition wird hier per Block-6 (Spec 3) eingebettet. */
    // val OVERLAY_5BUTTON: LayoutMode = ...    // SoT: Spec 3 §3.1; C-5 ergänzt den Property-Body hier.

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

### §8.7 Verification against the visibility matrix

Cross-check of the LayoutMode predicates against `main-button-area-inventory.md` §3 (visibility matrix):

| Button | Idle (today) | TWO_ROW predicate | Recording (today) | TWO_ROW_SEND_MODE | Paused (today) | TWO_ROW predicate | ReprocessStaging (today) | REPROCESS predicate | Pipeline-Running (today) | TWO_ROW_SEND_MODE | ✓ |
|---|---|---|---|---|---|---|---|---|---|---|---|
| record_pulse_layout | V | true | V | true | V | true | V | true | V | true | ✓ |
| resend_btn | V (iff lastAudio) | predResendVisible (Idle+lastAudio+ResendPref) | GONE | false | GONE | predResendVisible (false: not Idle) | GONE | false | GONE | false | ✓ |
| backspace_btn | V | true | V | true | V | true | V | true | V | true | ✓ |
| trash_btn | GONE | predTrashVisible (false) | V | false (Send-Mode!) | V | predTrashVisible (true) | V | true | GONE | false | **A1** |
| pause_btn | GONE | predPauseVisible (false) | V/enabled | false (Send-Mode!) | V/enabled | predPauseVisible (true) | V/disabled-0.4 | true (disabled+alpha) | GONE | false | **A2** |
| space_btn | V | true | V | true | V | true | V | true | V | true | ✓ |
| enter_btn | V | true | V | true | V | true | V | true | V | true | ✓ |
| audio_focus_btn (Single-Row) | V | true (SINGLE_ROW) | V | true (SINGLE_ROW_SEND) | V | true (SINGLE_ROW) | V | (n/a in Reprocess today) | V | true | ✓ |

**Note A1 / A2:** Today's behaviour in `KeyboardStateManager.kt:187-191` is `isActive || isStaging` — i.e. trash/pause are visible during both Recording **and** Pipeline-Running. **Research finding:** during the Recording → Pipeline-Running transition, pause/trash become GONE in today's code, because `RecordingState` switches to `Idle` while `pipeline` switches to `Preparing→Running` — and `predPauseVisible/predTrashVisible` returns false (not active, not paused, not staging).

→ A1/A2 thus exactly matches today's behaviour. **Send-Mode has no trash/pause** — a user-validated assumption from the use-case briefing.

### §8.8 Edge Cases

| Edge case | Today | Catalog predicate | Remark |
|---|---|---|---|
| ReprocessStaging + SingleRow | Renders the Two-Row layout (no Single-Row variant maintained) | `forKeyboard()` picks `KEYBOARD_REPROCESS_STAGING` independently of singleRowMode | **Simplification:** ReprocessStaging only has a Two-Row variant. Rationale: the Cancel-Staging workflow is UI-focused (editable queue + language chip in the promptbar) — Single-Row makes no sense here. If a user test demands a Single-Row variant, an additional LayoutMode is added. |
| ReprocessStaging + AudioFocus toggle | AudioFocus btn only visible in SingleRow; in ReprocessStaging-Two-Row therefore GONE | Predicate `false` in REPROCESS_STAGING | consistent |
| Preparing state + SingleRow toggle | Today: KSM.refresh is NOT triggered by SingleRowToggle (bug class "SSOT gap" in §7 of _pending-state-machine-visibility-owners.md) | In the refactor: `state.layout.singleRowMode` is part of the DictateUiState, every toggle produces a new state emission → the manager re-renders completely | Bug eliminated |
| Send-Mode → Recording (pause-stop and new start) | Today: race-prone (RecordingUi vs KeyboardUi) | The catalog picks deterministically via `forKeyboard()` | consistent |

---

## §9 Migration of Existing Classes — Code Pointer per Statement

### §9.1 KeyboardLayoutModeController → fully removed

| Today (source) | What happens | Target in the refactor |
|---|---|---|
| `KeyboardLayoutModeController.kt:47-50` (`csTwoRowAction = ConstraintSet().clone(views.actionRow)`, `csTwoRowInput = clone(views.inputRow)`) | deleted | `motion_scene_keyboard.xml/two_row_state` (declarative) |
| `KeyboardLayoutModeController.kt:66-74` (`originalParents` map with 7 views) | deleted | not needed — no re-parents in MotionLayout (L2) |
| `KeyboardLayoutModeController.kt:82` (`csSingleRow = buildSingleRowConstraintSet()`) | deleted | `motion_scene_keyboard.xml/single_row_state` |
| `KeyboardLayoutModeController.kt:97-101` (`init { setSingleRowMode(sp.get(Pref.SingleRowMode), animate=false) }`) | deleted | the first render of the `KeyboardLayoutManager` does `motionLayout.jumpToState(...)` (see ImeViewBackend.render §6) |
| `KeyboardLayoutModeController.kt:115-140` (`setSingleRowMode`) | deleted | Replaced by `motionLayout.transitionToState(targetSceneState)` (§6, line ~render) |
| `KeyboardLayoutModeController.kt:150-152` (`refresh()`) | deleted | not needed — the manager re-renders reactively via the StateFlow |
| `KeyboardLayoutModeController.kt:183-191` (`rehome(toSingleRow)`) | deleted | not needed — no re-parents (L2) |
| `KeyboardLayoutModeController.kt:202-272` (`buildSingleRowConstraintSet()`) | deleted | `motion_scene_keyboard.xml/single_row_state` |

**Net gain:** 273 lines of Kotlin deleted.

Code snippet from today's implementation (for verification that the migration is semantically identical):

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

→ replaced by (in the new `ImeViewBackend.render`):

```kotlin
val targetSceneState = mode.id.toSceneStateId()
if (state.layout.animationsEnabled) motionLayout.transitionToState(targetSceneState)
else                         motionLayout.jumpToState(targetSceneState)
```

**The `views.inputRow.visibility` toggle is dropped**, because `inputRow` no longer exists at all in the new architecture (flat hierarchy, L2). **The `views.audioFocusButtonInRow.visibility` toggle is dropped**, because the predicate lives in `KEYBOARD_SINGLE_ROW.AUDIO_FOCUS.visibilityPredicate = { true }` and `KEYBOARD_TWO_ROW.AUDIO_FOCUS.visibilityPredicate = { false }`.

### §9.2 MainButtonsController → ImeViewBackend

| Today | Target |
|---|---|
| `MainButtonsController.kt:76-79` (`recordClickListener` → `callback.onRecordClicked()`) | `ImeViewBackend.wireStaticHandlers` (RECORD slot → `actionResolver(state) = Action.RecordingAction.StartRecording(target = InsertionTarget.MainInputConnection) / StopRecordingAndSend / …`) |
| `MainButtonsController.kt:155-260` (`registerMainButtonListeners`, all 9 click/long-click/touch handlers) | `ImeViewBackend.wireStaticHandlers` + `actionResolver` per slot. Table in §13.2 |
| `MainButtonsController.kt:189-194` (`backspaceButton.setOnTouchListener(BackspaceSwipeHandler(...))`) | `ImeViewBackend.buildBackspaceSwipeHandler()` — same class, wired once in `attach()` (§11.7) |
| `MainButtonsController.kt:203-232` (CursorSwipeTouchHandler for space_btn) | `ImeViewBackend.buildSpaceTouchHandler()` (§11.7) |
| `MainButtonsController.kt:254-259` (`enterButton.setOnTouchListener(EnterOverlayHandler(...))`) | `ImeViewBackend.buildEnterOverlayHandler()` (§11.7) |
| `MainButtonsController.kt:251` (`overlayCharactersLl.visibility = VISIBLE` in enter-long-press) | stays — overlay-specific, not part of the LayoutCatalog visibility matrix |
| `MainButtonsController.kt:303-319` (`initializeKeyPressAnimations`) | `ImeViewBackend.wireStaticHandlers` calls `keyPressAnimator.applyPressAnimation(view)` for each button |
| `MainButtonsController.kt:331-333` (`setResendEnabled` for the 500ms cooldown) | stays as an orthogonal mutation on `view.isEnabled` (see §11.6 lifecycle note: this is triggered by a state update, not directly) |
| `MainButtonsController.kt:344-346` (`updateRecordButtonText`) | deleted — `textResolver` in the RECORD slot takes over |
| `MainButtonsController.kt:368-387` (`refreshAudioFocusIcon`) | `iconResolver` in the AUDIO_FOCUS slot |
| `MainButtonsController.kt:389-416` (`applyTheme` with `applyButtonColor`) | stays — the theme mutation is a separate axis, not state-driven. The ImeViewBackend has an `applyTheme(accentColor)` method that the service calls after each re-inflate. |
| `MainButtonsController.kt:424-437` (`animateSmallModeToggle`) | stays — external animation on `edit_numbers_btn`, not a slot resolver. Extracted as an `EditNumbersAnimator` helper. |
| `MainButtonsController.kt:452-477` (`animateEditNumbersBounce`) | identical to animateSmallModeToggle — remains in the EditNumbersAnimator |
| `MainButtonsController.kt:481-493` (`updateOverlayCharacters`) | stays — overlay-specific |

**Net gain:** approx. 200 lines from MainButtonsController move into ImeViewBackend (click logic replaced by the catalog → reduction by ~50 lines). Theme + animation helpers remain.

### §9.3 KeyboardStateManager → three owner classes (R.10)

<!-- FIX: Issue 2.1.13 / R.10 – KSM-Aufspaltung in ContentAreaController + PromptVisibilityController + OverlayResetHandler -->
**Today** `KeyboardStateManager` is a god class with three orthogonal responsibilities
(content-area container, prompt visibility, overlay-reset touchpoints). In the refactor the
class is split into **three small SRP-compliant owner classes**:

| Today's method | New owner class | Responsibility |
|-----------------|-------------------|---------------|
| `applyVisibility()` (lines 158-169) | (deleted) | The catalog predicates take over — no separate owner class needed |
| `applyContentAreaVisibility` (lines 171-181) | **`ContentAreaController`** (new, Spec 2 §9.3 / §13) | Owner: `mainButtonsCl` / `qwertz_container` / `emojiPicker_container` visibility (state.layout.contentArea axis). Optionally implemented as a second RenderBackend (Issue 2.1.15 / Option B). |
| `applyRecordingControlsVisibility` (lines 183-192) | (deleted) | Predicates in `predTrashVisible` / `predPauseVisible` (§8.5) |
| `applyPromptsVisibility` (lines 194-224) | **`PromptVisibilityController`** (new) | Owner: `prompts_container` + sub-views. Its own class, because the prompts hierarchy is orthogonal to the main-button area |
| `overlayCharactersLl.visibility = GONE` reset (line 162) | **`OverlayResetHandler`** (new) | Touchpoint for the overlay-characters-container reset; trivial, could also live in the EnterOverlayHandler — its own helper because the reset logic is reused |

**Migration strategy (R.13 / Issue 2.1.17):** in Block 5c the three old KSM methods
are replaced by **empty bodies** (no-op); the owner classes take over in parallel. Block 5d
deletes KSM completely. **Strict-mode logging** during 5c verifies that no
double mutation happens on a visibility axis.

**SOLID:** three classes × one responsibility each. KSM is no longer the only
SRP anti-pattern in the KeyboardLayoutManager region.

**Net gain:** class size drops from ~250 lines to 3×~50 lines. No central god class anymore.

### §9.4 RecordingUiController → KeyboardUiController portions + LayoutCatalog

| Today | Target |
|---|---|
| `RecordingUiController.kt:115-138` (`applyIdleState`) — sets `recordButton.text/isEnabled/CompoundDrawables`, calls `recordingAnimation.cancel()`, mutates `pauseButton.foreground`, **mutates `resendButton.visibility`** | deleted — `textResolver` (RECORD slot, §8.5), `iconResolver` (PAUSE slot), `RecordingAnimationController` (§11.5), predicate `predResendVisible` (§8.5) |
| `RecordingUiController.kt:140-142` (`applyPreparingState`) — `recordButton.isEnabled = false` | deleted — `enabledResolver` of the RECORD slots |
| `RecordingUiController.kt:144-184` (`applyActiveState`) — text "Senden", CompoundDrawables, **`resendButton.visibility = GONE`**, `recordingAnimation.start()`, prompt buttons | deleted / moves: text via resolver; resend via predicate; recordingAnimation via `RecordingAnimationController` |
| `RecordingUiController.kt:186-196` (`applyPausedState`) — pauseButton foreground, `recordingAnimation.pause()` | deleted — `iconResolver` PAUSE slot, `RecordingAnimationController` |
| `RecordingUiController.kt:51-60` (`onStateChanged` callback) | dropped — new architecture: a DictateUiState update is emitted by the service, the manager re-renders |
| `RecordingUiController.kt:62-64` (`onAmplitudeUpdate`) | stays — separate class `RecordingAnimationController` (§11.5) receives amplitudes via a separate hook |
| `RecordingUiController.kt:67-82` (`onTimerTick`) | stays partially — timer update on `recordingAnimation` and on `recordButton.text` (via a resolver that reads `state.recording.elapsedMs`) |
| `RecordingUiController.kt:222-246` (`updateQwertzRecButton`) | stays — the QWERTZ area is orthogonal, its own slot or its own controller |
| `RecordingUiController.kt:254-277` (`enterPipelineDisplay` / `updatePipelineTimer`) | stays — QWERTZ-specific, stays in the KeyboardUiController or is moved into the ContentArea controller |

**Net gain:** approx. 80 of the 280 lines from RecordingUiController deleted (all main-button mutations are dropped). The QWERTZ area + amplitude visualizer remain.

### §9.5 KeyboardUiController.refreshRecordButtonFromState → record_btn resolver

| Today | Target |
|---|---|
| `KeyboardUiController.kt:464-509` (Idle/Preparing/Running/ReprocessStaging branches in `refreshRecordButtonFromState`) | Split into `resolveRecordButtonText` (Idle), `resolveRecordButtonTextPipeline` (Preparing/Running) and `resolveRecordButtonTextStaging` (ReprocessStaging) — see §8.5 |
| `KeyboardUiController.kt:147-155` (`updateDictateUiState`) | deleted — DictateUiState is managed in the DictateOrchestrator (Spec 1 §4.3), mirrored from the DictateUiStateStore (Spec 1 §4.4) and emitted reactively <!-- FIX: Issue 2.0.2 – PipelineStateManager → DictateOrchestrator (Naming-Drift, F-11) --> |
| `KeyboardUiController.kt:241` (`infoCl.visibility = GONE` directly) | becomes `infoBarController.dismiss()` (see secondary weakness in _pending-state-machine-visibility-owners.md §1) |

### §9.6 DictateInputMethodService.java — the 4 problematic resend mutations

| Today | Target |
|---|---|
| `DictateInputMethodService.java:1345` (`resendButton.setVisibility(View.VISIBLE)` in the onStartInputView Idle branch) | deleted — the predicate takes over |
| `DictateInputMethodService.java:1347` (`resendButton.setVisibility(View.GONE)` in the onStartInputView Idle branch) | deleted — the predicate takes over |
| `DictateInputMethodService.java:1669` (`resendButton.setVisibility(View.GONE)` in `runTranscriptionViaOrchestrator`) | deleted — the predicate takes over (pipeline state switches to Preparing → predResendVisible = false) |
| `DictateInputMethodService.java:1839` (`resendButton.setVisibility(View.VISIBLE)` in `onShowResend()`) | becomes an action dispatch: `orchestrator.dispatch(Action.ResendAction.MarkLastAudio(exists = true))` → ResendModule.reduce sets `state.resend.lastAudioExists = true` → state is emitted → the predicate evaluates → resend becomes visible. <!-- FIX: Phase-B S-6 (2026-05-13) – Drift gegen F-8 (LocalBinder hat NUR `state` + `dispatch`, kein `markLastAudioExists`-Forwarder; siehe Spec 1 §5 LocalBinder-API). Action `MarkLastAudio(exists: Boolean)` ist in Spec 2 §3.3 `ResendAction.MarkLastAudio` bereits definiert. <!-- FIX: Phase-C C-3 (2026-05-14) – stale Z. 250 → Action-Name-Anchor (F-5-Pattern aus C-1). --> --> |

---

## §10 Acceptance Criteria

Block 4 (KeyboardLayoutManager + LayoutCatalog) is considered done when:
<!-- FIX: Phase-C C-4 (2026-05-14) – `pipelineService.state` → `pipeline.state` (LocalBinder, Spec 1 §5;
     F-11/G2-Naming-Drift homogenisiert mit §2 L6 + §11.8). -->
- [ ] The manager successfully subscribes to `pipeline.state` (LocalBinder, Spec 1 §5).
- [ ] On every state change the correct LayoutMode instance is picked from the catalog.
- [ ] All slots in all LayoutModes have predicates that match today's behaviour (verified against use-case list UC1-UC7 + UC-extra-1 to UC-extra-10).

Block 5 (ImeViewBackend + MotionLayout) is considered done when:
- [ ] The MotionLayout XML inflates correctly with all 9 buttons as direct children.
- [ ] Two-Row ↔ Single-Row transition animates smoothly (250ms), the pulse animation runs through undisturbed.
- [ ] Send-Mode + Single-Row: the Send button is fully visible, no hiding (bug elimination — covers main plan §1.1 bug symptom #3a).
- [ ] Toggle Single-Row in all pipeline states (Idle, Recording, Paused, Pipeline-Running, ReprocessStaging) correct.
- [ ] PulseLayout animation also runs during the MotionLayout transition (spike validation §11.3).
- [ ] Inflation cost on the first `onCreateInputView` < 50ms (spike measurement §11.4).
- [ ] Re-inflate (rotation, theme change): the first frame shows the correct LayoutMode without an animation snap (`jumpToState` instead of `transitionToState` on the first render).
- [ ] **The resend btn stays continuously visible during the Two-Row ↔ Single-Row toggle in Idle+lastAudio** (visibility=VISIBLE in every frame). Verified via Espresso `IdlingResource` or frame capture (see test §14.2 UI test 8 — new). Covers main plan §1.1 bug symptom #3b. <!-- FIX: Issue 3.0.9 – Resend-Toggle-Bug-Acceptance ergänzt -->
- [ ] **The resend-btn cooldown (500ms after click) leaves visibility=VISIBLE,** only enabled=false + alpha=0.4. See test §14.2 UI test 9 — new. <!-- FIX: Issue 3.0.9 -->
- [ ] **Active → Pipeline-Preparing transition:** no frame shows trash/pause over record_btn. See test §14.2 UI test 10 — new. <!-- FIX: Issue 3.0.9 -->
<!-- FIX: Issue 2.1.17 / R.13 + 2.1.18 / R.14 + 2.1.14 / R.11 – Acceptance-Erweiterungen -->
- [ ] **R.13 strict-mode logging during 5c:** `VisibilityWrite from $caller` log; the acceptance criterion "no two subsystems write simultaneously on a visibility axis" is verified.
- [ ] **R.14 firstRender flag on re-inflate:** the first render after `detach()` + `attach()` (rotation) calls `jumpToState` (no 250ms slide-in). A Robolectric test verifies the ConstraintSet animation = 0ms on the first render tick.
- [ ] **R.11 visibilityMode="ignore" on all 9 buttons:** XML lint check; each of the 9 button tags in the 4 MotionScene XMLs has the attribute. The catalog is the sole visibility owner (verified via test "MotionLayout transition does not accidentally set visibility").
<!-- FIX: Phase-B S-6 (2026-05-13) – Block-5-Deletion-Acceptance + R.13-Logger-Konkretisierung + WIDGET_TOGGLE-Slot-Render-Verifikation. -->
- [ ] **Phase-B S-6 — `KeyboardLayoutModeController.kt` file deleted:** `find app/src/main/java -name 'KeyboardLayoutModeController.kt'` returns an empty result after the Block-5d cleanup. Verification via a CI step or a plan-review check.
- [ ] **Phase-B S-6 — No orphaned callers of deleted KLMC/KSM methods:** `grep -rn 'KeyboardLayoutModeController\|setSingleRowMode\|csSingleRow\|csTwoRowAction\|csTwoRowInput\|applyContentAreaVisibility\|applyPromptsVisibility\|applyRecordingControlsVisibility' app/src/main/` returns ONLY hits in `docs/` and test files (if the regression suite uses mirror symbols) — no production code references the deleted symbols.
- [ ] **Phase-B S-6 — R.13 strict-mode logger concrete:** `VisibilityWriteAuditLogger` is its own class (`core/audit/VisibilityWriteAuditLogger.kt`), active in 5c via a `BuildConfig.DEBUG` guard and deleted without replacement in 5d. API: `fun logWrite(viewId: Int, caller: String, target: Int)` — `caller` extracted from `Thread.currentThread().stackTrace[2].className`. Acceptance: 0 logs after the Phase-5c soak test over 60 s (all 5 LayoutModes cycled through) — no second subsystem writes in parallel.
- [ ] **Phase-B S-6 — `widget_toggle_btn` is rendered in KEYBOARD_TWO_ROW + KEYBOARD_SINGLE_ROW** (predicate `viewMode == KEYBOARD`); in SEND_MODE + REPROCESS_STAGING it is GONE (`{ false }`). Test: `KEYBOARD_TWO_ROW.flatMap{it.slots}.map{it.logicalId}` contains `WIDGET_TOGGLE`; analogously SINGLE_ROW + SEND_MODE variants + REPROCESS_STAGING. The render loop raises `error(...)` if a LayoutMode does not define the slot (see §6 line 603 — silent-skip protection).
- [ ] **Phase-B S-6 — ButtonSlot.actionResolver 2-arg signature consistent:** all slot `actionResolver` lambdas in §8.1–§8.4 are implemented as 2-arg `{ _, _ -> ... }` / `{ state, _ -> ... }` / `{ _, services -> ... }` / a method reference `::resolveXxx` (the resolver function itself 2-arg). No `{ Action.X }` 0-arg lambda and no `{ state -> ... }` 1-arg lambda in the catalog anymore. Verification via a build smoke test (compile-time guarantee from the ButtonSlot type).

---

## §11 Research TODOs for the Agent — Concrete Answers

### §11.1 MotionLayout migration — concrete XML refactor diff

Diff between `activity_dictate_keyboard_view.xml` (today, lines 12-172) and the refactor (§7.2):

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

**Key observations from the diff:**
1. Both ConstraintLayout containers (`action_row`, `input_row`) are removed entirely.
2. The LinearLayout `main_buttons_cl` becomes the MotionLayout (with the same ID, so other constraints in the wrapper ConstraintLayout stay intact).
3. All 9 buttons become direct children of the MotionLayout — no more nesting.
4. **Constraints are no longer in the layout XML**, but in `motion_scene_keyboard.xml` (§7.1).
5. `tools:visibility` hints are preserved for the IDE preview.

**Implication for `KeyboardViews`** (data class in `KeyboardStateManager.kt:36-73`):
- `actionRow` / `inputRow` fields are **deleted**.
- `mainButtonsClTyped` is typed as `MotionLayout`.
- `audioFocusButtonInRow` becomes `audioFocusButton` (only one variant, since no longer re-parented).

### §11.2 LayoutCatalog: predicate verification

See §8.7 (table). All 9 buttons are verified in all 5 KEYBOARD LayoutModes against today's visibility matrix. Edge cases in §8.8.

**DRY proof:** all 5 LayoutModes use the **same** 3 predicate functions (`predResendVisible`, `predTrashVisible`, `predPauseVisible`) and the same 5 resolvers (`resolveRecordButtonText*`, `resolveTrashAction`, `resolvePauseAction`, `resolvePauseIcon`, `resolveRecordAction*`). Concretely: `predResendVisible` is used in each of the 5 modes as the `visibilityPredicate` for RESEND, with identical logic.

### §11.3 PulseLayout spike

**Spike goal:** verify that the `PulseLayout.startPulse()` animation keeps running during `motionLayout.transitionToState()`, without being cancelled.

**Spike code sketch:**

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

**Smoke-test criteria (passing):**
1. During the transition (250ms): `pulse.isPulsing == true` continuously in the TransitionListener.
2. Visually: the pulse circles keep rendering, without a visible reset (no `invalidate()` pause).
3. After the transition: PulseLayout is positioned in the new ConstraintSet (Single-Row vs. Two-Row), the animation keeps running with the same `animator.animatedFraction`.

**Known risk (to be validated by the spike):** MotionLayout performs partial view detach/re-attach during the transition. `PulseLayout.onDetachedFromWindow` (lines 136-140) calls `animator.cancel()` — that would stop the animation.

**Fallback on breakage:**
- **Option A (to be validated by the spike):** Wrap PulseLayout in a FrameLayout container that lives outside the MotionLayout and is positioned over the `record_pulse_layout` slot via a ConstraintHelper. The animation runs stably, since PulseLayout does not go through a detach-attach. Effort: medium.
- **Option B (fallback):** programmatic ConstraintSets instead of a MotionScene (Option 4 from motionlayout-architecture-options.md) — no re-attach phase, but no declarative MotionLayout animation system.

### §11.4 Inflation-cost measurement

**Profiling strategy:**

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

**Test devices:** Pixel 6 (mid-range), Pixel 4a (low-end — the critical data point), Samsung Galaxy A53 (typical mid-range with its own skin).

**Acceptance threshold:** `onCreateInputView` < 50ms on a Pixel 4a. If exceeded:

**Optimization strategies (staged):**
1. **Async inflate:** `AsyncLayoutInflater` for sub-views not visible in the first frame (emoji picker, QWERTZ container).
2. **Pre-inflate at service onCreate:** inflate the layout already at service start (not at keyboard open). Trade-off: service memory rises by ~2MB.
3. **Reduction of the MotionScene:** if deriveConstraintsFrom itself produces cost, merge intermediate states.

**Spike proposal (to be validated by the spike):** comparative measurement between
- (a) today's `LinearLayout` + 2 `ConstraintLayout` setup (baseline)
- (b) flat MotionLayout + scene XML

Expectation: (b) is not significantly more expensive (MotionLayout is ConstraintLayout + a state manager; ConstraintLayout inflation dominates).

### §11.5 BorderGlow animation migration: `RecordingAnimationController`

**Problem:** in the new reactive model the manager calls `actionResolver(state)`, but **no direct** `recordButton.foreground = ...` mutations — those are today in `BorderGlowAnimation.start()/pause()/cancel()` (BorderGlowAnimation.kt:62-110).

**Solution:** a separate class `RecordingAnimationController` that observes `state.recording` and animates on the `record_btn` view.

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

**Important:** `RecordingAnimationController` is **stateless** except for the `lastRecordingState` cache (a performance guard). It is a composition member of `ImeViewBackend` (see §6).

<!-- FIX: Issue 2.0.2 – PipelineStateManager → AudioModule/RecordingModule + LocalBinder-Spec-1-§5-Querverweis -->
<!-- FIX: Phase-B S-3 (2026-05-13) – "Callback-Methode am LocalBinder" widersprach F-8 (LocalBinder hat NUR state + dispatch). Side-Channel jetzt explizit als zweiter StateFlow am LocalBinder vorgesehen. -->
**Amplitude/timer hooks:** come directly from the AudioModule / RecordingModule (Spec 1 §15) — not via a `DictateUiState` emission, because that would lead to new state allocations per tick. Instead: an additional `StateFlow<AmplitudeTick>` on the LocalBinder (analogous to `state: StateFlow<DictateUiState>`, NOT as a callback method — F-8 forbids a typed forwarder on the LocalBinder). The concrete API is to be added in Spec 1 §5 if the amplitude axis lands in the refactor scope; otherwise it stays outside the state store until Phase 2 (a side channel via `services.amplitudeStream`).

### §11.6 Click-listener lifecycle: memory-leak analysis

**Problem hypothesis:** `ImeViewBackend.render()` sets per slot `view.setOnClickListener { onAction?.invoke(slot.actionResolver(state)) }`. On every render tick (pipeline running, every few 100ms) a new lambda is created that closes over `state` and `slot`. The old lambda is replaced by `setOnClickListener` → GC-eligible.

**Risk 1:** In fast tick intervals lambdas accumulate briefly (allocation pressure). With a 100ms tick and 9 buttons that is 90 lambdas/second that become GC-liable — moderate allocation cost, but measurable on low-end devices.

**Risk 2:** If a `state` snapshot is captured in a lambda that is executed by the system with a delay (e.g. a touch event in the frame queue), the click shows the `actionResolver` of a **stale** state. With a pipeline-tick race: a click on record_btn could emit `null` (old Pipeline.Running state, R.3: no-op resolver) instead of `Action.RecordingAction.StartRecording(target = InsertionTarget.MainInputConnection)` (new Idle state) — or vice versa, a Recording-Cancel action instead of a Pipeline-Cancel action. <!-- FIX: Phase-B S-3 (2026-05-13) – Action.NoOp existiert post-R.3 nicht mehr; Resolver returnt Action? (`null` = "kein Click-Effekt im aktuellen State"). -->

**Recommendation (L8):** set click listeners only once per backend attach, read the current state at click time from a backend field (`stateRef`).

```kotlin
// Setup einmalig in attach()/wireStaticHandlers()
view.setOnClickListener {
    onVibrate()
    val s = stateRef ?: return@setOnClickListener     // aktueller State
    val slot = currentSlot(id) ?: return@setOnClickListener
    // FIX: Phase-B S-3 (2026-05-13) – nullable Resolver-Idiom (R.3): null = "Click ist No-Op
    // im aktuellen State" → kein dispatch. Vorher `onAction?.invoke(slot.actionResolver(s))`
    // hätte bei null-Resolver einen NPE produziert (oder bei `onAction?.invoke(null!!)` einen
    // Kotlin-Type-Error, weil onAction: (Action) -> Unit nicht-nullable ist). Konsistent
    // mit §6 wireStaticHandlers und Spec 3 §4.2.
    // FIX: Phase-B S-7 (2026-05-13) – 2-arg Resolver (state, services) für Pre-Dispatch-Allocation (R.2).
    slot.actionResolver(s, services)?.let { onAction?.invoke(it) }
}
```

**Advantages:**
- One lambda allocation per button per lifecycle (instead of per tick) → allocation-free during recording.
- The click always reads the **current** state, not the render snapshot → no race.

**Disadvantage:** somewhat more indirect code readability (`currentSlot(id)` is looked up at runtime). Mitigation: `currentSlot(id)` is a simple `flatMap.firstOrNull` over the ~9 slots → O(9) is negligible.

**Memory-leak question:** the lambdas reference `stateRef`/`modeRef` (backend fields). The backend lifecycle is bound to the view lifecycle (`onCreateInputView` → `onDestroy`). When the view is disposed, the backend goes with it — no GC roots, no leak.

### §11.7 Special touch handlers: CursorSwipe / Backspace-Swipe / Enter-Overlay

Three touch handlers have state machines that do not fit into catalog slots (a slot is centred on `setOnClickListener`). Solution: wire them once in the `attach()` callback, **not** per render.

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

**Consideration:** the touch handlers are wired once in `attach()` (L9). They are state-free, do not need re-wiring per render. If `onTap` should emit an `Action`, that goes through the backend `onAction` field — the same pattern as the click listeners (§11.6).

**Special:** today's `EnterOverlayHandler` mutates `overlayCharactersLl.visibility = GONE` directly (lines 56, 62). That is superfluous in the new system — the visibility is set to GONE by `KeyboardStateManager.applyVisibility` respectively (line 162). Still: the local reset logic in the handler stays (defensive depth) — not a bug.

### §11.8 Migration order

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
   │     `pipeline.state.collect { manager.onStateChanged(it) }`  // <!-- FIX: Phase-C C-4 (2026-05-14) –
   │                                                              //      F-11/G2 `pipelineService` → `pipeline`
   │                                                              //      (LocalBinder, Spec 1 §5). -->.
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

**Rationale for the order:**
- 5a/5b can be parallelized, since they are testable on mock state.
- **5c has empty KSM bodies** (R.13 Option A+C): structurally eliminates the double mutation;
  the strict-mode log makes the acceptance verifiable.
- 5d is destructive — strictly at the end, the KSM class disappears entirely.

**Line budget per block:**
- 5a (XML): ~400 lines of scene + ~80 lines of layout patch.
- 5b (backend): ~250 lines ImeViewBackend + ~80 lines RecordingAnimationController.
- 5c (wiring): ~30 lines in the service.
- 5d (cleanup): ~370 lines deleted (KLMC 273 + KSM portion 30 + RUC portion 80 + service resend mutations 5).

**Net line balance:** ~+760 new, ~−400 deleted → +360 lines. But: dramatically lower complexity (declarative instead of imperative, single owner, no re-parenting).

---

## §12 References

### Phase-2 research (input material)

- [main-button-area-inventory.md](../main-button-area-inventory.md) — complete capability inventory.
- [motionlayout-architecture-options.md](../motionlayout-architecture-options.md) — rationale for the MotionLayout choice.
- [_pending-layout-container-architecture.md](../_pending-layout-container-architecture/_pending-layout-container-architecture.md) — confirms MotionLayout, identifies spike points.
- [_pending-state-machine-visibility-owners.md](../_pending-state-machine-visibility-owners/_pending-state-machine-visibility-owners.md) — complete visibility-mutation map.

### Code pointers (today)

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

### External references

- MotionLayout docs: https://developer.android.com/develop/ui/views/animations/motionlayout
- MotionScene XML: https://developer.android.com/reference/androidx/constraintlayout/motion/widget/MotionScene
- VISIBILITY_MODE_IGNORE: https://stackoverflow.com/questions/57889399/motionlayout-ignore-visibility

### ADRs (Block-0 artefacts, bidirectional)

- [ADR-0001 — state-modular-orchestrator-pattern](../../../../decisions/0001-state-modular-orchestrator-pattern.md) — binds §3.3 (Action sealed hierarchy), §4.1 (KeyboardLayoutManager ↔ LayoutModule contract), §5 (RenderBackend reads StateFlow, never writes).
- [ADR-0004 — ui-layout-catalog-motionlayout](../../../../decisions/0004-ui-layout-catalog-motionlayout.md) — binds §3 (data model ButtonSlot/RowDescriptor/LayoutMode), §4–§4.1 (KeyboardLayoutManager + multi-backend), §5–§5.1 (RenderBackend + SlotRenderer F-7), §6 (ImeViewBackend), §7 (MotionScene XML + VISIBILITY_MODE_IGNORE), §8 (LayoutCatalog), §11.6 (click-listener lifecycle L8).

### Architecture docs (Block-0 artefacts, teaching-explanatory)

- [`docs/architecture/state-architecture/rendering.md`](../../../../architecture/state-architecture/rendering.md) — RenderBackend pattern, LayoutCatalog, MotionScene convention.
- [`docs/architecture/state-architecture/wiring-ui.md`](../../../../architecture/state-architecture/wiring-ui.md) — click-listener once-wiring + special touch handlers.
- [`docs/architecture/state-architecture/adding-a-button.md`](../../../../architecture/state-architecture/adding-a-button.md) — walkthrough for new buttons.
- [`docs/architecture/state-architecture/adding-a-sub-keyboard.md`](../../../../architecture/state-architecture/adding-a-sub-keyboard.md) — walkthrough for new sub-keyboards.

---

## §13 Completeness Verification

**User requirement:** complete centralization of state and functionality, with consistent SOLID/DRY application.

### §13.1 Visibility-mutation audit

All of today's `.visibility =` and `setVisibility(...)` calls from the `core/`/`keyboard/`/`widget/` package that are UI-relevant for the main-button area. Source: [§1 visibility-mutation map in _pending-state-machine-visibility-owners.md](../_pending-state-machine-visibility-owners/_pending-state-machine-visibility-owners.md).

| # | View | file:line | Status after the refactor |
|---|---|---|---|
| 1 | `mainButtonsClTyped` | KeyboardStateManager.kt:172 | **STAYS** — belongs to the orthogonal ContentArea axis (main-button container vs. QWERTZ vs. emoji), not part of the LayoutCatalog. Extracted into a `ContentAreaController` (Block 5d cleanup). |
| 2 | `editButtonsLl` | KeyboardStateManager.kt:174 | **STAYS** (ContentArea axis) |
| 3 | `qwertzContainer` | KeyboardStateManager.kt:177 | **STAYS** (ContentArea axis) |
| 4 | `emojiPickerCl` | KeyboardStateManager.kt:179 | **STAYS** (ContentArea axis) |
| 5 | `pauseButton` | KeyboardStateManager.kt:187 | **MOVES** into `predPauseVisible` (KEYBOARD_TWO_ROW + KEYBOARD_SINGLE_ROW + KEYBOARD_REPROCESS_STAGING, PAUSE slot) |
| 6 | `trashButton` | KeyboardStateManager.kt:191 | **MOVES** into `predTrashVisible` (all KEYBOARD modes, TRASH slot) |
| 7 | `promptsCl` | KeyboardStateManager.kt:206 | **STAYS** (promptbar axis, its own subsystem; not part of Spec 2) |
| 8 | `promptsRv` | KeyboardStateManager.kt:210 | **STAYS** (promptbar) |
| 9 | `pipelineProgressLl` | KeyboardStateManager.kt:212 | **STAYS** (promptbar) |
| 10 | `promptRecordingControlsLl` | KeyboardStateManager.kt:218 | **STAYS** (promptbar) |
| 11 | `overlayCharactersLl` (reset) | KeyboardStateManager.kt:162 | **STAYS** (defensive reset of the transient overlay) |
| 12 | `overlayCharactersLl` (long-press open) | MainButtonsController.kt:251 | **MOVES** into the long-click handler of ENTER (in `wireStaticHandlers`) |
| 13 | `overlayCharactersLl` (per-slot) | MainButtonsController.kt:485,487 | **STAYS** (theme-internal, separate animation/theme class) |
| 14 | `overlayCharactersLl` (touch) | EnterOverlayHandler.kt:56,62 | **STAYS** (touch-handler-internal — see §11.7) |
| 15 | `infoCl.dismiss` | InfoBarController.kt:49 | **STAYS** (InfoBar-internal) |
| 16 | `infoCl.show` | InfoBarController.kt:57 | **STAYS** (InfoBar-internal) |
| 17 | `infoCl` direct mutation | KeyboardUiController.kt:241 | **FIXED** as a secondary cleanup: replace with `infoBarController.dismiss()` |
| 18-20 | `infoYesButton/infoNoButton` | InfoBarController.kt:65-163 | **STAYS** (InfoBar-internal) |
| 20 | Pipeline-step-row binding | KeyboardUiController.kt:383-448 | **STAYS** (pipeline-step-internal, promptbar) |
| 21 | `inputRow` | KeyboardLayoutModeController.kt:133 | **REMOVED** — `inputRow` no longer exists (L2, flat hierarchy) |
| 22 | `audioFocusButtonInRow` | KeyboardLayoutModeController.kt:138 | **MOVES** into the AUDIO_FOCUS slot predicate (`true` in SINGLE_ROW + SINGLE_ROW_SEND_MODE, `false` otherwise) |
| 22b | `widget_toggle_btn` | NEW (Spec 3 OPEN-2 / Phase-1 1.0.2 / Spec 1 §15 OverlayModule) | **NEW** — LayoutCatalog `WIDGET_TOGGLE` slot, predicate `{ state.viewMode == ViewMode.KEYBOARD }`. Visible in the IME view, dispatches a toggle to the WIDGET mode. <!-- FIX: Issue 3.0.12 – WIDGET_TOGGLE in §13.1 nachgepflegt (Phase-1-1.0.2-Followup) --> |
| **23** | **`resendButton` (Idle)** | **RecordingUiController.kt:137** | **REMOVED** — `predResendVisible` (RESEND slot) takes over |
| **24** | **`resendButton` (Active)** | **RecordingUiController.kt:158** | **REMOVED** — the predicate takes over |
| **25** | **`resendButton` (onStartInputView V)** | **DictateInputMethodService.java:1345** | **REMOVED** — the predicate takes over |
| **26** | **`resendButton` (onStartInputView G)** | **DictateInputMethodService.java:1347** | **REMOVED** — the predicate takes over |
| **27** | **`resendButton` (Pipeline-Start)** | **DictateInputMethodService.java:1669** | **REMOVED** — the predicate takes over (Pipeline=Preparing → predResendVisible=false) |
| **28** | **`resendButton` (onShowResend)** | **DictateInputMethodService.java:1839** | **REMOVED** — becomes `pipeline.dispatch(Action.ResendAction.MarkLastAudio(exists = true))` (LocalBinder F-8: only `state` + `dispatch`, no typed forwarder; see §9.6 + Spec 1 §5). <!-- FIX: Phase-C C-4 (2026-05-14) – stale `pipelineService.markLastAudioExists(true)`-Phantom-API durch konkrete Action-Dispatch-Form ersetzt (F-8 LocalBinder-API ist NUR `state` + `dispatch(action): DispatchOutcome`; F-11-Naming `pipelineService` → `pipeline`). §9.6-Zeile dokumentierte die korrekte Form bereits — Zeile 28 hier blieb auf Pre-F-8/F-11-Form stehen und widersprach §9.6 internal. --> |

**Verification:** all 27 mutations listed in `_pending-state-machine-visibility-owners.md` §1 are explicitly addressed. The 5+ problematic `resend_btn` mutations (#23-#28) are all REMOVED in favour of **one** predicate `predResendVisible` (see §8.5).

### §13.2 Click-listener audit

All of today's click/long-click/touch-handler setups in `MainButtonsController.kt` (see `MainButtonsController.kt:155-260`) and their migration:

| Today (file:line) | Slot/handler in the refactor |
|---|---|
| `recordButton.setOnClickListener(recordClickListener)` (line 160) | `RECORD.actionResolver` → resolveRecordAction (StartRecording / StopRecordingAndSend) |
| `recordButton.setOnLongClickListener` (line 163) | wireStaticHandlers (RECORD long-click) — long-click consumed (`return true`), no action emit (R.3 — nullable-resolver idiom; today's `onRecordLongClicked` is settings-open via an Activity intent, no state mutation) |
| `resendButton.setOnClickListener` (line 170) | `RESEND.actionResolver` → Action.ResendAction.ResendLastAudio |
| `resendButton.setOnLongClickListener` (line 174) | wireStaticHandlers (RESEND long-click) → Action.ResendAction.ResendLastAudioLong |
| `backspaceButton.setOnClickListener` (line 181) | `BACKSPACE.actionResolver` → Action.KeyboardInputAction.Backspace |
| `backspaceButton.setOnLongClickListener` (line 185) | wireStaticHandlers (BACKSPACE long-click) → concrete long-action (auto-delete) |
| `backspaceButton.setOnTouchListener(BackspaceSwipeHandler)` (line 189) | `buildBackspaceSwipeHandler()` (§11.7) — once in attach() |
| `trashButton.setOnClickListener` (line 197) | `TRASH.actionResolver` → resolveTrashAction (CancelRecording / CancelReprocessStaging) |
| `spaceButton.setOnTouchListener` with CursorSwipeTouchHandler (line 225) | `buildSpaceTouchHandler()` (§11.7) |
| `pauseButton.setOnClickListener` (line 235) | `PAUSE.actionResolver` → resolvePauseAction (PauseRecording / ResumeRecording) |
| `audioFocusButton.setOnClickListener(audioFocusClickListener)` (line 242) | `AUDIO_FOCUS.actionResolver` → Action.AudioAction.ToggleAudioFocusPref |
| `editAudioFocusButton.setOnClickListener(audioFocusClickListener)` (line 124) | **STAYS** — the edit-bar audio-focus btn is **outside** the main-button area (separate axis, separate class) |
| `enterButton.setOnClickListener` (line 245) | `ENTER.actionResolver` → Action.KeyboardInputAction.EnterKey |
| `enterButton.setOnLongClickListener` (line 249) | wireStaticHandlers (ENTER long-click) — opens `overlay_characters_ll` (view mutation, no action) |
| `enterButton.setOnTouchListener(EnterOverlayHandler)` (line 254) | `buildEnterOverlayHandler()` (§11.7) |
| **WIDGET_TOGGLE — NEW (Spec 3 OPEN-2)** | NEW — no migration source today. `WIDGET_TOGGLE.actionResolver` → `Action.ViewModeAction.ToggleViewModeWidget` (see Spec 3 §7.3 T1 + §13.4). <!-- FIX: Issue 3.0.12 – WIDGET_TOGGLE in §13.2 nachgepflegt --> |
| `editNumbersButton.setOnClickListener` (line 102) | **STAYS** in the EditBar controller — `Action.LayoutAction.ToggleSmallMode` is emitted <!-- FIX: Issue 3.0.5 – flache Action.ToggleSmallMode → hierarchisch --> |
| `editNumbersButton.setOnLongClickListener` (line 112) | **STAYS** in the EditBar controller — `Action.LayoutAction.ToggleSingleRowMode` <!-- FIX: Issue 3.0.5 --> |
| `editSettingsButton/editHistoryButton/pipelineCancelBtn` (lines 118-120) | **STAYS** in the EditBar controller — separate axis |
| `editKeyboardButton` (line 126/131) | **STAYS** in the EditBar controller — `Action.LayoutAction.SetContentArea(QWERTZ)` (formerly "ToggleQwertz", post-F-11 modelled canonically as a ContentArea variant in §3.3) <!-- FIX: Issue 3.0.5 - Pre-F-11-„ToggleQwertz" auf SetContentArea-Variante umgestellt --> |
| Edit actions undo/redo/cut/copy/paste (lines 138-150) | **STAYS** in the EditBar controller |
| Emoji listener (lines 264-280) | **STAYS** in the EmojiController |

**Finding:** all 9 main-button-area click handlers move into `actionResolver` slots. The edit bar (outside the main-button area) stays in a separate `EditBarController` that does not change. Click listeners are wired once in `wireStaticHandlers` (L8 / §11.6) — no new lambda allocation per render tick.

### §13.3 SOLID verification per new class

#### KeyboardLayoutManager

- **SRP (Single Responsibility):** render orchestration. Subscribes to the StateFlow, picks a LayoutMode from the catalog, calls `backend.render`. No visibility computation itself, no view manipulation. ✓
- **OCP (Open/Closed):** new LayoutModes are added via a catalog extension. Manager code unchanged. Precondition: `forKeyboard` returns the matching LayoutMode for each state (open for new state axes). ✓
- **LSP:** N/A (no inheritance).
- **ISP:** the manager knows only the `RenderBackend` interface with 3 methods. ✓
- **DIP (Dependency Inversion):** depends on the `RenderBackend` interface, not on `ImeViewBackend`/`OverlayBackend` directly. Implemented via constructor injection of an `onAction` callback. ✓

#### LayoutCatalog

- **SRP:** a data registry — defines the 5 KEYBOARD LayoutModes + the `forKeyboard(state)` selection. **No behaviour** other than the selection. ✓
- **DRY proof (see §13.4):** all predicates and resolvers live **outside** the LayoutMode definitions, are referenced. **No** duplicate logic in two LayoutModes. ✓
- **Type assessment:** should `LayoutCatalog` have behaviour? — **No.** Predicates and resolvers belong to slots (data members of the `ButtonSlot`); the catalog is an `object` (singleton data container). Verification: no methods on `LayoutCatalog` other than `forKeyboard(state): LayoutMode` (that is a pure data selection, no view manipulation). ✓

#### ImeViewBackend

- **SRP:** renders to the IME view. Translates LogicalButtonId to an Android view via the `findViewById` map, applies slot properties, dispatches click events. No pipeline knowledge. ✓
  - Verification: ImeViewBackend knows `DictateUiState` as an opaque data type (it calls `slot.visibilityPredicate(state)` without reading state internals). ✓
- **OCP:** new slots are taken up via a LayoutMode extension, provided the `LogicalButtonId` is present in the `buttonViews` map. If not: a 1-line extension. ✓
- **DIP:** depends on the `RenderBackend` interface. Receives `inputConnectionProvider`, `keyPressAnimator`, `recordingAnimationController` as constructor parameters (not instantiated directly). ✓

#### ButtonSlot

- **Data class:** all properties are `val` (immutable). No methods other than resolver calls (these are lambdas, not behaviour of the class). ✓
- Verification: `ButtonSlot` has **no** behaviour that mutates state. Resolvers only read. ✓

#### RecordingAnimationController

- **SRP:** coordinates the two animation subsystems (BorderGlowAnimation + PulseLayout) against the `RecordingState`. No view mutation other than the two animation APIs. ✓
- **Cache optimization (`lastRecordingState`):** a performance guard, not a state owner.

#### KeyboardLayoutModeController, MainButtonsController, RecordingUiController.applyXxxState

- **DELETED** (KeyboardLayoutModeController) respectively **REDUCED** (MainButtonsController click logic, RecordingUiController apply methods).

### §13.4 DRY verification

#### Two-Row and Single-Row: shared predicate definition

| Slot | Two-Row predicate | Single-Row predicate | Identical? |
|---|---|---|---|
| RECORD | `{ true }` | `{ true }` | ✓ |
| RESEND | `predResendVisible` | `predResendVisible` | ✓ — **same function** |
| BACKSPACE | `{ true }` | `{ true }` | ✓ |
| AUDIO_FOCUS | `{ false }` | `{ true }` | intentionally different (definition of the mode) |
| TRASH | `predTrashVisible` | `predTrashVisible` | ✓ — **same function** |
| SPACE | `{ true }` | `{ true }` | ✓ |
| PAUSE | `predPauseVisible` | `predPauseVisible` | ✓ — **same function** |
| ENTER | `{ true }` | `{ true }` | ✓ |

**Proof:** identical visibility logic is defined **once** via three top-level functions (`predResendVisible`, `predTrashVisible`, `predPauseVisible`) and referenced at **all places**. These three functions have exactly ONE definition each (in §8.5). No duplication.

#### Send-Mode variants: commonalities and differences

| Slot | TWO_ROW_SEND_MODE | SINGLE_ROW_SEND_MODE | Shared |
|---|---|---|---|
| RECORD textResolver | `resolveRecordButtonTextPipeline` | `resolveRecordButtonTextPipeline` | ✓ |
| RECORD enabledResolver | `{ it.pipeline !is Preparing }` | `{ it.pipeline !is Preparing }` | ✓ |
| RECORD actionResolver | `resolveRecordActionPipeline` | `resolveRecordActionPipeline` | ✓ |
| RESEND visibility | `{ false }` | `{ false }` | ✓ |
| TRASH visibility | `{ false }` | `{ false }` | ✓ |
| PAUSE visibility | `{ false }` | `{ false }` | ✓ |
| BACKSPACE/SPACE/ENTER | `{ true }` | `{ true }` | ✓ |
| AUDIO_FOCUS visibility | `{ false }` | `{ true }` | intentionally different (definition of Single-Row) |

**What differs:** **only** AUDIO_FOCUS visibility. This is **the only axis** that distinguishes TWO_ROW from SINGLE_ROW — the logic is consistent between the standard and send-mode variants.

**Possible further DRY optimization (to be validated by the spike):** instead of 4 LayoutMode constants (TWO_ROW, SINGLE_ROW, TWO_ROW_SEND, SINGLE_ROW_SEND) a single `LayoutMode` with conditional predicates that read `state.layout.singleRowMode` and `state.pipeline` directly. **Trade-off:** fewer constants, but more complex predicates per slot. Recommendation: **stay with 4 constants** — readability > code compactness, and the DRY obligation is already fulfilled by the shared predicate/resolver functions.

#### Resolver DRY across multiple slots

| Resolver | Used in |
|---|---|
| `resolveRecordButtonText` | RECORD slot in TWO_ROW + SINGLE_ROW |
| `resolveRecordButtonTextPipeline` | RECORD slot in TWO_ROW_SEND + SINGLE_ROW_SEND |
| `resolveRecordButtonTextStaging` | RECORD slot in REPROCESS_STAGING |
| `resolveTrashAction` | TRASH slot in TWO_ROW + SINGLE_ROW (REPROCESS_STAGING has its own `Action.PipelineAction.CancelReprocessStaging` direct value, because semantically fixed) |
| `resolvePauseAction` | PAUSE slot in TWO_ROW + SINGLE_ROW |
| `resolvePauseIcon` | PAUSE slot in TWO_ROW + SINGLE_ROW |

**Finding:** resolvers are consistently shared where the logic is identical. There is **no** slot definition in which a resolver is defined inline and identical logic also exists inline in another slot.

#### Slot-apply helper for both backends (F-7 / DRY)

| What | Today | Future (one source) |
|---|---|---|
| Slot → view properties (visibility/enabled/alpha/icon/text) | duplicated in `ImeViewBackend.applySlotProperties` (§6) and `OverlayBackend.applySlots` (Spec 3 §4.2) — 2× seven lines of identical setter logic | Top-level function `applySlotToView(slot, view, state, ctx)` in `keyboard/render/SlotRenderer.kt` (§5.1). Both backends call it. |

**DRY proof:** if the slot-property mapping is extended by `contentDescription`, `tint` or another axis, there is **one** place where the setter pattern lives — not two. This structurally excludes drift between the two backends.

#### AudioFocus-icon resolver (F-4 / DRY)

| What | Today | Future (one source) |
|---|---|---|
| `audioFocusEnabled` → icon resource | duplicated: once in `MainButtonsController.kt:368-387` for the main-button area, once for `edit_audio_focus_btn` in the edit bar | Top-level function `resolveAudioFocusIcon(enabled)` in §8.5. The AUDIO_FOCUS slot uses it via `iconResolver`; `EditBarController.refreshAudioFocusIcon` calls it as well. |

**DRY proof:** both consumers (slot AND edit bar) read from the same StateFlow AND map via the same function. If the icon set changes (e.g. Material Symbols instead of Material Icons), exactly one place needs adjustment.

### §13.5 Identified Gaps + Mitigations

<!-- FIX: Issue 3.0.7 – §13.5 in drei Bereiche gegliedert (Open / Cross-Spec-Pending / Resolved); Audit-Funktion wieder klar -->

Points uncovered during the verification that should be added to the plan:

#### §13.5.a Open Gaps

(Gap 3 — ContentArea axis — stays open as an implementation hint. Gap 5 — migration order — stays open as a coordination hint. Both below in the §13.5.a subsections.)

#### §13.5.b Cross-Spec Patches Pending

(The Phase-1 apply has incorporated all known cross-spec patches. The WIDGET_TOGGLE slot is now, after Phase-B S-6 (2026-05-13), explicitly anchored in **all 5 KEYBOARD LayoutModes** §8.1–§8.4: visibilityPredicate `{ viewMode == ViewMode.KEYBOARD }` in TWO_ROW + SINGLE_ROW; `{ false }` in the SEND_MODE variants + REPROCESS_STAGING — no mode change mid-pipeline. The final position may still be refined in the OPEN-2 apply (Spec 3), but the slot no longer falls through the render silent-skip hole.)

#### §13.5.c Resolved (Iter History)

(Gap 1 — edit-bar audio-focus btn — RESOLVED via F-4. Gap 2 — resend cooldown — RESOLVED via the §3 extension + the Phase-2-2.0.12 inline doc in §8.5. Gap 4 — BorderGlow animation — RESOLVED via the reactive animation binding to `state.recording`.)

---

**Gap 1: The edit-bar audio-focus btn (`edit_audio_focus_btn`) is not in the LayoutCatalog** — *Status: RESOLVED (§13.5.c)*

- **Today:** the edit bar has its own audio-focus variant (`edit_audio_focus_btn` in `editButtonsLl`) that is always visible. Today's `MainButtonsController.refreshAudioFocusIcon` (lines 368-387) synchronizes both buttons.
- **Refactor:** the edit bar is **not** part of the main-button area, hence not in the LayoutCatalog. The edit-bar variant of the audio-focus btn stays in its own `EditBarController`.
- **RESOLVED via F-4 (iteration 2026-05-08):** the shared helper `resolveAudioFocusIcon(enabled)` (§8.5) is used **both** by the AUDIO_FOCUS slot **and** by the EditBarController. Both listen to the same StateFlow AND map via the same function → a true SSOT, no two code paths anymore. (The previous mitigation "both listen to the StateFlow" eliminated the sync race but not the code duplication of the mapping function — F-4 closes this last gap.)

**Gap 2: `setResendEnabled(false)` cooldown after a resend click (500ms)** — *Status: RESOLVED (§13.5.c) via the §3 `resendCooldown` extension + the Phase-2-2.0.12 inline doc in §8.5*

- **Today:** `MainButtonsController.kt:331-333` temporarily disables the resend btn to prevent double clicks. Trigger: `Service.onResendClicked` → 500ms cooldown.
<!-- FIX: Phase-B S-1 (2026-05-13) – flacher state.resendCooldown → hierarchisch state.resend.resendCooldown -->
- **Refactor:** that is a **transient state** axis (`resendCooldown: Boolean`). Solution: `DictateUiState` already contains the field as `state.resend.resendCooldown` (see Spec 1 §3, `ResendState`), set to `true` by the `ResendModule` reducer after a click and reset after 500ms via a timer effect — **no `Handler.postDelayed` in the service**, but an `Effect.StartResendCooldownTimer(500ms)` from the module. The `enabledResolver` for the RESEND slot reads both axes: `{ !state.resend.resendCooldown }`.
- **Mitigation:** the field is already anchored in Spec 1 §3 (`ResendState.resendCooldown`). No additional schema extension needed.

**Gap 3: ContentArea axis vs. LayoutCatalog** — *Status: Open (§13.5.a) — implementation hint*

- **Today:** ContentArea (MAIN_BUTTONS / QWERTZ / EMOJI_PICKER) is **orthogonal** to the LayoutMode. If ContentArea ≠ MAIN_BUTTONS, the entire `mainButtonsClTyped` is GONE — all slots become invisible.
- **Refactor:** the LayoutCatalog covers only the case ContentArea = MAIN_BUTTONS. The `ContentAreaController` (extracted from `KeyboardStateManager.applyContentAreaVisibility`) sets the **container** to GONE.
<!-- FIX: Phase-B S-1 (2026-05-13) – flacher state.contentArea → hierarchisch state.layout.contentArea (R.5 LayoutState-Container) -->
- **Mitigation:** no duplication — the catalog always renders slot properties, but if the container is GONE it is visually irrelevant (no additional effort). **Possible optimization:** an early return in `ImeViewBackend.render` if `state.layout.contentArea != ContentArea.MAIN_BUTTONS`. Trade-off: a performance win vs. inconsistency when ContentArea switches back to MAIN_BUTTONS — the backend would then have to force a re-render. **Recommendation:** no early return, always render (idempotent). Performance is OK, because the slot properties on a GONE view do not trigger a layout pass.

**Gap 4: BorderGlow animation during the pipeline (Send-Mode)** — *Status: RESOLVED (§13.5.c)*

- **Today:** BorderGlowAnimation runs during recording (Active/Paused). On the transition to Pipeline-Running it is stopped via `RecordingUiController.applyIdleState` → `recordingAnimation.cancel()`. But: in pipeline mode the record_btn shows "Sende… 2/3 0:08", the animation is GONE — the live amplitude no longer exists either.
- **Refactor:** the `RecordingAnimationController` (§11.5) reacts to `state.recording`. In pipeline mode `state.recording = Idle` → the animation is cancelled. Consistent with today. ✓
- **No action needed.**

**Gap 5: Migration order versus the main-plan block order** — *Status: Open (§13.5.a) — coordination hint*

- The main plan lists 6 blocks (1: State-SSOT, 2: foreground service, 3: DB, 4: Manager+Catalog, 5: ImeViewBackend, 6: OverlayBackend).
- Block 1 (State-SSOT consolidation in Spec 1) must be completed **before** Block 4 (manager), because the manager expects `DictateUiState` with correct ownership (no more hybrid resend_btn state).
- **Mitigation:** Block 1 implements the `predResendVisible` consolidation **already** before the refactor — this eliminates the 6-mutator race **within today's code**, without MotionLayout. Block 5 then removes the last remnants (KeyboardLayoutModeController + the now-centralized `RecordingUiController.applyXxxState`). This order is consistent — explicitly documented in main plan §4 and Spec 1 §10 / Spec 2 §11.8.

---

## §14 Test Strategy

### §14.1 Today's layout tests

Current test inventory (assumption-based; concrete test inventory to be carried out in Block 4):

| Class | Today's tests | Broken by the refactor? |
|---|---|---|
| KeyboardLayoutModeController | unit tests for `setSingleRowMode`, `rehome`, `buildSingleRowConstraintSet` | **YES** — the class is deleted. The tests are dropped. |
| KeyboardStateManager | unit tests for the `applyVisibility` cascade, `setSmallMode`, `setContentArea` | **PARTIALLY** — visibility-cascade tests are dropped for recordingControls (resend/pause/trash). ContentArea tests stay. |
| RecordingUiController | unit tests for `applyIdleState/Active/Paused` | **YES** — the methods are deleted. The tests are rewritten as LayoutCatalog predicate tests. |
| MainButtonsController | unit tests for click-handler routing (mock callback) | **YES** — the click routing moves into ImeViewBackend. The tests become backend tests. |
| LayoutCatalog | **NEW** — no existing tests | — |
| ImeViewBackend | **NEW** — no existing tests | — |
| KeyboardLayoutManager | **NEW** — no existing tests | — |

### §14.2 New tests (unit + integration)

**Unit tests (LayoutCatalog):**
- Per LayoutMode (5 of them) × per slot (8 of them): predicate verification against tabulated DictateUiState permutations.
- Example test:
<!-- FIX: Phase-C C-4 (2026-05-14) – Test-Snippet auf hierarchische Sub-State-Pfade + korrekte Konstruktor-
     Signaturen umgestellt (vier Compile-Bugs gefixt):
     (a) `RecordingState.Active(false)` → `RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile())`
         (Spec 1 §3 `data class Active(val useBluetooth, val audioFile)` — zwei Pflicht-Args).
     (b) `PipelineUiState.Preparing` → `PipelineUiState.Preparing(sessionId = "test")`
         (Spec 1 §3 `data class Preparing(val sessionId: String)`).
     (c) `base.copy(lastAudioExists = false)` → `base.copy(resend = base.resend.copy(lastAudioExists = false))`
         (R.3 Sub-State-Container — `lastAudioExists` lebt in `ResendState`, nicht im Top-Level — AI-1-Pattern
         aus Phase-A Architecture-Scout).
     (d) Analog für `resendEnabled` (`state.resend.resendEnabled`).
     Ohne diese Korrektur wäre der Beispiel-Test ein Compile-Error gewesen — Test-Schreiber hätten den Bug
     erst beim ersten `./gradlew test` bemerkt und wäre als Pre-F-11-Test-Vorlage in andere Tests kopiert. -->
```kotlin
@Test fun `predResendVisible is true only in Idle with lastAudio and resendEnabled`() {
    val base = stateBuilder().build()    // base.recording = Idle, base.pipeline = Idle,
                                         // base.resend.lastAudioExists = true, resendEnabled = true
    assertFalse(predResendVisible(base.copy(recording = RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile()))))
    assertFalse(predResendVisible(base.copy(pipeline = PipelineUiState.Preparing(sessionId = "test"))))
    assertFalse(predResendVisible(base.copy(resend = base.resend.copy(lastAudioExists = false))))
    assertFalse(predResendVisible(base.copy(resend = base.resend.copy(resendEnabled = false))))
    assertTrue(predResendVisible(base))
}
```

**Unit tests (KeyboardLayoutManager):**
- `forKeyboard(state)` → the right LayoutMode per state permutation (Idle / Recording / Pipeline / ReprocessStaging × Two-Row/Single-Row).
- `onStateChanged` triggers `backend.render(state, mode)` with the correct arguments (mock backend).

**Integration tests (Espresso):** <!-- FIX: Issue 3.0.9 – Reverse-Pointer pro Test auf Hauptplan §1.1 Bug-Symptom-Spalte; UI-Tests 8/9/10 ergänzt -->

| Test | Description | covers bug symptom |
|---|---|---|
| **UI test 1** | Toggle Single-Row in Idle. Verify all 8 buttons visible, layout matches the Single-Row state. | §1.1 #1 |
| **UI test 2** | Start recording → verify resend GONE, trash/pause VISIBLE. | — (coverage baseline) |
| **UI test 3** | Recording stop → pipeline → verify record_btn text is "Sende…" / "1/3 0:01" (counter), trash/pause GONE. | — (coverage baseline) |
| **UI test 4** | Send-Mode + Single-Row → verify the Send button fully visible (the critical bug-fix verifier). | **§1.1 #3a** |
| **UI test 5** | ReprocessStaging → verify pause_btn VISIBLE+disabled+alpha 0.4. | — (coverage baseline) |
| **UI test 6** | Re-inflate (rotation) during recording → verify the animation keeps running, correct LayoutMode on the first frame. | — (coverage baseline) |
| **UI test 7** | Toggle Single-Row during recording → verify the pulse animation runs through, the Send-btn position switches correctly. | **§1.1 #2** |
| **UI test 8** *(new)* | Frame capture during the Two-Row ↔ Single-Row toggle in Idle+lastAudio: the resend btn stays visibility=VISIBLE in every frame (Espresso `IdlingResource` + per-frame check). | **§1.1 #3b** |
| **UI test 9** *(new)* | Resend cooldown (500ms): after a click, visibility=VISIBLE, enabled=false, alpha=0.4 stay — visibility is NOT cooldown-coupled. | **§1.1 #3b** |
| **UI test 10** *(new)* | Active → Pipeline-Preparing transition: per-frame check — none of the trash/pause buttons may be rendered over record_btn. | **§1.1 #3a + §1.1 #3b** (cross-bug verification) |

**The visibility matrix as an executable test suite:**

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

**Pending tests (test-first pattern):**
- Test for `predResendVisible` in PipelineUiState.Sending.Backgrounded — pending until Spec 1 §7 (background send) is implemented. Marker: `@Ignore("pending: Backgrounded-Sealed-Member kommt mit Block 7")`.

### §14.3 Spike validations

Per spike a **clear pass/fail criterion**, so that the spike result feeds into the final architecture:

| Spike | Pass criterion | Fail → mitigation |
|---|---|---|
| §11.3 PulseLayout in MotionLayout | The pulse runs through all 5 LayoutMode transitions without a visible reset | Option A (PulseLayout in an extra FrameLayout, positioned via a ConstraintHelper) |
| §11.4 Inflation cost | < 50ms `onCreateInputView` on a Pixel 4a | Async inflate / pre-inflate / reduction of the scene states |

---
