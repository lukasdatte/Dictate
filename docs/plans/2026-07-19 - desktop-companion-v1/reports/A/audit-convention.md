# Block A — Convention Audit

**Topic:** `convention` (same operation done differently across chunks: logging,
error handling, naming, file layout, imports)
**Block:** A · **Date:** 2026-07-20T00:40:00+02:00
**Scope commit range:** `c46cfe8..HEAD`, file-scoped to BLOCK_FILES
**Grounding:** project `CLAUDE.md`, user language convention (English code
comments), `knowledge-reference` (excel_ekl/scraper — not directly applicable to
this Android/Kotlin block, so grounding is the project CLAUDE.md + engineering
baseline).

## Summary

Block A convention consistency is **high**. Chunk A2 is byte-identical `git mv`
moves (no new code, nothing to diverge). The genuinely new surface is Chunk A3:
5 ports (`ai/port/`), 6 Android adapters (`ai/adapter/`), the `AndroidAiFactory`
wiring point, the two build files, and the port-wiring edits to the migrated
runners/orchestrator/factory. Across that surface:

- **Naming** — uniform and predictable: ports are role-named (`AiConfig`,
  `ProxyConfig`, `UsageSink`, `AudioDurationReader`, `PromptConfig`); adapters are
  `Android*`/`Room*`/`SharedPrefs*`/`MediaMetadata*` + the port name. Consistent.
- **File layout** — ports all under `shared-ai/.../ai/port/`, adapters all under
  `app/.../ai/adapter/`. Consistent.
- **Imports** — explicit, no wildcard imports anywhere in the new files.
- **Error handling** — the moved `AIOrchestrator` re-wraps `AIProviderException`
  identically in all three methods (transcribe/complete/converse); the ElevenLabs
  runner's `wrapProviderCall` status mapping is a single coherent block. No
  divergence introduced.
- **DI style** — every runner takes `proxy: ProxyConfig` and applies it the same
  way per transport (SDK runners → `proxy.applyTo(builder)`; raw-okhttp ElevenLabs
  → `proxy.rawProxy()` + `installAuthenticator()`). This is the designed port
  split, not a divergence.
- **JSON** — `org.json` fully retired from `:shared-ai`; every parse site
  (`ElevenLabsKeytermsParser`, `ElevenLabsTranscriptionRunner` success + error
  paths) now uses kotlinx-serialization uniformly, and `SharedAiPurityTest` bans
  `org.json` so the convention is enforced, not just followed.
- **Build files** — `settings.gradle` / `app/build.gradle` / `shared-ai/build.gradle`
  all carry consistent, well-reasoned block comments pointing at ADR-0015 /
  `adr-shared-ai-module`.
- **Inline anchors** — the `PipelineOrchestrator.kt` module-boundary KDoc uses the
  standard `@see` inline-anchor convention correctly.

Two low-severity convention inconsistencies survived, both Nice-to-have. No
Critical/Important convention findings.

## Findings

### convention-A-1 — Spec-reference style split within the new AI package (Nice-to-have)

**Files:**
- `app/src/main/java/net/devemperor/dictate/ai/adapter/AndroidAiConfig.kt:19` (uses `@see`)
- `app/src/main/java/net/devemperor/dictate/ai/adapter/AndroidPromptConfig.kt:8-13` (inline `(spec §6 A3.5)`)
- `app/src/main/java/net/devemperor/dictate/ai/adapter/SharedPrefsProxyConfig.kt:12-15` (inline `(spec §4.3)`)
- `app/src/main/java/net/devemperor/dictate/ai/adapter/RoomUsageSink.kt:6-9` (inline `(spec §4.2)`)
- `app/src/main/java/net/devemperor/dictate/ai/adapter/MediaMetadataAudioDurationReader.kt:6-9` (inline `(spec §4.4)`)
- `app/src/main/java/net/devemperor/dictate/ai/adapter/AndroidAiFactory.kt:6-11` (inline `(spec §4.5)`)

**What's wrong / why it matters:** The same operation — pointing a class's KDoc at
the spec section it implements — is done two different ways inside the newly
created `ai/adapter/` package. `AndroidAiConfig` uses the full
`@see docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md §…`
tag, which is exactly the convention every one of the 5 ports and every migrated
`:shared-ai` class (`AIOrchestrator`, `RunnerFactory`, `PromptService`,
`SystemPromptResolver`) uses. The other five adapters reference the spec inline as
`(spec §4.x)` with no `@see` tag. This is the per-block-convention drift the
`knowledge-doc-format` `@see`-anchor convention exists to prevent: the inline
`(spec §x)` form is not a resolvable/greppable anchor and doesn't match the
established block style.

**Expected instead:** All six adapters use the `@see docs/plans/… §x` tag, matching
`AndroidAiConfig` and the ports.

**Suggested fix:** Add an `@see docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md §4.x`
line to the five inline-only adapters (and optionally keep the human-readable
`(spec §x)` prose in the body). Mechanical, comment-only.

### convention-A-2 — Sibling `create()` factory bypassed / dead (Nice-to-have)

**Files:**
- `shared-ai/src/main/kotlin/net/devemperor/dictate/ai/prompt/PromptService.kt:72`
- `shared-ai/src/main/kotlin/net/devemperor/dictate/ai/prompt/SystemPromptResolver.kt:31-34`

**What's wrong / why it matters:** Both sibling prompt classes expose a
`companion object { @JvmStatic fun create(config: PromptConfig) }` factory — the
same convention applied to both. But the one composition path that could use
`SystemPromptResolver.create(config)` — inside `PromptService.create` — calls the
constructor directly: `PromptService(config, SystemPromptResolver(config))`. As a
result `SystemPromptResolver.create` has **zero call sites** (grep-confirmed across
`:shared-ai` + `:app`). The convention is declared on both classes but honoured on
only one, leaving a dead factory and an inconsistent "how do we construct a
resolver" answer within the same file pair.

**Expected instead:** Either compose via the sibling factory
(`SystemPromptResolver.create(config)` inside `PromptService.create`) so the
`create()` convention is used symmetrically, or drop the unused
`SystemPromptResolver.create` so only the constructor is the public entry.

**Suggested fix:** Preferred — call `SystemPromptResolver.create(config)` in
`PromptService.create` for symmetry; alternatively delete the dead companion.

## Out-of-scope observations (for the consolidator)

- **German code comments in a now-shared module (language convention).**
  `PromptService.kt` (`// Kontext 1: Whisper Style Prompt`, `Kein XML-Builder
  noetig`, `Ergebnis: … uebergebbar`, `User waehlt Prompt aus Liste`) and
  `PromptTemplates.kt` retain German comments after moving into `:shared-ai`. The
  user language convention is English code comments. These comments are
  **pre-existing and were carried verbatim** by the A2 move (not introduced by
  Block A), so I do not raise them as a block-A convention finding — but they are
  now in a cross-module shared component, so a translation pass is worth
  considering. (Category: likely a docs/quality item, not correctness.)
- **Two `new SharedPrefsProxyConfig(sp)` constructions** at
  `APISettingsActivity.java:274,419` — minor duplication (a fresh adapter per call
  site), but both call sites do it identically, so it is consistent, not drift.
  Belongs to a `logic`/DRY lens if anywhere; noted for completeness.

## Coverage note

**Audited (HEAD):**
- Build/config: `settings.gradle`, `app/build.gradle`, `shared-ai/build.gradle`.
- Ports (5): `AiConfig`, `PromptConfig`, `ProxyConfig`, `UsageSink`,
  `AudioDurationReader`.
- Adapters (6): `AndroidAiConfig`, `AndroidPromptConfig`, `SharedPrefsProxyConfig`,
  `RoomUsageSink`, `MediaMetadataAudioDurationReader`, `AndroidAiFactory`.
- Port-wired core: `AIOrchestrator`, `RunnerFactory`, `OpenAICompatibleRunner`
  (constructor + proxy), `AnthropicCompletionRunner` (constructor + proxy),
  `ElevenLabsTranscriptionRunner` (full), `PromptService`, `SystemPromptResolver`,
  `ModelFetcher` (signature).
- Integration edits: `PipelineOrchestrator.kt` (+28 KDoc anchor),
  `DictatePipelineService.kt` (wiring lines), `APISettingsActivity.java` (2 call
  sites).
- JSON convention grep across `shared-ai/src/main` (org.json fully retired,
  kotlinx everywhere; purity-test-enforced).

**Skipped / light-touch (with reason):**
- The 36 A2 pure-move files were **not** re-read line-by-line for convention: A2 is
  byte-identical to HEAD source (100% similarity, confirmed in A2-impl.md and the
  diff stat showing them as adds on the `:shared-ai` side of renames). Moved code
  cannot introduce cross-chunk convention drift; only the A3 edits on top can, and
  those were audited.
- Characterization/parity tests (`AiConfigParityTest`, `ProxyConfigParityTest`,
  etc.) — belong to the `test` topic; not audited here.
- Logic/edge-case correctness of the ElevenLabs error mapping and
  `completionParameters` sentinel filtering — belong to the `logic` topic.
