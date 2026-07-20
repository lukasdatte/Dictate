# Block D — AUDIT-TEST report

**Topic:** test · **Block:** D (Desktop companion: schema-parity, audio+pipeline, hotkey+panel, config UI)
**Diff range:** `c46cfe8..HEAD` (9c19a1b) filtered to `companion/src/test/**` + `fakes/` · **Timestamp:** 2026-07-20T13:30:00+02:00
**Verdict:** Green and high-quality after both repair waves. Suite passes (370 tests, 3 documented skips, 0 failures/0 errors), migration chain verifies, the earlier helper-duplication finding (T1) is resolved. **No new findings.**

## Dynamic run

`./gradlew :companion:test` → **BUILD SUCCESSFUL**; `./gradlew :companion:verifySqlDelightMigration` → **BUILD SUCCESSFUL**.

- 56 test classes, **370 tests, 0 failures / 0 errors**.
- **3 skips, both intentional and documented:**
  - `DpapiSecretStoreTest` (2) — Windows-only (Block B), `Assume`-skipped off-Windows.
  - `FocusFreeWindowSpikeTest` (1) — `@Ignore("pending: D2-focus-spike — awaiting the manual Windows verdict (TC-W1)")`. Correct test-first pending marker (greppable `pending:` prefix, references the runbook TC), not a faked-green.
- `verifySqlDelightMigration` green — the migration chain (1.sqm chords → 2.sqm session model / received_texts ablation → 3.sqm config entities + `usage` v3→v4 fold) verifies against the checked-in `3.db`/`4.db` snapshots.

**No coverage measurement available:** `CONVENTIONS.coverage_command = none` project-wide. Branch thresholds cannot be verified mechanically; coverage assessed qualitatively per file (below).

## Cross-chunk regressions

**None.** The behaviour-neutrality-protected suites are all green with the whole block applied:
`SyncE2ETest`, `CompanionE2ETest`, `MultiConnectorE2ETest`, `TruncatedResponseE2ETest`,
`SqlDelightHistoryRepositoryTest`. D1a's schema rebuild + received_texts ablation and the D1a→D3
`usage`-table fold did not disturb any previously-green path.

## Repair-wave test verification (state since the prior audit)

The prior audit (2026-07-20T00:40) flagged **T1** (cross-chunk helper duplication) and two scope
remainders. Current state:

- **T1 — RESOLVED.** `assertCheckFailure`, `Iterable<Enum<*>>.names()`, and `SqlDriver.exec(sql)` are
  extracted into `companion/src/test/.../fakes/SqlCheckSupport.kt` (the sanctioned helper home). All four
  data-package parity/migration tests (`CompanionSchemaParityTest`, `ConfigEntityCheckParityTest`,
  `ReceivedTextsAblationMigrationTest`, `SchemaMigratorTest`) now `import` it; no local re-definitions
  remain (grep-verified). The drift risk (one copy of the `"CHECK constraint failed"` matcher loosened
  in isolation) is closed.
- **Repair wave 2** added the profile-post-processing resolution tests: `ConfigProfileSourceTest` (8) —
  covers no-active-profile default, missing-row fallback, auto-apply-only prompt ordering with
  `requiresSelection` provenance, dropped-missing-prompt-row, all three style-prompt modes
  (NONE/PREDEFINED/CUSTOM), and language/auto-format from the injected device suppliers. Both halves
  present; regression-guards the "only ambiguityMode was resolved" finding.

## Static quality (per chunk)

**D1a — schema parity / ablation.** `CompanionSchemaParityTest` and `ConfigEntityCheckParityTest` pin the
Double-Enum rule with **both halves** per column (every enum value accepted + a rejection insert proving
the CHECK has teeth), comparing CHECK vocabularies against the shared enum `.name` sets.
`ReceivedTextsAblationMigrationTest` migrates a faithful v2 fixture and checks the ablation row-by-row
incl. same-millisecond cursor tie-break and `UNKNOWN→KEYBOARD` fold (ADR-0016). Modified
`SchemaMigratorTest`/`ChordMigrationSeedTest` rebuild the real v1/v2 tables before stamping the version so
the migration actually replays (a bare version stamp would no-op the migration).

**D1b — audio + pipeline.** `WavCodecTest` (11) covers header/writer/merge/duration/peak with edge cases
(data behind a LIST chunk, zero-copy single-segment merge, honours `len` over array size, non-WAV → −1)
and two named regression guards with root-cause comments (`merge_returnValueAlwaysExists…` for logic-D-1,
`peak_clampsAFullScaleNegativeSample…` for logic-D-2). `DictationReducerTest`, `JobQueueTest` (FIFO +
`maxConcurrent==1` + in-flight dedup + re-accept), `DesktopDictationPipelineTest` (headless E2E over a
real in-memory DB + WAV fixture + fake runners, asserting the whole Room-parity session graph),
`DesktopReviewDecisionMatrixTest` (parameterized, pins the shared `ReviewDecision.decide` matrix verbatim).

**D2 — hotkey / panel / focus.** `FocusRestorationPolicyTest` exercises both §6.3 paths incl. the
restore-BEFORE-insert ordering. `RecordingBarDesignTest` transfers every Android widget parameter with
each assertion citing the source line it must match. `HotkeyComboTest` (tolerant parse + garbage-rejection
battery). `Win32GlobalHotkeyTest` (pure modifier translation + RegisterHotKey constants). `PanelViewModelTest`
drives the presentation state machine against `MutableClock` incl. pause freezing the timer.

**D3 — config entities / UI.** `ConfigEntityCheckParityTest` mirrors the Double-Enum approach for config
tables. `CompanionConfigRepositoryTest` round-trips each entity, proves hash-recompute-on-write, provenance
null for local entities, ordered-prompt replace-not-append. `DesktopHistoryTest` covers the desktop-history
read scope (newest-first, substring filter, exclusion of in-flight / REVIEW_REFINEMENT / PHONE_SYNC).
`ConfigViewModelTest` drives the VM with `Dispatchers.Unconfined` incl. dangling-active-pointer cleanup and
blank-text rejection.

Mock convention (project fakes under `fakes/`) followed; no ad-hoc hand-mocks where a fake exists. Test
names describe behaviour + condition. Assertions concrete; no fragile snapshots. Doc-trail intact: the
repair-wave production-code fixes each ship with a matching regression test carrying a root-cause comment.

## Findings

**None.** T1 (the only prior test finding) is resolved.

## Observations (not findings)

- **`ProfileBackedPromptConfig` has no dedicated unit test** but is covered transitively: its only
  desktop-consumed surface (the style-prompt mode/text) is asserted through `ConfigProfileSourceTest`'s
  PREDEFINED/CUSTOM/NONE cases via the real `PromptService` path. Its `systemPrompt*` half is documented
  dead-for-now on desktop v1 (mapped for a future standalone-rewording path). No test gap of substance.
- **Coverage tooling is absent project-wide** (`coverage_command: none`). If quantitative gates become
  desirable, wiring JaCoCo into `:companion` would let the Double-Enum, reducer, and codec suites report
  the near-total branch coverage they already achieve.
- The two scope remainders the prior audit tracked (profile post-processing resolution; §9.3 history UI)
  are **plan-and-api / ownership** concerns, not test-quality ones — out of AUDIT-TEST scope. The test
  layer for what is implemented is complete and green.
