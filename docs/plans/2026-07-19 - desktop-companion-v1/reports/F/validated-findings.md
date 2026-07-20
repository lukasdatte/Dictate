# Block F — Audit Consolidation (MODE = initial)

**Date:** 2026-07-20T13:30:00+02:00 · **Block:** F (single chunk F1) · **Consolidator:** audit-consolidator agent
**Audit sources:** `reports/F/audit-plan-and-api.md` (the only lens run for this docs-only block; convention/logic/test topics ship no code — auditor confirmed "None" out-of-scope).
**Validation base:** plan §2/§6/§12 read in full; ADR files on disk grepped for reciprocity.

Block F is docs/promotion only (F1 promoted 8 plan-scoped ADR drafts to `docs/decisions/0028–0035`). Both raw findings were validated against the actual plan text and the promoted ADR files. **Both survive; nothing eliminated.**

## Validated findings

### plan-and-api-F-1 — Windows checklist misattributes §2 acceptance criterion 9 (Important · green)

**Confirmed against source.** Plan §2 criterion 9 (`desktop-companion-v1.md:215-216`) reads verbatim:
*"ADR-Vollständigkeit: Die 8 ADR-Drafts aus §6 existieren als plan-scoped Drafts und sind vor Plan-Archivierung promoted (docs/decisions/ + Index)."* — i.e. it is satisfied by the ADR promotion, which **F1 already completed** (0028–0035 + README index rows). It has nothing to do with the manual Windows acceptance run.

`windows-acceptance-checklist.md:99` (the `[!IMPORTANT]` note) states *"§2 criterion 9 (ADR completeness) is satisfied once this checklist is fully ticked and signed."* This **contradicts the checklist's own scope line** (`:4`), which correctly says the checklist validates criteria **3 / 4 / 7** (the Windows-only parts). Same misstatement is echoed in `F1-impl.md:66` (*"§2 criterion 9's final tick … requires the user to run it on a Windows device"*).

Effect: a plan-closure reader is misled both ways — criterion 9 looks *blocked on Windows* when it is *already met*, and the genuinely-pending manual acceptance of 3/4/7 is mislabeled as "ADR completeness". Note: Block-F acceptance in §5 (`:638-639`) lists criterion 9 **and** the checklist sign-off as **separate** items ("§2 Kriterium 9; docs-Referenzen ohne tote Links; Abnahme-Checkliste vom User abgehakt") — reinforcing that the checklist sign-off is not criterion 9.

**Fix (clear, small scope):** Reword `windows-acceptance-checklist.md:99` to state criterion 9 is already satisfied by the promoted ADRs (0028–0035 + index), and that a fully-ticked+signed checklist is what closes the Windows-only criteria 3/4/7 (any FAIL → issue-triage). Correct the same claim in `F1-impl.md:66`.

### plan-and-api-F-2 — One-way ADR links: §6 "erweitert/berührt" ADR-0016/0025/0024 not reciprocated (Nice-to-have · green)

**Confirmed against source.** Plan §6 declares peer-catalog *"erweitert ADR-0016/0025 (additive Familie)"* (`:656`) and config-entity *"berührt ADR-0024 (Prompt-Felder)"* (`:652`). Grep of the promoted ADRs confirms the links are **one-way**:
- `0034-peer-catalog.md` references 0016/0025 forward (`:9`, `:43-45`, `:236-237`, …); `0016`/`0025` carry **no** back-reference to 0034.
- `0030-config-entity-model.md` references 0024 forward (`:39`, `:215`); `0024` carries **no** back-reference to 0030.
- Control: 0017 and 0020 (which 0034 also extends) **did** get full reciprocal Decision-History entries (`0017:261/335`, `0020:210/274`) — so the asymmetry is real, not a grep artefact.

**Why it stays Nice-to-have (not eliminated):** internally consistent with the plan's own post-promotion summary — §12 (`:855-856`) lists only `0012/0013/0014/0015/0017/0020/0027` as reciprocated, which is exactly what F1 delivered. 0034/0030 frame 0016/0025/0024 as additive *reuse* ("built on"/"cooperates with"/"touched"), not revision — nothing in those ADRs changes. So this is a defensible, consistent choice; the only residue is §6's looser "erweitert/berührt" wording vs. the reuse reality. The fix is clear (no research), hence green.

**Fix (optional, clear):** Either add a one-line "built on by ADR-0034 / referenced by ADR-0030" reference bullet to 0016/0025/0024, **or** (cheaper, and aligns plan↔delivery) soften plan §6's "erweitert ADR-0016/0025" / "berührt ADR-0024" to "baut additiv auf … auf". Not blocking.

## Eliminated findings

None — both raw findings validated as real.

## Cross-cut patterns

- F-1 is a **single misstatement replicated across two artefacts** (checklist + F1 report / chunk summary): fix must touch both `windows-acceptance-checklist.md:99` and `F1-impl.md:66` so they stay in sync — not two independent findings.
- Both findings are documentation-accuracy issues in the same block; no code, no test, no convention drift. No file-clustering beyond F-1's two-file echo.
