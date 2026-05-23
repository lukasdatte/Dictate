# Phase 2 — Logic Review — Section 3: Keyboard-Layout-Renderer

**Plan:** `/home/lukas/WebStorm/Docs/docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.md`
**Section spec:** `research/2-keyboard-layout/2-keyboard-layout.md` (§§ 1–12, plus §13.1–§13.5; §13 reviewed only as far as §13.5; §14 read for context, §15+ N/A)
**Code cross-reference:** `/home/lukas/WebStorm/Dictate/`
**Reviewer scope:** Logic & Clean-Code (NOT structure/DRY/SOLID — that's the structure-reviewer).
**Output:** `plan-review/phase2/batch1/section3-logic.md`

---

## Findings

### Issue L-1: Send-Button-bug-fix (User-Aussage) ist im Plan **nicht zuverlässig adressiert** — Predicate "trash/pause = false in Send-Mode" beruht auf einer fragilen State-Annahme

- **Category:** [LOGIC]
- **Severity:** Critical
- **Location:** §8.7 Visibility-Matrix, Anmerkung A1/A2 (Z. 1244–1252); §8.5 `predTrashVisible` / `predPauseVisible` (Z. 1131–1137); LayoutCatalog `KEYBOARD_TWO_ROW_SEND_MODE` (§8.3, Z. 1020–1029).
- **Description:** Der heutige User-Bug "Send-Button verdeckt im Send-Modus" entsteht laut Plan §1.1 dadurch, dass im Send-Mode trash/pause noch sichtbar bleiben und über den record_btn rendern. Die LayoutCatalog-Lösung zeichnet in `KEYBOARD_TWO_ROW_SEND_MODE` und `KEYBOARD_SINGLE_ROW_SEND_MODE` für TRASH und PAUSE ein literales `visibilityPredicate = { false }`. Im selben Catalog-Mode `KEYBOARD_TWO_ROW` / `_SINGLE_ROW` (Idle-State) verweisen die TRASH/PAUSE-Slots aber auf die zentralen Predicates `predTrashVisible` / `predPauseVisible`. Diese liefern aber nicht garantiert `false` während eines Send-Mode-Übergangs:

  Send-Mode wird in `forKeyboard(state)` (§8.6) ausgelöst durch `state.pipeline is PipelineUiState.Preparing || Running`. Der Übergang Recording → Pipeline-Running ist aber **nicht atomar** — der Plan sagt selbst (§8.7 Anmerkung A1/A2): *"beim Übergang Recording → Pipeline-Running werden in heutigem Code pause/trash GONE, weil `RecordingState` zu `Idle` wechselt während `pipeline` zu `Preparing→Running` wechselt"*. Das Refactor hält am gleichen Modell fest: **Recording=Idle UND Pipeline=Preparing müssen gleichzeitig wahr sein**, sonst ist der Mode-Resolver kurz "im falschen Mode". Wenn die Reducer-Reihenfolge (Spec 1) zuerst `Pipeline=Preparing` setzt **bevor** `Recording=Idle`, dann ist während dieses Tick `predTrashVisible` weiter `true` (recording.isActiveOrPaused), aber `forKeyboard` wählt schon `KEYBOARD_TWO_ROW_SEND_MODE` mit hardcoded `{ false }` — das ist **dieselbe Race**, nur in deklarativer Form. Beim umgekehrten Übergang (Pipeline-Done → Recording=Idle) gibt es einen Tick, in dem `pipeline=Idle` und `recording=Idle` — Catalog wählt `KEYBOARD_TWO_ROW`, Predicate `predTrashVisible(state)` evaluiert zu `false || (pipeline is ReprocessStaging)` = false. OK.

  Aber: Im Übergang **Active → Pipeline.Preparing** sind zwei mögliche Reihenfolgen denkbar:
  1. `recording=Idle` zuerst, dann `pipeline=Preparing` → Catalog evaluiert beim ersten Tick `KEYBOARD_TWO_ROW` (recording=Idle, pipeline=Idle), und `predTrashVisible(state)` = `false`. OK.
  2. `pipeline=Preparing` zuerst, dann `recording=Idle` → Catalog evaluiert `KEYBOARD_TWO_ROW_SEND_MODE` (pipeline ≠ Idle), aber `state.recording = Active`. Slot-Predicate-Lookup für TRASH liefert hardcoded `{ false }` (per Slot-Definition in TWO_ROW_SEND_MODE) — also korrekt sichtbar=false, **aber** der `applySlotToView` (§5.1) setzt `view.isEnabled = slot.enabledResolver(state)` und `view.alpha = slot.alphaResolver(state)`. Die TRASH-Slot-Definition in §8.3 hat keinen `enabledResolver` (Default `{ true }`). Hier kein Bug — TRASH bleibt enabled, aber Visibility=GONE → Render OK.

  Der Bug-Fix-Mechanismus ist also: **der Catalog-Switch `forKeyboard` MUSS strikt vor `predTrashVisible/predPauseVisible` evaluieren**. Das ist garantiert — `forKeyboard` ist die äußere Auswahl, Slot-Predicates sind innere. Logisch ist die Bug-Fix-Garantie also **strukturell** (Send-Mode-Catalog hat hardcoded `{ false }`), nicht über die zentralen Predicates.

  **Aber:** das schafft einen **Drift-Pfad**: wenn jemand später `KEYBOARD_TWO_ROW_SEND_MODE.TRASH.visibilityPredicate` von `{ false }` auf `predTrashVisible` ändert (weil "DRY", weil "warum verschieden"), reaktiviert er den Bug. Die Spec dokumentiert nicht klar, **warum** Send-Mode hardcoded false hat statt predTrashVisible zu nutzen.
- **Example scenario:** Iter-3-Reviewer schlägt vor: "DRY — alle 5 LayoutModes nutzen `predTrashVisible`. Single Source of Truth." Ein Implementer ändert die hardcoded `{ false }` zu `predTrashVisible`. Test-Suite (§14.2 UI-Test 4) deckt nur den **Idle-Send-Übergang** ab, nicht den **Active-direct-to-Pipeline-Send** (StopRecordingAndSend). Bug rast zurück in Production.
- **Suggestion:**
  1. **Inline-Doku in §8.3:** ein deutlicher Kommentar an den `KEYBOARD_TWO_ROW_SEND_MODE.TRASH/PAUSE`-Slots, der explizit sagt: *"Hardcoded `{ false }` statt `predTrashVisible`, weil das ein bekannter User-Bug ist (Plan §1.1 #3) und die zentrale Predicate-Funktion während des Active→Preparing-Tick-Übergangs noch `true` liefern könnte. Catalog-Switch ist der Bug-Eliminator — nicht die Predicate-Logik."*
  2. **Test in §14.2:** zusätzlicher UI-Test "Active→Pipeline-Preparing-Transition: in **keinem** Render-Tick zwischen Click und Pipeline.Running zeichnet TRASH/PAUSE über record_btn". Beobachtbar via TransitionListener-Frame-Capture oder Espresso-`onView(withId(R.id.trash_btn)).check(matches(not(isDisplayed())))` direkt nach Click.
  3. Erwägen: `predTrashVisible` und `predPauseVisible` so ergänzen, dass sie `state.pipeline` mitbeachten — `recording.isActiveOrPaused && state.pipeline is PipelineUiState.Idle`. Damit ist die Predicate selbst sicher, und der Catalog-Switch ist nur noch Position-/Layout-Switch, nicht Visibility-Bug-Fix. Das macht die Send-Mode-Slot-Predicates konsistent mit den Idle-Mode-Slot-Predicates und eliminiert den Drift-Pfad.

---

### Issue L-2: Resend-Button verschwindet beim Toggle — Plan adressiert nicht den **Toggle-Race** mit MotionScene-Transition

- **Category:** [LOGIC]
- **Severity:** Critical
- **Location:** §6 `ImeViewBackend.render` (Z. 411–434); §8.5 `predResendVisible` (Z. 1124–1129); §11.6 Click-Listener-Lifecycle (Z. 1645–1672); Bekannter Bug aus Plan-Hauptdoku §1.1 #3.
- **Description:** Der zweite User-Bug "Resend-Button verschwindet beim Toggle" wird laut Plan §13.1 durch `predResendVisible` als SSOT eliminiert. Die Predicate ist:
  ```
  state.lastAudioExists && state.resendEnabled
      && state.recording is Idle
      && state.pipeline is Idle
  ```
  Beim **Single-Row-Toggle im Idle-State** sollte `predResendVisible` weiterhin `true` liefern, weil keine der vier Bedingungen sich ändert. Der Bug ist heute laut User-Aussage trotzdem aktiv — was heißt, der Bug entsteht **nicht** in der Predicate, sondern in der **Render-Reihenfolge**:

  `ImeViewBackend.render` macht in **dieser Reihenfolge**:
  1. `motionLayout.transitionToState(targetSceneState)` (250ms async)
  2. `mode.rows.flatMap { it.slots }.forEach { applySlotToView(slot, view, state, ctx) }` (sofort)
  3. `recordingAnimationController.onState(state)` (sofort)

  Das `applySlotToView` setzt `view.visibility = if (visible) View.VISIBLE else View.GONE`. **Aber MotionLayout managt während der Transition selbst die Visibility-Animation für Views, die `motion:visibilityMode != "ignore"` haben.** Plan §7.3 listet RESEND als `visibilityMode="ignore"` — das ist gut. Aber: was, wenn die Transition Two-Row → Single-Row während eines Pipeline-Tick (also state.pipeline.Running) stattfindet? Dann ist `predResendVisible(state) = false` (pipeline ≠ Idle), `applySlotToView` setzt RESEND auf `GONE`, und beim Pipeline-Done geht `state.pipeline = Idle`, neuer Render-Tick, RESEND wieder VISIBLE. Soweit OK.

  Aber **der reale User-Bug** ist laut §1.1 #3: "Resend-Button verschwindet **beim Toggle**" — also Toggle-Action, nicht Pipeline-Transition. In der neuen Architektur kommt die Toggle-Action via `Action.LayoutAction.ToggleSingleRowMode` (Spec 1 §3.3 §15) → State-Update setzt `state.singleRowMode = !singleRowMode`. Catalog-Selector wechselt `KEYBOARD_TWO_ROW` ↔ `KEYBOARD_SINGLE_ROW`. **Beide haben RESEND mit `predResendVisible` als Predicate.** Predicate liefert `true` in beiden Modes. **Sofern die State-Achsen `lastAudioExists / resendEnabled / recording / pipeline` über den Toggle-Tick stabil bleiben.**

  **Risiko:** in der heutigen Architektur ist `lastAudioExists` ein per-Service-Field, nicht im DictateUiState. Spec 2 §13.1 #28 sagt `pipelineService.markLastAudioExists(true)` triggert State-Update. Wenn der Toggle-Click das State-Update in einer **separaten Coroutine** auslöst, kann es zu einer Race kommen: `singleRowMode=true` wird atomar, `lastAudioExists` ist im selben Snapshot? **Spec 2 dokumentiert das nicht.** Laut Spec 1 ist DictateUiState immutable, jeder Reducer-Apply emittiert eine neue Instanz — das wäre OK. Aber: ist garantiert, dass der `singleRowMode`-Reducer im LayoutModule (Spec 1 §15) den `lastAudioExists`-Wert aus ResendModule **übernimmt**? Modulare Reducer arbeiten per Sub-State. Ein LayoutModule-Reducer ändert nur `state.layout`, nicht `state.resend.lastAudioExists`. Das kombinierte DictateUiState bleibt korrekt — gut.

  **Aber:** §11.6 Risiko 2 dokumentiert eine separate Race: "Wenn ein `state`-Snapshot in einem Lambda gefangen wird, das vom System verzögert ausgeführt wird, zeigt der Click die `actionResolver` von einem **veralteten** State an". Das ist mit der L8-Lösung (stateRef-Field) elegant gelöst. **Aber das Symmetrie-Problem ist nicht behandelt:** `applySlotToView` wird in `render()` synchron aufgerufen mit `state` als Argument. Wenn während dieser Render-Schleife (mehrere ms für 9 Slots) ein **neuer State** im StateFlow ankommt, läuft die jetzige Schleife auf altem State weiter, dann kommt ein zweiter Render mit dem neuen — also nur ein Frame Verzögerung, kein Bug. OK.

  Der eigentliche Bug ist vermutlich der Resend-Cooldown (§13.5 Gap 2): heute steht `setResendEnabled(false)` für 500ms nach Resend-Click. Wenn das **mit einem unabhängig ausgelösten Toggle koinzidiert**, sieht der User den Resend-Button verschwinden. Im Refactor: Gap-2-Lösung legt `resendCooldown: Boolean` in DictateUiState ab. Die Resend-Slot-Predicate **liest `resendCooldown` aber NICHT** (`predResendVisible` checkt nur lastAudio/resendEnabled/recording/pipeline) — Plan deklariert Cooldown nur im `enabledResolver`, NICHT im `visibilityPredicate`. Damit bleibt der Resend-Button während Cooldown sichtbar (nur disabled+alpha). **Das ist die strukturelle Lösung des "Resend verschwindet beim Toggle"-Bugs**, weil die Visibility nicht mehr durch transient-Events gesetzt wird.
- **Example scenario:**
  - Heute: User klickt Resend → `setResendEnabled(false)` → 500ms-Cooldown. Während dieser 500ms toggelt User Single-Row. `KeyboardLayoutModeController.setSingleRowMode` macht Re-Parent + applyConstraints. Re-Parent durchläuft `removeView(resend) → addView(resend)`. addView resettet `view.visibility` auf den XML-Default (`gone`, siehe `activity_dictate_keyboard_view.xml` Z. ~92). User sieht: Resend-Btn weg, kommt erst beim nächsten `applyIdleState`-Aufruf zurück (manchmal nie, weil Idle-State stabil ist und kein Re-Render triggert). **Das ist der heutige Bug.**
  - Refactor: kein Re-Parent (L2 flat hierarchy). MotionLayout-Transition mit `visibilityMode="ignore"` lässt die View-Visibility unverändert. Slot-Predicate evaluiert `true` in beiden Modes vor und nach Toggle. **Bug strukturell eliminiert.** ✓
  - **Aber:** im neuen Modell muss verifiziert werden, dass im Single-Row-Mode der Resend-Btn auch tatsächlich visible bleibt während der 250ms-Transition. MotionScene `single_row_state` (§7.1, Z. 682–693) hat `visibilityMode="ignore"` für resend — gut. **Risiko**: Wenn die Transition zwischen `two_row_state` und `single_row_state` zu einem Zwischen-State führt, in dem die Position des resend_btn temporär außerhalb des Viewports liegt (z.B. `marginEnd` interpoliert von 8dp zu 4dp via Position-Animation, aber der Button ist während der Animation hinter audio_focus_btn, was ja erst seit Single-Row sichtbar wird) — User-sichtbar, nicht durch View.GONE.
- **Suggestion:**
  1. **Acceptance-Kriterium in §10 ergänzen:** "Resend-Btn ist während der vollen 250ms-Transition Two-Row ↔ Single-Row sichtbar (visibility=VISIBLE) UND innerhalb des Viewports (kein clipping)." Aktuell sagt §10 nur "Toggle ... korrekt" — nicht messbar.
  2. **Test in §14.2 ergänzen** (UI-Test 8): "Frame-by-frame-Capture während Two-Row → Single-Row während Idle+lastAudio+resendEnabled. Resend-Btn ist in jedem Frame visible+nicht-clipped."
  3. **Inline-Doku** an `predResendVisible` (§8.5): explizit dokumentieren, dass `resendCooldown` **nicht** Teil der Visibility-Predicate ist (sondern nur enabledResolver), damit der bekannte Toggle-Bug strukturell ausgeschlossen ist. Diese Dokumentation ist load-bearing — ohne sie würde ein späterer Refactor den Cooldown in die Visibility-Predicate ziehen und den Bug reaktivieren.

---

### Issue L-3: Race zwischen MotionScene-Transition (250ms async) und State-Update — Slot-Properties werden auf Views gesetzt, **bevor** die Transition fertig ist

- **Category:** [LOGIC]
- **Severity:** Critical
- **Location:** §6 `ImeViewBackend.render` (Z. 411–434), Reihenfolge: transitionToState → applySlotToView → recordingAnimationController.onState.
- **Description:** Die Render-Methode setzt sofort nach Aufruf `motionLayout.transitionToState(targetSceneState)` und ruft direkt anschließend `applySlotToView` für jeden Slot. `transitionToState` ist async (250ms). Während dieser Zeit:
  - Buttons mit `visibilityMode="ignore"` (resend, audio_focus, trash, pause) erhalten ihre neue Visibility sofort durch `applySlotToView` — gut.
  - Buttons ohne `visibilityMode="ignore"` (record_pulse, backspace, space, enter — siehe §7.3) bleiben unter MotionLayout-Kontrolle. `applySlotToView` setzt **trotzdem** `view.visibility` auf VISIBLE/GONE — was MotionLayout aber während der Transition unter Umständen **überschreibt**. Plan dokumentiert das nicht.

  Konkret: `applySlotToView` macht `view.visibility = if (visible) View.VISIBLE else View.GONE`. Für space_btn (Predicate `{ true }`) ist das immer VISIBLE — kein Bug. Für RECORD: Predicate `{ true }` — immer VISIBLE — kein Bug. Für AUDIO_FOCUS: `visibilityMode="ignore"` ist gesetzt (§7.3 Z. 893). Predicate-Switch zwischen Two-Row (`false`) und Single-Row (`true`) → `applySlotToView` setzt View.GONE bzw. View.VISIBLE — MotionLayout ignoriert das wegen `visibilityMode="ignore"` — gut.

  **Aber:** Wenn User direkt während laufender Transition (z.B. ms 100 von 250) einen zweiten Toggle macht, was dann? `transitionToState` mit neuem Ziel — laut MotionLayout-Doku wird die laufende Transition unterbrochen und ein neuer Pfad berechnet. `applySlotToView` läuft auf dem neuen State — aber zwischen `transitionToState(neu)` und dem nächsten Frame kann es einen Frame geben, in dem MotionLayout's interner State und der applySlotToView-State inkonsistent sind. **Risiko:** Buttons sind kurz an der falschen Position oder kurz unsichtbar.

  **Zweites Problem:** Plan §10 (Acceptance Block 5) sagt: *"Re-Inflate (Rotation, Theme-Wechsel): erster Frame zeigt korrekten LayoutMode ohne Animation-Snap (`jumpToState` statt `transitionToState` beim ersten Render)."* — gut, das ist über `state.animationsEnabled` in `render` (§6, Z. 418–422) abgebildet. **Aber:** das Plan-Code-Snippet liest `state.animationsEnabled` als Predicate für transition vs. jump, nicht "ist dies der erste Render". Bei Re-Inflate ist `state.animationsEnabled` weiterhin `true` (User-Pref), also würde das System `transitionToState` nutzen — und einen Animation-Snap zeigen, weil der MotionLayout-Initial-State nicht der Ziel-State ist. **Bug:** Acceptance "kein Animation-Snap" wird durch Code nicht erfüllt.

- **Example scenario:**
  - User hat AnimationsEnabled=true, Layout=SingleRow. User schließt + öffnet Tastatur. `onCreateInputView` inflated MotionLayout (Default-State = `two_row_state` per `app:layoutDescription` und initial-set). Service triggert ersten `manager.onStateChanged(state)`. `render(state, KEYBOARD_SINGLE_ROW)` ruft `motionLayout.transitionToState(R.id.single_row_state)` (weil `animationsEnabled=true`). User sieht für 250ms eine Two-Row-Layout-Animation, die nach Single-Row morpht — Animation-Snap, der laut Acceptance §10 ausgeschlossen sein sollte.
- **Suggestion:**
  1. **Track first-render explizit:** ImeViewBackend hat ein Field `firstRender: Boolean = true`. In `render`: `if (firstRender || !state.animationsEnabled) motionLayout.jumpToState(...) else motionLayout.transitionToState(...); firstRender = false`.
  2. **Inline-Doku an §6 render** ergänzen: "Erster Render nach attach() muss jumpToState statt transitionToState — sonst Animation-Snap beim Re-Inflate, weil MotionLayout-Initial-State immer der erste ConstraintSet ist (`two_row_state`)." Aktuell wird das nur im Acceptance §10 verlangt, aber nicht in der Code-Skizze vorgeführt.
  3. **Re-entrant-Schutz in render():** wenn ein neuer State während laufender Transition kommt, `motionLayout.progress` prüfen oder `motionLayout.setTransition(...)` benutzen, um den neuen Pfad sauber einzubinden. Plan dokumentiert das Verhalten nicht.

---

### Issue L-4: `applySlotToView` und MotionLayout `visibilityMode` — silent contract

- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** §5.1 `applySlotToView` (Z. 336–356); §7.3 visibilityMode-Tabelle (Z. 887–898).
- **Description:** `applySlotToView` setzt unconditionally `view.visibility = if (visible) View.VISIBLE else View.GONE`. **Kein Slot kennt seinen `visibilityMode`-Mode-Status.** Ein Slot-Predicate, das `false` liefert für eine Button-Position ohne `visibilityMode="ignore"`, würde:
  1. View.GONE setzen (sofort durch applySlotToView)
  2. MotionLayout während der nächsten Transition: würde die Position-Animation auf einer GONE-View ausführen — was zu visuellen Artefakten führt (View springt nach GONE, dann Position wechselt unsichtbar, dann VISIBLE).

  Aktuell sind alle Slots, deren Predicate **nicht-konstant** ist, korrekt mit `visibilityMode="ignore"` ausgestattet (§7.3). Aber das ist **nicht durchgesetzt** — wenn jemand später einen neuen Slot hinzufügt (z.B. WIDGET_TOGGLE laut §3.1) und vergisst, `visibilityMode="ignore"` in der Scene zu setzen, gibt es einen subtilen Bug, der nur unter bestimmten Transitionen sichtbar ist.
- **Example scenario:** Block 5b adds the WIDGET_TOGGLE-Slot. `visibilityPredicate = { state.viewMode == ViewMode.KEYBOARD }` (predicate-driven). Implementer vergisst, `<PropertySet motion:visibilityMode="ignore" />` an `widget_toggle_btn` in `motion_scene_keyboard.xml` einzutragen. Test "Toggle Widget-Mode" → Button-Visibility wird durch MotionLayout während Transition resettet → Button blinkt während 250ms.
- **Suggestion:**
  1. Im Plan §7.3 ergänzen: **invariante** "Jeder Slot, dessen `visibilityPredicate` nicht-konstant ist, MUSS in der MotionScene `visibilityMode="ignore"` haben." Sanity-Check: ein Block-5-Validation-Test, der zur Build-Time alle LayoutMode-Slots auf nicht-konstante Predicates scannt und gegen die Scene-XML cross-checkt.
  2. **Erwägen:** `applySlotToView` so erweitern, dass es `view.tag` mit einem Marker setzt und die Scene-Inflater zur Test-Zeit gegen diesen Marker validiert. Oder einfacher: ein KDoc-Tag `@VisibilityModeIgnore` auf jedem nicht-konstanten ButtonSlot, der beim nächsten Pull-Request automatisch geprüft wird (lint-rule).

---

### Issue L-5: Predicate-Matrix-Orthogonalität — `predTrashVisible` und `predPauseVisible` sind **identisch**, aber als zwei Funktionen definiert

- **Category:** [CLEAN] / [LOGIC]
- **Severity:** Important
- **Location:** §8.5 Z. 1131–1137.
- **Description:** Beide Predicates sind:
  ```
  fun predTrashVisible(state) = recording.isActiveOrPaused || pipeline is ReprocessStaging
  fun predPauseVisible(state) = recording.isActiveOrPaused || pipeline is ReprocessStaging
  ```
  Identisch. Drei Risiken:
  1. **Duplikation:** zwei Funktionen, die immer das gleiche tun. Wenn jemand eine ändert (z.B. um eine neue State-Achse) und vergisst, die andere zu ändern, divergiert das System ohne Compile-Fehler.
  2. **Semantisches Drift:** der Plan dokumentiert nicht, **warum** trash und pause die gleiche Visibility haben. Falls das ein Zufall ist, könnte ein zukünftiger Plan-Iter trash und pause unterschiedlich rendern — und der Refactor wäre nicht mehr ein "DRY-Beweis", sondern eine versehentliche Kopplung.
  3. **Trash in §8.4 ist Predicate `{ true }` (REPROCESS_STAGING)**, Pause ist auch `{ true }` aber zusätzlich `enabledResolver = { false }, alphaResolver = { 0.4f }`. Diese Asymmetrie (gleicher Visibility-Predicate, andere Disabled-Logik) deutet darauf hin, dass `predPauseVisible` mehr Sub-State braucht (disabled vs. enabled), was die zentrale Predicate nicht abbildet.
- **Example scenario:** Iter-4-Anforderung: "trash darf nicht sichtbar sein während Pipeline.Preparing aber pause schon (für Cancel-Pipeline-Pause-Interaction)." Implementer ändert `predPauseVisible`, vergisst `predTrashVisible`. Test-Suite (§14.2) testet pro Predicate parameterisiert — aber wenn die parametrisierten Cases auf "TWO_ROW + Recording" beschränkt sind, fängt sie den neuen Pipeline.Preparing-Fall nicht.
- **Suggestion:**
  1. **Konsolidieren** in eine Predicate `predRecordingControlsVisible` (Two-Sub-Use), oder explizit zwei mit Doku, **warum** sie heute gleich sind. Inline-Kommentar an beiden Funktionen: "Heute identisch, kann sich aber unterscheiden — siehe Plan §8.7. Ändere nicht ohne explizite Plan-Iter."
  2. **Test in §14.2 ergänzen:** Parameterisierter Test "predTrashVisible == predPauseVisible für alle State-Permutationen" — wenn die Annahme bricht, ist das ein bewusster Plan-Iter, kein Drift.

---

### Issue L-6: `forKeyboard(state)` Selektor – ReprocessStaging-Branch unterschlägt SingleRow-Mode

- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** §8.6 Z. 1224–1232; §8.8 Z. 1258 (Edge-Cases).
- **Description:** Der Selektor:
  ```
  isStaging                                  -> KEYBOARD_REPROCESS_STAGING
  isPipelineLive && state.singleRowMode      -> KEYBOARD_SINGLE_ROW_SEND_MODE
  ...
  ```
  ReprocessStaging bekommt **immer** `KEYBOARD_REPROCESS_STAGING` (Two-Row-Variante), unabhängig von `singleRowMode`. §8.8 erklärt: "Single-Row macht hier keinen Sinn ... falls User-Test fordert, wird ein zusätzlicher LayoutMode hinzugefügt." Was passiert aber im **Toggle**: User ist in ReprocessStaging-Two-Row, klickt Single-Row-Toggle. Reducer setzt `state.singleRowMode = true`. Catalog wählt weiterhin `KEYBOARD_REPROCESS_STAGING` (Two-Row). MotionScene-State bleibt `reprocess_staging_state` (deriveFrom two_row). **Visuell**: kein Toggle-Effekt, weil der Catalog-Selector den Toggle ignoriert. User sieht: nichts passiert. Verwirrendes UX. Spec dokumentiert das nicht als Acceptance-Kriterium.

  Zweites Problem: `state.singleRowMode = true` wird nicht zurückgesetzt — sobald User ReprocessStaging verlässt (cancel oder send), springt das Layout sofort in `KEYBOARD_SINGLE_ROW`. Das ist konsistent mit der Pref, aber wieder ohne sichtbare Animation, weil der Catalog-Selector im Staging-Mode den Single-Row-Mode ignoriert.

- **Example scenario:** User ist in ReprocessStaging-Two-Row, klickt Single-Row-Toggle. UI rendert weiterhin Two-Row. User klickt nochmal (denkend, der Klick sei ignoriert worden). Now `singleRowMode = false` wieder. User klickt Cancel-Staging. State wechselt zu Idle. Catalog wählt `KEYBOARD_TWO_ROW` (singleRowMode=false). User wundert sich, dass die Tastatur nicht im Single-Row-Mode startet, obwohl er das vorher zweimal getoggelt hat.
- **Suggestion:**
  1. **Verhalten klären in §8.8 Edge-Case-Tabelle:** Was passiert, wenn User Single-Row während ReprocessStaging klickt? Drei Optionen:
     - Toggle wird vom Reducer ignoriert (visible feedback?).
     - Toggle wird angenommen, sichtbar erst nach ReprocessStaging-Exit (heutiges Verhalten der Selektor-Logik — verwirrend).
     - Toggle ist im UI während ReprocessStaging deaktiviert (besser).
  2. **Acceptance §10 ergänzen:** "Single-Row-Toggle während ReprocessStaging → [konkretes Verhalten]." Heute fehlt das.
  3. **Test §14.2 ergänzen:** "Toggle Single-Row während ReprocessStaging → keine Layout-Änderung sichtbar (visible feedback?)."

---

### Issue L-7: Migration §9 — Übergangs-State nicht definiert (KSM-Visibility entfernt vor neuem Code da)

- **Category:** [LOGIC] / [INTEGRATION]
- **Severity:** Critical
- **Location:** §11.8 Migration-Reihenfolge (Z. 1745–1761); §9.3 KSM.applyVisibility (Z. 1335–1346); §13.5 Gap 5 (Z. 2007–2011).
- **Description:** §11.8 sagt: *"5d ist destruktiv — strikt am Ende"*, und *"5c muss vor 5d kommen, sonst gibt es eine Phase, in der weder altes noch neues System Visibility setzt"*. Das adressiert die Reihenfolge, aber **nicht** die Frage des **Übergangs-States innerhalb von 5c**: 5c wired den neuen `KeyboardLayoutManager` ein, aber `KSM.applyRecordingControlsVisibility` läuft **noch**. Beide Systeme schreiben gleichzeitig auf `views.pauseButton.visibility` und `views.trashButton.visibility`. Welcher gewinnt? Letzter Render gewinnt. Wenn die KSM-Cascade **nach** dem Manager-Render läuft, würde KSM die neuen Predicate-basierten Werte überschreiben. Das passiert spätestens wenn `KSM.refresh()` durch andere Achsen (ContentArea-Switch, SmallMode) ausgelöst wird.

  Konkret: In Phase 5c wird der Manager attached, schreibt korrekte Visibility. User klickt QWERTZ-Toggle. KSM.setContentArea(QWERTZ) → applyVisibility → applyRecordingControlsVisibility → setzt pauseButton/trashButton wieder auf alte Logik. Bug: User in QWERTZ während Recording → trash/pause sind plötzlich "richtig" via KSM, aber sobald QWERTZ wieder zurück zu MAIN_BUTTONS geht, kommt der Manager-Render erst wieder beim nächsten State-Update (z.B. Pipeline-Step-Done) — Lücke.

  **Plan §9.3 sagt: "applyRecordingControlsVisibility — gelöscht."** Aber **wann** in der Block-Reihenfolge? §11.8 zeigt 5c (Wiring) und 5d (Cleanup) — Cleanup löscht KLMC, nicht KSM-Methoden. Spec ist mehrdeutig: löscht 5d auch `KSM.applyRecordingControlsVisibility`? Wenn ja, ist die Reihenfolge sicher (parallel-double-write nur kurz). Wenn nein, ist die Übergangs-Race permanent.
- **Example scenario:** 5c wird mergt, 5d ist im nächsten PR. User-Test während dieses PR-Gaps: Recording-Start, dann ContentArea-Switch QWERTZ ↔ MAIN_BUTTONS mehrmals. trash/pause-Visibility flackert, weil zwei Subsysteme beide schreiben. Bug-Report kommt rein. Migration-Reihenfolge ist Schuld, aber niemand bemerkt es bis zum 5d-Merge.
- **Suggestion:**
  1. **§11.8 explizit ergänzen:** "5c-Tail-Step: KSM.applyRecordingControlsVisibility wird durch leere Implementation **ersetzt** (nicht gelöscht). KSM.refresh ruft es weiterhin auf — no-op. Damit gibt es keine Doppelschreibung. 5d entfernt dann die leere Methode + alle Aufrufer." Diese Reihenfolge: write-disable in 5c, full-cleanup in 5d. Risiko-frei.
  2. **Acceptance-Kriterium für 5c:** "Während 5c-Live-Phase: keine zwei Subsysteme schreiben gleichzeitig auf pauseButton.visibility / trashButton.visibility / resendButton.visibility / audioFocusButton.visibility. Verifiziert durch Strict-Mode-Logging ('VisibilityWrite from $caller')."

---

### Issue L-8: Click-Listener-Listen-Lifecycle — `stateRef` ist nullable, Click-Lambda verschluckt während detach

- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** §6 (Z. 444–449); §11.6 (Z. 1655–1662); detach-Methode Z. 404–409.
- **Description:** Der Plan beschreibt: detach() setzt `onAction = null`, aber ClickListener bleiben verdrahtet, weil sie `stateRef` referenzieren — `null` → NoOp. Das ist gut. **Aber:** Plan dokumentiert nicht, wer `stateRef` auf null setzt. Schauen wir uns §6 detach() an (Z. 404–409):
  ```
  override fun detach() {
      this.onAction = null
      // Click-Listener werden NICHT abgemeldet — sie referenzieren `stateRef`,
      // das nach detach null wird; ein versehentlicher Klick auf einen
      // detached Backend ergibt dann ein NoOp.
  }
  ```
  Aber `stateRef` wird **nicht** auf null gesetzt in detach(). Die Field-Initialisierung ist `private var stateRef: DictateUiState? = null`, und `render` setzt `stateRef = state`. Ohne explizites Setzen in detach() bleibt `stateRef = letzter-state-vor-detach`, nicht null. Das heißt:
  - Click nach detach() → `stateRef` ist nicht null → `slot.actionResolver(stateRef!!)` returns echte Action — wäre ein Bug, weil onAction=null den Action verschluckt: `onAction?.invoke(slot.actionResolver(s))` — ja, das `?.invoke` schluckt. OK.
  - **Aber:** wenn `currentSlot(id)` einen Slot mit Side-effect-haltigem actionResolver liefert (z.B. einer, der vor onAction-Aufruf etwas State-mutiert), könnte das ausgeführt werden. Aktuell sind alle actionResolver pure (sie lesen state, returnen Action). Aber das ist nicht erzwungen.

  Zweites Problem: `currentSlot(id)` nutzt `modeRef`, das ebenfalls nicht in detach() gelöscht wird. Wenn User nach detach() ein altes Layout-Mode nochmal manipuliert (z.B. View-Recreation während State-Wechsel), könnte ein veralteter Slot eine veraltete Action emittieren — unwahrscheinlich, aber schwer zu reproduzieren und nur unter Concurrency.
- **Example scenario:** User schließt Tastatur während Recording. Service triggert detach(). User-Klick auf record_btn (Long-Frame-Queue, Touch noch nicht prozessiert) kommt **nach** detach. ClickListener feuert: `stateRef = letzter Recording.Active`, `currentSlot(RECORD)` returnt RECORD-Slot aus letztem Mode, `slot.actionResolver(s) = Action.StopRecordingAndSend`. `onAction?.invoke(...)` ist null → schluckt. OK. Aber: keine User-Visible-Reaktion. Wenn User dachte, der Klick soll noch das Recording stoppen... Subtile UX-Frage.
- **Suggestion:**
  1. **detach() ergänzen:** `stateRef = null; modeRef = null` explizit setzen. Damit ist die Branch `s = stateRef ?: return` defensive korrekt. Plan-Code-Snippet entsprechend anpassen.
  2. **Inline-Doku** an `private var stateRef: DictateUiState? = null` (Z. 394): "Nullable, weil detach() das Backend in einen leeren Zustand versetzt — ein nach-detach-Click ergibt then ein no-op statt eines Calls auf veralteten State."

---

### Issue L-9: `applySlotToView` `Map<LogicalButtonId, View>` — kein Slot-Lookup-Failure-Handling

- **Category:** [LOGIC] / [ROBUSTNESS]
- **Severity:** Nice-to-have
- **Location:** §6 Z. 427–430.
- **Description:** Render-Schleife:
  ```
  mode.rows.flatMap { it.slots }.forEach { slot ->
      val view = buttonViews[slot.logicalId] ?: return@forEach
      applySlotToView(slot, view, state, ctx)
  }
  ```
  `buttonViews` ist eine compile-time-fixe Map (§6 Z. 382–391). Wenn ein neuer LogicalButtonId (z.B. `WIDGET_TOGGLE` aus §3.1) zum Catalog hinzugefügt wird, aber jemand vergisst, ihn der `buttonViews`-Map hinzuzufügen, gibt das `?: return@forEach` ein **Silent-Skip** — der Slot wird nicht gerendert, kein Crash, kein Log, keine Test-Failure auf Build-Time.
- **Example scenario:** Spec 3 Block 6 fügt WIDGET_TOGGLE-Slot in KEYBOARD_TWO_ROW LayoutMode. ImeViewBackend-PR nicht entsprechend erweitert. User-Toggle Single-Row → Layout sieht "OK" aus, weil WIDGET_TOGGLE silently fehlt. Bug erst entdeckt, wenn User auf den nicht-existenten Button klickt (= klickt nichts). Fehler-Lokalisierung schwer.
- **Suggestion:** `?: return@forEach` durch `?: error("No view registered for ${slot.logicalId}")` ersetzen — Crash auf erstem Render in Tests, wenn ein Slot ohne View ist. Production-safe, weil `buttonViews` im Constructor initialized ist und alle Slots aus dem Catalog dort vorhanden sein müssen — anderfalls ist die Spec inkonsistent.

---

### Issue L-10: `RecordingAnimationController.onState` — `prev::class == curr::class` ist falsch für `RecordingState.Active` mit Bluetooth

- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** §11.5 Z. 1606–1625.
- **Description:** Der Cache-Guard:
  ```
  if (prev::class == curr::class) return  // gleiche Sealed-Variante = no-op
  ```
  Wenn `RecordingState.Active` Sub-Properties hat (z.B. `Active(useBluetooth: Boolean)` — siehe heutige `RecordingUiController.applyActiveState(useBluetooth)`), wechselt der `useBluetooth`-Wert während der Recording-Session NICHT die `class`, aber der Wert ändert sich (z.B. Bluetooth verbindet sich später). `prev::class == curr::class` → `return` → Animation aktualisiert die Bluetooth-Anzeige nicht.

  Im Refactor §11.5 ist `Active` aktuell ohne Sub-Properties, aber das ist eine versteckte Annahme. Was passiert, wenn Spec 1 `RecordingState.Active(elapsedMs: Long)` für Timer-Updates hinzufügt? Jeder Timer-Tick ändert `elapsedMs` → `prev::class == curr::class` → `return` → Animation läuft, aber `RecordingAnimationController.onTimerTick` wird separat gerufen, also OK für Timer.
- **Example scenario:** Spec 1 erweitert `Active(useBluetooth: Boolean = false)`. Recording startet ohne Bluetooth (`Active(false)`). User verbindet Bluetooth-Kopfhörer mid-Recording. State wechselt zu `Active(true)`. Animation-Controller sieht `prev = Active(false), curr = Active(true)`, `class` ist gleich → `return`. Animation rendert weiter ohne den Bluetooth-Indicator. Subtiler UI-Bug.
- **Suggestion:**
  1. **`prev == curr`** statt `prev::class == curr::class` (data-class equals statt class-Identity). Datasclass-equals greift Sub-Properties.
  2. **Falls Performance kritisch** (data-class-equals ist nicht teuer, aber vorsichtig): nur die Properties vergleichen, die für Animation relevant sind — z.B. `prev.recording::class == curr.recording::class && prev.animationsEnabled == curr.animationsEnabled`. Aber `prev == curr` ist clean und hier keine Performance-Sorge (~ein Vergleich pro State-Tick).
  3. Inline-Doku: "Cache-Guard auf data-class-Equality, nicht class-Identity, damit Sub-Properties (Bluetooth-Status etc.) nicht verschluckt werden."

---

### Issue L-11: `EnterOverlayHandler` — defensive Reset-Logik widerspricht dem SSOT-Prinzip des Refactors

- **Category:** [CLEAN] / [LOGIC]
- **Severity:** Nice-to-have
- **Location:** §11.7 Z. 1740–1742; §13.1 Z. 1832 (Reset des transient overlays).
- **Description:** §11.7 sagt: *"der heutige `EnterOverlayHandler` mutiert `overlayCharactersLl.visibility = GONE` direkt (Z. 56, 62). Das ist im neuen System überflüssig — die Visibility wird vom `KeyboardStateManager.applyVisibility` jeweils auf GONE gesetzt (Z. 162). Trotzdem: lokale Reset-Logik im Handler bleibt (defensive depth) — kein Bug."*

  "Defensive depth" ist ein direkt-widersprechender Wert zu "SSOT". Wenn Visibility von zwei Stellen gesetzt wird (KSM via cascade UND Handler defensive), gibt es Drift-Potenzial. Aktuell beide setzen `GONE` — übereinstimmend. Aber: Plan §13.1 #11 sagt KSM-Reset "bleibt", und Plan §13.1 #14 sagt Handler-Reset "bleibt". Wenn jemand später entscheidet, dass overlay_characters_ll dynamische Visibility nach State-Maschine bekommen soll, gibt es zwei Quellen.
- **Example scenario:** Iter-5 fügt einen `state.overlayCharactersOpen: Boolean` zu DictateUiState. ContentAreaController liest und setzt `overlayCharactersLl.visibility` reactively. Aber EnterOverlayHandler mutiert weiterhin direkt → Race zwischen reactive controller und imperative handler.
- **Suggestion:**
  1. **Klare Regel im Plan §13.1:** "overlay_characters_ll wird NUR vom EnterOverlayHandler mutiert (es ist eine handler-interne State-Maschine — ähnlich wie BackspaceSwipeHandler die Backspace-Animation kontrolliert). KSM-Reset und Handler-Reset zusammen sind ein Race — KSM-Reset entfällt, EnterOverlayHandler ist authoritative." Das eliminiert die Doppelmutation im Refactor und folgt dem SSOT-Prinzip.
  2. Alternativ: `overlayCharactersLl.visibility` als data-state in DictateUiState aufnehmen, EnterOverlayHandler emittiert Action zum State-Update. Mehr Code, aber konsistent mit SSOT-Vision.

---

### Issue L-12: GLOBAL_ISSUES 1.1.4 — LayoutModule SRP — alle 4 Layout-Achsen in einem Modul

- **Category:** [LOGIC] / [INTEGRATION]
- **Severity:** Important
- **Location:** Spec 1 §3.3 / §15 (LayoutAction); Spec 2 §3.3 (LayoutAction-Section, Z. 165–170).
- **Description:** LayoutModule (Spec 1 §15.x) verwaltet **vier disjunkte Achsen**:
  - `singleRowMode: Boolean` (User-Toggle Two-Row vs. Single-Row)
  - `smallMode: Boolean` (Small-Mode-Toggle)
  - `animationsEnabled: Boolean` (Pref-Mirror)
  - `contentArea: ContentArea` (MAIN_BUTTONS / QWERTZ / EMOJI_PICKER)

  Die 4 Achsen sind **unabhängig** voneinander steuerbar (kein State-Maschinen-Constraint zwischen ihnen, außer: setSmallMode auto-switches contentArea zu MAIN_BUTTONS — KeyboardStateManager Z. 142–144). Im Modular-Orchestrator-Pattern (Spec 1 §15) ist ein Modul typisiert mit einer einzigen `actionClass: KClass<A>`. LayoutAction ist sealed mit 3 Action-Varianten (ToggleSingleRowMode, ToggleSmallMode, SetContentArea). animationsEnabled hat **keine Action** — das ist Pref-Mirror.

  **Problem:** Wenn ein Logic-Bug in einer Achse (z.B. `ToggleSmallMode` setzt contentArea fälschlich auf QWERTZ) gibt es keine Modul-Grenze, die das Logic-Bug-Spreading verhindert. Beispiel: ToggleSmallMode-Reducer **darf** den contentArea ändern (auto-switch). Wenn das versehentlich oder durch Logic-Bug auch im SetContentArea-Reducer passiert (z.B. SetContentArea(QWERTZ) während smallMode=true → soll smallMode auto-disable?), gibt es keine klare Regel.

  Im Module-Pattern ist die übliche Lösung: ein **Modul pro disjunkter Achse** (bzw. pro Action-Klasse). LayoutModule mit vier Achsen ist ein SRP-Risiko: jede Achse hat ihre eigene Logik, alle teilen einen Reducer. Wenn der Reducer wächst (z.B. um Cross-Achsen-Constraints zu erfassen), wird die Modul-Komplexität schwer testbar.
- **Example scenario:** Iter-6-Anforderung: "Wenn animationsEnabled=false, soll ToggleSingleRowMode keine Transition triggern." Das ist eine Cross-Achsen-Logic im LayoutModule-Reducer. Aber ToggleSmallMode hat das gleiche Problem — auch transition-getriggert? Reducer-Code ufert aus, weil 4 Achsen zusammenleben. Entkopplung wäre einfacher gewesen.
- **Suggestion:**
  1. **Splitten** in 3 Module: `SingleRowModule` (singleRowMode + ToggleSingleRowMode), `SmallModeModule` (smallMode + ToggleSmallMode + Cross-Achsen-Hook auf contentArea), `ContentAreaModule` (contentArea + SetContentArea). animationsEnabled ist Pref-Mirror und braucht kein Modul.
  2. **Falls** Konsolidierung gewünscht (weniger Module = weniger Boilerplate): **explizite Doku** im Plan §15 mit konkreten Cross-Achsen-Regeln und Test-Cases pro Cross-Constraint. Aktuell fehlt die.
  3. **Cross-Module-Cascade-Test:** Spec 1 erwähnt `onCrossModuleStateChange`-Hook. Plan §15 dokumentiert keine konkreten Cross-Achsen-Logiken im LayoutModule. Was passiert bei `SetContentArea(QWERTZ)` während smallMode=true? Aktuell in KSM: setSmallMode setzt contentArea zu MAIN_BUTTONS (Z. 142–144). Was setContentArea(QWERTZ) während smallMode=true tun soll, ist undefiniert.

---

### Issue L-13: Slot-Definition mit `.toString()`-Lookup oder einfache `firstOrNull` — O(N²) Edge-Case

- **Category:** [PERFORMANCE] / [LOGIC]
- **Severity:** Nice-to-have
- **Location:** §6 Z. 470–471 `currentSlot()`.
- **Description:** Click-Listener ruft pro Click `currentSlot(id)`:
  ```
  private fun currentSlot(id: LogicalButtonId): ButtonSlot? =
      modeRef?.rows?.flatMap { it.slots }?.firstOrNull { it.logicalId == id }
  ```
  Pro Click: `flatMap` (allocation) + `firstOrNull` (O(N) scan). N=8 Slots, also O(8) — vernachlässigbar. Aber: `flatMap` allocates. Bei 9 Buttons × ~10 Clicks pro Aufnahme = 90 Allocations pro Recording-Session. Marginal, aber nicht null.

  Plan §11.6 erwähnt: "O(9) ist vernachlässigbar". OK. Aber das Allocation-Argument des Plans (L8: "Eine Lambda-Allokation pro Button pro Lifecycle, statt pro Tick") ist inkonsistent — pro Click eine `flatMap`-Allocation ist genauso viel wie das, was L8 zu vermeiden versucht.
- **Example scenario:** Auf Low-End-Gerät (Pixel 4a) mit Allocation-Pressure-Tests: 100 Recording-Sessions × 10 Clicks = 1000 flatMap-Allocations. Marginal, aber GC-Trigger.
- **Suggestion:**
  1. `modeRef?.rows?.asSequence()?.flatMap { it.slots.asSequence() }?.firstOrNull { ... }` — keine intermediate-list-Allocation.
  2. **Oder:** `slotsByLogicalId: Map<LogicalButtonId, ButtonSlot>` als Backend-Field, das in `render` aus dem Mode rebuilt wird. O(1) Lookup. Eine Map-Allocation pro Render-Tick, dann konstanter Lookup pro Click. Trade-off: N Slots = N Map-Entries; rebuild ist O(N).
  3. Wenn marginale Performance kein Issue ist: explizit dokumentieren ("Allocation acceptable, alternative is map rebuild per render").

---

### Issue L-14: Plan §10 Acceptance-Kriterien — kein End-to-End-Bug-Verifikator für die User-Bugs

- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** §10 Z. 1382–1397.
- **Description:** §10 listet 7 Acceptance-Kriterien für Block 5. Der **kritische** ist: "Send-Mode + Single-Row: Send-Button vollständig sichtbar, kein Verdecken (Bug-Eliminierung)." Das ist ein statisches Snapshot-Kriterium. Es deckt **nicht** ab:
  - Den **Übergang** Active → Pipeline-Preparing (siehe L-1).
  - Den **Resend-Button-Verschwinden-beim-Toggle**-Bug (kein Acceptance-Kriterium dafür! Plan §1.1 #3 listet beide Bugs als Auslöser, aber §10 deckt nur einen ab).
  - Race-Conditions bei schnellen Toggles (Multi-Tap).
  - View-Recreation-Cases (Rotation während Active-State).
- **Example scenario:** Block 5 wird abgenommen, weil §10 erfüllt. User-Test in Production: User toggelt Single-Row während Recording. Resend-Btn verschwindet kurz. Bug-Report. Block 5 muss reopened werden.
- **Suggestion:** §10 erweitern um:
  - "Resend-Btn ist während Single-Row-Toggle in Idle+lastAudio sichtbar (visibility=VISIBLE) durchgängig — verifiziert via Frame-Capture."
  - "Active → Pipeline-Preparing-Übergang: kein Frame zeigt trash/pause über record_btn (Bug §1.1 #3)."
  - "Toggle Single-Row während aktive MotionScene-Transition (re-entrant): keine Layout-Korruption, finaler State stabil."
  - "View-Recreation während Active: PulseLayout läuft weiter, kein Animation-Snap auf erstem Frame."

---

## Summary Table

| # | Category | Severity | Issue | Description |
|---|----------|----------|-------|-------------|
| L-1 | [LOGIC] | Critical | Send-Mode-Predicate-Drift-Pfad | TWO_ROW_SEND_MODE.TRASH/PAUSE hardcoded `{ false }` ist der eigentliche Bug-Fix, **nicht** die zentrale Predicate. Drift-Risiko ohne explizite Doku + Tests. |
| L-2 | [LOGIC] | Critical | Resend-Toggle-Bug strukturell vs. dokumentiert | `predResendVisible` adressiert den Bug strukturell — aber Doku, Acceptance, Test fehlen. Cooldown-Trennung von Visibility ist load-bearing aber nicht dokumentiert. |
| L-3 | [LOGIC] | Critical | MotionScene-Transition vs. erste Render | `firstRender` Flag fehlt → Animation-Snap bei Re-Inflate trotz `state.animationsEnabled=true`. Acceptance §10 wird durch Code-Skizze nicht erfüllt. |
| L-4 | [LOGIC] | Important | applySlotToView setzt visibility ohne Wissen über visibilityMode | Neuer Slot ohne `visibilityMode="ignore"` bricht still — keine Lint-Rule, keine Test-Time-Verification. |
| L-5 | [CLEAN] | Important | predTrashVisible == predPauseVisible | Identische Funktionen ohne Doku, warum getrennt. Drift-Risiko bei späterer Achsen-Erweiterung. |
| L-6 | [LOGIC] | Important | forKeyboard ignoriert singleRowMode in ReprocessStaging | Single-Row-Toggle während Staging hat keine Wirkung — undefiniertes UX-Verhalten. Acceptance fehlt. |
| L-7 | [LOGIC] | Critical | Migration §11.8 Übergangs-State unvollständig | 5c wired Manager ein, KSM.applyRecordingControlsVisibility lebt weiter → Doppelmutation während PR-Gap. |
| L-8 | [LOGIC] | Important | detach() löscht stateRef/modeRef nicht | Click nach detach evaluiert auf altem State (gefangen via `onAction = null`, aber unsauberer Lifecycle). |
| L-9 | [ROBUSTNESS] | Nice-to-have | Slot-View-Lookup `?: return@forEach` | Silent-Skip bei missing View-Mapping. Sollte error werfen. |
| L-10 | [LOGIC] | Important | RecordingAnimationController Cache-Guard auf class | `prev::class == curr::class` schluckt Sub-Property-Wechsel (zukünftige Active(useBluetooth) etc.). |
| L-11 | [CLEAN] | Nice-to-have | EnterOverlayHandler defensive-depth widerspricht SSOT | Visibility von zwei Stellen → Drift-Potenzial. |
| L-12 | [INTEGRATION] | Important | LayoutModule SRP — 4 disjunkte Achsen in einem Modul | Logic-Bugs in einer Achse können andere kompromittieren; Cross-Achsen-Constraints undokumentiert. |
| L-13 | [PERFORMANCE] | Nice-to-have | currentSlot flatMap-Allocation pro Click | Inkonsistent mit L8-Argument; einfach via Map-Field zu lösen. |
| L-14 | [LOGIC] | Important | §10 Acceptance fehlt End-to-End-Bug-Tests | Resend-Toggle-Bug nicht in Acceptance; kein Frame-Capture-Test für die zwei User-Bugs. |

---

## Notes for Reviewer

- **Scope:** §13.1–§13.5 reviewed; §13.6 onwards skipped per instruction. §14 (Test-Strategie) cited in L-1, L-2, L-14 as the natural target for the missing acceptance/test additions but not exhaustively reviewed (would have been part of structure-reviewer's section).
- **GLOBAL_ISSUES feedback:**
  - **1.1.4 LayoutModule SRP**: addressed in L-12 — Plan-Logic-Risk is concrete (Cross-Achsen-Constraints undokumentiert), not just abstract SRP.
  - **User-Bug-Fix-Verifikation**: L-1, L-2, L-3, L-14 collectively address whether the refactor reliably fixes the two known user bugs (Send-Btn-verdeckt, Resend-Verschwindet). **Conclusion**: structurally yes, but the plan does not document **why** the fix works (load-bearing inline-doku + tests are missing), so a future iter could regress without compile-time signal.
- **Cross-cutting theme:** The plan's biggest logical risk is **invariants that are structurally enforced but textually undocumented**. L-1 (hardcoded `{ false }`), L-2 (cooldown not in visibility-predicate), L-3 (firstRender), L-7 (5c-Übergangs-State) all share this pattern. The fix is consistently: doc + test, not code-rewrite.
