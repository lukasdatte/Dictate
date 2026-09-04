# CLAUDE.md — Upstream lineage (Dictate Keyboard)

> This file is **not part of upstream**. It is dropped in by
> `scripts/dk/new.sh` and hidden via `.git/info/exclude`. Never commit it.

## Where you are

This worktree checks out **DevEmperor/DictateKeyboard** — a FlorisBoard-based
Kotlin/Compose rewrite. It shares **no git history** with the fork in
`../../..` (Dictate 3.2 lineage). Two products, one clone.
Background: `../../../docs/decisions/0028-two-lineage-repository.md`.

Everything you know about the fork is wrong here. Verify, don't assume.

## Rules that differ from the fork

| Topic | Fork (`main`) | Here (`dk/*`) |
|---|---|---|
| Build | Gradle 8.14.3, Groovy DSL, `:app :shared :companion` | Gradle 9.4.1, Kotlin DSL, `:app :wear :lib:*` |
| Java target | 1.8 | 11 (AGP 9.2.1, Kotlin 2.3.20) |
| Commit style | `[Phase.Chunk] Title (plan-slug)` + Claude trailers | one imperative line, no prefix, **no trailers** |
| Docs | ADRs, plans, research under `docs/` | none — do not add any |
| Language | German working language, English docs | English only |
| Scope | whatever the plan says | one issue, one concern, minimal diff |

## Commit messages

Match the repository's voice — short, imperative, describing the *effect*,
never the mechanism. Real examples from `git log`:

```
Say why the keyboard is silent instead of leaving it to be guessed
Read what an audio file is out of the file, not out of its name
Let a language decide whether it needs the digit row
Let Russian mark where the stress falls (closes #328)
```

No `feat:`/`fix:` prefixes. No `Co-Authored-By`. No session links. No bodies
unless a body genuinely adds something. Reference an issue as `(closes #NNN)`.

## Scope discipline

Contributions here are **small, evidence-backed, issue-shaped**. The maintainer
lands focused PRs quickly and is the sole architect of this codebase.

- Do not refactor beyond what the fix needs.
- Do not port fork architecture. It does not fit and will not be accepted.
- Do not reformat, reorder imports, or "clean up" adjacent code.
- Do not add tests to a module that has none unless the change warrants it.
- One PR = one issue.

## Before opening a PR

1. `./gradlew :app:assembleDebug` passes.
2. `git diff dk/main` contains only the intended change.
3. The branch is rebased on a fresh `dk/main` (`scripts/dk/sync.sh`).
4. Behaviour verified on a device or the emulator — the fork's
   `scripts/e2e/` helpers target the fork's package name, not this one.

`scripts/dk/pr.sh <topic>` handles push + PR creation.
