# Block C — Convention Audit

**Topic:** convention (same operation done differently across chunks: naming, error handling,
enum/charset handling, file layout, Double-Enum application) · **Block:** C · **Agent:** block-audit
· **Timestamp:** 2026-07-20T00:40:00+02:00

**Grounding:** project `CLAUDE.md` (New code = Kotlin; Preferences via `DictatePrefs`; AI via
`AIOrchestrator`→`RunnerFactory`; Double-Enum rule + `docs/DATABASE-PATTERNS.md`), `knowledge-reference`
(versioned-envelope pattern — the `CatalogFileV3` v3 door matches it well).

## Scope audited

`git diff c46cfe8..HEAD` over the block file scope. Read at HEAD in full:

- `:shared/config` (C1): `CanonicalJson`, `CatalogCodec`, `ConfigValidations`, `ContentHash`,
  `Entities`, `ConfigEnums`.
- `:app/config` (C2/C3): `ConfigRepository`, `ConfigEntityMapper`, `ConfigWireMapping`,
  `ConfigEntityMigration`, `ConfigEntitySetup`, `ConfigSecrets`, `PrefsBackup`, `PromptHashing`,
  `PromptProvenance`, `CatalogImport`, `CatalogExport`, `ActiveProfile`, `ProfileListMutations`,
  `entity/ConfigRoomEntities`, `dao/ConfigDaos`, `database/migration/MigrationTo12`.
- `:app/ai/adapter`: `ProfileResolver`, `ProfilePromptConfig`, `AndroidAiFactory`.
- `:app/settings` (C3): `APISettingsActivity.kt`, `ProviderEditActivity`, `ProfileEditActivity`,
  `ParameterMapEditor`; `SystemPromptsActivity.java` diff.

**Not audited in depth:** test sources (owned by the `test` topic), Room schema `12.json`
(mechanical KSP export), `DictatePrefs.kt`/`DictateApplication.java` deltas (single-line additions,
consistent with existing style).

## Overall assessment

Convention discipline across the block is **high**. The `runCatching { Enum.valueOf(x) }.getOrDefault(…)`
string→enum idiom, the deterministic-id derivation (`UUID.nameUUIDFromBytes("<ns>:<slot>")`), the
`ConfigRepository` single-write-path recompute, the Double-Enum accessors on the Room rows, the DAO
naming (`byId`/`getAll`/`count`/`deleteById`), and the two settings-editor Activity skeletons
(edge-to-edge insets, `supportActionBar`, `onOptionsItemSelected` home) are all internally consistent.
The findings below are drift points where **one operation is expressed two different ways** across the
three chunks — none are correctness bugs; the two Important ones carry a real maintainability / latent
hash-drift cost.

## Findings

### convention-C-1 (Important) — Canonical-decimal helper duplicated across C2 and C3

**Files:** `app/.../config/ConfigEntityMigration.kt:242-243` (`toCanonicalDecimal`),
`app/.../settings/ParameterMapEditor.kt:166-167` (`canonicalFloat`).

Both are byte-identical: `BigDecimal(value.toString()).stripTrailingZeros().toPlainString()`, and both
declare themselves to be *the* §8.3 canonical parameter-value form (ParameterMapEditor's comment even
says "same canonical form the migration writes (§8.3)"). This is the string that feeds the
`contentHash` (via `parameterDefaults`/`parameterOverrides`), so the two copies are a **shared hash
contract**: if a future edit changes one (e.g. to force a locale, cap precision, or handle exponents)
and misses the other, the migration and the editor will write **different bytes for the same numeric
value → divergent contentHash → broken Block-E dedup**. A convention/DRY hazard with a concrete
failure mode, not cosmetic.

**Expected:** one canonical-decimal function shared by both write seams (e.g. a
`ConfigParams.canonicalDecimal(Float)` in `config/`, next to `ConfigEntityMapper.encodeParams` which
already owns the map's canonical form).

**Suggested fix:** extract a single `canonicalDecimal(Float): String` helper in `config/` and call it
from both `ConfigEntityMigration` and `ParameterMapEditor`.

### convention-C-2 (Important) — AIFunction→ModelFunction conversion inlined, breaking the ConfigWireMapping convention

**Files:** `app/.../config/ConfigEntityMigration.kt:175`, `app/.../config/ConfigEntitySetup.kt:100`
(both: `function = if (function == AIFunction.TRANSCRIPTION) ModelFunction.TRANSCRIPTION else ModelFunction.COMPLETION`).

`ConfigWireMapping` is the declared "value-equality bridge between the domain enums in `:shared-ai`
and the wire enums in `:shared`" and centralizes the sibling conversions `AIProvider.toWire()`,
`AmbiguityMode.toWire()`, `PromptMode.toWire()` (each pinned by `ConfigWireEnumParityTest`). The
fourth pair of this exact family — `AIFunction`↔`ModelFunction` — is instead **hand-inlined
identically in two different chunks**, bypassing the one place the convention says these conversions
live. Same operation, done two ways, and the ad-hoc form is not covered by the parity mechanism that
guards the others.

**Expected:** a `AIFunction.toWire(): ModelFunction` (+ inverse if needed) extension in
`ConfigWireMapping`, called from both sites — matching how every other domain↔wire enum is handled.

**Suggested fix:** add `fun AIFunction.toWire(): ModelFunction` to `ConfigWireMapping` and replace both
inline `if` expressions with `function.toWire()`; extend `ConfigWireEnumParityTest` to cover it.

### convention-C-3 (Nice-to-have) — ProfileRoomEntity diverges from the Double-Enum convention its own file header states

**File:** `app/.../config/entity/ConfigRoomEntities.kt:113-139` (`ProfileRoomEntity`).

The file header states: *"All finite-set columns store the enum `name()` as `String` and expose a
`xxxEnum` convenience accessor with a `getOrDefault` fallback — the Double-Enum rule."* The three other
entities honour this (`ProviderConfigRoomEntity`, `ApiCredentialRoomEntity`, `ModelRefRoomEntity`
each use `Enum.LOCAL.name` etc. defaults + `providerTypeEnum`/`kindEnum`/`functionEnum` accessors).
`ProfileRoomEntity` does **not**: its finite-set columns use hardcoded string literals
(`= "PREDEFINED"`, `= "ALWAYS_INSERT"` — lines 119/121/123) instead of
`PromptSelectionMode.PREDEFINED.name` / `AmbiguityModeValue.ALWAYS_INSERT.name`, and it exposes **no**
`stylePromptModeEnum`/`systemPromptModeEnum`/`ambiguityModeEnum` accessor — the string→enum parse is
pushed into `ConfigEntityMapper`'s private `promptSelectionMode()`/`ambiguityModeValue()` helpers
instead. The SQL half (CHECK constraints in `MigrationTo12`) is present, so this is not a data-safety
bug; it is a convention inconsistency that (a) makes the header's "all columns" claim false and (b)
weakens refactor-safety (an enum rename won't touch the string-literal defaults).

**Suggested fix:** replace the string-literal defaults with `Enum.name` and add the three `xxxEnum`
accessors on `ProfileRoomEntity`, moving the fallback parse out of `ConfigEntityMapper` to match the
sibling rows.

### convention-C-4 (Nice-to-have) — SourceRef-from-nullable-triple reconstruction duplicated

**Files:** `app/.../config/ConfigEntityMapper.kt:44-49` (private `sourceRef(...)` helper, used by all
four `toDto`), vs `app/.../config/CatalogExport.kt:71-76` (identical null-guard inlined in
`toPromptDto`).

The "three nullable provenance columns → `SourceRef?`" operation has a named helper in
`ConfigEntityMapper` but is re-implemented inline in `CatalogExport`. Same operation, two forms.
`CatalogExport` reconstructs from a `PromptEntity` (legacy prompt row) rather than a config-Room row,
so it can't reuse the mapper's private helper directly — but that argues for lifting the guard into a
small shared/internal helper both can call.

**Suggested fix:** make the `(peerId, originalId, originalHash) → SourceRef?` guard an internal shared
function and call it from both `ConfigEntityMapper` and `CatalogExport`.

### convention-C-5 (Nice-to-have) — Charset spelled two ways + one dead import

**Files:** `app/.../config/ConfigEntitySetup.kt:60` (`apiKey.toByteArray(StandardCharsets.UTF_8)`) vs
`app/.../settings/ProviderEditActivity.kt:162` & `APISettingsActivity.kt:342`
(`.toByteArray(Charsets.UTF_8)` — the Kotlin idiom); `app/.../config/ConfigEntityMigration.kt:28`
imports `java.nio.charset.StandardCharsets` but the file uses only bare `.toByteArray()` — the import
is unused (dead).

Same operation (UTF-8 key/text bytes) written two ways across the block; in new Kotlin code the
`Charsets.UTF_8` form is the established idiom. Trivial, but exactly the kind of drift this topic
tracks.

**Suggested fix:** use `Charsets.UTF_8` in `ConfigEntitySetup` and drop the unused `StandardCharsets`
import from `ConfigEntityMigration`.

### convention-C-6 (Nice-to-have) — Provider type / ambiguity mode rendered as raw enum `.name` in some UIs, localized/displayName in others

**Files:** `app/.../settings/APISettingsActivity.kt:137` (`providerTypeName(type) = type.name` shown in
the provider-list subtitle) and `app/.../settings/ProfileEditActivity.kt:178`
(`ambiguityModes.map { it.name }` as spinner labels) vs `ProviderEditActivity.kt:105`
(`providerChoices.map { it.displayName }`) and `ProfileEditActivity.kt:157-161` (prompt-mode spinner
uses localized `getString(R.string.…)`).

The same user-facing concept is presented inconsistently: the provider *editor* uses `displayName`
while the settings *hub* shows the raw wire-enum token (`OPENAI`, `CUSTOM`); within the profile editor
the prompt-mode spinner is localized but the ambiguity spinner shows raw tokens (`ALWAYS_INSERT`,
`ALWAYS_REVIEW`). This straddles convention/UX (partly a `logic`/i18n observation) but the divergence
is a cross-chunk presentation inconsistency.

**Suggested fix:** render `ProviderType` via `AIProvider.displayName` (or a shared label mapper) in the
hub subtitle, and give the ambiguity spinner localized labels like the prompt-mode spinner.

## Out-of-scope observations (for the consolidator)

- **[logic]** `ConfigEntityMigration.fingerprint` (line 246-248) re-implements the SHA-256→lowercase-hex
  rendering that `:shared/ContentHash.contentHash` already contains (`"%02x".format(it.toInt() and 0xFF)`).
  It is a *different* function (16-char key fingerprint vs full content hash) so not a strict convention
  duplication, but the hex-render inner loop is copied — a small hex-render helper could be shared.
- **[test]** `ConfigWireEnumParityTest` covers `ProviderType`/`AmbiguityModeValue`/`PromptSelectionMode`
  but (per finding C-2) there is no parity test binding `AIFunction`↔`ModelFunction`; if C-2 is fixed by
  centralizing the conversion, add the parity case.

## Coverage note

- **Files audited:** all 23 main-source files in the block scope (listed under Scope) at HEAD, plus the
  `SystemPromptsActivity.java` diff.
- **Files skipped:** test sources (owned by the `test` topic); `12.json` (mechanical export);
  layout/menu/`strings.xml` XML (no cross-chunk code-convention surface beyond id naming, which is
  consistent). No file was skipped for lack of time.
