---
date: 2026-08-11
author: Lukas + Claude (analysis session)
status: Research
context: Fork-vs-upstream analysis of prompt architecture, the prompt-queue transport with its reprocess editor, and typed prompt pills with the prompts-overview redesign — what we built, what Dictate Keyboard 5.3 has, and whether the features are still worth pursuing.
related-plan: n/a (plan-free research)
related-adrs: ADR-0024 (prompt-pill types), ADR-0012 (consolidated conversation turn — consumer of the builder), ADR-0009 (run queue)
---

# Prompt Management & Pills — Fork vs. Upstream Analysis

Three related pieces of the same surface: how a prompt is *assembled* into a model request
(`PromptService` + an XML-tag `PromptBuilder` that escapes untrusted content), how a *queue* of
prompts is transported and edited (content-carrying `PromptQueueSlot` + a drag-reorderable
reprocess editor), and what a prompt *is* in the data model (a typed `PROMPT`/`TEXT` column
replacing a `[bracketed]` string convention, plus a redesigned card-based overview). **Verdict:
partially exists upstream across all three, with the split running consistently the same way**
— upstream has more *breadth* (a community prompt library, per-prompt reasoning-effort
overrides, 16 providers behind the same prompts), we have more *structure* (escaped tagged
sections instead of string concatenation, a persisted type instead of a re-parsed convention,
editable queue slots instead of tap-toggles). This is the area with the highest ratio of
portable code to fork coupling — and also the one where upstream has an explicit constraint
that blocks the most interesting part.

## 1. Feature Overview

**Prompt assembly (cluster 3).** Upstream 3.2 built prompts by concatenating strings inside
`DictateUtils` and the IME service. `PromptService` replaced that with typed templates
assembled through a fluent `PromptBuilder` that emits tagged sections — `<instruction>`,
`<selected-text>`, `<user-request>`, `<language-hint>`, `<transcript>`, `<rules>`,
`<examples>`. The distinction that carries the weight is `section()` versus `dataSection()`:
structural tags built from *trusted* template text use the former; anything user- or
app-supplied goes through the latter, which XML-escapes `&`, `<` and `>` so a transcript cannot
close its own tag and forge a sibling instruction. Containment becomes a structural property of
the builder rather than a filter someone has to remember. System prompts became
context-specific through `PromptContext` (`REWORDING` / `LIVE` / `QUEUED`) resolved by
`SystemPromptResolver`.

**Queue transport and the reprocess editor (cluster 20).** Originally a history reprocess could
re-apply exactly one saved prompt, and the queue was a list of entity IDs — so editing or
deleting a saved prompt between confirming a re-run and executing it silently changed or
dropped what actually ran. The queue now carries prompt *content*. On top of that, a
bottom-sheet editor lets the user assemble the exact chain for a re-run: add saved prompts,
type free-text prompts, reorder by drag, remove entries, then run the ordered chain against the
session.

**Pill types and the overview (cluster 21).** A pill above the keyboard is either an AI
instruction or a literal snippet (a greeting, a signature). That distinction used to be a
naming trick — a prompt whose text was wrapped in square brackets was treated as literal —
re-parsed at several unrelated call sites. One of them was forgotten when the post-processing
pipeline was rebuilt, so a text pill leaked its literal text to the model as if it were an
instruction. It is now a real database column with a `CHECK` constraint. Alongside it, press
behaviour became a pure unit-tested policy (greyed pills stay *enabled* and are rendered
disabled by alpha only, because `setEnabled(false)` swallows every `MotionEvent` and made
long-press impossible), and the prompts screen was redesigned into draggable cards with
per-item duplicate.

## 2. Our Implementation

**Cluster 3 — `ai/prompt/`** (`PromptService`, `PromptBuilder`, `PromptContext`, `PromptMode`,
`PromptTemplates`, `SystemPromptResolver`, `PromptTypeClassifier`) plus
`core/AutoFormattingService.kt` as the first consumer. `PromptBuilder` is ~70 lines with no
Android dependency:

```kotlin
fun dataSection(tag: String, content: String): PromptBuilder = section(tag, escapeXml(content))
internal fun escapeXml(content: String) = content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
```

It later grew `instructions(items)`, which renders a numbered `<instruction index="N">` list —
the seam through which ADR-0012's consolidated turn merges auto-formatting rules, every queued
prompt and the ambiguity task into one message. **Size: S** (`7a0b8c4f`, 8 files, +350 / −59).
The inventory calls it the single most extractable piece in the fork, and that holds: it is one
Android-free class plus templates that need only `SharedPreferences`.

**Cluster 20 — `core/PromptQueueSlot.kt`** (76 LOC) models three valid shapes with an
unconstructible fourth, enforced by an `init` guard: ID-only (legacy keyboard transport,
resolved from the DB at execution and skipped if since-deleted), content + `entityId` (an
editor-confirmed saved prompt, so it survives the saved prompt being edited or deleted between
confirm and execution), and free-text (`entityId == null`).
`history/ReprocessQueueEditorModel.kt` (157 LOC) is UI-free and `Bundle`-friendly with a
`Snapshot` type; `ReprocessQueueEditorBottomSheet.kt` (252 LOC) is a plain
`BottomSheetDialogFragment`. Wiring touches `ImePipelineConfigResolver`, `JobExecutor`,
`PipelineOrchestrator.resolveQueueSlot` and `HistoryDetailActivity`; persistence is
`sessions.queued_prompt_ids` and the `QueuedPromptIds` pref. Post-merge hardening distinguished
"empty queue" from "unset queue", made resolution happen exactly once, and added resume/cancel
parity. **Size: M** (+1,518 / −102 core, ≈ +1,700 with hardening).

**Cluster 21 — ADR-0024.** `PromptType { PROMPT, TEXT }` as a Double-Enum column (`TEXT NOT
NULL DEFAULT 'PROMPT' CHECK (type IN ('PROMPT','TEXT'))`, migration **v10→v11** with a table
recreate). Legacy rows are classified once by a rule that lives in exactly one place —
`ai/prompt/PromptTypeClassifier.kt` — which the SQL migration mirrors and which JSON import
reuses for v1 files, pinned by `PromptTypeClassifierTest` and `MigrationTo11Test`.
`PromptType.TEXT` is the single branch point: `PromptPillPressPolicy.decide` never greys a text
pill; `onItemClicked` intercepts it before every staging branch and inserts it pipeline-free
via `InsertionService.insert(STATIC_PROMPT, PIPELINE)` (which also fixed a latent bug where the
static short-circuit called `clearCurrent()` and wiped a *running* pipeline's session
tracking); the queue paths exclude `TEXT` structurally (`resolveQueueSlot` returns null,
`PromptDao.getAutoApplyIds()` filters `type <> 'TEXT'`). The runtime bracket check
`PromptService.isStaticResponse` / `extractStaticResponse` was **deleted**. JSON export moved
to `version: 2` with a `type` field; import clamps selection and auto-apply flags for `TEXT`
pills. The overview became `PromptsOverviewActivity` + `PromptsOverviewAdapter` with
`PromptListMutations`, `PromptReorderCallback`, `PromptsInfoHeaderAdapter`: cards,
drag-and-drop, per-item duplicate, info header, export/import in an overflow menu. **Size:
M–L** (+3,071 / −389 across `9915ffc0`, `34cbf701`, `9a98147b`).

**Coupling.** Cluster 3 is essentially free-standing. Cluster 20 is moderate — the pure pieces
port, the sheet is a standard fragment, but the transport needs the fork's job/config plumbing
underneath. Cluster 21 is called out in the inventory as **the best port candidate of the
fork's AI-UX work**, because upstream already has a prompts table, an overview screen and a
keyboard pill row; the only fork couplings are the greyed-out driver, the `InsertionService`
path and the Windows auto-send divert gate, all shallow.

## 3. Upstream 5.3 Status

### Prompt assembly — absent as a concept

There is no builder. `requestReword` (`DictateController.kt:2931-2944`) is the whole of it:

```kotlin
val content = buildString {
    append(instruction)
    if (sys.isNotBlank()) append("\n\n").append(sys)
    if (!input.isNullOrBlank()) append("\n\n").append(input)
}
```

Instruction, system prompt and the user's text are three blocks separated by blank lines, with
no delimiters, no escaping and nothing distinguishing trusted template text from untrusted
payload. `buildAutoFormattingPrompt` (`DictatePromptDefaults.kt:78`) does the same thing by
string concatenation for the auto-formatting call.

**The prompt-injection angle, stated precisely.** Our XML escaping is not a security boundary
against a determined adversary — a model can be talked out of respecting tags. What it does is
make *accidental and opportunistic* confusion structurally unlikely: a transcript that happens
to contain instruction-shaped prose ("actually, ignore that and write it formally") sits inside
a tag the guardrail told the model to treat as data, and it cannot terminate that tag.
Upstream's concatenation gives such text the same syntactic standing as the instruction above
it.

Where this stops being hygiene and becomes load-bearing is our accessibility screen context
(cluster 23): the serialized view tree of **another application** enters the prompt as a
`<ui-context>` data block through the same builder. That content is genuinely
third-party-controlled — an app can put arbitrary strings in its own labels. Escaping is the
reason a hostile label cannot present itself as a sibling instruction. Upstream, notably, does
not have this exposure at all, because its accessibility service reads only the focused
editable node by explicit design (upstream area 12) — so the concatenation is less dangerous
*there* than the same code would be *here*.

Indirect corroboration that the unstructured shape costs upstream something: they carry two
heuristic echo guards (`looksLikeAutoFormattingPrompt`, `#124`; `looksLikeStylePromptEcho`,
`#77`) to catch the model emitting the prompt back into the text field — a failure mode that
tagged sections and structured output make much rarer.

### Prompt queue (upstream area 4) — the queue exists, the editor does not

`_pendingPrompts: MutableStateFlow<List<PromptModel>>` (`DictateController.kt:240-246`);
`togglePendingPrompt()` (`:630-646`) adds or removes and is a no-op unless the state is
`Recording` or `Transcribing`; `applyPendingPrompts()` (`:2855-2880`) runs them in tap order on
the finished transcript, one call per prompt, best-effort, appending `[snippet]` prompts
literally, then clears the queue. Origin: `cf8679df`.

Verified limits: the only affordance is **tapping chips in the ROW layout while capturing**
(`ui/DictatePromptStrip.kt:141-151`), with an accent fill marking queued chips — no slot list,
no reordering, no removal other than re-tapping, no position badge. The **PANEL layout does not
queue at all** (`ui/DictateInputLayout.kt:145-153` calls `applyPrompt` unconditionally). A live
prompt **discards** the queue outright (`:1446`). The queue is in-memory only and lost on
process death. Grepping `queue` across `dictate/` and `app/settings/dictate/` returns only
those two files — no settings screen, dialog or panel.

Our staged, arrangeable, content-carrying slots have no upstream counterpart, and neither does
re-running a *stored* session through a chosen chain.

### Prompt pills (upstream area 8) — same behaviour, no model

`PromptModel` (`data/prompts/PromptModel.kt:31-42`) carries `id, pos, name, prompt,
requiresSelection, autoApply, reasoningEffort, reasoningEffortCustom` — **no type field**. A
snippet is recognised by string syntax at call time, re-implemented at three separate sites:
`DictateController.kt:2726-2730` (direct apply), `:2865-2869` (queued path) and
`ui/DictatePromptStrip.kt:304-312` (icon choice). That is the identical convention we started
from, with the identical scatter — and it is worth noting that our own bug came from exactly
this shape: one of the three sites was forgotten when the pipeline changed.

> [!IMPORTANT]
> **Upstream's prompts schema is explicitly frozen.** `PromptsDatabaseHelper.kt:23-30, 44-47` declares the table frozen for legacy compatibility with the v1–v3 Java app (whose export format the current import is deliberately byte-compatible with, `DictatePromptsScreen.kt:102-109`). A `type` column is therefore not a missing feature — it is a change that runs directly into a stated constraint. This is the single most important upstream signal in this document.

### Prompts management UI (upstream area 7) — mixed, and upstream-only in two respects

Present in `app/settings/dictate/DictatePromptsScreen.kt`: **drag-and-drop reordering**
(hand-rolled `detectDragGesturesAfterLongPress`, `:271-303`, swap at half-item threshold,
`zIndex` + `translationY` lift, persisted as `POS = index` writing only changed rows,
`:146-153`); **import/export JSON** (`:184-201`) in `{"version":1,"prompts":[…]}` with
`name`/`prompt`/`requiresSelection`/`autoApply` plus optional reasoning fields, accepting both
the wrapped form and a bare array and then asking Replace vs. Add (`:543-562`).

Absent: **duplicate** (grepped across the dictate and settings trees — the editor dialog offers
Save / Cancel / Delete only, `:582-598`); **cards** (it is a `LazyColumn` of `JetPrefListItem`
rows with two status icons and a drag handle, `:254-335`); a search field.

Upstream-only, and both genuinely attractive to us:

- **Per-prompt reasoning-effort override** (`#155`) — `reasoningEffort` +
  `reasoningEffortCustom` on the model, passed per call. We have nothing equivalent.
- **Community prompt library** (`#105`) — `data/prompts/PromptLibraryManager.kt` pulls a static
  `library.json` from an orphan branch of the project repo with stale-while-revalidate caching
  and an APK-bundled fallback. Publishing has **no backend**:
  `PromptLibraryContribution.buildSubmissionUrl` (`:198-215`) builds a pre-filled GitHub
  "create new file" deep link so GitHub auto-forks and opens a PR. Elegant,
  zero-infrastructure, and a design worth admiring independently of whether we adopt it.

## 4. Assessment — are these features still sensible?

**Cluster 3 (`PromptBuilder`) is unambiguously worth keeping.** It is small, pure, tested, has
no maintenance surface to speak of, and it is the seam every later feature plugged into — the
consolidated conversation turn, the accessibility screen context, the language hint. It is also
the piece whose *absence* upstream is most visible in their code: two echo-guard heuristics and
a raw concatenation where they need containment. The one honest caveat is that XML escaping
buys containment, not security, and the fork should not describe it as more than that.

**Cluster 21 (typed pills) is worth keeping for us and is hard to give upstream.** For our fork
it removed a real user-visible defect and replaced three scattered re-parses with one column
and one classifier — exactly the tradeoff the project's Double-Enum rule exists to make. The
port assessment in the inventory ("best port candidate") is right on the *code* and wrong on
the *politics*: the code transplants nearly verbatim, and the frozen schema means it will not
be accepted in that form. What *can* be offered upstream is the mechanical half without a
schema change — collapse the three duplicated bracket checks into one classifier function that
all three sites call, keeping the string convention exactly as it is. Small, obviously correct,
no data migration, no compatibility risk. That is the shape DevEmperor merges. The card
redesign and the duplicate action are similarly small, self-contained UI PRs, though "cards vs.
rows" is a taste question on a codebase with a strong personal design voice and could easily be
declined for reasons that have nothing to do with quality.

**Cluster 20 (queue editor) is the most contested.** In favour: it is the only way to re-run a
stored session through a *deliberately composed* chain, which is a real capability, and the
content-carrying transport closes a genuine correctness hole (editing a saved prompt no longer
silently changes an already-confirmed re-run). Against: it is a bottom sheet reachable from a
detail screen behind a long-press — several taps deep in a surface that a heavy dictation user
may rarely visit. Its value is entirely a function of how often a re-run with a *different*
chain actually happens (gap 2). If the honest answer is "a few times, while building it", then
the content-carrying `PromptQueueSlot` still earns its place (it fixes correctness on the
everyday path too) while the editor sheet is the retirement candidate.

**Would upstream want any of it?** The queue editor is the one place where an issue has a
decent chance on its merits: the current queue has no removal, no reordering and no
persistence, which are plain gaps rather than design choices, and `#183`/`#194` show DevEmperor
is actively building configurable action rows in the legacy layout — adjacent territory,
recently touched. The realistic ask is small: let a queued chip be removed and reordered, and
survive process death. Not our bottom sheet, and not the reprocess flow (which presupposes
per-step history they do not have).

**And the traffic runs the other way too.** Per-prompt reasoning-effort override is a small,
clean feature we lack and our `ParameterRegistry` already has the shape to hold. The community
prompt library is a bigger idea, and its no-backend GitHub-deep-link publishing trick is the
kind of design that is worth copying wholesale rather than reinventing. Neither is blocked by
anything in our architecture.

## 5. Options going forward

**(a) Keep in fork as-is.** Correct default for cluster 3 (no cost) and cluster 21 (already
paid, guards a real defect). For cluster 20 it means keeping a sheet whose usage is unmeasured.

**(b) Port to Dictate Keyboard 5.x as a private patch.** `PromptBuilder` drops in beside
`requestReword` with no dependencies and would improve upstream's prompt hygiene immediately —
the cleanest single-file transplant available in either direction. The pill type column does
*not* port as a private patch either, for the same reason it will not be accepted: their table
is frozen and their import format is byte-compatible with the legacy app, so a local schema
change forfeits both. A private patch would have to reimplement the classifier without the
column, i.e. option (c)'s content minus the upstreaming.

**(c) Propose upstream via issues.** Three separable, differently-sized candidates, in
descending order of likely success:
  1. *"The `[…]` snippet check is duplicated at three call sites"* — one classifier function,
     all three sites call it, no schema change, no behaviour change. Mechanical, evidenced
     (`DictateController.kt:2726-2730`, `:2865-2869`, `DictatePromptStrip.kt:304-312`), and
     exactly the size that gets merged.
  2. *"Queued prompts cannot be removed or reordered and are lost on process death"* — a plain
     capability gap, adjacent to work he is already doing on configurable action rows.
  3. *"Rewording requests concatenate instruction, system prompt and user text without
     delimiters"* — framed as **robustness**, not security: transcript prose that reads like an
     instruction changes behaviour, and delimiting the payload also reduces the prompt-echo
     failures the two existing guards defend against. Real risk of being read as theoretical;
     it should lead with a concrete reproducible example or not be filed.

**(d) Retire / let upstream's equivalent replace it.** Only the reprocess queue editor is a
genuine candidate, and only on usage grounds — its transport half should survive either way.
Nothing else here has an upstream equivalent good enough to replace it.

**(e) Reverse-port from upstream.** Per-prompt reasoning-effort override (small, fits
`ParameterRegistry`), and the community prompt library with its backend-free GitHub PR
publishing flow (larger, and it would mean consuming *their* `library.json` or hosting our
own).

*Leaning:* keep clusters 3 and 21, measure cluster 20's editor before defending it, and treat
the deduplicated bracket classifier as a low-effort, high-probability first contribution
upstream — a good way to test the contribution channel before spending effort on anything
bigger.

## 6. Information Gaps

1. **Does the fork's prompt set actually contain `TEXT` pills in daily use**, or was the type
   column solving a bug in a feature nobody uses? *Owner:* Lukas. *Fallback:* `SELECT type,
   COUNT(*) FROM prompts GROUP BY type` on the device; if there are no `TEXT` rows, the
   column's value was purely defect-prevention and its ongoing worth is low.
2. **How often is a history session re-run through a *composed* chain** (rather than a plain
   regenerate)? *Owner:* Lukas. *Fallback:* count sessions with `origin = HISTORY_REPROCESS`
   and a multi-slot `queued_prompt_ids`; below a handful, the editor sheet is
   retirement-eligible while `PromptQueueSlot` stays.
3. **Has any transcript ever produced instruction-shaped confusion in practice** — i.e. is the
   escaping load-bearing today or only in principle? *Owner:* unmeasurable retrospectively
   (prompts are persisted, outcomes are not classified). *Fallback:* treat the `<ui-context>`
   accessibility path as the justification, since that content is genuinely
   third-party-controlled, and treat plain transcripts as hygiene.
4. **Would DevEmperor consider unfreezing the prompts schema** for a typed column, or is legacy
   import compatibility a permanent constraint? *Owner:* an upstream issue, ideally asked
   before writing anything. *Fallback:* assume permanent and offer only the schema-free
   classifier dedup.
5. **Is per-prompt reasoning effort useful for the fork's actual providers?** It is most
   valuable for models with an explicit effort knob. *Owner:* Lukas. *Fallback:* implement it
   as a per-prompt override on the existing `ParameterRegistry` and let it be a no-op where the
   provider has no such parameter.

## 7. References

- [`../sources/fork-feature-inventory.md`](../sources/fork-feature-inventory.md) — clusters 3
  (prompt architecture), 20 (queue transport & reprocess editor), 21 (pill types & overview
  redesign)
- [`../sources/upstream-rewrite-analysis.md`](../sources/upstream-rewrite-analysis.md) — area 4
  (prompt queue), area 7 (prompts management UI), area 8 (typed pills), area 15 (prompt
  contexts), Part C (contribution receptiveness)
- [`../../../decisions/0024-prompt-pill-types.md`](../../../decisions/0024-prompt-pill-types.md),
  [`0012-pipeline-post-processing-conversation.md`](../../../decisions/0012-pipeline-post-processing-conversation.md),
  [`0021-a11y-screen-context.md`](../../../decisions/0021-a11y-screen-context.md)
- [`../../2026-07-02 -
  reprocess-queue-editor.md`](../../2026-07-02%20-%20reprocess-queue-editor.md) (§2.1 holds the
  transport decision), [`../../2026-07-02 -
  history-reprocess-hardening.md`](../../2026-07-02%20-%20history-reprocess-hardening.md)
- Plans (untracked working copies): `tmp/plan-pill-typen.md`,
  `tmp/plan-history-drag-and-pill-fix.md`; `tmp/research-prompt-architektur.md` (the "two
  worlds" audit)
- Our commits: `7a0b8c4f` (cluster 3); `6d7f6531`, `eb5a9a3c`, `00dc182c`, `83fc6d7e` in merge
  `4df8820f` (cluster 20); `9915ffc0`, `34cbf701`, `9a98147b` (cluster 21)
- Our code: `app/src/main/java/net/devemperor/dictate/ai/prompt/PromptBuilder.kt`,
  `core/PromptQueueSlot.kt`, `ai/prompt/PromptTypeClassifier.kt`,
  `rewording/PromptPillPressPolicy.kt`, `history/ReprocessQueueEditorModel.kt`
- Upstream @ `upstream/main` (`3e5ebe46`):
  `dictate/DictateController.kt:240-246,630-646,1446,2726-2730,2855-2880,2865-2869,2931-2944`;
  `dictate/ui/DictatePromptStrip.kt:141-151,304-312`;
  `dictate/ui/DictateInputLayout.kt:145-153`;
  `lib/dictate-core/.../data/prompts/PromptModel.kt:31-42`,
  `PromptsDatabaseHelper.kt:23-30,44-47`, `DictatePromptDefaults.kt:78`,
  `PromptLibraryContribution.kt:198-215`;
  `app/settings/dictate/DictatePromptsScreen.kt:102-109,146-153,184-201,254-335,271-303,543-562,582-598`;
  commit `cf8679df`; issues `#105`, `#124`, `#155`, `#183`, `#194`
