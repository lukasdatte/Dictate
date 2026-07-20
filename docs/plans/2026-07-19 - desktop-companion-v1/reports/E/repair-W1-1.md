# Repair Wave W1-1 — Block E (logic-E-1)

**Date:** 2026-07-20T13:30:00+02:00
**Agent-ID:** repair-fix W1-1
**Cluster:** logic-E-1 (merged with E-T3, same root cause)

## Finding logic-E-1 — TailscalePeerDiscovery exec hang / orphan leak / stderr deadlock

**What was wrong:** `execTailscaleStatus()` read stdout with `readText()` *before*
`waitFor(5s)`. `readText()` blocks until stdout EOF, so a hung `tailscale` process
never returned and the 5s timeout guard never fired — breaking the kdoc/AC11
"timeout → empty list" promise. Three coupled defects:

1. **Hang:** inline `readText()` outran the timeout; on `Dispatchers.IO` a hang
   starves IO worker threads.
2. **Orphan leak:** on `waitFor == false` the child was never destroyed.
3. **stderr deadlock risk:** `redirectErrorStream(false)` left stderr un-drained,
   so a chatty stderr could fill its pipe and block the child while only stdout was read.

**What I did** (`companion/.../discovery/TailscalePeerDiscovery.kt`):

- stdout is now drained on a **daemon thread** into an `AtomicReference`, so
  `Process.waitFor(TIMEOUT_SECONDS)` owns the deadline instead of `readText()`.
- On timeout: `process.destroyForcibly()` before returning null — no orphan child.
- stderr is discarded at the OS level via `ProcessBuilder.Redirect.DISCARD`, so its
  pipe can never fill; only stdout is drained, so no stdout/stderr deadlock, and JSON
  stays pure (stderr warnings can't corrupt it).
- Success branch does a **bounded `drainer.join(TIMEOUT_SECONDS*1000)`** before
  `output.get()` — closes the race where the process has exited (stdout at EOF) but
  the drainer hasn't run its final `set()` yet, which would otherwise read null.
- Expanded the kdoc on `execTailscaleStatus()` to record *why* the timeout must own
  the deadline (gotcha anchor per engineering-principles).

Companion `jvmTarget = 17`, so `Redirect.DISCARD` (Java 9+) is available.

## Merged E-T3 — real-binary test could stall CI

`realCliBinding_neverThrows_evenWhereTailscaleIsMissing` execs the real binary via the
production default. Now that the production exec self-bounds to `TIMEOUT_SECONDS` (5s)
+ `destroyForcibly`, an added `@Test(timeout = 15_000)` converts any residual hang into
a **test failure**, never an unbounded CI stall. The test keeps its value (it verifies
AC11's no-throw contract against whatever binary the box has).

## Skipped

| ID | Reason |
|---|---|
| (regression test for the hang path) | Skipped — out of scope. The bug lives in the private `execTailscaleStatus()` companion function, which is not injectable (`runCommand` only injects `discover()`'s input, not the exec). A deterministic hang-regression test would require exec-ing a real slow subprocess (e.g. `sleep 30`), which is platform-dependent and itself a CI-stall risk, or refactoring the exec seam for injection — larger than this finding's scope. The primary fix's timeout+destroy is verified indirectly by the now-bounded `@Test(timeout = 15_000)` real-binary test. |

## Tests

`./gradlew :companion:test` — BUILD SUCCESSFUL (full suite; `TailscalePeerDiscoveryTest`
all green).

## Files modified

- `companion/src/main/kotlin/net/devemperor/dictate/companion/catalog/discovery/TailscalePeerDiscovery.kt`
- `companion/src/test/kotlin/net/devemperor/dictate/companion/catalog/TailscalePeerDiscoveryTest.kt`

## Drift

none
