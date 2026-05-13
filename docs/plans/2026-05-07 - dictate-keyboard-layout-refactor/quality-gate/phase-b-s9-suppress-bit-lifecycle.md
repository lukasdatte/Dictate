# Phase-B Quality-Gate S-9 — ResetSuppressBit-Lifecycle (PENDING-3) Migrations-Pfad-Review

**Datum:** 2026-05-13
**Reviewer:** Agent #7 von 9 (Phase-B Quality-Gate)
**Subsystem:** S-9 — ResetSuppressBit-Lifecycle: PENDING-3 Pseudo-Cascade → Cross-Module-Action mit Single-Reducer-Ownership
**Scope:** Cross-Cutting (klein im Scope, kritisch für Cascade-Korrektheit). Exemplarische Test-Case für KG-RSB-2 (Self-Filter-Bug-Klasse) und für die Pure-Cross-Module-Cascade-Mechanik (Mode 2 in Spec 1 §15.5). Hängt zwischen S-4 (Cascade-Mechanik) und S-8 (Overlay-Reducer).
**Vorgänger-Reports:** S-1 (`9f84730`), S-2 (`47a4e06`), S-3 (`af0bd00`), S-4 (`c895695`), S-7 (`2b27cf9`), S-5 (`f34e484`).

---

## Zusammenfassung

**5 Findings: 1 Critical, 3 Important, 1 Minor.**

Alle drei PENDING-3-bezogenen Plan-Pfade (Spec 1 §15.2 RecordingModule.onCrossModuleStateChange + Spec 2 §3.3 Action-Definition + Spec 3 §4.8 OverlayModule.reduce + §11.9 Boot-Default-Semantik + §10 Acceptance) sind in der ResetSuppressBit-Mechanik **architektur-strukturell wasserdicht**: Self-Cascade-Erlaubnis (KG-RSB-2 RESOLVED), Idempotenz-Vertrag (Reducer returnt `TransitionResult` auch bei `false`-Wert), Cancel-in-Preparing-Boundary präzise (`prev.recording is Idle && next.recording is Preparing`), Coupling-Matrix-Konvention KG-RSB-3 mit Self-Read-Diagonale `—`, Transient-Vertrag (Boot-Default `false`, kein Pref-Mirror, OOM-Recovery konsistent). Regression-Test R.RSB-FIX-A in Acceptance verankert; Test-Skelette in Spec 3 §14.1 für Reducer + Cross-Module-Cascade + End-to-End-Integration.

**Die Findings adressieren zwei strukturelle Lücken:**

1. **Spec-3-internal Notations-Drift** (Finding 1, CRITICAL) — Surprise-Finding #3 aus der Phase-A-Inventur. §7.3 T1+T2 zeigte eine Cross-Axis-Mutation im ViewModeModule-Reducer (Mode 3, Phase-2-Backlog), während §6.1 schon die SRP-konforme Mode-2-Cascade-Form hatte. Doppel-Truth-Quelle: zwei Sektionen, zwei Reducer-Formen für dieselbe Action. Gefixt: §7.3 auf §6.1-konsistente Cascade umgestellt.

2. **Konventions-Hardening gegen Re-Drift** (Findings 2+3, IMPORTANT) — Die KG-RSB-2-Auflösung (Self-Filter gestrichen) und die KG-RSB-3-Self-Read-Konvention sind durch Regression-Test bzw. Notations-Konvention abgesichert, aber NICHT durch Compile-Time-Schutz oder prominente Code-Banner. Bei einem Refactor "looks like an infinite-loop guard" könnte der Self-Filter wieder rein, oder ein neuer Maintainer könnte Mode 3 versehentlich einbauen (genau das, was in §7.3 passiert war). Gefixt: prominenter ASCII-Box-Banner in §4.3 + Anti-Beispiel-Tabelle in §15.5 mit Mode-1/2/3-Disambiguation und konkreten Plan-Beispielen.

Die übrigen zwei Findings (Idempotenz-Subscriber-Klarstellung, ProGuard-Cross-Link) sind Doku-Hardening ohne Bug-Risiko.

---

## Sieben Prüf-Achsen — Ergebnisse

### Achse 1: Self-Cascade-Erlaubnis (KG-RSB-2-Regression-Schutz)

**Verifiziert:**
- Self-Filter `modules.filter { it.id != module.id }` ist KOMPLETT entfernt aus Spec 1 §4.3 Step 5 (Z. 724–727 nach Edit). `modules.flatMap { it.onCrossModuleStateChange(prevGlobal, nextGlobal) }` iteriert ALLE Module inklusive des emittierenden.
- FIX-Kommentar in §4.3 (Z. 718–723 *vor* S-9-Edit) war als 6-Zeilen-`// FIX:`-Kommentar formatiert — visuell ähnlich zu den ~80 anderen FIX-Kommentaren in der 6700-Zeilen-Datei. **→ Finding 2 (IMPORTANT, gefixt).**
- Regression-Test `R.RSB-FIX-A` ist in Spec 1 §10 (Z. 4233) verankert mit Test-Class `DictateOrchestratorTest.kt`, Methodenname `recordingModule_idleToPreparing_emitsResetSuppressBit_viaSelfCascade()`, expliziter Setup-Beschreibung (`suppressAutoOverlayUntilNextSession = true`, dispatch `RecordingAction.StartRecording`, assert `store.snapshot.overlay.suppressAutoOverlayUntilNextSession == false`).

**Wie würde der Filter heute wieder reinkommen?** Drei Vektoren:
- (a) PR-Reviewer mit Reflex "looks like an infinite-loop guard" — der ASCII-Box-Banner nach S-9-Edit macht das schwer übersehbar.
- (b) Automatisches IDE-Refactoring (z.B. "Convert flatMap to filter+flatMap" + Sicherheits-Filter einbauen) — der Banner mit `⚠ DO NOT RE-ADD SELF-FILTER`-Heading verhindert das.
- (c) Test-Skip durch Selektiv-Build — der Regression-Test fängt das NICHT bei Skip; aber der Banner reduziert die Wahrscheinlichkeit drastisch, dass der Code überhaupt geändert wird.

**Verdict:** RESOLVED + gehardet (Finding 2).

### Achse 2: Idempotenz-Vertrag

**Verifiziert:**
- Reducer-Snippet (Spec 3 §4.8 Z. 895–898) returnt `TransitionResult(state.copy(suppressAutoOverlayUntilNextSession = false), emptyList())` auch wenn das Bit bereits `false` ist. Kein `null` → kein `DispatchOutcome.Rejected("reducer-null")`. ✅
- Test `OverlayModuleResetSuppressBitTest.resetSuppressBit_is_idempotent_when_bit_is_already_false()` (Spec 3 §14.1 Z. 2343–2352) verifiziert das Verhalten.
- Acceptance-Bullet in Spec 3 §10 (Z. 1562–1565) listet die Idempotenz-Klausel mit `assertTrue(outcome is DispatchOutcome.Applied)`.

**Cascade-Tiefe-Edge-Case:** Bei `Idle → Preparing` emittiert RecordingModule `ResetSuppressBit` (Cascade-Action). Diese läuft rekursiv via `dispatchInternal(depth+1)`. Nach dem Reset hat sich `state.recording` NICHT verändert (immer noch Preparing). Bei Pass 2 sieht jeder Module-Observer `prev=Preparing/suppress=true` → `next=Preparing/suppress=false`. RecordingModule.onCrossModuleStateChange triggert NICHT erneut auf `prev.recording is Idle && next.recording is Preparing` (beide sind Preparing). **Kein Endlos-Loop.** MAX_CASCADE_DEPTH (R.6, Cap 8) ist alleinige Safety-Net und wäre nicht nötig. ✅

**Subscriber-Klarstellung fehlte:** Plan-Kommentar in §4.8 dokumentierte die Idempotenz semantisch, aber nicht das StateFlow-Subscriber-Verhalten bei strukturell identischer data class. **→ Finding 4 (IMPORTANT, gefixt).**

### Achse 3: Cancel-in-Preparing-Edge-Case

**Verifiziert:**
- Spec 1 §15.2 Z. 6371 nutzt explizit `prev.recording is RecordingState.Idle && next.recording is RecordingState.Preparing` als Boundary. ✅
- Test `cancelRecording in preparing does NOT emit ResetSuppressBit` (Spec 3 §14.1 Z. 2391–2399) verifiziert: bei `Preparing → Idle` (Cancel) wird kein Reset emittiert.
- Test `pauseResume in active does NOT emit ResetSuppressBit` (Z. 2401–2419) verifiziert Active↔Paused.
- Test `preparingToActive does NOT emit ResetSuppressBit` (Z. 2421–2432) verifiziert MediaRecorderReady-Boundary.

**Edge-Case-Skript "Idle→Preparing→Idle(Cancel)→Preparing(retry)":** Beim ersten Idle→Preparing feuert Reset. Beim Cancel kein Reset. Beim zweiten Idle→Preparing feuert Reset wieder — gleiches Boundary, gleiches Predicate. ✅

**Edge-Case-Skript "Idle→Preparing→Active→Paused→Idle(Cancel-from-Paused)→User-öffnet-Widget→User-schließt-Widget(suppress=true)→Idle→Preparing(retry)":**
Beim Cancel-from-Paused (Paused → Idle) kein Reset (kein Boundary-Trigger). User öffnet/schließt Widget — `SuppressAutoOverlayUntilNextSession`-Cascade setzt das Bit auf `true` via OverlayModule.onCrossModuleStateChange auf HOVER→KEYBOARD-Übergang (Spec 3 §4.8 Z. 928–934). Beim zweiten Idle→Preparing emittiert RecordingModule.onCrossModuleStateChange wieder `ResetSuppressBit` → Bit zurück auf `false` → HOVER-Auto-Reopen klappt für die neue Session. **UX:** User-Wahl "verhindere Auto-Reopen für diese Session" hält bis zur nächsten echten Aufnahme — explizit in Spec 1 §15.2 KDoc (Z. 6361–6367) dokumentiert. ✅

### Achse 4: Coupling-Matrix-Konvention (KG-RSB-3)

**Verifiziert:**
- Konvention oberhalb der Matrix dokumentiert (Spec 1 §15.1.x Z. 6077–6089): Self-Reads werden NICHT in Diagonale eingetragen; Cascade-Trigger basierend auf Self-Read listen nur die `C(...)`-Konsequenz in der Observer-Spalte.
- Matrix-Zeile `Recording × Overlay = C(OverlayAction.ResetSuppressBit)` ohne `R(state.recording)`. Konsistent. ✅
- KG-RSB-3-Resolution-Block (Z. 6113–6137) erklärt die verwoerfene verbose Alternative (`[self]R(...)`) explizit.

**Cross-Module-Modi-Disambiguation fehlte:** Mode 1+2+3 sind in §15.5 als Tabelle gelistet, aber kein Anti-Beispiel-Block, der die Modi gegen konkrete Plan-Patterns abgrenzt. **→ Finding 3 (IMPORTANT, gefixt).** §15.5 jetzt mit 4-Zeilen-Anti-Beispiel-Tabelle (Mode 1 / Mode 2 / Mode 3-Backlog / Mode 2-Self-Read) + Code-Review-Pflicht-Klausel + Cross-Link auf KG-RSB-3-Self-Read-Konvention.

**Compile-Time-Schutz gegen Notations-Drift:** Keiner. Code-Review-Konvention (§15.1.x SRP-Konsequenz-Absatz) ist alleinige Sicherung. Optionaler Compile-Time-Manifest `data class CrossReadSet(...)` ist als Phase-2-Possibility erwähnt (§15.1.x Z. 6109–6111), aber nicht in Phase-1-Scope. Für S-9-Scope ausreichend, weil ResetSuppressBit konsistent in Matrix verankert ist.

### Achse 5: Transient vs. Persistent (KG-RSB-1)

**Verifiziert:**
- Spec 3 §11.9 Z. 1908–1923 dokumentiert die Boot-Default-Semantik explizit. Persistenz-Bit `suppressAutoOverlayUntilNextSession`: Boot-Default `false`, kein Pref-Mirror, OOM-Recovery resetted das Bit.
- Side-by-Side mit `userPrefersWidget` (Z. 1896–1904) — beide Bits transient, beide Boot-Default `false`, beide werden bei OOM-Death verworfen.
- Acceptance-Bullet "processRestart_resetsToFalse" ist NICHT explizit als eigener Test in Spec 3 §10 gelistet — aber durch die Status-quo-Semantik (`OverlayState`-data-class-Default `false`) automatisch erfüllt. PipelineRecovery (Spec 1 §4.6) touchiert NUR `pendingSessions`, NICHT OverlayState. Test-Coverage daher implizit über DictateUiState.initial()-Tests.

**Edge-Case "OOM-Recovery findet RECORDING-Session in DB":** PipelineRecovery lädt nur `pendingSessions` (Spec 1 §4.6 Z. 1038–1042). Eine RECORDING-Session wird per S-2-Logik (R.16a) auf FAILED downgegradet — landet NICHT in `pendingSessions` (terminal). Status: `userPrefersWidget` (transient) bleibt `false`. `suppressAutoOverlayUntilNextSession` (transient) bleibt `false`. Auto-Reopen-Logik beim nächsten User-Open würde HOVER NICHT überspringen, weil das Bit `false` ist. ✅ Konsistent.

**Verdict:** RESOLVED. KG-RSB-1-Doku in Spec 3 §11.9 ist vollständig.

### Achse 6: Surprise-Finding #3 (Spec 3 §7.3 T2 Cross-Axis-Mutation)

**Bestätigt:** §7.3 T1 (Z. 1369–1383) und T2 (Z. 1405–1419) zeigen ViewModeModule.reduce-Snippets, die GLEICHZEITIG mehrere Sub-State-Achsen mutieren — T1: `viewMode + overlay.onboardingPending`; T2: `viewMode + layout.smallMode + overlay.userPrefersWidget`. Das ist Cross-Axis-Mutation (Mode 3 / Atomic Cross-Axis-Update), die laut Spec 1 §15.5 explizit Phase-2-Backlog ist.

**Spec 3 §6.1** zeigt schon die korrekte Mode-2-Form (Z. 1226–1257): ViewModeModule mutiert NUR `viewMode`; LayoutModule.onCrossModuleStateChange cascadiert `LayoutAction.SetSmallMode(true)` bei WIDGET→KEYBOARD; OverlayModule.onCrossModuleStateChange cascadiert `OverlayAction.SetUserPrefersWidget(false)`. Issue 1.1.2 Option A+B fordert genau diese Form.

**Bewertung:** Notations-Drift innerhalb Spec 3 (zwei Stellen widersprechen sich), nicht echter Architektur-Bug. §7.3-Snippet ist Doku zur Logik aus Sicht von Spec 3 (siehe §7.1 SSoT-Note Z. 1300–1302), nicht der primäre SoT für den ViewModeModule.reduce-Code. Aber: ein Implementer hätte abhängig davon, welche Sektion er zuerst liest, eine 50%-Chance gehabt, die SRP-verletzende Form zu wählen. **→ Finding 1 (CRITICAL, gefixt).**

**Auflösung:** §7.3 T1+T2 auf §6.1-konsistente Mode-2-Cascade-Form umgestellt. ViewModeModule mutiert NUR `viewMode`; LayoutModule + OverlayModule reagieren via onCrossModuleStateChange. T1-Permission-Gate gibt `null` zurück (Reducer-Vertrag "Action nicht relevant"); Onboarding-Trigger lebt im Resolver/Effect-Pfad. Cross-Reference §6.1 ↔ §7.3 explizit als "Spec-3-internal SSoT"-Hinweis-Block.

### Achse 7: Bugs durch Migration

**(a) Action-Identity bei `object`:** ✅ `Action.OverlayAction.ResetSuppressBit::class` ist eindeutig (KClass-Identität für Kotlin-`object` ist garantiert). Spec 2 §3.3 (Z. 293–301) dokumentiert die Begründung explizit (`object` statt `data class`: keine Payload, Singleton-Identity, Naming-Konsistenz, Sealed-Leaves-Routing optimal).

**(b) ProGuard-Keep-Regel für `object`-Subtypen:** ✅ S-4 F-1 hat die Keep-Regel für die gesamte Action-Hierarchie eingeführt (Spec 1 §4.3 Z. 813–828): `-keep,allowobfuscation,allowshrinking class * extends Action { *; }`. `object`-Subtypen erben den Keep, weil das Pattern jeden Subtyp matched (inkl. `object`-Singletons). `KClass`-Reference bleibt intakt, weil `sealedSubclasses`-Reflection auf der Class-Hierarchie operiert, nicht auf Namen. **Minor: Cross-Link nicht explizit dokumentiert.** **→ Finding 5 (MINOR, NOTED ohne Plan-Edit).** Ein S-4-Reviewer könnte kurz irritiert sein, ob `object ResetSuppressBit : OverlayAction()` (kein Konstruktor, kein Body) ein Sonderfall ist — die Antwort ist nein, aber ein impliziter Cross-Link in Spec 2 §3.3 oder Spec 1 §4.3 wäre clarification-stärker. Da der existierende ProGuard-Pattern korrekt ist und das Test-Setup (Block-1b `OrchestratorReleaseSmokeTest.kt`, §4.3 Z. 825–828) eine Release-Build-Verifikation enthält, ist der Patch-Wert gering. MARK-as-NOTED.

**(c) Modul-Reorder-Risk (S-4 F-5):** ✅ Cascade-Order-Vertrag verankert in §4.3 (Z. 787–801) + `DictateModuleRegistry.all`-KDoc (Z. 1129–1143). RecordingModule kommt VOR OverlayModule in der Registry-Liste (Z. 1144–1149) — RecordingModule.onCrossModuleStateChange wird ZUERST aufgerufen in der Outer-Cascade-Iteration. Aber: `ResetSuppressBit` wird rekursiv via `dispatchInternal(depth+1)` dispatcht, nicht durch die Outer-Iteration. Reset-Mutation ist daher robust gegen Reorder zwischen RecordingModule und OverlayModule. ✅

**(d) Idempotenz vs. State-Subscribers:** ✅ `MutableStateFlow.update` vergleicht via `equals` (data class structural equality) und unterdrückt die Emission bei gleichem Wert. Subscriber bekommen KEINE Re-Render-Welle, wenn das Bit bereits `false` war. **Subscriber-Klarstellung war nicht im Plan dokumentiert. → Finding 4 (IMPORTANT, gefixt).** Reducer-Snippet-Kommentar erweitert um expliziten Subscriber-Verhalten-Absatz.

---

## Findings im Detail

### Finding 1 (CRITICAL): Spec 3 §7.3 T1+T2 Cross-Axis-Mutation widerspricht §6.1 + §15.5 Mode-2-Konvention

**Was vorher war:**
Spec 3 §7.3 T1 (Z. 1369–1383) zeigte:
```kotlin
when (action) {
    Action.ViewModeAction.ToggleViewModeWidget -> {
        if (!permissions.hasOverlayPermission()) {
            state.copy(overlay = state.overlay.copy(onboardingPending = true))
        } else {
            state.copy(
                viewMode = ViewMode.WIDGET,
                overlay = state.overlay.copy(userPrefersWidget = true),  // §11.9 Persistenz
            )
        }
    }
}
```
§7.3 T2 (Z. 1405–1419) zeigte:
```kotlin
ViewMode.WIDGET -> state.copy(
    viewMode = ViewMode.KEYBOARD,
    layout = state.layout.copy(smallMode = true),
    overlay = state.overlay.copy(userPrefersWidget = false),
)
```
Beide Snippets mutieren mehrere Sub-State-Achsen GLEICHZEITIG in einem Reducer-Block — Cross-Axis-Mutation (Mode 3 / Atomic Cross-Axis-Update), Phase-2-Backlog laut Spec 1 §15.5.

**Konflikt mit §6.1:** Spec 3 §6.1 (Z. 1226–1257) zeigt explizit die korrekte Mode-2-Form. Issue 1.1.2 Option A+B fordert: ViewModeModule mutiert NUR `viewMode`; LayoutModule + OverlayModule reagieren via Cross-Module-Cascade.

**Bug-Klasse:** Ein Implementer hätte abhängig davon, welche Sektion er zuerst liest, entweder die SRP-konforme oder die SRP-verletzende Form implementiert. Im SRP-Verletzenden Fall hätte ViewModeModule reducer-pflichtig auf 3 verschiedenen Sub-State-Achsen geschrieben, und die korrespondierenden Cascade-Hooks in LayoutModule + OverlayModule wären entweder Dead-Code (keine Cascade-Action emittiert) oder Double-Mutation (zwei Pfade setzen denselben State).

**Korrektur:** §7.3 T1+T2 auf §6.1-konsistente Mode-2-Cascade-Form umgestellt:
- ViewModeModule mutiert NUR `viewMode` (gibt `TransitionResult(nextState, emptyList())` zurück, oder `null` wenn Action im aktuellen State nicht relevant).
- LayoutModule.onCrossModuleStateChange cascadiert `LayoutAction.SetSmallMode(true)` bei WIDGET→KEYBOARD.
- OverlayModule.onCrossModuleStateChange cascadiert `OverlayAction.SetUserPrefersWidget(true/false)` bei KEYBOARD↔WIDGET-Übergängen.
- T1-Permission-Gate gibt `null` zurück (Reducer-Vertrag "Action nicht relevant"); Onboarding-Trigger lebt im Resolver/Effect-Pfad (Spec 3 §5.3).
- Cross-Reference §6.1 ↔ §7.3 explizit als "Spec-3-internal SSoT"-Hinweis-Block am Ende von T2.

**Plan-Edit-Stelle:** Spec 3 §7.3 T1 (Z. 1355–1383 vorher → ~75 Zeilen nachher) und T2 (Z. 1392–1422 vorher → ~50 Zeilen nachher). FIX-Block-Kommentare verankert in beiden T1 und T2.

### Finding 2 (IMPORTANT): Self-Filter-Re-Einführung-Schutz nur via Regression-Test

**Was vorher war:** KG-RSB-2-FIX-Kommentar in Spec 1 §4.3 Step 5 (Z. 718–723) war als 6-Zeilen-`// FIX:`-Kommentar formatiert — visuell ähnlich zu den ~80 anderen FIX-Kommentaren in der 6700-Zeilen-Datei.

**Bug-Risiko:** Bei späterem Code-Refactor ("looks like an infinite-loop guard; let me re-add the filter") wäre der Schutz NUR der Regression-Test `recordingModule_idleToPreparing_emitsResetSuppressBit_viaSelfCascade()`. Keine Compile-Time-Sicherung, keine Lint-Regel, keine prominente Code-Banner. Wenn der Reviewer den FIX-Kommentar in einer langen Spec übersieht, pusht den Re-Add-Filter, dann fängt der Test ihn — aber NUR wenn der Test mitläuft (Selektiv-Build / Test-Skip / Disable-Annotation).

**Korrektur:** §4.3 Step 5 FIX-Kommentar in einen prominenten ASCII-Box-Banner umgewandelt:
```
╔═══════════════════════════════════════════════════════════════════════════╗
║ ⚠ DO NOT RE-ADD SELF-FILTER (KG-RSB-2, 2026-05-11)                        ║
║ ... 12 Zeilen Erklärung mit Test-Link + Production-Bug-Beschreibung ...   ║
╚═══════════════════════════════════════════════════════════════════════════╝
```
Schwer zu übersehen, schwer versehentlich zu entfernen. Cross-Link auf §10 R.RSB-FIX-A-Test als Backup-Sicherung.

**Plan-Edit-Stelle:** Spec 1 §4.3 Z. 718–727 (vor Edit) → ~22 Zeilen Banner (nach Edit).

### Finding 3 (IMPORTANT): Cross-Module-Modi-Disambiguation fehlte explizite Anti-Beispiel-Tabelle

**Was vorher war:** §15.5 listete Mode 1 + Mode 2 als 2-Zeilen-Tabelle und Mode 3 als Phase-2-Backlog-Verweis. Kein Anti-Beispiel-Block, der die Modi gegen konkrete Patterns aus dem Plan abgrenzt. Self-Read-Konvention (KG-RSB-3) war zwar in §15.1.x dokumentiert, aber NICHT mit den Mode-Definitionen cross-verlinkt.

**Bug-Risiko:** Bei einem zukünftigen Spec-Eingriff (z.B. eine neue Cascade hinzufügen) hätte ein Maintainer ohne ausreichende Disambiguation Mode 3 versehentlich eingebaut — genau das, was in Spec 3 §7.3 (Finding 1) passiert war.

**Korrektur:** §15.5 um eine 4-Zeilen-Anti-Beispiel-Tabelle ergänzt, die Mode 1 / Mode 2 / Mode 3-Backlog / Mode 2 (Self-Read) anhand konkreter Plan-Beispiele abgrenzt:
- Mode 1: RecordingModule.reduce setzt `recording = Preparing` + Effect `AllocateMediaRecorder` — Achse + Effects gehören zur eigenen Verantwortung.
- Mode 2: ViewModeModule setzt `viewMode = KEYBOARD`; LayoutModule reagiert via `onCrossModuleStateChange` → `LayoutAction.SetSmallMode(true)` — Cross-Module-Cascade, SRP-konform.
- Mode 3 (NICHT verwenden): `ViewModeModule.reduce` setzt `viewMode + layout.smallMode + overlay.userPrefersWidget` gleichzeitig — SRP-Bruch, Phase-2-Backlog.
- Mode 2 (Self-Read): RecordingModule.onCrossModuleStateChange liest `prev.recording vs next.recording` → cascadiert `OverlayAction.ResetSuppressBit` — Self-Read folgt KG-RSB-3-Konvention.

Plus: Code-Review-Pflicht-Klausel ("Reducer mutiert zwei verschiedene Sub-State-Achsen → Mode-3-Verstoß") und Cross-Link auf §15.1.x Coupling-Matrix-Konvention.

**Plan-Edit-Stelle:** Spec 1 §15.5 (~Z. 6673), Anti-Beispiel-Tabelle-Block (~30 Zeilen).

### Finding 4 (IMPORTANT): Idempotenz-Subscriber-Verhalten nicht explizit dokumentiert

**Was vorher war:** Spec 3 §4.8 OverlayModule.reduce-Snippet (Z. 890–898) erklärte die Idempotenz semantisch (TransitionResult statt null → Applied statt Rejected), aber dokumentierte nicht, was mit StateFlow-Subscribern passiert, wenn `state.copy(suppressAutoOverlayUntilNextSession = false)` mit bereits `false`-Wert ein neues `OverlayState`-Objekt mit identischer struktureller Gleichheit erzeugt.

**Bug-Risiko:** Subscriber-Implementer in Block 6 könnte fragen "warum kommt mein OverlayBackend.render() bei Re-Reset nicht doppelt?" und sich auf nicht-dokumentiertes StateFlow-Verhalten verlassen — würde durch späteres MutableStateFlow-Wrapping gebrochen.

**Korrektur:** Reducer-Snippet-Kommentar in Spec 3 §4.8 um Subscriber-Verhalten-Absatz erweitert: "StateFlow-Subscriber-Verhalten: MutableStateFlow.update unterdrückt Emission bei strukturell gleicher data class — kein Re-Render-Overhead, keine doppelte Telemetrie".

**Plan-Edit-Stelle:** Spec 3 §4.8 Z. 890–898 (Kommentar erweitert um ~8 Zeilen).

### Finding 5 (MINOR, NOTED ohne Plan-Edit): `OverlayAction.ResetSuppressBit` als `object`-Singleton + ProGuard-Cross-Link

**Was vorher war:** Spec 2 §3.3 (Z. 293–301) dokumentiert ausführlich, *warum* `ResetSuppressBit` ein `object` ist (Naming-Konsistenz, Sealed-Leaves-Routing, Test-Assertions). Aber **nicht** explizit: wie verhält sich die `object`-Subtype unter der S-4-F-1-ProGuard-Keep-Regel `-keep,allowobfuscation class * extends Action`?

**Antwort (kein neuer Bug):** Pattern matched jeden Subtyp inkl. `object`-Singletons. ProGuard `allowobfuscation` erlaubt Namens-Verkürzung, aber `KClass`-Reference bleibt intakt — `sealedSubclasses`-Reflection operiert auf Class-Hierarchie, nicht auf Namen. Existierende ProGuard-Regel in Spec 1 §4.3 (Z. 813–828) deckt `object`-Subtypen vollständig ab.

**Korrektur:** Kein Spec-Eingriff nötig. Ein S-4-Reviewer könnte kurz irritiert sein, ob `object ResetSuppressBit : OverlayAction()` (kein Konstruktor, kein Body) ein Sonderfall ist — Antwort ist nein, aber impliziter Cross-Link wäre clarification-stärker. Da Wirkungs-Bias gering ist und Block-1b `OrchestratorReleaseSmokeTest.kt` eine Release-Build-Verifikation enthält, ist der Patch-Wert gering. **MARK-as-NOTED.**

---

## Plan-Edits — Übersicht

**4 Operations in 3 Dateien:**

| Datei | Sektion | Edit | Finding |
|---|---|---|---|
| `research/1-pipeline-service/1-pipeline-service.reviewed.md` | §4.3 Step 5 | ASCII-Box-Banner statt 6-Zeilen-FIX-Kommentar (~22 Zeilen) | F-2 |
| `research/1-pipeline-service/1-pipeline-service.reviewed.md` | §15.5 | Anti-Beispiel-Tabelle Mode 1/2/3/Self-Read + Code-Review-Pflicht (~30 Zeilen) | F-3 |
| `research/3-floating-overlay/3-floating-overlay.reviewed.md` | §4.8 OverlayModule.reduce | Subscriber-Verhalten-Kommentar (~8 Zeilen) | F-4 |
| `research/3-floating-overlay/3-floating-overlay.reviewed.md` | §7.3 T1+T2 | Cross-Axis-Mutation auf Mode-2-Cascade umgestellt (~125 Zeilen) | F-1 |
| `dictate-keyboard-layout-refactor.reviewed.md` | §9 Iter-Log | S-9-Eintrag (1 Zeile) | (alle) |

**Acceptance unverändert:** R.RSB-FIX-A Regression-Test + Suppress-Bit-Lifecycle-Bullets in Spec 1 §10 und Spec 3 §10 sind ausreichend für S-9. §7.3-Refactor ist Notation/Doku-Konsistenz, nicht neue Funktionalität — gleiche Implementierung, klarere Architektur-Darstellung.

**Spec 2 unverändert** — S-9-Action-Definition lebt in §3.3 als SoT und ist bereits konsistent (Z. 280–303 dokumentiert `OverlayAction.ResetSuppressBit` als `object` mit KDoc-Begründung, Idempotenz-Hinweis, Naming-Konsistenz).

---

## Top-Insight

Der Surprise-Finding-#3-Fall ist ein lehrreiches Beispiel für **interne Notations-Drift in einer einzelnen Spec-Datei**: §6.1 und §7.3 zeigten denselben State-Übergang (`WIDGET → KEYBOARD via ToggleViewModeWidget`) in zwei verschiedenen Reducer-Formen kodifiziert. §6.1 ist Mode-2-konform (Cross-Module-Cascade); §7.3 ist Mode-3 (Cross-Axis-Mutation, eigentlich Phase-2-Backlog). Beide Sektionen sind Doku zur gleichen Implementation, aber sie widersprechen sich struktturell.

Ohne S-9-Audit hätte ein Implementer eine 50%-Chance gehabt, die SRP-verletzende Form zu wählen. Bug-Klasse: nicht direkt Code-brechend (beide Formen würden compilen), aber architektur-zerstörend — SRP-Verletzung würde die saubere Layered-Architektur unterminieren und in Phase-2 Mode-3-Migration (atomic-cross-axis-update als Pattern) blockieren.

**KG-RSB-2 selbst (Self-Filter-Bug) bleibt robust gehärtet:** Regression-Test (R.RSB-FIX-A) + ASCII-Box-Banner in §4.3 sind belt-and-suspenders. Bei einem Bug dieser Schwere (HOVER-Auto-Reopen permanent kaputt nach erstem Close-Klick — User-sichtbarer Production-Bug) ist Doppel-Sicherung angemessen.

**S-9-Resolution-Status:** Alle drei KG-RSB-Marker (KG-RSB-1, KG-RSB-2, KG-RSB-3) waren bereits RESOLVED. S-9-Audit hat keine NEUEN Production-Bugs gefunden, sondern (a) eine Notations-Drift im Spec 3 entlarvt (Finding 1) und (b) bestehende Sicherungen gehärtet (Findings 2+3) und Doku-Klarstellungen ergänzt (Findings 4+5). S-9 ist damit bereit für Block 4 (RecordingModule-Implementation) und Block 6 (OverlayModule-Implementation).

---

## Cross-Refs zu Vorgänger-Reports

- **S-1 F-1** (Sub-State-Pfad-Drift): nicht direkt S-9-relevant — OverlayState-Struktur in Spec 2 §13.5 verwendet jetzt hierarchische Pfade, S-9-Edits referenzieren `state.overlay.suppressAutoOverlayUntilNextSession` konsistent.
- **S-3 F-1** (EffectFailure mit originModuleId): nicht S-9-relevant — `OverlayAction.ResetSuppressBit` wird nie failen können (kein SideEffect, reine State-Mutation).
- **S-4 F-1** (ProGuard-Keep-Regel für sealedSubclasses): direkter Cross-Link in S-9-F-5 (NOTED ohne Plan-Edit) — ProGuard-Pattern deckt `object`-Subtypen ab.
- **S-4 F-2** (Init-Sanity-Check Vollständigkeit): nicht direkt S-9-relevant — `OverlayAction` ist als `actionClass` von OverlayModule beansprucht; Vollständigkeits-Check verifiziert das.
- **S-4 F-5** (Cascade-Order der 13 Module): direkter Cross-Link in S-9-Achse-7c — RecordingModule kommt vor OverlayModule, aber `ResetSuppressBit` läuft rekursiv via depth+1-Dispatch (nicht via Outer-Iteration), daher robust gegen Reorder.
- **S-7 F-7** (RecordingModule.reduceFailure für AllocateMediaRecorder): nicht direkt S-9-relevant — `ResetSuppressBit` triggert keinen MediaRecorder-Effect.

---

**Status: S-9 RESOLVED. 5 Findings, 4 Plan-Edits, Subsystem ist implementation-ready für Block 4 + Block 6.**
