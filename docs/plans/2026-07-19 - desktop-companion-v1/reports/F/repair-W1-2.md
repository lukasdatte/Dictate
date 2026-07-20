# Repair Wave W1-2 — plan-and-api-F-2 (green / Nice-to-have)

**Date:** 2026-07-20T13:30:00+02:00
**Agent role:** repair-fix
**Finding cluster:** `plan-and-api-F-2` (green, Nice-to-have, not blocking)

## Finding recap

ADR-0034 forward-references ADR-0016/0025 ("built on the wire stack") and
ADR-0030 forward-references ADR-0024 ("typed Prompt pill"), but the three
depended-on ADRs (0016, 0025, 0024) carried **no reciprocal back-reference**.
The revised/extended control ADRs (0017, 0020, 0012) each received a full
reciprocal Decision-History entry at promotion; the *additively-reused* ADRs
got nothing, leaving three one-way (dangling) links. The ADR doctrine
(CLAUDE.md / knowledge-adr-format: "don't leave dangling one-way links — a
future reader of either should be able to navigate to the other") makes the
reciprocal link the correct sustainable resolution.

## What I did

Applied **Option A** (add reciprocal reference bullets) rather than Option B
(soften plan §6 wording). Rationale (D4, long-term-better): the ADRs are the
living, navigable reference; making the graph bidirectional directly satisfies
the no-dangling-links rule, whereas softening a historical planning doc's
wording only paints over the asymmetry. Chose a **References bullet**, not a
Decision-History entry — these are additive-reuse relationships, not revisions,
so a DH entry would be the wrong weight. This is consistent with the finding's
own "additive reuse (not revision)" framing and with plan §12 (:855-856), which
scopes Decision-History entries to only the revised/extended ADRs
(0012/0013/0014/0015/0017/0020/0027).

### Fixes per file

- **`docs/decisions/0016-wire-protocol-typed-dtos-konform.md`** — added two
  bullets to the "Related ADRs" list (after the ADR-0012 bullet): a back-ref to
  **ADR-0030** (reuses the wire-DTO + Konform + additive-versioning + wire-vs-domain
  enum doctrine for the config entities / v3 format) and to **ADR-0034** (catalog
  DTOs are an additive payload family built on this wire stack, as ADR-0025 did
  for input-commands). Both labelled "additive reuse, not a revision of this ADR."

- **`docs/decisions/0025-input-command-protocol.md`** — added a "Built on by:"
  line under the Extends line in References, linking **ADR-0034** as a further
  additive payload family (catalog DTOs) on the same protocol stack; link target
  `0034-peer-catalog.md` verified to exist.

- **`docs/decisions/0024-prompt-pill-types.md`** — added a "Built on by **ADR-0030**"
  bullet to References: the shared `Prompt` entity carries this typed pill-kind
  (`PromptType`) into the canonical v3 format instead of the `[bracket]`
  convention; additive reuse of the typed column, not a revision.

Result: all three previously one-way links (0034→0016, 0034→0025, 0030→0024)
are now reciprocal.

## Skipped findings

None.

## Tests

`CONVENTIONS.test_command` (`./gradlew test`) **not run** — justified skip. All
three edits are Markdown-only changes to ADR documentation files under
`docs/decisions/`. They touch zero source, test, or build inputs, so the JVM
test suites are provably unaffected. The only correctness surface here is the
inserted Markdown link target `0034-peer-catalog.md`, which was verified present
in `docs/decisions/`.

## Files modified

- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/docs/decisions/0016-wire-protocol-typed-dtos-konform.md`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/docs/decisions/0025-input-command-protocol.md`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/docs/decisions/0024-prompt-pill-types.md`

## Drift (files outside the finding's stated files list)

none — the finding listed 0016/0025/0024/0034/0030 and the plan file; I edited
only the three depended-on ADRs (0016/0025/0024). 0030/0034 already carry the
forward references and needed no change; the plan file was intentionally not
touched (Option B was the alternative, not applied).
