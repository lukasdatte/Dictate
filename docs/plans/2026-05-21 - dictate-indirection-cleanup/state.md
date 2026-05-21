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
| 3 | 3.5 | DONE | (pending) | `audioFocusListener` + alle 3 Register-/Unregister-Sites entfernt. Neue Action `ApplyAudioFocusRuntimeFromPref(value)` + Cross-Module-Cascade in `AudioModule.onCrossModuleStateChange` (deckt externe Settings-Activity-Writes ab). 5 neue Tests. |
| 3 | 3.6 | TODO | — | `audioFocusRequest` → `dispatch(OnAudioFocusGrantChanged)` (C-1) |
| 4 | 4.1 | TODO | — | `OverlayPermissionInfobarRenderer` (B-1 + C-7) |
| 4 | 4.2 | TODO | — | Container-Theme in `ImeViewBackend.applyTheme` (B-3 + B-4) |
| 4 | 4.3 | TODO | — | Enter-Icon Catalog-Resolver (B-5) |
| 4 | 4.4 | TODO | — | LastFileName + Import-File Pref-Persistenz (A-4 + A-5) |
| 4 | 4.5a | TODO | — | InputLanguages Mirror-Aufnahme |
| 4 | 4.5b | TODO | — | `inputLanguagesListener` entfernen |
| 4 | 4.5c | TODO | — | `setLanguageFromPicker` → `SetEffectiveLanguage` dispatch |
| 4 | 4.6 | TODO | — | RecordingStateController.Callback entfernen (C-4) |

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

## Open issues / postponed

- Block 5 (RecordingStateController retire) ist als Folge-Plan-Stub markiert (OQ-3) — wird in diesem Lauf **nicht** ausgeführt.
- OQ-2 PromptQueue-Migration ist als separater Folge-Plan markiert — out of scope.

## Final report (filled at end)

(Wird am Ende des Laufs ergänzt.)
