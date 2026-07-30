# Signup API (DEMO-1)

**Date:** 2026-07-29
**Source:** Jira DEMO-1 — "Implement signup functionality"
**Status:** Clarified (ready for planning)

## Clarifications

### Session 2026-07-29

- Q: The ticket doesn't enumerate which characters count as "special" for
  the password complexity rule, and there's no existing precedent in this
  codebase (DEMO-2's login only checks presence, not complexity). This is
  a validation-detail choice, not an architectural one, so resolved
  directly rather than escalated. → A: Use the standard OWASP-style
  punctuation/symbol set: `` !@#$%^&*()_+-=[]{};':"\|,.<>/? ``.
- Q: The ticket's example response shows `userId` as a formatted string
  (`"usr_10293"`). Does this match existing precedent, or is it a plain
  numeric id? → A: Consistent with existing precedent — checked
  `LoginResponse.UserSummary`, which already formats its id as
  `"usr_" + user.getId()`. Signup's `userId` MUST use the same
  `"usr_" + id` string format, not a bare number.
- Q: This deployment has no Redis or API gateway in front of it (single
  instance per `docker-compose.yml`), and DEMO-2's lockout counters are
  plain DB columns, not a request-rate limiter. Should the per-IP signup
  limiter be a simple in-process in-memory counter, or a distributed/
  shared store for multi-instance deployments? → A: In-memory counter.

## Overview

Expose a `POST /api/v1/auth/signup` endpoint that lets a new user register
with a name, email, and password. The backend validates the payload,
enforces password complexity rules, hashes the password before persisting,
rejects duplicate emails, and rate-limits the (unauthenticated) route per
source IP. This extends the existing `login-api` project (DEMO-2, already
merged to `main`), which already has a `User` entity, a `UserRepository`, a
`users` table with a unique index on email, and a `BCryptPasswordEncoder`
bean wired in `SecurityConfig` — this ticket is the first thing that
actually creates rows in that table via a public endpoint (DEMO-2 seeded
its one test user via a Flyway migration, not an API).

## User Scenarios & Testing

### Primary Flow

1. A client submits `name`, `email`, and `password` to
   `POST /api/v1/auth/signup`.
2. The backend validates presence of all three fields, email format, and
   password complexity — before touching the database.
3. It checks whether a user with that email already exists.
4. If not, it hashes the password with BCrypt, persists a new `User` row
   (with `created_at`/`updated_at` timestamps), and returns `201 Created`
   with the new user's id.
5. If the email is already taken, it returns `409 Conflict` without
   writing anything.
6. If a single source IP exceeds 10 signup attempts within a rolling
   1-minute window, further attempts from that IP return
   `429 Too Many Requests` until the window clears.

### Acceptance Scenarios

- **Given** a well-formed signup request with an email not already in the
  system, **when** the client posts to `/api/v1/auth/signup`, **then** the
  backend hashes the password with `BCryptPasswordEncoder`, persists the
  user with `created_at`/`updated_at` set, and returns `201 Created` with
  `{"status":"success","message":"User registered successfully.","userId":"usr_<id>"}`
  — the `"usr_" + id` string format, matching `LoginResponse.UserSummary`
  (resolved in Clarifications, Session 2026-07-29).
- **Given** a signup request whose email already exists in the `users`
  table, **when** the client posts to `/api/v1/auth/signup`, **then** the
  backend makes no database write and returns `409 Conflict` with
  `{"errorCode":"EMAIL_ALREADY_EXISTS","message":"An account with this email address already exists."}`.
- **Given** a request missing `name`, `email`, or `password` (null, empty,
  or absent), **when** the client posts, **then** the backend returns
  `400 Bad Request` without querying the database, listing which fields
  are missing.
- **Given** a request whose `email` fails format validation, or whose
  `password` fails complexity rules (minimum 8 characters, at least 1
  uppercase letter, 1 number, 1 special character from the set
  `` !@#$%^&*()_+-=[]{};':"\|,.<>/? ``), **when** the client posts,
  **then** the backend returns `400 Bad Request` with every failed
  constraint listed in one response (not just the first one found).
- **Given** more than 10 signup attempts from the same source IP within a
  1-minute window, **when** the 11th+ request arrives, **then** the
  backend returns `429 Too Many Requests` without processing the payload,
  via an in-process in-memory per-IP counter (resolved in Clarifications,
  Session 2026-07-29 — resets on restart, not shared across instances).

### Edge Cases

- Two concurrent signup requests for the same not-yet-registered email
  must not both succeed — the database's unique index on `UPPER(email)`
  (already in place from DEMO-2's `V1__create_users_table.sql`) is the
  final authority; the application-level existence check is a fast-path,
  not the sole guard, and a duplicate-key violation from the DB must still
  surface as `409`, not a `500`.
- Rate-limit counting must key on the actual client IP, not a
  spoofable/relayed value, consistent with how the login lockout on DEMO-2
  keyed on the true identity rather than an attacker-controlled input.
- A rejected signup (400, 409, or 429) must never partially persist a user
  row or log the raw password.

## Functional Requirements

- **FR-1:** The system MUST expose `POST /api/v1/auth/signup` accepting
  `{"name": string, "email": string, "password": string}`.
- **FR-2:** The system MUST validate presence/non-blank of `name`, `email`,
  and `password`, returning `400` before any database query if any are
  missing.
- **FR-3:** The system MUST validate `email` against the same format rule
  already used by the login endpoint's validator, and MUST validate
  `password` against complexity rules: minimum 8 characters, at least 1
  uppercase letter, at least 1 digit, at least 1 special character from
  the set `` !@#$%^&*()_+-=[]{};':"\|,.<>/? `` (resolved in
  Clarifications, Session 2026-07-29).
- **FR-4:** On any validation failure, the system MUST return `400 Bad
  Request` listing every violated constraint in a single response, not
  just the first one encountered.
- **FR-5:** The system MUST reject signup when a user with the same email
  (case-insensitive, matching DEMO-2's `findByEmailIgnoreCase` semantics)
  already exists, returning `409 Conflict` with
  `{"errorCode":"EMAIL_ALREADY_EXISTS","message":"An account with this email address already exists."}`,
  and MUST NOT write any row in that case.
- **FR-6:** On success, the system MUST hash the raw password with
  `BCryptPasswordEncoder` (the bean already defined in `SecurityConfig`)
  before persisting it — the raw password MUST NOT be persisted or logged
  anywhere.
- **FR-7:** On success, the system MUST persist the new user with
  `created_at` and `updated_at` timestamp columns set, and MUST return
  `201 Created` with
  `{"status":"success","message":"User registered successfully.","userId":"usr_<id>"}`
  — the `"usr_" + id` string format already used by
  `LoginResponse.UserSummary.id()` (resolved in Clarifications, Session
  2026-07-29).
- **FR-8:** The system MUST enforce the existing database-level unique
  constraint on email as the final authority against duplicate accounts,
  independent of the application-level pre-check in FR-5, and MUST map a
  resulting unique-constraint violation to `409` rather than an unhandled
  `500`.
- **FR-9:** The system MUST rate-limit `POST /api/v1/auth/signup` per
  source IP, returning `429 Too Many Requests` once that IP exceeds 10
  attempts within a 1-minute rolling window, backed by a simple
  in-process in-memory counter — no Redis or other shared store (resolved
  in Clarifications, Session 2026-07-29).
- **FR-10:** The project MUST continue to use the existing stack (Java 21,
  Spring Boot 3.3.x, Maven, PostgreSQL + Flyway) and extend the existing
  `users` table (via a new Flyway migration) rather than introducing a
  separate `employee_info` table — the ticket names both as options, but
  `users` is already the established schema this codebase authenticates
  against (DEMO-2), and splitting user data across two tables would break
  that existing login flow.

## Key Entities

- **User** (existing, extended) — `id`, `email` (unique), `passwordHash`,
  `name`, `failedAttempts`, `lockedUntil` (from DEMO-2), plus new
  `createdAt` and `updatedAt` timestamp columns added by this ticket's
  migration.

## Global Constraints

- Stack: Java 21, Spring Boot 3.3.x, Maven, PostgreSQL + Flyway — same as
  the rest of this codebase (DEMO-2). No new runtime infrastructure
  (Redis, message queue, API gateway) unless a clarification concludes
  it's required for the rate limiter.
- Reuse existing conventions: `ErrorResponse` shape (`errorCode`,
  `message`, `violations`), `GlobalExceptionHandler`-style exception
  mapping, `BCryptPasswordEncoder` bean already defined in
  `SecurityConfig`, and the `users` table's existing unique index on
  `UPPER(email)`.
- Quality gates: `mvn verify` (tests + Checkstyle + SpotBugs +
  JaCoCo coverage ≥ 80%) must pass, matching DEMO-2's bar.

## Review & Acceptance Checklist

- [x] `userId` response field format confirmed (Clarifications —
      `"usr_" + id` string, matching `LoginResponse.UserSummary`)
- [x] Password "special character" set confirmed (Clarifications,
      resolved directly — validation detail, not architectural)
- [x] Rate-limiter storage backing confirmed (Clarifications — in-memory
      counter)
- [x] Target table confirmed as `users` (resolved directly — existing
      schema precedent from DEMO-2 makes `employee_info` a non-option)
- [x] All functional requirements testable and unambiguous
- [x] No implementation details leaked into requirements
