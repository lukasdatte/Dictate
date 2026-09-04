#!/usr/bin/env bash
# Push dk/<topic> to our fork and open the PR against DevEmperor/DictateKeyboard.
#
# Usage: scripts/dk/pr.sh <topic> [issue-number]

source "$(dirname "$(readlink -f "$0")")/common.sh"

topic="${1:-}"; issue="${2:-}"
validate_topic "$topic"

branch="dk/$topic"
wt="$DK_WORKTREES/$topic"
[ -d "$wt" ] || die "no worktree at $wt"

[ -z "$(git -C "$wt" status --porcelain)" ] || die "worktree is dirty — commit or stash first"

commits="$(git -C "$wt" rev-list --count "$BASE_BRANCH..$branch")"
[ "$commits" -gt 0 ] || die "$branch has no commits on top of $BASE_BRANCH"

say "Diff going to upstream ($commits commit(s))"
git -C "$wt" log --oneline "$BASE_BRANCH..$branch" | sed 's/^/       /'
git -C "$wt" diff --stat "$BASE_BRANCH..$branch" | sed 's/^/       /'

# Trailers belong to the fork lineage; upstream commits carry none.
if git -C "$wt" log --format='%B' "$BASE_BRANCH..$branch" | grep -qiE '^(Co-Authored-By|Claude-Session):'; then
  die "commits carry Claude trailers — strip them (git rebase -i) before opening the PR"
fi

echo
read -r -p "Push to origin and open the PR? [y/N] " answer
[ "$answer" = "y" ] || die "aborted"

git -C "$wt" push -u origin "$branch"

# The PR head is owner:branch on our fork — derive the owner instead of
# hardcoding it, so a renamed or transferred fork keeps working.
origin_url="$(git -C "$REPO_ROOT" remote get-url origin)"
fork_owner="$(basename "$(dirname "${origin_url%.git}")")"
[ -n "$fork_owner" ] || die "could not derive the fork owner from '$origin_url'"

args=(-R DevEmperor/DictateKeyboard --base main --head "$fork_owner:$branch" --web)
[ -n "$issue" ] && args+=(--body "Closes #$issue")
gh pr create "${args[@]}"
