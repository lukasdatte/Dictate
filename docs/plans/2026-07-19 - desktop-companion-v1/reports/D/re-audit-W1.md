# Block D — Re-audit of Repair Wave 1 (MODE = re-audit)

**Block:** D (D1a schema/sync · D1b capture/pipeline · D2 hotkey/panel/insert · D3 review/config/entities)
**Timestamp:** 2026-07-20T00:40:00+02:00
**Repair commit:** `b99b1419dfbb23c03bfdb53227e32725cc95f6cb` — `[D] repair wave 1`
**Findings verified:** the 9 from `validated-findings.md` (MODE = initial)
**Verification run:** `:companion:test` + `:companion:verifySqlDelightMigration` → BUILD SUCCESSFUL (up-to-date against the committed tree); `4.db` snapshot inspected — contains the `usage` table.

## Verdict

**Not converged.** 7 of 9 findings fully resolved and dropped; **2 remain partially resolved**
(plan-and-api-D-2, plan-and-api-D-3) — each had its correctness/data core delivered but a
documented sub-scope was deliberately deferred to a follow-up chunk. No new defects were introduced
by the wave (one benign, undocumented behaviour improvement noted below). The two remainders are
scope/ownership decisions that warrant main-loop attention.

| ID | Original sev | Wave outcome | Re-audit |
|---|---|---|---|
| logic-D-1 | Critical | fixed | **dropped** |
| plan-and-api-D-1 | Important | fixed | **dropped** |
| plan-and-api-D-2 | Important | core fixed, part-b deferred | **kept (re-scoped to part b)** |
| plan-and-api-D-3 | Important | data layer fixed, UI deferred | **kept (re-scoped to UI)** |
| convention-D-1 | Nice-to-have | fixed | **dropped** |
| convention-D-2 | Nice-to-have | fixed | **dropped** |
| logic-D-2 | Nice-to-have | fixed | **dropped** |
| logic-D-3 | Nice-to-have | fixed | **dropped** |
| T1 | Nice-to-have | fixed | **dropped** |

## Resolved findings (dropped)

### logic-D-1 (Critical) — RESOLVED
`Take.finish()` now binds `val merged = WavConcat.merge(segments, File(recordingsDir, "${'$'}{takeId}.wav"))`
— consumes merge's return, so a single-segment (short) take gets the lone `{takeId}_1.wav` that
actually exists. `WavCodecTest.merge_returnValueAlwaysExists_soFinishUploadsARealFile` guards the
merge-contract regression. (A `finish()`-level test is not added — the adapter has no injectable
`TargetDataLine` seam; the fixer's scope note on that is reasonable for a repair wave, and the
merge-contract test covers the exact contract `finish()` relies on.)

### logic-D-2 (Nice-to-have) — RESOLVED
`PcmAmplitude.peak` now `minOf(abs(sample), 32767)`; regression test
`peak_clampsAFullScaleNegativeSampleToThe32767AndroidRange` (feeds `Short.MIN_VALUE`, asserts 32767).

### logic-D-3 (Nice-to-have) — RESOLVED
`line.stop()` moved before `stopLoop()` in both `finish()` and `discard()`, so a pending `line.read`
is unblocked before the join → no close-under-live-thread `IllegalStateException`.

### plan-and-api-D-1 (Important) — RESOLVED
`usage` table added to `Companion.sq` (+ `addUsage` increment-upsert, `usageByModel`/`allUsage`) and
folded into the committed `3.sqm` (v3→v4) per the D1b-1 hand-off; `4.db` regenerated and verified
(migration green, snapshot confirmed to carry `usage`). `SqlDelightUsageSink` [NEW] wired at
`CompanionContainer.production` in place of `NoopUsageSink` (whose KDoc is rewritten to its permanent
test-double role). `SqlDelightUsageSinkTest` (3 tests: insert, counter accumulation on conflict,
`model_provider` untouched on conflict). The migration-numbering decision the finding flagged (yellow)
was taken as research-sanctioned (fold into `3.sqm`, keep E1 = `4.sqm`; safe because the companion is
unreleased) and documented as a deviation — an acceptable resolution of the yellow gate.

### convention-D-1 (Nice-to-have) — RESOLVED
`TranscriptionRow` → `TranscriptionRecord` (type + `insertTranscription` param + sole construction
site in `DictationEffects.transcribe`).

### convention-D-2 (Nice-to-have) — RESOLVED
`import java.util.concurrent.ConcurrentHashMap` added; use site now `ConcurrentHashMap.newKeySet<String>()`.

### T1 (Nice-to-have) — RESOLVED
`assertCheckFailure`, `Iterable<Enum<*>>.names`, `SqlDriver.exec` extracted into
`companion/src/test/.../fakes/SqlCheckSupport.kt`; all four data-package tests import it, local copies
removed, orphaned `assertTrue` imports dropped. Suite green.

## Kept findings (still need fixing)

### plan-and-api-D-2 (Important, yellow — `desktop-aiconfig-credential-resolution`) — CORE RESOLVED, PART B REMAINS

**Resolved core:** `ProfileBackedAiConfig` [NEW] replaces the empty-key `CompanionAiConfig` at
`CompanionContainer.production` — `provider`/`modelName`/`baseUrl`/`completionParameters`/
`elevenLabsKeyterms` from the active profile, `apiKey` from the SecretStore
(`SecretStoreModule.detect(AppPaths.dataDirectory())`, ASCII-stripped), with §9.3 never-crash
fallbacks. Supporting `CompanionConfigWireMapping` (D5.a wire↔domain) and `CredentialSecrets`
(`CREDENTIAL_NAMESPACE = "credential"`, parity-pinned to `:app`'s `ConfigSecrets`) [both NEW]. Tested
(`ProfileBackedAiConfigTest` 6, `CompanionConfigWireEnumParityTest` 4, `CredentialSecretsTest` 2). The
empty-key core of the finding — "a real transcription/completion fails; Block D's goal not reachable
in production" — is closed: with a stored credential a real desktop dictation now authenticates.

**Remaining (part b):** `ConfigProfileSource.current()` still returns transitional defaults for the
post-processing surface of the profile that the original finding named — `instructions` /
`stylePrompt` (the profile's prompts → `List<TurnInstruction>` / style-prompt resolution, mirroring
the app's `ProfilePromptConfig`/`ActiveProfile`), and `autoFormatEnabled` / `language`, which have **no
`ProfileEntity` field** (they are prefs on Android — a v1 schema-boundary question, not inventable in a
repair). The transitional fixed `SYSTEM_PROMPT_CONVERSATION` persistence is the same root. The fixer
deliberately deferred this ("shipping it subtly wrong would be worse than transitional") and recommends
a follow-up chunk; the research file authorises it as a separate commit. **Still a real spec gap
against Block-D scope — needs an owning chunk with acceptance, or an explicit plan/spec amendment
stating desktop post-processing profile resolution lands in a named later chunk.** Credential
*population* (getting a key into the store) is separately Block E / a future entry field, out of D
scope.

**Classification:** yellow, Important (unchanged) — the remaining decision is architecture/ownership
(schema boundary for autoFormat/language; where the prompt-resolution mirror lives), not a mechanical
inline fix. Likely `AskUserQuestion`.

### plan-and-api-D-3 (Important, green) — DATA LAYER RESOLVED, UI REMAINS

**Resolved data layer:** `pageDesktopHistory` / `countDesktopHistory` / `desktopHistoryEntry` in
`Companion.sq` (scope `host_origin='DESKTOP_DICTATION' AND status='COMPLETED' AND
origin!='REVIEW_REFINEMENT'`, expose `final_output_text` + current transcript via correlated subquery,
`instr(lower(...))` substring search) + `DesktopSessionRepository` read methods and the
`DesktopHistoryEntry` model. `DesktopHistoryTest` (5 tests: newest-first with transcript+output,
substring filter, exclusion of in-flight / REVIEW_REFINEMENT / PHONE_SYNC). This closes the named
defect — desktop sessions were unreachable because `pageHistory` JOINs `dispatch_state` and scopes to
`PHONE_SYNC`.

**Remaining (UI):** No `HistoryScreen` section surfaces desktop sessions. `HistoryScreen.kt` was **not
touched** by the wave; it and `HistoryViewModel` are built entirely around `ReceivedText` +
`DispatchService.reinsert` (phone-sync). §9.3's transcript-vs-output detail and re-insert (which for a
desktop session must go through `container.inserter`/`TextInserter`, not `DispatchService`) are not
built. **Still an undelivered §9.3 scope item** — a user dictates, it persists, and no UI shows or
re-inserts it. The design is spec-prescribed and the data foundation now exists.

**Classification:** green, Important (unchanged) — design is clear (§9.3), no research needed; but it
is a fresh Compose surface (new view-model + `TextInserter` re-insert path). The finding itself asked
the main loop to decide build-now-in-repair vs. dedicated follow-up chunk; that decision is still open.

## New problems introduced by the wave

**None blocking.** One observation:

- **Undocumented benign drift in `Take.pause()` / `Take.resume()`** (`JavaSoundAudioCaptureService.kt`,
  repair-W1-1 cluster). Beyond the reported logic-D-3 fix (finish/discard ordering), the wave also added
  `line.stop()` to `pause()` and `line.start()` to `resume()`. This is a spec-§4.3-aligned correctness
  improvement (a running line during a paused loop would overrun its buffer and leak during-pause audio
  on resume) and is coherent with the read loop (which already skips `line.read` while `paused`). It is
  **not** mentioned in repair-W1-1's report (which only documents finish/discard). No race or crash: it
  is safe and beneficial. Noted for the trail, not elevated to a finding.

## Cross-cut

- The two kept findings share the root the initial consolidation already noted: **D3 under-delivered
  against the spec's Block-D scope on two axes** (profile post-processing resolution; §9.3 history UI).
  The wave delivered the correctness cores of both and pushed the remaining, larger-surface halves to
  follow-up chunks with explicit, defensible rationale. They are genuine remaining scope, not clever
  deviations.
- Both remainders were pre-flagged in the initial consolidation's "Escalation note" as scope/ownership
  decisions likely needing `AskUserQuestion`. Convergence of Block D depends on a plan decision (build
  the two remainders now vs. name their owning later chunks), not on further repair of this wave's code.
