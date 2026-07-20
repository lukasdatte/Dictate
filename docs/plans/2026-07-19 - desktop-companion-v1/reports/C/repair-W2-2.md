# Repair Report — W2-2 (doc-drift-androidaiconfig-retired)

**Timestamp:** 2026-07-20T00:40:00+02:00
**Agent:** repair-fix (Block C, wave W2-2)

## Finding

`doc-drift-androidaiconfig-retired` (green / Nice-to-have) — documentation drift.
The wave deleted `AndroidAiConfig` from `src/main` (moved to `src/test`) but left two
main-source KDoc comments asserting it still lives in main and is the migration's
parameter mirror / still-built adapter. Both statements were false after the C3 flip:
the non-secret parameter mirror is now `PrefCompletionParameters` (main), and
`AndroidAiFactory` now builds `ProfileResolver`. The `[AndroidAiConfig]` KDoc links
from these main files also resolved out into test sources (dangling Dokka references).
Not a build break (KDoc is not compiled) — documentation only.

## Fixes applied

**`app/src/main/java/net/devemperor/dictate/ai/adapter/AndroidAiFactory.kt`** (KDoc, ~line 18-21)
- Rewrote the trailing sentence of the C3-flip paragraph: the migration's non-secret
  parameter mirror now points at `[PrefCompletionParameters]` (a valid main-source link);
  the retired pref-based `AndroidAiConfig` is described as surviving only in test sources as
  the characterization baseline. Demoted the dangling `[AndroidAiConfig]` link to a code span.

**`app/src/main/java/net/devemperor/dictate/ai/adapter/ProfileResolver.kt`** (KDoc)
- Line 21: demoted the dangling `[AndroidAiConfig]` link (class is test-only now) to a
  `` `AndroidAiConfig` `` code span; text ("entity-model successor to the pref-based
  AndroidAiConfig") stays accurate.
- Section header + body (~line 36-39): retitled `## Not yet the live read path` →
  `## Live read path (C3)` and corrected the body — `AndroidAiFactory` builds this
  resolver (flip landed in C3, reads+writes moved atomically), `AndroidAiConfig` was
  retired to test sources as the characterization baseline. The previous false claim
  ("AndroidAiFactory still builds AndroidAiConfig") is removed.

Verified against source of truth: `find` confirms `AndroidAiConfig.kt` lives only under
`app/src/test/...` and `PrefCompletionParameters.kt` only under `app/src/main/...`; the
latter's own KDoc confirms it is the verbatim-extracted parameter reader kept in main.

## Verification

- Comment-only change (KDoc is not compiled). `./gradlew :app:compileDebugKotlin` → OK,
  confirming file integrity (well-formed comment delimiters, no stray tokens).
- No behavioural change, so no test delta possible; the finding itself notes the build and
  affected unit suites were already green.

## Skipped

None.

## Files modified

- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/app/src/main/java/net/devemperor/dictate/ai/adapter/AndroidAiFactory.kt`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/app/src/main/java/net/devemperor/dictate/ai/adapter/ProfileResolver.kt`

## Drift

none
