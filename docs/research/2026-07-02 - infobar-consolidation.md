# Info-Bar Consolidation — Finish the ADR-0006 Migration

---
date: 2026-07-02
author: Lukas + Claude (multi-agent review session)
type: Research
status: Research
context: Two live info-bar systems coexist; the legacy one escapes all state-driven UX machinery. Findings F-040 + F-039 (both adversarially confirmed), plus doc-drift satellites.
related-plan: n/a (seeded by 2026-07-02 - feature-wiring-code-review.md; continues pending tasks #149/#150)
related-adrs: ADR-0006
---

The whole-app review confirmed with high confidence that the ADR-0006 info-bar migration stalled halfway: the state-derived `InfoBarSelector`/`InfoBarRenderer` system runs beside the legacy `InfoBarController`, and every UX mechanism built for info bars (force-expand, prompts-mutex, small-mode suppression) covers only one of the two. This document consolidates the confirmed findings and the target picture; it is the research basis for the pending consolidation task (#149).

## 1. Vision and Motivation

### 1.1 Why this exists

`InfoBarSelector.kt:37-39` still documents a *"Pipeline-Errors (planned Block D.2)"* producer meant to replace the nine `InfoBarController.showInfo` cases. Block D.2 was never built. The result is two live systems:

| | State-driven (new) | Legacy |
|---|---|---|
| Components | `InfoBarSelector` + `InfoBarRenderer` | `InfoBarController` |
| Container | `overlay_permission_infobar` | `info_cl` |
| Producers | onboarding, pending-insert, partial-recovery, recovery-unfinished (4) | update/rate/donate hints + all 8 pipeline error types |
| Force-expand (`LayoutCatalog.kt:655`) | ✅ | ❌ |
| Prompts-mutex (`PromptVisibilityController.kt:129`) | ✅ | ❌ |
| Small-mode/QWERTZ/emoji suppression | n/a (selector-driven) | **dead** — see F-039 |

### 1.2 What problem this solves

1. **F-040 (confirmed, medium):** legacy error bars (`internet_error`, `quota_exceeded`, …) get neither the forced two-row expansion nor the prompts hide — exactly the cramped rendering those mechanisms were shipped to fix. Verification nuance: since the 2026-05-22 Z-order fix, `info_cl` heads the constraint chain, so a legacy bar *pushes content down* rather than covering it — the defect is cramped/inconsistent rendering, not literal covering.
2. **F-039 (confirmed, medium):** `InfoBarController.onStateChanged(contentArea, isSmallMode)` lost its only caller when `KeyboardStateManager` was deleted (commit `cc5803e`, B5-CR-DEL — git-history-verified). `suppressDisplay` is permanently `false`; the documented contract ("never show in small mode or QWERTZ/emoji", `InfoBarController.kt:23-24`) is dead. AI errors are async and can surface mid-QWERTZ/emoji; update/rate/donate fire during keyboard setup regardless of persisted small mode.
3. **Doc drift:** `InfoBarRenderer`'s KDoc/params (`info_cl`/`info_tv`/`info_yes_btn`/`info_no_btn`, `InfoBarRenderer.kt:27-29,71-75`) describe the *legacy* container, while the actual wiring (`DictateInputMethodService.java:1637-1650`) binds `overlay_permission_*` views. Related: the refuted finding F-030 established that `InfoBarSelector.kt:128-130` KDoc also contradicts the code, and that the partial-recovery item is effectively shadowed by the pending-insert item (same-session items share `createdAt`; stable sort puts pending-insert first).

### 1.3 Discarded Alternatives

- **Re-wire `onStateChanged` reactively and keep both systems** — patches F-039 but leaves force-expand/prompts-mutex split and two containers in the layout. Only acceptable as an interim step; the XML comment at `activity_dictate_keyboard_view.xml:293-302` ("the two now stack cleanly if both ever show simultaneously") documents the smell rather than fixing it.
- **Suppress legacy bars in small mode via visibility hacks** — treats the symptom; the error-visibility gap (F-053: persisted error details never displayed) argues for producers with real state, not more view toggling.

## 2. Findings + Conclusions

**Target picture (per ADR-0006's original intent):**

1. **New producers in `InfoBarSelector`:** pipeline errors (driven by a small pipeline-error state axis — this also unblocks F-053, showing persisted `last_error_type/last_error_message` in history, and F-076, the missing `cancelled` branch in `AIProviderException.toInfoKey`), plus update/rate/donate (pref-mirror flags).
2. **Delete `InfoBarController`** and the `info_cl` container; one container, one constraint chain (pending task #149).
3. **Docs:** fix `InfoBarRenderer` KDoc/param names; fix `InfoBarSelector.kt:128-130` dismissal KDoc (F-030 residue).
4. Force-expand, prompts-mutex, and content-area suppression then apply to *all* bars by construction — no parallel suppression contract to keep alive.

**Priority within the theme:** error producers first (user-visible error feedback currently renders in the worst surface), then container merge, then doc fixes.

**Conclusion for the migration playbook:** this is the same incomplete-cutover pattern the review found repo-wide (catalog §2.3 theme 1). The exit criterion for the consolidation must be zero-grep on `InfoBarController` and `info_cl`, not "primary path migrated".

## 3. Interim mitigation (if consolidation is deferred)

Wire the suppression reactively: a small observer on `state.layout.contentArea` + `state.layout.smallMode` calling `infoBarController.onStateChanged`, following the `EditBarAudioFocusObserver` pattern (`DictateInputMethodService.java:1726`). Cheap, restores the documented contract, does not block the real fix.

## 4. Information Gaps

1. **Exact producer set for update/rate/donate** (which pref flags, what cadence) — owner: consolidation implementer; fallback: mirror the current `showInfo` trigger sites (`DictateInputMethodService.java:3363/3370/3372`).
2. **Whether the shadowed partial-recovery item should gain its own visibility rule** (F-030 refutation showed it is effectively never shown when a transcript exists) — owner: user decision during implementation; fallback: keep current priority order, document it.

## 5. Change History

### 2026-07-02 — Initial research consolidation

- **Trigger:** Whole-app review confirmed F-040/F-039 with high confidence (including git-history verification of the `cc5803e` caller deletion).
- **What changed:** Document created; supersedes scattered knowledge in task #148's planning notes.

## 6. References

- Parent catalog: [`2026-07-02 - feature-wiring-code-review.md`](<2026-07-02 - feature-wiring-code-review.md>) — F-040, F-039, F-030 (refuted, doc-drift residue), F-053, F-076.
- `docs/decisions/` — ADR-0006 (info-bar architecture).
- Code: `state/infobar/InfoBarSelector.kt:37-39,128-130`, `core/InfoBarController.kt:23-46`, `state/layout/LayoutCatalog.kt:653-656`, `state/render/PromptVisibilityController.kt:129-136`, `core/DictateInputMethodService.java:1637-1650,3363-3372,4616`, `res/layout/activity_dictate_keyboard_view.xml:293-305`.
- Pending tasks: #149 (container consolidation), #150 (tests + device verify).
