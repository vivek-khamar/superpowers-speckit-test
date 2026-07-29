#!/bin/bash
# compute-branch-name.sh TICKET-KEY SUMMARY [TYPE]
#
# Prints the exact branch name to use for a ticket, per this pipeline's
# feat/<TICKET-KEY>-<slug> or fix/<TICKET-KEY>-<slug> convention (documented
# in CLAUDE.md's Ticket traceability section). Deterministic on purpose:
# branch naming should never be a judgment call made ad hoc when setting up
# a worktree -- that's exactly how a branch ends up named "login-api"
# instead of "feat/DEMO-2-login-api", silently violating the project's own
# convention until someone notices later (which, on this pipeline's own
# second real ticket, required an awkward branch-rename recovery after the
# rename briefly closed an open PR as a side effect).
#
# Always use this script's output verbatim as the branch name; don't
# compose it by hand even when it seems obvious what it should be.
#
# TICKET-KEY: e.g. DEMO-2
# SUMMARY:    the ticket's title/summary, any case/punctuation
# TYPE:       optional; if it case-insensitively contains "bug", the
#             branch is prefixed fix/ instead of feat/

set -euo pipefail

if [ $# -lt 2 ]; then
  echo "Usage: compute-branch-name.sh TICKET-KEY SUMMARY [TYPE]" >&2
  exit 1
fi

TICKET_KEY=$1
SUMMARY=$2
TYPE=${3:-}

PREFIX="feat"
echo "$TYPE" | grep -qi "bug" && PREFIX="fix"

SLUG=$(echo "$SUMMARY" \
  | tr '[:upper:]' '[:lower:]' \
  | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//' \
  | cut -c1-40 \
  | sed -E 's/-+$//')

echo "${PREFIX}/${TICKET_KEY}-${SLUG}"
