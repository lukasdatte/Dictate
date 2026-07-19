# Chunk A2 — Pure Moves — Implementation Report

**Chunk:** A2 (`:shared-ai`-Scaffold + verhaltensneutrale Klassen-Moves)
**Agent-ID:** groundwork / IMPL+TEST
**Date:** 2026-07-20T00:40:00+02:00
**Spec:** `research/shared-ai-extraktion.md` §2 (Akzeptanz), §3 (Inventar), §5 (Scaffold+Purity), §6 A2.0–A2.3, §7 (Directory), §11 (AmplitudeProcessor-Nachtrag); Plan §5 A2, §3 D5.d + D5.e.

## What I did

Scaffolded the new pure-JVM module `:shared-ai` (jvmTarget 1.8, own dependency policy, `SharedAiPurityTest`), then executed the A2 package-preserving `git mv` moves: 3 shared enums, the 27 category-(a) 0-dependency AI-core files, `core/AmplitudeProcessor.kt` (D5.e), and 6 pure test files — 36 moves total, all byte-identical to the HEAD source (100% similarity). `PromptTypeClassifier` correctly **stays in `:app`** (D5.d). No signature edits (those are A3). `:shared-ai:test` green (55 test methods across 7 classes); `:app:testDebugUnitTest` green (split-package move is behaviour-neutral).

> **Independent re-verification pass (2026-07-20).** Re-ran and personally
> confirmed every A2 acceptance item from a clean state: content-identity of all
> 36 moves (`git show HEAD:<old>` vs new = identical for each); R100 rename
> detection via a throwaway `GIT_INDEX_FILE` (real index untouched); `:shared-ai`
> class bytecode major=52 (Java 8) for both `AIProvider` and `AmplitudeProcessor`;
> both SDK core jars (openai-java-core 4.26.0, anthropic-java-core 2.16.0) major=52;
> purity negative self-test (injected `import android.content.Context` → RED at
> compile, reverted); and a full `./gradlew build -x lint …` green across all four
> modules incl. both `:app` variants + all unit tests.

## Acceptance criteria (Plan §2 Kriterien 1–3, 5 / Spec §2)

| # | Criterion | Result |
|---|---|---|
| 1 | Module exists & compiles at jvmTarget 1.8 | ✓ `:shared-ai:compileKotlin` green at 1.8 |
| 2 | Build-invariant across modules | ✓ `./gradlew build -x lint …` **BUILD SUCCESSFUL** across `:app`/`:shared`/`:shared-ai`/`:companion` (both `:app` variants assemble; `:app`+`:shared`+`:shared-ai`+`:companion` unit tests green). Full `./gradlew build` red only on **pre-existing `:app` lint debt** (A2-I1) — 80 errors, first at `core/PipelineNotificationCoordinator.kt:183` (MissingPermission); **zero** reference an A2 file (grep-verified). Not caused by A2 |
| 3 | Purity green + sharp | ✓ `SharedAiPurityTest` green incl. scanner self-test; injected `import android.content.Context` → module RED at compile (`Unresolved reference 'android'`, no android.jar on classpath — a stricter guard than the test), reverted |
| 5 | Move cleanliness (`git log --follow`) | ✓ 36 staged renames at R100; old paths carry committed history (AIProvider, MessageRole `[conv-1]`, AmplitudeProcessor) — `--follow` traverses them post-commit |

Criteria 4 (Verhaltensneutralität via characterization tests) and 6 (adapter dead-path grep) belong to **A3**, not A2.

## R9 / Spec §10 Gap 4 — SDK bytecode-target prüfauftrag (RESOLVED, no escalation)

`:shared-ai` compiles **green at jvmTarget 1.8** with `openai-java 4.26.0` and `anthropic-java 2.16.0` on the `api` classpath. Verified concretely: both SDK **core jars carry bytecode major version 52 (Java 8)** (`openai-java-core-4.26.0`, `anthropic-java-core-2.16.0`), and the emitted `:shared-ai` classes are likewise major=52. Both SDKs already run inside `:app` @ jvmTarget 1.8 today. **No forced bytecode bump, no `:app`-jvmTarget escalation required.** Note: no A2-moved file yet *references* SDK types (category-(a) files use only kotlin stdlib + java.* + net.devemperor), so the SDK surface is only fully exercised in A3 when the runners land — but the module already links against them at 1.8, which is the load-bearing check.

## Deviations

| Deviation | Plan location | What changed | Why | Impact on later chunks | Resolved? |
|---|---|---|---|---|---|
| `PromptTypeClassifier` stays in `:app` (not moved) | Plan §5 A2 old list / Spec §9 footgun / D5.d | Kept in `:app` (Kat. c) — hangs on `PromptType` (16 pill files, ADR-0024, desktop-fremd) | D5.d correction; moving it would drag 16 `:app` pill files | A3 leaves it; adapters live around it | Yes |
| `core/AmplitudeProcessor.kt` moved to `:shared-ai` (D5.e) | Spec §11 addendum (not in §3 `ai/` inventory) | Package-preserving move (`net.devemperor.dictate.core` split-package) | F19 1:1 recording-core design parity; pure `kotlin.math`; a `:companion` copy would drift | `:companion` (Block D) consumes it instead of copying | Yes |
| SDKs declared `api` in `shared-ai/build.gradle` | Spec §5.2 / §10 Gap 2 | `api libs.openai.java` / `api libs.anthropic.java` | Safe scaffold; the `ProxyConfig` port + runner surface (A3) carry SDK builder types on public API. No runner surface exists in `:shared-ai` during A2, so the `api`→`implementation` narrowing decision is structurally deferred to A3 | A3 owns the final api-vs-impl call | Deferred to A3 (documented in build.gradle comment) |

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| A2-I1 | Nice-to-have | `./gradlew build` fails on `:app:lintDebug` — 80 **pre-existing** lint errors (MissingPermission/UnusedResources/MissingTranslation etc.), first at `core/PipelineNotificationCoordinator.kt:183`. **Zero** reference any A2 file (grep-verified against ai/, AmplitudeProcessor, shared-ai, the moved enums). Not caused by A2; the A2 gate is `./gradlew build -x lint` + module tests, both green. | delegated (foreign/pre-existing) | none |
| A2-I2 | Nice-to-have | (prior report) `:shared:test` was red on a parallel agent's in-flight `:shared/…/config/` work. **Now RESOLVED** — on re-verification `./gradlew :shared:test` is BUILD SUCCESSFUL (the parallel C1 chunk advanced/committed its fix). A2 touches nothing under `shared/`; no A2 action was or is required. | resolved externally | none |

The only remaining red signal (A2-I1) is outside A2's file scope. The A2-relevant gate — `./gradlew build -x lint` across all four modules + `:shared-ai:test` + `:app:testDebugUnitTest` — is fully green.

## Inline fixes applied

None needed — pure moves, no logic edits. Category-(a) purity confirmed by grep before moving (all cross-refs resolve to other moving files or the 3 moving enums; the only refs to staying classes are in doc-comments).

## Files modified

**New:**
- `shared-ai/build.gradle`
- `shared-ai/src/test/kotlin/net/devemperor/dictate/ai/SharedAiPurityTest.kt`

**Edited (integration targets):**
- `settings.gradle` (`include ':shared-ai'`)
- `app/build.gradle` (`implementation project(':shared-ai')`)

**Moved (git mv, package-preserving — 36 renames):**
- Enums → `shared-ai/.../database/entity/`: `MessageRole.kt`, `ResponseFormatKind.kt`; → `.../preferences/`: `AmbiguityMode.kt`
- AI core → `shared-ai/.../ai/`: `AIProvider.kt`, `AIProviderException.kt`; `model/{ModelInfo,ParameterDef,ParameterRegistry}.kt`; `runner/{TranscriptionRunner,CompletionRunner,TranscriptionOptions,TranscriptionResult,CompletionOptions,CompletionResult,ConversationRequest,ConversationResult,StructuredOutputGuards}.kt`; `prompt/{PromptContext,PromptMode,PromptBuilder,PromptTemplates}.kt`; `conversation/{ConversationMessage,ConversationReconstructor,ConversationTurnBuilder,PostProcessingInputs,PostProcessingReview,ReviewDecision,StructuredResponseCodec,StructuredResponse}.kt` (all 8)
- `core/AmplitudeProcessor.kt` → `shared-ai/.../core/` (D5.e)
- Pure tests → `shared-ai/src/test/kotlin/.../ai/`: `conversation/{ConversationReconstructorTest,ConversationTurnBuilderTest,ReviewDecisionTest,StructuredResponseCodecTest}.kt`, `runner/{StructuredOutputGuardsTest,StructuredOutputSupportTest}.kt`

**Stayed in `:app` (category b for A3 + category c):** `AIOrchestrator`, `RunnerFactory`, `ModelFetcher`, `ElevenLabsKeytermsParser`, `PromptService`, `SystemPromptResolver`, the 3 concrete runners, and `PromptTypeClassifier` (c).

## Files outside assigned scope (drift)

None. All edits are within A2's declared scope (new `:shared-ai` module, `settings.gradle`, `app/build.gradle`, and the enumerated moves). The `conversation/` folder held 8 files, not the "10" the spec §3.3 loosely stated (illustrative list with "…"); all present files moved.

## Test-run result (fresh runs, re-verification pass)

- `./gradlew :shared-ai:cleanTest :shared-ai:test` → BUILD SUCCESSFUL. **55 test methods / 0 failures** across 7 classes: `SharedAiPurityTest` (2, incl. scanner self-test), `ConversationReconstructorTest` (3), `ConversationTurnBuilderTest` (17), `ReviewDecisionTest` (5), `StructuredResponseCodecTest` (23), `StructuredOutputGuardsTest` (2), `StructuredOutputSupportTest` (3).
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (split-package move behaviour-neutral; app tests referencing moved classes — `AIOrchestratorConverseTest`, `ElevenLabsKeytermsParserTest`, `PromptTypeClassifierTest`, `ElevenLabsTranscriptionRunnerTest` — all green).
- `./gradlew build -x lint -x lintDebug -x lintRelease -x lintVitalRelease` → **BUILD SUCCESSFUL** (2m24s) across `:app`/`:shared`/`:shared-ai`/`:companion` — both `:app` variants assemble, all module unit tests green, `:app:check` green minus lint.
- `./gradlew :shared:test` → BUILD SUCCESSFUL (A2-I2 resolved externally).
- Purity negative self-test: injecting `import android.content.Context` into a `:shared-ai` source → `:shared-ai:compileKotlin` FAILED (`Unresolved reference 'android'` — no android.jar on the pure-JVM classpath, a stricter guard than the scanner); reverted, module green again.
- Full `./gradlew build` (with lint) → red only on pre-existing `:app:lintDebug` (A2-I1); all module compilation + assembly + tests succeed (lint runs post-compile).

## Helper decisions

- `SharedAiPurityTest` mirrors `:shared/SharedPurityTest` 1:1, forbidden list per Spec §5.3 **plus** the recommended `kotlinx.coroutines` and `org.json` entries (mirrors `:shared` doctrine + pins the A3.4 org.json→kotlinx migration). Kept the `sources.size > 5` scanner-self-test guard (36 sources present, passes).
