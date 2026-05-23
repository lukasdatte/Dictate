# Plan — Dictate Enter-Button Host-Action

**Status:** Active

**Summary:** Closes the structural drift between Enter-Button icon and click: icon switched per `EditorInfo.imeOptions` (Legacy), but click hardcoded to `commitText("\n", 1)` (new state-driven path). Introduces a `HostEditorState` axis on `KeyboardInputModule` (migrates `S = Unit → KeyboardInputState`), so both icon and action read from the same state. Removes the parallel Legacy paths (`performEnterAction`, `updateEnterButtonIcon`, `scheduleAutoEnter` direct-call, QWERTZ callback indirection).

**Scope boundaries:**
- In: Enter-Key behavior end-to-end (Catalog + QWERTZ + Auto-Enter)
- Not in: Background-Animation des Send-Buttons (separate Iteration, gleicher Branch)
- Not in: Strukturelle Captured-`InputConnection` für Cross-App-Delay (out-of-scope)

**Comparison context:** Supersedes the skeleton `docs/plans/2026-05-21 - dictate-keyboard-input-state-elaboration/` which addressed the icon axis only (postponed from `dictate-indirection-cleanup` AC-B-5). This plan covers both axes.

**Chunks:**
1. Foundation — `KeyboardInputState` + `HostEditorState` + module migration
2. Mapper + Resolver + `Effect.PerformEnter`
3. Catalog-Cutover (5 slots) + Service-Dispatch
4. QWERTZ-Migration
5. Legacy-Cleanup (zero-grep)

**Related ADRs:** ADR-0001, ADR-0004, ADR-0008
