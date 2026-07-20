# Integration Check — desktop-companion-v1 (cross-block, A–F)

**Timestamp:** 2026-07-20T17:25:00+02:00
**HEAD:** 2f36da0 · **Range:** c46cfe8..HEAD
**Blocks:** A (shared-ai extraction) · B (SecretStore) · C (entity model + Android) ·
D (desktop host) · E (peer catalog + sync) · F (ADR promotion + docs)
**Scope:** the seams *between* blocks only — imports/types across boundaries, wiring
completeness, producer/consumer API contracts, convention drift across blocks,
deviation aggregate, postponed aggregate. Within-block issues (owned by the per-block
audits) and code style (owned by the self-fix passes) are out of scope. **Fixes nothing.**

## Verdict

**No Critical or Important cross-block integration defects.** Every high-risk cross-block
seam that the per-block audits flagged as *delegated / deferred / latent* has since been
closed by the post-audit E2-completion work and the D/C repair waves, and is now
verified at HEAD both at the code level and by compilation. One benign, documented
Nice-to-have residue is recorded (spec-vs-implementation staleness across all five specs).

The escalation threshold for postponed issues (§axis 6: ≥1 Critical, ≥5 Important, or
≥10 total) is **not** reached.

---

## Axis 1+2+3 — Imports/types, wiring completeness, API contracts across boundaries

All verified clean at HEAD:

### A → {B,C,D,E}: `:shared-ai` core + ports
- Ports `AiConfig`/`UsageSink`/`ProxyConfig`/`AudioDurationReader` + `SecretStore` are
  consumed by both hosts. Companion `production()` wires the **entity-backed**
  `ProfileBackedAiConfig` + `ConfigProfileSource` (the D↔C↔B seam); the transitional
  `CompanionAiConfig`/`TransitionalProfileSource` are retired (dead in production, KDoc
  confirms). `usageSink = SqlDelightUsageSink(database)` — the D1b→D3 usage-sink
  deferral (`plan-and-api-D-1`) is closed; `usage` table present in `Companion.sq`.

### C1 → C2/D3/E1: entity model + wire enums
- `:shared` `config/ConfigEnums.kt` is the single source for the seven wire enums
  (`ProviderType`, `ProviderKind`, `ModelFunction`, `AmbiguityModeValue`,
  `PromptSelectionMode`, `Visibility`, `SubscriptionMode`) — D5.a honored (no `AIProvider`
  move, no `:shared-ai`→`:shared` edge). Domain enums stay in `:shared-ai`/`:app`.
- **Parity enforced on BOTH hosts** (not just `:app`): `ConfigWireEnumParityTest` (app) +
  `CompanionConfigWireEnumParityTest`, `CompanionSchemaParityTest`,
  `CatalogCheckConstraintParityTest`, `ConfigEntityCheckParityTest` (companion). Drift is
  test-prevented, matching the repo's ADR-0016 doctrine.
- Migrations follow the D5 allocation: `2.sqm` (D1a), `3.sqm` (D3 config-entity tables),
  `4.sqm` (E1 peers/subscriptions/access_log); Room reaches **v13** (E2-completion's
  peers/subscriptions tables). Schema JSON exports 1..13 present.

### C3/E2: shared receive-path helpers (no per-host drift)
- `CatalogPayloadGraft` lives once in `:shared` and is consumed by **both**
  `AndroidCatalogSubscriberStore` and `SqlDelightCatalogSubscriberStore` — the
  hash-critical graft is a single implementation (a per-host copy would drift the
  recompute-hash and break fork-dedup).
- `CatalogImport.upsertPromptRow` is `internal` and shared by the C3 v3-file-import path
  and the E2 Android sync receive path — one write path, no divergence.

### E1 → E2: catalog wire producer/consumer contract
- The offer side (`CatalogService`/routes, `4.sqm`) and the subscriber side (engine +
  both host stores) agree on DTO shapes, root-hash, and credential isolation, validated
  end-to-end by the **two-peer real-HTTP** `CatalogSyncE2ETest` (subscribe→sync→update→
  notify + idempotency/one-GET + fork-protection) — green per `E2-completion.md`.

### D3 → UI: desktop-history read API has a consumer
- `plan-and-api-D-3` ("read API with zero UI consumers") is **resolved**:
  `DesktopHistoryViewModel` + a Desktop tab in `HistoryScreen` consume
  `pageDesktopHistory`/`countDesktopHistory`/`desktopHistoryEntry`, nav-wired via
  `App.kt` → `Destination.HISTORY`, with the `container.desktopSessions == null`
  (`forTest` graph) guard handled.

### Self-containment / build
- **No untracked source files** (`git status` shows only docs/reports untracked). The
  earlier `wave-verify.md` FAIL (committed code importing untracked catalog-sync
  producers) was at HEAD `d0d2e19`, **before** commit `3bec2b8` + the E2-completion
  slice landed — it is stale and resolved.
- `:shared`, `:shared-ai`, `:companion` `compileTestKotlin` → **BUILD SUCCESSFUL** at
  HEAD (Linux-compilable modules; `:app` needs the Android SDK but its unit suite is
  documented green — 2417 tests Block A, full green after E2-completion + `2f36da0`).

### SecretStore namespace agreement (B consumed cross-block)
- Namespaces are consistent and centralized: `credential` (C2 config credentials),
  `legacy` (B2 migration source), `pairing` (Windows device secret), `peer` (E2 peer
  pairing secret). App and companion both define `PEER_NAMESPACE = "peer"` in mirror
  `PeerSecrets` objects (the accepted per-platform-mirror pattern, D3).
- **Resolved latent Critical** (was flagged in `research/androidaiconfig-secret-pref-retirement.md`):
  the B2 migration deletes `Pref.WindowsDeviceSecret` on every app start; C2/C3 were
  supposed to re-point its readers/writers. At HEAD they are: `WindowsTarget.kt`,
  `WindowsPairingActivity.java`, `PipelinePrefMirror.kt` no longer name the pref, reading
  the device secret via `SecretStore`/`PairingSecrets.DEVICE_SECRET_REF`. The guard
  `NoLegacyKeyReadTest.secretPrefs_areReferencedOnlyInDefinitionAndMigration` is now an
  **active** `@Test` (the `@Ignore` is gone), so the secretstore §2.6 invariant is
  test-enforced. `WindowsDeviceSecret` appears only in `DictatePrefs.kt` (definition) and
  `SecretsMigration.kt` (allow-listed migration source).

---

## Axis 4 — Convention drift across blocks

No cross-block convention defect rises to a finding. Same-operation-done-two-ways items
that the block audits raised (timestamp formatting in `ui/peers` vs `asTime()`, status-chip
casing, charset spelling, canonical-decimal helper duplication) are **within-block**
convention nits already routed to the per-block repair waves — not cross-block seams.
Cross-cutting conventions that *do* span blocks are consistent: the Double-Enum pattern,
the `SecretRef` namespace scheme, the port-mirror-per-platform pattern, and the
`ProtocolCodec`/canonical-serialization single-door rule all hold uniformly A→E.

---

## Axis 5 — Deviation aggregate

There **is** a systemic, repeating deviation pattern across blocks — but it is benign and
already sanctioned:

> Where a spec prescribed a finer-grained structure, the implementation consolidated
> toward a single owner / DRY and documented + parity-tested the consolidation.

Instances: single `shared.config.*` enums instead of the specs' parallel `catalog.*Wire`
copies (E); SQLDelight migration numbers reallocated to `2/3/4.sqm` (D5); the companion
`usage` table moved D1b→D3; management UI consolidated into
`ui/config/ManagementScreen` + `ConfigViewModel` instead of the specs' separate
`ui/profiles`/`ui/models`/`ui/prompts` directories (D). Each is endorsed by D5 or a
documented D4 deviation, tested by a parity/characterization test, and annotated in code
headers + impl reports. This is the specs (authored **before** the D5 cross-spec
decisions) being intentionally superseded — not a plan-spec **mismatch** the way the axis
warns about. Recorded as a single Nice-to-have documentation-freshness residue
(`integ-1`) because the five specs remain the nominal per-block SSoT (plan §5) yet no
longer describe the built enum packages, migration numbers, or UI layout — relevant to
the F-stage EN translation / archival.

---

## Axis 6 — Postponed aggregate

The state file's Postponed table is **empty**. The genuinely-open items at closure are all
known, tracked, and below the escalation threshold:

- **Instrumented migration tests** `MigrationTo11/12/13Test` — device/emulator-only
  (`connectedDebugAndroidTest`), matching the pre-existing sibling convention; JVM side
  covered by `MigrationTo*MetadataTest` + store tests. Not "postponed" in the escalation
  sense.
- **Manual E2E** TC-A3 (emulator+mobile-mcp) and TC-W1..W5 (Windows two-process +
  real-provider smoke) — scheduled user acceptance at closure (Q3b/Q4a), Windows device
  available at the F timepoint.
- **Block-C Y1** `v3-forward-compat-hash-recompute` — a documented, dormant design item
  (the SAF file-import recompute rejects future additive-field v3 files; the Block-E peer
  path uses `CatalogCodec.decode` without recompute and is unaffected). A "accept +
  document" research verdict, not a formal postponement.

**0 Critical postponed · <5 Important · <10 total → threshold not reached.** No Critical
finding.

---

## Findings

| ID | Sev | Class | Summary |
|---|---|---|---|
| integ-1 | Nice-to-have | green | Five per-block specs (`research/{shared-ai-extraktion,secretstore,entitaetenmodell-android,desktop-host,peer-katalog}.md`), still the nominal SSoT per plan §5, are partially stale vs. the built structure (enum packages, `2/3/4.sqm` numbering, consolidated `ui/config` layout, D1b→D3 usage table). Consistent, documented deviations — but worth a freshness pass before the F-stage EN translation/archival so the archived specs match the code. |

No yellow findings (single auditor; classified directly). No Critical/Important.
