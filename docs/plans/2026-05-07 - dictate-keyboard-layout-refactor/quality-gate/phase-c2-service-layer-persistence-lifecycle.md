# Phase C-2 — Service-Layer + Persistence + Lifecycle Kohärenz-Review

**Erstellt:** 2026-05-14
**Reviewer:** Phase-C-Agent C-2
**Plan-Version vor Edits:** Commit `2a032e3` (Phase-C-1 abgeschlossen, 9 Subsystem-Reports + C-1-Apply mit 14 Edits)
**Scope:**
- Spec 1 §6 (Persistence-Erweiterung: Schema-Migration M3→M4, ActiveJobRegistry-Strategie, Checkpoint-Hooks R.17, Recovery-Read, Orphan-FAILED-Audio-Cleanup)
- Spec 1 §7 (Lifecycle: Foreground Service Composition Root + Notification-Coordinator + Action-Router + onDestroy-Cleanup)
- Spec 1 §11 (Implementation-Details: AndroidManifest-Diff, Block-1a/1b/2/3/4-Migrations-Reihenfolge, DB-Migration-Tests inkl. v1→v4-Chain, POST_NOTIFICATIONS Runtime-Permission, OOM-Death-Recovery, Lint-Setup, androidTest-Setup)

**Cross-Spec-Verifikation:** keine — C-2-Scope ist Spec-1-zentral (§6 + §7 + §11 sind ausschließlich Service-Lifecycle + Persistence + Block-Implementation, alle Spec-1-internal). Spec 2 + Spec 3 unverändert.

**Vorgänger-Anker:** Phase-C-1 hat KeyboardInputModule-Counter homogenisiert, §15.2 Markdown-Fence-Bug behoben, §13.3.12 DictateModule-Interface auf 7+4 Methoden umgestellt. C-2 baut auf diesen Edits auf — kein Re-Audit der C-1-Sektionen.

---

## Summary

Der Service-Layer + Persistence + Lifecycle-Bereich ist nach allen Phase-B-Edits **architektonisch tragfähig**, hatte aber ein **Drift-Echo-Cluster** aus drei verschiedenen Phase-B-Konsolidierungen:
- F-11-DictateOrchestrator-Rename (Phase-B S-1) wurde in `§4.11.5.1`-onDestroy + `§11.1.4`-Mitigation-Prosa nicht synchron gezogen
- NOTIF_ID-SoT-Konsolidierung (Phase-B S-5) auf `PipelineNotificationCoordinator.NOTIF_ID` hat drei Code-Snippet-Sites mit unqualifizierten Refs hinterlassen
- R.17-DB-first-Erweiterung (Phase-B S-2) lebte als 5. Bulletpoint neben der State-First-Klausel ohne Layer-Disambiguierung

Hinzu kam eine **innerhalb-Funktion-Drift** in §6.3 (Recovery-Snippet ruft DAO einmal mit `.name`-Strings, einmal mit Enum-Werten direkt) und ein **Doku-vs-Acceptance-Drift** in §7.3-onDestroy (Acceptance verlangt Pre-Cancel-Dispatch für MediaRecorder-Release, Snippet zeigt nur `orchestrator.shutdown()` ohne Pre-Cancel — Native-Heap-Leak-Risiko). **8 Findings (3 Critical, 4 Important, 1 Minor); 10 Plan-Edits** (alle in Spec 1: `1-pipeline-service.reviewed.md`; plus 1 Iter-Log-Eintrag im Hauptplan).

---

## Findings + Applied Fixes

### F-1 (CRITICAL) — §4.11.5.1 `onDestroy`-Snippet zeigt `stateManager.shutdown()` (Pre-F-11-Drift)

**Symptom:** Phase-B S-1 hat den monolithischen `PipelineStateManager` durch `DictateOrchestrator` ersetzt; das §4.11.5.1-onDestroy-Mini-Snippet (Cleanup-Job-Interaktion) wurde dabei nicht synchron gezogen.

**Folge:** Compile-Error, weil `stateManager` als Field im Service nicht existiert. Ein Implementer würde stutzen; wenn er den §7.3-Voll-Snippet (mit `runBlocking`-Timeout) als SoT nutzt, ist das Problem nur kosmetisch — trotzdem Lese-Anchor-Drift zwischen §4.11 und §7.3.

**Fix:** `stateManager.shutdown()` → `orchestrator.shutdown()`; FIX-Kommentar verweist auf §7.3 als Voll-Snippet-SoT.

**Edit:** §4.11.5.1 Cleanup-Job-Snippet.

---

### F-2 (CRITICAL) — §6.3 Recovery-Snippet übergibt `SessionStatus`-Enum-Werte an `getSessionsByStatuses(List<String>)`-DAO

**Symptom:** Innerhalb derselben Funktion `recoverFromDb()` ruft der Top-Block das DAO korrekt mit `.name`-Strings auf; der Bottom-Block übergibt die Enum-Werte direkt.

**Folge:** Kotlin-Type-Mismatch — Compile-Error, NICHT silent. Aber dass es **innerhalb derselben Funktion** zwei verschiedene Calling-Conventions gibt, ist ein Drift-Artefakt aus iterativer Plan-Editing-Geschichte. Solche innerhalb-Funktion-Drifts sind nicht durch `grep` findbar (Token ist identisch — nur die Aufruf-Argument-Form unterscheidet sich) und brauchen Code-Snippet-Read-Through.

**Fix:** Bottom-Block auf `.name`-Strings umgestellt + FIX-Kommentar dokumentiert die Konvention (DAO-Signatur ist `List<String>`, kein TypeConverter).

**Edit:** §6.3 Recovery-Snippet Bottom-Block (`getSessionsByStatuses`-Call).

---

### F-3 (CRITICAL) — §7.3 `onDestroy` fehlt Pre-Cancel-Dispatch für MediaRecorder-Release

**Symptom:** §10 Acceptance Block-2 ("MediaRecorder-release-Pfad", FIX Issue 3.0.11) und §13.5 G6 Pfad A verlangen explizit, dass `Service.onDestroy` bei aktivem Recording zuerst `orchestrator.dispatch(Action.PipelineAction.CancelPipeline)` ruft → `RecordingModule.runEffect(Effect.ReleaseMediaRecorder)` → `recordingManager.release()`. Der aktuelle §7.3-onDestroy-Snippet ruft jedoch nur `orchestrator.shutdown()`.

`DictateModule.terminate(services)` hat im §4.2-Interface einen Default-Body `Unit`, und `RecordingModule` (§15.2) hat KEIN `terminate`-Override, das `Effect.ReleaseMediaRecorder` synchron emittieren würde.

**Folge:** MediaRecorder leakt im Native-Heap bei IME-Schließen während Recording. Drift zwischen §7.3-Code-Snippet und §10/§13.5-Acceptance.

**Action-Naming-Frage (Cross-Spec):** `CancelPipeline` ist eine `Action.PipelineAction`-Variante; Recording-Hardware wird aber von `RecordingModule` gehalten — die semantisch korrekte Action wäre `Action.RecordingAction.CancelRecording`. Cross-Spec-Klärung gehört nach C-3 (Action-Hierarchie).

**Fix:** §7.3-onDestroy-Snippet um Schritt-0-Block ergänzt (auskommentiert mit State-Switch zwischen Recording-/Pipeline-Cancel) + prominenter FIX-Kommentar, der die Implementer-Pflicht zur Disambiguierung dokumentiert. Semantisch korrekte Action-Variante wird vor Block-2-Acceptance-Test entschieden (C-3-Cross-Reference).

**Edit:** §7.3 onDestroy-Snippet (Pre-Cancel-Block).

---

### F-4 (IMPORTANT) — §6.2 R.17 Persistenz-Vertrag hat zwei "Reihenfolge"-Klauseln ohne Layer-Disambiguierung

**Symptom:** Bulletpoint 2 ("Reihenfolge State-First": State zuerst, dann DB) und Bulletpoint 5 ("Reihenfolge DB → Cache": DB zuerst, dann ActiveJobRegistry) wirken auf den ersten Blick widersprüchlich, sind es aber nicht — sie sprechen über ZWEI verschiedene Layer-Übergänge:
- State ↔ DB ist die erste Stufe
- DB ↔ Performance-Cache die zweite

**Folge:** Ohne Disambiguierung-Block ist das ein Lese-Anchor-Drift-Risiko: ein Implementer könnte den State zuerst, dann ActiveJobRegistry, dann DB schreiben (= State-First, aber DB-last) — was die DB-first-Garantie für OOM-Recovery aufweicht.

**Fix:** Vorab-Disambiguierung als prominenter Blockquote ergänzt (zwei Reihenfolge-Klauseln, zwei Layer; Gesamt-Reihenfolge: State → DB → ActiveJobRegistry). Beide Bulletpoints im Vertrag explizit mit Layer-Tag annotiert: "(State ↔ DB)" und "(DB ↔ ActiveJobRegistry)".

**Edit:** §6.2 R.17 Persistenz-Vertrag Header + Bulletpoints 2 + 5.

---

### F-5 (IMPORTANT) — §11.6.2 Recovery-Snippet ist veraltete Pre-S-2-Variante, widerspricht §6.3 SoT

**Symptom:** Das §11.6.2-Snippet zeigt eine vereinfachte `recoverFromDb()`-Logik OHNE RECORDING/TRANSCRIBING-Branches — nur ein RECORDED-Subpfad + Ghost-Detection. §6.3 (SoT post-S-2) hat dagegen die volle 6-Stati-Recovery-Logik (RECORDING→FAILED+cleanup, TRANSCRIBING→RECORDED-Downgrade-oder-FAILED, etc.).

**Folge:** Ein Implementer, der nur §11.6.2 liest, würde die Recovery-Logik unvollständig implementieren — die R.16a/b/c-Tests in §10 Acceptance würden rot fehlschlagen.

**Fix:** §11.6.2-Snippet als "vereinfachte Pre-S-2-Variante OHNE RECORDING/TRANSCRIBING-Branches" + "Implementer-Anker: SoT ist §6.3" explizit annotiert. Funktion zu `recoverFromDb_recordedSubPath` umbenannt, damit klar ist, dass es nur den RECORDED-Sub-Pfad illustriert.

**Edit:** §11.6.2 Snippet-Header + Funktionsname.

---

### F-6 (IMPORTANT) — §11.1.4 Snippet-Prosa-Drift: `stateManager.state.value` (Pre-F-11)

**Symptom:** Mitigation-Text referenziert `stateManager.state.value` als State-Quelle für den synchronen Notification-Build. Phase-B S-1 hat das auf `DictateOrchestrator.state` umgestellt; Prosa-Drift seit Phase-B nicht synchron gezogen.

Zusätzlich: Mitigation-Text sagt `onCreate`-Phase für `startForeground`, aber tatsächlich lebt `startForeground` im `onStartCommand`-Pfad (§4.11.5.1 Sequence-Tabelle Schritt 9). Android führt `onCreate` ohne `onStartCommand` nicht via `startForegroundService` durch.

**Folge:** Lese-Anchor-Drift; ein Implementer, der die Mitigation-Prosa als Anker für die `startForeground`-Phase nutzt, hätte die falsche Lifecycle-Phase verankert.

**Fix:** `stateManager.state.value` → `orchestrator.state.value` + `onCreate` → `onStartCommand` (mit Cross-Ref auf §4.11.5.1 Sequence-Tabelle Schritt 9).

**Edit:** §11.1.4 Mitigation-Prosa.

---

### F-7 (IMPORTANT) — NOTIF_ID-Referenzen in §7.3-onStartCommand + §11.1.2-startForegroundCompat unqualifiziert

**Symptom:** Phase-B S-5 hat die NOTIF_ID-SoT auf `PipelineNotificationCoordinator.NOTIF_ID` konsolidiert (kein `private const val NOTIF_ID` mehr im Service-companion); §10 Acceptance "Phase-B S-5 NOTIF_ID-Konsistenz" verlangt das. Aber:
- §7.3-`onStartCommand` referenziert `NOTIF_ID` unqualifiziert
- §11.1.2-`startForegroundCompat` referenziert `NOTIF_ID` unqualifiziert
- §4.11.5.1 Sequence-Tabelle Schritt 9 referenziert `NOTIF_ID` unqualifiziert

§11.1.2 Companion-Snippet hat genau diese Doppel-Definition explizit gestrichen mit Hinweis "Service referenziert `PipelineNotificationCoordinator.NOTIF_ID` direkt" — aber die Konsumenten-Sites blieben unsynchron.

**Folge:** Kompiliert nicht, weil im Service-companion (`companion object { private const val TAG = ... }`) keine `NOTIF_ID`-Const existiert. Drift zwischen Konsolidierungs-Block und Code-Snippet.

**Fix:** Alle drei Sites auf `PipelineNotificationCoordinator.NOTIF_ID` qualifiziert.

**Edit:** §7.3 onStartCommand, §11.1.2 startForegroundCompat, §4.11.5.1 Sequence-Tabelle Schritt 9.

---

### F-8 (MINOR) — §11.2.3 Test-Strategie Tabelle Pref-Zähler stale (15 → 19) + §11.1.1 Permission-Caption Off-by-One

**Symptom (Teil 1):** Phase-C-1 hat §11.2.2 Schritt 7 von "15 Prefs" auf "19 Prefs" korrigiert (basierend auf §4.5 `initialMirror`-Block: 3 layout + 3 audio + 1 resend + 4 features + 4 theming + 4 overlay = 19). §11.2.3 Test-Strategie-Tabelle (`PipelinePrefMirrorTest`-Zeile) zeigte aber noch "15 Prefs" — Folge-Konsistenz-Drift aus C-1.

**Symptom (Teil 2):** §11.1.1 Block-2-Manifest-Diff-Caption sagt "alle drei Permission-Gruppen kombiniert", zählt aber **vier** Permission-Einträge (drei Service-Permissions + `SYSTEM_ALERT_WINDOW`). Off-by-One durch SYSTEM_ALERT_WINDOW-Ergänzung in Phase-B S-5 F-12.

**Fix:** Test-Strategie-Tabelle (`PipelinePrefMirrorTest`-Zeile) auf "19 Prefs" mit expliziter Aufzählung der Pref-Buckets aktualisiert. §11.1.1-Caption auf "vier Permission-Einträge — drei Service-Permissions + die vorab deklarierte Overlay-Permission" umformuliert.

**Edit:** §11.2.3 Test-Strategie-Tabelle Zeile 1b; §11.1.1 Block-2-Manifest-Diff-Caption.

---

## Plan-Edits (Audit-Trail)

| Datei | Sektion | Art | Kurzbeschreibung |
|---|---|---|---|
| Spec 1 §4.11.5.1 | Cleanup-Job-onDestroy-Snippet | Update | `stateManager.shutdown()` → `orchestrator.shutdown()` (F-1) |
| Spec 1 §4.11.5.1 | Sequence-Tabelle Schritt 9 | Update | `NOTIF_ID` → `PipelineNotificationCoordinator.NOTIF_ID` (F-7) |
| Spec 1 §6.2 | R.17 Persistenz-Vertrag | Insert/Update | Vorab-Disambiguierung-Blockquote + Layer-Tags an Bulletpoints 2 + 5 (F-4) |
| Spec 1 §6.3 | Recovery-Snippet Bottom-Block | Update | Enum-Werte → `.name`-Strings für DAO-Call (F-2) |
| Spec 1 §7.3 | onDestroy-Snippet | Insert | Schritt-0-Block "Pre-Cancel-Dispatch" mit FIX-Kommentar (F-3) |
| Spec 1 §7.3 | onStartCommand `startForeground`-Call | Update | `NOTIF_ID` → `PipelineNotificationCoordinator.NOTIF_ID` (F-7) |
| Spec 1 §11.1.1 | Block-2-Manifest-Diff-Caption | Update | "drei Permission-Gruppen" → "vier Permission-Einträge" (F-8) |
| Spec 1 §11.1.2 | `startForegroundCompat`-Snippet | Update | `NOTIF_ID` → `PipelineNotificationCoordinator.NOTIF_ID` (F-7) |
| Spec 1 §11.1.4 | Mitigation-Prosa | Update | `stateManager.state.value` → `orchestrator.state.value` + `onCreate` → `onStartCommand` (F-6) |
| Spec 1 §11.2.3 | Test-Strategie-Tabelle Zeile 1b | Update | "15 Prefs" → "19 Prefs" mit Bucket-Aufzählung (F-8) |
| Spec 1 §11.6.2 | Recovery-Snippet | Annotate | Snippet als "Pre-S-2-Variante" markiert + Funktionsname → `recoverFromDb_recordedSubPath` (F-5) |
| Hauptplan §9 | Iteration-Log | Insert | "2026-05-14 — Phase-C Quality-Gate C-2"-Entry mit 8 Findings + Plan-Edits-Summary |

**Gesamt:** 12 Operations in 2 Dateien (Spec 1: 11, Hauptplan: 1). Spec 2 + Spec 3 unverändert — der C-2-Scope (Service-Layer + Persistence + Lifecycle) ist Spec-1-zentral.

---

## Offene Fragen für nachfolgende Agents

### Für C-3 (Action-Hierarchie + Dispatch + EffectFailure)

- **F-3-Cross-Reference (Pflicht):** Der `Pre-Cancel-Dispatch`-Block in §7.3-onDestroy verweist auf die Action-Naming-Frage: `Action.PipelineAction.CancelPipeline` vs. `Action.RecordingAction.CancelRecording`. C-3 muss die semantisch korrekte Action-Variante bestimmen (Recording-Hardware wird von `RecordingModule` gehalten — wahrscheinlich `RecordingAction.CancelRecording`, mit `Effect.ReleaseMediaRecorder` im `runEffect`-Body) und den `// 0. Pre-Cancel-Dispatch`-Block in §7.3 entsprechend disambiguiert.
- **`Action.ViewModeAction.OnImeViewShown/OnImeViewHidden`-Dispatch-Pfad:** Spec 2 §3.3 + Spec 3 nutzen ViewModeAction-Refs; C-3 sollte prüfen, ob die Hierarchie + Slot-Resolver-`null`-Semantik (verhindert `DispatchOutcome.Unrouted` strukturell) konsistent gegen §5 LocalBinder.dispatch (C-1 F-6) verankert ist.
- **F-5-Pattern fortsetzen:** Falls C-3 weitere Z.-Refs in §4.3 + Spec 2 §3.3 entdeckt, gleiches Anchor-Pattern wie C-1 F-5 anwenden (Methoden-/Sektionsname statt Z.).

### Für C-4 (Layout/View-Rendering — Spec 2)

- §11.2.2 Schritt 6 "LayoutModule implementieren — `KeyboardStateManager.contentArea/isSmallMode` wandern in `LayoutState`" — C-4 muss prüfen, ob der Atomar-Vertrag (Block-1b-Acceptance "Atomarität setSmallMode") in der LayoutModule-Implementations-Stelle (Spec 2) korrekt reflektiert ist.
- C-4 sollte den F-7-NOTIF_ID-Drift-Pfad nicht erneut treffen — die Notification-Coordinator-Site ist nach C-2 vollständig homogenisiert.

### Für C-5 (Floating-Overlay — Spec 3)

- C-5 erbt das F-3-Cross-Reference (sobald C-3 die Action-Variante festgelegt hat): falls `Action.RecordingAction.CancelRecording` gewählt wird, prüfen, ob Spec 3 §6.1 + §7.3 (Overlay-FSM, T1–T7) die `RecordingAction`-Sub-Hierarchie korrekt verankern.
- Spec 3 §5.0 OverlayPermissionObserver-Boot-Default-Race-Block (S-8 F-6) und §7.3 T7-Block (S-8 F-4) sind in C-5-Scope — sollten beim Re-Audit-Pass auf Konsistenz mit dem in C-2 geklärten Pre-Cancel-Dispatch-Pfad (§7.3 Service-onDestroy) gegengeprüft werden.

### Für C-State (State-File-Konsistenz)

- Plan-State-File (`plan-review/state.md`) ist seit Phase-1-Abschluss nicht aktualisiert (Workflow-Step-Tabelle zeigt noch alle Steps 3-10 als `⏳`). Beim Phase-2/Phase-5-Plan-Archive-Schritt sollte das State-File auf den tatsächlichen Phase-A/B/C-Workflow umgestellt werden (Phase-1/2-Architektur wurde implizit ersetzt durch die Phase-A/B/C-Sektionen-Reviews).

---

**Reviewer-Note:** Das C-2-Finding-Cluster ist ein **Drift-Echo-Muster** ähnlich C-1: jede Phase-B-Iteration hat einen primären Konsolidierungs-Block angelegt (z.B. F-11-DictateOrchestrator-Rename, NOTIF_ID-SoT, R.17-Persistenz-Vertrag), aber Folge-Sites mit Cross-Refs blieben mehrfach stale. Konkret:

- **NOTIF_ID-Konsolidierung** in §11.1.2 (Block der Doppel-Definition entfernt) zog die §7.3-Code-Snippet-Sites + §4.11.5.1-Sequence-Tabelle nicht synchron auf qualifizierte Refs
- **F-11-DictateOrchestrator-Rename** zog §4.11.5.1-onDestroy + §11.1.4-Mitigation-Prosa nicht synchron
- **R.17-DB-first-Erweiterung** lebte als 5. Bulletpoint neben dem State-First-Bulletpoint ohne Disambiguierung

→ drei verschiedene Sub-Drift-Pfade aus drei verschiedenen Phase-B-Iterationen.

**Lesson:** jeder Phase-B-Edit, der eine **Naming-Konvention oder einen Vertrags-Layer** ändert, MUSS einen Plan-weiten `grep` über den ALTEN Naming-Token (z.B. `stateManager\.`, `NOTIF_ID\b`-unqualifiziert) auslösen. C-2 hat alle 10 Echo-Sites in §6 + §7 + §11 homogenisiert.

Plus: die §6.3 Recovery-Snippet-Enum-Drift (F-2) war ein **innerhalb-Funktion-Drift** (Top-Block korrekt, Bottom-Block falsch). Solche Sites sind nicht durch `grep` findbar (Token ist identisch, nur die Aufruf-Argument-Form unterscheidet sich) und brauchen Code-Snippet-Read-Through. Lesson: Code-Snippets, die im Plan mehrfach DAO-/Service-Calls in derselben Funktion enthalten, brauchen einen Convention-Check über alle Call-Sites — nicht nur den ersten.

Nach den 11 Spec-1-Edits + 1 Hauptplan-Edit ist der Service-Layer + Persistence + Lifecycle-Bereich für die Implementer-Phase reif.
