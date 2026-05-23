# Phase B — S-8 Floating-Overlay-Subsystem: Nicht-existent → OverlayBackend + WindowManager-Lifecycle + WIDGET/HOVER-Differenzierung Migrations-Pfad-Review

**Erstellt:** 2026-05-13
**Reviewer:** Phase-B-Agent S-8 (Subsystem #9 von 9, letzter Phase-B-Agent)
**Plan-Version vor Edits:** Stand nach S-6-Apply-Pass (Commit `fcc72ec`, S-6-Report `phase-b-s6-keyboard-renderer.md`)
**Vorgänger-Reports:** S-1 (`9f84730`), S-2 (`47a4e06`), S-3 (`af0bd00`), S-4 (`c895695`), S-7 (`2b27cf9`), S-5 (`f34e484`), S-9 (`e418b87`), S-6 (`fcc72ec`).

---

## Summary

S-8 ist das **letzte** Subsystem im Phase-B-Quality-Gate und das **einzige** ohne bestehende Code-Anker — Spec 3 schafft alle Klassen, das XML, die Strings, die Manifest-Permission neu. Daher konnten klassische "Refactor-only-the-API"-Bugs (wie S-7 F-1, S-6 F-1) hier nicht entstehen — es gab keine alte Schnittstelle, die jemand vergessen hätte mitzuziehen. Statt dessen kristallisierte sich eine andere Klasse von Findings heraus: **(a) Lifecycle-Idempotenz im Wrapper unvollständig** — `detach()` hatte `IllegalArgumentException`-Catch, aber `attach()` und `update()` nicht, obwohl identische OS-seitige-Detach-Race-Pfade existieren; **(b) Doku-Drift gegen Code-SoT** in §13.4 (Click-Listener-Tabelle) + §7.1 (SSoT-Note zu Spec 1 §15); **(c) FSM-Vollständigkeit** — §7.3 listet 6 von 7 Übergängen, der "Geist-Widget-Bug-Strukturschutz"-Übergang (T7) lebt nur in der Coupling-Matrix.

Die anderen Prüf-Achsen sind weitgehend wasserdicht: OVERLAY-Slot 2-arg-Resolver-Migration ist nach S-7 F-1 + F-2 vollständig in §3.1 verankert (inkl. `resolveOverlayRecordAction`-Helper mit IOException-Handling); `Action.OverlayAction`-sealed-class in Spec 2 §3.3 ist vollständig (`ResetSuppressBit`, `SetUserPrefersWidget`, `OnOverlayPermissionChanged`, `RequestOverlayPermission`, `SuppressAutoOverlayUntilNextSession`, `MarkOverlayOnboardingShown`, `DismissOverlayOnboarding`, `UpdateOverlayPosition`); WIDGET_TOGGLE-Slot ist nach S-6 F-3 in allen 5 KEYBOARD-LayoutModes verankert mit `Action.ViewModeAction.ToggleViewModeWidget`, was Spec 3 §6.1/§7.3 konsumiert; Drag-Hoheit + applyPosition-early-return ist sauber (R.18 + F-6/GAP-7); 5-Button-Layout WIDGET/HOVER-Differenzierung ist klar in §3.1 + §10 Acceptance.

**Zwei Critical-Bugs (Wrapper-Lifecycle):**

1. **`AndroidOverlayWindow.update()` ohne `IllegalArgumentException`-Catch (SRP-Verstoß + Crash-Pfad).** Der Wrapper (§4.1 Z. 335–337 vor Fix) rief `windowManager.updateViewLayout(view, params)` ohne try/catch. Bei Permission-Revoke zur Laufzeit (User toggelt in Settings ab, während Overlay sichtbar ist) detached Android die View OS-seitig, aber unser `attached`-Bit bleibt `true` (Android sendet kein Broadcast). Der nächste `applyPosition()`-Call (z.B. ein State-Drag-Update oder ein `render()`-Re-apply) ruft `updateViewLayout` auf einer nicht-mehr-attached View → `IllegalArgumentException` → unhandled-Exception-Crash. Plus SRP-Verstoß: der Wrapper sollte Lifecycle-Idempotenz garantieren, aber das galt nur für `detach()`.

2. **`AndroidOverlayWindow.attach()` `BadTokenException`-Catch lebt im Backend (SRP/DIP-Verstoß).** Der Wrapper rief `windowManager.addView` ohne try/catch (§4.1 Z. 330–334 vor Fix); stattdessen lebte der Catch im `OverlayBackend.inflateAndAttach()` (§4.2 Z. 522 vor Fix). Damit muss das Backend einen WindowManager-spezifischen Exception-Typ kennen — DIP-Verstoß: Backend soll polymorph über das `OverlayWindow`-Interface arbeiten, ohne `WindowManager.BadTokenException` importieren zu müssen. Plus die Inkonsistenz: warum sollte `BadTokenException` ins Backend wandern, aber `IllegalArgumentException` aus `removeView` im Wrapper bleiben?

**Drei Important-Findings:**

3. **§13.4 Tabelle Click-Listener-Spalte widerspricht §4.2-Code (post-Issue-3.1.10 Doppel-Truth).** Die Tabelle (Z. 2209 vor Fix) sagt Overlay nutzt "pro Render"-Listener (`view.setOnClickListener { onAction?.invoke(slot.actionResolver(state)) }`), aber §4.2 (Z. 433–443) zeigt `wireStaticOverlayHandlers` einmal-pro-inflate mit `stateRef`/`modeRef`-Field-Pattern — identisch zum IME. Doppel-Truth-Quelle: zwei Spec-3-Sektionen, zwei verschiedene Click-Listener-Setups für denselben Backend. §13.4 war vor Issue 3.1.10 (User-Decision Option A, Spec-2-Pattern auf Overlay übertragen) geschrieben, §4.2 wurde dafür refaktoriert, aber §13.4 nicht synchron gezogen.

4. **T7 (HOVER → KEYBOARD via PipelineDone-Cascade) als Übergang in §7 fehlt — Geist-Widget-Bug-Strukturschutz nicht aus FSM-Sektion ableitbar.** Phase-A Inventur (Z. 588–591) listet T7 als kritischen Test-Pflicht-Mode-Transition ("PipelineDone in HOVER triggert ViewMode.KEYBOARD via Cross-Module-Cascade — Geist-Widget-Bug strukturell ausgeschlossen"). Aber Spec 3 §7.3 zeigt nur T1–T6; T7 ist nur indirekt über Spec 1 §15.1 Coupling-Matrix (`Pipeline × ViewMode = R(state.pipeline) C(ViewModeAction.OnPipelineDone)`) + Hauptdok §10 Acceptance Block 1 erreichbar. Ein Implementer, der nur §7 liest, würde die Pipeline-Done-Cascade als implementations-pflichtiges Verhalten übersehen.

5. **Spec 1 §15 hat keine eigene `ViewModeModule`-Implementation; Spec 3 §7.1 SSoT-Note verweist auf "Spec 1 §15 kanonisch implementiert" — was nicht stimmt.** §7.1 SSoT-Note (Z. 1300–1302 vor Fix) sagt "ViewMode-FSM ist im ViewModeModule (Spec 1 §15) kanonisch implementiert", aber Spec 1 §15.2/§15.3/§15.6 enthalten nur die kanonischen Implementationen für RecordingModule, AudioModule, KeyboardInputModule. ViewModeModule ist in §15.1 Modul-Inventar als Zeile #4 + in der Coupling-Matrix verankert, aber **kein vollständiger Code-Block** vorhanden. Spec 3 §6.1 + §7.3 sind faktisch die einzige Quelle für die `reduce`-Skelette + `computeViewMode`-Truth-Table.

**Zwei Minor-Findings:**

6. **Permission-Boot-Default-Race-Window in §5 nicht dokumentiert.** `OverlayState.hasPermission = false` als Boot-Default. Zwischen Service-Start und dem ersten `OverlayPermissionObserver.init()`-Dispatch sieht jeder State-Subscriber `hasPermission = false` — falls in diesem Fenster ein `render(state, mode)` mit `state.viewMode in (WIDGET, HOVER)` triggert, fällt der Code in den Fallback-Pfad. Strukturell nicht erreichbar (User muss IME öffnen, bevor er Recording starten kann), aber bewusste Akzept-Eigenschaft sollte dokumentiert sein.

7. **`dragHandler?.isDragging() == true`-null-Verhalten in §4.2 `applyPosition` undokumentiert.** `dragHandler == null` zwischen `detach()` und nächstem `inflateAndAttach()` → `?.isDragging()` evaluiert zu null → `== true` ist false → early-return triggert NICHT. Das ist korrekt (ohne aktiven Drag-Handler keine Drag-Hoheit zu schützen), aber subtil und ohne Kommentar.

**Befund:** **7 Findings (2 Critical, 3 Important, 2 Minor) — ~8 Plan-Edit-Operationen in 2 Dateien (Spec 3: 7, Hauptplan: 1).**

**Hauptlücke:** Das `AndroidOverlayWindow`-Wrapper-Pattern hatte den `IllegalArgumentException`-Catch nur für `detach()`. `update()` und `attach()` waren ungeschützt — beide haben identische OS-seitige-Detach-Race-Pfade. Ohne S-8-Audit hätte der Block-6-Implementer das Wrapper-Pattern aus dem Plan 1:1 umgesetzt; der Crash wäre erst im QA-Test mit physischem Device + manueller Permission-Revoke aufgetreten. Lesson: wenn ein Wrapper für ein Resource-Lifecycle existiert, müssen ALLE Lifecycle-Methoden idempotent gegen den OS-seitigen-Detach-Race sein, nicht nur eine. Plus die Doku-Drift in §13.4 + §7.1 zeigt einen anderen Verschleißpfad: **Verifikations-Sektionen werden bei späteren Refactors leicht stale** — §13.4 war ein "Konsistenz-Beweis"-Block, der nach Issue 3.1.10 nicht synchron gezogen wurde, obwohl §4.2 (der Code-SoT) korrekt refaktoriert war.

---

## Findings + Applied Fixes

### F-1 `AndroidOverlayWindow.update()` ohne `IllegalArgumentException`-Catch

- **Severity:** Critical
- **Prüf-Achse:** 2 (Window-Lifecycle Edge-Cases §11.6)
- **Was:** Spec 3 §4.1 Z. 335–337 (vor Fix):
  ```kotlin
  override fun update(view: View, params: WindowManager.LayoutParams) {
      if (attached) windowManager.updateViewLayout(view, params)
  }
  ```
  Kein try/catch. Bei Permission-Revoke zur Laufzeit detached Android die View OS-seitig (Service-Cleanup, Window-Token-Invalidation), aber unser `attached`-Bit bleibt `true` — Android sendet kein `OverlayPermissionChanged`-Broadcast (§11.6 Edge-Case-Tabelle dokumentiert das). Der nächste `applyPosition()`-Call (z.B. ein State-Drag-Update über `Action.OverlayAction.UpdateOverlayPosition`-Cascade oder ein `render()`-Re-apply nach Cross-Module-Cascade) ruft `updateViewLayout` auf einer nicht-mehr-attached View → `IllegalArgumentException: View=... not attached to window manager` → unhandled-Exception-Crash.
- **Konsequenz:** Stiller Daten-Bug bis zum Crash. Permission-Revoke zur Laufzeit ist zwar selten, aber kein Edge-Case, der vor dem App-Submit-Test garantiert auftritt — User könnten den Bug nach Wochen in der Production-Telemetrie als Mysterium melden. Plus SRP-Verstoß: Wrapper sollte ALLE Window-Lifecycle-Idempotenz-Garantien tragen.
- **Fix angewandt:**
  - **Spec 3 §4.1:** `update()` um try/catch für `IllegalArgumentException` erweitert; `attached = false` + Log; idempotenter Recovery-Pfad ("beim nächsten render() läuft das Backend einen sauberen re-attach, der bei fehlender Permission über den Gate-Check früh aussteigt").
  - **Spec 3 §11.6 Edge-Case-Tabelle:** Neuer Eintrag "`windowManager.updateViewLayout` wirft `IllegalArgumentException` (View war OS-seitig schon detached)" — verlinkt die §4.1-Auflösung. Bestehender Edge-Case "Permission wird in System-Settings revoked, während Overlay sichtbar" aktualisiert: erwähnt jetzt explizit, dass der Wrapper den OS-seitigen Detach im update-Pfad sauber abfängt.
  - **Spec 3 §4.1 (NEU):** Lifecycle-Idempotenz-Vertrag-Block nach dem Wrapper-Code — "Wrapper ist die alleinige SRP-Heimat für WindowManager-Exception-Behandlung. Alle drei Methoden (`attach`/`update`/`detach`) sind idempotent gegen OS-seitige Detach-Race".

### F-2 `AndroidOverlayWindow.attach()` `BadTokenException`-Catch lebt im Backend (SRP/DIP-Verstoß)

- **Severity:** Critical
- **Prüf-Achse:** 2 (Window-Lifecycle Edge-Cases), 6 (DIP)
- **Was:** Spec 3 §4.1 Z. 330–334 (vor Fix):
  ```kotlin
  override fun attach(view: View, params: WindowManager.LayoutParams) {
      if (attached) return
      windowManager.addView(view, params)
      attached = true
  }
  ```
  Kein try/catch für `BadTokenException`. Stattdessen lebte der Catch im `OverlayBackend.inflateAndAttach()` (§4.2 Z. 522 vor Fix):
  ```kotlin
  try {
      overlayWindow.attach(view, params)
      // ...
  } catch (e: WindowManager.BadTokenException) {
      Log.w(TAG, "Overlay attach failed — permission revoked at runtime?", e)
      buttonViews = emptyMap()
  }
  ```
- **Konsequenz:** SRP-Verstoß — Backend kennt einen WindowManager-spezifischen Exception-Typ; DIP-Verstoß — Backend kann nicht polymorph mit dem `OverlayWindow`-Interface arbeiten ohne `WindowManager.BadTokenException` zu importieren. Zusätzliche Inkonsistenz: `removeView`-`IllegalArgumentException` lebt im Wrapper, aber `addView`-`BadTokenException` lebt im Backend — kein erkennbares Pattern. Plus bei einem späteren Test-Backend (`FakeOverlayWindow`): das Backend würde `BadTokenException` catchen, aber `FakeOverlayWindow` wirft den niemals → Backend-Catch-Block ist Dead-Code im Test.
- **Fix angewandt:**
  - **Spec 3 §4.1:** `attach()` um try/catch für `BadTokenException` erweitert; bei Catch `attached = false` + Log; konsistent mit `update()` und `detach()`-Idempotenz-Pattern.
  - **Spec 3 §4.2 `inflateAndAttach()`:** try/catch entfernt. Stattdessen prüft das Backend nach `overlayWindow.attach(view, params)` über `overlayWindow.isAttached() == false`, ob der Wrapper-Attach erfolgreich war, und bricht ab. Backend braucht **keinen** Import für `WindowManager.BadTokenException` mehr.
  - **Spec 3 §11.6 Edge-Case-Tabelle:** Edge-Case-Eintrag aktualisiert ("`windowManager.addView` wirft `BadTokenException`": Catch im Wrapper, Backend prüft `isAttached() == false`).

### F-3 §13.4 Tabelle Click-Listener-Spalte widerspricht §4.2-Code (post-Issue-3.1.10 Doppel-Truth)

- **Severity:** Important
- **Prüf-Achse:** 8 (5-Button-XML + Strings, Cross-Spec-Konsistenz)
- **Was:** Spec 3 §13.4 Tabelle Z. 2209 (vor Fix):
  > | Click | static in `wireStaticHandlers` (state-snapshot via `stateRef`/`modeRef` Field) | `view.setOnClickListener { onAction?.invoke(slot.actionResolver(state)) }` (pro Render) | backend-spezifisch begründet (Drag-Routing-Konflikt im Overlay) |

  Aber §4.2 Z. 433–443 zeigt:
  ```kotlin
  private fun wireStaticOverlayHandlers() {
      buttonViews.forEach { (id, view) ->
          view.setOnClickListener {
              val s = stateRef ?: return@setOnClickListener
              val slot = currentSlot(id) ?: return@setOnClickListener
              slot.actionResolver(s, services)?.let { onAction?.invoke(it) }
          }
      }
  }
  ```
  Einmal-pro-inflate (in `inflateAndAttach`), mit `stateRef`/`modeRef`-Field-Pattern — identisch zum IME (Spec 2 §6).
- **Konsequenz:** Doppel-Truth-Quelle — zwei Spec-3-Sektionen zeigen zwei verschiedene Click-Listener-Setups für denselben Backend. Ein Implementer hätte abhängig von der Lesreihenfolge entweder das Backend-spezifische "pro Render"-Pattern oder das geteilte stateRef-Pattern implementiert. Das stateRef-Pattern ist das aktuelle Soll (begründet in §4.2-FIX-Kommentar zu Issue 3.1.10 User-Decision Option A: "Spec-2-Pattern: stateRef-driven, einmaliger Click-Listener — eliminiert Single-Frame-Race und Performance-Allocation pro Render-Tick"). Vermutlicher Hintergrund: §13.4 war vor Issue 3.1.10 geschrieben, §4.2 wurde dafür refaktoriert, aber §13.4 nicht synchron gezogen.
- **Fix angewandt:**
  - **Spec 3 §13.4 Tabelle:** Click-Listener-Spalte auf "identisch" umgestellt; Drag-Routing-Konflikt-Auflösung über Touch-Listener-Hierarchie (Click-Listener feuern nur, wenn der Drag-Handler unter dem 8dp-Threshold bleibt) als Erklärung ergänzt; FIX-Kommentar mit Issue-3.1.10-Begründung.
  - **Spec 3 §13.4 (Konsistenz-Beweis-Absatz):** Erweitert um "Mit Issue 3.1.10 (Spec 2-Pattern auf Overlay übertragen) ist auch das Click-Listener-Setup identisch — keine Pattern-Drift zwischen den Backends."

### F-4 T7 (HOVER → KEYBOARD via PipelineDone-Cascade) als Übergang in §7 fehlt

- **Severity:** Important
- **Prüf-Achse:** 4 (Triangle-FSM T1–T7)
- **Was:** Phase-A Inventur (Z. 588–591) listet T7 als kritischen Test-Pflicht-Mode-Transition:
  > **Mode-Transition T7 (Cluster mit 3.1.2):** "Geist-Widget"-Bug strukturell ausgeschlossen — `PipelineDone` in HOVER triggert `ViewMode.KEYBOARD` via Cross-Module-Cascade. Test-Plicht in Hauptdok §10 Acceptance Block 1.

  Aber Spec 3 §7.3 listet nur T1–T6 explizit:
  - T1: KEYBOARD → WIDGET
  - T2: WIDGET → KEYBOARD (Schließen-Button)
  - T3: KEYBOARD → HOVER (View hidden + Pipeline aktiv)
  - T4: WIDGET → HOVER (View hidden + Pipeline aktiv, war WIDGET)
  - T5: HOVER → KEYBOARD (View kommt zurück, war NICHT WIDGET)
  - T6: HOVER → WIDGET (View kommt zurück, war WIDGET)

  T7 (PipelineDone-Auslöser in HOVER → KEYBOARD via Cross-Module-Cascade aus PipelineModule.onCrossModuleStateChange → ViewModeAction.OnPipelineDone → ViewModeModule.reduce re-evaluiert `computeViewMode`) ist nur indirekt über Spec 1 §15.1 Coupling-Matrix (`Pipeline × ViewMode = R(state.pipeline) C(ViewModeAction.OnPipelineDone)`) + Hauptdok §10 Acceptance Block 1 ableitbar.
- **Konsequenz:** Ein Implementer, der nur §7 liest, würde die Pipeline-Done-Cascade als implementations-pflichtiges Verhalten übersehen — der "Geist-Widget"-Bug (Overlay-Window bleibt sichtbar nach Pipeline-Done in HOVER) wäre ein verzögerter Bug-Report aus QA, nicht ein verhinderter Strukturschutz. Plus: §10 Acceptance verifiziert das Verhalten zwar, aber via Hauptdok-Block — nicht via Spec-3-eigene Acceptance.
- **Fix angewandt:**
  - **Spec 3 §7.3:** T7-Block neu eingefügt mit Auslöser (PipelineDone-Cascade aus PipelineModule.onCrossModuleStateChange) + reduce-Snippet im ViewModeModule (re-compute via `computeViewMode` mit `pipelineActive=false`) + WIDGET-Variante (auch wenn `userPrefersWidget=true` geht es auf KEYBOARD, weil `computeViewMode(visible=false, userToggledWidget=true, pipelineActive=false) → KEYBOARD` per Truth-Table; Widget braucht sichtbare IME oder aktive Pipeline).
  - **Spec 3 §10 Acceptance:** T7-Klausel ergänzt ("T7 Geist-Widget-Bug-Strukturschutz" mit Test-Pflicht-Beschreibung `pipelineDoneInHover_transitionsToKeyboard_overlayDetached`).
  - **Spec 3 §14.3 Manual-Test-Plan:** T7-Zeile in Übergangs-Tabelle ergänzt.

### F-5 Spec 1 §15 hat keine eigene `ViewModeModule`-Implementation; SSoT-Note ist irreführend

- **Severity:** Important
- **Prüf-Achse:** 9 (Cross-Spec-Konsistenz)
- **Was:** Spec 3 §7.1 SSoT-Note Z. 1300–1302 (vor Fix):
  > **SSoT-Note:** Die ViewMode-FSM ist im **ViewModeModule** (Spec 1 §15) kanonisch implementiert; dieser Abschnitt zeigt die Transition-Logik aus Sicht von Spec 3 als Referenz für den Implementierer.

  Aber Spec 1 §15.x hat NUR vollständige Code-Implementationen für:
  - §15.2 RecordingModule (Beispiel-Implementation, vollständig)
  - §15.3 AudioModule mit Cross-Module-Observer (Beispiel)
  - §15.6 KeyboardInputModule (Effect-only — Unit-State)

  ViewModeModule wird in §15.1 Modul-Inventar als Zeile #4 + in der Coupling-Matrix verankert, aber **kein vollständiger Code-Block** vorhanden. Spec 3 §6.1 + §7.3 (T1–T7) sind faktisch die einzige Quelle für die `reduce`-Skelette + `computeViewMode`-Truth-Table.
- **Konsequenz:** Ein Implementer sucht Spec 1 §15.x ViewModeModule, findet keinen Code, folgt der SSoT-Note ins Leere. Mögliche Reaktionen: (a) nach §15.2 RecordingModule-Pattern selbst auflegen (ohne Spec-3-Snippets zu konsultieren, was die Truth-Table verfehlt); (b) den Plan-Owner pingen ("wo lebt der ViewModeModule-Code?"); (c) Spec 3 §6.1 + §7.3 doch als kanonisch nehmen und auf eigene Faust den Modul-Boilerplate (`object ViewModeModule : DictateModule<...>`) hinzufügen. Alle drei Reaktionen kosten Zeit, und (a) führt zu Bugs (Truth-Table verfehlt).
- **Fix angewandt:**
  - **Spec 3 §7.1 SSoT-Note:** Um expliziten "Implementations-Heimat-Klarstellung"-Block erweitert: Spec 1 §15 hat Beispiel-Modul-Implementationen (RecordingModule §15.2, AudioModule §15.3, KeyboardInputModule §15.6); die übrigen Module — inklusive ViewModeModule — folgen demselben Modul-Pattern, sind aber nicht vollständig als Code-Blöcke in Spec 1 abgedruckt; für ViewModeModule liefert Spec 3 §7.1 + §6.1 + §7.3 (T1–T7) den konkreten Implementations-Anchor; Spec 1 §15.1 verankert den Modul-Inventar-Eintrag + Coupling-Matrix-Zeilen. Es gibt **keinen** zweiten Source-of-Truth.
  - Spec 1 bewusst NICHT erweitert — eine vollständige ViewModeModule-Implementation in Spec 1 hinzuzufügen wäre S-4-Scope, nicht S-8-Scope; plus es würde die Spec-1-Architektur-Entscheidung (Spec 1 = Beispiele + Pattern; Spec 3 = OverlayModule + ViewModeModule kanonisch via Issue 3.1.1 / 3.1.2 Option A) umkehren. Die Klarstellung in §7.1 ist der minimale Eingriff.

### F-6 Permission-Boot-Default-Race-Window nicht dokumentiert

- **Severity:** Minor
- **Prüf-Achse:** 3 (Permission-Lifecycle ohne Broadcast §5.5)
- **Was:** Spec 1 §3 Z. 183 (im OverlayState):
  ```kotlin
  val hasPermission: Boolean = false,
  ```
  Boot-Default ist `false`. Zwischen Service-Start und dem ersten `OverlayPermissionObserver.init()`-Dispatch (vom IME-onCreate, Spec 3 §5.0 Z. 988–991) sieht jeder State-Subscriber `hasPermission = false`. Falls in diesem Fenster ein `render(state, mode)` mit `state.viewMode in (WIDGET, HOVER)` triggert, fällt der Code in den Fallback-Pfad (§4.2 Z. 399 `if (!state.overlay.hasPermission) teardownOverlay()`).
- **Konsequenz:** In der Praxis nicht erreichbar: HOVER-Auto-Trigger setzt `state.recording.isActiveOrPaused` voraus → Recording startet immer aus dem IME-View, der vorher `init()` durchgelaufen ist. WIDGET-Toggle wird vom User explizit angeklickt — auch nur möglich, wenn der IME-View bereits sichtbar war. Aber: ein future Maintainer könnte das Pattern brechen, indem er z.B. eine "Wake-from-Notification"-Funktion einbaut, die HOVER direkt aus dem Service-Wake-up startet — dann wäre das Race-Window erreichbar. Dokumentation als bewusste Akzept-Eigenschaft schützt vor diesem Drift.
- **Fix angewandt:**
  - **Spec 3 §5.0:** Boot-Default-Race-Window-Block ergänzt mit (a) Konkreter Wert (`hasPermission = false` Boot-Default), (b) Race-Window-Beschreibung (Service-Start bis erster `init()`-Dispatch), (c) Strukturelle Nicht-Erreichbarkeit (HOVER-Trigger setzt aktives Recording voraus, WIDGET-Toggle vom User explizit), (d) "Polling wäre Anti-Pattern"-Begründung.

### F-7 `dragHandler?.isDragging() == true`-null-Verhalten in §4.2 applyPosition undokumentiert

- **Severity:** Minor
- **Prüf-Achse:** 5 (Drag-Hoheit R.18)
- **Was:** Spec 3 §4.2 Z. 463 (vor Fix):
  ```kotlin
  if (dragHandler?.isDragging() == true) return
  ```
  Kein Kommentar zum Null-Verhalten. `dragHandler == null` zwischen `detach()` (Z. 530–531) und nächstem `inflateAndAttach()` (Z. 504) → `?.isDragging()` evaluiert zu null → `== true` ist false → early-return triggert NICHT.
- **Konsequenz:** Das ist korrekt (ohne aktiven Drag-Handler keine Drag-Hoheit zu schützen — `applyPosition` läuft normal weiter), aber subtil. Ein Future-Maintainer könnte versehentlich das Pattern auf `dragHandler?.isDragging() ?: true` umstellen ("safer default"), was den umgekehrten Bug auslöst: während Drag-Handler-Detach-Phase würde jedes State-Update das Position-Set blockieren.
- **Fix angewandt:**
  - **Spec 3 §4.2:** 4-Zeilen-Kommentar an der Stelle ergänzt — Null-Verhalten explizit dokumentiert + Begründung (ohne aktiven Drag-Handler existiert keine Drag-Hoheit, die zu schützen wäre).

---

## Vollständige Prüf-Achsen-Coverage

| Prüf-Achse | Resultat | Findings |
|------------|----------|----------|
| 1. OVERLAY-Slot 2-arg-Resolver + `resolveOverlayRecordAction` (S-7-Folgepfad) | ✅ Alle 5 Slots (OVERLAY_RECORD, OVERLAY_SEND, OVERLAY_PAUSE, OVERLAY_TRASH, OVERLAY_CLOSE) auf 2-arg `actionResolver` umgestellt; `resolveOverlayRecordAction`-Helper mit IOException-Handling in §3.1 verankert (analog Spec 2 §8.5 `resolveRecordAction`); Migrations-Hinweis für 1-arg-Lambda-Stellen im Plan. | — |
| 2. Window-Lifecycle Edge-Cases (§11.6) | ⚠ Zwei kritische Lücken: `update()` ohne `IllegalArgumentException`-Catch; `attach()` ohne `BadTokenException`-Catch im Wrapper. | F-1, F-2 |
| 3. Permission-Lifecycle ohne Broadcast (§5.5) | ✅ `OverlayPermissionObserver.refresh()` an `onCreateInputView` / `onStartInputView` ist exhaustiv für die User-relevanten Pfade; Settings-Deep-Link über `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` korrekt. Permission-Loss-Cascade (§4.8 Z. 955–960) emittiert `ViewModeAction.SetViewMode(KEYBOARD)` (Action existiert in Spec 2 §3.3 Z. 226). | Minor F-6 |
| 4. Triangle-FSM T1–T7 | ⚠ T1–T6 explizit verankert, T7 fehlt. Post-S-9-Cascade-Form korrekt (Mode 2). | F-4 |
| 5. Drag-Hoheit vs. State-Read-Konflikt (R.18) | ✅ `applyPosition()` early-returnt bei `isDragging() == true`; `view.post { applyPosition(stateRef) }`-Hook (F-6 / GAP-7) deckt View-Größe-0-First-Render ab; Drag-State im DragHandler-Modul (begründet via SRP, §4.6 OverlayDragHandler). | Minor F-7 |
| 6. 5-Button-Layout WIDGET/HOVER-Differenzierung (OPEN-2) | ✅ WIDGET-autark in Idle (Record-Button sichtbar + enabled); HOVER disabled-Pfad (Record + Send disabled via Resolver, nicht via Layout-Block — DRY); Schließen-Button-Differential in §3.1 + §6 + §13.3 vollständig; Phase-2 STANDALONE_OVERLAY explizit Out-of-Scope markiert (§10 Acceptance + §11.9). | — |
| 7. `userPrefersWidget`-Persistenz (§11.9) | ✅ Bewusst transient, in-Memory im PipelineService; konsistent mit `suppressAutoOverlayUntilNextSession` (KG-RSB-1, S-9-Resolution); OOM-Recovery-Verhalten dokumentiert. | — |
| 8. 5-Button-XML + Strings + Manifest | ✅ `overlay_5button_layout.xml` mit 5 Buttons in zwei Reihen (Reihe 1: Record/Send/Pause; Reihe 2: Trash/Close mit Schließen unten rechts) korrekt; `overlay_background.xml` + `styles_overlay.xml` definiert; 5 Strings + 4 contentDescription-Strings in §5.3; `SYSTEM_ALERT_WINDOW`-Permission im Manifest. | — |
| 9. Cross-Spec-Konsistenz `Action.OverlayAction` + WIDGET_TOGGLE | ✅ `Action.OverlayAction`-sealed-class in Spec 2 §3.3 vollständig (alle 8 Varianten); WIDGET_TOGGLE-Slot in allen 5 KEYBOARD-LayoutModes verankert nach S-6 F-3 mit `Action.ViewModeAction.ToggleViewModeWidget`, was Spec 3 §6.1/§7.3 konsumiert. ⚠ §13.4 Tabelle Click-Listener-Spalte stale; ⚠ §7.1 SSoT-Note unklar. | F-3, F-5 |

---

## Edit-Liste

| Datei | Sektion | Art | Inhalt |
|-------|---------|-----|--------|
| Spec 3 | §4.1 AndroidOverlayWindow | Refactor | Wrapper-interne Exception-Hygiene für `attach()` (`BadTokenException`) + `update()` (`IllegalArgumentException`); Lifecycle-Idempotenz-Vertrag-Block (F-1, F-2) |
| Spec 3 | §4.2 inflateAndAttach | Refactor | try/catch im Backend entfernt; Prüfung über `overlayWindow.isAttached() == false`; FIX-Kommentar mit S-8-Begründung (F-2) |
| Spec 3 | §4.2 applyPosition | Add | 4-Zeilen-Kommentar zum `dragHandler?.isDragging() == true`-Null-Verhalten (F-7) |
| Spec 3 | §5.0 OverlayPermissionObserver | Add | Boot-Default-Race-Window-Block (F-6) |
| Spec 3 | §7.1 SSoT-Note | Refactor | Implementations-Heimat-Klarstellung — Spec 1 §15 hat Beispiel-Module; ViewModeModule-Implementation lebt in Spec 3 §7.1 + §6.1 + §7.3 (F-5) |
| Spec 3 | §7.3 (NEU T7) | Add | T7-Block: HOVER → KEYBOARD via PipelineDone-Cascade (Geist-Widget-Bug-Strukturschutz) (F-4) |
| Spec 3 | §10 Acceptance | Add | T7-Klausel "T7 Geist-Widget-Bug-Strukturschutz" mit Test-Pflicht (F-4) |
| Spec 3 | §11.6 Edge-Case-Tabelle | Refactor | Drei Race-Pfade-Einträge aktualisiert (alle drei WindowManager-Calls jetzt im Wrapper) (F-1, F-2) |
| Spec 3 | §13.4 Click-Listener-Tabelle | Refactor | "pro Render" → "identisch" mit Drag-Routing-Konflikt-Auflösungs-Erklärung; Konsistenz-Beweis-Absatz erweitert (F-3) |
| Spec 3 | §14.3 Manual-Test-Plan | Add | T7-Zeile in Übergangs-Tabelle (F-4) |
| Hauptplan | §9 Iter-Log | Add | S-8-Eintrag (diesen Report referenziert) |

**Plan-Edits gesamt:** ~11 Operationen in 2 Dateien (Spec 3: 10, Hauptplan: 1).

Spec 1 + Spec 2 unverändert — S-8-Findings sind strikt Spec-3-internal (Wrapper-Idempotenz + Doku-Konsistenz + T7-FSM-Vollständigkeit). Spec 1 §15-ViewModeModule-Lücke (F-5) bewusst nicht in Spec 1 nachgezogen, weil das eine Spec-1-Architektur-Entscheidung umkehren würde (Spec 1 = Beispiele; Spec 3 = ViewModeModule-Implementation kanonisch via Issue 3.1.1/3.1.2 Option A).

---

## Cross-Cutting Concerns (Status nach S-8-Pass)

- **F-8 Single-Dispatch-Ownership:** ✅ Verifiziert in Spec 3 §13.1 SSOT-Konformitäts-Tabelle (alle Mutationen über `onAction` → `DictateOrchestrator.dispatch` → Modul-Reducer).
- **Pure-Reducer-Invariante (F1+F2):** ✅ Verifiziert in §4.8 OverlayModule-Reducer (keine Hardware/IO-Reads im Reducer; Pre-Dispatch-Allocation in `resolveOverlayRecordAction` per S-7-Pattern).
- **MAX_CASCADE_DEPTH = 8 (R.6):** ✅ Aus Spec 1 §4.3 — schützt vor Endlos-Cascade nach KG-RSB-2-Fix. Spec 3 hat keine kritischen Cascade-Tiefen-Loops (HOVER-Permission-Loss → SetViewMode-Cascade triggert nur 1 weitere Iteration).
- **Cross-Module-Effect-Modi 1+2:** ✅ §6.1 + §7.3 T1–T7 alle in Mode-2-Form (cascade via `onCrossModuleStateChange`).
- **Coupling-Matrix (Spec 1 §15.1.x):** ✅ Spec-3-relevante Zeilen verifiziert: `Overlay × ViewMode = R(state.overlay.userPrefersWidget / hasPermission) C(ViewModeAction.SetViewMode)`; `Pipeline × ViewMode = R(state.pipeline) C(ViewModeAction.OnPipelineDone)` (T7-Pfad).

---

## Top-1-Insight

**Lifecycle-Idempotenz im Wrapper unvollständig (Findings 1+2).** Der `detach()`-Catch war da, aber `update()` und `attach()` hatten denselben Race-Pfad mit `IllegalArgumentException`/`BadTokenException` — bei `detach()` wurde das Risiko erkannt, bei `update()` und `attach()` nicht. **Lesson:** wenn ein Wrapper für ein Resource-Lifecycle existiert, müssen ALLE Lifecycle-Methoden idempotent gegen den OS-seitigen-Detach-Race sein, nicht nur eine. Plus die Doku-Drift in §13.4 + §7.1 zeigt einen anderen Verschleißpfad: **Verifikations-Sektionen werden bei späteren Refactors leicht stale** — §13.4 war ein "Konsistenz-Beweis"-Block, der nach Issue 3.1.10 nicht synchron gezogen wurde, obwohl §4.2 (der Code-SoT) korrekt refaktoriert war. T7-Lücke (F-4) ist die FSM-Spiegelung: §7.3 zeigte 6 von 7 Übergängen, der 7. lebte nur in der Coupling-Matrix — was passiert, wenn ein neuer Übergang über eine Cross-Module-Cascade entsteht und nicht parallel in die primäre FSM-Sektion verankert wird.

---

## Befund (Quantitativ)

**7 Findings: 2 Critical, 3 Important, 2 Minor.**
**~11 Plan-Edit-Operationen in 2 Dateien (Spec 3: 10, Hauptplan: 1).**
