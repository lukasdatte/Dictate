# Block A — Logic Audit (`logic` topic)

**Block:** A (`:shared-ai` extraction — scaffold + pure moves + ports/runner migration behind ports)
**Auditor:** block-audit / `logic`
**Date:** 2026-07-20T00:40:00+02:00
**Baseline:** `c46cfe8..HEAD`, file-scoped to BLOCK_FILES
**Grounding loaded:** `knowledge-typescript` (null-safety / exhaustiveness patterns — language-neutral for the Kotlin/Java surface)

## Result

**No confirmed logic findings.** Block A is a behaviour-neutral refactor: 36 byte-identical
`git mv` moves (A2) plus a mechanical extraction of AI-core collaborators behind ports (A3).
Every logic-bearing change was traced to its pre-move source and confirmed equivalent. The two
`org.json → kotlinx-serialization` rewrites carry only edge differences that cannot occur on any
real app-produced input (documented below as benign, not elevated to findings).

## What I audited (logic surface — the genuinely-edited files, not the pure moves)

The pure moves (R100, 0-line diffs) carry no logic risk. I focused on the files with real content
edits:

| File | Change | Verdict |
|---|---|---|
| `ai/AIOrchestrator.kt` | prefs-free: `resolveParameters`/keyterms → `AiConfig` port | **parity** — param logic moved byte-identical into `AndroidAiConfig.completionParameters`; keyterms guard reproduced (`AndroidAiConfig.elevenLabsKeyterms`) |
| `ai/factory/RunnerFactory.kt` | `sp` → `AiConfig`/`ProxyConfig`/`AudioDurationReader` | **parity** — provider/model/key/baseUrl selection delegated 1:1 to `AndroidAiConfig`; same branch structure |
| `ai/runner/OpenAICompatibleRunner.kt` | proxy + audio via ports | **parity** — `proxy.applyTo(builder)` == old `if(ProxyEnabled && isValidProxy) applyProxy`; audio via `AudioDurationReader` |
| `ai/runner/AnthropicCompletionRunner.kt` | proxy via port | **parity** — same guard reproduced in `SharedPrefsProxyConfig.isProxyActive()` |
| `ai/runner/ElevenLabsTranscriptionRunner.kt` | proxy+audio ports, `org.json`→kotlinx | **parity** — see edge notes; proxy/authenticator ordering preserved |
| `ai/ElevenLabsKeytermsParser.kt` | `org.json`→kotlinx (`to/fromJson`) | **parity** on all app-produced input; see edge notes |
| `ai/model/ModelFetcher.kt` | `sp` → `ProxyConfig` | **parity** — proxy guard preserved, applied at same point (after Custom/Anthropic/ElevenLabs early-return) |
| `ai/prompt/PromptService.kt` | `sp` → `PromptConfig` port | **parity** — `config.stylePromptMode()` etc. map 1:1 via `AndroidPromptConfig` |
| `ai/prompt/SystemPromptResolver.kt` | `sp` → `PromptConfig` port | **parity** — identical `when` over `PromptMode`, `raw.ifEmpty { null }` unchanged |
| `ai/prompt/PromptTemplates.kt` | punctuation table + resolver moved from `DictateUtils` | **parity** — fallback logic identical; `lowercase()` == old `toLowerCase(Locale.ROOT)` (no Turkish-i regression); 57/57 entries, byte-parity verified in A3 + `PromptTemplatesPunctuationTest` |
| `DictateUtils.java` | punctuation removed; proxy/audio helpers retained | **parity** — all 6 helpers (`getAudioDuration`, `isValidProxy`, `createProxy`, `applyProxyAuthenticator`, `applyProxy`, `applyProxyToAnthropic`) still present; no dangling refs to the removed method (only doc-comments) |
| `core/DictatePipelineService.kt` | wiring → `AndroidAiFactory` | **parity** — single construction site rewired; consumer contract unchanged |
| `settings/APISettingsActivity.java` | `ModelFetcher.fetchModels(sp)` → `SharedPrefsProxyConfig(sp)` | **parity** — caller update only |

### Key parity checks performed in detail

- **Parameter resolution** (`AndroidAiConfig.completionParameters`) is a character-for-character copy
  of the old `AIOrchestrator.resolveParameters` incl. the sentinel filters
  (`temperature >= 0f`, `maxTokens > 0`, `reasoningEffort.isNotEmpty()`) and the identical
  `PARAMETER_PREFS` provider→pref map (CUSTOM reuses OpenAI temperature/max-tokens). ✓
- **Keyterms guard** — `elevenLabsKeyterms()` reproduces
  `if (provider(TRANSCRIPTION)==ELEVENLABS) fromJson(...).takeIf{isNotEmpty} else null` exactly. ✓
- **Proxy guard** — `SharedPrefsProxyConfig.isProxyActive() = ProxyEnabled && isValidProxy(host)`
  matches the old inline guard in all four call sites; `installAuthenticator()` is called only when
  `rawProxy() != null` (ElevenLabs), matching the old `if(proxy != null){...applyProxyAuthenticator}`. ✓
- **Punctuation fallback** — null/empty/`detect` → English; exact key; hyphen-base subtag; English
  default — identical branch-for-branch. ✓

## Benign edge observations (NOT findings — no reachable failure scenario)

These are real byte-level differences from the `org.json`→kotlinx migration, documented for the
consolidator so they are not re-discovered. None is a logic defect on any input the app can produce.

1. **`ElevenLabsKeytermsParser.fromJson` strictness.** Old `JSONArray(json).getString(i)` coerced
   non-string elements and parsed leniently; new `Json.decodeFromString(ListSerializer(String))` is
   strict and returns `emptyList()` (via the existing `catch`) on a non-string element. Unreachable:
   the pref is only ever written by `toJson(List<String>)`, so the stored blob is always a clean
   string array; malformed/empty already short-circuit to `emptyList()` in both. Round-trips old data
   (kotlinx accepts the JSON `\/` escape).

2. **`toJson` forward-slash escaping.** Old org.json emitted `\/`; kotlinx emits `/`. Cosmetic —
   `Pref.ElevenLabsKeytermsParsed` is internal storage that round-trips (keyterms are sent to the API
   as raw per-term form-data parts, never as this JSON string), and `fromJson` reads both forms.

3. **ElevenLabs error `bodyStatus` `""` vs `null`.** Old `optJSONObject("detail")?.optString("status")`
   yields `""` when `detail` exists without `status`; new yields `null`. Dead-equivalent: `bodyStatus`
   is only compared to `"quota_exceeded"`, which both `""` and `null` fail. All other malformed/non-object
   cases fall into the shared `catch → null` in both implementations.

## Coverage

- **Audited:** all 13 genuinely-edited files above + the 6 new `:app` adapters (`AndroidAiConfig`,
  `SharedPrefsProxyConfig`, `MediaMetadataAudioDurationReader`, `AndroidPromptConfig`, `RoomUsageSink`,
  `AndroidAiFactory`) as the parity ground-truth.
- **Skipped (justified):** the 36 R100 pure moves (0-line diffs — byte-identical to HEAD source, no
  logic to audit); the 5 new port interfaces (`AiConfig`/`UsageSink`/`ProxyConfig`/`AudioDurationReader`/
  `PromptConfig` — declarations only, no logic); build/settings gradle files (no runtime logic).

## Out-of-scope observations (for other topics / consolidator)

- **[test]** `PromptTemplatesPunctuationTest` pins the fallback logic and spot-values (`de`, `zh-cn`,
  `zh-tw`) but does not individually assert all 57 language strings — full byte-parity rests on the
  A3 one-time Python diff. Not a logic defect; a coverage note for the `test` auditor.
- **[plan-and-api]** The 5th port `PromptConfig` and the `DictatePipelineService.kt` (not
  `PipelineOrchestrator.kt`) wiring site are documented, defensible A3 deviations (D4) — not re-litigated here.
