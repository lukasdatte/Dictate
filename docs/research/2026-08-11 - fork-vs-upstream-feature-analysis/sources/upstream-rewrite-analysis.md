# Upstream Rewrite Analysis — Dictate Keyboard 5.3 vs. our v3.2-based fork

**Analysed:** `upstream/main` @ `3e5ebe46` (tag `v5.3.0`, "Bring the README up to 5.3"), 453 commits, root
commit `266a1c0e` "Import FlorisBoard base as Dictate Keyboard foundation".
**Compared against:** local `main` (fork of Dictate v3.2, ~585 own commits, **no shared history**).
**Method:** read-only worktree of `upstream/main`, full commit-log sweep, targeted source reads.

> [!IMPORTANT]
> **The repo has moved.** Our `upstream` remote points at `DevEmperor/Dictate`, but the 5.3 README
> links issues, releases and the prompt library at **`DevEmperor/DictateKeyboard`**
> (`README.md:12-19`, `PromptLibraryCatalog.kt:88-111`). `DevEmperor/Dictate` still receives the
> commits, but the community-facing repo is the new one. The old Java v1–v3 codebase — the ancestor
> of our fork — is preserved on the **`legacy-java`** branch (`README.md:44-48`).

---

## Part A — What Dictate Keyboard 5.x is

### A.1 The rewrite in one paragraph

Dictate 5.x is **not the old app with a keyboard bolted on — it is FlorisBoard with a dictation layer
grafted into it.** DevEmperor imported the complete FlorisBoard source (Kotlin + Jetpack Compose,
package root still `dev.patrickgold.florisboard`, Gradle `rootProject.name = "FlorisBoard"`) and then
built the Dictate feature set as an additive layer in a `dictate/` sub-package plus a shared
`:lib:dictate-core` module. Everything our fork's v3.2 base *was* — a mic-only IME with a small
custom UI — is now one optional *display mode* inside a full-blown keyboard. The old Java codebase was
retired wholesale, not migrated.

### A.2 Module structure

```
:app                    79.3k LOC Kotlin — FlorisBoard + dictate/ (23.3k LOC of Dictate-specific code)
:wear                   Wear OS keyboard IME (own package net.devemperor.dictate.wear)
:lib:dictate-core       provider layer + prompt defaults + wear protocol (shared app↔wear)
:lib:android :lib:color :lib:compose :lib:kotlin :lib:snygg     inherited FlorisBoard libs
:benchmark              (commented out in settings.gradle.kts)
```

Dictate-specific packages under `app/.../florisboard/dictate/`: `audio/` (11 files), `data/`
(`history/`, `mappings/`, `prefs/`, `prompts/`, `stats/`), `gif/`, `overlay/`, `provider/`,
`recognition/`, `ui/`, `wear/`, plus ~20 top-level enums/controllers of which
`DictateController.kt` (~3200 lines) is the orchestrator.

### A.3 Feature map — how radically it differs from the 3.2 base

| Dimension | Dictate 3.2 (our fork's base) | Dictate Keyboard 5.3 |
|---|---|---|
| Language | Java | Kotlin + Jetpack Compose |
| Keyboard | none (mic overlay IME) | 77 character layouts, glide typing, autocorrect, next-word prediction, emoji, clipboard, themes |
| Providers | OpenAI (+ a few) | 16 presets + unlimited custom endpoints + on-device |
| Transcription modes | one-shot upload | one-shot, **realtime streaming** (5 providers), **on-device** (sherpa-onnx, streaming + one-shot), single-call multimodal, long-form segmented |
| Surfaces | IME only | IME + **system-wide floating bubble** + **Wear OS watch** + **system voice-input service** (other keyboards' mic key routes through Dictate) + file-transcription activity |
| Settings | PreferenceFragment | Compose settings hub with a **full-text search index** |
| Cost tracking | usage DB + pricing | **deliberately removed** (see area 14) |

### A.4 Version evolution 5.0 → 5.3

- **4.x line (pre-history for context):** 4.0 rebrand + l10n into 20 languages; 4.1 Soniox, interrupted-recording recovery; 4.2 **floating dictation button** + Gemini; 4.3 **Wear OS keyboard** (`#106`, which is what forced the `:lib:dictate-core` extraction, commit `dd8320fa`), on-device offline STT via sherpa-onnx (`#104`), find-and-replace mappings, single-call multimodal.
- **5.0** (`42d1cea7`): glide typing + word suggestions + spell check + autocorrect (`#127`), **realtime streaming transcription** (`#128`, ~18 commits), community prompt library (`#105`), local Silero VAD gate, Anthropic as a rewording provider, autocorrect Tier 1/2 (keyboard-proximity noisy channel + per-language bigrams), dictation statistics (`#142`), ElevenLabs/Deepgram/AssemblyAI (`#143`).
- **5.1** (`668ed89f`): **transcription history / activity log** (`#140`), **long-form segmented dictation** (`#170`), settings search (`#187`), **classic keyboard-free "legacy" dictation layout** (`#125`) — i.e. the Dictate-3 screen brought back as an option, reasoning-effort settings, per-prompt reasoning override (`#155`), multilingual typing.
- **5.2** (`de56ef30`): **system-wide voice input service** (`#67`) — Dictate registers as a `RecognitionService` + voice IME so *other* keyboards' mic keys transcribe through it, no a11y permission needed; Smart Turn v3 semantic auto-segmentation (`#191`); silence trimming before upload (`#232`); paragraph splitting (`#225`); freeform voice command from the bubble (`#230`); GIF search (KLIPY); on-device long-press send (`#228`).
- **5.3** (`3e5ebe46`): **push-to-talk** (hold-to-record, slide-left to discard, drag-up to lock — `#235`, ~20 commits), **autocorrect rebuilt as a touch-coordinate beam-search decoder** (88% → 98% on their test set, `#242`/`#244`), **on-device *live* transcription** with streaming Kroko models in 10 languages (`#233`), Canary + GigaAM models (`#255`), +45 languages in the catalog (`#252`), Aurora + Lattice bubble designs (`#253`), next-word prediction + long-press-to-learn (`#241`/`#245`), self-hosted realtime + sleeping-server wake-up (`#249`/`#189`).

The in-app "What's new" tour is a real, first-class artifact (`app/WhatsNewTour.kt`, 9 pages for 5.3,
with a version picker collapsing all tours into one entry) and is the best single summary of each
release's priorities.

---

## Part B — Per-area verdicts

| # | Our feature area | Upstream status |
|---|---|---|
| 1 | Full QWERTZ keyboard in the IME | **obsolete-by-design** — FlorisBoard base, 77 layouts |
| 2 | Desktop companion / PC dictation | **absent** (decisive; nothing comparable exists) |
| 3 | History system | **partially exists** (no pagination, no per-step history, no multi-segment) |
| 4 | Prompt queue / reprocess queue editor | **partially exists** (queue yes, editor UI no) |
| 5 | Overlay / floating widget mode | **partially exists — different architecture** (system-wide bubble; no opacity pref) |
| 6 | AI provider abstraction | **adopted / far exceeded** (16 providers; Anthropic via OpenAI-compat, not native) |
| 7 | Rewording prompts management UI | **partially exists** (DnD + import/export yes; duplicate + card design no) |
| 8 | Typed pills PROMPT/TEXT | **partially exists** (same `[...]` behaviour, no persisted type) |
| 9 | Language chip curation | **adopted / equivalent** |
| 10 | Recording robustness (BT SCO, audio focus, concurrency) | **partially exists** (BT SCO ≥ ours; audio focus weaker; concurrency absent) |
| 11 | Latency optimizations (cold start, pre-bind) | **absent** for IME cold start; pipeline latency work is elsewhere |
| 12 | Accessibility screen context | **absent — by explicit design decision** |
| 13 | Session persistence | **partially exists** (survives service restart, not process death) |
| 14 | Usage / cost tracking per API call | **obsolete-by-design — deliberately deleted** |
| 15 | Auto-formatting / prompt contexts | **adopted / exceeded** (5 contexts vs. our 3) |

All paths below are relative to the upstream worktree root.

---

### 1. Full QWERTZ keyboard in the IME — **obsolete-by-design**

The entire premise of our fork's keyboard work is dissolved by the rewrite. Upstream inherits
FlorisBoard's complete typing stack:

- **77 character layouts** in `app/src/main/assets/ime/keyboard/org.florisboard.layouts/layouts/characters/`, including `qwertz.json`, `german.json`, `german2.json`, `dvorak_de.json`.
- Glide typing with per-language downloadable dictionaries (`#127`, commits `47eceb3b`…`aec8d614`), word suggestions, spell check, and an autocorrect rebuilt in 5.3 as a **touch-coordinate beam-search decoder** (`d500ccbf`, `#242`) plus German noun capitalisation (`9cdb7a8b`) and umlaut/ß restoration (`d92c3b6c`).
- Next-word prediction + long-press-to-learn (`eb1ac0d5`, `#241`/`#245`), emoji keyboard with search, clipboard manager, one-handed mode, themes.

Notably, upstream also implements the **inverse** of our work: `DictateLegacyLayout` (`dictate/DictateLegacyLayout.kt:26-33`) with modes `OFF | LOCKED | SWIPE` brings back "the compact record-first UI from Dictate 3.x that several dictation-only users asked to have back" — `LOCKED` never shows the typing keyboard, `SWIPE` makes it a horizontal swipe away. It was later enriched with a drag-and-drop-configurable action row, long-form controls and a 2-row prompt strip (`5c9013d2`, `#183`/`#194`).

**Implication:** our QWERTZ layout has no migration path and no value upstream — the capability is a superset there.

---

### 2. Desktop companion / PC dictation — **absent**

This is the clearest and most consequential gap. Searched the entire tree for `ServerSocket`,
`NsdManager`, `mDNS`, `DatagramSocket`, `MulticastSocket`, `wake.?on.?lan`, `magic.?packet`,
`tailscale`, `pairing`, `QRCode`/`zxing`: **zero hits.** `"companion"` appears only as Kotlin
`companion object` and as "Wear OS companion". Dictate is always a WebSocket *client*, never a server.
The manifest requests only `INTERNET` — no `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` or
`NEARBY_WIFI_DEVICES`, which alone rules out LAN discovery or WoL broadcast.

Two things that superficially look adjacent, and are not:

1. **"Let a sleeping server wake up before it is asked to reword"** (`5f9ca9d5`, `#189`) is **not** Wake-on-LAN. It is `DictateController.warmUpRewordingServer()` (`DictateController.kt:2884-2919`) firing a throwaway HTTP `GET /models` at the user's own OpenAI-compatible *inference* endpoint, throttled to once a minute, so a GPU box that wakes on network traffic gets the dictation's duration as a head start. Flag: `ProviderAccount.customWarmUp` (`provider/ProviderAccount.kt:74-82`).
2. **The Wear transport is not repurposable.** Phone↔watch runs on the Google Play Services Wearable Data Layer (`ChannelClient`/`MessageClient`/`DataClient`, `dictate/wear/DictateWearService.kt:15-17`, `wear/.../sync/WearSyncClient.kt:14-16`), which is bound to a Play-Services-paired Wear node. The direction is also wrong for a PC companion: the **watch records and the phone transcribes**, returning text to the watch (`dictate/wear/PhoneTranscriber.kt`). Repurposing it means replacing the entire transport, not re-pointing an endpoint.

For contrast, our fork carries a `:companion` Compose Desktop module and a `:shared` JVM module
(`settings.gradle`, referencing ADR-0015/ADR-0017) plus a 10-file `windows/` package
(`PcInputCoordinator`, `WindowsDispatchService`, `PcInputSink`, …). **Nothing upstream corresponds to
any of it.** This is our largest unique surface and the one with zero upstream overlap.

---

### 3. History system — **partially exists**

Upstream shipped history in 5.1 (`36851710`, `#140`) and it is genuinely good, but shallower than ours.

**Present:**
- **Room**, not raw SQLite: `@Database(entities = [DictateHistoryEntry::class], version = 3)` (`dictate/data/history/DictateHistory.kt:143`), exported schema, hand-written `MIGRATION_2_3`.
- Per-entry columns (`DictateHistory.kt:49-90`): `text`, `originalText`, `createdAt`, `providerId`, `providerName`, `model`, `language`, `durationSecs`, `audioPath`, `audioBytes`, `source` (`keyboard`/`overlay`/`realtime`/`import`), `reworded`, `pinned`, `failed`.
- **Audio retention** with three independent caps enforced in `prune()` (`:306-342`): max entries, max age in days, and an audio **byte budget** that drops audio while keeping the text. Pinned entries are exempt from all three.
- **Detail view** (settings only): `DetailDialog` (`app/settings/dictate/DictateHistoryScreen.kt:445-591`) with `MediaPlayer` playback + progress ring, audio export to Downloads, share, pin.
- **Re-transcribe: yes** — `DictateController.retranscribeHistoryEntry` (`DictateController.kt:2540-2559`) re-runs the stored WAV through the whole chain, updating in place. Exposed only from the in-keyboard panel (`ui/DictateHistoryLayout.kt:180-183`).
- **Per-step copy: two levels only** — raw vs. final. `originalForHistory` is set only when the prompt chain actually changed the text (`DictateController.kt:1467`); the settings dialog renders two labelled sections each with its own copy button (`DictateHistoryScreen.kt:509-522`). Commit `7047202e`, `#240`.
- Pin, search (settings-only in-memory `contains` filter), backup/restore, failed-entry logging, sensitive-field gate.

**Absent vs. ours:**
- **No pagination whatsoever.** No `androidx.paging` anywhere, no `LIMIT`/`OFFSET`. The DAO is `SELECT * FROM dictate_history ORDER BY pinned DESC, createdAt DESC` returning a `Flow<List<…>>` (`DictateHistory.kt:95-96`); both UIs consume the full table and rely on `LazyColumn` windowing. There is an explicit comment at `DictateHistoryLayout.kt:156-158` that eager composition of "several hundred entries" blocked the UI thread for over a second — i.e. they hit the wall our Paging3 work solves and patched around it.
- **No per-prompt-step history.** A 3-prompt auto-apply chain stores only first-in and last-out. Our `PipelineStepAdapter` / `StepExpansionState` model has no counterpart.
- **No multi-segment audio storage.** Long-form segments are held in `segmentAudioFiles: HashMap<Int, File>` and merged by `AudioConcat.concat` into one `dictate_seg_merged.wav` before the single history write (`DictateController.kt:1942-1965`). No segment table, no segment column.
- **No prompt choice on rerun** — re-transcribe replays the whole chain; you cannot re-run a *different* prompt against a stored transcript. Our `PromptChooserBottomSheet` + reprocess flow has no equivalent.
- No search in the keyboard panel, no detail view in the keyboard panel.

---

### 4. Prompt queue / reprocess queue editor — **partially exists**

**The queue exists; the editor does not.**

`_pendingPrompts: MutableStateFlow<List<PromptModel>>` (`DictateController.kt:240-246`).
`togglePendingPrompt()` (`:630-646`) adds/removes and is a no-op unless the state is `Recording` or
`Transcribing`. `applyPendingPrompts()` (`:2856-2880`) runs them **in tap order** on the finished
transcript, best-effort per step, appending `[snippet]` prompts literally, then clears the queue.
Origin: commit `cf8679df` "queue prompts while recording".

Limits, all verified:
- The only affordance is **tapping chips in the ROW layout** while capturing (`ui/DictatePromptStrip.kt:141-151`); queued chips get an accent fill. There is **no list of slots, no reordering, no removal other than re-tapping, and no position badge**.
- The **PANEL layout does not queue at all** — it calls `applyPrompt` unconditionally (`ui/DictateInputLayout.kt:145-153`).
- A **live prompt discards the queue** outright: `_pendingPrompts.value = emptyList()` with the comment "a live prompt ignores any queued prompts" (`DictateController.kt:1446`).
- The queue is **in-memory only** and lost on process death.
- Grepping `queue` across `dictate/` and `app/settings/dictate/` returns only `DictatePromptStrip.kt` and `DictateController.kt` — no settings screen, dialog or panel.

Our `ReprocessQueueEditorBottomSheet.kt` + `ReprocessQueueEditorModel.kt` (staged, arrangeable slots)
have no upstream counterpart.

---

### 5. Overlay / floating widget mode — **partially exists, different architecture**

Upstream's "floating dictation button" is a **more ambitious feature than ours in scope, but narrower
in the specific knobs we built.**

**Architecturally different:** it is a `TYPE_ACCESSIBILITY_OVERLAY` window hosted by the accessibility
service — deliberately *not* `SYSTEM_ALERT_WINDOW`, so **no draw-over-apps permission is needed**
(`dictate/overlay/DictateBubbleController.kt:72-78`, params at `:648-665`; the manifest carries no
`SYSTEM_ALERT_WINDOW`). It therefore floats over **any app, even while a different keyboard is
active** — ours is a card inside our own IME. Background mic is legalised by promoting the a11y
service to `FOREGROUND_SERVICE_TYPE_MICROPHONE` (`DictateAccessibilityService.kt:516-527`).

Text injection is a three-tier ladder (`DictateAccessibilityService.kt:222-264`): a11y
`InputConnection.commitText` (API 33+) → node `ACTION_SET_TEXT` → clipboard paste with the user's
previous clip restored after 400 ms.

**Customization present:** 6 designs (`RING, PILL, ORB, CLOUD, AURORA, LATTICE` —
`DictateFloatingButtonDesign.kt:25-35`), 3 sizes (`SMALL/MEDIUM/LARGE` scale multipliers), full colour
picker, edge snapping, drag with per-app remembered positions, auto-dim, haptics, undo button,
optional clipboard copy, long-press menu offering **"Live Prompt" freeform voice command** plus every
saved prompt (`DictateBubbleController.kt:442-522`).

**Absent vs. ours:**
- **No opacity / transparency preference.** Grepped `opacity|transparen|alpha` across `dictate/`, the settings package and `strings.xml`: the colour picker **explicitly disables the alpha slider** (`app/settings/dictate/DictateFloatingButtonScreen.kt:239`, `showAlphaSlider = false`). The only fade is a hard-coded idle auto-dim (`.alpha(0.45f)`, `DictateBubbleController.kt:876`), on/off only. Our `Pref.WidgetOpacity` (20..100%, `DictatePrefs.kt:97`) has no counterpart.
- **No third row** concept.
- **No user-toggled collapse.** The PILL skin auto-expands while recording (`setExpanded()`, `:1491-1500`) and auto-dim shrinks to a 50%-scale dot, but there is no persistent collapsed-handle state and no collapse gesture.

---

### 6. AI provider abstraction — **adopted / far exceeded**

Upstream's provider layer is a strict superset of ours in breadth, with one architectural regression
relative to our design.

**16 presets** in `lib/dictate-core/.../provider/ProviderRegistry.kt` plus an unlimited custom
factory (`ProviderRegistry.custom(...)`, `:400`, ids `custom:<uuid8>`):

| Chat + STT | STT only | Chat only |
|---|---|---|
| OpenAI (+realtime), Groq, OpenRouter, Gemini (native `generateContent`), Mistral | Soniox (+RT), ElevenLabs (+RT), Deepgram (+RT), AssemblyAI (+RT), on-device `local` (+RT) | Anthropic, Together, DeepInfra, xAI, DeepSeek, Ollama |

**Shape:** one 1296-line `OpenAiCompatibleClient` implementing both `LlmProvider` and
`TranscriptionProvider`, with wire-format variation as an **enum switch** — `TranscriptionApi` (8
values) dispatched in `transcribeByApi` (`OpenAiCompatibleClient.kt:150`), and `RealtimeApi` (7
values) driving one 887-line `RealtimeClient`. Providers are plain data classes in a plain `object`
registry; adding one is adding a `val`.

**Anthropic is *not* natively implemented.** It is reached through Anthropic's *OpenAI-compatible*
endpoint (`ProviderRegistry.kt:185-206`), and the class doc concedes the gap: "Providers with a
genuinely different chat API (e.g. Anthropic native) would still need their own `LlmProvider`
implementation; until then they are reachable via OpenRouter" (`OpenAiCompatibleClient.kt:56-58`). The
only Anthropic-specific code is a URL-prefix branch adding `x-api-key`/`anthropic-version` headers for
model listing (`:688-692`). **Our `AnthropicCompletionRunner` (native SDK) is genuinely more capable
here**, and our `RunnerFactory` + per-provider runner interfaces are the more extensible shape than
upstream's enum switch.

Everything else is upstream-superset: a real **per-provider keyring** (`ProviderAccounts` as one JSON
JetPref, `ProviderAccount.kt:110-147` — note: **plaintext**, no Keystore/EncryptedSharedPreferences
anywhere), **global proxy** (HTTP/SOCKS5, `ProviderConfig.kt:160`, applied to every call), user-CA
trust, cleartext HTTP for LAN endpoints (`AndroidManifest.xml:52`, `#136`), reasoning effort
(global + per-prompt + custom string, with automatic retry-without-the-field when a model rejects it,
`OpenAiCompatibleClient.kt:75-107`), and single-call multimodal transcribe-and-format
(`ProviderConfig.useChatAudio`).

Plus an entire capability we do not have: **on-device transcription** via sherpa-onnx —
Whisper tiny/base/small (± `.en`), Parakeet TDT 0.6B v3, Parakeet German (primeline), Canary 180M
Flash, GigaAM Russian, and 10 streaming Kroko models; atomic downloads with SHA-256 verification under
a foreground service (`provider/LocalModelManager.kt`, `provider/ModelDownloadService.kt`);
single-slot recognizer cache with a user-configurable idle-unload timer and `onTrimMemory` hookup
(`provider/LocalTranscriptionProvider.kt:320-410`, `FlorisApplication.kt:106-111`); and an offline
fallback when a cloud call fails for connectivity reasons.

---

### 7. Rewording prompts management UI — **partially exists**

`app/settings/dictate/DictatePromptsScreen.kt`:

- **Drag-and-drop reordering: yes**, hand-rolled — `detectDragGesturesAfterLongPress` on the row (`:271-303`), swap on half-item threshold, `zIndex` + `translationY` lift, persisted as `POS = index` writing only changed rows (`:146-153`).
- **Import/export JSON: yes** — overflow menu (`:184-201`), `{"version":1,"prompts":[…]}` with `name`/`prompt`/`requiresSelection`/`autoApply` (+ optional reasoning fields), deliberately legacy-byte-compatible (`:102-109`); import accepts both the wrapped form and a bare array and then asks **Replace vs. Add** (`:543-562`).
- **Duplicate action: no.** Grepped `duplicate` across the dictate + settings trees — only unrelated hits. The editor dialog offers Save / Cancel / Delete only (`:582-598`).
- **Cards: no** — a `LazyColumn` of `JetPrefListItem` rows with two status icons and a drag handle (`:254-335`). Our cards-overview redesign has no counterpart.
- No search field on the prompts screen.
- **Per-prompt reasoning-effort override: yes** (`PromptModel.reasoningEffort` + `reasoningEffortCustom`, `#155`) — we have nothing equivalent.
- **Community prompt library** (`#105`) — upstream-only. Install pulls a static `library.json` from an orphan branch of the project repo with stale-while-revalidate caching and an APK-bundled fallback (`data/prompts/PromptLibraryManager.kt`). Publishing has **no backend**: it builds a pre-filled GitHub "create new file" deep link so GitHub auto-forks and opens a PR (`PromptLibraryContribution.buildSubmissionUrl`, `:198-215`).

---

### 8. Typed pills PROMPT / TEXT — **partially exists (behaviour yes, modelling no)**

Upstream has the *same user-facing behaviour* via the *same bracket convention* — but no type in the
data model.

`PromptModel` (`data/prompts/PromptModel.kt:31-42`) has `id, pos, name, prompt, requiresSelection,
autoApply, reasoningEffort, reasoningEffortCustom` — **no type field**. The SQL table has no TYPE
column and the schema is explicitly declared **frozen** for legacy compatibility
(`PromptsDatabaseHelper.kt:23-30, 44-47`).

A snippet is recognised by **string syntax at call time**, re-implemented at three separate sites:

```kotlin
// DictateController.kt:2726-2730
if (raw.length >= 2 && raw.startsWith("[") && raw.endsWith("]")) {
    sink.commitText(raw.substring(1, raw.length - 1)); return
}
```
plus `DictateController.kt:2865-2869` (queued path) and `ui/DictatePromptStrip.kt:304-312` (icon
choice: `ShortText` / `SelectAll` / `AutoAwesome`).

Our fork uses the identical `[...]` convention in `PromptTypeClassifier.kt:38` but **persists the
result as a `PromptType` enum column** under the project's Double-Enum pattern
(`database/entity/PromptType`, seeded in `DictateDatabase.kt:151`). Behaviourally equivalent;
structurally, ours is the sounder model and upstream has painted itself into a frozen schema.

---

### 9. Language chip curation — **adopted / equivalent**

Upstream implements exactly the two-level model we did.

- **Catalog:** `DictateLanguages.all` — 104 entries with `DETECT` first (`dictate/DictateLanguages.kt:48-153`), grown by 45 in 5.3 (`0ddb0b12`, `#252`).
- **Curated subset:** `prefs.dictate.inputLanguages`, a comma-separated string, default `"detect,en"` (`app/AppPrefs.kt:672-677`), parsed by `parseSelection`/`serializeSelection` (`DictateLanguages.kt:187-198`), one-time seeded with the device language (`DictateLanguages.matchDevice`).
- **Active language:** a separate pref `activeInputLanguage` (`AppPrefs.kt:678-683`), snapped back into the subset when it falls out (`DictateLanguagesScreen.kt:87-91`, repair routine at `DictateController.kt:679-685` — commit `4ac770ac` "fixes phantom globe").
- **Curation UI:** checkbox multi-select over the full catalog + a radio dialog restricted to the enabled subset for picking the active one (`app/settings/dictate/DictateLanguagesScreen.kt:106-172`).
- **On the keyboard:** a single `LanguageChip` (`ui/DictateSmartbarUi.kt:412-465`) — **tap cycles** through the subset (`DictateController.cycleLanguage()`, `:665-672`), **long-press opens a dropdown** of the subset. Globe icon for detect, otherwise the uppercase short code. Same globe in the legacy layout (`ui/LegacyDictateLayout.kt:496-506`).

Difference in degree only: upstream shows **one cycling chip**, not a chip *strip*; the curation list
has no search and no user ordering (catalog order is fixed).

---

### 10. Recording robustness — **partially exists** (mixed: BT ≥ ours, focus < ours, concurrency absent)

**(a) Bluetooth SCO — thorough, arguably better than ours.** `dictate/audio/BluetoothMicRouter.kt`
(127 lines): availability probe requires both `isBluetoothScoAvailableOffCall` *and* an actual
`TYPE_BLUETOOTH_SCO` input device (`:47-54`); API 31+ uses the modern `setCommunicationDevice()`
(`:81-86`); API 26–30 calls `startBluetoothSco()` and **waits** for
`SCO_AUDIO_STATE_CONNECTED` via `suspendCancellableCoroutine` under a 2.5 s timeout "so we don't
capture silence" (`:88-125`); timeout falls back to the user's configured local source
(`DictateController.kt:3119-3130`); `deactivate()` is idempotent and driven from a single
`cleanupAudioRouting()` teardown.
*Gap:* no listener for SCO **dropping mid-recording** — the receiver is unregistered after the
handshake, and there is no `AudioDeviceCallback`/`onAudioDevicesRemoved` anywhere.

**(b) Audio focus — materially weaker than ours.**
`requestAudioFocusIfEnabled` (`DictateController.kt:3097-3117`) requests `AUDIOFOCUS_GAIN_TRANSIENT`
and handles **only** `AUDIOFOCUS_LOSS` (→ `togglePause()`). Verified absent by repo-wide grep:
- `AUDIOFOCUS_LOSS_TRANSIENT` and `..._CAN_DUCK` are **not handled at all** — so the common "phone rings mid-dictation" case (which typically delivers `LOSS_TRANSIENT`) does **not** pause the recording.
- No `TelephonyManager` / `PHONE_STATE` / call-state listener (0 hits).
- No `registerAudioRecordingCallback` / `AudioRecordingConfiguration` (0 hits) — cannot detect another app taking the mic.
- On pause the mic is **not released**: `RecordingController.pause()` keeps reading and discarding frames (`audio/RecordingController.kt:104-107, 202-204`).
- The capture loop ignores negative `AudioRecord.read` returns (`if (n > 0 && !paused)`, `:107`) — `ERROR_DEAD_OBJECT` becomes a silent spin.

**(c) Interrupted-recording recovery — present and solid** (`#147`/`#111`). Three triggers funnel
into `stashRecordingOnHide` (`DictateController.kt:2226`): `onWindowHidden`, `onDestroy`, and a
**screen-off broadcast registered for the duration of every recording** (`:874-895`, described as "the
dependable catch-all"). It stops the recorder (patching the WAV header so the file is valid), moves
the WAV to `filesDir/dictate_interrupted.wav` "so it survives the cache wipe", and persists three
prefs. On the next keyboard open a chip offers **send / continue / discard**; *continue* splices the
new segment onto the old via `AudioConcat.concat` (`:2274`).
*Not covered:* a crash/OOM kill **during** recording loses the audio — the header is only patched in
`stop()`, and there is no periodic flush or orphan scan.

**(d) Concurrent recording — explicitly forbidden.** `canStartRecording()`
(`DictateController.kt:598-604`) returns **false** during `Recording`, `Transcribing` *and*
`Rewording`; a mic tap during transcription **cancels** it rather than starting a new capture
(`:613-628`). `DictateController` is a process-wide `object` with a single `recorder` and a single
`transcribeJob` — structurally one dictation at a time. **Our concurrent recording + deferred ordered
insertion has no upstream counterpart** on the normal path.
The one place upstream does order concurrent results is long-form segmentation (`#170`), and it does
it correctly: indices assigned under `segmentMutex` at cut time (`:1811-1819`), results buffered in
`segmentResults` and drained strictly in order by `segmentCommitIndex` (`:1910-1929`), with failed
cuts consuming their index "so the ordered drain never stalls".

---

### 11. Latency optimizations (cold start / pre-bind render) — **absent**

Upstream has done **no IME cold-start or first-frame work.**

- `onCreateInputView()` is the **stock FlorisBoard implementation, untouched** (`FlorisImeService.kt:312-318`): install view-tree owners, add `ImeRootView`, return null. No warm-up, no precomposition, no view caching, no comment about first frame.
- Grepping `first frame|first-frame|cold start|startup|inflat` across `ime/`, `FlorisImeService.kt` and `FlorisApplication.kt` yields one relevant hit (`ime/smartbar/quickaction/QuickActionButton.kt:467`) and it is about the mic key not visually jumping — layout stability, not render speed.
- `preload()` in `ime/nlp/` is inherited FlorisBoard dictionary preloading, subtype-scoped.
- No "bootstrap" symbol exists anywhere; no view retention across configuration changes.

Upstream's latency work is real but aimed entirely at the **dictation pipeline**, and it is
instrumented (`LATENCY_LOG_TAG = "DictateLatency"`, `BatchLatencyTrace`, phase stamps
`stopTapped`/`recorderStopped`/`audioRoutingCleaned`/`outputCommitted` —
`DictateController.kt:112-131, 1064-1086`). Highlights: `SpeechGate.prewarm()` hides one-time native
VAD/ONNX setup behind the user's speech (`:985-987`); the realtime session is opened off the main
thread because doing it inline "stalled the UI thread long enough for Android to cancel the in-flight
touch — which killed push-to-talk ~90 ms into a hold" (`:975-980`); a 39-commit
experiment/revert campaign on OpenRouter transcription latency in July 2026; silence trimming before
upload; base64/resampler copy elimination.

**Our cold-start render, pre-bind bootstrap and view retention work is upstream-absent** — and note
that upstream's Compose-based keyboard has an entirely different render path, so none of it ports.

---

### 12. Accessibility screen context — **absent, by explicit design decision**

Upstream's a11y service reads the **focused editable node and nothing else**, and says so in its own
doc comment (`dictate/overlay/DictateAccessibilityService.kt:36-50`):

> "It only ever reads the **focused** field (to know it is editable and to place text at the cursor);
> it does not collect screen content."

Verified: `focusedEditableNode()` (`:126-132`) → `findFocus(FOCUS_INPUT)`; `findEditableDescendant()`
(`:135-143`) is a DFS bounded to depth 6 that returns a *node* and **collects no text**;
`selectedTextOfFocused()` / `fullTextOfFocused()` (`:422-440`) read that one node.
`accessibility_service_config.xml` deliberately does **not** subscribe `typeWindowContentChanged`.

The freeform voice command (`#230`) uses **only the current selection**:
```kotlin
// DictateController.kt:1443-1449
val selection = sink(appContext).selectedText().takeIf { it.isNotEmpty() }
requestReword(rawText, selection)
```
With nothing selected, `input` is null — the field's full text is **not** silently attached.

Grepped for `screenContext`, "screen context", and any traversal concatenating node text across the
tree: **no hits**.

Our fork's `accessibility/AccessibilityContextReader.kt`, `ViewTreeSerializer.kt` and
`UiNodeSnapshot.kt` are genuine screen-tree capture and have **no upstream equivalent** — and
upstream's stance reads as a deliberate privacy choice, not an oversight, which makes this the area
least likely to ever be adopted upstream.

---

### 13. Session persistence — **partially exists**

`DictateController` is a process-wide `object` with a `SupervisorJob` scope (`:110, :228`), so its
state survives an **IME service** restart within the same process — but nothing about the live state
machine is serialized, so **process death loses all in-flight state.**

Deliberately persisted:

| What | Where |
|---|---|
| Interrupted-recording audio | `filesDir/dictate_interrupted.wav` (`:2215-2216`) |
| Its metadata (pending flag, seconds, was-live) | 3 JetPrefs (`AppPrefs.kt:576-589`) |
| Last dictation text (re-insert) | pref `dictate__last_dictation`, "stored to a pref so it survives the IME process being killed" (`:2357-2359`) |
| Provider keyring | one JSON JetPref (`ProviderAccount.kt:110-147`) |
| History | Room + `filesDir/dictate_history/<id>.wav` |

Explicitly **not** persisted (searched and absent): any in-flight transcription/rewording request
(no foreground service, WorkManager or JobScheduler for transcription — `startForegroundService`
appears only for model downloads and the overlay mic); the pending prompt queue; the carry-over splice
state (`carryOverAudio`, `:446-449` — in-memory only, so a process death during a *continued*
recording loses both halves); any `onSaveInstanceState`-style IME bundle. Recovery is strictly "here
is the finished WAV, send/continue/discard?", never "resume where the state machine was".

Our Room-backed session model is the stronger design here.

---

### 14. Usage / cost tracking per API call — **obsolete-by-design (deliberately deleted)**

This is the one area where upstream made an **explicit product decision against our feature.**

Commit `852e7f2d` "Dictate: remove usage / cost-tracking entirely (roadmap 6)" deleted the whole
`dictate/data/usage/` package — `DictatePricing.kt` (86 lines), `UsageDatabaseHelper.kt` (146),
`UsageModel.kt` (23) — 257 deletions, with the rationale:

> "Pricing tables go stale quickly and cost transparency is out of scope."

Verified today: `dictate/data/usage/` does not exist; `UsageDatabaseHelper|UsageModel|DictatePricing|
usageDb|cost.?tracking` return **zero** hits in `app/src/`. `cost` survives only in prose comments and
the Latin provider's edit-distance costs; `token` only as local-model `tokens.txt` vocab files and
text-parsing tokens. **No API response's `usage` field is read anywhere.**

What replaced it is a different feature: `DictateStats` (`dictate/data/stats/DictateStats.kt`,
`#142`) tracking dictations, words, characters, spoken seconds, rewordings-run (a plain count),
first-use timestamp, day streak and a rolling 7-day window — all local JetPref counters, with a
derived "time saved" figure (`words / 40 wpm * 60 − spokenSeconds`) and milestones. No money, no
tokens.

**Implication:** our usage/cost tracking will never be adopted upstream. It is not a gap — it is a
rejected direction.

---

### 15. Auto-formatting / prompt contexts — **adopted / exceeded**

Upstream distinguishes **five** application contexts (our `PromptContext` has three). They are
separate mechanisms rather than one abstraction:

1. **Transcription style prompt** — sent *with the audio* to bias the STT model, not a rewording call. `transcriptionStyleBasePrompt()` (`DictateController.kt:3011-3018`), selection ∈ {none, predefined per-language punctuation sentence, custom}; appends the user's custom-words glossary. Guarded by `looksLikeStylePromptEcho` (`:110-117`) against Whisper echoing it back on silence, with a unit test (`app/src/test/.../StylePromptEchoTest.kt`, commit `8fbb5c0e`, `#77`).
2. **Auto-formatting** — applied to every dictation, step 1 of `postProcessTranscript` (`:2818-2833`). The prompt is the **fixed** `DictatePromptDefaults.AUTO_FORMATTING_PROMPT` assembled with a language hint — **not user-editable**. Echo guard `looksLikeAutoFormattingPrompt` discards output that is the prompt itself (`#124`, commit `40446747`).
3. **Auto-apply prompts** — user prompts flagged `autoApply` run on every dictation in `POS` order, each step best-effort (`:2835-2846`); also folded into the single-call multimodal instruction and mirrored to the watch.
4. **Live prompt** — a *spoken instruction* instead of dictated text (`startLivePrompt`, `:2786-2802`); on finalize it routes the transcript through `requestReword(rawText, selection)`.
5. **Queued prompts** — see area 4.

Full ordering chain (`finalizeAndCommit`, `:1433-1478`): live-prompt **or** [auto-format → auto-apply
→ queued] → paragraph splitting (only for a pure transcript) → deterministic find-and-replace mappings
(`#129`) → commit.

Conceptually a superset of our REWORDING/LIVE/QUEUED model. The one place ours is better: upstream's
auto-format prompt cannot be edited by the user.

---

## Part C — DevEmperor's PR activity and receptiveness

**Contributor distribution** (`git shortlog -sne upstream/main`):

| Author | Commits |
|---|---|
| DevEmperor (incl. the `Jannis Zahn` and `accounts@devemperor.net` identities) | 397 |
| Alexander Immler | 49 |
| MyButtermilk | 3 |
| Karaoker / karaoker (`karaokedjodua`) | 2 |
| github-actions[bot] | 2 |

**Externally-authored PRs merged (all 5 `Merge pull request` commits):**

- `c9fbf13e` — #216 from **MyButtermilk** `agent/preserve-realtime-audio-start`
- `b69f0721` — #158 from **MyButtermilk** `codex/speed-up-audio-pipeline`
- `bd19deaa` — #168 from **karaokedjodua** `fix/init-order-npe` (NPE in `LatinLanguageProvider` init)
- `0ace1f8d` — #167 from **karaokedjodua** `add-voice-communication-source` (feature, not just a fix — and DevEmperor then *extended* it the same day in `8bb2ee0b` "make the merged VOICE_COMMUNICATION option selectable")
- `3e629657` — #244 from his own `DevEmperor/feature/touch-beam-decoder`

Of 27 merge commits total, the remaining 22 are his own feature branches.

**Alexander Immler (49 commits)** is the notable second voice: 39 commits on 2026-07-18 alone, almost
all `experiment:` / `Revert "experiment:"` pairs bisecting OpenRouter transcription latency, plus
substantive features (`da3ae9dc` Smart Turn v3 semantic auto-segmentation `#191`, `f914e20f` instant
floating-bubble response, `48aae513` recorder-release fix). Landed via `feature/openrouter-latency`
merged in `52ed77f0`. The volume and the direct-to-feature-branch pattern read as a close
collaborator rather than a drive-by contributor.

**Assessment.** The `(#NNN)` suffix on the overwhelming majority of commits is an **issue** reference,
not a PR — and that is the real signal. DevEmperor implements user-filed requests himself, fast and at
depth: `#235` (push-to-talk) drew ~20 commits including several visual reworks and two reverts;
`#128` (realtime) ~18; `#104` (on-device STT) ~12; `#127` (glide/autocorrect) ~9. He also merged
externally-authored code in both directions (bugfix *and* feature) without friction, and the
prompt-library publish flow is deliberately built as "GitHub auto-forks → Propose new file → PR",
i.e. an explicit invitation to contribute.

Practical read for us: **small, well-scoped, issue-anchored PRs have a good chance; large
architectural contributions almost certainly do not** — the codebase is effectively single-author with
a strong personal design voice, and the README states outright that "Full contribution and community
guidelines will be published as the project matures" while directing people to file issues instead
(`README.md:196-200`). For our unique surfaces, **filing an issue to gauge interest before writing
code is the higher-yield move**, especially since two of them (usage/cost tracking, screen-context
capture) run against decisions upstream has already made explicitly.
