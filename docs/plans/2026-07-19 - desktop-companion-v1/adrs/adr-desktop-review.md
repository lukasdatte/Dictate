# ADR-NNNN: Desktop Review Mode — The Full Review Panel Incl. Re-Dictate on the Companion, Revising the "Review is IME-Only" Rule of ADR-0013 / ADR-0027-F8

**Status:** Proposed (plan-scoped — pending promotion)
**Subsystem:** companion, ai, state, ui
**Date:** 2026-07-20
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Revises a sub-aspect of ADR-0013 and ADR-0027.** ADR-0013 built the review
> panel as an in-keyboard (IME) surface; ADR-0027-F8 reaffirmed that "review stays
> IME-only" for the PC-Dictation Activity. This ADR does **not** supersede either
> ADR wholesale — the `ReviewDecision` rule and the ambiguity modes are unchanged.
> It revises only the **"review can only be rendered inside the IME"** constraint,
> so the desktop host gets its own review surface. Both parent ADRs receive a
> Decision-History note at promotion recording the desktop exception; all three
> cross-reference here.

> **Plain-language summary.** "Review mode" is Dictate's clarify-by-conversation
> feature: when the AI thinks your dictation is ambiguous, instead of guessing it
> shows a panel where you see its question and its draft, and you can **accept**,
> **discard**, or **re-dictate** a clarification by voice. On Android this panel is
> part of the keyboard. This ADR gives the **desktop companion the full review panel
> too, including re-dictate**, rather than a cut-down "just insert or discard". The
> logic that decides *whether* to review (`ReviewDecision`) is shared and unchanged;
> only the *rendering surface* is new. Jargon: **re-dictate** = record a short spoken
> clarification that runs as another conversation turn without re-transcribing from
> scratch; **`ReviewDecision`** = the pure rule that returns INSERT or REVIEW.

## Research

- **Desktop-host spec** (`docs/plans/2026-07-19 - desktop-companion-v1/research/desktop-host.md`):
  §5.2 the `DictationPhase` model with the REVIEW branch and re-dictate loop; §5.3 the
  `DesktopUiState.review` sub-axis mirroring the ADR-0013 states (`refining`,
  `refinementRecording`); §5.5 step 2/3 (the conversation turn + `ReviewDecision.decide`);
  §8.3 the re-dictate continuation job on the shared serial queue.
- **The rule being revised:** ADR-0013 (Ambiguity Modes + the In-Keyboard Review Panel)
  — the review panel is a `KEYBOARD_REVIEW_PANEL` LayoutMode with Insert / Re-dictate /
  Discard, and `ReviewDecision.decide(mode, needsClarification, message)` is the pure
  verdict; re-dictate starts a transcription-only session (`REVIEW_REFINEMENT`,
  `transcriptionOnly=true`). ADR-0027-F8 kept review IME-only for the PC-Dictation
  Activity (a store-open review panel there just shows a hint back to the IME).
- **Shared pipeline semantics:** ADR-0012 (the persisted post-processing conversation)
  and ADR-0013 §3 (the `final_output_text` crash-resilience invariant) the desktop
  pipeline already mirrors (`adr-desktop-dictation-host`).
- **Concept / decisions:** `.../research/fragenkatalog.md` §F18 (full review mode from
  v1, incl. dictated refinement); `.../research/bestandsaufnahme.md` §8 (existing
  review/ADR-0013 inventory).
- **Plan Decision Log** (`.../desktop-companion-v1.md` §3): F18 (full review incl.
  re-dictate), and the §6 ADR table entry declaring this a partial supersede of
  ADR-0013 / ADR-0027-F8 with a shared `ReviewDecision` authority.

## Context

On Android, review is deeply tied to the IME: it is a keyboard LayoutMode, its state is
an IME `DictateModule` axis, and ADR-0027 explicitly declined to render it inside the
PC-Dictation Activity (that Activity just points the user back to the keyboard). That
"review is IME-only" stance was correct for those hosts, where the review surface and the
keyboard share a render pipeline.

The desktop dictation host (`adr-desktop-dictation-host`) is a different situation: it is
a standalone recording + pipeline host with **no IME at all**. If review stayed IME-only,
a desktop dictation that the AI flags as ambiguous would have nowhere to go — the user
could only accept a possibly-wrong insert or discard. Feature decision F18 requires the
**full** review experience, including dictated refinement, from v1 on the desktop.

The question is whether that means duplicating the `ReviewDecision` logic (risking drift)
or sharing it while adding only a new surface.

## Decision

Give the desktop host the **full review panel including re-dictate**, sharing the
`ReviewDecision` authority and revising only the render-surface constraint.

1. **Shared `ReviewDecision` authority (unchanged logic).** The pure rule
   `ReviewDecision.decide(mode, needsClarification, message)` (ADR-0013) is the **single**
   authority for INSERT-vs-REVIEW on both platforms. The desktop calls it verbatim after
   the post-processing turn (spec §5.5 step 3) — no second copy of the verdict logic.

2. **A desktop review surface (the revised sub-aspect).** `DesktopUiState.review` is a
   sub-axis mirroring the ADR-0013 review states (`message`, `output`, `refining`,
   `refinementRecording`). The Compose panel renders Accept / Re-dictate / Discard. This
   is what revises "review is IME-only": the review *panel* is no longer bound to the IME
   render pipeline — a non-IME host may render it.

3. **Full re-dictate on the shared queue (spec §5.2/§8.3, F18).** "Re-dictate" starts a
   transcription-only refinement recording whose transcript runs as another conversation
   turn (ADR-0012), staying in REVIEW; the continuation job runs through the **same** serial
   `JobQueue` (ADR-0009 semantics) as the primary pipeline. The `final_output_text`
   invariant (ADR-0013 §3) is preserved across every turn for crash-resilience.

4. **No new persisted status or verdict.** Desktop REVIEW maps onto the existing
   `sessions.status = COMPLETED` with the review held in UI state, exactly as the phase
   model does (`adr-desktop-dictation-host`) — Room/SQLDelight parity, no new vocabulary.

5. **The IME hosts stay as they were.** ADR-0013's in-keyboard panel and ADR-0027's
   Activity behaviour are unchanged; the Activity still points back to the IME for review.
   Only the desktop host gains its own panel. Parent ADRs get a Decision-History note at
   promotion; this ADR and both parents cross-reference.

## Alternatives Considered

1. **Keep review IME-only; the desktop gets insert-or-discard only.** The literal ADR-0013
   / ADR-0027-F8 stance. Rejected (F18): it strands ambiguous desktop dictations — the user
   cannot clarify, only accept or throw away, which is the exact frustration review exists
   to remove. The desktop is a standalone host with no IME to fall back to.
2. **Duplicate `ReviewDecision` and the review flow as desktop-specific code.** Rejected:
   two copies of the verdict rule drift over time (a change to ambiguity handling would have
   to be applied twice, silently divergent otherwise). Sharing the pure rule and adding only
   a surface keeps one authority.
3. **Defer re-dictate to a later version (review = accept/discard only in v1).** Rejected
   (F18): dictated refinement is the core value of review; a review panel without it is the
   weak half. The continuation job reuses the existing queue, so re-dictate is not
   disproportionately expensive.
4. **A full supersede of ADR-0013 / ADR-0027.** Rejected: those ADRs are correct for their
   hosts and their other decisions (ambiguity modes, the Activity's render-host design)
   stand. Only the "IME-only render surface" sub-aspect changes, so a partial revision with
   cross-references is the honest, minimal move.

## Consequences

**Positive:**
- Desktop dictation gets the complete clarify-by-voice experience from v1 — ambiguous
  results are recoverable, not a guess-or-discard choice.
- One `ReviewDecision` authority across phone and desktop means ambiguity behaviour cannot
  drift between platforms.
- Re-dictate reuses the existing serial queue and conversation machinery, so the new surface
  is mostly rendering, not new pipeline logic.

**Negative:**
- The "review is IME-only" invariant is now conditional — a future reader must know the
  desktop is the documented exception, which is why both parent ADRs carry a note pointing
  here.
- A second review *surface* (Compose panel) to build and keep visually/behaviourally
  consistent with the Android panel, even though the decision logic is shared.

**Failure Modes:**
- **A desktop re-dictate that re-transcribes from scratch instead of running a refinement
  turn** would lose the conversation context — the continuation must be
  `transcriptionOnly=true` and feed another turn (ADR-0012), not a fresh pipeline.
- **Dropping the `final_output_text` write during a held review** loses the recoverable text
  on a crash (the ADR-0013 §3 bug); every desktop turn must persist it in the same
  transaction.
- **A second `ReviewDecision` copy sneaking into desktop code** re-introduces drift; the rule
  must be imported from the shared location, never re-implemented.
- **Re-dictate jobs bypassing the serial queue** could interleave with the primary pipeline
  and reorder inserts; the continuation must go through the same `JobQueue`.

## References

- **Related Plan:** [desktop-companion-v1](docs/plans/2026-07-19 - desktop-companion-v1/desktop-companion-v1.md)
  — §3 (F18), §5 Block D, §6 ADR table (partial-supersede note). Motivates and is implemented
  by this ADR.
- **Spec:** `docs/plans/2026-07-19 - desktop-companion-v1/research/desktop-host.md`
  (§5.2 phase model + REVIEW, §5.3 review sub-axis, §5.5 verdict, §8.3 re-dictate).
- **Concept:** `.../research/fragenkatalog.md` §F18; `.../research/bestandsaufnahme.md` §8.
- **Related ADRs:**
  - ADR-0013 — **revised (sub-aspect):** the review panel is no longer strictly IME-only;
    the `ReviewDecision` rule and ambiguity modes are reused unchanged. Decision-History note
    added there at promotion.
  - ADR-0027 — **revised (sub-aspect):** F8's "review stays IME-only" gains a documented
    desktop-host exception. Note added there at promotion.
  - ADR-0012 — the persisted post-processing conversation re-dictate feeds a turn into.
  - ADR-0009 — the serial queue the re-dictate continuation runs on.
  - `adr-desktop-dictation-host` — the host whose REVIEW verdict this surface consumes.

## Decision History

### 2026-07-20 — Initial proposal (plan-scoped)

**Trigger:** Feature decision F18 (full review incl. dictated refinement on the desktop from
v1) collided with the ADR-0013 / ADR-0027-F8 "review is IME-only" rule; the desktop-host spec
resolved how to add a review surface without duplicating the verdict logic.

**Before:** Review was an in-keyboard (IME) surface (ADR-0013); ADR-0027-F8 kept it IME-only
even for the PC-Dictation Activity. The desktop host — which has no IME — therefore had no
place to render review.

**After:** The desktop host renders the full review panel (Accept / Re-dictate / Discard) as a
`DesktopUiState.review` sub-axis, calling the shared `ReviewDecision` authority unchanged and
running re-dictate as a conversation-turn continuation on the shared serial queue. The
"IME-only render surface" sub-aspect of ADR-0013 / ADR-0027-F8 is revised (not superseded);
both parents cross-reference and get a Decision-History note at promotion.

**Reasoning:** A standalone desktop host with no IME must be able to clarify ambiguous
dictations (F18), so review cannot stay IME-only; but the verdict logic is correct and shared,
so only the render surface is new. Sharing `ReviewDecision` prevents cross-platform drift; a
partial revision with cross-references is more honest than either a full supersede or a
duplicated flow.
