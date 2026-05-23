# Phase C-1 — State-Modell + Modul-System Kohärenz-Review

**Erstellt:** 2026-05-14
**Reviewer:** Phase-C-Agent C-1
**Plan-Version vor Edits:** Commit `dca8cc4` (Phase-B abgeschlossen, 9 Subsystem-Reports + ~132 Plan-Edits)
**Scope:** Spec 1 §3 (Daten-Modell), §4 (Orchestrator + Modul-System), §5 (LocalBinder), §15 (Modul-Inventar)
**Cross-Spec-Verifikation:** Spec 2 §3.3 (Action-Sealed-Hierarchie, ViewModeAction), Spec 3 nur referenziert wo §15-Cross-Module-Cascades dort landen

---

## Summary

Der Bereich ist nach allen Phase-B-Edits **architektonisch geschlossen und tragfähig**: Sub-State-Klassen + immutable + PersistentList-Idiom sind konsistent, der DictateModule-Interface-Vertrag inklusive Phase-B-Erweiterungen (`reduceFailure` für EffectFailure-Origin-Routing, `prefBindings()`/`terminate()` als optionale Hooks) ist sauber, Cascade-Mechanik mit MAX_CASCADE_DEPTH + ASCII-Box-Banner gegen Re-Add-Self-Filter + frozen-snapshot-Argument ist robust dokumentiert, ProGuard-Keep-Regel + Init-Sanity-Check-Erweiterung (Vollständigkeits-Check) schließen die in S-4 + S-3 identifizierten Reflection-Time-Bombs. Inkonsistenz-Cluster sind **Doku-Drift-bedingt**: KeyboardInputModule (Phase-B S-3-Ergänzung) wurde nicht synchron in alle "Anzahl Module"-Lese-Anchor gezogen, und ein Phase-B-S-4-Apply hat einen Markdown-Prosa-Block in der Mitte des §15.2-Kotlin-Code-Blocks belassen ohne Fence-Closure. **9 Findings (2 Critical, 4 Important, 3 Minor); 14 Plan-Edits** (alle in Spec 1: `1-pipeline-service.reviewed.md`; plus 1 Iter-Log-Eintrag im Hauptplan).

---

## Findings + Applied Fixes

### F-1 (CRITICAL) — §15.2 RecordingModule: Markdown-Prosa innerhalb des Kotlin-Code-Blocks

**Symptom:** Phase-B S-4 hat zwischen dem `reduce(...)`-Block und dem `runEffect(...)`-Block den Erklärungstext "**audioFile-Vertrag (R.2):** …" + "**Konsistenz der drei AllocateMediaRecorder-Sites (Phase-B S-4):**" + 3-Punkte-Liste eingefügt, ohne den umschließenden ` ```kotlin `-Fence zu schließen. Der Fence startet bei §15.2-Block-Beginn und schließt erst nach dem schließenden `}` des `RecordingModule`-Singletons.

**Folge:** Markdown rendert die `**`-Marker, die `1.`/`2.`/`3.`-Bullets und die "Konsistenz der drei AllocateMediaRecorder-Sites"-Heading **als Kotlin-Text-Literal** mit Syntax-Highlighting — dem Reader präsentiert sich Code, der so im Kotlin-Compiler nicht parsen würde (`**foo**` ist kein Kotlin-Operator).

**Fix:** Prosa aus der Mitte des Code-Blocks ausgelagert (FIX-Marker-Kommentar als Anker für die Position-Konsistenz mit dem Reducer/runEffect-Boundary belassen); identischer Inhalt nach dem schließenden ` ``` ` von §15.2 als regulärer Markdown-Prosa-Block wiederholt (mit Phase-C-FIX-Kommentar dokumentiert).

**Edit:** §15.2 Lines 6275–6293 (Code-Block) + neue Prosa-Sektion zwischen §15.2 schließendem Fence und der nachfolgenden "Cascade-Reihenfolge bei StartRecording"-Sektion.

---

### F-2 (CRITICAL) — KeyboardInputModule fehlt im §4.1-Architektur-Tree

**Symptom:** §15.1-Modul-Tabelle führt KeyboardInputModule als 13. aktives Modul (#13, Phase-B S-3-Ergänzung); §4.8 `DictateModuleRegistry.all` listet `KeyboardInputModule` zwischen `PendingSessionsModule` und `// InterruptionModule (Phase 2 — auskommentiert bis aktiv)`. §15.6 enthält die kanonische Implementierung. **Aber:** §4.1 "Architektur-Übersicht (Modular Orchestrator Pattern)" zeigt einen Tree-Diagramm der Module unter `DictateOrchestrator` — und in diesem Diagramm fehlt KeyboardInputModule. Caption sagt "13 Module" (was die alte Pre-S-3-Zählung war).

**Folge:** Bug-Klasse identisch zu Phase-B S-3 F-2 vor Fix: ein Implementer, der §4.1 als Übersicht nutzt ("welche Module muss ich anlegen?"), würde Backspace/Enter/Space-Klicks ohne Modul-Owner haben — `moduleByLeafClass[KeyboardInputAction.Backspace::class]` würde `null` zurückgeben → `DispatchOutcome.Unrouted` → silent-no-op. S-3 hat das durch Phase-B-Init-Sanity-Check-Erweiterung in §4.8 strukturell gefangen, aber die Lese-Anchor-Konsistenz in §4.1 blieb offen.

**Fix:** KeyboardInputModule im §4.1-Tree als 13. aktives Modul vor InterruptionModule eingefügt, mit Inline-Cross-Link auf §15.6 + Beschreibung "(IME-Direkteingaben Backspace/Enter/Space/CopyToClipboard — Unit-State, §15.6)". Tree-Caption auf "14 Module (13 aktiv + 1 Phase-2-Stub)" umgestellt.

---

### F-3 (IMPORTANT) — Modul-Zähler-Drift in 6+ weiteren Plan-Stellen

**Symptom:** Nach KeyboardInputModule-Ergänzung sind diverse Plan-Stellen mit dem alten "12 aktive Module"-Zähler nicht synchron:
- §1 Scope-Aufzählung (Z. 17)
- §4.2 Interface-Intro (Z. 404)
- §7.1 Service-Struktur-Tree (`DictateModuleRegistry.all`-Annotation Z. 3675)
- §11.2.2 Block-1b-Header (Z. 4585) + Sub-Schritt 1 (Sub-State-Klassen-Zähler) + Sub-Schritt 7 (PrefMirror)
- §11 Block-1b-Acceptance-Header (Z. 4229)
- §13.3.12 ISP-Block + §13.3.13 DIP-Block
- §13.3-Header-Block ("13 Module")
- §15-Intro (Modul-Inventar)

Drift verwirrt Reviewer und produziert Inkonsistenz-Findings in zukünftigen Audits.

**Fix:** Alle Sites auf "13 aktive Module (+1 Phase-2-Stub)" homogenisiert. §11.2.2 Schritt 1 (Sub-State-Felder-Zähler) zusätzlich auf "12 Sub-State-Typen + `pendingSessions: PersistentList<>`-Feld + Top-Level-Bool" präzisiert (vorher unterschlug `pendingSessions`). §11.2.2 Schritt 7 PrefMirror-Zähler von "15 Prefs" auf "19 Prefs" korrigiert (§4.5-`initialMirror`-Block listet exakt 19: layout 3 + audio 3 + resend 1 + features 4 + theming 4 + overlay 4 = 19; konsistent mit Phase-B-S-4-Callout "19 Prefs").

---

### F-4 (IMPORTANT) — DictateModule-Interface-Methoden-Zähler stale in §13.3.12 + §15.7

**Symptom:** §13.3.12 ISP-Block ("Minimal: 5 Methoden + 1 optionale. Keine Methode, die ein Modul nicht braucht.") + §15.7 SOLID-Verifikation-ISP-Zeile ("DictateModule-Interface ist minimal (5 Pflicht-Methoden + 1 optional)"). Beide stammen aus Pre-Phase-B-Zustand.

**Faktischer Stand:** Nach Phase-B S-3 (`reduceFailure` default null) + User-Decision-Apply 2026-05-10 (Issue 2.1.2 `prefBindings()` default empty + Issue 2.1.12 `terminate()` default `Unit`) hat das Interface jetzt:
- **7 Pflicht-Methoden:** `id, actionClass, read, write, initialState, reduce, runEffect`
- **4 optionale Default-Hooks:** `reduceFailure, onCrossModuleStateChange, prefBindings, terminate`

**Folge:** ein Reviewer, der §13.3.12 oder §15.7 als Lese-Anchor nutzt, hätte ein falsches Interface-Surface-Bild ("5 + 1 = 6 Methoden"). Beim Implementieren eines neuen Moduls würde er sich an §4.2 orientieren müssen — wo der vollständige Vertrag steht — die Audit-Sektionen wären als Faulheits-Falle blind.

**Fix:** §13.3.12 + §15.7 auf "7 Pflicht-Methoden + 4 optionale Default-Hooks" umgestellt; Methoden-Namen explizit aufgezählt.

---

### F-5 (IMPORTANT) — Phase-B-Cross-Links via Zeilennummer brechen nach späteren Edits

**Symptom:** Phase-B hat in mehreren FIX-Kommentaren Cross-Links der Form Zeilennummer gesetzt:
- §4.2 `reduceFailure`-KDoc: "Spec 1 §4.3 Z. 617"
- §4.3-Cascade-Order-Block: "§4.8 Z. 1017–1033"
- §4.3-ProGuard-Block: "`collectLeaves` (Z. 587–589)"
- §4.5 Phase-1-Hinweis: "§4.2 Z. 462"
- §4.11.5.1 Reihenfolge-Invariante: "§4.3 Z. 567–570"
- §10 Acceptance Phase-B-S-4-ProGuard: "§4.3 (Z. ~590 Hinweis-Block)"
- §10 Acceptance Phase-B-S-3-EffectFailure: "(§4.3 Z. 617)"

Nach den Phase-B-Apply-Pässen (Cascade-Order-Block + ProGuard-Block + KeyboardInput-Ergänzung + AllocateMediaRecorder-3-Arg-Fix + reduceFailure-Hook) sind alle Zeilennummern verschoben — typischer Versatz +50 bis +130 Zeilen.

**Folge:** ein Reviewer folgt der Z.-Referenz, landet im falschen Code-Snippet, schließt auf "Plan ist inkonsistent". Bug-Klasse identisch für Phase-C (jetzt) und potenziell weitere Phase-D-Reviews.

**Fix:** Cross-Links auf Section-Anchor-Form umgestellt. Vorher / Nachher:
- "Spec 1 §4.3 Z. 617" → "Spec 1 §4.3, EffectFailure-Pfad `dispatchInternal` Step 1a + 2"
- "§4.8 Z. 1017–1033" → "§4.8 `modules`-Liste"
- "`collectLeaves` (Z. 587–589)" → "`collectLeaves` (siehe `DictateOrchestrator`-Body)"
- "§4.2 Z. 462" → "§4.2 `prefBindings()`-Hook"
- "§4.3 Z. 567–570" → "§4.3 `DictateOrchestrator`-Konstruktor"
- "§4.3 (Z. ~590 Hinweis-Block)" → "§4.3 (ProGuard-Hinweis-Block direkt unter `DictateOrchestrator`-Snippet)"
- "(§4.3 Z. 617)" → "(§4.3 `dispatchInternal` Step 1a + 2)"

Anchor-Refs überleben spätere Refactorings; Z.-Refs nicht. **Hinweis für nachfolgende Phase-C-Agents (C-2 bis C-5, C-State):** falls weitere Z.-Refs auftauchen, gleiches Anchor-Pattern anwenden.

---

### F-6 (IMPORTANT) — §5 LocalBinder.dispatch-Return-Type `DispatchOutcome` nicht im KDoc

**Symptom:** `fun dispatch(action: Action) = orchestrator.dispatch(action)` (§5, Z. 2636 vor Fix) inferiert den Return-Type implizit aus dem Orchestrator-Vertrag (= `DispatchOutcome`, sealed mit Applied | Rejected | Unrouted). Der KDoc beschreibt aber nur die Eingabe-Semantik ("Single Dispatch — der einzige öffentliche Eingang … via `Action.ViewModeAction.OnImeViewShown / OnImeViewHidden`"). Weder Methoden-Header noch KDoc verraten, dass ein Outcome zurückkommt.

**Folge:** ein IME-Implementer, der `pipeline?.dispatch(Action.X)` ruft (Code-Beispiele Z. 2648 + 2668), sieht keinen Hinweis — typische Quelle für stille Rejected/Unrouted-Bugs (Action wird gedropped, niemand merkt es). Speziell für `Action.ViewModeAction.OnImeViewShown` wäre `Unrouted` ein Symptom für "ViewModeModule fehlt im Registry"; ein Implementer sähe keinen Unterschied zwischen "Action wurde verarbeitet" und "Action wurde gedropped".

**Fix:** Return-Type explizit als `: DispatchOutcome` im LocalBinder-Snippet ergänzt; KDoc-Absatz dokumentiert: "Return: `DispatchOutcome` (siehe §4.3). IME-Konsumenten dürfen den Wert ignorieren … `Rejected`/`Unrouted` sind Phase-1-Telemetry-Signale und brechen die UI nicht — der Orchestrator loggt sie bereits."

Phase-2-Backlog (nicht in diesem Edit): Verifikation eines Tests, der `DispatchOutcome.Unrouted` als TODO-Klausel in §10-Acceptance verankert ("Block-2-Acceptance: keine `pipeline?.dispatch(...)`-Site in `DictateInputMethodService.java` ist gegen Unrouted abgesichert; ein Lint-Check für unused-DispatchOutcome ist Phase-2-Backlog").

---

### F-7 (MINOR) — §15.1.x Coupling-Matrix ohne Caption-Hinweis auf KeyboardInputModule-Absenz

**Symptom:** Die Coupling-Matrix in §15.1.x hat 13 Zeilen + 13 Spalten (Recording … Interruption). KeyboardInputModule erscheint nicht in der Matrix. §15.6-Schluss-Absatz dokumentiert die Absenz ("Coupling-Matrix-Zeile/Spalte KeyboardInput bleibt leer"), aber die Matrix selbst hat keinen Caption-Hinweis. Ein Reviewer, der nur §15.1.x liest und KeyboardInputModule aus §15.1-Tabelle kennt, sucht nach der Zeile und schreibt potenziell ein "fehlende Zeile/Spalte"-False-Positive.

**Fix:** Caption-Block direkt unter der Matrix dokumentiert die bewusste Auslassung + verweist auf §15.6-Schluss-Absatz: "**Matrix-Caption:** Die Matrix listet ausschließlich Module mit eigener State-Achse (13 Zeilen/Spalten oben + Diagonale). KeyboardInputModule (§15.6) erscheint bewusst NICHT — Unit-State, kein Observer-Hook, kein Inbound-Coupling. Die F-8-Single-Dispatch-Garantie genügt; eine 14×14-Matrix mit einer leeren Zeile + leeren Spalte wäre Noise."

---

### F-8 (MINOR) — §3 "13 Achsen" vs. §15 "14 Module" wirkt off-by-one ohne Klarstellung

**Symptom:** §3-Tabelle Z. 304 (nach FIX-Anchor) zählt 13 Sub-State-Felder im DictateUiState. §15-Intro (nach Fix von F-3 oben) sagt "14 Module (13 aktiv + 1 Phase-2-Stub)". Differenz: KeyboardInputModule hat eine Modul-Definition (§15.6, Unit-State), aber keine eigene State-Achse. Beide Zahlen sind korrekt für ihr jeweiliges Counting-Schema (Felder im DictateUiState vs. Module-Singletons).

**Folge:** ohne Klarstellung wirkt das wie "13 vs. 14 — was stimmt jetzt?". Ein nachfolgender Phase-C-Agent (C-2 bis C-5) könnte einen False-Positive-Finding "Zähler-Inkonsistenz" produzieren.

**Fix:** §15-Intro um Klarstellung ergänzt: "Off-by-One-Klarstellung gegen §3: KeyboardInputModule hat KEINE eigene State-Achse (Unit-State, §15.6) — daher 14 Module, aber nur 13 Sub-State-Felder im DictateUiState (§3-Tabelle zeigt 13 Achsen + Top-Level-Bool). Beide Zahlen sind korrekt für ihr Schema."

---

### F-9 (MINOR) — §11.2.2 Block-1b Sub-Schritt 1 unterschlägt `pendingSessions` im Sub-State-Klassen-Zähler

**Symptom:** "**DictateUiState-Datentyp anlegen** — pure Daten-Klasse mit 12 Sub-State-Klassen (`AudioState`, `LayoutState`, …) plus 1 Top-Level-Bool (`lastResultNeedsManualPaste`)." Zählt: 12 Sub-State-Wrapper-Klassen + 1 Top-Level-Bool. **Unterschlagen:** `pendingSessions: PersistentList<PendingSession>` — eine eigene State-Achse (§3-Tabelle Zeile 12), die KEIN Wrapper-Typ ist, sondern ein direktes Feld.

**Folge:** ein Implementer denkt "12 Klassen + 1 Bool anlegen" und übersieht das `pendingSessions`-Feld, das im DictateUiState als drittletztes Feld (vor `lastResultNeedsManualPaste` und vor `interruption?`) erscheint.

**Fix:** Schritt 1 präzisiert auf "12 Sub-State-Typen (`RecordingState` sealed, `PipelineUiState` sealed, `AudioState`/`LayoutState`/… data classes) + `pendingSessions: PersistentList<PendingSession>` als 13. Achse + 1 Top-Level-Bool (`lastResultNeedsManualPaste`)".

---

## Plan-Edits (Audit-Trail)

| Datei | Sektion | Art | Kurzbeschreibung |
|---|---|---|---|
| Spec 1 §15.2 | RecordingModule-Code-Block | Refactor | Markdown-Prosa "audioFile-Vertrag" + "Konsistenz der drei AllocateMediaRecorder-Sites" aus der Mitte des Kotlin-Codes ausgelagert hinter den schließenden Fence (F-1) |
| Spec 1 §4.1 | Architektur-Übersicht-Tree | Insert | KeyboardInputModule als 13. aktives Modul vor InterruptionModule eingefügt; Tree-Caption auf "14 Module (13 aktiv + 1 Phase-2-Stub)" umgestellt (F-2) |
| Spec 1 §1 | Scope-Aufzählung | Update | "12 aktive Module" → "13 aktive Module" (F-3) |
| Spec 1 §4.2 | Interface-Intro | Update | "Jedes der 13 Module" → "Jedes der 13 aktiven Module" (F-3) |
| Spec 1 §7.1 | Service-Struktur-Tree | Update | "12 aktive Module" → "13 aktive Module (+ KeyboardInputModule §15.6)" (F-3) |
| Spec 1 §11.2.2 | Block-1b-Header + Schritte 1 + 7 | Update | Block-1b-Header 12 → 13 aktive; Schritt 1 `pendingSessions` explizit benannt; Schritt 7 PrefMirror 15 → 19 Prefs (F-3 + F-9) |
| Spec 1 §11 | Block-1b-Acceptance-Header | Update | "12 aktive Module" → "13 aktive Module" (F-3) |
| Spec 1 §13.3 | Header-Block | Update | "13 Module" → "13 aktive Module (+ 1 Phase-2-Stub)" (F-3) |
| Spec 1 §13.3.12 | ISP-Block | Update | "5 Pflicht + 1 optional" → "7 Pflicht + 4 optionale Default-Hooks" mit Methoden-Aufzählung (F-4) |
| Spec 1 §13.3.13 | DIP-Block | Update | "13 Module" → "13 aktive Module (+ 1 Phase-2-Stub)" (F-3) |
| Spec 1 §15 | Modul-Inventar-Intro | Update | "13 Module (12 + 1)" → "14 Module (13 + 1)" + Off-by-One-Klarstellung gegen §3 (F-3 + F-8) |
| Spec 1 §15.1.x | Coupling-Matrix-Caption | Insert | Caption-Block dokumentiert bewusste KeyboardInput-Auslassung (F-7) |
| Spec 1 §15.7 | SOLID-ISP-Zeile | Update | "5 + 1 Methoden" → "7 + 4 Methoden" (F-4) |
| Spec 1 §5 | LocalBinder.dispatch | Update | Return-Type `: DispatchOutcome` explizit + KDoc-Absatz zu Outcome-Verarbeitung (F-6) |
| Spec 1 §4.2 + §4.3 + §4.5 + §4.11.5.1 + §10 | Phase-B-Cross-Links | Refactor | 6 Z.-Refs auf Section-Anchor-Form umgestellt (F-5) |
| Hauptplan §9 | Iteration-Log | Insert | "2026-05-14 — Phase-C Quality-Gate C-1"-Entry mit 9 Findings + Plan-Edits-Summary |

**Gesamt:** 16 Operations in 2 Dateien (Spec 1: 15, Hauptplan: 1). Spec 2 + Spec 3 unverändert — der C-1-Scope (State-Modell + Modul-System) ist Spec-1-zentral; Cross-Spec-Verweise sind unidirektional (Spec 2 §3.3 wird gelesen, nicht editiert; Spec 3 nur über §15.1.x-Coupling-Matrix referenziert, kein Inhalt-Drift entdeckt).

---

## Offene Fragen für nachfolgende Agents

### Für C-2 (Action-Hierarchie + Dispatch + EffectFailure)

- **F-5-Pattern fortsetzen:** Falls C-2 weitere Z.-Refs in §4.3 + Spec 2 §3.3 entdeckt (z.B. Phase-B-S-3-Apply-Kommentare mit Zeilennummern auf Action-Hierarchie), gleiches Anchor-Pattern anwenden (Methoden-/Sektionsname statt Z.).
- **F-6-Pattern (Return-Type-Doku):** Falls C-2 die Action-Dispatch-Pfade in Spec 2 (Slot-Resolver, `actionResolver: (DictateUiState) -> Action?`) prüft, prüfen ob die Resolver-`null`-Semantik klar ist (kein DispatchOutcome.Unrouted-Bug-Risiko, weil der Resolver vor `dispatch` aussortiert).

### Für C-3 (Layout/View-Rendering)

- §11.2.2 Schritt 6 nennt "LayoutModule implementieren — `KeyboardStateManager.contentArea/isSmallMode` wandern in `LayoutState`". Prüfen: wird der Atomar-Vertrag (siehe Block-1b-Acceptance "Atomarität setSmallMode") in der LayoutModule-Implementations-Stelle (Spec 2) korrekt reflektiert?

### Für C-4 (Persistenz + Recovery + Pref-Mirror)

- F-3-Pref-Zähler "19 Prefs" sollte mit §4.5 `initialMirror` + `sync`-Block exakt match — falls C-4 die Pref-Tabelle in §11.7 cross-checked, dort denselben Zähler verifizieren.
- §4.5 Phase-1 vs. Phase-2 `prefBindings()`-Hinweis-Block ist deutlich; falls C-4 die `prefBindings()`-Migration in Phase 2 detaillierter ausarbeitet (Hauptplan §7.1 Out-of-Scope), die Dead-Code-Klausel "Module dürfen `prefBindings()` in Phase 1 NICHT befüllen" beachten.

### Für C-5 (Service-Lifecycle + Notification + Action-Router)

- §4.11.5.1 Service-onCreate-Sequenz (12 Schritte) + §7.3 Service-Wiring-Snippet: prüfen, ob die "Schritt 5 garantiert prefMirror.attach vor recovery.recover"-Invariante (§4.3 `DictateOrchestrator`-Konstruktor) im Snippet korrekt reflektiert ist — eventuell mit Mock-Service-Lifecycle-Test cross-validieren (Block-2-Acceptance "Phase-B S-4 shutdown-Order" ist verwandt).

### Für C-State (State-File-Konsistenz)

- Plan-State-File (falls vorhanden in `~/.claude/plans/`) sollte den neuen Modul-Zähler (13 aktiv + 1 Phase-2) reflektieren — beim Phase-2/Phase-5-Plan-Archive-Schritt.

---

**Reviewer-Note:** Phase C-1 hat das C-Achsen-Mandat ("HORIZONTAL pro Plan-Bereich") erfüllt: keine Migrations-Vertikalen erneut geprüft (Phase B abgeschlossen), nur Plan-Innenkohärenz + Clean Code + DRY + SOLID + Bug-Risiken nach Phase-B-Apply. Hauptcluster der Findings: **Phase-B-Lese-Anchor-Drift** (Counter-Sites + Z.-Refs nach S-3-/S-4-Apply nicht synchron gezogen) und **eine versehentliche Markdown/Code-Fence-Verletzung** in §15.2. Beide Cluster sind nicht-Architektur-blockierend, aber Plan-Lesbarkeit-blockierend; nach den 16 Edits ist der Bereich für die Implementer-Phase reif.
