# History UI Overhaul

---
date: 2026-07-02
author: Lukas + Claude (Fable lead + Opus analysis workers)
type: Spec
status: Accepted
context: Visual + structural overhaul of the history list and detail screens — per-step copy, tap-to-expand, systemic color fix, button audit, multi-segment-aware playback, and a new transcription re-run job (R1–R6 + seven adjacent catalog findings).
related-plan: n/a (plan-free; seeded by user requirements R1–R6 and 2026-07-02 - feature-wiring-code-review.md)
related-adrs: ADR-0003, ADR-0007, ADR-0009, ADR-0010
---

The history screens work but look unfinished: several step-action buttons render black (or white-on-white) because their vector drawables carry baked tint literals, step text is hard-capped at five lines, intermediate step outputs cannot be copied, playback is a one-shot on segment 1 only, and there is no way to re-run the transcription. This spec defines the target design: one systemic theme-attr tint convention (ADR-0010), a rewritten step adapter with stable keys + tap-to-expand + per-step copy, multi-segment-aware audio availability/playback/delete, and a `JobRequest.TranscriptionRerun` job that produces a new transcription *version* through the existing (DB-present, UI-dead) transcription version chain. It builds strictly on top of today's Paging3 list, JobExecutor-routed history AI ops, and the queue editor — none of that is undone.

## Glossary

### New components
- **`Widget.Dictate.HistoryIconButton`** — the single style all step-card/status icon views use; carries `tint=?attr/colorOnSurfaceVariant`, borderless ripple, 48dp touch target. The load-bearing artifact of the R3 systemic fix (§3.1).
- **`StepKey`** — stable identity of a step card across full list rebuilds (`type` + `chainIndex` / entity id); DiffUtil identity and expansion-state key (§3.3).
- **`StepExpansionState`** — plain Kotlin class owning the set of expanded `StepKey`s; survives adapter rebuilds and process death via saved instance state (§3.3).
- **`HistoryAudioResolver`** — computes `(available, playablePaths)` for a session from *both* audio columns + file existence; the one place that answers "does this session have audio?" (§3.2, fixes F-113 read side).
- **`HistoryAudioPlayer`** — sequential multi-segment playback with play/pause toggle and completion reset (§3.2, fixes F-113 playback + F-115).
- **`JobRequest.TranscriptionRerun`** — new sealed variant: re-transcribe a session's stored audio as a new transcription version, nothing downstream (§3.4).

### Existing concepts this spec wires up
- **Transcription version chain** — `TranscriptionEntity.version`/`is_current` + `SessionManager.addTranscriptionVersion` + `TranscriptionDao.getAllVersions`/`setCurrentById`: fully present in the DB layer, currently unused by any UI (`TranscriptionDao.kt:26-33`).
- **Downstream-staleness warning** — the existing "current version ≠ latest / downstream differs" pattern (`item_pipeline_version_warning_tv`), extended to the transcription step.
- **Registry-driven progress** — `ActiveJobRegistry` observer drives the detail progress bar and button gating (`HistoryDetailActivity.java:215-235`); every new operation plugs into this, never into Activity-local state.

> **Version chain ≠ processing chain.** The *processing chain* is the ordered list of processing steps (`chain_index` 1..N); a *version chain* is the set of alternatives at ONE position (`version` 1..M, one `is_current`). Transcription has its own version chain in a separate table (`TranscriptionEntity`), independent of the processing steps' version chains.

## 1. Vision and Motivation

### 1.1 Why this overhaul exists

The history feature received three structural upgrades today (Paging3 list, JobExecutor-routed AI ops, queue editor) but the *presentation layer* was never designed — it accreted:

1. **Buttons render black / invisible (R3).** Five of seven step-action icons carry `android:tint="#000000"` baked into the vector drawable (invisible in dark mode); `ic_baseline_autorenew_24` has *no* tint (white fill — invisible in light mode); status badge icons in the list are black too. The Activities are correctly DayNight-themed — the defect is 100 % drawable-literal + missing view-level tint (UI-INVENTORY §2).
2. **Text is cut off (R4).** `item_pipeline_output_tv` hard-caps every step output — transcription, prompt results, final output — at `maxLines=5` with no way to see the rest (`item_pipeline_step.xml:142-143`).
3. **Intermediate steps cannot be copied (R1).** Only the session-level final output has a copy button; per-step outputs (the thing you want when iterating on prompt post-processing) have zero copy affordance.
4. **Playback is broken for multi-segment recordings (F-113/F-115).** Play uses the legacy `audio_file_path` column (frozen at segment 1 for never-uploaded sessions), has no pause/stop, and delete-audio orphans segments 2..N.
5. **No transcription re-run (R6).** Regeneration exists for every completion step but not for the transcription — even though the DB already has a full transcription version chain and the job layer already has the routing pattern.
6. **Assorted quality gaps** — unguarded list delete during active jobs (F-114), session-level error details never displayed (F-053), stale recycled click listeners (F-107), conflated empty states (F-117), and the entire history-detail string namespace untranslated in de/es/pt.

### 1.2 What this solves

A user iterating on prompt post-processing can: read every step in full (tap to expand), copy any step's text with one tap, re-run the transcription or any completion step as a new version, compare versions via chips, and trust that every button is visible, correctly colored, and safely gated in both light and dark mode.

### 1.3 Discarded Alternatives

- **Per-view hex color patches** (set each broken icon to a hardcoded night-aware color pair): fixes today's seven icons, leaves the bug class open — the next icon added regresses. Rejected; the fix is a style + convention + invariant test (ADR-0010).
- **Stripping baked tints from the shared vector drawables**: the same `ic_baseline_*` assets are consumed by the keyboard, prompts-overview, overlay, and notifications (12 consumer files outside `res/drawable`). Stripping the literal would silently change every consumer that today *relies* on the baked tint. Rejected: view-level `app:tint` overrides the drawable literal at draw time (via `Drawable.setTintList`) with zero cross-consumer risk.
- **Transcription re-run = full reprocess flag**: reprocess already re-transcribes but then re-runs the whole prompt queue and *appends* steps to the chain — different persistence primitive (`appendProcessingStep` vs `addTranscriptionVersion`), different user intent. Rejected; R6 is a distinct job (§3.4, D3).
- **Auto re-running downstream steps after a transcription re-run**: collapses back into "reprocess", double-bills every completion, and destroys the version-comparison purpose. Rejected — version-only + staleness warning (D3).
- **Migrating `HistoryDetailActivity` to Kotlin + ViewModel** in this pass: correct end-state, but it multiplies the diff for zero user-visible gain; the reprocess-hardening pass already made the Activity a thin dispatcher. Deferred (documented scope cut, §7 gap 4); all *new* logic lands in extracted, testable Kotlin classes instead.

## 1a. Architecture Walkthrough

### 1a.0 Stack diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│  LAYER 1 — Screens (thin dispatchers)                          (top)     │
│  Files:  history/HistoryActivity.kt, history/HistoryDetailActivity.java  │
│  Form:   observe registry + paging; dispatch JobRequests; no AI/DB logic │
└──────────────────────────────────────────────────────────────────────────┘
                  ↓ binds rows via / reads state from
┌──────────────────────────────────────────────────────────────────────────┐
│  LAYER 2 — Adapters + extracted UI policy (all unit-testable)            │
│  Files:  history/PipelineStepAdapter.kt [rewrite], HistoryAdapter.kt,    │
│          history/StepExpansionState.kt [NEW],                            │
│          history/HistoryAudioResolver.kt [NEW],                          │
│          history/HistoryAudioPlayer.kt [NEW]                             │
│  Form:   ListAdapter + DiffUtil on StepKey; expansion set; audio policy  │
└──────────────────────────────────────────────────────────────────────────┘
                  ↓ dispatches work exclusively through
┌──────────────────────────────────────────────────────────────────────────┐
│  LAYER 3 — Job layer (rotation-safe, mutually exclusive)                 │
│  Files:  core/JobExecutor.kt (+ JobRequest.TranscriptionRerun [NEW])     │
│  Form:   ActiveJobRegistry-tracked, isAnyActive()-guarded (ADR-0009)     │
└──────────────────────────────────────────────────────────────────────────┘
                  ↓ executes via
┌──────────────────────────────────────────────────────────────────────────┐
│  LAYER 4 — Orchestrator + persistence                        (bottom)    │
│  Files:  core/PipelineOrchestrator.kt (rerunTranscriptionBlocking [NEW]),│
│          core/SessionManager.kt (addTranscriptionVersion — existing),    │
│          audio/AudioFileRepository.kt (readForPipeline — existing),      │
│          core/RecordingRepository.kt (delete fix)                        │
│  Form:   resolvePipelineAudio → transcribe → new transcription version   │
└──────────────────────────────────────────────────────────────────────────┘
```

Style/theme resources (`values/themes.xml`, new `Widget.Dictate.HistoryIconButton`) sit orthogonally under Layer 2 — every icon view in history layouts references the style; the invariant test (§6) locks the convention.

### 1a.1 Read-this-before-implementing checklist

- [ ] Never set a literal color in a history layout or bind-time code — theme attr via style only (ADR-0010, §3.1).
- [ ] Never key UI state to list position — the detail list is wholesale-rebuilt on every registry tick; use `StepKey` (§3.3).
- [ ] Never answer "has audio?" from `session.audioFilePath` — use `HistoryAudioResolver` (§3.2, ADR-0007).
- [ ] Never run AI or session-row writes from the Activity — `JobRequest` through `JobExecutor` only (locked by `HistoryDetailJobRoutingInvariantTest`).
- [ ] Every listener set in `onBindViewHolder` needs a symmetric clear branch (F-107 class).
- [ ] New user-facing strings land in all four locales (en, de, es, pt).

## 2. Acceptance Criteria

Requirement coverage (R) and catalog findings (F):

1. **(R1)** Every step card whose output text is non-empty shows a copy icon button; tapping it puts the step's **full, untruncated** text on the clipboard and shows a confirmation toast. Verified by adapter bind test + manual check M3.
2. **(R2)** Every interactive view on both screens: (a) has a `contentDescription`, (b) uses `Widget.Dictate.HistoryIconButton` or an explicit Material3 style, (c) is gated per §3.5's gating table (no button visible/enabled that would race an active job). Verified by invariant test + gating unit tests.
3. **(R3)** No history layout (`activity_history*.xml`, `item_history_session.xml`, `item_pipeline_step.xml`, `dialog_prompt_chooser.xml`, `item_prompt_chooser.xml`, `dialog_reprocess_queue_editor.xml`, `item_reprocess_queue_entry.xml`) contains a literal hex color, `@android:color/holo_*`, or a tint-less `ImageButton`/status `ImageView` — locked by `HistoryThemeInvariantTest` (pure-JVM source scan, red on today's code). Error/warning text uses `?attr/colorError` / new `dictateColorWarning` theme attr (light + night values).
4. **(R4 + R5)** Step output text renders collapsed at a preview line count with a visible expand affordance *only when actually truncated*; tapping the card (or text) toggles expanded (unlimited lines) ⇄ collapsed. Expansion state survives registry-tick rebuilds and rotation. `StepExpansionState` unit tests + manual check M4.
5. **(R6)** The transcription step of a session with resolvable audio shows a re-run button; it dispatches `JobRequest.TranscriptionRerun` through `JobExecutor` (registry-tracked, rotation-safe, mutually exclusive per ADR-0009); on success a **new current transcription version** exists (`TranscriptionEntity` version chain), `final_output_text` follows §3.4's rules, and the UI shows transcription version chips with working switching. Robolectric + Room job tests, red-provable against today's code (the variant doesn't exist).
6. **(R6 audio)** Audio availability, playback, and re-run all resolve audio multi-segment-aware (`audio_file_paths` + legacy fallback + file existence); playback plays **all** significant segments sequentially with a play/pause toggle whose icon reflects state and resets on completion (F-113, F-115). `RecordingRepository.deleteBySessionId` deletes *all* referenced audio files (segments + legacy + persistent) before clearing columns (F-113 delete side). Unit tests on resolver/player policy + repository test.
7. **(F-053)** A FAILED session renders its persisted `last_error_type` + `last_error_message` in the detail screen (dedicated error surface, night-aware colors); the `partial:N` marker renders as a human-readable partial-recovery note instead of raw text.
8. **(F-114)** List long-press delete and "Delete all" refuse (dialog/toast, no destructive action) sessions with `ActiveJobRegistry.isActive(id)`; "Delete all" deletes the rest and reports the skip. ViewModel unit test.
9. **(F-107)** `onBindViewHolder` clears the item click listener for non-SOURCE_SESSION rows (moot-by-rewrite is acceptable if the rewritten adapter binds symmetrically — test asserts a recycled SOURCE_SESSION holder rebound as a normal step is not clickable).
10. **(F-117)** With an active search/filter and zero results, the list shows a "no matching sessions" string, not the "no sessions yet" onboarding text. Unit-tested at the state-derivation level.
11. **(F-049)** Regression-verified only: registry-driven progress bar clears after every job type incl. the new re-run (covered by the existing observer path; manual check M6).
12. **Strings:** every new string + the existing `dictate_history_*` detail namespace exists in `values`, `values-de`, `values-es`, `values-pt`; the dead `dictate_history_pause` string is either wired (play/pause) or the audit removes other dead strings (`_based_on`, `_open_parent`, `_regenerating`, `_regenerate_failed`) after a zero-grep check.
13. **Build/tests:** `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug` green after every chunk commit.

## 3. Architecture Specification

### 3.1 Systemic color fix (R3) — style + theme attrs + invariant lock

**Convention (ADR-0010):** icon color comes from a theme attribute at the *usage site* (style or explicit `app:tint`); vector drawables' baked tints are never relied upon; layouts never carry hex literals.

**New resources:**

```xml
<!-- values/attrs.xml [NEW or EDIT] -->
<attr name="dictateColorWarning" format="color"/>

<!-- values/themes.xml + values-night/themes.xml [EDIT] : inside Theme.Dictate -->
<item name="dictateColorWarning">@color/dictate_warning</item>   <!-- light/dark values in colors.xml / colors night -->

<!-- values/styles (new file res/values/styles_history.xml or themes.xml) -->
<style name="Widget.Dictate.HistoryIconButton" parent="Widget.Material3.Button.IconButton">
    <item name="android:background">?attr/selectableItemBackgroundBorderless</item>
    <item name="tint">?attr/colorOnSurfaceVariant</item>
    <item name="android:minWidth">48dp</item>
    <item name="android:minHeight">48dp</item>
</style>
```

(Exact parent — plain `ImageButton` style vs `Widget.Material3.Button.IconButton` — is the implementer's choice; the invariants are: theme-attr tint, ripple background, ≥48dp touch target, one style used everywhere.)

**Applied to:** the seven step-card ImageButtons (`item_pipeline_step.xml:59-133`), the list status icon (`item_history_session.xml:56-63` gets `app:tint="?attr/colorOnSurfaceVariant"` or a status-colored attr), plus any icon view this overhaul adds. `item_pipeline_error_tv` → `?attr/colorError`; `item_pipeline_version_warning_tv` → `?attr/dictateColorWarning`.

**Not touched:** the vector drawables themselves (shared consumers: keyboard, prompts UI, overlay, notifications — view-level tint overrides the baked literal at draw time).

**Lock:** `HistoryThemeInvariantTest` (pure-JVM source scan over the seven history layout files, precedent: `HistoryDetailJobRoutingInvariantTest` / `MotionSceneSchemaTest`): bans `#`-hex color attribute values and `@android:color/holo_`, and requires every `<ImageButton>`/status `<ImageView>` element to declare `style=` or a `?attr/`-tint. Written RED against the unfixed layouts first.

### 3.2 Audio correctness (F-113, F-115) — resolver, player, delete

**`HistoryAudioResolver` [NEW, `history/HistoryAudioResolver.kt`]** — pure Kotlin, constructor-injected file-existence check for testability:

```kotlin
/** Single source of truth for "does this session have playable/re-runnable audio, and which files". */
class HistoryAudioResolver(private val fileExists: (String) -> Boolean = { File(it).exists() }) {
    data class Resolution(val playablePaths: List<String>) {
        val available: Boolean get() = playablePaths.isNotEmpty()
    }
    /** Order: multi-segment column first (existing files only); fall back to legacy column. */
    fun resolve(audioFilePaths: List<String>, legacyAudioFilePath: String?): Resolution
}
```

Consumers: play-button visibility, re-run-button visibility (§3.4), `startHistoryReprocess` availability check, delete-audio visibility — replacing all four `session.getAudioFilePath()`-only reads (`HistoryDetailActivity.java:357-361, 491-495`). This aligns the history read side with ADR-0007 (readers go through the multi-segment surface).

**`HistoryAudioPlayer` [NEW, `history/HistoryAudioPlayer.kt`]** — sequential playback of the resolved path list. Two parts:
- `SegmentPlaylistPolicy` (pure, unit-tested): current index, `advance()`, `toggle()` state machine `Idle → Playing(i) → Paused(i) → Idle(on completion of last)`.
- A thin `MediaPlayer` host that maps policy transitions to `setDataSource/start/pause/release`, calls back `onStateChanged(isPlaying)` so the adapter swaps the play/pause icon (uses `dictate_history_play`/`dictate_history_pause` — wiring the dead string), and releases in `onPause()` of the Activity (today: only `onDestroy`).

No muxing on the UI path — sequential playback avoids the MediaMuxer cost and temp files; the *job* path keeps using `readForPipeline` (merged) as today.

**Delete fix [EDIT `core/RecordingRepository.kt` `deleteBySessionId`]:** iterate and delete **all** of: every `audio_file_paths` entry, the legacy `audio_file_path`, and the persistent `filesDir/recordings/{sid}.m4a` copy if present — then clear both columns (today: deletes exactly one file, orphaning segments 2..N indefinitely for RECORDED sessions).

### 3.3 Step adapter rework (R1, R4, R5, F-107, F-053) — `PipelineStepAdapter.kt`

**Conversion Java → Kotlin `ListAdapter<PipelineStep, VH>`** (justified conversion: the adapter is rewritten anyway; `ListAdapter`+DiffUtil replaces the `notifyDataSetChanged()`-on-every-registry-tick full rebind, which currently reflows the whole screen during active jobs and would wipe expansion state).

- **`StepKey`** — stable id: `"$type:$chainIndex"` for chain steps, `"transcription"`, `"audio"`, `"final"`, `"source:$sessionId"`. DiffUtil `areItemsTheSame` on `StepKey`; `areContentsTheSame` structural.
- **`StepExpansionState` [NEW]** — `MutableSet<StepKey>`-backed, `toggle(key)`, `isExpanded(key)`, `snapshot()/restore(Bundle)`; owned by the Activity, passed to the adapter, saved in `onSaveInstanceState`. This is the R5 keystone: expansion survives both wholesale reloads (registry ticks call `submitList`, DiffUtil rebinds only changed rows) and rotation.
- **Expand/collapse (R4+R5):** collapsed = `maxLines = 5` (list preview stays 2); expanded = `Integer.MAX_VALUE`. A chevron / "more" affordance is shown only when the text is actually ellipsized (post-layout check); card tap and text tap both toggle. Content stays selectable-false (copy goes through the button — avoids scroll/selection conflicts inside the card).
- **Per-step copy (R1):** copy icon button in the card action row, visible iff output text non-empty; copies the full text via `ClipboardManager` + toast. Plain clipboard only — no insertion/usage logging (that stays exclusive to the session-level final-output copy button, which tracks paste-from-history).
- **Symmetric binding (F-107):** every listener/visibility set in `onBind` has an else branch; regression test binds a SOURCE_SESSION then rebinds the holder as TRANSCRIPTION and asserts non-clickable.
- **Session-level error surface (F-053):** `buildPipeline`/`buildRecordingPipeline` append a session-error element when `status == FAILED && lastErrorMessage != null` (rendered with `?attr/colorError`); `last_error_message == "partial:N"` renders via a new string `dictate_history_partial_recovery` ("Only N segment(s) of the recording could be recovered") instead of the raw marker. Error type shown via existing enum name mapping.
- **Version chips** — mechanism unchanged, extended to the transcription step (§3.4).

### 3.4 Transcription re-run (R6) — job, orchestrator, version UI

**Transport [EDIT `core/JobExecutor.kt`]:**

```kotlin
/** Re-transcribes a session's stored audio as a NEW transcription version.
 *  Deliberately does NOT touch the processing chain — downstream staleness is
 *  surfaced in the UI; re-running downstream stays the reprocess buttons' job. */
data class TranscriptionRerun(val sessionId: Long) : JobRequest()
```

Routed exactly like `StepRegenerate`: `JobExecutor.start` registers in `ActiveJobRegistry`, rejects when occupied (ADR-0009: history jobs stay `isAnyActive()`-guarded, outside the keyboard run-queue), survives the Activity (ADR-0003: executes on the JobExecutor thread in the FGS process scope).

**Execution [EDIT `core/PipelineOrchestrator.kt`, new `rerunTranscriptionBlocking(sessionId)`]:**

1. `resolvePipelineAudio(sessionId)` — existing primitive: multi-segment merge via `readForPipeline`, legacy fallback, `partial:N` persistence on partial recovery (ADR-0007 semantics preserved).
2. No audio resolvable → fail the job with the existing `audio_file_missing` surface; session row untouched.
3. `aiOrchestrator.transcribe(audioFile, session.language, stylePrompt = null)` — language from the session row; model/keyterms from current prefs (not persisted per-session — accepted, see gap 2). Usage is tracked by `transcribe` itself (re-run bills audio duration again — accepted, inherent to re-running).
4. Persist via **`SessionManager.addTranscriptionVersion`** (existing: `getMaxVersion+1`, `clearCurrent`, insert current). **No schema change, no migration** — the version chain already exists (`TranscriptionEntity.kt:16-22`).
5. `updateFinalOutputText` via the existing `getFinalOutput` selection (last current step wins; bare transcription wins only when no chain exists). When the session is FAILED **and has no processing chain**, finalize COMPLETED (a successful re-transcription of a failed session is a completed transcription — mirrors reprocess finalization; exact `SessionManager` call verified by the implementer, gap 3).

**UI [EDIT `HistoryDetailActivity.java` + adapter]:**

- Transcription step card gains a **re-run button** (autorenew icon, `HistoryIconButton` style): visible iff `HistoryAudioResolver.resolve(...).available`, enabled iff `!ActiveJobRegistry.isAnyActive()`; dispatches the job; progress + reload via the existing registry observer (nothing new).
- Transcription step gains **version chips** when `TranscriptionDao.getAllVersions(sessionId).size > 1` — same ChipGroup mechanism as processing steps; selection calls new `switchTranscriptionVersion` (`clearCurrent` + `setCurrentById` in a transaction, then `updateFinalOutputText`).
- **Staleness warning:** when a processing chain exists and step 1's snapshotted `input_text != current transcription text`, the transcription card shows the version-warning line ("Downstream steps are based on a different transcription version — use reprocess to re-run them"), reusing the `dictateColorWarning`-tinted warning view. This is the D3 contract: re-run/switch never mutates downstream; the warning routes the user to the existing reprocess buttons.

### 3.5 Button audit (R2) — gating and placement table

Target per-card action rows (all `HistoryIconButton`-styled, all with contentDescription):

| Card | Actions (left→right) | Visible when | Enabled when |
|---|---|---|---|
| Audio | play/pause · direct reprocess · reprocess-with-edit · delete audio | audio resolvable (resolver §3.2); reprocess pair additionally per existing status rules | reprocess/delete: visibility `!isActive(sessionId)`, dispatch `!isAnyActive()`; play always |
| Transcription | copy · re-run (R6) | copy: text non-empty; re-run: audio resolvable | re-run: visibility `!isActive(sessionId)`, dispatch `!isAnyActive()` |
| Processing step | copy · regenerate · other-prompt | copy: text non-empty; rest: current rules | visibility `!isActive(sessionId)`, dispatch `!isAnyActive()` |
| Last step | + post-process | current rule (last step) | visibility `!isActive(sessionId)`, dispatch `!isAnyActive()` |
| Final output (screen level) | copy · share (unchanged Material buttons) | always | always |
| Session error card | — (display only) | FAILED + error present | — |

The two-level gate mirrors the screen's pre-existing pattern: per-session *visibility* (`isActive(sessionId)`) keeps buttons of unrelated sessions usable while a job runs elsewhere; the *dispatch* helpers enforce the hard process-global `isAnyActive()` mutual exclusion (ADR-0009) and fail fast with a toast if a race slips through.

List screen: long-press delete + "Delete all" gain the F-114 guard — `ActiveJobRegistry.isActive(id)` → refusal dialog ("session is being processed"); "Delete all" excludes active ids and toasts the skipped count. Guard lives in `HistoryViewModel` (unit-testable), not in the click listener.

### 3.6 List screen polish (F-117, F-053-list)

- `updateEmptyState` differentiates: unfiltered-empty → existing onboarding string; filtered/search-empty → new `dictate_history_no_results`. The "is a filter active" fact comes from `HistoryViewModel` (it owns filter+query state).
- FAILED rows keep the badge; no error text in the list row (kept scannable — details live in the detail screen).

## 4. Directory Layout

```
app/src/main/
├── java/net/devemperor/dictate/
│   ├── history/
│   │   ├── HistoryActivity.kt                   [EDIT]  F-117 empty-state split, F-114 refusal dialog
│   │   ├── HistoryViewModel.kt                  [EDIT]  delete guards, filter-active exposure
│   │   ├── HistoryAdapter.kt                    [EDIT]  status-icon theme tint
│   │   ├── HistoryDetailActivity.java           [EDIT]  resolver wiring, rerun dispatch, error card, player lifecycle
│   │   ├── PipelineStepAdapter.java             [MOVE→ .kt rewrite]  ListAdapter + StepKey + expand + copy
│   │   ├── StepExpansionState.kt                [NEW]   R5 expansion-state owner
│   │   ├── HistoryAudioResolver.kt              [NEW]   F-113 read-side single source of truth
│   │   └── HistoryAudioPlayer.kt                [NEW]   F-113/F-115 sequential play/pause
│   ├── core/
│   │   ├── JobExecutor.kt                       [EDIT]  JobRequest.TranscriptionRerun
│   │   ├── PipelineOrchestrator.kt              [EDIT]  rerunTranscriptionBlocking
│   │   ├── SessionManager.kt                    [EDIT]  (only if finalize/getFinalOutput needs a seam)
│   │   └── RecordingRepository.kt               [EDIT]  multi-file deleteBySessionId
│   └── database/dao/TranscriptionDao.kt         [KEEP]  getAllVersions/setCurrentById finally get consumers
├── res/
│   ├── values/{themes,attrs,colors,strings}.xml [EDIT]  style, dictateColorWarning, new strings
│   ├── values-night/{themes,colors}.xml         [EDIT]  dark warning color
│   ├── values-{de,es,pt}/strings.xml            [EDIT]  new strings + history-namespace backfill
│   ├── layout/item_pipeline_step.xml            [EDIT]  styles, copy btn, rerun btn, expand affordance
│   ├── layout/item_history_session.xml          [EDIT]  status-icon tint
│   └── drawable/ic_baseline_pause_24.xml        [KEEP]  pre-existing; keyboard consumers rely on its baked tint — the history button's view-level style tint overrides it (ADR-0010 D1)
├── test/java/net/devemperor/dictate/history/
│   ├── HistoryThemeInvariantTest.kt             [NEW]   R3 lock (source scan)
│   ├── StepExpansionStateTest.kt                [NEW]
│   ├── HistoryAudioResolverTest.kt              [NEW]
│   ├── SegmentPlaylistPolicyTest.kt             [NEW]
│   ├── PipelineStepAdapterBindTest.kt           [NEW]   F-107 + copy/expand bind (Robolectric)
│   └── HistoryViewModelTest.kt                  [EDIT]  delete-guard + empty-state cases
└── test/java/net/devemperor/dictate/core/
    └── TranscriptionRerunJobTest.kt             [NEW]   Robolectric + real Room + capturing fake runner
docs/
├── decisions/0010-ui-icon-tint-theme-attrs.md   [NEW]   the R3 convention
├── decisions/README.md                          [EDIT]  index row
└── research/README.md                           [EDIT]  index row for this spec
```

**File counts:** 9 new files (5 prod, 4+ test), ~14 edits, 1 Java→Kotlin rewrite, 0 schema migrations.

## 5. Migration Plan (chunks — each compiles + full unit suite green + one commit)

1. **Chunk A — systemic color layer (R3).** New attrs/style/colors (+night), apply to all seven history layouts, `?attr/colorError`/`dictateColorWarning` for error/warning text, pause drawable. Write `HistoryThemeInvariantTest` FIRST and run it red against the unfixed layouts. Independently testable: pure resource + test change, app renders identically except colors.
2. **Chunk B — audio correctness (F-113/F-115).** `HistoryAudioResolver` + `HistoryAudioPlayer`/`SegmentPlaylistPolicy` + `RecordingRepository.deleteBySessionId` multi-file fix; wire detail-screen availability checks and play/pause through them. Tests: resolver (fake existence), policy state machine, repository deletion (temp files, red-first on the orphan case).
3. **Chunk C — step adapter rewrite (R1/R4/R5, F-107, F-053).** `PipelineStepAdapter` → Kotlin ListAdapter, `StepKey` + `StepExpansionState` (+ saved-state), per-step copy, expand affordance, symmetric binding, session-error card, `partial:N` humanization. Tests: expansion-state unit, Robolectric bind tests (F-107 recycle regression red-first against a Java-adapter-equivalent bind path is impractical — instead assert the new adapter's symmetric contract + keep the invariant list).
4. **Chunk D — transcription re-run (R6).** `JobRequest.TranscriptionRerun`, `rerunTranscriptionBlocking`, `switchTranscriptionVersion`, transcription version chips + re-run button + staleness warning. Tests: `TranscriptionRerunJobTest` (registry lifecycle, new current version, mutual exclusion, no-audio failure, FAILED-no-chain → COMPLETED, final_output rules), staleness-warning derivation unit test.
5. **Chunk E — list polish + strings + closure.** F-114 guards (ViewModel), F-117 empty states, string backfill de/es/pt, dead-string zero-grep cleanup, `assembleDebug`, manual verification list executed/documented.

Dependency order: A → C (styles used by rewritten layout bindings); B → D (resolver gates the re-run button); C → D (version chips live in the rewritten adapter). A/B are independent of each other.

## 6. Testing Approach

- **Invariant (source-scan, pure JVM):** `HistoryThemeInvariantTest` — R3 lock, red-proven. Precedents: `HistoryDetailJobRoutingInvariantTest`, `MotionSceneSchemaTest`.
- **Unit (plain JVM):** `StepExpansionStateTest`, `HistoryAudioResolverTest`, `SegmentPlaylistPolicyTest`, staleness-derivation, empty-state derivation, `HistoryViewModelTest` delete guards (fake registry seam).
- **Robolectric + real Room (K-1, no Mockito — capturing fake runner via the existing `open RunnerFactory` seam):** `TranscriptionRerunJobTest`, `PipelineStepAdapterBindTest`, repository delete test. Regression tests are run red against unfixed code where the unfixed path still exists (delete-orphan, theme scan); new-feature tests follow TDD.
- **Manual on-device (JVM-untestable visual/interaction surface)** — script in §"Manual verification" of the final report: dark/light button visibility sweep, expand/collapse across rotation + active job, multi-segment play/pause, re-run end-to-end, delete guard dialog, locale spot-check (de).

## Decision Log

### D1 — View-level theme-attr tint; shared drawables untouched
**Trigger:** R3 root cause = baked drawable tints; drawables shared by 12 non-history consumers. **Decision:** one `HistoryIconButton` style + explicit `?attr` tints at usage sites; never strip/edit the shared vectors in this pass. **Rationale:** view tint overrides the baked literal at draw time — zero cross-consumer risk; convention + invariant test kills the bug class. Codified as ADR-0010.

### D2 — Adapter rewritten as Kotlin ListAdapter with StepKey-based DiffUtil
**Trigger:** R5 needs expansion state that survives the registry-tick wholesale rebuilds; the Java adapter is `notifyDataSetChanged`-based. **Decision:** justified Java→Kotlin conversion (CLAUDE.md "don't convert without reason" — the reason is the rewrite itself); stable `StepKey` identity; expansion in `StepExpansionState` + saved instance state. **Alternative rejected:** expansion flag on the throwaway `PipelineStep` objects — reset on every reload.

### D3 — Transcription re-run is version-only; downstream never auto-re-runs
**Trigger:** RERUN-SURFACE tension 3 (downstream `input_text` snapshots go stale). **Decision:** re-run creates a new current transcription version + staleness warning; re-running downstream stays the existing reprocess buttons' job. **Rationale:** keeps R6 non-destructive and cheap, mirrors step-version semantics, avoids surprise multi-step billing; "reprocess" already exists for the full re-run intent.

### D4 — Reuse the existing TranscriptionEntity version chain; no schema change
**Trigger:** version/is_current + `addTranscriptionVersion` + unused DAO switchers already exist. **Decision:** no new columns, no migration, no Double-Enum work. A re-run version is indistinguishable from a reprocess version — acceptable (both are "re-transcriptions of the same audio").

### D5 — Multi-segment playback = sequential segments, not muxing
**Trigger:** F-113 playback fix needs all segments; `readForPipeline` muxes via MediaMuxer. **Decision:** sequential `MediaPlayer` playlist on the UI path; muxing stays a job-path concern. **Rationale:** no latency/temp-file cost for a UI tap; policy extractable and unit-testable.

### D6 — Emoji step/type icons stay
Theme-independent, information-dense, and liked; swapping to tinted vectors is churn without a defect. Only *interactive* icons are vectors under ADR-0010.

### D7 — F-114 guard blocks with refusal, does not cancel-first
Cancelling a running job as a side effect of a delete gesture is surprising and destructive; the refusal dialog names the reason. "Delete all" skips active sessions and reports the skip.

### D8 — Locale backfill of the whole `dictate_history_*` namespace included
The detail screen's label set is English-only in de/es/pt today; shipping a "deutlich schöner" screen with mixed-language UI fails the intent. Bounded (~30 strings × 3 locales), lands in Chunk E.

### D9 — No HistoryDetailViewModel migration in this pass
See §1.3; all new logic is extracted-testable instead. Recorded as gap 4 for a follow-up.

### D10 — Per-step copy does not log usage/insertion events
The session-level final-output copy keeps its paste-from-history tracking; per-step copy is a plain clipboard convenience — logging every intermediate copy would pollute usage stats.

## 7. Information Gaps

1. **Exact warning color values** (`dictate_warning` light/dark) — owner: implementer (pick M3-harmonious amber, verify contrast on `colorSurface` both modes); fallback: `#B26A00` light / `#FFB74D` dark.
2. **Re-run under different current prefs** (model/keyterms not persisted per-session) — a re-run may differ from v1 for reasons other than audio; owner: accepted as inherent (documented in the job KDoc); no UI copy planned.
3. **Exact finalize call for FAILED-no-chain → COMPLETED** (§3.4 step 5) — owner: Chunk D implementer verifies `SessionManager` API (`finalizeCompleted` clears error fields?) and mirrors reprocess semantics; fallback: leave status untouched and only update `final_output_text` (strictly less surprising, still useful).
4. **Detail-screen ViewModel + off-main-thread DAO access** — deferred (D9); owner: follow-up plan when `allowMainThreadQueries()` removal is scheduled (pagination spec §2.2 already scoped that out app-wide).
5. **Expand-affordance visual form** (chevron vs "more" text vs fade) — owner: implementer taste within M3; the spec only mandates: visible iff actually truncated, toggles both ways, state keyed by `StepKey`.

## 8. Change History

### 2026-07-02 — Initial spec
- **Trigger:** User requirements R1–R6 (history UI "deutlich schöner und nachhaltiger", easier prompt post-processing) + adjacent catalog findings.
- **Reasoning:** Two Opus analysis passes (full UI inventory + re-run design surface) established: color defects are drawable-literal class (systemic style fix), transcription versioning already exists DB-side (no migration needed), and expansion state must be keyed outside the rebuilt list. Findings F-049 (verified fixed), F-053, F-107, F-113, F-114, F-115, F-117 included; F-105 (orphaned audio on session delete) partially covered via the delete fix; excluded: keyboard-side F-001/F-003 (different subsystem), app-wide main-thread-queries removal (gap 4).

### 2026-07-03 — Implemented (status → Accepted)
- **Trigger:** Implementation of §5 chunks A–E on branch `worktree-agent-a8c0400d6df4497b5` (`[history-ui]` commits `defb3b6`, `6e44989`+`b83be9e`, `3c53f74`, `06a564b`, `135fae0`), adversarially reviewed against all 13 acceptance criteria (verdict: PASS, full suite 1821/0/0, `assembleDebug` green).
- **What changed:**
  - All five chunks landed per §5 with red-first regression tests where the unfixed path existed (theme scan, delete-orphan, delete-guard) and TDD for new features (re-run job).
  - **Deviations (all recorded per-commit):** (1) `ic_baseline_pause_24` was *not* new and was NOT tint-stripped — its keyboard consumers (`activity_dictate_keyboard_view.xml`, `QwertzRecordingController`, `IconResolvers`) use it as an untinted `android:foreground` and rely on the baked literal; the history button's view-level style tint overrides it (commit `b83be9e`, the first live application of ADR-0010's consumer-audit rule). §4 corrected to `[KEEP]`. (2) Button gating uses the screen's pre-existing two-level pattern — per-session visibility + process-global dispatch guard — §3.5 table wording aligned. (3) Gap-3 finalize choice: the no-audio re-run path surfaces via the job error callback without touching the session row; FAILED-no-chain success finalizes COMPLETED via `finalizeCompleted` (error fields left as-is, matching reprocess semantics). (4) Chunk E added `SessionDao.deleteAllExcept(exemptIds)` for the delete-all skip (no client-side enumeration). (5) Dead strings `_based_on`, `_open_parent`, `_regenerating`, `_regenerate_failed` deleted after zero-grep; `dictate_history_pause` is now wired.
  - Locale backfill: 37 keys × 3 locales (de/es/pt) — the full history namespace now translates.

## 9. References

- Requirements: user assignment R1–R6 (2026-07-02 session).
- Parent catalog: [`2026-07-02 - feature-wiring-code-review.md`](<2026-07-02 - feature-wiring-code-review.md>) — F-049, F-053, F-107, F-113, F-114, F-115, F-117.
- Sibling specs (build-on, do-not-undo): [`2026-07-02 - history-pagination-and-scale.md`](<2026-07-02 - history-pagination-and-scale.md>), [`2026-07-02 - history-reprocess-hardening.md`](<2026-07-02 - history-reprocess-hardening.md>), [`2026-07-02 - reprocess-queue-editor.md`](<2026-07-02 - reprocess-queue-editor.md>).
- ADRs: `docs/decisions/0003-service-foreground-pipeline-architecture.md`, `0007-audio-multi-file-repository.md`, `0009-pipeline-run-queue-serialized-concurrency.md`, `0010-ui-icon-tint-theme-attrs.md` (new).
- Key code (current state): `history/HistoryDetailActivity.java:215-235,357-361,461-476,491-495`, `history/PipelineStepAdapter.java:248-288`, `res/layout/item_pipeline_step.xml:59-190`, `core/JobExecutor.kt:259-417`, `core/PipelineOrchestrator.kt:591-678,1230-1271`, `core/SessionManager.kt:217-247,456-469`, `database/entity/TranscriptionEntity.kt:16-22`, `database/dao/TranscriptionDao.kt:26-33`, `core/RecordingRepository.kt:136-148`, `state/render/RecordButtonColorController.kt` (tint-discipline precedent).
