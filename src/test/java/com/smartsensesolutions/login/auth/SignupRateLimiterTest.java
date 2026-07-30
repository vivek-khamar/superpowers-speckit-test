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
