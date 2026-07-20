# Inline-Anchor Report — companion-pipeline-capture

**Date:** 2026-07-20T17:25:00+02:00
**Agent:** docs-inline (group `companion-pipeline-capture`)
**Slug:** companion-pipeline-capture
**Outcome:** no-change-needed (verification pass — anchors already complete + accurate)

## Scope

21 `.kt` files across three companion subsystems (Block D core):

- `companion/.../pipeline/` — 10 files (reducer, effects, controller, intents, effects enum,
  UI-state, job queue, profile sources, panel control)
- `companion/.../capture/` — 9 files (javax.sound capture service + WAV helpers + amplitude)
- `companion/.../hotkey/` — 2 files (`GlobalHotkey` port, `HotkeyCombo`)

## Verdict

**No anchor edits applied.** Contrary to the discovery report's blanket
"companion/** 8/155 sparse — most header-less" signal (which counted only the
formal `@see` JSDoc token), this specific group is **fully and accurately
anchored**. Every file was authored during Block D with a comprehensive
module/class header plus non-derivable gotcha comments. The linkage to plans/ADRs
is present in **prose form inside the KDoc** — e.g. `(desktop-host.md §5.4)`,
`(ADR-0013 §6, spec §8.3)` — rather than as a standalone `@see` tag, which is why
the discovery grep under-counted it. The convention values the *resolving linkage*,
not the exact token; I did **not** mass-convert prose refs to `@see` tags (that
would be pure churn across all 21 files, against the "surgical" directive and the
no-noise rule).

## Anchor-1 (module/class headers) — all present, all accurate

Every file carries a 3–8 line header stating responsibility + non-obvious pattern +
spec/ADR anchor. Spot-notable ones verified accurate:

| File | Header claim verified |
|---|---|
| `DictationReducer.kt` | "pure heart, no IO" — confirmed: no clock/UUID/IO in body |
| `DictationEffects.kt` | "IO side, serial JobQueue (ADR-0009 §5.6)" — confirmed |
| `JavaSoundAudioCaptureService.kt` | "AmplitudeProcessor moved to `:shared-ai` per D5.e" — **confirmed**: class now lives in module `:shared-ai` (package kept as `net.devemperor.dictate.core`, hence the `.core` import is correct, not stale) |
| `GlobalHotkey.kt` | "port in ADR-0018 style, Win32/Noop/Fake impls" — confirmed |
| `WavAudioDurationReader.kt` | "backing for shared `AudioDurationReader` port (shared-ai-extraktion.md §4.4)" — confirmed section exists |

## Anchor-2 (`@see` plan/ADR) — all cross-refs resolve

Verified every referenced target exists:

- **ADRs**: 0007, 0009, 0012, 0013, 0015, 0018 all exist in `docs/decisions/`.
- **ADR-0013 sub-sections**: §4 (`reviewPanel state axis`) and §6 (`Dictated refinement
  loop`) both exist — referenced by Effect/Intent/Reducer/Effects/Controller/UiState.
- **desktop-host.md sub-sections**: §4.1–4.5, §5.1–5.6, §6.1–6.2, §8.1–8.5, §9.1,
  §11, §12, §15 (Gap 2/3) — all resolve.
- **shared-ai-extraktion.md §4.4** (`AudioDurationReader`) — resolves.
- **Decision IDs**: `D4.2` (audio format fix, desktop-host §4.5) and `D5.e`
  (AmplitudeProcessor package-preserving move, shared-ai-extraktion.md:793) — both resolve.
- **Named §13 footguns**: "Reducer mit IO/Zeit", "Systemprompt aus Live-Template",
  "ReviewDecision nachbauen" — all present verbatim in desktop-host.md §13.

## Anchor-3 (gotcha comments) — non-derivable, several empirically dated

All inline `//` comments are genuine WHY-comments a reader cannot derive from code,
e.g.:

- `JavaSoundAudioCaptureService.pause()` — why `line.stop()` not just loop-sleep
  (javax.sound has no MediaRecorder pause; a running line overruns and leaks
  during-pause audio on resume).
- `JavaSoundAudioCaptureService.finish()` / `discard()` — `line.stop()` ordering
  before `stopLoop()` to unblock a pending `line.read`, carrying the empirical
  bug IDs `logic-D-3` / `logic-D-1`.
- `DictationReducer.onStartHotkey` — ADR-0009 dedup-by-session rationale.

No code-restating comment noise found → nothing to remove.

## Self-check

Re-read all edits: none applied, so nothing to re-verify structurally. Re-ran the
cross-reference resolution (ADRs, spec sections, decision IDs, named footguns) —
all green. No logic touched, no comment noise introduced.

## Files modified

None.

## Drift (edits outside assigned scope)

None.

## Notes for final report

- The group is a positive outlier vs. the discovery "companion sparse" signal —
  useful correction for the docs-final aggregation (the sparse count is a
  `@see`-token artifact; prose-form KDoc anchors are present and resolve).
- Minor CLAUDE.md nuance (for the `root-claude-md` worker, not this group):
  CLAUDE.md describes `:shared-ai` as package `net.devemperor.dictate.ai`, but
  `AmplitudeProcessor` lives in `:shared-ai` under the retained package
  `net.devemperor.dictate.core` (package-preserving move per D5.e). The header
  anchor here is correct; only the CLAUDE.md generalization glosses the `.core`
  exception.
