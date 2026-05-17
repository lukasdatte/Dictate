# Keyboard-Layout-Refactor: Service-centric SSOT + 3-Mode Triangle (KEYBOARD/WIDGET/HOVER)

**Status:** Skeleton — architecture finalized in iteration with the user, detailed specs in progress
**Created:** 2026-05-07 — Skeleton completed: 2026-05-08
**Branch:** `feature/dictate-keyboard-layout-refactor` (worktree, Phase-0-skill-mandated)
**Plan skill:** `feature-planning` → `implement-long-plan-v2` (active 2026-05-14)
**Complexity:** Large (service layer + UI refactor + new window type)

<!-- EXECUTION-PLAN -->
**Implement-Long-Plan-v2 execution strategy (Phase 1a + 1b — 2026-05-14):**

- **Chunking:** Plan-Reader-Mode (plan 1699 lines + 3 specs 6984/2601/2857 lines, modular-plan-pattern D21 active). 4 chunks.json files (1 plan-level, 3 spec-level), all validated. 19 chunks total, aggregate Implementation-Score ~16,200, ~11,200 LOC est.
- **Block grouping (6 blocks):**
  - **B0** — Architecture-Foundation (C0): 5 ADRs + state-architecture docs as binding pre-code anchor. Docs-only, no code. XL D12-atomic Foundation-Pack.
  - **B1** — Pre-Architecture + Service-Skeleton (C1, C2): Block-1a quick-wins + DictatePipelineService skeleton with FGS.
  - **B2** — Modular-Orchestrator (C3 → C4 → C5 → C6 → C7): state-types → orchestrator+registry → core modules → aux modules → wiring. Heaviest block (4350 score).
  - **B3** — Migration-Persistence-AudioFactory (C8 → C9 → C10 → C11): subsystem-adapter migration → DB schema M3→M4 → recovery+cleanup → AudioFileFactory. Introduces androidTest infrastructure (C9 Step 0).
  - **B4** — Keyboard-Layout-Catalog (C12 → C13 → C14 → C15): LayoutCatalog → MotionScene XML → ImeViewBackend → service wiring + cleanup.
  - **B5** — Floating-Overlay (C16 → C17 → C18): OverlayBackend + window → permission flow → mode-transitions T1-T7 + drag.
- **Test approach:** JVM unit tests with handwritten fakes (K-1, no Mockito/MockK) + no Android Context (K-4, no Robolectric) per Quality-Gate decision. First-time androidTest introduction in B3 (Room migration). Espresso UI-Tests 1-10 in B4. 5-step chunk workflow with 2 commits per chunk (production + tests).
- **Block-validation:** Multi-agent audit per block (PLAN-AND-API / CONVENTION / LOGIC / TEST) + consolidator + repair-sub-phase (iter cap 3). Test-agent count per block: 1-3 (state-file `Test-Agents` column).
- **E2E (Phase 4.5):** 24 manual TCs (Android-IME E2E is device-attached, no headless runner). Knowledge-skill gap flagged (`test-knowledge-android` / `test-knowledge-mobile` / `test-knowledge-ime` missing) — runbook hand-rolled.
- **User mode:** "ohne Walkthrough" — orchestrator runs Phase 0 → 5 autonomously without pause-questions. Phase-2 user-approval is briefing-only (informational). Phase-4.5 3 blocking-user-questions defaulted in state-file; user can override before Phase 4.5 starts.
- **Worktree:** `/home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor` (branch `feature/dictate-keyboard-layout-refactor`).
- **State-file:** `./dictate-keyboard-layout-refactor.state.md` (alongside this plan).
<!-- /EXECUTION-PLAN -->

---

## Translation note & split map

This is the English translation of the German working-language plan
`dictate-keyboard-layout-refactor.reviewed.md` (1717 lines). Per the
D16 split rule (> 1500 lines), the translation is split into three
files on the plan's top-level section boundaries:

| File | Source sections covered | Source line range |
|---|---|---|
| `dictate-keyboard-layout-refactor.reviewed-0-overview.en.md` (this file) | Frontmatter + EXECUTION-PLAN + §1 Context & Trigger + §2 Goals + §3 Architecture Vision + this split map | 1–228 |
| `dictate-keyboard-layout-refactor.reviewed-1-building-blocks.en.md` | §4 Building Blocks (Implementation Order) | 229–1123 |
| `dictate-keyboard-layout-refactor.reviewed-2-specs-risks-references-iteration-log.en.md` | §5 Spec-Files · §6 Risks · §7 Remaining Open Questions · §8 References · §9 Iteration-Log | 1124–1717 |

The German source file is preserved unchanged as the archived
working-language record (per `language-conventions.md`).

---

## 1. Context & Trigger

### 1.1 Symptom History

Today's architecture of the main-button area has produced a systematic class of bugs:

<!-- FIX: Issue 3.0.9 – Acceptance-Verifikator-Spalte ergänzt (Bidirectional-Pointer auf Spec/§/Test-ID); Bug #3 in #3a (Send-Mode-Verdecken) + #3b (Resend-Toggle-Verschwinden) gesplittet -->

| # | Date / Symptom | Description | Acceptance Verifier |
|---|---|---|---|
| 1 | 2026-05-06 — Asymmetric re-parenting (single-row toggle) | `trash_btn` / `pause_btn` were forgotten on toggle-on → invisible after mode switch | Spec 2 §10 + §14.2 UI-Test 1 (toggle single-row in idle); structurally eliminated via MotionLayout (no more re-parenting, Spec 2 §7) |
| 2 | 2026-05-07 — Asymmetric re-parenting (revert) | `record_pulse_layout` / `backspace_btn` / `resend_btn` were all stuffed into `input_row` → immediate fix with `originalParents` map | Spec 2 §10 + §14.2 UI-Test 7 (toggle single-row during recording); structurally eliminated (L2 flat hierarchy) |
| 3a | Send mode + single-row (send-btn occlusion) | Send button in send mode + single-row partially occluded | Spec 2 §10 + §14.2 UI-Test 4 ("Send button fully visible — critical bug-fix verifier") |
| 3b | Send mode + toggle (resend-btn disappearance) | `resend_btn` disappears on toggle two-row ↔ single-row in idle+lastAudio | Spec 2 §14.2 UI-Test 8 (frame capture during toggle) + UI-Test 9 (cooldown verification, visibility unbroken); Spec 1 §10 Block-1: `predResendVisible` does NOT reflect `resendCooldown` (cooldown lands only in the `enabledResolver`, see Spec 2 §8.5) |

These bugs are not coincidental. They are symptoms of **one fundamental architecture problem**: layout position and state visibility are managed in parallel across several code paths that overwrite one another. Every state change is a race between layout application and visibility computation.

### 1.2 Extended Requirement Set (developed in iteration with the user)

Further requirements were added during plan iteration:

- **Keyboard-switch survival**: recording/pipeline should keep running when the user switches to another keyboard (e.g. Gboard for a password field) and later returns.
- **WIDGET mode (user toggle)**: the keyboard can be moved into a floating widget with 4 buttons (Send + Pause + Trash + Close). The InputConnection stays alive, Send works.
- **HOVER mode (auto)**: when the keyboard is closed during active recording/pipeline, a floating window automatically appears with the **same 4-button layout** as WIDGET — the Send button here is only **disabled** (no InputConnection).
- **Close-button differential behavior**:
  - In HOVER: click → overlay disappears completely. The user must open + close the keyboard for the overlay to reappear.
  - In WIDGET: click → the keyboard is made small, state transitions back to KEYBOARD mode (with possibly active SmallMode).

### 1.3 What the Research Has Found So Far

Existing research (Phase 2):

- [research/main-button-area-inventory.md](research/main-button-area-inventory.md) — capability inventory (9 buttons, 4 state axes, visibility matrix)
- [research/motionlayout-architecture-options.md](research/motionlayout-architecture-options.md) — evaluation of 5+ layout-switching patterns. Recommendation: **MotionLayout + flat MotionScene** with `VISIBILITY_MODE_IGNORE` per state-driven button
- [research/_pending-layout-container-architecture/](research/_pending-layout-container-architecture/_pending-layout-container-architecture.md) — confirms the MotionLayout recommendation with concrete modifications + 2 spike validations
- [research/_pending-state-machine-visibility-owners/](research/_pending-state-machine-visibility-owners/_pending-state-machine-visibility-owners.md) — 27 visibility mutations tabulated; **5 problematic ones on `resend_btn`** in 3 classes identified; clear SSOT-consolidation order
- [research/_pending-ime-lifecycle-view-recreation/](research/_pending-ime-lifecycle-view-recreation/_pending-ime-lifecycle-view-recreation.md) — IME-lifecycle depth; confirms: view-recreate is first-class implemented; NO coroutines in the IME, NO WorkManager dependency
- [research/_pending-persistence-background-architecture/](research/_pending-persistence-background-architecture/_pending-persistence-background-architecture.md) — Room v3 is surprisingly mature; `RECORDED` status fits A+B persistence; **NO new table needed**, only 1 new column (`inserted_at`)

---

## 2. Goals

### 2.1 Architecture Goals

| Dimension | Today | Refactor Goal |
|-----------|-------|---------------|
| **Pipeline-logic owner** | in the IME-service process (dies on keyboard switch) | **dedicated foreground service** `DictatePipelineService` (survives keyboard switch) |
| **State SSOT** | hybrid (KSM + RecordingUiController + service mutate directly) | **`DictateOrchestrator` (composition root) + 13 modules in the foreground service** as the sole mutation source (Spec 1 §4.3 / §15) |
| **Visibility computation** | hybrid (5 mutators on `resend_btn`) | **declarative via `LayoutCatalog` predicates** in the LayoutManager |
| **Layout position** | imperative (ConstraintSet + re-parenting) | **declarative via MotionScene** (KEYBOARD backend) resp. static XML (overlay backend) |
| **Background robustness** | none (recording loses its owner on keyboard switch) | **foreground service keeps the process alive**; on OOM-death (rare): user-controlled resume from DB |
| **Layout modes** | 2 (two-row, single-row) | **3-mode triangle** (KEYBOARD, WIDGET, HOVER) plus 4 KEYBOARD sub-modes |

### 2.2 Success Criterion (formulated by the user)

> A UI change (new button, new mode, new state transition) can be described in **one place**, and the UI reflects it **automatically correctly** — without having to coordinate three classes or test for race conditions.

### 2.3 Bug-Elimination Goals

- Elimination of the "asymmetric re-parenting" bug class through a structural measure (MotionLayout instead of re-parent).
- Elimination of the `resend_btn` race (5 mutators → 1 predicate).
- Elimination of the `recordButton.text/isEnabled` hybrid (RecordingUiController + KeyboardUiController overwrite one another today).
- Send button in send mode + single-row visible correctly, not occluded.
- "Stale running session" on process death: zombie-like today, in the future solved through persistence recovery + user resume.

---

## 3. Architecture Vision

### 3.1 Triangle FSM (KEYBOARD / WIDGET / HOVER)

```
                   ┌──────────────────────────────┐
                   │        KEYBOARD              │
                   │   (volle Tastatur, normal)   │
                   │                              │
                   │   - Two-Row / Single-Row     │
                   │   - Send-Mode-Varianten      │
                   │   - ReprocessStaging         │
                   │   - InputConnection LEBT     │
                   └──────────────────────────────┘
                       │   ▲                  ▲
                       │   │                  │
              User klickt│   │User klickt    │User öffnet
              Widget-    │   │Widget-Close   │Tastatur wieder
              Toggle     │   │(transition    │(View kommt
                         ▼   │ via SmallMode)│ zurück)
                   ┌─────────────────────────┐  │
                   │       WIDGET            │  │
                   │   (User-Wahl, floating) │  │
                   │                         │  │
                   │   - 4 Buttons           │  │
                   │   - Send funktioniert   │  │
                   │   - InputConnection lebt│  │
                   └─────────────────────────┘  │
                       │   ▲                    │
                       │   │                    │
              View hidden│   │View kommt        │
              + Recording│   │zurück (User      │
              läuft      │   │öffnet Tastatur)  │
                         ▼   │                  │
                   ┌─────────────────────────┐  │
                   │      HOVER (Auto)       │──┘
                   │                         │
                   │   - 4 Buttons (gleiches │
                   │     Layout wie WIDGET)  │
                   │   - Send DISABLED       │
                   │   - Schließen → dismiss │
                   │   - InputConnection WEG │
                   └─────────────────────────┘
```

**6 transitions**, all triggered by the `KeyboardLayoutManager`. Auto-transitions are based on two inputs: `imeViewSichtbar?` and `pipelineAktiv?`. User-toggle transitions arrive via click events.

### 3.2 Service Layer (new)

```
╔══════════════════════════════════════════════════════════════════════╗
║                  APP-HAUPTPROZESS (immer derselbe)                   ║
║                                                                      ║
║  ┌────────────────────────────────────────────────────────────────┐ ║
║  │           DictatePipelineService (Foreground)                   │ ║
║  │   — überlebt Tastatur-Wechsel; Persistente Notification         │ ║
║  │                                                                 │ ║
║  │   <!-- FIX: Issue 1.0.1 – §3.2 Diagramm Naming-Update -->       │ ║
║  │   DictateOrchestrator (Composition Root, Single Dispatch)       │ ║
║  │     dispatch(action: Action) → Module-Registry-Routing          │ ║
║  │                                                                 │ ║
║  │   Ko-Aggregate (Hilfsklassen, F-11):                            │ ║
║  │     DictateUiStateStore  (StateFlow-Owner, _state Holder)       │ ║
║  │     PipelinePrefMirror   (SP ↔ Store-Spiegelung)                │ ║
║  │     PipelineRecovery     (DB-Replay)                            │ ║
║  │                                                                 │ ║
║  │   JobExecutor + PipelineOrchestrator (bestehend, bleibt)       │ ║
║  │                                                                 │ ║
║  │   RoomDatabase (sessions + 1 neue Spalte: inserted_at)         │ ║
║  └────────────────────────────────────────────────────────────────┘ ║
║                          ▲                                          ║
║                          │ Local Binder (kein IPC, gleicher Prozess)║
║                          │                                          ║
║  ┌───────────────────────┴────────────────────────────────────────┐ ║
║  │            DictateInputMethodService (IME-Service)              │ ║
║  │  — kommt und geht je nach Tastatur-Auswahl                      │ ║
║  │                                                                 │ ║
║  │   KeyboardLayoutManager (Triangle-FSM, Render-Orchestrator)    │ ║
║  │     subscribe(pipelineService.state) { render(...) }           │ ║
║  │                                                                 │ ║
║  │   ImeViewBackend (KEYBOARD-Modus)                              │ ║
║  │   OverlayBackend (WIDGET + HOVER, beide nutzen es)             │ ║
║  └────────────────────────────────────────────────────────────────┘ ║
╚══════════════════════════════════════════════════════════════════════╝
```

**Key properties:**
- **Both services in the same process** (no IPC, Local Binder + StateFlow for communication).
- **Foreground service keeps the process alive**, even when the IME service dies (keyboard-switch survival).
- **NO WorkManager worker** (decided by the user). On OOM-death: user resume from DB.
- **Persistent notification** serves simultaneously as the foreground-service mandatory UI and as a status display for the user.

### 3.3 LayoutDescriptor Pattern (core of the refactor)

Instead of having distributed code for each layout, every layout mode lives as a **data structure** in a central `LayoutCatalog`:

```kotlin
<!-- FIX: Issue 1.0.2 – §3.3 LogicalButtonId Liste auf Spec-2-§3.1-Stand -->
enum class LogicalButtonId { RECORD, RESEND, BACKSPACE, AUDIO_FOCUS, WIDGET_TOGGLE, TRASH, SPACE, PAUSE, ENTER, OVERLAY_RECORD, OVERLAY_SEND, OVERLAY_PAUSE, OVERLAY_TRASH, OVERLAY_CLOSE }

data class LayoutMode(
    val id: LayoutModeId,
    val backend: BackendType,
    val rows: List<RowDescriptor>,
)

data class ButtonSlot(
    val logicalId: LogicalButtonId,
    val widthPolicy: WidthPolicy,
    val visibilityPredicate: (DictateUiState) -> Boolean,
    val iconResolver: (DictateUiState) -> Int? = { null },
    val textResolver: (DictateUiState) -> CharSequence? = { null },
    val enabledResolver: (DictateUiState) -> Boolean = { true },
    val actionResolver: (DictateUiState) -> Action,
)

object LayoutCatalog {
    val KEYBOARD_TWO_ROW = LayoutMode(...)
    val KEYBOARD_SINGLE_ROW = LayoutMode(...)
    val KEYBOARD_TWO_ROW_SEND_MODE = LayoutMode(...)
    val KEYBOARD_SINGLE_ROW_SEND_MODE = LayoutMode(...)
    val KEYBOARD_REPROCESS_STAGING = LayoutMode(...)
    val OVERLAY_5BUTTON = LayoutMode(...)  // gemeinsam für WIDGET + HOVER  <!-- FIX: Issue 1.0.2 – OVERLAY_4BUTTON → OVERLAY_5BUTTON -->
}
```

Render backends iterate the slots, evaluate the resolvers against the current `DictateUiState`, and set visibility/icon/text/action.

---

<!-- FIX: Issue 1.1.6 / R.7 + 3.1.14 – Block 1 in 1a (heutiger Code, kompilier-grün) und 1b (PipelineService-Container) gesplittet -->
