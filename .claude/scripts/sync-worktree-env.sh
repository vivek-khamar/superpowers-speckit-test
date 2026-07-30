#!/bin/bash
# sync-worktree-env.sh
#
# Copies a gitignored .env from the main repository root into the current
# worktree, if the main root has one and this worktree doesn't yet.
#
# Git worktrees never share untracked files with each other or with the
# main checkout, no matter where the file physically lives -- so
# credentials configured once (e.g. SONAR_HOST_URL/SONAR_TOKEN for
# run-sonar-scan.sh) silently stop applying the moment a new worktree is
# created for the next ticket. Observed in practice: DEMO-1 and DEMO-6's
# headless runs both silently skipped the Sonar scan because their
# worktrees never had the .env that only ever existed in an earlier
# ticket's worktree.
#
# Run this once, right after creating a new worktree (see CLAUDE.md's
# Ticket traceability section). Safe to run even if there's nothing to
# copy, or if run from the main checkout itself -- no-ops silently either
# way.

set -uo pipefail

GIT_COMMON=$(git rev-parse --git-common-dir 2>/dev/null)
[ -z "$GIT_COMMON" ] && exit 0
MAIN_ROOT=$(cd "$GIT_COMMON/.." 2>/dev/null && pwd)
CURRENT_ROOT=$(git rev-parse --show-toplevel 2>/dev/null)

[ -z "$MAIN_ROOT" ] && exit 0
[ -z "$CURRENT_ROOT" ] && exit 0
[ "$MAIN_ROOT" = "$CURRENT_ROOT" ] && exit 0

SRC="$MAIN_ROOT/.env"
DEST="$CURRENT_ROOT/.env"

if [ -f "$SRC" ] && [ ! -f "$DEST" ]; then
  cp "$SRC" "$DEST"
  echo "Copied .env from the main repo root into this worktree ($CURRENT_ROOT)."
fi

exit 0
