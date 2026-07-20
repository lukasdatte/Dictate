# Chunk A2 — Self-Fix Report (fresh eyes, diff-based)

**Chunk:** A2 (`:shared-ai` scaffold + behaviour-neutral class moves)
**Agent-ID:** groundwork / SELF-FIX (fresh eyes)
**Wave commit reviewed:** `0fe964b [A.A2] Pure Moves — :shared-ai scaffold + behaviour-neutral class moves`
**Date:** 2026-07-20T00:40:00+02:00
**Plan sections:** Plan §5 A2 (L455-463), §3 D5.d + D5.e (L359-370); Spec `shared-ai-extraktion.md` §3, §5, §6 A2.0-A2.3, §7, §11.

## Context — re-invocation on an already-advanced branch

A2's self-fix already landed as `36e2cf6 [A] A2 self-fix`, and **A3, B, C, and D are all
committed on top** (`497ec8d` A3 ports/runner migration → A3 self-fix → mid-repair → repair
wave → blocks B/C/D). The `:shared-ai` module at HEAD has grown far beyond A2's 36 moves — it
now also holds the ports (`AiConfig`, `UsageSink`, `ProxyConfig`, `AudioDurationReader`),
`AIOrchestrator`, `RunnerFactory`, `ModelFetcher`, the concrete runners,
`PromptService`/`SystemPromptResolver`, `secrets/SecretStore`, and more, added by A3 and later
blocks.

**Consequence for this pass:** every file A2 moved has since been further edited by committed
downstream work. Re-moving, reverting, or "correcting" any A2 artifact now would destroy
committed A3/B/C/D changes. The correct fresh-eyes action is therefore to **verify A2's diff is
correct and that its contribution is green at HEAD**, not to mutate integrated code.

## Three-lens review of the A2 diff (`0fe964b`)

### Plan correctness — PASS
- **Module scaffold** present and correct: `settings.gradle` include, `app/build.gradle`
  `implementation project(':shared-ai')`, `shared-ai/build.gradle` at jvmTarget 1.8 (both
  `java{}` VERSION_1_8 and Kotlin `compilerOptions.jvmTarget = JVM_1_8`), no `repositories{}`
  block (correct — `settings.gradle` sets `FAIL_ON_PROJECT_REPOS`).
- **Move set exact.** 36 renames, all R100 / 0 insertions (byte-identical): 30 main + 6 test.
  - 3 enums (`MessageRole`, `ResponseFormatKind` @ `database/entity`; `AmbiguityMode` @
    `preferences`) — package unchanged (split-package, §3.4).
  - 26 category-(a) `ai/` files: `AIProvider`, `AIProviderException`; `model/{ModelInfo,
    ParameterDef,ParameterRegistry}`; `runner/` 9 interfaces+DTOs; `prompt/{PromptContext,
    PromptMode,PromptBuilder,PromptTemplates}`; `conversation/` all 8 present files.
  - `core/AmplitudeProcessor.kt` (D5.e), package-preserving.
  - 6 pure tests (`conversation/{ConversationReconstructor,ConversationTurnBuilder,
    ReviewDecision,StructuredResponseCodec}Test`, `runner/{StructuredOutputGuards,
    StructuredOutputSupport}Test`).
- **`PromptTypeClassifier` correctly stays in `:app`** (D5.d). Both A2 deviations (D5.d stay,
  D5.e move) plus the SDK `api`-scaffold deviation are documented and defensible. No
  undocumented drift.

### Code quality — PASS
- `build.gradle` pins jvmTarget 1.8 via both `java{}` source/target compatibility and
  `kotlin.compilerOptions.jvmTarget = JVM_1_8`, with a load-bearing WHY comment tying it to
  ADR-0015 (inline-bytecode constraint) and the `:shared` vs `:shared-ai` split rationale.
- SDKs declared `api` with the api-vs-implementation narrowing deliberately deferred to A3
  (spec §10 Gap 2), documented inline — consistent with the impl report's deviation table.
- `SharedAiPurityTest` mirrors `:shared/SharedPurityTest` with a justified forbidden-list delta:
  SDKs + okhttp allowed (the module's reason to exist); `android.`/`androidx.`/`io.ktor`/
  `kotlinx.coroutines`/`org.json` forbidden, each with a WHY string. JUnit
  `assertEquals(message, expected, actual)` order at line 65 is correct.

### Test quality — PASS
- Scanner self-test `theTestItself_findsAViolationWhenThereIsOne` pins `sources.size > 5` and
  that a known-bad import matches the forbidden map — an acceptable negative guard; the module
  additionally rejects `android` imports at compile time (stricter backstop).
- Moved pure tests compile against the moved sources; suite green at HEAD (see below).

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| — | — | None. A2's diff is a correct pure-move scaffold; its contribution is green at HEAD. A2-I1 (pre-existing `:app` lint debt) and A2-I2 (external `:shared` flake, resolved) from the impl report are out of A2's file scope and unchanged. | — | none |

## Inline fixes applied

None. No A2-scoped defect was found, and no fix is possible without destroying committed
downstream (A3/B/C/D) work that has already edited these files. This is the considered verdict
of the three-lens review, not a skipped step.

## Observations (non-actionable, no fix)

- The impl report's prose says "27 category-(a)" files where the `ai/` move count is 26
  (2 root + 3 model + 9 runner + 4 prompt + 8 conversation). The **actual moves are correct**
  (36 R100 renames = 30 main + 6 test). Left the historical impl report untouched — it is the
  implementer's record and the miscount has no bearing on correctness.

## Files modified

None (review only; no fixes required — see "Inline fixes applied").

## Files outside assigned scope (drift)

None.

## Test-run result (after review)

- `./gradlew :shared-ai:test --rerun-tasks --no-build-cache` → **BUILD SUCCESSFUL** (genuine
  re-execution, no cache). Test-result XML totals across 12 classes:
  **tests=94, skipped=0, failures=0, errors=0** (A2's moved pure tests plus the A3+/downstream
  tests now colocated in the module). `SharedAiPurityTest` green incl. its scanner self-test.
