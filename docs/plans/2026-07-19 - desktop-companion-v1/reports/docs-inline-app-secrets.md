# Inline-Anchor Sync — `app-secrets`

**Date:** 2026-07-20T17:25:00+02:00
**Slug:** app-secrets
**Scope:** `app/src/main/java/net/devemperor/dictate/secrets/`
**Agent:** docs-inline (app-secrets group)
**Plan range:** c46cfe8..HEAD

## Summary

The four `secrets/` files already carried strong module headers and `@see` spec
anchors (discovery rated the group 3/4 — good). One stale anchor: the migration
header still named the **plan-scoped ADR draft slug** `adr-secret-store`, which
was **promoted to `ADR-0029`** (`docs/decisions/0029-secret-store.md`) this
range. Fixed that, and added navigable `@see docs/decisions/0029-secret-store.md`
tags on the two public-facing surfaces (the migration entry point and the
`SecretStore` impl) whose shape follows directly from the now-promoted,
project-wide ADR-0029. No logic, imports, or formatting touched.

## Anchors changed — per file

| File | Anchor | Action |
|---|---|---|
| `SecretsMigration.kt` | module header prose `ADR adr-secret-store` | **updated** → `ADR-0029` (stale draft slug; promoted this range) |
| `SecretsMigration.kt` | `@see` block | **added** `@see docs/decisions/0029-secret-store.md` (alongside existing spec §7 + `PrefsMigration.migrateSecrets` refs) |
| `AndroidKeystoreSecretStore.kt` | `@see` block | **added** `@see docs/decisions/0029-secret-store.md` (alongside existing spec §5 ref) |

## Files reviewed, left unchanged (deliberate skips)

| File | Reason |
|---|---|
| `KekProvider.kt` | Header + `@see` spec §5.1/§5.3 resolve; it is a Robolectric test-seam supporting the same decision point (ADR-0029) already anchored on the two public surfaces — a further ADR `@see` here would over-anchor one decision point. |
| `PairingSecrets.kt` | Handle-SSoT constant holder; header + `@see` spec §7.2 resolve, and all inline symbol cross-refs (`WindowsTarget.resolve`, `settings.WindowsPairingActivity`, `config.ConfigSecrets`) verified present. Same-decision-point rationale as above. |

## Anchor-resolution verification (self-check)

- `docs/decisions/0029-secret-store.md` — exists (index row + file confirmed).
- Spec `research/secretstore.md` §5, §5.1, §5.3, §5.4, §7, §7.2 — all headers present.
- `ADR-0017 §F-3` (still referenced in the migration header) — accurate: ADR-0017
  §4 "Secret storage on the phone (F-3)"; ADR-0017's decision-history even records
  "§F-3 plaintext-secret defer resolved (ADR-0029)". Left as-is (correct).
- Inline symbol refs in `SecretsMigration`/`PairingSecrets` (`PrefsMigration.migrateSecrets`,
  `WindowsTarget.resolve`, `WindowsPairingActivity`, `ConfigSecrets`) — all resolve.
- No comment-noise added; no code/imports/formatting changed.

## Notes (bugs / name-smells — flagged, not edited)

- **Out-of-scope stale slug:** the same draft slug `adr-secret-store` still appears in
  two XML config files outside this group's `.kt` scope — should be updated to
  `ADR-0029` by whoever owns the resource/config docs:
  - `app/src/main/res/xml/data_extraction_rules.xml:11`
  - `app/src/main/res/xml/backup_rules.xml:13`
