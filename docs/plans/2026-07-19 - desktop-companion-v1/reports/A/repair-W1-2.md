# Repair Wave W1-2 — Block A

**Timestamp:** 2026-07-20T00:40:00+02:00
**Cluster:** convention-A-2 (fixed), A-TEST-1 (skipped — deferred by design)

## Findings

### convention-A-2 — FIXED (Nice-to-have, green)

The sibling prompt classes `PromptService` and `SystemPromptResolver` both
declare a companion `create(config: PromptConfig)` factory, but
`PromptService.create` composed the resolver via the constructor directly
(`SystemPromptResolver(config)`), leaving `SystemPromptResolver.create` with
zero call sites — a dead factory and an inconsistent construction answer
within the same file pair.

Applied the preferred fix: `PromptService.create` now calls
`SystemPromptResolver.create(config)` for symmetry, giving the resolver's
companion factory its single call site and making construction consistent
across both classes.

- File: `shared-ai/src/main/kotlin/net/devemperor/dictate/ai/prompt/PromptService.kt:72`
- Behaviour-neutral: `SystemPromptResolver.create(config)` returns
  `SystemPromptResolver(config)`, identical to the prior inline construction.

### A-TEST-1 — SKIPPED (deferred follow-up, not an edit to make now)

The finding explicitly classifies itself as a DEFERRED follow-up: "This is a
DEFERRED follow-up, not an edit to make now." Its suggested fix is
conditional — "When any of these files is next modified inside :shared-ai,
add an in-module unit test." No file in that set is being modified in this
repair wave in a way that changes behaviour, so there is nothing to attach a
new in-module regression test to now. Acting on it here would expand scope
against the finding's own instruction. Left for the next in-module change to
these AI-core files, per the finding.

## Tests

`./gradlew :shared-ai:test` — BUILD SUCCESSFUL (compileKotlin + test green).

## Files modified

- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/shared-ai/src/main/kotlin/net/devemperor/dictate/ai/prompt/PromptService.kt`

## Drift

none
