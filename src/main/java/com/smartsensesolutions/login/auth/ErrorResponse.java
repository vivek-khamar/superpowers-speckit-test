package com.smartsensesolutions.login.auth;

import java.util.List;

public record ErrorResponse(String errorCode, String message, List<String> violations) {

    public ErrorResponse {
        violations = List.copyOf(violations);
    }

    public static ErrorResponse of(String errorCode, String message) {
        return new ErrorResponse(errorCode, message, List.of());
    }

    public static ErrorResponse validation(String message, List<String> violations) {
        return new ErrorResponse("VALIDATION_FAILED", message, violations);
    }
}
