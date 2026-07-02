# `docs/research/` — Plan-Free Research Reports

Research findings **not tied to an active plan**: probes, audits, operational baselines, methodology investigations. Plan-scoped research lives in the plan's own `research/` directory under `docs/plans/` instead.

## Conventions

- **Naming:** `YYYY-MM-DD - {kebab-case-topic}.md` (date = creation day, immutable).
- **Format:** Universal Document Skeleton (UDOC) — frontmatter, §1 Vision, §2 Findings + Conclusions, Information Gaps, Change History, References. See the `knowledge-doc-format` skill.
- **Language:** English.
- **Research → Spec promotion:** a research file becomes a spec by promoting its frontmatter `status:` field (`Research` → `Spec — programmer-ready` → `Implementer-ready` → `Accepted`) — no rename, no move. Single-source-of-truth rule: once a plan references a spec, the spec is canonical for its topic.

## Index

| Date | Document | Status | Topic |
|---|---|---|---|
| 2026-07-02 | [`feature-wiring-code-review`](<2026-07-02 - feature-wiring-code-review.md>) | Research | Whole-app review: 96 findings (feature gaps, wiring gaps, bugs), 38 adversarially confirmed; seeds the six specs below |
| 2026-07-02 | [`overlay-widget-transparency`](<2026-07-02 - overlay-widget-transparency.md>) | Spec — programmer-ready | Configurable overlay-widget transparency + theme unification (F-118/119/120/121) |
| 2026-07-02 | [`infobar-consolidation`](<2026-07-02 - infobar-consolidation.md>) | Research | Kill the dual info-bar system, finish ADR-0006 (F-040/F-039) |
| 2026-07-02 | [`history-reprocess-hardening`](<2026-07-02 - history-reprocess-hardening.md>) | Research | History AI ops: lifecycle-safe execution + PromptService routing (F-055/108/109/111) |
| 2026-07-02 | [`recording-interruption-handling`](<2026-07-02 - recording-interruption-handling.md>) | Research | Produce InterruptionAction — call/headset/screen interruptions (F-036) |
| 2026-07-02 | [`history-pagination-and-scale`](<2026-07-02 - history-pagination-and-scale.md>) | Research | Paging, background queries, retention for history (F-054) |
| 2026-07-02 | [`reprocess-queue-editor`](<2026-07-02 - reprocess-queue-editor.md>) | Research | Ship the staged-queue editor UI (F-110) |
