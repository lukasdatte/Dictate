---
date: 2026-07-14
author: Lukas + Claude Code
status: Accepted
context: Cross-cutting overview of the Windows-Dispatch subsystem — how a dictated text reaches a paired PC, across the app, the shared protocol module, and the desktop companion.
related-plan: the windows-dispatch work package plan (pending archival to docs/plans/)
related-adrs: ADR-0015, ADR-0016, ADR-0017, ADR-0018, ADR-0019, ADR-0020
---

# Windows-Dispatch — Subsystem Overview

Windows-Dispatch lets a completed dictation be **sent to a paired Windows PC**
instead of (or after) being committed into the Android host field. The phone is a
pure HTTP client; a small **Compose Desktop companion** is the only server, reachable
over the user's Tailscale tailnet. A dictated text is typed into the active Windows
window via `Ctrl+V`, and the phone's full history is lazily mirrored onto the PC as a
derived archive.

This document is the map. The load-bearing decisions live in six ADRs (ADR-0015 …
ADR-0020); this overview is the orientation guide that ties them together and points
at the code.

## 1. Vision and Motivation

### 1.1 Why this subsystem exists

Dictation is fastest on the phone, but the text often belongs on the desktop — an
email, an IDE, a chat. Copy-paste across devices is friction. Windows-Dispatch closes
that gap: dictate on the phone, the words appear at the caret on the PC.

### 1.2 What problem it solves

- **Cross-device delivery** without a cloud round-trip: the transport is the user's
  own tailnet (WireGuard-encrypted, no port-forwarding, no third party).
- **No lost text.** A PC that is offline, unauthenticated, or cannot type never drops
  the dictation — it falls back to the existing "Tap to paste" pending part on the
  phone, or to the PC clipboard with a visible hint.
- **One primitive, not four.** Auto-send has two terminal producers (the IME and the
  headless sink) and a per-row history button, but exactly one dispatch code path — so
  the three call sites can never drift apart (ADR-0019).

### 1.3 Discarded alternatives

- **KMP or a separate repo** for the companion — rejected: protocol drift between two
  repos is exactly what a shared wire module prevents (ADR-0015).
- **A second confirmation channel / ACK protocol** — rejected: the HTTP response *is*
  the delivery confirmation (ADR-0017). A timeout is classified as unreachable, never
  as delivered.
- **Overloading `target_app_package` with `"windows:<id>"`** — rejected: a Windows
  dispatch writes into no Android package; a new `target_device_id` column carries the
  target identity instead (ADR-0019 / `docs/DATABASE-PATTERNS.md`).

### 1.4 What it buys us

1. Dictate-to-PC over a private, encrypted path with no server infrastructure.
2. A conservative, visible failure model — a broken PC degrades to a pending part, it
   never blocks or silently reorders dictation.
3. A derived, searchable copy of the full dictation history on the PC (ADR-0020).

## 1a. Architecture Walkthrough

### 1a.0 Module topology (ADR-0015)

```
┌─────────────────────────────────────────────────────────────────────┐
│  :app  (Android, Kotlin/Java, jvmTarget 1.8)                        │
│  net.devemperor.dictate.windows/  — the dispatch domain (pure)      │
│    WindowsDispatchCoordinator · WindowsDispatchService ·            │
│    WindowsAutoSend · AndroidSyncSource · SessionEntityMapper        │
│  state/modules/WindowsDispatchModule.kt — the in-flight axis        │
│  settings/WindowsPairingActivity.java   — QR / manual pairing       │
└───────────────────────────────┬─────────────────────────────────────┘
                                 │ depends on (blocking OkHttp client)
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│  :shared  (pure kotlin("jvm"), jvmTarget 1.8, Android-free,         │
│            NO Ktor / NO coroutines — SharedPurityTest enforces it)  │
│  protocol/  DTOs + Konform Validation + ProtocolCodec + Endpoints   │
│  client/    DispatchClient        transport/ OkHttpDispatchTransport │
│  auth/      PairingUri · Secrets · AuthHeaders                       │
│  sync/      SyncClient · Cursor · SyncSource                         │
└───────────────────────────────┬─────────────────────────────────────┘
                                 │ same wire types, both sides
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│  :companion  (Compose Desktop, JVM 17)                              │
│  server/    Ktor CIO — the ONLY server, bound to the tailnet        │
│  domain/    PairingService · AuthService · DispatchService          │
│  platform/  TextInserter (Win32 SendInput / Noop on Linux) ·        │
│             Autostart (HKCU Run-key / Noop)                          │
│  data/      SQLDelight history/device/settings repositories         │
└─────────────────────────────────────────────────────────────────────┘
```

The `:shared` module is the single wire authority: DTOs, their Konform validations, and
the endpoint constants live there once, so client and server can never disagree
(ADR-0016). It is deliberately Android-free and Ktor-free so the app's Kotlin/coroutine
lines stay untouched; the client is blocking OkHttp on a dedicated executor.

### 1a.1 The five endpoints (ADR-0017)

All under `/v1`, `PROTOCOL_VERSION = 1` carried in every request/response; a major
mismatch → `400 PROTOCOL_VERSION_UNSUPPORTED`. Auth is a bearer device-secret plus an
`X-Dictate-Device` id header; the server stores only the secret's SHA-256 and compares
constant-time.

| Endpoint | Method | Purpose |
|---|---|---|
| `/v1/pair` | POST | Exchange a one-time 120 s pairing token for a long-lived 256-bit device secret. Token burned on success (`409` on reuse). |
| `/v1/dispatch` | POST | Deliver one session's final text. `200 delivered=true` = on the PC; `401` unauthorized; `400` validation; `503` insertion failed. |
| `/v1/health` | GET | Liveness + `canInsert` (false on Linux/macOS → the phone warns at pairing). |
| `/v1/sync/cursor` | GET | The PC's receipt watermark `Cursor(lastCreatedAt, lastSessionId)`. |
| `/v1/sync` | POST | A page of idempotent `SessionUpsert`s (≤ 200), phone-authoritative. |

### 1a.2 The dispatch state machine (ADR-0019)

A dispatch is **not** a pipeline job. When auto-send fires, the terminal `PipelineDone`
carries `awaitingDispatch=true`: the ADR-0009 run-queue drains normally and the FSM goes
`Idle`, but neither `MarkSessionInserted` nor `AddPendingInsertSession` fires. The
in-flight state lives only in the `windowsDispatch` axis, resolved by a non-terminal,
guard-free `WindowsDispatchAction` family.

```
                       coordinator.dispatch(sid, text, …)
                                     │
                        ┌────────────▼────────────┐
                        │  no target → Failed(     │
                        │   WINDOWS_UNAUTHORIZED)   │
                        └────────────┬────────────┘
                                     │ target present
                                     ▼
                            Action: Started ────────────► axis: inFlight += sid
                                     │ (HTTP on the dispatch executor)
                 ┌───────────────────┼────────────────────┐
                 ▼                   ▼                     ▼
          200 delivered       200 CLIPBOARD_ONLY     4xx/5xx/timeout
                 │                   │                     │
             Succeeded          Succeeded              Failed(kind)
                 │              (+ INFO notice)            │
        ┌────────┴─────────┐        │            surfacedAsPending?
        │ surfacedAsPending?│       │             ├─ yes → no-op (part exists)
        │  ├ yes → Dismiss   │  acknowledge        └─ no  → SurfacePendingPart
        │  │  (remove + ack) │  (markInserted)         (AddOne, dedup by sid)
        │  └ no  → MarkAck    │
        └──────────┬─────────┘
                   ▼
          axis: inFlight -= sid ; markInserted once ; sync(target) fire-and-forget
```

Two safety nets keep exactly-one-acknowledge / exactly-one-part per session:

- **Teardown cascade.** If the IME view disappears mid-dispatch, every in-flight text is
  surfaced as a pending part *and* the axis records `MarkSurfaced`; a later `Succeeded`
  then `Dismiss`es it (removes + acknowledges), a later `Failed` adds no second part.
  The dispatch itself keeps running — the coordinator lives in the service, not the IME.
- **Process death mid-dispatch.** The session is `COMPLETED` / `inserted_at NULL` /
  `final_output_text` written in-transaction (ADR-0013), so the cold-boot
  `findPendingInsertion` recovery surfaces it once — the same durable net every headless
  completion already uses (ADR-0011). Conservative: one extra part beats a lost text.

### 1a.3 The three dispatch triggers, one primitive

`WindowsDispatchCoordinator.dispatch(...)` is the only method that starts a dispatch,
called from three sites, implemented once:

| Trigger | Site | `acknowledgeOnSuccess` |
|---|---|---|
| IME seam | `DictateInputMethodService.onPipelineCompleted` else-branch | `true` (fresh session) |
| Headless sink | `DictatePipelineService` `setHeadlessTerminalSink.onCompleted` | `true` (fresh session) |
| History row | `DictateInputMethodService.onKeyboardHistorySendClicked` | `pending` (re-send parity) |
| Review Insert (auto mode) | `onReviewInsertClicked` | `false` (Insert already acknowledges) |

The audit is written once, by `SessionManager.logTextInsertion` (the single
`text_insertions` writer) with `insertion_method = WINDOWS_DISPATCH` + `target_device_id`
— **not** `InsertionAudit.record`, which needs an InputConnection absent in the headless
case (ADR-0019).

## 2. Properties this architecture guarantees

1. **Exactly-once terminal dispatch per session per process** — the
   `PipelineTerminalDispatchGuard` is consumed by the bridge before anything
   Windows-specific runs; auto-send adds no fourth producer (ADR-0011 / ADR-0019).
2. **No lost text** — every non-`Delivered` outcome (unreachable, unauthorized, failed,
   process death) resolves to a recoverable pending part; `CLIPBOARD_ONLY` resolves to a
   PC-clipboard copy with a visible hint.
3. **One wire authority** — DTOs + validations live once in `:shared`; neither side can
   bypass `ProtocolCodec` (ADR-0016).
4. **Buildable + testable on Linux** — all Win32 lives behind `TextInserter`; the
   companion runs (with `canInsert=false`) and its full suite is green on the Linux VM
   (ADR-0018).

## 3. Code pointers

- **Dispatch primitive:** `app/src/main/java/net/devemperor/dictate/windows/WindowsDispatchCoordinator.kt`,
  `WindowsDispatchService.kt`, `WindowsAutoSend.kt`, `SessionEntityMapper.kt`
- **State axis:** `app/src/main/java/net/devemperor/dictate/state/modules/WindowsDispatchModule.kt`;
  the `awaitingDispatch` arm in `state/modules/PipelineModule.kt`
- **Producers:** `core/DictateInputMethodService.java` (`isWindowsAutoSendActive`,
  `onKeyboardHistorySendClicked`, `onReviewInsertClicked`) and
  `core/DictatePipelineService.kt` (`setHeadlessTerminalSink`, the app-start sync)
- **Pairing UI:** `app/src/main/java/net/devemperor/dictate/settings/WindowsPairingActivity.java`
- **Shared wire:** `shared/src/main/kotlin/net/devemperor/dictate/shared/` (protocol, client,
  transport, auth, sync)
- **Companion:** `companion/src/main/kotlin/net/devemperor/dictate/companion/` (server, domain,
  platform, data)
- **Sync:** `windows/AndroidSyncSource.kt` + `SessionDao.sessionsAfterCursor` /
  `sessionsFromStart`; `shared/.../sync/SyncClient.kt`

## 4. Information Gaps

1. **Multiple PC targets + an in-keyboard target picker** — the data structures
   (`deviceId`, multiple server-side `devices` rows) are already multi-capable, but V1
   dispatches to exactly one target. Owner: a follow-up package. Fallback: one target.
2. **Deletion propagation in sync** — the PC is an archive, not a mirror; a phone-side
   delete does not remove the PC copy in V1. Owner: a follow-up (`deleted_at`, additive,
   no protocol bump). Fallback: manual PC-side deletion.
3. **MSI code-signing** — V1 ships unsigned; SmartScreen warns. Owner: a follow-up.
4. **CI** — the repo has no CI; a `windows-latest` `packageMsi` job is a separate work
   package. Fallback: the manual Windows device checklist in the runbook.
5. **Review×auto-send process-death durability** — the review-panel "Insert" → PC path
   acknowledges eagerly (`markInserted` before the async send, coordinator
   `acknowledgeOnSuccess = false`), so on a send *failure* the text is surfaced only as an
   in-memory pending part; with `inserted_at` already set, a process death in that window is
   not recovered by cold-boot `findPendingInsertion` (the other three producers hold
   `inserted_at` NULL until `Succeeded` and are recoverable). No data loss — the text stays in
   the DB (`final_output_text`) and is re-sendable from the history row. Owner: a follow-up —
   defer the review acknowledge until the dispatch resolves (an Insert-without-ack variant),
   state-machine surgery over ADR-0013 + ADR-0019. See ADR-0019 Decision History (2026-07-14).

## 5. References

- **ADR-0015** — Companion Monorepo Topology (`shared/` + `companion/`)
- **ADR-0016** — Wire Protocol (typed DTOs, Konform, versioning)
- **ADR-0017** — Client/Server Roles, Transport, Pairing over Tailscale
- **ADR-0018** — Windows Text Insertion behind a Port (`SendInput`/JNA)
- **ADR-0019** — Auto-Send as a Third Terminal Pipeline Outcome
- **ADR-0020** — Lazy Cursor Sync
- **Runbook:** `docs/runbooks/companion-windows-release.md` (MSI build + the 10-point
  Windows device checklist)
- **Database:** `docs/DATABASE-PATTERNS.md` (`insertion_method` Double-Enum + `target_device_id`)
- **Cooperating ADRs:** ADR-0009 (run-queue), ADR-0011 (headless fallback), ADR-0013
  (review panel), ADR-0014 (history panel)
