# Inline-Anchor Worker Report — `companion-ai-secrets-platform`

**Date:** 2026-07-20T17:25:00+02:00
**Agent:** doc-worker-inline (finalize)
**Group:** `companion-ai-secrets-platform`
**Scope:** `companion/src/main/kotlin/net/devemperor/dictate/companion/{ai,secrets,platform}/`
**Plan:** `docs/plans/2026-07-19 - desktop-companion-v1/desktop-companion-v1.md`
**Range:** `c46cfe8..HEAD`

## Summary

The three subsystems were already **densely and well-anchored** from the Block-F
doc chunk — every service/class carries a responsibility header, `@see`
plan/research anchors resolve, and gotcha comments are WHY-only (no code-recap
noise). Two surgical fixes applied: one stale plan-chunk reference reworded, and
the promoted **ADR-0029** cross-reference added to the three companion SecretStore
files to bring them to parity with their `:app` twins.

## Anchors changed

| File | Anchor | Change |
|---|---|---|
| `ai/CompanionAiConfig.kt` | gotcha (method) | **Updated stale.** `apiKey()` said "No credential until D3 wires the SecretStore-backed profile key" — D3 landed (`ProfileBackedAiConfig` is production-wired in `CompanionContainer.kt:199`, and this class's own header already declares it a retained test baseline). Reworded to "Always keyless by design: the SecretStore-backed profile key lives in [ProfileBackedAiConfig]." — removes the misleading pending framing. |
| `secrets/DpapiSecretStore.kt` | `@see` ADR | **Added** `@see docs/decisions/0029-secret-store.md`. |
| `secrets/FileAesGcmSecretStore.kt` | `@see` ADR | **Added** `@see docs/decisions/0029-secret-store.md`. |
| `secrets/SecretStoreModule.kt` | `@see` ADR | **Added** `@see docs/decisions/0029-secret-store.md`. |

### Why the ADR-0029 additions

ADR-0029 (`Project-Wide SecretStore Port — Encrypted-at-Rest Secrets on Every
Host`, Status Accepted) is the canonical decision governing exactly what these
three files implement: one encrypted-at-rest backend per host, and the invariant
that a decrypt failure surfaces as `DecryptionFailed` rather than an empty key.
The `:app` twins already anchor it — `AndroidKeystoreSecretStore.kt:25` and
`SecretsMigration.kt:56` both carry `@see docs/decisions/0029-secret-store.md`.
The companion backends referenced only the plan's `research/secretstore.md` spec,
leaving the two SecretStore families pointing at different homes for the same
decision. Added alongside (not replacing) the existing research `@see` so the
fachliche spec link stays too. Target file confirmed present.

## Anchors verified (no change needed)

All of the following were reviewed against the plan/diff and are already correct
and resolving:

- **`ai/`** — `CredentialSecrets`, `CompanionConfigWireMapping`, `NoopUsageSink`,
  `ProfileBackedAiConfig`, `ProfileBackedPromptConfig`, `SqlDelightUsageSink`,
  `CompanionProxyConfig`, `CompanionAiConfig` (header): strong headers with
  resolving `@see` into `research/{desktop-host,entitaetenmodell-android,
  desktop-aiconfig-credential-resolution,shared-ai-extraktion}.md`; ADR-0012
  referenced where the fixed-system-prompt decision is invoked.
- **`secrets/`** — headers explain DPAPI user-account binding, the file-store
  `0600`/atomic-create hardening, per-access `available` evaluation, and the
  shared `secretFileName`/`writeSecretBlobAtomically` DRY helpers; all gotchas
  are non-derivable (§4.3 never-silent-empty, CREATE_NEW collision rationale).
- **`platform/`** — `AppPaths` (Local-not-Roaming rationale), `PlatformModule`
  (ADR-0018 OS-detection seam), the `fallback/Noop*` degrade-honestly ports, and
  the `windows/` leaves (`Win32GlobalHotkey` thread-affinity constraint,
  `Win32WindowStyler` `WS_EX_NOACTIVATE` spike, `AwtNotificationPort`
  single-tray-slot rule) all carry accurate headers + dated/spec-linked gotchas.

## Comment noise removed

None — no code-restating comments were found in scope.

## Drift (edits outside assigned scope)

None. All four edits are within the assigned `{ai,secrets,platform}/` scope and
touch comment/KDoc lines only (verified via `git diff` — no logic, imports, or
formatting changed).

## Notes for final

- The `:app` secrets twins (`AndroidKeystoreSecretStore`, `SecretsMigration`,
  `KekProvider`) and the companion secrets backends now both anchor ADR-0029 —
  parity restored across the SecretStore family.
- No source bugs or name-smells observed in this group.
</content>
</invoke>
