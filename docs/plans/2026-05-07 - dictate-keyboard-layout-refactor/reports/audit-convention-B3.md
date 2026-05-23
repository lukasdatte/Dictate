# Audit Report: convention (Block 3, scope: full-block)

**Agent-ID:** B3-AUDIT-CONVENTION
**Date:** 2026-05-15
**Knowledge skills used:** knowledge-reference (general patterns); knowledge-doc-format (Inline-Anchor: header / `@see` / gotcha). Project CLAUDE.md + docs/DATABASE-PATTERNS.md consulted as primary baselines.
**Files inspected:** 30

Production:
- `app/src/main/java/net/devemperor/dictate/core/RecordingHardwareAdapter.kt`
- `app/src/main/java/net/devemperor/dictate/core/BluetoothScoSubsystemAdapter.kt`
- `app/src/main/java/net/devemperor/dictate/core/AudioFocusSubsystemAdapter.kt`
- `app/src/main/java/net/devemperor/dictate/core/RecordingTimerAdapter.kt`
- `app/src/main/java/net/devemperor/dictate/core/AmplitudeStreamAdapter.kt`
- `app/src/main/java/net/devemperor/dictate/core/BorderGlowAdapter.kt`
- `app/src/main/java/net/devemperor/dictate/core/PipelineCallbackBridge.kt`
- `app/src/main/java/net/devemperor/dictate/core/CacheDirAudioFileFactory.kt`
- `app/src/main/java/net/devemperor/dictate/core/SessionManager.kt` (diff: 4 transition methods)
- `app/src/main/java/net/devemperor/dictate/core/ResendStatusDispatcher.kt` (diff)
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt` (diff)
- `app/src/main/java/net/devemperor/dictate/database/dao/SessionDao.kt`
- `app/src/main/java/net/devemperor/dictate/database/entity/SessionEntity.kt`
- `app/src/main/java/net/devemperor/dictate/database/entity/SessionStatus.kt`
- `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo4.kt`
- `app/src/main/java/net/devemperor/dictate/migration/LegacyAudioFileMigration.kt`
- `app/src/main/java/net/devemperor/dictate/history/HistoryAdapter.java` (diff)
- `app/src/main/java/net/devemperor/dictate/state/ModuleServices.kt` (diff)
- `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt`
- `app/src/main/java/net/devemperor/dictate/state/PipelineSessionRepoAdapter.kt`
- `app/src/main/java/net/devemperor/dictate/state/PipelineOrphanCleaner.kt`
- `app/src/main/java/net/devemperor/dictate/state/PipelineServiceStubSubsystems.kt` (diff)
- `app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt` (diff)
- `app/src/main/res/values/strings.xml` + `values-de`/`values-es`/`values-pt`

Tests + config:
- `app/src/androidTest/java/.../MigrationTo4Test.kt`
- `app/src/androidTest/java/.../AndroidTestSetupSmokeTest.kt`
- 14 new JVM test files under `app/src/test/java/.../core/`, `.../state/`, `.../database/entity/`, `.../migration/`, `.../testutil/`
- `app/build.gradle` (diff) + `gradle/libs.versions.toml` (diff)

## Summary

- Critical: 0
- Important: 3
- Nice-to-have: 7

K-1 (handwritten fakes only) clean: `grep -rE 'mockk|mockito|Mockito|MockK' app/src/test/ app/src/androidTest/` returns only comments documenting *absence* of those frameworks — no new dep introduced, no new `mockk(...)` / `mock<...>()` call site. K-4 (no Android Context in JVM tests unless justified): 9 Robolectric tests in B3 — every file declares an explicit K-4 justification in its class KDoc (`RecordingTimerAdapterTest` Handler/Looper, `RecordingHardwareAdapterTest` MediaRecorder, `LegacyAudioFileMigrationTest` Room/Context, `DictatePipelineServiceCompositionTest` Service controller, `DictatePipelineServiceAudioFileFactoryWiringTest` Service controller). All four B3 production state-side helpers (`PipelineRecovery`, `PipelineSessionRepoAdapter`, `PipelineOrphanCleaner`, plus a couple of new `state/`-package classes) keep their JVM tests pure (`runBlocking { ... }` + `FakeSessionDao` — no Robolectric needed). Double-Enum pattern intact for SessionStatus (the 2 new variants land in both Kotlin enum and SQL `CHECK` literal — see DATABASE-PATTERNS.md checklist below). `Pref.SessionCleanupGracePeriodMs` follows the `Pref<Long>` sealed-class entry shape correctly. The new androidTest source set wires `testInstrumentationRunner = androidx.test.runner.AndroidJUnitRunner`, `room-testing` + runner + rules in `libs.versions.toml`, and the wiring is honoured by `MigrationTo4Test` (mandatory `MigrationTestHelper` rule + 7 cases).

Findings below are stylistic-consistency drift across the 7+ new Kotlin files and a small number of CLAUDE.md / Pref-policy and inline-anchor inconsistencies. None block the block-validate pass.

## Verification: project-convention checklist (CLAUDE.md)

- [x] New code Kotlin — B3 adds 12 new Kotlin files; the only Java touched is the pre-existing `DictateInputMethodService.java` (already Java) and `HistoryAdapter.java` (already Java) — extension only, no Kotlin→Java conversion.
- [x] Preferences via `Pref` sealed class — `Pref.SessionCleanupGracePeriodMs` added (correct shape, namespaced key, KDoc with spec pointer). The `Pref<Long>` branch is already wired in the file's `get`/`put` extension functions.
- [⚠] Raw-string-key SharedPreferences access — `LegacyAudioFileMigration.FLAG_PREF = "legacy_audio_purged_v4"` is a raw string accessed via `PreferenceManager.getDefaultSharedPreferences(context).getBoolean(FLAG_PREF, ...)`. This follows the sibling `InputLanguagesLegacyMigration.kt` precedent (`private const val KEY = "net.devemperor.dictate.input_languages"`) but violates CLAUDE.md project-wide rule. See AUDIT-CONVENTION-B3-2 below.
- [x] AI-SDK boundary — no `AIOrchestrator` / `RunnerFactory` / `openai-java` / `anthropic-java` direct calls in any of the new B3 production files (grep confirms).
- [x] Room access through DAOs — `SessionDao` gets 8 new methods (M4 scope); all callers (`SessionManager`, `PipelineSessionRepoAdapter`, `PipelineOrphanCleaner`, `PipelineRecovery`, `LegacyAudioFileMigration`) go through it. No direct `db.openHelper.writableDatabase` access.
- [x] Double-Enum pattern (DATABASE-PATTERNS.md) — `SessionStatus` enum extended in `database/entity/SessionStatus.kt` (6 variants) and the SQL `CHECK` literal in `MigrationTo4.kt:75-79` carries the exact same 6 strings. `SessionEntity.statusEnum` accessor preserves the `runCatching ... getOrDefault(RECORDED)` fallback. DAO methods take `String`, callers pass `SessionStatus.X.name`. Default value `'COMPLETED'` in the SQL DEFAULT matches Spec 1 §6.1 (backfill semantics). The migration test (`MigrationTo4Test`) follows the documented `MigrationTestHelper` template — `assertFailsWith` is rephrased as `@Test(expected = SQLiteConstraintException::class)` (case #3) which is the same JUnit4 idiom. Migration-test fixtures use the v1→v4 chain (case #7) to exercise multi-step composition. **One DATABASE-PATTERNS.md "Checklist for new Double-Enum columns" item missed:** the doc says *"Default value in the `@ColumnInfo` matches the `DEFAULT` clause in SQL"* — the entity's `status` defaults to `SessionStatus.RECORDED.name` (Kotlin field), while the SQL `DEFAULT` is `'COMPLETED'`. See AUDIT-CONVENTION-B3-3 below.
- [x] K-1 (handwritten fakes only) — `grep -r "mockk\|mockito\|MockK\|Mockito" app/src/test/ app/src/androidTest/` returns only comments documenting absence. New `FakeSessionDao` in `testutil/` is hand-rolled (~160 LoC, in-memory Map-backed).
- [x] K-4 (Robolectric only with documented justification) — 9 Robolectric tests in B3 + diff; each has a class-level KDoc paragraph naming the Android-bound type that drove the opt-out (`MediaRecorder`, `Handler/Looper`, `Service` controller, Room+Context). Two of the new adapter tests stay pure-JVM (`AudioFocusSubsystemAdapterTest`, `BluetoothScoSubsystemAdapterTest`, `AmplitudeStreamAdapterTest`, `BorderGlowAdapterTest`, `PipelineCallbackBridgeTest`) — those rely on `FakeAudioFocusGate` / `BluetoothScoControl`-fake (K-1 conformant) + AtomicReference + `mutableListOf<Action>` capture. The split is well-justified per type.
- [x] androidTest infrastructure (Spec 1 §11.7.0a) — `app/build.gradle:18` sets `testInstrumentationRunner = androidx.test.runner.AndroidJUnitRunner`; `libs.versions.toml` declares `room-testing`, `androidx-test-runner`, `androidx-test-rules`; `app/build.gradle:120-122` wires the three `androidTestImplementation` deps. No `AndroidManifest.xml` under `app/src/androidTest/` (correct — AGP auto-merges from `main` for instrumented-only test packages; `Migration*Test` doesn't need a custom manifest).
- [x] Lint config (KG-SST-4) — `app/build.gradle:62-81` adds the `lint { error += "EnumSwitch" }` block with a documented rationale (deviation from the spec snippet w.r.t. `abortOnError`, justified in C9 deviation table).
- [x] Migration registration — `DictateDatabase.kt` version `3 → 4`, `addMigrations(MIGRATION_3_4)` line added. Schema export at `app/schemas/net.devemperor.dictate.database.DictateDatabase/4.json` committed (Spec 1 §11.7.0a step 5).
- [x] Class naming — `XxxAdapter` for the 7 production adapters wrapping legacy or new subsystems; `XxxBridge` (singular) for `PipelineCallbackBridge`; `MIGRATION_3_4` constant follows the existing `MIGRATION_1_2` / `MIGRATION_2_3` convention. `LegacyAudioFileMigration` (PascalCase object) and `MigrationTo4.kt` (file) differ in naming pattern — see AUDIT-CONVENTION-B3-9 below.
- [x] Cross-block-API consumer info — Block-3 report's "Cross-block-API consumer info forwarded to Block 4" line is still ⏳ at audit time. Not a convention finding, but worth noting for the orchestrator.

## Findings

### AUDIT-CONVENTION-B3-1

- **Severity:** Important
- **File:** `app/src/main/res/values-de/strings.xml`, `values-es/strings.xml`, `values-pt/strings.xml` (each: missing translations for `dictate_status_recording` + `dictate_status_transcribing`)
- **Description:** The English `values/strings.xml:342-343` adds two new strings — `dictate_status_recording = "Recording…"`, `dictate_status_transcribing = "Transcribing…"`. These are consumed by `HistoryAdapter.java:152` and `:158` as part of the M4-mandatory defensive switch arms (Spec 1 §6.1.3). The German, Spanish, and Portuguese resource files already carry the four sibling status strings (`dictate_status_recorded`, `_failed`, `_cancelled`, `_running`) — they are systematically translated. The two new keys are **only** in `values/` (English fallback). The companion `dictate_storage_full` string added in the same block IS translated to all four locales — consistent translation tone (`Cache full — recording cannot start.` / `Cache voll — Aufnahme kann nicht starten.` / etc.). The dictate-status-* asymmetry is the same kind of "same-operation-two-ways" drift the convention audit exists to catch: one new-string family is fully localised, the other isn't.
- **Why it matters:** Non-English users see English fallback text inside an otherwise-localised history list. The strings only surface in the defensive window (`HistoryAdapter` between OOM-death and `PipelineRecovery.recover()` running), so the user-facing impact is small but real — and the missing translations entrench themselves the moment another locale is added (every future translator copies the existing pattern and notices the gap, OR misses it). Block 4 / 5 may add additional `dictate_status_*` strings; setting the precedent now (every status-string gets all 4 locales) keeps the convention stable.
- **Suggested fix scope:** small (4 string entries × 3 locale files = 12 mechanical edits). Strings: DE "Aufnahme läuft…" / "Transkription läuft…", ES "Grabando…" / "Transcribiendo…", PT "Gravando…" / "Transcrevendo…" — pick localisations consistent with the sibling `_running` strings already there.
- **Suggested fix:** Add the two missing keys to each of `values-de/strings.xml`, `values-es/strings.xml`, `values-pt/strings.xml`. The C9 deviation table doesn't mention the localisation gap — flag it as the kind of plan-conform-but-incomplete localisation that AUDIT-PLAN-AND-API might also catch.

### AUDIT-CONVENTION-B3-2

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/migration/LegacyAudioFileMigration.kt:70-79`
- **Description:** `LegacyAudioFileMigration` uses a **raw string SharedPreferences key** (`FLAG_PREF = "legacy_audio_purged_v4"`) accessed via `PreferenceManager.getDefaultSharedPreferences(context).getBoolean(FLAG_PREF, false)` (line 79) and `prefs.edit().putBoolean(FLAG_PREF, true).apply()` (line 98). This violates the project-wide CLAUDE.md rule: *"Preferences are always accessed through `DictatePrefs.kt` sealed class — never use raw string keys."* The same pattern appears in `Pref.SessionCleanupGracePeriodMs` (added in the same block) — that one was correctly promoted to a `Pref<Long>` entry. The migration flag could have followed the same path: `Pref.LegacyAudioPurgedV4 : Pref<Boolean>("net.devemperor.dictate.legacy_audio_purged_v4", false)`. Note that the raw-key pattern is also visible in the sibling `InputLanguagesLegacyMigration.kt:36` (`private const val KEY = "net.devemperor.dictate.input_languages"`) — that is the **legacy SP key being migrated FROM** (a legitimate raw-key usage because Pref doesn't know the old type). `LegacyAudioFileMigration.FLAG_PREF` is **not** that — it is a new internal-state flag the migration owns and reads/writes itself, which is exactly the case `Pref` is for.
- **Why it matters:** Two failure modes inherited from B2-AUDIT-CONVENTION-B2-2 (which flagged the same drift on `OverlayModule.runEffect`'s raw keys):
  1. **Type-safety drift.** A future code reader expects all "internal flags" to live in `DictatePrefs.kt`. The migration class's raw key is invisible to a `grep Pref.` search.
  2. **Migration safety.** A future `PrefsMigration` rename / removal pass operates on `Pref` entries; the raw `"legacy_audio_purged_v4"` literal would be silently missed.
- **Suggested fix scope:** small (one `Pref<Boolean>` entry in `DictatePrefs.kt`; replace the 3 raw-string sites in `LegacyAudioFileMigration.kt` with `sp.get(Pref.LegacyAudioPurgedV4)` / `sp.edit().put(Pref.LegacyAudioPurgedV4, true).apply()`).
- **Suggested fix:** Add `object LegacyAudioPurgedV4 : Pref<Boolean>("net.devemperor.dictate.legacy_audio_purged_v4", false)` to the "Internal State" section of `DictatePrefs.kt`. Use it in `LegacyAudioFileMigration.run`. The migration-test (`LegacyAudioFileMigrationTest`) already uses `LegacyAudioFileMigration.FLAG_PREF` as a public constant — rename to `LegacyAudioFileMigration.flagPrefKey()` returning `Pref.LegacyAudioPurgedV4.key` to preserve test-helper readability.

### AUDIT-CONVENTION-B3-3

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/database/entity/SessionEntity.kt:36` vs. `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo4.kt:75`
- **Description:** The **Kotlin entity default** for `status` is `SessionStatus.RECORDED.name` (`SessionEntity.kt:36`); the **SQL `DEFAULT`** in `MigrationTo4.kt:75-79` for the same column is `'COMPLETED'`. The DATABASE-PATTERNS.md "Checklist for new Double-Enum columns" explicitly says: *"Default value in the `@ColumnInfo` matches the `DEFAULT` clause in SQL"*. This pre-existed in `MigrationTo3.kt` (the M2→M3 also used `DEFAULT 'COMPLETED'` against a Kotlin default of `RECORDED.name`) — so the **drift is inherited, not newly introduced** by B3. But B3 is the migration that touches the column most recently, and the M4 chunk could have closed the gap (the Spec 1 §6.1 backfill clause makes `'COMPLETED'` the right SQL DEFAULT for migration semantics, so the Kotlin entity default could be changed to `COMPLETED.name` to align — OR the migration could carry a comment that the SQL `DEFAULT` exists for legacy-row INSERTs from pre-Kotlin-default code paths).
- **Why it matters:** A reader following the DATABASE-PATTERNS.md checklist hits this row and finds the rule violated without explanation. Two read paths now diverge: a Kotlin construct `SessionEntity(id = "x", type = "RECORDING", createdAt = 0)` produces `status = "RECORDED"`; a raw SQL `INSERT INTO sessions (id, type, created_at) VALUES ('x', 'RECORDING', 0)` produces `status = "COMPLETED"`. The Java path is unreachable in normal app flow (Room generates the INSERT with explicit columns) — but `MigrationTo4Test.migrate3To4_addsInsertedAtColumn_andBackfillsCompleted` does exactly that raw-SQL INSERT (test fixture `INSERT INTO sessions (id, type, created_at, status, ...) VALUES (...)` — explicitly passes `status`, so the gap doesn't surface in test). A future test that forgets to set `status` in an `INSERT` will silently get `COMPLETED`.
- **Suggested fix scope:** small (one of three options): (a) add the missing comment to `MigrationTo4.kt` explaining the intentional mismatch and pointing at DATABASE-PATTERNS.md ("intentional deviation — see KDoc"); (b) update the entity default to match (`status: String = SessionStatus.COMPLETED.name`); (c) update the SQL default to match (`DEFAULT 'RECORDED'`). The C9 IMPL log doesn't flag the gap, and the Kotlin default is established C7 baseline — option (a) is the lowest-risk fix.
- **Suggested fix:** Add a `**Why DEFAULT 'COMPLETED' (not 'RECORDED')?**` paragraph to `MigrationTo4.kt`'s KDoc — the SQL DEFAULT is for legacy backfill semantics (a pre-M4 row that somehow reached an `UPDATE sessions SET status = NULL` would silently land on `COMPLETED`, the post-migration safe state). The Kotlin default `RECORDED` is correct for app-side construction (a freshly-created session has not yet been transcribed). Update the DATABASE-PATTERNS.md checklist to note that the rule has a documented carve-out for backfill migrations.

### AUDIT-CONVENTION-B3-4

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/PipelineSessionRepoAdapter.kt:3, 144-146` (vs. `app/src/main/java/net/devemperor/dictate/state/PipelineOrphanCleaner.kt:4-5`, `PipelineRecovery.kt:3, 253-255`)
- **Description:** `PipelineSessionRepoAdapter.kt` declares `import android.util.Log` (line 3) **and** a `private companion object { private const val TAG = "PipelineSessionRepoAdapter" }` (lines 144-146), but **never uses** the `Log` import or the `TAG` constant in the file body. `grep -c "Log\." app/src/main/java/net/devemperor/dictate/state/PipelineSessionRepoAdapter.kt` returns 0. The two sibling state-side classes in the same block (`PipelineRecovery.kt`, `PipelineOrphanCleaner.kt`) use the same `import + companion + TAG` shape **and** use `Log.w(TAG, ...)` in multiple sites — the adapter file ships the boilerplate without a consumer.
- **Why it matters:** Dead-code drift — a future reader assumes the adapter has logging it doesn't. The companion-object's `private const val TAG` is a 3-line block that's invisible at runtime, but it sets a precedent ("every adapter has a TAG even if it doesn't log") that a future implementer copies into a new adapter that also doesn't log, perpetuating the pattern.
- **Suggested fix scope:** small (remove the unused import + companion object, OR add at least one `Log.w(TAG, ...)` site for the error paths the adapter currently silently lets through — e.g. the `markFailed` `withContext(Dispatchers.IO) { dao.updateStatus(...) + dao.updateError(...) }` block has no failure logging; a `try/catch` with `Log.w(TAG, "markFailed($sessionId) DAO failed", t)` matches the `PipelineRecovery.safeUpdate*` pattern and is consistent with the file's "fail-soft" intent).
- **Suggested fix:** Either prune the unused `import android.util.Log` + companion object, or wrap the two `withContext(Dispatchers.IO) { ... }` blocks in `markInserted` / `markFailed` with `try { ... } catch (t: Throwable) { Log.w(TAG, "...", t) }` so the TAG earns its keep. The latter is the better fix per D7 — adapter failures are silent today; making them visible at WARN matches the sibling `PipelineOrphanCleaner` / `PipelineRecovery` convention.

### AUDIT-CONVENTION-B3-5

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt:100` (injectable `ioContext`) vs. `app/src/main/java/net/devemperor/dictate/state/PipelineOrphanCleaner.kt:82` (hardcoded `Dispatchers.IO`) vs. `app/src/main/java/net/devemperor/dictate/state/PipelineSessionRepoAdapter.kt:93, 107, 122` (hardcoded `Dispatchers.IO`)
- **Description:** Three state-side classes in the same block do the same operation ("dispatch DAO work onto Dispatchers.IO") with **three different mechanics**:
  - `PipelineRecovery` (line 100) takes an **injectable `ioContext: CoroutineContext = Dispatchers.IO`** parameter with a verbose justification KDoc ("Tests using `runTest { testScheduler.advanceUntilIdle() }` would otherwise have to busy-wait for the IO block to complete — flaky and slow"). Tests inject `EmptyCoroutineContext`.
  - `PipelineOrphanCleaner` (line 82) hardcodes `withContext(Dispatchers.IO)` and tests use `runBlocking { cleaner.cleanup(...) }`.
  - `PipelineSessionRepoAdapter` (lines 93, 107, 122) hardcodes `withContext(Dispatchers.IO)` for 3 `suspend` methods and tests use `runBlocking { adapter.loadPending() }`.
  The `PipelineRecovery` constructor's `ioContext` parameter was deliberately added per the C10 deviation table ("`runTest` schedulers stay in sync") — but the two sibling classes were left with hardcoded `Dispatchers.IO`. The argument for `ioContext`-injection applies symmetrically to both (the spec doesn't mandate `runBlocking` over `runTest`; both work).
- **Why it matters:** Same-operation-two-ways drift. A future C-12+ refactor that introduces `runTest`-based tests for `PipelineSessionRepoAdapter` or `PipelineOrphanCleaner` will have to add an `ioContext` parameter to those classes too, and may pick a different name — entrenching three slightly-different parameter shapes. The C10 IMPL log calls out the C7-baseline compatibility constraint for `PipelineRecovery` (kept the legacy single-arg constructor) — that constraint is **why** `ioContext` is on `PipelineRecovery`, but the same rationale would apply to the two new classes if anyone ever wanted runTest-based tests.
- **Suggested fix scope:** small (add the same `ioContext: CoroutineContext = Dispatchers.IO` parameter to both `PipelineOrphanCleaner` and `PipelineSessionRepoAdapter`, with the same KDoc paragraph as `PipelineRecovery`. Tests stay green with default value).
- **Suggested fix:** Either (preferred) propagate `ioContext` to all three classes for symmetry, OR (alternative) document in a one-paragraph "Coroutine-dispatch convention" section in `docs/architecture/state-architecture/` or a knowledge skill stating that injectable `ioContext` is the standard for state-side suspending classes that DAOs reach. The convention should then be backfilled into the two laggards as opportunity arises.

### AUDIT-CONVENTION-B3-6

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/PipelineOrphanCleaner.kt` (uses `try/catch` 4 times) vs. `app/src/main/java/net/devemperor/dictate/core/CacheDirAudioFileFactory.kt:107` (uses `runCatching`) vs. `app/src/main/java/net/devemperor/dictate/migration/LegacyAudioFileMigration.kt:84, 90` (uses `runCatching`) vs. `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt:246-251` (uses `try/catch` for the same `File.delete()` operation that `CacheDirAudioFileFactory` does with `runCatching`)
- **Description:** Error-handling style for **best-effort `File.delete()` calls** is split across two idioms:
  - `runCatching { f.delete() }.onFailure { Log.w(TAG, "...", it) }` — used by `CacheDirAudioFileFactory.cleanupOrphans` (line 107) and `LegacyAudioFileMigration.run` (lines 84-85, 90-96 wraps the DAO call too).
  - `try { file.delete() } catch (t: Throwable) { Log.w(TAG, "...", t) }` — used by `PipelineRecovery.deleteAudioOpportunistic` (lines 246-251) and `PipelineOrphanCleaner.cleanupOrphanedTerminalAudio` (line 136-140 wraps a `!file.exists() || file.delete()` expression).
  The semantic intent is identical — "delete a file, log on failure, never throw". The Kotlin idiom for that is `runCatching { ... }.onFailure { Log.w(...) }` (one-liner, no boilerplate). The `try/catch` shape is more familiar to Java readers but is 4 lines for the same operation.
- **Why it matters:** Three of the seven new files use one idiom, three use the other, in the same block, for the same operation. A future implementer picks whichever they last saw — drift entrenches.
- **Suggested fix scope:** small (mechanical pass; pick `runCatching { ... }.onFailure { ... }` per Kotlin idiom and rewrite the 3-4 `try/catch` sites that match the pattern). Note that `PipelineRecovery.safeUpdateStatus / safeUpdateError / safeClearAudioPath` use `try/catch` because the catch needs to swallow + log AND continue — that's slightly more involved than a one-liner `delete()`. Those don't need rewriting.
- **Suggested fix:** Adopt `runCatching { fileOp }.onFailure { Log.w(TAG, "fileOp failed", it) }` for all single-statement best-effort file operations. Document the convention in a one-paragraph "Best-effort-IO error handling" section in `docs/architecture/state-architecture/`.

### AUDIT-CONVENTION-B3-7

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/database/entity/SessionStatus.kt`, `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo4.kt`, `app/src/main/java/net/devemperor/dictate/core/RecordingTimerAdapter.kt`, `app/src/main/java/net/devemperor/dictate/core/AmplitudeStreamAdapter.kt`, `app/src/main/java/net/devemperor/dictate/core/BorderGlowAdapter.kt`
- **Description:** Inline-anchor convention drift across the 12+ new B3 files. The `knowledge-doc-format` skill §"Inline anchors" + Engineering-Principles ("document non-obvious why") recommend at least the **plan/spec `@see` anchor** so a reader can ctrl-click from the class KDoc to the canonical spec section.
  - **Files with `@see docs/plans/...` anchor (7):** `RecordingHardwareAdapter.kt`, `BluetoothScoSubsystemAdapter.kt`, `AudioFocusSubsystemAdapter.kt`, `PipelineCallbackBridge.kt`, `CacheDirAudioFileFactory.kt`, `PipelineSessionRepoAdapter.kt`, `PipelineOrphanCleaner.kt`, `PipelineRecovery.kt`, `LegacyAudioFileMigration.kt` (9 total — the bulk).
  - **Files without `@see docs/plans/...` anchor (5):** `SessionStatus.kt`, `MigrationTo4.kt`, `RecordingTimerAdapter.kt`, `AmplitudeStreamAdapter.kt`, `BorderGlowAdapter.kt`. All five carry spec-section references **in prose** ("Spec 1 §4.7", "Spec 1 §6.1 + §6.2 R.17", etc.), which is searchable but not Ctrl-clickable from the IDE.
- **Why it matters:** Same-operation-two-ways drift in the inline-anchor convention. The 7 anchored files set the pattern; the 5 anchorless files miss it. A reader landing on `SessionStatus.kt` has no Ctrl-click path to Spec 1 §6.1; one landing on `PipelineSessionRepoAdapter.kt` does. This same finding pattern (`@see` URI form) was already flagged in B1-AUDIT-CONVENTION-B1-2 — the convention has not settled.
- **Suggested fix scope:** small (5 KDoc additions, 1-2 lines each at the top of the type).
- **Suggested fix:** Add a single `@see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §6.1 §6.1.3` to `SessionStatus.kt`; `§6.1` to `MigrationTo4.kt`; `§4.7 §15.x` to the three placeholder adapter files. Adopt the same path-with-spaces form as the other 7 files (no quoting needed; KDoc renders it as plain text, but a search across the worktree picks it up consistently).

### AUDIT-CONVENTION-B3-8

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/PipelineServiceStubSubsystems.kt:217-239` (and `:185-195`) — stubs marked deprecated in prose, not via `@Deprecated`
- **Description:** Two members of `PipelineServiceStubSubsystems` are marked deprecated **in prose** (`stubSessionRepo`, `audioFileFactory`):
  - Line 184-185: *"Deprecated `AudioFileFactory` stub (C7 baseline)..."*
  - Line 217: *"**C10 — deprecated (kept for compile-compat only).**"*
  Neither carries the Kotlin `@Deprecated` annotation — the IDE warning + ProGuard / lint won't surface a "deprecated call" on the call sites. The remaining 6 members of the same `PipelineServiceStubSubsystems` object (`bluetoothSco`, `pipelineRunner`, `notificationCoordinator`, etc.) are **not** deprecated — they are still wired into production `DictatePipelineService.onCreate` (lines 287, 322, 324). Two stubs are post-replacement no-ops kept for test compatibility; the other six are pre-replacement production stubs. A `@Deprecated(message = "Replaced by ...", replaceWith = ReplaceWith("..."))` on the two retired stubs would surface IDE warnings on accidental new use, and **distinguish** them from the six still-live stubs.
- **Why it matters:** The C10 / C11 chunk prompts explicitly mark these as "deprecated/test-only" in the work plan. Inline `@Deprecated` is the language-level mechanism for that; prose-only deprecation depends on every future caller reading the KDoc.
- **Suggested fix scope:** small (2 annotations).
- **Suggested fix:** Add `@Deprecated("Replaced by PipelineSessionRepoAdapter in C10 — kept for test-only compile-compat", level = DeprecationLevel.WARNING)` on `stubSessionRepo` and `PipelineServiceStubSubsystems.audioFileFactory`. Tests that use them get a warning; the IDE renders them with strikethrough.

### AUDIT-CONVENTION-B3-9

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/migration/LegacyAudioFileMigration.kt` (new top-level `migration/` package) vs. `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo4.kt` (existing sub-package)
- **Description:** Two new migration-themed files in B3, two different package locations:
  - `MigrationTo4.kt` lives under `net.devemperor.dictate.database.migration` (existing package; co-located with `MigrationTo3.kt`, `MigrationTo2.kt` (`Migrations.kt`), and `DictateDatabase.kt`).
  - `LegacyAudioFileMigration.kt` lives under `net.devemperor.dictate.migration` — a **new top-level package** that B3 introduces as a sibling of `database`, `preferences`, `core`, `state`, `ai`, etc.
  The two operations are conceptually similar — both run once per install/update against the DB to migrate data — but live in different packages with different naming conventions (`MigrationTo<N>` for Room schema migrations vs. `LegacyXxxMigration` / `XxxLegacyMigration` for one-shot data migrations). The pre-existing `InputLanguagesLegacyMigration.kt` lives under `net.devemperor.dictate.preferences/` (i.e. **co-located** with the prefs surface it migrates) — that establishes the precedent: legacy migrations live alongside the surface they operate on. By that rule, `LegacyAudioFileMigration` should live in `core/` (next to `CacheDirAudioFileFactory.kt` and `DictatePipelineService.kt`) or in `database/migration/` (next to `MigrationTo4.kt`, since the operation IS a DB UPDATE plus a file delete). The new top-level `migration/` package has only one file in it.
- **Why it matters:** Future migrations will look at this package layout and pick whichever they last saw — three different homes for one concept ("legacy migration"). The cost-of-deferral is paid by every future implementer + every future reader doing a "where do migrations live?" search.
- **Suggested fix scope:** small (move `app/src/main/java/net/devemperor/dictate/migration/LegacyAudioFileMigration.kt` and its test `app/src/test/java/net/devemperor/dictate/migration/LegacyAudioFileMigrationTest.kt` to either `core/` (co-located with the audio-file factory it complements) or `database/migration/` (co-located with the Room schema migration that motivates it). Update the package declaration + the 1 import site in `DictatePipelineService.kt`. The new top-level `migration/` directory then goes away).
- **Suggested fix:** Move to `net.devemperor.dictate.core` (next to `CacheDirAudioFileFactory.kt`) — `LegacyAudioFileMigration` is the **transition** between the pre-refactor IME-side fixed-path audio-file and the new factory-allocated layout; co-locating it with the factory matches the precedent set by `InputLanguagesLegacyMigration.kt` living in `preferences/` (next to `DictatePrefs.kt`). Document the convention ("legacy migrations co-locate with the surface they migrate; Room schema migrations live in `database/migration/`") in a one-paragraph addition to `docs/DATABASE-PATTERNS.md` §"Migration Conventions" (currently a placeholder).

### AUDIT-CONVENTION-B3-10

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/database/dao/SessionDao.kt:249-252` (`OrphanedAudioRow`)
- **Description:** The `OrphanedAudioRow` projection class is declared at **top-level** in `SessionDao.kt` (line 249), not as a nested class inside the `SessionDao` interface. The KDoc on the class explains the intent: *"Lives next to `SessionDao` because it is purely a DAO projection — Room synthesises the column mapping at compile time"*. That's a valid argument **for keeping it in the same file**, but Room equally supports `@Embedded` and nested projection classes — and several pre-existing DAOs in the project (e.g. `transcriptionDao.kt`, `processingStepDao.kt`) have their projection classes nested. The OrphanedAudioRow placement is a single deviation; the precedent in the project goes the other way.
- **Why it matters:** Same-operation-two-ways drift for DAO projection placement. The KDoc explanation is good but doesn't address why the rule is being inverted for this one row type.
- **Suggested fix scope:** small (move `data class OrphanedAudioRow(...)` inside the `SessionDao` interface block, or document the choice as a convention in `docs/DATABASE-PATTERNS.md` §"Migration Conventions").
- **Suggested fix:** Either (a) nest the projection inside the DAO interface to align with the project's other DAO projections, or (b) document in DATABASE-PATTERNS.md that DAO projections may live at top-level **in the same file as the DAO** as long as the KDoc names the reason — and apply that retroactively. (a) is less typing; (b) is more flexible.

## Out-of-scope observations (other topics)

- **(plan-and-api)** `MigrationTo4Test`'s instrumented tests are tagged "local-only" per Spec 1 §11.7.0a — no CI invocation. The C9 IMPL log confirms the developer didn't run `./gradlew connectedDebugAndroidTest` (no connected device). AUDIT-PLAN-AND-API should verify the schema export at `app/schemas/.../4.json` is consistent with the migration body (i.e. the CHECK constraint extension is reflected in the Room-generated schema).
- **(logic)** `PipelineRecovery.recover()` calls `sessionDao.findPendingInsertion()` twice — once via `sessionRepo.loadPending()` (line 135, mapped through the adapter), and once again on line 150 for the SF-4 dispatch loop. The two queries are identical (same SQL, same row set), but they execute against separate IO-context blocks. AUDIT-LOGIC should verify the duplicate query is intentional (avoid filtering twice) or if a single fetch could be reused.
- **(test)** Coverage for `PipelineOrchestrator.persistNewSession` KG-AFF-1 patch (the `runCatching { audioFile.delete() }` patch in C11) is acknowledged-missing in the C11 IMPL log. AUDIT-TEST should decide whether the coverage gap is acceptable (the C11 log calls it out for B4 AUDIT-TEST follow-up).

## Coverage

- **Files audited (full-block scope):** all 30 production files modified + created in the B3 diff; the 14 new JVM test files were spot-checked for K-1 / K-4 conformance (headers + grep). The 7 new androidTest files were verified for Runner / Helper / Rule usage. `app/build.gradle` + `gradle/libs.versions.toml` reviewed for the androidTest wiring (Spec 1 §11.7.0a). `app/src/main/res/values*/strings.xml` reviewed for translation parity.
- **Files skipped (with reason):** the new `FakeSessionDao.kt` testutil (~160 LoC) was opened but only the class signature + the 8 new method stubs were verified; full per-method assertion content is AUDIT-TEST scope. The C8 `RecordingHardwareAdapterTest` and related Robolectric tests were verified only for K-4 justification + class header; the per-assertion content is AUDIT-TEST scope.
- **Knowledge-skill checkpoints applied:**
  - `knowledge-reference` Quick-Reference table — no `versioned-envelope` / `plugin-system` patterns in B3 scope. The state-side adapters (`PipelineSessionRepoAdapter`, `PipelineOrphanCleaner`) follow the same "thin-adapter-over-interface" pattern as the C8 hardware adapters; consistent.
  - `knowledge-doc-format` §"Inline anchors" — used to drive AUDIT-CONVENTION-B3-7 (`@see docs/plans/...` anchor presence across the 12 new files).
  - `knowledge-doc-format` §"SSoT — anti-redundancy rule" — verified the SessionStatus Kotlin enum values + SQL CHECK literal are the documented exception (the DATABASE-PATTERNS.md Double-Enum pattern explicitly mandates the duplication; it's an enforced invariant, not a SSoT violation).
  - `docs/DATABASE-PATTERNS.md` Double-Enum checklist — used to drive AUDIT-CONVENTION-B3-3 (entity vs SQL default mismatch).
  - CLAUDE.md "Preferences via `DictatePrefs.kt` sealed class" rule — used to drive AUDIT-CONVENTION-B3-2.

**Severity tally:**
- Critical: 0 (no K-1/K-4 violation, no Mockito/MockK introduced, no Pref-raw-string-key in module/reducer code, no Double-Enum invariant broken, CHECK-Recreate template followed correctly).
- Important: 3 — locale-translation parity gap (B3-1), raw-string-key in legacy migration (B3-2), Kotlin/SQL default-value mismatch (B3-3).
- Nice-to-have: 7 — dead `Log`/`TAG` in the adapter (B3-4), `ioContext`-injection asymmetry across 3 sibling state classes (B3-5), `runCatching` vs `try/catch` split across 7 files for the same best-effort file ops (B3-6), `@see docs/plans/...` anchor missing in 5/12 new files (B3-7), prose-only deprecation on stubs (B3-8), `migration/` top-level package introduced for a single file (B3-9), DAO projection placement drift (B3-10).
