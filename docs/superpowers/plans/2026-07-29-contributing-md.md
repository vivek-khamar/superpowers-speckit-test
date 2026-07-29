# CONTRIBUTING.md Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a single new `CONTRIBUTING.md` file at the repo root documenting how to run the test suite locally and the `POST /api/v1/auth/login` contract.

**Architecture:** This is a documentation-only change — one new Markdown file, no code. No test framework applies; "testing" this task means checking the file's content against the acceptance criteria and confirming no other file changed.

**Tech Stack:** Markdown. Project itself is Java 21 / Spring Boot 3.3.4 / Maven / PostgreSQL + Flyway / Testcontainers (referenced in the doc content, not touched by this change).

## Global Constraints

- No code changes. Documentation only.
- Do not modify any existing file — only add the new `CONTRIBUTING.md`.
- API contract section MUST be prose-only, one paragraph — no JSON code blocks.
- Scope MUST be limited to exactly the three ticket acceptance-criteria bullets (file exists; test command + prerequisites; API contract) — no local-app-run instructions.

---

### Task 1: Add CONTRIBUTING.md

**Files:**
- Create: `CONTRIBUTING.md`

**Interfaces:**
- Consumes: nothing (first and only task).
- Produces: `CONTRIBUTING.md` at repo root — the deliverable itself; no other task depends on it.

- [ ] **Step 1: Write `CONTRIBUTING.md`**

Create `CONTRIBUTING.md` at the repository root with exactly this content:

```markdown
# Contributing

## Running the test suite

Run the full test suite with:

    mvn test

This project's tests need two things in place before they'll pass: **JDK 21**
specifically (not just whatever `java`/`mvn` happens to resolve to by
default on your machine — check with `java -version` and `mvn -version`),
and **a reachable Docker daemon**, because the integration tests use
Testcontainers to start a real PostgreSQL container for the duration of the
run.

## `POST /api/v1/auth/login`

This endpoint authenticates a user with an `email` and `password` in the
JSON request body. On success it returns `200 OK` with a JSON body
containing `status`, `message`, and a `user` summary (`id`, `email`,
`name`), and sets the JWT as an `HttpOnly` cookie rather than returning it
in the body. On failure it returns one of three status codes: `400` when
the request fails validation (missing/blank `email` or `password`, or a
malformed email), `401` when the credentials don't match any account
(identical response whether the email is unknown or the password is
wrong, so callers can't distinguish the two), or `423` when the account is
currently locked out after too many consecutive failed attempts.
```

- [ ] **Step 2: Verify the file's content against the acceptance criteria**

Run: `cat CONTRIBUTING.md`

Expected: the file exists at the repo root and, read top to bottom,
satisfies all three ticket acceptance criteria:
1. File exists at repository root.
2. Exact test command (`mvn test`) present, with both prerequisites (JDK
   21, reachable Docker daemon) stated explicitly.
3. `POST /api/v1/auth/login` contract present as one paragraph: request
   shape, success response, and all three failure codes (400/401/423)
   with what triggers each.

- [ ] **Step 3: Verify no existing file was modified**

Run: `git status --porcelain`

Expected: exactly one line, `?? CONTRIBUTING.md` (untracked, new file) —
no `M` (modified) lines for any other path.

- [ ] **Step 4: Run the project's standard quality gate**

Run: `mvn verify`

Expected: `BUILD SUCCESS` — confirms this documentation-only change hasn't
broken anything (it shouldn't touch any code path, but this is the
project's standard gate per CLAUDE.md and must pass before the branch is
considered done).

- [ ] **Step 5: Commit**

```bash
git add CONTRIBUTING.md
git commit -m "docs(DEMO-6): add CONTRIBUTING.md with test command and login API contract"
```
