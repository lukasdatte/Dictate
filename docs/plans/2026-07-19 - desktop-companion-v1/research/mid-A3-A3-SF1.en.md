# Repair Research — mid-A3-A3-SF1: A3 substance uncommitted at HEAD

**Date:** 2026-07-20T00:40:00+02:00
**Triggered by:** Audit finding A3-SF1 [Critical] — the A3 fix-wave commit (`497ec8d`)
captured only `PipelineOrchestrator.kt` (+28) and one report file; the entire A3
port/adapter/move substance sits uncommitted, leaving HEAD incoherent.
**Agent-ID:** repair-research (mid-A3-A3-SF1)

## Problem in one sentence

Commit `497ec8d [A.3] …` is a **partial** A3 commit: a file-scoped commit picked up
only the retry agent's own declared file (`PipelineOrchestrator.kt`) plus its report,
while the bulk of A3 — 9 AI-core class moves `:app`→`:shared-ai`, 5 ports, 6 adapters,
the characterization/parity tests, and the app-side re-wires — remained in the working
tree. The fix is **not** a code change; it is **completing the commit** with the full,
coherent A3 file set.

## Sources

1. **Live git state** (worktree `feature/desktop-companion-v1`, HEAD `497ec8d`):
   - `git show --stat 497ec8d` → only `core/PipelineOrchestrator.kt` (+28) and
     `reports/A/A3-impl-retry1.md` (+110) were committed.
   - `git ls-tree -r HEAD` → `:shared-ai` has **no** `ai/AIOrchestrator.kt`,
     `ai/factory/RunnerFactory.kt`, `ai/prompt/PromptService.kt`, runners, or `ai/port/**`;
     `:app` **still owns** all of them at `app/src/main/java/net/devemperor/dictate/ai/**`.
   - `git diff --stat HEAD` → 22 files, +542/−604 of real A3 body sitting unstaged.
2. **Incoherence proof** — `git show HEAD:…/core/PipelineOrchestrator.kt` KDoc
   `@see net.devemperor.dictate.ai.adapter.AndroidAiFactory` (line 112) and
   `@see net.devemperor.dictate.ai.port.AiConfig` (line 113) point at files that **do
   not exist in the committed tree**. HEAD would not compile in isolation.
3. **Chunk contract** — `chunks.json` A3 `files_expected`:
   `shared-ai/…/ai/port/**`, `shared-ai/…/ai/**`, `app/…/ai/**`; `integration_targets`:
   `core/PipelineOrchestrator.kt`, `DictateUtils.java`. `context_notes`: "migrate
   AIOrchestrator, RunnerFactory, the runners …, ModelFetcher, PromptService onto
   [the ports] and move them. :app implements the ports as pure adapters."
4. **Split-attempt evidence** — `reports/A/A3-impl-retry1.md` §"Files modified (this
   retry)" lists only `PipelineOrchestrator.kt` and states "All other A3 files are the
   predecessor's (see `reports/A/A3-impl.md`)". The predecessor's substance was never
   committed; the retry's file-scoped commit only saw the retry's own file.
5. **Convention** — `~/.claude/CLAUDE.md` git-workflow: no `git stash`, no
   `git add -A`/`-a`, file-scoped `git commit --only <paths>`. Explicit-path
   `git add -- <paths>` is permitted (only the `-A`/`-a` sweep forms are banned).

## Findings

### Root cause
A3 ran as two attempts. Attempt 1 (`A3-impl.md`) performed the whole migration but its
output was left in the working tree (never committed, or the commit failed). Attempt 2
(`A3-impl-retry1.md`) added a 28-line doc anchor to `PipelineOrchestrator.kt` and the
commit-agent ran a file-scoped commit over the **retry's** `CHUNK_FILES` only. Result:
the doc anchor referencing ports/adapters got committed; the ports/adapters themselves
did not. This is the diff-based-self-fix / narrow-`CHUNK_FILES` trap — the commit scope
must be the chunk's **full** file set, not one agent attempt's delta.

### The complete A3 file set (authoritative — verified against the live tree)

**Moves — 9 main classes `:app`→`:shared-ai`** (git renders each as a rename = a
deletion on the old path + an addition on the new path; **both sides must be in the
commit** or the class exists twice / dangles):

| Old (`app/…/ai/`) | New (`shared-ai/…/ai/`) |
|---|---|
| `AIOrchestrator.kt` | `AIOrchestrator.kt` |
| `ElevenLabsKeytermsParser.kt` | `ElevenLabsKeytermsParser.kt` |
| `factory/RunnerFactory.kt` | `factory/RunnerFactory.kt` (full move — app `factory/` dir is now empty) |
| `model/ModelFetcher.kt` | `model/ModelFetcher.kt` |
| `prompt/PromptService.kt` | `prompt/PromptService.kt` |
| `prompt/SystemPromptResolver.kt` | `prompt/SystemPromptResolver.kt` |
| `runner/AnthropicCompletionRunner.kt` | `runner/AnthropicCompletionRunner.kt` |
| `runner/ElevenLabsTranscriptionRunner.kt` | `runner/ElevenLabsTranscriptionRunner.kt` |
| `runner/OpenAICompatibleRunner.kt` | `runner/OpenAICompatibleRunner.kt` |

**Test moves — 2** (same both-sides rule): `ai/ElevenLabsKeytermsParserTest.kt` and
`ai/runner/ElevenLabsTranscriptionRunnerTest.kt`, from `app/src/test/…` to
`shared-ai/src/test/…`.

**New ports (5)** — `shared-ai/src/main/kotlin/net/devemperor/dictate/ai/port/`:
`AiConfig.kt`, `PromptConfig.kt`, `ProxyConfig.kt`, `UsageSink.kt`, `AudioDurationReader.kt`.

**New adapters (6)** — `app/src/main/java/net/devemperor/dictate/ai/adapter/`:
`AndroidAiConfig.kt`, `AndroidPromptConfig.kt`, `SharedPrefsProxyConfig.kt`,
`RoomUsageSink.kt`, `MediaMetadataAudioDurationReader.kt`, `AndroidAiFactory.kt`.

**New/modified shared-ai code**: `ai/prompt/PromptTemplates.kt` (modified, +92 — receives
the punctuation constants moved out of `DictateUtils.java`).

**New shared-ai tests**: `ai/ElevenLabsKeytermsSerializationParityTest.kt`,
`ai/prompt/PromptTemplatesPunctuationTest.kt`, `ai/testutil/FakePorts.kt`.

**New app adapter tests** — `app/src/test/java/net/devemperor/dictate/ai/adapter/`:
`AiConfigParityTest.kt`, `ProxyConfigParityTest.kt`, `ParameterResolutionParityTest.kt`.

**App re-wires (modified, must ship in the same commit — they reference the moved/new
symbols)**: `DictateUtils.java` (−85, punctuation constants removed → live in
`PromptTemplates.kt`), `core/DictatePipelineService.kt` (constructs via
`adapter.AndroidAiFactory`), `settings/APISettingsActivity.java`,
`core/PipelineOrchestrator.kt` (a further 4-line KDoc edit on top of the already-committed
+28 — adds `PromptConfig` to the port list, coherent with the new `PromptConfig.kt`).

**Test re-wires (modified, must ship together — import from the moved locations)**:
`app/src/test/…/ai/AIOrchestratorConverseTest.kt`,
`app/src/test/…/core/PipelineOrchestratorQueueExecutionTest.kt`,
`app/src/test/…/core/PipelineOrchestratorRegenerationTest.kt`,
`app/src/test/…/core/TranscriptionRerunJobTest.kt`.

### Explicitly NOT part of A3 (must be EXCLUDED from the commit)

- `shared/src/test/kotlin/net/devemperor/dictate/shared/config/CanonicalJsonTest.kt` —
  a `:shared` config-codec test fix (replaces a raw U+0001 char with `''`).
  This is C1 / `:shared` territory (C1 already merged as `c44a0d6`), unrelated to the AI
  extraction. Leave it uncommitted for the orchestration / a C1 follow-up; do **not**
  fold it into A3.
- `docs/plans/2026-07-19 - desktop-companion-v1/**` (chunks.json, plan, state file,
  `reports/…`) — orchestration/report artifacts, committed by the orchestrator path, not
  the A3 code commit. The two A3 chunk reports (`reports/A/A3-impl.md`,
  `reports/A/A3-selffix.md`) may be swept into the code commit for provenance, but they
  are not load-bearing for build coherence — keep the orchestrator's report handling
  authoritative.

### No gradle changes pending
`settings.gradle`, `app/build.gradle`, `shared-ai/build.gradle` are all clean at HEAD —
the `:shared-ai` module + its `:app` dependency were wired in A2 (`0fe964b`), and the
kotlinx-serialization dep the Keyterms A3.4 migration needs is already present. The A3
completion commit is code/tests only; no build-file edit is required.

## Implementation Hints

The fix agent (a commit-agent) should make **one** follow-up commit that completes A3.
Do **not** `git commit --amend` `497ec8d` unless the orchestration explicitly wants
history rewritten — a new additive commit is the fix-wave-safe choice, and `497ec8d` is
still HEAD with nothing layered on it, so a follow-up commit lands cleanly.

**Convention-compliant recipe** (explicit-path add, then file-scoped commit — no
`git add -A`/`-a`, no `git stash`):

```bash
cd /home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1

# 1. Stage exactly the A3 file set (directory pathspecs stage moves' both sides +
#    the new port/adapter/test files; unchanged files like PromptTypeClassifier.kt
#    and PromptTemplates siblings are untouched).
git add -- \
  shared-ai/src/main/kotlin/net/devemperor/dictate/ai/ \
  shared-ai/src/test/kotlin/net/devemperor/dictate/ai/ \
  app/src/main/java/net/devemperor/dictate/ai/ \
  app/src/test/java/net/devemperor/dictate/ai/ \
  app/src/main/java/net/devemperor/dictate/DictateUtils.java \
  app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt \
  app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt \
  app/src/main/java/net/devemperor/dictate/settings/APISettingsActivity.java \
  app/src/test/java/net/devemperor/dictate/core/PipelineOrchestratorQueueExecutionTest.kt \
  app/src/test/java/net/devemperor/dictate/core/PipelineOrchestratorRegenerationTest.kt \
  app/src/test/java/net/devemperor/dictate/core/TranscriptionRerunJobTest.kt

# 2. VERIFY the staged set: it must contain the moves/ports/adapters/tests above and
#    must NOT contain shared/.../config/CanonicalJsonTest.kt or any docs/plans/** path.
git status --short

# 3. File-scoped commit of exactly those paths (--only guards against anything the
#    add might have swept in unexpectedly).
git commit --only -- \
  shared-ai/src/main/kotlin/net/devemperor/dictate/ai/ \
  shared-ai/src/test/kotlin/net/devemperor/dictate/ai/ \
  app/src/main/java/net/devemperor/dictate/ai/ \
  app/src/test/java/net/devemperor/dictate/ai/ \
  app/src/main/java/net/devemperor/dictate/DictateUtils.java \
  app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt \
  app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt \
  app/src/main/java/net/devemperor/dictate/settings/APISettingsActivity.java \
  app/src/test/java/net/devemperor/dictate/core/PipelineOrchestratorQueueExecutionTest.kt \
  app/src/test/java/net/devemperor/dictate/core/PipelineOrchestratorRegenerationTest.kt \
  app/src/test/java/net/devemperor/dictate/core/TranscriptionRerunJobTest.kt \
  -m "[A.3] Complete A3 commit — full port/adapter/move file set (desktop-companion-v1)"
```

Notes / gotchas:
- The directory pathspecs `app/src/main/java/net/devemperor/dictate/ai/` and
  `app/src/test/java/net/devemperor/dictate/ai/` correctly stage BOTH the deletions
  (moved-out classes) AND the new `adapter/` files in one shot — that is exactly what a
  move needs. `PromptTypeClassifier.kt` (stays in `:app` per D5.d) is unchanged, so it is
  not touched.
- Keep `shared/src/test/.../config/CanonicalJsonTest.kt` **out** of both the `add` and the
  `commit --only` pathspecs — it is not A3.
- After committing, confirm HEAD is coherent: `git ls-tree -r HEAD --name-only shared-ai |
  grep AIOrchestrator` must now return the `:shared-ai` path, and
  `git ls-tree -r HEAD app/src/main/java/net/devemperor/dictate/ai/AIOrchestrator.kt` must
  return **nothing** (moved out). Then a `./gradlew :app:compileDebugKotlin` (or the block's
  test task) should compile — this is the real acceptance signal that the incoherence is
  gone, and lets Block A's audit and every `:shared-ai`-consuming downstream block build on
  a sound HEAD.
- Commit-message slug is `desktop-companion-v1` (plan file name, pre-archive) per the
  commit-prefix convention; `[A.3]` matches the chunk.

## References

- Finding: A3-SF1 (this file's trigger).
- `chunks.json` → chunk `A3` (`files_expected`, `integration_targets`, `context_notes`).
- `reports/A/A3-impl-retry1.md` (committed in `497ec8d`) §"Files modified (this retry)".
- `reports/A/A3-impl.md`, `reports/A/A3-selffix.md` (predecessor + self-fix substance).
- Spec `research/shared-ai-extraktion.md` §4.1–4.5 (port signatures), §6 A3.1–A3.7 (move
  steps), §8.1–8.2 (characterization tests).
- `~/.claude/CLAUDE.md` → git-workflow-worktrees + commit-conventions (file-scoped commit,
  no `-A`/`-a`, `[<Phase>.<Chunk>] … (<plan-slug>)` message format).
