# Phase 2 — Section 3 (Structure) — Keyboard-Layout-Renderer

**Reviewer-Rolle:** Structure-Reviewer (DRY / SOLID / Architektur-Integration)
**Section-Scope:** Spec 2 (`research/2-keyboard-layout/2-keyboard-layout.md`) §1–§12
**Exklusion:** §13 Vollständigkeits-Verifikation (gehört zu Section 5)
**Cross-Reference-Code:** `/home/lukas/WebStorm/Dictate` (Worktree-Pfad)

---

## Kurzbefund

Spec 2 ist strukturell überwiegend solide aufgebaut: das Catalog/Manager/Backend-Tripel folgt klar dem heute gewählten Architektur-Pattern (deklarative LayoutModes + zentraler State-Owner + dünner Render-Pfad). Der gemeinsame Slot-Apply-Helper (§5.1, F-7) und die hierarchische `Action`-Sealed-Class (§3.3, F-8 + F-11) zeigen, dass DRY/SRP-Korrekturen aus früheren Iterationen eingearbeitet sind. Trotzdem bleiben strukturelle Lücken — vor allem im Visibility-Eigentümer-Design (D2), in der Beziehung Spec 2 ↔ Spec 1 LayoutModule und in der OCP-Erweiterbarkeit für künftige Layout-Modi (Three-Row, Compact). Die Bewertung von D1 (MotionLayout vs flach) erfolgt nicht in der Spec selbst — sie liegt in Recherche-Files, der Spec fehlt eine Knappe Begründungs-Spur.

Schweregrade: 2 × Critical, 6 × Important, 4 × Nice-to-have.

---

## Issue-Liste

### Issue S-1: Visibility-Ort (D2) ist im Spec-Text inkonsistent / nicht eindeutig fixiert

- **Category:** [SOLID] (SRP)
- **Severity:** Critical
- **Location:** Spec 2 §2 (L3), §5.1, §6 `render()`, §7.3 (Visibility-Mode-Tabelle), §8 (Predicates), §9.3
- **Description:**
  Der Plan verspricht "MotionScene managt Position, LayoutManager managt Visibility — kollisionsfrei (L3)". In der Umsetzung mischt sich diese Trennung jedoch:
  - `applySlotToView` (§5.1) setzt `view.visibility = if (visible) VISIBLE else GONE` — **das** ist der Visibility-Eigentümer.
  - Gleichzeitig sagt §7.3, dass `visibilityMode="ignore"` *nur* auf Buttons gesetzt wird, deren Predicate nicht-konstant ist. Buttons mit konstantem Predicate (z.B. `record_pulse_layout`, `backspace_btn`, `space_btn`, `enter_btn`) werden im Layout-XML auf `visible` gesetzt und bekommen `visibilityMode="ignore"` **nicht** — ihre Visibility kommt damit aus der MotionScene.
  - In Wirklichkeit setzt `applySlotToView` die Visibility aber für **alle** Slots, weil die Schleife in §6 (`mode.rows.flatMap { it.slots }.forEach { ... applySlotToView(...) }`) ohne Ausnahme drüberläuft.

  Das heißt: für die "konstanten" Buttons schreiben **MotionScene und Backend gleichzeitig** Visibility — exakt der Konflikt, den L3 verhindern wollte. Die heutige Visibility-Snap-Bug-Klasse (Recherche `_pending-state-machine-visibility-owners.md`) wandert damit nur auf eine andere Achse.

  Strukturell fehlt: ein **expliziter Filter** im Spec-Text, der entweder (a) `visibilityMode="ignore"` für *alle* gerenderten Slots verlangt (klare Single-Owner: `applySlotToView`), oder (b) den Render-Loop so einschränkt, dass Visibility nur für Slots mit nicht-konstantem Predicate geschrieben wird.

- **Affected codebase files:** —
- **Suggestion:**
  Eine der beiden Varianten konsequent festschreiben und in §2 (L3) + §5.1 + §7.3 als SSOT-Regel formulieren:
  - **Variante A (empfohlen):** `visibilityMode="ignore"` auf *alle 9 Buttons* setzen (auch die "konstanten"). `applySlotToView` ist der einzige Visibility-Owner. Vorteil: keine Sonderfälle, Tabelle in §7.3 wird trivial ("alle: JA").
  - **Variante B:** Render-Loop filtert: `slots.filter { it.visibilityPredicateIsDynamic() }.forEach { applySlotToView(...) }`. Predicates haben dafür ein flag. Vorteil: bewahrt MotionScene-Default für statische Buttons.
  Variante A ist klar SRP-stärker — der Catalog ist die einzige Visibility-Quelle, MotionScene macht ausschließlich Position.

---

### Issue S-2: KeyboardLayoutManager / ImeViewBackend — fehlender Bezug zum Spec-1-LayoutModule

- **Category:** [SOLID] (SRP)
- **Severity:** Critical
- **Location:** Spec 2 §4 (Manager-API), §6 (Backend); Spec 1 §4 + §15, `LayoutModule` mit `Action.LayoutAction.ToggleSingleRowMode/ToggleSmallMode/SetContentArea`
- **Description:**
  Spec 1 etabliert ein **`LayoutModule`** als dedizierte Modul-Achse im Modular-Orchestrator-Pattern (Spec 1 §4.8 Registry, §15). Der Modul-Sub-State enthält `singleRowMode`, `smallMode`, `animationsEnabled`, `contentArea`. Spec 2 §4 / §6 referenziert davon **nichts** — der Manager liest `state.singleRowMode` direkt vom Top-Level `DictateUiState`, der ImeViewBackend mutiert teilweise außerhalb des Module-Bus (z.B. `ContentArea` in §9.3 als orthogonalen `ContentAreaController` deklariert, ohne Bezug zum LayoutModule).

  Frage 1 (SRP): wer ist der **Eigentümer** des Layout-State?
  - Wenn `LayoutModule` der SSOT ist (so steht's in Spec 1), darf Spec 2 den State nur via Module-Achse lesen — und `KeyboardLayoutManager` muss Layout-Actions an `LayoutModule` (nicht an ein generisches `onAction`) routen.
  - Der `KeyboardLayoutModeController`, den Spec 2 in §9.1 löscht, ist im neuen Modell durch `LayoutModule` (State + Reducer) **plus** `KeyboardLayoutManager` (Render-Trigger) ersetzt — die Spec macht das aber nirgends explizit. Das Konzept bleibt im Niemandsland.

  Frage 2 (D2-Konsistenz): "ContentArea-Toggle" mutiert in §9.3 weiterhin direkt `views.mainButtonsClTyped.visibility` über einen separaten `ContentAreaController`. Das ist eine **dritte** Visibility-Achse außerhalb von Catalog UND MotionScene → SRP-Bruch (zwei Visibility-Eigentümer).

- **Affected codebase files:** —
- **Suggestion:**
  Zwei Klarstellungen in §4 / §9.3 / §1 ergänzen:
  1. **Beziehung Manager ↔ LayoutModule explizit machen:** ein eigener Unterabschnitt "§4.x Beziehung zu LayoutModule (Spec 1)" mit dem Mapping:
     - `LayoutModule.LayoutState` → liefert `singleRowMode` / `contentArea` an `KeyboardLayoutManager.computeLayoutMode`.
     - `Action.LayoutAction.*` werden vom Manager **nicht selbst** emittiert — die kommen von UI-Toggles (Edit-Bar-Button etc.). Der Manager ist read-only Subscriber des Layout-States.
   2. **ContentArea-Renderer integrieren:** entweder `ContentAreaController` als zweites RenderBackend modellieren (`MainContainerBackend`), oder im `ImeViewBackend.render` das Container-Toggle über einen eigenen Slot/Helper abdecken. Die bisherige Lösung "bleibt — wird zu einem orthogonalen ContentAreaController" ist nicht ausreichend dokumentiert (Spec 2 §9.3, eine Zeile).

---

### Issue S-3: Layout-Container-Decision (D1, MotionLayout vs flat ConstraintLayout) ist im Spec-Text nicht begründet

- **Category:** [INTEGRATION]
- **Severity:** Important
- **Location:** Spec 2 §2 L1 + L2 (Tabellen-Zeile)
- **Description:**
  D1 (MotionLayout-Wahl) und D2 (flache Hierarchie) erscheinen in §2 als 1-Satz-Begründung ("Empfohlen durch Phase-2-Recherche"). Die *eigentliche* Begründung — die Bewertung der vier Optionen + Pulse-/Re-Parent-Risiken + Inflation-Cost — lebt in `motionlayout-architecture-options.md` und `_pending-layout-container-architecture.md`. Wer Spec 2 standalone liest, hat keine Chance, die Wahl nachzuvollziehen oder zu hinterfragen.

  Strukturell relevant, weil D1 die ganze §6/§7-Architektur (Scene-XML + ImeViewBackend) trägt. Wenn D1 wackelt, fällt die halbe Spec.

- **Affected codebase files:** —
- **Suggestion:**
  In §2 nach der Tabelle einen kurzen Absatz "D1 / D2 — Begründungs-Zusammenfassung" einfügen mit den 3-4 entscheidenden Punkten aus den Recherche-Files (Re-Parenting-Bug-Klasse, deklarative Animation, Pulse-Spike-Risiko, Inflation-Cost) + expliziten Pointer (`→ siehe motionlayout-architecture-options.md §X`). Spec ist dann standalone lesbar; Detail bleibt im Recherche-File.

---

### Issue S-4: OCP — neue Layout-Modi (Three-Row / Compact) erfordern Edits an mehreren Stellen ohne klare Anleitung

- **Category:** [SOLID] (OCP)
- **Severity:** Important
- **Location:** Spec 2 §3.2 (`LayoutModeId`-Enum), §6 (`toSceneStateId()`), §7.1 (Scene-XML), §8 (Catalog-Definitionen), §8.6 (`forKeyboard()`-Branches)
- **Description:**
  Ein neuer Layout-Modus (z.B. "KEYBOARD_THREE_ROW" oder "KEYBOARD_COMPACT") erfordert heute koordinierte Änderungen an:
  1. `LayoutModeId`-Enum (§3.2)
  2. `toSceneStateId()`-when (§6, am Ende)
  3. neuer `<ConstraintSet>`-Block + Transitions in `motion_scene_keyboard.xml` (§7.1)
  4. neue `KEYBOARD_<X>` `LayoutMode`-Konstante (§8)
  5. neuer Branch in `LayoutCatalog.forKeyboard()` (§8.6)

  Das ist eigentlich gesund (jeder Schritt ist klein), aber:
  - Der `toSceneStateId()`-when ist **exhaustive** und bricht den Compiler bei jeder neuen Variante. Gut. Aber: die Funktion lebt als top-level extension (`LayoutModeId.toSceneStateId()`) und ist nicht Teil des `LayoutMode`-Datentyps. Wenn jemand sie übersieht, bricht es zur Laufzeit (Cast / IllegalState statt Compile-Error für Overlay-Branch — siehe `error("Overlay mode is not handled by ImeViewBackend")`).
  - Es fehlt eine **Checklist/Pattern-Beschreibung** "Wie füge ich einen neuen Layout-Modus hinzu?" — ein Hook, den jeder zukünftige Entwickler braucht. Das ist klassische Doku-Lücke, kein OCP-Bruch im engeren Sinne.

  Echter OCP-Bruch: `LayoutModeId.toSceneStateId()` ist *nicht* erweiterungsoffen — eine neue `LayoutModeId`-Variante muss diese Funktion editieren. Lösung wäre, den Scene-State-ID **direkt am `LayoutMode`** zu führen (`data class LayoutMode(... val sceneStateId: Int)`).

- **Affected codebase files:** —
- **Suggestion:**
  Zwei strukturelle Anpassungen:
  1. `LayoutMode` um `val sceneStateId: Int? = null` erweitern (nullable für Overlay-Modes ohne Scene). `ImeViewBackend.render` liest `mode.sceneStateId` direkt; `toSceneStateId()` als globale Funktion entfällt. **Open-Closed:** neuer LayoutMode = neue Konstante mit eigener sceneStateId, kein Edit an Mapping-Funktion.
  2. Ein "§8.x Erweiterungs-Pattern: neuer Layout-Modus"-Abschnitt mit 5-Punkt-Checklist (siehe oben). Hilft auch der Code-Review-Phase.

---

### Issue S-5: DRY — Slot-Definitionen pro LayoutMode haben hohe Wiederholung trotz Predicate-Helpern

- **Category:** [DRY]
- **Severity:** Important
- **Location:** Spec 2 §8.1–§8.4
- **Description:**
  Die Predicate- und Resolver-Helper (§8.5) sind extrahiert — gut. Trotzdem wiederholt jede der fünf `LayoutMode`-Konstanten denselben **Slot-Bauplan** für die 9 Buttons. Konkret:
  - In TWO_ROW, SINGLE_ROW, REPROCESS_STAGING ist der RESEND-Slot identisch (`predResendVisible` + `Action.ResendLastAudio`).
  - BACKSPACE-Slot ist in *allen 5* Modi identisch (`{ true }` + `Action.Backspace`).
  - SPACE-Slot ist in *allen 5* Modi identisch (`{ true }` + `Action.SpaceKey`).
  - ENTER-Slot ist in *allen 5* Modi identisch (`{ true }` + `Action.EnterKey`).
  - AUDIO_FOCUS-Slot variiert nur in `visibilityPredicate` (`{ true }` vs `{ false }`) — identische Action.

  Der einzige strukturelle Unterschied zwischen TWO_ROW und TWO_ROW_SEND_MODE (außer Send-Visuals auf RECORD) ist: TRASH/PAUSE/RESEND auf `{ false }`. Drei Predicate-Overrides → eigene komplette Mode-Definition.

  Das ist *fast* duplikatfrei dank der Helper, aber das wiederholte Slot-Skeleton (rund ~120 Zeilen für 5 Modi) ist Drift-anfällig: wer in einem Modus den BACKSPACE-Slot ändert (z.B. neuer Touch-Handler-Hook), muss in allen 5 nachziehen.

- **Affected codebase files:** —
- **Suggestion:**
  Konstante "Standard-Slots" extrahieren, die Modi nur überschreiben, was sich tatsächlich ändert. Beispiel:

  ```kotlin
  // app/.../keyboard/render/StandardSlots.kt
  val SLOT_BACKSPACE_DEFAULT = ButtonSlot(LogicalButtonId.BACKSPACE, WrapContent,
      visibilityPredicate = { true }, actionResolver = { Action.KeyboardInputAction.Backspace })
  val SLOT_SPACE_DEFAULT = ButtonSlot(LogicalButtonId.SPACE, FillRemaining, ...)
  val SLOT_ENTER_DEFAULT = ButtonSlot(LogicalButtonId.ENTER, WrapContent, ...)
  val SLOT_RESEND_DEFAULT = ButtonSlot(LogicalButtonId.RESEND, WrapContent,
      visibilityPredicate = ::predResendVisible, actionResolver = { Action.ResendLastAudio })
  // …
  fun slotResendHidden() = SLOT_RESEND_DEFAULT.copy(visibilityPredicate = { false }, actionResolver = { Action.NoOp })
  ```

  Dann sind die fünf `LayoutMode`-Definitionen 30–50 % schlanker und zeigen nur das, was diesen Modus eigen macht. SRP für die Modi wird sichtbar (Modi differenzieren sich, sie wiederholen nicht).

---

### Issue S-6: Slot-Apply-Helper kennt `MaterialButton` als Spezialfall — leichte ISP/DIP-Schwäche

- **Category:** [SOLID] (DIP / ISP)
- **Severity:** Nice-to-have
- **Location:** Spec 2 §5.1 `applySlotToView`
- **Description:**
  Der Helper hängt von `MaterialButton` (Lib-Klasse) explizit ab und führt `is MaterialButton`-Type-Check durch:
  ```kotlin
  if (view is MaterialButton) {
      slot.iconResolver(state)?.let { iconRes -> view.icon = ContextCompat.getDrawable(ctx, iconRes) }
      slot.textResolver(state)?.let { text -> view.text = text }
  }
  ```
  Drei Probleme:
  1. **DIP-leicht:** `applySlotToView` hängt am konkreten Lib-Typ statt an einer Abstraktion (z.B. `IconableTextableView`-Interface oder eine Wrapping-Funktion `setIcon(view, res)` / `setText(view, text)`).
  2. **OCP-leicht:** wenn ein Slot in Spec 3 (Overlay) auf einer `ImageView` (nicht MaterialButton) sitzt, wird der `iconResolver` stillschweigend ignoriert — ohne Warnung.
  3. **Test-Schwierigkeit:** der Helper ist nur über eine echte MaterialButton-Instanz testbar (Robolectric / Android-Test).

- **Affected codebase files:** —
- **Suggestion:**
  Pragmatisch (jetzt): unverändert lassen, aber `// TODO: extract to ViewSlotAdapter when 2nd backend lands`-Kommentar setzen.

  Sauber (mittelfristig): kleines Adapter-Interface einführen:
  ```kotlin
  fun interface SlotViewAdapter { fun apply(view: View, slot: ButtonSlot, state: DictateUiState, ctx: Context) }
  // Default-Adapter für MaterialButton, separater Adapter für ImageView (Overlay)
  ```
  Spec 3 (Overlay) wird sowieso einen zweiten View-Typ brauchen — das ist der Trigger.

---

### Issue S-7: KeyboardStateManager nach Refactor — Rolle nicht klar definiert (SRP-Restschwäche)

- **Category:** [SOLID] (SRP)
- **Severity:** Important
- **Location:** Spec 2 §9.3 (KSM-Migration), Bezug zu Spec 1
- **Description:**
  Heute (`KeyboardStateManager.kt:158-224`) macht KSM **vier** verschiedene Dinge: ContentArea-Visibility, Recording-Controls-Visibility, Prompts-Visibility, Overlay-Reset. Der Plan löscht §9.3 nur `applyRecordingControlsVisibility` und `applyVisibility` (~10 Zeilen). Der Rest "bleibt" — und wird zu einem `ContentAreaController` (§9.3 Tabelle).

  Frage: wie heißt die Klasse danach? Bleibt es ein `KeyboardStateManager`, der jetzt nur noch ContentArea + Prompts macht? Oder spaltet er sich in `ContentAreaController` + `PromptVisibilityController`? Das ist im Spec nicht eindeutig — nur "wird zu einem orthogonalen ContentAreaController" steht da, ohne weitere Details.

  Strukturell: nach dem Refactor sollte `KeyboardStateManager` als Klasse **entweder verschwinden** (bisher der "deterministische Visibility-Owner" laut Klassen-Header — diese Rolle gehört jetzt dem Catalog) **oder** klar umbenannt werden. Sonst trägt eine Klasse einen Namen, der nicht mehr ihre Verantwortung beschreibt → Naming-Drift (vgl. global Issue 1.1.1).

- **Affected codebase files:** `app/src/main/java/net/devemperor/dictate/core/KeyboardStateManager.kt`
- **Suggestion:**
  In §9.3 ein klarer Beschluss:
  - **Option A (empfohlen):** `KeyboardStateManager` aufspalten in:
    - `ContentAreaController` (mainButtonsCl/qwertz/emojiPicker-Container-Toggle)
    - `PromptVisibilityController` (promptsCl + Sub-Views)
    Beide getrennt vom Renderer-System, bekommen den `DictateUiState` separat.
  - **Option B:** `KeyboardStateManager` umbenennen in `ContentAreaController` und Prompts-Logik in eine eigene Klasse extrahieren.
  Beide Varianten lösen die SRP-Frage; Option A ist sauberer (eine Klasse, eine Achse).

---

### Issue S-8: PipelineStateManager-Naming-Drift sichtbar in §11.5 / §11.7 (global Issue 1.1.1)

- **Category:** [INTEGRATION]
- **Severity:** Important
- **Location:** Spec 2 §11.5 ("Amplitude/Timer-Hooks: kommen direkt vom `PipelineStateManager` (Spec 1)"), §11.7 (`onTap` über `Backend-onAction-Field` analog Click-Listener), §9.4 ("DictateUiState wird im PipelineStateManager (Spec 1) gehalten")
- **Description:**
  Spec 1 hat (laut §4.3) den `PipelineStateManager` zu `DictateOrchestrator` umbenannt. Spec 2 referenziert an drei Stellen weiterhin `PipelineStateManager` als Quelle (§9.4, §11.5). Das ist exakt die Naming-Drift, die in Phase 1 als globales Issue 1.1.1 markiert wurde — sie ist im Spec 2 nach dem Apply von Phase 1 immer noch sichtbar.

  Strukturell relevant, weil §11.5 (RecordingAnimationController.onAmplitude) eine **Direkt-Anbindung** an die Spec-1-Schicht beschreibt: "eigener StateFlow<AmplitudeTick> oder simple Callback-Methode am LocalBinder". Wenn Spec 1 das tatsächlich beim Orchestrator/AudioModule angesiedelt hat, muss Spec 2 das richtige Ziel benennen — sonst entsteht beim Implement eine falsche Dependency-Direction (Backend → veraltete Klasse).

- **Affected codebase files:** —
- **Suggestion:**
  Drei Stellen in Spec 2 finden + ersetzen:
  - §9.4: "DictateUiState wird im PipelineStateManager (Spec 1) gehalten" → "DictateUiState wird im DictateOrchestrator (Spec 1 §4.3) verwaltet, gespiegelt vom DictateUiStateStore (§4.4)"
  - §11.5: "kommen direkt vom PipelineStateManager (Spec 1)" → "kommen direkt vom AudioModule / RecordingModule (Spec 1 §15)" — die Amplitude-Quelle ist Modul-spezifisch.
  - §11.5 LocalBinder-Erwähnung: gegen Spec 1 §5 (Local-Binder API) abgleichen; die Amplitude-Achse muss explizit dort modelliert sein, sonst öffnet sich eine API-Lücke.

---

### Issue S-9: ButtonSlot-Predicate-Compositionsmuster — Risiko der Doppel-Definition (DRY)

- **Category:** [DRY]
- **Severity:** Nice-to-have
- **Location:** Spec 2 §8.1–§8.4 (5 LayoutModes), §8.5 (Predicate-Helper)
- **Description:**
  `predResendVisible` ist zentral definiert (§8.5). Aber die zweite Bedingungs-Schicht — "in welchem LayoutMode wird `predResendVisible` benutzt vs. `{ false }`" — ist über die Modi verteilt. TWO_ROW + SINGLE_ROW + REPROCESS_STAGING benutzen das echte Predicate; SEND_MODE-Varianten benutzen `{ false }`. Das ist drei Mal "ja" und zwei Mal "nein", und beides ist hartkodiert in den `LayoutMode`-Konstanten.

  Es gibt eine implizite Regel: "Wenn isPipelineLive → resend immer false". Diese Regel lebt heute *nur* implizit in der Modi-Verteilung von `predResendVisible` vs `{ false }`. Wer den `predResendVisible`-Helper liest, sieht ihn nicht — er muss alle 5 Modi inspizieren.

- **Affected codebase files:** —
- **Suggestion:**
  Den Helper um die Pipeline-Klausel erweitern und in allen 5 Modi denselben Helper benutzen:

  ```kotlin
  fun predResendVisible(state: DictateUiState): Boolean =
      state.lastAudioExists
          && state.resendEnabled
          && state.recording is RecordingState.Idle
          && state.pipeline is PipelineUiState.Idle  // schließt SEND_MODE + STAGING bereits aus
  ```
  (Das tut der Helper bereits! → siehe §8.5 Z. 1124-1129.) Damit sind die `{ false }`-Overrides in den SEND_MODE/STAGING-Modi *redundant*. Spec 2 hat hier eine ungenutzte Helper-Verbesserung — das Predicate kann **direkt in allen 5 Modi** verwendet werden, die `{ false }`-Overrides entfallen → DRY-Beweis stärker.

  Gleiche Analyse gilt für `predTrashVisible` und `predPauseVisible`: das Helper-Predicate ergibt im Pipeline-State bereits `false` → SEND_MODE-Override `{ false }` ist überflüssig.

  Strukturell: aus 5 sehr unterschiedlich aussehenden Modi werden 5 fast identische, die nur durch (a) Layout-Position (in Scene-XML) und (b) `RECORD`/`AUDIO_FOCUS`-Slot-Verschiedenheiten variieren. Das ist die ehrliche Strukturaussage.

---

### Issue S-10: Touch-Handler-Wiring (§11.7) — Lifecycle vs. RenderBackend-Interface unklar

- **Category:** [INTEGRATION]
- **Severity:** Nice-to-have
- **Location:** Spec 2 §6 (`detach()`-Kommentar "Click-Listener werden NICHT abgemeldet"), §11.7
- **Description:**
  `ImeViewBackend.detach()` (§6) lässt Click-Listener und Touch-Handler bewusst hängen ("ein versehentlicher Klick auf einen detached Backend ergibt dann ein NoOp"). Das ist plausibel für Click-Listener (`stateRef = null` → return @setOnClick), aber:
  - `BackspaceSwipeHandler` hält interne Repeat-Timer (`AcceleratingRepeatHandler`) — wenn der Backend detached, der Handler aber noch einen Repeat-Tick produziert, könnte das in Spec 1 eine Ghost-Action emittieren.
  - `EnterOverlayHandler` mutiert direkt `overlayCharactersLl.visibility` (§11.7 Special-Note) — wenn der View weg ist, NPE-Risiko.

  Das ist die Sorte Failure-Mode, die im Plan **explizit benannt** werden sollte. §6 macht es nur für Click-Listener, nicht für Touch-Handler.

- **Affected codebase files:** `app/src/main/java/net/devemperor/dictate/keyboard/BackspaceSwipeHandler.kt`, `EnterOverlayHandler.kt`
- **Suggestion:**
  §6 `detach()`-Kommentar erweitern: "Touch-Handler werden ebenfalls nicht abgemeldet, halten aber **keine** Background-Coroutines/Repeat-Handler über Backend-Lifecycle hinaus. Repeat-Timer in `BackspaceSwipeHandler` werden bei `MotionEvent.ACTION_UP/CANCEL` gestoppt — wenn der View detached ohne UP-Event, ist der Repeat-Timer beim nächsten Tick auf einer toten View, was via `inputConnectionProvider() == null`-Guard abgefangen wird."

  Falls dieser Guard nicht existiert: Spec 2 §11.7 müsste ihn explizit fordern.

---

### Issue S-11: Naming — `LayoutModeId` overlappt mit `LayoutMode` und `Action.LayoutAction`

- **Category:** [INTEGRATION]
- **Severity:** Nice-to-have
- **Location:** Spec 2 §3.2
- **Description:**
  Drei sehr ähnliche Namen kollidieren:
  - `LayoutMode` (Spec 2 §3.2): data class mit `id`, `backend`, `rows`.
  - `LayoutModeId` (Spec 2 §3.2): Enum der Mode-IDs.
  - `Action.LayoutAction` (Spec 2 §3.3): Sealed class für Layout-Toggles.
  - `LayoutModule` (Spec 1 §4.8): Plugin für Layout-State.
  - `KeyboardLayoutModeController` (Heute, Migration §9.1): wird gelöscht.
  - `KeyboardLayoutManager` (Spec 2 §4): neu.

  Sechs verschiedene "Layout"-Klassen. Insbesondere `LayoutMode` (Daten-Konfiguration für ein UI) vs. `LayoutModule` (Modul-Achse mit Sub-State) ist gefährlich verwechselbar. Im Code-Review werden Reviewer die zwei verwechseln.

- **Affected codebase files:** —
- **Suggestion:**
  Eine Umbenennung evaluieren:
  - `LayoutMode` → `KeyboardLayoutBlueprint` oder `LayoutDescriptor` (was es ist: deklarative Layout-Beschreibung).
  - `LayoutModeId` → `KeyboardLayoutBlueprintId` oder `LayoutVariant`.
  - `Action.LayoutAction` bleibt (gehört zu Modul-Achse).
  - `LayoutModule` bleibt (Spec-1-Owner).
  - `KeyboardLayoutManager` bleibt.

  Damit sind die zwei Achsen — Modul-State und UI-Beschreibung — namentlich getrennt.

---

### Issue S-12: §6 `ImeViewBackend.render()` — Idempotenz nicht gefordert (CleanCode-Schwäche, struktur-relevant)

- **Category:** [SOLID] (SRP, indirekt)
- **Severity:** Nice-to-have
- **Location:** Spec 2 §6 (Z. 411-434)
- **Description:**
  `render(state, mode)` wird laut Spec bei jeder State-Emission aufgerufen (§4 `onStateChanged`). In hot-loops (Pipeline-Tick alle 100ms) bedeutet das 9 Slot-Apply-Operationen pro Tick. Der Helper `applySlotToView` setzt jedes Mal `view.visibility`, `view.isEnabled`, `view.alpha`, ggf. `view.icon`, `view.text` — auch wenn der Wert sich nicht geändert hat.

  Strukturell: Idempotenz ist nicht definiert, und die Folgen sind nicht abgewogen:
  - Setzt `view.visibility = VISIBLE` auf einer schon sichtbaren View einen Layout-Pass aus? (Android: ja, mit Optimierung; aber `view.text = "same string"` triggert immer ein Re-Layout in MaterialButton.)
  - Animation-Cancellation: wenn `applySlotToView` den `view.icon` neu setzt (gleicher Drawable-Resource), könnte die `keyPressAnimator`-Animation reset werden.

  Spec hat in §11.6 die Click-Listener-Optimierung adressiert, aber die analoge Frage für Slot-Apply-Properties fehlt.

- **Affected codebase files:** —
- **Suggestion:**
  In §5.1 oder §6 einen Absatz "Idempotenz" ergänzen:
  - `applySlotToView` setzt Properties **unconditional** (entscheidung wegen Einfachheit).
  - Trade-off: bei 100ms-Tick = 9 Property-Mutations × 100ms = vernachlässigbar (Android-Diff-Render-Pipeline filtert no-op-Setter).
  - Alternative wäre Per-Slot-Caching (`lastAppliedSlotState`-Map) — wird **nicht** eingebaut, weil Komplexität > Nutzen.

  Diese Begründung gehört in den Plan, sonst wird sie im Code-Review aufgemacht.

---

## Zusammenfassende Tabelle

| #    | Category      | Severity      | Issue (Kurz)                                                  | Beschreibung                                                                                       |
|------|---------------|---------------|---------------------------------------------------------------|----------------------------------------------------------------------------------------------------|
| S-1  | [SOLID]       | Critical      | Visibility-Ort (D2) inkonsistent zwischen MotionScene + Helper | `applySlotToView` und MotionScene mutieren beide Visibility — L3 nicht durchgehalten              |
| S-2  | [SOLID]       | Critical      | Manager/Backend-Bezug zu Spec-1 LayoutModule fehlt            | LayoutModule (Spec 1) als State-Eigentümer wird nirgends explizit referenziert; ContentArea offen |
| S-3  | [INTEGRATION] | Important     | D1 (MotionLayout-Wahl) im Spec-Text nicht begründet            | Begründung lebt nur in Recherche-Files, Spec ist nicht standalone lesbar                           |
| S-4  | [SOLID/OCP]   | Important     | Neue Layout-Modi: 5 Stellen editieren ohne klare Anleitung    | `toSceneStateId()` extension bricht OCP, Erweiterungs-Checklist fehlt                              |
| S-5  | [DRY]         | Important     | Slot-Skeleton wiederholt sich über 5 Modi                     | Standard-Slots als Konstanten extrahieren, Modi überschreiben nur Differenzen                      |
| S-6  | [SOLID]       | Nice-to-have  | `applySlotToView` mit MaterialButton-Type-Check (DIP-leicht)  | Adapter-Pattern für 2. View-Typ in Spec 3 (Overlay) vorbereiten                                    |
| S-7  | [SOLID/SRP]   | Important     | KSM-Rolle nach Refactor nicht klar definiert                  | Aufspalten in `ContentAreaController` + `PromptVisibilityController` oder umbenennen               |
| S-8  | [INTEGRATION] | Important     | PipelineStateManager-Naming-Drift in §9.4 / §11.5             | global Issue 1.1.1 in Spec 2 nicht vollständig nachgepflegt                                        |
| S-9  | [DRY]         | Nice-to-have  | `predResendVisible` in SEND_MODE/STAGING redundant            | Helper schließt Pipeline bereits aus → `{ false }`-Overrides entfallen                             |
| S-10 | [INTEGRATION] | Nice-to-have  | Touch-Handler-Lifecycle in `detach()` nicht beschrieben        | Repeat-Timer + EnterOverlay-Visibility-Mutation als Failure-Mode benennen                          |
| S-11 | [INTEGRATION] | Nice-to-have  | Naming-Kollision LayoutMode/LayoutModule/LayoutAction         | `LayoutMode` → `LayoutBlueprint` o.ä. zur Trennung von Modul-Achse                                 |
| S-12 | [SOLID]       | Nice-to-have  | `render()`-Idempotenz nicht spezifiziert                       | Trade-off (kein Caching) explizit dokumentieren, statt offen lassen                                |

---

## Quick-Recap für den Sanity-Check-Consolidator

**Critical (2):**
- S-1: Visibility-Owner-Lücke (MotionScene-Default vs Catalog-Helper für statische Buttons)
- S-2: Bezug Spec 2 ↔ Spec 1 LayoutModule + ContentArea-Renderer fehlt

**Important (5):**
- S-3: D1-Begründung in Spec einbauen
- S-4: OCP-Erweiterungs-Pattern (sceneStateId in LayoutMode + Checklist)
- S-5: DRY: Standard-Slots extrahieren
- S-7: KeyboardStateManager-SRP nach Refactor (umbenennen/aufspalten)
- S-8: PipelineStateManager-Naming-Drift in §9.4 / §11.5 nachpflegen

**Nice-to-have (5):**
- S-6: SlotViewAdapter für 2. View-Typ vorbereiten
- S-9: Predicate-Helper-Klausel ausnutzen, Overrides entfernen
- S-10: detach()-Lifecycle für Touch-Handler dokumentieren
- S-11: LayoutMode-Naming entzerren
- S-12: Idempotenz-Trade-off explizit machen
