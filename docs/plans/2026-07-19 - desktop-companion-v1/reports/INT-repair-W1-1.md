# INT Repair Wave 1 — Report (INT-repair-W1-1)

**Timestamp:** 2026-07-20T17:25:00+02:00
**Agent role:** repair-fix (implement-long-plan-v3)
**Cluster:** `integ-1` (green / Nice-to-have) — pre-archival freshness pass over the
five per-block research specs.

## What was done

`integ-1` is a systemic-but-benign documentation-freshness aggregate: the five
per-block specs remain the nominal SSoT (plan §5) but are partially stale vs. the
built structure. No code impact — the deviations are all D5/D4-endorsed,
parity/characterization-tested, and already annotated in code headers + impl
reports.

Rather than rewrite the large spec bodies (heavy + drift-prone for a green
finding), I appended a **dated freshness-pass entry to each spec's Change-History /
Decision-Log** — the exact mechanism these specs already use to record
supersessions (e.g. the 2026-07-20 D4/D5/D6/D7 cross-spec entries). Each entry
records the as-built structure, points at the shipped location + the D5 plan
decision + the inline code docs, and states there is no code impact.

Verified shipped facts before writing (grep/read against the worktree):

- **Wire enums consolidated** into one home
  `shared/src/main/kotlin/net/devemperor/dictate/shared/config/ConfigEnums.kt` —
  incl. `Visibility` (`PRIVATE`/`SHARED`) and `SubscriptionMode`
  (`LOCAL`/`SUBSCRIBE`/`ONE_SHOT`). No `shared/catalog/` package exists;
  peer-katalog §5.2/§5.3's `shared.catalog.VisibilityWire`/`SubscriptionModeWire`
  never shipped. `CatalogEntityKindWire` stayed in `shared.protocol` as specified.
  The spec's `subscription_mode` `NULL` case for local/forked became an explicit
  `LOCAL` member (`NOT NULL DEFAULT 'LOCAL'`, CHECK updated).
- **SQLDelight migrations** are `1/2/3/4.sqm` (v1→v5). D1a=`2.sqm`, D3=`3.sqm`
  (config-entity tables), E1=`4.sqm` — already recorded in the 2026-07-20 D-entries.
- **`usage` table** shipped folded into `3.sqm` (D3), not the D1 `2.sqm` migration
  (the `3.sqm` header documents "Folded into this migration").
- **Companion management UI** consolidated into
  `companion/.../ui/config/ManagementScreen.kt` + `ConfigViewModel.kt` — one screen,
  not separate `ui/profiles`/`ui/models`/`ui/prompts`.
- **secretstore** is as-built per §9, only addition `app/.../secrets/PairingSecrets.kt`.

## Per-finding

| Finding | Status | Fix |
|---|---|---|
| `integ-1` | fixed | Dated freshness-pass entry appended to each of the five specs' Change-History / Decision-Log, recording as-built structure + pointing at the D5 decision and shipped code. |

## Files modified

- `docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md`
  — §11 Change History: 2026-07-20 freshness entry (spec is as-built; notes the
  cross-spec `ConfigEnums.kt` enum home for context).
- `docs/plans/2026-07-19 - desktop-companion-v1/research/secretstore.md`
  — §13 Change History: freshness entry (as-built; §9 layout + `PairingSecrets.kt`).
- `docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md`
  — §13 Decision Log: freshness entry (wire-enum home consolidation; companion UI
  consolidation pointer).
- `docs/plans/2026-07-19 - desktop-companion-v1/research/desktop-host.md`
  — §14 Decision Log: new D5 freshness entry (`usage` in `3.sqm`; UI consolidation).
- `docs/plans/2026-07-19 - desktop-companion-v1/research/peer-katalog.md`
  — §14 Decision Log: new D8 freshness entry (catalog wire-enum consolidation into
  `shared.config`, `LOCAL` member + CHECK update).

## Tests

**Not run — reasoned skip.** The change is documentation-only (Markdown
Change-History/Decision-Log prose in `research/*.md`); it cannot affect any
Gradle/JVM test outcome. The finding is explicitly classified green with "No code
impact". Running the full `./gradlew build`/`test` would also risk colliding with
concurrently active agents (e2e-fix-migrations et al.). No source, schema, or test
file was touched.

## Drift (edits outside assigned scope)

none — all edits are within the five spec files named in the finding.

## New issues discovered while fixing

none.
