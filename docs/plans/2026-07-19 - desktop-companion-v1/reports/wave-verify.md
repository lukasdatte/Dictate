# Wave-Verify Report

**Timestamp:** 2026-07-20T13:30:00+02:00
**Worktree:** /home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1
**Branch:** feature/desktop-companion-v1
**HEAD:** d0d2e19 `[F] repair wave 1 (desktop-companion-v1)`

**Verdict: FAIL (clean = false)** — HEAD does not compile. A coherent, uncommitted
"catalog-sync" feature slice (peer-catalog block) sits in the working tree; several
of its files are untracked yet are already imported by **committed** HEAD code. The
committed tree therefore has unresolved references (`:companion` will not compile),
while the working tree looks green only because the untracked producer files exist
on disk. This is exactly the untracked-producer failure this gate guards against.

---

## Check 1 — Everything landed (`git status` vs ALL_FILES)

**Result: FAIL.** Untracked source files matching ALL_FILES globs
(`companion/src/main/kotlin/.../companion/data/**`, `companion/src/main/sqldelight/**`)
are present and uncommitted:

```
companion/src/main/kotlin/net/devemperor/dictate/companion/data/SqlDelightCatalogAuditLog.kt
companion/src/main/kotlin/net/devemperor/dictate/companion/data/SqlDelightCatalogRepository.kt
companion/src/main/sqldelight/databases/5.db
companion/src/main/sqldelight/net/devemperor/dictate/companion/db/migrations/4.sqm
```

Broader uncommitted slice (same feature, mostly outside ALL_FILES literally but pulled
into the run's scope by Check 2 because committed code depends on them):

Untracked source:
```
app/src/main/java/net/devemperor/dictate/peers/AndroidNotificationPort.kt
app/src/main/java/net/devemperor/dictate/peers/CatalogSyncGateway.kt
app/src/main/java/net/devemperor/dictate/peers/CatalogSyncWorker.kt
app/src/test/java/net/devemperor/dictate/peers/AndroidNotificationPortTest.kt
companion/.../catalog/CatalogSyncScheduler.kt
companion/.../domain/CatalogService.kt
companion/.../domain/port/CatalogAuditLog.kt
companion/.../domain/port/CatalogEntityRepository.kt
companion/.../platform/fallback/NoopNotificationPort.kt
companion/.../platform/windows/AwtNotificationPort.kt
companion/.../server/routes/CatalogRoutes.kt
companion/.../test/.../catalog/CatalogSyncSchedulerTest.kt
companion/.../test/.../data/CatalogCheckConstraintParityTest.kt
companion/.../test/.../domain/CatalogServiceTest.kt
companion/.../test/.../fakes/FakeSecretStore.kt
companion/.../test/.../server/CatalogE2ETest.kt
```

Modified (tracked) but uncommitted:
```
 M app/src/main/java/net/devemperor/dictate/windows/DispatchOutcomeMapper.kt
 M companion/.../domain/CompanionSettings.kt
 M companion/.../domain/DomainErrors.kt
 M companion/.../domain/HealthService.kt
 M companion/.../platform/PlatformModule.kt
 M companion/.../server/CompanionServer.kt
 M companion/.../server/plugins/StatusPagesSetup.kt
 M gradle/libs.versions.toml
```

(Numerous `docs/plans/.../reports/**`, `research/**`, `chunks.json`, `*.state.md`
are also untracked — these are orchestration artifacts, not source, and are not
blocking.)

## Check 2 — Untracked-producer guard

**Result: FAIL (decisive).** Committed HEAD files reference classes whose definition
files exist ONLY in the untracked working tree:

- HEAD `companion/.../CompanionContainer.kt` — real imports + instantiations, not comments:
  - `:22 import ...data.SqlDelightCatalogAuditLog` → `:209 SqlDelightCatalogAuditLog(database)`
  - `:23 import ...data.SqlDelightCatalogRepository` → `:211 SqlDelightCatalogRepository(configRepository)`
  - `:30 import ...domain.CatalogService` → `:210 CatalogService(...)`
  - `:40 import ...domain.port.CatalogAuditLog`
- HEAD `companion/.../data/SqlDelightPeerExplorerStore.kt` → `SqlDelightCatalogRepository`
- HEAD `companion/.../ui/peers/OfferViewModel.kt`, `companion/.../db/Companion.sq`,
  `companion/.../test/.../OfferViewModelTest.kt` → `CatalogAuditLog`

Confirmed NONE of these classes are defined anywhere in HEAD:
```
CatalogService              defined in HEAD: NONE
SqlDelightCatalogRepository defined in HEAD: NONE
SqlDelightCatalogAuditLog   defined in HEAD: NONE
CatalogAuditLog             defined in HEAD: NONE
CatalogEntityRepository     defined in HEAD: NONE
```
Their definition files (`CatalogService.kt`, `SqlDelightCatalogRepository.kt`, …) are
untracked (`git cat-file -e HEAD:<path>` → NOT in HEAD).

(The `CatalogSyncWorker`/`CatalogSyncScheduler`/`*NotificationPort` hits in
`CatalogSyncEngine.kt`, `NotificationPort.kt`, `app/build.gradle` are KDoc/comment
references only — not compile-breaking on their own. The `CompanionContainer.kt`,
`SqlDelightPeerExplorerStore.kt`, `OfferViewModel.kt` references above ARE real code.)

## Check 3 — Clean-HEAD typecheck

**Not run as a working-tree build — it would be misleading.** Check 3's working-tree
shortcut is only valid when Checks 1 & 2 pass; here they fail. Because the untracked
producer files are present on disk, `./gradlew build` / `:companion:test` against the
working tree would compile GREEN and misrepresent HEAD. The static evidence in Check 2
is conclusive: the committed `:companion` module imports and instantiates
`CatalogService`, `SqlDelightCatalogRepository`, `SqlDelightCatalogAuditLog`,
`CatalogAuditLog`, none of which exist in the committed tree → HEAD `:companion`
compilation fails with unresolved references.

---

## Remediation

Commit the catalog-sync slice (untracked source + modified tracked files) with the
plan's file-scoped commit convention, then re-run wave-verify. Until then HEAD is red
and the wave must NOT be reported complete.
