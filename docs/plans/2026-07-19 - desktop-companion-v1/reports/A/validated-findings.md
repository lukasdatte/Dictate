# Block A — Audit Consolidation (validated findings)

**Mode:** initial · **Block:** A · **Timestamp:** 2026-07-20T00:40:00+02:00
**Consolidator:** validate / dedupe / classify (fixes nothing)
**Inputs:** 4 parallel audits — `plan-and-api`, `convention`, `logic`, `test`.

## Verdict

Block A (`:shared-ai` module creation + A2 pure moves + A3 port migration) is a
faithful, behaviour-neutral extraction. Two of four auditors (`plan-and-api`,
`logic`) returned **no findings**; `convention` and `test` each returned two
Nice-to-have items. All four survive validation as **real but Nice-to-have**.
No Critical/Important findings. Nothing blocks downstream blocks.

- **Findings validated (survive):** 4 (all Nice-to-have, all `green`)
- **Eliminated (false positive / misread):** 0
- **Needing research (`yellow`):** 0

## Validated findings

### convention-A-1 — Spec-reference style split in `ai/adapter/` (green, Nice-to-have)

**Verified.** `AndroidAiConfig.kt:20` carries the full
`@see docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md §4.1, §6 A3.3/A3.6`
tag — the anchor convention every port and every migrated `:shared-ai` class uses.
The five sibling adapters reference the spec inline in prose only, no `@see`:
- `AndroidPromptConfig.kt:12` — `(spec §6 A3.5)`
- `SharedPrefsProxyConfig.kt:15` — `(spec §4.3)`
- `RoomUsageSink.kt:9` — `(spec §4.2)`
- `MediaMetadataAudioDurationReader.kt:10` — `(spec §4.4)`
- `AndroidAiFactory.kt:14` — `(spec §4.5)`

The inline form is not a resolvable/greppable `@see` anchor; same operation
(anchoring a class to its spec section) done two ways inside one newly-created
package. Fix is mechanical, comment-only: add a matching
`@see docs/plans/… §4.x` line to the five inline-only adapters.

### convention-A-2 — Sibling `SystemPromptResolver.create()` dead / bypassed (green, Nice-to-have)

**Verified.** Both `PromptService` and `SystemPromptResolver` declare a
`companion object { @JvmStatic fun create(config: PromptConfig) }` (same
convention on both). But `PromptService.create` (`PromptService.kt:72`) composes
the resolver via the constructor directly —
`PromptService(config, SystemPromptResolver(config))` — so
`SystemPromptResolver.create` (`SystemPromptResolver.kt:33`) has **zero call
sites** (grep across `:shared-ai` + `:app` returns none). Convention declared on
both classes, honoured on only one. Fix (preferred): call
`SystemPromptResolver.create(config)` inside `PromptService.create` for symmetry;
alternatively drop the unused companion.

### A-TEST-1 — Moved AI-core files have no direct `:shared-ai` unit test (green, Nice-to-have)

**Verified as a real coverage note.** `AIOrchestrator`, `OpenAICompatibleRunner`,
`AnthropicCompletionRunner`, `ModelFetcher`, `PromptService`, `PromptBuilder`,
`RunnerFactory`, `SystemPromptResolver` carry no self-contained `:shared-ai`
test; they are covered only indirectly through green `:app` parity/
characterization tests (`AiConfigParityTest`, `ParameterResolutionParityTest`,
`ProxyConfigParityTest`, `AIOrchestratorConverseTest`). Acceptable for a
behaviour-neutral move block (no coverage threshold configured — `CONVENTIONS`:
`coverage_command: none`). This is a **deferred** follow-up: the recommended
action is "add an in-module test when any of these files is next modified inside
`:shared-ai`", not an edit to make now. Kept as a Nice-to-have signal for the
future companion-only paths; does not block.

### A-TEST-2 — `FakeProxyConfig.installAuthenticatorCalls` dead affordance (green, Nice-to-have)

**Verified.** `FakePorts.kt:18` declares `var installAuthenticatorCalls = 0` and
`:24` increments it in `installAuthenticator()`, but grep shows it is never read/
asserted by any test — despite its KDoc claiming proxy-path tests can assert the
no-proxy case. Currently a dead test-util affordance. Fix: either add the
intended proxy-path assertion (in `ElevenLabsTranscriptionRunnerTest` or a new
runner test) that reads the counter, or remove it.

## Dedupe / cross-cut notes

- No duplicates: the four findings touch disjoint concerns (adapter KDoc anchors,
  a shared-ai factory pair, in-module coverage, one test-util counter).
- **File cluster:** convention-A-1 spans 5 adapter files in `app/.../ai/adapter/`
  (one systemic convention drift — a single fix pass, not 5 unrelated edits).
  convention-A-2 is one file pair in `shared-ai/.../ai/prompt/`. The two `test`
  findings are separate concerns; A-TEST-2 is the only concrete-edit one.

## Eliminated findings

None. All four raw findings validated as real.

## Auditor out-of-scope observations (not elevated to findings)

Recorded so they are not re-discovered; the auditors themselves declined to raise
them as Block-A findings and the consolidator concurs:

1. **German code comments now in a shared module.** `PromptService.kt` /
   `PromptTemplates.kt` retain German comments (`// Kontext 1: …`, `Kein
   XML-Builder noetig`, …) after moving into `:shared-ai`. The user convention is
   English code comments, but these are **pre-existing**, carried verbatim by the
   A2 byte-identical move — not introduced by Block A. A translation pass is worth
   considering as a docs/quality item outside this block; not a Block-A
   correctness or convention finding.
2. **Two `new SharedPrefsProxyConfig(sp)` constructions** at
   `APISettingsActivity.java:274,419` — a fresh adapter per call site, but both
   identical, so consistent (not drift). Trivial DRY note only.
3. **chunks.json INTEGRATION_TARGETS mislabel** (`PipelineOrchestrator.kt` named,
   real wiring site is `DictatePipelineService.kt`) — already escalated to `main`
   as a documented, defensible deviation; a label correction, not a code defect.
4. **`shared/.../config/CanonicalJsonTest.kt` uncommitted `M`** — belongs to
   `:shared` (Block C territory), outside the `c46cfe8..HEAD` block diff; flagged
   for whoever owns that concurrent edit. Does not affect Block A verdict.

## Build/test state at HEAD (auditor-run, recorded)

`:shared-ai:test` → 87 tests, 0 failures (forced `--rerun-tasks`).
`:app:testDebugUnitTest` → 2417 tests, 0 failures. `:app` compile (Kotlin/Java/
unit-test-Kotlin) all BUILD SUCCESSFUL. No duplicate-class hazard (every moved
file exists only in `:shared-ai`).
