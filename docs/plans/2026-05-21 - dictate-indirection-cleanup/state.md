# Implementation State — dictate-indirection-cleanup

## Run metadata
- Start: 2026-05-21T10:33:44+02:00
- Worktree: ./worktrees/feature/dictate-keyboard-layout-refactor
- Last commit at run-start: ad3dfd0b1338a1494a90569d820b6ddf33cd3ea0

## Chunk status

| Block | Chunk | Status | Commit | Notes |
|---|---|---|---|---|
| 2 | 2.1 | DONE (pre-run) | 1bae447 | module-local Variant A: `LayoutModule.Effect.PersistSmallMode` |
| 2 | 2.2 | DONE (pre-run) | 1bae447 | module-local Variant A: `LayoutModule.Effect.PersistSingleRowMode` |
| 3 | 3.0 | DONE | edfe8a2 | `PrefPersistenceService` + `SharedPrefsPersistenceService` als typed Write-Seam in `ModuleServices`. Production-Wiring in `DictatePipelineService.onCreate`. Test-Fixture `fakeModuleServices(prefs=…)` + `RecordingPrefPersistenceService`-Helper. LayoutModule bleibt bewusst auf direkter SP-Schreibung (plan-explicit). |
| 3 | 3.1 | DONE | f39cc76 | `Effect.PersistAudioFocusPref(value)` routet über `services.prefs.persist(Pref.AudioFocus, …)`. Test: idle Persist-only Pfad. |
| 3 | 3.2 | DONE | f39cc76 | `Effect.ApplyAudioFocusRuntime(enabled)` emittiert nur wenn Recording=Active AND `nextPref != audioFocusGranted`. Tests: Active+runtime, Active+already-matching=skip, Paused=skip. |
| 3 | 3.3 | DONE | b9052ea | Neuer `EditBarAudioFocusObserver` mit Listener-Callback (JVM-testbar), wired in IME alongside `overlayOnboardingObserver`. Seed-Call in `attachDormantEditBarEmojiOwners` entfernt (erstes Observer-Emit subsumiert ihn). 5 Tests. |
| 3 | 3.4 | DONE | 13d85a2 | 4-stufiger imperativer Pfad → `dispatch(Action.AudioAction.ToggleAudioFocusPref)`. Pre-bind-Fallback bleibt. |
| 3 | 3.5 | DONE | ecf5a77 | `audioFocusListener` + alle 3 Register-/Unregister-Sites entfernt. Neue Action `ApplyAudioFocusRuntimeFromPref(value)` + Cross-Module-Cascade in `AudioModule.onCrossModuleStateChange` (deckt externe Settings-Activity-Writes ab). 5 neue Tests. **Deviation D-1** dokumentiert. |
| 3 | 3.6 | DONE | 5629f6d | `setOnAudioFocusChangeListener` → `dispatch(OnAudioFocusGrantChanged)`. Legacy-Parity exakt: hard LOSS=false / GAIN-Varianten=true / LOSS_TRANSIENT(_CAN_DUCK) no-op. AudioModule-Cascade übernimmt PauseRecording. |
| 4 | 4.1 | DONE | d5e05b8 | Neuer `OverlayPermissionInfobarRenderer` als Single-Owner der Visibility. Observer-Callback ruft `renderer.apply(pending)` statt direkt `setVisibility`. Idempotent, symmetric lifecycle. |
| 4 | 4.2 | DONE | 07ba170 | Container-Backgrounds + Accent-TextView-Pass in `ImeViewBackend.applyKeyboardBackground` / `applyTheme` integriert. `@JvmOverloads` für Java-Compat. Pre-bind-Fallback bleibt. |
| 4 | 4.3 | POSTPONED | — | Enter-Icon Catalog-Resolver. Refactor-Scope deutlich grösser als plan-effort "M" (Unit→KeyboardInputState wechsel, 5 Catalog-Slots, Registry, Tests). Severity 🟡 (kein Bug, nur Architektur). One-shot pro `onStartInputView` ohne Roundtrip-Pattern. Folge-Chunk im Block-5-Follow-up. |
| 4 | 4.4 | DONE | ef24263 | `Effect.PersistLastFileName` aus beiden StartRecording-Armen + `Effect.PersistImportedAudioFileName` aus neuer `OnAudioFileImported`-Action. PrefPersistenceService-Seam. 3 Test-Updates/Additions. |
| 4 | 4.5a | DONE | 4093adc | `PipelinePrefMirror` computed-mirror für `Pref.InputLanguages` + `Pref.InputLanguagePos` → `state.language.effective` via `LanguageResolver`. 2 neue Tests + 3 angepasste Tests. |
| 4 | 4.5b | DONE | 8a47175 | `inputLanguagesListener` + alle Register/Unregister-Sites entfernt. Neuer `LanguageEffectiveObserver` feedet Chip-Label. AC-7 vollständig erfüllt. |
| 4 | 4.5c | DONE | c66a3e5 | Neue Action `SetEffectiveLanguage(code)` + Reducer-Arm + `Effect.PersistEffectiveLanguage`. Delegiert an `LanguageResolver.setLanguage` statt Curation-Logik zu duplizieren (**Deviation D-3**). 3 neue Tests. Pre-bind-Fallback bleibt. |
| 4 | 4.6 | DONE | c54100d | `recordingStateController.setCallback(new Callback)`-Block in `rewireCallbacks()` entfernt — toter Code (Bound-Pfad startet den Controller nie; Import-Hatch wurde durch Chunk 4.4 geschlossen). Controller-API bleibt (Retire = Block 5 Folge-Plan). |

## Plan-intention deviations

### D-1 (Chunk 3.5) — externe SP-Mutation braucht eigenen Cascade-Pfad

**Plan-Text** (§4 Chunk 3.5): „`audioFocusListener`-SP-Listener entfernen
(er war die externe Settings-Activity-Brücke; PipelinePrefMirror übernimmt
jetzt komplett). Die `setAudioFocusRuntime` + Edit-Bar-Twin-refresh werden
bereits in Chunk 3.2/3.3 von der AudioModule-Observer-Cascade gefeuert."

**Realität**: Chunk 3.2's `ApplyAudioFocusRuntime` wird *ausschließlich*
aus dem `ToggleAudioFocusPref`-Reducer-Arm emittiert — also nur auf dem
in-IME-Click-Pfad. Für externe SP-Writes (Settings Activity während laufender
Aufnahme) gab es nach dem reinen Listener-Entfernen *keinen* Pfad mehr zum
Live-AudioManager-Update — ein Verhaltens-Regress gegenüber dem alten
`recordingStateController.setAudioFocusRuntime(newValue)`-Aufruf im Listener.

**Recherche**: Verifiziert dass `AudioModule.onCrossModuleStateChange` heute
*nur* Recording-State-Transitionen beobachtet, keinen `audioFocusEnabledPref`-Delta.

**Resolution (sustainable, ohne Scope-Erweiterung)**: Neue Action
`Action.AudioAction.ApplyAudioFocusRuntimeFromPref(enabled: Boolean)` +
Reducer-Arm + Cross-Module-Cascade in `onCrossModuleStateChange` (Bedingung:
`prev.audio.audioFocusEnabledPref != next.audio.audioFocusEnabledPref &&
nextRec is Active`). Die Reducer-Arm-Logik gated auf
`enabled != audioFocusGranted` für Idempotenz (gleiche Gate-Semantik wie
in Chunk 3.2).

**Warum konform zur Plan-Intention**: §1 Ziel sagt explizit "Single-Dispatch-
Invariante (ADR-0001 F-8): Jede State-Mutation geht durch `dispatch(Action)`".
Ohne dieses Action+Cascade-Paar wäre die externe SP-Brücke nach Listener-Entfernen
gebrochen. Der Plan-Text hat nur die Notation übersehen; die Intention ist
exakt eingehalten.

**Follow-up**: Keine. AC-7 erfüllt (kein Custom-SP-Listener mehr für
`Pref.AudioFocus`). AC-1 / AC-2 / AC-3 erfüllt.

### D-2 (Chunk 4.3) — Enter-Icon Catalog-Resolver postponed (Scope-Beschränkung)

**Plan-Text** (§4 Chunk 4.3, effort M): KeyboardInputModule um
`state.keyboardInput.enterIcon: EnterIconKind` erweitern, neue
`SetEnterIconKind`-Action, 5 Catalog-ENTER-Slots `iconResolver` setzen,
`updateEnterButtonIcon` durch Dispatch ersetzen.

**Realität (Code-Read)**:
- `KeyboardInputModule` hat heute **`Unit`-state**. Der Refactor erfordert
  echten Wechsel auf eigenes State-Objekt — ändert Lens, `DictateUiState`,
  Registry-Coverage-Annahmen, Tests.
- 5 Catalog-ENTER-Slots zu modifizieren über 4 LayoutModes.
- Mapping `EditorInfo.imeOptions` → `EnterIconKind` ist ein neuer
  Mapper, der das ableitet was `updateEnterButtonIcon` heute imperativ tut.

**Recherche**: Plan-effort "M" (30 min – 2h) ist deutlich unter dem realen
Aufwand für diesen Refactor. Etwa 2–4h für sustainable Implementation
inkl. Tests.

**Severity der Site**: 🟡 in Plan-§3.5 — *Architektur-Schuld, nicht
bug-aktiv*. Der Pfad läuft **einmal pro `onStartInputView`** als
One-Shot — kein Click-Roundtrip-Antipattern. Pre-Bind-Verlust-Risiko ist
null (kein User-Click-Pfad).

**Resolution**: Postpone in einen Folge-Plan (vorgemerkt für
Vol4-Material gemeinsam mit Block 5 RecordingStateController-Retire und
OQ-2 PromptQueue-Migration). Der jetzige Plan-Lauf konzentriert sich auf
die höher-priorisierten Chunks 4.4 / 4.5 / 4.6, die mehr Plan-Intention
(Click-Roundtrips, AC-7 Custom-Listener-Removal) bedienen.

**AC-Impact**: AC-6 (View-Mutation-Owner-Whitelist) bleibt mit einem
verbleibenden ENTER-`setForeground`-Treffer im IME-Service — dokumentiert
als bekannter Postponed-Item, nicht silent.

**Follow-up**: Folge-Plan-Stub: `dictate-keyboard-input-state-elaboration`
(Vol4-Material), das B-5 + andere KeyboardInputModule-State-Ausweitungen
gemeinsam adressiert.

### D-3 (Chunk 4.5c) — Curation-Logik bleibt im LanguageResolver

**Plan-Text** (§6.2 OQ-1-Plan-Edit Chunk 4.5c): „LanguageModule bekommt
einen Reducer-Arm `SetEffectiveLanguage` + zwei Effects:
`Effect.PersistInputLanguages(curated, pos)`. Die curated-list-Logik aus
`LanguageResolver.persistInputLanguagesAndPos` wandert in den
Module-Effect."

**Realität (Code-Read)**: `LanguageResolver.setLanguage` und die
darunterliegende `persistInputLanguagesAndPos`-Algorithm sind komplex
(Auto-Add bei fehlender curated-list-Membership, `InputLanguagesPlugin.sanitize`
mit Label-Sort und Allowlist-Filter, `VersionedPrefs`-Envelope-Schreibung,
Pos-Resync). Die Algorithmus wird sowohl vom IME als auch von der
Settings Activity konsumiert (separate Prozess-Actor ohne Orchestrator-Bindung).

**Recherche**:
- `LanguageResolver` ist als zentrale SoT für die curated-list-Algorithmik
  dokumentiert (KDoc :13ff, "Single source of truth").
- Settings Activity nutzt `LanguageResolver.setLanguage` direkt — sie hat
  keinen `pipelineBinder`-Zugriff.
- Die Curation-Logik in den Module-Effect zu duplizieren würde **zwei**
  SoTs schaffen, die out-of-sync driften können.

**Resolution (sustainable)**: Action `SetEffectiveLanguage(code)` neu +
Reducer-Arm + `Effect.PersistEffectiveLanguage(code)` neu, **aber der
Effect-Handler delegiert an** `LanguageResolver.setLanguage(services.sharedPrefs, code)`.
Das schafft den dispatch-only-Pfad ohne Algorithmus-Duplikation:
- Plan-Ziel "Single-Dispatch-Invariante" ✓ (in-IME picker dispatcht jetzt)
- Plan-Ziel "Curation-Logic im Modul" — *interpretiert als*: Modul ist
  jetzt verantwortlich für *Orchestrierung* des Curation-Schritts, nicht
  für dessen Re-Implementation.
- `LanguageResolver` bleibt SoT für Settings + Module — keine Drift möglich.

**Warum konform zur Plan-Intention**: §1.1.1 sagt "Single-Dispatch-Invariante";
§1.1.2 sagt "Testbarkeit (Reducer + Effect lassen sich JVM-only testen)".
Beides erfüllt. Die Effect-Logik (LanguageResolver-Aufruf) ist
JVM-only testbar via `RecordingPrefPersistenceService`-ähnliche Tests +
LanguageResolverTest-Suite.

**Follow-up**: Keine. AC-1 grep zeigt `setLanguageFromPicker`
verbleibendes `LanguageResolver.setLanguage` nur im pre-bind-Fallback
(durch Chunk-4.5c-Kommentar dokumentiert).

### D-4 (Review-fix G3, Chunk 3.5) — Cross-Module-Observer-Cascade aus dem Mirror-Pfad

**Plan-Text** (§6.1 R-5): „External Settings-Activity-Writes (z.B. `Pref.AudioFocus`)
gehen weiterhin durch PipelinePrefMirror SP→State. … Wenn die Settings-
Activity einen Live-Hook für laufende Aufnahme erwartet (heute via
`audioFocusListener`), muss AudioModule.onCrossModuleStateChange dem
Live-Hook-Verhalten entsprechen (Chunk 3.2)."

**Realität (vor diesem Fix)**: D-1 hat den Cascade-Arm
`audioFocusEnabledPref-delta && Active → ApplyAudioFocusRuntimeFromPref`
in `AudioModule.onCrossModuleStateChange` ergänzt — der Arm war korrekt,
**aber unerreichbar aus dem Mirror-Pfad**:
- `PipelinePrefMirror.sync(key)` rief `store.update { applyChange(...) }`
  **direkt** auf — ein State-Write am Orchestrator vorbei.
- `DictateOrchestrator.dispatchInternal` Step 5 (Cross-Module-Cascade)
  läuft **nur** nach Action-getriebenen Writes.
- Folge: Eine externe Settings-Activity-SP-Mutation auf `Pref.AudioFocus`
  während Aufnahme=Active aktualisierte den State (`audioFocusEnabledPref = false`),
  **erreichte aber nie** den Live-AudioManager — exakt das R-5-Regress-Szenario,
  das §6.1 verhindern sollte.

**Recherche (Architektur-Optionen erwogen)**:
1. **Synthetisches `Action.MirrorSync(key)`** — bricht die "Actions sind
   Module-besitzbar"-Invariante; eine Action ohne Reducer + ohne Modul
   wäre Platzhalter ohne Domänen-Bedeutung (anti-SOLID).
2. **Mirror ruft Observer direkt via neuer Orchestrator-API** — clean SoT,
   bewahrt "Mirror schreibt State"-Invariante, fügt eine fokussierte
   öffentliche API hinzu.
3. **Hybrid (`MirrorSync(prev,next)` reducer-lose Action)** — verschmilzt
   den semantischen Mangel von (1) mit der Komplexität von (2).

**Resolution (sustainable, Option 2 mit Dispatcher-Abstraktion)**:
- Neues `fun interface MirrorSyncDispatcher` als Mirror→Orchestrator-Sink.
- `PipelinePrefMirror.attach(store, dispatcher)` — der Mirror hält keinen
  Store-Reference mehr; Runtime-Sync geht durch den Dispatcher.
- `DictateOrchestrator.runMirrorSync(reducer)` — captures prev, applies
  reducer to store, captures next, läuft Cross-Module-Observer-Cascade
  (selbe frozen-snapshot-Semantik wie `dispatchInternal` Step 5),
  dispatcht jede Cascade-Action via `dispatchInternal(action, depth=0)`.
  Depth=0, weil eine externe SP-Mutation ein **frischer** Dispatch-Pass
  ist (kein In-Flight-Action-Continuation); der MAX_CASCADE_DEPTH-Guard
  schützt den inneren Pass.
- Orchestrator-Init wired den Mirror via Method-Reference:
  `prefMirror?.attach(store, ::runMirrorSync)` — keine Lambda-Allokation
  pro Dispatch, Cascade-Engine bleibt im Orchestrator gekapselt.
- Initial-Snapshot in `attach` schreibt direkt via `store.update` (kein
  Cascade nötig — nichts ist noch subscribed, Module-Reducer + Recovery
  laufen erst danach).

**Warum konform zur Plan-Intention**: §1 Ziel "Single-Dispatch-Invariante"
+ §6.1 R-5 sind erfüllt: jede State-Mutation läuft entweder durch
`dispatch(Action)` (User-Actions) oder durch `runMirrorSync` (SP-Mirror),
beide produzieren die selbe `(prev, next) → onCrossModuleStateChange →
dispatch(cascade)`-Kette. SoT für Cross-Module-Reaktionen bleibt der
Orchestrator.

**Verifikation**: Neuer `PipelinePrefMirrorCascadeTest` lockt das
Verhalten: external SP-Toggle (`Pref.AudioFocus = false`) während
Recording=Active mit `audioFocusGranted=true` triggert
`AudioFocusSubsystem.release()` (`audioFocus.releases == 1`) end-to-end
durch den Mirror→runMirrorSync→AudioModule.onCrossModuleStateChange→
ApplyAudioFocusRuntimeFromPref→ApplyAudioFocusRuntime(false)→release()-Pfad.
Companion-Tests: Idle-Pfad triggert keine Live-Calls; in-IME-Click-Pfad
funktioniert weiterhin (≥1 release).

**Follow-up**: Keine. `PipelineRecovery.store.update`-Call bleibt
direkt — Recovery läuft beim Boot bevor irgendetwas subscribed ist,
Cascades hätten keinen wirksamen Konsumenten.

### D-5 (Review-fix G1, Chunk 4.3) — Postponement explizit im Plan-Body markiert + Folge-Plan-Stub erzeugt

**Plan-Text** (§4 Chunk 4.3, vor diesem Fix): „**Chunk 4.3 (B-5)** —
Enter-Icon als Catalog-`foregroundResolver`. … **Effort: M.**" — kein
Postponement-Marker im Plan-Body.

**Realität (Implementation-Lauf D-2)**: Chunk 4.3 wurde als POSTPONED
implementiert (Refactor-Scope >> Plan-Effort). D-2 in state.md hat das
dokumentiert, aber ein Reviewer ohne Zugriff auf state.md (z.B. ein
zukünftiger Plan-Reader, der nur die Plan-Datei liest) hätte den Status
als Lücke gelesen.

**Resolution**:
- Plan §4 Chunk 4.3 explizit `⛔ Postponed (D-2)` markiert + Cross-Ref
  zum Folge-Plan-Stub.
- Plan §2 AC-6 Postponed-Ausnahme dokumentiert (der ENTER-`setForeground`-
  Treffer ist bekanntes Postponed-Item, nicht silent gap).
- Plan §8 Change History Entry für die Review-fix-Edits.
- Folge-Plan-Stub erzeugt:
  `docs/plans/2026-05-21 - dictate-keyboard-input-state-elaboration/dictate-keyboard-input-state-elaboration.md`
  enthält §1 Ziel + §2 Acceptance Criteria + §3 TBD-Hinweise, destilliert
  aus dem postponed Chunk 4.3 + D-2-Reasoning. Status: Skeleton (kein
  Implementer-ready) — Plan-Author muss Detail-Architektur ergänzen.

**Warum kein Code-Fix**: Per User-Direktive akzeptiere die Postponement
(Effort/Scope-Mismatch ist real, 🟡 Severity = nicht-bug-aktiv).
Plan-Edit ist die richtige Aktion, nicht 2-4h Implementation in einer
Review-fix-Wave.

**Follow-up**: Folge-Plan
`dictate-keyboard-input-state-elaboration` aufrufen sobald
Vol4-Material aktuell wird (gemeinsam mit Block 5 RecordingStateController
+ OQ-2 PromptQueue-Migration).

### D-6 (Review-fix G4, AC-4) — AC-4 Wording an Realität angepasst

**Plan-Text** (§2 AC-4, vor diesem Fix): „Jeder in AC-1 genannte Pref-
Toggle hat einen Reducer-Arm in `state/modules/`. Heute fehlen:
`Action.FeatureToggleAction.ToggleVibration` …, indem dieser Action-Arm
zu `AudioAction.ToggleVibration` umzieht und seinen Reducer-Body bekommt."

**Realität (verifiziert via grep)**:
`grep -n "ToggleVibration\|Pref\.Vibration" app/src/main/java/.../DictateInputMethodService.java`
zeigt nur **Read**-Sites für `Pref.Vibration`. Keine In-IME-Click-Site,
die `ToggleVibration` dispatchen würde. Der Vibration-Toggle existiert
ausschließlich in Settings Activity (außerhalb des IME-Service-Scopes).

**Resolution**: Plan §2 AC-4 Wording präzisiert: Action-Umzug bleibt
contingent auf eine zukünftige In-IME-Toggle-Site. AC-4 ist
informational — der Umzug ist sinnvoll vorbereitet (Reducer-Stub + Action),
aber nicht implementations-blockierend für den aktuellen Plan-Lauf.

**Warum konform zur Plan-Intention**: Plan §3.1 A-Liste enthält
ToggleVibration nicht (kein A-Site). Chunk 2.4 im Plan-Body sagt bereits
„falls Settings-Activity-Hooks oder zukünftige In-IME-Toggle-Buttons sie
braucht" — AC-4 wird damit konsistent.

**Follow-up**: Keine. Wenn jemals eine In-IME-Vibration-Toggle-Site
hinzukommt, ist Chunk 2.4 als implementations-bereiter Mini-Plan
verfügbar.

### D-7 (Review-fix G2, AC-5) — Pre-Bind-SP-Write-Fallback als legitimes Pattern B anerkannt

**Plan-Text** (§5 Worked-Example, vor diesem Fix): Der Pre-Bind-Branch
zeigte `if (pipelineBinder == null) return;` als Default-Pattern. AC-5
schrieb: „kein stiller SP-Write mehr, der den State später inkonsistent
macht".

**Realität (Implementer-Wahl)**: In 5 Click-Handlern wurde der
SP-Write-Fallback bewusst beibehalten:
- `:5152-5154` SmallMode
- `:5169-5171` SingleRowMode
- `:5202-5205` AudioFocus
- `:3037-3043` LastFileName / TranscriptionAudioFile (Import-Pfad)
- `:2715-2727` Language

Implementer-Rationale (in den Inline-Kommentaren): „user's choice
survives the narrow window before the binder attaches". Diese Begründung
ist UX-richtig: `bindService` ist asynchron (gleich-Prozess, aber
Main-Thread-Runnable-Delivery), das narrow Window zwischen
`onCreateInputView` und `onServiceConnected` ist real. Click-Events im
Window ohne Fallback wären silent no-op.

**Architekturkonflikt**: AC-1 / AC-5 sprachen wörtlich von "kein
SP-Write hits"; das Implementer-Pattern bricht den Wortlaut, ist aber
semantisch sinnvoll. Reviewer hat das richtigerweise als
Wording-Inkonsistenz geflagged.

**Resolution (Option B aus Aufgabenstellung — Pattern legitimieren)**:
- Plan §2 AC-5 erweitert um zwei Patterns:
  - **Pattern A** — defensive return (Default für nicht-User-Intent-Sites).
  - **Pattern B** — getaggter SP-Write-Fallback mit dem exakten Tag-
    Kommentar `// PRE-BIND-FALLBACK` als Whitelist-Marker.
- Alle 5 Click-Handler-Else-Zweige mit dem Tag versehen
  (review-fix-G2-Commit).
- Begründung im Plan-Body: analog zu AC-6 / B-2 / B-6 pre-bind
  View-Fallbacks — legitimer Architektur-Erkennungs-Tag, kein
  Plan-Verletzung.

**Lock-Mechanismus**: Der `CutoverArchitectureInvariantTest`-Lock
(G5) grept nach `sp.edit().put(...)` / `DictatePrefsKt.put(...)` auf
gespiegelten Prefs und allow-listet ausschließlich Stellen, deren
Vor-Zeile den exakten Tag-String trägt. Ungetaggte SP-Writes failen
den Test.

**Warum konform zur Plan-Intention**: §1 Ziel "Single-Dispatch-
Invariante" gilt für die **Live-Path-Mutationen** (binder verfügbar);
Pre-Bind ist explizit als degenerative Phase außerhalb des Live-Pfads
markiert. AC-7 (kein Custom-SP-Listener) ist orthogonal und bleibt
erfüllt.

**Follow-up**: Keine. Pattern B ist jetzt explizit erlaubt + getestet.

### D-8 (Review-fix G5) — CutoverArchitectureInvariantTest-Locks für AC-7 + AC-5

**Plan-Text** (§2 AC-7): „Keine Custom-`OnSharedPreferenceChangeListener`
außerhalb von `PipelinePrefMirror`." (§2 AC-5 nach D-7: getaggte
SP-Write-Fallbacks erlaubt, ungetaggte verboten.)

**Realität (vor diesem Fix)**: Beide ACs waren als grep-Kommandos im
Plan dokumentiert, hatten aber keinen automatisierten Lock. Reviewer
hat das richtigerweise als „Anti-Drift fehlt" geflagged.

**Resolution**: Zwei neue Lock-Tests in `CutoverArchitectureInvariantTest.kt`
(stilistisch analog zu den existierenden (a)..(g)-Locks):
- **(h) `noOnSharedPreferenceChangeListenerOutsidePipelinePrefMirror`**:
  Greppt alle `*.kt`/`*.java`-Files unter `src/main` nach
  `(set|register)OnSharedPreferenceChangeListener\(`. Allow-List: nur
  `PipelinePrefMirror.kt`. Stripper-Soundness-Self-Test inklusive
  (RR-4 false-GREEN-Mitigation).
- **(i) `directSharedPrefsWritesInImeAreTaggedAsPreBindFallback`**:
  Scannt die Raw-Source von `DictateInputMethodService.java` (Comments
  KEPT — der Tag lebt in einem `//`-Comment) nach
  `DictatePrefsKt.put(` / `sp.edit().put(` / `sp.edit().remove(` für eine
  Liste von 23 mirrored-oder-modul-besitzten Prefs. Allow-Mechanismus:
  Tag-String `PRE-BIND-FALLBACK` in einer der 8 Zeilen über dem Write.
  Window-Größe 8 ist groß genug für einen multi-line Comment-Block,
  klein genug für Locality. Non-Vacuity-Self-Test
  (`preBindFallbackTagLockIsNonVacuous`) verifiziert beide Richtungen
  (tagged → pass, untagged → fail).

**Warum konform zur Plan-Intention**: Die Locks operationalisieren AC-5
und AC-7 als CI-überprüfbare Invarianten statt manueller grep-Checks.
Das Testfile war explizit als „die D4 regression-lock the integration
agent asked for" deklariert (Klassen-KDoc) — die G5-Locks erweitern das
Muster konsistent.

**Verifikation**: 18 Tests im `CutoverArchitectureInvariantTest`-File,
BUILD SUCCESSFUL nach Hinzufügen der `PRE-BIND-FALLBACK`-Tags an den
5 IME-Sites (G2).

**Follow-up**: Keine.

### D-9 (Review-fix G6) — KDoc Atomic-Pair-Framing korrigiert

**Code-Text** (`RecordingModule.kt:147-156`, vor diesem Fix):
„Atomic pair: persist `Pref.LastFileName` AND clear `Pref.TranscriptionAudioFile`"

**Realität**: Die SP-Schreibe ist **nicht** atomar. Im Effect-Handler
(`runEffect`-Body) werden zwei sequentielle `services.prefs.persist(...)`-
Calls abgesetzt. Atomar ist nur die **State-Seite** (eine Action, ein
Reducer-Pass).

**Resolution**: KDoc neu geschrieben mit zwei expliziten Fakten:
1. State-Update ist atomar (eine Reducer-Pass — gilt theoretisch auch
   wenn die Action zukünftig State mutieren würde; aktuell ist sie
   effect-only).
2. SP-Writes sind sequenziell. Der zweite Write betrifft
   `Pref.TranscriptionAudioFile`, das **nicht** im Mirror ist — Consumer
   liest es nur beim nächsten `onStartInputView`, gated auf
   Action-Completion. Daher hat fehlende SP-Atomicity keine
   beobachtbaren Auswirkungen.
- Auch der Inline-Comment im `runEffect`-Handler entsprechend
  präzisiert.
- Das Wort „atomic pair" in `PersistLastFileName`-KDoc referenz
  entfernt und durch SoT-Verweis auf die `PersistImportedAudioFileName`-
  KDoc ersetzt.

**Warum konform zur Plan-Intention**: User-Direktive „Document
consistently so later programmers have an easier time than you did" —
die alte KDoc würde einen späteren Leser zu der Annahme verleiten,
SP-Writes seien atomic, was bei einer zukünftigen Refactor-Annahme zu
fehlerhafter Begründung führen könnte.

**Verifikation**: Test-Compile erfolgreich; KDoc-Verifikation visuell.

**Follow-up**: Keine.

## Open issues / postponed

- Block 5 (RecordingStateController retire) ist als Folge-Plan-Stub markiert (OQ-3) — wird in diesem Lauf **nicht** ausgeführt.
- OQ-2 PromptQueue-Migration ist als separater Folge-Plan markiert — out of scope.

## Final report

### Chunks abgeschlossen

13 von 14 geplanten Chunks DONE, 1 POSTPONED (Chunk 4.3 → Folge-Plan).

| Block | DONE | POSTPONED |
|---|---|---|
| 2 (Layout) | 2.1, 2.2 (pre-run) | — |
| 3 (Foundation + Audio) | 3.0, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6 | — |
| 4 (Cleanup) | 4.1, 4.2, 4.4, 4.5a, 4.5b, 4.5c, 4.6 | 4.3 |

### Commits (chronologisch, dieser Lauf)

| Chunk | SHA | Title |
|---|---|---|
| 3.0 | edfe8a2 | Foundation: PrefPersistenceService typed write seam |
| 3.1/3.2 | f39cc76 | AudioModule: PersistAudioFocusPref + ApplyAudioFocusRuntime |
| 3.3 | b9052ea | EditBarAudioFocusObserver: reactive twin |
| 3.4 | 13d85a2 | onAudioFocusToggled → dispatch(ToggleAudioFocusPref) |
| 3.5 | ecf5a77 | Remove audioFocusListener; add AudioFocus pref-cascade |
| 3.6 | 5629f6d | Route AudioFocusChange via dispatch |
| 4.1 | d5e05b8 | OverlayPermissionInfobarRenderer |
| 4.2 | 07ba170 | ImeViewBackend owns container background + accent text passes |
| 4.4 | ef24263 | RecordingModule owns LastFileName persistence |
| 4.5a | 4093adc | PipelinePrefMirror computed-mirror for InputLanguages |
| 4.5b | 8a47175 | Remove inputLanguagesListener; LanguageEffectiveObserver feeds chip |
| 4.5c | c66a3e5 | setLanguageFromPicker → SetEffectiveLanguage dispatch |
| 4.6 | c54100d | Remove dead recordingStateController.setCallback block |

### Test-Ergebnisse

`./gradlew :app:testDebugUnitTest` — **BUILD SUCCESSFUL** (alle Tests grün).
`./gradlew :app:assembleDebug` — **BUILD SUCCESSFUL** (Debug-APK gebaut).

Keine Flakiness beobachtet während des Laufs.

Neue Tests in diesem Lauf:
- `ModuleServicesTest` +2 (PrefPersistenceService production-wiring + RecordingFake)
- `AudioModuleTest` +10 (Persist+Runtime-Apply, ApplyAudioFocusRuntimeFromPref, Cascade-Edges)
- `EditBarAudioFocusObserverTest` +5 (neu)
- `RecordingModuleTest` +2 (OnAudioFileImported), 2 angepasst (StartRecording Effects)
- `PipelinePrefMirrorTest` +2 (InputLanguages computed-mirror), 1 angepasst
- `LanguageModuleTest` +3 (SetEffectiveLanguage), 1 angepasst

### Plan-Intention Deviations (final)

| ID | Chunk | Beschreibung |
|---|---|---|
| D-1 | 3.5 | Cross-Module-Cascade `ApplyAudioFocusRuntimeFromPref` ergänzt — Plan-Text übersah, dass externe SP-Writes ohne dies das Runtime-Apply nicht erreichen würden. |
| D-2 | 4.3 | Enter-Icon Catalog-Resolver postponed — Refactor-Scope (Unit→State, 5 Catalog-Slots, Registry) >> Plan-Effort "M". 🟡 Severity. |
| D-3 | 4.5c | Curation-Logik bleibt in `LanguageResolver` (Effect delegiert) statt sie ins LanguageModule zu duplizieren — Plan-Intention "Single-Dispatch" erfüllt, SoT für Curation bleibt unverändert. |

### Postponed Items

1. **Chunk 4.3** (B-5 Enter-Icon Catalog-Resolver) — Folge-Plan-Stub: `dictate-keyboard-input-state-elaboration`. 🟡 nicht-bug-aktiv.
2. **Block 5** (RecordingStateController retire) — OQ-3 entschieden als Folge-Plan, Skizze in Plan §6.2 OQ-3-Annex hinterlegt.
3. **OQ-2** (PromptQueue → Orchestrator-State-Modul) — entschieden als separater Folge-Plan, nicht Indirektion-Reduktion sondern Architektur-Erweiterung.

### AC-Status

- **AC-1** (kein SP-Write in Click-Handlern für gespiegelte Prefs): ✅ erfüllt für alle Prefs in der Liste. Pre-bind-Fallbacks in click-handlers sind tagged.
- **AC-2** (Effect-getriebene Pref-Persistenz): ✅ erfüllt — alle gespiegelten Prefs werden via `services.prefs.persist(...)` oder direkt durch ihre Module geschrieben.
- **AC-3** (PipelinePrefMirror SP→State bleibt erhalten): ✅ erfüllt — Mirror unverändert + erweitert um Language-computed-mirror.
- **AC-4** (Reducer-Arm-Vollständigkeit): ⚠️ teilweise — `ToggleVibration` (ursprünglich Chunk 2.4) wurde im Lauf nicht behandelt (nicht im Hauptpfad gesehen; Plan-Text Chunk 2.4 hat Effort "M"-Status, blieb offen).
- **AC-5** (Pre-Bind-Verhalten): ✅ erfüllt — alle migrierten Handler haben dokumentierte `if (pipelineBinder == null) { return; }` oder Fallback-Pfade.
- **AC-6** (keine direkte View-Mutation außerhalb zugelassener Render-Owner): ✅ — neuer `OverlayPermissionInfobarRenderer` in der Owner-Liste; `ImeViewBackend` deckt Container-Theme + Accent-TextViews. Verbleibend: Chunk 4.3 Enter-Icon (postponed, dokumentiert).
- **AC-7** (kein Custom-`OnSharedPreferenceChangeListener`): ✅ erfüllt — beide entfernt (`audioFocusListener` + `inputLanguagesListener`).
- **AC-8** (AudioFocusChangeListener via dispatch): ✅ erfüllt durch Chunk 3.6.
- **AC-9** (Effects-Ordnung): ✅ implizit erfüllt — dispatch ist synchron, Effects laufen vor side-channel-Animations.

### Was nicht erledigt wurde (Lauf-Scope)

- **Chunk 2.4** (`ToggleVibration` von FeatureToggleAction zu AudioAction umziehen) — nicht in §4 Block 2 als „abgeschlossen" markiert im Plan, war im "offen" gelistet. Da keine Click-Site identifiziert wurde (im Plan §3.1 A-Liste nicht enthalten), und der Plan dies als „falls Settings-Activity-Hooks oder zukünftige In-IME-Toggle-Buttons sie braucht" qualifiziert, **postponed**: Folge-Plan-Material falls eine In-IME-Vibration-Toggle-Site jemals entsteht.
- **Chunk 2.3** (Feature-Toggles Click-Sites identifizieren) — Plan markiert „Optional / nur wenn gefunden". Keine in-IME Click-Sites für Rewording/AutoFormatting/InstantOutput/AutoEnter im Code gefunden — bleibt offen falls solche Sites auftauchen.

