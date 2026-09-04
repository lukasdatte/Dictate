---
date: 2026-09-05
author: Lukas + Claude (research session)
status: Research
context: Four connected questions about working with Dictate Keyboard upstream — how to test the app agentically on this VM, what it would take to make the keyboard shorter on a tablet, where contributions actually belong given the FlorisBoard ancestry, and how to keep our own divergent build alive if PRs do not land.
related-plan: n/a (plan-free research)
related-adrs: ADR-0028
---

# Upstream Collaboration Strategy — Testing, First Feature, Routing, Divergence

Four questions, answered against the upstream tree at `dk/main` (`abd194bc`, v6.1.2,
2026-09-04) and this workstation's actual capabilities. They are connected: the testing
loop is what makes contributing cheap, the keyboard-height change is the first thing to
push through that loop, the routing question decides where it goes, and the divergence
plan decides what happens if it is refused.

## Contents

- [Glossary](#glossary)
- [1. Vision and Motivation](#1-vision-and-motivation)
- [2. Findings + Conclusions](#2-findings--conclusions)
- [3. Agentic testing on the VM](#3-agentic-testing-on-the-vm)
- [4. Making the keyboard shorter](#4-making-the-keyboard-shorter)
- [5. Where contributions belong](#5-where-contributions-belong)
- [6. Divergence: our own rebasable build](#6-divergence-our-own-rebasable-build)
- [7. Talking to DevEmperor](#7-talking-to-devemperor)
- [8. Information Gaps](#8-information-gaps)
- [9. References](#9-references)
- [10. Change History](#10-change-history)

## Glossary

**Baseline screen** — a fixed `DpSize` per form factor in `ImeWindowConstraints.BaselineScreens`.
Every keyboard dimension is a fraction of it, *not* of the real screen.

**Baseline height vs. effective height** — the height stored in preferences is normalised to
4 rows and no Smartbar (`baselineRowCount = 4f`). What you see on screen is
`calcRowHeight(h) * rowCount + calcSmartbarRowHeight(h) * smartbarRowCount`, which is
always larger. Min/max clamping happens in **baseline** units.

**Fixed vs. Floating mode** — upstream's terms for docked and undocked. `ImeWindowMode.Fixed`
has sub-modes NORMAL / COMPACT / THUMBS; `Floating` currently only NORMAL.

**Form factor** — one of six `ImeFormFactor.Type` values guessed from window width and height
via Material's `WindowSizeClass.BREAKPOINTS_V2`. Selects the baseline screen and every
sizing factor.

**Hard fork** — a fork created by copying a source tree into a fresh history rather than by
branching from it. Dictate Keyboard is a hard fork of FlorisBoard; our repo is a *git* fork
of Dictate 3.2 (see [ADR-0028](../../decisions/0028-two-lineage-repository.md)).

**Emulator gRPC bridge** — the emulator's control channel (`-grpc <port>`, default 8554),
which accepts `injectAudio` streams into the virtual microphone. This is how a test speaks
to a dictation app without a physical mic.

**Rebase train** — a maintenance pattern where a small set of squashed feature commits is
repeatedly replayed onto a fast-moving upstream, rather than merged with it.

> **Fixed ≠ docked-only.** "Fixed" means the window is attached to the bottom edge; COMPACT
> and THUMBS are still Fixed. "Floating" is the free-positioned window. Both are constrained
> by the same `BaselineScreens` table, with different factors.

## 1. Vision and Motivation

### 1.1 Why this research exists

Contributing regularly to a codebase you cannot run is guesswork. Every change to a
keyboard is a change to something visual and interactive, and the only feedback loop
available today is "install on the phone and look". That is too slow to sit inside an
implementation loop, and it does not work at all for the form factor that motivates the
first feature — a tablet, which is not the device in reach.

At the same time, the first concrete feature request (a shorter keyboard) needs to be
routed correctly. The app is a fork of FlorisBoard, which raises a reasonable question:
does a keyboard-geometry change belong upstream-of-upstream?

And underneath both sits the strategic question: what if DevEmperor does not want our
changes?

### 1.2 What problem this solves

- **No feedback loop** for upstream work: nothing here can currently run the 6.1.2 app.
- **An unrouted feature**: the height change could be wasted effort in the wrong repository.
- **An unhedged bet**: contributing without a fallback means a refused PR is a dead end.

### 1.3 Discarded alternative

**Testing on the physical phone only.** Rejected: the upstream debug build carries
`applicationId net.devemperor.dictate.debug` — byte-identical to *our fork's* debug id —
so installing one replaces the other on the device you dictate with every day. Beyond the
disruption, a phone cannot produce the tablet form factors that the height work targets.

### 1.4 What this buys us

1. An agent can build, install, drive and screenshot the upstream app without a device.
2. The height feature has an exact, evidence-backed diagnosis and a scoped change.
3. Contributions go to the right repository the first time.
4. A refusal costs a rebase, not a rewrite.

## 2. Findings + Conclusions

| # | Question | Answer |
|---|---|---|
| 1 | Does upstream have agentic test infrastructure? | **Partially.** Real assets exist — gRPC mic injection, a mock realtime server, a uiautomator-driven latency benchmark — but the harness is Windows PowerShell, single-purpose, and has no emulator lifecycle. |
| 2 | Do we have any? | **Yes, the complementary half.** `scripts/e2e/` boots a headless emulator on this VM and activates an IME. It is hardcoded to the fork's package and starts with `-no-audio` and no gRPC port. |
| 3 | Can this VM run it? | **Yes.** KVM present, user in `kvm`, emulator 36.6.11 (supports `-grpc` and `-allow-host-audio`), SDK installed, two AVDs already exist. Tablet device profiles are available. |
| 4 | Why can't the keyboard get smaller? | Minimum height is a **hard-coded fraction of a hard-coded baseline screen**, chosen by form factor. Nothing is user-adjustable and no preference exists. |
| 5 | How much smaller is the floor on a tablet? | Docked tablet-portrait floor is **222.7dp baseline / ≈274dp on screen** with one Smartbar row, vs. 140dp / ≈178dp on a phone. Undocked is *stricter* still (235.8dp baseline). |
| 6 | Do keyboard changes belong in FlorisBoard first? | **No.** Dictate Keyboard is a hard fork — one squashed `Import FlorisBoard base` root commit, zero merges from florisboard, and the sizing code has already deliberately diverged. Changes go to `DevEmperor/DictateKeyboard`. |
| 7 | What is the contribution process? | Issue-first. `README.md` §Contributing says opening an issue is "the best way to help right now"; formal guidelines are explicitly not published yet. No `CONTRIBUTING.md`, no PR template, no CI. |
| 8 | What if a PR is refused? | A **rebase train** on `dk/` — one squashed commit per feature, replayed onto each upstream release. Cheap because our features are small and additive; ADR-0028's structure already supports it. |

**The through-line:** every one of our four questions resolves in favour of *doing the work
in the upstream idiom, in the upstream repository, with a local fallback* — rather than
either porting fork architecture upstream or maintaining a silent private patch set.

## 3. Agentic testing on the VM

### 3.1 What upstream already has

| Asset | What it does | Usable for us? |
|---|---|---|
| `tools/inject_emulator_audio.py` | streams a mono PCM16 WAV into the emulator's virtual mic over gRPC `EmulatorController.injectAudio`, with paced packets and completion markers | **Yes, directly.** Pure Python + grpc, platform-neutral. This is the hard part of testing a dictation app and it is already solved. |
| `tools/mock_realtime_server.py` | a fake OpenAI-realtime endpoint that emits a fixed transcript on a timer, so client behaviour can be tested without a key, a GPU or a model | **Yes, directly.** Removes API keys and network flakiness from the loop. |
| `tools/benchmark_dictation_latency.ps1` | the actual harness: `dumpsys input_method` for readiness, `uiautomator dump` to read the focused field, audio injection, transcript assertion, N runs | **Pattern yes, code no.** PowerShell, Windows paths, and it measures latency rather than asserting behaviour. |
| `app/src/androidTest/` (7 files) | audio-encode, sherpa-onnx spike, dictionary/emoji language coverage | Runs on the emulator, but none of it drives the keyboard UI. |

There is **no emulator lifecycle** upstream — every tool assumes an emulator is already
running and reachable at `localhost:8554`.

### 3.2 What we already have

`scripts/e2e/` (fork lineage, landed today):

- `emulator-up.sh` — installs missing SDK packages on demand, creates the AVD, launches
  headless (`-no-window -gpu swiftshader_indirect -accel on`), **decoupled** via
  `setsid`+`nohup` so a killed agent session does not take the emulator with it, and waits
  for boot.
- `install-and-enable-ime.sh` — installs the APK and activates the IME, discovering the IME
  id from `ime list -a -s` rather than trusting a constant.
- `emulator-down.sh`, `env.sh` — teardown and fully overridable configuration.
- `docs/architecture/e2e-emulator.md` documents the `mInputShown=true` + `mCurMethodId`
  pair as the machine-checkable proof that the keyboard is actually on screen.

The two halves are complementary: we own the lifecycle, upstream owns the audio and the
assertion patterns.

### 3.3 Capability check on this VM

| Requirement | Status |
|---|---|
| KVM | `/dev/kvm` present, user in `kvm` group, 16 CPU threads with vmx/svm |
| Emulator | 36.6.11.0 — `-grpc <port>` and `-allow-host-audio` both supported |
| SDK | `~/android-sdk` with cmdline-tools, platform-tools, emulator, system-images |
| AVDs | `dictate-e2e`, `dictate-perf` already exist |
| Tablet profiles | `medium_tablet`, `pixel_tablet`, `10.1in WXGA (Tablet)` available via `avdmanager` |
| Upstream build | `:app:assembleDebug` succeeds (verified today, 125 MB APK) |

Nothing is missing. This is a configuration job, not a procurement one.

### 3.4 The gap, and the proposal

Four concrete deltas turn what exists into an agent-usable loop:

1. **Generalise `scripts/e2e/` across both lineages.** `env.sh` hardcodes
   `APP_ID=net.devemperor.dictate.debug` and the fork's service class. Upstream's debug
   build uses the *same* application id with a different service class, so the scripts need
   a lineage switch rather than a copy. Keep discovery-by-`ime list` as the primary path.
2. **Turn on the audio channel.** Add `-grpc 8554` and replace `-no-audio` with
   `-allow-host-audio` in `emulator-up.sh` (behind a flag — the perf AVD wants silence).
   Then `inject_emulator_audio.py` works unchanged, against a venv with `grpcio` and the
   emulator's generated stubs.
3. **Add a UI-driving layer** — the piece neither side has as a reusable script: focus a
   field, read it back with `uiautomator dump`, tap by resource-id or coordinates, wait on
   `mInputShown`, screenshot. The PowerShell benchmark is the reference implementation;
   port its four helper functions to bash/Python.
4. **Add tablet AVD profiles.** `dictate-tablet-portrait` and `dictate-tablet-landscape`
   from `pixel_tablet` / `medium_tablet`, so the height work can be seen rather than
   reasoned about. Verify which `ImeFormFactor.Type` each profile actually lands in
   (see §8.2) before trusting a result.

> [!IMPORTANT]
> The upstream debug build and our fork debug build share `applicationId
> net.devemperor.dictate.debug`. They cannot coexist on one emulator, and installing one
> silently replaces the other. Give each lineage its **own AVD** rather than sharing one.

Steps 2–4 are candidates for upstream contribution in their own right: a Linux-capable,
lifecycle-owning harness is exactly the kind of small, self-contained addition a
single-maintainer project benefits from. Step 1 is fork-only glue and stays here.

## 4. Making the keyboard shorter

### 4.1 Diagnosis

All keyboard geometry lives in
`app/src/main/kotlin/dev/patrickgold/florisboard/ime/window/ImeWindowConstraints.kt`.
The minimum height is:

```kotlin
override val minKeyboardHeight by calculation {
    val factor = when (formFactor.typeGuess) { /* six hard-coded fractions */ }
    (baselineScreen.height * factor).coerceAtMost(rootBounds.height)
}
```

Two properties of this make the keyboard un-shrinkable:

1. **The fraction is of `baselineScreen`, not of the real screen.** `BaselineScreens` is a
   fixed table of six `DpSize` values. A user's actual device geometry only ever *caps* the
   result (`coerceAtMost(rootBounds.height)`); it can never lower the floor.
2. **There is no preference.** `AppPrefs.keyboard.windowConfig` persists the *chosen* size
   (`ImeWindowConfig.ByTypeSerializer`), but the min/max envelope it is clamped into is
   compile-time constant. Resizing happens by dragging handles after the
   `TOGGLE_RESIZE_MODE` quick action; `ImeWindowProps.constrained()` clamps every drag into
   `[minKeyboardHeight, maxKeyboardHeight]`.

### 4.2 The actual floor, per form factor

`minKeyboardHeight` is a **baseline** height (4 rows, no Smartbar). On-screen height is
larger: `h/4 * rowCount + calcSmartbarRowHeight(h) * smartbarRowCount`, where the Smartbar
row is `defRowHeight * 0.553 + rowHeight * 0.20`.

| Form factor | Baseline screen | Docked min (baseline) | Undocked min (baseline) | Docked min on screen¹ |
|---|---|---:|---:|---:|
| PHONE_PORTRAIT | 395 × 875 | 140.0 dp | 175.0 dp | ≈ 178 dp |
| PHONE_LANDSCAPE | 835 × 365 | 127.8 dp | 102.2 dp | ≈ 158 dp |
| **TABLET_PORTRAIT** | 800 × 1310 | **222.7 dp** | **235.8 dp** | **≈ 274 dp** |
| **TABLET_LANDSCAPE** | 850 × 800 | **216.0 dp** | **240.0 dp** | **≈ 265 dp** |
| **LARGE_TABLET** | 1335 × 775 | **209.3 dp** | **193.8 dp** | **≈ 257 dp** |
| DESKTOP | 1600 × 900 | 243.0 dp | 225.0 dp | ≈ 298 dp |

¹ 4 rows + 1 Smartbar row, using each form factor's own `defKeyboardHeight`.

Three things fall out of this table, and they match the complaint exactly:

- On a tablet the floor is **~60% higher in absolute dp** than on a phone, in both modes.
- **Undocked is stricter than docked** on both tablet-portrait (235.8 vs 222.7) and
  tablet-landscape (240.0 vs 216.0) — the opposite of the intuition that a free-floating
  window should be freer.
- `LARGE_TABLET` is the only form factor where undocked is looser than docked, so which of
  the three tablet classes a device lands in changes the answer qualitatively.

### 4.3 What a change would look like

Three options, cheapest first:

1. **Lower the tablet fractions.** A three-line change to the `minKeyboardHeight` `when`
   blocks. Trivially reviewable, but it is an unargued taste change to someone else's tuned
   table, and it moves the floor for every user.

2. **A "minimum size" preference that scales the floor.** Add
   `keyboard__min_height_factor` (say 0.5f–1.0f, default 1.0f) and multiply
   `minKeyboardHeight` by it. Keeps existing behaviour as the default, makes the wish
   explicit and opt-in, and is the shape upstream already uses for other knobs. **This is
   the recommended proposal** — it answers the request without asserting that the current
   tuning is wrong.

3. **Make the floor a fraction of the real screen** with the baseline as a fallback.
   Architecturally the most honest fix — it removes the whole class of "the table has no
   row for my device" bugs, of which issue #114 (the zero-height DESKTOP baseline) was one.
   Also the largest change, touching every form factor and the property tests. Worth raising
   in the issue as the direction, not the opening PR.

Whichever is chosen, the property tests in
`app/src/test/kotlin/dev/patrickgold/florisboard/ime/window/ImeWindowConstraintsTest.kt`
must keep holding (`0 <= min <= def <= max` for all non-negative bounds, plus the three
tests added by `afb353bd`). That file is also the template for the tests a PR should add —
DevEmperor's own commit message calls out that the existing property tests could not see
his bug, which tells you what he values in a patch.

> [!TIP]
> `afb353bd` ("Give the widest screens a keyboard that isn't zero pixels tall") is the
> single best model for this contribution: same file, same table, a concrete device
> complaint, a named issue, an explanation of why the existing tests missed it, and new
> tests that would catch it. Match that shape.

### 4.4 Route

File an issue first, describing the tablet case with the numbers from §4.2 and a
screenshot from the tablet AVD. It is a sizing-policy question in someone else's tuned
table — cheap to reject as taste, hard to reject with a measured table and a device.

## 5. Where contributions belong

**Not FlorisBoard.** The evidence:

- Upstream's root commit is a single squashed `266a1c0e Import FlorisBoard base as Dictate
  Keyboard foundation`. There is no FlorisBoard history in the repository.
- `git log --merges` shows no merge from florisboard, ever. There is no sync relationship
  to feed.
- The sizing code has already **deliberately diverged**: the `DESKTOP` baseline comment in
  `ImeWindowConstraints.kt` states outright that the value "deliberately diverges from
  FlorisBoard upstream, which still carries the zero."
- `README.md` §"Built on FlorisBoard" frames FlorisBoard as attribution and license
  provenance (Apache-2.0, `NOTICE`), not as an active upstream.

So a keyboard-geometry change goes to `DevEmperor/DictateKeyboard`, full stop. Sending it
to FlorisBoard would land it in a codebase whose copy of this file is materially different
and whose maintainers have no stake in Dictate's form-factor table.

**The process**, such as it is:

- `README.md` §Contributing: *"The best way to help right now is to open an issue with bug
  reports, ideas or feedback. Full contribution and community guidelines will be published
  as the project matures."* There is no `CONTRIBUTING.md`, no PR template, no `.github`
  workflow, and no CI. Security issues go through GitHub's private advisory form, never a
  public issue (`SECURITY.md`).
- What the merge history actually shows: eight external PRs merged between 2026-07-06 and
  2026-07-31 from three contributors, several within a day. They are feature-sized but
  narrow ("Fix missing first words in realtime dictation", "Speed up OpenRouter dictation by
  22% without forcing IPv4"), and each maps to a specific behaviour.
- Commit style is a house style, not a convention you can guess: one imperative line naming
  the *effect*, no prefixes, no trailers, `(closes #NNN)` for issues. The `dk` worktree
  guardrail carries examples.

The operational form of all this is [`docs/runbooks/upstream-contribution.md`](../../runbooks/upstream-contribution.md).

## 6. Divergence: our own rebasable build

### 6.1 Why this is cheap now and expensive later

Our fork of Dictate 3.2 became unmaintainable-in-principle the moment upstream rewrote the
app: 585 commits against a base nobody else has. The mistake to avoid repeating is
accumulating divergence *shaped like history* rather than *shaped like patches*.

Upstream moved 5.3 → 6.1.2 in four weeks. Anything we keep out-of-tree gets replayed onto
that cadence. The only version of this that survives is one where our delta is small,
squashed, and independently replayable.

### 6.2 The rebase train

Proposed shape, built on ADR-0028's existing structure:

```
upstream/main ──────●──────●──────●──────●────▶   (6.1.2, 6.2, …)
                     \                    \
dk/main               ●  (mirror, ff-only) ●
                       \                    \
dk/dist                 ●─●─●                ●─●─●
                        │ │ │                │ │ │
                        │ │ └ feat: pipeline resilience (squashed)
                        │ └── feat: smaller keyboard      (squashed)
                        └──── feat: e2e harness           (squashed)
```

- **`dk/dist`** is our shippable build: `dk/main` plus one squashed commit per feature, in a
  fixed order. It is never merged into — only ever rebased.
- **One commit per feature, always squashed.** The commit message carries the full rationale
  (what upstream calls a body); the granular history stays on the feature branch that
  produced it, which is archived per `done.sh`. This is the fork convention worth adopting:
  the train's length is the number of *features* we carry, not the number of commits we made.
- **A feature leaves the train when its PR merges upstream.** That is the whole point — the
  train should shrink under success, and it is the visible measure of whether contributing
  is working.
- **Rebase on each upstream release**, not continuously. `scripts/dk/sync.sh` already
  reports what changed; a `dist.sh` would rebase `dk/dist` onto the new `dk/main` and report
  which feature commits conflicted.
- **Conflicts are the signal.** A feature that conflicts every release is one that should
  either be upstreamed properly or dropped. Cheap divergence stays cheap only if we act on
  that.

Each feature is developed exactly as a contribution (§5) — `dk/<topic>`, upstream idiom,
upstream commit style — and only *also* lands on the train. There is no separate "fork
version" of a feature. That symmetry is what keeps a refused PR from becoming rework.

### 6.3 What this is not

It is not a plan to re-create the fork's architecture on 6.x. The August analysis
(§2.5 option B) priced that as a months-long project and it stays out of scope. Item 3 in
the sketch above — pipeline resilience — is deliberately the *last* car on the train and is
addressed in §7.

## 7. Talking to DevEmperor

The strongest argument is the one already made in code: eight months of daily use, 585
commits, 27 ADRs, and a set of problems solved that upstream has not reached yet.

What is worth offering, in the order it is credible:

1. **Land two or three small PRs first.** Credibility here is empirical. The height change
   and the Linux test harness are both good openers: narrow, evidenced, useful to other
   users, and neither asks him to accept an architectural opinion.
2. **File the four issues from the August shortlist** (§2.4 of the fork-vs-upstream
   analysis) — history pagination, mid-recording crash loss, `AUDIOFOCUS_LOSS_TRANSIENT`,
   bubble opacity. These are bug-shaped and carry evidence from a codebase that already
   solved them. Filing them is valuable even if we never write the patches.
3. **Then raise maintenance.** Triage, reproduction, review — the work a single-maintainer
   project runs out of first. This is a much easier ask than commit access and it is what
   actually helps him.
4. **Only then discuss the pipeline.** Our background-processing engine — the guarantee that
   transcription survives a keyboard switch and that nothing is lost — is the fork's most
   valuable idea and the least portable. Upstream runs a ~3,200-line process-wide
   `DictateController` object with in-memory state; ours is a foreground-service-hosted,
   persist-first pipeline with insert-only session/step rows. It cannot be transplanted.
   What can travel is the *guarantee*, re-implemented in his idiom, and probably in pieces:
   crash-safe audio first (the WAV header is only patched in `stop()`, so a mid-recording
   crash loses the recording — that is issue #2 on the shortlist and it is the thin end of
   exactly this wedge).

> [!NOTE]
> Sequencing matters more than content here. A first contact that opens with "we would like
> to port our pipeline architecture" reads as a takeover of a codebase he is the sole
> architect of. The same conversation after three merged PRs and four useful issues reads as
> a collaborator with a track record.

## 8. Information Gaps

1. **Whether the emulator's mic injection actually reaches the app on this VM** is
   unverified — the gRPC path is documented and the flags exist, but it has never been run
   here. Owner: first run of the harness. Fallback: `mock_realtime_server.py` still tests
   the client end without audio.
2. **Which `ImeFormFactor.Type` each AVD tablet profile resolves to** is computed, not
   observed. The breakpoints are width ≥600 & height ≥900 → TABLET_PORTRAIT; width ≥840 &
   height ≥480 → TABLET_LANDSCAPE; width ≥1200 → LARGE_TABLET; width ≥1600 → DESKTOP — but
   the dp values depend on the profile's density. Owner: whoever creates the AVDs. Fallback:
   log `formFactor.typeGuess` from a debug build.
3. **Which form factor Lukas's own tablet lands in** is unknown, and §4.2 shows the three
   tablet classes differ qualitatively. Owner: Lukas. Fallback: propose the change for all
   three tablet classes.
4. **Upstream's appetite for a preference vs. a retuned constant** (§4.3 options 2 vs. 1) is
   unknown. Owner: the issue. Fallback: propose the preference, offer the constant.
5. **Whether DevEmperor wants collaboration at all** is untested. Owner: §7 step 1.
   Fallback: the rebase train (§6) makes a "no" survivable.
6. **The effort of re-implementing pipeline resilience on 6.x** has never been costed, at
   any granularity. Owner: a follow-up plan, only if §7 step 4 gets a positive signal.

## 9. References

- **ADR:** [`ADR-0028 — Two Unrelated Lineages in One Repository`](../../decisions/0028-two-lineage-repository.md)
- **Runbook:** [`upstream-contribution`](../../runbooks/upstream-contribution.md)
- **Prior research:** [`fork-vs-upstream-feature-analysis`](../2026-08-11%20-%20fork-vs-upstream-feature-analysis/fork-vs-upstream-feature-analysis.md)
  — adoption matrix, the four-issue shortlist (§2.4), fork-level options (§2.5)
- **Our test infrastructure:** [`e2e-emulator`](../../architecture/e2e-emulator.md), `scripts/e2e/`
- **Upstream code read for this document** (all at `dk/main` = `abd194bc`):
  - `app/src/main/kotlin/dev/patrickgold/florisboard/ime/window/ImeWindowConstraints.kt` — baselines, min/max/def factors
  - `.../ime/window/ImeWindowSpec.kt:100-115` — `toEffective` / `toBaseline`
  - `.../ime/window/ImeWindowProps.kt:83-88, 139-143` — where the clamp happens
  - `.../ime/window/ImeFormFactor.kt:61-85` — form-factor breakpoints
  - `.../app/AppPrefs.kt:1236-1240` — `keyboard__window_config`
  - `tools/inject_emulator_audio.py`, `tools/mock_realtime_server.py`, `tools/benchmark_dictation_latency.ps1`
  - `README.md` §"Built on FlorisBoard", §Contributing; `SECURITY.md`
  - commit `afb353bd` — the model contribution for §4
- **FlorisBoard:** https://github.com/florisboard/florisboard (attribution only; not an active upstream)

## 10. Change History

- **2026-09-05** — Initial version. Written against upstream `abd194bc` (v6.1.2) after the
  `dk/` lineage was established (ADR-0028) and `worktrees/dk/main` was confirmed to build.
