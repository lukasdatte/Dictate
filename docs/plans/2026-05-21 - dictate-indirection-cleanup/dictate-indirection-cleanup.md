---
date: 2026-05-21
author: Lukas + Claude Code (research session)
type: Plan
status: Implementer-ready
context: Inventarisiert sämtliche Resterscheinungen alter, imperativer User-Action-Wege im IME-Service nach dem abgeschlossenen Render-Path-Cutover (Vol2). Ziel ist es, jeden Click/Long-Press/Touch/SP-Roundtrip auf das `dispatch(Action)`-Modell zu reduzieren — die Render-Seite ist seit Vol2 Single-Writer-per-Axis-rein, jetzt schließt diese Plan die symmetrische Input-Seite. Erstellt nach expliziter User-Anfrage "tiefgreifende Recherche" am 2026-05-21.
related-plan: 2026-05-21 - dictate-render-cutover-completion-vol2 (Vorgänger — Render-Seite)
related-adrs: ADR-0001, ADR-0005
archive_target: 2026-05-21 - dictate-indirection-cleanup
---

Dieser Plan inventarisiert und beseitigt die letzten **architektonischen
Indirektionen** auf der **User-Action-Seite** des Dictate-IME. Phase 1–6
des Vorgänger-Plans `dictate-render-cutover-completion-vol2` haben die
**Render-Seite** auf Single-Writer-per-Axis gebracht — jeder
View-Attribut-Schreiber ist eindeutig (Catalog/SlotRenderer,
`ImeViewBackend`, `RecordingAnimationController`, `EditBarController`
etc.). Die Render-Path-Cutover-Erzählung ist damit abgeschlossen.

Die **Input-Seite** (Click-Handler, Long-Press-Resolver, SP-Schreibwege,
Callback-Pfade) zeigt jedoch weiterhin Strukturen aus der Vor-Cutover-Ära:
Click-Handler schreiben `SharedPreferences`, vertrauen auf den
`PipelinePrefMirror`-Listener-Pfad, der die Werte zurück in den
Orchestrator-State spiegelt — eine Sieben-Stufen-Indirektion für was
syntaktisch ein `dispatch(Action.X)` sein sollte. Andere Pfade umgehen
den Orchestrator ganz und mutieren Views direkt.

Dieser Plan ist die symmetrische Schwester von Vol2: **Single-Dispatch-
per-Axis auf der Input-Seite**, sodass jede UI-Mutation (Click → State →
Render) den definierten ADR-0001-Pfad nimmt.

## 1. Vision and Motivation

### 1.1 Why this plan exists

Der User hat in der Session 2026-05-21 das folgende Beispiel als
**kanonisches Anti-Pattern** angeführt:

`DictateInputMethodService.java:5031-5052` — `onSmallModeToggled()`:

```java
public void onSmallModeToggled() {
    boolean currentSmall = pipelineBinder != null
            ? pipelineBinder.getState().getValue().getLayout().getSmallMode()
            : DictatePrefsKt.get(sp, Pref.SmallMode.INSTANCE);
    boolean newSmallMode = !currentSmall;
    DictatePrefsKt.put(sp.edit(), Pref.SmallMode.INSTANCE, newSmallMode).apply();
    if (editNumbersAnimator != null) {
        editNumbersAnimator.animateSmallModeToggle(true);
    }
}
```

Sieben Indirektionsstufen für eine 1-bit-Mutation:

1. Click-Handler liest Orchestrator-State (`pipelineBinder.getState()…`)
2. Berechnet invertierten Wert
3. Schreibt SP via `DictatePrefsKt.put(sp.edit(), …).apply()`
4. `SharedPreferences.OnSharedPreferenceChangeListener` feuert
5. `PipelinePrefMirror.sync(key)` matched den Key
6. `PipelinePrefMirror.applyChange()` ruft `store.update { … }`
7. Reactive Render-Path emittiert auf neuem State

Der korrekte Pfad wäre **eine** Stufe:

```java
pipelineBinder.dispatch(Action.LayoutAction.ToggleSmallMode.INSTANCE);
// reducer flips state.layout.smallMode + Effect.PersistPref schreibt SP
```

Der Reducer-Arm existiert bereits (`LayoutModule.kt:81-87`). Was fehlt
ist eine **`Effect.PersistPref<T>`-Infrastruktur**, die die SP-Persistenz
vom Reducer-`copy` orchestriert. Heute ist die SP→State-Richtung gut
abgedeckt (`PipelinePrefMirror`), aber State→SP existiert nur in den
imperativen Click-Handlern.

### 1.2 What problem this solves

- **Single-Dispatch-Invariante (ADR-0001 F-8):** Jede State-Mutation
  geht durch `dispatch(Action)`. Heute verletzen 6 Click-Handler diesen
  Vertrag durch direkte SP-Schreibwege.
- **Testbarkeit:** Reducer + Effect lassen sich JVM-only testen; ein
  SP-roundtrip-Test braucht den Android-Framework-Mock.
- **Reaktivitäts-Garantien:** Bei pre-Bind (`pipelineBinder == null`)
  geht der heutige Pfad an der State-Wahrheit vorbei; die nach-Bind-
  emittierte initiale Mirror-Synchronisation kann jetzt nicht mehr
  fehlen (siehe Risk-§ unten).
- **Race-Klassen:** Die Reihenfolge "SP-write zuerst, dann
  state-derived UI" ist heute in jedem Handler ein per-Hand-Argument
  ("Phase 5.B race window"); mit Effect-Order ist es eine zentrale
  Garantie.
- **Diskoverabilität:** Künftige Maintainer schauen nach
  `dispatch(LayoutAction.X)`, nicht nach `sp.edit().put(…)` — der
  Code wird seine eigene Architektur erzählen.

### 1.3 Discarded alternatives

1. **Status quo lassen / als "post-Vol2 Tech-Debt" akzeptieren.** —
   Verworfen, weil jeder neue Feature-Toggle das Anti-Pattern
   reproduziert (siehe `onAudioFocusToggled` :5094, das die maximal
   verschachtelte Version der Roundtrip-Reihenfolge ist).
2. **Nur die Render-Seite beibehalten, SP→State weiter als Mirror.** —
   Verworfen, weil PipelinePrefMirror als Single-SoT-Hülle nur dann
   tragfähig ist, wenn State→SP **derselben** Achse durch denselben
   Module-Lens geht. Sonst ist Mirror keine SoT, sondern eine
   Konvention.
3. **Effect.PersistPref ins jeweilige Modul, Module-Pref-Bindings als
   Phase-2.** — Verworfen, weil "Phase 2" in PipelinePrefMirror seit
   2026-05-07 als Out-of-Scope-Marker steht (Spec 1 §15). Wenn wir
   State→SP wollen, machen wir es jetzt strukturell.

## 2. Acceptance Criteria

Jedes Kriterium ist als **technisch verifizierbarer Invariant** formuliert.

- **AC-1: SP-Write-Verbot in Click-Handlern für gespiegelte Prefs.**
  `grep -nE "DictatePrefsKt\.put|sp\.edit\(\)\.put"
  app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`
  liefert **keinen Treffer** für Keys, die `PipelinePrefMirror.applyChange`
  spiegelt (SmallMode / SingleRowMode / Animations / AudioFocus /
  UseBluetoothMic / Vibration / ResendButton / RewordingEnabled /
  AutoFormattingEnabled / InstantOutput / AutoEnter / Theme /
  AccentColor / OverlayCharacters / OutputSpeed / Overlay-Position*).
  Treffer für ungespiegelte interne Prefs (LastFileName / UserId /
  TranscriptionAudioFile) sind erlaubt — sie sind reine IME-Persistenz
  ohne State-Achse.

- **AC-2: Effect-getriebene Pref-Persistenz.** Jede gespiegelte Pref
  hat einen einzigen Schreiber: das zugehörige Modul über
  `Effect.PersistPref<T>(pref, value)` oder eine modulspezifische
  Spezialform. Test:
  `grep -rn "Effect.PersistPref\|Pref\\.\\w\\+\\.INSTANCE" app/src/main/java/net/devemperor/dictate/state/modules/`
  zeigt für jede AC-1-Pref genau eine Schreiber-Stelle.

- **AC-3: PipelinePrefMirror SP→State bleibt erhalten** (für externe
  Settings-Activity-Writes, Migrationen, Test-Setups). Mirror und
  Effect schreiben dieselben Keys — die Effect-Schreibung feuert
  zwar den Mirror-Listener, der dann `store.update { copy(…) }` mit
  unverändertem Inhalt ruft (idempotent: Diff-Check
  `applyChange(current, key)` produziert `current.copy(…)` mit
  identischen Werten — kein Re-Emit, kein Loop). Test:
  `PipelinePrefMirrorTest.kt` ergänzt eine "Effect-Write-feeds-Mirror-
  Idempotent"-Annahme.

- **AC-4: Reducer-Arm-Vollständigkeit.** Jeder in AC-1 genannte
  Pref-Toggle hat einen Reducer-Arm in `state/modules/`. Heute fehlen:
  `Action.FeatureToggleAction.ToggleVibration` (Reducer gibt `null`
  zurück, weil die Achse in `AudioState` lebt — der Plan löst das,
  indem dieser Action-Arm zu `AudioAction.ToggleVibration` umzieht
  und seinen Reducer-Body bekommt).

- **AC-5: Pre-Bind-Verhalten.** Ein Click vor `pipelineBinder != null`
  ist ein No-Op-Toast oder eine deterministische Defensive (kein
  stiller SP-Write mehr, der den State später inkonsistent macht).
  Validation: jeder migrierte Handler beginnt mit
  `if (pipelineBinder == null) { …no-op… return; }`.

- **AC-6: Keine direkte View-Mutation außerhalb der zugelassenen
  Render-Owner.** Liste der zugelassenen Owner (Stand Vol2 Phase 6):
  `ImeViewBackend`, `PromptVisibilityController`, `EditBarController`,
  `EmojiController`, `OverlayCharactersController`,
  `RecordingAnimationController`, `AutoEnterRenderer`,
  `RecordButtonColorController`, `PipelineStepRowRenderer`,
  `EditNumbersAnimator`, `OverlayResetHandler`, `ContentAreaController`,
  `InfoBarController`, `OverlayPermissionGate`. Test:
  `grep -nE "\\.(setVisibility|setText|setEnabled|setForeground|setBackgroundColor|setAlpha|setRotation|setSelected|setTextColor)\\("
  app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`
  liefert keinen Treffer mehr außerhalb von Test- /
  Pre-Bind-Fallback-Sites mit explizitem `@VisibleForTesting` /
  `// pre-bind fallback`-Tag.

- **AC-7: Keine Custom-`OnSharedPreferenceChangeListener` außerhalb
  von `PipelinePrefMirror`.** Heute existieren in
  `DictateInputMethodService.java` zwei: `inputLanguagesListener`
  (Zeile 1053) und `audioFocusListener` (Zeile 1072). Beide müssen
  entweder a) auf PipelinePrefMirror umgezogen werden (für die
  gespiegelten Prefs) oder b) als legitimer
  Settings-Activity-External-Refresh-Pfad mit Tag-Kommentar
  bestehen bleiben (für `InputLanguages`/`InputLanguagePos`, die
  nicht im Mirror sind — siehe Open Question OQ-1).

- **AC-8: AudioFocusChangeListener routet via dispatch.** Heute ruft
  der OnAudioFocusChangeListener (Zeile 671) direkt
  `recordingStateController.togglePause()` — bypasst den Orchestrator.
  Nach Migration: `dispatch(Action.AudioAction.OnAudioFocusGrantChanged(false))`,
  Reducer cascades zur Recording-Pause via `AudioModule.onCrossModuleStateChange`
  (Mechanik existiert bereits in AudioModule KDoc).

- **AC-9: Effects-Ordnung.** Wenn ein Action eine Pref persistiert
  *und* eine UI-Affordance auslöst (z.B. Bounce-Animation bei
  SingleRowMode), gilt die Ordnung: Reducer flippt State →
  Effect.PersistPref → side-channel Animation. Side-channel-Animationen
  wie `editNumbersAnimator.animateSmallModeToggle(true)` bleiben
  IME-side-affordances (kein State-Bit) und werden weiterhin vom
  Click-Handler nach dem Dispatch gefeuert — dispatch ist synchron
  (Spec 1 §4.0.1), darum ist die State→Render-Reihenfolge garantiert.

## 3. Inventory — full catalog

Inventar aller Indirektions-Sites in `DictateInputMethodService.java`.
Jede Zeile zeigt **Site (Datei:Zeile)**, Symptom, Pattern heute, Soll-
Pattern, vorhandene Infrastruktur, Effort und Severity.

> **Severity-Legende:** 🔴 = latentes Bug-Risiko heute (Race / silent
> mismatch / double-write). 🟡 = reine Architektur-Schuld (nicht
> Bug-aktiv, aber bricht eine Architektur-Invariante). 🟢 = kosmetisch
> (kommentar-mässig veraltet, aber funktional korrekt).
>
> **Effort-Legende:** S = ≤30 min. M = 30 min – 2 h. L = > 2 h.

### 3.1 Category A — SP-Roundtrip statt Action-Dispatch

Click-Handler liest State / pref, berechnet inversen Wert, schreibt SP,
verlässt sich auf PipelinePrefMirror als Transport zurück.

| # | Site | Symptom | Pattern heute | Soll-Pattern | Infrastruktur | Eff | Sev |
|---|------|---------|---------------|--------------|---------------|-----|-----|
| A-1 | `DictateInputMethodService.java:5031-5052` (`onSmallModeToggled`) | Liest `state.layout.smallMode`, schreibt `Pref.SmallMode`, Mirror spiegelt zurück | `boolean cur = pipelineBinder.getState().getValue().getLayout().getSmallMode(); DictatePrefsKt.put(sp.edit(), Pref.SmallMode, !cur).apply();` | `pipelineBinder.dispatch(Action.LayoutAction.ToggleSmallMode.INSTANCE);` + `LayoutModule` emittiert `Effect.PersistPref(Pref.SmallMode, nextState.smallMode)` | **Action existiert** (`Action.kt:352`), **Reducer-Arm existiert** (`LayoutModule.kt:81-95`). **Effect.PersistPref fehlt** (Modul-Effects sind aktuell leer, KDoc :43). | M | 🟡 |
| A-2 | `DictateInputMethodService.java:5054-5091` (`onSingleRowModeToggled`) | Liest Pref direkt, schreibt invertiert, Mirror spiegelt | `boolean cur = DictatePrefsKt.get(sp, Pref.SingleRowMode); DictatePrefsKt.put(sp.edit(), Pref.SingleRowMode, !cur).apply();` | `pipelineBinder.dispatch(Action.LayoutAction.ToggleSingleRowMode.INSTANCE);` + Effect.PersistPref(SingleRowMode) | **Action existiert** (`Action.kt:351`), **Reducer-Arm existiert** (`LayoutModule.kt:76-80`). Effect fehlt. | M | 🟡 |
| A-3 | `DictateInputMethodService.java:5093-5133` (`onAudioFocusToggled`) | Liest Pref, schreibt invertiert, ruft direkt RecordingStateController + EditBarController.refresh | 4-stufiger imperativer Pfad (SP-write + setAudioFocusRuntime + refreshAudioFocusIcon-Twin) | `pipelineBinder.dispatch(Action.AudioAction.ToggleAudioFocusPref.INSTANCE);` + Effect.PersistPref(AudioFocus) + Live-Hook via Module-Effect oder cross-module-cascade | **Action existiert** (`Action.kt:365`), **Reducer-Arm existiert** (`AudioModule.kt:142-145`). Live-Hook (`recordingStateController.setAudioFocusRuntime`) muss via Cross-Module-Cascade ins RecordingModule wandern, oder als AudioModule-Effect mit `services.audioFocus.applyRuntime(enabled)`. EditBarController.refreshAudioFocusIcon ist Render-Code und gehört zur Render-Reaktion auf `state.audio.audioFocusEnabledPref` (heute bereits über Catalog `iconResolver = ::resolveAudioFocusIconForSlot` für die Main-Button-Twin; der Edit-Bar-Twin braucht einen analogen Reactive-Hook im EditBarController). | L | 🔴 |
| A-4 | `DictateInputMethodService.java:3350` (`startRecording`) | Schreibt `Pref.LastFileName` aus dem Click-Handler | `DictatePrefsKt.put(sp.edit(), Pref.LastFileName, audioFile.getName()).apply();` | Kandidat für **D-Pattern (siehe §3.4)** — dies ist *kein* SP-Roundtrip-Antipattern, weil `LastFileName` *nicht* im PipelinePrefMirror gespiegelt wird. Die Persistenz dient der `RESEND`-Recovery (Read durch `KeyboardVisibilityPredicates.resolveResendVisibility` :2802-2806 + `ResendAction.MarkLastAudio` cascade). Soll: Effect der `RecordingAction.StartRecording` (RecordingModule) — Reducer setzt `state.recording.Preparing`, parallel Effect.PersistLastFileName | Keine Module-Effect-Schreibe-Infrastruktur für ungespiegelte Prefs heute; in den Recording-Modul ziehen | M | 🟡 |
| A-5 | `DictateInputMethodService.java:2941-2945` (`transcribeImportedAudioFileViaOrchestrator`) | Schreibt `Pref.LastFileName` + entfernt `Pref.TranscriptionAudioFile` aus dem Import-Click | siehe A-4: zwei Pref-Writes in einem Handler, beide nicht-gespiegelt aber click-getrieben | Effect der `PipelineAction.TriggerPipeline` (Import-Pfad) oder dedizierter `Action.RecordingAction.OnAudioFileImported(file)` mit RecordingModule-Effect | Kein Action für Import-File-Source heute (Pfad bypasses RecordingModule per Design — siehe `Action.PipelineAction.TriggerPipeline` KDoc); Action wäre additiv | M | 🟡 |
| A-6 | `DictateInputMethodService.java:2649` (`setLanguageFromPicker`) | Schreibt `Pref.InputLanguages` + `Pref.InputLanguagePos` via `LanguageResolver.setLanguage(sp, code)`, danach Push via `LanguageAction.RefreshFromPref` | `LanguageResolver.setLanguage(sp, code); pushPermanentLanguageToOrchestrator();` (zweistufig — SP-Write zuerst, Push danach) | Eine einzige `Action.LanguageAction.SetEffectiveLanguage(code)` mit LanguageModule-Effect.PersistInputLanguages, die curated-list + pos atomisch persistiert | Action `RefreshFromPref` ist *Refresh aus Pref* — eine Neue-Setter-Action ist sauberer. LanguageModule.reduce müsste die curated-Liste verwalten — heute ist das Logik in `LanguageResolver.persistInputLanguagesAndPos` Java-side. | L | 🟡 |

**Total Category A: 6 Sites.** A-3 hat 🔴 wegen aktiver Race-Window-
Argumentation in den Kommentaren — die heutige Ordnung 1.SP→2.Hook→3.Icon
ist per-Hand-justiert und bricht bei jeder Modifikation des Handlers.

### 3.2 Category B — Direkte View-Mutation außerhalb der Render-Owner

`view.setVisibility/setText/setEnabled/setForeground/setBackgroundColor/
setAlpha/setRotation/setSelected/setTextColor` in
`DictateInputMethodService.java` außerhalb der in AC-6 gelisteten
legitimen Render-Backend-Schreiber.

| # | Site | Symptom | Pattern heute | Soll-Pattern | Infrastruktur | Eff | Sev |
|---|------|---------|---------------|--------------|---------------|-----|-----|
| B-1 | `DictateInputMethodService.java:1485` (im `OverlayOnboardingObserver` lambda) | `overlayPermissionInfobar.setVisibility(pending ? VISIBLE : GONE)` — IME-Service mutiert View direkt aus dem Observer-Callback | Lambda im Observer-Setup | OverlayPermissionInfobar-Visibility ist eine **eigene Render-Achse** und gehört in einen dedizierten Renderer / Backend (z.B. `OverlayPermissionInfobarRenderer.kt` analog zu `OverlayPermissionGate`). Der Observer wäre dann reiner StateFlow-Collector ohne View-Referenz. | Observer + Render-Backend-Skeleton existieren — keine neue Mechanik nötig | S | 🟡 |
| B-2 | `DictateInputMethodService.java:2802-2806` (im pre-bind-fallback) | `resendButton.setVisibility(KeyboardVisibilityPredicates.resolveResendVisibility(...))` | Pre-bind fallback — bewusst defensive Direktmutation | **Legitim** als dokumentierter Pre-Bind-Fallback. Kommentar erweitern um `// PRE-BIND FALLBACK — single allowed direct write of resend visibility outside RenderBackend; safe because no binder ⇒ no reactive path` | — | S | 🟢 |
| B-3 | `DictateInputMethodService.java:2854-2856` (`onStartInputView` Theme-block) | `dictateKeyboardView.setBackgroundColor / emojiPickerCl.setBackgroundColor / qwertzContainer.setBackgroundColor` | Theme-Achse — 3 imperative Schreibwege auf 3 Container-Views beim Start | Theme ist heute *bewusst* nicht-state-getrieben (Spec 2 §9.2 "Theme-Mutation ist eine separate Achse"); diese 3 Writes sind die Anwendung des Theme auf die 3 ContentArea-Container. **Verbleibender Architektur-Bruch:** sie sind nicht in einem Theme-Renderer gebündelt (ImeViewBackend.applyTheme adressiert nur die 8 Buttons, EditBar/Emoji-Theme adressieren ihre Sub-Bereiche). Sub-Renderer "ContainerThemeRenderer" oder Integration in `ImeViewBackend.applyTheme` als zusätzlichen Schritt | ImeViewBackend.applyTheme ist die natürliche Stelle (siehe :2873-2875). Erweitern um Container-Pass. | S | 🟡 |
| B-4 | `DictateInputMethodService.java:2859` | `for (TextView tv : textColorViews) tv.setTextColor(accentColor)` — infoTv + emojiPickerTitleTv | Klein-Theme-Apply | Wie B-3 — ins ImeViewBackend.applyTheme / EmojiController.applyTheme ziehen | EmojiController.applyTheme existiert bereits — fehlt das emojiPickerTitleTv. InfoBarController hat noch keinen applyTheme. | S | 🟡 |
| B-5 | `DictateInputMethodService.java:3160-3184` (`updateEnterButtonIcon`) | `enterButton.setForeground(getDrawable(...))` 4× — abhängig von `EditorInfo.imeOptions` (Search/Send/Done/Default) | Imperative Foreground-Mutation aus `onStartInputView` :2731 | Catalog-`ButtonSlot` für ENTER hat heute **kein** `iconResolver` (LayoutCatalog ENTER-slots :151/:208/:328/:383/:481 — alle ohne iconResolver). Soll: ENTER-Slot bekommt einen `foregroundResolver = (state, ctx) -> Drawable?`, der `EditorInfo` lesen kann. `EditorInfo` lebt aktuell **nicht** im DictateUiState; es ist eine pro-Bind transiente Information. Optionen: a) Achse `state.imeOptions` (Spec 1 §15.6 KeyboardInputModule erweitern), b) ButtonSlot bekommt einen `ImeViewServices`-Parameter (analog zu `ModuleServices`) | EnterIcon-Drawable-Set ist statisch (4 Drawables); KeyboardInputModule existiert mit `Unit` state — Erweiterung auf `KeyboardInputState(enterIcon: EnterIconKind)` ist additiv | M | 🟡 |
| B-6 | `DictateInputMethodService.java:4060` (in `onShowResend` mainHandler.post lambda) | `resendButton.setVisibility(View.VISIBLE)` — pre-bind fallback im UNBOUND-Pfad | Pre-bind fallback wie B-2 | **Legitim** als dokumentierter Pre-Bind-Fallback (KDoc :4049 sagt das explizit). Symmetrisch zu B-2; gleicher Tag-Kommentar | — | S | 🟢 |
| B-7 | `DictateInputMethodService.java:4445` (`deleteOneCharacter` Helper) | `breakIterator.setText(before)` | **KEIN View-Mutation-Site** — `BreakIterator.setText` ist ICU-API auf einen String, nicht View. False positive der grep-Pattern. | — | — | — | n/a |

**Total Category B: 5 echte Sites** (B-7 raus, B-2 + B-6 als legitim
markiert, also 3 echte Fixes: B-1, B-3+B-4 zusammen, B-5).

### 3.3 Category C — Callbacks / Listener neben StateFlow

Custom Callback-Interfaces, Listener-Registrierungen oder
Service-bound Observer, die User-Aktionen oder State-Wechsel **außerhalb**
des `state.collect { render }`-Flusses transportieren. Eine
Callback-Chain ist legitim, wenn sie in ≤1 Hop auf
`dispatch(Action.X)` endet (Modell-Beispiel:
`EditBarController.Callback`). Sie ist eine Indirektion, wenn sie auf
SP-Write oder direkter View-Mutation endet.

| # | Site | Symptom | Pattern heute | Soll-Pattern | Infrastruktur | Eff | Sev |
|---|------|---------|---------------|--------------|---------------|-----|-----|
| C-1 | `DictateInputMethodService.java:661-675` (`audioFocusRequest` setOnAudioFocusChangeListener) | `if (focusChange == AUDIOFOCUS_LOSS) recordingStateController.togglePause();` — bypasst Orchestrator | Direkt-Mutation eines IME-side Controller-Pfads | `dispatch(Action.AudioAction.OnAudioFocusGrantChanged(granted = focusChange != AUDIOFOCUS_LOSS))` — Reducer-Arm existiert (`AudioModule.kt:121-127`), Cross-Module-Cascade Audio→Recording (Audio focus lost → PauseRecording) ist in AudioModule KDoc dokumentiert :17-26 | Mechanik dokumentiert, möglicherweise schon implementiert (verifizieren!) — wenn ja, ist der Click-Handler-Pfad einfach toter Code; wenn nicht, Cross-Module-Cascade ergänzen | S | 🔴 |
| C-2 | `DictateInputMethodService.java:1053-1068` (`inputLanguagesListener` registriert auf sp) | Custom OnSharedPreferenceChangeListener für `InputLanguages`/`InputLanguagePos` — terminiert in `pushPermanentLanguageToOrchestrator()` der einen dispatch macht | 1-Hop bis dispatch — **terminiert in dispatch**, also **legitim** | Bleibt — dokumentieren als "External Settings-Activity refresh path; not a roundtrip because InputLanguages is not mirrored". Tag-Kommentar präzisieren | — | S | 🟢 |
| C-3 | `DictateInputMethodService.java:1072-1097` (`audioFocusListener` registriert auf sp) | Custom OnSharedPreferenceChangeListener für `AudioFocus` — terminiert in `editBarController.refreshAudioFocusIcon(newValue)` + `recordingStateController.setAudioFocusRuntime(newValue)` | **bypasst Orchestrator** (state.audio.audioFocusEnabledPref wird vom PipelinePrefMirror gespiegelt, dieser Listener feuert *parallel*) — doppelter Pfad, der bei A-3 Migration redundant wird | Mit A-3 obsolet — entfernen. Der PipelinePrefMirror bedient sich derselben SP-Listener-Mechanik und feuert für `Pref.AudioFocus` (PipelinePrefMirror :199-200). Die Live-Hook + Edit-Bar-Twin-Refresh-Effekte werden Effect-getrieben. | Wird mit A-3 frei | S | 🟡 |
| C-4 | `DictateInputMethodService.java:2073-2135` (`recordingStateController.setCallback(new Callback() { … })`) | RecordingStateController-Callback ruft `qwertzRecordingController.updateQwertzRecButton/onAmplitude/onTimerTick`, `updatePromptButtonsEnabledState`, `transcribeImportedAudioFileViaOrchestrator`, `showInfo`, `updateKeepScreenAwake`, etc. — Callback-Chain mit ~7 Sub-Effekten | Klassischer Legacy-Callback-Pfad — die meisten Calls sind heute auf der Bound-Pfad dead-code (KDoc :2058-2065 dokumentiert das), aber die Callback-Struktur ist noch vorhanden. | Auf bound-Pfad ist legacy controller never started → komplett toter Code. Soll: Callback-Set ganz entfernen, RecordingStateController.setCallback aufrufen entfernen, sobald sichergestellt ist dass kein bound-Pfad-Caller ihn noch braucht. Risk: import-audio-file path (`onRecordingCompleted` :2104) — der Pfad geht über `transcribeImportedAudioFileViaOrchestrator`, der per **Action.PipelineAction.TriggerPipeline** dispatched. Migration: A-5 löscht diesen Pfad, dann fällt C-4 mit. | Cleanup nach A-5 | M | 🟡 |
| C-5 | `DictateInputMethodService.java:1107-1158` (`pipelineUiStateObserver = new PipelineUiStateObserver(...)`) | StateFlow-Collector mit Callback-Chain, ruft `syncQueueOrder` / `setLanguageChipEnabled` / `refreshLanguageChip` / `qwertzRecordingController.updateQwertzRecButton` etc. | **Legitim** — ist der explizit als "Non-Renderer-Responsibilities of PipelineUiCallback" dokumentierte State-Collector (KDoc :1099-1104). Terminiert in *Adapter*-Methoden + QwertzController-Calls, die selbst pure UI-Apply sind | Bleibt. Tag-Kommentar bestätigt die Architektur-Rolle. Mittelfristig kann `qwertzRecordingController.updateQwertzRecButton` durch einen eigenen Renderer ersetzt werden (analog AutoEnterRenderer) — out of scope für diesen Plan. | — | — | 🟢 |
| C-6 | `DictateInputMethodService.java:1497-1526` (`recordingTickerObserver = new RecordingActivityTickerObserver(...)`) | StateFlow-Collector für Recording-Aktivität — ruft `imeViewBackend.onTimerTick/onAmplitude` + `qwertzRecordingController.onTimerTick/onAmplitude` | **Legitim** — explizit als Side-Channel-Ticker dokumentiert (KDoc :1491-1496). Terminiert in pure View-Apply auf Renderer | Bleibt. | — | — | 🟢 |
| C-7 | `DictateInputMethodService.java:1478-1488` (`overlayOnboardingObserver = new OverlayOnboardingObserver(...)`) | StateFlow-Collector — terminiert in `overlayPermissionInfobar.setVisibility(...)` (siehe B-1) | Direkter View-Mutation-Terminus → Indirektion | Mit B-1 zusammen — Observer pumpt in einen `OverlayPermissionInfobarRenderer` statt direkt View-Schreibung. | siehe B-1 | S | 🟡 |
| C-8 | `DictateInputMethodService.java:904-934` (overlay-perm-grant + dismiss button setOnClickListener) | Direct `setOnClickListener` lambda auf dem permission-infobar — dispatched dann korrekt | Lambda dispatched in ≤1 Hop → **legitim** | Bleibt. Bei B-1/C-7 Migration zu eigenem Renderer wandern die Listener mit. | — | — | 🟢 |
| C-9 | `DictateInputMethodService.java:1011-1014` (`promptTrashBtn.setOnClickListener(v -> { vibrate(); onTrashClicked(); })`) | Listener ruft `onTrashClicked()` — der wiederum entweder dispatched `CancelReprocessStaging` oder `cancelEffectiveRecording()` (selbst dispatched) | 2-Hop bis dispatch, alle Hops sind reine helper-Funktionen ohne View-Mutation → **akzeptabel** als IME-side click-binding, da kein logical-button-slot dafür existiert (Prompt-Trash ist außerhalb der Catalog-Slots) | Bleibt. | — | — | 🟢 |
| C-10 | `DictateInputMethodService.java:2219-2289` (`new PromptsKeyboardAdapter.AdapterCallback() { … }`) | RecyclerView-Adapter-Callback für Prompt-Clicks — branched in 5 Modi (instant prompt, select all, clear queue, add prompt, regular prompt) — terminiert teils in dispatch (`startRecording`/`stopRecording` selbst dispatchen), teils in `promptQueueManager.togglePrompt(id)` (kein dispatch — direkte Mutation des nicht-orchestrator-State `promptQueueManager`) | Mehrstufige Indirektion mit Mischung Dispatch / Non-Dispatch | `promptQueueManager` ist heute außerhalb des Orchestrator-States; er hat eigene `PromptQueueCallback` (Zeile 28 in PromptQueueManager.kt). Soll-Modell: PromptQueue-Achse migriert ins Orchestrator-State (eigenes `PromptQueueModule`), Adapter-Callback dispatcht `Action.PromptQueueAction.Toggle(id)` / `Clear` / etc. | **Architektur-Erweiterung**, kein bestehendes Modul. **Bewusst out of scope** dieses Plans — als § Open Question OQ-2 markiert. | — | M | 🟡 |

**Total Category C: 6 echte Indirektionen** (C-2 als legitim
dokumentiert; C-5/C-6/C-8/C-9 als legitim bestätigt; C-1/C-3/C-4/C-7
sind echte Fixes; C-10 ist Architektur-Erweiterung → Open Question).

### 3.4 Category D — Pref-Writes aus Click-Handlern statt aus Effects

Reine Untermenge von Category A: SP-Write **ist** der primäre Output
(nicht Transport-zu-State).

| # | Site | Symptom | Pattern heute | Soll-Pattern | Infrastruktur | Eff | Sev |
|---|------|---------|---------------|--------------|---------------|-----|-----|
| D-1 | `DictateInputMethodService.java:693-695` (`onCreate` user-id generation) | `DictatePrefsKt.put(sp.edit(), Pref.UserId.INSTANCE, generatedUserId)` — schreibt UserId, wenn null | One-shot Initialization, kein Click-Pfad → **legitim als IME-init**. UserId ist nicht state-relevant (App-installation identifier) | Bleibt — als IME-init dokumentieren. | — | — | 🟢 |
| D-2 | `DictateInputMethodService.java:2941-2945` (`transcribeImportedAudioFileViaOrchestrator`) | Schreibt LastFileName + clears TranscriptionAudioFile aus dem File-Picker-Pfad | identisch zu A-5 — bewusst doppelt aufgeführt (A vs. D ist eine **Klassifikation**, nicht eine Verdopplung): A-5 modelliert es als Roundtrip-Antipattern, D-2 als "primärer Pref-Write" — beide sehen denselben Fix | siehe A-5 | siehe A-5 | siehe A-5 | siehe A-5 |
| D-3 | `DictateInputMethodService.java:3350` (`startRecording`) | LastFileName-Write aus dem startRecording-Pfad | identisch zu A-4 | siehe A-4 | siehe A-4 | siehe A-4 | siehe A-4 |

**Total Category D: 1 distinkter neuer Site (D-1, legitim).** D-2/D-3
sind alternative Klassifikationen von A-5/A-4. Im Sequencing-Plan
unten erscheinen sie nur einmal.

### 3.5 Cross-cutting summary

| Kategorie | Sites (gesamt) | davon real zu migrieren | davon 🔴 | davon 🟡 | davon 🟢 |
|-----------|----------------|--------------------------|----------|----------|----------|
| A — SP-Roundtrip | 6 | 6 | 1 (A-3) | 5 | 0 |
| B — Direkt-View-Mutation | 7 (incl. false pos.) | 3 (B-1, B-3+B-4, B-5) | 0 | 3 | 2 (B-2, B-6 legitim) |
| C — Callbacks neben StateFlow | 10 | 4 (C-1, C-3, C-4, C-7) | 1 (C-1) | 3 | 5 (legitim) |
| D — Pref-Writes als Primär-Output | 3 | 0 (alle Duplikate oder legitim) | 0 | 0 | 1 |
| **Gesamt** | — | **13 distinkte Fixes** | **2** | **10** | **1** |

## 4. Migration phases / blocks

Vorschlag: 4 Blöcke, sequentiell. Jeder Block ist als Standalone
deployable, jedes Chunk hat ein Reducer-Test als Acceptance.

### Block 1 — `Effect.PersistPref<T>` Infrastruktur (Foundation)

**Ziel:** Die fehlende State→SP-Schreib-Infrastruktur einführen. Kein
Verhalten ändert sich; nur eine generische Mechanik wird verfügbar.

- **Chunk 1.1** — Definiere `interface PrefPersistenceService` in
  `state/ModuleServices.kt` mit `<T> persist(pref: Pref<T>, value: T)`.
  Implementation `SharedPrefsPersistenceService` ruft
  `DictatePrefsKt.put(sp.edit(), pref, value).apply()`.
  ModuleServices als Konstruktor-Arg im DictateOrchestrator. **Effort: S.**
- **Chunk 1.2** — Module-lokales Pattern dokumentieren: Jedes Modul,
  das mirror-spiegelt-Werte besitzt, fügt einen
  `sealed interface Effect` `data class PersistPref<T>(val pref: Pref<T>,
  val value: T)` hinzu, mit `runEffect(effect, services) = services.prefs.persist(effect.pref, effect.value)`.
  Tests: `LayoutModuleTest.kt`, `FeatureToggleModuleTest.kt`,
  `AudioModuleTest.kt`. **Effort: M.**
- **Chunk 1.3** — Test für die Idempotenz-Garantie: Wenn Effect.PersistPref
  einen Wert schreibt, der PipelinePrefMirror-Listener fired, ruft
  `applyChange` → `current.copy(layout = current.layout.copy(...))` mit
  unverändertem Inhalt → StateFlow emittiert wegen Daten-Gleichheit nicht.
  Test: `PipelinePrefMirrorTest.kt` + Reducer-Test. **Effort: S.**

**AC für Block 1:** `Effect.PersistPref` ist Module-API. Mindestens
ein Modul (LayoutModule) emittiert es. Keine Verhaltensänderung — nur
neuer Effect-Pfad.

### Block 2 — Layout- + Feature-Toggle-Migration (Category A core)

**Ziel:** Die kanonischen Vol2-Kandidaten kippen.

> **Status 2026-05-21:** Chunk 2.1 + 2.2 sind im Working Tree
> **bereits implementiert** (Pair-Programming-Session vom selben Tag,
> uncommitted zum Zeitpunkt der Plan-Finalisierung). Block 1 wurde
> **nicht** in der generischen Form eingeführt — stattdessen wurden
> modul-lokale `LayoutModule.Effect.PersistSmallMode(value: Boolean)`
> + `Effect.PersistSingleRowMode(value: Boolean)` direkt in
> `LayoutModule` definiert. Der Pre-Bind-Fallback der Click-Handler
> in `DictateInputMethodService.java:5031-5089` ist die persistente
> SP-Schreibung; der bound-Pfad dispatcht `Action.LayoutAction.{Toggle
> SmallMode, ToggleSingleRowMode}`. Begleitende Tests sind in
> `LayoutModuleTest.kt` ergänzt (7 neue Tests für Effect-Emission +
> Idempotenz). Implementer soll Chunk 2.1 + 2.2 als **abgeschlossen**
> behandeln; Chunk 2.3 + 2.4 verbleiben offen. Block 1 (generische
> `Effect.PersistPref<T>`) wird in **Block 3** nachgezogen, sobald die
> generische Form für die Audio-Module-Effects ihren Wartungs-Hebel
> bekommt (siehe §6.1 Risk-Anmerkung am Ende).

- **Chunk 2.1 (A-1)** ✅ — `onSmallModeToggled` → dispatch +
  `LayoutModule.Effect.PersistSmallMode`. **Effort: S — DONE.**
- **Chunk 2.2 (A-2)** ✅ — `onSingleRowModeToggled` → dispatch +
  `LayoutModule.Effect.PersistSingleRowMode`. **Effort: S — DONE.**
- **Chunk 2.3** — Feature-Toggles, falls Settings-Activity-Hooks oder
  zukünftige In-IME-Toggle-Buttons sie braucht: Rewording /
  AutoFormatting / InstantOutput / AutoEnter — Reducer-Arme existieren,
  aber Click-Sites müssen identifiziert werden (heute außerhalb des
  IME-Service?). **Optional / nur wenn gefunden — Effort: S.**
- **Chunk 2.4** — `ToggleVibration` umziehen von
  `FeatureToggleAction.ToggleVibration` zu `AudioAction.ToggleVibration`
  (mit Reducer-Body). Action-Klasse umbenennen und AudioModule reduce
  ergänzen. **Effort: M.** Spec 1 §15 erlaubt das ausdrücklich (KDoc).

**AC für Block 2:** SmallMode + SingleRowMode + Vibration laufen via
dispatch. AC-1-Grep findet keine Treffer mehr für diese 3 Keys im
IME-Service.

### Block 3 — Audio-Pfad-Migration (Category A schwer + Category C)

**Ziel:** Audio-Focus als Lighthouse-Pattern für die komplexen Pfade.

> **Foundation-Vorlauf (per User-Entscheidung 2026-05-21):**
> Block 1 (generische `Effect.PersistPref<T>` + `PrefPersistenceService`
> in `ModuleServices`) wurde in Chunk 2.1/2.2 bewusst übersprungen, weil
> die Layout-Migration mit 2 Sites die generische Infrastruktur nicht
> brauchte. Hier — bei 3+ Audio-Sites in Block 3 + LastFileName/Language
> in Block 4 — lohnt sich die generische Form. **Chunk 3.0** zieht
> Block 1 als Foundation nach: `PrefPersistenceService`-Interface +
> `SharedPrefsPersistenceService`-Implementation in
> `state/ModuleServices.kt`, Konstruktor-Erweiterung des
> `DictateOrchestrator`. Die existierenden `LayoutModule.Effect.PersistSmallMode`/
> `PersistSingleRowMode` aus Chunk 2.1/2.2 können entweder bleiben
> (modul-lokale Variante als Override) ODER auf die generische Form
> migriert werden (LayoutModule.Effect.PersistPref<Boolean>). Empfehlung
> für den Implementer: belassen wie sie sind (kein Verhaltens-Unterschied,
> nur kosmetisch), um den Refactor-Scope zu begrenzen. **Effort: S.**

- **Chunk 3.0** ⭐ (Foundation-Vorlauf, neu) —
  `PrefPersistenceService`-Infrastruktur per Block 1 §4 nachziehen.
  Konkret:
  1. `interface PrefPersistenceService { fun <T> persist(pref: Pref<T>, value: T) }`
     in `state/ModuleServices.kt`.
  2. `class SharedPrefsPersistenceService(sp: SharedPreferences) : PrefPersistenceService`
     ruft `DictatePrefsKt.put(sp.edit(), pref, value).apply()`.
  3. `ModuleServices`-Konstruktor um `prefs: PrefPersistenceService`-Parameter
     erweitern; in `DictatePipelineService` mit
     `SharedPrefsPersistenceService(sharedPrefs)` injizieren.
  4. Bestehende `LayoutModule.runEffect`-Pfade bleiben unverändert
     (verwenden weiterhin `services.sharedPrefs.edit().put(…).apply()`
     direkt; Refactor zur generischen Form ist freiwillig und nicht
     blockierend). **Effort: S.**

- **Chunk 3.1 (A-3 Part 1)** — `Effect.PersistAudioFocusPref` in
  AudioModule. Verwendet die generische Form
  `Effect.PersistPref(Pref.AudioFocus, value)` und ruft
  `services.prefs.persist(effect.pref, effect.value)` in `runEffect`.
  **Effort: S.**
- **Chunk 3.2 (A-3 Part 2)** — Live-Hook für laufende Aufnahme:
  AudioModule observer beobachtet `state.audio.audioFocusEnabledPref`
  Übergang **während** `state.recording.Active`, emittiert
  `Effect.ApplyAudioFocusRuntime(enabled)`, der via
  `services.audioFocus.applyRuntime(...)` den Live-AudioFocusGate
  toggled. Ersetzt `recordingStateController.setAudioFocusRuntime`.
  **Effort: M.**
- **Chunk 3.3 (A-3 Part 3)** — EditBarController bekommt einen
  reactiven `attach(stateFlow)`-Pfad für den Audio-Focus-Twin (die
  edit_audio_focus_btn), die heute nur via expliziten
  `refreshAudioFocusIcon(newValue)`-Aufruf gerendert wird. Damit kann
  AudioFocusToggle-Click → dispatch → state emit → EditBarController
  re-rendert die Twin reactively (analog zur Catalog Main-Twin).
  **Effort: M.**
- **Chunk 3.4 (A-3 Final)** — `onAudioFocusToggled` 4-stufigen
  imperativen Pfad ersetzen durch
  `pipelineBinder.dispatch(Action.AudioAction.ToggleAudioFocusPref.INSTANCE);`
  Plus `vibrate()` (legitime IME-side affordance, kein State). **Effort: S.**
- **Chunk 3.5 (C-3)** — `audioFocusListener`-SP-Listener entfernen
  (er war die externe Settings-Activity-Brücke; PipelinePrefMirror
  übernimmt jetzt komplett). Die `setAudioFocusRuntime` + Edit-Bar-Twin-
  refresh werden bereits in Chunk 3.2/3.3 von der AudioModule-Observer-
  Cascade gefeuert. **Effort: S.**
- **Chunk 3.6 (C-1)** — `audioFocusRequest.setOnAudioFocusChangeListener`
  ruft heute direkt `recordingStateController.togglePause()`. Stattdessen
  `dispatch(Action.AudioAction.OnAudioFocusGrantChanged(granted=false))`,
  AudioModule cascadet → `Action.RecordingAction.PauseRecording`. Wenn
  die Cascade-Mechanik schon existiert (KDoc :17-26), nur Click-Site
  ändern; sonst Cascade ergänzen. **Effort: S–M.**

**AC für Block 3:** `recordingStateController.setAudioFocusRuntime` ist
toter Code (kein Caller mehr im Bound-Pfad). AC-7 erfüllt (kein
audioFocusListener mehr). AC-8 erfüllt.

### Block 4 — Restliche View-Mutation- und Cleanup-Sites

**Ziel:** Die verbleibenden 🟡 abräumen.

- **Chunk 4.1 (B-1 + C-7)** — Eigener `OverlayPermissionInfobarRenderer`
  (analog `OverlayPermissionGate`). Observer wird auf den Renderer
  gerichtet. **Effort: M.**
- **Chunk 4.2 (B-3 + B-4)** — Container-Theme-Apply in
  `ImeViewBackend.applyTheme` integrieren. Die 3 Container und die 2
  TextViews zu der Theme-Apply-Phase hinzufügen. **Effort: S.**
- **Chunk 4.3 (B-5)** — Enter-Icon als Catalog-`foregroundResolver`.
  KeyboardInputModule erweitert mit `state.keyboardInput.enterIcon:
  EnterIconKind` (Enum: ENTER / DONE / SEND / SEARCH). Action
  `KeyboardInputAction.SetEnterIconKind(kind)` (dispatch von
  `onStartInputView` / `EditorInfo`-Refresh) statt direkter
  `updateEnterButtonIcon`. Catalog ENTER-Slots bekommen
  `iconResolver = ::resolveEnterIcon`. **Effort: M.**
- **Chunk 4.4 (A-4 + A-5 / D-2 + D-3)** — LastFileName / TranscriptionAudioFile
  Pref-Persistenz in RecordingModule.Effect.PersistLastFileName + neue
  Action `RecordingAction.OnAudioFileImported(file)` für den Import-
  Pfad. Click-Handler ruft nur noch dispatch. **Effort: M.**
- **Chunk 4.5 (A-6)** — `setLanguageFromPicker` migrieren zu
  `LanguageAction.SetEffectiveLanguage(code)` mit LanguageModule-Effect.
  Curated-List-Persistenz wandert ins Modul (Logik aus
  LanguageResolver.persistInputLanguagesAndPos). **Effort: L.**
- **Chunk 4.6 (C-4)** — Nach 4.4: `recordingStateController.setCallback`
  hat keine Live-Caller mehr → Callback-Block entfernen,
  RecordingStateController.setCallback-Implementation entfernen.
  **Effort: S.**

**AC für Block 4:** AC-6 erfüllt (Grep liefert nur die zwei pre-bind-
fallback-Sites mit explizitem Tag). AC-1 vollständig erfüllt (kein
gespiegelter Pref-Write mehr im IME-Service).

## 5. Per-chunk implementation pattern — worked example (A-1 / SmallMode)

Das ist die Vorlage, an der sich jeder andere Migrations-Chunk
orientiert.

### Before

`app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:5031-5052`:

```java
@Override
public void onSmallModeToggled() {
    boolean currentSmall = pipelineBinder != null
            ? pipelineBinder.getState().getValue().getLayout().getSmallMode()
            : DictatePrefsKt.get(sp, Pref.SmallMode.INSTANCE);
    boolean newSmallMode = !currentSmall;
    DictatePrefsKt.put(sp.edit(), Pref.SmallMode.INSTANCE, newSmallMode).apply();
    if (editNumbersAnimator != null) {
        editNumbersAnimator.animateSmallModeToggle(true);
    }
}
```

### After (Click-Handler)

```java
@Override
public void onSmallModeToggled() {
    if (pipelineBinder == null) return;  // AC-5 pre-bind no-op
    pipelineBinder.dispatch(
            net.devemperor.dictate.state.Action.LayoutAction.ToggleSmallMode.INSTANCE);
    if (editNumbersAnimator != null) {
        editNumbersAnimator.animateSmallModeToggle(true);  // IME-side affordance, post-dispatch
    }
}
```

### After (LayoutModule.kt — Reducer + Effect)

```kotlin
object LayoutModule : DictateModule<LayoutState, Action.LayoutAction, LayoutModule.Effect> {

    sealed interface Effect : SideEffect {
        data class PersistSmallMode(val enabled: Boolean) : Effect
        data class PersistSingleRowMode(val enabled: Boolean) : Effect
        // …
    }

    override fun reduce(
        state: LayoutState,
        action: Action.LayoutAction,
        ctx: ReducerContext,
    ): TransitionResult<LayoutState, Effect>? = when (action) {

        Action.LayoutAction.ToggleSmallMode -> {
            val nextSmall = !state.smallMode
            val nextState = if (nextSmall) {
                state.copy(smallMode = true, contentArea = ContentArea.MAIN_BUTTONS)
            } else {
                state.copy(smallMode = false)
            }
            TransitionResult(
                nextState = nextState,
                sideEffects = listOf(Effect.PersistSmallMode(nextState.smallMode)),
            )
        }
        // …
    }

    override fun runEffect(effect: Effect, services: ModuleServices) {
        when (effect) {
            is Effect.PersistSmallMode -> services.prefs.persist(Pref.SmallMode, effect.enabled)
            is Effect.PersistSingleRowMode -> services.prefs.persist(Pref.SingleRowMode, effect.enabled)
        }
    }
}
```

### Test (LayoutModuleTest.kt)

```kotlin
@Test fun `ToggleSmallMode emits PersistSmallMode effect with new value`() {
    val state = LayoutState(smallMode = false, contentArea = ContentArea.QWERTZ)
    val result = LayoutModule.reduce(state, Action.LayoutAction.ToggleSmallMode, ctx)!!

    // Atomic clamp: enabling small-mode also clamps to MAIN_BUTTONS
    assertEquals(LayoutState(smallMode = true, contentArea = ContentArea.MAIN_BUTTONS),
                 result.nextState)
    assertEquals(listOf(LayoutModule.Effect.PersistSmallMode(true)),
                 result.sideEffects)
}

@Test fun `Effect-write feeds Mirror is idempotent (no re-emit loop)`() {
    val store = DictateUiStateStore(DictateUiState.initial())
    val mirror = PipelinePrefMirror(sp)
    mirror.attach(store)

    // Pretend a PersistSmallMode effect just wrote SP. The listener will
    // call applyChange. Because state.layout.smallMode already equals
    // sp.get(Pref.SmallMode), the resulting `current.copy(...)` is
    // structurally identical and StateFlow.emit is a no-op (no
    // collector wake-up).
    val before = store.state.value
    DictatePrefsKt.put(sp.edit(), Pref.SmallMode, true).commit()
    // Listener fires synchronously on commit().
    assertSame(before.layout, store.state.value.layout)  // same reference, no emit
}
```

## 6. Risks / Open Questions

### 6.1 Risks

- **R-1: Mirror-Effect-Loop.** Wenn `Effect.PersistPref` SP schreibt,
  feuert der PipelinePrefMirror-Listener, der `store.update {
  applyChange(it, key) }` aufruft — der Diff produziert `current.copy(...)`
  mit identischem Inhalt. **Mitigation:** Datenklassen-Gleichheit in
  `copy(...)` führt zu StateFlow-emit-Suppression (MutableStateFlow
  emittiert nur bei Wert-Änderung). Explizit als Test in Chunk 1.3
  abgesichert.
- **R-2: SP-Listener-Thread.** PipelinePrefMirror-Listener feuert auf
  dem Thread, der `commit()`/`apply()` aufgerufen hat. Effects laufen
  im DictateOrchestrator.scope (typischerweise Main-Thread für
  IME-bezogene Module). `apply()` ist async → Listener feuert ggf. auf
  einem Worker. **Mitigation:** `DictateUiStateStore.update` ist
  thread-safe (CAS-Loop laut KDoc :48-53). Bereits abgedeckt.
- **R-3: Pre-Bind-Verlust.** Heute schreibt der Click-Handler SP auch
  ohne Binder; bei Re-Bind kommt der Wert via initialMirror in den
  State. Nach Migration: `if (pipelineBinder == null) return;` → der
  Click hat keinen Effekt vor Bind. **Mitigation:** Pre-Bind ist ein
  sub-100ms-Fenster (Service-Bind ist near-instant nach
  onCreateInputView); UX-Impact zu vernachlässigen. AC-5 dokumentiert
  diese Entscheidung. **Toast für Critical-Cases** (z.B. wenn der
  User sich aufgrund einer fehlenden Bind-Verbindung wundert) — siehe
  startRecording-Toast :3325-3329 als Vorbild.
- **R-4: Effect-Ordnung vs. Animation.** `editNumbersAnimator.animateSmallModeToggle`
  läuft heute *nach* dem SP-Write; nach Migration läuft es *nach*
  dispatch. Dispatch ist synchron (Spec 1 §4.0.1) — die Reducer-Effekte
  inkl. PersistPref laufen *vor* dem Animator-Call. Identische Reihenfolge,
  kein Verhaltens-Drift. Test via Chunk-2.1 Manual-Verify.
- **R-5: Settings-Activity-Compat.** External Settings-Activity-Writes
  (z.B. `DictateSettingsActivity` toggled `Pref.AudioFocus`) gehen
  weiterhin durch PipelinePrefMirror SP→State. Nichts ändert sich.
  Aber: Wenn die Settings-Activity einen Live-Hook für laufende
  Aufnahme erwartet (heute via `audioFocusListener` :1072-1097), muss
  AudioModule.onCrossModuleStateChange dem Live-Hook-Verhalten
  entsprechen (Chunk 3.2). **Mitigation:** Test mit aktiver Aufnahme
  beim Settings-Toggle ergänzen.

### 6.2 Open Questions

> **Status 2026-05-21:** Alle drei Open Questions wurden vom User in
> der Plan-Review-Session vom selben Tag **entschieden**. Die
> Entscheidungen sind unten verbatim eingetragen und in die
> Block-Struktur (§4) gespiegelt, wo nötig.

- **OQ-1 (InputLanguages Mirror-Aufnahme) — ENTSCHIEDEN: Option A.**
  `Pref.InputLanguages` + `Pref.InputLanguagePos` werden in
  `PipelinePrefMirror.applyChange` als **Computed-Mirror-Pattern**
  aufgenommen: der Mirror ruft `LanguageResolver.effectiveLanguage(sp)`
  (= `langs[pos]`) und schreibt das Ergebnis in
  `state.language.effective` — analog zu den 19 existierenden
  Skalar-Mirrors, mit dem einzigen Unterschied, dass zwischen SP und
  State eine Berechnung steht. Damit fällt der
  `inputLanguagesListener`-Custom-Listener (`DictateInputMethodService.java:1053-1068`)
  ersatzlos weg (AC-7 erfüllt). Die SP-Schreibrichtung wandert in
  Chunk 4.5 (A-6, `setLanguageFromPicker` → `LanguageAction.SetEffectiveLanguage`
  + `LanguageModule.Effect.PersistInputLanguages`). **Konsequenz für
  den Implementer:** Chunk 4.5 wird um einen vorgelagerten
  Mirror-Aufnahme-Step erweitert; siehe Anhang OQ-1-Plan-Edit unten.

- **OQ-2 (PromptQueueManager-Migration) — ENTSCHIEDEN: Out of Scope,
  Follow-up.** `PromptQueueManager.kt` ist heute eine stateful Klasse
  außerhalb des Orchestrator-States, mit eigener Callback-API
  (`PromptQueueCallback`), eigener `synchronized`-Threading-Semantik,
  eigener SP-Persistenz und einem in `PromptsKeyboardAdapter.AdapterCallback`
  5-Modi-Branching (siehe C-10 im Inventar). Eine Migration in ein
  Orchestrator-`PromptQueueModule` ist eine **Architektur-Erweiterung**
  (neues Modul + neue Action-Hierarchie), nicht eine
  Indirektion-Reduktion. **Folge-Plan:** `dictate-prompt-queue-state-module`,
  als Vol4-Material vorgemerkt. Begründung: dieser Plan ist auf
  "bestehende Indirektionen abräumen" gescoped — sonst wird er ein
  neuer Mutter-Plan.

- **OQ-3 (RecordingStateController-Retire) — ENTSCHIEDEN: Folge-Plan
  + Block 5-Skizze.** Nach Abschluss von A-3 / C-1 / C-4 ist der
  `RecordingStateController` (358 Zeilen, heute 23 Callsites im IME)
  nahezu toter Code. Was verbleibt: ein dünner **Hardware-Adapter** für
  Bluetooth-SCO-Sequenz, MediaRecorder-Setup, Pause-Timeout-Scheduler,
  und der RESUME-Carve-out (`startResumeJob :4660-4686` verlässt sich
  auf `JobExecutor.INSTANCE.start` direkt — kein State-Modell vorhanden).
  Erwartete Endgröße: 50-100 Zeilen `RecordingHardwareAdapter` ohne
  eigene State-Replication. **Folge-Plan:** `dictate-recording-state-controller-retire`,
  Vol4-Material. Eine Skizze von **Block 5** (Final-Retire) ist als
  Anhang am Ende dieses Plans hinterlegt, damit der Folge-Plan-Autor
  nicht von vorne planen muss.

#### OQ-1-Plan-Edit: Erweiterung von Chunk 4.5

(Vom User entschieden 2026-05-21, hier explizit für den Implementer
ausformuliert.)

Chunk 4.5 (heute: nur SP-Schreibung via Effect.PersistInputLanguages)
wird in zwei Sub-Chunks aufgeteilt:

- **Chunk 4.5a — Mirror-Aufnahme.** `PipelinePrefMirror.applyChange`
  erweitern um:
  ```kotlin
  Pref.InputLanguages.key,
  Pref.InputLanguagePos.key -> {
      val effective = LanguageResolver.effectiveLanguage(sp)
      current.copy(language = current.language.copy(effective = effective))
  }
  ```
  `PipelinePrefMirrorTest.kt` ergänzt einen Test-Case
  "Pref.InputLanguagePos change updates LanguageState.effective via
  computed mirror". **Effort: S.**
- **Chunk 4.5b — Custom-Listener-Removal.**
  `inputLanguagesListener` (`DictateInputMethodService.java:1053-1068`)
  und alle drei Register-/Unregister-Sites (`:1068`, `:1933-1935`,
  `:2009-2011`) entfernen. `pushPermanentLanguageToOrchestrator()`
  bleibt für den `onStartInputView`-Boot-Pfad bestehen (boot-time
  cold-start ist nicht durch den Mirror abgedeckt — Mirror reagiert
  nur auf SP-Änderungen, nicht auf Boot-Lesung). **Effort: S.**
- **Chunk 4.5c — Schreibrichtung (ursprüngliches A-6).**
  `setLanguageFromPicker(code)` → `pipelineBinder.dispatch(
  Action.LanguageAction.SetEffectiveLanguage(code))`. LanguageModule
  bekommt einen Reducer-Arm `SetEffectiveLanguage` + zwei Effects:
  `Effect.PersistInputLanguages(curated, pos)`. Die curated-list-Logik
  aus `LanguageResolver.persistInputLanguagesAndPos` wandert in den
  Module-Effect. **Effort: L** (kept from original A-6).

#### Block 5-Skizze (Vol4-Folge-Plan-Stub)

(Vom User entschieden 2026-05-21, **nicht** Teil dieses Plans.
Hinterlegt damit der Folge-Plan-Autor einen Startpunkt hat.)

- **Ziel:** `RecordingStateController` von 358 Zeilen auf ein dünner
  `RecordingHardwareAdapter` (~50-100 Zeilen) zurückbauen, der nur
  noch I/O-Operationen kapselt (Bluetooth-SCO start/stop,
  MediaRecorder setup/release, Pause-Timeout-Scheduler).
- **Voraussetzungen:** A-3 + C-1 + C-4 + A-4 + A-5 dieses Plans
  abgeschlossen. Nach diesem Punkt: keine State-Spiegelung mehr im
  Controller, keine Callback-Block-Definition mehr, keine
  `setAudioFocusRuntime`-Pfade.
- **Verbleibende Callsites (Schätzung):** `setManagers()`,
  `onKeyboardShown/Hidden()`, `cancelRecording()`, `onDestroy()`,
  `startResumeJob`-Hook. Alle 5 sind reine Hardware-Adapter-Aufrufe.
- **Risiko:** Der RESUME-Carve-out (`startResumeJob :4660-4686`) ruft
  direkt `JobExecutor.INSTANCE.start` ohne State-Modell. Das Folge-Plan
  muss entscheiden, ob das in ein Action-Dispatch wandert
  (`Action.RecordingAction.StartResumeJob`) oder als legitime
  Hardware-Operation außerhalb des State-Modells bestehen bleibt.
- **AC:** `wc -l RecordingStateController.kt` ≤ 100. Keine Callback-API
  mehr, keine FSM-Logik mehr.

## 7. References

- **Vorgänger-Plan:**
  [`2026-05-21 - dictate-render-cutover-completion-vol2`](../2026-05-21%20-%20dictate-render-cutover-completion-vol2/dictate-render-cutover-completion-vol2.md)
  — Render-Path-Seite abgeschlossen.
- **ADRs:**
  - [ADR-0001 — state-modular-orchestrator-pattern](../../decisions/0001-state-modular-orchestrator-pattern.md)
    §"F-8 Single Dispatch" — der Vertrag, den diese Plan-Migration
    auf der Input-Seite durchsetzt.
  - [ADR-0005 — UI-Triangle-FSM](../../decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md)
    §"Required mechanics" — `userPrefersWidget` Beispiel für eine
    transiente State-Achse mit explizit-kein-Pref-Mirror.
- **Hauptbeleg-Code:**
  - `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`
    (~5143 Zeilen, primäres Migrations-Target)
  - `app/src/main/java/net/devemperor/dictate/state/render/EditBarController.kt`
    (Gold-Standard-Pattern: `installDormant` + `attachToViews` +
    Callback → 1-Hop-dispatch)
  - `app/src/main/java/net/devemperor/dictate/state/PipelinePrefMirror.kt`
    (heute Single-Direction SP→State; durch diesen Plan wird die
    State→SP-Hälfte mit Module-Effects komplettiert)
  - `app/src/main/java/net/devemperor/dictate/state/modules/LayoutModule.kt`
    + `FeatureToggleModule.kt` + `AudioModule.kt` (Reducer-Arme schon
    vorhanden, Effects fehlen)
  - `app/src/main/java/net/devemperor/dictate/state/Action.kt`
    (Action-Hierarchie — alle benötigten Actions außer
    `AudioAction.ToggleVibration` existieren bereits)

## 8. Change History

### 2026-05-21 — Initial draft

- **Trigger:** User-Anfrage in der Session 2026-05-21 nach Abschluss
  von `dictate-render-cutover-completion-vol2` Phase 1–6 (Render-Path-
  Cutover komplett). Konkretes Beispiel `onSmallModeToggled` als
  kanonisches Anti-Pattern angegeben mit dem Auftrag "tiefgreifende
  Recherche, alle analogen Sites finden, vollständigen Plan
  produzieren".
- **What changed:** Plan initial verfasst. Vier-Kategorien-Inventar
  (A/B/C/D) mit 13 distinkten Fixes. 4-Block-Migrations-Sequenz.
  Worked-Example für A-1 (SmallMode).
- **Status:** Implementer-ready (alle Sites verifiziert,
  Reducer-Arme im Code geprüft, Action-Hierarchie geprüft).

### 2026-05-21 — Plan-Review und Implementation Chunk 2.1 + 2.2

- **Trigger:** User-Plan-Review-Session in der selben Pair-Programming-
  Sitzung. User hat (a) die drei Open Questions OQ-1/2/3 entschieden
  und (b) Chunk 2.1 + 2.2 (A-1 SmallMode + A-2 SingleRowMode) direkt
  in der Session implementieren lassen, um den Roundtrip-Antipattern-
  Pfad als Lighthouse zu schließen.
- **What changed:**
  - §4 Block 2 — Status-Marker eingefügt: Chunk 2.1 + 2.2 sind
    **DONE** (modul-lokale Effect-Variante in `LayoutModule`, nicht
    die generische `Effect.PersistPref<T>` aus Block 1). Block 1 wird
    in Block 3 nachgezogen, wenn die generische Form für die
    AudioModule-Effects ihren Wartungs-Hebel bekommt.
  - §6.2 OQ-1 — von "Entscheidung erforderlich vor Block 4" auf
    "Option A entschieden". Neue Unter-Sektion "OQ-1-Plan-Edit" mit
    der Aufteilung von Chunk 4.5 in 4.5a (Mirror-Aufnahme),
    4.5b (Custom-Listener-Removal), 4.5c (Schreibrichtung).
  - §6.2 OQ-2 — Begründung verbreitert, "Vol4-Material" als expliziter
    Tag. Inhalt unverändert.
  - §6.2 OQ-3 — Block 5-Skizze als Anhang hinterlegt (Endgröße,
    verbleibende Callsites, RESUME-Carve-out-Risiko, AC).
- **Status:** Implementer-ready, nun mit eingetragenen
  User-Entscheidungen. Chunk 2.1 + 2.2 abgeschlossen; alle anderen
  Chunks bleiben offen.
