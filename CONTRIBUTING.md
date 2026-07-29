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
