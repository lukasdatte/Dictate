# Block D — Audit Consolidation (MODE = initial, re-run at HEAD post W1+W2)

**Block:** D (D1a schema/sync · D1b capture/pipeline · D2 hotkey/panel/insert · D3 review/config/entities)
**Timestamp:** 2026-07-20T13:30:00+02:00
**Inputs:** `audit-plan-and-api.md`, `audit-convention.md`, `audit-logic.md`, `audit-test.md` (all re-run at HEAD after repair waves W1 `b99b141` + W2 `3b9f980`)
**Verify base:** c46cfe8..HEAD · `:companion:test` (370 tests, 3 documented skips, 0 failures) + `verifySqlDelightMigration` green (per audit-test)

> This supersedes the 2026-07-20T00:40 initial consolidation. The nine findings from that pass are all resolved and re-verified closed by the audits at HEAD (logic-D-1 short-take, plan-and-api-D-1 usage sink, plan-and-api-D-2 Profile→AiConfig, convention-D-1/-2, logic-D-2/-3, T1). This pass consolidates the two raw findings that remain open at HEAD.

## Verdict

2 raw findings, **both validated as real**, 0 eliminated, 0 merged (distinct underlying issues in distinct files). One Important plan-fidelity gap (yellow — carries an open main-loop scope decision) and one Nice-to-have latent invariant gap (green — clear small fix).

| ID | Sev | Class | Research topic |
|---|---|---|---|
| plan-and-api-D-3 | Important | yellow | `desktop-history-ui-scope` |
| logic-D-4 | Nice-to-have | green | — |

## Validated findings (full detail)

### plan-and-api-D-3 (Important, yellow — `desktop-history-ui-scope`) — §9.3 desktop-history UI unbuilt; the read API is fully wired but has zero UI consumers, so persisted desktop dictations are unreachable

**Files:** `companion/.../data/DesktopSessionRepository.kt:296-343` (`pageDesktopHistory`/`countDesktopHistory`/`desktopHistoryEntry` + `DesktopHistoryEntry`), `companion/.../db/Companion.sq` (matching queries, scope `host_origin='DESKTOP_DICTATION' AND status='COMPLETED' AND origin!='REVIEW_REFINEMENT'`), `companion/.../ui/history/HistoryScreen.kt`, `companion/.../ui/history/HistoryViewModel.kt`

**Verified at HEAD:**
- Read layer exists on `DesktopSessionRepository.kt`: `pageDesktopHistory` (:296), `countDesktopHistory` (:308), `desktopHistoryEntry` (:312), model `DesktopHistoryEntry` (:343), backed by matching `Companion.sq` queries. `production()` wires `container.desktopSessions`.
- `grep` over `companion/src/main/kotlin/.../companion/ui/` for `pageDesktopHistory`/`countDesktopHistory`/`desktopHistoryEntry`/`DesktopHistoryEntry`/`desktopSessions` returns **zero** hits — the read API is dangling. `HistoryScreen`/`HistoryViewModel` remain phone-sync-only (`ReceivedText` + `DispatchService.reinsert`): no Phone/Desktop filter, no transcript-vs-`final_output_text` detail, no desktop re-insert.
- Desktop re-insert cannot reuse `DispatchService.reinsert` (resolves a phone `ReceivedText` and writes `dispatch_state`, which desktop rows never have); it must go through `container.inserter` / `TextInserter`.
- Net user impact: a desktop dictation persists but is invisible and non-re-insertable in every UI. §9.3 is explicit D3 scope (plan §5 Block D: "History §9.3"). A read API with no consumer is also a dangling cross-chunk contract.

**Why yellow (not green):** the design is spec-prescribed (§9.3), but the fix is a full new Compose surface + view-model + test suite, AND it carries an **open main-loop scope decision** — build it in Block-D repair now vs. a dedicated follow-up chunk — already escalated by W2 (`repair-W2-2.md`, cluster repair-W2-2). No code changed since escalation; re-confirmed open at HEAD. A build-vs-defer plan decision must precede the fix, so this is not a mechanical repair. Likely warrants an `AskUserQuestion` at the main loop.

**Fix (once scope is decided):** a desktop-history view-model over `DesktopSessionRepository.pageDesktopHistory`/`desktopHistoryEntry` + a `HistoryScreen` section with a Phone/Desktop filter, transcript-vs-`final_output_text` detail, and a `TextInserter` re-insert path; handle nullable `container.desktopSessions` in the `forTest` graph.

### logic-D-4 (Nice-to-have, green) — `reinsert` records the dispatch but never stamps `sessions.inserted_at`, so the two tables disagree on whether a synced text ever landed

**Files:** `companion/.../domain/DispatchService.kt:70-75` (`reinsert`), `companion/.../db/Companion.sq:394-397` (`recordDispatch`), doc invariant at `SqlDelightHistoryRepository.kt:45-49` and `Companion.sq:360-364`

**Verified at HEAD:**
- `DispatchService.reinsert` (:70-75) does `findById` → `inserter.insert` → `recordDispatch(sessionId, now, outcome)` with **no** `upsert`.
- `recordDispatch` (`Companion.sq:394-397`) updates only `dispatch_state` (`dispatched=1`, `last_outcome`, `received_at`); it never touches `sessions.inserted_at`.
- The cross-table invariant is documented and real: `SqlDelightHistoryRepository.kt:45-49` and `Companion.sq:360-364` — "`inserted_at` mirrors the dispatch flag so the archive agrees with dispatch_state on whether the text ever landed." The push path `DispatchService.dispatch` (:34-51) upholds it by upserting `dispatched=true` (stamping `inserted_at` via `upsertSyncSession`) before `recordDispatch`; `reinsert` does not.
- Re-inserting a text synced as pending (`upsertSyncSession` wrote `inserted_at=NULL`, `dispatch_state.dispatched=0`) leaves `dispatch_state.dispatched=1` while `sessions.inserted_at` stays NULL — the archive claims the text never landed, contradicting the dispatch row.

**Why Nice-to-have (latent):** no PHONE_SYNC read query reads `sessions.inserted_at` — `pageHistory`/`countHistory`/`receivedTextById`/`selectCursor` all read `ds.dispatched`; the `desktopHistory*` queries that select `inserted_at` scope to DESKTOP_DICTATION only. No observable behaviour impact today; it is a Room-parity gap on the `sessions` table (the table D1a's ablation keeps clean) that a future consumer reading `sessions.inserted_at` for phone rows would read wrong.

**Why green:** the fix is clear and small — have `recordDispatch` also stamp `sessions.inserted_at` with a never-downgrade coalesce (`UPDATE sessions SET inserted_at = coalesce(inserted_at, :at) WHERE id = :sessionId`) in the same transaction as the `dispatch_state` update, so both write paths maintain the documented mirror. Add a regression test asserting `inserted_at` is non-NULL after a `reinsert` of a pending synced row.

## Eliminated findings

None. Both raw findings validated against the code; neither is a misread, missing-context, or intentional-documented-decision false positive.

## Dedup

None required — the two findings are distinct issues in distinct files (D-3: UI/read-API consumer gap; D-4: dispatch write-path invariant), no overlap.

## Cross-cut patterns & clustering

None. Convention and test audits raised nothing at HEAD (convention: zero findings; test: T1 resolved, suite green). The two surviving findings do not cluster in a shared file and are not instances of one systemic pattern.

## Escalation note (for the main loop)

- **plan-and-api-D-3** is `yellow` and its research topic (`desktop-history-ui-scope`) should produce a build recommendation, but the final call — build the §9.3 history UI now inside Block-D repair vs. track it as a dedicated follow-up chunk — is a plan decision that likely warrants an `AskUserQuestion`. Already escalated once by W2 (`repair-W2-2.md`); no code has changed since.

## Noted observations (carried forward, not elevated to findings)

Documented-delegated deviations from the audits — defensible v1 deviations already tracked in impl issues:
- §9.1 mini-panel profile dropdown absent (D3-3, Nice-to-have) — the active profile is still selectable in `ManagementScreen`.
- §9.2 management editing depth shallow — deep pickers / model-param UI / network model-list deferred (D3-4, Nice-to-have; VM named as the E3 extension seam).
- Anticipated BLOCK_FILES paths (`ui/profiles/**`, `ui/models/**`, `ui/prompts/**`, `pipeline/review/**`) do not exist; functionality consolidated into `ui/config/ManagementScreen.kt` + `ConfigViewModel.kt` and the `pipeline/` reducer/effects. Layout deviation, functionally delivered.
- Transitional system prompt (`postProcess` persists fixed `SYSTEM_PROMPT_CONVERSATION`; `systemPromptMode` deliberately unwired, F9) — intentional ADR-0012 boundary.
- `submitContinuation` JobQueue dedup key includes `clock.nowMillis()` (effectively never dedups) — safe (reducer guards prevent double dispatch), convention-only.
- Re-dictate S2 timer: reducer keeps `recording = RecordingUi.Idle` during re-dictate (only `review.refinementRecording` flips), so `PanelViewModel`'s timer does not run for the S2 take — expected (ReviewPanel renders its own affordance); confirm at manual E2E.
- `CaptureResult.durationSeconds` integer division → sub-second take persists `0` — display/metadata only, rounding nit.
