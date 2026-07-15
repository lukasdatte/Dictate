# ADR-0022: Edit-Bar Slot Widths Derived at Measure Time, with a Forced Peek

- **Status:** Accepted
- **Date:** 2026-07-15
- **Subsystem:** ui-rendering
- **Scope:** Subsystem

## Summary in plain language

The row of small buttons above the keyboard used to divide the screen width by
the number of buttons, so each new button made all of them narrower — with
nothing stopping it before the icons collided. It now scrolls, and whenever it
scrolls it deliberately cuts the last visible button in half so the user can
see there is more to the right.

## Research

- The bar was a ConstraintLayout chain of `0dp` (match-constraint) buttons: the
  viewport was split evenly, unconditionally.
- Adding the widget, screen-context and PC toggles takes it from 12 to 14
  buttons — past what a 320dp phone can show at a usable touch target.
- Android has no `IntersectionObserver`: "is this row overflowing?" must be
  computed, not observed.
- A scroll container alone is insufficient: if the visible buttons happen to end
  flush with the viewport, the row reads as complete and the rest is never
  discovered.
- Repo precedent for horizontal scrolling in the keyboard: `prompts_keyboard_rv`
  (RecyclerView). Precedent for list-shaped rendering: `PipelineStepAdapter`
  (`ListAdapter` + DiffUtil).

## Context

ADR-0004 scopes the LayoutCatalog convention to `RenderBackend` surfaces and
explicitly exempts "pre-refactor surfaces that are not state-driven" — the
edit-bar is one. So a rebuild is permitted but not prescribed.

`EditBarController` carries a single-owner/dormant ledger from the render-path
cutover; `EditBarViews` also holds `pipelineCancelButton`, which lives in a
different layout entirely; `edit_emoji_btn`'s listener is owned by
`EmojiController`.

## Decision

1. **`PeekingButtonBar`** — a `HorizontalScrollView` subclass wrapping one
   `LinearLayout` row — sizes its slots in `onMeasure`.
2. **`EditBarWidthCalculator`** is a **pure object**: the peek rules are the
   interesting part and get exhaustive JVM tests rather than one Robolectric
   sample (the K-4 reasoning that keeps `LayoutStrings` out of the resolvers).
3. **Two rules.** Everything fits at the floor → split evenly, no peek (the
   pre-existing behaviour, so the common case looks unchanged). Overflow →
   reserve a ≥12dp sliver **first**, then divide the remainder across whole
   slots. "Looks exactly fitting" becomes unreachable by arithmetic rather than
   by a tuned constant.
4. **52dp minimum slot** — a 44dp touch target plus a 4dp gap per side. Hard
   floor: on a viewport too narrow for one slot plus a sliver, the floor wins
   and the peek degrades.
5. **The gap is an inset, not a margin**, so `rowWidth == count * slotWidth`
   stays exact and the arithmetic needs no per-child correction.

## Alternatives

- **RecyclerView + ListAdapter** (the plan's first instinct) — rejected.
  Recycling and diffing buy nothing for 14 permanently-visible buttons, and an
  adapter would have forced a rewrite of the single-owner ledger, `EditBarViews`
  (including a button from another layout), and `EmojiController`'s separate
  ownership — a large blast radius on a working, tested component for no
  functional gain. The actual pain (adding a button = re-threading a constraint
  chain) is gone either way: it is now an appended child.
- **Keep the ConstraintLayout chain, shrink further** — rejected: no bound, and
  no width at which the row admits buttons exist off-screen.
- **Detect overflow after layout and adjust** — rejected: a second layout pass
  and a frame of visible wrong state, to compute what arithmetic already knows.

## Consequences

### Positive
- Adding a button is appending a child; the peek follows automatically.
- The peek rules are covered for every viewport 200–2000px × 12–15 buttons.
- Reusable: `PeekingButtonBar` is not edit-bar-specific.

### Negative
- Overflowing means some buttons need a scroll to reach.
- A `HorizontalScrollView` inside the keyboard's ConstraintLayout adds one
  measure level.

### Failure Modes
- **Margins on a child** would silently break the `rowWidth == count *
  slotWidth` identity and skew the peek. Documented at the dimen, the class,
  and the XML.
- **Very narrow viewport** (< one slot + sliver): the floor wins, the peek
  shrinks, possibly to zero. Unreachable on real hardware (320dp leaves ~5
  full slots).
- RTL peek direction is untested in unit tests — on the device checklist.

## References

- Plan: `tmp/plan-a11y-widget-pcmode.md` Block B3
- ADR-0004 (LayoutCatalog + MotionLayout) — the scope clause that exempts this
  surface
- ADR-0010 (icon tint via theme attrs) — the keyboard's programmatic exemption

## Decision History

- **2026-07-15** — Initial draft alongside the B3 implementation.
  - **Trigger:** The a11y + PC toggles pushing the bar past its silent limit.
  - **Reasoning:** Recorded because the "reserve the peek first" rule looks like
    an arbitrary constant unless the alternative (check afterwards, and fail on
    exact fits) is written down.
