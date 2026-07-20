# `:shared-ai` Extraction — Implementer Spec for Block A

---
date: 2026-07-19
author: Lukas + Claude Code
type: Spec
status: Spec — programmer-ready
context: Binding build instructions for Block A of the desktop-companion-v1 plan — extraction of the AI core from `:app` into a new pure-JVM module `:shared-ai`, behind ports, behaviour-neutral for Android.
related-plan: ../../../../.claude/plans/desktop-companion-v1.md
related-adrs: docs/decisions/0015-companion-monorepo-topology.md, docs/decisions/0016-wire-protocol-typed-dtos-konform.md
---

This spec is the ready-to-implement concretization of chunks **A2 (Pure
Moves)** and **A3 (Ports + Runner/Orchestrator)** from the plan
`desktop-companion-v1`. It describes _what gets built_: the new Gradle module
`:shared-ai`, its exact file-inventory cut, the four ports and their app
adapters, a compilable move sequence, and the characterization tests that
prove behaviour neutrality. It is **not** a substitute for the ADR drafts (A1)
— the foundational decision "fourth module, package unchanged" lives in
`adr-shared-ai-module`; this spec implements it. Chunk **A1** (checking in ADR
drafts + concept research) is pure authoring work and is not covered here.

## Table of Contents

- [Glossary](#glossary)
- [§1 Vision and Motivation](#1-vision-and-motivation)
- [§1a Architecture Walkthrough](#1a-architecture-walkthrough)
- [§2 Acceptance Criteria](#2-acceptance-criteria)
- [§3 Current Inventory of `ai/` (exhaustive)](#3-current-inventory-of-ai-exhaustive)
- [§4 Port Design (Kotlin Signatures)](#4-port-design-kotlin-signatures)
- [§5 Gradle Setup for `:shared-ai`](#5-gradle-setup-for-shared-ai)
- [§6 Move Sequence (compilable steps)](#6-move-sequence-compilable-steps)
- [§7 Directory Layout](#7-directory-layout)
- [§8 Characterization and Regression Tests](#8-characterization-and-regression-tests)
- [§9 Footguns / Anti-Patterns](#9-footguns--anti-patterns)
- [§10 Information Gaps](#10-information-gaps)
- [§11 Change History](#11-change-history)
- [§12 References](#12-references)

## Glossary

### Module & Topology
- **`:shared-ai`** — new fourth Gradle module, pure `kotlin("jvm")`,
  jvmTarget 1.8, with its own dependency policy (SDKs allowed, Android/Ktor
  forbidden). Defined in §5.
- **Port** — platform-neutral interface in `:shared-ai` whose implementation
  is supplied by the platform (`:app` with SharedPreferences/Room; later
  `:companion` with SQLDelight). The four ports: §4.
- **Adapter** — app-side port implementation (`AndroidAiConfig`,
  `RoomUsageSink`, `SharedPrefsProxyConfig`, `MediaMetadataAudioDurationReader`).
  Pure delegation to existing SharedPreferences/Room/DictateUtils logic.

### Movement Categories (see §3)
- **Category (a) — 0-dependency move** — class without Android/SharedPreferences/
  Room/`org.json`; moves unchanged (only `git mv`), package stays
  `net.devemperor.dictate.ai.*`.
- **Category (b) — port migration** — class with today's Android/Room/
  `org.json` coupling; moves only after a port is introduced (signature edit).
- **Category (c) — stays in `:app`** — class that is Android-UI or pill-
  specific and not needed by the desktop pipeline path.

### Behaviour Neutrality
- **Characterization test** — test that pins _existing_ behaviour (runner
  configuration from prefs, proxy application, keyterms serialization),
  written **before** the move, then passing unchanged against the port
  adapters afterwards.
- **Purity test** — `SharedAiPurityTest`, analogous to `SharedPurityTest`;
  forbids `android.`/`androidx.`/`io.ktor` imports in the `:shared-ai` source
  tree. §5.3.

## 1. Vision and Motivation

### 1.1 Why This Extraction Exists

The AI core (provider enum, runners, orchestrator, prompt/conversation logic)
today lives entirely in `:app` and is coupled to Android — but **only
genuinely in three places**: `SharedPreferences` as the key/model/proxy value
store, the Room `UsageDao` for usage tracking, and
`android.media.MediaMetadataRetriever` for the audio duration. The desktop
companion (Block D) needs **the same** AI core without these Android APIs.
Duplicating it would violate the project's core DRY goal and let provider
logic drift permanently between two platforms — exactly the drift that
ADR-0016 already structurally rules out for the wire protocol.

### 1.2 What This Extraction Delivers

1. Exactly **one** implementation of providers, runners, orchestrator core,
   prompt and conversation logic — pure JVM, consumable by `:app` **and**
   `:companion`.
2. Four **ports** (`AiConfig`, `UsageSink`, `ProxyConfig`, `AudioDurationReader`)
   that encapsulate the three Android couplings; `:app` implements them as thin
   adapters with **byte-identical behaviour** to today's state.
3. A machine-enforced **purity test** (`SharedAiPurityTest`) that prevents the
   AI core from ever again introducing an Android dependency.

### 1.3 Discarded Alternatives

- **AI core in `:shared` instead of a new module.** Rejected: `:shared` has the
  strict `SharedPurityTest` (which among other things forbids passing through
  the okhttp API) and carries only `implementation libs.okhttp`; the AI SDKs
  (openai-java, anthropic-java) and their okhttp client builder on the public
  runner surface would dilute this purity. A separate module = separate
  dependency policy, same principles (plan D1, ADR-0015 extension).
- **Leave the enums in `:app` and duplicate them in `:shared-ai`.** Rejected:
  `MessageRole`/`ResponseFormatKind`/`AmbiguityMode` are shared vocabulary of
  the AI core; duplicates drift. They move along (§3.4, §6 step A2.0).
- **Rename the package (`net.devemperor.dictate.sharedai.*`).** Rejected per
  plan-D1: the package stays `net.devemperor.dictate.ai` so that the `:app`
  diffs touch almost only build files and `git log --follow` stays clean.

## 1a. Architecture Walkthrough

### 1a.0 ASCII Stack Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│  :app (Android)                          :companion (JVM 17, später) │
│  Port-Adapter:                            Port-Adapter (Block D):     │
│   AndroidAiConfig     (SharedPreferences)  CompanionAiConfig          │
│   RoomUsageSink       (UsageDao)           SqlDelightUsageSink        │
│   SharedPrefsProxyConfig (DictateUtils)    CompanionProxyConfig       │
│   MediaMetadataAudioDurationReader         JavaSoundAudioDurationRdr  │
└───────────────────────────┬───────────────────────┬─────────────────┘
                            │ implementiert Ports    │ implementiert Ports
                            ▼                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│  :shared-ai (NEU, pure JVM, jvmTarget 1.8)                          │
│  Ports:   AiConfig · UsageSink · ProxyConfig · AudioDurationReader   │
│  Kern:    AIProvider/AIFunction · AIProviderException · AIOrchestrator│
│           RunnerFactory · OpenAICompatibleRunner · Anthropic…Runner   │
│           ElevenLabsTranscriptionRunner · ModelFetcher · Prompt*/     │
│           Conversation* (StructuredResponseCodec, ReviewDecision …)   │
│  Enums:   MessageRole · ResponseFormatKind · AmbiguityMode           │
│  Deps:    openai-java · anthropic-java · okhttp · kotlinx-serialization│
└───────────────────────────┬─────────────────────────────────────────┘
                            │ nutzt (NICHT umgekehrt)
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│  :shared (bestehend, pure JVM, jvmTarget 1.8) — Wire-Protokoll      │
│  KEINE Abhängigkeit auf :shared-ai. :shared-ai hängt NICHT auf :shared│
│  (Block A führt keine Kopplung ein — beide sind unabhängige Blätter). │
└─────────────────────────────────────────────────────────────────────┘
```

> **Module edges (Block A):** `:app → :shared-ai`, `:app → :shared`,
> `:companion → :shared`. `:shared-ai → :shared` is **not** introduced in
> Block A (the AI core does not need the wire protocol). `:companion →
> :shared-ai` comes only in Block D.

### 1a.1 Layer `:shared-ai` — the Shared Core

- **Purpose:** exactly one AI implementation for both platforms; all platform
  access goes through ports.
- **File:** `shared-ai/src/main/kotlin/net/devemperor/dictate/ai/`
- **Contract:** four ports (§4); `SharedAiPurityTest` (§5.3) pins the purity.
- **Detail:** §3–§6.

### 1a.2 Layer Port-Adapter — the Platform Seam

- **Purpose:** `:app` (and later `:companion`) fill the ports with real values
  without the core knowing the platform.
- **File:** `app/src/main/java/net/devemperor/dictate/ai/adapter/` (NEW, §7).
- **Contract:** four adapters, pure delegation; behaviour byte-identical to the
  current state (the characterization tests §8 are the proof).

### 1a.3 Read-this-before-implementing checklist

- [ ] `:shared-ai` compiles at **jvmTarget 1.8** — NOT 17. `:app` consumes it
  and Kotlin does not inline 11/17-bytecode into 8-bytecode (§5.1, ADR-0015
  failure mode). The `java { }` block must set `VERSION_1_8`.
- [ ] The package of every moved file stays `net.devemperor.dictate.ai.*`
  (plan-D1). No rename.
- [ ] Every moved file via `git mv` (move as a move, `git log --follow` intact).
- [ ] No `import android.*` / `androidx.*` / `io.ktor` in the `:shared-ai` tree —
  otherwise `SharedAiPurityTest` fires (mandatory negative test, §5.3).
- [ ] The characterization tests (§8) are written BEFORE the A3 move and run
  red-free both before (against prefs) and after (against ports) the move.
- [ ] `org.json` is **not** available on pure JVM → switch `ElevenLabsKeytermsParser`
  and the ElevenLabs response parse to kotlinx-serialization (§6 A3.4).

## 2. Acceptance Criteria

Fulfils plan-§2 criterion 1 (build invariant) and 2 (behaviour neutrality).

1. **Module exists & compiles.** `shared-ai/build.gradle` +
   `settings.gradle` entry present; `./gradlew :shared-ai:compileKotlin`
   green; `:shared-ai` is at jvmTarget 1.8.
2. **Build invariant.** `./gradlew build` green across `:app`, `:shared`,
   `:shared-ai`, `:companion`.
3. **Purity green + sharp.** `SharedAiPurityTest` green; its negative self-test
   (a fabricated `android.` import is detected) green; a test-inserted
   `import android.content.Context` in a `:shared-ai` file turns the test red
   (manually verified, then reverted).
4. **Behaviour neutrality.** All existing `:app` unit tests green without
   assertion changes; the characterization tests (§8) are green before AND
   after A3. No diff in the constructed API traffic (runner configuration,
   proxy, multipart body — secured by the §8 tests).
5. **Move cleanliness.** `git log --follow` on at least one moved file per
   package shows the history across the move.
6. **No dead path.** In `:app`, no AI-core code reads
   `SharedPreferences`/`UsageDao`/`MediaMetadataRetriever` directly any more —
   access goes through the four adapters (grep check for the old direct
   accesses in the `ai/` tree outside `ai/adapter/`).

## 3. Current Inventory of `ai/` (exhaustive)

Basis: `app/src/main/java/net/devemperor/dictate/ai/`, 36 files, 2501 LOC
(as of 2026-07-19). Column **Cat.** = movement category (Glossary).

### 3.1 Root + Enums

| File | Purpose | Hard dependencies | Cat. |
|---|---|---|---|
| `AIProvider.kt` (also contains `enum AIFunction`) | Provider enum with capability flags; `fromPersistKey` | none | (a) |
| `AIProviderException.kt` | typed error class `ErrorType` | none | (a) |
| `AIOrchestrator.kt` | central orchestration transcribe/complete/converse + usage tracking + `resolveParameters` | `SharedPreferences`, `UsageDao`, `Pref.*` | (b) |
| `ElevenLabsKeytermsParser.kt` | parsing/serialization of keyterms | `org.json.JSONArray` | (b) |

### 3.2 `factory/` + `model/`

| File | Purpose | Hard dependencies | Cat. |
|---|---|---|---|
| `factory/RunnerFactory.kt` | selects provider/model/key/baseUrl from prefs, builds runners; `open` as a test seam (K-1) | `SharedPreferences`, `Pref.*` | (b) |
| `model/ModelInfo.kt` | data holder (id, displayName) | none | (a) |
| `model/ParameterDef.kt` | parameter definition | none | (a) |
| `model/ParameterRegistry.kt` | static parameter catalogues per provider/model | none | (a) |
| `model/ModelFetcher.kt` | live `/models` fetch (OpenAI-compatible) + hardcoded lists | `SharedPreferences`, `Pref.*`, `DictateUtils` (proxy) | (b) |

### 3.3 `runner/` + `prompt/` + `conversation/`

| File | Purpose | Hard dependencies | Cat. |
|---|---|---|---|
| `runner/TranscriptionRunner.kt`, `CompletionRunner.kt` | runner interfaces | `MessageRole`/`ResponseFormatKind` in DTOs | (a)* |
| `runner/TranscriptionOptions.kt`, `TranscriptionResult.kt`, `CompletionOptions.kt`, `CompletionResult.kt`, `ConversationRequest.kt`, `ConversationResult.kt` | DTOs | partly `MessageRole`/`ResponseFormatKind` | (a)* |
| `runner/StructuredOutputGuards.kt` | truncation guard | `AIProvider` | (a) |
| `runner/OpenAICompatibleRunner.kt` | OpenAI/Groq/OpenRouter/Custom | `SharedPreferences`, `Pref.*`, `DictateUtils` (proxy + `getAudioDuration`), `MessageRole`, `ResponseFormatKind`, openai-java SDK | (b) |
| `runner/AnthropicCompletionRunner.kt` | Anthropic Claude | `SharedPreferences`, `Pref.*`, `DictateUtils` (proxy), `MessageRole`, `ResponseFormatKind`, anthropic-java SDK | (b) |
| `runner/ElevenLabsTranscriptionRunner.kt` | ElevenLabs Scribe (okhttp directly) | `SharedPreferences`, `Pref.*`, `DictateUtils` (proxy + `getAudioDuration`), `org.json`, okhttp | (b) |
| `prompt/PromptContext.kt`, `PromptMode.kt`, `PromptBuilder.kt`, `PromptTemplates.kt` | XML prompt building, templates | none | (a) |
| `prompt/PromptService.kt` | prompt building per context | `SharedPreferences`, `Pref.*`, `DictateUtils.getPunctuationPromptForLanguage` | (b) |
| `prompt/SystemPromptResolver.kt` | system prompt resolution | `SharedPreferences`, `Pref.*` | (b) |
| `prompt/PromptTypeClassifier.kt` | classifies PROMPT vs TEXT (pill semantics) | `database.entity.PromptType` | **(c)** |
| `conversation/*` (10 files: `ConversationMessage`, `ConversationReconstructor`, `ConversationTurnBuilder`, `PostProcessingInputs`, `PostProcessingReview`, `ReviewDecision`, `StructuredResponse`, `StructuredResponseCodec` …) | structured-output wire authority, review verdict, turn reconstruction | `MessageRole` (Reconstructor, Message), `AmbiguityMode` (ReviewDecision); otherwise pure | (a)* |

`*` = pure except for the shared enums from §3.4, which move along as part of
the same move.

### 3.4 Shared Enums — move along (critical finding)

The AI core imports four platform-neutral enums from foreign packages. All
four are written **deliberately Android-free** (doc comments in `MessageRole`
and `AmbiguityMode` say so explicitly) but physically live in `:app` packages.
Since `:app → :shared-ai` (not the other way round), the enums used by the
core **must** move along to `:shared-ai`:

| Enum | Current location | Used by the core? | `:app` ripple (files) | Action |
|---|---|---|---|---|
| `MessageRole` | `database/entity/` | yes (runners, `ConversationMessage`, Reconstructor) | 10 (incl. `ConversationMessageEntity`, `MigrationTo8`, `PipelineOrchestrator`, `SessionManager`) | **Move → `:shared-ai`** |
| `ResponseFormatKind` | `database/entity/` | yes (runner results) | 6 (incl. `ProcessingStepEntity`, `MigrationTo8`) | **Move → `:shared-ai`** |
| `AmbiguityMode` | `preferences/` | yes (`ReviewDecision`) | 9 (incl. `JobExecutor`, `DictatePrefs`, `PcDictationActivity`, `DictateInputMethodService.java`) | **Move → `:shared-ai`** |
| `PromptType` | `database/entity/` | no (only `PromptTypeClassifier`, cat. c) | 16 (pill UI, DAO, migrations) | **stays in `:app`** |

Package decision for the three moved enums: **leave the package unchanged**
(`net.devemperor.dictate.database.entity` resp. `.preferences`) — they only
move physically to `shared-ai/src/…`, their import statements in `:app` stay
unchanged (split package across the module boundary, unproblematic at
jvmTarget 1.8 without `module-info` and exactly the pattern plan-D1 chooses for
the `ai` package). This keeps the `:app` diff at **zero** import lines for the
enum users. The alternative (a dedicated package under `ai/`) would touch 25+
files in `:app` — rejected as an unnecessary intervention. See §9 on the
split-package trade-off.

> **Room consequence:** `MessageRole`/`ResponseFormatKind`/`PromptType` are
> double-enum values with SQL CHECK constraints (docs/DATABASE-PATTERNS.md).
> The **enum move changes no schema** — the Room `@TypeConverter`/entity
> columns only reference the enums; as long as package + constant names stay
> the same, the CHECK constraints and converters remain untouched. Verified by
> the existing migration and DAO tests (§8.3).

### 3.5 App Couplings Outside `ai/`

- **`DictateUtils.java`** (Java, `:app`) provides the core with four things:
  proxy application (`applyProxy`, `applyProxyToAnthropic`, `createProxy`,
  `applyProxyAuthenticator`, `isValidProxy`), audio duration (`getAudioDuration`
  via `android.media.MediaMetadataRetriever`), and `getPunctuationPromptForLanguage`.
  The **proxy logic itself is pure JVM code** (java.net.Proxy, regex,
  java.net.Authenticator) — only the value store is `SharedPreferences`. The
  audio duration is the **only genuine Android API** in the core path. →
  `ProxyConfig` and `AudioDurationReader` ports (§4).
- `getPunctuationPromptForLanguage` is pure language-table logic (no Android
  API in the body, only physically in a Java class with other Android methods).
  Two options: (i) move the tables together with `PromptTemplates` to
  `:shared-ai` and have `PromptService` access them directly, or (ii) pass them
  through behind the `AiConfig` port as `punctuationPrompt(lang)`.
  **Recommendation (i)** — the punctuation tables belong conceptually to
  `PromptTemplates` (already cat. a); the move pulls `PROMPT_PUNCTUATION_*` out
  of `DictateUtils` into `PromptTemplates` and lets `DictateUtils` (if still
  needed elsewhere) delegate to it. Details §6 A3.5.

## 4. Port Design (Kotlin Signatures)

All ports live in `shared-ai/src/main/kotlin/net/devemperor/dictate/ai/port/`.
They are **narrow** and deliver _already-resolved_ values — the core knows
neither `Pref` keys nor the Room threading details.

### 4.1 `AiConfig` — Configuration Resolution

Replaces today's `RunnerFactory(sp)` + `AIOrchestrator.resolveParameters`
prefs accesses. Per `AIFunction`, the port delivers the already-selected
provider, model, key, baseUrl and the resolved parameters.

```kotlin
package net.devemperor.dictate.ai.port

import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIProvider

/**
 * Resolves the effective AI configuration for a function slot. The platform
 * (Android: SharedPreferences via DictatePrefs; Companion: active Profile in
 * SQLDelight) implements it. The AI core never sees preference keys.
 *
 * Behaviour parity note: `apiKey` MUST reproduce today's
 * `RunnerFactory.getApiKey` exactly, including the non-ASCII strip
 * (`replace(Regex("[^ -~]"), "")`) — see AIProvider-key characterization test.
 */
interface AiConfig {
    fun provider(function: AIFunction): AIProvider
    fun modelName(function: AIFunction): String
    /** Effective key for the function's provider, already ASCII-stripped. */
    fun apiKey(function: AIFunction): String
    /** Base URL; for CUSTOM the resolved host, else provider.defaultBaseUrl. */
    fun baseUrl(function: AIFunction): String
    /**
     * Completion parameters already filtered against sentinels (temp < 0,
     * maxTokens <= 0, empty reasoning_effort dropped) — reproduces
     * AIOrchestrator.resolveParameters + ParameterRegistry.
     */
    fun completionParameters(provider: AIProvider, model: String): Map<String, Any>
    /** Parsed ElevenLabs keyterms for the active transcription config, or null. */
    fun elevenLabsKeyterms(): List<String>?
}
```

> **Rationale for the cut:** `resolveParameters` and the keyterms resolution
> used to sit in the orchestrator and read prefs directly. They move behind
> `AiConfig` so the orchestrator becomes prefs-free. `ParameterRegistry`
> (cat. a) stays in the core and is used by the adapter to know the parameter
> names.

### 4.2 `UsageSink` — Usage Tracking

Replaces the direct `UsageDao` coupling in the orchestrator.

```kotlin
package net.devemperor.dictate.ai.port

/**
 * Sink for post-call usage accounting. Android backs it with the Room UsageDao;
 * the Companion with a SQLDelight table. Called AFTER a successful runner call,
 * on the same background thread as today.
 *
 * Threading parity: the Android adapter MUST preserve today's call semantics —
 * UsageDao.addUsage runs synchronously on the caller's background thread
 * (speechApiThread / rewordingApiThread). The adapter adds no threading.
 */
interface UsageSink {
    fun addUsage(
        modelName: String,
        audioDurationSeconds: Long,
        promptTokens: Long,
        completionTokens: Long,
        providerName: String,
    )
}
```

### 4.3 `ProxyConfig` — okhttp Proxy Application

Encapsulates the four `DictateUtils` proxy methods. The port receives the
respective SDK client builder and applies the proxy (or not). This keeps the
SDK-specific builder types (openai/anthropic) out of the port by offering one
type-specific method each — analogous to today's two `DictateUtils` methods.

```kotlin
package net.devemperor.dictate.ai.port

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import java.net.Proxy

/**
 * Applies the user's proxy configuration to an SDK/okhttp client. Android backs
 * it with DictateUtils (SharedPreferences-driven); the Companion with its own
 * settings. A no-op when no valid proxy is configured — reproduces today's
 * `if (ProxyEnabled && isValidProxy(host))` guard.
 *
 * Note: the openai/anthropic builder types come from the SDKs, which are
 * `:shared-ai` dependencies — allowed here (unlike in `:shared`).
 */
interface ProxyConfig {
    fun applyTo(builder: OpenAIOkHttpClient.Builder)
    fun applyTo(builder: AnthropicOkHttpClient.Builder)
    /** For the raw-okhttp ElevenLabs runner: the resolved Proxy or null, plus
     *  installing the process-wide Authenticator (today's createProxy +
     *  applyProxyAuthenticator pair). */
    fun rawProxy(): Proxy?
    fun installAuthenticator()
}
```

> **Trade-off:** `rawProxy()`/`installAuthenticator()` cover the ElevenLabs
> path, which builds okhttp directly (no SDK builder). This is a bit broader
> than ideal, but it mirrors today's `DictateUtils` surface exactly 1:1 and
> keeps behaviour neutrality provable. A later unification (a single
> `configureOkHttp(OkHttpClient.Builder)`) is possible once the SDK builders
> expose their okhttp builder — today they do not do so uniformly, hence the
> three methods.

### 4.4 `AudioDurationReader` — Audio Duration

Encapsulates the only genuine Android media API in the core path.

```kotlin
package net.devemperor.dictate.ai.port

import java.io.File

/**
 * Returns the audio duration in whole seconds, or -1 if unknown — reproduces
 * DictateUtils.getAudioDuration (android.media.MediaMetadataRetriever) exactly,
 * including the -1 fallback on any error. The Companion backs it with a
 * javax.sound / WAV-header reader (Block D).
 */
interface AudioDurationReader {
    fun durationSeconds(file: File): Long
}
```

### 4.5 Wiring — Who Constructs Whom

`AIOrchestrator` and `RunnerFactory` receive the ports via constructor. The
`open RunnerFactory` test seam (K-1) is preserved — tests keep subclassing it.

```kotlin
// :shared-ai
class AIOrchestrator(
    private val config: AiConfig,
    private val usageSink: UsageSink,
    private val factory: RunnerFactory,
)

open class RunnerFactory(
    private val config: AiConfig,
    private val proxy: ProxyConfig,
    private val audioDuration: AudioDurationReader,
)
```

App side (adapters, in `app/.../ai/adapter/`):

```kotlin
// :app — dünne Delegation, kein neues Verhalten
class AndroidAiConfig(private val sp: SharedPreferences) : AiConfig { /* heutige RunnerFactory-Prefs-Logik */ }
class RoomUsageSink(private val usageDao: UsageDao) : UsageSink { override fun addUsage(...) = usageDao.addUsage(...) }
class SharedPrefsProxyConfig(private val sp: SharedPreferences) : ProxyConfig { /* delegiert an DictateUtils */ }
class MediaMetadataAudioDurationReader : AudioDurationReader { override fun durationSeconds(f) = DictateUtils.getAudioDuration(f) }
```

The previous `AIOrchestrator(sp, usageDao)` constructor has exactly one caller
context in the IME service; it is switched to `AIOrchestrator(AndroidAiConfig(sp),
RoomUsageSink(usageDao), RunnerFactory(AndroidAiConfig(sp), SharedPrefsProxyConfig(sp), MediaMetadataAudioDurationReader()))`.
An `:app`-side convenience factory (`fun androidOrchestrator(sp,
usageDao): AIOrchestrator`) encapsulates the wiring in one place.

## 5. Gradle Setup for `:shared-ai`

### 5.1 `settings.gradle`

Add after the `:shared`/`:companion` include group:

```groovy
// :shared-ai — pure kotlin("jvm") module (jvmTarget 1.8), consumed by BOTH :app
//              and :companion. Android-free & Ktor-free (own purity test), but —
//              unlike :shared — the AI SDKs (openai-java, anthropic-java) and
//              okhttp ARE allowed. See ADR adr-shared-ai-module / ADR-0015.
include ':shared-ai'
```

### 5.2 `shared-ai/build.gradle`

```groovy
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// jvmTarget 1.8 — DO NOT raise without raising :app with it. :app consumes this
// module and Kotlin refuses to inline 11/17-bytecode into 8-bytecode (ADR-0015).
// The AI SDKs are compiled for Java 8 and already run inside :app today, so a
// 1.8 target is safe for them.
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8
    }
}

// No `repositories { }` — settings.gradle sets FAIL_ON_PROJECT_REPOS.

dependencies {
    // AI SDKs — the whole reason this is a separate module from :shared.
    api libs.openai.java
    api libs.anthropic.java
    // okhttp: raw client for the ElevenLabs runner + proxy plumbing.
    implementation libs.okhttp
    // kotlinx-serialization replaces org.json (ElevenLabs parse + keyterms).
    implementation libs.kotlinx.serialization.json

    testImplementation libs.junit
    testImplementation libs.okhttp.mockwebserver
}
```

> **`api` vs `implementation` for the SDKs:** `api`, because runner
> DTOs/exceptions and the `ProxyConfig` port signatures carry SDK builder types
> on the public surface (`OpenAIOkHttpClient.Builder` etc.). `:app` already
> compiles against these types today — `api` keeps that transitively available.
> Verification in the chunk: if the runner surface leaks no SDK types, downgrade
> to `implementation` (narrower is better). The `ProxyConfig` port definitely
> leaks them → at minimum it needs `api` on the SDKs.

### 5.3 `SharedAiPurityTest`

`shared-ai/src/test/kotlin/net/devemperor/dictate/ai/SharedAiPurityTest.kt` —
1:1 following the `SharedPurityTest` model, with a **different** forbidden list:

```kotlin
private val forbiddenImports = mapOf(
    "android." to "Android APIs — :shared-ai must stay consumable from the desktop companion",
    "androidx." to "AndroidX APIs — :shared-ai must stay consumable from the desktop companion",
    "io.ktor" to "Ktor — the server lives in :companion only; :shared-ai compiles at jvmTarget 1.8",
    // NOTE: coroutines are NOT forbidden here by necessity, but :shared-ai is
    // blocking-by-design (SDKs on background executors) — keep it coroutine-free
    // to stay consumable from :app's 1.7.3 line. Add "kotlinx.coroutines" if the
    // team wants it pinned (recommended — mirrors :shared).
    "org.json" to "org.json — not on pure JVM; use kotlinx-serialization",
)
```

The `theTestItself_findsAViolationWhenThereIsOne` self-test is carried over
(scanner-really-reads guarantee). **Recommendation:** add `kotlinx.coroutines`
and `org.json` to the forbidden list — the former mirrors the `:shared`
doctrine, the latter pins the A3.4 switch permanently.

## 6. Move Sequence (compilable steps)

Each step is one commit; after each step `./gradlew build` must be green.
Prefix `[A.2]` resp. `[A.3]` (plan conventions). `[MOVE]`/`[EDIT]`/`[NEW]`.

### Chunk A2 — Pure Moves (behaviour-neutral, no signature change)

**A2.0 — Module scaffold + purity test [NEW].**
`settings.gradle` include, `shared-ai/build.gradle` (§5.2), `SharedAiPurityTest`
(§5.3). `:app` gets `implementation project(':shared-ai')` in `app/build.gradle`.
Build green (empty module). The purity test runs (initially scanning only itself
— loosen its `sources.size > 5` guard until files are present, OR merge A2.0 with
A2.1 so real sources are immediately available — **recommendation: merge**).

**A2.1 — Enums first [MOVE].**
`git mv` of `MessageRole.kt`, `ResponseFormatKind.kt` (from
`app/.../database/entity/`) and `AmbiguityMode.kt` (from `app/.../preferences/`)
to `shared-ai/src/main/kotlin/net/devemperor/dictate/{database/entity,preferences}/`.
**Package line unchanged.** The target directory mirrors the package. No import
change in `:app` needed (split package). Build green. Reason for "first": the
conversation/runner moves in A2.2 reference them; if they are already in the
target module, no transient backward edge `:shared-ai → :app` arises.

**A2.2 — 0-dependency core [MOVE].** `git mv` (package unchanged
`net.devemperor.dictate.ai.*`, target tree `shared-ai/src/main/kotlin/…/ai/`):
- Root: `AIProvider.kt` (incl. `AIFunction`), `AIProviderException.kt`.
- `model/`: `ModelInfo`, `ParameterDef`, `ParameterRegistry`.
- `runner/` interfaces + DTOs: `TranscriptionRunner`, `CompletionRunner`,
  `TranscriptionOptions/Result`, `CompletionOptions/Result`,
  `ConversationRequest/Result`, `StructuredOutputGuards`.
- `prompt/`: `PromptContext`, `PromptMode`, `PromptBuilder`, `PromptTemplates`.
- `conversation/`: **all 10 files** (`ConversationMessage`,
  `ConversationReconstructor`, `ConversationTurnBuilder`, `PostProcessingInputs`,
  `PostProcessingReview`, `ReviewDecision`, `StructuredResponse`,
  `StructuredResponseCodec`, …).

Build green: everything here depends only on the §3.4 enums (now in the same
module) and on each other. **Stays in `:app`:** `prompt/PromptTypeClassifier.kt`
(cat. c, needs `PromptType`, which stays in `:app`).

**A2.3 — move the pure tests along [MOVE].** The pure JUnit tests (no
Robolectric, no SharedPreferences) to `shared-ai/src/test/…`:
`conversation/ConversationReconstructorTest`, `ConversationTurnBuilderTest`,
`ReviewDecisionTest`, `StructuredResponseCodecTest`,
`runner/StructuredOutputGuardsTest`, `runner/StructuredOutputSupportTest`.
`PromptTypeClassifierTest` stays in `:app` (tests the class remaining in
`:app`). Build + `:shared-ai:test` green.

### Chunk A3 — Ports + Runner/Orchestrator (signature changes)

> Order: **first** write the characterization tests (§8) in `:app` and see them
> green, **then** introduce the ports, **then** migrate the (b) classes.

**A3.1 — Define the ports [NEW].** The four port interfaces (§4) in
`shared-ai/.../ai/port/`. Build green (interfaces only).

**A3.2 — Migrate the runners [MOVE+EDIT].** `OpenAICompatibleRunner`,
`AnthropicCompletionRunner`, `ElevenLabsTranscriptionRunner` to `:shared-ai`;
constructor `sp: SharedPreferences` → `proxy: ProxyConfig` (+ `audioDuration:
AudioDurationReader` for the two transcription runners). Body edits:
`sp.get(Pref.Proxy*) + DictateUtils.applyProxy…` → `proxy.applyTo(builder)`;
`DictateUtils.getAudioDuration(f)` → `audioDuration.durationSeconds(f)`. No
other logic change.

**A3.3 — Migrate RunnerFactory [MOVE+EDIT].** `factory/RunnerFactory.kt` →
`:shared-ai`; `sp` accesses (provider/model/key/baseUrl) → `config: AiConfig`.
The key/model/baseUrl selection logic moves **into the `AndroidAiConfig` adapter**
(`:app`), the factory now only calls `config.provider/modelName/apiKey/baseUrl`.
The `open` seam stays.

**A3.4 — ModelFetcher + ElevenLabsKeytermsParser [MOVE+EDIT].**
`ModelFetcher`: `sp`→`ProxyConfig`. `ElevenLabsKeytermsParser`: `org.json.JSONArray`
→ kotlinx-serialization (`Json.encodeToString`/`decodeFromString` of a
`List<String>`; the `toJson`/`fromJson` contract unchanged — empty/`"[]"` input
→ empty list, the defensive try/catch stays). The ElevenLabs response `text`
parse in the runner (`JSONObject(body).optString("text")`) likewise to
kotlinx-serialization (`Json.parseToJsonElement`).

**A3.5 — Prompt service + punctuation tables [MOVE+EDIT].** `PromptService`,
`SystemPromptResolver` → `:shared-ai`; `sp` accesses → `AiConfig` (resp. a
narrow prompt-config access; the prompt prefs `StylePromptSelection`,
`SystemPromptSelection`, `*CustomText` into the adapter). `PROMPT_PUNCTUATION_*`
tables + `getPunctuationPromptForLanguage` logic from `DictateUtils` to
`PromptTemplates` (§3.5 option i). **Check:** whether `getPunctuationPromptForLanguage`
still has other `:app` callers — if so, let `DictateUtils` delegate to it
(backward compatibility), otherwise remove it.

**A3.6 — Migrate the orchestrator [MOVE+EDIT].** `AIOrchestrator` → `:shared-ai`;
`sp`/`usageDao` → `AiConfig`/`UsageSink`. `resolveParameters` +
`elevenLabsKeyterms` resolution move into `AndroidAiConfig`
(`completionParameters`/`elevenLabsKeyterms`). The orchestrator becomes prefs-free.

**A3.7 — Adapters + wiring [NEW+EDIT].** `app/.../ai/adapter/`:
`AndroidAiConfig`, `RoomUsageSink`, `SharedPrefsProxyConfig`,
`MediaMetadataAudioDurationReader` (§4.5). Switch the IME service call site to
the new orchestrator constructor (convenience factory). Adapt `AIOrchestratorConverseTest`
(Robolectric, stays in `:app`) to the new signature — assertions unchanged.
Build + all tests green = criterion §2.4.

## 7. Directory Layout

```
Dictate/
├── settings.gradle                         [EDIT]  + include ':shared-ai'
├── shared-ai/                              [NEW]   viertes Modul
│   ├── build.gradle                        [NEW]   §5.2 (jvmTarget 1.8, SDKs)
│   └── src/
│       ├── main/kotlin/net/devemperor/dictate/
│       │   ├── ai/                         [MOVE]  Kern (AIProvider … Orchestrator)
│       │   │   ├── port/                   [NEW]   AiConfig/UsageSink/ProxyConfig/AudioDurationReader
│       │   │   ├── factory/RunnerFactory   [MOVE+EDIT]
│       │   │   ├── model/                  [MOVE]  ModelInfo/ParameterDef/Registry/ModelFetcher(EDIT)
│       │   │   ├── prompt/                  [MOVE]  (ohne PromptTypeClassifier)
│       │   │   ├── conversation/            [MOVE]  alle 10
│       │   │   └── runner/                  [MOVE+EDIT]  Runner + DTOs
│       │   ├── database/entity/            [MOVE]  MessageRole, ResponseFormatKind (Package unverändert)
│       │   └── preferences/                [MOVE]  AmbiguityMode (Package unverändert)
│       └── test/kotlin/net/devemperor/dictate/ai/
│           ├── SharedAiPurityTest          [NEW]   §5.3
│           ├── conversation/               [MOVE]  4 pure Tests
│           └── runner/                     [MOVE]  StructuredOutput* Tests
├── app/
│   ├── build.gradle                        [EDIT]  + implementation project(':shared-ai')
│   └── src/main/java/net/devemperor/dictate/
│       └── ai/
│           ├── adapter/                     [NEW]   4 Port-Adapter (§4.5)
│           └── prompt/PromptTypeClassifier  [KEEP]  bleibt (Kat. c, PromptType)
```

**File delta:** ~30 moves (26 `ai/` files minus PromptTypeClassifier + 3 enums
+ pure tests), 2 new build entries, 1 new purity test, 4 new ports, 4 new
adapters, ~8 (b) classes with a signature edit.

## 8. Characterization and Regression Tests

Convention: `~/.claude/snippets/test-first-patterns.md`. The characterization
tests (8.1) are written **before** A3, against today's prefs-based code, and
run unchanged against the adapters after the move — that is the
behaviour-neutrality proof (plan-§8 point 1).

### 8.1 New Characterization Tests (BEFORE A3, in `:app`)

| Test (new) | Pins | Why load-bearing |
|---|---|---|
| `AiConfigParityTest` | For one fixture-prefs constellation per provider (OPENAI/GROQ/ANTHROPIC/OPENROUTER/ELEVENLABS/CUSTOM): `provider/modelName/apiKey/baseUrl` = exactly today's `RunnerFactory` values, incl. **non-ASCII key strip** and CUSTOM host resolution | the key/model/baseUrl selection moves from `RunnerFactory` into `AndroidAiConfig` — this test proves equality |
| `ParameterResolutionParityTest` | `completionParameters` = today's `AIOrchestrator.resolveParameters` incl. sentinel filter (temp<0, maxTokens≤0, empty reasoning_effort) for all `PARAMETER_PREFS` providers | `resolveParameters` moves into the adapter |
| `ProxyConfigParityTest` | For proxy-on/off × http/socks5 × with/without user:pass: `rawProxy()` returns the same `Proxy` as `DictateUtils.createProxy`; `applyTo` sets `.proxy(...)` exactly when it does today | the proxy path is what breaks "silently" in runners |
| `ElevenLabsKeytermsSerializationParityTest` | `toJson`/`fromJson` after the kotlinx switch = org.json behaviour (empty input → `[]`/empty; round-trip Unicode terms) | A3.4 swaps the JSON lib |

### 8.2 Existing Tests That Already Cover the AI Layer (stay green)

- `ai/runner/ElevenLabsTranscriptionRunnerTest` — multipart-body wire format
  (keyterms as repeated parts, model_id). After A3.2, against a `ProxyConfig`
  fake instead of `FakeSharedPreferences`; assertions unchanged. **Moves to
  `:shared-ai`.**
- `ai/AIOrchestratorConverseTest` — converse passthrough + usage tracking via
  the `open RunnerFactory` seam. **Stays in `:app`** (Robolectric), adapted to
  the new constructor signature, assertions unchanged.
- `ai/conversation/*Test` (4×), `ai/runner/StructuredOutput*Test` (2×) — pure,
  **move to `:shared-ai`** (A2.3).
- `ai/ElevenLabsKeytermsParserTest` — moves to `:shared-ai`; after A3.4 without
  Robolectric (org.json is gone).
- `ai/prompt/PromptTypeClassifierTest` — **stays in `:app`** (tests the class
  remaining there).

### 8.3 Enum-Move Safeguard (existing tests, unchanged and green)

`database/migration/MigrationTo8Test`/`MigrationTo11Test`, `dao/PromptDaoAutoApplyTest`,
`dao/Session*Test` reference the double-enum values. They stay green unchanged
because the package + constants of the moved enums remain the same (§3.4) —
they are the proof that the enum move does not touch the Room schema.

### 8.4 Purity Negative Test

`SharedAiPurityTest.theTestItself_findsAViolationWhenThereIsOne` (carried over) +
one-time manual verification from §2.3 (a fabricated `android.` import turns it
red).

## 9. Footguns / Anti-Patterns

| Anti-pattern | Why it's bad | Correction |
|---|---|---|
| Set `:shared-ai` to jvmTarget 17 (like `:companion`) | `:app` (1.8) cannot inline the bytecode; the error appears at the next `:app` compile, far from `shared-ai/build.gradle` | jvmTarget **1.8** + inline comment (§5.2), ADR-0015 failure mode |
| Rename the enum package to `ai.*` during the move | 25+ `:app` files + Room converter/CHECK references would have to come along; migration tests break | Leave the package unchanged, only move it physically (split package, §3.4) |
| Pull `PromptTypeClassifier` along to `:shared-ai` (plan-A2 lists it) | drags `PromptType` (16 `:app` pill files) along; pills are deliberately foreign to the desktop | Cat. (c): leave it in `:app`; the deviation from plan-A2 is noted in §12/the report |
| Make the ports "thick" (pass through pref keys) | the core knows platform details again; the companion would have to reproduce pref semantics | Ports deliver **resolved** values (§4); resolution in the adapter |
| Write the characterization tests only after the move | "a test that was never red" — proves no neutrality | Tests BEFORE A3 against the prefs code, then unchanged against the adapters (§8.1) |
| Leave `org.json` in the `:shared-ai` tree | not present on pure JVM (companion) → NoClassDefFound at runtime | kotlinx-serialization (A3.4); optionally pinned via the purity test (§5.3) |
| Accept the split package as a permanent solution without naming it | two modules share `database.entity`/`preferences` — a conflict under a later JPMS/`module-info` | a deliberately documented trade-off; §10 Gap 1 as the owner |

## 10. Information Gaps

1. **Split package `database.entity`/`preferences` across the module boundary.**
   The three moved enums will share their package between `:app` and `:shared-ai`
   going forward. Uncritical at jvmTarget 1.8 without `module-info` (Android has
   no JPMS), but a latent smell. *Owner:* the A3 agent documents it in the ADR
   draft `adr-shared-ai-module`; *fallback:* accepted for v1, later
   consolidation of the shared enums into `:shared` (at Block C, where entities
   move to `:shared` anyway) as a documented option.
2. **`api` vs `implementation` for the SDKs.** Whether the runner surface leaks
   SDK types (→ `api` needed) or only `ProxyConfig` (→ SDKs `implementation`,
   port `api`) is shown by the compile. *Owner:* the A2.0 agent verifies at the
   green build; *fallback:* `api` (broader, safe), narrowing to `implementation`
   within the chunk if possible (§5.2).
3. **Further `DictateUtils.getPunctuationPromptForLanguage` callers.** Whether
   the punctuation tables can move to `PromptTemplates` without breaking the
   `:app` remainder. *Owner:* the A3.5 agent (grep + delegation fallback);
   *fallback:* `DictateUtils` delegates to the moved tables.
4. **Anthropic/openai-java bytecode target.** Assumption: both compiled for
   Java 8 (they run in `:app` @1.8 today). *Owner:* the A2.0 agent verifies at
   the build; *fallback:* if an SDK carries 11-bytecode, that is a real blocker
   → escalation (would force an `:app` jvmTarget bump, outside Block A).

## 11. Change History

### 2026-07-19 — Initial version

- **Trigger:** Team-lead assignment "spec research Block A: shared-ai" on the
  basis of the implementation-ready plan `desktop-companion-v1`.
- **Reasoning:** Exhaustive current-code research (`app/.../ai/` 36 files,
  `DictateUtils`, shared enums, existing tests) + port design + compilable
  move sequence, so that A2/A3 are executable without further foundational
  questions.
- **What changed:** First version — inventory (§3), 4 ports (§4), Gradle setup
  (§5), 7-step move sequence (§6), characterization tests (§8).

### 2026-07-20 — Cross-spec decisions incorporated (plan §3 D5)

- **Trigger:** Plan refinement by the architect after all five block specs were
  available; three decisions concern this spec.
- **Reasoning:** (a) **Enum placement confirmed (D5.a):** The moves from §3.4
  stay as specified — `AIProvider`/`AmbiguityMode`/`MessageRole`/
  `ResponseFormatKind` to `:shared-ai`, NO move to `:shared`, NO
  `:shared-ai`→`:shared` edge; `:shared` defines its own wire enums
  (entity model §4.8/D6), parity tests + mappers in `:app` — consistent
  with the ADR-0016 wire-vs-domain doctrine. (b) **PromptTypeClassifier
  deviation confirmed (D5.d):** stays in `:app` (cat. c), the plan-A2 list
  was corrected accordingly — the §9 footgun entry is thereby
  plan-conformant. (c) **Scope extension A2 (D5.e):** `core/AmplitudeProcessor.kt`
  (pure `kotlin.math`, today `app/.../core/`) is ADDITIONALLY moved
  package-preservingly to `:shared-ai` (split package `net.devemperor.dictate.core`,
  same pattern as §3.4) — instead of the `:companion` copy originally
  foreseen in desktop-host §15 Gap 4; rationale: the amplitude curve parameters
  are the F19 design spec, and a copy would drift invisibly.
- **What changed:** No body rework needed (a/b confirm the spec's state); the
  A2 move scope extended by `AmplitudeProcessor` (this entry is the normative
  source for it; the §3 inventory lists only `ai/` files).

### 2026-07-20 — Freshness pass (post-implementation, before archival)

- **Trigger:** Integration check after completion of Blocks A–E (finding
  `integ-1`, green) — reconciliation of the five block specs against the built
  state before the F-stage archival/EN translation.
- **Reasoning:** This spec is correct as-built. `:shared-ai` (jvmTarget 1.8,
  package `net.devemperor.dictate.ai`) was built with the four ports (`AiConfig`/
  `UsageSink`/`ProxyConfig`/`AudioDurationReader`) plus the `SecretStore` port
  (Block B, `ai/secrets/`); `AIProvider`/`AmbiguityMode`/`AIFunction`/
  `MessageRole`/`ResponseFormatKind` live in the module as specified in §3.4/D5.a;
  `AmplitudeProcessor` is moved package-preservingly (D5.e). One detail outside
  this spec, for context: the **wire enums** used by the sister specs
  (config family + catalog) are all consolidated in the ONE file
  `shared/src/main/kotlin/net/devemperor/dictate/shared/config/ConfigEnums.kt`
  (not in multiple `catalog.*Wire` copies) — consistent with the mirror
  approach from D5.a, but concerns `:shared`, not `:shared-ai`.
- **What changed:** No body rework — confirmation of behaviour neutrality and
  module topology against the final state; no residual drift in this spec.

## 12. References

- **Related Plan:** `~/.claude/plans/desktop-companion-v1.md` — §5 Block A
  (chunks A1–A3), §3 D1, §7 depends_on (A3→A2, B1→A2), plan-conventions block.
- **Related ADRs:** `docs/decisions/0015-companion-monorepo-topology.md`
  (monorepo topology, Kotlin ceiling 2.1.20, jvmTarget-1.8 inline constraint,
  `SharedPurityTest` model), `docs/decisions/0016-wire-protocol-typed-dtos-konform.md`
  (SSoT doctrine, why duplication is avoided). The plan-scoped ADR draft
  `adr-shared-ai-module` (A1) carries the foundational decision; this spec implements it.
- **Source code (extraction basis):**
  - `app/src/main/java/net/devemperor/dictate/ai/` (36 files, §3)
  - `app/.../ai/AIOrchestrator.kt:29-33` (constructor sp/usageDao),
    `:168-182` (resolveParameters), `:50-52` (keyterms)
  - `app/.../ai/factory/RunnerFactory.kt:83-113` (getApiKey/getBaseUrl),
    `:102` (non-ASCII strip)
  - `app/.../ai/runner/OpenAICompatibleRunner.kt:43-57` (buildClient/proxy),
    `:98` (getAudioDuration)
  - `app/.../ai/runner/ElevenLabsTranscriptionRunner.kt:31-49` (proxy),
    `:118-147` (buildMultipartBody), `:98` (org.json parse)
  - `app/.../DictateUtils.java:157-171` (getAudioDuration),
    `:205-276` (createProxy/applyProxyAuthenticator/applyProxy/applyProxyToAnthropic),
    `:100-116` (getPunctuationPromptForLanguage)
  - Shared enums: `database/entity/MessageRole.kt`, `ResponseFormatKind.kt`,
    `PromptType.kt`, `preferences/AmbiguityMode.kt`
- **Models/templates:** `shared/build.gradle` (jvmTarget-1.8 pattern),
  `shared/src/test/kotlin/net/devemperor/dictate/shared/SharedPurityTest.kt`
  (purity-test template), `settings.gradle:24-27` (include pattern)
- **Conventions:** `docs/DATABASE-PATTERNS.md` (double-enum + CHECK),
  `~/.claude/snippets/test-first-patterns.md` (characterization/regression),
  CLAUDE.md (AI goes only through `AIOrchestrator`, new Kotlin/legacy Java)
