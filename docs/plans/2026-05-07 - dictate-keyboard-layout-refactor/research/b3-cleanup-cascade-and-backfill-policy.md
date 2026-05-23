---
status: Spec — programmer-ready
---

# B3 Research — Cleanup-Cascade + Backfill-Policy

**Date:** 2026-05-15
**Triggered by:** F-1 (LOGIC-B3-1, Critical) + F-2 (LOGIC-B3-2, Critical) in `reports/validated-findings-B3.md`
**Block:** B3 (migration-persistence-audiofactory)
**Agent-ID:** B3-VAL-RES-1
**Topic:** `b3-cleanup-cascade-and-backfill-policy` (combined research — both findings touch Spec 1 §6.2 R.17 + §6.5 cleanup-policy)

## §0 Header

This document resolves two Critical data-loss findings on the M3→M4
cleanup policy. Both are silent-data-loss bugs in the current B3
implementation (production code, but **never shipped** — the worktree
is pre-merge). The decisions captured here are intended for adoption
in a single repair-wave (Wave 1 per `validated-findings-B3.md`).

Single source of truth for the cleanup-policy decision is Spec 1 §6.2
R.17 + §6.3 + §6.3.1 + §11.7.0. This file extends those sections with
the data-preservation constraints derived from the two findings; the
spec sections themselves are amended in the repair-fix-wave.

## §1 Findings

### F-1 — Parent-cascade wipes children (LOGIC-B3-1, Critical)

**File pointers:**
- `app/src/main/java/net/devemperor/dictate/database/entity/SessionEntity.kt:10-16` — FK declared `ForeignKey.CASCADE`
- `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo4.kt:97` — SQL declares `FOREIGN KEY (parent_session_id) REFERENCES sessions (id) ON DELETE CASCADE`
- `app/src/main/java/net/devemperor/dictate/database/dao/SessionDao.kt:143` — `DELETE FROM sessions WHERE inserted_at IS NOT NULL AND inserted_at < :cutoff`
- `app/src/main/java/net/devemperor/dictate/state/PipelineOrphanCleaner.kt:87` — invokes `deleteInsertedOlderThan`

**Mechanic of the bug:** The self-referential FK on `parent_session_id`
declares `ON DELETE CASCADE` (carried over from MIGRATION_2_3 + restated
in MIGRATION_3_4 verbatim). The cleanup query `deleteInsertedOlderThan`
issues a **row-level DELETE** (unlike `DROP TABLE` during migrations,
which does not trigger CASCADE per SQLite §4.2). Row-level DELETE
**does** trigger CASCADE — so any session deleted by the cleanup
policy takes its entire child sub-tree with it.

**Concrete data-loss scenario:** Per `SessionType` + `SessionOrigin`
matrix (see `SessionOrigin.kt:11-15`), the only legitimate parent-child
relationship today is **POST_PROCESSING**: from
`HistoryDetailActivity.createPostProcessingSession()` (line 575), the
user picks a result text in the history view and applies an
additional prompt (e.g. "translate", "summarise"). The new session is
type `POST_PROCESSING`, origin `POST_PROCESSING`, with
`parentId = sessionId` of the original. On completion the child gets
its own `final_output_text` and (once inserted) its own `inserted_at`.

Walk-through of silent data loss:
1. **Day 1:** User records text A. Session `s_A` reaches COMPLETED,
   gets inserted → `inserted_at = day_1`.
2. **Day 9:** User opens history detail, applies "Translate" to A.
   New child session `s_B` (parent = `s_A`). Translation completes,
   user pastes it → `s_B.inserted_at = day_9`.
3. **Day 9 idle-stop:** `PipelineOrphanCleaner.cleanup()` runs.
   Cutoff = `day_9 - 7d - 1h`. `s_A.inserted_at = day_1` is older →
   `DELETE FROM sessions WHERE id = s_A` → CASCADE fires →
   `s_B` is **silently deleted** along with `s_A`.

The user just translated something five minutes ago and the row is
gone the moment a different session times out. The cascade is also
recursive: a POST_PROCESSING child can itself be a parent (e.g.
"summarise the translation"), so the cascade can wipe an entire
chain.

### F-2 — Migration backfill makes pre-existing history immediately deletable (LOGIC-B3-2, Critical)

**File pointer:** `MigrationTo4.kt:118-131` — backfill snippet:
```sql
CASE
    WHEN status = 'COMPLETED' AND final_output_text IS NOT NULL
        THEN created_at
    ELSE NULL
END
```

**Mechanic of the bug:** M3→M4 introduces `inserted_at` as the
"COMPLETED but not yet surfaced" marker (Spec 1 §6.1 R.17 + §6.5).
For pre-existing COMPLETED rows, the migration backfills
`inserted_at = created_at`. The cleanup query
`deleteInsertedOlderThan(now - 7d - 1h)` then sees every historical
row whose `created_at` is older than 7 days as eligible for deletion.

**Concrete data-loss scenario:** User has used the app since
2025-09 — 8 months of history, ~500 sessions. Most have
`created_at` from 2025-10 to 2026-04 → all of these are weeks/months
old. Pre-M4 the history list had no cleanup pass and these rows
sat in the DB indefinitely (only manual `HistoryDetail → Delete`
removed them). Post-M4:
1. User installs the v4 app. Migration runs, sets
   `inserted_at = created_at` for every COMPLETED row.
2. First idle-stop after the upgrade (could be the same minute):
   `deleteInsertedOlderThan(now - 7d - 1h)` runs.
3. Every row with `created_at < now - 7d - 1h` is silently deleted.
   For the example user, that's ~480 of 500 sessions wiped.

The user upgraded and lost 7 months of history with zero warning.

## §2 Constraint inventory

| Constraint | Source | Implication |
|---|---|---|
| **No silent data loss is acceptable** | Project value — Dictate is a user-facing IME with multi-month user history | Both fixes must preserve all user data that existed pre-upgrade. Cleanup that runs in the background must be conservative when in doubt. |
| **Schema is canonically described in Spec 1 §6** | Spec 1 §6.1 + §6.2 R.17 + §6.3 + §6.3.1 | Spec text gets updated to reflect the new policy. Spec is the SoT — code follows. |
| **Cleanup policy must be deterministic** | Spec 1 §6.2 R.17 (idempotence + reorderable) + DATABASE-PATTERNS.md (audit-trail value) | The fix must not introduce timing-dependent or state-dependent cleanup behaviour. NULL-semantics (option a for F-2) is cleanly deterministic; "amnesty flag for one cycle" (option b) is not. |
| **Double-Enum convention** | `docs/DATABASE-PATTERNS.md` §"Double-Enum pattern" + CLAUDE.md | Schema changes go through migrations. A new column (option F-2 c) triggers Double-Enum-style review + a follow-up CHECK migration. NULL semantics on an existing column (option F-2 a) is the cheaper path. |
| **M3→M4 has not shipped to users yet** | Worktree is pre-merge (verified: git status shows the migration file as untracked in B3-VAL repair); no release build with M4 has been published. | The cleanest fix is to **amend MIGRATION_3_4 in place** rather than introduce a patch MIGRATION_4_5. Amending is allowed only because no user device has run M4 yet. |
| **FK semantics are SQLite-text-stored** | Spec 1 §11.7.0 Risk-3 + MIGRATION_2_3 lessons-learned comments in `MigrationTo3.kt:38-42` | Changing the FK from `ON DELETE CASCADE` to `ON DELETE SET NULL` requires a table-recreate migration. M4 already does a table-recreate (for the CHECK-constraint extension), so the FK-change is free to bundle into M4. |
| **K-1 + K-4 test compliance** | Spec 1 §10 Block-3 acceptance + §11.4.2 | Migration test must cover the new cleanup-policy invariants. |
| **Forward-compat with B5/B6** | Spec 1 §10 Block-5/6 + plan section structure | The fix must not constrain how future blocks introduce additional cleanup paths or new parent-child types. |

## §3 F-1 options evaluated

Three options were listed in the finding. Below is the engineering
evaluation against the constraints in §2.

### Option (a) — Change FK to `ON DELETE SET NULL`

**What:** In `SessionEntity` annotation flip
`onDelete = ForeignKey.CASCADE` → `ForeignKey.SET_NULL`. In
`MigrationTo4.kt` SQL, replace `ON DELETE CASCADE` →
`ON DELETE SET NULL` on the FK line. Children whose parent is
deleted are kept; their `parent_session_id` becomes NULL — they
appear as root-level history items going forward.

**Pros:**
- **Zero data loss** for child rows. The user keeps the translation /
  summarisation / chain result.
- **Schema-clean.** No additional WHERE-clause logic in the cleanup
  query. The DB enforces the semantic.
- **Idempotent.** The cleanup query stays a single-statement DELETE;
  no NOT EXISTS sub-query that can drift.
- **Audit-trail-preserving** for the child. `created_at`, audio,
  transcriptions, processing-steps of the child all survive.
- **Future-proof.** Additional parent-child types (B5/B6 might add
  more) inherit the same correct behaviour without code change.
- **Bundles cleanly** with the existing M4 table-recreate (no extra
  migration cost; M4 is amended in place per §2).

**Cons:**
- Children become "orphans" in the parent-chain sense — the link
  back to the parent is lost. UX impact: history view shows the
  child as a root item, no "this was a post-processing of X" badge.
  Spec 1 §6.3 + history UI today does not surface that link in any
  user-visible way (history UI shows sessions as flat list, not as
  tree), so the UX regression is **nil** in practice.
- Requires SessionEntity annotation change + verify Room generates
  the right SQL (`ForeignKey.SET_NULL`).

### Option (b) — Change FK to `ON DELETE RESTRICT` + recursive children-check

**What:** FK becomes `ON DELETE RESTRICT` (SQLite default behaviour
when there are children). Cleanup query is then refused at the FK
level whenever children exist. To make the cleanup useful, we add a
recursive check before each DELETE: only delete parents whose entire
descendant chain is also eligible.

**Pros:**
- Preserves family-structure: if a chain is deleted, the whole chain
  goes; otherwise nothing in the chain goes.

**Cons:**
- **Complex.** Recursive eligibility means the cleanup query needs
  either a CTE (SQLite supports recursive CTEs but Room support is
  awkward) or an in-memory walk. Either way: more code, more test
  surface, more failure modes.
- **All-or-nothing-by-chain.** A 30-day-old parent with a 1-day-old
  child stays for another 6 days+. The parent's row is "stuck"
  longer than the policy intends. Over time this can lead to large
  chains that never get cleaned because someone always reworks them.
- **Failure-loud.** RESTRICT means a DELETE that hits an unexpected
  child throws — the cleanup is not best-effort, and any bug in the
  eligibility check surfaces as a runtime exception. The current
  `PipelineOrphanCleaner.cleanup()` `try/catch` swallows the
  exception but the cleanup-run is wasted.
- More test surface for the recursive eligibility check.

### Option (c) — Filter cleanup SQL to skip sessions with children

**What:** Keep the FK as `ON DELETE CASCADE`. Extend
`deleteInsertedOlderThan` SQL with `AND NOT EXISTS (SELECT 1 FROM
sessions c WHERE c.parent_session_id = sessions.id)`.

**Pros:**
- No schema change. Pure query fix.

**Cons:**
- **Same all-or-nothing-by-chain problem as (b)** — a parent with a
  child that's not eligible (e.g. fresh) sticks around indefinitely.
  The cleanup eligibility-check is shallow (one level), so a deep
  chain that has one young descendant blocks the whole branch.
- **Recursive coverage gap.** Even adding a recursive children-check
  has the same issue: a chain can grow indefinitely if any leaf is
  young. Eventually the DB has parent rows older than 10 years.
- **Sub-query cost.** `NOT EXISTS` on `index_sessions_parent_session_id`
  is cheap, but it's an extra index lookup per candidate. With <1k
  rows, negligible. Still: query complexity grows.
- **Same FK-cascade hazard remains** if any other code path ever
  issues a row-level DELETE on `sessions` (e.g. `deleteById` from
  user-driven "Delete" in HistoryDetail). The user-driven path
  arguably wants cascade — the user just said "delete this session"
  — but in practice that, too, often surprises (user deletes a
  parent to clean up, child translations vanish silently).
- **Doesn't actually prevent the bug for future row-level DELETEs.**

### Recommendation: **Option (a) — `ON DELETE SET NULL`**

Rationale:
- **Strongest data-preservation guarantee.** Children are unconditionally
  preserved regardless of which DELETE pathway triggered.
- **Lowest implementation cost** because M4 already does a
  table-recreate — the FK clause is a one-character change.
- **No new code surface** in `SessionDao` / cleanup logic. The
  policy is captured at the schema level where it belongs.
- **Forward-compat.** Any future row-level DELETE (manual, cleanup,
  user-driven) inherits the SET-NULL semantic.
- **No real UX downside.** The history UI doesn't display parent-child
  relationships as a tree today; orphaned children look identical to
  root-level history items.

This option also pairs naturally with the F-2 NULL-semantics
recommendation (see §4): both fixes converge on "NULL means
unknown / unowned → never cascade through, never auto-delete".

## §4 F-2 options evaluated

Four options were listed. Below is the engineering evaluation.

### Option (a) — Backfill `inserted_at = NULL` for all pre-existing rows

**What:** Change the migration backfill from
`inserted_at = created_at` to `inserted_at = NULL` for every
pre-existing row. Update the cleanup query to read
`WHERE inserted_at IS NOT NULL AND inserted_at < :cutoff` (it already
reads this — `inserted_at IS NOT NULL` is the existing first clause
in `SessionDao.deleteInsertedOlderThan` line 143; the migration just
needs to make pre-existing rows non-eligible). The query in
`findPendingInsertion` also already handles NULL correctly (it asks
for `inserted_at IS NULL` to surface pending insertions — pre-existing
COMPLETED rows would now surface as pending-insertion candidates, see
implementation hint below).

**Pros:**
- **Zero data loss.** Pre-M4 rows are immune to the cleanup policy,
  matching the pre-M4 expectation ("history is forever").
- **Kotlin-idiomatic.** NULL-as-unknown / NULL-as-never-cleaned is
  the cleanest representation. The column type is already
  `Long?` in Kotlin and `INTEGER NULL` in SQL.
- **No new column, no new pref, no new constant.** Schema stays
  exactly as designed; only the migration backfill changes.
- **Already partially correct.** The cleanup query already filters
  `inserted_at IS NOT NULL` (line 143), so the fix is purely on the
  migration side — no query change needed.
- **Idempotent.** A pre-existing row whose user later re-opens
  history and re-pastes it would set `inserted_at = now` via the
  same `markInserted` path, and from then on the 7-day clock starts.
  This is the right semantics: the user *just* surfaced the result.

**Cons:**
- **Pre-existing COMPLETED rows now surface as "pending-insertion"
  in `findPendingInsertion()`.** Spec 1 §6.3 + `PipelineRecovery`
  enumerates these and dispatches `NotifyManualPasteNeeded` actions.
  This would create a flood of stale notifications on first
  post-upgrade recovery. **Mitigation:** Either (i) gate
  `findPendingInsertion` on a freshness window like
  `inserted_at IS NULL AND created_at > :postUpgradeCutoff` (where
  the cutoff is the migration-completion timestamp — see option (d)),
  OR (ii) simpler: leave `findPendingInsertion` untouched and
  observe that this query is intended for OOM-death-recovery — it
  fires only when a process death interrupted the pipeline. The
  user's old rows have `final_output_text IS NOT NULL` from years
  ago; surfacing them as "pending paste" notifications would be
  noise. **Recommended:** apply mitigation (i) — a freshness window
  on `findPendingInsertion`. See §5 for the concrete migration-timestamp
  pref.

### Option (b) — Sentinel value (e.g. `inserted_at = -1`)

**What:** Backfill with a sentinel like `-1`. Cleanup query reads
`AND inserted_at > 0 AND inserted_at < :cutoff`.

**Pros:**
- Distinguishes "legacy unknown" from "freshly created not yet
  inserted" without needing a new column.

**Cons:**
- **Less Kotlin-idiomatic.** Kotlin has nullable types; sentinels
  encode the same information in-band with the value, which is the
  classic anti-pattern that nullable types replace.
- **Foot-gun for future migrations.** A future developer reading
  `inserted_at` may not realise `-1` is special and write
  comparison logic that treats it as a real timestamp.
- **Requires same `findPendingInsertion` mitigation as (a).** Same
  noise problem; same fix.

### Option (c) — Add `is_legacy: Boolean` column

**What:** New column distinguishes pre-migration vs post-migration
rows. Cleanup query: `WHERE is_legacy = 0 AND inserted_at IS NOT NULL
AND inserted_at < :cutoff`.

**Pros:**
- Explicit. A future developer can grep for `is_legacy` and trace
  intent.

**Cons:**
- **Larger schema change.** New column means new Double-Enum-class
  question (well, Boolean isn't Double-Enum but still: new CHECK
  constraint maybe, new entity field, new DAO logic).
- **Stale forever.** Pre-existing rows carry `is_legacy = TRUE`
  forever; the column is one-shot. Carries no information density
  after migration — same as a sentinel, but with an entire column.
- **Overkill.** NULL-as-unknown already encodes "legacy" cleanly.

### Option (d) — Cleanup requires `inserted_at >= migration_timestamp`

**What:** New pref `Pref.M4MigrationTimestampMs` written during
the migration. Cleanup query reads:
`WHERE inserted_at IS NOT NULL AND inserted_at >= :migrationTs
AND inserted_at < :cutoff`. Backfill stays NULL per option (a).

**Pros:**
- Belt-and-suspenders. Even if the backfill accidentally writes a
  timestamp to a legacy row, the migration-timestamp guard catches
  it.

**Cons:**
- **Pref-state-coupled.** Cleanup depends on a pref value. If the
  pref is wiped (e.g. user clears app data — though that wipes the
  DB too, so it's moot), behaviour is unclear.
- **Redundant** if option (a) is correctly implemented. The migration
  backfill is deterministic — there's no need for a secondary
  guard.

### Recommendation: **Option (a) — backfill `inserted_at = NULL`**

Rationale:
- **Strongest data-preservation guarantee.** No pre-existing row
  is auto-deletable.
- **Smallest possible change.** Migration SQL replaces
  `CASE ... THEN created_at ELSE NULL END` with literally `NULL`.
  Cleanup query is already correct (it filters
  `inserted_at IS NOT NULL`).
- **Kotlin-idiomatic.** NULL-as-unknown is the canonical way to
  express "the marker doesn't apply".
- **Pairs with F-1 SET NULL.** Both fixes converge on NULL semantics
  for "row is alive but the marker for cleanup doesn't apply".
- **Requires one mitigation:** `findPendingInsertion` would
  otherwise surface every legacy COMPLETED row as pending-insertion.
  Fix: gate that query on `created_at > :recentThreshold` (e.g. last
  24h, configurable via `Pref.PendingInsertionFreshnessMs`). See §5
  for the concrete implementation.

The option-(d) "migration-timestamp pref" is **not needed** if the
backfill is deterministically NULL. We carry the simpler version.

## §5 Combined implementation hints

### §5.1 Migration choice: amend M4 in place

The worktree has not shipped — no user device has run M4. We
**amend `MigrationTo4.kt` in place** (no new MIGRATION_4_5). This
keeps the migration chain short and the schema history clean. The
amend covers both F-1 (FK clause) and F-2 (backfill clause) in one
file edit.

### §5.2 Files to edit

| File | Change | Severity |
|---|---|---|
| `app/src/main/java/net/devemperor/dictate/database/migration/MigrationTo4.kt` | (1) Line 97: `ON DELETE CASCADE` → `ON DELETE SET NULL`. (2) Lines 125-128: replace `CASE WHEN ... THEN created_at ELSE NULL END` with literal `NULL`. (3) Update class KDoc to describe the new policy. | Critical |
| `app/src/main/java/net/devemperor/dictate/database/entity/SessionEntity.kt` | Line 15: `onDelete = ForeignKey.CASCADE` → `ForeignKey.SET_NULL`. Add a KDoc paragraph on the FK semantic + the SoT in Spec 1 §6.5. | Critical |
| `app/src/main/java/net/devemperor/dictate/database/dao/SessionDao.kt` | (1) `findPendingInsertion()` (line 122): add `AND created_at >= :freshnessFloor` parameter so legacy COMPLETED rows don't surface as pending notifications. (2) `deleteInsertedOlderThan` unchanged (already correctly filters NULL). | Critical |
| `app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt` | New pref `Pref.PendingInsertionFreshnessMs : Pref<Long>` (default 24h or matching `SessionCleanupGracePeriodMs`). KDoc explaining the M4-backfill rationale. | Important |
| `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt` | Callers of `findPendingInsertion()` (lines 135, 150) pass the freshness cutoff. | Important |
| `app/src/main/java/net/devemperor/dictate/state/PipelineSessionRepoAdapter.kt` | Adapter forwarder passes the cutoff through. | Important |
| `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md` | Spec amendments in §6.1 (migration code block), §6.2 R.17 (cleanup-cutoff bulletpoint), §6.5/§6.3 (cleanup-policy text), §11.4.2 (migration tests). | Critical (Spec SoT) |
| `docs/DATABASE-PATTERNS.md` | New §"Cleanup-policy + FK-cascade semantics" sub-section under Migration Conventions documenting the `SET NULL` choice + the NULL-as-unknown invariant for `inserted_at`. | Important |
| `docs/decisions/0003-service-foreground-pipeline-architecture.md` | Append a Decision-History entry for the FK-semantics + cleanup-policy clarification. (ADR-0003 is the service-foreground ADR which covers the persistence-side adapter — the cleanup-policy fits there.) | Important |

### §5.3 Concrete code diff sketches

**MigrationTo4.kt FK + backfill change:**

```diff
                 inserted_at INTEGER,
-                FOREIGN KEY (parent_session_id) REFERENCES sessions (id) ON DELETE CASCADE
+                FOREIGN KEY (parent_session_id) REFERENCES sessions (id) ON DELETE SET NULL
             )
```

```diff
                 final_output_text, input_text,
-                CASE
-                    WHEN status = 'COMPLETED' AND final_output_text IS NOT NULL
-                        THEN created_at
-                    ELSE NULL
-                END
+                NULL  -- M4 backfill: pre-existing rows are immune to
+                      -- deleteInsertedOlderThan. Spec 1 §6.5 + F-2.
             FROM sessions
```

**SessionEntity.kt FK annotation:**

```diff
-        onDelete = ForeignKey.CASCADE
+        onDelete = ForeignKey.SET_NULL
```

**SessionDao.findPendingInsertion freshness floor:**

```diff
-    @Query(
-        """
-        SELECT * FROM sessions
-        WHERE status = 'COMPLETED'
-          AND final_output_text IS NOT NULL
-          AND inserted_at IS NULL
-        ORDER BY created_at DESC
-        """
-    )
-    fun findPendingInsertion(): List<SessionEntity>
+    @Query(
+        """
+        SELECT * FROM sessions
+        WHERE status = 'COMPLETED'
+          AND final_output_text IS NOT NULL
+          AND inserted_at IS NULL
+          AND created_at >= :freshnessFloor
+        ORDER BY created_at DESC
+        """
+    )
+    fun findPendingInsertion(freshnessFloor: Long): List<SessionEntity>
```

**DictatePrefs.kt new pref:**

```kotlin
// Pending-insertion freshness floor (B3 §6.3 + F-2):
// After M4 backfilled `inserted_at = NULL` for all pre-existing
// COMPLETED rows (the only way to keep them safe from
// deleteInsertedOlderThan), `findPendingInsertion` would otherwise
// surface every legacy COMPLETED row as a pending-paste candidate
// and trigger NotifyManualPasteNeeded N times on first boot. The
// freshness floor caps which rows are considered "fresh enough" to
// surface — only sessions whose created_at is within the last
// `PendingInsertionFreshnessMs` are pending-insertion candidates.
// Default: 24h (anything older was either pasted long ago or is
// genuinely stale — the user has moved on).
object PendingInsertionFreshnessMs :
    Pref<Long>("net.devemperor.dictate.pending_insertion_freshness_ms", 86_400_000L)
```

### §5.4 Test impact

Existing test `migrate3To4_addsInsertedAtColumn_andBackfillsCompleted`
in `MigrationTo4Test` (§11.4.2 in spec) currently asserts the
old behaviour — it expects COMPLETED rows to get
`inserted_at = created_at`. This test must change.

**New + updated migration tests:**

1. **`migrate3To4_backfillsInsertedAt_asNull_forAllPreExistingRows`**
   (rename + content swap of the existing `_andBackfillsCompleted`
   test). Asserts that COMPLETED rows post-migration have
   `inserted_at IS NULL`.

2. **`migrate3To4_setsForeignKeyToSetNull`** — new. Insert a
   parent + child pre-migration, run M4, DELETE the parent
   row-level, assert child survives with `parent_session_id IS NULL`.
   Uses `helper.createDatabase(TEST_DB, 4)` then issues
   row-level DELETE. **Note:** This test must enable FK enforcement
   with `db.execSQL("PRAGMA foreign_keys = ON")` after the
   migration, since Room normally disables FK during migration.

3. **`pipelineOrphanCleaner_preservesChildren_whenParentDeleted`**
   (Robolectric / unit-level on `PipelineOrphanCleaner`) — insert
   parent (inserted_at = day_1) + child (inserted_at = day_9 or
   NULL), run cleanup with cutoff = day_2, assert child still
   present with `parentSessionId == null` (orphaned but alive).

4. **`pipelineOrphanCleaner_doesNotDeleteLegacyRows`** — populate
   the DB with rows whose `inserted_at IS NULL`, run cleanup, assert
   zero deletions.

5. **`findPendingInsertion_filtersOutLegacyRows`** — insert
   COMPLETED rows with old `created_at` (= "legacy") and modern
   ones, assert only the modern ones come back.

These five tests close the F-1 + F-2 coverage gap. Tests 1-2 are
androidTest (need Room MigrationTestHelper); tests 3-5 are JVM-only
via existing fakes.

### §5.5 Spec amendments

Spec 1 §6.1 (the `MIGRATION_3_4` code block, lines 2773-2880) must
be updated to match the new SQL. The backfill comment block
(currently "best-effort; the exact insertion timestamp is not
reconstructable, genügt aber für die 7-Tage-Cleanup-Policy.") gets
rewritten to:

> **Backfill rationale (Spec 1 §6.5 + B3-F-2):** Pre-existing
> COMPLETED rows receive `inserted_at = NULL` — *not* a backfilled
> `created_at`. Pre-M4 the history list had no automatic cleanup;
> users have a multi-month "history is forever" expectation. The
> NULL value places those rows outside the cleanup-policy's reach
> (`deleteInsertedOlderThan` filters `inserted_at IS NOT NULL`) and
> preserves the user contract.

Spec 1 §6.2 R.17 "Cleanup-Cutoff" bulletpoint stays as-is (the
cutoff math is unchanged) but the surrounding "Persistenz-Vertrag"
gets a new bulletpoint:

> **NULL-Semantik für `inserted_at`:** NULL means "not yet inserted
> OR pre-M4 legacy row". Both classes are immune to
> `deleteInsertedOlderThan`. Cleanup operates exclusively on
> `inserted_at IS NOT NULL AND inserted_at < cutoff`.

Spec 1 §6.5 (Cleanup-Policy — currently mentioned in §6.2 R.17 +
§6.3 but no dedicated section title) gets a new sub-section
clarifying the two-bug findings + the chosen policy.

Spec 1 §6.1 FK semantics: the table definition gets `ON DELETE SET NULL`
+ a rationale paragraph:

> **Why `ON DELETE SET NULL` instead of `CASCADE`?** Per F-1 (B3
> audit), `CASCADE` would let `deleteInsertedOlderThan` silently
> remove POST_PROCESSING children whose `inserted_at` is fresh, just
> because their parent aged out. `SET NULL` preserves children
> unconditionally; they become root-level history items (the history
> UI shows sessions as a flat list anyway — no UX regression). The
> data-preservation guarantee outweighs the lost parent-child link.

## §6 Forward-compatibility

- **B5/B6 (LayoutCatalog → orchestrator-driven recording).** The
  fix is in the persistence layer — orthogonal to the recording-side
  orchestration. No coupling.

- **Future parent-child types.** If B5/B6 or later introduces
  additional parent-child relationships (e.g. transcription chains,
  multi-step rewording), they inherit the `SET NULL` semantic
  automatically — children are always preserved.

- **Future cleanup paths.** If a follow-up plan adds
  `deleteFailedOlderThan(60d)` (mentioned in Spec 1 §6.3.1 as a
  Phase-2 possibility), the same NULL-semantics protect legacy
  rows: `deleteFailedOlderThan` would need its own `NOT NULL` check
  (or a created_at-based legacy threshold).

- **Schema versioning.** No additional migrations needed for this
  fix. Amend M4 in place. If a future user device has somehow
  acquired the buggy M4 (e.g. internal CI device), a one-shot
  data-fix can run in M5 if ever needed — but the worktree has not
  shipped, so this is hypothetical.

- **`findPendingInsertion` freshness floor.** The new
  `PendingInsertionFreshnessMs` pref is the only added stateful
  surface. It's a single Long, set conservatively (24h default).
  Future work can tighten it without breaking the cleanup policy
  (which doesn't read it).

## §7 Decision-History entry placeholder

A Decision-History entry should be appended to
`docs/decisions/0003-service-foreground-pipeline-architecture.md`
(ADR-0003 covers the persistence-side adapter per its own §"What's in
scope"). Suggested form (English, follows the existing format):

```markdown
### 2026-05-15 — Cleanup-policy + FK-cascade semantics (B3-VAL-REPAIR)

**Trigger:** B3 block-audit findings F-1 (parent-cascade wipes
children) + F-2 (M3→M4 backfill makes pre-existing history
immediately deletable). Both are Critical silent-data-loss bugs.

**Before:**
- `SessionEntity.parent_session_id` declared
  `ForeignKey.CASCADE`, allowing `deleteInsertedOlderThan` to wipe
  POST_PROCESSING children of an aged-out parent.
- `MigrationTo4.kt` backfilled `inserted_at = created_at` for
  pre-existing COMPLETED rows, exposing months of pre-M4 history
  to the 7-day cleanup at first idle-stop after upgrade.

**After:**
- FK is `ForeignKey.SET_NULL`. Children become root-level history
  items when their parent is deleted by any row-level DELETE.
- Migration backfill is `inserted_at = NULL` for all pre-existing
  rows. NULL-semantics = "the cleanup-marker does not apply to
  this row"; immune to `deleteInsertedOlderThan` (which already
  filters `inserted_at IS NOT NULL`).
- New `Pref.PendingInsertionFreshnessMs` (default 24h) gates
  `findPendingInsertion` so legacy COMPLETED rows don't flood the
  manual-paste notification surface on first post-upgrade boot.
- M4 is amended in place (no MIGRATION_4_5) because the worktree
  has not shipped.

**Reasoning:** Both fixes converge on NULL-as-unknown semantics for
"this row is alive but the cleanup-marker does not apply". The
data-preservation guarantee outweighs the lost parent-child link
(history UI shows sessions as a flat list anyway). Alternative
options (FK RESTRICT, NOT EXISTS sub-query, sentinel value,
is_legacy column, migration-timestamp pref) were evaluated in
`research/b3-cleanup-cascade-and-backfill-policy.md` §§3-4 and
rejected on data-preservation, complexity, or Kotlin-idiomaticity
grounds.

**References:**
- `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/b3-cleanup-cascade-and-backfill-policy.md` (this research)
- `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/reports/validated-findings-B3.md` F-1 + F-2
- Spec 1 §6.1 + §6.2 R.17 + §6.3 + §6.5 (canonical post-fix)
```

## §8 Sources

- **Spec 1 §6.1 Schema-Migration M3→M4** — `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md` lines 2715-2990.
- **Spec 1 §6.2 Checkpoint-Hooks + R.17 Cleanup-Vertrag** — lines 3238-3312.
- **Spec 1 §6.3 Recovery-Read** — lines 3313-3479.
- **Spec 1 §6.3.1 Orphan-FAILED-Audio-Cleanup (KG-SST-2)** — lines 3481-3615.
- **Spec 1 §11.4.2 Migration tests** — lines 5024-5283.
- **Spec 1 §11.7.0 DB-Migrations-Risiko (M3→M4)** — lines 5461-5478.
- **SessionEntity.kt:10-16** — FK declaration with `ForeignKey.CASCADE`.
- **MigrationTo4.kt:97 + :118-131** — FK SQL + backfill CASE expression.
- **SessionDao.kt:122-131 + :143-144** — `findPendingInsertion` + `deleteInsertedOlderThan` queries.
- **PipelineOrphanCleaner.kt:82-101** — `cleanup()` orchestration.
- **SessionOrigin.kt:11-15** — type × origin matrix proving POST_PROCESSING is the live parent-child case.
- **SessionManager.createSession (lines 65-69)** — runtime guard that POST_PROCESSING must have a parent.
- **HistoryDetailActivity.createPostProcessingSession (line 575)** — concrete user path that creates child sessions.
- **docs/DATABASE-PATTERNS.md** — Double-Enum pattern + migration conventions.
- **docs/decisions/0003-service-foreground-pipeline-architecture.md** — ADR for the persistence-side adapter, target of the cleanup-policy Decision-History entry.
- **SQLite foreign keys spec** — https://sqlite.org/foreignkeys.html §4.2 (cascade fires on row-level DELETE, not DROP TABLE) + §4.3 (ON DELETE SET NULL semantics).
- **Room ForeignKey API** — `androidx.room.ForeignKey.SET_NULL` is the Kotlin-side equivalent of SQL `ON DELETE SET NULL`.

## §9 References

- Block-report anchor: `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/reports/validated-findings-B3.md#f-1` and `#f-2`.
- Plan path: `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md`.
- Spec path: `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md`.
- ADR path: `docs/decisions/0003-service-foreground-pipeline-architecture.md` (Decision-History append target).
