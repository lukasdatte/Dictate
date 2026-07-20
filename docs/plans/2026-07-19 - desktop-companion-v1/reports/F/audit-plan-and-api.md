# Block F — Audit: plan-and-api

**Date:** 2026-07-20T13:30:00+02:00 · **Topic:** plan-and-api · **Block:** F (single chunk F1) · **Auditor:** block-audit agent
**Diff base:** `c46cfe8..HEAD`, file-scoped to the Block-F doc set.
**Grounding loaded:** `knowledge-adr-format` conventions (bidirectional Plan↔ADR / extends-supersedes reciprocity, mandatory ADR sections), lifecycle-adr promotion rule. (`knowledge-typescript`/`knowledge-reference` N/A — no production code in this block.)

Block F is docs/promotion only. The `plan-and-api` lens maps to: (a) **plan fidelity** — did the promotion match plan §6/§12 and §2 acceptance criteria; (b) **stubs/placeholders** — leftover `NNNN`/`adr-slug`/`plan-scoped` status in active ADR content; (c) **consumer match** — every doc-to-doc pointer (ADR cross-refs, README index, spec SSoT pointers, checklist↔runbook) resolves and agrees.

## Findings

### plan-and-api-F-1 — Windows checklist misattributes §2 acceptance criterion 9 (Important)

- **File:** `docs/plans/2026-07-19 - desktop-companion-v1/reports/windows-acceptance-checklist.md:99` (echoed in `reports/F/F1-impl.md:66` and the F1 chunk summary).
- **What's wrong:** The checklist's IMPORTANT note states *"§2 criterion 9 (ADR completeness) is satisfied once this checklist is fully ticked and signed."* Plan §2 criterion 9 (`ADR-Vollständigkeit`) actually reads: *"Die 8 ADR-Drafts aus §6 existieren als plan-scoped Drafts und sind vor Plan-Archivierung promoted (docs/decisions/ + Index)."* That criterion is about the 8 ADRs being promoted to `docs/decisions/` with index rows — which F1 **already completed** — and is entirely independent of the manual Windows acceptance.
- **Why it matters (plan fidelity):** The checklist's own scope line (`:4`) correctly says it validates criteria **3 / 4 / 7**. Line 99 contradicts that and re-labels the Windows sign-off as gating criterion 9. A plan-closure reader is misled in both directions: (i) criterion 9 looks *open/blocked on Windows* when it is in fact *satisfied now*; (ii) the genuinely-pending manual acceptance of criteria 3/4/7 is mislabeled as "ADR completeness". There is **no** §2 criterion for a user checklist sign-off, so the pending item F1 flagged ("criterion 9's final tick") does not exist as written.
- **Expected instead:** State that criterion 9 is already satisfied by the ADR promotion (docs/decisions/ 0028–0035 + index), and that the checklist, when fully ticked, is what closes **criteria 3, 4, 7** (the Windows-only parts). Correct the same statement in `F1-impl.md:66` / chunk summary.
- **Suggested fix:** Reword line 99 to: criterion 9 is satisfied by the promoted ADRs; a fully-ticked+signed checklist closes the Windows-only criteria 3/4/7 (any FAIL → issue-triage).

### plan-and-api-F-2 — One-way ADR links: §6 "erweitert/berührt" ADR-0016/0025/0024 not reciprocated (Nice-to-have)

- **Files:** target ADRs `docs/decisions/0016-*.md`, `docs/decisions/0025-*.md`, `docs/decisions/0024-*.md` (no back-reference); source refs in `0034-peer-catalog.md:236-237` and `0030-config-entity-model.md:215`.
- **What's wrong:** Plan §6 (`:656`) declares peer-catalog *"erweitert ADR-0016/0025 (additive Familie)"* and (`:652`) config-entity *"berührt ADR-0024 (Prompt-Felder)"*. ADR-0034 references 0016/0025 and ADR-0030 references 0024 forward, but 0016, 0025, and 0024 received **no** reciprocal Decision-History note / reference — unlike 0017 and 0020, which peer-catalog also extends and which *did* get reciprocal entries.
- **Why it matters:** One-way ADR↔ADR links; a reader arriving at ADR-0016/0025/0024 cannot navigate to the ADR the plan says "extends/touches" it.
- **Assessment (why only Nice-to-have):** Defensible as additive **reuse**, not revision — 0034 frames 0016/0025 as *"the stack this family is built on"* / *"cooperates with"* (nothing in 0016/0025 changes), and 0030 reuses 0024's typed-pill column without modifying it. Consistently, plan §12's post-promotion summary (`:852`) lists only `0012/0013/0014/0015/0017/0020/0027` as reciprocated — matching what F1 delivered. So this is an internally-consistent choice, not silent drift. The only residue is §6's looser "erweitert" wording vs. the reuse reality.
- **Suggested fix (optional):** Either add a one-line "built on by ADR-0034 / referenced by ADR-0030" reference bullet to 0016/0025/0024, or (cheaper) soften §6's "erweitert ADR-0016/0025" to "baut additiv auf ADR-0016/0025 auf" so plan wording matches the reuse relationship. Not blocking.

## Verified clean (no findings)

- **Number assignment** matches plan §6 table order 1:1 → 0028…0035; README index rows (`README.md:96-103`) and every promoted ADR header (`# ADR-00xx`, `Status: Accepted`, correct Scope/Subsystem) agree with the F1 report table.
- **No stubs / placeholders** in active ADR content: residual `NNNN` and `plan-scoped` strings occur only inside Decision-History **"Before:"** narration (correct historical record); no backticked `adr-slug` in any promoted ADR; all 8 have the full mandatory section set (Context/Decision/Alternatives/Consequences/References/Decision History).
- **Reciprocal cross-links present** for every relationship carried into §12: 0015←0028, 0017←0029, 0012←0030, 0017←0031, 0013←0033, 0027←0033, 0014←0035, 0020←0035, 0020←0034 all resolve (verified both directions).
- **No dead links:** zero clickable links to the moved `adrs/adr-*.md`; all inter-promoted cross-refs (ADR-0028…0035) resolve; companion/README SSoT spec pointers (`desktop-host`/`peer-katalog`/`secretstore`/`entitaetenmodell-android`/`shared-ai-extraktion`.md) all exist; checklist↔e2e-runbook links are bidirectional (`e2e-runbook.md:206` ↔ `windows-acceptance-checklist.md:3`).
- **Consumer-doc consistency:** CLAUDE.md (four-module topology, `:shared-ai` package/jvmTarget, SecretStore, entity conventions), DATABASE-PATTERNS §"SQLDelight Parity" (ADR-0035/0030/0020/0016), and companion/README module map all agree with the promoted ADRs.

## Coverage

- **Audited:** all 8 promoted ADRs (0028–0035); all 7 extended ADRs (0012/0013/0014/0015/0017/0020/0027) for reciprocity; README index; plan §2/§6/§12; CLAUDE.md; DATABASE-PATTERNS.md; companion/README.md; windows-acceptance-checklist.md; e2e-runbook.md cross-link.
- **Skipped:** production code in the working tree (uncommitted Block-E/catalog files) — out of Block-F file scope. Draft-history rewrite of research-spec/prose slug mentions was intentionally deferred to archival per lifecycle-adr (documented in F1 report §"Scope decision") — not a finding.

## Out-of-scope observations (for consolidator)

- None for the `convention` / `logic` / `test` topics — this block ships no code.
