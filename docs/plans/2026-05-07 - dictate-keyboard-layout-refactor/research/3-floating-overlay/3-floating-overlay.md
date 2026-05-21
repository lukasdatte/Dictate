# Spec 3 — Floating-Overlay (WIDGET + HOVER): OverlayBackend + Window-Lifecycle + Permission

**Status:** **Superseded by [ADR-0008](../../../../decisions/0008-ui-surface-axes-widget-state-and-ime-view.md) (2026-05-22)**. The Triangle-FSM model (KEYBOARD/WIDGET/HOVER) is replaced by two orthogonal surface axes: [WidgetState] (Hidden | Visible(USER) | Visible(PIPELINE)) and [imeViewVisible]. The plan that drives the migration is [`docs/plans/2026-05-21 - dictate-widget-state-and-recovery/dictate-widget-state-and-recovery.md`](../../../../plans/2026-05-21%20-%20dictate-widget-state-and-recovery/dictate-widget-state-and-recovery.md). Document retained as historical context for the OverlayBackend window-lifecycle + permission-flow details, which remain valid under the new axes.

Previously: Detail-Research erweitert (2026-05-08) — Architektur fixiert, Implementierungs-Details ausgearbeitet.
**Hauptplan:** [→ keyboard-layout-refactor.md](../../keyboard-layout-refactor.md)
**Geschwister-Specs:**
- [Spec 1 — Pipeline-Service-Layer](../1-pipeline-service/1-pipeline-service.md)
- [Spec 2 — KEYBOARD-Layout (IME-View)](../2-keyboard-layout/2-keyboard-layout.md)

---

## §1 Kontext und Scope

Diese Spec beschreibt das **Floating-Overlay-Subsystem** — die zwei Modi WIDGET und HOVER, die als separates Window über anderen Apps angezeigt werden. Sie umfasst:

- **`OverlayBackend`**: das Backend, das beide Modi auf einem `TYPE_APPLICATION_OVERLAY`-Window rendert.
- **Window-Lifecycle**: Create, Attach, Detach, Destroy via `WindowManager`.
- **Permission-Flow**: `SYSTEM_ALERT_WINDOW`-Erlaubnis, Onboarding-UI, Fallback bei Verweigerung.
- **Mode-Transitionen**: KEYBOARD ↔ WIDGET, KEYBOARD/WIDGET → HOVER (auto), HOVER → KEYBOARD (auto).
- **Schließen-Button-Differentialverhalten**: in WIDGET = Transition zu KEYBOARD, in HOVER = dismiss.
- **Touch-Routing**: Buttons im Overlay sind klickbar, Hintergrund ist transparent (oder fest).

Out-of-Scope (anderer Spec):
- KEYBOARD-Modus-Rendering, MotionLayout, IME-View — siehe Spec 2.
- Pipeline-State-Mutation, Service-Lifecycle — siehe Spec 1.

---

## §2 Architektur-Entscheidungen (fixiert)

| # | Entscheidung | Begründung |
|---|--------------|------------|
| O1 | **`TYPE_APPLICATION_OVERLAY`** als Window-Typ | Standard für floating-over-other-apps auf Android 8+. `TYPE_SYSTEM_ALERT` ist seit API 26 deprecated. |
| O2 | **`SYSTEM_ALERT_WINDOW`-Permission** notwendig | Vom System verlangt für TYPE_APPLICATION_OVERLAY. User muss in Settings explizit umschalten. |
| O3 | **Gemeinsames 5-Button-Layout** für WIDGET und HOVER (OPEN-2) | Layout ist identisch — Record + Send sind in HOVER **disabled** (kein InputConnection-Target). WIDGET wird damit autark (User kann Aufnahme aus dem Widget heraus starten). |
| O4 | **Schließen-Button Differential**: in WIDGET → transitionTo(KEYBOARD) (mit SmallMode-Aktivierung); in HOVER → dismiss-only. | Vom User entschieden. |
| O5 | **Eigene View-Instanzen** im Overlay-Window (nicht geteilt mit IME-View) | Android-Hard-Constraint: ein View kann nur in einem Window leben. |
| O6 | **Drag + per-Orientation-Persistierung** (OPEN-3) | Position wird normalisiert (0..1) in SharedPreferences geschrieben, getrennt für Portrait und Landscape. Click-vs-Drag via 8dp-Threshold. Default Top-End mit ~80dp y-Offset. Snap-to-Edge zunächst NICHT (Begründung in §11.5.7). |
| O7 | **Notification-Fallback** bei Permission-Verweigerung | Foreground-Service-Notification ist ohnehin da (Spec 1) — User sieht Status auch ohne Overlay. |
| O8 | **Window-Lifecycle gemanaged vom OverlayBackend selbst** | Nicht vom IME-Service direkt — der Manager triggert nur "render im OVERLAY-Modus", das Backend kümmert sich um WindowManager-Calls. |

---

## §3 Layout-Mode-Definition (gemeinsam für WIDGET und HOVER)

### §3.1 LayoutMode-Datenstruktur

```kotlin
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
                actionResolver = { Action.StartRecording }),
            ButtonSlot(LogicalButtonId.OVERLAY_SEND, FillRemaining,
                visibilityPredicate = { true },
                enabledResolver = { state -> state.viewMode == ViewMode.WIDGET
                                              && state.recording.isActiveOrPaused },
                alphaResolver = { state ->
                    if (state.viewMode == ViewMode.WIDGET && state.recording.isActiveOrPaused) 1f
                    else 0.4f
                },
                textResolver = { state -> resolveOverlaySendText(state) },
                actionResolver = { Action.StopRecordingAndSend }),
            ButtonSlot(LogicalButtonId.OVERLAY_PAUSE, WrapContent,
                visibilityPredicate = { true },
                enabledResolver = { state -> state.recording.isActiveOrPaused },
                iconResolver = { state ->
                    if (state.recording is RecordingState.Paused) R.drawable.ic_baseline_mic_24
                    else R.drawable.ic_baseline_pause_24
                },
                actionResolver = { state ->
                    if (state.recording is RecordingState.Paused) Action.ResumeRecording
                    else Action.PauseRecording
                }),
        )),
        // Reihe 2: Trash + Schließen (Schließen unten rechts wie vom User gewünscht)
        RowDescriptor(slots = listOf(
            ButtonSlot(LogicalButtonId.OVERLAY_TRASH, WrapContent,
                visibilityPredicate = { state -> state.recording.isActiveOrPaused
                                                || state.pipeline !is PipelineUiState.Idle },
                actionResolver = { Action.CancelRecording }),
            ButtonSlot(LogicalButtonId.OVERLAY_CLOSE, WrapContent,
                visibilityPredicate = { true },
                actionResolver = { state ->
                    when (state.viewMode) {
                        ViewMode.WIDGET -> Action.ToggleViewModeWidget  // → KEYBOARD mit SmallMode
                        ViewMode.HOVER -> Action.CloseOverlay              // dismiss-only
                        else -> Action.NoOp
                    }
                }),
        )),
    ),
)
```

**Schlüsselbeobachtung:** ein einziger `LayoutMode` deckt beide ViewMode-Varianten ab. Die Differenzen leben in den Resolvern, die `state.viewMode` lesen und sich entsprechend verhalten — insbesondere disablen Record + Send in HOVER, weil keine InputConnection als Ziel existiert (OPEN-2).

### §3.2 Konkretes Overlay-XML-Layout

Datei: `app/src/main/res/layout/overlay_5button_layout.xml`

Layout-Struktur: zwei Reihen mit 3+2 Spalten.
- Reihe 1: `[Record] [Send] [Pause]`
- Reihe 2: `[Trash] [Schließen]` — Schließen unten rechts (User-Wunsch)

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

**Hintergrund-Drawable** `res/drawable/overlay_background.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="?attr/colorSurface" />
    <corners android:radius="16dp" />
    <stroke android:width="1dp" android:color="?attr/colorOutlineVariant" />
</shape>
```

**Style-Definitionen** (in `res/values/styles_overlay.xml`):

```xml
<style name="OverlayButton.Primary" parent="Widget.Material3.Button">
    <item name="cornerRadius">12dp</item>
    <item name="android:textAllCaps">false</item>
</style>

<style name="OverlayButton.Icon" parent="Widget.Material3.Button.IconButton">
    <item name="cornerRadius">12dp</item>
</style>
```

**Resultierende Größe** (gemessen): ca. 270-310 dp Breite, 110 dp Höhe (zwei 48dp-Reihen + 6dp Gap + 12dp Padding; Reihe 1 hat drei 48dp-Buttons + 6dp-Gaps + Send-FillRemaining mit minWidth=100dp). Kompakt, passt in jede Bildschirm-Ecke ohne Inhalt zu verdecken.

**ID-Kontrakt** (von §4.4-Code referenziert):

| ID | Slot |
|----|------|
| `overlay_record_btn` | `LogicalButtonId.OVERLAY_RECORD` |
| `overlay_send_btn` | `LogicalButtonId.OVERLAY_SEND` |
| `overlay_pause_btn` | `LogicalButtonId.OVERLAY_PAUSE` |
| `overlay_trash_btn` | `LogicalButtonId.OVERLAY_TRASH` |
| `overlay_close_btn` | `LogicalButtonId.OVERLAY_CLOSE` |

---

## §4 OverlayBackend — API + Implementierung

### §4.1 Backend-Klasse mit gekapseltem Window-Manager-Wrapper (DIP)

Damit das Backend nicht hart an `android.view.WindowManager` hängt (DIP, testbar), wird die Window-Verwaltung in einen schmalen Wrapper extrahiert:

```kotlin
interface OverlayWindow {
    fun isAttached(): Boolean
    fun attach(view: View, params: WindowManager.LayoutParams)
    fun update(view: View, params: WindowManager.LayoutParams)
    fun detach(view: View)
}

class AndroidOverlayWindow(
    private val windowManager: WindowManager,
) : OverlayWindow {
    private var attached = false
    override fun isAttached() = attached
    override fun attach(view: View, params: WindowManager.LayoutParams) {
        if (attached) return
        windowManager.addView(view, params)
        attached = true
    }
    override fun update(view: View, params: WindowManager.LayoutParams) {
        if (attached) windowManager.updateViewLayout(view, params)
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

Dadurch kann ein `FakeOverlayWindow` in Tests einfach den Status mitschneiden, ohne reale `WindowManager`-Calls zu emulieren.

### §4.2 OverlayBackend (überarbeitet)

`OverlayBackend` delegiert Drag-Handling an einen eigenständigen `OverlayDragHandler`
(§4.6) und Position-Berechnung an `OverlayPositionMapper` (§4.7) — beide via Konstruktor
injiziert (DIP). Damit bleibt SRP gewahrt: Backend macht Render, DragHandler macht
Touch-Routing, PositionMapper macht 0..1 ↔ Pixel-Konversion.

```kotlin
class OverlayBackend(
    private val ctx: Context,
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
    /** State-Snapshot — wird von `view.post`-Callback (F-6 / GAP-7) gelesen. */
    private var stateRef: DictateUiState? = null

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

        if (!permissions.hasOverlayPermission()) {
            // Fallback-Pfad (§5.4): kein Overlay zeigen, Notification reicht.
            teardownOverlay()
            return
        }
        stateRef = state
        if (overlayView == null) inflateAndAttach()

        applySlots(state, mode)
        applyPosition(state)
    }

    /**
     * Slot → View-Properties über den geteilten Top-Level-Helper `applySlotToView`
     * aus Spec 2 §5.1 (F-7). Click-Listener werden pro Render gesetzt — nicht wie
     * im ImeViewBackend einmalig static, weil das Overlay keine Drag-Routing-
     * Konflikte hat und der State zur Click-Zeit aus dem Closure gelesen wird.
     */
    private fun applySlots(state: DictateUiState, mode: LayoutMode) {
        mode.rows.flatMap { it.slots }.forEach { slot ->
            val view = buttonViews[slot.logicalId] ?: return@forEach
            applySlotToView(slot, view, state, ctx)   // (F-7) geteilter Helper
            view.setOnClickListener { onAction?.invoke(slot.actionResolver(state)) }
        }
    }

    /**
     * Setzt die Window-Position auf die im State persistierte Position für die aktuelle
     * Orientation (OPEN-3). Der State hält normalisierte 0..1-Koordinaten; der
     * `positionMapper` konvertiert sie in absolute Pixel auf Basis der aktuellen
     * Display-Größe. Wird von `render()` bei JEDEM State-Update aufgerufen — billig,
     * weil `windowManager.updateViewLayout` no-op ist, wenn die Params unverändert sind
     * (wir merken uns die letzten Params und vergleichen).
     */
    private fun applyPosition(state: DictateUiState) {
        val view = overlayView ?: return
        val params = currentParams ?: return
        val isPortrait = ctx.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val (normX, normY) = if (isPortrait) {
            state.overlayPositionPortraitX to state.overlayPositionPortraitY
        } else {
            state.overlayPositionLandscapeX to state.overlayPositionLandscapeY
        }
        val (px, py) = positionMapper.normalizedToPixels(normX, normY, view)
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
        try {
            overlayWindow.attach(view, params)
            overlayView = view
            currentParams = params
            // Drag-Handling: OnTouchListener auf Root-View. Move > Threshold → Drag-Modus,
            // Tap < Threshold → Click an Button durchreichen. Drag-End emittiert
            // Action.UpdateOverlayPosition mit normalisierten 0..1-Koordinaten.
            dragHandler = dragHandlerFactory.create(
                view = view,
                window = overlayWindow,
                paramsHolder = { currentParams },
                positionMapper = positionMapper,
                onPositionPersist = { normX, normY ->
                    val portrait = ctx.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                    onAction?.invoke(Action.UpdateOverlayPosition(portrait, normX, normY))
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
        } catch (e: WindowManager.BadTokenException) {
            // Permission wurde zur Laufzeit revoked. Keep-going-Pfad: Logge, mache nichts weiter.
            Log.w(TAG, "Overlay attach failed — permission revoked at runtime?", e)
            buttonViews = emptyMap()
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

### §4.3 LayoutParamsFactory — vollständige WindowManager-Konfiguration

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

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,  // Alpha-Channel im Drawable wird respektiert.
        ).apply {
            // Initial-Anker (entspricht Default-State `overlayPosition*X = 1.0f, *Y = 0.1f`):
            // Top-End mit ~80dp y-Offset. Hält das Overlay unter Status-Bar + Notch.
            // ACHTUNG: nach erstem `render()`-Call übernimmt `OverlayBackend.applyPosition`
            // die Steuerung und stellt gravity auf TOP|START um, damit Drag-Pixel-Koordinaten
            // konsistent vom oberen-linken Display-Eck rechnen (siehe §4.6 / §11.5).
            gravity = Gravity.TOP or Gravity.END
            x = dpToPx(ctx, 16)
            y = dpToPx(ctx, 80)
            // KEINE Animation — wir wollen sofortige Anzeige, kein Slide-In.
            windowAnimations = 0
        }
    }
}

private fun dpToPx(ctx: Context, dp: Int): Int =
    (dp * ctx.resources.displayMetrics.density).toInt()
```

### §4.4 Flag-Tabelle: warum was

| Flag | Setzen? | Begründung |
|------|---------|------------|
| `FLAG_NOT_FOCUSABLE` | **JA** | Overlay darf der unterliegenden App keinen Fokus klauen. Soft-Keyboard-Eingaben in der App bleiben funktionsfähig. (AOSP setzt damit implizit auch `FLAG_NOT_TOUCH_MODAL`.) |
| `FLAG_NOT_TOUCH_MODAL` | **JA (explizit)** | Explizites Setzen, weil wir `WRAP_CONTENT` haben — Touches AUSSERHALB der Buttons müssen an die App darunter durchgehen. Ohne diesen Flag schluckt das Window den gesamten Touch-Stream im Bildschirm. |
| `FLAG_LAYOUT_IN_SCREEN` | **JA** | Position-Anker (gravity + x + y) rechnet vom Display-Edge — nicht vom Decor-Frame. Wichtig für stabile rechte-obere-Ecke unabhängig von App-Status-Bar/Action-Bar. |
| `FLAG_HARDWARE_ACCELERATED` | **JA** | Material-Buttons (Ripple, Elevation, Shadow) brauchen HW-Layer für saubere Renderings. Auf Software-Layer wirken Schatten oft unscharf. |
| `FLAG_KEEP_SCREEN_ON` | **NEIN** | Das ist Sache des PipelineService (Wake-Lock dort, falls aktiv-Recording). Nicht doppelt halten. |
| `FLAG_SHOW_WHEN_LOCKED` | **NEIN** | Wir wollen das Overlay NICHT auf dem Lock-Screen zeigen — das wäre verwirrend, der User kann ohnehin nichts in eine App eingeben, wenn der Screen gelockt ist. |
| `FLAG_LAYOUT_NO_LIMITS` | **NEIN** | Würde das Overlay über den Display-Rand schieben können. Wir wollen, dass das System unsere Bounds clamped (z.B. Notch-Schutz). |
| `FLAG_DIM_BEHIND` | **NEIN** | Kein Modal — nichts wird verdunkelt. |
| `FLAG_WATCH_OUTSIDE_TOUCH` | **NEIN (Phase 1)** | Würde uns `MotionEvent.ACTION_OUTSIDE` liefern — sinnvoll erst, wenn wir z.B. tap-outside-to-close-Verhalten wollen. Heute nicht gefordert. |

### §4.5 Format

| Format | Wahl | Begründung |
|--------|------|------------|
| `PixelFormat.TRANSLUCENT` | **JA** | Hintergrund ist Material-Surface mit Rounded-Corners — Alpha-Channel des Drawables muss respektiert werden. |
| `PixelFormat.OPAQUE` | nein | Würde unsere Rounded-Corners als Black-Box rendern. |
| `PixelFormat.TRANSPARENT` | nein | Wir haben einen **opaken** Innenhintergrund, nur Eckenrundung soll transparent sein → TRANSLUCENT ist semantisch korrekt. |

### §4.6 OverlayDragHandler (OPEN-3)

Eigenständige Klasse — **nicht als Mixin im Backend** (SRP/SOLID, vermeidet Touch-Routing-
Logik im Render-Code). Wird vom `OverlayBackend` über eine kleine Factory injiziert; der
Handler kennt nur Window-Update + Persist-Callback, nicht das State-Modell.

```kotlin
interface OverlayDragHandler {
    fun attach()
    fun detach()
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

    private val touchSlopPx: Int = ViewConfiguration.get(ctx).scaledTouchSlop
    // 8dp-Threshold (etwas großzügiger als der System-touch-slop ~ 8dp auf 2x density).
    // Gewählt, damit kleine Wackler beim Tippen nicht versehentlich in Drag-Modus wechseln.
    private val dragThresholdPx: Int = (8 * ctx.resources.displayMetrics.density).toInt()

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
                    val (normX, normY) = positionMapper.pixelsToNormalized(params.x, params.y, view)
                    onPositionPersist(normX, normY)
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

    override fun detach() {
        view.setOnTouchListener(null)
    }
}
```

**SOLID-Konformität:**
- **SRP:** DragHandler kennt nur Touch-Events + Window-Update. Keine State-Mutation, keine
  Pref-Schreibung — die Persist-Logik ist via Callback abstrahiert.
- **DIP:** `OverlayBackend` hängt am Interface `OverlayDragHandler` (+ Factory), nicht an
  der konkreten Klasse. Tests können einen `FakeOverlayDragHandler` injizieren.
- **OCP:** Snap-to-Edge oder andere Drag-End-Verhalten sind addable durch Decorator
  (`SnappingOverlayDragHandler` wraps DefaultOverlayDragHandler).

### §4.7 OverlayPositionMapper (OPEN-3)

Konvertiert zwischen normalisierten 0..1-Koordinaten (im State persistiert) und absoluten
Pixel-Koordinaten (vom WindowManager benötigt). Single-Source-of-Truth für die
Konversion — vermeidet, dass DragHandler und Backend dieselbe Math doppelt machen.

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
    override fun normalizedToPixels(normX: Float, normY: Float, view: View): Pair<Int, Int> {
        val (screenW, screenH) = displaySize()
        val viewW = view.width.takeIf { it > 0 } ?: view.measuredWidth
        val viewH = view.height.takeIf { it > 0 } ?: view.measuredHeight
        val maxX = (screenW - viewW).coerceAtLeast(0)
        val maxY = (screenH - viewH).coerceAtLeast(0)
        val px = (normX.coerceIn(0f, 1f) * maxX).toInt()
        val py = (normY.coerceIn(0f, 1f) * maxY).toInt()
        return px to py
    }

    override fun pixelsToNormalized(px: Int, py: Int, view: View): Pair<Float, Float> {
        val (screenW, screenH) = displaySize()
        val viewW = view.width.takeIf { it > 0 } ?: view.measuredWidth
        val viewH = view.height.takeIf { it > 0 } ?: view.measuredHeight
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
```

**DRY:** Alle Konversionen 0..1 ↔ Pixel laufen über diese Klasse. Backend (`applyPosition`)
und DragHandler (`pixelsToNormalized` bei UP) nutzen dieselben Formeln — keine
Drift-Quelle.

---

## §5 Permission-Onboarding-Flow

### §5.1 Permission-Gate (zentrale Logik, getrennt vom Render — SRP)

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

**Begründung der Trennung (SRP, §13.2):** Render-Logik soll nicht über Persistence-State entscheiden, ob das Onboarding gezeigt wird. Der Gate bündelt Permission-Check + Onboarding-State; der Render-Pfad fragt nur ja/nein.

### §5.2 Settings-Intent (Permission-Request)

`SYSTEM_ALERT_WINDOW` kann **nicht** über den Standard-Permission-Prompt erfragt werden (kein `runtime permission`, sondern `appop`/`special permission`). Stattdessen muss der User in System-Settings explizit umschalten.

Konkreter Code, **vom IME-Service aus** aufgerufen (nicht aus einer Activity — wir HABEN keine vordergrund-Activity):

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

### §5.3 Onboarding-UI im IME-View

Da der IME keine Activity ist, wird die Onboarding-Aufforderung **direkt im IME-View** als InfoBar angezeigt. Der `KeyboardLayoutManager` reagiert auf einen neuen State-Bit `state.overlayOnboardingPending` (gesetzt vom PipelineStateManager, sobald `Action.ToggleViewModeWidget` erkannt UND Permission fehlt).

**UI-Layout** — neue InfoBar-Region oberhalb der Tastatur-Buttons (Two-Row + Single-Row gleich):

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

**Konkrete UI-Strings** (in `res/values/strings.xml`, deutsche UI-Sprache):

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

**Klick-Handler** im `ImeViewBackend`:

```kotlin
private fun bindPermissionInfoBar(state: DictateUiState) {
    val bar = rootView.findViewById<View>(R.id.overlay_permission_infobar)
    bar.visibility = if (state.overlayOnboardingPending) View.VISIBLE else View.GONE
    if (!state.overlayOnboardingPending) return

    rootView.findViewById<View>(R.id.overlay_perm_grant_btn).setOnClickListener {
        launchOverlayPermissionSettings(ctx)
        onAction?.invoke(Action.MarkOverlayOnboardingShown)
        // Activity-Result kommt nicht zurück (IME ist keine Activity). Der Permission-Status
        // wird beim nächsten render() via Settings.canDrawOverlays() neu gelesen — siehe §5.5.
    }
    rootView.findViewById<View>(R.id.overlay_perm_dismiss_btn).setOnClickListener {
        onAction?.invoke(Action.DismissOverlayOnboarding)
    }
}
```

### §5.4 Erste-Mal-Logik vs. Wieder-Verweigert-Logik

Pseudo-Code-Flow im `PipelineStateManager` (§4 Spec 1):

```kotlin
fun toggleViewMode(target: ViewMode) {
    when (target) {
        ViewMode.WIDGET -> {
            if (!permissions.hasOverlayPermission()) {
                if (permissions.shouldShowOnboarding()) {
                    // Erst-Versuch: zeige InfoBar, KEIN ViewMode-Wechsel.
                    _state.value = _state.value.copy(overlayOnboardingPending = true)
                } else {
                    // Permanent abgelehnt: stiller Notification-Fallback (Notification ist da).
                    // Keine InfoBar, kein ViewMode-Wechsel — User muss in Settings selbst aktivieren.
                }
                return
            }
            // Permission da: normaler Wechsel.
            _state.value = _state.value.copy(viewMode = ViewMode.WIDGET, overlayOnboardingPending = false)
        }
        // ... andere Cases
    }
}

fun dismissOverlayOnboarding() {
    permissions.markPermanentlyDenied()
    _state.value = _state.value.copy(overlayOnboardingPending = false)
}

fun markOverlayOnboardingShown() {
    permissions.markOnboardingShown()
    _state.value = _state.value.copy(overlayOnboardingPending = false)
    // viewMode wird NICHT verändert — der User kommt aus den Settings zurück und muss
    // den Widget-Toggle erneut betätigen, dann ist Permission da und §7.1-Pfad greift.
}
```

### §5.5 Activity-Result-Handling — wie kommt die Antwort zurück?

**Wichtig:** der IME ist keine Activity, daher gibt es kein `onActivityResult`. Stattdessen lesen wir `Settings.canDrawOverlays()` lazy bei jedem Permission-relevanten Trigger:

| Trigger | Code-Pfad |
|---------|-----------|
| User kommt aus Settings zurück und klickt Widget-Toggle | `toggleViewMode(WIDGET)` ruft `permissions.hasOverlayPermission()` neu. |
| User öffnet IME-View neu (`onStartInputView`) | IME-Service ruft `pipeline.notifyImeViewShown()`, der StateManager kann hier `permissions.hasOverlayPermission()` cachen. |
| Render-Pfad (defensive) | `OverlayBackend.render()` prüft `permissions.hasOverlayPermission()` als Vorbedingung — wenn das System die Permission revoked hat (sehr selten), reißt der Backend das Window kontrolliert ab statt zu crashen. |

Damit ist kein `onActivityResult`-Listener nötig. Settings ist eine andere App; das System wird nicht zurück zu uns navigieren — der User kommt manuell zurück.

### §5.6 Fallback bei Verweigerung

- **WIDGET**: Toggle-Button im IME ist disabled (visuell ausgegraut, alpha 0.4). Tooltip: "Berechtigung fehlt — Tippen zum Erlauben". Klick löst die InfoBar erneut aus (auch nach permanenter Ablehnung — der User soll umkehren können).
- **HOVER**: Auto-Trigger (View hidden + Pipeline aktiv) zeigt **kein** Floating-Overlay. Stattdessen reicht die persistente Foreground-Service-Notification (aus Spec 1) als Status-Indikator. Kein Onboarding in dieser Situation — der User ist außerhalb der Tastatur, eine InfoBar wäre verloren.

### §5.7 Manifest-Eintrag

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

Auf API 23+ ist das eine Special-Permission (kein Runtime-Prompt). Auf API < 23 ist sie auto-gegranted bei Install. Der `Settings.canDrawOverlays()`-Check funktioniert auf allen API-Leveln und gibt auf < 23 immer `true` zurück.

---

## §6 Schließen-Button-Differentialverhalten

### §6.1 In WIDGET-Modus

```kotlin
// actionResolver(state) wenn state.viewMode == WIDGET:
Action.ToggleViewModeWidget
```

PipelineStateManager.toggleViewMode-Logik (in Spec 1 §4):

```kotlin
fun toggleViewMode(target: ViewMode) {
    when (target) {
        ViewMode.KEYBOARD -> {
            _state.value = _state.value.copy(
                viewMode = ViewMode.KEYBOARD,
                smallMode = true  // ← User-Anforderung: "macht es die Tastatur klein"
            )
            // IME-Service erkennt View-Mode-Wechsel und re-rendert IME-View
        }
        ViewMode.WIDGET -> {
            _state.value = _state.value.copy(viewMode = ViewMode.WIDGET)
        }
        // ...
    }
}
```

→ Resultat: Overlay wird abgerissen, IME-View wird neu gerendert mit aktivem SmallMode (kompakte Tastatur).

### §6.2 In HOVER-Modus

```kotlin
// actionResolver(state) wenn state.viewMode == HOVER:
Action.CloseOverlay
```

PipelineStateManager.closeOverlay-Logik:

```kotlin
fun closeOverlay() {
    // Brich aktuelle Pipeline ab UND dismisse Overlay
    cancelPipeline()
    _state.value = _state.value.copy(viewMode = ViewMode.KEYBOARD)
    // → Overlay wird abgerissen, KEINE neue UI angezeigt (User ist außerhalb von Eingabefeldern)
    // User muss explizit Tastatur öffnen + schließen, damit das Auto-Trigger-System wieder greift
}
```

→ Resultat: Overlay verschwindet komplett. Pipeline ist abgebrochen. Erst wenn User wieder ein Eingabefeld öffnet, läuft alles normal an.

---

## §7 Mode-Transitionen

### §7.1 Triangle-FSM-Logik (im PipelineStateManager)

```kotlin
fun notifyImeViewVisibilityChanged(visible: Boolean) {
    val newViewMode = computeViewMode(
        imeViewVisible = visible,
        userToggledWidget = _state.value.viewMode == ViewMode.WIDGET,
        pipelineActive = _state.value.pipeline !is PipelineUiState.Idle
                          || _state.value.recording.isActiveOrPaused
    )
    if (newViewMode != _state.value.viewMode) {
        _state.value = _state.value.copy(viewMode = newViewMode)
    }
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

### §7.2 KeyboardLayoutManager-Reaktion auf ViewMode-Wechsel

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

### §7.3 Code-Skizzen pro Übergang

`userToggledWidget` ist eine Persistenz-Bit, das in `DictateUiState.userPrefersWidget` lebt — siehe §11.9.

#### T1: KEYBOARD → WIDGET (User klickt Widget-Toggle in der Tastatur)

**Auslöser:** Klick auf einen `widget_toggle_btn` im IME-View (neuer Slot in Spec 2 oder im Settings-Bar).

```kotlin
// Im ButtonSlot des Widget-Toggle (Spec 2 ergänzt):
ButtonSlot(LogicalButtonId.WIDGET_TOGGLE, WrapContent,
    visibilityPredicate = { true },
    enabledResolver = { state -> state.recording.isActiveOrPaused
                                  || state.pipeline !is PipelineUiState.Idle },
    actionResolver = { Action.ToggleViewModeWidget })

// In PipelineStateManager:
fun onAction(action: Action) {
    when (action) {
        Action.ToggleViewModeWidget -> {
            // 1. Permission-Gate prüfen (§5.4)
            if (!permissions.hasOverlayPermission()) {
                _state.value = _state.value.copy(overlayOnboardingPending = true)
                return
            }
            // 2. State-Mutation: viewMode + Persistenz-Bit
            _state.value = _state.value.copy(
                viewMode = ViewMode.WIDGET,
                userPrefersWidget = true,  // §11.9 Persistenz
            )
        }
        // ...
    }
}

// KeyboardLayoutManager.onStateChanged() reagiert reaktiv auf state.viewMode:
//   → switchBackend(WIDGET)  → imeViewBackend.detach() + overlayBackend.attach()
//   → render(state, OVERLAY_5BUTTON)
```

**Reihenfolge:** Action → State-Mutation → StateFlow-Emit → KeyboardLayoutManager.onStateChanged → switchBackend → render. Es gibt **keinen** direkten Backend-Call vom Click-Handler — alles über die State-Pipe.

#### T2: WIDGET → KEYBOARD (User klickt Schließen-Button im Widget — mit SmallMode)

```kotlin
// Im OVERLAY_CLOSE-Slot:
actionResolver = { state ->
    when (state.viewMode) {
        ViewMode.WIDGET -> Action.ToggleViewModeWidget
        ViewMode.HOVER -> Action.CloseOverlay
        else -> Action.NoOp
    }
}

// In PipelineStateManager:
fun onAction(action: Action) {
    when (action) {
        Action.ToggleViewModeWidget -> {
            val current = _state.value
            if (current.viewMode == ViewMode.WIDGET) {
                // WIDGET → KEYBOARD mit SmallMode-Aktivierung (User-Anforderung)
                _state.value = current.copy(
                    viewMode = ViewMode.KEYBOARD,
                    smallMode = true,  // "Tastatur klein" wie vom User gewünscht
                    userPrefersWidget = false,  // §11.9 Persistenz reset
                )
            } else if (current.viewMode == ViewMode.KEYBOARD) {
                // KEYBOARD → WIDGET (siehe T1)
            }
        }
    }
}
```

**Wichtig:** `userPrefersWidget = false` reset, damit beim nächsten View-Hidden-Event NICHT wieder WIDGET, sondern HOVER greift (über §7.1-Logik).

#### T3: KEYBOARD → HOVER (View hidden + Pipeline aktiv, war KEYBOARD)

**Auslöser:** Android ruft `onFinishInputView` im IME-Service.

```kotlin
// In DictateInputMethodService:
override fun onFinishInputView(finishingInput: Boolean) {
    super.onFinishInputView(finishingInput)
    pipeline?.notifyImeViewHidden()
}

// In LocalBinder:
fun notifyImeViewHidden() = stateManager.notifyImeViewVisibilityChanged(false)

// In PipelineStateManager (§7.1):
fun notifyImeViewVisibilityChanged(visible: Boolean) {
    val state = _state.value
    val newViewMode = computeViewMode(
        imeViewVisible = visible,
        userToggledWidget = state.userPrefersWidget,
        pipelineActive = state.pipeline !is PipelineUiState.Idle || state.recording.isActiveOrPaused,
    )
    if (newViewMode != state.viewMode) {
        _state.value = state.copy(viewMode = newViewMode)
    }
}
```

**Konkret:** `visible=false`, `userToggledWidget=false`, `pipelineActive=true` → `HOVER`. KeyboardLayoutManager schaltet auf overlayBackend, OVERLAY_5BUTTON wird gerendert mit Send + Record disabled.

#### T4: WIDGET → HOVER (View hidden + Pipeline aktiv, war WIDGET)

Identischer Pfad wie T3, aber **Vorzustand** war WIDGET. Der Unterschied:

```kotlin
// notifyImeViewVisibilityChanged-Aufruf, state.userPrefersWidget=true:
imeViewVisible=false, userToggledWidget=true, pipelineActive=true
  → computeViewMode: !visible && pipelineActive → HOVER
```

**WICHTIGE Eigenschaft:** auch wenn `userPrefersWidget=true`, schalten wir bei View-Hidden auf HOVER (nicht auf WIDGET-bei-View-aus, das wäre semantisch falsch — InputConnection ist tot, Send geht nicht). Beim Wieder-Sichtbarmachen kommt der Widget-Wunsch zurück (T6). Das WIDGET-Bit ist persistiert in `state.userPrefersWidget`.

#### T5: HOVER → KEYBOARD (View kommt zurück, war NICHT WIDGET vorher)

**Auslöser:** Android ruft `onStartInputView`.

```kotlin
// In DictateInputMethodService:
override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
    super.onStartInputView(info, restarting)
    pipeline?.notifyImeViewShown()
}

// In PipelineStateManager:
fun notifyImeViewVisibilityChanged(visible=true):
    state.userPrefersWidget=false (vorher KEYBOARD, dann HOVER → User-Bit war false)
  → computeViewMode: visible && !userToggledWidget → KEYBOARD
```

#### T6: HOVER → WIDGET (View kommt zurück + war WIDGET — Persistenz-Bit greift)

```kotlin
// In PipelineStateManager:
fun notifyImeViewVisibilityChanged(visible=true):
    state.userPrefersWidget=true  // ← gesetzt in T1, NICHT zurückgesetzt
  → computeViewMode: visible && userToggledWidget → WIDGET
```

**Persistenz-Hinweis:** `state.userPrefersWidget` muss in **memory** überleben (StateFlow im Service, der den Tastatur-Wechsel überlebt — Spec 1 D1). Es muss **nicht** persistent in DB/Prefs landen, weil eine neue Pipeline-Session den Widget-Wunsch ohnehin neu setzt. Diskussion siehe §11.9.

---

## §8 Touch-Routing

`FLAG_NOT_FOCUSABLE` (gesetzt im LayoutParams oben): das Window erhält keine Keyboard-Events, aber Touch-Events werden weiterhin verarbeitet — Buttons reagieren auf Klick.

`FLAG_NOT_TOUCH_MODAL` (explizit gesetzt — §4.4): Touches **außerhalb** der Buttons gehen an die unterliegende App. Da das Window via `WRAP_CONTENT` nur die Größe der 5-Button-Box hat, gibt es keine "transparente Region außenrum" — die unterliegende App empfängt alle Touches direkt nebenan und unter dem Widget.

**Edge-Cases & Empfehlungen:**

| Szenario | Verhalten | Begründung |
|----------|-----------|------------|
| Touch direkt auf einen Button | Click reagiert | Standard. |
| Touch auf den Padding-Bereich INNERHALB des Window-Hintergrunds (zwischen Button und Border) | Touch wird vom Window absorbiert (kein Click). | Erwartetes Verhalten. Wenn störend: Button-Padding aufweiten statt Container-Padding. |
| Touch DARUNTER liegender App | Geht an die App durch | Dank `FLAG_NOT_TOUCH_MODAL`. |
| Long-Press auf Button | Heute nicht implementiert. | Bei späterem Bedarf: separate Action `Action.OnLongPress*` mit `OnLongClickListener`. |
| Drag der gesamten Box | Heute nicht implementiert (siehe §11.5). | OPEN-3 — ggf. später. |

---

## §9 Notification-Fallback (Permission-frei)

Auch ohne `SYSTEM_ALERT_WINDOW`-Permission ist die persistente Foreground-Service-Notification (Spec 1 §7) sichtbar. Sie ist ohnehin da, weil Foreground-Service-Pflicht.

Inhalt der Notification entspricht dem HOVER-Layout:
- "Recording läuft" / "Pipeline läuft" / "Bereit zum Einfügen"
- Action-Buttons: [Pause] [Cancel] [Senden] (max. 3 sichtbar)

**Implikation für Spec 3:** das Notification-Backend ist NICHT in Spec 3 — es gehört zur Foreground-Service-Konfiguration in Spec 1. Die Notification ist immer da, mit oder ohne Overlay.

---

## §10 Acceptance-Kriterien

Block 6 (OverlayBackend) gilt als done, wenn:

- [ ] Permission-Onboarding läuft beim ersten Widget-Toggle-Versuch.
- [ ] Bei aktiver Permission und User-Toggle: Overlay erscheint mit 5 Buttons in 2 Reihen (Reihe 1: Record/Send/Pause; Reihe 2: Trash/Schließen mit Schließen unten rechts).
- [ ] Bei View-hidden + Recording-aktiv: HOVER-Overlay erscheint automatisch mit gleichem Layout, Send-Button + Record-Button disabled.
- [ ] Schließen in WIDGET: Overlay weg, IME-View kommt mit SmallMode-Aktivierung.
- [ ] Schließen in HOVER: Overlay weg, Pipeline abgebrochen, KEIN neues Overlay erscheint bis User Tastatur explizit öffnet+schließt.
- [ ] Pause-Button funktioniert in beiden Modi (togglePause).
- [ ] Send-Button funktioniert in WIDGET (StopRecordingAndSend), ist in HOVER disabled.
- [ ] Trash-Button funktioniert in beiden Modi (cancelRecording).
- [ ] Tastatur-Wechsel zur Gboard mit aktivem WIDGET: Overlay verschwindet (IME-Service stirbt), aber PipelineService läuft weiter, Recording weiter aufgenommen.
- [ ] Permission verweigert: Widget-Toggle disabled, HOVER-Auto-Trigger fällt auf Notification-Only zurück.

**OPEN-2 (WIDGET autark, 5-Button-Layout):**

- [ ] In Idle (kein Recording, keine Pipeline): Record-Button im WIDGET sichtbar **und** enabled. User kann Aufnahme aus dem WIDGET heraus starten, ohne erst die Tastatur zu öffnen.
- [ ] In HOVER: Record-Button + Send-Button sind disabled (alpha 0.4, isEnabled=false), weil keine InputConnection als Ziel existiert.
- [ ] Layout-Reihenfolge in Reihe 1: Record links, Send mittig (Fill), Pause rechts. Reihe 2: Trash links, Schließen unten rechts (User-Wunsch).

**OPEN-3 (Drag + per-Orientation-Persistierung):**

- [ ] Widget kann via Drag verschoben werden (Move-Distance > 8dp triggert Drag-Mode).
- [ ] Position wird in SharedPreferences persistiert (Portrait und Landscape getrennt — `Pref.OverlayPositionPortraitX/Y` und `Pref.OverlayPositionLandscapeX/Y`).
- [ ] Bei Orientation-Change wird die orientation-spezifische Position geladen, das Widget springt an die persistierte Stelle der neuen Orientation.
- [ ] Bei Tap (Move-Distance ≤ 8dp) wird kein Drag, sondern ein Klick auf den jeweiligen Button erkannt — der OnClickListener feuert.
- [ ] Bei echtem Drag (Move > 8dp) wird beim ACTION_UP **kein** Click ausgelöst — die Klick-Listener der Buttons feuern nicht.
- [ ] Drag-End persistiert via `Action.UpdateOverlayPosition(portrait, x, y)` durch `PipelineStateManager.updateOverlayPosition` — nicht direkt aus dem `OverlayBackend` in SharedPreferences (SSOT).
- [ ] Initial-Position bei erstem App-Start: Top-End mit ~80dp y-Offset (Default-Werte `1.0f / 0.1f` im DictateUiState).

---

## §11 Research-Antworten (Detail-Research)

### §11.1 SYSTEM_ALERT_WINDOW im IME-Kontext

**Recherche-Ergebnis:** IMEs erhalten SYSTEM_ALERT_WINDOW **nicht** automatisch. Das Privileg ist eine `appop` (`OP_SYSTEM_ALERT_WINDOW`), die unabhängig vom Service-Typ über `Settings.canDrawOverlays()` geprüft wird. Vom Standpunkt der Permission-Logik ist ein IME-Service kein privilegierter Kontext — er muss denselben User-Settings-Toggle durchlaufen wie eine normale App.

**Pro Android-Version:**

| API-Level | Verhalten |
|-----------|-----------|
| < 23 (Android 5 und älter) | `SYSTEM_ALERT_WINDOW` ist Install-Time-Permission, automatisch granted, `Settings.canDrawOverlays()` gibt immer `true` zurück. |
| 23-25 (Android 6-7) | Special-Permission, User muss in Settings → "Über andere Apps anzeigen" toggeln. `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` öffnet die Settings-Seite. |
| 26+ (Android 8+) | Identisch zu 23-25, aber `TYPE_SYSTEM_ALERT` ist deprecated → `TYPE_APPLICATION_OVERLAY` ist Pflicht. |
| 31+ (Android 12+) | Zusätzliche Restriction: System blockt Touch-Events durch unsicher overlappende Overlays. Mit unserem 5-Button-Widget (klein, eindeutig user-initiiert) sind wir nicht betroffen. |

**Hersteller-Quirks (recherchiert via Bug-Reports):**

- **Xiaomi/MIUI:** zusätzlicher proprietärer Settings-Toggle "Andere Berechtigungen → Im Hintergrund anzeigen" — kann unabhängig zu `Settings.canDrawOverlays()` blockieren. Mitigation: User-FAQ auf dem Repo-README, kein Code-Hack.
- **Samsung One UI:** Standard-AOSP-Verhalten, keine Quirks bekannt.
- **Huawei EMUI:** ähnlich wie Xiaomi, "Geschützte Apps"-Liste kann Background-Activity einschränken — betrifft den Foreground-Service mehr als das Overlay.

**Empfehlung:** keine Sonderbehandlung im Code — wir vertrauen auf `Settings.canDrawOverlays()`. Bei Hersteller-Quirks: Notification-Fallback (§9) greift ohnehin, User sieht Status auch ohne Overlay.

### §11.2 TYPE_APPLICATION_OVERLAY-Best-Practices

**Final-Setup** (siehe §4.3, hier als Tabelle zur Verifikation):

| Attribut | Wert |
|----------|------|
| Type | `TYPE_APPLICATION_OVERLAY` (≥ API 26), `TYPE_PHONE` (< API 26) |
| Width / Height | `WRAP_CONTENT` / `WRAP_CONTENT` |
| Flags | `FLAG_NOT_FOCUSABLE \| FLAG_NOT_TOUCH_MODAL \| FLAG_LAYOUT_IN_SCREEN \| FLAG_HARDWARE_ACCELERATED` |
| Format | `PixelFormat.TRANSLUCENT` |
| Gravity | `Gravity.TOP \| Gravity.END` |
| x / y | 16dp / 80dp |
| windowAnimations | 0 (kein Slide-In) |

**Lock-Screen:** `TYPE_APPLICATION_OVERLAY` ohne `FLAG_SHOW_WHEN_LOCKED` erscheint **nicht** auf dem Lock-Screen. Das ist bei uns gewünscht (keine Eingabe möglich, also auch keine Steuerelemente). Bei Bedarf später: `FLAG_SHOW_WHEN_LOCKED` setzen.

**IME + Overlay parallel:**
Der WIDGET-Modus zeigt IME-View **und** Overlay gleichzeitig — das funktioniert, weil beide separate Windows sind und unterschiedliche Layer haben (IME-Layer ist über App-Window, Overlay-Layer ist über IME-Layer für `TYPE_APPLICATION_OVERLAY`). Verifikation auf physischem Device empfohlen, weil Layer-Ordering historisch je nach Hersteller variiert hat.

### §11.3 Onboarding-UX

**Konkrete UI-Strings** — siehe §5.3.

**Wann zeigen?** Erst beim **ersten Widget-Toggle-Versuch** (lazy), nicht prophylaktisch im Settings-Screen. Begründung:
- Der User klickt das Widget aktiv an — die Frage nach Permission ist im Kontext motiviert.
- Settings-vorab wäre eine fremde Frage ohne Bezug — schlechte Conversion.

**Screen-Flow:**

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

**Wenn der User "Später" klickt:** InfoBar wird ausgeblendet, `markPermanentlyDenied()` wird gerufen → bei nächstem Widget-Toggle-Versuch gibt es keine InfoBar mehr, sondern stillen Fallback (Notification reicht). Der User kann jederzeit in System-Settings selbst aktivieren — der nächste Widget-Toggle-Klick prüft erneut `canDrawOverlays()` und greift dann.

### §11.4 Touch-Routing-Details

**Antwort — Flag-Empfehlung:** `FLAG_NOT_TOUCH_MODAL` **explizit setzen**, auch wenn `FLAG_NOT_FOCUSABLE` es implizit aktiviert. Begründung: Robustheit gegen API-Changes — AOSP hat in der Vergangenheit den impliziten Effekt schon mal aufgehoben. Lieber doppelt belegt als später Bug-Hunt.

**Was passiert in der Transparenz-Area zwischen Buttons:**
Wir haben **keine Transparenz-Area** im klassischen Sinne — das Window hat `WRAP_CONTENT`, der Hintergrund ist opaque (Material-Surface mit Rounded-Corners). Touches auf den Container-Padding-Bereich werden vom LinearLayout abgefangen, aber nicht weitergeleitet — kein Click, aber auch kein Pass-Through. Das ist akzeptabel, weil der Padding-Bereich nur ~6dp breit ist.

**Verhinderung versehentlicher Klicks auf darunterliegende App:**
Da das Window klein ist (220x110dp) und in der Ecke (Top-End, 16dp/80dp Offset), liegt es außerhalb der typischen Inhalts-Bereiche. User-Klicks auf eigene Inhalte gehen vorbei. Falls trotzdem versehentlich auf einen Overlay-Button geklickt wird: ja, das ist die User-Intention (das ist ja unsere UI).

### §11.5 Drag-Funktionalität (OPEN-3) — **Block 6 in scope**

**Entscheidung (User, 2026-05):** Drag ist **Phase 1, Block 6**. Persistierung getrennt
pro Orientation, in normalisierten 0..1-Koordinaten, via dedizierter `OverlayDragHandler`-
Klasse (siehe §4.6). Die Position-Berechnung läuft durch `OverlayPositionMapper` (§4.7).

#### §11.5.1 OnTouchListener — Pseudo-Code (vollständig)

Die Implementation lebt in `DefaultOverlayDragHandler` (§4.6). Zur leichteren Review hier
der Pseudo-Code-Flow ohne Klassen-Boilerplate:

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
                onAction(Action.UpdateOverlayPosition(portrait, normX, normY))
                dragging = false
                return true                         // Click unterdrücken — war Drag
            else:
                return false                        // Tap → Buttons machen Click
```

#### §11.5.2 Click-vs-Drag-Differenzierung

| Szenario | Move-Distance | Verhalten |
|----------|---------------|-----------|
| Tap auf Button | 0..8dp Drift | `dragging=false`, ACTION_UP retourniert `false` → Button-OnClickListener feuert. |
| Slow-Drag startet langsam | überschreitet 8dp irgendwann | Sobald `dragging=true`, Touch wird konsumiert. ACTION_UP retourniert `true` → kein Click. |
| Schneller Tap | <8dp Drift, schnelle UP | wie Tap → Click. |
| Long-Press auf Button | <8dp Drift | wie Tap (DOWN nicht konsumiert → Long-Press-Detector des Buttons greift). |

Der Threshold ist in `dp` definiert (Display-Density-unabhängig). 8dp ist großzügiger als
der System-touch-slop (typisch 8dp auf 2x density, also ~16px), bewusst — ein Widget am
Bildschirmrand wird oft mit dem Daumen erreicht, der einen leichten Roll-Motion macht.

#### §11.5.3 Position-Berechnung in Pixel-Koordinaten

Während Drag (ACTION_MOVE) wird mit absoluten Pixeln gerechnet:

```
params.x = initialParamsX + (event.rawX - initialTouchX).toInt()
params.y = initialParamsY + (event.rawY - initialTouchY).toInt()
```

`gravity` ist `TOP|START` (siehe `OverlayBackend.applyPosition`), damit `params.x` vom
linken Display-Eck und `params.y` vom oberen Display-Eck zählt — additive Drag-Math
funktioniert korrekt. Boundary-Clamping passiert **nicht** im DragHandler — das
WindowManager-System clamped automatisch auf den sichtbaren Bereich (FLAG_LAYOUT_NO_LIMITS
ist `NEIN`, siehe §4.4). Wenn der User über den Rand hinaus zieht, hängt das Overlay an
der Kante; bei UP wird die geclampte Position normalisiert.

#### §11.5.4 Normalisierung 0..1 vor Persistierung

Bei ACTION_UP rechnet `OverlayPositionMapper.pixelsToNormalized` (§4.7):

```
maxX = (screenW - viewW).coerceAtLeast(1)
maxY = (screenH - viewH).coerceAtLeast(1)
normX = (params.x / maxX).coerceIn(0f, 1f)
normY = (params.y / maxY).coerceIn(0f, 1f)
```

`maxX/maxY` ist die "freie Fläche" — der Bereich, in dem die obere-linke Ecke des Widgets
sich legitim aufhalten kann (sonst würde es teilweise off-screen liegen). `normX=1` heißt
"die obere-linke Ecke ist so weit rechts wie möglich, sodass das Widget gerade noch
vollständig sichtbar ist" — das entspricht dem Default `1.0f` (Top-End-Anker, rechte Kante).

Die `Action.UpdateOverlayPosition(portrait, normX, normY)` läuft via
`onAction(...)`-Callback durch `KeyboardLayoutManager` zum `PipelineStateManager`, der die
Pref-Schreibung + State-Mutation atomar erledigt (Spec 1 §6.4).

#### §11.5.5 De-Normalisierung beim Render

`OverlayBackend.applyPosition(state)` (§4.2) ruft bei jedem render-Call:

```
isPortrait = config.orientation == ORIENTATION_PORTRAIT
(normX, normY) = if isPortrait
    state.overlayPositionPortraitX/Y
else
    state.overlayPositionLandscapeX/Y
(px, py) = positionMapper.normalizedToPixels(normX, normY, view)
params.x = px; params.y = py; params.gravity = TOP|START
overlayWindow.update(view, params)
```

Da `applyPosition` nur dann ein `update` triggert, wenn sich Werte tatsächlich geändert
haben (Vergleich gegen `currentParams`), ist das State-driven Setzen idempotent und billig.

#### §11.5.6 Orientation-Change-Handling

Welcher Pref-Wert gelesen wird, hängt von der **aktuellen** `Configuration.orientation` ab.
Konkreter Ablauf:

1. User dreht das Gerät → System triggert `onConfigurationChanged` im `IME-Service` und
   `PipelineService`.
2. Das nächste State-Emit (z.B. weil `recovery`-Reread oder `notifyImeViewVisibilityChanged`)
   führt zum `render(state)` im Backend.
3. `applyPosition` liest die jetzt aktuelle Orientation und nimmt den passenden Pref-Wert.
4. Das Widget springt in die persistierte Position der neuen Orientation.

**Wichtig:** Drag in Portrait persistiert NUR `OverlayPositionPortrait*`, Drag in Landscape
NUR `OverlayPositionLandscape*` — beide Default-Werte (1.0/0.1) sind initial gleich, aber
ab dem ersten Drag pro Orientation eigenständig.

#### §11.5.7 Optional: Snap-to-Edge — Empfehlung mit Begründung

**Empfehlung: NEIN, zunächst nicht implementieren.** Begründung:

- **User-Wunsch:** Im Open-Question-Block hat der User "einfach an User-Position bleiben"
  als Default markiert. Snap würde das überschreiben.
- **Sustainability:** Snap-to-Edge ist additive Logik im DragHandler (`ACTION_UP` →
  Distance-zu-jedem-Edge berechnen → springt zur nächsten). Sie kann jederzeit als
  Decorator-Klasse `SnappingOverlayDragHandler` ergänzt werden, ohne das Default-
  Verhalten zu brechen — OCP.
- **UX-Risiko:** Snap kann sich unerwartet anfühlen, wenn der User bewusst ein Mid-Screen-
  Position wählt (z.B. um eine bestimmte UI-Region freizuhalten). Pure-Free-Drag respektiert
  die User-Wahl 1:1.

Falls Snap später gewünscht: Decorator-Pattern, ein Flag `Pref.OverlaySnapToEdge: Boolean`,
der DragHandler-Factory wählt zwischen `Default` und `Snapping`. Decision-Punkt-Trigger:
User-Feedback "Widget verrutscht bei Tippen". Bis dahin: nicht in Scope.

#### §11.5.8 Listener-Anbringung

`view.setOnTouchListener(dragHandler.touchListener)` auf dem **Root-View** des Overlay-XMLs
(`@id/overlay_root`). Click-Listener auf den einzelnen Buttons greifen weiter, weil:
- Bei Tap (kein Drag): `OnTouchListener.onTouch` retourniert `false` → Touch-Event geht
  durch zum Child (Button) → dessen `OnClickListener` feuert.
- Bei Drag: Listener konsumiert den Stream ab dem Move-Threshold → Buttons sehen kein
  ACTION_UP → kein Click ausgelöst.

Persistenz-Logik: NICHT direkt in SharedPreferences vom DragHandler aus (würde
Single-Source-of-Truth-Regel verletzen — alle Mutations laufen durch `PipelineStateManager`).
Stattdessen via `Action.UpdateOverlayPosition` zum Manager (Spec 1 §6.4 + §13.2.3).

### §11.6 Window-Lifecycle-Edge-Cases

| Szenario | Verhalten |
|----------|-----------|
| `windowManager.addView` wirft `BadTokenException` (Permission revoked zur Laufzeit) | Catch in `inflateAndAttach()`, log, `buttonViews = emptyMap()`, kein Crash. Beim nächsten `render()` versucht es das Backend erneut — wenn Permission noch fehlt, schiebt der `permissions.hasOverlayPermission()`-Check oben in `render()` den Code in den Fallback-Pfad. |
| `windowManager.removeView` wirft `IllegalArgumentException` (View war nicht attached) | Catch im `AndroidOverlayWindow.detach()` — idempotent. |
| `OverlayBackend.detach()` während `inflateAndAttach()` läuft | Theoretisch race: `addView` läuft im Main-Thread, `detach` läuft im Main-Thread — kein echtes Race. Aber: wenn `attach`/`detach` über StateFlow-Emits getriggert werden, können sie schnell hintereinander kommen. Idempotenz ist die Lösung: `attached`-Bit im Wrapper, `if (attached) return` im `attach`. |
| PipelineService `onDestroy()` während Overlay attached | KeyboardLayoutManager.detachBackend() wird vom IME-Service vorher gerufen → `OverlayBackend.detach()` → `removeView`. Wenn nicht (z.B. Crash): das System räumt das Window beim Process-Death automatisch auf. |
| Permission wird in System-Settings revoked, während Overlay sichtbar | Android sendet KEIN Broadcast. Beim nächsten `render()`-Call (StateFlow-Emit) merken wir nichts — `addView` ist vor langer Zeit gelaufen, `Settings.canDrawOverlays()` würde `false` zurückgeben, aber wir prüfen das nur am Anfang von `render()`. Edge-Case ist akzeptabel, weil sehr selten. |

### §11.7 Multi-Window-Mode

| Szenario | Verhalten |
|----------|-----------|
| Split-Screen | `TYPE_APPLICATION_OVERLAY` wird ÜBER beiden Split-Halves gerendert — wie auf Vollbild. Position bleibt Top-End des physischen Displays. Akzeptabel; falls in Praxis störend (z.B. Overlay verdeckt Action-Bar der oberen App), kann später `WindowMetrics`-aware-Positioning ergänzt werden. |
| Picture-in-Picture (PiP) | PiP-Window des unterliegenden Apps ist klein, Overlay bleibt in Top-End. Kein Konflikt. |
| Free-Form-Window (z.B. ChromeOS, Samsung DeX) | `TYPE_APPLICATION_OVERLAY` ist global zum Display, nicht App-bound — bleibt Top-End des Displays unabhängig vom App-Window. Akzeptabel. |

**Empfehlung:** kein expliziter Multi-Window-Code in Phase 1. Falls in Telemetrie auffällig, später als Erweiterung. |

### §11.8 Notification-Fallback

**Aus Spec 3 ausgelagert.** Notification ist Pflicht der Foreground-Service-Konfiguration in **Spec 1 §7**. Diese Spec implementiert die Notification nicht; sie referenziert nur, dass die Notification ohnehin sichtbar ist und im Permission-Verweigerungs-Pfad als Status-Indikator reicht.

### §11.9 WIDGET im Idle (OPEN-2)

**Frage:** Soll der WIDGET-Toggle disabled sein, wenn keine Pipeline aktiv ist?

**Entscheidung (User, 2026-05): NEIN — WIDGET ist autark.** Begründung:
- Mit dem zusätzlichen Record-Button (5-Button-Layout) kann der User die Aufnahme direkt aus dem Widget heraus starten — auch ohne aktive Pipeline. Das Widget ist **selbstständig handlungsfähig**.
- Der Record-Button hat `visibilityPredicate = { state -> state.recording.isActiveOrPaused.not() && state.pipeline is PipelineUiState.Idle }` (sichtbar in Idle) und `enabledResolver = { state -> state.viewMode == ViewMode.WIDGET }` (in HOVER disabled, weil ohne InputConnection das Resultat nirgendwohin geschrieben werden könnte).
- Send/Pause/Trash sind weiterhin nur enabled, wenn ein Recording oder eine Pipeline läuft — das Widget ist also kontext-aware.

**Konsequenz:** der WIDGET-Toggle (im IME-View) ist immer enabled, sobald die Permission da ist. Es gibt keinen "leeren WIDGET"-Zustand mehr — Idle-WIDGET hat einen aktiven Record-Button als Einstiegspunkt.

**Persistenz-Bit `userPrefersWidget`:**

Das Bit lebt im DictateUiState (in-Memory im PipelineService). Mutation:
- `Action.ToggleViewModeWidget` in WIDGET: setzt `false` (User schließt Widget aktiv).
- `Action.ToggleViewModeWidget` in KEYBOARD: setzt `true` (User öffnet Widget aktiv).
- `Action.CloseOverlay` in HOVER: bleibt unverändert (HOVER ist auto, kein User-Wunsch-Reset).
- Service-Restart (OOM-Recovery): Bit ist `false` per default. Begründung: nach Process-Tod startet jede Session neu — Widget-Wunsch der vergangenen Session ist nicht garantiert noch gewollt.

**KEINE Prefs-Persistenz** (über Sessions hinweg). Falls in User-Tests gefragt: später optional als Setting "Widget standardmäßig aktiv" — aber Phase 1 nicht.

---

## §12 Referenzen

### Phase-2-Recherchen (Eingangs-Material)

- [_pending-ime-lifecycle-view-recreation.md](../_pending-ime-lifecycle-view-recreation/_pending-ime-lifecycle-view-recreation.md) — IME-Lifecycle, view-Recreate-Pfad. Relevant für Backend-Switch.

### Code-Pointer (Heute)

Dieses Subsystem ist **komplett neu** — keine bestehenden Klassen werden migriert. Code-Pointer entstehen erst durch die Implementierung.

### Externe Referenzen

- TYPE_APPLICATION_OVERLAY: https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY
- SYSTEM_ALERT_WINDOW Permission: https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW
- Settings.canDrawOverlays: https://developer.android.com/reference/android/provider/Settings#canDrawOverlays(android.content.Context)
- IME + Overlay Compatibility (StackOverflow): https://stackoverflow.com/questions/tagged/android-input-method
- Android 12 Touch-Pass-Through-Restrictions: https://developer.android.com/about/versions/12/behavior-changes-all#untrusted-touch-events
- WindowManager.LayoutParams Reference: https://developer.android.com/reference/android/view/WindowManager.LayoutParams
- Floating Windows Tutorial-Reihe (Localazy): https://medium.com/localazy/5-floating-windows-on-android-moving-window-103f8dff37c5
- DraggableView-Library (Inspiration für §11.5): https://github.com/hyuwah/DraggableView

---

## §13 Vollständigkeits-Verifikation

Diese Sektion dokumentiert, wie die Architektur die User-Anforderungen "vollständige Zentralisierung von State und Funktionalität, mit konsequenter SOLID/DRY-Anwendung" erfüllt. Sie dient als Audit-Track — bei jeder späteren Änderung wird hier gegengeprüft, ob die zentralen Eigenschaften noch halten.

### §13.1 SSOT-Konformität

**Behauptung:** das `OverlayBackend` macht KEINE eigene State-Mutation und entscheidet NICHT selbst, wann es aktiv ist.

**Beweis durch Code-Inspektion** (§4.2):

| Code-Stelle | Was es tut | SSOT-Konform? |
|-------------|------------|---------------|
| `attach(onAction)` | speichert Action-Callback, kein State-Touch | ja |
| `detach()` | reisst Window ab, kein State-Touch | ja |
| `render(state, mode)` | liest `state` (read-only), setzt View-Properties, leitet Click → `onAction` weiter | ja — schreibt nicht in state |
| `applySlots(state, mode)` | iteriert Slots, evaluiert Resolver, setzt View-Properties | ja — `state` ist read-only-Parameter |
| Click-Listener | invokt `onAction(slot.actionResolver(state))` | ja — Action geht an PipelineStateManager, der mutiert |

**Wer entscheidet, wann das Backend aktiv ist?**
Der `KeyboardLayoutManager` (§7.2) liest `state.viewMode` und ruft `switchBackend()`. Das OverlayBackend wird durch den Manager `attach`-ed, nicht durch sich selbst.

**Wer mutiert `viewMode`?**
Ausschließlich der `PipelineStateManager` (in Spec 1 §4). Action-Quellen:
- User-Klick im IME → `onAction(Action.ToggleViewModeWidget)` → StateManager.
- `notifyImeViewVisibilityChanged()` → StateManager.
- User-Klick im Overlay → `onAction(Action.ToggleViewModeWidget | CloseOverlay)` → StateManager.

**Konsequenz:** keine Mutation auf irgendeinem Pfad **am StateManager vorbei**. Das Overlay ist ein reiner Renderer + Action-Forwarder. ✓

### §13.2 SOLID-Verifikation

#### SRP (Single Responsibility)

| Klasse | Verantwortlichkeit | Single? |
|--------|-------------------|---------|
| `OverlayBackend` | Render des OVERLAY_5BUTTON-LayoutMode in einem Window + Position-Apply | ja — keine Pipeline-Logik, kein State-Mutation, kein Permission-Onboarding, kein Touch-Routing (delegiert an DragHandler). |
| `AndroidOverlayWindow` | Wrapper für `WindowManager.addView/removeView/updateViewLayout` | ja — kapselt nur Window-Operationen. |
| `OverlayPermissionGate` | Permission-Status + Onboarding-Persistenz | ja — keine UI, kein Render. |
| `DefaultOverlayLayoutParamsFactory` | Erstellt `WindowManager.LayoutParams` | ja — pure Factory, kein State. |
| `DefaultOverlayDragHandler` (OPEN-3) | Touch-Routing: Drag-vs-Click-Differenzierung + Window-Update während Move + Persist-Callback bei UP | ja — kennt nur Touch-Events + Window-Update; weiß nichts über State, Pref-Keys, Action-Typen. |
| `DefaultOverlayPositionMapper` (OPEN-3) | Konversion 0..1 ↔ Pixel | ja — pure Math, kein Side-Effect. |

**Wo werden die Verantwortlichkeiten zusammengefügt?** Im Constructor (DI) des `OverlayBackend`. Der Service-Layer (Spec 1) instanziiert die Komponenten und verdrahtet sie. Das ist Composition Root, keine Compositional Verantwortung im Backend selbst.

#### OCP (Open/Closed)

Erweiterung um neue Overlay-Modi (z.B. ein 6-Button-Overlay) erfordert:
- neuen `LayoutMode` im `LayoutCatalog` (Spec 2 §3.5)
- neuer ID in `LayoutModeId`
- ggf. neuer `LogicalButtonId`-Eintrag

KEINE Änderung am `OverlayBackend` notwendig — der iteriert generisch über Slots. ✓

#### LSP (Liskov Substitution)

`AndroidOverlayWindow` ist eine `OverlayWindow`. Eine `FakeOverlayWindow` (Tests) ist ebenfalls eine `OverlayWindow`. Backend-Verhalten ist unabhängig von der konkreten Implementierung — die Vor-/Nachbedingungen (`isAttached`, `attach`, `detach`) sind im Interface präzise. ✓

#### ISP (Interface Segregation)

`RenderBackend` (Spec 2 §5) ist 3 Methoden — minimal. `OverlayWindow` ist 4 Methoden — minimal. Kein Klient muss Methoden implementieren, die er nicht braucht. ✓

#### DIP (Dependency Inversion)

`OverlayBackend` hängt von `OverlayWindow` (Interface) und `OverlayPermissionGate` (Interface), nicht von `WindowManager` direkt. Das heißt:
- Tests können `FakeOverlayWindow` injizieren.
- Tests können `FakePermissionGate` injizieren.
- Refactor des Window-Mechanismus (z.B. später auf `Compose-for-Window`) ändert das Interface nicht — Backend bleibt invariant.

**Konkreter Beweis im Constructor (§4.2):**
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

### §13.3 DRY-Verifikation

#### Layout-Mode-Sharing für WIDGET und HOVER

**Behauptung:** `OVERLAY_5BUTTON` ist EINE Definition, von beiden Modi genutzt.

**Beweis** (§3.1):
- `OverlayBackend.render()` wird mit `mode = LayoutCatalog.OVERLAY_5BUTTON` aufgerufen — egal ob `state.viewMode == WIDGET` oder `HOVER`.
- KeyboardLayoutManager.computeLayoutMode (Spec 2 §4):
  ```kotlin
  ViewMode.WIDGET, ViewMode.HOVER -> LayoutCatalog.OVERLAY_5BUTTON  // selber Wert
  ```
- Der Send/Record-Button-Unterschied (in WIDGET enabled, in HOVER disabled) lebt im **Resolver**:
  ```kotlin
  enabledResolver = { state -> state.viewMode == ViewMode.WIDGET && state.recording.isActiveOrPaused }
  ```
  KEIN duplizierter Layout-Block. ✓

#### Schließen-Button-Action-Differenzierung

**Behauptung:** der Schließen-Button hat **eine** Slot-Definition; das Differential-Verhalten lebt im `actionResolver`.

**Beweis** (§3.1):
```kotlin
ButtonSlot(LogicalButtonId.OVERLAY_CLOSE, ...,
    actionResolver = { state ->
        when (state.viewMode) {
            ViewMode.WIDGET -> Action.ToggleViewModeWidget
            ViewMode.HOVER -> Action.CloseOverlay
            else -> Action.NoOp
        }
    })
```
KEIN zweiter Button-View, KEIN Backend-spezifischer Click-Listener-Block. ✓

#### Click-Listener-Setup

**Behauptung:** der Click-Listener-Setup ist einheitlich pro Slot, kein Backend-spezifisches Routing.

**Beweis** (§4.2 `applySlots()`):
```kotlin
view.setOnClickListener { onAction?.invoke(slot.actionResolver(state)) }
```
Eine Zeile, identisch für alle 5 Slots (Record, Send, Pause, Trash, Close). Keine Sonderfälle. ✓ — gleiches Pattern wie im `ImeViewBackend` (Spec 2 §6).

#### Slot → View-Property-Mapping (F-7 / Iteration 2026-05-08)

**Behauptung:** Die Property-Setter-Logik (visibility/enabled/alpha/icon/text) lebt
NICHT in jedem Backend dupliziert, sondern in **einer** Top-Level-Funktion, die
beide Backends aufrufen.

**Beweis:**
- Spec 2 §5.1 definiert `fun applySlotToView(slot, view, state, ctx): Boolean`.
- ImeViewBackend (§6 in Spec 2) ruft `applySlotToView(slot, view, state, ctx)` aus seinem `render`-Pfad.
- OverlayBackend (§4.2 oben) ruft denselben Helper aus `applySlots()`.

```kotlin
// Beide Backends:
mode.rows.flatMap { it.slots }.forEach { slot ->
    val view = buttonViews[slot.logicalId] ?: return@forEach
    applySlotToView(slot, view, state, ctx)   // ← geteilte Funktion
    // Click-Listener: backend-spezifisch (static im IME, pro Render im Overlay).
}
```

**Konsequenz:** Wenn die Property-Mapping um `contentDescription` oder `tint`
erweitert wird, gibt es **eine** Stelle (`applySlotToView` in Spec 2 §5.1), an der
das Setter-Pattern lebt. Beide Backends erben automatisch — Drift strukturell
unmöglich. ✓

#### Position-Konversion 0..1 ↔ Pixel (OPEN-3)

**Behauptung:** Die Konversion zwischen normalisierten 0..1-Koordinaten und absoluten
Pixeln existiert nur an EINER Stelle.

**Beweis:** `OverlayPositionMapper` (§4.7) hält beide Richtungen (`normalizedToPixels`,
`pixelsToNormalized`). Konsumenten:
- `OverlayBackend.applyPosition` ruft `normalizedToPixels` (State → Pixel beim Render).
- `DefaultOverlayDragHandler` ruft `pixelsToNormalized` (Drag-End → State).

KEINE Zweit-Implementierung in DragHandler oder Backend. Wenn die Formel sich ändert
(z.B. Anchor-Punkt-Wechsel), genau eine Stelle. ✓

#### Position-Persistierung (OPEN-3)

**Behauptung:** Persistierung der Overlay-Position läuft nur durch `PipelineStateManager`.

**Beweis:**
- `DefaultOverlayDragHandler` schreibt NICHT in SharedPreferences. Bei UP ruft er nur
  `onPositionPersist(normX, normY)`, was im `OverlayBackend` zu
  `onAction(Action.UpdateOverlayPosition(...))` wird.
- `PipelineStateManager.updateOverlayPosition` (Spec 1 §6.4) ist die einzige Methode, die
  `Pref.OverlayPosition*X/Y` schreibt + den State mutiert (atomar).
- Settings-Activity schreibt diese Prefs nicht (sie sind drag-only). Falls später ein
  "Reset Overlay Position"-Button gewünscht wird, geht der ebenfalls über
  `updateOverlayPosition(portrait, 1.0f, 0.1f)` — selbe Methode, kein Code-Duplikat. ✓

#### Permissions-Logik

**Behauptung:** Permission-Check existiert nur an EINER Stelle.

**Beweis:** `OverlayPermissionGate.hasOverlayPermission()` ist die einzige Quelle. Aufgerufen in:
- `PipelineStateManager.toggleViewMode()` (vor State-Mutation)
- `OverlayBackend.render()` (defensiv, vor Window-Attach)

Beide rufen denselben Gate. Kein duplizierter `Settings.canDrawOverlays()`-Aufruf. ✓

### §13.4 Cross-Spec-Konsistenz

#### Verwendet das OverlayBackend dasselbe `RenderBackend`-Interface wie der ImeViewBackend?

**Antwort: JA.** Spec 2 §5 definiert:
```kotlin
interface RenderBackend {
    fun attach(onAction: (Action) -> Unit)
    fun detach()
    fun render(state: DictateUiState, mode: LayoutMode)
}
```
`OverlayBackend` (§4.2) implementiert exakt dieses Interface. Der `KeyboardLayoutManager` arbeitet polymorph mit `RenderBackend`-Referenzen, kein `instanceof`-Check. ✓

#### Werden dieselben Slot/Action/Resolver-Patterns genutzt?

**Antwort: JA, und seit F-7 sogar wörtlich derselbe Code.** Vergleich nach F-7-Konsolidierung:

| Pattern | ImeViewBackend (Spec 2) | OverlayBackend (Spec 3) | Quelle |
|---------|--------------------------|--------------------------|--------|
| Slot-Iteration | `mode.rows.flatMap { it.slots }.forEach { ... }` | identisch | beide Render-Methoden |
| Visibility/Enabled/Alpha/Icon/Text | **`applySlotToView(slot, view, state, ctx)`** | **`applySlotToView(slot, view, state, ctx)`** | **Top-Level-Helper in Spec 2 §5.1 (F-7)** |
| Click | static in `wireStaticHandlers` (state-snapshot via `stateRef`/`modeRef` Field) | `view.setOnClickListener { onAction?.invoke(slot.actionResolver(state)) }` (pro Render) | backend-spezifisch begründet (Drag-Routing-Konflikt im Overlay) |

**Konsistenz-Beweis:** Mit F-7 ist das Property-Setter-Pattern identisch — eine Definition, zwei Aufrufer. GAP-1 (`.foreground` vs `.icon`-Inkonsistenz) ist damit obsolet, weil beide Backends durch denselben Helper laufen, der konsistent `.icon` verwendet.

#### Werden dieselben `Action`-Typen genutzt?

**Antwort: JA.** Spec 2 §3.3 definiert eine sealed `Action`. Beide Backends invokieren `onAction(Action)` — der Empfänger (`PipelineStateManager`) hat ONE Switch-Case-Block für alle Action-Typen. ✓

### §13.5 Identified Gaps + Mitigations

| ID | Gap | Mitigation |
|----|-----|------------|
| **GAP-1** | Spec 2 §6 nutzte ursprünglich `MaterialButton.foreground = drawable` für Icons; Spec 3 §4.2 nutzte `MaterialButton.icon = drawable`. | **RESOLVED via F-7 (Iteration 2026-05-08):** Beide Backends rufen jetzt den geteilten Top-Level-Helper `applySlotToView` (Spec 2 §5.1) auf, der konsistent `.icon` für MaterialButton-Icons verwendet. Die Inkonsistenz ist strukturell eliminiert — eine Definition, zwei Aufrufer. |
| **GAP-2** | `Action.MarkOverlayOnboardingShown` und `Action.DismissOverlayOnboarding` (in §5.3 erwähnt) sind in der Spec-2-§3.3-Action-sealed-Klasse noch nicht aufgelistet. | In Spec 2 §3.3 ergänzen, sobald Spec 2 implementiert wird. Beide Actions sind reine State-Toggles ohne Render-Konsequenz, daher trivial einzufügen. |
| **GAP-3** | `state.overlayOnboardingPending` (§5.3, §5.4) und `state.userPrefersWidget` (§7.3, §11.9) sind neue Felder im `DictateUiState`. Spec 1 §3 listet diese noch nicht. | In Spec 1 §3 ergänzen, sobald Spec 1 implementiert wird. Beide sind `Boolean`-Felder mit Default-`false`, additive Erweiterung — kein Migrations-Aufwand. |
| **GAP-4** | `LogicalButtonId.WIDGET_TOGGLE` (§7.3 T1) ist in Spec 2 §3.1 noch nicht aufgelistet — der Toggle-Button im IME-View muss als Slot ergänzt werden. | In Spec 2 §3.1-Liste eintragen + im KEYBOARD_TWO_ROW/SINGLE_ROW LayoutMode positionieren (vermutlich im `action_row` neben `audio_focus_btn`). Layout-Position als OPEN-Frage zu Spec 2 markieren. |
| **GAP-5** | Im T6-Pfad ("HOVER → WIDGET") gibt es einen subtilen Edge-Case: User klickt im HOVER auf Schließen → `closeOverlay()` setzt `viewMode = KEYBOARD`, aber `userPrefersWidget` bleibt unverändert (war zuvor `false`, sonst wäre HOVER nicht so erreicht worden). Konsistenz: HOVER-Schließen passt mit `userPrefersWidget=false`. ✓ Verifikation einer Inkonsistenz: wenn User in WIDGET → HOVER (T4) → HOVER-Schließen passiert: `userPrefersWidget` ist noch `true`. Wenn User dann Tastatur öffnet, kommt T6 → WIDGET. | Akzeptabel: das ist konsistent mit der Persistenz-Semantik ("WIDGET ist der vom User gewählte Modus, der per Default zurückkommt"). Kein Bug — als bewusste Eigenschaft in §11.9 dokumentiert. |
| **GAP-6** | Permissions-Revoke zur Laufzeit (User toggelt in Settings ab, während Overlay gerade sichtbar) löst KEIN Broadcast aus. Das Overlay bleibt sichtbar bis zum nächsten `render()`-Call. | Akzeptabler Edge-Case (sehr selten). Mitigation: kein Code-Hack; in §11.6 dokumentiert. Falls in Tests problematisch: `OverlayBackend.render()` könnte zusätzlich auf einem Timer alle 5 Minuten den Permission-Status pollen — overengineered für Phase 1. |
| **GAP-7** (OPEN-3) | View-Größe (`view.width`, `view.height`) ist beim ersten `applyPosition`-Call direkt nach `inflateAndAttach` noch `0`, weil das Layout-Pass noch nicht durchgelaufen ist. `pixelsToNormalized` würde mit `maxX/maxY = max(0, ...)` arbeiten und auf `0` clampen. | **RESOLVED via F-6 (Iteration 2026-05-08):** `inflateAndAttach` setzt einen `view.post { applyPosition(stateRef) }`-Hook nach dem `dragHandler.attach`. Der Callback feuert nach dem ersten Layout-Pass, wenn `view.width`/`height` korrekt sind, und triggert ein Re-Apply der Position. Damit ist der erste Frame korrekt positioniert. (Frühere Mitigation `view.measuredWidth` als Fallback bleibt zusätzlich in §4.7 als Defensive — wenn `post` aus irgendeinem Grund nicht durchläuft, verschwindet das Overlay nicht, sondern hängt am Default-Anker.) |
| **GAP-8** (OPEN-3) | Display-Größe wird über `ctx.resources.displayMetrics` ermittelt — bei Multi-Window/Split-Screen kann das vom WindowMetrics-API der Activity abweichen. | Akzeptabel: das Overlay ist `TYPE_APPLICATION_OVERLAY`, also display-global, nicht window-bound — `displayMetrics` ist die korrekte Quelle. Wenn später Multi-Window-aware-Positioning gewünscht (siehe §11.7), kann `WindowMetrics` injiziert werden. OCP via DI im `OverlayPositionMapper`. |

---

## §14 Test-Strategie

### §14.1 Unit-Tests (JVM, ohne Android-Runtime)

**Test-Targets:**
- `OverlayBackend` mit `FakeOverlayWindow`, `FakePermissionGate`, `FakeLayoutParamsFactory`.
- `DefaultOverlayPermissionGate` mit In-Memory-`SharedPreferences` (Robolectric oder Test-Double).
- `LayoutCatalog.OVERLAY_5BUTTON`-Resolver: Property-Tests gegen alle DictateUiState-Kombinationen (recording × pipeline × viewMode).

**Beispiel-Test:**

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

**Drag-spezifische Tests (OPEN-3):**

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

### §14.2 Espresso-/Instrumentation-Tests (Permission-Flow, Mock System Settings)

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

### §14.3 Manual-Test-Plan: Mode-Transitionen

| # | Übergang | Schritte | Erwartetes Ergebnis |
|---|----------|----------|---------------------|
| T1 | KEYBOARD → WIDGET | Recording starten, Widget-Toggle klicken | Overlay erscheint Top-End, IME-View bleibt sichtbar |
| T2 | WIDGET → KEYBOARD (mit SmallMode) | Im WIDGET den Schließen-Button klicken | Overlay verschwindet, IME-View wird kompakt (SmallMode) |
| T3 | KEYBOARD → HOVER | Recording starten, Tastatur schließen (Back-Button) | IME verschwindet, HOVER-Overlay erscheint mit Send disabled |
| T4 | WIDGET → HOVER | Im WIDGET die App wechseln, sodass IME-View hidden wird | Overlay-Layout bleibt sichtbar, aber Send disabled (Mode-Switch zu HOVER) |
| T5 | HOVER → KEYBOARD | Eingabefeld in App tappen (IME wird wieder geöffnet) | HOVER-Overlay verschwindet, IME-View kommt mit normaler Tastatur |
| T6 | HOVER → WIDGET | (Vorzustand: WIDGET → HOVER via T4). Eingabefeld tappen | HOVER-Overlay verschwindet, IME-View kommt im KEYBOARD-Modus, Widget-Toggle ist hervorgehoben (oder: Overlay bleibt + IME erscheint, je nach Implementierung von §7.3 T6) |

### §14.4 Sandbox/Device-Tests für WindowManager-Setup

| Test | Auf | Ziel |
|------|-----|------|
| Overlay Layer-Order | physisches Device + Emulator (API 26, 30, 34) | Overlay liegt über Tastatur-Window UND über Apps. Mehrere Hersteller (Samsung, Pixel, Xiaomi falls verfügbar). |
| Touch-Pass-Through | physisches Device | Touches außerhalb des Widgets gehen an die App durch (`FLAG_NOT_TOUCH_MODAL` wirkt). |
| Multi-Window Split-Screen | Pixel, Galaxy | Overlay bleibt sichtbar, Position konsistent. |
| PiP | Pixel | Overlay über PiP-Fenster, kein Z-Konflikt. |
| Lock-Screen | Pixel | Overlay erscheint NICHT auf Lock-Screen (ohne FLAG_SHOW_WHEN_LOCKED). |
| Permission-Revoke zur Laufzeit | Emulator | Overlay bleibt sichtbar bis zum nächsten render(); kein Crash. |
| Hardware-Keyboard-Connect | Pixel + USB-Keyboard | Tastatur-Eingaben gehen an die App, NICHT an unser Overlay (FLAG_NOT_FOCUSABLE wirkt). |

**Sandbox-Skript** (für gradle-managed AVD):
```bash
./gradlew :app:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.devemperor.dictate.overlay.OverlayWindowSetupTest
```
