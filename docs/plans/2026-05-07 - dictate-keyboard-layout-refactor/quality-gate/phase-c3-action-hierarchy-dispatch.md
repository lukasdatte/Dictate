# Phase C-3 — Action-Hierarchie + Dispatch + EffectFailure Kohärenz-Review

**Erstellt:** 2026-05-14
**Reviewer:** Phase-C-Agent C-3
**Plan-Version vor Edits:** Commit `2a032e3` (Phase-C-1 abgeschlossen) + Phase-C-2-Apply (10 Plan-Edits in Spec 1 §6 + §7 + §11)
**Scope:**
- Spec 1 §4.2 (DictateModule-Interface: `actionClass`, `reduce`, `runEffect`, `reduceFailure`, `onCrossModuleStateChange`)
- Spec 1 §4.3 (DictateOrchestrator.dispatch + dispatchInternal: Step 1a EffectFailure-Routing, Step 2 Reducer-vs-reduceFailure-Dispatch, Step 5–6 Cross-Module-Cascade + MAX_CASCADE_DEPTH)
- Spec 1 §4.7 (ModuleServices.emitAction-Konvention) + §4.8 (DictateModuleRegistry: `moduleByLeafClass`-Routing, Vollständigkeits-Check)
- Spec 1 §4.10 (Kontrakt) + §5 (LocalBinder.dispatch)
- Spec 1 §15.2 (RecordingModule: `actionClass`, `reduce`, `runEffect`, `reduceFailure`-Hook) + §15.3 (AudioModule.onCrossModuleStateChange) + §15.5 (Cross-Module-Effect-Modi) + §15.6 (KeyboardInputModule)
- Spec 1 §10 Block-2-Acceptance: MediaRecorder-release-Pfad (FIX Issue 3.0.11)
- Spec 1 §13.5.a G6 (Service-Death während aktivem Recording)
- Spec 2 §3.2 (ButtonSlot.actionResolver-Signatur) + §3.3 (Action-Sealed-Hierarchie inkl. EffectFailure) + §6 (ImeViewBackend.wireStaticHandlers Click-Listener)
- Spec 2 §8.4 (KEYBOARD_REPROCESS_STAGING-Slot-Definitionen) + §8.5 (Resolver-Helfer) + §9.6 (resend-Mutation-Migration)
- Spec 3 (Action-Refs in OverlayBackend, OverlayModule — Cross-Verifikation, kein Inhalt-Drift entdeckt)

**Cross-Spec-Verifikation:** **Pflicht** — C-3-Scope ist intrinsisch cross-spec (Action-Hierarchie lebt in Spec 2 §3.3, wird in Spec 1 §4.3 geroutet, in Spec 1 §15.x von Modul-Implementierungen beansprucht, und in Spec 2 §6/§8 + Spec 3 §3.1/§4.2/§7.3 von Slot-Resolvern und Cross-Module-Observern produziert). C-3 hat alle drei Specs auf Action-Naming-/EffectFailure-Konsistenz gegengeprüft.

**Vorgänger-Anker:** C-1 hat KeyboardInputModule + DictateModule-Interface-Surface (7+4 Methoden) homogenisiert; C-2 hat Service-Layer/Persistence/Lifecycle finalisiert und eine **explizite Cross-Reference** an C-3 hinterlassen (F-3 in C-2: `CancelPipeline` vs `CancelRecording` für den §7.3-onDestroy-Pre-Cancel-Block).

---

## Summary

Der Action-Hierarchie-/Dispatch-/EffectFailure-Bereich ist nach allen Phase-B + Phase-C-1/C-2-Edits **architektonisch tragfähig** (Sealed-Leaves-Indexing + EffectFailure-Origin-Routing + MAX_CASCADE_DEPTH + Self-Cascade-Vertrag + Frozen-Snapshot-Cascade sind robust), hatte aber drei **Cluster** an Innen-Drift:

1. **Action-Naming-Disambiguation für den MediaRecorder-Release-Pfad** (Cross-Reference aus C-2 F-3): §10 Acceptance + §13.5 G6 referenzierten `Action.PipelineAction.CancelPipeline`, das aber an PipelineModule routet — Recording-Hardware-Release-Effect lebt jedoch in `RecordingModule.Effect.ReleaseMediaRecorder`. Plus: §13.5 G6 erfindet ein fiktives `Effect.ReleaseRecording`, das in keiner Modul-Effect-Liste existiert.
2. **EffectFailure-Mechanik-Drift in Spec 2 §3.3**: Die KDoc behauptet, das Modul reagiere "in seinem eigenen Reducer-Arm `is Action.EffectFailure -> …`" — aber Phase-B S-3 hat den separaten `reduceFailure(...)`-Hook eingeführt (Spec 1 §4.2). Plus die KDoc enthält den stalen Z. 617-Ref aus C-1 F-5.
3. **Effect-Identifier-Matching-Bug in `RecordingModule.reduceFailure` (§15.2)**: Der Failure-Hook vergleicht `failure.effect == "AllocateMediaRecorder"`, aber der Orchestrator (§4.3 Step 4) füllt das Feld per `effect.toString()` — Kotlin-`data class.toString()` enthält die Property-Werte (`"AllocateMediaRecorder(target=…, useBluetooth=…, audioFile=…)"`), wodurch der exakte String-Match **NIE** zutrifft. Folge: Allocate-Failure-Rollback ist silent-toter Code, Recording bliebe für immer in `Preparing` hängen.

Plus zwei kleinere Lese-Anchor-Drifts (F-5-Pattern fortgesetzt: Z. 205/206/250 in Spec 2 §8.4 + §9.6) und eine **fehlende explizite Dokumentation** der Resolver-`null`-Semantik als strukturelle Verhinderung von `DispatchOutcome.Unrouted` (offene Frage aus C-1 F-6).

**8 Findings (3 Critical, 4 Important, 1 Minor); 10 Plan-Edits** (Spec 1: 4 — §10 + §13.5 + §7.3 + §15.2; Spec 2: 5 — §3.2 + §3.3 + §6 + §8.4 (zwei Stellen) + §9.6; Hauptplan: 1 — Iter-Log-Eintrag; Spec 3 unverändert).

---

## Findings + Applied Fixes

### F-1 (CRITICAL) — C-2 F-3 Cross-Reference: Action-Naming `CancelPipeline` → `CancelRecording` für MediaRecorder-Release-Pfad

**Symptom:** §10 Acceptance Block-2 ("MediaRecorder-release-Pfad", FIX Issue 3.0.11) und §13.5 G6 Pfad A verlangen, dass `Service.onDestroy` bei aktivem Recording `orchestrator.dispatch(Action.PipelineAction.CancelPipeline)` ruft → `recordingManager.release()`. Aber:

- `Action.PipelineAction.CancelPipeline` wird vom Sealed-Leaves-Indexing in §4.3 an **`PipelineModule`** geroutet (`moduleByLeafClass[Action.PipelineAction.CancelPipeline::class] = PipelineModule`, weil `PipelineModule.actionClass = Action.PipelineAction::class`).
- `PipelineModule` hält die Pipeline-State-Achse (DB-Status, Job-Submission, ReprocessStaging) — Recording-Hardware ist **nicht** seine Domäne.
- `Effect.ReleaseMediaRecorder` ist als `RecordingModule.Effect.ReleaseMediaRecorder` deklariert (§15.2) — PipelineModule kann ihn nicht emittieren.

**Folge:** Wenn `Service.onDestroy` `CancelPipeline` dispatcht, läuft der Pipeline-Module-Reducer, setzt `pipeline = Idle`, emittiert ggf. einen DB-Status-Update-Effect — aber der MediaRecorder bleibt allokiert. Der Native-Heap-Leak (G6) ist **nicht** durch die geplante Action gefixt.

**Disambiguation-Entscheidung (Phase-C C-3):** Die korrekte Action ist **`Action.RecordingAction.CancelRecording`**. Begründung:
- Recording-Hardware-Lifecycle gehört per SRP zu RecordingModule (§15.2 hält `Effect.ReleaseMediaRecorder` + `Effect.DeleteAudioFile`).
- `RecordingModule.reduce` hat **drei Reducer-Arme** für `CancelRecording` (Preparing+CancelRecording, Active+CancelRecording, Paused+CancelRecording), die alle synchron `Effect.ReleaseMediaRecorder` emittieren — der Pfad ist bereits implementiert, kein neuer Code nötig.
- Die SRP-konforme Trennung ist auch für Tests klar: `RecordingManagerReleaseTest` mockt nur RecordingModule + ModuleServices, kein Pipeline-Domain-State.

**Fix:** Drei zusammenhängende Updates:
1. **§10 Acceptance-Klausel** auf `Action.RecordingAction.CancelRecording` umgestellt + komplementäre Klausel für Pipeline-Cancel bei `state.pipeline !is Idle` ergänzt (Domain-Trennung explizit).
2. **§13.5 G6 Pfad-A-Mitigation** auf `Action.RecordingAction.CancelRecording` + `Effect.ReleaseMediaRecorder` umgestellt (siehe F-2).
3. **§7.3 onDestroy-Pre-Cancel-Block-Kommentar** verlässt den TODO-Marker-Status: Die Disambiguation ist getroffen, der State-Switch-Code (Recording-Priorität, dann Pipeline) bleibt im Snippet als auskommentierter Implementer-Anker, der FIX-Kommentar dokumentiert die Entscheidung mit Cross-Refs zu §10/§13.5/§15.2.

**Edit:** Spec 1 §10 Block-2-Acceptance MediaRecorder-Pfad-Klausel + neue komplementäre Pipeline-Cancel-Klausel; §13.5 G6 Mitigation; §7.3 `onDestroy`-FIX-Kommentar ergänzt.

---

### F-2 (CRITICAL) — §13.5 G6 referenziert fiktiven `Effect.ReleaseRecording` (existiert in keiner Modul-Effect-Liste)

**Symptom:** §13.5 G6 Mitigation Pfad A sagt wörtlich: *"der `PipelineModule`-Reducer/EffectHandler emittiert `Effect.ReleaseRecording`"*. Aber:

- `PipelineModule.Effect` (sealed interface) enthält nur Pipeline-Effects (`PersistStatus`, `MarkInserted`, `ConfirmInsertion`, …) — kein Recording-Release.
- `RecordingModule.Effect` (sealed interface, §15.2) enthält `ReleaseMediaRecorder` (und NICHT `ReleaseRecording`).
- Im gesamten Plan kommt der String `Effect.ReleaseRecording` nur an dieser einen Stelle vor — es ist ein **erfundener Effect**.

**Folge:** Ein Implementer, der die G6-Mitigation als Spec liest, sucht nach `Effect.ReleaseRecording`, findet nichts, und improvisiert (entweder Effect anlegen oder gegen `RecordingManager` direkt rufen — beides Drift gegen die Plan-Architektur).

**Fix:** §13.5 G6 Pfad-A-Mitigation auf:
- Action: `Action.RecordingAction.CancelRecording` (statt `Action.PipelineAction.CancelPipeline`) — siehe F-1.
- Reducer-Owner: `RecordingModule` (statt `PipelineModule`).
- Effect: `Effect.ReleaseMediaRecorder` (statt `Effect.ReleaseRecording`).
- Plus optional `Effect.DeleteAudioFile` (RecordingModule emittiert beide bei `CancelRecording`).

Der FIX-Kommentar dokumentiert die drei Korrekturen explizit.

**Edit:** Spec 1 §13.5.a G6-Tabellen-Mitigation.

---

### F-3 (CRITICAL) — RecordingModule.reduceFailure: `failure.effect == "AllocateMediaRecorder"` matched NIE (data-class-`toString()`-Bug)

**Symptom:** §15.2 `RecordingModule.reduceFailure` vergleicht den Effect-Identifier per exaktem String-Match:

```kotlin
failure.effect == "AllocateMediaRecorder" && state is RecordingState.Preparing -> …
```

Der Orchestrator (§4.3 Step 4) füllt `Action.EffectFailure.effect` jedoch per `effect.toString()`:

```kotlin
dispatchInternal(
    Action.EffectFailure(
        originModuleId = typedModule.id,
        effect = effect.toString(),
        reason = t.message ?: t.javaClass.simpleName,
    ),
    depth + 1,
)
```

Für `Effect.AllocateMediaRecorder(val target, val useBluetooth, val audioFile)` (`data class`) liefert Kotlin-`toString()` `"AllocateMediaRecorder(target=…, useBluetooth=…, audioFile=…)"` — der exakte Match `== "AllocateMediaRecorder"` ergibt **immer `false`**.

(Für `Effect.StopMediaRecorder` (`object`) liefert `toString()` den Simple-Name `"StopMediaRecorder"` — exakter Match ist korrekt.)

**Folge:**
- Der Allocate-Failure-Rollback-Arm ist silent-toter Code.
- Ein gefangener `MediaRecorder.prepare()`-Throw (externer Cache-Wipe, MIC-Permission entzogen) routet zur Default-`else -> null`-Klausel → `Rejected("reducer-null")`.
- `state.recording` bleibt für immer in `RecordingState.Preparing(audioFile=…)` hängen — der User sieht den "Preparing"-Spinner endlos.
- Bug-Klasse ist subtil: Tests, die den `reduceFailure`-Arm direkt aufrufen (ohne `effect.toString()`-Roundtrip), würden grün laufen.

**Fix:** Effect-Identifier-Matching auf `failure.effect.startsWith("AllocateMediaRecorder(")` umgestellt (Prefix-Match für data-class-toString()-Format) + Convention-Doku als FIX-Kommentar. Cross-Doku in Spec 2 §3.3 EffectFailure-KDoc ergänzt (siehe F-5), damit zukünftige Module-Implementierungen das Pattern kennen.

**Edit:** Spec 1 §15.2 RecordingModule.reduceFailure-Arm + Convention-FIX-Kommentar; Spec 2 §3.3 EffectFailure-KDoc (siehe F-5).

---

### F-4 (IMPORTANT) — Spec 2 §3.3 EffectFailure-KDoc: stale `Spec 1 §4.3 Z. 617`-Ref (F-5-Pattern aus C-1)

**Symptom:** Die KDoc des `EffectFailure`-Data-Class trägt den Anker:

> **Routing-Konvention (Spec 1 §4.3 Z. 617):** EffectFailure trägt die [originModuleId] …

Phase-B-S-3 + Phase-C-1 + Phase-C-2-Apply haben Spec 1 §4.3 mehrfach erweitert (Cascade-Order-Block, ProGuard-Block, KeyboardInput-Routing, reduceFailure-Hook); Z. 617 zeigt nicht mehr auf den EffectFailure-Routing-Block.

**Folge:** Identisch zu C-1 F-5: ein Reviewer folgt der Z.-Ref, landet im falschen Code-Snippet, schließt auf "Plan ist inkonsistent".

**Fix:** Z. 617 → Section-Anchor `"§4.3, EffectFailure-Pfad `dispatchInternal` Step 1a + 2"`.

**Edit:** Spec 2 §3.3 EffectFailure-KDoc.

---

### F-5 (IMPORTANT) — Spec 2 §3.3 EffectFailure-KDoc: stale Reducer-Arm-Prosa (vor Phase-B S-3 reduceFailure-Hook)

**Symptom:** Die KDoc behauptet:

> Module ohne expliziten `EffectFailure`-Reducer-Arm geben `null` aus `reduce()` zurück …
> … das Modul reagiert in seinem eigenen Reducer-Arm `is Action.EffectFailure -> …` und kann den State-Rollback / Failure-Marker setzen.

Aber Phase-B S-3 hat den **separaten Hook `reduceFailure(state, failure, ctx)`** auf dem `DictateModule`-Interface eingeführt (Spec 1 §4.2). §4.3 Step 2 ruft ihn explizit getrennt von `reduce(...)`:

```kotlin
val result = if (action is Action.EffectFailure) {
    typedModule.reduceFailure(subState, action, ctx)
} else {
    typedModule.reduce(subState, action, ctx)
} ?: …
```

**Begründung für die Trennung (laut §4.2 KDoc):** `reduce`'s Action-Parameter hat den Modul-spezifischen Typ `A` (z.B. `Action.RecordingAction`); `Action.EffectFailure` ist ein direkter `Action`-Subtyp, **keine** `RecordingAction` — ein Reducer-Arm `is Action.EffectFailure ->` im regulären `reduce` wäre type-unsicher.

**Folge:** Ein Implementer, der die §3.3-KDoc als Quelle für "wie reagiere ich auf EffectFailure" liest, baut den Failure-Arm in `reduce(...)` ein — entweder als Compile-Error (Type-Mismatch gegen `A`) oder als silent-no-op (wenn er den Type-Cast-Workaround wählt).

**Fix:** KDoc auf den separaten `reduceFailure`-Hook umgestellt + ISP-Begründung dokumentiert + Cross-Ref auf Spec 1 §4.2.

**Edit:** Spec 2 §3.3 EffectFailure-KDoc (zusammen mit F-4 + F-6-Doku).

---

### F-6 (IMPORTANT) — Spec 2 §3.3 EffectFailure-KDoc: Effect-Identifier-Konvention für `object` vs. `data class` fehlt

**Symptom:** Die KDoc beschreibt die Routing-Konvention, aber dokumentiert nicht, dass `effect: String` per `effect.toString()` befüllt wird und dass Kotlin-`data class.toString()` die Property-Werte enthält (siehe F-3). Ein zukünftiger Modul-Autor mit einem data-class-Effect würde denselben naiven String-Match-Bug wie F-3 reproduzieren.

**Fix:** Convention-Block in der KDoc ergänzt:
- `object`-Effects → exakter Match (`failure.effect == "ReleaseMediaRecorder"`).
- `data class`-Effects → Prefix-Match (`failure.effect.startsWith("AllocateMediaRecorder(")`).
- Alternative: typisierter Effect-Discriminator (Backlog-Hinweis).

**Edit:** Spec 2 §3.3 EffectFailure-KDoc (in einem zusammenhängenden Block mit F-4 + F-5).

---

### F-7 (IMPORTANT) — Slot-Resolver-`null`-Semantik als strukturelle Verhinderung von `DispatchOutcome.Unrouted` nicht dokumentiert (C-1 F-6-Offene-Frage)

**Symptom:** Spec 2 §3.2 `ButtonSlot.actionResolver`-KDoc dokumentiert "`null` bedeutet: Click ist im aktuellen State unbedeutend", aber nicht **wo** das `null` aussortiert wird (im Click-Handler per `?.let`) und dass damit die Action den Orchestrator nie erreicht → kein `DispatchOutcome.Unrouted`/`Rejected`-Log-Pfad. C-1 F-6 hat das als offene Frage an C-3 weitergegeben: prüfen, ob die Resolver-`null`-Semantik klar verankert ist.

**Folge:** Ein Implementer könnte ein Modul ohne Reducer-Arm für eine Resolver-erzeugbare Action schreiben und auf Telemetry-Logs warten, die aber nie kommen (weil `null` strukturell vor dem Dispatch herausgefiltert wird). Plus: Performance-Tests könnten fälschlich annehmen, dass jeder Click einen Dispatch produziert.

**Fix:** Zwei Lese-Anchor:
1. **§3.2 ButtonSlot.actionResolver-KDoc** explizit erweitert: `null`-Return wird vom Click-Handler (§6 wireStaticHandlers, `slot.actionResolver(s, services)?.let { onAction?.invoke(it) }`) aussortiert; die Action erreicht den Orchestrator nie; kein `DispatchOutcome.Rejected`/`Unrouted`-Log-Spam für unsinnige Clicks (Cooldown, Wrong-State); Resolver sind die **erste** Validierungs-Schicht, der Reducer ist die zweite.
2. **§6 ImeViewBackend.wireStaticHandlers** Cross-Ref-FIX-Kommentar zur §3.2-KDoc, damit die `?.let`-Filter-Site selbst dokumentiert ist.

**Edit:** Spec 2 §3.2 ButtonSlot.actionResolver-KDoc; §6 wireStaticHandlers-FIX-Kommentar.

---

### F-8 (MINOR) — Spec 2 §8.4 + §9.6: stale `Z. 205/206/250` Intra-Spec-Refs (F-5-Pattern fortgesetzt)

**Symptom:** Drei inline-Kommentare in Spec 2 referenzieren Action-Definitionen per Zeilennummer auf §3.3:
- §8.4 Z. 1320: `"SendStaging-Action (Spec 2 §3.3 Z. 205)"`
- §8.4 Z. 1350: `"CancelReprocessStaging (Spec 2 §3.3 Z. 206)"`
- §9.6 Z. 1708: `"MarkLastAudio in Spec 2 §3.3 Z. 250"`

Zwar zeigen die Refs aktuell noch auf die korrekten Definitionen (Verify: `grep` confirmed Z. 205/206/250), aber sie sind **fragil** gegen jede zukünftige Erweiterung der Action-Sealed-Class (z.B. ein zusätzlicher Sub-Action-Eintrag verschiebt alles ab Z. N um +1).

**Folge:** Identisch zu C-1 F-5: bei späteren Spec-Edits (z.B. wenn `Action.LayoutAction.SetFooBar` ergänzt wird) verschieben sich Z. 205/206/250 + jede `grep`-basierte Verifikation findet die Action mit der KOrrekten Z.-Ref nicht mehr → Reviewer-Confusion.

**Fix:** Alle drei Refs auf Action-Name-Anchor umgestellt (Pattern aus C-1 F-5):
- `"Spec 2 §3.3 Z. 205"` → `"Spec 2 §3.3 PipelineAction.SendStaging"`
- `"Spec 2 §3.3 Z. 206"` → `"Spec 2 §3.3 PipelineAction.CancelReprocessStaging"`
- `"Spec 2 §3.3 Z. 250"` → `"Spec 2 §3.3 ResendAction.MarkLastAudio"`

**Edit:** Spec 2 §8.4 (zwei Stellen) + §9.6.

---

## Plan-Edits (Audit-Trail)

| Datei | Sektion | Art | Kurzbeschreibung |
|---|---|---|---|
| Spec 1 §7.3 | onDestroy-Pre-Cancel-FIX-Kommentar | Update | C-2-TODO-Marker durch finale Action-Naming-Disambiguation ersetzt (C-3-F-1) |
| Spec 1 §10 | Block-2-Acceptance MediaRecorder-Pfad | Update | `Action.PipelineAction.CancelPipeline` → `Action.RecordingAction.CancelRecording`; komplementäre Pipeline-Cancel-Klausel ergänzt (F-1) |
| Spec 1 §13.5.a | G6 Mitigation-Tabelle | Update | Action + Reducer-Owner + Effect-Name korrigiert: `CancelPipeline` → `CancelRecording`, `PipelineModule` → `RecordingModule`, `Effect.ReleaseRecording` (fiktiv) → `Effect.ReleaseMediaRecorder` (F-1 + F-2) |
| Spec 1 §15.2 | RecordingModule.reduceFailure-Arm | Update | `failure.effect == "AllocateMediaRecorder"` → `failure.effect.startsWith("AllocateMediaRecorder(")` + Convention-FIX-Kommentar (F-3) |
| Spec 2 §3.2 | ButtonSlot.actionResolver-KDoc | Update | `null`-Semantik explizit als strukturelle Verhinderung von `DispatchOutcome.Unrouted`/`Rejected`-Log-Spam dokumentiert (F-7) |
| Spec 2 §3.3 | EffectFailure-Data-Class-KDoc | Update | Z. 617 → Section-Anchor (F-4); `reduce`-Arm-Prosa → `reduceFailure`-Hook (F-5); Effect-Identifier-Konvention für `object` vs. `data class` ergänzt (F-6) |
| Spec 2 §6 | ImeViewBackend.wireStaticHandlers | Insert | FIX-Kommentar dokumentiert `?.let { onAction?.invoke(it) }` als `null`-Aussortierung mit Cross-Ref auf §3.2 (F-7) |
| Spec 2 §8.4 | RECORD-Slot SendStaging-Comment | Update | `Z. 205` → Action-Name-Anchor `PipelineAction.SendStaging` (F-8) |
| Spec 2 §8.4 | TRASH-Slot CancelReprocessStaging-Comment | Update | `Z. 206` → Action-Name-Anchor `PipelineAction.CancelReprocessStaging` (F-8) |
| Spec 2 §9.6 | resend-Mutation-Tabelle | Update | `Z. 250` → Action-Name-Anchor `ResendAction.MarkLastAudio` (F-8) |
| Hauptplan §9 | Iteration-Log | Insert | "2026-05-14 — Phase-C Quality-Gate C-3"-Entry mit 8 Findings + Plan-Edits-Summary |

**Gesamt:** 11 Operations in 3 Dateien (Spec 1: 4; Spec 2: 6; Hauptplan: 1). Spec 3 unverändert — die Action-Refs in Spec 3 (Overlay-Backend, OverlayModule) verwenden bereits durchgängig die hierarchische Form (`Action.OverlayAction.X`, `Action.ViewModeAction.Y`) und enthalten keine stalen Z.-Refs.

---

## Offene Fragen für nachfolgende Agents

### Für C-4 (Layout/View-Rendering — Spec 2)

- **F-7-Hinweis:** Die in dieser Phase geklärte Resolver-`null`-Semantik ist jetzt in §3.2 + §6 wireStaticHandlers verankert. C-4 sollte beim Layout-Render-Pass prüfen, ob die `OverlayBackend`-Click-Sites (Spec 3 §4.2) das gleiche `?.let`-Pattern verwenden (Cross-Spec-DRY-Check) — wenn ja, ist der `null`-Filter-Pfad auch dort korrekt.
- **`Action.ViewModeAction.OnImeViewShown / OnImeViewHidden`-Dispatch-Pfad:** Spec 2 §3.3 + Spec 3 nutzen ViewModeAction-Refs durchgängig hierarchisch; C-4 muss prüfen, ob die Layout-Render-Sites in Spec 2 §11 + §8 die ViewModeAction-Cascade auf `LayoutAction.SetSmallMode` (siehe §15.5 Anti-Beispiel-Tabelle Zeile 2) korrekt anchored haben.
- **Atomic Cross-Axis (Mode 3) ist explizit Phase-2-Backlog** (§15.5) — C-4 sollte verifizieren, dass keine §6/§7/§8-Reducer-Sites versehentlich zwei Sub-State-Achsen in einem Reducer-Aufruf mutieren (Code-Review-Pflicht aus §15.5 Anti-Beispiel-Tabelle).

### Für C-5 (Floating-Overlay — Spec 3)

- **F-1-Cross-Reference (geerbt aus C-2 + jetzt aufgelöst):** Die Action-Disambiguation `CancelRecording` für den Recording-Hardware-Release-Pfad ist final. C-5 sollte prüfen, ob Spec 3 §6.1 + §7.3 (Overlay-FSM, T1–T7) die `RecordingAction.CancelRecording`-Subhierarchie korrekt verankern — insbesondere für den Schließen-Button im HOVER-Modus, dessen Resolver in §6.1 auf `Action.RecordingAction.CancelRecording` setzt (verifiziert: §3.1 Z. 104 zeigt das bereits).
- **`EffectFailure`-Konvention in OverlayModule:** OverlayModule (Spec 3 §4.8) hat einen `reduce(...)`-Block für `Action.OverlayAction.*`, aber **keinen** `reduceFailure`-Override. Das ist konsistent mit dem Default `null`-Hook (Spec 1 §4.2) — Overlay-Effects (`PersistOverlayPosition`, `MarkOnboardingShown`) sind alle idempotente Pref-Writes, ein Failure-Rollback ist hier semantisch nicht nötig. C-5 sollte das explizit als Design-Entscheidung dokumentieren, sonst entsteht ein zukünftiger False-Positive-Finding "OverlayModule fehlt reduceFailure".
- **DispatchOutcome-Telemetry (Phase-2-Backlog laut C-1 F-6):** C-5 sollte die in Phase 2 fällige Verifikation prüfen, dass jede `pipeline?.dispatch(Action.X)`-Site im neuen Spec-3-Onboarding-/Permission-Flow (§5.0 + §5.2) gegen `Unrouted` abgesichert ist (Lint-Check oder Acceptance-Klausel).

### Für C-State (State-File-Konsistenz)

- Plan-State-File (`plan-review/state.md`) ist seit Phase-1-Abschluss nicht aktualisiert (unverändert zum C-2-Hinweis). Beim Phase-2/Phase-5-Plan-Archive-Schritt sollte das State-File auf den tatsächlichen Phase-A/B/C-Workflow umgestellt werden.

---

**Reviewer-Note:** Das C-3-Finding-Cluster hat eine **neue Achse** gegenüber C-1/C-2:

- **C-1 Drift-Echo-Muster:** Phase-B-Edits an Counter-Sites (Modul-Anzahl) blieben in 6+ Lese-Anchor-Sites stale.
- **C-2 Drift-Echo-Muster:** Phase-B-Edits an Vertrags-Layer-Konventionen (NOTIF_ID, F-11-Rename, R.17-Persistenz) zogen Cross-Refs nicht synchron.
- **C-3 hat zusätzlich einen *Cross-Spec-Reducer-Logik*-Bug entdeckt (F-3):** der Effect-Identifier-Match-Bug in `RecordingModule.reduceFailure` ist nicht Drift-induziert, sondern entsteht aus der Wechselwirkung zwischen Spec 2 §3.3 (`EffectFailure.effect: String`-Definition + Orchestrator-`toString()`-Encoding) und Spec 1 §15.2 (RecordingModule-Reducer-String-Match). Solche Cross-Spec-Reducer-Bugs sind **nicht durch Drift-grep findbar** — sie brauchen einen End-to-End-Trace durch zwei Specs hindurch. Das ist der Wert des C-Achsen-Mandats: einzelne Spec-internal-Reviews (Phase B) hätten den Bug nicht gefangen, weil jede Spec für sich kohärent war.

Plus: die **C-2-Cross-Reference (F-3 von C-2)** war im Spec gut markiert — der TODO-Marker im §7.3-onDestroy-Snippet hat genau die Disambiguation-Pflicht an C-3 weitergereicht, die jetzt aufgelöst ist. Der Plan-Review-Workflow (C-2 → C-3 → C-4/C-5) funktioniert: explizite Cross-References (statt "vergessen") sind die Mechanik, die das **architektonisch korrekte** Ergebnis erzwingt.

Lesson für Phase-C-4/C-5: **Cross-Spec-Reducer-Logik gezielt prüfen, nicht nur Anchor-Drift.** Beispiel: Spec 2 §8.5 `resolveRecordAction` allokiert ein File und packt es in `Action.RecordingAction.StartRecording(audioFile=…)`; Spec 1 §15.2 RecordingModule liest `action.audioFile` und schreibt es in `RecordingState.Preparing(audioFile=…)`. Diese drei Touch-Points (Resolver → Action → Reducer-State) müssen am selben File-Type (`java.io.File`) und an derselben Null-Konvention (non-null durch Pre-Dispatch-Allocate) hängen — wenn nicht, ist es ein Cross-Spec-Logik-Bug, kein Drift-Bug.

Nach den 11 Operations ist der Action-Hierarchie-/Dispatch-/EffectFailure-Bereich für die Implementer-Phase reif. Die F-3-`reduceFailure`-Korrektur ist die kritischste Einzeländerung — ohne sie würde der erste real-getestete `MediaRecorder.prepare()`-Throw zu einem unrecoverable Preparing-Hänger führen.
