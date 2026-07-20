# Docs Discovery + Classification — desktop-companion-v1

**Date:** 2026-07-20T17:25:00+02:00
**Plan:** `docs/plans/2026-07-19 - desktop-companion-v1/desktop-companion-v1.md`
**Range:** `c46cfe8..HEAD`
**Activation:** full
**Agent:** docs-discovery (finalize)

## Scope summary

457 files changed (+45097 / −2241). This plan introduces two new/expanded
pure-JVM modules and a large desktop module:

- **`:shared`** — gained `config/` (catalog entities, `CanonicalJson`,
  `CatalogCodec`, `ContentHash`, `ConfigEnums`, `ConfigValidations`) and
  `sync/` (`CatalogSyncEngine`, `CatalogSubscriberStore`, `NotificationPort`)
  on top of the existing wire protocol/client.
- **`:shared-ai`** — new fourth module; the AI core (orchestrator, runners,
  conversation, prompt building, `AmplitudeProcessor`) moved out of `:app`
  behind four ports (`AiConfig`, `UsageSink`, `ProxyConfig`,
  `AudioDurationReader`) + the `SecretStore` port.
- **`:app`** — new subsystems `config/` (entity model + Room tables 12→13),
  `secrets/` (Keystore AES-GCM SecretStore + migration), `peers/`
  (subscriber sync + read-only explorer), plus `windows/` dispatch touch-ups.
- **`:companion`** — Compose Desktop host: capture, pipeline reducer, hotkey,
  panel/management/peer UI, Ktor catalog routes, SQLDelight persistence
  (migrations 2/3/4), DPAPI/file SecretStore, headless mode.

**Block F already refreshed the SSoT docs** (CLAUDE.md module topology +
conventions, `docs/DATABASE-PATTERNS.md` SQLDelight-parity section,
`companion/README.md`, ADR promotions 0028–0035, ADR-0015 decision-history).
The `update` items below are therefore mostly **verification passes** against
the final implemented reality (later repair/E2E waves may have moved schema
and code after the Block-F doc chunk ran), plus one genuinely stale diagram.

## File → doc mapping (update / convert work items)

| Slug | Action | Target doc | Why | Convertibility |
|---|---|---|---|---|
| `windows-dispatch-overview` | update | `docs/architecture/windows-dispatch/README.md` | **Genuinely stale.** The `:shared` module box in §1a.0 lists only `protocol/ client/ auth/ sync/`; `:shared` now also owns `config/` (catalog entities, `CanonicalJson`, `CatalogCodec`, `ContentHash`, `ConfigValidations`) and `sync/CatalogSyncEngine`. Code pointers in §3 reference `DispatchClient`/`DispatchError`/`WireResponse` which changed shape. Add a pointer to the peer-catalog family (ADR-0034) as a sibling on the same wire stack. | n/a |
| `companion-readme` | update | `companion/README.md` | Verify the module-layout table and subsystem list against the final tree (migrations table says `{1..4}.sqm`; footprint shows 2/3/4.sqm authored this range — confirm 1 exists; confirm `ui/devices`, `ui/pairing`, `cli/PairCli` still present). Already comprehensive from Block F — expect a light pass. | n/a |
| `database-patterns` | update | `docs/DATABASE-PATTERNS.md` | Verify the doc covers the final Android schema (Room 12→13: `MigrationTo12`, `MigrationTo13`, config-entity tables, peers/subscriptions tables) and the companion SQLDelight schema (`3.db`/`4.db`/`5.db`, migrations 2/3/4, parity tests). Touched in Block F — verify against later repair-wave schema. | n/a |
| `root-claude-md` | update | `CLAUDE.md` (repo root) | Verify the four-module topology, `:shared-ai` package note, and the SecretStore/config-entity convention bullets still match the final code (they were written in Block F and look current — light verification). | n/a |

No spec-conversion candidates: no file under the plan's `research/` carries a
`## Specification` section (the specs are prescriptive but are the fachliche
SSoT referenced by ADRs + module README, deliberately kept in the plan folder,
not promoted to `docs/architecture/`).

## Inline-anchor inventory (module header / `@see` plan-ADR tags / gotcha)

Sampled `@see` coverage per subsystem (files with ≥1 `@see` / total `.kt`):

| Subsystem | `@see` coverage | State |
|---|---|---|
| `shared/config`+`protocol`+`client`+`sync` | 6/28 | partial — new catalog files need anchors |
| `shared-ai` | 12/45 | partial — moved files carry old headers; new ports need `@see ADR-0028` |
| `app/config` | 13/16 | good — mostly anchored (spot-check gotchas) |
| `app/secrets` | 3/4 | good |
| `app/peers` | 0/10 | **absent** — new subsystem, no `@see` at all |
| `app/windows` (touched: `DispatchOutcomeMapper`, `WindowsAutoSend`) | 0/10 | `DispatchOutcomeMapper` has a good header+ADR ref; `WindowsAutoSend` lacks a module header |
| `companion/**` | 8/155 | **sparse** — huge new module, most files header-less |

Files carry mixed state: some (e.g. `DispatchOutcomeMapper.kt`,
`CatalogSyncGateway.kt`, most `app/config`) have strong headers with ADR/spec
refs; new peers/companion files are largely bare. Grouped below by subsystem.

## Inline groups (worker units)

1. `shared-config-wire` — `shared/src/main/kotlin/net/devemperor/dictate/shared/{config,protocol,client,sync}/`
2. `shared-ai-core` — `shared-ai/src/main/kotlin/net/devemperor/dictate/ai/` (ports + runners + conversation)
3. `app-config` — `app/src/main/java/net/devemperor/dictate/config/`
4. `app-secrets` — `app/src/main/java/net/devemperor/dictate/secrets/`
5. `app-peers` — `app/src/main/java/net/devemperor/dictate/peers/` (highest need: 0/10)
6. `app-windows` — `app/.../windows/{DispatchOutcomeMapper,WindowsAutoSend}.kt` + `preferences/WindowsTarget.kt`
7. `companion-pipeline-capture` — `companion/.../{pipeline,capture,hotkey}/`
8. `companion-data` — `companion/.../data/` + `companion/src/main/sqldelight/`
9. `companion-catalog-server-domain` — `companion/.../{catalog,server,domain}/`
10. `companion-ui` — `companion/.../ui/`
11. `companion-ai-secrets-platform` — `companion/.../{ai,secrets,platform}/`

## Gaps (flag only — no auto-generation)

- **Peer-catalog subsystem has no architecture overview.** The sibling
  Windows-Dispatch subsystem has `docs/architecture/windows-dispatch/README.md`;
  the comparably large peer-catalog + config-sharing subsystem (spans
  `:shared/config`+`:shared/sync`, `:app/peers`, `:companion/catalog`) is
  covered only by ADR-0034 + the `research/peer-katalog.md` spec. Candidate:
  `docs/architecture/peer-catalog/README.md` (cross-cutting orientation guide).
- **Config-entity model has no architecture overview.** ADR-0030 + the spec +
  a CLAUDE.md bullet are the only prose for a model spanning `:shared/config`,
  `:app/config`, and the companion entity tables. Candidate:
  `docs/architecture/config-entity-model.md`.
- **`:shared` and `:shared-ai` have no module README.** `:companion` ships a
  full `companion/README.md`; the two new/expanded pure-JVM modules have none
  (only ADR-0016/0028 + specs). Candidates: `shared/README.md`,
  `shared-ai/README.md` (Code-Pattern READMEs next to the code).

## ADR gap-check

No gaps. All eight plan-scoped ADR drafts were promoted this range
(0028 shared-ai, 0029 secret-store, 0030 config-entity, 0031 desktop host,
0032 panel, 0033 review, 0034 peer-catalog, 0035 history-parity); ADR-0015
received its decision-history entry (fourth module). The D5 cross-spec
decisions are all refinements folded into existing ADRs (0015/0016/0028/0030/
0034/0035), not standalone new decisions. `adr_flags` is empty.

## Notes

- `docs/runbooks/agentic/` does not exist → step 4b (agentic runbooks) N/A.
  The plan's manual E2E acceptance lives in `reports/e2e-runbook.md` +
  `reports/windows-acceptance-checklist.md` and `docs/runbooks/companion-windows-release.md`
  (unchanged this range).
- `docs/architecture/e2e-emulator.md` is untracked (created outside this
  commit range by the E2E workflow) — not a plan-footprint doc.
- `docs/architecture/state-architecture/*` references to `PipelinePrefMirror`
  are **pre-existing** (the file predates this plan); not stale from this range.
