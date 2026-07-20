# B2 — Self-Fix report (fresh eyes, diff-based)

**Chunk:** B2 · **Date:** 2026-07-20T00:40:00+02:00 · **Wave commit reviewed:** `7a78356`
**Spec:** `research/secretstore.md` §7 · **Plan:** §5 Chunk B2 (L491–497)

## What I reviewed

Read the implementer report (`B2-impl.md`), the chunk diff for the four source files
+ two test files, spec §7.1–§7.6 and §3.1 (11-slot inventory), the `SecretStore`/`SecretRef`
port (B1), and the `AndroidKeystoreSecretStore` + `FakeSharedPreferences` test seams. Verified
all 11 slots and pref keys match §3.1 exactly (ten API keys under `legacy`, `WindowsDeviceSecret`
under `pairing`), the §7.3 order invariant (backup→put→remove→flag), idempotence, availability
guard, byte-exact round-trip, and abort semantics. Baseline suite was green before my change
(21 tests, 1 skipped-by-design).

## Fix applied — rollback-backup write hardening (correctness + robustness)

**Defect:** `exportBackupOnce` wrote the §7.6 rollback file with an unguarded
`backupFile.writeText(...)`, and the call sat **outside** the `try/catch`. Two failure modes on a
backup IO error (e.g. `filesDir` full):

1. **App-start crash** — the exception propagated out of `run()` → `PrefsMigration.migrateSecrets`
   → `DictateApplication.onCreate`, crashing app start on every boot until the condition cleared.
2. **Silent data-loss window** — a partial `writeText` leaves a *truncated* backup on disk; the
   next start's write-once guard (`if (backupFile.exists()) return`) treats it as complete, the
   migration then **deletes the plaintext**, and the truncated JSON becomes the only rollback
   source (§7.6). A later KEK loss would drop every key not captured in the truncation.

**Fix (`SecretsMigration.kt`):**
- Stage the backup into a temp file and `renameTo` into place — the write-once guard now only ever
  observes a *complete* file; a failed/partial write never satisfies `exists()`.
- On any `IOException` the temp is deleted and the exception propagates.
- Moved `exportBackupOnce` **inside** the migration `try`, and added a `catch (IOException)` that
  aborts cleanly (flag unset, plaintext intact, log, return) — the migration retries next start
  with a fresh full backup instead of crashing app start.

Order invariant preserved: `exportBackupOnce` is still the first statement in the try, before the
per-slot loop, so the backup-before-delete guarantee (§7.3/§7.6) holds.

**Regression test (`SecretsMigrationTest.kt`):**
`backupWriteFailure_abortsCleanly_noPlaintextDeleted_noCrash` — plants a plain file where the
backup's parent directory must go, forcing the staged write to fail. Asserts `run()` does not
throw, the flag stays unset, all 11 plaintext slots survive, and no store write happened.
- **Red on the unfixed code:** `FileNotFoundException` thrown out of `run()` (SecretsMigrationTest.kt:229).
- **Green after the fix:** clean abort. (test-first-patterns: verified red before green.)

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| B2-SF-1 | Important | Unguarded rollback-backup write (`SecretsMigration.kt` `exportBackupOnce`) could crash app start on backup IO failure and leave a truncated backup that the write-once guard mistakes for complete → silent data loss once plaintext is deleted (§7.6). | fixed-inline | none |

Carried forward from the implementer (not re-litigated): **B2-1** (§2.6 grep-freeness completes in
C2/C3 via the pending `NoLegacyKeyReadTest`) — the scoping call is defensible and correctly
delegated; the readers/writers are re-pointed atomically in C2 (reads) + C3 (writes). No action.

## Inline fixes applied

- `SecretsMigration.kt`: atomic temp-file+rename backup write; `exportBackupOnce` moved inside the
  try; added `catch (IOException)` clean-abort branch; `import java.io.IOException`.
- `SecretsMigrationTest.kt`: added the `backupWriteFailure_*` regression test.

## Files modified

- `app/src/main/java/net/devemperor/dictate/secrets/SecretsMigration.kt`
- `app/src/test/java/net/devemperor/dictate/secrets/SecretsMigrationTest.kt`

## Files outside assigned scope (drift)

none — both edits are within CHUNK_FILES. (The worktree also shows unrelated in-flight edits from
parallel agents — `AndroidKeystoreSecretStoreTest.kt`, `companion/**`, `shared/**test` — which I did
**not** touch; they are not part of this fix wave.)

## Final test result

`./gradlew :app:testDebugUnitTest` — BUILD SUCCESSFUL, zero failures/errors across the module.
Secrets suite: `SecretsMigrationTest` 9/9, `AndroidKeystoreSecretStoreTest` 11/11,
`NoLegacyKeyReadTest` 2 (1 pending/skipped by design).
