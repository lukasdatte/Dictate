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

