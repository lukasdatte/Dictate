# Block A — AUDIT-TEST (topic: test)

**Date:** 2026-07-20
**Block:** A (`:shared-ai` module creation + behaviour-neutral pure moves + A3 port migration)
**Diff range:** `c46cfe8..HEAD` (HEAD = `be124b9 [A.A3] A3 self-fix`)
**Verdict:** PASS — no Critical/Important findings. Two Nice-to-have follow-ups.

## Scope

Block A introduces the pure-JVM `:shared-ai` module and relocates the AI core
into it (A2 pure moves, A3 ports + runner/orchestrator migration). The test
surface is: 12 test files under `shared-ai/src/test` (6 moved pure tests, the
`SharedAiPurityTest` invariant, 4 new/moved A3 characterization + wire-format
tests, 1 `FakePorts` helper). App-side AI tests (adapter/parity, converse,
PromptTypeClassifier) stay in `:app`.

## Step 0 — conventions

- `CONVENTIONS`: `lint_command: none`, `coverage_command: none` → no coverage
  threshold to enforce; coverage assessed qualitatively.
- `test_command_shared_ai: ./gradlew :shared-ai:test`; app unit tests via
  `:app:testDebugUnitTest`. jvmTarget 1.8 for `:shared-ai` (R9/ADR-0015).
- `SharedAiPurityTest` mirrors `:shared`'s `SharedPurityTest` with a module-
  specific forbidden list (android/androidx/ktor/coroutines/org.json) — the
  correct convention for this module; audited against it, not preference.

## Step 1 — static quality (block test diff)

- **Test naming** — behavior + condition throughout (e.g. `keyterms are sent as
  one form field per term, not a JSON array string`, `region subtag falls back
  to base language`). Good.
- **Assertions concrete** — no fragile snapshots; wire-format asserted by
  counting form-data parts and asserting absence of the JSON-array encoding;
  punctuation table asserted on exact 57-language strings. Good.
- **Mock/helper convention** — one shared helper file `testutil/FakePorts.kt`
  (`FakeProxyConfig`, `FakeAudioDurationReader`) built on the A3 ports; no ad-hoc
  hand-mocks, no near-identical duplicate helpers across the block. Good.
- **Purity self-test** — `SharedAiPurityTest.theTestItself_findsAViolationWhenThereIsOne`
  guards against a scanner that silently reads nothing (`sources.size > 5`).
- **Doc-trail** — the two behaviour-touching changes in the block are documented:
  A3.4 `org.json → kotlinx-serialization` swap (keyterms parser + ElevenLabs
  response/error parse) and A3.5 `getPunctuationPromptForLanguage` table move
  are both recorded in `reports/A/A3-impl.md` (byte-parity of 57 language strings
  stated as verified). The `ElevenLabsTranscriptionRunnerTest` HTTP-422 note is a
  regression-guard rationale for pre-existing repeated-form-part behaviour, not an
  undocumented in-block code change. No production-code change lacks a report entry.

## Step 2 — dynamic

`./gradlew :shared-ai:test --rerun-tasks` → BUILD SUCCESSFUL (forced clean rerun,
not cache): **87 tests, 0 skipped, 0 failures, 0 errors** across 11 classes:

| Class | tests |
|---|---|
| ConversationReconstructorTest | 3 |
| ConversationTurnBuilderTest | 17 |
| ReviewDecisionTest | 5 |
| StructuredResponseCodecTest | 23 |
| ElevenLabsKeytermsParserTest | 17 |
| ElevenLabsKeytermsSerializationParityTest | 6 |
| PromptTemplatesPunctuationTest | 6 |
| ElevenLabsTranscriptionRunnerTest | 3 |
| StructuredOutputGuardsTest | 2 |
| StructuredOutputSupportTest | 3 |
| SharedAiPurityTest | 2 |

**Cross-chunk regression check (moves out of `:app`):**
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL, **2417 tests, 0 failures**.
- Duplicate-class check: every moved main file (`AIProvider`, `AmplitudeProcessor`,
  `AIOrchestrator`, `MessageRole`, `ResponseFormatKind`, `AmbiguityMode`,
  `ConversationReconstructor`, `StructuredResponseCodec`, …) exists **only** in
  `:shared-ai`, not left behind in `:app` → no split-package / duplicate-class hazard.
- Moved test files (6 pure + ElevenLabs keyterms/runner + punctuation) all removed
  from `:app` and present exactly once in `:shared-ai`.
- No coverage threshold configured; no coverage regression measurable — N/A by convention.

No test that was green in its own chunk is red now. No regression.

## Findings (Nice-to-have)

1. **coverage — large moved AI-core files have no direct `:shared-ai` test.**
   `AIOrchestrator`, `OpenAICompatibleRunner`, `AnthropicCompletionRunner`,
   `ModelFetcher`, `PromptService`, `PromptBuilder`, `RunnerFactory`,
   `SystemPromptResolver` carry no self-contained `:shared-ai` unit test; they are
   exercised only indirectly through `:app` parity/characterization tests
   (`AiConfigParityTest`, `ParameterResolutionParityTest`, `ProxyConfigParityTest`,
   `AIOrchestratorConverseTest`), all green. Acceptable for a behaviour-neutral move
   block, but once these are modified *in-module* (e.g. companion-only paths) the
   module has no in-module regression net. Follow-up when they next change.

2. **test-util — `FakeProxyConfig.installAuthenticatorCalls` is a dead affordance.**
   `shared-ai/src/test/.../testutil/FakePorts.kt` tracks and increments the counter,
   and its KDoc says "proxy-path tests can assert the runner honoured the no-proxy
   case", but no current test reads it. Either add the intended proxy-path assertion
   or drop the counter.

## Out-of-scope observation (not a block-A finding)

`shared/src/test/kotlin/.../config/CanonicalJsonTest.kt` shows an **uncommitted**
working-tree modification (`git status: M`). It belongs to `:shared` (Block C
territory), is not in the `c46cfe8..HEAD` block diff, and does not affect the
`:shared-ai`/`:app` verdict above. Flagged for whoever owns that concurrent edit.
