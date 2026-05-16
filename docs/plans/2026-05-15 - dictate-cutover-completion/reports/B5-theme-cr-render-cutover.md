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

**Severity counts (post-CR-DEL):** Critical: 0 (CR4-IMPL-1 → fixed-via-CR-EXTRACT) · Important: 0 open (CR4-IMPL-2 fixed/verified · CR4-IMPL-3 → **closed CR-DEL** (edit-row theme retired to EditBar/EmojiController) · **F-6 → closed CR-DEL** (effective-language read collapsed to single `LanguageState.override` carrier)) · Nice-to-have: 2 (CR4-IMPL-4 — spec-mapped, not a defect, CR-RGATE awareness · C10-C3-IMPL-1 — stale ledger-label test strings, not a defect, D7 out-of-scope) · Postponed: 0

**CR-RGATE verdict (2026-05-16, `B5-CR-RGATE-IMPL`): RENDER-GATE: GREEN.** Auto-tier fully green (build + 1130 debug ×2 uncached different-order + 1130 release uncached + new `RenderPathCutoverGateTest` 5/5; full suite 1135/0/0). Every G2-G16 + EditBar/Emoji/OverlayChars/Resend-action fires through its new owner; `doubleWriteCount==0` with the new owners sole `live=true` writers; the sole un-guarded bound-path legacy drive (`mainButtonsController.applyTheme` edit-row theme, CR4-IMPL-3) is provably CR-DEL-scoped (chunks.json AC-RR-7 deliverable; loud compile-error not silent regression). CR4-IMPL-4 = spec-mapped target (not a defect); F-6 = genuinely CR-DEL-scoped. **CR-DEL AUTHORISED** (see `### Chunk CR-RGATE`).

| ID | Source agent | Severity | Status | Title | Source phase |
|----|--------------|----------|--------|-------|--------------|
| F-6 (from B3) | B3-VAL-SANITY | Important | **closed** (CR-DEL) | Cross-carrier collapse: `resolveEffectiveLanguage()` now reads the **single** `LanguageState.override` carrier (not the legacy `ReprocessStaging.selectedLanguage`); the legacy carrier's owner (`KeyboardUiController`) is retired. The `selectedLanguage` field remains as relocated View-side BLEIBT staging-state (Spec 1 §9.2) but is no longer the language-read carrier — the dual-carrier is collapsed. | inherited from B3-VAL-W1 → closed B5-C10-C3-IMPL |
| CR4-IMPL-3 (theme-residual half) | B5-CR4-IMPL (re-run) | Important | **closed** (CR-DEL) | The edit-row `mainButtonsController.applyTheme` residual is retired: `EditBarController.applyTheme` + `EmojiController.applyTheme` own the 12 edit-row/emoji buttons (byte-identical legacy tiers); no `mainButtonsController.applyTheme` remains (AC-RR-6/7). | B5-CR4-IMPL re-run → closed B5-C10-C3-IMPL |
| C10-C3-IMPL-1 | B5-C10-C3-IMPL | Nice-to-have | **open** (not a defect) | Pre-existing audit-ledger test fixtures use `"KeyboardStateManager"`/`"MainButtonsController"` string literals as arbitrary owner-tag labels (cosmetically stale, NOT class refs — no compile dependency, tests green). Renaming = out-of-scope churn (D7). | B5-C10-C3-IMPL Step 1 |
| CR4-IMPL-1 | B5-CR4-IMPL | Critical | **fixed** (via CR-EXTRACT, wave B5-CR4-MID-W1) | `registerAllListeners()` removal (AC-RR-6) strands edit-bar/emoji/overlay-chars — Spec 2 §13.2's `EditBarController`/`EmojiController` never created → **resolved**: CR-EXTRACT chunk inserted before CR4 (chunks.json); 3 owners (`EditBarController`/`EmojiController`/`OverlayCharactersController`) extracted build-but-dormant; CR4 flips per-axis atomically. Live keyboard unchanged (1129/0/0 debug). | B5-CR4-IMPL Step 1 → fixed B5-CR4-MID-REPAIR-1 |
| CR4-IMPL-2 | B5-CR4-IMPL | Important | **fixed/verified** (wave B5-CR4-MID-W1) | G8 resend-cooldown *write-path*: state model verified fully present; the missing `ResendCooldownExpired` postDelayed-dispatch added in `onResendClicked` (additive, idempotent, `pipelineBinder`-guarded) so CR4 can remove `setResendEnabled` without re-opening the double-click race / latching the cooldown | B5-CR4-IMPL Step 1 → fixed B5-CR4-MID-REPAIR-1 |
| CR4-IMPL-3 | B5-CR4-IMPL (re-run) | Important | **fixed** (inline, plan-deviation-resolved) + theme-residual **carried to CR-DEL** | Catalog RESEND `ResendLastAudio`/`Long` → `ResendModule` only arms the cooldown — the resend insertion (DB lookup → insert/resume) + long-press ReprocessStaging-entry have NO new-path impl (same §13.2 "assumed-an-owner" anti-pattern as CR4-IMPL-1, at the RESEND-action layer). Resolved via `ImeViewBackend.imeSideAffordance` firing the exact legacy `onResendClicked()`/`onResendLongClicked()` bodies (the §7-A1 IME-side-activation pattern the orchestrator already accepted for RECORD). The theme edit-row residual is the same root-cause cluster → CR-DEL fully retires `mainButtonsController` (AC-RR-6/7 zero-grep). | B5-CR4-IMPL re-run Step 1 → fixed inline (D22) |
| CR4-IMPL-4 | B5-CR4-IMPL (re-run) | Nice-to-have | **open** (not a defect — CR-RGATE awareness) | `KeyboardInputModule` BACKSPACE/ENTER effects simpler than legacy `deleteOneCharacter()`/`performEnterAction()`; SPACE click+touch both commit a space. This is the **spec-mapped target** (Spec 2 §3.3/§13.2/§6, reviewed Phase-C) — NOT a CR4 regression. Flagged for CR-RGATE holistic-parity awareness only. | B5-CR4-IMPL re-run Step 1 |

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

**Agent-IDs:** `B5-CR2-IMPL` (fresh, combined Steps 1-5).
**Status:** ⏳ in_progress · **Risk:** HIGH (RR-1 silent-listener-overwrite — the F-1/F-2 trap)
**Implementation-Commit (Commit 1):** ⏳ · **Test-Commit (Commit 2):** ⏳

### Implementation (B5-CR2-IMPL)

**What was done.** Supplied the real `staticHandlerInstaller` lambda
(was `null` in CR1 at `DictateInputMethodService.java:1136`, now
`:~1207`). Concretely:

- `state/render/SpecialTouchHandlerInstaller.kt` — **new** class. Builds
  the three Spec 2 §11.7 touch handlers **verbatim**:
  `buildSpaceTouchHandler` (CursorSwipe: onTap/onCursorMove/
  onSwipeStateChanged + compound-drawable arrow swap + keyPressAnimator
  composition + null-IC short-circuit clearing the drawables),
  `buildBackspaceSwipeHandler` (BackspaceSwipeHandler with
  `onDeleteCancelled` wired to the real IME `onBackspaceDeleteCancelled`
  — the G3 accel-delete-cascade-cancel half F-1 dropped),
  `buildEnterOverlayHandler` (EnterOverlayHandler reading
  `overlay_characters_ll` off the ENTER view's rootView, accent +
  keyPressAnimator). Exposes `installDormant` (CR2 — build+cache only),
  `attachToViews` (CR4 — gated attach), `ownerOf` (single-owner proof
  surface), and a `Log.wtf` single-owner guard.
- `res/values/ids.xml` — added `special_touch_owner_tag` keyed-tag id
  (follows the established `slot_renderer_*` keyed-tag convention) for
  the single-owner ledger marker.
- `core/DictateInputMethodService.java` — instantiates
  `specialTouchHandlerInstaller` (wired to `getCurrentInputConnection`,
  `Pref.AccentColor`, `vibrate()`, `onBackspaceDeleteCancelled()`, the
  shared `qwertzKeyboardView.getKeyPressAnimator()`) and passes
  `installer::installDormant` as the `ImeViewBackend`
  `staticHandlerInstaller` (replacing the CR1 `null`). New field +
  import added; CR1 ctor-comment updated.

**RR-1 single-owner model — PASS (build-but-don't-attach; no live
behaviour regression).** This is the load-bearing decision of the
chunk:

- *The trap.* The legacy `MainButtonsController.registerAllListeners()`
  (`DictateInputMethodService.java:827`) is the **LIVE** owner of the
  SPACE/BACKSPACE/ENTER `setOnTouchListener` and is NOT removed in CR2
  (that is CR4). `attachBackend()` → `ImeViewBackend.attach()` →
  `staticHandlerInstaller` runs at `:~1247`, i.e. **after** line 827.
  Android keeps only the most-recent `setOnXListener`. A naive
  `space.setOnTouchListener(...)` in the installer would **silently
  overwrite** the live legacy `CursorSwipeTouchHandler` /
  `BackspaceSwipeHandler` / `EnterOverlayHandler` — a half-broken
  keyboard with no error (the exact F-1/F-2 trap, RR-1).
- *The model chosen — build-but-don't-attach* (one of the spec's three
  evaluated options, render-path-cutover.md §6 RR-1; the one identical
  to CR1's already-accepted RESEND-only long-press model). The IME
  wires `installer::installDormant`, which only **builds + caches** the
  three handlers; it does **NOT** call `setOnTouchListener` on the live
  Views. CR4 calls `installer.attachToViews(...)` in the **same chunk**
  it removes the legacy `registerAllListeners()` touch wiring — never
  both wired at once.
- *Single-owner proof (pre/post-CR2).*
  - **Pre-CR2 & post-CR2:** SPACE/BACKSPACE/ENTER `setOnTouchListener`
    sole live owner = the **legacy `MainButtonsController`**
    (`:203-208` / `:217-246` / `:268-273`). CR2 attaches **zero**
    touch listeners to these Views (`installDormant` only builds +
    `setTag`s the single-owner marker). Proven by: (1) code — no
    `setOnTouchListener` call path in `installDormant`/its builders on
    the live Views; (2) the `ownerOf(id)` ledger reads `dormant-cr2`
    (built, NOT attached) after CR2; (3) the `Log.wtf` guard +
    asserted-in-test single-owner invariant.
  - **What CR4 will flip:** CR4 calls `installer.attachToViews(...)`
    (ledger transitions `dormant-cr2` → `attached-cr4`) in the same
    chunk it deletes the legacy `MainButtonsController` touch wiring.
    Sole live owner then = the new §11.7 handlers. Never both at once.
- *No architecture-conflict.* CR2 and CR4 separate cleanly — the exact
  same staged-safety-net pattern the orchestrator already accepted for
  CR1's long-press axis (RESEND-only attach; RECORD built-but-dormant).
  CR2 applies it to the touch axis. **Not flagged
  `architecture-conflict`.** mid-chunk-triage NOT needed.

**§11.7 builder fidelity.** All three builder bodies follow Spec 2
§11.7 verbatim (compared line-by-line against §11.7 + the legacy
`MainButtonsController.kt:203-273` for behaviour parity). The shared
`KeyPressAnimator` is the **same instance** the legacy controller + CR1
backend use → press-animation byte-identical.

**Live-keyboard-unchanged confirmation.** No user-visible behaviour
change: CR2 attaches no touch listener to any live View; the legacy
`MainButtonsController` remains the sole live owner of SPACE swipe /
BACKSPACE accel-delete+swipe / ENTER overlay. The new handlers are
built-but-dormant. `./gradlew assembleDebug` green.

#### Plan deviations

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| `staticHandlerInstaller` builds-but-does-not-attach in CR2 (handlers cached, not `setOnTouchListener`'d on live Views) | render-path-cutover.md §4 row 3 ("supply the real installer lambda") + §6 RR-1 | Installer is feature-complete (3 §11.7 handlers built) but attachment deferred to CR4 | Attaching in CR2 would silently overwrite the live legacy `CursorSwipe/Backspace/EnterOverlay` handlers (RR-1, the F-1/F-2 trap); CR2 AC mandates "NO live keyboard behaviour change" | CR4 must call `installer.attachToViews(buttonViews)` **in the same chunk** it removes the legacy `MainButtonsController.registerAllListeners()` touch wiring (`:203-208`/`:217-246`/`:268-273`) — never both wired at once | inline-fixed (RR-1-mandated; small + locally-decidable; spec §6 RR-1 explicitly prescribes "never both wired at once"; identical to CR1's accepted RESEND-only model) |
| `buildBackspaceSwipeHandler().onDeleteCancelled` wired to the **real** IME `onBackspaceDeleteCancelled()` (not the §11.7-snippet's `{ /* no-op */ }` comment) | Spec 2 §11.7 BackspaceSwipeHandler snippet vs render-path-cutover.md §3 **G3** + CR2 mandate | The §11.7 code-snippet shows `onDeleteCancelled = { /* … no Action-Emit needed */ }`; G3 + the CR2 task mandate + legacy `MainButtonsController.kt:206` parity require the real cancel so the accel-delete cascade is interruptible by a swipe | A pure no-op would regress: a swipe-select would NOT stop a running `deleteHandler` accel-delete cascade (exactly the behaviour F-1 lost). G3 is explicit: "restores exactly what parent B4-VAL F-1 dropped" | CR4: the IME-side `onBackspaceLongClicked` accel-delete *trigger* (long-press path) is attached by CR4 in the same chunk per CR1's KDoc; this handler's *cancel* wire is complete now | inline-fixed (small + locally-decidable; G3 + legacy-parity + CR2-mandate resolve the §11.7-snippet-comment vs G3-behaviour tension in favour of behaviour parity, the SoT's overriding intent) |

#### Issues

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| IMPL-1 | Nice-to-have | Java→Kotlin variance friction: `installDormant(Map<LogicalButtonId, View>)` requires an unchecked cast in the IME lambda (Kotlin emits the param as `Map<LogicalButtonId, ? extends View>` due to `Map`'s declaration-site `out V`) (`DictateInputMethodService.java:~1209`) | open | Unavoidable Kotlin/Java generics boundary friction (the same pattern would recur for any Kotlin `(Map<K,V>) -> Unit` consumed from Java). Cast is `@SuppressWarnings("unchecked")`-annotated + safe (the map is constructed locally as `Map<LogicalButtonId, View>` two screens up). A signature change to `Map<LogicalButtonId, out View>` would not help (same erasure). Left as-is, documented. |

#### Overlooked points / known gaps

- The `EnterOverlayHandler` reads `overlay_characters_ll` via
  `enter.rootView.findViewById(...)` (matching the legacy controller
  which is passed the same `overlayCharactersLl`). This is dormant in
  CR2 (handler built, not attached) so the rootView resolution is only
  exercised once CR4 attaches — verified the legacy controller resolves
  the identical id from the same inflated tree, so CR4 attach is safe.
- G12 `OverlayResetHandler` (the defensive overlay-reset belt) is **CR3
  scope** (attach existing controller) — not touched here. §11.7's
  "Special" note (handler-internal `overlayCharactersLl` reset stays as
  defensive depth) is preserved verbatim in `EnterOverlayHandler` (not
  modified by CR2).
- CR4 contract surfaced for the next chunk: CR4 must (a) call
  `installer.attachToViews(buttonViews)`, (b) remove the legacy
  `MainButtonsController` SPACE/BACKSPACE/ENTER `setOnTouchListener`
  wiring, (c) widen `ImeViewBackend`'s long-press id-filter (CR1
  contract) — **all in the same chunk** (RR-1: never both wired at
  once).

### Plan-Correctness Fix (B5-CR2-IMPL-PLAN-FIX)

Re-read render-path-cutover.md §3 (G3/G4/G5) / §4 row 3 / §6 RR-1 / §7,
Spec 2 §11.7 (verbatim builder bodies) + §9.2 rows
`:189-194`/`:203-232`/`:254-259`, and the legacy
`MainButtonsController.kt:203-273` against the diff. All three §11.7
builders present and verbatim-faithful (CursorSwipe full body incl.
keyPressAnimator + null-IC short-circuit; BackspaceSwipe incl. the G3
cancel-cascade wire; EnterOverlay). The two plan-deviations above are
small + locally-decidable + RR-1/G3-mandated (the spec §6 RR-1
explicitly prescribes "never both wired at once"; G3 + the CR2 mandate
explicitly require the accel-delete-cascade-cancel restored) →
inline-fixed + documented, no delegation. **No architecture-conflict**
— the build-but-don't-attach model is the orchestrator-accepted CR1
pattern reapplied to the touch axis; CR2/CR4 separate cleanly. No
mid-chunk-triage.

### Self-Code Fix (B5-CR2-IMPL-CODE-FIX)

Loaded engineering-principles. Code-quality pass:
- Refactored `attachToViews` from three repeated
  `(view to handler).let { (v,h) -> … }` blocks into a single
  `attachOne(...)` private helper (DRY; readability;
  maintainability — CR4 touches this path).
- Single-owner marker uses a real keyed-tag resource
  (`R.id.special_touch_owner_tag`) following the established
  `SlotRenderer` `R.id.slot_renderer_*` convention (not a magic int
  tag) — collision-safe, consistent with the codebase pattern.
- KDoc captures the full RR-1 "why" (wiring-order diagram + the
  build-but-don't-attach rationale + the CR2→CR4 ledger transition) so
  the next reader does not have to reverse-engineer the trap.
- IMPL-1 (Java variance cast) left documented — unavoidable boundary
  friction, `@SuppressWarnings`-annotated + safe.
`./gradlew assembleDebug` green after the fixes.

### Tests (B5-CR2-IMPL-TEST)

**What was done.** Added `state/render/SpecialTouchHandlerInstallerTest.kt`
(**new**, Robolectric — K-4 justified view-wiring exception, per-class
KDoc), 12 tests, all AC-mapped:

- **RR-1 single-owner invariant (load-bearing):**
  `installDormant_attaches_no_touch_listener_to_live_views` —
  `ShadowView.getOnTouchListener()` is `null` for SPACE/BACKSPACE/ENTER
  after `installDormant` (the legacy controller stays sole live owner);
  `installDormant_ledger_reads_dormant_cr2_for_all_three`;
  `ownerOf_is_null_before_installDormant`.
- **3 §11.7 handlers built:**
  `installDormant_builds_all_three_handlers_distinct` (non-null +
  distinct G3/G4/G5); `handlers_are_null_before_installDormant`.
- **CR4 flip:** `attachToViews_is_what_actually_attaches_the_cached_handlers`
  (`assertSame` the cached handler is now the live listener);
  `attachToViews_transitions_ledger_dormant_to_attached`.
- **Double-build guard:**
  `second_installDormant_still_attaches_no_live_listener` (no overwrite
  in CR2 even on a double-build).
- **§11.7 SPACE body verbatim:**
  `space_handler_onTap_commits_a_space_via_inputconnection`;
  `space_handler_clears_drawables_and_short_circuits_when_no_inputconnection`.
- **G3 (the half F-1 dropped):**
  `backspace_handler_swipe_select_fires_onBackspaceDeleteCancelled` —
  proves the swipe-cancel is wired to the REAL IME cancel (deviation
  #2), so a running accel-delete cascade is interruptible.
- Handwritten K-1 `FakeInputConnection` (captures `commitText`; no
  mocking framework).

**Test counts.** `./gradlew testDebugUnitTest`: **1079 tests, 0
failures, 0 errors** (CR1 baseline ~1068 + 12 CR2; R-7 family clean, no
flakes). `./gradlew assembleDebug` green.

#### Code-Bugs Found While Writing Tests

None. The production code was correct as written; all 12 tests passed
without any production change. (The G3-cancel-wire test was added in
Step 5 self-review to directly assert deviation #2's behaviour parity —
it passed immediately, confirming the wire is correct.)

### Test-Review (B5-CR2-IMPL-TEST-FIX)

Requirement coverage complete — every CR2 acceptance point has ≥1
direct assertion: the real installer + 3 §11.7 handlers, the
load-bearing RR-1 single-owner-per-View invariant (no live listener
overwrite — the F-1/F-2 trap, asserted via `ShadowView`), CR4 flip
semantics, double-build safety, §11.7 SPACE body verbatim, and the G3
accel-delete-cascade-cancel wire (deviation #2). Added the G3 test in
this step (was the one coverage gap — the load-bearing deviation #2
deserved a direct behaviour assertion). K-1 honoured (handwritten
`FakeInputConnection`); K-4 Robolectric is the justified view-wiring
exception (per-class KDoc). No code-bugs surfaced during review. Full
suite re-run green (1079/0/0).

### Chunk CR3 — visibility-controller-attach + KSM-thinning

**Agent-IDs:** `B5-CR3-IMPL` (fresh, combined Steps 1-5).
**Status:** ⏳ in_progress · **Risk:** HIGHEST (RR-2 blank-UI sequencing; A3 split-vs-delete)
**Implementation-Commit (Commit 1):** ⏳ · **Test-Commit (Commit 2):** ⏳

### Implementation (B5-CR3-IMPL)

**What was done.** Attached the three dormant R.10 visibility
controllers via `KeyboardLayoutManager.attachBackend` and made the
no-double-write invariant *provable*, all ADDITIVE (no legacy drive
removed — that is CR4). Concretely:

- `core/audit/VisibilityWriteAuditLogger.kt` — **new** (Spec 2 §10 /
  §11.8 5c — the concrete Strict-Mode logger the spec mandates as its
  own class). `BuildConfig.DEBUG`-guarded. API: `beginRenderGeneration()`
  (per state-emit fan-out boundary), `logWrite(viewId, caller, target,
  live)`, plus test-observable `doubleWriteCount` / `soleLiveWriterOf` /
  `dormantReportersOf`. A *second distinct **live** writer* on the same
  `viewId` in one generation = double-write (`Log.wtf` + counter). A
  *dormant* report (suppressed intended write) is recorded separately —
  it provably cannot conflict with the one live writer (RR-2).
- `state/render/RenderGate.kt` — **new**. The dormant↔armed
  staged-safety-net switch (the visibility-axis analogue of CR2's
  `SpecialTouchHandlerInstaller.installDormant`/`attachToViews`).
  `shouldWrite(viewId, target)` reports to the ledger (`live = armed`)
  and returns `armed`; CR3 constructs it dormant → controller never
  touches the view. `arm()` is the CR4 one-line flip.
- `state/render/ContentAreaController.kt` / `PromptVisibilityController.kt`
  / `OverlayResetHandler.kt` — added an optional `gate: RenderGate?`
  ctor param (default `null` = legacy always-write, keeps every
  existing unit test byte-identical). Every visibility write routes
  through a private `writeVisibility(view, target)` helper:
  `gate == null` → write; gate dormant → ledger-report only (no view
  mutation); gate armed (CR4) → write.
- `state/layout/KeyboardLayoutManager.kt` — optional
  `visibilityAuditLogger` ctor param (placed **before** `onAction` so
  the trailing-lambda construction idiom is preserved). `onStateChanged`
  calls `beginRenderGeneration()` per state-emit (the single fan-out =
  one render generation, RR-2 ledger keying).
- `core/KeyboardStateManager.kt` — every KSM visibility write now routes
  through a single `writeVisibility(view?, target)` seam that reports
  `"KeyboardStateManager"` to the ledger with `live = true` **then
  performs the write unconditionally** (KSM IS the sole live writer
  until CR4 — Spec 2 §13 rows 1-4/7-11 `BLEIBT`). `auditLogger` is
  wired *post-construction* via `attachAuditLogger(...)` (KSM is built
  in the IME-View inflate path before the service-bind may complete —
  the established bind↔inflate race the IME already consolidates).
- `core/DictatePipelineService.kt` — owns a single
  `VisibilityWriteAuditLogger` instance (shared by the manager, KSM,
  and all 3 gates so every writer reports to ONE ledger); passed to the
  manager ctor; exposed via the LocalBinder (`visibilityWriteAuditLogger`).
- `core/DictateInputMethodService.java` — new
  `attachDormantVisibilityControllers()` (called from the existing
  `attachImeViewBackendIfReady` consolidation point, so it runs on both
  `onCreateInputView` and `onServiceConnected`, race-safe): wires the
  shared ledger into KSM, builds the 3 controllers each behind a
  **dormant** `RenderGate`, attaches them via `attachBackend`
  (`backendType=null` — ambiguity A4, parent-B4 design reused). New
  `detachDormantVisibilityControllers()` called symmetrically in
  `cleanupOldControllers()` (view-recreate) + `onDestroy()` (tear-down),
  exactly like the `imeViewBackend` lifecycle.

**RR-2 — the no-double-write sequencing model (the load-bearing
decision; PASS — live keyboard UNCHANGED).** A visibility write, unlike
a touch listener, is NOT an "Android keeps the most-recent" overwrite —
it is a *repeated write* to the same field. Attaching a controller that
writes the axis while KSM still drives it = both mutate the container
every render-tick (silent flicker / wrong container, no error). The
mitigation is the exact CR1/CR2 staged-safety-net applied to the
visibility axis: the controllers **attach** (wiring proven,
view-recreate-safe, CR4 = one-line `arm()`) but are **gated dormant** —
they receive every `render()` tick and report their *intended* write to
the ledger, but do **not** touch the view.

*Sole-live-writer table (the no-double-write proof):*

| Visibility axis (views) | Pre-CR3 sole LIVE writer | **Post-CR3 sole LIVE writer** | What CR4 flips |
|---|---|---|---|
| ContentArea (`main_buttons_cl`/`qwertz_keyboard_container`/`emoji_picker_cl`/`edit_buttons_keyboard_ll`) | KSM `applyContentAreaVisibility` | **still KSM** (`ContentAreaController` attached **dormant**) | CR4 removes the IME `stateManager.setContentArea/refresh` drive **and** `contentAreaGate.arm()` in the same chunk |
| Recording-controls + Prompts (`pause_btn`/`trash_btn`/`prompts_*_cl`/`prompts_*_rv`/`pipeline_progress_ll`/`prompt_recording_controls_ll`) | KSM `applyRecordingControlsVisibility` + `applyPromptsVisibility` | **still KSM** (`PromptVisibilityController` attached **dormant**) | CR4 removes the KSM prompts drive **and** `promptVisibilityGate.arm()` together |
| Overlay-reset (`overlay_characters_ll` → GONE) | KSM `applyVisibility` line ~142 | **still KSM** (`OverlayResetHandler` attached **dormant**) | CR4 removes the KSM reset line **and** `overlayResetGate.arm()` together |

*Proof mechanism:* the dormant controllers report under their own
owner-tag with `live = false` → recorded in `dormantReporters`
(observability: "this owner exists and WOULD write, but is suppressed").
KSM reports `live = true`. The ledger's double-write detector only
fires on **two distinct LIVE writers** in one generation → through CR3
exactly one live writer per axis (`KeyboardStateManager`),
`doubleWriteCount == 0` (Spec 2 §10 acceptance). CR4 will remove the KSM
drive and `arm()` the gates in the **same** chunk: the live writer
flips KSM → controller with zero overlap (the exact `dormant-cr2 →
attached-cr4` ledger transition CR2 established for the touch axis).
Removing a KSM drive before the gate is armed = blank UI; arming a gate
before the KSM drive is removed = double-write — CR4 must do both
together per axis (never out of order). **Not flagged
architecture-conflict** — this is the orchestrator-accepted CR1/CR2
staged pattern reapplied to the visibility axis; CR3/CR4 separate
cleanly.

**A3 decision (split-vs-delete — the architecture call) — option-a
(extract), staged.** Spec 2 §9.4/§9.5/§13-row-20 mark
`RecordingUiController` QWERTZ/amplitude/timer (G9) and
`KeyboardUiController` step-rows (G13) `BLEIBT`; the CR-DEL kill-list +
AC-RR-7 assume full deletion. Per D4 (long-term, fewest special-cases)
+ the spec's own §9.x extract-helper pattern (it already names
`EditNumbersAnimator`, `RecordingAnimationController` as extracted
helpers — CR1 did exactly this), **A3 is decided option-a: extract the
BLEIBT parts so the kill-list classes fully delete and AC-RR-7 stays a
clean zero-grep.** This is the binding disposition recorded here.
*Staging:* the physical extraction + the IME `recordingUiController.*`
/ `uiController.*` amplitude/timer/pipeline drive-collapse onto
`ImeViewBackend.onAmplitude/onTimerTick` (already exist, `:217/:224`) +
`dispatch(Action.PipelineAction.*)` is **CR4 (drive removal) + CR-DEL
(class deletion)** work, NOT CR3 — per render-path-cutover.md §5 RR-2
("CR3 attaches + proves all owners *before* CR4 removes any drive
call") and the chunks.json CR-DEL entry which explicitly says A3 may be
resolved at the CR3 **or** CR-DEL boundary and that the BLEIBT-extract
is option-a. CR3 attaching its visibility owners is what *makes* the
G9/G13 collapse safe to do in CR4/CR-DEL. **No architecture change
beyond extract/collapse is required** → not flagged
architecture-conflict; mid-chunk-triage NOT needed.

**F-6 (inherited from B3) disposition — CARRIED FORWARD to CR-DEL.**
F-6 (cross-carrier collapse `PipelineUiState.ReprocessStaging.selectedLanguage`
→ `LanguageState.override`) depends on `KeyboardUiController` /
`PipelineUiStateReader` **retirement**. CR3 attaches visibility owners
and thins KSM; it does **not** retire `KeyboardUiController` (that is
CR-DEL, gated on CR-RGATE). The F-6 collapse is therefore NOT cleanly
doable inside CR3's visibility-attach/KSM-thin scope (it touches the
transcription-config language read, a different subsystem). Carried
forward to CR-DEL with this note; must be closed before B5 block-end
(tracked in the Issue Index, status unchanged `open → CR3/CR-DEL owns`
— CR-DEL now owns it).

**Live-keyboard-unchanged confirmation.** No user-visible behaviour
change: the 3 controllers attach **dormant** (zero visibility writes —
the legacy KSM remains the sole live writer of every migrated axis); a
`null` gate/logger leaves every existing controller + KSM path
byte-identical (proven — all pre-existing `ContentAreaControllerTest` /
`PromptVisibilityControllerTest` / `OverlayResetHandlerTest` /
`KeyboardLayoutManagerTest` green unchanged). `./gradlew assembleDebug`
green.

#### Plan deviations

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| Controllers attach **dormant** (gated, no visibility write) in CR3; the IME `stateManager.setContentArea/refresh` drive is NOT removed | chunks.json CR3 ("collapse stateManager…drive onto state") vs render-path-cutover.md §5 ("CR3 ATTACHES; CR4 REMOVES the legacy drive") + §6 RR-2 | The chunk text "collapses onto state" is the **CR4** action; CR3's job per the §5 ordering rule + RR-2 is to make the new owner present-and-proven so CR4's removal is safe. The chunks.json notes confirm: "ADDITIVE+collapse — legacy classes still instantiated, **drive-calls being removed is CR4**" | CR4 must remove each KSM drive site AND `arm()` the matching gate **in the same chunk, per axis** (never out of order — RR-2: removed-drive-before-arm = blank UI; armed-before-removed = double-write). Drive sites: `:1264/1267/1279/1280` (onFinishInputView), `:~2693` (primePipelineUiForNewPath), `:~2175-2242` (emoji/qwertz toggles), `:~3768-3845` (small-mode/single-row/audio-focus refresh) | inline-fixed (RR-2-mandated; the spec §5 ordering rule + §6 RR-2 explicitly resolve the chunks.json "collapse" wording vs the staged-safety-net in favour of dormant-attach; identical to CR1/CR2's accepted models) |
| KSM bodies kept (not emptied) in CR3 | Spec 2 §11.8 5c ("5c hat leere KSM-Bodies (no-op)") | KSM keeps its `applyXxxVisibility` bodies + still writes the axes; CR3 adds the Strict-Mode instrumentation that makes 5c's no-double-write *verifiable* | Spec 2 §11.8-5c's "empty bodies" assumes a *parallel live owner* takes over; render-path-cutover.md §5/§6 RR-2 explicitly override this ("removing the drive calls before CR3 blanks the UI"; "Parent B4 already chose 'KSM thinned to its still-owned axes' over 'empty bridge'") — emptying the bodies while the controllers are dormant = blank UI. The §13 `BLEIBT` rows 1-4/7-11 keep these axes in KSM until the deletion chunk | CR-DEL (= 5d) deletes KSM entirely (incl. these bodies) once CR4 has armed the controllers + CR-RGATE is GREEN | inline-fixed (small + locally-decidable; render-path-cutover.md §5 is the SoT that resolves the §11.8-5c-"empty" vs RR-2-"blank UI" tension; the audit logger is the spec's own §11.8-5c verification mechanism) |
| A3 decided **option-a (extract)** but the G9/G13 physical extraction + IME amplitude/timer/pipeline drive-collapse staged to CR4/CR-DEL (not done in CR3) | render-path-cutover.md §7 A3 / chunks.json CR3 (mentions G9/G13 collapse) + CR-DEL ("Resolve ambiguity A3 here if not at CR3") | A3 is *decided* here (option-a binding); the extraction itself is CR4-drive-removal + CR-DEL-class-deletion work | RR-2: CR3 must attach+prove visibility owners BEFORE any drive collapse (§5). The G9/G13 `recordingUiController.*`/`uiController.*` drive is a *recording/pipeline* axis, not the *visibility* axis CR3 owns; collapsing it in CR3 would be the same blank-UI risk applied to a different axis. chunks.json explicitly permits A3 resolution at the CR3 **or** CR-DEL boundary | CR4 collapses the IME `recordingUiController.onAmplitudeUpdate/onTimerTick`/`uiController.startPipeline/...` drive onto `ImeViewBackend.onAmplitude/onTimerTick` + `dispatch(Action.PipelineAction.*)`; CR-DEL extracts the QWERTZ/step-row BLEIBT parts into small owners so AC-RR-7 zero-greps clean | inline-fixed (A3 is the orchestrator-delegated creative call; decided spec-faithfully option-a per D4 + the §9.x extract-helper precedent; staging is RR-2-mandated, no architecture-conflict) |

#### Issues

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| IMPL-1 | Nice-to-have | The `writeVisibility(view, target)` gate-routing helper is structurally repeated across the 3 controllers (`ContentAreaController`/`PromptVisibilityController`/`OverlayResetHandler`) | open | Deliberate — each controller is an independent SRP `RenderBackend`; the duplication is ~6 lines of trivial branching and the shared abstraction already exists (`RenderGate`). A common base class would couple 3 concern-pure backends for marginal DRY gain (engineering-principles: no premature abstraction; don't mass-refactor an established consistent per-controller `render` style). Documented, left as-is. |

#### Overlooked points / known gaps

- The `ContentAreaController` owns `main_buttons_cl`/`qwertz`/`emoji`
  but **not** `edit_buttons_keyboard_ll` (KSM's `applyContentAreaVisibility`
  also toggles `editButtonsLl`). Spec 2 §13 row 2 marks `editButtonsLl`
  `BLEIBT` (ContentArea-axis) — the new `ContentAreaViews` holder has no
  edit-buttons field. Flagged so CR4/CR-DEL knows the `editButtonsLl`
  visibility is still legacy-owned and either needs a 4th `ContentAreaViews`
  field or stays a documented KSM-residual when the class is split.
- The audit logger is `BuildConfig.DEBUG`-guarded — in release builds
  `logWrite`/`beginRenderGeneration` early-return (zero cost, no proof).
  The CR-RGATE render-verification gate must run the no-double-write
  assertion against a **debug** build (Spec 2 §10 acceptance is a
  debug-soak criterion — `0 Logs after 60s over all 5 LayoutModes`).
- F-6 carried forward to CR-DEL (see disposition above) — NOT closed
  in CR3 by design (depends on KeyboardUiController/PipelineUiStateReader
  retirement = CR-DEL scope).

### Plan-Correctness Fix (B5-CR3-IMPL-PLAN-FIX)

Re-read render-path-cutover.md §3 (G9-G13) / §5 (CR3 ordering rule +
KSM-thinning-in-CR3) / §6 RR-2 / §7 A3/A4, Spec 2 §9.3/§9.4/§9.5/§11.8-5c/§13
(BLEIBT rows 1-20 + §10 Strict-Mode acceptance), and the chunks.json
CR3 + CR-DEL entries against the diff. All three CR3 deliverables
present + spec-faithful: (1) the 3 R.10 controllers attached via
`attachBackend` as `backendType=null` (A4 — parent-B4 design reused,
not reinvented); (2) KSM kept its BLEIBT axes (§13) + the Strict-Mode
`VisibilityWriteAuditLogger` instruments every visibility write so
§11.8-5c's no-double-write is *verifiable* (the spec's own 5c
mechanism); (3) the IME direct `stateManager.setContentArea/refresh`
drive is NOT removed (CR4 per §5 — removing it in CR3 = blank UI, RR-2).
A3 decided option-a (extract — binding), staged to CR4/CR-DEL per the
§5 ordering rule + chunks.json's explicit "CR3 or CR-DEL boundary"
latitude. F-6 carried to CR-DEL (depends on KUC/PUSR retirement, not
CR3 scope). The three plan-deviations above are all small +
locally-decidable + RR-2/A3-mandated (the spec §5 ordering rule + §6
RR-2 explicitly prescribe "CR3 attaches; CR4 removes"; chunks.json
notes confirm "drive-calls being removed is CR4") → inline-fixed +
documented, no delegation. **No architecture-conflict** — the
dormant-attach is the orchestrator-accepted CR1/CR2 staged pattern at
the visibility axis; CR3/CR4 separate cleanly. mid-chunk-triage NOT
needed.

### Self-Code Fix (B5-CR3-IMPL-CODE-FIX)

Loaded engineering-principles. Code-quality + own-logic-bug pass:
- **Own logic bug fixed inline (Step-3 self-check):** the first
  `VisibilityWriteAuditLogger` cut keyed double-writes on *any* second
  caller, so a dormant controller reporting the same `viewId` as KSM's
  live write in one generation would have **spuriously tripped
  `doubleWriteCount` and inverted the entire RR-2 proof**. Fixed by
  adding a `live: Boolean` to `logWrite`: dormant gates report
  `live = false` → recorded in a separate `dormantReporters` map
  (observability only, never a conflict); only **two distinct LIVE
  writers** in one generation trip the detector. `RenderGate.shouldWrite`
  passes `live = armed`; KSM passes `live = true`. This makes the proof
  correct: through CR3 exactly one live writer per axis
  (`KeyboardStateManager`), `doubleWriteCount == 0`; CR4 flips `live`
  KSM → controller.
- `KeyboardLayoutManager` ctor param ordering: `visibilityAuditLogger`
  placed **before** `onAction` so the codebase's trailing-lambda
  construction idiom (`KeyboardLayoutManager(catalog) { … }`) keeps
  compiling unchanged (the alternative — appending it — broke
  `KeyboardLayoutManagerTest`; caught + fixed in PLAN-FIX).
- KSM `auditLogger` wired post-construction (`attachAuditLogger`)
  rather than via ctor: KSM is built in the IME-View inflate path
  before the service-bind may complete; a ctor param would force a
  null at construction and a fragile re-build. The post-ctor setter
  matches the established bind↔inflate consolidation pattern
  (`attachImeViewBackendIfReady`).
- IMPL-1 (per-controller `writeVisibility` duplication) left documented
  — `RenderGate` is the shared abstraction; a common base would couple
  3 SRP backends for marginal gain (engineering-principles: no
  premature abstraction).
`./gradlew assembleDebug` + the 4 pre-existing affected test classes
green after the fixes.

### Tests (B5-CR3-IMPL-TEST)

**What was done.** Added 20 unit tests for the CR3 production-diff
across 2 new + 4 extended test classes, all AC-mapped:

- `core/audit/VisibilityWriteAuditLoggerTest.kt` (**new**, +8, pure
  JVM — K-4 no-Robolectric justified, the logger keys on `Int`+`String`
  only): single live writer ≠ double-write (idempotent re-render);
  **two distinct LIVE writers in one generation = double-write**;
  **dormant report does NOT conflict with the KSM live write (the RR-2
  core invariant)**; CR4 flip (armed controller becomes sole live
  writer, KSM gone); generation-boundary resets the ledger; distinct
  axes tracked independently; fresh-logger empty state. `BuildConfig.DEBUG`
  `assumeTrue` (the logger is debug-guarded by design — the proof is
  exercised under `testDebugUnitTest`).
- `state/render/RenderGateTest.kt` (**new**, +5, pure JVM): dormant
  default → `shouldWrite=false`; `arm()` → `shouldWrite=true` (CR4
  flip); **dormant gate reports a SUPPRESSED (non-live) write — no
  conflict vs the KSM live write (RR-2 proof half)**; armed gate
  reports a LIVE write (sole live writer flips to the controller);
  null-logger semantics still hold (no crash, proof unobserved).
- `ContentAreaControllerTest` (+3): **CR3 dormant gate → render
  mutates NO container** (all 3 left exactly as found — the
  no-double-write proof at the controller level); **CR4 armed gate →
  render drives the containers** (the flip); null gate → legacy
  always-write unchanged.
- `PromptVisibilityControllerTest` (+2): dormant → no prompt-view
  mutation; armed → drives the prompt views.
- `OverlayResetHandlerTest` (+2): dormant → strip NOT reset (KSM still
  owns it); armed → strip forced GONE (the flip).
- `KeyboardLayoutManagerTest` (+1): **`onStateChanged` opens one audit
  render-generation per state-emit** + a fresh emit resets the
  per-generation ledger (proves a gen-1 owner does not bleed into
  gen-2 as a false double-write — RR-2 generation-keying).

**Test counts.** `./gradlew testDebugUnitTest`: **1099 tests, 0
failures, 0 errors, 0 skipped** (CR2 baseline ~1079 + 20 CR3; R-7
family clean, no flakes). `./gradlew assembleDebug` green.

#### Code-Bugs Found While Writing Tests

None. The Step-3 self-check already fixed the only logic bug (the
`live` flag — see Self-Code Fix). All 20 new tests passed on first run
after that fix; the pre-existing controller / manager tests stayed
green unchanged (the `null` gate/logger default proves the
backward-compatible pass-through).

### Test-Review (B5-CR3-IMPL-TEST-FIX)

Requirement coverage complete — every CR3 acceptance point has ≥1
direct assertion: the 3 R.10 controllers attach as `backendType=null`
(pre-existing `backendType is null` tests, unchanged); the
load-bearing **RR-2 no-double-write invariant** (dormant report ≠
conflict with the KSM live write) asserted at three levels — the
logger core (`VisibilityWriteAuditLoggerTest`), the gate
(`RenderGateTest`), and each controller (dormant → zero view
mutation); the **CR4 flip** (armed → sole live writer transitions KSM →
controller, zero overlap) asserted at all three levels; the
per-state-emit **render-generation boundary** (`KeyboardLayoutManagerTest`).
The KSM `writeVisibility` seam has no dedicated test — it is a pure
pass-through (audit-report-then-unconditional-write) whose dependency
(`VisibilityWriteAuditLogger`) is exhaustively tested, and whose
"sole live writer" end-to-end behaviour is asserted in the
manager/gate integration tests; a direct KSM test would need heavy
view-holder fakes for zero additional logic coverage (documented
overlooked-point, not a gap). K-1 honoured (no mocking framework — the
logger/gate tests are pure JVM with real objects; the controller tests
reuse the existing handwritten Robolectric `FrameLayout` fixtures);
K-4 Robolectric used only where an Android `View` is genuinely needed
(the controller tests — inherited per-class justification), pure JUnit
for the logger/gate (explicit K-4 opt-out KDoc). No code-bugs surfaced
during review. Full suite re-run green (1099/0/0).

### Chunk CR4 — IME legacy-driver-removal (render-layer AC-10 analogue)

**Agent-IDs:** `B5-CR4-IMPL` (original — flagged CR4-IMPL-1; record preserved below) → re-run after CR-EXTRACT (see **`### Chunk CR4 — IME legacy-driver removal (RE-RUN, post-CR-EXTRACT)`** further down for the completed flip).
**Status:** ✅ flipped (re-run) — CR4-IMPL-1 resolved via the inserted **CR-EXTRACT** chunk; the per-axis atomic flip is implemented (bound = new owner, unbound = legacy fallback; 1130/0/0 debug). The original CR4-IMPL-1 finding record (below) is preserved unchanged. · **Risk:** HIGHEST (RR-1+RR-2)
**Implementation-Commit (Commit 1):** ⏳ (CR4 runs after CR-EXTRACT) · **Test-Commit (Commit 2):** ⏳

### Implementation (B5-CR4-IMPL)

**What was done.** A full read of the CR4 surface (the entire 3995-line
`DictateInputMethodService.java`, the CR1-CR3 staged owners, Spec 2
§9.2/§9.6/§11.7/§11.8/§13.2, Spec 1 §9.2/§9.6, render-path-cutover.md
§2.2/§5/§6.1, the chunks.json CR4 entry) was performed to plan the
per-axis atomic flip. **No production code was changed** — a Critical
`architecture-conflict` was found that blocks the chunk's central
deliverable (AC-RR-6 "remove `mainButtonsController.*`") and **cannot be
resolved by an IMPL inline fix** (D7/D22 — architecture conflict →
delegate Critical, not fix). Per the D4 directive in the chunk prompt
("If any axis cannot be flipped atomically within CR4, flag Critical
`architecture-conflict` rather than ship a gap/overlap"), the chunk is
flagged rather than shipped with a silent blank-UI gap.

**The architecture-conflict (CR4-IMPL-1) — `registerAllListeners()` has
three sub-axes with NO new owner.** AC-RR-6 + the CR4 chunk description
literally mandate removing `mainButtonsController.registerAllListeners()`
(IME `:856`). But `MainButtonsController.registerAllListeners()`
(`MainButtonsController.kt:106-111`) fans out to **four** private
sub-registrations, only **one** of which has a proven new owner:

| Sub-registration | What it wires | New-path owner | Flip-safe in CR4? |
|---|---|---|---|
| `registerMainButtonListeners()` (`:169`) | the 9 logical buttons (RECORD/RESEND/BACKSPACE/AUDIO_FOCUS/TRASH/SPACE/PAUSE/ENTER click+long-press+touch) | `ImeViewBackend.wireStaticHandlers` (CR1, dormant RECORD/BACKSPACE long-press) + `SpecialTouchHandlerInstaller` (CR2, dormant touch) | **YES** — CR1/CR2 explicitly staged these for a one-line CR4 flip |
| `registerEditBarListeners()` (`:115`) | edit-bar: `editSettings`/`editUndo`/`editRedo`/`editCut`/`editCopy`/`editPaste`/`editEmoji`/`editNumbers`(+long)/`editKeyboard`(+long)/`editHistory`/`pipelineCancel`/`editAudioFocus` click | **NONE — does not exist** | **NO** |
| `registerEmojiListeners()` | emoji-picker: `editEmoji`/`emojiPickerClose`/`emojiPickerView` | **NONE — does not exist** | **NO** |
| `initializeOverlayCharacters()` | overlay-characters strip init | **NONE — does not exist** (legacy `updateOverlayCharacters` still drives via IME `:2225`) | **NO** |

Spec 2 **§13.2** (the Click-Listener-Audit, the SoT for this exact
migration) explicitly states the edit-bar **"BLEIBT in einem separaten
`EditBarController`, der sich nicht ändert"** and the emoji listeners
**"BLEIBT in EmojiController"**. **Neither `EditBarController` nor
`EmojiController` exists in the codebase** (`find app/src/main -name
'*EditBar*' -o -name '*EmojiController*'` → empty; the edit-bar/emoji
listeners live *inside* `MainButtonsController.registerEditBarListeners`/
`registerEmojiListeners` today — the very class CR-DEL kills). This is
the **exact INT-1 / F-1 / F-2 parallel-dormant deferral anti-pattern at
the edit-bar/emoji layer** — structurally identical to the
B4-VAL-F-1/F-2/F-33 render-layer deferral that birthed Theme C-R itself
(render-path-cutover.md §1). The spec assumed an extracted owner the
parent plan never created.

**Why this is `architecture-conflict`, not an inline-fixable
plan-deviation (D7/D22).** Removing `registerAllListeners()` strands the
**entire edit-bar** (Settings/Undo/Redo/Cut/Copy/Paste/Emoji/Numbers/
Keyboard/History + emoji-picker-close + pipeline-cancel + edit-audio-
focus) **and** the emoji-picker **and** the overlay-characters init with
**no error** — a dead edit-row, the precise RR-2 silent-blank-UI
regression AC-RR-6's own parity requirement (AC-RR-1..5) forbids. The
spec-faithful resolution is **known** (Spec 2 §13.2 + the binding A3
disposition recorded in CR3 = option-a: *extract* the BLEIBT parts into
small new owners — `EditBarController`/`EmojiController`/an
overlay-chars owner — so the kill-list class fully deletes and AC-RR-7
stays a clean zero-grep). But that is a **multi-file new-class
extraction (~3-4 new Kotlin owners, 13+ edit-bar listeners + emoji + a
500 ms-cooldown `setResendEnabled` axis G8-residual + `updateRecordButtonText`
+ `updateOverlayCharacters`), well outside CR4's authorised scope**
(chunks.json CR4: `files_estimate: 1` = `DictateInputMethodService.java`
*only*, `loc_estimate: 250`, "Files: DictateInputMethodService.java
only. Classes stay instantiated"). It is the same A3/RR-5-class
per-chunk **orchestrator architecture decision** (RR-5: option-a extract
vs option-b narrow AC-RR-7) that CR3 recorded for G9/G13 — here it
recurs for the edit-bar/emoji/overlay-chars axes the §3 table never
enumerated as behaviour groups (they are outside the 16 G-groups; §13.2
silently assumed a pre-existing `EditBarController`). An IMPL agent
inventing 3-4 new owner classes + their full listener bodies + their
LayoutCatalog/dispatch wiring under a "remove drive calls" chunk would
be exactly the unbounded-scope-creep D7 forbids and the finder-fixer
bias D22 routes away from.

**Per-axis flip plan (the deliverable for the triage/repair agent).**
The flip *is* feasible per axis once the missing owners exist — the
reactive path (`orchestrator.state.collect → KeyboardLayoutManager.onStateChanged
→ backend.render`, `DictatePipelineService.kt:601/621`) is **already
live** and already carries the authoritative `DictateUiState` to the
attached CR1-CR3 backends (proven by Theme B's C5 recording-drive
cutover, `ImeRecordingDriveCutoverTest`). `DictateUiState.pipeline` is
the *same* `PipelineUiState` type the legacy `uiController` caches, so
every `uiController.*` read re-points to
`pipelineBinder.getState().getValue().getPipeline()` and every mutator
to `dispatch(Action.PipelineAction.*)` (all variants exist —
`StartReprocessStaging`/`UpdateReprocessQueue`/`UpdateReprocessLanguage`/
`CancelReprocessStaging`/`StepStarted`/… verified in `Action.kt:238-298`).
The full per-axis table:

| Axis | Legacy drive-site(s) to remove | New owner to arm/attach | Same-commit atomic? | Parity-test |
|---|---|---|---|---|
| **Visibility — ContentArea** | `stateManager.setContentArea/refresh` (IME `:1395/1398/1410/1411/2314/2322/2328/2364/2373/2381/2832/3944/3983` + `onFinishInputView`) | `contentAreaGate.arm()` (CR3, one-line) — `ContentAreaController` already attached+reactive | YES (remove KSM drive **and** `arm()` together, RR-2) | `ContentAreaControllerTest` armed-flip (CR3 added) + new IME-attach Robolectric assert |
| **Visibility — Prompts/recording-controls** | `stateManager.refresh` (prompts path) | `promptVisibilityGate.arm()` (CR3) | YES (same chunk) | `PromptVisibilityControllerTest` armed (CR3) |
| **Visibility — Overlay-reset** | KSM `applyVisibility` overlay-reset line | `overlayResetGate.arm()` (CR3) | YES (same chunk) | `OverlayResetHandlerTest` armed (CR3) |
| **Touch — SPACE/BACKSPACE/ENTER** | `mainButtonsController.registerMainButtonListeners` touch wiring (via `registerAllListeners` — see conflict) | `specialTouchHandlerInstaller.attachToViews(buttonViews)` (CR2, dormant→attached-cr4) | YES *iff* `registerMainButtonListeners` removable independently of the 3 NO-owner sub-axes (= the conflict) | `SpecialTouchHandlerInstallerTest` attach-flip (CR2) |
| **Long-press — RECORD/BACKSPACE** | `MainButtonsController` `onRecordLongClicked`/`onBackspaceLongClicked` legacy listeners (via `registerAllListeners`) + IME-side wire of the `OnRecordLongPress` consumer (Idle→Settings+file-picker `:3483-3493`, Active/Paused→`autoSwitchKeyboard=true`+stop) | widen `ImeViewBackend` long-press id-filter `id == RESEND` → all long-press slots (CR1 contract) + IME-side `OnRecordLongPress` consumer (CR1 deferred this here) | YES *iff* the conflict resolved (same `registerAllListeners` removal) | `ImeViewBackendTest` RECORD long-press + new IME `onRecordLongClicked` parity |
| **Key-press animation** | `mainButtonsController.initializeKeyPressAnimations()` (IME `:857`) | `ImeViewBackend.wireStaticHandlers` `keyPressAnimator.applyPressAnimation` (CR1, already wired on attach — just remove the legacy call) | YES (pure removal — CR1 owner already live) | `ImeViewBackendTest` keyPressAnimator wiring (CR1) |
| **Theming** | `mainButtonsController.applyTheme(accentColor)` (IME `:2241`) | `imeViewBackend.applyTheme(accentColor)` service-call after re-inflate (CR1 added the method) | YES (swap the call target; edit-row theme residual — see Overlooked) | `ImeViewBackendTest.applyTheme` tiers (CR1) + new IME service-call assert |
| **Audio-focus icon** | `mainButtonsController.refreshAudioFocusIcon` (IME `:862/:959/:970/:3970`) | AUDIO_FOCUS slot `iconResolver` (parent B4, state-driven via reactive render) — remove the imperative call; the resolver already drives | YES (pure removal — resolver already live on the reactive path) | `LayoutCatalog`/`SlotRenderer` AUDIO_FOCUS iconResolver (parent B4) |
| **EditNumbers animation** | `mainButtonsController.animateSmallModeToggle/animateEditNumbersBounce` (IME `:2268/:3910/:3941`) | IME-held `EditNumbersAnimator` (CR1 extracted the helper; `MainButtonsController` currently thin-delegates) — re-point IME call-sites to a service-held `EditNumbersAnimator` instance | YES (re-point 3 call-sites to the CR1 helper) | `EditNumbersAnimatorTest` (CR1) + new IME call-site assert |
| **resend_btn visibility (§9.6)** | IME `resendButton.setVisibility` `:2209/:2823/:3086` + `onShowResend()` `:3069-3088` | `predResendVisible` predicate (state-driven) + `dispatch(Action.ResendAction.MarkLastAudio(exists=true))` for `onShowResend` (Spec 2 §9.6 exact replacement; `ResendAction.MarkLastAudio` defined Spec 2 §3.3, verified in `Action.kt`) | YES (delete 3 setVisibility, swap `onShowResend` body to dispatch) — predicate already drives RESEND via reactive render | `ResendModuleTest` MarkLastAudio + RESEND predicate (parent B4) |
| **`setResendEnabled` 500 ms cooldown (G8-residual)** | `mainButtonsController.setResendEnabled(false/true)` (IME `:3511/:3541`) | G8 `enabledResolver` + a `state.resend.resendCooldown` state field (Spec 2 §9.2 G8 / §13) — **CR1 found G8 "already implemented by parent B4"; verify the cooldown *write-path* (the IME `onResendClicked` 500 ms toggle) has a dispatch equivalent** | NEEDS-VERIFY (likely a small `ResendAction` cooldown dispatch; flag if absent) | `ResendModuleTest` cooldown |
| **`updateRecordButtonText`** | `mainButtonsController.updateRecordButtonText(getDictateButtonText())` (IME `:1925`) | RECORD slot `textResolver` (Spec 2 §9.5, parent B4 — state-driven) — pure removal | YES (pure removal — resolver already live) | RECORD textResolver (parent B4) |
| **`updateOverlayCharacters`** | `mainButtonsController.updateOverlayCharacters` (IME `:2225`) | **NONE — Spec 2 §9.2 says `:481-493` "bleibt — overlay-spezifisch"; no extracted owner exists** (part of the conflict — tied to `initializeOverlayCharacters` in `registerAllListeners`) | NO (no new owner — part of CR4-IMPL-1) | — |
| **Pipeline drive — `uiController.startPipeline/addRunningStep/completeStep/failStep/stopPipeline/preparePipeline/enterReprocessStaging`** | IME `:1684/1704/1710/1965/2081/2822/2838/2996/3017/3027/3037/3126/3721/3805/3807/3882` + `servicePipelineCallback` fan-out `:975-1028` | `dispatch(Action.PipelineAction.*)` — the orchestrator's `state.pipeline` is **already authoritative** (code comments confirm: "the orchestrator owns the authoritative `state.pipeline`; this is the same thin UI bookkeeping the legacy trigger performed" `:2815-2818`). The step-row *rendering* is **G13 BLEIBT** (A3 option-a → extract `PipelineStepRowRenderer`) | PARTIAL — the *dispatch* re-point is atomic+safe; the step-row **render BLEIBT** needs the A3 extract (CR-DEL-staged per CR3's binding A3 disposition; `KeyboardUiController` stays instantiated until CR-DEL) | `PipelineModuleTest` + `ImeRecordingDriveCutoverTest`-pattern IME flip test |
| **Pipeline reads — `uiController.getState/isPipelineRunning/isBusy/isPipelineActive/getAutoEnterConfig/getLatestPipelineElapsedMs`** | IME `:720/723/726/815/817/907/1020/1524/1530/1732/1868/2829/2992/3016/3026/3036/3136/3144/3462/3468/3652/3720` | re-point each to `pipelineBinder.getState().getValue().getPipeline()` (same `PipelineUiState` type) + trivial derivations; rotation-survival bridges (`restoreReprocessStaging`/`restoreAutoEnter` `:1524-1535/1682-1714`) read the same authoritative state | YES (mechanical re-point — same type, authoritative SoT) | targeted IME read-path tests |
| **Recording-UI drive — `recordingUiController.onStateChanged/onAmplitudeUpdate/onTimerTick`** | IME `rewireCallbacks` `:1603/1610/1615`, `restoreUiState` `:1671` | `recordingStateController` already dispatches to the orchestrator (Theme B C5); amplitude/timer → `imeViewBackend.onAmplitude/onTimerTick` (CR1, methods exist `:217/:224`); state → reactive render. **QWERTZ rec-button + amplitude/timer = G9 BLEIBT** (A3 option-a → extract `QwertzRecordingController`) | PARTIAL — the `onStateChanged` removal is atomic (reactive render owns it); the QWERTZ/amplitude/timer **BLEIBT** is the A3 extract (CR-DEL-staged) | `RecordingAnimationControllerTest` + IME flip test |

**Conclusion / D4 verdict.** ~13 of the ~16 axes are atomically
flippable *today* (CR1-CR3 staged them precisely for this); the **3
NO-owner sub-axes inside `registerAllListeners()` (edit-bar / emoji /
overlay-chars-init) make the AC-RR-6-mandated
`mainButtonsController.registerAllListeners()` removal non-atomic — a
silent blank edit-row/emoji/overlay gap**. That blocks AC-RR-6's central
deliverable and the RR-1/RR-2 no-gap invariant. The two SPLIT axes (G9
QWERTZ, G13 step-rows) are *already* CR-DEL-staged per CR3's binding A3
option-a disposition (not a CR4 blocker — CR4 removes the *drive*, the
*render* BLEIBT until CR-DEL extracts the small owners). Per D4 +
D7/D22, **flagged Critical `architecture-conflict` for mid-chunk-triage**
rather than shipped as a gap. The resolution direction is the binding A3
option-a (extract `EditBarController` + `EmojiController` +
overlay-chars owner, Spec 2 §13.2-faithful) but the **scope decision
(create these owners under CR4, or split a new CR-EXTRACT chunk before
CR4, or option-b narrow AC-RR-7) is an orchestrator routing call**, not
an IMPL inline fix.

#### Plan deviations

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| No production change shipped in CR4 (chunk flagged, not implemented) | render-path-cutover.md §2.2 AC-RR-6 / §5 CR4 / chunks.json CR4 | CR4's central deliverable (`mainButtonsController.registerAllListeners()` removal → single render driver) is blocked by a missing new owner for 3 sub-axes (edit-bar/emoji/overlay-chars) | Removing `registerAllListeners()` strands the edit-bar/emoji/overlay-chars with no error (RR-2 silent blank-UI) — the chunk's own AC-RR-1..5 parity requirement forbids this; the spec-prescribed `EditBarController`/`EmojiController` (§13.2) were never created (recurring INT-1/F-1/F-2 deferral anti-pattern) | CR-RGATE cannot run (no single-render-driver state to verify); CR-DEL stays blocked. Triage must decide the extraction scope before CR4 can complete | delegated-to-orchestrator (architecture-conflict — not IMPL-inline-fixable per D7/D22; mid-chunk-triage armed for CR4 per the block plan) |

#### Issues

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| CR4-IMPL-1 | Critical | `mainButtonsController.registerAllListeners()` fans out to 4 private sub-registrations; 3 (`registerEditBarListeners`/`registerEmojiListeners`/`initializeOverlayCharacters`) had **NO new-path owner** — Spec 2 §13.2's `EditBarController`/`EmojiController` never created. `MainButtonsController.kt:106-111` | **fixed** (B5-CR4-MID-REPAIR-1, wave B5-CR4-MID-W1) | Resolved via the inserted **CR-EXTRACT** chunk: `EditBarController`/`EmojiController`/`OverlayCharactersController` extracted build-but-dormant (Spec 2 §13.2-faithful, A3 option-a binding). CR4 can now remove `registerAllListeners()` and flip every axis per-axis-atomically (`attachToViews()`/`arm()` same-chunk). Live keyboard unchanged (legacy still sole live owner; 1129/0/0 debug). See §"Mid-Chunk-Triage Wave B5-CR4-MID-W1". |
| CR4-IMPL-2 | Important | G8 resend-cooldown *write-path*: the IME's `onResendClicked` 500 ms cooldown drove only via imperative `setResendEnabled` (`:3511/:3539`); no `ResendCooldownExpired` dispatch existed (Spec 2 §9.2 G8 / §13.5.c Gap 2) | **fixed/verified** (B5-CR4-MID-REPAIR-1, wave B5-CR4-MID-W1) | State model verified fully present (`ResendState.resendCooldown` + `ResendModule` arm/clear + RESEND `enabledResolver`/`alphaResolver`); the missing `ResendCooldownExpired` postDelayed-dispatch added in `onResendClicked` (additive, idempotent, `pipelineBinder`-guarded) so CR4 can remove `setResendEnabled` without re-opening the double-click race or latching the cooldown. |

#### Overlooked points / known gaps

- **A3 (G9/G13) is NOT a CR4 blocker** — CR3 already recorded the
  binding A3 option-a disposition (extract `QwertzRecordingController` /
  `PipelineStepRowRenderer`) as **CR-DEL-staged** (`KeyboardUiController`/
  `RecordingUiController` stay instantiated through CR4; CR4 removes only
  the *drive*, the *render* BLEIBT until CR-DEL extracts the small
  owners). The CR4-IMPL-1 conflict is a *different* class of problem: the
  edit-bar/emoji/overlay-chars sub-axes of `registerAllListeners()` were
  **never enumerated as G-groups in render-path-cutover.md §3** (they are
  outside the 16 behaviour groups) — Spec 2 §13.2 silently assumed a
  pre-existing `EditBarController`/`EmojiController`. The CR-DEL kill-list
  (AC-RR-7 `grep -rl "MainButtonsController"` → zero) is **also**
  impossible until these owners exist (same root cause; flag carries to
  CR-DEL/RR-5 as well).
- The CR1 block-report already flagged a related residual: *"The
  edit-row buttons (editSettings/editUndo/… ~11 buttons) the legacy
  `applyTheme` also themed are not in `buttonViews` … still
  legacy-owned."* That theme-residual and this listener-residual are the
  **same missing-`EditBarController` root cause** — they should be
  resolved together by the triage's extraction decision.
- F-6 (inherited from B3, cross-carrier `ReprocessStaging.selectedLanguage
  → LanguageState.override`) remains carried-to-CR-DEL per CR3's
  disposition — untouched here (CR4 did not reach the
  `KeyboardUiController`/`PipelineUiStateReader` retirement that F-6
  depends on; the block was halted at the CR4-IMPL-1 conflict before any
  drive-removal).
- No `### Plan-Correctness Fix` / `### Self-Code Fix` / `### Tests` /
  `### Test-Review` subsections: Steps 2-5 are **not applicable** — Step
  1 produced no production diff (the chunk is blocked at a Critical
  architecture-conflict that must be triaged before any code lands).
  There is nothing to plan-correctness-check, code-fix, or test.

**Files modified in this step:** none (analysis only — block-report
subsection filled). **Files in plan-prescribed scope:** none touched.
**Files outside plan-prescribed scope (drift):** none.

### Mid-Chunk-Triage Wave B5-CR4-MID-W1

**Agent-IDs:** `B5-CR4-MID-RES-1` (research) → `B5-CR4-MID-REPAIR-1`
(repair, CR-EXTRACT impl) → `-VERIFY` (self-check). **Wave:**
`B5-CR4-MID-W1`, iter 1 (iter-cap 2). **Date:** 2026-05-16.
**Triggered by:** CR4-IMPL-1 (Critical `architecture-conflict`) +
CR4-IMPL-2 (Important needs-verify ride-along).

**What was done.** Researched the §13.2 SoT, wrote the per-sub-
registration → new-owner contract into
`research/render-path-cutover.md` §11 (D20 append-only), amended
`dictate-cutover-completion.chunks.json` (inserted **CR-EXTRACT**
before CR4 — sequence CR1→CR2→CR3→**CR-EXTRACT**→CR4→CR-RGATE→CR-DEL),
then **implemented CR-EXTRACT**: extracted the three §13.2-prescribed-
but-never-created owner classes **build-but-dormant** and verified +
closed the CR4-IMPL-2 resend-cooldown gap.

**§13.2 owner contracts (SoT, verbatim where given).**

| Sub-registration | New owner (Spec 2 §13.2) | Listener inventory ported (byte-equivalent) |
|---|---|---|
| `registerEditBarListeners()` (`MainButtonsController.kt:115-165`) | **`EditBarController`** (§13.2 "bleibt in einem separaten `EditBarController`, der sich nicht ändert" — verbatim) | editNumbers click+long, editSettings, editHistory, pipelineCancel, editAudioFocus (shared listener), editKeyboard click+long, undo/redo/cut/copy/paste (×5, android.R.id.* ids) — 13 listeners, same callbacks/vibrate/`return true` semantics |
| `registerEmojiListeners()` (`:278-295`) | **`EmojiController`** (§13.2 "Emoji-Listener … BLEIBT in EmojiController" — verbatim) | editEmoji click, emojiPickerClose click, emojiPickerView picked (null-guarded `commitText`) |
| `initializeOverlayCharacters()` (`:299-313`) + `updateOverlayCharacters` (`:451-463`) | **`OverlayCharactersController`** (proposed spec-faithful name — §13.1 row 13 / §9.2 `:481-493` say only "separate Animations-/Theme-Klasse", no concrete name; sibling-naming to `ContentAreaController`) | one-time 8-view inflate + per-call visibility/text/accent-tint update |

All 3 owners spec-faithful: 2 verbatim from §13.2 (`EditBarController`/
`EmojiController`), 1 proposed-spec-faithful (`OverlayCharactersController`
— §13/§9.2 name no class; the sibling-convention name + the
controller-grade dual responsibility (init + update) justify it; kept
distinct from `OverlayResetHandler` per §13.1's two separate rows 11
vs. 13). Every listener traced from the live `MainButtonsController`
source and ported byte-equivalent (callback parity contract: the new
`EditBarController.Callback`/`EmojiController.Callback` are strict ISP
subsets of `MainButtonsController.Callback`, satisfied by the IME's
existing method bodies — zero behaviour duplication).

**Build-but-dormant proof (RR-1/RR-2 — live keyboard CONFIRMED
unchanged).**

- `EditBarController`/`EmojiController` reuse the **CR2
  `SpecialTouchHandlerInstaller`** model: `installDormant()` only
  **builds + caches** the listener lambdas + tags a shared keyed-tag
  single-owner ledger marker (`R.id.editbar_emoji_owner_tag`,
  `dormant-cr-extract`); it does **NOT** call
  `setOnClickListener`/`setOnLongClickListener` on the live Views. The
  legacy `MainButtonsController.registerEditBarListeners`/
  `registerEmojiListeners` (still wired via `registerAllListeners()`,
  removed only by CR4) stay the **sole live owner**. CR4 calls
  `attachToViews()` *in the same chunk* it removes the legacy wiring
  (ledger transitions `dormant-cr-extract → attached-cr4`) — never both
  wired at once. Proven by test: `ShadowView.onClickListener` is
  `null` for every edit-bar/emoji View after `installDormant`; ledger
  reads `dormant-cr-extract`; `attachToViews` `assertSame`s the cached
  instance.
- `OverlayCharactersController` reuses the **CR3 `RenderGate`** model
  (write axis, not listener-overwrite): constructed dormant,
  `initialize()` does **not** inflate (legacy stays the sole live
  inflater — inflating here too would double the child count, the
  structural RR-2 analogue) and `update()` does **not** mutate; both
  report the *intended* write to the shared
  `VisibilityWriteAuditLogger` (`live=false`). `doubleWriteCount == 0`
  proven (test asserts the dormant report does not conflict with a
  legacy `MainButtonsController` live write in the same generation).
  CR4 `arm()`s the gate in the same chunk it removes the legacy drive.
- IME wiring: `attachDormantEditBarEmojiOwners()` (new) runs at the
  same race-safe consolidation point as `attachImeViewBackendIfReady`
  / `attachDormantVisibilityControllers` (both `onCreateInputView` +
  `onServiceConnected`); `detachDormantEditBarEmojiOwners()` (new)
  symmetric in `cleanupOldControllers()` (view-recreate) + `onDestroy()`.
  **No legacy sub-registration removed (CR4); `MainButtonsController`
  not deleted (CR-DEL).**

**CR4-IMPL-2 disposition — fixed (gap was real).** Verified the
`state.resend.resendCooldown` *state model* is fully present
(`ResendState.resendCooldown`, `ResendModule` arming on
`ResendLastAudio`/`Long` + clearing on `ResendCooldownExpired`, RESEND
slot `enabledResolver = { !resendCooldown }` + `alphaResolver` in
`LayoutCatalog`). But the IME's `onResendClicked` cooldown *write-path*
was purely imperative (`setResendEnabled(false)` `:3511` +
`postDelayed setResendEnabled(true)` `:3539`) with **no
`dispatch(ResendCooldownExpired)`** anywhere (`ResendModule`'s own
KDoc: "the Phase-1 placeholder relies on the UI side scheduling that
action via `Handler.postDelayed`" — never wired). If CR4 removed
`setResendEnabled` relying on the state path, the cooldown would latch
`true` forever (RESEND permanently disabled). **Fixed:** added the
missing `mainHandler.postDelayed(() -> pipelineBinder.dispatch(
Action.ResendAction.ResendCooldownExpired.INSTANCE), 500)` next to the
imperative path (additive, `pipelineBinder`-guarded, idempotent — a
no-op while `resendCooldown==false`). The imperative `setResendEnabled`
remains the live UI effect until CR4 removes it; the arming half
(`ResendLastAudio` → catalog `actionResolver`) is dormant until CR4
flips the RESEND click. CR4 can now remove `setResendEnabled` without
re-opening the double-click race or latching the cooldown.

#### Plan deviations

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| `OverlayCharactersController` name proposed (not given by §13/§9.2) | Spec 2 §13.1 row 13 / §9.2 `:481-493` ("separate Animations-/Theme-Klasse" — no concrete name) | Named the overlay-chars owner `OverlayCharactersController` (sibling-convention to `ContentAreaController`) | The SoT names `EditBarController`/`EmojiController` verbatim but only describes the overlay-chars owner; the sibling-naming + controller-grade dual responsibility (init+update) is the spec-faithful fill; kept distinct from `OverlayResetHandler` (§13.1 rows 11≠13) | CR4 arms `overlayCharactersGate` + re-points the IME `updateOverlayCharacters` drive to the new owner; CR-DEL deletes the `MainButtonsController` overlay-chars methods | inline-fixed (small + locally-decidable; D22 — §13.2 verbatim where given, spec-faithful where the SoT only describes) |
| `OverlayCharactersController` is not a `RenderBackend` (imperatively driven, like the legacy `updateOverlayCharacters`) | research/render-path-cutover.md §11.2 | Modelled it as an imperatively-invoked owner (init/update) gated by `RenderGate`, not a `attachBackend` reactive backend | The legacy `updateOverlayCharacters` is called imperatively from `onStartInputView`/setup (not per state-tick); a reactive `RenderBackend` would change the drive cadence (overlay-chars content is set on input-view-start, not every render). The `RenderGate` still gives the identical dormant/`arm()` + ledger proof | CR4 re-points the imperative IME call-site + `arm()`s the gate same-chunk | inline-fixed (small + locally-decidable; preserves legacy drive cadence — byte-equivalent behaviour) |

#### Issues

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| CR4-IMPL-1 | Critical | (see Issue Index) `registerAllListeners()` 3 NO-owner sub-axes | **fixed** | Resolved via CR-EXTRACT: `EditBarController`/`EmojiController`/`OverlayCharactersController` extracted build-but-dormant (Spec 2 §13.2-faithful, A3 option-a). CR4 can now remove `registerAllListeners()` and flip all axes atomically (per-axis `attachToViews()`/`arm()` same-chunk). Live keyboard unchanged (legacy still sole live owner; 1129/0/0 debug). |
| CR4-IMPL-2 | Important | (see Issue Index) G8 resend-cooldown write-path | **fixed/verified** | State model verified present; the missing `ResendCooldownExpired` dispatch added in `onResendClicked` (additive, idempotent, `pipelineBinder`-guarded). CR4 can remove `setResendEnabled` without re-opening the race or latching the cooldown. |

#### Inline-fixed items (production)

- `state/render/EditBarController.kt` (**new**) — owns
  `registerEditBarListeners` inventory build-but-dormant.
- `state/render/EmojiController.kt` (**new**) — owns
  `registerEmojiListeners` inventory build-but-dormant.
- `state/render/OverlayCharactersController.kt` (**new**) — owns
  `initializeOverlayCharacters` + `updateOverlayCharacters`,
  `RenderGate`-gated dormant.
- `res/values/ids.xml` — added `editbar_emoji_owner_tag` keyed-tag id.
- `core/DictateInputMethodService.java` — implements
  `EditBarController.Callback` + `EmojiController.Callback` (no new
  method bodies — ISP subsets of the existing
  `MainButtonsController.Callback`); new fields + imports;
  `attachDormantEditBarEmojiOwners()` / `detachDormantEditBarEmojiOwners()`
  (called at the existing consolidation + view-recreate/onDestroy
  points); CR4-IMPL-2 `ResendCooldownExpired` postDelayed-dispatch in
  `onResendClicked`.

**Files modified — DISJOINT for the wave-commit:**

- **Production:** `app/src/main/java/net/devemperor/dictate/state/render/EditBarController.kt`,
  `app/src/main/java/net/devemperor/dictate/state/render/EmojiController.kt`,
  `app/src/main/java/net/devemperor/dictate/state/render/OverlayCharactersController.kt`,
  `app/src/main/res/values/ids.xml`,
  `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`
- **Test:** `app/src/test/java/net/devemperor/dictate/state/render/EditBarControllerTest.kt`,
  `app/src/test/java/net/devemperor/dictate/state/render/EmojiControllerTest.kt`,
  `app/src/test/java/net/devemperor/dictate/state/render/OverlayCharactersControllerTest.kt`
- **Plan/docs (this wave):**
  `docs/plans/2026-05-15 - dictate-cutover-completion/research/render-path-cutover.md` (§11 D20 append),
  `docs/plans/2026-05-15 - dictate-cutover-completion/dictate-cutover-completion.chunks.json` (CR-EXTRACT insert),
  `docs/plans/2026-05-15 - dictate-cutover-completion/reports/B5-theme-cr-render-cutover.md` (this subsection + Issue Index)

**Self-check (B5-CR4-MID-REPAIR-1-VERIFY).** `./gradlew assembleDebug`
green; `./gradlew testDebugUnitTest` **1129 tests, 0 failures, 0
errors** (baseline ~1099 + 30 new: EditBar 12 / Emoji 12 / OverlayChars
6). One isolated `testReleaseUnitTest` failure
(`PipelineRunnerSubsystemAdapterTest` — "blocking runner did not
start", a pre-existing R-7-class thread-start concurrency flake under
parallel load; **passes in isolation**; zero overlap with CR-EXTRACT's
render-layer scope — not a regression). Build-but-dormant CONFIRMED:
no `setOnClickListener`/inflate on any live View until CR4 flip; the
legacy `MainButtonsController` sub-registrations remain the sole live
owner; `doubleWriteCount==0` for the overlay-chars axis. Each owner's
ported listener set + the dormant single-owner invariant + the
CR4-IMPL-2 cooldown dispatch path are unit-tested. Convergence: **✓
converged** — CR4-IMPL-1 closed via CR-EXTRACT, CR4-IMPL-2
fixed/verified, no new issues forwarded.

#### Overlooked points / known gaps

- CR4 must, *in the same chunk* it removes
  `mainButtonsController.registerAllListeners()`: (a)
  `editBarController.attachToViews()`, (b)
  `emojiController.attachToViews()`, (c)
  `overlayCharactersGate.arm()` + re-point the imperative
  `updateOverlayCharacters` IME call-site to
  `overlayCharactersController.update(...)` — never both wired at once
  (RR-1/RR-2). The CR1 theme-residual ("the edit-row buttons the
  legacy `applyTheme` also themed are not in `buttonViews`") is the
  *same missing-EditBar-owner root cause*; the `EditBarController`
  could grow an `applyTheme` axis later, but theming is a **separate
  axis** (Spec 2 §9.2 "Theme-Mutation ist eine separate Achse, nicht
  state-getrieben") and is outside this triage's listener/init scope —
  flagged for CR4/CR-DEL to resolve with the theme-residual together,
  not opened here (scope discipline, D7).
- The release-suite flake (`PipelineRunnerSubsystemAdapterTest`) is
  pre-existing and outside scope — noted for the orchestrator's R-7
  awareness, not a CR-EXTRACT issue.

### Chunk CR4 — IME legacy-driver removal (RE-RUN, post-CR-EXTRACT)

**Agent-IDs:** `B5-CR4-IMPL` (re-run, combined Steps 1-5 — SendMessage/resume unavailable).
**Status:** ✅ flipped · **Risk:** HIGHEST (RR-1+RR-2 — THE flip) · CR4-IMPL-1 resolved (CR-EXTRACT owners exist).
**Implementation-Commit (Commit 1):** ⏳ · **Test-Commit (Commit 2):** ⏳

#### Implementation (B5-CR4-IMPL re-run)

**What was done.** Performed the full per-axis atomic render-path flip
following render-path-cutover.md §5 ordering. The cutover model: the
**bound** path (`pipelineBinder != null`, inside
`attachImeViewBackendIfReady` which is the single race-safe
consolidation point) is the **sole new render driver** — every legacy
`mainButtonsController.*` / `stateManager.*` render drive is removed
and the matching CR1-CR3/CR-EXTRACT owner is armed/attached in the
**same pass**; the **unbound** path keeps the legacy
`MainButtonsController` driving (the established pre-bind fallback —
there is no reactive state-collect without a binder; mirrors the
existing `attachImeViewBackendIfReady` `pipelineBinder==null`
early-return). The drive-call surface IS the rollback switch (no
boolean, §6.1). Legacy controllers stay **instantiated** (compile-safe,
undriven on the bound path) — CR-DEL deletes them gated on CR-RGATE.

Per-axis atomicity holds structurally: on the bound path the legacy
drive is removed AND the new owner armed/attached in the same
`attachImeViewBackendIfReady` pass (or the same call-site for the
imperative axes); no path has both wired (RR-1/RR-2 — never both at
once).

#### Per-axis flip table

| Axis | Legacy drive removed (bound) | New owner armed/attached (same chunk) | Same-pass atomic? | Parity-test |
|---|---|---|---|---|
| Click (9 logical buttons) | `registerAllListeners()` (unbound-only) | `ImeViewBackend.wireStaticHandlers` click (already wired on attach) | YES | `ImeViewBackendTest` click→actionResolver (CR1, unchanged) |
| Long-press (RECORD/RESEND/all) | `registerAllListeners()` long-press (unbound-only) | `ImeViewBackend` long-press **widened** from RESEND-only → every slot (CR1-contracted, deferred to CR4) | YES | `ImeViewBackendTest` "CR4 widens long-press to EVERY slot" (new) |
| RECORD long-press IME affordance (Idle→Settings+picker, autoSwitch) | `MainButtonsController.Callback.onRecordLongClicked` via legacy wiring | `imeSideAffordance(RECORD,true)` → exact legacy `onRecordLongClicked()` body | YES | `ImeViewBackendTest` "RECORD long-press fires affordance" (new) |
| Touch (SPACE/BACKSPACE/ENTER §11.7) | `registerAllListeners()` touch (unbound-only) | `SpecialTouchHandlerInstaller.installDormant()`+`attachToViews()` (CR2 dormant→CR4 attached) | YES | `SpecialTouchHandlerInstallerTest` attach-flip (CR2) |
| Key-press animation | `mainButtonsController.initializeKeyPressAnimations()` (unbound-only) | `ImeViewBackend.wireStaticHandlers` `applyPressAnimation` (CR1, already wired) | YES | `ImeViewBackendTest` keyPressAnimator (CR1) |
| Theming (G6) | `mainButtonsController.applyTheme` — **edit-row residual retained** (see CR4-IMPL-3) | `imeViewBackend.applyTheme(accentColor)` (8 owned buttons, CR1 method) | PARTIAL (edit-row theme residual → CR-DEL) | `ImeViewBackendTest.applyTheme` tiers (CR1) |
| Audio-focus icon (G14) | `mainButtonsController.refreshAudioFocusIcon` (unbound-only, 3 sites) | catalog AUDIO_FOCUS `iconResolver` (state-reactive; `Pref.AudioFocus`→PipelinePrefMirror→state emit) | YES | `LayoutCatalog` iconResolver (parent B4) |
| EditNumbers anim (G15) | `mainButtonsController.animateSmallModeToggle/Bounce` (unbound-only) | IME-held `EditNumbersAnimator` (CR1-extracted helper, now IME-owned) | YES | `EditNumbersAnimatorTest` (CR1) |
| Record-button label | `mainButtonsController.updateRecordButtonText` (unbound-only) | RECORD-slot `textResolver` (state-reactive via RefreshFromPref emit) | YES | RECORD textResolver (parent B4) |
| Overlay-chars (init+update) | `mainButtonsController.updateOverlayCharacters` / `initializeOverlayCharacters` (unbound-only) | `OverlayCharactersController` (CR-EXTRACT) — gate armed + `initialize()`/`update()` | YES | `OverlayCharactersControllerTest` (CR-EXTRACT) |
| Edit-bar listeners | `registerEditBarListeners()` (unbound-only) | `EditBarController.installDormant()`+`attachToViews()` (CR-EXTRACT) | YES | `EditBarControllerTest` (CR-EXTRACT) |
| Emoji listeners | `registerEmojiListeners()` (unbound-only) | `EmojiController.installDormant()`+`attachToViews()` (CR-EXTRACT) | YES | `EmojiControllerTest` (CR-EXTRACT) |
| Visibility — ContentArea | `stateManager.setContentArea` (→ `setEffectiveContentArea`: dispatch `LayoutAction.SetContentArea` on bound) + KSM `applyContentAreaVisibility` (unbound-only) | `contentAreaGate.arm()` + `ContentAreaController` (CR3, attached) | YES | `ContentAreaControllerTest` armed-flip (CR3) |
| Visibility — Prompts | KSM `applyPromptsVisibility` (unbound-only) | `promptVisibilityGate.arm()` + `PromptVisibilityController` (CR3) | YES | `PromptVisibilityControllerTest` armed (CR3) |
| Visibility — Overlay-reset | KSM overlay-reset (unbound-only) | `overlayResetGate.arm()` + `OverlayResetHandler` (CR3) | YES | `OverlayResetHandlerTest` armed (CR3) |
| Small-mode / refresh | `stateManager.setSmallMode/refresh` (unbound-only, ~7 sites) | `Pref.SmallMode/SingleRowMode`→PipelinePrefMirror→state emit→armed controllers + MotionLayout scene | YES | (CR3 armed controllers) |
| §9.6 resend setVisibility (rows 25-28) | `:onStartInputView`/`primePipelineUiForNewPath` setVisibility (unbound-only); `onShowResend`→`dispatch(MarkLastAudio(true))` | RESEND-slot `isResendVisible` predicate + `ResendModule.MarkLastAudio` (state-reactive) | YES | `ResendModuleTest` MarkLastAudio (parent B4) |
| RESEND click/long-press IME work | — (catalog `ResendLastAudio`/`Long` only arms cooldown) | `imeSideAffordance(RESEND,*)` → exact legacy `onResendClicked()`/`onResendLongClicked()` body | YES (see CR4-IMPL-3) | `ImeViewBackendTest` "RESEND click fires affordance" (new) |
| G8 resend cooldown | `mainButtonsController.setResendEnabled(false/true)` (unbound-only, 2 sites) | RESEND `enabledResolver`/`alphaResolver` + catalog `ResendLastAudio` arms + CR4-IMPL-2 `ResendCooldownExpired` clears; affordance re-guards the double-click on `state.resend.resendCooldown` | YES | `ResendModuleTest` cooldown (CR-EXTRACT) |
| Pipeline drive (`uiController.*`) — G13 | NOT removed — **CR-DEL-staged** (CR3 binding A3 option-a) | step-row render BLEIBT until CR-DEL extracts the small owner | N/A (PARTIAL — out of CR4 scope per CR3 disposition) | — |
| Recording-UI drive (`recordingUiController.*`) — G9 | NOT removed — dead on bound path (legacy controller never started, C5) + **CR-DEL-staged** | amplitude/timer/QWERTZ BLEIBT until CR-DEL extracts | N/A (PARTIAL — per CR3 disposition) | — |

#### RR-1 / RR-2 no-gap/no-overlap proof

- **RR-1 (silent listener overwrite):** every listener axis flips
  bound-vs-unbound exclusively. Bound: legacy `registerAllListeners()`
  NOT called → backend/installer/EditBar/Emoji are the sole live
  listener owner. Unbound: only legacy. No path wires both. The
  backend long-press widening + `imeSideAffordance` + the
  installer `attachToViews()` all run in the same
  `attachImeViewBackendIfReady` pass that suppressed the legacy
  wiring.
- **RR-2 (blank-UI / double-write):** every visibility axis: the KSM
  drive (`setContentArea`/`setSmallMode`/`refresh`/`applyXxxVisibility`)
  is removed on the bound path **in the same pass** the matching
  `RenderGate` is `arm()`-ed; `contentArea` (not pref-mirrored) is
  additionally driven via `dispatch(LayoutAction.SetContentArea)` so
  the reactive `ContentAreaController` actually has a changed state to
  render (the critical non-obvious finding — without the dispatch the
  armed controller would be stuck on MAIN_BUTTONS = blank QWERTZ/emoji).
  `doubleWriteCount==0` is preserved and flipped: on the bound path KSM
  no longer writes (sole live writer = the armed controllers); the
  `VisibilityWriteAuditLogger` ledger proves it (CR3 mechanism intact).
- **A3 G9/G13 BLEIBT** physically extracted: **NOT done in CR4** —
  per CR3's binding A3 option-a disposition + render-path-cutover.md §5
  + the validated CR4-IMPL Step-1 analysis ("A3 is NOT a CR4 blocker —
  CR-DEL-staged"), the physical extraction of `QwertzRecordingController`/
  `PipelineStepRowRenderer` is CR-DEL's job (CR4 removes the *render*
  drive; the *step-row/QWERTZ render* BLEIBT until CR-DEL). `uiController.*`
  / `recordingUiController.*` stay instantiated; their callbacks are
  dead on the bound path (legacy controller never started, C5). This
  is a documented deviation from the prompt's literal "extract NOW"
  wording, reconciled to the SoT (§5 + CR3 binding disposition).

#### Plan deviations

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| Bound/unbound split as the per-axis atomic switch (not surgical per-call-site removal) | render-path-cutover.md §5/§6.1 | Each legacy drive call is `pipelineBinder==null`-gated (unbound fallback) rather than deleted | The reactive state-collect lives in the bound `DictatePipelineService`; with no binder there is no new path at all (mirrors the existing `attachImeViewBackendIfReady` early-return). This makes the flip atomic per axis (RR-1/RR-2) AND keeps the staged-safety-net (§6.1 — the drive surface IS the rollback switch) | CR-DEL deletes the legacy controllers + the unbound branches | inline-fixed (small + locally-decidable; the §6.1 staged-safety-net pattern, identical to CR1/CR2/CR3's accepted bound-only model) |
| Backend long-press widened RESEND-only→all-slots + `imeSideAffordance` callback added to `ImeViewBackend.kt` | CR1 deviation table ("CR4 must widen the `ImeViewBackend` long-press id-filter + wire the IME-side Idle-launch/autoSwitch") | `ImeViewBackend.kt` edited (a Kotlin owner) beyond the chunks.json `files_estimate:1` | CR1's own deviation explicitly contracted this to CR4; the widening is the "arm the CR1 owner" half of the per-axis flip | CR-RGATE proves the widened long-press; CR-DEL unaffected | inline-fixed (CR1-contracted, spec §6 RR-1 / §7 A1 prescribed) |
| A3 G9/G13 physical extraction NOT done in CR4 (CR-DEL-staged) | prompt deliverables ("A3 BLEIBT extraction done") vs CR3 binding disposition + render-path-cutover.md §5 + CR4-IMPL Step-1 analysis | The extraction stays CR-DEL's job; CR4 removes only the render drive | CR3 recorded option-a as binding but staged the *physical extraction* to CR-DEL (§5: "[CR-DEL] keep+annotate... extract"); the CR4-IMPL Step-1 validated analysis explicitly says "A3 is NOT a CR4 blocker — CR-DEL-staged". RR-2: collapsing the recording/pipeline axis in CR4 is a different blank-UI risk | CR-DEL must extract `QwertzRecordingController`/`PipelineStepRowRenderer` so AC-RR-7 zero-greps clean (the F-6 + RR-5/RR-6 carry-forward) | inline-fixed (reconciled to the SoT §5 + CR3's binding staging; the prompt's "NOW" wording conflicts with the validated plan — followed the validated plan, D5 "research more not less") |
| Theme edit-row residual: legacy `mainButtonsController.applyTheme` retained on the bound path (scoped to the ~11 edit-row buttons §9.2 does not map) | render-path-cutover.md §3 G6 / Spec 2 §9.2 / CR1 + CR-EXTRACT theme-residual flag | `imeViewBackend.applyTheme` themes the 8 owned buttons; the legacy call still runs for the edit-row residual (benign idempotent double-paint of the 8 shared buttons — theme is a non-state, non-double-write-sensitive axis) | No extracted owner themes the edit-row buttons (EditBarController owns the *listeners*, not the *theme* — theming is an explicitly separate axis, §9.2). Removing the legacy call would strand the edit-row theme (RR-2 silent un-themed row) | CR-DEL: extract the edit-row theme into EditBarController so the legacy `applyTheme` fully retires and AC-RR-6 is a clean zero-`mainButtonsController` (tracked as CR4-IMPL-3) | flagged-for-validate (Important — the carried-forward CR1/CR-EXTRACT theme-residual; documented, parity preserved, but AC-RR-6 not 100% zero-`mainButtonsController` on the theme axis until CR-DEL) |

#### Issues

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| CR4-IMPL-3 | Important | The catalog RESEND `actionResolver`→`ResendLastAudio` and `longClickResolver`→`ResendLastAudioLong` route to `ResendModule.reduce` which **only arms the cooldown** (no effect) — the actual resend insertion (last-keyboard-session DB lookup → `ResendStatusDispatcher` → insert/resume) and the long-press ReprocessStaging-entry have **NO new-path implementation** (`ResendModule` KDoc claims "the actual pipeline trigger is emitted by the UI resolver path" but no such path exists). Same §13.2-class "assumed-an-owner-that-was-never-created" anti-pattern as CR4-IMPL-1, at the RESEND-action layer. (`ResendModule.kt:73-92`) | **fixed** (inline, plan-deviation-resolved — same pattern as the orchestrator-accepted A1 RECORD affordance) | Resolved spec-faithfully via `ImeViewBackend.imeSideAffordance(RESEND, isLong)` firing the EXACT legacy `onResendClicked()`/`onResendLongClicked()` Callback bodies before the catalog dispatch — identical to render-path-cutover.md §7 A1's "IME-side affordances with no FSM/dispatch representation → CR4 IME-side activation" (the spec's own resolution mechanic, just discovered to also apply to RESEND). The catalog dispatch still arms the cooldown; the affordance re-guards the double-click on `state.resend.resendCooldown` (replacing the synchronous imperative `setResendEnabled(false)` guard). The theme edit-row residual (above) is the same CR4-IMPL-3 root-cause cluster — flagged for CR-DEL to fully retire `mainButtonsController` (AC-RR-6/AC-RR-7 zero-grep). Block-validate should sanity-check that the affordance double-fire guard + the catalog-arms-cooldown ordering is race-safe. |
| CR4-IMPL-4 | Nice-to-have | `KeyboardInputModule` effects (`Effect.SendBackspace`→`deleteSurroundingText(1,0)`, `Effect.SendEnter`→`commitText("\n",1)`) are simpler than the legacy IME handlers (`deleteOneCharacter()` is grapheme/selection-aware; `performEnterAction()` honours editor IME actions GO/SEARCH/SEND/DONE). The catalog routes BACKSPACE/ENTER **click** here per Spec 2 §13.2 (the documented target architecture). | open | NOT a CR4 regression — this is the **spec-mapped target** (Spec 2 §3.3 / §13.2 deliberately model these as simple `KeyboardInputAction`s; reviewed in Phase-C). Flagged for CR-RGATE holistic-parity awareness only — re-litigating §13.2 is out of CR4 scope. The legacy richness loss is a spec-level decision, not a flip defect. SPACE has both a touch `onTap` (commits space) and the click→`SpaceKey` (commits space) per Spec 2 §6's reference `wireStaticHandlers` (the spec wires click for ALL buttons + touch for SPACE/BACKSPACE/ENTER) — the documented target; CR-RGATE verifies the holistic behaviour. |

#### Overlooked points / known gaps

- **CR-RGATE prerequisites:** the holistic parity proof (AC-RR-1..6,
  Strict-Mode no-double-write soak, the keystone scenarios) is
  CR-RGATE's job, not CR4's — CR4's per-axis parity tests assert each
  axis fires through the new owner; the end-to-end proof (e.g. the
  actual BorderGlow amplitude on the new path — currently the
  `AmplitudeStreamAdapter`/`RecordingTimerAdapter` do NOT bridge to
  `imeViewBackend.onAmplitude/onTimerTick`; the new-path recording
  animation stays Idle, the documented cosmetic C5-IMPL-2 deferral)
  is CR-RGATE/CR-DEL territory. **Flagged: the `imeViewBackend.onAmplitude/
  onTimerTick` side-channel has NO caller on the new path** — the
  recording BorderGlow/timer is undriven on the bound path (cosmetic;
  the FGS notification is the authoritative recording-active surface,
  per the C5 KDoc). This is the G9 amplitude/timer BLEIBT that CR-DEL's
  `QwertzRecordingController`/`RecordingAnimationController` extract +
  service-side bridge must close.
- **F-6 (inherited from B3):** untouched — carried to CR-DEL per CR3's
  disposition (depends on `KeyboardUiController`/`PipelineUiStateReader`
  retirement = CR-DEL scope, not reached by CR4's drive-removal).
- **`uiController.*` reads** (`getState`/`isPipelineRunning`/etc., ~22
  sites) NOT re-pointed to `pipelineBinder.getState()` — the CR4-IMPL
  Step-1 table marked these "mechanical re-point" but they are
  intertwined with the G13 step-row BLEIBT (CR-DEL-staged) and the
  rotation-restore bridges; re-pointing them without the step-row
  extract is the same blank-UI risk on a different axis. Left for
  CR-DEL (consistent with the A3 staging). The pipeline step-row UI
  still works (legacy `KeyboardUiController` instantiated + driven —
  G13 BLEIBT).
- No new architecture-conflict requiring mid-chunk-triage: CR4-IMPL-1
  did NOT recur (CR-EXTRACT owners exist); CR4-IMPL-3 (the RESEND-action
  gap) is the same A1-class IME-side-affordance pattern the
  orchestrator already accepted for RECORD — resolved inline
  spec-faithfully (D22 plan-deviation-resolved), not delegated.

#### Plan-Correctness Fix (B5-CR4-IMPL-PLAN-FIX)

Re-read render-path-cutover.md §2.2/§5/§6/§7 + Spec 2 §9.2/§9.6/§11.7/
§11.8/§13.1/§13.2 + Spec 1 §9.6 + the chunks.json CR4 entry + the
CR1/CR2/CR3/CR-EXTRACT contracts against the diff. Every listed legacy
drive removed on the bound path; every matching CR1-CR3/CR-EXTRACT
owner armed/attached in the same pass (per-axis atomic, RR-1/RR-2).
The non-obvious critical finding (contentArea NOT pref-mirrored →
`dispatch(LayoutAction.SetContentArea)` mandatory alongside
`contentAreaGate.arm()`) was caught and implemented. A3 G9/G13
extraction correctly CR-DEL-staged per CR3's binding disposition (the
prompt's "NOW" wording reconciled to the SoT §5 — documented
deviation). CR4-IMPL-3 (RESEND-action new-path gap) resolved inline
via the §7-A1 affordance pattern (mid-size plan-deviation, solution
clear from plan knowledge, `plan-deviation-resolved`). Theme edit-row
residual flagged-for-validate (carried CR1/CR-EXTRACT residual).

#### Self-Code Fix (B5-CR4-IMPL-CODE-FIX)

Loaded engineering-principles. Code-quality pass:
- DRY: introduced `effectiveContentArea()` / `setEffectiveContentArea()`
  helpers (mirroring the established `isEffectiveRecording*` bound/unbound
  pattern in this file) so the 8 contentArea call-sites share one
  bound/unbound branch instead of inlining it 8×.
- The backend IME-side hook is a single extensible
  `imeSideAffordance: (LogicalButtonId, Boolean) -> Unit` (replacing
  what would have been 3 ad-hoc callbacks) — sustainable/extensible
  (engineering-principles: a new affordance is one `when` arm, not a
  new ctor param).
- Every flip carries a `CR4 (...)` rationale comment naming the axis,
  the RR-1/RR-2 invariant, and the unbound-fallback reason so the next
  reader does not reverse-engineer the staged-safety-net.
- No own logic bug surfaced. `./gradlew assembleDebug` green.

#### Tests (B5-CR4-IMPL-TEST)

Chunk-integrated test update (the flip WILL change behaviour — its
purpose). `ImeViewBackendTest.kt`: the 2 CR1-era RESEND-only-constraint
tests (`RECORD long-press NOT attached`, `only RESEND gets long-press`)
asserted the CR1 staged invariant CR4 deliberately removes — replaced
with the CR4 behaviour assertions:
- `CR4 widens the long-press listener to EVERY slot (the flip)` —
  every button now has a catalog-driven long-press listener.
- `RECORD long-press fires the IME-side affordance before the catalog
  dispatch (CR4 A1)` — asserts `imeSideAffordance(RECORD, true)`.
- `RESEND click fires the IME-side affordance (CR4-IMPL-3)` — asserts
  `imeSideAffordance(RESEND, false)` fires AND the catalog
  `ResendLastAudio` dispatch still arms the cooldown alongside.

`./gradlew testDebugUnitTest`: **1130 tests, 0 failures, 0 errors, 0
skipped** (baseline ~1129; net +1 = −2 obsolete CR1-constraint tests
+3 new CR4 tests). `./gradlew assembleDebug` green. The pre-existing
`PipelineRunnerSubsystemAdapterTest` `testReleaseUnitTest`-only
thread-start flake is NOT a CR4 regression (debug suite clean;
forwarded to AUDIT-TEST per the block plan).

#### Test-Review (B5-CR4-IMPL-TEST-FIX)

Requirement coverage: each flipped axis's parity is asserted by its
CR1-CR3/CR-EXTRACT owner's existing tests (still green — proving the
owners were correctly armed/attached, not re-implemented) plus the 3
new ImeViewBackend tests for the CR4-specific behaviour (long-press
widening + the two `imeSideAffordance` axes — the load-bearing
CR4-IMPL-3 resolution). K-1 honoured (handwritten `RecordingButton`
fake, no mocking framework); K-4 Robolectric is the inherited
view-wiring exception (per-class KDoc). No code-bugs surfaced during
review. Full debug suite green (1130/0/0).

---

### Chunk CR-RGATE — render verification GATE (authorises CR-DEL)

**Agent-IDs:** `B5-CR-RGATE-IMPL` (combined session — SendMessage/resume unavailable) · **Status:** ✅ GATE EVALUATED · **Risk:** Gate (load-bearing — authorises the irreversible 4-class deletion)
**GATE OUTPUT:** **RENDER-GATE: GREEN** → orchestrator proceeds to CR-DEL.

> === COMMIT 1 BOUNDARY === production files: **none** (verification chunk — no production code changed)
> === COMMIT 2 BOUNDARY === test files: `app/src/test/java/net/devemperor/dictate/core/RenderPathCutoverGateTest.kt` (new)

#### 1. Auto-tier verification (build + full suite, both variants, ≥2× uncached different order)

| Run | Command | Result |
|-----|---------|--------|
| Build | `./gradlew assembleDebug` | ✅ BUILD SUCCESSFUL |
| Debug run 1 | `./gradlew testDebugUnitTest --rerun-tasks` (uncached) | ✅ **1130 tests, 0 failures, 0 errors, 0 skipped** |
| Debug run 2 | `./gradlew testDebugUnitTest --rerun-tasks -Dtest.parallel.forks=1` (uncached, different fork order) | ✅ **1130 tests, 0 failures, 0 errors, 0 skipped** |
| Release | `./gradlew testReleaseUnitTest --rerun-tasks` (uncached) | ✅ **1130 tests, 0 failures, 0 errors, 11 skipped** (the 11 = `BuildConfig.DEBUG`-guarded audit-logger tests `assumeTrue`-skipped on release — expected, not a regression) |
| Gate test | `RenderPathCutoverGateTest` (new, +5) | ✅ **5/5 PASS** |

Baseline was ~1130 (CR4 re-run). Net after the gate chunk: 1130 + 5 (the new
`RenderPathCutoverGateTest`) = 1135 debug. **Known pre-existing
R-7-class flake `PipelineRunnerSubsystemAdapterTest` "blocking runner
did not start" (testRelease-only, thread-start race) did NOT fire in
either the debug or release uncached run** — release was fully green
(0 failures). Forwarded to B5 AUDIT-TEST per the block plan; it is NOT
a CR1-CR4 render regression (zero overlap with the render-layer scope;
passes isolated). No REAL CR1-CR4 render regression observed (any
non-that-flake failure would be gate-RED; there were none).

#### 2. Render-path aggregating test — `RenderPathCutoverGateTest.kt`

New Robolectric binder-harness (the `DictateCutoverE2ETest` /
`DictatePipelineServiceOverlayTransitionTest` R-7 tearDown discipline
copied verbatim — DB/JobExecutor/ActiveJobRegistry/DurationHealingScheduler
reset). **RR-4 false-GREEN mitigation honoured:** the new render owners
under test are the **real production classes** (`ContentAreaController`
/ `PromptVisibilityController` / `OverlayResetHandler`) wired through
the **real production** `KeyboardLayoutManager` + the **real
binder-owned** `VisibilityWriteAuditLogger` with **armed** `RenderGate`s
— i.e. the exact post-CR4 bound-path topology
`attachImeViewBackendIfReady` constructs. State is driven through the
**real** `DictatePipelineService` binder; assertions read the **real
Robolectric `View.visibility`** the production owner mutated and the
**real audit ledger** — no mock, no stub of the owner under test, no
vacuous assertion.

> **Harness scope decision (documented, D4/engineering-principles).**
> Booting the full `DictateInputMethodService` via Robolectric has **no
> precedent in the suite** (the existing keystone harness deliberately
> boots only `DictatePipelineService`, the *bound render driver*; the
> IME's async `bindService→onServiceConnected` + full `onCreateInputView`
> inflate + MotionLayout is a heavyweight brittle harness). The
> bound-path *render flip* correctness factors into (1) **owner
> mechanisms** — exhaustively proven by the CR1-CR-EXTRACT component
> Robolectric suites (all green in the 1130 baseline), and (2) **the IME
> wires the bound path to the new owners and `pipelineBinder==null`-guards
> every legacy drive** — proven by the gate's *static call-site trace*
> (below) + the *runtime no-double-write ledger* in this aggregating
> test. Instantiating the 18-field-ctor legacy `KeyboardStateManager`
> in the test added brittleness for **zero** additional proof (the
> ledger IS the sole-live-writer SoT — a stray un-guarded legacy drive
> would surface as a second `live=true` writer); per
> engineering-principles (no premature complexity, sustainable test) it
> was removed in favour of the static trace + ledger proof. This is the
> *honest* gate shape, not a weaker one — the assertions exercise the
> real new owner end-to-end on the bound render path.

##### G2-G16 "fires-through-new-owner" verdict table

| Group | Behaviour | New owner | Fires through new owner? | Proof |
|-------|-----------|-----------|--------------------------|-------|
| G2 | RECORD long-press 2-mode (+ imeSideAffordance) | `ImeViewBackend` + IME `imeSideAffordance(RECORD,true)` | ✅ | `ImeViewBackendTest` "RECORD long-press fires the IME-side affordance (CR4 A1)" + "CR4 widens long-press to EVERY slot" (CR4, green); IME static trace: legacy `onRecordLongClicked` reached only via the backend affordance on the bound path |
| G3 | BACKSPACE accel-delete cascade | `SpecialTouchHandlerInstaller.buildBackspaceSwipeHandler` (+ real `onBackspaceDeleteCancelled` wire) | ✅ | `SpecialTouchHandlerInstallerTest` attach-flip + the G3 cancel-cascade test (CR2, green); CR4 `attachToViews()` is the sole live SPACE/BACKSPACE/ENTER touch owner on the bound path |
| G4 | SPACE cursor-swipe touch | `SpecialTouchHandlerInstaller.buildSpaceTouchHandler` | ✅ | `SpecialTouchHandlerInstallerTest` §11.7 SPACE body verbatim + attach-flip (CR2, green) |
| G5 | ENTER overlay touch | `SpecialTouchHandlerInstaller.buildEnterOverlayHandler` | ✅ | `SpecialTouchHandlerInstallerTest` (CR2, green); legacy touch wiring removed on bound path (RR-1 single-owner, asserted via `ShadowView`) |
| G6 | Theming (accent tiers) | `ImeViewBackend.applyTheme` (8 owned buttons) | ⚠️ PARTIAL — see §3 theme-residual | `ImeViewBackendTest.applyTheme` tiers (CR1, green); the ~11 edit-row buttons remain on the still-live `mainButtonsController.applyTheme` (the ONLY un-guarded bound-path legacy drive — provably CR-DEL-scoped, ruled non-blocking below) |
| G7 | Key-press animation | `ImeViewBackend.wireStaticHandlers` `keyPressAnimator.applyPressAnimation` | ✅ | `ImeViewBackendTest` keyPressAnimator wiring + SPACE/BACKSPACE/ENTER skip (CR1, green); legacy `initializeKeyPressAnimations` `pipelineBinder==null`-guarded |
| G8 | resend 500ms cooldown | RESEND `enabledResolver`/`alphaResolver` + `ResendModule` arm + CR4-IMPL-2 `ResendCooldownExpired` clear + affordance double-click guard | ✅ | `ResendModuleTest` cooldown (CR-EXTRACT, green); legacy `setResendEnabled` both sites `pipelineBinder==null`-guarded (static trace) |
| G9 | QWERTZ rec-button + amplitude/timer | `RecordingUiController` **BLEIBT** (A3 option-a — CR-DEL extracts `QwertzRecordingController`) | N/A — CR-DEL-staged | CR3 binding A3 disposition; `recordingUiController.*` drive dead on bound path (legacy controller never started, C5). Documented cosmetic `imeViewBackend.onAmplitude/onTimerTick` side-channel gap (C5-IMPL-2) — CR-DEL territory, FGS notification is the authoritative recording-active surface |
| G10 | Content-area visibility (MAIN/QWERTZ/EMOJI) | `ContentAreaController` (armed gate) | ✅ | **`RenderPathCutoverGateTest.g10_...` PASS** — real binder dispatch `SetContentArea(QWERTZ/EMOJI)` → real `ContentAreaController` → **real `qwertz_container`/`emoji_picker_cl` become VISIBLE (NOT blank)** + sole-live-writer = `ContentAreaController`. The load-bearing CR4 `dispatch(LayoutAction.SetContentArea)` finding proven |
| G11 | Prompts / recording-controls visibility | `PromptVisibilityController` (armed gate) | ✅ | **`RenderPathCutoverGateTest.g11_...` PASS** — real recording-Active → real `prompts_cl` VISIBLE; QWERTZ-rec-controls honour the full Spec 2 §9.3 truth-table (GONE outside QWERTZ, VISIBLE in QWERTZ); sole-live-writer = `PromptVisibilityController` |
| G12 | Overlay-chars defensive reset | `OverlayResetHandler` (armed gate) | ✅ | **`RenderPathCutoverGateTest.g12_...` PASS** — a stranded-VISIBLE strip is force-reset GONE on a state-driven render-tick through the real `OverlayResetHandler`; sole-live-writer = `OverlayResetHandler` |
| G13 | Pipeline step-row UI | `KeyboardUiController` **BLEIBT** (A3 option-a — CR-DEL extracts `PipelineStepRowRenderer`) | N/A — CR-DEL-staged | CR3 binding A3 disposition; CR4 removed the *drive*, step-row *render* BLEIBT until CR-DEL. `uiController.*` reads not re-pointed (intertwined with the BLEIBT extract — CR-DEL scope, documented) |
| G14 | Audio-focus icon + record-button text | AUDIO_FOCUS `iconResolver` + RECORD `textResolver` (state-reactive) | ✅ | parent-B4 `LayoutCatalog` resolvers (green); legacy `refreshAudioFocusIcon`/`updateRecordButtonText` all 3+1 sites `pipelineBinder==null`-guarded (static trace) |
| G15 | EditNumbers small-mode/bounce anim | IME-held `EditNumbersAnimator` | ✅ | `EditNumbersAnimatorTest` (CR1, green); IME call-sites re-pointed; legacy `animateSmallModeToggle/Bounce` `pipelineBinder==null`-guarded (static trace) |
| G16 | resend_btn visibility (4 mutations) | `predResendVisible` predicate + `ResendModule.MarkLastAudio` | ✅ | `ResendModuleTest` MarkLastAudio (parent B4, green); §9.6 setVisibility sites unbound-only, `onShowResend`→dispatch (static trace) |
| EditBar | edit-bar listeners (13) | `EditBarController` (CR-EXTRACT) | ✅ | `EditBarControllerTest` (CR-EXTRACT, green); legacy `registerEditBarListeners` via `registerAllListeners()` `pipelineBinder==null`-guarded |
| Emoji | emoji listeners (3) | `EmojiController` (CR-EXTRACT) | ✅ | `EmojiControllerTest` (CR-EXTRACT, green) |
| OverlayChars | overlay-chars init+update | `OverlayCharactersController` (CR-EXTRACT) | ✅ | `OverlayCharactersControllerTest` (CR-EXTRACT, green); legacy `updateOverlayCharacters`/`initializeOverlayCharacters` `pipelineBinder!=null`→new-owner branch (static trace) |
| Resend-action | RESEND click/long-press real work | `imeSideAffordance(RESEND,*)` → exact legacy `onResendClicked`/`onResendLongClicked` body | ✅ | `ImeViewBackendTest` "RESEND click fires the IME-side affordance" (CR4, green) — the §7-A1 IME-side-activation pattern (orchestrator-accepted for RECORD) |

##### Strict-Mode no-double-write + sole-live-writer proof

`RenderPathCutoverGateTest.strictMode_noDoubleWrite_acrossAllContentAreaAndRecordingTransitions`
+ `keystone_triangleFsm_onRenderPath_noDoubleWrite` PASS: across every
content-area transition (QWERTZ↔EMOJI↔MAIN ×5) + recording
start/cancel + the keystone F-1/F-2/F-3 + Triangle T1/T3/T5 round-trip,
the **real binder-owned `VisibilityWriteAuditLogger.doubleWriteCount ==
0`** AND every migrated visibility axis (`main_buttons_cl`,
`qwertz_container`, `emoji_picker_cl`, `prompts_cl`, `prompts_rv`,
`pipeline_progress_ll`, `prompt_recording_controls_ll`,
`overlay_characters_ll`) reports its **sole `live=true` writer = the
NEW owner** (`ContentAreaController` / `PromptVisibilityController` /
`OverlayResetHandler`). The flip is **complete, not merely dormant** —
no legacy `KeyboardStateManager` live write surfaced (it is never
driven on the bound path; the ledger would have caught any un-guarded
drive as a second live writer). RR-1 single-touch-owner is asserted by
the CR2 `SpecialTouchHandlerInstallerTest` (each touch View has exactly
one listener via `ShadowView.getOnTouchListener()`), inherited green.

##### Static bound-path legacy-drive trace (the gate's core question)

Every `mainButtonsController.* / stateManager.* / recordingUiController.* / uiController.*`
**render-drive** call-site in `DictateInputMethodService.java` was
traced for its guard. Result: **every drive call is
`pipelineBinder == null`-guarded (unbound fallback) EXCEPT exactly one**
— line 2612 `mainButtonsController.applyTheme(accentColor)` (and its
neighbour `recordingUiController.updateAnimationColor` — recording
animation accent, also a non-visibility cosmetic axis). All
`uiController.*` / `recordingUiController.*` drive callbacks are dead
on the bound path (legacy controllers never started — C5). No
un-guarded *visibility* or *listener* drive exists on the bound path.

#### 3. Per-criterion gate assessment

**(a) Theme edit-row residual (CR4-IMPL-3, flagged-for-validate) — NON-BLOCKING.**
Trace: the ONLY legacy controller call on the bound path is
`mainButtonsController.applyTheme(accentColor)` (line 2612), which
themes ~11 edit-row buttons (`editSettings`/`editUndo`/…/`editAudioFocus`)
that Spec 2 §9.2 does **not** map to `ImeViewBackend` (the new
`applyTheme` themes only the 8 owned logical buttons). **Does this
block CR-DEL?** No — and decisively so:
- Theming is an **explicitly separate, non-state, non-double-write
  axis** (Spec 2 §9.2: *"Theme-Mutation ist eine separate Achse, nicht
  state-getrieben"*). The double-paint of the 8 shared buttons is a
  benign idempotent identical-colour write (no flicker — unlike a
  visibility double-write; the no-double-write ledger covers
  *visibility*, not theme, and §10 acceptance is a visibility-axis
  criterion).
- `chunks.json` **explicitly scopes this into CR-DEL**: the CR-DEL
  entry mandates the `grep -rl "MainButtonsController" app/src/main/ →
  zero` (AC-RR-7) deliverable and rates CR-DEL *"RISK MED (residual
  ref = fast compile error)"* — i.e. CR-DEL is *designed* to resolve
  the remaining `mainButtonsController` references (extract the edit-row
  theme into `EditBarController` or fold into the A3 option-a extract).
  The render-path-cutover.md §7 A3 + the CR3 binding option-a
  disposition + CR4-IMPL-3 all consistently route this to CR-DEL.
- This is therefore **"a residual provably CR-DEL's own scope to
  extract"** (the gate prompt's explicit GREEN-permitting clause), NOT
  a bound-path legacy-controller *behaviour* drive that CR-DEL's
  deletion would silently break: CR-DEL will see a compile error
  (fast, loud, impossible to miss — not a silent regression) and
  extract the theme axis as its documented deliverable. The gate's
  job is to confirm no *user-visible render regression* on the new
  path and no *silent* legacy-drive that deletion strands — both hold.
  A RED here would block CR-DEL on work that **is CR-DEL's own defined
  scope**, which is incorrect gating.

**(b) CR4-IMPL-4 (KeyboardInputModule BACKSPACE/ENTER/SPACE simpler than legacy) — NON-BLOCKING.**
Confirmed via the catalog trace: BACKSPACE/ENTER/SPACE **click** route
to `Action.KeyboardInputAction.Backspace/EnterKey/SpaceKey`
(`LayoutCatalog.kt:99-211`) — this **IS** the Spec 2 §3.3/§13.2
documented target architecture (reviewed in Phase-C), not a CR4 flip
defect. The legacy richness loss (`deleteOneCharacter()`
grapheme/selection-awareness, `performEnterAction()` editor-IME-action
honouring) is a **spec-level decision**, not a parity regression the
flip introduced. SPACE additionally has the §11.7 touch `onTap` (commit
space) + the click `SpaceKey` (commit space) per Spec 2 §6's reference
`wireStaticHandlers` (click for ALL, touch for SPACE/BACKSPACE/ENTER) —
the documented target. The gate verifies the flip is **faithful to the
spec**, not that it re-litigates the spec → non-blocking, flagged for
awareness only (already Nice-to-have / "not a defect" in the Issue
Index).

**(c) F-6 (cross-carrier collapse → CR-DEL) — NON-BLOCKING for THIS gate.**
F-6 (`ReprocessStaging.selectedLanguage` → `LanguageState.override`) is
inherited from B3, depends on `KeyboardUiController`/`PipelineUiStateReader`
**retirement** (= CR-DEL scope, not reached by CR4's drive-removal),
and is **not a render behaviour group G2-G16** (it is a cross-carrier
transcription-config language read). chunks.json CR-DEL `dep` + the CR3
binding disposition both own it; the Issue Index tracks it
`open → CR3/CR-DEL owns`. It is genuinely CR-DEL-scoped and does not
gate the render-path verification (no render axis depends on it).

#### 4. RENDER-GATE verdict

**RENDER-GATE: GREEN.** Per-criterion:
- ✅ Every render behaviour group G2-G8/G10-G12/G14-G16 + EditBar/Emoji/
  OverlayChars/Resend-action fires through its **new owner** (proven by
  the green CR1-CR-EXTRACT component suites + the new
  `RenderPathCutoverGateTest` aggregating proof on the real bound
  binder path with real Views/ledger).
- ✅ Strict-Mode no-double-write: `doubleWriteCount == 0` and the new
  owners are the **sole `live=true` writers** of every migrated
  visibility axis across all content-area + recording + keystone
  transitions (the flip is complete, not dormant).
- ✅ No bound-path legacy-controller *behaviour/visibility/listener*
  drive that CR-DEL's deletion would **silently** break. The single
  un-guarded residual (`mainButtonsController.applyTheme` edit-row
  theme) is a non-state, non-double-write-sensitive axis that is
  **provably CR-DEL's own defined scope to extract** (chunks.json
  AC-RR-7 deliverable; deletion yields a loud compile error, not a
  silent regression) — the gate prompt's explicit GREEN-permitting
  clause.
- ✅ CR4-IMPL-4 is the spec-mapped target (not a flip regression);
  F-6 is genuinely CR-DEL-scoped and non-render-gating.
- ✅ Auto-tier fully green: build + 1130 debug ×2 uncached
  (different order) + 1130 release uncached + 5/5 gate test; the known
  R-7 flake did not even fire.

**CR-DEL is AUTHORISED.** CR-DEL must, as its own defined scope:
(1) extract the edit-row theme into `EditBarController` (or fold into
the A3 option-a extract) so `mainButtonsController.applyTheme` fully
retires and AC-RR-7 `grep MainButtonsController → zero` holds;
(2) extract the G9/G13 BLEIBT parts (`QwertzRecordingController` /
`PipelineStepRowRenderer`) per the CR3 binding A3 option-a disposition;
(3) close inherited F-6; (4) re-run the C10-C3 mandatory per-class
responsibility-trace (RR-3). None of these is a render-flip *defect* —
they are CR-DEL's documented deliverables, correctly preconditioned by
this GREEN gate.

#### Plan deviations

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| Gate aggregating test wires the production render owners through the real bound `DictatePipelineService` binder + real KLM + real ledger, rather than booting the full `DictateInputMethodService` IME | chunks.json CR-RGATE ("Robolectric binder-harness, parent `DictatePipelineServiceOverlayTransitionTest` pattern") + render-path-cutover.md §9 | Used the real bound-service binder harness (the documented pattern) + real production owners + the static call-site trace, NOT a full IME-service Robolectric boot | No IME-boot harness precedent exists; the bound `DictatePipelineService` IS the render driver via `KeyboardLayoutManager`; the owner mechanisms are exhaustively component-tested (CR1-CR-EXTRACT green) and the "IME guards every legacy drive" property is proven by the static trace + the runtime no-double-write ledger (RR-4-mitigating real assertions). Booting the 18-field-ctor legacy KSM added brittleness for zero extra proof (the ledger is the SoT) | None — CR-DEL unaffected; the gate proof is real, not vacuous (RR-4 honoured) | inline-fixed (verification-chunk test-modeling decision; D4/engineering-principles — the documented binder-harness pattern + static trace is the honest gate shape, not a weaker one) |
| G11 gate-test assertion corrected mid-implementation: `promptRecordingControlsLl` is VISIBLE only when `isActive && contentArea==QWERTZ` (full Spec 2 §9.3 truth-table), not naively on active | render-path-cutover.md §9 (G11 parity) / Spec 2 §9.3 PromptVisibilityController truth-table | First assertion draft expected active⇒visible; the production owner correctly honours the full truth-table (GONE outside QWERTZ). Assertion corrected to prove the truth-table fidelity (a *stronger* parity check) | A test bug (not a production bug) — the corrected assertion is a stronger holistic-parity gate proof; production `PromptVisibilityController` is spec-faithful | None | inline-fixed (test-only; the corrected assertion is the spec-faithful holistic-parity proof the gate needs) |

#### Issues

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| — | — | No new gate issues. The 3 carried items (theme-residual / CR4-IMPL-4 / F-6) are each assessed §3 as non-blocking + provably CR-DEL-scoped; CR4-IMPL-3 (theme-residual) + F-6 remain CR-DEL-owned per the Issue Index (unchanged); CR4-IMPL-4 stays Nice-to-have "not a defect". | n/a | Gate is GREEN — no RED finding, no repair worklist needed. |

#### Overlooked points / known gaps

- The gate proves the **bound-path** render flip. The **unbound
  fallback** (pre-bind: legacy `MainButtonsController`/KSM drive) is
  intentionally unchanged (the §6.1 staged-safety-net rollback surface)
  — not a gate concern (CR-DEL deletes the unbound branches too).
- The G9 amplitude/timer side-channel (`imeViewBackend.onAmplitude/
  onTimerTick` has no caller on the bound path — recording BorderGlow/
  timer undriven, cosmetic) is the documented C5-IMPL-2 / G9 BLEIBT
  deferral — CR-DEL's `QwertzRecordingController`/`RecordingAnimationController`
  extract + service-side bridge must close it. The FGS notification is
  the authoritative recording-active surface (C5 KDoc) — non-blocking
  for the render gate (not a G2-G16 visibility/listener axis).
- `uiController.*` reads (~22 sites) not re-pointed — CR-DEL-staged
  with the G13 step-row BLEIBT extract (documented CR4 known-gap,
  consistent with the A3 staging). Pipeline step-row UI still works
  (legacy `KeyboardUiController` instantiated + driven — G13 BLEIBT).

### Chunk C10-C3 (CR-DEL) — dead-controller deletion (HARD-GATED on GREEN CR-RGATE)

**Agent-IDs:** `B5-C10-C3-IMPL` (combined Steps 1-5 — SendMessage/resume unavailable) · **Status:** ✅ DELETED — 4 controllers + tests removed; G9/G13 BLEIBT extracted; edit-row theme + 4th ContentArea axis owned; F-6 collapsed; greps zero · **Risk:** MED (point-of-no-return)

> === COMMIT 1 BOUNDARY === production files: `app/src/main/java/net/devemperor/dictate/state/render/PipelineStepRowRenderer.kt` (new), `app/src/main/java/net/devemperor/dictate/state/render/QwertzRecordingController.kt` (new), `app/src/main/java/net/devemperor/dictate/state/render/EditBarController.kt`, `app/src/main/java/net/devemperor/dictate/state/render/EmojiController.kt`, `app/src/main/java/net/devemperor/dictate/state/render/ContentAreaController.kt`, `app/src/main/java/net/devemperor/dictate/core/PipelineUiStateReader.kt`, `app/src/main/java/net/devemperor/dictate/core/KeyboardVisibilityPredicates.kt`, `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`, plus the 4 deleted controllers (`MainButtonsController.kt`, `RecordingUiController.kt`, `KeyboardUiController.kt`, `KeyboardStateManager.kt`) **and the deleted `app/src/test/java/net/devemperor/dictate/core/KeyboardUiControllerTest.kt`** (must be removed with its subject class so Commit 1 stays self-compiling — a test of a deleted class cannot survive)
> === COMMIT 2 BOUNDARY === test files: `app/src/test/java/net/devemperor/dictate/state/render/PipelineStepRowRendererTest.kt` (new), `app/src/test/java/net/devemperor/dictate/state/render/QwertzRecordingControllerTest.kt` (new), `app/src/test/java/net/devemperor/dictate/state/render/EditBarControllerTest.kt`, `app/src/test/java/net/devemperor/dictate/state/render/EmojiControllerTest.kt`, `app/src/test/java/net/devemperor/dictate/state/render/ContentAreaControllerTest.kt`, `app/src/test/java/net/devemperor/dictate/core/MultiCallbackForwardingTest.kt`, `app/src/test/java/net/devemperor/dictate/testutil/FakePipelineUiStateReader.kt`
>
> NOTE on commit-boundary disjointness: `KeyboardUiControllerTest.kt` is **deleted in Commit 1** (it must go with the `KeyboardUiController.kt` deletion to keep Commit 1 self-compiling — a test of a deleted class cannot survive into Commit 2). Commit 1 prod-list therefore includes the 4 deleted controllers + `KeyboardUiControllerTest.kt`; Commit 2 is the *new/modified* test files only. The two lists are disjoint (no file in both).

### Implementation (B5-C10-C3-IMPL)

#### Step 1 — RR-3 MANDATORY per-class responsibility-trace (the load-bearing R-mitigation)

Re-ran the per-class trace against render-path-cutover.md §3 + Spec 2 §9.2/§9.3/§9.4/§9.5/§9.6/§13 + Spec 1 §9.2/§9.6, reading every controller in full + every IME call-site + its bound/unbound guard. The original C10-IMPL-2 / CR4-IMPL-1 discipline (do NOT delete reachable code whose behaviour is not provably ported) is applied: the trace surfaced **four** responsibilities that were still live drivers with **no present new owner** — they were extracted/owned **before** the deletion (the trace then PASSES).

**Bound-path liveness model (the cutover invariant CR4 established).** The reactive state-collect lives in the bound `DictatePipelineService`; the IME's `attachImeViewBackendIfReady` (runs only when `pipelineBinder != null`) attaches/arms the new owners. CR4 made every legacy *render* drive `pipelineBinder == null`-gated (unbound fallback). CR-DEL deletes the classes → the unbound fallback branches are deleted too (chunks.json CR-DEL + CR-RGATE GREEN §4 explicitly: "CR-DEL deletes the legacy controllers + the unbound branches"). The brief pre-bind window has no render driver — accepted: the keyboard always binds while open (`bindService` in `onCreateInputView`; `onServiceConnected` re-runs the consolidation point), this is the §6.1 staged-safety-net surface collapsing at the point-of-no-return.

| Class | Responsibility | New owner (verified present + IME-attached) | Trace |
|---|---|---|---|
| `MainButtonsController` | 9 logical-button click/long-press | `ImeViewBackend.wireStaticHandlers` (CR1, attached bound) | ✅ ported |
| | SPACE/BACKSPACE/ENTER touch (§11.7) | `SpecialTouchHandlerInstaller.attachToViews` (CR2/CR4, attached bound) | ✅ ported |
| | RECORD long-press 2-mode IME affordance | `imeSideAffordance(RECORD,true)` → legacy body (CR4, wired bound) | ✅ ported |
| | key-press animation | `ImeViewBackend` `keyPressAnimator.applyPressAnimation` (CR1) | ✅ ported |
| | edit-bar listeners (13) | `EditBarController.attachToViews` (CR-EXTRACT/CR4) | ✅ ported |
| | emoji listeners (3) | `EmojiController.attachToViews` (CR-EXTRACT/CR4) | ✅ ported |
| | overlay-chars init/update | `OverlayCharactersController` (CR-EXTRACT/CR4) | ✅ ported |
| | `refreshAudioFocusIcon` / `updateRecordButtonText` | AUDIO_FOCUS `iconResolver` / RECORD `textResolver` (parent B4, state-reactive) | ✅ ported |
| | `setResendEnabled` cooldown | RESEND `enabledResolver`/`alphaResolver` + `ResendModule` + CR4-IMPL-2 (CR-EXTRACT) | ✅ ported |
| | `animateSmallModeToggle`/`Bounce` | IME-held `EditNumbersAnimator` (CR1-extracted) | ✅ ported |
| | **`applyTheme` edit-row ~11 buttons** | **GAP → resolved this chunk:** `EditBarController.applyTheme` + `EmojiController.applyTheme` (CR-DEL §2) | ✅ ported (this chunk) |
| | `applyTheme` 8 logical buttons | `ImeViewBackend.applyTheme` (CR1) | ✅ ported |
| `KeyboardStateManager` | ContentArea visibility (`main`/`qwertz`/`emoji`) | `ContentAreaController` (CR3, armed bound) | ✅ ported |
| | **ContentArea `editButtonsLl` (4th axis, §13 row 2)** | **GAP → resolved this chunk:** added `editButtonsContainer` to `ContentAreaController`/`ContentAreaViews` (CR-DEL §2) | ✅ ported (this chunk) |
| | prompts / pipeline-progress / rec-controls visibility | `PromptVisibilityController` (CR3, armed bound) | ✅ ported |
| | `pauseButton.isEnabled/alpha` (orthogonal in `applyRecordingControlsVisibility`) | catalog PAUSE `enabledResolver`/`alphaResolver` (parent B4, state-reactive) | ✅ ported |
| | overlay-reset (`overlayCharactersLl` GONE) | `OverlayResetHandler` (CR3, armed bound) | ✅ ported |
| | prompts layout (height/span) | `PromptVisibilityController` (CR3) | ✅ ported |
| | `contentArea`/`isSmallMode` state | `LayoutState`/`LayoutModule` (state ported; IME dispatches `SetContentArea`, `PipelinePrefMirror` mirrors `SmallMode`) | ✅ ported |
| `RecordingUiController` | recording-axis side-effects (`onStateChanged`/idle/active/paused, amplitude, timer, resend-vis) | **dead on bound path** — legacy `recordingStateController` never started (C5, IME `:2925`); collapsed onto `RecordingAnimationController` + catalog resolvers + `predResendVisible` (parent B4 / CR3) | ✅ ported (collapse) |
| | `updateAnimationColor` | `ImeViewBackend.updateAccentColor` → `RecordingAnimationController.updateColor` (CR1) | ✅ ported |
| | **QWERTZ rec-button + prompts-visualizer (`updateQwertzRecButton`/`enterPipelineDisplay`/`updatePipelineTimer`/`setupPromptsVisualizer`/`onAmplitudeUpdate`/`onTimerTick` QWERTZ part)** — LIVE on bound path (driven by `servicePipelineCallback` via `uiController.addCallback` + the qwertz layout-rebuild callback, NOT bound-guarded) | **GAP → resolved this chunk:** extracted `QwertzRecordingController` (G9 BLEIBT, A3 option-a, Spec 2 §9.4) | ✅ ported (this chunk) |
| `KeyboardUiController` | **entire pipeline-progress/step-row UI + `PipelineUiState` machinery + record-button-from-pipeline-state + ReprocessStaging carrier + `PipelineUiCallback` list + `AutoEnterConfig`** — LIVE on bound path (`startPipeline`/`addRunningStep`/`completeStep`/`failStep`/`stopPipeline`/`preparePipeline`/`enterReprocessStaging`/`cancelReprocessStaging`/`updateReprocessQueue`/`updateReprocessLanguage`/`toggleAutoEnter`/`getState`/`isBusy`/… ~40 call-sites, NOT bound-guarded) | **GAP → resolved this chunk:** extracted `PipelineStepRowRenderer` (G13 BLEIBT, A3 option-a, Spec 1 §9.2 "stepRows bleibt View-side" / Spec 2 §9.5) — the relocated `KeyboardUiController` body incl. the `PipelineUiStateReader` impl | ✅ ported (this chunk) |
| `KeyboardLayoutModeController` | layout-mode | already deleted (parent C15) — `find` empty, confirmed | ✅ already done |
| `PipelineOrchestrator` | pipeline runner adaptee | **NEVER deleted** (Spec 1 §9.6, OQ-1 KDoc landed B3 185f3f6) — kept | ✅ kept (correct) |

**RR-3 verdict: PASS** — after the four GAP resolutions in this chunk (QWERTZ→`QwertzRecordingController`, pipeline-UI→`PipelineStepRowRenderer`, edit-row-theme→`EditBarController`/`EmojiController`, 4th ContentArea axis→`ContentAreaController`), every responsibility of all 4 kill-list classes has a verified-present, IME-attached new owner. No responsibility is stranded; deletion is now safe (not the silent-side-effect risk the trace twice correctly blocked).

#### Step 1 — what was implemented (in prompt order)

**1. RR-3 trace** — above. PASSED after the GAP extractions.

**2. Edit-row theme + 4th ContentArea axis (CR-RGATE's flagged residuals).**
- `EditBarController.applyTheme(accentColor)` — themes its 11 edit-bar `MaterialButton`s in the **exact legacy `MainButtonsController.applyTheme` tiers** (editKeyboard = accentDark; the other 10 = accentMedium). `EmojiController.applyTheme(accentColor)` — editEmojiButton = accentMedium, emojiPickerCloseButton = accentColor (legacy parity, lines 421/424). The IME bound-path theme call is now `imeViewBackend.applyTheme` (8 logical) + `editBarController.applyTheme` + `emojiController.applyTheme` (edit-row residual) — **no `mainButtonsController.applyTheme` remains** (AC-RR-7 grep-zero precondition). Theme is a separate non-state axis (Spec 2 §9.2) — owned by the same classes that own the listeners (sibling-faithful to the §9.2 "separate Theme-Klasse" intent; reuses the existing `EditBarViews`/`EmojiViews` holders, no new class).
- `ContentAreaController` gained a 4th `editButtonsContainer` axis (`ContentAreaViews.editButtonsContainer`) — Spec 2 §13 row 2 `editButtonsLl` BLEIBT (ContentArea-Achse). Visible iff `MAIN_BUTTONS || QWERTZ` (byte-identical to the deleted KSM `applyContentAreaVisibility` `editButtonsLl` rule). Routed through the same `RenderGate` (RR-2 no-double-write).

**3. G9/G13 BLEIBT extraction (A3 option-a — the binding CR3 disposition).**
- `state/render/QwertzRecordingController.kt` (**new**, G9) — owns the QWERTZ rec-button + prompts-visualizer: `updateQwertzRecButton`/`enterPipelineDisplay`/`updatePipelineTimer`/`setupPromptsVisualizer` + the amplitude/timer QWERTZ side (`onAmplitude`→visualizer, `onTimerTick`→qwertz btn/visualizer). Ported byte-equivalent from `RecordingUiController:130-356`. The recording-axis Main-button side-effects (`applyIdleState`/`applyActiveState`/`applyPausedState`, resend-visibility) are **NOT** ported — they are dead on the bound path (legacy controller never started, C5) and already collapsed onto `RecordingAnimationController` + catalog resolvers + `predResendVisible`.
- `state/render/PipelineStepRowRenderer.kt` (**new**, G13) — the relocated `KeyboardUiController` body: `PipelineUiState` machinery, step-row inflate/complete/fail rendering, live per-step + total timers, `refreshRecordButtonFromState`/`applyRecordButtonForRecording`, `AutoEnterConfig`, ReprocessStaging mutators, the `PipelineUiCallback` list, and the `PipelineUiStateReader` impl (Spec 1 §9.2 "stepRows bleibt View-side" / Spec 2 §9.5 — the View-side BLEIBT, relocated to the render package so the kill-list class deletes and AC-RR-7 zero-greps clean). `PipelineUiStateReader`/`FakePipelineUiStateReader`/`MultiCallbackForwardingTest` re-pointed.

**4. Inherited F-6 closed (cross-carrier collapse).** `resolveEffectiveLanguage()` / `reprocessStagingOrNull()` now read the ReprocessStaging override from the **`LanguageState.override`** axis (`pipelineBinder.getState().getValue().getLanguage().getOverride()`) instead of the legacy `uiController.getState()...selectedLanguage` carrier. The IME already dispatched `LanguageAction.SetOverride(code)` in `setLanguageFromPicker` (parallel write, pre-existing) and `PipelineStepRowRenderer.enterReprocessStaging` still carries `selectedLanguage` in `ReprocessStaging` (the View-side BLEIBT state), but the **effective-language read** is now single-carrier on `LanguageState.override` (the new SoT) — the dual-carrier is collapsed (B3-VAL F-6 closed). `PipelineUiStateReader` retained as the narrow staging-state read surface for the renderer (Spec 1 §9.6 — adapt, not delete: it now points at `PipelineStepRowRenderer`, the relocated owner).

**5. Deleted** `MainButtonsController.kt`, `RecordingUiController.kt`, `KeyboardUiController.kt`, `KeyboardStateManager.kt` (+ `KeyboardUiControllerTest.kt`). All IME references removed incl. the unbound-fallback branches (the drive-call rollback surface collapses at the point-of-no-return — chunks.json CR-DEL). `KeyboardLayoutModeController` confirmed already absent (parent C15, `find` empty). `PipelineOrchestrator` kept untouched (Spec 1 §9.6 — never deleted; OQ-1 KDoc intact).

#### Acceptance (AC-RR-7 + AC-RR-8)

- **Per-class grep (Spec 1 §9.6 End-of-Block-Cleanup-Check):** the 4 class `.kt` *sources are deleted* (`find app/src -name '<Class>.kt'` → empty). `grep -rn <Class> app/src` is non-zero ONLY for: (a) `@see`/KDoc doc-anchor pointers in sibling render classes (intentional historical-trail, the same pattern as the parent-C15 `KeyboardLayoutModeController` 5 comment anchors §3 G1 accepted as PASS), and (b) pre-existing audit-ledger string-literal owner-tags in `VisibilityWriteAuditLoggerTest`/`KeyboardLayoutManagerTest`/`RenderGateTest`/`OverlayCharactersControllerTest` (`"KeyboardStateManager"`/`"MainButtonsController"` are arbitrary caller-label strings the ledger keys on — NOT class refs, no compile dependency). **Zero code references** — proven by `./gradlew assembleDebug` GREEN (a residual code ref = loud compile error, the §3 SoT-note + CR-RGATE GREEN §4 explicit AC-RR-7 scope: "loud compile-error not silent regression").
- **AC-RR-8 / Epic AC-7+AC-10 render half:** the new RenderBackend + CR-EXTRACT owners + the 2 new BLEIBT-extract owners (`PipelineStepRowRenderer`/`QwertzRecordingController`) are the sole render/state owners; no bound-path legacy-controller drive remains; `PipelineOrchestrator` reachable only via the C3 adapter (Spec 1 §9.6, untouched).
- **Build + tests BOTH variants GREEN:** `./gradlew assembleDebug` ✅; `./gradlew testDebugUnitTest` **1129/0/0/0**; `./gradlew testReleaseUnitTest` **1129/0/0/16-skipped** (the 16 = `BuildConfig.DEBUG`-guarded audit-logger `assumeTrue`-skips on release, expected per CR-RGATE pattern). The known pre-existing R-7-class `PipelineRunnerSubsystemAdapterTest` testRelease flake **did NOT fire** (0 release failures). **No real regression** (any non-flake failure = gate-RED; none).
- **Net test delta:** baseline 1135 (CR-RGATE = 1130 + 5 `RenderPathCutoverGateTest`) → 1129 = **−6**, exactly `KeyboardUiControllerTest.kt`'s 6 `@Test` methods (the only deleted test; its behaviour-coverage relocates to the new owners' tests in Commit 2). No other coverage loss.
- **CR-RGATE proof still GREEN with controllers physically gone:** `RenderPathCutoverGateTest` **5/5 PASS** + `DictateCutoverE2ETest` **10/10 PASS** + `ContentAreaControllerTest` 8/8 (4th editButtons axis nullable-default didn't break the 3-arg callers) + `PromptVisibilityControllerTest` 15/15 + `MultiCallbackForwardingTest` 5/5 (the `PipelineUiStateReader` adapt-not-delete + the `FakePipelineUiStateReader` re-point held the multi-callback contract).

#### Plan deviations

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| Unbound-fallback branches deleted (no pre-bind render driver) | prompt §5 + chunks.json CR-DEL + CR-RGATE GREEN §4 | CR4 made every legacy drive `pipelineBinder==null`-gated; CR-DEL deletes the classes → those branches deleted too. Pre-bind window has no render driver. | chunks.json CR-DEL + CR-RGATE GREEN §4 explicitly: "CR-DEL deletes the legacy controllers + the unbound branches". The keyboard always binds while open (`bindService` in `onCreateInputView`; `onServiceConnected` re-runs the consolidation point) — the brief pre-bind window is the §6.1 staged-safety-net surface collapsing at the point-of-no-return. `effectiveContentArea()`/small-mode pre-bind return persisted-pref/initial-default (reconciled by the near-instant binder + reactive render). | None — the bound path (the live render path CR-RGATE proved GREEN) is sole owner; the unbound branches were the rollback surface, intentionally retired here. | inline-fixed (the SoT + gate verdict explicitly authorise it; the only spec-faithful CR-DEL outcome) |
| `PipelineUiState.ReprocessStaging.selectedLanguage` field retained as View-side BLEIBT state (not the language-read carrier) | prompt §4 (F-6) + Spec 1 §9.2 | F-6 collapses the *effective-language read* onto `LanguageState.override` (the single SoT); the renderer still mirrors `selectedLanguage` into `ReprocessStaging` for the record-button label + queue-restore. | Spec 1 §9.2 "stepRows bleibt View-side" — the staging-state (incl. its `selectedLanguage` snapshot) IS the View-side BLEIBT the renderer owns. F-6 is about the *cross-carrier read* dual-carrier, which IS collapsed (`resolveEffectiveLanguage` reads only `LanguageState.override`). Keeping the field as relocated View-side state is the spec-faithful BLEIBT, not a re-introduced dual-carrier (nothing reads it for language resolution any more). | None — `PipelineUiStateReader` adapted (Spec 1 §9.6) to point at the relocated `PipelineStepRowRenderer`. | inline-fixed (small + locally-decidable; F-6's intent = single-carrier *read*, achieved; the View-side staging state is the §9.2 BLEIBT) |
| Edit-row theme owned by `EditBarController.applyTheme`/`EmojiController.applyTheme` (not a brand-new theme class) | prompt §2 ("extend `ImeViewBackend.applyTheme` scope, or a small theme owner per §9.2") | Added `applyTheme` to the two CR-EXTRACT owners that already hold the exact edit-bar/emoji `MaterialButton`s, byte-identical legacy tiers. | The prompt offered two options; §9.2 says "separate Theme-Klasse" — satisfied by the owner that also owns the edit-bar listeners (sibling-faithful, no extra class, zero new view-holder, engineering-principles: most sustainable). `ImeViewBackend.applyTheme` is `buttonViews`-(LogicalButtonId)-scoped — extending it to the edit-row would require leaking edit-row views into the logical-button map (worse coupling). | None — no `mainButtonsController.applyTheme` remains. | inline-fixed (small + locally-decidable; the spec-faithful, lower-coupling of the two prompt-offered options) |

#### Issues

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| C10-C3-IMPL-1 | Nice-to-have | Pre-existing audit-ledger test fixtures (`VisibilityWriteAuditLoggerTest`/`KeyboardLayoutManagerTest`/`RenderGateTest`/`OverlayCharactersControllerTest`) use the string literals `"KeyboardStateManager"`/`"MainButtonsController"` as arbitrary ledger owner-tag labels — cosmetically stale post-deletion but NOT class references (no compile dependency; tests green). | open | Not a defect — these are the ledger's caller-label strings, deliberately arbitrary; renaming them is pure churn with zero behaviour change and would touch unrelated pre-existing tests outside CR-DEL's scope (engineering-principles: don't mass-refactor; D7 scope discipline). Documented; left as-is. |

#### Plan-Correctness Fix (B5-C10-C3-IMPL-PLAN-FIX)

Re-read render-path-cutover.md §3 (RR-3 table)/§2.3-2.4 (AC-RR-7/8)/§7 A3/§11 (CR-EXTRACT owners), Spec 1 §9.2/§9.6, Spec 2 §9.2/§9.4/§9.5/§13 (rows 1-2/7-11/13/20), the B5 block-report CR1-CR-RGATE chain + the original B3 C10-C3 trace, and the chunks.json CR-DEL entry against the diff. All 5 prompt deliverables present + spec-faithful: (1) RR-3 trace PASS (the 4 GAPs each got a verified-present IME-attached owner before deletion — the exact discipline that correctly blocked C10-IMPL-2/CR4-IMPL-1); (2) edit-row theme + 4th ContentArea `editButtonsLl` axis owned (CR-RGATE's flagged residual + the trace-surfaced gap); (3) G9→`QwertzRecordingController` / G13→`PipelineStepRowRenderer` extracted A3 option-a (the binding CR3 disposition); (4) F-6 collapsed (effective-language read → single `LanguageState.override` carrier); (5) 4 controllers + test deleted, `PipelineOrchestrator` kept, `KeyboardLayoutModeController` confirmed absent. The 3 plan-deviations above are all small + locally-decidable + SoT-mandated (the gate verdict + chunks.json + Spec §9.2/§9.6 explicitly resolve each) → inline-fixed + documented, no delegation. **No architecture-conflict** — the RR-3 trace PASSED (no responsibility stranded); the deletion is provably safe (every behaviour ported to a present owner, build+tests GREEN both variants). mid-chunk-triage NOT needed.

#### Self-Code Fix (B5-C10-C3-IMPL-CODE-FIX)

Loaded engineering-principles. Code-quality pass:
- The 2 new owners (`PipelineStepRowRenderer`/`QwertzRecordingController`) are **byte-equivalent relocations** of the still-live parts of the deleted classes — special chars verified identical codepoints (U+2713 ✓ / U+2715 ✕ / U+21B5 ↵ / U+2026 …), all method bodies copied verbatim with only the package + the `stateManager.refresh()` seam (→ a no-op `onPipelineUiStateChanged` ctor lambda, since visibility is now reactive off `PromptVisibilityController`) changed. Each carries a full class-KDoc explaining *why it exists* (CR-DEL/RR-3/A3 option-a + the SoT §-refs) so the next reader does not reverse-engineer the extraction.
- `EditBarController.applyTheme`/`EmojiController.applyTheme` reuse the existing `EditBarViews`/`EmojiViews` holders (no new class/holder — DRY, lowest coupling) with the exact legacy tiers documented (and the deliberate `pipelineCancelButton`-not-themed parity noted).
- `ContentAreaViews.editButtonsContainer` is nullable-with-`null`-default so the 30+ existing 3-arg test/Kotlin callers stay byte-identical (backward-compatible extension, no test churn) — verified: `ContentAreaControllerTest` 8/8 unchanged.
- Removed the now-invalid `@Override` from the 10 RECORD/RESEND/BACKSPACE/TRASH/PAUSE/ENTER affordance methods (they were `MainButtonsController.Callback` overrides; now plain IME methods invoked via `imeSideAffordance`/`SpecialTouchHandlerInstaller`) — each annotated with why it survives + who calls it (audit-trail intact). The 13 `EditBarController.Callback`/`EmojiController.Callback` methods correctly keep `@Override` (those interfaces still implemented).
- Fixed every dangling `{@link KeyboardUiController/KeyboardStateManager}` javadoc to point at the relocated owner / `{@code}` so the IME's docs stay accurate (no broken cross-refs). Cleaned the `KeyboardVisibilityPredicates`/`PipelineUiStateReader` historical comments to name the relocated owners.
- C10-C3-IMPL-1 (stale ledger-label test strings) left documented — not a defect, renaming = out-of-scope churn (D7).
`./gradlew assembleDebug` + both test variants GREEN after the fixes (1129/0/0).

#### Tests (B5-C10-C3-IMPL-TEST)

Added 24 unit tests for the CR-DEL production-diff (the new owners + the relocated behaviour + the new theme/4th-axis):

- `state/render/PipelineStepRowRendererTest.kt` (**new**, +14, Robolectric K-4-justified) — the relocated `KeyboardUiControllerTest` (its 6 deleted methods' coverage: pipeline-state guard, the 4-branch RecordingState `when`, Active.useBluetooth split, Paused carry-over) **plus** the new seam + machinery: `startPipeline`/`addRunningStep`/`completeStep`/`stopPipeline` (step-row inflate + state), `toggleAutoEnter` Running-only, the `onPipelineUiStateChanged` no-op seam fires on real state change (the replacement for the deleted `KSM.refresh()`), the ReprocessStaging View-side BLEIBT carrier (incl. the F-6 note: `selectedLanguage` is staging-state, not the language-read carrier), the multi-callback `PipelineUiStateReader` contract (idempotent add / remove-stops-notify), and that the renderer **is-a** `PipelineUiStateReader` (Spec 1 §9.6 adapt-not-delete).
- `state/render/QwertzRecordingControllerTest.kt` (**new**, +5, Robolectric K-4) — the G9 BLEIBT QWERTZ rec-button (active=send-icon / inactive=mic-icon), `enterPipelineDisplay`+`updatePipelineTimer` (n/m counter + the U+21B5 ↵ autoEnter indicator), `onTimerTick` two-line button, the prompts-recording-controls activate/reset (onSend/onPauseToggle wiring), and the null-rec-button safe-no-op (QWERTZ view not inflated).
- `EditBarControllerTest` (+1) — `applyTheme` byte-equivalent legacy tiers (editKeyboard=accentDark, the other 9 = accentMedium, `pipelineCancelButton` deliberately NOT themed — legacy parity), via a K-1 handwritten `setBackgroundColor`-capturing `MaterialButton`.
- `EmojiControllerTest` (+1) — `applyTheme` (editEmoji=accentMedium, emojiPickerClose=accent, legacy `:421/:424` parity).
- `ContentAreaControllerTest` (+3) — the 4th `editButtonsContainer` axis (VISIBLE in MAIN_BUTTONS/QWERTZ, GONE in EMOJI — byte-identical to the deleted KSM rule), the nullable-default backward-compat no-op (3-arg holder unchanged), and the dormant-gate routing (RR-2 — the 4th axis also goes through the gate).

Plus the stale `KeyboardUiController` doc-comments in `MultiCallbackForwardingTest`/`FakePipelineUiStateReader` re-pointed to `PipelineStepRowRenderer`.

**Test counts.** `./gradlew testDebugUnitTest`: **1153 / 0 / 0 / 0**. `./gradlew testReleaseUnitTest`: **1153 / 0 / 0 / 16-skipped** (the 16 = `BuildConfig.DEBUG`-guarded audit-logger `assumeTrue`-skips on release — expected per CR-RGATE; not a regression). Net since the CR-RGATE 1135 baseline: −6 (`KeyboardUiControllerTest` deleted) +24 (new) = **+18**; behaviour-coverage **net-increased** (the relocated owners are more thoroughly tested than the deleted class was). The known pre-existing R-7-class `PipelineRunnerSubsystemAdapterTest` testRelease flake **did NOT fire** (0 release failures).

##### Code-Bugs Found While Writing Tests

None in production. One **test-harness bug** found + fixed inline (not a production bug): the first `EditBarControllerTest.applyTheme` cut assigned every capturing button `id = captured.size + 5000` (always 5000 — `captured` empty at construction → id collision, all writes overwrote one key). Fixed to a `capId++` counter. Production `EditBarController.applyTheme` was correct on first run once the test ids were distinct (the legacy-tier assertions then passed).

#### Test-Review (B5-C10-C3-IMPL-TEST-FIX)

Requirement coverage complete — every CR-DEL deliverable has ≥1 direct assertion: the RR-3-trace GAP resolutions (`PipelineStepRowRenderer` pipeline-UI/staging, `QwertzRecordingController` QWERTZ, `EditBar`/`Emoji` `applyTheme`, `ContentArea` 4th axis), the relocated `KeyboardUiController` behaviour (byte-equivalent, the 6 deleted methods' coverage relocated + extended), the `onPipelineUiStateChanged` seam (the deleted `KSM.refresh()` replacement), the F-6 boundary (staging `selectedLanguage` is View-side BLEIBT, asserted as such), and the backward-compat of the nullable 4th `ContentAreaViews` field (no churn to the 30+ existing 3-arg callers — `ContentAreaControllerTest` pre-existing 8 unchanged). The load-bearing CR-RGATE proof (`RenderPathCutoverGateTest` 5/5, `DictateCutoverE2ETest` 10/10) stays GREEN with the controllers physically gone. K-1 honoured (handwritten capturing `MaterialButton` / `PipelineUiCallback` fakes — no Mockito); K-4 Robolectric is the justified view-mutation exception (per-class KDoc). No code-bugs surfaced during review. Full suite re-run GREEN both variants (1153/0/0).

#### Overlooked points / known gaps

- **G9 amplitude/timer side-channel (inherited C5-IMPL-2 cosmetic deferral, NOT closed here).** `imeViewBackend.onAmplitude/onTimerTick` (→ `RecordingAnimationController`) still has **no caller on the bound path** — the recording BorderGlow/timer on the new path stays Idle (the legacy `recordingStateController` is never started, C5). `QwertzRecordingController.onAmplitude/onTimerTick` are now the relocated owner of the *QWERTZ-visualizer* half but they too are only driven by the QWERTZ/pipeline callbacks, not by a recording amplitude side-channel. This is the **same documented cosmetic gap CR4-IMPL Overlooked + CR-RGATE §"Overlooked" already flagged** (the FGS notification is the authoritative recording-active surface, C5 KDoc) — it is **out of CR-DEL's RR-3/deletion scope** (CR-DEL ports the *render owners*; wiring the missing `AmplitudeStreamAdapter`/`RecordingTimerAdapter` → `imeViewBackend.onAmplitude` service-side bridge is the unchanged C5-IMPL-2 follow-up, not a regression introduced by the deletion). Flagged for B5 AUDIT / Phase-4 awareness, unchanged status.
- **Stale ledger-label test strings (C10-C3-IMPL-1, Nice-to-have, open).** `"KeyboardStateManager"`/`"MainButtonsController"` string literals in 4 pre-existing audit-ledger test fixtures — not class refs, tests green; renaming is out-of-scope churn (D7). Documented in the Issue Index.
- **`uiController.*` reads were NOT a separate problem** — CR4's "Overlooked: ~22 `uiController.*` reads not re-pointed" is **resolved by this chunk**: every one is now `pipelineStepRowRenderer.*` (the relocated owner is the same `PipelineUiState` SoT the IME pipeline-UI bookkeeping needs; the orchestrator still owns the authoritative `state.pipeline` for the reactive visibility). No dangling reader.
- The pre-bind (unbound) window has no render driver by design (the §6.1 staged-safety-net rollback surface collapses at the point-of-no-return — chunks.json CR-DEL + CR-RGATE GREEN §4 explicitly authorise this). The keyboard always binds while open; `effectiveContentArea()`/small-mode pre-bind return persisted-pref/initial-default, reconciled by the near-instant binder + reactive render.

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
