# Login API (DEMO-2)

**Date:** 2026-07-28
**Source:** Jira DEMO-2 — "Implement login functionality"
**Status:** Drafted (pending clarification)

## Overview

Expose a stateless authentication endpoint that verifies a user's email and
password, issues a signed JWT on success (delivered as an `HttpOnly` cookie,
not in the response body), rejects invalid credentials uniformly (no
account-enumeration signal), and locks out an identity after repeated
failures. This is a standalone project with no prior signup/user-creation
work — how (or whether) this ticket establishes the user records it
authenticates against is an open question captured below, not guessed at.

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
- **FR-10 (open):** `[NEEDS CLARIFICATION: this project has no prior
  signup/user-creation work — does this ticket also need to establish a
  minimal User entity/schema and a way to seed at least one real user for
  login to be testable end-to-end, or is user persistence assumed to
  already exist / out of scope for this ticket?]`
- **FR-11 (open):** `[NEEDS CLARIFICATION: the 5-failure count — is it a
  rolling time window (e.g. only failures within the last N minutes count
  toward 5), or unbounded-in-time consecutive failures since the last
  success, with only the resulting LOCKOUT duration being time-bound at
  15 minutes?]`
- **FR-12 (open):** `[NEEDS CLARIFICATION: lockout counter storage — the
  ticket explicitly offers two options: Redis, or an internal
  database-backed counter/flag. Neither is specified as default.]`
- **FR-13 (open):** `[NEEDS CLARIFICATION: JWT signing — secret source
  (generated at app startup vs. a configured/persisted secret) and
  algorithm (HS256 vs RS256)? Startup-generated invalidates all sessions on
  every restart.]`
- **FR-14 (open):** `[NEEDS CLARIFICATION: target tech stack — same as
  DEMO-1 (Java 21, Spring Boot 3.3.x, Maven, PostgreSQL + Flyway), or
  different? This is a separate, currently-empty repository.]`

## Key Entities

- **User** — `id`, `email` (unique), `passwordHash`, `name`. Exact
  provenance depends on FR-10.
- **JWT claims** — `sub` (user id), `roles` (list; no role/authorization
  system exists elsewhere yet, so defaulting to a single implicit role
  unless FR-10's resolution says otherwise), `iat`, `exp`.
- **Lockout State** — per-identity failure count + last-failure timestamp
  (or equivalent), storage per FR-12.

## Global Constraints

- **Framework:** Java / Spring Boot (Spring Security) — per Jira DEMO-2.
  Exact version/build tool: `[NEEDS CLARIFICATION: FR-14]`.
- **Endpoint contract:** `POST /api/v1/auth/login`, request/response
  payload shapes and cookie format exactly as specified above — per Jira
  DEMO-2.
- **Lockout:** 5 consecutive failures → `423` for 15 minutes — per Jira
  DEMO-2; exact counting semantics per FR-11, storage per FR-12.
- **JWT:** claims `sub`/`roles`/`iat`/`exp`, cookie
  `Secure; HttpOnly; SameSite=Strict; Max-Age=86400` — per Jira DEMO-2;
  signing details per FR-13.
- **Out of scope for this spec (infrastructure-level, not app-code):** the
  ticket's TLS 1.3 mandate and p95 < 200ms latency target are deployment/
  infrastructure concerns, not something this endpoint's code can itself
  guarantee or that a unit/integration test can meaningfully verify. Noted
  here rather than silently dropped; flag to whoever owns deployment
  config for this project rather than building app-level "TLS enforcement"
  code.

## Review & Acceptance Checklist

- [ ] No unresolved `[NEEDS CLARIFICATION]` markers remain
- [ ] All functional requirements are testable and unambiguous
- [ ] User persistence provenance (FR-10) is defined
- [ ] Lockout counting semantics (FR-11) and storage (FR-12) are defined
- [ ] JWT signing approach (FR-13) is defined
- [ ] Target tech stack (FR-14) is defined
- [x] Endpoint contract, payload shapes, cookie format, and status codes
      are defined
- [x] Timing-safe / uniform failure response requirement is defined
