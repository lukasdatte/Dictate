---
date: 2026-08-11
author: Lukas + Claude (analysis session)
status: Research
context: Fork-vs-upstream analysis of consolidated conversation post-processing and the ambiguity/review panel — what we built, what Dictate Keyboard 5.3 has, and whether the feature is still worth pursuing.
related-plan: n/a (plan-free research)
related-adrs: ADR-0012 (post-processing as a persisted conversation), ADR-0013 (ambiguity modes + review panel), ADR-0009 (run queue), ADR-0011 (headless completion)
---

# Consolidated Conversation Post-Processing & Review — Fork vs. Upstream Analysis

Auto-formatting rules, every queued prompt and an optional ambiguity task collapse into **one**
consolidated user message; the model answers once with a provider-native structured `{message,
output}`; the whole exchange is persisted as a replayable multi-turn conversation, and — when
the model flags the request as ambiguous — the result is held in an in-keyboard review panel
the user refines **by speaking a follow-up**. **Verdict: upstream is a superset in breadth and
absent in depth.** Dictate Keyboard 5.3 distinguishes five application contexts to our three,
but applies them as N separate best-effort round-trips with no structured output, no persisted
conversation, no model-authored explanation and no review loop. Our one-call consolidation and
the spoken refinement turn have no upstream counterpart at all.

## 1. Feature Overview

Before this work our pipeline did what upstream still does: one completion call for
auto-formatting, then one more per queued prompt, each chained onto the previous one's output.
Three consequences followed. The text **degrades through N successive rewrites** — each call
sees only its predecessor's output and re-decides formatting it was never told about. It costs
**N round-trips** of latency and money for what is conceptually one editing task. And because
every call is an isolated single-shot completion, there is nowhere for the model to say *why*
it did something, and nothing to continue if the user wants to adjust the result.

The consolidation fixes all three at once. `ConversationTurnBuilder` merges the auto-formatting
rules, each queued instruction and (when enabled) the ambiguity task into one numbered
`<instructions>` list, puts the transcript last as an escaped `<transcript>` data block behind
an explicit guardrail, and sends it as a single user message. The model sees the whole
instruction set together and can reconcile conflicts between instructions instead of applying
them blind and in sequence. `hasWork(inputs) == false` short-circuits: a plain transcription
with no auto-formatting and no queued prompt makes **no** completion call at all and inserts
the bare transcript, so users who never use prompts pay nothing for the machinery.

The answer comes back as a structured `{message, output}` — provider-native, not parsed out of
prose. `output` is the text; `message` is the model's explanation, which History displays and
the review panel renders. That is a genuinely new user-visible thing: the user finally learns
*why* the AI changed something, rather than diffing two blobs of text.

On top of that foundation, `AmbiguityMode` is a tri-state preference. `ALWAYS_INSERT` (the
default) is the old behaviour. `AUTO` always runs a turn and lets an explicit
`needsClarification` verdict decide insert-versus-review. `ALWAYS_REVIEW` always holds. When
held, the prompt grid is replaced by a panel showing the produced text, the model's
explanation, and Insert / Re-dictate / Discard. **Re-dictate is the interesting one**: it
starts a transcription-only carrier recording, then appends the spoken reply as a follow-up
turn (`<user-reply>`) to the same persisted conversation and updates the panel in place — a
conversational loop entirely inside the keyboard, never leaving the host app. Because the
conversation is persisted byte-faithfully, a later refinement or a regenerate replays the exact
earlier system prompt and user messages.

## 2. Our Implementation

**The conversation layer (cluster 16, ADR-0012).** `ai/conversation/` holds
`ConversationTurnBuilder.kt` (pure, Android-free — the caller resolves all platform state into
a `PostProcessingInputs` first, mirroring the `state/layout` split),
`ConversationReconstructor.kt`, `StructuredResponse.kt`, `StructuredResponseCodec.kt` (285 LOC,
**the single wire authority** for all three provider paths), `PostProcessingInputs.kt` and
`ConversationMessage.kt`. The runner side adds `CompletionRunner.converse(ConversationRequest)`
with OpenAI `json_schema`, an Anthropic forced `emit_result` tool, `StructuredOutputGuards.kt`,
and the capability flag `AIProvider.allowsStructuredOutputTextFallback` — true only for
`CUSTOM`, `OPENROUTER` and `GROQ`, i.e. the heterogeneous multi-model endpoints where a schema
cannot be relied on.

Persistence is a new table `conversation_messages` (`session_id, turn_index, seq, role,
content, step_id, created_at`, unique on `(session_id, seq)`) plus `MessageRole` and its DAO;
`processing_steps` gains `assistant_message` and `response_format` (`ResponseFormatKind
{JSON_SCHEMA, TOOL_USE, TEXT_FALLBACK}`) and its `step_type` CHECK is retrofitted to include
`CONVERSATION_TURN` — **migration v7→v8**. `regenerateConversationTurn` bumps `version` at the
same `chain_index`; `appendConversationTurn` adds a new turn and (an ADR-0013 addition)
persists `sessions.final_output_text` uniformly, which is what makes any uninserted completed
turn survive process death.

**The review layer (cluster 17, ADR-0013).** `preferences/AmbiguityMode.kt` drives `forceTurn`,
threaded IME → `FreshConfig` → `JobRequest.TranscriptionPipeline` → `PipelineConfig`. The
verdict is an explicit third wire field `needsClarification`, **transient by design**: no DB
column, computed once at completion time, and `encode()` stays two-field so a replayed prior
assistant turn never carries a stale verdict back to the model. The pure
`ReviewDecision.decide(mode, needsClarification, message)` returns `INSERT`/`REVIEW`, with the
"blank message can never trigger a phantom review" safety net for providers that omit the
field. UI-side: `ReviewPanelModule.kt` (196 LOC, injected clock), `ReviewPanelState`,
`PipelineDone(heldForReview = true)`, `ReviewPanelRenderer.kt`, layout mode
`KEYBOARD_REVIEW_PANEL`. The refinement carrier records under `origin = REVIEW_REFINEMENT` and
enqueues a `ConversationContinuation` job whose completion routes through a **non-terminal**
`onReviewTurnCompleted` callback — necessary because ADR-0011's terminal dispatch guard fires
once per session and a conversation legitimately produces many turns.

**Size.** Cluster 16: +3,781 / −448 plus four hardening commits (replay corruption after
regenerate/ERROR turns, truncated structured responses, Groq text fallback, resume fidelity).
Cluster 17: +3,146 / −291 combined, including a substantial bugfix wave — the panel initially
had no height of its own and collapsed together with the emptied prompt grid, i.e. it was
invisible.

**Coupling.** Sharply split. `ai/conversation/` + the `converse` runner surface is Android-free
and **portable as-is** — `ConversationTurnBuilder`'s KDoc explicitly calls it liftable into a
shared JVM module. The DB half needs cluster 15's audit schema; the wiring in
`PipelineOrchestrator` is fork-shaped; and the review panel is among the most fork-tied things
we have — a state axis with a teardown cascade, a `LayoutMode` in the MotionLayout catalog,
dependent on the structured verdict and on ADR-0009's run queue. Only `ReviewDecision` and
`PostProcessingReview` port cleanly out of cluster 17.

## 3. Upstream 5.3 Status

**Verdict: adopted and exceeded in breadth (contexts), absent in depth (consolidation,
structure, conversation, review).**

Upstream distinguishes **five** application contexts to our three, as five separate mechanisms
rather than one abstraction: a *transcription style prompt* sent with the audio to bias the STT
model, *auto-formatting*, *auto-apply prompts*, a *live prompt* (a spoken instruction instead
of dictated text), and *queued prompts*. The full ordering chain in `finalizeAndCommit`
(`DictateController.kt:1433-1478`) is: live-prompt **or** [auto-format → auto-apply → queued] →
paragraph splitting → deterministic find-and-replace mappings (`#129`) → commit. As a taxonomy
of *when* an instruction applies, this is richer than ours.

**The round-trip question — verified in source, not inferred.** `postProcessTranscript`
(`DictateController.kt:2810-2846`) makes one `requestRewordRaw` call for auto-formatting, then
loops over auto-apply prompts in `POS` order making **one call each**:

```kotlin
for (p in autoApply) {
    text = runCatching {
        requestReword(instruction, if (p.requiresSelection) text else null, p.reasoningEffort, p.reasoningEffortCustom)
    }.getOrDefault(text)
}
```

`applyPendingPrompts` (`:2855-2880`) does the same for the queued list in tap order,
short-circuiting `[snippet]` prompts by literal append with no call. So a dictation with
auto-formatting plus two auto-apply prompts plus one queued prompt is **four sequential
completion calls**, each best-effort (`getOrDefault` keeps the text so far on failure). There
is no consolidated call anywhere. A side effect of the chain shape worth naming: a prompt with
`requiresSelection == false` is sent **without the running text**, so its answer replaces the
transcript wholesale — deliberate given the prompt semantics, but a sharp edge the consolidated
model does not have.

**No structured output.** Grepping
`json_schema|responseFormat|response_format|tool_choice|structuredOutput` across `app/src` and
`lib/` returns exactly one hit — `addFormDataPart("response_format", "json")` in the
*transcription* multipart (`OpenAiCompatibleClient.kt:190`), which is Whisper's verbose-JSON,
not chat structured output. Every rewording answer is plain text, trimmed. There is
consequently no `message` field, no explanation surface, and no verdict channel.

A telling second-order consequence: because the output is unstructured prose, upstream needs
**heuristic echo guards** — `looksLikeAutoFormattingPrompt` discards output that turns out to
be the prompt itself (`#124`, `40446747`) and `looksLikeStylePromptEcho` guards against Whisper
echoing the style prompt back on silence (`#77`, `8fbb5c0e`, with a unit test). Both are
patches for a failure mode that a schema-constrained `{message, output}` largely designs away.

**No persisted conversation.** There is no message table, no turn index and no replay.
`DictateController` is a process-wide `object`; the pending prompt queue is `MutableStateFlow`
in memory and lost on process death (upstream area 13). A regenerate cannot replay what was
actually sent because what was sent was never stored.

**No review or refinement loop.** Nothing corresponds to `AmbiguityMode`, the held-output
panel, or a spoken follow-up turn. The nearest neighbour is the *live prompt*
(`startLivePrompt`, `:2786-2802`) — a spoken instruction routed through `requestReword` — but
it is a one-shot alternative *entry* into rewording, not a continuation of an existing
exchange, and a live prompt explicitly **discards the queued prompts** (`_pendingPrompts.value
= emptyList()`, `:1446`, with the comment "a live prompt ignores any queued prompts").

**The one place upstream is clearly worse than ours on its own terms:** the auto-formatting
prompt is the fixed constant `DictatePromptDefaults.AUTO_FORMATTING_PROMPT` (a ~15-rule block
in `lib/dictate-core/.../DictatePromptDefaults.kt:44`) assembled with a language hint by
`buildAutoFormattingPrompt`. **It is not user-editable.** A user who dislikes one of its rules
has no recourse short of disabling auto-formatting entirely.

## 4. Assessment — is this feature still sensible?

**The consolidation is the strongest technical argument in the fork.** It is not a preference —
it is strictly fewer network round-trips producing strictly better-informed output, and it
degrades gracefully (`hasWork == false` means users who do not use prompts are unaffected). For
the fork's actual usage — a heavy dictation user with auto-formatting on and typically one or
two prompts — it converts three or four sequential API calls into one. On a mobile connection
that is the difference between a noticeable pause and a prompt result, and the quality argument
(one model turn that sees all instructions and reconciles them, versus N blind rewrites) is the
kind that only shows up on the hard cases where two instructions interact.

**The structured `{message, output}` is nearly as strong** and cheaper to justify: it makes the
pipeline debuggable, gives History and the review panel a real explanation to show, and removes
an entire class of "the model echoed my prompt into the text field" failures that upstream
currently defends against with string heuristics.

**The review panel and ambiguity modes are the genuinely contested part.** Arguing for: it is
the only place in either codebase where dictation becomes a *dialogue*, and for complex
rewording requests ("make this more formal but keep the second paragraph verbatim") a model
that can ask instead of guessing is qualitatively different. The spoken-refinement loop is a
real product idea, not a refactor. Arguing against: the default is `ALWAYS_INSERT`, meaning the
feature is off unless deliberately enabled; its value depends entirely on the model reliably
distinguishing "I am unsure" from "I guessed" — and `needsClarification` is a self-report, with
all the calibration problems that implies; and it carries the heaviest fork coupling in this
document (state axis, layout mode, non-terminal callback, teardown cascade) for a mode that may
never be switched on. If it is not in daily use, it is the clearest candidate in the entire
fork for retirement on cost grounds. That is gap 1 and it is decisive.

**Would it be attractive upstream?** The consolidation, honestly assessed: **the idea would
appeal; the framing matters, and there is a real conflict.**

- The *cost* argument is dead on arrival. Upstream deleted usage and cost tracking outright
  (`852e7f2d`, "pricing tables go stale quickly and cost transparency is out of scope").
  Pitching "fewer calls means cheaper" pitches against a decision already made.
- The *latency* argument is very much alive. Upstream instruments dictation latency as a
  first-class concern (`LATENCY_LOG_TAG = "DictateLatency"`, `BatchLatencyTrace`, phase stamps)
  and burned a 39-commit experiment campaign on transcription latency alone. "Three sequential
  completions become one" is exactly the currency that codebase trades in.
- **The conflict:** per-prompt reasoning-effort override (`#155`, `PromptModel.reasoningEffort`
  + `reasoningEffortCustom`) is threaded *per call* — `requestReword(..., p.reasoningEffort,
  p.reasoningEffortCustom)`. Consolidating N prompts into one call makes per-prompt reasoning
  structurally unrepresentable. Any upstream proposal must either scope consolidation to
  prompts that share a reasoning setting, or accept a global effort for the consolidated turn.
  Not naming this in an issue would be the fastest way to get it rejected.
- Structured output is a separable, smaller ask, but it multiplies across upstream's **16
  provider presets** with wire-format variation already handled as an enum switch — several of
  which (Ollama, self-hosted, custom endpoints) cannot be relied on to honour a schema. Our
  `allowsStructuredOutputTextFallback` flag exists precisely because we hit that; upstream's
  provider breadth makes the same problem four times larger. A schema-with-text-fallback design
  is proposable, but it is a real design conversation, not a patch.
- The review panel is a large architectural ask on an effectively single-author codebase with a
  strong personal design voice. The realistic read is: file an issue describing the
  interaction, do not write code first.

**Maintenance cost of keeping it.** The conversation layer is the *cheapest* large cluster we
own — pure Kotlin, one wire authority class, well-tested, and its four hardening commits have
already worked through the ugly cases (replay corruption after a regenerate, truncated
structured responses, providers that ignore the schema). The review layer is the expensive one,
and its cost is entirely in the fork's state/render machinery rather than in the AI code.

## 5. Options going forward

**(a) Keep in fork as-is.** Zero incremental cost for the conversation layer, which is stable
and load-bearing for History, the review panel and Windows dispatch alike. The review layer's
cost is real but bounded, and it is already paid.

**(b) Port to Dictate Keyboard 5.x as a private patch.** Unusually plausible for the
conversation half: `ai/conversation/` is Android-free and would sit beside upstream's
`OpenAiCompatibleClient` with the DB persistence dropped (they have nowhere to put it) — you
would keep the one-call consolidation and the `{message, output}` and lose the replay. The
review half does not port without a persisted conversation, so this option effectively means
"take the consolidation, leave the panel".

**(c) Propose upstream via an issue.** The well-scoped version is *one* issue, about
consolidation only: state the current behaviour with line references
(`postProcessTranscript:2810-2846`, `applyPendingPrompts:2855-2880`), quantify it (auto-format
+ two auto-apply + one queued = four sequential calls), argue latency rather than cost, propose
merging the instruction set into one numbered user message with the transcript isolated, and
**pre-empt the reasoning-effort conflict** by proposing that prompts carrying a per-prompt
override stay on their own call while the rest consolidate. Structured output belongs in a
separate later issue if the first lands. The review panel belongs in a third issue that is a
product proposal, not a patch offer.

**(d) Retire / let upstream's equivalent replace it.** Coherent only for the ambiguity modes
and the review panel, and only if gap 1 comes back "never used". The consolidation has no
upstream equivalent to be replaced by — retiring it means going back to N chained calls, which
is strictly worse on every axis.

**(e) Reverse-port from upstream.** Their five-context taxonomy is worth stealing conceptually
— specifically the *transcription style prompt* (an instruction sent with the audio to bias the
STT model rather than a rewording call afterwards), which we do not have as a distinct context.
Cheap and complementary; it sits before our consolidated turn rather than competing with it.

*Leaning:* keep the conversation layer unconditionally — it is the fork's best engineering per
line — and make the review panel's fate depend on whether the ambiguity modes are actually
switched on.

## 6. Information Gaps

1. **Is `AmbiguityMode` ever set to anything but `ALWAYS_INSERT`, and has the spoken refinement
   loop been used in real work?** *Owner:* Lukas. *Fallback:* read the pref off the device; if
   it has never left the default, treat cluster 17 as retirement-eligible and keep only cluster
   16.
2. **Measured latency delta of consolidation** — one turn versus the old N-call chain, on a
   typical prompt set. *Owner:* measurable locally by replaying a session through both paths;
   nobody has the number. *Fallback:* any upstream issue must present the argument structurally
   ("N sequential round-trips") rather than with a figure we cannot substantiate.
3. **How often does `needsClarification` actually fire in `AUTO` mode, and is it calibrated?**
   A model that never flags makes the mode inert; one that over-flags makes it annoying.
   *Owner:* Lukas + a log sweep over `processing_steps`. *Fallback:* assume miscalibration and
   treat `ALWAYS_REVIEW` as the only reliable mode.
4. **Would DevEmperor accept consolidation given the per-prompt reasoning override?** *Owner:*
   an upstream issue. *Fallback:* propose the hybrid (override-carrying prompts keep their own
   call) as the opening position rather than the fallback.
5. **How badly does structured output degrade across upstream's 16 presets?** We only know our
   own five providers' behaviour. *Owner:* unresolvable from outside. *Fallback:* propose
   schema-with-text-fallback, mirroring our `allowsStructuredOutputTextFallback` flag, as the
   design rather than schema-only.

## 7. References

- [`../sources/fork-feature-inventory.md`](../sources/fork-feature-inventory.md) — clusters 16
  (consolidated conversation post-processing) and 17 (ambiguity modes & review panel)
- [`../sources/upstream-rewrite-analysis.md`](../sources/upstream-rewrite-analysis.md) — area
  15 (auto-formatting / prompt contexts), area 4 (prompt queue), area 13 (session persistence),
  area 14 (cost tracking removal), Part C (contribution receptiveness)
- [`../../../decisions/0012-pipeline-post-processing-conversation.md`](../../../decisions/0012-pipeline-post-processing-conversation.md),
  [`0013-review-panel-and-ambiguity-modes.md`](../../../decisions/0013-review-panel-and-ambiguity-modes.md),
  [`0009-pipeline-run-queue-serialized-concurrency.md`](../../../decisions/0009-pipeline-run-queue-serialized-concurrency.md),
  [`0011-pipeline-headless-completion-fallback.md`](../../../decisions/0011-pipeline-headless-completion-fallback.md)
- Plans (untracked working copies): `tmp/plan-paket1-konversations-fundament.md`,
  `tmp/plan-paket2-review-modi.md`; retrospective audit `tmp/research-prompt-architektur.md`
- Our commits: `afbad682`…`9547cbc5`, hardening `57a8f444`, `c09efb55`, `a959d6ef`, `a822ba9d`
  (cluster 16); `41b542bd`…`06280fc8`, `9946fd8f`…`cfffbcc5`, `ed3634ba`, `cc2f71f2` (cluster
  17)
- Upstream @ `upstream/main` (`3e5ebe46`):
  `dictate/DictateController.kt:1433-1478,1446,2786-2802,2810-2846,2855-2880,2931-2944,2950-2961`;
  `lib/dictate-core/.../provider/OpenAiCompatibleClient.kt:190`;
  `lib/dictate-core/.../data/prompts/DictatePromptDefaults.kt:44,74-90`; commits `852e7f2d`
  (cost-tracking removal), `40446747` (`#124`), `8fbb5c0e` (`#77`); issues `#129`, `#155`,
  `#189`
