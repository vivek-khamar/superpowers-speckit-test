package com.smartsensesolutions.login.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsValidationExceptionTo400WithViolations() {
        ResponseEntity<ErrorResponse> response =
                handler.handleValidation(new LoginValidationException(List.of("email is required")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().violations()).containsExactly("email is required");
    }

    @Test
    void mapsAuthenticationFailedTo401WithGenericMessage() {
        ResponseEntity<ErrorResponse> response = handler.handleAuthFailed(new AuthenticationFailedException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().errorCode()).isEqualTo("AUTH_FAILED");
    }

    @Test
    void mapsAccountLockedTo423() {
        ResponseEntity<ErrorResponse> response = handler.handleLocked(new AccountLockedException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(response.getBody().errorCode()).isEqualTo("ACCOUNT_LOCKED");
    }
}
