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
            boolean prunedToEmpty = false;
            while (!attempts.isEmpty() && attempts.peekFirst().isBefore(cutoff)) {
                attempts.pollFirst();
                prunedToEmpty = attempts.isEmpty();
            }

            if (prunedToEmpty) {
                attemptsByKey.remove(key, attempts);
            }

            if (attempts.size() >= maxAttempts) {
                return false;
            }

            attempts.addLast(now);
            return true;
        }
    }
}
