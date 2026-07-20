# Block D — Audit: `plan-and-api` (re-run at HEAD, post repair-waves W1+W2)

**Topic:** plan-and-api (plan fidelity · stubs/placeholders · cross-chunk API-consumer match)
**Block:** D (D1a/D1b/D2/D3) · **Spec:** `research/desktop-host.md` · **Plan:** `desktop-companion-v1.md` §5 Block D
**Verify base:** c46cfe8 · **HEAD:** 9c19a1b (last D commit `3b9f980` [D] repair wave 2) · **Timestamp:** 2026-07-20T13:30:00+02:00
**Grounding:** knowledge-reference (TS-centric; general port/DI + dangling-API patterns only — project is Kotlin/Compose-Desktop)

## Summary

This is a fresh plan-and-api pass over the current tree, which already carries repair waves W1
(`b99b141`) and W2 (`3b9f980`). The two Important plan-fidelity gaps the initial audit raised on the
AI path are now **resolved and re-verified at HEAD**:

- **Usage (was plan-and-api-D-1) — CLOSED.** `usage` table present in `Companion.sq` (§5.4), folded into
  `3.sqm`/`4.db`; `SqlDelightUsageSink` implements the `:shared-ai` `UsageSink` port (increment-upsert
  mirroring Room's `RoomUsageSink`) and is wired at `CompanionContainer.production()` in place of
  `NoopUsageSink`. Desktop AI usage is now persisted, not discarded.
- **Profile→AiConfig (was plan-and-api-D-2) — CLOSED (both halves).** `ProfileBackedAiConfig`
  (provider/model/key/baseUrl/params from the active Block-C profile + SecretStore credential) replaces
  the empty-key `CompanionAiConfig` at `production()`; `ConfigProfileSource.current()` now resolves the
  full post-processing surface (`ambiguityMode` + auto-apply `instructions` + `stylePrompt` via the
  shared `PromptService`, plus device-pref `language`/`autoFormatEnabled`), not just `ambiguityMode`.
  The consumer chain compiles clean (`:companion:compileKotlin` green) and its signatures match the
  container wiring.

The core pipeline (pure `DictationReducer` ↔ `DictationEffects` ↔ `DesktopDictationController`, spec §5),
the SQLDelight Room-parity schema + `received_texts` ablation (§3), the capture layer (§4), the
re-dictate/`ConversationContinuation` flow (§8.3) and the verbatim shared-`ReviewDecision` verdict path
(§8.2) remain faithful and API-consistent across chunks. The §9.2 management UI (profiles/providers/
models/prompts CRUD, `ui/config/ManagementScreen` + `ConfigViewModel`) is delivered and reachable via
the `App.kt` navigation rail. No throw-not-implemented stubs; the only "placeholder" hit is the WAV
back-patch header (`WavWriter`, legitimate).

**One Important finding survives:** §9.3 desktop-history UI is still unbuilt — its read API landed in W1
but has **zero UI consumers**, so persisted desktop dictations remain unreachable. This was escalated to
the main loop by W2 (cluster repair-W2-2) as a scope decision and is re-confirmed here, still open.

## Findings

### plan-and-api-D-3 (Important) — §9.3 desktop-history UI unbuilt; the desktop-history read API is fully wired but has no UI consumer, so persisted desktop dictations are unreachable

- **What / where:** `companion/.../data/DesktopSessionRepository.kt:296-343`
  (`pageDesktopHistory` / `countDesktopHistory` / `desktopHistoryEntry` + `DesktopHistoryEntry` model),
  `companion/.../db/Companion.sq` (matching `pageDesktopHistory`/`countDesktopHistory`/`desktopHistoryEntry`
  queries, scope `host_origin='DESKTOP_DICTATION' AND status='COMPLETED' AND origin!='REVIEW_REFINEMENT'`),
  `companion/.../ui/history/HistoryScreen.kt`, `companion/.../ui/history/HistoryViewModel.kt`
  (both still phone-sync-only: built entirely around `ReceivedText` + `DispatchService.reinsert`).
- **Why it matters:** W1 delivered the §9.3 **data layer** (read queries + repository methods + model +
  `DesktopHistoryTest`) and `production()` wires `container.desktopSessions` (`CompanionContainer.kt:206`),
  but no UI calls any of it — `grep` over `companion/.../ui` finds **zero** consumers of
  `pageDesktopHistory`/`countDesktopHistory`/`DesktopHistoryEntry`/`desktopSessions`. `HistoryScreen`
  renders only phone-sync `ReceivedText`; there is no Phone/Desktop filter, no transcript-vs-final-output
  detail, and no desktop re-insert. Desktop re-insert **cannot** reuse `DispatchService.reinsert` (that
  resolves a phone `ReceivedText` + writes `dispatch_state`, which desktop rows never have) — it must go
  through `container.inserter` (`TextInserter`). Net effect against Block-D's stated goal ("Diktat →
  Insert/Review", plan §5): a user dictates on the desktop, the session is persisted, and it is then
  **invisible and non-re-insertable in every UI**. §9.3 is explicitly named D3 scope (plan §5 Block D D3:
  "Verwaltungs-/History-UI §9 … History §9.3"). A read API with no consumer is also a dangling
  cross-chunk contract — exactly the kind of API-consumer mismatch this topic guards.
- **Status:** already escalated to the main loop by W2 (`repair-W2-2.md`) as a build-now-vs-follow-up-chunk
  scope decision; **no code changed since**, so the gap is unchanged at HEAD. Re-reported so the open item
  stays visible in the current audit, not to re-litigate a resolved point.
- **Expected:** a `HistoryScreen` section (or filter) that lists `host_origin='DESKTOP_DICTATION'` sessions
  via the existing read API, shows transcript vs `final_output_text`, and re-inserts through
  `container.inserter` / `TextInserter`, per §9.3.
- **Suggested fix:** build a desktop-history view-model over `DesktopSessionRepository.pageDesktopHistory`/
  `desktopHistoryEntry` + a `HistoryScreen` section with a Phone/Desktop filter and a `TextInserter`
  re-insert path; handle `container.desktopSessions` being nullable in the `forTest` graph. Design is
  spec-prescribed (§9.3); a fresh Compose surface + VM test suite — the main-loop's open decision is
  whether to build it in Block-D repair now or as a dedicated follow-up chunk (needs a plan decision, not
  a silent inline fix).

## Out-of-scope observations (for the consolidator)

- **§9.1 panel-top profile dropdown absent (D3-3, Nice-to-have — documented-delegated).** The active
  profile is selectable in `ManagementScreen` and drives the pipeline via `ConfigProfileSource`; the
  convenience dropdown on the mini-panel (`ui/panel/PanelWindow.kt`) is not built. Defensible v1 deviation,
  documented in D3-impl issue D3-3 — not elevated.
- **§9.2 management editing depth is shallow (D3-4, Nice-to-have — documented-delegated).**
  Create/duplicate/delete/set-active + basic model/prompt create are present; deep pickers
  (profile→model/prompt-order editor, model-parameter UI, network model-list fetch) are deferred with the
  VM named as the E3 extension seam. Documented deviation — not elevated.
- **Anticipated BLOCK_FILES paths do not exist (structural deviation, functionally delivered).** The block
  scope lists `ui/profiles/**`, `ui/models/**`, `ui/prompts/**` and `pipeline/review/**`; none exist on
  disk. The profile/model/prompt UI was consolidated into a single `ui/config/ManagementScreen.kt` +
  `ConfigViewModel.kt` (§9.2), and the review flow lives in the `pipeline/` reducer/effects rather than a
  `pipeline/review/` subpackage. This is a layout deviation from the scope's anticipated file tree, but the
  §8/§9 functionality is present and coherent — noted for the trail, not a fidelity gap.
- **Transitional system prompt (documented, §8.2/ADR-0012).** `postProcess` persists the fixed
  `SYSTEM_PROMPT_CONVERSATION` as the SYSTEM row; `systemPromptMode` deliberately does not wire into the
  turn (F9), so `DictationProfile` carries no system-prompt field. This is an intentional, documented
  ADR-0012 boundary post-W2, no longer a symptom of unfinished profile resolution — not a finding.

## Coverage

**Audited (read at HEAD + `git diff c46cfe8..HEAD` over BLOCK_FILES):** `Companion.sq` (incl. new `usage`
table + `pageDesktopHistory`/`countDesktopHistory`/`desktopHistoryEntry`), `migrations/2.sqm`/`3.sqm`,
`DesktopSessionRepository.kt`, `SqlDelightHistoryRepository.kt`, `CompanionConfigRepository.kt`,
`CompanionDatabase.kt`, all `pipeline/*` (`DictationReducer`, `DictationEffects`, `DictationIntent`,
`DesktopDictationController`, `DesktopUiState`, `ActiveProfileSource`, `ConfigProfileSource`, `Effect`,
`JobQueue`, `PanelControl`), the `capture/*` layer, `hotkey/*`, `ui/panel/*`, `Main.kt`. Cross-boundary
consumers verified: `CompanionContainer.kt` (production wiring of `SqlDelightUsageSink`,
`ProfileBackedAiConfig`, `ConfigProfileSource`, `desktopSessions`, `FocusRestoringTextInserter`),
`ai/SqlDelightUsageSink.kt` / `ai/ProfileBackedAiConfig.kt` / `ai/ProfileBackedPromptConfig.kt`,
`ui/config/ManagementScreen.kt`, `ui/history/HistoryScreen.kt` + `HistoryViewModel.kt`, `ui/App.kt`
navigation. Cross-checked against spec §2/§3/§5/§6/§8/§9 and the `:shared-ai` API surface
(`AIOrchestrator`, `UsageSink`, `ReviewDecision`, `PromptService`, `ConversationTurnBuilder`).
`:companion:compileKotlin` runs clean (all cross-chunk signatures resolve).

**Skipped (reason):** Compose rendering internals of `PanelWindow`/`RecordingBar`/`RecordingBarDesign`
and the Win32/JNA platform adapters (hotkey/panel-control) — UI/logic-topic + platform-manual-acceptance
surface, noted only where a port crosses a chunk boundary (all matched). Full test execution belongs to
the `test` topic; W1/W2 verified `:companion:test` green at `3b9f980`.
