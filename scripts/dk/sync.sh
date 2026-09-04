#!/usr/bin/env bash
# Fast-forward dk/main onto upstream/main and report what changed.
# Run before starting any contribution and before every rebase.
#
# Usage: scripts/dk/sync.sh [--rebase <topic> ...]
#   --rebase <topic>   after syncing, rebase dk/<topic> onto dk/main

source "$(dirname "$(readlink -f "$0")")/common.sh"

sync_base

topics=()
while [ $# -gt 0 ]; do
  case "$1" in
    --rebase) shift; [ $# -gt 0 ] || die "--rebase needs a topic"; topics+=("$1") ;;
    *)        die "unknown argument '$1'" ;;
  esac
  shift
done

for topic in "${topics[@]:-}"; do
  [ -n "$topic" ] || continue
  wt="$DK_WORKTREES/$topic"
  [ -d "$wt" ] || die "no worktree at $wt"
  say "Rebasing dk/$topic onto $BASE_BRANCH"
  if git -C "$wt" rebase "$BASE_BRANCH"; then
    ok "dk/$topic rebased"
  else
    warn "rebase stopped with conflicts — resolve in $wt, then 'git rebase --continue'"
  fi
done
