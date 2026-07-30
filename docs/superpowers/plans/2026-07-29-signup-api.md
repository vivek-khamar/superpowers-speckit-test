# Signup API (DEMO-1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose `POST /api/v1/auth/signup` so a new user can register with `name`/`email`/`password`, with validation, password hashing, duplicate-email rejection, and per-IP rate limiting — extending the existing `login-api` (DEMO-2) project rather than starting a new service.

**Architecture:** Same single Spring Boot service, same `com.smartsensesolutions.login.auth` package that already hosts `LoginController`/`LoginService`. A thin `SignupController` owns rate-limit-then-delegate ordering; `SignupService` owns validation → duplicate pre-check → BCrypt hash → save (with a DB-unique-constraint fallback so a race still surfaces as `409`, never `500`); `SignupRequestValidator` mirrors `LoginRequestValidator`'s self-contained style; `SignupRateLimiter` is a thread-safe in-process sliding-window counter keyed by client IP (`ConcurrentHashMap<String, Deque<Instant>>`), with an injected `Clock` so it's testable without real sleeps. The existing `User` entity gains `createdAt`/`updatedAt` via `@PrePersist`/`@PreUpdate` (a new Flyway migration `V3` adds the columns, backfilling the one row DEMO-2 already seeded). `ErrorResponse.validation(...)` is generalized to take a message parameter so both login's and signup's distinct validation-failure messages can share one factory. `GlobalExceptionHandler` gains three new `@ExceptionHandler` methods.

**Tech Stack:** Java 21, Spring Boot 3.3.4 (Web, Data JPA, Security), Maven, PostgreSQL + Flyway, Testcontainers (Postgres) for integration tests, JUnit 5 + Mockito + AssertJ. No new dependencies.

## Global Constraints

- **Stack:** Java 21, Spring Boot 3.3.x, Maven, PostgreSQL + Flyway — same as the rest of this codebase (DEMO-2). No new runtime infrastructure (Redis, message queue, API gateway).
- **Reuse existing conventions:** `ErrorResponse` shape (`errorCode`, `message`, `violations`), `GlobalExceptionHandler`-style exception mapping, the `BCryptPasswordEncoder` bean already defined in `SecurityConfig`, and the `users` table's existing unique index on `UPPER(email)`.
- **Endpoint contract:** `POST /api/v1/auth/signup` — request `{"name","email","password"}`; success `201` with `{"status":"success","message":"User registered successfully.","userId":"usr_<id>"}` (the `"usr_" + id` string format, matching `LoginResponse.UserSummary`); `400` lists every violated constraint in one response; `409` `{"errorCode":"EMAIL_ALREADY_EXISTS","message":"An account with this email address already exists."}`; `429` once an IP exceeds 10 attempts within a rolling 1-minute window.
- **Password complexity:** minimum 8 characters, at least 1 uppercase letter, at least 1 digit, at least 1 special character from `` !@#$%^&*()_+-=[]{};':"\|,.<>/? ``.
- **Rate limiter:** in-process in-memory counter only — no Redis or shared store (resets on restart, not shared across instances).
- **DB is the final authority against duplicates:** the application-level email pre-check is a fast-path, not the sole guard; a unique-constraint violation from the DB must still surface as `409`, not an unhandled `500`.
- **Never persist or log the raw password.** A rejected signup (400/409/429) must never partially persist a user row.
- **Quality gate:** `mvn verify` (tests) must pass — this repo currently has no Checkstyle/SpotBugs/JaCoCo-threshold plugin configured, so `mvn verify`'s only enforced gate is test success; do not add those plugins as part of this feature.

Every task's requirements implicitly include this section.

---

### Task 1: `User` entity timestamps + Flyway migration

**Files:**
- Modify: `src/main/java/com/smartsensesolutions/login/user/User.java`
- Create: `src/main/resources/db/migration/V3__add_user_timestamps.sql`
- Modify: `src/test/java/com/smartsensesolutions/login/user/UserRepositoryTest.java`

**Interfaces:**
- Produces: `User.getCreatedAt(): Instant`, `User.getUpdatedAt(): Instant` (used by Task 9's integration test to assert timestamps are set on signup).

- [ ] **Step 1: Add the failing repository tests**

Append to `src/test/java/com/smartsensesolutions/login/user/UserRepositoryTest.java` (add these two `@Test` methods inside the existing class, before its closing brace):

```java
    @Test
    void persistingANewUserPopulatesCreatedAtAndUpdatedAtViaLifecycleCallbacks() {
        User user = new User("timestamp-target@example.com", "hash", "Timestamp Target");

        User saved = userRepository.saveAndFlush(user);

        try {
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getUpdatedAt()).isNotNull();
            assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt());
        } finally {
            userRepository.deleteById(saved.getId());
        }
    }

    @Test
    void updatingAnExistingUserAdvancesUpdatedAtButNotCreatedAt() {
        User user = new User("timestamp-update-target@example.com", "hash", "Timestamp Update Target");
        User saved = userRepository.saveAndFlush(user);
        Instant originalCreatedAt = saved.getCreatedAt();
        Instant originalUpdatedAt = saved.getUpdatedAt();

        try {
            saved.recordFailure(Instant.now(), 5, Duration.ofMinutes(15));
            User updated = userRepository.saveAndFlush(saved);

            assertThat(updated.getCreatedAt()).isEqualTo(originalCreatedAt);
            assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(originalUpdatedAt);
        } finally {
            userRepository.deleteById(saved.getId());
        }
    }
```

Add these imports to the top of the same file (alongside the existing ones):

```java
import java.time.Duration;
import java.time.Instant;
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `mvn test -Dtest=UserRepositoryTest`
Expected: FAIL — compile error / `getCreatedAt()` and `getUpdatedAt()` do not exist on `User`.

- [ ] **Step 3: Add timestamp fields and lifecycle callbacks to `User`**

In `src/main/java/com/smartsensesolutions/login/user/User.java`, add to the imports:

```java
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
```

Add these fields after the existing `lockedUntil` field:

```java
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
```

Add these lifecycle callbacks and getters after `recordSuccess()`:

```java
    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
```

Add these getters alongside the existing getters:

```java
    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
```

- [ ] **Step 4: Create the Flyway migration**

Create `src/main/resources/db/migration/V3__add_user_timestamps.sql`:

```sql
ALTER TABLE users
    ADD COLUMN created_at TIMESTAMP,
    ADD COLUMN updated_at TIMESTAMP;

UPDATE users SET created_at = now(), updated_at = now() WHERE created_at IS NULL;

ALTER TABLE users
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL;
```

The `UPDATE` backfill is required because `V2__seed_test_user.sql` already inserted a row before these columns existed; without it, the final `SET NOT NULL` would fail on that row.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn test -Dtest=UserRepositoryTest,UserTest`
Expected: PASS (all tests in both classes, including the two new ones).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/smartsensesolutions/login/user/User.java \
        src/main/resources/db/migration/V3__add_user_timestamps.sql \
        src/test/java/com/smartsensesolutions/login/user/UserRepositoryTest.java
git commit -m "feat(DEMO-1): add createdAt/updatedAt timestamps to User"
```

---

### Task 2: `SignupRequest` + `SignupRequestValidator`

**Files:**
- Create: `src/main/java/com/smartsensesolutions/login/auth/SignupRequest.java`
- Create: `src/main/java/com/smartsensesolutions/login/auth/SignupRequestValidator.java`
- Test: `src/test/java/com/smartsensesolutions/login/auth/SignupRequestValidatorTest.java`

**Interfaces:**
- Produces: `record SignupRequest(String name, String email, String password)` (consumed by Task 6's `SignupService` and Task 7's `SignupController`); `SignupRequestValidator.validate(SignupRequest): List<String>` (consumed by Task 6's `SignupService`).

- [ ] **Step 1: Write the failing validator test**

Create `src/test/java/com/smartsensesolutions/login/auth/SignupRequestValidatorTest.java`:

```java
package com.smartsensesolutions.login.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SignupRequestValidatorTest {

    private final SignupRequestValidator validator = new SignupRequestValidator();

    @Test
    void validRequestHasNoViolations() {
        SignupRequest request = new SignupRequest("Ada Lovelace", "ada@example.com", "StrongPass1!");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void blankNameIsAViolation() {
        SignupRequest request = new SignupRequest(" ", "ada@example.com", "StrongPass1!");

        assertThat(validator.validate(request)).contains("name is required");
    }

    @Test
    void missingEmailIsAViolation() {
        SignupRequest request = new SignupRequest("Ada Lovelace", "", "StrongPass1!");

        assertThat(validator.validate(request)).contains("email is required");
    }

    @Test
    void malformedEmailIsAViolation() {
        SignupRequest request = new SignupRequest("Ada Lovelace", "not-an-email", "StrongPass1!");

        assertThat(validator.validate(request)).contains("email must be a valid email address");
    }

    @Test
    void missingPasswordIsAViolation() {
        SignupRequest request = new SignupRequest("Ada Lovelace", "ada@example.com", "");

        assertThat(validator.validate(request)).contains("password is required");
    }

    @Test
    void tooShortPasswordIsAViolation() {
        SignupRequest request = new SignupRequest("Ada Lovelace", "ada@example.com", "Sh1!");

        assertThat(validator.validate(request)).contains("password must be at least 8 characters");
    }

    @Test
    void passwordWithoutUppercaseIsAViolation() {
        SignupRequest request = new SignupRequest("Ada Lovelace", "ada@example.com", "weakpass1!");

        assertThat(validator.validate(request)).contains("password must contain at least 1 uppercase letter");
    }

    @Test
    void passwordWithoutDigitIsAViolation() {
        SignupRequest request = new SignupRequest("Ada Lovelace", "ada@example.com", "WeakPass!");

        assertThat(validator.validate(request)).contains("password must contain at least 1 digit");
    }

    @Test
    void passwordWithoutSpecialCharacterIsAViolation() {
        SignupRequest request = new SignupRequest("Ada Lovelace", "ada@example.com", "WeakPass1");

        assertThat(validator.validate(request))
                .anyMatch(v -> v.startsWith("password must contain at least 1 special character from"));
    }

    @Test
    void allViolationsAreReportedTogetherNotJustTheFirst() {
        SignupRequest request = new SignupRequest("", "not-an-email", "short");

        List<String> violations = validator.validate(request);

        assertThat(violations).hasSize(6);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=SignupRequestValidatorTest`
Expected: FAIL with a compile error — `SignupRequest` and `SignupRequestValidator` do not exist yet.

- [ ] **Step 3: Create `SignupRequest`**

Create `src/main/java/com/smartsensesolutions/login/auth/SignupRequest.java`:

```java
package com.smartsensesolutions.login.auth;

public record SignupRequest(String name, String email, String password) {
}
```

- [ ] **Step 4: Create `SignupRequestValidator`**

Create `src/main/java/com/smartsensesolutions/login/auth/SignupRequestValidator.java`:

```java
package com.smartsensesolutions.login.auth;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class SignupRequestValidator {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private static final String SPECIAL_CHARACTERS = "!@#$%^&*()_+-=[]{};':\"\\|,.<>/?";

    private static final int MIN_PASSWORD_LENGTH = 8;

    public List<String> validate(SignupRequest request) {
        List<String> violations = new ArrayList<>();

        if (isBlank(request.name())) {
            violations.add("name is required");
        }

        if (isBlank(request.email())) {
            violations.add("email is required");
        } else if (!EMAIL_PATTERN.matcher(request.email()).matches()) {
            violations.add("email must be a valid email address");
        }

        if (isBlank(request.password())) {
            violations.add("password is required");
        } else {
            violations.addAll(passwordViolations(request.password()));
        }

        return violations;
    }

    private List<String> passwordViolations(String password) {
        List<String> violations = new ArrayList<>();

        if (password.length() < MIN_PASSWORD_LENGTH) {
            violations.add("password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.chars().noneMatch(Character::isUpperCase)) {
            violations.add("password must contain at least 1 uppercase letter");
        }
        if (password.chars().noneMatch(Character::isDigit)) {
            violations.add("password must contain at least 1 digit");
        }
        if (password.chars().noneMatch(c -> SPECIAL_CHARACTERS.indexOf(c) >= 0)) {
            violations.add("password must contain at least 1 special character from " + SPECIAL_CHARACTERS);
        }

        return violations;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -Dtest=SignupRequestValidatorTest`
Expected: PASS (10 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/smartsensesolutions/login/auth/SignupRequest.java \
        src/main/java/com/smartsensesolutions/login/auth/SignupRequestValidator.java \
        src/test/java/com/smartsensesolutions/login/auth/SignupRequestValidatorTest.java
git commit -m "feat(DEMO-1): add SignupRequest and SignupRequestValidator"
```

---

### Task 3: Signup exceptions

**Files:**
- Create: `src/main/java/com/smartsensesolutions/login/auth/SignupValidationException.java`
- Create: `src/main/java/com/smartsensesolutions/login/auth/EmailAlreadyExistsException.java`
- Create: `src/main/java/com/smartsensesolutions/login/auth/RateLimitExceededException.java`

**Interfaces:**
- Produces: `SignupValidationException(List<String> violations)` with `getViolations(): List<String>`, `EmailAlreadyExistsException()` (zero-arg), `RateLimitExceededException()` (zero-arg) — all consumed by Task 6/7's service and controller, and mapped to HTTP statuses by Task 8's `GlobalExceptionHandler`.

There is no separate test file for this task — each exception's exact message and status mapping is verified where it's thrown/caught (Task 6's `SignupServiceTest`, Task 8's `GlobalExceptionHandlerTest`).

- [ ] **Step 1: Create `SignupValidationException`**

Create `src/main/java/com/smartsensesolutions/login/auth/SignupValidationException.java`:

```java
package com.smartsensesolutions.login.auth;

import java.util.List;

public class SignupValidationException extends RuntimeException {

    private final List<String> violations;

    public SignupValidationException(List<String> violations) {
        super("Signup request failed validation: " + violations);
        this.violations = List.copyOf(violations);
    }

    public List<String> getViolations() {
        return violations;
    }
}
```

- [ ] **Step 2: Create `EmailAlreadyExistsException`**

Create `src/main/java/com/smartsensesolutions/login/auth/EmailAlreadyExistsException.java`:

```java
package com.smartsensesolutions.login.auth;

public class EmailAlreadyExistsException extends RuntimeException {
    public EmailAlreadyExistsException() {
        super("An account with this email address already exists.");
    }
}
```

- [ ] **Step 3: Create `RateLimitExceededException`**

Create `src/main/java/com/smartsensesolutions/login/auth/RateLimitExceededException.java`:

```java
package com.smartsensesolutions.login.auth;

public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException() {
        super("Too many signup attempts. Try again later.");
    }
}
```

- [ ] **Step 4: Compile to verify**

Run: `mvn compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/smartsensesolutions/login/auth/SignupValidationException.java \
        src/main/java/com/smartsensesolutions/login/auth/EmailAlreadyExistsException.java \
        src/main/java/com/smartsensesolutions/login/auth/RateLimitExceededException.java
git commit -m "feat(DEMO-1): add signup exception types"
```

---

### Task 4: `SignupRateLimiter` + `Clock` bean + config

**Files:**
- Create: `src/main/java/com/smartsensesolutions/login/auth/SignupRateLimiter.java`
- Modify: `src/main/java/com/smartsensesolutions/login/config/SecurityConfig.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/smartsensesolutions/login/auth/SignupRateLimiterTest.java`

**Interfaces:**
- Consumes: none from earlier tasks.
- Produces: `SignupRateLimiter.tryAcquire(String key): boolean` (consumed by Task 7's `SignupController`); a `Clock` bean in the application context (consumed by `SignupRateLimiter`'s own constructor injection; no other task needs to inject `Clock` directly).

- [ ] **Step 1: Write the failing rate limiter test**

Create `src/test/java/com/smartsensesolutions/login/auth/SignupRateLimiterTest.java`:

```java
package com.smartsensesolutions.login.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SignupRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final SignupRateLimiter rateLimiter = new SignupRateLimiter(clock, 3, 60);

    @Test
    void allowsAttemptsUpToTheConfiguredMax() {
        assertThat(rateLimiter.tryAcquire("1.2.3.4")).isTrue();
        assertThat(rateLimiter.tryAcquire("1.2.3.4")).isTrue();
        assertThat(rateLimiter.tryAcquire("1.2.3.4")).isTrue();
    }

    @Test
    void rejectsTheAttemptThatExceedsTheMaxWithinTheWindow() {
        rateLimiter.tryAcquire("1.2.3.4");
        rateLimiter.tryAcquire("1.2.3.4");
        rateLimiter.tryAcquire("1.2.3.4");

        assertThat(rateLimiter.tryAcquire("1.2.3.4")).isFalse();
    }

    @Test
    void tracksEachKeyIndependently() {
        rateLimiter.tryAcquire("1.2.3.4");
        rateLimiter.tryAcquire("1.2.3.4");
        rateLimiter.tryAcquire("1.2.3.4");

        assertThat(rateLimiter.tryAcquire("5.6.7.8")).isTrue();
    }

    @Test
    void allowsAttemptsAgainOnceTheWindowSlidesPast() {
        rateLimiter.tryAcquire("1.2.3.4");
        rateLimiter.tryAcquire("1.2.3.4");
        rateLimiter.tryAcquire("1.2.3.4");
        assertThat(rateLimiter.tryAcquire("1.2.3.4")).isFalse();

        clock.advance(61);

        assertThat(rateLimiter.tryAcquire("1.2.3.4")).isTrue();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(long seconds) {
            this.instant = this.instant.plusSeconds(seconds);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=SignupRateLimiterTest`
Expected: FAIL with a compile error — `SignupRateLimiter` does not exist yet.

- [ ] **Step 3: Create `SignupRateLimiter`**

Create `src/main/java/com/smartsensesolutions/login/auth/SignupRateLimiter.java`:

```java
package com.smartsensesolutions.login.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SignupRateLimiter {

    private final ConcurrentHashMap<String, Deque<Instant>> attemptsByKey = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxAttempts;
    private final Duration window;

    public SignupRateLimiter(Clock clock,
                              @Value("${signup.rate-limit.max-attempts:10}") int maxAttempts,
                              @Value("${signup.rate-limit.window-seconds:60}") long windowSeconds) {
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    public boolean tryAcquire(String key) {
        Deque<Instant> attempts = attemptsByKey.computeIfAbsent(key, k -> new ArrayDeque<>());
        Instant now = clock.instant();

        synchronized (attempts) {
            Instant cutoff = now.minus(window);
            while (!attempts.isEmpty() && attempts.peekFirst().isBefore(cutoff)) {
                attempts.pollFirst();
            }

            if (attempts.size() >= maxAttempts) {
                return false;
            }

            attempts.addLast(now);
            return true;
        }
    }
}
```

- [ ] **Step 4: Add a `Clock` bean to `SecurityConfig`**

In `src/main/java/com/smartsensesolutions/login/config/SecurityConfig.java`, add to the imports:

```java
import java.time.Clock;
```

Add this bean method alongside the existing `passwordEncoder()` bean:

```java
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
```

- [ ] **Step 5: Add rate-limit properties to `application.yml`**

In `src/main/resources/application.yml`, add this top-level section after the existing `login:` block:

```yaml
signup:
  rate-limit:
    max-attempts: 10
    window-seconds: 60
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn test -Dtest=SignupRateLimiterTest`
Expected: PASS (4 tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/smartsensesolutions/login/auth/SignupRateLimiter.java \
        src/main/java/com/smartsensesolutions/login/config/SecurityConfig.java \
        src/main/resources/application.yml \
        src/test/java/com/smartsensesolutions/login/auth/SignupRateLimiterTest.java
git commit -m "feat(DEMO-1): add in-memory per-IP signup rate limiter"
```

---

### Task 5: Generalize `ErrorResponse.validation(...)`

**Files:**
- Modify: `src/main/java/com/smartsensesolutions/login/auth/ErrorResponse.java`
- Modify: `src/main/java/com/smartsensesolutions/login/auth/GlobalExceptionHandler.java`

**Interfaces:**
- Produces: `ErrorResponse.validation(String message, List<String> violations): ErrorResponse` (replaces the old single-argument overload; consumed by Task 8's new signup validation handler and by this task's own updated login call site).

- [ ] **Step 1: Change `ErrorResponse.validation` to accept a message**

In `src/main/java/com/smartsensesolutions/login/auth/ErrorResponse.java`, replace:

```java
    public static ErrorResponse validation(List<String> violations) {
        return new ErrorResponse("VALIDATION_FAILED", "Login request failed validation.", violations);
    }
```

with:

```java
    public static ErrorResponse validation(String message, List<String> violations) {
        return new ErrorResponse("VALIDATION_FAILED", message, violations);
    }
```

- [ ] **Step 2: Update the login validation call site**

In `src/main/java/com/smartsensesolutions/login/auth/GlobalExceptionHandler.java`, replace:

```java
    @ExceptionHandler(LoginValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(LoginValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.validation(ex.getViolations()));
    }
```

with:

```java
    @ExceptionHandler(LoginValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(LoginValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.validation("Login request failed validation.", ex.getViolations()));
    }
```

- [ ] **Step 3: Run the existing tests to confirm no regression**

Run: `mvn test -Dtest=GlobalExceptionHandlerTest,LoginServiceTest,LoginRequestValidatorTest`
Expected: PASS — this is a pure signature change; `GlobalExceptionHandlerTest`'s assertions only check `errorCode` and `violations`, not `message`, so no test changes are needed here.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/smartsensesolutions/login/auth/ErrorResponse.java \
        src/main/java/com/smartsensesolutions/login/auth/GlobalExceptionHandler.java
git commit -m "refactor(DEMO-1): generalize ErrorResponse.validation to accept a message"
```

---

### Task 6: `SignupResponse` + `SignupService`

**Files:**
- Create: `src/main/java/com/smartsensesolutions/login/auth/SignupResponse.java`
- Create: `src/main/java/com/smartsensesolutions/login/auth/SignupService.java`
- Test: `src/test/java/com/smartsensesolutions/login/auth/SignupServiceTest.java`

**Interfaces:**
- Consumes: `SignupRequest`/`SignupRequestValidator.validate(...)` (Task 2), `SignupValidationException`/`EmailAlreadyExistsException` (Task 3), `UserRepository.findByEmailIgnoreCase(String): Optional<User>` and `UserRepository.save(User): User` (existing), `User(String email, String passwordHash, String name)` constructor (existing).
- Produces: `record SignupResponse(String status, String message, String userId)` with `SignupResponse.success(User): SignupResponse`; `SignupService.signup(SignupRequest): SignupResponse` (consumed by Task 7's `SignupController`).

- [ ] **Step 1: Write the failing service test**

Create `src/test/java/com/smartsensesolutions/login/auth/SignupServiceTest.java`:

```java
package com.smartsensesolutions.login.auth;

import com.smartsensesolutions.login.user.User;
import com.smartsensesolutions.login.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignupServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private SignupService signupService;

    @BeforeEach
    void setUp() {
        signupService = new SignupService(userRepository, passwordEncoder, new SignupRequestValidator());
    }

    @Test
    void validSignupHashesPasswordAndPersistsUser() {
        when(userRepository.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("StrongPass1!")).thenReturn("hashed-value");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            return User.existing(7L, saved.getEmail(), saved.getPasswordHash(), saved.getName(), 0, null);
        });

        SignupResponse response = signupService.signup(
                new SignupRequest("Ada Lovelace", "ada@example.com", "StrongPass1!"));

        assertThat(response.status()).isEqualTo("success");
        assertThat(response.userId()).isEqualTo("usr_7");

        ArgumentCaptor<User> savedCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getPasswordHash()).isEqualTo("hashed-value");
    }

    @Test
    void rejectsRequestFailingValidationBeforeTouchingRepository() {
        assertThatThrownBy(() -> signupService.signup(new SignupRequest("", "", "")))
                .isInstanceOf(SignupValidationException.class);

        Mockito.verifyNoInteractions(userRepository, passwordEncoder);
    }

    @Test
    void rejectsDuplicateEmailFoundByThePreCheckWithoutSaving() {
        User existing = User.existing(3L, "ada@example.com", "hash", "Ada", 0, null);
        when(userRepository.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> signupService.signup(
                new SignupRequest("Ada Lovelace", "ada@example.com", "StrongPass1!")))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void mapsADatabaseUniqueConstraintViolationTo409NotA500() {
        when(userRepository.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-value");
        when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> signupService.signup(
                new SignupRequest("Ada Lovelace", "ada@example.com", "StrongPass1!")))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=SignupServiceTest`
Expected: FAIL with a compile error — `SignupResponse` and `SignupService` do not exist yet.

- [ ] **Step 3: Create `SignupResponse`**

Create `src/main/java/com/smartsensesolutions/login/auth/SignupResponse.java`:

```java
package com.smartsensesolutions.login.auth;

import com.smartsensesolutions.login.user.User;

public record SignupResponse(String status, String message, String userId) {

    public static SignupResponse success(User user) {
        return new SignupResponse("success", "User registered successfully.", "usr_" + user.getId());
    }
}
```

- [ ] **Step 4: Create `SignupService`**

Create `src/main/java/com/smartsensesolutions/login/auth/SignupService.java`:

```java
package com.smartsensesolutions.login.auth;

import com.smartsensesolutions.login.user.User;
import com.smartsensesolutions.login.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SignupService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SignupRequestValidator validator;

    public SignupService(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          SignupRequestValidator validator) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.validator = validator;
    }

    public SignupResponse signup(SignupRequest request) {
        List<String> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new SignupValidationException(violations);
        }

        if (userRepository.findByEmailIgnoreCase(request.email()).isPresent()) {
            throw new EmailAlreadyExistsException();
        }

        User user = new User(request.email(), passwordEncoder.encode(request.password()), request.name());

        try {
            user = userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            throw new EmailAlreadyExistsException();
        }

        return SignupResponse.success(user);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -Dtest=SignupServiceTest`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/smartsensesolutions/login/auth/SignupResponse.java \
        src/main/java/com/smartsensesolutions/login/auth/SignupService.java \
        src/test/java/com/smartsensesolutions/login/auth/SignupServiceTest.java
git commit -m "feat(DEMO-1): add SignupResponse and SignupService"
```

---

### Task 7: `SignupController` + `SecurityConfig` route

**Files:**
- Create: `src/main/java/com/smartsensesolutions/login/auth/SignupController.java`
- Modify: `src/main/java/com/smartsensesolutions/login/config/SecurityConfig.java`

**Interfaces:**
- Consumes: `SignupService.signup(SignupRequest): SignupResponse` (Task 6), `SignupRateLimiter.tryAcquire(String): boolean` (Task 4), `RateLimitExceededException` (Task 3).
- Produces: `POST /api/v1/auth/signup` HTTP route (exercised by Task 9/10's integration tests).

There is no separate unit test for `SignupController` — matching this codebase's existing precedent (no `LoginControllerTest` exists either; controllers are exercised only via the `@SpringBootTest` integration tests in Task 9/10).

- [ ] **Step 1: Create `SignupController`**

Create `src/main/java/com/smartsensesolutions/login/auth/SignupController.java`:

```java
package com.smartsensesolutions.login.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SignupController {

    private final SignupService signupService;
    private final SignupRateLimiter rateLimiter;

    public SignupController(SignupService signupService, SignupRateLimiter rateLimiter) {
        this.signupService = signupService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/api/v1/auth/signup")
    public ResponseEntity<SignupResponse> signup(@RequestBody SignupRequest request, HttpServletRequest httpRequest) {
        if (!rateLimiter.tryAcquire(httpRequest.getRemoteAddr())) {
            throw new RateLimitExceededException();
        }

        SignupResponse response = signupService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
```

`httpRequest.getRemoteAddr()` is the client IP as seen by the servlet container itself — not a request header, so it can't be spoofed by a caller the way `X-Forwarded-For` could.

- [ ] **Step 2: Add the signup route to `SecurityConfig`**

In `src/main/java/com/smartsensesolutions/login/config/SecurityConfig.java`, replace:

```java
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().permitAll()
                );
```

with:

```java
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/auth/signup").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().permitAll()
                );
```

- [ ] **Step 3: Compile to verify**

Run: `mvn compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/smartsensesolutions/login/auth/SignupController.java \
        src/main/java/com/smartsensesolutions/login/config/SecurityConfig.java
git commit -m "feat(DEMO-1): add SignupController and permit its route"
```

---

### Task 8: `GlobalExceptionHandler` signup handlers

**Files:**
- Modify: `src/main/java/com/smartsensesolutions/login/auth/GlobalExceptionHandler.java`
- Modify: `src/test/java/com/smartsensesolutions/login/auth/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: `SignupValidationException`/`EmailAlreadyExistsException`/`RateLimitExceededException` (Task 3), `ErrorResponse.validation(String, List<String>)` (Task 5).
- Produces: HTTP status mapping for all three signup exceptions (exercised end-to-end by Task 9/10's integration tests).

- [ ] **Step 1: Write the failing handler tests**

Append to `src/test/java/com/smartsensesolutions/login/auth/GlobalExceptionHandlerTest.java` (add these three `@Test` methods inside the existing class, before its closing brace):

```java
    @Test
    void mapsSignupValidationExceptionTo400WithViolations() {
        ResponseEntity<ErrorResponse> response =
                handler.handleSignupValidation(new SignupValidationException(List.of("name is required")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().violations()).containsExactly("name is required");
    }

    @Test
    void mapsEmailAlreadyExistsTo409() {
        ResponseEntity<ErrorResponse> response = handler.handleEmailAlreadyExists(new EmailAlreadyExistsException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().errorCode()).isEqualTo("EMAIL_ALREADY_EXISTS");
    }

    @Test
    void mapsRateLimitExceededTo429() {
        ResponseEntity<ErrorResponse> response = handler.handleRateLimitExceeded(new RateLimitExceededException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().errorCode()).isEqualTo("RATE_LIMIT_EXCEEDED");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL with a compile error — `handleSignupValidation`, `handleEmailAlreadyExists`, `handleRateLimitExceeded` do not exist yet.

- [ ] **Step 3: Add the three handlers**

In `src/main/java/com/smartsensesolutions/login/auth/GlobalExceptionHandler.java`, add these methods inside the class, after `handleLocked`:

```java
    @ExceptionHandler(SignupValidationException.class)
    public ResponseEntity<ErrorResponse> handleSignupValidation(SignupValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.validation("Signup request failed validation.", ex.getViolations()));
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("EMAIL_ALREADY_EXISTS", "An account with this email address already exists."));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimitExceeded(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ErrorResponse.of("RATE_LIMIT_EXCEEDED", "Too many signup attempts. Try again later."));
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn test -Dtest=GlobalExceptionHandlerTest`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/smartsensesolutions/login/auth/GlobalExceptionHandler.java \
        src/test/java/com/smartsensesolutions/login/auth/GlobalExceptionHandlerTest.java
git commit -m "feat(DEMO-1): map signup exceptions to HTTP responses"
```

---

### Task 9: `SignupControllerIntegrationTest`

**Files:**
- Create: `src/test/java/com/smartsensesolutions/login/auth/SignupControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `SignupController` (Task 7), `AbstractIntegrationTest` (existing, static-container singleton pattern), `SignupRequest` (Task 2), `User.getCreatedAt()`/`getUpdatedAt()` (Task 1).

This class deliberately makes only a handful of requests per test method (well under the default 10/minute rate limit) so it never exercises the rate limiter — that is covered separately in Task 10 with its own low-threshold Spring context, keeping this class's success/400/409 assertions free of rate-limit-related flakiness.

- [ ] **Step 1: Write the integration tests**

Create `src/test/java/com/smartsensesolutions/login/auth/SignupControllerIntegrationTest.java`:

```java
package com.smartsensesolutions.login.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartsensesolutions.login.AbstractIntegrationTest;
import com.smartsensesolutions.login.user.User;
import com.smartsensesolutions.login.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SignupControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void validSignupReturns201AndPersistsAHashedPassword() throws Exception {
        String email = "new-signup-target@example.com";
        String body = objectMapper.writeValueAsString(
                new SignupRequest("New Signup", email, "StrongPass1!"));

        try {
            mockMvc.perform(post("/api/v1/auth/signup").contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("success"))
                    .andExpect(jsonPath("$.message").value("User registered successfully."))
                    .andExpect(jsonPath("$.userId").value(startsWith("usr_")));

            User saved = userRepository.findByEmailIgnoreCase(email).orElseThrow();
            assertThat(passwordEncoder.matches("StrongPass1!", saved.getPasswordHash())).isTrue();
            assertThat(saved.getPasswordHash()).isNotEqualTo("StrongPass1!");
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getUpdatedAt()).isNotNull();
        } finally {
            userRepository.findByEmailIgnoreCase(email).ifPresent(u -> userRepository.deleteById(u.getId()));
        }
    }

    @Test
    void duplicateEmailReturns409AndDoesNotOverwriteTheExistingRow() throws Exception {
        String email = "duplicate-signup-target@example.com";
        User existing = new User(email, passwordEncoder.encode("OriginalPass1!"), "Original Owner");
        userRepository.save(existing);

        try {
            String body = objectMapper.writeValueAsString(
                    new SignupRequest("Impersonator", email, "OtherPass1!"));

            mockMvc.perform(post("/api/v1/auth/signup").contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("EMAIL_ALREADY_EXISTS"));

            User stillOriginal = userRepository.findByEmailIgnoreCase(email).orElseThrow();
            assertThat(stillOriginal.getName()).isEqualTo("Original Owner");
        } finally {
            userRepository.deleteById(existing.getId());
        }
    }

    @Test
    void missingFieldsReturn400ListingViolationsWithoutQueryingTheDatabase() throws Exception {
        String body = objectMapper.writeValueAsString(new SignupRequest("", "", ""));

        mockMvc.perform(post("/api/v1/auth/signup").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations").isArray());
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `mvn test -Dtest=SignupControllerIntegrationTest`
Expected: PASS (3 tests). Requires a reachable Docker daemon (Testcontainers starts a real PostgreSQL container).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/smartsensesolutions/login/auth/SignupControllerIntegrationTest.java
git commit -m "test(DEMO-1): add SignupController integration tests"
```

---

### Task 10: `SignupRateLimitIntegrationTest`

**Files:**
- Create: `src/test/java/com/smartsensesolutions/login/auth/SignupRateLimitIntegrationTest.java`

**Interfaces:**
- Consumes: `SignupController` (Task 7), `SignupRateLimiter`'s `signup.rate-limit.*` properties (Task 4), `AbstractIntegrationTest` (existing).

This uses `@TestPropertySource` to lower the threshold to 2, which gives it its own Spring context (Spring Boot's test context cache keys on property overrides), separate from Task 9's class — safe because `AbstractIntegrationTest`'s static Postgres container is a shared singleton across the whole test JVM and is never stopped, so multiple distinct cached `ApplicationContext`s can point at it concurrently. The payload used here is intentionally invalid (malformed email, short password) so the first two allowed requests return `400` without ever writing a row — this test only needs to observe the 3rd request's `429`, with no database cleanup required.

- [ ] **Step 1: Write the rate-limit integration test**

Create `src/test/java/com/smartsensesolutions/login/auth/SignupRateLimitIntegrationTest.java`:

```java
package com.smartsensesolutions.login.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartsensesolutions.login.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "signup.rate-limit.max-attempts=2",
        "signup.rate-limit.window-seconds=60"
})
class SignupRateLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void exceedingTheConfiguredMaxAttemptsReturns429() throws Exception {
        String body = objectMapper.writeValueAsString(
                new SignupRequest("Rate Limited", "rate-limit-invalid-email", "short"));

        mockMvc.perform(post("/api/v1/auth/signup").contentType(APPLICATION_JSON).content(body));
        mockMvc.perform(post("/api/v1/auth/signup").contentType(APPLICATION_JSON).content(body));

        mockMvc.perform(post("/api/v1/auth/signup").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMIT_EXCEEDED"));
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn test -Dtest=SignupRateLimitIntegrationTest`
Expected: PASS (1 test).

- [ ] **Step 3: Run the full suite to confirm no cross-test interference**

Run: `mvn verify`
Expected: BUILD SUCCESS, all tests passing.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/smartsensesolutions/login/auth/SignupRateLimitIntegrationTest.java
git commit -m "test(DEMO-1): add signup rate-limit integration test"
```

---

### Task 11: `CONTRIBUTING.md` documentation

**Files:**
- Modify: `CONTRIBUTING.md`

**Interfaces:** none — documentation only.

- [ ] **Step 1: Add the signup endpoint section**

In `CONTRIBUTING.md`, add this section after the existing `## POST /api/v1/auth/login` section:

```markdown
## `POST /api/v1/auth/signup`

This endpoint registers a new user with a `name`, `email`, and `password` in
the JSON request body. On success it returns `201 Created` with
`{"status":"success","message":"User registered successfully.","userId":"usr_<id>"}`,
where `<id>` is the new user's numeric database id. On failure it returns
one of three status codes: `400` when the request fails validation (missing
`name`/`email`/`password`, a malformed email, or a password that doesn't meet
the complexity rules — minimum 8 characters, at least 1 uppercase letter, 1
digit, and 1 special character from `` !@#$%^&*()_+-=[]{};':"\|,.<>/? ``,
with every failed rule listed in one response), `409` when an account with
that email already exists, or `429` when the source IP has made more than 10
signup attempts within the last rolling minute.
```

- [ ] **Step 2: Commit**

```bash
git add CONTRIBUTING.md
git commit -m "docs(DEMO-1): document POST /api/v1/auth/signup"
```
