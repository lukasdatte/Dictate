---
date: 2026-08-11
author: Lukas + Claude (analysis session)
status: Research
context: Overview of the fork-vs-upstream feature analysis — what our fork built on top of Dictate 3.2, what the Dictate Keyboard 5.3 rewrite already has, and a per-feature recommendation basis for deciding what to keep pursuing.
related-plan: n/a (plan-free research)
related-adrs: —
---

# Fork vs. Upstream — Feature Analysis Overview

Our repo is a fork of DevEmperor/Dictate **v3.2** (source commit `2163ba08`, 2026-01-06) carrying
**585 own commits** across 31 feature merges. Upstream did not continue that codebase: **Dictate
Keyboard 5.3.0** is a ground-up rewrite on a FlorisBoard base with no shared git history. This
research set inventories every feature cluster we built, checks its status in the 5.3 rewrite, and
assesses per feature whether it is still worth pursuing. The per-feature deep dives live in
[`features/`](features/); the two underlying evidence reports in [`sources/`](sources/).

> [!IMPORTANT]
> **Scope decision (2026-08-11):** the desktop-companion / PC-dictation surface (fork clusters
> 24–26 and the unlanded `feature/desktop-companion-v1` branch) is **excluded from this analysis**
> by explicit decision — the analysis covers only the features on `main` that predate the
> companion work. Incidental mentions of PC-mode in the feature docs describe coupling facts, not
> analyzed features.

## 1. Vision and Motivation

### 1.1 Why this analysis exists

The fork and upstream have silently become **two different products sharing a name**. Upstream
replaced its own codebase (the 3.2 lineage survives only as the `legacy-java` branch; the
community-facing repo moved to `DevEmperor/DictateKeyboard`), and every feature we built sits on a
foundation upstream has abandoned. Before investing further, we need to know: what did we actually
build, what does 5.3 already cover, and where is our work still differentiated?

### 1.2 What this analysis answers

1. A complete, evidence-backed inventory of our own feature clusters (28 clusters, of which 12
   feature areas are analyzed here after the scope cut).
2. Per area: upstream status — **adopted / partially exists / absent / obsolete-by-design** —
   with file- and commit-level evidence from the 5.3 tree.
3. Per area: an honest assessment whether the feature is still sensible, and realistic options
   (keep / port / upstream issue / retire) with a recommendation.

### 1.3 What this analysis is not

It is **not** a migration plan to 5.x and **not** a PR campaign plan. It produces the decision
basis; the fork-level strategy decision (stay on the 3.2 lineage vs. move to 5.x) is §2.5, and
concrete upstream issues would each get their own preparation.

## 2. Findings + Conclusions

### 2.1 The two codebases

| Dimension | Our fork (3.2 lineage) | Dictate Keyboard 5.3 |
|---|---|---|
| Base | Dictate 3.2, Java → +Kotlin | FlorisBoard import, Kotlin + Compose |
| Size | ~67k LOC app + 243 test files, Room v11, 27 ADRs | ~79k LOC app (23k Dictate-specific), `:wear` module |
| Keyboard | own QWERTZ implementation | 77 layouts, glide typing, beam-search autocorrect |
| Providers | 6 (incl. native Anthropic, ElevenLabs) | 16 presets + custom + **on-device** (sherpa-onnx) + realtime streaming |
| Transcription surfaces | IME (+ overlay widget) | IME + system-wide bubble + Wear OS + system voice-input service + file activity |
| Persistence philosophy | audit-grade: insert-only versioned session/step/insertion rows, persist-first, crash recovery | history table (5.1) + "finished WAV + prefs" recovery; in-memory state machine |
| Orchestration | modular state store, 18 modules, FGS-hosted | one ~3,200-line process-wide `DictateController` object |

Full architecture map: [`sources/upstream-rewrite-analysis.md`](sources/upstream-rewrite-analysis.md) Part A.
Full fork inventory: [`sources/fork-feature-inventory.md`](sources/fork-feature-inventory.md).

### 2.2 Adoption matrix — our 12 feature areas vs. upstream 5.3

Verdicts: ✅ adopted/equivalent · 🟡 partially exists · ❌ absent · 🚫 obsolete-by-design / rejected.

| # | Feature area | Upstream 5.3 | Recommendation (basis for decision) | Deep dive |
|---|---|---|---|---|
| 1 | Modular state store, pipeline FGS, render backends, info-bar, single write owner | ❌ (monolith object; no FGS for IME recording) | **Keep** — it is the fork's foundation; unportable by design. File 2 bug-shaped upstream issues carrying its lessons (§2.4) | [state-architecture-and-pipeline-service.md](features/state-architecture-and-pipeline-service.md) |
| 2 | Session persistence, audit DB, persist-first pipeline, run queue, multi-file audio | 🟡 (weaker recovery; concurrency explicitly forbidden) | **Keep** — the "nothing is ever lost" guarantee is the fork's core value. Upstream issues: mid-recording crash loss (their gap) | [session-persistence-and-audit-pipeline.md](features/session-persistence-and-audit-pipeline.md) |
| 3 | History: Paging3, per-step history, multi-segment audio, rerun with prompt choice | 🟡 (shipped 5.1; no pagination — their code comment admits the UI stall; no step history) | **Keep**; reverse-port their audio-retention caps; **pagination issue = strongest upstream candidate of the whole fork** | [history-system.md](features/history-system.md) |
| 4 | Consolidated conversation post-processing, structured `{message, output}`, ambiguity/review panel, spoken refinement | 🟡 (5 contexts, but sequential per-step calls, no structured output, no review loop) | **Keep unconditionally** — the fork's best engineering per line and genuinely novel UX | [conversation-postprocessing-and-review.md](features/conversation-postprocessing-and-review.md) |
| 5 | PromptBuilder (XML-escaped, injection-contained), queue editor, typed pills, prompts redesign | 🟡 (same bracket convention untyped; queue without editor; DnD+import yes, cards/duplicate no) | **Keep** PromptBuilder + pill types; measure queue-editor usage before defending it | [prompt-management-and-pills.md](features/prompt-management-and-pills.md) |
| 6 | AI provider abstraction (runners, native Anthropic, ElevenLabs, key terms) | ✅ breadth far exceeded — but enum-switch monolith, Anthropic only via OpenAI-compat, plaintext keyring | **Keep** — our runner shape + native Anthropic remain the better architecture; their breadth (on-device, realtime) is the counter-argument at fork level | [ai-provider-abstraction.md](features/ai-provider-abstraction.md) |
| 7 | QWERTZ full keyboard, Small/Single-Row modes, edit toolbar | 🚫 FlorisBoard supersedes it wholesale (and their `DictateLegacyLayout` is our inverse) | **Freeze as sunk cost** — maintain, don't extend; nothing ports | [qwertz-keyboard-and-ergonomics.md](features/qwertz-keyboard-and-ergonomics.md) |
| 8 | Overlay widget (opacity, third row, collapse, pipeline-driven HOVER, crash resume) | 🟡 different architecture (system-wide a11y bubble — broader reach, fewer knobs, no opacity pref) | **Keep with fork**; one clean upstream candidate: bubble-opacity pref | [overlay-widget-mode.md](features/overlay-widget-mode.md) |
| 9 | Recording robustness: audio-focus authority, interruption FSM, BT-SCO | 🟡 mixed — their BT routing is better than ours; their audio focus is materially weaker (phone-ring case unhandled) | **Keep**; borrow their modern BT routing; file the audio-focus issue upstream | [recording-robustness.md](features/recording-robustness.md) |
| 10 | Latency: SCO gate (2518→24 ms), cold-start tap buffer, pre-bind render, view retention (190→10 ms) | ❌ for IME cold start (stock FlorisBoard path); their latency work targets the pipeline, instrumented | **Keep**; adopt their permanent latency instrumentation as practice | [latency-and-render-performance.md](features/latency-and-render-performance.md) |
| 11 | Language chip curation + versioned-envelope prefs | ✅ equivalent two-level model | **Non-differentiator** — chip UI matched; the `preferences/versioned/` infra keeps its value independently | [language-chips-and-versioned-prefs.md](features/language-chips-and-versioned-prefs.md) |
| 12 | Accessibility screen context (opt-in, redacting, send-tap capture) | 🚫 absent **by explicit privacy decision** ("does not collect screen content") | **Fork-only forever** — never propose upstream; keep while the fork lives | [accessibility-screen-context.md](features/accessibility-screen-context.md) |

> [!NOTE]
> **Usage/cost tracking** is not in the matrix because it is inherited from 3.2, not fork-built
> (we only migrated it to Room). Upstream **deliberately deleted** theirs in the rewrite
> ("pricing tables go stale quickly and cost transparency is out of scope") and replaced it with
> local dictation statistics. If cost transparency matters to us, it is a fork differentiator by
> upstream's own decision — it will never be adopted there.

### 2.3 The headline findings

1. **The rewrite dissolves our keyboard, not our value.** Everything typing-related (QWERTZ,
   layouts, ergonomics) is a superset upstream; everything **trust-related** — audit-grade
   persistence, never-lose-a-recording, replayable conversations, review-before-insert,
   per-step history — remains absent or shallower there. The fork's identity after the rewrite
   is "the dictation keyboard you can trust with your words", not "a keyboard with dictation".
2. **Two of our directions are explicitly rejected upstream** (usage/cost tracking deleted;
   screen-context capture ruled out as a privacy stance). These can never become contributions —
   they are fork differentiators or nothing.
3. **Upstream is better than us in places** — provider breadth, on-device/realtime transcription,
   BT-SCO routing, audio-retention budgeting, latency instrumentation as a permanent practice.
   Four concrete reverse-ports into the fork are cheap (§2.4).
4. **The fork's architecture does not port, its lessons do.** The state store, FGS, render
   backends and persist-first pipeline presuppose each other; no piece lands in 5.x as a patch.
   What travels is bug-shaped evidence of the problems they haven't solved yet.

### 2.4 Action shortlists

**Upstream issues worth filing** (devEmperor works issue-driven and lands small, evidenced,
bug-shaped reports fast — see [`sources/upstream-rewrite-analysis.md`](sources/upstream-rewrite-analysis.md) Part C), ranked:

1. **History pagination** — their own code comment admits the several-hundred-entries UI stall;
   propose `androidx.paging` on the existing DAO. The single most promising candidate.
2. **Mid-recording crash loses the audio** — WAV header only patched in `stop()`; propose
   periodic header flush or startup orphan scan.
3. **Audio focus ignores `AUDIOFOCUS_LOSS_TRANSIENT`** — the phone-ring-mid-dictation case does
   not pause recording; propose handling transient loss (+ mic release on pause).
4. **Bubble opacity preference** — auto-dim is hard-coded on/off at 0.45; a percent pref + slider
   is a few dozen lines against their existing settings screen.

**Reverse-ports from upstream into the fork** (small, independent of all other decisions):

1. Audio-retention pruning (their three-cap model: max entries / max age / byte budget, pinned exempt).
2. Modern BT routing (`setCommunicationDevice()` on API 31+, SCO-connected wait with timeout).
3. Permanent latency instrumentation (their `DictateLatency` phase-stamp discipline) instead of
   one-off measurement campaigns.
4. A "sleeping server warm-up" ping for self-hosted rewording endpoints (their `customWarmUp`).

**Retire / freeze:** QWERTZ keyboard (freeze, sunk cost), language-chip UI (maintain, stop
investing — matched upstream).

### 2.5 The fork-level question

Every per-feature verdict above conditions on "while the fork lives". The strategic alternatives:

- **(A) Continue the fork** on the 3.2 lineage. Keeps the trust layer (audit persistence,
  conversation/review, history depth, a11y context, cost tracking) that 5.3 lacks; costs
  permanent solo maintenance of a codebase whose upstream is dead (`legacy-java`), and foregoes
  upstream's typing stack, provider breadth, on-device/realtime STT and Wear support.
- **(B) Move to Dictate Keyboard 5.x** and re-create the genuinely missed features there as
  private patches. Gains the whole 5.x feature set and a living upstream; loses the fork's
  architecture wholesale — the portable pieces are few and small (pill-type column,
  `GraphemeTextOps`, `AudioFileRepository`, PromptBuilder, versioned prefs), and the trust layer
  would have to be rebuilt against a moving, single-author codebase.
- **(C) Hybrid drift**: continue the fork for daily use, file the §2.4 issues upstream, and
  re-evaluate once upstream's history/persistence matures (they have been closing exactly these
  gaps release by release: history 5.1, interruption recovery 4.1, long-form ordering 5.1).

This analysis deliberately stops at laying out (A)/(B)/(C) with their evidence; the decision is
the owner's. What the evidence does say: the case for (A) rests on the trust layer and — outside
this analysis' scope — the PC-companion surface; the case for (B) grows with every upstream
release that closes a gap in §2.2.

## 3. Information Gaps

1. **Actual usage frequency of contested features** (reprocess-queue editor, review panel modes,
   widget third row) is unknown — the verdicts assume they are used. Owner: Lukas (gut check or
   a week of attention). Fallback: treat "keep" verdicts for those as provisional.
2. **Upstream receptiveness is inferred, not tested** — from merge history and README language,
   not from a filed issue. Owner: first §2.4 issue filed. Fallback: rank order stands on evidence
   quality alone.
3. **Upstream 5.4+ roadmap** is unknown; any gap in §2.2 may close independently. Owner: watch
   `DevEmperor/DictateKeyboard` releases. Fallback: re-run the per-area check before acting on a
   port decision.
4. **Effort estimates for option (B)** (move to 5.x) were not produced — no per-feature port
   costing exists beyond the portability notes in the feature docs. Owner: a follow-up plan, only
   if (B) becomes a serious candidate.

## 4. Change History

- **2026-08-11** — Initial version: two evidence reports (fork inventory, upstream analysis)
  produced by research agents; 12 per-feature deep dives; this overview. Desktop-companion
  clusters (24–26, unlanded v1 branch) cut from scope mid-analysis by owner decision.

## 5. References

- Evidence: [`sources/fork-feature-inventory.md`](sources/fork-feature-inventory.md) ·
  [`sources/upstream-rewrite-analysis.md`](sources/upstream-rewrite-analysis.md)
- Per-feature deep dives: [`features/`](features/) (12 documents, linked in §2.2)
- Fork base: `2163ba08` (Dictate 3.2 + 2 fixes); analyzed tip: `048fb37c`
- Upstream: `upstream/main` @ `3e5ebe46` (tag v5.3.0); community repo
  https://github.com/DevEmperor/DictateKeyboard ; legacy lineage: branch `legacy-java`
- ADR index: [`../../decisions/README.md`](../../decisions/README.md)
