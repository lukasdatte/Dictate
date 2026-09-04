---
date: 2026-08-11
author: Lukas + Claude (analysis session)
status: Research
context: Fork-vs-upstream analysis of the QWERTZ full keyboard and the keyboard-ergonomics modes — what we built, what Dictate Keyboard 5.3 has, and whether the feature is still worth pursuing.
related-adrs: ADR-0022 (edit-bar overflow peek), ADR-0004 (LayoutCatalog + MotionLayout), ADR-0010 (icon tint via theme attrs), ADR-0026 (keyboard-action routing)
---

# QWERTZ Full Keyboard & Keyboard Ergonomics — Fork vs. Upstream Analysis

We turned a dictation overlay into a keyboard: `d6e2b769` replaced Dictate 3.2's numpad-style
special-character surface with a full German QWERTZ typing layout, and the surrounding chrome
then grew Small Mode, Single-Row Mode, a dedicated edit toolbar and — under ADR-0022 — a
measure-time slot calculator that deliberately cuts the last visible button so the user learns
the row scrolls. Roughly 5,000 lines and the fork's single largest product bet.
Dictate Keyboard 5.3 is **FlorisBoard with a dictation layer grafted on**, which makes this
cluster **obsolete by design**: upstream inherits 77 character layouts, glide typing, a
beam-search autocorrect, German noun capitalisation and umlaut restoration. This document's job
is to say that plainly and then check whether *anything* survives the write-off.

## 1. Feature Overview

Dictate 3.2 was a mic with an overlay. Its only text-input surface besides dictation was a small
grid of special characters, so the moment a transcript needed one word corrected the user had to
switch to another keyboard, fix it, and switch back. For a dictation-first workflow that is a
modal trap: the app that produces the text cannot repair it. `d6e2b769` closed that by building
a permanent full keyboard — three letter rows, a numeric/symbol layer behind a `123` key, a
shift/caps state machine, tab, a close-keyboard key, accelerating key repeat on backspace,
cursor-swipe on the space bar and per-key press animation. It changed what Dictate *is*: from a
dictation overlay into a general keyboard that also dictates.

The ergonomics work is the second half and it is a direct consequence of the first. A full
keyboard eats vertical space, so **Small Mode** collapses the upper UI sections when the
keyboard would push the host app's message field off-screen, and **Single-Row Mode** compresses
the two button rows into one. The mode toggles themselves outgrew the main row, so they moved
into a dedicated **edit toolbar**, joined by a keyboard show/hide toggle and an **audio-focus
runtime toggle** that stops Dictate ducking or pausing media while it records.

That toolbar then became its own problem, and the fix is the most interesting artefact in the
cluster. The bar was a ConstraintLayout chain of `0dp` buttons: the viewport was divided evenly
and unconditionally, so going from 12 to 14 buttons pushed icons below a usable touch target on
a 320 dp phone. ADR-0022 rebuilt it as a scrolling `PeekingButtonBar` whose slot widths are
derived in `onMeasure` against a 52 dp floor, with one deliberate rule: when the row overflows,
a ≥ 12 dp sliver is reserved **before** the remainder is divided, so the last visible button is
always cut. A row that ends flush with the viewport reads as complete and the rest is never
discovered — the peek makes "looks exactly fitting" unreachable by arithmetic instead of by a
tuned constant.

## 2. Our Implementation

**The keyboard itself** — `keyboard/`, 11 Kotlin files, 1,887 LOC:

| File | LOC | Role |
|---|---|---|
| `QwertzKeyboardView.kt` | 358 | custom `ViewGroup`, key hit-testing and drawing |
| `QwertzLayoutProvider.kt` | 341 | the layouts as data (`QWERTZ`, `NUMBERS`, `SYMBOLS`) |
| `BackspaceSwipeHandler.kt` | 339 | swipe-to-delete with word/character granularity |
| `QwertzKeyboardController.kt` | 310 | shift/caps state machine, layer switching |
| `EnterOverlayHandler.kt` | 100 | enter-key role overlay |
| `VerticalDragResizeHandler.kt` | 99 | reusable drag-resize primitive (later reused by the history panel) |
| `AcceleratingRepeatHandler.kt` | 85 | key repeat with acceleration |
| `QwertzKeyDef.kt` | 83 | the key data model |
| `KeyPressAnimator.kt` | 80 | per-key press animation |
| `CursorSwipeTouchHandler.kt` | 79 | space-bar cursor swipe |
| `QwertzKeyboardLayout.kt` | 13 | the three-value layer enum |

`QwertzLayoutProvider` is a plain `object` returning `List<List<QwertzKeyDef>>` per layer and
shift state, with the German row hardcoded (`ß` on the number row, umlauts in the letter rows).
There is **one** character layout, no per-language subtypes, no dictionary, no suggestions, no
autocorrect and no glide typing — worth stating explicitly because it is the whole comparison.

**The chrome around it.** `core/KeyboardLayoutModeController.kt` and `core/AudioFocusGate.kt`;
prefs `SmallMode`, `SingleRowMode`, `AudioFocus`; `widget/PeekingButtonBar.kt` (127) and the
pure `widget/EditBarWidthCalculator.kt` (129); `state/render/EditBarController.kt` (511). The
first commit deleted 263 lines of hand-placed buttons from
`res/layout/activity_dictate_keyboard_view.xml` and replaced them with the keyboard view.

**Plans and ADRs.** [`../../../plans/qwertz-keyboard-plan-v1-implemented.md`](../../../plans/qwertz-keyboard-plan-v1-implemented.md)
and [`../../../plans/qwertz-ui-integration-fix.md`](../../../plans/qwertz-ui-integration-fix.md);
[ADR-0022](../../../decisions/0022-editbar-overflow-peek.md) for the peek. `9e563180` (16 files,
+1,332 / −113) is notable as the commit that shipped the repository's **first real unit tests** —
the ergonomics work is where the fork's test culture starts.

**Coupling.** Mechanically the `keyboard/` package is portable: a self-contained custom
`ViewGroup` plus a controller driven by a `KeyDef` layout provider, liftable into any IME. In
product terms it is a hard fork divergence rather than an upstreamable patch. Its real weight is
positional: it owns the top-level layout XML that every later UI cluster builds on, two of the
eight `LayoutCatalog` modes are its row modes (`KEYBOARD_TWO_ROW`, `KEYBOARD_SINGLE_ROW`, plus
their send-mode variants), and [ADR-0026](../../../decisions/0026-keyboard-action-routing.md)
routes every key action through the `InsertionService` façade so PC-mode can divert it. Removing
the keyboard is therefore not a subtraction — it is a re-architecture of the surfaces above it.

## 3. Upstream 5.3 Status

**Verdict: obsolete-by-design.** The premise of the entire cluster is dissolved by the rewrite.
Upstream imported the complete FlorisBoard source and built Dictate as an additive layer on top,
so the typing stack it inherits is a strict superset of anything we could have written:

- **77 character layouts** under
  `app/src/main/assets/ime/keyboard/org.florisboard.layouts/layouts/characters/`, including
  `qwertz.json`, `german.json`, `german2.json` and `dvorak_de.json` — against our one hardcoded
  German layout.
- **Glide typing** with per-language downloadable dictionaries (`#127`, `47eceb3b`…`aec8d614`),
  word suggestions, spell check, and an autocorrect rebuilt in 5.3 as a **touch-coordinate
  beam-search decoder** (`d500ccbf`, `#242`) that moved their test-set accuracy from 88 % to
  98 %.
- **German-specific correction**: noun capitalisation (`9cdb7a8b`) and umlaut/ß restoration
  (`d92c3b6c`) — precisely the German-typing quality our keyboard has no mechanism for.
- **Next-word prediction with long-press-to-learn** (`eb1ac0d5`, `#241`/`#245`), emoji keyboard
  with search, clipboard manager, themes.

The ergonomics half fares no better, and this is the part the upstream report does not spell
out. Verified directly in the upstream worktree:

- `ime/text/gestures/SwipeAction.kt` declares **31 configurable swipe actions**, including
  `DELETE_WORD` / `DELETE_WORDS_PRECISELY`, the four `MOVE_CURSOR_*` directions plus
  start/end-of-line and start/end-of-page, `SELECT_*_PRECISELY`, `UNDO`/`REDO`. Our
  `CursorSwipeTouchHandler` and `BackspaceSwipeHandler` are two hardcoded instances of what
  upstream exposes as a user-configurable gesture matrix.
- `ime/input/InputEventDispatcher.kt` implements repeatable key codes with a per-code repeat
  delay (`:76`, `:112-114`) — our `AcceleratingRepeatHandler` in generic form.
- `TOGGLE_COMPACT_LAYOUT` (one-handed / compact) and `TOGGLE_SMARTBAR_VISIBILITY` are swipe
  actions, i.e. our Small Mode's problem solved as a first-class gesture.
- `ime/smartbar/SmartbarLayout.kt` is an enum of `SUGGESTIONS_ONLY`, `ACTIONS_ONLY`,
  `SUGGESTIONS_ACTIONS_SHARED`, `SUGGESTIONS_ACTIONS_EXTENDED` — the structural analogue of our
  Two-Row / Single-Row modes, as a user preference rather than a mode toggle.
- **ADR-0022's problem is solved upstream, differently and better.**
  `ime/smartbar/quickaction/QuickActionArrangement.kt:44-47` partitions actions into
  `stickyAction` / `dynamicActions` / `hiddenActions`, rendered by `QuickActionsRow` with a
  `QuickActionsOverflowPanel` for the hidden set and a `QuickActionsEditorPanel` for
  drag-and-drop rearrangement. Where we compute a peek so the user infers there is more, they
  let the user decide what is visible at all.

And upstream implements the **inverse** of our bet. `dictate/DictateLegacyLayout.kt:26-33`
defines modes `OFF | LOCKED | SWIPE` bringing back "the compact record-first UI from Dictate 3.x
that several dictation-only users asked to have back" (`#125`): `LOCKED` never shows the typing
keyboard at all, `SWIPE` puts it one horizontal swipe away. It was later enriched with a
drag-and-drop-configurable action row, long-form controls and a two-row prompt strip
(`5c9013d2`, `#183`/`#194`). Users asked DevEmperor for *less* keyboard; he built a way to hide
it. We built a keyboard.

**What upstream lacks vs. ours in this cluster: effectively nothing.** The honest search turns up
one candidate — the specific forced-peek heuristic, which is a defensible affordance upstream's
partition model does not reproduce (an overflow panel is discoverable by its own affordance
rather than by a cut button). That is a design opinion, not a capability.

## 4. Assessment — is this feature still sensible?

**Would a user on 5.3 miss ours? No — they would be relieved.** This is the cleanest verdict in
the whole comparison. A German QWERTZ typist on our fork gets a layout with no autocorrect, no
umlaut restoration, no noun capitalisation, no suggestions and no glide. The same typist on 5.3
gets all of it plus 76 other layouts. There is no dimension on which our keyboard is better and
no user for whom it is preferable.

**But "retire" does not mean "delete".** Inside the fork this cluster is not optional: it is the
only typing surface the app has. Removing it returns us to the 3.2 modal trap where correcting
one word means switching keyboards. So the realistic framing is *sunk cost, no further
investment* — keep it working, do not extend it, and let the migration question decide its fate
rather than treating it as a feature with a roadmap.

**What it costs to keep.** Moderate but non-zero, and structural rather than volumetric. The
1,887 LOC are stable and rarely touched; the cost is positional. The keyboard owns the top-level
layout XML, so every later UI cluster's changes route around it; it contributes four of the
eight LayoutCatalog modes; and ADR-0022 is itself evidence of the pattern — the edit bar had to
be rebuilt not because it was wrong but because the surface kept growing and the original design
had no bound. Each new toggle in the fork lands in a row that this cluster owns.

**The argument for having built it anyway.** It is worth recording that this was not a mistake at
the time. In January 2026 upstream was still the Java 3.2 codebase; the FlorisBoard import
(`266a1c0e`) was not visible as a direction, and the modal trap was a real daily cost. The bet
lost to an event, not to bad judgement. It also produced the fork's first unit tests
(`9e563180`) and two genuinely reusable primitives, which is a better salvage rate than a total
write-off implies.

**What actually survives.** Three things, and it is worth being precise because the temptation is
to over-claim:

1. **`EditBarWidthCalculator` (129 LOC, pure) + `PeekingButtonBar` (127 LOC).** Cleanly liftable,
   exhaustively tested for every viewport 200–2000 px × 12–15 buttons, and genuinely reusable —
   ADR-0022 says so explicitly. Their reuse value is *inside our fork or another project*, not
   upstream, because upstream's quick-action partitioning solves the same product problem in a
   way that does not need a peek.
2. **`VerticalDragResizeHandler` (99 LOC).** Already earning its keep outside this cluster — the
   in-keyboard history panel's drag-to-resize is built on it.
3. **The test culture.** Not code, but `9e563180` is where the fork stopped shipping untested UI.

Everything else — the layout provider, the key view, the controller, the swipe and repeat
handlers, Small Mode, Single-Row Mode — has a strictly better upstream counterpart.

**Upstream signal.** Unambiguous and not in our favour. `#125` (`DictateLegacyLayout`) is the
recorded community request in this space, and it asks for the keyboard to get *out of the way*.
Nothing about our QWERTZ work is proposable upstream; the capability is already a superset there
and the design direction is the opposite one.

## 5. Options going forward

**(a) Keep in fork as-is.** Not really optional while the fork ships — it is the only typing
surface, and its removal would break the correct-a-word workflow the cluster was built for. The
right posture is maintenance-only: fix what breaks, add nothing.

**(b) Port to Dictate Keyboard 5.x as a private patch.** Incoherent. 5.x already has 77 layouts
and a beam-search autocorrect; adding our single hardcoded QWERTZ layout on top would be
strictly subtractive.

**(c) Propose upstream via issue.** Nothing in this cluster qualifies. The one arguable candidate
— the forced-peek affordance for an overflowing action row — collides with a shipped upstream
design (sticky/dynamic/hidden + editor panel) rather than filling a gap, which the upstream
analysis identifies as exactly the shape of contribution that does not land in a
single-author codebase with a strong design voice.

**(d) Retire / let upstream's equivalent replace it.** The only option that ever removes the
maintenance surface, and it is available for free — but only as a consequence of migrating to
5.x, never as a standalone step. In that scenario the cluster evaporates and the user *gains*
capability, which is unusual enough among the fork's clusters to be worth noting in the
overview.

*Leaning:* treat this as sunk cost — freeze it, salvage `EditBarWidthCalculator` /
`PeekingButtonBar` / `VerticalDragResizeHandler` as reusable primitives, and let the fork-vs-5.x
migration decision dispose of the rest.

## 6. Information Gaps

1. **How much does Lukas actually type on the QWERTZ keyboard versus dictate?** The entire "modal
   trap" justification assumes meaningful typing volume. If corrections are rare and short, the
   cluster's daily value is lower than its build cost suggests, and the write-off is easier.
   *Owner: Lukas.* *Fallback:* none instrumented — the fork tracks pipeline sessions, not
   keystrokes.
2. **Would losing our specific edit-bar layout be acceptable in exchange for autocorrect, glide
   and umlaut restoration?** This is the concrete trade a 5.x migration presents, and it is a
   taste question no amount of code reading answers. *Owner: Lukas.* *Fallback:* treat as "yes"
   — the capability gap is large enough that the layout preference is unlikely to outweigh it.
3. **Does `DictateLegacyLayout` in `SWIPE` mode actually reproduce the fork's ergonomics?** The
   upstream report describes it from source, not from use. If `SWIPE` gives a record-first
   surface with the full keyboard one gesture away, it is arguably *better* than our Small Mode.
   *Owner: whoever installs 5.3.* *Fallback:* assume rough parity; the claim does not change the
   verdict, only its margin.
4. **Do our Small Mode and Single-Row Mode get used at all?** They are prefs (`SmallMode`,
   `SingleRowMode`) with no telemetry. If both sit at their defaults, ~2,700 lines of ergonomics
   work is dead weight independent of the upstream comparison. *Owner: Lukas.* *Fallback:* read
   the two prefs off the device.
5. **What is the real cost of the layout-XML ownership?** The claim that this cluster taxes every
   later UI change is inferred from ADR-0022 and the LayoutCatalog structure, not measured.
   *Owner: Claude.* *Fallback:* count commits touching
   `activity_dictate_keyboard_view.xml` since May 2026.

## 7. References

**Source reports**
- [`../sources/fork-feature-inventory.md`](../sources/fork-feature-inventory.md) — cluster 5
  ("QWERTZ full keyboard & keyboard ergonomics modes")
- [`../sources/upstream-rewrite-analysis.md`](../sources/upstream-rewrite-analysis.md) — area 1
  ("Full QWERTZ keyboard in the IME — obsolete-by-design"), §A.1–A.3

**Our plans & ADRs**
- [`../../../plans/qwertz-keyboard-plan-v1-implemented.md`](../../../plans/qwertz-keyboard-plan-v1-implemented.md)
- [`../../../plans/qwertz-ui-integration-fix.md`](../../../plans/qwertz-ui-integration-fix.md)
- [ADR-0022 — Edit-Bar Slot Widths Derived at Measure Time, with a Forced Peek](../../../decisions/0022-editbar-overflow-peek.md)
- [ADR-0004 — LayoutCatalog + MotionLayout](../../../decisions/0004-ui-layout-catalog-motionlayout.md)
  (the two keyboard row modes)
- [ADR-0026 — Keyboard-Action Routing](../../../decisions/0026-keyboard-action-routing.md)
  (every key action routes through `InsertionService`)

**Our commits**
- `d6e2b769` (15 files, +1,477 / −403) — the QWERTZ keyboard
- `89fa64d0`, `d0709f54`, `70464f17`, `f40d0bd6`, `4d30111a`, `efeb89e2` — keyboard follow-ups
- `716e2696` (+1,344 / −56) — ergonomics modes; `9e563180` (16 files, +1,332 / −113) — the
  fork's first unit tests
- `72d40eab` (inside merge `c8d670d6`) — the ADR-0022 edit-bar peek

**Upstream evidence** (paths relative to the `upstream/main` worktree, `3e5ebe46` / `v5.3.0`)
- `app/src/main/assets/ime/keyboard/org.florisboard.layouts/layouts/characters/` — 77 layouts
  (`qwertz.json`, `german.json`, `german2.json`, `dvorak_de.json`)
- `ime/text/gestures/SwipeAction.kt` — 31 configurable swipe actions
- `ime/input/InputEventDispatcher.kt:76,112-114` — repeatable keys with per-code repeat delay
- `ime/smartbar/SmartbarLayout.kt` — the four smartbar layouts
- `ime/smartbar/quickaction/QuickActionArrangement.kt:44-47` — sticky / dynamic / hidden
  partition; `QuickActionsOverflowPanel.kt`, `QuickActionsEditorPanel.kt`
- `dictate/DictateLegacyLayout.kt:26-33` — `OFF | LOCKED | SWIPE` (the inverse feature)
- Commits: `d500ccbf` (`#242` beam-search autocorrect), `9cdb7a8b` (German noun
  capitalisation), `d92c3b6c` (umlaut/ß restoration), `eb1ac0d5` (`#241`/`#245` next-word
  prediction), `5c9013d2` (`#183`/`#194` legacy-layout action row), `266a1c0e` (the FlorisBoard
  import)
- Issues: `#125` (bring back the keyboard-free layout), `#127` (glide/autocorrect), `#242`/`#244`
  (touch beam decoder)
