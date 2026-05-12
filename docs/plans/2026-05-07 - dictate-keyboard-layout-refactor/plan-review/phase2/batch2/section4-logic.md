# Phase 2 / Batch 2 / Section 4 — Logic Review

**Section:** Floating-Overlay (Spec 3 — §1 – §12, ohne §13)
**Spec file:** `/home/lukas/WebStorm/Docs/docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.md`
**Code cross-reference:** `/home/lukas/WebStorm/Dictate`
**Reviewer focus:** Logic, Clean Code, Code Integration (Visibility-Edge-Cases, Permission-Lifecycle, Drag/Position, Lifecycle, IME-Service-Death)
**Sister review:** Structure-Reviewer covers DRY/SOLID/Architecture in parallel.

Spec 3 ist auf Code-Skizzen-Level bereits sehr durchgearbeitet (DragHandler/PositionMapper als eigene Klassen, Permission-Gate getrennt vom Render, OverlayWindow-Wrapper für DIP). Die Findings unten sind **logische Lücken**, die das Spec heute offen lässt — vor allem rund um (a) Lifecycle-Übergänge, in denen der Overlay-Pfad nicht klar definiert ist, (b) Interaktion zwischen Permission-Status und laufender Pipeline, (c) Edge-Cases der Drag-/Position-Konversion, und (d) Code-Integrationsfragen, die heute mit dem Bestand drift-anfällig sind.

Bekannte Logic-Issues aus Phase 1 / Batch 1 sind referenziert, **nicht dupliziert**. Dieser Review fokussiert auf **das, was sec2-logic für Spec 3 noch nicht abdeckt**.

---

## Findings

### Issue L-1: Permission-Revoke während aktiver Pipeline — Spec sagt "akzeptabler Edge-Case", aber HOVER-Auto-Trigger steht dann ohne UI da
- **Category:** [LOGIC]
- **Severity:** Critical
- **Location:** Spec 3 §11.6 Tabellen-Zeile "Permission wird in System-Settings revoked, während Overlay sichtbar" + §5.6 Fallback + §4.2 `render()` Permission-Check
- **Description:** §11.6 dokumentiert: "Beim nächsten `render()`-Call (StateFlow-Emit) merken wir nichts — `addView` ist vor langer Zeit gelaufen, … Edge-Case ist akzeptabel, weil sehr selten." Das ist für den **WIDGET-Modus** (User aktiv, sieht IME) noch vertretbar — aber **nicht für HOVER**:
  1. HOVER greift automatisch, wenn `imeViewVisible=false && pipelineActive=true` (§7.1 `computeViewMode`). Der User ist außerhalb der Tastatur, eine InfoBar ist nicht erreichbar (§5.6 explizit).
  2. Wenn die Permission **vor** dem HOVER-Auto-Trigger revoked wurde (User hat sie manuell zurückgenommen), passiert in `render()`: `permissions.hasOverlayPermission()` → `false` → `teardownOverlay() + return`. Der User sieht **gar nichts** — kein Overlay, keine Notification-State-Änderung, kein Hinweis.
  3. Wenn die Permission **während** des HOVER-Modus revoked wird (User wechselt zu Settings, toggelt aus), bleibt das alte Overlay-Window so lange sichtbar bis das System es selbst abräumt — und der nächste render-Pass würde es zwar abreißen, aber er kommt erst beim nächsten StateFlow-Emit (z.B. neue Amplitude-Update).
  4. Spec 3 §9 (Notification-Fallback) sagt "Notification ist immer da" — gut. Aber: die Notification spiegelt **Recording-State**, nicht **Overlay-Sichtbarkeit**. Ein User, der gerade aufgenommen hat und den Foreground-Service-Notification ignoriert, hat keine Möglichkeit zu erkennen, dass jetzt der "Senden"-Button dort liegen würde, wo das Overlay nicht erschien.
- **Example scenario:** User startet Recording in WhatsApp → wechselt zu Browser, um etwas nachzuschlagen → HOVER soll greifen → User hatte aber gestern in Settings die Berechtigung deaktiviert (z.B. weil ein Antivirus-Tool sie geflagged hat) → kein Overlay erscheint, keine InfoBar-Möglichkeit. User vergisst die laufende Aufnahme, der Foreground-Service läuft 20 Minuten weiter.
- **Suggestion:**
  1. **§5.6 erweitern:** Bei HOVER-Auto-Trigger ohne Permission MUSS die Notification-Action-Liste angepasst werden ("Senden + Pause + Cancel sichtbar machen", siehe Spec 1 §7). Spec 3 §9 explizit sagen, dass die Pipeline-Notification der HOVER-Substitut ist.
  2. **Überwachungs-Pfad spezifizieren:** Beim Übergang `KEYBOARD/WIDGET → HOVER` (in `computeViewMode`) prüfen, ob `permissions.hasOverlayPermission()` → wenn nein, `state.copy(viewMode = HOVER, overlayPermissionMissingForHover = true)` setzen. Im Notification-Backend (Spec 1) eine Action `ShowSettingsForOverlayPermission` ergänzen — User kann via Notification-Tap zur Permission-Settings springen.
  3. **§11.6 letzte Zeile entschärfen:** "Edge-Case akzeptabel" stimmt für WIDGET, **nicht für HOVER**. Klassifikation in zwei Sub-Cases trennen.

---

### Issue L-2: Pipeline-Running während IME geschlossen wird, aber `computeViewMode` reagiert nicht atomar — Race-Window zwischen `onFinishInputView` und Pipeline-State-Emit
- **Category:** [LOGIC]
- **Severity:** Critical
- **Location:** Spec 3 §7.1 `notifyImeViewVisibilityChanged` + `computeViewMode` + §7.3 T3 + Code: `DictateInputMethodService.java:824+` (heutiger Lifecycle)
- **Description:** §7.1 liest `pipelineActive` als Snapshot aus `_state.value.pipeline !is PipelineUiState.Idle || _state.value.recording.isActiveOrPaused`. Das ist eine **single-thread-snapshot-Lese** im Moment des Visibility-Events. Es gibt aber zwei Pfade, in denen der Snapshot nicht stimmt:
  1. **Pipeline-Active wechselt synchron im selben Tick:** `onFinishInputView` → `notifyImeViewHidden()` → State liest `pipeline=Active`. Im selben Tick (oder kurz davor) hat der Pipeline-Service ein `Pipeline-Done` emittiert → `pipeline` würde auf `Idle` wechseln, aber der Reducer ist noch nicht durchgelaufen. → `computeViewMode` ergibt HOVER, obwohl die Pipeline bereits fertig ist. Overlay wird gezeigt → User sieht 200ms ein leeres HOVER-Widget mit Send disabled, dann verschwindet es wieder.
  2. **Pipeline startet erst nach View-Hidden:** User klickt Mikrofon im WIDGET-Modus (Auto-Record direkt aus Widget, OPEN-2), ohne dass der IME jemals sichtbar war → `notifyImeViewVisibilityChanged(false)` läuft beim nächsten App-Wechsel, `pipelineActive=true` → HOVER greift. Aber: das Spec spezifiziert **nirgends**, dass `notifyImeViewVisibilityChanged` auch dann gefeuert wird, wenn der View nie sichtbar war. Der heutige Code ruft `onFinishInputView` nur, wenn vorher `onStartInputView` gerufen wurde. → Wer triggert den Wechsel KEYBOARD-(nicht-sichtbar) → HOVER, wenn ein Recording aus dem WIDGET heraus gestartet wird (Standalone-WIDGET-Use-Case aus §11.9)?
  3. **Pipeline-Start aus WIDGET, dann User schließt Widget:** WIDGET → KEYBOARD-Übergang läuft via `Action.ToggleViewModeWidget` (§7.3 T2). `computeViewMode` wird **nicht** aufgerufen — der StateManager-Reducer mutiert direkt `viewMode = KEYBOARD`. Wenn die Pipeline noch läuft und der User die Tastatur wegswipet, läuft erneut `notifyImeViewVisibilityChanged(false)` → `userToggledWidget=false` (gerade reset in T2) + `pipelineActive=true` → HOVER. Konsistent, aber: was ist mit `smallMode=true` aus T2? Der wird in HOVER irrelevant, aber beim Re-Show (T5) bleibt er gesetzt → User landet in einer Mini-Tastatur, ohne dass er den Mini-Mode angefordert hat (zwischenzeitlich war HOVER).
- **Example scenario:** User klickt Mikrofon im KEYBOARD-Modus → spricht 5s → klickt Senden → Pipeline läuft (Whisper) → User wechselt sofort die App (IME wird unsichtbar). `onFinishInputView` läuft → `notifyImeViewHidden()` → `pipelineActive=true` → HOVER greift, Overlay erscheint mit Send disabled (kein InputConnection), Pause/Trash enabled. Whisper braucht 1.5s, dann `Pipeline-Done` → `Idle` State → State-Emit → `OverlayBackend.render()` mit `pipeline=Idle, recording=Idle` → der `computeViewMode`-Pfad ist im **Reducer** der `pipeline`-Mutation **nicht spezifiziert**. Wer triggert HOVER → KEYBOARD beim Pipeline-Done? §7.1 listet nur Visibility-Trigger.
- **Suggestion:**
  1. **§7.1 erweitern um Cross-Module-Trigger:** Nicht nur `notifyImeViewVisibilityChanged` triggert `computeViewMode`, sondern auch jeder Pipeline-State-Wechsel (Cross-Module-Cascade aus Spec 1 §15). Konkret: nach jedem `dispatch` muss eine `OverlayModule.onCrossModuleStateChange(prev, next)` prüfen, ob `viewMode` sich aufgrund von Pipeline/Recording-Änderungen ändert. Aktuell macht §7.1 das nur reaktiv auf View-Visibility.
  2. **Race-Window dokumentieren:** §7.1 explizit sagen, dass `computeViewMode` immer auf dem **post-Reducer**-State läuft (nicht auf einem Snapshot). Alternative: Synchronisation über Single-Dispatcher-Lock (siehe sec2-Logic L-2).
  3. **§7.3 T2 erweitern:** SmallMode-Persistenz bei zwischenzeitlichem HOVER klären — wird `smallMode` zurückgesetzt, wenn `viewMode` zwischendurch HOVER war? Spec ist heute silent.
  4. **WIDGET-Idle-Pfad (§11.9) testen:** Wenn der User Recording direkt aus dem WIDGET startet, ohne dass der IME jemals sichtbar war, MUSS der HOVER-Übergang (View hidden + Pipeline aktiv) auch dann triggern, wenn der View-Visibility-Pfad nie aufgerufen wurde. Spec spezifiziert das nicht — vermutlich greift es, weil der WIDGET-View **ist** der Overlay-View, `imeViewVisible` wäre `false`. Aber was, wenn der User den IME zwischendurch öffnet, wieder schließt — wird das Auto-WIDGET-Bit gesetzt?

---

### Issue L-3: Drag-Persist während laufendem Render — Position-Update kollidiert mit `applyPosition` und kann visuell flackern
- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** Spec 3 §4.2 `OverlayBackend.applyPosition` + §4.6 `DefaultOverlayDragHandler.touchListener` (ACTION_MOVE) + §11.5.4 Persist-Pfad
- **Description:** Während eines Drags läuft folgender paralleler Pfad:
  - **DragHandler-Thread (Main, Touch-Event-Pfad):** ACTION_MOVE → `params.x = ...; params.y = ...; window.update(view, params)` direkt.
  - **Backend-Thread (Main, StateFlow-Pfad):** Andere State-Achse mutiert (z.B. Amplitude-Update bei aktivem Recording, oder Pipeline-Status-Wechsel) → `render()` läuft → `applyPosition(state)` → liest **alte** persistierte 0..1-Position aus dem State → konvertiert via `normalizedToPixels` → `params.x/y` werden **überschrieben** → `window.update(view, params)` mit dem alten Wert.
  
  Resultat: das Widget springt während des Drags zurück auf seine gespeicherte Position. Das tritt nur ein, wenn ein State-Emit zwischen ACTION_MOVE-Frames passiert — bei aktivem Recording (Amplitude-Updates ~30 Hz) ist das bei jedem Move-Frame der Fall.
  
  Spec 3 §4.2 hat einen Idempotenz-Check (`if (params.x != px || params.y != py || params.gravity != ...)`), aber der greift nur, wenn der **State**-Wert (normalisiert) sich geändert hat — nicht wenn der **DragHandler** zwischendurch `params.x` direkt mutiert hat. Der Check erkennt also nicht, dass DragHandler die Hoheit hat.
  
  Der Code-Review zeigt: §11.5.8 sagt "Persistenz NICHT direkt vom DragHandler" — d.h. die State-Persistenz passiert erst bei ACTION_UP. Während des Drags (MOVE) ist `params.x/y` direkt mutiert, der State aber unverändert → `applyPosition` setzt zurück.
- **Example scenario:** User drag das Widget langsam von Top-End nach Center-Mitte → 30 Hz Amplitude-Update läuft → bei jedem Update läuft `render()` → `applyPosition` setzt das Widget zurück auf die persistierte Top-End-Position → Widget zittert oder springt während des Drags, oder bleibt einfach stehen, bis der User den Finger hebt und die persistierte Position aufholt.
- **Suggestion:**
  1. **DragHandler-Hoheit signalisieren:** `OverlayBackend.applyPosition` muss prüfen, ob der `dragHandler?.isDragging() == true` ist. Wenn ja, `applyPosition` early-return — der DragHandler hat die Hoheit über `params.x/y`. Konkret: `OverlayDragHandler` um eine `isDragging(): Boolean`-Methode erweitern, im Backend abfragen.
  2. **Alternativ:** während Drag den Position-Apply **suspendieren** (z.B. `private var positionApplyEnabled = true; dragHandler.attach { _, dragging -> positionApplyEnabled = !dragging }`).
  3. **Test:** Robolectric oder Instrumentation-Test: Drag starten, im selben Test 5 State-Emits mit unverändertem `overlayPosition*` ausführen, asseren dass `params.x/y` nicht zurückgesetzt wurden.

---

### Issue L-4: Multi-IME-Sessions — IME-Service-Restart während HOVER-Overlay aktiv: Wer reaktiviert das Overlay?
- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** Spec 3 §11.6 + Spec 1 §8 (referenziert) + Acceptance-Kriterium "Tastatur-Wechsel zur Gboard mit aktivem WIDGET" (§10)
- **Description:** Das Spec deckt den **WIDGET-Tastatur-Wechsel-Fall** explizit ab (Acceptance §10 Z. 1193: "Overlay verschwindet [IME-Service stirbt], Pipeline läuft weiter"). Was **nicht** abgedeckt ist: der **HOVER-Tastatur-Wechsel-Fall**:
  1. User startet Recording in App A → wechselt App → HOVER greift → Overlay sichtbar.
  2. User wechselt zu App B mit eigenem IME (z.B. Gboard) → `onFinishInputView` (Dictate) läuft, aber der Dictate-IME ist eh schon hidden → `notifyImeViewVisibilityChanged(false)` läuft erneut, kein State-Wechsel.
  3. Aber: wer reißt das Dictate-Overlay ab? Das Overlay lebt im **IME-Service** (`KeyboardLayoutManager` hält das `OverlayBackend`-Instanz). Wenn der IME-Service durch Tastatur-Wechsel stirbt (`onDestroy` läuft im Dictate-IME), wird `KeyboardLayoutManager` mit-zerstört, und das Overlay-Window leakt — `WindowManager.removeView` wurde nie gerufen.
  4. Das §11.6-Tabellen-Item "PipelineService onDestroy() während Overlay attached" sagt: "KeyboardLayoutManager.detachBackend() wird vom IME-Service vorher gerufen". Aber: PipelineService stirbt nicht, sondern der **IME-Service** stirbt. Der PipelineService **überlebt** (nach Spec 1 D1). Das Overlay aber lebt im IME-Service → Overlay leakt.
  5. Konsequenz: Window bleibt sichtbar, bis der `WindowManager` selbst aufräumt (System-Garbage-Collection des Process-Tokens — kann Sekunden bis Minuten dauern). Buttons im Overlay zeigen ins Leere, OnClickListener-Closures referenzieren toten `onAction`-Callback.
- **Example scenario:** User in WhatsApp recording → swipe-up zur App-Liste → tippt Browser an → Browser hat eigenen IME-Wunsch (Gboard ist Default) → Dictate-IME wird `onDestroy` → das Overlay (HOVER) bleibt sichtbar, weil `OverlayBackend.detach()` nie gerufen wurde. Der User klickt im Overlay auf "Senden" → `onClickListener` ruft `onAction(Action.StopRecordingAndSend)` → aber `onAction` ist die alte Closure des KeyboardLayoutManager → KeyboardLayoutManager wurde mit dem IME-Service zerstört → NullPointerException oder leere Action.
- **Suggestion:**
  1. **§4.2 erweitern:** `OverlayBackend.detach()` MUSS in `IME-Service.onDestroy()` aufgerufen werden, nicht erst im `KeyboardLayoutManager`-Cleanup. D.h. der IME-Service ruft direkt `keyboardLayoutManager.detachAllBackends()` (oder vergleichbar) in `onDestroy`, sodass das Overlay-Window vor Process-Death sauber abgerissen wird.
  2. **Architekturfrage öffnen:** Soll das Overlay wirklich im IME-Service leben? Alternative — Overlay als eigene `Service`-Komponente unter dem Pipeline-Service, mit eigener Lifecycle. Diskussion in §13 (Cross-Spec-Konsistenz) ergänzen — heute ist der Overlay-Owner mit dem IME gekoppelt, was bei Tastatur-Wechsel zerbricht.
  3. **Acceptance §10 erweitern:** "Tastatur-Wechsel zur Gboard mit aktivem **HOVER** (View ist eh hidden, aber HOVER-Overlay sichtbar)" als eigenes Kriterium — Overlay muss verschwinden, Pipeline läuft weiter (über die Notification-Aktionen).
  4. **Test:** Manual-Test §14.3 ergänzen: Recording starten → Tastatur-Wechsel forcieren (über IME-Picker) → Overlay verschwindet sofort, nicht erst nach Sekunden.

---

### Issue L-5: Drag-Threshold + System-touch-slop interagieren — Spec wählt 8dp, dokumentiert nicht den Konflikt mit Button-Long-Press
- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** Spec 3 §4.6 `dragThresholdPx` + §11.5.2 Tabelle "Click-vs-Drag-Differenzierung" Zeile "Long-Press auf Button"
- **Description:** §4.6 setzt `dragThresholdPx = 8 * density` und benutzt `ViewConfiguration.get(ctx).scaledTouchSlop` **nur für die Initialisierung** (Z. 566), aber **nicht** im Vergleich. Das ist inkonsistent mit Android-Konventionen:
  - System-`scaledTouchSlop` ist typisch 8dp (32px auf 4x density), aber kann bei Accessibility-Mode (großer touch slop) auf 16dp+ steigen.
  - §4.6 hardcoded 8dp ignoriert das. Resultat: Auf Geräten mit Accessibility-Mode greift der Drag-Modus, **bevor** der Button selbst seinen Touch-Slop überschritten hat → Drag konsumiert den Stream → Button zeigt keinen Click.
  - §11.5.2 Tabelle "Long-Press auf Button" sagt: "wie Tap (DOWN nicht konsumiert → Long-Press-Detector des Buttons greift)". Das stimmt nur, wenn der User den Finger **stillhält**. In der Praxis machen User bei Long-Press oft eine kleine Drift-Bewegung (>5dp). Mit 8dp-Threshold kippt das in den Drag-Modus, der Button-Long-Press-Listener feuert nicht.
  - Heute hat Dictate **keine** Long-Press auf Overlay-Buttons (Spec 3 §8 Tabelle: "Long-Press auf Button: Heute nicht implementiert"), aber der Send-Button hat schon heute in der Tastatur einen Long-Press (Translation-Mode). Wenn Spec 3 später erweitert wird, ist das Problem da.
- **Example scenario:** User mit Accessibility-Mode (z.B. ältere Person, große Touch-Targets) tippt auf Send-Button im WIDGET → Finger-Drift 6dp → Drag-Threshold 8dp wird nicht überschritten → ABER `scaledTouchSlop` ist auf diesem Gerät 12dp → Button-OnClickListener sieht den Move als "Touch verlassen" → kein Click. **Doppelter Verlust:** weder Drag noch Click feuern.
- **Suggestion:**
  1. **§4.6 anpassen:** `dragThresholdPx = max(8 * density, scaledTouchSlop * 1.5)`. Der 1.5-Faktor stellt sicher, dass der Drag-Threshold **über** dem System-touch-slop liegt — Buttons sehen ihren Click zuerst, Drag greift erst danach.
  2. **§11.5.2 Tabelle ergänzen:** "Long-Press auf Button" Zeile mit Hinweis, dass kleine Finger-Drift den Long-Press kippen lassen kann. Mitigation: bei Long-Press-Buttons erhöhter Drag-Threshold (View-spezifisch) oder Drag erst nach `LongPressTimeout`.
  3. **Test §14.1:** `OverlayDragHandlerTest.shouldNotTriggerDragInsideTouchSlop()` — Drift von 7dp soll Click triggern, Drift von 9dp soll Drag triggern.

---

### Issue L-6: `OverlayPositionMapper.normalizedToPixels` vor Layout-Pass — `view.measuredWidth` ist 0, GAP-7-Fix unvollständig
- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** Spec 3 §4.2 `inflateAndAttach` + `applyPosition` + §4.7 `DefaultOverlayPositionMapper.normalizedToPixels` (Z. 660-665)
- **Description:** §4.7 fängt den 0-Width-Fall ab via `view.width.takeIf { it > 0 } ?: view.measuredWidth`. Problem: **vor dem ersten Layout-Pass ist auch `measuredWidth = 0`**. `measure()` läuft erst nach `addView` — und auch dann erst, wenn der View-Hierarchie-Layout-Pass durch ist (asynchron via Choreographer).
  - Konkret: `inflateAndAttach()` Z. 380-381 inflate → `addView`. Dann (Z. 412) wird `view.post { applyPosition(...) }` registriert. Aber **dazwischen** (Z. 391) ruft `applyPosition` direkt aus dem ersten `render()`-Aufruf. Z. 395 `attachAndAttach` läuft → in `inflateAndAttach` wird `currentParams = params` gesetzt → `attach` returned → `applyPosition(state)` läuft (in `render()`-Pfad direkt nach `inflateAndAttach`).
  - Wait — Code-Review nochmal: `render()` Z. 333 ruft `if (overlayView == null) inflateAndAttach()` → dann Z. 335 `applySlots` → Z. 336 `applyPosition`. `applyPosition` ist also der erste Apply, BEVOR der Layout-Pass durch ist. `view.width=0`, `measuredWidth=0` (kein measure gelaufen), `maxX = (screenW - 0).coerceAtLeast(0) = screenW`. → `px = (normX * screenW).toInt()`. Bei `normX=1.0f` (Default Top-End) → `px = screenW` → das Widget wird auf `params.x = screenW` gesetzt → **komplett rechts vom Display, unsichtbar**, bis der Layout-Pass durchgelaufen ist und `view.post { applyPosition }` korrekt re-applied.
  - Der Spec-Code (§4.2) hat den `view.post`-Mechanismus (F-6/GAP-7), aber **nicht** den initialen Default-Pfad. Die "ein einmaliges Top-End-Default-Frame verschwindet"-Aussage stimmt nur, wenn das initiale `applyPosition` SKIPPED wird (return early). Heute returned es nicht, sondern setzt `params.x = screenW`.
- **Example scenario:** User aktiviert Widget zum ersten Mal → `inflateAndAttach` läuft → `applyPosition(state)` läuft mit `view.measuredWidth = 0` → `params.x = screenW`, `params.y = 0.1 * screenH` → `windowManager.update(view, params)` → das Widget ist rechts vom sichtbaren Bereich → Frame-Tick später: Layout-Pass durch, `view.post` callback feuert → `applyPosition` mit `view.width = ~280dp` → `params.x = (1.0 - 0) * (screenW - 280dp)` → korrekte Top-End-Position. Resultat: 16ms ein leeres Frame, dann Widget springt rein.
- **Suggestion:**
  1. **`applyPosition` early-return**: wenn `view.width == 0 && view.measuredWidth == 0`, sofort returnen — der `view.post`-Callback macht den Apply später. Heute returned er nicht, sondern berechnet eine kaputte Position.
  2. **Alternative:** initial-`params` aus dem `LayoutParamsFactory` nehmen (Top-End mit gravity-basiertem Anker), erst nach Layout-Pass auf `TOP|START` umstellen. Spec deutet das in §4.3-Code-Comment an (Z. 484-485), aber `OverlayBackend.applyPosition` setzt `gravity = TOP or START` UNCONDITIONAL — auch im ersten Render.
  3. **Test §14.1:** `OverlayPositionMapperTest.shouldReturnZeroPixelsForUnmeasuredView()` — `view.width=0, measuredWidth=0` → Mapper returned `(0, 0)` oder ein klar markiertes "use-fallback"-Sentinel. Backend respektiert das.

---

### Issue L-7: `OverlayBackend.detach()` während laufendem Drag — DragHandler hält Touch-Stream, Detach reißt View ab
- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** Spec 3 §4.2 `teardownOverlay` + §4.6 `DefaultOverlayDragHandler.touchListener` (laufender ACTION_MOVE)
- **Description:** Wenn während eines aktiven Drags ein Cross-Module-Cascade läuft, der `viewMode = KEYBOARD` mutiert (z.B. `Action.CloseOverlay` von einem anderen Pfad, oder Pipeline-Done in WIDGET), läuft `OverlayBackend.detach()` → `teardownOverlay()` → `dragHandler?.detach()` (setzt onTouchListener auf null) → `overlayWindow.detach(view)` → `removeView`.
  - Während dieser Sequenz: der DragHandler hatte ACTION_MOVE konsumiert (`dragging=true`), der nächste ACTION_UP würde `onPositionPersist` rufen. Mit removed View geht ACTION_UP nirgendwohin — der DragHandler **persistiert die Position nicht**.
  - Resultat: User dragt das Widget, mitten im Drag stirbt das Overlay (z.B. weil Pipeline-Done eintrifft) → die letzte Drag-Position wird verworfen. Beim nächsten Widget-Show ist die alte Position wiederhergestellt — User ist verwirrt, weil sein Drag "nichts gemacht" hat.
  - Verschärfend: §4.6 Z. 622-623 `detach()` setzt `view.setOnTouchListener(null)`. Was ist mit dem laufenden Touch-Event? Android sendet ACTION_CANCEL an den letzten Listener — aber der ist gerade nullified → kein Persist.
- **Example scenario:** User dragt das Widget von Top-End nach Mitte → bei 50% des Drags läuft ein Pipeline-Done-Effekt durch → `Action.PipelineDone` → ein Reducer mutiert `pipeline=Idle` → in `computeViewMode` greift "imeViewVisible=true && !userToggledWidget → KEYBOARD" → State-Emit → `KeyboardLayoutManager.switchBackend(KEYBOARD)` → `overlayBackend.detach()` mitten im Drag. User hebt den Finger, Drag-Position ist verloren.
- **Suggestion:**
  1. **§4.6 erweitern:** `OverlayDragHandler.detach()` MUSS bei aktivem Drag (`dragging=true`) den `onPositionPersist`-Callback **bevor** der Listener entfernt wird mit den **aktuellen** `params.x/y` aufrufen. Zusätzlich `dragging=false` setzen, sodass kein doppelter Persist nach UP geschieht.
  2. **§4.2 `teardownOverlay`:** Reihenfolge: `dragHandler?.detach()` (persistiert die letzte Position) → `overlayWindow.detach(view)`. Heutige Reihenfolge ist korrekt, aber DragHandler.detach muss erweitert werden.
  3. **Test §14.1:** `DragHandlerTest.shouldPersistPositionWhenDetachedMidDrag()` — Touch-DOWN, MOVE über Threshold, dann `handler.detach()` ohne UP → assertieren dass `onPositionPersist` aufgerufen wurde.

---

### Issue L-8: `userPrefersWidget`-Reset in T2 + Race mit `notifyImeViewVisibilityChanged` — Widget kommt nach Schließen unerwartet zurück
- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** Spec 3 §7.3 T2 (Schließen-Button im WIDGET) + §11.9 Persistenz-Bit + Acceptance §10
- **Description:** §7.3 T2 setzt beim Widget-Schließen `userPrefersWidget = false`. Aber: §7.3 T6 (HOVER → WIDGET wenn `userPrefersWidget=true`) liest `userPrefersWidget` beim Re-Show. Race-Szenario:
  1. User klickt Schließen-Button im WIDGET → `Action.ToggleViewModeWidget` → Reducer mutiert `viewMode = KEYBOARD, smallMode = true, userPrefersWidget = false` (atomar, single dispatch).
  2. **Aber:** `KeyboardLayoutManager.switchBackend(KEYBOARD)` braucht einen Frame, bis `imeViewBackend.attach` fertig ist. In dieser Frame-Lücke ist der View **noch nicht sichtbar** (alter Overlay-View ist gerade detached, neuer IME-View noch nicht inflated).
  3. Wenn das System genau in dieser Lücke `onFinishInputView` triggert (z.B. weil der User parallel App wechselt), läuft `notifyImeViewVisibilityChanged(false)` → `pipelineActive=true` (User hat ja noch Recording laufen) → `userToggledWidget=false` (gerade reset) → `computeViewMode → HOVER` → Overlay kommt zurück, statt KEYBOARD-mit-SmallMode.
  4. Das ist semantisch falsch: User hat aktiv Schließen geklickt, also will er **kein** Overlay mehr. HOVER greift trotzdem, weil das Visibility-Event nach dem Close-Klick durchlief.
- **Example scenario:** User in WhatsApp, Pipeline läuft, WIDGET sichtbar → User klickt Schließen-Button (Intention: "weg mit dem Widget") → in derselben Geste swipt der User nach oben um zur Home-Screen zu kommen → `onFinishInputView` wird gerufen, kurz nachdem der Schließen-Button die State-Mutation triggerte → HOVER-Overlay erscheint, obwohl User explizit "weg" gewählt hatte.
- **Suggestion:**
  1. **§7.3 T2 erweitern:** Beim Schließen im WIDGET zusätzlich ein Suppress-Bit setzen, z.B. `state.copy(viewMode = KEYBOARD, userPrefersWidget = false, suppressAutoOverlayUntilNextSession = true)`. Das Bit wird beim nächsten `notifyImeViewShown` (T5) automatisch zurückgesetzt.
  2. **`computeViewMode` anpassen:** "imeViewVisible=false && pipelineActive && **!suppressAutoOverlayUntilNextSession**" → HOVER. Sonst KEYBOARD.
  3. **Acceptance §10 ergänzen:** "Schließen in WIDGET unmittelbar gefolgt von App-Wechsel: HOVER greift NICHT, Pipeline läuft via Notification weiter."

---

### Issue L-9: Bypass von F-8/F-11 — direkte `_state.value.copy(...)` in §5/§6/§7 (bekanntes 1.1.2-Issue), aber zusätzlich logische Folge: Cross-Module-Observer für ViewMode/OverlayPosition läuft nicht
- **Category:** [LOGIC]
- **Severity:** Critical
- **Location:** Spec 3 §5.4 (Z. 846, 854, 862, 867), §6.1 (Z. 916-927), §6.2 (Z. 941-948), §7.1 (Z. 967-969), §7.3 (Z. 1029-1033, 1064-1068, 1100-1102) + Phase 1 1.1.2
- **Description:** Phase 1 1.1.2 dokumentiert das **strukturelle** Problem: direkte `_state.value.copy(...)` umgeht den `dispatch(Action)`-Single-Entry. Dieser Logic-Review ergänzt die **logische Konsequenz**:
  1. F-11 etabliert Cross-Module-Cascade (`onCrossModuleStateChange(prev, next)`). Wenn `viewMode` direkt mutiert wird (ohne Action-Dispatch), sehen andere Module den Wechsel **nie**. Konkrete Folge:
     - `OverlayModule` (falls existent oder als Reducer-Achse) erfährt nicht, dass `viewMode = WIDGET` wurde → kann keine `Effect.AttachOverlay` emittieren.
     - `RecordingModule` erfährt nicht, dass `viewMode = HOVER` wurde → kann keine "Buttons disablen, kein Send möglich"-Logik greifen lassen.
     - `LivePromptModule` (Spec 1) erfährt nicht, dass das Widget aktiv ist → kann nicht entscheiden, ob LivePrompt-Streaming sichtbar dargestellt werden soll.
  2. Konkret an §5.4 Z. 846: `_state.value = _state.value.copy(overlayOnboardingPending = true)` setzt nur das Flag — aber **wer attached die InfoBar**? In F-11-Architektur müsste das ein Effect sein (`Effect.ShowPermissionInfoBar`) oder ein State-Subscriber im `ImeViewBackend`. Heute ist nur der State gesetzt, kein Effect emittiert, kein Subscriber spezifiziert.
  3. §7.1 Z. 967-969 mutiert `viewMode` direkt. Wenn das `KeyboardLayoutManager.onStateChanged` (§7.2) reaktiv darauf reagiert, ist der Pfad ok. Aber der Test "is the SSoT consistent?" failed: F-8/F-11 sagen "Action ist Single Entry" — und §7.1 verletzt das.
- **Example scenario:** Pipeline-Module hat einen Effect, der bei `viewMode=WIDGET` einen Live-Preview-Stream zur Notification-Action hinzufügt. Wenn `viewMode` direkt mutiert wird (ohne Action), läuft der Cross-Module-Pfad nicht → Notification zeigt den Stream nicht → User in HOVER sieht den Live-Preview nicht in der Notification.
- **Suggestion:**
  1. **Konkrete Action-Liste in §3 (Spec 2 §3.3 referenzierend) auflisten** — Phase 1 1.1.2 + GAP-2 (§13.5) sagen das schon. Hier zusätzlich: alle State-Achsen, die heute direkt mutiert werden (siehe Tabelle), MÜSSEN über entsprechende Actions laufen:
     | Heute (direct mutation) | Soll (via Action) |
     |---|---|
     | `state.copy(overlayOnboardingPending = true)` | `dispatch(Action.OverlayAction.MarkOnboardingPending)` |
     | `state.copy(viewMode = ViewMode.WIDGET, userPrefersWidget = true)` | `dispatch(Action.OverlayAction.ToggleWidget)` (Reducer berechnet beide Felder atomar) |
     | `state.copy(viewMode = ViewMode.KEYBOARD, smallMode = true, userPrefersWidget = false)` | `dispatch(Action.OverlayAction.CloseWidget)` |
     | `state.copy(viewMode = ViewMode.KEYBOARD)` (in §6.2) | `dispatch(Action.OverlayAction.DismissOverlay)` |
     | `state.copy(viewMode = computed)` (in §7.1) | `dispatch(Action.ViewModeAction.OnImeVisibilityChanged(visible))` (Reducer berechnet `viewMode` via `computeViewMode`) |
  2. **Cross-Module-Cascade-Test:** Spec 3 §14.1 ergänzen: für jeden ViewMode-Wechsel sicherstellen, dass `onCrossModuleStateChange` für alle relevanten Module (`OverlayModule`, `PipelineModule`, `RecordingModule`) aufgerufen wird.

---

### Issue L-10: Pipeline-läuft + IME geschlossen, aber Pipeline-Done feuert während HOVER — Übergang HOVER → KEYBOARD ist nicht spezifiziert
- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** Spec 3 §7.3 (T1-T6 listen, kein "T7: HOVER → KEYBOARD nach Pipeline-Done") + §7.1 `computeViewMode`
- **Description:** §7.1 berechnet `viewMode` aus `imeViewVisible`, `userToggledWidget`, `pipelineActive`. Wenn `imeViewVisible=false && pipelineActive=false`: `else → KEYBOARD`. Aber: das Spec triggert `notifyImeViewVisibilityChanged` nur bei View-Visibility-Wechsel, nicht bei Pipeline-Wechsel.
  - Konkret: User in HOVER (View hidden, Pipeline aktiv) → Pipeline-Done feuert → `pipelineActive=false` → `computeViewMode` würde KEYBOARD ergeben — aber der **Trigger** für `computeViewMode` ist View-Visibility, der gerade nicht gewechselt hat. → Overlay bleibt in HOVER, obwohl es semantisch `KEYBOARD-Idle` (= kein Overlay) sein müsste.
  - In der Praxis greift der `OverlayBackend.render()`-Pfad: bei jedem State-Emit läuft `applySlots`, dort wird `OVERLAY_RECORD.visibility = (state.recording is Idle && state.pipeline is Idle) ? VISIBLE : ...` etc. → das Overlay zeigt einen "Idle-WIDGET-Look" mit Record-Button enabled (in HOVER aber: enabled=false). User sieht ein leeres Disabled-Overlay schweben, ohne Möglichkeit es zu schließen außer manuell den Schließen-Button (`Action.CloseOverlay`).
  - L-2 hat das Problem teil-adressiert ("Cross-Module-Trigger für computeViewMode"). Hier verschärft: das Spec listet die T1-T6 **vollständig** und verschweigt T7 (HOVER → KEYBOARD nach Pipeline-Done).
- **Example scenario:** User startet Recording in WhatsApp → wechselt App → HOVER greift → tippt Send-Button im HOVER (geht nicht — disabled) → erinnert sich, Send geht nur in WIDGET → wechselt zurück zu WhatsApp → IME wird sichtbar → T5 greift HOVER → KEYBOARD. Das funktioniert. ABER: alternative Sequenz: Pipeline ist 8s Whisper-Call → läuft fertig während User in der anderen App ist → HOVER bleibt sichtbar mit Send disabled, Pause disabled, Trash disabled. User sieht ein "Geist-Widget" das nichts tut.
- **Suggestion:**
  1. **§7.3 T7 ergänzen:** "HOVER → KEYBOARD-Idle nach Pipeline-Done". Trigger: `OverlayModule.onCrossModuleStateChange(prev, next)` mit `prev.pipeline != Idle && next.pipeline == Idle && next.recording == Idle && next.viewMode == HOVER` → emittiere `Action.ViewModeAction.AutoHoverDone` → Reducer setzt `viewMode = KEYBOARD`.
  2. **Acceptance §10 ergänzen:** "Pipeline-Done während HOVER: Overlay verschwindet automatisch, Foreground-Service bleibt mit `Pipeline-Done`-Notification (Spec 1 §7.5)".
  3. **Alternative:** §7.1 explizit sagen, dass `computeViewMode` nicht nur visibility-driven, sondern bei jedem State-Wechsel der relevanten Achsen läuft.

---

### Issue L-11: Auto-Trigger HOVER bei Pipeline-Start aus WIDGET — `userPrefersWidget=true`, Widget bleibt sichtbar nach IME-Schließen
- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** Spec 3 §7.3 T4 + §11.9 + Code-Skizze T4 Z. 1115
- **Description:** §7.3 T4 sagt: "auch wenn `userPrefersWidget=true`, schalten wir bei View-Hidden auf HOVER". Das ist semantisch korrekt (Send geht eh nicht ohne InputConnection). Aber:
  - §11.9 sagt: "Mit dem zusätzlichen Record-Button (5-Button-Layout) kann der User die Aufnahme direkt aus dem Widget heraus starten — auch ohne aktive Pipeline."
  - Konsequenz: User klickt Record im WIDGET (Idle) → Recording startet → User wechselt App → `notifyImeViewVisibilityChanged(false)` → T4 greift → HOVER. **Aber:** `userPrefersWidget=true` → beim Wieder-Sichtbarmachen (T6) → WIDGET. Das ist konsistent.
  - **ABER:** Der OPEN-2-Use-Case "User benutzt das WIDGET als floating-recording-button OHNE die Tastatur jemals zu öffnen" ist im Spec **nicht spezifiziert**:
    1. User aktiviert WIDGET in der Tastatur (KEYBOARD → WIDGET, T1).
    2. User wechselt App, IME wird hidden → T4 (WIDGET → HOVER mit `userPrefersWidget=true`).
    3. User klickt Record im HOVER — aber Record ist disabled (in HOVER, `enabledResolver = state.viewMode == ViewMode.WIDGET`).
    4. User kann also **nicht** aus HOVER recorden, obwohl der WIDGET-Modus für genau diesen Fall designed war (autark).
    5. Der einzige Pfad: User muss die Tastatur kurz öffnen → T6 (HOVER → WIDGET) → Record im WIDGET → wieder schließen → T4 (WIDGET → HOVER).
  - Das widerspricht dem WIDGET-autark-Versprechen aus §11.9. Entweder:
    - Das WIDGET ist autark, dann sollte Record auch in HOVER aktiv sein (alpha 1.0, enabled). Aber: Send geht nicht (kein InputConnection) — also Record-without-Send ist Müll.
    - Oder: das WIDGET ist NUR autark, wenn die Tastatur sichtbar ist (= ViewMode.WIDGET, nicht HOVER). Dann ist die §11.9-Aussage missverständlich.
- **Example scenario:** User aktiviert WIDGET zum Start → läuft 30 Sekunden im KEYBOARD-View → wechselt App, weil etwas anderes wichtig ist → HOVER greift, Recording fertig → User hat Idee, möchte zweite Aufnahme machen ohne wieder die Tastatur zu öffnen → klickt Record im HOVER-Widget → nichts passiert (Button disabled). User ist verwirrt, weil das Widget vorher Record hatte.
- **Suggestion:**
  1. **§11.9 klarstellen:** "WIDGET autark" gilt nur im `viewMode = WIDGET`-Zustand (= IME sichtbar + User toggled). HOVER ist disabled-Modus für Send/Record.
  2. **Alternative:** wenn der User wirklich autark sein soll, einen separaten "Standalone-Record"-Modus einführen (`ViewMode.STANDALONE_OVERLAY`?) der NICHT von IME-Visibility abhängt. Das ist aber Phase-2-Feature.
  3. **Acceptance §10 ergänzen:** "WIDGET → HOVER beim App-Wechsel: Record-Button im HOVER ist disabled, User kann erst nach IME-Re-Show wieder recorden" — explizit dokumentieren, dass das so gewünscht ist.

---

### Issue L-12: System-Theme-Wechsel + Overlay-Re-Render — `overlay_background.xml` nutzt `?attr/colorSurface`, aber `applyTheme` greift erst beim nächsten render
- **Category:** [INTEGRATION]
- **Severity:** Nice-to-have
- **Location:** Spec 3 §3.2 `overlay_background.xml` (Z. 215-216) + §4.2 `inflateAndAttach` + Android-Theme-System
- **Description:** `?attr/colorSurface` wird beim Inflate aus dem `Context.theme` gelesen. Wenn das System-Theme wechselt (z.B. Dark Mode On während Overlay sichtbar), läuft `onConfigurationChanged` im IME-Service. Der heutige Code (Dictate `DictateInputMethodService.java`) re-creiert den IME-View. Aber:
  - Das Overlay-Window lebt in einem **separaten** Window-Token. Es bekommt **kein** `onConfigurationChanged`-Callback.
  - Das `overlayView`-Drawable wurde mit dem alten Theme inflated → bleibt im alten Farbschema, bis Spec 3 §4.2 das Window manuell re-inflated.
  - Spec 3 spezifiziert das **nicht**. Der `inflateAndAttach`-Pfad läuft nur beim ersten `render()` mit `overlayView == null`. Theme-Wechsel triggert **kein** Re-Inflate.
- **Example scenario:** User im HOVER bei Tag (Light-Mode, weißes Overlay) → 18 Uhr Auto-Theme-Wechsel zu Dark → Foreground-App schaltet auf Dark um, Overlay bleibt weiß-mit-dunklem-Inhalt → optisch inkonsistent.
- **Suggestion:**
  1. **§4.2 erweitern:** `OverlayBackend` registriert sich auf `ContextCompat.registerComponentCallbacks` oder ähnlich. In `onConfigurationChanged` mit `diff & ActivityInfo.CONFIG_UI_MODE != 0` (Theme-Change) → `teardownOverlay() + render(state, mode)` → forciert Re-Inflate mit aktuellem Theme.
  2. **Test §14.4 ergänzen:** Manueller Test "Theme-Switch während Overlay sichtbar" → Overlay wechselt Hintergrund-Farbe innerhalb 200ms.

---

### Issue L-13: Drag-Position außerhalb Screen-Bounds (Rotation, Multi-Display) — `displaySize()` returned aktuelle Display-Metrik, aber Persist ist orientation-getrennt, nicht display-getrennt
- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** Spec 3 §4.7 `DefaultOverlayPositionMapper.displaySize()` + §11.5.6 Orientation-Change-Handling + §11.7 Multi-Window-Mode
- **Description:** `displaySize()` (§4.7 Z. 681-684) liest `ctx.resources.displayMetrics.widthPixels/heightPixels`. Bei:
  - **Multi-Display (foldable z.B. Z Fold, ChromeOS)**: `displayMetrics` referenziert das Display, in dem der Context lebt. Der IME-Service läuft typischerweise im Default-Display, aber das Overlay kann auf einem sekundären Display rendern. Die persistierte Position (Portrait/Landscape) ist normalisiert auf Display A → bei Wechsel zu Display B (anderes Aspect-Ratio) wird die normalisierte Position auf B falsch interpretiert (z.B. "Top-End" wird "Mitte-Rechts").
  - **Rotation-Change während Pipeline aktiv**: §11.5.6 sagt "der nächste State-Emit führt zum render". Was, wenn KEIN State-Emit kommt? Recording läuft mit ~30 Hz Amplitude-Updates → Emit folgt schnell. Aber: User hat Pipeline auf Idle, schaut nur ins Notification-Tray und dreht das Gerät → kein Emit → Overlay bleibt in der alten Orientation-Position.
  - **Foldable Fold/Unfold**: `displayMetrics` wechselt instant, aber der Spec hat keinen "Foldable-Trigger". Der Default-Pfad (orientation-Change) greift nur, wenn die `Configuration.orientation` selbst wechselt. Bei Fold können beide Modi Portrait sein, mit unterschiedlichen Aspect-Ratios.
- **Example scenario:** User auf Z Fold im Outer-Display (kompakt, 1.0 Aspect) → Widget bei `normX=1.0, normY=0.1` → entfaltet → Inner-Display (1.7 Aspect) → ohne Display-spezifischen Persist landet das Widget in einer ähnlichen Pixel-Position, sieht aber unverhältnismäßig nah am Rand (oder weit weg) aus. **Schlechter Fall:** Widget liegt jetzt off-screen oder über kritischen UI-Elementen.
- **Suggestion:**
  1. **§11.5 erweitern:** Persist nicht nur orientation-getrennt, sondern auch **Aspect-Ratio-Bucket-getrennt** (kompakt vs. tablet vs. foldable-inner). Schlüssel: `Pref.OverlayPosition_${aspectBucket}_${orientation}_X/Y`. Aspect-Bucket kann grob: `<1.5 = compact`, `1.5-1.8 = standard`, `>1.8 = wide`.
  2. **§11.7 (Multi-Window-Mode) konkretisieren:** Bei Free-Form / Split-Screen liest `displayMetrics` die Free-Form-Bounds — nicht das physische Display. Spec sagt heute "akzeptabel". Mindestens dokumentieren, dass die persistierte Position dann unter Umständen unbrauchbar wird, und ein Reset-Mechanismus ("Overlay ist off-screen") greift (Re-Center auf Default Top-End beim nächsten Show).
  3. **Test §14.4 ergänzen:** Foldable-Manual-Test: Position in Outer setzen → Inner unfold → Position ist sinnvoll repositioniert, nicht off-screen.

---

### Issue L-14: `Action.UpdateOverlayPosition` läuft via `onAction`, aber Action-Hierarchie in Spec 3 ist flach — Konflikt mit F-8 hierarchischer Action
- **Category:** [INTEGRATION]
- **Severity:** Nice-to-have
- **Location:** Spec 3 §4.2 Z. 404 (`Action.UpdateOverlayPosition`) + Phase 1 1.1.2 + 1.0.5 Tabelle
- **Description:** Phase 1 1.0.5 dokumentiert die hierarchische Action-Migration (`Action.CloseOverlay` → `Action.ViewModeAction.CloseOverlay`). Spec 3 nutzt aber durchgehend flache Actions: `Action.ToggleViewModeWidget`, `Action.CloseOverlay`, `Action.UpdateOverlayPosition(portrait, x, y)`, `Action.MarkOverlayOnboardingShown`, `Action.DismissOverlayOnboarding`.
  - Phase 1 1.0.5 ist als "Auto-Fix" markiert — Naming-Update ist gewollt. Logic-Konsequenz: Solange das Naming nicht durchgezogen ist, ist der `dispatch(action)`-Pfad nicht klar — der Reducer muss ein flaches `when (action)` haben oder die hierarchische Variante.
  - Zusätzlich: `Action.UpdateOverlayPosition(portrait, x, y)` hat **3 Parameter**. In F-8 sollten Actions als Daten-Klassen modelliert sein. Spec 3 zeigt nur die Aufruf-Form, nicht die Definition. Wo lebt sie? Spec 2 §3.3 (Action-Sealed-Class)? GAP-2 in §13.5 sagt, dass `MarkOverlayOnboardingShown` und `DismissOverlayOnboarding` "in der Spec-2-§3.3-Action-sealed-Klasse noch nicht aufgelistet" sind. `UpdateOverlayPosition` ist auch nicht aufgelistet.
- **Example scenario:** Implementer liest Spec 3 → schreibt `data class UpdateOverlayPosition(...)` als Top-Level. Reviewer schaut in Spec 2 §3.3 → keine `OverlayAction`-Sub-Hierarchie definiert → manueller Refactor nötig.
- **Suggestion:**
  1. **GAP-2 erweitern:** alle Spec-3-spezifischen Actions auflisten (`UpdateOverlayPosition`, `ToggleViewModeWidget`, `CloseOverlay`, `MarkOverlayOnboardingShown`, `DismissOverlayOnboarding`, `MarkOverlayOnboardingPending`).
  2. **Spec 2 §3.3 ergänzen:** `sealed class Action.OverlayAction : Action()` mit allen Sub-Klassen.
  3. **Phase 1 1.1.2 verweisen:** Spec 3 §5/§6/§7 müssen die hierarchischen Action-Namen verwenden (siehe oben Issue L-9).

---

### Issue L-15: `applySlots` setzt OnClickListener pro Render — Click-Action liest `state` aus Closure, der im nächsten Render veraltet ist
- **Category:** [CLEAN]
- **Severity:** Important
- **Location:** Spec 3 §4.2 `applySlots` Z. 345-351
- **Description:** Spec-Code:
  ```kotlin
  view.setOnClickListener { onAction?.invoke(slot.actionResolver(state)) }
  ```
  Pro Render-Aufruf wird der OnClickListener neu gesetzt. Der Listener-Closure captured `state` und `slot.actionResolver`. Wenn der User zwischen Render-Aufrufen klickt, gilt der Listener des **letzten** Render-Calls — d.h. `state` ist konsistent.
  
  Aber: zwischen `setOnClickListener(...)` und dem nächsten render kann der Click feuern. Wenn der User in dieser Lücke klickt, sieht er den State von Render N, nicht N+1. Bei schnellen State-Wechseln (Recording-Done → Pipeline-Start in 1 Frame) kann der User auf einem alten State agieren.
  
  Konkret: User klickt Send während eines Pipeline-Status-Wechsels → `actionResolver(state)` returned `Action.StopRecordingAndSend` (state war noch Recording) → State wechselt sofort darauf zu Pipeline-Active → die Action kommt im Reducer an, der State ist aber nicht mehr Recording → Reducer behandelt sie als invalid → silently drop.
  
  Der Spec-Kommentar Z. 343 sagt: "der State zur Click-Zeit aus dem Closure gelesen wird". Genau. Das ist die heute typische Lösung für Single-Frame-Race. Aber:
  - Im `ImeViewBackend` (Spec 2) wird laut Kommentar **nicht** so gemacht — dort werden die Click-Listener **einmalig static** gesetzt, der State wird zur Click-Zeit aus dem aktuellen StateFlow gelesen. Das ist ein **anderes Pattern** als hier — Spec 3 hat eine Inkonsistenz mit Spec 2.
  - Wenn Drag-Routing-Konflikt der einzige Grund war (Spec 3 Kommentar Z. 343-344), dann ist das fragwürdig: Drag im OverlayBackend würde durch den DragHandler-Listener auf dem Root-View abgefangen, **nicht** auf den Buttons. Buttons bekommen den Click erst nach Drag-Discriminierung. → Spec 3 müsste auch den static-Listener-Ansatz verwenden können.
- **Example scenario:** User in WIDGET, Recording aktiv → Pipeline-Done feuert (200ms vorher) → State wechselt zu Pipeline-Active → User klickt Send (Render mit State=Pipeline-Active läuft gerade) → Listener-Closure ist aber noch der alte (State=Recording) → `Action.StopRecordingAndSend` wird dispatched → Reducer ignoriert (Recording ist eh schon stopped).
- **Suggestion:**
  1. **§4.2 ändern:** Click-Listener einmalig setzen, im Click-Pfad den aktuellen State aus dem StateFlow lesen:
     ```kotlin
     view.setOnClickListener { onAction?.invoke(slot.actionResolver(stateRef ?: return@setOnClickListener)) }
     ```
     `stateRef` wird in `render()` als `private var stateRef = state` gesetzt — gibt es schon (§4.2 Z. 311). Damit ist der Lookup atomar, nicht via Closure.
  2. **§4.2 Kommentar Z. 343 entfernen oder umformulieren:** Der "Drag-Routing-Konflikt"-Grund hält nicht stand. Das pro-Render-Setzen ist eine Performance-Pessimierung (jeder State-Emit = N OnClickListener-Setops, jedes Set ist O(1) aber mit Garbage-Allocation der Lambda).
  3. **Konsistenz mit Spec 2:** beide Backends verwenden dasselbe Pattern (one-time setOnClickListener, Lookup zur Click-Zeit).

---

### Issue L-16: Permission-Verweigerung nach `markPermanentlyDenied` — kein UI-Pfad zum Wiederherstellen
- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** Spec 3 §5.4 Z. 847-851 + §5.6 + §11.3 Screen-Flow Z. 1287
- **Description:** §5.4 sagt: "Permanent abgelehnt: stiller Notification-Fallback. Keine InfoBar, kein ViewMode-Wechsel — User muss in Settings selbst aktivieren." §5.6 sagt: "Klick [auf disabled WIDGET-Toggle] löst die InfoBar erneut aus (auch nach permanenter Ablehnung — der User soll umkehren können)."
  
  Diese beiden Aussagen widersprechen sich:
  - §5.4: nach `markPermanentlyDenied` keine InfoBar mehr.
  - §5.6: Klick auf disabled WIDGET-Toggle zeigt erneut InfoBar.
  
  Code-Pfad: `toggleViewMode(WIDGET)` in §5.4: `if (!hasOverlayPermission()) { if (shouldShowOnboarding()) { … InfoBar … } else { … keine InfoBar … } return }`. → InfoBar nur wenn `shouldShowOnboarding() = true`. Nach `markPermanentlyDenied()` returned `shouldShowOnboarding()` `false` → keine InfoBar mehr. → §5.6 ist falsch.
- **Example scenario:** User klickt erstmals Widget → InfoBar erscheint → User klickt "Später" → `markPermanentlyDenied()`. Später möchte der User doch das Widget aktivieren → klickt Widget-Toggle → §5.4 Pfad: `shouldShowOnboarding() = false` → stiller Fallback → keine UI-Reaktion. User wundert sich, dass "Widget"-Klick nichts macht. **Kein Recovery-Pfad** außer manuell in Settings → Apps → Dictate → Erweitert → "Über andere Apps anzeigen".
- **Suggestion:**
  1. **§5.4 vs §5.6 vereinheitlichen:** entweder
     - **Option A (User-friendly):** auch nach `markPermanentlyDenied` zeigt der Klick auf disabled WIDGET-Toggle die InfoBar — nur die **erste-mal-Logik** (autonom auftauchen) verschwindet nach Denied. Manueller Klick triggert immer.
     - **Option B (strikt):** nach Denied keine InfoBar mehr, dafür eine separate "Widget-Aktivieren"-Option im Settings-Screen der App.
  2. **§5.4 Code-Skizze fixen:** entweder den `else`-Pfad mit einem `triggeredByExplicitClick`-Flag versehen → wenn explizit, InfoBar trotzdem zeigen. Oder das Disable-Verhalten: Toggle bleibt enabled (mit Tooltip "Berechtigung erforderlich"), Klick öffnet immer InfoBar.

---

### Issue L-17: Permission-Gate ruft `Settings.canDrawOverlays(ctx)` synchron im Render-Pfad — bei jedem State-Emit (~30 Hz beim Recording)
- **Category:** [PERFORMANCE]
- **Severity:** Nice-to-have
- **Location:** Spec 3 §4.2 `render()` Z. 327 + §5.1 `DefaultOverlayPermissionGate.hasOverlayPermission`
- **Description:** `Settings.canDrawOverlays(ctx)` ist ein AppOps-Lookup, der intern via Binder-IPC zum SystemServer läuft. Cost: ~50-200µs pro Call. Im Render-Pfad bei aktivem Recording (Amplitude-Updates ~30 Hz) → 30 IPC-Calls/s. Auf Low-End-Devices oder bei System-Last spürbar.
  - §5.5 Tabelle "User öffnet IME-View neu (`onStartInputView`)" sagt: "der StateManager kann hier `permissions.hasOverlayPermission()` cachen". Aber das Caching ist nicht im Code spezifiziert. Im Spec-Code (§5.1) gibt es keinen Cache.
  - Spec 3 §4.2 Z. 327 ruft die Methode bei jedem `render()` — ohne Cache.
- **Example scenario:** Pi 4 / Pixel 3a (Low-End) bei aktivem 60s-Recording → 1800 IPC-Calls für Permission-Check, je 100µs → 180ms CPU-Zeit auf dem Main-Thread für eine Frage, deren Antwort sich nicht ändert.
- **Suggestion:**
  1. **§5.1 erweitern:** `DefaultOverlayPermissionGate` cached `Settings.canDrawOverlays()` in einem volatile Field. Cache-Invalidation: bei jedem `onStartInputView` (§5.5) und beim Receive eines `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`-Result-Hints (es gibt keinen direkten Broadcast, aber `onResume`-Pendant beim IME-Re-Show). Plus: Cache-TTL von z.B. 5s als Safety-Net.
  2. **Alternative:** Permission-State als State-Achse `state.overlayPermissionGranted: Boolean` führen, im Reducer auf `Action.RecheckPermission` aktualisieren. Render liest aus dem State, kein IPC.
  3. **Test §14.1:** Mock-PermissionGate, asseren dass bei N State-Emits höchstens 1 `canDrawOverlays`-Call passiert.

---

### Issue L-18: WindowManager-LayoutParams nach App-Restore (Process-Death + Re-Bind) — `currentParams` ist null, neuer State-Emit triggert kein attach
- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** Spec 3 §4.2 `OverlayBackend` (state-fields) + §11.6 Lifecycle-Edge-Cases + Spec 1 §11.6 Recovery-Pfad (referenziert)
- **Description:** Beim Process-Death (System killt App im Hintergrund), läuft der Recovery-Pfad aus Spec 1 §11.6.2: PipelineService startet neu, liest DB, rehydriert State. Aber:
  - Spec 1 D1 sagt: "PipelineService überlebt", aber bei OOM ist auch der Service tot. Re-Bind via `bindService` startet ihn neu.
  - Wenn der State `viewMode = HOVER` hatte (= war im HOVER beim Tod), wird der State rehydriert. Aber: `OverlayBackend` ist im IME-Service. Der IME-Service ist beim Re-Bind **nicht aktiv** — er wird erst aktiviert, wenn der User die Tastatur öffnet.
  - → State sagt `viewMode = HOVER`, kein OverlayBackend lebt → kein Window. User sieht **kein** Overlay, obwohl der State es vorsieht.
  - Spec 3 §11.6 deckt das nicht ab. Die Tabelle listet "PipelineService onDestroy" und "Permission revoked", aber nicht "App-Process-Restart while State-says-HOVER".
- **Example scenario:** User aktiv in HOVER-Recording → System killed App (OOM) → Notification von Foreground-Service bleibt (re-startet via Sticky) → User klickt Notification-Action "Senden" → PipelineService startet, rehydriert State (viewMode=HOVER, recording=Active) → Aber kein Overlay sichtbar, weil IME-Service nicht aktiv → User-Erwartung "Widget kommt zurück" nicht erfüllt.
- **Suggestion:**
  1. **Spec 3 §11.6 erweitern:** "App-Process-Restart while State.viewMode = HOVER": HOVER kann **nur** im IME-Service-Kontext gerendert werden. Recovery-Pfad muss `state.viewMode = KEYBOARD` setzen (Reset). Foreground-Service-Notification ist der primäre Status-Indikator, bis User die Tastatur öffnet.
  2. **Alternative:** Overlay-Rendering in einen eigenen `Service` migrieren, der vom PipelineService aus startet (siehe L-4 Suggestion 2). Dann kann HOVER auch ohne IME-Service leben.
  3. **Acceptance §10 ergänzen:** "Process-Restart während HOVER: Notification ist primary, Overlay erscheint erst beim nächsten IME-Show."

---

### Issue L-19: `OverlayDragHandler` testet `paramsHolder()`-Null synchron pro Touch-Event — silent-drop ohne Logging
- **Category:** [CLEAN]
- **Severity:** Nice-to-have
- **Location:** Spec 3 §4.6 `DefaultOverlayDragHandler.touchListener` Z. 578
- **Description:** `val params = paramsHolder() ?: return@OnTouchListener false` — wenn `paramsHolder()` null returned (= Backend hat detacht oder ist im Re-Init), fällt der Touch silent durch. Kein Log, kein Crash, der User klickt ins Leere.
  - Das ist clean-code-mäßig ein Anti-Pattern: silent-drop ohne Indikation. In production debugging ist es schwer, Touch-Verlust auf "currentParams war null" zurückzuführen.
  - Konkret: zwischen `dragHandler?.detach()` (im teardown) und `dragHandler = null` läuft der `touchListener` weiterhin auf der View (View-Detach kommt erst danach). Wenn User in dieser Frame-Lücke (typisch <1ms, aber bei System-Last länger) klickt, wird der Touch silent gedroppt.
- **Example scenario:** Race-Condition-Bug-Hunt 6 Monate später: User berichtet "manchmal reagiert das Schließen-Button nicht". Reproducierbar in 0.1% der Klicks. Logs zeigen keinen Hint. Root-Cause-Analyse müsste den `paramsHolder() == null`-Pfad als Verdächtigen finden — schwer ohne Log.
- **Suggestion:**
  1. **§4.6 anpassen:** `?: run { Log.d(TAG, "drag-touch ignored: params null"); return@OnTouchListener false }`. Bei DEBUG-Builds reicht ein TRACE-Log.
  2. **Alternative:** `dragHandler?.detach()` setzt ein internes `disposed=true`-Flag, der `touchListener` returned `false` direkt. Cleaner als Null-Check.

---

### Issue L-20: Schließen in HOVER ruft `cancelPipeline()` — Recording-Buffer bleibt? Audio-File auf Disk?
- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** Spec 3 §6.2 `closeOverlay` Z. 941-948 + Spec 1 §6 Pipeline-Recovery + §11.6 (referenziert)
- **Description:** §6.2 sagt: "Brich aktuelle Pipeline ab UND dismisse Overlay … KEINE neue UI angezeigt … User muss explizit Tastatur öffnen + schließen". Aber:
  - **Recording-Buffer:** Wenn aktuell Recording läuft (vor Pipeline-Start), wird `cancelPipeline()` das Recording stoppen? §6.2 sagt das nicht aus. In Spec 1 §6 ist `cancelPipeline()` definiert — aber Recording vs. Pipeline sind getrennte State-Achsen (`state.recording` und `state.pipeline`).
  - **Audio-File:** Wenn die Aufnahme schon transcoded ist und das Audio-File auf Disk liegt (Status `RECORDED`), wird das File gelöscht? Spec 1 §11.6.4 hat einen Cleanup-Pfad für ghost-sessions, aber nicht für `cancelPipeline`-Output.
  - **DB-Eintrag:** Pipeline-Session wird in DB als `FAILED` markiert? Oder gelöscht? Status-Transitions in Spec 1 §6 zeigen `cancelled` als Endzustand — aber Spec 3 ruft nicht `cancelSession`, sondern `cancelPipeline`. Ist das identisch?
  - **Acceptance §10 Z. 1189**: "Schließen in HOVER: Overlay weg, Pipeline abgebrochen, KEIN neues Overlay erscheint bis User Tastatur explizit öffnet+schließt." Das ist UX-Beschreibung, nicht Daten-Cleanup.
- **Example scenario:** User HOVER-Recording 30s → klickt "Schließen" → Pipeline gecancelt → was passiert mit dem 30s-Audio-File? Wenn es auf Disk liegt (z.B. weil schon stop-recording lief), bleibt es liegen → Disk-Leak.
- **Suggestion:**
  1. **§6.2 erweitern:** Aufrufkette explizit aufschreiben:
     - `closeOverlay()` → `dispatch(Action.OverlayAction.DismissOverlay)` →
     - Reducer setzt `viewMode = KEYBOARD` + emittiert `Effect.CancelActiveSession`
     - Effect-Handler ruft `pipelineSessionRepo.cancelSession(activeSessionId)` (Spec 1 §6.4)
     - `cancelSession` löscht Audio-File + setzt DB-Status auf `cancelled`.
  2. **Spec 1 verlinken:** §6.2 muss explizit auf Spec 1 §6 (Pipeline-Cancel-Mechanik) referenzieren. Heute ist das implizit.
  3. **Acceptance §10 erweitern:** "Schließen in HOVER während aktivem Recording: Audio-File gelöscht (Disk-Cleanup), DB-Status auf `cancelled`, kein Notification-Eintrag verbleibt."

---

## Summary Table

| #  | Category      | Severity     | Issue                                                                           | Description (Kurzfassung) |
|----|---------------|--------------|----------------------------------------------------------------------------------|---------------------------|
| L-1  | [LOGIC]       | Critical     | Permission-Revoke + HOVER ohne UI                                              | HOVER-Auto-Trigger ohne Permission lässt User ohne Indikator; Notification reicht nicht zwingend (§11.6 zu lax). |
| L-2  | [LOGIC]       | Critical     | `computeViewMode` reagiert nicht auf Pipeline-State-Wechsel                    | Cross-Module-Trigger fehlt; Pipeline-Done in HOVER schaltet nicht zurück. WIDGET-Standalone-Pfad unspezifiziert. |
| L-3  | [LOGIC]       | Important    | Drag kollidiert mit `applyPosition` (State-Emit überschreibt Drag-Position)    | DragHandler-Hoheit signalisieren; `applyPosition` muss bei `isDragging` early-return. |
| L-4  | [LOGIC]       | Critical     | IME-Service-Death bei aktivem HOVER → Overlay leakt                            | IME-Service-onDestroy reißt Overlay nicht ab; Window-Token überlebt unkontrolliert. Tastatur-Wechsel-Bug. |
| L-5  | [LOGIC]       | Important    | Drag-Threshold ignoriert `scaledTouchSlop` → Click-Verlust auf Accessibility   | `dragThresholdPx` muss `max(8dp, scaledTouchSlop * 1.5)` sein. Long-Press-Inkompatibilität dokumentieren. |
| L-6  | [LOGIC]       | Important    | `applyPosition` vor Layout-Pass setzt `params.x = screenW`                     | `view.measuredWidth=0` → kaputte Initial-Position. early-return im Mapper / Backend nötig. |
| L-7  | [LOGIC]       | Important    | `OverlayBackend.detach` mid-drag verliert Drag-Persist                         | DragHandler-detach muss bei `dragging=true` letzte Position persistieren. |
| L-8  | [LOGIC]       | Important    | `userPrefersWidget`-Reset + Visibility-Race → unerwartetes HOVER-Re-Show       | Suppress-Bit bei Schließen-im-WIDGET nötig; `computeViewMode` muss berücksichtigen. |
| L-9  | [LOGIC]       | Critical     | Direkte `_state.value.copy(...)` umgeht Cross-Module-Cascade (Logic-Folge 1.1.2) | Andere Module sehen `viewMode`-Wechsel nicht; Effects fehlen. Action-Liste in §3 ergänzen. |
| L-10 | [LOGIC]       | Important    | T7 (HOVER → KEYBOARD nach Pipeline-Done) fehlt in §7.3                         | "Geist-Widget" bleibt sichtbar nach Pipeline-Done; Cross-Module-Trigger nötig. |
| L-11 | [LOGIC]       | Important    | WIDGET-autark-Versprechen (§11.9) inkonsistent mit T4 (HOVER disabled Record)  | Standalone-Recording aus HOVER nicht möglich; §11.9 muss klarstellen oder STANDALONE_OVERLAY-Modus. |
| L-12 | [INTEGRATION] | Nice-to-have | System-Theme-Wechsel triggert kein Overlay-Re-Inflate                          | `ComponentCallbacks.onConfigurationChanged` nicht abgedeckt; Theme-Drift möglich. |
| L-13 | [LOGIC]       | Important    | Position-Persist nur orientation-getrennt, nicht display/aspect-getrennt       | Foldable / Multi-Display: Persist-Schlüssel braucht Aspect-Bucket. Off-Screen-Recovery fehlt. |
| L-14 | [INTEGRATION] | Nice-to-have | Action-Hierarchie inkonsistent (flat vs. F-8 hierarchisch)                     | `UpdateOverlayPosition`, `MarkOverlayOnboardingShown` etc. in Spec 2 §3.3 nicht aufgelistet (GAP-2 erweitern). |
| L-15 | [CLEAN]       | Important    | OnClickListener pro Render gesetzt — Closure-Capture vs. Spec-2-Pattern        | Inkonsistent mit ImeViewBackend; Single-Frame-Race bei schnellen State-Wechseln. `stateRef` einmalig nutzen. |
| L-16 | [LOGIC]       | Important    | §5.4 vs §5.6 widersprüchlich: nach `markPermanentlyDenied` kein InfoBar-Re-Show | User hat keinen UI-Pfad zurück zur Permission. Logik vereinheitlichen. |
| L-17 | [PERFORMANCE] | Nice-to-have | `Settings.canDrawOverlays` IPC-Call pro Render (~30 Hz beim Recording)         | Cache + Invalidation auf `onStartInputView` reicht; oder als State-Achse modellieren. |
| L-18 | [LOGIC]       | Important    | App-Process-Restart mit `state.viewMode=HOVER` → kein IME-Service → kein Overlay | Recovery-Pfad muss HOVER auf KEYBOARD resetten; Notification ist primary. |
| L-19 | [CLEAN]       | Nice-to-have | DragHandler `paramsHolder() == null` silent-drop ohne Log                      | Debug-Log hinzufügen; oder explizites `disposed`-Flag. |
| L-20 | [LOGIC]       | Important    | `closeOverlay` in HOVER cancelt Pipeline, aber Audio-File-Cleanup unspezifiziert | Datenfluss `cancelSession` + Audio-File-delete + DB-Status fehlen in §6.2. |

---

## Verteilung

- **Critical:** 4 (L-1, L-2, L-4, L-9) — alle vier sind Lifecycle/Lebenszyklus-Lücken, die zu Daten-Verlust, Geist-UIs oder unrecoverable Zuständen führen können.
- **Important:** 12 (L-3, L-5, L-6, L-7, L-8, L-10, L-11, L-13, L-15, L-16, L-18, L-20) — Bugs, die in der Praxis auftreten werden, aber keinen Daten-Verlust verursachen.
- **Nice-to-have:** 4 (L-12, L-14, L-17, L-19) — Polish, Performance, Konsistenz.

## Querverweise zu Phase-1- und sec2-logic-Findings

- **Phase 1 1.1.2** (direkte `_state.value.copy(...)`-Bypass von F-8/F-11) — strukturell dort, logisch hier in **L-9** ergänzt (Cross-Module-Cascade-Folge).
- **Phase 1 1.0.5/1.0.6** (hierarchische Action-Pfade nicht durchpropagiert) — strukturell dort, integrativ hier in **L-14**.
- **Phase 1 GAP-2** (Action-Definitionen in Spec 2 §3.3 fehlen für `MarkOverlayOnboardingShown`, `DismissOverlayOnboarding`) — hier in **L-14** um `UpdateOverlayPosition`, `ToggleViewModeWidget`, `CloseOverlay` erweitert.
- **sec2-logic L-1** (IME-Service-Death während aktiver Pipeline, `inputConnectionProvider` undefiniert): gilt für Spec 3 verschärft als **L-4** (Overlay-Window leakt) und **L-18** (Process-Restart-Pfad).
- **sec2-logic L-5** (Concurrency parallele Pipelines + Auto-Enter): die Logic-Frage "was passiert mit dem Overlay, wenn parallel Sessions laufen?" ist im Spec 3 nicht explizit; das Spec 3 nimmt implizit **eine** aktive Session an. Bei chained-Pipeline (Pipeline-Done → ChainNext aus sec2-L-5) bleibt das Overlay sichtbar — das ist semantisch korrekt, aber nicht spezifiziert. Empfehlung: §7 explizit schreiben "bei Chain-Pipeline bleibt Overlay durchgängig in HOVER, kein Re-Trigger."
- **sec2-logic L-2** (Re-entrant dispatch): trifft auch §7.1 `notifyImeViewVisibilityChanged` wenn synchron in `dispatch` mündet — siehe **L-2** dieses Reviews.
