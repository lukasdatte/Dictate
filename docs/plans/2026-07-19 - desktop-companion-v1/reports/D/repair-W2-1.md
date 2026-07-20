# Repair W2-1 — `plan-and-api-D-2` Part B (desktop profile post-processing surface)

**Date:** 2026-07-20T00:40:00+02:00
**Agent-ID:** repair-fix (desktop-aiconfig-credential-resolution — Part B)
**Finding:** `plan-and-api-D-2` [Important, PARTIALLY RESOLVED by wave 1]

## Summary

Wave 1 closed the empty-key credential core (`ProfileBackedAiConfig` + container wiring + wire
mapper). This wave closes the finding's remaining half: `ConfigProfileSource.current()` no longer
returns `DEFAULT.copy(ambiguityMode = …)` but resolves the full post-processing surface of a take,
per research part b (F6-F11 make the plan decision the finding said it needed — no `AskUserQuestion`
required, the research authorises this as an in-scope Block-D repair):

- **Profile content** (from the active `ProfileEntity`): `ambiguityMode` (unchanged), `instructions`
  (auto-apply prompts in order → `TurnInstruction`), `stylePrompt` (via the shared `PromptService`).
- **Device prefs** (from `CompanionSettings`, NOT profile schema — F10): `language`, `autoFormatEnabled`.
- **Untouched (F9):** the conversation turn's fixed `SYSTEM_PROMPT_CONVERSATION` stays verbatim
  (ADR-0012); `systemPromptMode` deliberately does not wire into the turn. No schema change.

## Per-finding

### `plan-and-api-D-2` — FIXED (Part B)

1. **`CompanionConfigWireMapping.toPromptMode()`** added (`PromptSelectionMode → PromptMode` by name,
   NONE fallback), mirroring app `ConfigWireMapping` :44-45. Header updated to name the third enum pair.
2. **`ProfileBackedPromptConfig`** (new) — `PromptConfig` over a resolved `ProfileEntity`, the desktop
   twin of app `ProfilePromptConfig`; feeds the shared `PromptService.resolveWhisperStylePrompt` so the
   NONE/PREDEFINED/CUSTOM + language-aware fallback logic stays the single `:shared-ai` source of truth
   (F8) rather than an open-coded `when`.
3. **`CompanionSettings`** — two device-local settings `language: String?` (blank→null=auto-detect) and
   `autoFormatEnabled: Boolean` (default false), self-healing like every sibling, with a doc note on why
   they are device prefs not profile columns (F10). Keys `dictation.language`, `dictation.autoFormatEnabled`.
4. **`ConfigProfileSource`** — constructor gains `language: () -> String?` and
   `autoFormatEnabled: () -> Boolean`; `current()` resolves all five `DictationProfile` fields, dropping a
   `promptRef` whose prompt row is missing, keeping the no-profile branch at the plain `DEFAULT` (F11).
   Docstring rewritten (the stale "rest stays transitional" NOTE is gone).
5. **`CompanionContainer.production()`** — wires the two device-pref suppliers
   (`settings::language`, `settings::autoFormatEnabled`); F20 comment updated.

## Tests (all green — `./gradlew test` BUILD SUCCESSFUL)

- `CompanionConfigWireEnumParityTest` — extended with `PromptSelectionMode`↔`PromptMode` name parity
  (both directions).
- `ConfigProfileSourceTest` (new) — no-profile/missing-row → DEFAULT; auto-apply-only instruction
  resolution in order with `requiresSelection` provenance; missing prompt row dropped;
  PREDEFINED/CUSTOM/NONE style prompt (language-aware); language/auto-format from injected suppliers;
  ambiguity from profile.
- `CompanionSettingsDictationTest` — `language`/`autoFormatEnabled` round-trip + self-heal.
- `DesktopDictationPipelineTest` — new `profiledTake_resolvesTheAutoApplyInstructionIntoThePersistedUserMessage`
  drives the REAL `ConfigProfileSource` over a seeded profile and asserts the resolved instruction reaches
  the persisted USER message + language reaches the session row (regression guard the finding's failure
  cannot recur on the post-processing axis). `controller()` refactored to also accept an
  `ActiveProfileSource`.

## Deviations

| Deviation | Plan location | What changed | Why | Impact | Resolved? |
|---|---|---|---|---|---|
| Empty-transcript skip for `requiresSelection` slots omitted | research part b hint 1 | Auto-apply instructions always included | `current()` runs before transcription (§8.1 snapshot) — no transcript to test; option (a), documented in the class KDoc | Degenerate `requiresSelection`-on-empty case only; model tolerates it | Yes (documented) |

## Skipped / follow-ups (no scope expansion)

- **No desktop UI** exposes `language`/`autoFormatEnabled` yet — the *resolution* is correct now
  regardless of when the settings UI lands (same shape as Part-A F5 credential-entry gap). Not this
  repair's scope.

## Files modified

- `companion/src/main/kotlin/net/devemperor/dictate/companion/ai/CompanionConfigWireMapping.kt`
- `companion/src/main/kotlin/net/devemperor/dictate/companion/ai/ProfileBackedPromptConfig.kt` (new)
- `companion/src/main/kotlin/net/devemperor/dictate/companion/domain/CompanionSettings.kt`
- `companion/src/main/kotlin/net/devemperor/dictate/companion/pipeline/ConfigProfileSource.kt`
- `companion/src/main/kotlin/net/devemperor/dictate/companion/CompanionContainer.kt`
- `companion/src/test/kotlin/net/devemperor/dictate/companion/ai/CompanionConfigWireEnumParityTest.kt`
- `companion/src/test/kotlin/net/devemperor/dictate/companion/domain/CompanionSettingsDictationTest.kt`
- `companion/src/test/kotlin/net/devemperor/dictate/companion/pipeline/ConfigProfileSourceTest.kt` (new)
- `companion/src/test/kotlin/net/devemperor/dictate/companion/pipeline/DesktopDictationPipelineTest.kt`

## Drift

none — every edit is within the finding's named files or their direct test siblings. (The
`ui/panel/PanelWindow.kt`, `shared/.../CanonicalJsonTest.kt`, and untracked
`DesktopSessionRepositoryTest.kt` changes in the worktree belong to the parallel fixer repair-W2-2;
disjoint file set, not staged by this cluster.)
