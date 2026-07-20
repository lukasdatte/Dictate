# Repair Wave C W1-7 — C-TEST-1

**Date:** 2026-07-20T00:40:00+02:00
**Finding:** C-TEST-1 (Important, green) — C3-3 bug fix shipped with no regression test.

## What I did

Added `app/src/test/java/net/devemperor/dictate/config/PromptProvenanceTest.kt` — a
dedicated regression guard for `config/PromptProvenance` (the C3-3 fix that keeps the
v12 `uuid`/`content_hash` columns correct at the prompt write seams).

Assertions:

- **`stamped`**: mints a well-formed UUID when `uuid == ""`, stamps `updatedAt = now`,
  and populates `content_hash` matching `PromptHashing.contentHashOf(uuid, row)`;
  preserves an existing uuid; recomputes a *stale* stored hash without disturbing the
  uuid. This is the direct guard against reverting a seam to the historical 7-arg
  `PromptEntity` constructor (which reset uuid + hash) — that revert turns
  `stamped preserves an existing uuid` / `recomputes a stale content hash` red.
- **`edited`**: applies the new name/text/flags/type, carries the envelope over
  (`uuid`, `sourcePeerId`, `sourceOriginalId`), and re-derives `content_hash` from the
  new payload (differs from the pre-edit hash, matches `contentHashOf`).
- **`localCopy`**: assigns a fresh uuid (`!=` source), nulls `sourcePeerId` /
  `sourceOriginalId` / `sourceOriginalHash`, resets `visibility=PRIVATE` /
  `subscriptionMode=LOCAL`, and stamps a matching `content_hash`.

`contentHashOf` is now exercised directly (previously only transitively via
`CatalogImport.appendLegacyPrompts`).

## Decision on the optional part

The finding's "optionally extend `PromptListMutationsTest.copyOf` with uuid/contentHash
assertions" was **not** taken: the dedicated `PromptProvenanceTest.localCopy` test is a
stronger, more targeted guard for the same behavior (it asserts the full provenance
reset, not just uuid presence), and duplicating those assertions into the mutations test
would be redundant coverage of the same `PromptProvenance.localCopy` seam. `copyOf`'s own
test already verifies the position/name/type arithmetic that is its responsibility.

## Tests

`./gradlew :app:testDebugUnitTest --tests "net.devemperor.dictate.config.PromptProvenanceTest"`
→ BUILD SUCCESSFUL (5 tests green).

## Files modified

- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/app/src/test/java/net/devemperor/dictate/config/PromptProvenanceTest.kt` (new)

## Drift

none — test-only addition, no production files touched.
