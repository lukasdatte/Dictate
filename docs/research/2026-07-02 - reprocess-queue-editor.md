# Reprocess Queue Editor (PromptChooserBottomSheetV2)

---
date: 2026-07-02
author: Lukas + Claude (multi-agent review session)
type: Research
status: Research
context: The planned drag-to-reorder queue editor for "Reprocess with edit" (Plan 10.6) was never shipped; the V1 fallback rejects free-text after entry and cannot reproduce multi-prompt queues. Finding F-110.
related-plan: n/a (seeded by 2026-07-02 - feature-wiring-code-review.md, F-110; original scope: Plan 10.6)
related-adrs: —
---

"Reprocess with edit" ships a documented stop-gap: the shared V1 prompt chooser reduced to exactly one saved prompt. The in-code comment (`HistoryDetailActivity.java:169-177`) explicitly tracks `PromptChooserBottomSheetV2` as a follow-up. This document records the gap, the two user-visible dead-ends of the fallback, and the design constraints for V2.

## 1. Vision and Motivation

### 1.1 The two user-visible dead-ends today

1. **Free-text rejected after entry:** the shared `PromptChooserBottomSheet` always renders its free-text field + send button (`PromptChooserBottomSheet.java:82-117`), but the `TAG_REPROCESS_EDIT` branch discards free-text choices — `JobRequest.queuedPromptIds` carries **entity IDs only**. The user types a prompt, submits, and only then gets a "Please pick a saved prompt" toast (`HistoryDetailActivity.java:606-619`; `strings.xml:371` exists solely for this dead-end).
2. **Multi-prompt queues cannot be edited:** a session originally processed with N queued prompts can only be re-run unchanged (direct reprocess) or with exactly one prompt (edit). No reorder, no add/remove.

### 1.2 Context that raises this topic's priority

The review found the queue plumbing this editor would sit on is itself defective: **F-001 (confirmed, high)** — the *keyboard-side* ReprocessStaging Send never submits the user's staged queue edits (`Effect.SubmitReprocess` carries `queue = emptyList()`, falling back to the live auto-apply queue); **F-003 (confirmed, high)** — the auto-apply queue is never prepared on the catalog record path. Any V2 design should land **after or together with** the F-001/F-003 correctness fixes, on a `JobRequest` model that actually transports queues end-to-end.

### 1.3 Discarded Alternatives

- **Keep the one-prompt fallback silently** — the free-text dead-end actively wastes user input (type → submit → toast); at minimum the interim fix below is warranted.

## 2. Findings + Conclusions — design constraints for V2

1. **`JobRequest` must carry prompt *content*, not only entity IDs** — free-text prompts and since-deleted saved prompts both need a `(text, optional entityId)` pair per queue slot. This is the same transport-model decision F-001's fix needs (the staged `reprocessEditableQueue` also lives outside entity IDs) — **make it once**.
2. **UI:** multi-select + drag-to-reorder + free-text row, per the original Plan 10.6 sketch (V2 bottom sheet). The V1 sheet stays for its other tags (regenerate "Other prompt", post-process).
3. **Interim fix (cheap, ship immediately):** pass a "saved prompts only" flag into `PromptChooserBottomSheet` so the free-text row is hidden for `TAG_REPROCESS_EDIT` instead of failing after submission.
4. **Lifecycle:** V2 must use the rotation-safe tag/argument-encoded context pattern (`TAG_REPROCESS_EDIT_PREFIX + sessionId`) — the review confirmed the sibling post-process flow gets this wrong (F-111; see the history-reprocess-hardening research doc).

## 3. Information Gaps

1. **Original Plan 10.6 text** — locate the archived plan section and reconcile its UI sketch with today's V1 sheet before designing V2. Owner: V2 implementer; fallback: constraints in §2 suffice for a fresh design.
2. **Whether queue *reordering* of a past session should re-run per-prompt or as one chained run** — owner: user decision; fallback: mirror pipeline semantics (sequential chain, each step persisted).
3. **F-110 is unverified** (feature-gap pass-through) — the in-code comments are explicit, so risk is low; re-read `:606-619` before building.

## 4. Change History

### 2026-07-02 — Initial scoping

- **Trigger:** Whole-app review, history-redesign seed agent (+ history sweep duplicate, merged).
- **What changed:** Document created from F-110; cross-linked to the F-001/F-003 queue-correctness findings.

## 5. References

- Parent catalog: [`2026-07-02 - feature-wiring-code-review.md`](<2026-07-02 - feature-wiring-code-review.md>) — F-110, F-001, F-003, F-111.
- Code: `history/HistoryDetailActivity.java:168-181,606-619`, `history/PromptChooserBottomSheet.java:82-117`, `res/values/strings.xml:371`.
- Sibling research: [`2026-07-02 - history-reprocess-hardening.md`](<2026-07-02 - history-reprocess-hardening.md>) (execution-model refactor on the same screen).
