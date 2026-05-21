---
status: Skeleton
archive_target: 2026-XX-XX - dictate-keyboard-input-state-elaboration
---

# Plan — dictate-keyboard-input-state-elaboration

> **Status:** Skeleton-Stub. Plan-author muss §1 vertiefen, §2 prüfen,
> Implementation-Chunks ergänzen, Open Questions klären. Hier landen
> nur Ziel + Acceptance Criteria — destilliert aus dem postponed Chunk
> 4.3 (B-5) des `dictate-indirection-cleanup` Plans (siehe state.md D-2).

## 1. Ziel

`KeyboardInputModule` von `Unit`-state auf einen vollständigen
`KeyboardInputState` mit `enterIcon: EnterIconKind`-Achse erweitern,
damit der ENTER-Button-Icon-Render reaktiv aus dem State-Flow fließt
statt durch den imperativen `updateEnterButtonIcon`-Pfad in
`DictateInputMethodService.onStartInputView`.

**Folge-Plan-Begründung (aus `dictate-indirection-cleanup` D-2):**

> Der Refactor-Scope von Chunk 4.3 wurde unter "M" Effort geplant
> (30 min – 2 h), erfordert tatsächlich aber:
> - Echten State-Wechsel `Unit → KeyboardInputState` für das Modul
>   (ändert Lens, `DictateUiState`, Registry-Coverage-Annahmen, alle
>   Modul-Tests).
> - Anpassung von 5 Catalog-ENTER-Slots (LayoutCatalog
>   `:151/:208/:328/:383/:481`) über 4 LayoutModes mit
>   `foregroundResolver = (state, ctx) → Drawable?`.
> - Neuer Mapper `EditorInfo.imeOptions → EnterIconKind` (heute
>   imperativ in `updateEnterButtonIcon`).
> - Pre-Dispatch-Resolution-Hook in `onStartInputView`:
>   `dispatch(KeyboardInputAction.SetEnterIconKind(kind))`.
> - JVM-only-Tests für Reducer, Mapper, und integrationsweise gegen
>   Catalog.
>
> Realistischer Aufwand: 2–4 h. 🟡-Severity (Architektur-Schuld, kein
> Bug); One-Shot-Pfad pro `onStartInputView` — kein Click-Roundtrip-
> Antipattern, kein Pre-Bind-Verlust-Risiko. Postponed in diesen
> Folge-Plan, weil Vol4-Material gemeinsam mit Block 5
> RecordingStateController-Retire und OQ-2 PromptQueue-Migration besser
> in einem zusammenhängenden Plan adressiert wird.

## 2. Acceptance Criteria

- **AC-1:** `KeyboardInputModule.state` ist `KeyboardInputState` (data
  class) mit mindestens dem Feld `enterIcon: EnterIconKind`.
- **AC-2:** `EnterIconKind` ist ein Kotlin `enum class` mit den vier
  Mappings: `ENTER` (Default), `DONE`, `SEND`, `SEARCH` — analog dem
  heutigen `updateEnterButtonIcon`-Switch über `EditorInfo.imeOptions`.
- **AC-3:** Action `KeyboardInputAction.SetEnterIconKind(kind: EnterIconKind)`
  + Reducer-Arm im `KeyboardInputModule.reduce` — flippt
  `state.keyboardInput.enterIcon`, keine Effects nötig.
- **AC-4:** `LayoutCatalog` ENTER-Slots (5 Stück) bekommen einen
  `foregroundResolver: (DictateUiState, RenderContext) → Drawable?` der
  `state.keyboardInput.enterIcon` zu einem `R.drawable.*`-Ressource
  mappt. Heutiger imperativer `enterButton.setForeground(...)` in
  `DictateInputMethodService.updateEnterButtonIcon` entfällt.
- **AC-5:** `DictateInputMethodService.onStartInputView` dispatcht
  `SetEnterIconKind(...)` als Pre-Dispatch-Resolution vor dem
  Catalog-Render-Pass.
- **AC-6:** AC-6 des Vorgänger-Plans `dictate-indirection-cleanup`
  (View-Mutation-Owner-Whitelist) ist jetzt **vollständig** erfüllt —
  der verbleibende `setForeground`-Treffer in `updateEnterButtonIcon`
  ist eliminiert.
- **AC-7:** Pre-Bind-Verhalten: `pipelineBinder == null` während
  `onStartInputView` → kein Dispatch, kein Crash. State bleibt auf
  Boot-Default; nächster Bind-Pass aktualisiert das Icon (analog zur
  D-13/R-3 boot-before-bind-closure für Language).
- **AC-8:** JVM-Tests:
  - `KeyboardInputModuleTest` — Reducer-Arm + State-Initial.
  - `EnterIconKindMapperTest` — `EditorInfo.imeOptions →
    EnterIconKind` Tabelle.
  - `LayoutCatalogEnterResolverTest` — alle 5 Slots produzieren das
    korrekte `R.drawable.*`-Set für jeden `EnterIconKind`.

## 3. Sonstiges (TBD durch Plan-Author)

- Open Question: Ob `EnterIconKind` über `KeyboardInputState` oder einer
  separaten `ImeOptionsState`-Achse residiert (anstehend in
  Vol4-Material).
- Open Question: Ob der Pre-Dispatch-Resolution-Hook in eine separate
  Action `KeyboardInputAction.OnImeOptionsResolved(imeOptions: Int)`
  abstrahiert werden soll, damit der Mapper im Modul lebt (SoT) statt
  im IME.
- Cross-Reference: `dictate-indirection-cleanup` Chunk 4.3 (B-5) +
  state.md D-2 + `dictate-render-cutover-completion-vol2` Catalog-
  Architecture.

## References

- Vorgänger-Plan: [`../2026-05-21 - dictate-indirection-cleanup/dictate-indirection-cleanup.md`](../2026-05-21%20-%20dictate-indirection-cleanup/dictate-indirection-cleanup.md) §4 Chunk 4.3 (B-5)
- state.md D-2 Postponement-Begründung
- LayoutCatalog ENTER-Slots: `app/src/main/java/net/devemperor/dictate/.../LayoutCatalog.kt` `:151/:208/:328/:383/:481`
- Heutiger imperativer Pfad:
  `DictateInputMethodService.java:3160-3184` (`updateEnterButtonIcon`)
  + `:2731` (`onStartInputView` call-site)

## Change History

### 2026-05-21 — Skeleton stub created

- **Trigger:** Review-fix G1 vom Indirection-Cleanup-Plan-Review. Chunk
  4.3 wurde während der Implementation als POSTPONED markiert (D-2 in
  state.md), aber der Plan-Body referenzierte den Folge-Plan ohne dass
  er existierte — ein zukünftiger Reviewer ohne Zugriff auf state.md
  hätte das als Lücke flagged.
- **What changed:** Skeleton-File mit §1 Ziel + §2 Acceptance Criteria
  + §3 TBD-Hinweise. Detail-Architektur, Implementation-Chunks und
  Open-Question-Resolution bleiben dem Plan-Author überlassen.
- **Status:** Skeleton (kein Implementer-ready).
