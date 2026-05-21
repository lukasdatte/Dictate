# Implementation State — dictate-widget-integration

## Run metadata
- Start: 2026-05-21T11:29:36+02:00
- Worktree: ./worktrees/feature/dictate-keyboard-layout-refactor
- Last commit at run-start: d6177aef42fdd260f5c5f5d2176152daec9ea885
- Sibling indirection plan: completed in commits edfe8a2..d6177ae
- Plan: docs/plans/2026-05-21 - dictate-widget-integration/dictate-widget-integration.md (Implementer-ready)

## Chunk status
| Block | Chunk | Status | Commit | Notes |
|---|---|---|---|---|
| 1 | 1.1 Layout-XML neu zeichnen (Variante 2a) | DONE | 2fb5ad9 | PulseLayout-Wrapper um overlay_record_btn, Row 2 = [Trash][spacer][Pause][Close]; overlay_send_btn gestrichen. |
| 1 | 1.2 LogicalButtonId-Cleanup (OVERLAY_SEND raus) | DONE | 2fb5ad9 | Enum-Member entfernt, KDoc auf OVERLAY_RECORD dokumentiert die Variante-2a-Merge. |
| 1 | 1.3 OverlayBackend-Konstruktor + Forwarder-API | DONE | cd27d70 | Drei Factory-Slots (RecordingAnimation/AutoEnter/Color) + imeSideAffordance + onTimerTick/onAmplitude/updateAccentColor Forwarder. |
| 1 | 1.4 inflateAndAttach baut RendererBundle | DONE | cd27d70 | OverlayRendererBundle data class; buildRendererBundle() after wireDragController; render() ruft autoEnter→color→recording (gleicher Order wie ImeViewBackend); teardownOverlay resettet bundle BEFORE detach. |
| 2 | 2.1 resolveOverlayRecordButtonText | DONE | 2fb5ad9 | Komposition aus resolveRecordButtonText + resolveRecordButtonTextPipeline. |
| 2 | 2.2 resolveOverlayRecordAction erweitern | DONE | 2fb5ad9 | HOVER-Gate (User-Req SEND-gate), WIDGET-Active|Paused → StopRecordingAndSend (via resolveRecordAction), Preparing|Running → ToggleRunningAutoEnter (via resolveRecordActionPipeline). Plus resolveOverlayRecordEnabled. |
| 2 | 2.3 OVERLAY_RECORD-Slot Umbau | DONE | 2fb5ad9 | FillRemaining; always-visible; textResolver=resolveOverlayRecordButtonText; enabledResolver/alphaResolver/actionResolver verdrahtet. |
| 2 | 2.4 OVERLAY_SEND-Slot streichen | DONE | 2fb5ad9 | Slot entfernt; LayoutStrings.overlaySend deprecated. |
| 2 | 2.5 OVERLAY_PAUSE → resolvePauseAction | DONE | 2fb5ad9 | DRY-Konsolidierung mit Keyboard-PAUSE. |
| 2 | 2.6 resolveOverlayPauseAction löschen | DONE | 2fb5ad9 | Funktion entfernt (war byte-identisch zu resolvePauseAction). |
| 2 | 2.7 OVERLAY_TRASH → resolveTrashAction | DONE | 2fb5ad9 | DRY-Konsolidierung mit Keyboard-TRASH; ReprocessStaging-Branch ist im Overlay strukturell unerreichbar (KEYBOARD-only) aber Resolver-Symmetrie bleibt. |
| 3 | 3.1 imeSideAffordance-Forward im OverlayBackend | DONE | cd27d70 | wireStaticOverlayHandlers ruft `imeSideAffordance(id, false)` vor dem actionResolver für OVERLAY_RECORD. |
| 3 | 3.2 ModuleServices.imeSideAffordanceHook | DONE | cd27d70 | Variante C umgesetzt (Plan §8.3 hat Variante A empfohlen — siehe Deviation unten). LocalBinder.delegateImeSideAffordance + register/clear, OverlayBackend liest beim Click. IME registriert sich nach ImeViewBackend-Konstruktion, clear in unbindAiInfrastructureFromService. |
| 3 | 3.3 prepareCatalogStopRecordingIfActive verifizieren | DONE | (verify-only) | Self-gating bestätigt: returnt früh wenn recording nicht Active\|Paused (DictateInputMethodService.java:3585-3588). |
| 4 | 4.4 Architektur-Invariant-Test erweitern | DONE | (pending commit) | Neue Tests: `overlayBackendClickBranchFiresAffordanceForOverlayRecord` + `commentStripperIsSound_overlayAffordanceHook` in CutoverArchitectureInvariantTest. |

## Plan-intention deviations

### D-1: Chunk 3.2 — `ModuleServices.imeSideAffordanceHook` (Variante A) → LocalBinder-late-bind (Variante C)

- **Plan-text reference**: §8.3 Chunk 3.2, "Empfehlung: Variante A".
- **§2 User-Quote relation**: indirekt — User-Req "Pause-Button soll der gleiche Pause-Button sein, mit dem gleichen Wiring" / "exakt der gleiche Button" verlangt funktional Verhaltensidentität; das wird durch jede der drei Varianten erreicht.
- **Researched**: Die existierende `DictatePipelineService.LocalBinder`-Struktur kennt das exakte Pattern bereits — `delegateInputConnectionProvider` / `registerInputConnectionProvider` (Zeile 1296, 1335) und `delegatePipelineConfigResolver` / `registerPipelineConfigResolver` (Zeile 1310, 1349). Die IME-private Affordance-Lambda kapselt `imePipelineConfigResolver` + `newPathRecordingSessionId` — also IME-private Runtime-State, der in `ModuleServices` (laut KDoc: "DI-Container für Module-Effects") konzeptionell nicht hingehört.
- **Resolution**: Variante C in der LocalBinder-Form umgesetzt (`delegateImeSideAffordance` `@Volatile` + `registerImeSideAffordance(...)`). Der OverlayBackend bekommt im Konstruktor ein Lambda `imeSideAffordance = { id, longPress -> binder.delegateImeSideAffordance?.invoke(id, longPress) }`, das beim Click den aktuell registrierten Hook liest (oder no-op bei null/IME-unbound).
- **Why this is sustainable**: konsistent mit drei bestehenden IME→Service-Late-Binds, kein neuer Mechanismus, kein ModuleServices-Pollution. Sechs-Monats-Reader sieht das Pattern bereits an drei Stellen — leichter zu erkennen als ein 4. paralleler Bus.
- **Follow-up**: keine.

### D-2: Layout-Variante 2a — Row-2-Ordnung [Trash][spacer][Pause][Close] statt [Trash][spacer][Pause][6dp-Spacer][Close]

- **Plan-text reference**: §8.1 Chunk 1.1 schlägt `Reihe 2: [Trash 48dp] [Spacer w=1] [Pause 48dp] [Spacer 6dp] [Close 48dp]` vor.
- **§2 User-Quote relation**: keine direkte — Plan-Text-Detail, nicht User-bemerkte Optik.
- **Researched**: `app/src/main/res/layout/overlay_5button_layout.xml` (alt) hatte Pause + Close beide rechts, 6dp Margin zwischen ihnen. Das Match zur Keyboard-SEND_MODE-Reihe (`[Trash][Pause][Close]` ohne dedizierten Spacer) ist sauberer.
- **Resolution**: 1:1 wie im Plan-Text — [Trash 48dp] [Spacer w=1] [Pause 48dp] [Close 48dp marginStart=6dp]. Die "Spacer 6dp" zwischen Pause und Close ist als `android:layout_marginStart="6dp"` auf dem Close-Button umgesetzt, was XML-idiomatischer ist als ein separates `<Space>`-Element.
- **Follow-up**: keine; nur ein Formulierungsunterschied — Pause + Close haben jetzt eine 6dp-Lücke zwischen sich.

### D-3: Chunk 4.4 — Invariant-Test Tag-Buchstabe

- **Plan-text reference**: §8.4 Chunk 4.4 nennt die neue Assertion `(g) overlayBackendClickBranchFiresAffordanceForRecord`.
- **§2 User-Quote relation**: keine direkte — Test-Naming.
- **Resolution**: Übernommen als `overlayBackendClickBranchFiresAffordanceForOverlayRecord` (mit `OVERLAY_RECORD` statt nur `Record`, weil das die im Code verwendete Enum-Konstante ist). Plus `commentStripperIsSound_overlayAffordanceHook` als Selbst-Soundness-Test (gleiches Pattern wie alle anderen Invariant-Test-Paare).

## Open issues / postponed

- **Strings**: `R.string.overlay_send` + `R.string.overlay_send_cd` bleiben in `app/src/main/res/values{,-de,-es,-pt}/strings.xml` stehen — sie sind ungenutzt aber Löschen sprengt den Plan-Scope und könnte Übersetzungstooling beeinflussen. Cleanup-Item für später.
- **LayoutStrings.overlaySend** — deprecated markiert, Field hat Default-Wert; explizite Removal in separatem Cleanup-PR.
- **OQ-5 Ticker-Owner-Migration in den PipelineService** (Plan §10.2 OQ-5): out-of-scope dieses Plans; HOVER-Modus zeigt heute noch keinen Timer (IME-View ist hidden → Ticker läuft nicht). Folge-Plan `dictate-pipeline-ticker-service-side` empfohlen.
- **Manual Device-Test ausstehend** (AC-2, AC-3, AC-7, AC-8): JVM-Tests verifizieren Wiring + Resolver-Logik; visueller Test der PulseLayout / Amplitude / Timer / ↵-Icon / rote Failure-Farbe + Pipeline-Submit aus dem Overlay erfordert Device-Run (ADB Wireless instabil — User-Memory: ADB Wireless ist häufig instabil). Acceptance-Criteria-Mapping siehe Final Report.

## Final report (filled at end)

### Status
- **15/15 plan-chunks done** (Block 1: 4/4, Block 2: 7/7, Block 3: 3/3, Block 4: 1/1 — Chunks 4.1-4.3 sind als 0-Effort / Verify-only deklariert und durch die Block-2-Implementierung erledigt).
- **0 blocked, 0 hard-postponed.**

### Test results
- `./gradlew testDebugUnitTest` — **BUILD SUCCESSFUL** (Robolectric + JVM, gesamt; 30s).
- **+9 neue Tests** in `ActionResolversTest` für `resolveOverlayRecordAction` + `resolveOverlayRecordEnabled`.
- **+6 neue Tests** in `TextResolversTest` (neue Datei) für `resolveOverlayRecordButtonText`.
- **+4 neue Tests** in `OverlayBackendTest` (imeSideAffordance fires, no fire für andere Buttons, forwarder-no-op ohne Factory, teardown-clear).
- **+2 neue Tests** in `CutoverArchitectureInvariantTest` (Backend-Invariant + Self-Soundness).

### Reuse-Audit (button-by-button vs §9 Wiederverwendungs-Map)

| Element | Soll (Plan §9) | Ist | Status |
|---|---|---|---|
| RecordingAnimationController | eigene Overlay-Instanz | `RecordingAnimationControllerFactory` baut Instanz mit `BorderGlowAnimation` (gleiche Params wie IME-View-Seite) | ✓ |
| AutoEnterRenderer | eigene Overlay-Instanz | `AutoEnterRendererFactory.create(recordButton)` | ✓ |
| RecordButtonColorController | eigene Overlay-Instanz | `RecordButtonColorControllerFactory.create(recordButton)` | ✓ |
| PulseLayout | Overlay-XML hat `overlay_pulse_layout` | XML hat `<PulseLayout android:id="@+id/overlay_pulse_layout">` mit pulseCount/pulseDuration/pulseStartAlpha/pulseMaxRadiusFactor identisch zum Keyboard | ✓ |
| resolveRecordButtonText | indirekt via resolveOverlayRecordButtonText | resolveOverlayRecordButtonText (TextResolvers.kt) | ✓ |
| resolveRecordButtonTextPipeline | indirekt via resolveOverlayRecordButtonText | ditto | ✓ |
| resolveRecordAction | partiell wiederverwendet | resolveOverlayRecordAction delegiert direkt | ✓ |
| resolveRecordActionPipeline | partiell wiederverwendet | resolveOverlayRecordAction delegiert für Preparing/Running | ✓ |
| resolvePauseAction | direkt referenziert | `LayoutCatalog.OVERLAY_PAUSE.actionResolver = ::resolvePauseAction` | ✓ |
| resolvePauseIcon | direkt referenziert | bleibt | ✓ |
| resolveTrashAction | direkt referenziert | `LayoutCatalog.OVERLAY_TRASH.actionResolver = ::resolveTrashAction` | ✓ |
| resolveOverlayCloseAction | bleibt (ViewMode-spezifisch) | bleibt | ✓ |
| resolveOverlayRecordAction | erweitert um Pipeline-Sub-States | erweitert (+HOVER-Gate, +Active|Paused, +Preparing|Running) | ✓ |
| resolveOverlayPauseAction | gelöscht | gelöscht | ✓ |
| resolveOverlayRecordButtonText | neu | neu in TextResolvers.kt | ✓ |
| RecordingActivityTickerObserver | erweitert um Overlay-Forward | DictateInputMethodService fan-out: imeView + qwertz + `binder.getOverlayBackend()` | ✓ |
| imeSideAffordance | gleicher Lambda via ModuleServices | gleicher Lambda via LocalBinder-Late-Bind (Deviation D-1) | ✓ (anders verdrahtet, aber gleicher Lambda) |
| prepareCatalogStopRecordingIfActive | selbe Funktion vom Lambda gefeuert | unverändert; self-gating | ✓ |
| Send-gate (§2 User-Requirement) | HOVER kein SEND | `resolveOverlayRecordAction` returnt `null` im HOVER + `resolveOverlayRecordEnabled` setzt enabled=false → Android schluckt Clicks + Resolver returnt null als Race-Defense | ✓ (defensive doppelt) |

### Commits
| SHA | Description |
|---|---|
| 2fb5ad9 | [1.1-2.7] Variante 2a — merge overlay RECORD+SEND into one rich slot |
| cd27d70 | [1.3-3.2] OverlayBackend side-channel renderers + R-1 affordance hook |
| (pending) | [4.4] Architecture-Invariant test for overlay R-1 affordance hook |
