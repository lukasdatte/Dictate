# Phase C-5 — Floating-Overlay Kohärenz-Review

**Erstellt:** 2026-05-14
**Reviewer:** Phase-C-Agent C-5
**Plan-Version vor Edits:** Commit `2a032e3` (Phase-C-1) + Phase-C-2-Apply (10 Plan-Edits Spec 1) + Phase-C-3-Apply (11 Plan-Edits Spec 1+2) + Phase-C-4-Apply (10 Plan-Edits Spec 2)
**Scope:** Spec 3 §1–§14 (OverlayBackend + Wrapper + Permission + Mode-Transitionen + Drag + Acceptance + Tests), Cross-Spec-Verifikation gegen Spec 1 §3 (OverlayState/ViewMode-Sub-State), §15.1 (Modul-Inventar OverlayModule + ViewModeModule + Coupling-Matrix), §15.5 (Cross-Module-Effect-Modi/Mode-3-Verbot), Spec 2 §4 + §8.6 (LayoutCatalog.OVERLAY_5BUTTON-Property-Skelett — C-4 F-5-Cross-Reference), Hauptplan §3.3 (OVERLAY_5BUTTON-Acceptance-Anker).

**Cross-Spec-Verifikation:** **Pflicht** — C-5-Scope ist intrinsisch cross-spec:
- `OVERLAY_5BUTTON` ist als Catalog-Property in Spec 2 §8.6 SoT-Strukturplatz; Spec 3 §3.1 ist Inhalts-SoT — C-4 hat die Strukturplatz-Verankerung gesetzt, C-5 muss den Inhalts-Block einbetten.
- Action-Hierarchie kommt aus Spec 2 §3.3 (OverlayAction + ViewModeAction-Subhierarchien); Spec 3 referenziert sie überall (§3.1, §6, §7.3).
- State-Pfade (`state.overlay.X`, `state.viewMode`) sind in Spec 1 §3 deklariert; Spec 3 §3.1, §4.2, §4.8, §6, §7 referenziert sie konsumierend.
- Reducer-Signatur-Vertrag: jedes Modul operiert auf seiner Sub-State-Achse (Spec 1 §4.2 + §15.2-Beispiel). Spec 3 zeigt ViewModeModule.reduce-Snippets in §6.1, §7.1, §7.3 + OverlayModule.reduce in §4.8 — alle müssen sub-state-konsistent sein.
- C-3-Disambiguation `CancelRecording` vs `CancelPipeline` (C-3 F-1) muss in §6.2 (CloseOverlay-in-HOVER) sowie §4.8 OverlayModule.onCrossModuleStateChange angewandt sein.

**Vorgänger-Anker:**
- **C-1** hat den Modul-Counter homogenisiert + DictateModule-Interface-Surface (7+4 Methoden) + Anchor-Form für Cross-Links etabliert.
- **C-2** hat Service-Layer/Persistence/Lifecycle finalisiert.
- **C-3** hat die Action-Disambiguation `CancelRecording` (statt `CancelPipeline`) für den MediaRecorder-Release-Pfad final entschieden + Resolver-`null`-Semantik in Spec 2 §3.2 + §6 verankert; C-3 hat explizit an C-5 weitergegeben: (a) Action-Refs in Spec 3 auf die `RecordingAction`-Subhierarchie prüfen, (b) OverlayModule-`reduceFailure`-Absenz als bewusste Design-Entscheidung dokumentieren, (c) DispatchOutcome-Telemetry-Backlog.
- **C-4** hat die F-5-Cross-Spec-Korrektur-Pflicht (OVERLAY_5BUTTON-Catalog-Embedding) an C-5 übergeben + Click-Listener-DRY-Verifikation in Spec 3 §4.2 gefordert + Spec 2 §8.6 Property-Skelett-Anker gesetzt.
- **Phase-B S-8** hat T7 + Wrapper-Idempotenz + Boot-Default-Race-Window + SSoT-Implementations-Heimat in Spec 3 verankert (Doppel-Truth-Resolution).

---

## Summary

Der Floating-Overlay-Bereich (Spec 3) ist nach allen Phase-B + Phase-C-1/C-2/C-3/C-4-Edits **architektonisch tragfähig in den Grobzügen** (Wrapper-Idempotenz, 5-Button-Layout, Permission-Observer, Drag-Lifecycle, OverlayModule-Reducer-Arme, Cross-Module-Coupling-Matrix-Konformität sind robust), hatte aber **drei massive Inkonsistenz-Cluster**:

1. **Cross-Spec-Compile-Error `LayoutCatalog.OVERLAY_5BUTTON` (C-4 F-5-Vererbung)**: Spec 3 §3.1 deklarierte `OVERLAY_5BUTTON` als top-level `object`, aber Spec 2 §4 + §8.6 + Spec 3 §11/§14 referenzierten den qualifizierten Catalog-Member. C-5-Pflicht: Inhalts-SoT in Spec 3 §3.1 in den `LayoutCatalog`-Body einbetten.

2. **ViewModeModule-Reducer-API-Cluster (Compile-Error in 6 Snippets)**: ViewModeModule operiert auf der `ViewMode`-Enum-Sub-State, aber §7.1 + §7.3 T1–T7 + §5.4 + §6.1 nutzen `state.copy(viewMode = ...)` (= globale `DictateUiState.copy`) oder `state.viewMode` (= Read durch globalen State). Da Enums kein `.copy()` haben, sind die Snippets nicht compile-fähig — und in `reduce(state: ViewMode, action, ctx)` ist `state.viewMode` ein Property-Access auf der Enum-Instanz, der nicht existiert. Cross-Module-Reads (overlay.userPrefersWidget, pipeline, recording) müssen über `ctx.global.x.y` laufen, nicht `state.x.y`. Plus: `state.imeViewVisible` in §7.3 T7 ist ein **fiktives Feld** (existiert nicht in DictateUiState oder ViewMode-Enum).

3. **Mode-3-Verstoß + Pure-Reducer-Violation in §5.4** (klassischer AI-3-Drift, der in §7.3 T1/T2 via S-9-Fix schon homogenisiert wurde, aber in §5.4 stehen blieb): Permission-da-Pfad mutierte gleichzeitig `viewMode + overlay.onboardingPending` (Cross-Axis-Mutation, Spec 1 §15.5 Mode-3-Backlog); plus `permissions.markPermanentlyDenied()` / `markOnboardingShown()` wurden **synchron im Reducer** aufgerufen (R.2-Verstoß, Spec 1 §4.2 Reducer-Pure-Contract).

4. **C-3-Disambiguation `CancelPipeline` → `CancelRecording` in §6.2 + §4.8 nicht propagiert**: §6.2 CloseOverlay-HOVER-Snippet referenzierte noch `Action.PipelineAction.CancelPipeline` als Cascade-Action (Pre-C-3-Form); aber bei HOVER ist `state.recording.isActiveOrPaused` praktisch immer wahr (HOVER ist die Bedingung "IME hidden + Pipeline aktiv") — also Recording-Hardware-Release ist Domäne von RecordingModule (C-3 F-1). §4.8 OverlayModule.onCrossModuleStateChange hatte zudem die Cancel-Cascade **gar nicht emittiert** — nur den Suppress-Bit. Symmetrisch zur Spec-1-§7.3-onDestroy-Pre-Cancel-Logik (C-3 F-1) muss die Disambiguation hier angewandt werden.

5. **§13.3 Permissions-Logik-SoT-Beschreibung Pre-Issue-3.1.3** (klein): "Permission-Check existiert nur an einer Stelle: `OverlayPermissionGate.hasOverlayPermission()` … aufgerufen in ViewModeModule.reduce + OverlayBackend.render" — aber post-Issue-3.1.3 ist Permission eine **State-Achse** (`state.overlay.hasPermission`), nicht ein Live-Read. Der Observer (`OverlayPermissionObserver`, §5.0) ist die einzige Live-Quelle.

6. **Z.-Refs nach Phase-B nicht synchron gezogen** (zwei Stellen, F-5-Pattern aus C-1): `Spec 1 §3 Z. 183` (§5.0) + `§4.2 Zeile 341` (§10 Suppress-Bit-Acceptance).

7. **OverlayModule-`reduceFailure`-Absenz als bewusste Design-Entscheidung nicht dokumentiert** (C-3-Cross-Reference): §4.8 OverlayModule hat keinen `reduceFailure`-Override. Das ist konsistent mit dem Default-`null`-Hook (Spec 1 §4.2), aber ohne explizite Block-Doku entsteht ein zukünftiger False-Positive-Finding "OverlayModule fehlt reduceFailure".

**7 Findings (3 Critical, 3 Important, 1 Minor); 11 Plan-Edits** (alle in Spec 3: §3.1 (LayoutCatalog-Einbettung), §4.2 (DRY-Cross-Ref), §4.8 (EffectFailure-Design-Block + Cancel-Cascade), §5.0 (Z.-Ref-Anchor), §5.4 (Mode-3 + Pure-Reducer-Refactor), §6.1 (when (state.viewMode) → when (state)), §6.2 (Cancel-Disambiguation + Reducer-Signatur), §7.1 (Reducer-Signatur), §7.3 T1+T2+T3+T7 (Reducer-Signatur + fiktives Feld), §10 (Z.-Ref-Anchor), §13.3 (Permissions-Logik-SoT-Update); plus 1 Iter-Log-Eintrag im Hauptplan).

---

## Findings + Applied Fixes

### F-1 (CRITICAL) — Cross-Spec-Compile-Error: `LayoutCatalog.OVERLAY_5BUTTON` als top-level `object` deklariert (C-4 F-5-Vererbung)

**Symptom:** Spec 3 §3.1 deklarierte `OVERLAY_5BUTTON` als top-level Kotlin `object` außerhalb von `LayoutCatalog`:

```kotlin
object OVERLAY_5BUTTON : LayoutMode(
    id = LayoutModeId.OVERLAY_5BUTTON,
    backend = BackendType.OVERLAY_WINDOW,
    rows = listOf(…)
)
```

Aber 9+ Konsumenten in Spec 2 + Spec 3 (Spec 2 §4 `computeLayoutMode`, Spec 2 §8.6 Catalog-Body, Spec 3 §11.5.4 + §13.3.1 + §14.1 + §14.2-Mehrfach) referenzieren den qualifizierten Member `LayoutCatalog.OVERLAY_5BUTTON`. Compile-Error im jetzigen Zustand.

C-4 F-5 hat diese Doppel-Truth-Quelle entdeckt und die Korrektur-Pflicht explizit an C-5 weitergegeben + den SoT-Strukturplatz in Spec 2 §8.6 als Property-Skelett verankert (`// val OVERLAY_5BUTTON: LayoutMode = ... // SoT: Spec 3 §3.1; C-5 ergänzt den Property-Body hier.`).

**Folge:** Compile-Error beim ersten `./gradlew assembleDebug` nach Block 4 oder Block 6 (siehe C-4-Report F-5).

**Fix:** Spec 3 §3.1 zeigt jetzt die Einbettung als nested object inside `LayoutCatalog`:

```kotlin
object LayoutCatalog {
    // ... (KEYBOARD-Modes + forKeyboard(state) — siehe Spec 2 §8.6)

    object OVERLAY_5BUTTON : LayoutMode(…)
}
```

Damit ist `LayoutCatalog.OVERLAY_5BUTTON` ein gültiger qualifizierter Member-Zugriff (Kotlin nested-object). Spec 2 §8.6 Property-Skelett ist jetzt mit dem Inhalt aus Spec 3 §3.1 strukturell befüllbar.

**Edit:** Spec 3 §3.1 OVERLAY_5BUTTON-Deklaration (Top-Level-`object` → nested `object` im `LayoutCatalog`-Body) + prominenter FIX-Header mit Cross-Ref auf C-4 F-5 + Spec 2 §8.6.

---

### F-2 (CRITICAL) — ViewModeModule-Reducer-API-Cluster: 6 Snippets mit Compile-Fehlern (`state.copy(viewMode = …)`, `state.viewMode`, `state.overlay.X`, `state.imeViewVisible`)

**Symptom:** ViewModeModule operiert auf der `ViewMode`-Enum-Sub-State (Spec 1 §3 `viewMode: ViewMode`; §15.1 Modul-Inventar #4 "viewMode (enum)"). Reducer-Signatur (analog Spec 1 §15.2 RecordingModule): `reduce(state: ViewMode, action: Action.ViewModeAction, ctx: ReducerContext): TransitionResult<ViewMode, Effect>?`. Cross-Module-Reads gehen über `ctx.global.x.y`, nicht `state.x.y`.

Aber Spec 3 hatte 6 Reducer-Snippets mit dem **globalen-`DictateUiState`-API-Form**:

1. **§7.1** Lines 1387–1392: `userToggledWidget = state.overlay.userPrefersWidget`, `pipelineActive = state.pipeline !is ...`, `state.copy(viewMode = newViewMode)` — Compile-Error (ViewMode-Enum hat kein `.overlay` und kein `.copy`).
2. **§7.3 T1** Lines 1466–1469 (post-S-9 Form): `if (!state.overlay.hasPermission)`, `nextState = state.copy(viewMode = ViewMode.WIDGET)` — gleiche Bug-Klasse.
3. **§7.3 T2** Lines 1510–1518: `when (state.viewMode) { ViewMode.WIDGET -> TransitionResult(nextState = state.copy(viewMode = ViewMode.KEYBOARD), ...)` — gleiche Bug-Klasse.
4. **§7.3 T3** Lines 1555–1561: `userToggledWidget = state.overlay.userPrefersWidget`, `pipelineActive = state.pipeline ...`, `state.copy(viewMode = newViewMode)`.
5. **§7.3 T7** Lines 1633–1639: `imeViewVisible = state.imeViewVisible` — **fiktives Feld** (existiert weder in `ViewMode`-Enum noch in `DictateUiState`), `userToggledWidget = state.overlay.userPrefersWidget`, `state.copy(viewMode = newViewMode)`.
6. **§6.1** Lines 1378–1387 (post-S-9 Form): `when (state.viewMode)` (Enum hat keine `.viewMode`-Property).

**Folge:** Compile-Error in 6 verschiedenen Snippets. Plus, Bug-Klasse vervielfacht sich: ein Implementer kopiert das Pattern und reproduziert den Bug für jeden Mode-Trigger (T3/T4/T5/T6/T7). Plus `state.imeViewVisible` ist eine **Fata Morgana**: ein Implementer würde versuchen, das Feld zu DictateUiState hinzuzufügen → trifft sofort die Mode-3-Verbots-Grenze (ViewModeModule schreibt + liest sein eigenes Sub-Feld, aber ein neues `imeViewVisible`-Feld wäre weder klar dem ViewModeModule noch einem anderen Modul zuzuordnen). Der korrekte Pfad: IME-Visibility ist aus dem aktuellen ViewMode ableitbar (HOVER ⇒ IME hidden per Definition; KEYBOARD/WIDGET ⇒ IME visible).

Das ist ein **klassischer AI-1-Drift-Echo** (flach-state-paths) aus Phase-A — in den Plan-Body-Sektionen homogenisiert (alle anderen Module nutzen Sub-State + ctx.global), aber in den ViewModeModule-Snippets stehen geblieben, weil ViewModeModule keinen Code-Block in Spec 1 §15.x hat (S-8 F-5: Implementations-Heimat ist Spec 3) und die Snippets damit nicht gegen einen Spec-1-Code-Anker validiert wurden.

**Fix:** Alle 6 Snippets auf die Sub-State-Form umgestellt:

- `state` (in `when`/`copy`/Vergleich) → `state` als ViewMode-Enum direkt; Vergleich via `state != newViewMode` statt `state.viewMode != newViewMode`.
- `state.copy(viewMode = newViewMode)` → `TransitionResult(nextState = newViewMode, sideEffects = emptyList())` (oder `nextState = ViewMode.X` für direkten Wert).
- Cross-Module-Reads `state.overlay.X` / `state.pipeline.X` / `state.recording.X` → `ctx.global.overlay.X` / `ctx.global.pipeline.X` / `ctx.global.recording.X`.
- `state.imeViewVisible` (fiktiv) → `state != ViewMode.HOVER` (abgeleitet: HOVER = IME hidden; KEYBOARD/WIDGET = IME visible).

Plus expliziter Signatur-Kommentar `// Signatur: reduce(state: ViewMode, action: Action.ViewModeAction, ctx: ReducerContext): TransitionResult<ViewMode, _>?` in §7.1, §7.3 T1, T2, T3, T7, §6.1.

**Edit:** Spec 3 §6.1, §7.1, §7.3 T1, §7.3 T2, §7.3 T3, §7.3 T7 — sechs Snippet-Refactors.

---

### F-3 (CRITICAL) — §5.4 Pseudo-Code-Flow: Mode-3-Verstoß + Pure-Reducer-Violation

**Symptom:** §5.4 zeigte einen Pseudo-Code-Flow für die ToggleViewModeWidget-Permission-Logik mit zwei eigenständigen Bugs:

```kotlin
// OverlayModule.reduce / ViewModeModule.reduce — Action-Routing über DictateOrchestrator.dispatch:
when (action) {
    Action.ViewModeAction.ToggleViewModeWidget -> {
        if (!permissions.hasOverlayPermission()) {
            if (permissions.shouldShowOnboarding()) {
                state.copy(overlay = state.overlay.copy(onboardingPending = true))
            } else {
                state
            }
        } else {
            state.copy(
                viewMode = ViewMode.WIDGET,
                overlay = state.overlay.copy(onboardingPending = false),  // (a) Mode-3!
            )
        }
    }
    Action.OverlayAction.DismissOverlayOnboarding -> {
        permissions.markPermanentlyDenied()  // (b) Side-Effect im Reducer!
        state.copy(overlay = state.overlay.copy(onboardingPending = false))
    }
    Action.OverlayAction.MarkOverlayOnboardingShown -> {
        permissions.markOnboardingShown()    // (b) Side-Effect im Reducer!
        state.copy(overlay = state.overlay.copy(onboardingPending = false))
    }
}
```

**(a) Mode-3-Verstoß im Permission-da-Pfad:** `state.copy(viewMode = ViewMode.WIDGET, overlay = state.overlay.copy(onboardingPending = false))` mutiert ZWEI Sub-State-Achsen (`viewMode` + `overlay`) in einem Reducer-Schritt. Das ist genau das Anti-Pattern aus Spec 1 §15.5 Mode-3-Backlog (explizit Phase-2-Backlog: "Modul mutiert seine eigene Achse + EINE ANDERE Achse in einem Reducer-Schritt"). Analoger Bug wie Phase-B S-9 F-1 (§7.3 T1/T2 vor S-9-Fix), aber in §5.4 hängen geblieben.

**(b) Pure-Reducer-Violation:** `permissions.markPermanentlyDenied()` + `permissions.markOnboardingShown()` werden **synchron** im Reducer aufgerufen — Verstoß gegen R.2 (Spec 1 §4.2 Reducer-Pure-Contract: keine Side-Effects, keine I/O, keine Hardware/Service-Reads). Reducer müssen pure sein, Side-Effects laufen über `Effect`-Objekte → `runEffect(effect, services)`.

**(c) Modul-Trennung-Unklarheit:** Der Snippet-Header sagt "OverlayModule.reduce / ViewModeModule.reduce" — beide Module in einem `when (action)`-Block. Real sind das **zwei** getrennte Modul-Reducer mit unterschiedlichen Sub-State-Typen und Action-Subhierarchien (Spec 1 §4.2 Modul-Vertrag).

**Folge:** Implementer würde entweder (i) das Pseudo-Pattern wörtlich nehmen und Mode-3-Verstöße + Side-Effects im Reducer einbauen (Code-Review-Failure + Production-Bug: Side-Effects-im-Reducer brechen Replay-Tests, Time-Travel-Debugging und transactional state recovery); oder (ii) der Implementer fragt sich, wie er die zwei Module trennt und improvisiert.

**Fix:** §5.4 vollständig refaktoriert zu zwei getrennten `when (action)`-Blöcken (eines pro Modul) mit korrekter Sub-State-Signatur + Side-Effects über `Effect`-Objekte + Cascade in `OverlayModule.onCrossModuleStateChange` für onboardingPending-Cleanup (Mode-2-Form analog §7.3 T1 post-S-9). Plus prominenter FIX-Header dokumentiert die drei Korrekturen (a/b/c). Plus Hinweis-Block zum `onboardingPending = true`-Setter-Auslöser-Pfad (Implementer-Anchor für Block 6).

**Edit:** Spec 3 §5.4 Pseudo-Code-Block vollständig ersetzt.

---

### F-4 (IMPORTANT) — §6.2 CloseOverlay-in-HOVER: Pre-C-3-`CancelPipeline` + fehlende Cancel-Cascade in §4.8

**Symptom:** §6.2 CloseOverlay-Logik referenzierte (im Kommentar des ViewModeModule.reduce-Snippet):

> Cross-Module-Cascade: Pipeline-Cancel + ViewMode-Wechsel zu KEYBOARD
> Cascade-Action `Action.PipelineAction.CancelPipeline` wird vom
> Modular Orchestrator separat dispatched (siehe Spec 1 §4.3 + §15 onCrossModuleStateChange).

Das ist die **Pre-C-3-Form**. C-3 F-1 hat festgelegt: für Recording-Hardware-Release ist `Action.RecordingAction.CancelRecording` korrekt (RecordingModule hält `Effect.ReleaseMediaRecorder` + `Effect.DeleteAudioFile`), `Action.PipelineAction.CancelPipeline` ist nur für `state.pipeline !is Idle` ohne aktives Recording korrekt (PipelineModule hält Pipeline-State-Achse). Bei HOVER ist `state.recording.isActiveOrPaused` per Definition wahr (HOVER = IME hidden + Pipeline aktiv) — also Recording priorisiert.

Zusätzlich: §4.8 OverlayModule.onCrossModuleStateChange emittierte bei HOVER → KEYBOARD nur `SuppressAutoOverlayUntilNextSession` — die Cancel-Cascade **fehlte komplett**. Der §6.2-Kommentar verwies auf "wird vom Modular Orchestrator separat dispatched", aber die Cascade-Heimat-Sektion (§4.8) zeigte sie nicht.

**Folge:** Bei `Action.ViewModeAction.CloseOverlay` in HOVER würde der ViewMode auf KEYBOARD wechseln + Suppress-Bit setzen, aber **das Recording würde weiterlaufen** (kein Cancel-Effect emittiert). User-Wahrnehmung: "ich habe das Overlay weggeklickt, aber die Notification zeigt noch 'Recording'". Symptomatisch identisch zum MediaRecorder-Leak-Bug aus C-3 F-1 (§7.3 onDestroy).

**Fix:**
1. **§6.2 ViewModeModule.reduce-Snippet:** Reducer-Signatur korrigiert (analog F-2) + Kommentar dokumentiert, dass die Cancel-Cascade in §4.8 OverlayModule.onCrossModuleStateChange lebt (SRP — ViewModeModule kennt weder Recording- noch Pipeline-Hardware).
2. **§6.2 Folge-Block:** Zeigt die Cancel-Cascade in OverlayModule.onCrossModuleStateChange explizit mit C-3-Disambiguation:

```kotlin
when {
    next.recording.isActiveOrPaused || next.recording is RecordingState.Preparing ->
        cascade.add(Action.RecordingAction.CancelRecording)
    next.pipeline !is PipelineUiState.Idle ->
        cascade.add(Action.PipelineAction.CancelPipeline)
    // else: idle — kein Cancel nötig.
}
```

3. **§4.8 OverlayModule.onCrossModuleStateChange-Body:** HOVER → KEYBOARD-Block um die obige Cancel-Cascade ergänzt; Bestandscascade (`SuppressAutoOverlayUntilNextSession`) bleibt; Kommentar verlinkt §6.2 als Doku-Heimat + C-3 F-1 + Spec 1 §7.3 onDestroy-Pre-Cancel-Block als symmetrisches Pattern.

**Edit:** Spec 3 §6.2 (zwei Edits: Reducer + Cascade-Block) + Spec 3 §4.8 OverlayModule.onCrossModuleStateChange (HOVER → KEYBOARD-Block-Update).

---

### F-5 (IMPORTANT) — §13.3 Permissions-Logik-SoT Pre-Issue-3.1.3-Beschreibung

**Symptom:** §13.3 Permissions-Logik-DRY-Block sagte:

> **Behauptung:** Permission-Check existiert nur an EINER Stelle.
> **Beweis:** `OverlayPermissionGate.hasOverlayPermission()` ist die einzige Quelle. Aufgerufen in:
> - `ViewModeModule.reduce(Action.ViewModeAction.ToggleViewModeWidget)` (vor State-Mutation, Spec 1 §15)
> - `OverlayBackend.render()` (defensiv, vor Window-Attach)

Aber post-Issue-3.1.3 (User-Decision Option A — Permission als State-Achse, nicht Live-Read): `state.overlay.hasPermission` ist die SoT, vom `OverlayPermissionObserver.refresh()` synchron gehalten (§5.0). Reducer dürfen **keinen** synchronen `Settings.canDrawOverlays()`-Aufruf machen (Pure-Reducer-R.2). Der `OverlayPermissionGate`-Wrapper existiert noch (§5.1) als API-Abstraktion, aber nicht für den Reducer-Pfad.

**Folge:** Doku-Drift gegen Code-SoT — ein Reviewer, der §13.3 als DRY-Beweis liest, würde fragen, warum F-2 die `ctx.global.overlay.hasPermission`-Form fordert (Antwort: weil §13.3 stale war). Plus: ein Implementer könnte den `OverlayPermissionGate` versehentlich im Reducer-Pfad benutzen (= R.2-Verstoß).

**Fix:** §13.3 Permissions-Logik-Block refaktoriert: SoT ist jetzt `state.overlay.hasPermission` (Sub-State-Achse), Live-Quelle ist `OverlayPermissionObserver.refresh()` (Lifecycle-Trigger, kein Polling). Konsumenten lesen den State-Pfad; `OverlayPermissionGate` ist für nicht-Reducer-Konsumenten (Onboarding-Trigger im IME-View-Pfad). Plus FIX-Kommentar dokumentiert die Post-Issue-3.1.3-Form.

**Edit:** Spec 3 §13.3 "Permissions-Logik"-Block.

---

### F-6 (IMPORTANT) — OverlayModule `reduceFailure`-Absenz als bewusste Design-Entscheidung nicht dokumentiert (C-3-Cross-Reference)

**Symptom:** §4.8 OverlayModule.reduce-Block enthält Reducer-Arme + `runEffect` + `onCrossModuleStateChange`, aber keinen `reduceFailure(state, failure, ctx)`-Override. C-3 hatte explizit an C-5 weitergegeben: "OverlayModule (Spec 3 §4.8) hat einen `reduce(...)`-Block für `Action.OverlayAction.*`, aber **keinen** `reduceFailure`-Override. Das ist konsistent mit dem Default `null`-Hook (Spec 1 §4.2) — Overlay-Effects (`PersistOverlayPosition`, `MarkOnboardingShown`) sind alle idempotente Pref-Writes, ein Failure-Rollback ist hier semantisch nicht nötig. C-5 sollte das explizit als Design-Entscheidung dokumentieren, sonst entsteht ein zukünftiger False-Positive-Finding 'OverlayModule fehlt reduceFailure'."

**Folge:** Phase-D / C-State / ein zukünftiger Maintainer könnte beim Audit der Modul-Interface-Conformance einen False-Positive-Finding "OverlayModule überschreibt `reduceFailure` nicht, alle anderen Module mit Effect-Liste sollten das prüfen" generieren — der Auflösungspfad wäre eine Rück-Refaktorierung, die unnötigen Code hinzufügt.

**Fix:** §4.8 OverlayModule-Sektion vor dem Reducer-Code-Block um Block-Klarstellung erweitert: "**EffectFailure-Konvention (Design-Entscheidung):** OverlayModule überschreibt den `reduceFailure(state, failure, ctx)`-Hook **bewusst nicht**". Begründungs-Bullets:
- Alle Overlay-Effects sind idempotente Pref-Writes oder reine UI-Side-Effects.
- Pref-Write-Failure hat keine Rollback-Semantik im OverlayState (State ist korrekt, Pref-Mirror hinkt).
- `DeleteAudioFile`-Failure ist harmlos (Cache-Cleanup räumt auf).
- `OpenOverlayPermissionSettings`-Failure ist User-spürbar (Settings öffnen nicht).

Cross-Ref auf Spec 2 §3.3 EffectFailure-KDoc (C-3 F-4/F-5/F-6).

**Edit:** Spec 3 §4.8 OverlayModule-Sektion-Header (vor dem Code-Block).

---

### F-7 (MINOR) — Z.-Refs in §5.0 + §10 nicht auf Section-Anchor umgestellt (F-5-Pattern aus C-1)

**Symptom:** Zwei stale Z.-Refs in Spec 3:

1. **§5.0 Boot-Default-Race-Window-Block:** `Spec 1 §3 Z. 183` (zeigt auf `OverlayState.hasPermission`-Feld).
2. **§10 Acceptance Suppress-Bit-Lifecycle-Block:** `§4.2 Zeile 341` (zeigt auf den Suppress-Bit-Gate in `OverlayBackend.render`).

Beide sind fragil gegen jede Erweiterung von Spec 1 §3 oder Spec 3 §4.2 (Phase-D-Pässe). Pattern aus C-1 F-5 + C-3 F-8: alle Z.-Refs auf Section-Anchor + Methoden-/Block-Name umstellen.

**Fix:**
- `Spec 1 §3 Z. 183` → `Spec 1 §3 \`data class OverlayState\`, Feld \`hasPermission\``.
- `§4.2 Zeile 341` → `§4.2 \`render\`-Methode Suppress-Bit-Gate, direkt nach dem Permission-Gate`.

**Edit:** Spec 3 §5.0 + §10 (zwei Stellen).

---

## Plan-Edits (Audit-Trail)

| Datei | Sektion | Art | Kurzbeschreibung |
|---|---|---|---|
| Spec 3 §3.1 | `object OVERLAY_5BUTTON : LayoutMode(...)` | Refactor | Top-Level-`object` → nested `object` im `LayoutCatalog`-Body; FIX-Header dokumentiert C-4 F-5-Vererbung + Spec 2 §8.6-SoT-Strukturplatz (F-1) |
| Spec 3 §4.2 | `wireStaticOverlayHandlers` | Insert | FIX-Kommentar dokumentiert Cross-Spec-DRY-Pattern-Parität (C-3 F-7): Resolver-`null`-Filter identisch zu Spec 2 §6 `ImeViewBackend.wireStaticHandlers` |
| Spec 3 §4.8 | OverlayModule-Sektion-Header | Insert | EffectFailure-Konvention-Block (Design-Entscheidung: `reduceFailure` bewusst nicht überschrieben, alle Overlay-Effects sind idempotente Pref-Writes/UI-Side-Effects) (F-6) |
| Spec 3 §4.8 | OverlayModule.onCrossModuleStateChange HOVER → KEYBOARD-Block | Update | Cancel-Cascade ergänzt (C-3-Disambiguation: aktives Recording → CancelRecording; sonst pipeline !is Idle → CancelPipeline); Kommentar verweist auf §6.2 als Doku-Heimat (F-4) |
| Spec 3 §5.0 | Boot-Default-Race-Window-Block | Update | `Spec 1 §3 Z. 183` → Section-Anchor `\`data class OverlayState\`, Feld \`hasPermission\`` (F-7) |
| Spec 3 §5.4 | Pseudo-Code-Flow ToggleViewModeWidget + Onboarding | Refactor | Mode-3-Verstoß + Pure-Reducer-Violation behoben: zwei getrennte when (action)-Blöcke pro Modul, Sub-State-Signatur, Side-Effects über `Effect`-Objekte, OverlayModule.onCrossModuleStateChange-Cascade für onboardingPending-Cleanup; FIX-Header dokumentiert (a) Mode-3, (b) Pure-Reducer-Violation, (c) Modul-Trennung (F-3) |
| Spec 3 §6.1 | ViewModeModule.reduce-Snippet | Update | `when (state.viewMode)` → `when (state)` (Reducer-Signatur: state ist ViewMode-Enum-Sub-State); Signatur-Kommentar ergänzt (F-2) |
| Spec 3 §6.2 | ViewModeModule.reduce + Cascade-Block | Refactor | (a) Reducer-Signatur korrigiert (`state.copy(viewMode = ...)` → `TransitionResult(nextState = ViewMode.KEYBOARD, ...)`); (b) C-3-Disambiguation `CancelPipeline` → `CancelRecording`-Priorisierung in OverlayModule.onCrossModuleStateChange-Cascade dokumentiert; (c) Architektur-Auflösungs-Hinweis aktualisiert (PENDING 3.1.7 → aufgelöst mit C-3 + C-5) (F-2 + F-4) |
| Spec 3 §7.1 | ViewModeModule.reduce OnImeViewShown/Hidden | Update | Reducer-Signatur korrigiert: `state.x.y` → `ctx.global.x.y` (Cross-Module-Reads); `state.copy(viewMode = ...)` → `TransitionResult(nextState = newViewMode, ...)`; FIX-Block dokumentiert Pattern + Cross-Ref auf §6.1 + §15.2 RecordingModule-Beispiel (F-2) |
| Spec 3 §7.3 T1 | ViewModeModule.reduce Permission-Gate | Update | Reducer-Signatur korrigiert; `state.overlay.hasPermission` → `ctx.global.overlay.hasPermission`; `nextState = state.copy(viewMode = WIDGET)` → `nextState = ViewMode.WIDGET` (F-2) |
| Spec 3 §7.3 T2 | ViewModeModule.reduce WIDGET → KEYBOARD | Update | `when (state.viewMode)` → `when (state)`; `state.copy(viewMode = KEYBOARD)` → `nextState = ViewMode.KEYBOARD` (F-2) |
| Spec 3 §7.3 T3 | ViewModeModule.reduce OnImeViewHidden | Update | Cross-Module-Reads (overlay, pipeline, recording) auf `ctx.global` umgestellt; nextState-Form (F-2) |
| Spec 3 §7.3 T7 | ViewModeModule.reduce OnPipelineDone | Refactor | Fiktives Feld `state.imeViewVisible` aufgelöst (`state != ViewMode.HOVER` als abgeleiteter Wert); Cross-Module-Reads auf `ctx.global`; nextState-Form (F-2) |
| Spec 3 §10 | Suppress-Bit-Lifecycle-Acceptance | Update | `§4.2 Zeile 341` → Section-Anchor `§4.2 \`render\`-Methode Suppress-Bit-Gate` (F-7) |
| Spec 3 §13.3 | Permissions-Logik-DRY-Block | Refactor | Post-Issue-3.1.3-Form: SoT ist `state.overlay.hasPermission` State-Achse, OverlayPermissionObserver ist einzige Live-Quelle, OverlayPermissionGate ist nur für nicht-Reducer-Konsumenten (F-5) |
| Hauptplan §9 | Iteration-Log | Insert | "2026-05-14 — Phase-C Quality-Gate C-5"-Entry mit 7 Findings + Plan-Edits-Summary |

**Gesamt:** 16 Operations in 2 Dateien (Spec 3: 15, Hauptplan: 1). Spec 1 + Spec 2 unverändert — C-5-Scope ist Spec-3-internal (mit Ausnahme der OVERLAY_5BUTTON-Cross-Spec-Korrektur in F-1, deren SoT-Strukturplatz-Anker bereits in Spec 2 §8.6 von C-4 gesetzt ist).

---

## Offene Fragen für nachfolgende Agents

### Für C-State (State-File-Konsistenz)

- **Plan-State-File-Aktualisierung:** `plan-review/state.md` ist seit Phase-1-Abschluss nicht aktualisiert (unverändert zum C-4-Hinweis). Workflow-Step-Tabelle zeigt alle Phase-2/3/Phase-A/B/C-Schritte als `⏳`; tatsächlich ist Phase-A + B + C-1 bis C-5 abgeschlossen. C-State soll das State-File auf den tatsächlichen Workflow umstellen (Phase-A inventory, Phase-B Subsystem-Reviews S-1 bis S-9, Phase-C Coherence-Reviews C-1 bis C-5).
- **Verifikation OVERLAY_5BUTTON-Cross-Spec-Korrektur (F-1):** C-State soll prüfen, dass Spec 2 §8.6 LayoutCatalog-Body und Spec 3 §3.1 LayoutCatalog-Body strukturell kompatibel sind (Spec 2 erklärt forKeyboard + KEYBOARD-Modes; Spec 3 erklärt OVERLAY_5BUTTON; eine Block-Implementer-Zusammenführung verbindet die zwei Inhalte im einen `object LayoutCatalog`).
- **`onboardingPending = true`-Setter-Auslöser-Pfad (F-3 Implementer-Hinweis):** §5.4 post-C-5 hat einen offenen Implementer-Choice für den `onboardingPending = true`-Trigger (dedizierter `Action.OverlayAction.RequestOverlayPermission`-Reducer-Arm ODER expliziter `ShowOnboarding`-Action). Das ist Spec-3-internal-Block-6-Design-Choice; C-State sollte ggf. einen Acceptance-Klausel-Bullet ergänzen, der den gewählten Trigger-Pfad festschreibt.
- **DispatchOutcome-Telemetry-Backlog (C-1 F-6 → C-3 → C-5):** Die Onboarding-Permission-Flow-Dispatches in Spec 3 §5.0 + §5.2 (`Action.OverlayAction.MarkOverlayOnboardingShown`, `Action.OverlayAction.DismissOverlayOnboarding`, `Action.OverlayAction.OnOverlayPermissionChanged`) sollten alle gegen `DispatchOutcome.Unrouted` abgesichert sein (Lint-Check oder Acceptance-Klausel). Phase-2-Backlog laut C-1 F-6 — C-State sollte einen Backlog-Eintrag erstellen, falls noch nicht vorhanden.

### Für die spätere Block-6-Implementierungs-Phase

- **F-3 onboardingPending-Trigger Final-Choice:** Die §5.4-Doku zeigt zwei Implementer-Optionen (dedizierter Action-Arm vs. expliziter ShowOnboarding-Action). Der Block-6-Implementer sollte sich für eine entscheiden (Empfehlung: dedizierter `Action.OverlayAction.RequestOverlayPermission`-Reducer-Arm, der `onboardingPending = true` setzt + `Effect.OpenOverlayPermissionSettings` emittiert — symmetrisch zur §4.8 `RequestOverlayPermission`-Action, die bereits den Effect emittiert). Final-Decision während Implementation, nicht in Plan-Phase.

---

**Reviewer-Note:** Das C-5-Finding-Cluster hat **drei Achsen** gegenüber C-1/C-2/C-3/C-4:

- **C-1/C-2 Drift-Echo-Muster:** stale Counter / stale Vertrags-Layer-Refs in Lese-Anchor-Sites.
- **C-3 Cross-Spec-Reducer-Logik-Bug:** Reducer-String-Match gegen `effect.toString()`-Encoding scheitert wegen `data class.toString()`-Property-Inklusion.
- **C-4 Test-Snippet-Drift-Cluster:** AI-1-flat-state-paths-Drift in Plan-Body-Sektionen homogenisiert, aber in Test-Snippets stehen geblieben.
- **C-5 Reducer-API-Cluster (F-2):** ViewModeModule operiert auf der `ViewMode`-Enum-Sub-State, aber 6 Snippets in Spec 3 nutzten die globale `DictateUiState`-Copy-Form mit `state.copy(viewMode = ...)` + `state.overlay.X`-Reads. Bug-Klasse: ViewModeModule hat **keinen Code-Block in Spec 1 §15.x** (S-8 F-5 hat das als Implementations-Heimat-Klarstellung dokumentiert) — also wurden die Spec-3-Snippets nicht gegen ein Spec-1-Modul-Pattern validiert. Die anderen Module (RecordingModule, AudioModule, KeyboardInputModule) haben in Spec 1 §15.x kanonische Code-Blöcke, die als Reading-Anchor für die korrekte Sub-State-Reducer-Form dienen — ViewModeModule fehlt diese Anker-Quelle. Lesson für Phase-D: wenn ein Modul **keinen kanonischen Code-Block in Spec 1 §15** hat (= "Implementations-Heimat in Spec 3"), MUSS der Snippet-Reviewer den Sub-State-Reducer-Signatur-Vertrag aus Spec 1 §15.2 (RecordingModule-Beispiel) **manuell anlegen** — `grep` auf "state.copy(" alleine findet die globale-vs-sub-Form-Inkonsistenz nicht, weil es die nicht-existente Methode `Enum.copy()` als Token nicht unterscheidet.

Plus: das **F-2-Fiktives-Feld-Sub-Cluster** (`state.imeViewVisible`) ist eine separate Bug-Klasse — ein Implementer würde versuchen, das Feld zu DictateUiState hinzuzufügen + den Sync-Pfad zu IME-Lifecycle-Triggern zu verdrahten. Mode-3-Verbots-Frage taucht sofort auf (welches Modul "besitzt" `imeViewVisible`?). Die saubere Auflösung — IME-Visibility ist aus dem aktuellen ViewMode ableitbar (HOVER ⇒ hidden) — ist semantisch einfacher als ein neues State-Feld + zwei Sync-Pfade. Solche Fata-Morgana-Felder lassen sich nur durch Cross-Spec-Trace (Spec 3 §7.3 T7 Reference → Spec 1 §3 DictateUiState-Felder) finden.

Plus: **F-1-Cross-Spec-Korrektur** (OVERLAY_5BUTTON-Einbettung) ist die strukturelle Auflösung der C-4 F-5-Cross-Spec-Korrektur-Pflicht — Spec 2 §8.6 hat den Strukturplatz, Spec 3 §3.1 hat den Inhalt. Der Block-6-Implementer kann jetzt die zwei Spec-Sites zu **einem** `object LayoutCatalog`-Body verbinden ohne Inhalts-Drift.

Plus: **F-3-Mode-3-Verstoß in §5.4** zeigt, dass die S-9-Refactor-Welle (§7.3 T1/T2 auf Mode-2-Cascade) nicht **vollständig** war — §5.4 enthielt einen weiteren Mode-3-Verstoß für dieselbe `ToggleViewModeWidget`-Action, der unentdeckt blieb, weil §5.4 als "Pseudo-Code-Flow" mental abgegrenzt war (Code-Form, aber semantisch als Doku-Pseudocode markiert). Lesson: Pseudo-Code-Snippets in Plan-Sektionen, die Reducer-Logik zeigen, müssen denselben Sub-State-Reducer-Vertrag durchlaufen wie "echte" Code-Snippets — sie sind Lese-Vorlage für Implementer, und ein Pseudo-Code-Snippet mit Mode-3-Verstoß ist nicht weniger schädlich als ein Code-Snippet mit Mode-3-Verstoß.

Plus: **F-4-Cancel-Cascade-Lücke** (CloseOverlay-in-HOVER ohne Recording-Cancel) ist die Spiegelung der C-3 F-1-Disambiguation in einem zweiten Pfad — C-3 hat den onDestroy-Pfad fixiert, C-5 hat den CloseOverlay-in-HOVER-Pfad fixiert. Beide haben dieselbe Struktur (active recording → CancelRecording priorisiert, sonst Pipeline → CancelPipeline). Das ist ein **wiederkehrendes Pattern** für Service-/Lifecycle-Cancel-Pfade; ein zukünftiges Phase-D-Audit sollte alle Cancel-Trigger-Sites systematisch gegen dieses Pattern prüfen (Hard-Service-Death, User-Close-Overlay, App-Background-Cancel etc.).

Nach den 15 Spec-3-Edits + 1 Hauptplan-Edit ist der Floating-Overlay-Bereich für die Implementer-Phase reif. F-1 ist die kritischste Einzeländerung (Cross-Spec-Compile-Error eliminiert), F-2 ist das größte Cluster (6 Reducer-API-Bugs in einem Pass), F-3 + F-4 schließen die letzten Mode-3/Pure-Reducer-Lücken aus Phase-A AI-3 + die C-3-Disambiguation für CloseOverlay-in-HOVER.
