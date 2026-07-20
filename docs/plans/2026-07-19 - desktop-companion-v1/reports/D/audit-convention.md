# Block D — Convention Audit (re-audit after repair waves W1 + W2)

**Topic:** convention · **Block:** D · **Timestamp:** 2026-07-20T13:30:00+02:00
**Scope base:** `git diff c46cfe8..HEAD -- {BLOCK_FILES}` (file-scoped) · **Grounding:** project `CLAUDE.md`, `knowledge-reference`
**Supersedes:** the initial convention audit (2026-07-20T00:40) — its two Nice-to-have findings (convention-D-1, convention-D-2) were both fixed in repair wave 1 and are re-verified closed below.

## Verdict

**Zero convention findings.** Block D remains unusually consistent, and the two repair waves (W1: the 9
validated findings; W2: plan-and-api-D-2 part b) preserved that consistency — no new same-operation-done-
differently drift was introduced by the repair edits.

### Prior findings — re-verified closed
- **convention-D-1 (mixed `Row`/`Record` DTO suffix) — FIXED.** `grep TranscriptionRow` over
  `companion/src/main` returns nothing; the persist-input DTO family is now uniformly `…Record`
  (`TranscriptionRecord` @ `DesktopSessionRepository.kt:353`, `ConversationTurnRecord` @396,
  `ContinuationTurnRecord` @374), with read results correctly on `…Snapshot`/`…Entry`
  (`ConversationSnapshot` @368, `DesktopHistoryEntry` @343). The sole construction site
  (`DictationEffects.kt:144`) and the `insertTranscription` param were renamed too.
- **convention-D-2 (one FQN `java.util.concurrent` type) — FIXED.** `JobQueue.kt:3` now
  `import java.util.concurrent.ConcurrentHashMap`; use site @34 is `ConcurrentHashMap.newKeySet<String>()`,
  matching the imported treatment of its three siblings.

### Repair-wave additions checked against house convention
- **Module KDoc headers** — every in-scope production Kotlin file added/changed by the waves opens with a
  spec-anchored doc comment (`desktop-host.md §x` / ADR / research ref). Verified across all 21 in-scope
  `pipeline/`, `capture/`, `hotkey/` files; none headerless.
- **`ConfigProfileSource` (W2-1 rewrite)** — keeps the plain-class `ActiveProfileSource` impl shape,
  injected-supplier constructor (`activeProfileId`/`language`/`autoFormatEnabled` as `() -> …` lambdas,
  same style the file already used for `activeProfileId`), a `private companion object { val DEFAULT }`,
  and exhaustive enum mapping via the shared `CompanionConfigWireMapping` seam rather than an open-coded
  `when`. Consistent with the established source pattern.
- **Desktop-history SQLDelight queries (`Companion.sq` §9.3, W1)** — `pageDesktopHistory` /
  `countDesktopHistory` / `desktopHistoryEntry` use named `:param` placeholders, `instr(lower(...))`
  substring search, and `ORDER BY s.created_at DESC, s.id DESC` — a verbatim mirror of the existing
  `pageHistory`/`countHistory`/`selectCursor` conventions (Block-D named-param + section-banner style).
- **`usage` queries (`Companion.sq` §5.4, W1)** — named params, section banner, increment-upsert; the
  repository read methods (`pageDesktopHistory`, `desktopHistoryEntry`) follow the codebase's
  `.executeAsList().map { … }` / `.executeAsOneOrNull()?.let { … }` shape exactly.
- **Error handling** — the two-arm `catch (e: AIProviderException) … catch (e: Exception)` → `UNKNOWN`
  pattern is preserved uniformly across `runPipeline`, `submitRefinementTranscription`,
  `submitContinuation` (`DictationEffects.kt:128/131`, `239/242`, `292/295`) after the repair edits.
- **No logging drift** — grep for `println`/`System.out`/`LoggerFactory`/`.info(`/`.debug(`/`.warn(`/
  `.error(` across all in-scope directories returns nothing: the house "no logging outside the Ktor server
  plugins" convention still holds through both waves.
- **No stubs / TODO / not-implemented markers** in any in-scope directory.

## Findings

None.

## Out-of-scope observations (for the consolidator)

- **[plan-and-api] Two Important plan-fidelity remainders are still open** (not convention): the desktop
  profile-post-processing surface (plan-and-api-D-2 part b — resolved in W2-1) and the §9.3 History-Screen
  UI (plan-and-api-D-3 — data layer landed W1, Compose surface escalated to the main loop as a scope
  decision in W2-2). Convention is not affected; flagged only so the consolidator carries the open scope
  decisions forward. See `re-audit-W1.md` and `repair-W2-2.md`.
- **[out of file-scope] Repair-wave files under `ai/` and `domain/`** (`ProfileBackedAiConfig`,
  `ProfileBackedPromptConfig`, `CompanionConfigWireMapping`, `CredentialSecrets`, `SqlDelightUsageSink`,
  `CompanionSettings` dictation additions) and `CompanionContainer.kt` are outside this block's `BLOCK_FILES`
  glob, so not audited here for convention. Their chunk/repair reports describe them as mirroring the app's
  `ConfigWireMapping`/`ProfilePromptConfig`/`ConfigSecrets` parity conventions; a convention pass on them
  belongs to whichever block owns `ai/`/`domain/` scope (Block C).

## Coverage

**Audited (in-scope, at HEAD + diff `c46cfe8..HEAD`):** `pipeline/` (all 15 files, esp. the W2-changed
`ConfigProfileSource`, and `DictationEffects`/`JobQueue` from W1), `capture/` (all 10 files; W1 touched
`JavaSoundAudioCaptureService`, `PcmAmplitude`, `WavConcat`), `data/` (`DesktopSessionRepository` incl. the
W1 desktop-history read methods + DTO rename, `CompanionConfigRepository`, `CompanionDatabase`,
`SqlDelightHistoryRepository`), `sqldelight/…/Companion.sq` (usage + desktop-history sections),
`hotkey/`, `ui/panel/`, `Main.kt`. Compared against pre-existing house patterns (`pageHistory`/`selectCursor`
queries, `HistoryViewModel`/`SettingsViewModel`, the existing two-arm catch).

**Skipped / light:** generated `databases/3.db`/`4.db` snapshots and `migrations/*.sqm` (schema, not
convention surface — parity/migration auditors own them); Compose pixel-layout files (`RecordingBar.kt`,
`RecordingBarDesign.kt`, `PanelWindow.kt`) skimmed for structure only; test files (`test` topic owns them);
`ai/`, `domain/`, `CompanionContainer.kt`, `ui/config/`, `ui/history/` (outside `BLOCK_FILES`).
