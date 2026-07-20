# E2 — Sync-Engine + Benachrichtigung + Android-Bezieher (impl report)

**Chunk:** E2 · **Date:** 2026-07-20 · **Agent:** IMPL+TEST (chunk-impl-test)

## What was done

Implemented the peer-catalog subscriber side across three layers per spec `peer-katalog.md`
§6/§6.5/§7 and Plan §5 / §3 D5.f:

- **E2a (`:shared/sync`) — the acceptance core, fully tested.** A pure, platform-neutral
  `CatalogSyncEngine` (sibling of `SyncClient` on the receive side): one-GET no-op path, per-subscription
  diff, two-hash verify-before-write, source-removed handling, and query-based fork protection. Plus the
  ports/DTOs it drives (`CatalogSubscriberStore`, `NotificationPort` + `SyncNotification`/`CatalogChange`,
  `CatalogSyncOutcome`/`VerifyFailure`).
- **E2b (companion) — notification + scheduler.** `NoopNotificationPort` + `AwtNotificationPort`
  (AWT `SystemTray`, Spec-D6) implementing the shared port, wired through `PlatformModule.detect()`;
  `CatalogSyncScheduler` (timer + app-start/window-open triggers); `CompanionSettings.catalogSyncIntervalMillis`.
- **E2c (`:app`) — WorkManager poller + notification.** The **mandatory D5.f ceiling PRÜFAUFTRAG**
  (documented, see below), the `androidx.work:work-runtime-ktx:2.10.5` dependency, `CatalogSyncWorker`
  (`CoroutineWorker` + periodic/one-shot scheduling), `AndroidNotificationPort` + the `catalog_sync`
  `NotificationChannel`.

## D5.f WorkManager Kotlin-ceiling check (R4 / ADR-0015) — PASS

Verified **before** adding, per the PRÜFAUFTRAG. Method: `javap -v` on `androidx/work/CoroutineWorker.class`
from the published AAR + the POM `kotlin-stdlib` dependency.

| Version | `@Metadata mv` | kotlin-stdlib | Verdict |
|---|---|---|---|
| work-runtime **2.10.5** | `[1,8,0]` (Kotlin 1.9 line) | 1.8.22 | OK — wide margin below 2.1.20 |
| work-runtime 2.11.2 | `[2,1,0]` | 2.1.20 | OK — exactly at ceiling |
| work-runtime 2.12.0-alpha+ | Kotlin 2.2 line | — | REJECTED — do not adopt until `kotlin` lifts |

**Chosen: 2.10.5** (conservative stable, zero forward-metadata risk). The `AlarmManager`+`JobScheduler`
fallback was **not** needed. Empirically confirmed: `:app:compileDebugKotlin` succeeds with the dependency
(no metadata rejection). Documented in `gradle/libs.versions.toml` (`work`).

## Deviations

| Deviation | Plan location | What changed | Why | Impact on later chunks | Resolved? |
|---|---|---|---|---|---|
| `NotificationPort` lives in `:shared/sync`, not `companion/domain/port` | §7 | The port the shared engine invokes must live in `:shared`, else `:shared`→`:companion` cycles | Module layering; honors §7's "one port, two hows" (Awt/Noop/Android all implement it) | E3 wires the companion impls; already done for E2 | ✓ documented |
| Spec `Stale` outcome merged into `PeerUnreachable` | §6.1 sealed class | One run-outcome for "couldn't reach"; staleness is a *derived* peer state from `last_success_at` (§5.1), not a run result | Two outcomes meaning the same for one run would be dead code | none | ✓ |
| Enum names: engine uses E1's real `CatalogEntityKindWire` / `SubscriptionMode`, not spec's `*Wire` triple | §5.2/§5.3 | Match what E1/D3 actually shipped (`subscription_mode='LOCAL'`, not NULL) | Code reality over spec prose; fork protection is query-based (`activeSubscriptions` returns only SUBSCRIBE) | none — AC8 still holds | ✓ |
| Partial run does NOT advance `last_root_hash` | §6.1 step 4 | `recordSuccess(rootHash=null)` on any fetch/verify failure | Self-healing: the next run re-detects and retries the unresolved change (SyncClient stall-guard analogue) | none | ✓ hardening |

## Issues

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| E2-1 | Important | DB-backed `CatalogSubscriberStore` impls not implemented: `SqlDelightCatalogSubscriberStore` (companion — peers/subscriptions queries + adapters + payload→entity reconstruction across 4 kinds + credential→SecretStore) and `AndroidCatalogSubscriberStore` (Room), the `CatalogSyncTargets`/`CatalogSyncGateway` real impls (peer→client incl. SecretStore-backed credentials), the container/app-init call sites (`CatalogSyncScheduler.start()` / `CatalogSyncWorker.enqueue()`), and the two-peer `CatalogE2ETest` (§11). The engine + its port contract are complete and tested; these are the credential-sensitive persistence adapters that wire it to each platform's real store. | delegated | blocks-following |

Rationale for delegating E2-1: this is a large, credential-touching sub-chunk (spec's own E2b/E2c
persistence migration steps + the two-peer E2E). Given BLOCK-E's explicit security-audit emphasis on
credential paths, a careful reviewed adapter is warranted over a rushed one. The seams are in place
(`CatalogSubscriberStore`, `CatalogSyncTargets`, `CatalogSyncGateway`) and documented.

## Inline fixes applied

- Fixed a broken `lateinit var client` design in the first engine draft (data race + never assigned);
  the `CatalogClient` is now threaded through the pull helpers as a parameter.

## Tests (all green for this chunk)

- `:shared` `CatalogSyncEngineTest` — 12 tests, AC6–AC10 (one-GET idempotency, update detection, fork
  skip, source-removed, both verify-hash failures, unreachable=stale, endpoint-missing, credential
  delivery, mixed partial run). `SyncNotificationTest` — 3 tests (summary formatting).
- `:companion` `CatalogSyncSchedulerTest` — 3 tests (visits every peer, continues after a per-peer throw,
  swallows enumeration failure).
- `:app` `AndroidNotificationPortTest` — 2 tests, Robolectric (channel registration, crash-free notify).

`:shared:test`, `:companion:test` green in full. `:app:testDebugUnitTest` full run: **2508/2509 pass**;
the single failure is `AndroidKeystoreSecretStoreTest.put_whenKekUnavailable_throwsUnavailable`, a
**pre-existing order-dependent Robolectric flake** (passes in isolation; the E2 diff does not touch the
`secrets/` package — shared static keystore state across the suite).

## Files outside assigned scope (drift)

- `gradle/libs.versions.toml`, `app/build.gradle` — added the WorkManager dependency + ceiling doc (required by the chunk). Rationale: E2c cannot exist without it; the PRÜFAUFTRAG mandates documenting it here.
- `companion/.../domain/CompanionSettings.kt` — added `catalogSyncIntervalMillis` (§6.5 scheduler interval). Rationale: the scheduler's interval source per spec.
- `companion/.../platform/PlatformModule.kt` — added the `notificationPort` binding (§7 wiring). Rationale: the required call site for the notification port.
- `app/src/main/res/values/strings.xml` — added 2 additive strings (channel name + notif title). Rationale: user-visible notification text, default locale only (others fall back).

## Files modified

See structured return `files_modified`.
