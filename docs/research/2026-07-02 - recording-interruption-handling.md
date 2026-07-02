# Recording Interruption Handling — Produce InterruptionAction

---
date: 2026-07-02
author: Lukas + Claude (multi-agent review session)
type: Research
status: Accepted
context: Call/headset/screen interruption handling is entirely absent — the state-machine slot exists (InterruptionModule stub) but no producer dispatches its actions. Finding F-036.
related-plan: n/a (seeded by 2026-07-02 - feature-wiring-code-review.md, F-036)
related-adrs: —
---

An incoming phone call during an active recording neither pauses nor cancels it — the mic keeps capturing through the ringtone and the call. The state-machine side is prepared (`InterruptionAction` leaves + registered `InterruptionModule` stub), but **zero producers exist**: no call-state callback, no headset-plug receiver, no screen receiver anywhere in the app. This document scopes the missing Phase-2 implementation.

## 1. Vision and Motivation

### 1.1 Why this exists

`InterruptionModule.kt:22-24` KDoc claims *"the action sealed leaves are dispatched by the IME-side listeners today"* — this is false. A repo-wide grep for `PhoneCallStateChanged | HeadsetPlugChanged | ScreenStateChanged` hits only the `Action.kt` declarations, the module KDoc, and a test comment. No `TelephonyCallback`/`PhoneStateListener` and no `ACTION_HEADSET_PLUG` receiver are registered anywhere.

The stub registration itself is legitimate (`assertCompleteCoverage()` needs an owner for the action leaves); the defect is (a) the misleading KDoc and (b) the missing user-facing behaviour.

### 1.2 What problem this solves

1. **Phone call mid-recording:** recording should pause (or cancel — see Information Gaps) instead of capturing the call.
2. **Headset unplug mid-recording:** the classic pocket-unplug should not silently switch capture to the built-in mic without any state reaction.
3. **KDoc honesty:** the module doc must state the wiring reality.

### 1.3 Interaction with existing behaviour — read before designing

- **F-007 (confirmed, catalog):** a *service-side audio-focus listener* already pauses recording on transient focus loss, contradicting the documented lifecycle intent. Audio-focus loss is the de-facto interruption signal today — an incoming call usually takes audio focus, so users may already see a pause, but via the wrong, undocumented channel with no state-machine involvement. The Phase-2 design must **subsume or explicitly coordinate with** this listener, not add a second competing reactor.
- The Coupling-Matrix rows for Interruption × Recording are already documented in the state-machine docs (per the module KDoc) — the reducer cascade design exists on paper.

## 2. Findings + Conclusions — scoped implementation sketch

1. **Short-term (ship immediately):** correct the `InterruptionModule` KDoc — registration exists solely for `assertCompleteCoverage` + the OCP slot; listeners are not wired.
2. **Phase 2 (the feature):**
   - **Producers live FGS-side** (`DictatePipelineService`), not IME-side — recording survives IME teardown, so interruption detection must too (same reasoning as the recording-hardware ownership).
   - Call state: `TelephonyCallback.CallStateListener` (API 31+) / `PhoneStateListener` fallback; **permission question is open** — `READ_PHONE_STATE` may be avoidable via audio-focus-based detection (see gap 2).
   - Headset: `ACTION_HEADSET_PLUG` / `AudioDeviceCallback` receiver, coordinated with `BluetoothScoManager` (note F-013: the service-side SCO receiver is itself never registered — fix that wiring first or together).
   - Reducer: cascade `PauseRecording`/`CancelRecording` per the documented Coupling-Matrix rows; interruption reason surfaces via the info-bar system (pending consolidation — see the info-bar research doc).
   - **Consolidate the F-007 audio-focus listener into this design** so there is exactly one interruption authority.

## 3. Information Gaps

1. **Pause vs. cancel per interruption type** (call ⇒ pause? screen-off ⇒ nothing? headset-unplug ⇒ pause?) — owner: user decision at design time; fallback: pause for call/headset, no-op for screen.
2. **Permission strategy** — `READ_PHONE_STATE` is a heavy ask for an IME; audio-focus-loss classification may suffice for the call case. Owner: Phase-2 designer; fallback: audio-focus-based detection without new permissions.
3. **Whether `ScreenStateChanged` has any consumer use case at all** — if not, delete the action leaf rather than produce it. Owner: Phase-2 designer.
4. **F-036 is unverified** (feature-gap pass-through) — the zero-producer grep is strong evidence, but re-verify the audio-focus listener interplay (F-007 *is* confirmed) before building.

## 4. Change History

### 2026-07-02 — Implemented (status → Accepted)

- **Trigger:** Implementation session per §2 sketch + the documented Information-Gap fallbacks.
- **What changed:**
  - **KDoc lie fixed and made true.** `InterruptionModule` is no longer a stub — it owns a real `InterruptionState` axis (`lastInterruption: InterruptionEvent?`, non-null while an interruption-caused pause is live) and the pause cascade.
  - **Action surface renamed to the audio-focus truth (§1.1 latitude + Gap 2 fallback):** `PhoneCallStateChanged`/`HeadsetPlugChanged` were replaced by `AudioFocusInterrupted` and `HeadsetDisconnected` (plus the module-internal `ClearInterruption` self-cascade). Telephony semantics would have been a lie — detection is audio-focus-based, **no `READ_PHONE_STATE`**. `ScreenStateChanged` was **deleted** (Gap 3: no consumer use case). Enumeration tests (`ActionHierarchyTest`, `ModuleIdTest`, `DictateModuleRegistryTest`) updated.
  - **Producers live FGS-side** (`DictatePipelineService.onCreate`/`onDestroy`): the audio-focus listener now routes through `AudioFocusChangeClassifier` (`core/InterruptionClassifiers.kt`) — GAIN → grant flag, hard LOSS → grant flag + interruption, LOSS_TRANSIENT → interruption only, CAN_DUCK → ignored; headset unplug via `AudioDeviceCallback` + `HeadsetDeviceClassifier` (preferred over the sticky `ACTION_HEADSET_PLUG` broadcast).
  - **F-007 consolidated — exactly one interruption authority.** `AudioModule`'s granted-edge → `PauseRecording` cascade was removed; `audioFocusGranted` is bookkeeping only. A notification duck no longer pauses dictation; interrupting losses pause via `InterruptionModule`.
  - **F-013 fixed:** `DictatePipelineService.onCreate` now calls `BluetoothScoManager.registerReceiver()` (+ `unregisterReceiver()` in `onDestroy`); Robolectric regression test `DictatePipelineServiceScoReceiverTest` pins both lifecycle points.
  - **Reducer cascade per Gap 1 fallback:** interruption while `Active` ⇒ **pause** (not the old sketch's `CancelRecording`); `Idle`/`Preparing`/`Paused`/`Interrupted` ⇒ no-op. **No auto-resume** — the user resumes manually.
  - **Surfacing:** the interruption reason renders as a pure-info (button-less) state-derived info-bar item (`InfoBarSelector` producer reading `state.interruption`), lifetime bound to the interruption-caused pause via the module's self-clear cascade. New strings `dictate_interruption_audio_focus_msg` / `dictate_interruption_headset_msg` (default locale only, matching the `dictate_recovery_unfinished_info` precedent).
- **Deviations / deliberately left out:**
  - The dead IME-side parity audio-focus listener (`DictateInputMethodService.java:716`) was **not** deleted — it hangs off the never-started legacy `RecordingStateController` and its removal belongs to that controller's retirement (out of scope here; noted in F-007's suggested fix).
  - No telephony (`TelephonyCallback`) path at all — an interruption is reported as "audio focus taken", which cannot distinguish a call from another focus-taking app. Accepted trade-off of the no-permission design.
  - Headset *re-plug* produces no action (no mic-routing update — the old `HeadsetPlugChanged(plugged = true)` semantics had no consumer).

### 2026-07-02 — Initial scoping

- **Trigger:** Whole-app review, state-machine sweep agent.
- **What changed:** Document created from F-036, cross-linked with F-007 and F-013.

## 5. References

- Parent catalog: [`2026-07-02 - feature-wiring-code-review.md`](<2026-07-02 - feature-wiring-code-review.md>) — F-036, F-007, F-013.
- Code: `state/modules/InterruptionModule.kt:22-24`, `state/Action.kt:1082` (InterruptionAction leaves).
- Sibling research: [`2026-07-02 - infobar-consolidation.md`](<2026-07-02 - infobar-consolidation.md>) (interruption surfacing).
