# Project Conventions

## Ticket traceability

Every change here starts from a Jira ticket (project key `DEMO`).
Carry the ticket key through the whole trail so anyone can trace a commit
back to the requirement that motivated it:

- **Branch:** `feat/<TICKET-KEY>-<slug>` or `fix/<TICKET-KEY>-<slug>` (e.g.
  `feat/DEMO-1-short-description`), branched from `main`.
- **Start of work:** transition the ticket to "In Progress" before the first
  commit — don't leave it sitting at whatever status it had before work
  started (e.g. "To Do", or a stale "Done" from an earlier pass).
- **Commits:** prefix the subject with the ticket key, e.g.
  `feat(DEMO-1): add rate limiting filter`.
- **PR body:** must include, in this order: a summary, a link to the Jira
  issue, an acceptance-criteria checklist (one line per criterion, checked
  off), test results (what ran, pass/fail counts), and a list of modified
  files if the change is non-trivial.
- **Close the loop:** once a PR is open, transition the ticket to "In
  Review" and comment the PR link on it.

## Development process

Prefer the full discipline over a single end-of-run check:

1. Spec → clarify open questions with the person who owns the ticket before
   planning (`speckit-specify`/`speckit-clarify`, or `superpowers:brainstorming`).
2. Plan the work as bite-sized, independently testable tasks
   (`superpowers:writing-plans`).
3. Implement task-by-task with a fresh subagent per task, each followed by
   an independent task-scoped review — spec compliance and code quality —
   before moving to the next task (`superpowers:subagent-driven-development`).
4. Before opening a PR, run one independent whole-branch review covering
   the feature as a whole, not just its individual tasks. Task-scoped review
   alone will not catch cross-cutting issues (a security bypass, a
   performance regression from how two tasks compose, an inconsistency
   between what one task assumed and another delivered).

Do not replace this with a single pass/fail gate (tests + linter) run once
at the end. That catches a different, narrower class of problem (style,
common bug patterns, coverage) — it's a good complement to the above, not a
substitute for it.

## Iteration caps

If a fix loop (quality-gate failures, test failures) goes past 5 rounds
without converging, stop and escalate rather than continuing to iterate —
something is wrong with the approach, not just the latest attempt. Commit
what you have with the remaining failures noted, and say so plainly instead
of quietly retrying.

## Subagent discipline

A subagent may not dispatch its own subagents, and may not invoke
`/simplify` or `code-review --fix` on its own initiative — these are
mechanically blocked by a global hook (`~/.claude/hooks/guard-dispatch.sh`).
If a subagent needs to self-review, it should read and edit directly rather
than reach for a skill that can cascade into further agents.

A subagent also may not edit this file, `.claude/settings.json`, or
anything under `.claude/hooks/` — mechanically blocked by the same global
hook set (`~/.claude/hooks/enforce-scope.sh`). If one of these genuinely
needs to change, that's the orchestrator's or the user's call, not a
subagent's.

## Quality gates

A project-level Stop hook (`.claude/hooks/enforce-quality-gates.sh`)
auto-detects this project's ecosystem and runs its standard test+lint
command (`mvn verify`) before a turn is allowed to end, whenever a
relevant file has changed since the last time it passed. On failure, fix
exactly what's reported — don't refactor beyond it, don't reach for
`/simplify` or a subagent to make it pass, and don't weaken the gate itself
to get green.

## Pre-merge SonarQube scan (optional)

This project has a gitignored `.env` with `SONAR_HOST_URL`/`SONAR_TOKEN`
configured (local server at `http://localhost:9000`). Run
`.claude/scripts/run-sonar-scan.sh` as part of
`superpowers:finishing-a-development-branch`'s test-verification step —
right before presenting the merge/PR options, not on every turn (a full
Sonar analysis is much slower than the per-turn `mvn verify` gate above).

This is advisory only: it reports the quality gate result and dashboard
link, but never blocks finishing the branch on its own — weigh the result
the same way you'd weigh any other reviewer finding.

Invoke it by path (`bash .claude/scripts/run-sonar-scan.sh`) — the script
sources `.env` internally, so the invoking command itself never spells out
`.env` and won't trip the `block-dangerous.sh` credential-file guard. Don't
inline the `source .env`/token-reading steps directly into a shell command
of your own — that's what the guard is specifically watching for.
