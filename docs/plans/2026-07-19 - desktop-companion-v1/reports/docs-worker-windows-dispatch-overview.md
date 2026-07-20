# Doc Worker Report — windows-dispatch-overview

**Date:** 2026-07-20T17:25:00+02:00
**Action:** update
**Target:** `docs/architecture/windows-dispatch/README.md`
**Plan range:** `c46cfe8..HEAD`

## Summary

Brought the Windows-Dispatch subsystem overview in line with the shipped `:shared`
module. The genuinely-stale §1a.0 module diagram now reflects the packages the module
grew (catalog client, catalog sync, config-entity model), and the doc now points at the
peer-catalog / config-sharing sibling wire family that rides the same transport — without
absorbing that subsystem's architecture (which is a separate doc gap).

## Changes applied per section

| Section | Change |
|---|---|
| §1a.0 module topology diagram | Rewrote the `:shared` box. Was `protocol / client(DispatchClient) / auth / sync(SyncClient·Cursor·SyncSource)`. Now shows: `client/` = `DispatchClient · CatalogClient · WireResponse (shared 2xx-parse + non-2xx classify) · DispatchError`; a dedicated `transport/` line; `sync/` = history-push (`SyncClient·Cursor·SyncSource`) **and** peer-pull (`CatalogSyncEngine · CatalogSubscriberStore`); a new `config/` line (`catalog entities · CanonicalJson · CatalogCodec · ContentHash · ConfigValidations`). All box lines re-padded to the existing 69-col interior (verified 71 code points/line). |
| §1a.0 prose (after diagram) | Added a `> [!NOTE]` explaining `:shared` grew a **sibling wire family** (peer-catalog / config-sharing) that reuses the same `DispatchTransport` + `DispatchResult`/`DispatchError`, that the 2xx-parse/non-2xx-classify was lifted from `DispatchClient` into `WireResponse.kt`, and that the only overlap this doc owns is `DispatchOutcomeMapper` folding the non-dispatch `EntityGone` (catalog-only) + `EndpointMissing` (`/v1/input`) arms into `WINDOWS_UNREACHABLE` for `when`-exhaustiveness. |
| §3 Code pointers | Expanded the **Shared wire** bullet with the concrete files (`DispatchClient.kt`, `DispatchError.kt`, `WireResponse.kt` → `parseWire`/`classifyWireError`). Added an **Error classification** bullet (`DispatchOutcomeMapper.kt`) and a **Sibling wire family (not Windows-Dispatch)** bullet listing the catalog + config files with an ADR-0030/0034 pointer. |
| §5 References | Added a **Sibling wire family (peer-catalog / config-sharing)** bullet: ADR-0030 (Configuration Entity Model), ADR-0034 (Peer-Catalog Family) + the `peer-katalog.md` spec path. |

## Verified against source

- `WireResponse.kt` — `parseWire` + `classifyWireError` are the shared 2xx-parse/non-2xx-classify (confirmed the logic moved out of `DispatchClient` in the `c46cfe8..HEAD` diff; `DispatchClient` shrank by ~59 lines, `WireResponse.kt` is new).
- `DispatchError.kt` — closed hierarchy; `EntityGone` (catalog `CATALOG_ENTITY_NOT_FOUND`) and `EndpointMissing` (bare 404) both present.
- `DispatchOutcomeMapper.kt` — `EntityGone` arm added this range; comment confirms `EntityGone` = catalog-only, `EndpointMissing` = `/v1/input` keyboard-action path (my note corrected to match — not "both catalog-only").
- `Endpoints.kt` — `CATALOG` / `CATALOG_ENTITY` / `CATALOG_CREDENTIAL` present; `:shared` tree confirmed (`client/{Catalog,Dispatch}Client, DispatchError, WireResponse}`, `sync/{CatalogSyncEngine, CatalogSubscriberStore, ...}`, full `config/` package).
- Spec path `docs/plans/2026-07-19 - desktop-companion-v1/research/peer-katalog.md` exists.
- ADR titles confirmed: 0030 Configuration Entity Model, 0034 Peer-Catalog Family.
- `WindowsAutoSend.kt` diff (`from(sp) != null` → `isPaired(sp)`) is an internal refactor with no doc claim attached — no change needed.

## Removed / added sections

- No sections removed. Added: one `[!NOTE]` block in §1a.0; two bullets in §3; one bullet in §5. No whole-doc reformatting.

## Self-check

Re-read all four edits: box lines align (71 code points each), no leftover placeholders, all referenced file paths and ADR numbers resolve on disk, the `EntityGone`/`EndpointMissing` origin claim matches the `DispatchOutcomeMapper` source comments. Frontmatter `related-adrs` left as-is (ADR-0015..0020) — the sibling ADRs are surfaced as References/pointers rather than promoted into this subsystem's own ADR set, since 0030/0034 govern the peer subsystem, not Windows-Dispatch.

## Notes for final

- **Peer-catalog has no architecture overview doc yet** (discovery flagged a candidate `docs/architecture/peer-catalog/README.md`, and `docs/architecture/config-entity-model.md`). This doc now points readers at ADR-0030/0034 + the `peer-katalog.md` spec as the interim home. If a peer-catalog overview doc lands, the three sibling pointers I added (§1a.0 NOTE, §3 "Sibling wire family" bullet, §5 References bullet) should be re-targeted to link that overview instead of/in addition to the ADRs.
- The `companion-readme` / `database-patterns` / `root-claude-md` update workers touch adjacent SSoT docs; no contradiction introduced here, but the `:shared` package inventory I put in the §1a.0 box should stay consistent with whatever `CLAUDE.md` (root) says about `:shared` contents.
