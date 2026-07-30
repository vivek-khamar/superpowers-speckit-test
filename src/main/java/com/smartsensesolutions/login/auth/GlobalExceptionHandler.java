package com.smartsensesolutions.login.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(LoginValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(LoginValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.validation("Login request failed validation.", ex.getViolations()));
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ErrorResponse> handleAuthFailed(AuthenticationFailedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("AUTH_FAILED", "Invalid email or password."));
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ErrorResponse> handleLocked(AccountLockedException ex) {
        return ResponseEntity.status(HttpStatus.LOCKED)
                .body(ErrorResponse.of("ACCOUNT_LOCKED",
                        "Account temporarily locked due to too many failed attempts. Try again later."));
    }

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
}
