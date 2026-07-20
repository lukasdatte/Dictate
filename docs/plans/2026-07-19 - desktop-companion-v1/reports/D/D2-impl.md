# D2 — Hotkey + fokus-freies Panel + Recording-UI + Insert (IMPL+TEST)

**Date:** 2026-07-20 | **Chunk:** D2 | **Agent:** chunk-impl-test

## What was done

Implemented the full D2 surface per spec `desktop-host.md` §6–§7 + §8.5: the `GlobalHotkey` port
(Win32 `RegisterHotKey` message loop + Noop fallback + fake), the warm frameless always-on-top
panel window with the `WS_EX_NOACTIVATE` spike styler, the `FocusRestorationPolicy` fallback with
both paths unit-tested, the Compose-canvas recording UI with the tested 1:1 Android design
parameters, and the insert/auto-insert wiring incl. the F21 `insertion.confirmBeforeInsert` gate.
All wired into `Main.kt` (hotkey registration, warm `PanelWindow`, tray "Start dictation") and
`CompanionContainer.production` (focus-wrapped inserter, panel control, capture exposure).

## Focus-spike status (§6.3, R1)

The spike **code** is complete (`Win32WindowStyler.applyFocusFreeStyle`: HWND via JNA,
`GWL_EXSTYLE |= WS_EX_NOACTIVATE|WS_EX_TOOLWINDOW`, read-back verification), but the spike
**verdict** requires a real Windows desktop, which per the E2E runbook (Q3b) only exists at Block F.
Therefore:

- `ComposePanelWindowControl.focusFree = styleApplied && FOCUS_SPIKE_VERIFIED`, with
  `FOCUS_SPIKE_VERIFIED = false` until the manual TC-W1 acceptance. The
  `FocusRestorationPolicy` fallback stays active on Windows regardless (belt and braces — restoring
  an already-foreground window is idempotent), exactly per the §6.3 CAUTION / "Fokus klauen" footgun.
- The focus-free-window test is `FocusFreeWindowSpikeTest`, `@Ignore("pending: D2-focus-spike …")`
  — the real target assertion, NOT faked green. The un-pending procedure (and the delete-if-failed
  branch) is documented in its KDoc. Per D4.3 this deferral is **no escalation**.

## Deviation table

| Deviation | Plan location | What changed | Why | Impact on later chunks | Resolved? |
|---|---|---|---|---|---|
| `FocusRestorationPolicy` + `ForegroundWindows` port live in `domain/` (+`domain/port/`), not `ui/panel/` | spec §6.3, §10 layout (no home named for the policy) | pure policy → domain, raw API behind a domain port (house style: `TextInserter`) | keeps the policy inside `CompanionLayeringTest`'s framework-free zone; the decorator `FocusRestoringTextInserter` composes two ports like `DispatchService` does | none — D3 consumes via container | yes |
| Confirm gate implemented as reducer REVIEW-wait + new `ConfirmInsert` intent + `AcknowledgeDiscard` effect | spec §8.5 (F21) | `PipelineVerdict.requiresConfirm` (read by the IO side from settings), INSERT+confirm parks in the existing `ReviewUi` state; Discard from that wait acknowledges via `inserted_at` | reducer stays settings-free (§5.4); reuses the D1b review axis instead of a parallel "confirm" state; the acknowledge-discard is a small forward-slice of D3's §8.5 so the confirm state is not a trap | additive for D3 (its ReviewPanel + re-dictate plug into the same state; `plan-deviation-resolved` issue filed for audit verification) | yes |
| `hotkey.combo` stored as a plain string in `CompanionSettings`; parsing via `HotkeyCombo.parse(...) ?: DEFAULT` at the call site | spec §6.1 ("`CompanionSettings`-Key `hotkey.combo`") | typed combo lives in `hotkey/HotkeyCombo.kt`, settings stay string-typed | keeps `domain/` free of the `hotkey/` vocabulary (dependency direction), garbage self-heals like every other setting | none | yes |
| Warm window exists from boot-**Ready**, not literally process start | spec §6.2 | `PanelWindow` composes once `CompanionBootstrap` finished (~0.5 s async boot) | the panel needs the container (controller/capture); boot is deliberately off the render loop (existing Main.kt design) | none — toggle <100 ms holds for every hotkey press after Ready | yes |
| Panel REVIEW rendering is a minimal ConfirmRow (output + Insert/Discard) | spec §8.4 is D3 | D2 ships the §8.5 insert/confirm surface only | full ReviewPanel (message, re-dictate, refining states) is D3's chunk | D3 replaces `ConfirmRow` with `ReviewPanel.kt` | yes |
| Timer/elapsed lives in `PanelViewModel` (10 Hz tick vs. reducer state) | spec §5.3 `RecordingUi.Active.elapsedMillis` | reducer keeps `elapsedMillis = 0`; the ViewModel derives time from `ClockPort` | reducer is clock-free by design (§5.4); ticking through the reducer would fabricate 10 intents/s | none — presentation-only field | yes |

## Issue table

| ID | Severity | Description (what + file:line) | Status | Marker |
|---|---|---|---|---|
| D2-1 | Important | Confirm gate + acknowledge-discard design call (see deviation row 2) — `DictationReducer.kt` onVerdict/onConfirmInsert/onDiscard, `Effect.AcknowledgeDiscard` | fixed-inline | plan-deviation-resolved |
| D2-2 | Nice-to-have | `ConfirmInsert` runs the insert (focus restore 150 ms settle + Ctrl+V + DB stamp) on the dispatching UI thread — `DesktopDictationController.dispatch` executes effects on the caller's thread; the auto-insert path runs on the job-queue thread. Could route `InsertText` through the queue uniformly. | delegated | none |

## Inline fixes applied

- Deprecated `Icons.Default.Send` → `Icons.AutoMirrored.Filled.Send` (compiler warning).
- Removed a stray zero-width spacer left from layout drafting in `PanelWindow.kt`.

## Integration call sites (self-check item 5)

- `Main.kt:129-137` — `DisposableEffect(bootState)`: `globalHotkey.register(HotkeyCombo.parse(settings.hotkeyCombo) ?: DEFAULT) { container.startDictation() }` + unregister on dispose.
- `Main.kt:139-158` — warm `PanelWindow(controller, viewModel, dictationPanel, panelStyler)` composition + `PanelViewModel.start()` on an app-lifetime scope.
- `Main.kt:166-171` — tray `Item("Start dictation")` (Linux/F6 trigger path).
- `CompanionContainer.kt:104-112` — `startDictation()`: the one trigger funnel (`dictationFocus.onDictationTrigger()` → `desktopDictation.startHotkey()`).
- `CompanionContainer.kt` production graph — `FocusRestoringTextInserter(platform.inserter, dictationFocus)` into `DictationEffects(inserter=…)`, `panel = dictationPanel`, `confirmBeforeInsert = settings::confirmBeforeInsert`.
- `DictationEffects.kt` — `Effect.AcknowledgeDiscard` → `sessions.stampInserted(...)`; both `PipelineVerdict` dispatch sites carry `requiresConfirm`.

## Files modified

Main (new): `hotkey/GlobalHotkey.kt`, `hotkey/HotkeyCombo.kt`, `platform/windows/Win32GlobalHotkey.kt`,
`platform/windows/Win32PanelWindowControl.kt` (styler + `Win32ForegroundWindows`),
`platform/fallback/NoopGlobalHotkey.kt`, `platform/fallback/NoopForegroundWindows.kt`,
`domain/port/ForegroundWindows.kt`, `domain/FocusRestorationPolicy.kt` (+ decorator),
`ui/panel/PanelWindowControl.kt`, `ui/panel/PanelWindow.kt`, `ui/panel/RecordingBar.kt`,
`ui/panel/RecordingBarDesign.kt`, `ui/panel/PanelViewModel.kt`.

Main (edit): `Main.kt`, `CompanionContainer.kt`, `platform/PlatformModule.kt`,
`domain/CompanionSettings.kt`, `pipeline/DictationIntent.kt`, `pipeline/Effect.kt`,
`pipeline/DictationReducer.kt`, `pipeline/DictationEffects.kt`, `pipeline/DesktopDictationController.kt`.

Tests (new): `hotkey/HotkeyComboTest.kt`, `platform/Win32GlobalHotkeyTest.kt`,
`domain/FocusRestorationPolicyTest.kt`, `domain/CompanionSettingsDictationTest.kt`,
`ui/panel/RecordingBarDesignTest.kt`, `ui/panel/PanelViewModelTest.kt`,
`ui/panel/FocusFreeWindowSpikeTest.kt` (pending), `fakes/FakeGlobalHotkey.kt`,
`fakes/FakeForegroundWindows.kt`. Tests (edit): `pipeline/DictationReducerTest.kt` (+7 confirm-gate
cases).

## Drift (files outside assigned scope)

none.

## Test run

`./gradlew :companion:test` and `./gradlew :companion:build` (incl. `verifySqlDelightMigration`)
— **BUILD SUCCESSFUL**, all suites green (DictationReducerTest now 22 tests; all 9 new/edited test
classes executed; 1 deliberately `@Ignore`d pending test). Full `./gradlew test` not run: only
`:companion` is touched by this chunk and the parallel C2 chunk is actively editing `:app`.

## Helper decisions

Reused: `MutableClock`, `FakeTextInserter`, `InMemorySettings`, JUnit4 house patterns,
`AmplitudeProcessor` (already in `:shared-ai` per D5.e — consumed via `capture`'s amplitude flow).
New (in-scope, reusable by D3/F): `FakeGlobalHotkey`, `FakeForegroundWindows`.
