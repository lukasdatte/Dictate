# ADR-0015: Companion Monorepo Topology — shared/ (JVM) + companion/ (Compose Desktop)

**Status:** Accepted
**Subsystem:** build, architecture
**Scope:** Project-Wide
**Date:** 2026-07-14
**Supersedes:** —
**Author:** Lukas + Claude Code

## Research

The Windows-Dispatch work package needed a desktop companion that receives dictated text from the phone. Where that companion lives, and how the two apps share a wire protocol, was settled during the plan's build-topology analysis (`tmp/plan-windows-dispatch.md` §2 "Modul-Topologie & Build", and the ADR-0015 row at line 482). The load-bearing findings:

- **`:app` compiles at `jvmTarget = '1.8'`** — `app/build.gradle:38` (`jvmTarget = '1.8'`), and there is no `jvmToolchain` anywhere in the project. Kotlin refuses to inline bytecode built at a higher JVM target into bytecode built at a lower one (*"Cannot inline bytecode built with JVM target 11 into bytecode being built with JVM target 1.8"*). Any module `:app` consumes must therefore stay at 1.8 — or `:app` itself must be raised, which changes the shipped APK.
- **The Ktor server requires JDK 11+.** A shared module pinned to jvmTarget 1.8 cannot host it — which aligns with the binding rule "Desktop = the only server" (ADR-0017). The server therefore lives only in `companion/`.
- **The app pins `kotlinx-coroutines` to 1.7.3;** Ktor 3 wants 1.10+. Making the shared client blocking OkHttp on a background executor (the house pattern of `ai/runner/ElevenLabsTranscriptionRunner.kt` — `client.newCall(req).execute().use { … }`, cancel via `Thread.interrupt()`) removes coroutines from the shared module entirely, so this version conflict never materialises.
- **Kotlin is pinned at 2.1.20** (`gradle/libs.versions.toml:3`) with KSP `2.1.20-1.0.32` (line 4). The Kotlin compiler rejects metadata produced by a newer Kotlin, so every added library must be built with Kotlin ≤ 2.1.20 — the version matrix in §2.2 was verified against Maven Central on 2026-07-13.

As-built evidence (Blocks 1-3, green):

- `settings.gradle:24-27` — `include ':shared'` and `include ':companion'` with a header comment naming this ADR.
- `shared/build.gradle:12-20` — `kotlin("jvm")` + serialization plugins, `JavaVersion.VERSION_1_8`, `JvmTarget.JVM_1_8`; dependencies (lines 24-33) are `api kotlinx-serialization-json`, `api konform`, `implementation okhttp` — no Ktor, no coroutines.
- `companion/build.gradle:26-29` — `JavaVersion.VERSION_17` / `JvmTarget.JVM_17`.
- `shared/src/test/kotlin/net/devemperor/dictate/shared/SharedPurityTest.kt:27-32` — the invariant test forbids imports of `android.`, `androidx.`, `kotlinx.coroutines`, and `io.ktor` in the shared source tree, and (lines 62-71) proves the scanner actually reads files and matches.
- `gradle/libs.versions.toml` — `composeMultiplatform = "1.8.2"` (line 52), `ktor = "3.1.3"` (55), `kotlinxSerialization = "1.8.0"` (56), `konform = "0.11.1"` (59), `okhttp = "4.12.0"` (62), `sqldelight = "2.1.0"` (64).

## Context

Dictate is an Android IME. Windows-Dispatch adds a second, non-Android artefact — a desktop companion that receives dictated text over the local Tailnet and types it into the focused window. Two apps now share one wire protocol (DTOs + validation), and the phone and PC must never drift apart on that protocol.

The problem is topological: **where does the companion's code live, and how is the shared contract expressed so both apps compile against the exact same source?** The constraints are the ambient toolchain — Gradle 8.14.3, AGP 8.13.2, Kotlin 2.1.20, Groovy DSL, `:app` frozen at jvmTarget 1.8 — plus a hard requirement that the app's existing coroutines line (1.7.3) and its Room/KSP codegen stay untouched, because this work package must not regress the shipping keyboard.

## Decision

Add **two new Gradle modules to the existing repository** (a monorepo), not a Kotlin Multiplatform structure and not a separate repository:

1. **`shared/`** — a pure `kotlin("jvm")` module at **jvmTarget 1.8**, consumed by *both* `:app` and `:companion`. It holds the wire protocol (the `@Serializable` DTOs and their Konform `Validation<T>`s — ADR-0016). It is:
   - **Android-free** — no `android.jar` on its classpath, so the compiler already blocks Android APIs; an architecture-invariant test (`SharedPurityTest`) additionally pins it against `android.`/`androidx.` imports.
   - **Ktor-free** — the server needs JDK 11+ and belongs to the companion alone (ADR-0017). Pinned by the same test (`io.ktor`).
   - **Coroutine-free** — the HTTP client is **blocking OkHttp on a dedicated background executor**, the house pattern from `ElevenLabsTranscriptionRunner`. This keeps `:app`'s `kotlinx-coroutines` 1.7.3 line completely untouched. Pinned by the same test (`kotlinx.coroutines`).

2. **`companion/`** — a Compose Multiplatform Desktop application on **JVM 17**, `implementation project(':shared')`. It is the only server (Ktor CIO), owns SQLDelight persistence, and hosts the Win32 text insertion via JNA. Its higher jvmTarget is fine because nothing on Android consumes it.

`:app` gains exactly one line of coupling — `implementation project(':shared')` (`app/build.gradle:92`) — plus the ZXing QR-scan library for pairing (line 102).

### Binding version policy

**Kotlin stays at 2.1.20.** From that follows a hard ceiling: **no library built with Kotlin > 2.1.20**, because the Kotlin compiler rejects newer metadata. Concretely, this pins the second-newest line of each library rather than the latest:

| Library | Pinned | Latest line rejected |
|---|---|---|
| Compose Multiplatform Desktop | **1.8.2** | 1.9+/1.10+/1.11+ require Kotlin 2.2+ |
| Ktor (companion only) | **3.1.3** | 3.2.x+ built with Kotlin 2.2 |
| SQLDelight | **2.1.0** | 2.2.x built with Kotlin 2.2 |
| kotlinx-serialization | **1.8.0** | 1.9+ built with newer Kotlin |

The Kotlin 2.2.x bump (which would also drag KSP forward and force `:app` to jvmTarget 11) is a **deliberate, deferred follow-up package** — it touches the entire existing app (Room/KSP codegen, 209 test files) and is a risk unrelated to Windows-Dispatch.

### Scope of this Convention

This is a Project-Wide ADR because it defines a repository-wide module boundary and a repository-wide version ceiling.

- **Applies to:** the module topology (`:app` / `:shared` / `:companion`), the `:shared` purity rules (no Android, no Ktor, no coroutines), the jvmTarget alignment between `:app` and `:shared`, and the "no library built with Kotlin > 2.1.20" ceiling on every module in the repo.
- **Exempt:** `companion/` may use JVM 17 and coroutines/Ktor transitively (it is never consumed by Android). The version ceiling is *not* exempt for the companion — the Kotlin-metadata rejection is compiler-wide.

## Alternatives Considered

1. **Kotlin Multiplatform (`commonMain` / `jvmMain`).** One source set, with a path to iOS/JS later. Rejected: the KMP plugin would be applied across the whole project, adding metadata compilation, `kotlin.mpp.*` flags, and a markedly more fragile toolchain — pure ceremony for **two JVM consumers**. There is no non-JVM target in sight, so KMP buys nothing this package needs. The user explicitly excluded KMP; the technical justification is recorded here.

2. **A separate repository for the companion.** Clean process isolation. Rejected: two repositories means the wire protocol is duplicated or vendored, and **protocol drift between phone and PC is exactly the failure the schema-as-single-source-of-truth design (ADR-0016) exists to prevent.** A monorepo lets both apps compile against the *same* `shared/` source; a schema change that breaks one side fails the build immediately instead of drifting silently until runtime.

3. **Bump Kotlin to 2.2.x now and take the newest line everywhere.** Would let the project use CMP 1.11.x, Ktor 3.5.x, SQLDelight 2.2.x. Rejected for this package: it touches the entire existing app — Room/KSP codegen must move in lockstep, `:app` would have to go to jvmTarget 11 (a behaviour change to the shipped APK), and all 209 test files ride along. That is its own risk with nothing to do with Windows-Dispatch, so it is deferred to a dedicated follow-up rather than smuggled in here.

## Consequences

**Positive:**
- One repository, one build, one source of truth for the wire protocol — `:app` and `:companion` compile against the same `shared/` classes, so protocol drift is a compile error, not a runtime surprise.
- `:app`'s toolchain is untouched: no coroutines bump, no jvmTarget change, no KSP move. The shipping keyboard carries zero regression risk from this package.
- The `:shared` purity is machine-enforced (`SharedPurityTest`), so the module cannot silently grow an Android, Ktor, or coroutine dependency that would break `:app` or the desktop consumer.
- The companion runs on a modern JVM (17) with the full Compose Desktop / Ktor / SQLDelight stack, unconstrained by Android's 1.8 floor.

**Negative:**
- The whole repo runs on the **second-newest line of every shared library** (CMP 1.8.2, Ktor 3.1.3, SQLDelight 2.1.0, kotlinx-serialization 1.8.0) for as long as Kotlin stays at 2.1.20 — the price of not bumping Kotlin.
- The **Kotlin 2.2.x upgrade is deferred debt.** It is now a distinct future package (Kotlin + KSP + `:app` → jvmTarget 11 + 209 test files), and the longer it waits the more library lines fall behind.
- `shared/` carries a self-imposed constraint (jvmTarget 1.8, no coroutines) that is stricter than the JVM otherwise requires — a contributor writing shared code must remember they cannot reach for coroutines or Ktor there.

**Failure Modes:**
- **Raising `shared/`'s jvmTarget without raising `:app` breaks the build for the next person who touches `:app`** — Kotlin refuses to inline 11-bytecode into 8-bytecode, and the error surfaces at `:app` compile time, far from the `shared/build.gradle` edit that caused it. The inline comment at `shared/build.gradle:6-11` and `SharedPurityTest` are the guardrails; the jvmTarget coupling itself is not test-covered, only commented.
- **Adding a library built with Kotlin > 2.1.20 fails with a metadata-rejection error** that names the library, not the version ceiling — a contributor unaware of the policy can waste time before realising the fix is "pick the older line." The version-catalog comments (`libs.versions.toml`) name ADR-0015 next to each pinned line to shorten that hunt.
- A coroutine or Ktor import can only enter `shared/` if someone also disables or deletes `SharedPurityTest`; the test is the single point of enforcement, so its `theTestItself_findsAViolationWhenThereIsOne` self-check (guarding against a scanner that silently reads nothing) is load-bearing.

## References

- **Related Plan:** the Windows-Dispatch work package plan (`tmp/plan-windows-dispatch.md` §2, ADR-0015 row at line 482) — pending archival to `docs/plans/`. Motivated and is implemented by this ADR.
- **Related ADRs:**
  - ADR-0016 — the wire-protocol DTOs and Konform validation live in this `shared/` module.
  - ADR-0017 — the OkHttp client (phone) and Ktor server (companion) whose split this topology hosts; "Desktop = the only server" is why Ktor is excluded from `shared/`.
  - ADR-0003 — the `:app` side depending on `shared/` (PIPELINE-origin / FGS persistence) consumes this module boundary.
- **Implementation:** `settings.gradle:24-27`, `shared/build.gradle`, `companion/build.gradle`, `app/build.gradle:92,102`, `gradle/libs.versions.toml`.
- **Test suite:** `shared/src/test/kotlin/net/devemperor/dictate/shared/SharedPurityTest.kt`.

## Decision History

### 2026-07-14 — Initial proposal

**Trigger:** The Windows-Dispatch work package required a desktop companion and a wire protocol shared with the Android app. The build-topology analysis (plan §2) had to settle where the companion lives and how the shared contract is expressed without regressing the shipping keyboard.

**Before:** The repository held a single Android module, `:app`, compiling at jvmTarget 1.8 with `kotlinx-coroutines` pinned to 1.7.3 and Kotlin 2.1.20. There was no shared code and no second consumer of the protocol.

**After:** Two new Gradle modules — `shared/` (pure `kotlin("jvm")`, jvmTarget 1.8, Android-/Ktor-/coroutine-free, enforced by `SharedPurityTest`) and `companion/` (Compose Desktop, JVM 17) — in the same repository. Binding version policy: Kotlin stays 2.1.20, no library built with Kotlin > 2.1.20 (CMP 1.8.2, Ktor 3.1.3, SQLDelight 2.1.0, kotlinx-serialization 1.8.0). The Kotlin 2.2.x bump is a deferred follow-up.

**Reasoning:** A monorepo makes the shared wire protocol a single compiled source both apps build against, so protocol drift is a compile error — the exact drift a separate repo would reintroduce. KMP was rejected as pure ceremony for two JVM targets with no non-JVM target in sight. The blocking-OkHttp client keeps `shared/` coroutine-free, which severs the app's 1.7.3 coroutines line from Ktor's demands and lets `:app` ship untouched. Freezing Kotlin at 2.1.20 accepts running one library line behind in exchange for not dragging the entire existing app (Room/KSP, 209 tests, jvmTarget 11) through an upgrade unrelated to Windows-Dispatch.
