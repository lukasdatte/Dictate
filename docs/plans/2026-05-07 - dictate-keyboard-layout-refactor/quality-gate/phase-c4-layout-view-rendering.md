# Phase C-4 — Layout / View-Rendering Kohärenz-Review

**Erstellt:** 2026-05-14
**Reviewer:** Phase-C-Agent C-4
**Plan-Version vor Edits:** Commit `2a032e3` (Phase-C-1) + Phase-C-2-Apply (10 Plan-Edits in Spec 1 §6+§7+§11) + Phase-C-3-Apply (11 Plan-Edits in Spec 1 §7.3/§10/§13.5/§15.2 + Spec 2 §3.2/§3.3/§6/§8.4/§9.6)
**Scope:** Spec 2 §1–§14 (Keyboard-Layout-Renderer + LayoutCatalog + ImeViewBackend + MotionScene-XML + Migrations-Tabellen + Acceptance + Tests), Cross-Spec-Verifikation gegen Spec 1 §4.1 + §15.1 (LayoutModule + ViewModeModule Modul-Inventar) + §15.5 (Cross-Module-Effect-Modi) + §11.2.2 Schritt 6 (LayoutModule-Atomar-Vertrag) + Spec 1 §5 (LocalBinder.state-Naming) + Spec 3 §3.1 (`OVERLAY_5BUTTON`-Deklaration).

**Cross-Spec-Verifikation:** **Pflicht** — C-4-Scope ist eng cross-spec verzahnt:
- Action-Hierarchie kommt aus Spec 2 §3.3 (in Spec 2 selbst SoT) — verbraucht in Spec 1 §15 + Spec 3 §3/§4/§7.
- State-Pfade (`state.audio.X`, `state.layout.X`, `state.resend.X`, `state.recording.X`, `state.pipeline.X`) sind in Spec 1 §3 deklariert — Spec 2 §6 + §8 referenziert sie überall.
- `LocalBinder`-API: Spec 1 §5 (post-F-8, nur `state` + `dispatch`) ist SoT — Spec 2 muss konsistent referenzieren.
- `LayoutCatalog.OVERLAY_5BUTTON`-Cross-Spec-Member: Spec 2 §4 + Spec 3 §11/§14 referenzieren als Catalog-Member, Spec 3 §3.1 deklariert es als Top-Level-`object`. Doppel-Truth.

**Vorgänger-Anker:**
- **C-1** hat den Resolver-`null`-Semantik-Hinweis an C-3 weitergereicht (F-6 offene Frage) — C-3 hat den in Spec 2 §3.2 + §6 verankert.
- **C-1** hat die Atomar-Vertrag-Prüfung an C-4 (damals "C-3 Layout/View-Rendering") weitergereicht — siehe F-4 unten.
- **C-2** hat den F-7-NOTIF_ID-Pfad in Spec 1 vollständig homogenisiert; C-4 hat in Spec 2 KEINE NOTIF_ID-Refs gefunden (NOTIF_ID ist Service-Layer-Internas, kein UI-Layer-Konzern).
- **C-3** hat die Cross-Spec-Resolver-`null`-Semantik in Spec 2 §3.2 + §6 verankert; C-4 hat die State-Pfade + Mode-3-Verstöße in Spec 2 geprüft und keine Mode-3-Verletzung gefunden (Spec 2 enthält keine Reducer-Code; alle State-Mutationen laufen über `Action.LayoutAction.*` → `LayoutModule.reduce` in Spec 1 §15.1, der **eine** Sub-State-Achse `state.layout` mutiert).

---

## Summary

Der Layout-/View-Rendering-Bereich (Spec 2) ist nach allen Phase-B + Phase-C-1/C-2/C-3-Edits **architektonisch tragfähig** (LogicalButtonId-Mapping, MotionLayout-Container, ButtonSlot-Resolver-Pattern, SoT-Visibility-via-Predicates, geteilter `applySlotToView`-Helper sind robust; Mode-3-Verstöße in Spec 2 strukturell unmöglich, weil Spec 2 keine Reducer-Code enthält und das `KeyboardLayoutManager` per §4.1-Vertrag explizit "niemals direkt `store.update` ruft"). Hauptcluster:

1. **Test-Snippet-Compile-Error-Cluster in §14.2** (Critical): das `predResendVisible`-Beispiel-Test-Snippet enthielt VIER eigenständige Bugs in einem 6-Zeilen-Block — zwei falsche Konstruktor-Signaturen (`RecordingState.Active(false)` statt 2-arg; `PipelineUiState.Preparing` statt `Preparing("test")`) und zwei flache State-Pfade (`base.copy(lastAudioExists = false)` statt `base.copy(resend = base.resend.copy(lastAudioExists = false))`). Bug-Klasse: Test-Vorlage wäre als Copy-Source für die ganze parametrisierte VisibilityMatrixTest-Suite genutzt worden, die vier Bugs hätten sich in 25+ Cases vervielfacht. Klassische AI-1-Phase-A-Drift, die in den Plan-Body-Sektionen vollständig geputzt war, aber in den Test-Snippets unbemerkt blieb.

2. **F-11/G2-Naming-Drift-Echo `pipelineService` → `pipeline` (LocalBinder)** (Important): drei Sites in Spec 2 (§2 L6, §10 Acceptance Block 4, §11.8 Migration-Reihenfolge-Snippet) nutzen noch die Pre-F-11-Form `pipelineService.state` für die IME-Seite des Service-Binders. Spec 1 §5 (LocalBinder.state) ist SoT; IME-Side-Snippet (`pipeline!!.state.collect`) ist die kanonische Form.

3. **F-8/F-11-Drift in §13.1 Zeile 28** (Important): die `resendButton`-Mutation-Tabelle behauptet `pipelineService.markLastAudioExists(true)` als Ziel — aber F-8 LocalBinder-API erlaubt **nur** `state` + `dispatch`, keine typed Forwarder. §9.6 Zeile (dieselbe Migration!) dokumentiert die korrekte Form: `orchestrator.dispatch(Action.ResendAction.MarkLastAudio(exists = true))`. Internal-§13.1-vs-§9.6-Inkonsistenz.

4. **C-1-Offene-Frage aufgelöst: Atomar-Vertrag setSmallMode in Spec 2 nicht gespiegelt** (Important): C-1 hatte explizit an C-3/C-4 weitergegeben, ob die LayoutModule-Atomar-Vertrags-Klausel (Block-1b-Acceptance "Atomarität setSmallMode" in Spec 1 §11.2.2 Schritt 6 + §10 Acceptance) in Spec 2 §4.1 (KeyboardLayoutManager ↔ LayoutModule Beziehungs-Section) reflektiert ist. Antwort: war nicht — Spec 2 §4.1 hatte den Vertrag "Mutationen an state.layout gehen IMMER durch Action.LayoutAction.*", aber kein Cross-Link zur Atomarität (kritisch, weil das die Mode-3-Grenze erklärt: `LayoutState.copy(smallMode = enabled, contentArea = MAIN_BUTTONS)` mutiert **dieselbe** Sub-State-Achse atomar, ist KEIN Mode-3-Verstoß).

5. **Cross-Spec-Compile-Error: `LayoutCatalog.OVERLAY_5BUTTON`-Member existiert nicht** (Important): Spec 2 §4 + §8.6 (implizit) und Spec 3 §11/§14 referenzieren `LayoutCatalog.OVERLAY_5BUTTON` als qualifizierten Catalog-Member, aber Spec 3 §3.1 deklariert `OVERLAY_5BUTTON` als **top-level `object`** außerhalb des Catalog-Objects. Compile-Error in der jetzigen Form. Auflösung als Cross-Reference-Pflicht an C-5 dokumentiert; Spec 2 §8.6 hat den SoT-Ankerpunkt für die Einbettung.

6. **Internal-Inkonsistenz §4 Code-Snippet vs. §4.1 Prosa: Single-Backend vs. List-of-Backends** (Important): §4 zeigt `private var activeBackend: RenderBackend?` (Single-Field-Pattern), §4.1 sagt "der KeyboardLayoutManager hält **eine Liste** aktiver Backends statt eines einzigen `activeBackend`-Felds". Doppel-Truth-Quelle innerhalb derselben Section. Ein Implementer würde abhängig von der Lesreihenfolge entweder das Single-Backend-Pattern (Z. 421-433) implementieren — und dann beim `ContentAreaController`-Wiring stutzen ("warum gehen zwei Backends parallel?"). Auflösung: §4 ist pädagogisches Skelett, §4.1 ist Production-Vertrag — Cross-Reference-Header in §4 ergänzt, damit Implementer die SoT-Hierarchie kennt.

**6 Findings (1 Critical, 5 Important, 0 Minor); 8 Plan-Edits** (alle in Spec 2: §2 L6, §4 Implementer-Anker-Header, §4 LayoutCatalog.OVERLAY_5BUTTON-Cross-Ref, §4.1 Atomar-Vertrag-Cross-Link, §8.6 LayoutCatalog-OVERLAY_5BUTTON-Property-Skelett, §10 Acceptance Block 4, §11.8 Migration-Reihenfolge-Snippet, §13.1 Zeile 28, §14.2 Test-Snippet-Cluster; plus 1 Iter-Log-Eintrag im Hauptplan).

---

## Findings + Applied Fixes

### F-1 (CRITICAL) — §14.2 Test-Snippet `predResendVisible`: vier Bugs in einem 6-Zeilen-Block

**Symptom:** Das Beispiel-Test-Snippet im Abschnitt "Unit-Tests (LayoutCatalog)" enthielt vier eigenständige Compile/Drift-Bugs:

```kotlin
@Test fun `predResendVisible is true only in Idle with lastAudio and resendEnabled`() {
    val base = stateBuilder().build()
    assertFalse(predResendVisible(base.copy(recording = RecordingState.Active(false))))     // (a)
    assertFalse(predResendVisible(base.copy(pipeline = PipelineUiState.Preparing)))         // (b)
    assertFalse(predResendVisible(base.copy(lastAudioExists = false)))                       // (c)
    assertFalse(predResendVisible(base.copy(resendEnabled = false)))                          // (d)
    assertTrue(predResendVisible(base))
}
```

- **(a) `RecordingState.Active(false)`** — Spec 1 §3 deklariert `data class Active(val useBluetooth: Boolean, val audioFile: java.io.File)`. Zwei Pflicht-Args. `Active(false)` ist Compile-Error.
- **(b) `PipelineUiState.Preparing`** — Spec 1 §3 deklariert `data class Preparing(val sessionId: String)`. Singleton-Use ist Compile-Error.
- **(c) `base.copy(lastAudioExists = false)`** — `lastAudioExists` lebt in `ResendState` (Spec 1 §3, `state.resend.lastAudioExists`), NICHT im Top-Level-`DictateUiState`. `base.copy(lastAudioExists = ...)` ist Compile-Error (unknown property in `DictateUiState`).
- **(d) `base.copy(resendEnabled = false)`** — identisches Problem wie (c).

**Folge:** Bug-Klasse hat zwei Dimensionen:

1. **Direct compile-fail:** der erste Test-Schreiber, der das Snippet als Vorlage kopiert, wird mit vier Compile-Errors konfrontiert. Frust + Zeit-Verlust, aber selbst-korrigierend.
2. **Vervielfachung in parametrisierte Test-Suite:** das Snippet wird im selben Abschnitt als Vorlage für die `VisibilityMatrixTest`-Suite verwendet (25+ Cases mit `arrayOf(LayoutMode, State, Expected)`-Konstruktoren). Wenn die Test-Schreiber das Snippet copy-paste-iterativ erweitern, vervielfachen sich die vier Bugs in 25+ Test-Cases — alle würden rot kompilieren, der Implementer würde die 25 Stellen einzeln fixen müssen.

Plus: das Snippet ist die **erste Begegnung** eines Implementers mit dem State-Konstruktor-API; ein Snippet mit vier Compile-Bugs gibt ihm das falsche mentale Modell (er denkt, `state.copy(lastAudioExists = ...)` wäre ein gültiger Pfad — den er später in Production-Code reproduzieren könnte, was natürlich nicht kompiliert, aber Zeit kostet).

Klassisches **AI-1-Drift-Echo** (flat-state-paths) aus der Phase-A Architecture-Scout-Aufzählung, das in den Plan-Body-Sektionen (§6 ImeViewBackend, §8 LayoutCatalog) bereits vollständig auf hierarchische Form (`state.resend.lastAudioExists`) homogenisiert wurde — aber in den Test-Snippets (§14.2) ist die alte Form stehengeblieben.

**Fix:** Test-Snippet auf die vier korrekten Formen umgestellt:

```kotlin
assertFalse(predResendVisible(base.copy(recording = RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile()))))
assertFalse(predResendVisible(base.copy(pipeline = PipelineUiState.Preparing(sessionId = "test"))))
assertFalse(predResendVisible(base.copy(resend = base.resend.copy(lastAudioExists = false))))
assertFalse(predResendVisible(base.copy(resend = base.resend.copy(resendEnabled = false))))
```

Plus prominenter FIX-Kommentar dokumentiert alle vier Korrekturen mit Cross-Ref auf Spec 1 §3-Sub-State-Klassen-Definitionen.

**Edit:** Spec 2 §14.2 Test-Snippet-Block.

---

### F-2 (IMPORTANT) — Naming-Drift `pipelineService` → `pipeline` in drei Sites (F-11/G2-Echo)

**Symptom:** Spec 1 §5 (LocalBinder, post-F-8) ist SoT für die IME-Side-Subscription-API:

```kotlin
class DictateInputMethodService : InputMethodService() {
    private var pipeline: DictatePipelineService.LocalBinder? = null
    …
    pipeline!!.state.collect { state -> keyboardLayoutManager.onStateChanged(state) }
}
```

Drei Sites in Spec 2 referenzieren aber die Pre-F-11-Form `pipelineService.state`:

- **§2 L6** (Architektur-Entscheidung): "KeyboardLayoutManager collected `pipelineService.state`".
- **§10 Block-4-Acceptance:** "Manager subscribt erfolgreich an `pipelineService.state`".
- **§11.8 Migration-Reihenfolge** (Block 5c): "`pipelineService.state.collect { manager.onStateChanged(it) }`".

Phase-A G2 hatte ursprünglich 57+10+1 Drift-Stellen für `PipelineStateManager` → `DictateOrchestrator` (F-11). Phase-B hat den Hauptpfad homogenisiert, aber die Lese-Anchor-`pipelineService`-Form ist eine separate Naming-Achse (das **Feld-Naming im IME**, nicht das **Class-Naming im Service** wie bei F-11) — und wurde nicht in einer eigenen Pass-Welle synchron gezogen.

**Folge:** Compile-Error für einen Implementer, der das Spec-2-Snippet als Vorlage nimmt — er deklariert `pipelineService` als Feld, aber das IME-Side-Snippet (Spec 1 §5) verwendet `pipeline`. Drei Refs sind suspended in Pre-F-11-Form, die anderen ~20 Spec-2-Refs auf den IME-Side-Pfad nutzen bereits `pipeline.dispatch(...)` (siehe Spec 2 §11.6 `pipeline?.dispatch(Action.X)`).

**Fix:** Alle drei Sites auf `pipeline.state` umgestellt; Cross-Ref auf Spec 1 §5 LocalBinder-API ergänzt.

**Edit:** Spec 2 §2 L6, §10 Block-4-Acceptance-Liste, §11.8 Migration-Reihenfolge-Snippet (Block 5c).

---

### F-3 (IMPORTANT) — §13.1 Zeile 28 widerspricht §9.6: `pipelineService.markLastAudioExists` ist Pre-F-8-Form

**Symptom:** §13.1 Visibility-Mutation-Audit Tabelle Zeile 28 (für `resendButton (onShowResend)`) sagt:

> **ENTFERNT** — wird zu `pipelineService.markLastAudioExists(true)` State-Update

Aber §9.6 (dieselbe Migration in einer anderen Tabelle, die "4 problematischen resend-Mutationen" in DictateInputMethodService.java) sagt für genau diese Source-Line (`DictateInputMethodService.java:1839`):

> wird zu Action-Dispatch: `orchestrator.dispatch(Action.ResendAction.MarkLastAudio(exists = true))` → ResendModule.reduce setzt `state.resend.lastAudioExists = true` …

§9.6 hat einen FIX-Kommentar:

> Drift gegen F-8 (LocalBinder hat NUR `state` + `dispatch`, kein `markLastAudioExists`-Forwarder; siehe Spec 1 §5 LocalBinder-API). Action `MarkLastAudio(exists: Boolean)` ist in Spec 2 §3.3 `ResendAction.MarkLastAudio` bereits definiert.

Zwei verschiedene Spec-2-Sektionen für **dieselbe Source-Code-Zeile** in **derselben Spec**: §9.6 korrekt (Action-Dispatch via `orchestrator.dispatch`), §13.1 falsch (typed Forwarder, der unter F-8 nicht existiert).

**Folge:** Ein Implementer, der die §13.1-Tabelle als Lese-Anchor nutzt (sie ist ein prominentes Audit-Artefakt), würde `pipelineService.markLastAudioExists` aufrufen — und einen Compile-Error bekommen, weil F-8 LocalBinder keinen `markLastAudioExists`-Forwarder hat. Plus Spec-2-internal-Drift: derselbe Migration-Pfad ist in zwei Tabellen verschieden dokumentiert.

**Fix:** §13.1 Zeile 28 auf `pipeline.dispatch(Action.ResendAction.MarkLastAudio(exists = true))` umgestellt; FIX-Kommentar verweist auf §9.6 als Sister-Tabelle + Spec 1 §5 LocalBinder-API.

**Edit:** Spec 2 §13.1 Visibility-Mutation-Audit Tabelle Zeile 28.

---

### F-4 (IMPORTANT) — Atomar-Vertrag setSmallMode in Spec 2 §4.1 nicht cross-verlinkt (C-1-Offene-Frage)

**Symptom:** C-1 hatte explizit als offene Frage an C-3/C-4 weitergegeben (siehe `phase-c1-state-module-coherence.md` Sektion "Für C-3 (Layout/View-Rendering)"): *"§11.2.2 Schritt 6 nennt 'LayoutModule implementieren — KeyboardStateManager.contentArea/isSmallMode wandern in LayoutState'. Prüfen: wird der Atomar-Vertrag (siehe Block-1b-Acceptance 'Atomarität setSmallMode') in der LayoutModule-Implementations-Stelle (Spec 2) korrekt reflektiert?"*

C-4-Befund: Spec 2 §4.1 dokumentiert zwar den Vertrag "Mutationen an `state.layout` gehen **immer** durch `Action.LayoutAction.*` → `LayoutModule.reduce`", aber **kein Cross-Link zur Atomarität-Klausel**. Das ist load-bearing, weil:

1. Die Atomarität ist die explizite Mode-3-Grenze: `LayoutState.copy(smallMode = enabled, contentArea = MAIN_BUTTONS)` mutiert ZWEI Felder, aber sie leben in DERSELBEN Sub-State-Klasse `LayoutState` (Spec 1 §3). Das ist KEIN Mode-3-Verstoß (Spec 1 §15.5 Anti-Beispiel-Tabelle Zeile 3), weil Mode 3 = "Modul mutiert seine Achse + EINE ANDERE Achse (in anderem Modul) in einem Reducer-Schritt".
2. Ohne diesen Cross-Link würde ein Implementer, der nur Spec 2 §4.1 liest, ggf. die `smallMode`/`contentArea`-Mutation in **zwei separate** `Action.LayoutAction.SetSmallMode` + `Action.LayoutAction.SetContentArea`-Dispatches splitten — und damit das KSM-Bug-Verhalten (sequenzielle Schritte, Stale-Zwischen-Zustand für Subscriber) re-introducen.

**Folge:** Implementations-Drift gegen Block-1b-Acceptance ("LayoutModuleAtomicityTest.kt"). Bug-Klasse: ein Test-Schreiber, der die Acceptance liest, würde fragen "warum mutiert der Reducer zwei Felder — ist das nicht Mode 3?" — ohne den Cross-Link wäre die Antwort nicht im Sichtfeld.

**Fix:** Atomar-Vertrag-Cross-Link als Blockquote in Spec 2 §4.1 (direkt nach dem "Vertrag"-Absatz) verankert. Inhalt: Zitiert Spec 1 §11.2.2 Schritt 6 + Block-1b-Acceptance "Atomarität setSmallMode"; macht explizit, dass `state.copy(layout = layout.copy(smallMode = enabled, contentArea = MAIN_BUTTONS))` KEIN Mode-3-Verstoß ist (eine Sub-State-Achse `LayoutState`, atomar); Cross-Ref auf `LayoutModuleAtomicityTest.kt` (Spec 1 §11 Block-1b-Acceptance).

**Edit:** Spec 2 §4.1 KeyboardLayoutManager ↔ LayoutModule Beziehungs-Section (Atomar-Vertrag-Blockquote).

---

### F-5 (IMPORTANT) — Cross-Spec-Compile-Error: `LayoutCatalog.OVERLAY_5BUTTON` nicht im Catalog deklariert

**Symptom:** Spec 2 §4 `computeLayoutMode`-Code-Snippet und Spec 3 §11/§14 (mehrfach) referenzieren `LayoutCatalog.OVERLAY_5BUTTON` als qualifizierten Catalog-Member:

```kotlin
private fun computeLayoutMode(state: DictateUiState): LayoutMode = when (state.viewMode) {
    ViewMode.KEYBOARD -> LayoutCatalog.forKeyboard(state)
    ViewMode.WIDGET, ViewMode.HOVER -> LayoutCatalog.OVERLAY_5BUTTON
}
```

Aber Spec 3 §3.1 deklariert `OVERLAY_5BUTTON` als **top-level `object`** außerhalb von `LayoutCatalog`:

```kotlin
object OVERLAY_5BUTTON : LayoutMode(
    id = LayoutModeId.OVERLAY_5BUTTON,
    backend = BackendType.OVERLAY_WINDOW,
    ...
)
```

Spec 2 §8.6 `LayoutCatalog`-Object hat nur die Methode `forKeyboard(state)` und keine `OVERLAY_5BUTTON`-Property. Damit ist `LayoutCatalog.OVERLAY_5BUTTON`-Ref in der jetzigen Form ein **Compile-Error** ("unresolved reference OVERLAY_5BUTTON").

**Folge:** Compile-Error beim ersten `./gradlew assembleDebug` nach Block 4 oder Block 6. Ein Implementer würde wahrscheinlich:
- entweder den Catalog-Object um eine `OVERLAY_5BUTTON`-Property erweitern (Spec 3 §3.1-Inhalts-SoT, aber im `LayoutCatalog`-Body eingebettet),
- ODER das `LayoutCatalog.`-Prefix entfernen und auf top-level zugreifen — aber das funktioniert nur, wenn Spec 3 §3.1 als Top-Level-Object bleibt; dann müssen ABER alle anderen Sites in Spec 3 (§11/§14) auch un-qualifiziert sein.

Die Plan-Sektionen mischen die zwei Formen, was ein klassisches Doppel-Truth-Quelle-Problem ist.

**Auflösung (C-4-Entscheidung):** Die Catalog-Member-Form ist die korrekte (`LayoutCatalog.OVERLAY_5BUTTON`), weil:
1. `LayoutCatalog` ist die kanonische SoT für alle LayoutModes (§8.6); KEYBOARD-Modes + OVERLAY-Mode gehören in dasselbe Object.
2. Spec 3 §11/§14 mehrfach `LayoutCatalog.OVERLAY_5BUTTON` (5+ Stellen) — Mehrheit der Referenzen.
3. Spec 2 §4 Code-Snippet `LayoutCatalog.OVERLAY_5BUTTON` — direkter Konsument.

**Cross-Spec-Korrektur-Pflicht:** Spec 3 §3.1 muss die `object OVERLAY_5BUTTON : LayoutMode(...)`-Top-Level-Deklaration in den `LayoutCatalog`-Object einbetten. C-5 (Floating-Overlay-Audit) erbt diese Korrektur-Pflicht. C-4 hat:
- In Spec 2 §4 einen FIX-Kommentar mit Cross-Spec-Reference verankert (Implementer weiß, dass der Ref jetzt noch Compile-Error ist, aber post-C-5 grün wird).
- In Spec 2 §8.6 ein Property-Skelett (`// val OVERLAY_5BUTTON: LayoutMode = ... // SoT: Spec 3 §3.1; C-5 ergänzt den Property-Body hier`) als Ankerpunkt für die C-5-Cross-Spec-Edit.

**Edit:** Spec 2 §4 `computeLayoutMode`-Snippet (FIX-Kommentar); Spec 2 §8.6 `LayoutCatalog`-Object (Property-Skelett für `OVERLAY_5BUTTON`).

---

### F-6 (IMPORTANT) — §4 Code-Snippet vs. §4.1 Prosa: Single-Backend vs. List-of-Backends

**Symptom:** §4 zeigt im KeyboardLayoutManager-Code-Snippet:

```kotlin
class KeyboardLayoutManager(...) {
    private var activeBackend: RenderBackend? = null
    …
    fun attachBackend(backend: RenderBackend) {
        activeBackend?.detach()
        activeBackend = backend
        …
    }
    …
}
```

— ein einzelnes nullable Backend-Field. §4.1 (gleicher Section!) sagt aber explizit:

> Der `KeyboardLayoutManager` hält **eine Liste** aktiver Backends statt eines einzigen `activeBackend`-Felds; bei Render-Tick werden alle aufgerufen.

Hintergrund: §4.1 hat den `ContentAreaController` als zweites `RenderBackend` (R.10 / Issue 2.1.15 Option B) eingeführt — Container-Visibility-Pfad parallel zu Slot-Visibility-Pfad. Damit zwei Backends parallel zum Manager attached sind.

**Folge:** Doppel-Truth-Quelle innerhalb derselben Section. Ein Implementer, der nur §4 liest (= das zuerst kommende Snippet), würde:
- Single-Backend-Field implementieren.
- Beim `ContentAreaController`-Wiring im Block 5b stutzen (wo geht der zweite Backend hin?) und entweder:
  - Den Single-Backend-Field zur List erweitern (= effectiv §4.1 nachholen, aber ohne den expliziten Plan-Hinweis),
  - ODER den `ContentAreaController` in den `ImeViewBackend.render`-Body inlinen (= SRP-Verstoß gegen §4.1-Spaltung).

Bug-Klasse: identisch zu Phase-B S-8 F-3 (§13.4-vs-§4.2-Click-Listener-Pattern-Doppel-Truth in Spec 3) — Doppel-Truth in derselben Spec.

**Fix:** §4 Code-Snippet bleibt unverändert (pädagogisches Skelett für Single-Backend-Erklärung), aber ein **prominenter Implementer-Anker-Header** wird VOR dem Snippet eingefügt, der explizit dokumentiert:
- §4 ist Single-Backend-Skelett (Pädagogik).
- §4.1 ist Production-Vertrag (Multi-Backend mit ContentAreaController).
- SoT für Block-5b-Implementation ist §4.1.

Damit ist die Reading-Reihenfolge klar; der Implementer kann das §4-Snippet als Lese-Anker nutzen, aber die §4.1-Erweiterung als Implementations-Pflicht.

**Edit:** Spec 2 §4 vor dem KeyboardLayoutManager-Code-Snippet (Implementer-Anker-Blockquote).

---

## Plan-Edits (Audit-Trail)

| Datei | Sektion | Art | Kurzbeschreibung |
|---|---|---|---|
| Spec 2 §2 L6 | Architektur-Entscheidung Subscription-Pattern | Update | `pipelineService.state` → `pipeline.state` (LocalBinder, Spec 1 §5; F-11/G2-Naming-Drift) — F-2 |
| Spec 2 §4 | KeyboardLayoutManager-Code-Snippet-Header | Insert | Implementer-Anker-Blockquote: §4 = Single-Backend-Skelett, §4.1 = Multi-Backend-Production-Vertrag — F-6 |
| Spec 2 §4 | `computeLayoutMode`-Snippet | Insert | FIX-Kommentar dokumentiert `LayoutCatalog.OVERLAY_5BUTTON`-Cross-Spec-Compile-Error + C-5-Korrektur-Pflicht — F-5 |
| Spec 2 §4.1 | KeyboardLayoutManager ↔ LayoutModule Beziehungs-Section | Insert | Atomar-Vertrag-Cross-Link-Blockquote (C-1-Offene-Frage aufgelöst): Spec 1 §11.2.2 Schritt 6 + Block-1b-Acceptance "Atomarität setSmallMode" — F-4 |
| Spec 2 §8.6 | LayoutCatalog-Object | Insert | Property-Skelett-Anker (`// val OVERLAY_5BUTTON: LayoutMode = ... // SoT: Spec 3 §3.1`) als Ankerpunkt für die C-5-Cross-Spec-Edit — F-5 |
| Spec 2 §10 | Block-4-Acceptance erste Klausel | Update | `pipelineService.state` → `pipeline.state` (LocalBinder, Spec 1 §5) — F-2 |
| Spec 2 §11.8 | Migration-Reihenfolge-Snippet (Block 5c) | Update | `pipelineService.state.collect` → `pipeline.state.collect` — F-2 |
| Spec 2 §13.1 | Visibility-Mutation-Audit Tabelle Zeile 28 | Update | `pipelineService.markLastAudioExists(true)` → `pipeline.dispatch(Action.ResendAction.MarkLastAudio(exists = true))` (F-8 LocalBinder-API + F-11-Naming + Spec-2-internal-Konsistenz mit §9.6) — F-3 |
| Spec 2 §14.2 | Test-Snippet `predResendVisible`-Block | Refactor | Vier Korrekturen: (a) `RecordingState.Active(false)` → 2-arg-Konstruktor; (b) `PipelineUiState.Preparing` → `Preparing("test")`; (c+d) `base.copy(lastAudioExists/resendEnabled = …)` → `base.copy(resend = base.resend.copy(...))` — F-1 |
| Hauptplan §9 | Iteration-Log | Insert | "2026-05-14 — Phase-C Quality-Gate C-4"-Entry mit 6 Findings + Plan-Edits-Summary |

**Gesamt:** 10 Operations in 2 Dateien (Spec 2: 9, Hauptplan: 1). Spec 1 unverändert — alle C-4-Findings sind Spec-2-internal oder Cross-Spec-Aufwärts (Spec 2 → Spec 3 via §4 + §8.6, von C-5 zu fixen). Spec 3 unverändert in C-4 (Cross-Spec-Verifikation hat den `OVERLAY_5BUTTON`-Top-Level-vs-Catalog-Member-Drift entdeckt, die Korrektur in Spec 3 §3.1 ist C-5-Scope).

---

## Offene Fragen für nachfolgende Agents

### Für C-5 (Floating-Overlay — Spec 3)

- **F-5-Cross-Spec-Korrektur-Pflicht (erbt aus C-4):** Spec 3 §3.1 deklariert `OVERLAY_5BUTTON` als **top-level `object`**, aber Spec 2 §4 + Spec 3 §11/§14 mehrfach referenzieren `LayoutCatalog.OVERLAY_5BUTTON`. C-5 muss die Top-Level-Deklaration in den `LayoutCatalog`-Object (Spec 2 §8.6) einbetten — entweder als Property (`val OVERLAY_5BUTTON: LayoutMode = LayoutMode(...)`) oder als nested object (`object OVERLAY_5BUTTON : LayoutMode(...)`). SoT-Inhalt bleibt in Spec 3 §3.1; SoT-Strukturplatz ist Spec 2 §8.6 (Property-Skelett-Anker bereits gesetzt). Cross-Ref-Edits in Spec 3 §11 + §14 sollten dabei konsistent gemacht werden (alle Refs auf `LayoutCatalog.OVERLAY_5BUTTON`).
- **Cross-Spec-Konsistenz-Pass für Click-Listener-Pattern (post-C-3):** Spec 2 §6 wireStaticHandlers nutzt jetzt explizit `slot.actionResolver(s, services)?.let { onAction?.invoke(it) }` mit Resolver-`null`-Aussortierung (Phase-C C-3 F-7-Anker). C-5 sollte prüfen, ob die Overlay-Backend-Click-Sites in Spec 3 §4.2 (`wireStaticOverlayHandlers`) das **identische** Pattern nutzen — wenn nein, wäre das ein Cross-Spec-DRY-Drift (zwei Backends mit unterschiedlicher Null-Filter-Konvention). C-3 hat diesen Cross-Check explizit an C-4 weitergegeben; C-4 hat in Spec 2 verifiziert, dass §6 das Pattern korrekt hat — die Spec-3-Verifikation gehört nach C-5.
- **F-6 ContentAreaController als zweites Backend in Spec 3:** Falls Spec 3 OverlayBackend ebenfalls einen zweiten Backend-Slot für irgendwelche orthogonale Visibility-Achse braucht (z.B. ein dediziertes Overlay-Notification-Sublayer), sollte das im Multi-Backend-Pattern von §4.1 KeyboardLayoutManager reflektiert sein.

### Für C-State (State-File-Konsistenz)

- Plan-State-File (`plan-review/state.md`) ist seit Phase-1-Abschluss nicht aktualisiert (unverändert zum C-3-Hinweis). Beim Phase-2/Phase-5-Plan-Archive-Schritt sollte das State-File auf den tatsächlichen Phase-A/B/C-Workflow umgestellt werden.
- C-State sollte zusätzlich verifizieren, dass die in C-4 dokumentierte Cross-Spec-Korrektur (F-5: `LayoutCatalog.OVERLAY_5BUTTON`-Property-Skelett in Spec 2 §8.6) post-C-5-Apply tatsächlich umgesetzt ist (= Spec 3 §3.1 hat die Top-Level-Deklaration gelöscht und die Property-Form in Spec 2 §8.6 ergänzt).

---

**Reviewer-Note:** Das C-4-Finding-Cluster hat **drei Achsen** gegenüber C-1/C-2/C-3:

- **C-1/C-2 Drift-Echo-Muster:** stale Counter / stale Vertrags-Layer-Refs in Lese-Anchor-Sites.
- **C-3 Cross-Spec-Reducer-Logik-Bug:** Reducer-String-Match gegen `effect.toString()`-Encoding scheitert wegen `data class.toString()`-Property-Inklusion.
- **C-4 Test-Snippet-Drift-Cluster (F-1):** klassische AI-1-flat-state-paths-Drift, die in Plan-Body-Sektionen vollständig homogenisiert wurde, aber in Test-Snippets stehen blieb. Plus zwei eigenständige Konstruktor-Signatur-Drifts. Bug-Klasse: Test-Snippets sind häufig **das letzte Refactoring-Target** in einem Plan-Edit-Pass, weil sie als "Beispiel" mental abgekapselt werden — aber sie werden von Implementern als **Lese-Vorlage** für die echte Test-Suite kopiert. Wenn ein Test-Snippet vier Bugs hat, vervielfachen sich diese in der Production-Test-Suite. Lesson: jede Plan-Iteration, die State-Sub-Klassen oder Action-Sub-Klassen umstrukturiert, MUSS einen separaten Pass über Test-Snippets machen (gleich aggressiv wie über Code-Snippets, weil Test-Snippets dieselbe Compile-Korrektheits-Pflicht haben).

Plus: das **C-4-Naming-Drift-Cluster** (`pipelineService` → `pipeline`, F-2/F-3) ist die dritte Generation des F-11-Echo-Musters:
- Phase-B F-11 hat `PipelineStateManager` → `DictateOrchestrator` umgestellt (Class-Naming).
- C-1 hat den `DictateOrchestrator`-Echo in `KeyboardInputModule`-Sites homogenisiert (Methoden-Naming + Konstruktor-Naming).
- C-4 hat den `pipelineService` → `pipeline`-Echo in IME-Side-Sites homogenisiert (**Feld-Naming**).

Drei verschiedene Naming-Achsen, alle aus dem F-11-Refactor-Ursprung — jede mit eigener Drift-Welle, jede in einem anderen Phase-C-Pass entdeckt. Lesson für Phase-D-Reviews: bei Naming-Refactors **alle drei Achsen** (Class / Methode / Feld) müssen in separatem Grep-Pass homogenisiert werden, nicht nur die offensichtliche Class-Achse.

Plus: das **C-4-Cross-Spec-Compile-Error-Finding (F-5)** ist eine Sub-Klasse des C-3-Cross-Spec-Patterns: Spec 2 erwartet `LayoutCatalog.OVERLAY_5BUTTON`, Spec 3 deklariert `OVERLAY_5BUTTON` top-level. Beide Specs sind für sich kohärent (Spec 2 hat `LayoutCatalog` als SoT, Spec 3 hat die LayoutMode-Definition), aber die **Cross-Spec-Strukturplatz-Konvention** ist nicht synchronisiert. Solche Cross-Spec-Struktur-Bugs lassen sich nur durch End-to-End-Trace (Spec-1-Konsument referenziert Spec-3-Deklaration über Spec-2-SoT-Object) finden — klassischer C-Achsen-Wert.

Plus: F-4 ist die **echte Auflösung der C-1-Offene-Frage** — C-1 hat sie an C-4 weitergereicht; C-4 hat sie als load-bearing identifiziert und mit einem expliziten Mode-3-Grenze-Cross-Link verankert. Der Plan-Review-Workflow (C-1 → C-4 mit expliziter Offener-Frage-Brücke) funktioniert wie geplant.

Nach den 9 Spec-2-Edits + 1 Hauptplan-Edit ist der Layout/View-Rendering-Bereich für die Implementer-Phase reif. F-1 ist die kritischste Einzeländerung — ohne sie wäre das erste `./gradlew test`-Run nach Block 4 + 5 ein vierfacher Compile-Error gewesen.
