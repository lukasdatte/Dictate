#!/usr/bin/env bash
# Start a contribution to DevEmperor/DictateKeyboard.
# Creates branch dk/<topic> off a freshly synced dk/main and a worktree for it.
#
# Usage: scripts/dk/new.sh <topic> [issue-number]

source "$(dirname "$(readlink -f "$0")")/common.sh"

topic="${1:-}"; issue="${2:-}"
validate_topic "$topic"

branch="dk/$topic"
wt="$DK_WORKTREES/$topic"

git -C "$REPO_ROOT" show-ref --verify --quiet "refs/heads/$branch" \
  && die "branch $branch already exists"
[ ! -e "$wt" ] || die "$wt already exists"

sync_base

say "Creating $branch + worktree"
git -C "$REPO_ROOT" worktree add -b "$branch" "$wt" "$BASE_BRANCH" >/dev/null
ok "worktree at $wt"

# Android SDK location — gitignored in both lineages, so it must be seeded.
if [ -f "$REPO_ROOT/local.properties" ]; then
  cp "$REPO_ROOT/local.properties" "$wt/local.properties"
  ok "local.properties copied from the fork root"
else
  warn "no local.properties at the fork root — set sdk.dir in $wt/local.properties"
fi

# Upstream vendors sherpa-onnx (on-device STT) as gitignored .jar/.so artifacts
# and fails the build at :app:verifySherpaOnnxLibs without them. They are
# per-worktree, so every new tree needs them — copy from the reference worktree
# when it has them, otherwise re-download.
seed_sherpa() {
  local ref="$DK_WORKTREES/main"
  if [ -d "$ref/app/libs" ] && [ -n "$(ls -A "$ref/app/libs" 2>/dev/null)" ]; then
    mkdir -p "$wt/app/libs" "$wt/app/src/main/jniLibs"
    cp -r "$ref/app/libs/." "$wt/app/libs/"
    cp -r "$ref/app/src/main/jniLibs/." "$wt/app/src/main/jniLibs/"
    ok "sherpa-onnx artifacts copied from worktrees/dk/main"
  elif [ -x "$wt/tools/fetch-sherpa-onnx.sh" ]; then
    say "Fetching vendored sherpa-onnx artifacts (one-off download)"
    ( cd "$wt" && ./tools/fetch-sherpa-onnx.sh ) \
      && ok "sherpa-onnx artifacts fetched" \
      || warn "fetch failed — run tools/fetch-sherpa-onnx.sh in $wt before building"
  else
    warn "tools/fetch-sherpa-onnx.sh not found — upstream may have dropped the vendoring"
  fi
}
seed_sherpa

# Guardrail for agents: upstream conventions differ from the fork's in every
# dimension. Hidden from git so it can never leak into a PR.
cp "$REPO_ROOT/scripts/dk/worktree-CLAUDE.md" "$wt/CLAUDE.md"
exclude="$(git -C "$REPO_ROOT" rev-parse --path-format=absolute --git-common-dir)/info/exclude"
grep -qxF '/CLAUDE.md' "$exclude" 2>/dev/null || printf '/CLAUDE.md\n' >> "$exclude"
ok "CLAUDE.md guardrail placed (excluded from git)"

echo
say "Next"
hint "cd $wt"
[ -n "$issue" ] && hint "gh issue view $issue -R DevEmperor/DictateKeyboard"
hint "./gradlew :app:assembleDebug        # verify the tree builds before you touch it"
hint "# … change one thing, one imperative-line commit, no trailers …"
hint "$REPO_ROOT/scripts/dk/pr.sh $topic${issue:+ $issue}"
