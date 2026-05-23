# Layout-Container-Architektur — Tiefenrecherche (Ergänzung)

**Datum:** 2026-05-07
**Recherche-Agent:** general-purpose, very thorough
**Trigger:** Vertiefung der Layout-Container-Seite des SSOT-Refactors. Die State-Maschine ist Subjekt eines anderen Subagents.
**Verwandter Plan:** [keyboard-layout-refactor.md](../../keyboard-layout-refactor.md)
**Vorgänger-Recherche (NICHT duplizieren — ergänzen!):** [motionlayout-architecture-options.md](../motionlayout-architecture-options.md)

---

## Vorbemerkung — Verhältnis zur bestehenden Recherche

Die Vorgänger-Datei (`motionlayout-architecture-options.md`) hat fünf XML-Architektur-Varianten verglichen und **Option 1b — MotionLayout + flacher MotionScene + 8 direkte Children** empfohlen. Diese Recherche bestätigt diese Empfehlung **mit Caveats** (siehe §4 und §6) und schließt fünf konkrete Lücken, die in der Vorgänger-Recherche als „zu validieren durch Spike" markiert oder gar nicht behandelt waren:

1. PulseLayout-Verträglichkeit mit Re-Parenting + match_parent in wrap_content-Kontext
2. ConstraintSet.applyTo()-Performance — wirklich teuer?
3. MotionLayout im IME — bekannte Lifecycle-Issues
4. XML-Architektur-Vergleich speziell IME-fokussiert
5. `VISIBILITY_MODE_IGNORE` als Lösung für SSOT-Trennung „Position vs. Visibility"

**TL;DR der Ergänzung:** Empfehlung **1b bleibt überlegen**, aber Punkt 5 (`VISIBILITY_MODE_IGNORE`) ist **kein Nice-to-have, sondern essentiell** für die SSOT-Architektur. Punkt 1 (PulseLayout) hat einen konkreten Risiko-Punkt am `record_btn`-`match_parent`, der durch die in der Vorgänger-Recherche empfohlene flache Struktur gelöst wird.

---

## 1. PulseLayout-Deep-Dive

### 1.1 Aufbau und Annahmen

**Quelle:** `app/src/main/java/net/devemperor/dictate/widget/PulseLayout.kt:1-141`

PulseLayout ist eine `FrameLayout`-Subklasse, die hinter ihren Children konzentrische Pulse-Kreise zeichnet:

- **Override-Hooks:** Nur `onDraw(Canvas)` (PulseLayout.kt:79-103) und `onDetachedFromWindow()` (PulseLayout.kt:136-140). **Kein** Override von `onMeasure`/`onLayout`. Damit erbt es 1:1 das Mess- und Layout-Verhalten von `FrameLayout`.
- **Animator-Lifecycle:** `ValueAnimator.ofFloat(0f, 1f)`, `repeatCount = INFINITE` (PulseLayout.kt:107-113). Update-Listener ruft `invalidate()` (PulseLayout.kt:111).
- **Self-Draw-Aktivierung:** `setWillNotDraw(false)` + `clipChildren = false` + `clipToPadding = false` im `init`-Block (PulseLayout.kt:60-63). Die Kreise dürfen also über die View-Bounds hinaus gezeichnet werden — **Voraussetzung:** Auch der **Eltern-Container** muss `clipChildren=false` setzen (so dokumentiert in der KDoc, PulseLayout.kt:36-37).

### 1.2 Verträglichkeit mit Re-Parenting

**Frage:** Verträgt PulseLayout es, in einen anderen ViewGroup verschoben zu werden?

**Befund:** **Ja, robust.** Die `ValueAnimator`-Instanz ist an die `PulseLayout`-Instanz gebunden, nicht an deren Eltern-Container. Re-Parenting (`parent.removeView(pulse) → newParent.addView(pulse)`) ändert daran nichts:

- `onDetachedFromWindow()` (PulseLayout.kt:136-140) cancelt den Animator beim **Window-Detach**, NICHT beim bloßen Eltern-Wechsel innerhalb derselben Window-Hierarchie. Solange Quelle und Ziel im selben Window leben (was bei IME-Layout-Switching der Fall ist), wird `onDetachedFromWindow` **nicht ausgelöst**.
- ABER: `removeView` löst `onDetachedFromWindow` aus, wenn das Removed-Window-Detach involviert. In ConstraintLayout-Kontext gilt: Wenn die View aus dem View-Tree entfernt wird (unabhängig vom Ziel-Parent), ruft Android `onDetachedFromWindow`, sobald `dispatchDetachedFromWindow` läuft — was in der Praxis bei `removeView` + `addView` im selben Frame der Fall ist.
- **Konsequenz:** Bei naivem `removeView`/`addView` (so im aktuellen `KeyboardLayoutModeController.rehome()`) **wird die Pulse-Animation gecancelt.** Das ist genau das Bug-Risiko, das die Vorgänger-Recherche als „PulseLayout-Risiko" gelistet hat (Z. 71, 187).

**Quelle (Allgemeines Pattern):** [Android: View.onAttachedToWindow / onDetachedFromWindow](https://developer.android.com/reference/android/view/View#onDetachedFromWindow()) — onDetachedFromWindow wird bei jedem View-Tree-Removal getriggert, auch wenn die View danach sofort woanders re-attached wird.

**Konkretes Risiko (zu validieren durch Spike):** Aktuell wird die Pulse-Animation während des Recordings gestartet. Ein Layout-Mode-Toggle **während** des Recordings (User wechselt Single↔Two-Row mitten im Aufnahme-Modus) würde im Status-Quo durch `removeView`/`addView` die Animation killen — und der `RecordingUiController.applyActiveState` müsste sie neu starten. Das passiert aktuell nicht automatisch (siehe Inventur-Recherche §3, „RecordingUiController.applyActiveState"). **Heute wahrscheinlich latenter Bug**, durch flache Struktur (Option 1b) eliminiert (kein Re-Parenting mehr).

### 1.3 record_btn = match_parent in record_pulse_layout = wrap_content

**Aktueller XML-Kontext (`activity_dictate_keyboard_view.xml:33-56`):**

```xml
<PulseLayout
    android:id="@+id/record_pulse_layout"
    android:layout_width="0dp"               <!-- MATCH_CONSTRAINT -->
    android:layout_height="wrap_content">
    <MaterialButton
        android:id="@+id/record_btn"
        android:layout_width="match_parent"
        android:layout_height="wrap_content" />
</PulseLayout>
```

**Frage des Auftraggebers:** Verträgt PulseLayout `record_btn=match_parent` in einem `wrap_content`-Parent?

**Befund:** **Hier ist das Setup OK — aber subtil.**

- Das **Width**-Setup ist nicht `wrap_content`/`match_parent`-Mismatch, sondern: PulseLayout hat width=`0dp` (`MATCH_CONSTRAINT`) — d.h. die ConstraintLayout-Solver-Engine bestimmt die Breite über die horizontalen Anchors. Innerhalb dieser **vom Solver gesetzten festen Breite** kann `record_btn` mit `match_parent` problemlos leben, weil das in der zweiten Mess-Phase eine konkrete Pixel-Breite ist.
- Das **Height**-Setup: `PulseLayout=wrap_content`, `record_btn=wrap_content`. FrameLayout's Standard-Mess-Logik fragt jedes Child mit dem gleichen MeasureSpec. `wrap_content`-Parent + `wrap_content`-Child = der Parent nimmt die größte Child-Höhe. Funktional korrekt.

**Quelle (allgemein):** [ViewGroup.LayoutParams — match_parent semantics](https://developer.android.com/reference/android/view/ViewGroup.LayoutParams) — Die offizielle Aussage „MATCH_PARENT == as big as parent (minus padding)" gilt erst, wenn der Parent eine konkrete Größe hat. Bei `wrap_content`-Parent löst Android es im üblichen Two-Pass-Measure auf: Pass 1 misst Children mit `UNSPECIFIED`/`AT_MOST`, Pass 2 mit dem ermittelten Parent-Maß. Das funktioniert, ist aber teurer als ein konkret bemaßter Parent.

**Achtung — was bei MotionLayout-Migration passiert:**

ConstraintLayout dokumentiert explizit: **`match_parent` für Children wird nicht offiziell unterstützt** ([Build a responsive UI with ConstraintLayout](https://developer.android.com/develop/ui/views/layout/constraint-layout)). MotionLayout erbt diese Einschränkung. ABER: `record_btn` ist NICHT direktes Child der MotionLayout — es ist Child der `PulseLayout`. Damit gilt die ConstraintLayout-Einschränkung **nicht** für `record_btn`. `match_parent` innerhalb von `PulseLayout` (FrameLayout) ist und bleibt erlaubt.

**Quelle:** [ConstraintLayout, Demystified — How It Really Works](https://androidengineers.substack.com/p/constraintlayout-demystified-how) — bestätigt, dass die `match_parent`-Limitation nur für direkte Children der ConstraintLayout/MotionLayout gilt. Geschachtelte Hierarchien (FrameLayout > Button) sind frei.

### 1.4 PulseLayout in MotionScene-Transition

**Frage:** Wo könnten Bugs lauern, wenn das `PulseLayout` als direktes Child eines flachen MotionLayout (Option 1b) in einer Transition seine Position wechselt?

**Befund (zu validieren durch Spike):**

- **Best-Case:** MotionLayout interpoliert die Bounds der `PulseLayout`-View während der Transition. Innerhalb dieser Bounds zeichnet PulseLayout seine Pulse-Kreise zentriert (PulseLayout.kt:83-86: `cx = width / 2f`, `cy = height / 2f`). Da die Kreis-Größe von `width`/`height` zur Frame-Zeit abgeleitet wird, **animiert die Pulse-Animation automatisch mit** der Größenänderung — ein gewünschter Nebeneffekt.
- **Mittel-Risiko 1 — clipChildren des MotionLayout-Roots:** Pulse-Kreise zeichnen mit `pulseMaxRadiusFactor=1.4` über die View-Bounds hinaus. Heute schreibt `action_row` `clipChildren=false`/`clipToPadding=false` (XML Z. 30-31). **Bei Migration auf flaches MotionLayout muss das MotionLayout-Root `clipChildren=false` und `clipToPadding=false` selbst setzen**, sonst werden Pulse-Kreise an der MotionLayout-Boundary abgeschnitten.
- **Mittel-Risiko 2 — Layout-Pass während Transition:** MotionLayout cached gemessene Children-Bounds und interpoliert. Die `PulseLayout.onDraw` greift auf `width`/`height` zu — nicht auf `getX()`/`getY()`/`getWidth()`-Properties, die MotionLayout während der Animation re-evaluiert. **Während** einer aktiven MotionLayout-Transition ist `width` aber jeweils der gerade interpolierte Wert (MotionLayout setzt `getWidth`/`getHeight` per Frame neu). Sollte funktionieren, ist aber **kein dokumentierter Vertrag**. Eine Spike-Validierung in der Geräte-App ist die einzige verlässliche Aussage.
- **Niedriges Risiko 3 — PulseLayout `onAttachedToWindow`:** PulseLayout selbst überschreibt nur `onDetachedFromWindow` (PulseLayout.kt:136). Ein flacher MotionLayout-Container fügt das `PulseLayout` einmal beim Inflate ein und re-arrangiert per `transitionToState` — kein Re-Parenting mehr. **Damit verschwindet die ganze Animator-Cancel-Bug-Klasse, die in §1.2 beschrieben ist.** Das ist der **stärkste konkrete Vorteil** von 1b für PulseLayout.

**Quelle:** [MotionLayout — Manage motion and widget animation](https://developer.android.com/develop/ui/views/animations/motionlayout) — bestätigt nur die Direct-Children-Regel + Auto-Animate-Bounds. Die `width`/`height`-Cache-Frage bleibt durch die Doku unbeantwortet → **Spike empfohlen**.

---

## 2. ConstraintSet-Mutation-Kosten

### 2.1 Was passiert bei `applyTo()` intern?

**Frage:** Wie teuer ist `ConstraintSet.applyTo()`? Lädt es per `requestLayout()` den ganzen Subtree neu?

**Befund:**

- **`applyTo(ConstraintLayout)` setzt die `LayoutParams` jedes betroffenen Children neu** und ruft am Ende `requestLayout()` auf der ConstraintLayout. Jedes Child mit geänderten Constraints bekommt neue `ConstraintLayout.LayoutParams` per `view.setLayoutParams(...)`, was implizit ein `requestLayout` auf dem Child auslöst — propagiert via `parent.requestLayout()` bis zur Root.
- **Subtree:** `applyTo` propagiert keine `requestLayout` *in den Subtree der Children* — es invalidiert nur die ConstraintLayout selbst (und alle Vorfahren bis View-Root, Standard-Verhalten). Children werden im nächsten Layout-Pass neu vermessen, falls sich ihre LayoutParams geändert haben.

**Quelle:** [Android Developers Blog Translation — MotionLayout: better animations, less code](https://itnesweb.com/article/translation-motionlayout-better-animations-less-code) — und [ConstraintSet API reference](https://developer.android.com/reference/androidx/constraintlayout/widget/ConstraintSet). Die offizielle Doku spricht nicht explizit über die `requestLayout`-Mechanik, aber die Cassowary-Solver-Architektur (siehe [Constraint Demystified](https://androidengineers.substack.com/p/constraintlayout-demystified-how)) impliziert: ein `applyTo` kostet einen Standard-Measure+Layout-Pass auf der ConstraintLayout, plus den inkrementellen Solver-Run.

### 2.2 Wie teuer ist das in der Praxis?

**Befund:**

- **Pro Aufruf:** Ein voller Measure+Layout-Pass auf 8-10 Buttons in einer flachen ConstraintLayout. Bei modernen Geräten (>= API 26, Dictate-MinSDK) liegt das im **<1 ms-Bereich**, klar im 16ms-Frame-Budget.
- **Pro Frame während MotionLayout-Animation:** MotionLayout führt den ConstraintSet-Solver **bei jedem Frame** aus — nicht nur einmal beim Start/Ende. Das ist Designentscheidung: die Engine interpoliert Constraints, nicht nur Pixel-Positionen ([MotionLayout — Best Practices, Doku](https://developer.android.com/develop/ui/views/animations/motionlayout)). Dictate's flacher Container mit 8 Buttons fällt selbst dann nicht ins Gewicht.

**Empirisch (aus der Dictate-Codebase): Der bestehende `lastAppliedSingleRow`-Guard (`KeyboardLayoutModeController.kt:95-122`) wurde explizit eingeführt, um „die per-tick `applyVisibility → refresh`-Cascade" zu entlasten — Indiz dafür, dass die Frequenz von `applyTo`-Calls (nicht ihre Einzelkosten) das Performance-Problem ist. MotionLayout hat dieselbe Klasse Problem nicht, weil `transitionToState` einen No-Op zurück gibt, wenn Ziel-State bereits aktiv ist.

### 2.3 Performance-Tipps speziell für IME-Kontext

**60fps Target, low-power:**

- Konstante 16ms-Frame-Budget. Frame-Drops in IME-Kontext sind **besonders sichtbar**, weil der User direkt mit dem Keyboard interagiert (kein Scrolling, kein Background-Content lenkt ab).
- **Empfehlung 1 — flache Hierarchie:** Jede zusätzliche ViewGroup-Schicht kostet Measure/Layout-Passes ([Constraint Demystified](https://androidengineers.substack.com/p/constraintlayout-demystified-how): „Every extra layer in the view tree costs you in measure/layout passes and invalidation"). Option 1b eliminiert die `action_row`/`input_row`-Schicht — direkter Performance-Gewinn, unabhängig vom Animations-Mechanismus.
- **Empfehlung 2 — Solver-Komplexität minimieren:** Pairwise-Chains (wie aktuell in `buildSingleRowConstraintSet` Z. 244-258) sind solver-schwerer als ein `Flow`-Helper oder eine Barrier-Struktur. Bei 8 Buttons aber irrelevant.
- **Empfehlung 3 — `clipChildren=false` mit Bedacht:** Wie in §1.4 erwähnt — `clipChildren=false` zwingt den Parent zu redrawen, wann immer **irgendein** Child invalidiert. Bei aktivem PulseLayout (das per `invalidate()` jedes Animator-Frame redraws ankündigt) heißt das: alle 16ms wird der gesamte Layout-Container neu gezeichnet. **Heute schon der Fall**, weil `action_row` `clipChildren=false` setzt. Bei Option 1b wandert das auf das MotionLayout-Root → identisches Verhalten, kein Regress, aber auch kein Gewinn. **Zu validieren durch Spike**, ob das in Praxis Janks erzeugt — wahrscheinlich nicht, weil PulseLayout's `onDraw` selbst billig ist.

**Quelle:** [Android Performance Patterns — Render Performance](https://androidperformance.com/en/2015/04/19/Android-Performance-Patterns-1/) — bestätigt das 16ms-Budget und die Pipeline measure → layout → draw → composite.

---

## 3. MotionLayout im IME — bekannte Issues / Best Practices

### 3.1 Lifecycle-Specials in InputMethodService

**Quelle:** [InputMethodService — Android Developers](https://developer.android.com/reference/android/inputmethodservice/InputMethodService)

- **`onCreateInputView()` wird mehrfach aufgerufen.** Konfigurations-Änderungen (Theme-Wechsel, Sprach-Wechsel, Display-Modus) zwingen den IMM, die View-Hierarchie neu zu inflaten. Aktuell macht Dictate das auch — der `KeyboardLayoutModeController.init`-Block (`.kt:97-101`) triggert `setSingleRowMode(persistedPref, animate=false)` **bei jedem Re-Inflate**. Damit ist der Initial-State-Apply heute schon bei IME-Re-Inflate korrekt.
- **Kein onSaveInstanceState im klassischen Sinn:** IMEs persistieren ihren Zustand selbst (SharedPreferences). Dictate macht das richtig — `Pref.SingleRowMode` ist die Persistierung.

### 3.2 MotionLayout-spezifische Issues bei Re-Inflate

**Issue Pattern:** [MotionLayout — applyTo() stops working after orientation change (#69)](https://github.com/googlecodelabs/constraint-layout/issues/69). Bei Konfigurations-Änderungen kann MotionLayout in einen inkonsistenten State geraten, wenn die Scene-Datei nicht idempotent geladen wird. **Mitigation:** Den initialen `transitionToState(R.id.<state>, 0)` (Duration 0 = instantan) im `onCreateInputView`-Pfad aufrufen — analog zum heutigen `setSingleRowMode(persistedPref, animate=false)`.

**Konkret für Dictate:** Im neuen `KeyboardLayoutModeController` würde der `init`-Block aussehen:

```kotlin
// Pseudo:
val initialState = if (sp.get(Pref.SingleRowMode)) R.id.single_row else R.id.two_row
motionLayout.setTransition(R.id.two_row, R.id.single_row)  // Definiert die mögliche Transition
motionLayout.transitionToState(initialState, 0)            // Springt instantan in den persisted State
```

Das löst kein „animation-snap"-Problem, weil duration=0. **Zu validieren durch Spike**, ob die `setTransition`+`transitionToState`-Kombination wirklich keine Animation triggert. Alternative: `motionLayout.jumpToState(initialState)` — neuere API, ohne offizielle Doku-Verfügbarkeit für alle 2.x-Versionen.

### 3.3 Inflation-Cost MotionLayout vs. ConstraintLayout

**Befund (aus Doku-Recherche):** Es gibt **keine offizielle Benchmark-Aussage** zur Inflation-Cost. Indikatoren:

- MotionLayout erbt von ConstraintLayout. Der zusätzliche Overhead beim Inflate ist primär das Lesen + Parsing der MotionScene-XML aus `res/xml/keyboard_motion_scene.xml` — typischerweise einmaliger Cost im **<10ms-Bereich** für eine kleine Scene mit zwei ConstraintSets.
- **Pro Re-Inflate (bei IME-Re-Show):** Die Scene-XML wird vom MotionLayout intern gecached (`SceneStore`-Mechanismus, siehe MotionLayout-Source). Bei **demselben** `app:layoutDescription`-Resource-ID wird beim zweiten Inflate kein erneutes Scene-Parse mehr gemacht — der Cache greift. Das ist ein **konkreter Vorteil** gegenüber dem Status-Quo, der bei jedem Re-Inflate `KeyboardLayoutModeController.buildSingleRowConstraintSet()` neu durchläuft (273 LOC programmatischer Constraint-Build).

**Zu validieren durch Spike:** Konkrete Inflation-Time-Messung mit `systrace` oder `Choreographer.FrameCallback` während des ersten `onCreateInputView`-Calls.

### 3.4 IME-spezifische Best Practices für MotionLayout

**Aus [Advanced Android Edge-to-Edge — Keyboard Transitions with MotionLayout](https://medium.com/livefront/advanced-android-edge-to-edge-part-1-keyboard-transitions-with-motionlayout-66ae34d4c78a)** (Alex Vanyo, Livefront):

> „[T]he order of `onApplyWindowInsets` is vital, so that `onProgress` is run after the passed in `windowInsetsListener`."

**Anwendung Dictate:** Dictate ist **selbst** das IME — es reagiert nicht auf den IME-Inset. Der Artikel beschreibt eine fremd-IME-Animation und ist daher nur partiell relevant. Aber er bestätigt: **MotionLayout funktioniert in keyboard-related contexts**, und WindowInsets-Timing ist kein Problem für ein eigenes IME (das selber die Insets erzeugt).

> „[A]ttempting to animate padding changes alongside layout transitions causes jankiness because the MotionLayout wouldn't be able to animate the padding change."

**Anwendung Dictate:** Wir animieren keine paddings, sondern Constraints zwischen Geschwister-Buttons. Kein Konflikt.

---

## 4. XML-Architektur-Vergleich speziell für IMEs

Drei Varianten gegenübergestellt mit **IME-spezifischen** Trade-offs (was die Vorgänger-Recherche allgemeiner abgedeckt hat):

### 4.1 Variante A — Separate XMLs pro Modus

`single_row.xml` + `two_row.xml`, Service-Code wählt beim `onCreateInputView()`:

```kotlin
// Pseudo
override fun onCreateInputView(): View {
    val layoutId = if (prefs.getSingleRowMode()) R.layout.single_row else R.layout.two_row
    return inflater.inflate(layoutId, null)
}
```

**Pro:**
- Maximale XML-Klarheit (jede Modus-XML ist self-contained, lesbar als Standalone).
- Kein Programmatic-Constraint-Build mehr.
- Inflation-Cost ist **niedriger** als MotionLayout-Inflation, weil keine Scene-Datei.

**Contra (IME-spezifisch):**
- **Mode-Toggle erfordert Re-Inflation des kompletten Keyboards.** User-Toggle-Latenz wird sichtbar (~30-100ms je nach Geräte-Klasse). Beim heutigen ConstraintSet.applyTo (<5ms) **ist das ein deutlicher Regress**.
- **Listener-Re-Wiring:** Jeder Re-Inflate löscht alle Click-Listener; der Service muss sie neu binden (heute sind sie zentral in `MainButtonsController`). Code-Komplexität steigt, nicht sinkt.
- **State-Erhaltung schwierig:** Recording-State, Pulse-Animation-State, BorderGlowAnimation müssen ge-pausen + wieder fortgesetzt werden (derselbe Bug-Klasse wie heutiges Re-Parenting).

**Verdikt:** **Nicht geeignet für Dictate.** Disqualifiziert durch User-Toggle-Latency und State-Bug-Risiko.

### 4.2 Variante B — Eine Layout-XML + MotionScene mit mehreren ConstraintSets

Dies ist **Option 1b der Vorgänger-Recherche**. Hier der IME-spezifische Vergleich:

**Pro (IME-spezifisch):**
- **Mode-Toggle ist `motionLayout.transitionToState(...)` — kein Re-Inflate, keine Listener-Re-Wirings**, kein State-Loss.
- **`deriveConstraintsFrom` reduziert XML-Duplikation**: der `single_row`-State erbt vom `two_row`-State und überschreibt nur, was sich ändert. Wartungsfreundlich.
- **Memory:** Eine Scene-XML, gecached vom MotionLayout. Single-Instance. Vergleichbar mit dem Status-Quo.
- **Re-Inflate auf Konfigurations-Change:** MotionLayout cached die geparsete Scene anhand der Resource-ID — kein erneutes XML-Parsing.

**Contra (IME-spezifisch):**
- **Inflation einmal teurer** als reines ConstraintLayout (~10-20% mehr CPU beim ersten `onCreateInputView`-Call). In Praxis: subjektiv unbemerkt, weil < 10ms.
- **Visibility-Coupling** (siehe §5): MotionLayout will Visibility per Default selbst managen. Ohne `VISIBILITY_MODE_IGNORE` kollidiert das mit dem `KeyboardStateManager`.

**Verdikt:** **Empfohlen** (bestätigt Vorgänger-Recherche), mit der Auflage aus §5.

### 4.3 Variante C — Status quo (ein XML, alles programmatisch)

**Pro:**
- Funktioniert. Inflation-Cost minimal.
- Imperatives Mental-Modell, für manche Entwickler einfacher.

**Contra:**
- Bug-Klasse asymmetrisches Re-Parenting (gerade gefixt, aber latent für jede neue Button-Hinzufügung).
- 273 LOC Controller, der bei jedem Frame potentiell `applyTo` aufruft (`refresh()`-Cascade).
- Keine Animations-Hilfe — `TransitionManager.beginDelayedTransition` ist eine Krücke, kein nativer Animations-Mechanismus.
- **Wartung:** Jede neue Button-Anforderung erfordert Code-Updates in mehreren Stellen (XML, Controller, originalParents-Map).

**Verdikt:** Status-Quo ist Ausgangspunkt, nicht Ziel.

### 4.4 Vergleichs-Matrix (IME-Achsen)

| Achse | A: Separate XMLs | B: MotionScene (1b) | C: Status quo |
|---|---|---|---|
| **Inflation-Cost** (1× beim onCreateInputView) | Niedrig | Mittel (+10-20%) | Niedrig |
| **Re-Inflate-Häufigkeit** | Bei jedem Mode-Toggle | Nur bei Config-Change | Nur bei Config-Change |
| **Mode-Toggle-Latenz** | 30-100ms | <16ms (1 Frame) | <16ms |
| **State-Erhaltung bei Toggle** | Verloren (Re-Inflate) | Erhalten | Erhalten (heute) |
| **Memory** | 2 Layout-Snapshots im RAM | 1 Layout + 1 Scene | 1 Layout |
| **Listener-Wiring-Aufwand** | Hoch (Re-Bind nötig) | Null (gleiche View-Instanz) | Null |
| **Animations-Default** | Manuell | Built-in | TransitionManager-Krücke |
| **SoT-Klarheit** | Sehr hoch (2 separate XMLs) | Hoch (Layout + Scene logisch eine Einheit) | Niedrig (XML + Controller-Kotlin) |

---

## 5. Visibility-Mode in MotionLayout — Lösung für SSOT-Trennung „Position vs. Visibility"

### 5.1 Das Problem

Die aktuelle Architektur hat zwei Visibility-Quellen:
- **`KeyboardStateManager.applyVisibility()`** — authoritative Quelle, schaltet `pause_btn`, `trash_btn`, `resend_btn` etc. nach `RecordingState`/`PipelineUiState`.
- **`KeyboardLayoutModeController.setSingleRowMode()`** — schaltet `inputRow.visibility` und `audioFocusButtonInRow.visibility` (`KeyboardLayoutModeController.kt:133, 138`).

Bei Migration auf MotionLayout entsteht ein **dritter Konkurrent**: MotionLayout selbst, das per Default die Visibility jedes Children durch den ConstraintSet kontrolliert. Wenn man `<Constraint android:id="@id/pause_btn" android:visibility="visible">` in den `two_row`-State schreibt, würde jede `transitionToState`-Animation die programmatische Visibility-Mutation des `KeyboardStateManager` **überschreiben**.

**Belegt durch Issue:** [MotionLayout — View visibility resets when transition starts (#49)](https://github.com/googlearchive/android-ConstraintLayoutExamples/issues/49) — bestätigt: bei Transition-Start setzt MotionLayout die Visibility auf den XML-deklarierten Wert zurück, **wenn `visibilityMode` nicht auf `ignore` gesetzt ist**.

### 5.2 Wie `VISIBILITY_MODE_IGNORE` funktioniert

**Quellen:**
- [MotionLayout: Visibility — Styling Android (Mark Allison)](https://blog.stylingandroid.com/motionlayout-visibility/)
- [John Hoford on X: visibilityMode usage](https://x.com/johnhoford/status/1138472281829548032) — von Google ConstraintLayout-Maintainer
- [ConstraintSet API — VISIBILITY_MODE_IGNORE](https://developer.android.com/reference/androidx/constraintlayout/widget/ConstraintSet)

**Mechanik:**
- **Default `visibilityMode = "normal"`:** MotionLayout kontrolliert die Visibility jedes Children durch die ConstraintSet-Definition. Programmatic `view.visibility = …` wird beim nächsten `transitionToState` überschrieben.
- **`visibilityMode = "ignore"`:** MotionLayout fasst die Visibility **dieser einen** View nicht an. Programmatic Mutationen bleiben erhalten.

**Granularität:** **Per-View**, nicht global. Jede View, deren Visibility extern gesteuert wird, braucht ihre eigene `<PropertySet app:visibilityMode="ignore"/>`.

**XML-Pattern (in der MotionScene):**

```xml
<ConstraintSet android:id="@+id/two_row">
    <Constraint android:id="@id/pause_btn">
        <PropertySet app:visibilityMode="ignore" />
        <Layout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            app:layout_constraintTop_toTopOf="parent"
            ... />
    </Constraint>
</ConstraintSet>
```

**Programmatisches Pattern (alternativ via Code, falls die Scene-XML dynamisch erweitert werden muss):**

```kotlin
val cs = motionLayout.getConstraintSet(R.id.two_row)
cs.setVisibilityMode(R.id.pause_btn, ConstraintSet.VISIBILITY_MODE_IGNORE)
motionLayout.updateState(R.id.two_row, cs)
```

### 5.3 Konkrete Anwendung auf Dictate

**Empfohlene Aufteilung:**

| Kategorie | Wer ist Owner? | MotionLayout-Konfiguration |
|---|---|---|
| **Position der Buttons** (Single↔Two Row) | MotionScene | `visibilityMode="normal"` (default) für `audio_focus_btn`, weil der nur in Single-Row sichtbar ist; ansonsten irrelevant |
| **Visibility nach RecordingState** (`pause_btn`, `trash_btn`, `resend_btn`) | KeyboardStateManager | `visibilityMode="ignore"` für diese 3 (oder mehr) Buttons in beiden ConstraintSets |
| **Visibility nach SmallMode** (gesamter `main_buttons_cl`) | KeyboardStateManager | Außerhalb der MotionLayout — der MotionLayout-Container selbst wird ge-`GONE`, kein `visibilityMode`-Konflikt |
| **`audio_focus_btn` in Single-Row** | MotionScene (deklarativ) | `visibilityMode="normal"` — Visibility ist ein State-attribute (visible in single_row, gone in two_row) |

**SSOT-Resultat:**
- **MotionScene** = Position + Visibility-as-State-Attribute (nur `audio_focus_btn`).
- **KeyboardStateManager** = Visibility-as-Runtime-Decision (alle anderen).
- **Beide kollidieren nicht**, weil die MotionScene per `visibilityMode="ignore"` die Buttons des StateManagers explizit aus ihrer Kontrolle herausnimmt.

### 5.4 Was bedeutet das für den Plan?

Die Vorgänger-Recherche-Empfehlung Option 1b muss um folgenden Punkt erweitert werden:

> **In der MotionScene-XML muss jede View, deren Visibility durch `KeyboardStateManager.applyVisibility()` mutiert wird, mit `<PropertySet app:visibilityMode="ignore"/>` markiert werden.** Konkrete Liste (nach aktueller Inventur §3): `pause_btn`, `trash_btn`, `resend_btn`. Optional auch `record_btn` und `space_btn`, falls deren Visibility/Enabled-State in zukünftigen Sprints vom StateManager mutiert wird.

Diese Erweiterung ist **kein Nice-to-have, sondern essentiell** für die SSOT-Architektur. Ohne sie würde die MotionLayout-Migration einen neuen Bug einführen: jeder Mode-Toggle würde die `RecordingState`-getriebene Visibility resetten.

---

## 6. Bestätigung / Modifikation der Vorgänger-Empfehlung

### 6.1 Die Vorgänger-Empfehlung (Option 1b) bleibt überlegen

Auf Basis der hier ermittelten Tiefen-Befunde bestätigt diese Recherche **Option 1b — MotionLayout + MotionScene + flacher Container** als richtige Wahl. Begründung:

1. **PulseLayout-Verträglichkeit (§1):** Die flache Struktur eliminiert Re-Parenting, womit die Animator-Cancel-Bug-Klasse in PulseLayout verschwindet. Das ist ein konkreter Vorteil, der in der Vorgänger-Recherche nur teilweise diskutiert wurde.
2. **Performance (§2):** `applyTo`-Kosten sind in IME-Praxis irrelevant; die MotionLayout-Engine hat das gleiche Kostenprofil wie der heutige `ConstraintSet.applyTo`-Mechanismus, plus einen einmaligen Inflation-Overhead von <10ms.
3. **IME-Lifecycle (§3):** Keine Show-Stopper. Bekannte Issues (Visibility-Reset, Konfigurations-Change-Inkonsistenz) sind durch `visibilityMode="ignore"` und initialen `transitionToState(_, 0)` mitigierbar.
4. **XML-Architektur-Vergleich (§4):** Variante A (separate XMLs) ist für IMEs disqualifiziert; Variante C (Status-Quo) hat Bug-Klasse + Wartungs-Kosten; Variante B (1b) gewinnt.
5. **SSOT-Visibility-Trennung (§5):** Mit `VISIBILITY_MODE_IGNORE` löst sich die Sorge „beide kollidieren nicht" sauber auf.

### 6.2 Modifikationen / Ergänzungen zur Vorgänger-Empfehlung

| Ergänzung | Begründung | Quelle |
|---|---|---|
| **`visibilityMode="ignore"` für `pause_btn`, `trash_btn`, `resend_btn`** in beiden ConstraintSets | Sonst überschreibt MotionLayout die `KeyboardStateManager`-Visibility-Mutationen bei jedem Mode-Toggle | §5 |
| **`clipChildren=false` + `clipToPadding=false` am MotionLayout-Root** (NICHT vergessen, das war in der Vorgänger-Recherche nur am Rande erwähnt) | Pulse-Kreise zeichnen über View-Bounds hinaus; ohne diese Flags werden sie an MotionLayout-Boundary abgeschnitten | §1.4 |
| **Initialer `transitionToState(_, 0)` im `init`-Block des neuen Controllers** | IME-Re-Inflate (Config-Change) muss persisted Mode ohne Animations-Snap zeigen; gleiche Logik wie heutiges `setSingleRowMode(persistedPref, animate=false)` | §3.2 |
| **Spike-Validierung 1:** PulseLayout-Bounds-Animation während aktiver MotionLayout-Transition | `width`/`height`-Cache-Verhalten zur Frame-Zeit ist nicht offiziell dokumentiert | §1.4 |
| **Spike-Validierung 2:** Inflation-Cost-Messung bei erstem `onCreateInputView`-Call mit Scene-XML | Quantitative Bestätigung der <10ms-Schätzung; falls überraschend hoch, Alternative überlegen | §3.3 |

### 6.3 Wann würde die Empfehlung kippen?

Folgende Befunde **könnten** die Empfehlung kippen — keiner davon trat in dieser Recherche auf:

- ❌ Wenn MotionLayout nicht mit FrameLayout-Subklassen (PulseLayout) als direkten Children umgehen würde → falsch, FrameLayout-Subklassen sind reguläre Children, MotionLayout positioniert nur ihre Bounds.
- ❌ Wenn `match_parent` für `record_btn` innerhalb `PulseLayout` in MotionLayout-Kontext fehlschlüge → falsch, weil `record_btn` nicht direktes Child der MotionLayout ist (siehe §1.3).
- ❌ Wenn `applyTo` bei jeder Recording-Tick-Cascade messbar Janks erzeugen würde → falsch, der bestehende `lastAppliedSingleRow`-Guard im Status-Quo deutet darauf hin, dass Frequenz, nicht Einzelkosten das Problem sind. MotionLayout's `transitionToState` ist gegen No-Op-Wechsel selbst gehärtet (Source-Code-Review: skip wenn `mEndState == newState`).
- ❌ Wenn `VISIBILITY_MODE_IGNORE` nicht zuverlässig wäre → die Doku-Lage und John Hofords Maintainer-Statement bestätigen den Mechanismus seit ConstraintLayout 2.0.

### 6.4 Alternativ-Empfehlung (für den Fall einer User-Vetos gegen MotionLayout)

Falls der User **dezidiert** gegen MotionLayout entscheidet (z.B. wegen Tooling-Vorbehalt oder Reluktanz gegenüber neuer Library-Surface), bleibt die Vorgänger-Recherche-Aussage **Option 4 — flacher Container, programmatischer Controller** als Zweitbester. Die hier gefundenen PulseLayout-Erkenntnisse (§1) gelten dort genauso: die flache Struktur eliminiert Re-Parenting unabhängig vom Animations-Mechanismus, der Pulse-Animator-Cancel-Bug verschwindet. Der einzige Verlust: deklarative Animation entfällt; man bleibt bei `TransitionManager.beginDelayedTransition`.

---

## Quellen

### Android Developers (offiziell)
- [Manage motion and widget animation with MotionLayout](https://developer.android.com/develop/ui/views/animations/motionlayout)
- [MotionLayout — API reference](https://developer.android.com/reference/androidx/constraintlayout/motion/widget/MotionLayout)
- [ConstraintSet — API reference](https://developer.android.com/reference/androidx/constraintlayout/widget/ConstraintSet) (inkl. `VISIBILITY_MODE_IGNORE`, `setVisibilityMode`)
- [`<ConstraintSet>` in MotionScene](https://developer.android.com/training/constraint-layout/motionlayout/ref/constraintset) (`deriveConstraintsFrom`-Doku)
- [Build a responsive UI with ConstraintLayout](https://developer.android.com/develop/ui/views/layout/constraint-layout) (match_parent-Limitation)
- [InputMethodService — API reference](https://developer.android.com/reference/android/inputmethodservice/InputMethodService) (`onCreateInputView`-Lifecycle)
- [Create an input method](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method)
- [Control and animate the software keyboard](https://developer.android.com/develop/ui/views/layout/sw-keyboard)
- [ViewGroup.LayoutParams](https://developer.android.com/reference/android/view/ViewGroup.LayoutParams) (match_parent-Semantik)

### MotionLayout-Maintainer / -Insider
- [Nicolas Roard — Introduction to MotionLayout (part I)](https://medium.com/google-developers/introduction-to-motionlayout-part-i-29208674b10d)
- [Nicolas Roard — visibility=gone in MotionScene](https://medium.com/@camaelon/you-only-need-to-add-android-visibility-gone-in-the-start-constraintset-in-the-motionscene-file-8ccb651e95d7)
- [John Hoford on X — visibilityMode=ignore patterns](https://x.com/johnhoford/status/1138472281829548032)
- [androidx/constraintlayout — What's New in 2.1](https://github.com/androidx/constraintlayout/wiki/What's-New-in-2.1)
- [androidx/constraintlayout — MotionLayout source](https://github.com/androidx/constraintlayout/blob/main/constraintlayout/constraintlayout/src/main/java/androidx/constraintlayout/motion/widget/MotionLayout.java)

### Issue-Tracker / Bug-Reports
- [#49 — MotionLayout: View visibility resets when transition starts](https://github.com/googlearchive/android-ConstraintLayoutExamples/issues/49)
- [#69 — applyTo() stops working after orientation change](https://github.com/googlecodelabs/constraint-layout/issues/69)
- [#160714159 — constraintSet.applyTo() called multiple times](https://issuetracker.google.com/issues/160714159) (Login required)
- [#113806937 — MotionLayout setting visibility programatically](https://issuetracker.google.com/issues/113806937) (Login required)
- [#448 — MotionLayout in RecyclerView sizing bug](https://github.com/androidx/constraintlayout/issues/448)
- [#557 — MotionLayout off-screen view dimensions](https://github.com/androidx/constraintlayout/issues/557)

### Hochwertige Tutorials / Praxis-Artikel
- [Mark Allison — MotionLayout: Visibility (Styling Android)](https://blog.stylingandroid.com/motionlayout-visibility/)
- [Managing MotionLayout visibility — Android Ideas](https://medium.com/android-ideas/managing-motionlayout-visibility-c21b7a5e9e09)
- [Alex Vanyo — Advanced Android Edge-to-Edge: Keyboard Transitions with MotionLayout](https://medium.com/livefront/advanced-android-edge-to-edge-part-1-keyboard-transitions-with-motionlayout-66ae34d4c78a)
- [ConstraintLayout, Demystified — How It Really Works](https://androidengineers.substack.com/p/constraintlayout-demystified-how) (Solver-Mechanik, Performance)
- [Android Performance Patterns: Render Performance](https://androidperformance.com/en/2015/04/19/Android-Performance-Patterns-1/)
- [Sandeep Kella — requestLayout vs invalidate](https://medium.com/kotlin-android-chronicle/understanding-the-roles-of-requestlayout-and-invalidate-when-adding-a-view-in-android-93d47be50e1f)

### Dictate-Repo-Pointer (für Code-Verifikation)
- `app/src/main/java/net/devemperor/dictate/widget/PulseLayout.kt:1-141` — PulseLayout-Implementierung
- `app/src/main/java/net/devemperor/dictate/core/KeyboardLayoutModeController.kt:1-273` — Status-Quo-Controller
- `app/src/main/res/layout/activity_dictate_keyboard_view.xml:33-56` — record_pulse_layout/record_btn-Setup
- `app/src/main/res/layout/activity_dictate_keyboard_view.xml:25-105` — action_row mit `clipChildren=false`
- Vorgänger-Recherche: `docs/plans/2026-05-07 - keyboard-layout-refactor/research/motionlayout-architecture-options.md`
- Inventur-Recherche: `docs/plans/2026-05-07 - keyboard-layout-refactor/research/main-button-area-inventory.md`
