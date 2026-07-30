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

    // Entries are never evicted -- unbounded growth over process lifetime as
    // distinct keys accumulate is an accepted tradeoff of the in-memory design
    // (FR-9). A correctness-safe eviction needs an out-of-band sweep (inline
    // removal races with the same call's own write-back); deferred as a
    // follow-up rather than risking the rate limiter's correctness here.
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
