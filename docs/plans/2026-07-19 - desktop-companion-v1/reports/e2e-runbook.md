# E2E-Runbook: desktop-companion-v1

**Plan:** [→ ../desktop-companion-v1.md](../desktop-companion-v1.md)
**Status:** final (Phase-1 E2E-strategy output) — all 4 user questions resolved 2026-07-19
**Created:** 2026-07-19
**Mode-Distribution:** auto: 10, manual: 6 (16 cases total) — auto = 8 companion/shared JVM (TC-P1..P4, TC-C1..C4) + 2 Android emulator (TC-A1, TC-A2)

> No `docs/runbooks/agentic/` catalog exists in this repo → the persistent-runbook
> derivation duty does not apply. Provenance is cited per section as
> `per <source>` (established pattern/doc) or **NEW** (introduced by this plan).

## Scope

Two verification frontiers, split by platform reachability on this Linux VM:

1. **Companion JVM (auto here).** New desktop dictation pipeline (D1), review mode
   (D3), catalog protocol + two-peer sync (E1/E2), SQLDelight schema parity + the
   `received_texts→sessions` migration (D1), canonical v3 serialization/hash (C1).
   All run in-process via `embeddedServer(CIO, port=0)` + real `DispatchClient` over
   a socket — no emulator, no Windows, runnable on this host with `./gradlew :companion:test`
   / `:shared:test`.
2. **Android (emulator/Windows-analog).** Room v11→v12 + prefs→entity migration (C2),
   clear-text key migration (B2), settings-UI rebuild (C3). These need the headless
   emulator (`scripts/e2e/`) — see the two blockers under Prerequisites.
3. **Windows-only (manual, F1).** Global hotkey (`RegisterHotKey`), focus-free panel
   (`WS_EX_NOACTIVATE`), DPAPI SecretStore, `TextInserter` SendInput auto-insert, and
   the full hotkey→panel→dictate→insert acceptance. Not reachable on Linux (Noop/
   clipboard path only) → the Block-F manual Windows checklist.

## Relevant Knowledge

- `test-orchestrator` (live mode) — for the running-system companion E2E cases
  (real embedded Ktor server + socket client); verify mode for the parity/snapshot units.
- No dedicated `test-knowledge-*` skills exist in this repo; methodology is carried by
  the existing E2E test classes cited per case.

## Prerequisites

| # | Kind | Target | Check | Blocking |
|---|------|--------|-------|----------|
| 1 | e2e-infra (Q1a: committed as pre-A1 groundwork) | `scripts/e2e/` + `docs/architecture/e2e-emulator.md` versioned in the worktree | `test -x scripts/e2e/emulator-up.sh && test -f docs/architecture/e2e-emulator.md && echo OK` | resolved-by-groundwork |
| 2 | test-config-defect (Q2b: separate pre-flight groundwork commit) | `androidTest` source set wires `app/schemas/` as assets | `grep -q 'assets.srcDirs' app/build.gradle && echo OK  # then: ./gradlew :app:connectedDebugAndroidTest has no 'Cannot find schema file' failure` | resolved-by-groundwork |
| 3 | toolchain | JDK 17 + Gradle wrapper for companion JVM E2E (auto cases) | `./gradlew :companion:test --dry-run -q >/dev/null && echo OK` | yes (auto cases) |
| 4 | emulator | KVM + Android SDK (API 35 google_apis x86_64) + AVD `dictate-e2e/5554` | `"$ANDROID_HOME"/emulator/emulator -accel-check && adb devices \| grep -qw device && echo OK` | yes (Android cases) |
| 5 | windows-device (Q3b: available at F1 time) | Windows host for the F1 manual acceptance (hotkey/panel/DPAPI/insert) | at F1: run TC-W1..W4 on the Windows box — not present on this Linux VM before F1 | yes (Windows manual cases, Block F only) |
| 6 | api-keys (Q4a: manual only) | user's real provider key for the single manual real-provider smoke TC-W5 | isolation: the user's own key entered at test time, one real transcription, never checked in | no (manual case only; all auto cases use fake runners) |

`per docs/architecture/e2e-emulator.md` for items 1/2/4 (emulator flow, IME id
`net.devemperor.dictate.debug/…core.DictateInputMethodService`, swiftshader headless,
the schema-assets defect §"Current instrumented-test status").

## User Questions (resolved 2026-07-19)

| Question | Answer | Resolved |
|----------|--------|----------|
| Q1 — access to the untracked Android E2E infra (`scripts/e2e/`, `docs/architecture/e2e-emulator.md`) | **(a)** committed into the feature branch as pre-A1 groundwork by the orchestrator; `tmp/desktop-concept/` also copied into the worktree (untracked, A1 checks it into `research/`) | 2026-07-19 |
| Q2 — schema-assets defect blocking C2 migration verification | **(b)** the `androidTest { assets.srcDirs += schemas }` one-liner is committed as a separate pre-flight groundwork commit BEFORE the run — TC-A1/TC-A2 build on working instrumented migration tests | 2026-07-19 |
| Q3 — Windows device for F1 manual acceptance | **(b)** available at F1 time; TC-W1..W4 run in Block F with the user; no earlier Windows access planned | 2026-07-19 |
| Q4 — fake vs. real runners for auto pipeline E2E | **(a)** auto cases use fake runners exclusively; one manual real-provider check (TC-W5) with the user's own key | 2026-07-19 |

## Test Cases

### TC-P1: Desktop dictation pipeline persists a full session (fake runner)

- **Mode:** auto · **Knowledge:** test-orchestrator (live) · **Scope:** D1 companion pipeline
- **Provenance:** NEW (pipeline is built in D1) — harness `per companion CompanionE2ETest` (embedded CIO server, `CompanionContainer.forTest`)
- **Steps:**
  1. Build a `CompanionContainer.forTest(...)` with a fake transcription+completion runner and a WAV 16 kHz mono fixture.
  2. Drive the pipeline: enqueue the WAV fixture → transcription → post-processing conversation → terminal insert (FakeTextInserter).
  3. Query the SQLDelight DB for the produced `session`.
- **Expected Result:** exactly one `session` row with linked `transcription` + ≥1 `processing_step` + `conversation_message` rows; terminal state = inserted; usage row written via `UsageSink`.

### TC-P2: Pause/Resume/Discard state transitions

- **Mode:** auto · **Knowledge:** test-orchestrator (verify) · **Scope:** D1 recording state machine
- **Provenance:** NEW
- **Steps:** unit-drive the recording axis through start→pause→resume→stop and start→discard.
- **Expected Result:** discard produces no session; pause/resume yields one continuous session; transitions match the D2 (§3) reducer rules (pure reducers, no IO).

### TC-P3: SQLDelight↔Room schema parity + `received_texts` migration

- **Mode:** auto · **Knowledge:** test-orchestrator (verify) · **Scope:** D1 schema
- **Provenance:** `per companion OriginCheckConstraintParityTest` (Double-Enum both-directions) + `per verifySqlDelightMigration`; migration itself NEW
- **Steps:**
  1. Run `./gradlew :companion:verifySqlDelightMigration` (replays every `.sqm` vs the checked-in schema snapshot).
  2. Parity assert: every enum value in `sessions/transcriptions/processing_steps/conversation_messages` (origin, status, role, …) is insertable AND a non-enum value is rejected by the CHECK — in both Room (`app/schemas/`) and SQLDelight.
  3. Migration test: seed a pre-migration DB with `received_texts` rows → run migration → assert they appear as `sessions` with the correct origin marker (F16), no data loss.
- **Expected Result:** verifyMigration green; parity holds both directions; every `received_texts` row mapped losslessly.

### TC-P4: Review mode holds on needsClarification; re-dictate continues the conversation

- **Mode:** auto · **Knowledge:** test-orchestrator (live) · **Scope:** D3 review
- **Provenance:** NEW — semantics `per docs/decisions/0013` (AmbiguityMode), harness `per CompanionE2ETest`
- **Steps:**
  1. Fake runner returns `needsClarification=true`.
  2. Assert the panel state holds (non-terminal), verdict via the shared `ReviewDecision` (one code path).
  3. Feed a re-dictate fixture (origin `REVIEW_REFINEMENT`) → assert a `ConversationContinuation` turn is persisted and the panel updates non-terminally.
- **Expected Result:** panel holds then updates; continuation turn persisted; ADR-0013 verdict matrix (3 modes × needsClarification × blank-message) green as a unit suite on the ReviewDecision caller.

### TC-C1: Two-peer catalog — index, entity fetch, auth-gated credential delivery

- **Mode:** auto · **Knowledge:** test-orchestrator (live) · **Scope:** E1 protocol + server
- **Provenance:** NEW — harness `per companion CompanionE2ETest` + `per SyncE2ETest` (in-process two-container)
- **Steps:**
  1. Two `CompanionContainer.forTest` instances (provider + consumer), pairing per the F10 model.
  2. GET catalog index → assert `{rootHash, entries[{id,kind,contentHash,meta}]}`; credentials appear as metadata only.
  3. Fetch an entity by id; fetch a credential secret value — assert it succeeds ONLY with auth and lands only via the SecretStore on the consumer.
  4. Fetch a credential without auth → assert `ErrorEnvelope` rejection.
- **Expected Result:** index shape valid; entity fetch ok; credential fetch auth-gated (F12 envelope), never in the index; an audit-log line is emitted per credential delivery (R8).

### TC-C2: Root-hash determinism — changes iff an entity changes

- **Mode:** auto · **Knowledge:** test-orchestrator (verify) · **Scope:** C1/E1 hash
- **Provenance:** NEW
- **Steps:** compute `rootHash` before/after (a) a no-op re-serialization, (b) field reorder, (c) a real value change.
- **Expected Result:** identical for (a)+(b), different for (c); canonical bytes byte-stable (snapshot), matching the C1 contentHash.

### TC-C3: Sync run — SUBSCRIBE updates, ONE_SHOT stays, fork never overwritten, offline = staleness

- **Mode:** auto · **Knowledge:** test-orchestrator (live) · **Scope:** E2 sync engine
- **Provenance:** NEW — harness `per SyncE2ETest`
- **Steps:**
  1. Provider changes a prompt → consumer SUBSCRIBE run: assert root-hash diff detected, pull, local copy updated, notification hook fired.
  2. A ONE_SHOT copy: assert it stays unchanged with its `sourceRef`.
  3. A locally forked copy (edited → decoupled): assert sync never overwrites it (F29).
  4. Provider offline: assert staleness timestamp, no error spam (F33).
  5. Idempotency: a second run with no change = no-op, exactly one HTTP call.
- **Expected Result:** all five hold; credential updates land only in the SecretStore.

### TC-C4: Malformed/truncated catalog payloads rejected cleanly

- **Mode:** auto · **Knowledge:** test-orchestrator (live) · **Scope:** E1 wire robustness
- **Provenance:** `per companion TruncatedResponseE2ETest`
- **Steps:** send truncated + malformed bodies to the catalog routes.
- **Expected Result:** clean `ErrorEnvelope`, no server crash, no partial persist.

### TC-A1: Android Room v11→v12 + prefs→entity migration (real fixture)

- **Mode:** auto (emulator) · **Knowledge:** test-orchestrator (verify) · **Scope:** C2 — highest risk (R2)
- **Provenance:** NEW — `per docs/architecture/e2e-emulator.md` (emulator + `connectedDebugAndroidTest`)
- **Depends on:** the two pre-A1 groundwork commits (Q1a: `scripts/e2e/` + emulator doc versioned; Q2b: `androidTest` schema-assets one-liner). Both land before the run — see Prerequisites #1/#2. Verify with their check commands, then proceed.
- **Steps:**
  1. `scripts/e2e/emulator-up.sh`; MigrationTestHelper opens a populated schema-v11 fixture DB (real provider/model/host/parameter prefs + all 10 key slots).
  2. Run v11→v12 migration → assert new tables (`provider_configs`, `model_refs`, `profiles`, `profile_prompts`, `prompts`+columns) populated as a lossless "Default profile"; Double-Enum CHECKs present.
  3. Characterization: same pref constellation ⇒ same runner configuration as before C2.
  4. Idempotency: second run = no-op.
  5. **Run the instrumented migration suite `MigrationTo12Test`** (C-TEST-3): `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.devemperor.dictate.database.migration.MigrationTo12Test` — this is the only place the Double-Enum CHECK accept/reject, `profile_prompts` CASCADE, and `runMigrationsAndValidate` schema check for v12 actually execute (local-only, not in CI, per the `MigrationTo11Test` convention). The pure-JVM `MigrationTo12MetadataTest` pins only the 11→12 version pair, so this emulator run is a **release gate** — the CHECK constraints stay unverified until it is green at least once.
- **Expected Result:** lossless migration, superset mapping holds, CHECKs enforced, characterization identical; prefs-backup export written before migration (rollback path); `MigrationTo12Test` green — all six methods (prompt-row preservation + schema validation, valid-enum accept, three unknown-enum rejects, `profile_prompts` CASCADE) pass on the emulator.

### TC-A2: Clear-text API keys removed from SharedPreferences after migration

- **Mode:** auto (emulator/Robolectric) · **Knowledge:** test-orchestrator (verify) · **Scope:** B2
- **Provenance:** NEW
- **Steps:** fixture prefs with all 10 `*ApiKey*` slots → run B2 migration → read each key from the SecretStore; assert the SharedPreferences entries are gone; grep-test that no code path reads the old Pref key constants; fresh-install-without-keys regression stays functional.
- **Expected Result:** keys retrievable from SecretStore, prefs empty, no residual clear-text keys.

### TC-A3: Android settings-UI entity flow (create profile → dictate → model switch takes effect)

- **Mode:** manual (emulator, mobile-mcp) · **Knowledge:** test-orchestrator (live) · **Scope:** C3
- **Provenance:** NEW — `per docs/architecture/e2e-emulator.md` (mobile-mcp interactive driving over adb)
- **Steps:** install debug APK + enable IME; in Settings create a provider → profile → pick model; dictate in the contact-editor text field; change the profile's model → assert the next dictation uses it. No keyboard-UI profile switcher present (D4.4).
- **Expected Result:** profile creation persists; dictation works; model change takes effect; Robolectric settings-navigation smoke green as the auto companion to this manual case.

### TC-W1: Windows global hotkey → warm panel → dictate → auto-insert (full acceptance)

- **Mode:** manual (Windows, F1) · **Knowledge:** — · **Scope:** D2/D3 Windows path, §2 criteria 3/4
- **Provenance:** NEW — the Block-F manual Windows checklist
- **Steps:** press the configured hotkey → panel visible <100 ms → record (start/pause/resume/discard) → transcription+post-processing via the active profile → auto-insert into a focused editor; run a review-mode + re-dictate round.
- **Expected Result:** panel warm+frameless+always-on-top; focus-free insert works (or the fallback foreground-restore path — spike outcome per `adr-desktop-panel-ui`); text inserted at the caret; review/re-dictate behaves as TC-P4.

### TC-W2: Windows DPAPI SecretStore round-trip

- **Mode:** manual (Windows, F1) · **Knowledge:** — · **Scope:** B1 Windows impl
- **Provenance:** NEW
- **Steps:** put/get/delete a secret via the DPAPI (JNA Crypt32) impl on Windows; confirm the blob is user-bound and not readable as plaintext.
- **Expected Result:** round-trip ok; `available` flag true on Windows; Linux fallback documented weaker (auto-tested separately on this host).

### TC-W3: Real two-process peer sync across two ports

- **Mode:** manual · **Knowledge:** test-orchestrator (live) · **Scope:** E2/E3 real deployment
- **Provenance:** NEW (auto coverage is in-process TC-C1/TC-C3; this is the real two-process form)
- **Steps:** launch two companion instances on different ports (one `--headless` hub-peer), pair, share a prompt from A, subscribe from B, change it on A, confirm B's timed poll updates + system/tray notification fires; Peer Explorer shows current/update/decoupled/stale states.
- **Expected Result:** cross-process sync + notification + explorer state matrix behave as the in-process auto cases.

### TC-W4: Tailscale discovery enumerates catalog-capable peers

- **Mode:** manual · **Knowledge:** — · **Scope:** E3 discovery
- **Provenance:** NEW — auto coverage is the fixture-JSON `PeerDiscovery` port test; this exercises the real CLI
- **Steps:** on a tailnet host with `tailscale` present, run discovery → assert peers with `supportsCatalog` are found via `tailscale status --json` + health probe; Noop fallback when the CLI is absent.
- **Expected Result:** real peers discovered; graceful fallback without the CLI.

### TC-W5: Real-provider dictation smoke (user's own key)

- **Mode:** manual · **Knowledge:** test-orchestrator (live) · **Scope:** D1/D3 real pipeline (Q4a)
- **Provenance:** NEW — the single real-provider check; all auto pipeline cases (TC-P1/P4) use fake runners
- **Steps:** with a profile pointing at a real provider and the user's own API key (entered at test time, never checked in), dictate a short sample on the desktop host → real transcription + post-processing → insert. Verify the provider upload path handles WAV 16 kHz mono within the provider's size limit (§10 Gap 2).
- **Expected Result:** a real end-to-end transcription completes and inserts; no auth/format/size error; usage row recorded.

## Acceptance

- All `mode: auto` cases pass. TC-P1..P4 + TC-C1..C4 (companion/shared JVM) are runnable
  on this Linux host now; TC-A1/TC-A2 run against the emulator once the two pre-flight
  groundwork commits (Q1a infra + Q2b schema-assets) are present in the worktree.
- All `mode: manual` cases confirmed by the user: TC-A3 (emulator + mobile-mcp) and the
  Windows/real-deployment set TC-W1..W5 in Block F (§2 criteria 3/4/7). The Block-F
  Windows sign-off is a checkbox checklist: [→ windows-acceptance-checklist.md](./windows-acceptance-checklist.md).
- No server crash on any catalog route; credential values never appear in a catalog index.

## Failure Routing

On failure: orchestrator starts issue-triage analogous to block-closeout (research →
repair-chunk → re-test). After 3 iterations without convergence: `AskUserQuestion`
escalation. The Phase-4 refresh section (below) is populated as blocks land.

## Phase-4 Refresh (added by orchestrator)

_empty — populated during Phase 4 as edge-of-the-blade points emerge from block outputs._

## Phase-4 Execution Results (2026-07-20)

Executed at `HEAD e3bcf81` on branch `feature/desktop-companion-v1`. The runbook was
written pre-implementation; the mapping below records the **drift adjustments** made
before execution and the **actual result** per case. Every `covered-by` claim is backed
by a real test run (fresh `--rerun-tasks` / `cleanTest` execution or on-device
`connectedDebugAndroidTest`), not a bare assertion.

### Prerequisites (re-verified)

| # | Result |
|---|--------|
| 1 e2e-infra | PASS — `scripts/e2e/` + `docs/architecture/e2e-emulator.md` committed & versioned on the branch |
| 2 schema-assets | PASS — `androidTest.assets.srcDirs += schemas` present in `app/build.gradle`; on-device `AndroidTestSetupSmokeTest` green, so `MigrationTestHelper` finds the exported schemas |
| 3 toolchain | PASS — `:companion:test --dry-run` OK |
| 4 emulator | PASS — AVD `dictate-e2e` booted on `emulator-5554` (Android 15 / API 35), `sys.boot_completed=1` |

### Drift adjustments applied before execution

1. **`received_texts` → `sessions` + `dispatch_state`.** The companion history model was
   replaced. TC-P1/TC-P3 remapped: the desktop-dictation session graph is
   `session` + `transcription` + `CONVERSATION_TURN processing_step` +
   `conversation_message`; the phone-sync ablation (`received_texts` → `PHONE_SYNC`
   sessions + 1:1 `dispatch_state`, ADR-0016 origin mapping) is verified by the new
   `ReceivedTextsAblationMigrationTest` (2.sqm), not a `received_texts` migration.
2. **TC-P1 harness.** Runbook said "per `CompanionE2ETest`". That class is the
   **phone→PC dispatch** pipeline; the **desktop dictation** pipeline (the TC-P1 subject)
   is `DesktopDictationPipelineTest`. Both verified.
3. **Room v12 → v13.** This plan's Android schema advanced past C2's v12: E2 added
   `MIGRATION_12_13` (peers + subscriptions subscriber tables). TC-A1 extended to run
   `MigrationTo13Test` on-device alongside `MigrationTo12Test`.
4. **Two-peer HTTP catalog E2E.** E2-completion added `CatalogSyncE2ETest` (commit
   `d2e9ca9`) — a real provider server + real subscriber companion + real
   `CatalogClient` over real HTTP covering subscribe → sync → provider-update → notify
   and fork-never-overwritten. It subsumes the two-container intent of TC-C1/TC-C3.
5. **TC-A2 is JVM/Robolectric, not emulator.** The B2 clear-text-key migration
   (`SecretsMigration`) runs fully under Robolectric — no emulator needed. Verified on
   the JVM.
6. **SubscriberStores productive on both hosts.** `SqlDelightCatalogSubscriberStore`
   (companion) and `AndroidCatalogSubscriberStore` (app) are the real sync-engine write
   paths; exercised by `CatalogSyncE2ETest` / `AndroidCatalogSubscriberStoreTest`.

### Case matrix

| Case | Mode | Result | Evidence (fresh run) |
|------|------|--------|----------------------|
| TC-P1 | auto | **PASS** | `DesktopDictationPipelineTest` 5/5 — `autoInsertTake_persistsSessionTranscriptionTurnAndMessages` (session+transcription+CONVERSATION_TURN+SYSTEM/USER messages, terminal insert, NoopUsageSink) |
| TC-P2 | auto | **PASS** | `DictationReducerTest` 31/31 — start→pause→resume→stop + discard (pure reducers) |
| TC-P3 | auto | **PASS** | `verifySqlDelightMigration` green; `ReceivedTextsAblationMigrationTest` 5/5, `CompanionSchemaParityTest` 16/16, `SchemaMigratorTest` 5/5, `CatalogCheckConstraintParityTest` 8/8, `ConfigEntityCheckParityTest` 12/12 |
| TC-P4 | auto | **PASS** | `ReviewDecisionTest` 5/5 (shared-ai) + `DesktopReviewDecisionMatrixTest` 8/8 (parametrised ADR-0013 matrix) + `DesktopDictationPipelineTest.reviewVerdict…`/`reDictate…` (panel holds, continuation turn persisted) |
| TC-C1 | auto | **PASS** | `CatalogE2ETest` 7/7 — index shape, entity fetch, credential auth-gated + audit row, credential never in index (real HTTP) |
| TC-C2 | auto | **PASS** | `ContentHashTest` 10/10 + `CanonicalJsonTest` 7/7 — determinism (no-op/reorder/value), canonical byte-stability |
| TC-C3 | auto | **PASS** | `CatalogSyncE2ETest` 2/2 (two-peer real HTTP: subscribe→update→notify, fork never overwritten, idempotent 1×GET) + `CatalogSyncEngineTest` 13/13 (shared) + `SyncE2ETest` 8/8 (lazy phone-history paging) |
| TC-C4 | auto | **PASS** | `TruncatedResponseE2ETest` 1/1 + `CompanionE2ETest` malformed/truncated cases (clean ErrorEnvelope, no crash, no partial persist, no dictated-text leak) |
| TC-A1 | auto (emulator) | **PASS** | `MigrationTo12Test` 6/6 on `emulator-5554` (Double-Enum CHECK accept + 3 rejects, `profile_prompts` CASCADE, `runMigrationsAndValidate` v12) — release gate green. Drift-extension `MigrationTo13Test` 3/3 (v12→v13 peers/subscriptions, CHECK, CASCADE) |
| TC-A2 | auto (Robolectric JVM) | **PASS** | `SecretsMigrationTest` 11/11 + `NoLegacyKeyReadTest` 2/2 + `AndroidKeystoreSecretStoreTest` 13/13 — keys in SecretStore, prefs cleared, no legacy-key read path |
| TC-A3 | manual | **pending-manual** | emulator + mobile-mcp settings-UI flow; Robolectric settings-nav smoke is the auto companion |
| TC-W1 | manual | **pending-manual** | Windows hotkey→panel→dictate→auto-insert acceptance (Block F) |
| TC-W2 | manual | **pending-manual** | Windows DPAPI SecretStore round-trip |
| TC-W3 | manual | **pending-manual** | real two-process peer sync across two ports |
| TC-W4 | manual | **pending-manual** | Tailscale discovery of catalog-capable peers |
| TC-W5 | manual | **pending-manual** | real-provider dictation smoke (user's own key) |

**Auto totals:** 10/10 pass (8 companion/shared JVM + TC-A1 emulator + TC-A2 Robolectric).
**Manual:** 6 pending (TC-A3 + TC-W1..W5), user-driven in Block F.

### Finding F-E2E-1 — pre-existing on-device migration-fixture bit-rot (out of plan scope, Important)

The `connectedDebugAndroidTest` run reported **13 failures**, all in **older** instrumented
migration tests — `MigrationTo4Test` (1), `MigrationTo8Test` (5), `MigrationTo9Test` (4),
`MigrationTo10Test` (3). None are in this plan's scope (TC-A1's `MigrationTo12Test`/
`MigrationTo13Test` are 6/6 and 3/3 green).

- **Cause:** their `seedSession…` helpers `INSERT INTO sessions (…)` without the
  `audio_file_paths` column, which is `TEXT NOT NULL` (no default) since schema **v5** →
  `SQLiteConstraintException: NOT NULL constraint failed: sessions.audio_file_paths`.
  `MigrationTo4Test.migrate1To4_chain_preservesData` differs: it inserts into `sessions`
  at v1 where that table does not yet exist → `no such table: sessions`.
- **Pre-existing:** `audio_file_paths NOT NULL` predates this branch; this plan touched
  neither the four test files (last changed by `4d6734d [conv-2]`) nor the tripped
  columns. These on-device suites are **local-only / never-CI** (same
  `pending: instrumented` convention as `MigrationTo11/12/13Test`), so the fixture rot
  was simply never observed.
- **Complexity self-assessment:** **simple** for the NOT-NULL group (add
  `audio_file_paths` to each seed INSERT); the `MigrationTo4Test` v1
  chain fixture needs a short look at the v1 schema before repair.
- **Status: fixed 1caeccbd4a1378ac89d9556318e5b0e099950c28.** The NOT-NULL group now seeds
  `audio_file_paths` with `''` — the schema-correct empty value (`MIGRATION_4_5` adds the
  column as `NOT NULL DEFAULT ''` and `Converters.toStringList` decodes `''` as
  `emptyList()`; `'[]'` would wrongly decode as a one-element list). The `migrate1To4_chain`
  fixture now seeds the session **after** `MIGRATION_1_2` creates the `sessions` table
  (v1 has only `usage`/`prompts`; see `app/schemas/1.json`), splitting the chain into a
  1→2 leg (seed) and a 2→4 leg. Only the four test files were touched — no production or
  migration-SQL change, assertions unchanged. Verified on `emulator-5554`: 13/13 red
  before, 26/26 target + 9/9 `MigrationTo12/13Test` green after (35 tests, 0 failures).
