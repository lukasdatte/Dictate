# Phase 2 — Section 4 (Structure) — Floating-Overlay (Spec 3)

**Reviewer-Rolle:** Structure-Reviewer (DRY / SOLID / Architektur-Integration)
**Section-Scope:** Spec 3 (`research/3-floating-overlay/3-floating-overlay.md`) §1–§12
**Exklusion:** §13 Vollständigkeits-Verifikation (gehört zu Section 5)
**Cross-Reference-Code:** `/home/lukas/WebStorm/Dictate` (Worktree-Pfad)

---

## Kurzbefund

Spec 3 zeigt **innerhalb ihres eigenen Subsystems** eine sehr saubere Architektur: die Trennung in `OverlayBackend` (Render), `OverlayWindow` (Wrapper für DIP), `OverlayDragHandler` (Touch-Routing), `OverlayPositionMapper` (0..1 ↔ Pixel-Konversion) und `OverlayPermissionGate` ist mustergültig SRP/DIP. Die §4.6/§4.7-Aufspaltung adressiert direkt die Failure-Modes (Test-Mocking, Snap-to-Edge-Erweiterung als Decorator) und ist OCP-kompatibel.

**Das Hauptproblem liegt aber außerhalb dieser sauberen Innen-Architektur.** Spec 3 ist im **Vor-F-11-Vokabular** verfasst und ignoriert vollständig die in Spec 1 §3 + §4 + §15 etablierten Plugin-/Orchestrator-Konventionen:

- **Sub-State-Klasse `OverlayState` wird nirgends verwendet** — Spec 3 liest und schreibt durchgängig `state.overlayPositionPortraitX` (Top-Level), während Spec 1 §3 das Feld als `state.overlay.positionPortraitX` modelliert (Sub-State seit F-11).
- **Action-Hierarchie wird nicht respektiert** — Spec 3 verwendet flache `Action.ToggleViewModeWidget`, `Action.CloseOverlay`, `Action.UpdateOverlayPosition`, `Action.MarkOverlayOnboardingShown`, `Action.DismissOverlayOnboarding`. Spec 1 §15 erwartet `Action.ViewModeAction.*`, `Action.OverlayAction.*` (analog `Action.RecordingAction.*`).
- **Direkte State-Mutation statt Action-Dispatch** — alle "Spec 1"-Ausschnitte in §5.4, §6, §7 zeigen `_state.value = _state.value.copy(...)` und referenzieren benannte Methoden `PipelineStateManager.toggleViewMode()`, `closeOverlay()`, `updateOverlayPosition()`, `markOverlayOnboardingShown()`, `dismissOverlayOnboarding()`. Das bricht F-8 (Single-Dispatch) und F-11 (Module-Reducer) gleich doppelt.
- **OverlayModule ist nicht spezifiziert** — Spec 1 §15.1 listet OverlayModule als 6. Modul-Achse mit Reducer + Cross-Module-Observer. Spec 3 enthält **keinen** Reducer, keine Sub-State-Mutation via Module, kein Mapping `Action.OverlayAction.*` → State-Änderung. Das Modul existiert nur als Tabellen-Eintrag in Spec 1 — Spec 3 müsste den Reducer-Teil liefern.
- **PipelineStateManager-Naming-Drift** ist in Spec 3 noch ausgeprägter als in Spec 2 (die Phase-1 1.1.1 schon adressiert hatte): 7+ Treffer auf den umbenannten `DictateOrchestrator`.

Dazu kommen kleinere strukturelle Schwächen: kein Anchor-Wechsel-Pattern für die Layout-Params-Gravity (wird zur Laufzeit von TOP|END auf TOP|START umgestellt — fragil), `ImeViewBackend` reicht in §5.3 Permission-InfoBar-Klick-Handler aus dem Spec-3-Scope durch ohne klare Spec-2-Vereinbarung, und der OPEN-3-Drag/Mapper-Code dupliziert ViewWidth/Height-Lookup-Logik mit Fallbacks an zwei Stellen.

Schweregrade: **3 × Critical, 6 × Important, 4 × Nice-to-have**.

---

## Issue-Liste

### Issue S-1: OverlayState-Sub-Klasse aus Spec 1 §3 wird in Spec 3 nirgends verwendet

- **Category:** [INTEGRATION]
- **Severity:** Critical
- **Location:** Spec 3 §4.2 (`applyPosition`), §4.3 (Default-Anker-Kommentar), §6 (Schließen-Button), §7 (Mode-Transitionen), §11.5.5 (De-Normalisierung), §11.9 (Persistenz-Bit), §10 (Acceptance), Spec 1 §3 Z. 134-141 (Sub-State-Klasse)
- **Description:**
  Spec 1 §3 definiert nach F-11 eine **Sub-State-Klasse `OverlayState`**:
  ```kotlin
  data class OverlayState(
      val positionPortraitX: Float = 1.0f,
      val positionPortraitY: Float = 0.1f,
      val positionLandscapeX: Float = 1.0f,
      val positionLandscapeY: Float = 0.1f,
      val userPrefersWidget: Boolean = false,
      val onboardingPending: Boolean = false,
  )
  ```
  und bindet sie in `DictateUiState.overlay: OverlayState` ein (Spec 1 §3 Z. 89). In Spec 3 wird diese Sub-State-Klasse durchgehend ignoriert — der Code liest und mutiert flache Top-Level-Felder:
  - §4.2 `applyPosition`: `state.overlayPositionPortraitX` (Z. 366) statt `state.overlay.positionPortraitX`
  - §4.3 Kommentar: `overlayPosition*X = 1.0f, *Y = 0.1f` (Z. 481)
  - §5.3 Onboarding-InfoBar: `state.overlayOnboardingPending` (Z. 820, 821) statt `state.overlay.onboardingPending`
  - §5.4: `_state.value.copy(overlayOnboardingPending = true)` (Z. 846)
  - §7.1: `state.userPrefersWidget` (Z. 1098, 1114, 1134, 1143) statt `state.overlay.userPrefersWidget`
  - §11.5.5: `state.overlayPositionPortraitX/Y` (Z. 1408, 1410)
  - §10 Acceptance: "Default-Werte `1.0f / 0.1f` im DictateUiState" (Z. 1210) — als Top-Level beschrieben
  - GAP-3 in §13 erwähnt es als "neue Felder im DictateUiState" — also genau die Top-Level-Sicht, die Spec 1 nach F-11 nicht mehr hat

  Das ist mehr als Naming-Drift: das ist eine **inkompatible State-Modellierung**. Der Implementierer würde entweder Spec 1 oder Spec 3 brechen.

- **Affected codebase files:** —
- **Suggestion:**
  Spec 3 vollständig auf den Sub-State umstellen. Konkret:
  - §4.2 `applyPosition`: `val o = state.overlay; (o.positionPortraitX, o.positionPortraitY)` etc.
  - §5.3 `bindPermissionInfoBar`: `state.overlay.onboardingPending`
  - §5.4 / §6.2 / §7.x State-Mutations gehen über das Module (siehe S-2). Direkte `copy(overlay = state.overlay.copy(onboardingPending = true))`-Snippets sind nur akzeptabel, wenn die Spec sie explizit als Innen-Reducer-Code des `OverlayModule` markiert.
  - §10 Acceptance + §11.9 Persistenz-Bit-Beschreibung: mit `state.overlay.userPrefersWidget` und `state.overlay.positionPortraitX/Y` re-formulieren.
  - GAP-3 in §13 entfällt vollständig — die Felder sind in Spec 1 §3 bereits modelliert (im Sub-State).

---

### Issue S-2: Direkte `_state.value.copy(...)` statt Action-Dispatch — F-8 + F-11 gebrochen (Plan-weites globales Issue 1.1.2 in Spec 3 voll ausgeprägt)

- **Category:** [SOLID] (DIP, OCP) + [INTEGRATION]
- **Severity:** Critical
- **Location:** Spec 3 §5.4 (Z. 840-871), §6.1 (Z. 913-928), §6.2 (Z. 942-948), §7.1 (Z. 960-979), §7.3 T1 (Z. 1020-1036), T2 (Z. 1058-1075), T3 (Z. 1094-1104)
- **Description:**
  Sieben Code-Snippets in Spec 3 zeigen `_state.value = _state.value.copy(...)` direkt:
  - §5.4 `toggleViewMode`: `_state.value = _state.value.copy(overlayOnboardingPending = true)` und gleich noch eine Variante mit `viewMode`
  - §5.4 `dismissOverlayOnboarding` + `markOverlayOnboardingShown`: weitere `copy()`-Mutationen
  - §6.1 `toggleViewMode`: `_state.value = _state.value.copy(viewMode = ViewMode.KEYBOARD, smallMode = true)` — beachte: `smallMode` ist nach Spec 1 §3 in `state.layout.smallMode`, nicht Top-Level
  - §6.2 `closeOverlay`: `_state.value = _state.value.copy(viewMode = ViewMode.KEYBOARD)` plus impliziter `cancelPipeline()`-Effekt — der Cross-Module-Cascade ist nicht modelliert
  - §7.1 `notifyImeViewVisibilityChanged`: ViewMode-Wechsel via `copy()`
  - §7.3 T1 / T2 / T3: weitere fünf `copy()`-Snippets mit kombinierten Mutationen `viewMode + smallMode + userPrefersWidget`

  Spec 1 §4.3 + §15 verlangt: jede State-Mutation läuft als
  ```kotlin
  pipeline.dispatch(Action.OverlayAction.SetOnboardingPending(true))
  ```
  → Orchestrator routet via `KClass<Action>` ans `OverlayModule` → Reducer pure-function liefert `TransitionResult(nextOverlayState, sideEffects)` → Store wendet atomar an. F-8 nennt das **Single Dispatch**, F-11 nennt das **Modular Reducer**.

  Spec 3 umgeht das komplett. Folgen, falls implementiert wie geschrieben:
  1. **Cross-Module-Cascade fehlt:** §6.2 ruft inline `cancelPipeline()` zusammen mit `viewMode = KEYBOARD`. In Spec 1 §15 läuft Pipeline-Cancel als `Action.PipelineAction.Cancel` — das ViewMode-Modul müsste via `onCrossModuleStateChange` einen `Action.PipelineAction.Cancel` emittieren. Direkter Methoden-Aufruf bricht den Module-Bus.
  2. **Multi-Achsen-Mutation:** T2 mutiert `viewMode + smallMode + userPrefersWidget` in einem `copy()`. Das berührt drei verschiedene Module-Achsen (ViewModeModule, LayoutModule, OverlayModule). Spec 1 §15.5 erlaubt das nur als **Mode 3** (Atomic Cross-Axis), das nach Phase-1-Apply ausdrücklich als "**Phase-2 / nicht eingebaut**" markiert ist (Issue 2.0.3 in `validated-findings-batch1.md`). Die korrekte Lösung ist Action-Cascade: `Action.ViewModeAction.SetWidget(false)` → ViewModeModule → onCrossModuleStateChange returnt `[Action.LayoutAction.SetSmallMode(true), Action.OverlayAction.SetUserPrefers(false)]`.
  3. **Reducer-Eigentum unklar:** wer reduziert `Action.ToggleViewModeWidget` — ViewModeModule oder OverlayModule? Spec 3 entscheidet das nicht. Korrekter Schnitt: `Action.ViewModeAction.OnWidgetToggleClicked` → ViewModeModule reduziert, emittiert ggf. Cascade an OverlayModule (`SetUserPrefers`) und LayoutModule (`SetSmallMode`).
  4. **Permission-Gate als Reducer-Input:** §5.4 ruft `permissions.hasOverlayPermission()` aus einer Reducer-ähnlichen Funktion. Das ist ein Hardware-Read und gehört in den `EffectHandler` oder `ReducerContext` (Spec 1 §15.2 Beispiel verwendet `ctx.recordingAudioFile`, `ctx.audio.useBluetoothMic`). Korrekter Schnitt: Permission-Status spiegelt in `state.overlay` (z.B. `hasPermission: Boolean`), regelmäßig von einem `OverlayPermissionObserver` aktualisiert; der Reducer liest den State.

  Dies ist die Section-spezifische Ausprägung von **Phase-1 globalem Issue 1.1.2** — Spec 3 ist davon am stärksten betroffen, weil sie den User-Toggle-Pfad bis runter zur State-Mutation in einem `copy()` zeigt.

- **Affected codebase files:** —
- **Suggestion:**
  §5.4, §6, §7.1, §7.3 vollständig auf das Action/Module-Pattern umschreiben:
  - **Section §7.3.x umschreiben** als Action-Cascades. Beispiel T2:
    ```kotlin
    // Im OVERLAY_CLOSE-Slot (in WIDGET):
    actionResolver = { Action.ViewModeAction.OnWidgetToggleClicked }

    // ViewModeModule (Spec 1 §15):
    object ViewModeModule : DictateModule<ViewMode, Action.ViewModeAction, ViewModeModule.Effect> {
        override fun reduce(state, action, ctx) = when (action) {
            Action.ViewModeAction.OnWidgetToggleClicked -> {
                val newMode = if (state == ViewMode.WIDGET) ViewMode.KEYBOARD else ViewMode.WIDGET
                TransitionResult(newMode, sideEffects = emptyList())
            }
            // ...
        }
        override fun onCrossModuleStateChange(prev, curr): List<Action> {
            if (prev.viewMode == ViewMode.WIDGET && curr.viewMode == ViewMode.KEYBOARD)
                return listOf(
                    Action.LayoutAction.SetSmallMode(true),
                    Action.OverlayAction.SetUserPrefers(false),
                )
            return emptyList()
        }
    }
    ```
  - **§5.4 Permission-Gate** abstrahieren: Spec 3 spezifiziert `OverlayPermissionGate`, das ist ein Hardware-Subsystem analog `RecordingHardware` aus Spec 1 §7.3. In `ModuleServices` einsteuern und im OverlayModule-EffectHandler über `services.permissions` lesen. Der Onboarding-Flow ist dann `Action.OverlayAction.OnWidgetToggleClicked` → `OverlayModule.reduce` prüft `state.overlay.hasPermission` → entweder `nextState = state.copy(onboardingPending = true)` (Permission fehlt) oder `nextState = state.copy(...)` + Cascade `Action.ViewModeAction.SetWidget(true)`.
  - **§6.2 closeOverlay-Pfad** → Action-Cascade: `Action.OverlayAction.CloseClicked` (HOVER) → OverlayModule.reduce + Cascade `[Action.PipelineAction.Cancel, Action.ViewModeAction.SetMode(KEYBOARD)]`.

  Diese Umstellung ist substantiell — sie erfordert ein eigenes **§4a "Spec 3 ↔ Spec 1 Module-Mapping"** Unter-Kapitel oder einen Querverweis auf §15-OverlayModule + §15-ViewModeModule mit den genauen Action-Definitionen.

---

### Issue S-3: OverlayModule (Spec 1 §15.1, Achse 6) ist in Spec 3 nicht spezifiziert — Modul-Achse "Position-Persistierung + Onboarding-Status" hat keinen Reducer-Code

- **Category:** [INTEGRATION] + [SOLID] (SRP-Lokation)
- **Severity:** Critical
- **Location:** Spec 1 §4.1 (Module-Liste), §15.1 Z. 2247, Spec 3 — kein Treffer auf "OverlayModule"
- **Description:**
  Spec 1 §15.1 listet OverlayModule (Achse 6) mit Verantwortung "Position-Persistierung + Onboarding-Status, Action-Klasse `Action.OverlayAction`, Reducer trivial, kein Cross-Module-Observer". Spec 3 enthält **null Treffer** auf "OverlayModule" (Grep-verifiziert). Das heißt:
  - Es gibt keine `Action.OverlayAction`-Sealed-Class-Definition in Spec 3.
  - Es gibt keinen Reducer für OverlayState.
  - Es gibt keinen EffectHandler (für die SharedPreferences-Schreibung der Position).
  - Spec 1 §6.4 zeigt eine `updateOverlayPosition(portrait, x, y)`-Methode am `PipelineStateManager` — die passt nicht zum F-11-Pattern und sollte in den OverlayModule.reduce + EffectHandler aufgespalten werden.

  Konsequenz für den Implementierer: Block 6 (Spec 3) ist incomplete. Entweder die OverlayModule-Spec wandert nach Spec 3 (näher beim fachlichen Kontext), oder Spec 1 §15 wird um den OverlayModule-Code erweitert. Beides ist möglich; **eines** muss passieren.

  Strukturell vergleichbar mit Spec 2 ↔ LayoutModule (Sec3-Structure S-2) — dort wurde derselbe Mangel für LayoutModule kritisch gemeldet, und Spec 3 zeigt das Problem für OverlayModule nochmal in voller Ausprägung.

- **Affected codebase files:** —
- **Suggestion:**
  Eine neue Sektion **§4.x "OverlayModule (Spec 1 §15-Implementierung)"** in Spec 3 ergänzen. Inhalt:
  1. **`Action.OverlayAction` Sealed-Class** mit den vier Varianten:
     - `data class UpdatePosition(val portrait: Boolean, val normX: Float, val normY: Float) : Action.OverlayAction`
     - `data class SetOnboardingPending(val pending: Boolean) : Action.OverlayAction`
     - `data class SetUserPrefers(val prefers: Boolean) : Action.OverlayAction`
     - `data class SetHasPermission(val granted: Boolean) : Action.OverlayAction` (Pref-/canDrawOverlays-Mirror)
  2. **`object OverlayModule : DictateModule<OverlayState, Action.OverlayAction, OverlayModule.Effect>`** mit Reducer (4 trivial when-Branches) und EffectHandler (`PersistOverlayPosition(portrait, x, y)`-Effect → SharedPreferences-Write).
  3. **Cross-Module-Observer:** keine — Position + Onboarding sind autark. ViewMode-Wechsel via WIDGET ist Sache des **ViewModeModule**, nicht OverlayModule.
  4. **Pref-Mirror:** Konstanten und Spec-1-§6.4-Mapping bleiben gültig, werden aber als `OverlayModule.Effect.PersistOverlayPosition`-Handler implementiert (nicht als Methode am `PipelineStateManager`).

  Wenn die Sektion in Spec 1 §15 statt in Spec 3 landet, bekommt sie dort einen "→ Detail siehe Spec 3 §4.x"-Pointer. Wichtig ist, dass die Spec **eine** Heimat hat.

---

### Issue S-4: Action-Hierarchie nicht namespaced — alle Spec-3-Actions sind flach (`Action.X`) statt `Action.OverlayAction.X` / `Action.ViewModeAction.X`

- **Category:** [INTEGRATION]
- **Severity:** Important
- **Location:** Spec 3 §3.1 Z. 67, 77, 86-87, 95, 100-103; §4.2 Z. 404; §5.3 Z. 825, 830; §6.1 Z. 907; §6.2 Z. 936; §7.3 Z. 1017, 1022, 1051-1053, 1060
- **Description:**
  Spec 1 §15.2 (RecordingModule-Beispiel) etabliert die Konvention `Action.RecordingAction.StartRecording`, `Action.RecordingAction.PauseRecording` etc. Spec 1 §15.3 zeigt `Action.AudioAction.OnAudioFocusGrantChanged`. Spec 1 §5 (LocalBinder) zeigt nach Phase-1-Apply `Action.ViewModeAction.OnImeViewShown/Hidden`.

  Spec 3 verwendet durchgehend **flache** Namen:
  - `Action.StartRecording`, `Action.StopRecordingAndSend`, `Action.PauseRecording`, `Action.ResumeRecording`, `Action.CancelRecording` (sollten `Action.RecordingAction.*` sein)
  - `Action.ToggleViewModeWidget` (sollte `Action.ViewModeAction.*` sein, z.B. `Action.ViewModeAction.OnWidgetToggleClicked`)
  - `Action.CloseOverlay`, `Action.UpdateOverlayPosition`, `Action.MarkOverlayOnboardingShown`, `Action.DismissOverlayOnboarding` (sollten `Action.OverlayAction.*` sein)
  - `Action.NoOp` (Konvention: vermutlich `Action.NoOp` als Top-Level NoOp ist OK; auf Spec 1 §3 prüfen, ob NoOp definiert ist)

  Da `actionClass: KClass<Action.RecordingAction>` (Spec 1 §15.2 Z. 2272) der Lookup-Key für das Modul-Routing ist, würde ein flaches `Action.ToggleViewModeWidget` zur Run-Time bei `moduleByActionClass[Action.ToggleViewModeWidget::class]` keinen Treffer landen → `IllegalStateException("No module for action ...")` aus Spec 1 §4.3.

  Im Phase-1-Apply wurde dieses Issue für andere Specs schon angefasst (Spec 1 §5 LocalBinder-Beispiel), aber Spec 3 ist offensichtlich nicht in den Apply-Sweep einbezogen worden.

- **Affected codebase files:** —
- **Suggestion:**
  Alle `Action.*`-Treffer in Spec 3 mit Phase-1-Apply-Konvention konsistent halten:
  - `Action.StartRecording` → `Action.RecordingAction.StartRecording`
  - `Action.PauseRecording` / `ResumeRecording` → `Action.RecordingAction.PauseRecording` / `ResumeRecording`
  - `Action.CancelRecording` → `Action.RecordingAction.CancelRecording`
  - `Action.StopRecordingAndSend` → vermutlich `Action.RecordingAction.StopAndSend` oder `Action.PipelineAction.StartFromCurrentRecording` — Klärung mit Spec 1
  - `Action.ToggleViewModeWidget` → `Action.ViewModeAction.OnWidgetToggleClicked` (klingt zwar länger, ist aber sprechend; Detail über `WIDGET` vs `KEYBOARD` ergibt sich aus aktuellem State)
  - `Action.CloseOverlay` → `Action.OverlayAction.CloseClicked`
  - `Action.UpdateOverlayPosition(portrait, x, y)` → `Action.OverlayAction.UpdatePosition(portrait, x, y)`
  - `Action.MarkOverlayOnboardingShown` → `Action.OverlayAction.MarkOnboardingShown`
  - `Action.DismissOverlayOnboarding` → `Action.OverlayAction.DismissOnboarding`

  Diese Umbenennung ist mechanisch — sie korrespondiert mit S-3 (Action-Definition) und S-2 (Action-Dispatch statt direct copy). Idealerweise wird sie in einem Wash zusammen mit S-2 gemacht.

---

### Issue S-5: PipelineStateManager-Naming-Drift — Spec 3 hat 7+ Treffer auf den umbenannten Klassen-Namen

- **Category:** [INTEGRATION]
- **Severity:** Critical
- **Location:** Spec 3 §5.3 Z. 755, §5.4 Heading + Z. 837, §6.1 Z. 910, §6.2 Z. 939, §7.3 Z. 1019, §7.3 Z. 1057, §7.3 Z. 1093, §10 Acceptance Z. 1209, §11.5.4 Z. 1398, §11.5.8 Z. 1463, §11.6 Tabelle, §13.x (verschiedene)
- **Description:**
  Spec 1 §4.3 hat den `PipelineStateManager` zu `DictateOrchestrator` umbenannt (F-11). Phase-1 hat das als globales Issue 1.1.1 markiert; Phase-1-Apply hat Spec 1 nachgepflegt; Phase-2-Batch-1 (`validated-findings-batch1.md` Issue 2.0.2) hat **Spec 2** auf den neuen Namen umgestellt. **Spec 3 wurde übersprungen.**

  Treffer in Spec 3:
  - §5.3 Z. 755: "(gesetzt vom PipelineStateManager, sobald `Action.ToggleViewModeWidget` erkannt UND Permission fehlt)"
  - §5.4 Heading: "Pseudo-Code-Flow im `PipelineStateManager` (§4 Spec 1)"
  - §6.1 Z. 910: "PipelineStateManager.toggleViewMode-Logik (in Spec 1 §4)"
  - §6.2 Z. 939: "PipelineStateManager.closeOverlay-Logik:"
  - §7.3 Z. 1019: "// In PipelineStateManager:"
  - §7.3 Z. 1057: "// In PipelineStateManager:"
  - §7.3 Z. 1093: "// In PipelineStateManager (§7.1):"
  - §10 Acceptance Z. 1209: "durch `PipelineStateManager.updateOverlayPosition`"
  - §11.5.4 Z. 1398: "läuft via … durch `KeyboardLayoutManager` zum `PipelineStateManager`"
  - §11.5.8 Z. 1463: "alle Mutations laufen durch `PipelineStateManager`"
  - §11.6 Tabelle Z. 1474: "Beim nächsten `render()`-Call (StateFlow-Emit) merken wir nichts" — referenziert StateFlow am Manager

  Das sind **mehr Treffer als in Spec 2 vor dem Apply** — Spec 3 ist die größte verbleibende Drift-Quelle.

- **Affected codebase files:** —
- **Suggestion:**
  Globale Suche in Spec 3 nach `PipelineStateManager` und Ersetzung. Mapping abhängig vom Kontext:
  - "im `PipelineStateManager`" / "// In PipelineStateManager" → "im jeweiligen Modul" oder "im `DictateOrchestrator` (Spec 1 §4.3)"
  - "PipelineStateManager.toggleViewMode" / "closeOverlay" / "updateOverlayPosition" / etc. → benannte Methoden gibt es nicht mehr; `pipeline.dispatch(Action.ViewModeAction.OnWidgetToggleClicked)` etc. (siehe S-2/S-4)
  - Hinweise wie "alle Mutations laufen durch PipelineStateManager" → "alle Mutations laufen über `dispatch(action)` und Modul-Reducer (Spec 1 §4.3 + §15)"

  Die Umbenennung ist mit S-2/S-4 koppelt (eine kohärente Umstellung von Methoden-Aufrufen auf Action-Dispatch).

---

### Issue S-6: Cross-Module-Cascade in §6.2 (closeOverlay) ist als monolithischer Methoden-Aufruf modelliert — bricht F-11 Cross-Module-Effect-Modus 2

- **Category:** [SOLID] (DIP, OCP) + [INTEGRATION]
- **Severity:** Important
- **Location:** Spec 3 §6.2 (Z. 942-948)
- **Description:**
  Der Code-Skizze:
  ```kotlin
  fun closeOverlay() {
      cancelPipeline()
      _state.value = _state.value.copy(viewMode = ViewMode.KEYBOARD)
  }
  ```
  Tut zwei Dinge:
  1. **Pipeline-Cancel** (Cross-Module-Wirkung — gehört ins PipelineModule)
  2. **ViewMode-Wechsel** (gehört ins ViewModeModule)

  Beide werden in einer einzigen Funktion gemischt. Selbst wenn man die `_state.value.copy()`-Mutation S-2-konform ersetzt, bleibt der direkte `cancelPipeline()`-Aufruf eine **Methoden-Kopplung** zwischen OverlayModule und PipelineModule, die F-11 Cross-Module-Effect-Modus 2 (Action-Cascade via `onCrossModuleStateChange`) widerspricht.

  Korrekte Form:
  - User klickt Schließen in HOVER → `Action.OverlayAction.CloseClicked` dispatcht.
  - OverlayModule.reduce(CloseClicked) → keine eigene State-Änderung (oder `nextState = state.copy(userPrefersWidget = false)`); SideEffects = leer.
  - **OverlayModule.onCrossModuleStateChange(prev, curr)** wird vom Orchestrator nicht für *diese* Action aufgerufen, weil OverlayModule-State sich nicht (relevant) geändert hat. Stattdessen muss CloseClicked als Action-Cascade an Cascade-Empfänger gerouted werden.
  - **Lösung:** OverlayModule reduziert + cascade-emittiert: `cascadeActions = [Action.PipelineAction.Cancel, Action.ViewModeAction.SetMode(KEYBOARD)]`.
  - Orchestrator dispatcht beide rekursiv, jedes Modul reduziert seinen eigenen State.

  Strukturell wichtig: §6.2 ist der **eine** Punkt in Spec 3, an dem Cross-Module-Wirkung explizit benötigt wird. Ihre Behandlung muss dem in Spec 1 etablierten Pattern entsprechen, sonst wird sie zur Vorlage für weitere monolithische Methoden.

- **Affected codebase files:** —
- **Suggestion:**
  §6.2 als Cascade umschreiben:
  ```kotlin
  // Im OverlayModule.reduce, wenn Action == Action.OverlayAction.CloseClicked und state.viewMode == HOVER
  TransitionResult(
      nextState = state.copy(/* nichts; userPrefersWidget bleibt unverändert siehe GAP-5 */),
      sideEffects = emptyList(),
      cascadeActions = listOf(
          Action.PipelineAction.Cancel,
          Action.ViewModeAction.SetMode(ViewMode.KEYBOARD),
      ),
  )
  ```
  Ergänzend in der Spec einen Hinweis auf Spec 1 §15.5 Modus 2 (Action-Cascade).

---

### Issue S-7: Visibility-Logik in Spec 3 §3.1 dupliziert Pattern aus Spec 2 — `state.recording is RecordingState.Idle && state.pipeline is PipelineUiState.Idle` an zwei Stellen, ohne Helper

- **Category:** [DRY]
- **Severity:** Important
- **Location:** Spec 3 §3.1 Z. 60-62 (`OVERLAY_RECORD`-Predicate), Spec 2 §8.5 (Predicate-Helper `predResendVisible`)
- **Description:**
  Der `OVERLAY_RECORD`-ButtonSlot in §3.1 hat:
  ```kotlin
  visibilityPredicate = { state ->
      state.recording is RecordingState.Idle
          && state.pipeline is PipelineUiState.Idle
  }
  ```
  Das ist exakt die Idle-Bedingung, die in Spec 2 `predResendVisible` (§8.5 nach Phase-1-Apply) als Sub-Klausel benutzt:
  ```kotlin
  fun predResendVisible(state) =
      state.lastAudioExists
          && state.resendEnabled
          && state.recording is RecordingState.Idle
          && state.pipeline is PipelineUiState.Idle
  ```
  Ein potenzielles `predIsIdle(state)` würde an beiden Stellen genutzt:
  - Spec 2 §8.5: `predResendVisible` ruft `predIsIdle(state) && state.lastAudioExists && state.resendEnabled`
  - Spec 3 §3.1 OVERLAY_RECORD: ruft `::predIsIdle`

  Drift-Risiko: wird `RecordingState.Idle` später um `RecordingState.Stopping` erweitert, müssen beide Stellen nachgezogen werden.

  Zusätzlich: §3.1 verwendet flache `state.recording` (gegen die Sub-State-Struktur — siehe S-1 für Onboarding/Position; `state.recording` ist allerdings **Top-Level** korrekt nach Spec 1 §3 Z. 82, also kein Drift hier — nur Kon­fusion mit S-1).

- **Affected codebase files:** —
- **Suggestion:**
  Im gemeinsamen Predicate-Helper-File (Spec 2 §8.5 etabliert, Datei `keyboard/render/Predicates.kt`) einen `predIsIdle(state: DictateUiState): Boolean = state.recording is RecordingState.Idle && state.pipeline is PipelineUiState.Idle` ergänzen. Spec 3 §3.1 Verwendung:
  ```kotlin
  ButtonSlot(LogicalButtonId.OVERLAY_RECORD, WrapContent,
      visibilityPredicate = ::predIsIdle,
      // ...)
  ```
  Spec 2 §8.5 `predResendVisible` ruft den Helper. Vorteil: ein zentraler "Idle"-Begriff für alle Specs, OCP für eine künftige `RecordingState.Stopping`-Erweiterung.

---

### Issue S-8: ViewMode-FSM-Logik in §7.1 ist parallel zu Spec 1 §15 ViewModeModule modelliert — Doppel-Eigentum-Risiko

- **Category:** [INTEGRATION] + [SOLID] (SRP)
- **Severity:** Important
- **Location:** Spec 3 §7.1 (`computeViewMode`), §7.3 (sechs Transition-Pfade T1-T6), Spec 1 §15.1 Z. 2245 (ViewModeModule)
- **Description:**
  §7.1 zeigt in Spec 3 die **Triangle-FSM**:
  ```kotlin
  private fun computeViewMode(imeViewVisible, userToggledWidget, pipelineActive): ViewMode {
      return when {
          imeViewVisible && userToggledWidget -> ViewMode.WIDGET
          imeViewVisible && !userToggledWidget -> ViewMode.KEYBOARD
          !imeViewVisible && pipelineActive -> ViewMode.HOVER
          else -> ViewMode.KEYBOARD
      }
  }
  ```
  Spec 1 §15.1 sagt, dass die ViewMode-FSM-Logik im **ViewModeModule** lebt ("ehemals ViewModeFsm"). Spec 1 hat dafür einen eigenen Reducer (§4.1 Z. 293, §15.4 in Phase-2-Iter-1 erweitert).

  Folgen, wenn Spec 3 §7.1 als kanonisch betrachtet wird:
  1. **Doppel-Eigentum:** ViewModeModule (Spec 1) und Spec 3 §7.1 implementieren denselben `computeViewMode`. Wer ist Quelle der Wahrheit?
  2. **Top-Level-Felder vs Sub-State (siehe S-1):** Spec 3 liest `state.userPrefersWidget` flach, Spec 1 §3 nach F-11 hat `state.overlay.userPrefersWidget`. Wenn Spec 1 ViewModeModule kanonisch ist, muss es ihn aus Sub-State lesen.
  3. **`notifyImeViewVisibilityChanged(visible: Boolean)` als Methode** ist nach Phase-1-Apply (Issue 2.0.4) **entfernt** — der IME ruft `pipeline?.dispatch(Action.ViewModeAction.OnImeViewShown / OnImeViewHidden)` direkt. Spec 3 §7.3 T3-T6 zeigt aber weiterhin `pipeline?.notifyImeViewHidden()` und `stateManager.notifyImeViewVisibilityChanged(false)` — das Vor-Apply-Vokabular.

  Strukturell fehlt eine klare Aussage in Spec 3, dass die ViewMode-FSM **nur dokumentarisch** in Spec 3 §7.1 erscheint, der Code in **Spec 1 §15-ViewModeModule** lebt, und die Transition-Pfade T1-T6 Action-Sequenzen am Module-Bus dokumentieren.

- **Affected codebase files:** —
- **Suggestion:**
  In §7.1 einen Header-Block ergänzen:
  > **§7.1 Hinweis:** Die ViewMode-FSM ist in **Spec 1 §15-ViewModeModule** kanonisch implementiert. Dieser Abschnitt zeigt die Transition-Logik *aus Sicht von Spec 3* (Overlay-Subsystem), als Referenz für den Implementierer. Code-Skizzen in §7.3 sind keine separat zu implementierenden Methoden, sondern dokumentieren die **Action-Sequenzen** am Module-Bus (`Action.ViewModeAction.OnImeViewShown/Hidden/OnWidgetToggleClicked`).
  
  In §7.3 die Methoden-Calls (`notifyImeViewVisibilityChanged`, `notifyImeViewHidden`) auf `pipeline?.dispatch(Action.ViewModeAction.OnImeViewHidden)` etc. umstellen (zusammen mit S-5).

---

### Issue S-9: Anchor-Wechsel der Window-Position (Gravity TOP|END → TOP|START zur Laufzeit) ist fragil und nicht als bewusste Entscheidung dokumentiert

- **Category:** [INTEGRATION]
- **Severity:** Important
- **Location:** Spec 3 §4.2 `applyPosition` Z. 371-375, §4.3 `DefaultOverlayLayoutParamsFactory.create` Z. 486-488 + Kommentar Z. 482-485
- **Description:**
  §4.3 setzt die initialen LayoutParams mit `gravity = Gravity.TOP or Gravity.END` (rechte obere Ecke + 16dp/80dp Offset).
  §4.2 `applyPosition` setzt **bei jedem Render** auf `gravity = Gravity.TOP or Gravity.START` und rechnet `params.x = px, params.y = py` von TOP|START.

  Dieser Anchor-Wechsel passiert in der Vergleichsklausel:
  ```kotlin
  if (params.x != px || params.y != py || params.gravity != (Gravity.TOP or Gravity.START)) {
      params.gravity = Gravity.TOP or Gravity.START
      // ...
  }
  ```
  Der Kommentar in §4.3 Z. 482-485 sagt "ACHTUNG: nach erstem `render()`-Call übernimmt OverlayBackend.applyPosition die Steuerung und stellt gravity auf TOP|START um." Das ist ein bewusster Trick, aber strukturell heikel:
  1. **Initial-State-Frame:** zwischen `inflateAndAttach` und dem ersten `applyPosition` ist das Window für einen Frame im END-Anker mit (16dp, 80dp), beim nächsten Frame springt es nach (px, py) im START-Anker. Das ist die "Top-End-Default-Frame", die §4.2 Z. 408-414 mit `view.post { applyPosition(it) }` zu mitigieren versucht.
  2. **Single-Owner-Verletzung:** Anchor wird zweimal entschieden — einmal in der Factory (TOP|END), einmal im Backend (TOP|START). Das ist DRY-light und SRP-light: wer das Verhalten ändern will (z.B. Default-Position bottom-end), muss beide Stellen anfassen.

  Saubere Lösung wäre, die Factory direkt mit TOP|START zu erstellen und die Default-Position als Pixel-Werte aus dem normalisierten Default-State (`state.overlay.positionPortraitX = 1.0f, *Y = 0.1f`) zu berechnen. Dann gibt es nur **einen** Anchor und die Init-Logik ist konsistent mit der Render-Logik.

- **Affected codebase files:** —
- **Suggestion:**
  `DefaultOverlayLayoutParamsFactory.create()` so umbauen, dass:
  - Anchor von Anfang an `TOP|START` ist
  - Initial-`x/y` aus Default-Position-State berechnet wird (`positionMapper.normalizedToPixels(1.0f, 0.1f, /* view-stub */)`) — alternativ auf Pixel-Defaults (z.B. `screenWidth - viewWidth - 16dp`, `80dp`) berechnet, die exakt dem normalisierten Default entsprechen.
  - View-Width-Schätzung: weil zur Factory-Zeit die View noch nicht inflated ist, kann die Factory eine grobe Mindest-Schätzung nehmen (z.B. 270dp) und das `view.post { applyPosition }` korrigiert dann die Pixel präzise.

  In §4.3 den ACHTUNG-Kommentar entfernen; in §4.2 `applyPosition` den Anchor-Vergleich entfernen.

---

### Issue S-10: §4.7 OverlayPositionMapper — ViewWidth/Height-Lookup-Logik dupliziert mit Fallback an zwei Stellen

- **Category:** [DRY]
- **Severity:** Nice-to-have
- **Location:** Spec 3 §4.7 Z. 660-661 + Z. 671-672
- **Description:**
  In `normalizedToPixels` und `pixelsToNormalized` steht jeweils:
  ```kotlin
  val viewW = view.width.takeIf { it > 0 } ?: view.measuredWidth
  val viewH = view.height.takeIf { it > 0 } ?: view.measuredHeight
  ```
  Identische 2-Liner an beiden Methoden. Bei Drift (z.B. später `view.layoutParams.width` als 3. Fallback) wird einer der beiden vergessen.

- **Affected codebase files:** —
- **Suggestion:**
  Privatmethode oder Top-Level-Helper:
  ```kotlin
  private fun View.effectiveSize(): Pair<Int, Int> {
      val w = width.takeIf { it > 0 } ?: measuredWidth
      val h = height.takeIf { it > 0 } ?: measuredHeight
      return w to h
  }
  ```
  Beide Methoden rufen `view.effectiveSize()`. Trivial-Refactor.

---

### Issue S-11: Permission-InfoBar-Klick-Handler in §5.3 lebt im `ImeViewBackend` (Spec 2-Scope) — Cross-Spec-Kopplung nicht klargestellt

- **Category:** [INTEGRATION] + [SOLID] (SRP-Lokation)
- **Severity:** Nice-to-have
- **Location:** Spec 3 §5.3 (Z. 815-833 — `bindPermissionInfoBar` als Methode am `ImeViewBackend`)
- **Description:**
  §5.3 zeigt `private fun bindPermissionInfoBar(state: DictateUiState)` als Methode "im ImeViewBackend" — `ImeViewBackend` lebt aber in Spec 2 §6 und ist dort als der KEYBOARD-Render-Backend dokumentiert. Spec 3 fügt durch §5.3 eine **neue Methode** zum Spec-2-Backend hinzu, ohne dass Spec 2 das vorsieht.

  Saubere Optionen:
  1. **InfoBar als ButtonSlots integrieren:** den InfoBar als zwei Slots (`OVERLAY_PERM_GRANT`, `OVERLAY_PERM_DISMISS`) modellieren, die nur in einem speziellen LayoutMode sichtbar sind. Vorteil: konsistent mit dem deklarativen Catalog-Pattern. Nachteil: InfoBar ist eher eine eigene UI-Region, kein Tastatur-Button.
  2. **InfoBar als zweites RenderBackend** (analog `MainContainerBackend`, das Sec3-Structure S-2 für ContentArea vorschlägt): `OnboardingInfoBarBackend` — sauber separiert, wäre aber overkill für eine InfoBar.
  3. **§5.3 explizit als "Spec 2-Erweiterung"** kennzeichnen: einen Querverweis "→ Spec 2 §6 muss um `bindPermissionInfoBar(state)` erweitert werden" einfügen. Pragmatisch, kostengünstig.

- **Affected codebase files:** —
- **Suggestion:**
  Pragmatik-Pfad: §5.3-Header ergänzen:
  > **Cross-Spec-Hinweis:** `bindPermissionInfoBar` ist eine Erweiterung des `ImeViewBackend` (Spec 2 §6). Spec 2 sollte um diese Methode + den XML-Include `<include layout="@layout/overlay_permission_infobar" />` ergänzt werden. Begründung für die Lokation in Spec 2 statt Spec 3: die InfoBar lebt als View **im** IME-View, nicht im Floating-Overlay-Window — sie ist topologisch Teil von Spec 2.

  Entsprechende Notiz im Spec 2-§6 oder §9.3 als Reciprocal-Anchor.

---

### Issue S-12: Window-Lifecycle-Events (Permission-Revoke zur Laufzeit) — Spec 3 §11.6 dokumentiert das, hängt aber den Recovery-Pfad an `render()`-Call

- **Category:** [INTEGRATION]
- **Severity:** Nice-to-have
- **Location:** Spec 3 §4.2 `inflateAndAttach` Z. 415-419, §11.6 Tabelle Z. 1474
- **Description:**
  §11.6 Z. 1474 sagt: "Permission wird in System-Settings revoked, während Overlay sichtbar — Android sendet KEIN Broadcast. Beim nächsten render()-Call merken wir nichts — `addView` ist vor langer Zeit gelaufen, `Settings.canDrawOverlays()` würde `false` zurückgeben, aber wir prüfen das nur am Anfang von render(). Edge-Case ist akzeptabel, weil sehr selten."

  Strukturell ist das eine sehr passive Erkennung. Sauberer wäre:
  - Periodisches Re-Check (alle X Sekunden — overkill für ein 5-Button-Widget)
  - **OR:** ein `OverlayPermissionObserver` (Hardware-Subsystem analog `RecordingHardware`), der `Settings.canDrawOverlays()` bei jedem `onConfigurationChanged` und `onResume`-ähnlichen Event re-checkt und über `Action.OverlayAction.SetHasPermission(...)` den State aktualisiert.

  Letzteres ist auch konsistent mit dem F-11-Pattern (Hardware-Status-Spiegel im State, siehe Spec 1 §3 `audio.audioFocusGranted` als asynchroner System-Status getrennt vom User-Pref).

- **Affected codebase files:** —
- **Suggestion:**
  In §5 oder §11.6 ein `OverlayPermissionObserver` modellieren:
  ```kotlin
  class OverlayPermissionObserver(
      private val gate: OverlayPermissionGate,
      private val dispatch: (Action) -> Unit,
  ) {
      fun onConfigurationChanged() = recheck()
      fun onWindowFocusChanged(hasFocus: Boolean) { if (hasFocus) recheck() }
      private fun recheck() {
          dispatch(Action.OverlayAction.SetHasPermission(gate.hasOverlayPermission()))
      }
  }
  ```
  Im IME-Service `onConfigurationChanged` + `onWindowFocusChanged` triggern. State.overlay.hasPermission ist dann immer aktuell, OverlayBackend.render() liest den State (statt direkt `permissions.hasOverlayPermission()`).

---

### Issue S-13: `OverlayBackend` hat `RenderBackend`-Interface, aber Spec 3 zeigt es nirgends — Bezug zu Spec 2 §4 `RenderBackend` nicht explizit

- **Category:** [INTEGRATION]
- **Severity:** Nice-to-have
- **Location:** Spec 3 §4.2 Z. 303 (`: RenderBackend`), Spec 2 §4 (RenderBackend-Interface)
- **Description:**
  §4.2 zeigt `class OverlayBackend(...) : RenderBackend` — das `RenderBackend`-Interface ist aber in Spec 2 §4 (KeyboardLayoutManager-API) definiert, nicht in Spec 3. Spec 3 hat keinen Verweis auf Spec 2 §4 als Vertrag-Quelle.

  Ein neuer Leser von Spec 3 sieht `: RenderBackend` und weiß nicht, welche Methoden er implementieren muss; die spec-Snippets geben `attach`, `detach`, `render` — aber das ist nicht als Interface-Definition markiert.

- **Affected codebase files:** —
- **Suggestion:**
  In §4.1 oder §4.2 ein 1-Satz-Header "OverlayBackend implementiert das `RenderBackend`-Interface aus Spec 2 §4 (Z. X-Y) — Methoden `attach(onAction)`, `detach()`, `render(state, mode)`." einfügen. Spec 2 sollte reziprok in §4 erwähnen, dass Spec 3 ein zweites Implementierer-Backend liefert.

---

## Zusammenfassende Tabelle

| #    | Category      | Severity      | Issue (Kurz)                                                          | Beschreibung                                                                                                  |
|------|---------------|---------------|-----------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| S-1  | [INTEGRATION] | Critical      | OverlayState-Sub-Klasse aus Spec 1 §3 wird in Spec 3 nicht verwendet  | Spec 3 liest/schreibt flache Top-Level-Felder; Spec 1 §3 hat seit F-11 `state.overlay`-Sub-State              |
| S-2  | [SOLID]       | Critical      | Direkte `_state.value.copy(...)` statt Action-Dispatch (F-8/F-11 Verstoß) | 7 Code-Snippets in §5.4/§6/§7 mutieren State direkt; Module-Reducer fehlt; globales Issue 1.1.2 voll ausgeprägt |
| S-3  | [INTEGRATION] | Critical      | OverlayModule (Spec 1 §15 Achse 6) ist in Spec 3 nicht spezifiziert    | Action.OverlayAction-Definition, Reducer, EffectHandler fehlen vollständig; Modul existiert nur als Tabelleneintrag |
| S-4  | [INTEGRATION] | Important     | Action-Hierarchie nicht namespaced (`Action.X` statt `Action.OverlayAction.X`) | KClass-basiertes Modul-Routing aus Spec 1 §4.3 würde zur Run-Time scheitern                                  |
| S-5  | [INTEGRATION] | Critical      | `PipelineStateManager`-Naming-Drift mit 7+ Treffern                   | Spec 3 ist die größte verbleibende Drift-Quelle nach Phase-1-Apply (Issue 1.1.1) und Phase-2-Batch-1 (2.0.2)  |
| S-6  | [SOLID]       | Important     | Cross-Module-Cascade in §6.2 als monolithischer Methoden-Aufruf      | `closeOverlay()` mischt Pipeline-Cancel + ViewMode-Wechsel; F-11 Cross-Module-Mode-2 (Action-Cascade) fehlt  |
| S-7  | [DRY]         | Important     | Visibility-Predicate Idle-Check duplizt zwischen Spec 2 + Spec 3      | `state.recording is Idle && state.pipeline is Idle` an zwei Stellen ohne `predIsIdle`-Helper                  |
| S-8  | [INTEGRATION] | Important     | ViewMode-FSM in §7.1 parallel zu Spec 1 §15-ViewModeModule modelliert | Doppel-Eigentum-Risiko; `notifyImeViewVisibilityChanged`-Methoden-Vokabular nach Phase-1-Apply veraltet      |
| S-9  | [INTEGRATION] | Important     | Anchor-Wechsel TOP|END → TOP|START zur Laufzeit fragil + DRY-light    | LayoutParamsFactory + Backend.applyPosition entscheiden Anchor doppelt; Initial-Default-Frame                |
| S-10 | [DRY]         | Nice-to-have  | `OverlayPositionMapper` ViewWidth/Height-Fallback dupliziert          | 2-Liner an beiden Methoden — Privat-Helper extrahieren                                                       |
| S-11 | [INTEGRATION] | Nice-to-have  | Permission-InfoBar in `ImeViewBackend` ohne Spec 2-Reciprocal         | §5.3 `bindPermissionInfoBar` ist Spec-2-Erweiterung; nicht als solche markiert                                |
| S-12 | [INTEGRATION] | Nice-to-have  | Permission-Revoke-zur-Laufzeit als Edge-Case akzeptiert               | `OverlayPermissionObserver` (analog `audioFocusGranted`-Spiegel) wäre F-11-konform und sauberer              |
| S-13 | [INTEGRATION] | Nice-to-have  | `RenderBackend`-Interface-Bezug zu Spec 2 §4 nicht explizit          | Spec 3 §4.2 zeigt `: RenderBackend` ohne Querverweis auf das Interface in Spec 2                              |

---

## Quick-Recap für den Sanity-Check-Consolidator

**Critical (4):**
- **S-1:** OverlayState-Sub-Klasse (Spec 1 §3) wird ignoriert — flache Top-Level-Felder statt `state.overlay.*`
- **S-2:** Direkte `_state.value.copy()` an 7 Stellen — F-8 (Single Dispatch) und F-11 (Module-Reducer) gleich doppelt gebrochen — die Section-spezifische Ausprägung des Plan-weiten Issues 1.1.2
- **S-3:** OverlayModule (Spec 1 §15 Achse 6) hat keinen Spec-3-Code — Action-Definition, Reducer, EffectHandler fehlen vollständig
- **S-5:** `PipelineStateManager`-Naming-Drift mit 7+ Treffern (Spec 3 ist nach Phase-1-Apply die größte verbleibende Drift-Quelle)

**Important (5):**
- **S-4:** Action-Hierarchie nicht namespaced — Module-Routing würde fehlen (`Action.X` statt `Action.OverlayAction.X`)
- **S-6:** Cross-Module-Cascade in `closeOverlay` als monolithischer Methoden-Aufruf — F-11 Mode-2 fehlt
- **S-7:** Visibility-Predicate Idle-Check zwischen Spec 2 + Spec 3 duplziert — `predIsIdle`-Helper extrahieren
- **S-8:** ViewMode-FSM in §7.1 parallel zu Spec 1 §15 modelliert + veraltetes `notifyImeViewVisibilityChanged`-Vokabular
- **S-9:** Anchor-Wechsel TOP|END → TOP|START zur Laufzeit fragil — Factory + Backend entscheiden Anchor doppelt

**Nice-to-have (4):**
- **S-10:** `OverlayPositionMapper` ViewWidth/Height-Fallback an zwei Stellen
- **S-11:** Permission-InfoBar als ungekennzeichnete Spec-2-Erweiterung
- **S-12:** Permission-Revoke-zur-Laufzeit ohne `OverlayPermissionObserver`-Spiegel
- **S-13:** `RenderBackend`-Interface-Bezug zu Spec 2 §4 nicht explizit

**Architektur-Integration zu Sec3-Structure S-2 (Spec 2):**
Die in Sec3-Structure S-2 für Spec 2 als Critical gemeldete Lücke ("LayoutModule-Bezug fehlt") existiert in **viel ausgeprägterer Form** in Spec 3:
- Spec 2 referenziert flache `state.singleRowMode` (S-2 dort), Spec 3 referenziert flache `state.overlayPositionPortraitX` (S-1 hier)
- Spec 2 hat einen unklaren `ContentAreaController` ohne Module-Bezug, Spec 3 hat keinerlei `OverlayModule`-Code
- Spec 2 nutzt `Action.LayoutAction` (in §3.3 zumindest definiert), Spec 3 nutzt durchgehend flache `Action.X`

**Empfehlung:** S-1, S-2, S-3, S-4, S-5, S-6, S-8 sind als zusammenhängender Wash umzusetzen — sie betreffen alle die Spec-1-Plugin-Pattern-Integration und sollten in einer kohärenten Plan-Edit-Pass adressiert werden, sonst entstehen Halbzustände mit teilweise namespaced Actions, teilweise flachen Mutationen.
