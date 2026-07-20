# Block E — Convention Audit

**Topic:** convention (same operation done differently across chunks: logging, error handling,
naming, file layout, imports) · **Block:** E · **Date:** 2026-07-20T13:30:00+02:00
**Base:** `c46cfe8..HEAD` · **Plan:** desktop-companion-v1

## Scope & method

Grounding: project `CLAUDE.md` (Kotlin-new/Java-legacy, DictatePrefs, Double-Enum, Room-DAO
conventions; "No linter or formatter is configured"), `knowledge-reference` (TS-centric, not
directly applicable to this Kotlin/Java codebase). Read all three chunk report pairs
(E1/E2/E3 impl + self-fix), the file-scoped diff, and the E-owned files at HEAD.

Audited the E-owned surfaces for cross-chunk consistency: the shared client/engine
(`CatalogClient`, `CatalogSyncEngine`), companion discovery (`PeerDiscovery`,
`TailscalePeerDiscovery`), the Explorer ports/store/ViewModels
(`PeerExplorerStore`, `SqlDelightPeerExplorerStore`, `PeerExplorerViewModel`, `OfferViewModel`),
the Compose screens (`PeersScreen`, `PeerListScreen`, `PeerDetailScreen`, `OfferScreen`,
`ManagementScreen` SourceBadge), integration points (`App.kt`, `Main.kt`, `CompanionContainer.kt`),
SQLDelight query names, and the Android side (`PeerCopiesOverview`, `PeerExplorerActivity`).

The block is broadly consistent. Positives worth recording (NOT findings):

- `CatalogSyncEngine` (E2) mirrors its sibling `SyncClient` exactly: same
  `log: (String) -> Unit = {}` seam, same `"<subsystem>-sync:"` prefix
  (`catalog-sync:` vs `windows-sync:`), same error-as-outcome contract (never throws into the
  caller's thread). Same for `CatalogClient` reusing `DispatchResult`/`DispatchError` rather than
  a parallel family.
- Both ViewModels (`PeerExplorerViewModel`, `OfferViewModel`) follow the `HistoryViewModel`
  precedent verbatim: plain class, injected `scope`, `MutableStateFlow` + `asStateFlow()`,
  `init { refresh() }`, launch-per-action.
- SQLDelight query naming (`allPeers`/`insertPeer`/`subscriptionsForPeer`/`deleteSubscription`,
  `recordCatalogAccess`/`catalogAccessForEntity`) matches the surrounding house convention
  (`allUsage`, `promptsForProfile`, `insertProfilePrompt`, `upsert*`).
- `TailscalePeerDiscovery`'s failure-swallowing (`catch (_: Exception) -> emptyList()`) is the
  documented `JvmNetworkInterfaces` pattern, correctly reused.
- Android side correctly uses string resources (`R.string.dictate_peer_explorer_*`) while the
  companion Compose desktop uses inline English — each platform's own established convention
  (companion `HistoryScreen`/`ManagementScreen` are all inline English). Not a divergence.

## Findings

### convention-E-1 — Timestamp formatting diverges from the established companion pattern and is duplicated (Nice-to-have)

**Files:**
- `companion/.../ui/peers/PeerListScreen.kt:87-90` (`lastReached`)
- `companion/.../ui/peers/OfferScreen.kt:66-70` (`lastPickup`)

The established companion UI convention for rendering an epoch-milli `Long` as a display time is
`HistoryScreen`'s top-level extension `Long.asTime()` (HistoryScreen.kt:403-406), backed by
`java.time.DateTimeFormatter` + `Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())`.

E3's two new screens each instead re-roll the legacy `java.text.DateFormat` +
`java.util.Date` API:

```kotlin
DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))
```

— identical logic copy-pasted across the two sibling files, and different from the one existing
companion timestamp helper. This is the audit's "same operation done differently across chunks"
case (and a small intra-chunk DRY duplication).

**Why it matters:** two timestamp-formatting styles now coexist in the companion UI package, and a
future reader has to discover both. Cosmetically the outputs also differ (locale `SHORT/SHORT`
vs the fixed `dd.MM. HH:mm` pattern), so the Peers screen and History screen print times in
different formats.

**Expected instead:** reuse (or lift to a shared companion UI helper) the existing `asTime()`
extension so all companion screens format times one way. No behavioural requirement; consistency only.

### convention-E-2 — Adjacent status-chip labels use inconsistent casing within the Peer Explorer (Nice-to-have)

**Files:** `companion/.../ui/peers/PeerListScreen.kt:77-85` (`StatusLabel`),
`companion/.../ui/peers/PeerDetailScreen.kt:88-98` (`CopyStateLabel`)

Both are the same UI idiom — a small coloured `Text` chip driven by an exhaustive `when` over an
enum — and they render side by side on `PeerDetailScreen` (the peer's `StatusLabel` at the top, a
per-copy `CopyStateLabel` in each row). Yet they disagree on casing:

- `StatusLabel`: `"OK"` / `"STALE"` / `"UNREACHABLE"` (upper-case)
- `CopyStateLabel`: `"current"` / `"update available"` / `"forked"` / `"stale"` / `"source removed"`
  (lower-case, spaced)

Note `"STALE"` (peer) vs `"stale"` (copy) appear on the same screen in different casing.

**Why it matters:** two chips of the same visual family, adjacent on one screen, styled by two
casing rules reads as accidental rather than intentional. Minor polish, but it is exactly the kind
of cross-component styling drift this lens covers.

**Expected instead:** pick one casing/spacing rule for the peer/copy status pills and apply it to
both.

### convention-E-3 — Import block ordering drift in CompanionContainer.kt (Nice-to-have, low confidence)

**File:** `companion/.../CompanionContainer.kt:1-70` (import block)

E3's additions inserted imports out of the file's otherwise package-alphabetical single-block order:
`SqlDelightPeerExplorerStore` lands before `SqlDelightDeviceRepository` (P before D), and a
`capture.*` + `catalog.*` cluster is dropped mid-`data`/`domain` rather than in package order.

**Why it matters / confidence:** imports are an explicit convention axis, but the repo has **no
linter or formatter configured** (CLAUDE.md), the ordering is not enforced anywhere, and an IDE
auto-import pass fixes it mechanically. This is the weakest of the three findings — recorded for
completeness, not as a blocker.

## Coverage

| Area | Files | Audited |
|---|---|---|
| Shared client/engine | CatalogClient.kt, CatalogSyncEngine.kt | ✅ |
| Discovery | PeerDiscovery.kt, TailscalePeerDiscovery.kt | ✅ |
| Explorer ports/store/VMs | PeerIndexSource.kt, PeerExplorerStore.kt, SqlDelightPeerExplorerStore.kt, PeerExplorerViewModel.kt, OfferViewModel.kt | ✅ |
| Compose screens | PeersScreen, PeerListScreen, PeerDetailScreen, OfferScreen, ManagementScreen (SourceBadge) | ✅ |
| Integration | App.kt, Main.kt, CompanionContainer.kt | ✅ |
| SQLDelight | Companion.sq (E-added query names), CompanionDatabase.kt (adapters) | ✅ |
| Android | PeerCopiesOverview.kt, PeerExplorerActivity.kt | ✅ |
| Tests | Not primary for convention topic; naming spot-checked via reports | partial (test topic owns depth) |

No files skipped in the E scope. Non-E work interleaved on shared files (D3's ManagementScreen
sections, D2's Main.kt dictation-panel block) was read for context but not audited as E findings.

## Out-of-scope observations (for the consolidator)

- **logic/DRY:** the exhaustive `when (kind)` over the 6 `CatalogEntityKindWire` values is repeated
  three times inside E3 (`OfferViewModel.setVisibility`, `SqlDelightPeerExplorerStore.fork`,
  `SqlDelightPeerExplorerStore.labelOf`), each dispatching to the same four config accessors. Not a
  convention divergence (each does genuinely different work), but a `logic`/simplification lens may
  want to look at a shared kind→entity dispatch helper.
- **naming (minor):** `SqlDelightPeerExplorerStore` has both the public `fork(localEntityId, kind)`
  (mutating) and a private `fork(id, sourceId, kind, label, hash)` (a `SubscribedCopy` factory) —
  same name, two unrelated purposes. Reads slightly ambiguous; a `logic`/naming reviewer may prefer
  renaming the private constructor helper (e.g. `forkRow`).
