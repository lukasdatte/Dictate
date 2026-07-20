# Docs Final — Cross-Doc Sanity + Link Auto-Fix + Aggregate Report

**Date:** 2026-07-20T17:25:00+02:00
**Plan:** `docs/plans/2026-07-19 - desktop-companion-v1/desktop-companion-v1.md`
**Range:** `c46cfe8..HEAD`
**Agent:** docs-final (finalize)
**Activation:** full · single pass, no sub-agents

Runs after the docs-discovery classifier, 4 update/verify workers, and 11 inline-anchor
workers. Three jobs in one pass: cross-doc sanity, link resolution + conservative
auto-fix, aggregate report.

---

## 1. What was updated / converted (worker aggregate)

### Prose docs (update / verify workers — 4)

| Doc | Outcome | Substance |
|---|---|---|
| `docs/architecture/windows-dispatch/README.md` | **updated** | §1a.0 `:shared` module box rewritten (grew `client/CatalogClient`, `sync/CatalogSyncEngine·CatalogSubscriberStore`, full `config/` package); a `[!NOTE]` on the sibling peer-catalog / config-sharing wire family; §3 code pointers expanded (`WireResponse.parseWire/classifyWireError`, `DispatchOutcomeMapper`, sibling-family bullet); §5 References gained an ADR-0030/0034 sibling bullet. |
| `companion/README.md` | **updated** | 3 factual gaps: `Main.kt` `--minimized` flag + `CompanionBootstrap` delegation; new `CompanionBootstrap.kt` tree line; migrations line corrected to `1=key-command chords, 2=parity+dispatch_state, 3=config entities, 4=peers` (`1.sqm` was omitted). |
| `docs/DATABASE-PATTERNS.md` | **updated** | Double-Enum "Applied columns" table extended with all v12/v13 columns (provider/credential/model/profile/subscription enums, the shared `visibility`/`subscription_mode` envelope, `subscriptions.mode` subset-CHECK); enum-home blockquote (why they live in `:shared`, not `app/database/entity/`); retrofit-debt paragraph updated (v12/v13 born with CHECK). |
| `CLAUDE.md` (repo root) | **no-change-needed** | Verification pass — four-module topology, `:shared-ai` package note, SecretStore + config-entity convention bullets all verified current against source. |

### Inline anchors (inline-anchor workers — 11 groups)

| Group | Outcome | Anchors touched |
|---|---|---|
| `shared-config-wire` | edited | +5 `@see peer-katalog.md §X` + `@see 0034` on the 5 new peer-catalog family files (`CatalogPayloadGraft`, `CatalogClient`, `CatalogSubscriberStore`, `CatalogSyncEngine`, `NotificationPort`). |
| `shared-ai-core` | edited | +6 `@see 0028-shared-ai-module.md` on the 5 ports + `AIOrchestrator`; `SecretStore.kt` stale draft slug `adr-shared-ai-module` → `ADR-0028`, +`@see 0029`. |
| `app-config` | edited | 2 stale-path fixes (`§8.5.1` → `§8.5` in `PromptHashing`, `ConfigEntityMigration`); +3 `@see` tags (`ConfigSecrets`, `PromptProvenance`, `SourceRefMapping`) → 16/16 uniform. |
| `app-secrets` | edited | `SecretsMigration` draft slug `adr-secret-store` → `ADR-0029`; +2 `@see 0029` (migration + `AndroidKeystoreSecretStore`). |
| `app-peers` | edited | +10 `@see peer-katalog.md §X` module anchors + 1 `@see 0034` at the subscriber store (was 0/10 — the discovery gap). |
| `app-windows` | no-change-needed | Verification pass; discovery inventory was stale (headers + ADR refs already present in-range). |
| `companion-pipeline-capture` | no-change-needed | Fully anchored in prose-KDoc form; discovery "sparse" count is a `@see`-token artifact. |
| `companion-data` | edited | +9 class-level `@see` blocks (spec § + governing ADR) on the substantive repositories. |
| `companion-catalog-server-domain` | edited | +13 `@see` anchors (spec § + ADR where governing) on new services/ports/routes/policies. |
| `companion-ui` | edited | +8 class/composable `@see` anchors (ADR-0030/0032/0033/0034/0035 + spec §) at one decision point per subsystem. |
| `companion-ai-secrets-platform` | edited | 1 stale gotcha reworded (`CompanionAiConfig.apiKey()` "until D3" → "always keyless by design"); +3 `@see 0029` on the companion SecretStore backends (parity with `:app` twins). |

**Net:** 4 prose docs (3 edited, 1 verified) + ~57 inline-anchor edits across 9 edited
groups (2 groups verify-only). No source logic touched anywhere; all inline diffs are
KDoc/comment-only.

---

## 2. Job 1 — Cross-doc sanity

Read the four SSoT docs end-to-end and cross-checked terminology, package inventories,
migration tables, and ADR references. **No hard contradictions.** Three soft
drift/clarity items to flag (domain judgment — not auto-resolved):

### F1 — CLAUDE.md `:shared-ai` package generalization vs the `.core` exception
CLAUDE.md states `:shared-ai` is *"package `net.devemperor.dictate.ai`"* (§Module Topology
and §AI Abstraction Layer). But `AmplitudeProcessor` was moved into `:shared-ai` under the
**retained** package `net.devemperor.dictate.core` (a package-preserving move, plan
decision D5.e) — confirmed by the `companion-pipeline-capture` worker
(`JavaSoundAudioCaptureService` imports `net.devemperor.dictate.core.AmplitudeProcessor`)
and noted by the `root-claude-md` worker. The blanket "package `.ai`" over-generalizes.

> Recommendation: add a one-clause caveat to CLAUDE.md, e.g. *"package
> `net.devemperor.dictate.ai` (with `AmplitudeProcessor` retained under
> `net.devemperor.dictate.core` from its `:app` origin, per D5.e)."* Minor; not
> auto-applied because the exact wording is authorial.

### F2 — DATABASE-PATTERNS migration table scope vs companion README
`companion/README.md` Module Layout now lists `1=key-command chords` among the migrations.
`docs/DATABASE-PATTERNS.md` → "Migration-number assignment (desktop-companion-v1)" starts
at `2.sqm`, and its Double-Enum "Applied columns" table does **not** list the
`key_command_chords` column. This is **consistent by design** — `1.sqm` (key-command
chords) belongs to the *keyboard-action-engine* plan, not desktop-companion-v1 — but a
reader cross-referencing the two docs sees an apparent omission.

> Recommendation: add a one-line scope note under the DATABASE-PATTERNS migration table,
> e.g. *"`1.sqm` (key_command_chords) is owned by the keyboard-action-engine plan; this
> table scopes desktop-companion-v1 (2–4)."* Not auto-applied (cross-plan wording).

### F3 — `api_credentials` asymmetry between Room and companion SQLDelight
(Raised by the `database-patterns` worker.) The Android Room schema has an
`api_credentials` table; the companion SQLDelight schema deliberately has none
(credentials handled differently on desktop). The Parity section correctly lists only
`provider_configs`/`model_refs`/`prompts`/`profiles`/`profile_prompts` as the companion
mirror — **no contradiction**, but the intentional omission is implicit.

> Recommendation: optionally add one sentence to the SQLDelight-Parity section making the
> `api_credentials` omission explicit. Cosmetic.

---

## 3. Job 2 — Link resolution + auto-fix

Scanned the four touched prose docs (internal TOC anchors, cross-doc links) and the
inline `@see` anchors reported by the 11 inline workers.

**Auto-fixes applied: 0.** No safe auto-fix case triggered — the doc updates introduced
no moved-target or renamed-anchor breakage. Verification performed:

- **All doc-level cross-links resolve** — `docs/DATABASE-PATTERNS.md` (+ anchor
  `#sqldelight-parity-companion`), `docs/runbooks/companion-windows-release.md`, all five
  `research/*.md` specs, the plan file, and the pre-existing
  `b3-cleanup-cascade-and-backfill-policy.md` all exist on disk.
- **All 21 referenced ADRs resolve** (0009/0011/0012/0013/0014/0015–0020, 0023, 0025,
  0028–0035) — spot-verified against `docs/decisions/`.
- **DATABASE-PATTERNS internal TOC anchors** all map to headings.
- **Inline `@see` targets** self-verified by each worker; the spec sections and ADR files
  they cite exist (independently confirmed for the ADR set).

**One broken link — flagged, not auto-fixed (F4):**
`docs/architecture/windows-dispatch/README.md:264` (§4b) references `plan
tmp/plan-keyboard-action-engine.md`. `tmp/` is gitignored and the file is absent. This is
**pre-existing** (commit `f1ae6f4`, the *keyboard-action-engine* plan, outside this
`c46cfe8..HEAD` range) and outside the windows-dispatch worker's edit footprint (§1a.0/§3/§5
only). Per the auto-fix table a deleted/ephemeral target is a flag, not a fix.

> Recommendation: the keyboard-action-engine plan owner should archive that plan into
> `docs/plans/YYYY-MM-DD - keyboard-action-engine/` and repoint §4b, or drop the bare
> `tmp/` path. Do not silently delete the reference — §4b is load-bearing architecture prose.

---

## 4. Job 3 — Flags, gaps, source notes

### Documentation gaps (missing docs — follow-up recommendation: **default no**)

Carried from discovery; none block the plan (each has an interim home in an ADR + spec):

1. **Peer-catalog subsystem has no architecture overview.** The sibling Windows-Dispatch
   subsystem has `docs/architecture/windows-dispatch/README.md`; the comparably large
   peer-catalog + config-sharing subsystem (spans `:shared/config`+`:shared/sync`,
   `:app/peers`, `:companion/catalog`) is covered only by ADR-0034 + `research/peer-katalog.md`.
   Candidate: `docs/architecture/peer-catalog/README.md`. *(The windows-dispatch §1a.0 NOTE
   / §3 / §5 sibling pointers should be re-targeted to this overview if it lands.)*
2. **Config-entity model has no architecture overview.** ADR-0030 + spec + one CLAUDE.md
   bullet are the only prose for a model spanning `:shared/config`, `:app/config`, and the
   companion entity tables. Candidate: `docs/architecture/config-entity-model.md`.
3. **`:shared` and `:shared-ai` have no module README.** `:companion` ships a full
   `companion/README.md`; the two new/expanded pure-JVM modules have none. Candidates:
   `shared/README.md`, `shared-ai/README.md` (Code-Pattern READMEs next to the code).

> Recommendation: **no auto-generation.** If desired, a single small follow-up doc plan via
> `feature-planning` could produce items 1–3 together (they share the config-sharing story).

### Source-code notes (from worker `notes_for_final` — out of doc scope, surfaced for a cleanup pass)

- **Stale draft ADR slug in two `:app` XML resources** — `adr-secret-store` should be
  `ADR-0029` in `app/src/main/res/xml/data_extraction_rules.xml:11` and
  `app/src/main/res/xml/backup_rules.xml:13` (`app-secrets` worker; the `.kt` occurrences
  were already fixed in-range).
- **Pre-existing dangling inline anchors in `app/windows`** (`app-windows` worker):
  `WindowsTarget.kt:16` "(purity rule V10)" resolves to no rule anywhere;
  `DispatchOutcomeMapper.kt:33` bare "§6.1" does not name its document. Both pre-existing,
  outside this plan's anchor footprint.
- **Dead scaffolding** — `companion/BuildProbe.kt` (`CompanionBuildProbe`) self-documents
  "Delete this file once `wd-8` lands"; `wd-8` (the `ui/` module) has landed
  (`companion-readme` worker). Referenced only by its own test.
- **Minor efficiency smell** — `AndroidCatalogSubscriberStore.promptDtoByUuid()` does an
  O(n) `getAll().firstOrNull { it.uuid == uuid }` full-table scan per prompt update; a
  `promptDao().byUuid(uuid)` query would be cleaner (`app-peers` worker).
- **Cosmetic git-diff** — `companion/domain/CatalogService.kt` displays as binary in
  `git diff` (UTF-8 `§`/em-dash heuristic); a `.gitattributes` `*.kt text` entry would fix
  the display (`companion-catalog-server-domain` worker).

### ADR flags

**None.** All eight plan-scoped ADR drafts were promoted this range (0028–0035) and
ADR-0015 received its fourth-module decision-history entry (discovery ADR gap-check: clean).

### Knowledge-skill flags

**None.** No worker reported a missing/misaligned `knowledge-*` pattern.

---

## 5. Summary

- **Auto-fixes applied:** 0 (no safe within-set breakage; the one broken link is
  pre-existing + ephemeral-target → flagged).
- **Flagged (4):** F1 CLAUDE.md `.core` package caveat · F2 DATABASE-PATTERNS migration
  scope note · F3 `api_credentials` asymmetry clarity · F4 `tmp/plan-keyboard-action-engine.md`
  broken link. Plus 5 source-code cleanup notes (out of doc scope).
- **Gaps (3):** peer-catalog architecture overview · config-entity-model architecture
  overview · `:shared` / `:shared-ai` module READMEs. Follow-up doc plan: default **no**.
- **ADR flags / knowledge-skill flags:** none.
