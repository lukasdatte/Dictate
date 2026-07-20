# Concept Sketch — Dictate Desktop + Peer Distribution

Status: **Decided** — all 34 questions of the question catalog are answered
(as of 2026-07-19); this sketch reflects the binding decision state.
Most important deviations from the original recommendations: **F1 Compose Desktop
instead of browser UI** (this drops F3 TS codegen and F13 without replacement), **F12
envelope encryption instead of zero-knowledge**, **F15 full Room schema parity**,
**F18 full review mode from v1**, **F22 Android migration immediately with v1**.
Basis: `bestandsaufnahme.md` and `fragenkatalog.md` in the same folder.
Implementation plan: `~/.claude/plans/desktop-companion-v1.md`.

## 1. Guiding idea: three layers instead of two apps

The existing architecture already separates cleanly into "pure logic" (reducers,
AI contracts, codecs) and "platform render/IO" (Android views, Win32). The concept
generalizes this seam into three layers:

```
┌─────────────────────────────────────────────────────────────────┐
│  Peer network on the tailnet (NEW)                              │
│  Every companion = catalog provider AND consumer; an optional   │
│  headless "hub" is JUST another peer (same protocol).           │
│  Distribution: prompts, providers+keys, models, profiles —      │
│  hash-based subscription sync, pull-only, encrypted secrets     │
└──────────────▲──────────────────────────▲───────────────────────┘
               │ HTTPS, pull              │ HTTPS, pull + serve
┌──────────────┴───────────┐  ┌───────────┴──────────────────────┐
│  :app (Android IME)      │  │  :companion (desktop host, JVM)  │
│  Recording+pipeline phone│  │  NEW: recording+pipeline desktop │
│  existing                │  │  Global hotkey, text insertion,  │
│                          │  │  SQLDelight history, warm tray   │
└──────────▲───────────────┘  └───────────▲──────────────────────┘
           │ uses                         │ uses (JVM) + serves
┌──────────┴──────────────────────────────┴──────────────────────┐
│  Shared core (extension of :shared)                            │
│  :shared-ai  — AIProvider, Runner, orchestrator core,          │
│                PromptBuilder/Service, Conversation/Codec,      │
│                ReviewDecision, ParameterRegistry, ModelFetcher │
│  :shared     — Wire-DTOs + Konform + ProtocolCodec (existing)  │
│                + new hub/UI protocol families                  │
└────────────────────────────────────────────────────────────────┘
           Desktop UI: Compose (mini panel + management screens,
           directly in the companion process — F1/F5 decided)
```

Core principle (generalized from ADR-0004/0027): **state, pipeline and UI live in the
JVM companion process** — one language (Kotlin), no second AI/state
implementation. The AI infrastructure exists exactly ONCE (Kotlin/JVM, shared
between `:app` and `:companion`); the Compose mini panel is a native window of the
same warm process (F5), which makes UI protocol, WebSocket state sync and
TS-type codegen entirely unnecessary.

## 2. Module split (refactoring path)

1. **`:shared-ai` (new, pure JVM like `:shared`)** — extraction from `app/.../ai/`:
   - Immediately: `AIProvider`, `AIProviderException`, `ModelInfo`, `ParameterDef/Registry`,
     runner interfaces + DTOs, `ai/prompt/*`, `ai/conversation/*` (incl.
     `StructuredResponseCodec`, `ReviewDecision`).
   - With ports: `AIOrchestrator` (ports `AiConfig` instead of SharedPreferences,
     `UsageSink` instead of UsageDao), `RunnerFactory`, the three runners (`ProxyConfig` port,
     `AudioDurationReader` port), `ModelFetcher`; `org.json` → kotlinx-serialization.
   - `:app` implements the ports with SharedPreferences/Room (behavior unchanged),
     `:companion` with SQLDelight/Settings.
   - Why its own module instead of in `:shared`: `:shared` has the strict
     `SharedPurityTest` (no okhttp-api leak, jvmTarget 1.8) and is the
     wire protocol; AI SDKs (openai-java, anthropic-java) would dilute the purity.
     Separate module = separate dependency policy, same principles.
2. **Extend `:companion`** — new subsystems:
   - `capture/`: audio recording via `javax.sound.sampled` (JVM, cross-platform
     — F4 decided). Adopt the rolling-segment idea from ADR-0007
     (crash resilience), format WAV/Opus instead of AAC.
   - `pipeline/`: lean desktop pipeline orchestrator on `:shared-ai`
     (serialization as in ADR-0009: one queue, one job). No port of the full
     Android `state/` orchestrator — it is tailored to IME axes; the
     desktop needs ~4 axes (recording, pipeline, review, ui-panel).
   - `hotkey/`: global hook (Win32 `RegisterHotKey`/low-level hook via JNA — the pattern
     `Win32InputPerformer` exists; Linux/macOS ports later, port pattern like
     `TextInserter` ADR-0018).
   - `ui/panel/`: **Compose mini panel** (F1/F5): frameless, always-on-top,
     **focus-free** window (F21, WS_EX_NOACTIVATE-like behind a
     window port), toggled by the warm process in <50 ms. Renders
     `DesktopUiState` directly (Compose state, no wire protocol). Recording core
     as a 1:1 rebuild of the widget design language in Compose canvas (F19:
     waveform bars with age fade, HSV glow, ripple pulse — parameters/curves
     taken from `AmplitudeProcessor`/`VisualizerUtils`); management screens
     in the existing Compose Material3 style with the adopted color/shape language.
   - `data/`: session schema extension (SQLDelight) in **full Room schema parity**
     (F15 decided): `sessions`/`transcriptions`/`processing_steps`/
     `conversation_messages` with the same Double-Enum values + CHECK constraints;
     parity tests like `OriginCheckConstraintParityTest` exist as a template.

## 3. Technology decision, desktop UI (trade-offs, decided per F1)

| Option | Description | Pro | Contra |
|---|---|---|---|
| **A. Browser UI + companion backend (recommendation)** | TS SPA, served by the local companion; state/pipeline/secrets on JVM; WebSocket sync | One AI implementation (Kotlin, shared with the app); secrets never in the browser; hotkey + text insertion + keep-warm can only be done by the native process; browser UI freely designable; CORS/key issues disappear | Two languages (Kotlin+TS); the UI protocol must be typed twice (→ schema codegen, see below) |
| B. Extend Compose Desktop UI (no browser) | Recording UI directly in the existing Compose app | Least new build; one language; hotkey/keep-warm trivial | Does not satisfy "browser"; Compose Desktop UI finesse (animations) weaker; no path to later web access from foreign machines |
| C. Kotlin/Multiplatform + Wasm/JS | Migrate `:shared`(+`-ai`) to KMP, browser client in Compose Web/Kotlin JS | Real code sharing all the way into the browser | Superseded ADR-0015 (deliberately decided against KMP); Kotlin ceiling 2.1.20 collides with Wasm maturity; AI SDKs are JVM-only → runners still not browser-capable; high toolchain risk |
| D. Full TS rebuild (browser calls providers directly) | Browser app with its own AI layer | No local process needed | Double implementation of the entire AI/prompt logic (violates the DRY core goal); API keys in browser storage; CORS blocks (Anthropic/OpenAI only partly browser-capable); no global hotkey, no text insertion into foreign apps |

**DECIDED (F1): Option B — Compose Desktop UI, no browser.** The user has
decided against the browser UI and for extending the existing Compose app:
one language (Kotlin), minimal new build, hotkey/keep-warm/
window control trivial in its own process (F5: native frameless
always-on-top Compose window, <50 ms toggle). This drops without replacement: the
UI wire protocol, the WebSocket state sync, the TS-type-codegen topic (ex-F3)
and the browser secret question (ex-F13). The options table above remains as
documented decision context. The weaker web reachability
from foreign devices is deliberately given up; a later web access would be a
new decision.

## 4. Distribution architecture: peer catalog over Tailscale

> Addendum incorporated: different systems/users connect directly
> with each other (P2P over Tailscale); a dedicated server is possible long-term,
> but in the model is **just another peer**. ONE protocol, ONE data model.

### Peer model & transport

A **peer** is any participant that speaks the catalog protocol. Anyone who runs a
server can offer — per ADR-0017 that is today exactly the
desktop companions (each already brings a Ktor server addressable in the tailnet,
bind catalog ADR-0023) plus optionally a headless "hub" service
(same code, without UI, e.g. on a VM). **Android remains a pure consumer**
(server-less per ADR-0017); sharing from Android means: publish to your own
companion/hub peer.

- **Address:** MagicDNS name + port (`heim-pc.tailXXXX.ts.net:8756`) — the familiar
  tailnet pattern; no port forwarding, TLS via Tailscale/Tailscale-Serve.
- **Establishing a connection:** proven pairing pattern from `shared/auth/`
  (one-time token → peer secret, server-side only a hash) — one credential per
  peer relationship; the tailnet is network trust, the app credential is
  defense-in-depth (ADR-0017 doctrine unchanged).
- **Protocol:** new additive payload family on the existing wire stack
  (`:shared` DTOs + Konform + ProtocolCodec, extension following the ADR-0025 pattern:
  new endpoints + `HealthResponse` capability flag `supportsCatalog`, no
  version bump). Roughly: `GET /v1/catalog` (root hash + entity index),
  `GET /v1/catalog/entity/{id}` (payload), each only `visibility: shared`.

### Subscription sync: hash-based, pull-only

Two acquisition modes per acquired datum:

- **Subscribe (`SUBSCRIBE`):** locally, the **content hash** (SHA-256 over the
  canonical serialization) is stored per acquired entity.
  Sync run: first `GET /v1/catalog` → compare root hash (one request for "has
  anything changed?"), on divergence diff the entity index and pull only changed
  entities anew. Change ⇒ update local copy + **notification**
  (companion: tray notification + peer-explorer badge; Android:
  system notification).
- **One-shot pull (`ONE_SHOT`):** copy without a stored peer binding for the
  sync (origin `sourceRef` is retained as a display metadatum).

Pull-only with polling (interval + trigger on app/panel open) — consistent with
the "no back-channel" doctrine (ADR-0017/0020); the root hash makes the polling
cheap (one GET, mostly 304-like). Locally edited subscribed entities: copies
are **read-only** by default; "edit" explicitly decouples as a fork
(no merge problem, question F29).

Relation to the ADR-0020 pattern: the session sync (phone→PC) remains unchanged
cursor-based; the catalog sync is deliberately hash-based, because entities are small,
rarely changed and individually identifiable — hashes additionally provide
drift detection (locally edited? peer changed?) for free.

### Peer Explorer (UI)

Its own screen (companion UI/browser UI; Android as a lean settings page):
list of connected peers (name, MagicDNS address, status/last contact), per
peer the acquired entities with type, mode (subscription/one-shot), last reconcile and
state (up to date / update available / locally decoupled), plus actions
(reconcile now, unsubscribe, fork). The reverse view "what do I offer?" =
visibility management of your own entities. Data basis: `peers` and
`subscriptions` tables (peer ref, entity ref, mode, lastHash, lastCheckedAt) —
origin is thus fully traceable.

### Long-term server path (only prepare, do not build)

Goal per the addendum: later, all API accesses should be able to run over a server
(computations + certain prompts fully server-side, so that API keys
never leave the server device). Preparation without added complexity — three
seams suffice, all of which almost already exist:

1. **`AIOrchestrator` is already the single AI seam** (CLAUDE.md convention:
   never SDKs directly). The extraction to `:shared-ai` with the `AiConfig` port cements
   this; a later `GatewayCompletionRunner`/`GatewayTranscriptionRunner` is just
   another `RunnerFactory` variant behind the same interface.
2. **`ProviderConfig.kind = LOCAL | GATEWAY`** — the enum value `GATEWAY` is reserved
   and documented in the data model, but not implemented (the Double-Enum
   pattern allows a later migration cleanly). A gateway provider points, instead of at
   a vendor API, at a peer; the key then lives only there. Server-side
   prompts would be `visibility: shared` entities there whose *text* the peer does not
   even deliver on demand, but only executes by reference — that stays
   explicitly future design, the entity model (references instead of inline values)
   makes it possible.
3. **Protocol namespace:** the catalog family is cut so that a
   later `/v1/ai/*` family (proxy calls) fits additively next to it (ADR-0025
   pattern). No advance implementation, no speculative code — only the
   documented placeholder in the ADR + DTO namespace.

More preparation than these three points would be the "excessive complexity" that the
addendum excludes.

### Entity model (shareable, versioned)

```
ProviderConfig  — provider definition: AIProvider kind (OPENAI/ANTHROPIC/.../CUSTOM),
                  baseUrl, capability flags; key reference optional (local OR hub)
ApiCredential   — API key as encrypted blob + metadata (provider, label);
                  plaintext exists only client-side after decryption
ModelRef        — model definition: providerRef, modelId, function
                  (TRANSCRIPTION/COMPLETION), parameter defaults (ParameterDef values)
Prompt          — like PromptEntity minus pill fields: name, text, requiresSelection,
                  autoApply recommendation; type dropped (no pills on desktop)
Profile (Preset)— the configurable unit: { transcription: ModelRef,
                  completion: ModelRef, prompts: [PromptRef|inline], systemPrompt
                  selection, AmbiguityMode, parameter overrides }
```

Every entity: `id (uuid)`, `contentHash` (SHA-256 of the canonical serialization —
at once a sync watermark and drift detector), `visibility (private|shared)`,
`sourceRef?` (origin peer + original id for acquired copies), `updatedAt`.
**Sharing = making visible** (`visibility: shared` in your own catalog); **acquiring =
copy** (a subscription keeps it up to date via hash reconcile, one-shot does not — see
"Subscription sync" above). The existing PromptImportExport v2 JSON is evolved into
the wire representation (v3: + Profile/ModelRef/ProviderConfig), so that file export
and catalog protocol use the same serialization — the canonical form is at the same
time the hash basis (SSoT in `:shared`). Local entities and acquired copies
coexist; a profile references prompts/models by stable ID.

### Encryption concept (only secrets, rest plaintext)

Requirement: keys transmitted encrypted AND stored locally encrypted;
for profiles, only access credentials encrypted.

- **Transport:** always TLS (peers via Tailscale/Tailscale-Serve; the pattern is
  established in the project).
- **At-rest client-side (both platforms):** envelope encryption — a
  local master key in the platform keystore (Android Keystore; desktop: DPAPI/
  Keychain/libsecret behind a `SecretStore` port in the style of ADR-0018),
  payload AES-256-GCM. This also ends the "plaintext SP" situation deferred in
  ADR-0017 → its own project-wide ADR "Secret Storage".
- **Key sharing over peers — DECIDED (F12): envelope encryption, NO
  zero-knowledge, NO share-password step.** Offering peers MAY decrypt the
  credentials they manage: the offering peer holds the
  credential in its local SecretStore and delivers it on acquisition over the
  TLS channel; the recipient immediately puts it into its own SecretStore.
  The obligation thus remains exactly the requirement pair "transmitted encrypted"
  (TLS/Tailscale) + "stored locally encrypted" (SecretStore on both sides,
  F11); no plaintext key ever lies on any disk. The catalog index carries for
  credentials only metadata (provider, label, contentHash over the encrypted
  at-rest blob or a key fingerprint) — secrets never appear in the index.
  Deliberately accepted trade-off: whoever offers a key trusts the
  peer operator anyway (self-hosted context); the zero-knowledge variants
  (share password, X25519 sealed box) remain a documented later
  hardening option in the question catalog, but are NOT part of v1.

### Model selector reworked

Two-stage, data-driven instead of UI-wired: **1) choose/create provider**
(`ProviderConfig`, local or acquired from a peer) → **2) choose model**
(union of live `ModelFetcher` result + acquired `ModelRef`s + free text
for Anthropic/Custom). The same component feeds Android settings (refactor of
`APISettingsActivity` onto the new data model) and desktop UI. Models are thus
automatically shareable (a ModelRef is an entity, no longer a pref string).

### Prompt editor

Create locally (as today, minus pill semantics) + "Shared prompts" view per
peer (browse in the peer catalog, copy-to-local or subscribe). The same
UI pattern on Android (existing, freshly reworked PromptsOverview
extended by an origin badge local/peer) and desktop (browser UI); the peer
explorer links here.

## 5. Naming proposal for the preset unit: **"Profile" (Ger. `Profile`)**

Rationale: a profile is, in general usage, exactly that — a named,
switchable combination of tool + settings (browser profiles, VS Code
profiles, OBS profiles). "Preset" sounds like pure parameter values, "Set" is too
generic, "Workflow" suggests multi-step, "Mode" collides with
`AmbiguityMode`/`ViewMode`. "Profile" carries the semantics "model + prompts +
processing settings, duplicate me, share me" naturally and is
DE/EN-identical. **DECIDED (F24): the unit is called "Profile"** (`Profile` in
code). Content per F17: transcription ModelRef (+ language/style prompt),
completion ModelRef + parameter overrides, activated post-processing prompts
(ordered, with autoApply), system-prompt selection, AmbiguityMode; credentials
are exclusively referenced, never embedded.

## 6. What is deliberately NOT ported

- **Prompt pills** (given) — `PromptType.TEXT`/pill UI stays Android-only;
  the desktop prompt model knows no pill fields.
- **Android `state/` orchestrator as a whole** — only the pattern (pure reducers,
  one dispatch door) is adopted, not the 19 IME axes.
- **Review-panel implementation** — the logic (`ReviewDecision`,
  `ConversationContinuation`) is shared, the UI is created anew in Compose;
  per F18 the **full review mode from v1 incl. dictated refinement
  (re-dictate)** arrives — no staging. Requires an ADR that revises the "review is
  IME-only" decision (ADR-0013/0027-F8) for the desktop host.
- **Phone↔PC dispatch path** — stays unchanged; the new desktop mode is
  additive (its own recording), no replacement for PC dictation via phone.

## 7. Build-out stages (decided — details in the implementation plan)

1. **Foundation:** `:shared-ai` extraction + ports; `:app` migrated to ports
   (behavior-neutral, high testability). In parallel: project-wide SecretStore
   (F11) incl. migration of the Android plaintext keys.
2. **Entity model:** ProviderConfig/ModelRef/Prompt/Profile + canonical
   v3 serialization in `:shared`; **Android migrates immediately with v1** (F22:
   prefs→DB, `APISettingsActivity` rebuild onto the entity model).
3. **Desktop dictation:** companion recording + pipeline + hotkey + warm
   Compose mini panel (focus-free, auto-insert per F21), history in full
   schema parity (F15), **full review mode incl. re-dictate (F18)**,
   profile switcher, model switcher, prompt editor.
4. **Peer sync:** catalog protocol + subscription/hash sync + notifications +
   Peer Explorer (acquisition + offer view, F34) + sharing (prompts → profiles →
   keys per F12 envelope), Tailscale discovery port (F26: manual + QR AND
   API enumeration), v3 file export; optional headless hub peer as a
   `--headless` deployment variant of the same code.

New ADRs that this concept at minimum requires: (a) desktop recording+pipeline
host (extends the 0017 role model), (b) browser UI as render host + UI protocol
(extends 0004/0016), (c) peer catalog distribution architecture (companion server
becomes a multi-purpose peer, hash-based subscription sync, pull-only; extends 0017/0020 and
reserves the gateway path), (d) secret storage project-wide (resolves the 0017 defer),
(e) profile entity model, (f) desktop review (revises the
0013 surface constraint).
