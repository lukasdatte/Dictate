---
date: 2026-09-05
author: Lukas + Claude
status: Accepted
context: Operator walkthrough for contributing changes to DevEmperor/DictateKeyboard from this repository's second (dk/*) lineage, without disturbing the fork on main.
related-plan: n/a (plan-free runbook)
related-adrs: ADR-0028
---

# Runbook — Contributing to Upstream (Dictate Keyboard)

How to take a change from "upstream has a bug" to "the PR is merged", using the
`dk/*` lineage that lives alongside our fork in this repository.

> [!IMPORTANT]
> The rules in this runbook **contradict** the rest of this repository on
> purpose. Inside `worktrees/dk/` there are no ADRs, no plan files, no commit
> prefixes and no Claude trailers. The separation and its reasoning are
> ADR-0028; this runbook is the operational side.

## 1. Vision and Motivation

### 1.1 Why this runbook exists

Upstream (`DevEmperor/DictateKeyboard`) rebuilt the app on FlorisBoard and
shares **no git history** with our fork. Contributing there is therefore not
"push a branch" — it is working in a second codebase that happens to sit in the
same clone. Every fork reflex (commit prefix, ADR, plan, trailer, German notes)
is wrong there, and every one of those mistakes is visible to the maintainer.

Upstream also moves fast: 5.3 → 6.1.2 in four weeks. A branch cut from a
`upstream/main` that was fetched last month conflicts on arrival.

This runbook makes the loop mechanical so neither failure has to be remembered.

### 1.2 What problem this solves

- **Lineage confusion** — which conventions apply where, decided by *directory*
  rather than by memory.
- **Staleness** — every entry point re-syncs `dk/main` before branching.
- **Attribution leakage** — the global Claude-trailer rule would otherwise apply
  to upstream commits by default; `pr.sh` blocks it.
- **Setup friction** — a contribution starts with one command, not a checklist.

### 1.3 Discarded alternatives

A **second clone** of upstream was the obvious alternative and was rejected in
ADR-0028 §Alternatives: the daily work is comparative (reverse-ports, evidence
for issue reports), and a second clone puts `git log`/`git grep` across both
codebases out of reach while buying separation that the branch namespace and
worktrees already provide.

### 1.4 What this buys us

1. A contribution is three commands: `new.sh` → commit → `pr.sh`.
2. The fork's build, IDE index and Gradle cache are never disturbed.
3. Upstream code is permanently greppable at `worktrees/dk/main`.
4. Agents get the right rule set from the worktree they stand in.

## 2. Outcomes

After following this runbook:

1. A branch `dk/<topic>` exists, based on a `dk/main` that is a fast-forward of
   the current `upstream/main`.
2. `git diff dk/main..dk/<topic>` contains only the intended change — no
   `CLAUDE.md`, no `local.properties`, no reformatting.
3. Every commit message is a single imperative line with no prefix and no
   trailers.
4. `./gradlew :app:assembleDebug` passes in the topic worktree.
5. A PR is open at `DevEmperor/DictateKeyboard` from `lukasdatte:dk/<topic>`.
6. `main` and every `feature/*` worktree are byte-identical to before.

## 3. The two lineages — where you are matters

```
/home/lukas/WebStorm/Dictate/                     ← FORK lineage (Dictate 3.2)
│                                                   main · Gradle 8.14.3 · Groovy
│                                                   ADRs, plans, commit prefixes
├── docs/            decisions/ plans/ research/  ← fork documentation only
├── scripts/dk/                                   ← the tooling described here
│
└── worktrees/                                    (gitignored)
    ├── feature/desktop-companion-v1              ← FORK lineage
    │
    └── dk/                                       ← UPSTREAM lineage
        ├── main                                  ← mirror of upstream/main,
        │                                           never committed to
        └── <topic>                               ← one per contribution
```

| | Fork | Upstream (`dk/*`) |
|---|---|---|
| Build | Gradle 8.14.3, Groovy DSL | Gradle 9.4.1, Kotlin DSL |
| Modules | `:app :shared :companion` | `:app :wear :lib:*` |
| Java target | 1.8 | 11 (AGP 9.2.1, Kotlin 2.3.20) |
| Commit style | `[Phase.Chunk] Title (plan-slug)` + trailers | one imperative line, no trailers |
| Docs | ADRs, plans, research | none — never add any |
| Diff size | whatever the plan says | as small as the fix allows |

> [!CAUTION]
> Never `git merge`, `rebase` or `cherry-pick` between the lineages. They have
> no merge base; git will refuse without `--allow-unrelated-histories`, and
> forcing it produces thousands of conflicts. Transferring an idea between them
> means **re-implementing** it, by hand, in the target codebase's idiom.

## 4. Step-by-step reference

### 4.1 Pick the work

Upstream is issue-driven. Contributions that land are small, evidenced, and map
to something the maintainer already agrees is a problem.

```bash
gh issue list -R DevEmperor/DictateKeyboard --state open
gh issue view <N> -R DevEmperor/DictateKeyboard
```

Two sources of candidates:

- **The open issue list** (22 open as of 2026-09-05), preferring labelled and
  unassigned ones.
- **The upstream-issue shortlist** in
  [`fork-vs-upstream-feature-analysis`](../research/2026-08-11%20-%20fork-vs-upstream-feature-analysis/fork-vs-upstream-feature-analysis.md) §2.4
  — four gaps our fork already solved, ranked by how well-evidenced they are.
  File the issue **first**, let the maintainer respond, then open the PR
  against it.

> [!TIP]
> For anything larger than a bug fix, open the issue and wait for a reaction
> before writing code. The maintainer is the sole architect of this codebase;
> an unsolicited architectural PR is the one shape that reliably does not land.

### 4.2 Start the contribution

```bash
cd ~/WebStorm/Dictate
./scripts/dk/new.sh <topic> [issue-number]
```

`<topic>` is lowercase `[a-z0-9._-]`, one path segment, and becomes both the
branch `dk/<topic>` and the worktree `worktrees/dk/<topic>`.

The script fetches upstream, fast-forwards `dk/main`, creates the branch and
worktree, copies `local.properties`, seeds the vendored sherpa-onnx artifacts
(see below), and drops the agent guardrail `CLAUDE.md` (hidden via
`.git/info/exclude`, so it cannot reach the PR).

> [!NOTE]
> Upstream vendors the sherpa-onnx native libraries for on-device STT as
> gitignored `.jar`/`.so` artifacts, and `:app:verifySherpaOnnxLibs` fails the
> build without them. They are per-working-tree. `new.sh` copies them from
> `worktrees/dk/main` when that tree has them and falls back to
> `tools/fetch-sherpa-onnx.sh` (a ~100 MB download from the pinned GitHub
> release plus Maven Central) otherwise. In a hand-made worktree, run
> `./tools/fetch-sherpa-onnx.sh` once yourself.

### 4.3 Verify the tree builds before touching it

```bash
cd worktrees/dk/<topic>
./gradlew :app:assembleDebug
```

Do this **before** the first edit. A failure here is an environment problem;
the same failure after an edit looks like your change broke something.

### 4.4 Make the change

- One issue, one concern, minimal diff.
- No refactors beyond what the fix needs; no import reordering; no touching
  adjacent code that merely looks improvable.
- Match the surrounding Kotlin/Compose idiom, not the fork's.
- Do not port fork architecture. It presupposes modules that do not exist here.

### 4.5 Commit in upstream's voice

Single imperative line, describing the **effect**, not the mechanism. No
`feat:`/`fix:` prefix, no body unless it genuinely adds something, no trailers.
Reference the issue as `(closes #NNN)`.

Real examples from `git log dk/main`:

```
Say why the keyboard is silent instead of leaving it to be guessed
Read what an audio file is out of the file, not out of its name
Let a language decide whether it needs the digit row
Let Russian mark where the stress falls (closes #328)
```

```bash
git commit -m "Page the history list instead of loading it whole (closes #NNN)"
```

> [!WARNING]
> The session-wide attribution rule adds `Co-Authored-By: Claude` and a session
> link by default. That rule applies to the **fork lineage only**. `pr.sh`
> refuses to push a branch whose commits carry either trailer — but only when
> the push goes through `pr.sh`.

### 4.6 Re-sync and review the outgoing diff

```bash
cd ~/WebStorm/Dictate
./scripts/dk/sync.sh --rebase <topic>
git -C worktrees/dk/<topic> diff dk/main --stat
```

Read the diffstat. Anything you did not intend to change — `CLAUDE.md`,
`local.properties`, `.idea/`, whitespace-only hunks — is caught here or not at
all.

### 4.7 Open the PR

```bash
./scripts/dk/pr.sh <topic> [issue-number]
```

It prints the outgoing commits and diffstat, blocks on Claude trailers, asks
for confirmation, pushes to `origin`, and opens
`gh pr create -R DevEmperor/DictateKeyboard --base main --head lukasdatte:dk/<topic> --web`
so the description can be written in the browser.

PR description: what the user sees change and why, referencing the issue.
Screenshots or a short clip for anything visible. Keep it shorter than you
would for the fork.

### 4.8 Handle review

Push follow-up commits to the same branch — the PR updates itself. Squashing is
the maintainer's call at merge time, so leave the history readable rather than
rewriting it under an active review.

If upstream moves during review:

```bash
./scripts/dk/sync.sh --rebase <topic>
git -C worktrees/dk/<topic> push --force-with-lease
```

### 4.9 Close it out

```bash
./scripts/dk/done.sh <topic>          # removes the worktree, archives the branch
git push origin --delete dk/<topic>   # only after the PR is merged or closed
```

`done.sh` renames the branch to `archived/dk/<topic>` rather than deleting it,
per the repo-wide archive-instead-of-delete convention. It refuses to run on a
dirty worktree and warns about the surviving remote branch instead of deleting
it — a deleted head branch closes the PR.

## 5. Keeping the mirror current

```bash
./scripts/dk/sync.sh                       # fetch + fast-forward dk/main, list new commits
./scripts/dk/sync.sh --rebase a --rebase b # …and rebase topic branches a and b
```

Run it before starting anything and before every push on a branch older than a
few days. `sync.sh` **aborts** if `dk/main` cannot fast-forward — that means
something was committed on the mirror, which is never correct. Recover with:

```bash
git -C worktrees/dk/main reset --hard upstream/main
```

## 6. Reverse-ports — upstream code into the fork

The opposite direction has no tooling and deliberately so. Upstream is
Apache-2.0 (`LICENSE`, `NOTICE`), so reuse is permitted, but the code cannot be
transplanted: it targets modules and APIs the fork does not have.

Procedure: read the upstream implementation at `worktrees/dk/main`, understand
the *approach*, re-implement it in the fork's idiom on a normal `feature/*`
branch under the fork's full conventions (plan, ADR if it qualifies, tests),
and record the upstream commit as the source in the commit message or the ADR's
Research section.

The four candidates identified in the August analysis are listed in
[`fork-vs-upstream-feature-analysis`](../research/2026-08-11%20-%20fork-vs-upstream-feature-analysis/fork-vs-upstream-feature-analysis.md) §2.4
("Reverse-ports from upstream into the fork").

## 7. Failure Modes

| Symptom | Cause | Fix |
|---|---|---|
| `refname 'upstream/main' is ambiguous` | a local branch named `upstream/...` was created | delete it; the local prefix is `dk/`, never `upstream/` (ADR-0028 Research §4) |
| `dk/main has diverged` from `sync.sh` | something was committed on the mirror | `git -C worktrees/dk/main reset --hard upstream/main` |
| PR diff shows the entire fork | the branch was cut from the fork lineage, not from `dk/main` | recreate with `new.sh` and re-apply the change; never `git checkout -b dk/...` by hand |
| `pr.sh` refuses: "commits carry Claude trailers" | the global attribution rule was applied | `git rebase -i dk/main`, reword the messages, retry |
| PR contains `CLAUDE.md` or `local.properties` | the worktree was created by hand, bypassing the exclude | remove the files from the commit; recreate the worktree with `new.sh` |
| An agent applies Room/ADR/plan conventions in a `dk` worktree | the guardrail `CLAUDE.md` is missing | `cp scripts/dk/worktree-CLAUDE.md worktrees/dk/<topic>/CLAUDE.md` |
| `git push upstream` fails on an unresolvable URL | intended — `remote.upstream.pushurl` is disabled | push to `origin`; upstream is fetch-only |
| `Execution failed for task ':app:verifySherpaOnnxLibs'` | the vendored native libs are missing in this worktree | `./tools/fetch-sherpa-onnx.sh`, or copy `app/libs/` + `app/src/main/jniLibs/` from `worktrees/dk/main` |
| Gradle fails only in a `dk` worktree | missing `local.properties`, or upstream bumped AGP/Gradle | copy `local.properties` from the fork root; check `git log dk/main -- gradle/` |
| `worktree remove` refuses | build output or edits present | commit or clean, then retry; `--force` discards |

## 8. Information Gaps

1. **Upstream's review expectations are inferred, not tested** — from merge
   history and commit style, not from a PR we filed. Owner: the first PR.
   Fallback: keep PRs small enough that a rejection costs little.
2. **No upstream CI exists** (`.github/` has no workflows), so "it builds" is
   our own bar. Owner: —. Fallback: `assembleDebug` plus manual verification on
   a device before every PR.
3. **Device testing of upstream builds is unautomated.** The fork's
   `scripts/e2e/` helpers target the fork's package and IME id; upstream ships
   the same application id (`net.devemperor.dictate`), so installing an upstream
   debug build **replaces the fork build on the device**. Owner: whoever first
   needs both installed. Fallback: use a separate device or emulator profile.
4. **No rule yet for when a fork feature is worth proposing upstream.** The
   analysis ranks four candidates; beyond those it is case-by-case. Owner:
   Lukas. Fallback: file an issue and let the maintainer's reaction decide.

## 9. References

- **ADR:** [`ADR-0028 — Two Unrelated Lineages in One Repository`](../decisions/0028-two-lineage-repository.md)
  — the decision, its alternatives and its failure modes.
- **Research:** [`fork-vs-upstream-feature-analysis`](../research/2026-08-11%20-%20fork-vs-upstream-feature-analysis/fork-vs-upstream-feature-analysis.md)
  — adoption matrix, upstream-issue shortlist (§2.4), fork-level options (§2.5).
- **Tooling:** `scripts/dk/{common,sync,new,pr,done}.sh`; agent guardrail
  `scripts/dk/worktree-CLAUDE.md`.
- **Upstream:** https://github.com/DevEmperor/DictateKeyboard ·
  issues https://github.com/DevEmperor/DictateKeyboard/issues ·
  site https://dictatekeyboard.com
- **Fork conventions this runbook suspends inside `worktrees/dk/`:**
  `~/.claude/snippets/commit-conventions.md`,
  `~/.claude/snippets/docs/lifecycle-adr.md`, the repo's `CLAUDE.md`.

## 10. Change History

- **2026-09-05** — Initial version, alongside ADR-0028 and `scripts/dk/`.
  Written after establishing that upstream accepts external PRs (8 merged from
  3 contributors, 2026-07-06 … 2026-07-31) and that the two lineages have no
  merge base.
