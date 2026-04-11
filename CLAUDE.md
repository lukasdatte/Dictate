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

### Core Layer (`core/`)
- `DictateInputMethodService.java` — Main IME service (~2100 lines). Handles keyboard UI, recording, orchestration. Uses `ExecutorService` threads for API calls.
- `RecordingManager.kt`, `BluetoothScoManager.kt`, `PromptQueueManager.kt`, `AutoFormattingService.kt` — extracted concerns.

### AI Abstraction Layer (`ai/`)
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

## Database Patterns

All database conventions (schema design, migrations, Double-Enum pattern for finite-set columns, denormalized caches) are documented in [`docs/DATABASE-PATTERNS.md`](docs/DATABASE-PATTERNS.md). Read that file before touching Room entities, DAOs, or migrations.

**Key rule — Double-Enum pattern:** Any column holding a value from a finite set (status, origin, type, role, error classifier) MUST be modelled as a Kotlin `enum class` AND a SQL `CHECK` constraint. This forces schema changes to go through migrations and prevents silent data corruption. See `docs/DATABASE-PATTERNS.md` for the full structure, migration workflow, and test template.

## Dependencies (key)

- OpenAI Java SDK (`com.openai:openai-java`), Anthropic Java SDK (`com.anthropic:anthropic-java`)
- Room 2.6.1, Material 3, AndroidX Preference, Emoji2 Picker
- Version catalog in `gradle/libs.versions.toml`
