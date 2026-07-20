# ADR-0025: Input-Command Protocol — Semantic Keyboard Commands over the Wire, not VK Codes

**Status:** Accepted
**Subsystem:** shared, companion
**Scope:** Project-Wide
**Date:** 2026-07-15
**Author:** Lukas + Claude (Fable 5)

> **Extends ADR-0016 (wire protocol / Konform-on-both-sides / additive-versioning),
> ADR-0017 (delivered-only success semantics) and ADR-0018 (Win32 SendInput / UIPI).**
> This ADR adds a *second* payload family to the same protocol — a batch of keyboard actions
> for the paired PC — reusing those mechanisms rather than inventing a parallel channel.

## Plain-language summary

The phone's keyboard can, in PC-mode, act as a remote control for the Windows cursor. To do that
it must tell the companion "press Backspace", "move the cursor left three times", "redo". This ADR
records **how** those instructions travel: as **semantic commands** (`BACKSPACE`, `CURSOR_LEFT`,
`REDO`) — never as raw Windows virtual-key codes. The companion alone decides which physical keys a
command presses. The endpoint is additive (`POST /v1/input`), so shipping it needs no protocol
version bump; an old companion simply answers 404 and the phone says "update the companion".

## Research

Grounded in the shipped, green implementation (blocks §A/§B/§B2 of the keyboard-action-engine plan,
`tmp/plan-keyboard-action-engine.md` (worktree `feature/keyboard-action-engine`)). Load-bearing facts, verified in code:

- **The protocol already had exactly one door.** `ProtocolCodec` validates on encode **and** decode
  (`shared/.../protocol/ProtocolCodec.kt`), every DTO carries `protocolVersion` first, and
  `ignoreUnknownKeys = true` is what lets an optional field ship without a version bump
  (`ProtocolVersion.kt:17-23`). The new DTOs slot into that door unchanged.
- **A transport analysis (plan §4.3.1) showed the wire brings no batching.** OkHttp reuses the TCP
  connection (keep-alive) but never batches requests; Nagle is off (`TCP_NODELAY`); HTTP/1.1 has no
  pipelining. So a "batch of commands" must be an application-level list, not a transport trick —
  hence `commands: List<InputCommandWire>` with list order = execution order.
- **UIPI degradation was already modelled for Ctrl+V** (`Win32Keyboard.sendCtrlV` returns the
  accepted-event count, ADR-0018). The input performer reuses the identical detection for every
  chord.

## Context

The keyboard-action engine routes every keyboard action to the PC in PC-mode (see the sibling
routing ADR). Those actions must cross the wire. Two shapes were possible: raw VK/scan codes, or
semantic commands. The choice shapes validation surface, layout-portability, injection risk, and who
owns the keyboard layout.

## Decision

1. **Semantic commands, never VK codes.** `InputCommandKindWire` is a closed enum of *intents*
   (`TYPE_TEXT`, `BACKSPACE`, `ENTER`, `SPACE`, `CURSOR_LEFT/RIGHT`, `CURSOR_WORD_SELECT_BACK/
   FORWARD`, `SELECT_ALL`, `CUT/COPY/PASTE/UNDO/REDO`). The phone says what it *means*; the companion
   resolves the physical keys.

2. **Additive endpoint, no version bump.** `POST /v1/input` with `InputCommandRequest{commands}` and
   `InputCommandResponse{executed, outcome}`. `HealthResponse` gains `supportsInputCommands`
   (defaulted `false`). All Konform-validated on both sides: batch ≤ `MAX_INPUT_BATCH` (20), repeat
   `count` ≤ `MAX_INPUT_REPEAT` (50), `text` present **iff** `TYPE_TEXT`.

3. **`executed`-only success, mirroring `delivered` (ADR-0017).** A missing foreground window or a
   UIPI rejection returns HTTP 200 with `executed = false` and a reason (`NO_FOREGROUND_WINDOW` /
   `REJECTED`); only `SENT` is a success. Unlike a dictation there is **no clipboard fallback** — a
   keyboard action that could not be injected simply did not happen, and the phone shows a failure.

4. **Ephemeral — no history, no sync.** Keyboard actions are never persisted (they would drown the
   dictation history in single keystrokes); only `/v1/dispatch` sessions are archived.

5. **Chord resolution is companion-only, configurable, typed in the DB (D6).** A `key_command_chords`
   table (Double-Enum, `docs/DATABASE-PATTERNS.md`) maps each command to a `KeyChord`; the
   `Win32InputPerformer` resolves through the `ChordMappingRepository` port as the **single**
   resolution point, with `DefaultChords` the one home of VK literals. Because the wire is semantic,
   rebinding a chord touches neither `shared/` nor the app.

6. **`CURSOR_WORD_SELECT_BACK/FORWARD` carry the backspace-swipe word selection (D1).** They map to
   Ctrl+Shift+←/→ by default. Accepted limitation: word-selection is an app convention, not an OS
   guarantee (terminals, Vim modes vary); no fallback in v1.

## Alternatives considered

- **Raw VK / scan codes over the wire.** Rejected: not meaningfully validatable (any 0–255 is
  "valid"), a larger injection surface, and it drags the Windows keyboard layout onto the phone.
- **Chord DTOs over the wire (modifiers + key).** Rejected: makes the phone the owner of the PC's key
  bindings; D6's per-PC configurability would then have to round-trip through the phone.
- **A WebSocket channel for low-latency streaming.** Rejected for v1: the 500-ms send window plus the
  circuit breaker (sibling ADR) make request/response latency acceptable, and a socket would add a
  second connection lifecycle to own. Noted as a possible follow-up.

## Consequences

### Positive
- Validation, layout-portability and a small injection surface all follow from semantics.
- Rebinding chords is a pure companion concern (retroactively justifies the semantic wire).
- Additivity means new-phone/old-companion and old-phone/new-companion both degrade cleanly.

### Negative
- The companion owns a VK mapping the phone cannot see — a rebinding bug is invisible to the phone.
- A new command kind requires a coordinated `shared/` + companion change (the price of a closed enum;
  it is also what makes the wire validatable).

### Failure modes
- **Old companion (no `/v1/input`).** 404 → `DispatchError.EndpointMissing` → "update the companion"
  (distinct from "PC unreachable"); the health flag lets the phone warn proactively.
- **UIPI / no foreground window.** `executed = false` → a shown failure, never a silent success.
- **Word-selection variance per PC app (D1).** Accepted and documented; no detection in v1.

## References

- Plan: `tmp/plan-keyboard-action-engine.md` (worktree `feature/keyboard-action-engine`) (§1.3, §5, §9.1, D6) — motivated and implemented here.
- Sibling: [ADR-0026](0026-keyboard-action-routing.md) *Keyboard-Action Routing* (the app side that produces these commands).
- Extends: ADR-0016, ADR-0017, ADR-0018.
- Built on by: [ADR-0034](0034-peer-catalog.md) *Peer-Catalog Family* — adds a further additive
  payload family (the catalog DTOs) on this same protocol stack; additive reuse, not a revision.
- Code: `shared/.../protocol/{Dtos,Validations,Endpoints}.kt`, `shared/.../client/DispatchClient.kt`
  (`input()`, 404→EndpointMissing); `companion/.../platform/windows/{Win32Keyboard,Win32InputPerformer}.kt`,
  `companion/.../domain/{InputCommandService,model/KeyChord,model/DefaultChords}.kt`,
  `companion/.../server/routes/InputRoutes.kt`, `companion/.../data/SqlDelightChordMappingRepository.kt`.

## Decision History

- **2026-07-15 — Proposed (plan-scoped).** Authored alongside the keyboard-action-engine plan;
  implemented in blocks §A/§B/§B2 (green: `ProtocolCodecTest`, `ValidationsTest`, `DispatchClientTest`,
  `Win32InputPerformerTest`, `InputCommandE2ETest`, `SqlDelightChordMappingRepositoryTest`,
  `ChordMigrationSeedTest`). Awaiting promotion to `docs/decisions/NNNN-…` by the orchestrator.
- **2026-07-15 — Promoted + Accepted.** Assigned ADR-0025, moved to `docs/decisions/`; all blocks
  (§A/§B/§B2) merged to `main` with green suites.
