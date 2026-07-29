package com.smartsensesolutions.login.auth;

public class AccountLockedException extends RuntimeException {
    public AccountLockedException() {
        super("Account temporarily locked due to too many failed attempts.");
    }
}
