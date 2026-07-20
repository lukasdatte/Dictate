# `:companion` — Dictate Desktop Companion

The **Compose Desktop** companion to the Dictate Android keyboard (JVM 17). It plays
two roles: a passive **dispatch receiver** (the phone records, the companion types
into the focused Windows window) *and* a standalone **dictation host** (the companion
records, runs the shared AI pipeline, and inserts locally — no phone required). It is
also a **peer** in the configuration-sharing catalog and can run **headless** as an
always-on hub.

> **Governed by ADRs** (`docs/decisions/`) — these are the load-bearing decision
> records; this README is the orientation guide for the module's current shape and
> where to look. The fachliche SSoT for each subsystem is its spec under
> `docs/plans/2026-07-19 - desktop-companion-v1/research/`.
>
> - **ADR-0015** — monorepo topology + Kotlin ≤ 2.1.20 ceiling.
> - **ADR-0031** — desktop dictation host (slim own orchestrator, javax.sound capture).
> - **ADR-0032** — frameless focus-free mini-panel + global hotkey behind a port.
> - **ADR-0033** — desktop review mode incl. re-dictate (shared `ReviewDecision`).
> - **ADR-0034** — peer-catalog family (pull-only sharing, envelope credentials, headless peer).
> - **ADR-0035** — SQLDelight session-schema parity + `received_texts` retirement.
> - **ADR-0017 / ADR-0018 / ADR-0020 / ADR-0023 / ADR-0025** — roles/pairing, text
>   insertion, session sync, bind address, additive endpoints (the dispatch foundation).
> - **ADR-0028 / ADR-0029 / ADR-0030** — the `:shared-ai` AI core, `SecretStore`, and
>   the configuration entity model this module consumes.

## Overview

| Subsystem (`.../companion/`) | Responsibility |
|---|---|
| `capture/` | javax.sound WAV 16 kHz mono recording, device catalog, rolling segments, amplitude feed (ADR-0031). |
| `pipeline/` | `DesktopDictationController` — the one dispatch door, pure reducer over four axes (recording / pipeline queue / review / panel), effects, serial job queue (ADR-0031/0033). |
| `hotkey/` | `GlobalHotkey` port — Win32 `RegisterHotKey` impl on its own message loop + Noop fallback (ADR-0032). |
| `ui/` | Compose surfaces — the mini-panel (`ui/panel`, warm focus-free HUD), management screens (`ui/config`, `ui/devices`, `ui/history`, `ui/pairing`), and peer/profile/prompt editors. |
| `server/` | Ktor CIO server — dispatch, sync, and catalog routes (ADR-0017/0020/0034). |
| `catalog/` | Peer-catalog sync scheduler + discovery port (manual/QR + Tailscale enumeration). |
| `data/` | SQLDelight persistence — session archive (Room parity), config entities, peers/subscriptions (ADR-0035/0030/0034). |
| `secrets/` | `SecretStore` desktop impls — Windows DPAPI + POSIX-`0600` file fallback (ADR-0029). |
| `ai/` | Companion-side implementations of the `:shared-ai` ports (`AiConfig`, `UsageSink`, `ProxyConfig`) + profile-backed config resolution. |
| `platform/` | OS seams — paths, network interfaces, single-instance guard, clock, `PlatformModule.detect()`. |
| `domain/` | Auth, pairing, catalog, dispatch services + `CompanionSettings`, `FocusRestorationPolicy`. |
| `Main.kt` | Entry point (delegates start-up to `CompanionBootstrap`); `--minimized` starts straight into the tray (autostart), `--headless` runs the full server/persistence/catalog stack without Compose (ADR-0034, F8). |

## Module Layout

```
companion/src/main/kotlin/net/devemperor/dictate/companion/
├── Main.kt                 entry point (--minimized / --headless flags)
├── CompanionBootstrap.kt   start-up sequencing (db open, bind resolve, server start)
├── CompanionContainer.kt   composition root (wires ports + services)
├── capture/                audio capture + amplitude
├── pipeline/               DesktopDictationController + reducer + queue
├── hotkey/                 GlobalHotkey port (Win32 + Noop)
├── ui/                      App.kt + panel + management screens
├── server/                 Ktor routes (dispatch / sync / catalog)
├── catalog/                sync scheduler + discovery
├── data/                    SQLDelight repositories + SchemaMigrator
├── secrets/                DPAPI + file-fallback SecretStore
├── ai/                      :shared-ai port implementations
├── platform/               OS seams + PlatformModule
├── domain/                 auth / pairing / catalog / dispatch services
└── cli/                    PairCli (pairing helper)

companion/src/main/sqldelight/.../db/
├── Companion.sq            schema (Room-parity, ADR-0035)
└── migrations/{1..4}.sqm   1=key-command chords, 2=parity+dispatch_state, 3=config entities, 4=peers
```

> `1.sqm` (key-command chords) is owned by the keyboard-action-engine plan;
> desktop-companion-v1's own migration allocation begins at `2.sqm`.

## Build, Run, Test

```bash
./gradlew :companion:run                     # launch the desktop app (Compose)
./gradlew :companion:run --args="--headless" # run as a headless hub peer
./gradlew :companion:test                    # unit tests
./gradlew :companion:verifySqlDelightMigration  # schema migration check
./gradlew build                              # full multi-module build
```

`Main.kt` (`net.devemperor.dictate.companion.MainKt`) is the app entry; `cli/PairCli.kt`
(`...companion.cli.PairCliKt`) is a pairing helper. Packaging targets (MSI/DEB) are
declared in `companion/build.gradle`.

## Key Conventions

- **Ports, not platform calls.** OS- and SDK-specific behaviour sits behind ports with
  an `available` capability flag (`GlobalHotkey`, `TextInserter`, `SecretStore`,
  `PeerDiscovery`), selected by `PlatformModule.detect()`. Windows is first-class; Linux
  is dogfooding (Noop hotkey + clipboard-and-button). The desktop supplies its own
  implementations of the four `:shared-ai` ports.
- **Secrets only through `SecretStore`** — DPAPI on Windows, `0600` file fallback
  elsewhere; a `get` distinguishes `null` from `DecryptionFailed` (ADR-0029). Never read
  a raw secret from settings.
- **Reducer purity.** The pipeline reducer is `(state, intent) → (state, effects)` with
  **no I/O** — clock, UUID, capture, AI calls, persistence, and insertion all live in
  effect handlers (ADR-0031). A reducer reaching for `System.currentTimeMillis()` breaks
  reproducibility.
- **SQLDelight parity is a build gate.** The session and config schemas mirror Room
  exactly; parity tests fail the build on drift. See
  `docs/DATABASE-PATTERNS.md` §"SQLDelight Parity (Companion)".
- **One shared archive.** Phone-synced and desktop-recorded sessions live in one
  `sessions` table, separated by `origin` (F16). Peers are **never** a dictation store.
- **AI behaviour is shared, not re-implemented.** Transcription, post-processing, and the
  `ReviewDecision` verdict come from `:shared-ai` verbatim, so phone and desktop cannot
  drift (ADR-0028/0033).

## Cross-Refs

- **Specs (SSoT):** `docs/plans/2026-07-19 - desktop-companion-v1/research/{desktop-host,peer-katalog,secretstore,entitaetenmodell-android,shared-ai-extraktion}.md`.
- **Plan:** `docs/plans/2026-07-19 - desktop-companion-v1/desktop-companion-v1.md`.
- **Conventions:** `docs/DATABASE-PATTERNS.md`, repo-root `CLAUDE.md`.
- **Sibling modules:** `:shared` (wire + entities), `:shared-ai` (AI core + ports), `:app` (Android keyboard).
