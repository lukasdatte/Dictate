# ADR-0021: Screen Context via AccessibilityService as an Opt-In Prompt Data Block

- **Status:** Accepted
- **Date:** 2026-07-15
- **Subsystem:** ai, service, privacy
- **Scope:** Project-Wide

## Summary in plain language

Dictate can now send a description of what is on the user's screen to the AI
along with their dictation, so the model can resolve things the user refers to
("send it to Anna" → which Anna? the one visible in the chat). Reading another
app's screen requires Android's accessibility API, which is powerful and
invasive, so the feature is off by default, requires the user to enable a
system setting, and strips anything that looks like a secret before it leaves
the device.

## Research

- The IME already has `InputConnection`, which reads the field being typed
  into. It cannot see anything else on screen.
- The **Assist API** (`AssistStructure`) is the sanctioned way to read screen
  content, but only the device's selected assistant receives it. A keyboard
  cannot use it.
- **Content Capture** is a system/OEM-privileged API, unavailable to
  third-party apps.
- **AccessibilityService** is therefore the only realistic mechanism.
- The IME window is `TYPE_INPUT_METHOD` and never takes input focus, so
  `getRootInActiveWindow()` returns the *app underneath*, not our own keyboard.
- `FLAG_SECURE` windows are withheld from accessibility retrieval by the
  platform — banking apps are invisible with no cooperation from us.
- Password text is stripped from accessibility *events* but a node fetched by
  tree traversal can still return the real characters. Behaviour varies by
  version/OEM; this is a documented attack surface (CONQUER, NDSS 2023).
- Android 13+ "Restricted settings" greys out the accessibility toggle for apps
  installed by a non-session installer — i.e. every sideloaded build.
- GUI-agent projects (AppAgent, DroidBot, Android World) converge on: prune to
  interactive/labelled nodes, keep className/text/description/resource-id, drop
  layout scaffolding. Raw dump ≈ 5–10k tokens; pruned ≈ 1–2k.

## Context

ADR-0012 makes post-processing one consolidated turn built by an **Android-free**
`ConversationTurnBuilder`, and persists the built user message **verbatim**,
replaying it byte-for-byte on regenerate. Any screen context therefore (a) cannot
arrive as an Android type, and (b) lands in the local database and is re-sent on
every regenerate.

Distribution is **sideload only**, so Play's accessibility policy is not the
binding constraint; the Restricted-settings gate is.

## Decision

1. **AccessibilityService, pull-only.** `DictateAccessibilityService` consumes no
   events (`onAccessibilityEvent` is empty). The IME reads the tree on demand.
2. **Read at the send-tap**, in `captureFreshConfigSnapshot`, alongside
   `EditorInfo`. Not at the pipeline (background executor, seconds later — the
   user may have switched apps and the tree would describe the wrong screen).
3. **Redact at the point of reading.** `AccessibilityContextReader` never copies
   text out of a node that is `isPassword` or carries a password / email /
   postal / phone input type. Nothing downstream can leak what was never read.
4. **Plain data at the boundary.** The reader emits `UiNodeSnapshot` (Android-free);
   `ViewTreeSerializer` renders it. ADR-0012's Android-free builder is preserved,
   and the redaction rules get JVM tests.
5. **`<ui-context>` is a `dataSection`** (escaped), never a `section`. It is a
   third party's content and must not be able to forge tags.
6. **The guardrail is extended when — and only when — the block is present**
   (`UI_CONTEXT_GUARDRAIL`), because the base guardrail names `<transcript>` as
   the only data block.
7. **Persist it** (accept ADR-0012's verbatim rule) rather than special-casing
   the block out of the stored message. Mitigated by (3), a 4000-char ceiling,
   and strict opt-in.
8. **Opt-in twice:** `Pref.AccessibilityContextEnabled` (off by default) **and**
   the system service being enabled. Either missing → `uiContext = null` and the
   pipeline runs exactly as before.
9. **Bounded blocking:** the send-tap waits at most 250 ms for the read; a
   timeout loses the context, never the dictation.

## Alternatives

- **Assist API** — rejected: requires being the device assistant.
- **InputConnection only** — rejected as insufficient: it cannot see the
  surrounding screen, which is the whole feature. (It remains the right tool for
  the edited field and is unaffected.)
- **Exclude the context from persistence** — rejected: it would break ADR-0012's
  verbatim-replay invariant and require superseding it. Redaction + opt-in +
  truncation address the same risk without splitting the message model.
- **Read at recording-start** rather than send-tap — closer to "what the user saw
  while speaking", but no snapshot container exists yet at that point, and the
  screen rarely changes mid-dictation. Rejected for a real gain that is small
  versus a structural cost that is not.
- **Consume accessibility events** — rejected: a constant stream of other apps'
  UI changes for a snapshot we can pull.
- **`isAccessibilityTool="true"`** — rejected as a false declaration. This
  assists an AI feature, not a user with a disability.

## Consequences

### Positive
- Screen context is available to the model without any new permission model.
- Redaction rules are pure and exhaustively unit-tested.
- The feature is inert by construction until deliberately enabled.

### Negative
- The send-tap can block up to 250 ms when the feature is on.
- Screen content lands in the local conversation history and is re-sent on
  regenerate — the accepted cost of ADR-0012 conformance.
- Sideloaded installs need the "Allow restricted settings" detour, which is
  friction and a support burden.
- Play distribution would need prominent disclosure + a Console declaration and
  would carry real review risk. Not a concern today; would be if that changes.

### Failure Modes
- **Over-redaction:** an unusual input type hides a field the user meant to
  reference. Deliberate — the trade against leaking a password is not close.
- **Under-redaction:** a field holding a secret with none of the sensitive
  signals set (e.g. a plain `EditText` used for a PIN) would be read. Not
  detectable from the accessibility surface.
- **Stale tree:** the user switches apps between the send-tap and the read
  completing. Bounded by the read happening synchronously at the tap.
- **Token growth:** a dense screen inflates the prompt and the persisted row.
  Bounded by `MAX_CHARS` / `MAX_NODES` / `MAX_DEPTH`.

## References

- Plan: `tmp/plan-a11y-widget-pcmode.md` Block B1
- ADR-0012 (post-processing conversation) — the persistence + Android-free
  builder constraints this decision works within
- ADR-0013 (ambiguity modes) — the `forceTurn` durchstich this mirrors

## Decision History

- **2026-07-15** — Initial draft alongside the B1 implementation.
  - **Trigger:** Feature request "let the model see the UI".
  - **Reasoning:** Recorded here rather than inline because the persistence and
    redaction trade-offs are binding on any future context source.
