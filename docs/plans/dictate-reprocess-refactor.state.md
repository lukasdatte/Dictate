# State: dictate-reprocess-refactor

**Plan:** [→ dictate-reprocess-refactor.md](dictate-reprocess-refactor.md)
**Chunks-Datei:** [→ dictate-reprocess-refactor.chunks.json](dictate-reprocess-refactor.chunks.json)
**Ziel:** "Persist first, process later" Refactoring: Audio + Session-Daten werden atomar persistiert bevor API-Calls starten. Zentrale ActiveJobRegistry mit StateFlow, kooperative Cancellation via CancellationToken, Reprocess-aus-History-UI, Double-Enum-Pattern.
**Gestartet:** 2026-04-16

## Chunks

| # | Chunk | Main-Sektionen | ~Tokens | Status | Agent-ID (Impl) | Agent-ID (Val) | Abgeschlossen |
|---|-------|---------------|---------|--------|-----------------|----------------|---------------|
| 1 | Data Layer Foundation | Context, Design, File Overview, Phase 0, 1, 2, 6, Risks, Open Decisions, Impl Order | ~11700 | ✅ | a6c3416fc6675debf | a563f14ec60670972 | 2026-04-16 |
| 2 | Pipeline & Registry | Phase 3, 4, 5 | ~11400 | ✅ | a4c0d1fdb30da7005 | ade7564ea2651f212 | 2026-04-16 |
| 3 | UI Integration | Phase 7-13, Verification | ~13300 | ✅ | aaabc94d29505e898 | af2cdd9d74a792e25 | 2026-04-16 |

## Issues

### Chunk 1: Data Layer Foundation
- **Behoben K1:** `SessionErrorType` dupliziert `AIProviderException.ErrorType` → Enum gelöscht, Reuse wie in `DATABASE-PATTERNS.md` dokumentiert (Kritisch)
- **Behoben W1:** `extractDurationSeconds` dupliziert zwischen `Recording` und `DurationHealingJob` → auf `RecordingRepository` konsolidiert; Open Decision SA-1 resolved; `Recording` ist jetzt Android-Framework-frei (Wichtig)
- **Behoben W2:** `compressToOpus` silent no-op → als Stub klar markiert, nicht mehr unconditional aufgerufen (Wichtig)
- **Behoben W3:** Executor-Leak in `DictateApplication.onCreate()` → `executor.shutdown()` hinzugefügt (Wichtig)
- **Behoben W5:** `CREATE INDEX` in Migration ohne `IF NOT EXISTS` → konsistent mit `Migrations.kt` (Wichtig)
- **Behoben N5:** `deleteBySessionId` KDoc ergänzt um Best-effort-Ordering + Healing-Reconciliation (Nice-to-Have)
- **Deferred N1:** Cache-Invalidation cross-process (History → IME) → Chunk 3 Scope
- **Ignoriert N2:** `MigrationTo3.kt` vs `Migrations.kt` Datei-Konvention → Bikeshedding
- **Deferred N4:** `updateAudioFilePath` ohne Caller → wird in Chunk 2 verwendet
- **Ignoriert N6:** Repository Context-Coupling → Scope-Creep, kein Blocker

### Chunk 2: Pipeline & Registry
- **Behoben K1 + W5:** Resume Off-by-x (chainIndex vs queuedIds-Raum) + ERROR-Steps-Invalidation → Helper `computePromptIndexOffset` + `invalidateDownstream` vor Resume (Kritisch)
- **Behoben K2 + N4:** `CANCELLED` als FAILED persistiert → Helper `isCancellation(t)`, erkennt `AIProviderException(CANCELLED)`, `InterruptedIOException`, `CancellationException` konsistent an allen Catch-Stellen (Kritisch)
- **Behoben W1:** Stale Keyboard-Cache nach Session-Creation → `invalidateLastKeyboardCache` im persist, `notifyKeyboardSessionCompleted` erst nach `finalizeCompleted` (Wichtig)
- **Behoben W2:** `modelOverride` silent no-op → explizite KDoc "currently IGNORED" + TODO(Chunk 3). Option A/B würde Runner-Layer aufbrechen und gehört in Chunk 3 (Wichtig)
- **Behoben W3:** Duplizierte DAO-Methoden → `clearCurrentForSession`, `getMaxVersionForSession`, `getAllVersionsForSession`, `getMaxVersionAtIndex` gelöscht (Wichtig)
- **Behoben W4:** Dual-Cancel (Orchestrator vs JobExecutor) → KDoc-Warnung an `PipelineOrchestrator.cancel()`, Migration in Chunk 3 (Wichtig)
- **Behoben W6:** `toPipelineConfig()` Plan-Deviation → KDoc erklärt SOLID/ISP-Grund (Wichtig)
- **Deferred N1:** Dual-Cancellation-Check (cancelled flag + token.isCancelled) → Chunk 3 cleanup
- **Ignoriert N2:** `config.recordingsDir` vs `repository.recordingsDir` → Edge-Case
- **Ignoriert N3:** `ActiveJobRegistry.update` silent no-op → Nice-to-Have Logging

### Chunk 3: UI Integration
- **Behoben K1 + W8:** Nested-Executor-Bug → `*Blocking` Varianten auf Orchestrator + `PipelineRunner`-Interface extrahiert; JobExecutor ruft synchron. Plus `JobExecutorTest` (4 Tests) der den Fix verifiziert (Kritisch)
- **Behoben K2:** `onReprocessWithEdit` ≡ `onDirectReprocess` → V1-Chooser-Fallback verdrahtet, free-text-Prompts abgelehnt (Kritisch; V2 mit Drag-to-Reorder deferred als saubere Follow-up-Story)
- **Behoben K3:** Keine reaktive History-UI → `ActiveJobRegistryObserver` mit `repeatOnLifecycle(STARTED)` in beiden Activities (Kritisch)
- **Behoben K4:** `currentSessionId` bei Reuse → In allen 4 Blocking-Methoden (`runTranscriptionPipelineBlocking`, `resumePipelineBlocking`, `regenerateStepBlocking`, `runPostProcessingBlocking`) gesetzt (Kritisch)
- **Behoben W1:** ReprocessStaging überlebt View-Recreation → `restoreReprocessStaging`-Field analog zu `restoreAutoEnter` (Wichtig)
- **Behoben W2:** ES/PT-Übersetzungen (14 neue Keys) natürlichsprachlich (Wichtig)
- **Behoben W3:** Initiale Recording-Pipeline via JobExecutor → `JobRequest.TranscriptionPipeline(kind=RECORDING, ...)`, `preAllocatedSessionId` eingeführt (Wichtig)
- **Behoben W4:** `handleReprocessSend` File-Existenz-Check (Wichtig)
- **Behoben W5:** DB-Read auf `dbExecutor`, mainHandler für JobExecutor.start (Wichtig)
- **Behoben W6:** Adapter-Doppel-Write → Single source of truth = `PipelineUiState.ReprocessStaging.editableQueue` (Wichtig)
- **Ignoriert W7:** `MainButtonsController` vs `KeyboardStateManager` Konsolidierung → architektonisch sauber, nur Plan-Doku weicht ab
- **Ignoriert N1, N2, N4, N5:** Nice-to-Haves ohne Nutzen-Korrektheits-Tradeoff
