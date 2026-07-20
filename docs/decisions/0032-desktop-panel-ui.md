# ADR-0032: Desktop Mini-Panel — A Frameless, Always-on-Top, Focus-Free Compose Surface with a Ported Global Hotkey and a Focus-Restore Fallback

**Status:** Accepted
**Subsystem:** companion, ui
**Date:** 2026-07-20
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0004 and ADR-0027.** ADR-0004 established the multi-backend
> render model on Android and ADR-0027 added a third render host (the PC-Dictation
> Activity) reusing it. This ADR is the **desktop-side analogue** of that render-host
> idea: a warm, reusable UI surface driven by a single state stream — here a native
> Compose window rather than an Android view.

> **Plain-language summary.** The desktop dictation experience is a small floating
> **panel** that pops up on a global keyboard shortcut, shows recording/review, and
> disappears again — like a system-wide dictation HUD. Three things make it work:
> it is a **frameless, always-on-top** Compose window; it ideally takes **no keyboard
> focus** so the app you were typing in stays focused (so inserted text lands there);
> and a **global hotkey** toggles it. Because "never take focus" is not guaranteed on
> every OS, there is an equal-status **fallback**: remember which window was in front
> when the hotkey fired and restore it before inserting. After a dictation finishes,
> the text is **auto-inserted** by default (confirmation is an opt-in setting).
> Jargon: **focus-free** = the window is visible and interactive but does not steal
> keyboard focus from the app underneath; **`WS_EX_NOACTIVATE`** = a Win32 window
> style that asks the OS not to activate (focus) a window when shown.

## Research

- **Desktop-host spec** (`docs/plans/2026-07-19 - desktop-companion-v1/research/desktop-host.md`):
  §6 the `GlobalHotkey` port (Win32 `RegisterHotKey` on a message-loop thread; Noop
  fallback for Linux/macOS), the focus-free panel, and the `HotkeyCombo` config;
  §5.3 `DesktopUiState` (the panel is a pure Compose state consumer); §1a.1 "the warm
  window surface".
- **Reused patterns:** ADR-0004 (LayoutCatalog / multi-backend rendering) and ADR-0027
  (the third render host reusing one state stream) as the design analogue; ADR-0018
  (`TextInserter` / `available`) and the companion's `PlatformModule.detect()` for the
  port-behind-a-capability pattern the hotkey follows.
- **Concept / decisions:** `.../research/fragenkatalog.md` §F5 (native Compose window,
  frameless, always-on-top, <50 ms toggle), §F6 (Windows-first for hotkey/insertion,
  everything behind ports, Linux dogfooding with clipboard + button), §F19 (recording
  core 1:1 in Compose; management screens own layouts), §F21 (focus-free panel +
  auto-insert, confirmation as fallback setting); `.../research/konzept-skizze.md` §3
  (Compose-Desktop UI trade-off, decided per F1).
- **Plan Decision Log** (`.../desktop-companion-v1.md` §3): D2 (hotkey + focus-free
  panel), D4.3 (the `WS_EX_NOACTIVATE` spike may fail — the focus-restore fallback is a
  defined, equal-status path, **not** an escalation; both paths documented here), D4.6
  (additive to the ADR-0027 phone→PC mode).

## Context

The desktop dictation host (ADR-0031) needs a UI. The design intent
(F5) is a system-wide HUD: press a hotkey anywhere, a small panel appears warm and fast
(<50 ms), you dictate, and the transcript is inserted into whatever app you were using.
For that insertion to land in the right place, the panel ideally must **not** steal
keyboard focus from the underlying app.

Two uncertainties shaped the decision:

1. **Can a Compose Desktop window be truly focus-free on every target OS?** On Windows,
   `WS_EX_NOACTIVATE` should prevent activation, but whether Compose/AWT honours it
   cleanly is a **spike**, not a certainty.
2. **What is the cross-OS story?** Windows is first-class (global hotkey + `SendInput`
   insertion); Linux is dogfooding-only (F6), where a global hotkey may be unavailable.

## Decision

Build the dictation UI as a **frameless, always-on-top, focus-free Compose mini-panel**
driven by the single `DesktopUiState` stream, with the hotkey behind a port and a
documented focus-restore fallback.

1. **Warm render surface, one state stream (spec §5.3/§1a.1; ADR-0004/0027 analogue).**
   The panel is a pure consumer of `DesktopUiState` — no wire protocol, no WebSocket, the
   Compose renderer reads state directly (an F1/F5 benefit). It stays constructed and
   warm so the hotkey toggle is a visibility flip, hitting the <50 ms target (F5).

2. **Frameless, always-on-top, focus-free (F5/F21).** The window is undecorated and
   always-on-top. The **preferred** path is focus-free: on Windows, request
   `WS_EX_NOACTIVATE` so showing the panel does not activate it and the underlying app
   keeps keyboard focus.

3. **Equal-status focus-restore fallback (D4.3).** Because the focus-free path is a
   spike, the fallback is a **defined, equivalent** path — capture the foreground window
   when the hotkey fires and restore it before inserting. **A failed `WS_EX_NOACTIVATE`
   spike is NOT an escalation**; both paths are first-class and documented here. The
   choice is made per platform capability, not treated as success/failure.

4. **Global hotkey behind a `GlobalHotkey` port (spec §6, F6).** `interface GlobalHotkey
   { available; register(combo, onTrigger); unregister() }`. The Win32 implementation
   uses `User32.RegisterHotKey` on its own message-loop thread (JNA already present, no
   new dependency, Kotlin ceiling untouched); a Noop implementation (`available=false`)
   covers Linux/macOS, where the user triggers recording via the tray menu or a panel
   button. `HotkeyCombo` is configurable in `CompanionSettings` (default e.g.
   Ctrl+Alt+Space). This mirrors the `TextInserter`/`PlatformModule` port-behind-a-
   capability pattern.

5. **Auto-insert by default, confirmation opt-in (F21).** On a finished dictation the
   text is auto-inserted through `TextInserter` (ADR-0018); a `CompanionSettings` toggle
   makes the companion require an explicit confirm instead.

6. **Recording core 1:1, management screens their own layouts (F19).** The recording
   surface reproduces the Android recording UX 1:1 in Compose (shared amplitude curve
   parameters via the moved `AmplitudeProcessor`, ADR-0028 D5.e); the
   prompt/model/profile/peer **management** screens are their own Compose layouts with a
   desktop colour/shape language, not a keyboard clone.

7. **Additive (D4.6).** The panel is a new surface; the ADR-0027 phone→PC mode is
   unaffected.

## Alternatives Considered

1. **A browser/Electron UI or a WebSocket-driven web panel.** The original F1/F3/F13
   direction. Rejected (F1): a native Compose window shares the JVM stack, needs no
   embedded browser or IPC, reads `DesktopUiState` directly, and hits the <50 ms warm-toggle
   target far more simply. F3/F13 (shared UI protocol, secrets-in-browser) fall away.
2. **A normal focused window.** Simplest to build. Rejected: activating the panel steals
   keyboard focus, so the underlying app loses focus and insertion targets the wrong
   window (or nothing). Focus-free is the whole point of a HUD.
3. **Treat the `WS_EX_NOACTIVATE` spike as pass/fail with escalation on failure.**
   Rejected (D4.3): the focus-restore fallback is genuinely equivalent for the user, so a
   spike failure is a path selection, not a blocker. Escalating would stall the block on a
   non-problem.
4. **Rebuild the whole UI as a keyboard clone (like ADR-0027's render host).** Rejected
   (F19): the *recording core* is worth reproducing 1:1, but management screens are
   better as purpose-built desktop layouts than as a grid clone; a full clone would drag
   IME-specific chrome onto the desktop.
5. **Require a global hotkey on every OS.** Rejected (F6): Linux global-hotkey support is
   inconsistent; the Noop port + tray/button trigger keeps Linux dogfooding usable without
   a hotkey.

## Consequences

**Positive:**
- A fast, system-wide dictation HUD that reads state directly — no IPC, no browser, warm
  toggle under 50 ms.
- The focus-free path plus the focus-restore fallback means insertion lands in the right
  app whether or not the OS honours `WS_EX_NOACTIVATE` — no single point of failure.
- The hotkey behind a port keeps Windows first-class and Linux/macOS gracefully degraded,
  consistent with the `TextInserter`/`PlatformModule` pattern.
- Reusing the one `DesktopUiState` stream mirrors the proven ADR-0004/0027 render-host
  model on the desktop side.

**Negative:**
- Two focus strategies to build and test (focus-free + restore), and their behaviour is
  OS- and window-manager-dependent.
- Frameless always-on-top windows carry their own UX cost (no OS title bar, manual drag/
  close affordances) and can behave differently across Windows versions and Linux WMs.
- A native panel forgoes any future web-embedding reuse (accepted per F1).

**Failure Modes:**
- **`WS_EX_NOACTIVATE` silently not honoured** by a Compose/AWT window version → the panel
  *does* take focus and the underlying app loses it; the focus-restore fallback must be
  wired even when the focus-free path is "supposed to" work, or insertion targets the panel.
- **The global-hotkey message loop must run on its own thread** — registering `RegisterHotKey`
  on the Compose/AWT event thread can deadlock or miss `WM_HOTKEY`; the port's Win32 impl
  owns a dedicated loop.
- **Auto-insert into a wrong/stale foreground window** — if the captured foreground handle
  is restored to a window that has since closed, insertion fails or lands elsewhere; the
  restore step must validate the handle and degrade to the confirmation path.
- **Always-on-top over a full-screen app** may be suppressed by the OS (exclusive
  full-screen), so the panel can be invisible exactly when triggered; documented as a known
  edge for v1.

## References

- **Related Plan:** [desktop-companion-v1](docs/plans/2026-07-19 - desktop-companion-v1/desktop-companion-v1.md)
  — §3 (F5/F6/F19/F21, D2, D4.3, D4.6), §5 Block D. Motivates and is implemented by this ADR.
- **Spec:** `docs/plans/2026-07-19 - desktop-companion-v1/research/desktop-host.md`
  (§6 hotkey + focus-free panel, §5.3 `DesktopUiState`, §1a.1).
- **Concept:** `.../research/fragenkatalog.md` §F5/§F6/§F19/§F21; `.../research/konzept-skizze.md` §3.
- **Related ADRs:**
  - ADR-0004 — the multi-backend render model this panel is the desktop analogue of.
  - ADR-0027 — the third render host reusing one state stream; the same design idea on
    the desktop.
  - ADR-0018 — `TextInserter`/`available`, the port-behind-a-capability pattern the hotkey
    follows and the insertion path auto-insert uses.
  - ADR-0031 — the host whose `DesktopUiState` this panel renders.
  - ADR-0028 — the moved `AmplitudeProcessor` (D5.e) driving the shared
    recording amplitude curve.

## Decision History

### 2026-07-20 — Initial proposal (plan-scoped)

**Trigger:** Feature decisions F5/F19/F21 (a fast, focus-free, native dictation HUD with
auto-insert) and the F6 Windows-first/ports constraint required a panel and hotkey design;
the desktop-host spec resolved the port and the focus-free-vs-restore approach.

**Before:** The companion had no dictation UI of its own (only receiver-side dispatch,
ADR-0017); the earlier concept assumed a browser/WebSocket UI (F1/F3/F13).

**After:** A frameless, always-on-top, focus-free Compose mini-panel driven by the single
`DesktopUiState` stream, a `GlobalHotkey` port (Win32 impl + Noop fallback), a documented
`WS_EX_NOACTIVATE`-vs-focus-restore choice (both first-class, D4.3), auto-insert by default
with a confirmation opt-in, and a 1:1 recording core with purpose-built management screens.

**Reasoning:** A native Compose window reads `DesktopUiState` directly and hits the warm-toggle
target without a browser or IPC (F1). The focus-restore fallback makes the focus-free spike a
path choice rather than a blocker (D4.3). The hotkey behind a port keeps Windows first-class and
Linux degraded gracefully, reusing the established `TextInserter`/`PlatformModule` pattern; the
whole surface mirrors the ADR-0004/0027 render-host model on the desktop.

### 2026-07-20 — Promoted and accepted

**Trigger:** Chunk F1 (Block F) of the desktop-companion-v1 plan — blocks A–E are
implemented; the plan-scoped draft is promoted to a numbered, accepted ADR before
plan archival (§2 criterion 9).

**Before:** Plan-scoped draft `adrs/adr-desktop-panel-ui.md` with an `NNNN` placeholder and
`Proposed (plan-scoped — pending promotion)` status; sibling ADRs referenced by slug.

**After:** `docs/decisions/0032-desktop-panel-ui.md`, Status **Accepted**, indexed in
`docs/decisions/README.md`; sibling cross-references resolved to their assigned ADR
numbers.

**Reasoning:** The decision is active in the codebase across the implemented blocks;
promotion makes it a binding, navigable ADR with bidirectional cross-links.
