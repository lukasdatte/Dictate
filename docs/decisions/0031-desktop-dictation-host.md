# ADR-0031: Desktop Dictation Host — The Companion Becomes a Recording + Pipeline Host with Its Own Slim Orchestrator

**Status:** Accepted
**Subsystem:** companion, audio-pipeline, state
**Date:** 2026-07-20
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0017.** That ADR fixed the client/server roles: the phone
> records and the desktop companion is a passive **receiver** that types text into
> the focused window. This ADR extends that role model — the companion additionally
> becomes a **recording + pipeline host** in its own right. It reuses ADR-0009's
> ordered-queue semantics and ADR-0001's reducer discipline as *patterns*, not code.

> **Plain-language summary.** Until now the desktop companion could only *receive*
> a finished transcript from the phone and type it. This ADR makes the companion a
> full dictation device on its own: it records audio (via Java's built-in sound
> API), runs the shared AI pipeline (`:shared-ai`) to transcribe and post-process,
> and inserts the result — no phone involved. Rather than porting the Android app's
> large state machine (19 modules tuned for a keyboard), we write a **small, purpose-built
> orchestrator** with four concerns: recording, a pipeline queue, review, and the
> panel. The existing phone→PC dictation path (ADR-0027) is untouched; the desktop
> path is purely additive. Jargon: **orchestrator** = the single component that
> owns the UI state and turns user intents into pipeline steps; **reducer** = a pure
> function `(state, intent) → (state, effects)` with no I/O inside it.

## Research

- **Desktop-host spec** (`docs/plans/2026-07-19 - desktop-companion-v1/research/desktop-host.md`):
  §4 audio capture (javax.sound line setup, device enumeration, rolling segments,
  RMS amplitude feed, provider upload-limit); §5 the `DesktopDictationController`
  (the one dispatch door), the `DictationPhase` model, `DesktopUiState`, reducer
  purity + effects, the three `:shared-ai` calls, and the serial `JobQueue`; §6 the
  hotkey + focus-free panel; the ASCII stack §1a.0.
- **Reused ADR patterns:** ADR-0001 (modular orchestrator: pure reducers, one
  dispatch door, I/O in effects) as the *design*, not a port; ADR-0009 (ordered
  run-queue, serialized execution — "recording order = insert order"); ADR-0007
  (multi-file rolling-segment recording, adapted to the desktop); ADR-0012 (the
  post-processing conversation) and ADR-0013 (ambiguity modes + `ReviewDecision`)
  as the pipeline semantics the desktop mirrors; ADR-0018 (`TextInserter`) for
  insertion.
- **Concept / decisions:** `.../research/fragenkatalog.md` §F2 (pipeline on the
  companion), §F4 (audio recorded in the companion via javax.sound), §F5 (native
  Compose window, focus-free, <50 ms toggle); `.../research/bestandsaufnahme.md` §5
  (existing state-machine/render inventory).
- **Plan Decision Log** (`.../desktop-companion-v1.md` §3): D2 (slim own orchestrator,
  no port of `state/`), D4.2 (WAV 16 kHz mono for v1), D4.6 (the ADR-0027 phone→PC
  mode stays unchanged and parallel), D5.c (the D1a/D1b sub-split — SQLDelight parity
  before capture+pipeline).

## Context

ADR-0017 made the companion a passive dispatch target: the phone owns recording and
the pipeline, the companion only types. Feature decisions F2/F4/F5 change the ambition
— users want to dictate *at the desktop directly*, with the companion recording,
transcribing, post-processing, and inserting locally.

Two implementation questions arise:

1. **Where does the pipeline state machine come from?** The Android `state/` layer has
   19 modules shaped around IME concerns (keyboard surfaces, overlay widget, edit bar,
   IC commits). Porting it to the desktop would drag 15 irrelevant axes and their
   coupling into a context that has none of those surfaces.
2. **How does this coexist with the existing phone→PC mode (ADR-0027)?** That mode
   (phone records, companion types) must keep working unchanged.

## Decision

The companion becomes a **first-class dictation host** with a **small, purpose-built
orchestrator**, additive to the existing receiver role.

1. **`DesktopDictationController` — one dispatch door (spec §5.1, D2 / ADR-0001).**
   A slim controller holds the pure `DesktopUiState`, accepts `DictationIntent`s
   (hotkey, panel clicks, job callbacks) through a single `dispatch(intent)`, runs a
   **pure reducer** `(state, intent) → (state, List<Effect>)`, executes the effects
   (capture, the three `:shared-ai` calls, insertion, persistence, panel show/hide),
   and exposes `state: StateFlow<DesktopUiState>` to the Compose panel. It is a
   **new implementation** of the ADR-0001 rules, **not** a port of Android `state/`.

2. **Four concerns, not nineteen (D2).** `DesktopUiState` has exactly four axes:
   recording, pipeline (queue), review, and panel. The `DictationPhase` state model
   (IDLE → RECORDING → TRANSCRIBING → POST_PROCESSING → INSERTED/REVIEW/FAILED/CANCELLED)
   maps onto the **existing** persisted `sessions.status` values — no new status
   vocabulary, keeping Room/SQLDelight parity.

3. **Audio capture via javax.sound (spec §4, F4 / D4.2).** `AudioCaptureService`
   records **WAV 16 kHz mono** for v1 (Opus/OGG deferred); it enumerates and persists
   the input device; it uses **rolling segments** (the ADR-0007 idea, adapted) and
   feeds an **RMS amplitude** stream to the UI. The provider upload-limit handling is
   a remaining D4.2 task.

4. **Pipeline = three `:shared-ai` calls (spec §5.5).** Transcription
   (`AIOrchestrator.transcribe`), post-processing as a persisted conversation
   (`ConversationTurnBuilder` + `AIOrchestrator.converse`, with `final_output_text`
   persisted in the same transaction — ADR-0013 §3 crash-resilience), then
   `ReviewDecision.decide` for the INSERT-vs-REVIEW verdict (ADR-0013). Insertion goes
   through the existing `TextInserter` (ADR-0018).

5. **Serial job queue, ADR-0009 semantics simplified (spec §5.6).** One worker thread,
   FIFO, one job at a time; a second hotkey during a running pipeline **enqueues**
   (dedup by sessionId), never discards — recording order = insert order (ADR-0009
   "Positive"). Unlike Android, there is **no** parallel second recording in v1 (the
   desktop has one capture line; the next hotkey follows `stop`). The re-dictate
   continuation job runs through the same queue.

6. **Transitional config, then Profile (spec §5.1 note).** D1 runs against a
   transitional `AiConfig` from `CompanionSettings`; D3 later gates on the Block-C
   Profile (`ActiveProfileSource`). `AiConfig` is the `:shared-ai` port
   (ADR-0028).

7. **Additive to ADR-0027 (D4.6).** The phone-records→PC-types mode is untouched and
   runs in parallel. The desktop host is a new capability, not a replacement.

8. **Chunk order (D5.c).** SQLDelight full parity + `received_texts` retirement (D1a,
   the sibling ADR-0035) lands **before** capture + pipeline
   (D1b), isolating the highest-regression-risk work in its own audited chunk.

## Alternatives Considered

1. **Port the Android `state/` orchestrator to the desktop.** Maximum code reuse.
   Rejected (D2): 19 modules are tuned for IME axes (keyboard surfaces, overlay,
   edit bar, IC commits) the desktop does not have; porting drags irrelevant
   complexity and coupling. Sharing the *pattern* (ADR-0001) while writing a
   four-axis reducer is far more maintainable.
2. **Proxy recording to a paired phone and reuse its pipeline.** Rejected per F2/F4:
   it makes desktop dictation require a phone, defeats the standalone goal, and adds
   a network round-trip; the companion has javax.sound and can host `:shared-ai`.
3. **Opus/OGG capture for smaller uploads in v1.** Rejected (D4.2): WAV 16 kHz mono is
   simple, universally accepted by the providers, and adequate for v1; codec work is
   deferred until a real upload-size need appears.
4. **Allow a parallel second recording (Android-style).** Rejected for v1: the desktop
   has a single capture line and a hotkey workflow where the user stops before starting
   again; a strict FIFO serial queue is simpler and matches the interaction.
5. **A new `sessions.status` vocabulary for desktop phases.** Rejected: it would break
   Room/SQLDelight parity. Desktop phases map onto the existing status values.

## Consequences

**Positive:**
- The companion is a standalone dictation device — no phone required — while the phone
  path stays intact (additive).
- The slim four-axis orchestrator is far more maintainable than a ported 19-module
  machine, yet inherits the proven ADR-0001 discipline (pure reducers, one door, I/O
  in effects).
- Reusing `:shared-ai` means transcription + post-processing behave identically to the
  phone; reusing the existing `sessions.status` keeps persistence parity.
- The serial ADR-0009 queue guarantees recording order = insert order with no
  concurrency footguns.

**Negative:**
- A second, independent orchestrator to maintain — the desktop and Android state
  machines will evolve separately, and a shared pipeline-semantics change (e.g. a new
  ambiguity behaviour) must be applied in two places.
- javax.sound capture, device enumeration, and rolling segments are new desktop code
  with their own platform quirks (line availability, device hot-swap).
- No parallel recording in v1 is a deliberate capability gap versus Android.

**Failure Modes:**
- **I/O in a reducer would break the pure-reducer invariant** — time/UUID and all
  capture/AI/persistence must stay in effect handlers (`clock` + `UUID` inside effects).
  A reducer that reaches for `System.currentTimeMillis()` makes state non-reproducible;
  the checklist (spec §1a.5) is the guard.
- **A javax.sound line held open across a failed pipeline leaks the microphone** — the
  `StopCapture` effect must run on every terminal (including FAILED/CANCELLED), or the
  next recording finds the line busy.
- **Post-processing must persist `final_output_text` in the same transaction as the
  turn** (ADR-0013 §3); skipping it means a crash mid-review loses the recoverable
  text — the Android bug this invariant was created to prevent, re-introduced on the
  desktop.
- **Queue dedup keyed on sessionId** — a re-dispatch that reuses a sessionId could be
  swallowed as a duplicate; the continuation job must carry its own identity.

## References

- **Related Plan:** [desktop-companion-v1](../plans/2026-07-19%20-%20desktop-companion-v1/desktop-companion-v1.md)
  — §3 (F2/F4/F5, D2, D4.2, D4.6, D5.c), §5 Block D. Motivates and is implemented by this ADR.
- **Spec:** `docs/plans/2026-07-19 - desktop-companion-v1/research/desktop-host.md`
  (§4 capture, §5 orchestrator + queue, §6 hotkey/panel).
- **Concept:** `.../research/fragenkatalog.md` §F2/§F4/§F5; `.../research/bestandsaufnahme.md` §5.
- **Related ADRs:**
  - ADR-0017 — extends the role model (companion is now also a recording/pipeline
    host, not only a receiver); a Decision-History note is added there at promotion.
  - ADR-0001 — the modular-orchestrator discipline reused as a pattern (new impl, not a port).
  - ADR-0009 — the ordered serialized run-queue semantics reused (simplified).
  - ADR-0007 — the multi-file rolling-segment recording idea, adapted to the desktop.
  - ADR-0012 / ADR-0013 — the post-processing conversation and review/ambiguity
    semantics the desktop pipeline mirrors (`final_output_text` invariant, `ReviewDecision`).
  - ADR-0018 — the `TextInserter` used for insertion.
  - ADR-0032 — the panel/hotkey surface this host drives.
  - ADR-0033 — the desktop review mode that consumes the REVIEW verdict.
  - ADR-0035 — the D1a SQLDelight parity that lands before this host's D1b.

## Decision History

### 2026-07-20 — Initial proposal (plan-scoped)

**Trigger:** Feature decisions F2/F4/F5 (the companion records and runs the pipeline
itself) required a state machine and capture design; the desktop-host spec resolved the
orchestrator shape, the audio format, and the queue semantics.

**Before:** The companion was a passive dispatch receiver (ADR-0017): the phone recorded
and ran the pipeline, the companion only typed. There was no desktop recording or
pipeline code.

**After:** A `DesktopDictationController` (one dispatch door, pure reducer, four axes:
recording/pipeline/review/panel) driving javax.sound WAV-16 kHz-mono capture, the three
`:shared-ai` pipeline calls with the ADR-0013 `final_output_text` invariant, and a serial
ADR-0009-style queue — additive to the unchanged ADR-0027 phone→PC mode, and sequenced
after the D1a SQLDelight parity chunk (D5.c).

**Reasoning:** A slim purpose-built orchestrator (ADR-0001 pattern, not a port) avoids
dragging 19 IME-shaped modules into a context without those surfaces, while reusing
`:shared-ai` and the existing `sessions.status` keeps AI behaviour and persistence in
parity with the phone. WAV 16 kHz mono and a strict serial queue are the simplest choices
that meet v1; the phone path stays untouched.

### 2026-07-20 — Promoted and accepted

**Trigger:** Chunk F1 (Block F) of the desktop-companion-v1 plan — blocks A–E are
implemented; the plan-scoped draft is promoted to a numbered, accepted ADR before
plan archival (§2 criterion 9).

**Before:** Plan-scoped draft `adrs/adr-desktop-dictation-host.md` with an `NNNN` placeholder and
`Proposed (plan-scoped — pending promotion)` status; sibling ADRs referenced by slug.

**After:** `docs/decisions/0031-desktop-dictation-host.md`, Status **Accepted**, indexed in
`docs/decisions/README.md`; sibling cross-references resolved to their assigned ADR
numbers. The reciprocal role-extension note was added to ADR-0017.

**Reasoning:** The decision is active in the codebase across the implemented blocks;
promotion makes it a binding, navigable ADR with bidirectional cross-links.
