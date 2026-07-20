# Repair W1-3 — E-T1: commit the `:shared` catalog/sync closure

**Date:** 2026-07-20T13:30:00+02:00
**Finding:** E-T1 (green, Important) — a clean checkout of HEAD fails to compile `:shared` / `:shared:test`; the block's green suite depends on the dirty working tree.

## What I did

Verified the finding, discovered its suggested 4-file scope is **insufficient**, and
determined + verified the complete `:shared` reproducibility closure (13 files). No
source edits were needed — the files already exist and are correct in the working
tree; the fix is to commit the **complete** set so HEAD's `:shared` matches the
(green) working tree. Because `:shared` has no project dependency on `:companion`/
`:app` (`shared/build.gradle` has no `project(...)` deps), the closure is fully
self-contained within the module.

## Per-finding detail

### E-T1 — fixed (scope corrected + verified)

The suggested fix named 4 files (`CatalogSubscriberStore`, `NotificationPort`,
`WireResponse`, `FakeCatalogSubscriberStore`). Investigation showed that is not
enough to make `:shared` compile from a clean checkout:

- `WireResponse.kt` references `DispatchError.EntityGone`, a variant that exists
  ONLY in the uncommitted modification to `DispatchError.kt` (added at HEAD? no —
  absent at HEAD).
- **`CatalogClient.kt` is already committed** (tracked) yet calls
  `response.parseWire(...)` (only in uncommitted `WireResponse.kt`) and references
  `DispatchError.EntityGone`, plus `CatalogIndexResponse` / `CatalogEntityResponse`
  / `CatalogCredentialResponse` (uncommitted `Dtos.kt`), `Endpoints.CATALOG*`
  (uncommitted `Endpoints.kt`) and catalog validators (uncommitted `Validations.kt`).
  So HEAD is already broken independent of the sync engine.
- `DispatchClient.kt` was modified to delegate to `WireResponse` (−54 lines);
  committing `WireResponse.kt` without it would risk duplicate top-level
  declarations.

**Clean-checkout verification (the check the finding asks for), via a detached
worktree at HEAD:**

- HEAD alone → `:shared:compileKotlin` **FAILED**: unresolved `CatalogCredentialResponse`,
  `CatalogEntityResponse`, `CatalogIndexResponse`, `Endpoints.CATALOG`,
  `CATALOG_ENTITY`, `catalogIndexResponse` validator, etc.
- HEAD + the 13 uncommitted `:shared` files → `:shared:test` **BUILD SUCCESSFUL**.

`gradle/libs.versions.toml` (modified in the working tree) is **deliberately
excluded**: the clean checkout compiled `:shared` against HEAD's version catalog, so
the catalog bump belongs to other modules (companion/app deps), not the `:shared`
closure.

The complete closure (all 13 are the `git status` M/?? set under `shared/`):

Main (9):
- `shared/src/main/kotlin/net/devemperor/dictate/shared/client/WireResponse.kt` (new)
- `shared/src/main/kotlin/net/devemperor/dictate/shared/client/DispatchClient.kt` (modified)
- `shared/src/main/kotlin/net/devemperor/dictate/shared/client/DispatchError.kt` (modified — adds `EntityGone`)
- `shared/src/main/kotlin/net/devemperor/dictate/shared/sync/CatalogSubscriberStore.kt` (new)
- `shared/src/main/kotlin/net/devemperor/dictate/shared/sync/NotificationPort.kt` (new)
- `shared/src/main/kotlin/net/devemperor/dictate/shared/protocol/Dtos.kt` (modified — catalog DTOs)
- `shared/src/main/kotlin/net/devemperor/dictate/shared/protocol/Endpoints.kt` (modified — catalog endpoints)
- `shared/src/main/kotlin/net/devemperor/dictate/shared/protocol/ErrorEnvelope.kt` (modified — `CATALOG_ENTITY_NOT_FOUND`)
- `shared/src/main/kotlin/net/devemperor/dictate/shared/protocol/Validations.kt` (modified — catalog validators)

Test (4):
- `shared/src/test/kotlin/net/devemperor/dictate/shared/sync/FakeCatalogSubscriberStore.kt` (new)
- `shared/src/test/kotlin/net/devemperor/dictate/shared/sync/SyncNotificationTest.kt` (new)
- `shared/src/test/kotlin/net/devemperor/dictate/shared/client/CatalogClientTest.kt` (new)
- `shared/src/test/kotlin/net/devemperor/dictate/shared/protocol/ValidationsTest.kt` (modified)

## Tests

- Working-tree `./gradlew :shared:test` → BUILD SUCCESSFUL (baseline).
- Clean detached-HEAD worktree + the 13 files → `./gradlew :shared:test` BUILD SUCCESSFUL.
- Temp verification worktree removed; working tree unchanged (13 files still uncommitted).

## Skipped

None.

## Files modified

No file contents were changed. The deliverable is the **complete `files_modified`
list** (13 files above) so the commit-agent stages a self-contained, clean-checkout-
compilable `:shared`. Returning only the finding's 4 files would leave HEAD broken.

## Drift (outside assigned scope)

The investigation surfaced that the same "committed reference, uncommitted producer"
pattern very likely also affects `:companion` and `:app` (e.g. untracked
`CatalogService.kt`, `CatalogRoutes.kt`, `SqlDelightCatalogRepository.kt`,
`AndroidNotificationPort.kt`, `CatalogSyncGateway.kt`, and the modified
`libs.versions.toml`). Those are **out of scope for E-T1** (which is explicitly
`:shared`-scoped) and are NOT included here — flagged so a follow-up finding can
verify `:companion:test` / `:app:testDebugUnitTest` clean-checkout reproducibility.
