# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Dictate is an Android Input Method Editor (IME) — a keyboard app that uses AI (OpenAI Whisper, GPT, Anthropic Claude, Groq, OpenRouter) for speech-to-text transcription and text rewording. Package: `net.devemperor.dictate`, min SDK 26, target SDK 35.

## Build Commands

```bash
./gradlew assembleDebug          # Debug APK
./gradlew assembleRelease        # Release APK
./gradlew build                  # Full build (all variants)
./gradlew test                   # Unit tests
./gradlew connectedAndroidTest   # Instrumented tests
```

No linter or formatter is configured.

## Architecture

**Layered architecture** with a mix of Kotlin (new code) and Java (legacy).

### Module Topology

Gradle monorepo with **four** modules (ADR-0015 established the first three,
ADR-0028 added `:shared-ai`):

- **`:app`** — the Android IME/keyboard app (Android SDK, jvmTarget 1.8).
- **`:shared`** — pure-JVM wire + entity module (jvmTarget 1.8): protocol DTOs,
  Konform validations (ADR-0016), and the configuration **entities** +
  canonical/v3 codec (ADR-0030). Wire-purity is machine-enforced by
  `SharedPurityTest` — no Android, no Ktor, no coroutines.
- **`:shared-ai`** — pure-JVM **AI core** (jvmTarget 1.8, package
  `net.devemperor.dictate.ai`), consumed by both `:app` and `:companion`:
  providers, runners, `AIOrchestrator`, the post-processing conversation, prompt
  building, `AmplitudeProcessor`, and the four platform ports (`AiConfig`,
  `UsageSink`, `ProxyConfig`, `AudioDurationReader`) plus the `SecretStore` port.
  Most classes carry the `net.devemperor.dictate.ai` package, but
  `AmplitudeProcessor` was moved in package-preserving under
  `net.devemperor.dictate.core` from its `:app` origin (D5.e) — so `:shared-ai`
  is *predominantly* but not exclusively the `.ai` package.
  Its own `SharedAiPurityTest` allows the AI SDKs + OkHttp but forbids
  Android/Ktor/coroutines (ADR-0028). **`git log --follow`** traces classes moved
  out of `:app`.
- **`:companion`** — Compose Desktop companion (JVM 17): dictation host, catalog
  peer, SQLDelight persistence.

**Kotlin ceiling ≤ 2.1.20** (ADR-0015) is compiler-wide and applies to `:shared-ai`
too — every new dependency (incl. AI SDKs) must be built with Kotlin ≤ 2.1.20.

### Core Layer (`core/`)
- `DictateInputMethodService.java` — Main IME service (~2100 lines). Handles keyboard UI, recording, orchestration. Uses `ExecutorService` threads for API calls.
- `RecordingManager.kt`, `BluetoothScoManager.kt`, `PromptQueueManager.kt`, `AutoFormattingService.kt` — extracted concerns.

### AI Abstraction Layer (`ai/`, in `:shared-ai`)
The AI core lives in **`:shared-ai`** (package `net.devemperor.dictate.ai`), shared
by phone and desktop (ADR-0028). `:app` keeps only the Android **port
implementations** (`ai/adapter/`, `secrets/`). Never call an AI SDK directly from
UI/service code — go through `AIOrchestrator`.
- `AIOrchestrator.kt` — Central entry point for all AI operations (transcription + completion). Tracks usage after calls.
- `AIProvider` enum — Defines providers (OPENAI, GROQ, ANTHROPIC, OPEN_ROUTER, CUSTOM) with capability flags (`supportsTranscription`, `supportsCompletion`, `isOpenAICompatible`).
- `RunnerFactory` — Factory creating `TranscriptionRunner` or `CompletionRunner` based on provider.
- Runners: `OpenAICompatibleRunner` (handles OpenAI-compatible APIs), `AnthropicCompletionRunner` (Anthropic-specific).
- `PromptService.kt` + `PromptBuilder.kt` — XML-tag builder for structured prompts with context-specific system prompts (`PromptContext`: REWORDING, LIVE, QUEUED).

### Preferences (`preferences/`)
- `DictatePrefs.kt` — Type-safe `SharedPreferences` via `sealed class Pref<T>`. Access: Kotlin `sp.get(Pref.SomeKey)`, Java `DictatePrefsKt.get(sp, Pref.Something.INSTANCE)`.
- `PrefsMigration.kt` — Handles preference schema migrations.

### Database (`database/`)
- Room database with `UsageEntity` (API usage tracking) and `PromptEntity` (custom rewording prompts).
- Singleton `DictateDatabase.kt` with exported schemas in `app/schemas/`.

### Settings & UI
- `settings/` — Settings activities and preferences fragments.
- `rewording/` — Prompt editing and keyboard prompt selection UI.
- `onboarding/` — First-run experience.
- `usage/` — Usage statistics display.

## Key Conventions

- New code is written in **Kotlin**, legacy code remains **Java** (don't convert without reason).
- Preferences are always accessed through `DictatePrefs.kt` sealed class — never use raw string keys.
- AI provider integration goes through `AIOrchestrator` → `RunnerFactory` → Runner interfaces. Never call AI SDKs directly from UI/service code.
- Database access via Room DAOs, singleton `DictateDatabase.getInstance()`.
- **Secrets go through the `SecretStore` port** (`net.devemperor.dictate.ai.secrets`, in `:shared-ai`) — never plaintext `SharedPreferences`. API keys and the pairing secret are encrypted at rest (Android Keystore AES-GCM / Windows DPAPI / POSIX-`0600` file fallback). A `get` distinguishes "no secret" (`null`) from `DecryptionFailed` — never collapse a decrypt failure into an empty key (ADR-0029).
- **Configuration is modelled as entities, not loose prefs** — ProviderConfig / ApiCredential / ModelRef / Prompt / Profile are defined once in `:shared` with a canonical serialization + `contentHash` that *is* the v3 file/wire format. Credentials are referenced only (the secret lives in the SecretStore, never in a payload/column). `contentHash`/`updatedAt` are recompute-on-write denormalized caches; write through `ConfigRepository`. Mirror wire-enums live in `:shared`, behaviour-bearing domain enums in `:shared-ai`/`:app`, parity enforced by tests in `:app` (ADR-0030).

## Database Patterns

All database conventions (schema design, migrations, Double-Enum pattern for finite-set columns, denormalized caches) are documented in [`docs/DATABASE-PATTERNS.md`](docs/DATABASE-PATTERNS.md). Read that file before touching Room entities, DAOs, or migrations.

**Key rule — Double-Enum pattern:** Any column holding a value from a finite set (status, origin, type, role, error classifier) MUST be modelled as a Kotlin `enum class` AND a SQL `CHECK` constraint. This forces schema changes to go through migrations and prevents silent data corruption. See `docs/DATABASE-PATTERNS.md` for the full structure, migration workflow, and test template.

## Dependencies (key)

- OpenAI Java SDK (`com.openai:openai-java`), Anthropic Java SDK (`com.anthropic:anthropic-java`)
- Room 2.6.1, Material 3, AndroidX Preference, Emoji2 Picker
- Version catalog in `gradle/libs.versions.toml`
