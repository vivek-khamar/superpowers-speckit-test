# CONTRIBUTING.md (DEMO-6)

**Date:** 2026-07-29
**Source:** Jira DEMO-6 — "Add a CONTRIBUTING.md documenting local test commands"
**Status:** Clarified (ready for planning)

## Clarifications

### Session 2026-07-29

- Q: Should the API contract paragraph include concrete JSON examples, or
  stay prose-only? → A: Prose-only, matching the ticket's explicit "one
  paragraph" instruction — field names and status codes referenced inline
  in text, no separate JSON code blocks. Resolved directly from the
  ticket's own wording (not escalated — a self-evident reading, not a
  competing-tradeoff judgment call).
- Q: Does "documenting local test commands" extend to instructions for
  running the app locally (e.g. `docker-compose up`, `mvn
  spring-boot:run`)? → A: No. The ticket's three acceptance-criteria
  bullets are exhaustive and specific (file exists; test command +
  prerequisites; API contract) with no mention of running the app —
  scope is strictly those three. Resolved directly from the ticket's
  explicit acceptance criteria (not escalated).

## Overview

Add a single new file, `CONTRIBUTING.md`, at the repository root, documenting
how to run this project's test suite locally and the basic contract of the
`POST /api/v1/auth/login` endpoint. This is a documentation-only change: no
existing file is modified, no code changes are made. It is a deliberately
small, low-ambiguity ticket used to validate this project's headless
pipeline (`pipeline/scripts/run-headless.sh`) end-to-end for the first time.

## User Scenarios & Testing

### Primary Flow

1. A new contributor clones the repo and opens `CONTRIBUTING.md` to learn
   how to run the test suite locally.
2. They see the exact command (`mvn test`) plus the two environment
   prerequisites the tests actually need: JDK 21, and a reachable Docker
   daemon (the integration tests spin up a real `PostgreSQLContainer` via
   Testcontainers).
3. They also want to understand what the login endpoint does without
   reading the controller source, so `CONTRIBUTING.md` documents its basic
   contract: request shape, success response, and the three failure status
   codes.

### Acceptance Scenarios

- **Given** the repository root, **when** a contributor lists its files,
  **then** `CONTRIBUTING.md` exists there.
- **Given** `CONTRIBUTING.md`, **when** a contributor reads the test-running
  section, **then** they find the exact command `mvn test`, and both
  prerequisites called out explicitly: JDK 21 (not whatever `java`/`mvn`
  resolves to by default), and a running/reachable Docker daemon (needed
  because integration tests use Testcontainers to start a real Postgres
  container).
- **Given** `CONTRIBUTING.md`, **when** a contributor reads the API section,
  **then** they find one paragraph covering `POST /api/v1/auth/login`'s
  request shape (`email`, `password`), its success response (`200`, JSON
  body with `status`/`message`/`user`, plus an `HttpOnly` JWT cookie), and
  the three failure codes: `400` (validation failure — missing/blank fields
  or malformed email), `401` (bad credentials — unknown email or wrong
  password, identical response either way), and `423` (account locked after
  repeated failed attempts).
- **Given** the change is reviewed, **when** the diff is inspected, **then**
  it contains exactly one new file (`CONTRIBUTING.md`) and no modifications
  to any existing file.

### Edge Cases

- None — this is a static documentation file with no runtime behavior to
  edge-case.

## Functional Requirements

- **FR-1**: System MUST add a new file `CONTRIBUTING.md` at the repository
  root. No existing file may be modified.
- **FR-2**: `CONTRIBUTING.md` MUST document the exact command to run the
  full test suite: `mvn test`.
- **FR-3**: `CONTRIBUTING.md` MUST state that the tests require JDK 21
  specifically (not merely "a recent JDK" or whatever `java`/`mvn` resolves
  to by default on the contributor's machine).
- **FR-4**: `CONTRIBUTING.md` MUST state that a reachable Docker daemon is
  required, because integration tests use Testcontainers to start a real
  PostgreSQL container.
- **FR-5**: `CONTRIBUTING.md` MUST document the `POST /api/v1/auth/login`
  endpoint's basic contract in one paragraph: the request shape (`email`,
  `password`), the success response (status code and body shape), and the
  three failure status codes (400, 401, 423) with a brief note on what
  triggers each.
- **FR-6**: The API contract section MUST be prose-only (one paragraph,
  per the ticket) — no separate JSON code blocks.
- **FR-7**: Scope MUST be limited to exactly the three ticket
  acceptance-criteria bullets (file exists; test command + prerequisites;
  API contract) — no instructions for running the app locally.

## Key Entities

Not applicable — no data model changes.

## Global Constraints

- No code changes. Documentation only.
- Do not modify any existing file — only add the new `CONTRIBUTING.md`.
- Tech stack (for context, unchanged by this ticket): Java 21, Spring Boot
  3.3.4, Maven, PostgreSQL + Flyway, Testcontainers for integration tests.

## Review & Acceptance Checklist

- [x] Scope resolved: exactly the three ticket acceptance-criteria bullets,
      no app-run instructions (FR-7).
- [x] API contract format resolved: prose-only, one paragraph (FR-6).
- [ ] `CONTRIBUTING.md` exists at repo root.
- [ ] Test command (`mvn test`) documented exactly.
- [ ] JDK 21 prerequisite stated explicitly.
- [ ] Docker/Testcontainers prerequisite stated explicitly.
- [ ] `POST /api/v1/auth/login` contract documented (request, success,
      400/401/423).
- [ ] No existing file modified.
