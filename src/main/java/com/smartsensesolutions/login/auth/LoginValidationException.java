package com.smartsensesolutions.login.auth;

import java.util.List;

public class LoginValidationException extends RuntimeException {

    private final List<String> violations;

    public LoginValidationException(List<String> violations) {
        super("Login request failed validation: " + violations);
        this.violations = List.copyOf(violations);
    }

    public List<String> getViolations() {
        return violations;
    }
}
