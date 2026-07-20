# Repair W1-3 — finding convention-C-4 (G4)

**Date:** 2026-07-20T00:40:00+02:00
**Agent-ID:** repair-W1-3 (Block C repair wave)
**Cluster:** `convention-C-4` (Nice-to-have) — `SourceRef`-from-nullable-triple reconstruction duplicated

## What I did

Lifted the `(peerId?, originalId?, originalHash?) -> SourceRef?` all-or-nothing null-guard
into a single shared definition and routed both call sites through it.

- **New file** `app/src/main/java/net/devemperor/dictate/config/SourceRefMapping.kt` —
  top-level `internal fun sourceRefOrNull(peerId, originalId, originalHash): SourceRef?`
  with a doc comment explaining the all-or-nothing contract and why both callers exist
  (one works over the typed `:shared`-DTO Room rows, the other over the legacy
  `PromptEntity` whose columns the mapper's private helper could not reach — the original
  reason the guard was duplicated).
- **`ConfigEntityMapper.kt`** — removed the `private fun sourceRef(...)` helper (was lines
  44-49); its four `toDto` call sites now call the shared `sourceRefOrNull(...)`. Dropped
  the now-unused `import ...shared.config.SourceRef`.
- **`CatalogExport.kt`** — replaced the inline `if (peerId != null && ...) SourceRef(...) else null`
  block in `toPromptDto` (was lines 71-76) with a call to `sourceRefOrNull(...)`. Dropped
  the now-unused `import ...shared.config.SourceRef`.

Behaviour is identical (same guard, same argument order); this is a pure DRY extraction.

## Finding status

| ID | Status | Notes |
|---|---|---|
| convention-C-4 (G4) | fixed-inline | shared `sourceRefOrNull` in `config/`, both callers routed through it |

## Tests

Full `:app:testDebugUnitTest` compiled and ran (2497 tests) — the only non-green results were
two pre-existing sandbox class-loading failures unrelated to config
(`AIOrchestratorConverseTest`, `PromptListMutationsTest`: `ClassNotFoundException` /
`NoClassDefFoundError` from `SandboxClassLoader`).

A subsequent targeted recompile then failed with a **foreign** error:
`settings/ParameterMapEditor.kt:167 Unresolved reference 'BigDecimal'`. This file is **not in
my cluster** — it belongs to the concurrently-running `canonicalDecimal`-extraction finding
(validated-findings.md lines 72-83, `ParameterMapEditor.kt:166-167` + `ConfigEntityMigration.kt`),
handled by another repair agent in this same wave that is mid-edit. None of my three files
produce a compile error. I did not touch `ParameterMapEditor.kt` (out of scope).

## Collision flagged for orchestrator (drift / coordination)

- `ConfigEntityMapper.kt` is being edited by **two** findings in this wave: mine (removed the
  `sourceRef` helper, calls `sourceRefOrNull`) and the `canonicalDecimal` finding (another
  agent added `fun canonicalDecimal(Float)` + `import java.math.BigDecimal` to the same file).
  Both edits are present on disk and are compatible (disjoint regions), but the two findings'
  file sets are **not disjoint** on this file. When the commit stages `ConfigEntityMapper.kt`
  it will carry both changes. The commit-agent/serialization must account for this so the
  canonicalDecimal change is not double-committed or lost.
- The wave build cannot go fully green until the concurrent `canonicalDecimal` agent finishes
  its `ParameterMapEditor.kt` edit (adds the `BigDecimal` import or routes through the new
  helper). This is expected in a parallel repair wave and is outside my cluster.

## Files modified

- `app/src/main/java/net/devemperor/dictate/config/SourceRefMapping.kt` (new)
- `app/src/main/java/net/devemperor/dictate/config/ConfigEntityMapper.kt`
- `app/src/main/java/net/devemperor/dictate/config/CatalogExport.kt`

## Drift

`ConfigEntityMapper.kt` on disk also contains a concurrent agent's `canonicalDecimal`/`BigDecimal`
addition (a different finding). I did not author it and left it intact; flagged above so the
commit path handles the shared-file overlap. No other out-of-scope edits.
