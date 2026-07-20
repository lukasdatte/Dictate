# Block A — Re-Audit of Repair Wave 1

**Mode:** re-audit · **Block:** A · **Timestamp:** 2026-07-20T00:40:00+02:00
**Repair-wave commit:** `c6a828f` — `[A] repair wave 1 (desktop-companion-v1)`
**Consolidator:** verify wave (fixes nothing)
**Verdict:** **Converged.** All 4 findings resolved or intentionally deferred. No
new problems introduced. `findings` array empty.

## Per-finding verification

### convention-A-1 — Spec-reference style split in `ai/adapter/` → RESOLVED

The wave added a matching `@see docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md §4.x`
anchor to all five inline-only adapters, replacing the prose `(spec §…)` form,
so all six adapters now match the `AndroidAiConfig` / port convention:
- `AndroidAiFactory.kt` → `@see … §4.5`
- `AndroidPromptConfig.kt` → `@see … §6 A3.5`
- `MediaMetadataAudioDurationReader.kt` → `@see … §4.4`
- `RoomUsageSink.kt` → `@see … §4.2`
- `SharedPrefsProxyConfig.kt` → `@see … §4.3`

Comment-only, greppable/resolvable anchors. Systemic convention drift closed.

### convention-A-2 — dead/bypassed `SystemPromptResolver.create()` → RESOLVED

`PromptService.create` (`PromptService.kt:72`) now composes the resolver via
`SystemPromptResolver.create(config)` instead of the constructor directly, giving
the previously zero-call-site companion its intended single call site. Symmetry
between the sibling `create()` factories restored; `SystemPromptResolver.create`
(`SystemPromptResolver.kt:33`) is now exercised. `:shared-ai:test` green.

### A-TEST-2 — `FakeProxyConfig.installAuthenticatorCalls` dead affordance → RESOLVED

The wave gave `FakeProxyConfig` a `proxy: Proxy?` ctor arg (`rawProxy()` returns
it) and made `ElevenLabsTranscriptionRunner.buildClient()` `internal` (mirroring
the existing `buildMultipartBody` pattern, documented KDoc, no behaviour change).
Two new tests in `ElevenLabsTranscriptionRunnerTest` now **read** the counter:
- no-proxy → asserts `client.proxy == null` and `installAuthenticatorCalls == 0`
- resolved-proxy → asserts `client.proxy === proxy` and `installAuthenticatorCalls == 1`

grep confirms the counter is read at two live assertion sites; the affordance is
no longer dead. Both tests pass (`:shared-ai:test` BUILD SUCCESSFUL).

### A-TEST-1 — moved AI-core files lack direct `:shared-ai` unit tests → DROPPED (deferred by design)

This finding was classified in the initial consolidation as an explicit **deferred
follow-up** — "add an in-module test when any of these files is next modified
inside `:shared-ai`", not an edit to make in this wave. It is a future-signal, not
an outstanding repair, so the wave correctly did not touch it. Dropped from the
repair loop to allow convergence; the deferred note remains recorded in
`validated-findings.md` for the future companion-only paths. Not a regression, not
a pending fix.

## New problems introduced by the wave?

**None.**
- `@see` anchor format matches the established convention (`AndroidAiConfig`,
  ports) verbatim — comment-only.
- `buildClient()` visibility change (`private` → `internal`) mirrors the existing
  `buildMultipartBody` test-seam pattern; no behaviour change, proxy branch logic
  untouched.
- New tests compile and pass; full `:shared-ai:test` suite BUILD SUCCESSFUL.

## Build/test state (re-audit run)

`:shared-ai:test` → BUILD SUCCESSFUL (full suite + targeted
`ElevenLabsTranscriptionRunnerTest`). No compile/visibility regressions from the
`SystemPromptResolver.create` or `buildClient` changes.
