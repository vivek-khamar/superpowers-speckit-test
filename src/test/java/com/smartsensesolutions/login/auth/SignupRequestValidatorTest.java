package com.smartsensesolutions.login.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SignupRequestValidatorTest {

    private final SignupRequestValidator validator = new SignupRequestValidator();

    @Test
    void validRequestHasNoViolations() {
        SignupRequest request = new SignupRequest("Ada Lovelace", "ada@example.com", "StrongPass1!");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void blankNameIsAViolation() {
        SignupRequest request = new SignupRequest(" ", "ada@example.com", "StrongPass1!");

        assertThat(validator.validate(request)).contains("name is required");
    }

    @Test
    void missingEmailIsAViolation() {
        SignupRequest request = new SignupRequest("Ada Lovelace", "", "StrongPass1!");

        assertThat(validator.validate(request)).contains("email is required");
    }

    @Test
    void malformedEmailIsAViolation() {
        SignupRequest request = new SignupRequest("Ada Lovelace", "not-an-email", "StrongPass1!");

        assertThat(validator.validate(request)).contains("email must be a valid email address");
    }

    @Test
    void missingPasswordIsAViolation() {
        SignupRequest request = new SignupRequest("Ada Lovelace", "ada@example.com", "");

        assertThat(validator.validate(request)).contains("password is required");
    }

    @Test
    void tooShortPasswordIsAViolation() {
        SignupRequest request = new SignupRequest("Ada Lovelace", "ada@example.com", "Sh1!");

        assertThat(validator.validate(request)).contains("password must be at least 8 characters");
    }

    @Test
    void passwordWithoutUppercaseIsAViolation() {
        SignupRequest request = new SignupRequest("Ada Lovelace", "ada@example.com", "weakpass1!");

        assertThat(validator.validate(request)).contains("password must contain at least 1 uppercase letter");
    }

    @Test
    void passwordWithoutDigitIsAViolation() {
        SignupRequest request = new SignupRequest("Ada Lovelace", "ada@example.com", "WeakPass!");

        assertThat(validator.validate(request)).contains("password must contain at least 1 digit");
    }

    @Test
    void passwordWithoutSpecialCharacterIsAViolation() {
        SignupRequest request = new SignupRequest("Ada Lovelace", "ada@example.com", "WeakPass1");

        assertThat(validator.validate(request))
                .anyMatch(v -> v.startsWith("password must contain at least 1 special character from"));
    }

    @Test
    void allViolationsAreReportedTogetherNotJustTheFirst() {
        SignupRequest request = new SignupRequest("", "not-an-email", "short");

        List<String> violations = validator.validate(request);

        assertThat(violations).hasSize(6);
    }
}
