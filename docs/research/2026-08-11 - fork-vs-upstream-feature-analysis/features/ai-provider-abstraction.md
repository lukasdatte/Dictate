---
date: 2026-08-11
author: Lukas + Claude (analysis session)
status: Research
context: Fork-vs-upstream analysis of the AI provider abstraction — what we built, what Dictate Keyboard 5.3 has, and whether the feature is still worth pursuing.
related-adrs: ADR-0012 (post-processing conversation — constrains the runner contract); the abstraction itself predates the ADR corpus and has none of its own
---

# AI Provider Abstraction — Fork vs. Upstream Analysis

Our `ai/` package replaced upstream 3.2's hardcoded provider `switch` blocks with a typed
`AIProvider` enum, a `RunnerFactory` and per-shape runner interfaces, and used that seam to add
three providers (Anthropic, OpenRouter, ElevenLabs Scribe) on top of the original three.
Dictate Keyboard 5.3 **adopted the same idea and far exceeded it in breadth** — 16 provider
presets, unlimited custom endpoints, on-device transcription and realtime streaming — while
landing on a *worse structural shape* in exactly the two places the fork invested: one
1,296-line enum-switch client instead of runner interfaces, and Anthropic reached only through
its OpenAI-compatibility endpoint. The headline is uncomfortable but clear: upstream wins the
user-facing comparison decisively, and the fork wins one narrow architectural argument that
upstream's own source comments concede.

## 1. Feature Overview

Dictate 3.2 had no provider abstraction at all. Model lists lived in
`res/values/arrays.xml` string-arrays, provider-specific behaviour lived in `switch` blocks in
`DictateUtils.java`, and the user's provider choice was persisted as a raw integer
(`0 = OpenAI, 1 = Groq, 2 = own server`). Adding a provider meant touching every one of those
places, and a provider that did not speak the OpenAI wire format could not be added at all.
The practical symptom was a shared error path: a failed call produced a single "check your
billing" message that was simply wrong for a provider whose failure was a rate limit or a
missing model.

Commit `030cd760` replaced this with a capability-typed enum and a factory. `AIProvider`
carries `supportsTranscription`, `supportsCompletion`, `isOpenAICompatible` and (later, from
ADR-0012) `allowsStructuredOutputTextFallback`; the settings UI is populated from
`AIProvider.withTranscription()` / `withCompletion()` rather than from hand-maintained arrays,
so a provider that cannot transcribe simply never appears in the transcription spinner.
`AIOrchestrator` became the single entry point for every AI call in the app, and
`AIProviderException` gave the UI a typed `ErrorType` to render provider-appropriate messages.

The user-visible payoff is the three added providers. **Anthropic** (completion only, native
`com.anthropic:anthropic-java` SDK) makes Claude available for rewording. **OpenRouter**
(completion only — it has no `/audio/transcriptions` endpoint, which the capability flags
encode rather than discover at runtime) opens a large model catalogue behind one key.
**ElevenLabs Scribe** (transcription only, non-OpenAI multipart wire format) brought its own
runner and with it **key terms** — a user-maintained vocabulary list of names and jargon,
edited in `SystemPromptsActivity`, sent as repeated `keyterms` form-data parts to bias
recognition. For a German dictation workflow full of proper nouns and domain jargon that is a
transcription-quality feature, not a plumbing feature.

Two smaller surfaces round it out: a **hybrid model registry** (`ModelFetcher` queries
`/models` where the provider offers it, with a curated local fallback and a suffix-based
transcription/completion split; Anthropic and Custom fall back to a free-text model field
because neither exposes an OpenAI-shaped listing endpoint), and a **`ParameterRegistry`** that
declares per-provider tunable parameters — temperature, `max_completion_tokens`, `top_p`,
penalties, `reasoning_effort` — with model filters (`reasoning_effort` only for `o1`/`o3`/`o4`/
`gpt-5` prefixes) and mutual-exclusion rules (Anthropic's `temperature` vs `top_p`).

## 2. Our Implementation

**Scope.** The provider layer proper is roughly 1,400 LOC across `ai/`, excluding the sibling
packages that belong to other clusters (`ai/prompt/` → prompt architecture, `ai/conversation/`
→ ADR-0012 post-processing):

| Component | File | LOC |
|---|---|---|
| Provider catalogue + capability flags | `ai/AIProvider.kt` | 94 |
| Single entry point, usage tracking | `ai/AIOrchestrator.kt` | 206 |
| Typed error classification | `ai/AIProviderException.kt` | 52 |
| Runner construction (`open` as a test seam) | `ai/factory/RunnerFactory.kt` | 125 |
| OpenAI-shaped runner (5 of 6 providers) | `ai/runner/OpenAICompatibleRunner.kt` | 257 |
| Native Anthropic runner | `ai/runner/AnthropicCompletionRunner.kt` | 221 |
| ElevenLabs multipart runner | `ai/runner/ElevenLabsTranscriptionRunner.kt` | 153 |
| Key-terms parsing | `ai/ElevenLabsKeytermsParser.kt` | 71 |
| Model discovery + fallback | `ai/model/ModelFetcher.kt` | 102 |
| Per-provider parameter declarations | `ai/model/ParameterRegistry.kt` + `ParameterDef.kt` | 101 |

The shape is the point of the comparison, so it is worth drawing:

```
ours                                    upstream 5.3
────                                    ────────────
AIProvider (enum, 6 values)             ProviderRegistry (object, 16 vals + custom factory)
  └─ capability flags                     └─ plain data classes
        │                                       │
RunnerFactory ─┬─ OpenAICompatibleRunner   OpenAiCompatibleClient (1296 LOC)
               ├─ AnthropicCompletionRunner   ├─ TranscriptionApi enum (8) → when(...)
               └─ ElevenLabsTranscriptionRunner └─ RealtimeApi enum (7) → RealtimeClient (887)
        │
CompletionRunner / TranscriptionRunner   LlmProvider / TranscriptionProvider
  (interfaces — a new wire format is       (interfaces exist, but one class implements
   a new class)                             both and wire variance is a switch arm)
```

**Key plan.** [`../../../plans/archive/ai-abstraction-layer.md`](../../../plans/archive/ai-abstraction-layer.md)
records ten numbered architecture decisions, of which four still govern the code: *Kotlin
alongside Java, no rewrite*; *Runners, not Strategy*; *no DI framework*; and *SharedPreferences
keys stay upstream-compatible*. The last one is why the prefs surface is portable at all.

**ADR coupling.** The layer predates the ADR corpus and has no ADR of its own — its design
record is the plan file. It was later *constrained* by
[ADR-0012](../../../decisions/0012-pipeline-post-processing-conversation.md), which added
`CompletionRunner.converse(ConversationRequest)` and the
`allowsStructuredOutputTextFallback` flag (true only for CUSTOM / OpenRouter / Groq, i.e.
endpoints that front heterogeneous models where a 400 on `json_schema` is a capability gap
rather than a real error). The native Anthropic runner implements the same contract via a
**forced `emit_result` tool**, which is the concrete thing the OpenAI-compat route cannot do
the same way.

**Coupling to the fork.** This is the most portable major cluster we have. `ai/` depends on the
two vendor SDKs and `SharedPreferences` — no state store, no foreground service, no Room. The
coupled parts are elsewhere: the Room migration and the near-total rewrite of
`settings/APISettingsActivity.java` shipped in the same commit, and everything in the fork that
calls an AI goes through `AIOrchestrator`.

## 3. Upstream 5.3 Status

**Verdict: adopted / far exceeded**, with one self-admitted architectural regression.

Upstream's `lib/dictate-core/.../provider/ProviderRegistry.kt` declares **16 presets** plus an
unlimited custom factory (`ProviderRegistry.custom(...)`, ids `custom:<uuid8>`): OpenAI, Groq,
OpenRouter, Gemini (native `generateContent`) and Mistral do both chat and STT; Soniox,
ElevenLabs, Deepgram, AssemblyAI and on-device `local` are STT-only, all with realtime
variants; Anthropic, Together, DeepInfra, xAI, DeepSeek and Ollama are chat-only. Adding a
provider is adding a `val`.

**What upstream does better.**

- **On-device transcription** via sherpa-onnx — Whisper tiny/base/small, Parakeet TDT 0.6B v3,
  Parakeet German, Canary 180M Flash, GigaAM, and ten streaming Kroko models; atomic downloads
  with SHA-256 verification under a foreground service, a single-slot recognizer cache with a
  user-configurable idle-unload timer and an `onTrimMemory` hookup, plus an offline fallback
  when a cloud call fails for connectivity reasons. We have nothing in this direction.
- **Realtime streaming transcription** across five providers (`#128`), and 5.3's on-device
  *live* transcription in ten languages (`#233`).
- **Sleeping-server warm-up** (`5f9ca9d5`, `#189`) — `warmUpRewordingServer()` fires a
  throttled `GET /models` at a user's own inference endpoint so a GPU box that wakes on network
  traffic gets the dictation's duration as a head start.
- **Single-call multimodal** transcribe-and-format (`ProviderConfig.useChatAudio`), and
  reasoning effort as a global setting, a per-prompt override (`#155`) and a custom string,
  with automatic retry-without-the-field when a model rejects it.
- **User-CA trust and cleartext HTTP for LAN endpoints** (`AndroidManifest.xml:52`, `#136`).

**What upstream lacks vs. ours.**

- **Native Anthropic.** Anthropic is reached through Anthropic's *OpenAI-compatible* endpoint
  (`ProviderRegistry.kt:185-206`); the only Anthropic-specific code is a URL-prefix branch
  adding `x-api-key` / `anthropic-version` headers for model listing (`:688-692`). The class
  doc concedes it outright: *"Providers with a genuinely different chat API (e.g. Anthropic
  native) would still need their own `LlmProvider` implementation; until then they are
  reachable via OpenRouter"* (`OpenAiCompatibleClient.kt:56-58`). Our
  `AnthropicCompletionRunner` is the thing that comment asks for.
- **Extensibility shape.** Wire-format variation is an enum switch — `TranscriptionApi` (8
  values) dispatched in `transcribeByApi` (`OpenAiCompatibleClient.kt:150`), `RealtimeApi` (7
  values) driving an 887-line `RealtimeClient`. Our `RunnerFactory` + per-shape runner
  interfaces put a new wire format in a new class instead of a new arm.
- **Per-provider parameter tuning.** Verified by grep on the upstream worktree: `temperature`
  and `maxTokens` appear only as request-DTO fields
  (`provider/ProviderModels.kt:66-67`) — there is no corresponding `AppPrefs` entry and no
  settings surface. Our `ParameterRegistry` with model filters and mutual exclusion has no
  counterpart.
- **Secret storage.** The per-provider keyring is one JSON JetPref
  (`ProviderAccount.kt:110-147`) in **plaintext** — no Keystore, no
  `EncryptedSharedPreferences` anywhere in the tree. Ours is not encrypted either, so this is a
  shared weakness rather than a fork advantage, but it is worth naming because upstream's
  keyring holds up to 16 keys instead of two.

> [!NOTE]
> The upstream report lists a **global HTTP/SOCKS5 proxy** as an upstream-superset item. We
> have one too: `Pref.ProxyEnabled` / `Pref.ProxyHost` applied via
> `DictateUtils.applyProxy()` to the OpenAI OkHttp builder and
> `applyProxyToAnthropic()` to the Anthropic one, with a shared authenticator. Upstream's
> proxy is *broader* (applied uniformly to every call including realtime, plus user-CA trust),
> but this is a difference of reach, not presence.

## 4. Assessment — is this feature still sensible?

**For the user, upstream wins and it is not close.** What a heavy dictation user gets from a
provider layer is provider breadth and transcription quality. Upstream offers Soniox, Deepgram
and AssemblyAI alongside ElevenLabs; realtime streaming; and — the one that would actually
change the daily experience — on-device Parakeet German running offline with no per-minute
cost. Nothing in our layer competes with that, and nothing in our layer *blocks* it either: the
gap is not architectural, it is simply work we have not done.

**For a maintainer, our shape is better, and upstream says so.** The runner-interface design is
the right one, and the single concrete consequence is real rather than aesthetic:
ADR-0012's structured `{message, output}` response is obtained on Anthropic through a forced
`emit_result` tool, which is a native-API affordance. Whether Anthropic's OpenAI-compat
endpoint can produce the equivalent via `response_format: json_schema` is genuinely unverified
(gap 2 below) — if it cannot, upstream's Anthropic support silently degrades to the text
fallback for exactly the feature ADR-0012 built, and the architectural argument becomes a
functional one.

**Cost of keeping it in the fork is low.** ~1,400 LOC, no coupling to the state store or the
foreground service, three vendor SDK dependencies, and it has been stable since `030cd760`
apart from additive provider work. It is also load-bearing: every AI call in the fork routes
through `AIOrchestrator`, so "retire" is not a coherent option while the fork ships.

**The counter-argument worth stating.** Our provider breadth (6) is now a liability rather than
a feature, because it invites the maintenance of six code paths while delivering a strict
subset of what one upstream install offers. If the fork ever migrates to 5.x, this cluster is
the one with the *least* to salvage precisely because upstream did the same thing better —
except for the single Anthropic runner.

**Upstream signal is unusually favourable here.** DevEmperor's own source comment names the
missing native `LlmProvider` as a known gap; he has merged externally-authored *features*, not
just fixes (`0ace1f8d` `#167`, and then extended it himself the same day); and the
contribution pattern the upstream analysis identifies — small, well-scoped, issue-anchored — fits
"add one provider implementation behind an existing interface" almost exactly. Of everything in
this fork, this is the single best-shaped upstream contribution candidate.

## 5. Options going forward

**(a) Keep in fork as-is.** Zero marginal cost; it is already the fork's foundation and nothing
else can be built without it. Does nothing about the on-device / realtime gap, which is where
the user-visible value actually sits.

**(b) Port to Dictate Keyboard 5.x as a private patch.** Mostly pointless — upstream's layer is
a superset in breadth and dropping ours in would mean removing theirs. The one exception is the
Anthropic runner, and porting *that* privately is strictly worse than option (c), since the
same work upstreamed benefits us on every future release instead of becoming rebase debt.

**(c) Propose upstream: a native `AnthropicLlmProvider`.** File an issue first, quoting
`OpenAiCompatibleClient.kt:56-58` back at its author and asking whether a native implementation
is wanted before writing it. A well-scoped issue/PR contains: one class implementing the
existing `LlmProvider` interface against `com.anthropic:anthropic-java`; the registry entry
switched from the compat base URL to native; error mapping onto their existing exception
surface; and — the argument that makes it worth merging rather than tidy — tool-forced
structured output and prompt caching, neither of which the compat endpoint exposes cleanly. A
second, smaller candidate is a per-provider parameter surface modelled on `ParameterRegistry`,
but that touches settings UI and product taste, so it is materially more contested.

**(d) Retire / let upstream's equivalent replace it.** Only coherent inside a full migration to
5.x, where the whole `ai/` package evaporates and we inherit 16 providers plus on-device. That
is a decision about the fork, not about this cluster.

*Leaning:* keep it in the fork because it is foundational there, and treat the native Anthropic
provider as the fork's highest-yield upstream contribution — an issue costs an hour and the
gap is upstream-acknowledged.

## 6. Information Gaps

1. **Which provider does Lukas actually use for rewording day to day?** If it is OpenAI or Groq,
   the native-Anthropic argument is architecturally interesting but personally irrelevant.
   *Owner: Lukas.* *Fallback:* query the `usage` table by provider over the last 90 days.
2. **Does Anthropic's OpenAI-compatible endpoint honour `response_format: json_schema`?** This
   decides whether upstream's Anthropic support degrades to the ADR-0012 text fallback, i.e.
   whether our native runner is a functional advantage or only a structural one.
   *Owner: Claude (a single API probe answers it).* *Fallback:* assume it does not, and mark the
   claim as unverified in any issue we file.
3. **Is the ElevenLabs key-terms vocabulary actually maintained?** The feature only pays off if
   the list is populated; an empty list makes the whole ElevenLabs runner a plain STT call.
   *Owner: Lukas.* *Fallback:* read `Pref.ElevenLabsKeytermsParsed` on the device.
4. **Would DevEmperor accept a second `LlmProvider` implementation?** The codebase is
   effectively single-author with a strong design voice; the class comment invites it, but an
   invitation in a comment is not a merge commitment. *Owner: whoever files the issue.*
   *Fallback:* if the issue goes unanswered for two weeks, treat (c) as closed and fall back
   to (a).
5. **Would on-device transcription (Parakeet German, offline, zero marginal cost) change the
   fork's calculus?** It is the single largest capability gap and it is not blocked by our
   architecture — only by nobody having built it. *Owner: Lukas (product call).*

## 7. References

**Source reports**
- [`../sources/fork-feature-inventory.md`](../sources/fork-feature-inventory.md) — cluster 2
  ("AI abstraction layer & provider expansion")
- [`../sources/upstream-rewrite-analysis.md`](../sources/upstream-rewrite-analysis.md) — area 6
  ("AI provider abstraction — adopted / far exceeded"), plus Part C on contribution
  receptiveness

**Our plans & ADRs**
- [`../../../plans/archive/ai-abstraction-layer.md`](../../../plans/archive/ai-abstraction-layer.md)
  — the ten architecture decisions
- [ADR-0012 — Post-Processing as a Persisted Multi-Turn Conversation](../../../decisions/0012-pipeline-post-processing-conversation.md)
  — the `converse()` contract and `allowsStructuredOutputTextFallback`

**Our commits**
- `030cd760` — the abstraction (52 files, +3,007 / −1,510)
- `39b431dc`, `3396effd`, `fea392b8` — ElevenLabs Scribe + key terms

**Upstream evidence** (paths relative to the `upstream/main` worktree, `3e5ebe46` / `v5.3.0`)
- `lib/dictate-core/.../provider/ProviderRegistry.kt:185-206` — Anthropic via the
  OpenAI-compat endpoint; `:400` — the custom-provider factory
- `lib/dictate-core/.../provider/OpenAiCompatibleClient.kt:56-58` — the class doc conceding the
  native-Anthropic gap; `:150` — `transcribeByApi` enum dispatch; `:688-692` — the Anthropic
  header branch; `:75-107` — reasoning-effort retry
- `lib/dictate-core/.../provider/ProviderModels.kt:66-67` — `temperature` / `maxTokens` as DTO
  fields with no settings surface
- `lib/dictate-core/.../provider/ProviderAccount.kt:110-147` — the plaintext JSON keyring
- `provider/LocalModelManager.kt`, `provider/ModelDownloadService.kt`,
  `provider/LocalTranscriptionProvider.kt:320-410` — on-device sherpa-onnx
- `DictateController.kt:2884-2919` — `warmUpRewordingServer()` (`5f9ca9d5`, `#189`)
- Issues: `#104` (on-device STT), `#128` (realtime), `#136` (cleartext LAN), `#143`
  (ElevenLabs/Deepgram/AssemblyAI), `#155` (per-prompt reasoning), `#189` (sleeping server),
  `#233` (on-device live)
