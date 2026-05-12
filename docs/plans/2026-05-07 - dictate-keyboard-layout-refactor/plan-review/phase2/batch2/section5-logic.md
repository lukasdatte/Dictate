# Phase 2 — Logic Review — Section 5: Cross-Cutting Konsistenz + alle §13-Verifikationen + Hauptplan + Acceptance/Tests

**Plan:** `/home/lukas/WebStorm/Docs/docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.md`
**Specs:**
- Spec 1 (Pipeline-Service): `research/1-pipeline-service/1-pipeline-service.md` — §13 (Z. 1946–2216) + §10 Acceptance (Z. 1342–1366) + §15 Modul-Inventar (Z. 2229+)
- Spec 2 (Keyboard-Layout): `research/2-keyboard-layout/2-keyboard-layout.md` — §13 (Z. 1809–2013) + §10 Acceptance (Z. 1382–1397) + §14 Test-Strategie (Z. 2015–2103)
- Spec 3 (Floating-Overlay): `research/3-floating-overlay/3-floating-overlay.md` — §13 (Z. 1536–1767) + §10 Acceptance (Z. 1181–1212) + §14 Test-Strategie (Z. 1769+)
- Hauptplan: `dictate-keyboard-layout-refactor.md` (komplett, insb. §4 Block-Plan, §6 Risiken, §9 Iter-Log, OPEN-Liste)
**Code-Cross-Reference:** `/home/lukas/WebStorm/Dictate/`
**Reviewer-Scope:** Logic & Clean-Code, mit Fokus auf
- §13-Audit-Logik (Behauptung ↔ Beweis-Cross-Check)
- Acceptance ↔ Test-Coverage (testet der Plan, was er behauptet zu reparieren?)
- Cross-Spec-Konsistenz (Konflikte zwischen Spec 1 / 2 / 3)
- Hauptplan-Block-Reihenfolge ↔ Risiken
**Output:** `plan-review/phase2/batch2/section5-logic.md`

---

## Findings

### Issue L-1: §13.3.4 ViewModeFsm-Eintrag widerspricht der eigenen §13.3-Behauptung "F-11 ist propagiert"

- **Category:** [LOGIC] / [INTEGRATION]
- **Severity:** Important
- **Location:** Spec 1 §13.3.4 (Z. 2102–2108) und §13.3.9 (Z. 2134–2136); §13.3 Header-Box (Z. 2068–2072).
- **Description:** §13.3 macht eine starke Behauptung: *"Mit dem Modular-Orchestrator-Pattern (F-11) ist die zentrale Klasse jetzt der `DictateOrchestrator`, der nur das `DictateModule`-Interface kennt; Action-Logik wandert in 13 Module (siehe §15). Audit ist entsprechend pro Schicht strukturiert."* Dann listet §13.3.4 aber `ViewModeFsm` als eigene Audit-Sektion und schreibt im Body: *"Diese Klasse existiert nicht mehr eigenständig. Ihre Logik ist Teil des `ViewModeModule` (§15.1)..."*. **Identische Mechanik in §13.3.9 LocalBinder** — eine eigene Audit-Sektion, Body sagt "siehe §13.3.2b". Beide Einträge sind also **leere Audit-Sektionen**, die nur per Cross-Reference auf andere Sektionen zeigen. Das ist nicht falsch, aber der Audit-Track für `ViewModeModule` (das die FSM-Logik jetzt **trägt**) hat keinen separaten SOLID-Eintrag — es taucht nur generisch in §13.3.13 *"Modul-Implementierungen (am Beispiel RecordingModule)"* auf.
- **Example scenario:** Plan-Reviewer fragt "Hat das ViewModeModule eine SOLID-Begründung?" Sucht in §13.3 — findet §13.3.4 mit Hinweis "siehe §15". Sucht in §15.1 — findet eine Tabellenzeile "ViewModeModule, viewMode (enum), F4-Subset (ehemals ViewModeFsm), ja". Keine SRP-/OCP-/LSP-/ISP-/DIP-Begründung pro Achse. §13.3 behauptet, alle neuen Klassen audited zu haben — das stimmt für 11 von 13 Modulen nicht (nur RecordingModule wird beispielhaft auditiert, die übrigen 12 Module bekommen **kollektiv** ein "siehe §13.3.13"-Etikett).
- **Suggestion:**
  1. **§13.3.4 + §13.3.9 als „Verschoben"-Marker explizit kennzeichnen** (z.B. überschreiben mit "siehe §15.1 / §13.3.2b — Audit-Sektion an die kanonische Stelle verschoben"). Aktuell wirken die Einträge wie offene Audit-Lücken, weil sie als eigene `####` headings existieren.
  2. **§15.1 oder neue §13.3.16 ergänzen:** kurze SOLID-Begründungs-Tabelle pro 12 Module (eine Zeile pro Modul, nicht je 5 Zeilen pro Modul wie bei RecordingModule). Andernfalls ist die Behauptung in §13 ("alle neuen Klassen verifiziert") falsch.
  3. Die Iteration-Box (Z. 2068–2072) so umformulieren, dass klar ist: *"§13.3 audiert die Schicht-Klassen (Service, Orchestrator, Helper); §15 ist die kanonische Audit-Stelle für Modul-Klassen."* Das eliminiert den scheinbaren Widerspruch zwischen Behauptung und Body.

---

### Issue L-2: §13.1 Spec-1-Behauptung "alle 14 state-driven Visibility-Sites wandern" hat 2 falsche Klassifikationen

- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** Spec 1 §13.1 (Z. 1950–1976), Zeilen 7 + 18 + 19 der Tabelle; Spec 2 §13.1 (Z. 1813–1847), Zeile 11 + 14.
- **Description:** Die Audit-Tabelle in Spec 1 §13.1 klassifiziert jede Visibility-Mutation als entweder "WANDERT IN PREDICATE" oder "BLEIBT". Zwei Inkonsistenzen mit Spec 2 §13.1, die dieselbe Code-Stelle abdecken:
  - **Spec 1 §13.1 Zeile 7** sagt für `KeyboardStateManager.kt:162 (overlayCharactersLl)`: *"WANDERT IN PREDICATE (LayoutCatalog `OVERLAY_CHARS`-Slot, ungated) — `applyVisibility` löscht der Spec-2-Refactor; Default-GONE bleibt im LayoutCatalog."* — sagt also: **WANDERT**.
  - **Spec 2 §13.1 Zeile 11** für **dieselbe** `KeyboardStateManager.kt:162`-Site: *"`overlayCharactersLl` (Reset) BLEIBT (defensive Reset des transient overlays)"* — sagt also: **BLEIBT**.
  - **Spec 1 §13.1 Zeile 19** sagt für `EnterOverlayHandler.kt:56,62`: *"WANDERT IN PREDICATE — die Long-Press-Overlay-Anzeige für Enter-Key gehört in den `OVERLAY_CHARS`-Slot."* — sagt: **WANDERT**.
  - **Spec 2 §13.1 Zeile 14** für **dieselbe** Code-Stelle: *"BLEIBT (Touch-Handler-internal — siehe §11.7)"* — sagt: **BLEIBT**.

  Beide Specs widersprechen sich an zwei Sites — direkter logischer Konflikt im Audit-Beweis. Sec3-Logic L-11 hat den Touch-Handler-Reset-Aspekt schon adressiert ("Defensive depth ist direkt-widersprechender Wert zu SSOT"), aber die **Konflikt-Klassifikation zwischen den Specs** ist neu: Spec 1 behauptet Eliminierung, Spec 2 behauptet Bewahrung.

  Konsequenz: Block-Implementer kann nicht eindeutig entscheiden, ob `EnterOverlayHandler.kt:56,62` und `KeyboardStateManager.kt:162` gelöscht werden sollen oder bleiben. §13.1 in Spec 1 zählt die beiden Sites als "adressiert"; Spec 2 zählt sie als "bewusst erhalten". Logisch können sie nicht beides sein.
- **Example scenario:** Block-5-Implementer (Spec 2) liest Spec 2 §13.1 Zeile 11+14 — lässt EnterOverlayHandler-Mutationen unverändert. Block-1-Validator (Spec 1) prüft via §13.1 Zeile 7+19, ob die Mutationen entfernt sind — findet sie noch da, markiert als Plan-Deviation. Block-1 wird als incomplete eingestuft, obwohl Spec 2 (das die Mutation tatsächlich besitzt) es so wollte.
- **Suggestion:**
  1. **Spec 1 §13.1 Zeilen 7 + 19 müssen mit Spec 2 §13.1 Zeilen 11 + 14 abgeglichen werden.** Empfehlung: Die "BLEIBT"-Klassifikation aus Spec 2 ist korrekt (der EnterOverlayHandler ist eine Touch-Handler-interne State-Maschine, keine echte SSOT-Verletzung); Spec 1 §13.1 muss die Klassifikation korrigieren von "WANDERT" auf "BLEIBT" und in der Verifikations-Zusammenfassung (Z. 1976) von "alle 14 state-driven" auf "12 state-driven + 2 view-handler-internal".
  2. **Decision-Owner explizit benennen:** Wenn Spec 2 die kanonische Quelle für IME-View-Visibility ist, soll Spec 1 §13.1 ein Note-Block ergänzen *"Visibility-Mutationen mit IME-View-Scope sind kanonisch in Spec 2 §13.1 audited; Tabelle hier ist der Cross-Spec-Index."*
  3. Damit ist die Single-Source-of-Truth-Regel (Hauptplan §1.3, Spec-1-Plus-Spec-2-Pflicht) für die Audit-Tabelle selbst eingehalten.

---

### Issue L-3: §13.4 DRY-Verifikation in Spec 2 deklariert "Beweis: keine Duplikation" — aber Sec3-Logic L-5 fand identische `predTrashVisible == predPauseVisible` ohne Doku-Begründung

- **Category:** [LOGIC] / [CLEAN]
- **Severity:** Important
- **Location:** Spec 2 §13.4 (Z. 1916–1948), insbesondere Tabelle "Two-Row und Single-Row: gemeinsame Predicate-Definition" und Beweis-Block (Z. 1931).
- **Description:** §13.4 sagt: *"Identische Visibility-Logik wird über drei top-level Funktionen (`predResendVisible`, `predTrashVisible`, `predPauseVisible`) **einmal** definiert und an **allen Stellen** referenziert. Diese drei Funktionen haben jeweils EINE Definition (in §8.5). Keine Duplikation."* Das ist die DRY-Behauptung. **Aber:** Sec3-Logic L-5 (`section3-logic.md` Z. 117–134) hat empirisch festgestellt, dass `predTrashVisible` und `predPauseVisible` **wörtlich identisch** sind — `(state) -> recording.isActiveOrPaused || pipeline is ReprocessStaging` in beiden Definitionen. Das ist **Duplikation auf Funktions-Ebene** (zwei Funktionen mit identischer Body), nicht "EINE Definition". Die §13.4-Behauptung "keine Duplikation" ist also entweder falsch oder unscharf:
  - Wenn "Duplikation" = "Logik in zwei LayoutMode-Definitionen" (was §13.4-Tabelle prüft), dann stimmt die Behauptung.
  - Wenn "Duplikation" = "zwei Funktionen mit gleichem Verhalten ohne Doku-Begründung" (klassische DRY), dann **ist** das Duplikation, und §13.4 hat die Site übersehen.

  Die `audioFocusButton`-Variante in der Edit-Bar wurde via F-4 (Spec 2 §13.4 "AudioFocus-Icon-Resolver") explizit konsolidiert, weil identische Logik an zwei Sites lebte. Genau dasselbe Muster (zwei identische Predicates) ist hier **nicht konsolidiert** — entweder weil §13.4 das nicht als Duplikation betrachtet, oder weil ein Verfasser-Snapshot-Bug.
- **Example scenario:** Plan-Reviewer fragt "Was sagt §13.4 zur Duplikation auf Predicate-Ebene?" — findet die F-4-Sektion (audio-focus-icon konsolidiert) und folgert "DRY ist auf Funktions-Ebene streng angewandt". Im Code findet er dann `predTrashVisible` ≡ `predPauseVisible` und ist verwirrt — weil §13.4 die identischen Definitionen explizit als "EINE Definition" gegenargumentiert. Logischer Bruch: die DRY-Behauptung in §13.4 deckt die F-4-Site, vermeidet aber die F-5-Site (predTrash/Pause).
- **Suggestion:**
  1. **§13.4 erweitern:** ein dritter Eintrag *"Identische Predicates für getrennte Slots (predTrashVisible / predPauseVisible)"* mit konkreter Begründung — entweder (a) konsolidiert in `predRecordingControlsVisible`, oder (b) explizit doku-mentiert: "Heute identisch, könnte sich aber in Iter-N unterscheiden, siehe §8.7 Send-Mode-Note. Ändere nicht ohne explizite Plan-Iter."
  2. **Den DRY-Beweis kalibrieren:** §13.4 sollte explizit unterscheiden zwischen *Layout-Position-DRY* (Tabelle Two-Row-vs-Single-Row) und *Predicate-Body-DRY* (identische Lambda-Bodies). Aktuell vermischt §13.4 beides und behauptet pauschal "keine Duplikation".

---

### Issue L-4: Acceptance §10 Spec 2 + §10 Hauptplan-Bug-Eliminations-Ziele — Resend-Toggle-Bug fehlt in jeder Acceptance-Liste

- **Category:** [LOGIC]
- **Severity:** Critical
- **Location:** Hauptplan §2.3 (Z. 65–70, "Bug-Eliminations-Ziele"), Spec 2 §10 (Z. 1382–1397), Spec 3 §10 (Z. 1181–1212), Spec 1 §10 (Z. 1342–1366); kollidiert mit Sec3-Logic L-2 + L-14.
- **Description:** Hauptplan §1.1 listet **drei User-Bugs** als Auslöser des Refactors:
  1. *Asymmetrisches Re-Parenting beim Single-Row-Toggle* (trash/pause)
  2. *Asymmetrisches Re-Parenting beim Revert* (record_pulse/backspace/resend)
  3. *Send-Modus + Single-Row: Send-Button verdeckt, **resend-Button verschwindet beim Toggle***

  §2.3 macht daraus 5 Bug-Eliminations-Ziele, eines davon: *"Eliminierung der `resend_btn`-Race (5 Mutatoren → 1 Predicate)"*. Spec 2 §10 hat **7 Acceptance-Punkte** für Block 5. Davon adressiert genau einer den Send-Mode-Bug (Punkt 3: *"Send-Mode + Single-Row: Send-Button vollständig sichtbar, kein Verdecken (Bug-Eliminierung)"*) — **der Resend-Toggle-Bug taucht in keiner Acceptance-Bullet auf** (weder in Block 1, Block 4, Block 5, Block 6 noch im Hauptplan). Sec3-Logic L-2 + L-14 hatten den Bug strukturell analysiert; das hier ist die übergeordnete Bestätigung: **kein einziger Acceptance-Punkt prüft, ob "resend_btn verschwindet beim Toggle" tatsächlich gefixt ist.**

  Konsequenz: Block 1 + Block 5 können beide als „done" abgenommen werden, ohne dass jemand den ursprünglichen Bug verifiziert. Spec 2 §14.2 listet 7 UI-Tests, davon prüft UI-Test 1 *"Toggle Single-Row im Idle. Verify alle 8 Buttons sichtbar"* — das ist **statisch** (Snapshot nach Toggle), nicht **transient** (Frame-by-Frame während Toggle). Der ursprüngliche Bug war ja, dass der Button **kurz** verschwindet — das fängt der statische Test nicht.
- **Example scenario:** Block 1 implementiert `predResendVisible` zentral, Block 5 implementiert MotionLayout. Beide werden gegen ihre §10-Acceptance abgenommen. Manueller User-Test in Production: User ist im Idle-Mode mit `lastAudio=true`, klickt Resend (→ 500ms-Cooldown via `resendCooldown`-State, siehe Spec 2 §13.5 Gap 2), während des Cooldowns klickt User Single-Row-Toggle. Die `resendCooldown`-Achse + die `singleRowMode`-Achse mutieren in derselben Reducer-Cascade (siehe Phase-1 1.1.7 Cross-Module-Cascade-Loop) — falls die Reducer-Reihenfolge zuerst `resendCooldown=true` ergibt, sieht User den Resend-Btn 500ms lang disabled. Nach Cooldown-Ablauf läuft kein neuer Render, weil keine State-Achse sich ändert. **Bug ist subtle reaktiviert** (jetzt enabled-flicker statt visibility-flicker). §10 hätte das gefangen — fehlt aber.
- **Suggestion:**
  1. **Hauptplan §2.3 erweitern um Acceptance-Pflicht:** *"Jedes der 5 Bug-Eliminations-Ziele MUSS in genau einem Acceptance-Punkt eines Blocks stehen, mit eindeutigem Test-Reference-Marker (`§14.X`)."*
  2. **Spec 2 §10 ergänzen** (in Block-5-Acceptance):
     - *"Resend-Btn ist während Toggle Two-Row ↔ Single-Row in Idle+lastAudio durchgängig sichtbar (visibility=VISIBLE in jedem Frame). Verifiziert via Espresso `IdlingResource` oder Frame-Capture (siehe Test §14.2 UI-Test 8 — neu)."*
     - *"Resend-Btn-Cooldown (500ms nach Click) lässt visibility=VISIBLE, nur enabled=false + alpha=0.4. Siehe Test §14.2 UI-Test 9 — neu)."*
  3. **Spec 2 §14.2 zwei neue UI-Tests:** UI-Test 8 (Frame-Capture) und UI-Test 9 (Cooldown ist enabled-only). Sec3-Logic L-2 hat den Test-Pfad konkret beschrieben.
  4. **Spec 1 §10 (Block 1)** ergänzen: *"`predResendVisible` reflektiert nicht `resendCooldown` — Cooldown betrifft NUR `enabledResolver`, nicht `visibilityPredicate`. Verifiziert in Block-1-Unit-Test."*

---

### Issue L-5: Block-Reihenfolge-Garantie "Block 1 muss vor allem anderen kommen" ist mit F-11-Block-1-Inhalt nicht erfüllbar — Risiko-§6 erfasst das Risiko nicht

- **Category:** [LOGIC] / [INTEGRATION]
- **Severity:** Critical
- **Location:** Hauptplan §4 (Z. 199–209), §6 Risiken (Z. 226–238); Spec 1 §10 Block-1-Acceptance (Z. 1353–1358); Phase-1 1.1.6 (Issue-Track) — verstärkt durch Sec1-Logic + Sec1-Structure (Phase-2 Batch 1).
- **Description:** Hauptplan §4 sagt: *"Reihenfolge: 1 → 2 → 3 → 4 → 5 → 6. Block 1 (State-SSOT) **muss** vor allem anderen kommen, sonst werden neue Bug-Klassen auf einer noch-fragilen State-Quelle aufgebaut."* Phase-1 1.1.6 hat den fundamentalen Konflikt geflaggt:
  - Block-1-Beschreibung (Z. 201) steht **vor F-11**: *"resend_btn-Visibility zentralisieren; recordButton.text/isEnabled-Hybrid auflösen; Quick-Win-Fixes (KSM.refresh in Toggle-Callbacks); Komplexität: klein-mittel"*.
  - Mit F-11 (siehe §9 Iter-Log Z. 406+) ist Block-1-Inhalt: 13 Module, hierarchischer DictateUiState, Action-Sealed-Hierarchie, kotlinx.collections.immutable, Cross-Module-Observer, ModuleServices-DI — *"Komplexität: groß"*.
  - Block-2 (DictatePipelineService) ist der Container, in dem Orchestrator + 13 Module leben. **Block 1 (Module-Aufbau) hängt also von Block 2 (Service-Skelett) ab.** Die "vor-allem-Garantie" ist gebrochen.

  §6 Risiken-Tabelle hat 7 Einträge — **keiner** adressiert die Block-Reihenfolge-Race. Der einzige indirekte Eintrag *"State-Konsolidierung bricht bestehende Use-Cases"* mit Mitigation *"Block 1 als isolierter Refactor mit vollständigem Manual-Test-Pass vor Block 2 (Spec 1 §10)"* widerspricht der F-11-Realität: Block 1 **kann** nicht isoliert vor Block 2 laufen, wenn Block 1 den Service-Container braucht.

  Spec 2 §13.5 Gap 5 (Z. 2007–2011) deutet die Lösung an: *"Block 1 implementiert die `predResendVisible`-Konsolidierung **bereits** vor dem Refactor — das eliminiert die 6-Mutator-Race **innerhalb des heutigen Codes**, ohne MotionLayout."* Das wäre ein **Block 1a** (Quick-Wins im heutigen Code) und ein **Block 1b** (Module nach Block 2). Der Hauptplan **dokumentiert diesen Split aber nicht**, und §6 Risiken erfasst die ohne-Split-Race nicht. Phase-1 1.1.6 hat das als Empfehlung Option A formuliert (User-Decision: ⬜ PENDING).

  **Logische Konsequenz:** wenn ein Implementer den Plan wörtlich nimmt (Block 1 → Block 2 → Block 3 → ...), läuft er gegen einen Wall: Block 1 lässt sich nicht in einer Session abschließen, weil der Modul-Container fehlt. Wenn er rückwärts abweicht (Block 2 zuerst), bricht er die "vor-allem-Garantie" und potenziell die Quick-Win-Fixes.
- **Example scenario:** Implementer öffnet Plan, sieht `implement-long-plan-v2`-Skill in Aktion. Plant Phase 1 = Block 1. Liest Spec 1 §10 Block-1-Acceptance: *"resend_btn-Visibility wird nur an EINER Stelle berechnet (Predicate im PipelineStateManager)."* Aber `PipelineStateManager` existiert nicht mehr — es ist `DictateOrchestrator`, der im noch-nicht-existenten DictatePipelineService lebt. Implementer ist blockiert. Eskaliert an User. User-Frage: "Was war zuerst gedacht — Quick-Wins im alten Code oder Module-System?" Hauptplan beantwortet das nicht.
- **Suggestion:**
  1. **Hauptplan §4 erweitern um Block-1-Split** (Phase-1 1.1.6 Option A finalisieren):
     - **Block 1a:** Quick-Win-Konsolidierung im heutigen Code (predResendVisible als Helper, recordButton-Hybrid auflösen, KSM.refresh-Callbacks). Komplexität: klein-mittel. Reihenfolge: vor Block 2.
     - **Block 1b:** State-Architektur (DictateUiState hierarchisch, DictateOrchestrator, 13 Module, Action-Sealed). Komplexität: groß. Reihenfolge: nach Block 2.
     - Endgültige Reihenfolge: 1a → 2 → 1b → 3 → 4 → 5 → 6.
  2. **Hauptplan §6 Risiken-Tabelle erweitern:** neuer Eintrag *"Block-1-Quick-Wins ↔ Block-1b-Module-Aufbau überlappen, wenn nicht gesplittet → Race-Condition zwischen alten und neuen Mutatoren während des Refactor-Pfads."* Mitigation: *"Hauptplan §4 splittet Block 1 explizit in 1a + 1b; 1a ist isoliert testbar im alten Code, 1b ersetzt 1a's Helper durch Modul-Reducer."*
  3. **Spec 1 §10 Block-1-Acceptance entsprechend splitten** (Block 1a hat Quick-Win-Akzeptanz; Block 1b hat Modul-Akzeptanz). Aktuell sind beide vermischt.
  4. Iter-Log §9.4 (oder neuer §9.5-Eintrag) referenzieren, wo der Split begründet wird (via Phase-1 1.1.6 + Spec 2 §13.5 Gap 5).

---

### Issue L-6: Spec 3 §13.4 Cross-Spec-Konsistenz behauptet "selbe Action-Typen" — Sec3-Logic L-2 + Phase-1 1.1.5 zeigen direkten `_state.value.copy()`-Bypass in Spec 3

- **Category:** [LOGIC] / [INTEGRATION]
- **Severity:** Critical
- **Location:** Spec 3 §13.4 "Werden dieselben `Action`-Typen genutzt?" (Z. 1750–1752); Spec 3 §5.3 (Z. 846–867), §6.1 (Z. 916–945), §7.1+§7.3 (Z. 968–1102) — siehe Phase-1 1.1.2.
- **Description:** Spec 3 §13.4 sagt: *"Antwort: JA. Spec 2 §3.3 definiert eine sealed `Action`. Beide Backends invokieren `onAction(Action)` — der Empfänger (`PipelineStateManager`) hat ONE Switch-Case-Block für alle Action-Typen."* Das ist die Konsistenz-Behauptung. Aber:
  - Phase-1 1.1.5 (validated-findings-phase1.md Issue 1.1.2 Critical) hat empirisch festgestellt: *"Spec 3 §5.3 (Z. 846, 854, 862, 867), §6.1 (Z. 916, 923, 945), §7.1 (Z. 968), §7.3 (Z. 1025-1102) zeigen Snippets, die **direkt** `_state.value = _state.value.copy(viewMode = …, smallMode = …, userPrefersWidget = …)` mutieren."* Das ist exakt der **Bypass** des `dispatch(action)`-Vertrags, den §13.4 als bewiesenen Fakt annimmt.
  - Konsequenz: §13.4-Behauptung ist **falsch** für Spec 3 — die Cross-Spec-Konsistenz existiert nur in Spec 2 ↔ Spec 1 (über `Action`-Typen), aber Spec 3 selbst macht in seinen Body-Sektionen direkte State-Mutationen, die NICHT durch `Action` laufen. Phase-1 1.1.2 hat das geflaggt; User-Decision ist ⬜ PENDING.
  - §13.4 hat damit **eine in sich widersprüchliche Audit-Aussage**: Sektion behauptet "JA, dieselben Actions"; selbe Spec hat in §5/§6/§7 Beispiele, die das Gegenteil tun. Ein Plan-Reviewer, der nur §13.4 liest, gewinnt ein falsches Bild der Cross-Spec-Konsistenz.
- **Example scenario:** Spec-3-Implementer (Block 6) startet Implementierung. Liest §13.4 — folgert "ich nutze nur dispatch(action)". Liest dann §7.3 T6-Pfad: *"`closeOverlay() { _state.value = _state.value.copy(viewMode = ViewMode.KEYBOARD) }`"* — und folgt der Code-Skizze. Bypassiert Cross-Module-Observer; LayoutModule's Smallmode-Auto-Activation feuert nicht. User-Bug: Schließen in WIDGET → IME-View kommt zurück, aber **ohne SmallMode** (wider OPEN-1). Implementer fragt im Review: "Aber §13.4 sagt doch...". Plan war intern inkonsistent.
- **Suggestion:**
  1. **Spec 3 §13.4 erweitern um expliziten Konsistenz-Status:** *"Audit-Status (Mai 2026): die §5/§6/§7-Snippets zeigen direkte `_state.value.copy()`-Mutationen als Skizze; die finalen Implementierungen MÜSSEN durch `dispatch(Action.*)` ersetzt werden. Phase-1 1.1.2 hat die Migration gegen die F-8-Architektur dokumentiert. Bis zur Auflösung von 1.1.2 ist die `dispatch`-Konsistenz **gewollt** aber **nicht im Body durchgezogen** — siehe Cross-Spec-Konflikt Section-3-Logic L-2."*
  2. **Section-3-Logic L-2 explizit in §13.4 verlinken** als evidence cross-reference. Damit ist die Audit-Sektion mit ihrem eigenen Body konsistent (zumindest hinsichtlich des bekannten Mismatches).
  3. **Apply-Step (sobald 1.1.2 entschieden)**: Spec 3 §5/§6/§7 Snippets neu schreiben mit `dispatch(Action.OverlayAction.MarkOverlayOnboardingShown)` etc., DANN §13.4 als "fully verified" markieren.

---

### Issue L-7: Spec 1 §13.5 Gap G6 (MediaRecorder-Leak) wird als „Akzeptiert" markiert, aber Acceptance §10 testet das nicht

- **Category:** [LOGIC] / [ROBUSTNESS]
- **Severity:** Important
- **Location:** Spec 1 §13.5 G6 (Z. 2214); Spec 1 §10 Block-2-Acceptance (Z. 1342–1351); Hauptplan §9 Iter-Log Z. 292.
- **Description:** §13.5 G6: *"Service-Death während aktivem Recording: `RecordingManager.stop()` wird nicht mehr gerufen → MediaRecorder bleibt im Native-Heap. Mittel. `Service.onDestroy` ruft `stateManager.cancelPipeline()` → triggert `recordingManager.release()`. Bei Process-Kill greift Android's Cleanup. Akzeptiert."* Hauptplan-§9 Iter-Log: *"Spec 1 G6 (MediaRecorder-Leak bei Process-Death) — Android-Cleanup greift, dokumentiert."*

  **Logischer Bruch:** Die Mitigation hat zwei verschiedene Pfade:
  - Pfad A (Service.onDestroy normal): `cancelPipeline() → release()`. Das ist **Code, den der Plan implementiert**. Testbar.
  - Pfad B (Process-Kill ohne `onDestroy`): "Android-Cleanup". Nicht-testbar ohne Process-Kill-Test.

  §10 Block-2-Acceptance hat Punkt 5: *"Force-Stop der App: beim nächsten Tastatur-Open wird Restart-Button mit pending-Session gezeigt."* — testet nur die DB-Recovery, NICHT den MediaRecorder-Leak.

  **Was fehlt:** ein Acceptance-Punkt + Test für *"Service.onDestroy mit aktivem Recording: `recordingManager.release()` wurde aufgerufen UND der MediaRecorder ist tatsächlich freigegeben (verifiable via `MediaRecorder.release()` Mock-Spy)."* Aktuell ist der Pfad-A-Code ungetestbar und unbestätigt — nur Pfad B (das nicht-deterministisch ist) wird "akzeptiert".

  Konsequenz: ein Bug, der das `release()` im `cancelPipeline()`-Pfad bricht (z.B. eine Coroutine-Race, die `cancelPipeline` vorzeitig terminiert), würde **silent leak** — bis ein User den Phase-1-Bug "Mikrofon bleibt belegt nach Tastatur-Wechsel" erlebt.
- **Example scenario:** Sec1-Logic findet einen subtilen Coroutine-Cancel-Race im `cancelPipeline()`-Pfad. Block-2-Implementer fixt es nach §10-Acceptance (Force-Stop-Recovery), MediaRecorder-Pfad bleibt ungetestet. Production-User wechselt Tastatur, Recording stoppt nicht, IME-Service stirbt, MediaRecorder bleibt aktiv (Mikrofon-LED an). User wechselt zu anderer App, sieht Mikrofon-LED weiterhin → reportiert „App belauscht mich nach Tastatur-Wechsel". Bug-Wirkung: Privacy-Issue, schwer zu reproduzieren, nicht in §10 als Acceptance erfasst.
- **Suggestion:**
  1. **Spec 1 §10 Block-2-Acceptance ergänzen:** *"Service.onDestroy bei aktivem Recording: `recordingManager.release()` wird aufgerufen UND der MediaRecorder ist im released-State. Verifiziert via `MediaRecorder.release()`-Mock-Spy in Unit-Test (oder Robolectric)."*
  2. **§13.5 G6 erweitern:** Mitigation-Spalte sollte zwei Pfade explizit nennen:
     - Pfad A (testbar): `Service.onDestroy → cancelPipeline → release` mit Acceptance-Test in §10.
     - Pfad B (nicht-testbar, Process-Kill): "akzeptiert, Android-Cleanup".
  3. **Spec 1 §14 (falls vorhanden) oder neuer Block-2-Test-Eintrag:** Unit-Test, der `Service.onDestroy()` mit aktivem Recording aufruft und prüft, dass `recordingManager.release()` exakt einmal aufgerufen wurde.

---

### Issue L-8: Hauptplan §3.3 LogicalButtonId-Liste hat 15 Einträge laut Z. 165, Spec 2 §3.1 listet 14 — Phase-1 1.0.2 hat das gefixt, aber §13.1-Verifikations-Tabelle hat das nicht nachgepflegt

- **Category:** [LOGIC] / [INTEGRATION]
- **Severity:** Important
- **Location:** Hauptplan §3.3 (Z. 165 — nach Phase-1 1.0.2-Apply); Spec 2 §3.1 (kanonische Quelle); Spec 2 §13.1 + §13.2; Phase-1 1.0.2 (validated-findings-phase1.md ✅ APPLIED).
- **Description:** Phase-1 1.0.2 ist **APPLIED**: Hauptplan §3.3 LogicalButtonId-Liste wurde auf die Spec-2-§3.1-Liste angeglichen. Liste enthält jetzt: `RECORD, RESEND, BACKSPACE, AUDIO_FOCUS, WIDGET_TOGGLE, TRASH, SPACE, PAUSE, ENTER, OVERLAY_RECORD, OVERLAY_SEND, OVERLAY_PAUSE, OVERLAY_TRASH, OVERLAY_CLOSE`. Spec 3 §13.5 GAP-4 sagt aber: *"`LogicalButtonId.WIDGET_TOGGLE` (§7.3 T1) ist in Spec 2 §3.1 noch nicht aufgelistet — der Toggle-Button im IME-View muss als Slot ergänzt werden."* Das war pre-Phase-1; jetzt ist es in der Liste. Aber:
  - **Spec 2 §13.1 Visibility-Audit-Tabelle** (Z. 1817–1846) erwähnt `WIDGET_TOGGLE` nicht. Wenn `WIDGET_TOGGLE` in den KEYBOARD_TWO_ROW + KEYBOARD_SINGLE_ROW LayoutModes erscheint, muss er einen state-getriebenen Visibility-Predicate haben (z.B. `{ state.viewMode == ViewMode.KEYBOARD }`). §13.1 Tabelle muss eine Zeile dafür haben — fehlt.
  - **Spec 2 §13.2 Click-Listener-Audit-Tabelle** (Z. 1853–1875) erwähnt `WIDGET_TOGGLE` ebenfalls nicht — keine Migration-Quelle aus dem heutigen Code (logisch, weil der Button neu ist), aber kein Eintrag wie *"WIDGET_TOGGLE NEU — keine heutige Migration; Slot in §3.3 actionResolver = ToggleViewModeWidget"*.
  - Sec3-Logic L-9 (`section3-logic.md` Z. 207–220) hat Slot-View-Lookup-Failure dokumentiert: *"Wenn ein neuer LogicalButtonId zum Catalog hinzugefügt wird, aber jemand vergisst, ihn der `buttonViews`-Map hinzuzufügen, gibt das ein Silent-Skip — der Slot wird nicht gerendert, kein Crash."* Das ist genau der Failure-Modus für `WIDGET_TOGGLE`, wenn §13.1 + §13.2 ihn vergessen.

  Konsequenz: der Phase-1-Apply hat den Hauptplan-Mismatch korrigiert, aber die §13-Audit-Tabellen in Spec 2 wurden nicht synchron mit nachgepflegt. Es gibt kein Audit-Beweis, dass `WIDGET_TOGGLE` im Block-5-Implementer-Pfad angekommen ist.
- **Example scenario:** Block 5 (ImeViewBackend) wird gegen §13.1-Tabelle abgeklopft: *"Sind alle 27 in `_pending-state-machine-visibility-owners.md` §1 gelisteten Mutationen adressiert?"* — JA. Aber `WIDGET_TOGGLE` taucht weder in §1 (das war pre-OPEN-2) noch in §13.1 auf — Implementer übersieht den Slot. Block 5 wird abgenommen. Block 6 (Spec 3) merkt: WIDGET_TOGGLE-Click-Action existiert im LayoutCatalog, aber das ImeViewBackend hat keine `buttonViews[WIDGET_TOGGLE]`-Mapping → Silent-Skip (Sec3-L-9). Bug erst, wenn User aufs widget_toggle_btn klickt — nichts passiert.
- **Suggestion:**
  1. **Spec 2 §13.1 ergänzen:** neue Zeile *"WIDGET_TOGGLE | NEU (Spec 3 OPEN-2) | LayoutCatalog `WIDGET_TOGGLE`-Slot, Predicate `{ state.viewMode == ViewMode.KEYBOARD }`, Layout-Position OPEN siehe §3.1."*
  2. **Spec 2 §13.2 ergänzen:** *"WIDGET_TOGGLE | NEU — kein heutiger Migration-Source. Slot in `wireStaticHandlers` actionResolver = `Action.ViewModeAction.ToggleViewModeWidget`."*
  3. **Spec 2 §6 buttonViews-Map (Z. 382–391)** explizit um `WIDGET_TOGGLE -> R.id.widget_toggle_btn` erweitern. Aktuell ist die Map-Definition nicht state-of-the-art mit der LogicalButtonId-Liste.
  4. **Sec3-Logic L-9 Empfehlung umsetzen:** `?: return@forEach` durch `?: error("No view registered for ${slot.logicalId}")` ersetzen — ein silent miss würde dann zur Build-Time aufschlagen.

---

### Issue L-9: §15.3-§15.5 Cross-Module-Cascade ist im Modul-Inventar nur tabellarisch markiert, aber keine ausführbaren Test-Cases (Acceptance fehlt)

- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** Spec 1 §15.1 (Z. 2240–2255 — Tabelle "Cross-Module-Observer? ja/nein"); Spec 1 §15.5 (laut Phase-1 Issue 1.1.3 — Mode 1+2+3); Spec 1 §10 Block-1-Acceptance (Z. 1353–1358); Phase-1 1.1.7 (Cascade-Loop-Risiko).
- **Description:** §15.1 listet 7 Module mit "Cross-Module-Observer: ja" (PipelineModule, AudioModule, ViewModeModule, ResendModule, LivePromptModule, LanguageModule, InterruptionModule). Konkrete Cascades:
  - PipelineDone → ResendMarkLastAudio + LivePromptChainNext
  - AudioFocus-Loss → Recording.Pause; Recording.Preparing → AudioFocus-Request
  - Recording-Active+View-hidden → HOVER (ViewModeModule)
  - Reprocess-Override → Language.Override
  - Anruf → Recording.Cancel (Phase 2)

  **§10 Block-1-Acceptance hat 5 Punkte**, alle drehen sich um `predResendVisible` und `recordButton.text/isEnabled`. **Keiner** prüft die Cross-Module-Cascade. Spec 2 §14 hat Tests für `forKeyboard(state)` (Selektor) und `predResendVisible` (Predicate), **keine** für die Module-Cascade-Mechanik selbst.

  Phase-1 1.1.7 hat das Loop-Risiko geflaggt: *"Wenn Modul A beim State-Change von B mit Action X reagiert und Modul B's Reducer auf X seinen State so mutiert, dass A's Cross-Module-Observer wieder feuert..."* User-Decision ⬜ PENDING. Aber selbst ohne Loop-Mitigation fehlt ein Acceptance-Test für die **glücklichen** Cascade-Pfade — z.B. *"Pipeline-Done feuert ResendModule.MarkLastAudio innerhalb von einem Reducer-Tick; State.resend.lastAudioExists ist im nächsten render() true."*

  Konsequenz: Block-1-Implementer kann den Modul-Container fertigstellen, alle 13 Reducer schreiben, ohne dass ein einziger Cross-Module-Cascade tatsächlich verifiziert wird. Das ist genau die Art von Logic-Bug, die das ganze Refactor laut §1.1 vermeiden soll (*"Race zwischen Layout-Application und Visibility-Berechnung"*).
- **Example scenario:** Block-1b-Implementer schreibt PipelineModule + ResendModule + LivePromptModule. Cascade-Code: `PipelineModule.onCrossModuleStateChange` schaut auf `prev.pipeline != curr.pipeline && curr.pipeline is Idle`, emittiert dann via `emitAction` ein `Action.ResendAction.MarkLastAudio`. **Aber:** Implementer vergisst den Pfad für `prev.pipeline.Running → curr.pipeline.Idle` (vergessenes branching). Folge: Pipeline-Done-Cascade feuert nur halbwegs, Resend-Btn bleibt im Vor-Pipeline-State. Manueller Test fängt es nicht, weil §10-Acceptance auf Visibility (statisch) prüft, nicht auf Cascade-Effekte (dynamisch). Production-Bug-Report: *"Resend-Btn ist nach Pipeline manchmal nicht clickbar."*
- **Suggestion:**
  1. **Spec 1 §10 Block-1-Acceptance erweitern:** ein neuer Punkt pro Cross-Module-Cascade aus §15.1-Tabelle. Beispiele:
     - *"PipelineModule.PipelineDone → ResendModule.MarkLastAudio: nach Pipeline-Done ist `state.resend.lastAudioExists = true` ohne weiteres User-Input."*
     - *"AudioModule.AudioFocusLoss → RecordingModule.Pause: bei `AudioFocusLoss` während Recording.Active wechselt `state.recording` zu `Paused`."*
     - Etc., 7 Cascades = 7 Acceptance-Punkte.
  2. **Spec 2 oder Spec 1 §14 erweitern:** Unit-Test pro Cascade-Pfad mit Mock-Modules, die den emittierten Action-Stream beobachten. Pattern:
     ```kotlin
     @Test fun `pipeline done triggers resend mark last audio cascade`() {
         val orchestrator = orchestratorWith(PipelineModule, ResendModule, ...)
         orchestrator.dispatch(Action.PipelineAction.SimulateDone)
         assertEquals(true, orchestrator.state.value.resend.lastAudioExists)
     }
     ```
  3. **Phase-1 1.1.7-Auflösung verlinken:** sobald Loop-Guard entschieden, in §15.5 die getestete Cascade-Tiefe aufnehmen (z.B. *"Tests verifizieren Cascade-Tiefe ≤ 2 für alle 7 Cascades"*).

---

### Issue L-10: Hauptplan-Erfolgskriterium (§2.2) ist subjektiv, nicht testbar — keine Acceptance-Definition für „ein Ort, automatisch korrekt"

- **Category:** [LOGIC] / [CLEAN]
- **Severity:** Nice-to-have
- **Location:** Hauptplan §2.2 (Z. 60–62) — der vom User formulierte Erfolgsmaßstab.
- **Description:** §2.2 zitiert User-Sprache: *"Eine UI-Änderung (neuer Button, neuer Modus, neuer State-Übergang) lässt sich an **einem Ort** beschreiben, und die UI reflektiert das **automatisch korrekt** — ohne dass man drei Klassen koordinieren oder auf Race Conditions testen muss."*

  Das ist die High-Level-Vision, kein testbarer Acceptance-Kriterium. Spec 1+2+3 §10 testen Bug-Eliminierung und Funktionalität, aber **keine** Spec testet die Vision-Aussage selbst. Das ist akzeptabel für High-Level-Visionen, aber wenn das Refactor "done" ist, muss jemand subjektiv beurteilen, ob der Plan die Vision erfüllt hat. Ohne kleinster Operationalisierung (z.B. *"Hinzufügen eines neuen Buttons erfordert <X> Code-Änderungen in <Y> Dateien"*) bleibt das Plan-Ergebnis nicht-falsifizierbar.

  Spec 2 §13.4 macht einen Teil-Beweis: *"Hinzufügen eines neuen Slots = 1 LayoutMode-Eintrag + 1 ButtonSlot-Definition. Keine Klasse-Hierarchie-Änderung."* Spec 1 §13.3.13 macht einen anderen Teil-Beweis: *"Neue Recording-Action = neue Variante in Action.RecordingAction + neuer when-Branch in reduce."* Aber kein zusammenfassender Test.
- **Example scenario:** Refactor ist abgeschlossen, alle 6 Blöcke abgenommen. User fragt: *"Erfüllt das Refactor mein §2.2-Ziel?"* — Plan-Reviewer kann nur subjektiv antworten. Es gibt keinen End-to-End-Test, der zeigt: *"Wir haben einen neuen Button 'Whisper-Settings' hinzugefügt, das hat exakt 3 Code-Änderungen in 1 Datei gebraucht."* Erfolgsmaßstab ist nicht meßbar.
- **Suggestion:**
  1. **Hauptplan §2.2 operationalisieren** als End-to-End-Acceptance:
     - *"Beweis-Test (post-Refactor): Hinzufügen eines neuen Buttons (z.B. `WHISPER_SETTINGS`) erfordert ≤ 3 Code-Änderungen — eine in `LogicalButtonId`, eine in dem zugehörigen LayoutMode, eine in `buttonViews`-Map. Keine Reducer-Änderung, keine Backend-Klasse-Anpassung."*
     - *"Beweis-Test: Hinzufügen einer neuen Pipeline-Action (`Action.PipelineAction.RetryFromCheckpoint`) erfordert eine when-Variante in PipelineModule.reduce + ggf. eine Effect-Variante. Keine Klasse-Hierarchie-Änderung."*
  2. Diese Tests werden im **Phase 4 Integration Check** (oder neuer Phase-5-Cleanup-Test) am End-of-Refactor exemplarisch durchgeführt — nicht als Unit-Test, sondern als Refactor-Validierungs-Skizze.

---

### Issue L-11: Iter-Log §9 (Hauptplan) reflektiert F-1 bis F-11, aber nicht die Phase-1-Issues — Audit-Track ist nicht append-only

- **Category:** [INTEGRATION] / [LOGIC]
- **Severity:** Nice-to-have
- **Location:** Hauptplan §9 (Z. 261–451 — letzte Sektion mit ~190 Zeilen Iter-Log).
- **Description:** §9 ist sauber strukturiert: Initial-Entwurf → User-Iteration → Cross-Spec-Konsolidierung → F-1..F-7 → F-8..F-11. Jeder Iter-Block hat Datum, Begründung, Effekt-Auflistung. **Aber:** Phase-1 (validated-findings-phase1.md, 2026-05-10) hat 6 🟢 Auto-Fix-Issues APPLIED + 8 🟡 PENDING. Phase-2 Batch 1 (validated-findings-batch1.md, 2026-05-10) hat 12 🟢 + 18 🟡. **Nichts davon erscheint im Iter-Log.**

  §9-Pattern ist append-only und Iteration-narrativ — das ist gut für die Architektur-Iteration, aber Iteration-Issues (insbesondere die 🟢 APPLIED-Fixes, die Plan-Inhalte wirklich modifiziert haben) sollten dokumentiert werden. Beispiel: Phase-1 1.0.5 hat ~64 Action-Refs string-rewriten, 1.0.6 hat ~28 State-Path-Refs. Das sind nicht-triviale Plan-Modifikationen, die Iter-Log-Einträge verdienen.

  Konsequenz: ein Plan-Reviewer, der das Iter-Log liest, glaubt der letzte Stand ist 2026-05-10 mit F-11. Tatsächlich sind seither ~18 weitere Apply-Fixes durchgelaufen (Phase-1 + Phase-2-Batch-1 + diese Phase-2-Batch-2-Pass). Plan-Geschichte ist nicht vollständig nachverfolgbar.
- **Example scenario:** Halbjahr nach Refactor-Abschluss möchte ein Entwickler verstehen, *warum* Spec 1 §15.5 Mode 3 als "Phase-2 / nicht eingebaut" markiert ist. Sucht im Iter-Log — findet F-11 (das Mode 3 erst eingeführt hat) aber keinen späteren Eintrag, der erklärt, warum Mode 3 zurückgenommen wurde. Findet die Begründung nur in `validated-findings-batch1.md` Issue 2.0.3 — was nicht in Iter-Log verlinkt ist. Geschichts-Audit ist gebrochen.
- **Suggestion:**
  1. **Hauptplan §9 ergänzen** um zwei neue Iter-Einträge (am Ende, nach den F-Pässen):
     - *"### 2026-05-10 — Phase-1-Apply (Auto-Fix-Issues 1.0.1 bis 1.0.6)*: Naming-Drift-Bereinigung (PipelineStateManager → DictateOrchestrator in 87 Refs; Action-Hierarchie + State-Pfade auf F-8/F-10/F-11-Stand), Detail-Quelle siehe `plan-review/validated-findings-phase1.md`."*
     - *"### 2026-05-10 — Phase-2-Batch-1-Apply (Auto-Fix-Issues 2.0.1 bis 2.0.12)*: Numerierungs-Fixes, Mode-3-Markierung, LocalBinder-Schrumpfung, Pref-Mirror-Bypass-Cleanup, Lösch-Tabelle, etc., Detail-Quelle siehe `plan-review/validated-findings-batch1.md`."*
  2. **Iter-Log-Konvention dokumentieren** (am Ende von §9 als kleiner Hinweis): *"Plan-Review-Apply-Pässe werden hier als zusammenfassende Einträge mit Pointern in die Validated-Findings-Files referenziert; Detail-Findings bleiben in `plan-review/`."*

---

### Issue L-12: §13-Verifikationen testen Architektur-Konsistenz, aber nicht Plan-Vollständigkeit — fehlende „Was ist nicht abgedeckt"-Sektion

- **Category:** [LOGIC]
- **Severity:** Nice-to-have
- **Location:** Spec 1 §13 (komplett), Spec 2 §13 (komplett), Spec 3 §13 (komplett); Hauptplan-fehlt — kein zentraler "Out-of-Scope"-Block.
- **Description:** Alle drei §13-Sektionen verifizieren *"was im Refactor passiert"*. Keine §13 dokumentiert *"was im Refactor explizit NICHT passiert"*. Beispiele aus dem heutigen Code, die unklar sind:
  - **`InfoBarController` (Spec 2 §13.1 Zeile 17, §13.2 Click-Listener Z. 241)** — explizit als "BLEIBT" markiert, aber nicht klar, ob InfoBarController langfristig in einem späteren Refactor in das Modul-System wandert.
  - **`EditBarController`** (Spec 2 §13.2 Z. 1870–1875) — "BLEIBT in EditBar-Controller". Welche Achsen leben dort? Welche wandern in welches Modul (z.B. SmallMode-Toggle ist `Action.LayoutAction.ToggleSmallMode`)? Sec3-Logic L-12 hat das LayoutModule-SRP-Risiko geflaggt; die Verbindung zu EditBarController ist nicht explizit.
  - **`KeyboardUiController`-Step-Row-Logik** (Spec 1 §13.1 Zeile 16) — *"BLEIBT View-Lokal-Logik innerhalb pipeline_step_row-Dynamik"*. Aber die Step-Row-Visibility hängt von `state.pipeline.steps[i].status` ab — ist das State-driven oder View-Lokal?
  - **`promptsCl` / `promptsRv` / `pipelineProgressLl` / `promptRecordingControlsLl`** (Spec 2 §13.1 Zeilen 7–10) — alle "BLEIBT (Promptbar — separates Subsystem)". Aber das Promptbar-Subsystem ist nicht in einer separaten Spec-Datei dokumentiert — es ist nur in §13.1 als "out-of-scope" markiert.

  Konsequenz: ein Implementer kann nach Block-Abschluss nicht einfach prüfen *"habe ich alle UI-relevanten Mutationen aus dem heutigen Code adressiert?"* — er muss raten, ob ein nicht-erwähnter Punkt out-of-scope (akzeptiert) oder vergessen (Bug) ist.
- **Example scenario:** Block-5-Implementer findet `MainButtonsController.kt:368-387` (`refreshAudioFocusIcon`-Methode) — sieht es nicht in §13.1 oder §13.2 erwähnt. Ist das ein Bug, ein bewusster Erhalt, oder eine Lücke? §13.4 erwähnt es als F-4 (RESOLVED), aber §13.1+§13.2 nicht. Implementer ist unsicher.
- **Suggestion:**
  1. **Jede Spec §13 ergänzen** um eine kleine Sektion *"§13.X Bewusst Out-of-Scope"*:
     - Spec 1: Promptbar-Subsystem (Step-Rows, Prompt-Liste); InfoBarController (gekapselt); JobExecutor-Subsystem.
     - Spec 2: EditBarController (separate Achse); MainButtonsController-Theme-Sub-View-Logik (per-character-Views); BackspaceSwipeHandler / EnterOverlayHandler (gekapselte Touch-Handler).
     - Spec 3: Settings-Activity-OverlayPosition-UI (read-only durch Spec 1); Multi-Window-aware-Positioning (Phase 2).
  2. **Hauptplan §3 oder neuer §3.4** als zentralen "Refactor-Boundaries"-Block — eine Linie, die das Refactor explizit von nicht-betroffenen Systemen trennt.

---

### Issue L-13: Sec3-Logic-Findings L-1, L-3, L-7, L-12 (alle Critical) — Status nach Phase-1+2-Apply nicht im Plan reflektiert

- **Category:** [INTEGRATION] / [LOGIC]
- **Severity:** Important
- **Location:** Verifikation gegen `plan-review/phase2/batch1/section3-logic.md` (L-1 bis L-14); Plan-Status-Tracking durch alle 4 Plan-Files.
- **Description:** Section-3-Logic-Review (Batch 1) hat 4 **Critical**-Befunde: L-1 (Send-Mode-Predicate-Drift-Pfad), L-2 (Resend-Toggle-Bug strukturell vs. dokumentiert), L-3 (MotionScene-firstRender-Flag fehlt → Animation-Snap), L-7 (Migration §11.8 Übergangs-State unvollständig). **Status:** PENDING (validated-findings-batch1.md hat diese als 🟡 Needs Decision). Aber:
  - L-3 hat eine konkrete `firstRender`-Flag-Empfehlung, die Phase-1 1.1.5 (LayoutModule SRP) nicht abdeckt; im Plan ist die Empfehlung **nirgends** umgesetzt — Spec 2 §6 zeigt weiterhin nur `motionLayout.transitionToState(targetSceneState)` ohne first-render-Differenzierung. Issue 2.1.18 (validated-findings-batch1.md) ist die kanonische Aufnahme; aber Spec 2 selbst erwähnt das Problem nicht.
  - L-1 hat eine Empfehlung "Inline-Doku in §8.3 + Test in §14.2 + Konsolidierung der Predicates". Spec 2 §13.4 sagt *"identische Visibility-Logik..."* (siehe L-3 oben), aber die L-1-Empfehlung "Predicates so ergänzen, dass sie state.pipeline mitbeachten" ist nicht im Plan umgesetzt.
  - L-7 hat eine Empfehlung *"5c-Tail-Step: KSM.applyRecordingControlsVisibility durch leere Implementation ersetzen, 5d entfernt es dann"*. Spec 2 §11.8 hat die ursprüngliche Reihenfolge weiterhin (5c wired ein, 5d entfernt) — keine Tail-Step-Empfehlung umgesetzt.

  Konsequenz: 4 von 14 Section-3-Logic-Befunden sind Critical und im Plan **nicht** als TODO oder PENDING markiert. Block-5-Implementer sieht den Plan als "vollständig", merkt nicht, dass 4 kritische Logic-Lücken offen sind. Phase-1+Phase-2-Batch-1 haben die Findings dedupliziert/konsolidiert, aber kein Plan-Marker (z.B. ein `<!-- PENDING: Phase-1-Issue 1.1.5 -->` Kommentar) sagt dem Implementer, wo offene Decisions liegen.
- **Example scenario:** Block-5-Implementer arbeitet `implement-long-plan-v2`-Workflow durch. Liest Spec 2 §11.8 — implementiert die 5c/5d-Reihenfolge wörtlich. Sec3-Logic-L-7-Race tritt während des PR-Gaps zwischen 5c und 5d auf — User-Bug-Reports flackernde Visibility. Block 5 wird reopened. Issue 1.1.X-Tracking ist nicht im Plan — Implementer weiß nicht, dass die Phase-1-Diskussion bereits eine Lösung vorschlägt.
- **Suggestion:**
  1. **In jeder Spec §13.5 (oder neuer §13.6 "Open Plan-Review-Issues")** eine Tabelle mit allen 🟡 PENDING-Issues aus validated-findings-{phase1, batch1}.md, die diese Spec betreffen. Format:
     ```
     | Issue | Quelle | Severity | Empfehlung | Status |
     |---|---|---|---|---|
     | 1.1.5 | Phase-1 | Critical | Block-1-Split (1a + 1b) | PENDING (User-Decision offen) |
     | 2.1.18 | Batch-1 | Important | firstRender-Flag in §6 ergänzen | PENDING (DEPENDS ON 1.1.5-Decision) |
     ```
  2. **Inline-Marker im Plan-Body** (z.B. `> [!NOTE] PENDING: siehe Issue 2.1.18 in plan-review/validated-findings-batch1.md`) an den konkreten Code-Snippet-Stellen (Spec 2 §6, §11.8 etc.). Damit weiß ein Implementer beim Lesen sofort, wo offene Decisions sind.
  3. **Hauptplan §7 (Open Questions)** erweitern um die nicht-gelösten 🟡 Issues — aktuell hat §7 5 Einträge, alle als RESOLVED markiert. Die offenen Phase-1-Issues sollten dort sichtbar sein.

---

### Issue L-14: Spec 3 §13.5 GAP-5 dokumentiert HOVER-Schließen-Edge-Case als „bewusste Eigenschaft" — kein Acceptance-Test sichert das ab

- **Category:** [LOGIC] / [ROBUSTNESS]
- **Severity:** Nice-to-have
- **Location:** Spec 3 §13.5 GAP-5 (Z. 1762); Spec 3 §10 Acceptance (Z. 1181–1212); Spec 3 §11.9 (`userPrefersWidget`-Persistenz).
- **Description:** GAP-5: *"Im T6-Pfad ('HOVER → WIDGET') gibt es einen subtilen Edge-Case: User klickt im HOVER auf Schließen → `closeOverlay()` setzt `viewMode = KEYBOARD`, aber `userPrefersWidget` bleibt unverändert (war zuvor `false`, sonst wäre HOVER nicht so erreicht worden). Konsistenz: HOVER-Schließen passt mit `userPrefersWidget=false`. ✓ Verifikation einer Inkonsistenz: wenn User in WIDGET → HOVER (T4) → HOVER-Schließen passiert: `userPrefersWidget` ist noch `true`. Wenn User dann Tastatur öffnet, kommt T6 → WIDGET."* Akzeptiert als *"bewusste Eigenschaft"*.

  Logischer Bruch: GAP-5 dokumentiert einen Edge-Case, dessen Verhalten gewollt ist — User in WIDGET → HOVER (auto, weil View-hidden) → HOVER-Schließen → später Tastatur öffnen → WIDGET. Aber **keine Acceptance** in §10 prüft genau diese Sequenz. §10-Punkt *"Schließen in HOVER: Overlay weg, Pipeline abgebrochen, KEIN neues Overlay erscheint bis User Tastatur explizit öffnet+schließt"* sagt nur "Tastatur öffnet+schließt", nicht "die Tastatur kommt im WIDGET-Modus zurück, wenn `userPrefersWidget=true`".

  Konsequenz: ein Implementer kann den HOVER-Close-Pfad implementieren mit *"setze viewMode=KEYBOARD UND userPrefersWidget=false"* (defensiv, "alle Overlay-State auf Default zurücksetzen"). Das wäre ein Verhalten-Bug gegen die §13.5-GAP-5-Aussage, würde aber durch die §10-Acceptance nicht gefangen — der Test sagt nur *"KEIN neues Overlay erscheint bis User Tastatur öffnet+schließt"*, nicht welches Mode. Iter-3-Reviewer sieht den "defensiven" Code, hält ihn für korrekt — Bug-Regression.
- **Example scenario:** Block-6-Implementer schreibt `closeOverlay()` (Spec 3 §6.1). Defensiv: *"setze sowohl viewMode=KEYBOARD als auch userPrefersWidget=false"*. Test: §10 *"KEIN neues Overlay nach HOVER-Close"* — passes. User-Story: User aktiviert WIDGET → wechselt zu Gboard (View-hidden) → HOVER kommt auto → schließt HOVER → kommt zurück zu Dictate. Wenn `userPrefersWidget=false` jetzt, kommt KEYBOARD-Mode zurück (weil userPrefersWidget=false in T6 → KEYBOARD). User wundert sich: *"Ich hatte WIDGET aktiviert, warum ist das jetzt weg?"* — Bug, GAP-5 verletzt.
- **Suggestion:**
  1. **Spec 3 §10 Acceptance ergänzen:** *"HOVER-Close mit `userPrefersWidget=true` (durch frühere User-Wahl): nach Tastatur-Re-Open kommt WIDGET-Modus zurück, NICHT KEYBOARD-Modus. Verifiziert via §14.2-Test (neu)."*
  2. **Spec 3 §14.2 (oder neuer Test-Case):** *"Test `widgetPersistenceAfterHoverClose`: 1) User aktiviert WIDGET, 2) View hidden simulieren, 3) HOVER kommt auto, 4) User klickt HOVER-Close, 5) View wieder visible, 6) Verify: viewMode == WIDGET, userPrefersWidget == true."*

---

## Summary Table

| # | Category | Severity | Issue | Description |
|---|----------|----------|-------|-------------|
| L-1 | [LOGIC] / [INTEGRATION] | Important | §13.3.4/§13.3.9 widersprechen §13.3-Behauptung | F-11-Audit-Track ist unvollständig — 12 Module ohne SOLID-Audit, nur RecordingModule beispielhaft. |
| L-2 | [LOGIC] | Important | §13.1 Spec 1 ↔ Spec 2 widersprechen sich an 2 Sites | overlayCharactersLl + EnterOverlayHandler.kt sind in einer Spec "WANDERT", in der anderen "BLEIBT" — direkter Audit-Konflikt. |
| L-3 | [LOGIC] / [CLEAN] | Important | §13.4-DRY-Behauptung deckt Layout-DRY, übersieht Predicate-Body-DRY | predTrashVisible ≡ predPauseVisible (Sec3-L-5) ist nicht in §13.4 als Duplikation klassifiziert. |
| L-4 | [LOGIC] | **Critical** | Resend-Toggle-Bug fehlt in jeder Acceptance-Liste | Hauptplan §1.1 listet 3 User-Bugs; §10 in Spec 1+2+3 testet nur den Send-Mode-Bug, nicht den Resend-Toggle-Bug. |
| L-5 | [LOGIC] / [INTEGRATION] | **Critical** | Block-1-Reihenfolge-Garantie ist unter F-11 nicht erfüllbar | Hauptplan §4 sagt "Block 1 vor allem"; F-11-Block-1 braucht Block-2-Container. §6 erfasst das Risiko nicht. |
| L-6 | [LOGIC] / [INTEGRATION] | **Critical** | Spec 3 §13.4 Cross-Spec-Konsistenz behauptet "selbe Action-Typen" | §5/§6/§7 zeigen direkten _state.value.copy()-Bypass — §13.4 ist intern widersprüchlich. |
| L-7 | [LOGIC] / [ROBUSTNESS] | Important | §13.5 G6 (MediaRecorder-Leak) nicht in §10 Acceptance getestet | Pfad A (Service.onDestroy → release) ist code-implementiert aber acceptance-test-frei. |
| L-8 | [LOGIC] / [INTEGRATION] | Important | Phase-1 1.0.2 fixte LogicalButtonId-Liste, §13.1+§13.2 nachpflege fehlt | WIDGET_TOGGLE in Hauptplan-Liste, aber nicht in Spec-2-Audit-Tabellen. Sec3-Logic L-9 Silent-Skip-Risiko. |
| L-9 | [LOGIC] | Important | Cross-Module-Cascade in §15.1-Tabelle markiert, aber kein Acceptance-Test | 7 Cascades dokumentiert, 0 Acceptance-Punkte für Cascade-Verifikation. |
| L-10 | [LOGIC] / [CLEAN] | Nice-to-have | §2.2-Erfolgskriterium ist subjektiv, nicht testbar | "ein Ort, automatisch korrekt" hat keine Operationalisierung. |
| L-11 | [INTEGRATION] / [LOGIC] | Nice-to-have | §9 Iter-Log reflektiert F-1..F-11, nicht Phase-1+2-Apply | ~18 weitere Plan-Modifikationen ohne Iter-Log-Eintrag. |
| L-12 | [LOGIC] | Nice-to-have | §13 testet "was passiert", nicht "was bewusst nicht passiert" | Promptbar / EditBarController / Step-Rows sind out-of-scope ohne expliziten Marker. |
| L-13 | [INTEGRATION] / [LOGIC] | Important | Sec3-Logic L-1, L-3, L-7 (Critical) nicht im Plan-Body markiert | 🟡 PENDING-Issues sind in `plan-review/`-Files, nicht im Plan selbst — Implementer übersieht offene Decisions. |
| L-14 | [LOGIC] / [ROBUSTNESS] | Nice-to-have | GAP-5 (HOVER-Close + userPrefersWidget) ohne Acceptance-Test | "Bewusste Eigenschaft" laut §13.5, aber §10 prüft nicht das spezifische Verhalten — Regression-Risiko. |

---

## Notes for Reviewer

### Cross-Cutting-Themen
- **Größtes Critical-Bündel:** L-4 + L-5 + L-6 sind alle Critical und behandeln **drei verschiedene Audit-Konsistenz-Probleme**. Sie sind unabhängig voneinander, aber inhaltlich verwandt: alle drei sind Beispiele für *"Plan behauptet etwas in der Verifikations-Sektion, das im Body widerlegt wird"*.
- **Dominanter Pattern:** Der Plan trägt eine starke Architektur-Iteration (F-1..F-11) und eine starke Audit-Sektion (§13) — beides ist gut. Aber die **Verbindung** zwischen Audit-Behauptung und Code-Beispiel-Body ist oft schwach: Behauptung sagt "Single-Dispatch", Body zeigt direkten copy(); Behauptung sagt "alle 27 Mutationen adressiert", Tabellen-Klassifikationen widersprechen sich; Behauptung sagt "DRY beweisbar", aber DRY-Tabelle erfasst nur eine Drittel der relevanten Sites.
- **Acceptance-Test-Lücke (sammelnd):** L-4 + L-7 + L-9 + L-14 zeigen denselben Failure-Modus — der Plan hat eine **Verhaltens-Aussage** (Bug-Fix oder Edge-Case-Eigenschaft), aber **keinen Acceptance-Test, der diese Aussage in einem Block-§10-Bullet bekräftigt**. Wenn der Plan implementiert wird, kann ein Block "done" markiert werden, ohne dass die Verhaltens-Aussage validiert ist. Das ist die gefährlichste Klasse von Plan-Logik-Lücken.
- **Phase-1+Phase-2-Batch-1-Tracking-Lücke (L-13):** der Plan referenziert die Plan-Review-Files implizit, aber kein Plan-Body-Marker zeigt offene Phase-1+2-Issues. Implementer können kritische Decisions verpassen.

### Empfohlene Apply-Reihenfolge
1. **Erst L-5 + L-4 entscheiden** (Block-Split + Acceptance-Bug-Tests) — sie blockieren die Implementation am stärksten.
2. **Dann L-6** — Spec 3 dispatch()-Pfad muss vor Block 6 geklärt sein.
3. **L-1 + L-2 + L-8** sind reine Doku/Audit-Cleanups, niedrigere Priorität, aber wichtig für Plan-Konsistenz.
4. **L-3 + L-7 + L-9 + L-14** sind Acceptance/Test-Erweiterungen — kann parallel zu Block-1-Implementation laufen.
5. **L-10 + L-11 + L-12 + L-13** sind Plan-Hygiene — Nice-to-have, idealerweise vor Phase-5-Closure.

### Section-5-Specific Observations
- **§13-Verifikation-Logik (Beweis ↔ Behauptung):** L-1, L-2, L-3, L-6 sind die Kern-Findings. Spec-1/2/3 §13 sind alle einzeln gut aufgebaut, aber **cross-spec konsistent zu lesen** zeigt mehrere Logic-Brüche.
- **Cross-Module-Acceptance:** L-4, L-7, L-9, L-14 zeigen, dass die kritischsten **Verhaltens-Eigenschaften** des Refactors (Bug-Fix-Garantien, Cross-Module-Cascades, Edge-Case-Eigenschaften) keinen testbaren Acceptance-Anker haben.
- **Hauptplan-Logic:** L-5 (Block-Reihenfolge-Race) ist der größte Hauptplan-Befund. §6 Risiken hat 7 Einträge, aber das wichtigste systemische Risiko fehlt.
- **Cross-Spec-Logic-Konflikte:** L-2 (überlappende Visibility-Site-Klassifikation), L-6 (dispatch-Vertrag-Bypass), L-8 (LogicalButtonId-Drift) sind alle "Spec X sagt A, Spec Y sagt B" — Single-Source-of-Truth ist auf Audit-Ebene nicht eingehalten.

### Offene Fragen, die User-Decision brauchen
- **L-5 (Block-Split 1a + 1b):** Phase-1 1.1.6 hat Option A vorgeschlagen, User-Decision ⬜ PENDING. Ohne diese Entscheidung kann L-5 nicht final geschlossen werden.
- **L-6 (Spec-3-dispatch-Migration):** Phase-1 1.1.2 ist ⬜ PENDING. Section-5-Logic verstärkt Phase-1, kann aber die Architektur-Wahl nicht selbst treffen.
- **L-13 (Plan-Body-Marker für offene Issues):** strukturelle Entscheidung, ob der Plan einen `> [!NOTE] PENDING:`-Pattern adoptiert. Wenn ja, alle 🟡 Issues bekommen Inline-Marker.
