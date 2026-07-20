# D2 — Self-Fix (fresh eyes, diff-based)

**Date:** 2026-07-20T00:40:00+02:00 | **Chunk:** D2 | **Agent:** chunk-self-fix
**Wave commit:** 17a418a4 | **Impl report:** `reports/D/D2-impl.md`

## What was done

Reviewed the full D2 diff against plan §5 Chunk D2 and spec `desktop-host.md` §6.1/§6.2/§6.3,
§7.1–§7.4, §8.5 with the three lenses (plan correctness / code quality / test quality), including a
line-level comparison of the ported design parameters against the actual Android source
(`app/src/main/java/net/devemperor/dictate/widget/AmplitudeVisualizerDrawable.kt`). One real
parity defect found and fixed; everything else held up — the implementer's deviation table is
accurate and defensible (D4), the confirm-gate reducer design is clean, both §6.3 focus paths are
genuinely unit-tested, and the pending spike test carries the real target assertion with a correct
un-pending procedure.

## Fix applied (1)

**Bar-gap geometry misread the Android source (F19 "1:1" parity break).**
Android computes `barSpacing = barsAreaWidth * 0.02f` — the gap is 2 % of the **whole bars area**,
which with 30 bars makes the gaps *wider than the pills* (sparse thin-pill look;
`AmplitudeVisualizerDrawable.kt:220-222`). The port computed `gap = cell * GAP_FRACTION * 2` — 4 %
of a *single cell* — rendering fat bars with hairline gaps: a clearly different look, silently
forking the design that acceptance §2 criterion 9 pins. The spec table's terse "2 % der Bar-Fläche"
invited the misread; the cited source line settles it.

- `RecordingBarDesign.kt` — corrected the `GAP_FRACTION` doc (fraction of the whole bars-area
  width, not of one cell) and added the pure geometry pair `barSpacing(areaWidth)` /
  `barWidth(areaWidth, count)` mirroring the Android formula.
- `RecordingBar.kt` — Canvas now uses those helpers; x-advance is `barWidth + spacing` (Android
  `:236`), no leading half-gap.
- `RecordingBarDesignTest.kt` — new regression test
  `barGeometry_gapIsTwoPercentOfTheBarsArea_notOfASingleCell` pinning spacing, width, the
  gaps-wider-than-bars invariant and the exact-fill layout invariant (fails on the unfixed code:
  the helpers did not exist and the inline Canvas math diverged).

## Deviation table

| Deviation | Plan location | What changed | Why | Impact on later chunks | Resolved? |
|---|---|---|---|---|---|
| none new — implementer's six documented deviations reviewed and upheld | — | — | — | — | — |

## Issue table

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| D2-SF1 | Important | Bar-gap geometry 4 %-of-cell instead of Android's 2 %-of-area — `RecordingBar.kt:39-41` (pre-fix), design doc `RecordingBarDesign.kt:17` | fixed-inline | none |
| D2-SF2 | Nice-to-have | `ICON_SIZE_FRACTION` (0.45 h) and `H_PADDING_FRACTION` (0.35 h) are ported + tested but not consumed by `RecordingRow` (`PanelWindow.kt` uses default 24 dp `IconButton` icons and 12 dp padding). Plausibly deliberate — the desktop row carries extra pause/discard controls the phone bar lacks — but the deviation is undocumented, and whether the icon scale reads "same widget" is a visual call for the manual Windows acceptance (criterion 10) / D3's panel rework. | delegated | none |

## Review notes (checked, no finding)

- **Confirm gate:** reducer stays settings-free — `requiresConfirm` rides the intent, read by the IO
  side from `CompanionSettings` supplier; Discard from the REVIEW wait acknowledges via
  `AcknowledgeDiscard` and correctly drains the ADR-0009 queue (both tested).
- **Focus paths:** remember-at-trigger/restore-before-insert order, failed-restore degradation,
  spike no-op and unavailable-platform no-op all asserted with fakes; `focusFree` is a supplier so
  the verdict binds late; `FOCUS_SPIKE_VERIFIED=false` gate + pending test match the §6.3 CAUTION.
- **Win32 hotkey:** thread-affinity of `RegisterHotKey`/`GetMessage`/`UnregisterHotKey` handled on
  one dedicated loop thread; `PostThreadMessage(WM_QUIT)` teardown; pure modifier translation
  unit-tested on Linux; registration failure surfaces as `register(...) == false`.
- **Integration:** hotkey/tray/panel all funnel through `CompanionContainer.startDictation()` so the
  §6.3 remember step cannot be skipped; production graph wraps only the dictation inserter in
  `FocusRestoringTextInserter` (phone-dispatch keeps the bare inserter, as documented).
- **PanelViewModel timer:** pause/resume accumulation against `MutableClock` correct; timer freezes
  when the mic stops, waveform persists behind pipeline phases (Android-matching).

## Files modified

- `companion/src/main/kotlin/net/devemperor/dictate/companion/ui/panel/RecordingBarDesign.kt`
- `companion/src/main/kotlin/net/devemperor/dictate/companion/ui/panel/RecordingBar.kt`
- `companion/src/test/kotlin/net/devemperor/dictate/companion/ui/panel/RecordingBarDesignTest.kt`

## Drift (files outside assigned scope)

none.

## Test run

`./gradlew :companion:test` after the fix — **green** (all suites; the new geometry regression test
included; the one `@Ignore`d `pending: D2-focus-spike` test unchanged).
