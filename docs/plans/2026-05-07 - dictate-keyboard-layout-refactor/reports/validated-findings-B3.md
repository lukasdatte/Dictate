# Validated Findings — Block 3

**Agent-ID:** B3-VAL-SANITY
**Date:** 2026-05-15
**Source audits:**

| Audit | Findings (C / Imp / NTH) | File |
|-------|--------------------------|------|
| AUDIT-PLAN-AND-API | 0 / 4 / 4 | `./reports/audit-plan-and-api-B3.md` |
| AUDIT-CONVENTION | 0 / 3 / 7 | `./reports/audit-convention-B3.md` |
| AUDIT-LOGIC | 2 / 5 / 3 | `./reports/audit-logic-B3.md` |
| AUDIT-TEST | 0 / 2 / 3 | `./reports/audit-test-B3.md` |
| **Total raw** | **2 / 14 / 17 = 33 raw findings** | |

After de-duplication, false-positive elimination, and merge of cross-audit overlaps:

## Summary

- 🟢 valid + auto-fixable: **27** (Critical: 0, Important: 12, Nice-to-have: 15)
- 🟡 valid + research-needed: **2** (Critical: 2, Important: 0, Nice-to-have: 0)
- ❌ eliminated: **0** (no false-positives; 4 cross-audit overlaps merged)

Plus **1 known-gap forward-flag** (Important, marker `latent-coupled-to-F-5`) — `LOGIC-B3-10` deferred to whenever `Effect.StartMediaRecorder` lands.

## Repair-wave recommendation

**2 waves**, sequential:

1. **Wave 1 — Research-then-fix the 2 Criticals (F-1 + F-2).**
   - Spawn 1 research-agent with topic `b3-cleanup-cascade-and-backfill-policy` (single combined research because both findings touch the M3→M4 cleanup-policy decision in Spec 1 §6.2 R.17 + §6.5).
   - Resume same agent as `VAL-REPAIR-1` to apply the resulting migration patch + cleanup-query change + backfill change.
   - Estimated diff: 1 new Room migration (`MigrationTo5.kt`) OR an in-place fix to `MigrationTo4.kt` if it's safe to amend (worktree hasn't shipped) + 1 query change in `SessionDao.deleteInsertedOlderThan` + KDoc in 2 files. The migration-vs-amend choice is part of the research.

2. **Wave 2 — Auto-fix the remaining 27 🟢 findings in one sweep.**
   - VAL-REPAIR (consolidator-as-implementer via resume).
   - Largest batches: 12 Important + 15 Nice-to-have, mostly mechanical (string-resource adds, KDoc anchors, raw-pref→Pref entry, dead-Log cleanup, `runCatching` style sweep, `@Deprecated` annotations, file move).

Alternative single-wave path possible but **not recommended**: bundling research with 27 🟢s in one implementer invocation risks context-thrash on the migration-decision; the research-agent's focused output is shorter and cleaner if isolated.

## Cross-cut patterns

- **Pattern 1 — `LegacyAudioFileMigration` raw-pref-key (2 audits, same root):** AUDIT-CONVENTION-B3-2 + AUDIT-LOGIC out-of-scope obs flagged the same raw `"legacy_audio_purged_v4"` SharedPreferences key. Merged into F-7. Same drift class as B2-VAL-W1 (`OverlayModule.runEffect` raw keys) — second occurrence in two blocks, suggests the project would benefit from a lint check or pre-commit hook.

- **Pattern 2 — KG-AFF-1 sofort-delete uncovered (2 audits, same gap):** AUDIT-TEST-B3-1 + AUDIT-LOGIC out-of-scope obs both flagged the missing unit test for `PipelineOrchestrator.persistNewSession` `runCatching { audioFile.delete() }`. Merged into F-22.

- **Pattern 3 — `PipelineRecovery.findPendingInsertion()` double-query (2 audits, same finding):** AUDIT-LOGIC-B3-6 + AUDIT-CONVENTION out-of-scope obs flagged the same duplicate DB query. Merged into F-19.

- **Pattern 4 — Forward-compat documentation thinness:** PLAN-AND-API-B3-4 (stubbed `pipelineRunner` + `notificationCoordinator`) + PLAN-AND-API-B3-7 (LanguageController D-13 bridge follow-up) + PLAN-AND-API-B3-5 (PipelineRecovery legacy ctor footgun) all share a "B5/B6 follow-up tracking thin" theme. None block B3 closure; together they indicate the block-report's "Issue Index → carried-forward to next block" mechanism is under-exercised. Repair-fixes will add explicit Issue-Index entries.

- **Pattern 5 — Same-operation-two-ways style drift (5 findings, 1 block):** AUDIT-CONVENTION-B3-5 (`ioContext` injection asymmetry), B3-6 (`runCatching` vs `try/catch`), B3-7 (`@see` anchor presence 7-of-12), B3-8 (prose-only vs `@Deprecated`), B3-10 (DAO projection placement). All Nice-to-have, all mechanical to align. Repair-wave 2 includes a style-pass touching ~12 files.

- **Pattern 6 — Test-quality count drift in block-report (1 finding):** AUDIT-TEST-B3-5 — block-report aggregates have a +5/-1 = +4 drift in `PipelineCallbackBridgeTest` (19 claim, 14 actual) + `DictatePipelineServiceCompositionTest` (14 claim, 15 actual). The aggregate "141 net-new tests after B3" headline is still correct. Mechanical correction in F-31.

---

## Findings

### F-1 (was AUDIT-LOGIC-B3-1)

- **Classification:** 🟡 valid + research-needed
- **Severity:** Critical
- **Files:** `app/src/main/java/net/devemperor/dictate/database/dao/SessionDao.kt:143-144`, `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo4.kt:97`, plus the `sessions` FK definition (parent_session_id ON DELETE CASCADE — declared in M2→M3 schema, carried forward).
- **Description:** `deleteInsertedOlderThan(cutoff)` is a plain `DELETE FROM sessions WHERE inserted_at IS NOT NULL AND inserted_at < :cutoff`. The `sessions` table has a self-referential FK `parent_session_id REFERENCES sessions(id) ON DELETE CASCADE`. When a 7d+ COMPLETED parent ages out, the cascade wipes its POST_PROCESSING children — including children the user created moments earlier whose own `inserted_at` is fresh. Concrete scenario: user records text on day 1 + inserts; on day 8 opens the history row + applies a translate prompt; the new POST_PROCESSING child has `inserted_at = day 8`. At the next idle-stop the day-1 parent matches the cutoff, cascade wipes the day-8 child the user JUST created. Silent data loss.
- **Research topic:** `b3-cleanup-cascade-and-backfill-policy` (combined with F-2)
- **Why research:**
  - Fix options diverge architecturally:
    - (a) Add `AND id NOT IN (SELECT parent_session_id FROM sessions WHERE parent_session_id IS NOT NULL)` to the DELETE — keeps current FK, query-only fix.
    - (b) Change FK to `ON DELETE SET NULL` — requires Spec 1 §6.5 clarification + new migration `MigrationTo5.kt` (FK changes need table-recreate per SQLite — Spec 1 §11.7.0 KG-SST-3 cascade rules apply).
    - (c) Recurse into children's `inserted_at`-eligibility before deleting parent — more complex query, same FK.
  - The choice affects forward-compat with history view + parent-child UX (does "deleting a parent" mean "the children become root-level history items" or "they vanish too"?).
  - Spec 1 §6.5 cleanup-policy text needs to commit to a position. Worktree hasn't shipped — M3→M4 can still be amended OR a new M4→M5 added.
- **Suggested fix scope:** medium (1 query change OR 1 new migration). Research-agent must justify the choice referencing ADR-0001 + Spec 1 §6.2 R.17 + §6.5 + the M3→M4 backfill semantics.
- **Domain bundle candidate:** F-1 + F-2 — single research file, combined fix.

### F-2 (was AUDIT-LOGIC-B3-2)

- **Classification:** 🟡 valid + research-needed
- **Severity:** Critical
- **File:** `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo4.kt:118-131`
- **Description:** The M3→M4 migration backfills `inserted_at = created_at` for every pre-existing `status = 'COMPLETED' AND final_output_text IS NOT NULL` row. EVERY historical COMPLETED row whose `created_at` is older than 7 days becomes immediately eligible for `deleteInsertedOlderThan(now - 7d)` at the next idle-stop. First idle-stop after upgrade silently strips months of user history older than 7 days. The pre-M4 history list had no automatic cleanup — users have an implicit "history is forever" expectation that the migration silently breaks.
- **Research topic:** `b3-cleanup-cascade-and-backfill-policy` (combined with F-1)
- **Why research:**
  - Fix options:
    - (a) Backfill `inserted_at = max(created_at, now - gracePeriodMs + safetyBuffer)` — gives existing rows a fresh grace window.
    - (b) Amnesty flag in SharedPreferences — suppress `deleteInsertedOlderThan` for one cleanup cycle after migration.
    - (c) Backfill `inserted_at = NULL` for ALL pre-existing rows — they're never cleaned by the COMPLETED-grace path (NULL means "unknown age").
    - (d) Cleanup query requires `inserted_at >= migration_timestamp` (new column or pref) — pre-migration rows immune.
  - Spec 1 §6.5 must commit to a position. The cleanest fix per ADR-0001 (data-loss-avoidance) is likely (c) — leave pre-existing rows `inserted_at = NULL`, accept they're never auto-cleaned, document the trade-off.
- **Suggested fix scope:** medium (in-place edit to `MigrationTo4.kt` backfill IF worktree allows; otherwise new `MigrationTo5.kt` reverting the backfill + a one-time data-fix). Research-agent decides.
- **Domain bundle candidate:** F-1 + F-2 — single research, combined fix.

### F-3 (was AUDIT-PLAN-AND-API-B3-1)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:501-530` (`onDestroy`)
- **Description:** Service `onDestroy` is missing both the `runBlocking + withTimeout(2_000L) { orchestrator.shutdown() }` wrapper AND the Pre-Cancel-Dispatch step. ADR-0003 §"Required mechanics" item 8 (timeout wrapper) and item 9 (Pre-Cancel-Dispatch) are BINDING ("Required mechanics" not "Optional"); §"Failure Modes" explicitly lists them as "what goes wrong if violated" — MediaRecorder native-heap leak when service is destroyed during active recording. Spec 1 §10 Block-2 acceptance "MediaRecorder-release-Pfad" + "onDestroy-Timeout" cannot be checked off as long as this gap stands.
- **Suggested fix:** Add the literal Spec 1 §7.3 snippet:
  - Pre-Cancel-Dispatch: read state snapshot; if `state.recording !is Idle` dispatch `Action.RecordingAction.CancelRecording` through `runEffect` path (already exists).
  - Timeout wrapper:
    ```kotlin
    try {
        runBlocking { withTimeout(2_000L) { orchestrator.shutdown() } }
    } catch (e: TimeoutCancellationException) {
        Log.w(TAG, "orchestrator.shutdown() timed out at 2s — proceeding with onDestroy", e)
    }
    ```
- **Domain bundle candidate:** `DictatePipelineService.kt` (with F-4)

### F-4 (was AUDIT-PLAN-AND-API-B3-2)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:** `app/src/main/java/net/devemperor/dictate/settings/PreferencesFragment.java:286-298`, `app/src/main/res/values/strings.xml`, `values-de/`, `values-es/`, `values-pt/strings.xml`
- **Description:** KG-AFF-3 race-protect for "active recording during Cache leeren" missing. Spec 1 §4.11.6.3 + §10 Block-4 acceptance mandate an `isRecordingActive()` guard + `dictate_cache_clear_blocked_recording` toast string. Current implementation goes straight from confirm dialog → `clearCacheRecursively(cacheDir)` with no recording-state check. The string does not exist in any locale file.
- **Suggested fix:**
  - Add `dictate_cache_clear_blocked_recording` to `values/strings.xml` ("Cache cannot be cleared while recording is active.") and the 3 sibling locales (DE/ES/PT).
  - In `PreferencesFragment.java:286-298` cache-leeren click handler: before `clearCacheRecursively`, snapshot recording-state via `pipelineBinder` (same path the IME uses) — if `state.recording !is Idle`, show `Toast.makeText(..., R.string.dictate_cache_clear_blocked_recording, LENGTH_LONG)` and return.
- **Domain bundle candidate:** `PreferencesFragment.java` + 4 locale string files

### F-5 (was AUDIT-PLAN-AND-API-B3-3)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:1617-1620`
- **Description:** Residual legacy path bypasses `CacheDirAudioFileFactory`. The import-an-audio-file flow allocates `new File(getCacheDir(), DictatePrefsKt.get(sp, Pref.TranscriptionAudioFile.INSTANCE))` — cache root (fixed legacy name), not `cacheDir/audio/`. The file falls OUTSIDE `CacheDirAudioFileFactory.cleanupOrphans`'s scope, leaks indefinitely. Spec 1 §11.2.2 Block-4 step 13 calls for "Allokations-Zeile Z. 1612" deletion; block report cited 1612 but the second allocation site at 1617 (import-audio-file) is not addressed.
- **Suggested fix:** Route the import flow through `pipelineBinder.getAudioFileFactory().allocate()` (matching the regular `startRecording` Pre-Dispatch path at line 1834-1851) with an IOException fallback that shows `dictate_storage_full` toast. The import-audio-file flow then gains KG-AFF-4 freshness cutoff + KG-AFF-5 cleanup visibility.

### F-6 (was AUDIT-PLAN-AND-API-B3-4)

- **Classification:** 🟢 valid + auto-fixable (documentation-only, not real-impl this block)
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:322,324` + `B3-migration-persistence-audiofactory.md` (block-report Deviations table)
- **Description:** Three subsystems still come from `PipelineServiceStubSubsystems` in production: `pipelineRunner` (log-only no-ops), `notificationCoordinator` (no real notification updates from state changes), `bluetoothSco` (only on defensive `audioManager == null` fallback — production path is real). The block-report C8 narrative claims migration "covers all subsystems per Spec 1 §11.2.2 Block-3" but `PipelineRunnerSubsystem` + `PipelineNotificationCoordinatorSubsystem` are still stubbed.
- **Why documentation-only fix:** Both subsystems' real implementations are legitimately B5/B6 scope per the phased plan (PipelineRunner needs orchestrator-side recording wiring; NotificationCoordinator needs Spec 1 §7.4 class that doesn't exist yet). The audit's "or fix the notification coordinator" alternative would require new files outside B3 scope. The repair is therefore a block-report Deviations-table entry + Issue-Index entry with explicit B5/B6 follow-up pointer.
- **Suggested fix:** Update `B3-migration-persistence-audiofactory.md` Deviations table with an entry: *"PipelineRunnerSubsystem + PipelineNotificationCoordinatorSubsystem stubbed in production — real impls defined in Spec 1 §7.4/§7.5 but tracked for B5/B6 where orchestrator drives recordings end-to-end. Acceptance bullet '§10 Block-2: Beim Recording: persistente Notification sichtbar' carried forward."* Plus an Issue-Index row with `delegated-to-orchestrator` status. Also clarify the C8 narrative's "covers all subsystems" wording.

### F-7 (was AUDIT-CONVENTION-B3-2 + AUDIT-LOGIC out-of-scope obs, merged)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:** `app/src/main/java/net/devemperor/dictate/migration/LegacyAudioFileMigration.kt:70-79`, `app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt`
- **Description:** `LegacyAudioFileMigration` uses a raw-string SharedPreferences key (`FLAG_PREF = "legacy_audio_purged_v4"`) accessed via `PreferenceManager.getDefaultSharedPreferences(...).getBoolean(FLAG_PREF, false)` (line 79) and `.putBoolean(FLAG_PREF, true).apply()` (line 98). Violates CLAUDE.md project-wide rule "Preferences are always accessed through `DictatePrefs.kt` sealed class — never use raw string keys." Same drift class as B2-VAL-W1.
- **Suggested fix:**
  - Add to `DictatePrefs.kt` "Internal State" section: `object LegacyAudioPurgedV4 : Pref<Boolean>("net.devemperor.dictate.legacy_audio_purged_v4", false)`.
  - Replace the 3 raw-string sites in `LegacyAudioFileMigration.kt` with `sp.get(Pref.LegacyAudioPurgedV4)` + `sp.edit().put(Pref.LegacyAudioPurgedV4, true).apply()`.
  - Rename `LegacyAudioFileMigration.FLAG_PREF` constant → `flagPrefKey()` returning `Pref.LegacyAudioPurgedV4.key` (preserves test-helper readability for `LegacyAudioFileMigrationTest`).
- **Domain bundle candidate:** `LegacyAudioFileMigration.kt` (with F-13)

### F-8 (was AUDIT-CONVENTION-B3-3)

- **Classification:** 🟢 valid + auto-fixable (option (a) — KDoc carve-out)
- **Severity:** Important
- **Files:** `app/src/main/java/net/devemperor/dictate/database/entity/SessionEntity.kt:36`, `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo4.kt:75-79`, `docs/DATABASE-PATTERNS.md`
- **Description:** Kotlin entity default for `status` is `SessionStatus.RECORDED.name`; SQL `DEFAULT` is `'COMPLETED'`. DATABASE-PATTERNS.md "Checklist for new Double-Enum columns" mandates the two defaults match. Drift pre-existed in `MigrationTo3.kt` but B3 inherited it without flagging.
- **Suggested fix (option (a), lowest-risk):** Add a `**Why DEFAULT 'COMPLETED' (not 'RECORDED')?**` paragraph to `MigrationTo4.kt`'s KDoc — SQL DEFAULT is for legacy-row backfill safety (post-migration safe state); Kotlin default `RECORDED` is correct for app-side construction (freshly-created session not yet transcribed). Update DATABASE-PATTERNS.md checklist to document the carve-out for backfill migrations.

### F-9 (was AUDIT-CONVENTION-B3-1)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:** `app/src/main/res/values-de/strings.xml`, `values-es/strings.xml`, `values-pt/strings.xml`
- **Description:** `dictate_status_recording = "Recording…"` + `dictate_status_transcribing = "Transcribing…"` exist only in English `values/strings.xml:342-343`. Consumed by `HistoryAdapter.java:152,158`. The four sibling status strings (`_recorded`, `_failed`, `_cancelled`, `_running`) ARE translated to DE/ES/PT — asymmetric drift.
- **Suggested fix:** Add to each locale file:
  - DE: "Aufnahme läuft…" / "Transkription läuft…"
  - ES: "Grabando…" / "Transcribiendo…"
  - PT: "Gravando…" / "Transcrevendo…"

### F-10 (was AUDIT-LOGIC-B3-3)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt:184-195` (Preparing→Active reducer arm) + `RecordingHardwareAdapter.kt:94-112` (`start()`)
- **Description:** `RecordingHardwareSubsystem.start()` is unreachable from any module Effect. The interface KDoc says "Begin recording — must follow a successful `allocate`", but the Preparing→Active reducer only emits `Effect.StartTimer`, `Effect.StartAmplitudeStream`, `Effect.StartBorderGlow` — no effect calls `services.recordingHardware.start()`. Dormant in Phase 1 (IME's `RecordingManager` still drives recording); breaks at B5/B6 when LayoutCatalog routes through orchestrator (MediaRecorder.prepare() runs, `start()` is never called, file is empty when Stop arrives).
- **Suggested fix:**
  - Add `data object StartMediaRecorder : Effect` to `RecordingModule.Effect`.
  - Append it to the Preparing→Active reducer's side-effect list.
  - Add `Effect.StartMediaRecorder -> services.recordingHardware.start()` to `runEffect`.
  - **Bundled with F-11 (LOGIC-B3-10):** add the matching `reduceFailure` arm for `Effect.StartMediaRecorder` so a start-failure rolls FSM back to Idle + emits `Effect.ReleaseMediaRecorder`.

### F-11 (was AUDIT-LOGIC-B3-10, bundled with F-10)

- **Classification:** 🟢 valid + auto-fixable (paired with F-10 — applied together)
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt:343+` (`reduceFailure`)
- **Description:** When F-10 adds `Effect.StartMediaRecorder`, `reduceFailure` needs a matching arm. Today only `AllocateMediaRecorder` + `StopMediaRecorder` arms exist. Without this, a `MediaRecorder.start()` IllegalStateException leaves the FSM in Active while hardware is broken.
- **Suggested fix:** Add reducer arm: `Effect.StartMediaRecorder → Active → Idle` + emit `Effect.ReleaseMediaRecorder`. Applied together with F-10 in the same edit.

### F-12 (was AUDIT-LOGIC-B3-4)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:346-381` (onCreate order)
- **Description:** `DictateOrchestrator.<init>` does `scope.launch { recovery.recover(store) }` with `scope = Dispatchers.Main.immediate`. The launch returns immediately but inline body runs synchronously up to first `withContext(ioContext) { runStatusPromotion() }`. Then `onCreate` proceeds to run `LegacyAudioFileMigration.run(applicationContext)` synchronously on Main — both paths concurrently touch `sessions` table. Room serializes via single SQLite connection (no corruption) but logical ORDER is non-deterministic: a `RECORDING` row's `last_error_message` is non-deterministically either `recording-interrupted-by-process-death` (recovery) or `audio_file_path_legacy_purged` (LegacyAudioFileMigration). The comment block at lines 372-374 promises ordering that doesn't hold.
- **Suggested fix:** Move `LegacyAudioFileMigration.run(applicationContext)` to BEFORE `orchestrator = DictateOrchestrator(...)` construction. Migration is sub-100ms per its own KDoc; running it synchronously before recovery starts gives deterministic ordering and fulfills the existing comment-block rationale.

### F-13 (was AUDIT-LOGIC-B3-9)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt:117-118,269-301`
- **Description:** Convenience constructor `PipelineRecovery(sessionRepo)` wires `EmptySessionDao` (private singleton, ~20 no-op overrides) and `EmptyCoroutineContext`. Lives in production code, indistinguishable in usage from primary constructor — if accidentally wired in production, recovery silently does nothing (no §6.3 promotion, no SF-4 dispatch). Footgun.
- **Suggested fix:** Move `EmptySessionDao` + the secondary constructor to test fixtures at `app/src/test/java/net/devemperor/dictate/testutil/EmptySessionDao.kt`. Update C7-era tests (`DictateOrchestratorInitOrderTest` and similar) to import the test-only class explicitly. Production `PipelineRecovery` exposes only the primary constructor.

### F-14 (was AUDIT-LOGIC-B3-5)

- **Classification:** 🟢 valid + auto-fixable (B3 scope: data-shape change only; UI consumer wiring is B5/B6)
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/ResendModule.kt:119-125` + `app/src/main/java/net/devemperor/dictate/state/Action.kt:274`
- **Description:** `Action.ResendAction.NotifyManualPasteNeeded(val sessionId: String)` declares a `sessionId` payload, but the reducer ignores it — `ResendState.lastResultNeedsManualPaste` is single `Boolean`. PipelineRecovery dispatches one Notify per pending COMPLETED row; only the first sets the flag, remaining N-1 sessions are silently swallowed. User has no way to know which session's result needs pasting; once the user clears the flag, the others sit in DB indefinitely.
- **Suggested fix:** Replace `lastResultNeedsManualPaste: Boolean` with `pendingPasteSessionIds: Set<String>`. Reducer adds-on-Notify, removes-on-ClearForId (new action: `ClearManualPasteForSession(sessionId)`). Existing `ClearManualPasteFlag` semantics: clears all. The IME consumer wiring lands in B5/B6 — this fix is the data-shape change only; mark with `B5/B6 consumer wiring follow-up` in block-report.

### F-15 (was AUDIT-TEST-B3-1 + AUDIT-LOGIC out-of-scope obs, merged)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/test/java/net/devemperor/dictate/core/PipelineOrchestratorPersistTest.kt` (NEW) covering `PipelineOrchestrator.persistNewSession` KG-AFF-1 patch at lines 858-873
- **Description:** No unit test exercises the `runCatching { audioFile.delete() }` + WARN-log fallback. C11 known-gap acknowledges it ("flagged for B4 AUDIT-TEST if coverage policy demands it"). The plan-named test file `PipelineOrchestratorPersistTest.kt` does not exist.
- **Suggested fix:** Add a Robolectric (or `@get:Rule TemporaryFolder`) test class with 2-3 cases:
  - `persistNewSession_deletesCacheAudioFile_afterSuccessfulPersist` — happy path.
  - `persistNewSession_logsWarn_whenDeleteThrows` — failure path uses a `File` whose `delete()` returns `false` (read-only parent dir, or shadowed `File.delete()`).
  - `persistNewSession_succeedsEvenIfDeleteFails` — graceful-degrade contract.

### F-16 (was AUDIT-TEST-B3-4)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceCleanupOrderTest.kt` (NEW) — or extend `DictatePipelineServiceCompositionTest.kt`
- **Description:** `DictatePipelineService.onDestroy` → `triggerOrphanCleanupAsync()` invocation not exercised. `PipelineOrphanCleanerTest` covers the cleaner directly but not the Service-side wiring (where the cleanup is triggered, with which `referencedPaths` snapshot, ordering vs. `deleteInsertedOlderThan`). Plan §10 Block-3 Phase-B S-2 acceptance: "im `stopSelf`-Pfad läuft `dao.deleteInsertedOlderThan(cutoff)` VOR `cleanupOrphanedTerminalAudio()` VOR `stopSelf()`".
- **Suggested fix:** Add Robolectric `controller.destroy()` test that verifies:
  - The cleaner is invoked.
  - Invocation order: `deleteInsertedOlderThan` → `cleanupOrphanedTerminalAudio` → `stopSelf`.
  - The `referencedPaths` snapshot reflects the live `sessionDao.findAllAudioFilePaths()` at destroy time.
  - Survives a destroy-while-recording race (orchestrator.shutdown timeout from F-3 path).

### F-17 (was AUDIT-PLAN-AND-API-B3-5)

- **Classification:** 🟢 valid + auto-fixable (paired with F-13)
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt:117-118` — convenience constructor + `EmptySessionDao`
- **Description:** Duplicate-of F-13 — same root cause (legacy constructor + EmptySessionDao). F-13 is the canonical fix. Listed separately because PLAN-AND-API flagged it as "future-proof the test infrastructure" (NTH) while LOGIC flagged it as "production footgun" (Important). Merged severity: take Important (max-of-contributors) — already covered by F-13's fix.
- **Suggested fix:** Same as F-13. No additional work.

### F-18 (was AUDIT-PLAN-AND-API-B3-6)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/PipelineServiceStubSubsystems.kt:60` (header KDoc) + entire file
- **Description:** File-level KDoc still says "B3 fills this — real implementations land in Block 3 (subsystem-adapter migration, chunk C8) — pre-existing legacy classes (`RecordingManager`, `BluetoothScoManager`, …) get re-fronted by adapter shims at that point." After B3 the production usage has shrunk to 2 stubs + 1 fallback. The `MESSAGE = "B3 fills this …"` constant + per-property KDocs for `recordingHardware`, `bluetoothSco`, `audioFocus`, `recordingTimer`, `amplitudeStream`, `borderGlow` say "B3 fills this — module emitted an effect that the stub absorbs."
- **Suggested fix:** Mechanical KDoc rewrite — file-level KDoc reflects post-B3 reality ("After B3, this file retains only test-only stubs + production fallbacks for `audioManager == null`. Production wiring uses the *Adapter classes in core/."). Flip `MESSAGE` constant to "Test-only stub — real subsystem lives in the *Adapter classes in core/." Update per-property KDocs for the 6 properties.

### F-19 (was AUDIT-LOGIC-B3-6 + AUDIT-CONVENTION out-of-scope obs, merged)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt:135,150-152`
- **Description:** `recover()` calls `sessionRepo.loadPending()` (which executes `sessionDao.findPendingInsertion()`) at line 135, then `sessionDao.findPendingInsertion()` directly at line 150 for SF-4 dispatch. Same query, identical row set, two SELECT executions per recovery pass.
- **Suggested fix:** Restructure to read once: move `findPendingInsertion()` call to before Phase 2-3, capture the list, hydrate pendingSessions from it (combined with RECORDED-with-audio rows from `loadPending`), and iterate it again for SF-4 dispatch. Mind that `loadPending()` returns a union — refactor `PipelineSessionRepoAdapter.loadPending` to optionally accept a pre-fetched `pendingInsertion` list, OR split it into two methods (`loadRecording()` + accept `pendingInsertion` as caller-provided).

### F-20 (was AUDIT-LOGIC-B3-7)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/PipelineOrphanCleaner.kt:134-145`
- **Description:** `CleanupResult.deletedAudioFiles` field counts both "file actually deleted" AND "row whose audio_file_path was cleared (file already gone)". The KDoc documents the design but the metric is misleading when exposed in `Log.i(TAG, "orphan-cleanup: deletedAudioFiles=…")`.
- **Suggested fix:** Split into two counters — `filesActuallyDeleted` (only `delete()` returned true) + `rowsCleared` (existing semantic, covering both already-missing and just-deleted). Update the log statement and tests. Alternatively rename single field to `processedOrphanRows` if the simplicity is preferred. Pick the split (more information, clearer telemetry).

### F-21 (was AUDIT-LOGIC-B3-8)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/PipelineCallbackBridge.kt:71-77`
- **Description:** `catch (t: Throwable)` swallows `OutOfMemoryError`, `StackOverflowError`, `ThreadDeath`, `LinkageError`. KDoc rationale ("A misbehaving IME-side callback must NOT abort the pipeline thread") justifies catching `Exception`, not `Error`.
- **Suggested fix:** Replace `catch (t: Throwable)` with `catch (t: Exception)`. JVM Errors propagate (Crashlytics-visible); RuntimeException + IOException + NPE still get caught.

### F-22 (was AUDIT-PLAN-AND-API-B3-7)

- **Classification:** 🟢 valid + auto-fixable (documentation-only)
- **Severity:** Nice-to-have
- **File:** `B3-migration-persistence-audiofactory.md` (Issue-Index) + `app/src/main/java/net/devemperor/dictate/state/modules/LanguageModule.kt` (verify reducer arm)
- **Description:** D-13 LanguageController bridge (`Action.LanguageAction.RefreshFromPref` dispatch from one-line bridge in IME) is documented in C8 Deviations table but does NOT have an Issue-Index entry tracking "B4-B6 follow-up: delete LanguageController + bridge." Plus: verify `LanguageModule` actually has a `RefreshFromPref` reducer arm consuming the dispatched action.
- **Suggested fix:**
  - Add Issue-Index row in `B3-migration-persistence-audiofactory.md`: `I-D13-followup | Important | LanguageController bridge replaces direct subsumption per Spec 1 §9.6; follow-up deletion + LanguageModule.RefreshFromPref reducer-arm verify in B4-B6 | delegated-to-orchestrator | D5 sustainability — 30+ usages prevented inline deletion`.
  - Verify reducer arm exists; if missing, add it (single-line `state.copy(currentLanguageTag = effectiveLangFromPrefs())` reducer arm).

### F-23 (was AUDIT-PLAN-AND-API-B3-8)

- **Classification:** 🟢 valid + auto-fixable (block-report doc update — alternative-coverage confirmation)
- **Severity:** Nice-to-have
- **Files:** `B3-migration-persistence-audiofactory.md` Coverage section + adding `ResolverPreDispatchAllocateTest.kt`
- **Description:** Plan-named test files for B3+B4 acceptance:
  - `DictatePipelineServiceCleanupOrderTest.kt` — Spec 1 §10 Block-3 Phase-B S-2 acceptance. F-16 adds this file.
  - `DictatePipelineServiceBootOrphanCleanupTest.kt` — Spec 1 §10 Block-4 acceptance. Covered by `DictatePipelineServiceAudioFileFactoryWiringTest`. Confirm in block-report.
  - `PipelineOrchestratorPersistTest.kt` — Spec 1 §10 Block-4 acceptance KG-AFF-1. F-15 adds this file.
  - `ResolverPreDispatchAllocateTest.kt` — Spec 1 §10 Block-4 acceptance pre-dispatch allocation in resolvers. Genuinely missing — no test asserts IME's `startRecording` calls `audioFileFactory.allocate()` and translates `IOException` → `dictate_storage_full` toast.
- **Suggested fix:**
  - Add explicit Coverage-confirmation table to block-report mapping each plan-named test file to its actual coverage location (existing or new).
  - Add `ResolverPreDispatchAllocateTest.kt` — Robolectric or pure-JVM with `FakeAudioFileFactory` (already exists in B3 test util surface) asserting the IME's Pre-Dispatch path: success → `audioFileFactory.allocate()` returns File, toast not shown; IOException → `dictate_storage_full` toast shown, no dispatch.

### F-24 (was AUDIT-CONVENTION-B3-4)

- **Classification:** 🟢 valid + auto-fixable (option: wrap-with-Log.w — earns the TAG its keep)
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/PipelineSessionRepoAdapter.kt:3,144-146`
- **Description:** `import android.util.Log` (line 3) + `private companion object { private const val TAG = "PipelineSessionRepoAdapter" }` (lines 144-146) — neither consumed. Sibling state-side classes (`PipelineRecovery`, `PipelineOrphanCleaner`) use the same shape with active `Log.w(TAG, ...)` sites.
- **Suggested fix:** Wrap the two `withContext(Dispatchers.IO) { ... }` blocks in `markInserted` + `markFailed` with `try { ... } catch (t: Throwable) { Log.w(TAG, "operation failed for $sessionId", t) }` matching the `PipelineRecovery.safeUpdate*` pattern. Earns the TAG its keep + makes the file's "fail-soft" intent visible in logs. (Note: this introduces a `Throwable` catch — keep it because adapters need to swallow + log + continue; the F-21 narrowing only applies to PipelineCallbackBridge where Error propagation matters.)

### F-25 (was AUDIT-CONVENTION-B3-5)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **Files:** `PipelineOrphanCleaner.kt:82`, `PipelineSessionRepoAdapter.kt:93,107,122`
- **Description:** Same-operation-three-ways drift. `PipelineRecovery` takes `ioContext: CoroutineContext = Dispatchers.IO` injectable parameter; `PipelineOrphanCleaner` + `PipelineSessionRepoAdapter` hardcode `withContext(Dispatchers.IO)`. Tests for the two laggards use `runBlocking` instead of `runTest`.
- **Suggested fix:** Propagate `ioContext: CoroutineContext = Dispatchers.IO` parameter to both `PipelineOrphanCleaner` and `PipelineSessionRepoAdapter` constructors. Default value keeps current tests green. Copy the KDoc paragraph from `PipelineRecovery` documenting the convention. Future `runTest`-based tests for either class can then inject `EmptyCoroutineContext`.

### F-26 (was AUDIT-CONVENTION-B3-6)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **Files:** `PipelineOrphanCleaner.kt` + `PipelineRecovery.kt:246-251` (`deleteAudioOpportunistic`)
- **Description:** `try { file.delete() } catch (t: Throwable) { Log.w(TAG, "...", t) }` shape used in 3-4 sites where the Kotlin idiom `runCatching { fileOp }.onFailure { Log.w(...) }` would be one-liner. `CacheDirAudioFileFactory.cleanupOrphans` + `LegacyAudioFileMigration.run` already use the `runCatching` idiom. Note: F-21's `Exception` vs `Throwable` distinction does NOT apply here — these are best-effort file ops where Error-class catches don't matter (file IO doesn't throw OOM in practice).
- **Suggested fix:** Adopt `runCatching { fileOp }.onFailure { Log.w(TAG, "fileOp failed", it) }` for the 3-4 single-statement best-effort file-delete sites. Keep the multi-line `try/catch` in `PipelineRecovery.safeUpdateStatus / safeUpdateError / safeClearAudioPath` — those need to swallow + log AND continue with multi-line bodies.

### F-27 (was AUDIT-CONVENTION-B3-7)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **Files:** `SessionStatus.kt`, `MigrationTo4.kt`, `RecordingTimerAdapter.kt`, `AmplitudeStreamAdapter.kt`, `BorderGlowAdapter.kt`
- **Description:** 5/12 new B3 files miss the `@see docs/plans/...` KDoc anchor. The other 7 carry it (set the pattern).
- **Suggested fix:** Add a single `@see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §<section>` line to each file's class KDoc:
  - `SessionStatus.kt`: `§6.1 §6.1.3`
  - `MigrationTo4.kt`: `§6.1 §6.5`
  - `RecordingTimerAdapter.kt`, `AmplitudeStreamAdapter.kt`, `BorderGlowAdapter.kt`: `§4.7 §15.x`
- KDoc renders the path as plain text; grep-discoverable across worktree.

### F-28 (was AUDIT-CONVENTION-B3-8)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/PipelineServiceStubSubsystems.kt:185-195,217-239`
- **Description:** `stubSessionRepo` + `audioFileFactory` marked deprecated in prose only — no `@Deprecated` annotation. IDE doesn't surface a "deprecated call" on test call sites.
- **Suggested fix:** Add `@Deprecated("Replaced by PipelineSessionRepoAdapter in C10 — kept for test-only compile-compat", level = DeprecationLevel.WARNING)` on `stubSessionRepo`. Same shape with `"Replaced by CacheDirAudioFileFactory in C11"` on `audioFileFactory`. Tests get IDE strikethrough + warning.

### F-29 (was AUDIT-CONVENTION-B3-9)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **Files:** `app/src/main/java/net/devemperor/dictate/migration/LegacyAudioFileMigration.kt` + `app/src/test/java/net/devemperor/dictate/migration/LegacyAudioFileMigrationTest.kt`
- **Description:** New top-level `migration/` package introduced for a single file. Sibling `InputLanguagesLegacyMigration.kt` lives in `preferences/` (next to its surface); Room schema migrations live in `database/migration/`. Three different homes for the "migration" concept.
- **Suggested fix:** Move both files to `app/src/main/java/net/devemperor/dictate/core/LegacyAudioFileMigration.kt` + `app/src/test/java/net/devemperor/dictate/core/LegacyAudioFileMigrationTest.kt` — co-located with `CacheDirAudioFileFactory.kt`, matching the `InputLanguagesLegacyMigration.kt` precedent. Update package declaration + the 1 import site in `DictatePipelineService.kt`. The empty `migration/` directories disappear. Document the convention ("legacy migrations co-locate with the surface they migrate; Room schema migrations live in `database/migration/`") in `docs/DATABASE-PATTERNS.md` §"Migration Conventions".

### F-30 (was AUDIT-CONVENTION-B3-10)

- **Classification:** 🟢 valid + auto-fixable (option: nest the projection)
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/database/dao/SessionDao.kt:249-252`
- **Description:** `OrphanedAudioRow` projection at top-level in `SessionDao.kt`, not nested inside the `SessionDao` interface. The other DAO projections in the project (e.g. in `transcriptionDao.kt`, `processingStepDao.kt`) are nested. Same-operation-two-ways drift.
- **Suggested fix:** Move `data class OrphanedAudioRow(...)` inside the `SessionDao` interface block — aligns with project precedent.

### F-31 (was AUDIT-TEST-B3-2)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/test/java/net/devemperor/dictate/core/PipelineCallbackBridgeTest.kt`
- **Description:** `PipelineCallbackBridge.delegate` is held in `AtomicReference` (line 49) — single-write visibility safe — but no test exercises the concurrent-reader scenario (pipeline thread reads while main-thread re-binds).
- **Suggested fix:** Add a single test case using `Thread` + `CountDownLatch`: thread A repeatedly reads `bridge.dispatch(action)`; thread B flips `delegate` between two captures via `setDelegate(...)`; assert all dispatches go to one or the other delegate (no NPE, no lost dispatch, no dispatch-to-stale-delegate after `setDelegate(null)`).

### F-32 (was AUDIT-TEST-B3-3)

- **Classification:** 🟢 valid + auto-fixable (or deferred)
- **Severity:** Nice-to-have
- **File:** `app/src/test/java/net/devemperor/dictate/core/SessionTrackerTest.kt:104` + `app/src/test/java/net/devemperor/dictate/testutil/FakeSessionDao.kt`
- **Description:** Two `FakeSessionDao` implementations co-exist — private call-counting fake in `SessionTrackerTest` + canonical behavioral fake in `testutil/`. Doubles maintenance cost when new DAO methods land.
- **Suggested fix:** Migrate `SessionTrackerTest` to the shared `testutil/FakeSessionDao`. Add `findLatestByOrigin` to the behavioral fake (currently missing). Wrap with a small `CallCountingFakeSessionDao` test helper if the call-count semantic is still needed (composition over duplication). If the consolidation is non-trivial, defer to a Wave-2 follow-up Nice-to-have issue — but flag it in block-report.

### F-33 (was AUDIT-TEST-B3-5)

- **Classification:** 🟢 valid + auto-fixable (documentation-only)
- **Severity:** Nice-to-have
- **File:** `B3-migration-persistence-audiofactory.md:140,147` (and block-report aggregate counts)
- **Description:** Block-report claims `PipelineCallbackBridgeTest` = 19 tests (actual 14) and `DictatePipelineServiceCompositionTest` = 14 tests (actual 15). Aggregate +5/-1 = +4 drift; "141 net-new tests after B3" headline is correct (676 + 1 = 677 verified).
- **Suggested fix:** Mechanical correction in `B3-migration-persistence-audiofactory.md` sub-section count cells. Plus a one-line note in the audit-trail acknowledging the drift was discovered post-write by AUDIT-TEST.

---

## Eliminated findings

| Source ID | Source audit | Reason for elimination |
|-----------|--------------|------------------------|
| (none) | — | No findings were eliminated. 4 cross-audit overlaps were MERGED into single findings (F-7, F-15, F-19, F-22), not eliminated. All 33 raw findings represent real concerns; none are false-positives. |

---

## Aggregate cross-cut summary for repair planning

**Files touched (multi-finding clusters):**

| File | Findings | Severities | Bundle as |
|------|----------|------------|-----------|
| `LegacyAudioFileMigration.kt` (+test) | F-7 + F-29 | Imp + NTH | Bundle "legacy-audio-file-migration cleanup" |
| `PipelineServiceStubSubsystems.kt` | F-18 + F-28 | NTH + NTH | Bundle "stub-subsystems doc update" |
| `RecordingModule.kt` | F-10 + F-11 | Imp + Imp | Bundle "StartMediaRecorder effect" (paired by design) |
| `DictatePipelineService.kt` | F-3 + F-12 + F-6 | Imp + Imp + Imp | Bundle "service-lifecycle hardening" |
| `PipelineRecovery.kt` | F-13 + F-17 + F-19 | Imp + NTH + NTH | Bundle "recovery cleanup" |
| `SessionDao.kt` + `MigrationTo4.kt` + entity | F-1 + F-2 + F-8 + F-27 (partial) | Crit×2 + Imp + NTH | Research-then-fix wave + carve-out KDoc |
| `PreferencesFragment.java` + 4 locale strings | F-4 + F-9 | Imp + Imp | Bundle "locale + race-protect" |

**Repair-wave breakdown:**

- **Wave 1 — Research-then-fix (2 Criticals, F-1 + F-2):** spawn research-agent `B3-VAL-RES-1` with topic `b3-cleanup-cascade-and-backfill-policy`. Resume as `B3-VAL-REPAIR-1` to apply migration + query patch. Then `B3-VAL-REPAIR-1-VERIFY` self-check.

- **Wave 2 — Auto-fix sweep (27 🟢s, plus F-22 documentation-only):** resume consolidator as `B3-VAL-REPAIR`. Bundle by file-cluster per the table above. Aim for one commit per logical bundle to keep the diff reviewable. Then `B3-VAL-REPAIR-VERIFY` self-check.

**Out-of-scope confirmations (not findings):**
- All 8 items under audit-plan-and-api §"Deviation-Resolutions (plan-gaps filled or stubs implemented as deviations)" — verified plan-conform, no action.
- All 5 items under audit-plan-and-api §"API consumer match (cross-chunk)" — verified pass.
- LegacyAudioFileMigration takes `failedStatus: String` not `SessionStatus` — verified Double-Enum boundary convention, no action.
- C9 lint block omits `abortOnError true` — verified intentional deferral per Spec 1 §11.7.0 Cost-Check, no action.

---

## Stdout sign-off

Block 3 audit consolidation complete.
Validated: 🟢 27 (0 C / 12 Imp / 15 NTH), 🟡 2 (2 Crit). Eliminated: 0 (4 cross-audit overlaps merged).
Cross-cut patterns: 6 — see top of report.
Output: `./reports/validated-findings-B3.md`
Phase complete — orchestrator decides routing.

---

## Wave 1 Repair Status (B3-VAL-REPAIR, 2026-05-15)

| Finding | Status | Notes |
|---------|--------|-------|
| F-1 Critical FK CASCADE | fixed | MigrationTo4 + SessionEntity FK changed to SET NULL; schema 4.json regenerated; new test `migrate3To4_setsForeignKeyToSetNull` |
| F-2 Critical backfill | fixed | Backfill changed to NULL; new `Pref.PendingInsertionFreshnessMs` + `findPendingInsertion(freshnessFloor)` signature change; existing test renamed + assertions flipped |
| F-3 onDestroy timeout | fixed | Pre-Cancel-Dispatch + `runBlocking + withTimeout(2_000L)` wrapper added |
| F-4 Cache-clear race-protect | fixed | New string + service-bind + isRecordingActive() guard in PreferencesFragment |
| F-5 Imported-audio leak | fixed | Settings + IME both use `cacheDir/audio/<name>` (factory scope) |
| F-6 Stub-subsystems doc | fixed | Block-report Deviation entry + Issue Index forward to B5/B6 |
| F-7 Raw pref key | fixed | New `Pref.LegacyAudioPurgedV4` + `flagPrefKey()` helper |
| F-8 Status default carve-out | fixed | KDoc carve-out added; DATABASE-PATTERNS.md gains Data-preservation rule |
| F-9 DE/ES/PT translations | fixed | dictate_status_recording + dictate_status_transcribing translated to all 3 locales |
| F-10 + F-11 StartMediaRecorder effect + reduceFailure | fixed | Effect + reducer arm + runEffect + failure-arm added; existing reducer test updated |
| F-12 onCreate order | fixed | LegacyAudioFileMigration runs before orchestrator construction |
| F-13 + F-17 EmptySessionDao | fixed | Removed from production; new test-only `testutil/EmptySessionDao.kt` + `testPipelineRecovery()` helper |
| F-14 ResendState data-shape | fixed (data-shape) + flagged (consumer wiring) | Set + Boolean alias coexist; IME consumer wiring forwarded as `I-F14-followup` |
| F-15 PipelineOrchestratorPersistTest | delegated-to-orchestrator | Forwarded as `I-F15-test` to B4 AUDIT-TEST |
| F-16 CleanupOrderTest | delegated-to-orchestrator | Forwarded as `I-F16-test` to B4 AUDIT-TEST |
| F-18 Stub-subsystems KDoc | fixed | File KDoc + MESSAGE constant rewritten |
| F-19 findPendingInsertion dedup | fixed | Cached once in PipelineRecovery; reused for Phase-4 SF-4 dispatch |
| F-20 CleanupResult split | fixed | New `filesActuallyDeleted` + existing `clearedAudioPathRows`; `deletedAudioFiles` is now deprecated alias |
| F-21 Exception vs Throwable | fixed | PipelineCallbackBridge now catches Exception |
| F-22 LanguageController follow-up | fixed | Issue Index `I-D13-followup` added |
| F-23 Coverage map + new test | delegated-to-orchestrator | Forwarded as `I-F23-test` to B4 AUDIT-TEST |
| F-24 Log.w in repo adapter | fixed | markInserted + markFailed wrap IO block in try/catch with WARN log |
| F-25 ioContext convention | fixed | PipelineOrphanCleaner + PipelineSessionRepoAdapter gain `ioContext` parameter |
| F-26 runCatching style | fixed | Single-line file ops switched in PipelineRecovery + PipelineOrphanCleaner |
| F-27 @see anchors | fixed | 5/12 files updated (SessionStatus, MigrationTo4, RecordingTimerAdapter, AmplitudeStreamAdapter, BorderGlowAdapter) |
| F-28 @Deprecated | fixed | Both `sessionRepo` + `audioFileFactory` annotated |
| F-29 Package move | delegated-to-orchestrator | F-7 typed-pref applied here; package move deferred to follow-up clean-up wave (touches multiple imports + test source-set) |
| F-30 Nested projection | fixed (deviation documented) | OrphanedAudioRow stays top-level with KDoc rationale |
| F-31 + F-32 + F-33 | partially-fixed | F-33 acknowledged in block-report; F-31 + F-32 deferred to follow-up clean-up wave |

**Build/test outcome:** `./gradlew test` green; `./gradlew assembleDebug` green; Room schema 4.json regenerated with `ON DELETE SET NULL`. Full diff inventory + self-check in `./reports/B3-migration-persistence-audiofactory.md#repair-wave-1-b3-val-repair`.

**ADR-0003 amendment:** new Decision-History entry "2026-05-15 — Cleanup-policy + FK-cascade semantics (B3-VAL-REPAIR)" appended to `docs/decisions/0003-service-foreground-pipeline-architecture.md`.

**DATABASE-PATTERNS.md amendment:** new "Data-preservation rule" sub-section under "Migration Conventions" capturing both Rule 1 (NULL backfill) and Rule 2 (SET NULL FK) plus the mandatory test contract.
