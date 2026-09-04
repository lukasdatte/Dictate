#!/usr/bin/env bash
# Shared helpers for the dk/* (upstream lineage) scripts.
#
# The repo carries two unrelated git histories — see
# docs/decisions/0028-two-lineage-repository.md. Everything under
# scripts/dk/ operates on the UPSTREAM lineage (DevEmperor/DictateKeyboard),
# never on the fork lineage.

set -euo pipefail

# Works from any worktree: the common dir is shared, its parent is the fork root.
REPO_ROOT="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
DK_WORKTREES="$REPO_ROOT/worktrees/dk"
UPSTREAM_REMOTE="upstream"
UPSTREAM_BRANCH="upstream/main"
BASE_BRANCH="dk/main"

c_bold=$'\033[1m'; c_dim=$'\033[2m'; c_red=$'\033[31m'
c_green=$'\033[32m'; c_yellow=$'\033[33m'; c_off=$'\033[0m'

say()  { printf '%s==>%s %s\n' "$c_bold" "$c_off" "$*"; }
ok()   { printf '%s  ✓%s %s\n' "$c_green" "$c_off" "$*"; }
warn() { printf '%s  !%s %s\n' "$c_yellow" "$c_off" "$*"; }
die()  { printf '%s  ✗%s %s\n' "$c_red" "$c_off" "$*" >&2; exit 1; }
hint() { printf '%s     %s%s\n' "$c_dim" "$*" "$c_off"; }

# A dk topic becomes both a branch (dk/<topic>) and a worktree dir.
# Keep it to one path segment so `worktrees/dk/<topic>` stays flat.
validate_topic() {
  local topic="${1:-}"
  [ -n "$topic" ] || die "no topic given — usage: $(basename "$0") <topic>"
  case "$topic" in
    main)   die "'main' is the reserved base branch (dk/main)" ;;
    */*)    die "topic must be a single path segment, got '$topic'" ;;
    -*)     die "topic must not start with '-'" ;;
  esac
  [[ "$topic" =~ ^[a-z0-9][a-z0-9._-]*$ ]] \
    || die "topic must be lowercase [a-z0-9._-], got '$topic'"
}

# Fast-forward dk/main onto upstream/main. dk/main is checked out in a
# worktree, so `git branch -f` would be rejected — merge inside it instead.
sync_base() {
  say "Fetching $UPSTREAM_REMOTE"
  git -C "$REPO_ROOT" fetch --prune "$UPSTREAM_REMOTE"

  local before after
  before="$(git -C "$REPO_ROOT" rev-parse "$BASE_BRANCH")"

  if [ -d "$DK_WORKTREES/main" ]; then
    git -C "$DK_WORKTREES/main" merge --ff-only "$UPSTREAM_BRANCH" >/dev/null \
      || die "$BASE_BRANCH has diverged from $UPSTREAM_BRANCH — it must stay a pure mirror"
  else
    git -C "$REPO_ROOT" branch -f "$BASE_BRANCH" "$UPSTREAM_BRANCH"
  fi

  after="$(git -C "$REPO_ROOT" rev-parse "$BASE_BRANCH")"
  if [ "$before" = "$after" ]; then
    ok "$BASE_BRANCH already current ($(git -C "$REPO_ROOT" rev-parse --short "$after"))"
  else
    ok "$BASE_BRANCH $(git -C "$REPO_ROOT" rev-parse --short "$before") → $(git -C "$REPO_ROOT" rev-parse --short "$after")"
    git -C "$REPO_ROOT" log --oneline "$before..$after" | sed 's/^/       /'
  fi
}
