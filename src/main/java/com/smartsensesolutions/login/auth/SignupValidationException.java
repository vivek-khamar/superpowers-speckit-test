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
