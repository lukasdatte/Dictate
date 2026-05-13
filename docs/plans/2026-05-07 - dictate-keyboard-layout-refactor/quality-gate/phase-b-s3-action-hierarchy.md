# Phase B — S-3 Action-Hierarchie + Sealed-Leaves-Indexing + Single-Dispatch Migrations-Pfad-Review

**Erstellt:** 2026-05-13
**Reviewer:** Phase-B-Agent S-3 (Subsystem #3 von 9)
**Plan-Version vor Edits:** Stand nach S-2-Apply-Pass (S-2-Report `phase-b-s2-db-schema-migration.md`)

---

## Summary

Der Migrationspfad für S-3 ist **architektonisch tragfähig** — Sealed-Leaves-Indexing (R.4), Reentrancy-Vertrag (Issue 2.1.4 Option A), nullable Resolver-Idiom (R.3), Exhaustivity-Konvention (Issue 2.0.6) und Cascade-Tiefe-Counter (R.6) bilden zusammen einen sauberen MVI-Single-Dispatch-Stack, der die heutige verteilte Controller-Mutation (RecordingStateController + KeyboardUiController + KeyboardStateManager + verstreute Service-Lambdas) konsolidiert.

**Zwei Critical-Bugs ans Tageslicht gebracht — beide deterministisch dead-code im aktuellen Plan-Wortlaut:**

1. **`Action.EffectFailure` ist ein Top-Level-Subtyp von `Action`, aber kein Modul beansprucht `actionClass = Action.EffectFailure::class`.** Der `moduleByLeafClass`-Lookup hätte `null` geliefert → `DispatchOutcome.Unrouted` → still gedroppt. Das gesamte Failure-Channel-Konzept (Option D) wäre Architektur-Theater geblieben: Module wären nie über Effect-Failures informiert worden, kein State-Rollback hätte gegriffen, IME-Bugs wären als heisere Logs in Crashlytics verschwunden, ohne Recovery.

2. **`KeyboardInputAction` (Backspace, EnterKey, SpaceKey, CopyToClipboard) wird von Spec 2 §3.3 als "kein eigenes Modul — direkt im IME-Service ausgeführt" kommentiert.** Aber die Slot-Resolver in §3.2 dispatchen genau diese Actions an `orchestrator.dispatch(...)`. Resultat identisch zu Bug #1: `Unrouted`, silent-drop, Backspace/Enter/Space-Buttons sind im neuen System tot. Der IME-Service-Service hat keinen alternativen Eingriffspunkt — das `pipeline?.dispatch(...)` in `DictateInputMethodService` (§5 Z. 2247-2249) leitet alles an den Orchestrator weiter, der dann nichts tut.

Beide Bugs entstehen nicht durch fehlerhafte Implementierung, sondern durch **Spec-Inkonsistenz**: der Plan beschreibt eine Action-Hierarchie, die der Orchestrator nicht durchgängig routen kann.

**Fixes:**
- **EffectFailure** trägt jetzt einen `originModuleId: ModuleId`; der Orchestrator routet sie über `moduleById[originModuleId]` zurück ans emittierende Modul. Das `DictateModule`-Interface bekommt einen neuen `reduceFailure(state, failure, ctx)`-Hook mit Default `null` (Module ohne Failure-Pfad → semantisch korrekter `Rejected("reducer-null")`). `dispatchInternal` (§4.3) bekommt einen Special-Case-Branch für `Action.EffectFailure`.
- **KeyboardInputAction** bekommt ein eigenes `KeyboardInputModule` (§15.6 neu, Effect-only, `Unit`-State) — kanonisch im Plan dokumentiert, in `DictateModuleRegistry.all` registriert, `ModuleId.KeyboardInput` ergänzt, `ModuleServices.clipboard` als optionales Field für `CopyToClipboard`-Effect.

**Vier Important-Findings:**
- `ToggleAudioFocus` vs. `ToggleAudioFocusPref` Naming-Drift zwischen Spec 1 (zwei Stellen) und Spec 2 §3.3-SoT — auf SoT-Name umgestellt.
- Spec 2 §11.6 Empfehlungs-Snippet verletzte das R.3-nullable-Resolver-Idiom (`onAction?.invoke(slot.actionResolver(s))` statt `slot.actionResolver(s)?.let { onAction?.invoke(it) }`) — auf §6-konsistenten Pattern korrigiert.
- `ResendAction`-Naming-Kollision zwischen `core.ResendAction` (existierender Status-Dispatcher) und `state.Action.ResendAction` (neuer Orchestrator-Action) explizit dokumentiert als bewusst akzeptierte Doppel-Identität, damit Implementer die Typen nicht versehentlich mischen.
- Java-Brücke `DictateUiStateObserver` war im Plan nur als "vorgesehen für Block 2" erwähnt; vollständige Implementierung (analog zu `ActiveJobRegistryObserver.kt` — verifiziert per Code-Read) jetzt im Plan-Body mit Consumer-Tabelle + Block-2-Acceptance-Klausel.

**Ein Minor-Finding:**
- Spec 2 §11.6 erwähnte als Alternative für Amplitude/Timer-Hooks "simple Callback-Methode am LocalBinder", was dem F-8-Vertrag (LocalBinder hat NUR `state` + `dispatch`, keine typed Forwarder) widerspricht. Auf "zusätzlicher `StateFlow<AmplitudeTick>` am LocalBinder" umgestellt.

**Befund:** **7 Findings (2 Critical, 4 Important, 1 Minor) — 14 Plan-Edits in 2 Dateien.** Spec 3 unberührt — S-3-Action-Hierarchie ist Spec-2-SoT, die Spec-3-Konsumenten (§4.2 OverlayBackend, §4.8 OverlayModule) sind unverändert konsistent.

---

## Findings + Applied Fixes

### F-1 `Action.EffectFailure` ohne Modul-Routing (Failure-Channel tot)

- **Severity:** Critical
- **Prüf-Achse:** 2 (Sealed-Leaves-Indexing), 7 (Bugs durch Migration — EffectFailure-Behandlung)
- **Was:** Spec 2 §3.3 (Z. 135-137 vor Fix) definierte `data class EffectFailure(effect: String, reason: String) : Action()` als Top-Level-Subtyp von `Action`, parallel zu den 13 inneren sealed classes. Der Comment dort sagte "Geworfen vom Orchestrator bei Effect-Exception; Module reagieren via onCrossModuleStateChange." Spec 1 §4.3 Z. 617 ruft `dispatchInternal(Action.EffectFailure(effect, reason), depth+1)`. Aber: der `moduleByLeafClass`-Lookup (Z. 552) baut sich aus `module.actionClass` zusammen — kein Modul deklariert `actionClass = Action.EffectFailure::class`. Folge: `moduleByLeafClass[EffectFailure::class] == null` → `DispatchOutcome.Unrouted(action)` → still gedroppt. Der dispatchInternal-Pfad (Steps 2–6 in §4.3) läuft nie für EffectFailure → `prevGlobal`/`nextGlobal`-Cascade wird nie gestartet → kein Modul beobachtet das Failure → die "Module reagieren via onCrossModuleStateChange"-Konvention war reines Wishful-Thinking.
- **Konsequenz:** Failure-Channel ist totes Konstrukt. Ein `runEffect`-Exception (z.B. `RecordingHardware.allocate` wirft, weil die Mic-Permission gerade entzogen wurde) erzeugt einen `android.util.Log.e`-Eintrag und sonst nichts. Der State bleibt im `Preparing`-Zustand (Recording-Module hat den State-Update vor dem Effect schon gemacht); der User sieht ein "preparing"-UI, das nie zu `Active` wird. Manuelles Cancel funktioniert nicht (Resolver returnt `Action.RecordingAction.CancelRecording` → `RecordingModule.reduce` sieht `Preparing → Idle` als legal, aber kein Effect wird mehr ausgeführt, weil das Hardware schon failed war). De-facto: hängende UI-Achse, Pipeline gestoppt, kein Recovery-Pfad. Bug-Klasse "silent stuck state".
- **Fix angewandt:**
  - **Spec 2 §3.3:** `EffectFailure` trägt jetzt `originModuleId: ModuleId` als drittes Feld. KDoc dokumentiert die Routing-Konvention "über ID, nicht über KClass-Lookup". Begründung im Kommentar: nur das Owner-Modul des Effects weiß, welcher Sub-State-Rollback korrekt ist.
  - **Spec 1 §4.2:** Neuer optionaler Method `reduceFailure(state, failure: Action.EffectFailure, ctx)` im `DictateModule`-Interface mit Default `null` (semantisch korrekter `Rejected("reducer-null")` für Module ohne Failure-Pfad). KDoc erklärt, warum nicht als zusätzlicher Branch in `reduce(...)` (type-safety: `Action.EffectFailure` ist kein Subtyp von `A`).
  - **Spec 1 §4.3:** `dispatchInternal` bekommt einen Special-Case für `Action.EffectFailure` — lookup über `moduleById`-Map (neu, sekundärer Index auf `ModuleId`) statt `moduleByLeafClass`. Der Reducer-Call ist `if (action is EffectFailure) module.reduceFailure(...) else module.reduce(...)`. Die try-catch-Stelle in Step 4 setzt `originModuleId = typedModule.id` beim Konstruieren der EffectFailure-Action.
  - **§10 Block-2-Acceptance:** neue Klausel "EffectFailure-Origin-Routing" — ein Effect-Throw triggert eine EffectFailure mit korrekter `originModuleId`, das Origin-Modul reagiert via `reduceFailure`. Verifiziert via `DictateOrchestratorTest.kt::effectFailure_routedBackToOriginModule()`.

### F-2 `KeyboardInputAction` ohne Modul-Routing (Backspace/Enter/Space tot)

- **Severity:** Critical
- **Prüf-Achse:** 1 (Action-Hierarchie-Vollständigkeit), 2 (Sealed-Leaves-Indexing)
- **Was:** Spec 2 §3.3 Z. 291 (vor Fix): `// ─── Tastatur-Eingaben (kein eigenes Modul — direkt im IME-Service ausgeführt) ───`. Direkt darunter sealed class mit `Backspace`, `EnterKey`, `SpaceKey`, `CopyToClipboard(text)`. Die Resolver in Spec 2 §13 (z.B. Z. 1045: `actionResolver = { Action.KeyboardInputAction.Backspace }`) dispatchen diese Actions. Die Click-Listener (§6 wireStaticHandlers, §11.6 Empfehlungs-Snippet) rufen `onAction(it)` → `dispatchAction(action)` → `pipeline?.dispatch(action)` → `orchestrator.dispatch(action)`. Aber: kein Modul deklariert `actionClass = Action.KeyboardInputAction::class`. Folge identisch zu F-1: `Unrouted`, silent-drop. Das Plan-Comment "direkt im IME-Service ausgeführt" suggeriert einen Bypass-Pfad, der **nicht existiert** — alle Action-Dispatches laufen über `LocalBinder.dispatch` (F-8) → `orchestrator.dispatch`.
- **Konsequenz:** Backspace-, Enter- und Space-Buttons im Keyboard-Layout sind im neuen System dead-Listener. Erste manuelle UI-Test nach Block 5/6 würde scheitern. Plus: `CopyToClipboard`-Action wird nirgendwo emittiert (heute), wäre aber auch tot. Architektur-Konsistenz-Bruch: F-8 fordert "alle Mutationen über einen Eingang"; das Plan-Comment war Reform-Reflex, der den Eingang umschiffen wollte, ohne den Refactor durchzuziehen.
- **Fix angewandt:**
  - **Spec 2 §3.3:** Kommentar präzisiert auf "(KeyboardInputModule — Spec 1 §15.6)". Der frühere "kein eigenes Modul"-Hinweis ist gestrichen — mit Begründung im FIX-Kommentar.
  - **Spec 1 §15.1:** Modul-Tabelle um Zeile 13 `KeyboardInputModule` erweitert; InterruptionModule rutscht auf #14.
  - **Spec 1 `ModuleId`-Aufzählung (§4.2):** neuer `data object KeyboardInput` ergänzt.
  - **Spec 1 §4.8 `DictateModuleRegistry.all`:** `KeyboardInputModule` in der Liste vor dem InterruptionModule-Kommentar eingefügt.
  - **Spec 1 §15.6 NEU:** vollständige Implementierung von `KeyboardInputModule` als `DictateModule<Unit, Action.KeyboardInputAction, KeyboardInputModule.Effect>`. Reducer übersetzt jede Action 1:1 in einen Effect; `runEffect` nutzt `services.inputConnectionProvider()` (existiert bereits in §4.7) für Backspace/Enter/Space und `services.clipboard` (neu in §4.7) für `CopyToClipboard`. KDoc erklärt die `Unit`-State-Begründung (System-Clipboard + InputConnection-Buffer leben außerhalb `DictateUiState`) und die Konsistenz mit F-8 Single-Dispatch.
  - **Spec 1 §4.7 `ModuleServices`:** `clipboard: android.content.ClipboardManager?` als neues Field, KDoc dokumentiert Service-onCreate-Wiring und Null-Fallback.
  - **§10 Block-2-Acceptance:** neue Klausel "KeyboardInputModule (§15.6)" — Buttons lösen die korrekten InputConnection-Operationen aus, verifiziert via `KeyboardInputModuleTest.kt` und `orchestrator.dispatch(Action.KeyboardInputAction.Backspace)` returnt NICHT `Unrouted`.

### F-3 `ToggleAudioFocus` vs. `ToggleAudioFocusPref` Naming-Drift

- **Severity:** Important
- **Prüf-Achse:** 1 (Action-Hierarchie-Vollständigkeit), 6 (Cross-Spec-Konsistenz)
- **Was:** Spec 2 §3.3 Z. 201 (SoT) definiert `object ToggleAudioFocusPref : AudioAction()` mit `Pref`-Suffix. Aber Spec 1 §9.1 RecordingStateController-Migration-Tabelle (Z. 3498) und Spec 1 §13.4 DRY-Tabelle nennen die Action als `Action.AudioAction.ToggleAudioFocus` (ohne Suffix). Auch Spec 2 §13 Resolver verwenden konsistent `ToggleAudioFocusPref` (6 Stellen) — der Drift ist nur in Spec 1 (2 Stellen).
- **Konsequenz:** Ein Implementer, der Spec 1 §9.1 als Migrations-Vorlage nimmt (sehr wahrscheinlicher Pfad — "RecordingStateController → RecordingModule" ist eine wichtige Block-1b-Sub-Aufgabe), würde im Resolver oder Reducer einen Symbol-Reference auf `Action.AudioAction.ToggleAudioFocus` schreiben → Compile-Error. Oder schlimmer: würde versehentlich eine zweite Action namens `ToggleAudioFocus` (ohne Pref) hinzufügen, weil "Spec 1 hat es so genannt" → divergente Action-Liste, Cross-Spec-Verwirrung. Bug-Klasse "Spec-Drift, kein Production-Bug, aber kostet Implementer 30-60 Min Debugging".
- **Fix angewandt:** Spec 1 §9.1 Migration-Tabelle und §13.4 DRY-Tabelle: `ToggleAudioFocus` → `ToggleAudioFocusPref`, mit FIX-Kommentar, der auf Spec 2 §3.3 als SoT verweist.

### F-4 Spec 2 §11.6 Click-Listener-Empfehlung verletzt R.3-nullable-Resolver-Idiom

- **Severity:** Important
- **Prüf-Achse:** 4 (Nullable Resolver-Idiom)
- **Was:** Spec 2 §11.6 (Z. 1870 vor Fix) zeigt einen Empfehlungs-Snippet für Click-Listener: `onAction?.invoke(slot.actionResolver(s))`. Aber `slot.actionResolver` hat post-R.3 den Typ `(DictateUiState) -> Action?` (Spec 2 §3.1 Z. 91 + §3.3-FIX-Kommentar). Das heißt: `slot.actionResolver(s)` kann `null` sein; `onAction: (Action) -> Unit` ist nicht-nullable. `onAction?.invoke(null!!)` (mit Kotlin's `!!`) crasht; `onAction?.invoke(null)` ist Compile-Fehler. Der korrekte Pattern (§6 wireStaticHandlers Z. 565, Spec 3 §4.2 Z. 374) ist `slot.actionResolver(s)?.let { onAction?.invoke(it) }`.
- **Konsequenz:** Ein Implementer, der §11.6 als Vorlage nimmt (das ist die längere, ausführlicher dokumentierte Empfehlung), würde entweder einen Compile-Fehler bekommen (semi-positiv, fängt es früh) ODER, wenn er `!!` einfügt, einen NPE-Crash beim ersten Click auf einen Button mit nullable-Resolver (z.B. `RECORD` im `Preparing`-State). Bug-Klasse "Spec-Drift" + potentieller "NPE im Edge-Case-Path". Außerdem: zweiter Lese-Pfad weiter unten in §11.6 (Risiko-2-Block Z. 1860) erwähnte noch `Action.NoOp` als möglichen Resolver-Output, was post-R.3 nicht mehr existiert.
- **Fix angewandt:** §11.6 Z. 1870 auf `slot.actionResolver(s)?.let { onAction?.invoke(it) }` umgestellt, mit FIX-Kommentar. §11.6 Risiko-2-Block Z. 1860: `Action.NoOp` → `null` (mit FIX-Kommentar, der R.3 referenziert).

### F-5 `ResendAction`-Naming-Kollision zwischen `core` und `state` Packages

- **Severity:** Important
- **Prüf-Achse:** 1 (Action-Hierarchie-Vollständigkeit), 6 (Cross-Spec-Konsistenz)
- **Was:** Im heutigen Code (verifiziert per Read `core/ResendStatusDispatcher.kt:11-35`) existiert eine sealed class `net.devemperor.dictate.core.ResendAction` mit Varianten `Insert(output, sessionId)`, `Resume(sessionId)`, `NoOp`. Sie ist der interne Entscheidungs-Typ des `ResendStatusDispatcher` (Status-Matrix für den Resend-Button). Der neue Plan (Spec 2 §3.3 Z. 207-212) definiert `net.devemperor.dictate.state.Action.ResendAction` mit Varianten `ResendLastAudio`, `ResendLastAudioLong`, `ResendCooldownExpired`, `MarkLastAudio(exists)` — semantisch komplett verschieden. Spec 1 §6.1.3 Konsumenten-Patch (Z. 2702-2722) referenziert die alte `ResendAction.NoOp`-Variante — was korrekt ist, aber für einen Plan-Leser, der die SoT in Spec 2 §3.3 lesen würde, verwirrend wirkt: "Aber `Action.ResendAction` hat doch kein `NoOp`?".
- **Konsequenz:** Code-Review-Trap. Ein Implementer könnte beim Block-3-Konsumenten-Update auf `Action.ResendAction.NoOp` zugreifen wollen (existiert nicht) statt `core.ResendAction.NoOp` (existiert). Compile-Fehler bricht es früh — aber 10-15 Min Debug-Zeit + Wissens-Verbreitung, dass es zwei Namen gibt. Außerdem: zukünftiger Refactor könnte versehentlich `core.ResendAction` mit dem neuen `Action.ResendAction` zusammenführen, ohne zu verstehen, dass es zwei verschiedene Entscheidungs-Domänen sind (Status-Dispatcher vs. Orchestrator-Action).
- **Fix angewandt:** §6.1.3 vor dem `ResendStatusDispatcher`-Patch ein Hinweis-Block "Naming-Kollision (bewusst akzeptiert, dokumentiert)" — beide Sealed-Klassen erklärt, Begründung (verschiedene Packages → Compiler-Type-Mismatch bei Cross-Use), Hinweis dass eine zukünftige Refactor-Iteration den Dispatcher in einen `ResendModule.runEffect`-Pfad integrieren könnte (Phase-2-Backlog).

### F-6 Java-Brücke `DictateUiStateObserver` war zu dünn spezifiziert

- **Severity:** Important
- **Prüf-Achse:** 6 (Java-Brücke), 1 (Migrations-Vollständigkeit)
- **Was:** Spec 1 §4.4 Z. 711 (vor Fix) nannte die Brücke als "vorgesehen analog zu `core/ActiveJobRegistryObserver.kt`, konkrete Implementierung: Block 2". Das war eine Promise, keine Spec. Code-Read der Vorlage `core/ActiveJobRegistryObserver.kt` zeigte: 48 Zeilen Kotlin, `@JvmStatic observe(owner, listener)` mit `repeatOnLifecycle(STARTED)`, `fun interface Listener`. Die Vorlage ist klar; das Plan-Spec-Stub erforderte aber den Implementer, die Vorlage selbst zu adaptieren — ohne Vorgabe für (a) Konsumentenliste, (b) Threading-Vertrag-Doku, (c) Block-2-Acceptance. Außerdem: Phase-A-Inventur §S-3 Migrations-Schwerpunkt "Block 2 muss sie anlegen" — kein Acceptance-Punkt im §10-Block.
- **Konsequenz:** Block-2-Implementer könnte die Java-Brücke vergessen (es gibt keinen Compile-Anker — Java-Konsumenten könnten sich auch alternativ über AsyncTask, Handler etc. einklinken) → die heutigen Java-Sites (`HistoryAdapter.java`, `HistoryDetailActivity.java`, `DictateInputMethodService.java`) würden auf Polling oder ad-hoc-Lösungen ausweichen, statt das saubere StateFlow-Pattern zu adoptieren. Plus: ohne Block-2-Acceptance kein Verifikations-Pfad.
- **Fix angewandt:** Spec 1 §4.4 mit vollständiger `DictateUiStateObserver.kt`-Implementation (50 Zeilen, 1:1-Pattern von `ActiveJobRegistryObserver.kt`), KDoc mit Threading-Vertrag, Lifecycle-Vertrag, Beispiel-Java-Snippet und expliziter Konsumenten-Liste (DictateInputMethodService, HistoryAdapter, HistoryDetailActivity). §10 Block-2-Acceptance um Klausel "Java-Brücke `DictateUiStateObserver`" ergänzt mit Robolectric-Test-Pointer (`DictateUiStateObserverTest.kt`).

### F-7 Spec 2 §11.6 "Callback-Methode am LocalBinder" widerspricht F-8

- **Severity:** Minor
- **Prüf-Achse:** 1 (Action-Hierarchie-Vollständigkeit), 6 (Cross-Spec-Konsistenz)
- **Was:** Spec 2 §11.6 Z. 1852 (vor Fix): "Amplitude/Timer-Hooks: kommen direkt vom AudioModule / RecordingModule (Spec 1 §15) — nicht über `DictateUiState`-Emission … Stattdessen: eigener `StateFlow<AmplitudeTick>` oder simple Callback-Methode am LocalBinder." Aber F-8 hat den LocalBinder auf `state` + `dispatch` geschrumpft (Spec 1 §5 Z. 2200-2222): "**keine typed Forwarder-Methoden** (F-8-Geist)." Eine "Callback-Methode am LocalBinder" wäre genau das — ein typed Forwarder, der ein Lambda registriert. Drift-Spur aus pre-F-8-Spec-Versionen.
- **Konsequenz:** Niedrig, weil Amplitude-Streaming heute kein Refactor-Scope-Item ist (`services.amplitudeStream` läuft Side-Channel). Aber: wenn jemand den Amplitude-Pfad anfasst (z.B. wegen Pulsation-Bug), würde er das Callback-Pattern aus §11.6 nehmen und damit F-8 brechen. Doku-Drift.
- **Fix angewandt:** §11.6 Z. 1852 auf "ein zusätzlicher `StateFlow<AmplitudeTick>` am LocalBinder (analog zu `state: StateFlow<DictateUiState>`, NICHT als Callback-Methode — F-8 verbietet typed Forwarder)" umgestellt, mit FIX-Kommentar und Hinweis, dass Amplitude bis Phase 2 als Side-Channel über `services.amplitudeStream` läuft.

---

## Verifikationen (Code-Reads)

| Plan-Aussage | Verifiziert per | Ergebnis |
|---|---|---|
| `core/ActiveJobRegistryObserver.kt` ist Java-Brücken-Vorlage | Read Z. 1-48 | ✅ 48 Zeilen, `@JvmStatic observe(owner, listener)`, `repeatOnLifecycle(STARTED)`, `fun interface Listener` — Pattern 1:1 portabel |
| `core/ResendStatusDispatcher.kt` definiert `ResendAction` als separate sealed class | Read Z. 1-80 | ✅ `sealed class ResendAction` mit `Insert/Resume/NoOp` im Package `net.devemperor.dictate.core` — kollidiert namens-aber-nicht-typ-mäßig mit `state.Action.ResendAction` |
| `core/PipelineOrchestrator.kt` existiert als heutiger Pipeline-Executor (Surprise-Finding #2) | Read Z. 1-50 + grep | ✅ Klassen-Name kollidiert mit geplanter `state/DictateOrchestrator` nur konzeptuell, nicht in Code — `PipelineOrchestrator` bleibt erhalten (siehe §8 Migrations-Tabelle Z. 3428: "JobExecutor + PipelineOrchestrator bleiben, aber im PipelineService gehalten"). Naming ist verwirrend, aber Plan §8 dokumentiert die Koexistenz. |
| LocalBinder hat heute keinen `dispatch`-Eingang | Verifiziert über Phase-A-Inventur "Heute (Pre-Refactor) gibt es keinen zentralen Action-Bus" | ✅ DictateInputMethodService ruft RecordingStateController.startRecording etc. direkt |
| §11.3.3 BindService-Race "Java-Code ruft dispatch vor onServiceConnected" | Read Spec 1 §11.3.3 Z. 4071-4083 | ✅ Adressed — Same-Process + Main-Looper-Confined → kein Race möglich (Touch-Events und onServiceConnected laufen sequenziell auf Main-Looper) |
| `Action.EffectFailure` hat heute kein Modul-Routing | Spec 2 §3.3 + Spec 1 §4.3 Read | ❌ Bug bestätigt — Top-Level-Action ohne `actionClass`-Owner |
| `Action.KeyboardInputAction` hat heute kein Modul-Routing | Spec 2 §3.3 + Spec 1 §4.8 `DictateModuleRegistry.all` Read | ❌ Bug bestätigt — kein Modul deklariert die actionClass |
| `RecordingModule` hat einen `EffectFailure`-Reducer-Arm? | Read §15.2 reduce-Block | ❌ Nein — alle Reducer-Arme matchen nur auf `Action.RecordingAction.*`, kein `is Action.EffectFailure`. Konsistent mit dem F-1-Fix: reduceFailure-Hook ist optional, RecordingModule muss ihn künftig implementieren wenn Hardware-Recovery gewünscht ist (Block-1b-Acceptance R.RSB-FIX-A deckt das nicht ab — Test-Coverage-Gap, siehe "Offene Fragen") |
| Spec 2 §6 wireStaticHandlers verwendet `slot.actionResolver(s)?.let { onAction?.invoke(it) }` (R.3-konform) | Read Z. 565 | ✅ Konsistent |
| Spec 3 §4.2 OverlayBackend.wireStaticOverlayHandlers verwendet R.3-konform | Read Z. 369-377 | ✅ `slot.actionResolver(s)?.let { onAction?.invoke(it) }` |
| `DictateModuleRegistry.all` Init-Check fängt Doppel-actionClass | Read §4.8 init-Block | ✅ `require(actionClasses.toSet().size == actionClasses.size)` — aber das fängt nur Doppel-Zuordnung, nicht Fehlende-Zuordnung. F-1 + F-2 wären nicht aufgefangen worden, weil EffectFailure + KeyboardInputAction einfach in keiner Liste auftauchen. |
| `services.inputConnectionProvider` ist für IME-Operationen vorhanden | Read §4.7 ModuleServices Z. 832 | ✅ `val inputConnectionProvider: () -> android.view.inputmethod.InputConnection?` |
| `services.clipboard` existiert für CopyToClipboard? | Read §4.7 vor Fix | ❌ Nein — neu hinzugefügt im S-3-Fix |
| `KG-RSB-2-Fix` Self-Filter ist gestrichen (§4.3 Step 5) | Read §4.3 Z. 623-628 (post-Fix) | ✅ Self-Filter `it.id != module.id` ist entfernt; Begründung im Kommentar |

---

## Plan-Edits (Audit-Trail)

| Datei | Sektion | Art | Kurzbeschreibung |
|-------|---------|-----|------------------|
| Spec 2 | §3.3 EffectFailure | Refactor | `originModuleId: ModuleId` als drittes Feld; KDoc dokumentiert Origin-Routing-Konvention (F-1) |
| Spec 2 | §3.3 KeyboardInputAction-Kommentar | Refactor | "kein eigenes Modul"-Hinweis gestrichen; jetzt "KeyboardInputModule — Spec 1 §15.6" (F-2) |
| Spec 2 | §11.6 Click-Listener-Snippet | Fix | `onAction?.invoke(slot.actionResolver(s))` → `slot.actionResolver(s)?.let { onAction?.invoke(it) }` (F-4) |
| Spec 2 | §11.6 Risiko-2 NoOp-Mention | Fix | `Action.NoOp` → `null` (R.3-konform); FIX-Kommentar (F-4) |
| Spec 2 | §11.6 Amplitude-Hook-Vorschlag | Fix | "Callback-Methode am LocalBinder" → "zusätzlicher `StateFlow<AmplitudeTick>` am LocalBinder" (F-7) |
| Spec 1 | §4.2 DictateModule-Interface | Add | Neue Methode `reduceFailure(state, failure, ctx) = null` als Default-Hook (F-1) |
| Spec 1 | §4.2 ModuleId | Add | `data object KeyboardInput` (F-2) |
| Spec 1 | §4.3 DictateOrchestrator | Refactor | Neuer `moduleById`-Index + EffectFailure-Special-Case-Branch in `dispatchInternal`; try-catch in Step 4 setzt `originModuleId` (F-1) |
| Spec 1 | §4.4 DictateUiStateObserver Java-Brücke | Refactor | Vollständige Implementation (50 Zeilen) statt "vorgesehen für Block 2"; Konsumenten-Tabelle; Block-2-Acceptance-Pointer (F-6) |
| Spec 1 | §4.7 ModuleServices | Add | `val clipboard: android.content.ClipboardManager?` als neues Field für KeyboardInputModule.CopyToClipboard-Effect (F-2) |
| Spec 1 | §4.8 DictateModuleRegistry.all | Add | `KeyboardInputModule` in der Liste (F-2) |
| Spec 1 | §6.1.3 ResendAction-Naming-Kollision | Add | Hinweis-Block "core.ResendAction vs. state.Action.ResendAction" mit Begründung und Phase-2-Pfad (F-5) |
| Spec 1 | §9.1 RecordingStateController-Migration | Fix | `ToggleAudioFocus` → `ToggleAudioFocusPref` mit FIX-Kommentar (F-3) |
| Spec 1 | §13.4 DRY-Tabelle AudioFocus-Reaktion | Fix | `ToggleAudioFocus` → `ToggleAudioFocusPref` mit FIX-Kommentar (F-3) |
| Spec 1 | §15.1 Modul-Tabelle | Add | Zeile 13 `KeyboardInputModule`; InterruptionModule rutscht auf #14 (F-2) |
| Spec 1 | §15.6 NEU | Add | Vollständige `KeyboardInputModule`-Implementierung (50+ Zeilen) — DictateModule<Unit, KeyboardInputAction, Effect>, Reducer 1:1-Effect-Mapping, runEffect via inputConnectionProvider + clipboard (F-2) |
| Spec 1 | §10 Block-2-Acceptance | Add | 3 neue Klauseln: Java-Brücke, KeyboardInputModule, EffectFailure-Origin-Routing (F-1+F-2+F-6) |
| Hauptplan | §9 Iter-Log | Add | Phase-B Quality-Gate S-3 Eintrag (2026-05-13) — 7-Findings-Summary; chronologisch nach S-2 platziert |

**Gesamt:** 18 Edit-Operationen in 2 Dateien (Spec 1: 13, Spec 2: 4, Hauptplan: 1). Spec 3 unverändert — S-3-Action-Hierarchie ist Spec-2-SoT; die Spec-3-Konsumenten (§4.2 OverlayBackend, §4.8 OverlayModule) verwenden die Hierarchie konsistent (R.3-Resolver + sealed Action.OverlayAction-Reducer-Arme).

---

## Offene Fragen für nachfolgende Agents

### Für S-4 (Pipeline-Orchestrierung)
- Die `reduceFailure(state, failure, ctx) = null`-Default-Implementation im `DictateModule`-Interface (§4.2, Phase-B S-3-Add) bedeutet: Module ohne expliziten Failure-Pfad lassen `EffectFailure` in `DispatchOutcome.Rejected("reducer-null")` enden. Für die meisten trivialen Module (Theming, Layout, FeatureToggle, Language, KeyboardInput) ist das OK — sie haben keine recoverable States. Aber `RecordingModule` und `PipelineModule` haben Hardware-Resources, die im Failure-Fall released werden müssen. S-4 sollte verifizieren, dass:
  - `RecordingModule.reduceFailure` für `Effect.AllocateMediaRecorder`-Failure ein `Preparing → Idle` + Hardware-Release-Effect macht (sonst hängt der State im Preparing).
  - `PipelineModule.reduceFailure` für `Effect.PersistStatus`-Failure (S-2 Phase-B-Fix-Kontext) ein passendes Rollback macht (z.B. `Running → Failed` mit lastErrorMessage).
  - Test-Coverage: `RecordingModuleFailureTest.kt`, `PipelineModuleFailureTest.kt` in §10 Block-1b/Block-2-Acceptance ergänzen, falls noch nicht vorhanden.
- Das `moduleById`-Map ist ein neuer sekundärer Index im Orchestrator. Die `DictateModuleRegistry.all`-Init-Check (§4.8) validiert `ids.toSet().size == ids.size` bereits — das deckt den moduleById-Lookup automatisch ab (kein Doppel-Eintrag möglich). S-4 sollte sicherstellen, dass kein zukünftiger Refactor den Init-Check entfernt — sonst wäre `moduleById[duplicateId]` non-deterministic.

### Für S-5 (Service-Schicht)
- `ModuleServices.clipboard` (neu in §4.7, Phase-B S-3-Fix) wird im Service-onCreate gesetzt: `clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager`. S-5 sollte verifizieren, dass das Wiring im `ModuleServicesFactory` (§4.11.5.3 Service-Wiring-Snippet) tatsächlich gemacht wird — sonst ist `clipboard = null` zur Laufzeit und `CopyToClipboard`-Effect ist still-no-op.

### Für S-6 (LayoutCatalog) + S-8 (OverlayBackend)
- Die Cross-Spec-Resolver in Spec 2 §13 und Spec 3 §4.2/§4.8 verwenden korrekt das R.3-nullable-Resolver-Idiom (verifiziert in beiden Specs). S-6 + S-8 müssen beim Block-5/Block-6-Implementation dafür sorgen, dass keine Migration-Iteration `Action.NoOp` wieder einführt (Drift-Risiko, falls jemand "altes Pattern" recyclen will).
- Spec 2 §11.6 (NUR Empfehlungs-Doku, kein Code-Snippet) verwendet jetzt das R.3-konsistente Pattern. Falls S-6 die Empfehlung in Block-5 als Vorlage nimmt, ist sie jetzt direkt portable.

### Für S-7 (Audio-File-Management) + S-9 (ResetSuppressBit-Lifecycle)
- Keine direkten S-3-Touchpoints. Aber: der `RecordingModule.onCrossModuleStateChange`-ResetSuppressBit-Cascade (§15.2) hängt von einem korrekt geroutete `OverlayAction.ResetSuppressBit`-Action ab — das funktioniert nach S-3-Fix unverändert, weil `OverlayModule.actionClass = Action.OverlayAction::class` und der reguläre `moduleByLeafClass`-Lookup-Pfad nicht angetastet wurde. S-9 kann den `R.RSB-FIX-A`-Regression-Test wie geplant umsetzen.

### Für S-5 + S-8 (Permission-Achse — KG-RSB-1-Kontext)
- Der S-1-Report hat eine offene Frage für S-8 verzeichnet: ob `OverlayPermissionObserver.attach(store)` synchron im Orchestrator-`init` aufgerufen werden sollte (analog zu PipelinePrefMirror), um den `state.overlay.hasPermission`-Initial-Race zu vermeiden. S-3 berührt das nicht — aber S-3's Fix für die Java-Brücke (`DictateUiStateObserver`) gibt S-8 einen sauberen Ankerpunkt: der OverlayPermissionObserver kann seinen synchronen Initial-Read im Orchestrator-`init` machen (eine Action.OverlayAction.OnOverlayPermissionChanged(initial) dispatchen, bevor Subscriber attached werden — analog zur Initial-State-Race-Fence in S-1).

### Cross-Cutting (alle weiteren Agents)
- Die `reduceFailure`-Method ist optional (Default `null`). Beim Block-Validate in Phase-3 sollten alle Module mit Hardware-Resources einen expliziten `reduceFailure`-Arm haben. Ein nachfolgender Audit-Pass könnte einen Convention-Check schreiben: "wenn `runEffect` IO/Hardware macht, MUSS `reduceFailure` definiert sein, sonst Compile-Warning." Aktuell ist das Code-Review-Pflicht.
- Die `ResendAction`-Namens-Kollision (F-5) ist bewusst akzeptiert. Beim ResendModule-Implementation (Block 1b) muss der Implementer einen Import-Disambiguator setzen (`import net.devemperor.dictate.state.Action.ResendAction` vs. `import net.devemperor.dictate.core.ResendAction as CoreResendAction`). Das ist nicht im Plan-Body dokumentiert — aber durch das §6.1.3 Naming-Kollisions-Hinweis-Block sollte ein Implementer es erkennen.

---
