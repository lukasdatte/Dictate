# Repair Wave W1 — cluster 1 (Block C config wire/canonicalisation)

**Timestamp:** 2026-07-20T00:40:00+02:00
**Agent:** repair-fix (W1-1)
**Findings:** enum-parity-aifunction-modelfunction, convention-C-1, convention-C-5

Fixed all three assigned findings; `:app:testDebugUnitTest` (config package +
`ProfileResolverCharacterizationTest`) green. Two of the three files were being
edited concurrently by another agent (an unrelated `AndroidAiConfig →
PrefCompletionParameters` refactor in `ConfigEntityMigration.kt` and a
`sourceRef → sourceRefOrNull` extraction in `ConfigEntityMapper.kt`); both had
settled into a consistent, compiling state by the time I finished, and my edits
are disjoint from theirs.

## Per finding

### enum-parity-aifunction-modelfunction (Important)
The fourth domain↔wire enum pair was hand-inlined byte-identically and had no
parity test. Now centralised like the other three:
- `ConfigWireMapping.kt`: added `fun AIFunction.toWire(): ModelFunction` (plus the
  symmetric `fun ModelFunction.toAIFunction(): AIFunction`, to mirror the existing
  three pairs and enable a round-trip assertion). KDoc header updated to list the
  fourth pair.
- `ConfigEntityMigration.kt:175` and `ConfigEntitySetup.kt:100`: the
  `if (function == AIFunction.TRANSCRIPTION) ModelFunction.TRANSCRIPTION else
  ModelFunction.COMPLETION` expressions replaced with `function.toWire()`. The
  now-dead `ModelFunction` import removed from both files.
- `ConfigWireEnumParityTest.kt`: added `AIFunction names match ModelFunction names`
  (set-equality of `.name`, the spec §4.8 parity assertion 3) and
  `AIFunction round-trips through ModelFunction`, matching the existing test shape.

### convention-C-1 (Important)
The §8.3 canonical-decimal helper existed byte-identically in two chunks. Extracted
one helper and routed both callers through it:
- `ConfigEntityMapper.kt`: new public `fun canonicalDecimal(value: Float): String`
  next to `encodeParams` (both are config-canonicalisation helpers), with a KDoc
  naming the shared hash contract. Added `import java.math.BigDecimal`.
- `ConfigEntityMigration.kt`: `canonicalParam` now calls
  `ConfigEntityMapper.canonicalDecimal`; the private `toCanonicalDecimal` and the
  now-dead `java.math.BigDecimal` import removed.
- `ParameterMapEditor.kt`: `canonicalFloat(value)` call replaced with
  `ConfigEntityMapper.canonicalDecimal(value)`; private `canonicalFloat` and dead
  `java.math.BigDecimal` import removed; `import ...config.ConfigEntityMapper` added.

### convention-C-5 (Nice-to-have)
- `ConfigEntitySetup.kt:60`: `apiKey.toByteArray(StandardCharsets.UTF_8)` →
  `apiKey.toByteArray(Charsets.UTF_8)`; `import java.nio.charset.StandardCharsets`
  removed (was the only use).
- `ConfigEntityMigration.kt`: removed the dead `import
  java.nio.charset.StandardCharsets` (file uses only bare `.toByteArray()`).

## Skipped
None.

## Files modified
- app/src/main/java/net/devemperor/dictate/config/ConfigWireMapping.kt
- app/src/main/java/net/devemperor/dictate/config/ConfigEntityMapper.kt
- app/src/main/java/net/devemperor/dictate/config/ConfigEntityMigration.kt
- app/src/main/java/net/devemperor/dictate/config/ConfigEntitySetup.kt
- app/src/main/java/net/devemperor/dictate/settings/ParameterMapEditor.kt
- app/src/test/java/net/devemperor/dictate/config/ConfigWireEnumParityTest.kt

## Drift (outside the literal suggested-fix lines)
- Added the reverse `ModelFunction.toAIFunction()` extension + a round-trip test
  beyond the finding's minimum (set-equality only) — keeps the fourth pair
  structurally identical to the existing three and gives the round-trip safety net.
- Removed three dead imports (`ModelFunction` ×2, `BigDecimal` ×2, `StandardCharsets`
  ×1) that my in-scope edits orphaned — direct consequences of the fixes, not new
  scope.
- No edits to the concurrent agents' areas (`PrefCompletionParameters` call site,
  `sourceRefOrNull` extraction) — left untouched.
