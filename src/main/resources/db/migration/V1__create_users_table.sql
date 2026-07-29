-- Index on UPPER(email), matching what Spring Data JPA's IgnoreCase query
-- derivation actually generates (confirmed via EXPLAIN on DEMO-1) -- using
-- LOWER(email) here would silently full-table-scan every login attempt.
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP
);

CREATE UNIQUE INDEX ux_users_email ON users (UPPER(email));
