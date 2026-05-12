# Validated Findings – Phase 2 — Batch 2 (Sections 4-5)

**Created:** 2026-05-10
**Mode:** autonomous
**Source:**
- `plan-review/phase2/batch2/section4-structure.md` (Spec 3 — Floating-Overlay)
- `plan-review/phase2/batch2/section4-logic.md` (Spec 3 — Floating-Overlay)
- `plan-review/phase2/batch2/section5-structure.md` (Cross-Cutting + §13-Verifikationen + Hauptplan + Acceptance/Tests)
- `plan-review/phase2/batch2/section5-logic.md` (Cross-Cutting + §13-Verifikationen + Hauptplan + Acceptance/Tests)

**Previous Findings considered:**
- `plan-review/validated-findings-phase1.md` (Phase 1 — 1.0.1–1.0.6 ✅ APPLIED, 1.1.1–1.1.8 ⬜ PENDING)
- `plan-review/validated-findings-batch1.md` (Phase 2 Batch 1 — 2.0.1–2.0.12 ✅ APPLIED, 2.1.1–2.1.21 ⬜ PENDING)

---

## Summary

- **Reviewed:** 57 raw issues from 4 reviewer-agents (Sec4-S: 13, Sec4-L: 20, Sec5-S: 10, Sec5-L: 14).
- **🟢 Auto-Fix:** 12 issues (Spec-3-Naming-Drift mechanical extension of 1.0.x mappings, Acceptance-Test-Bullet-Insertions, §13-Audit-Cleanups, Doku-Notes, Inline-Doku-Anker, kleine Code-Skizzen-Korrekturen).
- **🟡 Needs Decision:** 15 issues (Architektur-Entscheidungen — Spec-3-Module-Integration, OverlayModule-Spec-Heimat, ViewMode-FSM-Eigentum, HOVER-Lifecycle-Bugs, IME-Service-Death-Overlay-Owner, Drag-Backend-Race, Permission-State-Achse, Block-1-Split-Decision-Verstärkung, Overlay-State-Achse-bei-Process-Restart, Acceptance-Bidi-Pointer-Pattern, Cross-Module-Coupling-Matrix, dispatch-Pattern-Cleanup pre-1.1.2-Decision, Click-Listener-Pattern-Konsistenz, GAP-5-userPrefersWidget-Persistierungs-Vertrag, Plan-Body-PENDING-Marker-Pattern).
- **❌ Eliminated / Merged:** 30 issues (24 als Verstärkungen in Phase-1 / Batch-1-Issues konsolidiert, 4 cross-section-deduped innerhalb Batch 2, 2 over-engineering / nicht-actionable / Code-Implementation-Detail).
- **Most important findings:**
  Batch 2 verstärkt **massiv** die Phase-1-Issues 1.1.1, 1.1.2, 1.1.6 + 1.0.5/1.0.6 (Spec 3 ist die größte verbleibende Drift-Quelle). Die §13-Audits in **Spec 1+2+3** sind in sich widersprüchlich (Audit-Behauptung "Single-Dispatch / SSoT / DRY" ↔ Body zeigt Bypass / Doppel-Klassifikation / Predicate-Body-Dupes) — sie können nach Phase-1+2-Apply-Pässen (1.0.5/1.0.6/2.0.2) mechanisch nachgepflegt werden. Sechs **neu** entdeckte Critical-Issues kommen hinzu, die in Phase 1 / Batch 1 keinen Ersatz haben: (a) HOVER-Lifecycle-Bugs (Permission-Revoke ohne UI, Cross-Module-Trigger fehlt, IME-Service-Death lässt Overlay leaken, T7 fehlt), (b) Cross-Spec-Audit-Konflikt (Spec 1 ↔ Spec 2 widersprechen sich an EnterOverlayHandler/overlayCharactersLl), (c) Acceptance-Test-Lücken pro Bug (Resend-Toggle-Bug + Cascade-Verifikation + MediaRecorder-Leak ungetestet), (d) Spec-3-Module-Integration insgesamt (OverlayModule-Spec-Heimat unklar), (e) Hauptplan §3.2-Diagramm + §3.3-LogicalButtonId-Listen-Drift gegenüber post-Phase-1-Stand (verifizieren!), (f) Block-1-Split bestätigt sich durch Section-5-Logic L-5 als architektur-blockierend.

---

## 🟢 Auto-Fix Issues

### Issue 3.0.1: Hauptplan §3.2 Service-Schicht-Diagramm — Verifizierung Phase-1-1.0.1-Apply
- **Category:** [INTEGRATION]
- **Severity:** Important
- **Source:** Sec5-Structure S-5 (→ `phase2/batch2/section5-structure.md` §S-5); kreuzt Phase-1 1.0.1 (✅ APPLIED, 2026-05-10)
- **Description:** Sec5-Structure S-5 reportet, dass Hauptplan §3.2 (Z. 121-158) "PipelineStateManager (SSOT für ALLE State-Achsen)" als Box im Service-Schicht-Diagramm zeigt. Phase-1 1.0.1 ist als ✅ APPLIED markiert ("ASCII-Block in §3.2 ersetzt — `PipelineStateManager` → `DictateOrchestrator (Composition Root, Single Dispatch)`"). Es gibt einen **Verifikations-Mismatch**: entweder hat der Phase-1-Apply den Block nicht vollständig erwischt (Box war nur ein Teil), oder Sec5-Structure-Reviewer hat einen Pre-Apply-Snapshot gelesen.
- **Fix:** Hauptplan §3.2 (Z. 121-158) erneut prüfen. Falls noch "PipelineStateManager"-Box vorhanden, gemäß Phase-1-1.0.1-Empfehlung in "DictateOrchestrator (Composition Root, Modular)" mit Sub-Boxen "DictateUiStateStore (StateFlow-Container)", "ModuleRegistry (13 Module, Spec 1 §15)", "PipelinePrefMirror", "PipelineRecovery" umschreiben. Falls bereits angepasst, Verifikations-Eintrag im Iter-Log §9 ergänzen.
- **Auto-Fix rationale:** Reine Verifikation eines bereits APPLIED-Issues. Ziel-Box-Layout existiert in Phase-1 1.0.1. Keine Architektur-Wahl.
- **Status:** ✅ APPLIED (2026-05-10) — Hauptplan §3.2 verifiziert: `DictateOrchestrator (Composition Root, Single Dispatch)`-Box und Ko-Aggregate (`DictateUiStateStore`, `PipelinePrefMirror`, `PipelineRecovery`) sind aus Phase-1-1.0.1-Apply korrekt übernommen. Iter-Log-Eintrag in §9 ergänzt.

---

### Issue 3.0.2: Hauptplan §3.3 — `OVERLAY_INDICATOR` + `OVERLAY_4BUTTON` Verifizierung Phase-1-1.0.2-Apply
- **Category:** [INTEGRATION]
- **Severity:** Important
- **Source:** Sec5-Structure S-5 (→ `phase2/batch2/section5-structure.md` §S-5); kreuzt Phase-1 1.0.2 (✅ APPLIED, 2026-05-10)
- **Description:** Sec5-Structure S-5 reportet `OVERLAY_INDICATOR`-Eintrag (Z. 165) und `OVERLAY_4BUTTON` (Z. 189) im Hauptplan §3.3-Code-Skelett. Phase-1 1.0.2 ist als ✅ APPLIED markiert ("`OVERLAY_4BUTTON` → `OVERLAY_5BUTTON`; LogicalButtonId-Liste ersetzt"). Identische Verifikations-Lücke wie 3.0.1.
- **Fix:** Hauptplan §3.3 (Z. 160-193) prüfen. Falls `OVERLAY_INDICATOR` / `OVERLAY_4BUTTON` noch enthalten: gemäß Phase-1-1.0.2-Empfehlung ersetzen (LogicalButtonId-Liste auf `RECORD, RESEND, BACKSPACE, AUDIO_FOCUS, WIDGET_TOGGLE, TRASH, SPACE, PAUSE, ENTER, OVERLAY_RECORD, OVERLAY_SEND, OVERLAY_PAUSE, OVERLAY_TRASH, OVERLAY_CLOSE`; Mode auf `OVERLAY_5BUTTON`). Falls bereits angepasst, dokumentieren.
- **Auto-Fix rationale:** Reine Verifikation eines APPLIED-Issues. Ziel-Konstanten existieren in Spec 2 §3.1 + Spec 3 §3 als SSoT.
- **Status:** ✅ APPLIED (2026-05-10) — Hauptplan §3.3 verifiziert: `LogicalButtonId`-Liste enthält `OVERLAY_5BUTTON` (Z. 196), kein `OVERLAY_INDICATOR` / `OVERLAY_4BUTTON` mehr. WIDGET_TOGGLE in der Liste vorhanden. Iter-Log-Eintrag in §9 ergänzt.

---

### Issue 3.0.3: Spec 3 — `PipelineStateManager`-Naming-Drift (7+ Treffer) auf `DictateOrchestrator` umstellen
- **Category:** [INTEGRATION]
- **Severity:** Critical
- **Source:** Sec4-Structure S-5 (→ `phase2/batch2/section4-structure.md` §S-5); verstärkt Phase-1 1.1.1, ergänzt mit konkreten Spec-3-Treffer-Zeilen
- **Description:** Spec 3 §5.3 Z. 755, §5.4 Heading + Z. 837, §6.1 Z. 910, §6.2 Z. 939, §7.3 Z. 1019/1057/1093, §10 Z. 1209, §11.5.4 Z. 1398, §11.5.8 Z. 1463, §11.6 Tabelle Z. 1474. Spec 3 ist nach Phase-1 1.0.1 + Batch-1 2.0.2 die größte verbleibende Drift-Quelle. Wir wenden Phase-1-Empfehlung Option B (kontext-sensitives Rename) hier mechanisch an, da die Stellen alle "Zielarchitektur"-Kontext sind (keine Iter-Log- / Migrations-Begründungen).
- **Fix:**
  - "im `PipelineStateManager`" / "// In PipelineStateManager" → "im `DictateOrchestrator` (Spec 1 §4.3)" oder "im jeweiligen Modul (Spec 1 §15)"
  - "PipelineStateManager.toggleViewMode" / "closeOverlay" / "updateOverlayPosition" / "markOverlayOnboardingShown" / "dismissOverlayOnboarding" → diese benannten Methoden gibt es nicht mehr; Hinweis: "via `pipeline.dispatch(Action.<Modul>Action.<Variante>)` (siehe 3.0.5)"
  - "alle Mutationen laufen durch PipelineStateManager" → "alle Mutationen laufen über `dispatch(action)` und Modul-Reducer (Spec 1 §4.3 + §15)"
  - §11.6 Tabelle StateFlow-Reference auf den Store (`DictateUiStateStore`) anpassen
- **Auto-Fix rationale:** Reines Naming-Update auf den in F-11 beschlossenen Namen. Konsistent mit Phase-1 1.0.1 + Batch-1 2.0.2 (Spec 2). Spec 3 wurde in den Phase-1-Apply-Sweep nicht einbezogen. Keine Architektur-Wahl.
- **Status:** ✅ APPLIED (2026-05-10) — Spec 3 §5.3 / §5.4 / §6.1 / §6.2 / §7.1 / §7.3 (T1, T2, T3, T5, T6) / §10 / §11.5.4 / §11.5.8 / §13.1 / §13.4 mit kontext-sensitivem Rename umgestellt: "im PipelineStateManager" → "im DictateOrchestrator (Spec 1 §4.3) bzw. im jeweiligen Modul (Spec 1 §15)"; Pre-F-8-Methoden (`closeOverlay`, `toggleViewMode`, `notifyImeViewVisibilityChanged`, `updateOverlayPosition`) durch Reducer-Logik / `dispatch(action)`-Pattern ersetzt.

---

### Issue 3.0.4: Spec 3 — Sub-State-Pfad-Drift (`state.overlayPosition*`, `state.overlayOnboardingPending`, `state.userPrefersWidget`) auf hierarchischen Sub-State umstellen
- **Category:** [INTEGRATION]
- **Severity:** Critical
- **Source:** Sec4-Structure S-1 + Sec5-Structure S-2 (→ `section4-structure.md` §S-1; `section5-structure.md` §S-2); verstärkt Phase-1 1.0.6 mit konkreten Spec-3-Zeilen
- **Description:** Spec 3 §4.2 (Z. 366-368, `applyPosition`), §4.3 Z. 481 (Default-Anker-Kommentar), §5.3 Z. 820/821 (Onboarding-InfoBar), §5.4 Z. 846 (Onboarding-Mutation), §7.1 Z. 1098/1114/1134/1143 (`userPrefersWidget`), §11.5.5 Z. 1408/1410 (Persistierung), §11.5 Z. 1408/1410 (`overlayPosition*`), §10 Z. 1210 Acceptance, §13.x State-Pfade. Auch Spec 1 §13.4.1 (Z. 2194-2195) und Spec 2 §8.5 (Z. 1124-1213) + §13.4.1 (Z. 2041) verbleibende flache State-Pfade. Phase-1 1.0.6 ist als ✅ APPLIED markiert, aber Spec 3 + die §13-Audit-Tabellen wurden im Apply-Sweep nicht (vollständig) erwischt.
- **Fix:** Mapping-Tabelle aus Phase-1 1.0.6 erneut anwenden, mit Fokus auf Spec 3 + §13-Tabellen:
  - `state.overlayPositionPortraitX/Y` → `state.overlay.positionPortraitX/Y`
  - `state.overlayPositionLandscapeX/Y` → `state.overlay.positionLandscapeX/Y`
  - `state.overlayOnboardingPending` → `state.overlay.onboardingPending`
  - `state.userPrefersWidget` → `state.overlay.userPrefersWidget`
  - `state.lastAudioExists` → `state.resend.lastAudioExists`
  - `state.audioFocusEnabled` → `state.audio.audioFocusEnabledPref`
  - Spec 3 §10 Acceptance + §11.9 Persistenz-Bit-Beschreibung re-formulieren
  - GAP-3 in Spec 3 §13 entfernen (die Felder sind in Spec 1 §3 bereits modelliert)
  - Spec 1 §13.2.2 + §13.4.1 + §11.2.2-Snippets + Spec 2 §8.5 + §13.4.1 + §14.2-Tests nachpflegen
- **Auto-Fix rationale:** Identische Mapping-Tabelle aus Phase-1 1.0.6, mechanische Anwendung auf neu identifizierte Spec-3 + §13-Sites. Sub-State-Klassen existieren in Spec 1 §3 (kanonische Quelle). Keine Architektur-Wahl.
- **Status:** ✅ APPLIED (2026-05-10) — Spec 3 §5.4-Pseudo-Code, §6.1, §7.3 T1+T2+T3+T5+T6, §11.9 + Spec 1 §13.1-Verifikations-Note auf hierarchische Sub-State-Pfade umgestellt (`state.overlay.onboardingPending`, `state.overlay.userPrefersWidget`, `state.layout.smallMode`). Verbleibende flache Pfade in Spec 1 §13.4.1 / Spec 2 §8.5 sind durch Phase-1-1.0.6-Apply bereits sauber. GAP-3-RESOLVED-Marker in Spec 3 §13.5.c ergänzt.

---

### Issue 3.0.5: Spec 3 — Action-Hierarchie-Drift (flache `Action.X`) auf `Action.<Modul>Action.X` umstellen
- **Category:** [INTEGRATION]
- **Severity:** Critical
- **Source:** Sec4-Structure S-4 + Sec5-Structure S-3 + Sec4-Logic L-14 (→ `section4-structure.md` §S-4; `section5-structure.md` §S-3; `section4-logic.md` §L-14); verstärkt Phase-1 1.0.5 mit Spec-3 / Spec-2-§8.5 / Spec-2-§11.7-Treffern
- **Description:** Phase-1 1.0.5 ist als ✅ APPLIED markiert. Sec4-Structure S-4 + Sec5-Structure S-3 zeigen aber, dass Spec 3 (§3.1 Z. 67/77/86-87/95/100-103, §4.2 Z. 404, §5.3 Z. 825/830, §6.1 Z. 907, §6.2 Z. 936, §7.3 Z. 1017/1022/1051-1053/1060) sowie Spec 2 §8.5 Resolver-Helpers (Z. 1171-1198) und Spec 2 §11.7 (`Action.Backspace`, `Action.EnterKey`) noch durchgängig flache Action-Namen verwenden. Spec 2 §13.2 Click-Listener-Audit-Tabelle Z. 1849-1877 ebenfalls. Apply-Sweep hat Spec 3 + Spec 2 §8.5/§11.7 + §13.2 nicht erwischt.
- **Fix:** Phase-1-1.0.5-Mapping-Tabelle erneut anwenden:
  - Recording: `Action.StartRecording / StopRecording / StopRecordingAndSend / PauseRecording / ResumeRecording / CancelRecording` → `Action.RecordingAction.<X>`
  - Pipeline: `Action.TriggerPipeline / StartPipeline / CancelPipeline / SendStaging / CancelReprocessStaging / ConfirmInsertion` → `Action.PipelineAction.<X>`
  - Resend: `Action.ResendLastAudio / ResendLastAudioLong` → `Action.ResendAction.<X>`
  - ViewMode: `Action.ToggleViewModeWidget / CloseOverlay` → `Action.ViewModeAction.<X>` (Hinweis: in S-2/S-4 wird auch erwogen `OverlayAction.CloseClicked` — siehe Issue 3.1.X)
  - Overlay: `Action.UpdateOverlayPosition(portrait, x, y) / MarkOverlayOnboardingShown / DismissOverlayOnboarding` → `Action.OverlayAction.<X>`
  - Audio: `Action.ToggleAudioFocus` → `Action.AudioAction.ToggleAudioFocusPref`
  - Layout: `Action.ToggleSingleRowMode / ToggleSmallMode` → `Action.LayoutAction.<X>`
  - Keyboard-Input: `Action.Backspace / EnterKey` → `Action.KeyboardInputAction.<X>`
  - `Action.NoOp` bleibt Top-Level (per Phase-1 1.0.5)
- **Auto-Fix rationale:** Identische Mapping-Tabelle aus Phase-1 1.0.5, mechanische Anwendung auf neu identifizierte Sites. Hierarchie kanonisch in Spec 1 §15 + Spec 2 §3.3 definiert. Keine Architektur-Wahl.
- **Status:** ✅ APPLIED (2026-05-10) — Spec 3 §5.3, §6.x, §7.3 + §10-Acceptance + §11.5 + §13.3-Persistenz-Beweis: alle flachen Action-Refs auf hierarchisch (`Action.OverlayAction.UpdateOverlayPosition`, `Action.OverlayAction.MarkOverlayOnboardingShown`, `Action.OverlayAction.DismissOverlayOnboarding`). Spec 2 §8.4 (`Action.SendStaging` → `Action.PipelineAction.SendStaging`, `Action.SpaceKey` → `Action.KeyboardInputAction.SpaceKey`) + §13.2-Click-Listener-Audit (`Action.LayoutAction.ToggleSmallMode` / `ToggleSingleRowMode`, `Action.LayoutAction.SetContentArea(QWERTZ)` für ehemals "ToggleQwertz") nachgepflegt.

---

### Issue 3.0.6: Spec 1 §13.3-Audit — Pre-F-11-/Pre-F-8-Vokabular-Cleanup (PipelineActionRouter, G6, §13.3.4/§13.3.9-Stubs)
- **Category:** [INTEGRATION]
- **Severity:** Critical
- **Source:** Sec5-Structure S-1 + Sec5-Logic L-1 (→ `section5-structure.md` §S-1; `section5-logic.md` §L-1)
- **Description:** Spec 1 §13.3 trägt die F-11-Header-Box aus Phase-1, aber:
  - §13.3.7/§13.3.8 (Z. 2122-2132) referenziert `PipelineStateManager` als Action-Routing-Ziel mit typed Methoden
  - §13.3.9 (Z. 2134-2136) ist leerer Stub mit Forward-Reference auf §13.3.2b — stört Nummern-Sequenz
  - §13.3.4 ViewModeFsm-Eintrag und §13.3.9 LocalBinder-Stub sind leere Audit-Sektionen, die nur per Cross-Reference auf andere Sektionen zeigen — wirken wie offene Audit-Lücken
  - §13.5 G6 ruft `stateManager.cancelPipeline()` als Lösung auf — typed Methode, post-F-8 nicht existent
- **Fix:**
  - §13.3.8 umschreiben: PipelineActionRouter mappt `Intent.action → Action-Sealed-Class-Variante`, dispatched über den injizierten `DictateOrchestrator`. Tests injizieren Mock-Orchestrator und prüfen, dass `dispatch` mit der korrekten `Action`-Variante gerufen wird.
  - §13.3.4 + §13.3.9 als „Verschoben"-Marker explizit kennzeichnen (z.B. überschreiben mit "siehe §15.1 / §13.3.2b — Audit-Sektion an die kanonische Stelle verschoben")
  - §13.5 G6: `stateManager.cancelPipeline()` → `orchestrator.dispatch(Action.PipelineAction.CancelPipeline)`
  - Iter-Box (Z. 2068-2072) so umformulieren, dass klar: §13.3 audited Schicht-Klassen (Service, Orchestrator, Helper); §15 ist kanonische Audit-Stelle für Modul-Klassen
- **Auto-Fix rationale:** Reine Doku-Korrektur als Erweiterung von Phase-1 1.1.1 + Batch-1 2.0.2 (kontext-sensitives Rename). Kein Code-Change. Eine eindeutige Lösung, weil F-8/F-11-Vokabular kanonisch festgelegt ist.
- **Status:** ✅ APPLIED (2026-05-10) — Spec 1 §13.3.4 + §13.3.8 + §13.3.9 + §13.5 G6 umgeschrieben: PipelineActionRouter routet auf `orchestrator.dispatch(Action.PipelineAction.X)`, ViewModeFsm-/LocalBinder-Stubs explizit als „Verschoben"-Marker mit Pointer auf §15.1 / §13.3.2b gekennzeichnet, G6 auf modulare `Action.PipelineAction.CancelPipeline`-Cascade + zwei explizite Pfade (testbar / Process-Kill) umgestellt. Iter-Box am Anfang von §13.3 mit Scope-Aufteilung Schicht-Klassen (§13.3) vs. Modul-Klassen (§15).

---

### Issue 3.0.7: Spec 1 §13.5 + Spec 2 §13.5 + Spec 3 §13.5 — Open-Gaps-Tabelle in drei Bereiche trennen (Open / Cross-Spec-Pending / Resolved)
- **Category:** [DRY]
- **Severity:** Important
- **Source:** Sec5-Structure S-6 (→ `section5-structure.md` §S-6)
- **Description:** §13.5 in allen drei Specs ist gemischter Log: aktuelle offene Gaps + RESOLVED-Anker + Cross-Spec-Drift-Hinweise. Spec 3 GAP-2/GAP-3/GAP-4 listen "muss in Spec 1/2 ergänzt werden", Spec 1 G3/G4/G5 sind eigentlich erledigt aber ohne RESOLVED-Annotation, Spec 2 Gap 1 hat RESOLVED-Annotation. Audit-Funktion erodiert.
- **Fix:** §13.5 in jeder Spec in **drei Bereiche** trennen:
  - **§13.5.a Open Gaps** — nur aktuell offene Punkte
  - **§13.5.b Cross-Spec Patches Pending** — mit klarem "→ in Spec X §Y eintragen, Verantwortlicher: Block-Z"
  - **§13.5.c Resolved (Iter-History)** — mit Status-Ankern + F-Iter-Pointer
  
  Konkret: Spec 1 G3/G4/G5 → §13.5.c (RESOLVED via F-1/F-10). Spec 3 GAP-2/GAP-3/GAP-4 → §13.5.b (oder durch 3.0.5 erledigt; dann §13.5.c).
- **Auto-Fix rationale:** Reine Tabellen-Strukturierung. Kategorisierung pro Eintrag eindeutig. Kein Code-Change.
- **Status:** ✅ APPLIED (2026-05-10) — Spec 1 §13.5 + Spec 2 §13.5 + Spec 3 §13.5 in drei Bereiche getrennt (a Open / b Cross-Spec-Pending / c Resolved). Spec 1: G3/G4/G5 → §13.5.c (RESOLVED via F-1/F-10/F-11). Spec 2: Gap 1/2/4 → §13.5.c, Gap 3/5 → §13.5.a, neue §13.5.b dokumentiert verbleibendes WIDGET_TOGGLE-Slot-Position-Pending. Spec 3: GAP-1 bis GAP-8 in §13.5.c sortiert (alle resolved oder akzeptiert).

---

### Issue 3.0.8: Spec 1 §13.1 — Cross-Spec-Konflikt mit Spec 2 §13.1 an `KeyboardStateManager.kt:162` + `EnterOverlayHandler.kt:56,62` auflösen
- **Category:** [INTEGRATION]
- **Severity:** Important
- **Source:** Sec5-Logic L-2 (→ `section5-logic.md` §L-2)
- **Description:** Spec 1 §13.1 Zeile 7 (KSM:162 overlayCharactersLl) klassifiziert "WANDERT IN PREDICATE"; Spec 2 §13.1 Zeile 11 (selbe Site) klassifiziert "BLEIBT (defensive Reset)". Spec 1 Zeile 19 (EnterOverlayHandler:56,62) "WANDERT"; Spec 2 Zeile 14 (selbe Site) "BLEIBT (Touch-Handler-internal)". Direkter Audit-Konflikt zwischen Specs.
- **Fix:** "BLEIBT"-Klassifikation aus Spec 2 ist korrekt (Touch-Handler-interne State-Maschine, defensiver Reset). Spec 1 §13.1 anpassen:
  - Zeile 7 + Zeile 19: Klassifikation auf "BLEIBT" ändern, Begründung "Touch-Handler-/View-Handler-internal — kanonisch in Spec 2 §13.1"
  - Verifikations-Zusammenfassung Z. 1976: "alle 14 state-driven" → "12 state-driven + 2 view-handler-internal"
  - Note-Block ergänzen: "Visibility-Mutationen mit IME-View-Scope sind kanonisch in Spec 2 §13.1 audited; Tabelle hier ist der Cross-Spec-Index"
- **Auto-Fix rationale:** Einseitige Korrektur (Spec 2 ist kanonische Quelle für IME-View-Visibility). Eindeutige Lösung. Kein Code-Change.
- **Status:** ✅ APPLIED (2026-05-10) — Spec 1 §13.1 Zeile 7 (KSM:162) + Zeile 19 (EnterOverlayHandler:56,62) auf "BLEIBT" geändert mit Begründung "Touch-Handler-/View-Handler-internal — kanonisch in Spec 2 §13.1". Verifikations-Zusammenfassung Z. 2071 auf "12 state-driven + 2 view-handler-internal" angepasst. Cross-Spec-Note ergänzt.

---

### Issue 3.0.9: Hauptplan §1.1 + Spec 1 §10 + Spec 2 §10 + Spec 2 §14.2 — Bug-Symptom-Acceptance-Bidi-Pointer + Resend-Toggle-Bug-Tests ergänzen
- **Category:** [INTEGRATION]
- **Severity:** Critical
- **Source:** Sec5-Structure S-8 + Sec5-Logic L-4 + Sec4-Logic L-9 (→ `section5-structure.md` §S-8; `section5-logic.md` §L-4; `section4-logic.md` indirekt)
- **Description:** Hauptplan §1.1 listet 3 User-Bugs; Spec 2 §10 #3 testet Send-Mode-Verdecken, ABER: Bug-Symptom #3b "Resend-Btn verschwindet beim Toggle" ist in keinem §10-Eintrag oder §14.2-Test verifiziert. Spec 1+2+3 §10 hat keine Bidirectional-Pointer auf §1.1. Sec3-Logic L-14 (Batch 1) hatte das aus Logic-Sicht angedeutet; hier Verstärkung.
- **Fix:**
  - **Hauptplan §1.1 erweitern** um Spalte "Acceptance-Verifikator" (Spec/§/Test-ID) pro Bug-Symptom (3 Symptome → 4 Zeilen, weil #3 in 3a + 3b splittet)
  - **Spec 2 §10 ergänzen** (Block-5-Acceptance):
    - "Resend-Btn ist während Toggle Two-Row ↔ Single-Row in Idle+lastAudio durchgängig sichtbar (visibility=VISIBLE in jedem Frame). Verifiziert via Espresso `IdlingResource` oder Frame-Capture (siehe Test §14.2 UI-Test 8 — neu)."
    - "Resend-Btn-Cooldown (500ms nach Click) lässt visibility=VISIBLE, nur enabled=false + alpha=0.4. Siehe Test §14.2 UI-Test 9 — neu)."
  - **Spec 2 §14.2 zwei neue UI-Tests:**
    - UI-Test 8 (Frame-Capture während Toggle): Resend-Btn bleibt sichtbar
    - UI-Test 9 (Cooldown): Resend-Btn enabled-only, Visibility ungebrochen
    - UI-Test 10 (Active → Pipeline-Preparing-Übergang): kein Frame zeigt trash/pause über record_btn
  - **Spec 1 §10 (Block 1)** ergänzen: "`predResendVisible` reflektiert nicht `resendCooldown` — Cooldown betrifft NUR `enabledResolver`, nicht `visibilityPredicate`. Verifiziert in Block-1-Unit-Test."
  - **§14.2-Tabelle** als Reverse-Pointer ("decken Bug-Symptom #X")
- **Auto-Fix rationale:** Reine Acceptance-/Test-Bullet-Insertion. Eindeutige Lösung (Bug-Symptom + Test-Pattern existiert). Mechanische Erweiterung — keine Architektur-Wahl. Inline-Doku-Anker für Bug-Drift-Schutz aus Batch-1 2.0.11/2.0.12 verstärkt sich.
- **Status:** ✅ APPLIED (2026-05-10) — Hauptplan §1.1 als Tabelle mit "Acceptance-Verifikator"-Spalte umstrukturiert (Bug #3 in #3a + #3b gesplittet). Spec 2 §10 Block-5-Acceptance erweitert um Resend-Btn-Sichtbarkeit + Cooldown-Trennung + Active→Pipeline-Übergang. Spec 2 §14.2 erweitert um UI-Test 8 (Frame-Capture), UI-Test 9 (Cooldown), UI-Test 10 (Cross-Bug-Verifikation) — als Tabelle mit Reverse-Pointer auf §1.1-Bug-Symptome. Spec 1 §10 Block-1: `predResendVisible` reflektiert NICHT `resendCooldown` als Acceptance-Punkt.

---

### Issue 3.0.10: Spec 1 §10 Block-1-Acceptance — Cross-Module-Cascade-Tests pro §15-Tabellen-Eintrag ergänzen
- **Category:** [INTEGRATION]
- **Severity:** Important
- **Source:** Sec5-Logic L-9 (→ `section5-logic.md` §L-9)
- **Description:** Spec 1 §15.1 listet 7 Module mit "Cross-Module-Observer: ja" + dokumentierten Cascades (PipelineDone → ResendMarkLastAudio + LivePromptChainNext, AudioFocus-Loss → Recording.Pause, Recording-Active+View-hidden → HOVER, Reprocess-Override → Language.Override, etc.). Spec 1 §10 Block-1-Acceptance hat 5 Punkte um `predResendVisible` und `recordButton`. **Keiner** prüft die Cross-Module-Cascade-Mechanik selbst.
- **Fix:** Spec 1 §10 Block-1-Acceptance erweitern um einen Bullet pro Cascade aus §15.1:
  - "PipelineModule.PipelineDone → ResendModule.MarkLastAudio: nach Pipeline-Done ist `state.resend.lastAudioExists = true` ohne weiteres User-Input."
  - "AudioModule.AudioFocusLoss → RecordingModule.Pause: bei `AudioFocusLoss` während Recording.Active wechselt `state.recording` zu `Paused`."
  - "ViewModeModule auf View-Hidden + Recording-Active → HOVER: korrekter ViewMode-Wechsel."
  - "PipelineModule auf Reprocess-Override → LanguageModule.Override: Sprache wird gesetzt."
  - (etc., 7 Cascades = 7 Acceptance-Punkte)
  - Spec 1 §14 (oder Block-1-Test-Eintrag): Unit-Test pro Cascade-Pfad mit Mock-Modules.
- **Auto-Fix rationale:** Reine Acceptance-Bullet-Erweiterung; Cascades sind in §15.1 bereits dokumentiert. Test-Pattern (Mock-Modules) ist Standard.
- **Status:** ✅ APPLIED (2026-05-10) — Spec 1 §10 Block-1-Acceptance um sieben Cross-Module-Cascade-Punkte erweitert (PipelineDone→ResendMarkLastAudio + LivePromptChainNext, AudioFocusLoss→Recording.Pause, Recording-Active+ImeViewHidden→ViewMode.HOVER, PipelineDone-in-HOVER→ViewMode.KEYBOARD, Reprocess-Override→Language.Override, OverlayCloseClicked-HOVER→PipelineCancel+Audio-Cleanup). Verifikation via Mock-Modules.

---

### Issue 3.0.11: Spec 1 §10 Block-2-Acceptance — MediaRecorder-Leak-Test (Service.onDestroy → release) ergänzen
- **Category:** [LOGIC]
- **Severity:** Important
- **Source:** Sec5-Logic L-7 (→ `section5-logic.md` §L-7)
- **Description:** Spec 1 §13.5 G6 markiert MediaRecorder-Leak als "Akzeptiert"; §10 Block-2-Acceptance hat Punkt 5 "Force-Stop der App", testet aber nur DB-Recovery, NICHT den `release()`-Pfad. Pfad A (Service.onDestroy normal) ist Code, der im Plan implementiert wird — testbar, aber ungetestet.
- **Fix:**
  - **Spec 1 §10 Block-2-Acceptance ergänzen:** "Service.onDestroy bei aktivem Recording: `recordingManager.release()` wird aufgerufen UND der MediaRecorder ist im released-State. Verifiziert via `MediaRecorder.release()`-Mock-Spy in Unit-Test (oder Robolectric)."
  - **§13.5 G6 erweitern:** Mitigation-Spalte zwei Pfade explizit:
    - Pfad A (testbar): `Service.onDestroy → cancelPipeline → release` mit Acceptance-Test in §10
    - Pfad B (nicht-testbar, Process-Kill): "akzeptiert, Android-Cleanup"
  - **Unit-Test** im Block-2-Test-Eintrag ergänzen
- **Auto-Fix rationale:** Reine Acceptance-/Test-Bullet-Erweiterung. Mock-Spy-Test-Pattern ist Standard. Eindeutige Lösung.
- **Status:** ✅ APPLIED (2026-05-10) — Spec 1 §10 Block-2-Acceptance um MediaRecorder-release-Pfad-Test ergänzt (Service.onDestroy → `orchestrator.dispatch(Action.PipelineAction.CancelPipeline)` → `recordingManager.release()` Mock-Spy). §13.5 G6-Mitigation auf zwei Pfade getrennt: Pfad A (testbar, Acceptance-Anker zu §10 Block-2) + Pfad B (nicht-testbar, Process-Kill — Android-Cleanup).

---

### Issue 3.0.12: Spec 2 §13.1 + §13.2 + §6 buttonViews-Map — `WIDGET_TOGGLE` nachpflegen (Phase-1-1.0.2-Followup)
- **Category:** [INTEGRATION]
- **Severity:** Important
- **Source:** Sec5-Logic L-8 (→ `section5-logic.md` §L-8)
- **Description:** Phase-1 1.0.2 hat `WIDGET_TOGGLE` zur Hauptplan-§3.3-LogicalButtonId-Liste hinzugefügt. Spec 2 §13.1 (Visibility-Audit, Z. 1817-1846) + §13.2 (Click-Listener-Audit, Z. 1853-1875) + §6 buttonViews-Map (Z. 382-391) sind aber nicht synchron nachgepflegt. Sec3-Logic L-9 hat Silent-Skip-Risiko bei fehlendem View-Mapping markiert.
- **Fix:**
  - **Spec 2 §13.1 ergänzen:** neue Zeile "WIDGET_TOGGLE | NEU (Spec 3 OPEN-2) | LayoutCatalog `WIDGET_TOGGLE`-Slot, Predicate `{ state.viewMode == ViewMode.KEYBOARD }`."
  - **Spec 2 §13.2 ergänzen:** "WIDGET_TOGGLE | NEU — kein heutiger Migration-Source. Slot-actionResolver = `Action.ViewModeAction.ToggleViewModeWidget`."
  - **Spec 2 §6 buttonViews-Map** explizit um `WIDGET_TOGGLE -> R.id.widget_toggle_btn` erweitern.
  - **Sec3-Logic L-9 Empfehlung umsetzen:** `?: return@forEach` durch `?: error("No view registered for ${slot.logicalId}")` ersetzen — silent miss schlägt zur Build-/Run-Time auf.
- **Auto-Fix rationale:** Mechanische Nachpflege eines APPLIED-Phase-1-Fixes. Kanonische Quelle ist Hauptplan §3.3-Liste post-1.0.2.
- **Status:** ✅ APPLIED (2026-05-10) — Spec 2 §6 buttonViews-Map um `LogicalButtonId.WIDGET_TOGGLE → R.id.widget_toggle_btn` erweitert. Spec 2 §13.1 + §13.2 mit WIDGET_TOGGLE-Zeilen ergänzt (Slot-actionResolver = `Action.ViewModeAction.ToggleViewModeWidget`, predicate `state.viewMode == ViewMode.KEYBOARD`). Silent-Skip im Render-Loop durch `error("No view registered for ${slot.logicalId} ...")` statt `return@forEach` ersetzt.

---

## 🟡 Needs Decision Issues

### Issue 3.1.1: Spec 3 — Module-Integration insgesamt (OverlayModule-Spec-Heimat + Reducer/EffectHandler)
- **Category:** [INTEGRATION] / [SOLID]
- **Severity:** Critical
- **Source:** Sec4-Structure S-3 + Sec5-Structure S-3 + Sec4-Structure S-2 (→ `section4-structure.md` §S-3, §S-2; `section5-structure.md` §S-3); verschärft Phase-1 1.1.2 (für Spec 3 spezifisch)
- **Description:** Spec 1 §15.1 listet OverlayModule (Achse 6) mit "Position-Persistierung + Onboarding-Status, Action-Klasse `Action.OverlayAction`, Reducer trivial, kein Cross-Module-Observer". Spec 3 enthält **null Treffer** auf "OverlayModule" — keine `Action.OverlayAction`-Sealed-Class-Definition, kein Reducer für OverlayState, kein EffectHandler (für SharedPreferences-Schreibung der Position), keine Spec-Heimat. Konsequenz für Implementer: Block 6 ist incomplete — entweder OverlayModule-Spec wandert nach Spec 3 (näher beim fachlichen Kontext) oder Spec 1 §15 wird um OverlayModule-Code erweitert. Beides möglich; eines muss passieren. Ergänzend: Spec 3 §5/§6/§7 zeigen direkte `_state.value.copy(...)`-Mutationen (siehe Phase-1 1.1.2) — die müssen auf dispatch + Reducer umgestellt werden. Sec4-Structure S-2 macht den OverlayModule-Bedarf für die spezifischen Action-Cascades konkret.
- **Options:**
  A) **OverlayModule-Spec-Heimat in Spec 3:** neue Sektion §4.x "OverlayModule (Spec 1 §15-Implementierung)" in Spec 3 ergänzen mit `Action.OverlayAction`-Sealed-Class (UpdatePosition, SetOnboardingPending, SetUserPrefers, SetHasPermission), `object OverlayModule : DictateModule<OverlayState, ...>` mit Reducer + EffectHandler (PersistOverlayPosition), Cross-Module-Observer (keine, autark). Spec 1 §15 bekommt "→ Detail siehe Spec 3 §4.x"-Pointer.
  B) **OverlayModule-Spec-Heimat in Spec 1 §15:** OverlayModule-Code in Spec 1 §15.1.x ausformulieren (analog RecordingModule-Beispielspec). Spec 3 enthält nur "OverlayModule wird in Spec 1 §15 implementiert"-Pointer.
  C) **Hybrid:** Spec 1 §15 enthält die kanonische Action-Sealed-Class + Reducer-Skelett; Spec 3 enthält den EffectHandler-Detail (weil SharedPreferences-Persistierung Spec-3-spezifisch ist).
- **Recommendation:** **Option A** — OverlayModule lebt fachlich näher bei Spec 3 (Position, Onboarding, UserPrefers, Permission sind Overlay-Subsystem-Konzepte). Spec 1 §15 bleibt der Modul-Inventar-Index. Diese Auflösung blockiert die Spec-3-Implementation in Block 6 — sollte gemeinsam mit Phase-1 1.1.2 entschieden werden.
- **Cluster:** Phase-1 1.1.2 (direkte `_state.value.copy(...)`), 3.1.2 (ViewMode-FSM-Eigentum)
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md) (🟡 — Architektur-Decision, Research-Step + User-Decision-Pass erforderlich)

---

### Issue 3.1.2: Spec 3 §7.1 ViewMode-FSM-Eigentum + Cross-Module-Trigger für `computeViewMode`
- **Category:** [SOLID] / [LOGIC]
- **Severity:** Critical
- **Source:** Sec4-Structure S-8 + Sec4-Logic L-2 + Sec4-Logic L-10 (→ `section4-structure.md` §S-8; `section4-logic.md` §L-2, §L-10)
- **Description:** Spec 3 §7.1 zeigt Triangle-FSM `computeViewMode(imeViewVisible, userToggledWidget, pipelineActive) → ViewMode`. Spec 1 §15.1 sagt, ViewMode-FSM-Logik lebt im **ViewModeModule** (ehemals ViewModeFsm). Doppel-Eigentum-Risiko. Zusätzlich:
  - **L-2 / L-10 Cross-Module-Trigger fehlt:** `computeViewMode` reagiert nur auf View-Visibility, nicht auf Pipeline-/Recording-State-Wechsel. Pipeline-Done in HOVER schaltet nicht zurück zu KEYBOARD → "Geist-Widget" bleibt sichtbar mit Send disabled. T7 (HOVER → KEYBOARD nach Pipeline-Done) fehlt in §7.3.
  - **L-2 Race-Window:** Pipeline-State wechselt synchron im selben Tick wie View-Hidden → falscher Snapshot → falscher ViewMode.
  - **§7.3 T1-T6 zeigt Methoden-Calls** (`notifyImeViewVisibilityChanged`, `notifyImeViewHidden`) — Pre-Phase-1-Apply-Vokabular (Issue 2.0.4 hat das LocalBinder-Wrapper entfernt; IME ruft `pipeline?.dispatch(Action.ViewModeAction.OnImeViewShown/Hidden)` direkt).
- **Options:**
  A) **§7.1 dokumentarisch + ViewModeModule kanonisch:** Spec 3 §7.1 wird Header-Block "Die ViewMode-FSM ist in Spec 1 §15-ViewModeModule kanonisch implementiert. Dieser Abschnitt zeigt die Transition-Logik aus Sicht von Spec 3, als Referenz für den Implementierer." Code-Skizzen in §7.3 dokumentieren Action-Sequenzen am Module-Bus, kein eigener `computeViewMode`-Code. T7 (HOVER → KEYBOARD nach Pipeline-Done) als Cross-Module-Cascade-Trigger im OverlayModule.onCrossModuleStateChange ergänzen. Methoden-Calls auf `pipeline?.dispatch(...)` umstellen (gemeinsam mit 3.0.5).
  B) **§7.1 als kanonische FSM:** ViewModeModule ist nur "Container", `computeViewMode`-Code lebt in Spec 3 §7.1 — Methoden-Aufrufe vom Modul-Reducer. Doppel-Eigentum-Risiko bleibt; weniger sauber.
  C) **Status quo + Doku-Trennung:** beide zeigen FSM, aber explizite SSoT-Note "Spec 1 §15 ist Code, Spec 3 §7.1 ist Dokumentation". Verlagert Architektur-Entscheidung auf den Implementer.
- **Recommendation:** **Option A** — Spec 1 §15 ist kanonisch, Spec 3 §7.1 wird zu Architektur-Dokumentation. T7 als neuer Transition-Pfad in §7.3 + Cross-Module-Cascade aus OverlayModule. Hängt mit 3.1.1 zusammen (OverlayModule muss Heimat haben, um den Cross-Module-Cascade zu emittieren).
- **Cluster:** 3.1.1, Phase-1 1.1.2, Batch-1 2.1.4 (Reentrancy-Vertrag)
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md) (🟡 — Architektur-Decision, Research-Step + User-Decision-Pass erforderlich)

---

### Issue 3.1.3: Spec 3 — Permission-Lifecycle als State-Achse + HOVER-ohne-Permission-UI-Pfad
- **Category:** [LOGIC]
- **Severity:** Critical
- **Source:** Sec4-Logic L-1 + Sec4-Logic L-16 + Sec4-Structure S-12 + Sec4-Logic L-17 (→ `section4-logic.md` §L-1, §L-16, §L-17; `section4-structure.md` §S-12)
- **Description:** Spec 3 §11.6 Tabelle markiert "Permission revoked während Overlay sichtbar" als "akzeptabler Edge-Case". L-1 zeigt: für **HOVER-Modus** (User außerhalb Tastatur) ist das nicht akzeptabel — User sieht gar nichts, kein Indikator, Notification spiegelt Recording-State nicht Overlay-Sichtbarkeit. L-16 zeigt zusätzlich: §5.4 (`shouldShowOnboarding=false` nach `markPermanentlyDenied`) widerspricht §5.6 ("Klick auf disabled WIDGET-Toggle löst InfoBar erneut aus"). Kein UI-Pfad zurück zur Permission. L-17 (Performance, Nice-to-have): `Settings.canDrawOverlays` IPC pro Render → Caching nötig. S-12: ein `OverlayPermissionObserver` (Hardware-Spiegel analog `audioFocusGranted`) wäre F-11-konform.
- **Options:**
  A) **Permission als State-Achse `state.overlay.hasPermission: Boolean`:** OverlayPermissionObserver (analog `audioFocusGranted`-Spiegel) re-checkt `Settings.canDrawOverlays` bei `onConfigurationChanged` / `onWindowFocusChanged` und dispatcht `Action.OverlayAction.SetHasPermission(...)`. Render liest aus State → kein IPC im Render-Pfad → L-17 erledigt. HOVER-Auto-Trigger ohne Permission setzt `state.overlay.permissionMissingForHover = true` → Notification-Action "Settings öffnen" wird ergänzt (§5.6 + Spec 1 §7.5-Notification-Action). §5.4 vs §5.6 vereinheitlicht: Klick auf WIDGET-Toggle triggert immer InfoBar (auch nach Denied) — manueller Re-Recovery-Pfad.
  B) **Notification-only-Fallback ohne State-Achse:** §5.6 ergänzen "HOVER ohne Permission → Notification ist primary"; §5.4/§5.6 vereinheitlichen (Variante "User-friendly", InfoBar bei explizitem Klick); Caching im PermissionGate als impl-Detail.
  C) **Status quo + Doku-Klarstellung:** "akzeptabler Edge-Case auch in HOVER" (User-Bug-Risiko bleibt).
- **Recommendation:** **Option A** — F-11-konform (Hardware-Status als State-Achse), eliminiert Performance-Issue + Recovery-Pfad-Lücke + HOVER-Edge-Case in einer kohärenten Erweiterung. Architektur-Entscheidung wegen neuer State-Achse + neuer Notification-Action + neuem Observer-Pattern.
- **Cluster:** 3.1.1 (OverlayModule-Heimat), 3.1.2 (T7 / Cross-Module-Trigger)
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md) (🟡 — Architektur-Decision, Research-Step + User-Decision-Pass erforderlich)

---

### Issue 3.1.4: IME-Service-Death während aktivem HOVER → Overlay-Window leakt; Overlay-Owner-Architektur
- **Category:** [LOGIC] / [SOLID]
- **Severity:** Critical
- **Source:** Sec4-Logic L-4 + Sec4-Logic L-18 (→ `section4-logic.md` §L-4, §L-18)
- **Description:** Overlay lebt im IME-Service (KeyboardLayoutManager hält OverlayBackend-Instanz). Wenn IME-Service durch Tastatur-Wechsel (z.B. zu Gboard) stirbt → KeyboardLayoutManager mit-zerstört → `WindowManager.removeView` nie gerufen → Overlay-Window leakt, OnClickListener referenzieren toten Callback. Spec 3 §11.6 Tabelle deckt nur PipelineService-onDestroy ab, nicht IME-Service-Death. L-18 verschärft: Process-Death-Recovery hat `state.viewMode = HOVER`, aber kein IME-Service aktiv → kein Overlay rendert obwohl State es vorsieht.
- **Options:**
  A) **IME-Service-onDestroy ruft `keyboardLayoutManager.detachAllBackends()` direkt:** Overlay wird vor Process-Death sauber abgerissen. Recovery-Pfad nach Process-Restart muss `state.viewMode = HOVER → KEYBOARD` resetten (Notification ist primary). Acceptance §10 ergänzen.
  B) **Overlay-Rendering in eigenen Service migrieren:** Overlay lebt unter PipelineService, mit eigener Lifecycle. HOVER kann auch ohne IME-Service leben → Process-Restart-Pfad bleibt funktional. Substantieller Architektur-Refactor (neue Service-Komponente).
  C) **Hybrid:** Option A (sofort) + Option B (Phase 2, falls Use-Case kommt).
- **Recommendation:** **Option C** — Option A als Sofort-Fix (kleiner Footprint, eliminiert Window-Leak), Option B als optionale Phase-2-Erweiterung dokumentiert. Acceptance §10 erweitern: "Tastatur-Wechsel zur Gboard mit aktivem HOVER" + "Process-Restart während HOVER: Notification ist primary, Overlay erst beim nächsten IME-Show".
- **Cluster:** 3.1.1 (OverlayModule-Heimat), Batch-1 2.1.9 (IME-Service-Death + InputConnection)
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md) (🟡 — Architektur-Decision, Research-Step + User-Decision-Pass erforderlich)

---

### Issue 3.1.5: Spec 3 — Drag-vs-Backend-Render-Race + Drag-Persist-bei-Detach + Drag-Threshold-vs-touchSlop
- **Category:** [LOGIC]
- **Severity:** Important
- **Source:** Sec4-Logic L-3 + Sec4-Logic L-7 + Sec4-Logic L-5 (→ `section4-logic.md` §L-3, §L-5, §L-7)
- **Description:** Drei verschränkte Drag-Lifecycle-Probleme:
  - **L-3 Race:** während ACTION_MOVE läuft, mutiert ein State-Emit (z.B. Amplitude-Update bei Recording, ~30 Hz) → `applyPosition` setzt `params.x/y` zurück → Widget zittert/springt während Drag.
  - **L-7 Detach-mid-Drag:** Cross-Module-Cascade dispatcht `viewMode = KEYBOARD` während aktivem Drag → `OverlayBackend.detach()` → DragHandler hatte ACTION_MOVE konsumiert, ACTION_UP geht ins Leere → `onPositionPersist` wird nicht gerufen → Drag-Position verloren.
  - **L-5 Drag-Threshold:** §4.6 hardcoded `dragThresholdPx = 8 * density` ignoriert `scaledTouchSlop` (Accessibility-Mode kann auf 16dp+ steigen) → bei Accessibility-Mode greift Drag, bevor Button seinen Touch-Slop überschritten hat → doppelter Verlust (weder Drag noch Click).
- **Options:**
  A) **Drag-Hoheit signalisieren + Persist-bei-Detach + threshold-Abstimmung:**
    - L-3: `OverlayDragHandler.isDragging(): Boolean` + `OverlayBackend.applyPosition` early-return wenn `dragHandler?.isDragging() == true`.
    - L-7: `OverlayDragHandler.detach()` MUSS bei `dragging=true` den `onPositionPersist`-Callback mit aktuellen `params.x/y` aufrufen, BEVOR Listener entfernt wird.
    - L-5: `dragThresholdPx = max(8 * density, scaledTouchSlop * 1.5)` — Drag-Threshold liegt über System-touch-slop.
  B) **Suspend Position-Apply während Drag:** `private var positionApplyEnabled = true; dragHandler.attach { _, dragging -> positionApplyEnabled = !dragging }`. Andernfalls wie A.
- **Recommendation:** **Option A** — saubere Trennung der drei Lifecycle-Probleme mit minimal-invasiven Änderungen am DragHandler-Interface. Robolectric-Tests pro Pfad.
- **Cluster:** Standalone — keine direkte Phase-1 / Batch-1-Verknüpfung
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md) (🟡 — Architektur-Decision, Research-Step + User-Decision-Pass erforderlich)

---

### Issue 3.1.6: Spec 3 §4.7 + §11.5 — Position-Mapping vor Layout-Pass + Multi-Display/Foldable-Persistierung
- **Category:** [LOGIC]
- **Severity:** Important
- **Source:** Sec4-Logic L-6 + Sec4-Logic L-13 (→ `section4-logic.md` §L-6, §L-13)
- **Description:**
  - **L-6:** `applyPosition` läuft vor erstem Layout-Pass mit `view.width = 0`, `measuredWidth = 0` → `params.x = screenW` → Widget rechts vom Display. `view.post`-Callback fixt das später (16ms Frame mit kaputter Position). Spec hat Idempotenz-Check, aber er greift nicht für unmessbaren View.
  - **L-13:** `displaySize()` liest aktuelle Display-Metrik. Bei Multi-Display (Z Fold), Foldable-Fold/Unfold, Multi-Window-Mode landet die persistierte Position (Portrait/Landscape) auf inkompatiblem Aspect-Ratio → Widget off-screen oder über kritischen UI-Elementen.
- **Options:**
  A) **`applyPosition` early-return + Aspect-Bucket-Persist + Off-Screen-Recovery:**
    - L-6: `applyPosition` early-return wenn `view.width == 0 && view.measuredWidth == 0` (oder `OverlayPositionMapper.normalizedToPixels` returned `null`-Sentinel).
    - L-13: Persist-Schlüssel `Pref.OverlayPosition_${aspectBucket}_${orientation}_X/Y` (Aspect-Bucket: <1.5 = compact, 1.5-1.8 = standard, >1.8 = wide). Off-Screen-Recovery: bei Unbrauchbarkeit Reset auf Default Top-End.
  B) **Initial-LayoutParams-Factory mit gravity-basiertem Anker:** Top-End-Default-Frame als initial gerendert, erst nach Layout-Pass auf TOP|START umgestellt. Adressiert L-6 ohne early-return; L-13 separat.
- **Recommendation:** **Option A** — sauber pro Pfad; L-6 verhindert Frame-Flicker, L-13 macht Position cross-display robust. Architektur-Entscheidung wegen Pref-Schema-Erweiterung (mehr Keys).
- **Cluster:** Standalone
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md) (🟡 — Architektur-Decision, Research-Step + User-Decision-Pass erforderlich)

---

### Issue 3.1.7: Spec 3 §6.2 + §7.3 T2 — `closeOverlay` Cascade-Vertrag + `userPrefersWidget`-Reset-Race + Audio-File-Cleanup
- **Category:** [LOGIC] / [SOLID]
- **Severity:** Critical
- **Source:** Sec4-Structure S-6 + Sec4-Logic L-8 + Sec4-Logic L-20 (→ `section4-structure.md` §S-6; `section4-logic.md` §L-8, §L-20)
- **Description:** §6.2 closeOverlay() ruft inline `cancelPipeline()` + `_state.value.copy(viewMode = KEYBOARD)` — bricht F-11 Cross-Module-Cascade-Modus 2. §7.3 T2 setzt `userPrefersWidget = false`; in Race mit `notifyImeViewVisibilityChanged` kann HOVER unerwartet zurückkommen, statt KEYBOARD-mit-SmallMode (User intendierte explizit Schließen). §6.2 Audio-File / DB-Status / cancelSession ist nicht spezifiziert.
- **Options:**
  A) **§6.2 als Cascade umschreiben + Suppress-Bit + Cleanup-Vertrag:**
    - §6.2: `Action.OverlayAction.CloseClicked` → OverlayModule.reduce + `cascadeActions = [Action.PipelineAction.Cancel, Action.ViewModeAction.SetMode(KEYBOARD)]`. PipelineModule.reduce(Cancel) emittiert `Effect.CancelActiveSession` → ruft `pipelineSessionRepo.cancelSession(activeSessionId)` (Spec 1 §6.4) → löscht Audio-File + setzt DB-Status auf `cancelled`.
    - §7.3 T2: `state.copy(viewMode = KEYBOARD, userPrefersWidget = false, suppressAutoOverlayUntilNextSession = true)`. Bit wird beim nächsten `OnImeViewShown` (T5) zurückgesetzt.
    - `computeViewMode` berücksichtigt Suppress-Bit.
    - §10 Acceptance: "Schließen in WIDGET unmittelbar gefolgt von App-Wechsel: HOVER greift NICHT, Pipeline läuft via Notification weiter." + "Schließen in HOVER während aktivem Recording: Audio-File gelöscht, DB-Status `cancelled`, kein Notification-Eintrag verbleibt."
  B) **Status quo + Doku-Klärung:** §6.2 explizit auf Spec 1 §6 (`cancelSession`) referenzieren, ohne Cascade-Umstellung. §7.3 T2 Race als bekannter Edge-Case dokumentiert.
- **Recommendation:** **Option A** — sauber F-11-konform, eliminiert Race + Disk-Leak in einer kohärenten Erweiterung. Architektur-Entscheidung wegen neuer State-Achse (Suppress-Bit) + Cascade-Pattern-Festlegung.
- **Cluster:** 3.1.1 (OverlayModule), 3.1.2 (ViewMode-FSM), Phase-1 1.1.2 (direkte copy())
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md) (🟡 — Architektur-Decision, Research-Step + User-Decision-Pass erforderlich)

---

### Issue 3.1.8: Spec 3 §11.9 — WIDGET-autark-Versprechen vs. T4-HOVER-disabled-Record
- **Category:** [LOGIC]
- **Severity:** Important
- **Source:** Sec4-Logic L-11 (→ `section4-logic.md` §L-11)
- **Description:** §11.9 sagt "User kann Recording direkt aus dem Widget heraus starten — auch ohne aktive Pipeline". §7.3 T4 (View-Hidden + WIDGET) → HOVER mit `userPrefersWidget=true`; in HOVER ist Record disabled. Use-Case "WIDGET autark als floating-recording-button" funktioniert nur, wenn Tastatur sichtbar bleibt — widerspricht "autark"-Versprechen.
- **Options:**
  A) **§11.9 klarstellen:** "WIDGET autark" gilt nur im `viewMode = WIDGET`-Zustand (= IME sichtbar + User toggled). HOVER ist disabled-Modus für Record/Send.
  B) **STANDALONE_OVERLAY-Modus einführen:** separater ViewMode, Record auch in HOVER aktiv. Phase-2-Feature.
  C) **Status quo + Acceptance §10:** "WIDGET → HOVER beim App-Wechsel: Record-Button im HOVER ist disabled" — explizit dokumentieren.
- **Recommendation:** **Option A + C** — Doku-Klärung + Acceptance-Anker. Option B als Phase-2-Backlog.
- **Cluster:** Standalone
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md) (🟡 — Architektur-Decision, Research-Step + User-Decision-Pass erforderlich)

---

### Issue 3.1.9: Spec 3 §13.5 GAP-5 — `userPrefersWidget`-Persistierung über HOVER-Close-Pfad ohne Acceptance-Test
- **Category:** [LOGIC] / [ROBUSTNESS]
- **Severity:** Nice-to-have
- **Source:** Sec5-Logic L-14 (→ `section5-logic.md` §L-14)
- **Description:** GAP-5 dokumentiert: HOVER-Close mit `userPrefersWidget=true` (durch frühere User-Wahl) → Re-Show kommt im WIDGET-Modus zurück. "Bewusste Eigenschaft", aber §10 hat keinen spezifischen Test. Implementer könnte defensiv `userPrefersWidget=false` setzen → Verhalten-Bug, durch §10-Acceptance nicht gefangen.
- **Options:**
  A) **§10 + §14.2 ergänzen:** "HOVER-Close mit `userPrefersWidget=true`: nach Tastatur-Re-Open kommt WIDGET-Modus zurück, NICHT KEYBOARD-Modus. Verifiziert via §14.2-Test (neu)." + Test-Case `widgetPersistenceAfterHoverClose`.
  B) **GAP-5 anders auflösen:** statt "bewusste Eigenschaft" → "User-Wahl wird auf Default-WIDGET zurückgesetzt nach Auto-HOVER-Schließen". Einfachere mentale Modellierung; aber widerspricht §13.5-Aussage.
- **Recommendation:** **Option A** — bekräftigt die "bewusste Eigenschaft" mit Test. Architektur-Entscheidung wegen Verhaltens-Vertrags-Festlegung. Hängt mit 3.1.7 zusammen (T2-Suppress-Bit).
- **Cluster:** 3.1.7
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md) (🟡 — Architektur-Decision, Research-Step + User-Decision-Pass erforderlich)

---

### Issue 3.1.10: Spec 3 §4.2 `applySlots` — OnClickListener-Pattern (Closure vs. stateRef)
- **Category:** [CLEAN] / [LOGIC]
- **Severity:** Important
- **Source:** Sec4-Logic L-15 (→ `section4-logic.md` §L-15)
- **Description:** Spec 3 §4.2 Z. 345-351 setzt `view.setOnClickListener { onAction?.invoke(slot.actionResolver(state)) }` pro Render — Closure captured `state`. ImeViewBackend (Spec 2) hat laut Kommentar das **andere** Pattern: einmaliger Listener, State-Lookup zur Click-Zeit. Inkonsistenz; bei schnellen State-Wechseln kann Spec-3-Pfad auf altem State agieren.
- **Options:**
  A) **Spec 3 auf Spec-2-Pattern umstellen:** `view.setOnClickListener { onAction?.invoke(slot.actionResolver(stateRef ?: return@setOnClickListener)) }`, einmalig in `inflateAndAttach`. Closure liest `stateRef` (existiert in §4.2 Z. 311). Konsistenz mit Spec 2; Performance-Gewinn (kein Listener-Set pro Render).
  B) **Status quo + Doku:** Drag-Routing-Konflikt-Begründung (§4.2 Kommentar Z. 343-344) ausführen. Hält dem Test "Drag wird auf Root-View gefangen, nicht auf Buttons" allerdings nicht stand.
- **Recommendation:** **Option A** — Konsistenz mit Spec 2, eliminiert Single-Frame-Race, bessere Performance. Architektur-Entscheidung wegen Pattern-Konsistenz-Festlegung.
- **Cluster:** Standalone
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md) (🟡 — Architektur-Decision, Research-Step + User-Decision-Pass erforderlich)

---

### Issue 3.1.11: Spec 3 §4.3 + §4.7 — Anchor-Wechsel TOP|END → TOP|START + ViewWidth/Height-Helper
- **Category:** [INTEGRATION] / [DRY]
- **Severity:** Important
- **Source:** Sec4-Structure S-9 + Sec4-Structure S-10 (→ `section4-structure.md` §S-9, §S-10)
- **Description:**
  - **S-9:** Factory setzt initial Anchor TOP|END, Backend.applyPosition switcht zur Laufzeit auf TOP|START. Single-Owner-Verletzung; Initial-State-Frame mit (16dp, 80dp) bevor erstes applyPosition läuft. Kollidiert mit L-6 (3.1.6).
  - **S-10:** ViewWidth/Height-Lookup-Logik (`view.width.takeIf { it > 0 } ?: view.measuredWidth`) dupliziert in `normalizedToPixels` + `pixelsToNormalized`.
- **Options:**
  A) **S-9 + S-10 zusammen:** Factory direkt mit TOP|START, Initial-Pixel-Werte aus normalisiertem Default-State berechnet (kombiniert mit 3.1.6-Option-A-`applyPosition`-early-return). `view.effectiveSize()`-Helper extrahieren.
  B) **S-10 trivial-Refactor:** Helper extrahieren (kostenlos). S-9 separat lassen / via 3.1.6 lösen.
- **Recommendation:** **Option A** — kohärente Lösung mit 3.1.6. S-10 ist ein triviales Sub-Element.
- **Cluster:** 3.1.6
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md) (🟡 — Architektur-Decision, Research-Step + User-Decision-Pass erforderlich)

---

### Issue 3.1.12: Spec 1 §15 + §13 — Cross-Module-Coupling-Matrix
- **Category:** [SOLID]
- **Severity:** Nice-to-have
- **Source:** Sec5-Structure S-10 (→ `section5-structure.md` §S-10)
- **Description:** §15.1 listet Module mit "Cross-Module-Observer? ja/nein" Boolean. SOLID-Audit (§13.3.13 / §15.6) hängt am "ein Modul = eine Achse"-Argument. Cross-Module-Read-Coupling (welche Sub-State-Pfade liest welches Modul) und Cross-Module-Cascade-Coupling (welche Action-Klassen emittiert welches Modul) sind nicht systematisch ausgewiesen → wächst still bei Plan-Erweiterungen.
- **Options:**
  A) **Cross-Module-Coupling-Matrix in §15.1.x:** 13×13 Matrix, Read-Liste + Cascade-Action-Klasse-Liste pro Modul. Compile-Zeit-Helfer `crossReads(global): CrossReadSet`. SRP/OCP-Audit basiert dann strukturell auf Matrix.
  B) **Status quo + Konvention:** Pro-Modul-Doku-Kommentar "liest nur diese Achsen". Niedrigere Disziplin.
- **Recommendation:** **Option A** — strukturell verifizierbar, eliminiert SRP/OCP-Audit-Argument-Schwäche. Architektur-Entscheidung wegen neuer Modul-API + neuem Audit-Format.
- **Cluster:** Phase-1 1.1.5 (LayoutModule SRP), Batch-1 2.1.13 (KSM-Aufspaltung)
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md) (🟡 — Architektur-Decision, Research-Step + User-Decision-Pass erforderlich)

---

### Issue 3.1.13: Spec 2 §13.4 — Cross-Spec-DRY-Tabelle (Symbol / Definition / Konsumenten) ergänzen
- **Category:** [DRY]
- **Severity:** Important
- **Source:** Sec5-Structure S-9 + Sec5-Logic L-3 (→ `section5-structure.md` §S-9; `section5-logic.md` §L-3)
- **Description:** §13.4 audited DRY pro Spec isoliert. Cross-Spec-Resolver-/Predicate-DRY (`state.recording.isActiveOrPaused`-Extension, `predResendVisible`/`predTrashVisible == predPauseVisible`, `OverlayPositionMapper`-Konversion vs. Spec-1-Persistenz-Pfad) ist nicht erfasst. L-3 zeigt: §13.4 behauptet "keine Duplikation" — `predTrashVisible` und `predPauseVisible` sind aber wörtlich identisch.
- **Options:**
  A) **Cross-Spec-DRY-Tabelle in §13.4 (Spec 2) und §13.3 (Spec 3):** Spalten Symbol / Definitions-Site / Konsumenten. Zentrale "Resolver/Predicate-Library" in Spec 1 §3 oder neue `state/Predicates.kt`.
  B) **§13.4 erweitern um dritten Eintrag** "Identische Predicates (predTrash/predPause)" — entweder konsolidiert in `predRecordingControlsVisible`, oder explizit doku-mentiert.
  C) **`predIsIdle`-Helper extrahieren** (Spec 2 §8.5 + Spec 3 §3.1) — siehe Sec4-Structure S-7.
- **Recommendation:** **Option A + B + C kombiniert** — A für Tabellen-Format-Erweiterung, B für die spezifische Predicate-Body-DRY-Lücke, C für den konkreten `predIsIdle`-Helper. Architektur-Entscheidung wegen Audit-Format-Erweiterung + zentraler Predicate-Library.
- **Cluster:** Standalone
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md) (🟡 — Architektur-Decision, Research-Step + User-Decision-Pass erforderlich)

---

### Issue 3.1.14: Hauptplan §4 + §6 — Block-1-Split (1a/1b) + Risiko-Eintrag (Phase-1-1.1.6-Verstärkung)
- **Category:** [INTEGRATION]
- **Severity:** Critical
- **Source:** Sec5-Structure S-7 + Sec5-Logic L-5 (→ `section5-structure.md` §S-7; `section5-logic.md` §L-5); verstärkt Phase-1 1.1.6 + Batch-1 2.1.X (im Cluster)
- **Description:** Phase-1 1.1.6 hat Block-1-Split-Empfehlung (Option A: 1a Quick-Win + 1b Module-Aufbau nach Block 2). Status: ⬜ PENDING (User-Decision offen). Sec5-Structure S-7 + Sec5-Logic L-5 verstärken: Hauptplan §4 sagt "Block 1 muss vor allem"; F-11-Block-1-Inhalt braucht aber Block-2-Container → "vor allem"-Garantie gebrochen. §6 Risiken erfasst diese Race nicht. Spec 1 §10 Block-1-Acceptance verifiziert nur Verhaltens-Konsolidierung, nicht 13-Module-Inventar / Pref-Mirror-Durchschlag / Action-KClass-Routing.
- **Options:** (identisch mit Phase-1 1.1.6, nun verstärkt)
  A) **Block-1-Split 1a + 1b (Phase-1-Empfehlung):** Reihenfolge 1a → 2 → 1b → 3 → 4 → 5 → 6. Plus weitere Granularität 1a-1e (siehe Batch-1-2.1.X-Cluster).
  B) **Block-1-Beschreibung umformulieren ohne Split:** Komplexität → groß; "vor-allem"-Garantie streichen.
  C) **Block 1 nach Block 2:** Block 2 wird Block 1, Modul-Aufbau Block 2.
- **Recommendation:** **Option A** (verstärkt Phase-1-Empfehlung) — zusätzlich:
  - Hauptplan §6 Risiken-Eintrag "Block-1-Quick-Wins ↔ Block-1b-Module-Aufbau überlappen ohne Split → Race-Condition"
  - Spec 1 §10 Block-1-Acceptance pro 1a/1b granular: 13-Module-Inventar-Check, Pref-Mirror-Durchschlag-Check, Action-KClass-Routing-Test
  - Spec 1 §11.2.2 in 1a/1b umstrukturieren mit "kompilier-grün vor nächstem Sub-Block"-Vertrag
- **Cluster:** Phase-1 1.1.6 (kanonische Quelle), Batch-1 2.1.X-Cluster
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md) (🟡 — Architektur-Decision, Research-Step + User-Decision-Pass erforderlich)

---

### Issue 3.1.15: Hauptplan + Specs — Plan-Body-PENDING-Marker für offene 🟡 Issues + §13 Out-of-Scope-Sektion
- **Category:** [INTEGRATION] / [LOGIC]
- **Severity:** Important
- **Source:** Sec5-Logic L-13 + Sec5-Logic L-12 + Sec5-Logic L-11 (→ `section5-logic.md` §L-13, §L-12, §L-11)
- **Description:**
  - **L-13:** 🟡 PENDING-Issues (Phase-1, Batch-1, Batch-2) sind nur in `plan-review/`-Files; Plan-Body hat keine Marker → Implementer übersieht offene Decisions (z.B. Sec3-Logic L-1, L-3, L-7 alle Critical, im Plan unmarkiert).
  - **L-12:** §13 testet "was passiert", nicht "was bewusst NICHT passiert". Promptbar / EditBarController / Step-Rows / InfoBarController sind out-of-scope ohne expliziten Marker.
  - **L-11:** Iter-Log §9 reflektiert F-1 bis F-11, aber nicht Phase-1+2-Apply-Pässe (~18 weitere Modifikationen).
- **Options:**
  A) **Plan-Body-PENDING-Marker-Pattern:** `> [!NOTE] PENDING: siehe Issue X.Y.Z in plan-review/validated-findings-*.md` an konkreten Code-Snippet-Stellen. Plus: jede Spec §13.X (oder neue §13.6) Tabelle "Open Plan-Review-Issues" mit allen 🟡 PENDING. Plus: Hauptplan §7 (Open Questions) erweitern. Plus: §13.X "Bewusst Out-of-Scope"-Sektion pro Spec. Plus: Iter-Log §9 ergänzen mit Apply-Pass-Einträgen.
  B) **Status quo + Iter-Log-Eintrag:** nur §9 ergänzen mit Phase-1+2-Apply-Pässen. Marker im Body verzichten.
  C) **Vollständiger Plan-Body-Refactor:** alle Markierungen + Out-of-Scope + Iter-Log + Cross-Reference-Linkage. Höchster Footprint.
- **Recommendation:** **Option A** — pragmatischer Mittelweg, eliminiert die wichtigsten Lücken (PENDING-Markers + Out-of-Scope + Iter-Log) ohne Vollumfang-Refactor. Architektur-Entscheidung wegen neuem Plan-Pattern.
- **Cluster:** Standalone (Plan-Hygiene-Maßnahme)
- **User decision:** ✅ APPLIED (User-chose Recommendation)
- **Status:** ✅ APPLIED (User-Decision Recommendation / Research-Resolved — siehe research-findings.md) (🟡 — Architektur-Decision, Research-Step + User-Decision-Pass erforderlich)

---

## ❌ Eliminated Issues

| Original Issue | Source | Reason for Elimination |
|----------------|--------|------------------------|
| Sec4-Structure S-1 (OverlayState-Sub-Klasse ignoriert) | section4-structure.md §S-1 | **Verstärkung Phase-1 1.0.6** — Spec 3 ist Subset der State-Pfad-Drift-Mappings; in 3.0.4 als "Spec-3-Followup" mechanisch erfasst. |
| Sec4-Structure S-2 (direkte `_state.value.copy(...)`) | section4-structure.md §S-2 | **Verstärkung Phase-1 1.1.2** — Section-spezifische Ausprägung. Einbettung in 3.1.1 (OverlayModule-Heimat) + 3.1.2 (ViewMode-FSM) + 3.1.7 (closeOverlay-Cascade) als architektonische Auflösung. |
| Sec4-Structure S-7 (Visibility-Predicate Idle-Check duplziert Spec 2 ↔ Spec 3) | section4-structure.md §S-7 | **Konsolidiert in 3.1.13** (Cross-Spec-DRY-Tabelle + `predIsIdle`-Helper). |
| Sec4-Structure S-11 (Permission-InfoBar als Spec-2-Erweiterung) | section4-structure.md §S-11 | **Pragmatisch: §5.3-Header-Note** — wird im Apply-Pass mitgenommen, kein eigenständiger Issue. Reciprocal-Anchor in Spec 2 §6 / §9.3. |
| Sec4-Structure S-13 (`RenderBackend`-Interface-Bezug zu Spec 2 §4) | section4-structure.md §S-13 | **Doku-Lücke, Nice-to-have** — wird beim EN-Translation-Pass / Doc-Format-Pass automatisch aufgegriffen. Kein dedizierter Issue. |
| Sec4-Logic L-9 (direkte `_state.value.copy()` Cross-Module-Cascade-Folge) | section4-logic.md §L-9 | **Verstärkung Phase-1 1.1.2** + dedup in 3.1.1 / 3.1.7. Logik-Konsequenz ist im architektonischen Auflösungs-Issue erfasst. |
| Sec4-Logic L-12 (System-Theme-Wechsel triggert kein Re-Inflate) | section4-logic.md §L-12 | **Nice-to-have** — Theme-Wechsel-Edge-Case im laufenden Overlay ist seltener als HOVER-Pfade; `ComponentCallbacks.onConfigurationChanged`-Pattern ist Standard, kann beim Apply als kleiner Code-Hinweis ergänzt werden. Kein eigener PENDING-Issue. |
| Sec4-Logic L-14 (Action-Hierarchie inkonsistent) | section4-logic.md §L-14 | **Verstärkung Phase-1 1.0.5** — in 3.0.5 mechanisch erfasst. GAP-2 wird im Apply automatisch erweitert. |
| Sec4-Logic L-19 (DragHandler silent-drop Logging) | section4-logic.md §L-19 | **Trivialer Code-Fix** — Debug-Log oder `disposed`-Flag, gehört zur Implementation-Phase. Kein Plan-Issue. |
| Sec5-Structure S-1 (§13.3-SOLID-Audit Pre-F-11-Vokabular) | section5-structure.md §S-1 | **Konsolidiert in 3.0.6** (PipelineActionRouter / G6 / §13.3.4/9-Stubs Cleanup). |
| Sec5-Structure S-4 (§13.1-SSOT-Behauptung Spec 3 + direkte copy()) | section5-structure.md §S-4 | **Verstärkung Phase-1 1.1.2** — in 3.1.1 / 3.1.7 architektonisch gelöst. §13.1-Audit-Tabellen-Korrektur fließt mit Apply automatisch ein. |
| Sec5-Logic L-6 (§13.4-Cross-Spec-Konsistenz Spec 3) | section5-logic.md §L-6 | **Verstärkung Phase-1 1.1.2** — Audit-Aussage wird mit Apply von 3.1.1 / 3.1.7 automatisch konsistent. |
| Sec3-Structure S-2 / Spec-2-LayoutModule-Bezug (im Sec4-Structure Quick-Recap erwähnt) | section4-structure.md Quick-Recap | **Verstärkung Batch-1 2.1.15** (Spec-2-LayoutModule-Beziehung) — bereits dort erfasst. |

(Note: Sec5-Logic L-10 — §2.2-Erfolgskriterium operationalisieren — wird durch Plan-Body-PENDING-Marker (3.1.15) und durch Phase-4-Integration-Test natural erfasst; kein eigener Issue.)

---

## Phase-1 + Batch-1 → Batch-2 Verstärkungs-Tabelle

| Phase-1 / Batch-1-Issue | Status nach Batch 2 | Verstärkungen aus Batch 2 |
|---|---|---|
| **Phase-1 1.0.1** Hauptplan §3.2-Diagramm | APPLIED — verifizieren! | Sec5-Structure S-5 (Drift in §3.2 noch beobachtet) → 3.0.1 als Verifikations-Issue |
| **Phase-1 1.0.2** §3.3 LogicalButtonId + OVERLAY_5BUTTON | APPLIED — verifizieren! | Sec5-Structure S-5 (Drift in §3.3 noch beobachtet) → 3.0.2 als Verifikations-Issue + 3.0.12 Spec-2-§13.1/§13.2/§6-Followup |
| **Phase-1 1.0.5** Action-Hierarchie | APPLIED — Spec 3 nicht erwischt | Sec4-Structure S-4 + Sec5-Structure S-3 + Sec4-Logic L-14 → 3.0.5 (Spec-3 + Spec-2-§8.5/§11.7-Followup) |
| **Phase-1 1.0.6** State-Pfade hierarchisch | APPLIED — Spec 3 + §13-Audits nicht erwischt | Sec4-Structure S-1 + Sec5-Structure S-2 → 3.0.4 (Spec-3 + §13-Audit-Followup) |
| **Phase-1 1.1.1** PipelineStateManager-Naming-Drift | weiterhin offen — Spec 3 als größte Drift-Quelle | Sec4-Structure S-5 (7+ Treffer in Spec 3) + Sec5-Structure S-1 → 3.0.3 (Spec-3-Naming-Apply als kontext-sensitives Rename) + 3.0.6 (§13.3-Audit-Cleanup) |
| **Phase-1 1.1.2** direkte `_state.value.copy(...)` in Spec 3 | weiterhin offen — größtes Cluster | Sec4-Structure S-2 + S-3 + S-6 + Sec4-Logic L-9 + Sec5-Structure S-4 + Sec5-Logic L-6 → 3.1.1 (OverlayModule-Heimat) + 3.1.2 (ViewMode-FSM) + 3.1.7 (closeOverlay-Cascade) |
| **Phase-1 1.1.5** Cascade-Loop-Guard / SOLID | weiterhin offen — strukturell Coupling-Matrix | Sec5-Structure S-10 (Cross-Module-Coupling-Matrix) → 3.1.12 |
| **Phase-1 1.1.6** Block-1 unterschätzt | weiterhin offen — verstärkt | Sec5-Structure S-7 + Sec5-Logic L-5 → 3.1.14 (Block-1-Split + Risiko-Eintrag) |
| **Batch-1 2.1.4** Reentrancy-Vertrag | weiterhin offen | Sec4-Logic L-2 (computeViewMode Race) → indirekt via 3.1.2 |
| **Batch-1 2.1.9** IME-Service-Death + InputConnection | weiterhin offen — verstärkt für Overlay | Sec4-Logic L-4 + L-18 → 3.1.4 (Overlay-Owner-Architektur) |
| **Batch-1 2.1.13** KSM-Aufspaltung | weiterhin offen | Sec5-Structure S-10 (Cross-Module-Matrix) → 3.1.12 |

---

## Cross-Section-Dedup innerhalb Batch 2

| Konsolidiertes Issue | Quellen |
|---|---|
| 3.0.4 (Sub-State-Pfad-Drift Spec 3 + §13) | Sec4-Structure S-1, Sec5-Structure S-2 |
| 3.0.5 (Action-Hierarchie Spec 3 + §13) | Sec4-Structure S-4, Sec5-Structure S-3, Sec4-Logic L-14 |
| 3.0.6 (§13.3-Audit-Pre-F-11-Vokabular) | Sec5-Structure S-1, Sec5-Logic L-1 |
| 3.0.9 (Acceptance-Bidi-Pointer + Resend-Toggle-Bug-Tests) | Sec5-Structure S-8, Sec5-Logic L-4, Sec4-Logic indirekt |
| 3.1.1 (OverlayModule-Heimat) | Sec4-Structure S-3 + S-2 (impl), Sec5-Structure S-3 |
| 3.1.2 (ViewMode-FSM-Eigentum) | Sec4-Structure S-8, Sec4-Logic L-2 + L-10 |
| 3.1.3 (Permission-Lifecycle) | Sec4-Logic L-1 + L-16 + L-17, Sec4-Structure S-12 |
| 3.1.4 (IME-Service-Death + Overlay-Owner) | Sec4-Logic L-4 + L-18 |
| 3.1.5 (Drag-Lifecycle-Cluster) | Sec4-Logic L-3 + L-5 + L-7 |
| 3.1.6 (Position-Mapping + Multi-Display) | Sec4-Logic L-6 + L-13 |
| 3.1.7 (closeOverlay-Cascade + T2-Race + Audio-File-Cleanup) | Sec4-Structure S-6, Sec4-Logic L-8 + L-20 |
| 3.1.13 (Cross-Spec-DRY-Tabelle + predIsIdle) | Sec5-Structure S-9, Sec5-Logic L-3, Sec4-Structure S-7 |
| 3.1.15 (Plan-Body-PENDING-Marker + Out-of-Scope + Iter-Log) | Sec5-Logic L-13 + L-12 + L-11 |

---

## Cluster für Research-Step / Final-Report

Themen, die zusammen behandelt werden sollten:

1. **Spec-3-Module-Integration-Cluster:** 3.1.1 + 3.1.2 + 3.1.7 + 3.1.4 + Phase-1 1.1.2 — OverlayModule-Heimat, ViewMode-FSM-Eigentum, closeOverlay-Cascade, Overlay-Owner-Architektur. Gemeinsam zu lösen, weil sie alle die Spec-3-↔-Spec-1-Pattern-Integration betreffen.
2. **HOVER-Lifecycle-Cluster:** 3.1.2 (T7) + 3.1.3 (Permission als State-Achse) + 3.1.4 (IME-Service-Death) + Sec4-Logic L-1 (HOVER-Permission-UI) — alle Lifecycle-Lücken im HOVER-Modus.
3. **Block-1-Architektur-Cluster:** 3.1.14 + Phase-1 1.1.6 + Batch-1 2.1.X — Block-1-Split-Decision unterschiedlicher Granularitäten, gemeinsam mit Spec-1-§10-Acceptance-Granularität.
4. **§13-Audit-Konsistenz-Cluster:** 3.0.6 + 3.0.7 + 3.0.8 + 3.1.13 + Phase-1 1.1.1 — alle §13-Audit-Cleanups + Cross-Spec-DRY.
5. **Acceptance-Test-Lücken-Cluster:** 3.0.9 + 3.0.10 + 3.0.11 + 3.1.9 + Sec3-Logic L-14 (Batch 1) — alle "Plan-Behauptung ohne Acceptance-Anker"-Findings.
6. **Drag-Lifecycle-Cluster:** 3.1.5 + 3.1.6 + 3.1.11 — alle Drag/Position-Lifecycle-Probleme.
7. **Plan-Hygiene-Cluster:** 3.1.15 + Iter-Log + Out-of-Scope-Sektionen — alle Plan-Body-Cleanups.

---

## Notes for Apply-Step (Resume Context)

- **Apply-able 🟢-Issues (12):** 3.0.1 (Hauptplan §3.2 Verifikation), 3.0.2 (Hauptplan §3.3 Verifikation), 3.0.3 (Spec-3-PipelineStateManager-Apply), 3.0.4 (Spec-3 + §13-State-Pfade-Apply), 3.0.5 (Spec-3 + Spec-2-§8.5/§11.7-Action-Hierarchie-Apply), 3.0.6 (Spec-1-§13.3-Audit-Cleanup), 3.0.7 (§13.5-Tabellen-Splitting), 3.0.8 (§13.1-Cross-Spec-Konflikt-Auflösung), 3.0.9 (Acceptance-Bidi-Pointer + Resend-Toggle-Tests), 3.0.10 (Cross-Module-Cascade-Acceptance-Tests), 3.0.11 (MediaRecorder-Leak-Acceptance-Test), 3.0.12 (WIDGET_TOGGLE in §13.1/§13.2/§6 + Silent-Skip-Fix).
- **🟡-Issues bleiben unangetastet (15):** 3.1.1 bis 3.1.15. Architektur-Entscheidungen, gehen in den Research-Step + Final-Report. User-Entscheidung pro Issue.
- **Größte Apply-Hot-Spots:** 3.0.3 + 3.0.4 + 3.0.5 — drei mechanische String-Rewrites über Spec 3 + verbleibende Spec-1/Spec-2-Sites. Apply-Agent muss die drei Phase-1-Mapping-Tabellen erneut anwenden.
- **Cluster-Hinweise:** siehe "Cluster für Research-Step / Final-Report" oben — gemeinsam klären statt einzeln.
