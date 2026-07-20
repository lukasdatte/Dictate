# ADR-0028: `:shared-ai` — A Fourth Pure-JVM Module for the AI Core Behind Platform Ports

**Status:** Accepted
**Scope:** Project-Wide
**Date:** 2026-07-20
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0015.** That ADR owns the monorepo topology
> (`:app` / `:shared` / `:companion`), the `jvmTarget 1.8` alignment, and the
> "no library built with Kotlin > 2.1.20" ceiling. This ADR adds a **fourth**
> module inside that topology and reuses ADR-0015's purity-test mechanism for
> its own boundary.

> **Plain-language summary.** Dictate's speech-to-text and text-rewording
> engine — the "AI core" — currently lives inside the Android app module
> (`:app`). To let the new desktop companion run the *same* pipeline instead of
> a re-implementation, we lift that engine into a new Gradle module, `:shared-ai`,
> that both the phone and the desktop depend on. The module is **Android-free**
> (so the desktop can use it), keeps the **same package name** `net.devemperor.dictate.ai`
> (so the extraction is almost purely build-file edits, not thousands of import
> changes), carries its **own dependency policy** (the AI SDKs are allowed here,
> Android and the server stack are not), and reaches back to each platform through
> four small interfaces called **ports** (config, usage tracking, proxy, audio
> duration). Jargon on first use: a **port** is an interface the core defines and
> each platform implements; a **purity test** is a unit test that scans the
> module's source for forbidden imports and fails the build if any appear.

## Research

- **`:shared-ai`-extraction spec** (`docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md`):
  §3 is the exhaustive `ai/` inventory (roots, enums, `factory/`, `model/`,
  `runner/`, `prompt/`, `conversation/`); §3.4 identifies the shared enums that
  must **move with** the core (a critical finding — they are referenced from both
  sides); §3.5 lists the `:app` couplings outside `ai/` that become ports; §4
  gives the four Kotlin port signatures (`AiConfig`, `UsageSink`, `ProxyConfig`,
  `AudioDurationReader`); §5 specifies the Gradle setup and the `SharedAiPurityTest`
  (including its negative self-test).
- **ADR-0015** (Companion Monorepo Topology) established `:shared` as a pure
  `kotlin("jvm")` module at jvmTarget 1.8 whose invariants are machine-enforced by
  `SharedPurityTest` (`shared/src/test/kotlin/.../SharedPurityTest.kt:27-32`), and
  the compiler rule *"cannot inline bytecode built with JVM target 11 into bytecode
  being built with JVM target 1.8"* that forces any `:app` dependency to stay at 1.8.
- **Concept groundwork** (`.../research/bestandsaufnahme.md` §3 "AI-Layer:
  Plattform-Neutralität") mapped how close the existing `ai/` layer already is to
  platform-neutral, and `.../research/fragenkatalog.md` §F2 recorded the binding
  decision that the desktop client runs the AI pipeline itself via `:shared-ai`
  (not a thin proxy back to the phone).
- **Plan Decision Log** (`.../desktop-companion-v1.md` §3): D1 (this module),
  D5.a (no `:shared-ai`→`:shared` edge), D5.d (`PromptTypeClassifier` stays in
  `:app`), D5.e (`AmplitudeProcessor` moves in, package-preserving).

## Context

Feature decision F2 requires the desktop companion to run Dictate's AI pipeline
locally rather than proxy every transcription back to a paired phone. The pipeline
code (providers, runners, the post-processing conversation, prompt building) lives
entirely in `:app` and therefore on the Android classpath. `:companion` runs on
JVM 17 and cannot consume `android.jar`, so the code has to leave `:app`.

The existing `:shared` module is not a valid home: ADR-0015 deliberately keeps it
**wire-only** — no coroutines, no Ktor, and OkHttp only as an `implementation`
detail, all pinned by `SharedPurityTest`. The AI core, by contrast, *is* the heavy
SDK layer (openai-java, anthropic-java, blocking OkHttp). Putting it in `:shared`
would dissolve the very reason `:shared` is pure. A new module is needed, with a
**different** but equally machine-enforced dependency policy.

## Decision

Introduce a **fourth Gradle module, `:shared-ai`**, a pure `kotlin("jvm")` module
at **jvmTarget 1.8** (R9 — it is consumed by `:app`, so it inherits the 1.8 floor),
consumed by both `:app` and `:companion`.

1. **Package-preserving move.** The extracted code keeps its package
   `net.devemperor.dictate.ai`. The `:app` diff is therefore almost entirely
   `build.gradle` / `settings.gradle` and file relocations, not import rewrites —
   minimising extraction risk and keeping `git log --follow` intact.

2. **Own dependency policy, own purity test.** `:shared-ai` **allows**
   openai-java, anthropic-java, and okhttp; it **forbids** Android (`android.`,
   `androidx.`), Ktor (`io.ktor`), and coroutines. `SharedAiPurityTest` enforces
   this exactly as `SharedPurityTest` enforces `:shared`, and carries the same
   negative self-test proving the scanner actually matches a planted violation.

3. **Four platform ports** are the seam between the core and each host
   (spec §4): `AiConfig` (resolves the API key / base-URL / model config a runner
   needs), `UsageSink` (records token/cost usage after a call), `ProxyConfig`
   (applies an OkHttp proxy), `AudioDurationReader` (reads a clip's duration).
   `:app` supplies Android implementations; `:companion` supplies desktop ones.

4. **No `:shared-ai` → `:shared` edge (D5.a).** The two modules are independent
   leaves. Wire enums stay in `:shared`; domain enums (`AIProvider`,
   `AmbiguityMode`, `AIFunction`) stay in `:shared-ai`; parity between the two
   vocabularies is enforced by tests + mappers in `:app` (the module that sees
   both), never by a module dependency. This is the existing wire-vs-domain
   doctrine (ADR-0016 `SessionOriginWire` ↔ `SessionOrigin`).

5. **Scope corrections carried in the plan** (spec §3.4/§9, plan D5.d/D5.e):
   `PromptTypeClassifier` **stays in `:app`** — it is bound to `PromptType` and the
   16 prompt-pill files, which are deliberately Android-only (ADR-0024).
   `AmplitudeProcessor` **moves into `:shared-ai`** package-preserving (it is pure
   `kotlin.math`, and F19 wants one authoritative amplitude curve shared by both
   recording UIs, not a drift-prone copy).

6. **Two-chunk extraction** (spec §6): **A2** performs verhaltensneutrale pure
   moves (no signature change); **A3** introduces the ports and the runner/orchestrator
   signature changes, with characterization tests written **before** the A3 move.

### Scope of this Convention

Project-Wide because it defines a repository-wide module boundary and a
repository-wide dependency policy.

- **Applies to:** the module topology gaining `:shared-ai`; its purity rules
  (Android-/Ktor-/coroutine-free); its jvmTarget-1.8 alignment with `:app`; the
  Kotlin ≤ 2.1.20 ceiling (ADR-0015) applying to every `:shared-ai` dependency;
  the rule that the AI core reaches platforms only through the four ports.
- **Exempt:** nothing in the ceiling sense (the Kotlin-metadata rejection is
  compiler-wide). `:companion`'s own AI-port implementations may use its JVM-17 /
  coroutine freedoms, since those live in `:companion`, not in `:shared-ai`.

## Alternatives Considered

1. **Host the AI core in `:shared`.** One fewer module. Rejected: `:shared` is
   wire-pure by design (ADR-0015); admitting openai-java/anthropic-java/okhttp-as-api
   there would break `SharedPurityTest` and erase the module's reason to exist. The
   split between *wire purity* (`:shared`) and *SDK weight* (`:shared-ai`) is the
   whole point of adding a module rather than widening one.

2. **Leave the AI core in `:app`; have `:companion` depend on `:app`.** No move at
   all. Rejected: `:app` is an Android module; `:companion` cannot put `android.jar`
   on its classpath, so it literally cannot compile against `:app`. The extraction
   is not optional.

3. **Rename the package during the move** (e.g. `net.devemperor.dictate.sharedai`).
   "Cleaner" module-name/package alignment. Rejected: it would turn a build-file
   change into thousands of import edits across `:app`, inflate the diff and review
   surface enormously, and sever `git log --follow`. Package-preserving is the
   low-risk path; the module name and package name are allowed to differ.

4. **A `:shared-ai` → `:shared` dependency to reuse the wire enums directly.**
   Would avoid the mirror enums. Rejected (D5.a): it introduces exactly the module
   coupling Block A avoids, drags behaviour-bearing domain enums toward the wire
   module, and breaks the package-preserving concept. Parity via test + mapper is
   the established repo pattern.

## Consequences

**Positive:**
- The phone and the desktop run the **identical** AI core — one implementation of
  providers, runners, and the post-processing conversation, so the two platforms
  cannot drift on AI behaviour.
- The `:app` diff is minimal (build files + relocations), so the shipping keyboard
  carries near-zero regression risk from the extraction.
- The module boundary is machine-enforced (`SharedAiPurityTest`), so `:shared-ai`
  cannot silently grow an Android or Ktor dependency that would break `:app` or
  the desktop.
- The four ports keep the core host-agnostic and unit-testable with fakes — no
  Android runtime needed to test a runner.

**Negative:**
- A fourth module and a second purity test to maintain; contributors must know
  which of `:shared` / `:shared-ai` a given piece of code belongs to.
- The Kotlin ≤ 2.1.20 ceiling (ADR-0015) now also constrains **AI-SDK** upgrades:
  a newer openai-java/anthropic-java built with Kotlin 2.2+ cannot be adopted until
  the deferred Kotlin bump lands.
- `:shared-ai` inherits the self-imposed jvmTarget-1.8 / no-coroutine constraint —
  stricter than the JVM requires — because `:app` consumes it.

**Failure Modes:**
- **Raising `:shared-ai`'s jvmTarget without raising `:app`** breaks the build for
  the next person who touches `:app` (Kotlin refuses to inline 11-bytecode into
  8-bytecode); the error surfaces at `:app` compile time, far from the
  `shared-ai/build.gradle` edit that caused it — the same trap ADR-0015 documents.
- **Package-preserving is a boundary footgun.** Because `:app` and `:shared-ai`
  share the package `net.devemperor.dictate.ai`, an `:app` file can reference a
  moved class by simple import and still compile — the boundary is enforced by the
  Gradle dependency *direction* and the ports, not by the package name. A reviewer
  must watch that `:app` reaches the core only through the ports and public API,
  not into internals that merely happen to be visible.
- **`AmplitudeProcessor`'s move (D5.e) leaves `:app` call sites needing a follow.**
  Any Android caller must be repointed; `git log --follow` is required to trace the
  class across the move.

## References

- **Related Plan:** [desktop-companion-v1](../plans/2026-07-19%20-%20desktop-companion-v1/desktop-companion-v1.md)
  — §3 D1/D5.a/D5.d/D5.e, §5 Block A. Motivates and is implemented by this ADR.
- **Spec:** `docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md`
  (§3 inventory, §4 ports, §5 Gradle + `SharedAiPurityTest`, §6 move sequence, §8 tests).
- **Concept:** `docs/plans/2026-07-19 - desktop-companion-v1/research/fragenkatalog.md` §F2;
  `.../research/bestandsaufnahme.md` §3.
- **Related ADRs:**
  - ADR-0015 — the monorepo topology and Kotlin/jvmTarget ceiling this module
    extends. A Decision-History entry is added there at promotion, recording the
    fourth module.
  - ADR-0016 — the wire-vs-domain enum doctrine reused for the enum layering (D5.a).
  - ADR-0024 — prompt-pill types; why `PromptTypeClassifier` stays in `:app` (D5.d).

## Decision History

### 2026-07-20 — Initial proposal (plan-scoped)

**Trigger:** Feature decision F2 (desktop runs the AI pipeline via `:shared-ai`) and
the five implementer specs of the desktop-companion-v1 plan required a concrete home
for the extracted AI core, plus a resolution of the enum-layering and scope questions
(D5.a/D5.d/D5.e).

**Before:** The AI core lived in `:app` on the Android classpath. `:shared` existed
but was wire-pure (ADR-0015); there was no Android-free home for the SDK-heavy AI code
and no second consumer of it.

**After:** A fourth module `:shared-ai` (pure `kotlin("jvm")`, jvmTarget 1.8,
package `net.devemperor.dictate.ai` preserved, Android/Ktor/coroutine-free, enforced
by `SharedAiPurityTest`) holding the AI core behind four platform ports (`AiConfig`,
`UsageSink`, `ProxyConfig`, `AudioDurationReader`). No `:shared-ai`→`:shared` edge;
`PromptTypeClassifier` stays in `:app`; `AmplitudeProcessor` moves in package-preserving.

**Reasoning:** A separate module with its own purity policy keeps wire purity
(`:shared`) and SDK weight (`:shared-ai`) cleanly split while giving the desktop an
Android-free core to depend on. Package-preserving minimises the `:app` diff and
extraction risk. Mirror enums + parity tests reuse the repo's existing wire-vs-domain
doctrine instead of introducing the module coupling Block A avoids.

### 2026-07-20 — Promoted and accepted

**Trigger:** Chunk F1 (Block F) of the desktop-companion-v1 plan — blocks A–E are
implemented; the plan-scoped draft is promoted to a numbered, accepted ADR before
plan archival (§2 criterion 9).

**Before:** Plan-scoped draft `adrs/adr-shared-ai-module.md` with an `NNNN` placeholder and
`Proposed (plan-scoped — pending promotion)` status; sibling ADRs referenced by slug.

**After:** `docs/decisions/0028-shared-ai-module.md`, Status **Accepted**, indexed in
`docs/decisions/README.md`; sibling cross-references resolved to their assigned ADR
numbers. The reciprocal fourth-module note was added to ADR-0015.

**Reasoning:** The decision is active in the codebase across the implemented blocks;
promotion makes it a binding, navigable ADR with bidirectional cross-links.
