# INT Re-Audit — Repair Wave 1

- **Mode:** re-audit
- **Block:** INT (integration check)
- **Repair-wave commit:** `bf4c804aa1bf524906a7b89012d763368ea4c4ab` — `[P4] INT repair wave 1`
- **Timestamp:** 2026-07-20T17:25:00+02:00
- **Verdict:** CONVERGED — the single finding is fully resolved, no new findings introduced.

## Finding under review

### `integ-1` (green, Nice-to-have) — RESOLVED → dropped

**Claim:** The five per-block specs (`shared-ai-extraktion.md`, `secretstore.md`,
`entitaetenmodell-android.md`, `desktop-host.md`, `peer-katalog.md`) were partially
stale vs. the built structure (consolidated `shared.config` enums instead of
`catalog.*Wire` copies; migrations renumbered to `2/3/4.sqm`; `usage` table moved
D1b→D3; management UI consolidated into `ui/config/ManagementScreen`). The
suggested fix: run a freshness pass over the five specs and add Change-History
notes pointing at the D5 decision, before F-stage EN translation/archival.

**What the wave did:** Commit `bf4c804` touches exactly the five named spec files
(plus its own report `INT-repair-W1-1.md`) — documentation only, +190 lines,
no body rewrites, all additive append-only "Freshness-Pass 2026-07-20" entries:

| Spec | Entry | Covers |
|---|---|---|
| `desktop-host.md` | D5 — Freshness-Pass | `usage`-in-`3.sqm`; UI consolidation into `ui/config/ManagementScreen` |
| `peer-katalog.md` | D8 — Freshness-Pass | `catalog.*Wire` → `shared.config.ConfigEnums` (`Visibility`/`SubscriptionMode`); updated `CHECK` vocabulary; migration numbering |
| `entitaetenmodell-android.md` | Freshness-Pass | Wire-enum home consolidation; companion UI consolidation |
| `shared-ai-extraktion.md` | Freshness-Pass | as-built confirmation of `:shared-ai` topology; cross-note on consolidated wire enums |
| `secretstore.md` | Freshness-Pass | as-built confirmation of SecretStore port + backends; `PairingSecrets.kt` addition |

**Verification against built code (each claim checked, not just read):**

- `shared/src/main/kotlin/net/devemperor/dictate/shared/config/ConfigEnums.kt`
  exists with `Visibility { PRIVATE, SHARED }`, `SubscriptionMode { LOCAL,
  SUBSCRIBE, ONE_SHOT }`, `ProviderType`, `ProviderKind`, `ModelFunction`.
- No `shared.catalog` package references remain in any Kotlin source
  (`grep -r shared\.catalog --include=*.kt` → no matches) — the `catalog.*Wire`
  copies genuinely do not exist; enums are consolidated.
- `usage` table is defined in `companion/.../db/migrations/3.sqm` (line 113)
  with the inline header "Folded into this migration" — matches D5 point 1.
- `subscription_mode` columns carry `CHECK (... IN ('LOCAL','SUBSCRIBE','ONE_SHOT'))`
  in `3.sqm` and `Companion.sq` — matches peer-katalog D8's updated-CHECK claim.
- `companion/.../ui/config/ManagementScreen.kt` + `ConfigViewModel.kt` exist;
  `ui/profiles`, `ui/models`, `ui/prompts` directories are absent — matches D5 point 2.
- `app/src/main/java/net/devemperor/dictate/secrets/PairingSecrets.kt` exists —
  matches the secretstore.md addition.
- Migrations `2.sqm`/`3.sqm`/`4.sqm` exist — matches the renumbering claim.

Every as-built assertion in the freshness notes is code-accurate. The finding
asked for exactly this freshness pass with D5-pointing Change-History notes, and
the wave delivered it faithfully.

**Resolution:** RESOLVED. Dropped.

## New problems introduced by the wave

None. The commit is documentation-only (five spec markdown files, all
append-only additions, no body rewrites), so there is no risk of code
regressions, broken imports, or contradicted existing content. The added notes
were spot-checked for internal consistency with the specs' prior Decision-History
sections and with the shipped code; no contradictions found.

## Eliminated findings

None (single finding under review; it was resolved, not eliminated as a false positive).

## Convergence

Empty findings array — the integration re-audit has converged. The five specs now
describe the built structure and are ready for the F-stage EN translation / archival.
