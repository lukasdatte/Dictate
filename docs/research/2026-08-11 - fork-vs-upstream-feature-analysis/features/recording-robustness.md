---
date: 2026-08-11
author: Lukas + Claude (analysis session)
status: Research
context: Fork-vs-upstream analysis of recording robustness — interruption handling, audio focus, Bluetooth SCO and interrupted-recording recovery. What we built, what Dictate Keyboard 5.3 has, and whether the feature is still worth pursuing.
related-adrs: ADR-0003, ADR-0007, ADR-0009
related-plan: n/a (plan-free research)
---

# Recording Robustness — Fork vs. Upstream Analysis

This is everything that keeps a recording honest when the world interferes: an incoming call,
a yanked headset, a Bluetooth link that drops, a process death mid-sentence. **Verdict:
partially exists upstream, and this is the one area in the whole comparison where the evidence
flows in both directions.** Upstream's Bluetooth SCO routing is arguably better engineered than
ours; their audio-focus handling is materially weaker and misses the most common real-world
interruption outright; their interrupted-recording recovery is solid but cannot survive a crash;
and concurrent recording — which we support — is forbidden there by construction. That mix
makes this the most plausible candidate in the fork for a small, well-scoped upstream
contribution.

## 1. Feature Overview

Before this work, an incoming phone call during a recording did nothing at all: the mic kept
capturing through the ringtone and through the conversation, and the user found out when they
read the transcript. The state machine looked prepared — `InterruptionAction` leaves existed
and an `InterruptionModule` was registered — but a repo-wide grep for
`PhoneCallStateChanged|HeadsetPlugChanged|ScreenStateChanged` hit only declarations, the
module's own (false) KDoc, and a test comment. **Zero producers existed.** Worse, a service-side
audio-focus listener was already pausing recordings through an undocumented channel with no
state-machine involvement, and it paused on a *notification ding* because its grant test was a
naive `GAIN || GAIN_TRANSIENT`.

The fix (finding F-036, with F-007 and F-013 folded in) declared **audio focus the single
interruption authority** rather than adding a second reactor next to the first. An audio-focus
change is classified by a pure function into orchestrator actions; a removed audio input device
is classified the same way; both feed one `InterruptionState` axis; and the resulting paused
state surfaces as a button-less info-bar item explaining *why* the recording stopped. There is
no auto-resume — the user restarts deliberately.

The second half of robustness is not losing audio. `MediaRecorder` cannot append: once
`release()` runs or the process dies, restarting against the same path overwrites at offset 0.
ADR-0007 answers with an `AudioFileRepository` that owns an N-segment on-disk reality behind a
one-audio-per-session API, and the recording adapter rolls to a new segment every 30 seconds
via `setNextOutputFile`, so **a crash mid-recording loses at most one rolling interval**. The
next service start finds the orphaned `RECORDING` row and, if every segment is still on disk,
promotes it to `RECORDING_INTERRUPTED` — from which the next record tap continues the same
session and the segments are concatenated at pipeline time.

## 2. Our Implementation

**Interruption axis.** `state/modules/InterruptionModule.kt` (191, rewritten from a stub) owns
`InterruptionState.lastInterruption: InterruptionEvent?`, non-null for exactly as long as an
interruption-caused pause is live, cleared by its own self-cascade. `core/InterruptionClassifiers.kt`
(106) holds the two pure seams:

- `AudioFocusChangeClassifier.actionsFor(focusChange)` — `GAIN` (all four variants) sets the
  grant flag; hard `LOSS` sets the flag *and* dispatches `AudioFocusInterrupted`;
  `LOSS_TRANSIENT` dispatches the interruption but deliberately keeps the grant flag (focus
  returns by definition, and the flag only feeds pref-toggle idempotency gates);
  `LOSS_TRANSIENT_CAN_DUCK` is ignored outright — pausing on a notification ding *was* the
  F-007 production bug; unknown constants fail closed.
- `HeadsetDeviceClassifier.isExternalMicInput(type, isSource)` — an input-capable
  `TYPE_WIRED_HEADSET`/`TYPE_USB_HEADSET`/`TYPE_USB_DEVICE`/`TYPE_BLUETOOTH_SCO`/`TYPE_BLE_HEADSET`
  removal interrupts; losing an output-only device never does.

Both take primitive framework values, so their tests need no Android objects.

**Where the producers live.** In `DictatePipelineService` (`onCreate`/`onDestroy`), not in the
IME — recording survives IME teardown, so interruption detection has to as well (ADR-0003). The
headset producer is an `AudioDeviceCallback`, preferred over the sticky `ACTION_HEADSET_PLUG`
broadcast. F-013 was fixed in the same pass: the service-side SCO receiver had never been
registered at all, which a Robolectric regression test (`DictatePipelineServiceScoReceiverTest`)
now pins at both lifecycle points.

**Deliberate non-decisions, all recorded.** No `TelephonyCallback` and therefore no
`READ_PHONE_STATE` — an interruption is reported as "audio focus was taken", which cannot
distinguish a call from another focus-taking app, and that was judged the right trade for an
IME. `ScreenStateChanged` was **deleted** rather than implemented, because no consumer use case
survived scrutiny. Headset *re-plug* produces no action.

**Bluetooth SCO.** `core/BluetoothScoManager.kt` (244) plus `BluetoothScoSubsystemAdapter`: a
`BluetoothScoControl` interface (test seam), an availability probe requiring both
`isBluetoothScoAvailableOffCall` and an actual `TYPE_BLUETOOTH_SCO` input device, a broadcast
receiver on `ACTION_SCO_AUDIO_STATE_UPDATED`, a 2,500 ms timeout falling back to the built-in
mic, and a `stoppingSco` guard covering the window between our `stopBluetoothSco()` and the
asynchronous terminal broadcast. Note that the **availability probe is only consulted since
July 2026** — see [`latency-and-render-performance.md`](latency-and-render-performance.md); until
then every record start with the pref on paid a fixed ~2.5 s timeout even with no headset in
the building.

**Recovery.** `state/PipelineRecovery.kt` at service start: `RECORDING` → `RECORDING_INTERRUPTED`
when every listed segment still exists (audio kept, no error marker, next record tap continues
it), otherwise → `FAILED` with the segments deleted, because a partial `MediaMuxer` concat would
fail downstream anyway. Stale `RECORDING_INTERRUPTED` rows past the freshness window are swept
to `FAILED`. `PipelineBindReconciliation` runs per bind and heals the in-memory FSM from a
terminal DB status, and is a strict no-op on any non-terminal status so it can never preempt a
live run.

**Size and coupling.** The interruption wave itself is small — `69a497ea` is 22 files,
+1,029 / −148, of which ~430 lines are tests. It depends entirely on the state store (cluster 8),
the foreground service (cluster 9) and the info-bar (cluster 12), and touches cluster 13's
`RECORDING_INTERRUPTED` status. Only `InterruptionClassifiers` and `BluetoothScoManager` are
reusable in isolation — but they are the two pieces that matter for a contribution.

## 3. Upstream 5.3 Status

**Verdict: partially exists — better in one dimension, materially weaker in another, absent in a
third.**

### (a) Bluetooth SCO — theirs is arguably better

`dictate/audio/BluetoothMicRouter.kt` is 127 lines and does the right things in the right
order. `isAvailable()` requires both `isBluetoothScoAvailableOffCall` and a real
`TYPE_BLUETOOTH_SCO` input device (`:47-54`) and is checked **before** activation — the gate
defect that cost us a 2.5 s stall for months never existed there. On API 31+ it uses the modern
`setCommunicationDevice()` (`:81-86`) instead of the deprecated `startBluetoothSco()`; on 26–30
it calls the legacy API and **waits** for `SCO_AUDIO_STATE_CONNECTED` in a
`suspendCancellableCoroutine` under a 2.5 s timeout, explicitly "so we don't capture silence"
(`:88-125`); a timeout falls back to the user's configured local source
(`DictateController.kt:3119-3130`); `deactivate()` is idempotent and driven from a single
`cleanupAudioRouting()` teardown.

We still use `startBluetoothSco()` on every API level and reach connection through a
callback + `Handler` timeout rather than a suspending wait. **Their gap:** no listener for SCO
dropping *mid-recording* — the receiver is unregistered after the handshake and there is no
`AudioDeviceCallback` anywhere in the tree. Ours catches exactly that, because
`HeadsetDeviceClassifier` treats a removed `TYPE_BLUETOOTH_SCO` input as an interruption.

### (b) Audio focus — theirs is materially weaker

`requestAudioFocusIfEnabled` (`DictateController.kt:3096-3116`, verified in the worktree)
requests `AUDIOFOCUS_GAIN_TRANSIENT` with sensible attributes and then handles exactly one
value:

```kotlin
.setOnAudioFocusChangeListener { change ->
    if (change == AudioManager.AUDIOFOCUS_LOSS) {
        val current = _state.value
        if (current is UiState.Recording && !current.paused) togglePause()
    }
}
```

Confirmed absent by repo-wide grep: `AUDIOFOCUS_LOSS_TRANSIENT` and `..._CAN_DUCK` are not
handled at all, so **the phone-ringing case — which typically delivers `LOSS_TRANSIENT` — does
not pause the recording**; there is no `TelephonyManager`/`PHONE_STATE` listener; there is no
`registerAudioRecordingCallback`, so another app taking the mic goes unnoticed; and the capture
loop ignores negative `AudioRecord.read` returns (`if (n > 0 && !paused)`,
`audio/RecordingController.kt:107`), turning `ERROR_DEAD_OBJECT` into a silent spin.

> [!NOTE]
> One claim in the upstream report reads as an advantage for us and should not: "on pause
> the mic is not released" is true of upstream (`RecordingController.pause()` keeps reading and
> discarding frames), but **ours does not hand the microphone back either** — we call
> `MediaRecorder.pause()`, which suspends capture through the platform API but keeps the session
> open. Our advantage here is detection and state modelling, not mic release. Neither app frees
> the mic for a concurrent recorder.

### (c) Interrupted-recording recovery — theirs is present and solid

Three triggers funnel into `stashRecordingOnHide` (`DictateController.kt:2226`): `onWindowHidden`,
`onDestroy`, and a **screen-off broadcast registered for the duration of every recording**
(`:874-895`), which their own comment calls "the dependable catch-all". It stops the recorder
(patching the WAV header so the file is valid), moves the WAV to
`filesDir/dictate_interrupted.wav` "so it survives the cache wipe", and persists three prefs. On
the next keyboard open a chip offers send / continue / discard, and *continue* splices the new
segment on via `AudioConcat.concat` (`:2274`). Shipped as #147/#111.

Two asymmetries. **Theirs catches a case ours does not:** we deliberately deleted
`ScreenStateChanged`, so a screen-off during recording produces no reaction from us at all (our
answer to "the IME view went away" is the `HOVER` widget instead, which is a different and
arguably better answer while the process lives). **Ours catches a case theirs does not:** a
crash or OOM kill *during* recording loses upstream's audio entirely — the header is only
patched in `stop()` and there is no periodic flush or orphan scan — whereas our 30-second
rolling segments plus the `RECORDING` anchor row make it recoverable minus at most one interval.

### (d) Concurrent recording — absent upstream, by construction

`canStartRecording()` (`DictateController.kt:598-604`) returns false during `Recording`,
`Transcribing` *and* `Rewording`; a mic tap during transcription **cancels** it rather than
starting a new capture. `DictateController` is a process-wide `object` with a single `recorder`
and a single `transcribeJob` — structurally one dictation at a time. Our ADR-0009 run queue and
deferred ordered insertion have no counterpart on the normal path. The one place upstream does
order concurrent results is long-form segmentation (#170), and it does it correctly: indices
assigned under `segmentMutex` at cut time, results drained strictly in order by
`segmentCommitIndex`, and failed cuts consume their index so the drain never stalls.

## 4. Assessment — is this feature still sensible?

**Does the user need it?** Yes, unambiguously, and more than most clusters here. This is a
heavy-dictation workflow: long recordings, phone in hand, calls and notifications arriving
mid-thought. "The recording captured my phone call" and "the recording silently ended when the
process died" are not polish complaints, they are data loss. Unlike the widget or the history
panel, nothing about this cluster is discretionary — it is the difference between a tool you can
trust with a five-minute dictation and one you cannot.

**Would a 5.3 user miss it?** Partly. They would gain better Bluetooth routing and a screen-off
catch-all. They would lose: pausing when a call arrives (the single most common interruption),
any reaction to a mid-recording SCO drop, crash-survivable audio, and the ability to start the
next recording while the previous one is still processing. On balance a 5.3 user is *less*
protected, but they would only notice the audio-focus gap the first time the phone rang.

**What does keeping it cost?** Very little, which is unusual for this fork. The wave is ~600
production insertions across ten files; the two classifiers are pure functions with dedicated
tests; the coupling is to infrastructure (state store, FGS, info-bar) that exists for a dozen
other reasons anyway. This is one of the cheapest things in the repo to keep.

**Upstream signal.** No decision against any of this — the gaps read as unbuilt rather than
rejected, which is a different situation from screen-context capture or cost tracking. And
`0ace1f8d` (#167, `add-voice-communication-source`) shows the maintainer merging an
externally-authored *audio-source* feature and then extending it the same day. The audio path
is demonstrably an area where outside contributions have landed.

**The counter-argument, honestly stated.** Our audio-focus design has a known blind spot of its
own: because we refused `READ_PHONE_STATE`, we cannot distinguish a call from any other
focus-taking app, and we pause for both. Upstream's narrower handling is at least deliberate in
scope. And their `setCommunicationDevice()` path is the API we ought to be using and are not —
we are still on the deprecated call on Android 12+. If we were writing this cluster today, half
of it would be a copy of their `BluetoothMicRouter`.

## 5. Options going forward

**(a) Keep in fork as-is.** Correct default: the cost is negligible and the protection is real.
Worth pairing with two small follow-ups that are ours to fix regardless — adopt
`setCommunicationDevice()` on API 31+ (their `BluetoothMicRouter.activateModern()` is the
reference), and reconsider whether a screen-off producer is worth reinstating for the case where
the process stays alive but the user pockets the phone.

**(b) Port to Dictate Keyboard 5.x as a private patch.** The two classifiers are pure Kotlin
over primitive framework values and would drop into `DictateController`'s focus listener almost
unchanged; the state-machine half would not travel, but it does not need to — upstream can call
`togglePause()` directly. This is a small patch, maybe 60 lines, and it is the same patch as
option (c).

**(c) Propose upstream via issue — the strongest candidate in the fork.** A well-scoped issue
would be titled roughly *"Recording does not pause when a call arrives (AUDIOFOCUS_LOSS_TRANSIENT
unhandled)"* and would contain: the reproduction (start a dictation, receive a call, observe the
transcript containing the ringtone and the conversation), the one-line cause (the listener at
`DictateController.kt:3107` tests only `AUDIOFOCUS_LOSS`), the proposed handling table
(`LOSS`/`LOSS_TRANSIENT` → pause, `CAN_DUCK` → ignore so a notification never pauses a
dictation, `GAIN` → nothing), and the note that this needs no new permission — which matters,
because `READ_PHONE_STATE` in a keyboard is exactly the kind of ask a maintainer would refuse.
A second, separable issue covers the mid-recording SCO drop (`AudioDeviceCallback` +
`onAudioDevicesRemoved` for `TYPE_BLUETOOTH_SCO`) against their own documented teardown gap. A
third could report the `ERROR_DEAD_OBJECT` spin, which is a two-line fix. All three are
bug-shaped, issue-anchored and independent — exactly the profile the upstream contributor
analysis says lands.

**(d) Retire / let upstream replace it.** Not defensible on its own terms. Upstream's equivalent
is weaker in the case that matters most, so retiring ours would be a straight regression.

*Leaning:* keep it, borrow their Bluetooth routing, and file the audio-focus issue — it is the
one place in this whole comparison where we have something upstream visibly needs and can accept
at the size he accepts things.

## 6. Information Gaps

1. **How often a call actually interrupts a dictation in practice.** The feature is justified by
   a correctness argument rather than a measured frequency, and its cost is low enough that this
   barely matters — but it does determine how hard to push option (c). **Owner:** Lukas.
   **Fallback:** assume it is occasional but high-cost when it happens.
2. **Whether the deleted `ScreenStateChanged` producer should come back.** Upstream treats
   screen-off as the dependable catch-all; we deleted it after finding no consumer use case, but
   that was before the widget's `HOVER` origin covered the IME-teardown case. The pocket case
   (screen off, process alive, mic hot) may still be uncovered. **Owner:** design decision.
   **Fallback:** leave it deleted; the mic keeps recording, which is at least not data loss.
3. **Whether pausing on any focus loss is too aggressive.** Without telephony we cannot
   distinguish a call from a video starting in another app. **Owner:** Lukas (observed
   behaviour). **Fallback:** current behaviour — pause and let the user resume.
4. **Whether `setCommunicationDevice()` actually behaves better on the user's hardware.** The
   modern API is right on paper; the deprecated path is what we have field experience with.
   **Owner:** device test. **Fallback:** keep the legacy path behind the existing availability
   gate, which already removed the pathological cost.

## 7. References

**Source reports**
- [`../sources/fork-feature-inventory.md`](../sources/fork-feature-inventory.md) — clusters 14
  (interruption / audio focus / BT-SCO) and 13 (multi-file audio repository)
- [`../sources/upstream-rewrite-analysis.md`](../sources/upstream-rewrite-analysis.md) — area 10
  (recording robustness), area 13 (session persistence)

**Our ADRs / research**
- [`ADR-0003 — Foreground pipeline service`](../../../decisions/0003-service-foreground-pipeline-architecture.md)
  (why the producers live service-side)
- [`ADR-0007 — Audio multi-file repository`](../../../decisions/0007-audio-multi-file-repository.md)
  (rolling segments, continuation)
- [`ADR-0009 — Run queue / serialized concurrency`](../../../decisions/0009-pipeline-run-queue-serialized-concurrency.md)
- Research: [`2026-07-02 - recording-interruption-handling.md`](../../2026-07-02%20-%20recording-interruption-handling.md)
  (F-036, with the full implementation change-history), seeded by
  [`2026-07-02 - feature-wiring-code-review.md`](../../2026-07-02%20-%20feature-wiring-code-review.md)
  (F-007, F-013)
- Plan: [`2026-05-22 - dictate-recording-stack-completion`](../../../plans/2026-05-22%20-%20dictate-recording-stack-completion/)
- Code: `core/InterruptionClassifiers.kt`, `core/BluetoothScoManager.kt`,
  `state/modules/InterruptionModule.kt`, `state/PipelineRecovery.kt`,
  `core/RecordingHardwareAdapter.kt`
- Commits: `69a497ea` (interruption wave), `bc9390b7` (F-000 audio-loss), `59ce0193`
  (`significantSegments`)

**Upstream evidence** (paths @ `upstream/main` `3e5ebe46`)
- `app/.../dictate/audio/BluetoothMicRouter.kt:47-54` (availability probe), `:78-95`
  (`activateModern` / `activateLegacy`)
- `app/.../dictate/DictateController.kt:3096-3116` (audio focus), `:598-604`
  (`canStartRecording`), `:874-895` (screen-off receiver), `:2226` (`stashRecordingOnHide`),
  `:2274` (splice-continue), `:1811-1929` (long-form ordered drain)
- `app/.../dictate/audio/RecordingController.kt:104-107` (pause keeps reading; negative-read
  handling absent)
- Issues: #147 / #111 (interrupted-recording recovery), #170 (long-form segmentation),
  #167 (`add-voice-communication-source`, merged external audio contribution)
