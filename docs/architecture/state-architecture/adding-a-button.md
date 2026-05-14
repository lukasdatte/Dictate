---
date: 2026-05-14
author: Lukas + Claude Code
type: Architecture
status: Accepted
context: Walkthrough — how to add a new button (INSERT_COMMA example) — 7 steps.
related-plan: ../../plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md
related-adrs: ADR-0001, ADR-0004
---

# Walkthrough — Adding a new button

A worked example for adding the `INSERT_COMMA` button to the
KEYBOARD_TWO_ROW layout. The button inserts a comma into the
InputConnection on click.

Total cost: ~20 LoC across 4–5 files. No new module, no DB
migration, no MotionScene XML change.

Owner ADRs: ADR-0001 (action routing) + ADR-0004 (slot model).
Source-of-truth in the plan: §4.0.6.1.

## 1. Vision and Motivation

### 1.1 When this walkthrough applies

Use this walkthrough when:

- You want a new button in an **existing** keyboard mode (e.g.
  add a comma button to KEYBOARD_TWO_ROW).
- The button's behavior is a single action (click → effect).
- No new state axis is needed (the button doesn't change ongoing
  state, just emits a one-shot effect).

Use [`adding-a-module.md`](adding-a-module.md) instead when:

- The button needs a new state axis (e.g. a toggle that persists).
- The behavior involves multiple action variants or cross-module
  reactions.

Use [`adding-a-sub-keyboard.md`](adding-a-sub-keyboard.md) when:

- You're adding a new content-area (numeric pad, accent picker).
- You're adding a new render surface entirely (a second
  overlay-style window).

## 2. Properties this Walkthrough Guarantees

1. **No central code touched.** Only the catalog + the owner
   module + the XML + the view-map are edited. The
   `DictateOrchestrator`, `DictateModuleRegistry`, and
   `KeyboardLayoutManager` are untouched.
2. **Compile-time safety.** Adding a new Action variant forces
   exhaustivity in the owner module's reducer. Forgetting to
   handle it is a compile error.
3. **Test-first available.** The Reducer is pure → JVM-testable
   without Android Context (Step 7).
4. **Slot-deletion is just as easy.** If the button later goes
   away, removing the slot + the action variant is the inverse
   of the walkthrough.

## 3. The 7 steps

### Step 1 — Add `LogicalButtonId` entry

```kotlin
// app/src/main/java/net/devemperor/dictate/keyboard/LogicalButtonId.kt
enum class LogicalButtonId {
    RECORD, RESEND, BACKSPACE, AUDIO_FOCUS, WIDGET_TOGGLE,
    TRASH, SPACE, PAUSE, ENTER,
    OVERLAY_RECORD, OVERLAY_SEND, OVERLAY_PAUSE, OVERLAY_TRASH, OVERLAY_CLOSE,
    INSERT_COMMA,   // ← NEW
}
```

### Step 2 — Add the XML view ID

```xml
<!-- app/src/main/res/layout/activity_dictate_keyboard_view.xml -->
<com.google.android.material.button.MaterialButton
    android:id="@+id/insert_comma_btn"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    motion:visibilityMode="ignore"
    style="@style/Widget.Dictate.KeyButton"
    android:text="," />
```

> [!IMPORTANT]
> `motion:visibilityMode="ignore"` is mandatory on every state-driven
> button (ADR-0004 §"Required mechanics" item 6). Without it,
> MotionScene transitions would fight the per-slot
> `visibilityPredicate`.

### Step 3 — Add the view-map entry

```kotlin
// app/src/main/java/net/devemperor/dictate/keyboard/render/ImeViewBackend.kt
private val buttonViews: Map<LogicalButtonId, View> = mapOf(
    LogicalButtonId.RECORD        to rootView.findViewById(R.id.record_btn),
    // … existing entries
    LogicalButtonId.INSERT_COMMA  to rootView.findViewById(R.id.insert_comma_btn),   // ← NEW
)
```

A missing entry would cause a hard `error(...)` at render time
(Spec 2 §6 "silent-skip protection"). The catalog wouldn't be the
problem; the missing view-map entry would.

### Step 4 — Add the Action variant

The button needs an action. Since "insert comma" is an IME-input
operation, the right module is `KeyboardInputModule` (Spec 1 §15.6).

```kotlin
// app/src/main/java/net/devemperor/dictate/state/Action.kt
sealed class Action {
    sealed class KeyboardInputAction : Action() {
        data object Backspace : KeyboardInputAction()
        data object Enter : KeyboardInputAction()
        data object Space : KeyboardInputAction()
        data class CopyToClipboard(val text: String) : KeyboardInputAction()
        data object InsertComma : KeyboardInputAction()   // ← NEW
    }
    // …
}
```

### Step 5 — Add the reducer arm + effect variant

```kotlin
// app/src/main/java/net/devemperor/dictate/state/modules/KeyboardInputModule.kt
object KeyboardInputModule : DictateModule<Unit, Action.KeyboardInputAction, KeyboardInputModule.Effect> {
    // …

    sealed interface Effect : SideEffect {
        object SendBackspace : Effect
        object SendEnter : Effect
        object SendSpace : Effect
        data class CopyToClipboard(val text: String) : Effect
        data class SendText(val text: String) : Effect    // ← NEW (generic — also handy for accent picker etc.)
    }

    override fun reduce(state: Unit, action: Action.KeyboardInputAction, ctx: ReducerContext) =
        when (action) {
            Action.KeyboardInputAction.Backspace ->
                TransitionResult(Unit, listOf(Effect.SendBackspace))
            Action.KeyboardInputAction.Enter ->
                TransitionResult(Unit, listOf(Effect.SendEnter))
            Action.KeyboardInputAction.Space ->
                TransitionResult(Unit, listOf(Effect.SendSpace))
            is Action.KeyboardInputAction.CopyToClipboard ->
                TransitionResult(Unit, listOf(Effect.CopyToClipboard(action.text)))
            Action.KeyboardInputAction.InsertComma ->                          // ← NEW
                TransitionResult(Unit, listOf(Effect.SendText(",")))
        }

    override fun runEffect(effect: Effect, services: ModuleServices) = when (effect) {
        Effect.SendBackspace -> services.inputConnectionProvider()?.deleteSurroundingText(1, 0); Unit
        Effect.SendEnter     -> services.inputConnectionProvider()?.commitText("\n", 1); Unit
        Effect.SendSpace     -> services.inputConnectionProvider()?.commitText(" ", 1); Unit
        is Effect.CopyToClipboard ->
            services.clipboard?.setPrimaryClip(ClipData.newPlainText("dictate", effect.text)); Unit
        is Effect.SendText ->                                                  // ← NEW
            services.inputConnectionProvider()?.commitText(effect.text, 1); Unit
    }
}
```

> [!IMPORTANT]
> Both `reduce` and `runEffect` use exhaustive `when` over sealed
> types. The compiler forces handling of the new `InsertComma` and
> `SendText` cases. Forgetting either is a compile error.

### Step 6 — Add the `ButtonSlot` to the catalog

```kotlin
// app/src/main/java/net/devemperor/dictate/keyboard/LayoutCatalog.kt
val KEYBOARD_TWO_ROW = LayoutMode(
    id = LayoutModeId.KEYBOARD_TWO_ROW,
    backend = BackendType.IME_VIEW,
    sceneStateId = R.id.two_row_state,
    rows = listOf(
        RowDescriptor(slots = listOf(
            // … existing slots
            ButtonSlot(                                                       // ← NEW
                logicalId = LogicalButtonId.INSERT_COMMA,
                widthPolicy = WidthPolicy.WrapContent,
                visibilityPredicate = { it.viewMode == ViewMode.KEYBOARD },
                iconResolver = { null },
                textResolver = { "," },
                enabledResolver = { true },
                actionResolver = { _, _ -> Action.KeyboardInputAction.InsertComma },
            ),
        )),
        // …
    ),
)
```

### Step 7 — Tests

Reducer test (JVM, pure, no Android Context):

```kotlin
// app/src/test/java/net/devemperor/dictate/state/modules/KeyboardInputModuleTest.kt
@Test
fun insertCommaAction_emitsSendTextEffect() {
    val result = KeyboardInputModule.reduce(
        state = Unit,
        action = Action.KeyboardInputAction.InsertComma,
        ctx = ReducerContext(DictateUiState.initial()),
    )
    assertNotNull(result)
    assertEquals(listOf(KeyboardInputModule.Effect.SendText(",")), result!!.sideEffects)
}
```

Optional: resolver test (verifies the slot's resolver returns the
expected action):

```kotlin
@Test
fun insertCommaSlot_clickInKeyboardMode_returnsInsertCommaAction() {
    val slot = LayoutCatalog.KEYBOARD_TWO_ROW.rows.flatMap { it.slots }
        .first { it.logicalId == LogicalButtonId.INSERT_COMMA }
    val state = DictateUiState.initial().copy(viewMode = ViewMode.KEYBOARD)
    val action = slot.actionResolver(state, fakeServices)
    assertEquals(Action.KeyboardInputAction.InsertComma, action)
}
```

## 4. What you DON'T have to do

- ❌ No MotionScene XML change. The button slots into the existing
  ConstraintSet (or you constrain it once and it works for all
  modes that derive from `two_row_state`).
- ❌ No Cross-Module Observer. The button has no cross-axis
  consequence.
- ❌ No DB migration.
- ❌ No new module file. `KeyboardInputModule` already exists.
- ❌ No update to `DictateModuleRegistry`. The module is registered.
- ❌ No update to `DictateInputMethodService.java`. The orchestrator
  routes the new action variant automatically.

## 5. What you MIGHT need to do (depending on the button)

| Scenario | Extra step |
|---|---|
| The button needs a new icon, not text | Add a `drawable/ic_comma.xml` and use `iconResolver = { R.drawable.ic_comma }` |
| The button should be enabled-conditionally (e.g. only when recording is idle) | Add `enabledResolver = { it.recording is RecordingState.Idle }` |
| The button is visible only in some keyboard modes | Add the slot to the relevant `LayoutMode`s only (e.g. KEYBOARD_TWO_ROW + KEYBOARD_SINGLE_ROW, but not KEYBOARD_REPROCESS_STAGING) |
| The button's icon depends on state (e.g. RECORD shows mic / stop / play) | Use a multi-branch `iconResolver = { state -> when (state.recording) { … } }` |
| The button needs Pre-Dispatch allocation (e.g. allocate a File before dispatching) | Use the 2-arg `actionResolver: (state, services) -> Action?` and call `services.audioFileFactory.allocate(...)` |
| The button should run a `services.emitAction` (async) | NOT in the resolver. Wrap in a Module Effect — see [`effects-and-failures.md`](effects-and-failures.md) |

## 6. Common mistakes (forbidden patterns)

| Mistake | Why it breaks | Correct shape |
|---|---|---|
| `actionResolver` returns `Action.NoOp` | `NoOp` doesn't exist (R.3) | Return `null` (forbidden pattern (m)) |
| Slot's `visibilityPredicate` reads `state.resend.resendCooldown` | Cooldown belongs in `enabledResolver` | Visibility is permanent-attribute; enabled is timed (forbidden pattern (j)) |
| New button without `motion:visibilityMode="ignore"` in XML | MotionScene animates visibility | Add `motion:visibilityMode="ignore"` (forbidden pattern (k)) |
| `setOnClickListener` set inside `render()` | Lambda allocation per render tick | Listener is wired once in `wireStaticHandlers` (forbidden pattern (l)) |
| Skipping the view-map entry | Render-time `error(...)` | Always add to `buttonViews` map at the backend |

See [`forbidden-patterns.md`](forbidden-patterns.md) for the full
catalogue.

## 7. Information Gaps

(no gaps known at this time — the walkthrough is end-to-end runnable from the plan §4.0.6.1)

## 8. Change History

### 2026-05-14 — Initial draft

- **Trigger:** Block 0 architecture anchor.
- **Reasoning:** Captures plan §4.0.6.1 in didactic form. The
  INSERT_COMMA example was chosen because it touches every step
  but adds zero state and zero cross-module-cascade — the smallest
  possible button addition.

## 9. References

- [Parent plan §4.0.6.1](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md)
- [ADR-0001 — state-modular-orchestrator-pattern](../../decisions/0001-state-modular-orchestrator-pattern.md)
- [ADR-0004 — ui-layout-catalog-motionlayout](../../decisions/0004-ui-layout-catalog-motionlayout.md)
- [Spec 2 §3.1 — LogicalButtonId](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md)
- [Spec 2 §8 — LayoutCatalog](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md)
- [Spec 1 §15.6 — KeyboardInputModule](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md)
- [`rendering.md`](rendering.md)
- [`wiring-ui.md`](wiring-ui.md)
- [`forbidden-patterns.md`](forbidden-patterns.md)
