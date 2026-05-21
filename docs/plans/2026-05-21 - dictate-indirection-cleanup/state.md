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
| 3 | 3.0 | TODO | — | Foundation-Vorlauf: `PrefPersistenceService` + DI ins ModuleServices |
| 3 | 3.1 | TODO | — | `Effect.PersistAudioFocusPref` (über generischen Pfad) |
| 3 | 3.2 | TODO | — | Live-Hook `Effect.ApplyAudioFocusRuntime` |
| 3 | 3.3 | TODO | — | EditBarController reactiver attach für Audio-Focus-Twin |
| 3 | 3.4 | TODO | — | `onAudioFocusToggled` → `dispatch(ToggleAudioFocusPref)` |
| 3 | 3.5 | TODO | — | `audioFocusListener` entfernen (C-3) |
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

Wird hier dokumentiert sobald Plan-Text und Realität auseinanderdriften.

## Open issues / postponed

- Block 5 (RecordingStateController retire) ist als Folge-Plan-Stub markiert (OQ-3) — wird in diesem Lauf **nicht** ausgeführt.
- OQ-2 PromptQueue-Migration ist als separater Folge-Plan markiert — out of scope.

## Final report (filled at end)

(Wird am Ende des Laufs ergänzt.)
