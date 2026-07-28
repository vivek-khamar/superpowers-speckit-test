# Login API (DEMO-2)

**Date:** 2026-07-28
**Source:** Jira DEMO-2 — "Implement login functionality"
**Status:** Clarified (ready for planning)

## Overview

Expose a stateless authentication endpoint that verifies a user's email and
password, issues a signed JWT on success (delivered as an `HttpOnly` cookie,
not in the response body), rejects invalid credentials uniformly (no
account-enumeration signal), and locks out an identity after repeated
failures. This is a standalone project with no prior signup/user-creation
work, so this ticket also includes a minimal User entity and a seeded test
user (resolved below in Clarifications) rather than assuming persistence
that doesn't exist yet.

## Clarifications

### Session 2026-07-28

- Q: What tech stack should this login API target? → A: Same as DEMO-1 —
  Java 21, Spring Boot 3.3.x, Maven, PostgreSQL + Flyway.
- Q: Since there's no existing signup flow, how should login get real user
  records to authenticate against? → A: Include a minimal User entity +
  repository + one Flyway migration seeding a fixed test user with a known
  BCrypt hash — just enough to make login testable end-to-end, without
  building a full signup flow.
- Q: The "5 times within a rolling window" lockout trigger — rolling window
  or unbounded count since last success? → A: Unbounded count since last
  success. Only the resulting lockout duration (15 minutes) is time-bound;
  the failure count itself has no separate time window.
- Q: Where should the per-identity failure counter and lockout state live —
  Redis or a database-backed counter? → A: Database-backed counter on the
  User row itself (e.g. `failed_attempts`, `locked_until` columns) — no new
  infrastructure dependency, transactionally consistent with the existing
  database.
- Q: How should the JWT be signed — HS256 with a configured secret, or
  RS256 with a generated keypair? → A: HS256 with a secret read from
  configuration (env var, with a default dev value in application.yml) —
  sessions survive restarts, no multi-service verification requirement
  exists to justify RS256's added complexity.

## User Scenarios & Testing

### Primary Flow

1. A client submits `email` and `password` to `POST /api/v1/auth/login`.
2. The backend validates the payload shape (presence, email format).
3. It looks up the user record by email and verifies the password against
   the stored hash.
4. If valid, it issues a signed JWT (claims: `sub`, `roles`, `iat`, `exp`),
   sets it as an `HttpOnly; Secure; SameSite=Strict` cookie, and returns
   `200` with the user's public profile (no token in the body).
5. If invalid, it returns a generic `401` — identical response whether the
   email doesn't exist or the password is wrong.
6. If the same identity has failed 5 times without an intervening success,
   further attempts return `423` until the lockout expires.

### Acceptance Scenarios

- **Given** a valid email + matching password, **when** the client posts to
  `/api/v1/auth/login`, **then** the backend returns `200` with
  `{"status":"success","message":"Authentication successful","user":{"id","email","name"}}`,
  and a `Set-Cookie: jwt=...; Secure; HttpOnly; SameSite=Strict; Max-Age=86400`
  header carrying a JWT signed with claims `sub` (user id), `roles`, `iat`,
  `exp`.
- **Given** an email that doesn't exist, OR a wrong password for an
  existing email, **when** the client posts to `/api/v1/auth/login`,
  **then** the backend returns `401` with
  `{"errorCode":"AUTH_FAILED","message":"Invalid email or password."}` —
  identical for both cases, with no observable difference (response
  time or body) that would let a caller distinguish "no such account"
  from "wrong password."
- **Given** `email` or `password` missing/blank, **when** the client posts,
  **then** the backend returns `400` without querying the database. Given a
  structurally malformed `email`, **then** `400` as well.
- **Given** an identity that has failed authentication 5 times without an
  intervening success, **when** it attempts to authenticate again, **then**
  the backend returns `423 Locked` without checking the password, until
  15 minutes after the 5th failure.

### Edge Cases

- A successful login resets that identity's failure count to zero.
- Concurrent login attempts for the same identity near the 5th-failure
  boundary must not both be admitted past the lockout check (the counter
  increment and the lockout check must not race) — same class of concern
  as the DB-level unique-email race in DEMO-1, but here for a shared
  mutable counter instead of an insert.
- A locked-out identity's `423` response must not reveal whether the
  identity itself is a real account (still generic enough to avoid
  enumeration) — mixing lockout state into the response is itself a
  potential account-enumeration signal if not handled carefully.
- Failed-attempt logging must mask the identity (per the ticket:
  "masked target identity") and must never log the raw password.

## Functional Requirements

- **FR-1:** The system MUST expose `POST /api/v1/auth/login` accepting
  `{"email": string, "password": string}`.
- **FR-2:** The system MUST validate presence/non-blank of both fields and
  basic email format, returning `400` before any database query if invalid.
- **FR-3:** The system MUST verify the password against the stored hash
  using the same hashing scheme as account creation (BCrypt, per DEMO-1's
  precedent) via a constant-time comparison (`BCryptPasswordEncoder.matches()`
  or equivalent) — never a direct string comparison.
- **FR-4:** On success, the system MUST issue a signed JWT with claims
  `sub`, `roles`, `iat`, `exp` (expiry consistent with the cookie's
  `Max-Age=86400`, i.e. 24 hours), delivered only via a
  `Secure; HttpOnly; SameSite=Strict` cookie — never in the JSON response
  body.
- **FR-5:** On success, the system MUST return `200` with the user's public
  profile (`id`, `email`, `name`) — never the password hash or the token
  itself in the body.
- **FR-6:** On invalid credentials (unknown email OR wrong password), the
  system MUST return an identical `401` response
  (`errorCode: AUTH_FAILED`) for both cases, and MUST NOT let response
  timing reveal which case occurred.
- **FR-7:** The system MUST track consecutive authentication failures per
  identity and return `423 Locked` once 5 failures occur without an
  intervening success, for 15 minutes from the 5th failure.
- **FR-8:** A successful authentication MUST reset that identity's failure
  counter.
- **FR-9:** The system MUST log failed-attempt security events with the
  target identity masked, and MUST NOT log raw passwords anywhere.
- **FR-10:** The system MUST include a minimal `User` entity (`id`, `email`
  unique, `passwordHash`, `name`) persisted via JPA/Hibernate, plus a
  Flyway migration seeding exactly one fixed test user with a known BCrypt
  hash, so login is testable end-to-end without a signup flow (resolved in
  Clarifications, Session 2026-07-28).
- **FR-11:** The failure count for lockout purposes MUST be an unbounded
  count of consecutive failures since the identity's last success (no
  separate time window on the counting itself) — only the resulting
  lockout duration is time-bound at 15 minutes (resolved in Clarifications,
  Session 2026-07-28).
- **FR-12:** The failure counter and lockout expiry MUST be stored as
  columns on the `User` row itself (database-backed, not Redis) (resolved
  in Clarifications, Session 2026-07-28).
- **FR-13:** The JWT MUST be signed with HS256 using a secret read from
  configuration (environment variable, with a default value for local/dev
  use in `application.yml`) (resolved in Clarifications, Session
  2026-07-28).
- **FR-14:** The project MUST use Java 21, Spring Boot 3.3.x, Maven, and
  PostgreSQL + Flyway — same stack as DEMO-1 (resolved in Clarifications,
  Session 2026-07-28).

## Key Entities

- **User** — `id`, `email` (unique), `passwordHash`, `name`,
  `failedAttempts` (int, default 0), `lockedUntil` (nullable timestamp).
  Seeded with exactly one fixed test user via Flyway.
- **JWT claims** — `sub` (user id), `roles` (list; no role/authorization
  system exists elsewhere yet, so defaulting to a single implicit role
  `["USER"]`), `iat`, `exp` (24h from `iat`, matching the cookie's
  `Max-Age`).
- **Lockout State** — folded into the `User` row itself (`failedAttempts`,
  `lockedUntil`), not a separate entity — per Clarifications.

## Global Constraints

- **Framework:** Java 21 / Spring Boot 3.3.x (Spring Security for hashing,
  JPA + Hibernate for persistence), built with Maven — per Jira DEMO-2 plus
  Clarifications, Session 2026-07-28.
- **Persistence:** PostgreSQL, schema managed via Flyway migrations
  (including the seeded test user) — per Clarifications, Session
  2026-07-28.
- **Endpoint contract:** `POST /api/v1/auth/login`, request/response
  payload shapes and cookie format exactly as specified above — per Jira
  DEMO-2.
- **Lockout:** 5 consecutive failures (unbounded count since last success)
  → `423` for 15 minutes, database-backed on the `User` row — per Jira
  DEMO-2 plus Clarifications, Session 2026-07-28.
- **JWT:** claims `sub`/`roles`/`iat`/`exp`, HS256 signed with a configured
  secret, cookie `Secure; HttpOnly; SameSite=Strict; Max-Age=86400` — per
  Jira DEMO-2 plus Clarifications, Session 2026-07-28.
- **Out of scope for this spec (infrastructure-level, not app-code):** the
  ticket's TLS 1.3 mandate and p95 < 200ms latency target are deployment/
  infrastructure concerns, not something this endpoint's code can itself
  guarantee or that a unit/integration test can meaningfully verify. Noted
  here rather than silently dropped; flag to whoever owns deployment
  config for this project rather than building app-level "TLS enforcement"
  code.

## Review & Acceptance Checklist

- [x] No unresolved `[NEEDS CLARIFICATION]` markers remain
- [x] All functional requirements are testable and unambiguous
- [x] User persistence provenance (FR-10) is defined
- [x] Lockout counting semantics (FR-11) and storage (FR-12) are defined
- [x] JWT signing approach (FR-13) is defined
- [x] Target tech stack (FR-14) is defined
- [x] Endpoint contract, payload shapes, cookie format, and status codes
      are defined
- [x] Timing-safe / uniform failure response requirement is defined
