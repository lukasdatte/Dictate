# Desktop AiConfig / Credential Resolution — Repair Research

**Date:** 2026-07-20T00:40:00+02:00
**Triggered by:** AUDIT-D finding `plan-and-api-D-2` [Important, CONFIRMED] —
the desktop pipeline resolves only `ambiguityMode` from the active profile;
provider/model/API-key and the remaining post-processing config stay on the
transitional `CompanionAiConfig`/`DEFAULT`, so a real desktop dictation cannot
transcribe or post-process in production. Block D goal ("Full dictation on the
PC", §2.3) is unreachable.
**Agent-ID:** repair-research (desktop-aiconfig-credential-resolution)

## Summary (plain language)

The Companion desktop-dictation pipeline (Block D) runs against **two** config
surfaces:

1. **`AiConfig`** — provider, model, base URL, completion params, **API key**.
   Currently `CompanionAiConfig`, a hard-coded OpenAI default whose
   `apiKey()` returns `""`. → a real provider call always fails.
2. **`DictationProfile`** — the "how to post-process" settings (ambiguity mode,
   language, auto-format, instructions, style prompt). Resolved by
   `ConfigProfileSource`, which today copies **only** `ambiguityMode` off the
   active profile and leaves everything else at a hard-coded `DEFAULT`.

Block C already landed the full data (`CompanionConfigRepository` exposes
`ProfileEntity` → `ModelRefEntity` → `ProviderConfigEntity.credentialRef`), and
Block B already landed the Companion `SecretStore`. The **Android** side already
has the exact resolver this desktop side is missing:
`app/.../ai/adapter/ProfileResolver.kt` (spec §9). The fix is to build the
Companion twin of that resolver, wire a `SecretStore` into
`CompanionContainer.production()`, and extend `ConfigProfileSource` to resolve
the rest of `DictationProfile`. **No new cross-block dependency is required —
all inputs have landed.**

## Ownership verdict (answers the "orphaned responsibility" question)

The finding is right that no *downstream* chunk (E1/E2/E3 = peer catalog; B =
SecretStore storage; F = docs/ADR/E2E) picks this up. But it is **not
genuinely orphaned — it is D3 under-delivery**, and the fix belongs to the
**Block D audit-repair scope (D3)**:

- `desktop-host.md §5.1 NOTE` promises: *"only D3 gates on the C1 profile types
  (`ActiveProfileSource` then delivers a real profile)"* — a **real**
  profile, i.e. full resolution, not just the ambiguity axis.
- `desktop-host.md §15 Gap 5` names the **D3-Agent** as Owner ("coordinates with
  the C strand", `depends_on: D3→{D2,C1}`).
- Plan §2.3 (Block D acceptance) requires "transcription+post-processing via
  **configured profile**", which the current slice does not satisfy.

D3 resolved the review axis (`ambiguityMode`, which its Review §8 flow needs)
and stopped, documenting the rest as still-Gap-5. Closing it is a D3 repair, not
a new block. The "transitional fixed `SYSTEM_PROMPT_CONVERSATION`" the finding
mentions is the same root (unresolved profile→config), not a separate gap.

## Sources

1. **Live Companion code (implemented D1b/D3):**
   - `companion/.../ai/CompanionAiConfig.kt` — the transitional `AiConfig`,
     `apiKey()=""` (:31).
   - `companion/.../pipeline/ConfigProfileSource.kt` — resolves only
     `ambiguityMode` (:26-29); docstring §Scope(D3) admits the rest stays
     transitional.
   - `companion/.../pipeline/ActiveProfileSource.kt` — `DictationProfile` data
     class + `TransitionalProfileSource`.
   - `companion/.../pipeline/DictationEffects.kt` — consumes ALL
     `DictationProfile` fields (`language` :95/:141, `autoFormatEnabled` :107,
     `instructions` :108, `stylePrompt` :141, `ambiguityMode` :109-110) and the
     `AiConfig` via `AIOrchestrator`; system prompt hard-coded
     `PromptTemplates.SYSTEM_PROMPT_CONVERSATION` (:171).
   - `companion/.../CompanionContainer.kt` `production()` (:131-176) — builds
     `CompanionAiConfig()` (:145), never a `SecretStore`.
   - `companion/.../data/CompanionConfigRepository.kt` — full read API:
     `profile(id)`, `modelRef(id)`, `providerConfig(id)` returning the
     `:shared.config` DTOs incl. `credentialRef`.
   - `companion/.../secrets/SecretStoreModule.kt` — `detect(configDir)`
     already returns a working `SecretStore` (DPAPI on Windows, file-AES-GCM
     fallback elsewhere); B1 landed.
2. **Android reference implementation (the pattern to mirror):**
   - `app/.../ai/adapter/ProfileResolver.kt` — the entity-backed `AiConfig`
     (spec §9.2 resolution, §9.3 fallbacks), byte-parity-tested by
     `ProfileResolverCharacterizationTest`.
   - `app/.../config/ConfigWireMapping.kt` — `ProviderType.toAIProvider()` etc.
     wire↔domain mapper (+ `ConfigWireEnumParityTest`), placed where both
     `:shared` and `:shared-ai` are visible (D5.a doctrine).
   - `app/.../config/ConfigSecrets.kt` — the `SecretRef("credential", <id>)`
     addressing convention (namespace constant).
3. **Specs / decisions:**
   - `research/entitaetenmodell-android.md §9` (ProfileResolver contract,
     resolution steps, fallback semantics, characterization test).
   - `research/desktop-host.md §5.1 NOTE`, §9.1, §15 Gap 5.
   - Plan §3 D5.a (wire enums in `:shared`; mapper+parity where both modules
     visible), §2.3 (Block D acceptance).
   - `shared-ai/.../port/AiConfig.kt` (the port contract + non-ASCII-strip
     parity note); `shared-ai/.../secrets/SecretStore.kt` + `SecretRef`.

## Findings

### F1 — The finding is CONFIRMED and correctly scoped

`ConfigProfileSource.current()` returns `DEFAULT.copy(ambiguityMode = …)`. Every
other `DictationProfile` field (`language`, `autoFormatEnabled`, `instructions`,
`stylePrompt`) stays at the empty `DEFAULT`, and `AiConfig` is a *separate*
object (`CompanionAiConfig`) that is not profile-derived at all and hard-codes
an empty key. `DictationEffects` faithfully consumes all of these — so the
under-resolution silently degrades every take to "plain OpenAI transcription,
verbatim insert, no key". A real transcription/completion throws (empty key).

### F2 — All inputs to a full resolver have already landed

The two chunks a real resolver needs are done:
- **C1/D3** — `CompanionConfigRepository` returns `ProfileEntity`
  (`transcriptionModelRef`, `completionModelRef`, `orderedPrompts`,
  `stylePromptMode`/`stylePromptCustomText`, `systemPromptMode`/…,
  `ambiguityMode`, `parameterOverrides`), `ModelRefEntity` (`providerRef`,
  `modelId`, `function`, `parameterDefaults`), `ProviderConfigEntity`
  (`providerType`, `kind`, `baseUrl`, `credentialRef`).
- **B1** — `SecretStoreModule.detect(configDir)` yields a `SecretStore`;
  `SecretRef`/`ConfigSecrets.CREDENTIAL_NAMESPACE = "credential"` fix the
  handle format.

So the resolver is a **pure mirror** of `ProfileResolver` reading SQLDelight
rows instead of Room rows. No blocking dependency; implementable inside a D3
repair now.

### F3 — Two resolution surfaces, split by concern (do NOT merge them)

Keep the split the pipeline already has:
- **`AiConfig`** (provider/model/key/baseUrl/params) → a **new**
  `ProfileBackedAiConfig : AiConfig`. This is the correctness-critical half
  (empty key). Mirror `ProfileResolver` 1:1.
- **`DictationProfile`** (ambiguity/language/autoFormat/instructions/style) →
  **extend `ConfigProfileSource`**. `AiConfig` deliberately has no
  instructions/style method (Android builds those in the pipeline, not the
  port) — so this half stays in `ConfigProfileSource`, consistent with the
  Android layering.

### F4 — Companion needs its own wire↔domain enum mapper (D5.a)

`ProviderType` (`:shared`) → `AIProvider` (`:shared-ai`) mapping lives in `:app`
today (`ConfigWireMapping`). The Companion sees **both** modules too, so per
D5.a it must get its own small mapper + a parity test (mirror of
`ConfigWireEnumParityTest`). `ConfigProfileSource` already open-codes the
`AmbiguityModeValue → AmbiguityMode` map inline — fold that into the same
Companion mapper for DRY.

### F5 — Caveat: a resolved key still needs to be *stored* (out of this repair's core, flag it)

The resolver reads `SecretStore.get(SecretRef("credential", credentialRef))`.
**Nothing landed on the Companion writes a credential into the store yet** —
`ConfigViewModel` (D3 management UI) writes only provider/model/profile
*metadata*, never `SecretStore.put`. Desktop credentials will arrive via
**Block E** peer credential delivery ("the credential lands on the receiver
exclusively in the SecretStore", §2.7) or a future credential-entry field. So:

- After this repair, a *profiled fake-runner* take (headless E2E, §12) resolves
  provider/model correctly; a **real** provider call still needs a credential
  present in the store.
- For the F1 manual real-provider smoke (TC-W5) the user must be able to enter a
  key. There is currently **no** Companion UI for that. Recommend flagging this
  as a small follow-on (a credential field in `ConfigViewModel`'s provider
  editor that calls `SecretStore.put` + writes `credentialRef`), owner E3/D3
  UI, OR accept E2 peer-delivery as the only v1 population path. This does **not
  block** the resolver work — the resolver is correct regardless of how the key
  arrives, and its `§9.3` fallback ("credential absent → key `""`, no crash")
  handles the empty-store case gracefully.

## Implementation Hints (concrete)

### 1. `ProfileBackedAiConfig` — new file, mirror of Android `ProfileResolver`

`companion/src/main/kotlin/net/devemperor/dictate/companion/ai/ProfileBackedAiConfig.kt`

```kotlin
class ProfileBackedAiConfig(
    private val config: CompanionConfigRepository,
    private val secretStore: SecretStore,
    private val activeProfileId: () -> String?,   // = { settings.activeProfileId }
) : AiConfig {

    private data class Resolved(
        val providerConfig: ProviderConfigEntity?,
        val modelRef: ModelRefEntity?,
    )

    override fun provider(function: AIFunction): AIProvider =
        resolve(function).providerConfig?.providerType?.toAIProvider() ?: AIProvider.OPENAI

    override fun modelName(function: AIFunction): String =
        resolve(function).modelRef?.modelId ?: ""

    override fun apiKey(function: AIFunction): String {
        val credentialRef = resolve(function).providerConfig?.credentialRef ?: return ""
        val bytes = runCatching {
            secretStore.get(SecretRef(CredentialSecrets.CREDENTIAL_NAMESPACE, credentialRef))
        }.getOrNull() ?: return ""
        return String(bytes, Charsets.UTF_8).replace(NON_ASCII, "")   // AiConfig contract: strip non-ASCII
    }

    override fun baseUrl(function: AIFunction): String {
        val pc = resolve(function).providerConfig ?: return AIProvider.OPENAI.defaultBaseUrl
        return pc.baseUrl ?: pc.providerType.toAIProvider().defaultBaseUrl
    }

    override fun completionParameters(provider: AIProvider, model: String): Map<String, Any> {
        // ModelRef defaults ⊕ Profile overrides (profile wins), iterate ParameterRegistry
        // defs with the SAME sentinel filters as app ProfileResolver (temp<0, tokens<=0,
        // empty reasoning_effort dropped). Copy that method verbatim.
    }

    override fun elevenLabsKeyterms(): List<String>? { /* mirror app ProfileResolver §9 */ }

    private fun resolve(function: AIFunction): Resolved {
        val profileId = activeProfileId() ?: return Resolved(null, null)
        val profile = config.profile(profileId) ?: return Resolved(null, null)
        val modelRefId = when (function) {
            AIFunction.TRANSCRIPTION -> profile.transcriptionModelRef
            AIFunction.COMPLETION -> profile.completionModelRef
        } ?: return Resolved(null, null)
        val modelRef = config.modelRef(modelRefId) ?: return Resolved(null, null)
        val providerConfig = config.providerConfig(modelRef.providerRef)
        return Resolved(providerConfig, modelRef)
    }

    private companion object { val NON_ASCII = Regex("[^ -~]") }
}
```

- **Reuse, don't reinvent:** copy the `completionParameters` and
  `elevenLabsKeyterms` bodies from `app/.../ai/adapter/ProfileResolver.kt`
  verbatim (`ParameterRegistry`, `ElevenLabsKeytermsParser` are in `:shared-ai`,
  visible to `:companion`). Params decode: `CompanionConfigRepository` already
  returns `parameterDefaults`/`parameterOverrides` as `Map<String,String>`, so
  use those directly (no `ConfigEntityMapper.decodeParams` needed on this side).
- **Fallback semantics = §9.3 verbatim:** no profile / no modelRef / absent
  credential ⇒ empty config, never crash. This is what preserves the "API key
  missing" UX and keeps the fake-runner E2E green.

### 2. Companion wire mapper + parity test (D5.a)

`companion/.../ai/CompanionConfigWireMapping.kt` (or under `pipeline/`):
`ProviderType.toAIProvider()`, `AmbiguityModeValue.toAmbiguityMode()`,
`PromptSelectionMode.toPromptMode()` — by `enum name`, mirroring
`app/.../config/ConfigWireMapping.kt`. Add `CompanionConfigWireEnumParityTest`
mirroring `ConfigWireEnumParityTest` (assert name/value equality both
directions). Fold `ConfigProfileSource`'s inline `toDomain()` into this mapper.
Also add a `CredentialSecrets` object (or reuse a shared constant) fixing
`CREDENTIAL_NAMESPACE = "credential"` — it MUST match `:app`'s
`ConfigSecrets.CREDENTIAL_NAMESPACE` so peer-delivered / cross-platform
credentials resolve identically. **Best home:** promote the namespace constant
into `:shared-ai` (`ai.secrets`) so both platforms share one source of truth
rather than two copies — the value is protocol-relevant (peer credential
delivery, E1/E2). If that widens scope, a Companion-local constant with a test
asserting `== "credential"` is the minimum.

### 3. Wire into `CompanionContainer.production()`

Replace the `CompanionAiConfig()` block (:145) with:

```kotlin
val secretStore = SecretStoreModule.detect(AppPaths.configDir())   // confirm the configDir accessor name
val aiConfig: AiConfig = ProfileBackedAiConfig(
    config = configRepository,
    secretStore = secretStore,
    activeProfileId = { settings.activeProfileId },
)
```

Keep the `NoopUsageSink` as-is. `CompanionAiConfig` stays only as the
test-baseline (like Android kept `AndroidAiConfig` in test sources) — or delete
it if no test references it beyond `DesktopDictationPipelineTest` (which should
switch to a fake `AiConfig` or the new resolver against an in-memory DB). Check
`AppPaths` for the config-dir accessor (`SecretStoreModule.detect` wants the
same dir the stores already use).

### 4. Extend `ConfigProfileSource.current()` for the rest of `DictationProfile`

Resolve from the same `ProfileEntity`:
- `ambiguityMode` — already done (via the shared mapper now).
- `stylePrompt` — from `stylePromptMode`/`stylePromptCustomText`
  (`PREDEFINED` → `PromptTemplates`-derived text; `CUSTOM` → custom text;
  `NONE` → null). Follow the same style-prompt selection Android uses (spec
  §9.2 step 8).
- `instructions` — from `profile.orderedPrompts`: resolve each `promptRef` via
  `config.prompt(id)`, map to `TurnInstruction` honouring `autoApply`. Mirror
  how the pipeline builds `PostProcessingInputs` on Android
  (`ConversationTurnBuilder`).
- `autoFormatEnabled` / `language` — **no explicit field** exists on
  `ProfileEntity`. On Android these are prefs, not profile columns. Recommend:
  leave `language` sourced from `CompanionSettings` (or null) and
  `autoFormatEnabled` per the existing transitional default, and document this
  as an intentional v1 boundary (a follow-up owns adding these to the profile
  schema). Do **not** invent schema here — that would be a C1/entity change
  outside a D-repair.

If the instructions/style resolution proves larger than the core key fix, split
the repair: **(a) `ProfileBackedAiConfig` + container wiring + mapper** is the
must-land correctness fix; **(b)** the `ConfigProfileSource` instruction/style
extension can be a second commit within the same D3 repair.

### 5. Tests (test-first)

- `ProfileBackedAiConfigTest` — matrix over provider/model/credential presence
  against an in-memory SQLDelight DB + a fake `SecretStore`; assert
  provider/model/baseUrl/params/key and every §9.3 fallback (no crash, `""`
  key). This is the desktop twin of `ProfileResolverCharacterizationTest`.
- `CompanionConfigWireEnumParityTest` — name/value parity both directions.
- Extend `DesktopDictationPipelineTest` to run a *profiled* take (real
  `ProfileBackedAiConfig` + fake runner + seeded profile) and assert the
  resolved provider/model reach the persisted transcription/turn rows — this is
  the regression guard that the finding's failure (only-ambiguity resolution)
  cannot recur.

## References

- Finding: AUDIT-D `plan-and-api-D-2`.
- Android reference: `app/src/main/java/net/devemperor/dictate/ai/adapter/ProfileResolver.kt`,
  `app/.../config/ConfigWireMapping.kt`, `app/.../config/ConfigSecrets.kt`,
  `app/src/test/java/.../ProfileResolverCharacterizationTest.kt`,
  `app/.../ConfigWireEnumParityTest.kt`.
- Companion touch points: `companion/.../ai/CompanionAiConfig.kt`,
  `companion/.../pipeline/{ConfigProfileSource,ActiveProfileSource,DictationEffects}.kt`,
  `companion/.../CompanionContainer.kt` (:131-176),
  `companion/.../data/CompanionConfigRepository.kt`,
  `companion/.../secrets/SecretStoreModule.kt`,
  `companion/.../ui/config/ConfigViewModel.kt` (credential-write gap, F5).
- Ports/DTOs: `shared-ai/.../port/AiConfig.kt`,
  `shared-ai/.../secrets/{SecretStore,SecretRef}.kt`,
  `shared/.../config/{Entities,ConfigEnums}.kt`.
- Specs: `research/entitaetenmodell-android.md §9`,
  `research/desktop-host.md §5.1 NOTE/§9.1/§15 Gap 5`; Plan §2.3, §3 D5.a,
  §3 D5.b.

---

## Findings (Update 2026-07-20 — Part B: the post-processing surface)

**Date:** 2026-07-20T00:40:00+02:00
**Triggered by:** repair wave 1 verdict on `plan-and-api-D-2` [Important, PARTIALLY
RESOLVED]. Part A (`ProfileBackedAiConfig` + container wiring + wire mapper, all
landed) closed the empty-key correctness core. The finding's remaining half — the
profile's **post-processing** surface that `ConfigProfileSource.current()` still
leaves at transitional `DEFAULT` (`instructions`, `stylePrompt`,
`autoFormatEnabled`, `language`, and the "fixed `SYSTEM_PROMPT_CONVERSATION`"
question) — was deferred to this follow-up because it "needs a plan decision, not
code repair of the wave". This update makes that decision.
**Agent-ID:** repair-research (desktop-aiconfig-credential-resolution — Part B)

### Plain-language summary of the decision

`DictationProfile` has five fields. Part A resolved the **AI-credential** half
(via `ProfileBackedAiConfig`) and `ambiguityMode`. The remaining four fields split
cleanly into **two owners**, and that split *is* the plan decision:

| Field | Owner | Source |
|---|---|---|
| `instructions` | **Profile content** | `profile.orderedPrompts` (auto-apply subset) → `PromptV3Entity` |
| `stylePrompt` | **Profile content** | `profile.stylePromptMode`/`stylePromptCustomText` + language, via the shared `PromptService` |
| `language` | **Device pref** | `CompanionSettings` (NOT a profile field) |
| `autoFormatEnabled` | **Device pref** | `CompanionSettings` (NOT a profile field) |

The fixed conversation system prompt is **not** part of this gap — see F9. The
decision to make is: *do `language`/`autoFormatEnabled` become profile columns, or
device settings?* Answer: **device settings**, because that is exactly how Android
models them and the entity model deliberately excluded them (F10). No
`ProfileEntity`/SQLDelight schema change is required — this stays a Block-D repair.

### Additional Sources (Part B)

1. **The Android PC-dictation sibling — the closest analog to the desktop companion:**
   - `app/.../core/PcDictationActivity.kt` `snapshotFreshConfig()` (:377-403) — the
     headless, host-less Android dictation path. It resolves `language` from
     `LanguageResolver.effectiveLanguage(sp)` (:378, a **device pref**), `stylePrompt`
     from `b.promptService.resolveWhisperStylePrompt(effLang)` (:380, **profile-backed**),
     `autoFormatEnabled` from `b.autoFormattingService.isEnabled()` (:381, a **device
     pref**), hard-codes `ambiguityMode = ALWAYS_INSERT`, and sends `queuedPromptIds
     = emptyList()` with `explicitEmptyQueue = true` (:388/:400). This is the exact
     field-by-field precedent for the desktop's four unresolved fields.
2. **How Android turns profile prompts into `TurnInstruction`s:**
   - `app/.../core/PipelineOrchestrator.kt` `buildPostProcessingInputs()` (:1760-1789)
     + `resolveQueueSlot()` (:1889-1916): each queue slot → `TurnInstruction(text,
     appliesToTranscript = entity.requiresSelection)`, skipped when the entity is a
     TEXT pill or (for `requiresSelection`) the transcript is empty.
   - `app/.../core/PromptQueueManager.kt` `prepareAutoApplyQueue()` (:112-124): the
     live queue is seeded from the **auto-apply** prompts (in order), before any
     manual additions. The desktop has no manual-tap surface, so the auto-apply
     subset *is* the whole desktop instruction set.
3. **The whisper style-prompt / prompt-config seam (shared, reusable from `:companion`):**
   - `shared-ai/.../prompt/PromptService.kt` `resolveWhisperStylePrompt(languageCode)`
     (:27-33): the NONE/PREDEFINED/CUSTOM switch, already the single source of truth.
   - `shared-ai/.../prompt/PromptTemplates.kt` `getPunctuationPromptForLanguage()`
     (:194-209): null/empty/"detect"/unknown → English default, region subtag →
     base language. Safe with a `null` desktop language.
   - `shared-ai/.../port/PromptConfig.kt` + `app/.../ai/adapter/ProfilePromptConfig.kt`:
     the narrow port and its entity-backed Android adapter (reads
     `ActiveProfile.stylePromptMode`/`stylePromptCustomText`). The Companion twin is
     a ~15-line mirror.
4. **The system-prompt distinction (why F9 is "already correct, not a gap"):**
   - `shared-ai/.../prompt/SystemPromptResolver.kt` `resolve(context)`: consumes
     `systemPromptMode`/`systemPromptCustomText` **only** for the REWORDING / LIVE /
     QUEUED contexts (standalone rewording + live-prompt paths).
   - `app/.../core/PipelineOrchestrator.kt` `executeConversationTurn()` (:1696) and
     `companion/.../pipeline/DictationEffects.kt` `postProcess()` (:171): **both** the
     app and the desktop pass the *fixed* `PromptTemplates.SYSTEM_PROMPT_CONVERSATION`
     to the dictation post-processing turn — the profile's `systemPromptMode` never
     touches it (ADR-0012 "system prompt persisted verbatim, not from live template").
5. **The desktop settings + wiring surfaces the fix touches:**
   - `companion/.../domain/CompanionSettings.kt` — the device-local typed settings
     store (self-healing defaults), where `language` + `autoFormatEnabled` belong.
   - `companion/.../CompanionContainer.kt` `production()` (:179) — the
     `ConfigProfileSource(configRepository, activeProfileId = …)` construction to extend.
   - `companion/.../ai/CompanionConfigWireMapping.kt` — has `ProviderType`/`Ambiguity`
     mappers; needs the `PromptSelectionMode → PromptMode` addition (F8).

### F6 — The remaining surface is four fields with two distinct owners

The finding lumped `instructions`, `stylePrompt`, `autoFormatEnabled`, `language`
and "SYSTEM_PROMPT_CONVERSATION" together as "unresolved profile→config". They are
not one thing. Two are **profile content** already present in the C-block data
(`orderedPrompts`, `stylePromptMode`/`stylePromptCustomText`) and resolvable now
with zero schema work. Two are **device prefs** that Android never stored on the
profile — resolving them is a `CompanionSettings` addition, not a profile-schema
change. The fixed conversation system prompt is a **non-issue** (F9). Treating them
as one blocked lump is what made the finding look like it "needs a plan decision";
seen field-by-field, the decision is small and local.

### F7 — `instructions` ← the profile's auto-apply prompts (profile content, resolvable now)

`profile.orderedPrompts` (each a `ProfilePromptRef{promptRef, autoApply}`) already
lands desktop-side via `CompanionConfigRepository.profile(id)` → `config.prompt(id)`
returns the `PromptV3Entity{text, requiresSelection, …}`. The desktop has no
keyboard queue and no manual-tap surface, so the faithful mapping of Android's
`prepareAutoApplyQueue` + `resolveQueueSlot` is: **the `autoApply == true` subset,
in `orderedPrompts` order**, each → `TurnInstruction(prompt.text, appliesToTranscript
= prompt.requiresSelection)`, dropping a `promptRef` whose prompt row is missing and
(mirroring `skipWhenTextEmpty`) a `requiresSelection` prompt when the transcript is
empty. Non-auto-apply prompts have **no trigger surface** on desktop v1 (that would
be a future desktop prompt-picker) — an intentional, documented boundary, not a
silent drop. `DictationEffects` already consumes `profile.instructions` verbatim
(:108) and `ConversationTurnBuilder.hasWork` already decides turn-vs-verbatim from
them, so this is purely a resolver change.

### F8 — `stylePrompt` ← reuse the shared `PromptService`, do not re-implement the switch

The whisper style prompt is profile content (`stylePromptMode`/`stylePromptCustomText`,
both already on the desktop `ProfileEntity` DTO). The NONE/PREDEFINED/CUSTOM logic —
including the language-aware `getPunctuationPromptForLanguage` fallback — is already
the single source of truth in `shared-ai`'s `PromptService.resolveWhisperStylePrompt`,
which `:companion` can see. **Reuse it** through a tiny Companion `PromptConfig`
adapter over the active profile (the twin of Android's `ProfilePromptConfig`) rather
than open-coding a second `when`. This keeps one behaviour for both platforms and
positions the desktop to reuse `SystemPromptResolver` unchanged if a standalone
rewording path is ever added. The adapter needs `PromptSelectionMode → PromptMode`,
which the app mapper has but `CompanionConfigWireMapping` does not yet — add it (+
extend `CompanionConfigWireEnumParityTest`), mirroring the app's `ConfigWireMapping`.

### F9 — The fixed `SYSTEM_PROMPT_CONVERSATION` is already correct — not part of this gap

The finding names "the fixed `SYSTEM_PROMPT_CONVERSATION` persistence" as sharing
the unresolved-profile root. It does not. The dictation post-processing turn uses a
**fixed** template on *both* platforms by design (ADR-0012: the turn-0 system prompt
is persisted verbatim as the SYSTEM row so a stored conversation survives template
changes across versions — `PipelineOrchestrator.executeConversationTurn` :1696 and
`DictationEffects.postProcess` :171 are byte-identical here). The profile's
`systemPromptMode`/`systemPromptCustomText` feed **only** `SystemPromptResolver` for
the REWORDING / LIVE / QUEUED standalone contexts, which the desktop companion
pipeline does not have in v1. **Recommendation: change nothing here, and do NOT wire
`systemPromptMode` into the conversation turn** — doing so would be a regression that
diverges the desktop from the app and breaks ADR-0012's persisted-verbatim contract.
This resolves the finding's third strand by clarification, not by code.

### F10 — `language` + `autoFormatEnabled` are device prefs — the plan decision is "CompanionSettings, not the entity schema"

Neither field exists on `ProfileEntity`, and that is deliberate, not an oversight:
- On Android they are **device prefs**, resolved outside the profile —
  `LanguageResolver.effectiveLanguage(sp)` and `AutoFormattingService.isEnabled()`
  (`= Pref.AutoFormattingEnabled && Pref.RewordingEnabled`). The PC-dictation sibling
  reads them exactly this way (`PcDictationActivity` :378/:381).
- The entity model **excludes** device-local, non-shareable state from profiles on
  purpose — the same reasoning the `Entities.kt` `is_active` NOTE spells out
  (":163 `is_active` is NOT a profile field … would pollute the hash"). Language and
  auto-format are per-device ergonomics, not shareable catalog content; adding them
  to `ProfileEntity` would pollute `contentHash`, break peer-catalog parity with
  Android, and require a cross-block C1/SQLDelight migration.

**Decision:** resolve `language` and `autoFormatEnabled` from **`CompanionSettings`**
(two new device-local settings with self-healing defaults, mirroring every other
setting there), NOT from the profile and NOT via a schema change. This keeps the fix
inside Block-D scope (desktop-local domain), mirrors Android's proven layering, and
preserves the config-hash invariant. For v1 they default to auto-detect language
(`null`) and the current transitional `autoFormatEnabled = false`; a desktop settings
UI to expose them is a small follow-on (the same shape as the Part-A F5 credential-
entry gap: the *resolution* is correct now regardless of when the UI lands).

### F11 — Scope boundary: no-profile path stays plain (unchanged)

When no profile is active (`activeProfileId == null` or the row is gone),
`ConfigProfileSource` should keep returning the plain `DEFAULT` (verbatim
transcription, no turn) — the honest "nothing configured" state, the same spirit as
`ProfileBackedAiConfig`'s §9.3 empty-config fallback. Device `language`/`autoFormat`
apply only once a profile is chosen; this keeps one coherent "no profile = plain"
story rather than half-configuring an unconfigured device.

## Implementation Hints (Part B — concrete)

### 1. Extend `ConfigProfileSource` (the only pipeline change)

`companion/.../pipeline/ConfigProfileSource.kt` — add two device-pref suppliers and
resolve the four fields; keep the no-profile branch returning `DEFAULT`:

```kotlin
class ConfigProfileSource(
    private val config: CompanionConfigRepository,
    private val activeProfileId: () -> String?,
    private val language: () -> String?,          // = settings::language
    private val autoFormatEnabled: () -> Boolean, // = settings::autoFormatEnabled
) : ActiveProfileSource {

    override fun current(): DictationProfile {
        val profileId = activeProfileId() ?: return DEFAULT
        val profile = config.profile(profileId) ?: return DEFAULT
        val lang = language()
        return DictationProfile(
            ambiguityMode = profile.ambiguityMode.toAmbiguityMode(),   // unchanged
            language = lang,
            autoFormatEnabled = autoFormatEnabled(),
            instructions = resolveInstructions(profile),
            stylePrompt = PromptService.create(ProfileBackedPromptConfig(profile))
                .resolveWhisperStylePrompt(lang),
        )
    }

    private fun resolveInstructions(profile: ProfileEntity): List<TurnInstruction> =
        profile.orderedPrompts
            .filter { it.autoApply }                       // desktop has no manual-tap queue
            .mapNotNull { ref ->
                val prompt = config.prompt(ref.promptRef) ?: return@mapNotNull null
                TurnInstruction(prompt.text, appliesToTranscript = prompt.requiresSelection)
            }
    // note: the requiresSelection/empty-transcript skip is transcript-dependent;
    // if kept here it needs the transcript — otherwise leave it to the builder,
    // which already lists instructions in order (appliesToTranscript is provenance).
}
```

The empty-transcript skip: Android drops `requiresSelection` slots when the text is
empty (`buildPostProcessingInputs` :1769). `ConfigProfileSource.current()` runs
*before* transcription (the profile is snapshotted at recording start, §8.1), so it
has no transcript. Two clean options: (a) accept a tiny divergence and always
include auto-apply instructions (a `requiresSelection` prompt on an empty transcript
is a degenerate case the model tolerates); or (b) push the skip into
`DictationEffects.runPipeline` where the transcript exists, filtering
`profile.instructions` before building `PostProcessingInputs`. Recommend (a) for v1
(simplest, and auto-apply prompts are rarely `requiresSelection`), documenting it.

### 2. `ProfileBackedPromptConfig` — Companion twin of `ProfilePromptConfig`

A ~15-line `PromptConfig` over the resolved `ProfileEntity` (or over
`config`+`activeProfileId`, matching the app's SharedPreferences+db shape). Reuse
`CompanionConfigWireMapping.toPromptMode` for the mode conversion:

```kotlin
class ProfileBackedPromptConfig(private val profile: ProfileEntity) : PromptConfig {
    override fun stylePromptMode()  = profile.stylePromptMode.toPromptMode()
    override fun stylePromptCustomText() = profile.stylePromptCustomText
    override fun systemPromptMode() = profile.systemPromptMode.toPromptMode()   // unused in v1, harmless
    override fun systemPromptCustomText() = profile.systemPromptCustomText
}
```

### 3. `CompanionConfigWireMapping` — add `PromptSelectionMode → PromptMode` (+ parity)

Mirror `app/.../config/ConfigWireMapping.kt` :44-45. Add
`fun PromptSelectionMode.toPromptMode(): PromptMode = runCatching {
PromptMode.valueOf(name) }.getOrDefault(PromptMode.NONE)` and extend
`CompanionConfigWireEnumParityTest` to assert the `PromptSelectionMode`↔`PromptMode`
name-set both directions (the drift guard).

### 4. `CompanionSettings` — two device-local settings

Add, next to the existing keys (self-healing defaults, same style as
`confirmBeforeInsert`):

```kotlin
/** The dictation language code (e.g. "de"), or null = auto-detect. Device-local,
 *  not profile content (entitaetenmodell §4.7 — language is a per-device ergonomic,
 *  not shareable catalog data). */
var language: String?
    get() = settings.get(KEY_LANGUAGE)?.takeIf { it.isNotBlank() }
    set(value) = settings.put(KEY_LANGUAGE, value.orEmpty())

/** Auto-format the transcript before insert (Android AutoFormattingService twin).
 *  Device-local pref; default false (the current transitional behaviour). */
var autoFormatEnabled: Boolean
    get() = settings.get(KEY_AUTO_FORMAT)?.toBooleanStrictOrNull() ?: false
    set(value) = settings.put(KEY_AUTO_FORMAT, value.toString())
```

with `KEY_LANGUAGE = "dictation.language"`, `KEY_AUTO_FORMAT = "dictation.autoFormatEnabled"`.
No UI is required for the resolver to be correct (follow-on, like Part-A F5).

### 5. Wire into `CompanionContainer.production()`

`ConfigProfileSource(configRepository, activeProfileId = { settings.activeProfileId },
language = settings::language, autoFormatEnabled = settings::autoFormatEnabled)`
(:179). Update the `DictationEffects.profiles` doc comment — the "rest stays
transitional" NOTE is now stale.

### 6. Leave the conversation system prompt untouched (F9)

Do not touch `DictationEffects.postProcess`'s `PromptTemplates.SYSTEM_PROMPT_CONVERSATION`.
Do not add a "systemPrompt" field to `DictationProfile`. This is the correct,
app-parity behaviour.

### 7. Tests (test-first)

- **`ConfigProfileSourceTest`** (new or extended): seed an in-memory SQLDelight DB
  with a profile carrying (a) two `orderedPrompts`, one `autoApply` one not, (b)
  `stylePromptMode = PREDEFINED` then `CUSTOM` then `NONE`, (c) `ambiguityMode`.
  Assert `current()` yields exactly the auto-apply `TurnInstruction` (in order,
  `appliesToTranscript` = requiresSelection), the right `stylePrompt` for each mode
  (PREDEFINED with `language = "de"` → the German punctuation prompt; `null` language
  → the English default; CUSTOM → the custom text; NONE → null), and that `language`
  / `autoFormatEnabled` reflect the injected suppliers. No-profile → `DEFAULT`.
- **`CompanionConfigWireEnumParityTest`**: extend with the `PromptSelectionMode`↔
  `PromptMode` name parity assertion.
- **`DesktopDictationPipelineTest`**: extend the profiled-take regression to assert a
  resolved auto-apply instruction reaches the persisted conversation-turn user
  message (guards against the "only-ambiguity resolution" regression recurring on the
  post-processing axis).
- **`CompanionSettingsDictationTest`**: the two new settings round-trip + self-heal on
  garbage.

## References (Part B)

- Android sibling (field-by-field precedent): `app/.../core/PcDictationActivity.kt`
  `snapshotFreshConfig()` (:377-403).
- Instruction resolution: `app/.../core/PipelineOrchestrator.kt`
  `buildPostProcessingInputs()`/`resolveQueueSlot()` (:1760-1916);
  `app/.../core/PromptQueueManager.kt` `prepareAutoApplyQueue()` (:112-124);
  `shared-ai/.../conversation/{PostProcessingInputs,TurnInstruction}.kt`.
- Style/system prompt seam: `shared-ai/.../prompt/{PromptService,SystemPromptResolver,
  PromptTemplates}.kt`; `shared-ai/.../port/PromptConfig.kt`;
  `app/.../ai/adapter/ProfilePromptConfig.kt`; `app/.../config/ConfigWireMapping.kt` (:44-45).
- System-prompt-verbatim contract: ADR-0012; `DictationEffects.postProcess` (:167-196).
- Device-pref-not-profile precedent: `shared/.../config/Entities.kt` (:162-163
  `is_active` NOTE); `app/.../core/AutoFormattingService.kt` (:17-22).
- Desktop touch points: `companion/.../pipeline/{ConfigProfileSource,ActiveProfileSource,
  DictationEffects}.kt`; `companion/.../domain/CompanionSettings.kt`;
  `companion/.../ai/CompanionConfigWireMapping.kt`; `companion/.../CompanionContainer.kt` (:179);
  `companion/.../data/CompanionConfigRepository.kt` (`profile`/`prompt` accessors).
</content>
</invoke>
