# Block E — Audit: `plan-and-api`

**Topic:** plan-and-api · **Block:** E (Peer-Katalog + Abo-Sync + Peer Explorer)
**Date:** 2026-07-20T13:30:00+02:00 · **Last verify commit:** c46cfe8 → HEAD (4952233)
**Spec:** `research/peer-katalog.md` (SSoT) · **Plan:** `desktop-companion-v1.md` §5 Block E

Grounding: `knowledge-typescript` (discriminated-union / API-contract lenses, applied
conceptually to Kotlin sealed classes + ports), project `CLAUDE.md`. Topic facets:
(a) plan fidelity vs. spec, cross-checked against chunk deviation tables; (b) stubs /
placeholder returns / not-implemented; (c) API-consumer match across chunks.

## Verdict summary

E1 (offer side) is faithfully and completely implemented against spec §3/§4/§5 and is
covered by real single-peer HTTP E2E tests (AC1–AC5). The `:shared` engine core (E2a) and
the Explorer UI + discovery + headless (E3) are faithful to §6/§8/§9 at the unit level.
The **subscriber runtime is not acceptance-complete**: the two-peer E2E (AC10) is absent and
the production consumer path is entirely unwired. Both are honestly documented as delegated
issues (E2-1, E3-1) with honest UI degradation — no stub fakes a passing AC, no
throw-not-implemented, no TODO markers in the diff. The findings below are the acceptance-
status truth the block-completion decision needs, plus one undocumented cross-seam contract gap.

## Findings

### plan-and-api-E-1 — AC10 (two-peer E2E) unmet and mischaracterized as covered · Important · CONFIRMED

`E2-impl.md` maps "AC6–AC10" to `CatalogSyncEngineTest` (12–13 tests over `FakeTransport` +
in-memory fakes). AC6–AC9 are legitimately engine-unit-testable and are covered. **AC10 is
not**: spec §2.10 / §11 require *two* in-process `embeddedServer(CIO, port=0)` instances
sharing Prompt/Profil/ModelRef/Credential over the **real** `CatalogClient` on real HTTP, with
the credential landing only in the receiver's (fake) SecretStore and an `catalog_access_log`
row on the provider. That two-peer `CatalogE2ETest` does **not** exist (only E1's single-peer
`CatalogE2ETest` does), and it is explicitly delegated inside issue **E2-1**.

- Files: `shared/src/main/kotlin/net/devemperor/dictate/shared/sync/CatalogSyncEngine.kt`
  (engine under test), spec §11 (the missing test).
- Why it matters: an acceptance criterion is presented as covered-by-proxy but the literal
  criterion (end-to-end credential isolation across two real servers) is unverified. If
  unnoticed, Block E could be marked acceptance-complete without its headline integration test.
- Failure scenario: a regression in `CatalogClient`↔server wiring (auth header, 404 fork,
  credential-route isolation) that a `FakeTransport` unit cannot see ships green.
- Suggested fix: implement the two-peer `CatalogE2ETest` per §11 as part of E2-1; until then
  do not treat AC6–AC10 as satisfied at the block gate. (Tracked by E2-1.)

### plan-and-api-E-2 — Production subscriber runtime is unwired; the consumer feature cannot run · Important · CONFIRMED

`CompanionContainer.production()` leaves `peerIndexSource` / `catalogSyncRunner` /
`catalogSubscriber` **null**, never constructs or starts a `CatalogSyncScheduler`, and there
is no `SqlDelightCatalogSubscriberStore` / Android `CatalogSubscriberStore` impl, no Android
`CatalogSyncWorker` wiring, and no add-peer pair-redemption path. The `peers` / `subscriptions`
sync-write queries (`recordContact`/`recordSuccess`/`applyEntityUpdate`) are deliberately not
in `Companion.sq` (E2 adapter's to add). Consequently, on a real companion the Peer Explorer
can never fetch a peer index (always CURRENT/STALE), never sync-now, never subscribe, and no
production code path ever inserts a `peers` or `subscriptions` row — the entire §6/§8.3/§9.1
consumer runtime is inert. This is honestly documented (E2-1, E3-1) and the UI degrades
honestly (`PeersScreen.kt:45` → `PeerIndexSource { null }`; `canSubscribe = false`), so it is
not deceptive — but the block ships offer-side (E1) + engine core (E2a) + UI shells (E3) only.

- Files: `companion/src/main/kotlin/net/devemperor/dictate/companion/CompanionContainer.kt:135-137,242-267`,
  `companion/src/main/kotlin/net/devemperor/dictate/companion/catalog/PeerIndexSource.kt`,
  `shared/src/main/kotlin/net/devemperor/dictate/shared/sync/CatalogSubscriberStore.kt`
  (port with no production impl).
- Why it matters: AC6–AC10's *runtime* and the whole subscriber user-feature are non-functional
  in production until the delegated credential adapter (E2-1) and its call sites (E3-1) land.
- Failure scenario: a user on a real companion adds/uses the Peers screen and nothing ever
  syncs or can be subscribed to — the feature is present but dead until E2-1/E3-1.
- Suggested fix: schedule E2-1 (SqlDelight + Android `CatalogSubscriberStore`, `CatalogSyncTargets`
  per-peer `CatalogClient`+SecretStore builder, `CatalogSyncScheduler.start()` / `CatalogSyncWorker.enqueue()`
  call sites) and E3-1 (add-peer pairing, Android peers storage) before closing Block E.
  (Tracked by E2-1, E3-1.)

### plan-and-api-E-3 — `markSourceRemoved` has no schema home; engine-detected SOURCE_REMOVED is not persisted · Nice-to-have · PLAUSIBLE

`CatalogSubscriberStore.markSourceRemoved` promises to "mark the subscription SOURCE_REMOVED",
but there is no such state anywhere in the schema: `SubscriptionMode = {LOCAL, SUBSCRIBE,
ONE_SHOT}` (`ConfigEnums.kt:56`) and `subscriptions.mode CHECK IN ('SUBSCRIBE','ONE_SHOT')`
have no SOURCE_REMOVED value, and there is no `source_removed` column. The Explorer's
`CopyState.SOURCE_REMOVED` (`PeerExplorerViewModel.kt:169`) is derived purely live — the entry
is absent from the freshly-fetched index — and falls back to `CURRENT` when no index is in hand
(`PeerExplorerViewModel.kt:168`). So the two "SOURCE_REMOVED" notions are disconnected: the
engine detects + notifies a removal, but nothing persists it, and if the peer's index is later
unfetchable the Explorer shows CURRENT, silently disagreeing with the notification the user saw.

- Files: `shared/src/main/kotlin/net/devemperor/dictate/shared/sync/CatalogSubscriberStore.kt:74-79`,
  `companion/src/main/kotlin/net/devemperor/dictate/companion/ui/peers/PeerExplorerViewModel.kt:165-171`,
  `companion/src/main/sqldelight/net/devemperor/dictate/companion/db/Companion.sq` (subscriptions DDL).
- Why it matters: the E2 persistence adapter (E2-1) inherits an under-specified contract — what
  `markSourceRemoved` writes and who reads it is undefined; the naming implies a persisted flag
  that has no column.
- Suggested fix: either add a persisted marker (e.g. a `source_removed_at` column read by a
  `copiesFrom` SOURCE_REMOVED derivation) or explicitly redefine the port method to "record the
  attempt (`last_checked_at`), keep the copy — SOURCE_REMOVED is index-derived only" so the
  delegated adapter has a defined, non-misleading behavior. Forward-looking to E2-1.

## Out-of-scope observations (for other topics)

- **logic:** in the no-op path and after a SOURCE_REMOVED-only run the engine advances
  `last_root_hash` (via `recordSuccess(..., index.rootHash)`), so a re-shared entity self-heals
  on the next root-hash change — verified correct, noted for the logic auditor's edge-case pass.
- **convention:** `error::class.java.simpleName` appears in two contexts in
  `CatalogSyncEngine.kt` (index-fail vs. pull-fail); 2-use, below the extraction threshold — the
  self-fix already considered and left it. Not a plan-and-api finding.

## Coverage note

Audited (spec-cross-checked): `CatalogClient.kt`, `CatalogSyncEngine.kt` + `CatalogSubscriberStore.kt`,
`PeerDiscovery.kt`, `TailscalePeerDiscovery.kt`, `PeerIndexSource.kt`, `PeerExplorerStore.kt`,
`SqlDelightPeerExplorerStore.kt`, `CompanionContainer.kt`, `Main.kt`, `App.kt` (PEERS destination
wiring — reachable, not dead), `PeersScreen.kt`, `PeerExplorerViewModel.kt` (§8.1 matrix),
`OfferViewModel.kt` (§8.2 — fully implemented), `Companion.sq` (peers/subscriptions/access_log
DDL + queries), `Validations.kt` + `Endpoints.kt` (label-cap consumer match — the 200 cap is used
on BOTH server encode and subscriber validation, so a long-named shared entity round-trips; the E1
deviation is resolved on both sides).

Verified-consistent (no finding): DTO/Validation field parity §3.2↔§3.3; `CatalogClient` 404-fork
vs. `EntityGone` classification matches §3.5 NOTE + AC1; `CatalogEntityKindWire`/`Visibility`/
`SubscriptionMode` enum reuse (E1/E2/E3 all consume the same `:shared` enums — D3 unification, no
parallel `*Wire` copies); `subscriptions.mode` subset CHECK vs. entity `subscription_mode`
full-enum; fork = mode-flip-to-LOCAL + subscription-row-delete in one transaction (§5.3, AC8) in
both the engine's `activeSubscriptions` query contract and `SqlDelightPeerExplorerStore.fork`.

Not audited in depth (delegated / out of topic): Android `PeerCopiesOverview`/`PeerExplorerActivity`
read-only page (E3, actions delegated to E3-1); `CatalogService`/`CatalogRoutes` server internals
(E1, covered by passing E2E — trusted); test-body correctness (that is the `test` auditor's topic).

No stubs, no `TODO`/`FIXME`, no throw-not-implemented in the block diff — the unwired seams are
typed nullable ports with honest UI degradation, not placeholder returns.
