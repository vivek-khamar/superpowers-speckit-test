package com.smartsensesolutions.login.auth;

public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException() {
        super("Too many signup attempts. Try again later.");
    }
}
