---
plan: dictate-pipeline-render-and-state-unification
status: not-started
created: 2026-05-21
---

# Implementation State — dictate-pipeline-render-and-state-unification

> Skeleton file. The implementer (e.g. via `implement-long-plan-v2`)
> populates the per-block/per-chunk progress as work proceeds.
>
> Block boundaries and chunk IDs follow the plan file §6.

## Open Questions (must resolve before code)

- [ ] OQ-1 — Pipeline-Label-Layout zweizeilig oder einzeilig?
      _Plan-Empfehlung: zweizeilig (`\n`-Separator)._
- [ ] OQ-2 — Step-Name-String 1:1 durchreichen oder via R.string?
      _Plan-Empfehlung: 1:1 (analog `PipelineStepRowRenderer`)._
- [ ] OQ-3 — `recordingStateController` post-cutover löschen?
      _Plan-Empfehlung: lassen, `@Deprecated` markieren, Folge-Plan._
- [ ] OQ-4 — Pipeline-Ticker-Intervall: 1 s, 100 ms, adaptiv?
      _Plan-Empfehlung: 1000 ms._
- [ ] OQ-5 — OVERLAY_RECORD-Long-Press im Widget no-op oder Settings-Launch?
      _Plan-Empfehlung: no-op (Variante A)._

## Block 1 — Quick-Wins

- [ ] **Chunk 1.1** — Entferne `android:foreground` an `pause_btn`.
      File: `app/src/main/res/layout/activity_dictate_keyboard_view.xml:180-181`.
- [ ] **Chunk 1.2** — Backspace-Long-Press Affordance-Branch.
      Files: `ImeViewBackend.kt`, `DictateInputMethodService.java`.
- [ ] Block-1 commit + manual device-verify.

## Block 2 — Affordance-Hook-Symmetrie (B-A Critical-Fix)

- [ ] **Chunk 2.1** — Erweitere IME-Affordance-Lambda um `OVERLAY_RECORD`.
      File: `DictateInputMethodService.java:1415-1462`.
- [ ] **Chunk 2.2** — KDoc-Update für `prepareCatalogStopRecordingIfActive`.
- [ ] **Chunk 2.3** — `CutoverArchitectureInvariantTest`-Eintrag.
- [ ] Block-2 commit + manual device-verify (Widget-SEND → text in editor).

## Block 3 — Prompt-Chips state-driven (B-E)

- [ ] **Chunk 3.1** — `updatePromptButtonsEnabledState` auf
      `pipelineBinder.state` migrieren.
- [ ] **Chunk 3.2** — Observer registrieren (analog
      `PipelineUiStateObserver`).
- [ ] **Chunk 3.3** — Architektur-Invariant-Test.
- [ ] Block-3 commit + manual verify (chips disable during Active/Pipeline).

## Block 4 — Pipeline-Label-Erweiterung (B-D-1)

- [ ] **Chunk 4.1** — `LayoutStrings.formatPipelineLabel`-Signatur erweitern.
- [ ] **Chunk 4.2** — `resolveRecordButtonTextPipeline` passt sich an.
- [ ] **Chunk 4.3** — Test-Wiring-Sites updaten.
- [ ] Block-4 commit + manual verify (step-name visible during Running).

## Block 5 — Pipeline-Timer-Ticker (B-D-3)

- [ ] **Chunk 5.1** — `Action.PipelineAction.TickPipelineTimer`.
- [ ] **Chunk 5.2** — `PipelineModule.reduce`-Arm.
- [ ] **Chunk 5.3** — `PipelineActivityTickerObserver`.
- [ ] **Chunk 5.4** — Observer-Wiring im IME.
- [ ] **Chunk 5.5** — Unit-Test.
- [ ] Block-5 commit + manual verify (timer ticks per second during Running).

## Phase 4 — Integration / Cross-Block

- [ ] Run full test suite.
- [ ] Manual device-walkthrough through all 5 bugs.
- [ ] Architektur-Invariant-Test suite green.

## Phase 5 — Closure

- [ ] Final commit + plan archive.
- [ ] EN translation of plan file.
- [ ] Closure report.
