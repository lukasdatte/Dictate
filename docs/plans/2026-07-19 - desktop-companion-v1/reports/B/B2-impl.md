# B2 — Android-Klartext-Key-Migration in den SecretStore — IMPL+TEST report

**Chunk:** B2 · **Date:** 2026-07-20 · **Spec:** `research/secretstore.md` §7 · **ADR:** `adrs/adr-secret-store.md`

## What I did

Implemented the one-time, idempotent migration of the **11 plaintext secret prefs** (the ten
`Pref.*ApiKey*` plus `WindowsDeviceSecret`, spec §7.1) out of the app `SharedPreferences` and into
the encrypted `SecretStore` (B1 port). API keys land under the `legacy` namespace
(`SecretRef("legacy", "<pref-key-suffix>")`, for C2's legacy→credential re-map), the device secret
under its final `pairing` namespace. The migration follows the §7.3 order invariant
(backup-once → per-slot put-before-remove → flag-last), is availability-guarded and aborts cleanly
(flag stays unset, plaintext survives) on any store failure, and exports a `0600` rollback JSON
(§7.6) that is never overwritten by a retry. Wired the call at app start via a new
`PrefsMigration.migrateSecrets(context)` (the integration target) invoked from
`DictateApplication.onCreate`.

## Scope decision — reader re-pointing is C2/C3, not B2

Spec criterion §2.6 (grep-freeness: the 11 pref constants referenced only in definition +
migration) cannot be satisfied by B2 alone. The runtime **readers** (`AndroidAiConfig` API keys,
`WindowsTarget` device secret) and **writers** (settings/onboarding/pairing UI) still reference
these prefs; they are re-pointed to the SecretStore atomically in **C2** (`ProfileResolver` reads,
legacy→credential re-map per §7.2) and **C3** (UI writes). A reads-only re-point in B2 would
silently break a newly entered key — the write path would still target prefs while the read path
looked in the store. B2 therefore moves the **data** and encodes the §2.6 end-state as a **pending**
`NoLegacyKeyReadTest` (@Ignore + `pending:` reason + C2/C3 tracking ref, per test-first-patterns).
The transitional runtime state after B2 (existing keys deleted from prefs, readers not yet
re-pointed) is the deliberate hard-migration cost (§7.6, F22), recoverable via the backup file.

## Deviations

| Deviation | Plan location | What changed | Why | Impact on later chunks | Resolved? |
|---|---|---|---|---|---|
| §2.6 grep-freeness delivered as a pending test, not green | Plan L496–497 / spec §2.6 | `NoLegacyKeyReadTest` carries the real end-state assertion but is `@Ignore`d | Readers/writers re-pointed only in C2/C3; a partial re-point in B2 breaks new-key entry | C2 must re-point reads + C3 the writes, then remove `@Ignore` | Encoded, not yet green (by design) |
| `DictateApplication.java` edited (outside INTEGRATION_TARGETS) | spec §7.3 ("dort wo migrateProviderPrefs läuft") | Added `PrefsMigration.migrateSecrets(this);` in `onCreate` | The migration needs a real runtime call site; app-start is the spec'd location | C2 (depends on B2) builds on this; no parallel conflict | Yes |

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| B2-1 | Important | §2.6 grep-freeness completes in C2/C3, not B2 — encoded as pending `NoLegacyKeyReadTest`; audit should confirm the scoping call and that C2/C3 remove the `@Ignore` | delegated | plan-deviation-resolved |

## Inline fixes / helper decisions

- No new test helpers; reused `FakeSharedPreferences` (testutil), `InMemoryKekProvider` (B1 seam),
  and the real `AndroidKeystoreSecretStore` for byte-exact round-trip. A small in-test
  `FakeSecretStore` drives the unavailable / put-failure abort paths deterministically.
- Corrected the retry test to reuse one store instance across the fail-then-heal passes (production
  reuses the same backing store, so slots stored before a failure are still present on retry).

## Integration (call-site, not existence)

- `PrefsMigration.kt` (INTEGRATION_TARGET) — real diff: `migrateSecrets(context)` calls
  `SecretsMigration.run(context)`.
- Runtime caller: `DictateApplication.java:onCreate` → `PrefsMigration.migrateSecrets(this)`
  (Application.onCreate always precedes any IME component, so one call covers the process).

## Files outside assigned scope (drift)

- `app/.../DictateApplication.java` — added the migration call site (2 lines). Rationale: a
  migration with no runtime caller is dead code; §7.3 names app-start as the location; C2 depends on
  B2 so there is no parallel-chunk conflict.

## Files modified

- `app/src/main/java/net/devemperor/dictate/secrets/SecretsMigration.kt` (new)
- `app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt` (+`SecretsMigratedV1` flag)
- `app/src/main/java/net/devemperor/dictate/preferences/PrefsMigration.kt` (+`migrateSecrets` hook)
- `app/src/main/java/net/devemperor/dictate/DictateApplication.java` (call site)
- `app/src/test/java/net/devemperor/dictate/secrets/SecretsMigrationTest.kt` (new, 8 tests)
- `app/src/test/java/net/devemperor/dictate/secrets/NoLegacyKeyReadTest.kt` (new, 1 pending + 1 scanner)

## Test run

`./gradlew :app:testDebugUnitTest --tests "net.devemperor.dictate.secrets.*"` — BUILD SUCCESSFUL.
- `SecretsMigrationTest` 8/8 pass (lossless 11-slot round-trip incl. non-ASCII; absence of all 11
  plaintext keys; idempotent no-op; fresh-install; store-unavailable abort; put-failure abort with
  full backup; backup-not-overwritten-on-retry; namespace split legacy/pairing).
- `NoLegacyKeyReadTest` 1 pass (scanner self-check) + 1 skipped (pending end-state).
- `AndroidKeystoreSecretStoreTest` (B1) 11/11 still green.

Scope note: full `./gradlew build` not run — `:companion`/`:shared` have concurrent in-flight edits
from parallel agents; B2 is `:app`-only and `:app:testDebugUnitTest` compiled `:app` + `:shared-ai`
+ `:shared` and passed.
