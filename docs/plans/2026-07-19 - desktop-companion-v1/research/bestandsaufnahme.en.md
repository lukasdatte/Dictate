# Inventory — Desktop/Browser Concept (Research Phase)

As of: 2026-07-18, branch `main` (048fb37). Research across CLAUDE.md, all 27 ADRs,
`ai/`, `state/`, `core/`, `windows/`, `database/`, `history/`, `rewording/`,
`preferences/`, `widget/`, `audio/` as well as `shared/` + `companion/`.

## 1. The most important preliminary finding: The monorepo already exists

The repo is no longer a pure Android project but a **3-module monorepo**
(`settings.gradle`, ADR-0015):

| Module | Technology | Role |
|---|---|---|
| `:app` | Android (Java/Kotlin), minSdk 26 | IME, recording, AI pipeline, state machine, entire UI |
| `:shared` | **pure `kotlin("jvm")`**, jvmTarget 1.8, free of Android/Ktor/coroutines (enforced by `SharedPurityTest`) | Wire protocol: `@Serializable` DTOs + Konform validation, `ProtocolCodec` (single codec door), `DispatchClient`/`SyncClient`, pairing secrets |
| `:companion` | Compose Multiplatform Desktop (JVM 17), Ktor-CIO server, SQLDelight, JNA | Desktop receiver: tray app, pairing, history archive, Win32 text insertion, semantic input commands |

Central paths:
- `/home/lukas/WebStorm/Dictate/shared/src/main/kotlin/net/devemperor/dictate/shared/protocol/{Dtos,Endpoints,ProtocolCodec,Validations,ProtocolVersion}.kt`
- `/home/lukas/WebStorm/Dictate/companion/src/main/kotlin/net/devemperor/dictate/companion/{Main,CompanionBootstrap,CompanionContainer}.kt`
- `/home/lukas/WebStorm/Dictate/companion/.../server/CompanionServer.kt`, `.../domain/{DispatchService,InputCommandService,SyncService,AuthService,PairingService,HealthService}.kt`
- `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/windows/{WindowsDispatchCoordinator,PcInputCoordinator,PcInputCommandMapper,AndroidSyncSource}.kt`
- `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/PcDictationActivity.kt`

**Merge status:** `feature/companion-bind-address` and `feature/pc-dictation-view`
(branch `pc-dictation-audit-fixes`) are **fully merged into main** (both tips are
ancestors of main, `git cherry` empty). Nothing outstanding remains in these worktrees.

## 2. What the companion can do today — and what it CANNOT

**Can:** Ktor server (default port 8756, bind catalog Tailscale/LAN/Loopback per
ADR-0023, never `0.0.0.0`), pairing (one-time token 120 s, QR + manual, device secret
stored only as SHA-256 hash), text reception `POST /v1/dispatch` → clipboard +
`SendInput(Ctrl+V)` via JNA (`Win32TextInserter`, UIPI degradation → `CLIPBOARD_ONLY`),
semantic input commands `POST /v1/input` (TYPE_TEXT, CURSOR_*, UNDO/REDO …, never
VK codes; chord resolution companion-side in `key_command_chords`), lazy history sync
from the phone (`POST /v1/sync`, cursor-based, idempotent, ADR-0020), Compose UI with three
screens (history with "insert again", devices/pairing, settings with bind address/
chords/autostart), tray + SingleInstanceGuard + `--minimized` autostart.

**Cannot (gaps relative to the desktop requirements):**
- **No audio recording** — the companion is a pure text receiver; recording + AI
  run today exclusively on the phone.
- **No AI pipeline** — no runner, no orchestrator, no keys on the desktop.
- **No global hotkey** — the companion does not listen for the keyboard (grep empty).
- **No recording/overlay UI** — only a management interface.
- **No prompts/models/presets** — prompt management exists only in `:app`.
- The companion history is a flat `received_texts` archive, not the rich
  session model (`sessions`/`transcriptions`/`processing_steps`) of the app.

**PC dictation mode today (ADR-0027):** `PcDictationActivity` is a third
render host of the **Android app** — the phone records, transcribes, post-processes
and sends the finished text to the PC. The transient `FeatureToggles.pcOnly` flag
diverts every pipeline terminal through `WindowsDispatchCoordinator.dispatch`
(the one send primitive, ADR-0019); errors → visible retry instead of a pending part.
Editbar/gestures/text pills go to the PC as `/v1/input` commands. The desktop client
now desired is the **reversed** scenario: recording and pipeline on
the PC itself, without a phone.

## 3. AI layer: platform neutrality (detail)

The AI layer is surprisingly close to "extractable" — the SDKs
(`com.openai:openai-java` 4.26.0, `com.anthropic:anthropic-java` 2.16.0, okhttp) are
**pure JVM libraries**; okhttp is already a dependency in `:shared`.

**Immediately movable (0 Android deps):**
`AIProvider` (enum with capabilities: `supportsTranscription/Completion`,
`isOpenAICompatible`, `allowsStructuredOutputTextFallback`), `AIProviderException`,
`AIFunction`, `ModelInfo`, `ParameterDef`/`ParameterRegistry`, all
runner interfaces + DTOs (`TranscriptionRunner`, `CompletionRunner`,
`ConversationRequest/Result`), `ai/prompt/` (PromptContext, PromptMode,
PromptBuilder with XML escaping, PromptTemplates, PromptTypeClassifier), the entire
`ai/conversation/` package (StructuredResponseCodec = single wire authority for
`{message, output, needsClarification}`, ReviewDecision, ConversationReconstructor —
explicitly documented as "Android-free").

**Movable with a small abstraction:** `AIOrchestrator`, `RunnerFactory`, the three
runners (OpenAICompatible, Anthropic, ElevenLabs), `ModelFetcher`, `PromptService`/
`SystemPromptResolver`. Couplings are only: (a) `SharedPreferences` for
key/model/proxy resolution → config interface, (b) `UsageDao` (Room) in the orchestrator
→ `UsageSink` interface, (c) `org.json` in ElevenLabs/KeytermsParser/ImportExport
(not present on pure JVM) → kotlinx-serialization (already in `:shared`),
(d) `DictateUtils.getAudioDuration` (Android Media) → port.

**Stays Android:** `APISettingsActivity` (783 lines, the only
model/provider selection UI: 2× provider spinner + model spinner/free text,
parameter UI dynamically from `ParameterRegistry`), `PromptsOverviewActivity` (SAF I/O),
pipeline orchestration in the IME service, SharedPreferences backend.

**API keys today: plaintext** in default SharedPreferences
(`Pref.*ApiKey*`, `DictatePrefs.kt:119-140`); encrypted storage was
explicitly deferred project-wide in ADR-0017. Model lists: OpenAI/Groq/OpenRouter
fetched live (`ModelFetcher`, no cache), ElevenLabs hardcoded, Anthropic/Custom
free text.

## 4. Prompts + share feature (existing)

`PromptEntity` (`prompts`): `id, pos, name, prompt, requiresSelection, autoApply,
type` (Double-Enum `PromptType {PROMPT, TEXT}`, ADR-0024). The existing
"share feature" (`rewording/PromptImportExport.java`) is a **JSON file export
via SAF** — no link, no server:
`{"version": 2, "prompts": [{name, prompt, requiresSelection, autoApply, type}]}`
with v1 backward compatibility. The serialization logic is already activity-free and
unit-tested, but hangs on `org.json` + Room `PromptEntity`. → Good basis for a
server format, but needs a platform-neutral prompt DTO.

## 5. State machine + render (existing)

`state/` follows ADR-0001: one orchestrator, ~19 modules with pure reducers +
lens axes, IO only in `runEffect`. **The reducer core is conceptually
platform-neutral** (pure data transformation, no Android types in the reduction),
but lives in `:app`. **`state/render/` is fully Android-bound**
(MotionLayout, View, MaterialButton), cleanly separated via the
`RenderBackend` abstraction — it is exactly this multi-backend seam (ADR-0004/0008/0027)
that today makes three parallel render hosts possible (IME view, overlay widget,
PcDictationActivity) and is the model for "browser as a further render host".

## 6. Widget / overlay design (existing)

`widget/`: design language = accent-color-driven, amplitude-reactive animation
(waveform bars with age fade, button glow via HSV brightness boost,
ripple pulse), pill shapes. **Logic and drawing are cleanly separated:**
- Portable (no Android): `core/AmplitudeProcessor` (log norm + EMA),
  `EditBarWidthCalculator` (peek arithmetic, pure `object`), effect parameters,
  `RecordingAnimation` strategy interface (almost Android-free).
- To be redrawn: all `Drawable`/`View` classes (`AmplitudeVisualizerDrawable`,
  `BorderGlowDrawable`, `PulseLayout`) — pure canvas renderers, well reproducible in
  web canvas/CSS (the parameters/curves are the reusable part).

## 7. History/database (existing)

Room v11, 8 tables. Session model: `sessions` (status/origin/type as
Double-Enums, denormalized caches `final_output_text`/`input_text`,
`inserted_at`, multi-file audio paths) → `transcriptions` (versioned, raw) →
`processing_steps` (versioned chain: AUTO_FORMAT/REWORDING/QUEUED_PROMPT/
CONVERSATION_TURN) → `conversation_messages` (ADR-0012) + `text_insertions` audit +
`usage` aggregate + `completion_log`. Audio: M4A/AAC 44.1 kHz mono 64 kbps,
rolling segments "always-one-ahead" (ADR-0007), MediaMuxer concat — the recording stack
is strongly Android-bound (MediaRecorder/MediaExtractor/MediaMuxer).
The history panel filter (ADR-0014) is definitionally identical to the sync filter (ADR-0020):
`COMPLETED AND final_output_text IS NOT NULL AND origin != REVIEW_REFINEMENT`.

## 8. Review mode (existing, ADR-0013)

Tri-state `AmbiguityMode` (ALWAYS_INSERT/AUTO/ALWAYS_REVIEW); the verdict is the
explicit wire field `needsClarification` + pure rule `ReviewDecision.decide` →
in-keyboard review panel (Insert / Re-dictate = transcription-only
refinement session with `ConversationContinuation` / Discard). **The review panel is
deliberately IME-only** — ADR-0027 F8 decided that another render host does NOT
rebuild review. The decision logic (`ReviewDecision`, `StructuredResponseCodec`)
is platform-neutral; only the panel UI is Android.

## 9. Binding ADR constraints for the undertaking

1. **ADR-0017:** "Desktop companion is the only server, phone a pure client;
   HTTP response = the only delivery confirmation, no back channel." A central
   distribution server is a new peer → needs a new ADR (extension/supersede).
2. **ADR-0020:** "Phone authoritative, PC a derived archive, no bidirectional
   sync." Server-authoritative prompt/key distribution inverts this → new ADR.
3. **ADR-0015/0016:** Wire SSoT = `shared/` DTO + Konform + `ProtocolCodec` as
   the only door; `shared/` is pure-JVM **without a JS target** (KMP deliberately rejected) —
   a browser client cannot use the protocol via compile sharing.
   Kotlin ceiling ≤ 2.1.20 compiler-wide.
4. **ADR-0016/0025:** New capabilities preferably as an additive endpoint +
   `HealthResponse` capability flag, no version bump for additive fields.
5. **ADR-0011/0019:** One dispatch primitive, one terminal guard (exactly-once),
   one acknowledge channel; ADR-0009: strictly serial pipeline queue.
6. **ADR-0013/0027:** Review IME-only; `StructuredResponseCodec` the only authority
   for structured output; text fallback only CUSTOM/OpenRouter/Groq.
7. **There is NO ADR** on server-side distribution of
   prompts/keys/models/presets — an entirely new decision field.

## 10. Tailscale/network inventory (relevant for the peer-sync undertaking)

Tailscale is already the load-bearing network assumption of the system: ADR-0017 chooses
Tailscale (MagicDNS + WireGuard) as the reachability layer with an app credential as
defense-in-depth; ADR-0023 explicitly categorizes bind addresses by
`kind: TAILSCALE|LAN|LOOPBACK|OTHER` with Tailscale-only as the first-configuration
default; the pairing URI transports the (base64url-encoded) base URL incl.
https for Tailscale Serve. Thus every companion already brings today an
addressable Ktor server in the tailnet — the technical foundation for a
peer model ("every companion is reachable") exists; what is missing are
catalog endpoints, subscription/hash sync and a peer concept in the data model. The phone
deliberately stays server-less per ADR-0017 (a pure client) — as peer **providers**
this leaves, without a new ADR, only desktop companions (and an optional headless
service) eligible, with Android being a pure consumer.

## 11. Core gap list (requirement → existing)

| Requirement | Existing | Gap |
|---|---|---|
| Hotkey → warm small UI | Companion is a warm tray process, but without hotkey/recording UI | Global-hotkey hook + recording window/browser UI |
| Display/pause/delete recording | Only on the phone (RecordingManager, state machine) | Desktop recording stack + UI entirely new (logic shareable) |
| Reuse widget | Logic (AmplitudeProcessor, parameters) portable; drawing Android canvas | Redraw web/Compose following the same design language |
| History infrastructure | Rich model in `:app` (Room); flat archive in the companion (SQLDelight) | Session model on the desktop side; clarify schema parity |
| Model switcher | `APISettingsActivity` (Android UI); data model portable | Normalize provider→model data model + new UI |
| Post-processing prompts | `PromptService`/conversation portable; management Android | Prompt store + UI on the desktop |
| Review mode + UI | Logic portable, panel IME-only (ADR-0027 F8) | Desktop review UI = new ADR decision |
| Server distribution (prompts/keys/models/presets) | Only local JSON export (v2) | Complete rebuild: server, entities, crypto, new ADRs |
| Peer connections over Tailscale (addendum) | Tailscale reachability + pairing + Ktor server per companion present (ADR-0017/0023) | Catalog protocol, peer registry, subscription/hash sync |
| Subscription sync with hash comparison + notification (addendum) | Cursor-sync pattern exists (ADR-0020, other direction) | Content hashes, subscription model, change notification |
| Peer Explorer UI (addendum) | Devices screen in the companion (only paired phones) | Origin tracking per entity + dedicated UI |
| Long-term server path for AI access (addendum) | `AIOrchestrator` is already the only AI seam | Reserve gateway seam (architecture only, no implementation) |
