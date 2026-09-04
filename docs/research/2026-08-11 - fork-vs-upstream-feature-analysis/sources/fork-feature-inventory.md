# Fork feature inventory (2163ba08..main)

**Baseline.** `2163ba08` (2026-01-06) is DevEmperor/Dictate v3.2.0 plus two upstream fixes: 187 tracked files, **22 Java source files, zero Kotlin, zero tests**, one 2,258-line `core/DictateInputMethodService.java` owning recording, pipeline, layout, visibility and info-bar, two raw `SQLiteOpenHelper` databases (`usage.db`, `prompts.db`), three AI providers (OpenAI, Groq, "own server"), a numpad-style special-character surface, and no persistence of anything the user dictated.

**Today (`048fb37c`, 2026-07-18).** 3 Gradle modules (`:app`, `:shared`, `:companion`), **353 source files / ~67,400 LOC** in `app/src/main/java`, **243 JVM test files + 7 instrumented**, Room schema **v11** with 8 entities, 6 AI providers, **27 ADRs**, 13 archived plan folders, and a Compose-Desktop Windows companion.

**Delta.** 585 commits (554 non-merge), 31 merge commits.

**Caveat carried through the whole report.** The branch `feature/desktop-companion-v1` (`8083882a`, 2026-07-27) is **1,250 files / +189,725 ahead of `main`** and is *not merged*. It contains two further plan runs (`desktop-companion-v1`, `companion-hardening-v2`) and ADRs 0028–0041. Everything below describes `main` unless a cluster explicitly says otherwise; the unlanded branch is recorded as Appendix A.

---

## 1. Build hygiene, test infrastructure & documentation process

**Summary.** Made the fork buildable without Google credentials, then grew a test suite and a documentation apparatus that did not exist upstream at all.

**What it does / value.** Upstream required a `google-services.json` (Firebase BoM + Crashlytics plugins) that is not in the repo, so an outside contributor could not build it; `0ac4ee03` removed the Google Services plugin, the Crashlytics plugin, the Firebase BoM and the crash-reporting calls. `875de2b1` added a `debug` build type (`applicationIdSuffix ".debug"`, `versionNameSuffix "-debug"`, `resValue "string", "app_name", "Dictate Debug"`) so a debug build installs side-by-side with a Play build, plus relaxed API-key validation so first-run onboarding can be clicked through without a real key. On top of that the fork accumulated 243 JVM test files (Robolectric 4.14.1 + JUnit, `maxHeapSize = "2g"`, `forkEvery = 80` because the Robolectric inflation suite OOMs otherwise), 7 instrumented Room-migration tests, a `lint { error += "EnumSwitch" }` rule, and an untracked headless-emulator E2E harness. The documentation side is a genuine asset: 27 ADRs with an index and a relationship graph, 13 archived plan folders with per-plan READMEs and audit reports, 12 `docs/architecture/state-architecture/` teaching documents (including `forbidden-patterns.md` — 14 hard-forbidden patterns a–n), `docs/DATABASE-PATTERNS.md` (335 lines, the Double-Enum rule), and `docs/research/` findings files.

**Scope & key components.** `build.gradle`, `app/build.gradle`, `gradle/libs.versions.toml`, `settings.gradle`; `docs/decisions/` (27 ADRs + README index), `docs/plans/` (13 dated folders + `archive/`), `docs/architecture/state-architecture/` (12 files), `docs/architecture/windows-dispatch/README.md`, `docs/runbooks/companion-windows-release.md`, `docs/DATABASE-PATTERNS.md`; untracked `scripts/e2e/{env,emulator-up,install-and-enable-ime,emulator-down}.sh` and `docs/architecture/e2e-emulator.md` (147 lines — KVM, `-gpu swiftshader_indirect`, and the machine-checkable keyboard-visible proof `adb shell dumpsys input_method | grep -E 'mInputShown|mCurMethodId'`).

**Key commits / plans.** `0ac4ee03`, `875de2b1`, `269cff59`; the ADR corpus was seeded by `26793129` / `1ca3bcca` ("5 ADRs + state-architecture docs as binding pre-code anchor" — written *before* the code).

**Size.** S for the build patch itself; **XL** counting the test suite (~53,000 LOC of tests) and 188 doc files / ~104,000 lines that shipped inside merge `efd39d25`.

**Category.** architecture/refactor (infrastructure).

**Coupling.** The de-Firebase patch and the debug variant are standalone and trivially portable. The test suite and ADR corpus describe fork architecture and do not port. The E2E emulator scripts are project-agnostic and portable. **Note:** `scripts/`, `tmp/` and `docs/architecture/e2e-emulator.md` are currently **untracked** — they exist on disk only.

---

## 2. AI abstraction layer & provider expansion

**Summary.** Replaced upstream's hardcoded provider `switch` blocks with a typed `AIProvider` enum, a `RunnerFactory` and runner interfaces — and added three providers on top.

**What it does / value.** Upstream 3.2 had no provider abstraction: model lists lived in `res/values/arrays.xml` string-arrays and `DictateUtils.java` `switch` blocks, and provider choice was a raw int in prefs (`0=OpenAI, 1=Groq, 2=own server`). `030cd760` introduced `AIProvider` with capability flags (`supportsTranscription`, `supportsCompletion`, `isOpenAICompatible`, later `allowsStructuredOutputTextFallback`), a central `AIOrchestrator` as the single entry point for every AI call, `TranscriptionRunner`/`CompletionRunner` interfaces behind a `RunnerFactory`, a hybrid model registry (`ModelFetcher` + local known-models fallback), a `ParameterRegistry` for per-provider temperature / max-tokens / reasoning-effort, and a typed `AIProviderException`. The same commit migrated the two raw SQLite helpers into one Room database, introduced `DictatePrefs.kt` (`sealed class Pref<T>`) and `PrefsMigration.kt`, and first cut the God-Class down by extracting `RecordingManager.kt`, `BluetoothScoManager.kt`, `PromptQueueManager.kt`. This is where Kotlin, KSP and Room enter the project. **Providers added over upstream:** Anthropic (completion-only, `com.anthropic:anthropic-java` 2.16.0), OpenRouter (completion-only — no `/audio/transcriptions` endpoint), and ElevenLabs Scribe (transcription-only, non-OpenAI wire format, own runner). ElevenLabs also brought provider-aware error handling (upstream's single "check your billing" message was wrong for other providers) and **key terms**: a user-maintained vocabulary list (names, jargon) biasing recognition, edited in `SystemPromptsActivity`.

**Scope & key components.** `ai/AIOrchestrator.kt`, `ai/AIProvider.kt` (6 values: `OPENAI`, `GROQ`, `ANTHROPIC`, `ELEVENLABS`, `OPENROUTER`, `CUSTOM`), `ai/AIProviderException.kt`, `ai/factory/RunnerFactory.kt`, `ai/model/{ModelFetcher,ModelInfo,ParameterDef,ParameterRegistry}.kt`, `ai/runner/{OpenAICompatibleRunner,AnthropicCompletionRunner,ElevenLabsTranscriptionRunner,CompletionRunner,TranscriptionRunner,StructuredOutputGuards}.kt`, `ai/ElevenLabsKeytermsParser.kt`, `preferences/DictatePrefs.kt` (today 82 keys), near-total rewrite of `settings/APISettingsActivity.java`.

**Key commits / plans.** `030cd760`; `39b431dc`, `3396effd`, `fea392b8` (ElevenLabs). Plan `docs/plans/archive/ai-abstraction-layer.md` (10 numbered architecture decisions, e.g. "Kotlin alongside Java, no rewrite", "Runners not Strategy", "no DI framework", "SharedPreferences keys stay compatible").

**Size.** **L** — `030cd760` alone: 52 files, +3,007 / −1,510. ElevenLabs adds ~+591 / −88.

**Category.** architecture/refactor with user-facing payoff (three new providers).

**Coupling.** **The most portable major cluster in the fork.** `ai/` depends only on the two vendor SDKs and `SharedPreferences`; prefs keys were deliberately kept upstream-compatible. It drags Kotlin + KSP + Room into the build, and the Room migration + `APISettingsActivity` rewrite are the coupled parts. Everything else in the fork that calls AI goes through it.

---

## 3. Prompt architecture — `PromptService` + XML-tag `PromptBuilder`

**Summary.** Ad-hoc string concatenation replaced by a fluent builder that emits XML-tagged, injection-escaped prompt sections with context-specific system prompts.

**What it does / value.** Upstream built prompts by concatenating strings inside `DictateUtils`/the IME service. `7a0b8c4f` extracted a `PromptService` assembling prompts from typed templates via a `PromptBuilder` that produces tagged sections (`<instruction>`, `<selected-text>`, `<user-request>`, `<language-hint>`, `<transcript>`, `<rules>`, `<examples>`). Untrusted content goes through `dataSection()`, which XML-escapes `& < >` so a transcript cannot close its own tag and forge sibling instructions — prompt-injection containment as a structural property, not a filter. System prompts became context-specific via `PromptContext` (REWORDING / LIVE / QUEUED) resolved by `SystemPromptResolver`, and `AutoFormattingService.kt` was extracted as the first consumer.

**Scope & key components.** `ai/prompt/{PromptService,PromptBuilder,PromptContext,PromptMode,PromptTemplates,SystemPromptResolver,PromptTypeClassifier}.kt`, `core/AutoFormattingService.kt`.

**Key commits / docs.** `7a0b8c4f` (8 files, +350 / −59). Retrospective audit: `tmp/research-prompt-architektur.md` (2026-07-15) — documents the resulting "two worlds" (legacy single-completion `PromptService` path vs. the ADR-0012 consolidated-conversation path).

**Size.** **S**.

**Category.** architecture/refactor.

**Coupling.** Very high portability — `PromptBuilder` is a ~70-line Android-free class; `PromptService`/`PromptTemplates` need only prefs. **Single most extractable piece in the fork.**

---

## 4. Session persistence & the pipeline audit database

**Summary.** The whole dictation pipeline — session, transcription, each processing step, completion log, text insertion — became persisted, versioned Room rows with a history UI and a live in-keyboard progress bar.

**What it does / value.** Upstream discarded everything: audio was overwritten by the next recording, transcripts and AI outputs lived only in RAM. This cluster persists the entire chain with **insert-only versioning**, so regenerating a step never destroys the previous version, plus an audit trail of what was inserted where and how. It shipped the first History UI (list + detail with pipeline view, audio playback, clipboard/share, version switcher, regenerate-with-prompt-chooser), parent/child sessions for reprocess/reword, a keyboard history button, and a live pipeline progress bar with a per-step elapsed timer and cancel button. Mid-cluster refactors (`aac2c4c3`, `cf0d879c`, `695b9a30`) extracted `KeyboardUiController`, `PipelineOrchestrator`, `KeyboardStateManager` and `MainButtonsController` from the God-Class purely to keep it from exploding — which is exactly the pressure that later triggered the May architecture wave.

**Scope & key components.** Entities `SessionEntity`, `TranscriptionEntity`, `ProcessingStepEntity`, `CompletionLogEntity`, `TextInsertionEntity` (tables `sessions`, `transcriptions`, `processing_steps`, `completion_log`, `text_insertions`) + enums `SessionType`, `StepType`, `StepStatus`, `InsertionMethod`, `InsertionSource`; 5 DAOs; `core/SessionManager.kt`, `core/SessionTracker.kt` + `ProcessingContext`; Room migrations v1→v2→v3; `history/` package; `PipelineProgressView` + `pipeline_progress_view.xml`.

**Key commits / plans.** Branch `feature/session-persistierung`, merged three times: `8911d086` (66 files, +6,900 / −784), `3f3d9aaf`, `98784114`. Core commits `6608bfa2` (46 files, +4,359 / −39), `d104e052`, `0b3df9a9`, `73fb4b29`, `aac2c4c3`, `beeba6c9`, `463c0915` (auto-reload prompts via Room `InvalidationTracker`), `6a8e33aa`. Plan `docs/plans/session-persistierung.md` (~1,150 lines, German, 5 phases) + `.state.md`.

**Size.** **XL** — well north of +10,000 lines across the branch.

**Category.** user-facing feature + persistence architecture.

**Coupling.** The schema and `SessionManager`/`SessionTracker` are reusable in isolation, but plan Phase 2 explicitly threaded a `ProcessingContext` through every pipeline call site inside the God-Class (`AutoFormattingService`, `requestRewordingFromApi()`, `commitTextToInputConnection`). This is the cluster that most tightly bound the fork to its own IME service. Everything in clusters 15–21 sits on this schema.

---

## 5. QWERTZ full keyboard & keyboard ergonomics modes

**Summary.** The numpad/special-character surface was replaced by a full SwiftKey-style QWERTZ keyboard, and the surrounding chrome gained Small Mode, Single-Row Mode and a dedicated edit toolbar.

**What it does / value.** `d6e2b769` replaced the small special-character overlay with a permanent full keyboard: three letter rows plus a numeric/symbol layer behind a `123` key, a shift/caps state machine, tab, a close-keyboard key, accelerating key repeat for backspace, cursor-swipe on the space bar, and per-key press animation. This changes what Dictate *is* — from a dictation overlay into a general keyboard that also dictates. Around it: **Small Mode** collapses the upper UI sections when the keyboard eats the message field; **Single-Row Mode** compresses the two button rows to one; the mode toggles moved out of the main row into a dedicated **edit toolbar** with a keyboard show/hide toggle; and an **Audio-Focus runtime toggle** stops Dictate ducking/pausing media while recording. Later (ADR-0022, July) the edit bar was rebuilt because it was a ConstraintLayout chain of `0dp` buttons that divided the viewport evenly and unconditionally — going from 12 to 14 buttons pushed icons past a usable touch target on a 320 dp phone. It now scrolls, derives slot widths at measure time (52 dp minimum), and deliberately cuts the last visible button so ≥12 dp peeks, because a row that ends flush with the viewport reads as complete and the rest is never discovered.

**Scope & key components.** `keyboard/{QwertzKeyDef,QwertzKeyboardLayout,QwertzLayoutProvider,QwertzKeyboardView,QwertzKeyboardController,KeyPressAnimator,AcceleratingRepeatHandler,CursorSwipeTouchHandler,BackspaceSwipeHandler,EnterOverlayHandler,VerticalDragResizeHandler}.kt`; `res/layout/activity_dictate_keyboard_view.xml` (−263 lines of hand-placed buttons in the first commit); `core/KeyboardLayoutModeController.kt`, `core/AudioFocusGate.kt`; `widget/PeekingButtonBar` + pure `widget/EditBarWidthCalculator`, `state/render/EditBarController.kt` (511 LOC); prefs `SmallMode`, `SingleRowMode`, `AudioFocus`.

**Key commits / plans / ADRs.** `d6e2b769` (15 files, +1,477 / −403), `89fa64d0`, `d0709f54`, `70464f17`, `f40d0bd6`, `4d30111a`, `efeb89e2`, `716e2696` (+1,344 / −56), `9e563180` (16 files, +1,332 / −113, ships the repo's first real unit tests), `72d40eab` (edit-bar peek, inside merge `c8d670d6`). Plans `docs/plans/qwertz-keyboard-plan-v1-implemented.md`, `docs/plans/qwertz-ui-integration-fix.md`. ADR **0022**.

**Size.** **L** — ~2,300 lines for QWERTZ across four commits, plus ~2,700 for the ergonomics modes.

**Category.** user-facing feature + UX polish.

**Coupling.** The `keyboard/` package is a self-contained custom `ViewGroup` + controller with a `KeyDef`-driven layout provider — mechanically portable to any IME. But it is a hard fork divergence in product terms, not an upstreamable patch, and it owns the top-level layout XML that every later UI cluster builds on. `EditBarWidthCalculator` and `PeekingButtonBar` are cleanly liftable.

---

## 6. Language chip curation & versioned-envelope preference storage

**Summary.** The 58-entry language spinner became an always-visible 2-letter pill with a curated shortlist, on top of a generic versioned JSON envelope for preference values.

**What it does / value.** Upstream showed the input-language selector only in certain states and offered all 58 entries flat. The fork makes the input language a compact, always-visible 2-letter pill on the prompt bar; tapping opens a grouped `PopupMenu` with the user's curated languages above a divider and everything else below, and the curated set is editable in settings. (`0d8eb736` switched from `AlertDialog` to `PopupMenu` — showing a dialog from an IME window throws `BadTokenException`.) Underneath sits a reusable **Versioned-Envelope** storage layer ported from the user's `excel_ekl` project: pref values are stored as `{version, payload}` JSON with a plugin registry and a migrator chain, so a stored list can change its schema without data loss — needed here because the `input_languages` key changes type from `StringSet` to `String`. The same plan bundled a resend-button fix (robust `InputConnection` capture with a 3-stage fallback; `FAILED` → no-op instead of silent auto-resume).

**Scope & key components.** `preferences/versioned/{Versioned,JsonCodec,VersionedPlugin,VersionedPluginRegistry,VersionedPrefs,VersionedSerializer,VersionedMigrator}.kt` + `InputLanguagesPlugin`; `LanguageController`, `LanguageLabelResolver`, `PipelineUiStateReader`; `item_prompts_keyboard_language_chip.xml`.

**Key commits / plans.** `0c8828f7` (13 files, +1,268, of which 763 are tests), `5d988cac`, `d8328cd3` (29 files, +4,994 / −147), `0a7071f5`, `798ceb24`, archived at `e8d8fd00`. Plan `docs/plans/archive/language-chip-curation.md` (+ `.state.md`, `.chunks.json`, `.verification.md`) — documents 27 quality-gate findings and 8 named risks A–H, including the downgrade-data-loss risk of the key type change and the `MultiSelectListPreference.persistStringSet()` trap solved via `setPersistent(false)`.

**Size.** **L** — ~7,500 added lines.

**Category.** user-facing feature + prefs infrastructure.

**Coupling.** `preferences/versioned/` is highly portable and self-contained (7 files, no Android beyond `SharedPreferences`) — it is itself already a port from another project. The chip UI on top is fork-specific.

---

## 7. Recording visual feedback

**Summary.** A pulse/ripple animation behind the record button and a real amplitude visualizer, both behind a strategy interface.

**What it does / value.** Upstream's recording feedback was minimal. `1deed1ae` added a `PulseLayout` custom `ViewGroup` rendering a ripple pulse behind the record button, with the animation itself behind a `RecordingAnimation` strategy interface and configurable via custom XML attrs. `007cd02e` extracted `RecordingStateController` (284 LOC) and `RecordingUiController` from the God-Class and added an amplitude visualizer: `AmplitudeProcessor` smooths the mic amplitude, `AmplitudeVisualizerDrawable` draws it, and `BorderGlowAnimation`/`BorderGlowDrawable` add a second strategy so the recording border glows with voice level.

**Scope & key components.** `widget/{PulseLayout,RecordingAnimation,RipplePulseAnimation,AmplitudeVisualizerDrawable,BorderGlowAnimation,BorderGlowDrawable,VisualizerUtils}.kt`, `core/{AmplitudeProcessor,RecordingState,RecordingStateController,RecordingUiController}.kt`, `res/values/attrs.xml`. Later reused by the overlay widget and by `state/render/{RecordGlowFactory,RecordingAnimationController,RecordButtonColorController}.kt`.

**Key commits.** `1deed1ae` (7 files, +268 / −58), `007cd02e` (12 files, +1,008 / −309), `b1706db6` (9 files, +311 / −426). Adjacent robustness fixes: `e78e9a2b` (backspace auto-delete stops on finger lift), `510a2ffb` (null checks in `onDestroy` to survive an app update while bound), `50d96971` (survive rotation by splitting `onCreate`/`onCreateInputView`).

**Size.** **M**.

**Category.** UX polish.

**Coupling.** `widget/` drawables + the strategy interface are largely standalone and portable; the controllers are coupled to `RecordingManager` and the fork's UI layer.

---

## 8. Modular state store & `DictateOrchestrator` (the architectural nucleus)

**Summary.** A Redux-style single-dispatch state container split into per-axis modules with pure reducers, lens-based sub-state and a governed cross-module cascade.

**What it does / value.** Upstream had five parallel writers on the same logical state (`RecordingStateController`, `RecordingUiController`, `KeyboardUiController`, `KeyboardStateManager` and the IME service itself); ADR-0001's research section counts **27 visibility mutations on the single `resend_btn` attribute** and five production bugs traced to it. The replacement makes `DictateOrchestrator.dispatch(Action)` the only mutation entry point, gives each state axis one `DictateModule<S, A, E>` addressed by a lens, has reducers return a pure `TransitionResult`, and executes `SideEffect`s outside the reducer with `EffectFailure` routed back by `originModuleId` (not by KClass — a deliberate correction from the Phase-B S-3 review). ADR-0002 codifies how modules influence each other: Mode 1 (own SideEffect), Mode 2 (`onCrossModuleStateChange` against a frozen snapshot, guarded by `MAX_CASCADE_DEPTH`), and forbidden Mode 3 (cross-axis writes in a foreign reducer). The self-cascade filter was removed after it caused a real bug — the HOVER overlay never reopened after its first close.

**Scope & key components.** `state/` is **103 `.kt` files / 25,508 LOC**. Core: `DictateOrchestrator.kt` (471), `DictateModule.kt` (216), `DictateModuleRegistry.kt` (267, `assertCompleteCoverage()`), `DictateUiState.kt` (1,168), `Action.kt` (1,546), `ModuleServices.kt` (670 — the DI seam), `SideEffect.kt`, `TransitionResult.kt`, `ModuleId.kt`. `state/modules/` holds 19 files / 6,037 LOC, 18 registered: Recording (1,225), Pipeline (891), Audio (521), Overlay (505), Widget (336), ViewMode (legacy), Resend, LivePrompt, Language, Layout, FeatureToggle, Theming, PendingSessions, KeyboardInput, InfoHint, Interruption, ReviewPanel, HistoryPanel, WindowsDispatch. `DictateUiState` carries **20 axes** today. Teaching docs: `docs/architecture/state-architecture/` (12 files).

**Key commits / plans / ADRs.** All inside merge `efd39d25` (277 commits). Plans: `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/` (6 blocks / 19 chunks, shipped 946 tests, built as a **parallel-dormant** second architecture), `2026-05-15 - dictate-cutover-completion/` (flipped it live, deleted the four legacy controllers), `2026-05-21 - dictate-indirection-cleanup/` (878 lines, symmetric input-side single-dispatch), `2026-05-23 - dictate-enter-button-host-action/`. ADRs **0001**, **0002**.

**Size.** **XL** — ~13,400 LOC of production code for store + modules; 25.5k LOC for the whole state tree, roughly 11× the entire upstream IME service.

**Category.** architecture/refactor (foundational).

**Coupling.** Clusters 9–22 and the Android half of 24–26 are all clients, hosts or modules of this. **Not portable — it is the new foundation.** `DictateInputMethodService.java` was not deleted but re-homed: 2,148 lines pre-merge → 5,766 at `efd39d25` → 7,418 today, now a host/wiring shell that dispatches actions instead of owning state.

---

## 9. Foreground pipeline service (`DictatePipelineService`)

**Summary.** A bound foreground service hosts the orchestrator, so recording and pipeline survive the IME being destroyed.

**What it does / value.** Android destroys the IME service when the user switches to another keyboard (e.g. Gboard for a password field) — anything held inside it dies mid-recording. ADR-0003 moves the state container into a process-resident FGS with `foregroundServiceType="microphone"` (mandatory from API 34) and an in-process `LocalBinder` (`exported=false`). The persistent notification doubles as the user-facing recording indicator. WorkManager was explicitly evaluated and rejected. Crash/OOM recovery is `PipelineRecovery` reading Room rows, plus a `BootCompletedReceiver` running `recoverDbOnly()` before the keyboard is first opened.

**Scope & key components.** `core/DictatePipelineService.kt` (2,375 LOC — channel-before-`startForeground` ordering, `startForegroundCompat`, SCO receiver registration, audio-focus request building), `core/PipelineNotificationCoordinator.kt` (344), `PipelineActionRouter.kt`, `PipelineCallbackBridge.kt`, `PipelineRunnerSubsystemAdapter.kt` (404), `ActiveJobRegistry.kt`, `BootCompletedReceiver.kt`, `PipelineTerminalDispatchGuard.kt`; `state/{PipelineRecovery,PipelinePrefMirror,PipelineBindReconciliation,PipelineOrphanCleaner,PipelineSessionRepoAdapter}.kt`; manifest permissions `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS` (gated by `core/NotificationPermissionPolicy.kt`). DB: **v3→v4** (`MigrationTo4.kt`) adds `sessions.inserted_at`, extends the status CHECK with `RECORDING`/`TRANSCRIBING` for OOM-death recovery, and flips the `parent_session_id` self-FK from `ON DELETE CASCADE` to `SET NULL` (a data-loss fix).

**Key commits / plans / ADRs.** `efd39d25`; plans `2026-05-07 - dictate-keyboard-layout-refactor` (Block 2) and `2026-05-15 - dictate-cutover-completion`. ADR **0003**.

**Size.** **L** — ~15 production files / ~5,500 LOC + manifest + one Room migration; 66 Robolectric test files under `core`.

**Category.** architecture/refactor with directly user-facing payoff (keyboard-switch survival, crash recovery).

**Coupling.** Hosts cluster 8; structural precondition for the pipeline-driven widget (11), the interruption producers (14) and headless completion (15). **Not portable independently.**

---

## 10. LayoutCatalog + MotionLayout render backends

**Summary.** Declarative per-mode button catalogs rendered by pluggable `RenderBackend`s over a MotionScene, replacing imperative view re-parenting.

**What it does / value.** Pre-refactor, `KeyboardLayoutModeController` re-parented buttons between `input_row` and `action_row` per mode with an `originalParents` map as the band-aid — the root cause of the "asymmetric re-parenting" bug class. ADR-0004 makes that structurally impossible: no re-parenting at all, only a MotionLayout transition between ConstraintSets, with `motion:visibilityMode="ignore"` on every state-driven button so state owns visibility. `LayoutCatalog` declares, per `LayoutModeId`, which `LogicalButtonId` sits in which `ButtonSlot` with predicate/text/icon/action resolvers; backends only read `state.collect` and never write state. ADR-0010 is the sibling convention: icon colour comes from theme attributes **at the usage site**, enforced by a JVM source-scan invariant test.

**Scope & key components.** `state/layout/` (12 files / 3,226 LOC): `LayoutCatalog.kt` (914), `ActionResolvers.kt` (658), `TextResolvers.kt`, `IconResolvers.kt`, `LayoutPredicates.kt`, `EnterRoleResolver.kt`, `ButtonSlot.kt`, `LogicalButtonId.kt` (18 ids), `LayoutMode.kt`/`LayoutModeId.kt`, `KeyboardLayoutManager.kt`, `RenderBackend.kt`. Eight layout modes: `KEYBOARD_TWO_ROW`, `KEYBOARD_SINGLE_ROW`, `KEYBOARD_TWO_ROW_SEND_MODE`, `KEYBOARD_SINGLE_ROW_SEND_MODE`, `KEYBOARD_REPROCESS_STAGING`, `KEYBOARD_REVIEW_PANEL`, `KEYBOARD_HISTORY_PANEL`, `OVERLAY_5BUTTON`. `state/render/` (22 files / 4,382 LOC): `ImeViewBackend.kt` (649), `SlotRenderer.kt`, `MotionSurface.kt`, `RenderGate.kt`, `EditBarController.kt`, `PipelineStepRowRenderer.kt`, `ContentAreaController.kt`, `EmojiController.kt`, `QwertzRecordingController.kt`, `PromptVisibilityController.kt`, `AutoEnterRenderer.kt`, `HistoryPanelRenderer.kt`, `ReviewPanelRenderer.kt`, `SpecialTouchHandlerInstaller.kt`, `EffectiveNightMode.kt`, `PcModeFrameRenderer.kt`. XML: `res/xml/motion_scene_keyboard.xml` (604 lines).

**Key commits / plans / ADRs.** `efd39d25`; plans `2026-05-07 - dictate-keyboard-layout-refactor` (Spec 2), `2026-05-21 - dictate-render-cutover-completion-vol2/` (rebuilt `PipelineStepRowRenderer` from a 100 ms-tick legacy renderer into a reactive state consumer and deleted the legacy `core.PipelineUiState` sealed class), `2026-05-21 - dictate-pipeline-render-and-state-unification/` (1,576 lines, five device-observed regressions). ADRs **0004**, **0010**, **0022**.

**Size.** **XL** — 34 production files / ~7,600 LOC + a 604-line MotionScene; 41 test files.

**Category.** architecture/refactor with visible UX outcome.

**Coupling.** Read-only consumer of cluster 8, but its resolvers take `DictateUiState`, so it does not detach. The overlay backend (11), the info-bar renderer (12) and the PC-dictation Activity (26) are the second, third and fourth clients of the same `RenderBackend` contract. **The pattern is portable; the concrete catalog is not.**

---

## 11. Widget / overlay floating mode

**Summary.** A draggable `TYPE_APPLICATION_OVERLAY` dictation surface — user-toggled or pipeline-driven — modelled first as a 3-mode FSM, then re-modelled into two orthogonal axes.

**What it does / value.** Two distinct needs: a floating keyboard the user toggles on (WIDGET, InputConnection still alive), and an auto-surfacing control UI when the IME view is gone but a pipeline is still running (HOVER — otherwise recording continues in "zombie mode" with no send/cancel affordance). ADR-0005 modelled this as a Triangle-FSM with a `computeViewMode` truth table (T1–T7; T7 HOVER→KEYBOARD on `PipelineDone` was added specifically to kill the "ghost widget" class). ADR-0008 **supersedes** it: once the bidirectional-render fix made both surfaces simultaneously live, a 1-of-3 enum was already a lie, so it became `WidgetState.Visible(origin = USER | PIPELINE)` plus an orthogonal `imeViewVisible` boolean — which is what makes "after the pipeline ends, return to KEYBOARD or WIDGET?" decidable and enables seamless crash resume. July brought parity work: a user-configurable transparency (20 % floor), opaque tonal containers under the translucent card so icons stay legible, a third editing row (delete/space/enter), record/send precedence fixes, and a full IME-surface collapse when the user widget is open.

**Scope & key components.** `state/render/overlay/` (11 files / 2,586 LOC): `OverlayBackend.kt` (1,174), `OverlayWindow.kt`, `OverlayLayoutParamsFactory.kt`, `OverlayDragController.kt`, `DraggableOverlayLayout.kt`, `OverlayPositionMapper.kt`, `OverlayCardFill.kt`, `OverlayPermissionGate/Observer`, `OverlayDeleteRepeatController.kt`, `OverlayCharactersController.kt`. Modules `WidgetModule.kt` (336), `OverlayModule.kt` (505), legacy `ViewModeModule.kt`. `res/layout/overlay_5button_layout.xml` (231 lines), `res/values/styles_overlay.xml`. Prefs: `WidgetOpacity`, `OverlayPositionPortraitX/Y`, `OverlayPositionLandscapeX/Y`, `OverlayOnboardingShown/Dismissed`, `OverlayCharacters`, `Theme`. DB: `MigrationTo6.kt` (v5→v6) widens the status CHECK with `RECORDING_INTERRUPTED`.

**Key commits / plans / ADRs.** `efd39d25` (base); plans `2026-05-21 - dictate-widget-integration/` (1,073 lines) and `2026-05-21 - dictate-widget-state-and-recovery/` (415 lines, the ADR-0008 refactor + crash-recovery auto-continuation). July: `fac8af72` (21 files, +749 — opacity pref, `Pref.Theme` unification, config-change re-inflate, new `EffectiveNightMode.kt`), `9d148bb9` (+128), `905ae915` (third row, 9 files / +453), `f557fd09` (record precedence, 4 files / +191 / −92), `43784f72` (widget-mode collapse, in `c8d670d6`). Research `docs/research/2026-07-02 - overlay-widget-transparency.md`, `2026-07-11 - widget-mode-parity-and-third-row.md`. ADRs **0005 (superseded)**, **0008**.

**Size.** **XL** — ~22 production files / ~4,400 LOC plus ~1,350 code insertions across the four July merges; 12 overlay test files.

**Category.** user-facing feature (May) → UX polish + bugfix wave (July).

**Coupling.** Depends on 8 (owns two axes), 9 (PIPELINE origin only exists because the FGS outlives the IME), 10 (renders via `LayoutCatalog.OVERLAY_5BUTTON`), 13 (continuation resumes multi-segment audio). Feeds 12 (info-bar producers). **The least portable feature in the fork.** Open debt: `ViewModeModule`/`ViewMode`/`ViewModeAction` still coexist with `WidgetModule` (282 reader migrations deferred), and `state-architecture/triangle-fsm.md` still documents the superseded model.

---

## 12. Info-bar notice system

**Summary.** One typed notice axis whose items are a pure function of state, replacing nine hardcoded imperative `showInfo()` cases, a parallel permission bar and scattered toasts.

**What it does / value.** Upstream's `InfoBarController.showInfo(type)` carried 9 hardcoded types mutating views directly from ~6 call sites, alongside a second `overlay_permission_infobar` surface with its own renderer, plus operational `Toast.makeText` fired from 3–4 sites each. One AI error fanned to 4–5 surfaces through independent wirings with no shared "error" fact in state, and the pipeline FSM destroyed the error *kind* (`PipelineFailed → Idle`). ADR-0006 makes info items a pure selector `(DictateUiState) -> List<InfoBarItem>` — "producers" are reads against state, not classes — and persists dismissal through each item's natural source instead of a uniform `dismissed` flag. It also defines lockdown: pending items while the IME is hidden turn the overlay into a full-screen translucent hint instead of the normal 5-button widget. The July consolidation (`be060522`) finished the job, deleting the legacy `InfoBarController` and the dead `ToastSink` — a good example of the fork auditing itself, since `InfoBarController.onStateChanged` had silently lost its only caller when `KeyboardStateManager` was deleted, leaving `suppressDisplay` permanently false.

**Scope & key components.** `state/infobar/` (5 files / 884 LOC): `InfoBarSelector.kt` (540), `InfoBarRenderer.kt`, `InfoBarItem.kt`, `InfoBarMessage.kt`, `InfoBarStyle.kt`; `state/InfoHintState.kt` (188) + `state/modules/InfoHintModule.kt` (241). Eleven producer blocks: panel ownership, widget ownership, overlay-permission onboarding, pending-parts aggregate, partial-recovery warning, recovery-surfaced unfinished recording, interruption-paused recording, pipeline errors, windows-dispatch notice, cancellation notice, engagement hints. Container unified onto `info_cl`.

**Key commits / plans / ADRs.** `efd39d25` (initial, wired wrongly to `overlay_permission_infobar`); plan `docs/plans/2026-05-22 - dictate-infobar-migration/` (293 lines + `research/infobar-territory-map.md`, `research/info-feedback-channels.md`); `be060522` (28 files, +1,604 / −426). Research `docs/research/2026-07-02 - infobar-consolidation.md` (findings F-039/F-040). ADR **0006**.

**Size.** **M** — ~8 production files / ~1,300 LOC; the consolidation merge includes ~600 lines of new tests.

**Category.** architecture/refactor + UX polish.

**Coupling.** Pure downstream consumer of 8 and 10; its producers read axes from 11, 13, 14, 15 and 24. The pattern is portable; the concrete selector is not.

---

## 13. Multi-file audio repository & recording stack completion

**Summary.** An N-segment on-disk reality hidden behind a one-audio-per-session API, making resume-after-process-death possible.

**What it does / value.** `MediaRecorder` cannot append: once `release()` runs or the process dies, restarting against the same path overwrites at offset 0 — so a crash mid-recording silently destroyed everything recorded so far. ADR-0007 answers with `AudioFileRepository` as the sole owner of the on-disk naming convention (`{prefix}{sessionId}{infix}{N}{ext}`); callers only ever receive `File` handles. `readForPipeline()` is `suspend`: single-segment sessions return zero-copy, multi-segment sessions are concatenated via `MediaExtractor.readSampleData` → `MediaMuxer.writeSampleData` (safe because all segments share codec params). Pipeline, state and recovery keep believing in one file. This is what makes the widget's "Continue" affordance and rolling-segment loss protection possible; "Discard" calls `deleteAll(sessionId)` to sweep every segment.

**Scope & key components.** `audio/` (6 files / 695 LOC): `AudioFileRepository.kt` (interface), `AudioCodecReader.kt`, `CodecParams.kt`, `PipelineAudioResult.kt`, `CacheAudioCleanupJob.kt`, `CacheAudioCleanupScheduler.kt`; `core/CacheDirAudioFileRepository.kt` (311) + legacy `CacheDirAudioFileFactory.kt`; `core/RecordingHardwareAdapter.kt` (477, `allocateFirst`/`allocateNext`), `RecordingManager.kt`, `RecordingRepository.kt`, `state/ContinuationLookup.kt`. Migrations: **v4→v5** (`sessions.audio_file_paths` pipe-delimited, backfilled, with `effectiveAudioFilePaths` as the bridge), **v5→v6** (`RECORDING_INTERRUPTED` in the CHECK, table recreate since SQLite cannot drop a CHECK), **v6→v7** (one-shot backfill closing the "paths empty but legacy file on disk" gap).

**Key commits / plans / ADRs.** `efd39d25`; plan `2026-05-21 - dictate-widget-state-and-recovery/` Block B1 built the repository **unwired**; `docs/plans/2026-05-22 - dictate-recording-stack-completion/` (1,020 lines) Block A is the actual cutover, Block B the `ViewMode` removal, Block C polish. Later fixes: `bc9390b7` (F-000 critical — the IME start path was not allocating through the repository, an audio-loss bug), `59ce0193` (`significantSegments` — false partial-recovery, dead continuation, 16 s history durations). ADR **0007** (header still says *Proposed* although implemented).

**Size.** **L** — ~10 production files / ~1,600 LOC for the audio layer + 3 migrations; ~2,500 LOC counting `RecordingModule` and `RecordingHardwareAdapter`.

**Category.** architecture/refactor enabling a user-facing capability.

**Coupling.** Depends on 8 and 9; cooperates with 11 and 12. **`AudioFileRepository` + `CacheDirAudioFileRepository` (MediaMuxer concat) is the most portable artifact of the whole May wave** — only its call sites are fork-shaped.

---

## 14. Recording interruption, audio focus & Bluetooth SCO

**Summary.** Audio focus declared the interruption authority; headset and BT-SCO events became state-machine actions instead of silent hardware behaviour.

**What it does / value.** Before this, an incoming call during a recording neither paused nor cancelled it — the mic kept capturing through the ringtone and the call. The state-machine slot existed (`InterruptionAction` leaves plus a registered `InterruptionModule` stub whose KDoc falsely claimed IME-side listeners dispatched it), but a repo-wide grep for `PhoneCallStateChanged|HeadsetPlugChanged|ScreenStateChanged` hit only declarations and a test comment. Compounding it, a service-side audio-focus listener already paused recording through an undocumented channel with no state-machine involvement (F-007). The fix subsumes rather than duplicates: audio focus becomes the explicit authority, classified by pure `InterruptionClassifiers`/`AudioFocusChangeClassifier`, and a headset producer plus SCO receiver wiring feed the same axis; the paused state then surfaces through an info-bar producer.

**Scope & key components.** `state/modules/InterruptionModule.kt` (191, rewritten), `core/InterruptionClassifiers.kt` (106, new), `state/modules/AudioModule.kt` (521), `core/AudioFocusGate.kt` + `AudioFocusSubsystemAdapter.kt`, `core/BluetoothScoManager.kt` (244) + `BluetoothScoSubsystemAdapter.kt`, `core/EditBarAudioFocusObserver.kt`; SCO receiver register/unregister in `DictatePipelineService.kt`; `AudioState { audioFocusEnabledPref, audioFocusGranted, bluetoothSco: BluetoothScoPublicState(phase, failureReason), useBluetoothMic, vibrationEnabled }`; new info-bar producer block + 6 strings.

**Key commits / plans.** `69a497ea` (22 files, +1,029 / −148; `InterruptionClassifiersTest` 125 lines, `DictatePipelineServiceScoReceiverTest` 87). Research `docs/research/2026-07-02 - recording-interruption-handling.md` (finding F-036), seeded by `2026-07-02 - feature-wiring-code-review.md` (2,314 lines). No dedicated ADR — it updates the state-architecture docs and lives under ADR-0001/0002.

**Size.** **S–M** — ~600 production insertions across 10 files, ~430 lines of tests.

**Category.** bugfix wave / missing-behaviour feature.

**Coupling.** Depends entirely on 8, 9 and 12; touches 13's `RECORDING_INTERRUPTED` status. Only the pure `InterruptionClassifiers` and `BluetoothScoManager` are reusable.

---

## 15. Persist-first pipeline, `JobExecutor` & the ordered run queue

**Summary.** Every dictation or reprocess run becomes a persisted session row with an audit chain, executed through a single-slot job registry, with a FIFO run queue so a second recording can start while the first is still processing.

**What it does / value.** Recording, transcription, auto-format, queued prompts and history re-runs all persist as `sessions` → `transcriptions` → `processing_steps` → `text_insertions` rows **before** the network call, so process death never loses text; `findPendingInsertion` re-surfaces uninserted output the next time the keyboard opens. History entries can be re-processed as real child sessions (`origin = HISTORY_REPROCESS` / `POST_PROCESSING`) running through the *same* executor as a keyboard run — one cancel path, one busy state, one notification slot. The run queue (ADR-0009) lets the user tap record again while a pipeline runs: `PipelineUiState.Preparing/Running` carry `queued: PersistentList<QueuedRun>`, terminal reducer arms chain-start the next run, and results flush as ordered pending parts instead of being dropped. ADR-0011 adds a service-side headless completion fallback so a run that finishes while no IME surface exists still lands somewhere recoverable ("Tap to paste"). The `d066a610` hardening wave fixed the routing correctness: history regenerate/post-process now go through `JobExecutor` rather than a raw thread, `AUTO_FORMAT` regeneration routes through `AutoFormattingService`, regenerate rebuilds the prompt via `PromptService` instead of double-wrapping, and a post-process session is created only *after* a prompt is chosen (no more orphan rows on dialog-cancel).

**Scope & key components.** `core/`: `PipelineOrchestrator.kt` (2,101), `JobExecutor.kt` (550), `ActiveJobRegistry.kt`, `ActiveJobRegistryObserver.kt`, `JobState.kt`, `CancellationToken.kt`, `SessionManager.kt` (812), `SessionTracker.kt`, `RecordingRepository.kt`, `ImePipelineConfigResolver.kt`, `PipelineRunnerSubsystemAdapter.kt`, `PipelineNotificationCoordinator.kt`, `PipelineTerminalDispatchGuard.kt`, `RegenerationPromptFactory.kt`, `AutoFormattingService.kt`, `ResendableSessionPolicy.kt`; `state/modules/{PipelineModule,PendingSessionsModule}.kt`, `state/PipelineOrphanCleaner.kt`; `sessions` columns `status`, `origin`, `queued_prompt_ids`, `last_error_type/message`, `final_output_text`, `input_text`, `inserted_at`, `audio_file_paths`; `processing_steps` columns `chain_index`, `version`, `is_current`, `source_session_id`, `previous_step_id`; `DurationHealingJob`/`Scheduler`.

**Key commits / plans / ADRs.** `79852ef0` (52 files, +7,486 / −2,236), run queue `7f484286`/`c1bfceb6`/`46383b16`/`28da773c`/`169f55d9`/`4a0c164f` (40 files, +3,192 / −315), `d066a610` (13 files, +937 / −318). Plan `docs/plans/dictate-reprocess-refactor.md` (2,765 lines) + `.chunks.json` + `.state.md`; research `docs/research/2026-07-02 - concurrent-recording-deferred-insertion.md` (855 lines), `- history-reprocess-hardening.md`, `- feature-wiring-code-review.md`. ADRs **0009**, **0011**.

**Size.** **XL** — ~+11,600 / −2,900 across the three waves.

**Category.** architecture/refactor with an embedded bugfix wave.

**Coupling.** Deeply fork-tied: `PipelineModule`'s FSM, `ModuleServices.sessionRepo`, the effect/action cascade and `DictatePipelineService` are all fork inventions. The *concepts* (persist-first audit rows, single-slot job registry) port; the code does not without clusters 8/9. Everything in 16–21 sits on it.

---

## 16. Consolidated conversation post-processing

**Summary.** Auto-format rules, all queued prompts and the ambiguity task collapse into **one** consolidated user message answered with a provider-native structured `{message, output}`, persisted as a replayable multi-turn conversation.

**What it does / value.** Previously each queued prompt was a separate AI round-trip chained onto the previous one's output, so text degraded through N successive rewrites and cost N calls. Now one call does all of it, and the model sees the whole instruction set at once. The response carries an explanation (`message`) alongside the text, which History displays and the review panel (cluster 17) renders — the user finally learns *why* the AI changed something. The conversation is persisted, so a later refinement turn or a regenerate replays the exact earlier system prompt and user messages byte-faithfully (`regenerateConversationTurn` bumps `version` at the same `chain_index`; `appendConversationTurn` adds a new one). `hasWork(inputs) == false` short-circuits a plain transcription to the bare transcript, so users who don't use prompts pay no extra call.

**Scope & key components.** `ai/conversation/`: `ConversationTurnBuilder.kt` (`<instruction index=N>` + `<transcript>` guardrail), `ConversationReconstructor.kt`, `StructuredResponse.kt`, `StructuredResponseCodec.kt` (285 — single wire authority), `PostProcessingInputs.kt`, `ConversationMessage.kt`. `ai/runner/`: `CompletionRunner.converse(ConversationRequest)`, OpenAI `json_schema`, Anthropic forced `emit_result` tool, `StructuredOutputGuards.kt`, `AIProvider.allowsStructuredOutputTextFallback` (true only for CUSTOM/OpenRouter/Groq — heterogeneous multi-model endpoints). DB: new table **`conversation_messages`** (`session_id, turn_index, seq, role, content, step_id, created_at`, unique `(session_id, seq)`), `MessageRole`, `ConversationMessageDao`; `processing_steps` gains `assistant_message` + `response_format` (`ResponseFormatKind {JSON_SCHEMA, TOOL_USE, TEXT_FALLBACK}`) and a `step_type` CHECK retrofit including `CONVERSATION_TURN` — **migration v7→v8**.

**Key commits / plans / ADRs.** `afbad682` … `9547cbc5` (43 files, +3,781 / −448); hardening `57a8f444` (replay corruption after regenerate/ERROR turns), `c09efb55` (truncated structured responses), `a959d6ef` (Groq text fallback), `a822ba9d` (resume fidelity + version-safe append). Plan `tmp/plan-paket1-konversations-fundament.md`. ADR **0012**.

**Size.** **L** — +3,781 / −448.

**Category.** architecture/refactor with direct user-facing payoff.

**Coupling.** The `ai/conversation/` + `ai/runner/` layer is Android-free and **portable as-is**. The DB half needs cluster 15's audit schema first; the wiring in `PipelineOrchestrator` is fork-shaped.

---

## 17. Ambiguity modes & the in-keyboard review panel

**Summary.** A tri-state pref lets the model flag ambiguous requests; the result is held in a review panel inside the keyboard that the user can insert, discard, or **refine by speaking a follow-up**.

**What it does / value.** `AmbiguityMode` is `ALWAYS_INSERT` (default, old behaviour), `AUTO` (a turn always runs; a `needsClarification` verdict decides insert-vs-review) or `ALWAYS_REVIEW`. When held, the prompt grid is replaced by a panel showing the produced text, the model's explanation, and Insert / Re-dictate / Discard. "Re-dictate" starts a transcription-only carrier recording (`origin = REVIEW_REFINEMENT`), then enqueues a `ConversationContinuation` job appending a follow-up turn (`<user-reply>` wrapper) to the reviewed session, updating the panel in place through a **non-terminal** `onReviewTurnCompleted` callback — a conversational loop entirely inside the keyboard, never leaving the host app. A crash-resilience side effect: `appendConversationTurn` now persists `sessions.final_output_text` uniformly, so any uninserted completed turn survives process death, and losing the IME surface converts held text into a pending part rather than dropping it.

**Scope & key components.** `preferences/AmbiguityMode.kt` + pref `net.devemperor.dictate.ambiguity_mode`; `ai/conversation/ReviewDecision.kt` (pure `decide(mode, needsClarification, message)`), `PostProcessingReview.kt`, `StructuredResponse.needsClarification` (wire-only, never persisted, never replayed); `state/modules/ReviewPanelModule.kt` (196, injected clock), `ReviewPanelState`, `PipelineDone(heldForReview = true)`; `state/render/ReviewPanelRenderer.kt`, layout mode `KEYBOARD_REVIEW_PANEL`, `review_panel_cl` container; `forceTurn` threaded IME → `FreshConfig` → `JobRequest.TranscriptionPipeline` → `PipelineConfig`.

**Key commits / plans / ADRs.** `41b542bd` … `06280fc8` (64 files, +2,114 / −125); hardening `9946fd8f` … `cfffbcc5` (32 files, +1,032 / −166); `ed3634ba` + `cc2f71f2` in `f83ddcd9` gave the panel its own height (it had been collapsing with the emptied grid — i.e. invisible). Plan `tmp/plan-paket2-review-modi.md`. ADR **0013**.

**Size.** **L** — +3,146 / −291 combined.

**Category.** user-facing feature with a substantial bugfix wave attached.

**Coupling.** Deeply fork-tied — a `DictateModule` axis with a teardown cascade, a `LayoutMode` in the MotionLayout catalog, depending on 16's structured verdict and 15's run queue. Only `ReviewDecision`/`PostProcessingReview` are portable pure code.

---

## 18. In-keyboard history panel

**Summary.** A Paging3-backed history list inside the keyboard, pending-first, with per-row insert, drag-to-resize and an in-panel detail view.

**What it does / value.** Re-insert any earlier result without leaving the text field; a run that completed while the keyboard was hidden appears on top (pending-first ordering) and one tap commits it. Long-press on the same edit-bar button still opens the full-screen `HistoryActivity` — fast path on the primary tap, heavyweight screen on the deliberate gesture. The panel is drag-resizable and the height persists, clamped to live drag bounds. Short-press on a row opens a detail surface with the full text (rows were previously truncated with no way to read the rest). The list auto-refreshes while open via Room `InvalidationTracker`, and refinement carrier sessions are hidden.

**Scope & key components.** `history/KeyboardHistoryPager.kt` (a Pager with a **caller-owned** `CoroutineScope` because an IME is no `ViewModelStoreOwner`; `PAGE_SIZE = 40`), `KeyboardHistoryController.kt` (owns the scope, centralises the three cancel points), `KeyboardHistoryAdapter.kt`, `SessionRowPredicates.kt` (parity-tested against the SQL ORDER BY); `SessionDao.pagedHistoryPanel()`; `state/modules/HistoryPanelModule.kt` (auto-close cascade on IME teardown / recording start / review panel opening); `state/render/HistoryPanelRenderer.kt`, `LayoutModeId.KEYBOARD_HISTORY_PANEL`, `history_panel_cl`, `item_keyboard_history.xml`, `dictate_drag_handle.xml`; `keyboard/VerticalDragResizeHandler.kt` (reusable resize primitive); pref `HistoryPanelHeightDp`. DB: **migration v8→v9** widens the `origin` CHECK by `REVIEW_REFINEMENT` and retrofits the `sessions.type` Double-Enum CHECK.

**Key commits / plans / ADRs.** `b30dfd7d` … `f90fdba6` (48 files, +3,103 / −28); merge `f83ddcd9` blocks A/B (22 files, +775 / −20); follow-ups `ac029902` (lazy render + spinner), `b864beeb`, `fede2c5a`. Plans `tmp/plan-paket3-history-panel.md` (1,111 lines), `tmp/plan-history-drag-and-pill-fix.md` (750 lines). ADR **0014**.

**Size.** **L** — +3,878 / −48.

**Category.** user-facing feature.

**Coupling.** Fork-tied via the state axis and LayoutCatalog. The genuinely reusable pieces are `KeyboardHistoryPager` + `KeyboardHistoryController` (the "Paging3 inside an IME with a caller-owned scope" pattern, declared reusable in ADR-0014) and `VerticalDragResizeHandler`. Hard data dependency on cluster 15's schema.

---

## 19. Full-screen history — pagination, UI overhaul & transcription rerun

**Summary.** `HistoryActivity` moved to Paging3 + debounced search + a Kotlin ViewModel; `HistoryDetailActivity` got a rewritten step list with tap-to-expand, per-step copy, multi-segment audio playback, version chips and a transcription re-run.

**What it does / value.** History no longer loads every session synchronously: `Pager(pageSize = 40)`, filter chips applied immediately, search debounced and wildcard-escaped (`LikeEscape`), registry ticks coalesced, deletes on an IO context with self-refresh through Room invalidation — it scales to thousands of rows. A 60-day retention sweep removes `CANCELLED` rows. On the detail screen each pipeline step is tappable to expand full input/output, has a per-step copy button, shows the session error inline, and keeps its expansion across rebinding. Multi-segment recordings play back as a playlist instead of only the first file. **Transcription re-run** re-transcribes an old recording with the current model, with version chips to switch between transcriptions and a staleness warning when downstream steps came from an older one. The wave also introduced the systemic icon/text colour layer (ADR-0010) so history icons follow light/dark correctly, plus split empty-states, delete guards and de/es/pt locale backfill.

**Scope & key components.** `history/HistoryActivity.kt` (224, converted from Java), `HistoryAdapter.kt` (174, converted), `HistoryViewModel.kt` (257), `HistoryRow.kt`, `database/dao/LikeEscape.kt`, `SessionDao.pagedHistory/deleteCancelledOlderThan/findOrphanedTerminalAudio`; `HistoryDetailActivity.java` (926), `PipelineStepAdapter.kt` (596, rewritten from a 332-line Java adapter), `StepExpansionState.kt`, `SessionErrorFormatter.kt`, `TranscriptionStaleness.kt`, `HistoryAudioPlayer.kt`, `HistoryAudioResolver.kt`; `res/values/attrs.xml` + `themes.xml` icon-tint attrs. Dep: Paging 3.3.6.

**Key commits / research / ADRs.** `567a5a2b` (20 files, +1,263 / −458), `7f66f66f` (49 files, +3,712 / −419). Research `docs/research/2026-07-02 - history-pagination-and-scale.md`, `- history-ui-overhaul.md`. ADR **0010** was born here.

**Size.** **L** — +4,975 / −877.

**Category.** performance (pagination) + user-facing feature / UX polish (overhaul).

**Coupling.** **The most portable cluster in the history area.** These are ordinary Activities plus a ViewModel; they touch the state store only through `ActiveJobRegistry`/`JobState` refresh ticks and dispatch re-runs through `JobExecutor`. Given cluster 15's schema they transplant with modest rewiring. Upstream 3.2 has no history screen at all, so this is net-new UI there.

---

## 20. Prompt-queue transport & the reprocess queue editor

**Summary.** The prompt queue stopped being a list of entity IDs and became content-carrying slots, unlocking a bottom-sheet editor for assembling the exact prompt chain of a history re-run.

**What it does / value.** Before, a history reprocess could only re-apply one saved prompt. Now a bottom sheet lets the user add saved prompts, type free-text prompts, reorder by drag and remove entries, then run the whole ordered chain against the session. Because the confirmed queue carries the prompt **text**, editing or deleting the saved prompt between confirm and execution no longer silently changes or drops what runs. Three explicit slot shapes exist with an unconstructible fourth: ID-only (legacy keyboard/live queue, resolved from the DB at execution, skipped if deleted), content+entityId (editor-confirmed saved prompt), and free-text (entityId null); `(null, null)` is blocked by an `init` guard. Post-merge hardening distinguished "empty queue" from "unset queue", made resolution happen once, and added resume/cancel parity.

**Scope & key components.** `core/PromptQueueSlot.kt` (76), `core/PromptQueueManager.kt` (140), pref `QueuedPromptIds` + `sessions.queued_prompt_ids`; `history/ReprocessQueueEditorModel.kt` (157, UI-free, Bundle-friendly `Snapshot`), `ReprocessQueueEditorBottomSheet.kt` (252), `PromptChooserBottomSheet.java`, `PromptChooserAdapter.java`; layouts `dialog_reprocess_queue_editor.xml`, `item_reprocess_queue_entry.xml`, `dialog_prompt_chooser.xml`; wiring in `ImePipelineConfigResolver`, `JobExecutor`, `PipelineOrchestrator.resolveQueueSlot`, `HistoryDetailActivity.startHistoryReprocess/onReprocessQueueConfirmed`.

**Key commits / research.** `6d7f6531` (24 files, +1,518 / −102), hardening `eb5a9a3c`, `00dc182c`, staging fix `83fc6d7e` (merge `4df8820f`, 17 files / +607 / −69). Research `docs/research/2026-07-02 - reprocess-queue-editor.md` (the transport decision lives in §2.1; no dedicated ADR).

**Size.** **M** — +1,518 / −102 core, ~+1,700 with hardening.

**Category.** user-facing feature.

**Coupling.** Moderate. `PromptQueueSlot` and `ReprocessQueueEditorModel` are pure Kotlin and portable; the sheet is a plain `BottomSheetDialogFragment`. It needs cluster 15's job/config transport underneath.

---

## 21. Prompt pill types & prompts-overview redesign

**Summary.** The `[bracketed]` string convention for literal text pills was replaced by a typed `prompts.type` column, pill press behaviour became a unit-tested pure policy, and the prompts screen was redesigned into draggable cards.

**What it does / value.** `PromptType { PROMPT, TEXT }`: a TEXT pill always inserts its snippet 1:1 — pipeline-free, in every keyboard state, never greyed out, never queued, and it can no longer leak into the AI prompt. A PROMPT pill runs the AI (standalone when idle, queue-toggle for selection prompts during recording). Previously the kind was `prompt.startsWith("[") && endsWith("]")` re-parsed in scattered places, and several code paths forgot it. Separately, greyed pills used `setEnabled(false)`, which swallows all MotionEvents — so long-press could never fire; the adapter now keeps pills enabled, renders "disabled" via alpha only, and routes press through the pure `PromptPillPressPolicy.decide`, which means a text-only pill can be applied by long-press even while a recording or pipeline is busy. The editor gained an explicit Prompt/Text toggle, JSON export moved to `version: 2` with a `type` field (v1 files import through the shared classifier), and import clamps selection/auto-apply flags for TEXT pills. The overview screen became a card layout with drag-and-drop reordering, per-item duplicate, an info header, and export/import in an overflow menu.

**Scope & key components.** `database/entity/PromptType.kt`, `PromptEntity.type`, **migration v10→v11** (table recreate + CHECK + classification: a fully-bracketed trimmed prompt becomes TEXT with brackets stripped), `PromptDao.getAutoApplyIds()` filtering `type <> 'TEXT'`; `ai/prompt/PromptTypeClassifier.kt` (mirrored by the SQL migration and by JSON import, pinned by `PromptTypeClassifierTest` + `MigrationTo11Test`); `PromptService.isStaticResponse/extractStaticResponse` **deleted**; `rewording/{PromptPillPressPolicy,PromptsKeyboardAdapter,PromptEditActivity,PromptImportExport,PromptsOverviewActivity,PromptsOverviewAdapter,PromptListMutations,PromptReorderCallback,PromptsInfoHeaderAdapter}`.

**Key commits / plans / ADRs.** `9915ffc0` (33 files, +2,163 / −127), `34cbf701` (4 files, +180 / −5), `9a98147b` (16 files, +728 / −257), plus block C of `f83ddcd9` (a STATIC_PROMPT pill must stay local under Windows auto-send). Plans `tmp/plan-pill-typen.md`, `tmp/plan-history-drag-and-pill-fix.md` block C; `tmp/prompts-redesign/*.png` (screenshot-driven design, no markdown plan). ADR **0024**.

**Size.** **M–L** — +3,071 / −389 combined.

**Category.** architecture/refactor (typed column replacing a string convention) + user-facing feature + UX polish.

**Coupling.** **The best port candidate of the fork's AI-UX work.** Upstream 3.2 already has a prompts table, an overview screen and a keyboard pill row, so the `type` column, `PromptTypeClassifier`, `PromptPillPressPolicy`, `PromptListMutations`, `PromptReorderCallback` and the redesigned layouts transplant nearly verbatim. The only fork couplings — the greyed-out driver, the `InsertionService` path, the `WindowsAutoSend` divert gate — are shallow.

---

## 22. Text insertion & editing semantics (single write owner)

**Summary.** All text writes funnel through one `InsertionService` with grapheme-correct delete, selection-aware semantics, and an Enter key whose icon and action read from the same state.

**What it does / value.** Upstream wrote to the `InputConnection` from many places with naive `deleteSurroundingText(1, 0)`, which splits emoji and combining sequences, and ignored an active selection. The fork consolidated every write behind `InsertionService` with `GraphemeTextOps` for cluster-correct deletion, selection-aware delete (a selection is replaced, not appended to), `ExtractedText.startOffset` honoured by `BackspaceSwipeHandler` (swipe-select was off by the offset in long fields), a `PendingPartsFlusher` for ordered deferred insertion, and a `SlowOutputAnimator` for the "output speed" pref. The Enter key had a structural drift: the icon switched per `EditorInfo.imeOptions` on the legacy path while the click hardcoded `commitText("\n", 1)` — so a Search field showed a search icon and inserted a newline. A `HostEditorState` axis on `KeyboardInputModule` made both read from one source and removed the parallel legacy paths (`performEnterAction`, `updateEnterButtonIcon`, direct `scheduleAutoEnter`, the QWERTZ callback indirection). This single-write-owner shape is the prerequisite that made cluster 25 (PC routing) possible at all.

**Scope & key components.** `state/insertion/`: `InsertionService.kt`, `Insertion.kt` (`ControlOp`, `EditAction`, `InsertionRequest`), `GraphemeTextOps.kt`, `InsertionCollaborators.kt`, `LocalImeSink.kt`, `PendingPartsFlusher.kt`, `SlowOutputAnimator.kt` (plus `KeyboardAction*` from cluster 25); `keyboard/BackspaceSwipeHandler.kt`, `EnterOverlayHandler.kt`; `state/layout/EnterRoleResolver.kt`; `KeyboardInputModule` migrated `S = Unit → KeyboardInputState`.

**Key commits / plans / ADRs.** `d2a78357` (F-018/F-020/F-021/F-023; 19 files, +1,237 / −260), plan `docs/plans/2026-05-23 - dictate-enter-button-host-action/` (5 chunks, ends in a zero-grep legacy cleanup), plan `2026-05-21 - dictate-indirection-cleanup/`. ADRs 0001/0004/0008 as context; ADR **0026** later builds on it.

**Size.** **M**.

**Category.** architecture/refactor + bugfix.

**Coupling.** `GraphemeTextOps` is pure and portable. `InsertionService` presumes the fork's action model but the *idea* (one write owner) is the single most valuable structural lesson here, and cluster 25 could not exist without it.

---

## 23. Accessibility screen context

**Summary.** An opt-in `AccessibilityService` reads the underlying app's view tree and passes a pruned, redacted description to the model as a prompt data block.

**What it does / value.** The IME's `InputConnection` can only see the field being typed into, so the model cannot resolve references to what is on screen ("send it to Anna" → which Anna?). The Assist API is reserved for the device assistant and Content Capture is system-privileged, leaving `AccessibilityService` as the only mechanism available to a keyboard. Because that API is invasive, the feature is off by default, requires the user to flip a system setting, and redacts at the point of reading: `AccessibilityContextReader` never copies text out of a node that `isPassword` or carries password/email markers, `FLAG_SECURE` windows are withheld by the platform, and the tree is pruned to interactive/labelled nodes (raw dump ≈ 5–10k tokens, pruned ≈ 1–2k). The read happens at the **send tap** inside `captureFreshConfigSnapshot`, not at pipeline time on a background executor seconds later — by then the user may have switched apps and the tree would describe the wrong screen. It never attaches in PC send-mode (the phone screen is not what the user is looking at). Distribution being sideload-only, the binding practical constraint is Android 13+ "Restricted settings", which greys out the accessibility toggle for sideloaded installs.

**Scope & key components.** `accessibility/{DictateAccessibilityService,AccessibilityContextReader,A11yEnablementGate,UiNodeSnapshot,ViewTreeSerializer}.kt`; the serialized block enters the prompt through the Android-free `ConversationTurnBuilder`, so it is persisted verbatim and replayed on regenerate.

**Key commits / plans / ADRs.** `f3f842e2` `[B1.1]` and `11e46f37` inside merge `c8d670d6` (whole merge: 68 files, +3,881 / −267). Plan `tmp/plan-a11y-widget-pcmode.md`. ADR **0021**.

**Size.** **M**.

**Category.** user-facing feature.

**Coupling.** The reader/serializer/gate are self-contained Android classes; the only coupling is the prompt seam in `ConversationTurnBuilder` and the PC-mode suppression check. **Conceptually portable**, though it needs a prompt-assembly seam to land in.

---

## 24. Desktop companion & wire protocol (Windows dispatch)

**Summary.** A Compose-Desktop Windows companion, a pure-JVM shared protocol module, and the Android auto-send path that types a finished dictation into the active Windows window over the user's Tailscale tailnet.

**What it does / value.** The user dictates on the phone and the finished text appears at the caret on the PC (clipboard set, then `Ctrl+V` via JNA `SendInput`). Delivery is confirmed by the HTTP 200 itself — there is no ACK protocol, and a timeout is classified `Unreachable`, never `Delivered`; every non-delivered outcome (PC off, unauthorized, insertion failed, process death mid-dispatch) falls back to the existing "Tap to paste" pending part, so text is never lost. Pairing is a QR code (or manual URI) burning a 120-second one-time token for a 256-bit device secret; the server stores only its SHA-256 and compares constant-time. On top of dispatch, the phone lazily mirrors its Room history to the PC as a searchable archive — cursor-paged, idempotent upserts, phone authoritative. Bind-address hardening followed: the companion used to listen on `0.0.0.0` with no warning while advertising an unrelated address in the QR, so a Tailscale user got a QR pointing at an address nobody listened on and pairing failed silently. It now shows a catalogue of every local IPv4 with its kind (Tailscale `100.64.0.0/10`, LAN, loopback, Docker bridge), defaults to Tailscale-only when a tailnet address exists, never silently widens, validates the selection so a typo can no longer brick start-up, and binds several Ktor connectors at once.

**Scope & key components.** `settings.gradle` gains `:shared` (`kotlin("jvm")`, jvmTarget 1.8, Android/Ktor/coroutine-free, enforced by `SharedPurityTest`) and `:companion` (Compose Desktop, JVM 17, SQLDelight with `verifyMigrations`, jpackage MSI+Deb, frozen `upgradeUuid`). `:shared`: `protocol/Dtos.kt` (`PairRequest/Response`, `DispatchRequest/Response`, `SyncCursor`, `SessionUpsert`, `SyncRequest/Response`, `HealthResponse`), `ProtocolCodec` (single encode/decode door, Konform-validates both directions), `ProtocolVersion.CURRENT = 1`, `Endpoints.kt`, `client/DispatchClient`, `transport/OkHttpDispatchTransport` (connect 3 s / read 8 s, no retry), `auth/{PairingUri,Secrets,AuthHeaders}`, `sync/{SyncClient,Cursor,SyncSource}`. `:companion`: `CompanionContainer`, `server/CompanionServer` (Ktor CIO) + `routes/{Pair,Dispatch,Sync,Health}Routes`, `domain/{PairingService,AuthService,DispatchService,SyncService,HealthService,CompanionSettings}`, ports `TextInserter`/`AutostartManager`/`ClipboardPort`/`HistoryRepository`, `platform/windows/{Win32TextInserter,Win32Keyboard,WinRegistryAutostart,AwtClipboard}`, `PlatformModule` OS-switch with Noop fallbacks so it builds and tests green on Linux, Compose UI + `QrCodes.kt`, `cli/PairCli.kt`; SQLDelight tables `devices`, `received_texts`, `settings`; bind-address domain `net/{AddressCatalog,AddressKind,BindSelection,ResolvedBinding,Ipv4}`. Android: `windows/{WindowsDispatchCoordinator,WindowsDispatchService,WindowsAutoSend,DispatchOutcomeMapper,AndroidSyncSource,SessionEntityMapper}`, `state/modules/WindowsDispatchModule.kt`, `settings/WindowsPairingActivity.java`; prefs `WindowsAutoSendEnabled`, `WindowsTargetUrl`, `WindowsDeviceSecret`, `WindowsDeviceId`, `WindowsServerName`. DB: **v10** adds `text_insertions.insertion_method = WINDOWS_DISPATCH` + `target_device_id` and the cursor DAO queries.

**Key commits / plans / ADRs.** 18 commits `15360b1a` (`[wd-0]`) → `86260bda` (`[wd-16]`) plus review fixes `4f484f54` (pairing-token TOCTOU race), `1a4d81c3` (`ErrorEnvelope` redaction), `2190b9f8`, `9abeba47`; bind-address merge `75f5c190` (25 files, +1,549 / −124) with `f9979f1f` (single-instance guard), `3d8e0c4c` (boot-failure UI instead of endless spinner), `d66e8245`, `e5a28ce1` (`java.sql` in the jlink runtime), `f9f02cd9` (Tailscale IP not AD hostname in the QR). Plans `tmp/plan-windows-dispatch.md` + `-1-shared` / `-2-companion` / `-3-android` (3,496 lines total, German), `tmp/rescued-plans/plan-bind-address.md`. ADRs **0015–0020**, **0023**. Architecture `docs/architecture/windows-dispatch/README.md`; runbook `docs/runbooks/companion-windows-release.md`.

**Size.** **XL** — `15360b1a^..86260bda` = 193 files, +15,143 / −56 (`shared` 30 / +3,010 · `companion` 83 / +5,564 · `app` 62 / +4,502 · `docs` 13 / +1,984), plus +1,549 for bind-address.

**Category.** user-facing feature with a large architecture component (two new Gradle modules).

**Coupling.** **The clean seam is the module boundary.** `:shared` and `:companion` have zero dependencies on the fork's Android architecture and port near-verbatim to any codebase that can host a second Gradle module. The Android third does not: it hangs off the state store, the run queue and its `PipelineDone`, the headless-completion pending-part fallback, the info-bar (`WINDOWS_UNREACHABLE`/`WINDOWS_UNAUTHORIZED` items), the review panel, the history panel's per-row "Send to Windows" seam and the Room history DB. Documented gaps on `main`: multi-PC targets are data-modelled but not built, and sync propagates no deletions.

---

## 25. Keyboard-action routing engine (PC input protocol)

**Summary.** In PC-mode *every* keyboard action — cursor, backspace, enter, clipboard, text pills, emoji — routes exclusively to the PC, turning the phone keyboard into a remote control for the Windows caret.

**What it does / value.** Before this, only terminal dictation text went to the PC; every other keystroke still wrote into the invisible Android host field, so the user was editing a field they could not see. Now cursor corrections, backspace/undo of just-inserted text and static text pills all land on the PC. The wire carries **semantic** commands, never raw VK codes, so the mapping stays layout-agnostic on the phone and the injection surface stays small. Failures are visible immediately rather than buffered: a failed batch is discarded with one `WINDOWS_INPUT_FAILED` notice plus a 3-second circuit breaker and is never retried (dictation text keeps its own pending-part fallback and does not flow through the router). The chords themselves are user-configurable on the PC with a key-capture UI and a reset. Capability discovery is explicit: `HealthResponse.supportsInputCommands` defaults false and a 404 maps to "update the companion".

**Scope & key components.** `:shared` (additive, no protocol bump): `Endpoints.INPUT = "/v1/input"`, `MAX_INPUT_BATCH = 20`, `MAX_INPUT_REPEAT = 50`, DTOs `InputCommandRequest/Wire/Response` with `InputCommandKindWire` (`BACKSPACE`, `CURSOR_LEFT/RIGHT`, `CURSOR_WORD_SELECT_BACK/FORWARD`, `SELECT_ALL`, `CUT/COPY/PASTE/UNDO/REDO`, `TYPE_TEXT`). `:companion`: `server/routes/InputRoutes.kt`, `domain/InputCommandService.kt`, `domain/model/{InputCommand,KeyChord,DefaultChords}.kt`, `platform/windows/Win32InputPerformer.kt`, new DB table `key_command_chords` (Double-Enum CHECK) seeded by migration `1.sqm` from `DefaultChords` (the one home of VK literals, pinned by `ChordMigrationSeedTest`), `ui/settings/{ChordSettingsSection,ChordSettingsViewModel,ChordLabels,KeyCapture}.kt`. `:app`: `state/insertion/{KeyboardAction,KeyboardActionRouter,KeyboardActionDispatcher}.kt` — the router sits in front of `InsertionService` and picks exactly one sink per action; `windows/{PcInputSink,PcInputCoordinator,PcInputCommandMapper,PcInputOutcome}.kt` (same single-thread executor as dictation dispatch for total order, 500 ms linger-only-when-busy coalescing). Backspace-swipe word selection maps to Ctrl+Shift+←/→; selection-requiring prompt pills grey out in PC-mode; a purple `dictate_pc_mode` 4 dp stripe renders as the keyboard root's *foreground* so it shows over every panel.

**Key commits / plans / ADRs.** Merge `0a3c622b` (13 commits `c38df888` … `d597820c`). Plan `tmp/plan-keyboard-action-engine.md` (490 lines, decision log D1–D6). ADRs **0025**, **0026**; §4b of the windows-dispatch README.

**Size.** **L** — 85 files, +3,960 / −369 (`app` 40 / +1,855 · `companion` 33 / +1,490 · `shared` 8 / +324 · `docs` 4 / +291).

**Category.** user-facing feature on top of an architecture refactor (one router replacing ~15 scattered `if (pcMode)` branches).

**Coupling.** The `:shared` + `:companion` halves are self-contained and portable. The `:app` half is bolted onto the fork's `InsertionService`/`ControlOp`/`EditAction` type model (cluster 22) and the injected IC-collaborator lambdas. **Conceptually portable** — "one router in front of one write owner" is a clean idea — but the code presumes the single-write-owner refactor as a prerequisite.

---

## 26. PC-Dictation Activity (third render host)

**Summary.** A full-screen app screen that reuses the keyboard grid and history as a standalone PC-dictation remote, with a transient PC-only terminal mode.

**What it does / value.** The IME is only usable while some app has a focused text field, which makes PC dictation awkward — you need a throwaway field on the phone just to talk. This adds a normal Activity openable from the launcher, an app shortcut, or a long-press on the keyboard's PC button; it renders the *same* keyboard grid (record button, gestures, edit bar) plus session history, and everything it produces goes to the paired PC. Because there is no local `InputConnection`, "commit into the host field" and "Tap to paste" do not apply, so `pcOnly` is a distinct transient mode-state — not the persistent `WindowsAutoSendEnabled` toggle — with its own gate predicate and error policy. It supports headless recording, history re-send, retry, and step rows for pipeline errors.

**Scope & key components.** `core/PcDictationActivity.kt` (a third `RenderBackend` via `ImeViewBackend` + `RealMotionSurface` + a `LogicalButtonId → View` map, attached to the service-owned `KeyboardLayoutManager`), `core/PcDictationLaunchPolicy.kt`, `core/StartPcDictationActivity.kt`, the `pcOnly` install mode on `SpecialTouchHandlerInstaller`, binder foreground-host precedence slots for the `PipelineConfigResolver` and keyboard actions (the Activity must register its own resolver or `DefaultPipelineConfigResolver.resolveFresh` throws), PC-only divert wired into both pipeline terminal seams, launcher-alias + shortcut manifest entries.

**Key commits / plans / ADRs.** Merge `48d5c967` (10 commits `e6c54614` … `ebbf0678`) and the follow-up audit wave `35194340` (`497628a0` P1 bugs: split-screen leak, silent send no-op; `6196e40d` ENTER/history UX/banner/auto-enter; `d3f4ccd0` resend + empty-queue leak; `329cfc91` PC edit-bar actions; `03dd54d9` pipeline-error visibility; `46fb28f6` text-pill row). ADR **0027** (with two append-only Decision-History waves).

**Size.** **L** — `48d5c967` = 29 files, +1,471 / −20; `35194340` = 20 files, +782 / −103. Combined ≈ +2,253 / −123.

**Category.** user-facing feature + bugfix wave (the second merge is 16 audit findings).

**Coupling.** **Deepest fork coupling of any cluster.** It is a direct consequence of the multi-render-host abstraction (ADR-0004/0008 — `KeyboardLayoutManager` fanning `DictateUiState` to a *list* of backends), plus service-owned state, the run queue, headless completion, the history panel and the MotionLayout scene catalogue. Without that abstraction the screen would have to be rebuilt from scratch.

---

## 27. Latency & render-performance waves

**Summary.** Two measured waves that removed a 2.5-second record-start stall, a ~1.3-second dead-keyboard window on cold start, and a ~190 ms rotation rebuild.

**What it does / value.** **Record latency:** with `UseBluetoothMic` on but no headset paired, starting a recording stalled ~2.5 s waiting on SCO. An availability gate in `AudioModule.kt` cut the median from **2,518 ms to 24 ms** (emulator-verified). The same wave added a cold-start tap buffer so a record tap in the pre-bind window is queued instead of dropped with a toast — which only worked after `fa16003b` hoisted the `pipelineBinder`-null guard to the head of `startRecording()`, since a tap arriving between `onCreateInputView` and `onServiceConnected` NPE'd on `promptQueueManager` *before* the buffer could set `pendingRecordOnBind`, losing the tap and crashing the QWERTZ record button. **Render latency:** after a process restart the IME window appears ~1.3 s before the pipeline-service binder lands, and the whole render path early-returned on `pipelineBinder == null`, so the keyboard showed dead XML defaults with no click listeners for the entire blind window; `ImeViewBackend`'s `services: ModuleServices` became a `() -> ModuleServices?` provider resolved at click time (null pre-bind = silent no-op) and attachment split into a bind-free `buildImeViewRenderers` and a binder-required `attachImeViewBackendToService`. Rotation used to tear down and rebuild the entire tree (detach + ~40 `findViewById` + controller reconstruction + re-attach + first render + GL draw, ~190–210 ms); `InputViewRetentionPolicy.canReuseInputView` is a pure function over `Configuration.diff` with a geometry allowlist (orientation, screen-size, screen-layout, keyboard-hidden, navigation, `CONFIG_WINDOW_CONFIGURATION`) — night-mode, locale, density, font-scale, layout-direction and any unknown bit fail safe to a rebuild. Rotation recreate went **~190 ms → ~10 ms**.

**Scope & key components.** `state/modules/AudioModule.kt` (SCO availability gate), `DictateInputMethodService.java` (`pendingRecordOnBind`, hoisted binder guard), `state/render/ImeViewBackend.kt`, `core/InputViewRetentionPolicy.kt`; tests `StartRecordingPreBindGuardTest.kt`, `InputViewRetentionPolicyTest` (283 of the 487 added lines are tests). Raw measurements in the untracked `tmp/perf/`.

**Key commits.** `f597c6c4` (7 files, +186 / −6), `fa16003b` (2 files, +93 / −11), `048fb37c` (12 files, +1,033 / −155) with `f4fc1139` (8 files, +453 / −144) and `6eb9ae25` (4 files, +487 / −0).

**Size.** **M**.

**Category.** performance.

**Coupling.** `InputViewRetentionPolicy` is a pure `Configuration.diff` predicate and portable to any IME. The pre-bind bootstrap render only makes sense with the FGS-binder architecture; the SCO gate is portable in concept.

---

## 28. Bugfix waves (F-numbered point-fixes)

**Summary.** Seven single-purpose merges closing findings from the fork's own audit passes, each landing with a red-first regression test.

**What it does / value.** These came out of `docs/research/2026-07-02 - feature-wiring-code-review.md` (2,314 lines) and the per-block audit rounds, and are worth calling out separately because they document real correctness gaps the fork found in itself: `F-000` (critical — the IME start path allocated its first audio file outside `AudioFileRepository`, an audio-loss bug), `F-001/F-003` (the staged reprocess queue never flowed end-to-end; auto-apply was not primed on the catalog path), `F-005` (the RESEND visibility axis was not seeded on cold boot), `F-029` (the resend cooldown timer lived outside `ResendModule`, so long-press latched the button), `F-092` (`POST_NOTIFICATIONS` was declared but never requested at runtime — the FGS notification silently never appeared on Android 13+), `F-018/F-020/F-021/F-023` (grapheme and selection delete semantics, `ExtractedText.startOffset`), `F-012/F-014/F-047` (a `significantSegments` API killing false partial-recovery prompts, a dead continuation path, and 16-second durations shown in history).

**Key commits.** `bc9390b7` (2 files, +150 / −9), `4df8820f` (17 files, +607 / −69), `44eb3b45` (9 files, +460 / −59), `6e3e1ac9` (12 files, +416 / −80), `d2a78357` (19 files, +1,237 / −260 — also cluster 22), `59ce0193` (7 files, +247 / −26).

**Size.** **M** in aggregate (~+3,100 / −500).

**Category.** bugfix wave.

**Coupling.** Each fix is bound to the subsystem it repairs; none port independently. Their value to a port decision is diagnostic — they map exactly where this architecture is easy to get wrong.

---

## Appendix A — Unlanded: `feature/desktop-companion-v1`

Two consecutive plan runs on the branch `feature/desktop-companion-v1` (`8083882a`, 2026-07-27) turn the passive dispatch target into a **full desktop dictation host**: its own microphone capture (`JavaSoundAudioCaptureService`, `WavWriter`, `WavConcat`), its own slim orchestrator sharing the same AI core as the phone via a new `:shared-ai` module, a Compose mini-panel driven by a global hotkey with a scoped low-level keyboard hook, commissioning without a phone, configuration moved off loose `SharedPreferences` onto `:shared` entities whose canonical `contentHash` *is* the v3 file/wire format (Room v11→v13), all secrets behind a `SecretStore` port (Android Keystore / Windows DPAPI / POSIX-0600), a multi-run pipeline, always-valid recordings (session row written when the mic opens, WAV header patched on a tick, boot recovery), Tailscale-native auth (device token **and** whois node, 401 vs 403 split, TOFU migration of legacy bearer pairings), and selective per-recipient catalog shares with an approval flow replacing token typing.

**Plans:** `docs/plans/2026-07-19 - desktop-companion-v1/` (59 commits, 6 blocks / 16 chunks) and `docs/plans/2026-07-22 - companion-hardening-v2/` (57 commits, 6 blocks / 15 chunks, 15 repair waves, 173 validated audit findings) — **both branch-only, neither exists on `main`**. ADRs **0028–0041** likewise.

**Size:** 1,250 files, +189,725 / −10,499 vs `main`. Unit-green (4,488 JVM tests, `./gradlew build` exit 0) but with ~16 manual Windows/E2E acceptance cases unsigned-off and 8 open decision items. Treat as a large, coherent, *unlanded* branch.

---

## Summary table

| # | Cluster | Category | Size | Portable to a fresh 3.2? |
|---|---|---|---|---|
| 1 | Build hygiene, tests & doc process | architecture/infra | S → XL | Build patch + E2E scripts yes; tests/ADRs describe fork architecture |
| 2 | AI abstraction layer & providers | architecture + feature | L | **Yes — highest-value portable cluster** |
| 3 | Prompt architecture (`PromptBuilder`) | architecture | S | **Yes — most extractable single piece** |
| 4 | Session persistence & audit DB | feature + persistence | XL | Schema yes; wiring was God-Class-coupled |
| 5 | QWERTZ keyboard & ergonomics modes | feature + UX polish | L | Mechanically yes; a product-level fork divergence |
| 6 | Language chip & versioned prefs | feature + infra | L | `preferences/versioned/` yes; chip UI no |
| 7 | Recording visual feedback | UX polish | M | Drawables yes; controllers no |
| 8 | Modular state store & orchestrator | architecture | XL | **No — this is the new foundation** |
| 9 | Foreground pipeline service | architecture | L | No (only meaningful with cluster 8) |
| 10 | LayoutCatalog & render backends | architecture | XL | Pattern yes; concrete catalog no |
| 11 | Widget / overlay floating mode | feature → UX polish | XL | **No — least portable feature** |
| 12 | Info-bar notice system | architecture + UX | M | Pattern yes; selector no |
| 13 | Multi-file audio repository | architecture → capability | L | `AudioFileRepository` yes (best of the May wave) |
| 14 | Interruption / audio focus / BT-SCO | bugfix wave | S–M | Only the pure classifiers |
| 15 | Persist-first pipeline & run queue | architecture + bugfix | XL | Concepts yes; code needs 8+9 |
| 16 | Conversation post-processing | architecture + feature | L | `ai/conversation/` yes; DB needs 15 |
| 17 | Ambiguity modes & review panel | feature + bugfix | L | Only `ReviewDecision` |
| 18 | In-keyboard history panel | feature | L | Pager/controller/resize-handler yes; panel no |
| 19 | Full-screen history overhaul | performance + feature | L | **Yes — most portable history work** |
| 20 | Prompt queue & reprocess editor | feature | M | Model + sheet yes; needs 15 underneath |
| 21 | Prompt pill types & overview redesign | architecture + feature | M–L | **Yes — near-verbatim onto upstream's prompts screen** |
| 22 | Text insertion & editing semantics | architecture + bugfix | M | `GraphemeTextOps` yes; the pattern is the lesson |
| 23 | Accessibility screen context | feature | M | Yes, given a prompt-assembly seam |
| 24 | Desktop companion & wire protocol | feature + architecture | XL | `:shared` + `:companion` **yes**; Android half no |
| 25 | Keyboard-action routing engine | feature + architecture | L | Shared/companion yes; app half needs cluster 22 |
| 26 | PC-Dictation Activity | feature + bugfix | L | **No — deepest fork coupling** |
| 27 | Latency & render performance | performance | M | `InputViewRetentionPolicy` yes; rest needs the FGS |
| 28 | Bugfix waves (F-findings) | bugfix wave | M | No — diagnostic value only |
| A | *Unlanded* desktop dictation host | architecture + feature | XL | Branch-only, never merged |
