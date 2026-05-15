# Research: `lastResultNeedsManualPaste` Field Architecture

**Date:** 2026-05-15
**Triggered by:** Finding F-1 (`B2-CRIT-MANUAL_PASTE`) in `reports/validated-findings-B2.md`
**Block:** B2 (modular-orchestrator)
**Agent-ID:** B2-VAL-RES-1
**Topic:** `manual-paste-field-architecture`
**Status:** Research → Recommendation (Implementer-ready)

---

## §1 Findings (Audit Summary)

Two audits independently flagged the same issue:

- **AUDIT-LOGIC-B2-1 (Critical):** `Action.PipelineAction.NotifyResultNeedsManualPaste` and `Action.PipelineAction.ClearManualPasteFlag` reach the `PipelineModule` reducer (lines 292-311) but explicitly return `null`. The reducer's own KDoc admits the gap with a comment referring to an "out-of-band write via `_state.update` on PrefMirror init" — but **no such write exists anywhere in the codebase**.
- **AUDIT-PLAN-AND-API-B2-1 (Important):** Spec 1 §3 + §15.1 declares the field, but no module's lens covers it. B3 will dispatch `NotifyResultNeedsManualPaste` from the IME-service-death recovery path and the dispatch will silently resolve as `DispatchOutcome.Rejected("reducer-null")`.

**Verification commands (executed during this research):**

- `grep -rn lastResultNeedsManualPaste app/src/` — 8 matches across **production code**: only the field declaration (`DictateUiState.kt:80`, `:103`), the axis-table KDoc (`:43`), the cascading-block KDoc in `PipelineModule.kt:14`. **Zero production read/write sites outside the declaration itself.**
- Test-side: `DictateUiStateTest.kt:44, 273-285`, `PipelinePrefMirrorTest.kt:349` — all three exercise the field as a pass-through, never assert a mutation through a reducer.
- The field is **dead code today.** The IME-service-death recovery affordance (Spec 1 §11.6, R.18 — "tell the user the result is on the clipboard, paste manually") is non-functional.

**B3-relevance:** B3 will wire the legacy IME `DictateInputMethodService` paths through `DictateOrchestrator`. The recovery path (`PipelineRecovery.recover` extended to surface stale-but-completed sessions to the IME UI) needs to dispatch `NotifyResultNeedsManualPaste` and have the flag actually flip. Today's silent-no-op makes the dispatch a routing dead-letter — exactly the bug-class the modular-orchestrator pattern is designed to eliminate.

---

## §2 Constraint Inventory

### Binding ADR constraints

| ID | Constraint | Source |
|---|---|---|
| C1 | **Pure-reducer invariant.** Reducers are deterministic, no hardware/IO, no `else` over sealed actions. | ADR-0001 §"Required mechanics" #2 |
| C2 | **Lens pattern, single-axis.** Every module's `read(global): S` / `write(global, sub): DictateUiState` operates on **one** sub-state-axis `S`. Reducer signs on `S`, not on `DictateUiState`. | ADR-0001 §"Required mechanics" #3; `DictateModule.kt:63-104` |
| C3 | **Mode 3 (Atomic Cross-Axis-Update) is forbidden in Phase 1.** A reducer writing more than one sub-state axis is the **multi-owner-per-axis anti-pattern** the refactor exists to kill. | ADR-0002 §"The forbidden third mode"; ADR-0001 §"Alternatives Considered" #5 |
| C4 | **Mode 1 + Mode 2 only.** Cross-module coordination is either an own-module SideEffect (Mode 1) or an Action cascade via `onCrossModuleStateChange` (Mode 2). | ADR-0002 §"The two allowed modes" |
| C5 | **Action coverage is enforced at boot.** `DictateModuleRegistry.assertCompleteCoverage()` (`DictateModuleRegistry.kt:92-111`) throws if any direct `Action` sealed-subclass is unclaimed by a module. → Every Action must have an owner module. | `DictateModuleRegistry.kt:92-111`; ADR-0001 §"Module inventory" |
| C6 | **Sealed-interface DictateModule.** Three type parameters `<S, A : Action, E : SideEffect>`. Subclasses must live in the same package (`net.devemperor.dictate.state`). | `DictateModule.kt:64`; comment at `PipelineModule.kt:1-6` |

### Kotlin language constraints

| ID | Constraint | Implication |
|---|---|---|
| K1 | **Sealed-interface members cannot carry shared fields.** Each subclass declares its own fields. Adding a `lastResultNeedsManualPaste` field to `PipelineUiState` requires adding it to a specific variant (`Idle` / `Preparing` / `Running` / `ReprocessStaging`) or every variant. | Constrains Option A |
| K2 | **`DictateModule<S, A, E>` has exactly one `S`.** Making `S` a tuple/pair changes every module-signature consumer. | Constrains Option B |
| K3 | **`object`-singleton per module** — modules are stateless. State is held by the lens read of `DictateUiState`. | Constrains all options |

### Plan-spec constraints

| ID | Constraint | Source |
|---|---|---|
| S1 | **The flag is meaningful when pipeline is back to `Idle`.** After IME-service-death recovery, the pipeline ends, the user must still see "paste from clipboard" until they perform the paste action. So the flag persists across the `pipeline = Idle` boundary. | Spec 1 §11.6, lines 5400-5460; field KDoc at `DictateUiState.kt:73-79` |
| S2 | **Issue 2.1.9 Option C** — "Clipboard + persistenter pending-Marker". The flag pairs with a `pendingSessions` entry of `status = COMPLETED` whose text the user still needs to paste. So the flag is semantically a UI-hint **adjacent to** `pendingSessions`, not part of the pipeline-FSM. | Spec 1 §3 (`DictateUiState.kt`-snippet at line 133-136); `plan-review/research-findings.md:759` |
| S3 | **No other module reads the flag.** Only the IME-side UI consumer (keyboard header, B3) will read it. | grep verification + Spec 1 §15.1 axes-table (no observer-coupling row) |

### Discovered during research (additional)

| ID | Constraint | Where |
|---|---|---|
| D1 | **PipelineUiState has no `Done` state.** Done-paths collapse directly to `Idle` (`PipelineModule.reduce` arms `PipelineDone` / `PipelineFailed` / `CancelPipeline` / `PersistenceError` all return `nextState = Idle`). The §15.x KDoc cascade-rule "`prev != Done && next is Done`" is implemented as "`prev != Idle && next is Idle`" (see `PipelineModule.kt:355-400`). | This **rules out** adding the flag to a `PipelineUiState.Done` variant — there is none. |
| D2 | **ResendState already exists as a "post-pipeline UI affordance" axis.** `ResendState` holds `lastAudioExists` — set by a `MarkLastAudio` cascade on PipelineDone (`PipelineModule.kt:380`). Conceptually, "the user must paste the result" and "the last audio exists for re-send" are siblings: both are post-pipeline UI hints that survive the pipeline returning to Idle. | `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt:288-301` + `PipelineModule.kt:376-381` |
| D3 | **`PendingSessionsModule`'s sub-state-axis is a raw `PersistentList<PendingSession>`** — no wrapper class. Adding a sibling boolean field to PendingSessionsModule would require introducing a wrapper `PendingSessionsState(list, needsManualPaste)`, which is a non-trivial axis shape change with cascading test impact. | `PendingSessionsModule.kt:45` (`DictateModule<PersistentList<PendingSession>, ...>`) |
| D4 | **The Coupling-Matrix (§15.1.x) already documents PipelineModule → Resend as `R(state.resend) C(ResendAction.MarkLastAudio)`.** Reusing the same cascade for a parallel `Resend → Resend.NotifyManualPaste` is structurally identical. | Spec 1 §15.1.x, line 6251 |
| D5 | **No existing module uses `services.emitAction()` in `runEffect` to redirect an action to another module's reducer for state-only mutation.** This pattern would be a precedent if introduced. Current `runEffect` bodies do hardware/IO/DB-launch only; redirection-as-state-write would be a novel pattern needing ADR-level acceptance. | survey of `runEffect` bodies in all 14 modules |

---

## §3 Options Evaluated

### Option A — Relocate into `PipelineUiState` sub-state

**Mechanism:** Move the flag into a specific variant of the sealed `PipelineUiState` (e.g. add a `data class Done(val needsManualPaste: Boolean) : PipelineUiState` variant; or fold it into `Idle` as `data class Idle(val needsManualPaste: Boolean = false)`).

**Pros:**
- Same-axis write. PipelineModule's existing lens covers the field. No registry / interface change.
- Aligns with the suggested-fix language in `audit-logic-B2.md:48` ("fold ... into PipelineUiState").

**Cons (decisive):**
- **D1 — there is no `Done` state.** Adding one means rewriting every Done-path reducer arm and every cross-module observer (`prev != Idle → next is Idle`-pattern in `PipelineModule.kt:370-388`). The transition surface that would change: ~15-20 reducer arms + the §15.1.x matrix.
- **K1 — sealed-interface field semantics.** Folding the flag into `Idle` as `data class Idle(val needsManualPaste: Boolean = false)` turns `Idle` from a `data object` into a `data class`. Every `is PipelineUiState.Idle` check now matches multiple instances; equality semantics shift. ~30 production test assertions of the form `assertEquals(PipelineUiState.Idle, ...)` would need to become `assertTrue(it is PipelineUiState.Idle)`.
- **Semantic mismatch.** PipelineUiState is the pipeline-FSM. The manual-paste flag is **post**-FSM UI affordance — the pipeline is *done* (back to Idle) and the user still needs a UI hint. Putting a UI-affordance flag inside the FSM blurs the FSM's purpose.
- **Forward-compat hostility for B3.** B3's recovery path sets the flag AFTER pipeline has settled to Idle and the result is in the DB + clipboard. Encoding "Idle but with pending paste" inside the FSM forces the FSM to model a kind of post-Done state that the rest of the architecture doesn't have.

**Verdict:** Architecturally wrong. The flag is **not** pipeline-FSM state.

---

### Option B — Extend `PipelineModule.lens` to a tuple `<PipelineUiState, Boolean>`

**Mechanism:** Change PipelineModule's type parameter `S` from `PipelineUiState` to `Pair<PipelineUiState, Boolean>` (or a wrapping `data class`). The lens reads/writes both fields atomically.

**Pros:**
- Avoids cross-axis-write at the reducer's signature level — `S` carries both fields, so the reducer signs on `(S, A, ctx) → TransitionResult<S, E>?` and naturally writes both.
- No new module file.

**Cons (decisive):**
- **C2 violation in spirit.** ADR-0001 §"Required mechanics" #3 ("Lens Pattern") is built around the principle that `read(global): S` / `write(global, sub): DictateUiState` covers **one** sub-state-axis. Bundling two semantically distinct fields into a tuple under the lens is the same multi-axis-per-owner anti-pattern dressed up at the type level. The reducer would still be writing two unrelated fields ("pipeline FSM" + "post-pipeline UI hint") — the type system would just hide the multi-axis mutation from `state.copy()`.
- **C6 inflation.** Changes `DictateModule<S, A, E>`'s usage convention. Every doc that says "one axis per module" needs a footnote.
- **Tooling friction.** Every consumer of `PipelineModule.read(global)` now sees a `Pair` (or a synthetic wrapper) instead of `PipelineUiState`. Two existing test sites (`DictateOrchestratorInitOrderTest.kt`, `PipelineModuleTest.kt`) sample the read; both would need updating to the wrapper shape.
- **Sets precedent.** Any future module that wants two unrelated fields would point at this and ask "why not me?". The ADR-0001 single-axis discipline degrades over time.

**Verdict:** Type-level workaround for an architectural mismatch. Rejected.

---

### Option C — Introduce a dedicated `ManualPasteModule`

**Mechanism:** New module owning a new sub-state-axis `ManualPasteState(needsAttention: Boolean)`. Owns `Action.ManualPasteAction.NotifyNeeded` and `Action.ManualPasteAction.Clear`. PipelineModule's `onCrossModuleStateChange` cascades `Action.ManualPasteAction.NotifyNeeded` on the death-recovery branch (or a different module dispatches it directly from a recovery path — B3 wiring).

**Pros:**
- Strict ADR-0001 + ADR-0002 conformance. Mode-2 cascade only.
- SRP-clean. The module owns exactly one concept.

**Cons:**
- **High cost for a single Boolean.** New `*Module.kt` file + new `ManualPasteState` data class + new `Action.ManualPasteAction` sealed class + new `ModuleId.ManualPaste` + new registry entry + new tests. Registry grows from 14 → 15.
- **`Action.PipelineAction.NotifyResultNeedsManualPaste` becomes orphaned.** That action is `PipelineAction` today. To make it a `ManualPasteAction` we'd have to move it across the sealed-action hierarchy + update every existing dispatcher (a few B3-side stubs already reference it conceptually). The C5 invariant (`assertCompleteCoverage`) forces either (a) moving the action out of `PipelineAction` and into `ManualPasteAction` (breaking change to the action hierarchy), or (b) keeping the action under `PipelineAction` and routing it via the registry to the new module — but the registry routes by `actionClass` (the root sealed class), so option (b) doesn't work without splitting `PipelineAction` further.
- **Architectural overhead disproportional to scope.** A single `Boolean` is the smallest possible axis. Project-wide module-count inflation has a real cognitive cost (per `docs/architecture/state-architecture/modules.md` discoverability note).

**Verdict:** Theoretically cleanest, practically over-engineered for one Boolean. Defensible if precedent matters more than minimalism, but rejected on cost-benefit.

---

### Option D (recommended) — Relocate the flag into `ResendState`, owned by `ResendModule`

**Mechanism:** Add `lastResultNeedsManualPaste: Boolean = false` as a sibling field on `ResendState`. Move ownership of `Action.PipelineAction.NotifyResultNeedsManualPaste` + `Action.PipelineAction.ClearManualPasteFlag` from `PipelineAction` to a renamed `Action.ResendAction.NotifyManualPasteNeeded` + `Action.ResendAction.ClearManualPasteFlag` (or, less invasively, leave the actions under `PipelineAction` but cascade them from `PipelineModule.onCrossModuleStateChange` to a new `Action.ResendAction.SetManualPasteFlag(value: Boolean)` — see "Action routing variants" below).

**Action routing variants:**

- **D-variant 1 (cleanest hierarchy fit):** rename the two existing actions to live under `ResendAction`. PipelineModule's recovery cascade (or B3's recovery path directly) dispatches `Action.ResendAction.NotifyManualPasteNeeded(sessionId)` and `Action.ResendAction.ClearManualPasteFlag`. ResendModule reduces them. **Cleanest, but requires action-tree restructuring.**
- **D-variant 2 (least churn, recommended):** keep the action names under `PipelineAction` (callers don't change), but PipelineModule's reducer arm for `NotifyResultNeedsManualPaste` and `ClearManualPasteFlag` continues to return `null` (it's not its own axis — clean ADR-0001 read), and ResendModule's `onCrossModuleStateChange` (today: empty) gains a hook: it observes the same Action that PipelineModule received via a different mechanism — **but cross-module observation is on (prev, next) state tuples, not on actions.** So variant 2 needs a workaround. The natural workaround: PipelineModule does NOT receive these actions at all — they go directly to ResendModule. To do that, the registry needs to route `NotifyResultNeedsManualPaste` to ResendModule, but the registry routes by root `actionClass` and `NotifyResultNeedsManualPaste` is under `PipelineAction`. → **D-variant 2 requires splitting the sealed action hierarchy or moving the leaves out — same problem as Option C.**

→ **Therefore D-variant 1 is the only clean form of D.**

**Pros (D-variant 1):**
- Same-axis write — ResendModule's lens already covers `ResendState`, so adding a field and reducing actions on it is a Mode-1 same-axis write. No ADR violation.
- **Semantic fit.** ResendState already holds `lastAudioExists` — set as a post-pipeline UI affordance via the `MarkLastAudio` cascade. `lastResultNeedsManualPaste` is a parallel post-pipeline UI affordance set via a parallel cascade. The two flags are siblings: both are "things the user can do with the just-finished result". The cascade pattern is identical to the existing `MarkLastAudio` flow.
- **Cheap.** ~1 new field on `ResendState`, ~2 new reducer arms on ResendModule, ~2 renamed `Action.ResendAction.*` leaves, plus the dead `PipelineAction.NotifyResultNeedsManualPaste` / `ClearManualPasteFlag` are removed. The §15.1.x matrix row for `Pipeline × Resend` already has `R(state.resend) C(ResendAction.MarkLastAudio)` — we add `C(ResendAction.NotifyManualPasteNeeded)` (or fold into a single cascade-arm).
- **No registry inflation.** 14 modules stay 14.
- **Forward-compat for B3.** B3's recovery dispatches `Action.ResendAction.NotifyManualPasteNeeded(sessionId)` directly (no PipelineModule intermediate); the dispatch is routed to ResendModule via `KClass`-routing as today.
- **Top-level field eliminated.** The `lastResultNeedsManualPaste: Boolean` field on `DictateUiState` goes away — it folds into `ResendState`. The Spec §3 axes table loses the "top" row, becoming a clean 13-axis table (= one field per module + nothing top-level). Architectural simplification.

**Cons:**
- The action moves from `PipelineAction` to `ResendAction`. Every dispatcher of those two action leaves needs updating — today there are **zero production dispatchers** and one test reference (`ModuleServicesTest.kt:75-80`) + one hierarchy-equality test (`ActionHierarchyTest.kt:86`). Total: 2 test sites + the field-declaration deletion + the new field-addition. **Low impact.**
- B3's planned dispatch site would already be net-new code; it writes the action by its new name from the start.
- One Spec-section update needed (Spec 1 §3 axes-table loses the "top" row; §15.1.x matrix row gains a cascade entry; §15.1 axes-count goes from "13 + 1 top-level" to "13").

**Verdict:** **Best fit.** Same architectural alignment as Option C, dramatically lower cost, and a side-benefit of eliminating the awkward top-level field that doesn't fit the "one axis per module" axis-table.

---

### Comparison table

| Option | ADR-0001 fit | ADR-0002 fit | Files touched | Tests impacted | New module? | Spec edits | Eliminates top-level field? | Verdict |
|---|---|---|---|---|---|---|---|---|
| A — fold into PipelineUiState | ⚠️ stretches FSM | ✓ | 2 prod + ~5 tests | ~30 (Idle-equality assertions) | no | medium (FSM-shape changes) | no | reject (semantic mismatch + high test churn) |
| B — tuple-lens | ❌ violates single-axis spirit | ✓ | 2 prod + ~3 tests | ~5 | no | medium (DictateModule pattern) | no | reject (single-axis discipline) |
| C — new ManualPasteModule | ✓ | ✓ | 4-5 new files | ~3 new test files | yes (15th) | small | yes | possible, but over-engineered |
| **D — fold into ResendState** | ✓ | ✓ | 3 prod + ~4 tests | ~2 (action-rename) | no | small (axis-table + matrix) | **yes** | **recommended** |

---

## §4 Recommendation: Option D (fold flag into `ResendState`)

**Rationale grounded in ADR + Spec:**

1. **ADR-0001 single-axis-per-module discipline is preserved.** ResendModule continues to own one axis (`ResendState`); the new field is part of that axis. The lens `read(global) = global.resend` / `write(global, sub) = global.copy(resend = sub)` mutates the field with a Mode-1 same-axis write (no Mode-3 violation).
2. **ADR-0002 cascade pattern is reused, not invented.** The existing `PipelineModule.onCrossModuleStateChange` already cascades `Action.ResendAction.MarkLastAudio(exists = true)` on the `prev != Idle → next is Idle` transition (`PipelineModule.kt:376-381`). The B3 recovery path will reuse the same cascade slot to also dispatch `Action.ResendAction.NotifyManualPasteNeeded(sessionId)` when the IME has no `InputConnection` (per Spec 1 §11.6 / R.18).
3. **Spec 1 §15.1.x matrix gains symmetry.** The `Pipeline × Resend` cell becomes `R(state.resend) C(ResendAction.MarkLastAudio + ResendAction.NotifyManualPasteNeeded)` — both post-pipeline UI affordances cascade from the same observer hook. This is **simpler** than the current shape, not more complex.
4. **Spec 1 §3 axes-table simplifies.** Today: 13 sub-state-axes + 1 top-level field. After: 13 sub-state-axes, no top-level field. The "top" row in the axes-table KDoc at `DictateUiState.kt:43` disappears, and the file-level KDoc claim of "13 sub-state fields + 1 top-level flag" becomes "13 sub-state fields, single-owner-per-axis throughout" — cleaner per ADR-0001's "Module inventory" binding contract.
5. **Issue 2.1.9 Option C semantics preserved.** The flag remains a Boolean UI hint companion to the post-pipeline session result. Whether it lives on `DictateUiState` directly or inside `ResendState` doesn't change the user-facing UX — but moving it inside `ResendState` aligns the field with its semantic siblings (`lastAudioExists`).

---

## §5 Implementation Hints (for the implementer-agent)

### Production changes

1. **`app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt`**
   - Remove the top-level field `val lastResultNeedsManualPaste: Boolean = false,` (line 80) and its initialiser line (line 103).
   - Add the field to `ResendState` (line 297-301):
     ```kotlin
     data class ResendState(
         val lastAudioExists: Boolean = false,
         val resendEnabled: Boolean = false,
         val resendCooldown: Boolean = false,
         /**
          * Set after IME-service-death recovery when the pipeline completed
          * but no InputConnection was available to insert the result; the
          * recovery path copied the result to the system clipboard and now
          * the IME header must hint "tap to paste". Cleared by
          * `Action.ResendAction.ClearManualPasteFlag` after the user pastes.
          * (Issue 2.1.9 Option C; F-1 fix per
          *  `research/manual-paste-field-architecture.md`.)
          */
         val lastResultNeedsManualPaste: Boolean = false,
     )
     ```
   - Remove the "top" row from the axes-table KDoc at lines 26-44.
   - Update the file-level KDoc count from "13 sub-state axes + 1 top-level flag" to "13 sub-state axes".

2. **`app/src/main/java/net/devemperor/dictate/state/Action.kt`**
   - Remove the two leaves from `PipelineAction` (lines 177-181):
     - `data class NotifyResultNeedsManualPaste(val sessionId: String) : PipelineAction()`
     - `data object ClearManualPasteFlag : PipelineAction()`
   - Add equivalent leaves to `ResendAction`:
     ```kotlin
     /** Service-death recovery — tell the user to paste from clipboard. */
     data class NotifyManualPasteNeeded(val sessionId: String) : ResendAction()

     /** User pasted — clear the manual-paste hint. */
     data object ClearManualPasteFlag : ResendAction()
     ```

3. **`app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt`**
   - Remove the two reducer arms at lines 292-311 (`NotifyResultNeedsManualPaste` and `ClearManualPasteFlag`). After the action-leaves move out of `PipelineAction`, those arms become dead `when`-branches and the compiler will demand their removal (sealed-exhaustiveness).
   - Update the class-KDoc at lines 12-16 — remove the "plus the `lastResultNeedsManualPaste`-flag for IME-service-death recovery" clause; the module no longer claims that field.

4. **`app/src/main/java/net/devemperor/dictate/state/modules/ResendModule.kt`**
   - Add two reducer arms for the new `ResendAction` leaves:
     ```kotlin
     is Action.ResendAction.NotifyManualPasteNeeded ->
         if (!state.lastResultNeedsManualPaste) {
             TransitionResult(
                 nextState = state.copy(lastResultNeedsManualPaste = true),
                 sideEffects = emptyList(),
             )
         } else null  // idempotent

     Action.ResendAction.ClearManualPasteFlag ->
         if (state.lastResultNeedsManualPaste) {
             TransitionResult(
                 nextState = state.copy(lastResultNeedsManualPaste = false),
                 sideEffects = emptyList(),
             )
         } else null  // idempotent
     ```
   - Update the class-KDoc cross-module-cascade list to mention the new cascade target.

5. **Optional but recommended: `PipelineModule.onCrossModuleStateChange`** — for the success path on a same-process pipeline (not just the death-recovery path), do NOT auto-cascade `NotifyManualPasteNeeded`. The flag is set only by B3's recovery path, which has the IC-availability check. Leave the existing `MarkLastAudio` cascade alone; the manual-paste cascade dispatch is the recovery-path's responsibility, not PipelineModule's `onCrossModuleStateChange` hook. (This is the difference between "always set after Done" — wrong — and "set only when result couldn't be inserted via IC" — correct, requires recovery-path knowledge.)

### Test changes (existing)

| File | Line | Change |
|---|---|---|
| `app/src/test/java/net/devemperor/dictate/state/DictateUiStateTest.kt` | 44 | `assertFalse(s.lastResultNeedsManualPaste)` → `assertFalse(s.resend.lastResultNeedsManualPaste)` |
| `app/src/test/java/net/devemperor/dictate/state/DictateUiStateTest.kt` | 273-285 | Move the two `lastResultNeedsManualPaste` tests into ResendModule's scope or rewrite as `resend.lastResultNeedsManualPaste`-assertions on the sub-state. |
| `app/src/test/java/net/devemperor/dictate/state/PipelinePrefMirrorTest.kt` | 349 | `before.lastResultNeedsManualPaste` → `before.resend.lastResultNeedsManualPaste` (or drop the assertion — the new field is owned by ResendState, which the PrefMirror tests already cover via the resend axis). |
| `app/src/test/java/net/devemperor/dictate/state/ModuleServicesTest.kt` | 75-80 | `Action.PipelineAction.ClearManualPasteFlag` → `Action.ResendAction.ClearManualPasteFlag` (rename only — the test is verifying `emitAction` routing, which still works). |
| `app/src/test/java/net/devemperor/dictate/state/ActionHierarchyTest.kt` | 86 | Same rename. |

### Test additions (new)

Add to `app/src/test/java/net/devemperor/dictate/state/ResendModuleTest.kt`:

```kotlin
@Test
fun `NotifyManualPasteNeeded flips lastResultNeedsManualPaste from false to true`() {
    val state = ResendState(lastResultNeedsManualPaste = false)
    val result = ResendModule.reduce(
        state,
        Action.ResendAction.NotifyManualPasteNeeded(sessionId = "sid-1"),
        ReducerContext(global = DictateUiState.initial(), now = 0L),
    )
    assertNotNull(result)
    assertTrue(result!!.nextState.lastResultNeedsManualPaste)
}

@Test
fun `NotifyManualPasteNeeded is idempotent when already set`() {
    val state = ResendState(lastResultNeedsManualPaste = true)
    val result = ResendModule.reduce(
        state,
        Action.ResendAction.NotifyManualPasteNeeded(sessionId = "sid-1"),
        ReducerContext(global = DictateUiState.initial(), now = 0L),
    )
    assertNull(result)  // no state change
}

@Test
fun `ClearManualPasteFlag flips lastResultNeedsManualPaste from true to false`() {
    val state = ResendState(lastResultNeedsManualPaste = true)
    val result = ResendModule.reduce(
        state,
        Action.ResendAction.ClearManualPasteFlag,
        ReducerContext(global = DictateUiState.initial(), now = 0L),
    )
    assertNotNull(result)
    assertFalse(result!!.nextState.lastResultNeedsManualPaste)
}

@Test
fun `ClearManualPasteFlag is idempotent when already clear`() {
    val state = ResendState(lastResultNeedsManualPaste = false)
    val result = ResendModule.reduce(
        state,
        Action.ResendAction.ClearManualPasteFlag,
        ReducerContext(global = DictateUiState.initial(), now = 0L),
    )
    assertNull(result)  // no state change
}
```

### Spec / Doc updates

- **Spec 1 §3** (axes-table at line 285-310): remove the `(top) | lastResultNeedsManualPaste` row. Update the axes-count from "13 + 1 top-level Boolean" to "13" (the top-level Boolean disappears).
- **Spec 1 §15.1** (axes-table line 6199-6222): ResendState row gains a brief note that it now holds the manual-paste hint.
- **Spec 1 §15.1.x** (matrix line 6248-6262): `Pipeline × Resend` cell can stay as-is (the `MarkLastAudio` cascade is the only one PipelineModule auto-fires); the new `NotifyManualPasteNeeded` cascade is dispatched by B3's recovery path, not by `PipelineModule.onCrossModuleStateChange` — so no matrix row update needed for this fix. A B3 future-edit will add a `Recovery → Resend` row when the recovery path becomes a state-owner.
- **`docs/architecture/state-architecture/state-and-actions.md`**: lines 111 + 136 (axes-table mirror) — same removal as Spec §3.
- **F-18 (Action.kt KDoc, validated-findings-B2.md line 567)**: the `PipelineAction.ClearManualPasteFlag` reference becomes `ResendAction.ClearManualPasteFlag` — note in F-18 fix that the action moved.

### Block-report deviation entry

Document under B2 block-report `### Block-Validate Repair Wave 1`:

```
| Dev-RW1 | Spec 1 §3 axes-table + DictateUiState.kt top-level field | Top-level `lastResultNeedsManualPaste: Boolean` field on `DictateUiState` removed; field relocated as `ResendState.lastResultNeedsManualPaste`; two `PipelineAction` leaves moved to `ResendAction`. | F-1 fix per `research/manual-paste-field-architecture.md` — single-axis-per-module discipline restored. | B3 recovery-path dispatches via the new action name | inline-fixed |
```

---

## §6 Forward-Compatibility Note (B3 Service-Death-Recovery)

B3 will extend `PipelineRecovery.recover(store)` to detect sessions that reached `status = COMPLETED` but never had `inserted_at` set (because the IME service died before it could insert through `InputConnection`). The recovery path:

1. Loads pending sessions from the DB (as today).
2. For each session with a `COMPLETED + final_output_text != null` but `inserted_at IS NULL`: write the text to the system clipboard (`services.clipboard.setPrimaryClip(...)`) and dispatch:
   ```kotlin
   services.emitAction(Action.ResendAction.NotifyManualPasteNeeded(sessionId))
   ```
3. ResendModule's reducer flips `state.resend.lastResultNeedsManualPaste = true`.
4. The keyboard header (B3 UI wiring) renders a "tap to paste" hint based on `state.resend.lastResultNeedsManualPaste`.
5. When the user performs the paste (or dismisses), the IME dispatches `Action.ResendAction.ClearManualPasteFlag` and the flag clears.

**Why this works cleanly with Option D:**

- The recovery code path already lives in `PipelineRecovery.recover` which already calls `store.update {}` directly (see `PipelinePrefMirror.kt` precedent). Per F-6 (a separate finding), recovery will gain a `try/catch` around the IO; same structure applies here.
- Switching from `store.update` to `services.emitAction(...)` for the flag-set is a one-line change at the recovery site. The action gets routed through `DictateOrchestrator.dispatch` → ResendModule.reduce, fully visible to subscribers and observers. No "out-of-band write" pattern; the architecture's single-dispatch invariant is preserved.
- The `sessionId` payload of `NotifyManualPasteNeeded` will let B3 cross-reference the pending-session entry — useful when the keyboard header offers a "paste this result" action that needs to identify which session's text is on the clipboard. (Today the action carries the field but nothing consumes it; B3 will.)

**Compatibility with simultaneous pending sessions:**

If two sessions both need manual paste (rare — two pipelines completed during one death window), the flag is a single Boolean — only one "needs paste" hint is shown. The `sessionId` payload of `NotifyManualPasteNeeded` overwrites the previous one's intent, and the clipboard holds the most recent text. Phase 1 acceptable; Phase 2 can extend to `pendingSessions` UI (already exists) for the multi-session case. The single-Boolean simplification is consistent with the original Issue 2.1.9 Option C design.

---

## §7 Decision-History Entry Placeholder

**ADR amendment recommendation:** No new ADR; **append Decision-History entry to ADR-0001 only.**

**Why ADR-0001 (not ADR-0002):**

- ADR-0001 §"Module inventory" lists `PipelineModule` as owning the pipeline FSM axis. The accompanying axes-count in the docs (and the §3 axes-table in Spec 1) attributes the top-level `lastResultNeedsManualPaste` field to `PipelineModule`. **That attribution turns out to be wrong** — the field is conceptually a ResendModule responsibility, and shipping it as a top-level field made the single-owner-per-axis claim degenerate (the field had nominal-owner PipelineModule but no actual owner in the lens). Folding it into `ResendState` restores the invariant.
- ADR-0002 is untouched — the cross-module-cascade rules don't need a change (this fix is a Mode-1 same-axis write inside ResendModule; the cascade from B3's recovery path is a normal Mode-2 cascade that fits the existing matrix).

**Proposed ADR-0001 Decision-History entry (template — implementer-agent adapts):**

```markdown
### 2026-05-15 — F-1 manual-paste field relocation (B2-VAL-REPAIR-1)

**Trigger:** Validated finding F-1 from `reports/validated-findings-B2.md` — the top-level `DictateUiState.lastResultNeedsManualPaste: Boolean` field had no module owning its mutation; `PipelineAction.NotifyResultNeedsManualPaste` + `ClearManualPasteFlag` reducer arms returned `null` (dead code).

**Before:** `DictateUiState` declared a top-level `lastResultNeedsManualPaste: Boolean` field nominally attributed to `PipelineModule` per the axes-table (§"Module inventory" + Spec 1 §3 axes-table). No module's lens covered the field — `PipelineModule.lens` writes `pipeline = sub` only. The two `PipelineAction` leaves for setting/clearing the flag explicitly returned `null` in the reducer, with a KDoc comment referring to an "out-of-band write via `_state.update` on PrefMirror init" that was never implemented. Result: the field was permanently `false` and the IME-service-death recovery user-affordance (Spec 1 §11.6 / R.18) was non-functional.

**After:** Field moved into `ResendState` as `ResendState.lastResultNeedsManualPaste: Boolean` (sibling to the existing `lastAudioExists` post-pipeline UI flag). Two action leaves moved from `PipelineAction` to `ResendAction`: `Action.ResendAction.NotifyManualPasteNeeded(sessionId)` + `Action.ResendAction.ClearManualPasteFlag`. ResendModule reduces both with same-axis Mode-1 writes — its existing lens covers the field. Top-level `lastResultNeedsManualPaste` field removed from `DictateUiState`; axes-count goes from "13 + 1 top-level Boolean" to "13 sub-state axes, no top-level". Module inventory unchanged (still 13 active + 1 Phase-2 stub).

**Reasoning:** ADR-0001's single-axis-per-module discipline was nominally violated by the top-level field — it had a nominal owner (`PipelineModule`) but no actual lens covered it, so the reducer-arms could not implement the mutation without a Mode-3 cross-axis write (forbidden per ADR-0002). The three architectural options evaluated (relocate into PipelineUiState, extend lens to tuple, introduce dedicated ManualPasteModule — see `research/manual-paste-field-architecture.md`) all had higher costs and worse architectural fits than folding the flag into `ResendState`. ResendState already holds `lastAudioExists` — both fields are post-pipeline UI affordances with parallel cascade structure; making them siblings is the natural taxonomic placement.

**Reference:** `research/manual-paste-field-architecture.md` (this research doc).
```

**Plan-side updates also needed:**

- The plan's `## References` block should add a row pointing to the research doc.
- Spec 1 §15.1 axes-table updates to reflect new ResendModule field.

---

## References

- **Block-report anchor:** `reports/validated-findings-B2.md` §F-1 (this research's source finding).
- **Plan path:** `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md`.
- **Spec sections consulted:** Spec 1 §3 (DictateUiState axes-table), §4 (DictateModule pattern), §11.6 (OOM-Death-Recovery), §15.1 (Module-Übersicht), §15.1.x (Coupling-Matrix), §15.5 (Cross-Module-Effect-Modi).
- **ADRs consulted:** ADR-0001 (state-modular-orchestrator-pattern) §"Required mechanics" + §"Module inventory" + §"Failure Modes"; ADR-0002 (state-cross-module-cascade) §"The two allowed modes" + §"The forbidden third mode".
- **Audit reports consulted:** `reports/audit-logic-B2.md` AUDIT-LOGIC-B2-1; `reports/audit-plan-and-api-B2.md` AUDIT-PLAN-AND-API-B2-1; `reports/audit-convention-B2.md` (F-18 NTH context).
- **Code files inspected:** `app/src/main/java/net/devemperor/dictate/state/{DictateUiState.kt, DictateModule.kt, ModuleId.kt, Action.kt, DictateModuleRegistry.kt, ModuleServices.kt}`, all 14 module files under `state/modules/`.
- **Sub-findings discovered:** see §"Sub-findings" below.

---

## Sub-findings (for orchestrator triage into validated-findings-B2.md)

These were not in the original audit but surfaced during this research:

- **SF-1 (NTH):** ADR-0001 §"Module inventory" implicitly attributes `lastResultNeedsManualPaste` to PipelineModule via the axes-table in `state-and-actions.md:136` and Spec 1 §3:306. If Option D is adopted, **ADR-0001 needs a Decision-History entry** (per knowledge-adr-format §"Lifecycle and editing rules" — Accepted ADRs are append-only). Template in §7 above.
- **SF-2 (NTH):** F-18 (Action.kt KDoc) lists `ClearManualPasteFlag` under `PipelineAction` — after Option D the leaf moves to `ResendAction`, so the F-18 fix-list needs to update its target (rename the leaf in the F-18 patch). Cross-cutting note for the orchestrator's repair-wave routing.
- **SF-3 (NTH):** `PipelineModule.kt:12-16` class-KDoc claims ownership of the flag; after the fix, that KDoc clause should be removed (also covered by §5 step 3 above, but called out here so the audit-trail is complete).
- **SF-4 (NTH):** Both AUDIT-LOGIC-B2-1 and validated-findings F-1 mention that `PersistenceError` (`PipelineModule.kt:271-280`) "also expects the manual-paste flag to be raised if the failure happened post-text-extraction." That cross-module emission is **not** part of this fix — the failure path's manual-paste signalling is a recovery-path responsibility (similar to the success path), and PipelineModule's reducer should not own it. Phase-1 acceptable to leave un-flagged; B3 may add a recovery-path dispatch in the PersistenceError path as well. Flagged here as a Phase-2 / B3-wiring note, not a Phase-1 gap.

