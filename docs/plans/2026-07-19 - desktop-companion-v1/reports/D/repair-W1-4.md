# Repair Wave W1-4 — Report

**Timestamp:** 2026-07-20T00:40:00+02:00
**Agent:** repair-fix (Block D)

## Findings

### logic-D-2 — PcmAmplitude.peak overflows the 0..32767 Android parity range — FIXED

- **File:** `companion/src/main/kotlin/net/devemperor/dictate/companion/capture/PcmAmplitude.kt`
- **Problem:** `abs(sample)` on a full-scale negative 16-bit sample (`-32768`) yields `32768`,
  one LSB above the `0..32767` range Android's `getMaxAmplitude()` produces and the shared
  `AmplitudeProcessor` is tuned against.
- **Fix:** Changed `val magnitude = abs(sample)` to `val magnitude = minOf(abs(sample), 32767)`
  (line 31), clamping the loudest possible sample into the parity range. Added a two-line comment
  explaining why the clamp is needed. Chose the in-peak clamp (over a caller-side coerce) because
  it keeps the invariant local to the function that documents the `0..32767` contract — every caller
  gets the guarantee for free.
- **Regression test:** Added `peak_clampsAFullScaleNegativeSampleToThe32767AndroidRange` to
  `companion/src/test/kotlin/net/devemperor/dictate/companion/capture/WavCodecTest.kt`, feeding a
  single `Short.MIN_VALUE` (-32768) sample and asserting `peak == 32767`. Verified it fails on the
  unfixed `abs`-only code (would return 32768) and passes after the clamp.

## Tests

`./gradlew :companion:test` — BUILD SUCCESSFUL (all green, including the new regression test).

## Files modified

- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/companion/src/main/kotlin/net/devemperor/dictate/companion/capture/PcmAmplitude.kt`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/companion/src/test/kotlin/net/devemperor/dictate/companion/capture/WavCodecTest.kt`

## Skipped findings

None.

## Drift

None — both edits are within the finding's scope (the producer file and its co-located test).
