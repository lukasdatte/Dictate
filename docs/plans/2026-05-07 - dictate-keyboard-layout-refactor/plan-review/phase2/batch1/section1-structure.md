# Phase 2 — Section 1 Structure Review: "State-Modell + Modul-System"

**Reviewer-Rolle:** Structure (DRY / SOLID / Architecture-Integration)
**Review-Target:** Spec 1 §3 (Datenmodell `DictateUiState`), §4 (DictateOrchestrator + Modular Plugin-Pattern, §4.1–§4.10), §5 (Local-Binder), §15 (Modul-Inventar, §15.1–§15.6)
**Plan-Datei:** `/home/lukas/WebStorm/Docs/docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.md`
**Code-Cross-Reference-Repo:** `/home/lukas/WebStorm/Dictate`
**Datum:** 2026-05-10

---

## Vorgehensweise

1. **DRY-Check** — Pattern-Wiederholung (intern + ggü. Excel-EKL-Vorbild aus Pattern-Catalog)
2. **SOLID-Check** — SRP / OCP / LSP / ISP / DIP auf Klassen-/Interface-Ebene
3. **Architecture-Integration** — Pattern-Konsistenz zu Excel-EKL Module-Augmentation, sealed-interface-Konvention, file/folder-Layout

Performance- und Robustheits-Findings werden hier NICHT erfasst (gehören zur Logic-Reviewer-Section bzw. sind nicht-anwendbar im Plan-Review).

Globale Issues 1.1.2 / 1.1.3 / 1.1.5 / 1.1.7 / 1.1.8 sind in Phase 1 bereits klassifiziert; ich ergänze nur Section-spezifische Strukturperspektiven, keine Doppel-Findings.

---

## Findings

### Issue S-1: `PipelinePrefMirror.sync()` — repetitive `current.copy(sub = current.sub.copy(...))`-Kette dupliziert Pref-Zuordnung

- **Category:** [DRY]
- **Severity:** Important
- **Location:** Spec 1 §4.5 Z. 575-600 (`sync()`-Funktion mit 19-Branch-`when`)
- **Description:** Die `sync()`-Funktion enthält 19 nahezu identische `when`-Branches. Jeder Branch folgt dem Schema:
  ```
  Pref.<X>.key -> current.copy(<sub> = current.<sub>.copy(<feld> = sp.get(Pref.<X>)))
  ```
  Drei Datenpunkte sind variabel: Pref-Name, Sub-State-Pfad, Feld-Name. Die initiale Befüllung in `initialMirror()` (Z. 541-573) wiederholt dieselbe Zuordnung in einer zweiten Ausprägung — **derselbe Pref↔State-Mapping-Wissensanker doppelt kodiert**.

  Folgen:
  - Eine neue Pref hinzufügen heißt: zwei Stellen ändern (Initial + Sync). Wird routinemäßig vergessen werden.
  - Pref-Drift: `initialMirror` setzt einen Default; `sync` liefert einen anderen Default (z.B. `OverlayPositionPortraitX` Default `1.0f` an beiden Stellen — bislang in Sync, könnte aber leicht auseinanderlaufen).
  - Die Anzahl der Prefs steigt mit jeder neuen Sub-State-Achse — der `when`-Block wird bei jedem Modul-Hinzufügen länger.

  **Pattern-Vergleich Excel-EKL Module-Augmentation (aus Pattern-Catalog):** Module deklarieren ihre Prefs lokal beim Modul; ein Registry-Mechanismus aggregiert sie. Hier wird zentral aggregiert mit hartem `when`.
- **Affected codebase files:**
  - Plan-Datei §4.5 ist Skeleton (kein implementierter Code im Repo).
  - `app/src/main/java/net/devemperor/dictate/util/Pref.kt` (existiert) — die `Pref<T>`-Sealed-Klasse verwaltet bereits Prefs typisiert; das Pattern könnte am Modul-Sub-State angedockt werden.
- **Suggestion:**
  Pref-Mirror-Mapping pro Modul deklarativ machen, Pref-Mirror nur noch als Aggregator. Skizze:
  ```kotlin
  interface DictateModule<S, A : Action, E : SideEffect> {
      // ... existing methods ...

      /** Pref-Bindings dieses Moduls, default leer. */
      fun prefBindings(): List<PrefBinding<S, *>> = emptyList()
  }

  data class PrefBinding<S, T>(
      val pref: Pref<T>,
      val apply: (sub: S, value: T) -> S,    // pure: sub → next-sub mit gesetztem Feld
  )
  ```
  Der `PipelinePrefMirror` baut beim Init eine Map `pref.key -> (module, binding)` aus
  `DictateModuleRegistry.all.flatMap { it.prefBindings() }`. `sync(key)` und
  `initialMirror()` greifen denselben Mechanismus an — eine Quelle der
  Wahrheit pro (Modul, Feld). Neuer Pref → neue Zeile im Modul, kein Touch
  am PrefMirror. Konsistent zu OCP-Modul-Geist (§15.6) und zu Excel-EKL-
  Pattern (Augmentation lokal beim Augmentor).

  Alternativ pragmatisch: ein `prefMap: Map<String, (DictateUiState, SharedPreferences) -> DictateUiState>` als Klassen-Konstante; `sync()` reduziert sich auf ein Map-Lookup. Weniger architektonisch, aber eliminiert die `when`-Kaskade.

---

### Issue S-2: `ReducerContext` ist asymmetrisch — exposed nur Sub-States, die der heutige Recording-Reducer braucht

- **Category:** [SOLID]
- **Severity:** Important (ISP / OCP)
- **Location:** Spec 1 §4.2 Z. 404-408 (Daten-Klasse `ReducerContext`)
- **Description:** `ReducerContext` ist deklariert als:
  ```kotlin
  data class ReducerContext(
      val audio: AudioState,
      val recordingAudioFile: File?,
      val now: Long = ...,
  )
  ```
  Heißt: jeder Reducer aller 13 Module bekommt nur `audio` + `recordingAudioFile` + `now`. Pipeline-Reducer brauchen aber `language` (für Reprocess-Override-Auflösung), Resend-Reducer brauchen `pipeline` (Done-State trigger), LivePromptModule braucht `pipeline.ReprocessStaging`-Detail. Die Schnittstelle ist auf den Recording-Module-Bedarf zugeschnitten.

  Konsequenz: jede neue cross-axis-Bedingung im Reducer eines anderen Moduls erfordert eine `ReducerContext`-Erweiterung — und damit eine Berührung der **zentralen** Daten-Klasse. Das verletzt OCP des Modul-Patterns ("neues Modul = nur neue Datei", §15.4 Step 1-6) und ISP (Module bekommen Daten, die sie nicht brauchen, oder müssen warten bis ihre Achse aufgenommen wird).

  Zusätzlich: `recordingAudioFile` ist hier ein Hardware-Read (siehe Issue 1.1.8); selbst nach dessen Auflösung bleibt die Asymmetrie strukturell — nur `recording` und `audio` sind privilegiert.
- **Affected codebase files:** Plan-only.
- **Suggestion:** Zwei Optionen, je nach Issue-1.1.8-Entscheidung:

  **Option A** (sauberster Schnitt, deckt zugleich 1.1.8): `ReducerContext` durch das **gesamte `DictateUiState` (immutable Snapshot)** ersetzen plus `now`. Der Reducer ist Pure relativ zu `(subState, action, globalSnapshot)` — er DARF lesen, wo nötig, ohne dass die Schnittstelle bei jedem neuen Bedarf wachsen muss. Ein Modul-Reducer verzichtet auf den Schreibzugriff (da `read`/`write`-Lens existieren); der Snapshot dient nur dem Querlesen.
  ```kotlin
  data class ReducerContext(val global: DictateUiState, val now: Long)
  ```
  Trade-off: Module könnten "zu viel" lesen — aber das ist im Plan ohnehin durch §15.5 Mode 2 (Action-Cascade) vorgesehen, nur nicht im Reducer-Pfad selbst kodiert.

  **Option B** (ISP-strikt, mehr Boilerplate): `ReducerContext` pro Modul — jedes Modul deklariert in seinem Vertrag, welche Sub-States es im Kontext erwartet. Der Orchestrator baut den Modul-spezifischen Kontext. Stark typisiert, aber 13× ein eigenes Context-Type.

  **Empfehlung: Option A.** Sie beseitigt OCP-Drift mit niedrigem Footprint und ist konsistent mit `onCrossModuleStateChange(prev, next)` (§4.2 Z. 377), wo bereits der globale State exposed wird — die beiden Hooks sollten symmetrisch sein.

---

### Issue S-3: `Action.NoOp` + Reducer-`null`-Return — beide formal "Action war ungültig", aber strukturell zwei Mechanismen

- **Category:** [DRY]
- **Severity:** Important
- **Location:** Spec 1 §4.2 Z. 360-362 (Reducer-`null`-Return) + Spec 2 §3.3 (`Action.NoOp` für nicht-bindende Slots, in Phase-1 referenziert)
- **Description:** Cross-Reference auf Issue 1.1.4 (Phase 1, 🟡). Aus Strukturperspektive ergänzt: das Doppelgleis erzwingt **zwei Handling-Pfade im Orchestrator**:
  - Pfad A: `Action.NoOp` → `findModule` retourniert null → `Log.w("Keine Modul-Zuordnung für NoOp")` (§4.3 Z. 446-448).
  - Pfad B: gültige Action, aber Reducer retourniert null → `Log.w("Action $action ungültig im aktuellen State")` (§4.3 Z. 458-461).

  Beide bedeuten "tut nichts" — aber Pfad A wird als Modul-Zuordnungs-Fehler geloggt, was im Production-Log nach echtem Bug aussieht. Strukturell **fließen zwei semantisch unterschiedliche "ungültig"-Begriffe in dasselbe Logger-Verhalten**, ohne sauberen Trennstrich.
- **Affected codebase files:** Plan-only (Spec 1 + Spec 2).
- **Suggestion:** Section-spezifischer Bezug auf Issue 1.1.4 Option A: nullable-Resolver-Typ im Layout-Slot eliminiert Pfad A komplett. Reducer-`null` bleibt als einzige Ungültigkeitsquelle und kriegt eine eindeutige Semantik: "Action wurde gefeuert, aber im aktuellen Sub-State nicht erlaubt" — ein logisches "weil-Bedingung-nicht-erfüllt", kein Dispatcher-Fehler.

  **Section-1-Konsequenz:** Wenn 1.1.4 Option A gewählt wird, kann §4.3 Z. 445-448 (`val module = findModule(action) ?: …`) auf `error("Action $action ohne Modul-Zuordnung — DictateModuleRegistry-Defekt")` umgestellt werden — das ist dann ein **Bug**, nicht ein erwarteter Pfad. Die Umstellung gehört in dieselbe Spec-Iteration.

---

### Issue S-4: `LayoutModule` aggregiert vier disjunkte Achsen — strukturelle Inkonsistenz in der Sub-State-Hierarchie

- **Category:** [SOLID]
- **Severity:** Important
- **Location:** Spec 1 §3 Z. 87-89 (State-Felder `contentArea` direkt + `layout` als Sub-State) + §15.1 Z. 2246
- **Description:** Cross-Reference auf Issue 1.1.5. Aus reiner Strukturperspektive ergänze ich:

  Die heutige Hierarchie hat eine **Asymmetrie zwischen Top-Level-Feld vs. Sub-State-Klasse**:
  - `state.contentArea: ContentArea` — Direkt-Feld (Enum).
  - `state.layout: LayoutPrefs` — Sub-State-Container.

  In §3 selbst werden andere Enum-Achsen unterschiedlich behandelt:
  - `viewMode: ViewMode` — Top-Level-Enum (✓ analog zu contentArea).
  - `recording: RecordingState` — Top-Level-sealed-class.
  - `pipeline: PipelineUiState` — Top-Level-sealed-class.
  - `audio.bluetoothSco: BluetoothScoPublicState` — verschachtelt zwei Sub-State-Klassen.

  Es gibt **kein erkennbares Schema**, wann eine Achse Top-Level vs. Sub-State-Container wird. Konsequenz: Modul-Owner-Tabelle (§15.1) muss Eigenartigkeiten erklären (LayoutModule = 4 Achsen, aber nur 2 davon sind im `LayoutPrefs`-Sub-State).
- **Affected codebase files:** Plan-only.
- **Suggestion:** Konvention dokumentieren und konsistent anwenden:

  **Vorschlag-Konvention:** Wenn ein Modul **mehrere disjunkte UI-Achsen** verwaltet, dann gehören sie **alle** in einen Sub-State-Container, der nach dem Modul benannt ist. Wenn ein Modul **eine Achse** verwaltet und diese strukturell ein Enum oder eine sealed class ist, kann sie Top-Level bleiben.

  Anwendung auf §3:
  - `LayoutModule` → Issue 1.1.5 Option B (`LayoutState(contentArea, prefs)`) ist mit dieser Konvention konsistent. **Empfehlung 1.1.5 Option B** wird damit aus strukturellen Gründen verstärkt.
  - `viewMode` Top-Level bleibt OK (1 Achse, Enum).
  - `recording` Top-Level bleibt OK (1 Achse, sealed class).

  Konsistenz-Reviewer-Notiz: §15.1 Spalte "Achse" zeigt nach der Korrektur einheitlich "ein Achsen-Slot pro Modul".

---

### Issue S-5: `DictateModule.actionClass: KClass<A>` + `findModule.firstOrNull(isAssignableFrom)` — Action-Hierarchie-Routing ist halb deklarativ, halb dynamisch

- **Category:** [SOLID]
- **Severity:** Nice-to-have
- **Location:** Spec 1 §4.2 Z. 349 + §4.3 Z. 480-485
- **Description:** Das Routing arbeitet zweistufig:
  1. **Schneller Pfad:** `moduleByActionClass[action::class]` — Lookup über exakten KClass-Match (Z. 481).
  2. **Fallback:** `modules.firstOrNull { module -> module.actionClass.java.isAssignableFrom(action::class.java) }` (Z. 482-484).

  Der Fallback existiert wegen der hierarchischen Action-Klassen (Issue 1.0.5: Action.RecordingAction.StartRecording ist eine konkrete sub-class von `Action.RecordingAction`). Der Map-Lookup auf `action::class` würde scheitern, weil im Map-Key nur `Action.RecordingAction::class` (die Modul-Action-Sealed-Klasse) liegt, aber `action::class` ist `StartRecording::class` (eine Tochter).

  Strukturell:
  - **DRY-Smell:** dasselbe Routing-Wissen ist in zwei Mechanismen kodiert (Map + Linear-Scan).
  - **Performance**-Mikro-Issue (nicht in Scope): Linear-Scan im `firstOrNull` schlägt auf jeden Dispatch durch, sobald die Hierarchie genutzt wird (was sie laut §4.2 immer wird). Für 13 Module und ~30-50 Actions/s irrelevant; nenne es zur Vollständigkeit.
- **Affected codebase files:** Plan-only.
- **Suggestion:** Map-Lookup auf der **konkreten Action-Class** unmöglich, weil unbekannt. Aber: die "richtige" Modul-Klasse für eine Action ist die **direkte Sealed-Parent-Klasse**, die `actionClass` deklariert. Für sealed Hierarchien ist das im Compiler bekannt; in Runtime via `action::class.allSuperclasses` (Reflection) auflösbar. Das ist allerdings teurer als `isAssignableFrom`.

  **Pragmatischere Option:** Map deklariert exakt `actionClass.allSubclasses()` als Schlüssel (nur einmal beim Init expanded), dann reiner O(1)-Lookup. Skizze:
  ```kotlin
  private val moduleByActionClass: Map<KClass<*>, DictateModule<*, *, *>> = run {
      modules.flatMap { module ->
          module.actionClass.sealedSubclasses().map { sub -> sub to module }
      }.toMap()
  }
  ```
  Trade-off: setzt voraus, dass die Action-Hierarchien `sealed class` sind (sind sie laut §4.2). Compile-Time-bekannt, R8-robust mit `@Keep`.

  **Severity Nice-to-have**, weil das aktuelle Doppel-Pattern funktional korrekt ist; die Sauberkeit ist marginal.

---

### Issue S-6: `runEffect` rückgabewertfrei, `onCrossModuleStateChange` rückgabewertbasiert — zwei Effect-Pfade, zwei Mechanismen

- **Category:** [SOLID]
- **Severity:** Nice-to-have (LSP / Konsistenz)
- **Location:** Spec 1 §4.2 Z. 369-377 (`runEffect` vs. `onCrossModuleStateChange`)
- **Description:** Ein Modul hat **zwei Wege**, andere Module zu beeinflussen:
  - `runEffect(effect, services)` — direkte Hardware/IO-Calls + ggf. `services.emitAction(...)` (§4.7 Z. 642).
  - `onCrossModuleStateChange(prev, next): List<Action>` — declarative cascade.

  Strukturell sind das **zwei Cross-Module-Modi** (Mode 1 + Mode 2 in §15.5), aber die API legt zusätzlich nahe, dass innerhalb von `runEffect` ein dritter Pfad existiert (`services.emitAction(...)`) — das überlappt semantisch mit Mode 2.

  Ein Modul-Implementierer kann jetzt drei Stilarten wählen:
  1. SideEffect emitten und in `runEffect` Hardware-Call.
  2. SideEffect emitten, in `runEffect` `services.emitAction(...)` aufrufen → Re-Dispatch.
  3. Cross-Module-Observer returnt `List<Action>` → Re-Dispatch.

  Stilarten 2 und 3 sind funktional äquivalent, aber im Code anders verteilt. Das ist ein Teaching-Smell: zwei Implementierer wählen unterschiedliche Stile für denselben Use-Case → Plan-Pattern erodiert.
- **Affected codebase files:** Plan-only.
- **Suggestion:** Stilrichtlinie in §15.5 explizit machen:
  - **Hardware/IO-Konsequenz aus eigenem State-Change** → SideEffect + `runEffect`-Hardware-Call (Mode 1).
  - **Andere Action triggern** (auch Cross-Module) → ausschließlich über `onCrossModuleStateChange`-Return-Value (Mode 2).
  - **`services.emitAction`** → entweder entfernen ODER auf einen einzigen, explizit dokumentierten Edge-Case einschränken (z.B. asynchrone System-Trigger wie BluetoothSco-State-Wechsel, der KEIN Sub-State-Change einer fremden Achse ist).

  Aktuell ist `services.emitAction` (§4.7 Z. 642) ohne Begründung im API-Surface — strukturell eine Hintertür neben den dokumentierten Modi.

---

### Issue S-7: `ModuleServices` ist ein 14-Feld-Container — Module bekommen Hardware, die sie nicht brauchen

- **Category:** [SOLID]
- **Severity:** Nice-to-have (ISP)
- **Location:** Spec 1 §4.7 Z. 628-643
- **Description:** `ModuleServices` ist ein flacher DI-Container mit 14 Feldern. Jedes Modul kriegt das gesamte Container-Objekt in `runEffect`. Z.B.:
  - `RecordingModule.runEffect` (§15.2 Z. 2361-2378) berührt: `recordingHardware`, `recordingTimer`, `amplitudeStream`, `borderGlow` → 4 von 14.
  - `AudioModule.runEffect` (§15.3 Z. 2423-2428) berührt: `audioFocus`, `bluetoothSco` → 2 von 14.
  - `LayoutModule` / `FeatureToggleModule` / `ThemingModule` (laut §15.1: "trivial") berühren vermutlich gar keine Hardware → 0 von 14.

  ISP-strikt gesehen ist das eine flache "God-Container"-API — jedes Modul sieht alles, kann auf alles zugreifen, und Tests müssen den vollen Container faken.
- **Affected codebase files:** Plan-only.
- **Suggestion:** Pragmatisch lassen, aber Konvention dokumentieren: **`ModuleServices` ist als Container OK, weil Module Hardware nur per Subsystem-Interface adressieren** (DIP-Treue per Subsystem, ISP-Verstoß per Container). Ein Test-Fake stuft nur die Subsysteme aus, die das spezifische Modul nutzt; die übrigen können `mockk(relaxed = true)` sein.

  **Alternative (nicht empfohlen wegen Footprint):** Pro Modul ein eigenes Service-Interface (`RecordingModuleServices`, `AudioModuleServices`); der Plan müsste 13 zusätzliche Interfaces deklarieren, die Mehrheit nutzt 0-2 Subsysteme. Overkill.

  **Empfehlung:** `ModuleServices` lassen wie geplant, aber Spec 1 §4.7 mit einem Satz ergänzen: "ISP wird auf Subsystem-Interface-Ebene erfüllt, nicht auf Container-Ebene — Tests faken nur die Subsysteme, die das Modul tatsächlich aufruft." Vermeidet das DOA-Argument bei ersten Code-Reviews.

---

### Issue S-8: §3 Achsen-Übersicht: "15 State-Achsen" Text vs. 14 Tabellen-Zeilen — Numerierungsdrift

- **Category:** [INTEGRATION]
- **Severity:** Nice-to-have (Inkonsistenz, betrifft Lesbarkeit)
- **Location:** Spec 1 §3 Z. 207 ("**15 State-Achsen**, klassifiziert nach Verantwortung") vs. Z. 209-224 (Tabelle mit 14 nummerierten Zeilen, # 1-14)
- **Description:** Header sagt 15, Tabelle hat 14 Zeilen. Außerdem ist die Tabelle in Z. 211 "sealed class RecordingState (4 States: ...)" — inhaltlich saubere Klassifikation, aber Zähler stimmt nicht mit Top-Level-Felder-Count überein:
  - Sub-State-Felder im `DictateUiState` (Z. 79-105): `recording, pipeline, viewMode, contentArea, layout, overlay, audio, resend, livePrompt, language, features, theming, pendingSessions, interruption` = **14**.

  D.h. der Text "15" ist falsch oder eine Achse fehlt in der Tabelle. Wahrscheinlich ist es eine Erbsen-Zähler-Diskrepanz (vermutlich wird `BluetoothScoPublicState` als eigene Achse mitgezählt, obwohl sie ein verschachteltes Detail von `audio` ist).
- **Affected codebase files:** Plan-only.
- **Suggestion:** Zähler vereinheitlichen: "14 State-Achsen (= Sub-State-Felder im `DictateUiState`)". Verschachtelte Sub-States wie `BluetoothScoPublicState` werden als Detail einer Achse beschrieben, nicht als eigene Achse gezählt. Trivial, aber eliminiert eine Quelle der Verwirrung beim ersten Lesen.

---

### Issue S-9: Datei-Layout — `state/` Top-Level + `state/modules/` Sub-Folder; State-Daten-Klassen verteilt vs. zentral?

- **Category:** [INTEGRATION]
- **Severity:** Nice-to-have
- **Location:** Spec 1 §3 (File `state/DictateUiState.kt` mit ALLEN Sub-State-Klassen) vs. §15 (Module in `state/modules/` als jeweils eigene Datei) + §4.2 (`state/DictateModule.kt`)
- **Description:** Aktuelle implizite Datei-Aufteilung:
  ```
  state/
  ├── DictateUiState.kt        ← alle 14 Sub-State-Klassen ({Layout,Overlay,Audio,Resend,LivePrompt,Language,Features,Theming,Interruption,PendingSession}State + Enums)
  ├── DictateModule.kt         ← Plugin-Kontrakt + ModuleId + ReducerContext + TransitionResult
  ├── DictateOrchestrator.kt
  ├── DictateUiStateStore.kt
  ├── PipelinePrefMirror.kt
  ├── PipelineRecovery.kt
  ├── ModuleServices.kt
  ├── DictateModuleRegistry.kt
  └── modules/
      ├── RecordingModule.kt   ← State + Reducer + Effect + EffectHandler
      ├── PipelineModule.kt
      ├── AudioModule.kt
      ├── ...
  ```
  **Strukturelle Frage:** Die Sub-State-Klasse `LayoutPrefs` lebt in `DictateUiState.kt`, aber das `LayoutModule` (das sie verwaltet) lebt in `modules/LayoutModule.kt`. Das ist eine räumliche Trennung von zwei Dingen, die laut §15.1 Header zur Modul-Domäne gehören sollten ("Pro Modul eine eigene Datei mit: State-Sub-Klasse, Effect-Sub-Sealed-Interface, Reducer, EffectHandler, Cross-Module-Observer").

  Es gibt also Konsistenz-Drift zwischen §15-Header und §3-File-Layout-Kommentar.
- **Affected codebase files:**
  - `app/src/main/java/net/devemperor/dictate/keyboard/state/` (existiert: `RecordingState.kt`, `KeyboardUiState.kt` etc. — heutiger Stand zentralisiert State-Sealed-Klassen ähnlich).
- **Suggestion:** Eine der zwei Konventionen wählen und konsistent anwenden:

  **Variante A — Module-Local-State** (entspricht §15-Header):
  Sub-State-Klasse pro Modul in der Modul-Datei. `DictateUiState.kt` enthält nur die `data class DictateUiState(...)`-Klammer und importiert die Sub-State-Klassen aus den Modul-Dateien. Konsistent zu Excel-EKL-Augmentation-Pattern.

  Trade-off: zyklische Datei-Abhängigkeiten möglich — `DictateUiState` importiert aus `modules/`, `modules/RecordingModule` importiert `DictateUiState`. Kotlin akzeptiert das, aber es ist ein Code-Smell, den manche Coder vermeiden möchten.

  **Variante B — Central-State** (status quo Plan):
  Alle Sub-State-Klassen in `DictateUiState.kt`. §15-Header anpassen: "Pro Modul: Reducer + Effect + EffectHandler. State-Klasse lebt in `DictateUiState.kt`."

  **Empfehlung Variante B**: minimaler Bruch zur heutigen Code-Konvention (`KeyboardUiState.kt` bündelt Sealed-Klassen heute auch zentral), und Vermeidet zyklische Imports. §15-Header Z. 2232-2236 entsprechend präzisieren.

---

### Issue S-10: §15.5 Cross-Module-Effect-Modi 1+2+3 dokumentiert, nur 1+2 implementiert — Halb-Pattern (Cross-Reference auf 1.1.3)

- **Category:** [INTEGRATION]
- **Severity:** Important
- **Location:** Spec 1 §15.5 Z. 2470-2480 + §4.3 Z. 442-478
- **Description:** Cross-Reference auf Issue 1.1.3 (Phase 1, 🟡). Aus Strukturperspektive ergänzend:

  **Strukturelle Konsequenz** des Halb-Patterns: §15.5 ist als API-Vertragsdokumentation für Modul-Implementierer gedacht (siehe §15.4 "Hinzufügen eines neuen Moduls"). Wenn dort Modus 3 als legitime Option aufgeführt ist, aber im Orchestrator-Code nicht existiert, wird der erste Modul-Implementierer, der Mode 3 wählt, **erst beim Run-Time merken**, dass es nicht funktioniert. Das ist eine reine Dokumentations-Drift gegen den Code.

  Die Sub-Sektion §15.6 SOLID-Verifikation ("OCP — Neues Modul = neue Datei") ist mit Mode 3 nicht konsistent, weil Mode 3 zentralen Orchestrator-Code anfassen müsste.
- **Affected codebase files:** Plan-only.
- **Suggestion:** Section-spezifisch: 1.1.3 Option B übernehmen (Mode 3 in §15.5 als "Phase-2 — nicht eingebaut" markieren) UND in §15.6 SOLID-Verifikation eine Zeile ergänzen: "OCP gilt für Modi 1+2; Mode 3 würde OCP gegen den Orchestrator brechen — daher bewusst nicht eingebaut bis konkreter Bedarf." Macht die Architektur-Entscheidung explizit zum Pattern statt zur Lücke.

---

### Issue S-11: `LocalBinder` exposed `notifyImeViewShown/Hidden` als typed Methods — F-8 "Single Dispatch" wird zu "Single Dispatch + 2 Lifecycle-Methods"

- **Category:** [SOLID]
- **Severity:** Nice-to-have (DRY / OCP-Drift)
- **Location:** Spec 1 §5 Z. 740-751
- **Description:** F-8-Architektur-Korrektur (§4 Z. 259-263) sagt: "LocalBinder schrumpft auf `state` + `dispatch` + Lifecycle-Hooks." Code in §5 zeigt:
  ```kotlin
  fun dispatch(action: Action) = orchestrator.dispatch(action)
  fun notifyImeViewShown() = dispatch(Action.ViewModeAction.OnImeViewShown)
  fun notifyImeViewHidden() = dispatch(Action.ViewModeAction.OnImeViewHidden)
  ```
  Die Lifecycle-Hooks sind **Wrapper über `dispatch()`** — wozu typed Forwarder, die direkt eine Action 1:1 weiterleiten? Das ist die **alte Drift-Falle** (§4 F-8-Begründung Z. 720-722: "LocalBinder mit ~25 typed Action-Methoden, parallel zu einer `Action`-Sealed-Class mit denselben Varianten — Doppel-Definition").

  Wenn die Begründung "weil sie semantisch Lifecycle-Events sind, keine User-Intentionen" tragen soll, dann müsste der Vertrag **getrennt** gesichert sein — z.B. eine separate `IDictateLifecycle`-Schnittstelle, die _nicht_ über Action läuft. Aktuell fließen Lifecycle-Events durch denselben Action-Bus → der semantische Trennungs-Argument hat keinen strukturellen Niederschlag.
- **Affected codebase files:** Plan-only.
- **Suggestion:** Eine von zwei Optionen:

  **Variante A** (konsequent F-8): Die Wrapper entfernen. IME-Service ruft `pipeline?.dispatch(Action.ViewModeAction.OnImeViewShown)` direkt — keine versteckte Action-Konstanz im Binder. Spec 1 §5 schrumpft auf `state` + `dispatch`, alle drei Konsumenten gehen denselben Weg.

  **Variante B** (Lifecycle als eigenständige Schnittstelle): Wenn Lifecycle wirklich semantisch separat sein soll, dann **nicht** als Action verschalten, sondern als eigenständigen Hook in den Orchestrator/ViewModeModule routen. Dann ist die F-8-Begründung in der Doku auch wahr.

  **Empfehlung Variante A**: kleinerer Footprint, konsistent mit F-8-Geist. Lifecycle-Events sind in dieser Architektur ohnehin "nur" Trigger-Actions; ein Wrapper, der dasselbe macht wie ein direkter `dispatch`, ist DRY-Smell.

---

## Summary Table

| # | Category | Severity | Issue | Description |
|---|---|---|---|---|
| S-1 | [DRY] | Important | `PipelinePrefMirror.sync()` 19 fast-identische `when`-Branches + Doppel-Definition mit `initialMirror` | Pref-Mapping-Wissen lebt zweimal; OCP-Bruch beim Modul-Hinzufügen |
| S-2 | [SOLID] | Important | `ReducerContext` exposed nur `audio` + `recordingAudioFile` — andere Module brauchen `language`, `pipeline`, etc. | OCP/ISP-Drift; jede neue Cross-Achsen-Bedingung erfordert zentrale `ReducerContext`-Änderung |
| S-3 | [DRY] | Important | `Action.NoOp` + Reducer-`null`-Return: zwei Mechanismen für "Action ignoriert" + zwei Logging-Zeilen | Section-Bezug auf Issue 1.1.4; betrifft §4.3 Z. 446-461 |
| S-4 | [SOLID] | Important | `LayoutModule` 4 disjunkte Achsen + Asymmetrie `contentArea` Top-Level vs. `layout` Sub-State | Section-Bezug auf Issue 1.1.5; strukturelle Konvention für Sub-State vs. Top-Level fehlt |
| S-5 | [SOLID] | Nice-to-have | Action-Routing via Map + Linear-Scan-Fallback — halb deklarativ, halb dynamisch | `findModule` (§4.3 Z. 480-485) doppelter Pfad; `sealedSubclasses()` würde Map vollständig machen |
| S-6 | [SOLID] | Nice-to-have | `runEffect` (Mode 1), `services.emitAction` (Mode 2'), `onCrossModuleStateChange` (Mode 2) — drei Cascade-Stile, einer un-dokumentiert | §15.5 listet 3 Modi, der `services.emitAction`-Pfad ist ein vierter, nicht-dokumentierter |
| S-7 | [SOLID] | Nice-to-have | `ModuleServices` 14-Feld-God-Container in `runEffect` aller Module | ISP-Verstoß auf Container-Ebene, vertretbar — Konvention dokumentieren |
| S-8 | [INTEGRATION] | Nice-to-have | "15 State-Achsen" Header vs. 14 Tabellenzeilen | Numerierungsdrift; Sub-State-Verschachtelung uneindeutig gezählt |
| S-9 | [INTEGRATION] | Nice-to-have | Sub-State-Klassen in `DictateUiState.kt` zentral, Module in `modules/`-Sub-Folder verteilt — §15-Header sagt "pro Modul eine eigene Datei mit: State-Sub-Klasse" | File-Layout-Konvention vs. Code-Snippets nicht konsistent |
| S-10 | [INTEGRATION] | Important | §15.5 Modus 3 dokumentiert, im Orchestrator-Code nicht implementiert — Halb-Pattern | Section-Bezug auf 1.1.3; §15.6 SOLID-Verifikation müsste Mode 3 explizit ausnehmen |
| S-11 | [SOLID] | Nice-to-have | `LocalBinder.notifyImeViewShown/Hidden` 1:1-Wrapper über `dispatch()` — DRY-Drift gegen F-8 | §5 Z. 740-751; entweder konsequent dispatch-only ODER Lifecycle als eigene Schnittstelle |

---

## Recommendations für den Apply-Step

1. **Wichtigste 4 Issues** für Section 1: S-1 (PrefMirror-DRY), S-2 (ReducerContext-ISP/OCP), S-4 (LayoutModule-SRP, zusammen mit 1.1.5), S-10 (Mode-3-Halbpattern, zusammen mit 1.1.3).
2. **Auto-Fixable (🟢)**: S-8 (Numerierungs-Drift), S-9 (File-Layout-Konvention, Doku-Update auf Variante B), S-11 (Wrapper entfernen — Variante A) — alle ohne Architektur-Entscheidung.
3. **Architektur-Entscheidungen (🟡)** mit User-Bestätigung: S-1 (Pref-Bindings im Modul vs. Map-Lookup im Mirror), S-2 (Option A `global: DictateUiState` vs. Option B per-Modul-Context), S-6 (`services.emitAction` entfernen oder dokumentieren), S-7 (Container-API-Konvention dokumentieren).
4. **Section-Cross-Refs**: S-3 ↔ Phase-1 1.1.4, S-4 ↔ 1.1.5, S-10 ↔ 1.1.3 — beim Apply-Step kombiniert behandeln, weil die Lösungen miteinander interagieren.

Logische Lücken (Reducer-Pure-Verletzung 1.1.8, Cross-Module-Cascade-Loop 1.1.7, Spec-3-direkt-Mutationen 1.1.2) sind bewusst NICHT hier dupliziert — Logic-Reviewer-Domain.
