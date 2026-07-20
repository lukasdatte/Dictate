# Block C — Audit: plan-and-api

**Topic:** plan-and-api · **Block:** C · **Timestamp:** 2026-07-20T00:40:00+02:00
**Scope base:** `c46cfe8..HEAD` (file-scoped to the block files)
**Grounding:** `knowledge-typescript` (discriminated-union / versioned-envelope concepts —
Kotlin project, so only the concepts transfer), `knowledge-reference` (`versioned-envelope`),
spec `research/entitaetenmodell-android.md` §2/§4/§5/§7/§8/§9/§10/§12/§13.

## Verdict

Block C is a faithful, high-quality implementation of the spec. The `:shared` config layer
(C1), the Room v11→v12 persistence + Prefs→entity migration + `ProfileResolver` (C2), and the
settings/import/export rebuild + read-path flip (C3) all match their spec sections; the
documented deviations are defensible (D4). No stubs, no placeholder returns, no
throw-not-implemented in the block scope. Cross-chunk API surfaces (CatalogCodec ↔
CatalogImport/Export, ConfigRepository ↔ migration/setup/import, ProfileResolver ↔ AiConfig
port, DAO surface ↔ all callers) are consistent, and the whole `:app` unit suite plus the
`:shared` config suites are green.

**One finding:** a spec-mandated enum-parity test is missing (`ModelFunction`↔`AIFunction`).

## Findings

### plan-and-api-C-1 — `ModelFunction`↔`AIFunction` parity test missing (Important)

**Files:** `app/src/test/java/net/devemperor/dictate/config/ConfigWireEnumParityTest.kt`
(spec `research/entitaetenmodell-android.md` §4.8, §2.1)

Spec §4.8 enumerates **four** required parity assertions between the `:shared` wire enums and
the `:shared-ai`/`:app` domain originals:

1. `ProviderType` ⇔ `AIProvider` — present (`ConfigWireEnumParityTest`, 2 tests)
2. `AmbiguityModeValue` ⇔ `AmbiguityMode.persistKey` — present
3. **`ModelFunction.entries.map{it.name}` == `AIFunction.entries.map{it.name}` — ABSENT**
4. `PromptSelectionMode` ⇔ `PromptMode` — present

`ConfigWireEnumParityTest` implements 1/2/4 but not 3. Grep confirms no `ModelFunction`
parity/round-trip assertion anywhere in `app/src/test` or `shared/src/test`, and
`ConfigWireMapping` has no `ModelFunction`↔`AIFunction` mapper. §2.1 acceptance says
"Enum-Paritäts-Tests grün", and this was the exact deliverable C1 explicitly delegated to C2
(issue C1-1, marker `blocks-following`) — C2 closed 3 of the 4.

**Failure scenario:** a future change adds a third `AIFunction` value (or renames one) without
mirroring it in `:shared` `ModelFunction`. The other three mirrors are guarded by red tests;
this pair is not, so the drift ships silently. Runtime blast radius today is low — the
migration/setup construct `ModelFunction` via an explicit `if (function == TRANSCRIPTION) …
else COMPLETION` rather than a `name()`-based conversion, so a mismatch would mis-map to
COMPLETION rather than throw — but the wire-format invariant (Companion's own `ModelFunction`
mirror in Block D/E depends on the same value set) is left without the safety net the spec
mandates and the other three enums have.

**Suggested fix (≈4 lines):** add to `ConfigWireEnumParityTest`:
```kotlin
@Test fun `AIFunction names match ModelFunction names`() {
    assertEquals(
        AIFunction.entries.map { it.name }.toSet(),
        ModelFunction.entries.map { it.name }.toSet(),
    )
}
```

## Coverage note

**Files audited (read in full):**
- C1 `:shared/config`: `CatalogCodec.kt`, `CanonicalJson.kt`, `ContentHash.kt`, `Entities.kt`,
  `ConfigEnums.kt`, `ConfigValidations.kt` — all match §4/§5. Envelope-strip is top-object-only,
  hash mask (`and 0xFF`) documented and guarded, GATEWAY rejection active (F31), discriminator
  `"kind"` single-sourced between `CanonicalJson` and `CatalogCodec`, Malformed-vs-Invalid split
  correct.
- C2: `ConfigWireMapping.kt`, `ProfileResolver.kt` (matches `AiConfig` port + AndroidAiConfig
  baseline byte-for-byte by construction), `ConfigEntityMapper.kt`, `ConfigRepository.kt`
  (recompute-on-write choke point, §5.3), `ConfigEntityMigration.kt` (order/backup/idempotency/
  deterministic ids per §8), `MigrationTo12.kt` (DDL matches §7.2/§7.3 verbatim incl. all
  Double-Enum CHECKs), `dao/ConfigDaos.kt` (surface complete for every consumer).
- C3: `CatalogImport.kt` (§10.4 dispatcher + §5.3 recompute check), `CatalogExport.kt` (§10.5,
  credential-free per D5), `ConfigEntitySetup.kt` (onboarding, deterministic ids shared with
  migration), `ProfilePromptConfig.kt` + `AndroidAiFactory.kt` (the atomic read-path flip).
- Tests: `ConfigWireEnumParityTest`, `ConfigEntityMapperTest`, and spot-checks of the `:shared`
  suites for enum coverage.

**Not deep-audited (out of this topic / covered elsewhere):** the Room entity column layout
(`ConfigRoomEntities.kt`), `PrefsBackup.kt`, `PromptHashing.kt`, `PromptProvenance.kt`,
`ProfileListMutations.kt`, and the settings-activity UI wiring — glanced for stubs (none) and
API-consumer consistency (consistent); logic/convention lenses own their depth.

## Out-of-scope observations (for the consolidator)

- **C3-1 (already open/delegated):** `NoLegacyKeyReadTest` remains `@Ignore`d because
  `AndroidAiConfig` still references the 11 secret-pref constants — no longer on the live path
  (factory flipped to `ProfileResolver`), but still used as the migration's completion-parameter
  mirror + the characterization-test baseline. This leaves the §2.6/AK6 grep-freedom criterion
  formally unverified. It is a Block-B-coupled orchestration decision, correctly filed as
  delegated in `C3-impl.md`; re-surfaced here only so it is not lost — not a new finding.
- **No convention/logic findings** surfaced during the plan-and-api pass; `ConfigRepository.
  upsertProfile` relies on `@Insert(REPLACE)` cascade-deleting `profile_prompts` then re-inserting
  — correct within the transaction, subtle but not a defect (logic topic may wish to confirm).
