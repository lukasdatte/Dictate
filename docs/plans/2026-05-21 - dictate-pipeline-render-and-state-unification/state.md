---
plan: dictate-pipeline-render-and-state-unification
status: in-progress
created: 2026-05-21
---

# Implementation State — dictate-pipeline-render-and-state-unification

## Run metadata
- Start: 2026-05-21
- Worktree: ./worktrees/feature/dictate-keyboard-layout-refactor
- Last commit at run-start: 6a1bed5341eec1e5ee4fe79d0746ba0f7121e987
- Branch: feature/dictate-keyboard-layout-refactor

## Open Questions
Alle vorab durch User-Review (§9.0) entschieden — Implementer startet ohne weitere Rücksprache.

| OQ | Entscheidung | Variante |
|---|---|---|
| OQ-1 | Pipeline-Label zweizeilig | A |
| OQ-2 | Step-Name 1:1 durchreichen | A |
| OQ-3 | `recordingStateController` als `@Deprecated` lassen | A |
| OQ-4 | Pipeline-Ticker-Intervall 1000 ms | Empfehlung |
| OQ-5 | OVERLAY_RECORD Long-Press = no-op | A |

## Block status

| Block | Chunks | Status | Commit(s) | Notes |
|---|---|---|---|---|
| 1 — Quick-Wins (B-B + B-C) | 1.1, 1.2 | DONE | 8d7507c | XML foreground entfernt + BACKSPACE-Affordance-Branch im IME-Lambda; 3 neue Invariant-Tests in CutoverArchitectureInvariantTest |
| 2 — Affordance-Hook-Symmetry (B-A) | 2.1, 2.2, 2.3 | DONE | e071ee6 | OVERLAY_RECORD im RECORD-Branch; KDoc dokumentiert Call-Sites; affordanceHookHandlesBothRecordIds invariant |
| 3 — Prompt-Chips state-driven (B-E) | 3.1, 3.2, 3.3 | DONE | 789b00d | PromptChipsBusyObserver + state-driven Predicate; @Deprecated auf RecordingStateController; updatePromptButtonsEnabledStateReadsOrchestratorNotLegacyController invariant |
| 4 — Pipeline-Label step-name (B-D-1) | 4.1, 4.2, 4.3 | DONE | a6608d0 | formatPipelineLabel-Signatur erweitert; resolver forwarded currentStepName; XML maxLines=2; 4 neue TextResolversTest-Cases |
| 5 — Pipeline-Timer-Ticker (B-D-3) | 5.1–5.5 | DONE | 3c1bc86 | TickPipelineTimer action + reducer arm + PipelineActivityTickerObserver (mit TickerScheduler-Abstraction für JVM-Tests); 8 Observer-Tests + 4 Reducer-Tests |

## Plan-intention deviations

**D-1 — Block 1 Chunk 1.1 (B-B, pause_btn foreground)**
- **Plan-Text:** entfernt nur die zwei `android:foreground` / `android:foregroundGravity` Zeilen, keine Kommentaranchors.
- **Was getan:** Plus inline-Kommentar `<!-- pause_btn — DO NOT add android:foreground here … -->` mit Verweis auf den Catalog-iconResolver + auf den Plan §4.2.
- **Warum:** ohne Anker reproduziert ein zukünftiger Edit das Bug. Die anderen `android:foreground`-Sites im XML (z.B. `resend_btn`, `widget_toggle_btn`) sind via Catalog überschrieben (siehe `B4-VAL F-29`-Kommentar), aber `pause_btn` ist der einzige Slot mit echtem `iconResolver` UND statischem Foreground → die Asymmetrie braucht eine Erklärung im Code.
- **Plan-Intention erhalten:** Ja (Single Writer für Pause-Icon-Achse).

**D-2 — Block 2 Chunk 2.1 (B-A, OVERLAY_RECORD branch)**
- **Plan-Text §5.4** zeigt zwei separate Branches: `(RECORD || OVERLAY_RECORD) && isLongPress -> onRecordLongClicked()` und `(RECORD || OVERLAY_RECORD) -> prepareCatalogStopRecordingIfActive()`.
- **Was getan:** OQ-5 (no-op für OVERLAY_RECORD long-press) als eigenen Branch dokumentiert (mit Kommentar warum). Das Long-Press im Plan-Text würde `onRecordLongClicked()` (Settings-Launch) im Widget feuern — Variante-A laut OQ-5 wäre das ein UX-Bruch.
- **Warum:** Plan §5.4 widerspricht §9.5 (OQ-5 Variante A = Widget-Long-Press no-op). §9.5 ist die User-Entscheidung. Plan-Text §5.4 zeigt das alte Vorschlag-Layout vor der Entscheidung.
- **Plan-Intention erhalten:** Ja (User-Decision wins).

**D-3 — Block 3 Chunk 3.3 (B-E, invariant scope)**
- **Plan-Text §5.7:** "Architektur-Locker: …sucht jede `recordingStateController.getState()`-Call-Site …und vergleicht gegen eine Whitelist."
- **Was getan:** Ein gezielter Invariant-Test `updatePromptButtonsEnabledStateReadsOrchestratorNotLegacyController` der ausschließlich den Body von `updatePromptButtonsEnabledState` scannt — nicht alle Call-Sites file-wide.
- **Warum:** Plan §9.3 entscheidet explizit `recordingStateController` post-cutover *zu lassen* (Variante A) — ein file-wide Whitelist-Lock würde alle bestehenden Pre-Bind-Fallback-Reads als Violations melden und Block 3 künstlich verbreitern. Die fokussierte Variante locked genau die AC-E-Surface (Prompt-Chips) ohne den Folge-Plan-Scope vorwegzunehmen.
- **Plan-Intention erhalten:** Ja (AC-E + AC-P-1 für Prompt-Chips locked; broader sweep bleibt für Folge-Plan).

**D-4 — Block 4 Chunk 4.1 (B-D-1, single-line fallback)**
- **Plan-Text §5.1** Format-Beispiel: immer zweizeilig `"<phase>\n<N>/<M> M:SS"`.
- **Was getan:** zweizeilig wenn `stepName` non-blank, einzeilig als Fallback wenn null/leer.
- **Warum:** Right after `StartPipeline` (vor erstem `StepStarted`) ist `currentStepName == null` → ohne Fallback würde das Format `"\nN/M  M:SS"` produzieren (leerer erster Line), der Button würde von 1-Line → 2-Line → 1-Line bei jedem `StepStarted` flicker. Der Fallback verhindert das UX-Jitter ohne den Step-Name-Slot zu verlieren.
- **Plan-Intention erhalten:** Ja (Variante A "zweizeilig" für den Hauptfall; Fallback ist defensiv und unbemerkt).

**D-5 — Block 5 Chunk 5.3 (B-D-3, TickerScheduler abstraction)**
- **Plan-Text §5.2** schlägt vor: "`PipelineActivityTickerObserver` analog zu `RecordingActivityTickerObserver`".
- **Was getan:** Plus eine `TickerScheduler` Interface (mit `HandlerTickerScheduler` als Produktion-Adapter und einem In-Memory-Fake im Test).
- **Warum:** `Handler.postDelayed` / `removeCallbacks` sind `final` → Kotlin/Java-Tests können sie nicht überschreiben. K-4 (kein Android in Unit-Tests). Das Pattern existiert bereits (`PauseTimeoutScheduler`) — analoge Abstraktion ist DRY-konsistent. Trade-off: 12 Zeilen mehr Code für volle JVM-Test-Determinismus.
- **Plan-Intention erhalten:** Ja (1 s Ticker durch Reducer; sauber JVM-testbar wie der Plan §5.8 verlangt).

## Open issues / postponed

- **broader `recordingStateController.getState()` removal**: §5.7-Whitelist-Lock + Source-side Migration aller Pre-Bind-Reads bleibt für Folge-Plan `dictate-recording-state-controller-removal` (OQ-3). Klasse ist `@Deprecated` markiert; im DictateInputMethodService bleiben ~8 Pre-Bind-Reads aktiv.
- **Manual device verification**: ADB Wireless ist instabil (User-Memory). Auf-Gerät-Verifikation der fünf Bugs wurde NICHT durchgeführt; alle ACs sind JVM-test-verifiziert + APK kompiliert. User-Manual-Test-Pass ist empfohlen:
  - B-A: Widget öffnen → aufnehmen → SEND → Text muss im Editor ankommen
  - B-B: Pause-Icon zeigt nur Pause-Bars (kein Rechteck dahinter)
  - B-C: Backspace long-press löscht beschleunigend (50→25→10→5 ms)
  - B-D-1: Pipeline-Phase zeigt Step-Name (e.g. "Transcribe\n1/2 0:08")
  - B-D-3: Timer tickt sekündlich während Pipeline-Running

## Final report

Alle fünf Blöcke implementiert und committed. 1321 JVM-Tests grün, 0 Failures, 0 Errors. Debug-APK kompiliert sauber.

Per-bug verdict:
- **B-A** ✓ fixed (Block 2, commit e071ee6): OVERLAY_RECORD-Branch in IME-Lambda → R-1-Snapshot fires → Pipeline läuft
- **B-B** ✓ fixed (Block 1, commit 8d7507c): pause_btn `android:foreground` entfernt → einziger Icon-Writer ist Catalog-iconResolver
- **B-C** ✓ fixed (Block 1, commit 8d7507c): BACKSPACE im affordance-gate → onBackspaceLongClicked() wird wieder ausgeführt
- **B-D-1** ✓ fixed (Block 4, commit a6608d0): currentStepName im formatPipelineLabel → zweizeiliges Label mit Step-Name
- **B-D-2** ✓ fixed strukturell (durch B-A): Pipeline erreicht jetzt `Running` → Counter wird sichtbar (kein eigener Fix nötig)
- **B-D-3** ✓ fixed (Block 5, commit 3c1bc86): TickPipelineTimer-Action + PipelineActivityTickerObserver → Timer tickt sekündlich
- **B-E** ✓ fixed (Block 3, commit 789b00d): updatePromptButtonsEnabledState liest aus pipelineBinder.getState()
