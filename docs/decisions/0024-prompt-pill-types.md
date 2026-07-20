# ADR-0024: Explicit Prompt-Pill Type Column Replaces the `[bracketed]` String Convention

**Status:** Accepted
**Subsystem:** database, rewording
**Scope:** Project-Wide
**Date:** 2026-07-15
**Supersedes:** —
**Author:** Lukas + Claude Fable 5

> **Plain-language summary.** The keyboard shows small "pills" above the keyboard.
> Two kinds exist: *prompt pills* (an AI instruction — "make this formal") and
> *text pills* (a fixed snippet inserted verbatim — a greeting, a signature).
> Until now a text pill was recognised only by a naming trick: if the prompt text
> was wrapped in square brackets (`[Beste Grüße]`), the app treated it as literal.
> That trick was checked in several unrelated places and one of them was forgotten
> when the post-processing pipeline was rebuilt, so a text pill could leak its
> literal text to the AI as if it were an instruction. This ADR replaces the
> bracket trick with a real, database-backed pill *type*, so the distinction is
> made once, explicitly, and cannot be forgotten.

## Research

Grounded in the shipped implementation of the `plan-pill-typen` work package
(worktree `feature/pill-types`, commits `[1.1]`, `[2.2]`, `[3.2]`). Load-bearing
facts, verified in code before the change:

- The pill kind had **no data representation**. `PromptEntity`
  (`app/src/main/java/net/devemperor/dictate/database/entity/PromptEntity.kt`) held
  `id, pos, name, prompt, requires_selection, auto_apply` — no type. "Static" was a
  runtime string check `prompt.startsWith("[") && endsWith("]")` in
  `PromptService.isStaticResponse` (without `trim`).
- The check existed in **exactly one path** — `PipelineOrchestrator.runStandalonePrompt`
  (the idle click). Every queue path (record-start auto-apply, reprocess-staging,
  the ADR-0012 consolidated turn) resolved `entity.prompt` **raw** into a
  `TurnInstruction` with no static check (`resolveQueueSlot`), so a `[…]` pill's
  literal text was sent to the model.
- The busy-state greying (`disableNonSelectionPrompts`) greyed **all**
  non-selection prompts, including static ones, even though a static insert needs
  no AI and is safe in every state — so a text pill did nothing on a short-press
  while recording.
- A latent bug: the static short-circuit called `onPipelineFinished` →
  `sessionTracker.clearCurrent()`, which would wipe a *running* pipeline's session
  tracking when a text pill was applied mid-pipeline.

## Context

The single fragile string convention had drifted into three scattered, easily
forgotten checks and produced a real user-visible defect (text leaking to the AI;
click doing nothing while recording). The distinction between "AI instruction" and
"literal snippet" is a **finite, closed set** owned by the app — exactly the shape
the project's Double-Enum pattern (`docs/DATABASE-PATTERNS.md`) exists to model.
The keyboard, queue, editor, and import/export all need to agree on the kind; a
value they can each read is strictly better than a format they must each re-parse.

## Decision

Introduce an explicit pill type as a **Double-Enum column**:

1. `PromptType { PROMPT, TEXT }` (`database/entity/PromptType.kt`) plus a
   `prompts.type` column (`TEXT NOT NULL DEFAULT 'PROMPT' CHECK (type IN ('PROMPT','TEXT'))`),
   added by schema migration v10→v11. Legacy rows are classified once: a prompt
   whose *trimmed* text is fully bracketed becomes `TEXT` with the outer brackets
   stripped; a fully-bracketed name is stripped too. The classification rule lives
   in one place, `PromptTypeClassifier`, which the SQL migration mirrors and JSON
   import (v1 files) reuses.
2. **One decision seam.** `PromptType.TEXT` is the single branch point:
   - the press policy (`PromptPillPressPolicy.decide`) never greys a text pill —
     short-press always inserts, long-press edits;
   - `onItemClicked` intercepts a `TEXT` pill before every staging/recording branch
     and inserts it **pipeline-free** via `InsertionService.insert(STATIC_PROMPT,
     PIPELINE)` — no orchestrator, so no `clearCurrent` on a running pipeline;
   - the queue paths exclude `TEXT` structurally (`resolveQueueSlot` returns null;
     `PromptDao.getAutoApplyIds` filters `type <> 'TEXT'`).
3. The runtime bracket check (`PromptService.isStaticResponse` /
   `extractStaticResponse`) is **deleted**; the editor gains an explicit
   Prompt/Text toggle; export bumps to `version: 2` with a `type` field (v1 files
   import through the shared classifier).

Text pills are deliberately **not queueable** — a click inserts immediately. The
enum keeps a future "append literal text after transcription" step cheap to add.

## Alternatives considered

- **V1 — keep the bracket convention, add the missing checks.** Cheapest (no
  migration), but conserves the root cause: three scattered format checks, the
  `"[a] und [b]"` ambiguity, bracket-typing UX. Any future path can forget the
  check again — precisely the bug that occurred. Rejected: poor maintainability.
- **V3 — separate table / sealed hierarchy, text pills as queue steps.** Maximum
  expressiveness, but two DAOs/data sources for two types that share every field
  minus two flags, with no named requirement for the extra power. Rejected as
  premature abstraction.

V2 (this decision) dominates: one explicit type, mechanically enforced by a CHECK,
one place that decides the semantics.

## Consequences

**Positive**

- The pill kind is first-class and consistent by construction; the CHECK makes an
  unmigrated new kind fail loudly instead of rotting silently.
- Text pills insert reliably in every state; their literal text can no longer reach
  the AI (queue paths exclude them structurally).
- The latent `clearCurrent`-on-running-pipeline bug is removed (pipeline-free insert).
- The editor exposes an honest type switch; brackets disappear from UX and strings.

**Negative**

- A schema migration + migration tests + a format bump (export v2), and all 14
  Java constructor call sites of `PromptEntity` had to carry the new field
  explicitly (Kotlin defaults are invisible from Java).

**Failure modes**

- A downgrade/rollback could leave an unknown `type` string; the `typeEnum`
  accessor falls back to `PROMPT` (safe) and the CHECK blocks writing unknown
  values. Import of a malformed `type` is normalised to `PROMPT` before insert, so
  it cannot violate the CHECK.
- Edge case `"[a] und [b]"` classifies as `TEXT` with inner `a] und [b` — identical
  to the pre-change runtime behaviour; documented and pinned by a migration test.

## References

- Plan: `tmp/plan-pill-typen.md` (worktree `feature/pill-types`).
- Pattern: `docs/DATABASE-PATTERNS.md` (Double-Enum) — new `prompts.type` row.
- Cooperates with **ADR-0012** (post-processing conversation turn): the queue path
  this ADR hardens is the one ADR-0012 rebuilt without a static check.
- Relates to **ADR-0019** (auto-send): `InsertionSource.STATIC_PROMPT` stays the
  never-diverted audit classifier for text-pill inserts.
- Built on by **ADR-0030** (config entity model): the shared `Prompt` entity carries this typed
  pill-kind (`PromptType`) into the canonical v3 format instead of the `[bracket]` convention;
  additive reuse of this typed column, not a revision.

## Decision History

- **2026-07-15 — Proposed (plan-scoped).**
  - **Trigger:** `plan-pill-typen` implementation replaced the `[bracketed]`
    convention with a DB type.
  - **Before:** pill kind was a runtime string format checked in one of several
    paths; text could leak to the AI and text pills were inert while recording.
  - **After:** explicit `PromptType` Double-Enum column; one decision seam;
    pipeline-free text insert; queue paths exclude TEXT.
  - **Reasoning:** a finite, app-owned distinction belongs in the data model with a
    CHECK, not in a fragile string convention re-parsed in scattered places.
