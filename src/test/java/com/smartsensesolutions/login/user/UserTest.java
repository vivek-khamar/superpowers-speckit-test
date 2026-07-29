package com.smartsensesolutions.login.user;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    @Test
    void isNotLockedOutWhenLockedUntilIsNull() {
        User user = new User("ada@example.com", "hash", "Ada");

        assertThat(user.isLockedOut(Instant.now())).isFalse();
    }

    @Test
    void isLockedOutWhenLockedUntilIsInTheFuture() {
        User user = User.existing(1L, "ada@example.com", "hash", "Ada", 5, Instant.now().plusSeconds(60));

        assertThat(user.isLockedOut(Instant.now())).isTrue();
    }

    @Test
    void isNotLockedOutWhenLockedUntilIsInThePast() {
        User user = User.existing(1L, "ada@example.com", "hash", "Ada", 5, Instant.now().minusSeconds(60));

        assertThat(user.isLockedOut(Instant.now())).isFalse();
    }

    @Test
    void recordFailureBelowThresholdDoesNotLock() {
        User user = User.existing(1L, "ada@example.com", "hash", "Ada", 3, null);

        user.recordFailure(Instant.now(), 5, Duration.ofMinutes(15));

        assertThat(user.getFailedAttempts()).isEqualTo(4);
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void recordFailureAtThresholdLocksForTheConfiguredDuration() {
        User user = User.existing(1L, "ada@example.com", "hash", "Ada", 4, null);
        Instant now = Instant.now();

        user.recordFailure(now, 5, Duration.ofMinutes(15));

        assertThat(user.getFailedAttempts()).isEqualTo(5);
        assertThat(user.getLockedUntil()).isEqualTo(now.plus(Duration.ofMinutes(15)));
    }

    @Test
    void recordSuccessClearsBothFailedAttemptsAndLockedUntil() {
        User user = User.existing(1L, "ada@example.com", "hash", "Ada", 5, Instant.now().plusSeconds(60));

        user.recordSuccess();

        assertThat(user.getFailedAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }
}
