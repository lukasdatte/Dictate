# ADR-0018: Windows Text Insertion Behind a Port — Clipboard + SendInput(Ctrl+V) via JNA

**Status:** Accepted
**Subsystem:** companion, platform
**Date:** 2026-07-14
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0017.** That ADR owns the client/server role split and the
> rule that the HTTP 200 *is* the delivery confirmation; this ADR owns the one operation
> that a 200 confirms — placing the dictated text into the PC — and the port that hides
> its OS-specific half.

## Research

The insertion problem was worked through against the built companion code during
Block 1 of the windows-dispatch package and captured in the Windows verification
checklist (`tmp/plan-windows-dispatch.md` §"Nur auf einem echten Windows-Rechner",
items 1–4). The load-bearing findings that shaped this decision:

- **UIPI is silent.** A non-elevated process injecting into an elevated window does
  not get an error — `SendInput` accepts *fewer* events than passed and returns that
  count. The return value `< nInputs` is the only signal, and it must be checked or the
  companion reports a success for a Ctrl+V that never happened
  (`companion/.../platform/windows/Win32Keyboard.kt:37-50`, checklist item 4).
- **The insertion policy is plain Kotlin and fully testable on the Linux VM.** Only the
  two `user32.dll` calls sit behind a seam (`Win32Keyboard`); every branch of the policy
  — failed clipboard write, missing foreground window, UIPI degradation, the restore that
  must not clobber a fresh copy — is exercised by `Win32TextInserterTest.kt:34-129` on
  Linux without a Windows box.
- **A non-text clipboard cannot be restored.** A `String`-shaped port cannot put an image
  or file list back, so the previous content is only restored when it was text and only if
  the clipboard still holds our text (`Win32TextInserter.kt:65-69`; checklist item 2;
  `FakeClipboard.kt:5-11` models the null-content case deliberately).
- **The companion must run and be startable on this Linux dev VM.** The fallback
  `NoopTextInserter` reports `available = false`, which surfaces as `canInsert = false`
  on `/v1/health` so the phone warns *at pairing time*
  (`NoopTextInserter.kt:16-21`, `HealthService.kt:9-23`).

## Context

The companion's single job on the network is to take a finished dictation and put it
where the user is typing on their PC. Windows exposes no clean "insert text into the
focused control" API for arbitrary applications; the portable, universally-understood
mechanism is *clipboard + Ctrl+V*, which every comparable tool uses.

Two constraints made a naive implementation unacceptable:

1. **Development happens on a Linux VM** (`vm-dev2`) with no `user32.dll`. If insertion
   were wired directly into the server, the companion would not compile or run here, and
   none of its logic could be tested without a Windows machine.
2. **The HTTP 200 is the only delivery confirmation** (ADR-0017). There is no second
   acknowledgement channel, so "the text was placed" and "the text was not placed" must be
   distinguished precisely — and, critically, a text that reached the clipboard but was not
   typed (no focus window, an elevated target, or the Linux fallback) is *not* a loss and
   must not be reported as one.

## Decision

Text insertion lives behind a port:

```kotlin
interface TextInserter {
    fun insert(text: String): InsertionOutcome
    val available: Boolean   // false → /v1/health reports canInsert = false
}

enum class InsertionOutcome { TYPED_CTRL_V, CLIPBOARD_ONLY, FAILED }
```

The Windows implementation (`Win32TextInserter`, JNA 5.19.1) runs this policy:

1. `previous = clipboard.readText()` (null = the clipboard held non-text).
2. `clipboard.writeText(text)` — if it fails → `FAILED`.
3. No foreground window? → `CLIPBOARD_ONLY`.
4. `User32.SendInput` with four `KEYBDINPUT` events (Ctrl↓, V↓, V↑, Ctrl↑). If Windows
   accepted fewer than 4 → `CLIPBOARD_ONLY` (UIPI blocked us).
5. Schedule a clipboard restore after a short delay, but only restore if the clipboard
   still holds our text → `TYPED_CTRL_V`.

**Three outcomes, two meanings.** `TYPED_CTRL_V` and `CLIPBOARD_ONLY` are *both success*:
the text is on the PC and reachable there, so the dispatch route answers **200**. Only
`FAILED` — nothing on this PC holds the text — is a failure, and it becomes a
**503 `INSERTION_FAILED`** (`DispatchService.kt:34-36`,
`StatusPagesSetup.kt:43-44`). `CLIPBOARD_ONLY` is deliberately *not* a failure: the text
was not lost, only not typed (no focus window, a UIPI block, or the companion running on
Linux). The wire enum carries only the two success outcomes; `FAILED` never travels the
wire as an outcome — `toWire()` throws if asked, precisely so a caller cannot skip the 503
arm and report a delivery (`InsertionOutcome.kt:25-34`).

**The caller must make the difference visible.** A `CLIPBOARD_ONLY` delivery is a 200, but
the user must not believe the text was typed. Surfacing that distinction on the phone is
ADR-0019's responsibility (an `INFO` InfoBar notice for `CLIPBOARD_ONLY`, a pending-part
fallback for `FAILED`).

**All Win32 lives only behind the port.** On every non-Windows OS — and in every test —
`NoopTextInserter` / `NoopAutostart` take over, so the companion stays buildable, startable,
and fully testable on the Linux VM. `/v1/health` then reports `canInsert = false`, and the
phone warns while pairing rather than letting the user discover it through a text that lands
only in a (non-existent) clipboard.

**The elevated-window boundary is an operational limit, not a bug.** `SendInput` cannot
type into an elevated window from a non-elevated sender — this is UIPI, by design. Running
the companion elevated would "fix" it and is rejected: it would hand every paired phone the
ability to type into an administrator's session. The `SendInput` return value `< nInputs` is
the *only* signal that this happened and **must** be checked; ignoring it reports a silent
success for a paste that never landed.

## Alternatives Considered

1. **Type the text character-by-character with `KEYEVENTF_UNICODE`.** Would avoid touching
   the clipboard at all. Rejected: it takes seconds for a long dictation, drops characters in
   fields with auto-complete or an active IME, and does not actually leave the clipboard
   untouched anyway. Clipboard + Ctrl+V is what every comparable tool does
   (`Win32Keyboard.kt:59-62`).

2. **Insert directly in the server route, no port.** Simpler by one indirection. Rejected:
   the companion would not compile or run on the Linux dev VM, and none of the insertion
   policy — where all the real bugs live (UIPI, missing focus, restore races) — could be
   tested without a Windows machine. The port is the single thing that keeps the package
   developable and testable off-Windows.

3. **Two success outcomes collapsed into one boolean `delivered`.** Would make the wire
   contract smaller. Rejected: the phone needs to tell the user *how* the text arrived —
   "typed into your window" vs "copied to your clipboard, paste it yourself". Collapsing the
   two would either lie to the user (claim it was typed) or under-report (claim failure for a
   text that is one Ctrl+V away).

4. **Treat `CLIPBOARD_ONLY` as a failure (503 + pending part).** Conservative — the phone
   would always keep a copy. Rejected: it would spam the user with pending-part ghosts every
   time the desktop had no focused window, and it would misrepresent a Linux companion (which
   genuinely has the text on its clipboard when one is wired) as broken. The text *is*
   reachable; calling that a failure is wrong.

5. **Run the companion elevated so UIPI never blocks a paste.** Rejected on security
   grounds: it would let any paired phone inject input into an administrator session. The
   `CLIPBOARD_ONLY` degradation is the correct, safe answer to an elevated target.

## Consequences

**Positive:**
- The port keeps the companion **buildable, startable, and fully testable on non-Windows**
  (the Linux dev VM). Every branch of the insertion policy is a Linux unit test
  (`Win32TextInserterTest.kt`); only the two raw `user32.dll` calls remain on the
  Windows-only checklist.
- One insert path for both live dispatch and the history "insert again" button — one set of
  Win32 gotchas, one place to fix them (`DispatchService.reinsert`, `DispatchService.kt:70-75`).
- The three-outcome enum lets the phone tell the user precisely what happened, and the
  `canInsert` health flag warns *before* the first dictation instead of after a silent
  clipboard-only landing.

**Negative:**
- One extra indirection: every insertion goes through the `TextInserter` port and (on
  Windows) the `Win32Keyboard` seam. A reader tracing a paste has two hops instead of one —
  accepted as the price of off-Windows testability.
- The companion runs on the *previous* library line by policy (JNA 5.19.1 chosen against
  the Kotlin-version ceiling of ADR-0015); newer JNA is deliberately not adopted.
- V1 supports Windows insertion only; other OSes get a Noop that cannot type. Cross-platform
  insertion is out of scope.

**Failure Modes:**
- **Unchecked `SendInput` return = silent success.** If a future change ignores the accepted-
  event count and returns `TYPED_CTRL_V` unconditionally, a UIPI-blocked paste (elevated
  target window) is reported as delivered and the user stares at an empty window believing the
  text arrived. This is the exact bug the whole package exists to prevent — the count check at
  `Win32TextInserter.kt:57-63` and its test at `Win32TextInserterTest.kt:98-111` guard it.
- **A non-text previous clipboard cannot be restored.** If the user had an image or file list
  on the clipboard, the dictated text stays there after insertion — a `String`-shaped port
  cannot put the original back, and clobbering it with an empty string would be worse. This is
  documented, checklist item 2, and modelled by `FakeClipboard(content = null)`.
- **An elevated target window blocks Ctrl+V.** Admin PowerShell, Task Manager, and UAC prompts
  reject injected input from the non-elevated companion. Expected behaviour: `CLIPBOARD_ONLY`,
  surfaced to the user — never a silent success and never a 503.
- **The focus race.** Between checking the foreground window and injecting, focus can move;
  `SendInput` delivers to whatever has focus *at delivery time*, not to a captured handle.
  Unpreventable and accepted (`Win32TextInserter.kt` KDoc).

## References

- **Related Plan:** windows-dispatch plan (`tmp/plan-windows-dispatch.md`, ADR row §3 line 485,
  Windows checklist §"Nur auf einem echten Windows-Rechner" — pending archival to
  `docs/plans/`).
- **Related ADRs:**
  - ADR-0017 — Client/server roles and transport; owns the rule that the HTTP 200 *is* the
    delivery confirmation. The dispatch route (`DispatchRoutes.kt`) invokes this port and turns
    its outcome into that 200 or a 503.
  - ADR-0019 — Auto-send as a terminal pipeline outcome; the Android side surfaces
    `CLIPBOARD_ONLY` as a dismissible `INFO` InfoBar notice and treats `FAILED` (503) as the
    pending-part fallback.
  - ADR-0016 — Wire protocol; defines the `InsertionOutcomeWire` enum this domain enum is a
    superset of, and the `ErrorEnvelope` carried by the 503.
- **Implementation:**
  - Port: `companion/src/main/kotlin/net/devemperor/dictate/companion/domain/port/TextInserter.kt`
  - Domain outcome + wire mapping: `.../domain/model/InsertionOutcome.kt`
  - Windows impl: `.../platform/windows/Win32TextInserter.kt`, `.../platform/windows/Win32Keyboard.kt`
  - Linux/test fallback: `.../platform/fallback/NoopTextInserter.kt`
  - Dispatch: `.../domain/DispatchService.kt`, `.../server/routes/DispatchRoutes.kt`,
    `.../server/plugins/StatusPagesSetup.kt`
  - Health / `canInsert`: `.../domain/HealthService.kt`
- **Test suite:**
  `companion/src/test/kotlin/net/devemperor/dictate/companion/platform/Win32TextInserterTest.kt`,
  fakes `.../fakes/FakeTextInserter.kt`, `.../fakes/FakeClipboard.kt`.

## Decision History

### 2026-07-14 — Initial proposal

**Trigger:** The windows-dispatch package (Blocks 1–3, done and green) needed the companion to
place dictated text on a Windows PC while remaining developable and testable on the Linux dev
VM, and to distinguish "text is on the PC and reachable" from "text was lost" so the phone
could react correctly to a single HTTP status.

**Before:** No insertion mechanism existed; the companion had no way to put text into the
user's focused window, and no contract for what a partial success (clipboard set but not typed)
should mean to the caller.

**After:** Insertion lives behind a `TextInserter` port returning a three-value
`InsertionOutcome`. `Win32TextInserter` implements the clipboard + `SendInput(Ctrl+V)` policy
via JNA, with the two raw `user32.dll` calls isolated behind the `Win32Keyboard` seam;
`NoopTextInserter` keeps the companion startable and fully testable off-Windows.
`TYPED_CTRL_V` and `CLIPBOARD_ONLY` are both success (200); only `FAILED` is a failure (503
`INSERTION_FAILED`). The UIPI boundary — a non-elevated sender cannot type into an elevated
window, signalled only by `SendInput` returning `< nInputs` — is documented as an operational
limit and enforced by degrading to `CLIPBOARD_ONLY`.

**Reasoning:** Clipboard + Ctrl+V is the portable, fast, correct insertion mechanism (character-
by-character typing was rejected as slow and lossy). The port is the single thing that keeps the
package developable and testable on Linux, so it outweighs the one extra indirection. Three
outcomes rather than a boolean let the phone tell the user precisely how the text arrived and
avoid both lying (claiming a typed success) and over-reporting (calling a reachable clipboard
text a failure). Running elevated to defeat UIPI was rejected as a security hazard; the
`CLIPBOARD_ONLY` degradation is the safe answer.
