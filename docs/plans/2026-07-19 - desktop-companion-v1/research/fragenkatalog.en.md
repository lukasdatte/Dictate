# Question Catalogue — Dictate Desktop (Browser) + Server Distribution

Numbered for answering one by one. Per question: context, options with trade-offs,
recommendation. References: `bestandsaufnahme.en.md`, `konzept-skizze.md`.

---

## Block A — Platform & Technology

### F1: How hard is the "browser" requirement for the desktop UI?

Context: Hotkey, keep-warm, and text insertion into foreign apps can only be done by
a native process — the companion already exists as a warm JVM tray process with a
Compose UI. The question is whether the *user interface* has to run in the browser
or whether "desktop app" is enough.

- **Option 1 — Browser UI, served by the companion (concept recommendation):** TS SPA,
  companion delivers assets + WebSocket state. Pro: modern UI freedom, later also
  reachable from other devices (Tailscale), clear render-host separation. Con:
  second language (TS), UI protocol needed.
- **Option 2 — Extend the Compose desktop UI:** recording panel directly in the
  existing app. Pro: minimal new build, one language. Con: no browser, web
  design language (animations) harder to reproduce.
- **Option 3 — Hybrid:** Compose window with an embedded WebView that renders the
  browser UI. Pro: window management native, UI web. Con: WebView dependency
  on the JVM (KCEF/JCEF) is heavyweight (~100 MB bundle).

**Recommendation:** Option 1. If the mini panel as its own browser window
(app-mode window from Chrome/Edge) is acceptable, the WebView question is dropped
entirely. → depends on F5.

### F2: Where does the desktop client's AI pipeline run?

Context: The runners (openai-java/anthropic-java) are pure JVM libraries;
direct browser calls to the providers fail partly on CORS and put keys into the
browser.

- **Option 1 — On the companion (JVM):** one shared Kotlin implementation
  (`:shared-ai`), the browser stays thin and secret-free. Con: none.
- **Option 2 — In the browser (TS rebuild):** double implementation of the entire
  prompt/conversation/fallback logic, keys in browser storage, CORS risk.

**Recommendation:** Clearly Option 1 — this is the core of "split rather than port".

### F3: How is the UI/hub protocol shared between Kotlin and TypeScript?

Context: ADR-0015 deliberately rejected KMP; `:shared` is pure JVM without a JS target.
A browser client, however, needs typed DTOs, otherwise exactly the drift arises that
ADR-0016 prevents.

- **Option 1 — Schema export + codegen:** generate JSON Schema/OpenAPI from the
  kotlinx-serialization DTOs, code-generate TS types from it
  (build step). Pro: `:shared` stays SSoT, ADR-0015 untouched. Con:
  generator maintenance; Konform constraints don't travel along (server keeps validating).
- **Option 2 — Convert `:shared` to KMP with a JS target:** real sharing. Con:
  supersede ADR-0015, Kotlin ceiling 2.1.20, toolchain risk, the AI SDKs
  stay JVM-only anyway.
- **Option 3 — Maintain manually in duplicate:** defensible only for a very small UI
  protocol; drift risk.

**Recommendation:** Option 1. The browser is a "trusted thin client" behind the
companion — validation authority stays server-side, TS only needs types.

### F4: Who records the audio — browser or companion?

Context: Android uses MediaRecorder (AAC/M4A, rolling segments ADR-0007). On the
desktop there are two microphone access paths.

- **Option 1 — Browser (`getUserMedia` + MediaRecorder API):** Pro: device-selection UI
  and permission model come for free, waveform data (AnalyserNode) directly for the
  visualization. Con: recording dies with the UI window (contradicts
  "panel may collapse"), format webm/opus, audio must be streamed to the companion.
- **Option 2 — Companion (`javax.sound.sampled`):** Pro: recording independent of the
  UI window (robust, fits the warm process), WAV→provider directly or
  Opus encoding; segment/recovery logic following the ADR-0007 model server-side.
  Con: build device selection yourself; amplitude feed must go over WebSocket to the UI.
- **Option 3 — Hybrid:** browser records, streams chunks live to the companion,
  which persists. Highest complexity.

**Recommendation:** Option 2. Recording belongs to the kept-warm process, not to the
transient UI window; amplitude streaming over the state WebSocket that is needed
anyway is cheap.

### F5: How is the mini panel technically kept warm and toggled by hotkey?

Context: Requirement 1 — a defined key combination opens a persistently warm,
small UI.

- **Option 1 — Companion-owned Compose window (frameless, always-on-top), content
  native:** Pro: <50 ms toggle, full window control (position, focus return).
  Con: UI in Compose instead of web (couples to F1 Option 2).
- **Option 2 — dedicated browser window in app mode**, opened by the companion at
  startup and afterward only shown/hidden: Pro: web UI (F1 Option 1), warm through
  a persistent tab + open WebSocket. Con: window show/hide via
  OS APIs (Win32 FindWindow/ShowWindow) is fiddly; the user can close the window
  (companion must re-spawn it).
- **Option 3 — Compose window with WebView (KCEF):** unites both, but a heavy
  bundle footprint and JCEF maintenance.

**Recommendation:** Option 2 as the target (consistently browser), with Option 1 as a
fallback line if window control proves too fragile in practice. The hotkey is in
any case registered by the companion (JNA/Win32; port pattern like ADR-0018 for
later Linux/macOS support — see F6).

### F6: Which desktop OSes are in scope?

Context: The companion is Windows-first (Win32 insertion via JNA) but runs on
Linux/macOS with no-op insertion ("canInsert=false"). Hotkey + insertion need
their own ports per OS.

- **Option 1 — Windows-only (v1):** covers the existing usage context; all
  ports (hotkey, insertion, autostart) exist only for Win32.
- **Option 2 — Windows + Linux:** Lukas' dev environment is Linux — valuable for
  dogfooding; hotkey (X11/Wayland issues!) and insertion (xdotool/wtype) are
  significantly limited on Wayland.
- **Option 3 — all three.**

**Recommendation:** Option 1 for insertion/hotkey, but all new builds behind ports
(like `TextInserter`), so that Linux dogfooding works immediately with clipboard-only
+ UI button instead of a hotkey.

---

## Block B — Server & Operations (Hub → Peer Network)

> **Reframing by the addendum (see Block G):** The connection model is a
> peer network over Tailscale — every companion can be a provider AND a consumer, a
> dedicated "hub" is just an optional headless peer with the same protocol.
> F7–F10 apply analogously to every providing peer.

### F7: What is the hub — deployment and tenancy model?

Context: "Server-side distribution from the start." There is currently no
server component at all besides the local companion.

- **Option 1 — Self-hosted single-tenant (one hub per user/family/team):**
  small Ktor service (Docker/VM), reuse of `:shared` patterns (ProtocolCodec,
  Konform), operated e.g. on your own VM behind Tailscale/HTTPS. Pro: no
  account system needed (pairing-style tokens suffice), privacy trivial,
  fits the existing Tailscale infrastructure. Con: sharing only within
  your own hub.
- **Option 2 — central multi-tenant service (SaaS-like):** public sharing
  between strangers. Pro: "community prompts". Con: accounts, moderation,
  operating costs, abuse (shared keys!), legal questions.
- **Option 3 — both in layers:** v1 self-hosted, protocol designed so that
  a public catalogue can dock on later.

**Recommendation:** Option 3 with v1 = self-hosted single-tenant. The requirement
"users obtain shared prompts from other users" works there within the
team/family circle; the entity model (uuid + revision + visibility) carries both
worlds. **Most important open info: Who is the target audience of sharing — your own
device fleet, a small team, or the public?**

### F8: Where does the hub code live — module in the monorepo or its own repo?

Context: ADR-0015 defines the monorepo topology with `:shared` as protocol SSoT;
the hub wants to use the same DTO/codec patterns.

- **Option 1 — fourth Gradle module `:hub` in the monorepo:** Pro: protocol sharing
  via `project(':shared')`, atomic protocol changes, one test run. Con:
  repo grows; deployment artifact (server JAR/container) in the app repo.
- **Option 2 — own repo with a published `shared` artifact:** Pro: separate
  release cycles. Con: versioning/publication overhead, drift danger —
  exactly what ADR-0015 wanted to avoid.

**Recommendation:** Option 1 (consistent with the ADR-0015 rationale). Sharpened by
the peer model (Block G): the headless hub peer is ideally not even its own module,
but a **deployment variant of the companion server**
(`--headless` startup without the Compose UI) — then the question is dropped almost
entirely; a separate `:hub` module would only be needed if the hub functionally diverges.

### F9: Pull-only or push for distribution?

Context: ADR-0017/0020 forbid back-channels in the local protocol; for the hub
this is to be decided anew.

- **Option 1 — Pull-only (catalogue sync on app start/settings open/manual):**
  Pro: consistent with existing doctrine, no connection management, offline-
  friendly. Con: changes arrive delayed.
- **Option 2 — Push (WebSocket/SSE from the hub):** Pro: immediate propagation. Con:
  standing connections on a mobile device (battery), complexity without a real need —
  prompts/profiles rarely change.

**Recommendation:** Option 1, with a `revision` cursor following the ADR-0020 model.

### F10: How do clients authenticate to the hub?

Context: The local pairing mechanism (one-time token → device secret, hash
server-side) is proven and implemented in `shared/auth/`.

- **Option 1 — Reuse the pairing model:** hub generates a one-time token
  (admin UI/CLI), the device exchanges it for a device secret. Pro: code + UX
  exist, no password/account system. Con: device-centric, no
  user concept (sufficient for single-tenant).
- **Option 2 — Accounts (email+password/OIDC):** needed only for multi-tenant.

**Recommendation:** Option 1 for v1; identity model only with the F7 Option 2 expansion.

---

## Block C — Security & Encryption

### F11: Do we now introduce project-wide encrypted secret storage (Android included)?

Context: API keys today sit in plaintext in SharedPreferences; ADR-0017 explicitly
deferred encrypted storage. The requirement "stored locally encrypted" for obtained
keys forces a secret store at least on the desktop.

- **Option 1 — only desktop encrypted, Android stays as before:** Pro: small
  scope. Con: inconsistent — the same shared key would sit on Android in plaintext,
  the requirement would only be formally met.
- **Option 2 — project-wide `SecretStore` port (Android Keystore / Windows DPAPI /
  Keychain / libsecret), migration of the existing plaintext keys:** Pro: cleanly
  resolves the ADR-0017 defer, one abstraction for both platforms. Con:
  migration effort + new ADR.

**Recommendation:** Option 2 — if the topic is being touched anyway, the half
solution is technical debt with advance notice.

### F12: May providing/relaying peers read the shared API keys (zero-knowledge yes/no)?

Context: "Keys transmitted and stored encrypted" leaves open whether a
peer that offers or relays keys (e.g. a headless hub peer) may itself
see plaintext. *(Reformulated via the peer model from Block G: "hub" =
arbitrary providing peer; the options apply per peer relationship.)*

- **Option 1 — Zero-knowledge via a share password:** the sharer encrypts the key
  client-side (Argon2id → AES-256-GCM); the providing peer stores only the
  blob (hashed over the blob → subscription sync without decryption); the recipient
  decrypts with an out-of-band communicated password. Pro: peer compromise
  discloses no keys; simple, no key directory. Con: password
  handover is a manual step.
- **Option 2 — Peer-side envelope encryption (the providing peer can
  decrypt):** Pro: frictionless obtaining without a password. Con: the peer
  becomes a high-value target; defensible on your own companion, not with foreign
  peers/multi-tenant.
- **Option 3 — Recipient public keys (X25519 sealed box):** convenient AND
  zero-knowledge, but needs a device/identity directory → expansion stage.

**Recommendation:** Option 1 for v1 (fulfills both encryption requirements
literally), Option 3 as a planned evolution. In profiles, key fields are modelled as
an `ApiCredential` reference, never inline — thereby "only sensitive
access credentials encrypted" is structurally guaranteed.

### F13: May secrets ever reach the browser (UI layer)?

Context: The browser UI displays provider configuration; to edit a key
it must be entered.

- **Option 1 — Write-only:** the browser sends new keys to the companion, never gets
  plaintext back (only a masked display `sk-…abc`). Pro: no secret in
  browser memory/DevTools persistence. Con: "show key" impossible.
- **Option 2 — Read on request with confirmation.**

**Recommendation:** Option 1 — congruent with the ADR-0016 redaction doctrine.

---

## Block D — Data Model & Sync

### F14: Sharing semantics: copy (fork) or live reference with updates?

Context: "Server prompts copyable", "presets duplicatable/movable" points to
copy semantics; teams, however, may want to obtain central updates.

- **Option 1 — Fork on import:** the obtained entity becomes a local copy with
  a `sourceRef` (origin marker). Pro: conflict-free, offline-robust, no
  merge logic. Con: updates to the original don't arrive.
- **Option 2 — Link + update channel:** the entity stays hub-bound, the client shows
  "update available". Con: conflict/override logic, offline cases.
- **Option 3 — Fork + update hint:** copy like 1, but `sourceRef`+`revision`
  allow an unobtrusive "newer version available → copy again"
  hint without a merge.

**Recommendation:** Option 3 — costs almost nothing extra and keeps both doors open.

### F15: Does the desktop get the full session schema (parity with Room) or its own leaner one?

Context: Android has `sessions`/`transcriptions`/`processing_steps`/
`conversation_messages` (versioned, double-enums); the companion only
`received_texts`. Desktop dictations need history including recording (requirement 4)
and review/regenerate needs conversation persistence (ADR-0012).

- **Option 1 — full schema parity in SQLDelight:** Pro: features (regenerate,
  review refinement, step chain) work identically; parity tests following the
  existing model. Con: greatest effort; two schema definitions (Room +
  SQLDelight) for the same model.
- **Option 2 — reduced desktop schema (session + current transcription +
  final output + conversation):** Pro: covers the desktop requirements; less
  migration maintenance. Con: feature divergence grows later.
- **Option 3 — common schema SSoT** (one generator for Room + SQLDelight):
  theoretically DRY, practically tooling terra incognita — not recommended.

**Recommendation:** Option 2, but with identical enum vocabularies and column names
(subset of the Room definitions + parity tests), so that later convergence stays
possible. Audio storage: segment files in the companion data dir with the same
cleanup-policy ideas (TTL inserted/cancelled).

### F16: Do desktop sessions synchronize anywhere (phone, hub)?

Context: Today phone sessions sync → companion archive (ADR-0020, one-way).
Desktop-own dictations produce sessions outside the phone for the first time.

- **Option 1 — local-only:** desktop history stays on the PC. Pro: no new
  authority question. Con: no cross-device history.
- **Option 2 — Desktop → hub (history backup/merge):** new privacy dimension
  (plaintext dictations on the server), conflict with phone authority from ADR-0020.
- **Option 3 — common history in the companion:** the phone already syncs there today;
  desktop sessions land in the same DB → the companion becomes the natural
  "family archive" of both sources (separated per `origin`/device column).

**Recommendation:** Option 3 (almost free, since phone sync exists) — the hub stays
strictly a configuration distributor, NEVER a dictation store (privacy boundary clear).

### F17: Which settings belong in a profile, which stay global?

Context: The profile combines "model + prompts + possibly further settings".
Too much in the profile makes switching surprising, too little makes it useless.

- Proposed profile content: transcription ModelRef (+ language/style prompt),
  completion ModelRef + parameter overrides, activated post-processing prompts
  (ordered, with autoApply), system-prompt selection, AmbiguityMode.
- Global stays: provider/credentials (referenced, not contained), UI/
  hotkey/audio settings, cleanup policies, proxy.

**Recommendation:** as proposed; the boundary "profile references credentials,
never contains them" is at the same time the encryption boundary (F12). To clarify: does
the profile concept from v1 also apply on Android (refactor of the settings), or desktop-first
with later Android adoption? → Recommendation: data model shared from v1, Android UI
rebuild as a separate expansion stage.

---

## Block E — UX & Scope

### F18: Does the desktop get the full review mode (review panel) including dictated refinement?

Context: ADR-0013/0027-F8: review is deliberately IME-only; the logic
(`ReviewDecision`, conversation continuation) is, however, platform-neutral. Requirement
5 demands "review mode with a corresponding UI".

- **Option 1 — full review mode:** panel in the mini window (message, output,
  insert/re-dictate/discard), re-dictate starts desktop recording S2. Pro:
  feature parity. Con: largest UI unit of the desktop client.
- **Option 2 — v1 only display+insert/discard/edit-by-hand, re-dictate later.**

**Recommendation:** Option 2 as a stage, Option 1 as the target; in any case a new ADR that
revises the surface constraint from 0013 for the desktop host.

### F19: How similar should the desktop widget look to the Android overlay?

Context: The drawing (canvas drawables) is not reusable, but the
design language and parameters are (waveform bars with age fade, HSV glow,
ripple pulse, pill shapes, accent color).

- **Option 1 — 1:1 rebuild in web canvas/CSS** (AmplitudeProcessor curves as spec).
- **Option 2 — own desktop design, only color/shape language adopted.**

**Recommendation:** Option 1 for the recording core (recognition, requirement 3),
Option 2 for management screens (there Material-like layout dominates anyway).

### F20: How does one select post-processing prompts on the desktop when there are no pills?

Context: Requirement 6 drops the pills; queued prompts are controlled on Android via
pills + autoApply.

- **Option 1 — only via the profile:** active prompts are a profile property;
  switching = profile change (dropdown in the mini panel). Pro: extremely lean
  panel. Con: ad-hoc enabling of individual prompts needs profile duplicates.
- **Option 2 — profile + ad-hoc toggle list in the panel** (checkboxes for this one
  recording). Pro: flexible. Con: more UI state.

**Recommendation:** Option 1 in v1 (a profile switcher is required anyway), Option 2 as
an extension if an ad-hoc need shows up.

### F21: Behavior after completing a desktop dictation — auto-insert or confirmation?

Context: On the phone the IME context decides; on the desktop the companion types via
Ctrl+V into the last-focused app. The mini panel may steal focus.

- **Option 1 — Auto-insert into the previously focused app** (remember focus on the
  hotkey, return it before insert). Pro: "dictate like typing". Con:
  focus restoration is error-prone (UIPI, closed windows).
- **Option 2 — Panel shows the result, insert by Enter/click** (+ option "insert
  immediately" as a setting).
- **Option 3 — Design the panel focus-free** (WS_EX_NOACTIVATE-style), then
  auto-insert is safe.

**Recommendation:** Aim for Option 3 (panel never takes focus, display runs parallel
to the target app), with Option 2 as a fallback setting. Interplay with the
review mode (F18): AmbiguityMode decides whether inserted or held —
identical semantics to ADR-0013.

---

## Block F — Migration & Approach

### F22: Is `APISettingsActivity`/the Android settings model refactored along with the profile model?

Context: Model/provider choice lives on Android as pref strings + a 783-line
activity; the new model (ProviderConfig/ModelRef/Profile) is entity-based.

- **Option 1 — yes, Android migrates to the entity model** (prefs → DB migration,
  activity rebuild). Pro: one model everywhere, sharing works from Android.
  Con: large rebuild of the stable existing code.
- **Option 2 — desktop-first; Android keeps reading prefs**, an adapter maps at
  hub sync. Con: two configuration worlds for a while.

**Recommendation:** Option 1, but as a separate, late expansion stage (after desktop v1) —
"provided for from the start" means: design the entity model and wire format from day 1 so
that Android can migrate losslessly (superset of today's prefs).

### F23: Does the file export (SAF JSON) remain as a sharing path?

Context: PromptImportExport v2 is the current sharing feature; the hub becomes the
new path.

- **Option 1 — both:** file export stays (offline, hub-less), format v3 = the same
  serialization as the hub wire format (SSoT in `:shared`).
- **Option 2 — deprecate the file export.**

**Recommendation:** Option 1 — the effort is minimal if hub and file format share
the same codec implementation, and it delivers the migration path (export
old → import new).

### F24: Name of the preset unit — "Profile"?

Context: konzept-skizze.md §5. Candidates: **Profile** (recommendation), Preset, Setup,
Workflow, Set, Mode.

- "Profile": familiar (browser/VS Code/OBS profiles), DE/EN-identical, carries
  "combination + switchable + shareable". Risk: collision with a later
  "user profile" with accounts.
- "Preset": collision-free, but sounds like pure parameter values.

**Recommendation:** "Profile" (`Profile` in code), fallback "Preset" if F7 goes toward
accounts/multi-tenant.

---

## Block G — Peer-to-Peer Sync & Distribution (Addendum)

### F25: Who may be a peer provider — only companions, or also Android/headless?

Context: ADR-0017 stipulates that the phone stays server-less; every companion
already brings a tailnet-addressable Ktor server. A "hub" would be
the same server code without a UI on a VM.

- **Option 1 — Companions + optional headless hub peer (same code):** Pro:
  one protocol, one server implementation, ADR-0017 stays untouched for the phone;
  Android shares by publishing to its own companion/hub.
  Con: sharing directly phone to phone does not work (needs a desktop peer
  in the network).
- **Option 2 — Android as a provider too:** supersede ADR-0017 (the phone gets
  a server), battery/reachability problems.

**Recommendation:** Option 1. "The server is just another peer" thereby becomes literal:
the headless hub is a deployment variant of the companion server.

### F26: How do peers find each other in the tailnet — manual address or discovery?

Context: Every participant needs an address; Tailscale provides stable
MagicDNS names (a familiar pattern in the existing code: `*.ts.net`).

- **Option 1 — manual entry of MagicDNS name+port + pairing token:** Pro:
  trivial, no new mechanism, works cross-tailnet (Tailscale
  sharing/funnel). Con: typing an address during setup.
- **Option 2 — Tailscale API/CLI enumeration (`tailscale status --json`):** list
  peers in your own tailnet automatically and ping them (`/v1/health` with
  a `supportsCatalog` flag). Pro: convenient. Con: ties to a local
  Tailscale installation + only your own tailnet.
- **Option 3 — QR/link like the existing phone pairing** (reuse the
  `dictate://pair` pattern to transport address+token).

**Recommendation:** Options 1 + 3 in v1 (the pairing-URI pattern exists including QR code);
Option 2 as a convenience expansion, behind a port (no hard Tailscale coupling —
Tailscale is "optimal" but per the requirement not mandatory).

### F27: Hash granularity and change detection — how is the comparison done?

Context: Requirement: per obtained datum a hash is stored and
compared; on deviation, re-fetch. Naive per-entity polling scales
poorly and makes "has anything changed?" expensive.

- **Option 1 — two-stage: catalogue root hash + per-entity content hash:** one
  `GET /v1/catalog` returns the root hash (hash over sorted entity hashes) + index;
  root equal → done (one request); otherwise diff the index, fetch only what changed.
  Pro: cheap polling, fulfills the hash-per-datum requirement exactly. Con:
  canonical serialization must be defined stably (field order!).
- **Option 2 — only per-entity hashes, individual requests:** simplest code, N
  requests per run.
- **Option 3 — full Merkle tree:** oversized for dozens of entities.

**Recommendation:** Option 1. The canonical serialization is needed anyway (it is
the v3 export format, SSoT in `:shared`); the hash over the *encrypted*
credential blob keeps secrets out of the comparison.

### F28: How does change notification work — who polls, how often, how is it displayed?

Context: "On changes a notification shall occur" — with pull-only there
is no push from the provider.

- **Option 1 — each device polls itself** (companion: timer, e.g. hourly +
  on UI open; Android: WorkManager periodicity + on app start) and shows locally
  a tray/system notification + badge in the Peer Explorer. Pro: no new channel,
  offline-robust. Con: double polling per household.
- **Option 2 — your own companion polls centrally** and the phone learns of it on
  the next contact: fails on the role direction (the companion cannot reach the phone,
  ADR-0017 no back-channel).
- **Option 3 — Push (WebSocket/SSE between peers):** contradicts the
  pull doctrine, standing connections for rare events.

**Recommendation:** Option 1. Notification is a result of the local sync run;
interval configurable, default conservative (prompts/profiles rarely change).

### F29: Conflict behavior — what happens with locally edited subscribed data?

Context: A subscription pulls the provider's changes; if the consumer has edited the copy
locally, both states collide. Merge logic for prompts/profiles is
disproportionate.

- **Option 1 — subscribed copies are read-only; "edit" = explicit
  decoupling as a fork** (sourceRef stays as origin): Pro: conflict structurally
  impossible, clear UX ("this prompt belongs to peer X; create your own version?").
  Con: no "local patch that keeps receiving updates".
- **Option 2 — editable, on peer update a dialog overwrite/keep:** drift
  detection via hash present, but recurring decision dialogs annoy.
- **Option 3 — three-way merge:** oversized.

**Recommendation:** Option 1 — directly covers "server prompts copyable" + "presets
duplicatable"; the Peer Explorer shows decoupled entries as
"locally decoupled (update available)".

### F30: Trust and identity model of the peers — what identifies a peer?

Context: Different *users* are to connect; today there is only
device pairing (token → secret hash) within a household.

- **Option 1 — Peer = address + pairing credential per relationship** (existing
  model generalized; display name reported by the peer itself): Pro: no
  account system, code exists. Con: identity is not cryptographically bound to
  a person (address move = new relationship); impersonation limited only
  by tailnet access + token possession.
- **Option 2 — Peer key pair (Ed25519), address is only transport:** stable
  cryptographic identity, basis for F12 stage 3 (sealed box) and signed
  catalogues. Con: key management/rotation.
- **Option 3 — central accounts:** only with multi-tenant opening (F7).

**Recommendation:** Option 1 for v1, but the data model gets a
`peerId` field that can later become a public-key fingerprint (Option 2 as a
planned evolution) — costs nothing now, prevents a later rename.

### F31: How much preparation for the long-term server path is right?

Context: In the long term, theoretically all API access should be able to run over a server
(keys only server-side, prompts/computations executable server-side) — but expressly
only architecturally prepare, no implementation, no excessive
complexity.

- **Option 1 — three seams (concept sketch §4):** (a) `AIOrchestrator`/
  runner interfaces as the single AI seam (arises through the `:shared-ai` extraction
  anyway), (b) reserved enum value `ProviderConfig.kind = GATEWAY`
  (documented, not implemented), (c) a protocol namespace that allows a later
  additive `/v1/ai/*` family. Pro: zero runtime complexity, switch
  later = new runner + new endpoint family. Con: server-side
  prompt execution ("prompt only by reference, text stays with the provider") is
  thereby not yet designed — deliberately.
- **Option 2 — additionally specify a gateway protocol now (without
  implementation):** Pro: wire design validated early. Con: speculation without
  a user — exactly the forbidden complexity.

**Recommendation:** Option 1; points (b)+(c) are recorded in the peer-catalogue ADR as
"Reserved for future use", nothing more.

### F32: One protocol for everything — the catalogue family on the existing wire stack?

Context: There already exist two payload families (Dispatch/Sync + Input) on
one stack (`:shared` DTOs, ProtocolCodec, Konform, additive endpoints per the
ADR-0025 pattern). The catalogue/subscription family could use the same stack or become a
separate protocol.

- **Option 1 — same family, same stack** (`/v1/catalog/*`,
  `supportsCatalog` health flag, Konform validation, ErrorEnvelope): Pro: one
  codec, one versioning doctrine, an E2E test pattern exists; "the server is only
  another peer" becomes true at the protocol level. Con: `:shared` grows.
- **Option 2 — separate protocol (e.g. a generic sync framework):** Pro:
  independence. Con: a second codec/versioning world, violates the
  ADR-0016 SSoT idea without need.

**Recommendation:** Clearly Option 1.

### F33: How does the system behave with offline/unreachable peers?

Context: Peers are end devices (desktop PCs), not high-availability servers —
a provider PC is off at night, a laptop leaves the tailnet. The sync run
must handle this as the normal case, not as an error.

- **Option 1 — tolerate silently with a staleness display:** subscriptions keep the last
  copy (fully functional — copy semantics pay off here); the Peer
  Explorer shows "last reached N days ago"; no error toast on every
  failed attempt, only a discreet status. Only after a configurable threshold
  (e.g. 30 days) a visible hint "peer permanently offline — cancel subscription?".
- **Option 2 — report an error per failed run:** honest, but annoying with
  a peer that is planned to be on only in the evening.
- **Option 3 — replication over intermediate peers** (a hub peer caches foreign
  catalogues): raises availability, but effectively turns the P2P model back into
  a server topology and raises redistribution/trust questions.

**Recommendation:** Option 1. Option 3 only as a deliberate later decision, if
offline providers show up as a real problem (then ADR-worthy anyway, because
redistribution of foreign content).

### F34: What does the Peer Explorer show — only provenance or also a supply/network view?

Context: The core requirement is "visible from whom which data was obtained"
(provenance). Obvious extensions compete for the same screen.

- **Option 1 — only the consumption view:** peers → obtained entities (type, mode
  subscription/one-shot, last comparison, state current/update/decoupled, actions).
  Minimal, fulfills the requirement literally.
- **Option 2 — consumption + supply view (recommendation):** additionally "what do I offer?"
  (own entities with `visibility: shared`, who last fetched them).
  Pro: without this view, sharing is flying blind; the fetcher display is trivially
  derivable from the server logs/device auth. Con: somewhat more UI.
- **Option 3 — additionally a network/catalogue browser** (browse foreign catalogues
  and subscribe directly): functionally the "prompt editor loads from the server" flow —
  belongs rather in the respective editor UIs (prompt/profile lists with a
  peer filter), the Peer Explorer only links there.

**Recommendation:** Option 2, with Option-3 functionality in the editor UIs instead of in the
Explorer (one responsibility per screen). Applies fully to desktop (browser UI);
Android gets a lean read-only variant in the settings.

---

## Top 10 (Prioritization for joint answering)

F1 (how hard is browser?) → F5 (keep-warm/window mechanics) → F4 (audio where?) →
F25+F7 (peer topology: who offers, is a headless hub peer needed,
target audience of sharing) → F29 (conflict model of subscribed data) →
F12 (zero-knowledge keys) → F11 (secret store project-wide) →
F15 (desktop schema scope) → F16 (history location) →
F17+F24 (profile content + name).

Secondary, but to be nailed down early because data-model-shaping: F27
(hash/catalogue mechanics), F30 (peerId future-proof), F31 (gateway reservation),
F22 (Android migration). Pure UX detail questions for later: F33 (offline peers),
F34 (Peer Explorer scope).
