# Chunk A3 — Ports + Runner/Orchestrator-Migration — Implementation Report

**Chunk:** A3 (`:shared-ai` Ports + (b)-Klassen-Migration hinter die Ports)
**Agent-ID:** groundwork / IMPL+TEST
**Date:** 2026-07-20T00:40:00+02:00
**Spec:** `research/shared-ai-extraktion.md` §2 (Akzeptanz), §3.5 (App-Kopplungen), §4.1–4.5 (Port-Signaturen), §6 A3.1–A3.7 (Move-Schritte), §8.1–8.2 (Charakterisierungs-Tests); Plan §5 A3.

## What I did

Introduced the four spec-ports (`AiConfig`, `UsageSink`, `ProxyConfig`, `AudioDurationReader`) plus a fifth narrow `PromptConfig` (spec-sanctioned "schmaler Prompt-Config-Zugang", §4.1/§6 A3.5) in `:shared-ai/…/ai/port/`, then `git mv`-migrated the 9 category-(b) AI-core files onto them (3 runners, `RunnerFactory`, `AIOrchestrator`, `ModelFetcher`, `ElevenLabsKeytermsParser`, `PromptService`, `SystemPromptResolver`). `:app` implements the ports as thin adapters in a new `ai/adapter/` package. Migrated `org.json` → kotlinx-serialization in the keyterms parser and the ElevenLabs response/error parse (A3.4). Moved the `PROMPT_PUNCTUATION_*` tables + `getPunctuationPromptForLanguage` from `DictateUtils` into `PromptTemplates` (A3.5, §3.5 option i) — byte-parity of all 57 language strings vs the Java source verified programmatically. Characterization tests written for the config/param/proxy/keyterms parity; existing AI tests adapted (converse) or moved to `:shared-ai` (ElevenLabs runner + keyterms). `AIOrchestrator` is now prefs-free; no `:shared-ai` AI-core path reads `SharedPreferences`/`UsageDao`/`MediaMetadataRetriever`/`DictateUtils`/`org.json` (grep-verified, §2 Kriterium 6).

## Acceptance criteria (Plan §2 Kriterien 2, 4, 6 / Spec §2)

| # | Criterion | Result |
|---|---|---|
| 2 | Build-Invariante | ✓ `./gradlew build -x lint …` **BUILD SUCCESSFUL** across `:app`/`:shared`/`:shared-ai`/`:companion` (both `:app` variants assemble; all module unit tests green). Full `./gradlew build` red only on **pre-existing `:app` lint debt** (A2-I1), unrelated to A3. |
| 4 | Verhaltensneutralität | ✓ All `:app` unit tests green **without assertion changes** (only mechanical constructor rewiring in 4 tests). Characterization tests (§8.1) green: `AiConfigParityTest` (12), `ParameterResolutionParityTest` (6), `ProxyConfigParityTest` (6), `ElevenLabsKeytermsSerializationParityTest` (6). Runner wire-format test unchanged assertions (moved to `:shared-ai`). |
| 6 | Kein toter Pfad (grep) | ✓ No `:shared-ai` AI-core file imports/reads `SharedPreferences`/`UsageDao`/`MediaMetadataRetriever`/`DictateUtils`/`org.json`/`Pref` (only doc-comments mention them; the sole real import is `preferences.AmbiguityMode`, itself a `:shared-ai` file from A2). `:app` `ai/` outside `ai/adapter/` = only `PromptTypeClassifier` (Kat. c). |

Criteria 1/3/5 (module exists, purity sharp, `git log --follow`) were A2's; A3 keeps them intact — `SharedAiPurityTest` still green (incl. the pinned `org.json` ban, now enforced), and all moved files are staged as `R` renames (incl. the fully-rewritten `AIOrchestrator`/`RunnerFactory`).

## Design decisions

- **Fifth port `PromptConfig` (§4.1 Schnitt-Begründung / §6 A3.5).** `PromptService`/`SystemPromptResolver` need the style/system prompt-selection + custom-text prefs, which are a distinct concern from runner config (provider/model/key). Per Interface Segregation I added a narrow `PromptConfig` rather than widening `AiConfig` (whose exact §4.1 signature is pinned by `AiConfigParityTest`). The spec explicitly sanctions this ("bzw. ein schmaler Prompt-Config-Zugang").
- **Punctuation move is byte-identical.** A Python diff decoded the Java `\uXXXX` table and compared it to the new Kotlin `PromptTemplates.PUNCTUATION_BY_LANGUAGE` — 57/57 entries identical, 0 mismatches. No API-traffic diff for the predefined Whisper style prompt.
- **`api` vs `implementation` for the SDKs (Spec §10 Gap 2, deferred from A2):** kept **`api`**. `ProxyConfig` carries `OpenAIOkHttpClient.Builder` / `AnthropicOkHttpClient.Builder` on its public surface, and the runner public constructors/surface reference SDK types → `api` is required for `:app` to compile against them transitively. Narrowing to `implementation` is not possible while the port leaks builder types.
- **ElevenLabs response parse:** the success `text` field parses via `Json.parseToJsonElement(...).jsonObject` (throws on malformed like the old `JSONObject(...)`), the error-body status parse stays defensively try/caught → null, mirroring the old `optString`/try-catch exactly.

## Deviations

| Deviation | Plan location | What changed | Why | Impact on later chunks | Resolved? |
|---|---|---|---|---|---|
| Genuine AI-wiring site is `DictatePipelineService.kt`, **not** `PipelineOrchestrator.kt` | INTEGRATION_TARGETS / CONTEXT_NOTES ("PipelineOrchestrator + DictateUtils MUST change") | Wired `AndroidAiFactory.androidOrchestrator(sp, usageDao)` + `androidPromptService(sp)` at `DictatePipelineService.kt:396-397` (the single `AIOrchestrator`/`PromptService` construction site). `PipelineOrchestrator.kt` only *consumes* `AIOrchestrator`'s public API (transcribe/complete/converse/getProvider/getModelName), whose signatures are unchanged, so it needs no edit. | The plan/notes labelled the wrong file. `PipelineOrchestrator` receives `aiOrchestrator` via constructor; it never constructs it. Forcing a no-op diff there would be dead churn. | None — behaviour identical; the correct integration point (`DictatePipelineService.kt`) has a real diff | Yes (see issue A3-I1 for the gate) |
| 5th port `PromptConfig` added (not in §4's four-port list) | Spec §4 lists 4 ports | Added `PromptConfig` for `PromptService`/`SystemPromptResolver` | ISP; spec §6 A3.5 explicitly offers "ein schmaler Prompt-Config-Zugang" as the alternative to fattening `AiConfig` | Companion (Block D) implements one more small port | Yes |
| `PromptService.create(sp)` / `AIOrchestrator(sp, usageDao)` convenience gone | Spec §4.5 (single IME caller) | Replaced by `AndroidAiFactory.{androidOrchestrator,androidPromptService}` (one `:app` wiring point) + updated 4 call sites (1 prod, 3 tests) | Core is prefs-free; the wiring is centralised in one adapter factory | none | Yes |
| SDKs stay `api` in `shared-ai/build.gradle` | Spec §5.2 / §10 Gap 2 | Kept `api` (verified: port leaks SDK builder types) | see Design decisions | none — final call for Block A | Yes |

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| A3-I1 | Important | **Integration gate targets the wrong file.** INTEGRATION_TARGETS demands a diff in `app/.../core/PipelineOrchestrator.kt`, but the genuine, single AI-construction site is `app/.../core/DictatePipelineService.kt:396-397` (which I modified). `PipelineOrchestrator.kt` only calls `AIOrchestrator`'s unchanged public methods, so it correctly shows no diff. The deterministic gate should retarget to `DictatePipelineService.kt`. No code action needed — this is a plan-label correction. | fixed-inline (wired at the true site) | plan-deviation-resolved |
| A3-I2 | Nice-to-have | `./gradlew build` (with lint) still red on the **pre-existing** `:app:lintDebug` debt (A2-I1); zero lint errors reference an A3 file. The A3 gate is `build -x lint` + module tests, both green. | delegated (foreign/pre-existing) | none |

## Inline fixes applied

- Removed now-unused `java.util.{Collections,HashMap,Map}` imports from `DictateUtils.java` (left dangling by the punctuation-table removal). `Locale` + `PromptTemplates` imports retained (still used).
- Kept `DictateUtils.PROMPT_REWORDING_BE_PRECISE` (deprecated alias, out of A3.5 scope, no external callers) to avoid unrelated churn.

## Files modified

**New — ports (`:shared-ai`):**
- `shared-ai/.../ai/port/{AiConfig,UsageSink,ProxyConfig,AudioDurationReader,PromptConfig}.kt`

**New — adapters (`:app`):**
- `app/.../ai/adapter/{AndroidAiConfig,RoomUsageSink,SharedPrefsProxyConfig,MediaMetadataAudioDurationReader,AndroidPromptConfig,AndroidAiFactory}.kt`

**New — tests:**
- `app/.../ai/adapter/{AiConfigParityTest,ParameterResolutionParityTest,ProxyConfigParityTest}.kt`
- `shared-ai/.../ai/{ElevenLabsKeytermsSerializationParityTest}.kt`, `shared-ai/.../ai/prompt/PromptTemplatesPunctuationTest.kt`, `shared-ai/.../ai/testutil/FakePorts.kt`

**Moved (`git mv`, then port-edited — renames detected):**
- `AIOrchestrator.kt`, `factory/RunnerFactory.kt`, `runner/{OpenAICompatibleRunner,AnthropicCompletionRunner,ElevenLabsTranscriptionRunner}.kt`, `model/ModelFetcher.kt`, `ElevenLabsKeytermsParser.kt`, `prompt/{PromptService,SystemPromptResolver}.kt` → `shared-ai/…`
- Tests → `shared-ai/…`: `ai/ElevenLabsKeytermsParserTest.kt`, `ai/runner/ElevenLabsTranscriptionRunnerTest.kt`

**Edited (`:shared-ai`):**
- `shared-ai/.../ai/prompt/PromptTemplates.kt` (+ punctuation table & resolver, A3.5)

**Edited (`:app` — integration + callers):**
- `app/.../core/DictatePipelineService.kt` (**genuine AI-wiring site**, replaces INTEGRATION_TARGET `PipelineOrchestrator.kt` — see A3-I1)
- `app/.../DictateUtils.java` (INTEGRATION_TARGET — punctuation removed; proxy/audio helpers retained for the adapters)
- `app/.../settings/APISettingsActivity.java` (`ModelFetcher.fetchModels` sp → `SharedPrefsProxyConfig`)
- Tests: `app/.../ai/AIOrchestratorConverseTest.kt`, `app/.../core/{PipelineOrchestratorRegenerationTest,PipelineOrchestratorQueueExecutionTest,TranscriptionRerunJobTest}.kt` (constructor rewiring, assertions unchanged)

## Files outside assigned scope (drift)

- `app/.../core/DictatePipelineService.kt` — **not** in INTEGRATION_TARGETS, but it is the genuine single construction site for `AIOrchestrator`/`PromptService` (INTEGRATION_TARGETS named `PipelineOrchestrator.kt`, which never constructs them). Editing it is mandatory for the adapter wiring; documented as A3-I1 / deviation #1.
- `app/.../settings/APISettingsActivity.java` — a caller of the migrated `ModelFetcher.fetchModels` (signature `sp` → `ProxyConfig`); required caller update, in-scope per §3.5.

## Test-run result (fresh runs)

- `./gradlew :shared-ai:test` → BUILD SUCCESSFUL. New/moved: `ElevenLabsKeytermsParserTest` (17), `ElevenLabsKeytermsSerializationParityTest` (6), `ElevenLabsTranscriptionRunnerTest` (3), `PromptTemplatesPunctuationTest` (6) — all 0 failures; A2 pure tests still green.
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL. New: `AiConfigParityTest` (12), `ParameterResolutionParityTest` (6), `ProxyConfigParityTest` (6). Rewired: `AIOrchestratorConverseTest` (2), `PipelineOrchestratorRegenerationTest` (5), `PipelineOrchestratorQueueExecutionTest` (15), `TranscriptionRerunJobTest` (5) — all green, assertions unchanged.
- `./gradlew build -x lint -x lintDebug -x lintRelease -x lintVitalRelease` → **BUILD SUCCESSFUL** across all four modules (both `:app` variants assemble, all unit tests green).
- Byte-parity of the 57-language punctuation table (Java `\uXXXX` vs new Kotlin) verified via a throwaway Python diff: 0 mismatches.

## Helper decisions

- New `:shared-ai` test fakes `FakePorts.kt` (`FakeProxyConfig` no-op + call-count, `FakeAudioDurationReader` fixed value) for pure-JVM runner tests (no `FakeSharedPreferences` on pure JVM).
- Adapter parity tests use the existing `:app` `FakeSharedPreferences` + type-safe `Pref` `put`/`get` helpers — no Robolectric needed (adapters only touch the fake + pure `DictateUtils`/`ParameterRegistry`).
