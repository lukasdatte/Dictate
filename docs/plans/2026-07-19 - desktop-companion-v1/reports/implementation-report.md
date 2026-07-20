# Implementation Report — desktop-companion-v1

**Plan:** `docs/plans/2026-07-19 - desktop-companion-v1/desktop-companion-v1.md`
**Worktree / branch:** `worktrees/feature/desktop-companion-v1` · `feature/desktop-companion-v1`
**Base:** `main@048fb37` · **HEAD at report time:** `f33fa2a`
**Aggregated:** 2026-07-20 (Phase 4.7, read-only)

## Header counters

| Dimension | Count |
|---|---|
| Blocks | 6 content blocks (A–F) + 1 cross-block integration pass (INT) |
| Chunks delivered | 16 planned (A1–A3, B1–B2, C1–C3, D1a/D1b/D2/D3, E1–E3, F1) + 1 follow-up (D4) |
| Repair waves | A×1, B×1, C×2, D×3, E×1, F×1, INT×1, + 1 mid-chunk repair (A3-SF1), + the E2-completion slice (post-audit) |
| Per-block audits | 4-lens (plan-and-api/convention/logic/test) for A–E; single-lens (plan-and-api, docs-only) for F; 1 cross-block integration check; 1 post-audit of the late E2 slice |
| Re-audits | A, B, C×2, D×2, E, F, INT, post-audit-3bec2b8 — all converged |
| Runs | 3 workflow runs (login-expiry abort + failed cache-resume + completedChunks fresh run) then a `finalize` run; manual landings for the E2 slice and E2E fixture fix |
| Auto E2E | 10/10 pass (8 companion/shared JVM + TC-A1 emulator + TC-A2 Robolectric) |
| Manual E2E | 6 pending user acceptance (TC-A3, TC-W1–W5) |

**Marker tally (consistent with the sections below):** 🔴 3 (R-1..R-3) · 🟠 7 (O-1..O-7)
· 🟢 8 fix families (grouped into 12 compact bullets). Full issue list: 30 grouped rows ·
drift list: 7 rows · research files: 14 · report files under `reports/`: 131 (coverage below).

**Overall outcome:** the plan is fully implemented and green. Every Critical and Important
finding raised across the run is resolved and re-verified at HEAD; all four modules
compile and their unit suites pass. What remains open is human-in-the-loop acceptance
(6 manual E2E cases + the Windows checklist sign-off) and a short tail of documented,
below-threshold Nice-to-have / doc-freshness items. The escalation threshold for
postponed issues (≥1 Critical, ≥5 Important, or ≥10 total) is **not** reached.

---

## 🔴 needs-research

Entries that hit a 🔴 trigger: a Critical that needed ≥2 repair attempts, an escalated
item, a repair/landing with drift ≥5 files, or a re-audit that found/escalated a new issue.

### R-1 — Block C: `C-TEST-2` WindowsDeviceSecret re-classified Important→Critical by re-audit, needed a 2nd repair wave
- **Source:** `reports/C/re-audit-W1.md` (escalation), `reports/C/re-audit-W2.md` (resolution), `research/androidaiconfig-secret-pref-retirement.md`
- **Severity / trigger:** Critical; a **re-audit found a cascade** — the Block-C repair wave 1 (`9b2038c`) closed the API-key half of the §2.6 SecretStore invariant but left `NoLegacyKeyReadTest` `@Ignore`d, and tracing the root cause end-to-end revealed a **live runtime + security regression**: `SecretsMigration` clears `Pref.WindowsDeviceSecret` on every app start while three main-source consumers still read/wrote the plaintext pref, so an already-paired user silently read as *unpaired* (dispatch gated off) and `WindowsPairingActivity` re-persisted the pairing secret in plaintext.
- **Wave commit:** repair wave 2 `d3c6e51`.
- **Files:** `preferences/WindowsTarget.kt`, `settings/WindowsPairingActivity.java`, `state/PipelinePrefMirror.kt`, `secrets/SecretsMigration.kt`, new `secrets/PairingSecrets.kt`, `secrets/NoLegacyKeyReadTest.kt`.
- **What to verify:** re-audit W2 confirmed convergence (`WindowsTarget.from` split into non-secret `isPaired(sp)` + store-backed `resolve(sp, secretStore)`; all consumers re-pointed; `NoLegacyKeyReadTest` un-ignored and green; grep shows zero secret-pref reads outside the allow-list). Cross-block integration re-confirmed this at HEAD. No open action — flagged 🔴 only because a re-audit escalated a Critical needing a second wave.
- **Follow-up research (2026-07-20, read-only at `f33fa2a`): CONFIRMED-RESOLVED.**
  - **(a) All former plaintext consumers re-pointed.** `git show d3c6e51^` shows exactly three main-source consumers of `Pref.WindowsDeviceSecret` besides the definition + migration: `WindowsTarget.from` (read via `sp.get`), `PipelinePrefMirror` (watched `WindowsDeviceSecret.key` + read through `from`), and `WindowsPairingActivity` (wrote plaintext on pair `:218` and unpair `:286`). At HEAD every one reads/writes the secret only through the `SecretStore`: `WindowsTarget.resolve` calls `secretStore.get(PairingSecrets.DEVICE_SECRET_REF)`; the `paired?` gate is the non-secret `isPaired(sp)` (url + deviceId); `WindowsPairingActivity.persistPairing` does `store.put(DEVICE_SECRET_REF, …)` and `onUnpairClicked` does `store.delete(DEVICE_SECRET_REF)`. Every runtime caller (`DictatePipelineService` ×3 `resolve`, `StartPcDictationActivity`, `DictateInputMethodService`, `PreferencesFragment`, `WindowsAutoSend`) now routes through `resolve`/`isPaired`.
  - **(b) grep-proof.** At HEAD `Pref.WindowsDeviceSecret` appears in main source only in `DictatePrefs.kt` (definition) and `SecretsMigration.kt` (the §2.6 allow-listed migration source). No raw-string read of `windows_device_secret` exists (the only raw-string hits are the `SecretRef` id in `PairingSecrets`/`SecretsMigration` — a store address, not a pref read). `WindowsTarget.from(` is fully gone from code (one stale reference survives in `docs/decisions/0014-in-keyboard-history-panel.md:324` — doc-only, non-runtime, trivial cleanup, not a risk).
  - **(c) Regression is guarded.** `NoLegacyKeyReadTest` is active (no `@Ignore` remains; only a comment mentions the removed marker), lists `"WindowsDeviceSecret"` among the 11 secret-pref names, and allow-lists only `{DictatePrefs.kt, SecretsMigration.kt}` — a reintroduced read/write in any other main-source file fails it with the exact `file:line`. Verified statically that its offending set is empty at HEAD. `SecretsMigrationTest.pairedUserSurvivesMigration_isPairedStaysTrue_resolveReadsMigratedSecret` is the direct behavioural regression test (paired user → run migration → `isPaired` stays true and `resolve` reads the migrated secret from the store).
  - **(d) Half-migrated edge case — no residual risk.** No production device has ever run this migration (unreleased feature branch). Even hypothetically, the pre-fix migration wrote the secret to the **identical** store address (`SecretRef("pairing", "windows_device_secret")` — `PAIRING_NAMESPACE` = `"pairing"` then, `PairingSecrets.DEVICE_SECRET_REF` now) *before* deleting the plaintext, and set the idempotence flag `SecretsMigratedV1` last. So a device that ran the buggy migration lands with the secret in the store and the flag set → the fixed build's migration is a no-op (no re-clear, no data loss), `isPaired` = true, and `resolve` reads the secret → fully paired again. The `backup → put → remove → flag-last` order invariant means any crash-window abort retries cleanly (covered by `secondRun_isNoOp`, `storeUnavailable_…`, `putFailureMidway_…`, and the backup tests). No persistent half-migrated broken state is reachable.
  - **Verdict:** the C-TEST-2 resolution is complete and correct at HEAD; no code action needed. Only optional trivial follow-up: refresh the stale `WindowsTarget.from` mention in ADR-0014.

### R-2 — Late E2 catalog-sync slice: wave-verify FAIL (uncommitted producers) → manual landing + post-audit + 9-commit completion (drift ≫5 files)
- **Source:** `reports/wave-verify.md`, `reports/E/post-audit-3bec2b8.md`, `reports/E/E2-completion.md`
- **Severity / trigger:** escalated at the wave-verify gate; **drift far above 5 files**. Run 3 committed E1–F but the `wave-verify` gate found HEAD (`d0d2e19`) importing an entire uncommitted catalog-sync + notification-port slice (~19 source files: `CatalogService`, `SqlDelightCatalogRepository`, `SqlDelightCatalogAuditLog`, `CatalogRoutes`, `CatalogSyncScheduler`, `AndroidNotificationPort`, `CatalogSyncWorker`, `4.sqm`, tests, …) — committed `:companion` code would not compile from a clean checkout.
- **Wave commits:** slice landed as `3bec2b8` (tests green: `:companion:test` + `verifySqlDelightMigration` + `:app:compileDebugKotlin`); post-audit found **0 Critical, 1 Important** (subscriber-side store missing) + 4 Nice-to-have; the Important was closed by the **E2-completion** work: `268b76b`, `bae3b34`, `a9cbd9f`, `cf534bf`, `d2e9ca9`, `056b01a`, `1cb920d`, `9fbd43d`, `e3bcf81` (productive subscriber stores on both hosts, Room v12→v13, two-peer real-HTTP `CatalogSyncE2ETest`, credential-fingerprint spec fix).
- **What to verify:** integration-check confirms zero untracked source at HEAD and both hosts wired; the two-peer E2E is green. One residual **Known minor** remains (see O-6). The AC8 fork-protection now runs against real tables. Verify nothing in the slice re-introduces the clean-checkout gap (integration-check: resolved).
- **Verification verdict (R-2 follow-up, 2026-07-20): CONFIRMED-RESOLVED.** (a) The post-audit Important is fully productive on **both** hosts — Android wires `CatalogSync.setGateway(AndroidCatalogSyncGateway(...))` + `CatalogSyncWorker.enqueue` in `DictateApplication.onCreate`; Companion builds the real `SqlDelightCatalogSubscriberStore` → engine → scheduler in `CompanionContainer.production()` and `CompanionBootstrap.start()` calls `catalogSyncScheduler.start()`. The `gateway ?: return success()` / null-scheduler paths are reached **only** pre-wiring or in the headless test graph — no `gateway=null` production path remains. The fingerprint fix (`bae3b34`) is **spec-conform**: §4.3 asks for a hash over the at-rest ciphertext, but the `SecretStore` port deliberately never exposes it (ADR-0029, three crypto-free backends), so the code takes the sanctioned §15-Gap-6 fallback — SHA-256 over the plaintext, **domain-separated** (`dictate.catalog.credential.v1`) + **id-salted** with a NUL delimiter — closing the low-entropy confirmation oracle. (b) The three E2-completion deviations are all defensible, none a follow-up risk: *Takeover local-id = source-id* is required for the `CatalogPayloadGraft` recompute-hash to match the source (verify-before-write depends on it), and the schema still permits differing ids; *`markSourceRemoved` derived from the live index* is a clean SSoT choice consistent with the accepted staleness≠error semantics; *worker-enqueue best-effort* is a Robolectric/uninitialised-WorkManager guard that is inert under the production manifest initializer. (c) Evidence at HEAD (`f33fa2a`): clean checkout, zero untracked source; `:companion:test` for `CatalogSyncE2ETest` (subscribe→sync→update→notify + fork-protection over real HTTP), `CatalogServiceTest` (domain-separated/id-salted + rotation-tracking fingerprint regressions) and `SqlDelightCatalogSubscriberStoreTest` all **green** (compile UP-TO-DATE). The chain 268b76b..9fbd43d is consistent and gap-free.

### R-3 — Mid-chunk Critical A3-SF1: the entire A3 substance was uncommitted at the A3 wave HEAD
- **Source:** `reports/A/A3-selffix.md`, `research/mid-A3-A3-SF1.md`
- **Severity / trigger:** Critical, `blocks-following`; the self-fix found that wave `497ec8d` committed only `PipelineOrchestrator.kt` (+28 KDoc) + report, while the real A3 body (5 ports, 6 adapters, 9 moved AI-core files, `DictateUtils.java` −85, characterization tests) sat unstaged — the committed A3 was incoherent and would not build in isolation, threatening every downstream `:shared-ai` consumer.
- **Wave commit:** mid-repair `b788fe5` `[A.A3] mid-repair A3-SF1` committed the full A3 file set.
- **What to verify:** Block-A audit ran on the corrected tree (2417 `:app` tests green, 87 `:shared-ai`); resolved. Flagged 🔴 because it was a Critical commit-coherence defect surfaced by fresh-eyes review.
- **Verdict (R-3 follow-up, HEAD `f33fa2a`): CONFIRMED-RESOLVED.** (a) AC6 acceptance grep passes: no `import android.` anywhere in `shared-ai/src/main`, and the AI core reads no `SharedPreferences`/`UsageDao`/`MediaMetadataRetriever` directly — the only matches are KDoc/comments naming the ports; the `:app` `ai/` tree outside `ai/adapter/` has zero direct-access reads. (b) The §8.1 characterization tests (`AiConfigParityTest`, `ParameterResolutionParityTest`, `ProxyConfigParityTest`) and §8.2 movers (`AIOrchestratorConverseTest`, `ElevenLabsKeytermsSerializationParityTest`, `PromptTemplatesPunctuationTest`, `ElevenLabs*RunnerTest`/`*ParserTest`) all run green at HEAD (`:app:testDebugUnitTest` + `:shared-ai:test` BUILD SUCCESSFUL). (c) Diff review of `b788fe5`: the commit carries only the A3 extraction substance (5 ports, 6 adapters, 11 moves, punctuation table `DictateUtils −85` → `PromptTemplates +92`) — the `AIOrchestrator` rewrite preserves keyterms resolution (moved verbatim into `AndroidAiConfig.elevenLabsKeyterms()`), `usageSink.addUsage` argument order, and `AIProviderException` re-wrapping; no assertion changes in the moved tests. The behaviour-bearing moves are pinned by the pre-A3 characterization tests, so neutrality holds. (d) `SharedAiPurityTest` guards the module cut (forbids `android./androidx./io.ktor/kotlinx.coroutines/org.json`) and its negative self-test `theTestItself_findsAViolationWhenThereIsOne` asserts the scanner actually reads >5 sources and matches a fabricated `android.content.Context` import — green.

> The three 🔴 entries are all **resolved and re-verified**. None is an open research
> question; each is listed because it tripped a 🔴 structural trigger (Critical + ≥2
> attempts / escalation / large drift). The genuinely open work is human acceptance, below.

---

## 🟠 review-recommended

Larger / architecture plan-deviations (even when resolved), repair drift ≥1 file,
cross-block convention drift, and accepted-transitional or postponed Important items.

### O-1 — §9.3 desktop-history UI: three-stage evolution (deferred → toggle → unified), design superseded twice
- **Source:** `reports/D/D3-impl.md` (D3-2 delegated), `reports/D/repair-W3-1.md`, `reports/D/D4-history-ui.md`
- D3 delivered the data layer only and delegated the UI (D3-2, Important). Repair wave W3-1 (`a5695c5`) built a **screen-level Phone/Desktop toggle over two view models**. Follow-up chunk **D4** (`f33fa2a`) then **superseded** that with one unified filterable (All/Phone/Desktop) `HistoryViewModel`, **deleting** `DesktopHistoryViewModel(+Test)`. Net: resolved and tested (unified `HistoryViewModelTest` 18/18), but the surface was designed three times — worth a glance that the final unified merge-paging (top-K over-fetch, not SQL UNION) is what is wanted long-term. Aligns with ADR-0035.

### O-2 — Accepted transitional desktop post-processing profile resolution (plan-and-api-D-2 part b)
- **Source:** `reports/D/re-audit-W1.md`, `reports/D/validated-findings.md` (Noted observations), `research/desktop-aiconfig-credential-resolution.md`
- The credential core of D-2 is resolved (`ProfileBackedAiConfig` reads the key from the SecretStore; a real desktop dictation now authenticates). The **post-processing** surface (`instructions`/`stylePrompt` resolution, and `autoFormatEnabled`/`language` which have no `ProfileEntity` field) ships **transitional**: `postProcess` persists a fixed `SYSTEM_PROMPT_CONVERSATION`, `systemPromptMode` deliberately unwired (F9, an intentional ADR-0012 boundary). The HEAD re-run consolidation treats D-2 as closed; review recommended to confirm the F9 transitional boundary is the accepted v1 end-state rather than an unowned gap.

### O-3 — Systemic "spec finer-grained → consolidated-and-parity-tested" deviation across blocks
- **Source:** `reports/integration-check.md` (Axis 5), `reports/INT-repair-W1-1.md`
- A repeating, sanctioned pattern: single `shared.config.*` enums instead of the specs' parallel `catalog.*Wire` copies (E); SQLDelight migrations reallocated to `2/3/4.sqm` (D5); companion `usage` table moved D1b→D3; management UI consolidated into `ui/config/ManagementScreen` + `ConfigViewModel` instead of `ui/profiles`/`ui/models`/`ui/prompts` (D). Each is D5/D4-endorsed, parity/characterization-tested, code-annotated. Recorded once as `integ-1` (Nice-to-have) and **resolved** by the INT freshness pass (`bf4c804`, +190 append-only spec Change-History lines). Review recommended so the archived specs' as-built notes are trusted.

### O-4 — Delegated E2-1/E3-1: the whole production subscriber runtime was deferred, then completed
- **Source:** `reports/E/E2-impl.md` (E2-1), `reports/E/E3-impl.md` (E3-1), `reports/E/validated-findings.md` (R1/R2/R3), `reports/E/E2-completion.md`
- E1 shipped the offer side + engine core + UI shells with the credential-touching subscriber runtime honestly deferred (`PeersScreen` null-wires `PeerIndexSource`; `canSubscribe=false`). AC10 (two-peer E2E) and the production wiring were `blocks-following`. All resolved in E2-completion (both host stores, scheduler/gateway wiring at app start, two-peer HTTP E2E). Large architecture deviation, resolved — review recommended to confirm the block-gate is genuinely closed (it is, per integration-check).

### O-5 — Repair-wave in-scope drift (each ≥1 file, benign, documented or not)
- **Source:** `reports/D/repair-W3-1.md` (drift section), `reports/D/re-audit-W1.md` (new-problems note)
- (a) `HistoryScreen.kt` `Pager` refactored to primitives so both scopes share it (DRY; phone tests green). (b) **Undocumented** benign drift: Block-D repair W1 added `line.stop()` to `Take.pause()` / `line.start()` to `resume()` beyond the reported finish/discard fix — spec-§4.3-aligned and safe, but not mentioned in the wave report. Review recommended only because it was an unreported behaviour change.

### O-6 — Known-minor perf: `AndroidCatalogSubscriberStore.promptDtoByUuid()` O(n) scan
- **Source:** `reports/E/E2-completion.md` (Known minor), `reports/docs-final-report.md` (source note)
- Each prompt-update does `getAll().firstOrNull { it.uuid == uuid }` over the full prompt table; a `promptDao().byUuid(uuid)` query would be cleaner. Pre-existing pattern, no functional defect; tracked as a follow-up.

### O-7 — Documentation gaps + differing forward-compat characterization
- **Source:** `reports/docs-final-report.md` (3 gaps, 4 flags, 5 source notes), `reports/C/re-audit-W1.md` vs `reports/integration-check.md` (Axis 6)
- Docs-final left **3 gaps** (peer-catalog architecture overview, config-entity-model architecture overview, `shared/`+`shared-ai/` module READMEs — follow-up doc plan default **no**) and **4 flags** F1–F4 (F1–F3 addressed by cleanup commit `7e44996`; F4 `tmp/plan-keyboard-action-engine.md` broken link deliberately left — pre-existing, foreign plan). Separately, **`logic-C-1`/Y1 `v3-forward-compat-hash-recompute`**: re-audit-W1 records it **RESOLVED** (resolution (b), recompute over raw `JsonElement`, `ContentHashTest` added), but `integration-check.md` Axis 6 still lists it as a "documented, dormant design item." Review recommended to confirm the resolved state is authoritative (the SAF file-import path now survives additive keys) and that the integration-check characterization is merely stale.

---

## 🟢 informational (resolved, by family)

- **Inline fixes during implementation** — A3: removed dangling `java.util.{Collections,HashMap,Map}` imports from `DictateUtils.java` after the punctuation-table move; A3 self-fix: added `PromptConfig` to the `PipelineOrchestrator` KDoc port list. All committed with their chunks.
- **Self-fix passes (fresh-eyes, per chunk)** — every chunk ran a diff-based self-fix (`[X] … self-fix` commits): A1/A2/A3, B2, C1/C2/C3, D1a/D1b/D2/D3, E1/E2/E3, F1. Most were verify-only; the load-bearing one was A3-SF1 (→ R-3).
- **Block-A repair wave 1 (`c6a828f`)** — 4 Nice-to-have: `@see` anchor style unified across 5 adapters (convention-A-1); `SystemPromptResolver.create()` given its one call site (convention-A-2); `FakeProxyConfig.installAuthenticatorCalls` made live via 2 new tests (A-TEST-2); A-TEST-1 deferred by design.
- **Block-B repair wave 1 (`58a88b1`)** — 8 findings: F1 Important (Android `put` wraps `cipher.init/doFinal` in `StorageIo`, killing the boot-loop crash path) + 7 Nice-to-have (atomic secret-blob write; per-access `available`; KEK-unavailable test; `detectSecretStore` test; `writeOwnerOnly` committed + regression test; `object SecretStoreModule`; German→English KDoc).
- **Block-C repair waves 1+2 (`9b2038c`,`d3c6e51`)** — 11 green (enum-parity `AIFunction.toWire()`; single `canonicalDecimal` helper; `ProfileRoomEntity` Double-Enum defaults; `sourceRefOrNull` shared; charset spelling + dead import; raw-enum-label rendering; `setTranscriptionKeyterms` returns Boolean; `nextPos()` = MAX+1; `PromptProvenanceTest`; CHECK-constraint emulator gate; `ConfigMigrationScenario` test util) + `logic-C-1` forward-compat (resolution b) + the doc-drift KDoc cleanup.
- **Block-D repair waves 1–3 (`b99b141`,`3b9f980`,`a5695c5`)** — logic-D-1 Critical (merge return-value → single-segment take uploads a real file) + convention/logic/test Nice-to-haves (peak clamp, line-stop ordering, `TranscriptionRecord` rename, `ConcurrentHashMap`, usage sink wired, `SqlCheckSupport` test util) + logic-D-4 (`markDispatchInserted` stamps `sessions.inserted_at` transactionally, regression verified red-then-green).
- **Block-E repair wave 1 (`df191be`)** — 8 green: TailscalePeerDiscovery timeout/leak/deadlock rewrite + test timeout; `PeerExplorerViewModel` blocking seams hopped to `ioDispatcher`; `:shared` port files committed self-contained; `:app` `forkEvery=1` test isolation; single `Long.asTime()` helper; status-chip casing unified; fake honors `peerId`; import ordering.
- **Block-F repair wave 1 (`d0d2e19`)** — 2 docs findings: Windows checklist / F1 report criterion-9 misattribution corrected in lockstep; one-way ADR links (0016/0024/0025 ↔ 0030/0034) made reciprocal.
- **Post-audit-3bec2b8 Nice-to-haves** — credential fingerprint domain-separated + id-salted (`bae3b34`); the other three (scheduler restart-after-stop, `AwtNotificationPort.available` timing, enum-package spec drift) documented, no action needed.
- **INT repair wave 1 (`bf4c804`)** — the `integ-1` five-spec freshness pass (append-only Change-History entries; no code).
- **E2E fixture repair (`2f36da0`)** — F-E2E-1: 13 red pre-existing on-device migration-fixture failures (`MigrationTo4/8/9/10Test`, out of plan scope, `audio_file_paths NOT NULL` bit-rot) fixed → 35/35 green; only the four test files touched.
- **Cleanup (`7e44996`) + documentation (`ddfd0fb`)** — 15 doc workers (companion README, windows-dispatch README, DATABASE-PATTERNS, ~57 inline `@see`/slug/path anchor edits, 0 auto-fixes); F1–F3 doc caveats applied; ADR-slug fix; BuildProbe dead scaffolding removed; `.gitattributes`.

---

## Full issue list (chronological, by block)

| # | Source | ID | Sev | Status |
|---|---|---|---|---|
| 1 | A audit | convention-A-1 (adapter `@see` split) | Nice | resolved W1 |
| 2 | A audit | convention-A-2 (`SystemPromptResolver.create` bypassed) | Nice | resolved W1 |
| 3 | A audit | A-TEST-1 (no in-module test for moved AI-core) | Nice | deferred by design |
| 4 | A audit | A-TEST-2 (`installAuthenticatorCalls` dead) | Nice | resolved W1 |
| 5 | A self-fix | **A3-SF1 (entire A3 uncommitted at HEAD)** | **Critical** | resolved mid-repair `b788fe5` → R-3 |
| 6 | A retry | A3-R1 / A3-I1 (integration target mislabeled `PipelineOrchestrator` vs `DictatePipelineService`) | Important | resolved (doc anchor + label note) |
| 7 | B audit | F1 (Android `put` leaks `GeneralSecurityException` → boot loop) | Important | resolved W1 |
| 8 | B audit | F2–F8 (atomic write, `available`, tests, `SecretStoreModule`, KDoc, …) | Nice ×7 | resolved W1 |
| 9 | B self-fix | B1-SF4 / B2-1 (foreign `:companion` compile break; §2.6 grep completes in C2/C3) | Important | delegated → resolved C2/C3 |
| 10 | C audit | G1 enum-parity, G2 canonical-decimal | Important ×2 | resolved W1 |
| 11 | C audit | G3–G11 (Double-Enum, SourceRef, charset, labels, keyterms no-op, pos collision, provenance test, CHECK gate, test util) | Nice/Imp | resolved W1 |
| 12 | C audit | Y1 `logic-C-1` forward-compat hash-recompute | Important (yellow) | resolved W1 (resolution b) — see O-7 |
| 13 | C re-audit W1 | **C-TEST-2 WindowsDeviceSecret (Important→Critical)** | **Critical** | resolved W2 `d3c6e51` → R-1 |
| 14 | C re-audit W1 | doc-drift-androidaiconfig-retired | Nice | resolved W2 |
| 15 | D audit | logic-D-1 (single-segment take) | Critical | resolved W1 |
| 16 | D audit | plan-and-api-D-1 (usage sink), D-2 (Profile→AiConfig), D-3 (§9.3 UI) | Important ×3 | D-1 resolved; D-2 core resolved (part b transitional → O-2); D-3 resolved W3/D4 |
| 17 | D audit | convention-D-1/-2, logic-D-2/-3/-4, T1 | Nice | resolved W1/W3 |
| 18 | D impl | D3-3 (§9.1 panel profile dropdown), D3-4 (§9.2 shallow editing) | Nice | delegated, accepted v1 deviation |
| 19 | E audit | logic-E-1 (+E-T3) discovery timeout/leak, logic-E-2 UI-scope blocking | Important ×2 | resolved W1 |
| 20 | E audit | E-T1 untracked ports, E-T2 zip-fs flake | Important ×2 | resolved W1 |
| 21 | E audit | convention-E-1/-2/-3, E-T4 | Nice | resolved W1 |
| 22 | E impl | E1-1 (peer FK deferred), E2-1/E3-1 (subscriber runtime) | Important | resolved E2-completion → O-4 |
| 23 | E post-audit | subscriber-store missing | Important | resolved E2-completion `9fbd43d` etc. → R-2 |
| 24 | E post-audit | fingerprint / scheduler restart / AWT tray / enum pkg | Nice ×4 | fingerprint fixed `bae3b34`; rest documented |
| 25 | E2-completion | promptDtoByUuid O(n) scan | Known minor | open follow-up → O-6 |
| 26 | F audit | plan-and-api-F-1 (criterion-9 misattribution), F-2 (one-way ADR links) | Important/Nice | resolved W1 |
| 27 | wave-verify | HEAD uncommitted catalog-sync producers | FAIL (escalation) | resolved slice `3bec2b8` → R-2 |
| 28 | E2E exec | F-E2E-1 (pre-existing migration-fixture bit-rot) | Important | resolved `2f36da0` |
| 29 | INT | integ-1 (spec staleness) | Nice | resolved `bf4c804` → O-3 |
| 30 | docs-final | F1–F4 flags, 3 gaps, 5 source notes | Nice | F1–F3 fixed `7e44996`; F4 + gaps deliberately left → O-7 |

## Full drift list (edits outside strict finding/chunk scope)

| Where | Files outside scope | Kind |
|---|---|---|
| A3 impl | `DictatePipelineService.kt`, `APISettingsActivity.java` | genuine AI-wiring site + required caller update (INTEGRATION_TARGETS mislabeled `PipelineOrchestrator.kt`) — documented, resolved |
| A3-SF1 | full A3 file set (ports/adapters/moves/tests) | uncommitted body committed by mid-repair — commit-scoping, not code drift |
| D repair W1 | `Take.pause()`/`resume()` line start/stop | **undocumented** benign behaviour improvement (spec §4.3) → O-5 |
| D repair W3-1 | `HistoryScreen.kt` `Pager` → primitives | in-file DRY refactor, phone tests green → O-5 |
| E2 slice / completion | ~19 catalog-sync files landed manually | escalation remediation → R-2 |
| INT wave | 5 spec files (+190 append-only lines) | freshness pass → O-3 |
| E2E fix | 4 instrumented migration test files | out-of-plan-scope bit-rot repair → informational |

No drift breached block boundaries destructively; every case is documented in its report.

## Fix families

- **inline-impl fixes:** A3 import cleanup, A3 KDoc port list (2)
- **self-fix (per chunk):** 16 passes; 1 load-bearing (A3-SF1 → mid-repair)
- **mid-chunk repair:** A3-SF1 (`b788fe5`)
- **block-repair waves:** A(1)/B(1)/C(2)/D(3)/E(1)/F(1) = 9 waves, all converged
- **post-audit repair:** E2-completion (9 commits) closing the 1 Important post-audit finding
- **integration-repair:** INT W1 (`bf4c804`, docs freshness) + integration-check (no code)
- **e2e-repair:** F-E2E-1 fixture fix (`2f36da0`)
- **doc/cleanup:** `ddfd0fb` (15 workers) + `7e44996` (flags/cleanup)

## Research files produced (`research/`)

Plan-input (checked in by A1): `bestandsaufnahme.md`, `konzept-skizze.md`, `fragenkatalog.md`.
Per-block specs (SSoT, freshness-passed): `shared-ai-extraktion.md`, `secretstore.md`,
`entitaetenmodell-android.md`, `desktop-host.md`, `peer-katalog.md`.
Finding-driven research: `androidaiconfig-secret-pref-retirement.md` (→ R-1),
`desktop-aiconfig-credential-resolution.md` (→ O-2), `desktop-history-ui-scope.md` (→ O-1),
`desktop-usage-sink-migration.md`, `v3-forward-compat-hash-recompute.md` (→ O-7),
`mid-A3-A3-SF1.md` (→ R-3).

## Block timeline

| Block | Chunks | Audit result | Waves → convergence |
|---|---|---|---|
| A `:shared-ai` | A1 ADRs, A2 pure moves, A3 ports/migration | 4 Nice-to-have; A3-SF1 Critical (commit-scoping) | mid-repair `b788fe5` + W1 `c6a828f` → converged |
| B SecretStore | B1 port+impls, B2 key migration | 1 Important + 7 Nice | W1 `58a88b1` → converged |
| C entity model + Android | C1 codec, C2 Room v11→v12, C3 UI | 4 Important + 8 Nice + 2 yellow; C-TEST-2 escalated to Critical | W1 `9b2038c` + W2 `d3c6e51` → converged |
| D desktop host | D1a schema, D1b capture, D2 hotkey/panel, D3 review/UI (+D4 follow-up) | 1 Critical + 3 Important + Nice | W1 `b99b141`, W2 `3b9f980`, W3 `a5695c5`, D4 `f33fa2a` → converged |
| E peer catalog + sync | E1 offer/server, E2 engine/sync, E3 explorer | 4 Important + Nice; subscriber runtime delegated | W1 `df191be` + slice `3bec2b8` + E2-completion (9 commits) → converged |
| F ADR promotion + docs | F1 | 1 Important + 1 Nice (docs) | W1 `d0d2e19` → converged |
| INT cross-block | — | integ-1 Nice | W1 `bf4c804` → converged; integration-check: no Critical/Important cross-block defect |

## Eliminated audit findings (audit of the audit)

Very low false-positive rate — nearly every raw finding validated as real:
- Block A: 0 eliminated (4/4 real).
- Block B: 0 eliminated; 1 pair merged (plan-api-B-1 ≡ convention-B-1 → F1).
- Block C: 0 eliminated (14/14 real); 1 pair merged (plan-and-api-C-1 + convention-C-2 → G1).
- Block D: 0 eliminated (both HEAD-open findings real; 9 earlier findings all resolved).
- Block E: 0 genuine false positives; 3 plan-and-api findings **removed from the fix list** as valid-but-already-delegated (E2-1/E3-1 block-gate work, not repair-wave fixes), 3 deduped (E-T3 → logic-E-1).
- Block F: 0 eliminated (2/2 real).
- INT: single finding, resolved not eliminated.

## Open at closure (tracked, below escalation threshold)

- **6 manual E2E cases** pending user acceptance: TC-A3 (emulator + mobile-mcp) and TC-W1–W5 (Windows two-process + real-provider smoke). Windows acceptance checklist unsigned.
- **On-device instrumented migration tests** are local-only/never-CI by the repo's pre-existing convention; `MigrationTo12Test` (6/6) and `MigrationTo13Test` (3/3) were run green on `emulator-5554` during Phase-4 E2E, so the Double-Enum CHECK gates are verified at least once.
- **O-6** promptDtoByUuid O(n) scan; **O-7** 3 doc gaps + F4 broken link (foreign plan) deliberately left; older-wave Spec-§8 follow-ups untouched.
- State-file Postponed table is empty; 0 Critical / <5 Important / <10 total postponed.

## Sources read

State file; all per-block `validated-findings.md`, `re-audit-*.md`, and the impl/self-fix
Issues/Deviations/Drift sections for A–F; `reports/D/repair-W3-1.md`, `reports/D/D4-history-ui.md`;
`reports/E/post-audit-3bec2b8.md`, `reports/E/E2-completion.md`; `reports/integration-check.md`,
`reports/INT-re-audit-W1.md`, `reports/INT-repair-W1-1.md`; `reports/wave-verify.md`;
`reports/e2e-runbook.md` (incl. Phase-4 execution results + F-E2E-1), `reports/windows-acceptance-checklist.md`;
`reports/docs-discovery.md` + `reports/docs-final-report.md` + the 15 `docs-*` worker reports
(11 inline groups + 4 prose workers, summarized via the docs-final consolidation);
the per-block `audit-*.md`, the intermediate `repair-W*.md`, and `reports/A/repair-mid-A3-1.md`
(outcomes captured via their block consolidations/re-audits, which are the validated SSoT);
the full commit timeline `048fb37..f33fa2a`.

**Source-coverage self-check:** 131 report files total. Every file is accounted for — either
read directly (state file; all `validated-findings.md`, `re-audit-*.md`, `post-audit-3bec2b8.md`,
`E2-completion.md`, `D4-history-ui.md`, `repair-W3-1.md`, `integration-check.md`, `INT-*.md`,
`wave-verify.md`, `e2e-runbook.md`, `windows-acceptance-checklist.md`, `docs-final-report.md`;
per-chunk `*-impl.md`/`*-selffix.md` Issues/Deviations/Drift sections A–F) or read **through its
block consolidation** as a documented gap: the raw four-lens `audit-{plan-and-api,convention,logic,test}.md`,
the intermediate `repair-W1-*/W2-*.md` and `repair-mid-A3-1.md`, and the 16 `docs-inline-*`/`docs-worker-*`/`docs-discovery.md`
workers were read via the `validated-findings.md` / `re-audit-*.md` / `docs-final-report.md`
consolidations, which quote and classify every raw finding. The consolidations are the designed
SSoT; no finding is dropped. No report file is unaccounted for.
