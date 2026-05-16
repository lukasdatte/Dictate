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
