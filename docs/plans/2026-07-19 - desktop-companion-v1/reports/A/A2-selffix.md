# Chunk A2 — Self-Fix Report (fresh eyes, diff-based)

**Chunk:** A2 (`:shared-ai` scaffold + behaviour-neutral class moves)
**Agent-ID:** groundwork / SELF-FIX (fresh eyes)
**Wave commit reviewed:** 0fe964b
**Date:** 2026-07-20T00:40:00+02:00

## What I did

Reviewed the committed A2 diff against Plan §5 A2, §3 D5.d/D5.e, and spec
`shared-ai-extraktion.md` §3/§5/§6/§7/§11 with three lenses (plan correctness,
code quality, test quality). Found the chunk clean — no inline fixes required.
Re-ran `:shared-ai:test` and `:app:testDebugUnitTest` to confirm green after review.

## Review findings

### Plan correctness — PASS
- **Module scaffold** present and correct: `settings.gradle` include, `app/build.gradle`
  `implementation project(':shared-ai')`, `shared-ai/build.gradle` at jvmTarget 1.8 (both
  `java{}` VERSION_1_8 and Kotlin `compilerOptions.jvmTarget = JVM_1_8`), no `repositories{}`
  block (correct — `settings.gradle:15` sets `FAIL_ON_PROJECT_REPOS`).
- **Move set exact.** 36 renames, all R100 (byte-identical): 30 main + 6 test.
  - 3 enums (`MessageRole`, `ResponseFormatKind` @ `database/entity`; `AmbiguityMode` @
    `preferences`) — package unchanged (split-package, §3.4).
  - 26 category-(a) `ai/` files: `AIProvider`, `AIProviderException`; `model/{ModelInfo,
    ParameterDef,ParameterRegistry}`; `runner/` 9 interfaces+DTOs; `prompt/{PromptContext,
    PromptMode,PromptBuilder,PromptTemplates}`; `conversation/` all 8 present files.
  - `core/AmplitudeProcessor.kt` (D5.e), package-preserving.
  - 6 pure tests (`conversation/{ConversationReconstructor,ConversationTurnBuilder,
    ReviewDecision,StructuredResponseCodec}Test`, `runner/{StructuredOutputGuards,
    StructuredOutputSupport}Test`).
- **Correctly retained in `:app`** (verified by listing the remaining `ai/` tree):
  category-(b) `AIOrchestrator`, `RunnerFactory`, `ModelFetcher`, `ElevenLabsKeytermsParser`,
  `PromptService`, `SystemPromptResolver`, and the 3 concrete runners; category-(c)
  `PromptTypeClassifier` (D5.d) + `PromptType.kt` enum. No file left behind in
  `app/.../ai/conversation/`; `app/.../core/AmplitudeProcessor.kt` is gone.

### Code quality — PASS
- `SharedAiPurityTest` faithfully mirrors `:shared/SharedPurityTest` (same scan logic,
  same self-test guard `sources.size > 5`) with a justified forbidden-list delta: SDKs +
  okhttp allowed (the module's reason to exist), `kotlinx.coroutines` + `org.json` added per
  spec §5.3 recommendation. Doc-comment explains the "why" for each entry.
- `build.gradle` `api` on the two SDKs is documented as the safe scaffold with the
  api-vs-implementation narrowing deliberately deferred to A3 (spec §10 Gap 2) — consistent
  with the deviation table in the impl report.
- ADR cross-reference `adr-shared-ai-module` resolves to an existing draft under the plan's
  `adrs/`.

### Test quality — PASS
- `:shared-ai:test` BUILD SUCCESSFUL — the moved pure tests compile against the moved
  sources (incl. `StructuredOutputSupportTest`); purity self-test present.
- Split-package move is behaviour-neutral: `:app:testDebugUnitTest` BUILD SUCCESSFUL.

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| — | — | None. Chunk clean; A2-I1 (pre-existing `:app` lint debt) and A2-I2 (external `:shared` flake, resolved) from the impl report are out of A2's file scope and unchanged. | — | none |

## Inline fixes applied

None — the chunk is a set of provably byte-identical moves plus two clean new-content
files; nothing in scope needed changing.

## Observations (non-actionable, no fix)

- The impl report's prose says "27 category-(a)" files where the `ai/` move count is 26
  (2 root + 3 model + 9 runner + 4 prompt + 8 conversation). The **actual moves are
  correct** (36 R100 renames = 30 main + 6 test). Left the historical impl report untouched
  — it is the implementer's record and the miscount has no bearing on correctness.

## Files modified

None (review only; no fixes required).

## Files outside assigned scope (drift)

None.

## Test-run result (after review)

- `./gradlew :shared-ai:cleanTest :shared-ai:test` → BUILD SUCCESSFUL.
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL.
</content>
</invoke>
