# History Regenerate / Post-Process Hardening

---
date: 2026-07-02
author: Lukas + Claude (multi-agent review session)
type: Research
status: Research
context: Four confirmed findings show HistoryDetailActivity's AI operations bypass the app's job, prompt, and formatting infrastructure — one consolidation fixes all four.
related-plan: n/a (seeded by 2026-07-02 - feature-wiring-code-review.md, F-055/F-108/F-109/F-111)
related-adrs: —
---

The redesigned history detail screen runs its AI operations (Regenerate, Other prompt, Post-process) through hand-rolled paths that bypass three pieces of infrastructure the pipeline already has: `JobExecutor`/`ActiveJobRegistry` (lifecycle survival + mutual exclusion), `PromptService` (prompt construction contract), and `AutoFormattingService` (auto-format prompts). All four findings are adversarially confirmed with high confidence and share one architectural root cause — so this is one refactor topic, not four point fixes.

## 1. Vision and Motivation

### 1.1 Why this exists — the shared root cause

`HistoryDetailActivity` executes regenerate/post-process via a **private Activity-scoped executor** and calls `AIOrchestrator.complete()` **raw**. Everything else follows: rotation kills work, the registry doesn't know about it, prompts diverge from the pipeline's, and AUTO_FORMAT steps regenerate without any instruction. Notably, `JobExecutor` **already defines** `JobRequest.StepRegenerate` and `JobRequest.PostProcess` variants (`JobExecutor.kt:160-169`) — the registry-tracked path exists but is bypassed.

### 1.2 The four confirmed findings

1. **F-055 — Activity-scoped executor.** `regenerateExecutor` (`HistoryDetailActivity.java:91`) is `shutdownNow()`-terminated in `onDestroy` (`:731`). No `configChanges` in the manifest ⇒ rotation destroys mid-call: queued work dropped, LOADING state unrecoverable (recreated instance renders IDLE with no reattach). Invisible to `ActiveJobRegistry` ⇒ a reprocess can start concurrently with a regenerate **on the same session**, racing writes to the same processing-step chain (`setUiState(LOADING)` disables no buttons; guards check only registry state, `:285/:293/:454`). Verification nuance: on rotation `isFinishing()` is false, so the missing-Toast claim applies to back-press/finish; whether the in-flight HTTP write lands depends on socket interruptibility — the guaranteed losses are UI state and queued work.
2. **F-109 — PromptService bypass.** `complete(step.getInputText(), promptText)` (`:523/:628`) puts the raw instruction into the *system* prompt and skips the XML `PromptBuilder` structure + `PromptContext.QUEUED` system prompt. Because the pipeline persists the **built** `pp.userPrompt` as the step's `inputText` (`PipelineOrchestrator.kt:1435`), regenerating a QUEUED_PROMPT step feeds an already-XML-wrapped prompt back in and applies the instruction twice in a different shape. Regenerated versions are produced under a different prompt contract than v1 — defeating the versioning UI's comparison purpose. (Verifier note: the JobExecutor-routed regenerate at `PipelineOrchestrator.kt:623-626` *also* double-wraps, though with the correct system prompt — fix both.)
3. **F-108 — AUTO_FORMAT regenerate sends no instruction at all.** AUTO_FORMAT steps persist `promptUsed = null` (`PipelineOrchestrator.kt:1316-1323`); the unconditional Regenerate button (`:423`) then calls `complete(inputText, null)` — the model receives the bare transcript with no system prompt and no PromptBuilder wrapping, and will answer/continue the transcript instead of reformatting. The garbage becomes the new current version and propagates into `final_output_text`. Recoverable via the version chooser, but silently wrong.
4. **F-111 — Session created before prompt chosen.** `createPostProcessingSession()` inserts the POST_PROCESSING row *before* the chooser opens (`:575-598`); dismissing the sheet leaks a permanent ghost "Recorded" entry (immune to auto-cleanup: `inserted_at` never set; immune to ghost-promotion: no audio paths). Pending-chooser context lives in bare instance fields (`:81-84`, no `onSaveInstanceState`) while the BottomSheet *does* survive rotation ⇒ post-rotation prompt choice silently no-ops. The reprocess-edit path demonstrates the rotation-safe pattern (context encoded in the fragment tag, `:178-180`).

### 1.3 Discarded Alternatives

- **Point-fixing each finding in the Activity** — leaves the divergent execution model in place; the next feature on this screen inherits the same four bug classes. Rejected per the sustainability baseline.
- **`android:configChanges` to dodge rotation** — masks F-055's lifecycle symptom only; registry invisibility, prompt divergence, and the orphan session remain.

## 2. Findings + Conclusions — target architecture

**Route all three operations through `JobExecutor`:**

1. Use (finish and wire) the existing `JobRequest.StepRegenerate` / `JobRequest.PostProcess` variants so regenerate/post-process survive the Activity, register in `ActiveJobRegistry`, and are mutually exclusive with reprocess by construction. The Activity becomes a thin dispatcher + observer, like the reprocess flow (`:492`) already is.
2. **Prompt correctness inside the job layer, not the Activity:**
   - Persist the **raw** prompt input separately from the built prompt (schema addition), or derive the raw instruction from `promptUsed`; regeneration re-runs `PromptService.buildQueuedPrompt(rawPrompt, originalInput)`. This fixes the double-wrap on *both* the Activity path and the existing JobExecutor path (`PipelineOrchestrator.kt:623-626`).
   - `StepType.AUTO_FORMAT` regeneration routes through `AutoFormattingService` (its own system/rules/examples prompts + PromptBuilder user prompt). Alternatively hide Regenerate/Other-prompt on auto-format rows — but re-running the formatter is the semantically right offer.
3. **Post-process session lifecycle:** create the session **in** `onPromptChosen` (create + append step + finalize in one transaction), or delete the pre-created row on sheet dismiss. Encode chooser context in the fragment tag/arguments like the reprocess-edit path so rotation survives.

**Fix order:** F-111 is independent and small — can ship first. F-055 (JobExecutor routing) is the structural step; F-109/F-108 (prompt construction) land naturally inside it.

## 3. Testing Approach

- **Unit:** prompt-construction tests asserting regenerate of a QUEUED_PROMPT step produces byte-identical built prompts to the original pipeline call (guards the double-wrap regression); AUTO_FORMAT regenerate assembles the AutoFormattingService prompt set.
- **Unit (lifecycle):** JobExecutor test — regenerate job registers/unregisters in `ActiveJobRegistry`; `startHistoryReprocess` blocked while a regenerate is active on the same session.
- **Regression (F-111):** dismissing the chooser leaves no POST_PROCESSING row; choosing after simulated recreation still completes.
- **Device:** rotation mid-regenerate → operation completes and the reopened detail screen shows the new version.

## 4. Information Gaps

1. **Schema decision for raw-vs-built prompt persistence** (new column vs. re-derivation from `promptUsed`) — owner: implementer, consult `docs/DATABASE-PATTERNS.md` for the migration; fallback: re-derive from `promptUsed`, accepting that pre-existing steps regenerate from the built prompt (status quo, flagged in UI copy if desired).
2. **UX while a job is active** (disable which buttons, show which progress surface) — owner: user decision; fallback: mirror the reprocess flow's gating (`ActiveJobRegistry.isActive(sessionId)`).

## 5. Change History

### 2026-07-02 — Initial research consolidation

- **Trigger:** Whole-app review; history seed agent + history sweep agent converged on the shared root cause.
- **What changed:** Document created from F-055 (big topic) + F-108/F-109/F-111 satellites.

## 6. References

- Parent catalog: [`2026-07-02 - feature-wiring-code-review.md`](<2026-07-02 - feature-wiring-code-review.md>) — F-055, F-108, F-109, F-111 (full evidence + verification notes).
- Code: `history/HistoryDetailActivity.java:81-91,136-147,423,453-456,492,518-566,575-621,623-662,711-731`, `core/PipelineOrchestrator.kt:623-626,705-706,737,1316-1343,1433-1437`, `core/JobExecutor.kt:116-169`, `ai/AIOrchestrator.kt:84`, `core/AutoFormattingService.kt:26-38`.
- Related: F-049 (progress bar never hidden after reprocess), F-113/F-114 (playback/delete gaps) — same screen, separate point fixes in the catalog.
