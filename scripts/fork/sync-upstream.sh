#!/usr/bin/env bash
# [FORK] Local equivalent of the Sync Upstream workflow.
#
#   main   ← fast-forwarded to upstream (pure mirror, never hand-edited)
#   mods   ← rebased on top of it (our patch series)
#   patches/ ← refreshed snapshot
#
# Nothing is pushed unless you pass --push, so you can inspect the result first.
#
# Usage:
#   scripts/fork/sync-upstream.sh [upstream-ref] [--push]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

REF="main"
PUSH="no"
for arg in "$@"; do
  case "$arg" in
    --push) PUSH="yes" ;;
    *) REF="$arg" ;;
  esac
done

info() { printf '\033[0;34m[sync]\033[0m %s\n' "$*"; }
fail() { printf '\033[0;31m[sync]\033[0m %s\n' "$*" >&2; exit 1; }

git remote get-url upstream >/dev/null 2>&1 || \
  git remote add upstream https://github.com/OpenMinis/OpenMinis.git

info "fetching upstream…"
git fetch upstream --tags --prune

TARGET="$(git rev-parse "upstream/${REF}" 2>/dev/null || git rev-parse "$REF")"
info "target: $(git rev-parse --short "$TARGET")  $(git log -1 --format=%s "$TARGET")"

# Refuse to operate on a dirty tree — a rebase would either fail confusingly or
# quietly fold uncommitted work into the series.
if [ -n "$(git status --porcelain)" ]; then
  fail "working tree is dirty; commit or stash first"
fi

CURRENT="$(git rev-parse --abbrev-ref HEAD)"
if [ "$CURRENT" != "mods" ]; then
  info "switching to mods (was $CURRENT)"
  git checkout mods
fi

BEFORE="$(git rev-parse HEAD)"
COUNT="$(git rev-list --count "${TARGET}..HEAD")"
info "fork commits to replay: ${COUNT}"

if [ "$(git merge-base HEAD "$TARGET")" = "$TARGET" ]; then
  info "mods already contains this upstream ref — nothing to rebase"
else
  info "rebasing…"
  if ! git rebase "$TARGET"; then
    echo
    fail "rebase conflict. Resolve, then: git add <files> && git rebase --continue
Guidance: docs/FORK.md §4 — upstream's implementation wins; re-apply our hook on
top of the new upstream code rather than reverting theirs.
Abort with: git rebase --abort  (mods returns to ${BEFORE:0:8})"
  fi
fi

info "updating local main to upstream…"
git branch -f main "$TARGET"

info "refreshing patch snapshot…"
bash scripts/fork/export-patches.sh "$TARGET"
if [ -n "$(git status --porcelain patches)" ]; then
  git add patches
  git commit -m "chore(fork): refresh patch snapshot for upstream $(git rev-parse --short "$TARGET")"
fi

if [ "$PUSH" = "yes" ]; then
  info "pushing…"
  git push origin "main:main" --force
  git push --force-with-lease origin mods:mods
  info "pushed. Next: Actions → Build Android APK"
else
  info "done (nothing pushed). Review with:  git log --oneline ${TARGET}..HEAD"
  info "then:  scripts/fork/sync-upstream.sh ${REF} --push"
fi
