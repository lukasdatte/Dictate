# ADR-0028: Two Unrelated Lineages in One Repository — Fork Work and Upstream Contributions Side by Side

**Status:** Accepted
**Scope:** Project-Wide
**Date:** 2026-09-05
**Supersedes:** —
**Author:** Lukas + Claude

> **Plain-language summary.** This repository now holds **two codebases that
> share nothing but a name**. One is our fork: everything on `main`, grown out
> of Dictate 3.2 over 585 commits. The other is the project we forked from,
> which meanwhile threw its own code away and rebuilt the app on a different
> foundation — no shared file, no shared commit. We keep our fork exactly as
> it is, and we add a second, clearly separated lineage (`dk/*`) in the same
> clone so we can contribute small fixes back to that rebuilt project. This
> ADR records how the two are kept apart, and why they live in one clone
> rather than two.

## Research

The decision rests on `docs/research/2026-08-11 - fork-vs-upstream-feature-analysis/`
plus a git- and GitHub-level survey of both repositories on 2026-09-05.

Load-bearing findings:

1. **The two histories have no merge base.** `git merge-base main upstream/main`
   returns nothing. Our fork's root is `ceee2b5a`; the upstream tree's root is
   `266a1c0e` ("Import FlorisBoard base as Dictate Keyboard foundation"). Our
   actual fork point, `2163ba08` (Dictate 3.2, 2026-01-06), survives upstream
   only on the abandoned `legacy-java` branch, whose tip
   (`6f2daf05`, 2026-05-08) is a single gradle bump.
   → *Consequence:* nothing can ever be merged, rebased or cherry-picked
   between the lineages by path. Every transfer is a manual re-implementation.

2. **The build systems are mutually exclusive within one working tree.**
   Fork: Gradle 8.14.3, Groovy DSL, `:app :shared :companion`, Java target 1.8.
   Upstream: Gradle 9.4.1, Kotlin DSL, `:app :wear :lib:{android,color,compose,dictate-core,kotlin,snygg}`,
   Java target 11, AGP 9.2.1, Kotlin 2.3.20.
   → *Consequence:* switching lineages by `git checkout` in one directory
   invalidates the Gradle cache and the IDE index every time. Separate
   working trees are not a convenience, they are a requirement.

3. **Upstream accepts outside pull requests, and lands them fast.**
   `DevEmperor/DictateKeyboard` merged PRs from three external contributors
   between 2026-07-06 and 2026-07-31 (#158, #167, #168, #209, #210, #215,
   #216, #223), several within a day. 22 issues are open, some labelled and
   unassigned.
   → *Consequence:* the contribution path is real, and it is issue-shaped.

4. **`upstream/*` is unusable as a local branch prefix.** Git resolves
   `refs/heads/upstream/main` and `refs/remotes/upstream/main` from the same
   short name and reports `refname 'upstream/main' is ambiguous`.
   → *Consequence:* the local prefix must differ from the remote name.

5. **The GitHub fork relationship is intact.** `lukasdatte/Dictate` still
   carries `parent: DevEmperor/DictateKeyboard` even though its default branch
   shares no history with the parent's. GitHub compares the *branch*, not the
   default branch, so PRs from a correctly based branch produce a clean diff.
   → *Consequence:* no second GitHub fork is needed.

## Context

Until now this repository had one job: carry our fork forward. `main`, the
`feature/*` branches and the `archived/feature/*` history are all one lineage
descended from Dictate 3.2, with 27 ADRs, a plan archive, and conventions
(commit prefixes, plan-scoped ADRs, German working language) built around it.

Upstream did not continue that codebase. It rebuilt the app on FlorisBoard and
now ships 6.1.2 — a superset in typing, provider breadth and on-device STT,
and a different product in everything our fork treats as its core value
(audit-grade persistence, replayable conversations, review-before-insert).

The August analysis left the fork-level question open — continue the fork (A),
migrate to 6.x (B), or drift and re-evaluate (C) — and produced a shortlist of
four upstream issues worth filing. Since then two things changed: upstream went
from 5.3 to 6.1.2 in four weeks, and the intent is now to contribute there
**regularly**, not once.

That makes the open (A)/(B)/(C) question a liability if it has to be answered
first. We need a working arrangement that does not prejudge it: the fork stays
untouched and fully operational, and upstream contributions become routine
rather than a special occasion.

The naive arrangements both fail:

- *Contribute from the fork's branches* — impossible, no merge base.
- *A second clone* — works, but duplicates the object store, splits `gh`
  context, and puts the upstream tree out of reach of a plain `git log` or
  `git grep` from the fork, which is exactly what the reverse-port and
  issue-filing work needs constantly.

## Decision

**One clone, two lineages, separated by branch namespace and by worktree, with
tooling and an agent guardrail enforcing the separation.**

### The two lineages

| | Fork lineage | Upstream lineage |
|---|---|---|
| Branches | `main`, `feature/*`, `archived/feature/*` | `dk/main`, `dk/<topic>`, `archived/dk/*` |
| Root commit | `ceee2b5a` | `266a1c0e` |
| Remote | `origin` (`lukasdatte/Dictate`) | `upstream` (fetch-only), `origin` for PR head branches |
| Working tree | repo root, `worktrees/feature/<name>` | `worktrees/dk/<topic>` |
| Purpose | the product we run | contributions to `DevEmperor/DictateKeyboard` |

`dk` stands for Dictate Keyboard, upstream's product name. The prefix is
deliberately short, and deliberately **not** `upstream/` — see Research §4.

### `dk/main` is a mirror, never a workspace

`dk/main` tracks `upstream/main` and is only ever fast-forwarded. It is checked
out permanently at `worktrees/dk/main` so that upstream code is greppable and
buildable without a checkout dance. Nothing is ever committed on it; every
contribution branches off it. `scripts/dk/common.sh` enforces this — a
non-fast-forward sync aborts with an error rather than creating a merge.

### `upstream` is fetch-only

`remote.upstream.pushurl` is set to the literal string
`DISABLED-no-write-access-to-upstream`, so an accidental
`git push upstream` fails on an unresolvable URL instead of producing a
confusing permission error. PR head branches go to `origin`.

### Scope of this Convention

Applies to **every** branch, worktree, commit and document in this repository.

- Work in the repo root or under `worktrees/feature/` → fork conventions:
  commit prefixes per `~/.claude/snippets/commit-conventions.md`, Claude
  attribution trailers, ADRs, plans, research, German working language.
- Work under `worktrees/dk/` → **upstream conventions**, which contradict the
  fork's in almost every dimension: single-line imperative commit messages with
  no prefix and **no trailers**, no ADRs, no plan files, English only, minimal
  diffs scoped to one issue.
- The two rule sets never mix. Fork documentation *about* upstream work (this
  ADR, the runbook) lives on the fork lineage; upstream *code* never carries
  fork documentation.

Exempt: nothing. The `worktrees/` directory itself is gitignored on the fork
lineage, so no cross-contamination is possible through tracked files.

### Tooling — `scripts/dk/`

| Script | Job |
|---|---|
| `sync.sh [--rebase <topic>…]` | fetch `upstream`, fast-forward `dk/main`, print the new commits, optionally rebase topic branches |
| `new.sh <topic> [issue]` | sync, branch `dk/<topic>` off `dk/main`, add `worktrees/dk/<topic>`, seed `local.properties` and the vendored sherpa-onnx artifacts, drop the agent guardrail |
| `pr.sh <topic> [issue]` | show the outgoing diff, **refuse if any commit carries a Claude trailer**, push to `origin`, open the PR against `DevEmperor/DictateKeyboard:main` |
| `done.sh <topic>` | remove the worktree, rename the branch to `archived/dk/<topic>` |

The trailer check in `pr.sh` is the one hard gate: it is the failure most
likely to happen silently and the most visible to the maintainer if it lands.

### The agent guardrail

`scripts/dk/worktree-CLAUDE.md` is copied to `CLAUDE.md` in every `dk`
worktree and added to `.git/info/exclude`, so it is invisible to git and can
never reach a PR. It states the lineage, tabulates the rules that differ from
the fork, and shows real upstream commit messages as the style reference. The
fork's own root `CLAUDE.md` is *tracked*, so the exclude entry has no effect on
it — tracked files ignore exclude rules.

Without it, an agent that reads the repo root's `CLAUDE.md` — or carries fork
context from an earlier session — will apply Room conventions, ADR
requirements, plan prefixes and Claude trailers to a codebase that wants none
of them.

## Alternatives Considered

1. **A second clone of `DevEmperor/DictateKeyboard`.** The obvious separation:
   two directories, zero chance of confusion, no ambiguous refs. Rejected
   because the daily work is *comparative* — the reverse-port shortlist and
   every upstream issue we file rest on reading both codebases against each
   other. A second clone means a second object store, a second `gh` context,
   no `git log upstream/main` from the fork, and no way to view a fork commit
   and an upstream commit in one `git show`. The separation it buys is already
   achieved by the branch namespace plus worktrees, and git's own refusal to
   merge unrelated histories is a stronger guard than directory distance.

2. **Grafting the lineages onto a shared root** (`git replace`, or a synthetic
   merge with `--allow-unrelated-histories`) so that normal merge/cherry-pick
   tooling works across them. Rejected outright: the trees share no file paths
   in a meaningful way (`app/src/main/java/net/devemperor/dictate/…` exists in
   both with entirely different content), so every such operation would produce
   thousands of conflicts. It would also make the fork's history dishonest
   about where its code came from.

3. **Branch prefix `upstream/*`.** The most self-documenting name, and the
   first thing anyone would try. Rejected because git cannot disambiguate it
   from the `upstream` remote's tracking refs (Research §4) — `git log
   upstream/main` becomes a coin flip, and the warning appears at exactly the
   moments when clarity matters most.

4. **Contribute ad hoc, without structure** — branch off `upstream/main`
   whenever something comes up, no scripts, no guardrail. This is what would
   happen by default. Rejected because the failure modes are silent and
   embarrassing rather than loud: a PR carrying `Co-Authored-By: Claude`, a
   branch accidentally based on a stale `upstream/main` from weeks ago, a diff
   polluted by a stray `CLAUDE.md` or `local.properties`. Upstream moved 5.3 →
   6.1.2 in four weeks; a manual process re-earns the staleness bug every time.

5. **Migrate the fork to 6.x now** (option B from the August analysis) and
   collapse the problem to a single lineage. Rejected as out of scope for this
   ADR — it is a product decision costing months, and it is precisely the
   decision this arrangement is designed *not* to force. Contributing upstream
   regularly is also the cheapest way to gather evidence for it.

## Consequences

**Positive:**

- The fork is untouched. `main`, its worktrees, its build and its conventions
  work exactly as before; nothing about upstream work reaches them.
- Upstream contributions become a three-command routine (`new.sh` → commit →
  `pr.sh`) instead of a setup project, which is what makes "regularly" realistic.
- Cross-lineage reading is trivial: `git log dk/main`, `git grep … dk/main`,
  and `git show` across both roots all work from any worktree.
- The (A)/(B)/(C) fork-level question stays open, and every merged PR upstream
  adds evidence to it rather than pre-empting it.
- One object store: the upstream lineage costs a checkout, not a clone.

**Negative:**

- Two rule sets in one repository. Every contributor — human or agent — must
  know which lineage they are standing in before writing a commit message. The
  guardrail reduces this cost but does not remove it.
- `git branch -a` and the IDE branch picker now list two unrelated products.
- `worktrees/dk/main` costs roughly a gigabyte once built, for a tree that is
  read far more often than it is run.
- The scripts are project-specific glue that has to be maintained alongside
  upstream's build changes (a Gradle or AGP bump upstream can break the seeded
  `local.properties` assumption).

**Failure Modes:**

- **A `dk/<topic>` branch accidentally based on the fork lineage.** `new.sh`
  always branches from `dk/main`, but a hand-rolled `git checkout -b dk/foo`
  while standing on `main` produces a branch that looks right and whose PR
  diff is the entire fork. `pr.sh` prints the outgoing diffstat before pushing
  precisely so this is caught by eye; there is no automatic check for it.
- **Claude trailers on an upstream commit.** The session-level attribution rule
  is global and applies by default, so this is the *expected* mistake, not an
  unlikely one. `pr.sh` refuses to push when it finds one — but only when the
  PR goes through `pr.sh`. A manual `gh pr create` bypasses the gate entirely.
- **A stale `dk/main`.** Rebasing a topic branch onto a `dk/main` that was last
  synced weeks ago produces a PR that conflicts on arrival. `new.sh` syncs;
  a long-running branch needs `sync.sh --rebase <topic>` before every push, and
  nothing reminds you.
- **The guardrail silently missing.** If `worktrees/dk/<topic>/CLAUDE.md` is
  deleted, or the worktree was created by hand rather than by `new.sh`, an
  agent falls back to the repo root's `CLAUDE.md` — which describes the fork's
  Room database, its AI abstraction layer and its Kotlin/Java split, none of
  which exist in the upstream tree. The resulting work looks confidently wrong.
- **`.git/info/exclude` is shared across all worktrees** of this clone (it lives
  in the common dir). The `/CLAUDE.md` entry is therefore repo-wide. It is
  harmless today only because the fork's `CLAUDE.md` is tracked; if it were
  ever untracked, it would silently disappear from `git status`.
- **Per-worktree build prerequisites drift.** Upstream vendors sherpa-onnx as
  gitignored `.jar`/`.so` artifacts and fails `:app:verifySherpaOnnxLibs`
  without them, so every `dk` worktree needs a bootstrap that git does not
  carry. `new.sh` seeds it by copying from `worktrees/dk/main`, which is fast
  but silently wrong if upstream bumps the pinned sherpa-onnx version — the
  copied artifacts then have the wrong filenames and the verify task fails with
  the same message as a fresh tree. The fix is to re-run
  `tools/fetch-sherpa-onnx.sh` in `worktrees/dk/main` after such a bump.
- **`archived/dk/*` branches accumulate** without ever being pushed or pruned,
  and their `origin/dk/*` counterparts outlive the merged PR. `done.sh` warns
  but deliberately does not delete the remote branch.

## References

- **Related research:** [`fork-vs-upstream-feature-analysis`](../research/2026-08-11%20-%20fork-vs-upstream-feature-analysis/fork-vs-upstream-feature-analysis.md)
  — the adoption matrix, the upstream-issue shortlist (§2.4) that this
  arrangement exists to execute, and the open (A)/(B)/(C) fork-level question
  (§2.5) it deliberately leaves open.
- **Runbook:** [`upstream-contribution`](../runbooks/upstream-contribution.md)
  — the operational walkthrough: picking work, the per-PR loop, review
  handling, and what to do when upstream moves under a branch.
- **Related ADRs:** none. Every existing ADR describes the fork lineage and is
  scoped to it by this ADR's Scope clause; none of them apply inside
  `worktrees/dk/`.
- **Tooling:** `scripts/dk/` (`common.sh`, `sync.sh`, `new.sh`, `pr.sh`,
  `done.sh`), agent guardrail `scripts/dk/worktree-CLAUDE.md`.
- **Upstream:** https://github.com/DevEmperor/DictateKeyboard — analysed at
  `abd194bc` (v6.1.2, 2026-09-04). Abandoned shared lineage: branch
  `legacy-java`, tip `6f2daf05`.

## Decision History

### 2026-09-05 — Initial proposal

**Trigger:** The intent to contribute to `DevEmperor/DictateKeyboard`
regularly, after the August fork-vs-upstream analysis established that upstream
is a living, PR-accepting project on a codebase unrelated to ours, and after
observing it move 5.3 → 6.1.2 in four weeks.

**Before:** One lineage, one set of conventions. Upstream existed only as a
fetched remote for analysis; there was no way to work on its code that did not
either destroy the fork's build state or require a second clone. The
repository-level rules (commit prefixes, ADR requirements, Claude attribution)
applied unconditionally and would have been applied to upstream PRs.

**After:** Two lineages in one clone, separated by the `dk/*` branch namespace
and by worktrees under `worktrees/dk/`. `dk/main` is a fast-forward-only mirror
of `upstream/main` checked out permanently; `upstream` is fetch-only;
`scripts/dk/` automates the per-contribution loop and blocks Claude trailers
before push; an excluded `CLAUDE.md` per `dk` worktree carries the upstream
rule set for agents.

**Reasoning:** A second clone was the safer-looking option, but the work this
arrangement serves is inherently comparative — reverse-ports and issue reports
both require reading the two codebases against each other — and git's refusal
to merge unrelated histories already provides the guard that directory
separation would buy. The residual risk is not mechanical but conventional:
applying fork rules to upstream commits. That risk is where the tooling and the
guardrail are aimed, rather than at the separation itself.
