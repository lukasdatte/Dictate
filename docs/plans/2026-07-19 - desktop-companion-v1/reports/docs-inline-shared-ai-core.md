# Inline-Anchor Worker Report — shared-ai-core

**Date:** 2026-07-20T17:25:00+02:00
**SLUG:** shared-ai-core
**Scope:** `shared-ai/src/main/kotlin/net/devemperor/dictate/ai/{port,runner,conversation}/`, `.../secrets/SecretStore.kt`, `.../AIOrchestrator.kt`
**Plan:** `docs/plans/2026-07-19 - desktop-companion-v1/desktop-companion-v1.md`

## Summary

The `:shared-ai` core is already strongly anchored overall (headers, invariants,
gotchas present on the substantive files). The one systematic gap flagged by the
docs-discovery report — *"new ports need `@see ADR-0028`"* — was real: no
`:shared-ai` source file referenced the **promoted** ADR-0028 path, and the
`SecretStore` port still carried the **draft slug** `adr-shared-ai-module` plus
lacked a pointer to its governing ADR-0029 (the app-side impls already have it).
Seven files edited, anchor lines only.

## Anchors added / updated / removed (per file)

| File | Change | Anchor kind |
|---|---|---|
| `port/AiConfig.kt` | **added** `@see docs/decisions/0028-shared-ai-module.md` (above existing spec `@see`) | `@see` ADR |
| `port/AudioDurationReader.kt` | **added** `@see docs/decisions/0028-shared-ai-module.md` | `@see` ADR |
| `port/ProxyConfig.kt` | **added** `@see docs/decisions/0028-shared-ai-module.md` | `@see` ADR |
| `port/UsageSink.kt` | **added** `@see docs/decisions/0028-shared-ai-module.md` | `@see` ADR |
| `port/PromptConfig.kt` | **added** `@see docs/decisions/0028-shared-ai-module.md` | `@see` ADR |
| `AIOrchestrator.kt` | **added** `@see docs/decisions/0028-shared-ai-module.md` (central module class; prefs-free port-based design is the ADR-0028 decision) | `@see` ADR |
| `secrets/SecretStore.kt` | **updated** stale draft slug `ADR adr-shared-ai-module` → `ADR-0028`; **added** `@see docs/decisions/0029-secret-store.md` (governing ADR, matching the app-side impls) | stale-path fix + `@see` ADR |

**Rationale for the ADR-0028 anchors:** the five ports are exactly the ports
ADR-0028 §4 (via `shared-ai-extraktion.md` §4) defines — their existence and
shape follow from the module-boundary decision the code alone doesn't justify.
The existing spec `@see` gives the section-level cut detail; the added ADR `@see`
gives the promoted, load-bearing decision. Per-file anchoring matches the
project convention already visible on the app-side SecretStore impls
(`SecretsMigration.kt`, `AndroidKeystoreSecretStore.kt` each carry their own
`@see ADR-0029`).

## Files inspected, deliberately NOT changed (skips with reasons)

| File(s) | Reason |
|---|---|
| `conversation/*` (all 8) | Already best-anchored in the set — strong headers referencing ADR-0011/0012/0013 inline in prose (the sanctioned `per ADR-NNNN` gotcha form). Paths/numbers resolve; converting the prose refs into `@see` tags would be churn, not clarity. No stale refs. |
| `runner/AnthropicCompletionRunner.kt`, `OpenAICompatibleRunner.kt`, `ElevenLabsTranscriptionRunner.kt` | Moved-from-`:app` files with adequate headers (responsibility / retry / exception-mapping) and ADR-0012/0013 refs at the relevant `converse`/schema methods. No module-boundary anchor needed — they are consumers of the ports, not the boundary itself. |
| `runner/CompletionRunner.kt`, `TranscriptionRunner.kt`, `ConversationRequest.kt`, `ConversationResult.kt`, `StructuredOutputGuards.kt` | Interfaces/DTOs already carry accurate headers with ADR-0012/0013 refs where the shape is decision-driven. No gaps. |
| `runner/CompletionOptions.kt`, `CompletionResult.kt`, `TranscriptionOptions.kt`, `TranscriptionResult.kt` | Trivial data classes — no header warranted (anchor rule: not on trivial files). |

## Self-check

- **Targets resolve:** `docs/decisions/0028-shared-ai-module.md` and
  `0029-secret-store.md` both exist. Existing spec `@see` targets
  (`shared-ai-extraktion.md`, `secretstore.md`) unchanged and still present.
- **No logic touched:** `git diff` confirms every hunk is a comment-only line
  inside a `/** */` block; no code, imports, or formatting changed.
- **No comment noise added:** every added line is a navigational `@see` to a
  load-bearing decision the code cannot self-justify; no code-restating comments
  introduced or found to remove.
- **No lingering draft slugs:** grep for `adr-shared-ai-module` / `adr-secret`
  in scope returns none.

## Notes for final

- The `conversation/*` and `runner/*` files reference ADRs by bare number in
  prose (`(ADR-0012)`) rather than `@see` tags. This is the accepted inline
  `per ADR-NNNN` form and resolves fine; left as-is to avoid churn. If a future
  convention pass wants `@see` uniformity across `:shared-ai`, those are the
  candidates — but it is cosmetic, not a gap.
- No bugs or name-smells observed in the inspected files.
