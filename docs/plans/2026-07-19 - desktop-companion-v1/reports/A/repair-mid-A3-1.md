# Repair Fix — mid-A3 wave 1 (A3-SF1)

**Date:** 2026-07-20T00:40:00+02:00
**Agent-ID:** repair-fix (mid-A3-1)
**Finding cluster:** A3-SF1 [Critical] — A3 substance uncommitted at HEAD

## Nature of the fix — no code change, a commit-completion

Finding A3-SF1 is **not** a code defect. The entire A3 port/adapter/move
substance is already present and **correct** in the working tree; the previous
fix-wave commit (`497ec8d`) was file-scoped to the retry agent's own delta
(`PipelineOrchestrator.kt` + its report) and left the other 46 A3 files
uncommitted. HEAD is therefore incoherent: the committed `PipelineOrchestrator.kt`
KDoc `@see`-references `ai.port.AiConfig` / `ai.adapter.AndroidAiFactory`, which
do not exist in the committed tree.

The fix is to **complete the commit** with the full, coherent A3 file set. As a
repair-fix agent I do **not** commit — the load-bearing action is returning the
COMPLETE `files_modified` list so the orchestration's commit-agent stages all 48
files (both sides of every move), not one attempt's delta. This is exactly the
"a 16-file fix that returns 2 files leaves HEAD broken" trap the prompt warns of.

## Verification performed

1. **Live git state** matches research `mid-A3-A3-SF1.md` exactly: `git status
   --short` shows 11 renames (`RM`, app→shared-ai), 8 modified app files, 1
   modified `shared-ai/…/prompt/PromptTemplates.kt`, plus untracked `ai/port/`,
   `ai/adapter/` (app), and 3 new shared-ai tests.
2. **Coherence proof**: `PipelineOrchestrator.kt` working-tree diff carries the
   4-line KDoc edit adding `PromptConfig` to the port list; all 5 ports and 6
   adapters exist on disk.
3. **Build/tests green on the working tree** (the state the completed commit will
   capture): `./gradlew :app:compileDebugKotlin` SUCCESS; `./gradlew
   :shared-ai:test :app:testDebugUnitTest --rerun-tasks` → **BUILD SUCCESSFUL**
   (both test tasks ran fresh, not cached). This is the real acceptance signal
   that the incoherence is gone once these files land in one commit.

## Findings

| ID | Severity | Resolution |
|---|---|---|
| A3-SF1 | Critical | Fixed — complete A3 file set returned in `files_modified` (48 files) for the commit-agent. No source edit required; working tree already coherent + green. |

## Complete A3 file set (48 files — `files_modified`)

**Moves — 9 main classes, both sides** (old app path = deletion, new shared-ai
path = addition; both MUST be committed or the class dangles/duplicates):
old `app/src/main/java/net/devemperor/dictate/ai/{AIOrchestrator, ElevenLabsKeytermsParser,
factory/RunnerFactory, model/ModelFetcher, prompt/PromptService, prompt/SystemPromptResolver,
runner/AnthropicCompletionRunner, runner/ElevenLabsTranscriptionRunner, runner/OpenAICompatibleRunner}.kt`
→ new `shared-ai/src/main/kotlin/net/devemperor/dictate/ai/…` (same relative paths).

**Test moves — 2, both sides**: `ai/ElevenLabsKeytermsParserTest.kt`,
`ai/runner/ElevenLabsTranscriptionRunnerTest.kt` — app `src/test/java` → shared-ai `src/test/kotlin`.

**Modified shared-ai**: `ai/prompt/PromptTemplates.kt` (+92, receives punctuation constants).

**New ports (5)**: `shared-ai/…/ai/port/{AiConfig, AudioDurationReader, PromptConfig, ProxyConfig, UsageSink}.kt`.

**New adapters (6)**: `app/…/ai/adapter/{AndroidAiConfig, AndroidAiFactory, AndroidPromptConfig,
MediaMetadataAudioDurationReader, RoomUsageSink, SharedPrefsProxyConfig}.kt`.

**New shared-ai tests (3)**: `ai/ElevenLabsKeytermsSerializationParityTest.kt`,
`ai/prompt/PromptTemplatesPunctuationTest.kt`, `ai/testutil/FakePorts.kt`.

**New app adapter tests (3)**: `app/…/ai/adapter/{AiConfigParityTest, ParameterResolutionParityTest, ProxyConfigParityTest}.kt`.

**Modified app main (4)**: `DictateUtils.java` (−85, punctuation removed),
`core/DictatePipelineService.kt`, `core/PipelineOrchestrator.kt` (further 4-line KDoc edit),
`settings/APISettingsActivity.java`.

**Modified app test re-wires (4)**: `ai/AIOrchestratorConverseTest.kt`,
`core/PipelineOrchestratorQueueExecutionTest.kt`, `core/PipelineOrchestratorRegenerationTest.kt`,
`core/TranscriptionRerunJobTest.kt`.

## Explicitly EXCLUDED from the commit (verified, not in `files_modified`)

- `shared/src/test/kotlin/net/devemperor/dictate/shared/config/CanonicalJsonTest.kt`
  — `:shared` config-codec test fix, C1 territory (C1 merged as `c44a0d6`), unrelated
  to A3. Left uncommitted for the orchestration / a C1 follow-up.
- `docs/plans/2026-07-19 - desktop-companion-v1/**` (chunks.json, plan, state file,
  reports) — orchestration/report artifacts, committed by the orchestrator path, not
  the A3 code commit.

## Skipped findings

None.

## Files modified (drift)

No files modified outside the A3 finding scope. The `files_modified` list is the
A3 commit set, not agent edits — no source was changed; the working tree was
already coherent. **Drift: none.**

## Note for the commit-agent

Stage exactly the 48 paths in `files_modified` (both sides of the 11 moves —
`git add -- <old-app-path>` stages the deletion, `git add -- <new-shared-ai-path>`
stages the addition). Do NOT stage `CanonicalJsonTest.kt` or any `docs/plans/**`
path. A new additive commit (not `--amend`) is the fix-wave-safe choice; `497ec8d`
is still HEAD with nothing layered on it. Suggested message:
`[A.3] Complete A3 commit — full port/adapter/move file set (desktop-companion-v1)`.
