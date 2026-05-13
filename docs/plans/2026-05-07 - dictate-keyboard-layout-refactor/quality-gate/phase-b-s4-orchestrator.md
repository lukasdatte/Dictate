# Phase B — S-4 Pipeline-Orchestrierung: Verteilte Controller → DictateOrchestrator + DictateModule-Plugin-Pattern Migrations-Pfad-Review

**Erstellt:** 2026-05-13
**Reviewer:** Phase-B-Agent S-4 (Subsystem #4 von 9)
**Plan-Version vor Edits:** Stand nach S-3-Apply-Pass (Commit `af0bd00`, S-3-Report `phase-b-s3-action-hierarchy.md`)

---

## Summary

Der Migrationspfad für S-4 ist **architektonisch tragfähig** — `DictateOrchestrator` als schlanke Composition Root + `DictateModule<S, A, E>`-Plugin-Kontrakt + 13 Module + frozen-snapshot-Cascade decken die heutige God-Class-Verteilung (RecordingStateController + KeyboardUiController + KeyboardStateManager + DictateInputMethodService) sauber ab. Sealed-Leaves-Indexing (R.4), Reentrancy-Vertrag (Issue 2.1.4 Option A), MAX_CASCADE_DEPTH (R.6), und die Self-Cascade-Erlaubnis (KG-RSB-2 Auflösung A) bilden zusammen den MVI-Stack korrekt ab.

**Kritisch geblieben: zwei strukturelle Lücken plus zwei nicht-spezifizierte Garantien, die als Time-Bombs Production-Bugs erzeugen würden:**

1. **`KClass.sealedSubclasses`-Reflection ohne ProGuard-Keep-Regel.** Der Plan hat eine PENDING-Markierung (Hauptplan §7.2 Z. 283) und die Open-Question-Aussage "manuelle Liste mit Init-Check, Reflection als optionales Upgrade" (§9 F-11 Z. 556), aber der **aktuelle Code in §4.3 `collectLeaves` VERWENDET BEREITS Reflection** (`c.sealedSubclasses.flatMap`). Die Plan-Aussage "wir wählen die explizite Liste, weil sie debug-freundlicher und R8-/ProGuard-robust ist" (§4.8 Z. 1049) ist nur halb wahr: die Modul-Registry ist manuell, aber das Action-Leaves-Indexing nutzt Reflection. ProGuard-Default-Behavior ist `sealedSubclasses` zu strippen → leere Leaf-Map → **ALLE Actions wären `Unrouted`** in Release-Builds. Das ist exakt die selbe Bug-Klasse wie S-3 F-1 + F-2, nur jetzt für alle 14 sealed Action-Subtypen statt nur EffectFailure + KeyboardInputAction. Im Release-Build hätte der erste Click auf einen Record-Button silent-no-op gemacht.

2. **Init-Sanity-Check fängt nur Doppel-Routing, nicht Fehlende Routing.** S-3 hat das als expliziten Follow-Up parkiert (S-3-Report §F-7). Der aktuelle Check `require(actionClasses.toSet().size == actionClasses.size)` (§4.8 Z. 1042) hätte S-3-F-1 (EffectFailure) und S-3-F-2 (KeyboardInputAction) NICHT gefangen, weil beide Actions schlicht in keiner Modul-`actionClass`-Deklaration aufgetaucht wären. Die Lehre aus S-3 ist nicht eingearbeitet. Future Action-Subtypes (z.B. Aktivierung des `InterruptionModule`-Action-Routings in Phase 2) wären wieder still-dropped. Sanity-Check muss um Vollständigkeits-Check erweitert werden.

3. **`Effect.AllocateMediaRecorder`-Signatur-Drift in §15.2.** Z. 5456 deklariert `data class AllocateMediaRecorder(val target: InsertionTarget, val useBluetooth: Boolean)` mit 2 Args, aber Z. 5492 (Reducer-Body) ruft `Effect.AllocateMediaRecorder(action.target, ctx.global.audio.useBluetoothMic, action.audioFile)` mit 3 Args. Compile-Fehler beim ersten `./gradlew assembleDebug`. Plus: Z. 5565 `runEffect` ruft `services.recordingHardware.allocate(effect.target, effect.useBluetooth)` — kein `audioFile`-Arg, also wandert das File-Argument nirgendwo hin. R.2-Vertrag "audioFile lebt im State" ist konzeptuell richtig, aber das Effect-Argument ist im Plan inkonsistent.

4. **AudioModule.onCrossModuleStateChange enthält Dead-Code-Block.** §15.3 Z. 5857-5861 zeigt einen `if (prev.recording is Idle && next.recording is Preparing) { ... }`-Block, dessen Body NUR Kommentare enthält ("Effects werden im Recording-Modul ausgelöst, aber Audio-Effects passieren durch direktes runEffect — alternativer Pfad: via emitAction(...)"). Kein `cascade.add(...)`. Das ist entweder: (a) vergessener Code, oder (b) "exemplarischer" Pseudo-Code im Plan, der einem Implementer als Vorlage dient — und der ihn falsch macht (weil der Kommentar gleichzeitig den falschen Pfad "direkter Hardware-Call im Observer" beschreibt, der gegen Pure-Function spricht). Drift gegen die Coupling-Matrix-Aussage Zelle "Audio × Recording = C(RecordingAction.PauseRecording)" wo das AudioFocus-Loss-Cascade richtig steht.

5. **Cascade-Order über 13 Module ist nicht garantiert.** §4.3 Step 5-6 cascadet `modules.flatMap { it.onCrossModuleStateChange(prevGlobal, nextGlobal) }.forEach { dispatchInternal(it, depth+1) }`. Frozen-snapshot garantiert dass alle 13 Observer denselben `(prev, next)` sehen — gut. ABER: die rekursiven `dispatchInternal(cascadeAction, depth+1)`-Calls werden **in der `cascadeActions.forEach`-Reihenfolge** ausgeführt, also in der Reihenfolge von `DictateModuleRegistry.all`. Jeder rekursive Aufruf macht einen FRISCHEN `prevGlobal`/`nextGlobal`-Snapshot. **Wenn zwei Module unabhängige Cascades emittieren, sieht die zweite Cascade-Action einen State, der bereits die erste Cascade-Mutation enthält.** Reihenfolge der Module in `DictateModuleRegistry.all` (§4.8 Z. 1017-1033) determiniert damit die Cascade-Ordnung — und ein versehentliches Refactor-Reorder (z.B. Alphabetisierung beim Code-Cleanup) ändert observable Semantik. Keine Plan-Klausel "Reihenfolge fixiert dokumentiert" oder "Reihenfolge irrelevant weil disjunkt"; keine Acceptance-Test gegen Reorder.

6. **`prefBindings()`-Interface-API ohne Konsumenten.** `DictateModule.prefBindings()` (§4.2 Z. 462) ist explizit als Pref-Mirror-Hook spezifiziert ("`PipelinePrefMirror` verwendet diese Liste, um Initial-Read und OnSharedPreferenceChangeListener generisch zu bauen"). Aber §4.5 `PipelinePrefMirror.initialMirror` + `sync` (Z. 864-924) verwendet 19 **hardcodierte** Pref-Mappings, keine einzige Konsumption von `module.prefBindings()`. Hauptplan §7.1 Out-of-Scope listet "`prefBindings()`-Migration aller 13 Module" als Phase-2-Backlog. Folge: das Interface-Hook ist **Phase-1-Dead-Code** — Module deklarieren `prefBindings()` (default `emptyList()`), und niemand liest sie. Ein Implementer könnte beim RecordingModule den `prefBindings()`-Hook implementieren und annehmen "der wird automatisch gerufen", was nicht der Fall ist. Klarstellung im Plan-Body Pflicht.

7. **`PipelineOrchestrator` vs. `DictateOrchestrator` Naming-Konflikt nicht prominent dokumentiert (Surprise-Finding #2).** Der alte `core/PipelineOrchestrator.kt` (1383 Zeilen, Audio-Pipeline-Runner) bleibt nach Refactor erhalten und wird im PipelineService gehalten (§8 Z. 3586, §13.5 G7). Der neue `state/DictateOrchestrator.kt` kommt hinzu. Zwei Klassen mit "Orchestrator" im Namen verwirren beim Lesen. Plan hat das im Migrations-Block §8 als Tabellen-Zeile aber keinen dedizierten Disambiguier-Block im Plan-Body. Risiko: Implementer mischt Verantwortlichkeiten ("ich brauch nen Pipeline-Step durchführen, lass mich den Orchestrator rufen") oder Code-Reviewer findet das verwirrend.

**Eine Detail-Klärung:**

8. **`shutdown()`-vs-`serviceScope.cancel()`-Reihenfolge nicht klar.** §4.3 `shutdown()` KDoc dokumentiert die Sequenz (`prefMirror.detach()` → `modules.forEach { terminate }` → "Service.onDestroy ruft anschließend `serviceScope.cancel()`"). Aber das ist eine Vertrags-Aussage zwischen zwei Klassen, kein erzwungenes Pattern. §7.3 onDestroy (Z. 3456-3459) macht es korrekt (`shutdown()` synchron vor `serviceScope.cancel()`). Acceptance-Test fehlt — bei Refactor könnte jemand `serviceScope.cancel()` vor `shutdown()` setzen, und die Module-`terminate()`-Hardware-Calls liefen auf einem gecancellten Scope → entweder no-op oder Exception. Production-Bug-Klasse "MediaRecorder.release im Native-Heap niemals gerufen".

**Befund:** **9 Findings (3 Critical, 5 Important, 1 Minor) — 18 Plan-Edits in 2 Dateien (Spec 1: 15, Hauptplan: 3).** Spec 2 + Spec 3 unberührt — S-4 ist Spec-1-Scope (DictateOrchestrator + DictateModule sind dort kanonisch definiert; die Spec-2/3-Konsumenten lesen aber unverändert weiter).

---

## Findings + Applied Fixes

### F-1 `KClass.sealedSubclasses`-Reflection ohne ProGuard-Keep-Regel

- **Severity:** Critical
- **Prüf-Achse:** 1 (Init-Sanity-Check), 7 (Reflection-vs-Manuelle-Registry), 8 (Bugs durch Migration)
- **Was:** Spec 1 §4.3 Z. 587-589 (vor Fix) implementiert `collectLeaves(c)` mit `c.sealedSubclasses.flatMap { collectLeaves(it) }` — also tatsächlich Reflection-basierter Sealed-Subclass-Traversal. Plan-Aussage in §4.8 Z. 1049 ("wir wählen die explizite Liste, weil sie debug-freundlicher und R8-/ProGuard-robust ist") bezieht sich NUR auf die Modul-Liste (`DictateModuleRegistry.all`), nicht auf das Action-Leaves-Indexing. Hauptplan §7.2 PENDING-Marker Z. 283 erkennt das Problem an ("braucht Implementer-Konkretisierung der `KClass.sealedSubclasses`-Reflection"), aber keine ProGuard-Klausel im Plan-Body. Verifizierungsschritt: `app/proguard-rules.pro` heute existiert (Standard-Android-Setup), aber kein Plan-Patch dafür. Default-ProGuard-Behavior in R8: alle nicht-explizit-gehaltenen Klassen werden weg-optimiert, `KClass.sealedSubclasses` returnt leere Liste, weil ProGuard die Hierarchie nicht beibehält.
- **Konsequenz:** Im Release-Build (`./gradlew assembleRelease`) wäre `moduleByLeafClass` eine leere Map — `moduleByLeafClass[Action.RecordingAction.StartRecording::class]` → `null` → `DispatchOutcome.Unrouted(action)` → silent-drop für ALLE Actions. Erster Click auf einen Record-Button im Release-Build: nichts passiert. Selbe Bug-Klasse wie S-3 F-1/F-2, aber kataklysmisch — nicht nur ein Modul betroffen, sondern alle 14. Bug wäre erst im internal Release-Test fühlbar, weil Debug-Builds (ohne ProGuard) korrekt funktionieren. Plus: ProGuard-Regel ist Pflicht-Acceptance, nicht "Implementer-Decision" — keine sinnvolle Alternative.
- **Fix angewandt:**
  - **Spec 1 §4.3:** Hinweis-Block "ProGuard/R8-Keep-Regel ist Pflicht" direkt nach dem `collectLeaves`-Snippet eingefügt — mit konkretem ProGuard-Snippet (`-keep,allowobfuscation class * extends net.devemperor.dictate.state.Action { *; }` + `-keepclassmembers class kotlin.reflect.** { *; }`) und Begründung.
  - **Spec 1 §4.8:** Klärungs-Block am Ende von `DictateModuleRegistry`-Sektion eingefügt: "Manuelle Modul-Liste vs. Reflection-basierte Action-Leaves — zwei verschiedene Reflection-Entscheidungen" mit expliziter Tabelle (Klasse-Hierarchie: Modul-Registry / Action-Leaves; Mechanismus: manuelle Liste / `sealedSubclasses`; R8-Risk: nein / **JA** mit Mitigation).
  - **Spec 1 §10 Block-1b-Acceptance:** neue Klausel "Phase-B S-4 ProGuard-Robustheit" — `./gradlew assembleRelease && adb install` Smoke-Test: nach Install eine `Action.RecordingAction.StartRecording` dispatch und assert `DispatchOutcome.Applied` (nicht `Unrouted`). Test-Datei `OrchestratorReleaseSmokeTest.kt` (instrumented, optional, aber Acceptance-Pflicht).
  - **Hauptplan §7.2:** PENDING-Marker Z. 283 wird auf "RESOLVED in Phase-B S-4: ProGuard-Keep-Regel in Spec 1 §4.3 dokumentiert" aktualisiert.

### F-2 Init-Sanity-Check fängt nur Doppel-Routing, nicht Fehlende Routing (S-3 Follow-Up)

- **Severity:** Critical
- **Prüf-Achse:** 1 (Init-Sanity-Check-Vollständigkeit)
- **Was:** Spec 1 §4.8 Z. 1041-1044 (vor Fix) definiert nur zwei Init-Checks: (a) `ids.toSet().size == ids.size` (Doppel-ModuleIds), (b) `actionClasses.toSet().size == actionClasses.size` (Doppel-Routing). S-3-Report §F-7 hat dies explizit als Follow-Up für S-4 markiert: "F-1 + F-2 wären nicht aufgefangen worden, weil EffectFailure + KeyboardInputAction einfach in keiner Liste auftauchen." Der vorhandene Check ist DI-Container-Standard für "ambiguity", aber NICHT für "unrouted". Plus: die `Action`-Top-Level-Hierarchie hat heute (post-S-3-Fix) 14 direkte Subtypes (13 Modul-Action-Sealed-Klassen + `EffectFailure` als Special-Case). Wenn der Implementer einen neuen Top-Level-Subtyp einführt (z.B. Interruption-Modul-Aktivierung in Phase 2), würde der Sanity-Check schweigen.
- **Konsequenz:** Bug-Klasse "Silent action drop" wiederholt sich. Konkretes Szenario: Phase-2-Implementer aktiviert das `InterruptionModule` (heute auskommentiert in Z. 1032), aber vergisst, das `KClass<Action.InterruptionAction>` in der Modul-Definition zu setzen oder verwendet einen Tippfehler — und die Tests laufen grün (kein Doppel-Routing), aber der Anruf-Cancel-Cascade funktioniert nicht. Class-of-bugs, die S-3 schon im Plan hatte. Vollständigkeits-Check, der die Hierarchie scannt, würde es fangen.
- **Fix angewandt:**
  - **Spec 1 §4.8:** `DictateModuleRegistry`-Init-Block erweitert um einen dritten Check: Vollständigkeits-Check via `Action::class.sealedSubclasses` + Excludelist für Special-Case-Subtypes (`Action.EffectFailure`, weil das via `originModuleId` geroutet wird, nicht via `actionClass`). Concrete-Check: für jede direkte sealed-Subclass von `Action::class`, die NICHT in der Exclude-Liste ist, muss ein Modul in `all` mit `actionClass == it` existieren. Failure: Init-Time-Exception mit konkreter Liste der fehlenden Routing-Einträge.
  - **Spec 1 §4.8:** ProGuard-Hinweis: der Vollständigkeits-Check braucht ebenfalls die ProGuard-Keep-Regel aus F-1 (sonst leere `Action::class.sealedSubclasses` → false-positive "alle fehlen" beim ersten Release-Build). Cross-Link auf F-1.
  - **Spec 1 §10 Block-1b-Acceptance:** neue Klausel "Phase-B S-4 Vollständigkeits-Check": ein gezielter Test entfernt das `KeyboardInputModule` aus `DictateModuleRegistry.all` und erwartet einen Init-Failure mit "Missing routing for Action.KeyboardInputAction" — verifiziert dass der Check greift. Test-Datei `DictateModuleRegistryTest.kt`.

### F-3 `Effect.AllocateMediaRecorder`-Signatur-Drift (§15.2)

- **Severity:** Critical
- **Prüf-Achse:** 3 (Pure-Reducer-Invariante), 5 (Modul-Vollständigkeit), 8 (Bugs durch Migration)
- **Was:** Spec 1 §15.2 Z. 5456 (vor Fix): `data class AllocateMediaRecorder(val target: InsertionTarget, val useBluetooth: Boolean) : Effect` — 2 Felder. Aber Z. 5492 (Reducer-Body) ruft den Konstruktor mit 3 Argumenten: `Effect.AllocateMediaRecorder(action.target, ctx.global.audio.useBluetoothMic, action.audioFile)`. Plus: Z. 5565 (runEffect-Body) ruft `services.recordingHardware.allocate(effect.target, effect.useBluetooth)` — 2 Argumente, kein `audioFile`. Drei verschiedene Aussagen über dasselbe Effect-Schema im selben Modul. R.2-Vertrag in §15.2 Z. 5559-5562 sagt explizit "audioFile lebt im RecordingState" und "Allocator-Effect bekommt das File-Objekt von außen (Caller, z.B. PipelineRunner oder LocalBinder.startSession)" — also: das `audioFile`-Argument SOLL im Effect leben, weil RecordingHardwareSubsystem.allocate(target, useBluetooth, audioFile) den Filename braucht.
- **Konsequenz:** Bei erster Kompilierung von `RecordingModule.kt` brennt der Compiler ("expected 2 arguments, got 3"). 5-Minuten-Fix für den Implementer, ABER: ohne klare Plan-Vorlage müsste er raten, ob: (a) das `audioFile` einen Effect-Konstruktor-Slot bekommen soll (R.2-Konform: das File-Objekt wandert als Daten durch den Action-Reducer-Effect-Pfad — also JA, drittes Feld in Effect), oder (b) das `recordingHardware.allocate(...)`-Interface signature den File-Arg braucht (auch JA — siehe §4.7 Z. 952 `recordingHardware: RecordingHardwareSubsystem` ohne explizite Signatur, aber R.2 verlangt eine 3-arg-Variante). Implementer-Decision-Confusion + Drift-Risk: Plan §4.7 `RecordingHardwareSubsystem.allocate` Signature ist nicht definiert; §4.9 listet sie nicht. So ist die Pure-Function-Garantie (R.2 — `audioFile` durch den Reducer geschoben statt ctx-Hardware-Read) im Spec-Code zwar konzeptuell, aber im Effect-Argument verstreut + inkonsistent.
- **Fix angewandt:**
  - **Spec 1 §15.2:** `Effect.AllocateMediaRecorder`-Definition Z. 5456 auf 3 Felder erweitert: `data class AllocateMediaRecorder(val target: InsertionTarget, val useBluetooth: Boolean, val audioFile: File) : Effect`. FIX-Kommentar verweist auf R.2 + S-4 Phase-B-Audit.
  - **Spec 1 §15.2:** `runEffect`-Body Z. 5565 auf `services.recordingHardware.allocate(effect.target, effect.useBluetooth, effect.audioFile)` umgestellt.
  - **Spec 1 §4.7:** `RecordingHardwareSubsystem`-Interface ist nicht direkt im Plan definiert; Hinweis im KDoc des `recordingHardware`-Feldes ergänzt: "Erwartete Signatur: `fun allocate(target: InsertionTarget, useBluetooth: Boolean, audioFile: File)` — der `audioFile`-Pfad lebt im State (R.2), Hardware-Subsystem erhält ihn als Effect-Argument."
  - **Spec 1 §15.2:** der `audioFile-Vertrag (R.2)`-Block (Z. 5559-5562) um den expliziten Hinweis erweitert: "Effect.AllocateMediaRecorder trägt das audioFile als 3. Konstruktor-Argument (siehe Z. 5456 Definition + Z. 5492 Reducer-Use + Z. 5565 EffectHandler-Use — drei konsistente Sites)."

### F-4 AudioModule.onCrossModuleStateChange Dead-Code-Block (§15.3)

- **Severity:** Important
- **Prüf-Achse:** 3 (Pure-Reducer-Invariante / Cascade-Mechanik), 5 (Modul-Vollständigkeit)
- **Was:** Spec 1 §15.3 Z. 5857-5861 (vor Fix) zeigt:
  ```kotlin
  if (prev.recording is RecordingState.Idle && next.recording is RecordingState.Preparing) {
      // Effects werden im Recording-Modul ausgelöst, aber Audio-Effects passieren
      // durch direktes runEffect — alternativer Pfad: via emitAction(Action.X) eine
      // spezifische Audio-Action einleiten, die hier wieder reduziert wird.
  }
  ```
  Der Body ist leer — nur Kommentare, kein `cascade.add(...)`. Das ist entweder vergessener Code oder ein Implementer-Anti-Pattern-Vorlage ("hier könntest du Direct-Hardware-Calls machen"). Plus: die Coupling-Matrix-Zelle `Audio × Recording` (§15.1.x Z. 5389) sagt `R(state.audio.audioFocusGranted) C(RecordingAction.PauseRecording)` — der Cascade ist AudioFocus-Loss → Recording.Pause, NICHT Recording-Preparing → AudioFocus-Request. Letzteres sollte als Effect IM RecordingModule emittiert werden (z.B. via Cross-Module-Cascade aus RecordingModule.onCrossModuleStateChange — analog zum ResetSuppressBit-Pattern).
- **Konsequenz:** Ein Implementer könnte den Dead-Code-Block als TODO-Marker interpretieren und ihn mit `services.audioFocus.request()` als Direct-Call füllen — was die Pure-Function-Garantie für den Observer bricht (Cross-Module-Observer darf NUR Actions emittieren, kein Direct-Effect, F1+F2 Geist plus §15.5 Mode-2-Vertrag). Konsequenz: Observer-Side-Effects sind nicht-deterministisch (Hardware-Call läuft je nach Modul-Reihenfolge in `modules.flatMap`, Tests sind nicht reproduzierbar). Plus: der Plan-Kommentar erwähnt `emitAction(Action.X)` als "alternativer Pfad" — das ist aber nicht "alternativ", sondern **der einzig zulässige** Pfad nach Mode 2.
- **Fix angewandt:**
  - **Spec 1 §15.3:** Dead-Code-Block ersetzt durch einen expliziten Cascade-add für AudioFocus-Request, ODER (saubere Alternative) ersatzlos entfernt mit erklärendem Kommentar "AudioFocus-Request läuft als Effect im RecordingModule beim Preparing-Übergang — KEIN Cross-Module-Cascade nötig". Variante 2 gewählt, weil Recording.runEffect bereits den AudioFocus-Pfad via `Effect.AllocateMediaRecorder` triggert (RecordingHardwareSubsystem kapselt das AudioFocus-Wiring im prepare()-Pfad). FIX-Kommentar dokumentiert die Begründung + verweist auf S-4 Phase-B-Audit.
  - **Spec 1 §15.3:** Top-of-Block-KDoc um expliziten Vermerk erweitert: "Cross-Module-Observer darf NUR Actions emittieren — KEINE Direct-Hardware-Calls (`services.X.Y()`) im Body. Pure-Function-Garantie. Mode 2 strikt (siehe §15.5)."

### F-5 Cascade-Reihenfolge der 13 Module nicht spezifiziert

- **Severity:** Important
- **Prüf-Achse:** 2 (Cascade-Mechanik-Korrektheit), 8 (Module-Reorder-Risk)
- **Was:** Spec 1 §4.3 Step 5-6 (Z. 689-693) implementiert die Cascade als:
  ```kotlin
  val cascadeActions = modules.flatMap { it.onCrossModuleStateChange(prevGlobal, nextGlobal) }
  cascadeActions.forEach { dispatchInternal(it, depth + 1) }
  ```
  Die `modules`-Iteration läuft in der Reihenfolge von `DictateModuleRegistry.all` (§4.8 Z. 1017-1033). Jeder rekursive `dispatchInternal`-Aufruf erfasst seinen eigenen `prevGlobal`/`nextGlobal`-Snapshot innerhalb von `dispatchInternal` (Z. 641 + 688). **Folge:** wenn AudioModule die Cascade-Action `RecordingAction.PauseRecording` emittiert UND OverlayModule die Cascade-Action `ViewModeAction.SetViewMode(WIDGET)` emittiert (beide reagieren auf denselben Recording-Active→Idle-Übergang), dann läuft Cascade-A zuerst (AudioModule ist Modul #3 in der Liste), mutiert `state.recording`, und Cascade-B (OverlayModule, #5 in der Liste) sieht beim eigenen `dispatchInternal`-Aufruf einen **bereits mutierten** State. Reihenfolge der Module in `all` (§4.8) determiniert observable Semantik. Keine Plan-Klausel garantiert "Reihenfolge fixiert/irrelevant"; ein Code-Cleanup, der die Liste alphabetisch sortiert, würde stille Verhaltens-Änderungen erzeugen. Plus: kein Acceptance-Test gegen Reorder.
- **Konsequenz:** Class-of-bugs "non-deterministic cascade timing". Konkretes Szenario: AudioModule's `AudioFocus-Loss → RecordingAction.PauseRecording`-Cascade läuft, mutiert `state.recording` zu Paused. Ein anderes Modul (z.B. PendingSessionsModule) hatte denselben Active→Idle-Übergang observiert und wollte `state.recording is Active`-Property abfragen — sieht jetzt `Paused`. Plus: bei Konstruktion neuer Cross-Module-Cascades (Phase-2) muss ein Implementer die Modul-Reihenfolge in `DictateModuleRegistry.all` kennen, ohne dass das in der Klassendoku steht. Wartbarkeit-Killer.
- **Fix angewandt:**
  - **Spec 1 §4.3:** Direkt nach der `cascadeActions.forEach`-Zeile (Z. 693) ein neuer Hinweis-Block "Cascade-Order-Vertrag (Phase-B S-4)" eingefügt: Aussage "Reihenfolge der Cascade-Actions ist deterministisch = `DictateModuleRegistry.all`-Reihenfolge. Frozen-snapshot in jedem rekursiven `dispatchInternal` (depth+1) bedeutet: jeder Cascade-Call sieht den State **inklusive** vorheriger Cascade-Mutationen aus diesem Pass." Plus: explicit "**Konvention:** Cross-Module-Cascades sollen disjunkte State-Achsen mutieren — ein Modul soll nicht in seine Cascade einplanen, dass ein anderer Cascade-Pass den State VOR ihm mutiert. Wenn Reihenfolge-Abhängigkeit nötig wird, ist das ein Mode-3-Use-Case (Atomic Cross-Axis-Update, Phase-2-Backlog, §14 Open-Q 4)."
  - **Spec 1 §4.8:** `DictateModuleRegistry.all`-Listen-Definition (Z. 1017-1033) bekommt einen KDoc-Block oberhalb: "**Reihenfolge:** Deterministisch + Code-Review-relevant. Cascade-Order folgt dieser Liste (siehe §4.3 Step 5-6 + Cascade-Order-Vertrag). Reorder erfordert Phase-B-Wiederholung — kein Refactor ohne Plan-Konsultation."
  - **Spec 1 §10 Block-1b-Acceptance:** neue Klausel "Phase-B S-4 Cascade-Order-Determinism": ein Test mit zwei Mock-Modulen, die beide auf denselben State-Übergang reagieren, verifiziert dass die zweite Cascade-Action den State INKLUSIVE der ersten Cascade-Mutation sieht. Test-Datei `DictateOrchestratorCascadeOrderTest.kt`.

### F-6 `prefBindings()`-Interface-API ohne Konsumenten (Phase-1-Dead-Code)

- **Severity:** Important
- **Prüf-Achse:** 3 (Pure-Reducer-Invariante / Pref-Reads), 4 (ModuleServices-DI)
- **Was:** `DictateModule.prefBindings(): List<PrefBinding<S, *>>` (§4.2 Z. 462, KDoc Z. 456-461) ist als Pref-Mirror-Hook spezifiziert: "deklarative Auflistung der SharedPreferences-Keys, die in den Sub-State des Moduls gespiegelt werden. Der `PipelinePrefMirror` (§4.5) verwendet diese Liste, um Initial-Read und OnSharedPreferenceChangeListener generisch zu bauen." Aber §4.5 `PipelinePrefMirror.initialMirror` (Z. 864-895) und `sync` (Z. 898-922) verwenden 19 **hardcodierte** Pref-Mappings — keine Iteration über `modules.flatMap { it.prefBindings() }`. Hauptplan §7.1 Out-of-Scope (Z. 275) erkennt das an: "`prefBindings()`-Migration aller 13 Module … Phase 2: alle Module deklarieren ihre Prefs deklarativ; PrefMirror wird trivial-generisch." Folge: in Phase 1 ist die `prefBindings()`-API **Dead Interface Method** — Default `emptyList()` und niemand konsumiert sie.
- **Konsequenz:** Drei Risiko-Klassen: (a) Implementer baut bei RecordingModule eine `prefBindings()`-Liste und nimmt an, sie werde reaktiv gespiegelt — passiert nicht, Pref-Änderungen kommen nie in den State, Recording-Verhalten ist falsch. (b) Implementer entdeckt das Hardcode-Pattern in PipelinePrefMirror und vermutet einen Bug ("aber das Interface promises…"), schlägt einen Fix vor, der den Phase-2-Plan vorzieht — Scope-Creep. (c) Im Phase-2-Migration ist unklar, ob `initialMirror` komplett ersetzt wird oder co-existiert (Doppel-Pref-Reads, Race-Bugs).
- **Fix angewandt:**
  - **Spec 1 §4.2:** `prefBindings()`-KDoc (Z. 456-461) erweitert um Phase-Hinweis: "**Phase 1:** Default `emptyList()` — Implementierungen lassen den Hook leer. `PipelinePrefMirror` (§4.5) verwendet ihn in Phase 1 NICHT (hardcodierte Pref-Liste). **Phase 2 (Backlog, siehe Hauptplan §7.1):** `prefBindings()` wird zur einzigen Pref-Spiegelungs-Quelle. Module dürfen in Phase 1 die Hooks NICHT befüllen — das wäre Dead-Code, der Phase-2-Migration komplizierter macht."
  - **Spec 1 §4.5:** KDoc oberhalb von `PipelinePrefMirror`-Klassen-Definition (Z. 842) erweitert: "**Phase 1 (heute):** hardcoded Pref-Listen in `initialMirror` + `sync`. **Phase 2 (Hauptplan §7.1):** Iteration über `modules.flatMap { it.prefBindings() }` ersetzt die Hardcodes — Modul-Anwender deklarieren Prefs deklarativ. Während Phase 1: KEIN Modul-Pref-Hook konsumieren."
  - **Spec 1 §13.4.2:** Code-Review-Checkliste (Z. 5266) um Punkt ergänzt: "Module-`prefBindings()`-Override NUR mit Default `emptyList()` in Phase 1 erlaubt. Modul-spezifische Pref-Reads laufen ausschließlich über `ctx.global.X` (siehe Pure-Reducer-Vertrag §4.2 Z. 519)."

### F-7 `PipelineOrchestrator` vs. `DictateOrchestrator` Naming-Konflikt nicht prominent dokumentiert

- **Severity:** Important
- **Prüf-Achse:** 6 (Naming-Konflikt — Surprise-Finding #2)
- **Was:** Der heutige `app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt` (1383 Zeilen, verifiziert per `wc -l`) ist der Audio-Pipeline-Runner — orchestriert Speech-API-Calls + Reword-Pipeline + Auto-Formatting. Bleibt nach Refactor unverändert (Plan §8 Z. 3586: "JobExecutor + PipelineOrchestrator bleiben, aber im PipelineService gehalten"). Der neue `app/src/main/java/net/devemperor/dictate/state/DictateOrchestrator.kt` kommt hinzu — Action-Router + Cross-Module-Cascade-Dispatch. Verschiedene Verantwortlichkeiten, ähnliche Namen. Plan-Body erwähnt das Coexisten in einer Tabellen-Zeile (§8) und einem Audit-Eintrag (§13.5 G7), aber kein dedizierter Disambiguier-Block. S-3-Report §F-7 (im Findings-Wrap-Up-Block "Verifikationen") hat das per Code-Read bestätigt: "Klassen-Name kollidiert mit geplanter `state/DictateOrchestrator` nur konzeptuell, nicht in Code … Naming ist verwirrend, aber Plan §8 dokumentiert die Koexistenz."
- **Konsequenz:** Bug-Klasse "Cognitive Confusion": Implementer mit teil-implementiertem Block 1b liest in einem Test-Fail-Stack-Trace `PipelineOrchestrator.runTranscriptionPipeline(...) at 837` und sucht den Bug im neuen state/DictateOrchestrator (weil "wait, der State-Orchestrator hat keine `runTranscriptionPipeline`-Methode"). 30-60 Min Debug-Zeit. Plus: Code-Reviewer beim PR muss "Welcher Orchestrator?" jedes Mal kontextuell ermitteln. Plus: zukünftiges grep nach `Orchestrator` liefert vermischte Treffer. Langfristig-Wartbar wäre eine Umbenennung des alten — z.B. `PipelineOrchestrator` → `PipelineRunner` (passend, weil er das `PipelineRunner`-Interface aus §4.9 bedient) oder `PipelineExecutor` (näher zum heutigen Verhalten).
- **Fix angewandt:**
  - **Spec 1 §1 Kontext und Scope:** Neuer Block "Naming-Konvention für 'Orchestrator'" am Ende von §1 eingefügt — disambiguiert die beiden Klassen mit Tabellen-Form (Klasse / Verantwortlichkeit / Package / Geplante-Migration). Aussage: "Diese Doppel-Existenz ist **bewusst akzeptiert** für Phase 1 (kein Refactor des Audio-Pipeline-Pfades), wird in Phase 2 evaluiert (Umbenennung auf `PipelineRunner` oder Auflösung in `PipelineModule.runEffect`-Pfad). Implementer + Code-Reviewer beachten: 'Orchestrator' im Plan-Body meint **immer** den neuen `DictateOrchestrator`; der alte heißt im Plan-Body durchgängig `PipelineOrchestrator` (qualifiziert mit Package wo nötig)."
  - **Spec 1 §13.5.a:** G7-Block aktualisiert mit explicit Cross-Link auf den Naming-Konvention-Block in §1.
  - **Hauptplan §7.1 Out-of-Scope:** neuer Eintrag "Umbenennung des alten `PipelineOrchestrator`" — Verschoben-weil-Grund: "Phase 1 Scope ist State-Refactor, nicht Audio-Pipeline-Refactor; Umbenennung erfordert Konsumenten-Site-Updates (~10 Stellen, verifiziert via Spec 1 §12 Code-Pointer). Phase 2 evaluiert eine `PipelineRunner`/`PipelineExecutor`-Umbenennung oder eine Auflösung des Orchestrators in `PipelineModule.runEffect`-Pfad (eliminiert den Naming-Konflikt strukturell)."

### F-8 `shutdown()`-vs-`serviceScope.cancel()`-Reihenfolge nicht acceptance-getestet

- **Severity:** Important
- **Prüf-Achse:** 4 (ModuleServices-DI + Effect-Scope), 8 (Bugs durch Migration — Resource-Leak)
- **Was:** Spec 1 §4.3 `shutdown()` (Z. 702-727) hat einen KDoc-Block, der die Sequenz dokumentiert: "1. PrefMirror detachen … 2. Pro Modul `terminate(services)` rufen … 3. Service.onDestroy ruft anschließend `serviceScope.cancel()`." §7.3 onDestroy (Z. 3455-3460) implementiert das korrekt: `orchestrator.shutdown(); serviceScope.cancel()` — synchron in der richtigen Reihenfolge. **Aber:** das ist eine Vertrags-Aussage zwischen zwei Klassen, kein erzwungenes Pattern. Wenn ein Implementer den Service.onDestroy refactored (z.B. um Defensive-Try-Catch hinzuzufügen) und versehentlich `serviceScope.cancel()` vor `orchestrator.shutdown()` setzt, dann läuft `modules.forEach { terminate(services) }` auf einem gecancellten Scope — die `services.scope`-CoroutineScope ist tot, Hardware-Effects (`recordingHardware.release`) laufen synchron-blocking (kein Coroutine-Suspend), aber alle anderen async-Operations (z.B. `services.notificationCoordinator.cancel(NOTIF_ID)`) werden silent-no-op.
- **Konsequenz:** Bug-Klasse "Resource-Leak auf Service-Death". Bei aktivem Recording während Service.onDestroy: MediaRecorder bleibt im Native-Heap (heutiger §13.5 G6 Pfad A). Plan-Wortlaut behauptet das sei testbar via Mock-Spy auf `MediaRecorder.release()` (§10 Block-2-Acceptance Z. 3802), aber der Test prüft NUR dass `recordingManager.release()` aufgerufen wird — NICHT die Reihenfolge `shutdown` → `cancel`. Bei vertauschter Reihenfolge würde der Test trotzdem grün bleiben (release wird synchron in terminate gerufen, nur die nachfolgenden async-Cleanup-Schritte würden silent-no-op laufen — z.B. Notification bleibt sichtbar nach Service-Death weil cancel async). Plus: §4.3 KDoc-Aussage "Effekte sind hier synchron-Hardware-Releases (kein Coroutine-Suspend)" ist optimistisch — wenn ein Implementer einen Modul-`terminate(...)` mit `runBlocking { … }` schreibt (z.B. um eine zukünftige async-Sequenz zu serialisieren), würde der zur Laufzeit auf einem cancelled-Scope blockieren.
- **Fix angewandt:**
  - **Spec 1 §4.3:** `shutdown()`-KDoc (Z. 702-714) erweitert um expliziten "**Aufrufer-Vertrag**"-Absatz: "Aufrufer (typischerweise `Service.onDestroy`) MUSS `shutdown()` **vor** `serviceScope.cancel()` rufen. Andernfalls laufen Module-`terminate(services)`-Calls auf einem gecancellten Scope — synchrone Hardware-Releases funktionieren noch, aber alle async-Cleanup-Schritte (Notification-cancel, DB-Flush, etc.) werden silent-no-op. Diese Reihenfolge ist Teil des `DictateOrchestrator.shutdown()`-Vertrags und durch `OrchestratorShutdownOrderTest.kt` (Block-2-Acceptance) verifiziert."
  - **Spec 1 §10 Block-2-Acceptance:** neue Klausel "Phase-B S-4 shutdown-Order": ein Test mit Fake-Module, dessen `terminate(services)`-Implementation auf `services.scope.isActive` assertiert, verifiziert dass Scope während `terminate`-Aufruf noch lebt. Test-Datei `OrchestratorShutdownOrderTest.kt`. Plus: ein zweiter Test verifiziert, dass `terminate` BEVOR `cancel` läuft (Spy-based: zwei separate `verify`-Aufrufe in der erwarteten Reihenfolge).

### F-9 §11.6.2 Recovery-Snippet referenziert `_state.update` statt `store.update` (Minor Drift)

- **Severity:** Minor
- **Prüf-Achse:** 4 (ModuleServices-DI), 6 (Cross-Spec-Konsistenz)
- **Was:** Spec 1 §11.6.2 Z. 4604 (vor Fix): `_state.update { it.copy(pendingSessions = pending + orphanedRecorded) }` — das `_state`-Identifier ist die Insider-Syntax von `DictateUiStateStore` (private MutableStateFlow, §4.4 Z. 754). Außerhalb der Store-Klasse muss `store.update { ... }` verwendet werden. §4.6 `PipelineRecovery.recover(store)` Z. 937 macht es korrekt (`store.update { ... }`). Drift in der Beispielcode-Variante von §11.6.2.
- **Konsequenz:** Implementer copy-pasted Z. 4604 in `PipelineRecovery`, bekommt `Cannot access '_state': it is private` Compile-Error, debuggt 5 Min, fixt es. Minor — keine Production-Bug-Klasse. Aber: Spec-Drift = Verlust an Glaubwürdigkeit der Plan-Snippets.
- **Fix angewandt:** **Spec 1 §11.6.2:** Z. 4604 auf `store.update { it.copy(pendingSessions = pending + orphanedRecorded) }` umgestellt. FIX-Kommentar mit Phase-B-S-4-Verweis.

---

## Verifikationen (Code-Reads)

| Plan-Aussage | Verifiziert per | Ergebnis |
|---|---|---|
| `core/PipelineOrchestrator.kt` existiert als 56kB Audio-Pipeline-Runner (Surprise-Finding #2) | `wc -l` + Read Z. 53-69 | ✅ 1383 Zeilen, `class PipelineOrchestrator @JvmOverloads constructor(aiOrchestrator, autoFormattingService, ...)` — komplett verschieden vom State-Orchestrator |
| `core/JobExecutor.kt` hat Test-Seam-Pattern (`initialize` / `initializeForTest` / `resetForTest`) | Read Z. 56-72 | ✅ Pattern explizit dokumentiert: "Stored as PipelineRunner — the minimal contract JobExecutor needs — so unit tests can swap in a fake without constructing a full PipelineOrchestrator" |
| `core/RecordingStateController.kt` `setState`-Callback ist SYNCHRON | Read Z. 353-357 | ✅ `state = newState; callback?.onStateChanged(old, newState)` — synchroner Aufruf inline. Neue Architektur dispatcht async via `emitAction`/Subscriber — Reentrancy-Wechsel von sync → async |
| `core/ActiveJobRegistryObserver.kt` als Java-Brücken-Vorlage | bereits in S-3 verifiziert | ✅ S-3 §F-6 hat die Vorlage 1:1 portiert nach `state/DictateUiStateObserver.kt` (§4.4 Z. 819-836) |
| `DictateOrchestrator.collectLeaves` verwendet `KClass.sealedSubclasses` (Reflection) | Read §4.3 Z. 587-589 | ❌ Bug bestätigt — Reflection-Use ohne ProGuard-Keep-Regel im Plan. Hauptplan §7.2 PENDING-Marker Z. 283 hat das erkannt, aber kein konkreter ProGuard-Patch im Plan-Body |
| `DictateModuleRegistry.all` Init-Check ist nur Doppel-Routing, nicht Fehlende Routing | Read §4.8 Z. 1036-1045 | ❌ Bug bestätigt — nur `actionClasses.toSet().size == actionClasses.size`-Check. S-3 §F-7 hat das als Follow-Up parkiert. Vollständigkeits-Check fehlt |
| `Effect.AllocateMediaRecorder`-Signatur ist konsistent über Definition / Reducer / EffectHandler | Read §15.2 Z. 5456 + 5492 + 5565 | ❌ Bug bestätigt — Z. 5456 hat 2 Felder (target, useBluetooth), Z. 5492 ruft mit 3 Args (target, useBluetooth, audioFile), Z. 5565 ruft `services.recordingHardware.allocate(target, useBluetooth)` ohne audioFile — drei verschiedene Aussagen. R.2-Vertrag (audioFile lebt im State) erfordert 3-Arg-Variante konsistent |
| AudioModule.onCrossModuleStateChange enthält dead-Code-Block | Read §15.3 Z. 5857-5861 | ❌ Bestätigt — leerer if-Block (nur Kommentare, kein cascade.add); plus widerspricht §15.5 Mode-2-Vertrag (Observer darf nur Actions emittieren) |
| Cascade-Order der 13 Module ist deterministisch dokumentiert | Read §4.3 Step 5-6 (Z. 689-693) | ❌ Nein — kein Hinweis auf Reihenfolge-Garantie oder Disjunkt-Konvention. Implizit deterministisch via `modules.flatMap`, aber nicht explizit verankert |
| `prefBindings()`-Hook ist im PipelinePrefMirror konsumiert | Read §4.2 Z. 462 + §4.5 Z. 864-924 | ❌ Nein — `prefBindings()`-API existiert im Interface, aber `initialMirror` + `sync` haben 19 hardcodierte Pref-Mappings, keine Iteration über `modules.flatMap { prefBindings() }`. Phase-2-Backlog, aber im Plan-Body nicht klar als Phase-1-No-Op markiert |
| `shutdown()`-vs-`serviceScope.cancel()`-Reihenfolge ist getestet | Read §10 Block-2-Acceptance | ❌ Nein — Block-2-Acceptance Z. 3802 testet nur `MediaRecorder.release`-Aufruf, nicht die Reihenfolge `shutdown` → `cancel`. Bei vertauschter Reihenfolge würde der Test grün bleiben |
| `ModuleServices.emitAction` ist async-via-scope (Reentrancy-Vertrag 2.1.4 Option A) | Read §4.3 Z. 596-600 + §4.7 Z. 989-1000 | ✅ `emitAction(action: Action) { scope.launch { dispatch(action) } }` — async via scope.launch; KDoc erklärt "re-entrant Aufrufe aus runEffect heraus sind sicher" |
| `MAX_CASCADE_DEPTH = 8` (R.6) | Read §4.3 Z. 731 | ✅ DEBUG `error()` / Release-Log; Begründung "Cap 8 ist konservativ, reale Cascade-Tiefen 1-3" |
| Self-Cascade-Erlaubnis (KG-RSB-2 Auflösung A) | Read §4.3 Z. 682-687 | ✅ Self-Filter `it.id != module.id` gestrichen; FIX-Kommentar verweist auf KG-RSB-2 + R.RSB-FIX-A-Regression-Test |
| EffectFailure-Origin-Routing (S-3 F-1) | Read §4.3 Z. 624-636 + 669-676 | ✅ moduleById-Lookup für EffectFailure-Special-Case + try-catch in Step 4 setzt originModuleId; KDoc dokumentiert die Sonderpfad-Begründung |
| KeyboardInputModule (S-3 F-2) | Read §15.6 Z. 5908-5980 | ✅ Vollständig spezifiziert mit Unit-State + Effect-Pipeline + KDoc-Begründung "Konsistenz mit F-8 Single-Dispatch" |
| §11.6.2 Recovery-Snippet verwendet `store.update` (nicht `_state.update`) | Read §11.6.2 Z. 4604 | ❌ Bug bestätigt — `_state.update` ist Insider-Syntax, sollte `store.update` sein |

---

## Plan-Edits (Audit-Trail)

| Datei | Sektion | Art | Kurzbeschreibung |
|-------|---------|-----|------------------|
| Spec 1 | §1 Kontext und Scope | Add | Naming-Konvention-Block "PipelineOrchestrator vs. DictateOrchestrator" — Tabellen-Disambiguierung (F-7) |
| Spec 1 | §4.2 prefBindings()-KDoc | Refactor | Phase-1/Phase-2-Hinweis ergänzt: Phase 1 Default `emptyList()`, kein PrefMirror-Konsum (F-6) |
| Spec 1 | §4.3 dispatchInternal | Add | ProGuard-Keep-Regel-Hinweis-Block nach `collectLeaves` mit konkretem ProGuard-Snippet (F-1) |
| Spec 1 | §4.3 dispatchInternal | Add | Cascade-Order-Vertrag-Block nach cascadeActions.forEach (Z. 693): Deterministische Reihenfolge + Disjunkt-Konvention (F-5) |
| Spec 1 | §4.3 shutdown()-KDoc | Refactor | Aufrufer-Vertrag-Absatz: `shutdown()` MUSS vor `serviceScope.cancel()` laufen (F-8) |
| Spec 1 | §4.5 PipelinePrefMirror-KDoc | Add | Phase-1/Phase-2-Hinweis: Phase 1 hardcoded, Phase 2 via `modules.flatMap { prefBindings() }` (F-6) |
| Spec 1 | §4.8 DictateModuleRegistry | Refactor | Init-Check erweitert um Vollständigkeits-Check via `Action::class.sealedSubclasses` + Excludelist (F-2) |
| Spec 1 | §4.8 DictateModuleRegistry.all | Add | KDoc oberhalb der Liste: "Reihenfolge: Deterministisch + Code-Review-relevant" + Verweis auf §4.3 Cascade-Order-Vertrag (F-5) |
| Spec 1 | §4.8 DictateModuleRegistry | Add | Klärungs-Block "Manuelle Modul-Liste vs. Reflection-basierte Action-Leaves" — zwei verschiedene Reflection-Entscheidungen (F-1) |
| Spec 1 | §10 Block-1b-Acceptance | Add | 4 neue Klauseln: ProGuard-Robustheit, Vollständigkeits-Check, Cascade-Order-Determinism, shutdown-Order (F-1, F-2, F-5, F-8) |
| Spec 1 | §13.4.2 Code-Review-Checkliste | Add | Modul-prefBindings()-Override-Regel: nur Default `emptyList()` in Phase 1 (F-6) |
| Spec 1 | §13.5.a G7-Block | Refactor | Cross-Link auf §1 Naming-Konvention-Block (F-7) |
| Spec 1 | §15.2 Effect.AllocateMediaRecorder | Fix | Definition Z. 5456 auf 3 Felder (target, useBluetooth, audioFile); runEffect-Body Z. 5565 ruft mit 3 Args; audioFile-Vertrag-Block expandiert (F-3) |
| Spec 1 | §15.3 AudioModule.onCrossModuleStateChange | Fix | Dead-Code-Block ersatzlos entfernt + Top-of-Block-KDoc: "Observer darf NUR Actions emittieren, keine Direct-Hardware-Calls" (F-4) |
| Spec 1 | §11.6.2 Recovery-Snippet | Fix | `_state.update` → `store.update` (Insider-Syntax-Drift, F-9) |
| Hauptplan | §7.1 Out-of-Scope | Add | Eintrag "Umbenennung des alten PipelineOrchestrator" + Phase-2-Trigger-Klausel (F-7) |
| Hauptplan | §7.2 PENDING-Marker | Refactor | Z. 283 R.4-PENDING auf "RESOLVED in Phase-B S-4: ProGuard-Keep-Regel in Spec 1 §4.3 dokumentiert" aktualisiert (F-1) |
| Hauptplan | §9 Iter-Log | Add | Phase-B Quality-Gate S-4 Eintrag (2026-05-13) — 8-Findings-Summary; chronologisch nach S-3 platziert |

**Gesamt:** 18 Edit-Operationen in 2 Dateien (Spec 1: 15, Hauptplan: 3). Spec 2 + Spec 3 unverändert — S-4 ist Spec-1-Scope (DictateOrchestrator + DictateModule + DictateModuleRegistry leben dort kanonisch).

---

## Offene Fragen für nachfolgende Agents

### Für S-5 (Service-Schicht)

- Das §11.6.2 Recovery-Snippet (Z. 4598-4617) zeigt einen zweistufigen DB-Read (`findPendingInsertion` + `getByStatus("RECORDED")` mit Audio-File-Existence-Check) im Recovery-Pfad. S-2-Report §F-2 hat den RECORDING-Recovery-Pfad bereits dokumentiert. S-5 sollte verifizieren, dass die `DAO-Methode `getByStatus(String)` tatsächlich existiert oder ergänzt werden muss (Block 3 Schema-Erweiterung in §11.2.2 listet sie nicht explizit — Plan §6.1 erwähnt nur `findPendingInsertion` + andere neue DAOs).
- §7.3 onCreate-Snippet Z. 3398-3408 zeigt das `ModuleServicesFactory`-Lazy-Pattern, aber das `RecordingHardware(audioManager, ...)`-Konstrukt verwendet `...`-Auslassungen. S-5 sollte beim Block-2-Implementation den vollständigen RecordingHardwareSubsystem-Wire-Pfad spezifizieren (welche Konstruktor-Args, woher kommt audioManager etc.) — heute ist es ein Placeholder.

### Für S-6 (LayoutCatalog) + S-8 (OverlayBackend)

- S-4-Fix F-5 verankert die Cascade-Order-Determinism — wenn die Resolver in Spec 2 §3.2 / Spec 3 §4.2 Cascade-getriggert sind, sehen sie den State INKLUSIVE vorheriger Cascade-Mutationen. S-6 + S-8 sollten beim Block-5/6-Implementation prüfen, ob ein Resolver auf einen Cascade-Zwischenstand zurückgreift (sollte nicht, weil Resolver pure State→View-Mappings sind). Wenn ein Resolver Cross-Module-Reads macht (z.B. `state.recording.isActive && state.overlay.hasPermission`), ist die Frage: sieht er den State VOR oder NACH der Cascade? Antwort: NACH (Resolver läuft nicht im Cascade-Pass, sondern erst im nächsten StateFlow-collect-Tick). Trotzdem als Verifikations-Hinweis nützlich.
- S-4-Fix F-1 ProGuard-Keep-Regel betrifft auch S-8 — Action.OverlayAction-Subclasses (CloseOverlay, UpdatePosition, etc.) müssen in der Keep-Regel mitgehalten werden. Die Regel `-keep,allowobfuscation class * extends net.devemperor.dictate.state.Action { *; }` fängt das transitiv (alle direkten + indirekten Action-Subtypes). S-8 sollte beim Block-6-Implementation einen Release-Build-Smoke-Test machen.

### Für S-7 (Audio-File-Management)

- S-4-Fix F-3 verankert `Effect.AllocateMediaRecorder(target, useBluetooth, audioFile)` mit 3 Args. S-7 (AudioFileFactory) ist der Pre-Dispatch-Allocator — `services.audioFileFactory.allocate()` läuft im Resolver, das Result wird im `Action.RecordingAction.StartRecording(target, audioFile)` mitgeschoben (Spec 1 §4.11.5.1 Sequence). S-7 sollte verifizieren, dass die `RecordingHardwareSubsystem.allocate(target, useBluetooth, audioFile)`-Signatur (S-4 §4.7 KDoc-Erweiterung) konsistent mit dem heutigen `RecordingManager.kt:61-62` ist (MediaRecorder-Container m4a + MPEG_4 + AAC) — das `audioFile`-Arg wird vom MediaRecorder als Output-Path verwendet.

### Für S-9 (ResetSuppressBit-Lifecycle)

- S-4-Fix F-5 Cascade-Order-Determinism betrifft den ResetSuppressBit-Cascade: RecordingModule.onCrossModuleStateChange feuert `OverlayAction.ResetSuppressBit` beim `Idle → Preparing`-Übergang. Wenn andere Module ebenfalls auf denselben Übergang reagieren (ViewModeModule, AudioModule, PendingSessionsModule — alle 5 Owner-Spalten in §15.1.x für `Recording`), läuft der ResetSuppressBit-Cascade in `DictateModuleRegistry.all`-Reihenfolge. RecordingModule ist Position #1 in der Liste (Z. 1018), also läuft sein Cascade-Hook als erstes. S-9 sollte verifizieren, dass die `R.RSB-FIX-A`-Regression-Test (Block-4-Acceptance) auch bei einer Re-Reihenfolge der Module funktioniert (Test-Setup: dispatch StartRecording, verifiziere ResetSuppressBit-Cascade ist im store.snapshot.overlay.suppressBit reflektiert — Position-unabhängig).

### Cross-Cutting

- **Reflection-vs-manuelle-Registry-Entscheidung (F-1):** Die Plan-Aussage in Hauptplan §9 Z. 556 ("manuelle Liste mit Init-Check, Reflection als optionales Upgrade") ist nur auf die Modul-Liste bezogen. Die Action-Leaves-Indexing verwendet Reflection als integralen Bestandteil. Phase-B S-4-Fix F-1 verankert die ProGuard-Keep-Regel. Falls ein zukünftiger Refactor die Reflection-Komponente ELIMINIEREN will (z.B. ein KSP-Annotation-Processor, der die Leaves-Map zur Compile-Zeit baut), sollte das als Phase-2-Backlog evaluiert werden (Hauptplan §7.1).
- **Cascade-Order-Vertrag (F-5):** Konvention "disjunkte State-Achsen pro Cascade" ist aktuell gut eingehalten (Coupling-Matrix §15.1.x). Wenn Phase 2 einen Mode-3 (Atomic Cross-Axis-Update) bekommt, ist die Cascade-Order-Frage strukturell anders zu lösen — Mode 3 läuft als atomic state.copy außerhalb des Cascade-Patterns.

---
