# Repair Wave W1 — Cluster 2 (Block D)

**Date:** 2026-07-20T00:40:00+02:00
**Agent:** repair-fix (D / W1-2)
**Findings:** plan-and-api-D-1, plan-and-api-D-2, plan-and-api-D-3, convention-D-1
**Result:** D-1, D-2 (core), convention-D-1 fixed + tested; D-3 partial (data layer done, UI remains).
`:companion:verifySqlDelightMigration` green; `:companion:test` green (20 new tests, 0 failures).

---

## convention-D-1 — TranscriptionRow → TranscriptionRecord (fixed)

Renamed the persist-input DTO for suffix consistency with its siblings `ConversationTurnRecord` /
`ContinuationTurnRecord` (all built by the effects layer and handed to a `persist*/insert*` method),
and to drop the visual collision with the SQLDelight-generated `Transcriptions` row type. Renamed the
type + the `insertTranscription` parameter (`row` → `record`) and its single construction site.

- `DesktopSessionRepository.kt` — `data class TranscriptionRecord`, `insertTranscription(record)`.
- `DictationEffects.transcribe` — import + `TranscriptionRecord(...)`.

## plan-and-api-D-1 — desktop usage sink (fixed)

Per `research/desktop-usage-sink-migration.md` (recommendation: fold the `usage` table into the
already-committed `3.sqm`, keep E1 = `4.sqm` — safe because the companion is unreleased and `3.sqm`
was committed in this same run; no field DB is at v4). Closes D1b-1: every desktop transcription /
completion / conversation turn calls `usageSink.addUsage(...)`, and `NoopUsageSink` was discarding it.

- `Companion.sq` — `CREATE TABLE usage` (open-vocab `model_provider`, NO CHECK / no adapter — mirrors
  Room `UsageEntity`, §3.3 non-parity) + `addUsage` increment-upsert + `usageByModel` / `allUsage`.
- `migrations/3.sqm` — byte-identical `CREATE TABLE usage` (folded here per D1b-1).
- `databases/4.db` — regenerated (`:companion:generateMainDictateCompanionDbSchema`); `3.db` unchanged
  (usage is created *inside* 3.sqm, v3→v4). `verifySqlDelightMigration` green.
- `SqlDelightUsageSink.kt` [NEW] — the desktop twin of `RoomUsageSink`, pure delegation, no threading.
- `CompanionContainer.production` — `usageSink = SqlDelightUsageSink(database)` (was `NoopUsageSink`).
- `NoopUsageSink.kt` — KDoc rewritten to its real, permanent role (the no-usage double for headless
  tests; still used by `DesktopDictationPipelineTest`) — no longer "deferred to D3".
- `SqlDelightUsageSinkTest.kt` [NEW, 3 tests] — insert, counter accumulation on conflict,
  `model_provider` untouched on conflict.

No `CompanionDatabase.build()` change (usage has no typed column); `CompanionSchemaParityTest` stays
green (usage is non-parity).

## plan-and-api-D-2 — desktop AiConfig / credential resolution (core fixed; part-b scoped follow-up)

Per `research/desktop-aiconfig-credential-resolution.md`. The **must-land correctness fix (part a)** is
delivered: the desktop pipeline no longer runs against a hard-coded empty key. All inputs had already
landed (C1 config repo, B1 SecretStore), so the resolver is a pure mirror of the Android
`ProfileResolver`.

- `ProfileBackedAiConfig.kt` [NEW] — entity-backed `AiConfig`: `provider`/`modelName`/`baseUrl`/
  `completionParameters`/`elevenLabsKeyterms` from the active profile, `apiKey` from the SecretStore
  (ASCII-stripped), with the §9.3 never-crash fallbacks (no profile / no modelRef / absent credential
  → empty, never throws). Mirrors `ProfileResolver` 1:1.
- `CompanionConfigWireMapping.kt` [NEW] — the D5.a wire↔domain mapper (`ProviderType → AIProvider`,
  `AmbiguityModeValue → AmbiguityMode`) the companion needs because it sees both `:shared` and
  `:shared-ai` (the app's `ConfigWireMapping` is `:app`-local).
- `CredentialSecrets.kt` [NEW] — companion-local `CREDENTIAL_NAMESPACE = "credential"` (+ `credentialRef`),
  pinned equal to `:app`'s `ConfigSecrets` so a peer-delivered / cross-platform credential resolves to
  the same `SecretRef.handle`.
- `CompanionContainer.production` — wires `SecretStoreModule.detect(AppPaths.dataDirectory())` +
  `ProfileBackedAiConfig`; `CompanionAiConfig()` removed from the graph.
- `ConfigProfileSource.kt` — the inline `AmbiguityModeValue → AmbiguityMode` `when` folded into the
  shared mapper (DRY, one parity-pinned seam); scope KDoc updated.
- `CompanionAiConfig.kt` — KDoc updated: it is now the fixed-config **test baseline** (used by
  `DesktopDictationPipelineTest`), not the production path.
- Tests [NEW]: `ProfileBackedAiConfigTest` (6 — provider/model/baseUrl/params/key + every fallback),
  `CompanionConfigWireEnumParityTest` (4), `CredentialSecretsTest` (2).

**Remaining (part b — NOT done, documented follow-up).** The `DictationProfile` post-processing surface
`ConfigProfileSource` still returns transitional defaults for `instructions` / `stylePrompt`, and
`autoFormatEnabled` / `language` have no `ProfileEntity` field (they are prefs on Android — a v1 schema
boundary, not inventable in a D-repair). The research explicitly authorises this as a "second commit":
resolving `orderedPrompts → List<TurnInstruction>` and `stylePromptMode/customText → stylePrompt`
mirrors the app's `ProfilePromptConfig`/`ActiveProfile` prompt resolution, a separate correctness
surface (predefined style-prompt vocab, auto-apply / requiresSelection semantics). Shipping it subtly
wrong would be worse than leaving it transitional. **Recommend a follow-up chunk** for the
`ConfigProfileSource` instruction/style extension. The finding's CONFIRMED core — "a real
transcription/completion fails (empty key)… Block D's goal not reachable in production" — is resolved:
with a stored credential a real desktop dictation now authenticates and post-processes (fixed system
prompt + profile ambiguity mode). Credential *population* (getting a key into the store) remains Block
E peer-delivery / a future entry field (research F5, §15 Gap 5) — out of this repair's scope.

## plan-and-api-D-3 — desktop-session history (partial: data layer delivered + tested; UI remains)

The finding's named defect — `pageHistory`/`countHistory` JOIN `dispatch_state` and scope to
`PHONE_SYNC`, so a desktop-dictated session (no `dispatch_state` row) can never surface — is fixed at
the query + read-path level, with tests. The **HistoryScreen UI section (§9.3 transcript-vs-output
detail + re-insert surfacing)** is NOT built (see skip reasoning below).

- `Companion.sq` — `pageDesktopHistory` / `countDesktopHistory` / `desktopHistoryEntry`: scope
  `host_origin = 'DESKTOP_DICTATION' AND status = 'COMPLETED' AND origin != 'REVIEW_REFINEMENT'`,
  expose both `final_output_text` and the current transcript (correlated subquery, highest version),
  `instr(lower(...))` substring search matching `pageHistory`.
- `DesktopSessionRepository.kt` — `pageDesktopHistory` / `countDesktopHistory` / `desktopHistoryEntry`
  read methods + the `DesktopHistoryEntry` domain model (sessionId, createdAt, finalOutputText,
  transcriptText, insertedAt).
- `DesktopHistoryTest.kt` [NEW, 5 tests] — completed takes newest-first with transcript+output,
  substring filter, and exclusion of in-flight / REVIEW_REFINEMENT / PHONE_SYNC rows.

**Why the UI is deferred (skip reason).** Surfacing desktop sessions in `HistoryScreen` is a fresh
Compose feature: the existing screen + `HistoryViewModel` are built entirely around `ReceivedText` +
`DispatchService.reinsert` (phone-sync, `dispatch_state`-backed), which desktop sessions do not have —
re-insert would have to go through `container.inserter` directly, needing a new (untested) view-model
and a separate section. The finding itself flagged this: "Confirm with main loop whether to build now
within Block-D repair vs. track as a follow-up chunk." Building an untested Compose surface in a repair
wave is the wrong trade; the tested data foundation it needs is now in place. **Recommend a dedicated
follow-up chunk** for the HistoryScreen desktop-sessions section.

---

## Deviations

| Deviation | Plan location | What changed | Why | Impact | Resolved? |
|---|---|---|---|---|---|
| `usage` folded into committed `3.sqm` (not a new `4.sqm`) | research D-1 rec. | Amended a committed migration | Unreleased app, no field DB at v4; E1 keeps `4.sqm`, zero cross-block renumber | E1's `4.sqm` unaffected (builds on amended `4.db`) | Yes |
| `CredentialSecrets` kept companion-local (not promoted to `:shared-ai`) | research D-2 hint 2 | Local constant + parity test `== "credential"` | Promoting widens scope into `:app`/`:shared-ai`; the minimum (research-sanctioned) keeps drift caught without cross-module churn | If the constant ever moves, promote then | Yes (minimum) |

## Files outside assigned scope (drift)

- `CompanionAiConfig.kt` — not in D-2's file list, but is the transitional config D-2 replaces; edited
  KDoc only (documents its new test-baseline role now production uses `ProfileBackedAiConfig`). No
  behaviour change; the class stays for `DesktopDictationPipelineTest`.

## Skipped / remaining (for the re-audit + main loop)

1. **D-2 part b** — `ConfigProfileSource` instruction/style resolution from the profile (see D-2 above).
   Follow-up chunk; AiConfig correctness core is fixed.
2. **D-3 UI** — HistoryScreen desktop-sessions section (see D-3 above). Follow-up chunk; data layer +
   query fix delivered and tested.
