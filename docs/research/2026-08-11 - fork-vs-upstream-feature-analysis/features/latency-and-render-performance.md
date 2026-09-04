---
date: 2026-08-11
author: Lukas + Claude (analysis session)
status: Research
context: Fork-vs-upstream analysis of the latency and render-performance waves — record-start stall, cold-start blind window, rotation rebuild. What we built, what Dictate Keyboard 5.3 has, and whether the feature is still worth pursuing.
related-adrs: ADR-0003, ADR-0004, ADR-0027
related-plan: n/a (plan-free research)
---

# Latency & Render Performance — Fork vs. Upstream Analysis

Two measured waves in July 2026 removed a fixed ~2.5-second stall at record start, a ~1.3-second
window in which a freshly started keyboard was visible but dead, and a ~190 ms rebuild on every
rotation. **Verdict: absent upstream for the IME surface, and none of it ports.** Dictate
Keyboard 5.3 has done no cold-start or first-frame work at all — its `onCreateInputView` is the
stock FlorisBoard implementation — but its *dictation-pipeline* latency work is real,
instrumented, and in one respect more mature than ours. The transferable asset here is not code;
it is the measurement discipline, and upstream already has its own version of that.

## 1. Feature Overview

Three separate user-visible stalls, each confirmed by measurement before anything was changed.

**Record start.** With `UseBluetoothMic` enabled but no headset paired, every single record tap
stalled for about two and a half seconds before the mic opened. The cause was a gate defect, not
a hardware cost: the `StartRecording` reducer branched on the *preference* alone, entered
`Preparing(awaitingSco = true)`, and the effect handler armed
`AudioManager.startBluetoothSco()` and waited out the full 2,500 ms timeout for a
`SCO_AUDIO_STATE_CONNECTED` broadcast that could never arrive. Measured on an emulator with no
Bluetooth hardware whatsoever: median 2,516 ms with the pref on versus 9 ms with it off.

**Cold start.** After a process restart, the keyboard window appears roughly 0.4 s in, but the
pipeline-service binder does not land for another ~1.3 s. The entire render path early-returned
on `pipelineBinder == null`, so for that whole window the user looked at dead XML defaults with
no click listeners attached, and a record tap was answered with a "service not ready" toast. This
was not theoretical: in a scripted run, a tap ~0.9 s after the keyboard appeared was swallowed in
4 of 5 trials.

**Rotation.** A configuration change re-enters `onCreateInputView`, and the old implementation
tore the whole tree down and rebuilt it — detach, ~40 `findViewById` calls, controller
reconstruction, re-attach, first render, GL draw. Measured at ~210 ms in the analysis pass and
~190 ms median when the fix was re-measured.

After the waves: record start 2,518 ms → **24 ms** median; a cold-start record tap is buffered
rather than dropped and the keyboard paints a usable surface at frame 1; rotation 190 ms →
**~10 ms**.

## 2. Our Implementation

**Wave 1 — `f597c6c4`** (SCO availability gate + cold-start tap buffer).

The gate lives in `AudioModule`'s *effect handler*, not its reducer — a pure reducer must not
read hardware (ADR-0001 forbidden pattern (b)), so the question "is a headset actually there?"
belongs where hardware access is legitimate. When `services.bluetoothSco.isAvailable()` is false
the handler emits the same terminal `Failed` phase the timeout would eventually have produced,
so the existing `ScoRouteResolved(useBluetooth = false)` cascade fires immediately and the
deferred `AllocateMediaRecorder` sources the built-in mic. A real headset keeps the unchanged
handshake. The re-dispatch goes through `emitAction`, the sanctioned async seam — never a
synchronous re-entry into `dispatch` (forbidden pattern (h)).

The tap buffer (`pendingRecordOnBind`) only works because of `fa16003b`, which hoisted the
`pipelineBinder`-null guard to the head of `startRecording()`. A tap arriving between
`onCreateInputView` and `onServiceConnected` used to NPE on `promptQueueManager` *before* the
buffer could arm — losing the tap and crashing the QWERTZ record button.

**Wave 2 — `048fb37c`**, two independent problems.

*Pre-bind bootstrap render (`f4fc1139`).* `ImeViewBackend`'s `services: ModuleServices` became a
`() -> ModuleServices?` provider resolved at click time: pre-bind it returns null and a click is
a silent no-op, post-bind it resolves to the live container. `render()` never needed the binder
at all, so attachment split into a bind-free `buildImeViewRenderers` (always runs in
`onCreateInputView`, and finishes with a bootstrap render of `DictateUiState.bootstrap()` through
the **real** `LayoutCatalog` — same backend, same catalog, same slot path, so there is no second
writer) and a binder-required `attachImeViewBackendToService`. A temporary record-click listener
routes a tap into the buffer and is atomically replaced by `wireStaticHandlers` on bind.
`LayoutStrings.from(context)` was extracted so the bootstrap catalog and the service catalog
cannot drift on labels, and `singleRowMode` is read IME-side so single-row users do not see a
two-row flash on bind.

*View retention (`6eb9ae25`).* `core/InputViewRetentionPolicy.canReuseInputView` is a pure
function over `Configuration.diff`: reuse is allowed only when *every* set delta bit is in a
geometry allowlist (orientation, screen size, screen layout, keyboard-hidden, navigation,
window-configuration); night mode, locale, density, font scale, layout direction and **any bit
not explicitly listed** force a rebuild. The `@hide` `CONFIG_WINDOW_CONFIGURATION` bit
(`0x20000000`) had to be whitelisted by literal value, because a real rotation was measured to
produce `diff = 0x20000480` — without it the fast path would have been dead code. On the fast
path nothing is detached, inflated or re-attached: backend, controllers, observers, listeners and
the `firstRender` flag all stay live. That required service-loss hardening (B3), since a view
rebuild no longer implicitly heals a service restart: `onServiceDisconnected` / `onBindingDied`
now clear an `imeViewBackendAttached` marker and stop the service-bound observers, and the
re-bind path keys on that marker.

**Size and coupling.** `M` overall — `f597c6c4` 7 files (+186/−6), `fa16003b` 2 files (+93/−11),
`f4fc1139` 8 files (+453/−144), `6eb9ae25` 4 files (+487/−0), of which 283 of the last wave's 487
added lines are tests (`StartRecordingPreBindGuardTest`, `InputViewRetentionPolicyTest`).
`InputViewRetentionPolicy` is a pure `Configuration.diff` predicate and portable to any
View-based IME. The pre-bind bootstrap render is meaningless without the FGS-binder architecture
(ADR-0003) and the catalog-driven render path (ADR-0004, ADR-0027). The SCO gate is portable as a
concept, and upstream already has it.

**Measurement method, because it is the reusable part.** Everything was measured before and after
on a headless AVD (Pixel 6 profile, API 35, software GPU) using the app's existing `DictateTrace`
logcat markers with an epoch time base — no instrumentation was added for the measurement pass —
plus `atrace` framework slices (`IMS.showWindow`, `IMS.resetStateForNewConfiguration`, `inflate`)
and N-repetition medians/P90s. The write-up carries an explicit emulator caveat: with
swiftshader every GPU-side number is distorted, so conclusions come from *relative* comparisons
(cold vs. warm, on vs. off), and one of the four suspicions in the analysis was **refuted** by
the measurements (per-tap synchronous DB/disk work turned out to be ~9 ms on the hot path) rather
than confirmed. Raw data lives in the untracked `tmp/perf/`.

## 3. Upstream 5.3 Status

**Verdict: absent for IME cold start / first frame; present and well-instrumented for the
dictation pipeline.**

`onCreateInputView()` is the stock FlorisBoard implementation, untouched — verified in the
worktree at `app/src/main/kotlin/dev/patrickgold/florisboard/FlorisImeService.kt:312-318`: install
view-tree owners, add `ImeRootView`, return null. No warm-up, no precomposition, no view caching,
no comment about first frame. Grepping `first frame|cold start|startup|inflat` across `ime/`,
`FlorisImeService.kt` and `FlorisApplication.kt` yields one relevant hit
(`ime/smartbar/quickaction/QuickActionButton.kt:467`) and it is about the mic key not visually
jumping — layout stability, not render speed. `preload()` in `ime/nlp/` is inherited FlorisBoard
dictionary preloading. No "bootstrap" symbol exists anywhere and there is no view retention
across configuration changes.

There is also no structural equivalent of our cold-start problem to solve: upstream's
`DictateController` is a process-wide `object`, not a bound foreground service, so there is no
bind to wait for. Our blind window is a *consequence* of the FGS architecture that buys us
keyboard-switch survival and crash recovery — a cost we chose, then paid down.

**Where upstream is ahead.** Their latency work targets the pipeline and is properly instrumented:
`LATENCY_LOG_TAG = "DictateLatency"` with a `BatchLatencyTrace` and named phase stamps
(`stopTapped` / `recorderStopped` / `audioRoutingCleaned` / `outputCommitted`,
`DictateController.kt:112-131, 1064-1086, 1494`) — permanent in-tree instrumentation, not a
one-off measurement harness. Concrete results built on it: `SpeechGate.prewarm()` hides one-time
native VAD/ONNX setup behind the user's own speaking time (`:985-987`); the realtime session is
opened off the main thread because doing it inline "stalled the UI thread long enough for Android
to cancel the in-flight touch — which killed push-to-talk ~90 ms into a hold" (`:975-980`); a
39-commit `experiment:` / `Revert "experiment:"` bisection campaign by Alexander Immler in July
2026 on OpenRouter transcription latency; silence trimming before upload (#232); base64 and
resampler copy elimination.

The honest comparison: **we measured a surface they never touched; they instrumented a path we
measure ad hoc.** Our `tmp/perf/` measurement scripts are untracked and were built for one
investigation. Their phase stamps ship in the product.

## 4. Assessment — is this feature still sensible?

**Does the user need it?** All three fixes were user-visible on this user's own device, and two
of them were making the app feel broken rather than slow. A record button that swallows the first
tap after a keyboard switch is indistinguishable from a bug, and a fixed 2.5-second delay before
every recording — for a heavy dictation user, dozens of times a day — is minutes per week of pure
waiting. The rotation fix is the most marginal of the three: 190 ms on rotation is perceptible but
not obstructive, and it only pays off for users who rotate.

**Is it superseded by the rewrite?** No, and the question is slightly misframed. Two of the three
fixes address problems that only exist *because of* our architecture: the blind window is the
price of the bound foreground service, and the rotation rebuild is the price of an imperative
View tree with ~40 `findViewById` wirings. A user on 5.3 would not miss these fixes, because they
would not have the problems — Compose recomposition and a process-resident controller object make
both moot. The SCO stall is the exception: that was a genuine defect on a code path upstream also
has, and upstream had already avoided it by checking availability before activating.

**What does keeping it cost?** Almost nothing ongoing. `InputViewRetentionPolicy` is a pure
function with a decision-matrix test; the pre-bind split is structural and self-documenting. The
one live risk is the retention fast path: it deliberately keeps controllers and listeners alive
across a configuration change, so any future code that assumes "`onCreateInputView` means fresh
state" will be quietly wrong. The service-loss hardening in the same commit exists precisely
because the first version of that assumption broke. That is a permanent tax on anyone touching
IME lifecycle code in this repo, and it should stay documented in the policy's KDoc where it is.

**Upstream signal.** None either way — nobody has asked for IME cold-start work upstream because
the architecture does not produce the symptom. There is nothing here to propose.

## 5. Options going forward

**(a) Keep in fork as-is.** The obvious default. These are small, tested, load-bearing fixes to
problems the fork's own architecture creates; removing any of them re-introduces a regression that
was measured.

**(b) Port to Dictate Keyboard 5.x as a private patch.** Not applicable in either direction. The
render path is Compose, there is no binder to be blind on, and `onCreateInputView` returns null by
design. `InputViewRetentionPolicy` is portable in the abstract — to any View-based IME — but 5.x
is not one.

**(c) Propose upstream via issue.** Nothing from this cluster is proposable. The SCO availability
gate, the one genuinely shared concern, is something upstream already does correctly
(`BluetoothMicRouter.isAvailable()` gates `activate()`); we were the ones with the defect. The
only thing worth *taking* from upstream is the reverse direction: adopting in-tree latency phase
stamps in the style of their `BatchLatencyTrace`, so the next measurement pass does not start by
rebuilding a harness in `tmp/`.

**(d) Retire / let upstream's equivalent replace it.** Only meaningful as part of a whole-app move
to 5.x, at which point all three problems disappear with the architecture that caused them.

*Leaning:* keep everything, and treat upstream's permanent latency instrumentation — not any of
its optimisations — as the thing worth copying.

## 6. Information Gaps

1. **Every absolute number is emulator-relative.** The measurements were taken on a headless AVD
   with a software GPU; the write-up says so explicitly and leans on relative comparisons. Device
   numbers for the same three scenarios were never captured. **Owner:** Lukas (device run).
   **Fallback:** the relative improvements (2,518 → 24 ms, 190 → 10 ms) are large enough that the
   direction is not in doubt even if the magnitudes shift.
2. **How often the user actually rotates while the keyboard is open.** This determines whether the
   retention fix pays for the "controllers survive `onCreateInputView`" tax it introduces.
   **Owner:** Lukas. **Fallback:** keep it — the tax is documented and the fix is inert when it
   does not apply.
3. **Whether the ~0.44 s synchronous `Service.onCreate` (Room open + migrations + ~22-module
   orchestrator init) is worth attacking next.** It is the remaining half of the cold-start cost
   and was measured but not addressed. **Owner:** a future wave. **Fallback:** the tap buffer plus
   the bootstrap render already make the window non-destructive, so this is now latency rather
   than breakage.
4. **The two per-show synchronous system-settings reads** (`A11yEnablementGate.isServiceEnabled()`
   and `overlayPermissionObserver.refresh()`, both binder round-trips to `system_server` on every
   `onStartInputView`) were code-confirmed but could not be cleanly quantified on the emulator.
   **Owner:** follow-up measurement. **Fallback:** unchanged; they are gated behind
   `pipelineBinder != null` and did not show up as a dominant cost.
5. **`scripts/` and `tmp/perf/` are untracked.** The measurement harness that produced all of this
   exists on one disk only. **Owner:** Lukas. **Fallback:** re-derive from
   `tmp/perf/2026-07-17-measurements.md`'s method section, which documents the markers and
   procedure well enough to rebuild.

## 7. References

**Source reports**
- [`../sources/fork-feature-inventory.md`](../sources/fork-feature-inventory.md) — cluster 27
  (latency & render-performance waves)
- [`../sources/upstream-rewrite-analysis.md`](../sources/upstream-rewrite-analysis.md) — area 11
  (latency optimizations)

**Our ADRs / code / measurements**
- [`ADR-0003 — Foreground pipeline service`](../../../decisions/0003-service-foreground-pipeline-architecture.md)
  (§4: `bindService` stays in `onCreateInputView` — the constraint the bootstrap render works within)
- [`ADR-0004 — LayoutCatalog + MotionLayout render backends`](../../../decisions/0004-ui-layout-catalog-motionlayout.md)
  (the catalog stays the single source of truth for the bootstrap surface too)
- [`ADR-0027 — PC-Dictation Activity`](../../../decisions/0027-pc-dictation-activity.md)
  (the backend stays host-agnostic, which is why a provider-resolved `ModuleServices` was viable)
- Code: `core/InputViewRetentionPolicy.kt`, `state/render/ImeViewBackend.kt`,
  `state/modules/AudioModule.kt` (`Effect.StartBluetoothSco` arm),
  `core/DictateInputMethodService.java` (`pendingRecordOnBind`, hoisted binder guard)
- Tests: `StartRecordingPreBindGuardTest.kt`, `InputViewRetentionPolicyTest.kt`
- Commits: `f597c6c4` (wave 1 merge), `fa16003b` (hoisted guard), `f4fc1139` (bootstrap render),
  `6eb9ae25` (view retention), `048fb37c` (wave 2 merge)
- Measurements (untracked): `tmp/perf/2026-07-17-static-performance-analysis.md`,
  `tmp/perf/2026-07-17-measurements.md`, `tmp/perf/2026-07-18-wave2-render-spec.md`

**Upstream evidence** (paths @ `upstream/main` `3e5ebe46`)
- `app/src/main/kotlin/dev/patrickgold/florisboard/FlorisImeService.kt:312-318` (stock
  `onCreateInputView`)
- `app/.../dictate/DictateController.kt:112-131` (`DictateLatency` / `BatchLatencyTrace`),
  `:1064-1086` and `:1494` (phase stamps), `:975-980` (off-main realtime open), `:985-987`
  (`SpeechGate.prewarm`)
- `52ed77f0` (merge of `feature/openrouter-latency`, the 39-commit experiment/revert campaign)
- Issues: #232 (silence trimming), #235 (push-to-talk, the feature the main-thread stall broke)
