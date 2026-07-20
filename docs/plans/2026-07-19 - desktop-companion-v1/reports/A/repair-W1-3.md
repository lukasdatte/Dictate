# Repair Wave W1-3 — Block A

**Timestamp:** 2026-07-20T00:40:00+02:00
**Agent role:** repair-fix (validated-findings cluster)

## Findings

### A-TEST-2 (green / Nice-to-have) — FIXED

**Problem:** `FakeProxyConfig.installAuthenticatorCalls` (FakePorts.kt:18 declaration,
:24 increment) was tracked and incremented in `installAuthenticator()` but never
read/asserted by any test, despite its KDoc promising proxy-path tests could assert
the runner honoured the no-proxy case — a dead affordance.

**Root cause of the gap:** the only caller of `installAuthenticator()` is
`ElevenLabsTranscriptionRunner.buildClient()`, which was `private` and reachable only
through the live-network `transcribe()` path, so no unit test could exercise it.

**Fix (D4 — add the intended coverage rather than delete the affordance):**

1. `shared-ai/src/main/kotlin/net/devemperor/dictate/ai/runner/ElevenLabsTranscriptionRunner.kt`
   — changed `buildClient()` from `private` to `internal`, mirroring the already-`internal`
   `buildMultipartBody()`, with a KDoc explaining the testability rationale (proxy branch
   is where proxy handling regresses silently).

2. `shared-ai/src/test/kotlin/net/devemperor/dictate/ai/runner/ElevenLabsTranscriptionRunnerTest.kt`
   — added two tests that read `installAuthenticatorCalls`, covering both branches:
   - `no proxy configured leaves the client unproxied and skips the authenticator`
     — asserts `client.proxy == null` and `installAuthenticatorCalls == 0`.
   - `resolved proxy is applied to the client and installs the authenticator`
     — asserts the resolved `Proxy` is applied to the okhttp client and
     `installAuthenticatorCalls == 1`.

   The counter is now a live assertion target, and the runner's proxy-wiring branch
   gains genuine coverage. Added imports: `assertNull`, `assertSame`,
   `java.net.InetSocketAddress`, `java.net.Proxy`.

## Tests

`./gradlew :shared-ai:test` — BUILD SUCCESSFUL. Both new test cases confirmed executed
via the JUnit XML report; full ElevenLabs suite green.

## Files modified

- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/shared-ai/src/main/kotlin/net/devemperor/dictate/ai/runner/ElevenLabsTranscriptionRunner.kt`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/shared-ai/src/test/kotlin/net/devemperor/dictate/ai/runner/ElevenLabsTranscriptionRunnerTest.kt`

## Drift

none — the counter left in FakePorts.kt is now the assertion target, so no edit to
that file was needed; the fix stayed within the finding's scope (test coverage + the
minimal visibility change enabling it).
