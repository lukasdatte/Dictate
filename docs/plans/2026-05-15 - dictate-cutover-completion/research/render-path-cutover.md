---
status: Spec — programmer-ready
---

# Spec — Render-Path Cutover (Theme C-R)

**Date:** 2026-05-16
**Triggered by:** C10-IMPL-2 (Critical `architecture-conflict`, `B3-C10-C3-IMPL`, block-report `reports/B3-theme-c-legacy-retire.md` §"Chunk C10-C3")
**Block:** new Theme-C-R block, inserted between C9-C2 and the (repurposed) C10-C3 deletion
**Agent-ID:** B0-RENDER-CUTOVER-PLAN
**SoT:** Spec 2 §9.1–§9.6 + §11.5–§11.8 + §13 (`../2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md`); Spec 1 §9.6 (`../2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md`). This spec **adopts** those dispositions; it does not redesign them.

> [!CAUTION]
> This is destructive UI surgery on a working keyboard. Recording + the
> keyboard UI are the product. The legacy controllers are the live render
> path **today**; the new `RenderBackend` path runs in parallel but
> incomplete. Every behaviour group below MUST have a proven new owner
> **before** the C10-C3 4-class deletion is allowed (the deletion is the
> final gated step — §6).

---

## 1. Context & Trigger

The `dictate-cutover-completion` Epic's Theme B did the **recording-drive**
cutover (`JobExecutor.start` → `pipelineBinder.dispatch`). It did **not**
touch the **render path**. C10-C3 was written on the false premise that
"Theme B + the parent RenderBackend path made the 4 controllers dead". The
mandatory per-class responsibility-trace (`reports/B3-theme-c-legacy-retire.md`
§"MANDATORY per-class responsibility-trace") proved the premise false and
blocked all 4 deletions:

- `ImeViewBackend` (`state/render/ImeViewBackend.kt`, 287 LOC) is attached
  **in parallel** to the legacy controllers (`DictateInputMethodService.java:1023`
  `attachImeViewBackendIfReady`, documented at `:1014-1017`: *"the record
  path still works through the legacy MainButtonsController +
  KeyboardUiController + RecordingUiController flow"*).
- It wires **plain click + RESEND long-press only**
  (`ImeViewBackend.kt:252-276`); `staticHandlerInstaller` is wired **`null`**
  (`DictateInputMethodService.java:1113`).
- `ContentAreaController` / `PromptVisibilityController` / `OverlayResetHandler`
  (`state/render/*.kt`) **exist + are unit-tested** but are **not attached**
  in the IME (`grep` empty; parent B4-VAL F-6/F-33 KDoc anchors say so).
- Parent B4-VAL **F-1** removed BACKSPACE long-press wiring; **F-2** removed
  RECORD long-press wiring — deliberately, so the legacy
  `MainButtonsController` handlers survive — and deferred the proper port to
  a *"B5/B7 / D-13 follow-up"* that **was never created** (the exact INT-1
  parallel-dormant anti-pattern, recurring at the render layer).

This spec defines the render-path cutover (Theme C-R) that wires the new
render owners into the IME, ports the deferred behaviour groups per the
Spec 2 §9.x SoT, removes the legacy driver calls, and only then permits the
4-class deletion.

---

## 2. Acceptance Criteria

> §2-heading per UDOC Spec genre.

### 2.1 Per-group behaviour parity

For every behaviour group in §3, the new owner reproduces the legacy
behaviour with **no user-visible regression**. Specifically:

- **AC-RR-1 (long-press parity).** RECORD long-press: Idle → open Settings +
  audio-file picker; Active/Paused → set `autoSwitchKeyboard=true` then stop.
  BACKSPACE long-press: accelerated-delete cascade survives (no
  event-consuming `{ true }` listener on the new path).
- **AC-RR-2 (touch parity).** SPACE cursor-swipe, BACKSPACE swipe-delete,
  ENTER overlay all behave as on the legacy path (handled via
  `staticHandlerInstaller`, Spec 2 §11.7).
- **AC-RR-3 (theming + animation parity).** Accent-colour theming,
  key-press animation, small-mode/edit-numbers bounce, overlay-characters
  rendering all visible on the new path.
- **AC-RR-4 (QWERTZ + pipeline-progress parity).** QWERTZ rec-button,
  prompts-visualizer, pipeline step-row UI render identically (these
  `BLEIBT` per Spec 2 §13 — they remain in their owning class but the IME
  drive-path collapses onto state).
- **AC-RR-5 (content/prompt visibility parity).** `ContentAreaController` +
  `PromptVisibilityController` + `OverlayResetHandler` attached and driving
  the visibility axes; KSM thinned to its still-owned axes per Spec 2 §9.3 +
  §13 (`BLEIBT` rows) — **no double-write** on any visibility axis
  (Strict-Mode-Logging gate, Spec 2 §11.8 5c).

### 2.2 Single-architecture (no parallel-dormant render layer)

- **AC-RR-6 (single render driver).** After Theme C-R, the IME no longer
  calls the legacy controller drive methods
  (`recordingUiController.onStateChanged/onAmplitudeUpdate/onTimerTick`,
  `uiController.startPipeline/addRunningStep/completeStep/...`,
  `stateManager.setContentArea/refresh`, `mainButtonsController.*`). The
  `RenderBackend` path is the sole render driver. This is the render-layer
  analogue of Epic AC-10.

### 2.3 Compile invariant (the deletion gate output)

- **AC-RR-7 (compile-invariant greps — repurposed C10-C3 / Epic AC-7).**
  After the deletion chunk:
  - `grep -rl "MainButtonsController" app/src/main/` → zero
  - `grep -rl "RecordingUiController" app/src/main/` → zero
  - `grep -rl "KeyboardStateManager" app/src/main/` → zero
  - `grep -rl "KeyboardUiController" app/src/main/` → zero
    (note: behaviour the spec marks `BLEIBT` migrated to a *renamed/new*
    owner, not preserved under the old class name — see §3 dispositions)
  - `PipelineOrchestrator` **still present** (Spec 1 §9.6 — never deleted;
    OQ-1 boundary KDoc already added by C10-C3).
- **AC-RR-8 (build + regression).** `./gradlew assembleDebug` green;
  `./gradlew test` green (≥ the Epic baseline ~1048 tests, no net
  behaviour-coverage deletion — Epic AC-9).

### 2.4 Verification gate

- **AC-RR-9 (render-gate GREEN before deletion).** A D2pre-style render
  verification chunk (§5 R-GATE) proves AC-RR-1..AC-RR-6 on the new path
  **with the legacy path still present behind it**. A GREEN R-GATE run is
  the hard precondition for the deletion chunk. RED → triage; no deletion.

---

## 3. Per-Behaviour-Group → Target-Owner Mapping (the SoT table)

Legend for "Spec disposition":
- **MOVE** = behaviour ported to a *new* RenderBackend owner (Spec 2 §9.x).
- **BLEIBT** = behaviour stays in its (possibly renamed) owning class, but
  the **IME drive-path** collapses onto state/RenderBackend (Spec 2 §13
  `BLEIBT` rows). The class on the kill-list keeps only its still-owned axis
  under a new owner name where §9.x renames it.
- **ALREADY DONE** = parent C15 already did it.

| # | Behaviour group | Current legacy source (file:line) | Spec 2 §9.x / §13 SoT owner | Disposition | Notes |
|---|---|---|---|---|---|
| G1 | **MotionScene layout-mode** (single/two-row, send-mode, staging) | `KeyboardLayoutModeController.kt` (already deleted, parent C15) | `motion_scene_keyboard.xml` + `ImeViewBackend.render` `transitionToState` (Spec 2 §9.1) | ALREADY DONE | Verified by C10-C3 (`KeyboardLayoutModeController` source absent; 5 comment-only anchors). No action. |
| G2 | **RECORD long-press 2-mode** (Idle→Settings+file-picker / Active→autoSwitch+stop) | `MainButtonsController.kt:163-166` (`onRecordLongClicked` callback → IME `onRecordLongClicked`) | `ImeViewBackend.wireStaticHandlers` long-press → `Action.RecordingAction.*` via a slot `longClickResolver` (Spec 2 §9.2 row `registerMainButtonListeners`; §13.2 *"backspace/RECORD long-click → konkrete Long-Action"*) | MOVE | Parent B4-VAL F-2 dropped the wire (Option c interim) so the legacy listener survives. Theme C-R adds the real long-press model. **Spec 2 §6/§13.2 specify a `longClickResolver` slot field but do not give its body** — see §7 Ambiguity A1. |
| G3 | **BACKSPACE accelerated-delete** (long-press → `deleteHandler.postDelayed` cascade) | `MainButtonsController.kt:185-194` (`backspaceButton.setOnLongClickListener` + `BackspaceSwipeHandler`) | `ImeViewBackend.buildBackspaceSwipeHandler()` via `staticHandlerInstaller` (Spec 2 §9.2 row `:189-194`; §11.7 `BackspaceSwipeHandler` builder) | MOVE | Parent B4-VAL F-1 dropped the bare `{ true }` consumer-listener. The accel-delete cascade is part of `BackspaceSwipeHandler` (§11.7) wired via the installer hook. |
| G4 | **SPACE cursor-swipe touch** | `MainButtonsController.kt:203-232` (`CursorSwipeTouchHandler` on `space_btn`) | `ImeViewBackend.buildSpaceTouchHandler()` via `staticHandlerInstaller` (Spec 2 §9.2 row `:203-232`; §11.7 CursorSwipe builder, full code) | MOVE | §11.7 gives the exact builder body (onTap/onCursorMove/onSwipeStateChanged + compound-drawable swap). |
| G5 | **ENTER overlay touch** | `MainButtonsController.kt:249-259` (`EnterOverlayHandler` on `enter_btn`) | `ImeViewBackend.buildEnterOverlayHandler()` via `staticHandlerInstaller` (Spec 2 §9.2 row `:254-259`; §11.7 EnterOverlay builder) | MOVE | §11.7 gives the builder. `overlayCharactersLl.visibility` reset stays handler-internal + `OverlayResetHandler` (G10) is the defensive belt. |
| G6 | **Theming** (`applyTheme(accentColor)` button colours + overlay chars) | `MainButtonsController.kt:389-416` (`applyTheme`), `:481-493` (`updateOverlayCharacters`) | `ImeViewBackend.applyTheme(accentColor)` invoked by the service after each re-inflate (Spec 2 §9.2 rows `:389-416` "bleibt erhalten — separate Achse", `:481-493` "bleibt — overlay-spezifisch") | MOVE (to ImeViewBackend method) | Spec 2 §9.2 explicitly says theme is a **separate axis, not state-driven**; the new backend exposes an `applyTheme` method the service calls. **`ImeViewBackend` has no `applyTheme` method today** — see §7 Ambiguity A2. |
| G7 | **Key-press animation** | `MainButtonsController.kt:303-319` (`initializeKeyPressAnimations`) | `ImeViewBackend.wireStaticHandlers` calls `keyPressAnimator.applyPressAnimation(view)` per button (Spec 2 §9.2 row `:303-319`) | MOVE | Backend already takes a `KeyPressAnimator` in the Spec 2 §6 constructor; **the current `ImeViewBackend.kt` constructor does NOT carry `keyPressAnimator`** (`state/render/ImeViewBackend.kt:85-93`) — see §7 Ambiguity A2. |
| G8 | **`setResendEnabled` 500 ms cooldown** | `MainButtonsController.kt:331-333` | orthogonal `view.isEnabled` mutation via state-update → `enabledResolver` (Spec 2 §9.2 row `:331-333` + §11.6-lifecycle note; `state.resend.resendCooldown` per §13) | MOVE | Cooldown becomes a state field driving the RESEND `enabledResolver`. Verify `state.resend.resendCooldown` exists post-Theme-A/B; if not, flag (small state-shape add or read existing). |
| G9 | **QWERTZ rec-button + prompts-visualizer + prompt-bar controls** | `RecordingUiController.kt:222-277` (`updateQwertzRecButton`/`enterPipelineDisplay`/`updatePipelineTimer`), `:62-82` amplitude/timer; IME `servicePipelineCallback` `:940/:969-990`, `:1376/:1381` | **BLEIBT** in `RecordingUiController` (Spec 2 §9.4 rows `:222-246`/`:254-277` "bleibt — QWERTZ-spezifisch"; §13 rows 7-10/20 `BLEIBT` Promptbar) — but amplitude/timer move to `RecordingAnimationController` (already wired into `ImeViewBackend`, §11.5) | SPLIT (BLEIBT + MOVE) | The QWERTZ + prompts-visualizer rendering stays; the IME's direct `recordingUiController.onAmplitudeUpdate/onTimerTick` drive collapses to the backend forwards `ImeViewBackend.onAmplitude/onTimerTick` (already exist, `:200-209`). **`RecordingUiController` cannot be deleted by C10-C3** under §9.4 if QWERTZ stays in it — see §7 Ambiguity A3 (the disposition splits the class; the kill-list assumes full deletion). |
| G10 | **Content-area visibility** (`mainButtonsCl`/`qwertz_container`/`emojiPicker_container` GONE/VISIBLE) | `KeyboardStateManager.kt:171-181` (`applyContentAreaVisibility`); IME `:1169/:1172/:1184-1185` `stateManager.setContentArea/refresh` | `ContentAreaController` (Spec 2 §9.3, R.10 split; exists at `state/render/ContentAreaController.kt`, `backendType=null`) | MOVE (attach existing) | Class exists + tested; **not IME-attached**. Theme C-R attaches it via `KeyboardLayoutManager.attachBackend`. KSM `BLEIBT` for its still-owned axis per Spec 2 §13 rows 1-4 (`BLEIBT — ContentArea-Achse`) until the deletion chunk. |
| G11 | **Prompts visibility + pipelineProgress swap-in** | `KeyboardStateManager.kt:194-224` (`applyPromptsVisibility`/`applyPromptsLayout`); §13 rows 7-10 | `PromptVisibilityController` (Spec 2 §9.3; exists at `state/render/PromptVisibilityController.kt`, `backendType=null`) | MOVE (attach existing) | Class exists + tested; not attached. Theme C-R attaches it. Truth-table per parent B4 `PromptVisibilityController` KDoc + Spec 2 §9.3. |
| G12 | **Overlay-characters defensive reset** | `KeyboardStateManager.kt:162` (`overlayCharactersLl.visibility = GONE` reset) | `OverlayResetHandler` (Spec 2 §9.3; exists at `state/render/OverlayResetHandler.kt`, `backendType=null`) | MOVE (attach existing) | Class exists + tested; not attached. Theme C-R attaches it. §13 row 11 `BLEIBT` defensive-reset semantics preserved. |
| G13 | **Pipeline-progress / step-row UI** (`stepRows`, `item_pipeline_step_row`, live per-step timers) + **IME pipeline-UI driver** | `KeyboardUiController.kt:383-448` (step-row binding); IME `:1295/:1301/:1450/:1470/:1476/:2559/:2713/:2717/:3528` (`uiController.startPipeline/addRunningStep/completeStep/failStep/...`); KSM/RUC lambdas `:786/:788/:878/:881` | **BLEIBT** in `KeyboardUiController` View-side (Spec 1 §9.2 "stepRows bleibt im KeyboardUiController View-side"; Spec 2 §13 row 20 `BLEIBT` Pipeline-Step-internal). The **state half** → `PipelineModule.reduce` via `Action.PipelineAction.*` (Spec 2 §9.5; Spec 1 §9.2). | SPLIT (BLEIBT + MOVE) | The IME's direct `uiController.*` pipeline drive collapses onto `dispatch(Action.PipelineAction.*)` + reactive state; step-row *rendering* stays. **`KeyboardUiController` cannot be fully deleted** under §9.5/§13-row-20 if step-rows stay — see §7 Ambiguity A3. `PipelineUiStateReader`/`FakePipelineUiStateReader` consumers must be re-pointed or the step-row reader kept. |
| G14 | **`refreshAudioFocusIcon` / record-button text** | `MainButtonsController.kt:344-346` (`updateRecordButtonText`), `:368-387` (`refreshAudioFocusIcon`) | RECORD slot `textResolver` + AUDIO_FOCUS slot `iconResolver` (Spec 2 §9.2 rows `:344-346`/`:368-387`; resolvers already in `LayoutCatalog`) | MOVE | Resolvers exist (parent B4). The IME drive (`mainButtonsController.refreshAudioFocusIcon`, IME `:833/:921`, `updateRecordButtonText`) collapses onto state. Note parent B4 `LayoutStrings` is not yet language-aware (D-13 follow-up / Epic C8 handles `LanguageState.effective` — already done in B3 C8); re-verify after C8. |
| G15 | **`animateSmallModeToggle` / `animateEditNumbersBounce`** | `MainButtonsController.kt:424-437` / `:452-477` | extracted `EditNumbersAnimator` helper (Spec 2 §9.2 rows `:424-437`/`:452-477` "verbleibt im EditNumbersAnimator") | MOVE (extract helper) | Spec 2 §9.2 says extract an `EditNumbersAnimator`; **no such class exists yet** — see §7 Ambiguity A2. External animation on `edit_numbers_btn`, not a slot resolver. |
| G16 | **resend_btn visibility mutations (4)** | `DictateInputMethodService.java:1345/1347/1669/1839` | `predResendVisible` predicate + `Action.ResendAction.MarkLastAudio(exists)` dispatch (Spec 2 §9.6; §13 rows 27-28) | MOVE | Spec 2 §9.6 gives the exact replacement: `:1839` `onShowResend()` → `dispatch(Action.ResendAction.MarkLastAudio(exists=true))`; `:1345/:1347/:1669` deleted (predicate owns). `ResendAction.MarkLastAudio` defined Spec 2 §3.3. |

### 3.1 Where Spec 2 is the SoT (followed verbatim)

G1, G3, G4, G5, G7, G8, G10, G11, G12, G14, G16 — each row above cites the
exact Spec 2 §9.x/§11.x/§13 disposition and follows it. No invention.

### 3.2 Where Spec 2 is silent / ambiguous (flagged for orchestrator)

G2 (longClickResolver body), G6 (`ImeViewBackend.applyTheme` method),
G7+G15 (`keyPressAnimator` constructor param + `EditNumbersAnimator` class
not present in current code), G9+G13 (the §9.4/§9.5 dispositions **split**
the class — they are NOT pure deletes; the C10-C3 kill-list assumes full
deletion). See §7.

---

## 4. Code Reality vs. Spec 2 §6 (the delta the cutover must close)

| Spec 2 §6 prescribes | Current `state/render/ImeViewBackend.kt` | Delta Theme C-R must close |
|---|---|---|
| Constructor carries `keyPressAnimator: KeyPressAnimator` | constructor has **no** `keyPressAnimator` (`:85-93`) | add param + `keyPressAnimator.applyPressAnimation` in `wireStaticHandlers` (G7) |
| `wireStaticHandlers` wires RECORD long-press to a concrete long-action | wires **RESEND long-press only**; RECORD/BACKSPACE intentionally dropped (F-1/F-2 KDoc `:226-251`) | add `longClickResolver` model (G2) + remove the F-1/F-2 interim KDoc |
| `buildSpaceTouchHandler/buildBackspaceSwipeHandler/buildEnterOverlayHandler` inside backend | replaced by `staticHandlerInstaller` hook (parent B4 SRP/DIP deviation), wired **`null`** in IME (`DictateInputMethodService.java:1113`) | supply the real installer lambda from the IME (G3/G4/G5) |
| `ContentAreaController`/`PromptVisibilityController`/`OverlayResetHandler` attached | defined + tested, **not attached** (parent B4 F-6) | `attachBackend` all three from the IME (G10/G11/G12) |
| `applyTheme(accentColor)` method on backend | absent | add method; service calls after re-inflate (G6) |

> [!NOTE]
> The `staticHandlerInstaller` hook (a parent-B4 SRP/DIP improvement over
> Spec 2 §6's "builders inside the backend") is **kept** — it is a
> better-than-spec design (backend stays independent of IME-side handler
> classes). Theme C-R supplies the lambda, it does not revert the hook.

---

## 5. Chunk Breakdown & Dependency Order

Theme C-R is a new skill-block inserted **after C9-C2** and **before** the
(repurposed) C10-C3 deletion. Ordering is dependency-driven; the deletion is
the FINAL gated step.

```
C9-C2 (done)
   │
   ▼
[CR1] backend completion       ← longClickResolver + applyTheme + keyPressAnimator
   │                             (G2, G6, G7, G14, G16 state/resolver side)
   ▼
[CR2] special-touch installer  ← staticHandlerInstaller lambda from IME
   │                             (G3, G4, G5)  dep: CR1
   ▼
[CR3] visibility controllers   ← attach ContentAreaController +
   │   attach + KSM thinning     PromptVisibilityController + OverlayResetHandler;
   │                             KSM/RUC/KUC drive-calls collapse to state
   │                             (G9, G10, G11, G12, G13)  dep: CR2
   ▼
[CR4] IME legacy-driver removal ← remove mainButtonsController/recordingUiController/
   │   (behind no boolean —      uiController/stateManager drive-call sites in IME;
   │    the controllers still     controllers still INSTANTIATED (compile-safe) but
   │    compile, just unused)     no longer driven  (AC-RR-6)  dep: CR3
   ▼
[R-GATE] render verification    ← D2pre-style: prove AC-RR-1..6 GREEN on the
   │   GATE (D12-atomic)          new path with legacy classes still present.
   │                             GREEN authorises CR-DEL.  dep: CR4
   ▼
[CR-DEL] = repurposed C10-C3    ← delete MainButtonsController/RecordingUiController/
       4-class deletion           KeyboardUiController/KeyboardStateManager (+tests);
       (HARD-GATED on R-GATE)     keep+annotate PipelineOrchestrator (OQ-1 already
                                  done). AC-RR-7/8.  dep: R-GATE GREEN
```

**Why this order:**

1. **CR1 before CR2** — the installer lambda (CR2) wires touch handlers that
   reference the backend's resolver model; the backend must be
   feature-complete first.
2. **CR2 before CR3** — visibility controllers (CR3) must attach onto a
   backend whose static handlers are fully wired, else a view-recreate races
   a half-wired backend.
3. **CR3 before CR4** — the IME drive-call removal (CR4) is only safe once
   every visibility/animation axis has a *proven-attached* new owner;
   removing the drive calls before CR3 blanks the UI.
4. **CR4 before R-GATE** — the gate proves the *new-only* path; the legacy
   classes must still exist (compile-safe, undriven) so the gate measures
   the real cutover, and a RED gate can re-enable a drive call without a
   revert (the staged-safety-net pattern, Epic §6.2).
5. **R-GATE before CR-DEL** — mirrors Epic C6/D2-pre → C7. The 4-class
   deletion is the point of no return; it runs only on a GREEN gate.

**KSM thinning (Spec 2 §11.8 5c, §13 `BLEIBT` rows) is in CR3, not CR-DEL.**
Per Spec 2 §11.8: 5c gives the KSM still-owned-axis bodies their final form
+ Strict-Mode-Logging verifies no double-write; 5d (= CR-DEL) deletes the
class. Parent B4 already chose "KSM thinned to its still-owned axes" over
"empty bridge" (D7) — CR3 follows that.

---

## 6. Risk Register

Mirrors the Epic §6 staged-safety-net + a D2pre-style gate.

| ID | Risk | Severity | Mitigation |
|---|---|---|---|
| RR-1 | **Silent listener overwrite.** Wiring a new long-press/touch listener on the same View as a legacy one — Android keeps only the most-recent `setOnXListener` — silently erases a user feature (the exact F-1/F-2 trap). | HIGHEST | CR4 removes the legacy `registerAllListeners()` / touch wiring **in the same chunk** that the new path takes over (G2-G5). Never both wired at once. R-GATE asserts each long-press/touch behaviour fires through the new path. |
| RR-2 | **Blank UI from premature drive-removal.** Removing a `stateManager.setContentArea` / `uiController.*` drive call before the new owner is attached blanks a visibility axis. | HIGHEST | Strict dependency order (§5): CR3 attaches + proves all visibility owners *before* CR4 removes any drive call. CR3 runs Strict-Mode-Logging (Spec 2 §11.8 5c) to detect double/zero-write per axis. |
| RR-3 | **Unported side-effect (subtle R-risk).** A controller method has a side-effect the spec map missed; deletion strands it. This is exactly what blocked C10-C3. | HIGH | The deletion (CR-DEL) re-runs the C10-C3 mandatory per-class responsibility-trace **after** CR4, against the §3 table — every responsibility must show a *verified-present, IME-attached* new owner. RED trace → no delete. |
| RR-4 | **R-GATE false-GREEN.** The gate passes but a rarely-hit path (OOM-recreate, two-keyboard switch, rotation) still routes to a deleted controller. | HIGH | R-GATE re-uses the Epic C6/D2-pre keystone scenarios (FGS-survival, two-keyboard switch, view-recreate, rotation) on the render path; the parent `DictatePipelineServiceOverlayTransitionTest` binder-harness pattern (R-7 tearDown discipline). |
| RR-5 | **`KeyboardUiController`/`RecordingUiController` are split, not deleted.** §9.4/§9.5/§13 keep QWERTZ + step-rows in these classes (`BLEIBT`); the C10-C3 kill-list + AC-RR-7 greps assume full deletion. | HIGH | §7 Ambiguity A3 — **orchestrator decision required per-chunk**. Options: (a) extract the `BLEIBT` parts into a new small owner (`QwertzRecordingController` / `PipelineStepRowRenderer`) so the old class fully deletes (spec-faithful, more work); (b) keep the class for its `BLEIBT` axis and narrow AC-RR-7 to "no IME *drive* refs" (less work, but a class survives — re-litigates the parallel-dormant rule). Spec 2 §9.4/§9.5 lean toward (a) (it names `EditNumbersAnimator`, `RecordingAnimationController` as extracted helpers — same pattern). Flagged, not chosen here. |
| RR-6 | **`PipelineUiStateReader` consumers dangle.** Step-row rendering is read via `PipelineUiStateReader` + `FakePipelineUiStateReader`; deleting `KeyboardUiController` breaks them. | MED | Tied to RR-5 resolution. If (a): re-point the reader to the extracted renderer. If (b): the reader stays. CR-DEL must trace every `PipelineUiStateReader` consumer. |
| RR-7 | **Test-pollution amplification** (parent F-9 / Epic R-7). New IME-attach Robolectric tests share `DictateDatabase` singleton + default prefs. | MED | Every new boot/attach test copies the `DictatePipelineServiceOverlayTransitionTest` tearDown (DB/pref/JobExecutor reset), per `b5-ime-activation-wiring.md` §8. |
| RR-8 | **D1/D2 (Epic C11/C12) inherit the blocker.** Espresso UI-1..10 + final E2E assume sole-RenderBackend post-C3 (e2e-runbook TC-21). | MED | Epic D1/D2 are re-scoped to run *after* Theme C-R (see §8). Their sole-RenderBackend assumption is restored only by CR-DEL. |

### 6.1 Staged safety net (Epic §6.2 analogue)

- **CR1-CR3 are additive** (add backend features + attach controllers; the
  legacy path still drives). Reverting CR1-CR3 commits restores the
  dormant-but-working parallel state.
- **CR4 removes the legacy drive calls** but **keeps the controller classes
  instantiated** (compile-safe, undriven). A regression in dogfood is fixed
  by re-adding a single drive call (forward-fix; the classes still exist) —
  no big revert. This is the render-layer analogue of the
  `USE_LEGACY_RECORDING_DRIVE` boolean (Epic §6.2) without needing a boolean
  (the drive calls themselves are the switch surface).
- **CR-DEL is the point of no return** — gated on a GREEN R-GATE, separately
  committed for `git revert` isolation.

---

## 7. Open Ambiguities (orchestrator decides per-chunk)

| ID | Ambiguity | Spec 2 says | Proposed spec-faithful resolution (flagged) |
|---|---|---|---|
| A1 | **G2 — RECORD `longClickResolver` body.** Spec 2 §6/§13.2 reference a `longClickResolver` slot field and "konkrete Long-Action" but give no body for the 2-mode (Idle→Settings+picker / Active→autoSwitch+stop) logic. | Slot carries a long-click resolver; RESEND already wired (`ResendLastAudioLong`). | Model `Action.RecordingAction.OnRecordLongPress` (2-mode resolved in the module reducer from `state.recording`), mirroring the existing `actionResolver` `(state, services) -> Action?` pattern. Add a nullable `longClickResolver` to `ButtonSlot`; `ImeViewBackend.wireStaticHandlers` invokes it like the click path. CR1 owns this. |
| A2 | **G6/G7/G15 — missing classes/params.** `ImeViewBackend` has no `applyTheme` method, no `keyPressAnimator` ctor param; no `EditNumbersAnimator` class exists. | Spec 2 §6 ctor *includes* `keyPressAnimator`; §9.2 names `applyTheme` method + `EditNumbersAnimator` helper. | CR1 adds `keyPressAnimator` ctor param + `applyTheme(accentColor)` method to `ImeViewBackend` (spec-faithful — closes the §4 delta). `EditNumbersAnimator`: extract from `MainButtonsController:424-477` as a standalone helper the IME holds (spec-faithful). |
| A3 | **G9/G13 — split, not delete.** §9.4 (`RecordingUiController`) + §9.5/§13-row-20 (`KeyboardUiController`) keep QWERTZ + step-rows (`BLEIBT`). The C10-C3 kill-list + AC-RR-7 assume full deletion of these 2 classes. | "bleibt — QWERTZ-spezifisch" / "stepRows bleibt im KeyboardUiController View-side". | **Orchestrator decision (RR-5).** Recommended: option (a) extract `BLEIBT` parts into small new owners so the kill-list classes fully delete (consistent with the §9.x extract-helper pattern, keeps AC-RR-7 a clean zero-grep, no surviving parallel class). Decide at CR3/CR-DEL boundary. |
| A4 | **Two backends or one for visibility?** Parent B4 made `ContentAreaController`/`PromptVisibilityController`/`OverlayResetHandler` `backendType=null` multi-backends. | Spec 2 §9.3 R.10 "drei Owner-Klassen"; §2.1.15 Option B "optional als zweites RenderBackend". | Follow parent B4 (already implemented): attach all three as `backendType=null` backends via `KeyboardLayoutManager.attachBackend`. No redesign. CR3 owns this. |

---

## 8. Impact on Epic D1/D2 (re-scoping)

The Epic's D1 (C11 Espresso UI-1..10) and D2 (C12 final integration E2E)
**assume the render path is solely RenderBackend post-C3** (e2e-runbook
TC-21 literally states "C3 deleted the legacy controllers"). That
assumption is **invalid until Theme C-R lands**. Re-scoping:

- **D1/D2's sole-RenderBackend assumption is restored ONLY after Theme C-R's
  CR-DEL chunk** (not after the old C10-C3, which is now CR-DEL).
- The Epic block/chunk order becomes: `… C9-C2 → [Theme C-R: CR1..CR4 →
  R-GATE → CR-DEL] → C11-D1 → C12-D2`. C11/C12 depend on `CR-DEL` (was:
  depended on `C10-C3`).
- e2e-runbook TC-21 ("C3 deleted the legacy controllers") is re-pointed to
  CR-DEL; no wording change needed beyond the chunk-id, since CR-DEL *is*
  the (now-correctly-preconditioned) 4-class deletion.
- C11's "test the real path not the dead one" precondition is satisfied by
  CR4 (legacy drive removed) + CR-DEL (classes gone), not by the old C10-C3.

No Epic AC changes: AC-7/AC-8/AC-9/AC-10 still hold — Theme C-R is the
*missing implementation* of the render half of AC-7/AC-10, not a new
acceptance target.

---

## 9. Verification Gate (R-GATE) — contents

Modelled on Epic C6 (D2-pre). D12-atomic (its own chunk, not merged).

- Re-run the parent keystone scenarios on the **render** path: two-keyboard
  switch (Spec 1 §10), FGS-survival, OOM-kill view-recreate, rotation —
  asserting each behaviour group G2-G16 fires through the **new** owner
  (not the legacy controller).
- Strict-Mode-Logging assertion: no visibility axis double-written
  (Spec 2 §11.8 5c / §10 acceptance).
- Long-press/touch parity asserts (AC-RR-1/2): RECORD 2-mode, BACKSPACE
  accel-delete, SPACE swipe, ENTER overlay each produce the legacy effect.
- Robolectric binder-harness (parent `DictatePipelineServiceOverlayTransitionTest`
  pattern, R-7 tearDown discipline).
- **GATE OUTPUT:** GREEN → orchestrator proceeds to CR-DEL. RED →
  mid-chunk-triage; **no controller deletion** until resolved.

---

## 10. References

- Trigger / evidence: `reports/B3-theme-c-legacy-retire.md` §"Chunk C10-C3 — dead-controller retire" (per-class responsibility-trace, IMPL-2)
- SoT: `../2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md` §9.1–§9.6, §11.5–§11.8, §13
- SoT: `../2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md` §9.6, §9.2
- Deferral source: `../2026-05-07 - dictate-keyboard-layout-refactor/reports/B4-keyboard-layout-catalog.md` (F-1/F-2/F-6/F-33)
- Epic: `dictate-cutover-completion.md` §2 (AC-7/AC-10), §6 (risk/rollback), §6.2 (staged safety net)
- Gate pattern: Epic C6 (D2-pre) in `dictate-cutover-completion.chunks.json`

---

## 11. EditBar / Emoji / Overlay-Chars Owner Extraction (Update 2026-05-16)

> [!NOTE]
> D20 append-only section. **Topic:**
> `editbar-emoji-overlaychars-owner-extraction`. **Triggered by:**
> CR4-IMPL-1 (Critical `architecture-conflict`, `B5-CR4-IMPL`) +
> CR4-IMPL-2 (Important needs-verify ride-along). **Block:** B5
> Theme C-R. **Agent-ID:** `B5-CR4-MID-RES-1` (mid-chunk-triage wave
> `B5-CR4-MID-W1`, iter 1).

### 11.1 The conflict (why §3's 16-group map under-enumerated)

`MainButtonsController.registerAllListeners()`
(`MainButtonsController.kt:106-111`) fans out to **four** private
sub-registrations. The §3 16-group map only enumerated the *render*
sub-set; `registerAllListeners()` actually bundles the render listeners
CR-DEL kills **with three sub-axes that have no new-path owner**:

| Sub-registration | §13.2 disposition (SoT, verbatim) | Owner class status |
|---|---|---|
| `registerMainButtonListeners()` (`MainButtonsController.kt:169`) | 9 Main-Button-Area handlers → `actionResolver` slots + `wireStaticHandlers` (§13.2 "alle 9 … wandern in `actionResolver`-Slots") | **CR1/CR2 staged** — `ImeViewBackend.wireStaticHandlers` + `SpecialTouchHandlerInstaller` (dormant). Not part of this conflict. |
| `registerEditBarListeners()` (`MainButtonsController.kt:115`) | **"Edit-Bar (außerhalb der Main-Button-Area) bleibt in einem separaten `EditBarController`, der sich nicht ändert"** (§13.2 Befund) | **`EditBarController` does not exist** — listeners live inside `MainButtonsController` today (the class CR-DEL kills). |
| `registerEmojiListeners()` (`MainButtonsController.kt:278`) | **"Emoji-Listener (Z. 264-280) BLEIBT in EmojiController"** (§13.2) | **`EmojiController` does not exist.** |
| `initializeOverlayCharacters()` (`MainButtonsController.kt:299`) | §13.1 row 13 `overlayCharactersLl` (per-Slot) **"BLEIBT (Theme-internal, separate Animations-/Theme-Klasse)"**; §9.2 `:481-493` `updateOverlayCharacters` **"bleibt — overlay-spezifisch"** | **No extracted owner class exists.** §13 names no concrete class — only "separate Animations-/Theme-Klasse". |

§13.2's "BLEIBT in *EditBarController*/*EmojiController*" presupposes
classes the parent plan never created — the exact INT-1 / F-1 / F-2
parallel-dormant deferral anti-pattern at the edit-bar/emoji layer
(structurally identical to the B4-VAL-F-1/F-2/F-33 render-layer
deferral that birthed Theme C-R itself, §1). The spec assumed an
extracted owner; the listeners are still inside the kill-list class.

### 11.2 Per-sub-registration → new-owner contract (the SoT, §13.2-faithful)

The resolution is the **binding A3 option-a** disposition CR3 already
recorded for G9/G13 (extract the BLEIBT parts into small new owners so
the kill-list class fully deletes and AC-RR-7 stays a clean
zero-grep). It recurs here for the edit-bar/emoji/overlay-chars axes
§3 never enumerated. Three new Kotlin owner classes (sibling to
`ContentAreaController`/`SpecialTouchHandlerInstaller`):

**`EditBarController`** — §13.2 "bleibt in einem separaten
`EditBarController`, der sich nicht ändert". OWNS the exact
`registerEditBarListeners()` listener inventory
(`MainButtonsController.kt:115-165`), ported byte-equivalent:

| Listener (current `MainButtonsController` line) | Wire | §13.2 row |
|---|---|---|
| `editNumbersButton.setOnClickListener` (`:116-119`) | `onVibrate()` + `callback.onSmallModeToggled()` | "`Action.LayoutAction.ToggleSmallMode`" — BLEIBT EditBar |
| `editNumbersButton.setOnLongClickListener` (`:126-130`) | `onVibrate()` + `callback.onSingleRowModeToggled()` + return `true` | "`Action.LayoutAction.ToggleSingleRowMode`" — BLEIBT EditBar |
| `editSettingsButton.setOnClickListener` (`:132`) | `callback.onSettingsClicked()` | "BLEIBT — separate Achse" |
| `editHistoryButton.setOnClickListener` (`:133`) | `callback.onHistoryClicked()` | "BLEIBT — separate Achse" |
| `pipelineCancelBtn.setOnClickListener` (`:134`) | `callback.onPipelineCancelClicked()` | "BLEIBT — separate Achse" |
| `editAudioFocusButton.setOnClickListener` (`:138`) | shared `audioFocusClickListener` (`onVibrate()` + `callback.onAudioFocusToggled()`) | §13.2 "`editAudioFocusButton` … **BLEIBT** — Edit-Bar-Audio-Focus-Btn ist außerhalb der Main-Button-Area"; F-4 |
| `editKeyboardButton.setOnClickListener` (`:140-143`) | `onVibrate()` + `callback.onKeyboardToggleClicked()` | "BLEIBT — `Action.LayoutAction.SetContentArea(QWERTZ)`" |
| `editKeyboardButton.setOnLongClickListener` (`:145-149`) | `onVibrate()` + `callback.onKeyboardLongClicked()` + `true` | "BLEIBT" |
| undo/redo/cut/copy/paste `setOnClickListener` ×5 (`:151-164`) | per-button `onVibrate()` + `callback.onEditAction(actionId)` (android.R.id.undo/redo/cut/copy/paste) | "Edit actions undo/redo/cut/copy/paste — BLEIBT EditBar" |

**`EmojiController`** — §13.2 "Emoji-Listener (Z. 264-280) BLEIBT in
EmojiController". OWNS the `registerEmojiListeners()` inventory
(`MainButtonsController.kt:278-295`):

| Listener (current line) | Wire |
|---|---|
| `editEmojiButton.setOnClickListener` (`:279-282`) | `onVibrate()` + `callback.onEmojiToggleClicked()` |
| `emojiPickerCloseButton.setOnClickListener` (`:284-287`) | `onVibrate()` + `callback.onEmojiCloseClicked()` |
| `emojiPickerView.setOnEmojiPickedListener` (`:289-294`) | `onVibrate()` + `inputConnectionProvider()?.commitText(emoji.emoji, 1)` (null-guarded) |

**`OverlayCharactersController`** (proposed spec-faithful name —
§13/§9.2 only say "separate Animations-/Theme-Klasse", no concrete
name; `OverlayCharactersController` matches the
`ContentAreaController`/`OverlayResetHandler` sibling-naming
convention). OWNS both overlay-chars responsibilities currently split
across `MainButtonsController`:

| Method (current line) | Responsibility |
|---|---|
| `initializeOverlayCharacters()` (`:299-313`) | inflate 8 `item_overlay_characters` TextViews into `overlayCharactersLl` with the rounded-stroke `GradientDrawable` background (one-time init) |
| `updateOverlayCharacters(characters, accentColor)` (`:451-463`) | per-render: show/hide + set text + accent-tint the 8 char views (the §9.2 `:481-493` "bleibt — overlay-spezifisch" axis) |

> Naming rationale: `OverlayCharactersController` (not `…Handler`)
> because it owns both *init* (structural inflate) and *update*
> (theme/content) — a controller-grade responsibility, parallel to
> `ContentAreaController`. `OverlayResetHandler` (G12, already
> attached CR3) is the *defensive-reset belt* and stays distinct —
> different concern (transient reset vs. content/theme ownership),
> §13.1 rows 11 vs. 13 are explicitly two separate rows.

### 11.3 Build-but-dormant staged model (identical to CR2/CR3)

The three new owners follow the **exact CR2
`SpecialTouchHandlerInstaller.installDormant`/`attachToViews` +
CR3 `RenderGate` dormant/`arm()`** staged-safety-net:

- **build-but-dormant in CR-EXTRACT:** the classes exist, are
  constructed + attached at the IME consolidation point
  (`attachImeViewBackendIfReady`, race-safe both `onCreateInputView`
  + `onServiceConnected`), but a `RenderGate`-style ledger marker
  keeps them from calling `setOnClickListener`/`setOnLongClickListener`
  on the live Views. The **legacy `MainButtonsController`
  sub-registrations stay the SOLE LIVE owner** (RR-1: Android keeps
  only the most-recent `setOnXListener` — attaching now would silently
  overwrite the live legacy listener, the F-1/F-2 trap).
- **CR4 flips per-axis atomically:** CR4 removes
  `mainButtonsController.registerAllListeners()` (which now no longer
  fans out to the 3 NO-owner sub-axes — they will have armed owners)
  AND calls `editBarController.attachToViews()` /
  `emojiController.attachToViews()` /
  `overlayCharactersController.arm()` in the *same* chunk, never both
  wired at once (the established `dormant-cr-extract → attached-cr4`
  ledger transition CR2 set for the touch axis / CR3 for the
  visibility axis).
- **single-owner proof:** each new owner reports its intended writes
  to a ledger (`live=false` while dormant) → `doubleWriteCount == 0`
  through CR-EXTRACT (Spec 2 §10 Strict-Mode-Logging acceptance,
  reusing the CR2 `OWNER_TAG`/`Log.wtf` guard pattern + the CR3
  `RenderGate` ledger). Click-listener axes (EditBar/Emoji) reuse the
  CR2 `installDormant`/`attachToViews` + keyed-tag single-owner
  marker; the overlay-chars *write* axis (visibility/text/tint on the
  8 char views) reuses the CR3 `RenderGate` (dormant → ledger-report
  only; `arm()` = CR4 flip).

### 11.4 CR4-IMPL-2 — G8 resend-cooldown write-path verification

**Finding (verified real, not a false positive).** The
`state.resend.resendCooldown` state model is **fully present**:
`ResendState.resendCooldown` (`DictateUiState.kt:434`),
`Action.ResendAction.ResendLastAudio`/`ResendLastAudioLong`
arm the cooldown in `ResendModule.reduce`
(`ResendModule.kt:77-92`), `Action.ResendAction.ResendCooldownExpired`
clears it (`:96-102`), and the RESEND slot's
`enabledResolver = { !state.resend.resendCooldown }` +
`alphaResolver` already render the disabled state
(`LayoutCatalog.kt:88-89/217-218`). **However**, the IME's
`onResendClicked` (`DictateInputMethodService.java:3496-3546`)
cooldown *write-path* is purely **imperative**:
`mainButtonsController.setResendEnabled(false)` at `:3511` and
`mainHandler.postDelayed(() -> setResendEnabled(true), 500)` at
`:3539-3543`. **No `dispatch(ResendCooldownExpired)` scheduling
exists** — `ResendModule`'s own KDoc confirms it
("the Phase-1 placeholder relies on the UI side scheduling that
action via `Handler.postDelayed`"; that scheduling was never wired).
The catalog `actionResolver = { _, _ -> ResendLastAudio }` *arms*
the cooldown when the RESEND slot fires via the new reactive path,
but **nothing clears it** → if CR4 removes the imperative
`setResendEnabled` calls and relies on the state path, the cooldown
latches `true` forever (resend button permanently disabled — a worse
regression than the double-click race).

**Resolution (build-but-dormant, mirrors the owner extraction).**
CR-EXTRACT adds the missing `ResendCooldownExpired` dispatch-schedule
*next to* the existing imperative path (both run; the imperative call
stays the live effect until CR4 removes it). Concretely: when
`onResendClicked` arms the imperative cooldown it ALSO schedules
`mainHandler.postDelayed(() -> pipelineBinder.dispatch(
Action.ResendAction.ResendCooldownExpired.INSTANCE), 500)` (guarded
`pipelineBinder != null`). The arming half (`ResendLastAudio` →
`resendCooldown=true`) is dispatched by the catalog `actionResolver`
on the **reactive path** (dormant until CR4 flips the RESEND click),
so CR-EXTRACT only needs to wire the **clear** half so the round-trip
is complete before CR4 removes the imperative `setResendEnabled`. This
is additive + dormant-safe: the double dispatch is idempotent
(`ResendCooldownExpired` is a no-op when `resendCooldown==false`,
`ResendModule.kt:97`) and the imperative `setResendEnabled` remains
the live UI effect until CR4.

### 11.5 Implementation Hints (concrete, for the repair leg)

1. `EditBarController` / `EmojiController` — Kotlin, package
   `net.devemperor.dictate.state.render` (sibling to
   `SpecialTouchHandlerInstaller`). Constructor takes a typed
   view-holder data class (`EditBarViews` / `EmojiViews`) + the
   shared callbacks (the `MainButtonsController.Callback` surface is
   the parity contract) + `inputConnectionProvider` (emoji). Expose
   `installDormant()` (build + cache the listener lambdas, tag the
   single-owner keyed-marker, no `setOnClickListener` on live Views)
   and `attachToViews()` (CR4 flip — `setOnClickListener` the cached
   lambdas + ledger `attached-cr4`), exactly like
   `SpecialTouchHandlerInstaller`.
2. `OverlayCharactersController` — Kotlin, same package. Constructor
   takes `OverlayCharactersViews(overlayCharactersLl)` +
   `gate: RenderGate?`. `initialize()` does the one-time 8-view
   inflate (idempotent — guard on `childCount`). `update(characters,
   accentColor)` routes every char-view `visibility`/`text`/tint
   write through the `RenderGate` (dormant → ledger-report only;
   `arm()` = CR4). Reuse the CR3 `writeVisibility` gate-routing idiom.
3. IME wiring: extend `attachDormantVisibilityControllers()` (or a
   parallel `attachDormantEditBarEmojiOwners()` at the same
   consolidation point) to construct + attach the 3 owners dormant;
   extend `detachDormant…` symmetrically (view-recreate + onDestroy).
   Add a new keyed-tag id `editbar_emoji_owner_tag` in `ids.xml`
   (follows the `special_touch_owner_tag` convention).
4. Do **not** remove any `MainButtonsController` sub-registration
   here — that is CR4. Do **not** delete `MainButtonsController` —
   that is CR-DEL.
5. CR4-IMPL-2: add the `ResendCooldownExpired` postDelayed-dispatch
   in `onResendClicked`'s cooldown path next to the existing
   `setResendEnabled` calls (additive, idempotent, `pipelineBinder`-
   guarded).

### 11.6 chunks.json / spec amendment summary

- **chunks.json:** new chunk `CR-EXTRACT-RR-editbar-emoji-owners`
  inserted into Block B5 `chunk_ids` immediately **before** CR4
  (sequence: CR1 → CR2 → CR3 → **CR-EXTRACT** → CR4 → CR-RGATE →
  CR-DEL). `spec_references` = Spec 2 §13.2 (+ §13.1 row 13 / §9.2
  `:481-493` for overlay-chars). dep: CR3. note: "resolves
  CR4-IMPL-1; build-but-dormant; CR4 flips per-axis atomically".
  Block note + top-level note updated.
- **This spec:** §3.2 / §7 A3 disposition extended to the
  edit-bar/emoji/overlay-chars axes (this §11).

### 11.7 References

- Block-report: `reports/B5-theme-cr-render-cutover.md`
  §"Mid-Chunk-Triage Wave B5-CR4-MID-W1" + Issue Index (CR4-IMPL-1 /
  CR4-IMPL-2)
- SoT: `../2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md`
  §13.2 (Click-Listener-Audit), §13.1 row 13, §9.2 (`:481-493`),
  §13.5.c Gap 2 (resend-cooldown)
- Plan: `dictate-cutover-completion.chunks.json` (Block B5)
- Pattern precedent: this spec §6 RR-1 / §6.1 (CR2
  `SpecialTouchHandlerInstaller`), §6 RR-2 (CR3 `RenderGate`)

---

## 12. `space-touch-vs-click-double-commit` (F-1)

**Date:** 2026-05-17
**Triggered by:** F-1 (Critical — `validated-findings-B5.md`)
**Block:** B5 (Theme-C-R render-path cutover)
**Agent-ID:** B5-VAL-RES-1

### 12.1 Sources

1. **Live code trace** — `ImeViewBackend.wireStaticHandlers`
   (`state/render/ImeViewBackend.kt:333-409`),
   `SpecialTouchHandlerInstaller.buildSpaceTouchHandler`
   (`state/render/SpecialTouchHandlerInstaller.kt:229-260`),
   `CursorSwipeTouchHandler.onTouch`
   (`keyboard/CursorSwipeTouchHandler.kt:45-78`),
   `KeyboardInputModule` `SpaceKey → Effect.SendSpace → commitText(" ",1)`
   (`state/modules/KeyboardInputModule.kt:80-99`),
   catalog SPACE slot `actionResolver = { _, _ -> SpaceKey }`
   (`state/layout/LayoutCatalog.kt:139,190,304,350,452`).
2. **Legacy baseline** — `git show c92ebd1:.../MainButtonsController.kt`
   lines 155-260: `spaceButton` has **only** `setOnTouchListener`
   (CursorSwipe, `consumeTouchEvents=false`); **no**
   `spaceButton.setOnClickListener`. BACKSPACE/ENTER have *both*
   click+touch but their touch handlers do not commit on tap (Backspace
   swipe / Enter overlay) so the click is the sole commit path there.
3. **Spec 2 SoT §13.2 Click-Listener-Audit**
   (`2-keyboard-layout.reviewed.md:2320-2349`) — the authoritative
   legacy→new mapping. BACKSPACE (`:2330`) and ENTER (`:2338`)
   explicitly map `setOnClickListener → actionResolver`. **SPACE has
   exactly one row** (`:2334`): `spaceButton.setOnTouchListener` (Z. 225)
   → `buildSpaceTouchHandler()` (§11.7). There is **no**
   `spaceButton.setOnClickListener` row — because legacy had none.
4. **Spec 2 §6 reference `wireStaticHandlers`**
   (`2-keyboard-layout.reviewed.md:701-725`) — illustrative
   `buttonViews.forEach { setOnClickListener }` loop **plus** a
   separate `SPACE.setOnTouchListener(buildSpaceTouchHandler())`. This
   reference is a simplified sketch; §13.2 is the prescriptive mapping.

### 12.2 Findings

**Defect reproduction (consensus, all sources agree).** One physical
SPACE tap fires TWO space commits:

1. `CursorSwipeTouchHandler.onTouch` `ACTION_UP` with `hasSwiped==false`
   → `onTap()` → `inputConnectionProvider()?.commitText(" ", 1)`
   (space #1). Returns `consumeTouchEvents == false`.
2. Because the outer `OnTouchListener` returns `false`, Android's
   `View` does not consume the touch → `View.performClick()` fires →
   the `OnClickListener` wired by `wireStaticHandlers` for **every**
   button (incl. SPACE) → `currentSlot(SPACE).actionResolver` →
   `Action.KeyboardInputAction.SpaceKey` → `KeyboardInputModule` →
   `Effect.SendSpace` → `commitText(" ", 1)` (space #2).

The `:402-405` SPACE skip in `wireStaticHandlers` is
`keyPressAnimator`-only — it does **not** skip the click loop. Legacy
`consumeTouchEvents=false` was harmless because legacy SPACE had no
click listener; the CR1 universal click-wiring + the CR4 catalog
`SpaceKey` slot introduced the second path. BACKSPACE/ENTER unaffected
(their §11.7 handlers have no commit-on-tap).

**Spec-faithfulness analysis.** The §13.2 Click-Listener-Audit is the
SoT for "what owns each legacy listener after the cutover". Every
commit-key's `setOnClickListener` is explicitly mapped; SPACE's is
**not present** because SPACE never had one — its commit is the §11.7
`buildSpaceTouchHandler` `onTap`. The catalog `SpaceKey` resolver
(`LayoutCatalog`) is therefore a spec-internal redundancy for the
IME backend: it duplicates a commit the §11.7 touch handler already
owns. The §6 reference loop is a simplified illustration, not a
contradiction of §13.2 (the §6 sketch even shows the SPACE
`setOnTouchListener` separately — the spec author kept the §11.7 owner
and the catalog row both, without reconciling that two commit paths
result; §13.2's per-listener audit resolves it: SPACE is touch-only).

**Option evaluation:**

| Option | Spec-faithful? | Risk | G4 / §11.7 impact |
|---|---|---|---|
| (i) exclude SPACE from the `wireStaticHandlers` click loop | **Yes** — §13.2 maps SPACE solely to `buildSpaceTouchHandler`; legacy-parity (touch-only) | Lowest — one `if` guard, no touch/state-machine change | None — `CursorSwipeTouchHandler` + `buildSpaceTouchHandler` untouched; `consumeTouchEvents=false` preserved → G4 MOVE-propagation intact |
| (ii) make `onTap`/handler consume the tap (`return true` on ACTION_UP) | No — breaks §11.7 verbatim contract | High — `consumeTouchEvents=false` is load-bearing for G4: ACTION_MOVE must propagate so the cursor-swipe keeps moving; returning true on UP needs per-event branching that diverges from the verbatim builder | Breaks G4 invariant + §11.7 verbatim |
| (iii) route SPACE click → no-op | Functionally = (i) but leaves a dead listener | Low | None, but more clutter + a dead resolver path |

### 12.3 Implementation Hints

**Chosen: Option (i) — exclude SPACE from the click-listener loop in
`ImeViewBackend.wireStaticHandlers`.**

- In `wireStaticHandlers` (`ImeViewBackend.kt:334`), inside
  `buttonViews.forEach { (id, view) -> ... }`, guard the
  `view.setOnClickListener { ... }` block so it is **not** wired for
  `id == LogicalButtonId.SPACE`. The long-click listener and the
  key-press-animation skip stay exactly as they are (SPACE already
  skips press-animation; SPACE has no long-press resolver so the
  long-click listener is a harmless vibrate-and-consume — leave it,
  it matches the other no-resolver buttons and legacy SPACE had no
  long-press anyway; removing it is out of scope and would diverge
  from the uniform wiring).
- Keep the `CursorSwipeTouchHandler` and `buildSpaceTouchHandler`
  **byte-identical** (verbatim §11.7 contract — do not touch
  `consumeTouchEvents`, `onTap`, `onCursorMove`). The G4 cursor-swipe
  MOVE-propagation invariant (`consumeTouchEvents=false` so ACTION_MOVE
  events keep arriving) is untouched: a swipe still moves the cursor;
  only the spurious second commit on the TAP path is removed.
- Leave the catalog `SPACE` slot `actionResolver = { _, _ -> SpaceKey }`
  as-is. It stays correct for any future/overlay backend that does NOT
  install the §11.7 touch handler; for the IME backend it is simply
  not reached (no click listener). Add a short KDoc note in
  `wireStaticHandlers` explaining the SPACE click-skip (legacy-parity:
  SPACE is touch-only per §13.2; the §11.7 `buildSpaceTouchHandler`
  `onTap` is the single commit path — wiring a click here double-commits
  because `CursorSwipeTouchHandler` deliberately returns
  `consumeTouchEvents=false` for G4 MOVE-propagation, so `performClick`
  would also fire).
- **Regression test** (`ImeViewBackendTest`): assert SPACE has **no**
  `OnClickListener` after `attach()` (Robolectric `ShadowView`
  `hasOnClickListeners()` is false / `getOnClickListener()` is null for
  SPACE while non-null for e.g. RECORD), and that the other buttons
  still have their click listener. Plus a touch-path test (can live in
  `SpecialTouchHandlerInstallerTest` or `ImeViewBackendTest`) proving a
  synthesized DOWN→UP (no MOVE) on SPACE produces exactly **one**
  `commitText(" ", 1)` and a DOWN→MOVE(>threshold)→UP produces a cursor
  move (`commitText("", ±)`) and **no** space commit — i.e. the swipe
  still works and the tap commits exactly once.

This is a small, one-file production fix (`ImeViewBackend.kt`) + test;
no architecture change → **not escalated**.

---

## 13. `f6-staging-language-override-lifecycle` (F-2 — F-6 RE-OPENED)

**Date:** 2026-05-17
**Triggered by:** F-2 (Critical — `validated-findings-B5.md`); re-opens
B3-VAL **F-6** (prematurely marked closed in CR-DEL)
**Block:** B5 (Theme-C-R render-path cutover)
**Agent-ID:** B5-VAL-RES-1

### 13.1 Sources

1. **`grep -rn SetOverride app/src/main`** — the **only**
   `LanguageAction.SetOverride` dispatch in all of `app/src/main` is
   `setLanguageFromPicker` (`DictateInputMethodService.java:2391`,
   the explicit picker). No entry-seed, no clear.
2. **Live code trace** — `resolveEffectiveLanguage`
   (`:2144-2149`) → `reprocessStagingOverrideOrNull` (`:2181-2192`,
   reads only `pipelineBinder.getState()...getLanguage().getOverride()`);
   staging entry `onResendLongClicked` →
   `pipelineStepRowRenderer.enterReprocessStaging(...lastSession.getLanguage())`
   (`:4141-4145`) with **no** adjacent `SetOverride` dispatch;
   view-recreate restore entry `:1973-1978` (same — no `SetOverride`);
   staging exits: `onTrashClicked` → `cancelReprocessStaging()`
   (`:4199-4202`) and `handleReprocessSend` → `preparePipeline()`
   (`:4284`) — neither dispatches `SetOverride(null)`.
3. **False KDoc** — `resolveEffectiveLanguage` KDoc (`:2129-2143`,
   specifically the `:2133` clause) claims the override is "written by
   `SetOverride` from `setLanguageFromPicker` **and cleared on staging
   exit**" — no code clears it; the invariant is fictional and masks
   the bug.
4. **Chip-refresh wiring** — `servicePipelineCallback`
   `onPipelineUiStateChanged` (`:1008-1029`) calls
   `refreshLanguageChip()` on **every** pipeline-state change incl.
   entering/leaving staging → `resolveEffectiveLanguage()` re-reads the
   override. So seeding the override *before* `enterReprocessStaging`
   makes the chip show the session language with no extra plumbing.
5. **SoT** — Spec 1 §15.2 (RecordingModule / language carrier), Spec 2
   §9.5 (`PipelineStepRowRenderer` ReprocessStaging carrier is the
   View-side BLEIBT *display* state, not the language-read carrier),
   the original B3-VAL F-6 collapse intent (single
   `LanguageState.override` read-carrier), and the legacy
   `KeyboardUiController.enterReprocessStaging` semantics (`git show
   c92ebd1:`) where staging entry seeds the session's language as the
   initial override.

### 13.2 Findings

**F-6 collapse is INCOMPLETE — F-6 is NOT closed.** B3-VAL F-6
collapsed the effective-language *read* onto the single
`LanguageState.override` carrier (good). CR-DEL marked F-6 "closed"
(`B5` Issue Index `:53`, RR-3 trace `:1702`, CR-RGATE `:1565`). But
the collapse removed the *read* side of the legacy
`ReprocessStaging.selectedLanguage` carrier without wiring the
*write/seed* and *clear* side onto the new carrier:

1. **Lost staged language.** Staging entry sets only the View-side
   `PipelineUiState.ReprocessStaging.selectedLanguage` (the §9.5 BLEIBT
   display state). It does **not** dispatch
   `SetOverride(sessionLanguage)`. The only `SetOverride` site is the
   explicit picker. A staging session entered without a manual re-pick
   → `reprocessStagingOverrideOrNull()` reads a null/stale
   `LanguageState.override` → `resolveEffectiveLanguage()` falls
   through to the **permanent** pref language → the language chip + the
   transcription-config snapshot read show the **wrong** language for
   that staging session.
2. **Stale-override leak between sessions.** No `SetOverride(null)`
   clear exists. The `reprocessStagingOrNull()` scope-guard in
   `reprocessStagingOverrideOrNull` stops a stale value leaking
   *outside* staging, but not *between* staging sessions: pick "de" in
   staging A → exit → enter staging B for an "en" session without
   re-picking → `LanguageState.override` still holds "de" → chip shows
   stale "de".
3. **False KDoc** (`:2133`) asserts a "cleared on staging exit"
   invariant that no code implements — actively masks (1)+(2).

**Scope (consensus).** The reprocess *job* itself is unaffected:
`handleReprocessSend` (`:4223`) reads `staging.getSelectedLanguage()`
directly off the View-side carrier, so the transcription uses the
correct language. This is a **display / config-read fidelity** bug
(language chip + the `resolveEffectiveLanguage()`-driven
transcription-config snapshot at `:3197`/`:3846`) + a false-doc —
**not** a wrong-transcription bug. But it is user-visible and
**contradicts** the block-report's "F-6 closed, no regression" claim
→ F-6 must be **re-opened and actually closed by this wave**.

**Spec-faithful design (consensus, the audit's preferred + lowest-risk
+ genuinely single-carrier option).** Seed + clear the *single*
`LanguageState.override` carrier at the staging lifecycle boundaries —
do **not** re-introduce a `selectedLanguage` read-fallback (that
partially re-introduces the dual-carrier F-6 exists to collapse; ruled
out as spec-unfaithful). This matches the legacy
`KeyboardUiController.enterReprocessStaging` semantics (session
language is the initial override) and keeps `LanguageState.override`
the sole read SoT (Spec 1 §15.2 / Spec 2 §9.5 / B3-VAL F-6 intent).

### 13.3 Implementation Hints

Add a small private helper mirroring the existing
`setLanguageFromPicker` dispatch pattern (`:2389-2396`) — guarded
`pipelineBinder != null`, `try/catch` `Log.w` on failure — to keep one
dispatch idiom:

```java
/** F-6 lifecycle: seed/clear the single LanguageState.override carrier
 *  at the ReprocessStaging boundary. `code==null` clears. */
private void dispatchStagingOverride(@Nullable String code) {
    if (pipelineBinder == null) return;
    try {
        pipelineBinder.dispatch(
            new net.devemperor.dictate.state.Action.LanguageAction.SetOverride(code));
    } catch (Throwable t) {
        Log.w("DictateIME", "Staging SetOverride dispatch failed", t);
    }
}
```

(Confirm `LanguageAction.SetOverride` accepts a nullable code and the
reducer treats `null`/blank as "no override" — `reprocessStagingOverrideOrNull`
already blank-guards on the read side, so a `null` or blank clear is
consistent. If `SetOverride` requires a non-null code, use the
reducer's existing clear action; check `state/Action.kt` +
`LanguageModule`/state during repair and pick the carrier's idiomatic
clear. The read-side blank-guard at `:2190` means an empty-string
override already reads as "none", so worst-case `SetOverride("")` is a
safe clear — but prefer the explicit clear action if one exists.)

**Wire it at all staging-entry and staging-exit boundaries:**

- **Entry — seed the session language (BEFORE `enterReprocessStaging`):**
  - `onResendLongClicked` (`:4141`): call
    `dispatchStagingOverride(lastSession.getLanguage())` immediately
    before `pipelineStepRowRenderer.enterReprocessStaging(...)`. (Both
    run on `mainHandler.post` — same thread, ordering deterministic.
    Seeding before the `enterReprocessStaging` state-change means the
    `onPipelineUiStateChanged` → `refreshLanguageChip()` →
    `resolveEffectiveLanguage()` already sees the seeded override → the
    chip shows the session language with no extra refresh call.)
  - View-recreate restore (`:1973`): call
    `dispatchStagingOverride(staging.getSelectedLanguage())` before
    `pipelineStepRowRenderer.enterReprocessStaging(...)`. (Preserves
    the user's possibly-already-overridden language across rotation —
    `selectedLanguage` on the restored staging carries the last value;
    seeding it back into `LanguageState.override` keeps read-fidelity
    after recreate.)
- **Exit — clear (`SetOverride(null)`):**
  - `onTrashClicked` cancel branch (`:4200`): call
    `dispatchStagingOverride(null)` right after / before
    `pipelineStepRowRenderer.cancelReprocessStaging()` (cancel /
    discard).
  - `handleReprocessSend` (`:4284`): call
    `dispatchStagingOverride(null)` right before
    `pipelineStepRowRenderer.preparePipeline()` (staging → Preparing).
    NOTE: `handleReprocessSend` already snapshotted
    `selectedLanguage` (`:4223`) and passed it to `submitReprocess`
    (`:4280`) *before* this point, so clearing the override here does
    **not** affect the in-flight reprocess job's language — it only
    resets the per-staging-session transient so the next staging
    session starts clean (fixes leak #2). Order: clear after the
    `submitReprocess` call, before/with `preparePipeline()`.
- **Picker (`setLanguageFromPicker` `:2386-2402`):** unchanged — it
  already dispatches `SetOverride(code)` in the staging branch. It now
  *overrides* the entry-seeded value (user explicitly re-picks) which
  is exactly the desired behaviour.

**Fix the false KDoc** (`resolveEffectiveLanguage` `:2129-2143`):
correct the `:2133` clause to state the true lifecycle — the override
is seeded with the session language on ReprocessStaging entry
(`onResendLongClicked` / view-recreate restore), overridden by the
explicit picker (`setLanguageFromPicker`), and cleared
(`SetOverride(null)`) on staging exit (`cancelReprocessStaging` /
reprocess-send → Preparing). Also reconcile the `setLanguageFromPicker`
KDoc (`:2354-2377`) and the `reprocessStagingOverrideOrNull` KDoc
(`:2169-2180`) so all three describe the now-real lifecycle (SSoT — no
contradictory doc surface left).

**Regression test** (new test in
`DictateInputMethodServiceTest` or the closest staging-language test
harness; if IME-service unit-testing is infeasible per K-4, test at
the seam — assert the dispatched `SetOverride` actions on a fake
binder):
- Staging-entry with session language "de" (no manual pick) →
  `resolveEffectiveLanguage()` / chip resolves to "de" (not the
  permanent pref).
- Staging-exit (cancel) → `LanguageState.override` cleared →
  `resolveEffectiveLanguage()` returns the permanent pref.
- Cross-session leak guard: enter staging A (de) → exit → enter
  staging B (en) without re-pick → resolves "en" (not stale "de").
- Re-open then close **F-6** in the block-report Issue Index.

This is a medium fix (one helper + 4 call-sites + 3 KDoc corrections)
in `DictateInputMethodService.java` (stays Java) — no architecture
change → **not escalated**.

### 13.4 References

- Block-report: `reports/B5-theme-cr-render-cutover.md`
  §"Block-Validate Repair Wave 1 (B5-VAL-REPAIR-1)" + Issue Index
  (F-6 re-open→close)
- Validated findings: `reports/validated-findings-B5.md` F-1, F-2
- SoT: `../2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md`
  §15.2; `.../2-keyboard-layout/2-keyboard-layout.reviewed.md`
  §9.5, §13.2 (`:2334`)
- Legacy baseline: `git show c92ebd1:.../MainButtonsController.kt`
  (SPACE touch-only), `KeyboardUiController.enterReprocessStaging`
  (session-language initial override)
