# Audit Report: logic (Block 5, scope: full-block)

**Agent-ID:** B5-AUDIT-LOGIC
**Date:** 2026-05-15
**Knowledge skills used:** knowledge-typescript (exhaustiveness-checks pattern — sealed-class `when` exhaustiveness), knowledge-reference (architectural-pattern baseline)
**Files inspected:** 13
- `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayBackend.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayWindow.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayPermissionObserver.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/overlay/DefaultOverlayPermissionGate.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayDragController.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayPositionMapper.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/OverlayModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/ViewModeModule.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt` (onCrossModuleStateChange)
- `app/src/main/java/net/devemperor/dictate/state/layout/KeyboardLayoutManager.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt`
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt` (diff)

## Summary

- Critical: 1
- Important: 4
- Nice-to-have: 3

## Findings

### AUDIT-LOGIC-B5-1

- **Severity:** Critical
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:110` + `ActionResolvers.kt` (no overlay-permission resolver) + `OverlayModule.kt:146-149`
- **Description:** The **in-IME onboarding-trigger path is entirely missing**, although it is explicitly in this block's scope. C17's chunks.json description names "Onboarding-UI im IME-View (Erste-Mal vs. Wieder-Verweigert-Logik), Activity-Result-Handling" with `spec_references` §5.3 + §5.4. Today: when the user taps the keyboard `WIDGET_TOGGLE` button without overlay permission, the resolver emits `Action.ViewModeAction.ToggleViewModeWidget`; `ViewModeModule.reduce` returns `null` (silent no-op at `ViewModeModule.kt:116-117`); **nothing** dispatches `Action.OverlayAction.RequestOverlayPermission`, **nothing** sets `state.overlay.onboardingPending = true`, and there is no `bindPermissionInfoBar` rendering. Net effect: a permission-less user gets a 100 %-silent dead button with zero feedback and no path to grant the permission from the keyboard. The standalone `OverlayPermissionOnboardingActivity` is a *secondary* surface (its own KDoc says the in-IME info-bar is "the high-traffic path") and is never launched by any code in the diff either, so the feature is functionally unreachable for the primary flow. C17 deferred this to C18; C18's "Overlooked points" forward it to "Phase 4 Integration" — it fell through every chunk and is undelivered at block end.
- **Why it matters:** This is the primary user-facing entry into the entire floating-overlay feature. Without it the WIDGET mode is unreachable for any user who has not already granted `SYSTEM_ALERT_WINDOW` out-of-band. Spec 3 §5.3/§5.4 designs this as the core UX. It is in-scope work that was not done, not a deliberate phase-deferral (the spec marks only the *trigger-arm design choice* as an implementer decision, not the whole path).
- **Suggested fix scope:** medium — needs (a) an `onboardingPending=true` trigger (a `ShowOnboarding` arm or extending `RequestOverlayPermission` per the §5.4 implementer-note), (b) an overlay-permission resolver on the disabled `WIDGET_TOGGLE` that dispatches it when `!state.overlay.hasPermission`, and (c) `ImeViewBackend.bindPermissionInfoBar` render binding (§5.3). Cross-references C17 + C18 + the ImeViewBackend (B4).
- **Suggested fix:** needs research — the §5.4 note ("Auslöser TBD: entweder ein neuer `Action.OverlayAction.RequestOverlayPermission`-Arm … der `onboardingPending = true` setzt, oder ein expliziter `ShowOnboarding`-Reducer-Arm") is an open design choice that must be resolved before implementing. Route via repair-sub-phase / orchestrator.

### AUDIT-LOGIC-B5-2

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayBackend.kt:226-233`
- **Description:** First-render attach race / window-leak window. `render()` calls `if (overlayView == null) inflateAndAttach()`. Inside `inflateAndAttach()` (line 296-298) `overlayView = view` is set **only after** `overlayWindow.attach()` succeeds. If `attach()` catches `BadTokenException` (permission revoked between the state-gate at line 206 and the `addView` call), `overlayView` stays `null` and the method returns. Back in `render()`, line 227 `if (!overlayWindow.isAttached()) return` correctly bails. So far so good. **But** the next render-tick still has `overlayView == null` while `state.overlay.hasPermission` may still be `true` (the `OverlayPermissionObserver` has not yet refreshed — it is lifecycle-triggered, not polled, per `OverlayPermissionObserver` KDoc). The backend therefore re-enters `inflateAndAttach()` and re-inflates a fresh `View` + retries `addView` on **every state emit** until the observer eventually refreshes. Each retry inflates a new view (cheap-ish but unbounded allocation churn under a rapidly-emitting pipeline) and repeatedly logs at WARN. The comment at line 228-232 assumes the observer "refreshes" promptly, but there is no IME-side `refresh()` wiring in this block (confirmed: C17 + C18 reports both forward IME-side `observer.refresh()` as un-wired). So in a runtime-revoke scenario the only thing that recovers the state axis is the `OverlayModule.onCrossModuleStateChange` permission-loss cascade — which is driven *by* `hasPermission` flipping, which only the (un-wired) observer does. The recovery path documented in `OverlayWindow.kt:33-38` ("the next render-tick attempts a clean re-attach which then bails at the permission gate") is therefore not closed within B5.
- **Why it matters:** Under a real runtime permission-revoke while WIDGET/HOVER is active, the overlay backend busy-retries inflate+addView on every pipeline emit (potentially many per second during transcription) with no upper bound until an IME lifecycle event happens to fire. Not a hard crash (per-emit isolation in `DictatePipelineService` catches throws) but a real resource/log-spam defect and a state-axis-stuck condition.
- **Suggested fix scope:** medium — either wire the IME-side `observer.refresh()` (the C17/C18-forwarded item), or add a backend-local "attach failed, suppress retry until next permission-change" latch. Needs the C17-forwarded refresh decision.
- **Suggested fix:** needs research (depends on the IME-refresh-wiring decision forwarded out of C17/C18).

### AUDIT-LOGIC-B5-3

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayPositionMapper.kt:92-95` vs `112-116`
- **Description:** Asymmetric clamp denominator between the two conversion directions breaks the round-trip invariant at the zero-free-area edge. `normalizedToPixels` uses `maxX = (screenW - viewW).coerceAtLeast(0)` then `px = (normX * maxX)` — so when the view fills the screen, `maxX == 0` and every `normX` maps to `px = 0`. `pixelsToNormalized` uses `maxX = (screenW - viewW).coerceAtLeast(1)` (note: `1`, not `0`) then `nx = (px / maxX).coerceIn(0,1)`. The inverse functions therefore disagree on the degenerate denominator: `normalizedToPixels(1.0)` → `0`, but `pixelsToNormalized(0)` → `0.0`. A drag that ends with the view exactly screen-sized persists `0.0` even though the default/previous value was `1.0f` (the right-edge anchor — `OverlayState.positionPortraitX` default is `1.0f`). The mapper KDoc claims it is "the single SoT … keeps the two call-sites in sync"; the asymmetric `coerceAtLeast` floor silently violates that.
- **Why it matters:** Edge case (full-screen overlay is rare on a phone), but on small/split-screen or a very wide overlay it silently rewrites the persisted anchor from "right edge" (1.0) to "left edge" (0.0) on the first drag-end, moving the overlay across the screen on the next orientation/session. Boundary-condition correctness defect in the designated single-source-of-truth converter.
- **Suggested fix scope:** small — make both directions use the same floor (either both `coerceAtLeast(1)` or guard the zero case symmetrically so the round-trip is identity at the boundary).
- **Suggested fix:** Use a shared `private fun freeArea(screen, view) = (screen - view)` and a single zero-guard policy applied identically in both directions.

### AUDIT-LOGIC-B5-4

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayBackend.kt:316-330` (`onPositionPersist`) + `OverlayDragController.kt:247-250` (`persistCurrentPosition`)
- **Description:** Orientation-read race on drag-end persistence. The drag controller computes the normalised position via `positionMapper.pixelsToNormalized(...)` and hands `(normX, normY)` to `onPositionPersist`. The backend's `onPositionPersist` lambda then **independently** reads `isPortraitOrientation()` to decide which pref bucket (`portrait` flag) the `UpdateOverlayPosition` action targets. The pixel→normalised conversion and the orientation read are two separate reads of `ctx.resources.configuration`. If a configuration change lands between the controller's `pixelsToNormalized` call (which uses `displaySize()` from the *pre-rotation* metrics) and the backend lambda's `isPortraitOrientation()` (which may observe the *post-rotation* configuration), the normalised value is computed against the old screen geometry but persisted into the *new* orientation's pref bucket. Result: a corrupted position in the wrong bucket. The R.18 mid-drag-detach path (`OverlayDragController.detach()` → `persistCurrentPosition`) has the same split-read shape since `detach()` runs from `teardownOverlay()` which can be invoked from a transition triggered by a config-change.
- **Why it matters:** Rotation-during-drag is a realistic gesture (one-handed reposition while the device rotates). Persisting a stale-geometry coordinate into the freshly-rotated bucket places the overlay at a wrong location after rotation. Spec 3 §11.5.6 explicitly calls out per-orientation bucketing as a correctness concern.
- **Suggested fix scope:** medium — capture the orientation **once** at gesture start (`ACTION_DOWN`) inside the controller and thread it through `onPositionPersist` together with the coords, so the bucket and the geometry that produced the normalised value are from the same configuration snapshot. (The controller KDoc currently asserts "the controller does not know which orientation it's in (orientation discrimination lives in the backend)" — that SRP split is exactly what creates the split-read; revisit.)
- **Suggested fix:** needs research (touches the controller↔backend SRP boundary the C18 author deliberately drew).

### AUDIT-LOGIC-B5-5

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/OverlayModule.kt:239-251` (HOVER→KEYBOARD cascade) + `DictateOrchestrator.kt:374-386`
- **Description:** The HOVER→KEYBOARD cancel-cascade (F-7 fix) emits an additive list: `SuppressAutoOverlayUntilNextSession`, then conditionally `CancelRecording`, then conditionally `CancelPipeline`. These are dispatched serially at `depth+1` with re-snapshotting (orchestrator Step 6). `CancelRecording` itself triggers `RecordingModule.onCrossModuleStateChange` and the recording→pipeline send-cascade, which can dispatch further actions at `depth+2`, `depth+3`… Combined with the also-emitted `CancelPipeline` and the `ViewModeModule` already being mid-cascade (the HOVER→KEYBOARD transition is itself frequently a cascade product of `OnPipelineDone`/`CloseOverlay` at `depth≥1`), the worst-case both-in-flight path stacks: e.g. `OnPipelineDone`(d0) → ViewMode KEYBOARD → Overlay cascade(d1): Suppress + CancelRecording + CancelPipeline → CancelRecording(d1) → recording observers(d2) → … The B5-floating-overlay report and the cross-block audit dimension call out `MAX_CASCADE_DEPTH=8`. I could not find a test that exercises the *both-recording-and-pipeline-in-flight HOVER-close while already nested in a pipeline-done cascade* worst-case to prove it stays < 8. The F-7 disambiguation comment (OverlayModule.kt:209-221) reasons about correctness of the additive list but not about the resulting depth budget when this fires from an already-deep cascade.
- **Why it matters:** If the realistic worst-case ever reaches depth 8, the orchestrator `error()`s in DEBUG and silently `Rejected`s in release, dropping a `CancelPipeline`/`CancelRecording` — i.e. the user closes HOVER, the pipeline keeps running, and a transcript the user opted out of gets produced (the exact bug F-7 was meant to fix). Unverified depth budget for a state-losing path.
- **Suggested fix scope:** medium — add a worst-case cascade-depth assertion test for the both-in-flight HOVER-close-from-pipeline-done path; if it approaches the cap, restructure (e.g. emit a single coalesced cancel action).
- **Suggested fix:** needs research (cross-block: depends on B2's cascade machinery and the recording→pipeline cascade fan-out).

### AUDIT-LOGIC-B5-6

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayBackend.kt:206-216`
- **Description:** Suppress-bit teardown is correct for the auto-HOVER case but coupled to the permission teardown via identical `teardownOverlay()`. On `suppressAutoOverlayUntilNextSession == true` the backend tears down completely. This is correct per Spec 3 §4.2. However, the suppress bit is reset by `RecordingModule.onCrossModuleStateChange` only on the `Idle → Preparing` boundary (`RecordingModule.kt:445-446`). Trace: user closes HOVER (suppress=true, viewMode→KEYBOARD via cascade) → `syncOverlayBackendAttachment` detaches the backend (viewMode==KEYBOARD) → backend already torn down. So the suppress-bit teardown branch in `render()` is only reachable if the backend is still attached while suppress is set, i.e. WIDGET/HOVER **with** suppress=true. That combination is possible: HOVER suppress fires `SetUserPrefersWidget` is NOT cleared, so if the user reopens IME with `userPrefersWidget==true` they land in WIDGET while `suppressAutoOverlayUntilNextSession` is still `true` (it only resets on next recording-start). In WIDGET the user explicitly wants the overlay, but `render()` line 213-216 will `teardownOverlay()` because the suppress bit is still set — the explicitly-requested WIDGET overlay is suppressed until the next recording starts. This contradicts the suppress-bit's documented intent ("don't *auto*-reopen the HOVER overlay" — it should not gate an *explicit* WIDGET open).
- **Why it matters:** A user who closes the auto-HOVER overlay and then explicitly toggles the WIDGET keyboard-overlay sees nothing until they start a new recording. Mis-scoped suppression (suppress should gate auto-HOVER only, not explicit WIDGET).
- **Suggested fix scope:** small — gate the suppress-teardown on `state.viewMode == ViewMode.HOVER` (suppress only suppresses the auto path, not explicit WIDGET).
- **Suggested fix:** `if (state.overlay.suppressAutoOverlayUntilNextSession && state.viewMode == ViewMode.HOVER) { teardownOverlay(); return }`. Verify against Spec 3 §4.8 + §7.3 before applying (interaction with `userPrefersWidget` lifecycle).

### AUDIT-LOGIC-B5-7

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/overlay/OverlayDragController.kt:193`
- **Description:** Drag-threshold promotion uses `hypot(dx, dy) > dragThresholdPx` (strict `>`) once, in `ACTION_MOVE`, and `dragThresholdPx` is computed once as an `Int` via `.toInt()` truncation of `scaledTouchSlop * 1.5f`. Two minor robustness gaps: (a) the threshold is only re-checked on `ACTION_MOVE`, so a single large jump event that goes DOWN→(no MOVE)→UP can never become a drag (acceptable — taps don't move), but a very fast flick that produces one MOVE far past the threshold then UP is correctly handled — fine. (b) `.toInt()` truncates `scaledTouchSlop*1.5` downward; combined with the `baseDp` floor this only ever lowers the threshold by <1px — negligible. The real nit: there is no guard against `paramsHolder()` returning a *different* params instance between `ACTION_DOWN` (captures `initialParamsX/Y`) and subsequent `ACTION_MOVE`. The backend mutates `currentParams.x/y` in place (so the reference is stable in the happy path), but `teardownOverlay()` sets `currentParams = null` and a re-inflate creates a fresh params object; if a re-inflate races a mid-gesture the controller would read `initialParamsX` from the old object and apply deltas to the new one. The controller is detached on teardown (`teardownOverlay` calls `dragController?.detach()` first) so the listener is removed before the params swap — the race is closed by ordering, but it is implicit and undocumented at the controller site.
- **Why it matters:** Defensive only; the teardown ordering currently closes the race. Worth a comment so a future refactor of teardown ordering doesn't silently reopen it.
- **Suggested fix scope:** small — add a one-line invariant comment in `OverlayDragController` documenting the "detach-before-params-swap" dependency on the backend's teardown ordering.
- **Suggested fix:** comment only.

### AUDIT-LOGIC-B5-8

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt` (`syncOverlayBackendAttachment`, ~line 600-630 of HEAD)
- **Description:** The flag-first ordering (`overlayBackendAttached = true` before `attachBackend`) is correctly reasoned for the *attach* path (a throwing first-render still bookkeeps as attached so the matching detach fires — no window leak). The symmetric *detach* path sets `overlayBackendAttached = false` **before** `keyboardLayoutManagerImpl.detachBackend(backend)`. `KeyboardLayoutManager.detachBackend` calls `backend.detach()` → `OverlayBackend.teardownOverlay()` which can throw (it catches its own throwables, but `Log.w`/reflection edge). If `detachBackend` threw after the flag was already cleared, the collector's `try/catch` isolates it, but the backend would remain in `activeBackends` (the `remove` in `KeyboardLayoutManager.detachBackend` happens before `backend.detach()`, so actually it is removed first — OK) while `overlayBackendAttached == false`. On the next `viewMode != KEYBOARD` transition the code would `attachBackend` again; `KeyboardLayoutManager.attachBackend` has a `check(backend !in activeBackends)` — since the backend *was* removed from the list before the throw, re-attach succeeds. So the invariant actually holds by virtue of `detachBackend` removing-before-detaching. This is correct but, like B5-7, entirely implicit: the safety of the detach flag-ordering depends on a `KeyboardLayoutManager.detachBackend` implementation detail (remove-before-detach) that is not referenced at the `syncOverlayBackendAttachment` site, whereas the *attach* ordering is heavily commented.
- **Why it matters:** Defensive/maintainability. The asymmetry (attach path exhaustively commented, detach path relies on an un-cited cross-class ordering guarantee) is a maintenance trap.
- **Suggested fix scope:** small — add a comment on the detach branch noting the dependency on `KeyboardLayoutManager.detachBackend`'s remove-before-detach ordering.
- **Suggested fix:** comment only.

## Coverage

- **Files audited:** all 13 listed above (full block diff `74f9dd3..HEAD` for production code; plan §4.8 / §5.0–§5.7 / §7.x and chunks.json C16–C18 cross-checked).
- **Triangle-FSM T1–T7:** verified exhaustive. The `viewMode != KEYBOARD` collapse in `syncOverlayBackendAttachment` is provably correct for all 7 transitions (T1/T3 → attach; T2/T5/T7 → detach; T4/T6 → no churn, both endpoints non-KEYBOARD). T7 is structurally identical to T5 (both settle on KEYBOARD via `ViewModeModule.OnPipelineDone` → `computeViewMode(pipelineActive=false)` → KEYBOARD); the Geist-Widget-Bug regression is **not** re-introduced — the FSM correctly drops HOVER to KEYBOARD on pipeline-done and the collector then detaches. No finding here.
- **Sealed-class exhaustiveness (knowledge-typescript checkpoint):** `OverlayModule.reduce`, `ViewModeModule.reduce`, `resolveOverlayCloseAction`, `KeyboardLayoutManager.computeLayoutMode` are all exhaustive `when` over sealed/enum with no bug-hiding `else` swallowing a live variant. Clean.
- **Reducer purity (R.2):** confirmed `OverlayModule.reduce` never calls `Settings.canDrawOverlays()`; the `OverlayPermissionObserver` is the only live source. Clean.
- **AndroidOverlayWindow exception hygiene (C16):** BadToken-on-attach / IllegalArgument-on-update/detach all caught, `attached` flag transitions correct, idempotent. Clean.
- **WIDGET-vs-HOVER differential:** Record/Send `enabledResolver` correctly gates on `viewMode == WIDGET`; `resolveOverlayRecordAction` defensively returns `null` outside WIDGET. Clean.
- **Files skipped (with reason):** XML/drawable/strings resources (no logic), test files (out of scope for `logic` topic — covered by `AUDIT-TEST`).
- **Knowledge-skill checkpoints applied:** exhaustiveness-checks (Technique A/B/C — verified no anti-pattern `default: return state`-equivalent); cross-language race/boundary baseline from knowledge-reference.

## Out-of-scope observations (for the consolidator)

- **plan-and-api:** the C17 chunk scope item "Activity-Result-Handling" + "Onboarding-UI im IME-View" (§5.3/§5.4) is undelivered — see B5-1; the `plan-and-api` audit should also flag this as a plan-conformity gap, not only a logic defect.
- **convention:** `OverlayBackend` heavily comments the attach-flag ordering but leaves the symmetric detach-flag ordering (B5-8) and the drag-params-stability invariant (B5-7) implicit — a documentation-consistency convention nit.
