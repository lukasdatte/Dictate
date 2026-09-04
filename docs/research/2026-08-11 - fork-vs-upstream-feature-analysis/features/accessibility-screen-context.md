---
date: 2026-08-11
author: Lukas + Claude (analysis session)
status: Research
context: Fork-vs-upstream analysis of opt-in accessibility screen-context capture — what we built, what Dictate Keyboard 5.3 has (nothing, by explicit design), and whether the feature is still worth pursuing.
related-adrs: ADR-0021, ADR-0012, ADR-0013
related-plan: n/a (plan-free research)
---

# Accessibility Screen Context — Fork vs. Upstream Analysis

Screen context lets the model see a pruned, redacted description of the app the user is
dictating into, so it can resolve references the transcript alone cannot ("send it to Anna" →
which Anna? the one in the visible chat header). **Verdict: absent upstream by explicit design
decision.** Upstream ships an accessibility service too — it hosts their floating bubble and
injects text — but its own doc comment states that it "does not collect screen content", and the
code backs that up: it reads the one focused node and nothing else. This is the area in the whole
comparison least likely to ever be adopted upstream, and the honest reading is that this feature
is fork-only or private-patch for its whole life.

## 1. Feature Overview

An IME sees the world through `InputConnection`, which reaches exactly one field: the one being
typed into. Everything else on screen — the recipient's name in a chat header, the order number
in a form, the person you are replying to — is invisible. That is fine for transcription and
badly limiting for the fork's post-processing model, which is asked to *act* on a dictation
rather than just write it down. "Reply to her that it's fine" is unanswerable without knowing who
"her" is.

The mechanism was not a free choice. The Assist API (`AssistStructure`) is the sanctioned way to
read screen content, but only the device's selected assistant receives it — a keyboard cannot
use it. Content Capture is system/OEM-privileged. `AccessibilityService` is the only mechanism a
third-party keyboard has, and it is powerful enough that the design is built around containing
it rather than exploiting it.

What the user sees: the feature is off by default and requires two independent opt-ins — a
preference (toggleable from a keyboard button, with a long-press that opens the setup flow) *and*
the system accessibility service actually being enabled. With either missing, `uiContext` is
`null` and the pipeline runs exactly as it did before. When it is on, the send tap reads the tree
of the app underneath, prunes and redacts it, and the result travels as a `<ui-context>` data
block inside the same consolidated conversation turn the transcript rides in.

## 2. Our Implementation

**Five files, ~486 LOC, in `accessibility/`.**

- `DictateAccessibilityService.kt` (89) — pull-only. `onAccessibilityEvent` is empty; the service
  exists so the platform will hand us a root node on demand. `accessibility_service_config.xml`
  declares `typeWindowStateChanged` only because the platform requires *some* event type — it is
  the narrowest thing that satisfies the requirement, and nothing consumes it. It sets
  `flagReportViewIds` (a field's resource id is often the only place its purpose is named — high
  value per token) and `flagRetrieveInteractiveWindows` (so the reader can pick the focused
  application window explicitly in split-screen and overlay cases), deliberately omits
  `flagIncludeNotImportantViews`, and pins `isAccessibilityTool="false"` — this assists an AI
  feature, not a user with a disability, and claiming otherwise would be a false declaration.
- `AccessibilityContextReader.kt` (149) — the traversal, and the only place redaction can
  happen, because it is the only place that can see the platform's `isPassword` and input-type
  signals. It never copies text out of a node that is `isPassword` or carries a password /
  visible-password / web-password / email / web-email / postal-address input type. Bounded by
  `MAX_DEPTH = 12` and `MAX_NODES = 600` — each level is a synchronous IPC into another process,
  and a web view can otherwise present thousands of nodes.
- `UiNodeSnapshot.kt` (47) — a plain, Android-free data shape at the boundary, which is what keeps
  ADR-0012's Android-free `ConversationTurnBuilder` intact.
- `ViewTreeSerializer.kt` (117) — renders one indented line per interesting node
  (`EditText #compose_body "Hi Anna" (editable)`), dropping layout scaffolding while re-parenting
  its children so the budget is not eaten by containers. Raw dump ≈ 5–10k tokens; pruned ≈ 1–2k.
  Hard ceiling `MAX_CHARS = 4000`, cut at a line boundary with a truncation marker. A node the
  reader marked `redacted` prints `[redacted]`; this class can never *recover* text, so a bug here
  cannot leak a password.
- `A11yEnablementGate.kt` (84) — distinguishes two questions that are easy to conflate: has the
  user *enabled* the setting (`isServiceEnabled`, via `AccessibilityManager` rather than parsing
  `Settings.Secure`) versus is the service *bound right now* (`isConnected`). They disagree in a
  real window just after the switch is flipped, and conflating them produces a screen that claims
  the feature is on while every dictation silently ships no context. It also builds both
  destination intents — accessibility settings and App info — for the reason in §3 below.

**The three decisions that matter** (all in
[ADR-0021](../../../decisions/0021-a11y-screen-context.md)):

1. **Read at the send tap**, inside `captureFreshConfigSnapshot`, not at pipeline time. The
   pipeline runs on a background executor seconds later, by which point the user may have switched
   apps and the tree would describe the wrong screen. The read runs on a worker and the tap waits,
   bounded by `UI_CONTEXT_TIMEOUT_MS = 250`; a timeout loses the context and never the dictation.
2. **Redact at the point of reading**, not downstream. Nothing later in the chain can leak what
   was never copied.
3. **`<ui-context>` is a `dataSection`**, XML-escaped like the transcript, because it is a third
   party's content and must not be able to forge sibling tags. The base guardrail names
   `<transcript>` as the only data block, so `UI_CONTEXT_GUARDRAIL` extends it — but only when the
   block is actually present.

**The accepted cost.** ADR-0012 persists the built user message verbatim and replays it
byte-for-byte on regenerate. Rather than special-case the context out of the stored message (which
would break that invariant and require superseding ADR-0012), the decision accepts that screen
content lands in the local database and is re-sent on every regenerate — mitigated by redaction,
the 4,000-character ceiling and strict opt-in.

**Two suppressions worth naming.** The context is never attached in PC send-mode
(`WindowsAutoSend.shouldAutoSend`, checked at the send tap in the same instant the divert decision
snapshots) — the dictation is bound for the PC's focused window, so the phone's view tree is the
*wrong* context and must not steer or leak into the prompt (`11e46f37`). And the IME window is
`TYPE_INPUT_METHOD` and never takes input focus, so `getRootInActiveWindow()` returns the app
underneath rather than our own keyboard.

**Size and coupling.** `M`. Two commits inside merge `c8d670d6` (`f3f842e2` `[B1.1]`,
`11e46f37`). The reader, serializer and gate are self-contained Android classes with pure JVM
tests over the redaction rules; the only couplings are the prompt seam in `ConversationTurnBuilder`
and the PC-mode suppression check. Conceptually portable to anything with a prompt-assembly seam.

## 3. Upstream 5.3 Status

**Verdict: absent — by explicit design decision, not by omission.**

Upstream *has* an accessibility service (`dictate/overlay/DictateAccessibilityService.kt`), and it
is more load-bearing than ours: it hosts the floating bubble as a `TYPE_ACCESSIBILITY_OVERLAY`
window, promotes itself to a microphone foreground service during bubble-driven dictation, and
injects text through a three-tier ladder. But its scope is stated in its own doc comment, verified
in the worktree:

> "It only ever reads the **focused** field (to know it is editable and to place text at the
> cursor); it does not collect screen content."

The code matches the comment. `focusedEditableNode()` (`:126-132`) uses `findFocus(FOCUS_INPUT)`;
`findEditableDescendant()` (`:135-143`) is a DFS bounded to depth 6 that returns a *node* and
collects no text; `selectedTextOfFocused()` / `fullTextOfFocused()` (`:422-440`) read that one
node. Grepping for `screenContext`, "screen context", or any traversal concatenating node text
across the tree returns nothing.

One correction to the source report is worth recording, because it affects how firmly the stance
is held. The report cites `accessibility_service_config.xml` not subscribing
`typeWindowContentChanged` as privacy evidence; reading the file, the stated reason is
**performance** — "it fires on every character typed, and each one made the service re-fetch the
focused node's full `AccessibilityNodeInfo` … a per-keystroke IPC flood that caused visible typing
jank", which matches commit `d6ba4829` ("Fix typing jank: stop the a11y service re-fetching the
node tree per keystroke"). The service does subscribe five other event types
(`typeViewFocused|typeViewClicked|typeViewTextSelectionChanged|typeWindowStateChanged|typeWindowsChanged`)
and sets `flagRetrieveInteractiveWindows|flagReportViewIds`, so it is not configured to be
*incapable* of traversal — it simply does not traverse. The privacy commitment rests on the doc
comment and the code, which is still a clear commitment, but it is a stated policy rather than a
structural impossibility.

The nearest adjacent feature is the freeform voice command from the bubble (#230), and it is
instructive precisely because it stops short: it passes **only the current selection**
(`DictateController.kt:1443-1449`), and with nothing selected `input` is null — the field's full
text is not silently attached, let alone the screen's.

**What upstream's equivalent does better:** nothing, because there is no equivalent. What it
*lacks* is the whole feature. The relevant comparison is not capability but posture: they solved
the "let the user aim an instruction at something" problem with an explicit selection, which needs
no traversal, no redaction machinery, no token budget and no privacy argument. That is a smaller
feature and a much cheaper one to defend.

> [!IMPORTANT]
> This is not an unbuilt gap like the audio-focus handling in
> [`recording-robustness.md`](recording-robustness.md). A doc comment that says "it does not
> collect screen content" is a promise made to users about what the service is allowed to do.
> Landing our reader upstream would require the maintainer to retract that promise. Treat
> upstream adoption as effectively closed, and any contribution attempt as likely to be declined
> on principle rather than on implementation quality.

## 4. Assessment — is this feature still sensible?

**The privacy-stance conflict, taken seriously.** Both designs are defensible and they encode
genuinely different bets. Upstream's bet is that a keyboard should be *structurally* incapable of
reading the screen, so that no bug, no future refactor and no compromised build can turn it into
one — the guarantee is the absence of the code. Our bet is that the capability is worth having if
it is contained: redaction at the point of reading (so a downstream bug cannot leak what was never
copied), a platform-enforced blind spot for `FLAG_SECURE` windows, two independent opt-ins, and a
bounded token budget. Ours is a stronger *feature* and a weaker *guarantee*, and no amount of test
coverage converts one into the other.

The residual risks are documented rather than solved, and they are real. ADR-0021's own failure
modes name under-redaction: a plain `EditText` used for a PIN carries none of the sensitive
signals and would be read, and this is not detectable from the accessibility surface. Password
text is stripped from accessibility *events* but a node fetched by traversal can still return real
characters, with behaviour varying by version and OEM — a documented attack surface (CONQUER,
NDSS 2023). And by ADR-0012 conformance, whatever is read is persisted locally and re-sent on
every regenerate. For a single-user sideloaded build where the user *is* the threat model's
owner, that is an acceptable trade. It would not be acceptable for a shipped product without
prominent disclosure, and the ADR says so.

**Does the user need it?** This one is genuinely uncertain and deserves the honest answer: it
depends entirely on whether the post-processing prompts actually reference on-screen entities.
For pure dictation — speak, transcribe, format, insert — the feature contributes nothing but a
250 ms budget on the send tap and a bigger persisted row. Its value is concentrated in the
ambiguity/review flow (ADR-0013) and in instructional prompts, and the fork has no telemetry to
say how often those hit a reference the model could not resolve. In the PC-dictation workflow the
feature is off by construction, and that workflow is a substantial share of this fork's actual
use.

**The Android 13+ friction is a permanent tax.** Distribution is sideload-only, and Android 13+
"Restricted settings" greys out the accessibility toggle for apps installed by a non-session
installer — which is exactly what "open the APK and install" is. The switch is visible and simply
refuses to move, with no explanation. The fork handles this as well as it can be handled: a
dedicated onboarding string spells out the App info → ⋮ → *Allow restricted settings* detour, and
`A11yEnablementGate` exposes both destination intents. But it is still a multi-step, unexplained
system detour that must be re-walked after some updates and after some OEM restores, and it is the
main reason the feature can be *enabled in settings but not bound*, which is precisely the state
the gate's two-question split exists to render honestly.

**What does keeping it cost?** Little in maintenance — five self-contained files, pure tests over
the redaction rules, one prompt seam. The real cost is not code, it is the manifest surface: an
`AccessibilityService` declaration in a keyboard is the single most sensitive thing this app
declares, and it is the reason a Play distribution would need prominent disclosure and a Console
declaration and would carry real review risk. Today that is hypothetical; it stops being
hypothetical the moment distribution changes.

**Upstream signal.** Explicit and negative, per §3. Nothing in the issue tracker or the commit log
suggests movement, and the stance is expressed in a doc comment rather than an omission, which is
about as clear as a maintainer gets without an ADR.

## 5. Options going forward

**(a) Keep in fork as-is.** The natural home. It is opt-in, contained, cheap to maintain, and the
one place it could do damage — PC send-mode — is already suppressed. The one thing worth revisiting
is whether the persisted-and-replayed copy is worth its accepted cost now that the feature has run
for a while; ADR-0012 conformance was the right call structurally, but a stored screen dump is the
part a future reader will question first.

**(b) Port to Dictate Keyboard 5.x as a private patch.** Mechanically the most feasible option in
the whole fork: the reader, serializer and gate are ordinary Android classes, upstream already
*has* an enabled accessibility service (so the permission and onboarding cost is already paid by
their bubble), and the only new thing needed is a prompt-assembly seam — which their
`postProcessTranscript` chain (`DictateController.kt:2818-2846`) provides. This would be a genuine
private patch, kept out of any PR, and it should stay private precisely because it contradicts a
promise their doc comment makes to their users.

**(c) Propose upstream via issue.** Not recommended, and it is worth being concrete about why. An
issue would have to open by asking the maintainer to reverse a stated position, which is the
lowest-yield opening available; the upstream contributor analysis already flags this and the
usage/cost-tracking area as the two directions that run against decisions already made. If it were
attempted anyway, the only shape with any chance is a *question* rather than a proposal — "would
you consider an opt-in screen-context block for the rewording call, or is the no-screen-content
guarantee a firm boundary?" — filed before any code exists, so that a "no" costs nothing.

**(d) Retire it.** Defensible and should not be dismissed. If the answer to Gap 1 below is "the
model rarely needed it", then the feature is buying a manifest-level `AccessibilityService`
declaration, a documented under-redaction risk and a sideload onboarding detour in exchange for
occasional convenience — and the cheaper 80 % (upstream's approach: pass the current selection)
is already available without any of that.

*Leaning:* this stays fork-only whatever happens, so the real question is not where it lives but
whether it earns its manifest surface — and that is answerable only with usage evidence.

## 6. Information Gaps

1. **How often the model actually needs screen context.** The decisive question for §5(d), and
   completely unmeasured: there is no counter for "dictations sent with a non-null `uiContext`",
   let alone for "outputs that were better because of it". **Owner:** Lukas. **Fallback:** the
   persisted conversation rows already contain the `<ui-context>` blocks — a one-off query over
   `conversation_messages` would at least establish how often the block was attached and how large
   it typically is.
2. **Whether the 250 ms send-tap budget is ever actually spent.** The timeout is a ceiling, not a
   measurement; nobody has logged the real read durations on the user's device with the
   accessibility cache warm. **Owner:** device measurement (the method in
   [`latency-and-render-performance.md`](latency-and-render-performance.md) applies directly).
   **Fallback:** the timeout guarantees the worst case is bounded, so this is a comfort question,
   not a correctness one.
3. **How often the Restricted-settings detour has to be re-walked.** If an OS update or an app
   update silently disables the service, the feature fails *open* (context is simply null) and the
   user may not notice for weeks. **Owner:** Lukas. **Fallback:** the keyboard toggle already
   renders an "unavailable — long-press to set up" state, which is the detection mechanism; whether
   it is noticed is the open part.
4. **Under-redaction in practice.** ADR-0021 names the plain-`EditText`-used-for-a-PIN case as
   undetectable from the accessibility surface. Nobody has audited real screens the user dictates
   into for fields that carry secrets without any sensitive input-type signal. **Owner:** a
   deliberate audit pass. **Fallback:** the current redaction set is deliberately generous
   (password, email, postal, phone) and over-redaction was accepted as the correct failure
   direction.
5. **Whether persisting the block is still the right call.** It follows from ADR-0012, but a
   stored screen dump has a different retention profile than a stored transcript. **Owner:**
   revisit alongside any history-retention work. **Fallback:** unchanged — the 4,000-char ceiling
   and redaction bound the exposure.

## 7. References

**Source reports**
- [`../sources/fork-feature-inventory.md`](../sources/fork-feature-inventory.md) — cluster 23
  (accessibility screen context)
- [`../sources/upstream-rewrite-analysis.md`](../sources/upstream-rewrite-analysis.md) — area 12
  (absent by explicit design), Part C (contributor receptiveness)

**Our ADRs / code**
- [`ADR-0021 — Screen context via AccessibilityService as an opt-in prompt data block`](../../../decisions/0021-a11y-screen-context.md)
  — the full research, alternatives (Assist API, `InputConnection`-only, non-persisted context,
  read-at-recording-start, event consumption, `isAccessibilityTool`) and failure modes
- [`ADR-0012 — Post-processing conversation`](../../../decisions/0012-pipeline-post-processing-conversation.md)
  — the verbatim-persistence and Android-free-builder constraints this works within
- [`ADR-0013 — Review panel and ambiguity modes`](../../../decisions/0013-review-panel-and-ambiguity-modes.md)
  — the `forceTurn` durchstich this mirrors, and the flow where screen context pays off
- Code: `accessibility/{DictateAccessibilityService,AccessibilityContextReader,UiNodeSnapshot,ViewTreeSerializer,A11yEnablementGate}.kt`;
  the prompt seam in `ai/conversation/ConversationTurnBuilder.kt:51-67`; the capture site
  `core/DictateInputMethodService.java` (`readUiContextOrNull`, `UI_CONTEXT_TIMEOUT_MS`); the
  toggle axis in `state/modules/FeatureToggleModule.kt` (`ToggleScreenContext`,
  `SetScreenContextAvailable`)
- Plan (untracked): `tmp/plan-a11y-widget-pcmode.md` Block B1
- Commits: `f3f842e2` `[B1.1]`, `11e46f37` (PC send-mode suppression), both inside merge
  `c8d670d6`; `ed5876e7` (promotion of the plan-scoped ADR)

**Upstream evidence** (paths @ `upstream/main` `3e5ebe46`)
- `app/.../dictate/overlay/DictateAccessibilityService.kt:36-50` (the "does not collect screen
  content" doc comment), `:126-132` (`focusedEditableNode`), `:135-143`
  (`findEditableDescendant`, depth-6 DFS returning a node), `:422-440` (single-node text reads)
- `app/src/main/res/xml/accessibility_service_config.xml` (no `typeWindowContentChanged` — for
  typing-jank reasons, see `d6ba4829`)
- `app/.../dictate/DictateController.kt:1443-1449` (freeform voice command uses selection only),
  `:2818-2846` (`postProcessTranscript` — the prompt seam a private patch would land in)
- Issue: #230 (freeform voice command from the bubble), #88 (the floating button the a11y service
  exists for)
