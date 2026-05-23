---
name: dictate-enter-button-host-action
status: Spec — programmer-ready
created: 2026-05-23
archive_target: 2026-05-23 - dictate-enter-button-host-action
supersedes:
  - docs/plans/2026-05-21 - dictate-keyboard-input-state-elaboration/dictate-keyboard-input-state-elaboration.md
references:
  - docs/decisions/0001-state-modular-orchestrator-pattern.md
  - docs/decisions/0004-ui-layout-catalog-motionlayout.md
  - docs/decisions/0008-ui-surface-axes-widget-state-and-ime-view.md
---

# Dictate Enter-Button — Host-Action (State-Driven Cutover)

## Problem

Der Enter-Button wechselt sein **Icon** abhängig von `EditorInfo.imeOptions` (`updateEnterButtonIcon` im Legacy-Service, `DictateInputMethodService.java:3591–3615`), aber sein **Click** ist hardcoded auf `Effect.SendEnter → commitText("\n", 1)` (`KeyboardInputModule.kt:94–96`). Folge: Icon zeigt „Senden", Click sendet Newline. In Browsern, Chat-Apps, Suchfeldern, Form-NEXT, Custom-Actions → **8 von 12 Edge-Cases sind heute Bugs**.

Zusätzlicher Drift: `commitText("\n", 1)` (Implementation) widerspricht der ursprünglichen Spec `sendKeyEvent(KEYCODE_ENTER)` (`2026-05-07 - dictate-keyboard-layout-refactor/.../dictate-keyboard-layout-refactor.reviewed.md:848`). In WebViews kommt heute oft gar nichts an (kein DOM-keydown).

Parallel-Pfad: `QwertzKeyboardController.handleEnter()` → `performEnterAction()` (live wired in `DictateInputMethodService:932`) macht es **richtig** (über den Legacy-Pfad), aber der Catalog-Pfad daneben macht es falsch. → zwei Wahrheiten.

## Acceptance Criteria

1. **AC-1 — Icon UND Action aus einer State-Quelle.** Catalog-`iconResolver` und Catalog-`actionResolver` für den ENTER-Slot lesen **denselben** State-Axis (`state.keyboardInput.hostEditor`). Drift zwischen Optik und Verhalten ist strukturell unmöglich.
2. **AC-2 — Edge-Cases korrekt behandelt.** Für jeden Eintrag in der Edge-Case-Tabelle (Teil C unten) liefert der Catalog das erwartete Verhalten. JVM-Tests decken alle Fälle ab.
3. **AC-3 — `canCommitToHost`-Invariante erhalten.** Wenn `imeViewVisible == false`, dispatcht der ENTER-Resolver **kein** Action (ADR-0008).
4. **AC-4 — Zero-Grep `performEnterAction` und `updateEnterButtonIcon`.** Nach Chunk 5: `grep -r "performEnterAction\|updateEnterButtonIcon" app/src/main/` → 0 Treffer.
5. **AC-5 — QWERTZ-Enter und Catalog-ENTER nutzen denselben Dispatch-Pfad.** Beide gehen über `dispatch(Action.KeyboardInputAction.EnterKey)`.
6. **AC-6 — Auto-Enter (`scheduleAutoEnter`) nutzt denselben Pfad.** Verhalten äquivalent zu Legacy.
7. **AC-7 — Build grün + 8-App-Device-Smoke-Test bestanden.**

## Architektur-Entscheidung

**Bestehendes `KeyboardInputModule` migriert von `Unit` auf `KeyboardInputState`.** Begründung:
- F-10/Spec 1 §15.6 dokumentiert das Modul explizit als „Unit-state". Mit einer echten Host-Editor-Achse wird es zum natürlichen Owner aller Keyboard-Input-Belange.
- Kein neues Modul nötig (Overhead-Vermeidung).
- Konsumiert das postponed Skeleton `dictate-keyboard-input-state-elaboration` (wird nach Implementation als superseded markiert).

**State-Shape:**

```kotlin
data class KeyboardInputState(val hostEditor: HostEditorState = HostEditorState())

data class HostEditorState(
    val imeActionId: Int = EditorInfo.IME_ACTION_UNSPECIFIED,
    val customActionId: Int = 0,                  // EditorInfo.actionId (0 = use imeAction)
    val customActionLabel: CharSequence? = null,  // EditorInfo.actionLabel
    val hasNoEnterAction: Boolean = false,        // IME_FLAG_NO_ENTER_ACTION
    val isMultiLine: Boolean = false,             // TYPE_TEXT_FLAG_(IME_)?MULTI_LINE
    val hasEditorInfo: Boolean = false,           // false = pre-bind / EditorInfo null
)

// Derived selector (free function, NOT stored in state):
enum class EnterButtonRole { NEWLINE, GO, SEARCH, SEND, NEXT, PREVIOUS, DONE, CUSTOM }
fun resolveEnterRole(s: HostEditorState): EnterButtonRole { ... }
```

**Neue Actions** (in `Action.KeyboardInputAction`):
- `data class HostEditorAttached(val state: HostEditorState) : KeyboardInputAction()`
- `data object HostEditorDetached : KeyboardInputAction()`

**Neuer Effect** (ersetzt `Effect.SendEnter`):
- `data class PerformEnter(val role: EnterButtonRole, val actionId: Int) : Effect`
- Effect-Handler ruft `performEditorAction(actionId)` bei SEND/SEARCH/GO/NEXT/PREVIOUS/DONE/CUSTOM, `commitText("\n", 1)` bei NEWLINE, `sendKeyEvent(KEYCODE_ENTER)` als Fallback (`hasEditorInfo == false`).

**Catalog-ENTER-Slots** (alle 5: `LayoutCatalog.kt:151, 208, 328, 383, 481`):
```kotlin
ButtonSlot(
    logicalId = LogicalButtonId.ENTER,
    widthPolicy = WidthPolicy.WrapContent,
    visibilityPredicate = { true },
    iconResolver = ::resolveEnterIcon,
    actionResolver = ::resolveEnterAction,
)
```

## Teil C — Edge-Case-Tabelle (JVM-Test-Spec)

| # | Editor / Kontext | imeOptions / Flags | Erwartete Role | Erwartete Effect |
|---|---|---|---|---|
| 1 | Browser `<input type=email>` | `IME_ACTION_GO` | GO | `performEditorAction(GO)` |
| 2 | Browser `<textarea>` multi-line | `UNSPECIFIED` + `TYPE_TEXT_FLAG_MULTI_LINE` | NEWLINE | `commitText("\n", 1)` |
| 3 | Browser `<input>` ohne Action | `UNSPECIFIED` | NEWLINE | `commitText("\n", 1)` |
| 4 | Chat (WhatsApp) | `IME_ACTION_SEND` | SEND | `performEditorAction(SEND)` |
| 5 | Suche (Maps, Play) | `IME_ACTION_SEARCH` | SEARCH | `performEditorAction(SEARCH)` |
| 6 | Notizfeld mit Flag | `IME_FLAG_NO_ENTER_ACTION` + `SEND` | NEWLINE (Flag dominiert) | `commitText("\n", 1)` |
| 7 | Custom (`actionLabel`+`actionId=42`) | `actionId=42` | CUSTOM | `performEditorAction(42)` |
| 8 | Form-NEXT | `IME_ACTION_NEXT` | NEXT | `performEditorAction(NEXT)` |
| 9 | Single-Line + DONE | `IME_ACTION_DONE` | DONE | `performEditorAction(DONE)` |
| 10 | `EditorInfo == null` (pre-bind) | n/a (`hasEditorInfo=false`) | NEWLINE-Fallback | `sendKeyEvent(KEYCODE_ENTER)` |
| 11 | Multi-line + SEND | `SEND` + `MULTI_LINE` | NEWLINE (multi-line dominiert) | `commitText("\n", 1)` |
| 12 | PREVIOUS | `IME_ACTION_PREVIOUS` | PREVIOUS | `performEditorAction(PREVIOUS)` |

## Chunk-Plan

### Chunk 1 — Foundation (additiv, Verhalten unverändert)

- `state/KeyboardInputState.kt` (neu): `data class KeyboardInputState(val hostEditor: HostEditorState)` + `HostEditorState` data-class
- `state/Action.kt`: 2 neue Actions in `KeyboardInputAction` (`HostEditorAttached`, `HostEditorDetached`)
- `state/DictateUiState.kt`: neues Feld `keyboardInput: KeyboardInputState`, KDoc-Tabelle aktualisieren (Achse #14), `initial()` ergänzen
- `state/modules/KeyboardInputModule.kt`: Modul-Type-Args `DictateModule<KeyboardInputState, ..., Effect>`, `read/write/initialState` neu, 2 neue Reducer-Arme (pure state-write, kein Effect)
- Unit-Tests (`KeyboardInputModuleTest`): HostEditorAttached schreibt durch, Detached resettet, bestehende `Backspace/EnterKey/SpaceKey/CopyToClipboard` weiterhin grün
- **Build grün, Catalog noch nicht touch, kein Verhalten geändert**

### Chunk 2 — Mapper + Resolver + Effect

- `state/HostEditorMapper.kt` (neu): pure Free-Function `HostEditorState.Companion.from(info: EditorInfo?): HostEditorState`
- `state/layout/EnterRoleResolver.kt` (neu): `EnterButtonRole`-Enum + `resolveEnterRole(HostEditorState): EnterButtonRole` + `actionIdForRole(role): Int`
- `state/layout/IconResolvers.kt`: neue Funktion `resolveEnterIcon(state): Int` (delegiert an `resolveEnterRole`)
- `state/layout/ActionResolvers.kt`: neue Funktion `resolveEnterAction(state, services): Action?` (mit `canCommitToHost`-Guard)
- `state/modules/KeyboardInputModule.kt`: 
  - `Effect.SendEnter` → `Effect.PerformEnter(role, actionId)`
  - `EnterKey`-Reducer-Arm berechnet Role + actionId aus `state.hostEditor`
  - `runEffect` neuer Arm: `performEditorAction` vs `commitText` vs `sendKeyEvent`
- Tests: `HostEditorMapperTest`, `EnterRoleResolverTest` (alle 12 Edge-Cases), Reducer-Tests für neue Effects
- **Build grün, Catalog noch nicht touch, neuer Code-Pfad ist tot**

### Chunk 3 — Catalog-Cutover

- `state/layout/LayoutCatalog.kt`: 5 ENTER-Slots auf `iconResolver = ::resolveEnterIcon` + `actionResolver = ::resolveEnterAction` umstellen
- `core/DictateInputMethodService.java`: in `onStartInputView` (Zeile ~3119) `updateEnterButtonIcon(info)` ersetzen durch `dispatch(HostEditorAttached(HostEditorState.from(info)))`. In `onFinishInputView` (existierender `OnImeViewHidden`-Dispatch) zusätzlich `HostEditorDetached` dispatchen.
- Catalog-Konsistenz-Test (`LayoutCatalogEnterSlotConsistencyTest`): alle 5 ENTER-Slots haben identische Resolver-Referenz
- **Bug ist hier behoben. Manueller Geräte-Test gegen die 8 Apps (siehe Test-Strategie).**

### Chunk 4 — QWERTZ-Migration

- `core/DictateInputMethodService.java:932`: QWERTZ-Enter-Callback umstellen von `this::performEnterAction` auf `() -> { dispatch(EnterKey); return Unit; }`
- Pre-Bind-Fallback: bei `pipelineBinder == null` direkt-call auf `performEnterAction()` (wird in Chunk 5 entfernt)
- Smoke-Test QWERTZ-Tastatur (alphabetische + Symbol-Tab beide Enter-Pfade)

### Chunk 5 — Legacy-Cleanup (zero-grep)

- `core/DictateInputMethodService.java`: löschen
  - `updateEnterButtonIcon(EditorInfo)` (`:3591–3615`) + Aufruf in `:3119`
  - `performEnterAction()` (`:3523–3565`)
  - `onEnterClicked()` (`:5534`) — laut KDoc toter Code
- `scheduleAutoEnter(String)` (`:3573–3589`) migrieren: `mainHandler.postDelayed(() -> dispatch(EnterKey), baseDelay)`. Der Reducer liest zur Dispatch-Zeit den aktuellen `hostEditor` — semantisch äquivalent zu Legacy (`getCurrentInputEditorInfo()` live-read).
- Pre-Bind-Fallback aus Chunk 4 entfernen
- `grep -r "performEnterAction\|updateEnterButtonIcon\|Effect.SendEnter" app/src/main/` → **0 Treffer**

## Test-Strategie

**JVM-Tests** (`app/src/test/java/net/devemperor/dictate/...`):
- `KeyboardInputModuleTest`: Reducer-Arme für HostEditorAttached/Detached/EnterKey × Edge-Case-Tabelle
- `HostEditorMapperTest`: EditorInfo → HostEditorState für alle Flag-Kombinationen
- `EnterRoleResolverTest`: alle 12 Edge-Cases aus Teil C
- `LayoutCatalogEnterSlotConsistencyTest`: 5 ENTER-Slots strukturell identisch
- `KeyboardInputE2ETest`: Fake-`InputConnection` → assert dass `performEditorAction(SEND)` ankommt

**Geräte-Test** (manuell, am Ende):
- Chrome: Adressleiste (GO), Such-Feld (SEARCH), Login-Form (DONE/NEXT)
- WhatsApp / Signal: Chat-Input (SEND)
- Maps: Suchleiste (SEARCH)
- Notes/Markor: multi-line (NEWLINE)
- Settings → WLAN-Passwort (DONE)
- Termux: multi-line, UNSPECIFIED

## References

- ADR-0001 — Single-Dispatch + Module-Pattern (eingehalten)
- ADR-0004 — Layout-Catalog (extending)
- ADR-0008 — `canCommitToHost: imeViewVisible` (eingehalten)
- Vorgänger-Plan: `docs/plans/2026-05-21 - dictate-keyboard-input-state-elaboration/` (supersedes)
- Layout-Refactor: `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/` (Drift-Quelle)
