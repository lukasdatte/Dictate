# Doc Worker Report — root-claude-md

**Slug:** `root-claude-md`
**Action:** update (verification pass)
**Target doc:** `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/CLAUDE.md`
**Date:** 2026-07-20T17:25:00+02:00
**Plan:** `docs/plans/2026-07-19 - desktop-companion-v1/desktop-companion-v1.md`
**Range:** `c46cfe8..HEAD`

## Outcome: no-change-needed

CLAUDE.md was refreshed in Block F (commit `a6320ee`) and is accurate against the
final shipped code. This was scoped by the discovery report as a light
verification pass; every flagged claim verifies. No edit warranted.

## Claims verified against source

| CLAUDE.md claim | Source verified | Verdict |
|---|---|---|
| Four-module topology `:app` / `:shared` / `:shared-ai` / `:companion` (§Module Topology, L27–44) | `settings.gradle` L24–34 includes all four; `:shared-ai` comment matches (pure `kotlin("jvm")`, jvmTarget 1.8, consumed by `:app`+`:companion`, AI SDKs allowed) | Current |
| `:shared-ai` package `net.devemperor.dictate.ai`, jvmTarget 1.8, allows AI SDKs+OkHttp, forbids Android/Ktor/coroutines (L36–39, 46) | `shared-ai/build.gradle` sets JVM_1_8 + `api openai.java`/`anthropic.java`/`impl okhttp`; `AiConfig.kt` package is `net.devemperor.dictate.ai.port` | Current |
| SecretStore convention: port at `net.devemperor.dictate.ai.secrets` in `:shared-ai`; `get` distinguishes `null` (no secret) from `DecryptionFailed`; Android Keystore AES-GCM (ADR-0029) (L84) | `AndroidKeystoreSecretStore.kt` imports `net.devemperor.dictate.ai.secrets.{SecretRef,SecretStore,SecretStoreException}`; `get()` returns `null` on absent blob, throws `DecryptionFailed` on KEK loss / decode / tag mismatch; AES-256-GCM; references `FileAesGcmSecretStore` (POSIX fallback) | Current |
| Config-entity convention: entities in `:shared`, canonical serialization + `contentHash` recompute-on-write, write through `ConfigRepository`, `updatedAt` monotone, profile+prompts atomic (L85, ADR-0030) | `ConfigRepository.kt` imports `ProviderConfigEntity`/`ApiCredentialEntity`/`ModelRefEntity`/`ProfileEntity`/`contentHash` from `net.devemperor.dictate.shared.config`; every upsert stamps `contentHash(...)` + `clock()`; `upsertProfile` runs profile + `profile_prompts` in one `runInTransaction` | Current |

## Deviations

None.

## Issues

None.

## Files modified

None.

## Files outside assigned scope (drift)

None.

## Notes for final

- **Database section (CLAUDE.md L68–70) is a deliberately terse high-level
  pointer, not an exhaustive entity list.** The live Room DB
  (`DictateDatabase.kt`) is now `version = 13` with 15 entities — this plan added
  the config-entity tables (`ProviderConfig`/`ApiCredential`/`ModelRef`/`Profile`/
  `ProfilePrompt` Room entities) and the peer-catalog tables (`Peer`,
  `Subscription`). The section already omitted the 6 pipeline entities
  (`Session`/`Transcription`/`ProcessingStep`/`CompletionLog`/`TextInsertion`/
  `ConversationMessage`) added by an earlier plan, so its brevity is a
  pre-existing authorial convention. Block F intentionally routed the
  config-entity model into a Key Conventions bullet (L85) + `docs/DATABASE-PATTERNS.md`
  rather than expanding this section — I honored that choice and did not expand it.
  Flagging for the final agent only in case a cross-doc consistency pass wants to
  confirm CLAUDE.md's Database section and the `database-patterns` worker's output
  don't contradict (they should not — DATABASE-PATTERNS.md is the schema SSoT).
- No cross-doc link breakage: CLAUDE.md's only doc link
  (`docs/DATABASE-PATTERNS.md`) is unchanged and owned by a sibling worker.
