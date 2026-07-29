# Project Conventions

## Ticket traceability

Every change here starts from a Jira ticket (project key `DEMO`).
Carry the ticket key through the whole trail so anyone can trace a commit
back to the requirement that motivated it:

- **Branch:** `feat/<TICKET-KEY>-<slug>` or `fix/<TICKET-KEY>-<slug>` (e.g.
  `feat/DEMO-1-short-description`), branched from `main`.
  Derive this name by running
  `.claude/scripts/compute-branch-name.sh <TICKET-KEY> "<ticket summary>" [<ticket type>]`
  and using its output verbatim — don't compose the branch name by hand,
  even when the right name seems obvious. A hand-composed name is exactly
  how this convention has drifted before (a branch named just `login-api`
  instead of `feat/DEMO-2-login-api`, discovered only after the fact and
  requiring a disruptive rename to fix).
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
command (mvn verify) before a turn is allowed to end, whenever a
relevant file has changed since the last time it passed. On failure, fix
exactly what's reported — don't refactor beyond it, don't reach for
`/simplify` or a subagent to make it pass, and don't weaken the gate itself
to get green.

## Pre-merge SonarQube scan (optional)

If this project has a gitignored `.env` with `SONAR_HOST_URL` and
`SONAR_TOKEN` configured, run `.claude/scripts/run-sonar-scan.sh` as part
of `superpowers:finishing-a-development-branch`'s test-verification step —
right before presenting the merge/PR options, not on every turn (a full
Sonar analysis is much slower than the per-turn test+lint gate above).

This is advisory only: it reports the quality gate result and dashboard
link, but never blocks finishing the branch on its own — weigh the result
the same way you'd weigh any other reviewer finding. If `.env` isn't
configured, the script skips silently; don't treat that as a failure or
set one up unprompted.

Invoke it by path (`bash .claude/scripts/run-sonar-scan.sh`) — the script
sources `.env` internally, so the invoking command itself never spells out
`.env` and won't trip the `block-dangerous.sh` credential-file guard. Don't
inline the `source .env`/token-reading steps directly into a shell command
of your own — that's what the guard is specifically watching for, and
routing around it defeats its purpose even if a differently-worded command
would technically slip through.

## Headless mode

This project can run two ways: interactively (a human drives each turn in
a live session — everything above already describes that mode, no changes
needed) or headlessly, one-shot, via `pipeline/scripts/run-headless.sh
--project-dir . --ticket <TICKET-KEY>` (invokes `claude --print
--permission-mode auto` and exits when that turn ends). Headless mode is
active exactly when the environment variable `SUPERPOWERS_HEADLESS=1` is
set — `run-headless.sh` sets it; a normal interactive session never has
it. Check for it before applying anything in this section.

**The one hard rule: never call `AskUserQuestion` when `SUPERPOWERS_HEADLESS=1`
is set.** There is no human watching a one-shot run to answer it — the
call will hang or the answer will be meaningless. Every place the
interactive process would normally ask a question falls into one of the
two buckets below instead.

**Routine choices get a fixed default — don't ask, don't pause:**

| Decision point | Interactive default | Headless default |
|---|---|---|
| `using-git-worktrees` worktree consent | ask | yes, always create one |
| `writing-plans` execution handoff | ask (Subagent-Driven vs Inline) | always Subagent-Driven — Inline's batch checkpoints assume a human is present between batches |
| `finishing-a-development-branch` menu | ask (4 options) | always "Push and create PR", once tests pass and the whole-branch review is clean |

**`speckit-clarify` needs its own override, not just "don't call
AskUserQuestion".** Its documented process is "ask ONE question at a time"
— in an interactive session that means: output the question, end the
turn, and the human's reply arrives as the *next* turn. A one-shot
`claude -p` run has no next turn, so followed literally this stalls
silently after the first question: no error, no
`AWAITING_HUMAN_APPROVAL` marker, nothing left to resume. When headless,
skip the one-at-a-time interactive loop entirely: run the ranking step
exactly as documented (impact × uncertainty, max 5), then if ANY material
ambiguity survives, end the turn immediately with every ranked question
batched into one marker instead of asking the first and waiting:

```
AWAITING_HUMAN_APPROVAL: clarify DEMO-2 -- 1) <question 1> 2) <question 2> ...
```

Resume with all the answers in one `--message`, then write them into the
spec's Clarifications section exactly as the interactive process
describes, and continue. If no material ambiguity survives the ranking
step, don't pause at all — proceed straight to `writing-plans` as usual.

**Genuine judgment calls still pause — end the turn instead of guessing.**
These are exactly the situations that would otherwise be an
`AskUserQuestion` call because there's no obviously-correct default: a
plan step that contradicts the plan's own Global Constraints or the spec,
a reviewer finding that's plan-mandated (the plan explicitly requires
something the review rubric treats as a defect), a design/security
tradeoff with real competing considerations and no clean answer (e.g. "the
423-vs-401 status code is itself an account-enumeration oracle — fix it,
accept it, or file a follow-up?" — a real one from this pipeline's own
history), or anything `subagent-driven-development`'s own process already
says to escalate to "the human's decision" rather than the orchestrator
deciding unilaterally.

When one of these comes up, end the turn with exactly this marker line
instead of calling `AskUserQuestion`:

```
AWAITING_HUMAN_APPROVAL: <one-line summary of what's being asked>
```

`run-headless.sh` detects this marker, persists the session, and exits
with code `2` (distinct from `0` success and `1` failure) so a human can
review later and resume the exact same session with `--resume <session-id>
--message "<answer>"`. Only use this marker for a genuine checkpoint, not
a routine confirmation already covered by the default-policy table above.

**Every subagent dispatch must be foreground, always** — this is already
mechanically enforced by the global `guard-dispatch.sh` hook when headless
(it blocks any `run_in_background: true` Task/Agent call, from the
orchestrator or a subagent), but the reasoning matters: a one-shot
`claude --print` process exits the instant its turn ends, so a backgrounded
subagent is abandoned mid-work with nothing left to receive its result —
the ticket would silently stall. Dispatch every implementer, reviewer, and
fix subagent in the foreground and wait for it to finish before ending
the turn.
