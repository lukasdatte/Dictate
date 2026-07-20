# Block A — Audit: `plan-and-api`

**Topic:** plan-and-api · **Block:** A · **Timestamp:** 2026-07-20T00:40:00+02:00
**Auditor:** block-audit (external check, fixes nothing) · **HEAD:** be124b9
**Grounding loaded:** `knowledge-typescript` (applied conceptually — Kotlin/Android
project; the relevant lenses are API-shape matching, exhaustive `when`/enum
handling, discriminated-union ≈ sealed/enum parity).

## Verdict

**No findings.** Block A (Chunks A1–A3) is a faithful, behaviour-neutral
extraction. Plan/spec fidelity, cross-chunk API-consumer match, and
stub-freedom all verified against the SSoT spec `shared-ai-extraktion.md`
and the pre-A3 originals. HEAD is coherent and compiles green.

## Scope audited

- Chunk reports: `A1-impl`, `A1-selffix`, `A2-impl`, `A2-selffix`, `A3-impl`,
  `A3-impl-retry1`, `A3-selffix`, `repair-mid-A3-1`.
- Plan §2 (acceptance), §3 D1/D5, §5 Block A; Spec `shared-ai-extraktion.md`
  §2/§3/§4/§5/§6/§9/§10 in full.
- `git diff 0fe964b..HEAD` for the migrated AI-core, plus HEAD contents of the
  5 ports, 6 adapters, `AIOrchestrator`, `RunnerFactory`, all 3 runners,
  `ModelFetcher`, `PromptService`, `SystemPromptResolver`,
  `ElevenLabsKeytermsParser`, and the `:app` wiring
  (`DictatePipelineService.kt`, `APISettingsActivity.java`).

## (a) Plan / spec fidelity — PASS

- **Ports (§4).** `AiConfig`/`UsageSink`/`ProxyConfig`/`AudioDurationReader`
  match the spec Kotlin signatures verbatim. The 5th port `PromptConfig` is the
  spec-sanctioned "schmaler Prompt-Config-Zugang" (§6 A3.5) — documented
  deviation, ISP-correct, not a finding.
- **Enum + AmplitudeProcessor moves (D5.a/D5.e, §3.4).** Package-preserving;
  `PromptTypeClassifier`/`PromptType` correctly stay in `:app` (D5.d).
- **Punctuation move (A3.5, §3.5 opt i).** `PROMPT_PUNCTUATION_*` +
  `getPunctuationPromptForLanguage` fully removed from `DictateUtils`
  (grep count 0), landed in `PromptTemplates`, consumed by `PromptService`;
  no dangling `:app` caller.
- **org.json → kotlinx (A3.4).** `ElevenLabsKeytermsParser` and the ElevenLabs
  response/error parse migrated; guard semantics (`isBlank`/`"[]"` → empty,
  defensive try/catch → empty/null) preserved.
- **Criterion 6 (no dead path).** Re-verified independently: `shared-ai/src/main`
  has 0 imports of android/androidx/ktor and 0 imports of
  `SharedPreferences`/`UsageDao`/`MediaMetadataRetriever`/`org.json`/`DictateUtils`.
  The single `preferences.*` import is `AmbiguityMode` — itself a moved
  `:shared-ai` file (intra-module), not an `:app` coupling.

## (b) Stubs / placeholders — PASS

No `TODO`/`FIXME`/`not-implemented`/`stub`/`placeholder` in `shared-ai/src/main`
or `ai/adapter`. Every adapter is full delegation; no throw-not-implemented.

## (c) Cross-chunk API-consumer match — PASS

- **Wiring (§4.5).** `DictatePipelineService` replaces
  `AIOrchestrator(sp, usageDao)` → `AndroidAiFactory.androidOrchestrator(...)`
  and `PromptService.create(sp)` → `AndroidAiFactory.androidPromptService(sp)`
  — a clean 1:1 substitution (diff verified vs 0fe964b).
- **All constructor call sites updated.** `RunnerFactory`/`AIOrchestrator`/
  `PromptService.create`/`SystemPromptResolver.create` are only constructed via
  `AndroidAiFactory` (prod) or the new 3-arg/adapter signatures (4 test files);
  no straggler old-signature call remains.
- **`ModelFetcher.fetchModels`** signature `sp: SharedPreferences` →
  `proxy: ProxyConfig`; its sole caller `APISettingsActivity.java` (2 sites)
  updated to `new SharedPrefsProxyConfig(sp)`.
- **DTO shapes** (`Transcription/Completion/ConversationOptions/Result`) were
  byte-identical A2 moves; unchanged by A3 — consumer contract intact.

## Behaviour-parity spot checks (Spec §2 crit 4)

Proxy guard `ProxyEnabled && isValidProxy(ProxyHost)` is now centralized once in
`SharedPrefsProxyConfig.isProxyActive()` and reproduces the former inline guard
byte-for-byte at all four call sites (OpenAI, Anthropic, ElevenLabs runners +
ModelFetcher). ElevenLabs installs the process-wide Authenticator only when
`rawProxy() != null` (= proxy active), matching the original `if (proxy != null)`
nesting. `AndroidAiConfig.completionParameters` / `elevenLabsKeyterms` /
key-selection (non-ASCII strip) reproduce the former `AIOrchestrator`/
`RunnerFactory` logic verbatim (PARAMETER_PREFS map identical).

## Build verification (auditor-run, at HEAD)

`:shared-ai:test`, `:app:compileDebugKotlin`, `:app:compileDebugUnitTestKotlin`,
`:app:compileDebugJavaWithJavac` → all **BUILD SUCCESSFUL**. This confirms the
A3-SF1 incoherence (A3 substance uncommitted) was resolved by mid-repair commit
b788fe5: HEAD now contains all ports/adapters/moves coherently.

## Out-of-scope observations (not findings — for the consolidator/orchestration)

- **Integration-gate / INTEGRATION_TARGETS mislabel** (raised as A3-I1, A3-R1,
  A3-SF1): chunks.json names `PipelineOrchestrator.kt` as the wiring target, but
  the genuine single AI-construction site is `DictatePipelineService.kt:397-398`.
  The implementer wired the real site correctly and added a (genuinely useful,
  non-stub) module-boundary KDoc anchor to `PipelineOrchestrator.kt`. This is a
  fully documented, defensible deviation already escalated to `main` — a
  chunks.json label correction, not a code defect. Recorded here only so it is
  not re-discovered downstream.

## Coverage note

Files audited: all BLOCK_FILES (settings.gradle, app/shared-ai build.gradle,
SharedAiPurityTest, AmplitudeProcessor, the moved enums, all `ai/model`,
`ai/runner`, `ai/prompt`, `ai/conversation`, `ai/port`, `ai/adapter` files,
`AIOrchestrator`, `RunnerFactory`, `ModelFetcher`, `ElevenLabsKeytermsParser`,
`DictateUtils.java`, `APISettingsActivity.java`, `DictatePipelineService.kt`,
`PipelineOrchestrator.kt`) plus the rewired `:app` tests.
Files skipped: none in scope. The pure A2 moves (byte-identical, R100) were
spot-checked rather than line-diffed — their identity is proven by the git
rename detection and the green moved test suites.
