# Architektur-Recherche: Single-Row vs Two-Row Layout-Switching im Dictate-Keyboard

**Datum:** 2026-05-07
**Recherche-Agent:** general-purpose, very thorough
**Trigger:** Toggle-Bug im aktuellen `KeyboardLayoutModeController` — Re-Parenting-Logik ist fragil; Senden-Button wird im Send-Modus von anderen Buttons verdeckt; resend-Button verschwindet beim Toggle.
**Verwandter Plan:** [keyboard-layout-refactor.md](../keyboard-layout-refactor.md)

---

## Zielstellung der Recherche

Bewertung der User-Idee "Single-Row und Two-Row in einer XML deklarieren, beim Umschalten Diff berechnen und Buttons verschieben" gegen das, was Android-Standard-Patterns leisten. Recherche-only, keine Implementierung.

**Aktueller Code:**
- `app/src/main/res/layout/activity_dictate_keyboard_view.xml` (LinearLayout vertikal mit zwei verschachtelten ConstraintLayouts: `action_row` + `input_row`)
- `app/src/main/java/net/devemperor/dictate/core/KeyboardLayoutModeController.kt` (273 LOC, Re-Parenting + 3 ConstraintSets, davon einer programmatisch in `buildSingleRowConstraintSet()`)

---

## 1. MotionLayout + MotionScene

### Definition

`MotionLayout` (Subklasse von `ConstraintLayout` aus `androidx.constraintlayout.motion.widget`) ist eine ViewGroup, die zwischen zwei oder mehr `<ConstraintSet>`s animiert. Die Constraint-Definitionen leben in einer separaten XML-Datei, der **MotionScene** (`res/xml/`). Per Konvention referenziert das Layout nur die Scene; die Scene ist die SoT für alle Constraint-Sets.

### Code-Skizze

```xml
<!-- res/layout/activity_dictate_keyboard_view.xml -->
<androidx.constraintlayout.motion.widget.MotionLayout
    android:id="@+id/main_buttons_ml"
    app:layoutDescription="@xml/keyboard_motion_scene"
    ...>
    <!-- 8 Buttons als direkte Children, ohne Constraints -->
    <com.google.android.material.button.MaterialButton android:id="@+id/record_btn" .../>
    <com.google.android.material.button.MaterialButton android:id="@+id/space_btn" .../>
    ...
</androidx.constraintlayout.motion.widget.MotionLayout>
```

```xml
<!-- res/xml/keyboard_motion_scene.xml -->
<MotionScene xmlns:android="..." xmlns:motion="...">
    <Transition motion:constraintSetStart="@id/two_row"
                motion:constraintSetEnd="@id/single_row"
                motion:duration="200" />
    <ConstraintSet android:id="@+id/two_row">
        <Constraint android:id="@id/record_btn" .../>
        <!-- alle 8 Buttons in 2-Reihen-Position -->
    </ConstraintSet>
    <ConstraintSet android:id="@+id/single_row" motion:deriveConstraintsFrom="@id/two_row">
        <!-- nur die Differenzen -->
    </ConstraintSet>
</MotionScene>
```

In Kotlin: `motionLayout.transitionToState(R.id.single_row)` / `transitionToState(R.id.two_row)`.

### Erfüllt die User-Anforderungen?

- **Single Source of Truth:** Konzeptuell ja (Scene-XML hält beide States), aber **gegen den Buchstaben des Wunsches verstößt es** — die Scene ist eine *zusätzliche* XML in `res/xml/`. Das ist allerdings ein **etabliertes Idiom**: jeder Android-Entwickler erkennt die Layout↔Scene-Kopplung sofort und sucht den Partner-File.
- **Doku-freundlich:** Sehr gut. Beide Constraint-Sets stehen deklarativ als XML nebeneinander, mit `deriveConstraintsFrom` als expliziter Vererbung.
- **Default-Render Two-Row:** Erfüllt — `motion:constraintSetStart` ist der Initial-State.
- **Vergleichs-basiertes Switching:** **Genau das ist der eingebaute Kernmechanismus.** Die Engine berechnet den Diff zwischen Start- und End-ConstraintSet und animiert ihn. Genau die Operation, die der User händisch beschreibt — als Standard-Idiom, wartungsfrei.

### Aufwand für Dictate / Risiken

- **Re-Parenting bleibt das fundamentale Problem.** [Manage motion and widget animation with MotionLayout](https://developer.android.com/develop/ui/views/animations/motionlayout) und Stack-Overflow-Verifikationen bestätigen: **MotionLayout arbeitet nur mit *direct children*.** Ein `LinearLayout > {action_row, input_row}` wäre als Kind von MotionLayout zwar erlaubt — die 8 Buttons aber weiterhin in zwei separaten ViewGroups. Daraus folgt: **Migration auf MotionLayout zwingt die XML in eine flache Struktur** (alle 8 Buttons als direkte Geschwister im MotionLayout), kein nested `action_row`/`input_row` mehr.
- **Click-Listener:** State-Wechsel löst kein View-Recycling aus — Listener bleiben erhalten. Bekanntes Issue: MotionLayout kann aber Touch-Events frei "fangen", wenn `<OnClick>` oder `<OnSwipe>` in der Scene stehen. Solange wir das in der Scene weglassen und nur `transitionToState()` aus Kotlin aufrufen, sind die existierenden Listener ungestört.
- **Performance:** Beim State-Wechsel macht MotionLayout einen `requestLayout()` — vergleichbar mit dem aktuellen `ConstraintSet.applyTo()`. Im Steady-State: identisch zu `ConstraintLayout`.
- **PulseLayout-Risiko:** Der `record_btn` lebt aktuell *innerhalb* von `PulseLayout` (custom ViewGroup). Wenn `PulseLayout` als Wrapper im flachen MotionLayout bleibt, ist ein `PulseLayout`-Kind kein "direct child" der MotionLayout, sondern Kind der PulseLayout. Das ist OK — MotionLayout positioniert das `PulseLayout`, nicht den `record_btn` darin.
- **Geschätzte LOC:** ~120 LOC neu (Scene-XML), `KeyboardLayoutModeController` schrumpft von 273 auf ~40-60 LOC (nur noch Pref-Lesen + `transitionToState()`-Routing + initial-apply-Guard).
- **Risiken:** *Eines* zentrales Risiko — Tooling für Programmatic-Override des `audio_focus_btn` Visibility. Aktuell wird Visibility direkt im Controller gesetzt (`views.audioFocusButtonInRow.visibility = …`). MotionLayout's ConstraintSets können `android:visibility` mitkodieren, was der saubere Weg wäre. Migration bedeutet: Visibility wandert teilweise in die Scene-XML.

---

## 2. ConstraintSet in der Layout-XML, gleicher Datei

### Definition

Idee: Die Wurzel-`ConstraintLayout` enthält Buttons + zusätzliche `<Constraints>`- oder `<ConstraintSet>`-Geschwister-Tags, die per `ConstraintSet.clone(context, R.id.set_id)` geladen werden.

### Code-Skizze

```xml
<!-- Erhofft: -->
<ConstraintLayout>
    <Button android:id="@+id/btn_a" .../>
    <Button android:id="@+id/btn_b" .../>
    <ConstraintSet android:id="@+id/cs_two_row"> ... </ConstraintSet>
    <ConstraintSet android:id="@+id/cs_single_row"> ... </ConstraintSet>
</ConstraintLayout>
```

### Erfüllt die User-Anforderungen?

**Nein — die Idee funktioniert in dieser Form nicht.** [androidx/constraintlayout 2.1 release notes](https://github.com/androidx/constraintlayout/wiki/What's-New-in-2.1) bestätigt: **`<ConstraintSet>` ist ausschließlich ein Kind-Element von `<MotionScene>` (`res/xml/`)**. Innerhalb einer Layout-XML (`res/layout/`) als Kind von `<ConstraintLayout>` ist es kein erkanntes Element — der LayoutInflater würde versuchen, einen View daraus zu inflaten, und scheitern.

Was *teilweise* geht: Es gibt eine Helper-Klasse `androidx.constraintlayout.widget.Constraints`, die als XML-Container für reine Constraint-Definitionen dient. Sie wird aber *nicht* genutzt, um mehrere Sets in *einer* Layout-Datei zu deklarieren — sie ist eine pro-Datei-Variante.

### Aufwand

**Sackgasse.** Die Variante existiert nicht als unterstütztes Idiom.

---

## 3. Layout-File-Referenzierung statt programmatischem ConstraintSet

### Definition

`ConstraintSet().apply { clone(context, R.layout.alt_layout) }` lädt die Constraint-Definitionen aus *einer separaten Layout-XML*. Die alt-Layout-XML enthält die *gleichen Children mit den gleichen IDs*, aber andere Constraints. Bei `applyTo(layout)` werden die Constraints auf die existierenden Children der ursprünglichen Layout angewendet — **die alt-Layout wird nie inflated**, sie dient nur als Constraint-Schablone.

### Code-Skizze

```xml
<!-- res/layout/keyboard_buttons_two_row.xml -- defaults, im Activity inflated -->
<ConstraintLayout android:id="@+id/buttons_cl">
    <Button android:id="@+id/record_btn" app:layout_constraintTop_toTopOf="parent" .../>
    ...
</ConstraintLayout>

<!-- res/layout/keyboard_buttons_single_row.xml -- nur als ConstraintSet-Schablone -->
<ConstraintLayout>
    <Button android:id="@+id/record_btn" app:layout_constraintStart_toEndOf="@id/trash_btn" .../>
    ...
</ConstraintLayout>
```

```kotlin
val csSingleRow = ConstraintSet().apply { clone(context, R.layout.keyboard_buttons_single_row) }
csSingleRow.applyTo(buttonsCl)
```

### Erfüllt die User-Anforderungen?

- **Single Source of Truth:** **Verstoß** — der User will *eine* XML, das wären zwei.
- **Doku-freundlich:** Gut für sich genommen — beide XMLs sind voll lesbar.
- **Default-Render Two-Row:** Erfüllt — die Default-XML wird inflated.
- **Vergleichs-basiertes Switching:** Erfüllt — `applyTo()` rechnet implizit die Differenz.
- **Re-Parenting:** Hilft *nicht* gegen Re-Parenting. Die alt-XML kann nur Constraints für Buttons setzen, die auch wirklich in der Default-Layout existieren und im *gleichen* ConstraintLayout-Parent leben. Für Buttons, die in der Default-XML in *einem anderen* Parent sind (`input_row` vs `action_row`), würde `applyTo()` schweigend ignorieren.

### Aufwand

- ~50 LOC Layout-XML neu, ~20 LOC weniger Kotlin (kein `buildSingleRowConstraintSet()` mehr).
- **Aber:** schon der erste User-Wunsch ("nur eine XML") wird verletzt. Pattern verliert daher Gewicht.

---

## 4. Ein Container, kein Re-Parenting (flache Struktur)

### Definition

Die zwei verschachtelten `ConstraintLayout`s (`action_row`, `input_row`) werden **eliminiert**. Alle 8 Buttons sind direkte Children eines einzigen `ConstraintLayout` (oder `MotionLayout`). Die "zwei Reihen" entstehen rein durch Constraints — keine ViewGroup-Grenzen mehr. Switching = ConstraintSet-Apply *ohne* Re-Parenting.

### Code-Skizze

```xml
<!-- 8 Buttons, alle direkte Geschwister -->
<ConstraintLayout android:id="@+id/buttons_cl"
                  android:layout_height="wrap_content">
    <Button android:id="@+id/record_btn"
            app:layout_constraintTop_toTopOf="parent"
            app:layout_constraintStart_toStartOf="parent" .../>
    <Button android:id="@+id/space_btn"
            app:layout_constraintTop_toBottomOf="@id/record_btn"  <!-- Two-Row default: 2. Zeile -->
            app:layout_constraintStart_toStartOf="parent" .../>
    <!-- ... -->
</ConstraintLayout>
```

Single-Row-CS positioniert *alle* 8 Buttons mit `app:layout_constraintTop_toTopOf="parent"` und einer durchgehenden Start-toEndOf-Kette → eine Zeile.

### Erfüllt die User-Anforderungen?

- **Single SoT:** Wenn kombiniert mit MotionScene oder programmatischem CS-Build: vollständig.
- **Doku-freundlich:** Sehr gut — keine "Movables vs Natives"-Asymmetrie mehr; jeder Button ist ein freies Atom.
- **Default-Render Two-Row:** Erfüllt — die Default-XML-Constraints sind die Two-Row-Position.
- **Vergleichs-basiertes Switching:** Erfüllt — und elegant, weil **kein Re-Parenting mehr**. `ConstraintSet.applyTo()` reicht.
- **Höhe-Effekt:** Die Container-Höhe (`wrap_content`) folgt der höchsten besetzten Zeile — Two-Row → 2× Button-Höhe + Margin, Single-Row → 1× Button-Höhe. Das ist *erwünschtes* Verhalten (UI ändert sich sichtbar).

### Aufwand

- **Strukturelle Layout-Refaktorierung** — `action_row` und `input_row` sind Wurzel-Container für `record_pulse_layout`-Wrapping und `marginBottom`-Margin. Beides muss umverteilt werden.
- ~60 LOC XML-Umbau, **`KeyboardLayoutModeController` schrumpft drastisch** (kein `originalParents`, kein `rehome()`, keine 3-CS-Logik). Geschätzt: 273 LOC → 60-80 LOC.
- **Risiken:**
  - **Bug-Klasse "asymmetrisches Re-Parenting" verschwindet vollständig.** Das ist die Wurzel des gerade gefixten Bugs.
  - PulseLayout muss als Wrapper bleiben; bewegt wird nur seine Constraint-Position, nicht der innere `record_btn`. Funktioniert.
  - `input_row` hatte `layout_marginBottom="16dp"` — das muss als margin am Bottom-Anchor des ButtonsCl-Containers neu gesetzt werden (oder per ConstraintSet pro State).

### Bewertung

**Stärkster strukturaler Gewinn aller Optionen.** Behebt nicht nur das aktuelle Problem, sondern *macht eine ganze Bug-Klasse unmöglich*.

---

## 5. Andere Ansätze

### `Flow`-Helper (ConstraintLayout 2.0+)

[Flow API reference](https://developer.android.com/reference/androidx/constraintlayout/helper/widget/Flow): virtueller Helper, der referenzierte Views horizontal/vertikal kettet, mit `wrapMode` (`none` / `chain` / `aligned`). **Kann Reihenanzahl dynamisch ändern.**

```xml
<androidx.constraintlayout.helper.widget.Flow
    android:id="@+id/buttons_flow"
    app:constraint_referenced_ids="trash_btn,record_btn,space_btn,pause_btn,backspace_btn,enter_btn,resend_btn,audio_focus_btn"
    app:flow_wrapMode="chain"
    app:flow_maxElementsWrap="8"  <!-- single-row: 8, two-row: 4 -->
    .../>
```

**Erfüllt User-Anforderungen?** Single SoT vollständig — *einer* XML, nur das `flow_maxElementsWrap`-Attribut wird programmatisch zwischen 4 und 8 umgeschaltet. **Aber:** Flow-Helpers verteilen Children gleichmäßig — feinkörnige Steuerung pro Button (z.B. "space_btn dehnt sich" + Custom-Margins) ist begrenzt. Für Dictates Use-Case (Buttons mit unterschiedlichen Breiten / Visibility) wahrscheinlich **zu wenig Kontrolle**.

### `Group`-Helper

Nur Visibility-Sync. **Hilft hier nicht** — keine Layout-Umstrukturierung.

### `Barrier`-Helper

Virtuelle Guideline für variable Breite. Könnte Detail-Hilfe leisten (z.B. "alle Pausierungs-Buttons enden auf gemeinsamer Linie"), aber **nicht der Hauptweg** für Two-Row→Single-Row.

### ViewStub-basierte Lazy-Inflation

Ungeeignet — ViewStub kann pro Instanz nur **einmal** inflated werden. Mehrfaches Switchen würde manuelle View-Replacement-Logik erfordern, was strukturell genau das Re-Parenting-Problem reproduziert.

### Custom-ViewGroup mit eigener `onMeasure`/`onLayout`-Logik

Maximale Kontrolle, **maximaler Aufwand**. Über-engineered für 8 Buttons in 2 Modi. Disqualifiziert.

### `<merge>` + alternative Layout-Files (`layout-port`, `layout-land`)

Resource-Qualifier-System wechselt nur bei Konfigurationsänderung (Orientierung, Sprache). User-Toggle ist *kein* Konfigurations-Trigger → **disqualifiziert**.

---

## 6. Bewertung der User-Idee "in einer XML beide Layouts"

### Idee scharf bewertet

> "Beide Layout-Varianten in einer XML, beim Umschalten Differenz berechnen und Buttons verschieben."

**Diese Idee ist genau die Beschreibung von MotionLayout — als Konzept.** Der Algorithmus, den der User imaginiert ("vergleichen, bewegen"), ist exakt was `ConstraintSet.applyTo()` und MotionLayout's Transition-Engine intern tun.

Mit dem **buchstäblichen** Wunsch ("alles in *einer* Datei") kollidiert es jedoch: Android-Layout-XML in `res/layout/` erlaubt **keine** zweite ConstraintSet-Definition als Kind-Element des Wurzel-Layouts. Das ist eine harte technische Grenze des Layout-Inflators (siehe Punkt 2).

### Möglichkeitsraum

| User-Wunsch | Realisierbar? | Wie |
|---|---|---|
| "Genau eine XML" buchstäblich | **Nein** | Inflater versteht keine `<ConstraintSet>`-Geschwister im Layout-XML |
| "Eine logisch zusammengehörige Einheit aus zwei XMLs" | **Ja** | Layout + MotionScene (Punkt 1) |
| "Zwei Layout-XMLs, eine als Default + eine als CS-Schablone" | **Ja** | `ConstraintSet.clone(context, R.layout.alt)` (Punkt 3) |
| "Eine flache XML ohne Reihen-Container, beide States im Code" | **Ja** | Punkt 4, ggf. kombiniert mit MotionScene |
| "Eine flache XML + Flow-Helper mit dynamischem `maxElementsWrap`" | **Ja**, aber wenig Detail-Kontrolle | Punkt 5 / Flow |

### Idiom-Match

**MotionLayout/MotionScene ist das offizielle Idiom für "deklarative Constraint-States + automatisches Diff-Switching"** — genau das, was der User in Worten beschreibt. Dass es eine Scene-XML als Partner-File gibt, ist eine bewusste Architekturentscheidung von Google, nicht ein Mangel.

---

## A. Vergleichstabelle

| Option | Single-XML? | Re-Parenting nötig? | Bug-Klasse "asymmetrisch" möglich? | Animations-fähig? | Migration | Empfehlung |
|---|---|---|---|---|---|---|
| **0. Status quo** (3 ConstraintSets, Re-Parenting) | Ja (1 Layout) | **Ja** | **Ja** (gerade gefixt!) | Manuell via TransitionManager | — | ★ |
| **1. MotionLayout + MotionScene (nested rows)** | Teilweise (Layout + Scene) | **Ja** (Rows als nested → MotionLayout greift nicht) | Ja | Ja, native | M | ★★ |
| **1b. MotionLayout + MotionScene (flat, 8 direkte Children)** | Teilweise (Layout + Scene, aber idiomatisch eine Einheit) | **Nein** | **Nein** | Ja, native, deklarativ | L | **★★★★★** |
| **2. Multiple `<ConstraintSet>` in einer Layout-XML** | n/a | n/a | n/a | n/a | — | (nicht supported) |
| **3. `ConstraintSet.clone(context, R.layout.alt)`** | Nein (2 Layouts) | **Ja** (gleicher Bug bleibt) | Ja | Manuell via TransitionManager | S | ★★ |
| **4. Flacher Container, kein Re-Parenting (programmatisch wie heute)** | Ja (1 Layout) | **Nein** | **Nein** | Manuell via TransitionManager | M | ★★★★ |
| **5a. Flow-Helper (`flow_maxElementsWrap`)** | Ja (1 Layout) | **Nein** | **Nein** | Begrenzt | S | ★★ (zu wenig Kontrolle) |
| **5b. Custom-ViewGroup** | Ja | **Nein** | n/a | Selbst zu schreiben | XL | ★ |

---

## B. Empfehlung

**Option 1b — MotionLayout + MotionScene mit flacher Button-Struktur.** Diese Variante kombiniert die zwei stärksten Hebel beider Welten: die strukturelle Eliminierung der `action_row`/`input_row`-Container (Punkt 4) macht die "asymmetrisches Re-Parenting"-Bug-Klasse mathematisch unmöglich, und MotionScene gibt dem User die deklarative, doku-freundliche, an genau einer logischen Stelle definierte SoT, die er sucht (Punkt 1). Das `KeyboardLayoutModeController`-Imperativ-Konstrukt (273 LOC mit `originalParents`-Map, `rehome()`, programmatischem `buildSingleRowConstraintSet()`) reduziert sich auf ~30-40 LOC reines `transitionToState()`-Routing plus Initial-Apply-Guard. Animations-Verhalten wird als Nebenprodukt korrekt — kein TransitionManager-Trick mehr nötig. Der einzige formale Verstoß gegen "alles in einer XML" ist die Scene-XML als Partner-File, was aber das anerkannte Android-Idiom ist und für jeden Android-Entwickler beim ersten Blick ans Layout sofort als logische Einheit erkennbar (`app:layoutDescription`-Attribut zeigt die Kopplung explizit). Trade-off: die Migration ist L (groß), weil sowohl XML-Struktur als auch Controller umgebaut werden müssen — aber genau dieser Umbau ist der nachhaltige Gewinn.

Wenn der User MotionLayout strikt vermeiden will (z.B. wegen API-Level-Sorgen oder Tooling-Vorbehalt), wäre **Option 4** (flacher Container, Controller-imperativ wie heute, aber ohne Re-Parenting) der zweitbeste Weg — dieselbe Bug-Klassen-Elimination, weniger Doku-Komfort, dafür minimaler Idiom-Sprung weg vom Status quo.

---

## C. Risiken / Was der User selbst entscheiden muss

1. **"Eine XML"-Doktrin: buchstäblich oder konzeptuell?** Wenn buchstäblich: nur Option 4 oder 5a erfüllt das. Wenn konzeptuell ("eine logische Einheit, klar verlinkt"): Option 1b ist überlegen.

2. **PulseLayout im flachen MotionLayout:** Verifikation am Gerät nötig — `record_btn` als Kind von `PulseLayout` als Kind von MotionLayout. Theoretisch unproblematisch (MotionLayout sieht nur das `PulseLayout` als direct child), aber Pulse-Animation könnte mit MotionLayout's Layout-Pass interagieren. **Konkretes Risiko, nicht recherchierbar ohne Geräte-Test.**

3. **Visibility-Wandern in die Scene-XML:** MotionLayout's idiomatischer Weg ist, Visibility per `<Constraint android:visibility="…">` pro State zu kodieren. Der aktuelle Code setzt Visibility imperativ. Bei Migration zu MotionLayout muss entschieden werden: bleibt Visibility imperativ (im Controller) oder deklarativ (in der Scene)? Beides geht, der idiomatische Weg ist deklarativ.

4. **`audio_focus_btn`-Edit-Bar-Variante** ist bereits in einem separaten Container (`edit_buttons_keyboard_ll`) und wird *nicht* von der Layout-Mode-Logik berührt. Die Migration tangiert nur die `main_buttons_cl`-Buttons. Kein Risiko hier.

5. **Touch-Capture durch MotionLayout:** Bekanntes Issue — wenn `<OnClick>` oder `<OnSwipe>` in der Scene definiert sind, kann MotionLayout Touches "fangen". **Mitigation:** Solche Tags in der Scene weglassen, alle Click-Listener wie heute aus Kotlin setzen, Transition-Auslöser nur per `transitionToState()`.

6. **API-Level / Library-Version:** ConstraintLayout 2.0+ bringt Flow + die meisten MotionLayout-Features. Aktueller `compileSdk` und `constraintlayout`-Dependency müssen geprüft werden — die Recherche ergibt: alle besprochenen Features sind seit 2.0 stabil, sollte für Dictate gegeben sein.

---

## Sources

- [Manage motion and widget animation with MotionLayout — Android Developers](https://developer.android.com/develop/ui/views/animations/motionlayout)
- [MotionScene reference — Android Developers](https://developer.android.com/reference/androidx/constraintlayout/motion/widget/MotionScene)
- [`<ConstraintSet>` in MotionScene — Android Developers](https://developer.android.com/training/constraint-layout/motionlayout/ref/constraintset)
- [ConstraintSet API reference — Android Developers](https://developer.android.com/reference/androidx/constraintlayout/widget/ConstraintSet)
- [Build a responsive UI with ConstraintLayout — Android Developers](https://developer.android.com/develop/ui/views/layout/constraint-layout)
- [What's New in ConstraintLayout 2.1 — androidx/constraintlayout Wiki](https://github.com/androidx/constraintlayout/wiki/What's-New-in-2.1)
- [Flow helper API reference — Android Developers](https://developer.android.com/reference/androidx/constraintlayout/helper/widget/Flow)
- [Reuse layouts with `<include>` and `<merge>` — Android Developers](https://developer.android.com/develop/ui/views/layout/improving-layouts/reusing-layouts)
- [ViewStub API reference — Android Developers](https://developer.android.com/reference/android/view/ViewStub)
- [Introduction to MotionLayout (part I) — Nicolas Roard, Google Developers](https://medium.com/google-developers/introduction-to-motionlayout-part-i-29208674b10d)
- [Introducing Constraint Layout 2.0 — Sean McQuillan, Android Developers](https://medium.com/androiddevelopers/introducing-constraint-layout-2-0-9daa3e99995b)
