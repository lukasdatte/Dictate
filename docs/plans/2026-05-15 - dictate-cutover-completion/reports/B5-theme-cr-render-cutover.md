# Block 5: Theme C-R — Render-Path Cutover (the missing INT-1-class scope)

> **Logbook for Block 5.** Implementation/Audit-Agents document here.
> Orchestrator maintains the state-file status table — agents do not.

**Phase:** Theme C-R — render-path cutover. The C10-IMPL-2 resolution:
Theme B did the *recording-drive* cutover; the *render-path* cutover never
happened (parent B4-VAL F-1/F-2/F-33 deferred it to a never-created block —
INT-1 anti-pattern at the render layer). This block ports the ~16 keyboard
UI behaviour groups from the 4 legacy controllers to the RenderBackend per
Spec 2 §9.x (SoT), then deletes the controllers.
**Implementation-Chunks:** CR1, CR2, CR3, CR4, CR-RGATE, C10-C3 (CR-DEL)
**Workflow:** Iter-10 5-step (combined-step; orchestrator splits 2 commits/chunk). Sequential CR1→CR2→CR3→CR4→CR-RGATE→CR-DEL. **mid-chunk-triage ARMED for CR1 (A1 long-press model), CR2 (RR-1 silent-listener-overwrite), CR3 (RR-2 blank-UI sequencing, A3 split-vs-delete), CR4 (RR-1+RR-2).**
**Block-Start-Commit:** c92ebd1
**Block-End-Commit:** ⏳

> **⚠ GATE (render-layer analogue of C6, load-bearing):** CR-RGATE is a
> verification GATE. C10-C3/CR-DEL (4-controller deletion) MUST NOT start
> until CR-RGATE signs off GREEN. The legacy controller CLASSES stay
> instantiated-but-undriven through CR4 (the drive-call surface IS the
> rollback switch — no boolean; forward-fix by re-adding a drive call).
> Deletion is the point-of-no-return, only after the render path is
> proven.

> **Spec SoT:** `research/render-path-cutover.md` (16-group → owner map,
> AC-RR-1..9, 8-risk register) + Spec 2 §9.x/§11.7/§11.8/§13 (the parent
> plan's target render architecture — already specified, never
> implemented). Inherits **F-6** from B3 (cross-carrier collapse
> ReprocessStaging.selectedLanguage → LanguageState.override — folded
> here; depends on KeyboardUiController/PipelineUiStateReader retirement).
> Resolves the deferred **C5-IMPL-2** (legacy recording-UI Idle on new
> path) as a side-effect of the render-port.

> **Ambiguities (per-chunk implementer-decided, D6):** A1 RECORD
> longClickResolver body (CR1 — proposed Action.RecordingAction.
> OnRecordLongPress), A2 ImeViewBackend.applyTheme/keyPressAnimator ctor
> + EditNumbersAnimator extract (CR1), A3 G9/G13 split-vs-delete
> (RecordingUiController/KeyboardUiController BLEIBT-parts per Spec 2
> §9.4/§9.5 — recommended option-a extract so AC-RR-7 stays clean
> zero-grep; CR3 or CR-DEL), A4 multi-backend visibility (CR3 — follow
> parent-B4 backendType=null, already implemented).

---

## Issue Index (Orchestrator-Maintained)

**Severity counts:** Critical: 0 · Important: 1 (F-6 inherited, deferred-in) · Nice-to-have: 0 · Postponed: 0

| ID | Source agent | Severity | Status | Title | Source phase |
|----|--------------|----------|--------|-------|--------------|
| F-6 (from B3) | B3-VAL-SANITY | Important | open → CR3/CR-DEL owns | Cross-carrier collapse: ReprocessStaging.selectedLanguage read → LanguageState.override (depends on KeyboardUiController/PipelineUiStateReader retirement = this block's scope) | inherited from B3-VAL-W1 |

---

## Conventions established this block

| Convention | Where established | Description |
|------------|-------------------|-------------|
| — | — | — |

---

## Mandatory Format Reminder for All Agents

Shared directives: `~/.claude/skills/implement-long-plan-v2/prompts/agent-prompts.md`.
Each agent documents: What was done · Plan deviations (table) · Issues
(table, severity + 5-status) · Overlooked points. 5-status: `open` /
`delegated-to-orchestrator` / `postponed` / `fixed` / `closed`.

---

## Implementation Logs

### Chunk CR1 — ImeViewBackend completion (Spec 2 §6 ctor + theming + long-press + key-anim)

**Agent-IDs:** `B5-CR1-IMPL` (fresh, combined Steps 1-5).
**Status:** ⏳ in_progress · **Risk:** MED (additive backend surface; A1 long-press is the only creative axis — mid-chunk-triage armed)
**Implementation-Commit (Commit 1):** ⏳ · **Test-Commit (Commit 2):** ⏳

### Implementation (B5-CR1-IMPL)

**What was done.** Closed the Spec 2 §6 ↔ code delta additively (no
legacy drive removed — that is CR4). Concretely:

- `state/Action.kt` — added `Action.RecordingAction.OnRecordLongPress`
  (pure `data object`, full A1-rationale KDoc).
- `state/layout/ButtonSlot.kt` — added the nullable
  `longClickResolver: (DictateUiState, ModuleServices) -> Action?` slot
  field (default `{ _, _ -> null }`, R.3-symmetric with `actionResolver`).
- `state/layout/ActionResolvers.kt` — added `resolveRecordLongPressAction`
  (Active/Paused → `OnRecordLongPress`, Idle/Preparing → `null`).
- `state/layout/LayoutCatalog.kt` — wired `longClickResolver` on the two
  **standard** RECORD slots (`::resolveRecordLongPressAction`) + the two
  **standard** RESEND slots (`{ _, _ -> ResendLastAudioLong }`). SEND_MODE
  / STAGING RESEND slots are `visibilityPredicate = { false }` (GONE) so
  they need no long-press resolver; SEND_MODE RECORD uses the pipeline
  resolver (no legacy long-press there).
- `state/modules/RecordingModule.kt` — added the `OnRecordLongPress`
  reducer arm to the **Active** and **Paused** branches (discard-stop
  effect set, identical to `StopRecording`). Idle/Preparing fall through
  the existing `else -> null` (R.3 second-defence).
- `state/render/ImeViewBackend.kt` — added `keyPressAnimator:
  KeyPressAnimator` ctor param (default `KeyPressAnimator()`); added
  `applyTheme(accentColor)` method (legacy-tier-faithful, owned buttons
  only); `wireStaticHandlers` now applies press-animation per owned
  button **except** SPACE/BACKSPACE/ENTER (RR-1), and attaches the
  catalog-driven long-press listener for **RESEND only** (RR-1 — RECORD
  listener deferred to CR4). Removed the F-1/F-2 interim KDoc, replaced
  with the new long-press-model + RR-1 KDoc.
- `core/EditNumbersAnimator.kt` — **new** standalone helper (G15, Spec 2
  §9.2). `MainButtonsController` now delegates
  `animateSmallModeToggle`/`animateEditNumbersBounce` to it (legacy
  call-sites unchanged → byte-identical live behaviour).
- `core/DictateInputMethodService.java` — minimal forced ctor-arg add:
  passes the shared `qwertzKeyboardView.getKeyPressAnimator()` (same
  instance MainButtonsController uses) to the new ImeViewBackend ctor
  slot. No new behaviour (backend still parallel/dormant per spec).

**A1 decision (RECORD long-press model) — additive, no
architecture-conflict.** Modelled exactly as render-path-cutover.md §7
A1 proposes: `OnRecordLongPress` is pure data; the 2-mode is resolved in
the RecordingModule reducer from `state.recording`. The FSM-half of the
Active/Paused branch (a *discard* stop, matching the legacy
`stopRecording()` which discards) maps cleanly to the existing
`StopRecording` effect set — no new `ModuleServices` surface. The legacy
Idle→`startActivity(DictateSettingsActivity + open_file_picker)` launch
and the `autoSwitchKeyboard` one-shot flag are **IME-side affordances,
not FSM state** — there is no Activity/IME-flag surface on
`ModuleServices` and adding one would exceed CR1's additive scope. Per
A1 these are the **CR4 IME-side activation** concern: the reducer returns
`null` for Idle (correct "no FSM transition" outcome) and the catalog
resolver short-circuits Idle/Preparing before dispatch (R.3). **This
stays within "additive resolver + Action + reducer-arm" — NOT flagged
`architecture-conflict`.**

**A2 decisions.** (a) `keyPressAnimator` ctor param added spec-faithful
(Spec 2 §6); `KeyPressAnimator` already existed at
`keyboard/KeyPressAnimator.kt` — reused, not re-created. (b)
`applyTheme(accentColor)` added as a method (Spec 2 §9.2 — separate
non-state-driven axis; service calls it after re-inflate; the *call* is
CR4 — legacy `mainButtonsController.applyTheme` still drives live in
CR1). (c) `EditNumbersAnimator` extracted as a standalone helper exactly
as Spec 2 §9.2 names it; `MainButtonsController` delegates so the live
behaviour is byte-identical (additive — CR4 re-points the IME call-sites
and deletes the controller).

**RR-1 no-double-wire verdict — PASS (no live behaviour regression).**
- *Long-press:* `ImeViewBackend.attach()` runs *after* the legacy
  `MainButtonsController.registerAllListeners()`, so any
  `setOnLongClickListener` here is the live (most-recent) listener.
  CR1 attaches the long-press listener for **RESEND only** — its
  `ResendLastAudioLong` is behaviour-identical to the legacy
  `onResendLongClicked` (ReprocessStaging entry), and RESEND was already
  the live long-press owner *pre-CR1* (the old code also wired RESEND
  long-press) → **zero regression**. RECORD/BACKSPACE long-press
  listeners are deliberately **not** attached in CR1 (a generic listener
  would overwrite the legacy `onRecordLongClicked` 2-mode handler /
  `onBackspaceLongClicked` and regress the live keyboard). CR4 attaches
  them in the same chunk that removes the legacy `registerAllListeners()`
  drive (RR-1 mitigation: never both wired at once). The catalog
  `longClickResolver` data + reducer arm are complete + unit-tested now;
  only the *listener attachment* for RECORD is the CR4 activation step.
- *Key-press animation:* SPACE/BACKSPACE/ENTER are excluded from
  `applyPressAnimation` — their `OnTouchListener` belongs to the
  `staticHandlerInstaller` (null in CR1, supplied by CR2). The other
  owned buttons get the *same* `KeyPressAnimator` instance the legacy
  controller uses, and `applyPressAnimation` returns `false`
  (click/long-press unaffected) → behaviour-equivalent, no regression.
- *Click:* unchanged from pre-CR1 (already the live listener — not a CR1
  change).
- *Theme:* `applyTheme` added but NOT called by the service in CR1 —
  legacy `mainButtonsController.applyTheme` still drives the live theme
  axis. Dormant.

**Live-keyboard-unchanged confirmation.** No user-visible behaviour
change on the live keyboard: the only newly-attached live listener
(RESEND long-press) is behaviour-identical to the pre-CR1 one; the
new backend surface (RECORD long-press, applyTheme, EditNumbersAnimator
as a separate class) is dormant/delegated. `./gradlew assembleDebug`
green.

#### Plan deviations

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| RECORD long-press *listener* not attached in CR1 (only the catalog resolver + reducer arm + RESEND listener) | render-path-cutover.md §4 row 2 ("add `longClickResolver` model") + §6 RR-1 | Backend is feature-complete (resolver/Action/reducer-arm) but the RECORD `OnLongClickListener` attachment is deferred to CR4 | Attaching it in CR1 would overwrite the live legacy `onRecordLongClicked` and regress the Idle Settings+file-picker launch + autoSwitch+send (RR-1 — the exact F-1/F-2 trap; CR1 AC mandates "NO behaviour change to the live keyboard") | CR4 must widen the `ImeViewBackend` long-press id-filter (currently `id == RESEND`) to all long-press slots **in the same chunk** it removes the legacy `registerAllListeners()` drive + wires the IME-side Idle-launch/autoSwitch for `OnRecordLongPress` | inline-fixed (RR-1-mandated, small + locally decidable, spec §6 RR-1 explicitly prescribes "never both wired at once") |
| `OnRecordLongPress` Idle/Preparing → reducer `null` (no Idle Activity-launch in the reducer) | render-path-cutover.md §7 A1 | The Idle Settings+file-picker launch + `autoSwitchKeyboard` flag are IME-side, not modelled in the pure reducer | No Activity/IME-flag surface on `ModuleServices`; adding one exceeds CR1 additive scope + would be the flagged architecture change A1 warns against. A1 explicitly scopes the resolver/Action/reducer-arm to CR1 and the IME-side body to activation | CR4 wires the IME-side `OnRecordLongPress` consumer (Idle→launch settings+picker, Active/Paused→autoSwitch then the existing stop already done by the reducer arm) | inline-fixed (A1 is the orchestrator-delegated creative call; resolved spec-faithfully as additive) |
| MainButtonsController `animateSmallModeToggle`/`animateEditNumbersBounce` kept as thin delegations (not deleted) | render-path-cutover.md §3 G15 / §7 A2 | Logic extracted to `EditNumbersAnimator`; controller delegates instead of being deleted | CR1 is additive — the legacy IME call-sites still drive these (live). Deleting the controller methods now would break the live keyboard | CR4 re-points `DictateInputMethodService` call-sites (`:2057/:3699/:3730`) to an IME-held `EditNumbersAnimator` and CR-DEL deletes the controller | inline-fixed (small, spec-faithful per §9.x extract-helper pattern) |

#### Issues

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| IMPL-1 | Nice-to-have | RecordingModule `OnRecordLongPress` Active/Paused arms duplicate the `StopRecording` effect-list literals (state/modules/RecordingModule.kt) | open | Deliberate — the module file's established convention is self-contained `when`-arm `TransitionResult` literals (the `StopRecording`/`CancelRecording`/`StopRecordingAndSend` arms all spell out their effects rather than sharing). Introducing a shared helper would deviate from the consistent surrounding style for marginal DRY gain (engineering-principles: don't mass-refactor an established consistent pattern). Left as-is, documented. |

#### Overlooked points / known gaps

- G8 (resend-cooldown `enabledResolver`) and G16 (`MarkLastAudio` Action
  + `isResendVisible` predicate) were found **already implemented** by
  parent B4 — no CR1 catalog/backend work needed. The §9.6 IME resend
  drive-mutation removal (`:1345/:1347/:1669/:1839`,
  `onShowResend → MarkLastAudio` dispatch) is **CR4 scope** per the
  chunk description ("NO IME drive-call removal here"). Not done here by
  design.
- The new `ImeViewBackend.applyTheme` themes only the **owned** logical
  buttons (RECORD/RESEND/BACKSPACE/AUDIO_FOCUS/TRASH/SPACE/PAUSE/ENTER).
  The edit-row buttons (`editSettings`/`editUndo`/… ~11 buttons) the
  legacy `applyTheme` also themed are **not** in `buttonViews` — they
  stay themed by the legacy controller until/unless a later chunk migrates
  them. Spec 2 §9.2 maps only the logical buttons; flagged here so CR4
  (which wires the service `applyTheme` call) knows the edit-row theme is
  still legacy-owned.

### Plan-Correctness Fix (B5-CR1-IMPL-PLAN-FIX)

Re-read render-path-cutover.md §3/§4/§6/§7 + Spec 2 §6/§9.2/§9.6 + the
chunks.json CR1 entry against the diff. All five CR1 deliverables
present and spec-faithful (G2 resolver+Action+arm, G6 applyTheme, G7
keyPressAnimator ctor+wiring, G15 EditNumbersAnimator, F-1/F-2 KDoc
removed). G8/G16 confirmed already-done by parent B4 (not a CR1 gap).
The three plan-deviations above are all small + locally-decidable +
RR-1/A1-mandated (the spec §6 RR-1 + §7 A1 explicitly prescribe the
deferral) → inline-fixed + documented, no delegation. No
architecture-conflict (A1 resolved within the additive
resolver+Action+reducer-arm envelope the chunk prompt authorised).

### Self-Code Fix (B5-CR1-IMPL-CODE-FIX)

Loaded engineering-principles. Code-quality pass:
- Replaced the fully-qualified `net.devemperor.dictate.DictateUtils`
  call in `ImeViewBackend.applyTheme` with a clean top-level import.
- Removed the now-unused `DecelerateInterpolator` import from
  `MainButtonsController.kt` (logic moved to `EditNumbersAnimator`).
- KDoc consistency: the preserved `animateEditNumbersBounce` delegation
  KDoc now points to the helper for the full K6 rationale (SSoT — no
  duplicated rationale).
- RecordingModule effect-list duplication left as IMPL-1 (deliberate,
  matches the file's established self-contained-arm convention).
`./gradlew assembleDebug` green after the fixes.

### Tests (B5-CR1-IMPL-TEST)

**What was done.** Added 20 unit tests for the CR1 production-diff
across 3 existing + 1 new test class, all AC-mapped:

- `state/RecordingModuleTest.kt` (+4) — `OnRecordLongPress` reducer arm:
  Active → Idle + 5 discard-stop effects (no EmitPipelineTrigger / no
  DeleteAudioFile); Paused → Idle + 4 discard-stop effects (no
  StopAmplitudeStream — already stopped on Active→Paused); Idle →
  `null` (A1 — Idle launch is IME-side); Preparing → `null`.
- `state/layout/ActionResolversTest.kt` (+4) —
  `resolveRecordLongPressAction`: Active/Paused → `OnRecordLongPress`;
  Idle → `null`; Preparing → `null`.
- `state/render/ImeViewBackendTest.kt` (+7) — RESEND long-press fires
  `ResendLastAudioLong` via the catalog `longClickResolver`; RECORD has
  **no** backend long-press listener (RR-1); only RESEND wired (RR-1
  per-id sweep); keyPressAnimator wired on non-special + skipped on
  SPACE/BACKSPACE/ENTER (RR-1); press-anim listener returns `false`;
  `applyTheme` legacy accent tiers per owned button; WIDGET_TOGGLE
  untouched. Added handwritten K-1 `RecordingButton` fake (captures
  `setBackgroundColor`/`setOnLongClickListener`/`setOnTouchListener`).
- `core/EditNumbersAnimatorTest.kt` (**new**, +5) — G15 helper:
  instant rotation 180/0 from `isSmallMode`; instant set when animations
  off even if `animate=true`; suppliers read **live**;
  `animateEditNumbersBounce` no-op when disabled. (Async tweened paths
  intentionally not asserted — non-deterministic under Robolectric.)

**Test counts.** `./gradlew testDebugUnitTest`: **1068 tests, 0
failures, 0 errors** (baseline ~1048 + 20; R-7 family clean, no
flakes). `./gradlew assembleDebug` green.

#### Code-Bugs Found While Writing Tests

None. The four initial test failures were **test-harness artifacts**,
not production bugs: (1) `performLongClick()` NPE (Robolectric
context-menu on unparented view) → invoke the captured
`OnLongClickListener` directly via the fake; (2) keyPressAnimator scale
read-back (`view.animate()` async under Robolectric) → assert the
*wiring* (was `setOnTouchListener` called?) via the fake, which is the
backend's actual contract; (3) `MaterialButton.setBackgroundColor`
tint-vs-drawable read-back mismatch → capture the `setBackgroundColor`
argument via the fake. Production calls are correct (identical to the
legacy `applyButtonColor`).

### Test-Review (B5-CR1-IMPL-TEST-FIX)

Requirement coverage complete — every CR1 deliverable has ≥1 direct
assertion; RR-1 (the highest-risk CR1 invariant: RECORD long-press
unwired + special-touch press-anim skipped) is explicitly asserted.
K-1 honoured (handwritten `RecordingButton` fake); K-4 Robolectric is
the justified view-wiring exception (per-test-class KDoc). No code-bugs
surfaced during review. Full suite re-run green (1068/0/0).

---

### Chunk CR2 — special-touch-installer (CursorSwipe/Backspace/Enter)

**Agent-IDs:** `B5-CR2-IMPL` · **Status:** ⏳ pending · **Risk:** HIGH (RR-1 silent-listener-overwrite — the F-1/F-2 trap)

### Chunk CR3 — visibility-controller-attach + KSM-thinning

**Agent-IDs:** `B5-CR3-IMPL` · **Status:** ⏳ pending · **Risk:** HIGHEST (RR-2 blank-UI sequencing; A3)

### Chunk CR4 — IME legacy-driver-removal (render-layer AC-10 analogue)

**Agent-IDs:** `B5-CR4-IMPL` · **Status:** ⏳ pending · **Risk:** HIGHEST (RR-1+RR-2)

### Chunk CR-RGATE — render verification GATE (authorises CR-DEL)

**Agent-IDs:** `B5-CR-RGATE-IMPL` · **Status:** ⏳ pending · **Risk:** Gate
**GATE OUTPUT:** green → CR-DEL proceeds; red → mid-chunk-triage, NO deletion.

### Chunk C10-C3 (CR-DEL) — dead-controller deletion (HARD-GATED on GREEN CR-RGATE)

**Agent-IDs:** `B5-C10-C3-IMPL` · **Status:** ⏳ blocked-on-CR-RGATE · **Risk:** MED

---

## Block-Validate (Phase 3.2)

**Status:** ⏳ · **Pre-Validate Commit:** ⏳ · **Validate-Pass Commit:** ⏳

| Topic | Agent-ID | Status | Output File | Findings |
|-------|----------|--------|-------------|----------|
| plan-and-api | `B5-AUDIT-PLAN-AND-API` | ⏳ | `./reports/audit-plan-and-api-B5.md` | — |
| convention | `B5-AUDIT-CONVENTION` | ⏳ | `./reports/audit-convention-B5.md` | — |
| logic | `B5-AUDIT-LOGIC` | ⏳ | `./reports/audit-logic-B5.md` | — |
| test | `B5-AUDIT-TEST` | ⏳ | `./reports/audit-test-B5.md` | — |

### Sanity-Check Consolidator

**Agent-ID:** `B5-VAL-SANITY` · **Output:** `./reports/validated-findings-B5.md`

### Mini-Triage + Repair-Wave(s)

(Per iteration, max 3 per D5 soft-cap.)

---

## Block Deviation Summary

| # | Plan Location | What changed | Why | Impact | Inline-fixed | Source-Agent | Source-Step |
|---|---------------|--------------|-----|--------|--------------|--------------|--------------|
| — | — | — | — | — | — | — | — |

---

## Block Closeout (Orchestrator)

- **All chunks complete (5-step, both commits):** ⏳
- **CR-RGATE gate GREEN (authorises CR-DEL):** ⏳
- **Block-Validate converged:** ⏳
- **AUDIT-TEST: coverage + no cross-chunk regressions:** ⏳
- **Build green at block-end (Spec 1 §9.6 cleanup-greps pass):** ⏳
- **Issue index reconciled (incl. inherited F-6):** ⏳
- **Cross-block-API consumer info forwarded to B6 (D1/D2 render-assumption restored):** ⏳

**Block completed at:** ⏳
**Block-End-Commit:** ⏳
