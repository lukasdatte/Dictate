# Repair wave C / W1-5 — report

**Timestamp:** 2026-07-20T00:40:00+02:00
**Cluster:** logic-C-2 (green, Nice-to-have)

## Finding logic-C-2 — `setTranscriptionKeyterms` silent no-op

**File:** `app/src/main/java/net/devemperor/dictate/config/ActiveProfile.kt` (lines 105-115)

**What was wrong:** `setTranscriptionKeyterms` early-returned as a silent
no-op (`transcriptionModelRef(sp, db) ?: return`) when the active profile has
no transcription `ModelRef`. The pref-based predecessor persisted keyterms
unconditionally, so keyterms had nowhere to go and were dropped without any
signal — an unobservable, untestable behaviour regression (ElevenLabs-only,
low impact).

**What I did:** Changed the return type from `Unit` to `Boolean` — `false`
on the no-op branch, `true` after the value is persisted — and expanded the
KDoc to document the contract and the invariant. This turns the silent
early-return into an explicit, testable outcome without introducing dead code.

**Why not a toast (the suggested alternative):** The sole caller,
`SystemPromptsActivity.setupKeyterms()`, already guards this path:
`updateKeytermsEnabled()` disables the keyterms `EditText` unless an ElevenLabs
`scribe_v2` transcription model ref is active, so the `afterTextChanged`
listener that calls `setTranscriptionKeyterms` cannot fire in the no-op state.
A toast ("select a transcription model first") would therefore be unreachable
dead code, violating the clean-code baseline. The `false` branch is now a
documented defensive contract instead. The heavier auto-create-ModelRef
alternative was likewise not warranted for a Nice-to-have, ElevenLabs-only edge.
The Java caller ignores the new `Boolean` return (compiles fine); the return is
available for any future caller lacking the field-disable guard.

## Skipped findings

None.

## New issues discovered

None.

## Tests

- `./gradlew :app:testDebugUnitTest --tests net.devemperor.dictate.config.ConfigEntityMigrationTest --tests net.devemperor.dictate.config.CatalogImportExportTest --rerun-tasks` → **BUILD SUCCESSFUL** (config-area tests green, clean recompile including my change).
- A prior full `:app:testDebugUnitTest` run reported 12 `initializationError`/`ClassNotFoundException` failures — all in the `net.devemperor.dictate.windows.*` / `state.WindowsDispatch*` test classes (the test classes themselves failed to load). These are **outside my `config` cluster**, do not appear in the working-tree diff, and were caused by concurrent Kotlin daemon corruption from parallel fixer agents sharing this worktree's `build/` dir (recurring "Detected multiple Kotlin daemon sessions" warning). A clean isolated recompile of the same sources succeeds with only warnings, confirming the failures are environmental, not a regression from this change.

## Files modified

- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/app/src/main/java/net/devemperor/dictate/config/ActiveProfile.kt`

## Drift (files outside assigned scope)

None.
