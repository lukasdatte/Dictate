# Phase 1 — Pattern & Reusability Scout

**Plan:** Keyboard-Layout-Refactor (4-file modular plan, ~6993 lines)
**Worktree:** `/home/lukas/WebStorm/Dictate` (no worktree, direct on `feature/language-chip-curation`)
**Date:** 2026-05-10
**Reviewer focus:** existing Dictate patterns vs. plan's new abstractions; consistency across the 4 plan files; idiomatic Kotlin translation of the Excel-EKL Module-Augmentation pattern.

---

## Executive summary

The plan introduces a coherent MVI-ähnliches Architektur (StateFlow + sealed Action + reducer + side-effects + 13 plugin modules). The core abstractions are well-grounded in existing Dictate patterns (sealed RecordingState, sealed PipelineUiState, StateFlow in ActiveJobRegistry, Pref<T>-Registry). The Excel-EKL Module-Augmentation pattern is translated to Kotlin via `sealed interface DictateModule` + `object`-Singletons + manual `DictateModuleRegistry` — this is an idiomatic Kotlin equivalent and **does not** reinvent existing repo patterns.

**Critical issues found:**

1. **Action-Hierarchie wurde nur in §3.3 von Spec 2 vollständig propagiert; §8.5 + §8.6 von Spec 2 und ALLE Action-Refs in Spec 3 nutzen weiter die alten flachen Namen.** ❌ harte Cross-Spec-Inkonsistenz, F-8/F-11 nicht zu Ende durchgezogen.
2. **`PipelineStateManager` vs. `DictateOrchestrator` Naming-Drift** in Spec 1 (57 verbleibende `PipelineStateManager`-Refs nach F-11-Rename) und in Spec 3 (10 Refs zum alten Namen). 🟡 Naming-SSOT verletzt.
3. **Cross-Module-Effect-Modus 3 (Atomic Cross-Axis) ist im Pattern-Inventar erwähnt, aber im DictateOrchestrator-Code (§4.3) nicht verdrahtet** — dispatch() macht nur Action-Cascade. 🟡 inkonsistent zwischen Pattern-Beschreibung und Skelett.
4. **`Action.NoOp`-Default vs. `null`-Reducer-Return** als zwei parallele "diese Action war nicht erlaubt"-Mechanismen. 🟡 Konzept-Doppelgleisigkeit.

Detail siehe unten.

---

## 1 · Existing Dictate Patterns — Reuse Map

Die folgenden Patterns existieren **bereits** im Codebase und werden vom Plan korrekt referenziert oder weitergenutzt. Wo der Plan sie ablöst, ist die Ablöse-Strategie sinnvoll.

| Pattern / Utility | File | What it does | Plan-Relevanz |
|---|---|---|---|
| **Sealed `RecordingState`** | `app/src/main/java/net/devemperor/dictate/core/RecordingState.kt` | 4 Varianten (Idle/Preparing/Active/Paused), eliminiert die alten 3-Boolean-Flags | ✅ Plan übernimmt 1:1 als Sub-State (`DictateUiState.recording`); RecordingModule ist als Reducer-Vorbild perfekt — derselbe sealed-Stil. |
| **Sealed `PipelineUiState`** | `app/src/main/java/net/devemperor/dictate/core/PipelineUiState.kt` | 4 Varianten (Idle/Preparing/Running/ReprocessStaging) mit Daten-Klassen-Payload | ✅ Plan übernimmt 1:1 als Sub-State (`DictateUiState.pipeline`). Die `ReprocessStaging`-Variante mit `editableQueue/selectedLanguage/selectedModel`-Feldern bleibt unangetastet. |
| **`MutableStateFlow` + `update`** | `app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistry.kt` | Reaktiver Process-wide State-Container mit `state: StateFlow<...>`, `_state.update {...}`, atomarer Mutation, Java-Brücke via `ActiveJobRegistryObserver` | ✅ Plan übernimmt das Pattern für `DictateUiStateStore` (§4.4 Spec 1) — selbe `update`-Methode, selbe `StateFlow.collect`-Subscriber-Logik. **Reuse-Hinweis:** Wenn Java-Code (`DictateInputMethodService.java`) auf den State zugreifen soll, sollte ein analoger `DictateUiStateObserver` mit `repeatOnLifecycle` + `fun interface Listener` gebaut werden — derselbe Stil wie `ActiveJobRegistryObserver`. **Plan erwähnt das nicht explizit** → 🟡 Lücke. |
| **`Pref<T>`-Registry (sealed class)** | `app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt` | Type-safe Pref-Definitionen mit `key`, `default`; Extension `sp.get(pref)`, `sp.put(pref, value)` | ✅ Plan nutzt `Pref.SingleRowMode`, `Pref.AudioFocus`, etc. direkt im `PipelinePrefMirror` (§4.5 Spec 1). Die geplante Erweiterung um `Pref.OverlayPositionPortraitX/Y` etc. (Spec 1 §6.4) folgt exakt diesem Stil. |
| **`Callback`-Interface mit Default-Methoden** | `RecordingStateController.kt:89-97`, `RecordingManager.kt:26-33`, `MainButtonsController.kt:46-74`, `PipelineUiCallback.kt:13-19` | Single-Slot-Callback mit Default-Body-Methoden, gesetzt via Setter-Injection nach Konstruktion | ⚠️ Plan ersetzt diesen Stil mit `dispatch(Action)`-Single-Channel + `state.collect`-Subscription. **Migration-Spannung:** Der Plan-§9.x in Spec 1 listet die Migration auf, aber an einer Stelle (§9.x ‚KeyboardUiController.callbacks: CopyOnWriteArrayList`) bleibt die alte Multi-Callback-Topologie nur als „G4-Gap entfällt automatisch" markiert. Der Plan sollte expliziter sagen: **alle `Callback`-Interfaces in `core/` werden gelöscht** — keine bleibt als Mix. |
| **Strategy-Interface `RecordingAnimation`** | `app/src/main/java/net/devemperor/dictate/widget/RecordingAnimation.kt` | Strategy für swap-bare Animation mit Lifecycle (`prepare/start/pause/resume/cancel/onAmplitude/onTimerTick/updateColor`) | ✅ Plan übernimmt: `RecordingModule.Effect` (§15.2 Spec 1) ruft `services.borderGlow.start/stop/pause/resume` — identische Lifecycle-Methoden. Das ist konsistent. |
| **`BluetoothScoControl`-Interface (DIP-Test-Seam)** | `app/src/main/java/net/devemperor/dictate/core/BluetoothScoManager.kt:27-39` | Test-Seam-Interface für Production-Wrapper (Setter-Injection, kein Mockito) | ✅ Plan nutzt **denselben Stil** für `PipelineSessionRepo` + `PipelineRunner` (§4.9 Spec 1) und `OverlayWindow` + `OverlayPermissionGate` + `OverlayPositionMapper` + `OverlayDragHandlerFactory` (§4.1, §4.6, §4.7 Spec 3). Konsistent. |
| **`KeyboardStateManager.applyVisibility` als deterministische Visibility-Funktion** | `app/src/main/java/net/devemperor/dictate/core/KeyboardStateManager.kt:158-241` | Berechnet alle View-Visibilities aus den Lambda-Quellen (`isRecording`, `isPaused`, `isPipelineRunning`, `isRewordingEnabled`) | ⚠️ Plan löst das durch `LayoutCatalog`-Predicates (Spec 2 §8.5) und `applySlotToView` (Spec 2 §5.1) ab. Konzeptionell **dasselbe Pattern in besser** — pure Function, keine Hidden-State, deterministisch. ✅ **Aber:** Die `ContentArea`-Achse (MAIN_BUTTONS/QWERTZ/EMOJI_PICKER) bleibt im neuen System parallel zum LayoutCatalog (Spec 2 §13.5 Gap 3 dokumentiert das). Der `ContentAreaController` wird neu erfunden, obwohl `KeyboardStateManager.applyContentAreaVisibility` (Z. 171-181) genau dieselbe Aufgabe schon hat. Das ist **kein DRY-Verstoß** (Migration löscht KSM), aber der Plan sollte expliziter sein, ob `ContentAreaController` eine eigene Klasse bekommt oder Teil des LayoutModule wird. |
| **`KeyboardLayoutModeController.originalParents`-Map (Bug-Fix vom 2026-05-07)** | `KeyboardLayoutModeController.kt:60-74` | Sofort-Fix für asymmetrisches Re-Parenting | ✅ Plan löscht die Klasse vollständig (Spec 2 §9.1) — MotionLayout-Refactor macht Re-Parenting strukturell unnötig. Das ist explizit dokumentiert. |
| **`MainButtonsController.refreshAudioFocusIcon` (`.foreground = drawable`)** | `MainButtonsController.kt:368-387` | Synchronisiert beide Audio-Focus-Buttons (Edit-Bar + Single-Row) | ✅ Plan F-4 löst es: `resolveAudioFocusIcon(enabled)`-Helper (Spec 2 §8.5) wird **sowohl** vom AUDIO_FOCUS-Slot **als auch** vom EditBarController genutzt. F-7 ergänzt: `applySlotToView` benutzt `.icon` (nicht `.foreground`) konsistent in beiden Backends. |
| **`JobExecutor` als `object`-Singleton mit `initialize(orchestrator)`-Pattern** | `app/src/main/java/net/devemperor/dictate/core/JobExecutor.kt:29-72` | Process-wide Singleton mit Lazy-Init über Setter-Injection; Test-Seams `initializeForTest` / `resetForTest` | ✅ Plan übernimmt: `DictateModuleRegistry` ist auch ein `object`-Singleton (§4.8 Spec 1) mit init-Sanity-Check. **Reuse-Hinweis:** Die Test-Seam-Brücke fehlt im Plan — `DictateOrchestrator.initialize(modules)` würde dem etablierten Stil entsprechen. Heute ist im Plan die Module-Liste über Konstruktor-Default-Parameter `modules: List<DictateModule<*, *, *>> = DictateModuleRegistry.all` (§4.3) — funktioniert auch, ist aber subtil anders. |
| **`PipelineUiStateReader`-Interface (DIP für Sub-Subsystem-Zugriff)** | `app/src/main/java/net/devemperor/dictate/core/PipelineUiStateReader.kt` (referenziert in `LanguageController`) | Read-only-Interface auf `KeyboardUiController.state` | ⚠️ Plan ersetzt durch `state: StateFlow<DictateUiState>` direkt am LocalBinder (§5 Spec 1). Konsistent — aber: Subsysteme wie `LanguageController` lesen heute über `pipelineUiStateReader.state`, künftig über `localBinder.state.value.pipeline` oder via `state.collect`. Plan §9.x sollte LanguageController als Migration-Target explizit listen (es wird in §15.1 als `LanguageModule` neu eingeführt — wird der bestehende `LanguageController` gelöscht oder angepasst? Nicht klar). 🟡 |

---

## 2 · Plan-Internal Pattern-Konsistenz (DRY at the Plan Level)

### 2.1 Hierarchische `Action`-Sealed-Class — Propagation unvollständig

**Pattern:** Spec 2 §3.3 + Spec 1 §15 definieren `Action` als hierarchische sealed class mit inneren sealed classes pro Modul (`Action.RecordingAction.StartRecording`, `Action.ViewModeAction.ToggleViewModeWidget`, etc.). Das ist Voraussetzung für `KClass<A>`-basiertes Action-Routing in `DictateOrchestrator.dispatch()` (§4.3 Spec 1).

**Inkonsistenz:** In den konkreten Action-Verwendungen wird durchgängig die **flache** Notation benutzt:

| Datei | Zeilen | Beispiel | erwartet |
|---|---|---|---|
| Spec 2 §6 + §8.5 | Z. 1172-1192 | `Action.StartRecording`, `Action.PauseRecording`, `Action.NoOp`, `Action.CancelRecording`, `Action.ResumeRecording`, `Action.StopRecordingAndSend`, `Action.CancelReprocessStaging` | `Action.RecordingAction.StartRecording`, etc. |
| Spec 3 §3.1 | Z. 67-103 | `Action.StartRecording`, `Action.StopRecordingAndSend`, `Action.PauseRecording`, `Action.ResumeRecording`, `Action.CancelRecording`, `Action.ToggleViewModeWidget`, `Action.CloseOverlay`, `Action.NoOp` | hierarchisch |
| Spec 3 §6, §7.3 | Z. 907, 936, 1017, 1022, 1051-1053 | dito | hierarchisch |

Quantitativ: **31 flache Action-Refs in Spec 2, ~20 in Spec 3, 0 hierarchische Refs außerhalb der einen Erklär-Stelle in Spec 2 §3 Z. 245**. Spec 1 ist konsistent hierarchisch (24 hierarchische Refs in §4 + §15).

**Consequence:** Compile-Fehler bei der Implementierung. Auch der Compile-Time-Vorteil aus F-8 (Reducer-`when`-Exhaustivität pro Modul) hängt an der hierarchischen Struktur — flache Aktion auf Modul-Reducer compiled nicht.

**Klassifikation:** ❌ **harter Fehler, autonomer Fix unrealistisch (zu viele Stellen, jede einzeln korrekt zu klassifizieren)** → Ich klassifiziere das als **🟡 needs decision**: Phase-2-Apply muss systematisch durch alle Action-Refs gehen und hierarchisch korrigieren. Alternativ: Type-Aliases `typealias StartRecording = Action.RecordingAction.StartRecording` zentral einführen, dann sind beide Notationen syntaktisch gleich gültig — aber das untergräbt die Compile-Time-Garantie.

---

### 2.2 `PipelineStateManager` vs. `DictateOrchestrator` — Naming-Drift

**Pattern:** F-11 (2026-05-09) hat `PipelineStateManager` umbenannt zu `DictateOrchestrator`, weil er zum Composition Root + Action-Router ohne fachliche Logik geschrumpft ist (§4.3 Spec 1). Hilfsklassen behalten ihre Namen (`DictateUiStateStore`, `PipelinePrefMirror`, `PipelineRecovery`).

**Inkonsistenz:**

- **Spec 1:** 57 Refs zu `PipelineStateManager` nach Rename. Die Übergangs-Hinweise in §4-Vorbemerkung erwähnen den Rename, aber §6.4 (Z. 945, 973), §7.x (Z. 992, 1018, 1032, 1122, 1166), §9.x (Z. 1190, 1207, 1222, 1224, 1234, 1248, 1252-1253, 1258, 1267, 1354), §11.2 (Z. 1540, 1549, 1561) und §13.x (Z. 2143, 2146) verwenden noch konsequent den alten Namen.
- **Spec 3:** 10 Refs (Z. 755, 837, 910, 939, 957, 1019, 1057, 1093, 1132, 1141) — alle in den Mode-Transition-Pseudo-Code-Blöcken.
- **Hauptplan:** 1 Ref in §3.2 (Diagramm Z. 132), wo das Service-Diagramm noch `PipelineStateManager` zeigt.

**Consequence:** Reader denkt es gibt zwei Klassen (eine alte und eine neue), die parallel existieren. Das ist nach F-11 nicht mehr der Fall — `DictateOrchestrator` löst `PipelineStateManager` **vollständig ab**.

**Klassifikation:** 🟡 **needs decision** — entweder global rename oder explizit dokumentieren, dass beide Namen synonym sind (was Plan-§4 §11 nicht tut). Empfehlung: globales Rename in Apply-Phase. SSOT-Regel: ein Name pro Konzept.

---

### 2.3 Cross-Module-Effect-Modus 3 (Atomic Cross-Axis) ist deklariert, aber nicht verdrahtet

**Pattern:** Spec 1 §15.5 listet drei Cross-Module-Effect-Modi:
1. Eigene SideEffect (im Reducer-Output)
2. Action-Cascade (`onCrossModuleStateChange` returns Action-Liste)
3. **Atomic Cross-Axis-Update** (im Orchestrator nach normalem Reduce, mehrere Achsen in einem `store.update`)

**Inkonsistenz:** Der `DictateOrchestrator.dispatch`-Code (§4.3 Spec 1, Z. 442-478) implementiert **nur** Modi 1 + 2:

```kotlin
// Modus 1
result.sideEffects.forEach { effect -> typedModule.runEffect(effect, services) }
// Modus 2
val cascadeActions = modules.filter { it.id != module.id }.flatMap { it.onCrossModuleStateChange(prevGlobal, nextGlobal) }
cascadeActions.forEach { dispatch(it) }
```

Modus 3 fehlt. Die offenen Fragen in §F-11 (Z. 442) erwähnen es als **„Open Question, Implementierungsphase"** — aber §15.5 listet es bereits als etabliertes Pattern. Für „Pipeline-Done betrifft Resend + LivePrompt + PendingSessions in einem Update" gibt es keine Code-Skizze.

**Consequence:** Implementer steht vor einem Pattern, das beschrieben aber nicht skizziert ist. Es könnte ad-hoc gelöst werden (zwei aufeinanderfolgende dispatch-Calls), was Zwischen-States kurzzeitig sichtbar macht.

**Klassifikation:** 🟡 **needs decision** — entweder Modus 3 jetzt skizzieren (z.B. optionaler `composeAtomic(prev, next, action): DictateUiState?`-Hook auf `DictateModule` oder eine separate Liste atomarer Reducer im `DictateOrchestrator`) oder Modus 3 als „Phase-2-Pattern, vorerst nicht eingebaut" markieren und konsequent aus §15.5 entfernen. Halb-Pattern ist die schlechteste Option.

---

### 2.4 `Action.NoOp`-Default vs. Reducer-`null`-Return — Doppelgleis

**Pattern A:** `Action.NoOp` wird als Default für nicht-bindende Slots benutzt — Spec 2 §3.3 Z. 126 (`object NoOp : Action()`), und in Spec 2 §8.5 (`resolveRecordActionPipeline`) plus Spec 3 §3.1 (Schließen-Button im `else`-Fall).

**Pattern B:** Reducer geben `null` zurück, wenn die Action im aktuellen State nicht erlaubt ist — Spec 1 §4.2 Z. 360-362 (`Return null = Action war im aktuellen State nicht erlaubt (F1-Verstoß)`), in §15.2 verwendet (`else -> null`).

**Inkonsistenz:** Beides ist „diese Action soll nichts tun", aber:
- `Action.NoOp` läuft durch `dispatch()`, findet kein Modul (das `actionClass` matched), loggt `"Keine Modul-Zuordnung"` (§4.3 Z. 446-448) und beendet. Nicht intendiert.
- `null`-Return im Reducer: Action erreicht das Modul, aber das Modul lehnt sie ab.

**Consequence:** Wenn Spec 2 `actionResolver = { ... else -> Action.NoOp }` returns, dispatcht der Orchestrator das, sucht ein Modul für `NoOp::class` — findet keins, loggt Warning. Das ist Noise.

**Klassifikation:** 🟡 **needs decision** — Empfehlung: `Action.NoOp` aus der Action-Hierarchie entfernen und stattdessen den Slot-`actionResolver` als `(DictateUiState) -> Action?` (nullable) typisieren. Dann macht das Backend `?.let { onAction(it) }` und nicht-bindende Slots feuern keine dispatch-Calls. Alternativ: `NoOp` als spezielle "Ignore"-Variante im Orchestrator früh aussortieren (`if (action == NoOp) return`).

---

### 2.5 `KeyboardLayoutManager` als FSM-Owner vs. `ViewModeModule` als FSM-Owner

**Pattern:** Spec 2 §4 Z. 252-291 sagt: **„`KeyboardLayoutManager` ist zentraler Triangle-FSM-Owner"** (Z. 15: „subscribiert an `DictateUiState`, entscheidet über aktiven LayoutMode + RenderBackend").

Aber Spec 1 §15.1 sagt: **„`ViewModeModule` (Triangle-FSM KEYBOARD/WIDGET/HOVER, ehemals ViewModeFsm)"** — das Modul reduziert die FSM-Transitionen.

**Klärung:** Beide sind korrekt, mit unterschiedlichen Verantwortungen:
- `ViewModeModule` reduziert State (Triangle-FSM-**Logik**) — entscheidet, was nach `OnImeViewShown` der nächste `ViewMode` ist.
- `KeyboardLayoutManager` reagiert auf den **Output** der FSM (state.viewMode) und switched RenderBackend.

**Inkonsistenz im Wording:** „FSM-Owner" wird zweimal vergeben. Spec 2 §1 sagt „zentraler Triangle-FSM-Owner" — das suggeriert, dass die FSM-Logik im Manager lebt. Sie lebt aber im ViewModeModule.

**Consequence:** Mild verwirrend. Reader könnte denken, die FSM-Logik wird zweifach implementiert. Tatsächlich ist sie an einer Stelle (ViewModeModule), und der Manager ist nur Subscriber.

**Klassifikation:** 🟢 **auto-fix**: Wording in Spec 2 §1 + §4 anpassen — "Triangle-FSM-Reaktor" oder "FSM-Subscriber" statt "FSM-Owner". Eine Zeile pro Datei.

---

### 2.6 `PipelinePrefMirror`-Pattern vs. `LanguageController.persistInputLanguagesAndPos` (existing)

**Pattern (neu, Plan):** `PipelinePrefMirror` (§4.5 Spec 1) bündelt alle Pref-↔-State-Mirroring-Logik in einer Klasse mit `attach/detach/initialMirror/sync`.

**Pattern (existing):** `LanguageController` (`app/src/main/java/net/devemperor/dictate/core/LanguageController.kt`) macht dasselbe für die Language-Achse — eigener `persistInputLanguagesAndPos`-Helper, eigener SharedPreferences-Listener (vermutlich), eigene `setLanguage`-Methode.

**Übergang:** Spec 1 §15.1 listet `LanguageModule` als neues Modul (Achse 10). Der Plan ist **nicht explizit**, ob `LanguageController.kt` gelöscht wird (in `LanguageModule` aufgeht) oder weiter parallel zum Modul existiert. Die Pref-Mirror-Logik wäre dann **doppelt** — im neuen `PipelinePrefMirror` UND im bestehenden `LanguageController`.

**Klassifikation:** 🟡 **needs decision** — Plan §9.x in Spec 1 sollte `LanguageController` explizit als Migration-Target listen. SSOT-Regel: Pref-Mirroring lebt nur an einer Stelle.

---

## 3 · Excel-EKL Module-Augmentation → Kotlin: Übersetzungs-Audit

Die F-11-Begründung (Hauptplan Z. 410-411) referenziert Excel-EKL's Module-Augmentation-Pattern. Ich habe das Pattern-Dokument in `~/.claude/skills/knowledge-typescript/patterns/module-augmentation.md` und `~/.claude/skills/knowledge-reference/patterns/plugin-system.md` gegen die Kotlin-Übersetzung im Plan geprüft.

| TS-Pattern (Excel-EKL) | Kotlin-Übersetzung (Plan) | Idiomatisch? |
|---|---|---|
| `interface PluginRegistry { /* empty */ }` + `declare module './_core'` Augmentation pro Plugin | `sealed interface DictateModule<S, A, E>` mit `object`-Singletons pro Modul | ✅ Idiomatisch. Kotlin hat kein declarative Module-Augmentation; sealed interface ist die nächstbeste Sache (Compile-Time-Vollständigkeit). |
| `keyof TRegistry` für Compile-Time-Dispatch | `KClass<A>` Lookup-Map (`actionClass`-Property pro Modul) | 🟡 **Subtil verschieden**: Excel-EKL kennt String-Literal-Types als Plugin-Keys; Kotlin nutzt `KClass`. Plus: Excel-EKL macht den Type-Lookup **compile-time** (`TRegistry[K]`), Kotlin macht ihn **runtime** (`moduleByActionClass[action::class]`). Plan-§4.3 mitigiert mit `init`-Sanity-Check + `@Suppress("UNCHECKED_CAST")`. **Nicht idiomatisch optimal**, aber **idiomatisch akzeptabel** — Kotlin's Type-System hat keine dependent types. Alternative wäre Reflection-basierte Discovery, was Plan §F-11 Open Question 1 explizit verwirft (R8/ProGuard-Robustheit). |
| `discovery.ts` als explizite Liste aller Plugins | `DictateModuleRegistry.all = listOf(...)` | ✅ 1:1-Übersetzung. Sogar derselbe Stil-Punkt: explizit, nicht Reflection. |
| `BasePluginOrchestrator<TRegistry>` mit F-bounded Polymorphism | `DictateOrchestrator(modules: List<DictateModule<*, *, *>>)` mit `*`-Star-Projection und `@Suppress("UNCHECKED_CAST")` | 🟡 **Star-Projection ist die Kotlin-Variante des "any plugin"**. Akzeptabel. F-bounded gibt es in Kotlin nicht direkt. **Verlust:** Excel-EKL kann pro `K extends keyof TRegistry` exakt typisierte Schemas zurückgeben; Kotlin kann das nur mit Casts. Das ist eine fundamentale Sprach-Differenz, kein Plan-Defekt. |
| Per-plugin policy constraints im Type-Level (`TRegistry[K]['policy']`) | – nicht im Plan | ✅ Nicht nötig — die Cross-Module-Constraints im Plan sind Verhalten (Reducer + Cross-Module-Observer), nicht Policy-Schemas. Kein Verlust. |
| Module-Augmentation pro Plugin als **Side-effect-Import** | – Plan nutzt explizite Liste | ✅ Bewusste Wahl in Plan-§4.8 dokumentiert (debug-freundlich, R8-robust). Idiomatisch korrekt, weil Kotlin keine `declare module`-Side-Effect-Augmentation kennt. |

**Fazit:** Die Übersetzung ist **idiomatisch korrekt** für Kotlin. Die zwei Stellen, wo TS strenger ist (Compile-Time-Dispatch + F-bounded), sind echte Sprach-Limits, nicht Plan-Defekte. **Keine Klassifikation als Issue.**

---

## 4 · Globale Issues

| # | Kategorie | Issue | Klassifikation |
|---|---|---|---|
| **G1** | [DRY/Inkonsistenz] | `Action`-Hierarchie nur in Spec 2 §3.3 + Spec 1 §15 vollständig — Spec 2 §6 + §8 + Spec 3 alle `Action`-Refs sind flach. ~50 Stellen total. | ❌ **harter Fehler** — als 🟡 für Phase-2-Apply (zu komplex für Auto-Fix, weil pro-Stelle die richtige Achse zu wählen ist). |
| **G2** | [Naming-SSOT] | `PipelineStateManager` (alt, vor F-11) vs. `DictateOrchestrator` (neu) — 57 Stellen in Spec 1, 10 in Spec 3, 1 im Hauptplan-Diagramm. | 🟡 **needs decision** (global rename oder explizite Synonym-Doku) |
| **G3** | [Pattern-Inkonsistenz] | Cross-Module-Effect-Modus 3 (Atomic Cross-Axis) ist deklariert (§15.5), aber Orchestrator-Code (§4.3) implementiert ihn nicht. | 🟡 **needs decision** (Skizzieren oder als Phase-2 markieren) |
| **G4** | [Konzept-Doppelgleis] | `Action.NoOp`-Default vs. Reducer-`null`-Return als zwei „diese Action ist nicht aktiv"-Mechanismen. | 🟡 **needs decision** (NoOp-Variante entfernen, Action-Resolver nullable typisieren ist mein Vorschlag) |
| **G5** | [SSOT-Lücke] | Bestehende Klassen wie `LanguageController.kt`, `RecordingManager.kt`, `BluetoothScoManager.kt`, `KeyboardUiController.kt` werden in `core/` weiter existieren oder gelöscht? Spec 1 §9 listet einige Migrationen, aber nicht alle. Speziell `LanguageController` vs. `LanguageModule` ist unklar. | 🟡 **needs decision** (Migration-Liste vervollständigen) |
| **G6** | [Existing-Pattern-Reuse] | Plan baut `DictateUiStateStore` ohne Java-Brücke. `ActiveJobRegistryObserver` zeigt das etablierte Pattern (Lifecycle-bound, fun interface Listener). Da `DictateInputMethodService.java` weiterhin Java ist, wird ein analoger `DictateUiStateObserver` gebraucht — Plan erwähnt das nicht. | 🟢 **auto-fix-bar**: §4.4 Spec 1 erweitern um eine kurze Notiz „Java-Brücke analog `ActiveJobRegistryObserver`"; konkrete Klasse kann in der Implementierung gebaut werden. |
| **G7** | [Wording] | „FSM-Owner" wird zweimal vergeben (KeyboardLayoutManager und ViewModeModule). | 🟢 **auto-fix-bar**: Spec 2 §1+§4 Wording anpassen — z.B. „FSM-Subscriber/Renderer" beim Manager. |
| **G8** | [Reuse-Hinweis] | `JobExecutor.initialize(orchestrator)`-Pattern (Singleton mit Lazy-Init) ist im Code etabliert; `DictateModuleRegistry` ist auch Singleton aber ohne explizites `initialize`-Pattern (Module sind Const). Konsistenz-Frage: lohnt sich ein `DictateOrchestrator.initialize(...)`-Methode für Test-Seam-Symmetrie? | 🟢 **auto-fix-bar / dokumentieren** — Plan §4.8 könnte zwei Sätze über Test-Seams ergänzen, wenn das gewünscht ist. |
| **G9** | [F-7-Konsistenz] | F-7 (geteilter `applySlotToView`) ist in Spec 2 §5.1 + Spec 3 §13.3 ausdrücklich konsolidiert; Click-Listener-Routing aber **bewusst** backend-spezifisch belassen (IME: static im `wireStaticHandlers`; Overlay: pro Render). Spec 3 §13.4 Zeile „Click" dokumentiert die Begründung sauber. | ✅ **kein Issue** — bewusste Differenzierung, klar begründet. |
| **G10** | [Plan-Datei-Konsistenz] | Hauptplan §3.2-Diagramm zeigt noch `PipelineStateManager` ↔ `KeyboardLayoutManager`-Beziehung; die F-11-Iteration im Iteration-Log nennt `DictateOrchestrator`. | 🟢 **auto-fix-bar**: Diagramm Z. 132 in Hauptplan updaten (`PipelineStateManager` → `DictateOrchestrator`). |

---

## 5 · Pattern-Erweiterungs-Vorschläge (kein Issue, aber Reuse-Potenzial)

| Vorschlag | Begründung |
|---|---|
| **`DictateUiStateObserver` mit `repeatOnLifecycle` + `fun interface Listener`** als Java-Brücke zur StateFlow | `DictateInputMethodService.java` ist Java; ohne Brücke muss jede Java-Stelle den `state.collect`-Lifecycle selbst basteln. `ActiveJobRegistryObserver` zeigt das etablierte Pattern (`app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistryObserver.kt`). |
| **`ImmutableActions`-Test-DSL für Reducer-Tests** | Plan §13.3.4 betont Reducer als pure functions. Excel-EKL etabliert Test-Patterns für solche Reducer (immutable input + assertion auf output). Lohnt sich, ein kleines DSL `assertReducer(state, action) returns nextState withEffects [...]` zu skizzieren — hält die Reducer-Tests konsistent. |
| **`@Synchronized`-Annotation auf `dispatch`** | `ActiveJobRegistry.register/update/unregister` sind alle `@Synchronized`. Plan-§4.3 sagt nichts über Thread-Safety von `dispatch`. Wenn Cross-Module-Cascade rekursiv dispatcht, kann das auf verschiedenen Threads landen. Sicherheitshalber `@Synchronized` ergänzen oder explizit dokumentieren, dass dispatch nur auf Main-Thread läuft. |

---

## 6 · Klassifikations-Übersicht

| Bucket | Anzahl | Issues |
|---|---|---|
| 🟢 **auto-fix-bar** (Phase-2 Apply automatisch) | 4 | G6, G7, G8, G10 |
| 🟡 **needs decision** (Phase-2 mit User-Klärung) | 5 | G1, G2, G3, G4, G5 |
| ❌ **false positive** | 0 | – |

**Schwerpunkt für Phase 2:** Die fünf 🟡-Issues sind alle harte Architektur-Inkonsistenzen, die nicht durch String-Replace lösbar sind. G1 (Action-Hierarchie-Propagation) ist mit ~50 Stellen am aufwendigsten; G5 (Migration-Liste vervollständigen) braucht User-Input für jede Klasse einzeln.

---

## 7 · Anhang — Code-Pointer für Phase 2

### Existing Patterns die übernommen werden sollten

- `app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistry.kt` — StateFlow + update + Lifecycle-Observer
- `app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistryObserver.kt` — Java-Brücke
- `app/src/main/java/net/devemperor/dictate/core/RecordingState.kt` — sealed-class Vorbild
- `app/src/main/java/net/devemperor/dictate/core/PipelineUiState.kt` — sealed-class mit Daten
- `app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt` — Pref<T>-Registry
- `app/src/main/java/net/devemperor/dictate/core/BluetoothScoManager.kt:27-39` — DIP-Test-Seam-Pattern

### Klassen die im Refactor migriert/gelöscht werden müssen (Plan-Lücke G5)

- `app/src/main/java/net/devemperor/dictate/core/RecordingStateController.kt` — wandert in `RecordingModule`
- `app/src/main/java/net/devemperor/dictate/core/RecordingUiController.kt` — wandert in LayoutCatalog-Resolvers
- `app/src/main/java/net/devemperor/dictate/core/MainButtonsController.kt` — wandert in ImeViewBackend
- `app/src/main/java/net/devemperor/dictate/core/KeyboardStateManager.kt` — entfällt, Visibility wandert in LayoutCatalog
- `app/src/main/java/net/devemperor/dictate/core/KeyboardLayoutModeController.kt` — entfällt durch MotionLayout
- `app/src/main/java/net/devemperor/dictate/core/KeyboardUiController.kt` — State wandert in `DictateUiState.pipeline`, View-Logik in LayoutCatalog
- `app/src/main/java/net/devemperor/dictate/core/LanguageController.kt` — **unklar im Plan**; wird vermutlich `LanguageModule` (§15.1)
- `app/src/main/java/net/devemperor/dictate/core/RecordingManager.kt` — **unklar im Plan**; wird vermutlich `RecordingHardwareSubsystem` (im `ModuleServices`)
- `app/src/main/java/net/devemperor/dictate/core/BluetoothScoManager.kt` — **unklar im Plan**; wird vermutlich `BluetoothScoSubsystem` (im `ModuleServices`)
- `app/src/main/java/net/devemperor/dictate/core/JobExecutor.kt` — bleibt? wird `PipelineRunner`-Implementation? `PipelineOrchestratorRunner` wird referenziert in der Codebase aber Plan §4.9 sagt nur „statisches `JobExecutor`-object adaptiert das Interface"

### Spec-interne Konsistenz-Hotspots (G1)

- Spec 2: Z. 1124-1192 (§8.5 Predicate/Resolver-Helfer) — alle Action-Refs flach
- Spec 2: Z. 904-1110 (§8.1-8.4 LayoutMode-Definitionen) — alle Action-Refs flach (über Slots)
- Spec 3: Z. 47-107 (§3.1 OVERLAY_5BUTTON-Definition) — alle Action-Refs flach
- Spec 3: Z. 907-1141 (§7.x Mode-Transitionen) — Pseudo-Code mit alten Namen + flachen Actions
