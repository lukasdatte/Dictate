# E1 — CatalogClient — Self-Fix Report (fresh eyes)

**Chunk:** E1 (Block E) · **Scope:** `shared/.../client/CatalogClient.kt` only
**Date:** 2026-07-20T13:30:00+02:00 · **Plan:** desktop-companion-v1

## What I did

Fresh-eyes review of the just-committed `CatalogClient.kt` (wave commit `8fb0cdf`)
against spec `peer-katalog.md` §3.5 and the surrounding shared client code
(`DispatchClient`, `WireResponse`, `DispatchError`, transport, `Endpoints`,
`Validations`). Three lenses: plan correctness, code quality, test quality. **No
fixes required** — the chunk is clean. Reran `CatalogClientTest` fresh: green.

## Review findings

### Plan correctness — PASS
- Signature matches spec §3.5 verbatim: `index()` / `entity(id)` / `credential(id)`
  returning `DispatchResult<Catalog*Response>`; constructor `(DispatchTransport,
  () -> Credentials?)`.
- Reuses the mandated primitives (`DispatchTransport`, `Credentials`,
  `AuthHeaders.forDevice`, `ProtocolCodec` via `parseWire`) — pure JVM, no new
  error family. Matches the §3.5 NOTE ("reuse `DispatchResult<T>`/`DispatchError`;
  a `CatalogError` extension by `EntityGone` suffices").
- The 404 fork is correct per AC1 and the §6.4 contract: a **bare** 404
  (`classifyWireError` → `Server(404, …)`) is remapped to `EndpointMissing`;
  a 404 carrying a `CATALOG_ENTITY_NOT_FOUND` envelope is classified as
  `EntityGone` by the shared classifier (not `Server`), so the remap correctly
  leaves it intact. Verified by walking both `classifyWireError` arms.
- The implementer's documented deviation (shared `WireResponse.kt` plumbing instead
  of a per-client copy of `classifyError`) is a D4-aligned improvement and is faithful
  to the §3.5 NOTE — the classification still "follows exactly" `DispatchClient`'s,
  because it is now the *same* function.

### Code quality — PASS
- KDoc is accurate and explains WHY (the 404 fork, the lambda-not-value credential,
  the blocking contract, the parallel-to-`DispatchClient` rationale). No WHAT-noise.
- Naming and structure match `DispatchClient` (local `credentials` shadow mirrors
  `DispatchClient.authenticated`; `catalogRead` mirrors `read` + `input`'s 404 remap).
- Entity ids are interpolated unencoded into the path; this is safe — `ENTITY_ID_PATTERN`
  (`[A-Za-z0-9._:-]`) is entirely URL-path-safe and the server re-validates, so a
  malformed id can only ever be a caller bug, surfaced as a 400/404 by the server.
- DRY: the only genuinely shared, non-trivial logic (`parseWire` / `classifyWireError`
  / `describeWire`) was already extracted to `WireResponse.kt`. The residual overlap
  with `DispatchClient.read` (get + IOException→`Unreachable` + `parseWire`) is trivial
  boilerplate across two intentionally-decoupled clients and sits at 2 uses — extracting
  it further would be premature abstraction / cross-class coupling. Correctly left alone.

### Test quality — PASS
`CatalogClientTest` (9 tests) exercises every branch of `catalogRead`:
- success decode for all three calls;
- null credentials → `Unauthorized` **without touching the wire** (asserts
  `transport.calls.isEmpty()`);
- `IOException` → `Unreachable`;
- bare 404 → `EndpointMissing`; 404+`CATALOG_ENTITY_NOT_FOUND` envelope →
  `EntityGone` (both entity and credential); 401 → `Unauthorized`.
Behavioral names, concrete assertions, shared `FakeTransport`/`ProtocolCodec` helpers.
No coverage gap for this file.

## Issues

None.

## Inline fixes applied

None — no defect found.

## Files modified

None.

**Out-of-scope (drift):** none.

## Test-run result

- `./gradlew :shared:test --tests CatalogClientTest --rerun-tasks` — **green** (freshly executed, 9/9).
- `./gradlew :shared:test` — green.
