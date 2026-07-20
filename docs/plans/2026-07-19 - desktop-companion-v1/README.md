# desktop-companion-v1 — Archive README

**Title:** Grow the Dictate companion into a standalone desktop dictation host
(Compose panel, global hotkey, full pipeline), introduce an entity/profile
configuration model with an immediate Android migration, extract the AI core into
a shared pure-JVM module, and distribute configuration between hosts via a
pull-only peer catalog over the existing Tailscale wire.

**Status:** Archived 2026-07-20 — **Implemented; all four modules compile and their
unit suites are green. 6 manual E2E cases + the Windows acceptance checklist remain
open for human-in-the-loop sign-off (see "Open manual acceptance").**

**Created:** 2026-07-19 · **Branch/worktree:**
`feature/desktop-companion-v1` · **Base:** `main@048fb37`.

## Summary

This plan turns the passive Windows-dispatch companion into a first-class desktop
dictation host and, in the same run, replaces the loose-`SharedPreferences`
configuration surface with a proper entity model shared byte-for-byte between phone
and desktop. Four things were built together because they are one architecture: (1)
a **fourth pure-JVM module `:shared-ai`** that holds the AI core (orchestrator,
runners, prompt building, amplitude) behind platform ports, consumed by both `:app`
and `:companion` (ADR-0028); (2) a **project-wide `SecretStore` port** that encrypts
every secret at rest on every host (Android Keystore / Windows DPAPI / POSIX-`0600`
fallback) and distinguishes "no secret" from "decrypt failed" (ADR-0029); (3) a
**configuration entity model** in `:shared` whose canonical serialization +
`contentHash` *is* the v3 file/wire format, with a Room v11→v13 migration on Android
(ADR-0030); and (4) the **desktop host + peer catalog** — a Compose mini-panel with a
global hotkey and its own slim orchestrator (ADR-0031/0032/0033) plus pull-only
catalog sharing over the existing wire stack (ADR-0034), with full SQLDelight
session-schema parity retiring the legacy `received_texts` table (ADR-0035). The run
was executed with `implement-long-plan-v3` (6 blocks A–F, 16 chunks) and is fully
implemented and green at HEAD.

## Implementation outcome

- **6 blocks / 16 chunks** (A1–A3 shared-ai extraction · B1–B2 SecretStore · C1–C3
  entity model + Room migration · D1a/D1b/D2/D3 desktop host + panel + review ·
  E1–E3 peer catalog + sync · F1 Windows acceptance) **+ 1 follow-up chunk D4**
  (unified history UI data layer; the §9.3 Compose surface was scoped as a dedicated
  follow-up — see O-items in the implementation report).
- **59 commits · 484 files changed (+47,173 / −2,377)** over `main@048fb37`,
  including two pre-run groundwork commits (`62bf912` local emulator E2E infra,
  `c46cfe8` androidTest schema-assets fix).
- **Tests:** all four modules (`:app`, `:shared`, `:shared-ai`, `:companion`) compile
  and their unit suites pass at HEAD; `:companion:verifySqlDelightMigration` armed and
  green; Room final `@Database(version = 13)`. **Auto E2E 10/10** (8 companion/shared
  JVM + TC-A1 emulator + TC-A2 Robolectric). The postponed-issue escalation threshold
  (≥1 Critical / ≥5 Important / ≥10 total) is **not** reached.
- **8 plan-scoped ADR drafts promoted** to `docs/decisions/0028–0035` (all
  `Accepted`, bidirectional plan ↔ ADR links in place). ADR-0015's Decision-History
  gained the `:shared-ai` Kotlin-ceiling entry.
- **Three 🔴 needs-research arcs, all resolved and re-verified at HEAD**
  (see `reports/implementation-report.md`): R-1 the Block-C `WindowsDeviceSecret`
  plaintext-pref regression (repair wave 2 `d3c6e51`), R-2 the late E2 catalog-sync
  slice that failed wave-verify uncommitted (`3bec2b8` + 9-commit E2-completion), R-3
  the mid-chunk A3-SF1 incoherent-HEAD partial commit.

## Comparison context

- **What changed vs. before:** the companion was a passive dispatch target reading
  plaintext prefs; it is now a recording + pipeline host with its own orchestrator,
  panel, and hotkey, sharing the *same* AI core and config format as the phone.
  Configuration moved from loose `SharedPreferences` keys to `:shared` entities with a
  canonical `contentHash` format; all secrets moved behind the `SecretStore` port
  (zero plaintext secret-pref reads outside the allow-listed definition + migration,
  regression-locked by `NoLegacyKeyReadTest`).
- **What was deliberately NOT changed:** the wire/transport stack (peer catalog is
  **pull-only** over the existing Tailscale HTTP stack — no new push protocol); the
  Kotlin ≤ 2.1.20 compiler ceiling (ADR-0015) which now also governs `:shared-ai` and
  every new dependency; the `:shared` wire-purity invariant (no Android/Ktor/coroutines,
  machine-enforced). `AmplitudeProcessor` was moved package-preserving under
  `net.devemperor.dictate.core` (D5.e), so `:shared-ai` is *predominantly* but not
  exclusively the `.ai` package.
- **Scope boundary / known follow-ups (non-blocking):** the unified history UI Compose
  surface (§9.3) landed only its data layer (D4) — the visual surface is a tracked
  follow-up. One below-threshold **Known minor** (O-6) survives from the E2-completion
  slice, plus a short tail of doc-freshness Nice-to-haves (e.g. a stale `WindowsTarget.from`
  mention in ADR-0014, doc-only, non-runtime). All are recorded in the implementation
  report, none block the plan.

## Implementation reports

Full run artefacts in [`./reports/`](./reports/):

- [`implementation-report.md`](./reports/implementation-report.md) — Phase-4.7
  aggregate (🔴 3 / 🟠 7 / 🟢 8 fix families; 30 issue rows · 7 drift rows; the R-1/R-2/R-3
  arcs with HEAD-verified resolutions).
- [`integration-check.md`](./reports/integration-check.md) +
  [`INT-re-audit-W1.md`](./reports/INT-re-audit-W1.md) /
  [`INT-repair-W1-1.md`](./reports/INT-repair-W1-1.md) — cross-block integration pass.
- [`wave-verify.md`](./reports/wave-verify.md) — the E2 uncommitted-slice gate.
- [`e2e-runbook.md`](./reports/e2e-runbook.md) — 16 E2E cases + Phase-4 execution
  results (auto 10/10; manual pending). [`windows-acceptance-checklist.md`](./reports/windows-acceptance-checklist.md) — the Block-F Windows sign-off checklist.
- [`docs-final-report.md`](./reports/docs-final-report.md) +
  [`docs-discovery.md`](./reports/docs-discovery.md) — Phase-4.6 documentation update
  (4 prose docs, ~11 inline-anchor groups, 8 ADR promotions).
- Per-block folders [`A`](./reports/A/) … [`F`](./reports/F/) — per-chunk `*-impl` /
  `*-selffix`, 4-lens audits, `validated-findings`, and repair-wave reports.

Chunking + orchestration artefacts: [`chunks.json`](./chunks.json),
[`desktop-companion-v1.state.md`](./desktop-companion-v1.state.md).

## Open manual acceptance

Six cases remain for human-in-the-loop sign-off at closure (all auto-runnable cases
already green). Per `reports/e2e-runbook.md` and the state file:

- **TC-A3** — emulator + mobile-mcp interactive Android case.
- **TC-W1..W4** — real two-process Windows acceptance (global hotkey, mini-panel,
  DPAPI secret at rest, auto-insert) on the Windows host at the Block-F timepoint.
- **TC-W5** — one real-provider transcription smoke with the user's own key (never
  checked in).

## EN translation

Per this project's documentation-language policy, the German-working-language plan +
research get English sidecars after implementation:

- **Plan file:** [`desktop-companion-v1.en.md`](./desktop-companion-v1.en.md) —
  full EN translation of the German-native plan.
- **Research (14 files, all with `.en.md` sidecars):** the German-native concept docs
  (`bestandsaufnahme`, `konzept-skizze`, `fragenkatalog`) and the five implementer-ready
  Specs (`shared-ai-extraktion`, `secretstore`, `entitaetenmodell-android`,
  `desktop-host`, `peer-katalog`) are genuine German→English translations; the six
  English-native repair-research files (`androidaiconfig-secret-pref-retirement`,
  `desktop-aiconfig-credential-resolution`, `desktop-history-ui-scope`,
  `desktop-usage-sink-migration`, `mid-A3-A3-SF1`, `v3-forward-compat-hash-recompute`)
  carry faithful near-identical `.en.md` mirrors (embedded German spec-quote fragments
  rendered in English; code blocks and identifiers byte-identical).

## Related ADRs

Eight plan-scoped ADRs were promoted to `docs/decisions/` during Block F (all
`Accepted` 2026-07-20; each ADR's `## References` links back to this plan —
bidirectional):

- **[ADR-0028 — `:shared-ai` module](../../decisions/0028-shared-ai-module.md)** — a
  fourth pure-JVM module for the AI core behind platform ports (*Project-Wide*).
- **[ADR-0029 — `SecretStore` port](../../decisions/0029-secret-store.md)** —
  project-wide encrypted-at-rest secrets on every host (*Project-Wide*).
- **[ADR-0030 — config entity model](../../decisions/0030-config-entity-model.md)** —
  entities in `:shared`, canonical serialization + `contentHash` as the v3 format
  (*Project-Wide*).
- **[ADR-0031 — desktop dictation host](../../decisions/0031-desktop-dictation-host.md)**
  — the companion as a recording + pipeline host with its own slim orchestrator.
- **[ADR-0032 — desktop panel UI](../../decisions/0032-desktop-panel-ui.md)** —
  frameless, always-on-top, focus-free Compose surface + global hotkey.
- **[ADR-0033 — desktop review mode](../../decisions/0033-desktop-review.md)** — full
  review incl. re-dictate on the companion.
- **[ADR-0034 — peer catalog](../../decisions/0034-peer-catalog.md)** — pull-only
  configuration sharing over the existing wire stack (*Project-Wide*).
- **[ADR-0035 — companion history parity](../../decisions/0035-companion-history-parity.md)**
  — full SQLDelight session-schema parity + `received_texts` retirement.

The plan additionally extended/touched the accepted ADR-0007, 0009, 0012, 0013,
**0015** (Decision-History: `:shared-ai` under the Kotlin ceiling), 0016, 0017, 0020,
0024, 0025, 0027 without superseding them.
