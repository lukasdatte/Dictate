# Spec 3 — Floating Overlay (WIDGET + HOVER): OverlayBackend + Window Lifecycle + Permission

**Status:** Detail research extended (2026-05-08) — architecture fixed, implementation details elaborated
**Main plan:** [→ keyboard-layout-refactor.md](../../keyboard-layout-refactor.md)
**Sibling specs:**
- [Spec 1 — Pipeline Service Layer](../1-pipeline-service/1-pipeline-service.md)
- [Spec 2 — KEYBOARD Layout (IME View)](../2-keyboard-layout/2-keyboard-layout.md)

---

## §1 Context and Scope

This spec describes the **Floating-Overlay subsystem** — the two modes WIDGET and HOVER, which are shown as a separate window above other apps. It covers:

- **`OverlayBackend`**: the backend that renders both modes on a `TYPE_APPLICATION_OVERLAY` window.
- **Window lifecycle**: Create, Attach, Detach, Destroy via `WindowManager`.
- **Permission flow**: `SYSTEM_ALERT_WINDOW` permission, onboarding UI, fallback on denial.
- **Mode transitions**: KEYBOARD ↔ WIDGET, KEYBOARD/WIDGET → HOVER (auto), HOVER → KEYBOARD (auto).
- **Close-button differential behaviour**: in WIDGET = transition to KEYBOARD, in HOVER = dismiss.
- **Touch routing**: buttons in the overlay are clickable, the background is transparent (or fixed).

Out of scope (other specs):
- KEYBOARD-mode rendering, MotionLayout, IME view — see Spec 2.
- Pipeline state mutation, service lifecycle — see Spec 1.

---

## §2 Architecture Decisions (fixed)

| # | Decision | Rationale |
|---|--------------|------------|
| O1 | **`TYPE_APPLICATION_OVERLAY`** as window type | Standard for floating-over-other-apps on Android 8+. `TYPE_SYSTEM_ALERT` has been deprecated since API 26. |
| O2 | **`SYSTEM_ALERT_WINDOW` permission** required | Required by the system for TYPE_APPLICATION_OVERLAY. The user must explicitly toggle it in Settings. |
| O3 | **Shared 5-button layout** for WIDGET and HOVER (OPEN-2) | The layout is identical — Record + Send are **disabled** in HOVER (no InputConnection target). This makes WIDGET self-sufficient (the user can start recording from within the widget). |
| O4 | **Close-button differential**: in WIDGET → transitionTo(KEYBOARD) (with SmallMode activation); in HOVER → dismiss-only. | Decided by the user. |
| O5 | **Own view instances** in the overlay window (not shared with the IME view) | Android hard constraint: a view can only live in one window. |
| O6 | **Drag + per-orientation persistence** (OPEN-3) | Position is written normalized (0..1) into SharedPreferences, separately for portrait and landscape. Click-vs-drag via 8dp threshold. Default top-end with ~80dp y-offset. Snap-to-edge not initially (rationale in §11.5.7). |
| O7 | **Notification fallback** on permission denial | The foreground-service notification is there anyway (Spec 1) — the user sees status even without an overlay. |
| O8 | **Window lifecycle managed by the OverlayBackend itself** | Not by the IME service directly — the manager only triggers "render in OVERLAY mode", the backend takes care of the WindowManager calls. |

---

## §3 Layout-Mode Definition (shared for WIDGET and HOVER)

### §3.1 LayoutMode Data Structure

<!-- FIX: Issue 1.0.5 – Action-Hierarchie (F-8/F-11) durchpropagiert in §3.1/§6/§7.3 (Mapping siehe Spec 2 §3.3) -->
<!-- FIX: Phase-C C-5 (2026-05-14) – Cross-Spec-Korrektur (C-4 F-5): `OVERLAY_5BUTTON` als Member von
     `LayoutCatalog` deklariert (statt top-level `object`). Hintergrund: Spec 2 §4 + §8.6 sowie Spec 3 §11/§14
     (mehrere Stellen) referenzieren `LayoutCatalog.OVERLAY_5BUTTON` als qualifizierten Catalog-Member;
     C-4 hat in Spec 2 §8.6 den Property-Skelett-Anker (`// val OVERLAY_5BUTTON: LayoutMode = ...`) gesetzt
     und die Inhalts-SoT in dieser §3.1 belassen. Auflösung: das Singleton-`object` ist jetzt als
     `object OVERLAY_5BUTTON : LayoutMode(...)` INNERHALB des `LayoutCatalog`-Objects deklariert. Damit
     ist `LayoutCatalog.OVERLAY_5BUTTON` ein gültiger qualifizierter Member-Zugriff (Kotlin nested object).
     Spec 2 §8.6 Property-Skelett verweist hierher als Inhalts-SoT. -->

```kotlin
// Im LayoutCatalog-Object eingebettet (Spec 2 §8.6 ist der SoT-Strukturplatz; Inhalt ist hier).
object LayoutCatalog {
    // ... (KEYBOARD-Modes + forKeyboard(state) — siehe Spec 2 §8.6)

    object OVERLAY_5BUTTON : LayoutMode(
        id = LayoutModeId.OVERLAY_5BUTTON,
        backend = BackendType.OVERLAY_WINDOW,
    rows = listOf(
        // Reihe 1: Record + Senden + Pause
        RowDescriptor(slots = listOf(
            // (OPEN-2): Record-Button macht das WIDGET autark — User kann die Aufnahme
            // direkt aus dem Widget heraus starten, ohne erst zur Tastatur zu gehen.
            // Sichtbar in Idle (kein Recording, keine Pipeline). In HOVER disabled,
            // weil keine InputConnection vorhanden ist und das Resultat nirgendwohin
            // geschrieben werden könnte.
            ButtonSlot(LogicalButtonId.OVERLAY_RECORD, WrapContent,
                visibilityPredicate = { state ->
                    state.recording is RecordingState.Idle
                        && state.pipeline is PipelineUiState.Idle
                },
                enabledResolver = { state -> state.viewMode == ViewMode.WIDGET },
                alphaResolver = { state -> if (state.viewMode == ViewMode.WIDGET) 1f else 0.4f },
                iconResolver = { R.drawable.ic_baseline_mic_24 },
                // FIX: Phase-B S-7 (2026-05-13) – Pre-Dispatch-Allocation (R.2 / Spec 1 §4.11).
                // Vorher: `StartRecording(target = …)` ohne `audioFile` → Compile-Error (data class
                // `StartRecording(target, audioFile)` verlangt beide Felder). Jetzt: Resolver-Signatur
                // `(state, services) -> Action?` (Spec 2 §3.2 post-S-7); IOException aus allocate() wird
                // in Toast-Sink-Pfad übersetzt + null returnt (kein dispatch, kein Reducer-State-Wechsel).
                // Konsistent mit Spec 2 §8.5 `resolveRecordAction`. Service-Heimat: WidgetOverlayBackend-
                // Konstruktor (§4.2 Spec 3 post-S-7).
                actionResolver = ::resolveOverlayRecordAction),
            ButtonSlot(LogicalButtonId.OVERLAY_SEND, FillRemaining,
                visibilityPredicate = { true },
                enabledResolver = { state -> state.viewMode == ViewMode.WIDGET
                                              && state.recording.isActiveOrPaused },
                alphaResolver = { state ->
                    if (state.viewMode == ViewMode.WIDGET && state.recording.isActiveOrPaused) 1f
                    else 0.4f
                },
                textResolver = { state -> resolveOverlaySendText(state) },
                actionResolver = { Action.RecordingAction.StopRecordingAndSend }),
            ButtonSlot(LogicalButtonId.OVERLAY_PAUSE, WrapContent,
                visibilityPredicate = { true },
                enabledResolver = { state -> state.recording.isActiveOrPaused },
                iconResolver = { state ->
                    if (state.recording is RecordingState.Paused) R.drawable.ic_baseline_mic_24
                    else R.drawable.ic_baseline_pause_24
                },
                actionResolver = { state ->
                    if (state.recording is RecordingState.Paused) Action.RecordingAction.ResumeRecording
                    else Action.RecordingAction.PauseRecording
                }),
        )),
        // Reihe 2: Trash + Schließen (Schließen unten rechts wie vom User gewünscht)
        RowDescriptor(slots = listOf(
            ButtonSlot(LogicalButtonId.OVERLAY_TRASH, WrapContent,
                visibilityPredicate = { state -> state.recording.isActiveOrPaused
                                                || state.pipeline !is PipelineUiState.Idle },
                actionResolver = { Action.RecordingAction.CancelRecording }),
            <!-- FIX: Issue 1.1.4 + 2.1.7 / R.3 – Resolver returnt Action? = null statt Action.NoOp -->
            <!-- FIX: Issue 3.1.7 (User-Decision Option A) – CloseOverlay-Cascade triggert Suppress-Bit -->
            ButtonSlot(LogicalButtonId.OVERLAY_CLOSE, WrapContent,
                visibilityPredicate = { true },
                actionResolver = { state ->
                    when (state.viewMode) {
                        ViewMode.WIDGET -> Action.ViewModeAction.ToggleViewModeWidget  // → KEYBOARD mit SmallMode
                        ViewMode.HOVER -> Action.ViewModeAction.CloseOverlay              // dismiss-only; OverlayModule cascadiert SuppressAutoOverlayUntilNextSession
                        else -> null
                    }
                }),
        )),
    ),
    )    // schließt `object OVERLAY_5BUTTON : LayoutMode(...)`
}        // schließt `object LayoutCatalog`
```

**Key observation:** a single `LayoutMode` covers both ViewMode variants. The differences live in the resolvers, which read `state.viewMode` and behave accordingly — in particular they disable Record + Send in HOVER, because no InputConnection exists as a target (OPEN-2).

<!-- FIX: Phase-B S-7 (2026-05-13) – OVERLAY_RECORD-Resolver mit Pre-Dispatch-Allocation. -->
**`resolveOverlayRecordAction` (pre-dispatch allocation, Spec 1 §4.11.4):**

Analogous to Spec 2 §8.5 `resolveRecordAction`, the widget Record button needs an `audioFile`
allocation BEFORE the `StartRecording` action is dispatched (R.2 pure-reducer guarantee). On the
widget path `state.recording is Idle` (that is the visibility condition); allocation only happens
there. On `IOException` (storage full) the resolver returns `null` + shows a
toast — no dispatch, no reducer state change.

```kotlin
/**
 * OVERLAY_RECORD-Click in WIDGET — Pre-Dispatch-Allocation (R.2 / Spec 1 §4.11).
 *
 * IOException-Handling (Spec 1 §4.11.10 / F1): `services.audioFileFactory.allocate()` kann
 * werfen, wenn `mkdirs()` auf `cacheDir/audio/` failt (Storage voll, FS-Permission). Resolver
 * fängt **lokal**, zeigt einen Toast über `services.toastSink` und gibt `null` zurück; der
 * Caller (Click-Handler) sieht eine No-Op. Reducer sieht den Failure NIE.
 *
 * In HOVER ist Visibility-Predicate `state.viewMode == WIDGET` false → Click ist disabled
 * (keine Event-Auslösung möglich). Resolver-Fallback `null` für sicheres Verhalten, falls
 * trotzdem ein Click ankommt (z.B. Race zwischen ViewMode-Toggle und Touch).
 */
fun resolveOverlayRecordAction(
    state: DictateUiState,
    services: ModuleServices,
): Action? = when {
    state.viewMode != ViewMode.WIDGET -> null    // HOVER ist disabled, defensive
    state.recording !is RecordingState.Idle -> null
    else -> {
        val file = try {
            services.audioFileFactory.allocate()
        } catch (e: java.io.IOException) {
            services.toastSink.show(R.string.dictate_storage_full)    // selbe String-Resource wie Spec 2 §8.5
            android.util.Log.w("OverlayResolver", "audioFileFactory.allocate failed", e)
            return null
        }
        Action.RecordingAction.StartRecording(
            target = InsertionTarget.MainInputConnection,
            audioFile = file,
        )
    }
}
```

**Migration point for other 1-arg resolvers in §3.1:** The slot definitions above use
1-arg lambdas (`{ state -> ... }` or `{ Action.X }`). After S-7 the resolver type is
`(DictateUiState, ModuleServices) -> Action?` — Kotlin then requires 2-arg lambdas. All non-record
slots ignore the second argument: `{ Action.X }` → `{ _, _ -> Action.X }`,
`{ state -> ... }` → `{ state, _ -> ... }`. The Block-6 implementer (Spec 3) must extend the
slot definitions mechanically; a compile error at the first build points out the spot.

### §3.2 Concrete Overlay XML Layout

File: `app/src/main/res/layout/overlay_5button_layout.xml`

Layout structure: two rows with 3+2 columns.
- Row 1: `[Record] [Send] [Pause]`
- Row 2: `[Trash] [Close]` — Close at bottom right (user request)

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/overlay_root"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:background="@drawable/overlay_background"
    android:elevation="8dp"
    android:padding="6dp">

    <!-- Reihe 1: Record + Senden + Pause -->
    <LinearLayout
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical">

        <com.google.android.material.button.MaterialButton
            android:id="@+id/overlay_record_btn"
            style="@style/OverlayButton.Icon"
            android:layout_width="48dp"
            android:layout_height="48dp"
            app:icon="@drawable/ic_baseline_mic_24"
            app:iconGravity="textStart"
            app:iconPadding="0dp"
            android:contentDescription="@string/overlay_record_cd" />

        <com.google.android.material.button.MaterialButton
            android:id="@+id/overlay_send_btn"
            style="@style/OverlayButton.Primary"
            android:layout_width="0dp"
            android:layout_height="48dp"
            android:layout_weight="1"
            android:layout_marginStart="6dp"
            android:minWidth="100dp"
            android:text="@string/overlay_send"
            app:icon="@drawable/ic_baseline_send_24"
            app:iconGravity="textStart"
            android:contentDescription="@string/overlay_send_cd" />

        <com.google.android.material.button.MaterialButton
            android:id="@+id/overlay_pause_btn"
            style="@style/OverlayButton.Icon"
            android:layout_width="48dp"
            android:layout_height="48dp"
            android:layout_marginStart="6dp"
            app:icon="@drawable/ic_baseline_pause_24"
            app:iconGravity="textStart"
            app:iconPadding="0dp"
            android:contentDescription="@string/overlay_pause_cd" />
    </LinearLayout>

    <!-- Reihe 2: Trash links, Schließen rechts (User-Wunsch: Schließen unten rechts) -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="6dp"
        android:orientation="horizontal"
        android:gravity="center_vertical">

        <com.google.android.material.button.MaterialButton
            android:id="@+id/overlay_trash_btn"
            style="@style/OverlayButton.Icon"
            android:layout_width="48dp"
            android:layout_height="48dp"
            app:icon="@drawable/ic_baseline_delete_24"
            app:iconGravity="textStart"
            app:iconPadding="0dp"
            android:contentDescription="@string/overlay_trash_cd" />

        <Space
            android:layout_width="0dp"
            android:layout_height="0dp"
            android:layout_weight="1" />

        <com.google.android.material.button.MaterialButton
            android:id="@+id/overlay_close_btn"
            style="@style/OverlayButton.Icon"
            android:layout_width="48dp"
            android:layout_height="48dp"
            app:icon="@drawable/ic_baseline_close_24"
            app:iconGravity="textStart"
            app:iconPadding="0dp"
            android:contentDescription="@string/overlay_close_cd" />
    </LinearLayout>
</LinearLayout>
```

**Background drawable** `res/drawable/overlay_background.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="?attr/colorSurface" />
    <corners android:radius="16dp" />
    <stroke android:width="1dp" android:color="?attr/colorOutlineVariant" />
</shape>
```

**Style definitions** (in `res/values/styles_overlay.xml`):

```xml
<style name="OverlayButton.Primary" parent="Widget.Material3.Button">
    <item name="cornerRadius">12dp</item>
    <item name="android:textAllCaps">false</item>
</style>

<style name="OverlayButton.Icon" parent="Widget.Material3.Button.IconButton">
    <item name="cornerRadius">12dp</item>
</style>
```

**Resulting size** (measured): approx. 270-310 dp width, 110 dp height (two 48dp rows + 6dp gap + 12dp padding; row 1 has three 48dp buttons + 6dp gaps + Send-FillRemaining with minWidth=100dp). Compact, fits in any screen corner without obscuring content.

**ID contract** (referenced by §4.4 code):

| ID | Slot |
|----|------|
| `overlay_record_btn` | `LogicalButtonId.OVERLAY_RECORD` |
| `overlay_send_btn` | `LogicalButtonId.OVERLAY_SEND` |
| `overlay_pause_btn` | `LogicalButtonId.OVERLAY_PAUSE` |
| `overlay_trash_btn` | `LogicalButtonId.OVERLAY_TRASH` |
| `overlay_close_btn` | `LogicalButtonId.OVERLAY_CLOSE` |

---

## §4 OverlayBackend — API + Implementation

### §4.1 Backend Class with Encapsulated Window-Manager Wrapper (DIP)

So that the backend is not hard-wired to `android.view.WindowManager` (DIP, testable), window management is extracted into a thin wrapper:

```kotlin
interface OverlayWindow {
    fun isAttached(): Boolean
    fun attach(view: View, params: WindowManager.LayoutParams)
    fun update(view: View, params: WindowManager.LayoutParams)
    fun detach(view: View)
}

<!-- FIX: Phase-B S-8 (2026-05-13) – Wrapper-interne Exception-Hygiene für ALLE drei WindowManager-Calls.
     Vorher: attach() fing BadTokenException NICHT (Catch lebte stattdessen im OverlayBackend.inflateAndAttach
     — SRP-Verstoß: Lifecycle-Idempotenz ist Aufgabe des Wrappers, nicht des Backends);
     update() fing IllegalArgumentException NICHT (Race-Pfad: System detached View bei Permission-Revoke
     zur Laufzeit, attached-Bit ist noch true, der nächste applyPosition()-Call führt zu updateViewLayout
     auf einer nicht-mehr-attached View → IllegalArgumentException). Beide Pfade jetzt im Wrapper:
     - attach() catched BadTokenException + setzt attached=false (Permission revoked vor addView).
     - update() catched IllegalArgumentException + setzt attached=false (View bereits OS-seitig detached).
     Damit ist OverlayWindow vollständig SRP-konform für Window-Lifecycle-Idempotenz; das Backend
     muss keinen WindowManager-Exception-Typen mehr kennen (DIP). -->
class AndroidOverlayWindow(
    private val windowManager: WindowManager,
) : OverlayWindow {
    private var attached = false
    override fun isAttached() = attached
    override fun attach(view: View, params: WindowManager.LayoutParams) {
        if (attached) return
        try {
            windowManager.addView(view, params)
            attached = true
        } catch (e: WindowManager.BadTokenException) {
            // Permission wurde revoked, bevor addView lief. attached bleibt false; Caller (Backend)
            // sieht über isAttached() == false, dass der Attach nicht erfolgreich war.
            android.util.Log.w("AndroidOverlayWindow", "addView failed — permission revoked at runtime?", e)
            attached = false
        }
    }
    override fun update(view: View, params: WindowManager.LayoutParams) {
        if (!attached) return
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: IllegalArgumentException) {
            // Race: System detached die View OS-seitig (z.B. Permission-Revoke), bevor unser
            // Wrapper das Bit gedreht hat. Idempotent: attached=false, beim nächsten render()
            // wird das Backend einen sauberen re-attach versuchen, der dann am Permission-Gate
            // korrekt abbiegt.
            android.util.Log.w("AndroidOverlayWindow", "updateViewLayout on detached view — was OS-detached?", e)
            attached = false
        }
    }
    override fun detach(view: View) {
        if (!attached) return
        try { windowManager.removeView(view) } catch (e: IllegalArgumentException) {
            // View war nicht (mehr) attached — z.B. nach Permission-Revoke. Idempotent.
        }
        attached = false
    }
}
```

This lets a `FakeOverlayWindow` in tests simply track the status, without emulating real `WindowManager` calls.

**Lifecycle-idempotency contract (Phase-B S-8):** The wrapper is the sole SRP home for
WindowManager exception handling. All three methods (`attach`/`update`/`detach`) are
**idempotent against OS-side detach races** — when Android removes the view behind our back
(permission revoke at runtime, window-token invalidation), we flip
`attached = false` and the next `render()` call runs cleanly back into the
permission-gate check. The backend must know **no** WindowManager-specific exception type —
DIP-conformant.

### §4.2 OverlayBackend (revised)

`OverlayBackend` delegates drag handling to a standalone `OverlayDragHandler`
(§4.6) and position computation to `OverlayPositionMapper` (§4.7) — both injected via the
constructor (DIP). This preserves SRP: the backend does rendering, the DragHandler does
touch routing, the PositionMapper does 0..1 ↔ pixel conversion.

<!-- FIX: Phase-B S-7 (2026-05-13) – OverlayBackend-Konstruktor um `services: ModuleServices` erweitert.
     Hintergrund: Resolver-Signatur post-S-7 ist `(state, services) -> Action?` (Spec 2 §3.2);
     OVERLAY_RECORD-Resolver (`resolveOverlayRecordAction`, §3.1 post-S-7) braucht
     `services.audioFileFactory.allocate()` als Pre-Dispatch-Allocation (R.2, Spec 1 §4.11).
     Click-Listener-Loop (im Slot-Loop-Snippet weiter unten) ruft `slot.actionResolver(state, services)`.
     Konsistent mit `ImeViewBackend` (Spec 2 §6 post-S-7). -->
```kotlin
class OverlayBackend(
    private val ctx: Context,
    private val services: ModuleServices,                // Phase-B S-7: für Pre-Dispatch-Allocation (audioFileFactory)
    private val overlayWindow: OverlayWindow,
    private val permissions: OverlayPermissionGate,  // §5.1
    private val layoutParamsFactory: OverlayLayoutParamsFactory = DefaultOverlayLayoutParamsFactory(ctx),
    private val dragHandlerFactory: OverlayDragHandlerFactory = DefaultOverlayDragHandlerFactory(ctx),
    private val positionMapper: OverlayPositionMapper = DefaultOverlayPositionMapper(ctx),
) : RenderBackend {

    private var overlayView: View? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private var dragHandler: OverlayDragHandler? = null
    private var onAction: ((Action) -> Unit)? = null
    private var buttonViews: Map<LogicalButtonId, View> = emptyMap()
    /** State-Snapshot — wird von `view.post`-Callback (F-6 / GAP-7) und Click-Listener (3.1.10) gelesen. */
    private var stateRef: DictateUiState? = null
    <!-- FIX: Issue 3.1.10 / R.10 – modeRef-Feld für stateRef-driven Click-Listener -->
    private var modeRef: LayoutMode? = null

    override fun attach(onAction: (Action) -> Unit) {
        this.onAction = onAction
        // KEIN inflate hier — render() macht das idempotent.
        // Begründung: attach() darf laufen, bevor wir wissen, ob Permission da ist.
    }

    override fun detach() {
        this.onAction = null
        teardownOverlay()
    }

    override fun render(state: DictateUiState, mode: LayoutMode) {
        require(mode.backend == BackendType.OVERLAY_WINDOW)

        <!-- FIX: Issue 3.1.3 (User-Decision Option A) – Permission als State-Achse statt Live-Read -->
        if (!state.overlay.hasPermission) {
            // Fallback-Pfad (§5.4): kein Overlay zeigen, Notification reicht.
            // OverlayPermissionObserver hält state.overlay.hasPermission synchron.
            teardownOverlay()
            return
        }
        <!-- FIX: Issue 3.1.7 – Suppress-Bit: User schloss Overlay; nicht reopen -->
        if (state.overlay.suppressAutoOverlayUntilNextSession) {
            teardownOverlay()
            return
        }
        stateRef = state
        modeRef = mode
        if (overlayView == null) inflateAndAttach()

        applySlots(state, mode)
        applyPosition(state)
    }

    <!-- FIX: Issue 3.1.10 (User-Decision Option A) – Spec-2-Pattern: stateRef-driven, einmaliger Click-Listener -->
    /**
     * Slot → View-Properties über den geteilten Top-Level-Helper `applySlotToView`
     * aus Spec 2 §5.1 (F-7). Click-Listener werden **einmalig** gesetzt (in `attach()`/
     * `inflateAndAttach`); sie referenzieren `stateRef`/`modeRef` (Felder), nicht
     * Render-Argumente — eliminiert Single-Frame-Race und Performance-Allocation pro
     * Render-Tick. Konsistenz mit `ImeViewBackend.wireStaticHandlers` (Spec 2 §6).
     */
    private fun applySlots(state: DictateUiState, mode: LayoutMode) {
        mode.rows.flatMap { it.slots }.forEach { slot ->
            val view = buttonViews[slot.logicalId] ?: return@forEach
            applySlotToView(slot, view, state, ctx)   // (F-7) geteilter Helper
        }
    }

    /** Einmal in `inflateAndAttach()` aufgerufen; Listener lesen aktuellen State über stateRef. */
    private fun wireStaticOverlayHandlers() {
        buttonViews.forEach { (id, view) ->
            view.setOnClickListener {
                val s = stateRef ?: return@setOnClickListener
                val slot = currentSlot(id) ?: return@setOnClickListener
                // FIX: Phase-B S-7 (2026-05-13) – 2-arg Resolver (state, services) für Pre-Dispatch-Allocation.
                // FIX: Phase-C C-5 (2026-05-14) – Cross-Spec-DRY-Verifikation (C-3 F-7): das
                //      `?.let { onAction?.invoke(it) }`-Pattern ist identisch zu Spec 2 §6
                //      `ImeViewBackend.wireStaticHandlers`. Resolver-`null` wird strukturell aussortiert
                //      (kein `DispatchOutcome.Unrouted`/`Rejected`-Log-Pfad für unsinnige Clicks);
                //      Resolver ist erste Validierungs-Schicht, Reducer ist zweite. Cross-Ref auf
                //      Spec 2 §3.2 ButtonSlot.actionResolver-KDoc (post-C-3).
                slot.actionResolver(s, services)?.let { onAction?.invoke(it) }
            }
        }
    }

    private fun currentSlot(id: LogicalButtonId): ButtonSlot? =
        modeRef?.rows?.flatMap { it.slots }?.firstOrNull { it.logicalId == id }

    /**
     * Setzt die Window-Position auf die im State persistierte Position für die aktuelle
     * Orientation (OPEN-3). Der State hält normalisierte 0..1-Koordinaten; der
     * `positionMapper` konvertiert sie in absolute Pixel auf Basis der aktuellen
     * Display-Größe. Wird von `render()` bei JEDEM State-Update aufgerufen — billig,
     * weil `windowManager.updateViewLayout` no-op ist, wenn die Params unverändert sind
     * (wir merken uns die letzten Params und vergleichen).
     */
    <!-- FIX: Issue 3.1.5 / R.18 – Drag-Hoheit: applyPosition early-returnt während aktivem Drag -->
    <!-- FIX: Issue 3.1.6 (User-Decision Option A) – early-return wenn view.effectiveSize null + Aspect-Bucket-Persist -->
    private fun applyPosition(state: DictateUiState) {
        val view = overlayView ?: return
        val params = currentParams ?: return

        // Drag-Hoheit: während aktivem Drag NIEMALS einen externen Position-Set überschreiben.
        // FIX: Phase-B S-8 (2026-05-13) – Null-Behandlung explizit dokumentiert:
        // `dragHandler == null` (zwischen detach() und nächstem inflateAndAttach()) → `?.isDragging()`
        // ist null → `== true` ist false → kein early-return. Das ist korrekt: ohne aktiven
        // Drag-Handler existiert keine Drag-Hoheit, die zu schützen wäre. Position-Set läuft normal.
        if (dragHandler?.isDragging() == true) return

        val isPortrait = ctx.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val (normX, normY) = if (isPortrait) {
            state.overlay.positionPortraitX to state.overlay.positionPortraitY
        } else {
            state.overlay.positionLandscapeX to state.overlay.positionLandscapeY
        }

        // Issue 3.1.6 / R.20: View hat noch keinen Layout-Pass → Position-Set postponen.
        val (px, py) = positionMapper.normalizedToPixels(normX, normY, view) ?: return

        if (params.x != px || params.y != py || params.gravity != (Gravity.TOP or Gravity.START)) {
            params.gravity = Gravity.TOP or Gravity.START  // Drag-Modus rechnet von TOP|START
            params.x = px
            params.y = py
            overlayWindow.update(view, params)
        }
    }

    private fun inflateAndAttach() {
        val inflater = ctx.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.overlay_5button_layout, null)
        buttonViews = mapOf(
            LogicalButtonId.OVERLAY_RECORD to view.findViewById(R.id.overlay_record_btn),
            LogicalButtonId.OVERLAY_SEND to view.findViewById(R.id.overlay_send_btn),
            LogicalButtonId.OVERLAY_PAUSE to view.findViewById(R.id.overlay_pause_btn),
            LogicalButtonId.OVERLAY_TRASH to view.findViewById(R.id.overlay_trash_btn),
            LogicalButtonId.OVERLAY_CLOSE to view.findViewById(R.id.overlay_close_btn),
        )
        val params = layoutParamsFactory.create()
        // FIX: Phase-B S-8 (2026-05-13) – BadTokenException-Catch wandert in den Wrapper (§4.1).
        // Backend prüft das Resultat über overlayWindow.isAttached() und bricht ab, wenn der
        // Wrapper-Attach gescheitert ist. SRP: Backend kennt keinen WindowManager-Exception-Typ mehr.
        overlayWindow.attach(view, params)
        if (!overlayWindow.isAttached()) {
            // Wrapper hat BadTokenException gefangen — Permission ist zur Laufzeit revoked worden.
            // Keep-going-Pfad: kein Crash, kein State-Touch. Beim nächsten render() läuft der
            // Permission-Gate-Check oben in render() (state.overlay.hasPermission == false sobald
            // OverlayPermissionObserver.refresh() den Wechsel sieht) in den Fallback-Pfad.
            buttonViews = emptyMap()
            return
        }
        overlayView = view
        currentParams = params
        // <!-- FIX: Issue 3.1.10 / R.10 – wireStaticOverlayHandlers einmal pro inflate -->
        wireStaticOverlayHandlers()
        // Drag-Handling: OnTouchListener auf Root-View. Move > Threshold → Drag-Modus,
        // Tap < Threshold → Click an Button durchreichen. Drag-End emittiert
        // Action.OverlayAction.UpdateOverlayPosition mit normalisierten 0..1-Koordinaten.
        // FIX: Issue 3.0.5 – flache Action.UpdateOverlayPosition → Action.OverlayAction.UpdateOverlayPosition (Phase-1-Mapping)
        dragHandler = dragHandlerFactory.create(
            view = view,
            window = overlayWindow,
            paramsHolder = { currentParams },
            positionMapper = positionMapper,
            onPositionPersist = { normX, normY ->
                val portrait = ctx.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                onAction?.invoke(Action.OverlayAction.UpdateOverlayPosition(portrait, normX, normY))
            },
        ).also { it.attach() }
        // (F-6 / GAP-7): Erste applyPosition läuft direkt im render() — aber view.width
        // und view.height sind dann oft noch 0, weil der Layout-Pass noch nicht durchgelaufen
        // ist. `view.post {}` schiebt den Re-Apply ans Ende der Message-Queue, also nach dem
        // ersten Layout-Pass. So wird die Window-Position mit den dann korrekten View-
        // Dimensionen gesetzt; das einmalige "Top-End-Default-Frame" verschwindet.
        view.post {
            stateRef?.let { applyPosition(it) }
        }
    }

    private fun teardownOverlay() {
        dragHandler?.detach()
        dragHandler = null
        overlayView?.let { overlayWindow.detach(it) }
        overlayView = null
        currentParams = null
        buttonViews = emptyMap()
    }

    companion object { private const val TAG = "OverlayBackend" }
}
```

### §4.3 LayoutParamsFactory — Complete WindowManager Configuration

```kotlin
interface OverlayLayoutParamsFactory {
    fun create(): WindowManager.LayoutParams
}

class DefaultOverlayLayoutParamsFactory(
    private val ctx: Context,
) : OverlayLayoutParamsFactory {

    override fun create(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
            // TYPE_SYSTEM_ALERT wurde überlegt, ist aber strenger sandboxed (Bildschirm-
            // Lock-Screen-Anzeige). TYPE_PHONE deckt unsere User-Story (5-Button-Widget
            // über App) auf API < 26 ausreichend ab. Vor Android 6 sowieso kein Permission-
            // Prompt nötig — auf API 23-25 fragt das System SYSTEM_ALERT_WINDOW über
            // Settings.ACTION_MANAGE_OVERLAY_PERMISSION ab; identisch zur aktuellen Logik.
        }

        val flags = (
            // Wir wollen KEINEN Keyboard-Fokus klauen. Das IME wird ja teilweise
            // gleichzeitig sichtbar sein (im WIDGET-Fall durch User-Toggle).
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            // Touches AUSSERHALB unseres Overlays gehen weiter an die App darunter.
            // Ohne diesen Flag würde Android das gesamte Display unter unser Window
            // legen und Touches schlucken — das wäre für ein 5-Button-Widget grob.
            or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            // Layout in Screen-Koordinaten, damit gravity + x/y-Offsets vom Bildschirmrand
            // aus berechnet werden (statt vom Decor-Frame der unterliegenden App).
            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            // Hardware-beschleunigt rendern (Material-Buttons mit Ripple, Elevation).
            or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        <!-- FIX: Issue 3.1.11 / R.19 – Single-Owner-Vertrag: gravity = TOP|START direkt -->
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,  // Alpha-Channel im Drawable wird respektiert.
        ).apply {
            // Initial-Anker mit gravity = TOP|START (eliminiert Initial-Frame-Switch):
            // Drag-Koordinaten rechnen konsistent vom oberen-linken Display-Eck.
            // Der initiale Pixel-x/y wird aus dem normalisierten Default
            // (`state.overlay.position*X = 1.0f` ⇒ rechte Display-Kante) berechnet,
            // **wenn `view.measuredWidth > 0` ist** (R.19); sonst postponiert
            // `applyPosition` den Set-Call (early-return — siehe R.20 / 3.1.6).
            gravity = Gravity.TOP or Gravity.START
            // x und y bleiben 0 hier — werden nach erstem Layout-Pass via
            // `OverlayPositionMapper.normalizedToPixels(view, normX, normY)` gesetzt.
            x = 0
            y = 0
            // KEINE Animation — wir wollen sofortige Anzeige, kein Slide-In.
            windowAnimations = 0
        }
    }
}

private fun dpToPx(ctx: Context, dp: Int): Int =
    (dp * ctx.resources.displayMetrics.density).toInt()
```

### §4.4 Flag Table: Why What

| Flag | Set? | Rationale |
|------|---------|------------|
| `FLAG_NOT_FOCUSABLE` | **YES** | The overlay must not steal focus from the underlying app. Soft-keyboard input in the app stays functional. (AOSP thereby also implicitly sets `FLAG_NOT_TOUCH_MODAL`.) |
| `FLAG_NOT_TOUCH_MODAL` | **YES (explicit)** | Set explicitly because we have `WRAP_CONTENT` — touches OUTSIDE the buttons must pass through to the app underneath. Without this flag the window swallows the entire touch stream on the screen. |
| `FLAG_LAYOUT_IN_SCREEN` | **YES** | The position anchor (gravity + x + y) computes from the display edge — not from the decor frame. Important for a stable top-right corner independent of the app status bar/action bar. |
| `FLAG_HARDWARE_ACCELERATED` | **YES** | Material buttons (ripple, elevation, shadow) need an HW layer for clean renderings. On a software layer shadows often look blurry. |
| `FLAG_KEEP_SCREEN_ON` | **NO** | That is the PipelineService's concern (wake lock there, if actively recording). Don't hold it twice. |
| `FLAG_SHOW_WHEN_LOCKED` | **NO** | We do NOT want to show the overlay on the lock screen — that would be confusing; the user can't input into an app anyway when the screen is locked. |
| `FLAG_LAYOUT_NO_LIMITS` | **NO** | Would allow the overlay to be pushed beyond the display edge. We want the system to clamp our bounds (e.g. notch protection). |
| `FLAG_DIM_BEHIND` | **NO** | Not modal — nothing is dimmed. |
| `FLAG_WATCH_OUTSIDE_TOUCH` | **NO (Phase 1)** | Would deliver us `MotionEvent.ACTION_OUTSIDE` — only meaningful once we want e.g. tap-outside-to-close behaviour. Not required today. |

### §4.5 Format

| Format | Choice | Rationale |
|--------|------|------------|
| `PixelFormat.TRANSLUCENT` | **YES** | The background is a Material surface with rounded corners — the drawable's alpha channel must be respected. |
| `PixelFormat.OPAQUE` | no | Would render our rounded corners as a black box. |
| `PixelFormat.TRANSPARENT` | no | We have an **opaque** inner background, only the corner rounding should be transparent → TRANSLUCENT is semantically correct. |

### §4.6 OverlayDragHandler (OPEN-3)

Standalone class — **not as a mixin in the backend** (SRP/SOLID, avoids touch-routing
logic in the render code). Injected into the `OverlayBackend` via a small factory; the
handler only knows window-update + persist-callback, not the state model.

```kotlin
<!-- FIX: Issue 3.1.5 / R.18 – isDragging() + Persist-bei-Detach -->
interface OverlayDragHandler {
    fun attach()
    fun detach()
    /** Issue 3.1.5 — Backend nutzt diesen Read, um applyPosition während aktivem Drag zu skippen. */
    fun isDragging(): Boolean
}

interface OverlayDragHandlerFactory {
    fun create(
        view: View,
        window: OverlayWindow,
        paramsHolder: () -> WindowManager.LayoutParams?,
        positionMapper: OverlayPositionMapper,
        onPositionPersist: (normX: Float, normY: Float) -> Unit,
    ): OverlayDragHandler
}

class DefaultOverlayDragHandlerFactory(
    private val ctx: Context,
) : OverlayDragHandlerFactory {
    override fun create(
        view: View,
        window: OverlayWindow,
        paramsHolder: () -> WindowManager.LayoutParams?,
        positionMapper: OverlayPositionMapper,
        onPositionPersist: (Float, Float) -> Unit,
    ): OverlayDragHandler = DefaultOverlayDragHandler(
        ctx, view, window, paramsHolder, positionMapper, onPositionPersist,
    )
}

class DefaultOverlayDragHandler(
    private val ctx: Context,
    private val view: View,
    private val window: OverlayWindow,
    private val paramsHolder: () -> WindowManager.LayoutParams?,
    private val positionMapper: OverlayPositionMapper,
    private val onPositionPersist: (Float, Float) -> Unit,
) : OverlayDragHandler {

    <!-- FIX: Issue 3.1.5 / R.18 – Threshold-Abstimmung mit System-Touch-Slop für Accessibility-Modes -->
    /**
     * Drag-Threshold: max(8dp, scaledTouchSlop * 1.5). Der System-Touch-Slop berücksichtigt
     * Accessibility-Anpassungen (z.B. größere Slop bei "Touch & Hold delay"-Settings); wir
     * multiplizieren mit 1.5, damit ein Drag bewusster als ein Long-Press intendiert ist.
     */
    private val dragThresholdPx: Int = run {
        val baseDp = (8 * ctx.resources.displayMetrics.density).toInt()
        val scaledSlop = (ViewConfiguration.get(ctx).scaledTouchSlop * 1.5f).toInt()
        max(baseDp, scaledSlop)
    }

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialParamsX = 0
    private var initialParamsY = 0
    private var dragging = false

    private val touchListener = View.OnTouchListener { _, event ->
        val params = paramsHolder() ?: return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                initialParamsX = params.x
                initialParamsY = params.y
                dragging = false
                false  // Touch nicht konsumieren — Buttons müssen DOWN sehen für Ripple.
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (!dragging && hypot(dx.toDouble(), dy.toDouble()) > dragThresholdPx) {
                    dragging = true
                    // Wir konsumieren den Touch-Stream ab jetzt, damit Buttons den
                    // Click NICHT auslösen (sonst würde ein Drag versehentlich klicken).
                }
                if (dragging) {
                    params.x = initialParamsX + dx.toInt()
                    params.y = initialParamsY + dy.toInt()
                    window.update(view, params)
                    true   // konsumiert
                } else {
                    false  // weiter an Buttons
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    positionMapper.pixelsToNormalized(params.x, params.y, view)?.let { (nx, ny) ->
                        onPositionPersist(nx, ny)
                    }
                    dragging = false
                    true   // Click unterdrücken — wir hatten ein echtes Drag.
                } else {
                    false  // Tap → an Buttons durchreichen.
                }
            }
            else -> false
        }
    }

    override fun attach() {
        view.setOnTouchListener(touchListener)
    }

    <!-- FIX: Issue 3.1.5 / R.18 – Persist-bei-Detach (mid-drag-detach räumt nicht-persistierte Position) -->
    override fun detach() {
        if (dragging) {
            paramsHolder()?.let { params ->
                positionMapper.pixelsToNormalized(params.x, params.y, view)?.let { (nx, ny) ->
                    onPositionPersist(nx, ny)
                }
            }
            dragging = false
        }
        view.setOnTouchListener(null)
    }

    override fun isDragging(): Boolean = dragging
}
```

**SOLID conformance:**
- **SRP:** the DragHandler knows only touch events + window update. No state mutation, no
  pref write — the persist logic is abstracted via a callback.
- **DIP:** `OverlayBackend` depends on the `OverlayDragHandler` interface (+ factory), not on
  the concrete class. Tests can inject a `FakeOverlayDragHandler`.
- **OCP:** snap-to-edge or other drag-end behaviours are addable via a decorator
  (`SnappingOverlayDragHandler` wraps DefaultOverlayDragHandler).

### §4.7 OverlayPositionMapper (OPEN-3)

Converts between normalized 0..1 coordinates (persisted in the state) and absolute
pixel coordinates (needed by the WindowManager). Single source of truth for the
conversion — avoids the DragHandler and the backend doing the same math twice.

```kotlin
interface OverlayPositionMapper {
    fun normalizedToPixels(normX: Float, normY: Float, view: View): Pair<Int, Int>
    fun pixelsToNormalized(px: Int, py: Int, view: View): Pair<Float, Float>
}

class DefaultOverlayPositionMapper(
    private val ctx: Context,
) : OverlayPositionMapper {

    /**
     * normX/normY sind 0..1, relativ zur "freien" Fläche = (Display - View-Größe).
     * normX=0 → linke Kante, normX=1 → rechte Kante (View berührt rechten Rand).
     * Analog Y.
     */
    override fun normalizedToPixels(normX: Float, normY: Float, view: View): Pair<Int, Int>? {
        val viewW = view.effectiveSize() ?: return null
        val viewH = view.effectiveHeight() ?: return null
        val (screenW, screenH) = displaySize()
        val maxX = (screenW - viewW).coerceAtLeast(0)
        val maxY = (screenH - viewH).coerceAtLeast(0)
        val px = (normX.coerceIn(0f, 1f) * maxX).toInt()
        val py = (normY.coerceIn(0f, 1f) * maxY).toInt()
        return px to py
    }

    override fun pixelsToNormalized(px: Int, py: Int, view: View): Pair<Float, Float>? {
        val viewW = view.effectiveSize() ?: return null
        val viewH = view.effectiveHeight() ?: return null
        val (screenW, screenH) = displaySize()
        val maxX = (screenW - viewW).coerceAtLeast(1)
        val maxY = (screenH - viewH).coerceAtLeast(1)
        val nx = (px.toFloat() / maxX).coerceIn(0f, 1f)
        val ny = (py.toFloat() / maxY).coerceIn(0f, 1f)
        return nx to ny
    }

    private fun displaySize(): Pair<Int, Int> {
        val metrics = ctx.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }
}

<!-- FIX: Issue 3.1.11 / R.19 – effectiveSize-Helper als Single-Source für view-Width/Height-Lookup -->
/**
 * Ermittelt die effektive Breite einer View — `width` wenn gemessen, sonst `measuredWidth`,
 * sonst null. Vermeidet duplizierte `view.width.takeIf { it > 0 } ?: view.measuredWidth`-Lookups.
 */
fun View.effectiveSize(): Int? = when {
    width > 0 -> width
    measuredWidth > 0 -> measuredWidth
    else -> null
}

fun View.effectiveHeight(): Int? = when {
    height > 0 -> height
    measuredHeight > 0 -> measuredHeight
    else -> null
}
```

**DRY:** all 0..1 ↔ pixel conversions go through this class. The backend (`applyPosition`)
and the DragHandler (`pixelsToNormalized` on UP) use the same formulas — no
drift source. When `view.effectiveSize()` returns null (view not measured), the mapper
itself returns null, and the caller (`OverlayBackend.applyPosition`) postpones the set-call.

<!-- FIX: Issue 3.1.1 (User-Decision Option A) – OverlayModule-Spec-Heimat: Spec 3 §4.8 -->
<!-- FIX: Issue 3.1.7 (User-Decision Option A) – closeOverlay-Cascade + Suppress-Bit + Audio-File-Cleanup -->
### §4.8 OverlayModule — Canonical Spec (Reducer + Cross-Module-Observer + EffectHandler)

The **OverlayModule** code lives entirely in this spec (Issue 3.1.1 Option A); Spec 1 §15.1
lists the entry in the module inventory and points here. Spec 3 §7.1 (Triangle FSM) is
**documentation** of the logic that Spec 1 §15.X-ViewModeModule canonically implements (Issue 3.1.2
Option A: code = Spec 1 ViewModeModule, doc = Spec 3 §7.1).

<!-- FIX: Phase-C C-5 (2026-05-14) – EffectFailure-Konvention bewusste Design-Entscheidung dokumentiert. -->
> **EffectFailure convention (design decision):** OverlayModule **deliberately does not**
> override the `reduceFailure(state, failure, ctx)` hook (default impl in Spec 1 §4.2 is `null` →
> EffectFailure is recorded as `DispatchOutcome.Applied`-without-state-change, an origin log is
> written). Rationale:
> - All overlay effects (`PersistOverlayPosition`, `MarkOnboardingShown`,
>   `MarkOnboardingPermanentlyDismissed`, `DeleteAudioFile`, `NotifyOverlayPermissionRequired`,
>   `OpenOverlayPermissionSettings`) are **idempotent pref writes** or pure UI side effects.
> - A pref-write failure (e.g. `SharedPreferences.apply()` fails under storage pressure) has
>   **no** rollback semantics in the OverlayState — the state is already correct, only the
>   persistent mirror lags behind. The next `prefMirror.sync` cycle (Spec 1 §4.5)
>   cleans that up.
> - A `DeleteAudioFile` failure is harmless (the audio file stays in the cache; cache cleanup
>   tidies it up later).
> - An `OpenOverlayPermissionSettings` failure (intent fails) would theoretically be noticeable,
>   but the user would notice it (the settings page doesn't open) and go into the system settings
>   manually.
>
> Thus `reduceFailure` is structurally unnecessary for OverlayModule. If a C-State / Phase-D
> false-positive finding "OverlayModule lacks reduceFailure" is raised, the resolution is this
> block. Cross-ref to Spec 2 §3.3 EffectFailure-KDoc (C-3 F-4/F-5/F-6).

```kotlin
// File: app/src/main/java/net/devemperor/dictate/state/modules/OverlayModule.kt
object OverlayModule : DictateModule<OverlayState, Action.OverlayAction, OverlayModule.Effect> {
    override val id = ModuleId.Overlay
    override val actionClass: KClass<Action.OverlayAction> = Action.OverlayAction::class

    override fun read(g: DictateUiState) = g.overlay
    override fun write(g: DictateUiState, s: OverlayState) = g.copy(overlay = s)
    override fun initialState() = OverlayState()

    sealed interface Effect : SideEffect {
        data class PersistOverlayPosition(val portrait: Boolean, val x: Float, val y: Float) : Effect
        object MarkOnboardingShown : Effect
        object MarkOnboardingPermanentlyDismissed : Effect
        data class DeleteAudioFile(val file: java.io.File) : Effect    // Issue 3.1.7 Audio-File-Cleanup
        object NotifyOverlayPermissionRequired : Effect                // Issue 3.1.3 Notification-Action
        object OpenOverlayPermissionSettings : Effect
    }

    override fun reduce(state: OverlayState, action: Action.OverlayAction, ctx: ReducerContext) = when (action) {
        is Action.OverlayAction.UpdateOverlayPosition -> TransitionResult(
            nextState = if (action.portrait)
                state.copy(positionPortraitX = action.x, positionPortraitY = action.y)
            else
                state.copy(positionLandscapeX = action.x, positionLandscapeY = action.y),
            sideEffects = listOf(Effect.PersistOverlayPosition(action.portrait, action.x, action.y)),
        )
        Action.OverlayAction.MarkOverlayOnboardingShown -> TransitionResult(
            nextState = state.copy(onboardingPending = false),
            sideEffects = listOf(Effect.MarkOnboardingShown),
        )
        Action.OverlayAction.DismissOverlayOnboarding -> TransitionResult(
            nextState = state.copy(onboardingPending = false),
            sideEffects = listOf(Effect.MarkOnboardingPermanentlyDismissed),
        )
        Action.OverlayAction.SuppressAutoOverlayUntilNextSession -> TransitionResult(
            nextState = state.copy(suppressAutoOverlayUntilNextSession = true),
            sideEffects = emptyList(),
        )
        <!-- FIX: Issue PENDING-3 / Spec-3 Reducer Simplified – OverlayAction.ResetSuppressBit -->
        <!-- FIX: Phase-B S-9 (2026-05-13) – Subscriber-Hinweis ergänzt (StateFlow distinct-Vertrag). -->
        // Idempotent: TransitionResult auch wenn bereits false. Reducer-null würde
        // DispatchOutcome.Rejected("reducer-null") triggern — semantisch falsch für eine
        // Session-Start-Markierung. `state.copy` mit unverändertem Wert ist no-op-billig
        // (Kotlin data class) und liefert ein sauberes `Applied`.
        //
        // **StateFlow-Subscriber-Verhalten (Phase-B S-9):** `MutableStateFlow.update`
        // (Spec 1 §4.4) vergleicht den neuen Wert via `equals` mit dem alten und unterdrückt
        // die Emission bei strukturell gleicher data class — der OverlayState-Subscriber
        // bekommt also KEINE Re-Render-Welle, wenn das Bit bereits `false` war. Idempotenter
        // Reset ist damit auch für State-Subscribers harmlos (kein Re-Render-Overhead, keine
        // doppelte Telemetrie).
        Action.OverlayAction.ResetSuppressBit -> TransitionResult(
            nextState = state.copy(suppressAutoOverlayUntilNextSession = false),
            sideEffects = emptyList(),
        )
        is Action.OverlayAction.SetUserPrefersWidget -> TransitionResult(
            nextState = state.copy(userPrefersWidget = action.prefers),
            sideEffects = emptyList(),
        )
        is Action.OverlayAction.OnOverlayPermissionChanged -> TransitionResult(
            nextState = state.copy(hasPermission = action.granted),
            sideEffects = emptyList(),
        )
        Action.OverlayAction.RequestOverlayPermission -> TransitionResult(
            nextState = state,
            sideEffects = listOf(Effect.OpenOverlayPermissionSettings),
        )
    }

    override fun runEffect(effect: Effect, services: ModuleServices) = when (effect) {
        is Effect.PersistOverlayPosition -> services.prefs.putOverlayPosition(effect.portrait, effect.x, effect.y)
        Effect.MarkOnboardingShown -> services.prefs.markOverlayOnboardingShown()
        Effect.MarkOnboardingPermanentlyDismissed -> services.prefs.markOverlayPermanentlyDismissed()
        is Effect.DeleteAudioFile -> { effect.file.delete(); Unit }
        Effect.NotifyOverlayPermissionRequired -> services.notifications.showPermissionRequired()
        Effect.OpenOverlayPermissionSettings -> services.activityLauncher.openOverlayPermissionSettings()
    }

    /**
     * Cross-Module-Observer (Issue 1.1.2 Option A+B + 3.1.7 + 3.1.3 Permission-Reset).
     */
    override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> {
        val cascade = mutableListOf<Action>()

        // Issue 3.1.7: HOVER → KEYBOARD via CloseOverlay-Cascade — Suppress-Bit setzen
        // + Recording/Pipeline-Cancel-Cascade (C-3 F-1 Disambiguation: Recording-Hardware vor
        //   Pipeline-State, analog Spec 1 §7.3 onDestroy-Pre-Cancel-Block).
        // FIX: Phase-C C-5 (2026-05-14) – Cancel-Cascade hier verankert; §6.2 ist Doku-Heimat
        //      (zeigt die HOVER-CloseOverlay-Pfad-Variante).
        if (prev.viewMode == ViewMode.HOVER && next.viewMode == ViewMode.KEYBOARD) {
            cascade.add(Action.OverlayAction.SuppressAutoOverlayUntilNextSession)
            // C-3-Disambiguation: aktives Recording priorisiert (synchron `Effect.ReleaseMediaRecorder`
            // + `Effect.DeleteAudioFile`); sonst laufende Pipeline (PipelineModule-Reducer setzt
            // `pipeline = Idle` + DB-Status-Effect). Audio-File-Cleanup wandert über
            // RecordingModule.Effect.DeleteAudioFile (CANCELLED-Session-Pfad).
            when {
                next.recording.isActiveOrPaused || next.recording is RecordingState.Preparing ->
                    cascade.add(Action.RecordingAction.CancelRecording)
                next.pipeline !is PipelineUiState.Idle ->
                    cascade.add(Action.PipelineAction.CancelPipeline)
                // else: idle — kein Cancel nötig.
            }
        }

        <!-- FIX: Issue PENDING-3 / Spec-3 Reducer Simplified – OverlayAction.ResetSuppressBit -->
        // Suppress-Bit-Reset bei Session-Start lebt **nicht hier**, sondern als
        // dedizierte `Action.OverlayAction.ResetSuppressBit`-Cascade im
        // `RecordingModule.onCrossModuleStateChange` (Spec 1 §15.2). Damit:
        //   - liest OverlayModule kein RecordingState mehr (SRP: Overlay-Coupling-Matrix-
        //     Zeile in §15.1.x bleibt schlank — kein neuer Recording-Read-Eintrag),
        //   - ist der Reset deterministisch in einem benannten Reducer-Arm verankert
        //     (statt impliziter "SetUserPrefersWidget-Cascade mit Reset-Kommentar"-Krücke),
        //   - findet ein zukünftiger Leser den Reset-Pfad über `grep ResetSuppressBit`.

        // Issue 3.1.3: Permission-Loss zur Laufzeit → Mode-Cascade auf KEYBOARD + Notification.
        if (prev.overlay.hasPermission && !next.overlay.hasPermission &&
            next.viewMode != ViewMode.KEYBOARD
        ) {
            cascade.add(Action.ViewModeAction.SetViewMode(ViewMode.KEYBOARD))
            // Notification-Action emittiert via Effect (siehe runEffect).
        }

        return cascade
    }
}
```

**SOLID:**
- **SRP:** OverlayModule encapsulates position/onboarding/permission/suppress in a single file.
- **OCP:** a new overlay state axis = a new sub-field in `OverlayState` + Action; no other module is touched.
- **DIP:** the EffectHandler depends on the `services.prefs` / `services.notifications` / `services.activityLauncher` interfaces.

---

## §5 Permission-Onboarding Flow

<!-- FIX: Issue 3.1.3 (User-Decision Option A) – Permission-Lifecycle als State-Achse mit Observer + Settings-Deep-Link -->

### §5.0 OverlayPermissionObserver

A small helper class keeps `state.overlay.hasPermission` synchronized with the system
permission status. Pattern analogous to `audioFocusGranted` (Spec 1 §15.3 AudioModule).

```kotlin
class OverlayPermissionObserver(
    private val ctx: Context,
    private val orchestrator: DictateOrchestrator,
) {
    /** Vom IME-onCreate gerufen. Pollt nicht — reagiert auf Lifecycle-Trigger. */
    fun init() {
        orchestrator.dispatch(Action.OverlayAction.OnOverlayPermissionChanged(checkPermission()))
    }

    /** Vom IME-onStartInputView / onCreateInputView gerufen — User kommt aus Settings zurück. */
    fun refresh() {
        val granted = checkPermission()
        // Idempotent: nur dispatchen, wenn sich der Wert wirklich geändert hat (Reducer
        //  prüft das auch, aber Cascade-Tiefe ist dann unnötig).
        orchestrator.dispatch(Action.OverlayAction.OnOverlayPermissionChanged(granted))
    }

    private fun checkPermission(): Boolean = Settings.canDrawOverlays(ctx)
}
```

**Why no live polling?** Android delivers no system-wide `OverlayPermissionChanged`
broadcast. Instead of polling, we use the lifecycle points at which the user *can* return from
the settings (`onCreateInputView`, `onStartInputView`). That is sufficient — a
permission change outside these points is irrelevant for the user UX.

<!-- FIX: Phase-B S-8 (2026-05-13) – Boot-Default-Race-Window dokumentiert. -->
<!-- FIX: Phase-C C-5 (2026-05-14) – Z.-Ref auf Section-Anchor umgestellt (C-1 F-5 Pattern). -->
**Boot-default race window (accepted):** `OverlayState.hasPermission` is `false` by default in
`DictateUiState.initial()` (Spec 1 §3 `data class OverlayState`, field `hasPermission`). Between service start
and the first `OverlayPermissionObserver.init()` dispatch (from IME-onCreate), every
state subscriber sees `hasPermission = false` — if a `render(state, mode)`
with `state.viewMode in (WIDGET, HOVER)` triggers in this window, the code falls into the
fallback path (`teardownOverlay()`). In practice this is harmless because:
- The HOVER auto-trigger requires `state.recording.isActiveOrPaused` — recording always
  starts from the IME view, which has run `init()` beforehand.
- The WIDGET toggle is explicitly clicked by the user — also only possible if the IME view
  was already visible.
The boot-race window is therefore structurally unreachable. Polling would be an anti-pattern.

<!-- FIX: Issue 1.0.6 – Hierarchische State-Pfade (F-10) durchpropagiert in §3.1/§5/§7 (Mapping siehe Spec 1 §3) -->

### §5.1 Permission Gate (central logic, separated from rendering — SRP)

```kotlin
interface OverlayPermissionGate {
    fun hasOverlayPermission(): Boolean
    fun shouldShowOnboarding(): Boolean   // erstes Mal vs. bereits abgelehnt
    fun markOnboardingShown()
    fun markPermanentlyDenied()
}

class DefaultOverlayPermissionGate(
    private val ctx: Context,
    private val prefs: SharedPreferences,
) : OverlayPermissionGate {

    override fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(ctx)

    override fun shouldShowOnboarding(): Boolean =
        !hasOverlayPermission() && !prefs.getBoolean(KEY_PERMANENTLY_DENIED, false)

    override fun markOnboardingShown() {
        prefs.edit().putBoolean(KEY_ONBOARDING_SHOWN, true).apply()
    }

    override fun markPermanentlyDenied() {
        prefs.edit().putBoolean(KEY_PERMANENTLY_DENIED, true).apply()
    }

    companion object {
        const val KEY_ONBOARDING_SHOWN = "overlay_perm_onboarding_shown"
        const val KEY_PERMANENTLY_DENIED = "overlay_perm_permanently_denied"
    }
}
```

**Rationale for the separation (SRP, §13.2):** render logic should not decide based on persistence state whether the onboarding is shown. The gate bundles permission check + onboarding state; the render path only asks yes/no.

### §5.2 Settings Intent (Permission Request)

`SYSTEM_ALERT_WINDOW` **cannot** be requested via the standard permission prompt (not a `runtime permission`, but an `appop`/`special permission`). Instead the user must explicitly toggle it in the system settings.

Concrete code, called **from the IME service** (not from an activity — we DON'T HAVE a foreground activity):

```kotlin
fun launchOverlayPermissionSettings(ctx: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${ctx.packageName}")
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // FLAG_ACTIVITY_NEW_TASK ist nötig, weil wir aus einem Service-Kontext starten,
        // nicht aus einer Activity. Ohne diesen Flag wirft Android: "Calling startActivity()
        // from outside of an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag".
    }
    ctx.startActivity(intent)
}
```

### §5.3 Onboarding UI in the IME View

Since the IME is not an activity, the onboarding prompt is shown **directly in the IME view** as an InfoBar. The `KeyboardLayoutManager` reacts to a new state bit `state.overlay.onboardingPending` (set by the `OverlayModule` reducer in the `DictateOrchestrator` (Spec 1 §4.3 + §15), as soon as `Action.ViewModeAction.ToggleViewModeWidget` is detected AND permission is missing). <!-- FIX: Issue 3.0.3 – Pre-F-11-„PipelineStateManager" auf DictateOrchestrator + Modul-Verantwortung umgestellt -->

**UI layout** — new InfoBar region above the keyboard buttons (Two-Row + Single-Row the same):

```xml
<!-- res/layout/overlay_permission_infobar.xml -->
<LinearLayout
    android:id="@+id/overlay_permission_infobar"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="?attr/colorSurfaceVariant"
    android:padding="12dp"
    android:visibility="gone"
    android:orientation="vertical">

    <TextView
        android:id="@+id/overlay_permission_message"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/overlay_perm_explainer"
        android:textAppearance="?attr/textAppearanceBodyMedium" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:gravity="end"
        android:orientation="horizontal">

        <com.google.android.material.button.MaterialButton
            android:id="@+id/overlay_perm_dismiss_btn"
            style="@style/Widget.Material3.Button.TextButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/overlay_perm_later" />

        <com.google.android.material.button.MaterialButton
            android:id="@+id/overlay_perm_grant_btn"
            style="@style/Widget.Material3.Button.TonalButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:text="@string/overlay_perm_grant" />
    </LinearLayout>
</LinearLayout>
```

**Concrete UI strings** (in `res/values/strings.xml`, German UI language):

```xml
<string name="overlay_perm_explainer">Damit das Floating-Widget über anderen Apps angezeigt werden kann, benötigt Dictate die Berechtigung „Über anderen Apps anzeigen".</string>
<string name="overlay_perm_later">Später</string>
<string name="overlay_perm_grant">Erlauben</string>
<string name="overlay_send">Senden</string>
<string name="overlay_send_cd">Aufnahme stoppen und Text senden</string>
<string name="overlay_pause_cd">Aufnahme pausieren oder fortsetzen</string>
<string name="overlay_trash_cd">Aufnahme verwerfen</string>
<string name="overlay_close_cd">Widget schließen</string>
```

**Click handler** in the `ImeViewBackend`:

```kotlin
private fun bindPermissionInfoBar(state: DictateUiState) {
    val bar = rootView.findViewById<View>(R.id.overlay_permission_infobar)
    bar.visibility = if (state.overlay.onboardingPending) View.VISIBLE else View.GONE
    if (!state.overlay.onboardingPending) return

    rootView.findViewById<View>(R.id.overlay_perm_grant_btn).setOnClickListener {
        launchOverlayPermissionSettings(ctx)
        onAction?.invoke(Action.OverlayAction.MarkOverlayOnboardingShown)  // FIX: Issue 3.0.5 – flache Action.X → Action.OverlayAction.X (Phase-1-Mapping)
        // Activity-Result kommt nicht zurück (IME ist keine Activity). Der Permission-Status
        // wird beim nächsten render() via Settings.canDrawOverlays() neu gelesen — siehe §5.5.
    }
    rootView.findViewById<View>(R.id.overlay_perm_dismiss_btn).setOnClickListener {
        onAction?.invoke(Action.OverlayAction.DismissOverlayOnboarding)  // FIX: Issue 3.0.5
    }
}
```

### §5.4 First-Time Logic vs. Denied-Again Logic

<!-- FIX: Issue 3.0.3 + 3.0.4 + 3.0.5 – Pre-F-11-„PipelineStateManager.toggleViewMode/dismissOverlayOnboarding/markOverlayOnboardingShown"-Methoden gibt es nicht mehr. Logik liegt im OverlayModule.reduce + ViewModeModule.reduce (Spec 1 §15); flache state-Pfade auf hierarchisch umgestellt. -->
<!-- FIX: Phase-C C-5 (2026-05-14) – Mode-3-Verstoß + Pure-Reducer-Violation behoben:
     (a) Mode-3-Verstoß: der "Permission-da"-Pfad mutierte gleichzeitig `viewMode + overlay.onboardingPending`
         in einem Reducer-Schritt — Cross-Axis-Mutation (Spec 1 §15.5 Anti-Beispiel-Tabelle Zeile 3, explizit
         Phase-2-Backlog). Auflösung: nach §7.3-T1-Pattern (post-S-9) — ViewModeModule.reduce mutiert NUR
         `viewMode`; `overlay.onboardingPending` wird über OverlayModule.onCrossModuleStateChange als
         Cascade aufgeräumt (Mode 2). Der Dismiss-Reset bleibt in OverlayModule.reduce-Arm.
     (b) Pure-Reducer-Violation: `permissions.markPermanentlyDenied()` + `permissions.markOnboardingShown()`
         wurden synchron im Reducer aufgerufen — Verstoß gegen R.2 (Spec 1 §4.2 reduce-Vertrag: pure,
         keine Side-Effects). Auflösung: Side-Effects über `Effect.MarkOnboardingPermanentlyDismissed`
         + `Effect.MarkOnboardingShown` emittieren (siehe §4.8 OverlayModule.Effect-Liste), runEffect
         schreibt die Prefs.
     (c) Reducer-Modul-Trennung: Snippet zeigte gemischte OverlayModule + ViewModeModule Reducer-Arme
         im selben when-Block — irreführend. Auflösung: getrennte when-Blöcke pro Modul mit klarer
         Sub-State-Signatur. -->

Pseudo-code flow as **reducer logic** (separated by `OverlayModule.reduce` + `ViewModeModule.reduce`, Spec 1 §15):

```kotlin
// ViewModeModule.reduce — Signatur: (state: ViewMode, action: Action.ViewModeAction, ctx: ReducerContext): TransitionResult<ViewMode, _>?
when (action) {
    Action.ViewModeAction.ToggleViewModeWidget -> {
        if (!ctx.global.overlay.hasPermission) {
            // Permission fehlt → kein viewMode-Wechsel. Onboarding-Trigger wird vom Resolver/UI-Pfad
            // separat ausgelöst (Action.OverlayAction.MarkOverlayOnboardingShown / DismissOverlayOnboarding,
            // siehe OverlayModule.reduce unten). null = "Action im aktuellen State nicht relevant".
            null
        } else {
            // Permission da: normaler ViewMode-Wechsel. `onboardingPending`-Cleanup lebt in
            // OverlayModule.onCrossModuleStateChange (Cascade, Mode 2 — siehe §7.3 T1 + §4.8).
            TransitionResult(nextState = ViewMode.WIDGET, sideEffects = emptyList())
        }
    }
    // ...
}

// OverlayModule.reduce — Signatur: (state: OverlayState, action: Action.OverlayAction, ctx: ReducerContext): TransitionResult<OverlayState, Effect>?
when (action) {
    Action.OverlayAction.DismissOverlayOnboarding -> TransitionResult(
        nextState = state.copy(onboardingPending = false),
        sideEffects = listOf(Effect.MarkOnboardingPermanentlyDismissed),  // Effect → runEffect schreibt Pref
    )
    Action.OverlayAction.MarkOverlayOnboardingShown -> TransitionResult(
        nextState = state.copy(onboardingPending = false),
        sideEffects = listOf(Effect.MarkOnboardingShown),                  // Effect → runEffect schreibt Pref
        // viewMode wird NICHT verändert — der User kommt aus den Settings zurück und muss
        // den Widget-Toggle erneut betätigen, dann ist Permission da und §7.1-Pfad greift.
    )
    // ... (weitere Arme siehe §4.8 OverlayModule)
}

// OverlayModule.onCrossModuleStateChange — Mode-2-Cascade für Onboarding-Auto-Cleanup:
override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> =
    if (prev.viewMode != ViewMode.WIDGET && next.viewMode == ViewMode.WIDGET && next.overlay.onboardingPending)
        listOf(Action.OverlayAction.MarkOverlayOnboardingShown)   // räumt onboardingPending nach erfolgreichem Widget-Switch
    else emptyList()
```

> **Onboarding trigger (the triggering path):** `state.overlay.onboardingPending = true` is **not** set in the
> `ViewModeModule.reduce` permission-missing path (see above — Mode-3 violation), but in the
> UI resolver path: `ImeViewBackend.bindPermissionInfoBar` (§5.3) calls the settings intent directly + dispatches
> `Action.OverlayAction.MarkOverlayOnboardingShown`. The `onboardingPending = true` setter is part of a
> dedicated reducer arm in OverlayModule (trigger TBD: either a new
> `Action.OverlayAction.RequestOverlayPermission` arm — see §4.8 — that sets `onboardingPending = true` +
> emits `Effect.OpenOverlayPermissionSettings`, or an explicit `ShowOnboarding` reducer arm).
> Implementer note: this is a Spec-3-internal design choice for Block 6.

### §5.5 Activity-Result Handling — How Does the Answer Come Back?

**Important:** the IME is not an activity, so there is no `onActivityResult`. Instead we read `Settings.canDrawOverlays()` lazily on every permission-relevant trigger:

| Trigger | Code path |
|---------|-----------|
| User returns from settings and clicks the widget toggle | `toggleViewMode(WIDGET)` calls `permissions.hasOverlayPermission()` afresh. |
| User reopens the IME view (`onStartInputView`) | The IME service calls `pipeline.notifyImeViewShown()`, the StateManager can cache `permissions.hasOverlayPermission()` here. |
| Render path (defensive) | `OverlayBackend.render()` checks `permissions.hasOverlayPermission()` as a precondition — if the system has revoked the permission (very rare), the backend tears down the window in a controlled way instead of crashing. |

This means no `onActivityResult` listener is needed. Settings is a different app; the system will not navigate back to us — the user comes back manually.

### §5.6 Fallback on Denial

- **WIDGET**: the toggle button in the IME is disabled (visually greyed out, alpha 0.4). Tooltip: "Permission missing — tap to allow". A click triggers the InfoBar again (even after permanent denial — the user should be able to reverse it).
- **HOVER**: the auto-trigger (view hidden + pipeline active) shows **no** floating overlay. Instead the persistent foreground-service notification (from Spec 1) suffices as a status indicator. No onboarding in this situation — the user is outside the keyboard, an InfoBar would be lost.

### §5.7 Manifest Entry

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

On API 23+ this is a special permission (no runtime prompt). On API < 23 it is auto-granted at install. The `Settings.canDrawOverlays()` check works on all API levels and always returns `true` on < 23.

---

## §6 Close-Button Differential Behaviour

### §6.1 In WIDGET Mode

<!-- FIX: Issue 3.0.3 + 3.0.4 – Pre-F-11-„PipelineStateManager.toggleViewMode" durch ViewModeModule.reduce ersetzt; flache state.smallMode → state.layout.smallMode -->

```kotlin
// actionResolver(state) wenn state.viewMode == WIDGET:
Action.ViewModeAction.ToggleViewModeWidget
```

ViewModeModule.reduce logic (Spec 1 §15.1; action routing via `DictateOrchestrator.dispatch`):

<!-- FIX: Issue 1.1.2 (User-Decision Option A+B kombiniert) – ViewModeModule mutiert nur viewMode; -->
<!-- Layout/Overlay-Folge-Mutationen kommen über Cross-Module-Observer-Cascade (LayoutModule + OverlayModule). -->
<!-- FIX: Phase-C C-5 (2026-05-14) – `when (state.viewMode)` → `when (state)`: `state` ist die
     ViewMode-Enum-Sub-State direkt (siehe Reducer-Signatur in §7.1). -->
```kotlin
// ViewModeModule.reduce — mutiert NUR `viewMode` (SRP):
// Signatur: reduce(state: ViewMode, action: Action.ViewModeAction, ctx: ReducerContext): TransitionResult<ViewMode, _>?
when (action) {
    Action.ViewModeAction.ToggleViewModeWidget -> {
        when (state) {
            ViewMode.WIDGET -> TransitionResult(
                nextState = ViewMode.KEYBOARD,
                sideEffects = emptyList(),
            )
            ViewMode.KEYBOARD -> TransitionResult(
                nextState = ViewMode.WIDGET,
                sideEffects = emptyList(),
            )
            else -> null
        }
    }
    // ...
}

// LayoutModule.onCrossModuleStateChange — beobachtet ViewMode → KEYBOARD und setzt SmallMode:
override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> =
    if (prev.viewMode == ViewMode.WIDGET && next.viewMode == ViewMode.KEYBOARD)
        listOf(Action.LayoutAction.SetSmallMode(true))   // User-Anforderung: "macht es die Tastatur klein"
    else emptyList()

// OverlayModule.onCrossModuleStateChange — reset userPrefersWidget bei WIDGET → KEYBOARD:
override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> =
    if (prev.viewMode == ViewMode.WIDGET && next.viewMode == ViewMode.KEYBOARD)
        listOf(Action.OverlayAction.SetUserPrefersWidget(false))
    else emptyList()

// IME-Service erkennt View-Mode-Wechsel und re-rendert IME-View
```

→ Result: the overlay is torn down, the IME view is re-rendered with SmallMode active (compact keyboard).

### §6.2 In HOVER Mode

<!-- FIX: Issue 3.0.3 + 3.0.5 – Pre-F-11-„closeOverlay()"-Methode + flache `cancelPipeline()`-Inline-Call durch Cross-Module-Cascade über DictateOrchestrator ersetzt; `state.copy(viewMode=...)` modular über ViewModeModule -->

```kotlin
// actionResolver(state) wenn state.viewMode == HOVER:
Action.ViewModeAction.CloseOverlay
```

CloseOverlay logic (in the respective module, Spec 1 §15 + ViewModeModule cross-module cascade):

<!-- FIX: Phase-C C-5 (2026-05-14) – Action-Disambiguation aus C-3 F-1 angewandt + Reducer-Signatur:
     (a) `Action.PipelineAction.CancelPipeline` (Pre-C-3-Form) → C-3-Disambiguation:
         RecordingModule hält die Recording-Hardware-Release-Effects, PipelineModule hält die Pipeline-State-Achse.
         Bei aktivem Recording in HOVER ist `Action.RecordingAction.CancelRecording` korrekt (synchroner
         `Effect.ReleaseMediaRecorder` + `Effect.DeleteAudioFile`); bei `state.pipeline !is Idle` ohne
         aktives Recording ist `Action.PipelineAction.CancelPipeline` korrekt. Komplementäres Pattern
         analog zu Spec 1 §7.3 onDestroy-Pre-Cancel-Block (C-3 F-1).
     (b) Reducer-Signatur (analog T3): `state` ist ViewMode-Enum, nicht DictateUiState. `nextState = ViewMode.KEYBOARD`.
     (c) Cross-Module-Cascade-Heimat: die Pipeline/Recording-Cancel-Cascade lebt NICHT im
         ViewModeModule.reduce-Arm (Pure-Reducer-Vertrag), sondern in OverlayModule.onCrossModuleStateChange
         als Mode-2-Cascade (HOVER → KEYBOARD + active recording → CancelRecording; HOVER → KEYBOARD +
         pipeline !is Idle → CancelPipeline). Implementer-Hinweis: §4.8 ergänzt um diese
         Cascade-Klausel — siehe Cascade-Block unten. -->
```kotlin
// ViewModeModule.reduce — Signatur: (state: ViewMode, action: Action.ViewModeAction, ctx: ReducerContext): TransitionResult<ViewMode, _>?
when (action) {
    Action.ViewModeAction.CloseOverlay -> {
        // ViewMode-Wechsel auf KEYBOARD (SRP-konform). Recording-/Pipeline-Cancel-Cascade lebt
        // in OverlayModule.onCrossModuleStateChange (HOVER → KEYBOARD-Boundary) als Mode-2-Cascade
        // — siehe §4.8 + Cascade-Block unten. ViewModeModule selbst kennt weder Recording- noch
        // Pipeline-Hardware (SRP).
        TransitionResult(nextState = ViewMode.KEYBOARD, sideEffects = emptyList())
        // → Overlay wird abgerissen, KEINE neue UI angezeigt (User ist außerhalb von Eingabefeldern).
        // User muss explizit Tastatur öffnen + schließen, damit das Auto-Trigger-System wieder greift.
    }
    // ...
}

// OverlayModule.onCrossModuleStateChange — Cancel-Cascade für CloseOverlay-in-HOVER (C-3-Disambiguation):
override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> {
    val cascade = mutableListOf<Action>()
    if (prev.viewMode == ViewMode.HOVER && next.viewMode == ViewMode.KEYBOARD) {
        cascade.add(Action.OverlayAction.SuppressAutoOverlayUntilNextSession)  // §4.8 Bestandsregel
        // C-3-Disambiguation: RecordingModule hält Recording-Hardware-Release; PipelineModule die Pipeline-Achse.
        // Priorität: aktives Recording → CancelRecording (synchron Effect.ReleaseMediaRecorder + DeleteAudioFile);
        //             sonst pipeline !is Idle → CancelPipeline (PipelineModule-Reducer cleared den State).
        when {
            next.recording.isActiveOrPaused || next.recording is RecordingState.Preparing ->
                cascade.add(Action.RecordingAction.CancelRecording)
            next.pipeline !is PipelineUiState.Idle ->
                cascade.add(Action.PipelineAction.CancelPipeline)
            // else: kein aktives Recording, keine laufende Pipeline → nichts zu cancellen.
        }
    }
    return cascade
}
```

> **Note on the architecture resolution:** Audio-file cleanup + DB `cancelled` status hang on the
> `RecordingModule.reduce(CancelRecording)` path (Effect.DeleteAudioFile) and the
> `PipelineModule.reduce(CancelPipeline)` path (DB status effect), respectively. The C-3 disambiguation (recording before
> pipeline) is symmetric to the Spec-1-§7.3-onDestroy-pre-cancel logic. Architecture decision **3.1.7**
> (PENDING in `plan-review/validated-findings-batch2.md` pre-C-3) is resolved with C-3 F-1 + this C-5 addition.

→ Result: the overlay disappears completely. The pipeline is cancelled. Only when the user opens an input field again does everything start normally.

---

## §7 Mode Transitions

### §7.1 Triangle FSM Logic (canonical in the ViewModeModule, Spec 1 §15)

<!-- FIX: Issue 3.0.3 – Pre-F-11-Header („im PipelineStateManager") auf ViewModeModule (Spec 1 §15) umgestellt. ViewMode-FSM-Eigentum (Doppel-Eigentum-Risiko) ist als Architektur-Decision 3.1.2 PENDING. -->

> **SSoT note:** The ViewMode FSM is canonically anchored in the **ViewModeModule** (Spec 1 §15.1 module-inventory line #4); this section shows the transition logic from Spec 3's perspective as a reference for the implementer. Action source: the IME service dispatches `Action.ViewModeAction.OnImeViewShown / OnImeViewHidden`; the module re-evaluates `computeViewMode`.
>
> <!-- FIX: Phase-B S-8 (2026-05-13) – SSoT-Pfad-Klarstellung. -->
> **Implementation-home clarification:** Spec 1 §15 contains the canonical module implementations
> for RecordingModule (§15.2), AudioModule (§15.3) and KeyboardInputModule (§15.6) as
> examples. The remaining modules — including ViewModeModule — follow the same module pattern
> (`DictateModule<State, Action, Effect>` interface, `reduce`/`runEffect`/`onCrossModuleStateChange`),
> but are not fully printed as code blocks in Spec 1. For ViewModeModule, this section (Spec 3 §7.1)
> provides the `computeViewMode` truth table + the `reduce` skeletons in
> §6.1 (ToggleViewModeWidget), §7.3 T1+T2 (Toggle), T3+T4 (OnImeViewHidden), T5+T6 (OnImeViewShown),
> T7 (OnPipelineDone). These snippets are the concrete implementation anchor for the Block-6
> implementer; Spec 1 §15.1 anchors the module entry in the inventory + the cross-module-coupling-
> matrix lines. There is **no** second source of truth.

<!-- FIX: Phase-C C-5 (2026-05-14) – Reducer-Signatur-Korrektur: ViewModeModule.reduce operiert auf der
     ViewMode-Enum-Sub-State (siehe Spec 1 §15.1 Modul-Inventar-Zeile #4 "viewMode (enum)" + §3 `viewMode:
     ViewMode`-Sub-State-Feld). Daher ist `state: ViewMode` (das Enum-Sub-State), nicht `DictateUiState`.
     Cross-Module-Reads laufen über `ctx.global` (siehe Spec 1 §15.2 RecordingModule-Pattern: `ctx.global.audio.useBluetoothMic`).
     Vorherige Form `state.copy(viewMode = newViewMode)` war Compile-Error (Enum hat kein copy()) und
     verletzte zusätzlich F-11-Modul-Reducer-Sub-State-Vertrag. Auflösung: `nextState = newViewMode` als
     TransitionResult — konsistent mit §6.1 (`nextState = ViewMode.KEYBOARD`-Form, post-S-9). -->
```kotlin
// ViewModeModule.reduce / Cross-Module-Trigger (Spec 1 §15.1):
// Signatur: reduce(state: ViewMode, action: Action.ViewModeAction, ctx: ReducerContext): TransitionResult<ViewMode, Effect>?
when (action) {
    Action.ViewModeAction.OnImeViewShown, Action.ViewModeAction.OnImeViewHidden -> {
        val visible = action is Action.ViewModeAction.OnImeViewShown
        val newViewMode = computeViewMode(
            imeViewVisible = visible,
            userToggledWidget = ctx.global.overlay.userPrefersWidget,
            pipelineActive = ctx.global.pipeline !is PipelineUiState.Idle
                              || ctx.global.recording.isActiveOrPaused,
        )
        if (newViewMode != state) TransitionResult(nextState = newViewMode, sideEffects = emptyList()) else null
    }
    // ...
}

private fun computeViewMode(imeViewVisible: Boolean, userToggledWidget: Boolean, pipelineActive: Boolean): ViewMode {
    return when {
        imeViewVisible && userToggledWidget -> ViewMode.WIDGET
        imeViewVisible && !userToggledWidget -> ViewMode.KEYBOARD
        !imeViewVisible && pipelineActive -> ViewMode.HOVER
        else -> ViewMode.KEYBOARD  // Default — auch wenn nichts da, behalten wir Standard-Layout
    }
}
```

### §7.2 KeyboardLayoutManager Reaction to ViewMode Change

```kotlin
fun onStateChanged(state: DictateUiState) {
    if (state.viewMode != lastViewMode) {
        switchBackend(state.viewMode)
        lastViewMode = state.viewMode
    }
    activeBackend?.render(state, computeLayoutMode(state))
}

private fun switchBackend(target: ViewMode) {
    activeBackend?.detach()
    activeBackend = when (target) {
        ViewMode.KEYBOARD -> imeViewBackend
        ViewMode.WIDGET, ViewMode.HOVER -> overlayBackend
    }
    activeBackend?.attach(onAction)
}
```

### §7.3 Code Sketches per Transition

`userToggledWidget` is a persistence bit that lives in the hierarchical sub-state `state.overlay.userPrefersWidget` (Spec 1 §3 + Phase-1 1.0.6) — see §11.9. <!-- FIX: Issue 3.0.4 – flache DictateUiState.userPrefersWidget → state.overlay.userPrefersWidget -->

#### T1: KEYBOARD → WIDGET (user clicks the widget toggle in the keyboard)

**Trigger:** click on a `widget_toggle_btn` in the IME view (new slot in Spec 2 or in the settings bar).

<!-- FIX: Phase-B S-9 (2026-05-13) – T1/T2-Snippets auf SRP-konforme Cross-Module-Cascade umgestellt.
     Hintergrund (Phase-A Surprise-Finding #3): vorher mutierte ViewModeModule.reduce GLEICHZEITIG
     `viewMode + overlay.onboardingPending` (T1) bzw. `viewMode + layout.smallMode + overlay.userPrefersWidget`
     (T2) — Cross-Axis-Mutation (Mode 3 / Atomic Cross-Axis-Update), die laut Spec 1 §15.5 explizit
     Phase-2-Backlog ist. §6.1 dieses Specs zeigt schon die korrekte Mode-2-Form (Cascade via
     onCrossModuleStateChange in LayoutModule + OverlayModule); §7.3 war eine inkonsistente
     Doppel-Truth-Quelle (zwei Snippets, zwei verschiedene Reducer-Formen für dieselbe Action).
     Auflösung: §7.3 T1+T2 auf §6.1-konsistente Cascade-Form umgestellt. ViewModeModule mutiert
     NUR `viewMode`; Layout/Overlay-Folge-Mutationen sind Mode-2-Cascades. -->
```kotlin
// Im ButtonSlot des Widget-Toggle (Spec 2 ergänzt):
ButtonSlot(LogicalButtonId.WIDGET_TOGGLE, WrapContent,
    visibilityPredicate = { true },
    enabledResolver = { state -> state.recording.isActiveOrPaused
                                  || state.pipeline !is PipelineUiState.Idle },
    actionResolver = { Action.ViewModeAction.ToggleViewModeWidget })

<!-- FIX: Phase-C C-5 (2026-05-14) – Reducer-Signatur-Korrektur (analog §7.1): `state` ist die
     ViewMode-Enum-Sub-State. Cross-Module-Reads (overlay.hasPermission) gehen über `ctx.global`.
     `state.copy(viewMode = ViewMode.WIDGET)` war Compile-Error (Enum hat kein copy()); Auflösung:
     `nextState = ViewMode.WIDGET`. Konsistent mit §6.1 post-S-9. -->
// ViewModeModule.reduce (Spec 1 §15) — mutiert NUR `viewMode` (SRP-konform, Issue 1.1.2 Option A+B):
// Signatur: reduce(state: ViewMode, action: Action.ViewModeAction, ctx: ReducerContext): TransitionResult<ViewMode, Effect>?
when (action) {
    Action.ViewModeAction.ToggleViewModeWidget -> {
        // 1. Permission-Gate prüfen (§5.4) — onboardingPending wird über OverlayModule.reduce
        //    gesetzt, nicht hier; ViewModeModule liest nur den Permission-Status aus
        //    `ctx.global.overlay.hasPermission` (Cross-Module-Read, Coupling-Matrix §15.1.x).
        if (!ctx.global.overlay.hasPermission) {
            // Permission fehlt → kein viewMode-Wechsel; Onboarding-Trigger lebt im Resolver/Effect-Pfad,
            // siehe §5.3 (UI ruft `Action.OverlayAction.MarkOverlayOnboardingShown` separat).
            null  // null = "Action im aktuellen State nicht relevant" (siehe Spec 1 §4.2 reduce-Vertrag)
        } else {
            // 2. State-Mutation: NUR viewMode. `overlay.userPrefersWidget`-Cascade lebt in
            //    OverlayModule.onCrossModuleStateChange (siehe Cascade-Block unten).
            TransitionResult(
                nextState = ViewMode.WIDGET,
                sideEffects = emptyList(),
            )
        }
    }
    // ...
}

// OverlayModule.onCrossModuleStateChange — beobachtet KEYBOARD → WIDGET und setzt userPrefersWidget:
override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> =
    if (prev.viewMode == ViewMode.KEYBOARD && next.viewMode == ViewMode.WIDGET)
        listOf(Action.OverlayAction.SetUserPrefersWidget(true))   // §11.9 Persistenz
    else emptyList()

// KeyboardLayoutManager.onStateChanged() reagiert reaktiv auf state.viewMode:
//   → switchBackend(WIDGET)  → imeViewBackend.detach() + overlayBackend.attach()
//   → render(state, OVERLAY_5BUTTON)
```

**Order:** Action → ViewModeModule.reduce (mutates only viewMode) → state-emit pass 1 →
cross-module observers (OverlayModule cascades `SetUserPrefersWidget(true)`) → recursive dispatch
(depth+1) → OverlayModule.reduce mutates `overlay.userPrefersWidget` → state-emit pass 2 →
KeyboardLayoutManager.onStateChanged → switchBackend → render. There is **no** direct
backend call from the click handler — everything goes through the state pipe.

#### T2: WIDGET → KEYBOARD (user clicks the close button in the widget — with SmallMode)

<!-- FIX: Phase-B S-9 (2026-05-13) – siehe T1-FIX-Block oben (gleicher Refactor: Cross-Axis-Mutation auf
     Mode-2-Cascade umgestellt; LayoutModule + OverlayModule reagieren via onCrossModuleStateChange).
     Cross-Reference: §6.1 zeigt die identische Cascade-Form bereits; §7.3 ist jetzt konsistent. -->
```kotlin
// Im OVERLAY_CLOSE-Slot (Issue 1.1.4 + 2.1.7 / R.3 – nullable Resolver statt Action.NoOp):
actionResolver = { state ->
    when (state.viewMode) {
        ViewMode.WIDGET -> Action.ViewModeAction.ToggleViewModeWidget
        ViewMode.HOVER -> Action.ViewModeAction.CloseOverlay
        else -> null
    }
}

<!-- FIX: Phase-C C-5 (2026-05-14) – Reducer-Signatur-Korrektur: `state` ist die ViewMode-Enum-Sub-State;
     `when (state)` direkt statt `when (state.viewMode)`; `nextState = ViewMode.KEYBOARD` statt
     `state.copy(viewMode = ...)`. Konsistent mit §6.1 + §7.1 post-S-9-Form. -->
// ViewModeModule.reduce — mutiert NUR `viewMode` (SRP-konform, Issue 1.1.2 Option A+B):
// Signatur: reduce(state: ViewMode, action: Action.ViewModeAction, ctx: ReducerContext): TransitionResult<ViewMode, Effect>?
when (action) {
    Action.ViewModeAction.ToggleViewModeWidget -> {
        when (state) {
            ViewMode.WIDGET -> TransitionResult(
                nextState = ViewMode.KEYBOARD,
                sideEffects = emptyList(),
            )
            ViewMode.KEYBOARD -> { /* KEYBOARD → WIDGET — siehe T1 */ null }
            else -> null
        }
    }
}

// LayoutModule.onCrossModuleStateChange — beobachtet WIDGET → KEYBOARD und aktiviert SmallMode:
override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> =
    if (prev.viewMode == ViewMode.WIDGET && next.viewMode == ViewMode.KEYBOARD)
        listOf(Action.LayoutAction.SetSmallMode(true))   // "Tastatur klein" wie vom User gewünscht
    else emptyList()

// OverlayModule.onCrossModuleStateChange — reset userPrefersWidget bei WIDGET → KEYBOARD:
override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> =
    if (prev.viewMode == ViewMode.WIDGET && next.viewMode == ViewMode.KEYBOARD)
        listOf(Action.OverlayAction.SetUserPrefersWidget(false))   // §11.9 Persistenz reset
    else emptyList()
```

**Important:** `userPrefersWidget = false` reset, so that on the next view-hidden event WIDGET does NOT engage again, but HOVER does (via §7.1 logic). Both cascades (LayoutModule + OverlayModule) run recursively via `dispatchInternal(depth+1)`; the cascade-depth counter (Spec 1 §4.3 R.6, cap 8) protects against endless loops.

**Spec-3-internal SSoT:** §6.1 and §7.3 are now consistent — both show the same cascade form (ViewModeModule mutates only `viewMode`; layout/overlay follow-up mutations are Mode-2 cascades). Before Phase-B S-9, §6.1 and §7.3 contradicted each other — §7.3 showed a cross-axis mutation that, per Spec 1 §15.5, is explicitly Phase-2 backlog.

#### T3: KEYBOARD → HOVER (view hidden + pipeline active, was KEYBOARD)

**Trigger:** Android calls `onFinishInputView` in the IME service.

```kotlin
// In DictateInputMethodService — IME ruft den Orchestrator-Dispatch direkt
// (LocalBinder ist auf `state` + `dispatch(action)` reduziert, F-8 Single Dispatch):
// FIX: Issue 3.0.3 + 3.0.5 – Pre-F-8/Pre-F-11-„notifyImeViewHidden / stateManager.notifyImeViewVisibilityChanged"
// auf direkten dispatch + ViewModeModule.reduce umgestellt
override fun onFinishInputView(finishingInput: Boolean) {
    super.onFinishInputView(finishingInput)
    pipeline?.dispatch(Action.ViewModeAction.OnImeViewHidden)
}

<!-- FIX: Phase-C C-5 (2026-05-14) – Reducer-Signatur-Korrektur: Cross-Module-Reads (overlay.userPrefersWidget,
     pipeline, recording) gehen über `ctx.global`; `state` ist die ViewMode-Enum-Sub-State.
     `nextState = newViewMode` statt `state.copy(viewMode = ...)`. -->
// ViewModeModule.reduce (siehe §7.1):
when (action) {
    Action.ViewModeAction.OnImeViewHidden -> {
        val newViewMode = computeViewMode(
            imeViewVisible = false,
            userToggledWidget = ctx.global.overlay.userPrefersWidget,
            pipelineActive = ctx.global.pipeline !is PipelineUiState.Idle || ctx.global.recording.isActiveOrPaused,
        )
        if (newViewMode != state) TransitionResult(nextState = newViewMode, sideEffects = emptyList()) else null
    }
    // ...
}
```

**Concretely:** `visible=false`, `userToggledWidget=false`, `pipelineActive=true` → `HOVER`. The KeyboardLayoutManager switches to the overlayBackend, OVERLAY_5BUTTON is rendered with Send + Record disabled.

#### T4: WIDGET → HOVER (view hidden + pipeline active, was WIDGET)

Identical path to T3, but the **prior state** was WIDGET. The difference:

```kotlin
// notifyImeViewVisibilityChanged-Aufruf, state.overlay.userPrefersWidget=true:
imeViewVisible=false, userToggledWidget=true, pipelineActive=true
  → computeViewMode: !visible && pipelineActive → HOVER
```

**IMPORTANT property:** even if `userPrefersWidget=true`, we switch to HOVER on view-hidden (not to WIDGET-when-view-off, which would be semantically wrong — the InputConnection is dead, Send doesn't work). When made visible again, the widget wish comes back (T6). The WIDGET bit is persisted in `state.overlay.userPrefersWidget`.

#### T5: HOVER → KEYBOARD (view comes back, was NOT WIDGET before)

**Trigger:** Android calls `onStartInputView`.

```kotlin
// In DictateInputMethodService — direkter dispatch (F-8 Single Dispatch):
// FIX: Issue 3.0.3 – Pre-F-8/Pre-F-11-„notifyImeViewShown" auf dispatch umgestellt
override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
    super.onStartInputView(info, restarting)
    pipeline?.dispatch(Action.ViewModeAction.OnImeViewShown)
}

// ViewModeModule.reduce (siehe §7.1):
//   Action.ViewModeAction.OnImeViewShown trifft `state.overlay.userPrefersWidget = false`
//   (vorher KEYBOARD, dann HOVER → User-Bit war false)
//   → computeViewMode(visible=true, userToggledWidget=false) → KEYBOARD
```

#### T6: HOVER → WIDGET (view comes back + was WIDGET — the persistence bit engages)

```kotlin
// ViewModeModule.reduce (siehe §7.1):
// FIX: Issue 3.0.3 – Pre-F-11-Header umgestellt
//   Action.ViewModeAction.OnImeViewShown trifft `state.overlay.userPrefersWidget = true`
//   (gesetzt in T1, NICHT zurückgesetzt)
//   → computeViewMode(visible=true, userToggledWidget=true) → WIDGET
```

**Persistence note:** `state.overlay.userPrefersWidget` must survive in **memory** (StateFlow in the service, which survives the keyboard switch — Spec 1 D1). It does **not** need to land persistently in DB/Prefs, because a new pipeline session sets the widget wish anew anyway. Discussion see §11.9.

<!-- FIX: Phase-B S-8 (2026-05-13) – T7 (Geist-Widget-Bug-Strukturschutz) explizit als Übergang verankert.
     Hintergrund: Phase-A Subsystem-Inventur (Z. 588–591) listet T7 als kritischen Mode-Transition-Test,
     aber §7.3 zeigte nur T1–T6. Lücke: Reader sieht die Geist-Widget-Bug-Auflösung nur indirekt
     über §15.1 Coupling-Matrix (`Pipeline × ViewMode = R(state.pipeline) C(ViewModeAction.OnPipelineDone)`)
     + §10 Acceptance Block 1. Ohne expliziten T7-Block ist die Cascade-Sequenz nicht aus der FSM-Sektion
     selbst ableitbar — der "Geist-Widget"-Begriff (Widget bleibt sichtbar obwohl Pipeline fertig + IME
     hidden) wird in §10 zwar geprüft, aber die FSM-Sicht ist unvollständig. -->
#### T7: HOVER → KEYBOARD via Pipeline-Done cascade (ghost-widget-bug structural protection)

**Trigger:** PipelineModule emits `PipelineUiState.Done` (transcription successfully inserted or failed → final state). The cross-module cascade is anchored in the §15.1 coupling matrix: `Pipeline × ViewMode = R(state.pipeline) C(ViewModeAction.OnPipelineDone)`.

```kotlin
// PipelineModule.onCrossModuleStateChange (Spec 1 §15.x — Pipeline → ViewMode-Cascade):
override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> =
    if (prev.pipeline !is PipelineUiState.Done && next.pipeline is PipelineUiState.Done)
        listOf(Action.ViewModeAction.OnPipelineDone)
    else emptyList()

<!-- FIX: Phase-C C-5 (2026-05-14) – Reducer-Signatur-Korrektur (analog T3/§7.1) + fiktives Feld
     `state.imeViewVisible` aufgelöst. Vorher: das Snippet referenzierte `state.imeViewVisible` als
     gäbe es ein boolesches IME-Visibility-Feld im State — existiert nicht (Spec 1 §3 hat KEIN solches
     Feld). Auflösung: IME-Visibility ist aus dem aktuellen ViewMode ableitbar: HOVER ⇒ IME hidden
     (per Definition); KEYBOARD und WIDGET ⇒ IME visible. Damit `imeViewVisible = state != ViewMode.HOVER`.
     Plus: `state` ist die ViewMode-Enum-Sub-State; Cross-Module-Reads laufen über `ctx.global`. -->
// ViewModeModule.reduce (Spec 1 §15 / Spec 3 §7.1 SSoT-Note):
when (action) {
    Action.ViewModeAction.OnPipelineDone -> {
        // Pipeline ist fertig. Wenn wir in HOVER sind, ist die Auto-Trigger-Bedingung
        // (pipelineActive=true) jetzt false → re-compute viewMode.
        // IME-Visibility wird vom aktuellen ViewMode abgeleitet: HOVER = IME hidden (per Definition,
        // siehe T3/T4-Pfad); KEYBOARD/WIDGET = IME visible. Damit eliminieren wir die Notwendigkeit
        // eines separaten `imeViewVisible`-State-Felds (würde nur Synchronisations-Drift einführen).
        val newViewMode = computeViewMode(
            imeViewVisible = state != ViewMode.HOVER,
            userToggledWidget = ctx.global.overlay.userPrefersWidget,
            pipelineActive = false,                        // post-Done
        )
        if (newViewMode != state) TransitionResult(nextState = newViewMode, sideEffects = emptyList()) else null
    }
    // ...
}
```

**Concretely:** in HOVER (visible=false, pipelineActive=true, possibly userPrefersWidget=false → was HOVER via T3 path) PipelineDone fires. `computeViewMode(visible=false, userToggledWidget=false, pipelineActive=false)` → `KEYBOARD`. The KeyboardLayoutManager switches to `imeViewBackend`, which however is not visible (the IME view is not mounted) — the overlay is torn down, but no new visible UI appears. Correct: the user has closed the IME, the pipeline is done, no UI need anymore.

**Ghost-widget-bug structural protection:** Without the T7 cascade the overlay window would **stay visible** ("ghost widget"), even though the pipeline has lost its auto-trigger reason. T7 is the structural elimination of this bug — the FSM stays deterministically on `KEYBOARD` (no-UI state) after the pipeline-done, not stuck in a ghost HOVER. The acceptance test is anchored in §10 (main doc Acceptance Block 1) + additionally as a cross-module-cascade test in Spec 1 §10 (`pipeline_done_in_hover_cascades_to_keyboard`).

**Variant T7-WIDGET:** when `userPrefersWidget = true` (prior state WIDGET → HOVER via T4), PipelineDone fires identically, but `computeViewMode(visible=false, userToggledWidget=true, pipelineActive=false)` → `KEYBOARD` (not WIDGET — see §7.1 `computeViewMode` truth table: `!visible && !pipelineActive → KEYBOARD`, independent of userPrefersWidget). That is consistent: the widget needs a visible IME or an active pipeline; without either, KEYBOARD (= no visible UI) is the correct idle state.

---

## §8 Touch Routing

`FLAG_NOT_FOCUSABLE` (set in the LayoutParams above): the window receives no keyboard events, but touch events are still processed — buttons respond to clicks.

`FLAG_NOT_TOUCH_MODAL` (set explicitly — §4.4): touches **outside** the buttons go to the underlying app. Since the window only has the size of the 5-button box via `WRAP_CONTENT`, there is no "transparent region around it" — the underlying app receives all touches directly next to and under the widget.

**Edge cases & recommendations:**

| Scenario | Behaviour | Rationale |
|----------|-----------|------------|
| Touch directly on a button | Click responds | Standard. |
| Touch on the padding area INSIDE the window background (between button and border) | Touch is absorbed by the window (no click). | Expected behaviour. If annoying: widen the button padding instead of the container padding. |
| Touch on the underlying app | Passes through to the app | Thanks to `FLAG_NOT_TOUCH_MODAL`. |
| Long-press on a button | Not implemented today. | If needed later: a separate action `Action.OnLongPress*` with `OnLongClickListener`. |
| Drag of the whole box | Not implemented today (see §11.5). | OPEN-3 — possibly later. |

---

## §9 Notification Fallback (permission-free)

Even without `SYSTEM_ALERT_WINDOW` permission, the persistent foreground-service notification (Spec 1 §7) is visible. It is there anyway, because foreground-service is mandatory.

The notification content corresponds to the HOVER layout:
- "Recording in progress" / "Pipeline in progress" / "Ready to insert"
- Action buttons: [Pause] [Cancel] [Send] (max. 3 visible)

**Implication for Spec 3:** the notification backend is NOT in Spec 3 — it belongs to the foreground-service configuration in Spec 1. The notification is always there, with or without an overlay.

---

## §10 Acceptance Criteria

Block 6 (OverlayBackend) is considered done when:

- [ ] Permission onboarding runs on the first widget-toggle attempt.
- [ ] With permission active and a user toggle: the overlay appears with 5 buttons in 2 rows (row 1: Record/Send/Pause; row 2: Trash/Close with Close at bottom right).
- [ ] On view-hidden + recording-active: the HOVER overlay appears automatically with the same layout, Send button + Record button disabled.
- [ ] Close in WIDGET: overlay gone, the IME view comes with SmallMode activation.
- [ ] Close in HOVER: overlay gone, pipeline cancelled, NO new overlay appears until the user explicitly opens+closes the keyboard.
- [ ] The Pause button works in both modes (togglePause).
- [ ] The Send button works in WIDGET (StopRecordingAndSend), is disabled in HOVER.
- [ ] The Trash button works in both modes (cancelRecording).
- [ ] Keyboard switch to Gboard with WIDGET active: the overlay disappears (the IME service dies), but the PipelineService keeps running, recording continues to be captured.
- [ ] Permission denied: the widget toggle is disabled, the HOVER auto-trigger falls back to notification-only.
<!-- FIX: Phase-B S-8 (2026-05-13) – T7 Geist-Widget-Strukturschutz als eigene Acceptance-Klausel. -->
- [ ] **T7 ghost-widget-bug structural protection:** In HOVER (IME hidden + pipeline active) `PipelineUiState.Done` is reached (transcription successful or final-failure). Expectation: the cross-module cascade `PipelineDone → ViewModeAction.OnPipelineDone` (Spec 1 §15.1 Pipeline × ViewMode line) triggers `viewMode = KEYBOARD`; the OverlayBackend is detached; **no ghost widget** stays visible after pipeline-done. Test mandatory via Robolectric `pipelineDoneInHover_transitionsToKeyboard_overlayDetached`.

<!-- FIX: Issue PENDING-3 / Spec-3 Reducer Simplified – Suppress-Bit-Lifecycle-Acceptance -->
**Suppress-bit lifecycle (PENDING-3):**

- [ ] **User closes the HOVER overlay → immediately a new session → the overlay pops up again:**
  During active recording the user dispatches `ViewModeAction.CloseOverlay` in the HOVER
  mode. The `suppressAutoOverlayUntilNextSession` bit is set to `true` (Spec 3
  §4.8 OverlayModule.onCrossModuleStateChange HOVER → KEYBOARD cascade). As long as the
  <!-- FIX: Phase-C C-5 (2026-05-14) – Z.-Ref auf Section-Anchor umgestellt (Anchor-Form, C-1 F-5 Pattern). -->
  bit is `true`, `OverlayBackend.render` suppresses every auto-reopen (see §4.2
  `render` method suppress-bit gate, directly after the permission gate). When the user afterwards starts a **new**
  session via `RecordingAction.StartRecording`, the bit is reset to `false` via the cascade `Idle → Preparing →
  OverlayAction.ResetSuppressBit` (Spec 1 §15.2).
  Expectation: the HOVER overlay appears automatically again on view-hidden during the new session.
- [ ] **Cancel-recording in Preparing triggers NO reset:**
  When the user dispatches `CancelRecording` within the `Preparing` phase
  (Preparing → Idle), the suppress bit stays unchanged at `true`. The boundary test
  in the §15.2 observer covers only the `Idle → Preparing` forward transition.
- [ ] **Pause/Resume within Active triggers NO reset:** Active → Paused →
  Active is not an `Idle → Preparing` boundary, the bit stays unchanged.
- [ ] **ResetSuppressBit is idempotent:** Dispatching the action with
  `suppressBit == false` results in `DispatchOutcome.Applied` (NOT
  `Rejected("reducer-null")`). Verified via the unit test
  `OverlayModuleResetSuppressBitTest.idempotent_whenBitAlreadyFalse_returnsApplied`.

**OPEN-2 (WIDGET self-sufficient, 5-button layout):**

<!-- FIX: Issue 3.1.8 (User-Decision Option A+C) – WIDGET-autark gilt nur in WIDGET-Modus; STANDALONE_OVERLAY ist Phase-2 (Spec 1 §14 Open-Question 6) -->
- [ ] In Idle (no recording, no pipeline) **and in `state.viewMode == WIDGET`**: the Record button in the WIDGET is visible **and** enabled. The user can start a recording from within the WIDGET without first opening the keyboard.
- [ ] **Important:** WIDGET-self-sufficient only applies when the user is actively in WIDGET mode (toggle pressed). In HOVER auto mode (pipeline active + IME hidden) the Record button is disabled — see the T4 constraint.
- [ ] In HOVER: the Record button + Send button are disabled (alpha 0.4, isEnabled=false), because no InputConnection exists as a target.
- [ ] Layout order in row 1: Record left, Send centre (Fill), Pause right. Row 2: Trash left, Close at bottom right (user request).
- [ ] **Out of scope (Phase 2):** STANDALONE_OVERLAY mode (WIDGET without IME toggle, e.g. for foldable outer display) — see Spec 1 §14 Open-Question 6.

<!-- FIX: Issue 3.1.9 (User-Decision Option A) – userPrefersWidget-Persistierung als bewusste UI-Konsistenz -->
**OPEN-2.1 (userPrefersWidget persistence after auto-close):**

- [ ] When the user actively toggles WIDGET (T1: KEYBOARD → WIDGET), `state.overlay.userPrefersWidget = true` stays persistent across mode auto-switches. **Rationale:** UI consistency — the user choice stays valid until it is actively revoked via T2 (WIDGET → KEYBOARD via close button). This is Android convention for persisted UI modes.
- [ ] Acceptance test: open the widget → let the pipeline run → pipeline-done → IME visible again → view-hidden again + new recording trigger. Expectation: WIDGET again, because `userPrefersWidget=true`.

**OPEN-3 (drag + per-orientation persistence):**

- [ ] The widget can be moved via drag (move distance > 8dp triggers drag mode).
- [ ] The position is persisted in SharedPreferences (portrait and landscape separately — `Pref.OverlayPositionPortraitX/Y` and `Pref.OverlayPositionLandscapeX/Y`).
- [ ] On orientation change the orientation-specific position is loaded, the widget jumps to the persisted spot of the new orientation.
- [ ] On a tap (move distance ≤ 8dp) no drag is recognized but a click on the respective button — the OnClickListener fires.
- [ ] On a real drag (move > 8dp) NO click is triggered on ACTION_UP — the buttons' click listeners do not fire.
- [ ] Drag-end persists via `Action.OverlayAction.UpdateOverlayPosition(portrait, x, y)` through the `OverlayModule` reducer/EffectHandler in the `DictateOrchestrator` — not directly from the `OverlayBackend` into SharedPreferences (SSOT). <!-- FIX: Issue 3.0.3 + 3.0.5 – Pre-F-11-„PipelineStateManager.updateOverlayPosition" auf modulare Reducer/EffectHandler umgestellt; flache Action.UpdateOverlayPosition → Action.OverlayAction.UpdateOverlayPosition -->
- [ ] Initial position on first app start: top-end with ~80dp y-offset (default values `1.0f / 0.1f` in DictateUiState).

<!-- FIX: Issue 3.1.5 / R.18 – Drag-Lifecycle-Cluster Acceptance-Tests -->
**OPEN-3.1 (drag lifecycle, R.18):**

- [ ] **Race-update-during-drag test (Robolectric):** a drag starts, an external state update emits an UpdateOverlayPosition with different coordinates — `applyPosition` must early-return, because `dragHandler.isDragging() == true`. Drag-end persists the final user coordinates.
- [ ] **Cross-module-cascade-mid-drag-detach test:** a drag is running, `OverlayBackend.detach()` is called (e.g. via a mode switch to KEYBOARD). DragHandler.detach() persists the current pixel position as normalized coordinates — no "non-persisted" position is lost.
- [ ] **Accessibility-mode touch-slop test:** with the system setting "larger touch slop" (accessibility), `dragThresholdPx = max(8dp, scaledTouchSlop * 1.5)`. Tests verify that with increased slop a deliberate drag is still possible and there is no accidental drag on long-press intentions.

<!-- FIX: Issue 3.1.6 / R.20 – Multi-Display Aspect-Bucket Acceptance -->
**OPEN-3.2 (multi-display + aspect-bucket, 3.1.6):**

- [ ] Foldable test: the position is stored on the inner display, then a switch to the outer display — the position is **not** read from the inner bucket, but from `phone_portrait_*` (the outer has a different aspect bucket).
- [ ] First-render test: the view has `width == 0` on the first `applyPosition` → the set-call is postponed (early-return). `view.post {}` triggers a re-apply with the then-measured view — the position lands correctly.

---

## §11 Research Answers (detail research)

### §11.1 SYSTEM_ALERT_WINDOW in the IME context

**Research result:** IMEs do **not** receive SYSTEM_ALERT_WINDOW automatically. The privilege is an `appop` (`OP_SYSTEM_ALERT_WINDOW`), which is checked independently of the service type via `Settings.canDrawOverlays()`. From the standpoint of permission logic, an IME service is not a privileged context — it has to go through the same user-settings toggle as a normal app.

**Per Android version:**

| API level | Behaviour |
|-----------|-----------|
| < 23 (Android 5 and older) | `SYSTEM_ALERT_WINDOW` is an install-time permission, automatically granted, `Settings.canDrawOverlays()` always returns `true`. |
| 23-25 (Android 6-7) | Special permission, the user must toggle it in Settings → "Display over other apps". `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` opens the settings page. |
| 26+ (Android 8+) | Identical to 23-25, but `TYPE_SYSTEM_ALERT` is deprecated → `TYPE_APPLICATION_OVERLAY` is mandatory. |
| 31+ (Android 12+) | Additional restriction: the system blocks touch events through unsafely overlapping overlays. With our 5-button widget (small, clearly user-initiated) we are not affected. |

**Manufacturer quirks (researched via bug reports):**

- **Xiaomi/MIUI:** an additional proprietary settings toggle "Other permissions → Display in background" — can block independently of `Settings.canDrawOverlays()`. Mitigation: a user FAQ on the repo README, no code hack.
- **Samsung One UI:** standard AOSP behaviour, no quirks known.
- **Huawei EMUI:** similar to Xiaomi, the "Protected apps" list can restrict background activity — affects the foreground service more than the overlay.

**Recommendation:** no special handling in the code — we trust `Settings.canDrawOverlays()`. With manufacturer quirks: the notification fallback (§9) engages anyway, the user sees status even without an overlay.

### §11.2 TYPE_APPLICATION_OVERLAY best practices

**Final setup** (see §4.3, here as a table for verification):

| Attribute | Value |
|----------|------|
| Type | `TYPE_APPLICATION_OVERLAY` (≥ API 26), `TYPE_PHONE` (< API 26) |
| Width / Height | `WRAP_CONTENT` / `WRAP_CONTENT` |
| Flags | `FLAG_NOT_FOCUSABLE \| FLAG_NOT_TOUCH_MODAL \| FLAG_LAYOUT_IN_SCREEN \| FLAG_HARDWARE_ACCELERATED` |
| Format | `PixelFormat.TRANSLUCENT` |
| Gravity | `Gravity.TOP \| Gravity.END` |
| x / y | 16dp / 80dp |
| windowAnimations | 0 (no slide-in) |

**Lock screen:** `TYPE_APPLICATION_OVERLAY` without `FLAG_SHOW_WHEN_LOCKED` does **not** appear on the lock screen. That is what we want (no input possible, so no controls either). If needed later: set `FLAG_SHOW_WHEN_LOCKED`.

**IME + overlay in parallel:**
The WIDGET mode shows the IME view **and** the overlay at the same time — that works because both are separate windows and have different layers (the IME layer is above the app window, the overlay layer is above the IME layer for `TYPE_APPLICATION_OVERLAY`). Verification on a physical device recommended, because layer ordering has historically varied by manufacturer.

### §11.3 Onboarding UX

**Concrete UI strings** — see §5.3.

**When to show?** Only on the **first widget-toggle attempt** (lazy), not prophylactically in the settings screen. Rationale:
- The user clicks the widget actively — the question of permission is contextually motivated.
- A settings-upfront prompt would be an out-of-context question — bad conversion.

**Screen flow:**

```
[KEYBOARD-Modus, Recording läuft]
   User klickt "Widget"-Toggle
        ↓
[IME-View mit InfoBar oben]
"Damit das Floating-Widget über anderen Apps angezeigt werden kann..."
[Später] [Erlauben]
        ↓
   User klickt "Erlauben"
        ↓
[System-Settings-Screen "Über anderen Apps anzeigen"]
   User toggelt zu ON, drückt Back
        ↓
[App-Switcher: User wählt vorherige App / IME wird wieder aufgerufen]
        ↓
[KEYBOARD-Modus, InfoBar weg]
   User klickt "Widget"-Toggle erneut
        ↓
[WIDGET-Modus: Floating-Overlay erscheint]
```

**When the user clicks "Later":** the InfoBar is hidden, `markPermanentlyDenied()` is called → on the next widget-toggle attempt there is no InfoBar anymore, but a silent fallback (notification suffices). The user can enable it manually in the system settings at any time — the next widget-toggle click checks `canDrawOverlays()` again and then engages.

### §11.4 Touch-routing details

**Answer — flag recommendation:** set `FLAG_NOT_TOUCH_MODAL` **explicitly**, even though `FLAG_NOT_FOCUSABLE` activates it implicitly. Rationale: robustness against API changes — AOSP has, in the past, removed the implicit effect once. Better doubly assigned than a bug hunt later.

**What happens in the transparency area between buttons:**
We have **no transparency area** in the classical sense — the window has `WRAP_CONTENT`, the background is opaque (Material surface with rounded corners). Touches on the container padding area are caught by the LinearLayout but not forwarded — no click, but also no pass-through. That is acceptable because the padding area is only ~6dp wide.

**Preventing accidental clicks on the underlying app:**
Since the window is small (220x110dp) and in the corner (top-end, 16dp/80dp offset), it lies outside the typical content areas. User clicks on their own content go past it. If an overlay button is accidentally clicked anyway: yes, that is the user intention (it is our UI after all).

### §11.5 Drag functionality (OPEN-3) — **in scope for Block 6**

**Decision (user, 2026-05):** drag is **Phase 1, Block 6**. Persistence separated
per orientation, in normalized 0..1 coordinates, via a dedicated `OverlayDragHandler`
class (see §4.6). The position computation runs through `OverlayPositionMapper` (§4.7).

#### §11.5.1 OnTouchListener — pseudo-code (complete)

The implementation lives in `DefaultOverlayDragHandler` (§4.6). For easier review here
is the pseudo-code flow without class boilerplate:

```kotlin
state {
    initialTouchX, initialTouchY: Float            // raw screen-coords beim ACTION_DOWN
    initialParamsX, initialParamsY: Int            // params.x/y beim ACTION_DOWN
    dragging: Boolean = false
    val dragThresholdPx = 8 * density              // ~8dp
}

onTouch(rootView, event):
    when event.action:
        ACTION_DOWN:
            initialTouchX = event.rawX
            initialTouchY = event.rawY
            initialParamsX = params.x
            initialParamsY = params.y
            dragging = false
            return false                            // Buttons sehen DOWN für Ripple

        ACTION_MOVE:
            dx = event.rawX - initialTouchX
            dy = event.rawY - initialTouchY
            if (!dragging && hypot(dx, dy) > dragThresholdPx):
                dragging = true                     // Drag-Modus aktiv

            if dragging:
                params.x = initialParamsX + dx.toInt()
                params.y = initialParamsY + dy.toInt()
                window.update(view, params)
                return true                         // konsumiert
            else:
                return false                        // weiter an Buttons

        ACTION_UP, ACTION_CANCEL:
            if dragging:
                (normX, normY) = positionMapper.pixelsToNormalized(params.x, params.y, view)
                onAction(Action.OverlayAction.UpdateOverlayPosition(portrait, normX, normY))  // FIX: Issue 3.0.5 – flach → hierarchisch
                dragging = false
                return true                         // Click unterdrücken — war Drag
            else:
                return false                        // Tap → Buttons machen Click
```

#### §11.5.2 Click-vs-drag differentiation

| Scenario | Move distance | Behaviour |
|----------|---------------|-----------|
| Tap on a button | 0..8dp drift | `dragging=false`, ACTION_UP returns `false` → the button OnClickListener fires. |
| Slow drag starts slowly | exceeds 8dp at some point | As soon as `dragging=true`, the touch is consumed. ACTION_UP returns `true` → no click. |
| Fast tap | <8dp drift, fast UP | like a tap → click. |
| Long-press on a button | <8dp drift | like a tap (DOWN not consumed → the button's long-press detector engages). |

The threshold is defined in `dp` (display-density independent). 8dp is more generous than
the system touch-slop (typically 8dp at 2x density, i.e. ~16px), deliberately — a widget at
the screen edge is often reached with the thumb, which makes a slight roll motion.

#### §11.5.3 Position computation in pixel coordinates

During a drag (ACTION_MOVE), absolute pixels are used:

```
params.x = initialParamsX + (event.rawX - initialTouchX).toInt()
params.y = initialParamsY + (event.rawY - initialTouchY).toInt()
```

`gravity` is `TOP|START` (see `OverlayBackend.applyPosition`), so that `params.x` counts from
the left display corner and `params.y` from the top display corner — additive drag math
works correctly. Boundary clamping does **not** happen in the DragHandler — the
WindowManager system clamps automatically to the visible area (FLAG_LAYOUT_NO_LIMITS
is `NO`, see §4.4). When the user drags beyond the edge, the overlay hangs at
the edge; on UP the clamped position is normalized.

#### §11.5.4 Normalization 0..1 before persistence

On ACTION_UP, `OverlayPositionMapper.pixelsToNormalized` (§4.7) computes:

```
maxX = (screenW - viewW).coerceAtLeast(1)
maxY = (screenH - viewH).coerceAtLeast(1)
normX = (params.x / maxX).coerceIn(0f, 1f)
normY = (params.y / maxY).coerceIn(0f, 1f)
```

`maxX/maxY` is the "free area" — the range in which the top-left corner of the widget
can legitimately reside (otherwise it would partially lie off-screen). `normX=1` means
"the top-left corner is as far right as possible, so that the widget is just barely
fully visible" — that corresponds to the default `1.0f` (top-end anchor, right edge).

The `Action.OverlayAction.UpdateOverlayPosition(portrait, normX, normY)` runs via the
`onAction(...)` callback through `KeyboardLayoutManager` to `DictateOrchestrator.dispatch`. The
`OverlayModule` reducer mutates the state (via the sub-state axis `state.overlay.*`); the
`OverlayModule` EffectHandler does the pref write atomically (Spec 1 §15 + §6.4). <!-- FIX: Issue 3.0.3 + 3.0.5 – Pre-F-11-„PipelineStateManager" auf modulare Architektur umgestellt -->

#### §11.5.5 De-normalization on render

`OverlayBackend.applyPosition(state)` (§4.2) calls on every render call:

```
isPortrait = config.orientation == ORIENTATION_PORTRAIT
(normX, normY) = if isPortrait
    state.overlay.positionPortraitX/Y
else
    state.overlay.positionLandscapeX/Y
(px, py) = positionMapper.normalizedToPixels(normX, normY, view)
params.x = px; params.y = py; params.gravity = TOP|START
overlayWindow.update(view, params)
```

Since `applyPosition` only triggers an `update` when values have actually changed
(comparison against `currentParams`), the state-driven setting is idempotent and cheap.

#### §11.5.6 Orientation-change handling

Which pref value is read depends on the **current** `Configuration.orientation`.
Concrete sequence:

1. The user rotates the device → the system triggers `onConfigurationChanged` in the `IME service` and
   the `PipelineService`.
2. The next state-emit (e.g. because of a `recovery` reread or `notifyImeViewVisibilityChanged`)
   leads to the `render(state)` in the backend.
3. `applyPosition` reads the now-current orientation and takes the matching pref value.
4. The widget jumps to the persisted position of the new orientation.

**Important:** a drag in portrait persists ONLY `OverlayPositionPortrait*`, a drag in landscape
ONLY `OverlayPositionLandscape*` — both default values (1.0/0.1) are initially identical, but
independent per orientation from the first drag onward.

#### §11.5.7 Optional: snap-to-edge — recommendation with rationale

**Recommendation: NO, do not implement initially.** Rationale:

- **User wish:** In the open-question block the user marked "just stay at the user position"
  as the default. Snap would override that.
- **Sustainability:** snap-to-edge is additive logic in the DragHandler (`ACTION_UP` →
  compute distance to each edge → jump to the nearest). It can be added at any time as a
  decorator class `SnappingOverlayDragHandler` without breaking the default
  behaviour — OCP.
- **UX risk:** snap can feel unexpected when the user deliberately chooses a mid-screen
  position (e.g. to keep a certain UI region free). Pure free-drag respects
  the user choice 1:1.

If snap is wanted later: decorator pattern, a flag `Pref.OverlaySnapToEdge: Boolean`,
the DragHandler factory chooses between `Default` and `Snapping`. Decision-point trigger:
user feedback "the widget slips when tapping". Until then: not in scope.

#### §11.5.8 Listener attachment

`view.setOnTouchListener(dragHandler.touchListener)` on the **root view** of the overlay XML
(`@id/overlay_root`). Click listeners on the individual buttons still engage, because:
- On a tap (no drag): `OnTouchListener.onTouch` returns `false` → the touch event passes
  through to the child (button) → its `OnClickListener` fires.
- On a drag: the listener consumes the stream from the move threshold → buttons see no
  ACTION_UP → no click is triggered.

Persistence logic: NOT directly into SharedPreferences from the DragHandler (would
violate the single-source-of-truth rule — all mutations go through `dispatch(action)` and
the module reducer/EffectHandler, Spec 1 §4.3 + §15). Instead via
`Action.OverlayAction.UpdateOverlayPosition` to the `DictateOrchestrator` (Spec 1 §6.4 + §13.2.3 + OverlayModule). <!-- FIX: Issue 3.0.3 + 3.0.5 – Pre-F-11-„PipelineStateManager" + flache Action auf modulare Architektur umgestellt -->

### §11.6 Window-lifecycle edge cases

| Scenario | Behaviour |
|----------|-----------|
<!-- FIX: Phase-B S-8 (2026-05-13) – Tabelle aktualisiert: alle drei WindowManager-Race-Pfade jetzt im Wrapper. -->
| `windowManager.addView` throws `BadTokenException` (permission revoked at runtime) | Catch in `AndroidOverlayWindow.attach()` (§4.1, Phase-B S-8), log, `attached = false`, no crash. The backend checks via `overlayWindow.isAttached() == false` and treats this like a normal attach-failure. On the next `render()` the `state.overlay.hasPermission` check at the top of `render()` pushes the code into the fallback path. |
| `windowManager.updateViewLayout` throws `IllegalArgumentException` (the view was already OS-side detached, e.g. after a permission revoke at runtime) | Catch in `AndroidOverlayWindow.update()` (§4.1, Phase-B S-8), log, `attached = false`. On the next `render()` the backend attempts a clean re-attach, which bails out early via the gate check if permission is missing. |
| `windowManager.removeView` throws `IllegalArgumentException` (the view was not attached) | Catch in `AndroidOverlayWindow.detach()` — idempotent. |
| `OverlayBackend.detach()` while `inflateAndAttach()` is running | Theoretically a race: `addView` runs on the main thread, `detach` runs on the main thread — no real race. But: when `attach`/`detach` are triggered via StateFlow emits, they can come quickly one after another. Idempotency is the solution: the `attached` bit in the wrapper, `if (attached) return` in `attach`. |
| PipelineService `onDestroy()` while the overlay is attached | KeyboardLayoutManager.detachBackend() is called by the IME service beforehand → `OverlayBackend.detach()` → `removeView`. If not (e.g. crash): the system cleans up the window automatically on process death. |
| Permission is revoked in system settings while the overlay is visible | Android sends NO broadcast. On the next `render()` call (StateFlow emit) we notice nothing at the state level, because `OverlayPermissionObserver.refresh()` only runs at an IME lifecycle trigger (`onStartInputView`/`onCreateInputView`). But: if the user triggers an `applyPosition` in the meantime (drag event, state change), the OS-side detach would be cleanly caught via the `updateViewLayout` `IllegalArgumentException` in the wrapper — no crash gap (Phase-B S-8 F-1). The edge case is acceptable because it is very rare + non-blocking. |

### §11.7 Multi-window mode

| Scenario | Behaviour |
|----------|-----------|
| Split-screen | `TYPE_APPLICATION_OVERLAY` is rendered OVER both split halves — as in fullscreen. The position stays top-end of the physical display. Acceptable; if annoying in practice (e.g. the overlay covers the action bar of the top app), `WindowMetrics`-aware positioning can be added later. |
| Picture-in-picture (PiP) | The PiP window of the underlying app is small, the overlay stays in top-end. No conflict. |
| Free-form window (e.g. ChromeOS, Samsung DeX) | `TYPE_APPLICATION_OVERLAY` is global to the display, not app-bound — stays top-end of the display independent of the app window. Acceptable. |

**Recommendation:** no explicit multi-window code in Phase 1. If conspicuous in telemetry, add later as an extension. |

### §11.8 Notification fallback

**Moved out of Spec 3.** The notification is the responsibility of the foreground-service configuration in **Spec 1 §7**. This spec does not implement the notification; it only references that the notification is visible anyway and suffices as a status indicator in the permission-denial path.

### §11.9 WIDGET in Idle (OPEN-2)

**Question:** Should the WIDGET toggle be disabled when no pipeline is active?

**Decision (user, 2026-05): NO — WIDGET is self-sufficient.** Rationale:
- With the additional Record button (5-button layout) the user can start a recording directly from within the widget — even without an active pipeline. The widget is **capable of acting on its own**.
- The Record button has `visibilityPredicate = { state -> state.recording.isActiveOrPaused.not() && state.pipeline is PipelineUiState.Idle }` (visible in Idle) and `enabledResolver = { state -> state.viewMode == ViewMode.WIDGET }` (disabled in HOVER, because without an InputConnection the result could not be written anywhere).
- Send/Pause/Trash are still only enabled when a recording or a pipeline is running — the widget is therefore context-aware.

**Consequence:** the WIDGET toggle (in the IME view) is always enabled once permission is present. There is no longer an "empty WIDGET" state — Idle-WIDGET has an active Record button as an entry point.

**Persistence bit `state.overlay.userPrefersWidget`:**

The bit lives in the DictateUiState as a sub-state axis (`OverlayState.userPrefersWidget`, Spec 1 §3) — in-memory in the PipelineService. Mutation: <!-- FIX: Issue 3.0.4 – flache state.userPrefersWidget → state.overlay.userPrefersWidget (hierarchische Sub-State-Pfade aus Phase-1 1.0.6) -->
- `Action.ViewModeAction.ToggleViewModeWidget` in WIDGET: sets `false` (the user actively closes the widget).
- `Action.ViewModeAction.ToggleViewModeWidget` in KEYBOARD: sets `true` (the user actively opens the widget).
- `Action.ViewModeAction.CloseOverlay` in HOVER: stays unchanged (HOVER is auto, no user-wish reset).
- Service restart (OOM recovery): the bit is `false` by default. Rationale: after process death every session starts afresh — the widget wish of the past session is not guaranteed to still be wanted.

**NO prefs persistence** (across sessions). If requested in user tests: later optionally as a setting "widget active by default" — but not Phase 1.

<!-- FIX: Issue PENDING-3 / Spec-3 Reducer Simplified – Spiegel-Eintrag für Suppress-Bit -->
<!-- KG-RSB-1 RESOLVED 2026-05-11: Boot-Default `false` ist bewusst transient, dokumentiert hier. Auflösung lebt in Spec 1 §15.2 (Marker-Block). -->
**Persistence bit `state.overlay.suppressAutoOverlayUntilNextSession` (PENDING-3, KG-RSB-1 RESOLVED 2026-05-11):**

Analogous to `userPrefersWidget`, the suppress bit also lives as a sub-state axis
(`OverlayState.suppressAutoOverlayUntilNextSession`, Spec 1 §3) — in-memory
in the PipelineService. Mutation:
- `Action.ViewModeAction.CloseOverlay` in HOVER → `OverlayModule.onCrossModuleStateChange`
  cascades `OverlayAction.SuppressAutoOverlayUntilNextSession` (sets `true`).
- `Action.RecordingAction.StartRecording` (Idle → Preparing) →
  `RecordingModule.onCrossModuleStateChange` cascades `OverlayAction.ResetSuppressBit`
  (sets `false`).
- Service restart (OOM recovery): the bit is `false` by default. Rationale
  identical to `userPrefersWidget` — after process death no active
  recording session is in flight anymore, the suppress contract ("prevent auto-
  reopen for *this* session") is moot.

**NO prefs persistence, no DB mirror.** Rationale as above.

---

## §12 References

### Phase-2 research (input material)

- [_pending-ime-lifecycle-view-recreation.md](../_pending-ime-lifecycle-view-recreation/_pending-ime-lifecycle-view-recreation.md) — IME lifecycle, view-recreate path. Relevant for the backend switch.

### Code pointers (today)

This subsystem is **completely new** — no existing classes are migrated. Code pointers only emerge through the implementation.

### External references

- TYPE_APPLICATION_OVERLAY: https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY
- SYSTEM_ALERT_WINDOW Permission: https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW
- Settings.canDrawOverlays: https://developer.android.com/reference/android/provider/Settings#canDrawOverlays(android.content.Context)
- IME + Overlay Compatibility (StackOverflow): https://stackoverflow.com/questions/tagged/android-input-method
- Android 12 Touch-Pass-Through-Restrictions: https://developer.android.com/about/versions/12/behavior-changes-all#untrusted-touch-events
- WindowManager.LayoutParams Reference: https://developer.android.com/reference/android/view/WindowManager.LayoutParams
- Floating Windows tutorial series (Localazy): https://medium.com/localazy/5-floating-windows-on-android-moving-window-103f8dff37c5
- DraggableView library (inspiration for §11.5): https://github.com/hyuwah/DraggableView

### ADRs (Block-0 artefacts, bidirectional)

- [ADR-0003 — service-foreground-pipeline-architecture](../../../../decisions/0003-service-foreground-pipeline-architecture.md) — structural prerequisite for HOVER (the FGS survives IME-service death); binds §11.6 (window lifecycle), §4.x (the OverlayBackend hangs on the DictatePipelineService).
- [ADR-0004 — ui-layout-catalog-motionlayout](../../../../decisions/0004-ui-layout-catalog-motionlayout.md) — binds §3.1 (OVERLAY_5BUTTON LayoutMode), §4 (OverlayBackend implements RenderBackend), the shared `applySlotToView` helper.
- [ADR-0005 — ui-triangle-fsm-keyboard-widget-hover](../../../../decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md) — binds §6 (close-button differential), §7.1 (computeViewMode truth table), §7.3 (T1–T7 transitions), §11.9 (userPrefersWidget transience).

### Architecture docs (Block-0 artefacts, teaching/explanatory)

- [`docs/architecture/state-architecture/triangle-fsm.md`](../../../../architecture/state-architecture/triangle-fsm.md) — KEYBOARD/WIDGET/HOVER, T1–T7, ghost-widget structural protection.
- [`docs/architecture/state-architecture/rendering.md`](../../../../architecture/state-architecture/rendering.md) — OverlayBackend as a RenderBackend implementation.

---

## §13 Completeness Verification

This section documents how the architecture fulfils the user requirements "complete centralization of state and functionality, with consistent SOLID/DRY application". It serves as an audit track — on every later change it is cross-checked here whether the central properties still hold.

### §13.1 SSOT conformance

**Claim:** the `OverlayBackend` does NO own state mutation and does NOT decide itself when it is active.

**Proof by code inspection** (§4.2):

| Code location | What it does | SSOT-conformant? |
|-------------|------------|---------------|
| `attach(onAction)` | stores the action callback, no state touch | yes |
| `detach()` | tears down the window, no state touch | yes |
| `render(state, mode)` | reads `state` (read-only), sets view properties, forwards click → `onAction` | yes — does not write into state |
| `applySlots(state, mode)` | iterates slots, evaluates resolvers, sets view properties | yes — `state` is a read-only parameter |
| Click listener | invokes `onAction(slot.actionResolver(state))` | yes — the action goes via `DictateOrchestrator.dispatch` to the responsible module, which runs the reducer + side-effects (Spec 1 §4.3 + §15) <!-- FIX: Issue 3.0.3 – Pre-F-11-„PipelineStateManager" auf modular --> |

**Who decides when the backend is active?**
The `KeyboardLayoutManager` (§7.2) reads `state.viewMode` and calls `switchBackend()`. The OverlayBackend is `attach`-ed by the manager, not by itself.

**Who mutates `viewMode`?**
Exclusively the `ViewModeModule` (Spec 1 §15) — action routing happens via `DictateOrchestrator.dispatch`. Action sources:
- User click in the IME → `onAction(Action.ViewModeAction.ToggleViewModeWidget)` → orchestrator → ViewModeModule.reduce.
- IME lifecycle (`onStartInputView` / `onFinishInputView`) → `pipeline?.dispatch(Action.ViewModeAction.OnImeViewShown / OnImeViewHidden)` → ViewModeModule.reduce.
- User click in the overlay → `onAction(Action.ViewModeAction.ToggleViewModeWidget | CloseOverlay)` → orchestrator → ViewModeModule.reduce.

<!-- FIX: Issue 3.0.3 – Pre-F-11-„PipelineStateManager" + Pre-F-8-„notifyImeViewVisibilityChanged" auf modulare Architektur (DictateOrchestrator + ViewModeModule + Single-Dispatch via dispatch(action)) umgestellt -->

**Consequence:** no mutation on any path **bypassing the StateManager**. The overlay is a pure renderer + action forwarder. ✓

### §13.2 SOLID verification

#### SRP (Single Responsibility)

| Class | Responsibility | Single? |
|--------|-------------------|---------|
| `OverlayBackend` | Render of the OVERLAY_5BUTTON LayoutMode in a window + position apply | yes — no pipeline logic, no state mutation, no permission onboarding, no touch routing (delegated to the DragHandler). |
| `AndroidOverlayWindow` | Wrapper for `WindowManager.addView/removeView/updateViewLayout` | yes — encapsulates only window operations. |
| `OverlayPermissionGate` | Permission status + onboarding persistence | yes — no UI, no render. |
| `DefaultOverlayLayoutParamsFactory` | Creates `WindowManager.LayoutParams` | yes — a pure factory, no state. |
| `DefaultOverlayDragHandler` (OPEN-3) | Touch routing: drag-vs-click differentiation + window update during move + persist callback on UP | yes — knows only touch events + window update; knows nothing about state, pref keys, action types. |
| `DefaultOverlayPositionMapper` (OPEN-3) | Conversion 0..1 ↔ pixel | yes — pure math, no side effect. |

**Where are the responsibilities composed?** In the constructor (DI) of the `OverlayBackend`. The service layer (Spec 1) instantiates the components and wires them. That is the composition root, not a compositional responsibility in the backend itself.

#### OCP (Open/Closed)

Extending with new overlay modes (e.g. a 6-button overlay) requires:
- a new `LayoutMode` in the `LayoutCatalog` (Spec 2 §3.5)
- a new ID in `LayoutModeId`
- possibly a new `LogicalButtonId` entry

NO change to the `OverlayBackend` necessary — it iterates generically over slots. ✓

#### LSP (Liskov Substitution)

`AndroidOverlayWindow` is an `OverlayWindow`. A `FakeOverlayWindow` (tests) is also an `OverlayWindow`. The backend behaviour is independent of the concrete implementation — the pre-/post-conditions (`isAttached`, `attach`, `detach`) are precise in the interface. ✓

#### ISP (Interface Segregation)

`RenderBackend` (Spec 2 §5) is 3 methods — minimal. `OverlayWindow` is 4 methods — minimal. No client has to implement methods it does not need. ✓

#### DIP (Dependency Inversion)

`OverlayBackend` depends on `OverlayWindow` (interface) and `OverlayPermissionGate` (interface), not on `WindowManager` directly. That means:
- Tests can inject `FakeOverlayWindow`.
- Tests can inject `FakePermissionGate`.
- A refactor of the window mechanism (e.g. later to `Compose-for-Window`) does not change the interface — the backend stays invariant.

**Concrete proof in the constructor (§4.2):**
```kotlin
class OverlayBackend(
    private val ctx: Context,
    private val overlayWindow: OverlayWindow,        // ← Interface, nicht WindowManager
    private val permissions: OverlayPermissionGate,  // ← Interface, nicht SharedPreferences
    private val layoutParamsFactory: OverlayLayoutParamsFactory = ...,  // ← Interface
    private val dragHandlerFactory: OverlayDragHandlerFactory = ...,    // ← Interface (OPEN-3)
    private val positionMapper: OverlayPositionMapper = ...,            // ← Interface (OPEN-3)
)
```
✓

### §13.3 DRY verification

#### Layout-mode sharing for WIDGET and HOVER

**Claim:** `OVERLAY_5BUTTON` is ONE definition, used by both modes.

**Proof** (§3.1):
- `OverlayBackend.render()` is called with `mode = LayoutCatalog.OVERLAY_5BUTTON` — regardless of whether `state.viewMode == WIDGET` or `HOVER`.
- KeyboardLayoutManager.computeLayoutMode (Spec 2 §4):
  ```kotlin
  ViewMode.WIDGET, ViewMode.HOVER -> LayoutCatalog.OVERLAY_5BUTTON  // selber Wert
  ```
- The Send/Record-button difference (enabled in WIDGET, disabled in HOVER) lives in the **resolver**:
  ```kotlin
  enabledResolver = { state -> state.viewMode == ViewMode.WIDGET && state.recording.isActiveOrPaused }
  ```
  NO duplicated layout block. ✓

#### Close-button action differentiation

**Claim:** the close button has **one** slot definition; the differential behaviour lives in the `actionResolver`.

**Proof** (§3.1):
```kotlin
<!-- FIX: Issue 1.1.4 + 2.1.7 / R.3 – nullable Resolver statt Action.NoOp -->
ButtonSlot(LogicalButtonId.OVERLAY_CLOSE, ...,
    actionResolver = { state ->
        when (state.viewMode) {
            ViewMode.WIDGET -> Action.ViewModeAction.ToggleViewModeWidget
            ViewMode.HOVER -> Action.ViewModeAction.CloseOverlay
            else -> null
        }
    })
```
NO second button view, NO backend-specific click-listener block. ✓

#### Click-listener setup

**Claim:** the click-listener setup is uniform per slot, no backend-specific routing.

**Proof** (§4.2 `applySlots()`):
```kotlin
<!-- FIX: Issue 1.1.4 / R.3 – Click-Listener nutzt nullable-Resolver-Idiom -->
<!-- FIX: Phase-B S-7 (2026-05-13) – 2-arg Resolver (state, services). -->
view.setOnClickListener { slot.actionResolver(state, services)?.let { onAction?.invoke(it) } }
```
One line, identical for all 5 slots (Record, Send, Pause, Trash, Close). No special cases. ✓ — the same pattern as in the `ImeViewBackend` (Spec 2 §6).

#### Slot → view-property mapping (F-7 / iteration 2026-05-08)

**Claim:** The property-setter logic (visibility/enabled/alpha/icon/text) does
NOT live duplicated in every backend, but in **one** top-level function that
both backends call.

**Proof:**
- Spec 2 §5.1 defines `fun applySlotToView(slot, view, state, ctx): Boolean`.
- ImeViewBackend (§6 in Spec 2) calls `applySlotToView(slot, view, state, ctx)` from its `render` path.
- OverlayBackend (§4.2 above) calls the same helper from `applySlots()`.

```kotlin
// Beide Backends:
mode.rows.flatMap { it.slots }.forEach { slot ->
    val view = buttonViews[slot.logicalId] ?: return@forEach
    applySlotToView(slot, view, state, ctx)   // ← geteilte Funktion
    // Click-Listener: backend-spezifisch (static im IME, pro Render im Overlay).
}
```

**Consequence:** When the property mapping is extended by `contentDescription` or `tint`,
there is **one** place (`applySlotToView` in Spec 2 §5.1) where
the setter pattern lives. Both backends inherit automatically — drift structurally
impossible. ✓

#### Position conversion 0..1 ↔ pixel (OPEN-3)

**Claim:** The conversion between normalized 0..1 coordinates and absolute
pixels exists only in ONE place.

**Proof:** `OverlayPositionMapper` (§4.7) holds both directions (`normalizedToPixels`,
`pixelsToNormalized`). Consumers:
- `OverlayBackend.applyPosition` calls `normalizedToPixels` (state → pixel on render).
- `DefaultOverlayDragHandler` calls `pixelsToNormalized` (drag-end → state).

NO second implementation in the DragHandler or the backend. When the formula changes
(e.g. an anchor-point change), exactly one place. ✓

#### Position persistence (OPEN-3)

**Claim:** persistence of the overlay position runs only through the `OverlayModule` (Spec 1 §15) in the `DictateOrchestrator`.

**Proof:**
- `DefaultOverlayDragHandler` does NOT write into SharedPreferences. On UP it only calls
  `onPositionPersist(normX, normY)`, which in the `OverlayBackend` becomes
  `onAction(Action.OverlayAction.UpdateOverlayPosition(...))`.
- The `OverlayModule` reducer mutates the sub-state `state.overlay.position{Portrait|Landscape}{X|Y}`; the coupled EffectHandler writes `Pref.OverlayPosition*X/Y` atomically (Spec 1 §6.4 + §15).
- The settings activity does not write these prefs (they are drag-only). If a
  "Reset Overlay Position" button is wanted later, it also goes via
  `Action.OverlayAction.UpdateOverlayPosition(portrait, 1.0f, 0.1f)` — the same action, no code duplicate. ✓ <!-- FIX: Issue 3.0.3 + 3.0.5 – Pre-F-11-Naming auf modulare Architektur umgestellt -->

#### Permission logic

**Claim:** the permission status exists as **one** state axis and is kept synchronized by **one** observer.

<!-- FIX: Phase-C C-5 (2026-05-14) – Post-Issue-3.1.3-Form: Permission ist State-Achse, nicht Live-Read.
     Vor Issue 3.1.3 wurde `OverlayPermissionGate.hasOverlayPermission()` (= `Settings.canDrawOverlays()`)
     bei jedem Render frisch gelesen; post-3.1.3 lebt der Status in `state.overlay.hasPermission`, vom
     `OverlayPermissionObserver` (§5.0) synchron gehalten. Damit ist der Reducer pure (R.2). -->
**Proof:**
- `state.overlay.hasPermission` is the SoT state axis (Spec 1 §3 OverlayState; Issue 3.1.3 Option A).
- `OverlayPermissionObserver.refresh()` (§5.0) is the **only** live source for `Settings.canDrawOverlays()` —
  called from IME-`onCreateInputView` / `onStartInputView` (lifecycle trigger, no polling).
- Consumers read exclusively `state.overlay.hasPermission`:
  - `ViewModeModule.reduce(Action.ViewModeAction.ToggleViewModeWidget)` via `ctx.global.overlay.hasPermission` (§7.3 T1).
  - `OverlayBackend.render()` via `state.overlay.hasPermission` (§4.2, defensive gate).
  - `OverlayPermissionGate.hasOverlayPermission()` (§5.1) wraps `Settings.canDrawOverlays()` for non-reducer
    consumers (e.g. the onboarding trigger in the IME-view path) — but is **not** the reducer live-read path.

No duplicated `Settings.canDrawOverlays()` call in the reducer path (R.2 pure-reducer guarantee). ✓ <!-- FIX: Issue 3.0.3 – Pre-F-11-„PipelineStateManager.toggleViewMode" auf modular umgestellt -->

### §13.4 Cross-spec consistency

#### Does the OverlayBackend use the same `RenderBackend` interface as the ImeViewBackend?

**Answer: YES.** Spec 2 §5 defines:
```kotlin
interface RenderBackend {
    fun attach(onAction: (Action) -> Unit)
    fun detach()
    fun render(state: DictateUiState, mode: LayoutMode)
}
```
`OverlayBackend` (§4.2) implements exactly this interface. The `KeyboardLayoutManager` works polymorphically with `RenderBackend` references, no `instanceof` check. ✓

#### Are the same slot/action/resolver patterns used?

**Answer: YES, and since F-7 even literally the same code.** Comparison after F-7 consolidation:

<!-- FIX: Phase-B S-8 (2026-05-13) – Click-Listener-Spalte aktualisiert. Vorher zeigte die Tabelle
     einen "pro Render"-Listener im Overlay (Pre-Issue-3.1.10), aber §4.2-Code zeigt seit Issue 3.1.10
     `wireStaticOverlayHandlers` einmal-pro-inflate mit `stateRef`/`modeRef`-Feldern — identisch zum
     IME-Pattern. Tabelle war veraltete Doppel-Truth gegen §4.2; jetzt konsistent. -->
| Pattern | ImeViewBackend (Spec 2) | OverlayBackend (Spec 3) | Source |
|---------|--------------------------|--------------------------|--------|
| Slot iteration | `mode.rows.flatMap { it.slots }.forEach { ... }` | identical | both render methods |
| Visibility/Enabled/Alpha/Icon/Text | **`applySlotToView(slot, view, state, ctx)`** | **`applySlotToView(slot, view, state, ctx)`** | **Top-level helper in Spec 2 §5.1 (F-7)** |
| Click | static in `wireStaticHandlers` (state-snapshot via `stateRef`/`modeRef` field) | **identical** — static in `wireStaticOverlayHandlers` (state-snapshot via `stateRef`/`modeRef` field, Issue 3.1.10 / Phase-B S-8) | Spec 2 §6 + Spec 3 §4.2 — both backends now run with the same one-time-listener pattern; the drag-routing conflict in the overlay is resolved via the touch listener on the root view (the click listeners only fire if the drag handler stays below the 8dp threshold), not via variable click-listener hooks |

**Consistency proof:** With F-7 the property-setter pattern is identical — one definition, two callers. GAP-1 (`.foreground` vs `.icon` inconsistency) is thereby obsolete, because both backends run through the same helper, which uses `.icon` consistently. With Issue 3.1.10 (Spec-2 pattern carried over to the overlay) the click-listener setup is also identical — no pattern drift between the backends.

#### Are the same `Action` types used?

**Answer: YES.** Spec 2 §3.3 defines a sealed `Action` hierarchy (one inner sealed class per module axis, F-8 + F-11). Both backends invoke `onAction(Action)` — the `DictateOrchestrator` (composition root, Spec 1 §4.3) routes the action via a `KClass<A>` lookup to the responsible module, whose reducer holds the `when` block over the inner sealed class. ✓ <!-- FIX: Issue 3.0.3 – Pre-F-11-„PipelineStateManager mit ONE Switch-Case-Block" auf Modular-Routing umgestellt -->

### §13.5 Identified Gaps + Mitigations

<!-- FIX: Issue 3.0.7 – §13.5 in drei Bereiche getrennt (Open / Cross-Spec-Pending / Resolved); Audit-Funktion wieder klar. FIX: Issue 3.0.5 – flache Action-Refs in GAP-2 auf hierarchische umgestellt. -->

#### §13.5.a Open Gaps

(Currently no Spec-3-internal open gaps. GAP-5 + GAP-6 + GAP-8 are documented accept-cases — see §13.5.c.)

#### §13.5.b Cross-Spec Patches Pending

(All known cross-spec patches were reconciled against Spec 1 + Spec 2 in Phase-1-Apply (1.0.5/1.0.6) and Phase-2-Batch-2 (3.0.4/3.0.5/3.0.12) respectively. Remaining: the WIDGET_TOGGLE slot position in the Spec-2 LayoutMode — see Spec 2 §13.5.b. Responsible: the Block-4 implementer.)

#### §13.5.c Resolved (iter-history)

| ID | Gap | Status | Resolution |
|----|-----|--------|-----------|
| **GAP-1** | Spec 2 §6 originally used `MaterialButton.foreground = drawable` for icons; Spec 3 §4.2 used `MaterialButton.icon = drawable`. | RESOLVED via F-7 | Both backends call the shared top-level helper `applySlotToView` (Spec 2 §5.1), which uses `.icon` consistently for MaterialButton icons. |
| **GAP-2** | `Action.OverlayAction.MarkOverlayOnboardingShown` / `Action.OverlayAction.DismissOverlayOnboarding` (mentioned in §5.3) were not yet listed hierarchically in the Spec-2-§3.3 Action sealed class. | RESOLVED via Phase-1 1.0.5 + Phase-2-Batch-2 3.0.5 | Spec 2 §3.3 contains the hierarchical `Action.OverlayAction` sealed class with both variants + `UpdateOverlayPosition`. Spec 3 §5.3 + §13 uses the hierarchical naming consistently. |
| **GAP-3** | `state.overlay.onboardingPending` (§5.3, §5.4) and `state.overlay.userPrefersWidget` (§7.3, §11.9) are new fields in the `DictateUiState`. | RESOLVED via F-10 | `OverlayState` is modelled as a sub-state class in Spec 1 §3; all relevant fields (`positionPortraitX/Y`, `positionLandscapeX/Y`, `onboardingPending`, `userPrefersWidget`, possibly `permissionMissingForHover`) are defined there. |
| **GAP-4** | `LogicalButtonId.WIDGET_TOGGLE` (§7.3 T1) was not yet listed in Spec 2 §3.1 — the toggle button in the IME view had to be added as a slot. | RESOLVED via Phase-1 1.0.2 + Phase-2-Batch-2 3.0.12 | Spec 2 §3.1 + §13.1 + §13.2 + §6 buttonViews map contain `WIDGET_TOGGLE`. The layout-slot position stays `§13.5.b` pending (see above). |
| **GAP-5** | In the T6 path ("HOVER → WIDGET") a subtle edge case with `userPrefersWidget` persistence after closing HOVER. | Accepted (deliberate property) | Consistent with the persistence semantics ("WIDGET is the user-chosen mode that comes back by default"). Not a bug — documented in §11.9. |
| **GAP-6** | A permission revoke at runtime triggers NO broadcast; the overlay stays visible until the next `render()` call. | Accepted (rare) | No code hack; documented in §11.6. Polling overengineered for Phase 1. |
| **GAP-7** (OPEN-3) | The view size `view.width/height` is still `0` on the first `applyPosition` (the layout pass has not yet run). | RESOLVED via F-6 | `inflateAndAttach` sets a `view.post { applyPosition(stateRef) }` hook; the callback fires after the first layout pass and re-applies the position. |
| **GAP-8** (OPEN-3) | The display size via `ctx.resources.displayMetrics` possibly deviates from the WindowMetrics API in multi-window/split-screen. | Accepted | The overlay is `TYPE_APPLICATION_OVERLAY` (display-global), `displayMetrics` is the correct source. Multi-window-aware positioning addable via DI in `OverlayPositionMapper` if needed. |

---

## §14 Test Strategy

### §14.1 Unit tests (JVM, without Android runtime)

**Test targets:**
- `OverlayBackend` with `FakeOverlayWindow`, `FakePermissionGate`, `FakeLayoutParamsFactory`.
- `DefaultOverlayPermissionGate` with in-memory `SharedPreferences` (Robolectric or test double).
- `LayoutCatalog.OVERLAY_5BUTTON` resolvers: property tests against all DictateUiState combinations (recording × pipeline × viewMode).

**Example test:**

```kotlin
class OverlayBackendTest {
    private val fakeWindow = FakeOverlayWindow()
    private val fakePerms = FakePermissionGate(hasPermission = true)
    private val backend = OverlayBackend(ctx, fakeWindow, fakePerms, FakeLayoutParamsFactory())

    @Test
    fun `render does not attach when permission missing`() {
        fakePerms.hasPermission = false
        backend.attach { }
        backend.render(stateWithRecording(), LayoutCatalog.OVERLAY_5BUTTON)
        assertFalse(fakeWindow.isAttached())
    }

    @Test
    fun `render attaches and binds buttons when permission granted`() {
        backend.attach { }
        backend.render(stateWithRecording(), LayoutCatalog.OVERLAY_5BUTTON)
        assertTrue(fakeWindow.isAttached())
    }

    @Test
    fun `detach removes view idempotently`() {
        backend.attach { }
        backend.render(stateWithRecording(), LayoutCatalog.OVERLAY_5BUTTON)
        backend.detach()
        backend.detach()  // zweites detach: kein Crash, idempotent
        assertFalse(fakeWindow.isAttached())
    }

    @Test
    fun `send button is disabled in HOVER mode`() {
        var lastAction: Action? = null
        backend.attach { lastAction = it }
        val state = stateWithRecording().copy(viewMode = ViewMode.HOVER)
        backend.render(state, LayoutCatalog.OVERLAY_5BUTTON)
        val sendBtn = fakeWindow.lastAttachedView!!.findViewById<View>(R.id.overlay_send_btn)
        assertFalse(sendBtn.isEnabled)
    }

    @Test
    fun `close button in WIDGET emits ToggleViewModeWidget action`() { /* ... */ }
    @Test
    fun `close button in HOVER emits CloseOverlay action`() { /* ... */ }
}
```

**Drag-specific tests (OPEN-3):**

```kotlin
class DefaultOverlayPositionMapperTest {
    private val mapper = DefaultOverlayPositionMapper(ctx)
    private val view = mockView(width = 200, height = 100)
    // ctx liefert displayMetrics 1000x2000

    @Test
    fun `normalized 1,0 maps to far right edge`() {
        // freie Fläche X = 1000-200 = 800; Y = 2000-100 = 1900
        assertEquals(800 to 0, mapper.normalizedToPixels(1f, 0f, view))
    }

    @Test
    fun `normalized 0,0 maps to top-left`() {
        assertEquals(0 to 0, mapper.normalizedToPixels(0f, 0f, view))
    }

    @Test
    fun `pixels round-trip through normalization`() {
        val (nx, ny) = mapper.pixelsToNormalized(400, 950, view)
        assertEquals(0.5f, nx, 0.001f)
        assertEquals(0.5f, ny, 0.001f)
        // round-trip back
        assertEquals(400 to 950, mapper.normalizedToPixels(nx, ny, view))
    }

    @Test
    fun `pixelsToNormalized clamps out-of-bounds values`() {
        val (nx, ny) = mapper.pixelsToNormalized(2000, 5000, view)
        assertEquals(1f, nx, 0.001f)
        assertEquals(1f, ny, 0.001f)
    }
}

class DefaultOverlayDragHandlerTest {
    // Move-Distance < 8dp → kein Drag, ACTION_UP retourniert false → Click feuert.
    @Test
    fun `tap below threshold does not enter drag mode`() { /* ... */ }

    // Move-Distance > 8dp → Drag-Modus, ACTION_UP retourniert true, kein Click.
    @Test
    fun `move beyond threshold enters drag mode and consumes up`() { /* ... */ }

    // ACTION_UP nach Drag emittiert UpdateOverlayPosition mit normalisierten Koordinaten.
    @Test
    fun `drag end persists normalized position via callback`() {
        var captured: Pair<Float, Float>? = null
        val handler = DefaultOverlayDragHandler(
            ctx, view, fakeWindow,
            paramsHolder = { fakeParams },
            positionMapper = DefaultOverlayPositionMapper(ctx),
            onPositionPersist = { x, y -> captured = x to y },
        )
        handler.attach()
        // simuliere DOWN → MOVE > 8dp → UP
        // ...
        assertNotNull(captured)
    }
}
```

<!-- FIX: Issue PENDING-3 / Spec-3 Reducer Simplified – Suppress-Bit-Test-Skelette -->
**Suppress-bit tests (PENDING-3) — reducer + cross-module cascade:**

These tests verify the ResetSuppressBit mechanic specified in Spec 1 §15.2 + Spec 3 §4.8.
They are the executable form of the suppress-bit
acceptance list in §10. The test classes live in
`app/src/test/java/.../state/modules/`.

```kotlin
// File: OverlayModuleResetSuppressBitTest.kt — Reducer-Verhalten
class OverlayModuleResetSuppressBitTest {

    private val ctx = ReducerContext(global = DictateUiState.initial())

    @Test
    fun `resetSuppressBit clears the bit when previously true`() {
        val state = OverlayState(suppressAutoOverlayUntilNextSession = true)
        val result = OverlayModule.reduce(state, Action.OverlayAction.ResetSuppressBit, ctx)
        assertNotNull(result)
        assertFalse(result!!.nextState.suppressAutoOverlayUntilNextSession)
        assertTrue(result.sideEffects.isEmpty())
    }

    @Test
    fun `resetSuppressBit is idempotent when bit is already false`() {
        // §4.8 Reducer-Kommentar: idempotent → liefert TransitionResult, NICHT null.
        // Begründung: DispatchOutcome.Rejected("reducer-null") wäre semantisch falsch
        // für einen Session-Start-Marker.
        val state = OverlayState(suppressAutoOverlayUntilNextSession = false)
        val result = OverlayModule.reduce(state, Action.OverlayAction.ResetSuppressBit, ctx)
        assertNotNull(result)
        assertFalse(result!!.nextState.suppressAutoOverlayUntilNextSession)
    }

    @Test
    fun `resetSuppressBit preserves other overlay fields`() {
        val state = OverlayState(
            suppressAutoOverlayUntilNextSession = true,
            userPrefersWidget = true,
            hasPermission = true,
            positionPortraitX = 0.42f,
        )
        val result = OverlayModule.reduce(state, Action.OverlayAction.ResetSuppressBit, ctx)!!
        // Nur das Suppress-Bit wird gemutiert.
        assertEquals(true, result.nextState.userPrefersWidget)
        assertEquals(true, result.nextState.hasPermission)
        assertEquals(0.42f, result.nextState.positionPortraitX, 1e-6f)
    }
}
```

```kotlin
// File: RecordingModuleResetSuppressBitTest.kt — Cross-Module-Cascade
class RecordingModuleResetSuppressBitTest {

    @Test
    fun `idleToPreparing emits ResetSuppressBit cascade`() {
        val prev = DictateUiState.initial()    // recording = Idle
        val next = prev.copy(
            recording = RecordingState.Preparing(
                useBluetooth = false,
                audioFile = java.io.File("/tmp/test.m4a"),
            ),
        )
        val cascade = RecordingModule.onCrossModuleStateChange(prev, next)
        assertTrue(
            "Idle → Preparing must emit ResetSuppressBit, got: $cascade",
            cascade.contains(Action.OverlayAction.ResetSuppressBit),
        )
    }

    @Test
    fun `cancelRecording in preparing does NOT emit ResetSuppressBit`() {
        // Preparing → Idle ist KEIN Boundary für den Reset (siehe §15.2-Observer).
        val preparing = RecordingState.Preparing(useBluetooth = false, audioFile = java.io.File("/tmp/x"))
        val prev = DictateUiState.initial().copy(recording = preparing)
        val next = prev.copy(recording = RecordingState.Idle)
        val cascade = RecordingModule.onCrossModuleStateChange(prev, next)
        assertFalse(cascade.contains(Action.OverlayAction.ResetSuppressBit))
    }

    @Test
    fun `pauseResume in active does NOT emit ResetSuppressBit`() {
        // Active → Paused und Paused → Active sind keine Idle → Preparing-Boundary.
        val active = RecordingState.Active(useBluetooth = false, audioFile = java.io.File("/tmp/x"))
        val paused = RecordingState.Paused(useBluetooth = false, audioFile = java.io.File("/tmp/x"))
        val base = DictateUiState.initial()

        val activeToPaused = RecordingModule.onCrossModuleStateChange(
            base.copy(recording = active),
            base.copy(recording = paused),
        )
        val pausedToActive = RecordingModule.onCrossModuleStateChange(
            base.copy(recording = paused),
            base.copy(recording = active),
        )

        assertFalse(activeToPaused.contains(Action.OverlayAction.ResetSuppressBit))
        assertFalse(pausedToActive.contains(Action.OverlayAction.ResetSuppressBit))
    }

    @Test
    fun `preparingToActive does NOT emit ResetSuppressBit`() {
        // MediaRecorderReady-Boundary: kein Reset (nur Idle → Preparing).
        val preparing = RecordingState.Preparing(useBluetooth = false, audioFile = java.io.File("/tmp/x"))
        val active = RecordingState.Active(useBluetooth = false, audioFile = java.io.File("/tmp/x"))
        val base = DictateUiState.initial()
        val cascade = RecordingModule.onCrossModuleStateChange(
            base.copy(recording = preparing),
            base.copy(recording = active),
        )
        assertFalse(cascade.contains(Action.OverlayAction.ResetSuppressBit))
    }
}
```

```kotlin
// File: CrossModuleResetSuppressBitIntegrationTest.kt — End-to-End-Cascade
// (Lebt in CrossModuleCascadeTest.kt aus Spec 1 §10 Acceptance, hier als
//  zusätzlicher Testfall.)
class CrossModuleResetSuppressBitIntegrationTest {

    @Test
    fun `startRecording resets suppress bit via orchestrator cascade`() {
        // Vorbedingung: User hat HOVER-Overlay geschlossen → Bit ist true.
        val store = DictateUiStateStore(
            DictateUiState.initial().let { it.copy(
                overlay = it.overlay.copy(suppressAutoOverlayUntilNextSession = true),
            ) }
        )
        val orchestrator = DictateOrchestrator(
            scope = TestScope(),
            store = store,
            servicesFactory = FakeServicesFactory(),
            prefMirror = FakePrefMirror(),
            recovery = FakeRecovery(),
        )

        val outcome = orchestrator.dispatch(
            Action.RecordingAction.StartRecording(
                target = InsertionTarget.IME,
                audioFile = java.io.File("/tmp/test.m4a"),
            ),
        )
        assertTrue(outcome is DispatchOutcome.Applied)
        // Nach dem Cascade-Pass ist das Bit clearend (KG-RSB-2: hängt davon ab,
        // ob §4.3 Step 5 Self-Filter aktiv ist — der Test KLÄRT KG-RSB-2 empirisch:
        // wenn rot → Self-Filter blockiert die Cascade → Auflösung (A) anwenden).
        assertFalse(store.snapshot.overlay.suppressAutoOverlayUntilNextSession)
    }
}
```

### §14.2 Espresso/instrumentation tests (permission flow, mock system settings)

```kotlin
@Test
fun overlayPermissionOnboarding_grantPath() {
    // 1. Permission ist initial nicht gegeben (FakePermissionGate.hasPermission=false).
    // 2. User klickt Widget-Toggle → InfoBar wird angezeigt (verifiziert via View-Visibility).
    // 3. User klickt "Erlauben" → Settings-Intent wird gestartet (via Intents-Recorder).
    // 4. Mock: Permission ist jetzt true (FakePermissionGate.hasPermission=true).
    // 5. User klickt Widget-Toggle erneut → Overlay wird attach-ed.

    onView(withId(R.id.widget_toggle_btn)).perform(click())
    onView(withId(R.id.overlay_permission_infobar)).check(matches(isDisplayed()))
    intending(hasAction(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)).respondWith(...)
    onView(withId(R.id.overlay_perm_grant_btn)).perform(click())
    intended(hasAction(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))

    fakePerms.hasPermission = true
    onView(withId(R.id.widget_toggle_btn)).perform(click())
    assertTrue(fakeWindow.isAttached())
}

@Test
fun overlayPermissionOnboarding_denyThenStillAccessible() {
    // 1. User klickt Widget-Toggle → InfoBar.
    // 2. User klickt "Später" → markPermanentlyDenied(), InfoBar geht weg.
    // 3. Nächster Widget-Klick: KEIN InfoBar mehr (silent fallback).
    // 4. Aber: erneuter Klick öffnet trotzdem Settings (Komfort-Pfad).
}
```

### §14.3 Manual test plan: mode transitions

| # | Transition | Steps | Expected result |
|---|----------|----------|---------------------|
| T1 | KEYBOARD → WIDGET | Start recording, click the widget toggle | The overlay appears top-end, the IME view stays visible |
| T2 | WIDGET → KEYBOARD (with SmallMode) | Click the close button in WIDGET | The overlay disappears, the IME view becomes compact (SmallMode) |
| T3 | KEYBOARD → HOVER | Start recording, close the keyboard (back button) | The IME disappears, the HOVER overlay appears with Send disabled |
| T4 | WIDGET → HOVER | In WIDGET, switch apps so that the IME view is hidden | The overlay layout stays visible, but Send disabled (mode switch to HOVER) |
| T5 | HOVER → KEYBOARD | Tap an input field in an app (the IME is reopened) | The HOVER overlay disappears, the IME view comes with a normal keyboard |
| T6 | HOVER → WIDGET | (Prior state: WIDGET → HOVER via T4). Tap an input field | The HOVER overlay disappears, the IME view comes in KEYBOARD mode, the widget toggle is highlighted (or: the overlay stays + the IME appears, depending on the implementation of §7.3 T6) |
<!-- FIX: Phase-B S-8 (2026-05-13) – T7 Manual-Test-Eintrag. -->
| T7 | HOVER → KEYBOARD via PipelineDone | Start recording → close the keyboard (back) → HOVER appears → wait for pipeline-done | The HOVER overlay disappears automatically (no "ghost widget"), no visible UI stays active |

### §14.4 Sandbox/device tests for the WindowManager setup

| Test | On | Goal |
|------|-----|------|
| Overlay layer-order | physical device + emulator (API 26, 30, 34) | The overlay lies above the keyboard window AND above apps. Multiple manufacturers (Samsung, Pixel, Xiaomi if available). |
| Touch pass-through | physical device | Touches outside the widget pass through to the app (`FLAG_NOT_TOUCH_MODAL` works). |
| Multi-window split-screen | Pixel, Galaxy | The overlay stays visible, position consistent. |
| PiP | Pixel | The overlay above the PiP window, no Z conflict. |
| Lock screen | Pixel | The overlay does NOT appear on the lock screen (without FLAG_SHOW_WHEN_LOCKED). |
| Permission revoke at runtime | Emulator | The overlay stays visible until the next render(); no crash. |
| Hardware-keyboard connect | Pixel + USB keyboard | Keyboard input goes to the app, NOT to our overlay (FLAG_NOT_FOCUSABLE works). |

**Sandbox script** (for a gradle-managed AVD):
```bash
./gradlew :app:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.devemperor.dictate.overlay.OverlayWindowSetupTest
```
