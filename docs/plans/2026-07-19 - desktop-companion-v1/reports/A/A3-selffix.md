# Chunk A3 — Self-Fix (fresh eyes, diff-based)

**Chunk:** A3 · **Agent-ID:** groundwork / SELF-FIX (fresh eyes)
**Date:** 2026-07-20T00:40:00+02:00
**Wave commit reviewed:** `497ec8d` — `[A.3] Ports + Runner/Orchestrator migration to :shared-ai`
**CHUNK_FILES scope:** `app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt`
**Predecessor reports:** `reports/A/A3-impl.md` (full A3 body), `reports/A/A3-impl-retry1.md` (the +28 KDoc anchor)

## What I did

Reviewed the committed CHUNK_FILES diff (the 28-line module-boundary KDoc anchor the
retry added to `PipelineOrchestrator`'s class doc) with the three lenses. The anchor is
accurate and well-grounded — I verified every symbol and claim it makes against the
working tree rather than trusting the prose:

- `net.devemperor.dictate.ai.port.AiConfig` / `PromptConfig` / `UsageSink` / `ProxyConfig` /
  `AudioDurationReader` all exist (`shared-ai/.../ai/port/`, 5 ports).
- `net.devemperor.dictate.ai.adapter.AndroidAiFactory` exists (`app/.../ai/adapter/`).
- `RecordingRepository.extractDurationSeconds` exists and is genuinely the sole media
  touch on the session-duration path (called at PipelineOrchestrator L1288/L1325/L1327/L1593).
- The named `aiOrchestrator` public API (`complete`/`converse`/`getModelName`/`getProvider`/
  `transcribe`) matches the actual call sites exactly; `promptService.buildQueuedPrompt`
  exists in the migrated `:shared-ai` PromptService.

**Inline fix applied (1):** the port list in the anchor named only 4 of the 5 ports,
omitting `PromptConfig` — which is precisely the port backing `promptService`, one of the
two collaborators the paragraph is about. Added `PromptConfig` to the list so the doc is
accurate and complete. Comment-only; zero runtime effect.

## Review verdict per lens

| Lens | Verdict |
|---|---|
| Plan correctness | Anchor documents the genuine A3 `:app`→`:shared-ai` boundary shift (spec §6 A3.6/A3.7, §4.5). Accurate after the `PromptConfig` fix. The retry's "documentation anchor, not wiring code" deviation is defensible and already documented (the real wiring is `DictatePipelineService.kt`, not this runner). |
| Code quality | KDoc captures a non-derivable "why" (module-boundary shift a future reader cannot reconstruct from the code) — aligned with the engineering baseline's "document consistently" rule, not doc bloat. `@see` links resolve. No smells introduced. |
| Test quality | Diff is comment-only; no test surface. Existing `:app` + `:shared-ai` + `:shared` suites green (below). No new coverage owed by a KDoc change. |

## Tests (working tree, post-fix)

`./gradlew :app:compileDebugKotlin :shared-ai:test :app:testDebugUnitTest :shared:test`
→ **BUILD SUCCESSFUL**. `:app:compileDebugKotlin` re-ran on my edit and passed; the JVM
test tasks are green.

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| A3-SF1 | Critical | **The entire A3 substance is uncommitted at HEAD — only `PipelineOrchestrator.kt` (+28) and the report were committed by wave `497ec8d`.** Verified against the committed tree (`git ls-tree -r HEAD`): `:shared-ai` has **no** `AIOrchestrator.kt` (move uncommitted), `:app` **still** owns it, and `ai/port/` + `ai/adapter/` are **absent** from HEAD. `git diff --stat HEAD` shows the real A3 body sitting unstaged in the working tree (5 ports, 6 adapters, 9 moved AI-core files, RunnerFactory split, `DictateUtils.java` −85 punctuation move, characterization tests, and the `DictatePipelineService.kt`/`APISettingsActivity.java`/test rewires). Consequence: the committed `PipelineOrchestrator.kt` KDoc `@see`-references `ai.port.AiConfig` and `ai.adapter.AndroidAiFactory`, which do **not** exist in the committed tree — the committed A3 is incoherent and would not build in isolation. The commit-agent for the fix wave **must** commit the full A3 file set, not just CHUNK_FILES, or Block A's audit and every downstream block that builds on `:shared-ai` will operate on a broken HEAD. | delegated | blocks-following |

Note: the retry's own issue **A3-R1** (integration gate mislabeled — real wiring is
`DictatePipelineService.kt`, not `PipelineOrchestrator.kt`) is confirmed accurate and
stands; it is orchestration-side (chunks.json target set) and not fixable in CHUNK_FILES.

## Files modified

- `app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt` — added
  `PromptConfig` to the port list in the A3 module-boundary KDoc anchor (comment-only, +1/−1).

## Files outside assigned scope (drift)

- none. Only CHUNK_FILES was edited. The uncommitted A3 body (DictateUtils, moves,
  ports, adapters, tests) is pre-existing working-tree state from the predecessor, not
  my drift — raised as issue A3-SF1 for the commit-agent, not touched.
