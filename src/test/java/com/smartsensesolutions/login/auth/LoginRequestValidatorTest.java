package com.smartsensesolutions.login.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRequestValidatorTest {

    private final LoginRequestValidator validator = new LoginRequestValidator();

    @Test
    void acceptsAWellFormedRequest() {
        List<String> violations = validator.validate(new LoginRequest("ada@example.com", "anything"));

        assertThat(violations).isEmpty();
    }

    @Test
    void flagsMissingOrBlankFields() {
        List<String> violations = validator.validate(new LoginRequest(" ", null));

        assertThat(violations).containsExactlyInAnyOrder("email is required", "password is required");
    }

    @Test
    void flagsMalformedEmail() {
        List<String> violations = validator.validate(new LoginRequest("not-an-email", "anything"));

        assertThat(violations).containsExactly("email must be a valid email address");
    }
}
