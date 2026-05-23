# Audit Report: logic (Block 4, scope: full-block)

**Agent-ID:** B4-AUDIT-LOGIC
**Date:** 2026-05-15
**Knowledge skills used:** knowledge-typescript (sealed-class exhaustiveness, applies to Kotlin)
**Files inspected:** 16
- `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/Predicates.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/IconResolvers.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/TextResolvers.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/ButtonSlot.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/LayoutMode.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/KeyboardLayoutManager.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/RenderBackend.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/ImeViewBackend.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/RecordingAnimationController.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/ContentAreaController.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/PromptVisibilityController.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/OverlayResetHandler.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/SlotRenderer.kt`
- `app/src/main/java/net/devemperor/dictate/state/render/MotionSurface.kt`
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt`
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`
- `app/src/main/java/net/devemperor/dictate/core/MainButtonsController.kt`
- `app/src/main/java/net/devemperor/dictate/core/KeyboardStateManager.kt`
- `app/src/main/res/xml/motion_scene_keyboard.xml`
- `app/src/main/res/layout/activity_dictate_keyboard_view.xml`

## Summary

- Critical: 4
- Important: 6
- Nice-to-have: 5

## Findings

### AUDIT-LOGIC-B4-1

- **Severity:** Critical
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/ImeViewBackend.kt:256`
- **Description:** `BACKSPACE` long-press handler is wired as `{ true }` — a bare consume — which **breaks the accelerated-delete loop**. The legacy `MainButtonsController.registerMainButtonListeners` (line 185-188) wires `setOnLongClickListener { callback.onBackspaceLongClicked(); true }`, which kicks off the timed accelerated-delete `deleteHandler.postDelayed` loop in `DictateInputMethodService.onBackspaceLongClicked` (line 2830-2855). Because `ImeViewBackend.wireStaticHandlers` runs **after** `MainButtonsController.registerAllListeners` (IME line 733 vs line 949), the ImeViewBackend's `setOnLongClickListener` **overwrites** the legacy handler. The result is that long-pressing backspace produces no behavior at all (no single delete, no accelerated delete) — the touch handler still fires for swipe-to-cursor, but the press-and-hold for fast deletion is dead. This is a real user-visible regression from C15 wiring.
- **Why it matters:** Backspace accelerated delete is a baseline keyboard feature; users will encounter "I held backspace and nothing happened" the moment the new path is live.
- **Suggested fix scope:** small
- **Suggested fix:** Either (a) remove the BACKSPACE long-press wiring from `ImeViewBackend.wireStaticHandlers` entirely so the legacy handler survives, or (b) introduce an `Action.KeyboardInputAction.BackspaceLong` (started-/cancelled-pair) and wire the existing repeated-delete loop into a new effect. Option (a) is the least-risk fix until D-13 / B5+ can model the repeat-cycle as actions.

### AUDIT-LOGIC-B4-2

- **Severity:** Critical
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/ImeViewBackend.kt:248-250`
- **Description:** `RECORD` long-press handler is wired as `{ onVibrate(); true }` (vibrate-only), **losing the legacy `onRecordLongClicked()` cascade**. The legacy handler (`MainButtonsController` line 163-167, IME line 2643-2654) does two things:
  - In `RecordingState.Idle`: opens the `DictateSettingsActivity` with the file-picker extra (audio import flow).
  - During an active/paused recording: sets `autoSwitchKeyboard = true` and calls `stopRecording()` (auto-switch-keyboard flow).
  Both behaviors disappear once the ImeViewBackend's `setOnLongClickListener` overrides the legacy listener. Spec 2 §6 calls the new behavior "vibrate-only marker" — but that contradicts the existing two-way IME contract. The C14 deviation table claims "the legacy IME doesn't emit a record-long-press action either" — but the legacy IME's long-press triggers IME-side methods directly (not actions), and those methods are now unreachable.
- **Why it matters:** The audio-file import flow (`open_file_picker` Intent extra) and the auto-switch-keyboard after recording are documented user features. They silently vanish at the moment the new path attaches.
- **Suggested fix scope:** small (preserve legacy via Action) / medium (model both branches as actions)
- **Suggested fix:** Option (a): remove `setOnLongClickListener` for RECORD from `ImeViewBackend.wireStaticHandlers` so the legacy handler survives (mirrors the BACKSPACE fix). Option (b): emit a new `Action.RecordingAction.OnRecordLongClicked` and add a Module reducer that opens the settings intent / triggers stop-recording. Option (a) is the safer interim fix.

### AUDIT-LOGIC-B4-3

- **Severity:** Critical
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/IconResolvers.kt:52-54` (vs `app/src/main/java/net/devemperor/dictate/core/MainButtonsController.kt:370-374`)
- **Description:** `resolveAudioFocusIcon` **inverts** the legacy icon-meaning. New path:
  - `enabled=true` → `R.drawable.ic_baseline_volume_up_24`
  - `enabled=false` → `R.drawable.ic_baseline_volume_off_24`
  Legacy `MainButtonsController.refreshAudioFocusIcon`:
  - `enabled=true` → `R.drawable.ic_baseline_volume_OFF_24`
  - `enabled=false` → `R.drawable.ic_baseline_volume_UP_24`
  The legacy semantic of the icon is "what state OTHER audio is in after AudioFocus is requested" (focus enabled = silence other audio = volume_off shown). The new path reads "icon shows what audio-focus pref is" — opposite meaning. Today both paths write to different View slots (legacy → `view.foreground`, new → `view.icon`), so they don't physically collide; the legacy foreground is what users see. But the moment the new path becomes authoritative (post-cleanup / D-13), the icon meaning flips and users see the inverted icon.
- **Why it matters:** Visual contract drift on a user-visible toggle — pre-Phase users will read "volume_up = focus active" as the OPPOSITE of what the legacy meant. Easy to miss because both paths still run in parallel today.
- **Suggested fix scope:** small
- **Suggested fix:** Invert the truth-table in `resolveAudioFocusIcon`:
  ```kotlin
  fun resolveAudioFocusIcon(enabled: Boolean): Int =
      if (enabled) R.drawable.ic_baseline_volume_off_24
      else R.drawable.ic_baseline_volume_up_24
  ```
  Then update `IconResolversTest`/`SlotRendererTest` to assert the corrected pairing. The legacy code's icon semantics are the user-facing truth.

### AUDIT-LOGIC-B4-4

- **Severity:** Critical
- **File:** `app/src/main/res/xml/motion_scene_keyboard.xml:276-285` (vs `single_row_state` audio_focus_btn line 260-271)
- **Description:** `widget_toggle_btn` in `single_row_state` is parked at `layout_constraintEnd_toEndOf="parent"` (line 282). But `audio_focus_btn` in the same state is also at `layout_constraintEnd_toEndOf="parent"` (line 269). Both buttons share the same end-anchor with no chain-position to separate them, so **they overlap visually at the parent end** when both are visible. In `KEYBOARD_SINGLE_ROW` mode (Catalog § lines 146-217) `WIDGET_TOGGLE.visibilityPredicate = ::isWidgetToggleVisible` (`viewMode == KEYBOARD` → typically true) AND `AUDIO_FOCUS.visibilityPredicate = { true }`. With both visible and overlapping, the user sees stacked icons. The C13 deviation comment ("widget_toggle_btn parked at parent-end (out of chain) in Single-Row") concedes the placement but doesn't acknowledge the overlap with audio_focus.
- **Why it matters:** Visual bug at the layout level — users in single-row mode see two icons stacked. Catches every render-tick once the new path drives layout.
- **Suggested fix scope:** small
- **Suggested fix:** In `single_row_state`, chain `widget_toggle_btn` BEFORE `audio_focus_btn`: e.g. `widget_toggle_btn.constraintEnd_toStartOf="@+id/audio_focus_btn"` + adjust `audio_focus_btn.constraintStart_toEndOf="@+id/widget_toggle_btn"`. OR: make `widget_toggle_btn`'s catalog visibility `{ false }` in `KEYBOARD_SINGLE_ROW` to match the "parked off-chain" intent the deviation describes (and the spec gap re Spec 2 §3.1 stating widget_toggle is "neben AUDIO_FOCUS im action_row", which is a two-row concept).

### AUDIT-LOGIC-B4-5

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/ContentAreaController.kt` + `PromptVisibilityController.kt` + `OverlayResetHandler.kt`
- **Description:** All three null-`backendType` controllers exist as production code with 20 unit tests (5+11+4) — but **NONE of them are wired in production**. `DictateInputMethodService.attachImeViewBackendIfReady` (line 967-1045) only attaches `ImeViewBackend`. `DictatePipelineService.onCreate` builds the manager but the IME never constructs / attaches the three null-backend controllers. The C15 deviation table acknowledges "Two render paths run in parallel" but only for the `ImeViewBackend`-controlled buttons. The container visibility / prompt visibility / overlay reset is **still entirely owned by `KeyboardStateManager.applyVisibility` / `applyContentAreaVisibility` / `applyPromptsVisibility`** — i.e., the legacy path. The three new controllers are dead code today.
- **Why it matters:** Spec 2 §4.1 / R.10 (Issue 2.1.15 Option B) requires the multi-backend list to fan render-ticks out to all surface controllers. The contract is implemented but unused. From a maintainability standpoint this is misleading: a reader of `KeyboardLayoutManager.renderTo` thinks ContentAreaController is firing, but no one is plugging it in. Risk: future block authors assume parity, write code that relies on the new path, and discover the legacy SoT silently overrides it.
- **Suggested fix scope:** medium
- **Suggested fix:** Either (a) wire the three controllers in `attachImeViewBackendIfReady` to live up to the Spec 2 §4.1 multi-backend contract (recommended), or (b) explicitly document them as "B5/B6 wiring pending" in their class KDoc + a state-file follow-up issue (currently no such issue exists). Option (a) needs careful gating because dual-write to the same View slots is harmful — see also B4-AUDIT-LOGIC-B4-3 (icon collision).

### AUDIT-LOGIC-B4-6

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:382-391` (KEYBOARD_REPROCESS_STAGING RECORD slot)
- **Description:** The RECORD slot in REPROCESS_STAGING has `enabledResolver = { state -> state.pipeline is PipelineUiState.ReprocessStaging }`. By construction, the catalog only renders this mode when the pipeline IS `ReprocessStaging` (per `forKeyboard()`), so the resolver returns `true` whenever the mode is active. The intent (per the inline comment) is to be DISABLED while `s.isStarting` is true — a field that doesn't exist on `ReprocessStaging` yet. This means RECORD is **always-enabled** during the entire staging flow, including the brief moment immediately after the user clicks "Send" (before the pipeline transitions out of staging). A double-click within ~16 ms would dispatch `SendStaging` twice. The C12 IMPL-3 issue acknowledges this as a known carry-over but it should be documented as a real race risk at the data-layer.
- **Why it matters:** Double-dispatch of `SendStaging(sessionId)` is a potential pipeline-state corruption surface (two `Preparing` cascade entries with the same sessionId).
- **Suggested fix scope:** small (defensive)
- **Suggested fix:** Either add `isStarting: Boolean = false` to `PipelineUiState.ReprocessStaging` and gate the resolver on it (preferred — closes the race), or wrap the `resolveSendStagingAction` to set a one-shot cooldown bit in `ResendModule`-style state.

### AUDIT-LOGIC-B4-7

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/ImeViewBackend.kt:159-166`
- **Description:** `firstRender = false` is set OUTSIDE the `mode.sceneStateId?.let { ... }` block (line 166). If a backend's first render-tick happens with a mode that has `sceneStateId = null` (e.g. a synthetic test mode or a future overlay-via-IME hybrid), the firstRender flag gets cleared without any `jumpToState` call. The subsequent IME_VIEW render would then call `transitionToState` (animated 250 ms) when it should have jumped. Today this is unreachable in production because the IME_VIEW backend filter rejects modes with `BackendType.OVERLAY_WINDOW` AND all five IME_VIEW modes have non-null `sceneStateId`. But the invariant ("first render with sceneStateId snaps") is fragile.
- **Why it matters:** Future-proofing: a future test or new keyboard mode with `sceneStateId = null` would silently lose the no-animation first-render contract.
- **Suggested fix scope:** small
- **Suggested fix:** Move `firstRender = false` INSIDE the `?.let { ... }` block so it only flips after an actual jump/transition fired. Alternatively, document the invariant ("backend never sees a null-sceneStateId mode") in the class KDoc.

### AUDIT-LOGIC-B4-8

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:974-978`
- **Description:** Comment + null check claim to handle the case "main_buttons_cl is not a MotionLayout". But Java `findViewById` is generic — if the view at that id is the wrong type, the assignment throws `ClassCastException`, NOT returns `null`. The null branch only fires when the view doesn't exist at all (which the test scaffolding could produce). The misleading comment + log message could lead a future reader to assume defensive type-checking is in place when it isn't.
- **Why it matters:** Minor — current code works because the layout XML always declares `main_buttons_cl` as `MotionLayout`. But the defensive intent fails its own contract.
- **Suggested fix scope:** small
- **Suggested fix:** Replace the cast + null-check with an explicit `instanceof` check:
  ```java
  View v = dictateKeyboardView.findViewById(R.id.main_buttons_cl);
  if (!(v instanceof MotionLayout)) {
      Log.w("DictateIME", "main_buttons_cl is not a MotionLayout — ImeViewBackend not attached");
      return;
  }
  MotionLayout motionLayout = (MotionLayout) v;
  ```

### AUDIT-LOGIC-B4-9

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/SlotRenderer.kt:64-67`
- **Description:** `applySlotToView` writes `view.icon = ContextCompat.getDrawable(ctx, iconRes)` on EVERY render-tick (even when the slot is `View.GONE`). This is a per-tick `Drawable` allocation through `ContextCompat.getDrawable` for every MaterialButton in every mode. For 5 KEYBOARD modes × ~9 slots × every state emission, this is ~45 unnecessary allocations per state emit even though most icons don't change. The render path is meant to be cheap; the legacy code only re-set icons on toggle.
- **Why it matters:** Performance — StateFlow emits frequently (every recording amplitude tick, every pref change), and per-tick allocations inflate the StateFlow → render cost. Goes against Spec 2 §11.5's per-tick-allocation comment ("per-tick state allocations would inflate StateFlow").
- **Suggested fix scope:** small
- **Suggested fix:** Cache the last-applied `iconRes` per logical button id in `ImeViewBackend` and only call `ContextCompat.getDrawable` when the resource id actually changed. Same for text (already cheap because String objects are interned).

### AUDIT-LOGIC-B4-10

- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/Predicates.kt:101-102`
- **Description:** `isWidgetToggleVisible(state) = state.viewMode == ViewMode.KEYBOARD`. But the catalog applies this predicate ONLY when `state.viewMode == ViewMode.KEYBOARD` already (the manager's `computeLayoutMode` routes WIDGET/HOVER to `OVERLAY_5BUTTON`, not the keyboard modes). So the predicate is **structurally redundant** — it returns true 100% of the time it's evaluated in production. The intent (per the KDoc) is to gate the toggle, but the gating happens at the mode-selection layer, not here. The dead truth-table branch may mislead future readers into thinking widget_toggle is conditionally visible within KEYBOARD mode (it isn't).
- **Why it matters:** Code-comprehension bug. Today's behavior is "always visible in KEYBOARD mode" which contradicts the C13 deviation that puts widget_toggle visibility in single-row at the same parent-end as audio_focus (see B4-AUDIT-LOGIC-B4-4 for the overlap consequence).
- **Suggested fix scope:** small
- **Suggested fix:** Either (a) make widget_toggle conditional on `viewMode==KEYBOARD AND singleRowMode==false` (closes B4-AUDIT-LOGIC-B4-4 too), or (b) simplify the predicate to `{ true }` in the KEYBOARD-mode slots (since the predicate's only contribution is structural overhead).

### AUDIT-LOGIC-B4-11

- **Severity:** Nice-to-have
- **File:** `app/src/main/res/xml/motion_scene_keyboard.xml:328-352`
- **Description:** Missing transition edges for state pairs the runtime can legitimately enter:
  - `two_row_send_mode_state ↔ single_row_send_mode_state` — user toggles single-row during a running pipeline (Spec 2 §1.1 #2 / `onSingleRowModeToggled` always triggers regardless of pipeline state).
  - `two_row_send_mode_state ↔ reprocess_staging_state` and `single_row_send_mode_state ↔ reprocess_staging_state` — Spec 1 §15.2 cascade from `Running → ReprocessStaging` on certain pipeline failures (without an intermediate Idle).
  The C13 deviation note mentions falling back to MotionLayout auto-transition (default fade), but auto-transitions don't pass through `visibilityMode="ignore"` consistently — the fallback may double-write visibility during the transition window.
- **Why it matters:** Edge cases that exist in the pipeline-state space could produce missed-edge bugs. Audit cannot prove they're harmless without running the cases on a device.
- **Suggested fix scope:** small
- **Suggested fix:** Add the four missing transitions with `motion:duration="200"`. They derive their constraints from the same parents so the chain logic is identical.

### AUDIT-LOGIC-B4-12

- **Severity:** Nice-to-have
- **File:** `app/src/main/res/xml/motion_scene_keyboard.xml:38-44` (`record_pulse_layout` in two_row_state)
- **Description:** `record_pulse_layout` (the PulseLayout wrapper around record_btn) does NOT carry `<PropertySet motion:visibilityMode="ignore"/>` in any ConstraintSet. The C13 overlooked-points table calls this out: if a future C14/C15 change drives the wrapper's visibility (e.g., to hide the whole record-button column during EMOJI_PICKER), MotionLayout will fight the catalog's visibility writes during transitions. Currently the wrapper's `android:visibility` defaults to VISIBLE and nobody toggles it, so the gap is dormant. But it's an Open Configuration Principle violation — the invariant assumed by Spec 2 §7.3 / R.11 ("every state-driven button carries visibilityMode=ignore") doesn't hold for the wrapper.
- **Why it matters:** Latent bug — a future visibility consumer would have to learn this invariant the hard way.
- **Suggested fix scope:** small
- **Suggested fix:** Add `<Constraint android:id="@+id/record_pulse_layout"><PropertySet motion:visibilityMode="ignore"/></Constraint>` to `two_row_state` (and rely on derive-from for the SEND_MODE / staging variants).

### AUDIT-LOGIC-B4-13

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/render/ImeViewBackend.kt:255` + line 257
- **Description:** Inconsistent vibrate semantics on long-press wiring:
  - `RECORD` long-press: `{ onVibrate(); true }`
  - `RESEND` long-press: `{ onVibrate(); onAction?.invoke(Action.ResendAction.ResendLastAudioLong); true }`
  - `BACKSPACE` long-press: `{ true }` ← no vibrate, no action
  The BACKSPACE branch dropping `onVibrate()` is inconsistent with the RECORD/RESEND pattern (both vibrate on long-press). If the team decides to revert AUDIT-LOGIC-B4-1 differently (keep the long-press handler but emit an action), the vibrate omission should still be corrected for haptic consistency.
- **Why it matters:** UX consistency — long-press should feel the same across keys.
- **Suggested fix scope:** small
- **Suggested fix:** Add `onVibrate()` to the BACKSPACE long-press lambda, or remove vibrate from RECORD's lambda to match BACKSPACE's terse form. Best: keep consistent with RECORD/RESEND (vibrate).

### AUDIT-LOGIC-B4-14

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:163`
- **Description:** Slot in `KEYBOARD_SINGLE_ROW` for `LogicalButtonId.RECORD` uses a fully-qualified type reference: `state.recording !is net.devemperor.dictate.state.RecordingState.Preparing`. The rest of the file (e.g. line 76) uses the imported short form `RecordingState.Preparing`. Inconsistency at line 163 — likely a residual from C12 inline-fix.
- **Why it matters:** Minor — readability only.
- **Suggested fix scope:** small (mechanical)
- **Suggested fix:** Use the short `RecordingState.Preparing` form (the import on line 7 already covers it).

### AUDIT-LOGIC-B4-15

- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt:498-507` (`forKeyboard` decision tree)
- **Description:** The `forKeyboard` `when { }` block uses sentinel local booleans `isStaging`, `isPipelineLive` and structures the cases as `isStaging -> ...`, `isPipelineLive && singleRowMode -> ...`, etc. The catch-all `else -> KEYBOARD_TWO_ROW` is technically unreachable because the four prior branches cover `(isStaging vs !isStaging) × (isPipelineLive vs !isPipelineLive) × (singleRowMode vs !singleRowMode)` exhaustively — the `else` fires only for `!isStaging && !isPipelineLive && !singleRowMode`, which the fourth branch already covers as `!isPipelineLive && !singleRowMode`. Defensive-but-misleading. A reader expects `else` to mean "unexpected state shape". Cleaner: collapse to an explicit `else if`-chain or use `state.pipeline` + `state.layout.singleRowMode` directly in the `when`-subject for exhaustive sealed-class deconstruction.
- **Why it matters:** Code clarity — the unreachable `else` invites future "what's the else case?" confusion.
- **Suggested fix scope:** small
- **Suggested fix:** Replace `else -> KEYBOARD_TWO_ROW` with `!isPipelineLive && !state.layout.singleRowMode -> KEYBOARD_TWO_ROW`. Either include a defensive `else -> error("forKeyboard: impossible state shape: $state")` or let the compiler enforce exhaustivity by switching to a fully sealed-class deconstruction.

## Coverage

- Files audited:
  - All B4-introduced files under `state/layout/` and `state/render/`
  - C13 MotionScene XML (`res/xml/motion_scene_keyboard.xml`)
  - C13 layout XML (`res/layout/activity_dictate_keyboard_view.xml`)
  - C15 modified files (`DictatePipelineService.kt`, `DictateInputMethodService.java`, `KeyboardStateManager.kt`)
  - Legacy collaborators referenced by C15 deviations (`MainButtonsController.kt`)
- Files skipped: B4-introduced test files (covered by AUDIT-TEST topic per Iter-10 routing).
- Knowledge-skill checkpoints applied:
  - `knowledge-typescript` sealed-class exhaustiveness (applies to Kotlin) — used to evaluate `forKeyboard` (B4-AUDIT-LOGIC-B4-15) and the RecordingState/PipelineUiState `when` branches in resolvers.
  - Generic logic checks: null/empty inputs, off-by-one, race conditions, error-path coverage.

## Out-of-scope observations

- **Convention drift (defer to AUDIT-CONVENTION):** the `applySlotToView` `@Suppress("UNUSED_VARIABLE") val _unused = mode` idiom in `ContentAreaController.render` / `PromptVisibilityController.render` / `OverlayResetHandler.render` is a hand-rolled pattern; project convention typically discards parameters with `_` in the lambda. Triple repetition warrants a single comment in `RenderBackend.render` KDoc that says "implementations may ignore `mode`".
- **Plan-and-API drift (defer to AUDIT-PLAN-AND-API):** Spec 2 §11.8 5d says "DELETE MainButtonsController click-logic" — the C15 deviation table notes this is preserved. The dual-render-path (legacy click listeners replaced silently by ImeViewBackend's) is currently described as "both read same SoT, result is consistent" — but B4-AUDIT-LOGIC-B4-1, -B4-2, -B4-3 show this claim is too strong for long-press and audio_focus icon. AUDIT-PLAN-AND-API should re-evaluate the deviation rationale.
- **Test-quality (defer to AUDIT-TEST):** `RecordingAnimationControllerTest` does not exercise the case where `onState` receives `Preparing → Active → Preparing → Active` quickly — the class-comparison cache should be revalidated when Preparing → Active fires twice. Worth a regression test if the no-op-on-Preparing behavior changes.
