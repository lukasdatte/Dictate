#!/usr/bin/env bash
# Retire a finished contribution: drop the worktree, archive the branch.
# Archive instead of delete — the commits stay reachable under archived/.
#
# Usage: scripts/dk/done.sh <topic>

source "$(dirname "$(readlink -f "$0")")/common.sh"

topic="${1:-}"
validate_topic "$topic"

branch="dk/$topic"
wt="$DK_WORKTREES/$topic"

if [ -d "$wt" ]; then
  [ -z "$(git -C "$wt" status --porcelain)" ] || die "worktree is dirty — nothing removed"
  git -C "$REPO_ROOT" worktree remove "$wt"
  ok "worktree removed"
fi

git -C "$REPO_ROOT" branch -m "$branch" "archived/$branch"
ok "branch archived as archived/$branch"

if git -C "$REPO_ROOT" show-ref --verify --quiet "refs/remotes/origin/$branch"; then
  warn "origin/$branch still exists — delete it once the PR is merged or closed:"
  hint "git push origin --delete $branch"
fi
